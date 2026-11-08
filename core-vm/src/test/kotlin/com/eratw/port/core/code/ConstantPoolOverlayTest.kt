package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.ArgInterner
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R7 波 C / L10：`ConstantPool` 的**组合池**（尾段分派）出口判据。
 *
 * ## 这个测试守的是什么
 *
 * 装载期要把 base 的池与补丁的池合成一个可寻址空间，但**不能合并**：base 的语句池
 * 1,100,720 条 / 表达式池 328,661 条是惰性解码的，整池拷一份直接撞装载峰值闸。
 * 于是 `ConstantPool(basePool = ...)` 只拼「绝对下标空间」：`index < base.<x>Count` 归 base，
 * 其余按本池基线折回续段。
 *
 * 七条判据（逐条对应本文件的用例）：
 * ① 零尾段（`basePool == null`）逐位等价：计数与四个取用面与改造前完全一致。
 * ② 组合池四个计数 == base + 续段；**每一个**绝对下标都与手工拼接的期望列表逐项相等。
 * ③ 跨界三个下标（`baseCount - 1` / `baseCount` / `totalCount - 1`）逐个正确 ——
 *    `index - base` 折回最容易在这里错。
 * ④ 惰性池组合：取续段时 base 的惰性源 `statementAt` / `node` 调用次数为 **0**。
 * ⑤ 构造期不变式：四条基线与 base 计数不一致 ⇒ 抛错并点名（期望值 vs 实际值）。
 * ⑥ 构造期必须是空池（纯透传），之后只追加续段；[ConstantPool.seal] 只封本段不波及 base。
 * ⑦ [ConstantPool.releaseMmap] 对 base 是「两者都点头才关」。
 */
class ConstantPoolOverlayTest {

    // ------------------------------------------------------------------
    // 桩：计数的惰性源（判据 ④）
    // ------------------------------------------------------------------

    /** 计数的惰性语句池：记 `statementAt` 被调次数，用来证明「取续段不碰 base」。 */
    private class CountingStatementPool(private val items: List<Statement>) : LazyStatementPool {
        var statementAtCalls: Int = 0
            private set

        /** 本桩的 `releaseMmap` 答复（缺省 = 接口缺省的 false：还有回读要走通道）。 */
        var mmapReleasable: Boolean = false

        fun reset() {
            statementAtCalls = 0
        }

        override val totalCount: Int get() = items.size
        override val materializedCount: Int get() = 0

        override fun statementAt(index: Int): Statement {
            statementAtCalls++
            return items[index]
        }

        override fun releaseRaws() = Unit

        override fun seedInterning(roots: List<Expr?>): ArgInterner.Stats =
            ArgInterner.Stats(0, 0, 0, 0, 0L)

        override fun releaseMmap(): Boolean = mmapReleasable

        override fun buildFlatStatementsRange(from: Int, until: Int): FlatStatements =
            FlatStatements.encode(items.subList(from, until), emptyList())
    }

    /** 计数的惰性表达式源。 */
    private class CountingExprSource(private val nodes: List<Expr>) : LazyExprSource {
        var nodeCalls: Int = 0
            private set

        fun reset() {
            nodeCalls = 0
        }

        override val nodeCount: Int get() = nodes.size

        override fun node(index: Int): Expr {
            nodeCalls++
            return nodes[index]
        }
    }

    // ------------------------------------------------------------------
    // 夹具：base 3 long / 4 string / 2 expr / 5 statement，续段 2 / 1 / 3 / 2
    // ------------------------------------------------------------------

    /** 判据 ⑤ 的一条反例：哪条基线错、期望值与实际值、以及构造它的动作。 */
    private class MismatchCase(
        val name: String,
        val expected: Int,
        val actual: Int,
        val make: () -> ConstantPool,
    )

    private class Overlay {
        val baseLongs = listOf(100L, 101L, 102L)
        val baseStrings = listOf("base-0", "base-1", "base-2", "base-3")
        val baseExprs: List<Expr> = listOf(IntLiteral(1L), IntLiteral(2L))
        val baseStmts: List<Statement> = (0 until 5).map { RendStmt(VoidArgument(), it, "REND") }

        val patchLongs = listOf(200L, 201L)
        val patchStrings = listOf("patch-0")
        val patchExprs: List<Expr> = listOf(IntLiteral(11L), IntLiteral(12L), IntLiteral(13L))
        val patchStmts: List<Statement> = (0 until 2).map { RendStmt(VoidArgument(), 100 + it, "REND") }

        val base: ConstantPool = ConstantPool().apply {
            baseLongs.forEach { addLong(it) }
            baseStrings.forEach { addString(it) }
            baseExprs.forEach { addExpr(it) }
            baseStmts.forEach { addStatement(it) }
        }

        /** 组合池：构造期为空，续段条目在构造之后追加（判据 ⑥ 的口径）。 */
        val composite: ConstantPool = ConstantPool(
            longBase = baseLongs.size,
            stringBase = baseStrings.size,
            exprBase = baseExprs.size,
            statementBase = baseStmts.size,
            basePool = base,
        ).apply {
            patchLongs.forEach { addLong(it) }
            patchStrings.forEach { addString(it) }
            patchExprs.forEach { addExpr(it) }
            patchStmts.forEach { addStatement(it) }
        }

        val expectedLongs: List<Long> get() = baseLongs + patchLongs
        val expectedStrings: List<String> get() = baseStrings + patchStrings
        val expectedExprs: List<Expr> get() = baseExprs + patchExprs
        val expectedStmts: List<Statement> get() = baseStmts + patchStmts
    }

    // ------------------------------------------------------------------
    // 判据 ①：零尾段逐位等价
    // ------------------------------------------------------------------

    @Test
    fun `零尾段：普通池的计数与四个取用面逐位不变`() {
        val pool = ConstantPool()
        val l0 = pool.addLong(11L)
        val l1 = pool.addLong(22L)
        val s0 = pool.addString("a")
        val s1 = pool.addString("b")
        val e0 = pool.addExpr(IntLiteral(7L))
        val e1 = pool.addExpr(IntLiteral(8L))
        val t0 = RendStmt(VoidArgument(), 1, "REND")
        val t1 = RendStmt(VoidArgument(), 2, "REND")
        val i0 = pool.addStatement(t0)
        val i1 = pool.addStatement(t1)

        assertEquals(listOf(0, 1), listOf(l0, l1), "无基线时 addLong 从 0 起")
        assertEquals(listOf(0, 1), listOf(s0, s1), "无基线时 addString 从 0 起")
        assertEquals(listOf(0, 1), listOf(e0, e1), "无基线时 addExpr 从 0 起")
        assertEquals(listOf(0, 1), listOf(i0, i1), "无基线时 addStatement 从 0 起")

        assertEquals(2, pool.longCount)
        assertEquals(2, pool.stringCount)
        assertEquals(2, pool.exprCount)
        assertEquals(2, pool.statementCount)

        for (i in 0 until pool.longCount) assertEquals(listOf(11L, 22L)[i], pool.longAt(i))
        for (i in 0 until pool.stringCount) assertEquals(listOf("a", "b")[i], pool.stringAt(i))
        for (i in 0 until pool.exprCount) assertEquals(listOf<Expr>(IntLiteral(7L), IntLiteral(8L))[i], pool.exprAt(i))
        for (i in 0 until pool.statementCount) assertSame(listOf(t0, t1)[i], pool.statementAt(i))

        // 越界仍抛 IndexOutOfBounds（既有口径，不静默回落）。
        assertFailsWith<IndexOutOfBoundsException> { pool.longAt(2) }
        assertFailsWith<IndexOutOfBoundsException> { pool.statementAt(-1) }
    }

    // ------------------------------------------------------------------
    // 判据 ②：组合池四个计数 + 逐下标取用
    // ------------------------------------------------------------------

    @Test
    fun `组合池：四个计数等于 base 加续段`() {
        val o = Overlay()
        assertEquals(5, o.composite.longCount, "3 base + 2 续段")
        assertEquals(5, o.composite.stringCount, "4 base + 1 续段")
        assertEquals(5, o.composite.exprCount, "2 base + 3 续段")
        assertEquals(7, o.composite.statementCount, "5 base + 2 续段")
    }

    @Test
    fun `组合池：每个绝对下标都与手工拼接的期望列表逐项相等`() {
        val o = Overlay()
        val longs = o.expectedLongs
        for (i in 0 until o.composite.longCount) {
            assertEquals(longs[i], o.composite.longAt(i), "long 绝对下标 $i")
        }
        val strings = o.expectedStrings
        for (i in 0 until o.composite.stringCount) {
            assertEquals(strings[i], o.composite.stringAt(i), "string 绝对下标 $i")
        }
        val exprs = o.expectedExprs
        for (i in 0 until o.composite.exprCount) {
            assertEquals(exprs[i], o.composite.exprAt(i), "expr 绝对下标 $i")
        }
        val stmts = o.expectedStmts
        for (i in 0 until o.composite.statementCount) {
            assertSame(stmts[i], o.composite.statementAt(i), "statement 绝对下标 $i（按身份）")
        }
    }

    // ------------------------------------------------------------------
    // 判据 ③：跨界点专测
    // ------------------------------------------------------------------

    @Test
    fun `组合池：跨界三个下标逐个正确`() {
        val o = Overlay()

        fun probes(baseCount: Int, total: Int): List<Int> = listOf(baseCount - 1, baseCount, total - 1)

        for (i in probes(o.baseLongs.size, o.composite.longCount)) {
            assertEquals(o.expectedLongs[i], o.composite.longAt(i), "long 跨界下标 $i")
        }
        for (i in probes(o.baseStrings.size, o.composite.stringCount)) {
            assertEquals(o.expectedStrings[i], o.composite.stringAt(i), "string 跨界下标 $i")
        }
        for (i in probes(o.baseExprs.size, o.composite.exprCount)) {
            assertEquals(o.expectedExprs[i], o.composite.exprAt(i), "expr 跨界下标 $i")
        }
        for (i in probes(o.baseStmts.size, o.composite.statementCount)) {
            assertSame(o.expectedStmts[i], o.composite.statementAt(i), "statement 跨界下标 $i")
        }

        // 边界两侧确实来自不同的池（防止「全归 base」也能凑出正确答案）。
        assertSame(o.baseStmts[4], o.composite.statementAt(4), "baseCount - 1 归 base")
        assertSame(o.patchStmts[0], o.composite.statementAt(5), "baseCount 归续段")
        assertSame(o.patchStmts[1], o.composite.statementAt(6), "totalCount - 1 归续段")
    }

    // ------------------------------------------------------------------
    // 判据 ④：惰性池组合，取续段不触发 base 物化
    // ------------------------------------------------------------------

    @Test
    fun `组合池：取续段不触发 base 的惰性物化`() {
        val baseStmts = (0 until 5).map { RendStmt(VoidArgument(), it, "REND") }
        val baseExprs: List<Expr> = listOf(IntLiteral(1L), IntLiteral(2L))
        val countingStmts = CountingStatementPool(baseStmts)
        val countingExprs = CountingExprSource(baseExprs)

        val base = ConstantPool()
        base.attachLazyExpressions(countingExprs)
        base.attachLazyStatementPool(countingStmts)
        assertEquals(5, base.statementCount, "惰性档按段声明条数报数")
        assertEquals(2, base.exprCount)

        val tailStmt = RendStmt(VoidArgument(), 99, "REND")
        val tailExpr = IntLiteral(42L)
        val composite = ConstantPool(exprBase = 2, statementBase = 5, basePool = base)
        composite.addExpr(tailExpr)
        composite.addStatement(tailStmt)

        countingStmts.reset()
        countingExprs.reset()
        assertSame(tailStmt, composite.statementAt(5), "续段下标归本池")
        assertSame(tailExpr, composite.exprAt(2))
        assertEquals(0, countingStmts.statementAtCalls, "取续段不许触发 base 语句池的物化")
        assertEquals(0, countingExprs.nodeCalls, "取续段不许触发 base 表达式池的物化")

        // 反向：base 段的下标仍然归 base，而且确实会去物化 base 那一条。
        assertSame(baseStmts[4], composite.statementAt(4))
        assertEquals(1, countingStmts.statementAtCalls)
        assertSame(baseExprs[1], composite.exprAt(1))
        assertEquals(1, countingExprs.nodeCalls)
    }

    // ------------------------------------------------------------------
    // 判据 ⑤：构造期不变式（基线与 base 计数一致）
    // ------------------------------------------------------------------

    @Test
    fun `组合池：基线与 base 计数不一致构造期抛错并点名`() {
        val base = ConstantPool()
        listOf(1L, 2L, 3L).forEach { base.addLong(it) }
        listOf("a", "b", "c", "d").forEach { base.addString(it) }
        listOf<Expr>(IntLiteral(1L), IntLiteral(2L)).forEach { base.addExpr(it) }
        (0 until 5).forEach { base.addStatement(RendStmt(VoidArgument(), it, "REND")) }

        val cases = listOf(
            MismatchCase("longBase", 3, 2) {
                ConstantPool(longBase = 2, stringBase = 4, exprBase = 2, statementBase = 5, basePool = base)
            },
            MismatchCase("stringBase", 4, 3) {
                ConstantPool(longBase = 3, stringBase = 3, exprBase = 2, statementBase = 5, basePool = base)
            },
            MismatchCase("exprBase", 2, 1) {
                ConstantPool(longBase = 3, stringBase = 4, exprBase = 1, statementBase = 5, basePool = base)
            },
            MismatchCase("statementBase", 5, 4) {
                ConstantPool(longBase = 3, stringBase = 4, exprBase = 2, statementBase = 4, basePool = base)
            },
        )
        for (c in cases) {
            val ex = assertFailsWith<IllegalArgumentException>("${c.name} 与 base 计数不一致必须抛错") { c.make() }
            val msg = ex.message ?: ""
            assertTrue(msg.contains(c.name), "${c.name} 的错误消息必须点名参数，实际: $msg")
            assertTrue(msg.contains("期望 ${c.expected}"), "必须报期望值，实际: $msg")
            assertTrue(msg.contains("实际 ${c.actual}"), "必须报实际值，实际: $msg")
        }

        // 合法组合不抛（防止把检查写成恒抛）。
        ConstantPool(longBase = 3, stringBase = 4, exprBase = 2, statementBase = 5, basePool = base)
    }

    // ------------------------------------------------------------------
    // 判据 ⑥：构造期空池（纯透传）+ seal 只封本段
    // ------------------------------------------------------------------

    @Test
    fun `组合池：构造期是纯透传，seal 只封本段不波及 base`() {
        val base = ConstantPool()
        base.addLong(7L)
        base.addString("x")

        val composite = ConstantPool(longBase = 1, stringBase = 1, basePool = base)
        assertEquals(1, composite.longCount, "构造期不带任何条目：计数恰等于 base")
        assertEquals(1, composite.stringCount)
        assertEquals(7L, composite.longAt(0), "构造期就是 base 的纯透传视图")

        // 续段只允许往后追加，且返回的仍是绝对下标。
        assertEquals(1, composite.addLong(8L))
        assertEquals(2, composite.longCount)

        composite.seal()
        assertFailsWith<IllegalStateException>("seal 之后本段不可再追加") { composite.addLong(9L) }
        assertEquals(1, base.addLong(9L), "base 池没被 seal 波及：追加返回 base 的绝对下标 1")
        assertEquals(2, base.longCount, "base 池仍是 2 条")
    }

    // ------------------------------------------------------------------
    // 判据 ⑦：releaseMmap 对 base 是「两者都点头才关」
    // ------------------------------------------------------------------

    @Test
    fun `组合池：releaseMmap 要求 base 与续段都点头`() {
        val items = listOf(RendStmt(VoidArgument(), 0, "REND"))
        val stub = CountingStatementPool(items)
        val base = ConstantPool()
        base.attachLazyStatementPool(stub)

        val composite = ConstantPool(statementBase = 1, basePool = base)
        stub.mmapReleasable = false
        assertFalse(composite.releaseMmap(), "base 说通道还得留着 ⇒ 续段不许擅自关")

        stub.mmapReleasable = true
        assertTrue(composite.releaseMmap(), "两者都点头才允许关")
    }
}
