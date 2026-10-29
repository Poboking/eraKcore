package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogicalLineReaderTest {

    private fun readerOf(vararg lines: String): LogicalLineReader =
        LogicalLineReader(lines.toList(), "test.erb")

    // ---------------------------------------------------------------
    // 行连续块 { } —— spec: EraStreamReader.cs:136-184
    // ---------------------------------------------------------------
    @Test
    fun lineContinuationBlock() {
        val r = readerOf(
            "{",
            "    abc",
            "    def",
            "}",
            "PRINTL 2",
        )
        val l1 = r.readLogicalLine()!!
        // 触发行 '{' 被丢弃（spec: EraStreamReader.cs:138-141），块内两行合并，
        // 行间插入 ReplaceContinuationBR（去 '"' 后为单个空格），行首空白保留到词法层处理
        assertEquals(LineKind.CODE, l1.kind)
        assertEquals("abc     def ", l1.content)
        val l2 = r.readLogicalLine()!!
        assertEquals("PRINTL 2", l2.content)
    }

    @Test
    fun continuationBlockStartMustBeBareBrace() {
        val r = readerOf("{ abcd", "}")
        assertFailsWith<LexError> { r.readLogicalLine() }
    }

    @Test
    fun continuationBlockUnclosedThrows() {
        val r = readerOf("{", "abc")
        assertFailsWith<LexError> { r.readLogicalLine() }
    }

    // ---------------------------------------------------------------
    // 空行 / 空白行 / 整行注释归约 —— spec: EraStreamReader.cs:107-129
    // ---------------------------------------------------------------
    @Test
    fun emptyAndCommentLinesReduced() {
        val r = readerOf("", "   ", "; 整行注释", "\t\t", "A = 1")
        val line = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        assertEquals("A = 1", line.content)
    }

    // ---------------------------------------------------------------
    // 字符串内 ';' 不终止逻辑行（由词法层保证），但整行 ';' 注释被归约
    // ---------------------------------------------------------------
    @Test
    fun commentOnlyLineInsideFunction() {
        val r = readerOf("@FUNC", "; comment", "PRINTL x")
        assertEquals(LineKind.FUNCTION_LABEL, r.readLogicalLine()!!.kind)
        val code = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, code.kind)
        assertEquals("PRINTL x", code.content)
    }

    // ---------------------------------------------------------------
    // 行分类 —— spec: ErbLoader.cs:388-481
    // ---------------------------------------------------------------
    @Test
    fun lineKinds() {
        val r = readerOf("@EVENTFIRST", "\$MYLABEL", "#DIM X", "PRINTV 1")
        assertEquals(LineKind.FUNCTION_LABEL, r.readLogicalLine()!!.kind)
        assertEquals(LineKind.GOTO_LABEL, r.readLogicalLine()!!.kind)
        assertEquals(LineKind.SHARP, r.readLogicalLine()!!.kind)
        assertEquals(LineKind.CODE, r.readLogicalLine()!!.kind)
    }

    // ---------------------------------------------------------------
    // 预处理 [SKIPSTART]/[SKIPEND] —— spec: ErbLoader.cs:39-167, 390-406
    // ---------------------------------------------------------------
    @Test
    fun preprocessorSkipBlock() {
        val r = readerOf(
            "@FUNC",
            "[SKIPSTART]",
            "DEBUGLINE 1",
            "[SKIPEND]",
            "PRINTL live",
        )
        assertEquals(LineKind.FUNCTION_LABEL, r.readLogicalLine()!!.kind)
        val line = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        assertEquals("PRINTL live", line.content)
    }

    @Test
    fun preprocessorTrailingJunkWarns() {
        val r = readerOf("[SKIPSTART] extra", "@FUNC", "PRINTL x")
        r.readLogicalLine()
        // token2 在 ']' 之前读取（spec: ErbLoader.cs:391-396），"extra" 在 ']' 之后
        // 只产生一条「後ろは無視されます」警告（spec: ErbLoader.cs:403-405）
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].message.contains("無視"))
    }

    // ---------------------------------------------------------------
    // 文件读取：BOM 剥离 / 无 BOM / CRLF 与 LF 混用
    // spec: Preload.cs readAllLinesDetectEncoding / EncodingHandler.cs:15-41
    // ---------------------------------------------------------------
    @Test
    fun utf8BomStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "@FUNC\r\nPRINTL 1".toByteArray(Charsets.UTF_8)
        val lines = LogicalLineReader.readAllLines(bytes)
        assertEquals(listOf("@FUNC", "PRINTL 1"), lines)
    }

    @Test
    fun noBomFileReadsFine() {
        val bytes = "#DIM X\r\nA = 1".toByteArray(Charsets.UTF_8)
        val lines = LogicalLineReader.readAllLines(bytes)
        assertEquals(listOf("#DIM X", "A = 1"), lines)
    }

    @Test
    fun mixedCRLFAndLF() {
        // CRLF、LF、裸 CR 混用（对齐 .NET File.ReadAllLines 行为）
        val text = "a\r\nb\nc\rd"
        assertEquals(listOf("a", "b", "c", "d"), LogicalLineReader.splitLines(text))
    }

    @Test
    fun trailingNewlineNoExtraEmptyLine() {
        assertEquals(listOf("a", "b"), LogicalLineReader.splitLines("a\nb\n"))
        assertEquals(listOf("a"), LogicalLineReader.splitLines("a\n"))
        assertEquals(listOf(""), LogicalLineReader.splitLines("\n"))
    }

    // ---------------------------------------------------------------
    // 端到端：读取 + 词法
    // ---------------------------------------------------------------
    @Test
    fun endToEndFunctionWithAssignmentBody() {
        val r = readerOf("@FUNC, ARG:0", "A = ARG:0 * 2")
        val label = r.readLogicalLine()!!
        val lexer = ErbLexer()
        val (isFunction, name, args) = lexer.parseLabelLine(label.content, label.lineNo, label.filename)
        assertTrue(isFunction)
        assertEquals("FUNC", name)
        assertEquals(4, args.size) // , ARG : 0

        val body = r.readLogicalLine()!!
        val tokens = lexer.tokenizeLine(body.content, body.lineNo)
        assertEquals(7, tokens.size) // A = ARG : 0 * 2
        assertEquals(OperatorCode.Assignment, (tokens[1] as Token.Operator).code)
        assertEquals(OperatorCode.Mult, (tokens[5] as Token.Operator).code)
    }

    @Test
    fun fileEndReportsUnclosedPreprocessor() {
        val r = readerOf("[SKIPSTART]", "@FUNC")
        r.readLogicalLine() // @FUNC
        r.fileEnd()
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].message.contains("SKIPEND"))
    }
}
