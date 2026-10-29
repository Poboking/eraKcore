package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * deep-stmt-args 深审回归：`ArgBuilders` 实参构建 / `ArgInterner` 驻留语义边界。
 *
 * 逐条对照 C# `ArgumentParser.cs`（`reference/emuera-cs`），并行号写进每个用例的注释；
 * 语料可达性一并注明（下表读数为 2026-10-26 对 `ERATW_CORPUS` 实测）：
 * - `ARRAYSORT` 单目标形态：语料 11 处全部带 `, FORWARD|BACK` ⇒ **不可达**（对齐性修复）；
 * - `SAVEVAR` 重复变量：语料 **0 处**；
 * - `VARSET` 第 3 参之后 / `CVARSET` 第 4 参之后：语料目标全是 1D ⇒ **不可达**；
 * - `CLEARLINE 0` / `FONTSTYLE -1` / `REPEAT 0`：语料 0 处（常量域实测无 <=0 / <0）。
 */
class DeepStmtArgsAuditTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("SCALAR", OperandType.INT, 0)
            .declareVariable("CNAME", OperandType.STR, 0, isCharacterData = true)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 10, p: LineParser = parser()): Statement? =
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "deep-stmt-args.erb", lineNo, text))

    // -----------------------------------------------------------------------
    // ArgBuilders
    // -----------------------------------------------------------------------

    @Test
    fun `ARRAYSORT 单目标被接受（原版 num1=0 num2=null）`() {
        // spec: ArgumentParser.cs:650-703 —— 无顶层逗号时 ReduceExpressionTerm 停在行尾、
        // ShiftNext 无动作、Current 非 IdentifierWord ⇒ 跳过方向/数字两支，直接构造：
        // order 保持 ASCENDING、num1 保持 SingleLongTerm(0)、num2 为 null。
        val stmt = assertIs<InstructionStmt>(parse("ARRAYSORT ARR"))
        val arg = assertIs<SpArraySortArgument>(stmt.args)
        assertEquals(SortOrder.ASCENDING, arg.order)
        assertEquals(IntLiteral(0L), arg.num1)
        assertNull(arg.num2)
        // 对照组：方向词仍生效（确认没有把 `, BACK` 分支改坏）。
        val back = assertIs<SpArraySortArgument>(assertIs<InstructionStmt>(parse("ARRAYSORT ARR, BACK")).args)
        assertEquals(SortOrder.DESENDING, back.order)
    }

    @Test
    fun `SAVEVAR 重复变量判错（原版 level1 告警后 return null）`() {
        // spec: ArgumentParser.cs:2214-2223 —— DuplicateVarSave 是 level 1 告警，但紧接
        // `return null` ⇒ SetArgumentTo 走 error()（:2708-2714）⇒ 该行判错、不执行。
        val p = parser()
        assertIs<InstructionStmt>(parse("SAVEVAR \"a\", \"b\", ARR", 10, p))
        val e = assertFailsWith<StmtParseException> { parse("SAVEVAR \"a\", \"b\", ARR, ARR", 11, p) }
        assertTrue(e.message!!.contains("试图多次保存变量"), e.message!!)
    }

    @Test
    fun `VARSET 第3参之后的非一维目标留 level1 告警`() {
        // spec: ArgumentParser.cs:1809-1812。
        val p = parser()
        assertIs<InstructionStmt>(parse("VARSET SCALAR, 0, 1, 5", 10, p))
        assertTrue(
            p.warnings.any { it.message.contains("第3参数后面除了一维数组以外的参数都会被忽略") },
            "非一维目标 + 第3参必须留 level1 告警：${p.warnings.map { it.message }}",
        )
        val p2 = parser()
        assertIs<InstructionStmt>(parse("VARSET ARR, 0, 1, 5", 11, p2))
        assertTrue(
            p2.warnings.none { it.message.contains("第3参数后面") },
            "一维目标不得告警：${p2.warnings.map { it.message }}",
        )
    }

    @Test
    fun `CVARSET 第4参之后的非一维目标留 level1 告警`() {
        // spec: ArgumentParser.cs:1876-1879（目标 0 维角色数据变量：既非 1D 也非 2D，
        // 前面的 IsCharacterData/IsArray2D 两支都放行，落到这里）。
        val p = parser()
        assertIs<InstructionStmt>(parse("CVARSET CNAME, 0, \"x\", 1, 2", 10, p))
        assertTrue(
            p.warnings.any { it.message.contains("第4参数后面除了一维数组以外的参数都会被忽略") },
            "非一维目标 + 第4参必须留 level1 告警：${p.warnings.map { it.message }}",
        )
    }

    @Test
    fun `INT_EXPRESSION 省略告警按 IsExtended 分流`() {
        // spec: ArgumentParser.cs:1199-1207 + FunctionIdentifier.cs:4235-4238（flag & 2 = EXTENDED）。
        val p = parser()
        assertIs<InstructionStmt>(parse("CLEARLINE", 10, p))
        val clearline = p.warnings.single { it.message.contains("参数被省略") }.message
        assertTrue(!clearline.contains("eramaker"), "CLEARLINE 是 EXTENDED ⇒ 应走 OmittedArg1：$clearline")

        val p2 = parser()
        assertIs<InstructionStmt>(parse("PRINT_ABL", 11, p2))
        val printAbl = p2.warnings.single { it.message.contains("参数被省略") }.message
        assertTrue(printAbl.contains("eramaker"), "PRINT_ABL 非 EXTENDED ⇒ 应走 OmittedArg2：$printAbl")
    }

    @Test
    fun `常量实参范围告警（CLEARLINE-FONTSTYLE-REPEAT）`() {
        // spec: ArgumentParser.cs:1219-1245；`-1` 形态由窄版常量折叠（foldedConstInt）识别。
        val p = parser()
        assertIs<InstructionStmt>(parse("CLEARLINE 0", 10, p))
        assertTrue(p.warnings.any { it.message.contains("小于等于0") }, "CLEARLINE 0：${p.warnings.map { it.message }}")

        val p2 = parser()
        assertIs<InstructionStmt>(parse("CLEARLINE 5", 11, p2))
        assertTrue(p2.warnings.none { it.message.contains("小于等于0") }, "CLEARLINE 5 不该告警：${p2.warnings.map { it.message }}")

        val p3 = parser()
        assertIs<InstructionStmt>(parse("FONTSTYLE -1", 12, p3))
        assertTrue(p3.warnings.any { it.message.contains("负值") }, "FONTSTYLE -1：${p3.warnings.map { it.message }}")

        val p4 = parser()
        val rep = assertIs<InstructionStmt>(parse("REPEAT 0", 13, p4))
        assertIs<SpForNextArgument>(rep.args)
        assertTrue(p4.warnings.any { it.message.contains("0或以下") }, "REPEAT 0：${p4.warnings.map { it.message }}")

        val p5 = parser()
        assertIs<InstructionStmt>(parse("REPEAT 5", 14, p5))
        assertTrue(p5.warnings.none { it.message.contains("0或以下") }, "REPEAT 5 不该告警：${p5.warnings.map { it.message }}")
    }

    // -----------------------------------------------------------------------
    // ArgInterner（驻留语义边界）
    // -----------------------------------------------------------------------

    private fun programOf(vararg stmts: Statement): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        for (s in stmts) {
            val idx = b.statement(s)
            b.emit(Opcode.PRINT, idx, 0, SlotAddr.NONE, 0)
        }
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun setStmt(dest: VarRef, term: com.eratw.port.core.expr.Expr?, addConst: Boolean, line: Int): InstructionStmt =
        InstructionStmt(FunctionCode.SET, SpSetArgument(dest, term).apply { this.addConst = addConst }, line, "SET")

    @Test
    fun `Argument 家族合并吃 isConst 与 addConst 原子位`() {
        // 结构相等 ⇒ 合并；仅 addConst（SpSetArgument 的原子位）不同 ⇒ 绝不合并。
        val dest1 = VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT)
        val dest2 = VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT)
        val a = setStmt(dest1, IntLiteral(1), addConst = false, line = 1)
        val b = setStmt(dest2, IntLiteral(1), addConst = false, line = 2)
        val c = setStmt(VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT), IntLiteral(1), addConst = true, line = 3)
        val program = programOf(a, b, c)
        program.internStatementArgs()
        assertSame(program.statement(0).args, program.statement(1).args, "结构相等的 SpSetArgument 应合并")
        assertNotSame(program.statement(0).args, program.statement(2).args, "addConst 不同不得合并")
    }

    @Test
    fun `字符串按内容归一，驻留不引入身份依赖`() {
        // 两个结构相等、但字符串是各自独立实例的 Argument：必须合并（内容比较，非身份）。
        val s1 = StringBuilder("セリフ").toString()
        val s2 = StringBuilder("セリフ").toString()
        assertNotSame(s1, s2, "前置条件：两份独立 String 实例")
        val a = InstructionStmt(FunctionCode.PRINTL, ExpressionArgument(StrLiteral(s1)), 1, "PRINTL x")
        val b = InstructionStmt(FunctionCode.PRINTL, ExpressionArgument(StrLiteral(s2)), 2, "PRINTL x")
        val program = programOf(a, b)
        program.internStatementArgs()
        assertSame(program.statement(0).args, program.statement(1).args, "内容相等的字符串结点应合并")
        val kept = assertIs<ExpressionArgument>(program.statement(0).args)
        assertEquals("セリフ", (kept.term as StrLiteral).value, "归一只换实例、不改内容")
    }

    @Test
    fun `列表元素就地改写不换列表身份（语句容器）`() {
        // ExpressionArrayArgument 的 terms 是语句自己的容器：合并的是父 Argument，
        // 元素被换成规范实例；父的 terms 列表内容与合并前逐项相同。
        val t1 = VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT)
        val a = InstructionStmt(FunctionCode.RETURN, ExpressionArrayArgument(listOf(t1)), 1, "RETURN")
        val b = InstructionStmt(FunctionCode.RETURN, ExpressionArrayArgument(listOf(VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT))), 2, "RETURN")
        val program = programOf(a, b)
        program.internStatementArgs()
        val arg = assertIs<ExpressionArrayArgument>(program.statement(0).args)
        assertEquals(1, arg.terms.size)
        assertEquals(t1, arg.terms[0], "合并后元素按值相同")
    }

    @Test
    fun `Expr 文本字段一律归一（FuncCall 名与 KeywordIndexArg 变量名）`() {
        // 类 KDoc「覆盖：`Expr`/`FormPart` 的文本」：`FuncCall.name` / `KeywordIndexArg.varName`
        // 是 `Expr` 的文本字段，必须与 `VarRef.name`/`StrLiteral.value` 同口径归一。
        // 用「实参不同 ⇒ 不互相合并」的两份结点验证：各自重建时仍折向同一规范 String 实例。
        val n1 = StringBuilder("SOME_FUNC").toString()
        val n2 = StringBuilder("SOME_FUNC").toString()
        val k1 = StringBuilder("CFLAG").toString()
        val k2 = StringBuilder("CFLAG").toString()
        assertNotSame(n1, n2)
        assertNotSame(k1, k2)
        val a = IfStmt(FuncCall(n1, listOf(IntLiteral(1)), OperandType.INT), ExpressionArgument(IntLiteral(0)), 1, "IF F(1)")
        val b = IfStmt(FuncCall(n2, listOf(IntLiteral(2)), OperandType.INT), ExpressionArgument(IntLiteral(0)), 2, "IF F(2)")
        // 关键词下标挂在重建路径上（condition 是语句自带表达式字段，会被写回规范实例）。
        val c = IfStmt(KeywordIndexArg(StrLiteral("k1"), k1, 1), ExpressionArgument(IntLiteral(0)), 3, "IF CFLAG:k1")
        val d = IfStmt(KeywordIndexArg(StrLiteral("k2"), k2, 1), ExpressionArgument(IntLiteral(0)), 4, "IF CFLAG:k2")
        val program = programOf(a, b, c, d)
        program.internStatementArgs()
        val ca = assertIs<FuncCall>(a.condition)
        val cb = assertIs<FuncCall>(b.condition)
        assertNotSame(ca, cb, "实参不同的 FuncCall 不得互相合并")
        assertSame(ca.name, cb.name, "同名 FuncCall 的 name 必须折向同一规范实例")
        val kc = assertIs<KeywordIndexArg>(c.condition)
        val kd = assertIs<KeywordIndexArg>(d.condition)
        assertSame(kc.varName, kd.varName, "同变量名 KeywordIndexArg 的 varName 必须折向同一规范实例")
    }
}