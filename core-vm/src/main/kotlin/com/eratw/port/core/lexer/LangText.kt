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
 * ## 语言口径（三语 i18n，2026-11-07 定稿）
 * 1. **内核/契约层报错用日文原文**（[LangVariant.JA]）：`Lang.cs` 的模板逐字照抄，`{0}` 照常展开。
 *    词法层的 [LexError] / [LexWarning] 走这一档（与 `core/fn/ConfigMethods.kt` 的 `LangError`
 *    同口径：`CodeEE` 的 message 不做本地化改写）。
 * 2. **使用者面诊断按壳层语言设置三选一**（[LangVariant.ZHS] / [LangVariant.EN] /
 *    [LangVariant.JA]）：app 壳层用 `SettingsLanguage` 单路映射（`GameHost` 的
 *    `langVariantOf`），core 不读系统 Locale、不持有全局语言状态（AGENTS 铁律 4）。
 *    `core/stmt/BlockChecker`（装载期块结构错误）与 `core/stmt/ArgBuilders` 的 warn/fail
 *    （语句参数构造失败，带「命令名 + 命令:」前缀）走这一档。
 * 3. **英文档 = `lang.emuera-eng.xml` 逐字照抄**（[LangVariant.EN]）；官方英译漏占位符的
 *    三条（[UnexpectedFullWidthSpace] / [InvalidCharacterAfterInstruction1] /
 *    [LoaddataArgIsNegative]）按 ADR-005 原样照抄，`ErrorTextRenderTest` 里单列白名单。
 *
 * ## 照抄纪律（ADR-005）
 * 只做「按序号原位替换」，不 trim、不改引号形态、不修误译：zhs 表里的全角/半角引号混用
 * （如 [OutsideSelectcase] 是半角直引号、`HtmlStrict` 的同类键是全角）、甚至明显误译
 * （[UnexpectedEndif] 的 zhs 写作「缺少IF对应的ENDIF」，与日文原文语义相反）都原样上屏。
 *
 * @property templateJa `Lang.cs` 的日文原文模板（`{0}` 未展开）
 * @property templateZhs zhs 译文表里的中文模板（`{0}` 未展开）
 * @property templateEn eng 译文表里的英文模板（`{0}` 未展开）
 * @property argCount 模板里的占位符个数 = `string.Format` 实参个数（也是 [render] 的实参契约）
 * @property langCsLine 日文原文所在的 `Lang.cs` 行号
 * @property zhsXmlLine zhs 译文所在的**仓内 reference 内嵌表**行号，即
 *   `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml`（**不是**语料根的
 *   `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml`）。两张表的**行号不同源**、多数键**同文**：
 *   实测本枚举全部条目的 [zhsXmlLine] 都能在 reference 表那一行命中同键同文（`MacroOverLimit`
 *   在 reference 表 :843，而语料根表 :843 是 `SystemLine.DoYouOverwrite`、`MacroOverLimit`
 *   在语料根表实际是 :455）。语料根表的行号按键记在 `specs/error-texts.yaml` 的
 *   `zhs_root_xml` 字段里，两表不同文的键（`FuncPrefix` / `ArgIsNot*` / `NotVarFunc` /
 *   `XmlParseError` 几族）按组长裁定一律不动、本枚举只收 reference 形。
 */
enum class LangKey(
    val templateJa: String,
    val templateZhs: String,
    val templateEn: String,
    val argCount: Int,
    val langCsLine: Int,
    val zhsXmlLine: Int,
    val engXmlLine: Int,
) {

    // -----------------------------------------------------------------------
    // 一、词法层（Lang.cs:3554-3594 / zhs:843-853）
    // 消费点：LexicalAnalyzer 的 6 条自造句（本轮只出回执，接线由组长做）
    // -----------------------------------------------------------------------

    /** `Lang.cs:3554` / zhs:843；调用点 `LexicalAnalyzer.cs` 宏展开计数。 */
    MacroOverLimit(
        "マクロの展開数が1文あたりの上限値{0}を超えました(自己参照・循環参照のおそれ)",
        "宏展开的次数超过单次展开上限值{0}(可能是因为自我调用・循环调用)",
        "Maximum number of macro expansions per sentence {0} has been exceeded (Beware of self or circular references)",
        1, 3554, 843, 846,
    ),

    /** `Lang.cs:3562` / zhs:845；调用点 `LexicalAnalyzer.cs` 全角空格。 */
    UnexpectedFullWidthSpace(
        "予期しない全角スペースを発見しました(この警告はシステムオプション「{0}」により無視できます)",
        "发现意料之外的全角空格(此警告可以用系统配置「{0}」来无视)",
        "Found an unexpected full-width space (This error can be ignored by using \"Include full-width space in whitespace\" system option)",
        1, 3562, 845, 848,
    ),

    /** `Lang.cs:3582` / zhs:850；调用点 `LexicalAnalyzer.cs` 未知字符。 */
    UnexpectedCharacter(
        "字句解析中に予期しない文字'{0}'を発見しました",
        "词法解析中出现意料之外的字符'{0}'",
        "Found an unexpected character '{0}' during lexical analysis",
        1, 3582, 850, 853,
    ),

    /** `Lang.cs:3586` / zhs:851；调用点 `LexicalAnalyzer.cs` 空 `[[]]`。 */
    EmptyTwoSBrackets(
        "空の[[]]です",
        "空的[[]]",
        "[[]] is empty",
        0, 3586, 851, 854,
    ),

    /** `Lang.cs:3590` / zhs:852；调用点 `LexicalAnalyzer.cs` 缺 `]]`。 */
    MissingTwoSBrackets(
        "対応する\"]]\"のない\"[[\"です",
        "没有对应\"]]\"的\"[[\"",
        "Missing corresponding \"]]\" for \"[[\"",
        0, 3590, 852, 855,
    ),

    /** `Lang.cs:3594` / zhs:853；调用点 `LexicalAnalyzer.cs` 不可 rename 的符号。 */
    CanNotRenameKey(
        "字句解析中に置換(rename)できない符号{0}を発見しました",
        "发现词法解析中无法被替换(rename)的符号{0}",
        "Found an illegal substituted(rename) sign {0} during lexical analysis",
        1, 3594, 853, 856,
    ),

    // -----------------------------------------------------------------------
    // 二、ArgumentBuilder 族（Lang.cs:1918-2086 / zhs:434-476）
    // 消费点：core/stmt/ArgBuilders 的 fail/warn（带「命令名 + 命令:」前缀）
    // -----------------------------------------------------------------------

    /** `Lang.cs:1918` / zhs:434；`ArgumentBuilder.cs:32` 的前缀本体（两侧同为「命令:」）。 */
    Instruction(
        "命令:",
        "命令:",
        "Instruction:",
        0, 1918, 434, 437,
    ),

    /** `Lang.cs:1922` / zhs:435；`ArgumentBuilder.cs:46`、`ArgumentParser.cs:454`/`:495` 等。 */
    MissingArg(
        "引数が設定されていません",
        "缺少参数",
        "Missing argument",
        0, 1922, 435, 438,
    ),

    /** `Lang.cs:1926` / zhs:436；`ArgumentBuilder.cs:51`。 */
    NotEnoughArguments(
        "引数が足りません",
        "参数过少",
        "Not enough arguments",
        0, 1926, 436, 439,
    ),

    /** `Lang.cs:1930` / zhs:437；`ArgumentBuilder.cs:57`（level 1，截断多余实参）。 */
    TooManyArg(
        "引数が多すぎます",
        "参数过多",
        "Too many arguments",
        0, 1930, 437, 440,
    ),

    /** `Lang.cs:1934` / zhs:438；`ArgumentBuilder.cs:71`。 */
    CanNotRecognizeArg(
        "第{0}引数を認識できません",
        "无法识别第{0}参数",
        "Argument number {0}: unable to recognize argument",
        1, 1934, 438, 441,
    ),

    /** `Lang.cs:1938` / zhs:439；`ArgumentBuilder.cs:77`。 */
    IncorrectArg(
        "第{0}引数の型が正しくありません",
        "第{0}参数的类型错误",
        "Argument number {0}: argument type is incorrect",
        1, 1938, 439, 442,
    ),

    /** `Lang.cs:1942` / zhs:440；`ArgumentBuilder.cs:96`、`ArgumentParser.cs:531`（VARSIZE）。 */
    ArgIsNotVariable(
        "第{0}引数に変数以外を指定することはできません",
        "第{0}参数必须为变量",
        "The {0} argument cannot be anything other than a variable",
        1, 1942, 440, 443,
    ),

    /** `Lang.cs:1946` / zhs:441；`ArgumentBuilder.cs:101`。 */
    ArgIsConst(
        "第{0}引数に変更できない変数を指定することはできません",
        "第{0}参数不能指定为只读变量",
        "The {0} argument cannot be specified with a read-only variable",
        1, 1946, 441, 444,
    ),

    /**
     * `Lang.cs:1966` / zhs:446；调用点 `ArgumentParser.cs:2559-2563`（STR_DOUBLE 的首参表达式
     * 归约不出项时 `warn(WrongFormat, level 2)` 后 `return null`）。
     *
     * 本波（W10 · AUD-p9p10）新增：此前 `ArgBuilders.strDouble` 对「归约不出项」既不告警也不
     * 返回 null，而是把 null 塞进 `StrDoubleArgument` —— 与 `ArgumentParser.cs:2559-2563` 的
     * 「告警 + 作废该行」相反，且给运行期留了一个空表达式位。
     */
    WrongFormat(
        "書式が間違っています",
        "格式错误",
        "The format is wrong",
        0, 1966, 446, 449,
    ),

    /** `Lang.cs:1974` / zhs:448；`ArgumentParser.cs:412-417`（FORM_STR_ANY 逗号后耗尽）。 */
    MissingArgAfterComma(
        "','の後ろに引数がありません。",
        ",'后面必须存在一个参数",
        ",' doesn't have an argument after it",
        0, 1974, 448, 451,
    ),

    /** `Lang.cs:1978` / zhs:449；`ArgumentParser.cs:431`（VOID 构造器多余实参，level 1）。 */
    ArgIsNotRequired(
        "引数は不要です",
        "不需要参数",
        "Argument is not required",
        0, 1978, 449, 452,
    ),

    /** `Lang.cs:1994` / zhs:453；`ArgumentParser.cs:542`（VARSIZE 名后多余字符，level 1）。 */
    ExtraCharacterAfterArg(
        "引数の後に余分な文字があります",
        "参数后面有一个多余的字符",
        "There's an extra character after the argument",
        0, 1994, 453, 456,
    ),

    /** `Lang.cs:2078` / zhs:474；`ArgumentParser.cs:2406`（SP_INPUTS 第 2 参非整数，忽略）。 */
    IgnoreArgBecauseNotInt(
        "第2引数は整数型ではないため、無視されます",
        "第二个参数因为不是整型数值而被忽略",
        "Second argument will be ignored because not integer",
        0, 2078, 474, 477,
    ),

    /** `Lang.cs:2082` / zhs:475；`ArgumentParser.cs:1197-1207`（INT_EXPRESSION 省略实参补 0）。 */
    OmittedArg1(
        "省略できない引数が省略されています。Emueraは0を補います",
        "必要的参数被省略了。Emuera自动补0",
        "Arguments that cannot be omitted were omitted. Emuera will add 0",
        0, 2082, 475, 478,
    ),

    /** `Lang.cs:2086` / zhs:476；`ArgumentParser.cs` 的 eramaker 兼容档（本工程暂未使用）。 */
    OmittedArg2(
        "省略できない引数が省略されています。Emueraは0を補いますがeramakerの動作は不定です",
        "必要的参数被省略了。Emuera自动补0，但在eramaker中的行为未定义",
        "Arguments that cannot be omitted were omitted. Emuera adds 0, but the behavior of eramaker is undefined",
        0, 2086, 476, 479,
    ),

    // -----------------------------------------------------------------------
    // 三、块结构族（Lang.cs:2590-2714 / zhs:602-633）
    // 消费点：core/stmt/BlockChecker（对应 ErbLoader 的 funcMatch 校验）
    // -----------------------------------------------------------------------

    /** `Lang.cs:2590` / zhs:602；`ErbLoader.cs:1100`（SELECTCASE 前置区）与 `:1245`（CASE 无母块）。 */
    OutsideSelectcase(
        "SELECTCASE構文の分岐の外に命令\"{0}\"が含まれています",
        "在SELECTCASE语法的分支以外包含命令\"{0}\"",
        "Attempted to use instruction outside the branch of SELECTCASE syntax '{0}'",
        1, 2590, 602, 605,
    ),

    /** `Lang.cs:2606` / zhs:606；`ErbLoader.cs:1214`（ELSE/ELSEIF 栈顶不是 IF）。 */
    InvalidElse(
        "IF～ENDIFの外で\"{0}\"文が使われました",
        "在IF-ENDIF以外使用了\"{0}\"",
        "A statement other than IF-ENDIF was used \"{0}\"",
        1, 2606, 606, 609,
    ),

    /** `Lang.cs:2614` / zhs:608；`ErbLoader.cs:1229`。注意 zhs 是官方的反向误译，照抄。 */
    UnexpectedEndif(
        "対応するIFの無いENDIF文です",
        "缺少IF对应的ENDIF",
        "Missing corresponding IF for ENDIF",
        0, 2614, 608, 611,
    ),

    /** `Lang.cs:2618` / zhs:609；`ErbLoader.cs:1251`/`:1279`（SELECTCASE 的 stack2 恢复）。 */
    InstructionNotClosed(
        "{0}文に対応する{1}がない状態で{2}文に到達しました",
        "在{0}没有对应{1}情况下运行到{2}语句",
        "{0} statement has reached {1} statement without the corresponding {2} statement",
        3, 2618, 609, 612,
    ),

    /** `Lang.cs:2626` / zhs:611；`ErbLoader.cs:1272`（ENDSELECT 无母块）。 */
    UnexpectedEndselect(
        "対応するSELECTCASEの無いENDSELECT文です",
        "应对selectcase无endselect文。",
        "Missing corresponding SELECTCASE for ENDSELECT",
        0, 2626, 611, 614,
    ),

    /** `Lang.cs:2634` / zhs:613；`ErbLoader.cs:1332`（REND/NEXT/WEND/LOOP 找母块）与 `:1568`（EOF 残留）。 */
    MissingCorresponding(
        "対応する\"{0}\"の無い\"{1}\"文です",
        "缺少\"{0}\"对应的\"{1}\"",
        "Missing corresponding \"{0}\" for \"{1}\" statement",
        2, 2634, 613, 616,
    ),

    /** `Lang.cs:2638` / zhs:614；`ErbLoader.cs:1343`（CATCH 无 TRYC 系）。 */
    MissingTryc(
        "対応するTRYC系命令がありません",
        "缺少对应的TRYC系统命令",
        "Missing corresponding TRYC system instruction",
        0, 2638, 614, 617,
    ),

    /** `Lang.cs:2642` / zhs:615；`ErbLoader.cs:1353`（ENDCATCH 无母块）。 */
    UnexpectedEndcatch(
        "対応するCATCHのないENDCATCHです",
        "缺少CATCH对应的ENDCATCH",
        "Missing corresponding CATCH for ENDCATCH",
        0, 2642, 615, 618,
    ),

    /** `Lang.cs:2662` / zhs:620；`ErbLoader.cs:1412`（DATALIST 无 PRINTDATA 系母块）。 */
    UnexpectedDatalist(
        "対応するPRINTDATA系命令のないDATALISTです",
        "缺少PRINTDATA系列命令对应的DATALIST",
        "Missing corresponding PRINTDATA-type instruction for DATALIST",
        0, 2662, 620, 623,
    ),

    /** `Lang.cs:2666` / zhs:621；`ErbLoader.cs:1422`（ENDLIST 无母块）。 */
    UnexpectedEndlist(
        "対応するDATALISTのないENDLISTです",
        "缺少DATALIST对应的ENDLIST",
        "Missing corresponding DATALIST for ENDLIST",
        0, 2666, 621, 624,
    ),

    /** `Lang.cs:2678` / zhs:624；`ErbLoader.cs:1458`（ENDDATA 无 PRINTDATA 系/STRDATA 母块）。 */
    MissingPrintdataStrdata(
        "対応するPRINTDATA系命令もしくはSTRDATAのない\"{0}\"です",
        "缺少PRINTDATA系列命令或STRDATA系列命令对应的{0}",
        "Missing corresponding PRINTDATA-type or STRDATA-type instruction for {0}",
        1, 2678, 624, 627,
    ),

    /** `Lang.cs:2694` / zhs:628；`ErbLoader.cs:1495`/`:1525`（ENDFUNC 无 TRYCALLLIST 系母块）。 */
    MissingTrycalllist(
        "対応するTRYCALLLIST系命令のない\"{0}\"です",
        "缺少TRYCALLLIST系列命令对应的{0}",
        "Missing corresponding TRYCALLLIST-type instruction for \"{0}\"",
        1, 2694, 628, 631,
    ),

    /** `Lang.cs:2714` / zhs:633；`ErbLoader.cs:1551`（ENDNOSKIP 无母块）。 */
    MissingNoskip(
        "対応するNOSKIP系命令のない\"{0}\"です",
        "缺少NOSKIP系列命令对应的{0}",
        "Missing corresponding NOSKIP-type instruction for \"{0}\"",
        1, 2714, 633, 636,
    ),

    // -----------------------------------------------------------------------
    // 四、行解析族（本轮只登记，接线归 L06 面：LexicalAnalyzer.parseSharpLine / LineParser.splitArgs）
    // -----------------------------------------------------------------------

    /** `Lang.cs:2354` / zhs:543；`LogicalLineParser.cs:38`（`#` 行关键字为空）。 */
    CanNotInterpretSharpLine(
        "解釈できない#行です",
        "解析#行失败",
        "Failed to interpret # line",
        0, 2354, 543, 546,
    ),

    /** `Lang.cs:2878` / zhs:674；`LogicalLineParser.cs:523-527`（命令后非法字符，提示开关）。 */
    InvalidCharacterAfterInstruction1(
        "命令で行が始まっていますが、命令の直後に半角スペース・タブ以外の文字が来ています(この警告はシステムオプション「{0}」により無視できます)",
        "以命令开始的行在命令后有半角空格和水平制表符以外的文字(此警告可以用系统配置「{0}」来无视)",
        "Line begins with an instruction, but there are characters other than half-width space or tab following after it (This error can be ignored by using \"Include full-width space in whitespace\" system option)",
        1, 2878, 674, 677,
    ),

    /** `Lang.cs:2882` / zhs:675；同上（不带开关提示的短形）。 */
    InvalidCharacterAfterInstruction2(
        "命令で行が始まっていますが、命令の直後に半角スペース・タブ以外の文字が来ています",
        "以命令开始的行在命令后有半角空格和水平制表符以外的文字",
        "Line begins with an instruction, but there are characters other than half-width space or tab following after it",
        0, 2882, 675, 678,
    ),

    /**
     * `Lang.cs:2890` / zhs:677；调用点 `LogicalLineParser.cs:548-554` —— 赋值行的
     * `ReadAssignmentOperator` 读出 `OperatorCode.Equal`（`==`）时 `ParserMediator.Warn(…, 0)`
     * 后**照常按 `=` 执行**（level 0，纯提示，不改语义）。
     * zhs 原文的引号是 U+201D 右引号对（”==”），非半角直引号，照抄不修（ADR-005）。
     */
    Use2EqualToAssign(
        "代入演算子に\"==\"が使われています",
        "使用”==”作为赋值运算符",
        "Assignment statement is using relational operator \"==\"",
        0, 2890, 677, 680,
    ),

    // -----------------------------------------------------------------------
    // 五、用户变量名族（私有 #DIM 的重名与名字语法；IdentifierDictionary.cs:340-392 的三条闸）
    // -----------------------------------------------------------------------

    /**
     * `Lang.cs:2854` / zhs:668；调用点 `LogicalLineParser.cs:284-287` ——
     * `label.AddPrivateVariable` 对同函数体内已存在的键返回 false
     * （`FunctionLabelLine.cs:98-103`）后 `Warn(..., position, 2)` 并 `return false`（该行作废、
     * 后到者不注册，先到者保留）。
     */
    VarNameAlreadyUsed(
        "変数名\"{0}\"は既に使用されています",
        "变量名\"{0}\"已经被使用",
        "Variable name \"{0}\" is already in use",
        1, 2854, 668, 671,
    ),

    /**
     * `Lang.cs:3702` / zhs:879；调用点 `IdentifierDictionary.cs:355-359`
     * （`CheckUserPrivateVarName` 的第三条闸：`char.IsDigit(varName[0])` → warnLevel 2 →
     * `UserDefinedVariableData.cs:288-292` 抛 CodeEE，该行无效、变量不注册）。
     * 文案说「半角数字」，判据是 `char.IsDigit`（含全角数字，U+FF10-FF19 属 Nd），照抄不改。
     */
    VarStartedHalfDigit(
        "変数名\"{0}\"が半角数字から始まっています",
        "变量名\"{0}\"以半角数字开始",
        "Variable name \"{0}\" starts with a half-width digit",
        1, 3702, 879, 882,
    ),

    // -----------------------------------------------------------------------
    // 六、语句名族（PRINT / PRINTDATA 的后缀不变量；FunctionIdentifier.cs:167-170 / :264-267）
    //
    // 两条都是 `PRINT_Instruction` / `PRINT_DATA_Instruction` 构造尾部的**内部不变量断言**：
    // 名字来自 `code.ToString()`（FunctionIdentifier.cs:3814 / :3819），用户写的非法后缀
    // 在 funcDic 查表阶段就已失败，走不到这里。故原版抛 ExeEE 属「开发者可见诊断」
    // （ADR-005 例外第 1 条），移植侧照抄官方文案即可，不涉及 ERB 可观测语义。
    // -----------------------------------------------------------------------

    /** `Lang.cs:2250` / zhs:517；调用点 `FunctionIdentifier.cs:167-170`（PRINT 后缀残留）。 */
    AbnormalPrint(
        "PRINT異常",
        "PRINT异常",
        "Abnormal PRINT",
        0, 2250, 517, 520,
    ),

    /** `Lang.cs:2254` / zhs:518；调用点 `FunctionIdentifier.cs:264-267`（PRINTDATA 后缀残留）。 */
    AbnormalPrintdata(
        "PRINTDATA異常",
        "PRINTDATA异常",
        "Abnormal PRINTDATA",
        0, 2254, 518, 521,
    ),

    // -----------------------------------------------------------------------
    // 七、运行期下标 / 运算符 / XML / 标签族（2026-11-05 lane errtext4 收编入本通道）
    //
    // 这 14 条此前散在 `core/vars/VarErrorText.kt`、`core/err/OperatorErrorText.kt`、
    // `core/fn/XmlMethods.kt` 的字面量层里，`specs/error-texts.yaml` 与本枚举都不认它们
    // （逐键 grep `id: <键>` 0 命中）⇒ 本波把键补全，并由 `LangKeySourceTableTest` 逐条
    // 回读 reference 两份真源（`Lang.cs` + 内嵌 zhs 表）对拍。
    // 每条都标了 `Lang.cs` 行号、reference 内嵌表行号（= [zhsXmlLine]）、语料根表行号
    // （只在 `specs/error-texts.yaml` 的 `zhs_root_xml` 记，本处不重复）与原版抛出点。
    // 14 条**两张 zhs 表同文**（逐条核过），故不受「两表不同文一律不动」的裁定约束。
    // -----------------------------------------------------------------------

    /**
     * `Lang.cs:1326` / reference 表 :286（语料根表 :236 同文）；
     * 调用点 `OperatorMethodManager.cs:134`（DivIntInt）、`:153`（ModIntInt）——
     * 官方 0 占位符、直接抛 `.Text`，不做 `string.Format`。
     * 移植侧消费点：`core/err/OperatorErrorText.kt` 的 `DIVIDE_BY_ZERO_TEXT`
     * （表达式与字节码两条通道共用）。
     */
    DivideByZero(
        "0による除算が行なわれました",
        "除数为0的除法运算",
        "A division by zero has been attempted",
        0, 1326, 286, 289,
    ),

    /**
     * `Lang.cs:1318` / reference 表 :284（语料根表 :486 同文）；
     * 调用点 `OperatorMethodManager.cs:99`（MultStrInt 的负实参支，喂 `intValue.ToString()`）。
     */
    MultiplyNegativeToStr(
        "文字列に負の値({0})を乗算しようとしました",
        "字符串无法与负值({0})进行相乘运算",
        "Attempted to multiply a string negative amount of times({0})",
        1, 1318, 284, 287,
    ),

    /**
     * `Lang.cs:1322` / reference 表 :285（语料根表 :485 同文）；
     * 调用点 `OperatorMethodManager.cs:103`（MultStrInt 的 10000 及以上支；判式是 `>= 10000`，
     * 10000 本身走这一支）。
     */
    Multiply10kToStr(
        "文字列に10000以上の値({0})を乗算しようとしました",
        "字符串无法与超过10000的值({0})进行相乘运算",
        "Attempted to multiply a string more than 10000 times({0})",
        1, 1322, 285, 288,
    ),

    /**
     * `Lang.cs:1330` / reference 表 :287（语料根表 :770 同文）；
     * XML_GET 家族的解析错误前缀（`{0}` = 函数名、`{1}` = 底层解析器消息）。
     * 移植侧消费点：`core/fn/XmlMethods.kt` 的 `xmlParseErrorMessage`（`core/fn` 非本路所有，
     * 字面量与本条的同形由 `LangKeySourceTableTest` 钉住）。
     */
    XmlGetError(
        "XML_GET関数:\"{0}\"の解析エラー:{1}",
        "XML_GET函数:\"{0}\"的解析错误:{1}",
        "XML_GET:Interpreted error of \"{0}\":{1}",
        2, 1330, 287, 290,
    ),

    /**
     * `Lang.cs:1334` / reference 表 :288（语料根表 :771 同文）；
     * XML_GET 家族的 **XPath** 解析错误支（注意 `{0}` 前没有半角空格，紧贴 `XPath`）。
     */
    XmlGetPathError(
        "XML_GET関数:XPath\"{0}\"の解析エラー:{1}",
        "XML_GET函数:XPath\"{0}\"的解析错误:{1}",
        "XML_GET:Interpreted error of XPath\"{0}\":{1}",
        2, 1334, 288, 291,
    ),

    /**
     * `Lang.cs:1566` / reference 表 :346（语料根表 :774 同文）；
     * 调用点 `VariableParser.cs:178` —— 0 维变量被写成 `A:1` 这类带下标形态。
     */
    ZeroDVarHasArg(
        "配列でない変数\"{0}\"を引数付きで呼び出しています",
        "变量\"{0}\"不是数组却带了一个参数",
        "Variable that is not an array \"{0}\" is being called with an argument",
        1, 1566, 346, 349,
    ),

    /**
     * `Lang.cs:1622` / reference 表 :360（语料根表 :568 同文）；
     * 调用点 `CharaVariableToken.cs:60/64/68`（角色位 / 1 维 / 2 维，`{1}` 喂字面量 "1"/"2"/"3"）。
     * zhs 把 `{1}引数` 误译成 `{1}个变量`，ADR-005 原样保留。
     * 移植侧消费点：`core/vars/VarErrorText.kt` 的 `oorCharaVarArg`。
     */
    OoRCharaVarArg(
        "キャラクタ配列変数\"{0}\"の第{1}引数({2})はキャラ登録番号または配列の範囲外です",
        "角色数组变量\"{0}\"的第{1}个变量({2})超出角色登记编号的最大范围",
        "Character array variable \"{0}\" at the {1} argument ({2}) is out of the range of the character registration number ",
        3, 1622, 360, 363,
    ),

    /**
     * `Lang.cs:1626` / reference 表 :361（语料根表 :575 同文）；
     * 调用点 `UserDefinedVariableToken.cs:95/99`、`ReferenceToken.cs:83/87`、
     * `CharaVariableToken.cs:77/81` —— 实参依次是「语句名、1 基参数号、端点值、变量名」。
     *
     * 三处 `VariableEvaluator.cs` 的喂值口径：`SetValueAll`（`VARSET` 语句）在
     * `:275/:303` 传 `("VARSET", 3L, 4L)`；函数侧（SUMARRAY/MATCH/MAXARRAY/FINDELEMENT…）
     * 由 `FunctionMethodCreator.cs:4404/4472/4754/4947/5068/6002` 各带自己的语句名与参数号。
     * 注意 zhs 形与 ja 形不同：日文在 `{0}` 外带引号、中文不带（照抄中文形，ADR-005）。
     */
    OoRInstructionArg(
        "\"{0}\"命令の第{1}引数({2})は配列\"{3}\"の範囲外です",
        "{0}命令的第{1}个参数({2})超出数组\"{3}\"的范围",
        "The {1} argument of \"{0}\" instruction ({2}) is out of the range of the \"{3}\" array",
        4, 1626, 361, 364,
    ),

    /**
     * `Lang.cs:1630` / reference 表 :362（语料根表 :329 同文）；
     * 调用点 `ReferenceToken.cs:40`（REF 变量、无参 `GetLength()` 且 rank != 1）、
     * `UserDefinedVariableToken.cs:46`（用户变量同形态）。实参 = 「维数、变量名」。
     * 角色变量另有 `GetSizeCharaVarWithoutDim`（`CharaVariableToken.cs:40`）、
     * 0 维角色变量另有 `GetSize0DCharaVar`（`:38`）⇒ 不同键，不许混用。
     */
    GetSizeDimError(
        "{0}次元配列型変数\"{1}\"の長さを取得しようとしました",
        "试图获取一个{0}维数组变量\"{1}\"的长度",
        "Attempted to get the size of a {0} dimensional array variable \"{1}\"",
        2, 1630, 362, 365,
    ),

    /**
     * `Lang.cs:1614` / reference 表 :358（语料根表 :328 同文）；
     * 调用点 `CharaVariableToken.cs:40`（角色变量、无参 `GetLength()` 且元素维数 > 1）。
     * 与 [GetSizeDimError] 是**不同键**（角色变量族单独一条，中文形带「角色」二字），不许混用。
     */
    GetSizeCharaVarWithoutDim(
        "{0}次元配列型のキャラ変数\"{1}\"の長さを次元を指定せずに取得しようとしました",
        "试图获取一个{0}维角色变量\"{1}\"的长度",
        "Attempted to get the size of a {0} dimensional character variable \"{1}\" without specifying the dimension",
        2, 1614, 358, 361,
    ),

    /**
     * `Lang.cs:1610` / reference 表 :357（语料根表 :325 同文）；
     * 调用点 `CharaVariableToken.cs:38`（0 维角色变量取整体长度）。
     */
    GetSize0DCharaVar(
        "配列型でないキャラクタ変数\"{0}\"の長さを取得しようとしました",
        "试图获取一个非数组角色变量\"{0}\"的长度",
        "Attempted to get the size of a non-array character variable \"{0}\"",
        1, 1610, 357, 360,
    ),

    /**
     * `Lang.cs:1638` / reference 表 :364（语料根表 :585 同文）；
     * 调用点 `UserDefinedVariableToken.cs:62/66/70`、`:78/82/86`、`LocalVariableToken.cs:43`、
     * `ReferenceToken.cs:66/70/74`、`VariableData.cs:85/177`。
     * 移植侧消费点：`core/vars/VarErrorText.kt` 的 `oorVarArg`。
     */
    OoRVarArg(
        "配列変数\"{0}\"の第{1}引数({2})は配列の範囲外です",
        "数组变量\"{0}\"的第{1}参数({2})超出数组范围",
        "Array variable \"{0}\" at the {1} argument ({2}) is out of the range of the array",
        3, 1638, 364, 367,
    ),

    /**
     * `Lang.cs:1642` / reference 表 :365（语料根表 :272 同文）；
     * 调用点 `ReferenceToken.cs:36/49/62/120`、`VariableData.cs:2329/2338` —— REF 变量未绑定目标。
     * 移植侧消费点：`core/vars/VarErrorText.kt` 的 `emptyRefVar`。
     */
    EmptyRefVar(
        "参照型変数\"{0}\"は何も参照していません",
        "引用类型变量\"{0}\"缺少引用对象",
        "Reference type variable \"{0}\" does not refer to anything",
        1, 1642, 365, 368,
    ),

    /**
     * `Lang.cs:2342` / reference 表 :540（语料根表 :513 同文）；
     * 调用点 `FunctionIdentifier.cs:3703`（`JUMP` 系语句的标签名查不到 ⇒ CodeEE）、
     * `:3668`（同一模板的 warn 支，level 2 且 `isError`）。
     *
     * **`$` 是文案本体**：两张表都写作 `标签名"\${0}"`（日文 `ラベル名"\${0}"`），
     * 即屏上带 ERB 标签 sigil。Kotlin 侧写这个字面量必须转义 `\${0}` ——
     * 少一个反斜杠时 `${0}` 会被当成模板表达式（取实参 0），整条串塌成 `标签名"0"`；
     * `core/code/Vm.kt` 的旧字面量正是这么塌掉的（本波接线修掉，`core/proc/ProcessRuntime.kt:3568`
     * 同形待组长接线）。实参 = 名字**原文未大写化**（`:3703` 传 `text`）。
     */
    NotDefinedLabelName(
        "指定されたラベル名\"\${0}\"は現在の関数内に存在しません",
        "当前函数中不存在指定的标签名\"\${0}\"",
        "Specified label name \"\${0}\" does not exist in the current function",
        1, 2342, 540, 543,
    ),

    // -----------------------------------------------------------------------
    // 八、运行期变量形态 / 参数类型族（2026-11-05 lane negrange 收编）
    //
    // 本波「区间/下标/越界族自造文案清零」普查中判「官方有串」的 8 枚：两张 zhs 表**逐字同文**
    // （行号记在 specs/error-texts.yaml 的 zhs_root_xml），官方通道 = 运行期 CodeEE，与移植侧
    // 抛出点同通道 ⇒ 可接。消费点：`fn/VarWriteMethods.kt`（SETVAR / VARSETEX / EXISTVAR 的
    // 动态引用闸门与写入类型闸门）与 `vars/VariableStore.kt`（REF 多维 VARSIZE 取不存在维）。
    // -----------------------------------------------------------------------

    /**
     * `Lang.cs:3814` / reference 表 :907（语料根表 :415 同文）；
     * 调用点 `FunctionMethodCreator.cs:395/:403/:425/:433/:762/:782/:816/:926` ——
     * 动态变量名函数（SETVAR/VARSETEX/GETVAR/EXISTVAR 系）把字符串解析成表达式后落不到变量。
     * 注意 zhs 形 `{0}不是变量` 不带引号、实参紧贴文案（无空格），
     * 与退役的自造形「X 不是变量」（多一个半角空格）不同屏 ⇒ 本波接线即换屏。
     */
    IsNotVar(
        "\"{0}\"が変数ではありません",
        "{0}不是变量",
        "{0} is not variable",
        1, 3814, 907, 910,
    ),

    /**
     * `Lang.cs:3818` / reference 表 :908（语料根表 :411 同文）；
     * 调用点 `FunctionMethodCreator.cs:399/:768/:8569`。
     * 照抄纪律：SETVAR 官方把文案**用反了** —— 字符串变量收到非字符串值报本条（IsNotInt）、
     * 整数变量收到非整数报 [IsNotStr]（`:766-777`）；移植侧连这个缺陷一起复刻（ADR-005）。
     */
    IsNotInt(
        "\"{0}\"が整数型ではありません",
        "{0}不是整型",
        "{0} is not integer",
        1, 3818, 908, 911,
    ),

    /** `Lang.cs:3822` / reference 表 :909（语料根表 :413 同文）；调用点 `FunctionMethodCreator.cs:429/:776/:8611`。 */
    IsNotStr(
        "\"{0}\"が文字列型ではありません",
        "{0}不是字符串型",
        "{0} is not string",
        1, 3822, 909, 912,
    ),

    /**
     * `Lang.cs:1362` / reference 表 :295（语料根表 :639 同文）；
     * 调用点 `FunctionMethodCreator.cs:826`（VARSETEX 字符串目标收到非字符串值）。
     */
    SetStrToInt(
        "文字列型でない変数\"{0}\"に文字列型を代入しようとしました",
        "尝试将字符串赋值给非字符串变量\"{0}\"",
        "Attempted to set string to Non-string variable \"{0}\"",
        1, 1362, 295, 298,
    ),

    /**
     * `Lang.cs:1366` / reference 表 :296（语料根表 :638 同文）；
     * 调用点 `FunctionMethodCreator.cs:863`（VARSETEX 整数目标收到非整数）与
     * `VariableEvaluator.cs:321`（CVARSET 非整数目标）。
     */
    SetIntToStr(
        "整数型でない変数\"{0}\"に整数値を代入しようとしました",
        "尝试将整数赋值给非整数型变量\"{0}\"",
        "Attempted to set integer to Non-integer variable \"{0}\"",
        1, 1366, 296, 299,
    ),

    /**
     * `Lang.cs:1578` / reference 表 :349（语料根表 :481 同文）；
     * 调用点 `VariableNoArgTerm.cs:19-69` —— 需要下标的变量被**整体**取值/赋值。
     * 实参 = `Identifier.Name`（裸变量名），不是引用写法原文 ⇒ 移植侧喂 [com.eratw.port.core.expr.VarRef] 的 name。
     */
    MissingVarArg(
        "変数\"{0}\"に必要な引数が不足しています",
        "变量\"{0}\"缺少必要的参数",
        "Variable \"{0}\" is missing required arguments",
        1, 1578, 349, 352,
    ),

    /**
     * `Lang.cs:1634` / reference 表 :363（语料根表 :330 同文）；
     * 调用点 `ReferenceToken.cs:55`、`UserDefinedVariableToken.cs:55`、`LocalVariableToken.cs:36`、
     * `VariableData.cs:165/:265/:382/:695/:759` —— `VARSIZE X, N` 的 N 超出变量维数。
     * 与 [GetSizeDimError] 不同键（那是「多维变量不带维号取整体长度」），不许混用。
     */
    GetSizeNonExistDim(
        "配列型変数\"{0}\"の存在しない次元の長さを取得しようとしました",
        "试图获取一个不存在该维度的数组变量\"{0}\"的长度",
        "Attempted to get the size of the non-existing dimension of an array variable \"{0}\"",
        1, 1634, 363, 366,
    ),

    /**
     * `Lang.cs:1602` / reference 表 :355（语料根表 :91 同文）；
     * 调用点 `VariableToken.cs:212-216`（`GetArray()` 对角色数据抛）——
     * VARSETEX 的二/三维整块通道拿到角色变量时原版就在这炸，移植侧同点位同键。
     */
    CallCharaVarAsVar(
        "キャラクタ変数\"{0}\"を非キャラ変数として呼び出しました",
        "角色变量\"{0}\"被当作非角色变量调用",
        "Character variable \"{0}\" is called as a non-character variable",
        1, 1602, 355, 358,
    ),

    // -----------------------------------------------------------------------
    // 九、语句族运行期文案（lane errtext6 2026-11-05「negrange 需接线清单」收编）
    //
    // 这 33 枚的移植侧抛出点**本就逐字等于官方 zhs 展开形**（negrange 普查记作「官方同形
    // 字面量」），本波把键收进表与枚举、由 LangKeySourceTableTest 回读两份真源钉行号，
    // 再由 sem/StatementFamilyErrorTextDriftTest 装行级钉「产品行字面 ↔ 枚举模板」同形
    // （`used_at` 的行号是写入日读数，产品行漂移由 drift 闸的 needle 当场抓红）。
    // 33 枚全部**两张 zhs 表逐字同文**（reference 行号 = [zhsXmlLine]，语料根行号记在
    // specs 的 zhs_root_xml）。除 [TransparentUnsupported]（`vars/UserVariables.kt` 本波接线）
    // 外产品字面量一律不动 —— 判「不改-同形」，接线归后续波次。
    // -----------------------------------------------------------------------

    /** `Lang.cs:1418` / zhs 表 :309（根表 :564）；抛点 `VariableEvaluator.cs:712/:781`（ARRAYSHIFT 起点越界）。 */
    OoRArrayShift(
        "命令ARRAYSHIFTの第4引数({0})が配列\"{1}\"の範囲を超えています",
        "命令ARRAYSHIFT的第四个参数({0})超出了数组\"{1}\"的范围",
        "The fourth argument of the ARRAYSHIFT instruction ({0}) is out of range of the \"{1}\" array",
        2, 1418, 309, 312,
    ),

    /** `Lang.cs:1422` / zhs 表 :310（根表 :563）；抛点 `VariableEvaluator.cs:852`（ARRAYREMOVE 起点越界）。 */
    OoRArrayRemove(
        "命令ARRAYREMOVEの第2引数({0})が配列\"{1}\"の範囲を超えています",
        "命令ARRAYREMOVE的第二个参数({0})超出了数组\"{1}\"的范围",
        "The second argument of the ARRAYREMOVE instruction ({0}) is out of range of the \"{1}\" array",
        2, 1422, 310, 313,
    ),

    /** `Lang.cs:1426` / zhs 表 :311（根表 :565）；抛点 `VariableEvaluator.cs:900/:925`（ARRAYSORT 起点越界）。 */
    OoRArraySort(
        "命令ARRAYSORTの第3引数({0})が配列\"{1}\"の範囲を超えています",
        "命令ARRAYSORT的第三参数({0})超出了数组\"{1}\"的范围",
        "The third argument of the ARRAYSORT instruction ({0}) is out of range of the \"{1}\" array",
        2, 1426, 311, 314,
    ),

    /** `Lang.cs:1414` / zhs 表 :308（根表 :567）；抛点 `VariableEvaluator.cs:336`（CVARSET 元素下标越界，喂 name、字面量 "2"、num）。 */
    OoRCharaVar(
        "キャラクタ配列変数\"{0}\"の第{1}引数({2})は配列の範囲外です",
        "角色数组变量\"{0}\"的第{1}参数({2})超出数组范围",
        "Character array variable \"{0}\" at the {1} argument ({2}) is out of range of the array",
        3, 1414, 308, 311,
    ),

    /** `Lang.cs:3074` / zhs 表 :723（根表 :417）；抛点 `Process.cs:1619/:1666/:1683`（ARRAYSHIFT/REMOVE/SORT 非 1 维数组）。 */
    IsUsableOnly1DVar(
        "{0}は1次元配列および配列型キャラクタ変数のみに対応しています",
        "{0}仅对1位数组以及数组型角色变量有效",
        "{0} supports only one-dimensional arrays and character array variables",
        1, 3074, 723, 726,
    ),

    /** `Lang.cs:2294` / zhs 表 :528（根表 :83）；抛点 `FunctionIdentifier.cs:2597`（AWAIT 负值）。 */
    AwaitArgIsNegative(
        "AWAIT命令:負の値({0})が指定されました",
        "AWAIT命令:指定了一个负值({0})",
        "AWAIT instruction has been assigned with a negative value ({0})",
        1, 2294, 528, 531,
    ),

    /** `Lang.cs:2298` / zhs 表 :529（根表 :84）；AWAIT 超 10 秒；移植侧喂 `${v}ms` 与模板的 `({0}ms)` 同形。 */
    AwaitArgIsOver10Seconds(
        "AWAIT命令:10秒以上の待機時間({0}ms)が指定されました",
        "AWAIT命令:指定了一个超过10秒的值({0}ms)",
        "AWAIT instruction has been assigned with time of 10 seconds or more ({0}ms)",
        1, 2298, 529, 532,
    ),

    /** `Lang.cs:3022` / zhs 表 :710（根表 :634）；抛点 `Process.cs:1333`（SAVEDATA 槽号负值）。 */
    SavedataArgIsNegative(
        "SAVEDATAの引数に負の値({0})が指定されました",
        "对SAVEDATA的参数使用负值({0})",
        "A negative value has been specified to SAVEDATA ({0})",
        1, 3022, 710, 713,
    ),

    /** `Lang.cs:3026` / zhs 表 :711（根表 :673）；SAVEDATA 槽号超 Int32 上限。 */
    TooLargeSavedataArg(
        "SAVEDATAの引数({0})が大きすぎます",
        "SAVEDATA的参数值过大({0})",
        "Argument given to SAVEDATA ({0}) is too big",
        1, 3026, 711, 714,
    ),

    /** `Lang.cs:3030` / zhs 表 :712（根表 :635）；抛点 `Process.cs:1342`；zhs「SAVEDATA对的提示文本」是官方原译，照抄（ADR-005）。 */
    SavetextContainNewLineCharacter(
        "SAVEDATAのセーブテキストに改行文字が与えられました（セーブデータが破損するため改行文字は使えません）",
        "SAVEDATA对的提示文本使用了换行符号（由于会导致存档损坏，不能使用换行符）",
        "SAVEDATA save text contains a new line character (Save data will get corrupted)",
        0, 3030, 712, 715,
    ),

    /** `Lang.cs:3086` / zhs 表 :726（根表 :441）；抛点 `Process.cs:1846`（LOADDATA 槽号负值）。 */
    LoaddataArgIsNegative(
        "LOADDATAの引数に負の値({0})が指定されました",
        "LOADDATA的参数被指定为负数({0})",
        "A negative value has been specified to LOADDATA (\" + target.ToString() + \")",
        1, 3086, 726, 729,
    ),

    /** `Lang.cs:3090` / zhs 表 :727（根表 :672）；LOADDATA 槽号超上限。 */
    TooLargeLoaddataArg(
        "LOADDATAの引数({0})が大きすぎます",
        "LOADDATA的参数值({0})过大",
        "Argument given to LOADDATA ({0}) is too big",
        1, 3090, 727, 730,
    ),

    /** `Lang.cs:2278` / zhs 表 :524（根表 :580）；抛点 `FunctionIdentifier.cs:1886`（SAVECHARA 角色号越界，{0} = 实参序 i+3）。 */
    OoRSavecharaArg(
        "SAVECHARAの第{0}引数の値がキャラ登録番号の範囲を超えています",
        "SAVECHARA第{0}参数的值超出角色登记编号的范围",
        "The value of the {0} SAVECHARA argument exceeds the range of character registration number",
        1, 2278, 524, 527,
    ),

    /** `Lang.cs:2282` / zhs 表 :525（根表 :248）；抛点 `FunctionIdentifier.cs:1892`（SAVECHARA 角色号重复）。 */
    DuplicateCharaNo(
        "同一のキャラ登録番号({0})が複数回指定されました",
        "同一个角色登记编号({0})被多次指定",
        "Identical character registration number ({0}) has been specified more than once",
        1, 2282, 525, 528,
    ),

    /** `Lang.cs:3014` / zhs 表 :708（根表 :579）；PICKUPCHARA 的角色号越界。 */
    OoRPickupcharaArg(
        "命令PICKUPCHARAの第{0}引数にキャラリストの範囲外の値({1})が与えられました",
        "命令PICKUPCHARA的第{0}个参数的值({1})超过角色列表的范围",
        "Argument number {0} at the PICKUPCHARA instruction has been given a value outside of the character list range ({1})",
        2, 3014, 708, 711,
    ),

    /** `Lang.cs:1458` / zhs 表 :319（根表 :615）；引用未注册角色（CHARA 变量落到未定义角色位）。 */
    RefUndefinedChara(
        "定義していないキャラクタを参照しようとしました",
        "试图引用一个尚未定义的角色",
        "Attempted to refer to an undefined character",
        0, 1458, 319, 322,
    ),

    /** `Lang.cs:1114` / zhs 表 :233（根表 :574）；FORCEKANA 参数超出 0～3。 */
    OoRForcekanaArg(
        "命令FORCEKANAの引数が指定可能な範囲(0～3)を超えています",
        "命令FORCEKANA的参数超过可指定范围(0～3)",
        "The amount of allowed arguments for FORCEKANA instruction is out of range(0-3)",
        0, 1114, 233, 236,
    ),

    /** `Lang.cs:3082` / zhs 表 :725（根表 :75）；抛点 `Process.cs:1793`（ASSERT 实参为 0）。 */
    AssertArgIs0(
        "ASSERT文の引数が0です",
        "ASSERT的参数为0",
        "Argument in ASSERT statement is 0",
        0, 3082, 725, 728,
    ),

    /** `Lang.cs:3058` / zhs 表 :719（根表 :640）；SETCOLOR 分量小于 0。 */
    SetcolorArgLessThan0(
        "SETCOLORの引数に0未満の値が指定されました",
        "SETCOLOR的参数指定了小于0的值",
        "SETCOLOR was given an argument less than 0",
        0, 3058, 719, 722,
    ),

    /** `Lang.cs:3062` / zhs 表 :720（根表 :641）；SETCOLOR 分量大于 255 —— zhs 表原文就误写成「超过0的值」，照抄（ADR-005）。 */
    SetcolorArgOver255(
        "SETCOLORの引数に255を超える値が指定されました",
        "SETCOLOR的参数指定了超过0的值",
        "SETCOLOR was given an argument greater than 255",
        0, 3062, 720, 723,
    ),

    /**
     * `Lang.cs:2126` / zhs 表 :486（根表 :55）；喂法 = `ArgumentParser.cs:1750` 的两实参形
     * （参数号、实参值）。原版运行期支 `FunctionIdentifier.cs:752` 只喂了 "2" 一个实参
     * （`{1}` 缺实参 ⇒ .NET 在这一支会抛 FormatException，官方潜在缺陷）；移植屏上按两实参形走。
     */
    ArgIsOoRBit(
        "第{0}引数({1})がビット範囲(0～63)を超えています",
        "第{0}参数({1})超出范围(0-63)",
        "{0} argument ({1}) is outside the range (0-63)\"",
        2, 2126, 486, 489,
    ),

    /** `Lang.cs:2270` / zhs 表 :522（根表 :571）；抛点 `FunctionIdentifier.cs:1691/:1700`（CVARSET 区间端点；第 5 参支官方就喂的 num2=start，移植照抄）。 */
    OoRCvarsetArg(
        "命令CVARSETの第{0}引数({1})がキャラクタの範囲外です",
        "CVARSET命令的第{0}参数({1})超出角色范围",
        "{0} argument of CVARSET instruction ({1}) is out of character range",
        2, 2270, 522, 525,
    ),

    /** `Lang.cs:2226` / zhs 表 :511（根表 :73）；抛点 `Process.cs:1730/:1742`（ARRAYCOPY 目标不是数组）。 */
    ArraycopyArgIsNotArray(
        "ARRAYCOPY命令の第{0}引数\"{1}\"は配列変数ではありません",
        "ARRAYCOPY命令的第{0}参数\"{1}\"不是一个数组变量",
        "The {0} argument of the ARRAYCOPY instruction \"{1}\" is not an array variable",
        2, 2226, 511, 514,
    ),

    /** `Lang.cs:2230` / zhs 表 :512（根表 :71）；抛点 `Process.cs:1734/:1746`（ARRAYCOPY 目标是角色变量）。 */
    ArraycopyArgIsCharaVar(
        "ARRAYCOPY命令の第{0}引数\"{1}\"はキャラクタ変数です（対応していません）",
        "ARRAYCOPY命令的第{0}参数\"{1}\"是一个角色变量(不支持)",
        "The {0} argument of the ARRAYCOPY instruction \"{1}\" is a character variable (not supported)",
        2, 2230, 512, 515,
    ),

    /** `Lang.cs:2238` / zhs 表 :514（根表 :230）；抛点 `Process.cs:1754`（原版直取 `.Text` 不做 Format，模板本身 0 占位符）。 */
    DifferentArraycopyArgsDim(
        "ARRAYCOPY命令の2つの引数の次元が異なります",
        "ARRAYCOPY命令的两个参数维度不一致",
        "The two arguments of the ARRAYCOPY instruction have different dimensions",
        0, 2238, 514, 517,
    ),

    /** `Lang.cs:2242` / zhs 表 :515（根表 :231）；抛点 `Process.cs:1758/:1767`（日文 `２` 全角、中文半角，各表原样）。 */
    DifferentArraycopyArgsType(
        "ARRAYCOPY命令の２つの配列変数の型が一致していません",
        "ARRAYCOPY命令的两个参数的数组变量类型不一致",
        "The type of the two array variables of the ARRAYCOPY instruction do not match",
        0, 2242, 515, 518,
    ),

    /** `Lang.cs:1118` / zhs 表 :234（根表 :456）；PRINTBAR 系最大值非正值。 */
    MaxBarNotPositive(
        "BARの最大値が正の値ではありません",
        "BAR的最大值不为正值",
        "BAR maximum value is not positive",
        0, 1118, 234, 237,
    ),

    /** `Lang.cs:1122` / zhs 表 :235（根表 :85）；PRINTBAR 系长度非正值。 */
    BarNotPositive(
        "BARの長さが正の値ではありません",
        "BAR的长度不为正值",
        "BAR length is not positive",
        0, 1122, 235, 238,
    ),

    /** `Lang.cs:1126` / zhs 表 :236（根表 :674）；BAR 长度不小于 100。 */
    TooLongBar(
        "BARが長すぎます",
        "BAR过长",
        "BAR is too long",
        0, 1126, 236, 239,
    ),

    /** `Lang.cs:1982` / zhs 表 :450（根表 :685）；抛点 `FunctionMethodCreator.cs:3828-3830`（COLOR_FROMNAME 收到 transparent）。本波唯一接线枚。 */
    TransparentUnsupported(
        "無色透明(Transparent)は色として指定できません",
        "不能将透明(Transparent)指定为颜色",
        "Transparent cannot be specified as a color",
        0, 1982, 450, 453,
    ),

    /** `Lang.cs:1986` / zhs 表 :451（根表 :375）；色名查不到（官方另有 `InvalidColorName2`，另一枚键，不混用）。 */
    InvalidColorName(
        "指定された色名\"{0}\"は無効な色名です",
        "指定的颜色名\"{0}\"无效",
        "Specified color name \"{0}\" is invalid",
        1, 1986, 451, 454,
    ),

    /** `Lang.cs:3078` / zhs 表 :724（根表 :775）；抛点 `Process.cs:1780`（ENCODETOUNI 超缓冲上限）。 */
    tooLongEncodetouniArg(
        "ENCODETOUNIの引数が長すぎます（現在{0}文字。最大{1}文字まで）",
        "ENCODETOUNI的参数过长（目前共{0}字符。最大{1}字符）",
        "ENCODETOUNI argument is too long (Current - {0}, limit - {1})",
        2, 3078, 724, 727,
    ),

    /** `Lang.cs:3322` / zhs 表 :785（根表 :185）；引擎态不允许的命令（移植侧 = 系统状态位闸，`VM 不可用命令` 族）。 */
    CanNotUseInstruction(
        "{0}命令は使用できません",
        "不能使用{0}命令",
        "{0} Instructions cannot be used",
        1, 3322, 785, 788,
    ),

    // -----------------------------------------------------------------------
    // 十、函数调用族（REF 形参省略等；CalledFunction.cs:147-158）
    // -----------------------------------------------------------------------

    /**
     * `Lang.cs:2906` / zhs 表 :681 / eng 表 :684；抛点 `CalledFunction.cs:147-158`
     * （REF 形参在调用点被省略）。移植侧 5 处此前直打同形 zhs 串，2026-11-07 i18n 波
     * 起改走 `CodeEE.keyed` 三语通道。
     */
    CanNotOmitRefArg(
        "\"@{0}\"の{1}番目の引数は参照渡しのため省略できません",
        "@{0}的第{1}个参数是引用形式因此无法省略",
        "The {1} argument is passed by the reference to \"@{0}\" function and cannot be omitted",
        2, 2906, 681, 684,
    ),

    // -----------------------------------------------------------------------
    // 十一、装载失败函数族（ErbLoader.cs:540-561 / :995-1010）
    // -----------------------------------------------------------------------

    /**
     * `Lang.cs:2450` / zhs 表 :567（根表 :99）/ eng 表 :570。
     *
     * 原版装载期函数体解析失败时**不删标签**，只打 `ErrMes`（`ErbLoader.cs:995-1010`；
     * 形参解析失败 `:540-561` 同一处理）：运行期调用即抛 `CodeEE(ErrMes)`
     * （`CalledFunction.cs:133` → `Process.cs:1017-1035`）。
     *
     * 移植侧消费点：erbcc 兼容档降级桩的入口 THROW（`tools/erbcc/.../CorpusCompile.kt`
     * 的 `degradedBody`）—— 2026-11-08 起由自造串「函数「@X」在装载时出错，无法使用」
     * 改为本键 zhs 原文（ADR-005 照抄；函数名信息由原版也不提供，故不保留）。
     */
    CalledFailedFunc(
        "ロード時に解析に失敗した関数が呼び出されました",
        "调用了一个加载时解析失败的函数",
        "A function that failed parsing at loading was called",
        0, 2450, 567, 570,
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
        val template = when (variant) {
            LangVariant.JA -> templateJa
            LangVariant.ZHS -> templateZhs
            LangVariant.EN -> templateEn
        }
        return fill(template, args)
    }

    /** 使用者面默认档（zhs）—— 口径见类头。 */
    fun render(vararg args: Any?): String = render(LangVariant.ZHS, *args)

    /** 内核档：`Lang.cs` 日文原文。 */
    fun renderJa(vararg args: Any?): String = render(LangVariant.JA, *args)

    /** zhs 译文档（显式名，避免调用点看不出语言）。 */
    fun renderZhs(vararg args: Any?): String = render(LangVariant.ZHS, *args)

    /** eng 译文档（显式名，避免调用点看不出语言）。 */
    fun renderEn(vararg args: Any?): String = render(LangVariant.EN, *args)

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

/** 文案语言档 —— 口径见 [LangKey] 类头（内核 ja / 使用者面按壳层设置三选一）。 */
enum class LangVariant {
    /** `Lang.cs` 的日文原文。 */
    JA,

    /** `lang.emuera-zhs.xml` 的中文译文。 */
    ZHS,

    /** `lang.emuera-eng.xml` 的英文译文。 */
    EN,
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
