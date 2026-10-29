package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 前端边角逐条对拍（M6B-FRONT 审计，C# 侧证据全部给 `文件:行号`）。
 *
 * 覆盖：`{}` 续行里的多行 `@"..."`、反斜杠的真实语义（转义而非续行）、分号注释的四种形态、
 * BOM 只剥唯一前导与 Shift-JIS 回退、全角空格 / 全角标识符、`[IF 宏]` 的宏表判定、
 * `#DIM` / `#FUNCTIONS` 的取 token 口径、指数溢出的官方报错文本。
 *
 * 语料读数（`eratw-画蛇添足版eratw4.980/ERB`，3,971 文件）：
 * - 行末反斜杠 3 处，全部是字符串里的 `\\`（不是续行）；真正跨行的串 59 处，形态全是 `{}` 块里的 `@"`；
 * - 双 BOM 文件 0 个；
 * - `[IF <名>]` 非 DEBUG 形态只有 1 处（`[IF NDEBUG]`），而 ERH 的 22 个宏里没有 `DEBUG` / `NDEBUG`
 *   ⇒ 语料上「宏表是否参与 IF 判定」与「恒未定义」同值（所以本用例用合成行覆盖）。
 */
class LexerFrontAuditTest {

    private val lexer = ErbLexer()

    private fun linesOf(r: LogicalLineReader): List<String> =
        generateSequence { r.readLogicalLine() }.map { it.content }.toList()

    // ------------------------------------------------------------------
    // 1. `{}` 续行：多行 `@"..."` 的唯一形态
    // ------------------------------------------------------------------

    @Test
    fun `续行块把跨物理行的 @ 串拼成一条逻辑行`() {
        // spec: EraStreamReader.cs:136-184 —— 独占一行的 '{' 起块，块内逐行**原样** Append(text)，
        // 每行后再 Append(Config.ReplaceContinuationBR 去掉引号) = 一个空格（:178-179，emuera.config:72 `" "`）
        val r = LogicalLineReader(
            listOf("{", "    PRINTFORML @\"ABC", "    DEF\"", "}"),
            "test.erb",
        )
        val line = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        // 逻辑行行号 = 闭合 '}' 的 0 基物理行号（spec: ErbLoader.cs:389 ScriptPosition 用 curNo）
        assertEquals(3, line.lineNo)
        // 行2 的 4 格缩进 + 拼接空格 + 行3 的 4 格缩进 = ABC 与 DEF 之间 5 个空格
        assertEquals("PRINTFORML @\"ABC" + " ".repeat(5) + "DEF\" ", line.content)

        val toks = ErbLexer().tokenizeLine(line.content, line.lineNo)
        assertEquals(2, toks.size)
        assertEquals("ABC" + " ".repeat(5) + "DEF", (toks[1] as Token.FormStr).form.strs[0])
    }

    @Test
    fun `续行块内的整行注释与行尾注释按词法层截断`() {
        // 块内不剥离 ';'：注释符原样进合并串，由 SkipWhiteSpace / Analyse 的 ';' 分支截断
        // （spec: LexicalAnalyzer.cs:662-676 / :919-933）
        val r = LogicalLineReader(
            listOf("{", "    PRINTL 1 ; 说明", "    PRINTL 2", "}"),
            "test.erb",
        )
        val line = r.readLogicalLine()!!
        assertTrue(line.content.contains("; 说明"))
        val toks = ErbLexer().tokenizeLine(line.content, 0)
        assertEquals(2, toks.size, "';' 之后整行余下（含下一物理行的 PRINTL 2）都算注释")
    }

    // ------------------------------------------------------------------
    // 2. 反斜杠：转义 + `\@` 三项式，没有「行续行」
    // ------------------------------------------------------------------

    @Test
    fun `反斜杠不是续行符`() {
        // 物理行永不含 LF：拆行先于词法（spec: EraStreamReader.cs:52 File.ReadAllLines + :96-129），
        // 因此 ReadString 的 `\`+LF 分支（LexicalAnalyzer.cs:390-391）与
        // AnalyseFormattedString 的 `\`+LF 分支（:1157 / :1172）在本工程不可达。
        assertEquals(listOf("A = \"x\\", "y\""), LogicalLineReader.splitLines("A = \"x\\\ny\""))

        // 转义下一个字符：`\`+空格 → 空格（spec: LexicalAnalyzer.cs:387-389）
        assertEquals("a b", (lexer.tokenizeLine("\"a\\ b\"", 0)[0] as Token.StringLiteral).value)

        // 块内行末的 `\` 会吃掉拼接空格，仍按「转义下一个字符」解释，而不是续行
        val r = LogicalLineReader(listOf("{", "PRINTFORML @\"a\\", "b\"", "}"), "t.erb")
        val form = (ErbLexer().tokenizeLine(r.readLogicalLine()!!.content, 0)[1] as Token.FormStr).form
        assertEquals("a b", form.strs[0])
    }

    // ------------------------------------------------------------------
    // 3. 分号注释的四种形态
    // ------------------------------------------------------------------

    @Test
    fun `分号注释 行尾 分号叹号分号 分号尖号分号 与 分号井号分号`() {
        // 行尾注释：SkipWhiteSpace 遇 ';' 直接 Seek(End)（spec: LexicalAnalyzer.cs:662-676）
        assertEquals(2, lexer.tokenizeLine("PRINTL 1 ; PRINTL 2", 0).size)

        // ';!;' 在 Analyse 里也跳过继续（spec: LexicalAnalyzer.cs:926-932）
        assertEquals(4, lexer.tokenizeLine("PRINTL 1 ;!; PRINTL 2", 0).size)

        // ';^;' 只在 SkipWhiteSpace 里被跳过（:669），Analyse 只认 ';!;'（:926）→ 行内截断
        val st = CharStream(";^; A = 1")
        assertEquals(1, lexer.skipWhiteSpace(st), "跳过的 3 字符标记不计入返回值（:669-672）")
        assertEquals("A = 1", st.rowString.substring(st.currentPosition))
        assertEquals(2, lexer.tokenizeLine("PRINTL 1 ;^; PRINTL 2", 0).size)

        // 行首 ';^;'：SkipWhiteSpace 跳过后仍有代码 → 该行仍是逻辑行（spec: EraStreamReader.cs:124-129）
        val r = LogicalLineReader(listOf(";^; PRINTL 2", "@F"), "t.erb")
        assertEquals("PRINTL 2", r.readLogicalLine()!!.content)

        // ';#;' 只在 DebugMode 下跳过（:664 / :920）
        assertEquals(2, lexer.tokenizeLine("PRINTL 1 ;#; PRINTL 2", 0).size)
        val dbg = ErbLexer(LexerConfig(debugMode = true))
        assertEquals(4, dbg.tokenizeLine("PRINTL 1 ;#; PRINTL 2", 0).size)
    }

    // ------------------------------------------------------------------
    // 4. BOM（唯一前导）与编码探测
    // ------------------------------------------------------------------

    @Test
    fun `BOM 只剥唯一前导`() {
        // 字节层：EF BB BF 只吞一次（spec: EncodingHandler.cs:20-26 读 3 字节判 preamble）
        val twoBoms = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
        ) + "A".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("\uFEFFA"), LogicalLineReader.readAllLines(twoBoms))

        // 行层：首行再剥一个 → 第二个 BOM 留在行首，于是行首不是 '#'（分类为普通代码行）
        val r = LogicalLineReader(listOf("\uFEFF\uFEFF#DIM X"), "t.erb")
        val line = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        assertEquals("\uFEFF#DIM X", line.content)

        // 非首行的 U+FEFF 一律保留
        val r2 = LogicalLineReader(listOf("@F", "\uFEFFPRINTL 1"), "t.erb")
        assertEquals(LineKind.FUNCTION_LABEL, r2.readLogicalLine()!!.kind)
        assertEquals("\uFEFFPRINTL 1", r2.readLogicalLine()!!.content)
    }

    @Test
    fun `严格 UTF-8 失败回退 Shift-JIS`() {
        // spec: EncodingHandler.cs:27-40 —— StreamReader 严格 UTF-8 校验失败 → shiftjisEncoding(932)
        // 0x8A 0xBF = 漢、0x8E 0x9A = 字（Shift-JIS，且不是合法 UTF-8）
        val sjis = byteArrayOf(0x8A.toByte(), 0xBF.toByte(), 0x8E.toByte(), 0x9A.toByte())
        assertEquals(listOf("漢字"), LogicalLineReader.readAllLines(sjis))
    }

    // ------------------------------------------------------------------
    // 5. 全角空格 / 全角标识符
    // ------------------------------------------------------------------

    @Test
    fun `全角空格按 SystemAllowFullSpace 判定`() {
        // 默认 true（ConfigData.cs:101），emuera.config 未覆盖 → 本游戏全角空格当空白
        assertEquals(2, lexer.tokenizeLine("前半　後半", 0).size)

        // U+3000 是标识符终止字符（spec: LexicalAnalyzer.cs:28 终止集含 "　"）
        val st = CharStream("名前　")
        assertEquals("名前", lexer.readSingleIdentifier(st))
        assertEquals('\u3000', st.current)

        // SystemAllowFullSpace=false：代码位出现全角空格即 CodeEE（:748-754）
        val strict = ErbLexer(LexerConfig(systemAllowFullSpace = false))
        assertFailsWith<LexError> { strict.tokenizeLine("A　B", 0) }
    }

    // ------------------------------------------------------------------
    // 6. `[IF 宏]` / `[ELSEIF 宏]`：宏表参与判定，嵌套与跳过区互不串味
    // ------------------------------------------------------------------

    @Test
    fun `IF 宏 命中第一分支 ELSE 关闭`() {
        val r = LogicalLineReader(
            listOf("[IF FOO]", "PRINTL yes", "[ELSE]", "PRINTL no", "[ENDIF]", "PRINTL tail"),
            "t.erb",
            // 宏名大小写不敏感（原版 GetMacro 用 OrdinalIgnoreCase，Config.cs:299-308）
            macroNames = setOf("foo"),
        )
        assertEquals(listOf("PRINTL yes", "PRINTL tail"), linesOf(r))
    }

    @Test
    fun `IF 宏 未定义时取 ELSE 分支`() {
        val r = LogicalLineReader(
            listOf("[IF FOO]", "PRINTL yes", "[ELSE]", "PRINTL no", "[ENDIF]", "PRINTL tail"),
            "t.erb",
        )
        assertEquals(listOf("PRINTL no", "PRINTL tail"), linesOf(r))
    }

    @Test
    fun `IF ELSEIF 只走首个命中分支`() {
        fun body(vararg macros: String): List<String> = linesOf(
            LogicalLineReader(
                listOf(
                    "[IF A]", "PRINTL x",
                    "[ELSEIF B]", "PRINTL y",
                    "[ELSE]", "PRINTL z",
                    "[ENDIF]", "PRINTL t",
                ),
                "t.erb",
                macroNames = macros.toSet(),
            ),
        )
        // spec: ErbLoader.cs:94 / :109 / :124 —— done 一旦置位，后续分支一律禁用
        assertEquals(listOf("PRINTL x", "PRINTL t"), body("A"))
        assertEquals(listOf("PRINTL y", "PRINTL t"), body("B"))
        assertEquals(listOf("PRINTL z", "PRINTL t"), body())
        assertEquals(listOf("PRINTL x", "PRINTL t"), body("A", "B"))
    }

    @Test
    fun `SKIPSTART 区间内 IF 命中也不放行`() {
        // spec: ErbLoader.cs:163-166 —— AddKeyWord 末尾 `if (skip) Disabled = true`，
        // 故 skip 区内的任何分支都禁用，直至 SKIPEND 恢复压栈值
        val r = LogicalLineReader(
            listOf("[SKIPSTART]", "[IF FOO]", "PRINTL hidden", "[ENDIF]", "[SKIPEND]", "PRINTL live"),
            "t.erb",
            macroNames = setOf("FOO"),
        )
        assertEquals(listOf("PRINTL live"), linesOf(r))
    }

    @Test
    fun `未闭合 IF 在文件末尾告警`() {
        val r = LogicalLineReader(listOf("[IF FOO]", "PRINTL x"), "t.erb", macroNames = setOf("FOO"))
        r.readLogicalLine()
        r.fileEnd()
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].message.contains("ENDIF"))
    }

    // ------------------------------------------------------------------
    // 7. `#DIM` / `#FUNCTIONS` 的取 token 口径
    // ------------------------------------------------------------------

    @Test
    fun `井号行只有 DIM DIMS LOCALSIZE LOCALSSIZE 取 token`() {
        // spec: LogicalLineParser.cs:220 / :281 —— 这两族各做一次 Analyse(EoL, AllowAssignment)；
        // 其余关键字（FUNCTION / FUNCTIONS / ONLY / PRI / LATER / SINGLE）只改标签属性，不取 token
        assertEquals(5, lexer.parseSharpLine("#DIM HOGE, 1, 2", 0, "t.erb").second.size)
        assertEquals(1, lexer.parseSharpLine("#LOCALSIZE 20", 0, "t.erb").second.size)
        assertEquals(0, lexer.parseSharpLine("#FUNCTION", 0, "t.erb").second.size)
        assertEquals(0, lexer.parseSharpLine("#FUNCTIONS", 0, "t.erb").second.size)

        // 关键字大小写不敏感（Config.StringComparison = OrdinalIgnoreCase，IGNORE CASE:YES emuera.config）
        val (kw, toks) = lexer.parseSharpLine("#dim HOGE", 0, "t.erb")
        assertEquals("dim", kw)
        assertEquals(1, toks.size)
        assertEquals("HOGE", (toks[0] as Token.Identifier).code)

        // '#' 后为空 → 原版 CanNotInterpretSharpLine（spec: LogicalLineParser.cs:24-28，level 1）
        assertFailsWith<LexError> { lexer.parseSharpLine("#", 0, "t.erb") }
    }

    // ------------------------------------------------------------------
    // 8. 指数溢出的官方报错文本
    // ------------------------------------------------------------------

    @Test
    fun `指数溢出报错取数字起点到行尾`() {
        // spec: LexicalAnalyzer.cs:85 —— st.Substring(currentPosition, currentPosition2) 把
        // **结束位置**当长度传；CharStream.cs:90-98 再钳制（start+length > len ⇒ 取到行尾），
        // 故文本是「数字起点到本行末尾」，不是数字本身（ADR-005 原样复刻）
        val e = assertFailsWith<LexError> { lexer.tokenizeLine("PRINTV 3e19 + 1", 3) }
        assertEquals("\"3e19 + 1\"は64ビット符号付き整数の範囲を超えています", e.message)

        // 数字就在行尾时两者同形（钳制后长度恰好等于数字长度）
        val e2 = assertFailsWith<LexError> { lexer.tokenizeLine("PRINTV 3e19", 0) }
        assertEquals("\"3e19\"は64ビット符号付き整数の範囲を超えています", e2.message)
    }

    // ------------------------------------------------------------------
    // 9. `@"..."` 未闭合（跨行串由续行块提供，未闭合仍是 CodeEE）
    // ------------------------------------------------------------------

    @Test
    fun `未闭合的 @ 串报 NotClosed`() {
        // spec: LexicalAnalyzer.cs:896-900 —— AnalyseFormattedString 之后 Current != '"' → NotClosed
        assertFailsWith<LexError> { lexer.tokenizeLine("PRINTFORML @\"abc", 0) }
    }
}
