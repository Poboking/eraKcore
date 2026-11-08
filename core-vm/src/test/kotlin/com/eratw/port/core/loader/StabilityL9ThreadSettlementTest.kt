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
 * L9 必红夹具：**装载失败路径不许带着存活的解码线程离开**（CMP-11）。
 *
 * ## 现场（修复前）
 *
 * `ErbcStreamDecoder.decodeProgram` 在同一个窗口里起两条工作线程：
 * `erbc-prefetch`（段 4/6/7）与 `erbc-intern-seed`（语句实参去重）。两处 join 都写在
 * 「正常路径」上，但失败路径各有缺口：
 *
 * 1. 段 2（表达式池）抛错时，catch 只收口 `erbc-stmt3`，预取线程仍在解段 4/6/7；
 * 2. 段 3 收尾（侧车校验 / 回落扫描 / 整池逐条解码）抛错时，预取线程同样未被收口；
 * 3. 并行档的 `validate(program, flags)` 抛错时，`erbc-intern-seed` 未被收口。
 *
 * 调用方（`MappedErbc.toProgram` / `ProgramLoader.loadMappedHandle`）拿到异常后的下一帧
 * 就 `close()` 映射；被遗弃的线程继续按同一份映射读段，还占着整份已解码 `Program` 的
 * 引用与 CPU —— 失败屏（低端机 OOM 重试）恰好是它最不该来抢资源的时候。
 *
 * ## 判据（先红后绿）
 *
 * 捕获预期异常后**立即**（不 sleep、不 gc）扫 `Thread.getAllStackTraces()`，
 * 断言同名线程一条都不存活：
 *
 * - `erbc-prefetch` 夹具：100,000 个函数记录 ⇒ 预取线程在 `MappedFunctionTable.index`
 *   的索引趟里要跑几十毫秒；主线程段 2 只解 1 个节点后就在池根表上抛错，
 *   所以「抛错时预取线程还活着」是结构性事实，不是竞态。
 * - `erbc-intern-seed` 夹具：整池对照档（`loadMappedEager`）在无表达式段时走
 *   `interner.run(pool.statementView())` 的整池遍历（60,000 条语句）⇒ 校验在第一条
 *   （入口函数下标越界）立刻抛错时去重线程必在遍历中。
 */
class StabilityL9ThreadSettlementTest {

    private companion object {
        const val PREFETCH = "erbc-prefetch"
        const val INTERN = "erbc-intern-seed"
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 段 2 极小（一个表达式根）+ 段 4 极大（[functions] 个函数）：主线程在段 2 的池根表上
     * 立刻抛错，预取线程此时正卡在函数表索引趟里。
     */
    private fun programWithManyFunctions(functions: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        val root = b.expr(IntLiteral(1L, 1, 1))
        b.emit(Opcode._EVAL_INT, root, SlotAddr.local(0), 0, 1)
        for (i in 0 until functions) b.declareFunction("f$i", 0, 0, 0)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /**
     * 无表达式段（`exprCount == 0` ⇒ 写侧不落段 2）+ [statements] 条语句：整池对照档
     * 走到 `internStatementArgs` 的整池遍历分支，入口函数下标一越界就去重线程仍在跑。
     */
    private fun programWithoutExpressions(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
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

    /** 段 4 头部 `entryFunction` 字段的绝对偏移（段头 = funcCount, entryFunction, globalSlots）。 */
    private fun entryFunctionPos(file: Path): Int =
        ProgramLoader.mapped(file).use { m ->
            val from = m.header.offsets[ErbcFormat.SECTION_FUNCTIONS]
            assertTrue(from > 0, "样本必须带函数表段（段 4）")
            from + 4
        }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    fun `段2池根越界后不得留下存活的 erbc-prefetch 线程`(@TempDir dir: Path) {
        val options = ErbcEncodeOptions(version = ErbcFormat.VERSION_2)
        val bytes = ErbcWriter.encode(programWithManyFunctions(functions = 100_000), options)
        val file = dir.resolve("prefetch-abandon.erbc")
        Files.write(file, bytes)

        val rootPos = exprRootPos(file, bytes)
        val bad = bytes.copyOf().also { writeLe32(it, rootPos, 0x7FFFFFFF) }
        Files.write(file, bad)

        val e = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(file) }
        assertTrue(
            e.message!!.contains("表达式根下标越界"),
            "夹具必须打中段 2 的池根表（期望「表达式根下标越界」）：${e.message}",
        )

        // 关键断言：立即查，不许 sleep（弃线程此刻一定还在函数表索引趟里）
        assertNoLiveThread(PREFETCH, "段 2 失败后")
    }

    @Test
    fun `校验失败后不得留下存活的 erbc-intern-seed 线程`(@TempDir dir: Path) {
        val options = ErbcEncodeOptions(version = ErbcFormat.VERSION_2)
        val bytes = ErbcWriter.encode(programWithoutExpressions(statements = 60_000), options)
        val file = dir.resolve("intern-abandon.erbc")
        Files.write(file, bytes)

        // 入口函数下标写成一个不可能的值：validate 的第一条判定就抛错，
        // 此刻整池去重线程刚开始遍历 60,000 条语句。
        val entryPos = entryFunctionPos(file)
        val bad = bytes.copyOf().also { writeLe32(it, entryPos, 0x7FFFFFFF) }
        Files.write(file, bad)

        val e = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMappedEager(file) }
        assertTrue(
            e.message!!.contains("入口函数下标越界"),
            "夹具必须打中 validate 的入口函数判定（期望「入口函数下标越界」）：${e.message}",
        )

        assertNoLiveThread(INTERN, "校验失败后")
    }

    // ==================================================================
    // 断言辅助
    // ==================================================================

    private fun assertNoLiveThread(name: String, whenText: String) {
        val alive = Thread.getAllStackTraces().entries.filter {
            it.key.name == name && it.key.isAlive
        }
        assertTrue(
            alive.isEmpty(),
            "$whenText 仍有 ${alive.size} 条存活的 $name 线程" +
                "（调用方紧接着 close 映射，它们会继续读已失效映射）：\n" + dump(alive),
        )
    }

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
