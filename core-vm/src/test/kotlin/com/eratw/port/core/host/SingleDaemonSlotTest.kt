package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CMP-17 的可测判据（不依赖真机）：[SingleDaemonSlot] 的代次语义。
 *
 * 钉住三件事，正是 `DevToolsHost` 现症的镜像：
 * 1. 进程级只有**一条**调试线程（未 stop 时重复 start 不叠线程、不换代）；
 * 2. [SingleDaemonSlot.stop] 之后 second generation 能起来，且 [SingleDaemonSlot.target]
 *    换成第二代的新对象（旧 Activity/GameHost 不再被静态钉住）；
 * 3. stop 后 1 s 内线程必须死、`isRunning` 归 false。
 *
 * 真机项（fd 数、Activity 实例数、smaps）不在本用例内，见交付报告的 TODO(verify)。
 */
class SingleDaemonSlotTest {

    private fun alive(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == name }

    /** 有界等待（断言用例自己的最大等待就是判据的一部分，不是被测代码里的 sleep）。 */
    private fun waitUntil(
        timeoutMs: Long,
        what: String,
        cond: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        assertTrue(cond(), "$what：${timeoutMs}ms 内未成立")
    }

    /** 与生产 `DevToolsHost.loop` 同款形状：睡到线程被打断为止。 */
    private fun idleLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    @Test
    fun `两代 start 后 target 是第二代，且全进程只有一条 eratw-devtools`() {
        val name = "eratw-devtools"
        val slot = SingleDaemonSlot<Any>(name)
        try {
            val first = Any()
            assertTrue(slot.start({ first }, { idleLoop() }), "第一代必须起来")
            waitUntil(1_000, "第一代线程进入线程表") { alive(name) == 1 }
            assertSame(first, slot.target, "第一代的 target")
            assertEquals(1, slot.generation)
            assertEquals(1, alive(name), "全进程只允许一条 $name")

            // 未 stop：第二次 start 必须被拒，既不叠线程也不换代（生产里 Activity 重建会再调一次）
            val second = Any()
            assertFalse(slot.start({ second }, { idleLoop() }), "未 stop 时的重复 start 必须被拒")
            assertSame(first, slot.target, "未 stop 时 target 不许被换掉")
            assertEquals(1, slot.generation, "未 stop 时不许换代")
            assertEquals(1, alive(name), "重复 start 不许叠出第二条线程")

            // stop 真复位：线程 1 s 内死、target 清空、isRunning 归 false
            assertTrue(slot.stop(1_000), "stop() 必须在 1 s 内把轮询线程收口")
            assertFalse(slot.isRunning, "stop() 之后 isRunning 必须是 false")
            assertNull(slot.target, "stop() 之后不许再钉住旧的 host/view（CMP-17 的泄漏面）")
            waitUntil(1_000, "旧线程退出") { alive(name) == 0 }

            // 第二代
            assertTrue(slot.start({ second }, { idleLoop() }), "stop() 之后必须允许起第二代")
            assertSame(second, slot.target, "target 必须是第二代的对象")
            assertEquals(2, slot.generation)
            assertEquals(1, alive(name), "换代不许叠线程：全进程仍只有一条 $name")
        } finally {
            slot.stop()
        }
        waitUntil(1_000, "用例收尾后无线程残留") { alive(name) == 0 }
    }

    @Test
    fun `stop 在 1s 内打断线程并让 running 归位`() {
        val name = "eratw-devtools-stop"
        val slot = SingleDaemonSlot<Any>(name)
        assertTrue(slot.start({ Any() }, { idleLoop() }))
        waitUntil(1_000, "线程起来") { alive(name) == 1 }
        assertTrue(slot.isRunning)

        val t0 = System.nanoTime()
        assertTrue(slot.stop(1_000), "stop() 的返回值就是「1 s 内已收口」")
        val ms = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(ms <= 1_200, "stop() 实测 ${ms}ms，判据上限 1 s（留 200ms 调度余量）")
        assertFalse(slot.isRunning, "stop() 之后 running=false")
        assertEquals(0, alive(name), "stop() 之后线程必须已经不在线程表里")
    }

    @Test
    fun `create 只在真的起线程时调用，线程自然退出后允许重新 start`() {
        val name = "eratw-devtools-generic"
        var created = 0
        val slot = SingleDaemonSlot<Any>(name)
        try {
            // 第一代：body 立刻返回 ⇒ 线程自然死掉
            assertTrue(slot.start({ created++; Any() }, {}))
            waitUntil(1_000, "第一代自然退出") { !slot.isRunning }
            assertEquals(1, created)

            // 线程死掉之后（没人调 stop）必须允许重新 start —— running 不许被永久钉在 true
            assertTrue(slot.start({ created++; Any() }, { idleLoop() }), "线程自然退出后必须能重新 start")
            waitUntil(1_000, "第二代起来") { alive(name) == 1 }
            assertEquals(2, created, "create 只在线程真的起来时调用")
            assertEquals(2, slot.generation)

            // 未 stop 的重复 start 不许再调 create（Activity 每次重建都会走 startOnce）
            assertFalse(slot.start({ created++; Any() }, { idleLoop() }))
            assertEquals(2, created, "被拒的 start 不许调 create")
        } finally {
            slot.stop()
        }
    }
}
