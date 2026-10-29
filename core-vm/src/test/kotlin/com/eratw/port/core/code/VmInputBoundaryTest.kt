package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M10-RUNB：死循环保护的计数边界 = 「相邻两次输入等待之间」（不是「一整局」）。
 *
 * 原版没有每-run 指令上限：`DoScript` 外层 while 的边界是 `console.IsRunning`
 * （spec: Process.cs:438-458，:449/:453 —— 要输入就把控制权还给控制台）；原版的
 * 死循环防护只是按墙钟弹框、用户选继续即清零（spec: Process.cs:493-512 / :1012-1014）。
 * 本移植的输入等待是阻塞式，一次 run 跨几十上百次输入 ⇒ 闸的计数边界挪到输入等待
 * 之间（[VmHost.yieldBoundaryCount]），阈值数值不变。M10-AWAIT 又把 `AWAIT` 的帧让出并入
 * 同一读数（sem 侧 `StatementExecutor.yieldBoundaryCount`），断言见 `sem/InputStatementsTest`。
 *
 * 三条真断言（判据 1-3）：
 * * ① 不含输入等待的紧循环仍在同一阈值抛 [StepLimitExceeded]（`e.steps == cap+1`）。
 * * ② 反复等待输入、总指令数远超阈值的脚本**不抛**且跑完 —— 用 [QueueInputSink]
 *   真队列 + 后台喂入线程（每次等待都真实阻塞后才拿到提交），不是同步桩。
 * * ③ 跨段边界后段落计数确实归零：同一程序只改「语句是否等待」，等待版跑完且
 *   `executedSteps` = 全 run 总指令数（口径不变，判据 3），不等待版仍在 cap+1 抛。
 */
class VmInputBoundaryTest {

    /** 语句 marker（区间 29..197，语义全在本测试宿主里做）。 */
    private fun stmt(raw: String): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, raw)

    /**
     * 测试宿主：登记为「等待语句」的 marker 走 [sink] 真实等待一次，然后
     * 段落计数 +1（对齐 sem 的 `StatementExecutor.waitInput` 记账点）。
     */
    private class WaitHost(
        private val sink: InputSink,
        private val waitStmts: Set<Statement>,
    ) : VmHost {
        override val evalContext: EvalContext? = null

        var waits = 0L
            private set

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            if (stmt in waitStmts) {
                sink.waitInput(InputRequest(type = InputRequest.InputType.INT))
                waits++
            }
            return StmtOutcome.Normal
        }

        override fun yieldBoundaryCount(): Long = waits
    }

    // ------------------------------------------------------------------
    // ① 无输入的紧循环：阈值与抛点不动
    // ------------------------------------------------------------------

    @Test
    fun `无输入的紧循环仍在同一阈值抛步数上限`() {
        val program = Assembler.assemble(
            """
            .func main slots=1
            loop:
              _JUMP loop
            """.trimIndent(),
        )
        val vm = VirtualMachine(program, StringHost(), VmConfig(maxStepsPerRun = 500))

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(501L, e.steps, "段落计数无边界可跨 ⇒ 抛点与改造前逐条一致")
    }

    // ------------------------------------------------------------------
    // ② 主判据：真队列 + 后台喂入，总指令数远超阈值不抛
    // ------------------------------------------------------------------

    @Test
    fun `反复等待输入的长脚本不撞步数闸`() {
        val iterations = 30
        val wait = stmt("INPUT x")
        val program = Assembler.assemble(
            """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R2, $iterations
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _LT R3, R0, R2
              _JUMP_IF_TRUE R3, loop
              _HALT
            """.trimIndent(),
            statements = listOf(wait),
        )

        // 真队列：VM 线程阻塞在 waitInput，后台线程等 isAwaiting 置起后提交一条再对表下一条
        // —— 每次等待都真实进过 await（队列当时为空），不是同步返回的桩。
        val sink = QueueInputSink()
        val host = WaitHost(sink, setOf(wait))
        val vm = VirtualMachine(program, host, VmConfig(maxStepsPerRun = 10))

        val deadline = System.currentTimeMillis() + 20_000
        val feeder = Thread {
            for (i in 1..iterations) {
                // 只在「等待方阻塞中 且 队列已空」时投喂：这一次 waitInput 必然真实进过
                // arrived.await（首条提交时队列为空可证）。不对表"消费完成"——
                // VM 消费后立刻再入等待会吞掉 awaiting 落回 null 的窗口，锁步协议会死锁。
                while (!(sink.isAwaiting && sink.pendingCount == 0)) {
                    if (System.currentTimeMillis() > deadline) {
                        sink.close()
                        return@Thread
                    }
                    Thread.sleep(1)
                }
                sink.submit("1")
            }
        }.apply { isDaemon = true }
        feeder.start()
        try {
            // 不抛 = 判据；单段最多 4 条指令，全 run 123 条 > 阈值 10 ⇒ 累计口径必在 11 条处炸。
            vm.run()
            feeder.join(20_000)
            assertEquals(iterations.toLong(), host.waits, "每次循环都真实跨过了一次输入等待")
            assertEquals(0, sink.pendingCount, "喂入条数与消费条数严格对表")
            // ③ 的另一半：executedSteps 仍是"最近一次 run 执行的总指令数"（口径不变）。
            assertEquals(3L + 4L * iterations + 1L, vm.executedSteps)
        } finally {
            sink.close()
            feeder.join(5_000)
        }
    }

    // ------------------------------------------------------------------
    // ③ 段落归零的对照实验：同一程序，只改「语句是否等待」
    // ------------------------------------------------------------------

    @Test
    fun `跨过输入边界后段落计数从0重算而executedSteps口径不变`() {
        val wait = stmt("WAIT")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _NOP
              _NOP
              _NOP
              _NOP
              _NOP
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(wait),
        )
        val cap = 6L

        // 等待版：5 NOP + marker = 6 条到边界，marker 执行完段落归零，_HALT 从 1 重算 ⇒ 跑完。
        // 同步 NoopInputSink 只用于这条对照（真队列的阻塞形态由 ② 锁死）。
        val waitHost = WaitHost(NoopInputSink, setOf(wait))
        val vmWait = VirtualMachine(program, waitHost, VmConfig(maxStepsPerRun = cap))
        vmWait.run()
        assertEquals(1L, waitHost.waits)
        assertEquals(7L, vmWait.executedSteps, "executedSteps = 全 run 总指令数（7），不是段落数")

        // 同一程序、语句不等待：_HALT 取指时段落=总=7 > 6 ⇒ 仍在同一阈值抛。
        val noWaitHost = WaitHost(NoopInputSink, emptySet())
        val vmNoWait = VirtualMachine(program, noWaitHost, VmConfig(maxStepsPerRun = cap))
        val e = assertFailsWith<StepLimitExceeded> { vmNoWait.run() }
        assertEquals(7L, e.steps)
    }
}
