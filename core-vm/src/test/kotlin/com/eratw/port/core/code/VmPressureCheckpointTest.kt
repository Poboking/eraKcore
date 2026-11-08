package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 长页自救口的 VM 半边：指令循环的周期检查点（[VmHost.pressureCheckpoint]）。
 *
 * 背景（2026-11-01 收件箱 0.2.34）：同一类页面平时 `turn 2ms`、堆贴顶时 `turn 58515ms`，
 * 而宿主所有自救动作原先只挂在**页尾**（进入输入等待的 `onTurnReady`）—— 一页算一分钟，
 * 那一分钟一次都跑不到。本检查点与既有的宿主关闭闸同处同频（每
 * [Vm.SHUTDOWN_CHECK_MASK] + 1 条指令，真机 ≈30 ms），把「页中」也变成一个可动作的点。
 *
 * 四条真断言：
 * * ① 触发频次 = 每 65536 条指令恰好一发（不多发到每条指令），且抛点与 `executedSteps` 口径不动。
 * * ② 缺省实现（不覆写）零影响：同一段程序在覆写前后**指令口径逐字一致**
 *   （`executedSteps` 与 [StepLimitExceeded.steps] 都不动）⇒ 加钩子没有改变死循环闸的抛点。
 * * ③ 数的是**指令**而不是墙钟：宿主在语句体里自转 20 万次循环，换不来一发检查点。
 */
class VmPressureCheckpointTest {

    /** 只数检查点与语句派发次数的最小宿主；[waitStmts] 里的语句模拟「一次真实让出」。 */
    private class CheckpointHost(
        private val waitStmts: Set<Statement> = emptySet(),
    ) : VmHost {
        override val evalContext: EvalContext? = null

        var checkpoints = 0L
            private set

        var statements = 0L
            private set

        /** 检查点只能在两条语句之间：语句体内置位标记，进来时若为真说明被截断。 */
        private var insideStatement = false

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            check(!insideStatement) { "语句体被从中间截断（检查点落在了语句执行内部）" }
            insideStatement = true
            try {
                statements++
                if (stmt in waitStmts) Thread.sleep(0)
            } finally {
                insideStatement = false
            }
            return StmtOutcome.Normal
        }

        override fun pressureCheckpoint() {
            check(!insideStatement) { "周期检查点必须只在两条语句之间触发" }
            checkpoints++
        }
    }

    private fun stmt(raw: String): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, raw)

    /** 纯回边紧循环：每条循环体只有 `_JUMP` 一条指令，指令数可精确预期。 */
    private fun loopProgram(): Program = Assembler.assemble(
        """
        .func main slots=1
        loop:
          _JUMP loop
        """.trimIndent(),
    )

    @Test
    fun `周期检查点每65536条指令恰好一发`() {
        val cap = 3L * 65536L + 1000L
        val host = CheckpointHost()
        val vm = VirtualMachine(loopProgram(), host, VmConfig(maxStepsPerRun = cap))

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        // 抛点：段落攒到 cap+1 条指令（与加钩子前同一判据，见 ② 的对照）。
        assertEquals(cap + 1L, e.steps)
        // 发数：steps = 65536 / 131072 / 196608 各一发，第 4 发（262144）在抛点之后。
        assertEquals(3L, host.checkpoints, "65536 的整点各一发，不多发到每条指令")
        assertEquals(cap + 1L, vm.executedSteps, "executedSteps 口径不受钩子影响")
    }

    @Test
    fun `不覆写钩子的宿主与改造前逐条一致`() {
        // 缺省实现 = 空方法体：同一程序、同一阈值下抛点与指令数都不该动。
        val cap = 200_000L
        val plain = object : VmHost {
            override val evalContext: EvalContext? = null
            override fun print(text: String) {}
            override fun executeStatement(stmt: Statement, env: StmtEnv) = StmtOutcome.Normal
        }
        val vm = VirtualMachine(loopProgram(), plain, VmConfig(maxStepsPerRun = cap))

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        assertEquals(cap + 1L, e.steps)
        assertEquals(cap + 1L, vm.executedSteps)
    }

    @Test
    fun `语句执行期间不追加检查点`() {
        // 一条 65536+ 指令的语句（marker 内部由宿主自己转圈）执行时，检查点必须在语句结束后才补。
        val heavy = stmt("HEAVY")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _NOP
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(heavy),
        )

        class HeavyHost : VmHost {
            override val evalContext: EvalContext? = null
            var checkpoints = 0L
            override fun print(text: String) {}

            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
                if (stmt === heavy) repeat(200_000) { /* 语句体内自转：这段时间里没有检查点 */ }
                return StmtOutcome.Normal
            }

            override fun pressureCheckpoint() {
                checkpoints++
            }
        }

        val host = HeavyHost()
        VirtualMachine(program, host, VmConfig(maxStepsPerRun = 1_000_000L)).run()

        // 3 条指令（_NOP / marker / _HALT）不足以跨过 65536 ⇒ 一次都不发：钩子只在指令流里数步数。
        assertEquals(0L, host.checkpoints, "语句体内部的自转不计入指令数，也就不该换来检查点")
    }
}
