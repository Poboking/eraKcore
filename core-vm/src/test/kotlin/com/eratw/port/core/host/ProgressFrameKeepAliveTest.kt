package com.eratw.port.core.host

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 页中保活（[LineBufferConsoleSink.publishProgressFrame]）的 host 面钉子。
 *
 * 背景：`EVENTTRAIN` 整段在慢机上约 130 s（specs/verify-notes.md 的 22.2 节），
 * 帧闸原本只在让出边界（AWAIT / 输入等待）发布，长链中途整屏停在上一页。页中保活
 * 只做「重抓当前可见行 + 推进 revision」，不打开发布闸、不改死循环闸的段落口径。
 *
 * 这里同时钉两条轴：
 * 1. sink 语义（用例 1-4）：冻结段补一帧、无脏不发、静默期、节流；
 * 2. 段落口径隔离（用例 5）：同一条长链在「检查点里调保活」与「不调」两种配置下，
 *    `executedSteps`、死循环采样次数（[VmHost.askInfiniteLoop]）与检查点频次逐值相同。
 */
class ProgressFrameKeepAliveTest {

    private companion object {
        /** 原版死循环闸的采样行数（`Process.cs:990` 的 `state.lineCount % 10000 == 0`）。 */
        const val CHECK_LINES = 10_000

        /** 墙钟阈值 1 ms；宿主在每个采样段第一条语句睡 5 ms，保证判据必被触发。 */
        const val ALERT_MS = 1L

        const val SEGMENT_SLEEP_MS = 5L
    }

    /** 单调假时钟：让静默期 / 节流两条判据与机器快慢无关。 */
    private class ManualClock(var ms: Long = 0L)

    private fun newSink(clock: ManualClock): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 40, progressClockMs = { clock.ms })

    private fun texts(s: LineBufferConsoleSink): List<String> =
        s.frameSnapshot().map { s.textOf(it) }

    // ------------------------------------------------------------------
    // 1. 只上屏、不开口闸
    // ------------------------------------------------------------------

    @Test
    fun `页中发布推进 revision 并给出当前行快照且闸保持关闭`() {
        val clock = ManualClock(1_000L)
        val s = newSink(clock)
        s.holdFrame()
        s.printLine("甲")
        s.printLine("乙")
        val base = s.revision

        clock.ms += LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()

        assertEquals(base + 1, s.revision, "页中发布只推进 revision")
        assertEquals(listOf("甲", "乙"), texts(s), "快照 = 此刻已打印的可见行")

        // 之后的可见写（含 CLEARLINE）只记脏：保活没有打开 publishGateOpen。
        s.deleteLine(2)
        assertEquals(base + 1, s.revision, "页中发布之后的新可见写不得立即发布（闸没被打开）")
        assertEquals(listOf("甲", "乙"), texts(s), "冻结快照停在页中发布那一刻")

        s.publishFrame()
        assertEquals(base + 2, s.revision, "下一次让出边界才发布冻结段完整帧")
        assertEquals(emptyList<String>(), texts(s), "CLEARLINE 的结果在边界帧里才上屏")
        assertTrue(s.snapshot().isEmpty(), "活表与边界帧一致")
    }

    // ------------------------------------------------------------------
    // 2. 无脏不发 / 幂等
    // ------------------------------------------------------------------

    @Test
    fun `上次快照之后没有可见写则不发帧`() {
        val clock = ManualClock(1_000L)
        val s = newSink(clock)
        s.holdFrame()
        s.printLine("甲")

        clock.ms += LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        val rev = s.revision

        // 时钟走很远也没有新可见写：序列号判据先挡住，不允许重复发同一帧。
        clock.ms += 60_000L
        s.publishProgressFrame()
        assertEquals(rev, s.revision, "无可见写时页中发布必须幂等")
    }

    // ------------------------------------------------------------------
    // 3. 节流：同一时段内最多一帧
    // ------------------------------------------------------------------

    @Test
    fun `两次页中发布之间受 PROGRESS_MIN_INTERVAL_MS 节流`() {
        val clock = ManualClock(1_000L)
        val s = newSink(clock)
        s.holdFrame()
        s.printLine("甲")
        clock.ms += LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        val firstPublishAt = clock.ms
        val rev = s.revision

        // 新可见写发生在首次发布之后不久；静默期过了，但节流窗口还没过。
        clock.ms = firstPublishAt + 100L
        s.printLine("乙")
        clock.ms = firstPublishAt + 100L + LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        assertEquals(rev, s.revision, "距上次页中发布不足 MIN_INTERVAL ⇒ 不发")

        clock.ms = firstPublishAt + LineBufferConsoleSink.PROGRESS_MIN_INTERVAL_MS
        s.publishProgressFrame()
        assertEquals(rev + 1, s.revision, "节流窗口过后补发一次")
        assertEquals(listOf("甲", "乙"), texts(s))
    }

    // ------------------------------------------------------------------
    // 4. 静默期：刚写完不抓中间态
    // ------------------------------------------------------------------

    @Test
    fun `距最近一次可见写不足 QUIET_MS 时不发`() {
        val clock = ManualClock(1_000L)
        val s = newSink(clock)
        s.holdFrame()
        s.printLine("甲")

        clock.ms = 1_000L + LineBufferConsoleSink.PROGRESS_QUIET_MS - 1L
        s.publishProgressFrame()
        assertEquals(0L, s.revision, "刚写完（< QUIET_MS）⇒ 不发，避免 CLEARLINE 后的中间态")

        clock.ms = 1_000L + LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        assertEquals(1L, s.revision, "静默期满足后发一帧")
        assertEquals(listOf("甲"), texts(s))
    }

    // ------------------------------------------------------------------
    // 4b. REPLICA 档回显不得被页中保活单独发布
    // ------------------------------------------------------------------

    @Test
    fun `页中保活不单独发布 REPLICA 回显行`() {
        val clock = ManualClock(1_000L)
        val s = newSink(clock)
        s.printLine("地图页")
        s.publishFrame()
        val published = s.revision

        // REPLICA 回显入口自己会 holdFrame：回显只进冻结段，等脚本 CLEARLINE／重印一起成帧。
        s.printEchoLine("UPDATE")
        clock.ms += LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        assertEquals(published, s.revision, "回显-only 的冻结段不得被页中保活提前发布")
        assertEquals(listOf("地图页"), texts(s), "发布帧仍停在回显之前的整帧")

        // 脚本随后 CLEARLINE（地图页 + 回显）并重印：新的可见写越过回显序号，保活恢复。
        s.deleteLine(2)
        s.printLine("地图页2")
        clock.ms += LineBufferConsoleSink.PROGRESS_QUIET_MS
        s.publishProgressFrame()
        assertEquals(published + 1, s.revision, "回显被清掉并有新可见写后，页中保活才能发帧")
        assertEquals(listOf("地图页2"), texts(s))
    }

    // ------------------------------------------------------------------
    // 5. 段落口径隔离：同一条长链，有无保活逐值相同
    // ------------------------------------------------------------------

    /** 一段长链的读数；revision = -1 表示该轮没有保活 sink。 */
    private data class Reading(
        val executedSteps: Long,
        val alerts: Int,
        val checkpoints: Long,
        val dispatches: Int,
        val revision: Long,
    )

    /** 语句 marker 的载体：语义全在测试宿主里做，与真语料无关。 */
    private fun printl(): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL")

    /**
     * `iterations` 次 marker 派发的循环体（`_ADD`/`_LT`/`_JUMP*` 不是语句 marker，
     * 因此行数 == iterations），照 InfiniteLoopGuardTest 的同一夹具。
     */
    private fun markerLoop(iterations: Int): Program {
        val src = """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R3, $iterations
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _LT R2, R0, R3
              _JUMP_IF_FALSE R2, done
              _JUMP loop
            done:
              _HALT
        """.trimIndent()
        return Assembler.assemble(src, statements = listOf(printl()))
    }

    /**
     * 记录告警与检查点的宿主。`sink != null` 时在检查点里调 [LineBufferConsoleSink.publishProgressFrame]，
     * 并让假时钟每次前进 1 s，使保活真的走到发布分支。
     */
    private class KeepAliveVmHost(
        private val sink: LineBufferConsoleSink?,
        private val clock: ManualClock,
    ) : VmHost {
        override val evalContext: EvalContext? = null

        var dispatches = 0
            private set
        var alerts = 0
            private set
        var checkpoints = 0L
            private set

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            dispatches++
            // 每 1000 条语句一行可见写：让保活有真实的冻结段内容可抓，又不把测试拖成万行排版。
            if (sink != null && (dispatches == 1 || dispatches % 1_000 == 0)) {
                sink.printLine("line $dispatches")
            }
            // 与 InfiniteLoopGuardTest 同款：每个采样段的起点真实睡一次，保证墙钟必过 1 ms。
            if (dispatches % CHECK_LINES == 1) Thread.sleep(SEGMENT_SLEEP_MS)
            return StmtOutcome.Normal
        }

        override fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
            alerts++
            return InfiniteLoopAnswer.Continue
        }

        override fun pressureCheckpoint() {
            checkpoints++
            if (sink != null) {
                clock.ms += 1_000L
                sink.publishProgressFrame()
            }
        }
    }

    private fun runLongChain(keepAlive: Boolean): Reading {
        val clock = ManualClock()
        val sink = if (keepAlive) newSink(clock).also { it.holdFrame() } else null
        val host = KeepAliveVmHost(sink, clock)
        val vm = VirtualMachine(
            markerLoop(3 * CHECK_LINES),
            host,
            VmConfig(maxStepsPerRun = Long.MAX_VALUE, maxRunMillis = 0L, infiniteLoopAlertTimeMs = ALERT_MS),
        )
        vm.run()
        return Reading(
            executedSteps = vm.executedSteps,
            alerts = host.alerts,
            checkpoints = host.checkpoints,
            dispatches = host.dispatches,
            revision = sink?.revision ?: -1L,
        )
    }

    @Test
    fun `页中保活不改变 executedSteps 与死循环采样次数`() {
        val plain = runLongChain(keepAlive = false)
        val keep = runLongChain(keepAlive = true)

        // 等价判据：VM 侧唯一可观察的段落读数必须逐值相同。
        assertEquals(plain.executedSteps, keep.executedSteps, "executedSteps 口径不受保活影响")
        assertEquals(plain.alerts, keep.alerts, "死循环采样次数不受保活影响（loopLines 未被重置）")
        assertEquals(plain.checkpoints, keep.checkpoints, "检查点频次不受保活影响")
        assertEquals(plain.dispatches, keep.dispatches, "语句派发次数不受保活影响")
        assertTrue(plain.alerts > 0, "夹具必须真的触发死循环采样，否则本条判据空转")
        assertTrue(keep.revision > 0L, "保活侧必须真的发布了页中帧，否则隔离断言空转")
    }
}
