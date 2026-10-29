package com.eratw.port.core.stmt

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `{}` 续行块里的 `#DIM/#DIMS` 声明（语料实形 `魔改内容/PRINT系/Print_&_Input.ERB:24-28`）。
 *
 * 原版把 `{` 独占一行的块内的**全部物理行拼成一条逻辑行**再交给
 * `LogicalLineParser.ParseSharpLine`（spec: EraStreamReader.cs:136-184 → ErbLoader.cs:387-424），
 * 因此块内的 `#DIM` 与单行 `#DIM` 走**同一条**声明路径；唯一的物理差异是行间插入
 * `ReplaceContinuationBR`（去引号后 = 一个空格，spec: EraStreamReader.cs:179）。
 *
 * 行号口径（与常见直觉相反，按原版）：逻辑行行号 = **闭合 `}` 所在物理行**。
 * `EraStreamReader.ReadLine` 每读一行推进 `curNo`（spec: EraStreamReader.cs:77-87），
 * 块循环读完 `}` 后才返回（:143-180），调用方随即用
 * `new ScriptPosition(Filename, LineNo)` 取位置（spec: ErbLoader.cs:389）。
 */
class BlockDimDeclarationTest {

    private fun readerOf(vararg lines: String): LogicalLineReader =
        LogicalLineReader(lines.toList(), "Print_&_Input.ERB")

    private val parser = LineParser()

    private fun sharpOf(raw: String): SharpStmt =
        assertIsSharp(parser.parseSharpLine(raw, 0))

    private fun assertIsSharp(stmt: Statement?): SharpStmt {
        val s = assertNotNull(stmt as? SharpStmt, "# 行应解析成 SharpStmt，实得 $stmt")
        return s
    }

    // ---------------------------------------------------------------
    // 1. 块内 #DIM → 一条 SHARP 逻辑行
    // ---------------------------------------------------------------

    @Test
    fun `块内 DIM 合并成一条 SHARP 逻辑行且行号取闭合括号行`() {
        val r = readerOf(
            "@PRINT_ALPHABET(RANK, RANK_NUM, ARG)", // 0
            "#DIMS CONST STR_RANK10, 3 = \"Ex\", \"SS\", \" S\"", // 1
            "{", // 2
            "#DIM  CONST C_RANK10, 3 =", // 3
            "\t C_P_PURPLE,  C_WHITE,  C_WHITE", // 4
            "}", // 5
            "#DIM  CONST C_RANK7,   2 =  C_P_PURPLE,  C_WHITE", // 6
        )
        val label = r.readLogicalLine()!!
        assertEquals(LineKind.FUNCTION_LABEL, label.kind)
        assertEquals(0, label.lineNo)

        val single = r.readLogicalLine()!!
        assertEquals(LineKind.SHARP, single.kind)
        assertEquals(1, single.lineNo)

        val block = r.readLogicalLine()!!
        assertEquals(LineKind.SHARP, block.kind, "块内 #DIM 的首字符仍是 '#'，必须按 SHARP 分类")
        assertEquals(5, block.lineNo, "逻辑行行号 = 闭合 '}' 所在物理行（EraStreamReader.cs:143-180 + ErbLoader.cs:389）")
        // 块内两行按原版拼成一条：行间插入一个空格（EraStreamReader.cs:179），行首空白保留到词法层
        assertEquals(
            "#DIM  CONST C_RANK10, 3 = \t C_P_PURPLE,  C_WHITE,  C_WHITE ",
            block.content,
        )

        val next = r.readLogicalLine()!!
        assertEquals(6, next.lineNo, "块后的下一物理行不得被块吞掉")
        assertEquals("#DIM  CONST C_RANK7,   2 =  C_P_PURPLE,  C_WHITE", next.content)
    }

    @Test
    fun `块内 DIM 的 SharpStmt 名字是变量名而不是修饰符`() {
        val r = readerOf("{", "#DIMS CONST STR_RANK10, 3 = \"Ex\", \"SS\", \" S\"", "}")
        val line = r.readLogicalLine()!!
        val sharp = assertIsSharp(parser.parseLogicalLine(line))
        assertEquals("DIMS", sharp.keyword.uppercase().removePrefix("#"))
        // 原版 Name 是「关键字循环之后的首个标识符」（spec: UserDefinedVariableData.cs:54-57 / :262-263）
        assertEquals("STR_RANK10", sharp.name)
        assertTrue(sharp.isString)
        assertTrue(sharp.raw.startsWith("#DIMS CONST STR_RANK10"))
    }

    // ---------------------------------------------------------------
    // 2. #DIMS / #DIM REF 同路径
    // ---------------------------------------------------------------

    @Test
    fun `块内 DIMS 声明可达声明层`() {
        val r = readerOf("{", "#DIMS CONST NAMES, 2 = \"a\", \"b\"", "}")
        val sharp = assertIsSharp(parser.parseLogicalLine(r.readLogicalLine()!!))
        assertEquals("NAMES", sharp.name)
        val split = assertNotNull(SystemVariableBootstrap.splitDeclaration(sharp.raw.trim()))
        val spec = DimParser.parse(split.first, split.second, isPrivate = true)
        assertTrue(spec.isStr)
        assertTrue(spec.isConst)
        assertEquals(listOf(2), spec.dims.toList())
        assertEquals(listOf("a", "b"), spec.defaultsStr)
    }

    @Test
    fun `块内 DIM REF 声明多维零尺寸`() {
        val r = readerOf("{", "#DIM REF RR,,", "}")
        val sharp = assertIsSharp(parser.parseLogicalLine(r.readLogicalLine()!!))
        assertEquals("RR", sharp.name, "REF 是修饰符，名字取其后首个标识符")
        val split = assertNotNull(SystemVariableBootstrap.splitDeclaration(sharp.raw.trim()))
        val spec = DimParser.parse(split.first, split.second, isPrivate = true)
        assertTrue(spec.isRef)
        assertFalse(spec.isStatic, "REF 隐含 static=false（spec: UserDefinedVariableData.cs:115-116）")
        assertEquals(listOf(0, 0), spec.dims.toList(), "每个逗号一维 0（spec: UserDefinedVariableData.cs:309-320）")
    }

    // ---------------------------------------------------------------
    // 3. 块内普通语句
    // ---------------------------------------------------------------

    @Test
    fun `块内普通语句与 FORM 花括号一起合并`() {
        val r = readerOf("{", "PRINTFORML {A}", "\tIF A > 0", "}")
        val line = r.readLogicalLine()!!
        assertEquals(LineKind.CODE, line.kind)
        assertEquals(3, line.lineNo)
        // FORM 里的 '{' 不是续行记号：原版只把「整行仅 { 」当块开始（spec: EraStreamReader.cs:136-141）
        assertEquals("PRINTFORML {A} \tIF A > 0 ", line.content)
    }

    // ---------------------------------------------------------------
    // 4. 块结构错误与行号
    // ---------------------------------------------------------------

    @Test
    fun `块内再出现独占一行的左花括号是错误`() {
        val e = assertFailsWith<LexError> { readerOf("{", "{", "}").readLogicalLine() }
        assertEquals(1, e.lineNo, "错误位置 = 出问题的物理行（spec: EraStreamReader.cs:173-176）")
    }

    @Test
    fun `块内右花括号带尾随内容是错误`() {
        val e = assertFailsWith<LexError> { readerOf("{", "abc", "} x").readLogicalLine() }
        assertEquals(2, e.lineNo, "spec: EraStreamReader.cs:165-172")
    }

    @Test
    fun `顶层孤立的右花括号是错误`() {
        val r = readerOf("PRINTL 1", "}")
        r.readLogicalLine() // 第一条语句正常
        val e = assertFailsWith<LexError> { r.readLogicalLine() }
        assertEquals(1, e.lineNo, "spec: EraStreamReader.cs:132-135")
    }

    @Test
    fun `块内未闭合时错误行号停在文件末行之后`() {
        val e = assertFailsWith<LexError> { readerOf("{", "#DIM X, 1").readLogicalLine() }
        // 原版 ReadLine 先 `curNo = nextNo` 再判越界（spec: EraStreamReader.cs:80-85），
        // 读空时 curNo 已推到「末行 + 1」，报错位置即该值（:146-149）。
        assertEquals(2, e.lineNo, "spec: EraStreamReader.cs:146-149")
    }

    @Test
    fun `块结束后普通语句的错误行号指向自己的物理行`() {
        val r = readerOf("{", "#DIM X, 1", "}", "?bad")
        assertEquals(2, r.readLogicalLine()!!.lineNo)
        val e = assertFailsWith<StmtParseException> { parser.parseLogicalLine(r.readLogicalLine()!!) }
        assertEquals(3, e.line, "块外行号不受块影响")
    }

    // ---------------------------------------------------------------
    // 5. #DIM 元数据：name 跳过修饰符
    // ---------------------------------------------------------------

    @Test
    fun `DIM 名字跳过全部七个修饰符`() {
        // 名字刻意不用单字母：A..Z 在原版 nameDic 里是 SystemVariable，
        // `#DIM X` 会被 CORE-06 拒绝（IdentifierDictionary.cs:258-297）。
        assertEquals("XX", sharpOf("#DIM CONST XX, 3 = 1, 2, 3").name)
        assertEquals("XX", sharpOf("#DIMS CONST XX, 3 = \"a\", \"b\", \"c\"").name)
        assertEquals("YY", sharpOf("#DIM DYNAMIC YY").name)
        assertEquals("YY", sharpOf("#DIM STATIC YY").name)
        assertEquals("ZZ", sharpOf("#DIM REF ZZ, 0").name)
        assertEquals("WW", sharpOf("#DIM GLOBAL SAVEDATA WW, 2").name)
        assertEquals("CC", sharpOf("#DIM CHARADATA CC, 2").name)
        assertEquals("PLAIN", sharpOf("#DIM PLAIN").name)
    }

    // ---------------------------------------------------------------
    // 6. 真实形态：初值引用 0x 定义的全局 CONST
    // ---------------------------------------------------------------

    @Test
    fun `块内 DIM CONST 引用十六进制全局常量时按折叠表注册`() {
        // 语料实形：DIM.ERH:589/595/618 都是 `#DIM CONST X = 0x......`
        val store = VariableStore()
        val install = SystemVariableBootstrap.install(
            store,
            listOf(
                SystemVariableBootstrap.RawDeclaration("#DIM CONST C_P_PURPLE = 0xC070C0", "DIM.ERH", 588),
                SystemVariableBootstrap.RawDeclaration("#DIM CONST C_WHITE    = 0xC0C0C0", "DIM.ERH", 595),
            ),
            DimParseConfig(),
        )
        assertEquals(2, install.declared)
        assertEquals(0xC070C0L, install.constants["C_P_PURPLE"])

        val raw = "#DIM  CONST C_RANK10, 3 =\t C_P_PURPLE,  C_WHITE,  C_WHITE "
        val split = assertNotNull(SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw.trim())))

        // 无折叠表 → 声明整条丢（原版不会，因为 ConstantData 此时已有 C_P_PURPLE）
        assertFailsWith<CodeEE> {
            DimParser.parse(split.first, split.second, isPrivate = true, config = DimParseConfig())
        }

        val spec = DimParser.parse(
            split.first, split.second, isPrivate = true,
            config = DimParseConfig(intConstants = install.constants),
        )
        assertTrue(spec.isConst)
        assertEquals(listOf(3), spec.dims.toList())
        assertEquals(listOf(0xC070C0L, 0xC0C0C0L, 0xC0C0C0L), spec.defaultsInt?.toList())

        store.declareUserVar(spec, "PRINT_ALPHABET")
        val registered = assertNotNull(store.userVar("C_RANK10", "PRINT_ALPHABET"))
        assertEquals(0xC070C0L, registered.readInt(0, 0))
        assertEquals(0xC0C0C0L, registered.readInt(0, 1))
        // 私有作用域隔离：全局表里不该出现
        assertNull(store.userVar("C_RANK10", null))
    }
}
