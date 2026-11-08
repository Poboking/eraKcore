package com.eratw.port.render.adapter

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图形段（`<img>` / `<shape>`）的**折行宽 == 渲染实占**同尺等价（2026-11-05 遗留①收口钉）。
 *
 * 折行（core，[CellWidth.visibleHtmlCells]）与绘制（本层 `HtmlRunFlatten.graphicSize`）
 * 必须从**同一个** script px 出发经 [CellWidth.graphicCells] 量化 —— 两边各算各的，
 * 图形行就会攒到超宽才折、渲染右沿裁切（4.981 指令网格回归的同族病）。
 * 本钉逐形对拍：`flatten` 产物的格推进（文本段 `Grid.cells`、图形段 `widthCells`）
 * 必须逐值等于折行口径。
 *
 * 夹具两档：恒等档（字号 41 = 脚本字号 18 的「字号 41」档，scriptFontPixelSize=18）覆盖
 * 设备字号 ≠ 脚本字号的真实形态；等字号档（41/18 同值）覆盖 scriptPixels 恒等支。
 */
class HtmlGraphicFoldParityTest {

    /** 显式宽度形态样本（CHARALIST 立绘行 / 自定义菜单缩进 / 进度条块）。 */
    private val samples = listOf(
        "<img src='a' width='216px' height='144px'>设置角色",
        "<img src='a' width='100'><button value='1'>日记本</button>",
        "<shape type='space' param='100'>[  0] 你进行自定义角色",
        "<shape type='space' param='216px'>下药",
        "<shape type='rect' param='216px' color='red'>bar",
        "<shape type='space' param='-180px'>文字",
    )

    private fun htmlRun(html: String) =
        TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML)

    /** 渲染侧的格推进：图形段 = [InlineGraphic.widthCells]（负 space = 回退），文本段 = [Grid.cells]。 */
    private fun drawnCells(runs: List<TextRun>): Int = runs.sumOf { run ->
        val g = run.graphic
        if (g != null) g.widthCells else Grid.cells(run.text)
    }

    private fun assertParity(html: String, fontPx: Int, scriptFontPx: Int) {
        val runs = HtmlRunFlatten(CellMetrics(fontPx, fontPx * 19 / 18), scriptFontPixelSize = scriptFontPx)
            .flatten(htmlRun(html))
        assertEquals(
            CellWidth.visibleHtmlCells(html, scriptFontPx.toLong()),
            drawnCells(runs),
            "折行宽必须等于渲染实占（fontPx=$fontPx scriptFontPx=$scriptFontPx）：«$html»",
        )
    }

    @Test
    fun `恒等档_字号等于脚本字号_逐形等价`() {
        for (html in samples) assertParity(html, fontPx = 18, scriptFontPx = 18)
    }

    @Test
    fun `真机档_字号41脚本字号18_逐形等价`() {
        for (html in samples) assertParity(html, fontPx = 41, scriptFontPx = 18)
    }
}
