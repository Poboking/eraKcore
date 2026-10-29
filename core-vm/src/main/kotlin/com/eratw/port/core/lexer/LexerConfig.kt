package com.eratw.port.core.lexer

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.host.RuntimeAux

/**
 * 词法阶段使用的系统配置开关，默认值取自 ConfigData 注册项。
 * 移植版不做完整配置系统，仅暴露可覆盖项。
 */
data class LexerConfig(
    /** spec: ConfigData.cs:101 —— SystemAllowFullSpace 默认 true（全角空格视为空白） */
    val systemAllowFullSpace: Boolean = true,
    /** spec: ConfigData.cs:110 —— SystemIgnoreTripleSymbol 默认 false（FORM 内三连记号要展开） */
    val systemIgnoreTripleSymbol: Boolean = false,
    /**
     * spec: LexicalAnalyzer.cs:664 / 920 —— ";#;" 仅 DebugMode 下跳过。
     * spec: Program.cs:47/64-67/81 —— `Program.DebugMode` 只由命令行 `-Debug`/`-debug`/`-DEBUG`
     * 开关置位，默认 false（本移植不接 CLI 开关，故恒 false）。
     */
    val debugMode: Boolean = false,
    /**
     * spec: ConfigData.cs:119 —— ReplaceContinuationBR 默认值 C# 字面量 "\" \""（引号+空格+引号）。
     * spec: EraStreamReader.cs:179 —— 使用前会把 '"' 全部去掉，实际插入一个空格。
     */
    val replaceContinuationBR: String = "\" \"",
    /**
     * ERH `#DEFINE` 宏表（宏名 → 展开体原文）。空表 = 不展开（独立单测 / 不读 ERB 的运行期）。
     *
     * 展开位置 = [com.eratw.port.core.lexer.ErbLexer.analyse] 的 token 流（spec:
     * `LexicalAnalyzer.cs:942-1006 expandMacro`）：只在**标识符 token** 上替换，
     * 字符串字面量内部与注释不参与；展开体重新词法化后再查表（递归，100 次上限，
     * spec: `LexicalAnalyzer.cs:954-959`）。`#DIM` 行不走此处 —— 那里由
     * `DimParseConfig.macros` 按声明语义折叠。
     *
     * 运行期来源 = [installLexerMacros] 挂在 `store.aux` 的那张表（会话装配时从
     * `BootMetadata.dimParseConfig.macros` 投影一次；见 `GameSession` 的接线回执）。
     */
    val macros: Map<String, String> = emptyMap(),
    /**
     * 宏名查表是否忽略大小写（spec: `IdentifierDictionary.cs:419-427` 的
     * `Config.IgnoreCase` 分支；本作 `SymbolConfig.ignoreCase` 缺省 true）。
     */
    val macroIgnoreCase: Boolean = true,
) {
    /**
     * 全角空格条目在官方报错里的显示名 —— 即 `Config.GetConfigName(SystemAllowFullSpace)`。
     *
     * spec: `Config.cs:291-294`（`return nameDic[code]`）+ `ConfigData.cs:204-214`
     * （`item.Text + "/" + item.EngText`）；本作该条目的两个标签是
     * 「全角スペースをホワイトスペースに含める/Whitespace includes full-width space」
     * （`ConfigData.cs:101`）。`LexicalAnalyzer` 的全角空格 CodeEE 把 `{0}` 填成它
     * （spec: `LexicalAnalyzer.cs:751`），不再是枚举名 `SystemAllowFullSpace`。
     */
    val systemAllowFullSpaceName: String
        get() = configDisplayName(ConfigKey.SystemAllowFullSpace)

    companion object {
        /**
         * 从条目表组装（CMP-36）：编译期词法开关必须吃**磁盘配置**而不是出厂默认。
         *
         * 原版 `LexicalAnalyzer` 直读静态字段 `Config.SystemAllowFullSpace`
         * （spec: `Config.cs:370`，消费点 `LexicalAnalyzer.cs:748-752` / `:681`）；
         * 该字段由 `ConfigData.LoadConfig` 从 `emuera.config` 写入（`ConfigData.cs:491-494`）。
         * 本游戏 `emuera.config:54` = `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`
         * ⇒ 运行期/编译期都应为 false。erbcc 的 `CorpusCompile` 构造点（LineParser 与
         * LogicalLineReader）均已接 fromConfigStore（2026-10-26 fixcore 波 A1）；
         * 其余装置（erbcc Main 的 ScanRunner、core-vm 的 RealCorpusCompile /
         * CorpusValidator / GoldenPipeline）暂无 ConfigStore 源，仍吃出厂默认（TODO(verify)）。
         *
         * @param store 已读盘的条目表（`EmueraConfigParser.loadChainFromDisk` /
         *   `loadOverrideMap` 的产物；ConfigKey 注册默认即 ConfigData 出厂值）
         * @param macros `#DEFINE` 宏表（erbcc 侧来自 `SymbolTable.config.macros`）
         * @param macroIgnoreCase 宏名大小写口径（erbcc 侧来自 `SymbolConfig.ignoreCase`）
         */
        fun fromConfigStore(
            store: ConfigStore,
            macros: Map<String, String> = emptyMap(),
            macroIgnoreCase: Boolean = true,
        ): LexerConfig = LexerConfig(
            systemAllowFullSpace = store.itemOf(ConfigKey.SystemAllowFullSpace).bool(),
            systemIgnoreTripleSymbol = store.itemOf(ConfigKey.SystemIgnoreTripleSymbol).bool(),
            macros = macros,
            macroIgnoreCase = macroIgnoreCase,
        )
    }
}

// ---------------------------------------------------------------------------
// 配置显示名（官方报错文案的 {0}）
// ---------------------------------------------------------------------------

/**
 * `Config.GetConfigName(ConfigCode)` 的形状 —— `Config.cs:291-294`（`nameDic[code]`）与
 * `ConfigData.cs:204-214`（`item.Text + "/" + item.EngText`）。本仓 `ConfigSpec` 的
 * [ConfigSpecTable.specOf] 就是注册表真源，`jpLabel`/`enLabel` 即 Text/EngText。
 */
internal fun configDisplayName(key: ConfigKey): String =
    ConfigSpecTable.specOf(key)?.let { "${it.jpLabel}/${it.enLabel}" } ?: key.name

// ---------------------------------------------------------------------------
// 运行期 #DEFINE 宏表（store.aux 槽）
// ---------------------------------------------------------------------------

/**
 * `#DEFINE` 宏表在 [RuntimeAux] 的槽名（家族前缀小写，约定见 `core/host/RuntimeAux.kt`）。
 *
 * 宏表在**会话装配期**从 `BootMetadata.dimParseConfig` 投影到这里（`BootMetadata.kt:363-365`
 * 落盘、`:849-859` 读回），此后 `GameSession` 可以照旧释放整份 metadata ——
 * 运行期 `ConfigMethods.lexerConfig` 与 `BuiltinHost.macroNames` 都从这只槽现读。
 */
const val LEXER_MACROS_AUX_SLOT: String = "lexer.macros"

/**
 * 宏表壳：`Map` 本身不可变，壳只提供「装配期写一次、运行期现读」的稳定引用。
 * 与 `ConfigMethods` 的条目表壳同构：生命周期跟 `store.aux` 走，不额外缓存到宿主字段。
 */
class LexerMacroTable(
    /** 宏名 → 展开体原文；查表时按 [LexerConfig.macroIgnoreCase] 归一。 */
    var macros: Map<String, String> = emptyMap(),
)

/**
 * 装配期把会话持有的宏表挂进 `store.aux`（幂等：同一槽只换内容，不重建）。
 *
 * 调用点属 `GameSession` 装配面（本路只出回执）：在 `VariableStore` 建好之后、
 * `SystemVariableBootstrap.install` 之前，用
 * `installLexerMacros(store.aux, metadata.dimParseConfig.macros)` 挂一次即可。
 */
fun installLexerMacros(aux: RuntimeAux, macros: Map<String, String>) {
    aux.slot(LEXER_MACROS_AUX_SLOT) { LexerMacroTable() }.macros = macros
}

/** 现读宏表；未装配 = 空表（不展开，与旧口径等值）。 */
fun lexerMacrosOf(aux: RuntimeAux): Map<String, String> =
    aux.peek<LexerMacroTable>(LEXER_MACROS_AUX_SLOT)?.macros ?: emptyMap()

/**
 * 宏名集合；未装配 = null（= 宿主未接线，`ISDEFINED` / `ENUMMACRO*` 的旧回落口径）。
 *
 * 注意「装了空表」与「没装」不是一回事：空表给 [emptySet]（有宏表、只是没有宏），
 * 没装给 null（宿主未接线）。两者对 `ISDEFINED` 同值，但让 `ENUMMACRO*` 的
 * 「未接线」与「零命中」保持可区分。
 */
fun lexerMacroNamesOf(aux: RuntimeAux): Set<String>? =
    aux.peek<LexerMacroTable>(LEXER_MACROS_AUX_SLOT)?.macros?.keys
