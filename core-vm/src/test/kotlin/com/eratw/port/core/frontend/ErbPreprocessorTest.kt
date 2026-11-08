package com.eratw.port.core.frontend

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 预处理状态机单测（2026-10-31 R7-B/L6 下沉后建立）。
 *
 * 为什么这些用例必须存在：预处理是整条编译链里**最容易出静默错位**的一环 ——
 * 它不产生语法错误、只改变「哪些行进入下游」。少一行 / 多一行都不会报错，
 * 只会让符号表登记幽灵 `@函数`、或让整段正文凭空消失，产物 `program.erbc`
 * 表面合法。所以本文件的判据一律是「**发射行的确切集合**」+「**告警的确切条数**」，
 * 不用「不抛异常」当通过标准。
 *
 * 覆盖面对照原 `:tools:erbcc` 侧（`PreprocessorParityTest` 7 例 +
 * `SymbolsTest.scanLabelsWarnsOnUnclosedPreprocessRegion` 1 例 = 8 例），本文件 24 例。
 * 负向用例集中在「未闭合 / 非法嵌套 / 畸形指令」：断言的是**明确告警且状态不变**，
 * 不是静默吞掉。
 *
 * 语义出处全部是原版 `ErbLoader.cs:25-181`（`PPState`），逐条写在断言旁。
 */
class ErbPreprocessorTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 一个薄装置：按调用方（`CorpusScan.scan` / `SymbolTable.scanLabels`）的真实姿势
     * 逐行喂 `process`，收集「既未被消费也未被禁用」的行 —— 那才是下游能看见的正文。
     */
    private class Harness(
        debugMode: Boolean = false,
        macros: Set<String> = emptySet(),
        file: String = "fixture.erb",
    ) {
        val pp = ErbPreprocessor(debugMode, macros, file)
        val emitted = ArrayList<String>()

        fun feed(vararg lines: String): Harness = feed(lines.toList())

        fun feed(lines: List<String>): Harness {
            for ((i, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (pp.process(line, i + 1)) continue
                if (pp.disabled) continue
                emitted.add(line)
            }
            return this
        }

        fun finish(lineNo: Int = 0): Harness {
            pp.fileEnd(lineNo)
            return this
        }

        val messages: List<String> get() = pp.warnings.map { it.message }
    }

    // ------------------------------------------------------------------
    // 宏展开：[IF 宏] 只做「宏名是否在表里」的判定（ErbLoader.cs:94/109）
    // ------------------------------------------------------------------

    @Test
    fun definedMacroEmitsThenBranch() {
        // spec: ErbLoader.cs:94 —— Disabled = GetMacro(token2) == null；已定义 ⇒ 分支 enabled
        val h = Harness(macros = setOf("FOO")).feed("[IF FOO]", "@IN", "[ENDIF]", "@OUT")
        assertEquals(listOf("@IN", "@OUT"), h.emitted)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun undefinedMacroDropsThenBranch() {
        val h = Harness().feed("[IF FOO]", "@IN", "[ENDIF]", "@OUT")
        assertEquals(listOf("@OUT"), h.emitted, "未定义宏的分支整段不发射")
        assertEquals(emptyList(), h.messages, "未定义宏不是畸形指令，不告警")
    }

    @Test
    fun macroNameIsCaseInsensitive() {
        // spec: Config.StrComper = OrdinalIgnoreCase（Config.cs:299-308）—— 两侧都归一
        assertEquals(listOf("@IN"), Harness(macros = setOf("foo")).feed("[IF FOO]", "@IN", "[ENDIF]").emitted)
        assertEquals(listOf("@IN"), Harness(macros = setOf("FOO")).feed("[IF foo]", "@IN", "[ENDIF]").emitted)
    }

    @Test
    fun elseifChainPicksFirstMatchingBranch() {
        // spec: ErbLoader.cs:97-111 —— done 位保证「只取第一条命中分支」，后面的命中不再回退
        val h = Harness(macros = setOf("B"))
            .feed("[IF A]", "@A", "[ELSEIF B]", "@B", "[ELSEIF C]", "@C", "[ELSE]", "@D", "[ENDIF]")
        assertEquals(listOf("@B"), h.emitted)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun elseTakesAllRemainingWhenNothingMatched() {
        val h = Harness()
            .feed("[IF A]", "@A", "[ELSEIF B]", "@B", "[ELSE]", "@ELSE", "[ENDIF]", "@OUT")
        assertEquals(listOf("@ELSE", "@OUT"), h.emitted)
    }

    @Test
    fun debugModeFlipsDebugDirectives() {
        // spec: ErbLoader.cs:59-96 —— IF_DEBUG 判 !debugMode、IF_NDEBUG 判 debugMode
        val release = Harness(debugMode = false).feed("[IF_DEBUG]", "@D", "[ELSE]", "@N", "[ENDIF]")
        assertEquals(listOf("@N"), release.emitted)
        val debug = Harness(debugMode = true).feed("[IF_DEBUG]", "@D", "[ELSE]", "@N", "[ENDIF]")
        assertEquals(listOf("@D"), debug.emitted)
    }

    // ------------------------------------------------------------------
    // 嵌套条件：内层在 disabled 区间内也必须正确开闭（ErbLoader.cs:390-406）
    // ------------------------------------------------------------------

    @Test
    fun nestedIfInsideDisabledBranchKeepsOuterDisabled() {
        // 外层未命中 ⇒ 内层整段不得发射；ENDIF 逐个出栈后 @OUT 恢复
        //
        // 注意口径边界（已核原版）：`PPState` 的 `Disabled`/`done` 是**标量字段 + 两个并行栈**
        // （spec: ErbLoader.cs:25-38），内层 `[IF]` 只 push 外层值、求值只看自己的宏；
        // `[ELSE]` 分支写的是 `Disabled = done; done = true;`（spec: ErbLoader.cs:112-126）——
        // **不读外层 Disabled**。所以「外层 disabled、内层命中」这一形态下，内层正文**会**发射。
        // 这是原版行为（见下面 `nestedIfInsideDisabledBranchCanStillEmit` 的钉子），不是本次下沉引入的差异。
        // 本用例只用「内外层都不命中」的形态，钉住「栈正确成对出入」。
        val h = Harness().feed(
            "[IF OUTER]",
            "[IF INNER]",
            "@DEEP",
            "[ENDIF]",
            "@MID",
            "[ENDIF]",
            "@OUT",
        )
        assertEquals(listOf("@OUT"), h.emitted, "内外层都不命中时整段不发射，ENDIF 逐个出栈后恢复")
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun nestedIfInsideEnabledBranchEvaluatesIndependently() {
        val h = Harness(macros = setOf("OUTER")).feed(
            "[IF OUTER]",
            "@MID",
            "[IF INNER]",
            "@DEEP",
            "[ENDIF]",
            "[ENDIF]",
            "@OUT",
        )
        assertEquals(listOf("@MID", "@OUT"), h.emitted, "外层 enabled 时内层仍按自己的宏判定")
    }

    @Test
    fun skipstartClampDropsNestedIfGhost() {
        // CMP-39 必红件：spec: ErbLoader.cs:163-166 —— 每条指令后无条件 `if (skip) disabled = true`。
        // 缺这条 clamp 时 `[IF_NDEBUG]` 会把 disabled 置回 false，`@GHOST` 就会被发射、
        // 被符号表登记成原版根本不编译的函数。
        val h = Harness().feed("[SKIPSTART]", "[IF_NDEBUG]", "@GHOST", "[ENDIF]", "[SKIPEND]", "@OUT")
        assertEquals(listOf("@OUT"), h.emitted, "SKIPSTART 内的 [IF_NDEBUG] 不得放行 @GHOST")
    }

    @Test
    fun skipstartClampAlsoAppliesToElse() {
        val h = Harness().feed("[SKIPSTART]", "[IF X]", "[ELSE]", "@GHOST", "[ENDIF]", "[SKIPEND]", "@OUT")
        assertEquals(listOf("@OUT"), h.emitted)
    }

    @Test
    fun nestedIfInsideDisabledBranchCanStillEmit() {
        // 原版口径（易被「顺手修好」的坑，钉死防回归）：`[ELSE]` 只把本层 `done` 写进 Disabled
        // （spec: ErbLoader.cs:112-126），**不读**外层 Disabled ⇒ 外层未命中、内层命中时，
        // 内层正文照样发射。core 侧 `LogicalLineReader` 与 erbcc 同此行为，不许单方面「修正」。
        val h = Harness(macros = setOf("INNER")).feed(
            "[IF OUTER]",
            "@OUTER_BODY",
            "[IF INNER]",
            "@INNER_BODY",
            "[ELSE]",
            "@INNER_ELSE",
            "[ENDIF]",
            "[ENDIF]",
        )
        assertEquals(
            listOf("@INNER_BODY"),
            h.emitted,
            "外层未命中不抑制内层命中分支（原版标量 Disabled 口径）；@OUTER_BODY/@INNER_ELSE 仍不发射",
        )
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun directivesAreProcessedWhileDisabled() {
        // spec: ErbLoader.cs:390-406 —— 禁用期间指令照常处理，否则嵌套区间会错位（少一个 ENDIF）
        val h = Harness().feed(
            "[IF OFF]",
            "@HIDDEN",
            "[IF ALSO_OFF]",
            "[ENDIF]",
            "[ENDIF]",
            "@OUT",
        )
        assertEquals(listOf("@OUT"), h.emitted, "嵌套 ENDIF 必须逐个出栈，否则 @OUT 会一直禁用")
        assertEquals(emptyList(), h.messages)
    }

    // ------------------------------------------------------------------
    // 未闭合区间（负向）：ErbLoader.cs:169-180 FileEnd，只报最内层一次
    // ------------------------------------------------------------------

    @Test
    fun unclosedSkipstartWarnsOnceAndNamesSkipend() {
        val h = Harness().feed("[SKIPSTART]", "@HIDDEN").finish(lineNo = 2)
        assertEquals(emptyList(), h.emitted)
        assertEquals(1, h.messages.size, "FileEnd 只对最内层未闭合区间告警一次")
        assertTrue(h.messages[0].contains("[SKIPEND]"), "缺的是 SKIPEND，文案要点名：${h.messages[0]}")
        assertEquals(2, h.pp.warnings[0].line, "告警行号 = 调用方给的 fileEnd 行号")
        assertEquals("fixture.erb", h.pp.warnings[0].file, "告警要带文件名，否则多文件语料无法定位")
    }

    @Test
    fun unclosedIfReportsEndifNotElseif() {
        // spec: ErbLoader.cs:169-180 —— ppMatch 栈顶是 ELSEIF 时对外报 ENDIF（原版 `text = "ENDIF"`）
        val h = Harness().feed("[IF A]", "@HIDDEN").finish()
        assertEquals(1, h.messages.size)
        assertTrue(h.messages[0].contains("[ENDIF]"), "未闭合的 IF 应报缺 ENDIF，而不是 ELSEIF：${h.messages[0]}")
    }

    @Test
    fun unclosedNestedRegionReportsOnlyInnermost() {
        val h = Harness().feed("[IF A]", "[IF B]", "[SKIPSTART]").finish()
        assertEquals(1, h.messages.size, "一次 fileEnd 只报最内层（原版也只弹一层）")
        assertTrue(h.messages[0].contains("[SKIPEND]"), "最内层是 SKIPSTART ⇒ 报 SKIPEND：${h.messages[0]}")
        // 再调一次 = 下一个未闭合层（模拟调用方按层收尾），不得静默变空
        h.finish()
        assertEquals(2, h.messages.size)
    }

    @Test
    fun closedRegionProducesNoFileEndWarning() {
        val h = Harness(macros = setOf("A")).feed("[IF A]", "@IN", "[ENDIF]").finish()
        assertEquals(emptyList(), h.messages, "正常闭合不得产生任何告警（假告警会污染装载日志）")
    }

    // ------------------------------------------------------------------
    // 非法嵌套 / 畸形指令（负向）：ErbLoader.cs:44-158 Warn(level 1) + break，不改状态
    // ------------------------------------------------------------------

    @Test
    fun strayEndifWarnsAndDoesNotSwallowFollowingLines() {
        // 空栈 [ENDIF] 原版 UnexpectedMacroEndif（ErbLoader.cs:142-158）后 break：
        // 若误把 disabled 置 true，后面整段正文会静默消失 —— 这条钉的就是「不静默吞」。
        val h = Harness().feed("[ENDIF]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), h.emitted, "空栈 ENDIF 不得把后续正文吞掉")
        assertEquals(1, h.messages.size)
        assertTrue(h.messages[0].contains("[ENDIF]"))
    }

    @Test
    fun strayElseAndElseifWarnWithoutChangingState() {
        val h = Harness().feed("[ELSE]", "[ELSEIF A]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), h.emitted)
        assertEquals(2, h.messages.size, "[ELSE] + [ELSEIF] 各报一条")
    }

    @Test
    fun straySkipendWarnsAndKeepsEnabled() {
        val h = Harness().feed("[SKIPEND]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), h.emitted)
        assertEquals(1, h.messages.size)
        assertTrue(h.messages[0].contains("[SKIPEND]"))
    }

    @Test
    fun endifClosingSkipstartIsRejected() {
        // 错位嵌套：[SKIPSTART] 的期望闭合是 SKIPEND，[ENDIF] 不得把它关掉。
        // 原版先弹再判（spec: ErbLoader.cs:142-158）⇒ 栈顶被消耗，只剩告警；skip 强制位
        // 仍把 disabled 夹回 true（ErbLoader.cs:163-166），所以 @STILL_HIDDEN 依然不发射。
        val h = Harness().feed("[SKIPSTART]", "@HIDDEN", "[ENDIF]", "@STILL_HIDDEN").finish()
        assertEquals(emptyList(), h.emitted, "[ENDIF] 关不掉 SKIPSTART 区间")
        assertEquals(1, h.messages.size, "UnexpectedMacroEndif 一条")
    }

    @Test
    fun skipendClosingIfIsRejected() {
        // 错位嵌套：[SKIPEND] 不匹配 [IF] ⇒ 只告警，disabled 保持 IF 的求值结果（A 已定义 ⇒ enabled）
        val h = Harness(macros = setOf("A")).feed("[IF A]", "@IN", "[SKIPEND]", "@AFTER").finish()
        assertEquals(listOf("@IN", "@AFTER"), h.emitted, "[SKIPEND] 不改 disabled，enabled 分支照常发射")
        assertEquals(1, h.messages.size, "UnexpectedSkipend 一条")
    }

    @Test
    fun missingAndExtraArgumentsWarnWithoutChangingState() {
        // spec: ErbLoader.cs:44-52/85-90/112-117 —— HasTooManyArg / MissingArguments 后 break
        val missingIf = Harness().feed("[IF]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), missingIf.emitted, "[IF] 缺参数不得开区间")
        assertEquals(1, missingIf.messages.size)

        val extraElse = Harness(macros = setOf("A"))
            .feed("[IF A]", "@IN", "[ELSE extra]", "@AFTER", "[ENDIF]")
            .finish()
        assertEquals(listOf("@IN", "@AFTER"), extraElse.emitted, "[ELSE extra] 不改状态 ⇒ enabled 分支继续")
        assertEquals(1, extraElse.messages.size)

        val extraSkipstart = Harness().feed("[SKIPSTART extra]", "@KEPT", "[SKIPEND]", "@ALSO_KEPT").finish()
        assertEquals(listOf("@KEPT", "@ALSO_KEPT"), extraSkipstart.emitted, "[SKIPSTART extra] 不开区间")
        assertEquals(2, extraSkipstart.messages.size, "HasTooManyArg + UnexpectedSkipend")
    }

    @Test
    fun duplicateSkipstartDoesNotNest() {
        // spec: ErbLoader.cs:49-53 —— DuplicateSkipstart 后 break，不嵌套；第一个 SKIPEND 就收区
        val h = Harness().feed("[SKIPSTART]", "[SKIPSTART]", "@INNER", "[SKIPEND]", "@OUTER", "[SKIPEND]").finish()
        assertEquals(listOf("@OUTER"), h.emitted)
        assertEquals(2, h.messages.size, "DuplicateSkipstart + UnexpectedSkipend")
    }

    @Test
    fun elseifAfterElseIsRejected() {
        // [ELSE] 把栈顶换成 ENDIF ⇒ 其后的 [ELSEIF] 不再匹配（原版 IsInvalid 后 break，栈顶已被消耗），
        // 于是随后的 [ENDIF] 也失去配对再报一条 —— 两条告警都要可见，不许静默。
        val h = Harness(macros = setOf("A"))
            .feed("[IF A]", "@IN", "[ELSE]", "@ELSE", "[ELSEIF A]", "@AFTER_ELSEIF", "[ENDIF]")
            .finish()
        assertEquals(listOf("@IN"), h.emitted, "命中 IF 后 ELSE 分支不发射，[ELSEIF] 也不得复活它")
        assertEquals(2, h.messages.size, "[ELSEIF] 无匹配 [IF] + 后续 [ENDIF] 也失去配对")
    }

    // ------------------------------------------------------------------
    // 指令行识别口径（LexicalAnalyzer.cs:310-328 + ErbLoader.cs:387-405）
    // ------------------------------------------------------------------

    @Test
    fun nonDirectiveLinesAreNotConsumed() {
        val h = Harness().feed("@FUNC", "PRINTL 1", "#DIM X, 1", "[[ESCAPED]]")
        assertFalse(h.pp.process("@FUNC", 1), "普通行必须返回 false")
        assertFalse(h.pp.process("[[ESCAPED]]", 4), "`[[` 转义形态不是指令（spec: ErbLoader.cs:387）")
        assertEquals(listOf("@FUNC", "PRINTL 1", "#DIM X, 1", "[[ESCAPED]]"), h.emitted)
    }

    @Test
    fun unknownDirectiveIsConsumedAndWarned() {
        // spec: ErbLoader.cs:159-161 + :387-405 —— 未知指令告警后整行不进正文（返回 true）
        val h = Harness().feed("[BOGUS 1]", "@AFTER").finish()
        assertTrue(h.pp.process("[BOGUS]", 9), "未知指令行同样被消费")
        assertEquals(listOf("@AFTER"), h.emitted, "未知指令行不得当普通行下发")
        assertEquals(2, h.messages.size)
        assertTrue(h.messages[1].contains("BOGUS"), "告警要点名指令：${h.messages[1]}")
    }

    @Test
    fun leadingSpaceAfterBracketMakesKeywordEmpty() {
        // spec: ErbLoader.cs:393 + LexicalAnalyzer.cs:310-325 —— ReadSingleIdentifier 不跳前导空白，
        // `[ IF X]` 的 keyword 是空串 ⇒ 落未知指令，**不开区间**。旧 erbcc 多跳一处空白，
        // 会把 `[ IF UNDEF]` 当有效 IF，把后面整段静默禁用。
        val h = Harness().feed("[ IF UNDEF]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), h.emitted)
        assertEquals(1, h.messages.size, "keyword 空 ⇒ 未知指令告警一条")
    }

    @Test
    fun ifTakesOnlyOneIdentifierAsMacroName() {
        // CMP-41：spec: ErbLoader.cs:393-395 —— token2 只读一个 identifier，`[IF A B]` 的宏名是 A
        val h = Harness(macros = setOf("A")).feed("[IF A B]", "@IN", "[ENDIF]").finish()
        assertEquals(listOf("@IN"), h.emitted, "A 已定义 ⇒ 分支 enabled（余文 B 不参与判定）")
    }

    @Test
    fun bodyEndsAtFirstCloseBracket() {
        // spec: ErbLoader.cs:387-395 —— 指令体到第一个 ']' 为止，其后原文不参与状态机
        val h = Harness(macros = setOf("A")).feed("[IF A] trailing", "@IN", "[ENDIF]").finish()
        assertEquals(listOf("@IN"), h.emitted)
        assertEquals(emptyList(), h.messages)
    }

    @Test
    fun identifierTerminatorsMatchLexerTable() {
        // 终止符表必须与 core.lexer 词法器同表（spec: LexicalAnalyzer.cs:28）。这里**行为化**钉死：
        // 表内字符把 `[IF A<c>B]` 截成宏名 A（A 已定义 ⇒ @IN 发射）；表外字符让宏名变 A<c>B（未定义 ⇒ 丢弃）。
        // 该表在三处各有一份私有副本（core.lexer / 本包 / erbcc Symbols），任一处漂移都会让
        // `[IF A,B]` 的宏名分叉 —— 这条用例是本包那份的守门人。
        val terminators = " \u3000.+-*/%=!<>|&^~?#)}],:({[\$\\'\"@;\t"
        for (c in terminators) {
            // ']' 会先截断 body（substringBefore），走的是另一条口径，单独由 bodyEndsAtFirstCloseBracket 覆盖
            if (c == ']') continue
            val h = Harness(macros = setOf("A")).feed("[IF A${c}B]", "@IN", "[ENDIF]")
            assertEquals(
                listOf("@IN"),
                h.emitted,
                "字符 U+%04X 应在标识符终止符表内（宏名被截成 A）".format(c.code),
            )
        }
        for (c in listOf('Z', '_', 'あ', '0')) {
            val h = Harness(macros = setOf("A")).feed("[IF A${c}B]", "@IN", "[ENDIF]")
            assertEquals(
                emptyList(),
                h.emitted,
                "字符 U+%04X 不在终止符表内 ⇒ 宏名是 A${c}B（未定义）".format(c.code),
            )
        }
    }

    // ------------------------------------------------------------------
    // 二进制 / 损坏输入：不许崩、不许静默当普通行放行
    // ------------------------------------------------------------------

    @Test
    fun binaryGarbageLinesDoNotCrashOrSlipThroughSilently() {
        // 前置说明：**二进制文件跳过**（前 8KB 出现 NUL 即整文件跳过，附 level-1 告警）的闸
        // 不在本类，而在上游 `CorpusScan.readForScan`（尚未下沉）。本类拿到的是**已解码**的行，
        // 因此这里钉的是它的边界责任：损坏行不得让状态机崩溃，也不得被静默当成普通正文下发。
        //
        // ① 解码一段真二进制（含 NUL / SOH / DEL）不抛异常：预处理器只吃 String。
        //    「不以 '[' 起头就当普通行透传」是本类的既定口径 —— 二进制文件整体跳过的决定
        //    必须由上游读取层做（读的时候才看得见原始字节），预处理器无权也不该猜。
        val blob = byteArrayOf(0x00, 0x01, 0x7F, 0x0A, 0x42, 0x0A)
        val decoded = String(blob, StandardCharsets.UTF_8).split('\n')
        val h = Harness(macros = setOf("A")).feed(decoded).feed("[IF A]", "@IN", "[ENDIF]").finish()
        assertEquals(
            listOf("\u0000\u0001\u007F", "B", "@IN"),
            h.emitted,
            "二进制垃圾行原样透传（跳过与否归上游），其后状态机仍可用（未污染 disabled）",
        )
        assertEquals(emptyList(), h.messages, "透传的垃圾行不是指令，不得产生假告警")

        // ② 指令体内含 NUL：不匹配任何指令 ⇒ 未知指令**留痕并被消费**，不许静默当普通行下发
        val nul = Harness().feed("[IF\u0000]", "@AFTER").finish()
        assertEquals(listOf("@AFTER"), nul.emitted, "含 NUL 的指令行不得当普通正文下发")
        assertEquals(1, nul.messages.size)
        assertTrue(nul.messages[0].contains("未知预处理指令"), "损坏指令必须留痕：${nul.messages}")
    }

    @Test
    fun continuationSeparatorIsSingleSpace() {
        // spec: EraStreamReader.cs:178-179 + 本游戏 emuera.config:72 —— `{}` 块连接串 = 一个空格。
        // 注意：拼接循环本身在 CorpusScan / LogicalLineReader 里，本类只提供这个常量。
        assertEquals(" ", ErbPreprocessor.continuationSeparator())
        assertContentEquals(
            "A B".toByteArray(StandardCharsets.UTF_8),
            ("A" + ErbPreprocessor.continuationSeparator() + "B").toByteArray(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun warningsCarryFileLineAndOrder() {
        val h = Harness(file = "ERB/sub/foo.erb").feed("[BOGUS]", "[ENDIF]").finish(lineNo = 7)
        assertEquals(2, h.pp.warnings.size)
        assertEquals("ERB/sub/foo.erb", h.pp.warnings[0].file)
        assertEquals(1, h.pp.warnings[0].line)
        assertEquals("ERB/sub/foo.erb", h.pp.warnings[1].file)
        assertEquals(2, h.pp.warnings[1].line, "告警按发生顺序追加，行号逐条可定位")
    }
}
