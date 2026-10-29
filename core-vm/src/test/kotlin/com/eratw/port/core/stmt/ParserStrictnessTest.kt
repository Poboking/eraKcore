package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **解析器收紧回归（A3）：G1 / G2 / G3 的「原版拒 / 原版收」成对用例。**
 *
 * 每条断言的期望值都以 `reference/emuera-cs` 为准，并在测试内标注行号：
 *
 * - **G1 形参必须是可赋值变量**：`ErbLoader.parseLabel` 用
 *   `ReduceArguments(..., isDefine:true)` 把形参解成 `VariableTerm`
 *   （spec: ErbLoader.cs:658-702），非 `VariableTerm` / CONST → `ArgCanOnlyAssignableVar`
 *   （spec: ErbLoader.cs:651-652），最终 `Warn(level 2, isError)`（spec: ErbLoader.cs:769）。
 *   省略形参位是 `CannotOmitFuncArg`（spec: ExpressionParser.cs:269-272）。
 * - **G2 私有变量表按定义粒度**：`FunctionLabelLine.privateVar` 是每个标签一份
 *   （spec: FunctionLabelLine.cs:15 + 98-117），同名多定义互不共享。
 * - **G3 VARI/VARS 登记私有变量**：`LogicalLineParser.cs:463-471`（VARI）与 `:500-508`（VARS）
 *   在 `ParseLine` 内即时 `AddPrivateVariable`，维度 = 长度个数、无 CONST。
 *
 * 语料实测（3,971 文件）G1 参数名、G2 私有名、G3 `VARI/VARS` 均 0 条会因此新增失败；
 * 唯一命中的 3 条参数名全在 `[SKIPSTART]` 内（见交付报告）。
 */
class ParserStrictnessTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("eratw-parser-strictness")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private fun write(name: String, content: String): Path {
        val p = dir.resolve(name)
        p.writeText(content)
        return p
    }

    private fun kindOf(text: String): LineKind = when {
        text.startsWith("@") -> LineKind.FUNCTION_LABEL
        text.startsWith("$") -> LineKind.GOTO_LABEL
        text.startsWith("#") -> LineKind.SHARP
        else -> LineKind.CODE
    }

    private fun parseLine(p: LineParser, path: Path, lineNo: Int, text: String): Statement? =
        p.parseLogicalLine(LogicalLine(kindOf(text), path.toString(), lineNo, text))

    private fun symbolParser(vararg names: String): LineParser {
        val sym = SymbolTable()
        for (n in names) sym.declareVariable(n, OperandType.INT, 1)
        return LineParser(sym)
    }

    private fun label(p: LineParser, text: String): FuncLabelStmt =
        assertIs<FuncLabelStmt>(p.parseLabelLine(text, 1, "GOLDEN_ORACLE.ERB"))

    // -----------------------------------------------------------------------
    // G1 —— 形参必须是可赋值变量
    // -----------------------------------------------------------------------

    @Test
    fun `G1 正例 普通形参是已声明变量`() {
        // 原版：ReduceArguments(isDefine:true) → VariableTerm（ErbLoader.cs:658-702）
        val f = label(symbolParser("A", "B"), "@F(A, B)")
        assertEquals(listOf("A", "B"), f.paramNames)
    }

    @Test
    fun `G1 正例 ARG 与 ARGS 内建变量`() {
        // ARG/ARGS 是 VariableCode.ARG/ARGS（ErbLoader.cs:703-713 对二者单独记 ArgLength/ArgsLength）
        assertEquals(listOf("ARG", "ARGS"), label(LineParser(SymbolTable()), "@F(ARG, ARGS)").paramNames)
        assertEquals(listOf("ARG"), label(LineParser(SymbolTable()), "@F(ARG:0)").paramNames)
    }

    @Test
    fun `G1 正例 函数私有 DIM 名作形参`() {
        // 原版 parseLabel 在 parentProcess.scaningLine = label 下解析（ErbLoader.cs:546-548），
        // 而 #DIM 已在第一遍登记到该标签的 privateVar（ErbLoader.cs:413-423 + FunctionLabelLine.cs:98-111）。
        val file = write(
            "PRIV_PARAM.ERB",
            """
            @F(PRIV_VAL, PRIV_ARR:1 = 3)
            #DIM DYNAMIC PRIV_VAL
            #DIM DYNAMIC PRIV_ARR, 10
            RETURN
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(file))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))
        val f = assertIs<FuncLabelStmt>(parseLine(parser, file, 0, "@F(PRIV_VAL, PRIV_ARR:1 = 3)"))
        assertEquals(listOf("PRIV_VAL", "PRIV_ARR"), f.paramNames)
    }

    @Test
    fun `G1 正例 无括号逗号形态`() {
        // 原版：symbolWord == ',' → ReduceArguments(EoL, isDefine:true)（ErbLoader.cs:659-661）
        val file = write(
            "COMMA_PARAM.ERB",
            """
            @F, ARG0, ARG1 = 2
            #DIM ARG0
            #DIM ARG1
            RETURN
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(file))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))
        val f = assertIs<FuncLabelStmt>(parseLine(parser, file, 0, "@F, ARG0, ARG1 = 2"))
        assertEquals(listOf("ARG0", "ARG1"), f.paramNames)
    }

    @Test
    fun `G1 反例 字符串字面量`() {
        // 原版：Restructure as VariableTerm == null → ArgCanOnlyAssignableVar（ErbLoader.cs:680-684）
        val e = assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F(\"ABC\")") }
        assertTrue(e.message!!.contains("可赋值变量"), e.message)
    }

    @Test
    fun `G1 反例 整数字面量`() {
        // 原版：LiteralIntegerWord → SingleTerm，不是 VariableTerm（ErbLoader.cs:680-684）
        val e = assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F(123)") }
        assertTrue(e.message!!.contains("可赋值变量"), e.message)
    }

    @Test
    fun `G1 反例 未声明标识符`() {
        // 原版：标识符解析期抛 CodeEE（IdentifierDictionary.cs:628），由 setLabelsArg 捕获后置错
        val e = assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F(UNDECLARED_X)") }
        assertTrue(e.message!!.contains("未定义的标识符"), e.message)
        assertTrue(e.message!!.contains("UNDECLARED_X"), e.message)
    }

    @Test
    fun `G1 反例 CONST 变量`() {
        // 原版：variableTerm.Identifier.IsConst → ArgCanOnlyAssignableVar（ErbLoader.cs:681-684）
        val sym = SymbolTable().declareVariable("K", OperandType.INT, 1, isConst = true)
        val e = assertFailsWith<StmtParseException> { label(LineParser(sym), "@F(K)") }
        assertTrue(e.message!!.contains("常量"), e.message)
    }

    @Test
    fun `G1 反例 省略形参位`() {
        // 原版：逗号后直接再来逗号 → ReduceExpressionTerm 返回 null
        // → terms.Last == null → CannotOmitFuncArg（ExpressionParser.cs:269-272）
        assertFailsWith<StmtParseException> { label(symbolParser("A", "B"), "@F(A,,B)") }
        assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F(,A)") }
        assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F(,)") }
    }

    @Test
    fun `G1 正例 末尾逗号不算缺参`() {
        // 原版 ReduceArguments 遇到 ',' 先 ShiftNext，再在循环顶撞上 ')' / EOL 直接返回
        // （spec: ExpressionParser.cs:247-252 / 296-299），因此 `@F(A,)` 不是 CannotOmitFuncArg。
        assertEquals(listOf("A"), label(symbolParser("A"), "@F(A,)").paramNames)
    }

    @Test
    fun `G1 反例 空格分隔的形参`() {
        // 原版：标签名后既不是 ',' 也不是 '(' → WrongArgFormat（ErbLoader.cs:654-668）
        assertFailsWith<StmtParseException> { label(symbolParser("A"), "@F A") }
    }

    // -----------------------------------------------------------------------
    // G2 —— 私有表按「定义」而非「函数名」
    // -----------------------------------------------------------------------

    @Test
    fun `G2 正例 同一定义内私有名可见`() {
        val f = write(
            "SAME_DEF.ERB",
            """
            @X
            #DIM ONLY_A
            ONLY_A = 1
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(f))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))
        assertIs<FuncLabelStmt>(parseLine(parser, f, 0, "@X"))
        assertNotNull(pre.funcVarDefs[CorpusValidator.FuncDefKey(norm(f), 0)])
        assertIs<AssignStmt>(parseLine(parser, f, 2, "ONLY_A = 1"))
    }

    @Test
    fun `G2 反例 跨文件同名定义不共享私有表`() {
        // 原版：A 文件的 @X 与 B 文件的 @X 是两个 FunctionLabelLine，
        // privateVar 各一份（FunctionLabelLine.cs:15 + 98-117）。
        val a = write(
            "A.ERB",
            """
            @X
            #DIM ONLY_A
            ONLY_A = 1
            """.trimIndent() + "\n",
        )
        val b = write(
            "B.ERB",
            """
            @X
            #DIM ONLY_B
            ONLY_B = 1
            ONLY_A = 2
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(a, b))
        // 旧视图（按函数名合并）里 ONLY_A 仍在，证明拒绝不是「预扫描没收到」
        assertTrue(pre.funcVars.getValue("X").containsKey("ONLY_A"))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))

        // A 的定义内合法
        parseLine(parser, a, 0, "@X")
        assertIs<AssignStmt>(parseLine(parser, a, 2, "ONLY_A = 1"))

        // B 的定义内：自己的私有名可见，A 的私有名不可见 → 原版 VarNotDefinedThisFunc
        // （IdentifierDictionary.cs:590-592）
        parseLine(parser, b, 0, "@X")
        assertIs<AssignStmt>(parseLine(parser, b, 2, "ONLY_B = 1"))
        val e = assertFailsWith<StmtParseException> { parseLine(parser, b, 3, "ONLY_A = 2") }
        assertTrue(e.message!!.contains("未定义的标识符"), e.message)
    }

    @Test
    fun `G2 反例 同文件同名定义也不共享`() {
        // 定义身份 = 文件 + 标签行号，不是「文件 + 函数名」
        val f = write(
            "TWO_DEFS.ERB",
            """
            @X
            #DIM ONLY_A
            ONLY_A = 1
            @X
            #DIM ONLY_B
            ONLY_B = 1
            ONLY_A = 2
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(f))
        val parser = LineParser(CorpusValidator.ScopedResolver(pre))
        parseLine(parser, f, 0, "@X")
        assertIs<AssignStmt>(parseLine(parser, f, 2, "ONLY_A = 1"))
        parseLine(parser, f, 3, "@X")
        assertIs<AssignStmt>(parseLine(parser, f, 5, "ONLY_B = 1"))
        assertFailsWith<StmtParseException> { parseLine(parser, f, 6, "ONLY_A = 2") }
    }

    // -----------------------------------------------------------------------
    // G3 —— VARI / VARS 登记私有变量
    // -----------------------------------------------------------------------

    @Test
    fun `G3 VARI 登记维度与类型 且仅本定义可见`() {
        val v = write(
            "VARI_DEF.ERB",
            """
            @F
            VARI PRIV_X, 10
            PRIV_X = 1
            VARI PRIV_W, 10, 20
            @G
            PRIV_X = 2
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(v))
        val resolver = CorpusValidator.ScopedResolver(pre)
        val parser = LineParser(resolver)

        parseLine(parser, v, 0, "@F")
        // 解析到 VARI 之前不可见（原版登记点是 ParseLine 内即时 AddPrivateVariable）
        assertNull(resolver.resolveVariable("PRIV_X", null))

        val decl = assertIs<VarDeclStmt>(parseLine(parser, v, 1, "VARI PRIV_X, 10"))
        assertEquals(FunctionCode.VARI, decl.code)
        val info = assertNotNull(resolver.resolveVariable("PRIV_X", null))
        // 原版：Dimension = list2.Count（LogicalLineParser.cs:463-470），TypeIsStr = false
        assertEquals(1, info.dimension)
        assertEquals(OperandType.INT, info.operandType)
        assertFalse(info.isConst, "VARI 没有 CONST 语义（LogicalLineParser.cs:463-470）")

        // 同一函数体内后续行可见
        assertIs<AssignStmt>(parseLine(parser, v, 2, "PRIV_X = 1"))

        // 多维：Dimension = 逗号后的长度个数
        assertIs<VarDeclStmt>(parseLine(parser, v, 3, "VARI PRIV_W, 10, 20"))
        assertEquals(2, assertNotNull(resolver.resolveVariable("PRIV_W", null)).dimension)

        // 另一个定义不可见（私有表按定义隔离，FunctionLabelLine.cs:15/98-117）
        parseLine(parser, v, 4, "@G")
        val e = assertFailsWith<StmtParseException> { parseLine(parser, v, 5, "PRIV_X = 2") }
        assertTrue(e.message!!.contains("未定义的标识符"), e.message)
    }

    @Test
    fun `G3 VARS 登记字符串类型`() {
        // 注意：用例刻意不使用 `PRIV_S:0` —— prescan 的「带下标使用补登记」启发式
        // （CorpusValidator.kt:348-383）会把任何 `名:下标` 补登成**全局**变量，
        // 那是既有宽松面（TODO(verify): 正式符号层替换），与本条要验的 VARI/VARS 登记无关。
        val v = write(
            "VARS_DEF.ERB",
            """
            @F
            VARS PRIV_S, 10
            PRIV_S = "abc"
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(v))
        val resolver = CorpusValidator.ScopedResolver(pre)
        val parser = LineParser(resolver)

        parseLine(parser, v, 0, "@F")
        assertNull(resolver.resolveVariable("PRIV_S", null))
        assertIs<VarDeclStmt>(parseLine(parser, v, 1, "VARS PRIV_S, 10"))
        val info = assertNotNull(resolver.resolveVariable("PRIV_S", null))
        assertEquals(1, info.dimension)
        assertEquals(OperandType.STR, info.operandType)
        assertFalse(info.isConst)
        assertIs<AssignStmt>(parseLine(parser, v, 2, "PRIV_S = \"abc\""))
    }

    @Test
    fun `G3 VARI 裸声明维度为 1`() {
        // 原版：数组省略尺寸时 list = [1]，Dimension = 1（UserDefinedVariableData.cs:291-299）
        val v = write(
            "VARI_BARE.ERB",
            """
            @F
            VARI PRIV_BARE
            """.trimIndent() + "\n",
        )
        val pre = CorpusValidator.prescan(listOf(v))
        val resolver = CorpusValidator.ScopedResolver(pre)
        val parser = LineParser(resolver)
        parseLine(parser, v, 0, "@F")
        assertIs<VarDeclStmt>(parseLine(parser, v, 1, "VARI PRIV_BARE"))
        assertEquals(1, assertNotNull(resolver.resolveVariable("PRIV_BARE", null)).dimension)
    }

    /** 规范化路径（与 [CorpusValidator.prescan] 的 FuncDefKey 同口径）。 */
    private fun norm(p: Path): String = p.toAbsolutePath().normalize().toString().replace('\\', '/')
}
