package com.eratw.port.render.perf

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.Grid
import com.eratw.port.render.TextRun
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.displaylist.DisplayList
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.view.ConsoleFrame
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenComposer
import com.eratw.port.render.view.ScreenRows
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 每帧分配的回归闸（W8-RENDERPERF）。
 *
 * ## 量的是什么
 *
 * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`（Temurin 17 支持；
 * 不支持时整类跳过，不算绿也不算红）。它数的是**当前测试线程**的 TLAB 分配字节，
 * 因此不受别的测试线程干扰；只跟「每帧有没有临时容器」有关，不随机器快慢变化。
 *
 * ## 阈值怎么定
 *
 * 先跑 200 帧预热再量 200 帧（JIT/逃生门都稳定之后），阈值取改造后读数的 20 倍以上——
 * 目的是拦住「谁又在热路径上 new 了一个 List」，不是卡住 JIT 的噪声。
 * 改造前（同一台机、同一轮）实测：`DisplayList.submit` 1,712 B/帧、`ButtonLayer.attach`
 * 1,376 B/帧、`ConsoleScreen.refresh` 4,136 B/帧、`ScreenComposer.compose` 7,824,568 B/帧。
 * 改造后前三项为 0 / 0 / 608 B/帧，画布走 `ConsoleFrame` 复用（0 B/帧）。
 *
 * 读数回退即红：这几条用例断言的是「稳态帧不再分配临时容器」这个结构性事实，
 * 改回每帧 new 列表/数组必然超阈值。
 */
class RenderFrameAllocTest {

    private val bean: com.sun.management.ThreadMXBean? = run {
        val anyBean = ManagementFactory.getThreadMXBean()
        val sun = if (anyBean is com.sun.management.ThreadMXBean) anyBean else null
        if (sun != null && sun.isThreadAllocatedMemorySupported) sun else null
    }

    private fun assumeAllocationSupported() {
        Assumptions.assumeTrue(bean != null, "本 JVM 不支持逐线程分配计数（com.sun.management），跳过分配闸")
    }

    /** 预热 [frames] 帧后，量接下来的 [frames] 帧，返回每帧平均分配字节。 */
    private fun allocatedPerFrame(frames: Int = WARMUP_FRAMES, block: () -> Unit): Long {
        val threadBean = bean ?: return 0L
        val id = Thread.currentThread().id
        repeat(frames) { block() }
        val before = threadBean.getThreadAllocatedBytes(id)
        repeat(frames) { block() }
        return (threadBean.getThreadAllocatedBytes(id) - before) / frames
    }

    private fun row(index: Int): DisplayRow {
        val text = "行$index-" + "x".repeat(20)
        return DisplayRow(
            runs = listOf(TextRun(text, 0xC0C0C0, 0, 0, "黑体")),
            cells = Grid.cells(text),
            isLogicalLine = true,
            isLineEnd = true,
        )
    }

    @Test
    fun `稳态帧的显示列表提交不分配容器`() {
        assumeAllocationSupported()
        val snapshot = List(WINDOW_ROWS) { row(it) }
        val list = DisplayList(clientWidth = 200)
        list.submit(snapshot)
        val perFrame = allocatedPerFrame { list.submit(snapshot) }
        assertTrue(perFrame <= NO_ALLOC_BYTES, "submit 稳态帧分配 $perFrame B/帧，超过闸值 $NO_ALLOC_BYTES")
    }

    @Test
    fun `稳态帧的命中表重建不分配`() {
        assumeAllocationSupported()
        val rows = List(WINDOW_ROWS) { row(it) }
        val layer = ButtonLayer()
        layer.attach(rows)
        val perFrame = allocatedPerFrame { layer.attach(rows) }
        assertTrue(perFrame <= NO_ALLOC_BYTES, "attach 稳态帧分配 $perFrame B/帧，超过闸值 $NO_ALLOC_BYTES")
    }

    @Test
    fun `脏行渲染不再每次分配输出列表`() {
        assumeAllocationSupported()
        val list = DisplayList(clientWidth = 200)
        list.submit(List(WINDOW_ROWS) { row(it) })
        val rects = list.submit(List(WINDOW_ROWS) { row(it) }.toMutableList().also { it[WINDOW_ROWS - 1] = row(999) })
        assertTrue(rects.isNotEmpty())
        val out = ArrayList<DrawCmd>(256)
        val perFrame = allocatedPerFrame { list.renderInto(rects, out) }
        assertTrue(perFrame <= NO_ALLOC_BYTES, "renderInto 分配 $perFrame B/帧，超过闸值 $NO_ALLOC_BYTES")
    }

    @Test
    fun `全量渲染的renderInto稳态帧零分配`() {
        assumeAllocationSupported()
        val list = DisplayList(clientWidth = 200)
        list.submit(List(WINDOW_ROWS) { row(it) })
        val out = ArrayList<DrawCmd>(256)
        list.renderInto(out)
        assertEquals(list.render(), out.toList(), "全量 renderInto 与 render 逐项相同")
        val perFrame = allocatedPerFrame { list.renderInto(out) }
        assertTrue(perFrame <= NO_ALLOC_BYTES, "全量 renderInto 分配 $perFrame B/帧，超过闸值 $NO_ALLOC_BYTES")
    }

    @Test
    fun `按行重绘的稳态帧零分配且复用画布`() {
        assumeAllocationSupported()
        val composer = ScreenComposer(clientWidthCells = 200, heightLines = WINDOW_ROWS)
        val screen = ConsoleScreen(clientWidth = 200, maxLog = 5000, heightLines = WINDOW_ROWS)
        val frame = ConsoleFrame(composer)
        val rects = screen.submit(List(WINDOW_ROWS) { ScreenRows.textLine("行$it-" + "x".repeat(20)) })
        frame.draw(screen, rects)
        assertTrue(frame.lastWasFull)
        val canvas = frame.canvas
        val perFrame = allocatedPerFrame {
            val none = screen.refresh()
            frame.draw(screen, none)
        }
        assertTrue(perFrame <= FRAME_BYTES, "稳态帧（提交 + 按行重绘）分配 $perFrame B/帧，超过闸值 $FRAME_BYTES")
        assertTrue(canvas === frame.canvas, "画布必须整帧复用，不许每帧新建 Raster")
        assertTrue(frame.lastRowsRepainted == 0, "稳态帧不该重绘任何行")
    }

    @Test
    fun `追加一行的帧复用画布且分配有上限`() {
        assumeAllocationSupported()
        val composer = ScreenComposer(clientWidthCells = 200, heightLines = WINDOW_ROWS)
        val screen = ConsoleScreen(clientWidth = 200, maxLog = 5000, heightLines = WINDOW_ROWS)
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.submit(List(WINDOW_ROWS) { ScreenRows.textLine("行$it-" + "x".repeat(20)) }))
        val canvas = frame.canvas
        var counter = 0
        val perFrame = allocatedPerFrame {
            counter++
            frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("新$counter"))))
        }
        assertTrue(canvas === frame.canvas, "追加行也不许新建画布")
        // 这一帧的分配基本只剩「折一条新行 + 那个新行自己的命令表」，与整屏重绘无关。
        assertTrue(perFrame <= APPEND_BYTES, "追加一行分配 $perFrame B/帧，超过闸值 $APPEND_BYTES")
    }

    private companion object {
        /** 基准的绘制窗口行数（`heightLines + DRAW_EXTRA_ABOVE`，54 + 1）。 */
        const val WINDOW_ROWS = 55

        /** 预热帧数（同时也是默认的测量帧数）。 */
        const val WARMUP_FRAMES = 200

        /** 稳态帧的分配闸：结构性零分配，留 96 B 给 JIT 噪声。 */
        const val NO_ALLOC_BYTES = 96L

        /** 稳态整帧（提交 + 按行重绘）的分配闸：改造后 608 B/帧，改造前 4,136 B/帧。 */
        const val FRAME_BYTES = 2_048L

        /**
         * 追加一行整帧的闸：改造后约 22 KB/帧（折行 + 新行的命令表 + 整屏下标前移导致的
         * 其余 54 行重新展开字形），改造前约 33 KB/帧。这里只堵住容器浪费的回归，
         * 「滚动时命令表按行实例复用」是已登记的后继优化（见交付报告）。
         */
        const val APPEND_BYTES = 48_000L
    }
}
