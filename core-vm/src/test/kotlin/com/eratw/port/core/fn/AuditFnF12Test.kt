package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * W10 · AUD-fn-f12 —— `SETVAR` / `VARSETEX` / `GETVAR` / `GETVARS` 第 1 参的**求值次数**。
 *
 * ## 原版点位（都是「存了 `strValue` 又重算一遍」的习惯写法）
 *
 * | 函数 | 原版两次求值 | 原版行号 |
 * |---|---|---|
 * | `SETVAR` | `strValue` → 又喂 `Analyse` | `FunctionMethodCreator.cs:757-758` |
 * | `VARSETEX` | 同上 | `:811-812` |
 * | `GETVAR` | 同上 | `:388-389` |
 * | `GETVARS` | 同上 | `:418-419` |
 *
 * 移植侧此前每个只求值一次 ⇒ 带副作用的实参（用户函数 / `RAND`）**触达次数**与原版分叉。
 * 本夹具用一个会计数的用户函数 `TICK()` 当实参，直接读计数。
 *
 * ## 判性：本作不可观测（语料实测）
 *
 * `eratw-画蛇添足版eratw4.980`（4,542 份 ERB/ERH）里这四个函数共 **23 处**调用
 * （GETVAR 15 / GETVARS 7 / SETVAR 1 / VARSETEX 0），首参形态全部是**字符串变量**
 * （`ARGS` / `LOCALS` / `TEMP_VAR`）或**纯读插值字面量**（`@"...{LIST:LOCAL}..."`），
 * 无一处带副作用 ⇒ 两次求值与一次求值结果恒等，本作读数零影响。
 * 照抄的理由是 ADR-005 理由 3（前向兼容 mod），不是「本作会变」。
 *
 * ## 同族但**未动**的两处（留给后续判性，勿顺手改）
 *
 * `VARSIZE`（`VarsizeMethod`，`:3443` 同一表达式里求值两次；语料 **312 处**调用）与
 * `GETNUMB`（`GetnumBMethod`，`:4850/:4853/:4856` 求值**三次**；语料 0 处）。
 * VARSIZE 在热路径且调用量大，改求值次数要单独量性能，不在本波范围。
 */
class AuditFnF12Test {

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** 建环境并装一个会计数的用户函数 `TICK()`：每次调用 `ticks++`，返回固定字符串。 */
    private fun env(ret: String): Triple<BuiltinEvalContext, VariableStore, () -> Int> {
        var ticks = 0
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(
            base, store,
            userFunctions = mapOf<String, (List<Value?>) -> Value>(
                "TICK" to { _ -> ticks++; Value.StrV(ret) },
            ),
        )
        return Triple(ctx, store, { ticks })
    }

    /** `TICK()` 的调用点表达式（返回值由 [env] 的 `ret` 决定）。 */
    private fun tick(): FuncCall = FuncCall("TICK", emptyList(), OperandType.STR)

    private fun ints(v: Long): IntLiteral = IntLiteral(v)

    // -----------------------------------------------------------------------
    // GETVARS / GETVAR
    // -----------------------------------------------------------------------

    @Test
    fun `GETVARS 的第 1 参求值两次`() {
        val (ctx, store, ticks) = env("ZZSTR:0")
        store.declareUserVar(DimSpec("ZZSTR", isStr = true, dims = intArrayOf(2)))
        store.writeStr("ZZSTR", longArrayOf(0), "你好")
        val r = ctx.callFunctionRef("GETVARS", listOf(tick()))
        assertEquals("你好", assertIs<Value.StrV>(r).v)
        assertEquals(2, ticks(), "spec: FunctionMethodCreator.cs:418-419 —— 第 1 参求值两次")
    }

    @Test
    fun `GETVAR 的第 1 参求值两次`() {
        val (ctx, store, ticks) = env("ZZINT:0")
        store.declareUserVar(DimSpec("ZZINT", isStr = false, dims = intArrayOf(2)))
        store.writeInt("ZZINT", longArrayOf(0), 42)
        val r = ctx.callFunctionRef("GETVAR", listOf(tick()))
        assertEquals(42L, assertIs<Value.IntV>(r).v)
        assertEquals(2, ticks(), "spec: FunctionMethodCreator.cs:388-389")
    }

    @Test
    fun `求值两次但两次结果不同时按第二次解析`() {
        // 原版：`strValue`（第一次，只进错误文案）+ 第二次喂 Analyse。这里让 TICK 第一次返回
        // 合法变量名、第二次返回另一变量名，验证「解析用的是第二次」。
        var n = 0
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(
            base, store,
            userFunctions = mapOf<String, (List<Value?>) -> Value>(
                "TICK2" to { _ -> Value.StrV(if (n++ == 0) "ZZA:0" else "ZZB:0") },
            ),
        )
        store.declareUserVar(DimSpec("ZZA", isStr = false, dims = intArrayOf(2)))
        store.declareUserVar(DimSpec("ZZB", isStr = false, dims = intArrayOf(2)))
        store.writeInt("ZZA", longArrayOf(0), 1)
        store.writeInt("ZZB", longArrayOf(0), 2)
        val r = ctx.callFunctionRef("GETVAR", listOf(FuncCall("TICK2", emptyList(), OperandType.STR)))
        assertEquals(2L, assertIs<Value.IntV>(r).v, "解析输入取第二次求值的结果（B:0）")
        assertEquals(2, n)
    }

    // -----------------------------------------------------------------------
    // SETVAR / VARSETEX
    // -----------------------------------------------------------------------

    @Test
    fun `SETVAR 的第 1 参求值两次`() {
        val (ctx, store, ticks) = env("ZZINT:0")
        store.declareUserVar(DimSpec("ZZINT", isStr = false, dims = intArrayOf(2)))
        store.writeInt("ZZINT", longArrayOf(0), 0)
        ctx.callFunctionRef("SETVAR", listOf(tick(), ints(7)))
        assertEquals(7L, store.readInt("ZZINT", longArrayOf(0)))
        assertEquals(2, ticks(), "spec: FunctionMethodCreator.cs:757-758")
    }

    @Test
    fun `VARSETEX 的第 1 参求值两次`() {
        val (ctx, store, ticks) = env("ZZINT:0")
        store.declareUserVar(DimSpec("ZZINT", isStr = false, dims = intArrayOf(2)))
        store.writeInt("ZZINT", longArrayOf(0), 0)
        store.writeInt("ZZINT", longArrayOf(1), 0)
        ctx.callFunctionRef("VARSETEX", listOf(tick(), ints(9)))
        assertEquals(2, ticks(), "spec: FunctionMethodCreator.cs:811-812")
    }
}
