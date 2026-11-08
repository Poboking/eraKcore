package com.eratw.port.render.view

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * div 子行里的 `<img>` 图元 **miss**：尺寸源接进 `ScreenComposer` 后按 AltText 当文本画
 * （`ConsoleImagePart.cs:60-65` + `:104-115`）。
 *
 * 判据与可观测量：
 * - 未接尺寸源（[ScreenComposer] 的缺省）⇒ 展平成 IMAGE 段、绘制侧查不到 sprite 就
 *   静默跳过（`ConsoleImagePart.cs:168` 的 `IsCreated` 守卫；headless 对图形段本来不铺墨）
 *   ⇒ **整段无墨**；
 * - 接上尺寸源且查表未命中 ⇒ 展平成 TEXT 段、内容是重建的 AltText，行走普通字形路径
 *   ⇒ 行条上出现前景色字形块。
 *
 * 这条差别正是「同一标签在两条链读数一致」的可视化面：core 的折行/回读把 miss 的 img
 * 记成 AltText 的格数，渲染侧也必须真的把它当文本铺出来，而不是留一段空白。
 */
class ScreenComposerDivImgMissTest {

    private val metrics = CellMetrics(18, 18)

    private val background = ScreenScene.DEFAULT_BACKGROUND
    private val fore = 0xFFC0C0C0.toInt()

    /** 宿主已接线、表里没有这张图。 */
    private val missing = HtmlRunFlatten.ImageSizeSource { null }

    private fun composer(source: HtmlRunFlatten.ImageSizeSource?): ScreenComposer = ScreenComposer(
        clientWidthCells = 100,
        heightLines = 20,
        metrics = metrics,
        canvasHeightPx = 20 * 18,
        sizeSource = source,
    )

    /** 一颗无盒模型内缩的覆盖块（几何出处见 `view/DivChildGraphicTest` 的同名工具）。 */
    private fun block(composer: ScreenComposer, child: String): DivOverlayPlanner.Block {
        val raw = DisplayDivBlock(
            altHeadTag = "<div>",
            anchorCol = 0,
            anchorPointXPx = 0,
            xOffsetPx = 0,
            divXOffsetPx = 0,
            pointYPx = 0,
            yOffsetPx = 0,
            widthPx = 400,
            heightPx = 60,
            subDivisionWidthPx = 400,
            depth = 0,
            backgroundColor = null,
            margin = emptyList(),
            padding = emptyList(),
            border = emptyList(),
            radius = emptyList(),
            borderColors = emptyList(),
            isRelative = true,
            isEscaped = true,
            children = listOf(DisplayDivChildLine(child)),
        )
        return composer.divPlanner.plan(
            blocks = listOf(raw),
            anchorTopPx = 10,
            anchorPointXPx = 0,
            canvasHeightPx = composer.canvasHeightPx,
            visibleTopLine = 0,
            visibleBottomLine = 100,
            anchorLineNo = 0,
        ).all.single()
    }

    private fun compose(source: HtmlRunFlatten.ImageSizeSource?, child: String): Raster {
        val composer = composer(source)
        val block = block(composer, child)
        return composer.compose(
            commands = emptyList(),
            rowCount = 20,
            scene = ScreenScene(),
            sprites = SpriteTable(),
            overlay = DivOverlayFrame(emptyList(), listOf(block)),
            focusedDiv = null,
            pressedDiv = null,
        )
    }

    @Test
    fun `接入尺寸源后 miss 的 img 按 AltText 铺字形`() {
        val child = "<img src='NO_SUCH' width='20px' height='20px'>"
        val canvas = compose(missing, child)
        // 字形块从行条顶 + 0 起，第 0 列落在 x=0：`<` 的第一个字形像素（DivChildGraphicTest 同式）。
        assertEquals(fore, canvas.pixelAt(1, 13), "AltText 当文本画：屏上是重建出来的 `<img …>` 文案")
        assertTrue(
            (20 until 60).any { canvas.pixelAt(it, 13) == fore },
            "文案有 40+ 格，第 20 像素之后仍应有字形（不是被当成一张方图或一段空白）",
        )
    }

    @Test
    fun `未接尺寸源时同一标签仍是图形段且不铺墨`() {
        val canvas = compose(null, "<img src='NO_SUCH' width='20px' height='20px'>")
        assertEquals(background, canvas.pixelAt(1, 13), "IMAGE 段查不到 sprite ⇒ 静默跳过（:168）")
        assertTrue(
            (0 until 60).none { canvas.pixelAt(it, 13) == fore },
            "未接线保持旧读数：div 子行里一个字形都不出",
        )
    }
}
