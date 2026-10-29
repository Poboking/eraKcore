package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 装载失败路径的三条审计缺陷（w-audit-loader）：
 *
 * 1. [ProgramLoader.loadMapped] 在解码失败时不再泄漏 `FileChannel`：失败路径没有
 *    `Program` 接管通道（惰性语句池只挂在成功返回的 `Program` 上），所以当场关；
 *    成功路径的「惰性池不关通道」语义原样保留（见 [ProgramLoader.loadMapped] 与
 *    [MappedErbc.toProgram] 的 KDoc）。
 * 2. 字符串表计数按「剩余字节数 / 每项最小 4 字节」卡上界：只按剩余字节判界时，
 *    `count = 剩余字节数` 的谎言能让 `IntArray(count + 1)` 先吃掉 4× 文件大小的堆
 *    （`OutOfMemoryError` 越过装载层的 RuntimeException 收口）。
 * 3. 非法 UTF-8 由严格解码器抛 `CharacterCodingException`（IOException 支），不是
 *    `IllegalArgumentException`：三处字符串表视图的 catch 已改成前者，两条解码路径
 *    （整读 / 流式）抛同一条 `ErbcDecodeException`，再由装载层加成 `ErbcLoadException`，
 *    从而落在 [BootFailures] 的「段损坏」分支而不是 `OTHER`。
 *
 * 「文件可删」是本波的验收口径；注意本机（Windows + JDK 17）实测：**只开着只读通道**
 * 不阻止删除/重写（`FILE_SHARE_DELETE`），只有**存活的映射**阻止重写。因此这里的删除断言
 * 只钉「失败后不长期占文件」，通道本身是否关掉由 [ProgramLoader.loadMapped] 的
 * catch/rethrow 结构保证（泄漏时 fd 会一直挂到进程结束，进程内没有可观测的计数器）。
 */
class LoaderFailureAuditTest {

    /** 样本：全局串池一项多字节 UTF-8 + 一条指令；四个池/函数表/行号表都齐。 */
    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val s = b.string(SAMPLE_STRING)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), s, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun write(
        dir: Path,
        name: String,
        bytes: ByteArray,
    ): Path {
        val f = dir.resolve(name)
        Files.write(f, bytes)
        return f
    }

    // ==================================================================
    // 1. 非法 UTF-8：同型异常 + 段损坏分类 + 失败后文件可删
    // ==================================================================

    /** 把全局串池里唯一一项的首字节改成 0xFF：任何 UTF-8 解码器都必须拒绝。 */
    private fun corruptPoolString(bytes: ByteArray): ByteArray {
        val needle = SAMPLE_STRING.toByteArray(Charsets.UTF_8)
        val at = indexOf(bytes, needle)
        require(at >= 0) { "样本产物里找不到串池原文（${SAMPLE_STRING}）：样本构造失效" }
        return bytes.copyOf().also { it[at] = 0xFF.toByte() }
    }

    private fun indexOf(
        hay: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `非法 UTF-8 两条解码路径同型同句，归段损坏，失败后文件可删`(@TempDir dir: Path) {
        val bad = corruptPoolString(ErbcWriter.encode(sampleProgram()))
        val f = write(dir, "bad-utf8.erbc", bad)

        val viaBytes = assertFailsWith<ErbcLoadException> { ProgramLoader.load(bad, "bytes.erbc") }
        val viaFile = assertFailsWith<ErbcLoadException> { ProgramLoader.loadFile(f) }
        val viaMapped = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(f) }

        assertTrue(
            viaFile.message!!.contains("不是合法 UTF-8"),
            "定位文案必须保留「字符串表第 i 项不是合法 UTF-8」：${viaFile.message}",
        )
        assertEquals(viaFile::class, viaMapped::class, "整读与流式两条路径的异常类型必须相同")
        assertEquals(viaFile.message, viaMapped.message, "整读与流式两条路径的异常消息必须逐字相同")
        assertTrue(viaBytes.message!!.contains("不是合法 UTF-8"), viaBytes.message!!)
        assertEquals(
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailures.classify(viaMapped).kind,
            "坏 UTF-8 必须归段损坏（修复前是裸 IOException ⇒ OTHER）",
        )

        // 失败路径不占文件：删掉不再需要 GC 兜底（映射窗口只阻止重写，不阻止删除）
        Files.delete(f)
        assertFalse(Files.exists(f), "loadMapped 失败后产物文件必须可删")
    }

    // ==================================================================
    // 2. 计数字段：按元素最小宽度卡上界
    // ==================================================================

    /**
     * 把段 0 的字符串表项数改成「恰好等于剩余字节数」——旧判界（`count <= remaining`）
     * 必须放行，新判界（`count <= remaining / 4`）必须当场拒绝。
     */
    @Test
    fun `字符串表计数按每项最小 4 字节卡上界，两条路径同型同句`(@TempDir dir: Path) {
        val good = ErbcWriter.encode(sampleProgram())
        // 正样本先能装载：新上界不许误伤合法产物
        assertTrue(ProgramLoader.load(good).instructionCount > 0, "正样本必须仍可装载")

        val off = ProgramLoader.probe(good, null).offsets[ErbcFormat.SECTION_STRINGS]
        assertTrue(off > 0, "样本必须带全局串池段")
        val remaining = good.size - off - 4
        val inflated = remaining
        assertTrue(inflated <= remaining, "构造的计数必须过旧判界（<= 剩余字节数）")
        assertTrue(inflated > remaining / 4, "构造的计数必须触发新判界（> 剩余字节数 / 4 项）")
        val bad = good.copyOf().also { writeLe32(it, off, inflated) }
        val f = write(dir, "inflated-count.erbc", bad)

        val viaFile = assertFailsWith<ErbcLoadException> { ProgramLoader.loadFile(f) }
        assertTrue(viaFile.message!!.contains("字符串表项数"), viaFile.message!!)
        assertTrue(
            viaFile.message!!.contains("每项") && viaFile.message!!.contains("4 字节"),
            "拒绝理由必须说明元素宽度上界（不是旧的「大于剩余字节数」）：${viaFile.message}",
        )
        val viaMapped = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(f) }
        assertEquals(viaFile::class, viaMapped::class, "整读与流式两条路径的异常类型必须相同")
        assertEquals(viaFile.message, viaMapped.message, "整读与流式两条路径的异常消息必须逐字相同")
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, BootFailures.classify(viaMapped).kind)

        // MappedErbc.stringTable 是第三条消费同一计数的入口（不上装载层的包装）
        ProgramLoader.mapped(f).use { m ->
            val e = assertFailsWith<ErbcDecodeException> { m.stringTable(ErbcFormat.SECTION_STRINGS) }
            assertTrue(e.message!!.contains("每项至少 4 字节"), e.message!!)
        }
        Files.delete(f)
    }

    // ==================================================================
    // 3. 段布局阶段失败：同样不留文件句柄
    // ==================================================================

    @Test
    fun `段布局拒绝的产物 loadMapped 失败后文件可删`(@TempDir dir: Path) {
        val good = ErbcWriter.encode(sampleProgram())
        val codeOff = ProgramLoader.probe(good, null).offsets[ErbcFormat.SECTION_CODE]
        // 段区间装不下 9,999 条指令：失败发生在 checkSectionLayout（解码之前）
        val bad = good.copyOf().also { writeLe32(it, codeOff, 9_999) }
        val f = write(dir, "bad-layout.erbc", bad)

        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(f) }
        assertTrue(e.message!!.contains("CODE 段声明 9999 条指令"), e.message!!)
        Files.delete(f)
        assertFalse(Files.exists(f), "段布局拒绝后产物文件必须可删")
    }

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private companion object {
        const val SAMPLE_STRING = "こんにちは，世界"
    }
}
