package com.eratw.port.render.view

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PRINT_IMG` 注入尺寸源后的**单出口**读数（`ConsoleImagePart.cs:60-65`）。
 *
 * 语句路与 HTML 路是同一张图表的两个入口：命中支的纵横比推宽（`:66-71`）、miss 支的
 * AltText 量宽（`:104-115`）都必须与 `adapter/HtmlRunFlatten` 逐值同源，否则「同一张立绘
 * 用语句打 / 用 HTML 打」占格不同。本文件钉三件事：
 *
 * 1. miss（查表未命中 / 基图高 ≤ 0）⇒ `widthCells` = AltText 的显示格、
 *    `altText` 字段与 AltText 同串、**显式 width/height/ypos 全部不生效**；
 *    miss 的高度回落到构造初值 `bottom = Config.FontSize`（`:37`，因为 `int num` 与
 *    `top` 都在 `:61-65` 的 `return` 之后才计算）；
 * 2. 命中且缺宽 ⇒ `DestBaseSize.Width * 高 / DestBaseSize.Height`（`:69` 整数除法）；
 * 3. 未注入（[GraphicsSinkBridge] 的缺省）⇒ 判不了 miss，保持旧读数
 *    （[GraphicsSinkBridgeImgDefaultTest] 已经钉住了那一面，这里只做反向对照）。
 */
class GraphicsSinkBridgeSizeMissTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 20, requestedLineHeight = 24)

    /** 宿主已接线、表里没有这张图。 */
    private val missing = HtmlRunFlatten.ImageSizeSource { null }

    private fun sizes(w: Int, h: Int): HtmlRunFlatten.ImageSizeSource =
        HtmlRunFlatten.ImageSizeSource { (w shl 16) or h }

    private fun image(
        source: HtmlRunFlatten.ImageSizeSource?,
        name: String,
        params: List<Pair<Long, Boolean>>,
    ): InlineGraphic.Image {
        val layer = GraphicsLayer()
        GraphicsSinkBridge(layer, metrics, sizeSource = source).printImage(name, null, null, params)
        return layer.drainInline().single() as InlineGraphic.Image
    }

    @Test
    fun `miss 行按 AltText 计格且高度回落一行`() {
        // param[0] = 宽、param[1] = 高、param[2] = ypos（FunctionIdentifier.cs:427-439）
        val img = image(missing, "pic", listOf(0L to false, 200L to false, 50L to false))
        val alt = "<img src='pic' height='40' ypos='10'>"
        assertEquals(alt, img.altText, "altText 与绘制侧回落用同一串（函数级重建，ConsoleImagePart.cs:44-59）")
        assertEquals(CellWidth.displayCells(alt), img.widthCells, "miss 宽 = AltText 显示格（:104-115）")
        assertEquals(
            CellWidth.displayCells(alt) * metrics.fontPixelSize / 2,
            img.widthPx,
            "本层没有字体度量口：像素宽 = 格 × 半角步进（格数本身精确）",
        )
        assertEquals(
            metrics.fontPixelSize,
            img.heightPx,
            "miss 的高度停在构造初值 bottom = Config.FontSize（:37）—— height='200' 不生效",
        )
        assertEquals(0, img.yOffsetPx, "top 也在 return 之后（:81）⇒ ypos 不生效")
        assertFalse(img.flipX || img.flipY, "翻转归一（:83-92）同样在 return 之后")
    }

    @Test
    fun `miss 时显式宽不生效`() {
        val img = image(missing, "pic", listOf(70L to true, 200L to false))
        // 重建次序是 height → width → ypos（`ConsoleImagePart.cs:52-57`），与脚本里谁先写无关。
        val alt = "<img src='pic' height='40' width='70px'>"
        assertEquals(alt, img.altText)
        assertEquals(CellWidth.displayCells(alt), img.widthCells, "原版 return 在 :72-75 之前 ⇒ 显式宽一个格也不计")
        assertTrue(img.widthPx != 70, "宽度不是显式给的 70px")
    }

    @Test
    fun `基图高为零按 miss 处理`() {
        val img = image(sizes(100, 0), "pic", listOf(70L to true, 200L to false))
        assertEquals(CellWidth.displayCells("<img src='pic' height='40' width='70px'>"), img.widthCells)
    }

    @Test
    fun `命中且缺宽时按基图纵横比推宽`() {
        val img = image(sizes(100, 50), "pic", listOf(0L to false, 200L to false))
        assertEquals(80, img.widthPx, "100 * 40 / 50（ConsoleImagePart.cs:69）")
        assertEquals(40, img.heightPx)
        assertEquals(
            InlineGraphic.cellsFor(80, metrics.cellPixelWidth),
            img.widthCells,
            "命中支格数仍由物理宽折算（与 HTML 路同式）",
        )
        assertEquals(0, img.yOffsetPx)
    }

    @Test
    fun `未注入尺寸源时保持旧读数`() {
        val img = image(null, "pic", listOf(0L to false, 200L to false))
        assertEquals(40, img.widthPx, "见方占位（登记偏差，反向对照见 GraphicsSinkBridgeImgDefaultTest）")
        assertEquals(40, img.heightPx)
    }
}
