package com.eratw.port.core.proc

import com.eratw.port.core.config.parseBoolLiteral
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore

/**
 * 全局 `#DIM` / `#DIMS` 原文 → [VariableStore] 的装载器（ERH 常量 + 全局变量）。
 *
 * ## 为什么需要这一层
 *
 * 原版 ERH 先于 ERB 装载，`#DIM CONST` 在装载期进入变量池
 * （`ErhLoader.cs:249-338` 的 `analyzeSharpDimLines` 本身就是**不动点重试环**：
 * 标识符未解析的行重新入队（`:315-321`），整轮无进展才降级为告警（`:334-337`）；
 * 变量注册在 `:267`）。反编译口径里 `;` 是**行内注释分隔符**：词法器遇到未转义的
 * `;` 直接 seek 到行尾（`LexicalAnalyzer.cs:919-929`）。
 *
 * 现有 headless 装载链把 `SharpStmt.raw`（**含行尾注释**）直接喂给 `DimParser`
 * （`core-vm/src/test/.../boot/RealCorpusCompile.kt:488-495`，且 `runCatching` 把
 * 失败吞成 null），而 `DimParser` 没有注释剥离，于是任何带 `;` 尾注的
 * `#DIM CONST ...` 都以「声明末尾有多余内容」失败（`UserVariables.kt:298`），
 * 常量没进变量池；运行到 `GCREATED(RM_TITLE)` 就报「未定义的变量 RM_TITLE」
 * （`VariableStore.kt:107`）。
 *
 * 语料已逐字节核对（2026-10-26）：`RM_TITLE` 的声明原文在
 * `ERB/リソース管理/ResourceManager.ERH:15` = `#DIM CONST RM_TITLE = 100000 ; タイトル画面の画像ID`
 * （带行尾注释；`fromEN/OMOGATARI/Omogatari.ERH:909` 的同名行前缀 `;` 是整行注释、原版本就不注册）。
 * 机制本身由上面两条代码路径确定，且单测已用真实形态复现。
 *
 * 本类的职责只有一件：**按原版词法剥掉行内注释后再解析**，并在同一遍里把
 * 已注册的标量 CONST 累加进 [DimParseConfig.intConstants]，供后续 `#DIM X, <常量>`
 * 的尺寸求值使用（`UserDefinedVariableData.cs:325-341`）。
 *
 * ## 二进制存档开关（M4-H 接线）
 *
 * `#DIM ... SAVEDATA ...` 是否受维度/CHARADATA 限制由配置项 `SystemSaveInBinary` 决定
 * （spec: `UserDefinedVariableData.cs:465-475`；移植侧同判据 `UserVariables.kt:384-387`）。
 * 判据本身不许放宽——本层只提供取值入口 [systemSaveInBinaryFrom]：装载装置从
 * `BuiltinHost.configOverrides`（`emuera.config` 那一层）取一次值，**同源**同时喂
 * [DimParseConfig.systemSaveInBinary]（声明判定用）与 `VarConfig.systemSaveInBinary`
 * （状态哈希的 meta 簇用，见 `core/vars/VariableSizes.kt:31`）。语料该值为 YES
 * （`emuera.config:60`），故真实开局装载下这些声明全部合法。
 *
 * ## 字符串常量表（M4-H 接线）
 *
 * `#DIMS CONST X = "..."` 与 `#DIMS CONST Y = X`（初值是另一条字符串 CONST）都靠
 * [DimParseConfig.strConstants] 折叠；本层在注册成功后把字符串标量 CONST 并入该表
 * （spec: `UserDefinedVariableData.cs:405-416`），语料实形
 * `ステータス表示関連/グラフィック特殊効果.ERH:19` 的 `EFFNAME_SPLIT = ","` →
 * 同目录 `グラフィック特殊効果.ERB:64` 的 `C_SPLITWORD = EFFNAME_SPLIT`。
 *
 * ## 两轮装载
 *
 * ERB/ERH 的物理遍历序不等于原版的「ERH 全部先装」。这里用不动点迭代：
 * 一轮里能解析的就注册并把常量并入配置，解析失败（典型是引用了尚未出现的常量）
 * 留到下一轮；一整轮无进展才判定为真失败。这与原版 `analyzeSharpDimLines`
 * 的「未解析行重新入队 / 无进展才告警」完全同构（`ErhLoader.cs:315-337`），
 * 又不依赖调用方排序。
 *
 * ## 装载期折叠回调（M4-M m-dimfold 接线）
 *
 * [install] 的不动点循环用 `config.copy(intConstants = ..., strConstants = ...)` 逐轮
 * 重建配置，`DimParseConfig.functionFolder` 原样透传——**本层不自行注入回调**
 * （铁律 4：不给未注入的调用方改变失败口径）。boot 装置 / erbcc 侧车需要折叠
 * VARSIZE/POWER/COLOR_FROMNAME/UNICODE 时，在构造 [DimParseConfig] 处调
 * `withPureStaticDimFolders(varSizes)`（`DimFunctionFolders` 的 KDoc 有逐条 C# 行号）；
 * VARSIZE 的尺寸表来自装载时点的 IdentifierDictionary 快照
 * （spec: FunctionMethodCreator.cs:3443/:3453/:3465），REF 变量不入表（:3466-3469
 * `UniqueRestructure` 返回 false）。
 *
 * 不负责：`#DEFINE` 宏表（由 erbcc 的 `SymbolTable.config.macros` 提供，
 * 经 [DimParseConfig.macros] 传入）、私有 `#DIM`（走 `declareUserVar(spec, functionKey)`）。
 *
 * TODO(verify)：原版数字字面量支持 `0x` / `0b` / `p`（以 2 为底）/ `e`（以 10 为底）
 * （`LexicalAnalyzer.cs:36-89`），而 `DimParser.lex` 只认十进制
 * （`UserVariables.kt:403-408`）。若某条 `#DIM CONST` 用了这些写法（典型是位掩码
 * `1p1`、`0x4000`），本层会把它当「初值只能是常量」记失败——**这是继行内注释之后
 * 第二个"常量失踪"来源**。修法属 `core/vars`（不在 BOOT-2 文件面内）：
 * 在 `DimParser.lex` 的 Number 分支支持三种字面量，或在 erbcc 侧归一化。
 */
object SystemVariableBootstrap {

    /** 一条顶层声明原文。`#` 与关键字之间的空白、BOM、行尾注释都可原样带进来。 */
    data class RawDeclaration(val raw: String, val file: String, val line: Int)

    data class Failure(val file: String, val line: Int, val raw: String, val message: String)

    /**
     * @property declared 成功注册的变量数
     * @property failures 失败清单（不中断装载）
     * @property constants 装载后可见的**整型**标量 CONST 表（名大写 → 值，含入参里的既有项）
     * @property strConstants 装载后可见的**字符串**标量 CONST 表（名大写 → 值，含入参里的既有项）。
     *   原版链条：`#DIMS CONST X = "..."` 的初值段经 `Restructure` 折成 `SingleStrTerm` 后写进
     *   `DefaultStr`（spec: `UserDefinedVariableData.cs:405-416`），后续声明
     *   `#DIMS CONST Y = X` 的初值同样折成 `SingleStrTerm` 才通过
     *   （spec: `UserDefinedVariableData.cs:406-408`，非 `SingleTerm` 报 InitialValueOnlyConst）。
     *   本移植把这两步都落到 [DimParseConfig.strConstants] 上（消费点 `UserVariables.kt:843`）。
     */
    class Result(
        val declared: Int,
        val failures: List<Failure>,
        val constants: Map<String, Long>,
        val strConstants: Map<String, String> = emptyMap(),
    )

    /**
     * 去掉行内注释（`;` 到行尾）。字符串字面量内的 `;` 保留。
     *
     * 引号/转义口径逐字对齐原版词法器的 `ReadString`（`LexicalAnalyzer.cs:330-400`）：
     * - **双引号与单引号各自作为定界符**，二者在字符串内互为普通字符（`ReadString`
     *   按 `StrEndWith.DoubleQuotation` / `SingleQuotation` 各判各的，`:343-360`）；
     * - 字符串内的 `\` 转义**其后一个字符**（`:369-394`：`\s`/`\S`/`\t`/`\n` 有特殊映射，
     *   其余取原字符）。对「剥注释」而言等价于「跳过下一字符、不改引号状态」；
     * - 原版**没有**双写引号转义语义，故连续两个引号不构成转义、各自按定界符处理；
     * - 字符串**外**的 `\` 不是转义符（原版只在 `ReadString` 内处理转义）。
     *
     * 逐**物理行**处理：一条 `#DIM` 在原版按单物理行入队（`ErhLoader.cs:88-124` 的
     * `ReadEnabledLine` + `Analyse`），`;` 分支的 `st.Seek(0, SeekOrigin.End)`
     * （`LexicalAnalyzer.cs:674/:928`）因此只吞掉**当前物理行**剩余部分——与这里
     * 「吞到换行符为止、不跨物理行」同解（续行块的后续物理行不被误吞）。
     */
    fun stripComment(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        var quote: Char? = null
        while (i < raw.length) {
            val c = raw[i]
            if (quote != null) {
                // 字符串内：反斜杠转义其后一个字符（ReadString 的随字符转义）。
                if (c == '\\' && i + 1 < raw.length) {
                    sb.append(c).append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                sb.append(c)
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == ';') {
                while (i < raw.length && raw[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** `#DIM ...` / `#DIMS ...` → (keyword, body)；非 DIM 行返回 null。 */
    fun splitDeclaration(raw: String): Pair<String, String>? {
        val text = raw.trimStart('\uFEFF', ' ', '\t')
        if (!text.startsWith("#")) return null
        val sp = text.indexOfFirst { it == ' ' || it == '\t' }
        if (sp < 0) return null
        val keyword = text.substring(0, sp).uppercase()
        if (keyword != "#DIM" && keyword != "#DIMS") return null
        return keyword to text.substring(sp + 1)
    }

    /**
     * 来源判定：该全局声明来自 **ERB** 文件 ⇒ 原版按 `InvalidSharp` 丢弃（CMP-89）。
     *
     * 依据（`reference/emuera-cs`）：
     * - ERB 里 `#` 行只在「紧跟 `@` 函数标签之后」的头部块合法；`logicalLine2` 不是
     *   `FunctionLabelLine` 时 `ParserMediator.Warn(Lang.Error.InvalidSharp, …, 1)` 后
     *   **丢弃**（`MinorShift.Emuera.Runtime.Script.Loader/ErbLoader.cs:413-423`，文案
     *   `Lang.cs:2430`「関数宣言の直後以外で#行が使われています」）。
     * - 全局用户变量只从 ERH 装载（`Process.cs:309/:313` 的 ErhLoader 先于 ErbLoader；
     *   `:300-301` 建好的 IdentifierDictionary 是两者共用的登记处）。
     *
     * 判据只看**扩展名**（大小写不敏感），不猜来源：装置/裁决用例喂的 `"TRIAGE"` 这类
     * 无扩展名来源保持旧口径（照常注册），不做无法举证的收紧。
     * 语料实测（跳过 `[SKIPSTART]` 区）ERB 函数外 `#DIM/#DIMS` 命中 `0` 条 ⇒ 本规则对
     * 现有语料是零行为差，纯收紧。
     *
     * public：`CorpusCompile` 的收集点（`:295-307`，归 L03/L15 面）若要在源头就过滤，
     * 必须复用这一条判据，不许各写一份。
     */
    fun isDiscardedErbDimSource(file: String): Boolean {
        val name = file.substringAfterLast('/').substringAfterLast('\\')
        return name.endsWith(".erb", ignoreCase = true)
    }

    /** 丢弃文案（单源）：收集点与测试都引用这一个常量，不许各抄一份。 */
    const val ERB_FUNCTION_DIM_REASON: String =
        "ERB 函数外 #DIM 无效：原版 InvalidSharp（ErbLoader.cs:413-423，warn 1）丢弃，" +
            "全局用户变量只从 ERH 装载（Process.cs:309/:313）"

    /**
     * 全局 `REF` 声明的拒绝文案（单源）。
     *
     * 原版 `ErhLoader.analyzeSharpDimLines`：`UserDefinedVariableData.Reference` 为真时
     * 抛 `NotImplCodeEE`（`ErhLoader.cs:262-265`），其消息 = `Lang.Error.CanNotUseFuncCurrentVer`
     * （`NotImplCodeEE.cs:5-14` → `Lang.cs:3478` 日原文「この機能は現バージョンでは使えません」，
     * zhs 译文 `lang.emuera-zhs.xml:824`「该功能不能在现版本使用」）；异常被 `:328-331` 的
     * `catch (CodeEE)` 捕获 ⇒ `Warn(…, 2)` 且**不注册**。
     */
    const val GLOBAL_REF_DECL_REASON: String =
        "全局 REF 变量声明在当前版本不可用：该功能不能在现版本使用" +
            "（ErhLoader.cs:262-265 抛 NotImplCodeEE，:328-331 catch(CodeEE) ⇒ Warn level 2 且不注册）"

    /**
     * **收集点来源闸**（CMP-89 的唯一实现）：把 ERB 函数外的 `#DIM/#DIMS` 原文丢掉，
     * 每条丢弃写进 [discarded]（= 原版 `InvalidSharp` warn 1 的留痕），返回保留者（原序）。
     *
     * 接线点：`tools/erbcc/.../compile/CorpusCompile.kt:295-307` 在把原文放进
     * `globalDimDecls` 之前调一行：
     * ```
     * val kept = SystemVariableBootstrap.filterErbFunctionDimDeclarations(globalDimDecls, <failures/warnings sink>)
     * ```
     * 该段落归 L03/L15 面，故本路只落判据与文案（回执见交付）。
     *
     * 为什么不在 [install] 里做：`install` 是纯注册面，`file` 只是诊断字段，而装置/裁决
     * 用例普遍拿 `TEST.ERB` / `ERB/main.ERB` 这类合成名当全局声明来源
     * （`GameSessionTest` / `GotoTitleTest` / `BootMetadataLazyTest` 等 10 个用例）——
     * 在注册面按扩展名拦会把「harness 的文件名」当成语义；只有收集点知道该 `#DIM`
     * 是否位于函数外（`curKey == null`）。
     */
    fun filterErbFunctionDimDeclarations(
        declarations: Iterable<RawDeclaration>,
        discarded: MutableList<Failure>,
    ): List<RawDeclaration> {
        val kept = ArrayList<RawDeclaration>()
        for (d in declarations) {
            if (!isDiscardedErbDimSource(d.file)) {
                kept.add(d)
                continue
            }
            discarded.add(Failure(d.file, d.line, d.raw, "$ERB_FUNCTION_DIM_REASON：${d.raw.take(80)}"))
        }
        return kept
    }

    /**
     * `SystemSaveInBinary` 的**唯一取值口径**：从宿主配置覆盖表（`BuiltinHost.configOverrides`，
     * 即 `emuera.config` 那一层）读该条目。
     *
     * 键（spec: `ConfigData.cs:107` 注册 `ConfigCode.SystemSaveInBinary`，出厂默认 `t: false`）：
     * 覆盖表的键已是条目标签的大写英文形（`BootHosts.kt` 的 `loadConfigOverrides` 用
     * `item.spec.upperEnLabel`，spec: `AConfigItem.cs:22-23`），这里仍按 `GetConfigItem` 的
     * 三形匹配做「Trim 后大写比较」（spec: `ConfigData.cs:262-284`），同时认日文标签形，
     * 使调用方无论喂英文键还是日文键都命中同一条目。
     *
     * 值词法 = [parseBoolLiteral]（spec: `ConfigItem.cs:227-250`）：整数非 0、`YES`/`TRUE`/`前`
     * 为真，`NO`/`FALSE`/`後` 为假。键缺失或值解析失败 ⇒ **false**（出厂默认 +
     * 原版 `LoadConfig` 对无效值只记警告、保留旧值的口径，spec: `ConfigData.cs:563-586`、
     * `ConfigItem.cs:89-95`）。
     *
     * 语料实形：`eratw-画蛇添足版eratw4.980/emuera.config` 第 60 行
     * `USE THE BINARY FORMAT FOR SAVING DATA:YES` ⇒ true。
     */
    fun systemSaveInBinaryFrom(overrides: Map<String, String>): Boolean {
        val raw = overrides.entries.firstOrNull { it.key.trim().uppercase() in SYSTEM_SAVE_IN_BINARY_KEYS }
            ?.value
            ?.trim()
            ?: return false
        // 解析失败按原版「保留旧值」= 出厂默认 false（spec: ConfigItem.cs:89-95）。
        return parseBoolLiteral(raw) ?: false
    }

    /**
     * 注册全部声明。失败逐条记入 [Result.failures]，不抛异常——装载期缺一条常量
     * 应当在运行到使用点时以「未定义的变量」暴露（原版语义），而不是阻断整个装载。
     *
     * **不做来源闸**（CMP-89）：本方法是纯注册面，`file` 只是诊断字段——装置/裁决用例
     * 也拿 `TEST.ERB` / `ERB/main.ERB` 这类合成名当全局声明来源（`GameSessionTest` /
     * `GotoTitleTest` / `BootMetadataLazyTest` 等 10 个用例），在这里按扩展名拦会
     * 「把 harness 的文件名当成语义」。来源闸的正确位置是**收集点**（`CorpusCompile`
     * 的 `globalDimDecls`，`:295-307`）：那里才知道该 `#DIM` 是否位于函数外。
     * 判据与文案收成单源 [filterErbFunctionDimDeclarations]，收集点接一行即可（见交付回执）。
     */
    fun install(
        store: VariableStore,
        declarations: Iterable<RawDeclaration>,
        config: DimParseConfig = DimParseConfig(),
    ): Result {
        val failures = ArrayList<Failure>()
        val constants = LinkedHashMap<String, Long>()
        constants.putAll(config.intConstants)
        val strConstants = LinkedHashMap<String, String>()
        strConstants.putAll(config.strConstants)
        // 每一轮都把两张常量表并进解析配置（`DimParser` 的尺寸段查 intConstants、
        // 字符串初值段查 strConstants，spec: UserVariables.kt:419 / :843）。
        val pending = ArrayList<RawDeclaration>()
        declarations.forEach { pending.add(it) }
        var declared = 0

        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val roundConfig = config.copy(intConstants = constants, strConstants = strConstants)
            val it = pending.iterator()
            while (it.hasNext()) {
                val d = it.next()
                val split = splitDeclaration(stripComment(d.raw))
                if (split == null) {
                    it.remove()
                    failures.add(Failure(d.file, d.line, d.raw, "不是 #DIM/#DIMS 声明"))
                    continue
                }
                val spec = try {
                    DimParser.parse(
                        keyword = split.first,
                        body = split.second,
                        isPrivate = false,
                        config = roundConfig,
                    )
                } catch (e: CodeEE) {
                    val message = e.message ?: "解析失败"
                    // 尺寸/初值引用了尚未注册的常量 → 留到下一轮；其余错误直接记失败。
                    if (isUnresolvedConstant(message)) continue
                    it.remove()
                    failures.add(Failure(d.file, d.line, d.raw, message))
                    continue
                }
                // 原版全局 REF 变量声明**不可用**（`ErhLoader.cs:262-265` 抛 `NotImplCodeEE`，
                // 被 `:328` 的 `catch (CodeEE)` 捕获 ⇒ `Warn(…, 2)` 且**不注册**：
                // `CreateUserDefVariable` 未执行）。移植侧此前按 `DimParser` 支持的 REF
                // 正常注册，是原版的**命名空间超集** —— 这里补齐拒绝面。
                // 私有 `#DIM ... REF`（函数内）走另一条通道（`declareUserVar(spec, functionKey)`），
                // 原版支持，不受本闸影响。
                if (spec.isRef) {
                    it.remove()
                    failures.add(Failure(d.file, d.line, d.raw, GLOBAL_REF_DECL_REASON))
                    continue
                }
                try {
                    store.declareUserVar(spec)
                } catch (e: CodeEE) {
                    it.remove()
                    failures.add(Failure(d.file, d.line, d.raw, e.message ?: "注册失败"))
                    continue
                }
                it.remove()
                declared++
                progress = true
                rememberConstant(spec, constants, strConstants)
            }
        }
        for (d in pending) {
            failures.add(Failure(d.file, d.line, d.raw, "依赖的常量无法解析（#DEFINE 宏表或前置 CONST 缺失）"))
        }
        return Result(declared, failures, constants, strConstants)
    }

    /** 覆盖表里认的两种标签形（大写英文形 + 大写化日文形）。 */
    private val SYSTEM_SAVE_IN_BINARY_KEYS: Set<String> = setOf(
        "USE THE BINARY FORMAT FOR SAVING DATA",
        "セーブデータをバイナリ形式で保存する".uppercase(),
    )

    /**
     * `UserVariables.kt:234` 的尺寸标识符未命中 = 可等待下一轮；
     * 字符串初值段的未注册常量标识符同口径
     * （`UserVariables.kt:844` 的「的初值 'X' 不能求值为常量」）。
     * 原版两者都走「重新入队等下一轮」（spec: `ErhLoader.cs:315-321`）。
     *
     * public（而非 private）：erbcc 的 `foldStringConstants` 预扫与 [install] 是同一条
     * 不动点语义的两个执行点（`ErhLoader.cs:315-337` 的重试环），失败分类必须同源——
     * 「未解析常量」重试、「其余」记 level 2 告警（`ErhLoader.cs:328-331`），两边若各写
     * 一份判据迟早分叉。
     */
    fun isUnresolvedConstant(message: String): Boolean =
        message.contains("不能求值为整数常量") || message.contains("不能求值为常量")

    /**
     * 标量 CONST 进入常量表。原版 `ConstantData` 只折叠单值常量；数组 CONST
     * （如 `#DIM CONST BIT = 0,1,2,...`）按下标读取，不能折叠为一个值。
     *
     * 整型走 [intSink]（尺寸段与整型初值段共用），字符串型走 [strSink]
     * （`#DIMS` 初值段，消费点 `UserVariables.kt:843`）。两张表都只在
     * `dims.size == 1 && totalSize == 1` 的标量 CONST 上写入（数组 CONST 不折叠）。
     */
    private fun rememberConstant(
        spec: DimSpec,
        intSink: MutableMap<String, Long>,
        strSink: MutableMap<String, String>,
    ) {
        if (!spec.isConst || spec.isRef) return
        if (spec.dims.size != 1) return
        if (spec.totalSize != 1) return
        val key = spec.name.uppercase()
        if (spec.isStr) {
            val first = spec.defaultsStr?.firstOrNull() ?: return
            strSink[key] = first
            return
        }
        val first = spec.defaultsInt?.firstOrNull() ?: return
        intSink[key] = first
    }
}
