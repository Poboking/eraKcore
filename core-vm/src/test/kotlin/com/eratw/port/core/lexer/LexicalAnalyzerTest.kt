package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexicalAnalyzerTest {

    private val lexer = ErbLexer()

    private fun tokensOf(content: String): List<Token> = lexer.tokenizeLine(content, 0)

    // ---------------------------------------------------------------
    // 字符串内分号不算注释 —— spec: LexicalAnalyzer.cs:330-400（ReadString 不处理 ';'）
    // ---------------------------------------------------------------
    @Test
    fun semicolonInsideStringIsNotComment() {
        val tokens = tokensOf("PRINTV \"a;b;c\" ; これはコメント")
        assertEquals(2, tokens.size)
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        assertEquals("PRINTV", (tokens[0] as Token.Identifier).code)
        assertEquals(TokenType.STRING, tokens[1].type)
        assertEquals("a;b;c", (tokens[1] as Token.StringLiteral).value)
    }

    @Test
    fun trailingCommentIsStripped() {
        val tokens = tokensOf("A = 1 ;comment with ; more semicolons")
        assertEquals(3, tokens.size)
        assertEquals("A", (tokens[0] as Token.Identifier).code)
        assertEquals(OperatorCode.Assignment, (tokens[1] as Token.Operator).code)
        assertEquals(1L, (tokens[2] as Token.Integer).value)
    }

    // ---------------------------------------------------------------
    // 字符串转义 —— spec: LexicalAnalyzer.cs:369-394
    // ---------------------------------------------------------------
    @Test
    fun stringEscapes() {
        // \s→' ' \S→'\u3000' \t→tab \n→LF \;→';' \\+换行→无
        val tokens = tokensOf("\"a\\sb\\Sc\\td\\ne;<x>\"")
        assertEquals(1, tokens.size)
        assertEquals("a b\u3000c\td\ne;<x>", (tokens[0] as Token.StringLiteral).value)
    }

    @Test
    fun unterminatedStringThrows() {
        assertFailsWith<LexError> { tokensOf("\"abc") }
    }

    // ---------------------------------------------------------------
    // @"..." 格式化字符串 + {} / %% 内嵌 —— spec: LexicalAnalyzer.cs:886-903, 1073-1229
    // ---------------------------------------------------------------
    @Test
    fun formStringWithCurlyAndPercentEmbed() {
        // 注意1：%% 内不能嵌 {}（spec: LexicalAnalyzer.cs:916-918）；注意2：每个 % 嵌入必须闭合，
        // 否则尾部 " 会被当作字符串起点报未闭合（与原版一致，spec: LexicalAnalyzer.cs:877-885）
        val tokens = tokensOf("PRINTFORM @\"x{B}y%A + 1%z\"")
        assertEquals(2, tokens.size)
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        val form = (tokens[1] as Token.FormStr).form
        assertEquals(listOf("x", "y", "z"), form.strs)
        assertEquals(2, form.subWords.size)
        val curly = form.subWords[0] as CurlyBraceSubWord
        assertEquals(1, curly.words.size) // B
        val percent = form.subWords[1] as PercentSubWord
        assertEquals(3, percent.words.size) // A + 1
    }

    @Test
    fun curlyInsidePercentEmbedThrows() {
        // spec: LexicalAnalyzer.cs:916-918 —— %% 内嵌 {} 是原版行为下的错误
        assertFailsWith<LexError> { tokensOf("PRINTFORM @\"x{%y{B}%z\"") }
    }

    @Test
    fun formStringTripleSymbol() {
        // $$$ 三连记号 → TripleSymbolSubWord
        val tokens = tokensOf("PRINTL @\"a\$\$\$b\"")
        val form = (tokens[1] as Token.FormStr).form
        assertEquals(listOf("a", "b"), form.strs)
        assertEquals(1, form.subWords.size)
        assertEquals('$', (form.subWords[0] as TripleSymbolSubWord).code)
    }

    @Test
    fun yenAtConditional() {
        // \@三项条件式 —— spec: LexicalAnalyzer.cs:908-915, 1231-1258（裸 \@ 语法，不在引号内）
        val tokens = tokensOf("PRINTFORM \\@A ? x # y \\@")
        val form = (tokens[1] as Token.FormStr).form
        assertEquals(1, form.subWords.size)
        val yenAt = form.subWords[0] as YenAtSubWord
        assertEquals(1, yenAt.condition.size) // A
        assertEquals(listOf("x"), yenAt.left.strs)
        assertEquals(listOf("y"), yenAt.right!!.strs)
    }

    // ---------------------------------------------------------------
    // 整数 —— spec: LexicalAnalyzer.cs:30-165
    // ---------------------------------------------------------------
    @Test
    fun integerBasesAndExponent() {
        assertEquals(10L, (tokensOf("10")[0] as Token.Integer).value)
        assertEquals(26L, (tokensOf("0x1A")[0] as Token.Integer).value)
        assertEquals(5L, (tokensOf("0b101")[0] as Token.Integer).value)
        assertEquals(3000L, (tokensOf("3e3")[0] as Token.Integer).value)
        assertEquals(8L, (tokensOf("1p3")[0] as Token.Integer).value) // 1 × 2^3
    }

    // ---------------------------------------------------------------
    // 运算符全集 —— spec: LexicalAnalyzer.cs:402-509
    // ---------------------------------------------------------------
    @Test
    fun operatorSet() {
        val cases = mapOf(
            "==" to OperatorCode.Equal, "!=" to OperatorCode.NotEqual,
            "!&" to OperatorCode.Nand, "!|" to OperatorCode.Nor,
            "<=" to OperatorCode.LessEqual, ">=" to OperatorCode.GreaterEqual,
            "<<" to OperatorCode.LeftShift, ">>" to OperatorCode.RightShift,
            "||" to OperatorCode.Or, "&&" to OperatorCode.And, "^^" to OperatorCode.Xor,
            "++" to OperatorCode.Increment, "--" to OperatorCode.Decrement,
        )
        for ((src, expected) in cases) {
            val t = tokensOf(src)[0]
            assertTrue(t is Token.Operator, "source: $src")
            assertEquals(expected, (t as Token.Operator).code, "source: $src")
        }
        // 单字符
        assertEquals(OperatorCode.Plus, (tokensOf("+")[0] as Token.Operator).code)
        assertEquals(OperatorCode.BitNot, (tokensOf("~")[0] as Token.Operator).code)
        assertEquals(OperatorCode.Ternary_a, (tokensOf("?")[0] as Token.Operator).code)
        assertEquals(OperatorCode.Ternary_b, (tokensOf("#")[0] as Token.Operator).code)
    }

    @Test
    fun bareEqualWithoutAssignmentFlagThrows() {
        // spec: LexicalAnalyzer.cs:429-439 —— 非 AllowAssignment 下裸 '=' 报错
        val st = CharStream("=")
        assertFailsWith<LexError> { lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, 0) }
    }

    // ---------------------------------------------------------------
    // 标识符（含全角） —— spec: LexicalAnalyzer.cs:315-328（终止字符集之外的字符都算标识符）
    // ---------------------------------------------------------------
    @Test
    fun fullWidthIdentifier() {
        val tokens = tokensOf("全角変数_１２３+1")
        assertEquals(3, tokens.size)
        assertEquals("全角変数_１２３", (tokens[0] as Token.Identifier).code)
        assertEquals(TokenType.OPERATOR, tokens[1].type)
        assertEquals(1L, (tokens[2] as Token.Integer).value)
    }

    // ---------------------------------------------------------------
    // 括号配对检查 —— spec: LexicalAnalyzer.cs:710-728
    // ---------------------------------------------------------------
    @Test
    fun unclosedBracketThrows() {
        assertFailsWith<LexError> { tokensOf("A[1") }
        assertFailsWith<LexError> { tokensOf("F(1))") }
    }

    // ---------------------------------------------------------------
    // '@' 函数行 / '$' 标签行 / '#' 预处理行
    // ---------------------------------------------------------------
    @Test
    fun functionLabelLine() {
        val (isFunction, name, argTokens) = lexer.parseLabelLine("@EVENTFIRST", 0, "test.erb")
        assertTrue(isFunction)
        assertEquals("EVENTFIRST", name)
        assertTrue(argTokens.isEmpty())
    }

    @Test
    fun functionLabelLineWithArgs() {
        val (_, name, argTokens) = lexer.parseLabelLine("@COM, ARG:0, ARG:1", 0, "test.erb")
        assertEquals("COM", name)
        assertEquals(8, argTokens.size) // , ARG : 0 , ARG : 1
    }

    @Test
    fun gotoLabelLine() {
        val (isFunction, name, argTokens) = lexer.parseLabelLine("\$LABEL_SKIP", 0, "test.erb")
        assertEquals(false, isFunction)
        assertEquals("LABEL_SKIP", name)
        assertTrue(argTokens.isEmpty())
    }

    @Test
    fun sharpDimLine() {
        val (keyword, tokens) = lexer.parseSharpLine("#DIM HOGE, 1, 2", 0, "test.erb")
        assertEquals("DIM", keyword)
        // HOGE , 1 , 2
        assertEquals(5, tokens.size)
        assertEquals("HOGE", (tokens[0] as Token.Identifier).code)
    }

    @Test
    fun sharpFunctionLine() {
        val (keyword, tokens) = lexer.parseSharpLine("#FUNCTION", 0, "test.erb")
        assertEquals("FUNCTION", keyword)
        assertTrue(tokens.isEmpty())
    }

    // ---------------------------------------------------------------
    // 行号与列号
    // ---------------------------------------------------------------
    @Test
    fun tokenPositions() {
        val tokens = lexer.tokenizeLine("  AB, 1", 7)
        assertEquals(7, tokens[0].line)
        assertEquals(2, tokens[0].col)
        assertEquals(4, tokens[1].col)
        assertEquals(6, tokens[2].col)
    }

    // ---------------------------------------------------------------
    // SkipWhiteSpace 的注释归约 —— spec: LexicalAnalyzer.cs:647-690
    // ---------------------------------------------------------------
    @Test
    fun skipWhiteSpaceCommentReduction() {
        val st = CharStream("  ; 全部注释")
        lexer.skipWhiteSpace(st)
        assertTrue(st.eos)
    }
}
