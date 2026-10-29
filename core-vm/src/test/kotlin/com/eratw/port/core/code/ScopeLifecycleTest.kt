package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 函数作用域生命周期：`onFunctionEnter/onFunctionExit` 必须严格配对。
 *
 * 裁决依据（team-lead 2026-10-17）：进入/离开是**帧生命周期事件**，由 VM 的
 * `_CALL`/`_RET` 统一驱动，语句层不插指令。因此这里锁死三件事：
 * 1) 正常返回（RET/HALT）配对；
 * 2) **异常退出**（CodeEE/步数上限）也配对——走 `run()` 的 finally 兜底；
 * 3) 进入回调自身抛错时不会误出栈（enter 在 depth 变更之前）。
 */
class ScopeLifecycleTest {

    private class RecordingHost : VmHost {
        override val evalContext: EvalContext? = null
        val events = ArrayList<String>()

        var depth = 0
            private set

        var maxDepth = 0
            private set

        override fun print(text: String) {}

        override fun onFunctionEnter(funcIndex: Int) {
            depth++
            if (depth > maxDepth) maxDepth = depth
            events += "enter:$funcIndex"
        }

        override fun onFunctionExit(funcIndex: Int) {
            depth--
            events += "exit:$funcIndex"
        }
    }

    private fun run(src: String, host: RecordingHost = RecordingHost()): RecordingHost {
        VirtualMachine(Assembler.assemble(src.trimIndent()), host).run()
        return host
    }

    @Test
    fun `递归四层的进入与离开严格配对且 LIFO`() {
        // rec(3) → rec(2) → rec(1) → rec(0)，加上 main 共 5 帧
        val host = run(
            """
            .func main slots=2
              _SET_INT R0, 3
              _CALL rec, 1, R1
              _HALT

            .func rec arity=1 slots=3
              _SET_INT R1, 1
              _LT R2, R0, R1
              _JUMP_IF_TRUE R2, done
              _SUB R0, R0, R1
              _CALL rec, 1, R1
            done:
              _RET R0
            """,
        )

        assertEquals(
            listOf(
                "enter:0", "enter:1", "enter:1", "enter:1", "enter:1",
                "exit:1", "exit:1", "exit:1", "exit:1", "exit:0",
            ),
            host.events,
        )
        assertEquals(0, host.depth, "作用域栈必须归零")
        assertEquals(5, host.maxDepth)
    }

    @Test
    fun `运行期异常中途退出也会回滚作用域栈`() {
        val host = RecordingHost()
        val vm = VirtualMachine(
            Assembler.assemble(
                """
                .func main slots=1
                  _CALL f, 0, R0
                  _HALT

                .func f arity=0 slots=1
                  _CALL g, 0, R0
                  _RET

                .func g arity=0 slots=1
                  _DIV R0, R1, R2
                  _RET
                """.trimIndent(),
            ),
            host,
        )

        assertFailsWith<CodeEE> { vm.run() }
        assertEquals(listOf("enter:0", "enter:1", "enter:2", "exit:2", "exit:1", "exit:0"), host.events)
        assertEquals(0, host.depth)
    }

    @Test
    fun `步数上限截断时作用域栈同样归零`() {
        val host = RecordingHost()
        val vm = VirtualMachine(
            Assembler.assemble(
                """
                .func main slots=1
                  _CALL spin, 0, R0
                  _HALT

                .func spin arity=0 slots=1
                loop:
                  _JUMP loop
                """.trimIndent(),
            ),
            host,
            VmConfig(maxStepsPerRun = 200),
        )

        assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), host.events)
        assertEquals(0, host.depth)
    }

    @Test
    fun `被调函数内 HALT 同样逐个出栈`() {
        val host = run(
            """
            .func main slots=1
              _CALL f, 0, R0
              _HALT

            .func f arity=0 slots=1
              _HALT
            """,
        )
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), host.events)
        assertEquals(0, host.depth)
    }

    @Test
    fun `顶层 RET 结束时入口帧也会离开`() {
        val host = run(
            """
            _SET_INT R0, 1
            _RET R0
            """,
        )
        assertEquals(listOf("enter:0", "exit:0"), host.events)
    }

    @Test
    fun `exit 自身抛错时不会被 finally 重复调用`() {
        // leaveFunction 在栈不配对时会抛 CodeEE。此时该帧已从 VM 栈弹出，
        // finally 不得对它再 leave 一次（否则变量层会看到两次 leave）。
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            val events = ArrayList<String>()

            override fun print(text: String) {}

            override fun onFunctionEnter(funcIndex: Int) {
                events += "enter:$funcIndex"
            }

            override fun onFunctionExit(funcIndex: Int) {
                events += "exit:$funcIndex"
                if (funcIndex == 1) throw IllegalStateException("scope out 失败")
            }
        }

        val vm = VirtualMachine(
            Assembler.assemble(
                """
                .func main slots=2
                  _CALL f, 0, R0
                  _HALT

                .func f arity=0 slots=1
                  _RET
                """.trimIndent(),
            ),
            host,
        )

        assertFailsWith<IllegalStateException> { vm.run() }
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), host.events)
    }

    @Test
    fun `enter 自身抛错时不会误把该帧计入退出`() {
        // 宿主在进入 rec（函数下标 1）时抛错：该帧还没成为 VM 的活动帧，
        // finally 只能退出 main，enter/exit 仍然配对。
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            val events = ArrayList<String>()
            var depth = 0

            override fun print(text: String) {}

            override fun onFunctionEnter(funcIndex: Int) {
                events += "enter:$funcIndex"
                if (funcIndex == 1) throw IllegalStateException("scope in 失败")
                depth++
            }

            override fun onFunctionExit(funcIndex: Int) {
                events += "exit:$funcIndex"
                depth--
            }
        }

        val vm = VirtualMachine(
            Assembler.assemble(
                """
                .func main slots=2
                  _CALL rec, 0, R0
                  _HALT

                .func rec arity=0 slots=1
                  _RET
                """.trimIndent(),
            ),
            host,
        )

        assertFailsWith<IllegalStateException> { vm.run() }
        assertEquals(listOf("enter:0", "enter:1", "exit:0"), host.events)
        assertEquals(0, host.depth)
        // 再次 run 不会因为上次异常残留的 depth 而错乱
        assertFailsWith<IllegalStateException> { vm.run() }
        assertEquals(0, host.depth)
        assertTrue(host.events.size == 6, host.events.toString())
    }
}
