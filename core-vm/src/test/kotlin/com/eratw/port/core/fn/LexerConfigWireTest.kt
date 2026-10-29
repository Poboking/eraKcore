package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 运行期词法配置接线（SystemAllowFullSpace / SystemIgnoreTripleSymbol → LexerConfig）。
 *
 * 断言的基准：
 *   * 条目注册默认（ConfigData.cs:101 的 true、:110 的 false）；
 *   * 静态字段读取点 Config.cs:370 / :372 与消费点 LexicalAnalyzer.cs:748-754、:1138；
 *   * 语料 `emuera.config` 的英文标签形态（OUTPUT ENGLISH ITEMS:YES：
 *     `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`），经宿主覆盖表进入条目表后
 *     必须真的抵达运行期动态解析位（GETVAR/STRFORM/PRINTFORMS）。
 *
 * 词法层自身的开关行为（SkipWhiteSpace 停住 / Analyse 抛错）已由 LexerAuditTest 钉死，
 * 本文件只钉「配置值有没有流进词法器」这条通道。
 */
class LexerConfigWireTest {

    /** 内建函数测试环境（与 BuiltinsTest 同构）。 */
    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** 只带覆盖表的宿主 —— 模拟「宿主没实现 ConfigChainSource」的降级通道。 */
    private class OverridesHost(
        private val overrides: Map<String, String>,
    ) : BuiltinHost {
        override val configOverrides: Map<String, String> get() = overrides
    }

    private fun env(overrides: Map<String, String> = emptyMap()): Triple<BuiltinEvalContext, VariableStore, RuntimeAux> {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, OverridesHost(overrides))
        return Triple(ctx, store, store.aux)
    }

    // 语料 emuera.config 的英文标签（ConfigSpec.engText 的大写形态，GetConfigItem 三级匹配的
    // EngText 分支，spec: ConfigData.cs:277）。
    private val fullSpaceNo = "WHITESPACE INCLUDES FULL-WIDTH SPACE" to "NO"
    private val tripleYes = "DO NOT PROCESS TRIPLE SYMBOLS INSIDE FORM" to "YES"

    @Test
    fun `出厂默认 true + false（ConfigData 101、110）`() {
        val (ctx, _, aux) = env()
        val lc = ConfigMethods.lexerConfig(ctx)
        assertTrue(lc.systemAllowFullSpace) // ConfigData.cs:101 默认 true
        assertTrue(!lc.systemIgnoreTripleSymbol) // ConfigData.cs:110 默认 false
        // aux 直入形与 BuiltinContext 形读同一份条目表（槽单例）
        val lc2 = ConfigMethods.lexerConfig(aux, ctx.host)
        assertEquals(lc, lc2)
        assertTrue(ConfigMethods.store(aux, ctx.host) === ConfigMethods.store(aux, ctx.host))
    }

    @Test
    fun `覆盖表的语料英文标签真的流进 LexerConfig`() {
        val (ctx, _, aux) = env(mapOf(fullSpaceNo, tripleYes))
        val lc = ConfigMethods.lexerConfig(aux, ctx.host)
        // 语料实况：SystemAllowFullSpace=false（emuera.config 行 WHITESPACE INCLUDES FULL-WIDTH SPACE:NO）
        assertTrue(!lc.systemAllowFullSpace)
        // 条目置 true 才「不展开」（ConfigData.cs:110 条目名「FORM中の三連記号を展開しない」）
        assertTrue(lc.systemIgnoreTripleSymbol)
    }

    @Test
    fun `GETVAR 动态表达式随开关报全角空格或放行`() {
        // 默认（语料之外的出厂态）：U+3000 是空白，表达式照常求值
        val (openCtx, openStore, _) = env()
        openStore.writeInt("FLAG", longArrayOf(0), 42)
        val v = openCtx.callFunctionRef("GETVAR", listOf(com.eratw.port.core.expr.StrLiteral("FLAG:0　+1")))
        assertEquals(43L, (v as Value.IntV).v) // LexicalAnalyzer.cs:748-754 的 true 分支

        // 语料配置（SystemAllowFullSpace=false）：同一串在词法层抛 UnexpectedFullWidthSpace，
        // 被 getvarDynamic 的 LexError 收口转 CodeEE（文案保持原版日文）
        val (strictCtx, strictStore, _) = env(mapOf(fullSpaceNo))
        strictStore.writeInt("FLAG", longArrayOf(0), 42)
        val e = assertFailsWith<CodeEE> {
            strictCtx.callFunctionRef("GETVAR", listOf(com.eratw.port.core.expr.StrLiteral("FLAG:0　+1")))
        }
        assertTrue(
            e.message?.contains("全角スペース") == true,
            "GETVAR 应报 UnexpectedFullWidthSpace：${e.message}",
        )
    }
}
