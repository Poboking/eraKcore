package com.eratw.port.core.compile

import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 契约 R10/R11 在**编译器产物层**的发射语义锁。
 *
 * 断言风格：按指令模式（opcode + 操作数位）扫描，不锁死 pc；只有两处需要 pc
 * （RESTART / TRYGOTOLIST 的 `_JUMP` 目标）按 VM 换算 `pc + 1 + a` 现场计算
 * —— `_JUMP` 的偏移在操作数 `a`（Vm.kt:385 `_JUMP -> pc = pc + 1 + a`），
 * `pc + 1 + b` 是 `_JUMP_IF_FALSE/_JUMP_IF_TRUE` 的口径。
 *
 * 被锁语义：
 * - R10：`JUMP`（静态函数名）→ `_CALL` 的 b bit30=1（VM 走 jumpInto 尾跳），
 *   `CALL` → bit30=0；`RESTART` → 同帧回跳函数 entryPc 的 `_JUMP`；
 *   `RETURN` 只入语句池、不再发 `_RET`；TRY{CALL,JUMP}LIST 只发射第一个已知目标，
 *   bit30 由语句种类（JUMP 系 / CALL 系）决定。
 * - R11：`CALLFORM` 动态名不置 `_CALL_BY_NAME` 的 bit29；`CALLFORMF` 置 bit29。
 */
class JumpCompileTest {

    // 与 ErbProgramCompiler.kt:1613-1622（file-private）及 Vm.kt:769-779 同口径。
    private companion object {
        const val JUMP_BIT = 0x40000000
        const val CALLF_BIT = 0x20000000
        const val ARG_COUNT_MASK = 0x1FFFFFFF

        /** isa 语句操作码上界：29..197 为语句；198/199 是编译器扩展（BY_NAME/DYN）。 */
        const val LAST_STATEMENT_OPCODE = 197
    }

    // ------------------------------------------------------------------
    // 基础设施（同 ErbProgramCompilerTest 的 parse/compile 口径）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("RESULT", OperandType.INT, 0)
            .declareFunction("MYFUNC", OperandType.INT)
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

    private fun compileOne(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse(*lines))
        return c.build()
    }

    /** 单函数编译并保留 [FunctionBytecode]（entryPc/endPc/labels 供 pc 换算断言）。 */
    private fun compileUnit(vararg lines: String, isMethod: Boolean = false): Pair<Program, FunctionBytecode> {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("TEST", isMethod = isMethod), parse(*lines))
        return c.build() to fb
    }

    private fun compileFuncs(vararg funcs: Pair<String, Array<String>>): Program {
        val c = ErbProgramCompiler()
        val known = funcs.map { it.first.uppercase() }.toSet()
        for ((name, lines) in funcs) c.compileFunction(FuncSig(name), parse(*lines), known)
        return c.build(funcs.first().first)
    }

    /**
     * TRY*LIST 双函数编译：`known` 只含 `GOLDEN_LISTCALLED`（`GOLDEN_NOFUNC_C` 不存在）。
     * 若编译器对 miss 目标也发射 `_CALL`，[ErbProgramCompiler.build] 会以"未定义的函数"抛错。
     */
    private fun compileTryListProgram(opener: String): Program {
        val known = setOf("GOLDEN_LISTCALLED")
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("TEST"),
            parse(
                opener,
                "FUNC GOLDEN_NOFUNC_C",
                "FUNC GOLDEN_LISTCALLED, 5",
                "ENDFUNC",
                "RETURN 6",
            ),
            known,
        )
        c.compileFunction(FuncSig("GOLDEN_LISTCALLED"), parse("RETURN 11"), known)
        return c.build("TEST")
    }

    private fun instructions(p: Program): List<Instruction> =
        (0 until p.instructionCount).map { p.instructionAt(it) }

    private fun indexOfFunc(p: Program, name: String): Int =
        p.functions.indexOfFirst { it.name == name }

    private fun onlyCall(p: Program): Instruction {
        val calls = instructions(p).filter { it.op == Opcode._CALL }
        assertEquals(1, calls.size, "期望恰好一条 _CALL，实际 ${calls.size}")
        return calls.single()
    }

    private fun onlyCallByName(p: Program): Instruction {
        val calls = instructions(p).filter { it.op == Opcode._CALL_BY_NAME }
        assertEquals(1, calls.size, "期望恰好一条 _CALL_BY_NAME，实际 ${calls.size}")
        return calls.single()
    }

    /** `_JUMP` 目标 pc：Vm.kt:385 `_JUMP -> pc = pc + 1 + a`。 */
    private fun jumpTarget(p: Program, pc: Int): Int {
        val ins = p.instructionAt(pc)
        assertEquals(Opcode._JUMP, ins.op, "pc $pc 不是 _JUMP")
        return pc + 1 + ins.a
    }

    private fun isStatementMarker(ins: Instruction): Boolean =
        ins.op in Opcode.INTERNAL_COUNT..LAST_STATEMENT_OPCODE

    // ------------------------------------------------------------------
    // R10：JUMP = 尾跳（_CALL bit30），CALL = 常规调用
    // ------------------------------------------------------------------

    @Test
    fun `JUMP 静态函数名发射尾跳 _CALL 而 CALL 不置位`() {
        val jump = compileFuncs(
            "TEST" to arrayOf("JUMP TARGET, 7, 8"),
            "TARGET" to arrayOf("RETURN"),
        )
        val j = onlyCall(jump)
        assertEquals(indexOfFunc(jump, "TARGET"), j.a, "_CALL 的 a = TARGET 的函数下标")
        assertEquals(2, j.b and ARG_COUNT_MASK, "argc 低位 = 实参数（2）")
        assertEquals(JUMP_BIT, j.b and JUMP_BIT, "JUMP → _CALL bit30 = 1（VM 走 jumpInto）")

        val call = compileFuncs(
            "TEST" to arrayOf("CALL TARGET, 7, 8"),
            "TARGET" to arrayOf("RETURN"),
        )
        val c = onlyCall(call)
        assertEquals(indexOfFunc(call, "TARGET"), c.a, "_CALL 的 a = TARGET 的函数下标")
        assertEquals(2, c.b and ARG_COUNT_MASK, "argc 低位 = 实参数（2）")
        assertEquals(0, c.b and JUMP_BIT, "CALL → _CALL bit30 = 0（不加帧的常规调用）")
    }

    // ------------------------------------------------------------------
    // R11：CALLFORMF 家族置 _CALL_BY_NAME 的 bit29
    // ------------------------------------------------------------------

    @Test
    fun `CALLFORM 动态名不置 CALLF 位而 CALLFORMF 置位`() {
        val plain = compileOne("CALLFORM GOLDEN_FF{1+1}")
        val byName = onlyCallByName(plain)
        assertEquals(0, byName.b and CALLF_BIT, "CALLFORM 不置 bit29")
        assertEquals(0, byName.b, "无实参、非 try、非尾跳 → b 全 0")
        assertTrue(SlotAddr.isNone(byName.c), "非 TRYC 的 CALLFORM 不占标志槽")

        val callf = compileOne("CALLFORMF GOLDEN_FF{1+1}, 4")
        val byNameF = onlyCallByName(callf)
        assertEquals(CALLF_BIT, byNameF.b and CALLF_BIT, "CALLFORMF 置 bit29 = 0x20000000")
        assertEquals(1, byNameF.b and ARG_COUNT_MASK, "argc = 1")
        assertEquals(0, byNameF.b and JUMP_BIT, "CALLFORMF 不置 bit30")
        assertTrue(byNameF.b >= 0, "bit31（try 位）必须为 0")
    }

    // ------------------------------------------------------------------
    // R10：RESTART 同帧回跳函数入口
    // ------------------------------------------------------------------

    @Test
    fun `RESTART 同帧回跳函数入口`() {
        val (p, fb) = compileUnit("PRINTL \"A\"", "RESTART", "PRINTL \"B\"")
        val jumps = (fb.entryPc until fb.endPc)
            .filter { p.instructionAt(it).op == Opcode._JUMP }
        assertEquals(1, jumps.size, "RESTART 只发射一条 _JUMP，实际 ${jumps.size}")
        assertEquals(fb.entryPc, jumpTarget(p, jumps.single()), "回跳目标 = 函数 entryPc")
    }

    // ------------------------------------------------------------------
    // R10：RETURN 只入语句池（不再发 _RET）；RETURNF 对照组
    // ------------------------------------------------------------------

    @Test
    fun `RETURN 只入语句池不再发射 _RET`() {
        val (p, fb) = compileUnit("RETURN 10, 20")
        val markers = (fb.entryPc until fb.endPc - 1)
            .map { p.instructionAt(it) }
            .filter { isStatementMarker(it) && it.a in 0 until p.statementCount }
        val ret = assertNotNull(
            markers.firstOrNull { p.statement(it.a) is ReturnStmt },
            "RETURN 必须有语句池 marker",
        )
        assertTrue(ret.op !in 0 until Opcode.INTERNAL_COUNT, "marker 操作码不得是 0..28 的内部指令")
        assertEquals(Opcode.RETURN, ret.op, "marker 操作码 = 语句操作码 RETURN")

        // compileFunction 在函数尾无条件追加一条兜底 _RET（ErbProgramCompiler.kt:190），
        // 故"函数体"取 [entryPc, endPc-1)；RETURN 的返回值不得在体内经 _RET 发射。
        val bodyRets = (fb.entryPc until fb.endPc - 1)
            .filter { p.instructionAt(it).op == Opcode._RET }
        assertTrue(bodyRets.isEmpty(), "RETURN 不得发射 _RET（实际 ${bodyRets.size} 条）")
        assertEquals(Opcode._RET, p.instructionAt(fb.endPc - 1).op, "函数尾兜底 _RET 保留")
    }

    @Test
    fun `RETURNF 仍发射带返回值槽的 _RET`() {
        // CMP-31：RETURNF 只在 #FUNCTION/#FUNCTIONS 内合法 ⇒ 本用例按方法编译
        // （isMethod 不参与发射，只做装载期形态校验）。
        val (p, fb) = compileUnit("RETURNF 42", isMethod = true)
        val bodyRets = (fb.entryPc until fb.endPc - 1)
            .filter { p.instructionAt(it).op == Opcode._RET }
        assertEquals(1, bodyRets.size, "RETURNF 必须在函数体内发射一条 _RET")
        val ret = p.instructionAt(bodyRets.single())
        assertFalse(SlotAddr.isNone(ret.a), "RETURNF 的 _RET 必须携带返回值槽")
    }

    // ------------------------------------------------------------------
    // R10-D：TRY{CALL,JUMP}LIST 只发射第一个已知目标
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALLLIST 只调用首个已知目标且不置尾跳位`() {
        val p = compileTryListProgram("TRYCALLLIST")
        // calls.size == 1 同时锁住"不存在指向 GOLDEN_NOFUNC_C 的调用"：
        // 该目标名不在 known/函数表里，任何命中它的 _CALL 都会在 build() 时抛"未定义的函数"。
        val call = onlyCall(p)
        assertEquals(indexOfFunc(p, "GOLDEN_LISTCALLED"), call.a, "命中第一个已知目标")
        assertEquals(1, call.b and ARG_COUNT_MASK, "argc = 1（FUNC …, 5）")
        assertEquals(0, call.b and JUMP_BIT, "TRYCALLLIST → 常规调用（bit30 = 0）")
        assertTrue(call.b >= 0, "TRY 位不用于 TRY*LIST 的 _CALL")
    }

    @Test
    fun `TRYJUMPLIST 命中目标置尾跳位`() {
        val p = compileTryListProgram("TRYJUMPLIST")
        val call = onlyCall(p)
        assertEquals(indexOfFunc(p, "GOLDEN_LISTCALLED"), call.a, "命中第一个已知目标")
        assertEquals(1, call.b and ARG_COUNT_MASK, "argc = 1（FUNC …, 5）")
        assertEquals(JUMP_BIT, call.b and JUMP_BIT, "TRYJUMPLIST → 尾跳（bit30 = 1）")
    }

    // ------------------------------------------------------------------
    // R10-D：TRYGOTOLIST 只认标签表（同函数绝对跳转，不是调用）
    // ------------------------------------------------------------------

    @Test
    fun `TRYGOTOLIST 命中标签表发射同函数 _JUMP`() {
        val (p, fb) = compileUnit(
            "TRYGOTOLIST",
            "FUNC \$TAIL",
            "ENDFUNC",
            "PRINTL \"NEVER\"",
            "\$TAIL",
            "PRINTL \"DONE\"",
        )
        assertTrue(instructions(p).none { it.op == Opcode._CALL }, "TRYGOTOLIST 不得发射 _CALL")
        val jumps = (fb.entryPc until fb.endPc)
            .filter { p.instructionAt(it).op == Opcode._JUMP }
        assertEquals(1, jumps.size, "只发射命中标签的那条 _JUMP，实际 ${jumps.size}")

        val labelPc = assertNotNull(fb.labels["TAIL"], "标签 TAIL 必须登记进函数标签表")
        assertEquals(labelPc, jumpTarget(p, jumps.single()), "目标 pc = \$TAIL 的标签 pc")
        val target = p.instructionAt(labelPc)
        assertTrue(isStatementMarker(target), "跳转目标是语句 marker 而非内部指令")
        assertEquals("PRINTL \"DONE\"", p.statement(target.a).raw, "落到 \$TAIL 之后的第一行")
    }
}
