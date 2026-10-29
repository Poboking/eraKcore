package com.eratw.port.core.stmt

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语句参数构造/校验缺口回归 —— 逐条对照 C# ArgumentParser 的 ArgumentBuilder：
 *
 * 1. INT_EXPRESSION 省略实参 → 补 SingleLongTerm(0) + level 1 告警，不再当「参数不足」
 *    （ArgumentParser.cs:1179-1208；`CLEARLINE` / `IF` / `LOOP` 的空参形态）；
 * 2. SP_INPUT 的省略位（null）合法，只拒非整型（ArgumentParser.cs:2400-2410）；
 * 3. SORTCHARA 排序键必须是角色数据变量（ArgumentParser.cs:588-592，level 2）；
 * 4. ARRAYSORT 目标必须是一维数组（ArgumentParser.cs:645-649，level 2）；
 * 5. VARSIZE 拒绝 RAND 与未登记变量（ArgumentParser.cs:528-538，level 2）。
 */
class StmtArgValidationTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("SCALAR", OperandType.INT, 0)
            .declareVariable("CFLAG", OperandType.INT, 1, isCharacterData = true)
            .declareVariable("CDFLAG", OperandType.INT, 2, isCharacterData = true)
        return LineParser(sym)
    }

    private fun logical(text: String, lineNo: Int = 10): LogicalLine =
        LogicalLine(LineKind.CODE, "stmt-arg-gap.erb", lineNo, text)

    private fun parse(text: String, lineNo: Int = 10): Statement? =
        parser().parseLogicalLine(logical(text, lineNo))

    @Test
    fun `CLEARLINE 省略实参补 0 并只告警`() {
        val p = parser()
        val stmt = assertIs<InstructionStmt>(p.parseLogicalLine(logical("CLEARLINE")))
        val arg = assertIs<ExpressionArgument>(stmt.args)
        assertEquals(IntLiteral(0L), arg.term, "省略的 INT_EXPRESSION 实参必须补 IntLiteral(0)")
        assertTrue(arg.isConst, "补出来的 0 是常量（原版 SingleLongTerm）")
        assertEquals(0L, arg.constInt)
        assertTrue(
            p.warnings.any { it.message.contains("参数被省略") },
            "非 nullable 的 INT_EXPRESSION 省略实参必须留 level 1 告警：${p.warnings}",
        )
    }

    @Test
    fun `IF 与 LOOP 省略实参同样补 0`() {
        val p = parser()
        val ifStmt = assertIs<IfStmt>(p.parseLogicalLine(logical("IF", 11)))
        assertEquals(IntLiteral(0L), ifStmt.condition)
        val loop = assertIs<LoopStmt>(p.parseLogicalLine(logical("LOOP", 12)))
        assertEquals(IntLiteral(0L), loop.condition)
    }

    @Test
    fun `INPUT 省略位允许 null`() {
        val stmt = assertIs<InstructionStmt>(parse("INPUT ,0"))
        val arg = assertIs<SpInputsArgument>(stmt.args)
        assertNull(arg.def, "第1位省略 = null（原版 list[0] 为 null 不判类型）")
        // 解析出的字面量带行列号，按值断言（IntLiteral 是 data class，行列入相等）。
        assertEquals(0L, (arg.mouse as IntLiteral).value)
        assertNull(arg.canSkip)
        // 非整型仍按 level 2 拒绝（ArgumentParser.cs:2403-2410）。
        val e = assertFailsWith<StmtParseException> { parse("INPUT 1, \"x\"") }
        assertTrue(e.message!!.contains("第2个参数的类型不正确"), e.message!!)
    }

    @Test
    fun `SORTCHARA 排序键必须是角色变量`() {
        // 合法：CFLAG 是角色数据变量（ArgumentParser.cs:588 只判 IsCharacterData）。
        val ok = assertIs<InstructionStmt>(parse("SORTCHARA CFLAG:0:7, FORWARD"))
        assertIs<SpSortcharaArgument>(ok.args)
        // 非法：普通数组不是角色数据变量。
        val e = assertFailsWith<StmtParseException> { parse("SORTCHARA ARR:0") }
        assertTrue(e.message!!.contains("第1个参数不是角色变量"), e.message!!)
    }

    @Test
    fun `ARRAYSORT 目标必须是一维数组`() {
        assertIs<InstructionStmt>(parse("ARRAYSORT ARR, FORWARD, 0, 5"))
        // 角色数据变量：字符下标之外还有自身维度（CDFLAG 为 2 维 ⇒ 共 3 个下标）。
        val twoDim = assertFailsWith<StmtParseException> { parse("ARRAYSORT CDFLAG:0:0:0") }
        assertTrue(twoDim.message!!.contains("第1个参数不是一维数组变量"), twoDim.message!!)
        val scalar = assertFailsWith<StmtParseException> { parse("ARRAYSORT SCALAR, FORWARD") }
        assertTrue(scalar.message!!.contains("第1个参数不是一维数组变量"), scalar.message!!)
    }

    @Test
    fun `VARSIZE 拒绝 RAND 与未登记变量`() {
        assertIs<InstructionStmt>(parse("VARSIZE ARR"))
        val rand = assertFailsWith<StmtParseException> { parse("VARSIZE RAND") }
        assertTrue(rand.message!!.contains("第1个参数不是数组变量"), rand.message!!)
        val scalar = assertFailsWith<StmtParseException> { parse("VARSIZE SCALAR") }
        assertTrue(scalar.message!!.contains("第1个参数不是数组变量"), scalar.message!!)
        val unknown = assertFailsWith<StmtParseException> { parse("VARSIZE NO_SUCH_VAR") }
        // 改钉（L11 文案收口）：原版此处是 Lang.Error.ArgIsNotVariable
        // （ArgumentParser.cs:529-533；Lang.cs:1942 / zhs:440），译文「第{0}参数必须为变量」；
        // ArgBuilders 的 fail 统一带「语句名 + 命令:」前缀（ArgumentBuilder.cs:32）。
        assertTrue(unknown.message!!.contains("VARSIZE命令:第1参数必须为变量"), unknown.message!!)
    }
}
