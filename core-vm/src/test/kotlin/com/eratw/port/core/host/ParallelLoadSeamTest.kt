package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CMP-18 的可测判据（不依赖真机）：`程序.meta` 并行任务的**闸序**与**收口**。
 *
 * `GameHost.loadAndRun` 的现症有两条，本用例各自钉一条：
 * 1. 顺序：堆预检闸拒绝时**一次都不许**调用起线程的动作（旧写法 607 行起线程早于 614 行判闸）；
 * 2. 收口：装载中途退出时，被遗弃的 `eratw-meta` 线程必须 interrupt + join 掉
 *    （旧写法 `close()` 只 interrupt VM 线程，从不碰 meta）。
 *
 * 真机项（弱机失败屏、重建时的并发解析、/proc/pid/fd）不在本用例内。
 */
class ParallelLoadSeamTest {

    private fun alive(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == name }

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

    @Test
    fun `闸拒绝之后一次都不许起 meta 线程`() {
        val name = "eratw-meta"
        var starts = 0
        val gate = BeginAfterPreflight {
            starts++
            ParallelTask(name) { "meta" }.start()
        }
        // 宿主：预检不足 + 非 force_load + 非 debuggable ⇒ 先 reject，再 return（失败屏）
        gate.reject()
        assertTrue(gate.isRejected)
        assertNull(gate.begin(), "闸拒绝后 begin() 必须是 null")
        assertEquals(0, starts, "闸拒绝后 create 一次都不许被调")
        assertEquals(0, alive(name), "闸拒绝后不许留下 $name 线程")
        assertFalse(gate.isBegun)
    }

    @Test
    fun `闸放行只起一条线程，begin 幂等，await 拿到结果`() {
        val name = "eratw-meta-ok"
        var starts = 0
        val gate = BeginAfterPreflight {
            starts++
            ParallelTask(name) {
                Thread.sleep(20)
                "meta-ok"
            }.start()
        }
        val first = gate.begin()
        assertNotNull(first, "闸放行后必须起任务")
        assertEquals(1, starts)
        assertTrue(gate.isBegun)

        // 重复 begin 不许再起一条（宿主里 assembleSession 只会 await 一次，但 close() 也读同一句柄）
        assertSame(first, gate.begin(), "重复 begin 必须返回同一任务")
        assertEquals(1, starts, "重复 begin 不许再起线程")
        assertEquals(1, alive(name), "全进程只允许一条 $name")

        assertEquals("meta-ok", first.await(), "await 取到 body 的结果")
        assertTrue(first.isDone)
    }

    @Test
    fun `cancel 打断并 join 掉正在解析的 meta 线程`() {
        val name = "eratw-meta-cancel"
        val task = ParallelTask(name) {
            Thread.sleep(60_000) // 模拟 49 MB 侧车的长解析
            0
        }.start()
        waitUntil(1_000, "meta 线程起来") { alive(name) == 1 }

        val t0 = System.nanoTime()
        assertTrue(task.cancel(1_000), "cancel() 的返回值就是「1 s 内已 join 掉」")
        val ms = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(ms <= 1_200, "cancel() 实测 ${ms}ms，判据上限 1 s（留 200ms 调度余量）")
        assertFalse(task.isAlive, "cancel() 之后线程必须已经死了")
        assertEquals(0, alive(name), "cancel() 之后线程表里不许再有 $name")
    }

    @Test
    fun `cancel 幂等且不挂死，body 异常由 await 原样抛出`() {
        val name = "eratw-meta-idem"
        val task = ParallelTask(name) { "done" }.start()
        assertEquals("done", task.await())
        assertTrue(task.cancel(1_000), "已结束的任务上 cancel 无害且返回 true")
        assertTrue(task.cancel(1_000), "cancel 幂等")

        val boom = ParallelTask<String>("eratw-meta-boom") { throw IllegalStateException("侧车格式错") }.start()
        val e = assertFailsWith<IllegalStateException> { boom.await() }
        assertEquals("侧车格式错", e.message, "装载层报错口径必须原样冒到宿主（不换一句话）")
        assertTrue(boom.cancel(1_000))
    }
}
