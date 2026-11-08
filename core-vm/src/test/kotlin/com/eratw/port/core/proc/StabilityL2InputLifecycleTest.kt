package com.eratw.port.core.proc

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L2 稳定性通道：输入等待的**截止点算术**与 BEGIN 系统等待的**帧闸成对性**。
 *
 * 两条判据都取自 `reference/emuera-cs` 的同一段控制台代码：
 * - 限时等待的剩余时间 = `Timelimit - ElapsedMilliseconds`（EmueraConsole.cs:987/:1023），
 *   两边都是**非负 Long**，原版不可能回绕；本移植把毫秒换成纳秒后相加，极端 TIMES 值会回绕；
 * - `setTimer()` 才 `_genericTimerStopwatch.Restart()`（EmueraConsole.cs:996-1003），
 *   计时随"让出边界"成对起停；本移植的成对点是 `publishFrame` / `holdFrame`。
 */
class StabilityL2InputLifecycleTest {

    /**
     * 反证：把 `QueueInputSink` 的截止点换回 `timeSource() + limitMs * 1_000_000L`
     * （或删掉饱和分支），本用例的等待方会在微秒内返回 `isTimeout = true`，
     * 下面的"仍在等待"断言当场红。
     */
    @Test
    fun `TIMES 取 Long 极大值时不得因纳秒换算回绕而立刻超时`() {
        // 假时钟钉死 0：回绕只由 limitMs 的换算决定，不依赖真机时钟。
        val sink = QueueInputSink(timeSource = { 0L })
        val outcome = AtomicReference<InputOutcome?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val waiter = thread(name = "stability-l2-huge-times") {
            try {
                outcome.set(
                    sink.waitInput(
                        InputRequest(
                            type = InputRequest.InputType.INT,
                            // (2^63 - 1) * 1_000_000 按 2^64 回绕成 -1_000_000：旧写法截止点在"过去"。
                            timeLimit = Long.MAX_VALUE,
                        ),
                    ),
                )
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && waiter.isAlive && System.nanoTime() < gate) Thread.sleep(5)
        assertTrue(waiter.isAlive, "巨大 TIMES 直接返回了：截止点被纳秒换算回绕成过去时刻（立刻超时）")
        assertTrue(sink.isAwaiting, "等待方未进入等待态")
        assertTrue(sink.submitInt(1L), "提交应入队")
        waiter.join(5_000)
        assertFalse(waiter.isAlive, "提交后等待方仍未返回（疑似挂死）")
        assertNull(failure.get(), "等待方抛错")
        val result = outcome.get()
        assertNotNull(result, "等待方没交出结果")
        assertFalse(result.isTimeout, "极大 TIMES 只应由提交推进；isTimeout = true 说明走了回绕后的立即超时")
        assertEquals(1L, result.intValue, "交付值应来自那条提交")
        assertEquals(0, sink.pendingCount, "提交应被消费")
    }

    /**
     * 反证：把 `waitSystemInteger` 的 `finally` 去掉（恢复成"取值后直接 holdFrame"），
     * 在关闭队列上调用本入口会留下解冻态，随后一行可见变更即 bump revision，断言红。
     */
    @Test
    fun `系统等待被关闭打断后帧闸必须重新冻结`() {
        val console = LineBufferConsoleSink(clientWidth = 40)
        val sink = QueueInputSink()
        val host = ConsoleBeginHost(sink, console)
        sink.close()
        assertFailsWith<InputSinkClosedException> { host.awaitOpeningInput() }

        val before = console.revision
        console.print("关闭后迟到的一行")
        console.newLine()
        assertEquals(
            before,
            console.revision,
            "帧闸停在解冻态：可见变更被逐条发布（正常路径的成对 holdFrame 没覆盖异常出口）",
        )
    }
}
