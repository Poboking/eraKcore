package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M2 dispatch ISA 的运行期判据（ABI-3 候选；specs/erbc-format.md §5.6a）。
 *
 * 程序：ENTRY 普通调用 B（返回值落 R0 后 `_RET R0`），B/C 各返回 111/222。
 * dispatch 表 [0,2,2] = 「B 被覆盖，执行入口 = C」的链接形态。断言面：
 *
 * - 无表（null）= 无 Mod 产物快路径：直调 B，行为与改造前逐位一致；
 * - 恒等表 = 语义基准：查表结果仍 B；
 * - 覆盖表：`_CALL` 进 C（覆盖胜者）；
 * - 覆盖表 + b bit27 DIRECT：绕过表直调 B（CALL_SUPER 直调前实现语义）；
 * - 尾跳（bit30）同样过表：MID 尾跳 B 实际进 C；
 * - 越界下标原样透传：报既有「函数下标越界」而非 AIOOBE（界防御）；
 * - 表长度不等于函数表长度：构造期拒绝。
 */
class VmDispatchTest {

    /** 只记录帧进入事件的宿主（print 不使用）。 */
    private class RecordingHost : VmHost {
        val entered = ArrayList<Int>()

        override val evalContext: EvalContext? = null

        override fun print(text: String) {}

        override fun onFunctionEnter(funcIndex: Int) {
            entered += funcIndex
        }
    }

    /** ENTRY 普通调用 B（`_CALL a=1, c=R0`），B/C 各 `_SET_INT`+`_RET` 返回 111/222。 */
    private fun callProgram(extraFlags: Int = 0): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, extraFlags, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("B", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 2)
        b.emit(Opcode._RET, 0, 0, 0, 3)
        b.declareFunction("C", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(222L), 0, 4)
        b.emit(Opcode._RET, 0, 0, 0, 5)
        return b.build(entry)
    }

    /** ENTRY 调 MID，MID **尾跳** B（bit30）：jumpInto 路的查表判据。 */
    private fun tailCallProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("MID", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, 2, Opcode.JUMP_FLAG, 0, 2) // 尾跳 B（下标 2）
        b.declareFunction("B", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        b.declareFunction("C", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(222L), 0, 5)
        b.emit(Opcode._RET, 0, 0, 0, 6)
        return b.build(entry)
    }

    /** 「B 被覆盖、执行入口 = C」的链接形态（B/C 恒等，ENTRY 恒等）。 */
    private val overrideTable = intArrayOf(0, 2, 2)

    @Test
    fun `无表快路径直调物理下标`() {
        // null 表 = 无 Mod 产物：不查表，行为与改造前逐位一致（golden 前提）。
        val host = RecordingHost()
        val vm = VirtualMachine(callProgram(), host)
        assertEquals(111L, vm.run(0))
        assertEquals(listOf(0, 1), host.entered)
        assertEquals(null, vm.functionDispatch)
    }

    @Test
    fun `恒等表语义基准`() {
        val host = RecordingHost()
        val vm = VirtualMachine(callProgram(), host, functionDispatch = intArrayOf(0, 1, 2))
        assertEquals(111L, vm.run(0))
        assertEquals(listOf(0, 1), host.entered)
    }

    @Test
    fun `覆盖表进胜者`() {
        val host = RecordingHost()
        val vm = VirtualMachine(callProgram(), host, functionDispatch = overrideTable)
        // _CALL B 查表 → 执行入口 C（覆盖胜者），返回值 222。
        assertEquals(222L, vm.run(0))
        assertEquals(listOf(0, 2), host.entered)
    }

    @Test
    fun `DIRECT位绕过表直调`() {
        val host = RecordingHost()
        val vm = VirtualMachine(
            callProgram(extraFlags = Opcode.DIRECT_CALL_FLAG),
            host,
            functionDispatch = overrideTable,
        )
        // bit27 置位 = a 已是最终执行入口（CALL_SUPER 回填形态）：直调 B，不查表。
        assertEquals(111L, vm.run(0))
        assertEquals(listOf(0, 1), host.entered)
    }

    @Test
    fun `尾跳同样过表`() {
        val host = RecordingHost()
        // 4 函数表（ENTRY/MID/B/C）：ENTRY/MID 恒等，B(2)→C(3) 覆盖。
        val vm = VirtualMachine(tailCallProgram(), host, functionDispatch = intArrayOf(0, 1, 3, 3))
        // MID 尾跳 B（bit30）→ jumpInto 查表 → 实际进 C（下标 3）；B 一次都不进。
        assertEquals(222L, vm.run(0))
        assertEquals(listOf(0, 1, 3), host.entered)
    }

    @Test
    fun `越界下标透传报既有文案`() {
        // 畸形产物 _CALL a=99：resolveDispatch 界防御原样透传 → callInto 报既有
        // 「函数下标越界」（不是 AIOOBE），与无表行为一致。
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 99, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("B", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(1L), 0, 2)
        b.emit(Opcode._RET, 0, 0, 0, 3)
        b.declareFunction("C", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._SET_INT, 0, b.constant(2L), 0, 4)
        b.emit(Opcode._RET, 0, 0, 0, 5)
        val program = b.build(entry)
        val vm = VirtualMachine(program, RecordingHost(), functionDispatch = overrideTable)
        val ex = assertFailsWith<CodeEE> { vm.run(0) }
        assertTrue("函数下标越界" in ex.message!!, ex.message)
    }

    @Test
    fun `表长度不等函数表构造期拒绝`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            VirtualMachine(callProgram(), RecordingHost(), functionDispatch = intArrayOf(0, 2))
        }
        assertTrue("必须等于函数表长度" in ex.message!!, ex.message)
    }
}
