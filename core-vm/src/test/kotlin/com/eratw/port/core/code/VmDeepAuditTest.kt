package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * deep-code-vm 深审回归（取指/Vm：`Vm.kt` / `Program.kt`）。
 *
 * 四条缺陷，均以「引擎数组越界（`ArrayIndexOutOfBoundsException`，非 [CodeEE]）冒到宿主」
 * 或「帧模型被静默破坏」为症状 —— 与 CMP-55 已修的四入口槽守卫同族：
 *
 * 1. **实参复制上界**（`Vm.callInto`/`jumpInto`）：`argc = min(argcRaw, callee.arity)`，
 *    没有把上界夹到 `callee.slotCount`。畸形的 [FunctionInfo]（`arity > slotCount`，
 *    装载期 `validateFunctionRecord` 不拦这一条）下按 `arity` 复制实参，会写进**相邻帧**
 *    区域、甚至越过 `ensureFrameSlots` 只保证到 `frameBase + slotCount` 的容量 ⇒ AIOOBE；
 *    负 `arity` 还会让清零区间 `[argc, slotCount)` 变成负数起点。
 *    原版只按被调**头部形参位**逐位绑定（`ProcessState.cs:435` 循环上界
 *    `call.TopLabel.Arg.Length`），多出的实参本就忽略 ⇒ 夹到 `[0, min(arity, slotCount)]` 语义不漂。
 * 2. **池下标无守卫**（`_SET_INT`/`_SET_STR`/`_PRINT_LITERAL`/`_EVAL_INT`/`_EVAL_STR`）：
 *    `.erbc` 装载链有 `ErbcStream.validate` 的 `requirePool`，但**内存直编**（`CodeBuilder`）
 *    与 `Assembler` 产物不走那趟；越界下标只会让 `ArrayList` 抛非 CodeEE ⇒ 主线程死。
 * 3. **`currentFunctionKey` 违 KDoc**：`run()`（含异常退出）的 finally 里 `unwindScopes`
 *    把 `depth` 收成 -1；该公开属性直接取 `funcStack[-1]` 抛 AIOOBE，而 KDoc 承诺「无帧 = null」。
 * 4. **`_JUMP_DYN` 不校验目标函数归属**：只判标签下标范围，`labelPcs[a]` 落在**别的函数**
 *    区间时照跳 —— 当前帧的返回点被留在另一个函数体里，帧模型坏掉。同函数内跳转的
 *    `StmtOutcome.Transfer` 早已由 `checkTransferTarget` 守卫，本条与它对齐。
 */
class VmDeepAuditTest {

    private object StubReader : VariableReader {
        override fun isConst(name: String): Boolean = false
        override fun readInt(name: String, indices: LongArray): Long = 0L
        override fun readStr(name: String, indices: LongArray): String = ""
        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = delta
        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 0L
    }

    /** 只为让 `_EVAL_*` 的 evalContext 守卫放行（表达式本体在越界用例里不会被求值）。 */
    private class StubEvalContext : EvalContext {
        override val variables: VariableReader get() = StubReader

        override fun callFunction(name: String, args: List<Value?>): Value = Value.IntV(0L)
    }

    // ------------------------------------------------------------------
    // 1：实参复制不得越过被调帧槽区间
    // ------------------------------------------------------------------

    /**
     * 把被调帧推到高位（调用方 slotCount=100 ⇒ callee 基址 100，容量倍增后 128），
     * 再让被调函数以 `arity=200`、`slotCount=1` 建帧、调用点 `argc=200`：
     * 夹取前复制循环会写到 `frameInts[128..]` ⇒ AIOOBE；夹取后只写 `frameInts[100]`。
     *
     * `arity <= slotCount` 自 `b04c224f` 起是 `CodeBuilder.build` 的跨字段不变量（与
     * `ErbcReader` / `ErbcWriter` / `ErbcStream` 三路装载同款），**正常构建路径已造不出**
     * 这种函数表 ⇒ 先按合法形态（`arity=200 slots=200`）建好，再把 `slotCount` 压到 1，
     * 模拟「内存函数表在构建之后被改坏」。本用例锁的是 VM 侧的兜底夹取
     * （`Vm.callInto`/`jumpInto`）——表被改坏也不许越界写坏相邻帧；构建期的拒绝由
     * `VmFrameSlotAuditTest.构建期拒绝 arity 大于 slotCount 的函数` 单独钉住。
     */
    @Test
    fun `函数表被改坏成 arity 大于 slotCount 时实参复制仍夹到本帧槽区间`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 100)
        b.emit(Opcode._CALL, 1, 200, SlotAddr.NONE, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)

        val subEntry = b.here()
        b.declareFunction("sub", subEntry, 200, 200)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 3)

        val program = b.build(main)
        // 构建期不变量拦的是"造得出来的表"；这里直接改坏终态，专测 VM 兜底。
        program.functions[1].slotCount = 1

        val host = StringHost()
        // 夹取前：ArrayIndexOutOfBoundsException；夹取后：正常返回 0（sub 的 `_RET none`）。
        assertEquals(0L, VirtualMachine(program, host).run())
    }

    /** 负 arity（内存构造的畸形函数表）不得让清零区间的起点为负、写坏调用方已用的槽。 */
    @Test
    fun `负 arity 的畸形函数表不得把调用方 R3 静默清零`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 8)
        b.emit(Opcode._SET_INT, SlotAddr.local(3), b.constant(99L), 0, 1)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 2)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(3), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)

        val subEntry = b.here()
        b.declareFunction("sub", subEntry, -5, 3)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)

        // 夹取前：`argc = min(0, -5) = -5` ⇒ 清零区间 [-5, 3) 从 `frameInts[8-5=3]` 起写，
        // 把调用方 R3..R7 静默清零 ⇒ 输出 "0\n"（错）。夹取后 argc=0 ⇒ 只清 [8, 11)，R3 保持 99。
        val host = StringHost()
        assertEquals(0L, VirtualMachine(b.build(main), host).run())
        assertEquals("99\n", host.output.toString())
    }

    // ------------------------------------------------------------------
    // 2：池下标越界一律 CodeEE
    // ------------------------------------------------------------------

    @Test
    fun `常量池下标越界抛 CodeEE 而不是 AIOOBE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(7L), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 2, 999)

        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(main), StringHost()).run() }
        val message = e.message.orEmpty()
        assertTrue("常量池下标越界(999)" in message, "实际：$message")
        assertTrue("共 1 项" in message, "实际：$message")
    }

    @Test
    fun `字符串池下标越界（SET_STR）抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("x"), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 2, 999)

        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(main), StringHost()).run() }
        assertTrue("字符串池下标越界(999)" in e.message.orEmpty(), "实际：${e.message}")
    }

    @Test
    fun `字符串池下标越界（PRINT_LITERAL 的 a）抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("x"), 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 1, 999)

        val e = assertFailsWith<CodeEE> {
            VirtualMachine(b.build(main), StringHost()).run()
        }
        assertTrue("字符串池下标越界(999)" in e.message.orEmpty(), "实际：${e.message}")
    }

    @Test
    fun `表达式池下标越界（EVAL_INT 的 a）抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(1L)), SlotAddr.local(0), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 1, 999)

        val host = StringHost(evalContext = StubEvalContext())
        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(main), host).run() }
        assertTrue("表达式池下标越界(999)" in e.message.orEmpty(), "实际：${e.message}")
    }

    /** 反向：合法下标不得误报（表达式求值真的走到并写回槽）。 */
    @Test
    fun `合法的常量池与表达式池下标不被误报`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(7L), 0, 1)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(35L)), SlotAddr.local(1), 0, 2)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), FLAG_NEWLINE, 0, 3)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(1), FLAG_NEWLINE, 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val host = StringHost(evalContext = StubEvalContext())
        assertEquals(0L, VirtualMachine(b.build(main), host).run())
        assertEquals("7\n35\n", host.output.toString())
    }

    // ------------------------------------------------------------------
    // 3：currentFunctionKey 的无帧语义
    // ------------------------------------------------------------------

    @Test
    fun `run 之后 currentFunctionKey 为 null 而不是抛 AIOOBE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)

        val vm = VirtualMachine(b.build(main), StringHost())
        vm.run()
        // unwindScopes 已把 depth 收成 -1：KDoc 承诺「无帧 = null」。
        assertNull(vm.currentFunctionKey)
    }

    @Test
    fun `run 抛错退出之后 currentFunctionKey 同样为 null`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._MOV_INT, SlotAddr.local(0), SlotAddr.local(9), 0, 1) // R9 越界
        b.emit(Opcode._HALT, 0, 0, 0, 2)

        val vm = VirtualMachine(b.build(main), StringHost())
        assertFailsWith<CodeEE> { vm.run() }
        assertNull(vm.currentFunctionKey)
    }

    // ------------------------------------------------------------------
    // 4：_JUMP_DYN 目标的函数归属
    // ------------------------------------------------------------------

    @Test
    fun `_JUMP_DYN 目标落在别的函数区间时抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 1) // a=0 → 标签下标 0
        val subEntry = b.here()
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.declareFunction("sub", subEntry, 0, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 3)

        val p = b.build(main)
        // 畸形标签表：main 的 $L 指向 sub 的区间（编译器不会产生，只有脏产物/内存构造可达）。
        p.functions[main].putLabel("L", subEntry)

        val e = assertFailsWith<CodeEE> { VirtualMachine(p, StringHost()).run() }
        val message = e.message.orEmpty()
        assertTrue("跳转目标 pc 越界" in message, "实际：$message")
        assertTrue("main" in message, "文案须点名当前函数，实际：$message")
    }

    /** 反向：合法标签（落在本函数区间）照常跳。 */
    @Test
    fun `_JUMP_DYN 目标在本函数区间内时照常跳转`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 1) // 跳到 $L
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(1L), 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        val labelPc = b.here()
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(42L), 0, 4)
        b.emit(Opcode._HALT, 0, 0, 0, 5)

        val p = b.build(main)
        p.functions[main].putLabel("L", labelPc)

        val host = StringHost()
        assertEquals(0L, VirtualMachine(p, host).run())
    }

    private companion object {
        const val FLAG_NEWLINE = 1
    }
}