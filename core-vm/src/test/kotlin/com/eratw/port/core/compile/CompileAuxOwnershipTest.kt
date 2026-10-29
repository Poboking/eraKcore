package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `core/compile` 辅助族（[ExprCanonicalizer]/[ArgSignatureCheck]）的补充回归：
 *
 * 1. **语句子类身份保真**（[ExprCanonicalizer] 的 `is InstructionStmt` 分支）：语句身份
 *    决定 sem 分派（ErbProgramCompiler「子类身份保真」契约），折叠**不得**把具名子类
 *    （DoStmt 等）按基类 `InstructionStmt` 重建 —— 旧写法会静默丢类型。
 * 2. 通用 `InstructionStmt` 直接实例仍照常折叠（不许因 1 的回退而整体放弃折叠）。
 * 3. `SpTimesArgument` 的 `dest` 参与折叠（旧写法直接返回原实例，去重遗漏）。
 * 4. 内建 arity 的 `ArgsNotNeeded` 文案（spec: FunctionMethod.cs:307-318）：形参数 0
 *    且实参非空是独立文案，不是通用「个数不匹配」。
 */
class CompileAuxOwnershipTest {

    private val canon = ExprCanonicalizer()

    private fun varRefA(line: Int) =
        VarRef("A", emptyList(), operandType = OperandType.INT, line = line, col = line)

    // ------------------------------------------------------------------
    // 1/2：语句子类身份 + 通用指令行折叠
    // ------------------------------------------------------------------

    @Test
    fun `InstructionStmt具名子类的类型身份不被折叠抹掉`() {
        // DoStmt 的真实 args 恒为 VoidArgument（本不会重建）；此处人为喂非 Void，
        // 触发「args 被折叠改写」这条路径，验证回退分支保身份。
        val s = DoStmt(ExpressionArgument(IntLiteral(1, 1, 1)), 1, "DO")
        val r = canon.canonicalizeStatement(s)
        assertSame(s, r, "具名子类必须原实例返回，绝不重建为基类 InstructionStmt")
        assertTrue(r is DoStmt, "子类类型必须保留")
    }

    @Test
    fun `通用InstructionStmt直接实例仍折叠其参数`() {
        val s = InstructionStmt(FunctionCode.POWER, ExpressionArgument(IntLiteral(5, 1, 1)), 2, "POWER 5")
        val r = canon.canonicalizeStatement(s) as InstructionStmt
        assertNotSame(s, r, "args 被行列归一改写 ⇒ 重建通用指令行")
        assertEquals(IntLiteral(5, -1, -1), (r.args as ExpressionArgument).term, "字面量行号归 -1")
        assertEquals(s.code, r.code)
        assertEquals(s.line, r.line, "语句行号不归一")
        assertEquals(s.raw, r.raw, "语句原文不归一")
    }

    // ------------------------------------------------------------------
    // 3：SpTimesArgument 的 dest 折叠
    // ------------------------------------------------------------------

    @Test
    fun `SpTimesArgument的dest参与折叠`() {
        val s = InstructionStmt(
            FunctionCode.TIMES,
            SpTimesArgument(varRefA(5), 2.0),
            3,
            "TIMES A, 2",
        )
        val r = canon.canonicalizeStatement(s) as InstructionStmt
        val times = r.args as SpTimesArgument
        assertEquals(-1, times.dest.line, "dest VarRef 行号应归一为 -1")
        assertEquals(-1, times.dest.col, "dest VarRef 列号应归一为 -1")
        assertEquals(2.0, times.doubleValue, "倍率不丢")
    }

    // ------------------------------------------------------------------
    // 4：内建 ArgsNotNeeded 文案（GETSECOND 为 0 参真实内建）
    // ------------------------------------------------------------------

    private fun compileOne(vararg lines: String): Program {
        val p = LineParser(
            SymbolTable()
                .declareVariable("OUT", OperandType.INT, 1)
                .declareFunction("GETSECOND", OperandType.INT),
        )
        val stmts = lines.mapIndexedNotNull { i, text ->
            p.parseLogicalLine(LogicalLine(LineKind.CODE, "aux.erb", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        compiler.compileFunction(FuncSig("MAIN", 0), stmts, setOf("MAIN"))
        return compiler.build("MAIN")
    }

    @Test
    fun `内建形参数为零且实参非空用不需要参数文案`() {
        val program = compileOne("OUT:0 = GETSECOND(1)")
        val ws = ArgSignatureCheck.checkProgram(program)
        val w = ws.firstOrNull { it.callee.equals("GETSECOND", ignoreCase = true) }
        assertTrue(w != null, "GETSECOND(1) 应产一处内建 arity 告警；实际：$ws")
        assertEquals(ArgSignatureWarningKind.TOO_MANY, w!!.kind)
        assertTrue(w.message.contains("不需要参数"), "ArgsNotNeeded 独立文案：${w.message}")
    }
}