package com.eratw.port.core.host

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEEL1/L2：[SingleDaemonSlot] 的**进程级死亡留痕环**（[SingleDaemonSlot.captureDeath] 写、
 * [SingleDaemonSlot.drainCapturedFailures] 读清），钉「直接接线」那一层的语义。
 *
 * 结的病：[SingleDaemonSlot.lastFailure] 是实例读数，而 [SingleDaemonSlot.start] 会把上一代
 * 清零——两代之间没有消费者（槽实例归宿主私有，`DevToolsHost.kt:564`，采集侧够不着）时那条死
 * 就被起代动作抹掉。环在**死那一刻**由 per-thread handler 落账，活过代次更替；消费口
 * [SingleDaemonSlot.drainCapturedFailures] 取一次即清，app 侧 `ErrorReportCollector` 每份报告
 * drain 一次。本类把这几条判据各自钉死：
 * - 非预期死亡当场入环（类名/消息/栈顶/代次都是死那一刻的读数）；
 * - 正常打断路径**不**入环（body 吞 `InterruptedException` 干净返回，handler 不跑）；
 * - 满 [SingleDaemonSlot.FAILURE_RING] 丢最旧（容量写死，§0 第 10 条）；
 * - 同槽两代先后死：环里两条、各带自己的代次（实例读数被 start 清零不殃及环）；
 * - [SingleDaemonSlot.clearCapturedFailures] 收口。
 *
 * 环是进程级单例状态：每条用例起手 [SingleDaemonSlot.clearCapturedFailures] 清场，拿确定起点。
 * 「线程不再存活 ⇒ handler 已跑完」是 [SingleDaemonSlot.drainLastFailure] 既有判据（见
 * `SingleDaemonSlotDrainTest`）沿用的同一口径：JVM 在标记账号线程死亡之前调用
 * uncaughtExceptionHandler，所以 `!isRunning` 一观察到，留痕必然已入环。
 */
class SingleDaemonSlotCaptureTest {

    @BeforeTest
    fun clearResidue() {
        SingleDaemonSlot.clearCapturedFailures()
    }

    /**
     * 与生产 `DevToolsHost.loop` / 基准 `SingleDaemonSlotTest.idleLoop` 同款形状：睡到被打断为止，
     * **吞掉** `InterruptedException` 干净返回——只有这种「健康线程」才允许断言正常打断路径一个字
     * 不进环；若 body 让中断异常逃逸，handler 会把它当非预期死亡入环，断言就假阴了。
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
    fun `非预期死亡当场入环_drain取一次即清`() {
        val slot = SingleDaemonSlot<Any>("eratw-capture-once")
        try {
            assertTrue(slot.start({ Any() }, { throw RuntimeException("轮询崩了") }))
            waitUntil(2_000, "daemon 被 body 异常带死") { !slot.isRunning }
            val first = SingleDaemonSlot.drainCapturedFailures()
            assertEquals(1, first.size, "死一条入环一条")
            val row = first.single()
            assertEquals("eratw-capture-once", row.threadName)
            assertEquals(1, row.generation, "代次先落账再起线程：第一代死必须记第 1 代而不是第 0 代")
            assertEquals("java.lang.RuntimeException", row.errorClass)
            assertEquals("轮询崩了", row.message)
            assertTrue(row.topFrame.isNotBlank(), "栈顶帧文本必须当场压好（环不攥 Throwable）")
            assertEquals(
                emptyList(),
                SingleDaemonSlot.drainCapturedFailures(),
                "取一次即清：同一条死 drain 第二次必须拿不到（防逐份重播）",
            )
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `正常打断路径不入环`() {
        val slot = SingleDaemonSlot<Any>("eratw-capture-clean")
        try {
            assertTrue(
                slot.start({ Any() }, { idleLoop() }),
            )
            assertTrue(slot.stop(1_000), "正常收口")
            assertEquals(
                emptyList(),
                SingleDaemonSlot.drainCapturedFailures(),
                "stop 的打断走 body 干净返回，不是非预期死亡，一个字都不该进环",
            )
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `满环丢最旧_容量硬顶`() {
        val cap = SingleDaemonSlot.FAILURE_RING
        // 逐条起、逐条等死透再起下一条：入环顺序确定，旧在前。
        (1..cap + 1).forEach { i ->
            val slot = SingleDaemonSlot<Any>("eratw-cap-$i")
            assertTrue(slot.start({ Any() }, { throw IllegalStateException("抖线 $i") }))
            waitUntil(2_000, "eratw-cap-$i 带死") { !slot.isRunning }
        }
        val drained = SingleDaemonSlot.drainCapturedFailures()
        assertEquals(cap, drained.size, "投 ${cap + 1} 条只留最新 $cap 条——容量是硬顶不是估算")
        assertEquals(
            (2..cap + 1).map { "eratw-cap-$it" },
            drained.map { it.threadName },
            "最旧的 eratw-cap-1 被挤出，留下的必须是最新 cap 条且旧在前",
        )
    }

    @Test
    fun `同槽两代先后死_环里两条各带代次`() {
        val slot = SingleDaemonSlot<Any>("eratw-capture-two-gen")
        try {
            assertTrue(slot.start({ Any() }, { throw RuntimeException("第一代死") }))
            waitUntil(2_000, "第一代带死") { !slot.isRunning }
            // 不 drain，直接起第二代：start 清零的是实例读数 lastFailure（W8 刀五旧口径），
            // 本波要结的正是「这一清就把第一代死抹掉」——环必须把两代都留住。
            assertTrue(slot.start({ Any() }, { throw RuntimeException("第二代死") }))
            waitUntil(2_000, "第二代带死") { !slot.isRunning }
            val rows = SingleDaemonSlot.drainCapturedFailures()
            assertEquals(2, rows.size, "起代清零不再吞死痕：两代各一条")
            assertEquals(listOf(1, 2), rows.map { it.generation }, "旧在前，且各带死那一刻的代次")
            assertEquals(listOf("第一代死", "第二代死"), rows.map { it.message })
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `clearCapturedFailures_清空环后drain为空`() {
        val slot = SingleDaemonSlot<Any>("eratw-capture-clear")
        try {
            assertTrue(slot.start({ Any() }, { throw RuntimeException("留一条") }))
            waitUntil(2_000, "daemon 带死") { !slot.isRunning }
            SingleDaemonSlot.clearCapturedFailures()
            assertEquals(emptyList(), SingleDaemonSlot.drainCapturedFailures(), "clear 是 drain 之外的显式收口口")
        } finally {
            slot.stop()
        }
    }
}
