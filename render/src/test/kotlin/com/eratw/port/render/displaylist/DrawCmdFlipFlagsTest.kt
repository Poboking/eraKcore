package com.eratw.port.render.displaylist

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2 定向单测：镜像标记必须**一路走到 [DrawCmd]**（绘制侧从 `cmd.graphic` 读）。
 *
 * 数据链：`HtmlRunFlatten.emitImage`（填 `flipX/flipY`）→ [TextRun.graphic] →
 * `DisplayList.submit/prepare`（原样搬运同一个 `InlineGraphic` 实例，不复制、不重算）→
 * [DrawCmd.graphic]。本组用例从 HTML 原文出发，钉住「展平结果」与「显示列表里的命令」
 * 两处都能读到同一组标记 —— 中间任何一处丢了标记，绘制侧就会把镜像图画成正向图。
 */
class DrawCmdFlipFlagsTest {

    private val metrics = CellMetrics(18)

    private fun runsOf(html: String): List<TextRun> =
        HtmlRunFlatten(metrics).flatten(
            TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML),
        )

    private fun cmdOf(html: String): DrawCmd {
        val runs = runsOf(html)
        var cells = 0
        for (run in runs) cells += if (run.graphic != null) run.graphic!!.widthCells else Grid.cells(run.text)
        val list = DisplayList(clientWidth = 1800)
        list.submit(listOf(DisplayRow(runs, cells, isLogicalLine = true, isLineEnd = true)))
        return list.render().single { it.graphic != null }
    }

    /** 负宽展平后，命令上的图仍带 `flipX`。 */
    @Test
    fun `负宽命令可见flipX`() {
        val cmd = cmdOf("<img src='a' width='-5px' height='10px'>")
        val image = cmd.graphic as? InlineGraphic.Image
        assertNotNull(image)
        assertTrue(image.flipX)
        assertFalse(image.flipY)
        assertEquals(5, image.widthPx)
    }

    /** 负高展平后，命令上的图仍带 `flipY`。 */
    @Test
    fun `负高命令可见flipY`() {
        val cmd = cmdOf("<img src='a' height='-10px'>")
        val image = cmd.graphic as? InlineGraphic.Image
        assertNotNull(image)
        assertTrue(image.flipY)
        assertEquals(10, image.heightPx)
    }

    /** 正常图的命令上两个标记都是 false（不给普通图挂镜像）。 */
    @Test
    fun `正常图命令上两个标记都是false`() {
        val cmd = cmdOf("<img src='a' width='20px' height='20px'>")
        val image = cmd.graphic as? InlineGraphic.Image
        assertNotNull(image)
        assertFalse(image.flipX)
        assertFalse(image.flipY)
    }
}
