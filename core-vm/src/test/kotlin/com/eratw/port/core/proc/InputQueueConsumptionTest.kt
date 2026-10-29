package com.eratw.port.core.proc

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 排队提交在**等待形态切换**时的消费规则：谁吃、吃几次、多余的去哪。
 *
 * 判据全部来自原版的单线程事件序（`MinorShift.Emuera.GameView/EmueraConsole.cs`）：
 * - `PressEnterKey` 入口先置 `MesSkip`，`state != WaitInput` 直接丢（:1371-1376）；
 * - `Void` 在出队之前早退 ⇒ 这次回车**不进程序**（:1417-1420），不是「留给下一次等待」；
 * - `endTimer` 越过截止点后先 `RunEmueraProgram("")` 把这次等待收掉（:1043/:1068），
 *   之后才到达的按键属于**下一次** `WaitInput`；
 * - 反之，按键先到 ⇒ `doInputToEmueraProgram` 收下它并在 :1278 `stopTimer` ⇒ 不算超时。
 *
 * 本文件与 [InputKindModelingTest] 的分工：那边钉「每个形态吃什么、交付什么」，
 * 这边钉「一条等待吃几条、剩下的去哪、超时与按键谁先到」。
 */
class InputQueueConsumptionTest {

    /** 每读一次前进 [stepNanos] 的假单调时钟（纳秒）；step 大于被测时限即可零墙钟驱动超时。 */
    private fun clockOf(stepNanos: Long): () -> Long {
        val now = AtomicLong(0L)
        return { now.addAndGet(stepNanos) }
    }

    private fun req(
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        hasDef: Boolean = false,
        defInt: Long = 0L,
        timeLimit: Long = -1L,
    ) = InputRequest(
        type = type,
        form = form,
        hasDef = hasDef,
        defInt = defInt,
        timeLimit = timeLimit,
    )

    /** 等等待方进入等待态（带超时，红要红成断言而不是挂住）。 */
    private fun awaitAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) Thread.sleep(5)
        assertTrue(sink.isAwaiting, "等待方未进入等待态")
    }

    // ------------------------------------------------------------------

    /**
     * VOID 期间（含进入 VOID 之前就入队的）提交一律丢弃：原版这次回车落在
     * `state == Running`（:1373-1376）或 `InputType.Void`（:1417-1420）两条早退上，
     * 都不会进程序。下一次取值等待必须重新等一次按键。
     */
    @Test
    fun `VOID 丢弃提交 下一次等待不会自动吃它`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("5")
        val out = sink.waitInput(req(form = InputRequest.RequestForm.VOID, timeLimit = 100))
        assertTrue(out.isTimeout, "Void 只由超时推进")
        assertEquals(0, sink.pendingCount, "那次回车被丢弃，不排队")

        val done = CountDownLatch(1)
        thread(name = "after-void-waiter") {
            runCatching { sink.waitInput(req(type = InputRequest.InputType.STR)) }
            done.countDown()
        }
        assertFalse(
            done.await(30, TimeUnit.MILLISECONDS),
            "下一次等待不得凭空拿到 Void 期间那次提交（原版要再按一次回车）",
        )
        sink.close()
        assertTrue(done.await(2, TimeUnit.SECONDS), "close 必须唤醒等待方")
    }

    /** Void 早退发生在 `MesSkip = keySkip`（:1373）之后 ⇒ 丢弃提交也要记下跳过标志。 */
    @Test
    fun `VOID 丢弃提交仍记下跳过标志`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("", mesSkip = true)
        val out = sink.waitInput(req(form = InputRequest.RequestForm.VOID, timeLimit = 100))
        assertTrue(out.isTimeout)
        assertEquals(0, sink.pendingCount)
        assertTrue(sink.consumeMesSkip(), "MesSkip 在入口就置（:1373），Void 早退不回滚")
    }

    /**
     * 截止点先到、按键后到：先交超时默认值（TINPUT 恒 `HasDefValue`，FunctionIdentifier.cs:1020），
     * 后到的那条提交留在队里给下一次等待 —— 原版 `endTimer` 已经 `RunEmueraProgram("")`
     * 把这次等待收掉了（:1043/:1068），按键只可能落到新的 `WaitInput`。
     *
     * 本用例对「等待方先 fires 还是先收到提交」不敏感：两条路径的终态一致
     * （77 + isTimeout + 队里留下 5），无需 settle。
     */
    @Test
    fun `截止点之后到达的提交不覆盖超时默认值`() {
        val clock = AtomicLong(0L)
        val sink = QueueInputSink(timeSource = { clock.get() })
        val ref = AtomicReference<InputOutcome?>()
        val t = thread(name = "late-answer") {
            ref.set(sink.waitInput(req(hasDef = true, defInt = 77, timeLimit = 100)))
        }
        awaitAwaiting(sink)
        clock.set(1_000_000_000L) // deadline 在进入等待时按 clock=0 算出 = 100ms
        sink.submit("5")
        t.join(2_000)
        assertFalse(t.isAlive, "超时必须推进（整数有默认值，:1119-1124）")
        val out = assertNotNull(ref.get())
        assertEquals(77L, out.intValue, "这次等待收的是超时默认值，不是后到的 5")
        assertTrue(out.isTimeout)
        assertEquals(1, sink.pendingCount, "后到的提交留在队里")
        assertEquals(5L, sink.waitInput(req()).intValue, "它属于下一次等待")
    }

    /**
     * 非取值形态同理：ENTER_KEY 超时推进后，后到的提交不能被这次等待吞掉，
     * 否则紧接着的 INPUT 会凭空拿到一条早于提示的按键。
     */
    @Test
    fun `ENTER_KEY 超时后到达的提交留给下一次等待`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val out = sink.waitInput(req(form = InputRequest.RequestForm.ENTER_KEY, timeLimit = 100))
        assertTrue(out.isTimeout)
        sink.submit("x")
        assertEquals(
            "x",
            sink.waitInput(req(type = InputRequest.InputType.STR)).strValue,
            "超时收尾之后才入队的提交归下一次等待",
        )
    }

    /**
     * 截止点之前到达的按键先被处理（原版同一 UI 线程按事件到达序）⇒ `stopTimer`（:1278）
     * 之后计时器不再触发，这一次不算超时。用假时钟把「按键在期限内」钉成确定性输入。
     *
     * 确定性保证：`awaitAwaiting` 只证明等待方置起了 `awaiting`（在截止判定之前），
     * 若不等等待方真正 park 进 `awaitNanosQuietly` 就抢提交，等待方可能先在「队空 +
     * 时钟已越点」那一支上把超时 fires 掉（firedNow 读的是被主线程改过的时钟）——
     * 交付就会从 5 变成超时默认值 77。故这里补一次 settle：等待方此时必然已挂在
     * `awaitNanosQuietly(100ms)` 上（纯计算路径，微秒级；30ms 有两个数量级余量），
     * 之后的提交只会把它唤醒重判，而 `inTime` 只看 `atNs` 与 deadline、与当时时钟无关。
     */
    @Test
    fun `期限内到达的按键优先于计时器 不报超时`() {
        val clock = AtomicLong(0L)
        val sink = QueueInputSink(timeSource = { clock.get() })
        val ref = AtomicReference<InputOutcome?>()
        val t = thread(name = "in-time-answer") {
            ref.set(sink.waitInput(req(hasDef = true, defInt = 77, timeLimit = 100)))
        }
        awaitAwaiting(sink)
        Thread.sleep(30) // 等等待方 park 进 awaitNanosQuietly（见本用例 KDoc）
        sink.submit("5") // atNs = 0 <= deadline(100ms)
        clock.set(1_000_000_000L) // 即使随后越点，也不该把这次交付改判成超时
        t.join(2_000)
        assertFalse(t.isAlive)
        val out = assertNotNull(ref.get())
        assertEquals(5L, out.intValue, "按键先到 ⇒ 交按键值")
        assertFalse(out.isTimeout, "stopTimer 之后 endTimer 不会再跑（:1278/:1035-1038）")
        assertEquals(0, sink.pendingCount)
    }
}
