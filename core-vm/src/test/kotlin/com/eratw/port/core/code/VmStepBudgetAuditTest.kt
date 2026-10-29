package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BH3-VMCORE 判据 ②：步数预算与让出边界。
 *
 * 原版口径（C#）：`runScriptProc` 的主循环边界是 `console.IsRunning`
 * （spec: Process.cs:1081 `while (console.IsRunning && !state.ScriptEnd)`）；
 * 死循环防护是**墙钟**弹框（spec: Process.cs:493-512 checkInfiniteLoop，
 * :496 时限 / :509 用户选继续即清零），唯一复位点是 `EmueraConsole.Await` 里的
 * `UpdateCheckInfiniteLoopState()`（spec: Process.cs:487-491，调用点
 * EmueraConsole.cs:849）——**原版没有「每 run 指令上限」**。本移植的输入等待是阻塞式，
 * 计数边界挪到让出点上（[VmHost.yieldBoundaryCount]，M10-RUNB/M10-AWAIT，
 * 详见 [VmConfig] KDoc）。
 *
 * 本文件锁四件移植侧契约：
 * 1. 检查点在**每条指令取指前**（不跨让出点时抛点 = cap+1，与改造前逐条一致）；
 * 2. 超预算抛点从不在语句中间 —— 语句要么完整执行要么尚未开始（VM 不可抢占语句）；
 * 3. 撞闸后 VM 实例状态可续跑（depth/frameBase/段计数/入口帧全部重置，作用域栈已回滚）；
 * 4. 段计数不跨 run 累计（每次 run 从 0 起算）。
 */
class VmStepBudgetAuditTest {

    /** 计数语句执行次数的宿主（另记 enter/exit 配对）。 */
    private class CountingHost : VmHost {
        override val evalContext: EvalContext? = null
        var executed = 0
            private set
        var enters = 0
            private set
        var exits = 0
            private set

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            executed++
            return StmtOutcome.Normal
        }

        override fun onFunctionEnter(funcIndex: Int) { enters++ }
        override fun onFunctionExit(funcIndex: Int) { exits++ }
    }

    private fun stmt(raw: String): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, raw)

    private fun vmOf(
        src: String,
        config: VmConfig = VmConfig(),
        statements: List<Statement> = emptyList(),
        host: VmHost = CountingHost(),
    ): Pair<VirtualMachine, CountingHost> {
        val program = Assembler.assemble(src.trimIndent(), statements = statements)
        return VirtualMachine(program, host, config) to (host as CountingHost)
    }

    // ------------------------------------------------------------------
    // 1. 检查点位置：取指前，无让出点时抛点 = cap + 1
    // ------------------------------------------------------------------

    @Test
    fun `预算为零时第一条指令都不执行`() {
        val (vm, host) = vmOf(
            """
            PRINTL stmt#0
            _HALT
            """,
            VmConfig(maxStepsPerRun = 0),
            listOf(stmt("PRINTL")),
        )
        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(1L, e.steps, "第一次取指即超预算（steps=1 > 0）")
        assertEquals(0, host.executed, "语句尚未分派：期望 0 实际 ${'$'}{host.executed}")
    }

    @Test
    fun `无让出点的紧循环抛点仍是 cap 加一`() {
        val (vm, _) = vmOf(
            """
            .func main slots=0
            loop:
              _JUMP loop
            """,
            VmConfig(maxStepsPerRun = 500),
        )
        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(501L, e.steps, "无让出边界可跨 ⇒ 抛点与改造前逐条一致")
    }

    // ------------------------------------------------------------------
    // 2. 语句原子性：抛点永不在语句中间
    // ------------------------------------------------------------------

    @Test
    fun `撞闸抛点从不在语句中间`() {
        // 3 条语句 + _HALT，预算 3：三条语句各完整执行一次，第 4 次取指（_HALT）才抛。
        // 若检查点落在语句内部（或语句可被抢占），executed 会小于 3。
        val (vm, host) = vmOf(
            """
            .func main slots=1
              PRINTL stmt#0
              PRINTL stmt#0
              PRINTL stmt#0
              _HALT
            """,
            VmConfig(maxStepsPerRun = 3),
            listOf(stmt("PRINTL")),
        )
        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(4L, e.steps, "3 条语句各 1 步，第 4 次取指超预算")
        assertEquals(3, host.executed, "三条语句已各完整执行一次: 期望 3 实际 ${'$'}{host.executed}")
    }

    @Test
    fun `预算恰等于指令条数时程序跑完不抛`() {
        // 4 条指令（3 NOP + _HALT）、预算 4：_HALT 取指时 steps=4 不 > 4 ⇒ 正常返回。
        val (vm, _) = vmOf(
            """
            .func main slots=0
              _NOP
              _NOP
              _NOP
              _HALT
            """,
            VmConfig(maxStepsPerRun = 4),
        )
        vm.run()
        assertEquals(4L, vm.executedSteps)
    }

    // ------------------------------------------------------------------
    // 3. 撞闸后 VM 实例可续跑
    // ------------------------------------------------------------------

    @Test
    fun `撞闸后的同一 VM 实例可续跑其他入口且作用域栈已回滚`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL looper, 0, R0
              _HALT

            .func looper slots=0
            loop:
              _JUMP loop

            .func quick slots=2
              _SET_INT R0, 7
              _RET R0
            """.trimIndent(),
        )
        val host = CountingHost()
        val vm = VirtualMachine(program, host, VmConfig(maxStepsPerRun = 100))

        assertFailsWith<StepLimitExceeded> { vm.run(1) }
        assertEquals(host.enters, host.exits, "撞闸后 enter/exit 必须配对（作用域栈回滚）")

        // 续跑 quick：depth/frameBase/段计数/入口帧都应已重置
        assertEquals(7L, vm.run(2), "撞闸后续跑返回值必须正确")
        assertEquals(2L, vm.executedSteps, "quick = _SET_INT + _RET 两条: 实际 ${'$'}{vm.executedSteps}")
        assertEquals(host.enters, host.exits, "两次 run 之后 enter/exit 仍配对")
    }

    @Test
    fun `段计数不跨 run 累计`() {
        // 同一入口连跑两次、预算恰够：若 steps 跨 run 累计，第二次必抛。
        val (vm, _) = vmOf(
            """
            .func main slots=2
              _SET_INT R0, 1
              _ADD R0, R0, R0
              _RET R0
            """,
            VmConfig(maxStepsPerRun = 3),
        )
        assertEquals(2L, vm.run())
        assertEquals(2L, vm.run(), "第二次 run 的段计数从 0 重算，不累计上一次")
        assertEquals(3L, vm.executedSteps)
    }

    // ------------------------------------------------------------------
    // 4. 让出边界复位段计数（与 VmInputBoundaryTest 同口径的最小复现）
    // ------------------------------------------------------------------

    @Test
    fun `跨过一次让出边界后段落计数归零`() {
        val wait = stmt("WAIT")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _NOP
              _NOP
              PRINTL stmt#0
              _NOP
              _NOP
              _NOP
              _HALT
            """.trimIndent(),
            statements = listOf(wait),
        )
        // 语句执行时把让出边界 +1（模拟一次输入等待返回）
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            var boundaries = 0L
                private set
            override fun print(text: String) {}
            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
                boundaries++
                return StmtOutcome.Normal
            }
            override fun yieldBoundaryCount(): Long = boundaries
        }
        val vm = VirtualMachine(program, host, VmConfig(maxStepsPerRun = 4))
        vm.run()
        assertEquals(1L, host.boundaries)
        assertEquals(7L, vm.executedSteps, "executedSteps = 全 run 总指令数，不受段落归零影响")
        assertTrue(vm.lastPc >= 0, "正常跑完")
    }
}
