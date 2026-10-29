package com.eratw.port.core.expr

/**
 * ERB 运算符编码与优先级表。
 *
 * 直接移植自 C# 原版，保留原始的「高位标志位 + 低位优先级」位编码：
 * - bit17 (__UNARY__)       前置一元
 * - bit18 (__BINARY__)      二元
 * - bit19 (__TERNARY__)     三目
 * - bit20 (__UNARY_AFTER__) 后置一元
 * - 低 8 位                 优先级（数值越大绑定越紧）
 *
 * spec: OperatorCode.cs:3-40, OperatorManager.cs:131-154
 */
enum class OperatorCode(val value: Int) {
    NULL(0x00000),

    PLUS(0x30180),              // +
    MINUS(0x30280),             // -
    MULT(0x20390),              // *
    DIV(0x20490),               // /
    MOD(0x20590),               // %
    EQUAL(0x20660),             // ==
    GREATER(0x20765),           // >
    LESS(0x20865),              // <
    GREATER_EQUAL(0x20965),     // >=
    LESS_EQUAL(0x20A65),        // <=
    NOT_EQUAL(0x20B60),         // !=
    INCREMENT(0x92000),         // ++  (前缀 + 后置)
    DECREMENT(0x92100),         // --  (前缀 + 后置)
    AND(0x20C40),               // &&
    OR(0x20D40),                // ||
    XOR(0x21540),               // ^^
    NAND(0x21640),              // !&
    NOR(0x21740),               // !|
    BIT_AND(0x20E50),           // &
    BIT_OR(0x20F50),            // |
    BIT_XOR(0x21050),           // ^
    NOT(0x11100),               // !
    BIT_NOT(0x11200),           // ~
    RIGHT_SHIFT(0x21370),       // >>
    LEFT_SHIFT(0x21470),        // <<
    TERNARY_A(0x41805),         // ?
    TERNARY_B(0x41910),         // #  (ERB 三目的第二分隔符，不是 C 的 ':')
    ASSIGNMENT(0x1FE),          // =
    ASSIGNMENT_STR(0x2FE);      // '=

    val isUnary: Boolean get() = (value and __UNARY__) == __UNARY__
    val isUnaryAfter: Boolean get() = (value and __UNARY_AFTER__) == __UNARY_AFTER__
    val isBinary: Boolean get() = (value and __BINARY__) == __BINARY__
    val isTernary: Boolean get() = (value and __TERNARY__) == __TERNARY__

    /** 数值越大绑定越紧。 spec: OperatorManager.cs:151-154 */
    val priority: Int get() = value and __PRIORITY_MASK__

    companion object {
        private const val __PRIORITY_MASK__ = 0xFF
        private const val __UNARY__ = 0x10000
        private const val __BINARY__ = 0x20000
        private const val __TERNARY__ = 0x40000
        private const val __UNARY_AFTER__ = 0x80000

        /** 运算符文本 → 编码，插入顺序与原版一致。 spec: OperatorManager.cs:7-113 */
        private val opDictionary: Map<String, OperatorCode> = linkedMapOf(
            "+" to PLUS,
            "-" to MINUS,
            "*" to MULT,
            // 原版 OperatorManager.cs:7-113 的文本表确实**不含** "/"、"%"、"=="：
            // 这三条由词法器直接构造（LexicalAnalyzer.cs:426 → Div、:428 → Mod、:433 → Equal，
            // 赋值形态在同文件 :550 / :556 / :562；移植侧对应 LexicalAnalyzer.kt:193-196）。
            // 本表补上这三条只作「源码文本 → 编码」的反查用（toOperatorString / fromText），
            // 与原版词法器口径不冲突。原 TODO(verify) 已由上述行号坐实（M4-N / n-expraudit）。
            "/" to DIV,
            "%" to MOD,
            ">" to GREATER,
            "<" to LESS,
            ">=" to GREATER_EQUAL,
            "<=" to LESS_EQUAL,
            "!=" to NOT_EQUAL,
            "==" to EQUAL,
            "&&" to AND,
            "||" to OR,
            "^^" to XOR,
            "!&" to NAND,
            "!|" to NOR,
            "&" to BIT_AND,
            "|" to BIT_OR,
            "!" to NOT,
            "^" to BIT_XOR,
            "~" to BIT_NOT,
            "?" to TERNARY_A,
            "#" to TERNARY_B,
            ">>" to RIGHT_SHIFT,
            "<<" to LEFT_SHIFT,
            "++" to INCREMENT,
            "--" to DECREMENT,
            "=" to ASSIGNMENT,
            "'=" to ASSIGNMENT_STR,
        )

        fun fromText(text: String): OperatorCode? = opDictionary[text]

        /**
         * 按 C# 原版枚举名映射（"Equal"/"Div"/"Mod"/"Ternary_a"/"AssignmentStr"…）。
         * 供 lexer adapter 使用：core.lexer 的 Token.Operator.text 即原版枚举名
         * （见 m1-lexer 的说明），与 [fromText] 的源码符号文本是两套键。
         */
        fun fromCSharpName(name: String): OperatorCode? = when (name) {
            "Plus" -> PLUS
            "Minus" -> MINUS
            "Mult" -> MULT
            "Div" -> DIV
            "Mod" -> MOD
            "Equal" -> EQUAL
            "Greater" -> GREATER
            "Less" -> LESS
            "GreaterEqual" -> GREATER_EQUAL
            "LessEqual" -> LESS_EQUAL
            "NotEqual" -> NOT_EQUAL
            "Increment" -> INCREMENT
            "Decrement" -> DECREMENT
            "And" -> AND
            "Or" -> OR
            "Xor" -> XOR
            "Nand" -> NAND
            "Nor" -> NOR
            "BitAnd" -> BIT_AND
            "BitOr" -> BIT_OR
            "BitXor" -> BIT_XOR
            "Not" -> NOT
            "BitNot" -> BIT_NOT
            "RightShift" -> RIGHT_SHIFT
            "LeftShift" -> LEFT_SHIFT
            "Ternary_a" -> TERNARY_A
            "Ternary_b" -> TERNARY_B
            "Assignment" -> ASSIGNMENT
            "AssignmentStr" -> ASSIGNMENT_STR
            else -> null
        }

        fun toOperatorString(op: OperatorCode): String =
            opDictionary.entries.firstOrNull { it.value == op }?.key ?: ""
    }
}
