package com.eratw.port.core.lexer

/**
 * 原版 `Lang.Error` 文案键 —— 全工程唯一的「键 → 文案」渲染层。
 *
 * ## 为什么要有这一层
 * 原版每条报错都是 `Lang.Error.<键>.Text`（`MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs`），
 * 带 `{0}`/`{1}`/`{2}` 位置参数，由 `string.Format` 展开；译文另有一份
 * `MinorShift.Emuera.Properties.lang.emuera-zhs.xml`（同键、`Error.<键>` 的 `tr id`）。
 * 移植侧此前把文案直接写成 Kotlin 字面量（自造句），既对不上原文、也不带占位符渲染，
 * 于是同一条错误的中文形散落在各处、`{0}` 无处可填。[LangKey] 把模板与实参个数收在一处，
 * 每条都带两侧出处行号，`specs/error-texts.yaml` 是它的可审计副本（表与枚举由
 * `ErrorTextRenderTest` 对账）。
 *
 * ## 语言口径（本波裁定，显式声明）
 * 1. **内核/契约层报错用日文原文**（[LangVariant.JA]）：`Lang.cs` 的模板逐字照抄，`{0}` 照常展开。
 *    词法层的 [LexError] / [LexWarning] 走这一档（与 `core/fn/ConfigMethods.kt` 的 `LangError`
 *    同口径：`CodeEE` 的 message 不做本地化改写）。
 * 2. **使用者面诊断取 zhs 译文**（[LangVariant.ZHS]）：`lang.emuera-zhs.xml` 的中文形逐字照抄。
 *    `core/stmt/BlockChecker`（装载期块结构错误）与 `core/stmt/ArgBuilders` 的 warn/fail
 *    （语句参数构造失败，带「命令名 + 命令:」前缀）走这一档（与 `core/fn/HtmlStrict.kt`、
 *    `core/vars/VariableStore.kt` 的既有裁决同口径）。
 *
 * ## 照抄纪律（ADR-005）
 * 只做「按序号原位替换」，不 trim、不改引号形态、不修误译：zhs 表里的全角/半角引号混用
 * （如 [OutsideSelectcase] 是半角直引号、`HtmlStrict` 的同类键是全角）、甚至明显误译
 * （[UnexpectedEndif] 的 zhs 写作「缺少IF对应的ENDIF」，与日文原文语义相反）都原样上屏。
 *
 * @property templateJa `Lang.cs` 的日文原文模板（`{0}` 未展开）
 * @property templateZhs zhs 译文表里的中文模板（`{0}` 未展开）
 * @property argCount 模板里的占位符个数 = `string.Format` 实参个数（也是 [render] 的实参契约）
 * @property langCsLine 日文原文所在的 `Lang.cs` 行号
 * @property zhsXmlLine zhs 译文所在的 `lang.emuera-zhs.xml` 行号
 */
enum class LangKey(
    val templateJa: String,
    val templateZhs: String,
    val argCount: Int,
    val langCsLine: Int,
    val zhsXmlLine: Int,
) {

    // -----------------------------------------------------------------------
    // 一、词法层（Lang.cs:3554-3594 / zhs:843-853）
    // 消费点：LexicalAnalyzer 的 6 条自造句（本轮只出回执，接线由组长做）
    // -----------------------------------------------------------------------

    /** `Lang.cs:3554` / zhs:843；调用点 `LexicalAnalyzer.cs` 宏展开计数。 */
    MacroOverLimit(
        "マクロの展開数が1文あたりの上限値{0}を超えました(自己参照・循環参照のおそれ)",
        "宏展开的次数超过单次展开上限值{0}(可能是因为自我调用・循环调用)",
        1, 3554, 843,
    ),

    /** `Lang.cs:3562` / zhs:845；调用点 `LexicalAnalyzer.cs` 全角空格。 */
    UnexpectedFullWidthSpace(
        "予期しない全角スペースを発見しました(この警告はシステムオプション「{0}」により無視できます)",
        "发现意料之外的全角空格(此警告可以用系统配置「{0}」来无视)",
        1, 3562, 845,
    ),

    /** `Lang.cs:3582` / zhs:850；调用点 `LexicalAnalyzer.cs` 未知字符。 */
    UnexpectedCharacter(
        "字句解析中に予期しない文字'{0}'を発見しました",
        "词法解析中出现意料之外的字符'{0}'",
        1, 3582, 850,
    ),

    /** `Lang.cs:3586` / zhs:851；调用点 `LexicalAnalyzer.cs` 空 `[[]]`。 */
    EmptyTwoSBrackets(
        "空の[[]]です",
        "空的[[]]",
        0, 3586, 851,
    ),

    /** `Lang.cs:3590` / zhs:852；调用点 `LexicalAnalyzer.cs` 缺 `]]`。 */
    MissingTwoSBrackets(
        "対応する\"]]\"のない\"[[\"です",
        "没有对应\"]]\"的\"[[\"",
        0, 3590, 852,
    ),

    /** `Lang.cs:3594` / zhs:853；调用点 `LexicalAnalyzer.cs` 不可 rename 的符号。 */
    CanNotRenameKey(
        "字句解析中に置換(rename)できない符号{0}を発見しました",
        "发现词法解析中无法被替换(rename)的符号{0}",
        1, 3594, 853,
    ),

    // -----------------------------------------------------------------------
    // 二、ArgumentBuilder 族（Lang.cs:1918-2086 / zhs:434-476）
    // 消费点：core/stmt/ArgBuilders 的 fail/warn（带「命令名 + 命令:」前缀）
    // -----------------------------------------------------------------------

    /** `Lang.cs:1918` / zhs:434；`ArgumentBuilder.cs:32` 的前缀本体（两侧同为「命令:」）。 */
    Instruction(
        "命令:",
        "命令:",
        0, 1918, 434,
    ),

    /** `Lang.cs:1922` / zhs:435；`ArgumentBuilder.cs:46`、`ArgumentParser.cs:454`/`:495` 等。 */
    MissingArg(
        "引数が設定されていません",
        "缺少参数",
        0, 1922, 435,
    ),

    /** `Lang.cs:1926` / zhs:436；`ArgumentBuilder.cs:51`。 */
    NotEnoughArguments(
        "引数が足りません",
        "参数过少",
        0, 1926, 436,
    ),

    /** `Lang.cs:1930` / zhs:437；`ArgumentBuilder.cs:57`（level 1，截断多余实参）。 */
    TooManyArg(
        "引数が多すぎます",
        "参数过多",
        0, 1930, 437,
    ),

    /** `Lang.cs:1934` / zhs:438；`ArgumentBuilder.cs:71`。 */
    CanNotRecognizeArg(
        "第{0}引数を認識できません",
        "无法识别第{0}参数",
        1, 1934, 438,
    ),

    /** `Lang.cs:1938` / zhs:439；`ArgumentBuilder.cs:77`。 */
    IncorrectArg(
        "第{0}引数の型が正しくありません",
        "第{0}参数的类型错误",
        1, 1938, 439,
    ),

    /** `Lang.cs:1942` / zhs:440；`ArgumentBuilder.cs:96`、`ArgumentParser.cs:531`（VARSIZE）。 */
    ArgIsNotVariable(
        "第{0}引数に変数以外を指定することはできません",
        "第{0}参数必须为变量",
        1, 1942, 440,
    ),

    /** `Lang.cs:1946` / zhs:441；`ArgumentBuilder.cs:101`。 */
    ArgIsConst(
        "第{0}引数に変更できない変数を指定することはできません",
        "第{0}参数不能指定为只读变量",
        1, 1946, 441,
    ),

    /** `Lang.cs:1974` / zhs:448；`ArgumentParser.cs:412-417`（FORM_STR_ANY 逗号后耗尽）。 */
    MissingArgAfterComma(
        "','の後ろに引数がありません。",
        ",'后面必须存在一个参数",
        0, 1974, 448,
    ),

    /** `Lang.cs:1978` / zhs:449；`ArgumentParser.cs:431`（VOID 构造器多余实参，level 1）。 */
    ArgIsNotRequired(
        "引数は不要です",
        "不需要参数",
        0, 1978, 449,
    ),

    /** `Lang.cs:1994` / zhs:453；`ArgumentParser.cs:542`（VARSIZE 名后多余字符，level 1）。 */
    ExtraCharacterAfterArg(
        "引数の後に余分な文字があります",
        "参数后面有一个多余的字符",
        0, 1994, 453,
    ),

    /** `Lang.cs:2078` / zhs:474；`ArgumentParser.cs:2406`（SP_INPUTS 第 2 参非整数，忽略）。 */
    IgnoreArgBecauseNotInt(
        "第2引数は整数型ではないため、無視されます",
        "第二个参数因为不是整型数值而被忽略",
        0, 2078, 474,
    ),

    /** `Lang.cs:2082` / zhs:475；`ArgumentParser.cs:1197-1207`（INT_EXPRESSION 省略实参补 0）。 */
    OmittedArg1(
        "省略できない引数が省略されています。Emueraは0を補います",
        "必要的参数被省略了。Emuera自动补0",
        0, 2082, 475,
    ),

    /** `Lang.cs:2086` / zhs:476；`ArgumentParser.cs` 的 eramaker 兼容档（本工程暂未使用）。 */
    OmittedArg2(
        "省略できない引数が省略されています。Emueraは0を補いますがeramakerの動作は不定です",
        "必要的参数被省略了。Emuera自动补0，但在eramaker中的行为未定义",
        0, 2086, 476,
    ),

    // -----------------------------------------------------------------------
    // 三、块结构族（Lang.cs:2590-2714 / zhs:602-633）
    // 消费点：core/stmt/BlockChecker（对应 ErbLoader 的 funcMatch 校验）
    // -----------------------------------------------------------------------

    /** `Lang.cs:2590` / zhs:602；`ErbLoader.cs:1100`（SELECTCASE 前置区）与 `:1245`（CASE 无母块）。 */
    OutsideSelectcase(
        "SELECTCASE構文の分岐の外に命令\"{0}\"が含まれています",
        "在SELECTCASE语法的分支以外包含命令\"{0}\"",
        1, 2590, 602,
    ),

    /** `Lang.cs:2606` / zhs:606；`ErbLoader.cs:1214`（ELSE/ELSEIF 栈顶不是 IF）。 */
    InvalidElse(
        "IF～ENDIFの外で\"{0}\"文が使われました",
        "在IF-ENDIF以外使用了\"{0}\"",
        1, 2606, 606,
    ),

    /** `Lang.cs:2614` / zhs:608；`ErbLoader.cs:1229`。注意 zhs 是官方的反向误译，照抄。 */
    UnexpectedEndif(
        "対応するIFの無いENDIF文です",
        "缺少IF对应的ENDIF",
        0, 2614, 608,
    ),

    /** `Lang.cs:2618` / zhs:609；`ErbLoader.cs:1251`/`:1279`（SELECTCASE 的 stack2 恢复）。 */
    InstructionNotClosed(
        "{0}文に対応する{1}がない状態で{2}文に到達しました",
        "在{0}没有对应{1}情况下运行到{2}语句",
        3, 2618, 609,
    ),

    /** `Lang.cs:2626` / zhs:611；`ErbLoader.cs:1272`（ENDSELECT 无母块）。 */
    UnexpectedEndselect(
        "対応するSELECTCASEの無いENDSELECT文です",
        "应对selectcase无endselect文。",
        0, 2626, 611,
    ),

    /** `Lang.cs:2634` / zhs:613；`ErbLoader.cs:1332`（REND/NEXT/WEND/LOOP 找母块）与 `:1568`（EOF 残留）。 */
    MissingCorresponding(
        "対応する\"{0}\"の無い\"{1}\"文です",
        "缺少\"{0}\"对应的\"{1}\"",
        2, 2634, 613,
    ),

    /** `Lang.cs:2638` / zhs:614；`ErbLoader.cs:1343`（CATCH 无 TRYC 系）。 */
    MissingTryc(
        "対応するTRYC系命令がありません",
        "缺少对应的TRYC系统命令",
        0, 2638, 614,
    ),

    /** `Lang.cs:2642` / zhs:615；`ErbLoader.cs:1353`（ENDCATCH 无母块）。 */
    UnexpectedEndcatch(
        "対応するCATCHのないENDCATCHです",
        "缺少CATCH对应的ENDCATCH",
        0, 2642, 615,
    ),

    /** `Lang.cs:2662` / zhs:620；`ErbLoader.cs:1412`（DATALIST 无 PRINTDATA 系母块）。 */
    UnexpectedDatalist(
        "対応するPRINTDATA系命令のないDATALISTです",
        "缺少PRINTDATA系列命令对应的DATALIST",
        0, 2662, 620,
    ),

    /** `Lang.cs:2666` / zhs:621；`ErbLoader.cs:1422`（ENDLIST 无母块）。 */
    UnexpectedEndlist(
        "対応するDATALISTのないENDLISTです",
        "缺少DATALIST对应的ENDLIST",
        0, 2666, 621,
    ),

    /** `Lang.cs:2678` / zhs:624；`ErbLoader.cs:1458`（ENDDATA 无 PRINTDATA 系/STRDATA 母块）。 */
    MissingPrintdataStrdata(
        "対応するPRINTDATA系命令もしくはSTRDATAのない\"{0}\"です",
        "缺少PRINTDATA系列命令或STRDATA系列命令对应的{0}",
        1, 2678, 624,
    ),

    /** `Lang.cs:2694` / zhs:628；`ErbLoader.cs:1495`/`:1525`（ENDFUNC 无 TRYCALLLIST 系母块）。 */
    MissingTrycalllist(
        "対応するTRYCALLLIST系命令のない\"{0}\"です",
        "缺少TRYCALLLIST系列命令对应的{0}",
        1, 2694, 628,
    ),

    /** `Lang.cs:2714` / zhs:633；`ErbLoader.cs:1551`（ENDNOSKIP 无母块）。 */
    MissingNoskip(
        "対応するNOSKIP系命令のない\"{0}\"です",
        "缺少NOSKIP系列命令对应的{0}",
        1, 2714, 633,
    ),

    // -----------------------------------------------------------------------
    // 四、行解析族（本轮只登记，接线归 L06 面：LexicalAnalyzer.parseSharpLine / LineParser.splitArgs）
    // -----------------------------------------------------------------------

    /** `Lang.cs:2354` / zhs:543；`LogicalLineParser.cs:38`（`#` 行关键字为空）。 */
    CanNotInterpretSharpLine(
        "解釈できない#行です",
        "解析#行失败",
        0, 2354, 543,
    ),

    /** `Lang.cs:2878` / zhs:674；`LogicalLineParser.cs:523-527`（命令后非法字符，提示开关）。 */
    InvalidCharacterAfterInstruction1(
        "命令で行が始まっていますが、命令の直後に半角スペース・タブ以外の文字が来ています(この警告はシステムオプション「{0}」により無視できます)",
        "以命令开始的行在命令后有半角空格和水平制表符以外的文字(此警告可以用系统配置「{0}」来无视)",
        1, 2878, 674,
    ),

    /** `Lang.cs:2882` / zhs:675；同上（不带开关提示的短形）。 */
    InvalidCharacterAfterInstruction2(
        "命令で行が始まっていますが、命令の直後に半角スペース・タブ以外の文字が来ています",
        "以命令开始的行在命令后有半角空格和水平制表符以外的文字",
        0, 2882, 675,
    ),
    ;

    /**
     * 按 [variant] 展开模板。
     *
     * 实参个数必须等于 [argCount]（`string.Format` 的实参表是硬契约，多/少都算调用点写错，
     * 直接 `require` 抛而不是静默补空 —— 报错路径上再吞错会让缺陷更难查）。
     */
    fun render(variant: LangVariant, vararg args: Any?): String {
        require(args.size == argCount) {
            "LangKey.$name 需要 $argCount 个占位符实参，实际 ${args.size}"
        }
        return fill(if (variant == LangVariant.JA) templateJa else templateZhs, args)
    }

    /** 使用者面默认档（zhs）—— 口径见类头。 */
    fun render(vararg args: Any?): String = render(LangVariant.ZHS, *args)

    /** 内核档：`Lang.cs` 日文原文。 */
    fun renderJa(vararg args: Any?): String = render(LangVariant.JA, *args)

    /** zhs 译文档（显式名，避免调用点看不出语言）。 */
    fun renderZhs(vararg args: Any?): String = render(LangVariant.ZHS, *args)

    private fun fill(template: String, args: Array<out Any?>): String {
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                if (j > i + 1 && j < template.length && template[j] == '}') {
                    val index = template.substring(i + 1, j).toInt()
                    // C# string.Format 的 null 实参上屏为空串。
                    sb.append(args.getOrNull(index)?.toString() ?: "")
                    i = j + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/** 文案语言档 —— 口径见 [LangKey] 类头（内核 ja / 使用者面 zhs）。 */
enum class LangVariant {
    /** `Lang.cs` 的日文原文。 */
    JA,

    /** `lang.emuera-zhs.xml` 的中文译文。 */
    ZHS,
}

/**
 * ArgumentBuilder 族的统一前缀 —— 逐字对应 `ArgumentBuilder.cs:32`：
 * `line.Function.Name + Lang.Error.Instruction.Text + mes`，即「语句名 + 命令: + 文案」。
 *
 * 只属于 ArgumentBuilder 族：`assignwarn`（`ArgumentBuilder.cs:19-28`）与
 * `ParserMediator.Warn`（`ErbLoader.cs` 各块结构校验）都不加前缀，故 [BlockChecker] 不走这里。
 */
fun instructionPrefix(statementName: String): String =
    statementName + LangKey.Instruction.renderZhs()
