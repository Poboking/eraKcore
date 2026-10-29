package com.eratw.port.core.config

import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** 一个待读取的配置文件：文件名（进告警定位）+ 已解码好的行（不含换行符）。 */
data class ConfigFile(val name: String, val lines: List<String>) {

    /** 行数为 0 也算「文件存在但空」；不存在由调用方传 null 表达（原版 `File.Exists` 守卫，spec: `ConfigData.cs:514-522`）。 */
    companion object {
        /** UTF-8 BOM 的码位 U+FEFF；用转义写死，避免源码里出现不可见字符。 */
        private const val BOM = 0xFEFF

        /** 去掉 UTF-8 BOM：`EraStreamReader` 用 `EncodingHandler.DetectEncoding` 认 BOM 并据此选编码（spec: `EncodingHandler.cs:15-41`），解码后 BOM 不留在文本里。 */
        fun stripBom(line: String): String =
            if (line.isNotEmpty() && line[0].code == BOM) {
                line.substring(1)
            } else {
                line
            }
    }
}

/** 一行配置的处理结论 —— golden 判据要求逐行可判定「改了什么 / 为什么没改」。 */
enum class ConfigLineStatus {

    /** 空行：`text.Length == 0` 就跳过（spec: `ConfigData.cs:529-532`）。 */
    BLANK,

    /** 以 `;` 开头的注释行 —— **只有第 0 个字符是 `;` 才算**，整行没有 `Trim()`（spec: `ConfigData.cs:529`）。 */
    COMMENT,

    /** 行里没有冒号：`array.Length < 2` 直接跳过，不记告警（spec: `ConfigData.cs:535-538`）。 */
    NO_SEPARATOR,

    /** 键查不到条目：`GetConfigItem == null` 直接跳过，不记告警（spec: `ConfigData.cs:540-543`）。 */
    UNMATCHED_KEY,

    /** 解析成功并写入。 */
    APPLIED,

    /** `EditorArgument` 专属：绕过 `TryParse` 的直写分支（spec: `ConfigData.cs:564-568`）。 */
    DIRECT_ASSIGNED,

    /** 值为空串，`TryParse` 在最前面返回（spec: `ConfigItem.cs:92-95`）。 */
    EMPTY_VALUE,

    /** 条目已被钉住（spec: `ConfigItem.cs:96-99`）。 */
    ALREADY_FIXED,

    /** 解析失败但原版静默保留旧值 —— 只有 `char` 型（spec: `ConfigItem.cs:119-126`）。 */
    SILENT_KEEP_DEFAULT,

    /** 抛 `CodeEE`：转成 level-1 告警，并中止本文件剩余行（spec: `ConfigData.cs:579-586`）。 */
    PARSE_ERROR,
}

/** 逐行记录。[lineNo] 是**1 基**（等于编辑器里的行号）；原版 `ScriptPosition` 用的是 0 基。 */
data class ConfigLineRecord(
    val lineNo: Int,
    val rawLine: String,
    val status: ConfigLineStatus,
    val matchedKey: ConfigKey? = null,
    val rawValue: String? = null,
    val error: ConfigParseException? = null,
) {
    /** 值是否真的被这一行改变（`DIRECT_ASSIGNED` 也算，它绕过了 `TryParse`）。 */
    val changedValue: Boolean
        get() = status == ConfigLineStatus.APPLIED || status == ConfigLineStatus.DIRECT_ASSIGNED
}

/** 一个文件的读取结果。 */
data class ConfigFileLoadResult(
    val fileName: String,
    val records: List<ConfigLineRecord>,
    val warnings: List<ConfigWarning>,
    /**
     * 首个抛错的行号；**该行之后的所有行都不再读取**，因为原版的 `try/catch` 包在
     * 整个 `while` 外面（spec: `ConfigData.cs:524-586`），异常一冒就跳出循环。
     * 官方怪癖，原样保留（ADR-005）。
     */
    val abortedAtLine: Int? = null,
    /** 因 [abortedAtLine] 而被丢弃的行数，报告用。 */
    val droppedLines: Int = 0,
) {
    val appliedCount: Int get() = records.count { it.changedValue }

    val unmatchedKeys: List<ConfigLineRecord> get() = records.filter { it.status == ConfigLineStatus.UNMATCHED_KEY }

    /**
     * 本层真正写进条目表的键（去重）—— 同一键被写两次时后者生效，但键集只有一份，
     * 装载链报告要的就是这个「可见键」口径（判据里的「合并后可见键数」由
     * [ConfigChainLoad.touchedKeys] 对三层求并集）。
     * 来源是 [ConfigLineRecord.matchedKey]，即已经过 `redirectOnReadTo` 改写后的**目标**条目
     * （spec: `ConfigData.cs:544-547`），所以 `CompatiDRAWLINE` 那行计入的是
     * `CompatiLinefeedAs1739`。
     */
    val appliedKeys: Set<ConfigKey>
        get() = records.filter { it.changedValue }.mapNotNull { it.matchedKey }.toSet()
}

/**
 * `emuera.config` 一族文件的读取器 —— C# `ConfigData.loadConfig` / `LoadDebugConfig` /
 * `LoadReplaceFile` 三个读文件方法的数据层等价物（spec: `ConfigData.cs:512-592`、`:667-710`、`:594-639`）。
 *
 * 不做的事：
 *   * **不读盘**：入参是已解码的行列表。原版的解码走 `EncodingHandler.DetectEncoding`
 *     （BOM → UTF-8-with-BOM；否则嗅探 UTF-8；再否则 Shift-JIS，兜底失败也是 Shift-JIS，
 *     spec: `EncodingHandler.cs:15-41`），那是 `core/loader` 的口径、本路的禁地；
 *     本层只负责 [ConfigFile.stripBom] 这一个文本级细节。
 *   * **不夹取**：范围夹取发生在读完之后的 `Config.SetConfig`（spec: `Config.cs:296`），
 *     见 [EffectiveConfig]；这里读到的永远是配置里写着的数（LOG-FLOOR 同族政策，
 *     参照 `render/.../scroll/LogBuffer.kt` 的 `resolveMaxLog`）。
 *   * **不弹框**：夹取要弹的 5 个提示框以 [ConfigClampNotice] 数据形式返回。
 *
 * 文件链（spec: `ConfigData.cs:479-510`）：`CsvDir/_default.config`（缺失则 `default.config`）
 * →  exe 目录的 `emuera.config` → `CsvDir/_fixed.config`（缺失则 `fixed.config`，`fix: true`）→
 * `Config.SetConfig`。顺序本身就是优先级：后面的文件覆盖前面的，`fixed` 再往后钉住。
 */
object EmueraConfigParser {

    /** 分析模式（原版 `-a` 命令行）下 `MAXLOG` 被强行改写成这个值（spec: `ConfigData.cs:569-572`）。 */
    const val MAX_LOG_ANALYSIS_MODE = 10000

    /**
     * 读主文件（`emuera.config` 及 `default.config` / `fixed.config` 两个同族文件）。
     *
     * @param fix 来自 `_fixed.config` / `fixed.config` 时传 true：解析成功的条目会被钉住
     *   （spec: `ConfigData.cs:573-576`，注意 `EditorArgument` 的直写分支**不会**钉，
     *   因为它在 `:567` 就 `continue` 了）
     * @param analysisMode 见 [MAX_LOG_ANALYSIS_MODE]
     */
    fun loadGameFile(
        file: ConfigFile,
        store: ConfigStore,
        fix: Boolean = false,
        analysisMode: Boolean = false,
    ): ConfigFileLoadResult = loadConfigFile(file, store, fix, analysisMode) { store.resolveGame(it) }

    /**
     * [loadGameFile] 的本体：逐行逻辑不变，只有「键 → 条目」这一步由 [resolve] 决定。
     *
     * 之所以留一个解析器参数：主文件链必须只认 `GAME` 组（spec: `ConfigData.cs:539` 的
     * `GetConfigItem`），而宿主覆盖表 [loadOverrideMap] 是一张本工程自己摊平出来的
     * 「键 → 原文」表 —— 它的键取 `upperEnLabel`，会带上 `REPLACE` 组的英文键，
     * 那张表播种时需要在 `REPLACE` 组里也认一次英文标签（详见 [loadOverrideMap]）。
     * 把解析器抽成参数，两条通道共用同一份逐行语义（空行/注释/偏移/钉位/中止）。
     */
    private fun loadConfigFile(
        file: ConfigFile,
        store: ConfigStore,
        fix: Boolean,
        analysisMode: Boolean,
        resolve: (String) -> ConfigItem?,
    ): ConfigFileLoadResult {
        val records = ArrayList<ConfigLineRecord>(file.lines.size)
        val warnings = ArrayList<ConfigWarning>()
        for ((index, rawLine) in file.lines.withIndex()) {
            val lineNo = index + 1
            val text = if (index == 0) ConfigFile.stripBom(rawLine) else rawLine
            if (text.isEmpty()) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.BLANK)
                continue // ConfigData.cs:529-532
            }
            if (text[0] == ';') {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.COMMENT)
                continue // ConfigData.cs:529-532；注意没有 Trim，行首空格的 `;` 不算注释
            }
            val parts = text.split(':') // ConfigData.cs:534 —— Split(new char[1]{ ':' })，按**所有**冒号切
            if (parts.size < 2) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.NO_SEPARATOR)
                continue // ConfigData.cs:535-538
            }
            val item = resolve(parts[0].trim().uppercase(Locale.ROOT)) // :539（大写化在 GetConfigItem 里，:264）
            if (item == null) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.UNMATCHED_KEY)
                continue // :540-543
            }
            // :544-547 —— 这个键被读到时改写另一个条目（本表里只有 CompatiDRAWLINE 设了该字段，
            // 目标 CompatiLinefeedAs1739 恒注册在 :102，所以下面的 null 分支在真实条目表下不可达；
            // 原版在这一步不判空，真为 null 就是下一行 NRE —— 本层不复制崩溃，按「本行无事发生」处理）
            val redirectKey = item.spec.redirectOnReadTo
            val redirect = redirectKey?.let { store.item(it) }
            if (redirectKey != null && redirect == null) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.UNMATCHED_KEY, item.spec.key, parts[1])
                continue
            }
            val target = redirect ?: item
            var value = parts[1]
            if (target.spec.key == ConfigKey.TextEditor && parts.size > 2) {
                value = rejoinTextEditorValue(parts) // :548-563
            }
            if (target.spec.key == ConfigKey.EditorArgument) {
                // :564-568 —— 直写 parts[1]（不 Trim、不走 TryParse），并且不置 Fixed
                val applied = target.assign(ConfigValue.StringValue(parts[1]))
                records += ConfigLineRecord(
                    lineNo, rawLine,
                    if (applied) ConfigLineStatus.DIRECT_ASSIGNED else ConfigLineStatus.ALREADY_FIXED,
                    target.spec.key, parts[1],
                )
                continue
            }
            if (target.spec.key == ConfigKey.MaxLog && analysisMode) {
                value = MAX_LOG_ANALYSIS_MODE.toString() // :569-572
            }
            val outcome = target.tryParse(value, lineNo)
            val status = when (outcome) {
                TryParseOutcome.Applied -> {
                    // :573 —— `if (configItem.TryParse(array[1]) && fix) configItem.Fixed = true`。
                    // 关键在于枚举行**解析成功也返回 false**：`ConfigItem.TryParse` 末尾 `return flag`
                    // （spec: `ConfigItem.cs:212`），而 5 个枚举分支从不给 `flag` 赋值
                    // （spec: `ConfigItem.cs:172-211`）—— 于是 `fixed.config` 钉不住任何枚举条目
                    // （值照样被改写，只是不置钉）。判定口径挂在
                    // [ConfigValueType.tryParseReturnsTrue] 上，官方怪癖原样复刻（ADR-005）。
                    if (fix && target.spec.valueType.tryParseReturnsTrue) {
                        target.fixed = true // :573-576 —— 只有解析成功且该类型会返回 true 才钉
                    }
                    ConfigLineStatus.APPLIED
                }

                TryParseOutcome.NotParsedEmptyValue -> ConfigLineStatus.EMPTY_VALUE
                TryParseOutcome.NotParsedFixed -> ConfigLineStatus.ALREADY_FIXED
                TryParseOutcome.NotParsedSilently -> ConfigLineStatus.SILENT_KEEP_DEFAULT
                is TryParseOutcome.Failed -> {
                    warnings += ConfigWarning(file.name, lineNo, level = 1, message = outcome.error.message, configKey = target.spec.key)
                    records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.PARSE_ERROR, target.spec.key, value, outcome.error)
                    return ConfigFileLoadResult(
                        fileName = file.name,
                        records = records,
                        warnings = warnings,
                        abortedAtLine = lineNo,
                        droppedLines = file.lines.size - lineNo,
                    )
                }
            }
            records += ConfigLineRecord(lineNo, rawLine, status, target.spec.key, value)
        }
        return ConfigFileLoadResult(file.name, records, warnings)
    }

    /**
     * 原版的整条主文件链（spec: `ConfigData.cs:479-494`）：`default` → `emuera` → `fixed`。
     * 缺哪个传 null；三个文件都读完后由调用方跑 [EffectiveConfig]（对应 `Config.SetConfig`，
     * `ConfigData.cs:494`）。`CheckUpdate` / `SaveConfig` 那两步（`ConfigData.cs:495-508`）
     * 依赖 exe 目录与 ERB/CSV 的时间戳异或，属 M5 宿主层，不在本路。
     */
    fun loadGameChain(
        store: ConfigStore,
        defaultFile: ConfigFile?,
        emueraFile: ConfigFile?,
        fixedFile: ConfigFile?,
        analysisMode: Boolean = false,
    ): List<ConfigFileLoadResult> {
        val results = ArrayList<ConfigFileLoadResult>(3)
        defaultFile?.let { results += loadGameFile(it, store, fix = false, analysisMode = analysisMode) } // ConfigData.cs:491
        emueraFile?.let { results += loadGameFile(it, store, fix = false, analysisMode = analysisMode) } // :492
        fixedFile?.let { results += loadGameFile(it, store, fix = true, analysisMode = analysisMode) } // :493
        return results
    }

    // -----------------------------------------------------------------------
    // 装载链的**磁盘侧**：定位 default / emuera / fixed 三个文件，逐层解析
    // -----------------------------------------------------------------------

    /**
     * 原版 `ConfigData.LoadConfig` 的完整磁盘口径（spec: `ConfigData.cs:479-510`）：
     *
     * ```text
     * CsvDir/_default.config  →  (缺失则) CsvDir/default.config      fix = false   :481-486, :491
     * ExeDir/emuera.config                                             fix = false   :33, :492
     * CsvDir/_fixed.config    →  (缺失则) CsvDir/fixed.config        fix = true    :487-490, :493
     * ```
     *
     * 三条铁口径，逐条对应 C# 原文：
     *   1. **下划线形优先**：`_default.config` 存在就用它，**不再看** `default.config`
     *      （spec: `:483-486` 是 `if (!File.Exists(text)) text = …`，不是「两个都读」）；
     *      `_fixed.config` 同理（`:487-490`）。
     *   2. **文件不存在 ⇒ 静默跳过**：`loadConfig` 开头 `if (!File.Exists(confPath)) return false`
     *      （spec: `:514-517`），`EraStreamReader.Open` 失败也是 `return false`（`:519-522`），
     *      两条路径都不告警、不弹框、**不填任何默认值** —— 条目保持 `setDefault()` 里
     *      注册的出厂值（spec: `ConfigData.cs:45-174`）。本层绝不发明默认值。
     *   3. **顺序即优先级**：`default` → `emuera` → `fixed`，后写的覆盖先写的；`fixed` 层
     *      解析成功的条目被置钉（spec: `:573-576`），此后设置页的写入与后续文件都改不动它
     *      （写入器守卫 spec: `ConfigItem.cs:20-27`，解析器守卫 `:96-99`）。
     *
     * 与原版的一处**必要适配**（不改变语义）：`Program.CsvDir` 的字面量是 `"csv"`
     * （spec: `Program.cs:207` `Path.Combine(ExeDir, "csv")`），Windows 大小写不敏感所以
     * 与语料里的 `CSV/` 等价；JVM/Android 侧大小写敏感，故由 [resolveCsvDir] 做一次
     * 不敏感匹配。语料实测目录名就是 `CSV`。
     *
     * `CheckUpdate` / `SaveConfig` 两步（spec: `:495-508`）不在本层：它们要写宿主文件、
     * 且依赖 ERB/CSV 时间戳异或，属宿主层职责（同 [loadGameChain] 的说明）。
     */
    fun loadChainFromDisk(
        gameDir: Path,
        store: ConfigStore = ConfigStore(),
        csvDir: Path? = null,
        analysisMode: Boolean = false,
    ): ConfigChainLoad {
        val csv = csvDir ?: resolveCsvDir(gameDir)
        val layers = listOf(
            locateLayer(ConfigChainRole.DEFAULT, csv, DEFAULT_CONFIG_CANDIDATES),
            locateLayer(ConfigChainRole.EMUERA, gameDir, EMUERA_CONFIG_CANDIDATES),
            locateLayer(ConfigChainRole.FIXED, csv, FIXED_CONFIG_CANDIDATES),
        )
        val results = ArrayList<ConfigFileLoadResult>(layers.size)
        for (layer in layers) {
            val file = layer.file ?: continue // 缺文件：静默跳过（ConfigData.cs:514-517）
            results += loadGameFile(file, store, fix = layer.role.fix, analysisMode = analysisMode)
        }
        return ConfigChainLoad(store, layers, results, csv, analysisMode)
    }

    /**
     * 把一次 [loadChainFromDisk] 已经读到的三层内容**重放**到另一份条目表上。
     *
     * 存在的理由：原版 `ConfigData` 是进程级单例（spec: `ConfigData.cs:21`、`:29-36`），
     * 三层链在一个进程里只跑一次（spec: `Program.cs:95` 唯一的 `LoadConfig` 调用点），
     * 之后所有消费者读的都是同一份条目表。移植侧每个 VM 各有一份条目表（铁律 4），
     * 于是宿主把开局读到的那批字节（[ConfigChainLayer.file]）按同样的层序与同样的 `fix`
     * 开关重放进新表，而不是重新读盘 —— 两条通道（宿主的覆盖表与函数层的条目表）因此
     * 必然是同一批输入，不会出现「文件在两次读取之间被改」的分裂读数。
     *
     * 层序与 `fix` 取值与 [loadChainFromDisk] 完全一致（spec: `ConfigData.cs:491-493`：
     * 前两次 `fix: false`、第三次 `fix: true`），缺文件的层同样静默跳过。
     *
     * @return 重放得到的逐层结果（顺序同入参链的在位层），供调用方继续做告警/统计
     */
    fun replayChainOn(
        load: ConfigChainLoad,
        store: ConfigStore,
        analysisMode: Boolean = load.analysisMode,
    ): List<ConfigFileLoadResult> {
        val results = ArrayList<ConfigFileLoadResult>(load.results.size)
        for (layer in load.layers) {
            val file = layer.file ?: continue // 缺层：静默跳过（ConfigData.cs:514-517）
            results += loadGameFile(file, store, fix = layer.role.fix, analysisMode = analysisMode)
        }
        return results
    }

    /**
     * 把「键 → 原文值」的内存覆盖表当成一层读进 [store] —— 主文件链的**降级通道**。
     *
     * 为什么还要留着：宿主面（`BuiltinHost.configOverrides`，`core/fn/Builtins.kt:332`）是一张
     * `Map<String, String>`，除 `GETCONFIG` 外还有码页 / Compati 开关 / LOADTEXT 白名单 /
     * 存档页数等消费者在读，而 `ConsoleBuiltinHost` 一类的包装宿主只转发这张表、
     * 转发不了 [ConfigChainSource]。所以这条入口保留为「宿主没实现 [ConfigChainSource] 时」
     * 的播种路径；[ConfigMethods.storeFromHost] 的优先通道是 [replayChainOn]（三层链本体）。
     *
     * 键取 `ConfigSpec.upperEnLabel`（`GAME` 组标签，`resolveGame` 认英文标签，
     * spec: `ConfigData.cs:277`）。宿主表自 Replace 接线起还可能带 `REPLACE` 组的英文键
     * （[toOverrideMap] 把 `_Replace.csv` 摊进同一张快照），所以本入口在 `GAME` 组落空后
     * 再按 `upperEnLabel` 在 `REPLACE` 组补一次匹配 —— **主文件链不享受这条放宽**
     * （[loadGameFile] 仍只认 `GAME` 组，spec: `ConfigData.cs:539`），只有覆盖表这条
     * 移植侧形状需要它。值取 `parts[1]` 再 `Trim()` —— 与 [toOverrideMap] 成对，
     * 所以宿主表走这条路与走三层链**读到的值相同**；差异只有两处，逐条登记在交付报告：
     *   * `EditorArgument`：原版把它的值按**未 Trim** 的原文直写（spec: `ConfigData.cs:566`），
     *     表里的值已被宿主 Trim 过；
     *   * `fixed` 层的**钉位**：表是值快照，表达不了 `Fixed` 标志（spec: `ConfigData.cs:493` 的
     *     `fix: true` 与 `:573-576`），所以走这条路的条目全部不钉。
     */
    fun loadOverrideMap(
        store: ConfigStore,
        name: String,
        overrides: Map<String, String>,
        fix: Boolean = false,
        analysisMode: Boolean = false,
    ): ConfigFileLoadResult = loadConfigFile(
        ConfigFile(name, overrides.map { (key, value) -> "$key:$value" }),
        store,
        fix,
        analysisMode,
        resolve = { key ->
            // 覆盖表可以带 REPLACE 组的键（宿主把 `_Replace.csv` 并进同一张快照后，
            // [toOverrideMap] 写出的就是 GAME + REPLACE 两组英文标签）。主文件链只认
            // GAME 组（spec: `ConfigData.cs:539`），这里在覆盖表这条**移植侧形状**上
            // 补一次 REPLACE 组的英文标签匹配 —— 不放进 [loadGameFile]，否则 emuera.config
            // 就能写 replaceArray 了，那是原版没有的语义（spec: `ConfigData.cs:298-316`
            // 的 `GetReplaceItem` 只比 Name / Text，不认英文标签）。
            store.resolveGame(key) ?: store.items.firstOrNull {
                it.spec.group === ConfigGroup.REPLACE && key == it.spec.upperEnLabel
            }
        },
    )

    /**
     * 装载链 → 「键 → 原文值」覆盖表，即 [loadOverrideMap] 的逆向：把三层读到的原文按
     * 层序（`default` → `emuera` → `fixed`）摊平，**后写的层覆盖先写的层**；
     * [replace] 在位时最后再摊 REPLACE 层（C# 的时序：`LoadConfig` 之后才
     * `LoadReplaceFile` + `SetReplace`，spec: `Process.cs:240-270`）。
     *
     * 口径逐条：
     *   * 层序就是优先级（spec: `ConfigData.cs:491-493` 的三次 `loadConfig` 调用），
     *     所以 [ConfigChainLoad.results] 必须按该序追加（[loadChainFromDisk] 的循环保证）；
     *   * 只收真的落到条目上的行（`APPLIED` 与 `EditorArgument` 直写的 `DIRECT_ASSIGNED`，
     *     判据 [ConfigLineRecord.changedValue]）；未匹配键、无冒号行、空值行一律不进表
     *     （spec: `ConfigData.cs:529-543` 的三种静默 `continue`）；
     *   * 键取 [ConfigSpec.upperEnLabel]。主文件链只认 `GAME` 组条目
     *     （spec: `ConfigData.cs:539` 只用 `GetConfigItem`），所以链本身不会带 `DEBUG` 组；
     *     REPLACE 组的键**只**来自 [replace] 层（宿主把 `_Replace.csv` 并进同一张快照后，
     *     这层是唯一来源）。值取文件原文（`Trim` 后），不做 `ValueToString` 回写，
     *     以免给下游引入「注册默认被夹取/规范化」的新读数。
     *
     * 与三层链的**唯一**信息损失是钉位标志（表的形状表达不了它），故本函数只服务宿主面消费者；
     * `GETCONFIG` 一类的条目表读数走 [replayChainOn]，钉在那里的条目在那条路上照样钉。
     */
    fun toOverrideMap(load: ConfigChainLoad, replace: ConfigReplaceLoad? = null): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (result in load.results) { // 已是 default → emuera → fixed 的层序
            for (record in result.records) {
                if (!record.changedValue) continue // ConfigData.cs:529-543 的静默 continue 不进表
                val key = record.matchedKey ?: continue
                val raw = record.rawValue ?: continue
                val item = load.store.item(key) ?: continue
                out[item.spec.upperEnLabel] = raw.trim() // 后层覆盖前层
            }
        }
        // REPLACE 层在链之后（Process.cs:240-270：LoadConfig → LoadReplaceFile → SetReplace）。
        // 两组标签不重名，这里的覆盖顺序只是把原版时序如实表达出来。
        replace?.result?.let { result ->
            for (record in result.records) {
                if (!record.changedValue) continue // 空值/未匹配键不进表（ConfigData.cs:613-622）
                val key = record.matchedKey ?: continue
                val raw = record.rawValue ?: continue
                val item = replace.store.item(key) ?: continue
                out[item.spec.upperEnLabel] = raw.trim()
            }
        }
        return out
    }

    /**
     * `CsvDir` 的大小写不敏感定位（spec: `Program.cs:207` 的字面 `"csv"` 之于
     * Windows 的不可感知差异）。找不到任何 `csv` 目录时返回字面 `"csv"` 的路径 ——
     * 它不存在，于是两层 CSV 文件都判 `ABSENT`，正是原版「目录都没有」时的行为。
     */
    fun resolveCsvDir(exeDir: Path): Path = resolveSubDir(exeDir, CSV_DIR_NAME)

    /**
     * `DebugDir` 的大小写不敏感定位（spec: `Program.cs:213-215` 的 `Path.Combine(ExeDir, "debug")`）。
     * 与 [resolveCsvDir] 同一个理由：Windows 上 `"csv"`/`"debug"` 字面量与语料的 `CSV`/`DEBUG`
     * 不可分辨，JVM 侧大小写敏感。语料实测**没有** debug 目录（只有 `DEBUG_MODE.bat` 这一支
     * 带 `-debug` 启动的脚本），所以本层在真语料上恒判 `ABSENT`。
     */
    fun resolveDebugDir(exeDir: Path): Path = resolveSubDir(exeDir, DEBUG_DIR_NAME)

    /** 子目录定位的共用实现；找不到时返回**字面名**的路径（不存在 ⇒ 上层判 `ABSENT`）。 */
    private fun resolveSubDir(exeDir: Path, name: String): Path {
        val literal = exeDir.resolve(name)
        if (Files.isDirectory(literal)) return literal
        val upper = exeDir.resolve(name.uppercase(Locale.ROOT))
        if (Files.isDirectory(upper)) return upper
        val match = runCatching {
            Files.list(exeDir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().equals(name, ignoreCase = true) }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
        return match ?: literal
    }

    /** 按候选名顺序取**第一个存在**的文件（spec: `ConfigData.cs:483-490` 的 `File.Exists` 链）。 */
    fun locateFirstExisting(dir: Path, candidates: List<String>): Path? {
        for (name in candidates) {
            val p = dir.resolve(name)
            if (Files.isRegularFile(p)) return p // File.Exists 对目录返回 false，这里同口径
        }
        return null
    }

    /**
     * 读出一个配置文件的全部行。解码口径与宿主层一致：UTF-8（语料三个文件都带 BOM，
     * BOM 字符留在第 0 行行首，由 [ConfigFile.stripBom] 在解析时剥掉）。
     *
     * 无 BOM 且 UTF-8 严格解码失败（非法字节序列）时，按原版
     * `EncodingHandler.DetectEncoding` 的回落分支改用 shift_jis（`GetEncoding(932)`，
     * EncodingHandler.cs:15-41、:11）重读一次；两条都失败才是 UNREADABLE。
     *
     * 行切分用 `ReadLine` 口径：**末尾的换行不产生空行**（`"a\n"` ⇒ 1 行而不是 2 行）。
     * 这条直接影响装载链报告里的行数读数，见 [ConfigChainLayer.lineCount] 的语料实测值。
     *
     * @return null 表示「读不动」：不存在或解码失败，一律按原版 `return false` 静默跳过
     *   （spec: `ConfigData.cs:514-522`）
     */
    fun readConfigLines(file: Path): List<String>? {
        // Files.readString 是 Java 11 API（android.jar 编译面没有，真机必 NoSuchMethodError）；
        // 且外层 runCatching 会把它吞成 null ⇒ emuera.config 整份静默丢失（码页/白名单全回
        // 出厂）——「降级后语义不完整」的典型，正是 ADR-008 点名的病灶形态。改用 Java 7 的
        // readAllBytes + CharsetDecoder（编译面有）。
        // 解码必须保持**严格**口径（HostContractAuditTest 钉死）：decoder 默认
        // REPORT —— 非法字节抛异常，与 Files.readString 行为一致；不能用
        // String(bytes, cs)（REPLACE 语义，会变 PRESENT）。
        // 平台 API 兼容闸（AndroidApiCompatTest）首跑抓获，2026-10-22。
        val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }
        val text = utf8.getOrElse { err ->
            // 原版 EncodingHandler.DetectEncoding 在 UTF-8 严格解码失败时回落到
            // shift_jis = GetEncoding(932)（EncodingHandler.cs:15-41、:11）；
            // 无 BOM 且非 UTF-8 的 emuera.config（日文 Windows 另存）走的就是这一支。
            // UTF-8 能解的部分（含 BOM 前缀）一律保持上面的结果，不走这里。
            if (err !is MalformedInputException) return null
            runCatching {
                Charset.forName("windows-31J").newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull() ?: return null
        }
        val lines = text.split('\n').map { it.removeSuffix("\r") } // 前置剥 \r，行文本不含行尾符
        return if (lines.isNotEmpty() && lines.last().isEmpty() && text.endsWith("\n")) {
            lines.dropLast(1) // ReadLine 口径：文件以换行结尾时不产生尾随空行
        } else {
            lines
        }
    }

    /** 定位 + 读出一层；读不到的层只带 [ConfigLayerPresence]，不带行。 */
    private fun locateLayer(role: ConfigChainRole, dir: Path, candidates: List<String>): ConfigChainLayer {
        val path = locateFirstExisting(dir, candidates) ?: return ConfigChainLayer(role, dir, candidates, ConfigLayerPresence.ABSENT, null, null)
        val lines = readConfigLines(path)
            ?: return ConfigChainLayer(role, dir, candidates, ConfigLayerPresence.UNREADABLE, null, path)
        return ConfigChainLayer(role, dir, candidates, ConfigLayerPresence.PRESENT, ConfigFile(path.fileName.toString(), lines), path)
    }

    /**
     * `debug.config`（spec: `ConfigData.cs:667-710`）：形状与主文件相同，但
     *   * 只查 `debugArray`（spec: `:684` `GetDebugItem`，因此**不认英文标签**，
     *     `ConfigData.cs:330-348` 里没有 `EngText` 分支）；
     *   * 没有 `fix` 概念（永不钉位）；
     *   * 抛错后走 `goto IL_00ef`：仍然调用 `SetDebugConfig` 并返回 false
     *     （spec: `:689-709`），即「出错也要把当前状态推给静态字段」。
     */
    fun loadDebugFile(file: ConfigFile, store: ConfigStore): ConfigFileLoadResult {
        val records = ArrayList<ConfigLineRecord>(file.lines.size)
        val warnings = ArrayList<ConfigWarning>()
        for ((index, rawLine) in file.lines.withIndex()) {
            val lineNo = index + 1
            val text = if (index == 0) ConfigFile.stripBom(rawLine) else rawLine
            if (text.isEmpty() || text[0] == ';') {
                records += ConfigLineRecord(lineNo, rawLine, if (text.isEmpty()) ConfigLineStatus.BLANK else ConfigLineStatus.COMMENT)
                continue // ConfigData.cs:678
            }
            val parts = text.split(':') // :681
            if (parts.size < 2) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.NO_SEPARATOR)
                continue // :682
            }
            val item = store.resolveDebug(parts[0].trim().uppercase(Locale.ROOT)) // :684 + :332
            if (item == null) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.UNMATCHED_KEY)
                continue
            }
            val outcome = item.tryParse(parts[1], lineNo)
            if (outcome is TryParseOutcome.Failed) {
                warnings += ConfigWarning(file.name, lineNo, level = 1, message = outcome.error.message, configKey = item.spec.key)
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.PARSE_ERROR, item.spec.key, parts[1], outcome.error)
                return ConfigFileLoadResult(file.name, records, warnings, lineNo, file.lines.size - lineNo)
            }
            records += ConfigLineRecord(
                lineNo, rawLine,
                when (outcome) {
                    TryParseOutcome.Applied -> ConfigLineStatus.APPLIED
                    TryParseOutcome.NotParsedEmptyValue -> ConfigLineStatus.EMPTY_VALUE
                    TryParseOutcome.NotParsedFixed -> ConfigLineStatus.ALREADY_FIXED
                    else -> ConfigLineStatus.SILENT_KEEP_DEFAULT
                },
                item.spec.key, parts[1],
            )
        }
        return ConfigFileLoadResult(file.name, records, warnings)
    }

    /**
     * `debug.config` 的**磁盘侧**装载 —— 原版 `ConfigData.LoadDebugConfig`（spec: `ConfigData.cs:667-710`）
     * 的读盘半程，与 [loadChainFromDisk] 对位。三点口径照抄：
     *   * 路径 = `Program.DebugDir + "debug.config"`（spec: `ConfigData.cs:34` 的静态构造，
     *     `DebugDir` = `Path.Combine(ExeDir, "debug")`，spec: `Program.cs:213-215`），
     *     目录名大小写按 [resolveDebugDir] 兜；
     *   * **只有 `-debug` 命令行才读**（spec: `Program.cs:142-144` 的 `if (DebugMode)` 守卫），
     *     所以本函数由调用方在调试模式下才发起，不在主链里顺手读；
     *   * 文件不存在 / 打不开时原版**仍然**执行 `Config.SetDebugConfig(this)` 再返回 false
     *     （spec: `ConfigData.cs:670` 的短路 + `:706-709` 的 `goto IL_00ef`），即「读不到也要把
     *     当前条目值推给静态字段」。移植侧的静态字段等价物是只读的 [EffectiveConfig] 视图，
     *     装载与否不改变条目值，故这里只如实记 [ConfigLayerPresence] 而不写任何值。
     *
     * 写面只有 `debugArray`：查条目走 [ConfigStore.resolveDebug]（spec: `:684` 的 `GetDebugItem`），
     * 而它只比 `Name` / `Text`、**不认英文标签**（spec: `:330-348`）；主文件链反过来只查
     * `GetConfigItem`（spec: `:539`），所以 `DEBUG` 组条目**只能由本层写入**，
     * 反过来 `GAME` 组条目本层也一条都写不到。官方分区，原样保留（ADR-005）。
     */
    fun loadDebugFromDisk(
        gameDir: Path,
        store: ConfigStore = ConfigStore(),
        debugDir: Path? = null,
    ): ConfigDebugLoad {
        val dir = debugDir ?: resolveDebugDir(gameDir)
        val path = dir.resolve(DEBUG_CONFIG_FILE_NAME)
        if (!Files.isRegularFile(path)) {
            return ConfigDebugLoad(store, dir, ConfigLayerPresence.ABSENT, null, null, null) // :670 的 File.Exists 短路
        }
        val lines = readConfigLines(path)
            ?: return ConfigDebugLoad(store, dir, ConfigLayerPresence.UNREADABLE, null, path, null) // :670 的 Open 失败
        val file = ConfigFile(DEBUG_CONFIG_FILE_NAME, lines)
        return ConfigDebugLoad(store, dir, ConfigLayerPresence.PRESENT, file, path, loadDebugFile(file, store))
    }

    /**
     * 把一次 [loadDebugFromDisk] 读到的 debug 层重放到另一份条目表（与 [replayChainOn] 同理由：
     * 开局只读盘一次，每 VM 的条目表吃的是同一批字节）。缺文件 / 读不动时无事发生（spec: `ConfigData.cs:670`）。
     */
    fun replayDebugOn(load: ConfigDebugLoad, store: ConfigStore): ConfigFileLoadResult? {
        val file = load.file ?: return null
        return loadDebugFile(file, store)
    }

    /**
     * `_Replace.csv` 的**磁盘侧**装载 —— 原版 `ConfigData.LoadReplaceFile`（spec: `ConfigData.cs:594-639`）
     * 的读盘半程，与 [loadDebugFromDisk] 对位。
     *
     * 路径 = `Program.CsvDir + "_Replace.csv"`（spec: `Process.cs:240-246`）；`CsvDir` 的目录名
     * 大小写按 [resolveCsvDir] 兜（`Program.cs:207` 的字面 `"csv"` 之于 Windows 的不可感知差异）。
     * 文件不存在 / 打不开时原版**静默跳过**（`File.Exists` 守卫 + `LoadReplaceFile` 开头的
     * `if (!eraStreamReader.Open(filename)) return;`，spec: `Process.cs:240`、`ConfigData.cs:600-603`），
     * 这里同样只记 [ConfigLayerPresence] 不发明内容。
     *
     * **是否读这一层由调用方决定**：原版的守卫是
     * `Config.UseReplaceFile && !Program.AnalysisMode && File.Exists(...)`
     * （spec: `Process.cs:240`），其中 `UseReplaceFile` 是主文件链的 GAME 组条目
     * （注册默认 true，spec: `ConfigData.cs:47`），只能在链读完之后取。
     * 本函数只负责「给定目录 + 条目表，读出这一层」。
     *
     * [store] 就地写入（与 [loadDebugFromDisk] 同口径）：原版只有一个进程级
     * `ConfigData.Instance`，Replace 值与链值落在同一张 `replaceArray` / `configArray` 上；
     * 移植侧由调用方把**同一个** [ConfigStore] 交进来，保证 `configOverrides` 与函数层
     * 条目表读的是同一批字节。
     */
    fun loadReplaceFromDisk(
        gameDir: Path,
        store: ConfigStore = ConfigStore(),
        csvDir: Path? = null,
    ): ConfigReplaceLoad {
        val csv = csvDir ?: resolveCsvDir(gameDir)
        val path = csv.resolve(REPLACE_FILE_NAME)
        if (!Files.isRegularFile(path)) {
            return ConfigReplaceLoad(store, csv, ConfigLayerPresence.ABSENT, null, null, null) // Process.cs:240 的 File.Exists 短路
        }
        val lines = readConfigLines(path)
            ?: return ConfigReplaceLoad(store, csv, ConfigLayerPresence.UNREADABLE, null, path, null) // LoadReplaceFile 的 Open 失败
        val file = ConfigFile(REPLACE_FILE_NAME, lines)
        return ConfigReplaceLoad(store, csv, ConfigLayerPresence.PRESENT, file, path, loadReplaceFile(file, store))
    }

    /**
     * 把一次 [loadReplaceFromDisk] 读到的 Replace 层重放到另一份条目表（与 [replayDebugOn] 同理由：
     * 开局只读盘一次，每 VM 的条目表吃的是同一批字节）。缺文件 / 读不动时无事发生。
     *
     * **幂等**：赋值走 `ConfigItem.tryParse`，`List<long>` 条目先清空再逐项写入
     * （spec: `ConfigItem.cs:147-158`），同一批行重放两次结果相同 —— 所以
     * `applyChainTo` 被重复调用也不会把 `汚れの初期値` 叠成长度翻倍的列表。
     */
    fun replayReplaceOn(load: ConfigReplaceLoad, store: ConfigStore): ConfigFileLoadResult? {
        val file = load.file ?: return null
        return loadReplaceFile(file, store)
    }

    /**
     * `_Replace.csv`（spec: `ConfigData.cs:594-639`，调用点 spec: `Process.cs:246`）——
     * `replaceArray` 的**唯一**覆盖来源。与主文件链的四处不同：
     *   1. 整行先 `Trim()`（spec: `:607`），所以行首空格的 `;` 在这里算注释（主文件不算）；
     *   2. 分隔符是「逗号或冒号」任一（spec: `:613` `Split(',', ':')`）；
     *   3. 值不是 `parts[1]` 而是**从第一个分隔符之后一路取到行尾**的原文
     *      （spec: `:617-619` `text.Substring(array[0].Length + 1)`），所以值里的逗号冒号都保留；
     *   4. 值为空白时整行跳过（spec: `:620-622`），且只有前面三条都过了才查条目。
     */
    fun loadReplaceFile(file: ConfigFile, store: ConfigStore): ConfigFileLoadResult {
        val records = ArrayList<ConfigLineRecord>(file.lines.size)
        val warnings = ArrayList<ConfigWarning>()
        for ((index, rawLine) in file.lines.withIndex()) {
            val lineNo = index + 1
            val trimmed = if (index == 0) ConfigFile.stripBom(rawLine).trim() else rawLine.trim()
            if (trimmed.isEmpty()) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.BLANK)
                continue // ConfigData.cs:608-612
            }
            if (trimmed[0] == ';') {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.COMMENT)
                continue
            }
            val parts = trimmed.split(',', ':') // :613
            if (parts.size < 2) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.NO_SEPARATOR)
                continue // :614
            }
            val key = parts[0].trim() // :616
            // :617-619 —— `num = array[0].Length + 1`，偏移用的是**未 Trim 的**首段长度。
            // 语料 `CSV/_Replace.csv:7` 是「お金の単位 , ￥」（键后有一个空格再跟逗号）：
            // 按 array[0].Length 算 ⇒ 值从逗号后一位起（" ￥"），TryParse 再 Trim ⇒ 「￥」；
            // 若改用 trim 后的长度就会把分隔符本身吃进值里 ⇒ 「, ￥」。原版口径照抄。
            val value = trimmed.substring(parts[0].length + 1)
            if (value.trim().isEmpty()) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.EMPTY_VALUE, rawValue = value)
                continue // :620
            }
            val item = store.resolveReplace(key.uppercase(Locale.ROOT)) // :622 + :300
            if (item == null) {
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.UNMATCHED_KEY, rawValue = value)
                continue // :622 的 `?.` 让查不到条目时静默跳过
            }
            val outcome = item.tryParse(value, lineNo)
            if (outcome is TryParseOutcome.Failed) {
                warnings += ConfigWarning(file.name, lineNo, level = 1, message = outcome.error.message, configKey = item.spec.key)
                records += ConfigLineRecord(lineNo, rawLine, ConfigLineStatus.PARSE_ERROR, item.spec.key, value, outcome.error)
                return ConfigFileLoadResult(file.name, records, warnings, lineNo, file.lines.size - lineNo)
            }
            records += ConfigLineRecord(
                lineNo, rawLine,
                when (outcome) {
                    TryParseOutcome.Applied -> ConfigLineStatus.APPLIED
                    TryParseOutcome.NotParsedEmptyValue -> ConfigLineStatus.EMPTY_VALUE
                    TryParseOutcome.NotParsedFixed -> ConfigLineStatus.ALREADY_FIXED
                    else -> ConfigLineStatus.SILENT_KEEP_DEFAULT
                },
                item.spec.key, value,
            )
        }
        return ConfigFileLoadResult(file.name, records, warnings)
    }

    /**
     * `TextEditor` 的含冒号值重拼（spec: `ConfigData.cs:548-563`）。
     * 原版只为「`X:\path` 这种盘符后紧跟反斜杠」开的洞：
     *   * 第 3 段以 `\` 开头才把它拼回值里（`:550-554`）；
     *   * 段数大于 3 时，第 4 段起**无条件**拼回（`:555-562`）。
     * 于是 `TEXT EDITOR:C:\tools\ed.exe --arg:x` 之外的形状会被截断到第二个冒号为止
     * —— 例如值里第二个冒号后面紧跟正斜杠（`C:/a:b`）就会丢掉 `:b`。官方行为，原样保留。
     */
    private fun rejoinTextEditorValue(parts: List<String>): String {
        var value = parts[1]
        if (parts[2].startsWith('\\')) {
            value = "$value:${parts[2]}" // ConfigData.cs:550-554
        }
        if (parts.size > 3) {
            for (i in 3 until parts.size) {
                value = "$value:${parts[i]}" // ConfigData.cs:555-562
            }
        }
        return value
    }
}

/** 装载链里一层的身份 —— 决定它的定位目录、候选文件名与 `fix` 开关。 */
enum class ConfigChainRole(val fix: Boolean) {

    /** `CsvDir` 下的 `default.config` 一族，最低优先级（spec: `ConfigData.cs:481-486`、`:491`）。 */
    DEFAULT(fix = false),

    /** `ExeDir` 下的 `emuera.config`，玩家自己那份（spec: `ConfigData.cs:33`、`:492`）。 */
    EMUERA(fix = false),

    /** `CsvDir` 下的 `fixed.config` 一族，最高优先级且解析成功后钉住条目（spec: `ConfigData.cs:487-490`、`:493`、`:573-576`）。 */
    FIXED(fix = true),
}

/** 一层的定位结果 —— 「文件在不在」必须是可断言的读数，而不是隐含在空列表里。 */
enum class ConfigLayerPresence {

    /** 文件在位且解码成功。 */
    PRESENT,

    /** 两个候选名都不存在：原版 `File.Exists == false` ⇒ `return false`，静默跳过（spec: `ConfigData.cs:514-517`）。 */
    ABSENT,

    /** 文件存在但读不动 / 解码失败：对应 `EraStreamReader.Open` 失败的同一静默分支（spec: `:519-522`）。 */
    UNREADABLE,
}

/** [EmueraConfigParser.loadChainFromDisk] 里一层的完整读数。 */
data class ConfigChainLayer(
    val role: ConfigChainRole,
    val directory: Path,
    val candidates: List<String>,
    val presence: ConfigLayerPresence,
    /** 解析输入；[presence] 不是 `PRESENT` 时为 null（不发明内容）。 */
    val file: ConfigFile?,
    /** 命中的磁盘路径；未命中为 null。 */
    val resolvedPath: Path?,
) {
    /** 本层读到的**行数**（`ReadLine` 口径，尾随换行不计）；缺文件为 0。 */
    val lineCount: Int get() = file?.lines?.size ?: 0

    /** 本层文件名（进告警定位）；缺文件为候选名首位。 */
    val name: String get() = file?.name ?: (candidates.firstOrNull() ?: role.name)
}

/** 一次装载链的结果：条目表 + 三层定位 + 三层逐行记录。 */
data class ConfigChainLoad(
    val store: ConfigStore,
    val layers: List<ConfigChainLayer>,
    /** 只含 `PRESENT` 的层，顺序同 [layers]（spec: `ConfigData.cs:491-493` 的三次调用）。 */
    val results: List<ConfigFileLoadResult>,
    val csvDir: Path,
    /** 装载时用的分析模式开关（原版 `-a`/带文件参数，spec: `Program.cs:87-91`；`MaxLog` 改写依据）。 */
    val analysisMode: Boolean = false,
) {
    fun layer(role: ConfigChainRole): ConfigChainLayer = layers.first { it.role === role }

    fun result(role: ConfigChainRole): ConfigFileLoadResult? {
        val index = layers.indexOfFirst { it.role === role }
        if (index < 0 || layer(role).presence != ConfigLayerPresence.PRESENT) return null
        // results 按 layers 顺序追加，只跳过缺文件的层 ⇒ 用「本层之前的在位层数」当下标
        val offset = layers.take(index).count { it.presence == ConfigLayerPresence.PRESENT }
        return results.getOrNull(offset)
    }

    /** `default.config` 层读到的行数（缺文件 0）。 */
    val defaultLineCount: Int get() = layer(ConfigChainRole.DEFAULT).lineCount

    /** `emuera.config` 层读到的行数（缺文件 0）。 */
    val emueraLineCount: Int get() = layer(ConfigChainRole.EMUERA).lineCount

    /** `fixed.config` 层读到的行数（缺文件 0）。 */
    val fixedLineCount: Int get() = layer(ConfigChainRole.FIXED).lineCount

    /** 三层各自真正写进条目表的键集（下标即 [ConfigChainRole] 顺序）。 */
    val perLayerKeys: List<Set<ConfigKey>>
        get() = layers.map { result(it.role)?.appliedKeys ?: emptySet() }

    /**
     * **合并后可见键数**：三层写过的键的并集大小。
     * 「可见」= 该条目不再等于出厂默认（严格说还要看覆盖值恰好好等于默认的情形，
     * 本读数不做那层比较，只报「被写过」的键集，判据同 `ConfigLineRecord.changedValue`）。
     */
    val touchedKeys: Set<ConfigKey>
        get() = perLayerKeys.fold(emptySet<ConfigKey>()) { acc, keys -> acc union keys }

    /** 被 `fixed.config` 钉住的键（spec: `ConfigData.cs:573-576`）；枚举行不在此列（见 [ConfigValueType.tryParseReturnsTrue]）。 */
    val fixedKeys: Set<ConfigKey>
        get() = store.items.filter { it.fixed }.map { it.spec.key }.toSet()

    /** 全部告警，按层顺序串起来。 */
    val warnings: List<ConfigWarning>
        get() = results.flatMap { it.warnings }

    /** 因解析错误而中止的层（原版会丢掉该行之后的全部行）。 */
    val abortedLayers: List<ConfigChainLayer>
        get() = layers.filter { result(it.role)?.abortedAtLine != null }
}

/** 原版 `Program.CsvDir` 的字面目录名（spec: `Program.cs:207`；大小写适配见 [EmueraConfigParser.resolveCsvDir]）。 */
const val CSV_DIR_NAME: String = "csv"

/** `Program.ExeDir + "emuera.config"`（spec: `ConfigData.cs:33`）。 */
const val EMUERA_CONFIG_FILE_NAME: String = "emuera.config"

/** `CsvDir/_default.config` 优先，缺失才退到 `CsvDir/default.config`（spec: `ConfigData.cs:481-486`）。 */
val DEFAULT_CONFIG_CANDIDATES: List<String> = listOf("_default.config", "default.config")

/** `CsvDir/_fixed.config` 优先，缺失才退到 `CsvDir/fixed.config`（spec: `ConfigData.cs:487-490`）。 */
val FIXED_CONFIG_CANDIDATES: List<String> = listOf("_fixed.config", "fixed.config")

/** 主文件层没有下划线变体，永远只有 `emuera.config` 一个候选（spec: `ConfigData.cs:492`）。 */
val EMUERA_CONFIG_CANDIDATES: List<String> = listOf(EMUERA_CONFIG_FILE_NAME)

/** `Program.DebugDir` 的字面目录名（spec: `Program.cs:213-215`；大小写适配见 [EmueraConfigParser.resolveDebugDir]）。 */
const val DEBUG_DIR_NAME: String = "debug"

/** `Program.DebugDir + "debug.config"`（spec: `ConfigData.cs:34`）。 */
const val DEBUG_CONFIG_FILE_NAME: String = "debug.config"

/** `Program.CsvDir + "_Replace.csv"`（spec: `Process.cs:240-246` 的字面量）。 */
const val REPLACE_FILE_NAME: String = "_Replace.csv"

/** 一次 `debug.config` 装载的读数（层定位结果 + 逐行记录；缺文件时只有 [presence]）。 */
data class ConfigDebugLoad(
    val store: ConfigStore,
    val debugDir: Path,
    val presence: ConfigLayerPresence,
    /** 读到的行；[presence] 不是 `PRESENT` 时为 null（不发明内容）。 */
    val file: ConfigFile?,
    val resolvedPath: Path?,
    /** 逐行结果；只有 [presence] 为 `PRESENT` 时非 null（spec: `ConfigData.cs:670` 的两级短路）。 */
    val result: ConfigFileLoadResult?,
) {
    /** 本层写进条目表的键（去重）。 */
    val appliedKeys: Set<ConfigKey> get() = result?.appliedKeys ?: emptySet()

    /** 因解析错误而中止的行号（原版抛错后仍走 `SetDebugConfig` 并返回 false，spec: `:689-698`、`:706-709`）。 */
    val abortedAtLine: Int? get() = result?.abortedAtLine
}

/**
 * 一次 `_Replace.csv` 装载的读数（层定位结果 + 逐行记录；缺文件时只有 [presence]）。
 *
 * 形状与 [ConfigDebugLoad] 同构：`REPLACE` 组 16 条（spec: `ConfigData.cs:159-174`）的
 * 唯一写入口，由 [EmueraConfigParser.loadReplaceFromDisk] / [EmueraConfigParser.replayReplaceOn]
 * 写入调用方给的条目表。
 */
data class ConfigReplaceLoad(
    val store: ConfigStore,
    val csvDir: Path,
    val presence: ConfigLayerPresence,
    /** 读到的行；[presence] 不是 `PRESENT` 时为 null（不发明内容）。 */
    val file: ConfigFile?,
    val resolvedPath: Path?,
    /** 逐行结果；只有 [presence] 为 `PRESENT` 时非 null（spec: `Process.cs:240` 的 File.Exists 短路）。 */
    val result: ConfigFileLoadResult?,
) {
    /** 本层写进条目表的键（去重）；语料上应为 4 条（`お金の単位` / `単位の位置` / `起動時簡略表示` / `汚れの初期値`）。 */
    val appliedKeys: Set<ConfigKey> get() = result?.appliedKeys ?: emptySet()

    /** 因解析错误而中止的行号（原版 try/catch 包住整个 while，spec: `ConfigData.cs:625-635`）。 */
    val abortedAtLine: Int? get() = result?.abortedAtLine
}

/**
 * 「开局就把三层链读到手」的宿主能力 —— `core/proc/BootHosts.kt` 的 rooted 宿主实现它，
 * [com.eratw.port.core.fn.ConfigMethods] 在播种条目表时优先走它。
 *
 * 为什么要单独一个接口而不能挂到 `BuiltinHost` 上：`BuiltinHost` 是宿主总接口
 * （`core/fn/Builtins.kt`，本波禁地），而链的结果是**每 VM 一份的可变条目表**，
 * 挂成接口属性就等于让宿主持有状态。本接口只让宿主交出「读到的文件」，
 * 条目表由调用方（[EmueraConfigParser.replayChainOn]）现场建。
 *
 * 原版对应物是进程级单例 `ConfigData.Instance`（spec: `ConfigData.cs:21`、`:29-36`）
 * 加 `Program.cs:95` 的一次性 `LoadConfig()`；移植侧按铁律 4 不许有那种静态可变全局。
 */
interface ConfigChainSource {

    /** 开局读一次的三层链（层在位性、行数、钉位与告警读数都在它身上）。 */
    val configChain: ConfigChainLoad

    /** `-debug` 模式下的 debug 层读数；未开调试模式为 null（spec: `Program.cs:142-144` 的守卫）。 */
    val configDebug: ConfigDebugLoad? get() = null

    /**
     * 把本宿主读到的链重放进 [store]（`default` → `emuera` → `fixed`，`fixed` 层钉位；
     * 调试模式在位时再叠 `debug.config` 一层；`_Replace.csv` 在位时最后叠 Replace 层，
     * 层序与原版 `LoadConfig` → `LoadDebugConfig` → `LoadReplaceFile` 一致，
     * spec: `Program.cs:95`、`:142-144`、`Process.cs:240-270`）。
     * 返回值就是重放进 [store] 的那条链。
     */
    fun applyChainTo(store: ConfigStore): ConfigChainLoad
}
