package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.SpDtColumnOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-22 的可测判据：装载侧「畸形计数不许放大分配」。
 *
 * ## 缺陷
 *
 * `DTOption 数` 是全仓唯一没传 `minBytesPerItem` 且**紧接分配**的计数点：
 * `ArrayList<DTOption>(optionCount)` 按 4 B/引用槽预留，而线上每项只有 1 B
 * ⇒ 畸形计数最多把分配放大到剩余字节的 4 倍（全量产物尺度 ≈1.3 GB），抛的是
 * `OutOfMemoryError`，失败屏误报「堆不足」而不是「产物损坏」。
 *
 * ## 判据
 *
 * 取一份**合法**的 `SP_DT_COLUMN_OPTIONS` 记录（3 个 DEFAULT 选项，线值 0），把
 * 「DTOption 数」u32 改成 `剩余字节 / 2`：
 * - 它仍满足通用的 `count <= remaining` ⇒ 旧实现会先 `ArrayList(count)` 再逐项读；
 * - 但 `count * 4 > remaining` ⇒ 新实现必须在分配前用 `minBytesPerItem = 4` 拒绝，
 *   报「至少需要 N 字节（每项 ≥ 4 字节）」的 [ErbcDecodeException]（`CORRUPT_ARTIFACT`）。
 */
class StreamAllocationGuardTest {

    /** 一条带 3 个 DEFAULT 选项的 DT_COLUMN_OPTIONS 语句（其余部分最小）。 */
    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val dt = SpDtColumnOptions(
            dt = IntLiteral(0L),
            column = IntLiteral(1L),
            options = listOf(
                SpDtColumnOptions.DTOption.DEFAULT,
                SpDtColumnOptions.DTOption.DEFAULT,
                SpDtColumnOptions.DTOption.DEFAULT,
            ),
            values = listOf(IntLiteral(1L), IntLiteral(2L), IntLiteral(3L)),
        )
        val stmt = b.statement(InstructionStmt(FunctionCode.DT_COLUMN_OPTIONS, dt, 1, "DT_COLUMN_OPTIONS"))
        b.emit(Opcode.WAIT, stmt, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(dir: Path): Pair<Path, ByteArray> {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = dir.resolve("program.erbc")
        Files.write(f, bytes)
        return f to bytes
    }

    /**
     * 定位 `DTOption 数` 的 u32：合法记录的字节形态是
     * `count=3` + 三个 0x00（DT_DEFAULT）+ `values.count=3`。
     * 返回 count 字段的绝对偏移；样本里必须恰好一处。
     */
    private fun locateOptionCount(bytes: ByteArray): Int {
        val needle = byteArrayOf(
            3, 0, 0, 0, // DTOption 数
            0, 0, 0, // 3 × DT_DEFAULT
            3, 0, 0, 0, // values 列表长度
        )
        var found = -1
        outer@ for (at in 0..bytes.size - needle.size) {
            for (k in needle.indices) if (bytes[at + k] != needle[k]) continue@outer
            if (found >= 0) {
                throw AssertionError("样本里 DTOption 计数形态不唯一（$found 与 $at），定位不可信")
            }
            found = at
        }
        check(found >= 0) { "样本里找不到 DTOption 计数形态（count=3 + 3×DT_DEFAULT + values=3）" }
        return found
    }

    private fun writeLe32(bytes: ByteArray, at: Int, v: Int) {
        for (k in 0..3) bytes[at + k] = (v ushr (8 * k)).toByte()
    }

    @Test
    fun `合法样本仍是 3 个选项（对照组，minBytesPerItem 不许误杀）`(@TempDir dir: Path) {
        val f = writeArtifact(dir).first
        val program = ProgramLoader.loadMapped(f)
        val loaded = program.statement(0).raw
        assertEquals("DT_COLUMN_OPTIONS", loaded, "对照组必须先能装载")
    }

    @Test
    fun `畸形 DTOption 计数在分配前被拒，而不是先申请 4 倍引用槽`(@TempDir dir: Path) {
        val (f, original) = writeArtifact(dir)
        val countAt = locateOptionCount(original)
        val remaining = original.size - (countAt + 4)
        assertTrue(remaining > 8, "样本剩余字节($remaining)太小，构造不出「count ≤ remaining 但 4×count > remaining」")
        val lying = remaining / 2
        assertTrue(lying <= remaining, "构造前提：count 必须仍在「剩余字节」范围内（旧实现的通用判据放行）")
        assertTrue(lying * 4L > remaining, "构造前提：4 B/槽的分配必须超界（旧实现会先 ArrayList($lying)）")

        val bad = original.copyOf()
        writeLe32(bad, countAt, lying)
        Files.write(f, bad)

        val e = assertFailsWith<ErbcFormatException>("畸形 DTOption 计数必须报解码错误，而不是 OOM/继续分配") {
            ProgramLoader.loadMapped(f)
        }
        assertTrue(
            e.message!!.contains("DTOption 数") && e.message!!.contains("每项 ≥ 4 字节"),
            "拒绝理由要指名 DTOption 计数与 4 B/项 的分配上界，实际：${e.message}",
        )
        assertEquals(
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailures.classify(e).kind,
            "拒绝之后失败屏走「产物损坏」，不许被 OOM 抢去报「堆不足」",
        )
    }

    @Test
    fun `超 128 MiB 的串表退回 8 MiB 分块（注释与实现同式）`() {
        // 全量语料语句段局部表 104,923,457 B：≤128 MiB ⇒ 整块映射（随机访问不换块）。
        assertEquals(
            104_923_457,
            stringTableWindowChunk(104_923_457),
            "104.9 MiB 的语句段局部串表必须整块映射（换成 8 MiB 分块会把装载拖到 123 s，见 MAX_TABLE_WINDOW_BYTES）",
        )
        assertEquals(WINDOW_BYTES, stringTableWindowChunk(MAX_TABLE_WINDOW_BYTES + 1))
        assertEquals(WINDOW_BYTES, stringTableWindowChunk(MAX_TABLE_WINDOW_BYTES + 12345))
        assertEquals(8 shl 20, WINDOW_BYTES, "分块窗口必须是注释承诺的 8 MiB")
        assertEquals(0, stringTableWindowChunk(0), "空表没有数据块")
    }
}
