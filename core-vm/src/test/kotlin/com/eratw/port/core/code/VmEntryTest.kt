package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [VirtualMachine.run]`(entryFunctionIndex)`：任意入口执行与帧生命周期配对。
 *
 * 程序固定为两个函数：`a`（= 程序入口）只 `_HALT`，`b` 返回 42。任意入口时目标函数
 * 直接成为第 0 帧（无合成根），顶层 `_RET` 结束本次 run，`onFunctionEnter/Exit` 仍各一次。
 */
class VmEntryTest {

    /** 只记录帧生命周期事件的宿主（print 不使用）。 */
    private class RecordingHost : VmHost {
        val entered = ArrayList<Int>()
        val exited = ArrayList<Int>()

        override val evalContext: EvalContext? = null

        override fun print(text: String) {}

        override fun onFunctionEnter(funcIndex: Int) {
            entered += funcIndex
        }

        override fun onFunctionExit(funcIndex: Int) {
            exited += funcIndex
        }
    }

    private fun fixtureProgram(): Program = Assembler.assemble(
        """
        .func a slots=1
          _HALT

        .func b slots=1
          _SET_INT R0, 42
          _RET R0
        """.trimIndent(),
    )

    private fun indexOf(program: Program, name: String): Int {
        val idx = program.functions.indexOfFirst { it.name == name }
        check(idx >= 0) { "函数 $name 不在程序中" }
        return idx
    }

    @Test
    fun `run 指定函数下标时从该函数起跑并返回其顶层返回值`() {
        val program = fixtureProgram()
        val host = RecordingHost()
        val vm = VirtualMachine(program, host)
        val b = indexOf(program, "b")

        assertEquals(42L, vm.run(b))
    }

    @Test
    fun `任意入口的 onFunctionEnter 与 onFunctionExit 各一次且下标正确`() {
        val program = fixtureProgram()
        val host = RecordingHost()
        val vm = VirtualMachine(program, host)
        val b = indexOf(program, "b")

        vm.run(b)

        assertEquals(listOf(b), host.entered, "入口帧只进入一次")
        assertEquals(listOf(b), host.exited, "顶层 _RET 结束 run 后恰好离开一次")
    }

    @Test
    fun `无参 run 仍走 program 入口函数`() {
        val program = fixtureProgram()
        val host = RecordingHost()
        val vm = VirtualMachine(program, host)
        val a = program.entryFunction

        assertEquals(0L, vm.run())
        assertEquals(listOf(a), host.entered)
        assertEquals(listOf(a), host.exited)
    }

    @Test
    fun `入口下标越界抛 CodeEE 且不进入任何帧`() {
        val program = fixtureProgram()
        val host = RecordingHost()
        val vm = VirtualMachine(program, host)

        assertFailsWith<CodeEE> { vm.run(program.functions.size) }
        assertFailsWith<CodeEE> { vm.run(-1) }
        assertEquals(emptyList<Int>(), host.entered)
        assertEquals(emptyList<Int>(), host.exited)
    }
}
