package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.installLexerMacros
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 运行期 `#DEFINE` 宏表接线（CMP-34，RECON-CMP §5 的必红夹具）。
 *
 * 原版语义链：
 *   * ERH `#DEFINE` 装进 `IdentifierDictionary.macroDic`（`ErhLoader.cs:170/246`）；
 *   * `LexicalAnalyzer.Analyse` 末尾对标识符 token 展开（`LexicalAnalyzer.cs:729-732` +
 *     `:942-1006`），所以 `STRFORM` 的 `{...}` / `%...%` 子词里宏名先变成宏体 token；
 *   * `ISDEFINED` 查 `MacroKeys`（`FunctionMethodCreator.cs:218-230`、`IdentifierDictionary.cs:286`）。
 *
 * 移植侧宏表的来源是 `BootMetadata.dimParseConfig.macros`（`BootMetadata.kt:363-365` 落盘、
 * `:849-859` 读回）。会话装配把它挂到 `store.aux`（[installLexerMacros]），本用例只走
 * 「装表 → 运行期入口」这一段；`GameSession` 的装配调用属 L05 面，见交付回执。
 *
 * 夹具形状的 PC 依据（RECON §5 原稿 `mapOf("FOO" to "42")` + `STRFORM("%FOO%")` 期望 "42"
 * 与 C# 不符，故按权威改写，通道覆盖不变）：
 *   * `{...}` 子词要求 long（`StrForm.cs:310-315`）⇒ 宏体 `42` 走 `{FOO}`，期望 "42"；
 *   * `%...%` 子词要求 string（`StrForm.cs:330-335`）⇒ 字符串宏体走 `%SFOO%`，期望 "42"；
 *   * `天候` 宏体 `TIME:5` 是 long ⇒ 数值通道 `{天候}` 与直接写 `{TIME:5}` 必须同解；
 *     硬写 `%天候%` 在 PC/本实现都落在「不是字符串型」分支，本文件只钉「与 PC 同错」。
 *
 * 红形（接线前）：`{FOO}` 抛 `CodeEE: 未定义的变量 FOO`、`ISDEFINED("FOO")` = 0。
 * 绿形：`42` / `1`；`STRFORM("{1+1}")` 作对照，接线前后都必须 = `2`。
 */
class RuntimeMacroTableWireTest {

    /** 只提供变量读口的 EvalContext（与 BuiltinsTest 同构）。 */
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /**
     * 装配一个只挂宏表的运行期环境：
     * 宏表先按 `DimParseConfig.macros` 的形状装进 `store.aux`，宿主用接口缺省
     * （`BuiltinHost.DEFAULT`）—— 宏名/宏体必须由 aux 槽流到两个消费面，不靠测试替身。
     */
    private fun env(macros: Map<String, String>): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore()
        installLexerMacros(store.aux, DimParseConfig(macros = macros).macros)
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store) to store
    }

    private fun call(ctx: BuiltinEvalContext, name: String, vararg args: Value): Value =
        ctx.callFunction(name, args.toList())

    private fun sv(v: Value): String = (v as Value.StrV).v
    private fun iv(v: Value): Long = (v as Value.IntV).v

    /** 数值通道：`#DEFINE FOO 42` 经 `{FOO}` 应解成整数 42（spec: StrForm.cs:310-315）。 */
    @Test
    fun `花括号子词展开运行期整数宏`() {
        val (ctx, _) = env(mapOf("FOO" to "42", "天候" to "TIME:5"))
        // spec: LexicalAnalyzer.cs:942-1006 —— 标识符 token 展开后才进 CurlyBraceSubWord
        assertEquals("42", sv(call(ctx, "STRFORM", Value.StrV("{FOO}"))))
        // 宏名查表口径 = Config.IgnoreCase（Config.cs:299-303，默认 true）
        assertEquals("42", sv(call(ctx, "STRFORM", Value.StrV("{foo}"))))
    }

    /** 字符串通道：`%SFOO%` 的宏体是字符串字面量，必须解成 "42"（spec: StrForm.cs:330-335）。 */
    @Test
    fun `百分号子词展开运行期字符串宏`() {
        val (ctx, _) = env(mapOf("SFOO" to "\"42\""))
        assertEquals("42", sv(call(ctx, "STRFORM", Value.StrV("%SFOO%"))))
    }

    /** 「与 PC 同解」：`{天候}` 与把宏体直接写进 FORM 的 `{TIME:5}` 必须同值同错。 */
    @Test
    fun `天候宏体按 TIME 5 的 token 流求值`() {
        val (ctx, _) = env(mapOf("FOO" to "42", "天候" to "TIME:5"))
        val viaMacro = runCatching { call(ctx, "STRFORM", Value.StrV("{天候}")) }
        val direct = runCatching { call(ctx, "STRFORM", Value.StrV("{TIME:5}")) }
        assertEquals(direct.isSuccess, viaMacro.isSuccess, "宏展开后的可求值性与直接书写必须一致")
        if (direct.isSuccess) {
            assertEquals(direct.getOrThrow(), viaMacro.getOrThrow())
        } else {
            assertEquals(
                direct.exceptionOrNull()?.message,
                viaMacro.exceptionOrNull()?.message,
                "失败文案也必须同源（宏不该被当成变量名报错）",
            )
        }
    }

    /**
     * `%天候%` 的 PC 口径：宏体 `TIME:5` 是 long，`%...%` 要求 string ⇒ 与直接写
     * `%TIME:5%` 同一条失败分支（`StrForm.cs:330-335`）。只比去掉回显格式串后的文案。
     */
    @Test
    fun `百分号通道对整数宏体与 PC 同错`() {
        val (ctx, _) = env(mapOf("天候" to "TIME:5"))
        val viaMacro = runCatching { call(ctx, "STRFORM", Value.StrV("%天候%")) }
        val direct = runCatching { call(ctx, "STRFORM", Value.StrV("%TIME:5%")) }
        assertTrue(viaMacro.isFailure, "PC 的 %...% 不接受 long 表达式：${viaMacro.exceptionOrNull()?.message}")
        assertEquals(direct.isSuccess, viaMacro.isSuccess)
        assertEquals(
            direct.exceptionOrNull()?.message?.replace("%TIME:5%", "%FORM%"),
            viaMacro.exceptionOrNull()?.message?.replace("%天候%", "%FORM%"),
        )
    }

    /** ISDEFINED 的数据源 = `IdentifierDictionary.MacroKeys`（FunctionMethodCreator.cs:286）。 */
    @Test
    fun `ISDEFINED 认运行期宏名`() {
        val (ctx, _) = env(mapOf("FOO" to "42", "天候" to "TIME:5"))
        assertEquals(1L, iv(call(ctx, "ISDEFINED", Value.StrV("FOO"))))
        assertEquals(1L, iv(call(ctx, "ISDEFINED", Value.StrV("foo"))))
        assertEquals(1L, iv(call(ctx, "ISDEFINED", Value.StrV("天候"))))
        assertEquals(0L, iv(call(ctx, "ISDEFINED", Value.StrV("NOT_DEFINED"))))
    }

    /** 对照：没有宏插值的 `{...}` 通道接线前后都必须绿（防止「修宏表改坏 FORM」）。 */
    @Test
    fun `花括号表达式不受宏表影响`() {
        val (ctx, _) = env(mapOf("FOO" to "42"))
        assertEquals("2", sv(call(ctx, "STRFORM", Value.StrV("{1+1}"))))
    }

    /** 未装配 aux 槽时保持旧口径：ISDEFINED 仍答 0，不因修复引入「凭空有宏」。 */
    @Test
    fun `未装配宏表时 ISDEFINED 维持 0`() {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store)
        assertEquals(0L, iv(ctx.callFunction("ISDEFINED", listOf(Value.StrV("FOO")))))
        assertTrue(store.aux.peek<Any>("lexer.macros") == null)
    }
}
