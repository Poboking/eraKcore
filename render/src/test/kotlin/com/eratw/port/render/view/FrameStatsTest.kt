package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 帧统计的开关与格式（W8-RENDERPERF）。
 *
 * 期望值是格式字面量本身（`render.frame ms=… draw=… rows=…`），不是从实现回读：
 * 真机日志的读法必须固定成这一行，后续验收脚本才不会随实现漂移。
 * 时钟注入 ⇒ `ms` 的取整口径可钉死，不依赖真实耗时。
 */
class FrameStatsTest {

    @Test
    fun `关闭时一次时钟都不取也不输出`() {
        var clockCalls = 0
        val out = ArrayList<String>()
        val stats = FrameStats(enabled = false, sink = { out.add(it) }, clock = { clockCalls++; 0L })
        stats.beginFrame()
        assertNull(stats.endFrame(draw = 3, rows = 2))
        assertEquals(0, clockCalls)
        assertTrue(out.isEmpty())
        assertEquals(0L, stats.frames)
        assertEquals(-1L, stats.lastMs)
        assertEquals(0, stats.lastDraw)
        assertEquals(0, stats.lastRows)
        assertEquals("", stats.lastLine)
    }

    @Test
    fun `毫秒向下取整`() {
        var now = 0L
        val stats = FrameStats(enabled = true, clock = { now })
        stats.beginFrame()
        now = 999_999L
        stats.endFrame(0, 0)
        assertEquals(0L, stats.lastMs, "不足 1ms 记 0")
        stats.beginFrame()
        now = 1_999_999L
        stats.endFrame(0, 0)
        assertEquals(1L, stats.lastMs, "1.999ms 向下取整到 1")
        stats.beginFrame()
        now = 3_000_000L
        stats.endFrame(0, 0)
        assertEquals(1L, stats.lastMs)
        assertEquals(3L, stats.frames)
    }

    @Test
    fun `行格式只有一处`() {
        assertEquals("render.frame ms=3 draw=57 rows=54", FrameStats.format(3, 57, 54))
        assertEquals("render.frame ms=0 draw=0 rows=0", FrameStats.format(0, 0, 0))
    }

    @Test
    fun `输出走回调且每帧一行`() {
        val out = ArrayList<String>()
        var now = 0L
        val stats = FrameStats(enabled = true, sink = { out.add(it) }, clock = { now })
        stats.beginFrame()
        now = 5_000_000L
        assertEquals("render.frame ms=5 draw=7 rows=3", stats.endFrame(draw = 7, rows = 3))
        assertEquals(listOf("render.frame ms=5 draw=7 rows=3"), out)
        assertEquals("render.frame ms=5 draw=7 rows=3", stats.lastLine)
    }

    /** 没有配对 `beginFrame` 的帧按 0 毫秒记（不拿上一次的起点算假长帧）。 */
    @Test
    fun `未开始就收尾按零毫秒记`() {
        val stats = FrameStats(enabled = true, clock = { 5_000_000_000L })
        assertEquals("render.frame ms=0 draw=1 rows=1", stats.endFrame(1, 1))
    }

    /** [FrameStats.discardFrame]：尺寸变化那一帧不算数，下一次 begin 重新计时。 */
    @Test
    fun `丢弃未收尾的帧`() {
        var now = 0L
        val stats = FrameStats(enabled = true, clock = { now })
        now = 1_000_000L
        stats.beginFrame()
        stats.discardFrame()
        now = 100_000_000L
        assertEquals(0L, stats.endFrame(0, 0)?.let { stats.lastMs }, "丢弃之后不许把 99ms 记成一帧")
    }

    @Test
    fun `系统属性开关`() {
        val key = FrameStats.STATS_PROPERTY
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "true")
            assertTrue(FrameStats.fromProperty().enabled)
            System.setProperty(key, "false")
            assertTrue(!FrameStats.fromProperty().enabled)
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }
}
