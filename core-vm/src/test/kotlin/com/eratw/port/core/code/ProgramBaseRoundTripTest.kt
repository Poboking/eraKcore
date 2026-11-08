package com.eratw.port.core.code

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R7 波 B（L8）：`ProgramBase` 把「带基线的 [Program]」这条链的两端接上 ——
 * 写侧 [ErbcWriter] 能落盘、读侧 [ErbcReader] 能带着基线构造。
 *
 * 判据分四组：
 *
 * 1. **零基线逐位等价**：不传基线时产物与装载结果必须与改造前完全相同（`ProgramBase.NONE`
 *    的六个 0 让所有折算恒等）。两次 `encode` 逐字节相同锁的是编码器的确定性。
 * 2. **带基线写读往返**：六个基线都非 0 的续段程序 `build()` 不许抛；读回的绝对计数、
 *    窗口起点、逐条指令、函数表都与写侧一致。
 * 3. **本段口径**：补丁体单独解码（不传基线）必须被池下标判界拒绝 —— 这证明产物里只有
 *    **本段**条目，基线只进补丁头。
 * 4. **负向**：基线本身非法（非对齐 / 负值）、行号表长度与本段条数不符，都必须当场抛错并点名。
 */
class ProgramBaseRoundTripTest {

    // ==================================================================
    // 1. 零基线：逐位等价 + 确定性
    // ==================================================================

    @Test
    fun `零基线写读逐位等价且两次编码逐字节相同`() {
        val before = zeroBaseProgram()
        val bytes1 = ErbcWriter.encode(before)
        val bytes2 = ErbcWriter.encode(before)
        assertTrue(bytes1.contentEquals(bytes2), "同一 Program 两次 encode 必须逐字节相同")

        val after = ErbcReader.decode(bytes1)
        assertEquals(0, after.instructionBase, "不传基线时窗口起点必须是 0")
        assertEquals(before.instructionCount, after.instructionCount)
        assertTrue(before.code.contentEquals(after.code), "指令流逐字不同")
        assertTrue(before.lines.contentEquals(after.lines), "行号表逐字不同")
        assertEquals(before.constantCount, after.constantCount, "常量池计数")
        assertEquals(before.stringCount, after.stringCount, "字符串池计数")
        assertEquals(before.exprCount, after.exprCount, "表达式池计数")
        assertEquals(before.statementCount, after.statementCount, "语句池计数")
        assertEquals(before.functions.size, after.functions.size, "函数表条数")
        assertEquals(before.entryFunction, after.entryFunction, "入口函数下标")
        assertEquals(before.globalSlots, after.globalSlots, "全局槽数")
    }

    // ==================================================================
    // 2. 带基线：写读往返（绝对口径逐项相等）
    // ==================================================================

    @Test
    fun `带基线续段写读往返 计数与指令逐条相等`() {
        val before = segmentProgram()
        val bytes = ErbcWriter.encode(before)
        val after = ErbcReader.decode(bytes, ProgramBase(CODE_BASE, FUNC_BASE, STMT_BASE, STR_BASE, LONG_BASE, EXPR_BASE))

        assertEquals(CODE_BASE / Instruction.WORDS, after.instructionBase, "instructionBase 必须等于 codeBase / WORDS")
        assertEquals(before.instructionCount, after.instructionCount, "instructionCount（绝对口径）")
        assertEquals(before.statementCount, after.statementCount, "statementCount（绝对口径）")
        assertEquals(before.stringCount, after.stringCount, "stringCount（绝对口径）")
        assertEquals(before.constantCount, after.constantCount, "constantCount / longCount（绝对口径）")
        assertEquals(before.exprCount, after.exprCount, "exprCount（绝对口径）")

        // 逐条指令：绝对 pc 空间上第 k 条（读面按 instructionBase 折回窗口）
        val segmentCount = before.codeSource.wordCount / Instruction.WORDS
        for (k in 0 until segmentCount) {
            val pc = after.instructionBase + k
            assertEquals(before.op(pc), after.op(pc), "pc=$pc 的 op")
            assertEquals(before.a(pc), after.a(pc), "pc=$pc 的 a")
            assertEquals(before.b(pc), after.b(pc), "pc=$pc 的 b")
            assertEquals(before.c(pc), after.c(pc), "pc=$pc 的 c")
        }
        assertTrue(before.code.contentEquals(after.code), "本段指令字逐字不同")
        assertTrue(before.lines.contentEquals(after.lines), "本段行号表逐字不同")

        // 函数表只含本段新增条目；entryFunction 是**表内序号**（CodeBuilder.build 已折回），
        // 绝对函数下标 = functionBase + entryFunction。
        assertEquals(2, after.functions.size, "函数表只应含本段新增的两个函数")
        assertEquals(before.entryFunction, after.entryFunction, "entryFunction（表内序号）")
        assertEquals(FUNC_BASE, after.functionBase, "functionBase 必须原样带回")
        assertEquals(FUNC_BASE, after.functionBase + after.entryFunction, "绝对入口函数下标")
        assertEquals("mod_main", after.functions[after.entryFunction].name, "入口函数名")
        // 本段函数表里 entryPc 是绝对 pc
        assertEquals(before.instructionBase, after.functions[after.entryFunction].entryPc, "入口函数 entryPc 是绝对 pc")
        // 调用点实参表（段 7）的 pc 也是绝对口径
        assertEquals(setOf(before.instructionBase + 4), after.callArgs.keys, "调用点 pc 应为绝对 pc")
    }

    // ==================================================================
    // 3. 产物里只有本段：补丁体脱离基线单独解码必须被拒
    // ==================================================================

    @Test
    fun `补丁体不带基线单独解码会被池下标判界拒绝`() {
        val bytes = ErbcWriter.encode(segmentProgram())
        // 产物声明的池条数都是 1（本段），而指令里的池下标是绝对口径（30 / 50 / 70 / 100），
        // 不带基线解码时必然越界 —— 这正是「基线只进补丁头」的可观测证据。
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes) }
        assertTrue(e.message!!.contains("越界"), e.message)
    }

    // ==================================================================
    // 4. 负向：基线非法
    // ==================================================================

    @Test
    fun `ProgramBase 拒绝非对齐 codeBase 与负值并点名参数`() {
        val misaligned = assertFailsWith<IllegalArgumentException> { ProgramBase(codeBase = 4095) }
        assertTrue(misaligned.message!!.contains("codeBase"), misaligned.message)
        assertTrue(misaligned.message!!.contains("4095"), misaligned.message)

        val cases = listOf<Pair<String, () -> ProgramBase>>(
            "codeBase" to { ProgramBase(codeBase = -Instruction.WORDS) },
            "functionBase" to { ProgramBase(functionBase = -1) },
            "statementBase" to { ProgramBase(statementBase = -1) },
            "stringBase" to { ProgramBase(stringBase = -1) },
            "longBase" to { ProgramBase(longBase = -1) },
            "exprBase" to { ProgramBase(exprBase = -1) },
        )
        for ((name, build) in cases) {
            val e = assertFailsWith<IllegalArgumentException>("$name 为负时必须抛错") { build() }
            assertTrue(e.message!!.contains(name), "报错必须点名 $name，实为: ${e.message}")
        }
    }

    // ==================================================================
    // 5. 负向：行号表长度按本段口径判界
    // ==================================================================

    @Test
    fun `带基线时行号表长度与本段条数不符则拒写并报出两个数`() {
        val program = mismatchLinesProgram()
        val e = assertFailsWith<ErbcEncodeException> { ErbcWriter.encode(program) }
        // 本段 3 条指令、行号表 2 条；绝对口径的 instructionCount 是 1027，不许出现在这条消息里当分母。
        assertTrue(e.message!!.contains("行号表长度(2)"), e.message)
        assertTrue(e.message!!.contains("本段指令条数(3)"), e.message)
    }

    // ==================================================================
    // 样题构造
    // ==================================================================

    /** 零基线小程序：一个函数、四个池各一条、三种池引用指令。 */
    private fun zeroBaseProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), arity = 1, slotCount = 3)
        val c = b.constant(7L)
        val s = b.string("zero-base")
        val e = b.expr(IntLiteral(1, 0, 0))
        val st = b.statement(RendStmt(VoidArgument(), 0, "REND"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._EVAL_INT, e, SlotAddr.local(2), 0, 3)
        b.emit(Opcode.DATA, st, 0, SlotAddr.NONE, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)
        b.setFunctionSlots(main, 3)
        return b.build(main)
    }

    /**
     * 带基线续段程序：六个基线全非 0，含 `_CALL`（绝对函数下标）、`$标签`（绝对 pc）、
     * 段 7 调用点（绝对 pc）三种「绝对下标」形态。
     */
    private fun segmentProgram(): Program {
        val b = CodeBuilder(
            codeBase = CODE_BASE,
            statementBase = STMT_BASE,
            stringBase = STR_BASE,
            longBase = LONG_BASE,
            exprBase = EXPR_BASE,
            functionBase = FUNC_BASE,
        )
        val main = b.declareFunction("mod_main", b.here(), arity = 1, slotCount = 3)
        val c = b.constant(7L)
        val s = b.string("mod-str")
        val e = b.expr(IntLiteral(1, 0, 0))
        val st = b.statement(RendStmt(VoidArgument(), 0, "REND"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._EVAL_INT, e, SlotAddr.local(2), 0, 3)
        b.emit(Opcode.DATA, st, 0, SlotAddr.NONE, 4)
        val callPc = b.emit(Opcode._CALL, 0, 0, SlotAddr.NONE, 5)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        val sub = b.declareFunction("mod_sub", b.here(), arity = 0, slotCount = 1)
        b.patch(callPc, 1, sub)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 7)
        b.declareLabel(main, "TAIL", callPc)
        b.declareCallArgs(callPc, listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "LV", present = true)))
        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(sub, 1)
        return b.build(main)
    }

    /** 直构 Program（internal 构造器）：带基线、行号表比本段少一条 —— 只有写侧能拒绝它。 */
    private fun mismatchLinesProgram(): Program {
        val code = IntArray(3 * Instruction.WORDS)
        code[0] = Opcode._HALT
        return Program(
            code = code,
            lines = IntArray(2) { 1 },
            pool = ConstantPool(
                longBase = LONG_BASE,
                stringBase = STR_BASE,
                exprBase = EXPR_BASE,
                statementBase = STMT_BASE,
            ),
            functions = listOf(FunctionInfo("main", CODE_BASE / Instruction.WORDS, 0, 1)),
            entryFunction = 0,
            globalSlots = 0,
            codeBase = CODE_BASE,
            statementBase = STMT_BASE,
            stringBase = STR_BASE,
            longBase = LONG_BASE,
            exprBase = EXPR_BASE,
            functionBase = FUNC_BASE,
        )
    }

    private companion object {
        const val CODE_BASE = 4096
        const val FUNC_BASE = 10
        const val STMT_BASE = 100
        const val STR_BASE = 50
        const val LONG_BASE = 30
        const val EXPR_BASE = 70
    }
}
