package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 变量写入家族测试（SETVAR / VARSETEX / EXISTVAR / ARRAYMSORT / ARRAYMSORTEX）。
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:535-549`
 * （`GOLDEN_10_XMLMAP.ERB:36-52` 的调用序列）。
 * 断言值一律取自基准文本：
 * - 535 `SETVAR=1 NOW=456`
 * - 536 `VARSETEX=9,9,9`
 * - 537 `EXISTVAR=1,6,0`
 * - 540 `MSORT_KA=1,1,2,3,3`
 * - 541 `MSORT_KB=10,11,20,30,33`
 */
private class VwBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("VwBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun vwEnv(): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = VwBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store) to store
}

/** 引用参数调用：字符串 → StrLiteral，整数 → IntLiteral，VarRef 原样。 */
private fun vwRefCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is VarRef -> it
                is Long -> IntLiteral(it)
                is Int -> IntLiteral(it.toLong())
                is String -> StrLiteral(it)
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun vwIv(v: Value): Long = (v as Value.IntV).v

private fun vwRef(name: String, vararg indices: Long): VarRef =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.INT)

private fun vwRefS(name: String, vararg indices: Long): VarRef =
    VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

private fun VariableStore.vwInt1(name: String, n: Int): List<Long> =
    (0 until n).map { readInt(name, longArrayOf(it.toLong())) }

private fun VariableStore.vwSetInt1(name: String, vararg values: Long) {
    values.forEachIndexed { i, v -> writeInt(name, longArrayOf(i.toLong()), v) }
}

private fun VariableStore.vwStrOf(name: String, i: Long = 0): String =
    readStr(name, longArrayOf(i))

private fun VariableStore.vwIntOf(name: String, vararg indices: Long): Long =
    readInt(name, indices)

private fun vwDeclare(store: VariableStore, name: String, dims: IntArray, isStr: Boolean = false) {
    store.declareUserVar(DimSpec(name, isStr = isStr, dims = dims))
}

class VarWriteMethodsTest {

    // ==================================================================
    // SETVAR：基准 535
    // ==================================================================

    @Test
    fun `SETVAR 复现基准 535 行`() {
        val (ctx, store) = vwEnv()
        store.writeInt("FLAG", longArrayOf(10), 123)
        assertEquals(1L, vwIv(vwRefCall(ctx, "SETVAR", "FLAG:10", 456L)))
        assertEquals(456L, store.vwIntOf("FLAG", 10))
        // 字符串目标
        store.writeStr("STR", longArrayOf(0), "旧值")
        assertEquals(1L, vwIv(vwRefCall(ctx, "SETVAR", "STR:0", "新值")))
        assertEquals("新值", store.vwStrOf("STR"))
    }

    @Test
    fun `SETVAR 二维下标写入单个元素`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "T2", intArrayOf(3, 4))
        assertEquals(1L, vwIv(vwRefCall(ctx, "SETVAR", "T2:1:2", 77L)))
        assertEquals(77L, store.vwIntOf("T2", 1, 2))
        assertEquals(0L, store.vwIntOf("T2", 1, 1))
        assertEquals(0L, store.vwIntOf("T2", 2, 2))
    }

    @Test
    fun `SETVAR 类型错误文案照抄原版的反向消息`() {
        val (ctx, store) = vwEnv()
        // spec: FunctionMethodCreator.cs:766-777 —— 字符串变量收到整数 → IsNotInt（"不是整型"）
        val e1 = assertFailsWith<CodeEE> { vwRefCall(ctx, "SETVAR", "STR:0", 5L) }
        assertTrue(e1.message!!.contains("不是整型"), "实际：${e1.message}")
        // 整数变量收到字符串 → IsNotStr（"不是字符串型"）
        val e2 = assertFailsWith<CodeEE> { vwRefCall(ctx, "SETVAR", "FLAG:0", "x") }
        assertTrue(e2.message!!.contains("不是字符串型"), "实际：${e2.message}")
        // 非常量 / 非法表达式一律 IsNotVar
        assertFailsWith<CodeEE> { vwRefCall(ctx, "SETVAR", "1+1", 1L) }
        assertFailsWith<CodeEE> { vwRefCall(ctx, "SETVAR", "GAMEBASE_TITLE", "x") }
        // 二维变量省略下标：VariableNoArgTerm.SetValue 的 MissingVarArg 口径
        vwDeclare(store, "T2", intArrayOf(3, 4))
        assertFailsWith<CodeEE> { vwRefCall(ctx, "SETVAR", "T2", 1L) }
    }

    // ==================================================================
    // VARSETEX：基准 536
    // ==================================================================

    @Test
    fun `VARSETEX 复现基准 536 行`() {
        val (ctx, store) = vwEnv()
        // GOLDEN_10_XMLMAP.ERB:38 —— VARSETEX "FLAG", 9, 0, 100, 103
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "FLAG", 9L, 0L, 100L, 103L)))
        assertEquals(listOf(9L, 9L, 9L), (100..102).map { store.vwIntOf("FLAG", it.toLong()) })
        assertEquals(0L, store.vwIntOf("FLAG", 99))
        assertEquals(0L, store.vwIntOf("FLAG", 103))
    }

    @Test
    fun `VARSETEX 字符串一维与类型错误`() {
        val (ctx, store) = vwEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "STR", "X", 1L, 0L, 3L)))
        assertEquals(listOf("X", "X", "X", ""), (0L..3L).map { store.vwStrOf("STR", it) })
        // spec: :824-826 / :861-863 —— 类型文案互换的含义同 SETVAR 家族
        val e1 = assertFailsWith<CodeEE> { vwRefCall(ctx, "VARSETEX", "STR", 5L) }
        assertTrue(e1.message!!.contains("赋值给非字符串变量"), "实际：${e1.message}")
        val e2 = assertFailsWith<CodeEE> { vwRefCall(ctx, "VARSETEX", "FLAG", "x") }
        assertTrue(e2.message!!.contains("赋值给非整数型变量"), "实际：${e2.message}")
    }

    @Test
    fun `VARSETEX 一维默认结束下标是长度`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "D5", intArrayOf(5))
        // args = (name, value, all, start) → end 默认 GetLength()
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "D5", 4L, 1L, 2L)))
        assertEquals(listOf(0L, 0L, 4L, 4L, 4L), store.vwInt1("D5", 5))
    }

    @Test
    fun `VARSETEX 二维 all 标志：1 写所有行 0 只写引用行`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "T2", intArrayOf(2, 4))
        // all=1：忽略第 0 维下标，所有行第 1..2 列 = 8
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T2:1:0", 8L, 1L, 1L, 3L)))
        assertEquals(8L, store.vwIntOf("T2", 0, 1))
        assertEquals(8L, store.vwIntOf("T2", 1, 2))
        assertEquals(0L, store.vwIntOf("T2", 0, 0))
        assertEquals(0L, store.vwIntOf("T2", 0, 3))
        // all=0：只写第 0 维 = 1 的那一行
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T2:1:0", 6L, 0L, 1L, 3L)))
        assertEquals(6L, store.vwIntOf("T2", 1, 1))
        assertEquals(8L, store.vwIntOf("T2", 0, 1))
    }

    @Test
    fun `VARSETEX 三维默认结束下标恒为 0 且单点写入交换 0 1 维`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "T3", intArrayOf(3, 3, 4))
        // spec: :819 —— 三维分支重复判断 IsArray2D，默认 end = 0，因此一个都不写
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T3:1:2:0", 5L, 0L)))
        assertEquals(0L, store.vwIntOf("T3", 2, 1, 0))
        // 显式区间：非 all 分支写 [i1, i0, j]（:919 的交换）
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T3:1:2:0", 5L, 0L, 0L, 2L)))
        assertEquals(5L, store.vwIntOf("T3", 2, 1, 0))
        assertEquals(5L, store.vwIntOf("T3", 2, 1, 1))
        assertEquals(0L, store.vwIntOf("T3", 1, 2, 0), "未交换则应在此格；默认实现必须写 T3[2,1,*]")
        // all=1：所有行/列的 j 区间
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T3:0:0:0", 9L, 1L, 1L, 3L)))
        assertEquals(9L, store.vwIntOf("T3", 0, 0, 1))
        assertEquals(9L, store.vwIntOf("T3", 2, 2, 2))
        assertEquals(0L, store.vwIntOf("T3", 0, 0, 0), "start=1 时第 0 格不应被写")
    }

    @Test
    fun `VARSETEX 区间端点按 Long 判界再截取`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "D5", intArrayOf(5))
        // 终点超过长度：裸 toInt 会把 2^32 级端点回绕，这里先按 Long 判界
        // （口径同 VariableStore.checkRangeEndpoints，d2147ec）
        val e1 = assertFailsWith<CodeEE> { vwRefCall(ctx, "VARSETEX", "D5", 1L, 0L, 3L, 9L) }
        assertTrue(e1.message!!.contains("区间终点 9 越界"), "实际：${e1.message}")
        val e2 = assertFailsWith<CodeEE> { vwRefCall(ctx, "VARSETEX", "D5", 1L, 0L, 7L, 9L) }
        assertTrue(e2.message!!.contains("区间起点 7 越界"), "实际：${e2.message}")
        // 端点可等于长度
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "D5", 1L, 0L, 0L, 5L)))
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L), store.vwInt1("D5", 5))
    }

    @Test
    fun `VARSETEX 引用下标溢出按 Int 域判界`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "T2", intArrayOf(2, 4))
        // spec: FunctionMethodCreator.cs:892 —— i0 作数组下标，C# 对 long 下标做带溢出
        // 检查的转换；裸 toInt 会把 2^32 回绕成 0 静默写错行
        val e = assertFailsWith<CodeEE> {
            vwRefCall(ctx, "VARSETEX", "T2:4294967296:0", 8L, 0L, 0L, 2L)
        }
        assertTrue(e.message!!.contains("超出 32 位整型范围"), "实际：${e.message}")
        // all=1 时 i0 从不转 Int（:876 只是求值成 long），超大下标不报错
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T2:4294967296:0", 8L, 1L, 0L, 2L)))
        assertEquals(8L, store.vwIntOf("T2", 0, 0))
        assertEquals(8L, store.vwIntOf("T2", 1, 1))
        // 官方行为复刻：i1 只作循环下界，源码显式 (int) 无溢出检查（:890）⇒ 2^32+1 截断为 1
        assertEquals(1L, vwIv(vwRefCall(ctx, "VARSETEX", "T2:0:4294967297", 6L, 0L, 0L, 2L)))
        assertEquals(6L, store.vwIntOf("T2", 0, 1))
        assertEquals(0L, store.vwIntOf("T2", 0, 2))
    }

    // ==================================================================
    // EXISTVAR：基准 537
    // ==================================================================

    @Test
    fun `EXISTVAR 复现基准 537 行`() {
        val (ctx, _) = vwEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "EXISTVAR", "FLAG")))
        assertEquals(6L, vwIv(vwRefCall(ctx, "EXISTVAR", "GAMEBASE_TITLE")))
        assertEquals(0L, vwIv(vwRefCall(ctx, "EXISTVAR", "NO_SUCH_VAR_XYZ")))
        // 第二参 0 与省略等价
        assertEquals(1L, vwIv(vwRefCall(ctx, "EXISTVAR", "FLAG", 0L)))
    }

    @Test
    fun `EXISTVAR 维数与用户变量标志位`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "T2", intArrayOf(3, 4))
        vwDeclare(store, "T3", intArrayOf(2, 3, 4))
        vwDeclare(store, "S1", intArrayOf(5), isStr = true)
        assertEquals(9L, vwIv(vwRefCall(ctx, "EXISTVAR", "T2")))   // 1 | 8
        assertEquals(17L, vwIv(vwRefCall(ctx, "EXISTVAR", "T3")))  // 1 | 16
        assertEquals(2L, vwIv(vwRefCall(ctx, "EXISTVAR", "S1")))   // 2
    }

    @Test
    fun `EXISTVAR 非零模式只判断表达式能否解析`() {
        val (ctx, _) = vwEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "EXISTVAR", "FLAG:0", 1L)))
        assertEquals(1L, vwIv(vwRefCall(ctx, "EXISTVAR", "FLAG", 2L)))
        assertEquals(0L, vwIv(vwRefCall(ctx, "EXISTVAR", "1+", 1L)))
        // 能解析但不是变量的常量也返回 1（:490-496 只要求 ReduceExpressionTerm 成功）
        assertEquals(1L, vwIv(vwRefCall(ctx, "EXISTVAR", "123", 1L)))
    }

    // ==================================================================
    // ARRAYMSORT：基准 540-541
    // ==================================================================

    @Test
    fun `ARRAYMSORT 复现基准 540 与 541 行`() {
        val (ctx, store) = vwEnv()
        store.vwSetInt1("FLAG", 3L, 1L, 2L, 1L, 3L)
        store.vwSetInt1("GLOBAL", 30L, 10L, 20L, 11L, 33L)
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORT", vwRef("FLAG"), vwRef("GLOBAL"))))
        assertEquals(listOf(1L, 1L, 2L, 3L, 3L), store.vwInt1("FLAG", 5))
        assertEquals(listOf(10L, 11L, 20L, 30L, 33L), store.vwInt1("GLOBAL", 5))
    }

    @Test
    fun `ARRAYMSORT 遇到第一个 0 截止 只排前缀`() {
        val (ctx, store) = vwEnv()
        store.vwSetInt1("FLAG", 2L, 1L)
        // FLAG:2 默认为 0 —— 排序只覆盖前两个元素
        store.vwSetInt1("GLOBAL", 20L, 10L, 99L)
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORT", vwRef("FLAG"), vwRef("GLOBAL"))))
        assertEquals(listOf(10L, 20L, 99L), store.vwInt1("GLOBAL", 3))
    }

    @Test
    fun `ARRAYMSORT 字符串键也按键截止`() {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "SKA", intArrayOf(4), isStr = true)
        vwDeclare(store, "SKB", intArrayOf(4), isStr = true)
        listOf("b", "a", "", "c").forEachIndexed { i, s ->
            store.writeStr("SKA", longArrayOf(i.toLong()), s)
        }
        listOf("B", "A", "Z", "C").forEachIndexed { i, s ->
            store.writeStr("SKB", longArrayOf(i.toLong()), s)
        }
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORT", vwRefS("SKA"), vwRefS("SKB"))))
        assertEquals(listOf("a", "b", "", "c"), (0L..3L).map { store.vwStrOf("SKA", it) })
        assertEquals(listOf("A", "B", "Z", "C"), (0L..3L).map { store.vwStrOf("SKB", it) })
    }

    @Test
    fun `ARRAYMSORT 载荷不足 键长度时返回 0`() {
        val (ctx, store) = vwEnv()
        store.vwSetInt1("FLAG", 3L, 1L, 2L, 1L, 3L)
        vwDeclare(store, "SHORT", intArrayOf(3))
        store.vwSetInt1("SHORT", 100L, 200L, 300L)
        assertEquals(0L, vwIv(vwRefCall(ctx, "ARRAYMSORT", vwRef("FLAG"), vwRef("SHORT"))))
    }

    @Test
    fun `ARRAYMSORT 参数必须是变量且键必须一维`() {
        val (ctx, store) = vwEnv()
        assertFailsWith<CodeEE> { vwRefCall(ctx, "ARRAYMSORT", "FLAG") }
        vwDeclare(store, "T2", intArrayOf(2, 4))
        assertFailsWith<CodeEE> { vwRefCall(ctx, "ARRAYMSORT", vwRef("T2"), vwRef("FLAG")) }
    }

    // ==================================================================
    // ARRAYMSORTEX
    // ==================================================================

    private fun msortExEnv(): Pair<BuiltinEvalContext, VariableStore> {
        val (ctx, store) = vwEnv()
        vwDeclare(store, "KA", intArrayOf(5))
        vwDeclare(store, "KB", intArrayOf(5))
        vwDeclare(store, "NM", intArrayOf(2), isStr = true)
        store.vwSetInt1("KA", 3L, 1L, 2L, 1L, 3L)
        store.vwSetInt1("KB", 30L, 10L, 20L, 11L, 33L)
        store.writeStr("NM", longArrayOf(0), "KA")
        store.writeStr("NM", longArrayOf(1), "KB")
        return ctx to store
    }

    @Test
    fun `ARRAYMSORTEX 字符串键名与名称数组`() {
        val (ctx, store) = msortExEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRefS("NM"))))
        assertEquals(listOf(1L, 1L, 2L, 3L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(10L, 11L, 20L, 30L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 变量引用作为键与降序`() {
        val (ctx, store) = msortExEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", vwRef("KA"), vwRefS("NM"), 0L)))
        assertEquals(listOf(3L, 3L, 2L, 1L, 1L), store.vwInt1("KA", 5))
        assertEquals(listOf(30L, 33L, 20L, 10L, 11L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 第三参空位按升序`() {
        // spec: FunctionMethodCreator.cs:569 —— `arguments.Count < 3 || arguments[2] == null ||
        // GetIntValue(exm) != 0`：空位是 null 而不是 0，必须走升序分支（老实现把 null 当 0 → 降序）。
        val (ctx, store) = msortExEnv()
        assertEquals(1L, vwIv(ctx.callFunctionRef("ARRAYMSORTEX", listOf(vwRef("KA"), vwRefS("NM"), null))))
        assertEquals(listOf(1L, 1L, 2L, 3L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(10L, 11L, 20L, 30L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX num 限定参与排序的元素数`() {
        val (ctx, store) = msortExEnv()
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRefS("NM"), 1L, 3L)))
        assertEquals(listOf(1L, 2L, 3L, 1L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(10L, 20L, 30L, 11L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 显式空位第 3 参按升序`() {
        val (ctx, store) = msortExEnv()
        // spec: FunctionMethodCreator.cs:569 ——
        // `arguments.Count < 3 || arguments[2] == null || arguments[2].GetIntValue(exm) != 0`
        // ⇒ 空位是升序；修复前 `?: 0L` 把空位当 0 走降序。
        assertEquals(
            1L,
            vwIv(ctx.callFunctionRef("ARRAYMSORTEX", listOf(vwRef("KA"), vwRefS("NM"), null))),
        )
        assertEquals(listOf(1L, 1L, 2L, 3L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(10L, 11L, 20L, 30L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 显式 num 为 0 直接返回 0 且不修改`() {
        val (ctx, store) = msortExEnv()
        assertEquals(0L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRefS("NM"), 1L, 0L)))
        assertEquals(listOf(3L, 1L, 2L, 1L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(30L, 10L, 20L, 11L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 字符串键遇空串在 num 为 -1 时放弃`() {
        val (ctx, store) = msortExEnv()
        vwDeclare(store, "KS", intArrayOf(3), isStr = true)
        store.writeStr("KS", longArrayOf(0), "b")
        store.writeStr("KS", longArrayOf(1), "")
        store.writeStr("KS", longArrayOf(2), "a")
        assertEquals(0L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", vwRefS("KS"), vwRefS("NM"))))
        assertEquals(listOf("b", "", "a"), (0L..2L).map { store.vwStrOf("KS", it) })
        assertEquals(listOf(3L, 1L, 2L, 1L, 3L), store.vwInt1("KA", 5))
        // 显式 num=3 时空串参与排序而不再放弃；只重排名称数组里的变量，键自身不自动加入
        assertEquals(1L, vwIv(vwRefCall(ctx, "ARRAYMSORTEX", vwRefS("KS"), vwRefS("NM"), 1L, 3L)))
        assertEquals(listOf("b", "", "a"), (0L..2L).map { store.vwStrOf("KS", it) })
        assertEquals(listOf(1L, 2L, 3L, 1L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(10L, 20L, 30L, 11L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 先解析全部名字再重排`() {
        val (ctx, store) = msortExEnv()
        store.writeStr("NM", longArrayOf(1), "NO_SUCH_VAR_XYZ")
        // spec: :625-630 —— list3 全部转换完才进入重排循环；第二个名字非法时 KA 不得被动过
        assertFailsWith<CodeEE> { vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRefS("NM")) }
        assertEquals(listOf(3L, 1L, 2L, 1L, 3L), store.vwInt1("KA", 5))
        assertEquals(listOf(30L, 10L, 20L, 11L, 33L), store.vwInt1("KB", 5))
    }

    @Test
    fun `ARRAYMSORTEX 名称数组必须是字符串一维`() {
        val (ctx, store) = msortExEnv()
        vwDeclare(store, "NUMNAME", intArrayOf(2))
        assertFailsWith<CodeEE> { vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRef("NUMNAME")) }
        vwDeclare(store, "NM2", intArrayOf(2, 2), isStr = true)
        assertFailsWith<CodeEE> { vwRefCall(ctx, "ARRAYMSORTEX", "KA", vwRefS("NM2")) }
    }
}
