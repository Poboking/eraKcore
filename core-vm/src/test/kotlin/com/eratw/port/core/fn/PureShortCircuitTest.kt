package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * PureMethods 的两条行为修正钉：
 *
 * 1. **CORE-04 ALLSAMES 短路**：C# `AllsamesMethod.GetIntValue`
 *    （FunctionMethodCreator.cs:4625-4650）逐个求值实参，不等立即 return 0
 *    （int :4630-4636 / str :4641-4646）——命中不同后余参的表达式**不再求值**，
 *    副作用与错误都不触发。移植侧引用路径（`callFunctionRef`，生产路径
 *    `FuncCall` 一律走它，Expr.kt:158/163）经 `allSamesLazy` 惰性复刻；
 *    值路径（语句层 `callFunction`）实参已被桥整体求值，保留旧口径。
 * 2. **FN-05 UNICODE 控制码警告前缀**：C# :5709-5712 把 InvalidUnicode
 *    （zhs emuera-zhs.xml:926）套进 WarnPrefix（zhs :192）打系统行，
 *    getCurrentLine == null 时回落无前缀（:5713-5716）。移植侧当前行 =
 *    `BuiltinContext.currentScriptLine`（生产由 `ProcessRuntime` 每条语句刷新）；
 *    本文件钉住未接线时的 null 回落、接线后的全形前缀，以及 [unicodeWarnPrefix]
 *    的逐字文案。
 */
class PureShortCircuitTest {

    /** 与 GroupMatchTest 同构的最小环境，外加系统行捕获与副作用计数。 */
    private class Harness : EvalContext {
        override lateinit var variables: VariableReader
        val systemLines = ArrayList<String>()
        override fun printSystemLine(message: String) {
            systemLines.add(message)
        }
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("Harness 不应被直接调用：$name")
    }

    /** [userFns]：用户 `#FUNCTION` 遮蔽内建（Builtins.kt 的 userFunctions 通道），做可观测副作用；每次被调触发 [onCall]。 */
    private fun ctx(vararg userFns: Pair<String, Value>, onCall: () -> Unit = {}): Pair<BuiltinEvalContext, Harness> {
        val store = VariableStore()
        val base = Harness()
        base.variables = store
        val fns = userFns.associate { it.first.uppercase() to { _: List<Value?> -> onCall(); it.second } }
        return BuiltinEvalContext(base, store, userFunctions = fns) to base
    }

    // ------------------------------------------------------------------
    // CORE-04：ALLSAMES 引用路径短路（FunctionMethodCreator.cs:4630-4636 / :4641-4647）
    // ------------------------------------------------------------------

    @Test
    fun `ALLSAMES int 命中不同后余参不求值`() {
        var calls = 0
        val (c, _) = ctx("SIDE" to Value.IntV(1))
        val side = FuncCall("SIDE", emptyList(), OperandType.INT)
        // 第 2 个实参（下标 1）已经不同：SIDE 与第 4 参都不该被求值
        val r = c.callFunctionRef("ALLSAMES", listOf(IntLiteral(1), IntLiteral(2), side, side))
        assertEquals(0L, (r as Value.IntV).v)
        assertEquals(0, calls, "短路后余参的表达式不得求值（C# :4634 命中即 return）")
    }

    @Test
    fun `ALLSAMES int 全同则每个实参各求值一次`() {
        var calls = 0
        val (c, _) = ctx("SIDE" to Value.IntV(1), onCall = { calls++ })
        val side = FuncCall("SIDE", emptyList(), OperandType.INT)
        val r = c.callFunctionRef("ALLSAMES", listOf(IntLiteral(1), IntLiteral(1), side))
        assertEquals(1L, (r as Value.IntV).v)
        assertEquals(1, calls, "无短路命中时逐项求值（C# 循环体 :4630-4636）")
    }

    @Test
    fun `ALLSAMES str 命中不同后余参不求值`() {
        var calls = 0
        val (c, _) = ctx("SIDES" to Value.StrV("x"))
        val side = FuncCall("SIDES", emptyList(), OperandType.STR)
        val r = c.callFunctionRef("ALLSAMES", listOf(StrLiteral("a"), StrLiteral("b"), side))
        assertEquals(0L, (r as Value.IntV).v)
        assertEquals(0, calls, "字符串分支同样短路（C# :4643-4646）")
    }

    @Test
    fun `ALLSAMES str 全同返回 1`() {
        var calls = 0
        val (c, _) = ctx("SIDES" to Value.StrV("三途之川"), onCall = { calls++ })
        val side = FuncCall("SIDES", emptyList(), OperandType.STR)
        val r = c.callFunctionRef(
            "ALLSAMES",
            listOf(StrLiteral("三途之川"), StrLiteral("三途之川"), side),
        )
        assertEquals(1L, (r as Value.IntV).v)
        assertEquals(1, calls)
    }

    /** 短路的另一面：命中不同后，余参的**类型错误**也不触发（「余参不求值」同一条语义）。 */
    @Test
    fun `ALLSAMES 引用路径短路后类型错误不触发`() {
        val (c, _) = ctx()
        val r = c.callFunctionRef(
            "ALLSAMES",
            listOf(IntLiteral(1), IntLiteral(2), StrLiteral("x")),
        )
        assertEquals(0L, (r as Value.IntV).v)
    }

    /** 值路径（语句层 callFunction / 既有测试入口）口径不变：实参整体已求值。注意值路径的
     *  比较循环同样先命中不同（`.all` 在 2≠1 处即断），类型错要在**先行位**才触发。 */
    @Test
    fun `ALLSAMES 值路径保持整体求值口径`() {
        val (c, _) = ctx()
        assertEquals(
            "ALLSAMES函数: 第2个参数不是整数",
            assertFailsWith<CodeEE> {
                c.callFunction("ALLSAMES", listOf(Value.IntV(1), Value.StrV("x"), Value.IntV(2)))
            }.message,
        )
        assertEquals(
            0L,
            (c.callFunction("ALLSAMES", listOf(Value.IntV(4), Value.IntV(4), Value.IntV(5))) as Value.IntV).v,
        )
        assertEquals(
            1L,
            (c.callFunction("ALLSAMES", listOf(Value.IntV(4), Value.IntV(4), Value.IntV(4))) as Value.IntV).v,
        )
    }

    // ------------------------------------------------------------------
    // FN-05：UNICODE 控制码警告（FunctionMethodCreator.cs:5707-5717）
    // ------------------------------------------------------------------

    @Test
    fun `UNICODE 控制码返回空串且当前输出不带前缀`() {
        val (c, h) = ctx()
        // 9 = TAB（<31 且不是 10/13 → 控制码分支，:5707）
        assertEquals("", (c.callFunction("UNICODE", listOf(Value.IntV(9))) as Value.StrV).v)
        // 当前行通道未接线（currentScriptLine 恒 null）⇒ 走 C# :5713-5716 的无前缀回落；
        // 通道接线后本断言应改为钉 WarnPrefix 全形（见类 KDoc）。
        assertEquals(
            listOf("UNICODE函数: 传入了控制字符对应的值(0x9)"),
            h.systemLines,
        )
    }

    @Test
    fun `当前行通道接线后 UNICODE 告警带 WarnPrefix`() {
        val store = VariableStore()
        val h = Harness()
        h.variables = store
        val c = BuiltinEvalContext(
            h,
            store,
            currentScriptLineProvider = { "ERB\\TITLE.ERB" to 41 },
        )
        assertEquals("", (c.callFunction("UNICODE", listOf(Value.IntV(9))) as Value.StrV).v)
        assertEquals(
            listOf("注意: ERB\\TITLE.ERB的第41行: UNICODE函数: 传入了控制字符对应的值(0x9)"),
            h.systemLines,
            "WarnPrefix = zhs emuera-zhs.xml:192（C# :5709-5712）",
        )
    }

    @Test
    fun `UNICODE 非控制码原样返回且无系统行`() {
        val (c, h) = ctx()
        assertEquals("A", (c.callFunction("UNICODE", listOf(Value.IntV(65))) as Value.StrV).v)
        assertEquals(0, h.systemLines.size)
    }

    /** WarnPrefix 逐字文案钉（zhs emuera-zhs.xml:192 = `注意: {0}的第{1}行: {2}`）。 */
    @Test
    fun `WarnPrefix 拼接逐字对齐`() {
        assertEquals(
            "注意: ERB\\TITLE.ERB的第41行: UNICODE函数: 传入了控制字符对应的值(0x1F)",
            unicodeWarnPrefix("ERB\\TITLE.ERB", 41, "UNICODE函数: 传入了控制字符对应的值(0x1F)"),
        )
    }
}
