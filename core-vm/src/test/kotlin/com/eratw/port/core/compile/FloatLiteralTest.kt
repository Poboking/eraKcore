package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FloatLiteral
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.TokenType
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 4.981 浮点升版（W1 浮点波次）的定向用例：词法 / 表达式类型 / 求值 / 字节码往返。
 *
 * 语料实证：
 * - `COMMON.ERB:605` `回復量 = TOINT(回復量 * 0.7)`（同文件 606/608/617/618/620/622 同形）；
 * - `DLC/魔法DLC/使用魔法.ERB:1248-1256` `LOCALF = 0.0` / `LOCALF += … * 0.01` / `TOINT(LOCALF)`
 *   （`LOCALF` 变量族本波未落地，见交付报告的 TODO）；
 * - 4,229 处 / 548 文件的小数字面量全部是 `digits.digits` 形态（无 `.5`、无 `1.`、无 `1.5e3`）。
 *
 * 装置与 `CaseRangeAndStepTest` 同源：真 [LineParser] + 真 [ErbProgramCompiler] + 真
 * [VariableStore] + 真 [ProcessRuntime]，变量一律用内建裸字母 A..Z（1 维整型系统变量）。
 */
class FloatLiteralTest {

    private val lexer = ErbLexer()

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun tokensOf(text: String): List<Token> = lexer.tokenizeLine(text, 0)

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "float.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parse(*lines), setOf("T"))
        return c.build("T")
    }

    private fun run(program: Program): VariableStore {
        val store = VariableStore()
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        return store
    }

    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    /** 在表达式树上递归找第一个满足条件的结点（表达式池只放根，子结点要自己下钻）。 */
    private fun findExpr(e: Expr, pred: (Expr) -> Boolean): Expr? {
        if (pred(e)) return e
        val kids: List<Expr> = when (e) {
            is Binary -> listOfNotNull(e.left, e.right)
            is Unary -> listOf(e.operand)
            is FuncCall -> e.args.filterNotNull()
            is VarRef -> e.indices
            else -> emptyList()
        }
        for (k in kids) findExpr(k, pred)?.let { return it }
        return null
    }

    /** 单条赋值语句的右值项（`A = <expr>` 的 `<expr>`）。 */
    private fun assignTerm(text: String): Expr {
        val stmt = parse("@T", text).filterIsInstance<AssignStmt>().single()
        return (stmt.args as SpSetArgument).term
            ?: error("`$text` 的右值被折成了常量形态，用例需要表达式节点")
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    /** `A = 0.7` → 小数 token（此前报「不该出现的符号」）。 */
    @Test
    fun `小数字面量切成浮点 token`() {
        val t = tokensOf("A = 0.7")
        assertEquals(3, t.size, "A / = / 0.7 三个 token，实得 ${t.map { it.type }}")
        val f = assertIs<Token.Float>(t[2])
        assertEquals(0.7, f.value)
        assertEquals("0.7", f.raw)
        assertEquals(TokenType.FLOAT, t[2].type)
    }

    /** 多位数整数部分 + 小数部分：`10.5`。 */
    @Test
    fun `多位数整数部分的小数`() {
        val f = assertIs<Token.Float>(tokensOf("A = 10.5")[2])
        assertEquals(10.5, f.value)
        assertEquals("10.5", f.raw)
    }

    /** `1e5` 仍是整数（readInt64 的 e/E 指数路径，spec: LexicalAnalyzer.cs:102-143）。 */
    @Test
    fun `整数指数形态仍走整数路径`() {
        val i = assertIs<Token.Integer>(tokensOf("A = 1e5")[2])
        assertEquals(100000L, i.value)
    }

    /** `1.修改了地图外观`（DLC/GETINFODLC.ERB:318 一类的字符串实参）不得被误判成浮点。 */
    @Test
    fun `点号后非数字不算小数字面量`() {
        val t = tokensOf("PRINTL 1.修改了地图外观")
        assertEquals(1L, assertIs<Token.Integer>(t[1]).value, "整数部分照旧是 1")
        assertTrue(
            t.none { it.type == TokenType.FLOAT },
            "`.` 后不是 ASCII 数字 ⇒ 整条不是浮点，实得 ${t.map { it.type }}",
        )
    }

    /** `0x1F` / `0b101` 不受小数判据影响。 */
    @Test
    fun `非十进制前缀不受影响`() {
        assertEquals(31L, assertIs<Token.Integer>(tokensOf("A = 0x1F")[2]).value)
        assertEquals(5L, assertIs<Token.Integer>(tokensOf("A = 0b101")[2]).value)
    }

    // ------------------------------------------------------------------
    // 表达式类型（解析期不变量）
    // ------------------------------------------------------------------

    /**
     * `A * 0.7` 是浮点结点（parser 的类型提升表）。
     *
     * 注意不能写成 `A = A * 0.7`：浮点右值赋给整数变量在解析期就会被拒（ArgBuilders.set
     * 只认 INT/STR 两种右值），这里借 `TOINT(…)` 的实参位拿到同一个结点。
     */
    @Test
    fun `整数乘浮点的结点类型是FLOAT`() {
        val call = assertIs<FuncCall>(assignTerm("A = TOINT(A * 0.7)"))
        assertEquals("TOINT", call.name)
        val e = assertIs<Binary>(call.args.single())
        assertEquals(OperatorCode.MULT, e.op)
        assertEquals(OperandType.FLOAT, e.operandType)
        assertEquals(0.7, assertIs<FloatLiteral>(e.right).value)
    }

    /** 比较运算的**结果**仍是整型（`IF LOCALW > 0.5` 的形态）。 */
    @Test
    fun `浮点比较的结点类型是INT`() {
        val e = assertIs<Binary>(assignTerm("A = A > 0.5"))
        assertEquals(OperatorCode.GREATER, e.op)
        assertEquals(OperandType.INT, e.operandType, "比较结果必须能进整型上下文")
        assertEquals(OperandType.FLOAT, e.right.operandType)
    }

    /** 一元负号的浮点操作数保持浮点。 */
    @Test
    fun `一元负号保持浮点类型`() {
        val e = assertIs<Binary>(assignTerm("A = A > -0.5"))
        val u = assertIs<Unary>(e.right)
        assertEquals(OperatorCode.MINUS, u.op)
        assertEquals(OperandType.FLOAT, u.operandType)
    }

    // ------------------------------------------------------------------
    // 求值（COMMON.ERB:605 与 IF LOCALW > 0.5 的最小复刻）
    // ------------------------------------------------------------------

    /** `回復量 = TOINT(回復量 * 0.7)`：10 * 0.7 = 7.0 → TOINT → 7（spec: COMMON.ERB:605）。 */
    @Test
    fun `TOINT截断乘法结果`() {
        val store = run(compile("@T", "A = 10", "A = TOINT(A * 0.7)"))
        assertEquals(7L, intOf(store, "A"), "10 * 0.7 = 7.0，TOINT 后应为 7")
    }

    /** `TOINT` 的浮点实参向零截断（C# `(long)double` 口径）。 */
    @Test
    fun `TOINT向零截断`() {
        val store = run(
            compile(
                "@T",
                "A = TOINT(2.5)",
                "B = TOINT(-2.5)",
                "C = TOINT(7.9)",
                "D = TOINT(\"7.9\")",
            ),
        )
        assertEquals(2L, intOf(store, "A"), "2.5 → 2（向零）")
        assertEquals(-2L, intOf(store, "B"), "-2.5 → -2（向零）")
        assertEquals(7L, intOf(store, "C"), "7.9 → 7")
        assertEquals(7L, intOf(store, "D"), "字符串路径（FunctionMethodCreator.cs:5500-5557）不受影响")
    }

    /** `IF LOCALW > 0.5` 形态：1 > 0.5 成立、0 > 0.5 不成立。 */
    @Test
    fun `浮点比较进IF条件`() {
        val hit = run(compile("@T", "A = 1", "IF A > 0.5", "J = 1", "ENDIF"))
        assertEquals(1L, intOf(hit, "J"), "1 > 0.5 成立")
        val miss = run(compile("@T", "A = 0", "IF A > 0.5", "J = 1", "ENDIF"))
        assertEquals(0L, intOf(miss, "J"), "0 > 0.5 不成立")
    }

    /** `TOFLOAT` 返回浮点（本作自选的最小实现，见 PureMethods.kt 的 TODO(verify)）。 */
    @Test
    fun `TOFLOAT返回浮点结点`() {
        val e = assertIs<Binary>(assignTerm("A = TOFLOAT(3) > 0.5"))
        assertEquals(OperandType.INT, e.operandType)
        assertEquals(OperandType.FLOAT, e.left.operandType, "TOFLOAT 的返回类型必须是 FLOAT")
        val store = run(compile("@T", "J = TOFLOAT(3) > 0.5", "K = TOFLOAT(3) > 2.5"))
        assertEquals(1L, intOf(store, "J"))
        assertEquals(1L, intOf(store, "K"))
    }

    // ------------------------------------------------------------------
    // 字节码往返（ExprTag.FLOAT_LITERAL=12 / WireEnum.OPERAND_FLOAT=2）
    // ------------------------------------------------------------------

    /**
     * 含浮点字面量的函数编成 .erbc 再读回：行为与首份程序逐值一致，且再编码字节逐字相同。
     * 追加新标签属**兼容变更**、不必升容器 version（specs/erbc-format.md §8.4）。
     */
    @Test
    fun `浮点字面量字节码往返`() {
        val before = compile("@T", "A = 10", "A = TOINT(A * 0.7)")
        val bytes = ErbcWriter.encode(before)
        val after = ErbcReader.decode(bytes)
        // 先做字节比对再 run：ProcessRuntime 构造期会释放语句原文（MEM-OPT），
        // 跑过之后语句 raw 变空串，再编码必然比首份小（与浮点无关）。
        assertContentEquals(bytes, ErbcWriter.encode(after), "读写两两自证：再编码应逐字相同")
        val floatNodes = after.statements().filterIsInstance<AssignStmt>().mapNotNull { st ->
            findExpr((st.args as SpSetArgument).term ?: return@mapNotNull null) { e ->
                e is FloatLiteral && e.value == 0.7
            }
        }
        assertNotNull(
            floatNodes.firstOrNull(),
            "读回后的语句池里仍应有 0.7 的浮点结点（ExprTag.FLOAT_LITERAL + f64 往返）",
        )
        val floatBinaries = after.statements().filterIsInstance<AssignStmt>().mapNotNull { st ->
            findExpr((st.args as SpSetArgument).term ?: return@mapNotNull null) { e ->
                e is Binary && e.operandType == OperandType.FLOAT
            }
        }
        assertNotNull(floatBinaries.firstOrNull(), "读回后的 `A * 0.7` 仍是 FLOAT 型结点（OPERAND_FLOAT 解码）")
        assertEquals(7L, intOf(run(after), "A"), "读回后的程序仍算出 7")
    }

    /**
     * 浮点字面量的 `line` / `col` 经容器**逐字**往返（specs/erbc-format.md §5.3:196-197）：
     * 它们是 data class 的组成字段，丢了会让「结构相等去重」把只差行列号的两条表达式
     * 错误合并。这里用**非默认**行列号（7,9）把往返钉在容器层的写/读两侧。
     */
    @Test
    fun `浮点字面量行列经容器逐字往返`() {
        val p = Assembler.assemble("_EVAL_INT \$0, R0\n_HALT", listOf(FloatLiteral(0.7, 7, 9)))
        val e0 = p.expr(0)
        assertIs<FloatLiteral>(e0)
        assertEquals(7, e0.line, "写侧入口的行号必须原样")
        assertEquals(9, e0.col, "写侧入口的列号必须原样")

        val bytes = ErbcWriter.encode(p)
        val after = ErbcReader.decode(bytes)
        assertEquals(e0, after.expr(0), "浮点字面量的 (value,line,col) 必须逐字段相等")
        assertEquals(7, after.expr(0).line, "line 不得被合并/清零")
        assertEquals(9, after.expr(0).col, "col 不得被合并/清零")
        assertContentEquals(bytes, ErbcWriter.encode(after), "再编码逐字节相同")
    }
}
