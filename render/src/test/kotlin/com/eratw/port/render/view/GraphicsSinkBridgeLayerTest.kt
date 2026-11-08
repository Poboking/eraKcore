package com.eratw.port.render.view

import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WIRE-3 / WIRE-4 的渲染侧接线钉（C-IMAGE-LAYER@1 / C-TEXT-BG@1 消费 / C-RENDER-SINK-STATE@1）。
 *
 * 覆盖：[GraphicsSinkBridge] 的两条新 override 把字段写进 [GraphicsLayer]（同 depth 覆盖、
 * `rgb < 0` 清除、alpha 百分比域夹取、revision 推进、reset 清空），以及缺省实现让既有宿主
 * （[NoopGraphicsSink]）零改动空转。
 */
class GraphicsSinkBridgeLayerTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 20, requestedLineHeight = 24)

    // ---------------------------------------------------------------
    // SETIMAGELAYERL → GraphicsLayer.imageLayers
    // ---------------------------------------------------------------

    @Test
    fun `SETIMAGELAYERL 转发九字段进图层快照并推进 revision`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        val rev0 = layer.revision.get()
        sink.setImageLayer(
            spriteName = "flan_frame_1",
            depth = 1L,
            lineNo = 7,
            xpos = -3,
            ypos = -8,
            width = 100,
            height = 200,
            opacity = 255,
            followScroll = true,
        )
        val snap = layer.imageLayers.single()
        assertEquals("flan_frame_1", snap.spriteName)
        assertEquals(1L, snap.depth)
        assertEquals(7, snap.lineNo)
        assertEquals(-3, snap.xpos)
        assertEquals(-8, snap.ypos)
        assertEquals(100, snap.width)
        assertEquals(200, snap.height)
        assertEquals(255, snap.opacity)
        assertTrue(snap.followScroll)
        assertTrue(layer.revision.get() > rev0, "写入推进 revision")
    }

    @Test
    fun `SETIMAGELAYERL 同 depth 覆盖且保留插入位置`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setImageLayer("a", 1L, 1, 0, 0, 0, 0, 255, true)
        sink.setImageLayer("b", 2L, 2, 0, 0, 0, 0, 255, true)
        sink.setImageLayer("a2", 1L, 3, 5, 6, 7, 8, 128, true)
        val layers = layer.imageLayers
        assertEquals(2, layers.size, "同 depth 覆盖不新增槽")
        assertEquals(listOf("a2", "b"), layers.map { it.spriteName }, "depth 1 覆盖且保留原位（LinkedHashMap 口径）")
        assertEquals(128, layers[0].opacity)
        assertEquals(7, layers[0].width)
        assertEquals(3, layers[0].lineNo)
    }

    // ---------------------------------------------------------------
    // setTextBgColor → GraphicsLayer.textBackground
    // ---------------------------------------------------------------

    @Test
    fun `setTextBgColor 非负写快照 rgb 与百分比`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setTextBgColor(0x112233L, 50L)
        val bg = layer.textBackground!!
        assertEquals(0x112233, bg.rgb)
        assertEquals(50, bg.opacityPercent)
    }

    @Test
    fun `setTextBgColor 负色哨兵清空衬底`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setTextBgColor(0x112233L, 50L)
        assertTrue(layer.textBackground != null)
        // TEXT_BGC_OFF 的 (-1, 0) 哨兵
        sink.setTextBgColor(-1L, 0L)
        assertNull(layer.textBackground, "rgb < 0 = 清除而非取色")
    }

    @Test
    fun `setTextBgColor 百分比越界夹到 0 到 100`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setTextBgColor(0x112233L, 150L)
        assertEquals(100, layer.textBackground!!.opacityPercent)
        sink.setTextBgColor(0x112233L, -5L)
        assertEquals(0, layer.textBackground!!.opacityPercent)
    }

    // ---------------------------------------------------------------
    // 通知与清空
    // ---------------------------------------------------------------

    @Test
    fun `两条语句各通知一次重绘`() {
        var invalidations = 0
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics, onInvalidate = { invalidations++ })
        sink.setImageLayer("a", 1L, 0, 0, 0, 0, 0, 255, true)
        sink.setTextBgColor(1L, 50L)
        sink.setTextBgColor(-1L, 0L)
        assertEquals(3, invalidations)
    }

    @Test
    fun `reset 清空图层与文字衬底`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setImageLayer("a", 1L, 0, 0, 0, 0, 0, 255, true)
        sink.setTextBgColor(1L, 50L)
        layer.reset()
        assertTrue(layer.imageLayers.isEmpty())
        assertNull(layer.textBackground)
    }

    @Test
    fun `缺省实现让既有宿主零改动空转`() {
        // 既有假体与 Noop 不 override 新方法也能编译并空转（接口缺省实现的存在性断言）。
        NoopGraphicsSink.setImageLayer("a", 1L, 0, 0, 0, 0, 0, 255, true)
        NoopGraphicsSink.setTextBgColor(1L, 50L)
        assertTrue(true)
    }
}