package com.eratw.port.core.perf

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.perf.SizeModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ResidentWalker] / [PoolDecomp] 的口径单测（纯合成对象，不需要语料、不读堆）。
 *
 * 断言的是**不变量**而不是某个 MiB 读数：
 * - 身份去重：同一实例被引用两次与一次计出的字节相同；
 * - 容器：Map 的键值、List 的元素都被计入，Map 自身开销由 [ResidentWalker.mapOwnBytes] 给出；
 * - 归属：语句 `args` 与 `exprs` 池共享同一个 Expr 实例时，表达式桶只留独占部分；
 * - 原文账单：`rawBill` 的字符数/字节数就是 `Statement.raw` 的精确和。
 *
 * 全量语料读数不放在 JUnit：全量编译峰值 > 3 GiB，超过 test worker 的 `maxHeapSize = 3g`，
 * 走 `:core-vm:perfBaseline`（JavaExec 6g）把结果写进 `build/perf-report.md` §2b。
 */
class PoolDecompTest {

    private val sizer = SizeModel(4)

    /** 两个引用槽的合成节点（私有嵌套类，反射可读）。 */
    private class Pair2(val a: Any?, val b: Any?)

    @Test
    fun `同一实例被引用两次只计一次`() {
        val shared = "shared-literal-0123456789"
        val once = ResidentWalker(sizer).consume(Pair2(shared, null))
        val twice = ResidentWalker(sizer).consume(Pair2(shared, shared))
        assertEquals(once, twice, "身份去重后两次引用不应比一次多")

        val w = ResidentWalker(sizer)
        val first = w.consume(shared)
        assertEquals(0L, w.consume(shared), "同一 walker 第二次消费同一实例应为 0")
        assertEquals(SizeModel.stringBytes(shared), first)
    }

    @Test
    fun `Map 的键值与 List 元素都计入且容器开销单列`() {
        val list = listOf("v2", "v3")
        val map: Map<String, Any?> = linkedMapOf("k1" to "v1", "k2" to list)
        val w = ResidentWalker(sizer)
        val actual = w.consume(map)
        val expected = w.mapOwnBytes(map) +
            SizeModel.stringBytes("k1") + SizeModel.stringBytes("v1") +
            SizeModel.stringBytes("k2") +
            w.listOwnBytes(list) + SizeModel.stringBytes("v2") + SizeModel.stringBytes("v3")
        assertEquals(expected, actual, "Map/List 必须逐项计入，容器开销按模型单列")
    }

    @Test
    fun `字符串按 compact strings 判定 latin1 与 UTF-16`() {
        assertEquals(32L, SizeModel.stringBytes(""), "空串：32B 对象壳")
        assertEquals(56L, SizeModel.stringBytes("a".repeat(20)), "20×latin1：32+20 对齐 56")
        assertEquals(72L, SizeModel.stringBytes("あ".repeat(20)), "20×UTF-16：32+40 = 72")
    }

    @Test
    fun `共享 Expr 归语句池且不与表达式池重复计数`() {
        val shared = program(sharedExpr = true)
        val separate = program(sharedExpr = false)

        assertEquals(1, shared.exprCount)
        assertEquals(1, separate.exprCount)
        assertEquals(1, shared.statementCount)

        val a = PoolDecomp.decomposeProgram(shared, sizer)
        val b = PoolDecomp.decomposeProgram(separate, sizer)

        assertEquals(1, a.exprSharedRoots, "语句 args 里的同一 Expr 实例应被识别为共享")
        assertEquals(0, b.exprSharedRoots, "两份字面量各自独立时不应报共享")
        assertTrue(
            a.bytes < b.bytes,
            "共享实例只计一次（${a.bytes}B）应小于独立子树（${b.bytes}B）",
        )
        assertEquals(a.bytes, PoolDecomp.decomposeProgram(shared, sizer).bytes, "同一 Program 两次分解必须等值")
        assertTrue(a.bytes > shared.code.size * 4L, "分解总量必须包含 code 载荷")
    }

    @Test
    fun `原文账单是 raw 的精确和`() {
        val p = program(sharedExpr = true)
        val bill = PoolDecomp.rawBill(p)
        val raw = p.statement(0).raw
        assertEquals(1, bill.statements)
        assertEquals(raw.length.toLong(), bill.chars)
        assertEquals(SizeModel.stringBytes(raw), bill.bytes)
    }

    /** 合成一个最小 Program：1 函数 1 语句 1 指令，表达式池 1 个根。 */
    private fun program(sharedExpr: Boolean): Program {
        val b = CodeBuilder()
        val fn = b.declareFunction("MAIN", 0, 0, 4)
        val pooled = StrLiteral("pooled-literal")
        b.expr(pooled)
        val argExpr = if (sharedExpr) pooled else StrLiteral("statement-literal")
        b.statement(InstructionStmt(FunctionCode.WAIT, ExpressionArgument(argExpr), 1, "WAIT"))
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(fn)
    }
}
