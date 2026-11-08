package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 后台暂停闸（跨线程）的定向单测。
 *
 * 覆盖两层：
 * - VM 主语句循环的闸语义（合成程序：语句 marker + 回边，语句计数走宿主）；
 * - [ProcessRuntime] 的暂停位实现（[ProcessRuntime.setBackgroundPaused] /
 *   [ProcessRuntime.awaitResume]）在真进程里的阻塞、唤醒与中断收敛。
 *
 * 语义口径（[VmHost.backgroundPaused] 的 KDoc）：暂停不消耗步数、不改变执行顺序、
 * 不推进任何计时；恢复后从原语句继续。因此断言只读「宿主被调到的语句条数」：
 * 暂停期间它必须停住，恢复后继续增长。
 */
class BackgroundPauseTest {

    /**
     * 记录型宿主：每次真实语句执行 +1；暂停位与 [ProcessRuntime] 同形
     * （`@Volatile` 标志 + 50 ms 超时轮询 + interrupt 立即返回）。
     */
    private class PauseProbe : VmHost {
        val executed = AtomicLong()

        @Volatile
        private var paused = false

        private val lock = ReentrantLock()
        private val resumeSignal = lock.newCondition()

        override val evalContext: EvalContext? = null

        override fun print(text: String) {}

        override fun backgroundPaused(): Boolean = paused

        override fun awaitResume() {
            while (paused) {
                if (Thread.currentThread().isInterrupted) return
                lock.lock()
                try {
                    if (paused) resumeSignal.await(50L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } finally {
                    lock.unlock()
                }
            }
        }

        fun setBackgroundPaused(paused: Boolean) {
            lock.lock()
            try {
                this.paused = paused
                if (!paused) resumeSignal.signalAll()
            } finally {
                lock.unlock()
            }
        }

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            executed.incrementAndGet()
            return StmtOutcome.Normal
        }
    }

    /**
     * 每执行一条语句就报一次让出边界的宿主（等价于 `AWAIT` 逐帧让出）。
     *
     * 用来复现主循环周期检查够不着的形状：`steps` 在每个让出边界清零，短段永远攒不到
     * [com.eratw.port.core.code.VirtualMachine.SHUTDOWN_CHECK_MASK] 的 65536，只有让出边界
     * 自己再看一眼中断位才能收口（@BADAPPLE 6573 帧那种动画）。
     */
    private class YieldingProbe : VmHost {
        val executed = AtomicLong()

        @Volatile
        private var boundaries = 0L

        override val evalContext: EvalContext? = null

        override fun print(text: String) {}

        override fun yieldBoundaryCount(): Long = boundaries

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            executed.incrementAndGet()
            boundaries++
            return StmtOutcome.Normal
        }
    }

    // ------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------

    /** 语句 marker + 回边的无限循环（暂停/中断测试只关心「语句还在不在跑」）。 */
    private fun loopingProgram(): Program {
        val spin = InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL spin")
        return Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 0
              _SET_INT R1, 1
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _JUMP loop
            """.trimIndent(),
            statements = listOf(spin),
        )
    }

    /** 只有 main 一条 `_HALT` 的程序（[ProcessRuntime] 的暂停位测试不需要真跑语句）。 */
    private fun haltProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.setFunctionSlots(main, 1)
        return b.build(0)
    }

    private fun startVm(probe: PauseProbe): Pair<VirtualMachine, Thread> {
        val vm = VirtualMachine(loopingProgram(), probe, VmConfig(maxStepsPerRun = Long.MAX_VALUE))
        val t = Thread { vm.run() }
        t.isDaemon = true
        // 中断退出走 CodeEE（见 VM 的暂停闸）：这里吞掉未捕获异常，避免测试输出噪声。
        t.setUncaughtExceptionHandler { _, _ -> }
        t.start()
        return vm to t
    }

    private fun awaitCount(probe: PauseProbe, atLeast: Long, timeoutMs: Long = 2_000L) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (probe.executed.get() < atLeast) {
            if (System.nanoTime() > deadline) {
                fail("等待语句计数 >= $atLeast 超时（当前 ${probe.executed.get()}）")
            }
            Thread.sleep(5L)
        }
    }

    // ------------------------------------------------------------------
    // VM 闸：暂停停住、恢复继续
    // ------------------------------------------------------------------

    @Test
    fun `暂停后语句计数停住 恢复后继续增长`() {
        val probe = PauseProbe()
        val (_, vmThread) = startVm(probe)
        awaitCount(probe, 1_000L)

        probe.setBackgroundPaused(true)
        // 暂停在语句边界生效：先给一个边界窗口，再读基线。
        Thread.sleep(100L)
        val atPause = probe.executed.get()
        Thread.sleep(200L)

        assertEquals(atPause, probe.executed.get(), "暂停期间不得再执行任何语句")
        assertTrue(vmThread.isAlive, "暂停不是结束：VM 线程应阻塞在 awaitResume")

        probe.setBackgroundPaused(false)
        awaitCount(probe, atPause + 100L)

        // 收口：暂停中 interrupt 才会走「暂停 → 中断」退出路径（见下一条用例）。
        probe.setBackgroundPaused(true)
        vmThread.interrupt()
        vmThread.join(2_000L)
        assertFalse(vmThread.isAlive, "测试收口失败：VM 线程未退出")
    }

    // ------------------------------------------------------------------
    // 暂停中 interrupt：线程必须在 2s 内退出
    // ------------------------------------------------------------------

    @Test
    fun `暂停中 interrupt 后 VM 线程两秒内退出`() {
        val probe = PauseProbe()
        val (_, vmThread) = startVm(probe)
        awaitCount(probe, 1_000L)

        probe.setBackgroundPaused(true)
        Thread.sleep(100L)

        val startedNs = System.nanoTime()
        vmThread.interrupt()
        vmThread.join(2_000L)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

        assertFalse(vmThread.isAlive, "暂停中 interrupt 后 VM 线程未在 2s 内退出")
        assertTrue(elapsedMs < 2_000L, "退出耗时 ${elapsedMs}ms 超出口径")
    }

    // ------------------------------------------------------------------
    // 未暂停 interrupt：纯计算长链也必须在 2s 内退出（2026-10-31 玩家报告）
    // ------------------------------------------------------------------

    /**
     * 复现玩家「切到 QQ 再回来 = 启动失败：上一局的内存还没释放（422 MiB / 512 MiB）」。
     *
     * 旧口径只把 `interrupt` 当「叫醒暂停」用：VM 的检查全在 `backgroundPaused` 分支里，
     * 一段纯计算长链（地图重修 `CHARA_MOVEMENT` 是上亿条指令量级）根本不进那个分支 ——
     * Activity 销毁时 `GameHost.close()` 的 interrupt 无效，旧会话带着整份 Program 一直跑，
     * 新 Activity 一装载就撞上堆基线硬闸。
     *
     * 判据：**不**先 `setBackgroundPaused(true)`，直接 interrupt，线程必须在 2s 内退出，
     * 且退出原因是宿主关闭（`CodeEE` 原话），不是步数/墙钟闸。
     */
    @Test
    fun `未暂停 interrupt 后纯计算长链两秒内退出`() {
        val probe = PauseProbe()
        val vm = VirtualMachine(loopingProgram(), probe, VmConfig(maxStepsPerRun = Long.MAX_VALUE))
        val thrown = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val vmThread = Thread {
            try {
                vm.run()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        vmThread.isDaemon = true
        vmThread.start()
        try {
            awaitCount(probe, 10_000L)

            val startedNs = System.nanoTime()
            vmThread.interrupt()
            vmThread.join(2_000L)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

            assertFalse(vmThread.isAlive, "未暂停的纯计算长链没有响应 interrupt（旧口径：只有暂停分支看中断位）")
            assertTrue(elapsedMs < 2_000L, "退出耗时 ${elapsedMs}ms 超出口径")
            val e = thrown.get()
            assertTrue(e is com.eratw.port.core.code.CodeEE, "退出原因应为宿主关闭（CodeEE），实际 $e")
            assertTrue(
                e.message.orEmpty().contains("宿主正在关闭"),
                "退出文案应与暂停分支同源（宿主正在关闭），实际 ${e.message}",
            )
        } finally {
            vmThread.interrupt()
            vmThread.join(2_000L)
        }
    }

    /**
     * 让出边界也必须看中断位：`AWAIT` 逐帧让出的短段会不断把 `steps` 清零，
     * 主循环的 65536 周期检查永远够不着；旧口径下这种动画链在 `close()` 之后
     * 仍会跑到帧尾（@BADAPPLE 6573 帧）。
     *
     * 宿主 [YieldingProbe] 每条语句报一次让出边界，等价于每帧一次 `AWAIT`。
     */
    @Test
    fun `频繁让出的短段在 interrupt 后两秒内退出`() {
        val probe = YieldingProbe()
        val vm = VirtualMachine(loopingProgram(), probe, VmConfig(maxStepsPerRun = Long.MAX_VALUE))
        val thrown = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val vmThread = Thread {
            try {
                vm.run()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        vmThread.isDaemon = true
        vmThread.start()
        try {
            val deadline = System.nanoTime() + 2_000L * 1_000_000L
            while (probe.executed.get() < 10_000L) {
                if (System.nanoTime() > deadline) fail("等待语句计数超时（当前 ${probe.executed.get()}）")
                Thread.sleep(5L)
            }

            val startedNs = System.nanoTime()
            vmThread.interrupt()
            vmThread.join(2_000L)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

            assertFalse(vmThread.isAlive, "频繁让出的短段没有响应 interrupt（周期检查被 steps 清零绕开）")
            assertTrue(elapsedMs < 2_000L, "退出耗时 ${elapsedMs}ms 超出口径")
            val e = thrown.get()
            assertTrue(e is com.eratw.port.core.code.CodeEE, "退出原因应为宿主关闭（CodeEE），实际 $e")
            assertTrue(
                e.message.orEmpty().contains("宿主正在关闭"),
                "退出文案应与暂停分支同源（宿主正在关闭），实际 ${e.message}",
            )
        } finally {
            vmThread.interrupt()
            vmThread.join(2_000L)
        }
    }

    // ------------------------------------------------------------------
    // ProcessRuntime 的暂停位实现
    // ------------------------------------------------------------------

    private fun runtime(): ProcessRuntime =
        ProcessRuntime(haltProgram(), VariableStore(), FunctionTable(emptyList()), VmConfig())

    @Test
    fun `runtime 暂停位阻塞 awaitResume 恢复即唤醒`() {
        val rt = runtime()
        rt.setBackgroundPaused(true)
        val done = CountDownLatch(1)
        val waiter = Thread { rt.awaitResume(); done.countDown() }
        waiter.isDaemon = true
        waiter.start()

        assertFalse(done.await(200L, TimeUnit.MILLISECONDS), "暂停位为 true 时 awaitResume 不得返回")

        rt.setBackgroundPaused(false)
        assertTrue(done.await(2_000L, TimeUnit.MILLISECONDS), "恢复后 awaitResume 必须在 2s 内返回")
        waiter.join(2_000L)
    }

    @Test
    fun `runtime 暂停中 interrupt 后 awaitResume 立即返回`() {
        val rt = runtime()
        rt.setBackgroundPaused(true)
        val done = CountDownLatch(1)
        val waiter = Thread { rt.awaitResume(); done.countDown() }
        waiter.isDaemon = true
        waiter.start()
        assertFalse(done.await(200L, TimeUnit.MILLISECONDS), "暂停位为 true 时 awaitResume 不得返回")

        waiter.interrupt()
        assertTrue(done.await(2_000L, TimeUnit.MILLISECONDS), "interrupt 后 awaitResume 必须在 2s 内返回")
        waiter.join(2_000L)
    }
}
