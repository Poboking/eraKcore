package com.eratw.port.core.compile

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-31：装载期校验缺两条 —— 本侧必须补上（原版都是 `ParserMediator.Warn(isError: true)`
 * 的**行级**错误；本侧装载单元是函数，按既有口径统一升级为 [CompileException]，由调用方
 * 记 `CompileFailure` 并落空体桩）。
 *
 * 1. **`RETURNF` 在非 `#FUNCTION` 内**（spec: FunctionIdentifier.cs:3455-3458
 *    `if (!parentLabelLine.IsMethod) Warn(CanNotUseReturnf, isError:true)`）。
 *    本侧旧实现无条件发 `_RET`：RETURNF 落在 depth 0 的非方法帧时直接把整局执行返回掉。
 *    语料 18,077 处实测全在 `#FUNCTION`/`#FUNCTIONS` 内 ⇒ 补校验不动语料。
 * 2. **`CASE` 零条件**（spec: ErbLoader.cs:1309-1312 `caseExps.Length == 0` →
 *    `Warn(MissingArg, isError:true)`；运行期 SELECTCASE 跳过 IsError 的 ifCase，
 *    FunctionIdentifier.cs:3169-3172）。本侧旧实现让该 CASE 恒命中（失配链直接落进它的体）。
 *    装载器 `ArgBuilders.case` 已在解析期拒绝零条件（`CASE 参数格式错误`），本条守的是
 *    容器/手写 AST 面。
 */
class Cmp31LoadValidationTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareFunction("MYFUNC", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "cmp31.erb", i + 1, text))
        }
    }

    // ------------------------------------------------------------------
    // 1) RETURNF 只能在 #FUNCTION / #FUNCTIONS 内
    // ------------------------------------------------------------------

    @Test
    fun `非方法函数里的 RETURNF 在装载期报错`() {
        val e = assertFailsWith<CompileException> {
            ErbProgramCompiler().compileFunction(FuncSig("TEST"), parse("RETURNF 1"))
        }
        assertTrue("RETURNF" in (e.message ?: ""), "文案应点名 RETURNF，实际=${e.message}")
        assertTrue(
            "#FUNCTION" in (e.message ?: ""),
            "文案应说明只能在 #FUNCTION/#FUNCTIONS 内（spec: FunctionIdentifier.cs:3455-3458），实际=${e.message}",
        )
    }

    @Test
    fun `方法函数里的 RETURNF 合法（对照组）`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST", isMethod = true), parse("RETURNF 1"))
        c.build("TEST")
    }

    // ------------------------------------------------------------------
    // 2) CASE 零条件
    // ------------------------------------------------------------------

    @Test
    fun `零条件 CASE 在装载期报错`() {
        val stmts = ArrayList<Statement>()
        stmts.addAll(parse("SELECTCASE 1"))
        // 装载器已在解析期拒绝（ArgBuilders.case → "CASE 参数格式错误"）；这里直接构造
        // 容器/手写 AST 面能产出的零条件节点。
        stmts.add(CaseStmt(emptyList(), CaseArgument(emptyList()), 2, "CASE"))
        stmts.addAll(parse("ENDSELECT"))
        val e = assertFailsWith<CompileException> {
            ErbProgramCompiler().compileFunction(FuncSig("TEST"), stmts)
        }
        assertTrue(
            "CASE" in (e.message ?: "") && "缺少条件" in (e.message ?: ""),
            "应报 CASE 缺少条件（spec: ErbLoader.cs:1309-1312 MissingArg），实际=${e.message}",
        )
    }

    @Test
    fun `正常 CASE 仍可编译（对照组）`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(
            FuncSig("TEST"),
            parse("SELECTCASE A", "CASE 1", "PRINTL \"ONE\"", "CASEELSE", "PRINTL \"OTHER\"", "ENDSELECT"),
        )
        c.build("TEST")
        assertTrue(fb.instructionCount > 0)
    }
}
