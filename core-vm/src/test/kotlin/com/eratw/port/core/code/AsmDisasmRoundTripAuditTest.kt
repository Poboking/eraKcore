package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BH3-VMCORE 判据 ①：操作码表与汇编往返 —— `Assembler → 字节 → Disassembler → Assembler`
 * 对**所有操作码与操作数形态**逐字节相同；有差异就是真 bug。
 *
 * 覆盖矩阵：
 * - 全部 244 个操作码（0..28 内部 / 29..197 语句 / 198..201 编译器扩展 / 202..243 栈码）
 *   逐条往返；
 * - 语句操作码的三种立即数形态（3 操作数 / 0 操作数 / 负立即数）+ marker 形态（`stmt#k`）；
 * - 槽形态 R<n>/G<n>/`none`（含必选位上的 `none`）、常量池下标（含 Long.MIN/MAX、去重序）、
 *   负立即数、`#<下标>` 与函数名两种调用形态、前向标签/后向标签/原始偏移跳转；
 * - 往返差异回归（本轮修掉的两条真 bug）：共享 entryPc 的空函数被 HashMap 覆写丢失、
 *   拖尾空函数（entryPc == instructionCount）不打印；以及 `#` 前缀函数名与
 *   `_CALL #<下标>` 语法冲突导致的静默换函数（汇编期显式失败）。
 *
 * 判据口径：指令字（op/a/b/c 四个 u32）逐字相同即恒等；池大小、全局槽数、函数表
 * （name/arity/slotCount/entryPc）随之比对。断言一律带「期望 vs 实际」。
 */
class AsmDisasmRoundTripAuditTest {

    // ------------------------------------------------------------------
    // 往返判据
    // ------------------------------------------------------------------

    /** 第一处不同的字（期望 vs 实际）；全同返回空串。 */
    private fun firstDiff(expected: List<Int>, actual: List<Int>): String {
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                val pc = i / Instruction.WORDS
                val field = when (i % Instruction.WORDS) {
                    0 -> "op"
                    1 -> "a"
                    2 -> "b"
                    else -> "c"
                }
                return "第 $i 字（pc=$pc 的 $field）不一致: 期望 ${expected[i]} 实际 ${actual[i]}"
            }
        }
        if (expected.size != actual.size) {
            return "指令字数不一致: 期望 ${expected.size} 实际 ${actual.size}"
        }
        return ""
    }

    private fun assertRoundTrip(
        src: String,
        exprs: List<Expr> = emptyList(),
        statements: List<Statement> = emptyList(),
    ) {
        val p1 = Assembler.assemble(src.trimIndent(), exprs, statements)
        val text = p1.disassemble()
        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())

        assertEquals("", firstDiff(p1.code.toList(), p2.code.toList()), "指令字不一致。反汇编文本:\n$text")
        assertEquals(p1.instructionCount, p2.instructionCount, "指令条数不一致。反汇编文本:\n$text")
        assertEquals(p1.constantCount, p2.constantCount, "常量池大小不一致。反汇编文本:\n$text")
        assertEquals(p1.stringCount, p2.stringCount, "字符串池大小不一致。反汇编文本:\n$text")
        assertEquals(p1.exprCount, p2.exprCount, "表达式池大小不一致。反汇编文本:\n$text")
        assertEquals(p1.globalSlots, p2.globalSlots, "全局槽数不一致。反汇编文本:\n$text")
        assertEquals(
            p1.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            p2.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            "函数表不一致。反汇编文本:\n$text",
        )
    }

    // ------------------------------------------------------------------
    // 全部操作码逐条往返
    // ------------------------------------------------------------------

    private fun internalForm(op: Int): String = when (op) {
        Opcode._NOP, Opcode._HALT -> ""
        Opcode._SET_INT -> "R0, 42"
        Opcode._SET_STR -> "R0, \"s\""
        Opcode._MOV_INT, Opcode._MOV_STR, Opcode._NEG, Opcode._NOT -> "R0, R1"
        Opcode._ADD, Opcode._SUB, Opcode._MUL, Opcode._DIV, Opcode._MOD,
        Opcode._EQ, Opcode._NE, Opcode._LT, Opcode._LE, Opcode._GT, Opcode._GE,
        -> "R0, R1, R2"
        Opcode._JUMP -> "0"
        Opcode._JUMP_IF_FALSE, Opcode._JUMP_IF_TRUE -> "R0, 0"
        Opcode._CALL -> "#0, 1, R0"
        Opcode._RET -> "R0"
        Opcode._PRINT_INT, Opcode._PRINT_STR -> "R0, 1"
        Opcode._PRINT_LITERAL -> "\"x\", 1"
        Opcode._EVAL_INT, Opcode._EVAL_STR -> "\$0, R0"
        else -> error("内部指令缺形态: $op ${Opcode.nameOf(op)}")
    }

    private fun extensionForm(op: Int): String = when (op) {
        Opcode._CALL_BY_NAME -> "R0, 1, R1"
        Opcode._JUMP_BY_NAME -> "R0, 0, none"
        Opcode._JUMP_DYN -> "0"
        Opcode._RETS -> "R0"
        else -> error("扩展指令缺形态: $op ${Opcode.nameOf(op)}")
    }

    /**
     * 栈码组（202..243）的逐条形态：空串 = 无操作数。
     *
     * 这里**逐条列全**（不写 `else -> ""` 的兜底）：新增栈码却忘了配形态时要当场炸，
     * 而不是悄悄按「无操作数」往返 —— 后者会让带操作数的栈码在反汇编里丢字（往返假绿）。
     */
    private fun stackForm(op: Int): String = when (op) {
        Opcode._STK_PUSH_IMM -> "42"
        Opcode._STK_PUSH_STR -> "\"s\""
        Opcode._STK_PUSH_VAR_I, Opcode._STK_PUSH_VAR_S,
        Opcode._STK_STORE_VAR_I, Opcode._STK_STORE_VAR_S,
        -> "\"A\", 1"
        Opcode._STK_PLUS_VAR_I -> "\"A\", 2, -1"
        Opcode._STK_JZ_POP, Opcode._STK_JNZ_POP, Opcode._STK_JZ_KEEP -> "0"
        Opcode._STK_POP_SLOT_I, Opcode._STK_POP_SLOT_S -> "R0"
        Opcode._STK_DUP_I, Opcode._STK_DROP, Opcode._STK_DROP_S,
        Opcode._STK_ADD, Opcode._STK_SUB, Opcode._STK_MUL, Opcode._STK_DIV, Opcode._STK_MOD,
        Opcode._STK_NEG, Opcode._STK_BITAND, Opcode._STK_BITOR, Opcode._STK_BITXOR,
        Opcode._STK_SHL, Opcode._STK_SHR, Opcode._STK_BITNOT,
        Opcode._STK_EQ, Opcode._STK_NE, Opcode._STK_LT, Opcode._STK_LE, Opcode._STK_GT,
        Opcode._STK_GE, Opcode._STK_SEQ, Opcode._STK_SNE, Opcode._STK_SLT, Opcode._STK_SLE,
        Opcode._STK_SGT, Opcode._STK_SGE, Opcode._STK_CONCAT, Opcode._STK_SREPEAT,
        Opcode._STK_I2S,
        -> ""
        else -> error("栈码缺形态: $op ${Opcode.nameOf(op)}")
    }

    @Test
    fun `全部 244 个操作码逐条往返字节级恒等`() {
        assertEquals(244, Opcode.NAMES.size, "操作码表大小漂移，遍历范围要跟着改")
        assertEquals(Opcode.MAX_OPCODE, Opcode.NAMES.size - 1)
        val exprs = listOf<Expr>(IntLiteral(1L))
        for (op in Opcode.NAMES.indices) {
            val name = Opcode.NAMES[op]
            val form = when {
                Opcode.isInternal(op) -> internalForm(op)
                Opcode.isStackOp(op) -> stackForm(op)
                Opcode.isExtension(op) -> extensionForm(op)
                else -> null
            }
            if (form != null) {
                assertRoundTrip("$name $form\n_HALT", exprs)
            } else {
                // 169 条 ERB 语句：三种立即数形态各自往返（反汇编恒打三字，回编按立即数）
                assertRoundTrip("$name 1, 2, 3\n_HALT")
                assertRoundTrip("$name\n_HALT")
                assertRoundTrip("$name -1, 0, -1\n_HALT")
            }
        }
    }

    // ------------------------------------------------------------------
    // 操作数形态矩阵（一个程序覆盖全部形态）
    // ------------------------------------------------------------------

    @Test
    fun `操作数形态矩阵往返恒等`() {
        val stmts = listOf<Statement>(
            InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL 0"),
            InstructionStmt(FunctionCode.SET, VoidArgument(), 2, "SET COUNT, 0"),
        )
        assertRoundTrip(
            """
            .func main slots=8
            top:
              _SET_INT R0, -9223372036854775808
              _SET_INT R1, 9223372036854775807
              _SET_INT R2, 0
              _SET_INT R3, -1
              _SET_INT R6, 42
              _SET_INT R7, 42
              _SET_STR R4, ""
              _SET_STR R5, "引号\"反斜杠\\换行\n制表\t回车\r分号;逗号,"
              _MOV_INT R6, G0
              _MOV_STR R7, G1
              _ADD G2, R0, R1
              _SUB G3, G2, none
              _MUL none, R0, R1
              _DIV R0, R1, G4
              _MOD R1, R2, G5
              _NEG R2, R0
              _NOT R3, R1
              _EQ R4, R0, R1
              _NE R5, R0, R1
              _LT R6, R0, R1
              _LE R7, R0, R1
              _GT G6, R0, R1
              _GE G7, R0, R1
              _JUMP top
              _JUMP_IF_FALSE R0, fwd
              _JUMP_IF_TRUE R0, top
            fwd:
              _JUMP 0
              _JUMP -1
              _CALL #0, 1, R0
              _CALL helper, 2, none
              _CALL helper
              _CALL_BY_NAME R0, -2147483648, none
              _CALL_BY_NAME R0, 1073741825, R1
              _JUMP_BY_NAME R0, -2147483648
              _JUMP_BY_NAME R0, 0, R1
              _JUMP_DYN 3
              _RET R0
              _RET
              _RETS R0
              _RETS
              _PRINT_INT R0, 1
              _PRINT_INT R0
              _PRINT_STR R1, 0
              _PRINT_STR R1
              _PRINT_LITERAL "lit"
              _PRINT_LITERAL "lit", 1
              _EVAL_INT ${'$'}0, R0
              _EVAL_STR ${'$'}1, R1
              PRINTL stmt#0
              SET stmt#1
              PRINT 1, 2, 3
              ENDIF
              SET -1, 0, -1
              _NOP
              _HALT

            .func helper arity=2 slots=4
              _RET R0
            """,
            exprs = listOf(IntLiteral(7L), IntLiteral(8L)),
            statements = stmts,
        )
    }

    @Test
    fun `常量池去重与负立即数在往返后保持同序同值`() {
        // 同一常量两次出现只占一个池条目；Long.MIN/MAX 与 -1 各自独立
        val p1 = Assembler.assemble(
            """
            _SET_INT R0, -9223372036854775808
            _SET_INT R1, 9223372036854775807
            _SET_INT R2, 42
            _SET_INT R3, 42
            _SET_INT R4, -1
            _SET_INT G0, 0
            _HALT
            """.trimIndent(),
        )
        val p2 = Assembler.assemble(p1.disassemble(), p1.expressions(), p1.statements())
        assertEquals(p1.code.toList(), p2.code.toList())
        assertEquals(5, p2.constantCount, "42 只应占一个池条目（期望 5 实际 ${'$'}{p2.constantCount}）")
        assertEquals(42L, p2.constant(p2.b(2)))
        assertEquals(42L, p2.constant(p2.b(3)), "两条 42 必须指向同一池下标")
        assertEquals(p2.b(2), p2.b(3))
    }

    // ------------------------------------------------------------------
    // 往返差异回归（本轮修复的真 bug）
    // ------------------------------------------------------------------

    @Test
    fun `共享 entryPc 的空函数全部打印且往返不丢函数`() {
        // 旧实现 funcAt 是 HashMap<entryPc, FunctionInfo>：empty 被 main 覆写，
        // asm(disasm(p)) 只剩 main（函数表从 2 条变 1 条）。
        val p1 = Assembler.assemble(
            """
            .func empty slots=1
            .func main slots=2
              _SET_INT R0, 1
              _HALT
            """.trimIndent(),
        )
        assertEquals(2, p1.functions.size)
        assertEquals(0, p1.functions[0].entryPc)
        assertEquals(0, p1.functions[1].entryPc, "两条函数共享入口 pc")

        val text = p1.disassemble()
        assertTrue(text.contains(".func empty"), "空函数 empty 未出现在反汇编文本:\n$text")
        assertTrue(text.contains(".func main"), "函数 main 未出现在反汇编文本:\n$text")

        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())
        assertEquals(
            p1.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            p2.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            "往返后函数表丢失: 期望 ${'$'}{p1.functions.map { it.name }} 实际 ${'$'}{p2.functions.map { it.name }}",
        )
        assertEquals(p1.code.toList(), p2.code.toList())
    }

    @Test
    fun `拖尾空函数在指令流末尾补打且往返不丢函数`() {
        // entryPc == instructionCount 的函数没有任何 pc 命中它，旧实现整条丢失。
        val p1 = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 1
              _HALT

            .func tailA slots=1

            .func tailB arity=1 slots=2
            """.trimIndent(),
        )
        assertEquals(3, p1.functions.size)
        val p2 = Assembler.assemble(p1.disassemble(), p1.expressions(), p1.statements())
        assertEquals(
            p1.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            p2.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            "往返后函数表丢失: 期望 ${'$'}{p1.functions.map { it.name }} 实际 ${'$'}{p2.functions.map { it.name }}",
        )
        assertEquals(p1.code.toList(), p2.code.toList())
        // 按名引用的函数（callFixup 路径）往返后下标不变
        val p3 = Assembler.assemble(
            """
            .func main slots=2
              _CALL tailB, 1, R0
              _HALT

            .func tailB arity=1 slots=2
              _RET R0
            """.trimIndent(),
        )
        val p4 = Assembler.assemble(p3.disassemble(), p3.expressions(), p3.statements())
        assertEquals(p3.code.toList(), p4.code.toList())
        assertEquals(1, p4.a(0), "_CALL 的函数下标应回填为 tailB")
    }

    @Test
    fun `跳转目标恰为指令流末尾时标签补打且往返恒等`() {
        // 目标 == instructionCount 的跳转没有任何 pc 命中它：旧实现丢标签，
        // 回编报「未定义的标签」。反汇编在指令流末尾补打 L<count>，偏移精确复原。
        val p1 = Assembler.assemble(
            """
            .func main slots=1
              _NOP
              _JUMP 1
              _HALT
            """.trimIndent(),
        )
        assertEquals(3, p1.instructionCount)
        assertEquals(3, 1 + 1 + p1.a(1), "跳转目标应恰为指令流末尾")
        val p2 = Assembler.assemble(p1.disassemble(), p1.expressions(), p1.statements())
        assertEquals(p1.code.toList(), p2.code.toList())
        assertTrue(p1.disassemble().contains("L3:"), "末尾标签未补打")
    }

    @Test
    fun `井号开头的函数名在声明期显式失败`() {
        // `_CALL #5` 是「下标 5」语法：名为 #5 的函数反汇编打回后会被回编成下标 5，
        // 静默换函数。声明期就失败，不让损坏下沉到字节码。
        val e = assertFailsWith<AssemblyException> {
            Assembler.assemble(".func #5 slots=1\n  _HALT")
        }
        assertTrue(e.message!!.contains("不能以 # 开头"), "实际文案: ${'$'}{e.message}")
    }

    @Test
    fun `带引号的函数名在声明期显式失败`() {
        val e = assertFailsWith<AssemblyException> {
            Assembler.assemble(".func \"a b\" slots=1\n  _HALT")
        }
        assertTrue(e.message!!.contains("不能是带引号"), "实际文案: ${'$'}{e.message}")
    }
}
