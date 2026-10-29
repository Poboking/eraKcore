package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CMP-29：裸 `GOTO <非常量表达式>`（**非 FORM 语句**）必须走**标签**通道，不是函数名通道。
 *
 * 原版 `GOTO_Instruction.DoInstruction`（spec: FunctionIdentifier.cs:3681-3698）：
 * - `func.Argument.IsConst` → 装载期已解析的 `func.JumpTo`（常量标签，`SetJumpTo` :3655-3679）；
 * - **非 const** → `((SpCallArgment)func.Argument).FuncnameTerm.GetStrValue(exm)` 后
 *   `state.CurrentCalled.CallLabel(...)` = 运行期查**当前函数**的 `$标签`表（:3696-3698）；
 * - 查不到：非 TRY 抛 `NotDefinedLabelName`，TRY 系跳过本行（:3699-3709）。
 *
 * 本侧 `JumpStmt.label` 只是「`funcNameTerm` 是 `StrLiteral`」时的常量快照
 * （`SpCallArgument.label`），非常量时为 null。旧实现的 `when` 把 `label == null` 一律
 * 当作 FORM 函数名路径（`_CALL_BY_NAME`），于是 `GOTO <表达式>` 会把**标签当函数名**解析
 * （目标查不到又被空体桩静默吞掉，闸仍 0 失败）。
 *
 * 可达性：装载器的 SP_CALL 构造器（`ArgBuilders.call`）把非 FORM 名一律读成字符串字面量
 * （`readString` → `StrLiteral`），语料 3037 处 `GOTO` 全为裸标识符 ⇒ 源码不可达。
 * 本文件按 Board 要求直接构造 `label == null` 的非常量形态（erbcc 容器/手写 AST 面）。
 */
class Cmp29DynamicGotoRoutingTest {

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
            p.parseLogicalLine(LogicalLine(kind, "cmp29.erb", i + 1, text))
        }
    }

    /** 非常量标签名表达式 `"TA" + "IL"`（求值结果 = 已定义的 `$TAIL`）。 */
    private fun dynName(): SpCallArgument = SpCallArgument(
        Binary(
            OperatorCode.PLUS,
            StrLiteral("TA", 1),
            StrLiteral("IL", 1),
            OperandType.STR,
            1,
        ),
        emptyList(),
        emptyList(),
    )

    private fun compile(code: FunctionCode): Pair<FunctionBytecode, com.eratw.port.core.code.Program> {
        val c = ErbProgramCompiler()
        // `label == null` = 非常量目标（SpCallArgument.label 只认裸 StrLiteral）。
        val jump = JumpStmt(code, null, dynName(), 1, "$code \"TA\" + \"IL\"")
        val body = ArrayList<Statement>()
        body.add(jump)
        body.addAll(parse("PRINTL SKIPPED", "\$TAIL", "PRINTL DONE"))
        val fb = c.compileFunction(FuncSig("TEST"), body)
        return fb to c.build("TEST")
    }

    private fun ops(fb: FunctionBytecode, p: com.eratw.port.core.code.Program) =
        (fb.entryPc until fb.endPc).map { p.instructionAt(it) }

    @Test
    fun `GOTO 非常量表达式发射 _JUMP_BY_NAME 而非 _CALL_BY_NAME`() {
        val (fb, p) = compile(FunctionCode.GOTO)
        val list = ops(fb, p)
        assertTrue(
            list.none { it.op == Opcode._CALL_BY_NAME },
            "GOTO 的非常量目标走标签表（CallLabel），不是函数名（CalledFunction.CallFunction）",
        )
        val byName = list.filter { it.op == Opcode._JUMP_BY_NAME }
        assertEquals(1, byName.size, "应发射一条 _JUMP_BY_NAME，实际 ${byName.size}")
        assertEquals(0, byName.single().b, "GOTO 不置 try 位（TRYGOTO 才置）")
    }

    @Test
    fun `TRYGOTO 非常量表达式同样走 _JUMP_BY_NAME 且置 try 位`() {
        val (fb, p) = compile(FunctionCode.TRYGOTO)
        val list = ops(fb, p)
        assertTrue(list.none { it.op == Opcode._CALL_BY_NAME }, "TRYGOTO 不得进 _CALL_BY_NAME")
        val byName = list.filter { it.op == Opcode._JUMP_BY_NAME }
        assertEquals(1, byName.size, "应发射一条 _JUMP_BY_NAME，实际 ${byName.size}")
        assertEquals(
            Opcode.TRY_FLAG, byName.single().b and Opcode.TRY_FLAG,
            "TRYGOTO 必须置 bit31（未命中 → 跳过本行，spec: FunctionIdentifier.cs:3699-3704）",
        )
    }

    /**
     * 运行期证死：动态目标求值成 `TAIL` 时，`_JUMP_BY_NAME` 必须查**当前函数的 `$标签`**
     * （Vm.kt :584-602），落到 `$TAIL` 之后的 `PRINTL "DONE"`；若仍误走函数名通道，
     * 这里会走「目标函数不存在」的空体桩，输出停在 `SKIPPED` 或直接报错。
     */
    @Test
    fun `动态 GOTO 运行期跳到同名标签而不是函数`() {
        val (_, p) = compile(FunctionCode.GOTO)
        // 走完整运行期（语句 marker 需要 sem 执行器）：PRINTL 落 output 镜像。
        val rt = ProcessRuntime(p, VariableStore(), FunctionTable(emptyList()))
        rt.execute()
        assertEquals("DONE\n", rt.output.toString())
    }

    @Test
    fun `常量 GOTO 仍走编译期 _JUMP（对照组，不许回退）`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("TEST"), parse("GOTO \$TAIL", "PRINTL \"SKIPPED\"", "\$TAIL", "PRINTL \"DONE\""))
        val p = c.build("TEST")
        val list = ops(fb, p)
        assertTrue(list.none { it.op == Opcode._JUMP_BY_NAME }, "常量标签在装载期解析（SetJumpTo），不进运行期表")
        assertEquals(1, list.count { it.op == Opcode._JUMP }, "常量 GOTO = 一条 _JUMP")
    }
}
