package com.eratw.port.core.diag

import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.proc.QueueInputSink
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LockWatch] 的行为面单测（CONTRACT-FEEL1-3LANE §2 L3）。
 *
 * 断言的四件事，逐条对着契约的硬约束：
 * 1. **缺省关 = 零行为**：未挂载时 [LockWatch.handleOrNull] 给 null（挂载点据此整块跳过，
 *    口径同 `ProcessRuntime.statementProbe` 的守卫，`core/proc/ProcessRuntime.kt:1126-1129`）；
 * 2. **按「挂载点 + 线程名」聚合**：最长持有 / 累计等待 / 样本数三读数分别正确；
 * 3. **有界**：写满 [LockWatch.THREAD_SLOTS] 条线程名后进合并桶，样本总量一条不丢；
 *    越界 id 进 [LockWatch.totalSamples] 的兜底计数，绝不抛；
 * 4. **读侧禁抛 + 读写并发不丢账**（提交 `49490501` 的 CME 教训：写侧挂 monitor、
 *    读侧必须走同一把锁的快照）。
 *
 * 时标一律由测试直接给数（[LockWatch.noteSegment] 只算差、不读墙钟），所以本文件
 * 不需要假计时源也不受宿主时钟影响。
 */
class LockWatchTest {

    /** 每条用例独立的挂载-摘除闭环：进用例前复位、出用例后回到缺省关。 */
    private fun mounted(body: () -> Unit) {
        val saved = LockWatch.enabled
        LockWatch.reset()
        LockWatch.enabled = true
        try {
            body()
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }

    @Test
    fun `缺省关时探针口给 null 挂载点整块跳过`() {
        val saved = LockWatch.enabled
        try {
            LockWatch.enabled = false
            assertNull(LockWatch.handleOrNull(), "关 = 探针口 null（不取时钟、不进 LockWatch）")
            LockWatch.enabled = true
            assertNotNull(LockWatch.handleOrNull(), "开 = 同一个进程级采样器实例")
            assertSame(LockWatch, LockWatch.handleOrNull(), "必须是单例本体，不许每次新建")
        } finally {
            LockWatch.enabled = saved
        }
    }

    private fun assertSame(expected: Any?, actual: Any?, message: String) {
        if (expected !== actual) throw AssertionError("$message：期望同一实例，实际 $actual")
    }

    @Test
    fun `一条样本拆成排队等待与持有两段`() {
        mounted {
            // enter 0 → acquired 100 → exit 300：排队 100 ns、持有 200 ns。
            LockWatch.noteSegment(LockWatch.ID_GFX_PIXELS_DECODE, 0L, 100L, 300L, tag = 42L)
            val s = LockWatch.snapshot().single { it.lockId == LockWatch.ID_GFX_PIXELS_DECODE }
            assertEquals(1L, s.samples, "一次获锁 = 一条样本")
            assertEquals(200L, s.maxHoldNs, "持有 = exit - acquired")
            assertEquals(200L, s.totalHoldNs)
            assertEquals(100L, s.maxWaitNs, "等待 = acquired - enter（排队拿锁那一段）")
            assertEquals(100L, s.totalWaitNs)
            assertEquals(42L, s.maxHoldTag, "tag 记最长那一笔的身份")
            assertEquals(42L, s.maxWaitTag)
            assertEquals("gfx.pixels.decode", LockWatch.nameOf(LockWatch.ID_GFX_PIXELS_DECODE))
        }
    }

    @Test
    fun `同线程多样本聚合最值与累计分别正确`() {
        mounted {
            LockWatch.noteSegment(LockWatch.ID_SINK_SUBMIT, 0L, 10L, 60L)      // wait 10 hold 50
            LockWatch.noteSegment(LockWatch.ID_SINK_SUBMIT, 0L, 5L, 905L)      // wait 5  hold 900
            LockWatch.noteSegment(LockWatch.ID_SINK_SUBMIT, 0L, 7L, 20L)       // wait 7  hold 13
            val s = LockWatch.snapshot().single { it.lockId == LockWatch.ID_SINK_SUBMIT }
            assertEquals(3L, s.samples)
            assertEquals(900L, s.maxHoldNs, "最长一次持有条不被累计稀释")
            assertEquals(963L, s.totalHoldNs)
            assertEquals(10L, s.maxWaitNs)
            assertEquals(22L, s.totalWaitNs)
        }
    }

    @Test
    fun `时钟异常给出的负差值折成零但不丢样本`() {
        mounted {
            LockWatch.noteSegment(LockWatch.ID_ERBC_MATERIALIZE, 500L, 100L, 10L)
            val s = LockWatch.snapshot().single { it.lockId == LockWatch.ID_ERBC_MATERIALIZE }
            assertEquals(1L, s.samples, "时标传错也要留下「发生过一次」这条账")
            assertEquals(0L, s.maxHoldNs, "负差折 0，不许溢出成大数")
            assertEquals(0L, s.maxWaitNs)
            assertEquals(0L, s.totalHoldNs)
            assertEquals(0L, s.totalWaitNs)
        }
    }

    @Test
    fun `纯等待样本只加等待不加持有`() {
        mounted {
            LockWatch.noteWait(LockWatch.ID_SINK_ANYKEY_AWAIT, 1_000L, 4_000L)
            val s = LockWatch.snapshot().single { it.lockId == LockWatch.ID_SINK_ANYKEY_AWAIT }
            assertEquals(1L, s.samples)
            assertEquals(3_000L, s.maxWaitNs)
            assertEquals(3_000L, s.totalWaitNs)
            assertEquals(0L, s.totalHoldNs, "等待点没有持有时长")
            assertEquals(0L, s.maxHoldNs)
        }
    }

    @Test
    fun `不同线程名分槽聚合`() {
        val workerDone = java.util.concurrent.CountDownLatch(1)
        LockWatch.reset()
        LockWatch.enabled = true
        try {
            LockWatch.noteSegment(LockWatch.ID_SPRITE_POOL_EVICT, 0L, 1_000L, 4_000L)
            val t = thread(name = "lw-worker") {
                try {
                    LockWatch.noteSegment(LockWatch.ID_SPRITE_POOL_EVICT, 0L, 1L, 8L)
                } finally {
                    workerDone.countDown()
                }
            }
            assertTrue(workerDone.await(5, java.util.concurrent.TimeUnit.SECONDS), "工作线程必须收得回来")
            t.join(5_000L)
            val rows = LockWatch.snapshot().filter { it.lockId == LockWatch.ID_SPRITE_POOL_EVICT }
            assertEquals(2, rows.size, "两条线程名 = 两行读数（锁标识相同也不合并）")
            assertEquals(3_000L, rows.maxOf { it.maxHoldNs })
            assertEquals(2L, rows.sumOf { it.samples })
            assertTrue(rows.any { it.threadName == "lw-worker" }, "线程名进读数键")
            assertTrue(rows.none { it.mergedSamples > 0 }, "两条具名线程不该落进合并桶")
        } finally {
            LockWatch.enabled = false
            LockWatch.reset()
        }
    }

    @Test
    fun `线程名写满后进合并桶 总量一条不丢`() {
        val n = LockWatch.THREAD_SLOTS + 3
        val names = (0 until n).map { "lw-thread-$it" }
        LockWatch.reset()
        LockWatch.enabled = true
        try {
            val done = java.util.concurrent.CountDownLatch(n)
            val threads = names.map { name ->
                thread(name = name) {
                    try {
                        LockWatch.noteSegment(LockWatch.ID_GFX_PIXELS_DECODE, 0L, 1L, 5L)
                        LockWatch.noteSegment(LockWatch.ID_GFX_PIXELS_DECODE, 0L, 1L, 50L)
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "线程没跑完")
            threads.forEach { it.join(5_000L) }
            val rows = LockWatch.snapshot().filter { it.lockId == LockWatch.ID_GFX_PIXELS_DECODE }
            assertTrue(
                rows.size <= LockWatch.THREAD_SLOTS,
                "读数行数必须封顶在 ${LockWatch.THREAD_SLOTS} 槽（有界性硬约束，§0 第 10 条），实际 ${rows.size}",
            )
            assertEquals(2L * n, rows.sumOf { it.samples }, "并进合并桶也不许丢样本")
            assertTrue(rows.any { it.mergedSamples > 0 }, "超出的线程名进合并桶并留降级标注")
            assertTrue(LockWatch.readout().contains("并入本桶样本"), "合并桶行要写明粒度降级（计数口径 = 并进本桶的样本数）")
        } finally {
            LockWatch.enabled = false
            LockWatch.reset()
        }
    }

    @Test
    fun `越界 id 不抛只进兜底计数`() {
        mounted {
            LockWatch.noteSegment(LockWatch.LOCK_COUNT + 7, 0L, 1L, 5L)
            LockWatch.noteWait(-1, 0L, 5L)
            assertFalse(LockWatch.hasSamples(LockWatch.LOCK_COUNT + 7), "越界 id 不进任何槽")
            assertEquals(0, LockWatch.snapshot().filter { it.lockId == -1 }.size)
            assertEquals(2L, LockWatch.totalSamples(), "越界样本进 outOfRange 兜底账，不许静默蒸发")
            assertTrue(LockWatch.readout().contains("越界 id 样本=2"))
        }
    }

    @Test
    fun `读写并发不丢账 读侧禁抛`() {
        val writers = 6
        val perWriter = 400
        LockWatch.reset()
        LockWatch.enabled = true
        val readerStop = java.util.concurrent.atomic.AtomicBoolean(true)
        val readerErrors = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val reader = thread(name = "lw-reader") {
                while (readerStop.get()) {
                    try {
                        LockWatch.readout()
                        LockWatch.snapshot().size
                    } catch (_: Throwable) {
                        readerErrors.incrementAndGet()
                    }
                }
            }
            val done = java.util.concurrent.CountDownLatch(writers)
            val ts = (0 until writers).map { w ->
                thread(name = "lw-writer-$w") {
                    try {
                        repeat(perWriter) { LockWatch.noteSegment(LockWatch.ID_SINK_SUBMIT, 0L, 1L, 2L) }
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "写侧超时")
            readerStop.set(false)
            reader.join(10_000L)
            ts.forEach { it.join(5_000L) }
            assertEquals(0, readerErrors.get(), "读侧禁抛（LoadTrace 的 CME 事故不许复活）")
            assertEquals(
                writers.toLong() * perWriter, LockWatch.totalSamples(),
                "写侧全挂同一把锁：一条样本都不许丢",
            )
            val s = LockWatch.snapshot().filter { it.lockId == LockWatch.ID_SINK_SUBMIT }
                .sumOf { it.totalHoldNs }
            assertEquals(writers.toLong() * perWriter, s, "每条 hold=1ns，累计即样本数")
        } finally {
            LockWatch.enabled = false
            LockWatch.reset()
        }
    }

    @Test
    fun `空表读数走降级行不抛`() {
        val saved = LockWatch.enabled
        LockWatch.reset()
        LockWatch.enabled = false
        try {
            val text = LockWatch.readout()
            assertTrue(text.startsWith("[lockwatch] 挂载=off"), "缺省关时表头写明未挂载：$text")
            assertTrue(text.contains("无样本"))
            assertTrue(LockWatch.snapshot().isEmpty())
        } finally {
            LockWatch.enabled = saved
        }
    }

    /**
     * 挂载后不改被观察方的语义：探针挂上，[QueueInputSink] 的入队/拒收行为与不挂一致。
     * （「摘掉即红」由 [LockWatchMountPinTest] 的源码面钉子负责，本条钉反方向。）
     */
    @Test
    fun `探针挂载下输入队列行为不变`() {
        mounted {
            val sink = QueueInputSink()
            assertTrue(sink.submit("7"), "入队照常成功（类 KDoc 第 1 条：允许宿主抢跑提交）")
            assertFalse(sink.isAwaiting, "没有等待方时探针不许把 isAwaiting 翻真")
            sink.close()
            assertFalse(sink.submit("8"), "关闭后拒收的返回值不变（这条走的正是 finally 里的探针）")
            assertEquals(0, sink.awaiting?.let { 1 } ?: 0, "awaiting 快照不受探针影响")
            assertTrue(LockWatch.totalSamples() > 0L, "submit 的探针确实在记")
            assertTrue(LockWatch.hasSamples(LockWatch.ID_SINK_SUBMIT))
            assertFalse(LockWatch.hasSamples(LockWatch.ID_SINK_ANYKEY_AWAIT), "没等过的挂载点恒零")
            val row = LockWatch.snapshot().filter { it.lockId == LockWatch.ID_SINK_SUBMIT }
                .sumOf { it.samples }
            assertEquals(2L, row, "成功入队与关闭拒收两条路都记（记录点在 finally）")
            assertEquals(1L, LockWatch.snapshot().first { it.lockId == LockWatch.ID_SINK_SUBMIT }.maxHoldTag,
                "tag = 提交文本长度（\"7\" 是一条）")
        }
    }

    /**
     * 头号病灶面（`pixels` 解码段全程持锁）的挂载反方向钉：**挂上探针不许改变解码本身**。
     *
     * 同一装置（`GraphicsPool.get` + `createDeferred` 的延迟源）跑两遍：关时与开时
     * 像素逐字相同、延迟源**同样只被调用一次**（`同一代只解一次` 的口径见
     * `core/host/GraphicsBuffer.kt:245`）；开时多出来的只有读数行。
     * 另钉「早退不记样本」：解完之后 `pendingPixels` 已空，第二次读 `pixels` 走无锁快路径，
     * 采样数仍为 1 —— 没在锁里干活的路径不许稀释读数。
     */
    @Test
    fun `探针挂载下位图解码逐字不变`() {
        val saved = LockWatch.enabled
        LockWatch.reset()
        try {
            val calls = IntArray(2) // [0] = 关时的延迟源调用数，[1] = 开时的
            LockWatch.enabled = false
            val offBuf = GraphicsPool().get(901)
            assertTrue(offBuf.createDeferred(2, 2) { calls[0]++; IntArray(4) { 0x1122 } })
            val offRead = offBuf.pixels.toList()
            assertEquals(1, calls[0], "挂载前的基准：同一代只解一次")
            assertEquals(0L, LockWatch.totalSamples(), "缺省关时挂载点一条都不记（零开销守卫）")

            LockWatch.enabled = true
            val onBuf = GraphicsPool().get(902)
            assertTrue(onBuf.createDeferred(2, 2) { calls[1]++; IntArray(4) { 0x1122 } })
            val onPixels = onBuf.pixels
            assertEquals(offRead, onPixels.toList(), "挂上探针后像素逐字不变（观察面不得反噬解码）")
            assertEquals(1, calls[1], "同一代仍只解一次：探针没让延迟源多跑一遍")
            onBuf.pixels
            val row = LockWatch.snapshot().single { it.lockId == LockWatch.ID_GFX_PIXELS_DECODE }
            assertEquals(1L, row.samples, "解码后走无锁快路径 ⇒ 早退不记样本")
            assertEquals(4L, row.maxHoldTag, "tag = 本代的像素数（2x2）")
            assertTrue(LockWatch.readout().contains("gfx.pixels.decode"))
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }
}
