package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DIV-WIRE-2 帧级集成：**真机那条路**（`ConsoleFrame.draw`）上 div 子行按钮可点、tooltip 可取。
 *
 * 这条钉的是「接线」而不是「算法」：`ButtonLayer` 的 div 命中表靠
 * `ConsoleScreen.updateDivOverlay(overlay, metrics, canvasHeightPx)` 每帧喂同一份
 * [com.eratw.port.render.adapter.DivOverlayFrame]（`ConsoleFrame.draw` 里紧挨
 * `divOverlayFrame(screen)` 之后那一行）。少了这行，B 路所有单测照样绿、真机却点不到 ——
 * 所以这里从 sink 打印开始、走到 `frame.draw`、再用**像素**去问命中。
 *
 * 口径：命中几何照 `ConsoleDivPart.TestChildHitbox`（`ConsoleDivPart.cs:134-172`）——
 * 盒矩形宽用脚本 `width`（不是绘制侧 `width+2`）、子行带 `rect.Y + 子行号 × 行高`、
 * 子行 x 带含 `xInset`。
 */
class DivFrameHitTest {

    private val metrics = CellMetrics()
    private val clientWidth = 40
    private val heightLines = 20

    /** 一张 div 卡：底 `#363636`、`padding='4px'`、子行里一颗 `value='7'` 的按钮（带 title）。 */
    private val pageHtml =
        "<div xpos='0px' ypos='0px' width='120px' height='40px' padding='4px' color='#363636'>" +
            "<button value='7' title='棋子卡'>DIVBTN</button></div>"

    private fun buildScreen(): Pair<ConsoleScreen, ScreenComposer> {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        sink.printHtml(pageHtml, false)
        val screen = ConsoleScreen(clientWidth, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        return screen to composer
    }

    /**
     * `frame.draw` 之后，命中表必须已经按**这一帧的几何**建好：
     * 像素 → 子行按钮值；盒外 null；tooltip 取到的是按钮 label。
     */
    @Test
    fun `ConsoleFrame 每帧喂命中表后 div 子行按钮可点`() {
        val (screen, composer) = buildScreen()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.list.dirtyStrips())

        // 子行首字形：内容盒左沿（= borderRect.x + padding）+ 半个格，取字形中心像素。
        val rows = screen.visibleRows()
        assertEquals(1, rows.size, "div 页只有一条锚定行（子行不是显示行）")
        val rowH = metrics.lineHeight
        val rowTop = composer.rowTopPx(0, rows.size)
        // padding=4：内容盒左沿 = 盒 x(0) + 4；子行 y = 盒顶 + 4
        val xPx = 4 + metrics.cellsToPixels(0) + 2
        val yPx = rowTop + 4 + rowH / 2

        val hit = screen.divHitAt(xPx, yPx)
        assertNotNull(hit, "帧级路径上 div 子行按钮必须可点（$xPx,$yPx）")
        assertEquals(7L, hit.value, "点到的应是 value='7' 那颗")
        assertEquals(0, hit.blockIndex, "Plan.all 全局下标：只有一颗块")
        assertEquals(0, hit.childRow)
        assertEquals("棋子卡", screen.divTooltipAt(xPx, yPx), "tooltip 取按钮 title")
    }

    /** 盒子外（画布右下方）必须不命中，且不会误报成正文行。 */
    @Test
    fun `盒外像素不命中 div`() {
        val (screen, composer) = buildScreen()
        ConsoleFrame(composer).draw(screen, screen.list.dirtyStrips())
        assertNull(screen.divHitAt(composer.canvasWidthPx - 1, composer.canvasHeightPx - 1))
    }

    /** 没画过帧（命中表未喂）时一律不命中 —— 证明命中表确实来自 `ConsoleFrame` 的每帧喂参。 */
    @Test
    fun `未喂帧时无 div 命中`() {
        val (screen, composer) = buildScreen()
        assertNull(screen.divHitAt(4, 4), "没有 frame.draw 就没有命中表")
        // 画一帧后同一个像素就有命中 —— 差异只可能来自接线。
        ConsoleFrame(composer).draw(screen, screen.list.dirtyStrips())
        assertNotNull(screen.divHitAt(6, composer.rowTopPx(0, 1) + 4 + metrics.lineHeight / 2))
    }
}
