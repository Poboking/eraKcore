package com.eratw.port.core.expr

/**
 * 词法 Token 视图 —— 表达式解析器消费的 Token 模型。
 *
 * ## 与 lexer（com.eratw.port.core.lexer，另一 agent 并行开发）的对接假设
 * 词法器产出 Token(type + text + 行号 + 列号)。集成期由适配层把词法器 Token 映射为本模型：
 *
 * | 词法器产物              | ExprTokenKind            | text / intValue                |
 * |------------------------|--------------------------|--------------------------------|
 * | 整数字面量              | INT_LITERAL              | intValue = 数值(先按 i64 解析)  |
 * | 字符串字面量 "..."      | STR_LITERAL              | text = 已去引号的内容           |
 * | 字符串形式 "..."(带{})  | FORM_STRING              | form = 词法器 StrForm(SubWord 层级) |
 * | 标识符/关键字           | IDENTIFIER               | text                           |
 * | 运算符                  | OPERATOR                 | text（见 OperatorCode 表）      |
 * | ( ) [ ] , : @ .        | LPAREN/RPAREN/LBRACKET/  | text                           |
 * |                        | RBRACKET/COMMA/COLON/AT/DOT                                |
 * | 行尾/流尾               | EOL                      |                                |
 *
 * 注意：词法器需保证 `==`、`&&`、`>>` 等多字符运算符已被合并为单个 OPERATOR token
 * （对照 OperatorCode 表，最长匹配优先；`#` 与 `'=` 也是运算符）。
 * 若最终词法器模型不一致，在集成期补一个 adapter 即可，本包不依赖 core.lexer。
 *
 * spec: ExpressionParser.cs:503-617（Word.Type 分派）
 */
enum class ExprTokenKind {
    INT_LITERAL,
    STR_LITERAL,
    FORM_STRING,
    IDENTIFIER,
    OPERATOR,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,
    COLON,
    AT,
    DOT,
    EOL,
}

data class ExprToken(
    val kind: ExprTokenKind,
    val text: String,
    val intValue: Long = 0L,
    val line: Int = -1,
    val col: Int = -1,
    /**
     * FORM_STRING 专有：词法器产出的 StrForm（strs + SubWord 层级），
     * 由 FormStringTerm.fromLexerStrForm 直接消费（对齐原版 FromWordToken 读
     * StrFormWord.Strs/SubWords 的结构，spec: StrForm.cs:206-353）。
     */
    val form: com.eratw.port.core.lexer.StrForm? = null,
) {
    companion object {
        val EOL = ExprToken(ExprTokenKind.EOL, "")
    }
}

/** 表达式解析的终止符集合（位标志）。 spec: TermEndWith.cs:3-17 */
object TermEndWith {
    const val NONE = 0
    const val EoL = 1
    const val Comma = 2
    const val RightParenthesis = 4
    const val RightBracket = 8
    const val Assignment = 16
    const val KeyWordPx = 32
    const val RightParenthesis_Comma = 6
    const val RightBracket_Comma = 10
    const val Comma_Assignment = 18
    const val RightParenthesis_Comma_Assignment = 22
    const val RightBracket_Comma_Assignment = 26

    /**
     * `Comma or KeyWordPx` = 34 —— 原版在 SP_PRINT_IMG / SP_PRINT_SHAPE 的尺寸参数位写的
     * 裸字面量 `(TermEndWith)34`（spec: ArgumentParser.cs:57、:124）。命名后再传，
     * 免得调用点各写一份 34（CMP-53）。
     */
    const val Comma_KeyWordPx = 34
}

/** 参数列表的终止方式。 spec: ArgsEndWith.cs:3-9 */
enum class ArgsEndWith {
    EoL,
    RightParenthesis,
    RightBracket,
}
