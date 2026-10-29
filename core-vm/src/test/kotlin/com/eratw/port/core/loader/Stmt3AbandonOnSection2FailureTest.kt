package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-11 必红夹具：**段 2 抛错时，段 3 的并行解码线程必须被收口**。
 *
 * ## 现场
 *
 * `decodeProgram` 先起 `erbc-stmt3`（daemon）去解段 3 的局部串池 + 节点表，再在主线程解段 2；
 * `stmt3Thread?.join()` 写在段 2 之后。段 2 一旦抛错（畸形表达式池 / OOM / `Throwable`），
 * 异常直接出 `decodeProgram`，`join` 与 `stmt3Failure` 两行都执行不到 ——
 * 线程继续按同一份映射读段 3，而 `ProgramLoader.loadMappedHandle` 的失败路径**马上 close 通道**。
 *
 * ## 判据（先红后绿）
 *
 * 段 2 的池根表最后一个 `u32` 改成 `0x7FFFFFFF`（越界根下标）⇒ 主线程在
 * `arena.nodeAt(cur.u32())` 处抛「表达式根下标越界」。捕获异常后**立即**
 * （不 sleep、不 gc）扫 `Thread.getAllStackTraces()`：
 *
 * - 红：还有存活的 `erbc-stmt3` 线程（它没被 join 就被遗弃）；
 * - 绿：一条都没有（失败路径已 interrupt + join 收口，再 rethrow）。
 *
 * 段 2 故意只留一个根（解码极快），段 3 是 60,000 条语句 + 60,000 个节点（要跑一会儿）：
 * 主线程抛错时工作线程必然还在索引趟里 —— 这样红的不是竞态，而是结构性遗漏。
 */
class Stmt3AbandonOnSection2FailureTest {

    private companion object {
        const val THREAD_NAME = "erbc-stmt3"
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 小段 2 + 大段 3：段 2 只有一个表达式根（可被一条 `_EVAL_INT` 合法引用），
     * 段 3 有 [statements] 条带独立字符串的语句（局部串池与节点表都足够大）。
     */
    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val root = b.expr(IntLiteral(1L, 1, 1))
        val stmts = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            val line = i % 500 + 1
            stmts.add(
                b.statement(
                    InstructionStmt(
                        FunctionCode.PRINTL,
                        ExpressionArgument(StrLiteral("第 $i 行", line, 0)),
                        line,
                        "PRINTL 第 $i 行",
                    ),
                ),
            )
        }
        b.emit(Opcode._EVAL_INT, root, SlotAddr.local(0), 0, 1)
        for (k in stmts.indices) b.emit(Opcode.WAIT, stmts[k], 0, SlotAddr.NONE, k % 500 + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /** 段 2 池根表最后一项的绝对偏移（根表是段 2 正文的末尾）。 */
    private fun exprRootPos(file: Path, bytes: ByteArray): Int {
        ProgramLoader.mapped(file).use { m ->
            val from = m.header.offsets[ErbcFormat.SECTION_EXPRESSIONS]
            assertTrue(from > 0, "样本必须带表达式池段（段 2）")
            val end = m.endOf(ErbcFormat.SECTION_EXPRESSIONS)
            val poolCount = le32(bytes, from + 4)
            assertEquals(1, poolCount, "本夹具故意只放一个表达式根")
            return end - 4 * poolCount
        }
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    fun `段2池根越界后不得留下存活的 erbc-stmt3 线程`(@TempDir dir: Path) {
        val options = ErbcEncodeOptions(version = ErbcFormat.VERSION_2)
        val bytes = ErbcWriter.encode(sampleProgram(statements = 60_000), options)
        val file = dir.resolve("stmt3-abandon.erbc")
        Files.write(file, bytes)

        val rootPos = exprRootPos(file, bytes)
        // 池根 0x7FFFFFFF 越界：段 2 的 `arena.nodeAt(cur.u32())` 必抛（表达式根下标越界）
        val bad = bytes.copyOf().also { writeLe32(it, rootPos, 0x7FFFFFFF) }
        Files.write(file, bad)

        val e = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(file) }
        assertTrue(
            e.message!!.contains("表达式根下标越界"),
            "夹具必须打中段 2 的池根表（期望「表达式根下标越界」）：${e.message}",
        )

        // 关键断言：立即查，不许 sleep（线程若被遗弃，此刻一定还在跑段 3 的索引趟）
        val alive = Thread.getAllStackTraces().entries.filter {
            it.key.name == THREAD_NAME && it.key.isAlive
        }
        assertTrue(
            alive.isEmpty(),
            "段 2 失败后仍有 ${alive.size} 条存活的 $THREAD_NAME 线程" +
                "（调用方紧接着 close 通道，它们会继续读已失效映射）：\n" + dump(alive),
        )
    }

    // ==================================================================
    // 断言辅助
    // ==================================================================

    /** 失败时把每个存活线程的完整栈打出来（线程类缺陷必须能在红屏上定位）。 */
    private fun dump(alive: List<Map.Entry<Thread, Array<StackTraceElement>>>): String {
        if (alive.isEmpty()) return "（无）"
        val sb = StringBuilder()
        for ((t, st) in alive) {
            sb.append("  ").append(t).append(" state=").append(t.state).append('\n')
            for (frame in st) sb.append("    at ").append(frame).append('\n')
        }
        return sb.toString()
    }

    private fun le32(src: ByteArray, at: Int): Int =
        (src[at].toInt() and 0xFF) or
            ((src[at + 1].toInt() and 0xFF) shl 8) or
            ((src[at + 2].toInt() and 0xFF) shl 16) or
            ((src[at + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(dst: ByteArray, at: Int, value: Int) {
        dst[at] = value.toByte()
        dst[at + 1] = (value ushr 8).toByte()
        dst[at + 2] = (value ushr 16).toByte()
        dst[at + 3] = (value ushr 24).toByte()
    }
}
