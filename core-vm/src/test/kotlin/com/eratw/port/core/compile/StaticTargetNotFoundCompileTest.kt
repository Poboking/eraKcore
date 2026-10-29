package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **非 TRY 静态目标缺失**的编译期降级锁（行级错误 → THROW 哨兵）。
 *
 * 原版对「常量 GOTO 标签不存在」「常量 CALL/CALLF 函数不存在」只记 **IsError 行**
 * （装载照常完成），执行到该行才抛 CodeEE：
 * - 判据：`GOTO_Instruction.SetJumpTo`（spec: FunctionIdentifier.cs:3664-3669）、
 *   `CALL_Instruction.SetJumpTo`（:3532-3535）、`CALLF_Instruction.SetJumpTo`（:1133-1143）；
 *   ErbLoader 的 FunctionoNotFoundName 通道（:1615-1625）与 `line.IsError = true`（:937-955）；
 * - 运行期抛点：`Process.cs:1017-1019`；`GOTO_Instruction.DoInstruction` :3703、
 *   `CALL_Instruction.DoInstruction` :3581、`CALLF_Instruction.DoInstruction` :1163。
 *
 * 旧实现（GOTO）在 [ErbProgramCompiler] 的 `run()` 末尾抛「GOTO 目标不存在」，
 * 令整个 @函数被 CorpusCompile 兜底成空体桩；旧实现（CALL/CALLF）走 `build()`
 * 的「未定义的函数」，连整个 Program 都建不出来。本文件把两者钉成 THROW 哨兵。
 * 语料可达：`COMF410 掃除.ERB:51` @COM410 的 `CANCEL_LOOP`、
 * `M_KOJO_K17_口上固有追加イベント.ERB:2396` @K17_ONABARE 的 `GOOUT`。
 */
class StaticTargetNotFoundCompileTest {

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
            p.parseLogicalLine(LogicalLine(kind, "static-miss.erb", i + 1, text))
        }
    }

    /** 产物里 THROW marker 携带的文案；无 THROW 则 null。 */
    private fun throwMessage(p: Program): String? {
        for (i in 0 until p.instructionCount) {
            val ins = p.instructionAt(i)
            if (ins.op != Opcode.THROW) continue
            val term = (p.statement(ins.a).args as? ExpressionArgument)?.term
            return (term as? StrLiteral)?.value
        }
        return null
    }

    // ------------------------------------------------------------------
    // GOTO
    // ------------------------------------------------------------------

    @Test
    fun `常量 GOTO 缺失编 THROW 哨兵且不中断编译单元`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("GOTO \$NOWHERE", "PRINTL \"AFTER\""))
        val p = c.build("TEST") // 不得抛「GOTO 目标不存在」
        assertEquals(
            "当前函数中不存在指定的标签名\"\$NOWHERE\"",
            throwMessage(p),
            "文案须照抄官方 zhs（lang.emuera-zhs.xml:540）",
        )
    }

    @Test
    fun `TRYGOTO 到不存在标签静默跳过不编哨兵`() {
        // GOTO_Instruction.DoInstruction 的 `JumpTo == null → return`（FunctionIdentifier.cs:3688-3690），
        // TRY 位下 SetJumpTo 对查不到的目标不报错（:3666-3670）。
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("TRYGOTO \$NOWHERE", "PRINTL \"AFTER\""))
        val p = c.build("TEST")
        assertNull(throwMessage(p), "TRYGOTO 缺失 = 跳过本行，不得编 THROW")
    }

    @Test
    fun `常量 GOTO 命中标签仍发射 _JUMP（回归）`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(
            FuncSig("TEST"),
            parse("GOTO \$TAIL", "PRINTL \"SKIPPED\"", "\$TAIL", "PRINTL \"DONE\""),
        )
        val p = c.build("TEST")
        val list = (fb.entryPc until fb.endPc).map { p.instructionAt(it) }
        assertEquals(1, list.count { it.op == Opcode._JUMP }, "常量命中标签 = 一条 _JUMP")
        assertNull(throwMessage(p), "命中标签不得编 THROW")
    }

    // ------------------------------------------------------------------
    // CALL / JUMP / CALLF
    // ------------------------------------------------------------------

    @Test
    fun `CALL 到不存在函数编 THROW 哨兵而非炸掉 Program`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("CALL NOWHERE"), setOf("KNOWN"))
        val p = c.build("TEST") // 旧实现会抛「未定义的函数」
        assertEquals("没有找到指定的函数\"@NOWHERE\"", throwMessage(p))
    }

    @Test
    fun `JUMP 到不存在函数同样编 THROW 哨兵`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("JUMP NOWHERE"), setOf("KNOWN"))
        val p = c.build("TEST")
        assertEquals("没有找到指定的函数\"@NOWHERE\"", throwMessage(p))
    }

    @Test
    fun `CALLF 到不存在函数编 THROW 哨兵`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("CALLF NOWHERE"), setOf("KNOWN"))
        val p = c.build("TEST")
        assertEquals("没有找到指定的函数\"@NOWHERE\"", throwMessage(p))
    }

    @Test
    fun `TRYCALL 到不存在函数静默跳过不编哨兵`() {
        // CALL_Instruction.DoInstruction：isTry 且 calledFunction == null → return（FunctionIdentifier.cs:3577-3587）。
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("TRYCALL NOWHERE"), setOf("KNOWN"))
        val p = c.build("TEST")
        assertNull(throwMessage(p), "TRY 系缺失 = 跳过本行")
    }

    @Test
    fun `known 为空时 CALL 缺失保持旧行为不做编译期判名`() {
        // known 空 = 调用方未提供函数全集（旧装置特权）：无从判定缺失，维持无条件发射，
        // 由 build() 的「未定义的函数」兜底（与旧行为逐字一致）。
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("CALL NOWHERE"))
        val e = assertFailsWith<CompileException> { c.build("TEST") }
        assertTrue(e.message!!.contains("未定义的函数"), "实际: ${e.message}")
    }

    // ------------------------------------------------------------------
    // 运行期：哨兵真的抛 CodeEE 且文案照 zhs（旧行为是静默空体桩）
    // ------------------------------------------------------------------

    @Test
    fun `缺失 GOTO 的哨兵运行期抛 CodeEE 且文案照 zhs`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("GOTO \$NOWHERE"))
        val p = c.build("TEST")
        val rt = ProcessRuntime(p, VariableStore(), FunctionTable(emptyList()))
        val err = runCatching { rt.execute() }.exceptionOrNull()
        val msg = listOfNotNull(err?.message, err?.cause?.message).joinToString(" | ")
        assertTrue(
            msg.contains("当前函数中不存在指定的标签名"),
            "原版执行到该行抛 CodeEE（Process.cs:1017-1019），实际: $msg",
        )
    }
}