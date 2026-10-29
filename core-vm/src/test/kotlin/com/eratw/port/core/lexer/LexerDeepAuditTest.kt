package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * deep-lexer 波（2026-10-26 fixcore）词法层回归 —— 逐条对应本轮修复的 C# 语义：
 *
 * 1. `{}` 续行块合并后的流仍要过 ErbLoader 的 `[` 预处理判定（EraStreamReader.cs:181-183 → ErbLoader.cs:387-390）；
 * 2. 合并后归约为空白的块不产逻辑行（LogicalLineParser.cs:387-391）；
 * 3. BOM 探测补齐 UTF-16/32（EncodingHandler.cs:20-41 + .NET StreamReader.DetectEncoding）；
 * 4. 文案照抄：NotClosed（Lang.cs:3598）、UnexpectedCharacter("[[")（:3582）、
 *    UnexpectedFullWidthSpace 的 `{0}`（:3562 + Config.cs:291-294）、MacroOverLimit（:3554）。
 */
class LexerDeepAuditTest {

    private val lexer = ErbLexer()

    private fun linesOf(r: LogicalLineReader): List<String> =
        generateSequence { r.readLogicalLine() }.map { it.content }.toList()

    // ------------------------------------------------------------------
    // 1. 续行块合并后仍走预处理行判定
    // ------------------------------------------------------------------

    @Test
    fun `续行块合并出的方括号行按预处理指令处理而不是代码行`() {
        // 合并串 = "[SKIPSTART] "，首字符 '[' → ErbLoader.cs:390-406 的预处理分支：
        // 读 keyword=SKIPSTART、']' 后的余下部分只告警 IgnoreAfterPreprosessor（:402-405）。
        // 旧实现直接 classify 成 CODE，SKIPSTART 不生效 → "PRINTL hidden" 会被放行。
        val r = LogicalLineReader(
            listOf("{", "[SKIPSTART]", "}", "PRINTL hidden", "[SKIPEND]", "PRINTL live"),
            "t.erb",
        )
        assertEquals(listOf("PRINTL live"), linesOf(r))
        assertTrue(
            r.warnings.any { it.message == "[SKIPSTART]の後ろは無視されます。" },
            "合并行 ']' 之后的内容应告警 IgnoreAfterPreprosessor：${r.warnings}",
        )
    }

    @Test
    fun `续行块合并出的 IF 行参与分支选择`() {
        // 合并串 = "[IF FOO] "，FOO 未定义 → disabled=true，后续行被禁用直到文件的 FileEnd
        // （spec: ErbLoader.cs:94 Disabled = GetMacro(token2) == null）
        val r = LogicalLineReader(
            listOf("{", "[IF FOO]", "}", "PRINTL hidden"),
            "t.erb",
        )
        assertEquals(emptyList<String>(), linesOf(r))
    }

    // ------------------------------------------------------------------
    // 2. 空白合并块不产逻辑行（已在 LexerAuditTest 钉桩，此处补块尾边界）
    // ------------------------------------------------------------------

    @Test
    fun `空白合并块后紧跟的井号行照常识别`() {
        val r = LogicalLineReader(
            listOf("{", "  ", "}", "@F", "#DIM X", "PRINTL 2"),
            "t.erb",
        )
        assertEquals(LineKind.FUNCTION_LABEL, r.readLogicalLine()!!.kind)
        assertEquals(LineKind.SHARP, r.readLogicalLine()!!.kind)
        assertEquals("PRINTL 2", r.readLogicalLine()!!.content)
    }

    // ------------------------------------------------------------------
    // 3. BOM 探测：UTF-16 / UTF-32
    // ------------------------------------------------------------------

    @Test
    fun `UTF-16LE BOM 文件按 UTF-16 解码`() {
        // spec: EncodingHandler.cs:27-32 —— StreamReader 读到 FF FE 即切 Unicode(UTF-16 LE)并吞掉 BOM
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "漢字\nA".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("漢字", "A"), LogicalLineReader.readAllLines(bytes))
    }

    @Test
    fun `UTF-16BE BOM 文件按 UTF-16 解码`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "漢字".toByteArray(Charsets.UTF_16BE)
        assertEquals(listOf("漢字"), LogicalLineReader.readAllLines(bytes))
    }

    @Test
    fun `UTF-32LE 与 UTF-32BE BOM 分别优先于 UTF-16 前缀`() {
        // FF FE 00 00 必须走 UTF-32 LE，不能按 FF FE 截成 UTF-16 LE（.NET StreamReader.DetectEncoding 顺序）
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00.toByte(), 0x00.toByte()) +
            "漢".toByteArray(charset("UTF-32LE"))
        assertEquals(listOf("漢"), LogicalLineReader.readAllLines(le))

        val be = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0xFE.toByte(), 0xFF.toByte()) +
            "漢".toByteArray(charset("UTF-32BE"))
        assertEquals(listOf("漢"), LogicalLineReader.readAllLines(be))
    }

    // ------------------------------------------------------------------
    // 4. 文案照抄
    // ------------------------------------------------------------------

    @Test
    fun `未闭合双引号串的 NotClosed 是三连双引号`() {
        // Lang.Error.NotClosed = "{0}"が閉じられていません（Lang.cs:3598），实参 = 一个半角双引号
        val e = assertFailsWith<LexError> { lexer.tokenizeLine("PRINTFORML \"abc", 0) }
        assertEquals("\"\"\"が閉じられていません", e.message)

        // @ 串未闭合走同一文案
        val e2 = assertFailsWith<LexError> { lexer.tokenizeLine("PRINTFORML @\"abc", 0) }
        assertEquals("\"\"\"が閉じられていません", e2.message)
    }

    @Test
    fun `顶层双方法括号报 UnexpectedCharacter 且实参是双方括号`() {
        // spec: LexicalAnalyzer.cs:809-813 —— RenameDic==null 分支实参是字面量 "[["
        val e = assertFailsWith<LexError> { lexer.tokenizeLine("PRINTL [[X]]", 0) }
        assertEquals("字句解析中に予期しない文字'[['を発見しました", e.message)
    }

    @Test
    fun `全角空格报错填官方配置显示名而不是枚举名`() {
        // {0} = Config.GetConfigName(SystemAllowFullSpace) = "全角スペースをホワイトスペースに含める/Whitespace includes full-width space"
        // spec: LexicalAnalyzer.cs:751 + Config.cs:291-294 + ConfigData.cs:101/204-214
        val strict = ErbLexer(LexerConfig(systemAllowFullSpace = false))
        val e = assertFailsWith<LexError> { strict.tokenizeLine("A\u3000B", 0) }
        assertEquals(
            "予期しない全角スペースを発見しました(この警告はシステムオプション" +
                "「全角スペースをホワイトスペースに含める/Whitespace includes full-width space」により無視できます)",
            e.message,
        )
        assertTrue(!e.message!!.contains("SystemAllowFullSpace」"))
    }

    @Test
    fun `宏展开超限报 MacroOverLimit 官方文案`() {
        // spec: LexicalAnalyzer.cs:954-963 —— string.Format(MacroOverLimit.Text, "100")
        val cfg = LexerConfig(macros = mapOf("M" to "M"))
        val e = assertFailsWith<LexError> { ErbLexer(cfg).tokenizeLine("M", 0) }
        assertEquals("マクロの展開数が1文あたりの上限値100を超えました(自己参照・循環参照のおそれ)", e.message)
    }
}