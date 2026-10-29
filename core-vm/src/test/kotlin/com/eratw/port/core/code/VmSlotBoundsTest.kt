package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-55 夹具②：VM 四入口（getInt/setInt/getStr/setStr）的槽越界守卫。
 *
 * 现状（改前）：`G99` + `globalSlots=1` 直接 `globalInts[99]` ⇒
 * `ArrayIndexOutOfBoundsException`；帧内 `R5` + `slotCount=2` 则**静默**
 * 读写到 `frameInts[5]`（数组容量 64），相邻帧内存被写坏而没人报错。
 *
 * 改后：两者都抛 [CodeEE]，文案沿用 `checkRetDst` 的槽形式（`G<n>` / `R<n>` +
 * 可用槽数），与脚本语义错误同一层（ADR-005：不把引擎数组越界冒到宿主）。
 *
 * 反向可证：合法全局槽、合法帧槽（含跨帧切换后）必须照常读写 —— 守卫只认
 * 「当前帧的 slotCount」与「全局槽数组长度」，不认物理数组容量。
 */
class VmSlotBoundsTest {

    private companion object {
        /** `_PRINT_INT` 等的标志位：bit0 = 末尾换行。 */
        const val FLAG_NEWLINE = 1
    }

    /**
     * 单函数裸骨架：`_SET_INT G99`（先发 G0 再回填成 G99，`globalSlots` 保持 1）。
     *
     * CodeBuilder 的 `noteGlobalSlot` 会把计数顶到 100，那样就测不到「未登记的全局槽」，
     * 所以先按 G0 发射、再用 [CodeBuilder.patch] 把 a 改成 G99。
     */
    private fun globalIntProgram(globalSlot: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.noteGlobalSlot(0) // globalSlots = 1（数组长度即 1）
        b.emit(Opcode._SET_INT, SlotAddr.global(0), b.constant(7L), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 1, SlotAddr.global(globalSlot))
        return b.build(main)
    }

    /** 单函数裸骨架：`_SET_STR G99`，口径同上。 */
    private fun globalStrProgram(globalSlot: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.noteGlobalSlot(0)
        b.emit(Opcode._SET_STR, SlotAddr.global(0), b.string("x"), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        b.patch(0, 1, SlotAddr.global(globalSlot))
        return b.build(main)
    }

    /** 帧内写：`_SET_INT R<slot>, 7`，main 只声明 2 槽（R5 已越过 slotCount 但在物理数组内）。 */
    private fun frameWriteProgram(slot: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(slot), b.constant(7L), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        return b.build(main)
    }

    /** 帧内读：`_MOV_STR R0, R<slot>`，main 只声明 2 槽。 */
    private fun frameReadStrProgram(slot: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._MOV_STR, SlotAddr.local(0), SlotAddr.local(slot), 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        return b.build(main)
    }

    // ------------------------------------------------------------------
    // 红：越界槽必须是 CodeEE
    // ------------------------------------------------------------------

    @Test
    fun `夹具二 G99 在 globalSlots=1 时抛 CodeEE 而不是 AIOOBE`() {
        val e = assertFailsWith<CodeEE> {
            VirtualMachine(globalIntProgram(99), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("G99" in message, "文案须含槽形式 G99，实际：$message")
        assertTrue("全局槽共 1 个" in message, "文案须含全局槽总数，实际：$message")
    }

    @Test
    fun `字符串全局槽越界同样是 CodeEE`() {
        val e = assertFailsWith<CodeEE> {
            VirtualMachine(globalStrProgram(99), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("G99" in message, "文案须含槽形式 G99，实际：$message")
        assertTrue("全局槽共 1 个" in message, "文案须含全局槽总数，实际：$message")
    }

    @Test
    fun `帧内槽越过 slotCount 但仍在物理数组内也必须 CodeEE`() {
        val e = assertFailsWith<CodeEE> {
            VirtualMachine(frameWriteProgram(5), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("R5" in message, "文案须含槽形式 R5，实际：$message")
        assertTrue("当前帧只有 2 槽" in message, "文案须含当前帧槽数，实际：$message")
    }

    @Test
    fun `字符串帧内读越过 slotCount 也必须 CodeEE`() {
        val e = assertFailsWith<CodeEE> {
            VirtualMachine(frameReadStrProgram(5), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("R5" in message, "文案须含槽形式 R5，实际：$message")
        assertTrue("当前帧只有 2 槽" in message, "文案须含当前帧槽数，实际：$message")
    }

    // ------------------------------------------------------------------
    // 绿（反向用例）：合法槽不得误报
    // ------------------------------------------------------------------

    @Test
    fun `合法的全局槽写入与读出不被误报`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.noteGlobalSlot(1) // globalSlots = 2，G1 是最后一个合法槽
        b.emit(Opcode._SET_INT, SlotAddr.global(1), b.constant(7L), 0, 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.global(1), FLAG_NEWLINE, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)

        val host = StringHost()
        assertEquals(0L, VirtualMachine(b.build(main), host).run())
        assertEquals("7\n", host.output.toString())
    }

    @Test
    fun `合法的帧内最后一个槽读写不被误报`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(9L), 0, 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(1), FLAG_NEWLINE, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)

        val host = StringHost()
        assertEquals(0L, VirtualMachine(b.build(main), host).run())
        assertEquals("9\n", host.output.toString())
    }

    /**
     * 跨帧切换后，被调帧的槽区间与调用方不同：callee 的 R2 合法（slotCount=3），
     * 若守卫拿调用方的 slotCount（2）来判，这里会误报。
     */
    @Test
    fun `被调帧更宽的槽区间不被误报`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.local(0), 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), FLAG_NEWLINE, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)

        val sub = b.declareFunction("sub", b.here(), 0, 3)
        b.emit(Opcode._SET_INT, SlotAddr.local(2), b.constant(42L), 0, 10)
        b.emit(Opcode._MOV_INT, SlotAddr.local(0), SlotAddr.local(2), 0, 11)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 12)

        val host = StringHost()
        assertEquals(42L, VirtualMachine(b.build(main), host).run())
        assertEquals("42\n", host.output.toString())
    }
}
