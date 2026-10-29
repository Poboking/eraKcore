package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 词法 / 逻辑行对拍（M7 w7-lexeraudit，产品零改动）。
//
// 对拍面：core.lexer 的 CharStream / LexicalAnalyzer 移植（ErbLexer）/ LogicalLineReader
// 对照 C#：Script.Parser/LexicalAnalyzer.cs、Script.Parser/LogicalLineParser.cs、
// Utils/CharStream.cs、Sub/EraStreamReader.cs、Script.Loader/ErbLoader.cs。
//
// 覆盖点：CRLF/LF/裸 CR 拆行与末尾终止符、BOM、`{}` 续行、`@""` 跨行与内嵌
// （`{}` / `%` / 三连记号 / `\@`）、`;` 注释边界、全角与半角符号、行尾空白、
// 赋值行两段式。已知分歧按现状钉桩并登记 TODO(verify) 与 C# 行号。
class LexerAuditTest {

    private val lexer = ErbLexer()

    private fun tokensOf(src: String): List<Token> = ErbLexer().tokenizeLine(src, 0)

    // ------------------------------------------------------------------
    // 1. 行尾空白 / 全角空格 / SkipAllSpace
    // ------------------------------------------------------------------

    @Test
    fun `行尾空白 制表 全角空格 都不产生 token`() {
        // spec: LexicalAnalyzer.cs:744-747 —— Analyse 遇制表/半角空格只 ShiftNext，不产词
        // spec: LexicalAnalyzer.cs:748-754 —— 全角空格在 SystemAllowFullSpace=true 下同样只前进
        assertEquals(2, tokensOf("PRINTL 1 \t \u3000 ").size)

        // spec: LexicalAnalyzer.cs:622-636 SkipAllSpace 只认 空格 / 制表 / 全角空格，返回跳过个数
        val st = CharStream("  \t\u3000  A")
        assertEquals(6, lexer.skipAllSpace(st))
        assertEquals('A', st.current)

        // 集合外的控制字符（此处回车）立即停止，计数为 0（C# 同，:628 的三元比较）
        val cr = CharStream("\rA")
        assertEquals(0, lexer.skipAllSpace(cr))
        assertEquals('\r', cr.current)
    }

    @Test
    fun `SystemAllowFullSpace 关闭时 SkipWhiteSpace 停在 U+3000`() {
        val strict = ErbLexer(LexerConfig(systemAllowFullSpace = false))
        // spec: LexicalAnalyzer.cs:677-684 —— 遇 U+3000 且开关关闭：直接返回已计半角空白数，游标不动
        val st = CharStream("  \t\u3000A")
        assertEquals(3, strict.skipWhiteSpace(st))
        assertEquals('\u3000', st.current)

        // 同一字符交给 Analyse 就是 CodeEE（:748-754），与 skipWhiteSpace 的「静默停住」不同
        assertFailsWith<LexError> { strict.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, 0) }

        // 默认配置（ConfigData.cs:101 为 true）下同一串只剩 1 个标识符 token
        assertEquals(1, tokensOf("  \t\u3000A").size)
    }

    // ------------------------------------------------------------------
    // 2. 标识符终止字符全集 / 全角非终止字符
    // ------------------------------------------------------------------

    @Test
    fun `标识符终止字符全集逐个生效 且不被消费`() {
        // spec: LexicalAnalyzer.cs:28 —— SearchValues.Create 的字面量：
        // 空格 U+3000 . + - * / % = ! < > | & ^ ~ ? # ) } ] , : ( { [ $ \ ' " @ ; 制表
        val terminators = " \u3000.+-*/%=!<>|&^~?#)}],:({[\$\\'\"@;\t"
        for (t in terminators) {
            val st = CharStream("ab" + t + "cd")
            assertEquals("ab", lexer.readSingleIdentifier(st), "终止字符 U+%04X".format(t.code))
            assertEquals(2, st.currentPosition, "终止字符不被消费 U+%04X".format(t.code))
            assertEquals(t, st.current, "终止字符留在游标处 U+%04X".format(t.code))
        }
        // spec: LexicalAnalyzer.cs:315-328 —— 除终止集外全部算标识符：数字、全角数字、全角符号、假名同样在内
        val rest = "ab0０＋，；＃＄＠（あcd"
        val st2 = CharStream(rest)
        assertEquals(rest, lexer.readSingleIdentifier(st2))
        assertTrue(st2.eos)
    }

    @Test
    fun `全角符号不参与运算符切分也不起注释`() {
        // spec: LexicalAnalyzer.cs:28 —— 终止集只有半角符号，全角 ＋ 不在其中
        val id = tokensOf("Ａ＋Ｂ")
        assertEquals(1, id.size)
        assertEquals("Ａ＋Ｂ", (id[0] as Token.Identifier).code)

        // spec: LexicalAnalyzer.cs:662 / :919 —— 只有半角 ';' 进注释分支；全角分号 U+FF1B 属标识符字符
        val semi = tokensOf("PRINTL 1 ；コメント")
        assertEquals(3, semi.size)
        assertEquals("；コメント", (semi[2] as Token.Identifier).code)
        assertEquals(2, tokensOf("PRINTL 1 ; コメント").size)

        // spec: ErbLoader.cs:413 / :425 —— 行分类只看半角 '#' / '$' / '@'，全角 ＃ 行是普通代码行
        val line = LogicalLineReader(listOf("＃DIM X"), "t.erb").readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        assertEquals("＃DIM X", line.content)

        // spec: LexicalAnalyzer.cs:105 —— char.IsDigit 对全角数字为真，循环消费后在 :145-152
        // 的 long.Parse 上失败（移植版在 :153 的同义校验失败）
        val e = assertFailsWith<LexError> { tokensOf("PRINTL 1\uFF11") }
        assertTrue(e.message!!.contains("1\uFF11"))
    }

    // ------------------------------------------------------------------
    // 3. 拆行：CRLF / LF / 裸 CR / BOM
    // ------------------------------------------------------------------

    @Test
    fun `裸 CR CRLF LF 的拆行口径与末尾终止符`() {
        // spec: EraStreamReader.cs:52 File.ReadAllLines —— ReadLine 把 CR、LF、CRLF 一律当行终止
        assertEquals(listOf("a", "b"), LogicalLineReader.splitLines("a\rb"))
        assertEquals(listOf("a", "b"), LogicalLineReader.splitLines("a\r\nb"))
        assertEquals(listOf("a", "b"), LogicalLineReader.splitLines("a\nb"))
        // 只有终止符时是「一个空行」，不是两行；末尾终止符不再多产一行
        assertEquals(listOf(""), LogicalLineReader.splitLines("\r\n"))
        assertEquals(listOf(""), LogicalLineReader.splitLines("\r"))
        assertEquals(listOf("a"), LogicalLineReader.splitLines("a\r"))
        assertEquals(emptyList<String>(), LogicalLineReader.splitLines(""))
        // 裸 CR 紧跟 CRLF 时两者各算一个终止符，中间是空行
        assertEquals(listOf("a", "", "b"), LogicalLineReader.splitLines("a\r\r\nb"))
    }

    @Test
    fun `带 BOM 的 CRLF 文件里续行块与井号行照常识别`() {
        val text = "{\r\nPRINTL 1\r\n}\r\n\r\n; 注释\n#DIM X\r\nPRINTL 2"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        // spec: EncodingHandler.cs:19-26 —— 只读 3 字节判前导 EF BB BF；spec: Preload.cs:23-33 的行装载
        val lines = LogicalLineReader.readAllLines(bytes)
        assertEquals(listOf("{", "PRINTL 1", "}", "", "; 注释", "#DIM X", "PRINTL 2"), lines)
        assertTrue(lines.none { '\r' in it }, "拆行后不得残留 '\\r'")

        val r = LogicalLineReader(lines, "t.erb")
        // spec: EraStreamReader.cs:136-183 —— '{' 起块、逐行 Append、行间插入 ReplaceContinuationBR
        val merged = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, merged.kind)
        assertEquals("PRINTL 1 ", merged.content)
        // spec: ErbLoader.cs:389 —— ScriptPosition 取最后读到的物理行号（此处是 '}' 的 0 基行号）
        assertEquals(2, merged.lineNo)

        // 空行 / 整行注释被归约（spec: EraStreamReader.cs:107-110 / :124-129）
        val dim = r.readLogicalLine()!!
        assertEquals(LineKind.SHARP, dim.kind)
        assertEquals(5, dim.lineNo)
        assertEquals("#DIM X", dim.content)
        assertEquals("PRINTL 2", r.readLogicalLine()!!.content)
        assertNull(r.readLogicalLine())
    }

    // ------------------------------------------------------------------
    // 4. `{}` 续行 + `@""` 跨行
    // ------------------------------------------------------------------

    @Test
    fun `续行块把跨行的花括号内嵌拼进同一个 @ 串`() {
        val r = LogicalLineReader(listOf("{", "PRINTFORML @\"A{1+", "2}B\"", "}"), "t.erb")
        val line = r.readLogicalLine()!!
        // spec: EraStreamReader.cs:178-179 —— 行间连接符是 ReplaceContinuationBR 去掉引号后的一个空格
        assertEquals("PRINTFORML @\"A{1+ 2}B\" ", line.content)

        val toks = ErbLexer().tokenizeLine(line.content, line.lineNo)
        assertEquals(2, toks.size)
        val form = (toks[1] as Token.FormStr).form
        // spec: LexicalAnalyzer.cs:1123-1132 —— '{' 产 CurlyBraceSubWord，前后各留一段常量
        assertEquals(listOf("A", "B"), form.strs)
        assertEquals(1, form.subWords.size)
        val curly = form.subWords[0] as CurlyBraceSubWord
        assertEquals(3, curly.words.size)
        assertEquals(1L, (curly.words[0] as Token.Integer).value)
        assertEquals(OperatorCode.Plus, (curly.words[1] as Token.Operator).code)
        assertEquals(2L, (curly.words[2] as Token.Integer).value)
    }

    @Test
    fun `@ 串里的分号与右花括号是字面量`() {
        // spec: LexicalAnalyzer.cs:1083-1112 —— endWith=DoubleQuotation 时 ';' '}' 只当普通字符追加，
        // 不进 Analyse 的注释分支（:919-933）也不触发右括号结束（:871-876）
        val form = (tokensOf("PRINTFORML @\"a;b}c\"")[1] as Token.FormStr).form
        assertEquals(listOf("a;b}c"), form.strs)
        assertTrue(form.subWords.isEmpty())

        // 串外的半角分号照旧截断整行剩余部分
        assertEquals(2, tokensOf("PRINTFORML @\"a;b\" ; PRINTL 9").size)
    }

    @Test
    fun `花括号内嵌的空表达式 嵌套方括号 与 未闭合`() {
        // spec: LexicalAnalyzer.cs:871-876 —— endWith=RightCurlyBrace 时 '}' 直接结束，不产 Symbol
        val empty = (tokensOf("PRINTL @\"x{}y\"")[1] as Token.FormStr).form
        assertEquals(listOf("x", "y"), empty.strs)
        assertEquals(0, (empty.subWords[0] as CurlyBraceSubWord).words.size)

        // spec: LexicalAnalyzer.cs:798-831 —— 内嵌表达式里的 '[' ']' 仍走 nestBracketL 配对
        val nested = (tokensOf("PRINTL @\"{A[0]}\"")[1] as Token.FormStr).form
        val words = (nested.subWords[0] as CurlyBraceSubWord).words
        assertEquals(listOf("A", "[", "0", "]"), words.map { it.text })

        // spec: LexicalAnalyzer.cs:1128-1131 —— 到行尾仍未见到 '}' → NotFoundCorresponding("{" , "}")
        assertFailsWith<LexError> { tokensOf("PRINTL @\"{1") }
    }

    @Test
    fun `未闭合的百分号内嵌报 NotFoundCorresponding`() {
        // spec: LexicalAnalyzer.cs:1113-1122 —— '%' 起 Analyse(Percent)，回到 '%' 才算闭合
        val e = assertFailsWith<LexError> { tokensOf("PRINTL @\"a%b") }
        assertTrue(e.message!!.contains("'%'"))
    }

    @Test
    fun `三连记号展开与 SystemIgnoreTripleSymbol 开关`() {
        // spec: LexicalAnalyzer.cs:1133-1147 —— $$$ *** /// === +++ 各产一个 TripleSymbolSubWord
        val form = (tokensOf("PRINTL @\"+++ *** /// === \$\$\$\"")[1] as Token.FormStr).form
        assertEquals(listOf('+', '*', '/', '=', '$'), form.subWords.map { (it as TripleSymbolSubWord).code })
        // strs.size == subWords.size + 1：每产一个 SubWord 前先 add 一段常量，末尾再补尾段
        // （spec: LexicalAnalyzer.cs:1114-1147 / :1218）
        assertEquals(6, form.strs.size)

        // spec: ConfigData.cs:110 默认 false → 展开；置 true 后整段退化为普通常量
        val literal = (ErbLexer(LexerConfig(systemIgnoreTripleSymbol = true))
            .tokenizeLine("PRINTL @\"+++ ***\"", 0)[1] as Token.FormStr).form
        assertEquals(listOf("+++ ***"), literal.strs)
        assertTrue(literal.subWords.isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. `\@` 三项条件式
    // ------------------------------------------------------------------

    @Test
    fun `反斜杠三项式左右分支只 trim 半角空格与制表`() {
        // spec: LexicalAnalyzer.cs:1231-1258 + :1223-1227 —— TrimStart/TrimEnd 的字符表只有 ' ' 与 '\t'
        val form = (tokensOf("PRINTL @\"\\@ A ?  yes  #  no  \\@\"")[1] as Token.FormStr).form
        val yen = form.subWords[0] as YenAtSubWord
        assertEquals(1, yen.condition.size)
        assertEquals(listOf("yes"), yen.left.strs)
        assertEquals(listOf("no"), yen.right!!.strs)

        // 全角空格 U+3000 不在 trim 表里，原样保留（与系统开关 SystemAllowFullSpace 无关）
        val full = (tokensOf("PRINTL @\"\\@ A ? \u3000yes\u3000 # \u3000no\u3000 \\@\"")[1] as Token.FormStr).form
        val yen2 = full.subWords[0] as YenAtSubWord
        assertEquals(listOf("\u3000yes\u3000"), yen2.left.strs)
        assertEquals(listOf("\u3000no\u3000"), yen2.right!!.strs)
    }

    @Test
    fun `缺井号分支的三项式右支为 null 并告警`() {
        // spec: LexicalAnalyzer.cs:1240-1249 —— 左支读完当前是 '@' 而非 '#'：吞掉 '@'、告警、right 返回 null
        val lx = ErbLexer()
        val form = (lx.tokenizeLine("PRINTL @\"\\@ A ? yes\\@\"", 0)[1] as Token.FormStr).form
        val yen = form.subWords[0] as YenAtSubWord
        assertNull(yen.right)
        assertEquals(listOf("yes"), yen.left.strs)
        assertEquals(1, lx.warnings.size)
        assertEquals(1, lx.warnings[0].level)
        assertTrue(lx.warnings[0].message.contains("#"))
    }

    // ------------------------------------------------------------------
    // 6. 赋值行两段式（LogicalLineParser.cs:536-556）
    // ------------------------------------------------------------------

    @Test
    fun `赋值行先截到赋值符再读赋值运算符`() {
        // spec: LogicalLineParser.cs:536-556 —— Seek(0) 后先 Analyse(Operator, None) 截断，再 ReadAssignmentOperator
        fun dest(src: String, st: CharStream = CharStream(src)): List<String> {
            val toks = lexer.analyse(st, LexEndWith.Operator, LexAnalyzeFlag.NONE, 0)
            return toks.map { it.text }
        }

        val st1 = CharStream("A = 1")
        assertEquals(listOf("A"), dest("A = 1", st1))
        // spec: LexicalAnalyzer.cs:429-439 —— allowAssignment=false 时 '=' 是 Assignment（词法层放行）
        assertEquals(OperatorCode.Assignment, lexer.readAssignmentOperator(st1, 0))
        assertEquals(listOf("1"), lexer.analyse(st1, LexEndWith.EoL, LexAnalyzeFlag.NONE, 0).map { it.text })

        // '==' 到词法层仍是 Equal；降级为 Assignment 发生在 LogicalLineParser.cs:548-555（并告警）
        val st2 = CharStream("A == 1")
        dest("A == 1", st2)
        assertEquals(OperatorCode.Equal, lexer.readAssignmentOperator(st2, 0))

        // spec: LexicalAnalyzer.cs:844-870 的 st.Next=='=' 特例 + :511-620 —— '\''+'=' 是字符串赋值
        val st3 = CharStream("A '= \"s\"")
        assertEquals(listOf("A"), dest("A '= \"s\"", st3))
        assertEquals(OperatorCode.AssignmentStr, lexer.readAssignmentOperator(st3, 0))

        // 复合赋值与移位赋值（spec: LexicalAnalyzer.cs:511-620）
        assertEquals(OperatorCode.Plus, lexer.readAssignmentOperator(CharStream("+="), 0))
        assertEquals(OperatorCode.LeftShift, lexer.readAssignmentOperator(CharStream("<<="), 0))
        assertEquals(OperatorCode.RightShift, lexer.readAssignmentOperator(CharStream(">>="), 0))

        // 移位符缺 '=' → 报错（spec: LexicalAnalyzer.cs:255-268）
        assertFailsWith<LexError> { lexer.readAssignmentOperator(CharStream("<<"), 0) }
    }

    // ------------------------------------------------------------------
    // 7. 已登记分歧（按现状钉桩）
    // ------------------------------------------------------------------

    @Test
    fun `只含空白行的续行块不产逻辑行`() {
        // spec: EraStreamReader.cs:181-183 —— 合并后 SkipWhiteSpace 到 EOS 也照样把流交回，
        // ErbLoader 走 LogicalLineParser.ParseLine，后者 EOS 时 return null（LogicalLineParser.cs:387-391）
        // ⇒ 该块不产生任何逻辑行。
        // 2026-10-26 fixcore 波 deep-lexer 修正：此前直接 classify 会多吐一条 content='' 的 CODE 行。
        val r = LogicalLineReader(listOf("{", "   ", "\t", "}", "PRINTL 2"), "t.erb")
        assertEquals("PRINTL 2", r.readLogicalLine()!!.content)
        assertNull(r.readLogicalLine())
    }

    @Test
    fun `顶层双方法括号与孤立花括号美元符一律按 UnexpectedCharacter 报错`() {
        // spec: LexicalAnalyzer.cs:809-827 —— C# 的 '[[' 分四路：RenameDic==null 时 UnexpectedCharacter("[[")；
        // 非 null 时按 "]]" 缺失 / 空名 / 未命中三分，报 EmptyTwoSBrackets / MissingTwoSBrackets / CanNotRenameKey。
        // 移植版统一抛 UnexpectedCharacter 且实参只取一个 '['（ErbLexer 的 '[' 分支）。
        // TODO(verify): 文案实参（"[[" vs "["）与 CanNotRenameKey 三路未复刻；替换名在读行阶段已由
        // EraStreamReader.cs:111-122 的同款替换消掉，剩余未命中形态在语料里未逐条统计。
        assertFailsWith<LexError> { tokensOf("PRINTL [[X]]") }
        // spec: LexicalAnalyzer.cs:916-918 —— '$' 与 '{' 在 Analyse 顶层没有入口
        assertFailsWith<LexError> { tokensOf("PRINTL {1}") }
        assertFailsWith<LexError> { tokensOf("PRINTL \$X") }

        // 已登记替换名的物理行在读行阶段就被替换掉，词法层看不到 "[["
        // （spec: EraStreamReader.cs:111-122 的 regexRenameIdentifer 分支）
        val r = LogicalLineReader(listOf("PRINTL [[名]]"), "t.erb", renameDic = mapOf("[[名]]" to "VAL"))
        assertEquals("PRINTL VAL", r.readLogicalLine()!!.content)
    }
}
