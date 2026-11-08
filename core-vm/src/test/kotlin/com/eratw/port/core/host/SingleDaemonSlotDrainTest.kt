package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * FEEL1/L2：[SingleDaemonSlot.drainLastFailure] 的「取一次即清」判据（纯 JVM，不依赖真机）。
 *
 * 结的病是「后台 daemon 线程静默死、报告里一个字都没有」：[SingleDaemonSlot.lastFailure] 只是
 * **读数**（一直挂着到下一次 [SingleDaemonSlot.start] 才清），全仓此前无人取。本波要把它接进
 * 错误报告——而接进报告的前提是「同一条死只出声一次、不逐份重播」，靠的就是 drain 的取一次即清。
 * 这条用例钉的正是这个消费语义（读侧不抛、取到即复位）。
 */
class SingleDaemonSlotDrainTest {

    /**
     * 与生产 `DevToolsHost.loop` / 基准 `SingleDaemonSlotTest.idleLoop` 同款形状：睡到被打断为止，
     * **吞掉** `InterruptedException` 干净返回。契约 §对 body 的要求就是这条——正常 [SingleDaemonSlot.stop]
     * 的打断不得走 handler，否则「正常收口」会被误记成非预期死亡。
     */
    private fun idleLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        assertTrue(cond(), "$what：${timeoutMs}ms 内未成立")
    }

    @Test
    fun `drainLastFailure 返回当代死因并立刻复位`() {
        val slot = SingleDaemonSlot<Any>("eratw-drain-once")
        val boom = RuntimeException("loop 崩了")
        try {
            assertTrue(slot.start({ Any() }, { throw boom }))
            waitUntil(1_000, "daemon 线程被 body 异常带死") { !slot.isRunning }
            // 读数先就位（W8 刀五既有语义）
            assertSame(boom, slot.lastFailure, "非预期死亡必须记进 lastFailure")
            // 取一次：拿到同一条，并立刻清掉
            assertSame(boom, slot.drainLastFailure(), "drain 交出当前死因")
            assertNull(slot.lastFailure, "drain 之后读数必须复位，防止逐份重播")
            assertNull(slot.drainLastFailure(), "同一条死 drain 第二次必须是 null（取一次即清）")
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `没死过时 drainLastFailure 返回 null 且不影响起新一代`() {
        val slot = SingleDaemonSlot<Any>("eratw-drain-clean")
        try {
            assertTrue(slot.start({ Any() }, { idleLoop() }))
            assertNull(slot.drainLastFailure(), "健康线程没有死因可 drain")
            assertTrue(slot.stop(1_000), "正常打断路径收口")
            assertNull(slot.lastFailure, "stop 的打断不走 lastFailure（body 吞 InterruptedException 干净返回）")
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `起新一代时 start 仍会清零上一代死因`() {
        val slot = SingleDaemonSlot<Any>("eratw-drain-regen")
        val boom = IllegalStateException("上一代崩了")
        try {
            assertTrue(slot.start({ Any() }, { throw boom }))
            waitUntil(1_000, "第一代带死") { !slot.isRunning }
            assertSame(boom, slot.lastFailure)
            // 不 drain，直接起第二代：start 内部必须把 lastFailure 清零（不把上一代的死带进新一代）
            assertTrue(slot.start({ Any() }, { idleLoop() }))
            assertNull(slot.lastFailure, "start 清零上一代死因")
            assertNull(slot.drainLastFailure(), "清零后 drain 拿不到旧的死")
        } finally {
            slot.stop()
        }
    }
}
