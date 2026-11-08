package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.RuntimeCallResolution
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CALLSTR 族（`TRYCALLSTR`/`TRYCCALLSTR`）运行期字符串调用通道的定向用例 ——
 * WIRE-1/WIRE-2，契约 C-CALL-STR-OPCODE@1（操作码 `_CALL_STR=245`）与
 * C-CALL-STR-HOST@1（`VmHost.resolveRuntimeCall`/`onRuntimeCallArgs`）。
 *
 * 覆盖：
 * 1. [ProcessRuntime.resolveRuntimeCall] 的整串解析 + 逐位求值（R1-AC1..R1-AC4）；
 * 2. VM `_CALL_STR` 分派：实参入槽、按名调用、返回值入 c 槽、TRY/TRYC 降级；
 * 3. `_CALL_STR` 的汇编/反汇编往返与 erbc 编码/解码/装载往返（R1-AC5）；
 * 4. `ErbProgramCompiler.compileJumpLike` 的 CALLSTR 分支发射形态（TRYCALLSTR 单条、
 *    TRYCCALLSTR 两步式）。
 *
 * 字节码用手工 [CodeBuilder] 直发（沿用 [CallFByNameTest] 的既定路线）：`_CALL_STR` 的
 * b 标志位 Assembler 表达不了字面量组合。
 *
 * 语义依据 `specs/isa.yaml:2480`（CALLS 族）；fork-src `Instraction.Child.cs:3950-4066`
 * 本机缺失 ⇒ TODO(verify)：待 fork-src 就位后复核。
 */
class CallStrRuntimeTest {

    private companion object {
        /** Vm.kt 的 `Opcode.TRY_FLAG`（b 操作数 bit31）字面量。 */
        const val TRY_FLAG = Int.MIN_VALUE
    }

    // ------------------------------------------------------------------
    // 构建工具
    // ------------------------------------------------------------------

    private fun rt(program: Program, metas: List<FuncMeta> = emptyList()): ProcessRuntime =
        ProcessRuntime(program, VariableStore(), FunctionTable(metas))

    private fun ProcessRuntime.runAndOutput(): String {
        execute()
        return output.toString()
    }

    /**
     * main（pc0）：
     * ```
     * _SET_STR R5, <整串原文>
     * _CALL_STR R5, bFlags, cSlot
     * <after()>
     * _HALT
     * ```
     * 随后可选的被调函数（`_RET NONE` 收尾，防止掉进未定义区域）。
     */
    private fun callProgram(
        raw: String,
        bFlags: Int,
        cSlot: Int,
        calleeName: String? = null,
        calleeArity: Int = 0,
        calleeSlots: Int = 0,
        calleeBody: (CodeBuilder.() -> Unit)? = null,
        after: CodeBuilder.() -> Unit = {},
    ): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 8)
        b.emit(Opcode._SET_STR, SlotAddr.local(5), b.string(raw), 0, 1)
        b.emit(Opcode._CALL_STR, SlotAddr.local(5), bFlags, cSlot, 2)
        b.after()
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        if (calleeName != null && calleeBody != null) {
            val pc = b.here()
            b.declareFunction(calleeName, pc, calleeArity, calleeSlots)
            b.calleeBody()
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        }
        return b.build(0)
    }

    private fun calleeMeta(program: Program, name: String, argInt: Int = 0): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $name 不在程序中")
        return FuncMeta(index = idx, key = name.uppercase(), name = name, argIntCount = argInt, defIndex = idx)
    }

    /** 单条 `_RET NONE` 的 @函数体，用于只关心解析结果的程序。 */
    private fun parseProgram(vararg fns: Pair<String, Int>): Program {
        val b = CodeBuilder()
        for ((name, arity) in fns) {
            val pc = b.here()
            b.declareFunction(name, pc, arity, maxOf(arity, 1))
            b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 1)
        }
        return b.build(0)
    }

    private fun metasOf(p: Program, vararg fns: Pair<String, Int>): List<FuncMeta> = fns.map { (n, a) ->
        val idx = p.functions.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        FuncMeta(index = idx, key = n.uppercase(), name = n, argIntCount = a, defIndex = idx)
    }

    // ------------------------------------------------------------------
    // 1. resolveRuntimeCall：整串解析 + 逐位求值
    // ------------------------------------------------------------------

    private fun ready(raw: String, vararg fns: Pair<String, Int>): RuntimeCallResolution.Ready {
        val p = parseProgram(*fns)
        val r = rt(p, metasOf(p, *fns)).resolveRuntimeCall(raw)
        assertTrue(r is RuntimeCallResolution.Ready, "期望 Ready，实际 $r（raw=$raw）")
        return r
    }

    @Test
    fun `NAME(int) 命中并求值出单个整型实参`() {
        val r = ready("FOO(3)", "FOO" to 1)
        assertEquals(0, r.targetIndex)
        assertEquals(1, r.args.size)
        assertTrue(!r.args[0].isStr)
        assertEquals(3L, r.args[0].intValue)
    }

    @Test
    fun `NAME(int, int) 逐位求值保序`() {
        val r = ready("BAR(1, 2)", "FOO" to 1, "BAR" to 2)
        assertEquals(1, r.targetIndex, "BAR 的函数下标应为 1")
        assertEquals(listOf(1L, 2L), r.args.map { it.intValue })
    }

    @Test
    fun `逗号分隔至行尾形态求值`() {
        val r = ready("FOO, 7", "FOO" to 1)
        assertEquals(1, r.args.size)
        assertEquals(7L, r.args[0].intValue)
    }

    @Test
    fun `裸名无实参形态命中且实参为空`() {
        val r = ready("FOO", "FOO" to 1)
        assertEquals(0, r.targetIndex)
        assertTrue(r.args.isEmpty())
    }

    @Test
    fun `字符串实参与整型实参按类型分桶`() {
        val r = ready("BAR(\"hi\", 2)", "BAR" to 2)
        assertEquals(2, r.args.size)
        assertTrue(r.args[0].isStr)
        assertEquals("hi", r.args[0].strValue)
        assertTrue(!r.args[1].isStr)
        assertEquals(2L, r.args[1].intValue)
    }

    @Test
    fun `空串与全空白折成 Blank`() {
        val p = parseProgram("FOO" to 1)
        val r = rt(p, metasOf(p, "FOO" to 1))
        assertTrue(r.resolveRuntimeCall("") is RuntimeCallResolution.Blank)
        assertTrue(r.resolveRuntimeCall("   ") is RuntimeCallResolution.Blank)
    }

    @Test
    fun `目标不存在折成 NotFound`() {
        val p = parseProgram("FOO" to 1)
        val r = rt(p, metasOf(p, "FOO" to 1))
        val res = r.resolveRuntimeCall("GHOST(1)")
        assertTrue(res is RuntimeCallResolution.NotFound)
        assertEquals("GHOST", (res as RuntimeCallResolution.NotFound).name, "NotFound 应携带解析后函数名（供文案）")
    }

    @Test
    fun `未闭合小括号折成 Failed`() {
        val p = parseProgram("FOO" to 1)
        val r = rt(p, metasOf(p, "FOO" to 1)).resolveRuntimeCall("FOO(1")
        assertTrue(r is RuntimeCallResolution.Failed, "期望 Failed，实际 $r")
        assertTrue(!r.message.isNullOrEmpty())
    }

    @Test
    fun `实参求值失败折成 Failed 且不抛出`() {
        val p = parseProgram("FOO" to 1)
        val r = rt(p, metasOf(p, "FOO" to 1)).resolveRuntimeCall("FOO(1/0)")
        assertTrue(r is RuntimeCallResolution.Failed, "期望 Failed，实际 $r")
    }

    // ------------------------------------------------------------------
    // 2. VM `_CALL_STR` 分派：入槽 / 调用 / 返回 / TRY / TRYC
    // ------------------------------------------------------------------

    @Test
    fun `命中时被调目标被调用且返回值写入调用方 c 槽`() {
        // FOO `_RET R0`：把（被调帧 R0 收到的）实参当返回值交回 c 槽 ⇒ 证明实参入槽 + 返回。
        val p = callProgram(
            raw = "FOO(5)", bFlags = 0, cSlot = SlotAddr.local(0),
            calleeName = "FOO", calleeArity = 1, calleeSlots = 1,
            calleeBody = { emit(Opcode._RET, SlotAddr.local(0), 0, 0, 6) },
            after = { emit(Opcode._PRINT_INT, SlotAddr.local(0), 1, 0, 3) },
        )
        assertEquals("5\n", rt(p, listOf(calleeMeta(p, "FOO", 1))).runAndOutput())
    }

    @Test
    fun `多实参按位置写入被调帧槽`() {
        val p = callProgram(
            raw = "FOO(3, 9)", bFlags = 0, cSlot = SlotAddr.NONE,
            calleeName = "FOO", calleeArity = 2, calleeSlots = 2,
            calleeBody = {
                emit(Opcode._PRINT_INT, SlotAddr.local(0), 0, 0, 6)
                emit(Opcode._PRINT_INT, SlotAddr.local(1), 1, 0, 6)
            },
        )
        assertEquals("39\n", rt(p, listOf(calleeMeta(p, "FOO", 2))).runAndOutput())
    }

    @Test
    fun `混合类型交错实参按各自类型桶保序写入`() {
        // 交错是区分「分桶序 / argc 口径」的关键：int 桶 = [3, 9]，str 桶 = ["a"]。
        // 若误用「位置号」当桶号、或把 argc 当桶数，会写错槽/少拷实参 ⇒ 本用例必红。
        val p = callProgram(
            raw = "FOO(3, \"a\", 9)", bFlags = 0, cSlot = SlotAddr.NONE,
            calleeName = "FOO", calleeArity = 3, calleeSlots = 3,
            calleeBody = {
                emit(Opcode._PRINT_INT, SlotAddr.local(0), 0, 0, 6)
                emit(Opcode._PRINT_STR, SlotAddr.local(0), 0, 0, 6)
                emit(Opcode._PRINT_INT, SlotAddr.local(1), 1, 0, 6)
            },
        )
        assertEquals("3a9\n", rt(p, listOf(calleeMeta(p, "FOO", 2))).runAndOutput())
    }

    @Test
    fun `字符串实参写入被调帧字符串桶`() {
        val p = callProgram(
            raw = "FOO(\"hi\")", bFlags = 0, cSlot = SlotAddr.NONE,
            calleeName = "FOO", calleeArity = 1, calleeSlots = 1,
            calleeBody = { emit(Opcode._PRINT_STR, SlotAddr.local(0), 1, 0, 6) },
        )
        assertEquals("hi\n", rt(p, listOf(calleeMeta(p, "FOO", 1))).runAndOutput())
    }

    @Test
    fun `非 TRY 目标缺失抛官方 NotDefinedFunc 文案`() {
        val p = callProgram(raw = "GHOST(1)", bFlags = 0, cSlot = SlotAddr.NONE)
        val e = assertFailsWith<CodeEE> { rt(p).execute() }
        // {0} = 解析后函数名（剥实参），对齐既有 `_CALL_BY_NAME` 的 "@$name"（Vm.kt:1302）。
        assertEquals("没有找到指定的函数\"@GHOST\"", e.message)
    }

    @Test
    fun `TRY 目标缺失静默跳过并继续执行下一条`() {
        val p = callProgram(
            raw = "GHOST(1)", bFlags = TRY_FLAG, cSlot = SlotAddr.NONE,
            after = { emit(Opcode._PRINT_LITERAL, string("AFTER"), 1, 0, 3) },
        )
        assertEquals("AFTER\n", rt(p).runAndOutput())
    }

    @Test
    fun `TRYC 命中时命中标志槽置 1`() {
        val p = callProgram(
            raw = "FOO(1)", bFlags = TRY_FLAG, cSlot = SlotAddr.local(3),
            calleeName = "FOO", calleeArity = 1, calleeSlots = 1,
            calleeBody = { emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6) },
            after = { emit(Opcode._PRINT_INT, SlotAddr.local(3), 1, 0, 3) },
        )
        assertEquals("1\n", rt(p, listOf(calleeMeta(p, "FOO", 1))).runAndOutput())
    }

    @Test
    fun `TRYC 未命中时命中标志槽置 0`() {
        val p = callProgram(
            raw = "GHOST(1)", bFlags = TRY_FLAG, cSlot = SlotAddr.local(3),
            after = { emit(Opcode._PRINT_INT, SlotAddr.local(3), 1, 0, 3) },
        )
        assertEquals("0\n", rt(p).runAndOutput())
    }

    @Test
    fun `空串不发起调用且顺序执行`() {
        val p = callProgram(
            raw = "   ", bFlags = 0, cSlot = SlotAddr.NONE,
            after = { emit(Opcode._PRINT_LITERAL, string("AFTER"), 1, 0, 3) },
        )
        assertEquals("AFTER\n", rt(p).runAndOutput())
    }

    // ------------------------------------------------------------------
    // 3. `_CALL_STR` 的编码/解码/装载往返（R1-AC5）
    // ------------------------------------------------------------------

    @Test
    fun `_CALL_STR 汇编-反汇编往返字节恒等`() {
        val src = """
            .func main slots=4
              _SET_STR R1, "RENDER_FN(3)"
              _CALL_STR R1, -2147483648, none
              _CALL_STR R1, 1073741824, R0
              _HALT
        """.trimIndent()
        val p1 = Assembler.assemble(src)
        val text = p1.disassemble()
        assertTrue(text.contains("_CALL_STR"), "反汇编未打印 _CALL_STR:\n$text")
        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())
        assertEquals(p1.code.toList(), p2.code.toList(), "往返后指令字不一致:\n$text")
    }

    @Test
    fun `_CALL_STR 经 erbc 编码解码装载往返保形`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 4)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("RENDER_FN(3)"), 0, 1)
        b.emit(Opcode._CALL_STR, SlotAddr.local(1), Int.MIN_VALUE, SlotAddr.NONE, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.setFunctionSlots(main, 4)
        val p = b.build(0)

        assertTrue(ErbcFormat.isValidOpcode(Opcode._CALL_STR), "新操作码必须合法")
        assertEquals(Opcode.MAX_OPCODE, ErbcFormat.OPCODE_MAX, "容器上界必须随 MAX_OPCODE 跟进")

        val back = ErbcReader.decode(ErbcWriter.encode(p))
        val ins = back.instructionAt(1)
        assertEquals(Opcode._CALL_STR, ins.op)
        assertEquals(SlotAddr.local(1), ins.a)
        assertEquals(Int.MIN_VALUE, ins.b)
        assertEquals(SlotAddr.NONE, ins.c)
    }

    // ------------------------------------------------------------------
    // 4. 编译器 CALLSTR 分支发射形态
    // ------------------------------------------------------------------

    private fun lp(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("LCALL", OperandType.STR, 0)
            .declareFunction("RENDER_FN", OperandType.INT),
    )

    private fun parseLines(vararg lines: String): List<Statement> {
        val p = lp()
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

    private fun compileLines(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parseLines(*lines), setOf("RENDER_FN"))
        return c.build()
    }

    private fun callStrPcs(p: Program): List<Int> =
        (0 until p.instructionCount).filter { p.instructionAt(it).op == Opcode._CALL_STR }

    @Test
    fun `TRYCALLSTR 编译为 _CALL_STR 置 TRY 位且命中槽 NONE`() {
        val p = compileLines("TRYCALLSTR LCALL")
        val pcs = callStrPcs(p)
        assertEquals(1, pcs.size, "_CALL_STR 应恰发射一次")
        val ins = p.instructionAt(pcs[0])
        assertEquals(TRY_FLAG, ins.b and TRY_FLAG, "TRY 位应置位")
        assertEquals(SlotAddr.NONE, ins.c, "TRYCALLSTR 的命中槽为 NONE")
    }

    @Test
    fun `TRYCCALLSTR 发射 _CALL_STR 命中槽加二步式跳转`() {
        val p = compileLines("TRYCCALLSTR LCALL", "CATCH", "ENDCATCH")
        val pcs = callStrPcs(p)
        assertEquals(1, pcs.size, "_CALL_STR 应恰发射一次")
        val ins = p.instructionAt(pcs[0])
        assertEquals(TRY_FLAG, ins.b and TRY_FLAG, "TRY 位应置位")
        assertTrue(!SlotAddr.isNone(ins.c), "TRYCCALLSTR 的 c 应作命中标志槽")
        val ops = (0 until p.instructionCount).map { p.instructionAt(it).op }
        assertTrue(ops.contains(Opcode._JUMP_IF_TRUE), "两步式应含 _JUMP_IF_TRUE")
        assertTrue(ops.contains(Opcode._JUMP), "两步式应含 _JUMP")
    }
}