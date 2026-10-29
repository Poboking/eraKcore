package com.eratw.port.render.view

import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UP1-WIRE-1：图形桥下沉后的共享语义钉。Android 壳与桌面壳都只是委托，算式只此一份。
 *
 * 每条断言对着原版出处（KDoc 详见 [GraphicsSinkBridge]）：`PRINT_IMG` 的
 * 宽/高/ypos 槽位、`PRINT_RECT` 的 1 参/4 参与 alpha、`PRINT_SPACE` 的 px/百分比、
 * 背景三语句的 depth/opacity，以及「成功入队一次 = 通知一次重绘」。
 */
class GraphicsSinkBridgeTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 20, requestedLineHeight = 24)

    private fun layer() = GraphicsLayer()

    private fun drain(layer: GraphicsLayer): List<InlineGraphic> = layer.drainInline()

    @Test
    fun `PRINT_IMG 按 宽 高 ypos 槽位折算并保留按钮与取色图`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics)
        sink.printImage(
            name = "pic",
            nameb = "pic_b",
            namem = "pic_m",
            params = listOf(50L to true, 100L to false, 25L to true),
        )
        val img = drain(l).single() as InlineGraphic.Image
        assertEquals(50, img.widthPx)
        // 100% of FontSize 20
        assertEquals(20, img.heightPx)
        assertEquals(25, img.yOffsetPx)
        assertEquals(InlineGraphic.cellsFor(50, metrics.cellPixelWidth), img.widthCells)
        assertEquals("pic_b", img.buttonResource)
        assertEquals("pic_m", img.mappingResource)
        assertEquals("<img src='pic' srcb='pic_b' srcm='pic_m' height='20' width='50px' ypos='25px'>", img.altText)
    }

    @Test
    fun `PRINT_IMG 空串按钮与取色图落 null 且缺高缺宽按一行高见方`() {
        val l = layer()
        GraphicsSinkBridge(l, metrics).printImage("pic", "", "", emptyList())
        val img = drain(l).single() as InlineGraphic.Image
        assertNull(img.buttonResource)
        assertNull(img.mappingResource)
        assertEquals(20, img.heightPx)
        assertEquals(20, img.widthPx)
        assertEquals(0, img.yOffsetPx)
    }

    /**
     * 脚本 `px` 与 HTML 侧同尺（立绘空档修复的**语句半边**）：基准 10、真机字号 20 ⇒ 系数 2。
     * 上面两条用例（缺省基准）就是「未接 = 逐值不变」的反向判据。
     */
    @Test
    fun `PRINT_IMG 与 PRECT 的 px 形态按基准字号折算`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics, scriptFontPixelSize = 10)
        sink.printImage("pic", "", "", listOf(50L to true, 100L to true, 25L to true))
        val img = drain(l).single() as InlineGraphic.Image
        assertEquals(100, img.widthPx, "50 * 20 / 10")
        assertEquals(200, img.heightPx, "100 * 20 / 10")
        assertEquals(50, img.yOffsetPx, "25 * 20 / 10")

        sink.printRect(listOf(30L to true), 0x112233)
        assertEquals(60, (drain(l).single() as InlineGraphic.Rect).widthPx, "PRECT 单参同尺")

        sink.printSpace(listOf(30L to true))
        val space = drain(l).single() as InlineGraphic.Space
        assertEquals(InlineGraphic.cellsFor(60, metrics.cellPixelWidth), space.widthCells, "PSPACE 同尺")

        // 百分比形态不受影响：它本来就跟着真机字号。
        sink.printRect(listOf(125L to false), 0x112233)
        assertEquals(25, (drain(l).single() as InlineGraphic.Rect).widthPx, "125% * 20 / 100 不折")
    }

    @Test
    fun `PRINT_RECT 单参恒补 alpha 且高度取字号 非正宽不入队`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics)
        sink.printRect(listOf(30L to true), 0x112233)
        val rect = drain(l).single() as InlineGraphic.Rect
        assertEquals(0xFF112233.toInt(), rect.colorArgb)
        assertEquals(20, rect.heightPx)
        assertEquals(30, rect.widthPx)

        sink.printRect(listOf(0L to true), 0x112233)
        sink.printRect(listOf(-5L to true), 0x112233)
        assertTrue(drain(l).isEmpty(), "num <= 0 原版不入段（ConsoleShapePart.cs:57）")
    }

    @Test
    fun `PRINT_RECT 四参走浮点折算并按 x+w 取整算宽`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics)
        // x=10% -> 2, y=50% -> 10, w=125% -> 25, h=10% -> 2
        sink.printRect(
            listOf(10L to false, 50L to false, 125L to false, 10L to false),
            0x00FF00,
        )
        val rect = drain(l).single() as InlineGraphic.Rect
        assertEquals(2, rect.xOffsetPx)
        assertEquals(10, rect.yOffsetPx)
        assertEquals(25, rect.widthPx)
        assertEquals(2, rect.heightPx)
        assertEquals(0xFF00FF00.toInt(), rect.colorArgb)

        // 负 x / 零宽高一律不成段（ConsoleShapePart.cs:64）
        sink.printRect(listOf((-1L) to true, 0L to true, 10L to true, 10L to true), 0xFF0000)
        sink.printRect(listOf(0L to true, 0L to true, 0L to true, 10L to true), 0xFF0000)
        assertTrue(drain(l).isEmpty())
    }

    @Test
    fun `PRINT_RECT 四参高度取整为 0 且原值不小于千分之一时补 1 像素`() {
        val l = layer()
        // h = 1% of 20 = 0.2f -> 取整 0，但 >= 0.001f ⇒ 补 1
        GraphicsSinkBridge(l, metrics).printRect(
            listOf(0L to true, 0L to true, 10L to true, 1L to false),
            0xFFFFFF,
        )
        val rect = drain(l).single() as InlineGraphic.Rect
        assertEquals(1, rect.heightPx)
    }

    @Test
    fun `PRINT_SPACE px 与百分比两种口径`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics)
        sink.printSpace(listOf(15L to true))
        sink.printSpace(listOf(50L to false))
        val spaces = drain(l).map { it as InlineGraphic.Space }
        assertEquals(InlineGraphic.cellsFor(15, metrics.cellPixelWidth), spaces[0].widthCells)
        assertEquals(InlineGraphic.cellsFor(10, metrics.cellPixelWidth), spaces[1].widthCells)

        sink.printSpace(emptyList())
        sink.printSpace(listOf(1L to true, 2L to true))
        assertTrue(drain(l).isEmpty(), "非 1 参形态原版不成段（ConsoleShapePart.cs:51）")
    }

    @Test
    fun `背景三语句按 depth 降序并夹 opacity`() {
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics)
        sink.setBgImage("low", 1L, 300L)
        sink.setBgImage("high", 5L, null)
        assertEquals(listOf("high", "low"), l.background.map { it.resource })
        assertEquals(255, l.background.first().opacity)
        assertEquals(255, l.background.last().opacity)

        sink.removeBgImage("high")
        assertEquals(listOf("low"), l.background.map { it.resource })
        sink.clearBgImages()
        assertTrue(l.background.isEmpty())
    }

    @Test
    fun `成功入队每条语句通知一次重绘 失败形态不通知`() {
        var invalidations = 0
        val l = layer()
        val sink = GraphicsSinkBridge(l, metrics) { invalidations++ }
        sink.printImage("a", null, null, emptyList())
        sink.printRect(listOf(1L to true))
        sink.printSpace(listOf(1L to true))
        sink.setBgImage("bg", 0L, 0L)
        sink.removeBgImage("bg")
        sink.clearBgImages()
        assertEquals(6, invalidations)

        sink.printRect(emptyList())
        sink.printSpace(emptyList())
        assertEquals(6, invalidations, "守卫早退不通知重绘")
    }
}
