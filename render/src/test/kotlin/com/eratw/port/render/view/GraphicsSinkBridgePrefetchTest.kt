package com.eratw.port.render.view

import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `onImage` 预取回调的接线面（立绘/背景首帧卡顿的对策）。
 *
 * 语义只在 [GraphicsSinkBridge] 一处：`printImage` 的 `name` 与 `setBgImage` 的 `name` 入队后各回调
 * 一次，按钮图（`nameb`）与取色遮罩图（`namem`）不预取；色块/空白段与背景移除**不**回调；
 * 回调为 null（缺省）时行为与接入前逐值相同 —— 那正是桌面预览与既有用例的构造形态。
 *
 * 这里只钉「回调了什么」，不钉解码：真解码在 `SpriteBitmapSource.prefetchAsync`
 * （Android 桩环境跑不了，见交付报告）。
 */
class GraphicsSinkBridgePrefetchTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 20, requestedLineHeight = 24)

    @Test
    fun `PRINT_IMG 入队后只把真图名交给预取`() {
        val seen = ArrayList<String>()
        val sink = GraphicsSinkBridge(GraphicsLayer(), metrics, onImage = { seen.add(it) })
        sink.printImage(
            name = "bodyGraph_X",
            nameb = "btn",
            namem = "mask",
            params = listOf(50L to true),
        )
        assertEquals(listOf("bodyGraph_X"), seen, "按钮图 nameb 与取色图 namem 都不是主显示路径，不预取")
    }

    @Test
    fun `SETBGIMAGE 入队后把背景图名交给预取`() {
        val seen = ArrayList<String>()
        val sink = GraphicsSinkBridge(GraphicsLayer(), metrics, onImage = { seen.add(it) })
        sink.setBgImage("bg01", 0L, 200L)
        assertEquals(listOf("bg01"), seen)
        sink.removeBgImage("bg01")
        sink.clearBgImages()
        assertEquals(listOf("bg01"), seen, "移除/清空背景没有新图要解，不预取")
    }

    @Test
    fun `PRECT 与 PSPACE 不触发预取`() {
        val seen = ArrayList<String>()
        val sink = GraphicsSinkBridge(GraphicsLayer(), metrics, onImage = { seen.add(it) })
        sink.printRect(listOf(30L to true), 0x112233)
        sink.printSpace(listOf(10L to true))
        assertTrue(seen.isEmpty(), "色块/空白段没有资源名，不该进预取队列")
    }

    @Test
    fun `onImage 缺省时入队与背景形态逐值不变`() {
        val l = GraphicsLayer()
        // 缺省构造 = 桌面预览与既有用例的形态：没有预取回调，也不许因此少画/多画任何一段。
        val sink = GraphicsSinkBridge(l, metrics)
        sink.printImage("a", null, null, emptyList())
        val img = l.drainInline().single() as InlineGraphic.Image
        assertEquals("a", img.resource)
        sink.setBgImage("bg", 0L, null)
        assertEquals(listOf("bg"), l.background.map { it.resource })
    }
}
