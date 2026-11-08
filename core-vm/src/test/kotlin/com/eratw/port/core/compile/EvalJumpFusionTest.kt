package com.eratw.port.core.compile

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.StringHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MODERN-T1：条件位融合超指令 `_EVAL_INT_JZ`（246）的发射、等价性与装载期校验。
 *
 * 语义权限：`_EVAL_INT a,b,0` 后接 `_JUMP_IF_FALSE b,c,0` 的序列逐字压成一条
 * （C# 锚见 `Opcode._EVAL_INT_JZ` 的 KDoc：WHILE `FunctionIdentifier.cs:3023-3027`、
 * SIF `:3072-3078`、IF/ELSEIF `:3128-3141`）。
 *
 * 本类锁四件事：
 * 1. **发射面**：IF / SIF / ELSEIF / WHILE 的 INT 非字面量条件各发一条融合指令，
 *    且不再残留 `_JUMP_IF_FALSE`；字面量条件不融合（旧序列逐字节不变）。
 * 2. **等价面**：开态产物按「展开成两条」规范化后，与关态产物**逻辑指令流逐条相同**；
 *    帧槽数也不得变化（分配序与改造前一致）。
 * 3. **行为面**：手写汇编的融合程序与展开版跑 VM 输出一致（真/假两支都测）。
 * 4. **装载面**：表达式池越界、跳转目标越出函数区间、声明不带表达式池三种畸形产物
 *    在 `ErbcReader.decode` 当场拒载。
 */
class EvalJumpFusionTest {

    // ------------------------------------------------------------------
    // 编译装置（同 JumpCompileTest 口径）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("RESULT", OperandType.INT, 0),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun program(fuse: Boolean, vararg lines: String): Program {
        val c = ErbProgramCompiler(fuseEvalJump = fuse)
        c.compileFunction(FuncSig("TEST"), parse(*lines))
        return c.build()
    }

    private fun fusedCount(p: Program): Int =
        (0 until p.instructionCount).count { p.op(it) == Opcode._EVAL_INT_JZ }

    private fun countOp(p: Program, op: Int): Int =
        (0 until p.instructionCount).count { p.op(it) == op }

    // ------------------------------------------------------------------
    // 1：发射面
    // ------------------------------------------------------------------

    @Test
    fun `IF 的 INT 条件发一条融合指令且不再发 _JUMP_IF_FALSE`() {
        val p = program(true, "IF A", "PRINTL A", "ENDIF")
        assertEquals(1, fusedCount(p), "IF 应发恰好一条 _EVAL_INT_JZ")
        assertEquals(0, countOp(p, Opcode._JUMP_IF_FALSE), "融合后不得残留 _JUMP_IF_FALSE")

        val pc = (0 until p.instructionCount).single { p.op(it) == Opcode._EVAL_INT_JZ }
        assertTrue(p.a(pc) in 0 until p.exprCount, "a 必须是表达式池下标（共 ${p.exprCount} 条）")
        assertTrue(!SlotAddr.isNone(p.b(pc)), "b 必须是条件落槽")
        val target = pc + 1 + p.c(pc)
        assertTrue(target > pc, "条件为假时跳转目标应在融合指令之后，实际 $target")
        assertTrue(target in 0..p.instructionCount, "跳转目标越界: $target")
    }

    @Test
    fun `SIF ELSEIF WHILE 三处同样融合`() {
        val sif = program(true, "SIF A", "PRINTL A")
        assertEquals(1, fusedCount(sif), "SIF 应发一条")
        assertEquals(0, countOp(sif, Opcode._JUMP_IF_FALSE), "SIF 融合后不得残留 _JUMP_IF_FALSE")

        val elseif = program(true, "IF A", "PRINTL A", "ELSEIF A", "PRINTL A", "ENDIF")
        assertEquals(2, fusedCount(elseif), "IF + ELSEIF 各一条")
        assertEquals(0, countOp(elseif, Opcode._JUMP_IF_FALSE), "ELSEIF 融合后不得残留 _JUMP_IF_FALSE")

        val wend = program(true, "WHILE A", "PRINTL A", "WEND")
        assertEquals(1, fusedCount(wend), "WHILE 应发一条")
        assertEquals(0, countOp(wend, Opcode._JUMP_IF_FALSE), "WHILE 融合后不得残留 _JUMP_IF_FALSE")
    }

    @Test
    fun `字面量条件不融合_开关两态逐字节相同`() {
        val cases = listOf(
            arrayOf("IF 1", "PRINTL A", "ENDIF"),
            arrayOf("WHILE 0", "PRINTL A", "WEND"),
        )
        for (src in cases) {
            val on = program(true, *src)
            val off = program(false, *src)
            assertEquals(0, fusedCount(on), "字面量条件不得融合：${src.first()}")
            assertEquals(1, countOp(on, Opcode._JUMP_IF_FALSE), "字面量仍走 _JUMP_IF_FALSE")
            assertEquals(on.code.toList(), off.code.toList(), "字面量条件下开/关两态必须逐字节相同")
        }
    }

    // ------------------------------------------------------------------
    // 2：等价面（展开规范化）
    // ------------------------------------------------------------------

    /** 规范化后的一条逻辑指令；跳转只比**绝对目标**（相对偏移随 pc 平移，不具可比性）。 */
    private data class LIns(val op: Int, val a: Int, val b: Int, val c: Int, val target: Int?)

    /**
     * 把程序展平成「逻辑指令流」：融合指令展开成 `_EVAL_INT` + `_JUMP_IF_FALSE` 两条，
     * 所有跳转偏移换算成展开后的**绝对 pc**。两份产物的 pc 编号因此可比。
     */
    private fun logical(p: Program): List<LIns> {
        val n = p.instructionCount
        val newPc = IntArray(n + 1)
        var k = 0
        for (pc in 0 until n) {
            newPc[pc] = k
            k += if (p.op(pc) == Opcode._EVAL_INT_JZ) 2 else 1
        }
        newPc[n] = k

        val out = ArrayList<LIns>(k)
        for (pc in 0 until n) {
            val op = p.op(pc)
            val a = p.a(pc)
            val b = p.b(pc)
            val c = p.c(pc)
            when (op) {
                Opcode._EVAL_INT_JZ -> {
                    out += LIns(Opcode._EVAL_INT, a, b, 0, null)
                    out += LIns(Opcode._JUMP_IF_FALSE, b, 0, 0, newPc[pc + 1 + c])
                }
                Opcode._JUMP -> out += LIns(op, 0, b, c, newPc[pc + 1 + a])
                Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> out += LIns(op, a, 0, c, newPc[pc + 1 + b])
                else -> out += LIns(op, a, b, c, null)
            }
        }
        return out
    }

    @Test
    fun `融合产物展开后与关态逻辑指令流逐条相同且帧槽数不变`() {
        val lines = arrayOf(
            "IF A", "PRINTL A", "ELSEIF A", "PRINTL A", "ENDIF",
            "WHILE A", "SIF A", "PRINTL A", "WEND",
        )
        val on = program(true, *lines)
        val off = program(false, *lines)

        assertTrue(fusedCount(on) >= 3, "样本必须真的融合到，实际 ${fusedCount(on)} 条")
        assertEquals(0, fusedCount(off), "关态不得出现融合指令")

        assertEquals(logical(off), logical(on), "展开后的逻辑指令流不一致")
        assertEquals(
            off.functions.map { it.slotCount },
            on.functions.map { it.slotCount },
            "融合不得改变帧槽数",
        )
    }

    // ------------------------------------------------------------------
    // 3：行为面（VM 跑真/假两支）
    // ------------------------------------------------------------------

    private object NoVars : VariableReader {
        override fun isConst(name: String): Boolean = false
        override fun readInt(name: String, indices: LongArray): Long = error("字面量表达式不应读变量: $name")
        override fun readStr(name: String, indices: LongArray): String = error("字面量表达式不应读变量: $name")
        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = error("不应写变量: $name")
        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 0L
    }

    private object LiteralCtx : EvalContext {
        override val variables: VariableReader get() = NoVars
        override fun callFunction(name: String, args: List<Value?>): Value = error("不应调用函数: $name")
    }

    /** 汇编并跑 VM；`$0` = 1（真）、`$1` = 0（假）。 */
    private fun runText(src: String): String {
        val p = Assembler.assemble(src.trimIndent(), listOf(IntLiteral(1L), IntLiteral(0L)))
        val host = StringHost(LiteralCtx)
        VirtualMachine(p, host).run()
        return host.output.toString()
    }

    @Test
    fun `融合指令条件为真时落槽并顺序执行_行为与展开版一致`() {
        val fused = runText(
            """
            .func main slots=2
              _EVAL_INT_JZ ${'$'}0, R0, L_DONE
              _PRINT_INT R0, 0
            L_DONE:
              _HALT
            """,
        )
        val expanded = runText(
            """
            .func main slots=2
              _EVAL_INT ${'$'}0, R0
              _JUMP_IF_FALSE R0, L_DONE
              _PRINT_INT R0, 0
            L_DONE:
              _HALT
            """,
        )
        assertEquals("1", fused, "融合版条件为真应打印落槽值 1")
        assertEquals(expanded, fused, "融合版与展开版输出必须一致")
    }

    @Test
    fun `融合指令条件为假时跳过分支且仍写目标槽_行为与展开版一致`() {
        val fused = runText(
            """
            .func main slots=2
              _EVAL_INT_JZ ${'$'}1, R0, L_DONE
              _PRINT_INT R0, 0
            L_DONE:
              _PRINT_INT R0, 0
              _HALT
            """,
        )
        val expanded = runText(
            """
            .func main slots=2
              _EVAL_INT ${'$'}1, R0
              _JUMP_IF_FALSE R0, L_DONE
              _PRINT_INT R0, 0
            L_DONE:
              _PRINT_INT R0, 0
              _HALT
            """,
        )
        assertEquals("0", fused, "融合版条件为假应跳过分支、打印目标槽的 0")
        assertEquals(expanded, fused, "融合版与展开版输出必须一致")
    }

    // ------------------------------------------------------------------
    // 4：装载期校验
    // ------------------------------------------------------------------

    @Test
    fun `声明不带表达式池时融合指令在装载期拒载`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._EVAL_INT_JZ, 0, SlotAddr.local(0), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        val e = assertFailsWith<ErbcDecodeException> {
            ErbcReader.decode(ErbcWriter.encode(b.build(main)))
        }
        assertTrue("表达式池" in e.message.orEmpty(), "实际文案: ${e.message}")
    }

    @Test
    fun `表达式池下标越界在装载期拒载`() {
        val p = Assembler.assemble(
            """
            .func main slots=2
              _EVAL_INT_JZ ${'$'}0, R0, 0
              _HALT
            """.trimIndent(),
            listOf(IntLiteral(1L)),
        )
        // 指令字布局 op/a/b/c：pc 0 的 a 是第 1 字。
        p.code[1] = 9
        val e = assertFailsWith<ErbcDecodeException> {
            ErbcReader.decode(ErbcWriter.encode(p))
        }
        assertTrue("表达式池" in e.message.orEmpty(), "实际文案: ${e.message}")
    }

    @Test
    fun `跳转目标越出函数区间在装载期拒载`() {
        val p = Assembler.assemble(
            """
            .func main slots=2
              _EVAL_INT_JZ ${'$'}0, R0, 0
              _HALT
            """.trimIndent(),
            listOf(IntLiteral(1L)),
        )
        // c 是第 3 字：-2 使目标 = 0 + 1 - 2 = -1，落在 [0, 2) 之外。
        p.code[3] = -2
        val e = assertFailsWith<ErbcDecodeException> {
            ErbcReader.decode(ErbcWriter.encode(p))
        }
        assertTrue("跳转目标" in e.message.orEmpty(), "实际文案: ${e.message}")
    }
}
