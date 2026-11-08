package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 必填实参空位（null）的收敛口径与正常取值钉桩（A-4 requiredIntArg/requiredStrArg）。
 *
 * 空位**编译期可达**：erbcc 与运行期共用 core-vm 的 ExprParser（erbcc SymbolUses.kt:252），
 * `FUNC(,1)` 解析为 `[null, expr]`（Parser.kt reduceArguments；C# 链 = ExpressionParser.cs:605-610
 * 逗号即出栈 → :620-627 end() → :143-148 TermStack.ReduceAll 空栈返 null）。
 *
 * 文案真源 = `ArgCanNotBeNull`（spec: FunctionMethod.cs:198-205 旧路 argumentTypeArray
 * 任意位 / :320-323 全量；Ex 路按 `j < OmitStart` 判；zhs `emuera-zhs.xml:887`
 * = `{0}函数: 第{1}个参数不能省略`）。
 *
 * 保留 NPE 的例外（不在此钉 CodeEE，见各函数 KDoc）：
 * - GROUPMATCH/NOSAMES/ALLSAMES（SameAsFirst 判型族，GroupMatchTest 钉 NPE）
 * - SAVETEXT 位置 3 / LOADTEXT 位置 2「求值即丢弃」位（原版放行后解引用崩溃，
 *   FunctionMethodCreator.cs:7965 / :8048-8051）
 * - MIN/MAX 的 variadic 位（OmitStart=1 之后，`VariadicInt = 258` 不含 DisallowVoid，
 *   FunctionMethod.cs:28,45）
 */
class ArgSafetyTest {

    private class CaptureCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("ArgSafetyTest 不应被直接调用：$name")

        override fun printSystemLine(message: String) = Unit
    }

    private fun ctx(): BuiltinEvalContext {
        val store = VariableStore()
        val base = CaptureCtx()
        base.variables = store
        return BuiltinEvalContext(base, store)
    }

    private fun iv(v: Value): Long = (v as Value.IntV).v

    // ------------------------------------------------------------------
    // 空位 → CodeEE，文案 = ArgCanNotBeNull（zhs:887）
    // ------------------------------------------------------------------

    @Test
    fun `ABS 首参空位抛 ArgCanNotBeNull 文案`() {
        assertEquals(
            "ABS函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx().callFunction("ABS", listOf(null)) }.message,
            "旧路 argumentTypeArray Type[1]（spec: FunctionMethodCreator.cs:4144）",
        )
    }

    @Test
    fun `STRLENS VARSIZE 首参空位抛同形文案`() {
        val ctx = ctx()
        assertEquals(
            "STRLENS函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("STRLENS", listOf(null)) }.message,
        )
        assertEquals(
            "VARSIZE函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("VARSIZE", listOf(null)) }.message,
            "Ex 路 OmitStart=1（spec: FunctionMethodCreator.cs:3425-3432），j=0 < OmitStart 报同文案",
        )
    }

    @Test
    fun `CSVCSTR CONVERT 第二参空位抛同形文案`() {
        val ctx = ctx()
        assertEquals(
            "CSVCSTR函数: 第2个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("CSVCSTR", listOf(Value.IntV(0), null)) }.message,
        )
        assertEquals(
            "CONVERT函数: 第2个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("CONVERT", listOf(Value.IntV(-1), null)) }.message,
        )
    }

    @Test
    fun `GETCHARA FLOWINPUT 首参空位在触碰宿主之前抛`() {
        val ctx = ctx()
        assertEquals(
            "GETCHARA函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("GETCHARA", listOf(null)) }.message,
        )
        assertEquals(
            "FLOWINPUT函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx.callFunction("FLOWINPUT", listOf(null)) }.message,
            "OmitStart=1（spec: FunctionMethodCreator.cs:8466-8479）",
        )
    }

    @Test
    fun `MIN 首位空位抛 CodeEE 而非 NPE`() {
        assertEquals(
            "MIN函数: 第1个参数不能省略",
            assertFailsWith<CodeEE> { ctx().callFunction("MIN", listOf(null, Value.IntV(2))) }.message,
            "OmitStart=1（spec: FunctionMethodCreator.cs:4082-4092）；variadic 位保留 NPE 复刻不在此钉",
        )
    }

    // ------------------------------------------------------------------
    // 正常取值（requiredValueArg/requiredIntArg/requiredStrArg 三口）
    // ------------------------------------------------------------------

    @Test
    fun `requiredIntArg 口 ABS POWER 正常读数`() {
        val ctx = ctx()
        assertEquals(5L, iv(ctx.callFunction("ABS", listOf(Value.IntV(-5)))))
        assertEquals(1024L, iv(ctx.callFunction("POWER", listOf(Value.IntV(2), Value.IntV(10)))))
    }

    @Test
    fun `requiredStrArg 口 STRLENS 正常读数`() {
        assertEquals(
            3L,
            iv(ctx().callFunction("STRLENS", listOf(Value.StrV("abc")))),
        )
    }

    @Test
    fun `variadic 位正常取值 MIN 读数不变`() {
        assertEquals(
            1L,
            iv(ctx().callFunction("MIN", listOf(Value.IntV(3), Value.IntV(1), Value.IntV(2)))),
        )
    }
}
