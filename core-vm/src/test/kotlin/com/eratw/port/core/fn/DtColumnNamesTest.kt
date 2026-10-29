package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DT_COLUMN_NAMES 的越界口径用例（M4-M m-dtcolnames / 分歧 C）。
 *
 * 权威类体：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * 的 `DataTableColumnManagementMethod`（类体 :2008-2124，形参表 :2041-2054，
 * `GetIntValue` :2067-2084）。逐条结论：
 * - 缺表返回 -1：:2071-2074。
 * - 目标数组取法：:2078 —— 第 2 参缺省或不是 VariableTerm 时回落
 *   `exm.VEvaluator.RESULTS_ARRAY`，否则 `variableTerm.Identifier.GetArray() as string[]`。
 * - 写循环：:2079-2082 —— 上界是 `dataTable.Columns.Count`（**列数**），
 *   与目标数组长度无关；列数超过数组长度时 `array[i]` 无界写 ⇒ .NET
 *   `System.IndexOutOfRangeException`，函数整体抛错、**无返回值**（:2083 的
 *   `return dataTable.Columns.Count` 走不到）。
 * - 抛出形态是裸 CLR 异常而非 CodeEE：`FunctionMethodTerm.cs:20-23` 无 try/catch；
 *   `VariableTerm.cs:57-78`、`80-106` 与 `FixedVariableTerm.cs:71-78` 的
 *   `catch (Exception ex)` 只包住 VariableTerm 自身的取/写值，命中 IOOR 后仍是 `throw;`
 *   原样上抛，包不到函数体内部；顶层 `Process.cs:462-477` → `handleException`（:595）
 *   的**非 EmueraException 分支**（:651-660）打 `Lang.Error.HasUnexpectedError` +
 *   `exc.GetType().ToString()` + `":"` + `exc.Message` + 堆栈。
 *
 * 对照：**MAP_GETKEYS 的同类循环在原版就有显式截断**
 * （`MapGetStrMethod` 的 `if (num >= array.Length) break;` :2987-2995），
 * 移植侧 `mapGetKeys` 的两处 break 是忠实复刻，与本文件的口径不同，不要一起改。
 *
 * TODO(verify)：.NET IOOR 的**消息文本**随版本与本地化变化（"Index was outside the bounds
 * of the array." 一类），仓库内没有该行原文的引用点，故本文件只钉异常**类型**与「无返回值」，
 * 不钉文案。
 */
private class ColNamesBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("ColNamesBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

private fun colNamesCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = ColNamesBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override fun clockMillis(): Long = 1_700_000_000_000L
    }
    return BuiltinEvalContext(base, store, host)
}

private fun cnInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun aS(v: String): Value = Value.StrV(v)

class DtColumnNamesTest {

    // ==================================================================
    // 不超时：现状与原版一致（本组用例保证修 A 时没有把正常路径带偏）
    // ==================================================================

    @Test
    fun `单参形态在长度足够时写满 RESULTS 并返回列数`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("alpha"))
        cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("beta"))
        // 列数 = id + alpha + beta = 3（:2083 返回 Columns.Count）。
        assertEquals(3L, cnInt(c, "DT_COLUMN_NAMES", aS("T")))
        assertEquals("id", c.store.readStr("RESULTS", longArrayOf(0L)))
        assertEquals("alpha", c.store.readStr("RESULTS", longArrayOf(1L)))
        assertEquals("beta", c.store.readStr("RESULTS", longArrayOf(2L)))
    }

    @Test
    fun `双参形态在长度足够时写目标数组并返回列数`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("gamma"))
        val v = c.callFunctionRef(
            "DT_COLUMN_NAMES",
            listOf(StrLiteral("T"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR)),
        )
        assertEquals(2L, (v as Value.IntV).v)
        assertEquals("id", c.store.readStr("RESULTS", longArrayOf(0L)))
        assertEquals("gamma", c.store.readStr("RESULTS", longArrayOf(1L)))
    }

    @Test
    fun `缺表返回负一且不抛`() {
        val c = colNamesCtx()
        // :2071-2074：表名不在 DataDataTables 里直接 return -1，与目标数组无关。
        assertEquals(-1L, cnInt(c, "DT_COLUMN_NAMES", aS("NOPE")))
    }

    // ==================================================================
    // 分歧 C：列数超过目标数组长度 ⇒ 抛越界（旧现状是静默截断）
    // ==================================================================

    @Test
    fun `单参形态列数超 RESULTS 长度时抛越界`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        val cap = c.store.getLength("RESULTS", -1)
        assertTrue(cap > 1, "本用例需要 RESULTS 有正长度（实测 $cap）")
        for (i in 0 until cap + 2) cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("c" + i))
        // 旧实现（`if (i >= len) break`）在这里静默返回列数 ⇒ 本行红。
        assertFailsWith<IndexOutOfBoundsException> { cnInt(c, "DT_COLUMN_NAMES", aS("T")) }
    }

    @Test
    fun `双参形态列数超目标数组长度时抛越界`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        val cap = c.store.getLength("RESULTS", -1)
        for (i in 0 until cap + 2) cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("c" + i))
        assertFailsWith<IndexOutOfBoundsException> {
            c.callFunctionRef(
                "DT_COLUMN_NAMES",
                listOf(StrLiteral("T"), VarRef("RESULTS", emptyList(), operandType = OperandType.STR)),
            )
        }
    }

    @Test
    fun `越界前已写入的前面各列与原版部分写入同构`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        val cap = c.store.getLength("RESULTS", -1)
        for (i in 0 until cap + 2) cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("c" + i))
        assertFailsWith<IndexOutOfBoundsException> { cnInt(c, "DT_COLUMN_NAMES", aS("T")) }
        // 原版 :2081 一路写到 i == array.Length 才抛，前 array.Length 个槽已经落值。
        // 列 0 = id，列 k = c(k-1) ⇒ RESULTS[cap-1] = c(cap-2)。
        assertEquals("id", c.store.readStr("RESULTS", longArrayOf(0L)))
        assertEquals("c0", c.store.readStr("RESULTS", longArrayOf(1L)))
        assertEquals("c" + (cap - 2), c.store.readStr("RESULTS", longArrayOf((cap - 1).toLong())))
    }

    @Test
    fun `越界抛出不是 CodeEE 以对应裸 CLR 异常形态`() {
        val c = colNamesCtx()
        cnInt(c, "DT_CREATE", aS("T"))
        val cap = c.store.getLength("RESULTS", -1)
        for (i in 0 until cap + 2) cnInt(c, "DT_COLUMN_ADD", aS("T"), aS("c" + i))
        val thrown: Throwable? = runCatching { cnInt(c, "DT_COLUMN_NAMES", aS("T")) }.exceptionOrNull()
        // 原版无返回值（:2083 走不到）⇒ 必须抛，且抛的是 IOOR 同型异常。
        assertTrue(
            thrown is IndexOutOfBoundsException,
            "期望 IndexOutOfBoundsException，实际 ${thrown?.let { it::class.simpleName } ?: "无抛出"}",
        )
        // 原版链路没有任何把 IOOR 转成 CodeEE 的 catch（见类头行号证据）⇒ 移植侧也不许包成 CodeEE。
        assertTrue(thrown !is CodeEE, "裸 CLR 异常不许降级成 CodeEE")
    }
}
