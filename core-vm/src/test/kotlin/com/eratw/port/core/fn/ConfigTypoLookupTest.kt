package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CONFIG-TYPO 回归（契约 CONTRACT-M4-H §2 h-microfix ①）。
 *
 * 案情：`core/fn/DebugMiscMethods.kt` 的 CONFIG_ROWS 曾把三条 DEBUG 项的日文 Text
 * 转写成「デバッグ**ウイ**ンドウ位置…」（U+30A6 U+30A4），而原版
 * `reference/emuera-cs/MinorShift.Emuera.Runtime.Config/ConfigData.cs:156-158`
 * 原文是「デバッグ**ウィ**ンドウ位置…」（U+30A6 U+30A3，小イ）。条目表自 j-dispatch 起
 * 单一真源在 `core/config`（`ConfigStore` + `ConfigSpecTable`），查表面
 * `ConfigMethods`/`ConfigStore.findItem` 按 Text 精确匹配，口径仍对应原版
 * GetItem :236-249 与 GetDebugItem :330-348；转写错误会让
 * `GETCONFIG("デバッグウィンドウ位置X")` 查不到——正确形查不到、
 * 错误形反而查得到。官方 bug 复刻（ADR-005）复刻的是原版缺陷，不是我们的转写错误。
 *
 * 本文件把三条**修正后的正确形**用码位锚点钉死（不依赖肉眼可辨的假名字形），
 * 并守住两侧边界：
 * - 原版自己就写「ウイ」的两条（ConfigData.cs:152-153）必须保持原样、仍可命中；
 * - 修正只影响这三条 Text，全表其余条目的 Text / EngText 命中面不得被波及
 *   （巡检见 [全表影响面巡检] 用例；123 行与 ConfigData.cs 的逐码位全表对账
 *   由本路独立脚本完成，登记在交付报告）。
 *
 * 交叉印证（只读，本路禁改 core/config 目录下各文件）：
 * - `core/config/ConfigSpec.kt:205-211` 五条 debug 行的 Text / EngText 与 C# 一致；
 * - `core/config/ConfigPageLabels.kt:558-582` 的 98 条标签对账表同读数
 *   （其 :104-110 的登记正是本案：该波只登记不修）；
 * - zhs 译文侧 `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml:176-178`
 *   「调试窗口在固定位置开启 / 调试窗口位置X / 调试窗口位置Y」与三条一一对应
 *   （键名 `UI.DebugConfigDialog.SetWindowPos/WindowX/WindowY`）。
 */

/** 与 DebugMiscMethodsTest 的装置同形；名字带 Ty 前缀避免与他文件相撞。 */
private class TyBaseCtx(val lines: MutableList<String> = mutableListOf()) : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("TyBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {
        lines += message
    }
}

private class TyHost(
    override val clientWidth: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    override val fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    override val soundChannels: Int = 0,
    override val fileOps: FileOps? = null,
    override val consoleLines: List<ConsolePrintedLine>? = null,
    override val logHeaderLines: List<String> = emptyList(),
    override val memoryUsageBytes: Long? = null,
    override val textBox: TextBoxHost? = null,
    override val audio: AudioHost? = null,
    override val measureText: ((String) -> Long)? = null,
    override val configOverrides: Map<String, String> = emptyMap(),
) : BuiltinHost

private class TyEnv(host: BuiltinHost = TyHost()) {
    val base = TyBaseCtx()
    val store = VariableStore()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, host)
    }
}

// ---------------------------------------------------------------------------
// 码位锚点：逐字符抄自 ConfigData.cs:152-158 的反编译字面量（脚本实测码位见交付报告）
// ---------------------------------------------------------------------------

/** ConfigData.cs:156 Text「デバッグウィンドウ位置を指定する」（30A6 30A3 = ウィ）。 */
private const val JA_SET_POS =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A3\u30F3\u30C9\u30A6\u4F4D\u7F6E\u3092\u6307\u5B9A\u3059\u308B"

/** ConfigData.cs:157 Text「デバッグウィンドウ位置X」（汉字位置 4F4D 7F6E + 半角 X 0058）。 */
private const val JA_POS_X =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A3\u30F3\u30C9\u30A6\u4F4D\u7F6E\u0058"

/** ConfigData.cs:158 Text「デバッグウィンドウ位置Y」。 */
private const val JA_POS_Y =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A3\u30F3\u30C9\u30A6\u4F4D\u7F6E\u0059"

/** 同一码位串、但把小イ（30A3）换成大イ（30A4）= 曾经的转写错误形，原版没有这一条目。 */
private const val JA_SET_POS_TYPO =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A4\u30F3\u30C9\u30A6\u4F4D\u7F6E\u3092\u6307\u5B9A\u3059\u308B"
private const val JA_POS_X_TYPO =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A4\u30F3\u30C9\u30A6\u4F4D\u7F6E\u0058"
private const val JA_POS_Y_TYPO =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A4\u30F3\u30C9\u30A6\u4F4D\u7F6E\u0059"

/** ConfigData.cs:152 Text 原文即「ウイ」（30A4），官方原形，必须保留且仍可命中。 */
private const val JA_SHOW_WIN_ORIG =
    "\u8D77\u52D5\u6642\u306B\u30C7\u30D0\u30C3\u30B0\u30A6\u30A4\u30F3\u30C9\u30A6\u3092\u8868\u793A\u3059\u308B"

/** ConfigData.cs:153 Text 原文即「ウイ」（30A4）。 */
private const val JA_TOPMOST_ORIG =
    "\u30C7\u30D0\u30C3\u30B0\u30A6\u30A4\u30F3\u30C9\u30A6\u3092\u6700\u524D\u9762\u306B\u8868\u793A\u3059\u308B"

private fun tyInt(ctx: BuiltinEvalContext, key: String): Long =
    (ctx.callFunction("GETCONFIG", listOf(Value.StrV(key))) as Value.IntV).v

/**
 * 「查得到」判据：GETCONFIGS 对已命中条目可能因值类型抛「型が違います」（Lang.cs:3914，
 * 实参见 `FunctionMethodCreator.cs:6063/:6072`，落点在 `ConfigMethods`），那是**命中之后**的
 * 分支；只有「適切なコンフィグ名ではありません」（Lang.cs:1742 `InvalidConfigName`，
 * spec: `ConfigData.cs:352-356`）才算查不到。
 * 文案自 j-dispatch 起改用 Lang.cs 原文（ERB 可见面按 ADR-005 对齐 PC 基准），
 * 故这里匹配的也是原文子串，不再匹配旧的中文转写。
 */
private fun tyResolvable(ctx: BuiltinEvalContext, key: String): Boolean =
    try {
        ctx.callFunction("GETCONFIGS", listOf(Value.StrV(key)))
        true
    } catch (e: CodeEE) {
        val msg = e.message ?: ""
        if (msg.contains("適切なコンフィグ名ではありません")) {
            false
        } else {
            assertTrue(msg.contains("型が違います"), "查 $key 出现预期外错误：$msg")
            true
        }
    }

class ConfigTypoLookupTest {

    @Test
    fun `码位锚定的正确日文形命中三条 debug 项并取到出厂默认`() {
        val env = TyEnv()
        // bool false → "NO" → 0（configValueInErb 兜底分支，DebugMiscMethods.kt:521-528）
        assertEquals(0L, tyInt(env.ctx, JA_SET_POS))
        // int 0 → 走 "0".toLong() 分支
        assertEquals(0L, tyInt(env.ctx, JA_POS_X))
        assertEquals(0L, tyInt(env.ctx, JA_POS_Y))
        // 同区相邻两条（ConfigData.cs:154-155）默认 400 / 300，同样锚定命中面
        assertEquals(400L, tyInt(env.ctx, "デバッグウィンドウ幅"))
        assertEquals(300L, tyInt(env.ctx, "デバッグウィンドウ高さ"))
    }

    @Test
    fun `正确形 Text 命中并可与 EngText 键形播种（仅 CONFIG 区认 EngText）`() {
        val env = TyEnv(
            TyHost(
                configOverrides = mapOf(
                    JA_SET_POS to "YES",
                    JA_POS_X to "777",
                    JA_POS_Y to "888",
                    // EngText 播种只在 CONFIG 区有效（ConfigData.cs:262-283 的 :277 才比 EngText）
                    "Window X position" to "555",
                ),
            ),
        )
        // CONFIG 区键形（含 EngText）走 emuera.config 层 ⇒ 播种生效（ConfigData.cs:539 用
        // GetConfigItem 查，:277 才比 EngText）。
        assertEquals(555L, tyInt(env.ctx, "ウィンドウ位置X"))
        // DEBUG 区三条**不受** emuera.config 层播种：原版 `LoadConfig` 只用 `GetConfigItem`
        // （ConfigData.cs:539），查不到就 :541 continue；debug 项另由 `LoadDebugConfig` 经
        // `GetDebugItem` 从 debug.config 读（:684）。旧断言把三层混成一层、期望 1/777/888，
        // 是移植侧自造口径；j-dispatch 切到 core/config 单一真源后按原版落定 ⇒ 仍是出厂 0。
        // 这三条**可读**（[码位锚定的正确日文形命中三条 debug 项并取到出厂默认] 用例钉住读面），
        // 只是写面缺口留待 debug.config 装载链接线。
        assertEquals(0L, tyInt(env.ctx, JA_SET_POS))
        assertEquals(0L, tyInt(env.ctx, JA_POS_X))
        assertEquals(0L, tyInt(env.ctx, JA_POS_Y))
        // DEBUG 区 EngText 形在原版就是死键：GetDebugItem（ConfigData.cs:330-348）只比
        // Name / Text，没有 EngText 分支——原版缺陷，禁止「顺手接通」。
        assertFalse(tyResolvable(env.ctx, "Fixed debug window starting position"))
        assertFalse(tyResolvable(env.ctx, "Debug window X position"))
        assertFalse(tyResolvable(env.ctx, "Debug window Y position"))
    }

    @Test
    fun `旧转写错误形在读写两侧都不再命中`() {
        val env = TyEnv(TyHost(configOverrides = mapOf(JA_POS_X_TYPO to "999")))
        for (key in listOf(JA_SET_POS_TYPO, JA_POS_X_TYPO, JA_POS_Y_TYPO)) {
            val e = assertFailsWith<CodeEE> { tyInt(env.ctx, key) }
            assertTrue(
                (e.message ?: "").contains("適切なコンフィグ名ではありません"),
                "错误形 $key 应查不到，实际：${e.message}",
            )
        }
        // 错误形作为 configOverrides 键同样被拒收（GetConfigItem :262-284 三形比对全部不命中
        // ⇒ ConfigData.cs:539-542 的 `configItem == null` 直接 continue），不得污染正确条目：
        // PosX 仍是出厂 0，而不是 999。
        assertEquals(0L, tyInt(env.ctx, JA_POS_X))
    }

    @Test
    fun `原版即ウイ的两条保留原样且仍可命中`() {
        val env = TyEnv()
        // ConfigData.cs:152-153 默认 true → "YES" → 1；这两条不是转写错误，禁「顺手统一」
        assertEquals(1L, tyInt(env.ctx, JA_SHOW_WIN_ORIG))
        assertEquals(1L, tyInt(env.ctx, JA_TOPMOST_ORIG))
    }

    @Test
    fun `精确匹配保证 CONFIG 区同形条目与 debug 条目互不吞并`() {
        val env = TyEnv(
            TyHost(
                configOverrides = mapOf(
                    "ウィンドウ位置X" to "111", // ConfigData.cs:58 WindowPosX（CONFIG 区）
                    JA_POS_X to "222",          // ConfigData.cs:157 DebugWindowPosX（DEBUG 区）
                ),
            ),
        )
        assertEquals(111L, tyInt(env.ctx, "ウィンドウ位置X"))
        // DEBUG 区同形条目保持出厂 0：既是「emuera.config 层写不到 debug 项」
        // （LoadConfig :539 只查 GetConfigItem），也是「两条没有被并成一条」的证据——
        // 若 CONFIG 区那次播种串到了 debug 条目，这里会读到 111 而不是 0。
        assertEquals(0L, tyInt(env.ctx, JA_POS_X))
        // 大写化的 Name 分支维持原版缺陷（findItem 拿 uppercase 的 key 比混合大小写 Name，
        // 永远不中；spec: ConfigData.cs:262-283/330-348，实现见 core/config/ConfigStore）
        assertFalse(tyResolvable(env.ctx, "DEBUGWINDOWPOSX"))
        assertFalse(tyResolvable(env.ctx, "WindowPosX"))
    }

    @Test
    fun `全表影响面巡检 修正未波及其它条目`() {
        val env = TyEnv()
        val resolvable = listOf(
            // DEBUG 区 7 条的 Text 形（ConfigData.cs:152-158）
            JA_SHOW_WIN_ORIG, JA_TOPMOST_ORIG,
            "デバッグウィンドウ幅", "デバッグウィンドウ高さ",
            JA_SET_POS, JA_POS_X, JA_POS_Y,
            // CONFIG 区含 ウィ 的 8 条 Text + 3 条 EngText（ConfigData.cs:54-61；
            // CONFIG 区认 EngText，ConfigData.cs:277）
            "ウィンドウの高さを可変にする", "描画インターフェース",
            "ウィンドウ幅", "ウィンドウ高さ", "ウィンドウ位置X", "ウィンドウ位置Y",
            "起動時のウィンドウ位置を指定する", "起動時にウィンドウを最大化する",
            "Window width", "Window X position", "Fixed window starting position",
            // CONFIG 区其它抽查（含以「デバッグ」开头的非窗口项，防前缀误伤）
            "大文字小文字の違いを無視する", "デバッグコマンドを使用する",
            "マウスを使用する", "オートセーブを行なう", "PRINTCを並べる数",
            "フォント名", "一行の高さ", "履歴文字色", "フレーム毎秒",
            // REPLACE 区抽查（findItem 第二段，:583-585 = ConfigData.cs:298-316）
            "お金の単位", "単位の位置", "起動時簡略表示", "販売アイテム数", "DRAWLINE文字",
        )
        for (key in resolvable) {
            assertTrue(tyResolvable(env.ctx, key), "应能命中但查不到：$key")
        }
        val missing = listOf(
            JA_SET_POS_TYPO, JA_POS_X_TYPO, JA_POS_Y_TYPO,
            // 错误形的其它拼装（半角Y、指定する变体）也不许命中
            "デバッグウインドウ位置Yを指定する",
            // 大小写混合的 Name 形维持原版不可用
            "DebugWindowPosX", "DebugSetWindowPos", "DebugWindowPosY",
            // DEBUG / REPLACE 区 EngText 形维持原版不可用（GetDebugItem / GetReplaceItem
            // 没有 EngText 分支，ConfigData.cs:298-316 / :330-348）
            "Show debug window on startup", "Debug window always on top",
            "Debug window width", "Debug window height",
            "Currency symbol", "DRAWLINE character",
        )
        for (key in missing) {
            assertFalse(tyResolvable(env.ctx, key), "不该命中却查到了：$key")
        }
    }
}
