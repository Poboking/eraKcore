package com.eratw.port.erbcc

import java.util.Locale

/**
 * 一条装载期告警（level 1 语义：不参与成败判定，但必须可见）。
 *
 * 原版对应 `ParserMediator.Warn(…, 1)` —— 打到控制台但不置 noError（spec: ErbLoader.cs:46 等；
 * 对比 level 2：`noError = false` → 「解析失败」）。erbcc 各装载入口（预扫/切片/ERH 收集）
 * 不中断整批，但把降级决定逐条留痕，禁止「什么都没发生」式静默。
 */
data class LoadWarning(val file: String, val line: Int, val message: String)

/**
 * ERB/ERH 共用的预处理状态机（`[SKIPSTART]`/`[SKIPEND]`/`[IF 宏]`/`[ELSEIF]`/`[ELSE]`/
 * `[ENDIF]`/`[IF_DEBUG]`/`[IF_NDEBUG]`）—— erbcc 内唯一一份，口径 = 原版 `PPState`
 * （spec: ErbLoader.cs:25-181），三处使用方：[SymbolTable.scanLabels]、
 * `CorpusScan.scan`（预扫描）与切片侧装载。
 *
 * core-vm 侧另有一份等价实现（`LogicalLineReader` 与其私有 `PreprocessorState`，
 * 供运行期装载）。两份实现必须对同一份 ERB 发射**逐字节相同**的逻辑行集合，
 * 由 `PreprocessorParityTest` 用同一合成件同时喂两侧对拍钉住。
 *
 * 逐条口径：
 *  - 任何 `[`（非 `[[`）起头的行**整行消费**：命中指令走状态机；未知指令是原版
 *    `UnrecognizedPreprosessor` Warn level 1 后照样不进正文（spec: ErbLoader.cs:387-405
 *    + :160）——所以 [process] 对未知指令留痕并返回 true，而不是把它当普通行。
 *  - 指令在 Disabled 期间仍被处理（嵌套区间正确开闭，spec: ErbLoader.cs:390-406）。
 *  - `skip` 强制位：每条指令处理完后无条件执行 `if (skip) disabled = true`
 *    （spec: ErbLoader.cs:163-166）——`[SKIPSTART]` 区间内即使嵌套 `[IF 宏]`/
 *    `[IF_NDEBUG]`/`[ELSE]` 把 disabled 置回 false，也必须重新禁用；否则预扫/符号表
 *    会登记原版根本不编译的幽灵 `@函数`/私有 `#DIM`（CMP-39）。
 *  - 畸形指令（多余参数、缺参数、空栈不匹配）原版一律 `Warn(…, 1)` 后 `break`：
 *    **不改** `ppMatch`/`disabled`/`done`/`skip`（spec: ErbLoader.cs:44-52/85-90/
 *    97-126/127-158）。`[IF]` 后只读**一个** identifier 当宏名（spec: ErbLoader.cs:393-395），
 *    其后余文只触发 level 1 告警，不参与分支判定（CMP-41）。
 *  - `[[...]]` 转义形态不是指令（spec: ErbLoader.cs:387 `charStream.Next != '['`）。
 *  - `[IF_DEBUG]` 判 `!debugMode`、`[IF_NDEBUG]` 判 `debugMode`、`[IF 宏]` 判
 *    `GetMacro(arg)==null`（spec: ErbLoader.cs:59-96；本移植 debugMode 恒 false，
 *    spec: Program.DebugMode 默认值）。
 *  - 文件结束仍未闭合：原版 `PPState.FileEnd` 只对**最内层**未闭合区间告警一次
 *    （`ELSEIF` 报成 `ENDIF`），Warn level 1「～がありません」（spec: ErbLoader.cs:169-180
 *    + ErbLoader.cs:513 调用点）——[fileEnd] 等价复刻。
 *
 * 旧实现是三处各自内联的状态机（scanLabels / CorpusScan.Preprocess / 测试装置
 * CorpusValidator.ErbPreprocess），判据一致但「未知指令」处理互相分叉
 * （scanLabels 消费、CorpusScan 当普通行）——本类按原版口径统一为消费。
 */
class ErbPreprocessor(
    private val debugMode: Boolean,
    macroNames: Set<String>,
    private val file: String,
) {
    /** 装载期告警（level 1 语义：不参与成败判定，但必须可见）。 */
    val warnings = ArrayList<LoadWarning>()

    var disabled = false
        private set

    /** spec: ErbLoader.cs:27 —— `[SKIPSTART]` 区间的强制位，独立于 [disabled]。 */
    private var skip = false
    private var done = false
    private val ppMatch = ArrayList<String>()          // 期望闭合：SKIPEND / ELSEIF / ENDIF（末尾 = 栈顶）
    private val disabledStack = ArrayList<Boolean>()
    private val doneStack = ArrayList<Boolean>()

    /** 宏名大小写不敏感（原版 `Config.StrComper` = OrdinalIgnoreCase，Config.cs:299-308）。 */
    private val macroKeys: Set<String> = macroNames.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }

    /**
     * 处理一条逻辑行（content 需已 trim）。返回 true = 该行被预处理消费（指令或未知
     * `[...]` 行），调用方跳过；返回 false = 普通行。
     */
    fun process(content: String, lineNo: Int): Boolean {
        if (!content.startsWith("[") || content.startsWith("[[")) return false
        val body = content.substring(1).substringBefore(']')
        // B3（2026-10-26 fixcore 波）：keyword/token2 改按标识符终止符截断
        // （Symbols.IDENT_TERMINATORS，与 core 词法器 readSingleIdentifier 同表，
        // spec: LexicalAnalyzer.cs:315-328）—— 此前按空白 split，会把 `[SKIPSTART,Foo]`
        // 整体当指令名、`[IF A,B]` 的宏名读成 "A,B"。语料三形态实测 0 例，低风险。
        //
        // R8（2026-10-26）：keyword **前**的空白**不跳** —— 原版 `ReadSingleIdentifier`
        // 从不跳前导空白（spec: LexicalAnalyzer.cs:310-325），ErbLoader.cs:393 里 shiftNext
        // 越过 '[' 后直接读 keyword。故 `[ IF 宏]` 的 keyword 读成**空串**（→ 未知指令，
        // 不改状态）；core 侧 LogicalLineReader.kt:217 同口径。此前 [lead] 多跳过一处空白，
        // 会把 `[ IF 宏]` 误当有效 IF 开区间，与 core/原版分叉（`PreprocessorParityTest`
        // 的 `leadingSpaceAfterBracketMakesKeywordEmpty` 钉住）。
        val kwRaw = body.takeWhile { it !in Symbols.IDENT_TERMINATORS }
        val kw = kwRaw.uppercase(Locale.ROOT)
        // spec: ErbLoader.cs:394-395 —— 只有指令名与 token2 **之间**才 SkipWhiteSpace，
        // 且 token2 只读**一个** identifier。其余文字原版是 InvalidSBrackets /
        // IgnoreAfterPreprosessor 的 level 1 告警，不进状态机（CMP-41：`[IF A B]` 的宏名是 A）。
        val rest = body.substring(kwRaw.length).dropWhile { it == ' ' || it == '\t' }
        val arg = rest.takeWhile { it !in Symbols.IDENT_TERMINATORS }.uppercase(Locale.ROOT)
        // spec: ErbLoader.cs:390-406 + LexicalAnalyzer.cs:315-328 —— `[IF (A)]` 这类
        // token2 为空 = MissingArguments level 1 告警后不改状态（见 addKeyword 的 IF 分支）。
        addKeyword(kw, arg, lineNo)
        // spec: ErbLoader.cs:163-166 —— 每条指令处理后无条件 clamp（含 warn+break 路径：
        // C# 的 break 只跳出 switch，仍会落到这条 if）。对照 core 侧
        // LogicalLineReader.kt:126 的同一条 clamp。
        if (skip) disabled = true
        return true
    }

    /**
     * spec: ErbLoader.cs:39-167 AddKeyWord —— 每条 case 的 Warn(level 1) + break 语义：
     * 告警后**不改**栈与 disabled 状态，直接返回（clamp 由 [process] 统一施加）。
     */
    private fun addKeyword(kw: String, arg: String, lineNo: Int) {
        when (kw) {
            "SKIPSTART" -> {                      // ErbLoader.cs:43-60
                if (arg.isNotEmpty()) {
                    warn(lineNo, "[SKIPSTART] 多余参数（原版 HasTooManyArg level 1：忽略该指令，不改变状态）")
                    return
                }
                if (skip) {
                    warn(lineNo, "[SKIPSTART] 重复使用（原版 DuplicateSkipstart level 1：忽略，不嵌套）")
                    return
                }
                ppMatch.add("SKIPEND"); disabledStack.add(disabled); doneStack.add(done)
                skip = true
                disabled = true
                done = false
            }
            "SKIPEND" -> {                        // ErbLoader.cs:127-141
                if (arg.isNotEmpty()) {
                    warn(lineNo, "[SKIPEND] 多余参数（原版 HasTooManyArg level 1：忽略该指令，不改变状态）")
                    return
                }
                // 原版先弹再判：不匹配时栈顶已被消耗，只告警
                val top = if (ppMatch.isEmpty()) "" else ppMatch.removeAt(ppMatch.size - 1)
                if (top != "SKIPEND") {
                    warn(lineNo, "[SKIPSTART] と対応しない [SKIPEND]（原版 UnexpectedSkipend level 1：忽略）")
                    return
                }
                skip = false
                disabled = disabledStack.removeAt(disabledStack.size - 1)
                done = doneStack.removeAt(doneStack.size - 1)
            }
            "IF", "IF_DEBUG", "IF_NDEBUG" -> {    // ErbLoader.cs:61-96
                if (kw == "IF" && arg.isEmpty()) {
                    warn(lineNo, "[IF] 缺参数（原版 MissingArguments level 1：忽略该指令，不改变状态）")
                    return
                }
                if (kw != "IF" && arg.isNotEmpty()) {
                    warn(lineNo, "[$kw] 多余参数（原版 HasTooManyArg level 1：忽略该指令，不改变状态）")
                    return
                }
                ppMatch.add("ELSEIF"); disabledStack.add(disabled); doneStack.add(done)
                disabled = when (kw) {
                    "IF" -> arg !in macroKeys    // GetMacro(arg)==null
                    "IF_DEBUG" -> !debugMode
                    else -> debugMode
                }
                done = !disabled
            }
            "ELSEIF" -> {                          // ErbLoader.cs:97-111
                if (arg.isEmpty()) {
                    warn(lineNo, "[ELSEIF] 缺参数（原版 MissingArguments level 1：忽略该指令，不改变状态）")
                    return
                }
                // 原版先弹再判；不匹配时空栈/错位栈顶已被消耗，只告警
                val top = if (ppMatch.isEmpty()) "" else ppMatch.removeAt(ppMatch.size - 1)
                if (top != "ELSEIF") {
                    warn(lineNo, "[ELSEIF] 无匹配的 [IF]（原版 IsInvalid level 1：忽略，不改变状态）")
                    return
                }
                ppMatch.add("ELSEIF")
                disabled = done || arg !in macroKeys
                done = done || !disabled
            }
            "ELSE" -> {                            // ErbLoader.cs:112-126
                if (arg.isNotEmpty()) {
                    warn(lineNo, "[ELSE] 多余参数（原版 HasTooManyArg level 1：忽略该指令，不改变状态）")
                    return
                }
                val top = if (ppMatch.isEmpty()) "" else ppMatch.removeAt(ppMatch.size - 1)
                if (top != "ELSEIF") {
                    warn(lineNo, "[ELSE] 无匹配的 [IF]（原版 IsInvalid level 1：忽略，不改变状态）")
                    return
                }
                ppMatch.add("ENDIF")
                disabled = done
                done = true
            }
            "ENDIF" -> {                           // ErbLoader.cs:142-158
                if (arg.isNotEmpty()) {
                    warn(lineNo, "[ENDIF] 多余参数（原版 HasTooManyArg level 1：忽略该指令，不改变状态）")
                    return
                }
                val top = if (ppMatch.isEmpty()) "" else ppMatch.removeAt(ppMatch.size - 1)
                if (top != "ENDIF" && top != "ELSEIF") {
                    warn(lineNo, "対応する [IF] のない [ENDIF]（原版 UnexpectedMacroEndif level 1：忽略）")
                    return
                }
                disabled = disabledStack.removeAt(disabledStack.size - 1)
                done = doneStack.removeAt(doneStack.size - 1)
            }
            // spec: ErbLoader.cs:159-161 —— 未知指令 Warn(UnrecognizedPreprosessor, 1) 后整行
            // 已被消费（不进正文）。原版对 `[` 起头行的分派在 AddKeyWord 之前（:387-405），
            // 无论识别与否都不再当普通行解析。
            else -> {
                warn(lineNo, "未知预处理指令 [$kw]（原版 Warn level 1: UnrecognizedPreprosessor，该行不进正文）")
            }
        }
    }

    /** 文件结束：最内层未闭合区间告警一次（spec: ErbLoader.cs:169-180）。 */
    fun fileEnd(lineNo: Int) {
        if (ppMatch.isNotEmpty()) {
            var text = ppMatch.removeAt(ppMatch.size - 1)
            if (text == "ELSEIF") text = "ENDIF"
            warn(lineNo, "预处理区间未闭合：缺少 [$text]（原版 Warn level 1: ～がありません，spec: ErbLoader.cs:169-180）")
        }
    }

    private fun warn(lineNo: Int, message: String) {
        warnings.add(LoadWarning(file, lineNo, message))
    }

    companion object {
        /**
         * `{ }` 续行块的连接串 = `Config.ReplaceContinuationBR` 去引号
         * （spec: EraStreamReader.cs:178-179；本游戏 emuera.config:72 `" "`，
         * Config.cs:386 + ConfigData.cs:119）。erbcc 不读游戏 config，按本游戏
         * 的单空格固定；直接拼接会把相邻两行的标识符粘成一个 token，与切片侧分叉。
         */
        fun continuationSeparator(): String = " "
    }
}
