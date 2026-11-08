package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * `HostEventRing` 的行为锁（R3）：序号单调、超容覆盖、快照是副本、并发发射不丢序号。
 *
 * 环是常开通道，任何一条「序号断裂/快照被并发改动」都会让离线因果链读错，故全部钉死。
 */
class HostEventRingTest {

    @Test
    fun `序号从 1 起单调递增且载荷保序`() {
        val ring = HostEventRing(8)
        val a = ring.emit(100L, "session.start", linkedMapOf("build" to "x", "pid" to "1"))
        val b = ring.emit(200L, "turn.end", mapOf("ms" to "5"))
        assertEquals(1L, a.seq)
        assertEquals(2L, b.seq)
        assertEquals(listOf("build" to "x", "pid" to "1"), a.payload.toList())
        assertEquals(2L, ring.emitted)
    }

    @Test
    fun `超容丢最旧并累计 dropped`() {
        val ring = HostEventRing(3)
        repeat(5) { ring.emit(it.toLong(), "t", mapOf("i" to it.toString())) }
        val snap = ring.snapshot()
        assertEquals(listOf(3L, 4L, 5L), snap.map { it.seq })
        assertEquals(2L, ring.dropped)
        assertEquals(5L, ring.emitted)
        assertEquals(listOf(4L, 5L), ring.tail(2).map { it.seq })
        assertEquals(emptyList<HostEvent>(), ring.tail(0))
        assertEquals(listOf(3L, 4L, 5L), ring.tail(99).map { it.seq })
    }

    @Test
    fun `快照是副本且发射后改源载荷不影响环内事件`() {
        val ring = HostEventRing(4)
        val src = linkedMapOf("k" to "v")
        ring.emit(1L, "t", src)
        src["k"] = "mutated"
        val snap = ring.snapshot()
        assertEquals("v", snap[0].payload["k"], "emit 必须复制载荷")
        val mutable = snap.toMutableList()
        mutable.clear()
        assertEquals(1, ring.snapshot().size, "调用方改快照不得影响环")
    }

    @Test
    fun `并发发射序号唯一且总数正确`() {
        val ring = HostEventRing(64)
        val threads = 4
        val per = 250
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = AtomicInteger(0)
        repeat(threads) { ti ->
            Thread {
                try {
                    start.await()
                    repeat(per) { ring.emit(ti.toLong(), "t", mapOf("i" to it.toString())) }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals(0, errors.get())
        assertEquals((threads * per).toLong(), ring.emitted)
        assertEquals(64, ring.snapshot().size)
        assertEquals((threads * per - 64).toLong(), ring.dropped)
        val seqs = ring.snapshot().map { it.seq }
        assertEquals(seqs.distinct(), seqs, "环内序号不得重复")
        assertEquals((threads * per).toLong(), seqs.last())
    }

    @Test
    fun `clear 计入 dropped 且序号不复位`() {
        val ring = HostEventRing(4)
        repeat(3) { ring.emit(it.toLong(), "t") }
        ring.clear()
        assertEquals(3L, ring.dropped)
        assertTrue(ring.snapshot().isEmpty())
        assertEquals(4L, ring.emit(9L, "t").seq)
    }

    @Test
    fun `容量必须为正`() {
        assertFailsWith<IllegalArgumentException> { HostEventRing(0) }
    }
}
