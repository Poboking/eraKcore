package com.eratw.port.core.lexer

/**
 * 词法分析结束条件 —— spec: LexEndWith.cs
 */
enum class LexEndWith {
    None, EoL, Operator, Question, Percent, RightCurlyBrace, Comma, GreaterThan
}

/**
 * spec: LexAnalyzeFlag.cs（位标志）
 */
object LexAnalyzeFlag {
    const val NONE = 0
    const val ANALYZE_PRINT_V = 1
    const val ALLOW_ASSIGNMENT = 2
    const val ALLOW_SINGLE_QUOTATION_STR = 4
}

/**
 * 字符串字面量的结束方式 —— spec: StrEndWith.cs（ReadString 用）
 */
enum class StrEndWith {
    DoubleQuotation, SingleQuotation, Comma, LeftParenthesis_Bracket_Comma_Semicolon
}

/**
 * 格式化字符串（@"..." / \@ 三项）的结束方式 —— spec: FormStrEndWith.cs（AnalyseFormattedString 用）
 */
enum class FormStrEndWith {
    None, EoL, DoubleQuotation, Sharp, YenAt, Comma, LeftParenthesis_Bracket_Comma_Semicolon
}

/**
 * 运算符全集 —— spec: OperatorCode.cs（原版数值编码含优先级位段，此处仅保留枚举名；
 * 优先级计算属于表达式解析阶段，不在 M1 词法范围内）
 */
enum class OperatorCode {
    NULL,
    Plus, Minus, Mult, Div, Mod,
    Equal, Greater, Less, GreaterEqual, LessEqual, NotEqual,
    Increment, Decrement,
    And, Or, Xor, Nand, Nor,
    BitAnd, BitOr, BitXor, Not, BitNot,
    RightShift, LeftShift,
    Ternary_a, Ternary_b,
    Assignment, AssignmentStr
}

/**
 * 逻辑行种类（由 LogicalLineReader 按行首字符分类）
 * spec: ErbLoader.cs:388-427 —— '[' / '#' / '$' / '@' / 其他
 */
enum class LineKind {
    /** 普通代码行 */
    CODE,
    /** '@函数' 行 */
    FUNCTION_LABEL,
    /** '$标签' 行 */
    GOTO_LABEL,
    /** '#DIM' 等预处理行 */
    SHARP,
}

/**
 * Token 类型（对应原版 Word 子类，Type 字符见各 C# 类）
 */
enum class TokenType(val typeChar: Char) {
    /** IdentifierWord —— spec: IdentifierWord.cs:9 */
    IDENTIFIER('A'),
    /** LiteralIntegerWord —— spec: LiteralIntegerWord.cs:9 */
    INTEGER('0'),
    /** LiteralStringWord —— spec: LiteralStringWord.cs:9 */
    STRING('"'),
    /** OperatorWord —— spec: OperatorWord.cs:11 */
    OPERATOR('='),
    /** SymbolWord —— spec: SymbolWord.cs:7 */
    SYMBOL('\u0000'),
    /** StrFormWord（@"..." 格式化字符串整体） —— spec: StrFormWord.cs:13 */
    STR_FORM('F'),
}
