package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fixcore 审查波钉子（2026-10-26，verify-notes §16 的 fn 级回归）。
 *
 * 夹具照 [HostCapabilityMethodsTest]：BuiltinEvalContext + 默认注册表，`callFunction` 直驱。
 * 语句级修复（QUIT 整链停止 / 重复标签首个生效 / TIMES 溢出 / ARRAYSHIFT / VARSET /
 * RESETDATA 清扩展池）由全量闸与 E2E 金标准背书，语义依据见 verify-notes §16.1。
 * （本文件块注释遵守 AGENTS.md 铁律 5：不含块注释开启字符对。）
 */
private class FxBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("FxBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun fxCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = FxBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {})
}

private fun fxStr(ctx: BuiltinEvalContext, name: String, arg: String): String =
    (ctx.callFunction(name, listOf(Value.StrV(arg))) as Value.StrV).v

class FixcoreWaveTest {

    @Test
    fun `TOUPPER 是全映射 ß 变 SS`() {
        // FMC.cs:5587 `ToUpper(CultureInfo.InvariantCulture)`：.NET ICU 全映射可扩长
        // （Unicode SpecialCasing.txt 无条件映射行）；旧实现逐码元 Character.toUpperCase 分叉。
        val ctx = fxCtx()
        assertEquals("SS", fxStr(ctx, "TOUPPER", "\u00DF"))
        assertEquals("FI", fxStr(ctx, "TOUPPER", "\uFB01"))
        assertEquals("ABC", fxStr(ctx, "TOUPPER", "abc"))
        assertEquals("", fxStr(ctx, "TOUPPER", ""))
    }

    @Test
    fun `TOLOWER 是全映射 İ 变 i 加组合点`() {
        // FMC.cs:5588 `ToLower()`（CurrentCulture；ja/zh 机器与 invariant 等价）：İ → i + U+0307。
        val ctx = fxCtx()
        assertEquals("i\u0307", fxStr(ctx, "TOLOWER", "\u0130"))
        assertEquals("\u00DF", fxStr(ctx, "TOLOWER", "\u00DF"))
        assertEquals("abc", fxStr(ctx, "TOLOWER", "ABC"))
    }

    @Test
    fun `UNICODEBYTE 串尾孤立高位代理按 S12 口径返 FFFD`() {
        // verify-notes §12 政策：原版 flush:false 对串尾孤立高位代理字节计数 = 0 ⇒
        // BitConverter.ToInt32 崩溃（fn-strings 路 2026-10-26 .NET 10 实测）；本仓不复刻崩溃。
        val ctx = fxCtx()
        assertEquals(0xFFFDL, (ctx.callFunction("UNICODEBYTE", listOf(Value.StrV("\uD800"))) as Value.IntV).v)
        assertEquals(0xFFFDL, (ctx.callFunction("UNICODEBYTE", listOf(Value.StrV("\uDC00"))) as Value.IntV).v)
        // 配对代理 = 码点（😀 = U+1F600 = 128512）
        assertEquals(128512L, (ctx.callFunction("UNICODEBYTE", listOf(Value.StrV("\uD83D\uDE00"))) as Value.IntV).v)
        assertTrue(true)
    }
}
