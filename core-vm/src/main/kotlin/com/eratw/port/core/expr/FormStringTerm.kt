package com.eratw.port.core.expr

import com.eratw.port.core.lexer.CurlyBraceSubWord
import com.eratw.port.core.lexer.PercentSubWord
import com.eratw.port.core.lexer.StrForm
import com.eratw.port.core.lexer.SubWord
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.TripleSymbolSubWord
import com.eratw.port.core.lexer.YenAtSubWord
import java.nio.charset.Charset

/**
 * 格式化字符串的组成部件：常量文本段与内嵌表达式段按序交替。
 *
 * 对应原版 StrForm 的 strs: string[] 与 terms: AExpression[] 两个平行数组
 * （spec: StrForm.cs:98-100，strs.size == terms.size + 1）；本模型将其摊平为单一列表，
 * 空文本段同样保留（与原版 strs 原样保存一致，见 fromLexerStrForm 注释）。
 */
sealed interface FormPart {
    /** 常量文本段（原版 strs[i]）。 */
    data class Text(val str: String) : FormPart

    /** 内嵌表达式段（原版 terms[i]，按 FormattedStringMethod 的三个实现做转换）。 */
    data class Embedded(val ast: Expr, val conversion: FormConversion) : FormPart
}

/**
 * 内嵌表达式的转换规则 —— 原版 StrForm 内部 FormattedStringMethod 三个实现的移植。
 * spec: StrForm.cs:20-96
 */
sealed interface FormConversion {
    /**
     * {expr} —— expr 必须是数值型（解析期检查，spec: StrForm.cs:310-315）。
     * int→string 的隐式转换发生点：long.ToString()（spec: StrForm.cs:49）。
     * width != null 时补齐到宽度；alignLeft=true（写作 LEFT）→ PadRight（左对齐），
     * false（RIGHT 或省略）→ PadLeft（右对齐）。spec: StrForm.cs:50-58
     */
    data class Curly(val width: Expr? = null, val alignLeft: Boolean = false) : FormConversion

    /**
     * %expr% —— expr 必须是字符串型（解析期检查，spec: StrForm.cs:330-335）。
     * 宽度先按**界面语言码页的字节数**调整（strlenLang - length），再补齐。spec: StrForm.cs:62-83
     */
    data class Percent(val width: Expr? = null, val alignLeft: Boolean = false) : FormConversion

    /**
     * \@cond?left#right\@ —— ast 为条件（数值型，spec: StrForm.cs:245），
     * left/right 为嵌套的 FormStringTerm（或缺 # 分支时的 StrLiteral("")）。
     * cond != 0 取 left，== 0 取 right。spec: StrForm.cs:86-96
     */
    data class YenAt(val ifTrue: Expr, val ifFalse: Expr) : FormConversion
}

/**
 * 字符串形式（@"..." 内含 {表达式}/%字符串%/三连记号/\@三项条件式）。
 * 对应原版 StrFormTerm（持有 StrForm）+ StrForm.GetString，spec: StrFormTerm.cs / StrForm.cs:400-414。
 *
 * ## 消费路径说明
 * parts 由词法器的 StrForm（SubWord 层级）直接构造（[fromLexerStrForm]），而非从原文重建。
 * 理由：原版 FromWordToken 也是直接消费词法阶段的产物 StrFormWord.Strs/SubWords
 * （spec: StrForm.cs:206-353）；从原文重建需要二次词法，既偏离原版结构又会丢失行号。
 * 因此本包与 core.lexer 的 StrForm/SubWord 类型存在编译期依赖（同模块内包依赖，接受）。
 */
data class FormStringTerm(
    val parts: List<FormPart>,
    override val line: Int = -1,
    override val col: Int = -1,
) : Expr {
    override val operandType get() = OperandType.STR
    override fun getIntValue(ctx: EvalContext) = 0L

    /** spec: StrForm.cs:400-414 GetString —— 部件顺序求值拼接 */
    override fun getStrValue(ctx: EvalContext): String {
        // IsConst 快路径（strs.Length == 1 即纯文本）。spec: StrForm.cs:403-406
        if (parts.size == 1 && parts[0] is FormPart.Text) return (parts[0] as FormPart.Text).str
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is FormPart.Text -> sb.append(p.str)
                is FormPart.Embedded -> sb.append(renderEmbedded(p, ctx))
            }
        }
        return sb.toString()
    }

    private fun renderEmbedded(p: FormPart.Embedded, ctx: EvalContext): String = when (val c = p.conversion) {
        is FormConversion.Curly -> formatCurlyBrace(p.ast, c, ctx)
        is FormConversion.Percent -> formatPercent(p.ast, c, ctx)
        is FormConversion.YenAt -> formatYenAt(p.ast, c, ctx)
    }

    /** spec: StrForm.cs:45-60 FormatCurlyBrace */
    private fun formatCurlyBrace(expr: Expr, c: FormConversion.Curly, ctx: EvalContext): String {
        val text = expr.getIntValue(ctx).toString()  // spec: StrForm.cs:49 —— int→string（Long.ToString 与 C# long.ToString 的 "G" 输出一致）
        val w = c.width ?: return text               // spec: StrForm.cs:50-53
        val width = w.getIntValue(ctx).toInt()       // spec: StrForm.cs:56,58 —— (int) 截断与 C# unchecked 转换一致
        if (width < 0) {
            // .NET PadLeft/PadRight 对负 totalWidth 抛 ArgumentOutOfRangeException（原版即异常路径）
            throw ExprEvalException("宽度($width)不能为负数")
        }
        return if (c.alignLeft) text.padEnd(width, ' ') else text.padStart(width, ' ')
    }

    /**
     * spec: StrForm.cs:62-84 FormatPercent —— 宽度补偿用的是**界面语言码页的字节数**
     * （StrForm.cs:72 的 LangManager.GetStrlenLang），故这里必须走 [langCodecOf]，
     * 与 `STRLENS`/`SUBSTRING` 同一份实现；**不是**显示宽度（那条在 core 层的 CellWidth，不归本路）。
     */
    private fun formatPercent(expr: Expr, c: FormConversion.Percent, ctx: EvalContext): String {
        val strValue = expr.getStrValue(ctx)
        val w = c.width ?: return strValue           // spec: StrForm.cs:67-70
        var num = w.getIntValue(ctx).toInt()
        // 全角字符按码页字节数折算。spec: StrForm.cs:72-73（LangManager.GetStrlenLang）
        num -= langCodecOf(ctx).strlen(strValue) - strValue.length
        if (num < strValue.length) return strValue   // spec: StrForm.cs:74-77
        return if (c.alignLeft) strValue.padEnd(num, ' ') else strValue.padStart(num, ' ')  // spec: StrForm.cs:78-83
    }

    /** spec: StrForm.cs:86-96 FormatYenAt */
    private fun formatYenAt(cond: Expr, c: FormConversion.YenAt, ctx: EvalContext): String =
        if (cond.getIntValue(ctx) == 0L) c.ifFalse.getStrValue(ctx) else c.ifTrue.getStrValue(ctx)

    companion object {
        /**
         * spec: StrForm.cs:206-353 FromWordToken —— 从词法器的 StrForm（strs + SubWord 列表）构造。
         * 部件顺序 = strs[i], subWords[i], strs[i+1], ...（spec: StrFormWord.cs：
         * strs.size == subWords.size + 1）。空文本段原样保留，与原版 strs 直接赋值一致
         * （原版 IsConst/GetAExpression 都依赖 strs 的原始形态）。
         */
        fun fromLexerStrForm(
            form: StrForm,
            resolver: IdentifierResolver?,
            config: ParserConfig,
            line: Int,
            col: Int,
        ): FormStringTerm {
            val parts = mutableListOf<FormPart>()
            for (i in form.strs.indices) {
                parts.add(FormPart.Text(form.strs[i]))
                if (i < form.subWords.size) {
                    parts.add(subWordToPart(form.subWords[i], resolver, config, line, col))
                }
            }
            return FormStringTerm(parts, line, col)
        }

        /** spec: StrForm.cs:213-349 —— 按 SubWord 具体类型分派 */
        private fun subWordToPart(
            sw: SubWord,
            resolver: IdentifierResolver?,
            config: ParserConfig,
            line: Int,
            col: Int,
        ): FormPart.Embedded = when (sw) {
            is TripleSymbolSubWord ->
                // spec: StrForm.cs:216-238 —— 三连记号 → NAME/CALLNAME 系统变量，
                // 转换与 Initialize 构建的 FunctionMethodTerm(formatPercent, [var, null, null]) 同型。spec: StrForm.cs:144-203
                FormPart.Embedded(tripleSymbolTerm(sw.code), FormConversion.Percent())

            is YenAtSubWord -> {
                // spec: StrForm.cs:241-254 —— 条件为整数表达式，须消费到行尾
                val cond = ExprParser(toExprTokens(sw.condition), resolver, config)
                    .reduceIntegerTerm(TermEndWith.EoL)
                // spec: StrForm.cs:255-256 —— 左右分支是嵌套 StrFormTerm；缺 # 分支时为 ""
                val left = fromLexerStrForm(sw.left, resolver, config, line, col)
                val right = sw.right?.let { fromLexerStrForm(it, resolver, config, line, col) } ?: StrLiteral("")
                FormPart.Embedded(cond, FormConversion.YenAt(left, right))
            }

            is CurlyBraceSubWord -> braceOrPercent(sw.words, isCurly = true, resolver, config, line, col)
            is PercentSubWord -> braceOrPercent(sw.words, isCurly = false, resolver, config, line, col)
        }

        /**
         * {expr[,width[,LEFT|RIGHT]]} 与 %expr[,width[,LEFT|RIGHT]]% 的解析。
         * spec: StrForm.cs:272-349
         */
        private fun braceOrPercent(
            words: List<Token>,
            isCurly: Boolean,
            resolver: IdentifierResolver?,
            config: ParserConfig,
            line: Int,
            col: Int,
        ): FormPart.Embedded {
            val parser = ExprParser(toExprTokens(words), resolver, config)
            // 主表达式以 ',' 终止；为空报错（EmptyBrace/EmptyPer）。spec: StrForm.cs:272-281
            val main = parser.reduceExpressionTerm(TermEndWith.Comma)
                ?: throw ExprParseException(line, col, if (isCurly) "{ } 内缺少表达式" else "% % 内缺少表达式")
            var width: Expr? = null
            var alignLeft = false
            parser.shiftNext()  // spec: StrForm.cs:284
            if (parser.cur.kind != ExprTokenKind.EOL) {
                // 宽度为整数表达式。spec: StrForm.cs:287
                width = parser.reduceIntegerTerm(TermEndWith.Comma)
                parser.shiftNext()  // spec: StrForm.cs:288
                if (parser.cur.kind != ExprTokenKind.EOL) {
                    // 第三个参数只能是标识符 LEFT/RIGHT（LEFT→左对齐 PadRight）。spec: StrForm.cs:289-304
                    if (parser.cur.kind != ExprTokenKind.IDENTIFIER) {
                        throw ExprParseException(parser.cur.line, parser.cur.col, "缺少 LEFT 或 RIGHT")
                    }
                    when {
                        parser.cur.text.equals("LEFT", ignoreCase = true) -> alignLeft = true
                        parser.cur.text.equals("RIGHT", ignoreCase = true) -> {}
                        else -> throw ExprParseException(parser.cur.line, parser.cur.col, "只能指定 LEFT 或 RIGHT")
                    }
                    parser.shiftNext()
                    if (parser.cur.kind != ExprTokenKind.EOL) {
                        throw ExprParseException(parser.cur.line, parser.cur.col, "LEFT/RIGHT 之后有多余字符")
                    }
                }
            }
            return if (isCurly) {
                if (main.operandType != OperandType.INT) {
                    throw ExprParseException(line, col, "{ } 内的表达式不是数值型")  // spec: StrForm.cs:310-315
                }
                FormPart.Embedded(main, FormConversion.Curly(width, alignLeft))
            } else {
                if (main.operandType != OperandType.STR) {
                    throw ExprParseException(line, col, "% % 内的表达式不是字符串型")  // spec: StrForm.cs:330-335
                }
                FormPart.Embedded(main, FormConversion.Percent(width, alignLeft))
            }
        }

        /**
         * 三连记号 → NAME/CALLNAME 系统变量（角色下标 0）。
         * spec: StrForm.cs:218-237（分派）与 129-203（Initialize 构建的同款 VariableTerm）：
         * *** → NAME:TARGET、+++ → CALLNAME:MASTER、=== → CALLNAME:PLAYER、
         * /// → NAME:ASSI、$$$ → CALLNAME:TARGET
         */
        private fun tripleSymbolTerm(code: Char): Expr {
            val (role, name) = when (code) {
                '*' -> "TARGET" to "NAME"
                '+' -> "MASTER" to "CALLNAME"
                '=' -> "PLAYER" to "CALLNAME"
                '/' -> "ASSI" to "NAME"
                '$' -> "TARGET" to "CALLNAME"
                else -> throw ExprEvalException("不正な三連記号 '$code'")  // spec: StrForm.cs:236 ExeEE
            }
            val roleIdx = VarRef(role, listOf(IntLiteral(0L)), operandType = OperandType.INT)
            return VarRef(name, listOf(roleIdx), operandType = OperandType.STR)
        }

        /**
         * 词法 Token → ExprToken。core.lexer 的 OperatorCode 是原版 C# 枚举名
         * （Token.Operator.text == code.name），经 [OperatorCode.fromCSharpName] 转回编码，
         * 再取 [OperatorCode.toOperatorString] 的源码符号文本（ExprParser.fromText 的键形）。
         */
        private fun toExprTokens(words: List<Token>): List<ExprToken> = words.map { w ->
            when (w) {
                is Token.Identifier -> ExprToken(ExprTokenKind.IDENTIFIER, w.code, 0L, w.line, w.col)
                is Token.Integer -> ExprToken(ExprTokenKind.INT_LITERAL, w.text, w.value, w.line, w.col)
                is Token.StringLiteral -> ExprToken(ExprTokenKind.STR_LITERAL, w.value, 0L, w.line, w.col)
                is Token.Operator -> {
                    val oc = OperatorCode.fromCSharpName(w.code.name)
                        ?: throw ExprParseException(w.line, w.col, "未知运算符 ${w.code.name}")
                    ExprToken(ExprTokenKind.OPERATOR, OperatorCode.toOperatorString(oc), 0L, w.line, w.col)
                }
                is Token.Symbol -> ExprToken(symbolKind(w.char), w.char.toString(), 0L, w.line, w.col)
                is Token.FormStr -> ExprToken(ExprTokenKind.FORM_STRING, "<FORM>", 0L, w.line, w.col, w.form)
            }
        }

        private fun symbolKind(c: Char): ExprTokenKind = when (c) {
            '(' -> ExprTokenKind.LPAREN
            ')' -> ExprTokenKind.RPAREN
            '[' -> ExprTokenKind.LBRACKET
            ']' -> ExprTokenKind.RBRACKET
            ',' -> ExprTokenKind.COMMA
            ':' -> ExprTokenKind.COLON
            '@' -> ExprTokenKind.AT
            '.' -> ExprTokenKind.DOT
            else -> throw ExprParseException(-1, -1, "不该出现的符号 '$c'")
        }
    }
}

// ===========================================================================
// 字节长度一族的唯一实现 —— 原版 LangManager 的移植（波次契约 M4-E「e-strlen」收口）
// ===========================================================================
//
// 本段之前是两份互相矛盾的写死实现：这里一份 Shift_JIS(932)、core 层的
// com.eratw.port.core.fn.PureMethods 里一份 GBK(936)。原版只有一份，且**由配置决定**，
// 现在收回来只留这一份。语义与证据全部挂在 [LangCodec] 的 KDoc 上。

/** [LangCodec] 在每 VM 附属状态里的槽名（约定：小写家族名，见 core 层 RuntimeAux 的 KDoc）。 */
internal const val LANG_CODEC_AUX_KEY = "lang.codec"

/**
 * 界面语言码页 —— 原版 `LangManager` 的静态 `lang` 字段连同它的三个方法，移植成**不可变值对象**。
 *
 * ## 原版到底问什么（逐条带行号）
 *
 * | 移植侧 | 原版 | 口径 |
 * |---|---|---|
 * | [strlen] | `LangManager.cs:15-22` GetStrlenLang | 整串 ASCII（每个 UTF-16 码元小于 0x80）直接取 `Length`，否则取**配置码页**的字节数；慢路径逐码元累加单字符 `GetByteCount`（代理对各算 1，见 [strlen] 说明） |
 * | [uftIndex] | `LangManager.cs:24-47` GetUFTIndex | 字节下标 → 字符下标 |
 * | [subString] | `LangManager.cs:49-95` GetSubStringLang | 按**字节**切串（`*SU` 那一族才按 UTF-16 长度切，见下） |
 *
 * 消费点（原版）：
 * - `STRLENS` = GetStrlenLang（FunctionMethodCreator.cs:5260-5273）；`STRLENSU` = `string.Length`
 *   （:5275-5289，注册名见 :9123-9124）——**两路口径不同，不许收成一个数**：
 *   `STRLENS("あい")=4`（字节）而 `STRLENSU("あい")=2`（UTF-16 码元）。
 *   语句层同理：`STRLEN`/`STRLENFORM` 取字节数、`STRLENU`/`STRLENFORMU` 取 Length
 *   （FunctionIdentifier.cs:700-729 的 STRLEN_Instruction，:722 走 Length、:726 走 GetStrlenLang）。
 * - `SUBSTRING` 走 [subString]（:5290-5327）；`SUBSTRINGU` 走纯 UTF-16 下标（:5328-5385，不碰码页）。
 * - `STRFIND` 走 [uftIndex] + [strlen]（:5386-5432，:5418/:5427）；`STRFINDU` 不碰码页。
 * - `TOINT` / `ISNUMERIC` 用 `Length < GetStrlenLang(...)` 当「含全角」判据（:5516、:5777）。
 * - **FORM 串的 `%…%宽度` 排版**走 [strlen]（StrForm.cs:62-84 的 FormatPercent，:72 一处），
 *   所以 `%POS` 一类的补齐宽度跟的是**界面语言码页**，本文件 [formatPercent] 即其移植。
 *
 * ## 码页从哪来（配置驱动，不是写死）
 *
 * `Config.cs:412-430` 按 `ConfigCode.useLanguage` 分派 `LangManager.setEncode(...)`：
 * JAPANESE→932（:414-417）、KOREAN→949（:418-421）、CHINESE_HANS→936（:422-425）、
 * CHINESE_HANT→950（:426-429）；`setEncode` 本体 = `Encoding.GetEncoding(code)`（LangManager.cs:9-13）。
 * 配置行的出厂默认是 JAPANESE（ConfigData.cs:103），故 [DEFAULT] = 932。
 * 键匹配复刻 `ConfigData.GetItem`（:236-249 → :262-284）：键先转大写，再比 `Name`/`Text`/`EngText`；
 * `Text`/`EngText` 在构造时就转成大写存着（AConfigItem.cs:22-23），`Name` 是混合大小写的 enum 名，
 * 永远命中不了（原版缺陷，[fromConfig] 照抄不修）。值按 `Enum.TryParse(ignoreCase)` 解析
 * （ConfigItem.cs:196-202），空串直接 TryParse false（:89-95）；解析失败原版只 ConfigWarn 并保留旧值
 * （ConfigData.cs:579-586）⇒ 回落 [DEFAULT]。
 * 本作运行环境实测：语料 `emuera.config:56` = `DEFAULT ANSI ENCODING:CHINESE_HANS` ⇒ 该环境是 **936**。
 * （注意 `emuera.config:76` 的「EMUERA INTERFACE LANGUAGE:简体中文」是 `EmueraLang`，
 * 只管界面文案语言（ConfigData.cs:130、Config.cs:390），**不**决定码页 —— 别拿它当证据。）
 *
 * ## JDK 码页名与 .NET 不同构（本机 Temurin 17 实测，字节数:字节序列）
 *
 * .NET 侧 `Encoding.GetEncoding("Shift-JIS")` 是 CP932（含 NEC / IBM 表外扩充），
 * 而 JDK 的 `Shift_JIS`（别名 `SJIS`/`x-SJIS`/`Cp932` 实测同值）**不含**这些扩充，两者会给出相反的字节数：
 *
 * | 码位 | Shift_JIS | windows-31J / MS932 | GBK / Cp936 / windows-936 |
 * |---|---|---|---|
 * | `U+2460` ① | 1:3F | **2:8740** | 2:A2D9 |
 * | `U+00B7` · | 1:3F | 2:8145 | 2:A1A4 |
 * | `U+2014` — | 2:815C | 1:3F | 2:A1AA |
 * | `U+2501` ━ | 2:84AA | 2:84AA | 2:A9A5 |
 * | `U+203B` ※ | 2:81A6 | 2:81A6 | 2:A1F9 |
 * | `U+00B1` ± | 2:817D | 2:817D | 2:A1C0 |
 * | `U+FF61`/`U+FF71`/`U+FF9F` 半角假名 | 1:A1/1:B1/1:DF | 1:A1/1:B1/1:DF | 1:3F（不可映射→替换） |
 * | `U+5168` 全 | 2:9153 | 2:9153 | 2:C8AB |
 * | `U+836F` 药 | 1:3F | 1:3F | 2:D2A9 |
 * | `U+0041` A | 1:41 | 1:41 | 1:41 |
 *
 * 结论：日文稿用 **`windows-31J`**（`MS932` 实测同值）而不是 `Shift_JIS` —— ① 这类 CP932 表内字
 * 在 .NET 是 2 字节、在本仓既有的宽度表里也是 2 字节，只有 `windows-31J` 与两者同时一致。
 * 中文稿用 **`GBK`**（与 `Cp936`/`windows-936` 实测同值）。
 * TODO(verify)：`U+00B7`（JDK windows-31J 给 2:8145，但该字节在 CP932 表里对应 `U+30FB` ・，
 * .NET 侧到底是 2 字节还是替换 1 字节没有实测条件）与 `U+2014`（.NET CP932 亦疑为 1 字节替换）；
 * 卡点 = 本机没有 .NET 运行时可对拍，需真机或 reference 采集补一条读数。
 *
 * ## 不归本路的一条边界（后人勿混）
 *
 * **PRINTC 的补齐宽度与显示宽度用的是另一套、与界面语言无关的口径**：原版写死
 * `Encoding.GetEncoding("Shift-JIS")`（EmueraConsole.cs:3255），屏上宽度则问 GDI 像素
 * （StringMeasure.cs:39-66）；显示宽度的唯一表在 core 层的 CellWidth（口径裁定在 main 上的 166dbd7），
 * **禁改、禁重开**。本 [LangCodec] 只管「字节数」这一族（STRLENS / SUBSTRING / STRFIND / TOINT /
 * ISNUMERIC / FORM 串 `%…%宽度`）；把两者混成一个数是历史事故，别再犯。
 */
internal class LangCodec private constructor(
    /** 原版 `UseLanguage` 的 enum 名（JAPANESE / KOREAN / CHINESE_HANS / CHINESE_HANT）。 */
    val useLanguage: String,
    /** 原版 `LangManager.setEncode` 收到的码页号（932 / 949 / 936 / 950）。 */
    val codepage: Int,
    /** JDK 侧选用的字符集名（实测理由见类 KDoc）。 */
    val charsetName: String,
) {
    val charset: Charset = Charset.forName(charsetName)

    /**
     * 整串字节数。spec: LangManager.cs:15-22（Ascii.IsValid 快路径 + GetByteCount）。
     *
     * 慢路径按**逐 UTF-16 码元累加**（[charWidth]），不是把整串交给 JDK 编码器：
     * .NET 的 `GetByteCount`（`CodePagesEncodingProvider` 的 CP932/936/949/950）对代理对的
     * 两个码元各自走一次回退 = 各 1 字节（`"😀"` → 2），而 JDK 的整串编码器会把合法代理对
     * 折成 1 个替换字节（→ 1）。这条差会顺着 `STRLENS` / `SUBSTRING` / `STRFIND` / `TOINT` /
     * `ISNUMERIC` / FORM 串 `%…%宽度` 全部外溢（LangManager.cs:39/74/90 的三处单字符
     * `GetByteCount` 本来就是逐码元口径，这里与它们同形）。
     *
     * 串里没有代理对时两个口径必然同值（码页本身无状态），那条分支仍走整串编码快路径。
     */
    fun strlen(str: String): Int {
        if (str.all { it.code < 0x80 }) return str.length
        // 无代理对时整串编码与逐码元累加同值（CP932/936/949/950 都是无状态码页，每个码元
        // 独立编码），走整串快路径保住热点的分配次数；这条分支与原实现逐字节一致。
        if (str.none { it.isHighSurrogate() || it.isLowSurrogate() }) return str.toByteArray(charset).size
        var bytes = 0
        for (c in str) bytes += charWidth(c)
        return bytes
    }

    /**
     * 单字符字节数。spec: LangManager.cs:39/74/90 —— 原版逐字符 `GetByteCount(str[num].ToString())`，
     * 代理对的两半各自单独成串（各自不可映射 ⇒ 按替换字符计 1），这里保持同款逐码元口径。
     */
    fun charWidth(c: Char): Int = c.toString().toByteArray(charset).size

    /** 字节下标 → 字符下标。spec: LangManager.cs:24-47 GetUFTIndex（官方边界怪行为原样复刻）。 */
    fun uftIndex(str: String, langIndex: Int): Int {
        if (langIndex <= 0) return 0
        if (langIndex >= strlen(str)) return str.length
        var bytes = 0
        var chars = 0
        for (i in str.indices) {
            bytes += charWidth(str[i])
            chars++
            if (bytes >= langIndex) break
        }
        return chars
    }

    /** 按字节切串。spec: LangManager.cs:49-95 GetSubStringLang（含 `length` 越界折回全长的原版行为）。 */
    fun subString(str: String, startindex: Int, length: Int): String {
        val strlenLang = strlen(str)
        if (startindex >= strlenLang || length == 0) return ""
        var len = length
        if (len < 0 || len > strlenLang) len = strlenLang
        val sb = StringBuilder()
        var pos = 0
        var width = 0
        if (startindex <= 0) {
            if (len == strlenLang) return str
        } else {
            for (i in str.indices) {
                width += charWidth(str[pos])
                pos++
                if (width >= startindex) break
            }
            if (pos >= str.length) return ""
        }
        width = 0
        do {
            sb.append(str[pos])
            width += charWidth(str[pos])
            pos++
        } while (width < len && pos < str.length)
        return sb.toString()
    }

    override fun toString(): String = "$useLanguage(cp$codepage/$charsetName)"

    companion object {
        // 分派表：spec: Config.cs:412-430 的四条 case，出厂默认 JAPANESE（ConfigData.cs:103）。
        val JAPANESE = LangCodec("JAPANESE", 932, "windows-31J")
        val KOREAN = LangCodec("KOREAN", 949, firstSupportedCharset("Cp949", "x-windows-949"))
        val CHINESE_HANS = LangCodec("CHINESE_HANS", 936, "GBK")
        val CHINESE_HANT = LangCodec("CHINESE_HANT", 950, firstSupportedCharset("Cp950", "Big5"))

        /**
         * 码页名 → JDK/Android 实际支持的字符集名。
         *
         * 原版 `LangManager.setEncode` 直接把码页号交给 `Encoding.GetEncoding(code)`
         * （LangManager.cs:9-12），CP949 / CP950 在 .NET 侧必然存在；JVM 侧同一码页的
         * 名字是 **实现相关的别名**：Android 的 charsets 是裁剪过的，`Cp949` / `Cp950`
         * 这两个 Windows 风格别名可能不在表里，`Charset.forName` 会抛
         * UnsupportedCharsetException —— 而 [KOREAN] / [CHINESE_HANT] 是 companion 的
         * 属性初始化，异常会让 [LangCodec] 类初始化直接失败（连 `GBK` 路径也用不了）。
         *
         * 因此先试原代码页名，失败再试语义等价的规范名（`x-windows-949` / `Big5`，
         * 后者是 CP950 的超集，与原版 `GetEncoding(950)` 的日文/繁中混排口径一致）。
         * 两者都没有时返回原名，让构造函数的 `Charset.forName` 照旧抛出 —— 不静默
         * 换成某个「能跑但字节数不对」的字符集。
         */
        private fun firstSupportedCharset(primary: String, fallback: String): String =
            when {
                runCatching { Charset.forName(primary) }.isSuccess -> primary
                runCatching { Charset.forName(fallback) }.isSuccess -> fallback
                else -> primary
            }

        /** 配置没写、写歪了、或宿主没接线时的回落 —— 原版 `ConfigData.cs:103` 的出厂默认。 */
        val DEFAULT: LangCodec = JAPANESE

        private val VALUES = listOf(JAPANESE, KOREAN, CHINESE_HANS, CHINESE_HANT)

        /**
         * `ConfigCode.useLanguage` 这一行能被命中的三种键形（大写后），spec: ConfigData.cs:103 的行定义
         * + AConfigItem.cs:22-23 的 Text / EngText 大写化。`Name`（"useLanguage"）分支命中不了，不列。
         */
        private val USE_LANGUAGE_KEYS = setOf("USELANGUAGE", "内部で使用する東アジア言語", "DEFAULT ANSI ENCODING")

        /** enum 名（大小写不敏感）→ 码页。spec: ConfigItem.cs:196-202 Enum.TryParse(ignoreCase)。 */
        fun byUseLanguage(raw: String): LangCodec? =
            VALUES.firstOrNull { it.useLanguage.equals(raw.trim(), ignoreCase = true) }

        /** 码页号 → 码页（`setEncode` 的入参形，便于宿主按整数配置接）。 */
        fun byCodepage(cp: Int): LangCodec? = VALUES.firstOrNull { it.codepage == cp }

        /**
         * 配置层 → 码页。[overrides] 就是 `BuiltinHost.configOverrides`（emuera.config 那一层）。
         *
         * 值按 `Enum.TryParse(ignoreCase: true)` 的两段解析（`ConfigItem.cs:196-202`）：
         * ① 先按 **enum 名**（大小写不敏感，[byUseLanguage]）；② 失败再按**整数**。
         * 整数段只认 **ASCII 十进制**（可带一个 `+`/`-`）—— .NET 的 Int32 解析不接受全角数字，
         * 而 Kotlin 的 `toIntOrNull()` 走 `Character.digit`、会把 `"２"` 也解成 2；
         * 故这里先过 [isAsciiDecimal] 再 `toIntOrNull()`，收严两边的差。
         *
         * enum 值 `UseLanguage.cs` = JAPANESE(0) / KOREAN(1) / CHINESE_HANS(2) / CHINESE_HANT(3)，
         * 与 [VALUES] 顺序一致，故「数值按 enum 值」与「按列表下标」同义。
         * 越界数字在原版会走到 `Config.cs:412-430` switch 的空分支，`lang` 保持 null，
         * 之后非 ASCII 串直接 NullReferenceException。core 层不复刻这个崩溃，
         * TODO(verify)：该形态是否需要在移植侧显式报错（卡点 = 原版没有可观测文案可对拍），
         * 现在按「无法识别 ⇒ 保留默认」处理（与 ConfigData.cs:579-586 的 ConfigWarn 保留旧值同形）。
         */
        fun fromConfig(overrides: Map<String, String>): LangCodec {
            val raw = overrides.entries
                .firstOrNull { it.key.trim().uppercase() in USE_LANGUAGE_KEYS }
                ?.value
                ?.trim()
                ?: return DEFAULT
            if (raw.isEmpty()) return DEFAULT            // ConfigItem.cs:89-95 空串 ⇒ TryParse false
            byUseLanguage(raw)?.let { return it }
            if (isAsciiDecimal(raw)) {
                raw.toIntOrNull()?.let { idx ->
                    VALUES.getOrNull(idx)?.let { return it }
                }
            }
            return DEFAULT
        }

        /**
         * 值的「整数形态」判据：ASCII 十进制、可带一个前导符号。
         *
         * `Enum.TryParse` 的整数分支走 .NET 的 Int32 解析（只认 ASCII 数字），
         * 而 Kotlin `toIntOrNull()` 经 `Character.digit` 会收全角数字（`"２"` → 2）、
         * Unicode 数字（`"٢"` → 2 等）。挡掉这一族，免得配置串把码页悄悄换掉。
         */
        private fun isAsciiDecimal(raw: String): Boolean {
            if (raw.isEmpty()) return false
            var i = if (raw[0] == '+' || raw[0] == '-') 1 else 0
            if (i >= raw.length) return false
            while (i < raw.length) {
                val c = raw[i]
                if (c < '0' || c > '9') return false
                i++
            }
            return true
        }
    }
}

/**
 * 运行期取码页：`STRLEN` 一族的语句层/表达式层入口。
 *
 * 只有 [com.eratw.port.core.fn.BuiltinContext] 那条通道能拿到宿主配置（`EvalContext` 本身
 * 只有变量读取与函数调用两条通道，没有配置通道），故这里做一次下转型；下转型不是设计目标而是
 * 本轮文件所有权的结果 —— 长期形态应是 `EvalContext` 直接给 `langCodec`（接线需求见交付报告）。
 * 非 BuiltinContext 语境（纯表达式单测）回落 [LangCodec.DEFAULT] = 932，与收口前的 FORM 路一致。
 * 每 VM 解析一次并缓存（原版 `LangManager.lang` 也是加载配置时一次性设定，运行期不变；
 * 见 core 层 RuntimeAux 的槽约定，禁静态可变全局）。
 */
internal fun langCodecOf(ctx: EvalContext): LangCodec =
    (ctx as? com.eratw.port.core.fn.BuiltinContext)?.let { c ->
        c.aux.slot(LANG_CODEC_AUX_KEY) { LangCodec.fromConfig(c.host.configOverrides) }
    } ?: LangCodec.DEFAULT

/** 语句层 `STRLEN` 一族的入口：码页从执行语境同一条通道取（`ctx.eval` 是 BuiltinContext 时问宿主配置）。 */
internal fun strLenLang(s: String, codec: LangCodec): Int = codec.strlen(s)
