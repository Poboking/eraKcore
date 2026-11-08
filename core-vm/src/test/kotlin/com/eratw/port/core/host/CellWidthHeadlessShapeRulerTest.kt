package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * headless 下错误形态 shape 标签的折行预算与行内游标**只允许一把尺**（C1 第二段 · C2 残量）。
 *
 * 原版 `ConsoleShapePart.CreateShape`（ConsoleShapePart.cs:48-74）把落不到 space/rect
 * 合法形态的 shape 标签交给 `ConsoleErrorShapePart`（:71-74）：它把重建文案当文本画、
 * `SetWidth` 按文本度量（ConsoleErrorShapePart.cs:28-37、StringMeasure.cs:42-72）。
 * 移植侧因此两条链都该计文本格：
 * - 折行预算 [CellWidth.visibleHtmlCells] 的 headless 支；
 * - [LineBufferConsoleSink] 行内游标的错误形态分支（div 锚点列位，即命中坐标）。
 *
 * 收口前 headless（graphicFontPx <= 0）下 visibleHtmlCells 把整个标签宽度门控成 0，
 * 而行内游标无条件按文本计 ⇒ 同一个标签「折行说 0 格、锚点说 N 格」。本类钉同值，
 * 并钉合法图形在 headless 下两条链都记 0 格（既有读数，由 graphicFontPx > 0 门控）。
 * 形态判据只认一份：[CellWidth.shapeGraphicNum] / [CellWidth.isInvalidShapeTag]。
 *
 * 反向证明：撤掉 visibleHtmlCells 的 headless 错误形态支 ⇒ 下面「同尺」用例红；
 * 把行内游标判据退回 isShapeTag ⇒ 「合法图形 headless 同为零格」用例红。
 */
class CellWidthHeadlessShapeRulerTest {

    private fun card() =
        "<div xpos='0px' ypos='0px' width='100px' height='20px'>台词</div>"

    /** 行内游标对前缀的格投影（div 锚点列 = 前缀占格；div 块本身零宽）。 */
    private fun anchorCol(prefix: String, sink: LineBufferConsoleSink): Int {
        sink.printHtml("<nobr>" + prefix + card() + "</nobr>", false)
        return sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single().anchorCol
    }

    private fun headless() = LineBufferConsoleSink(clientWidth = 400)
    private fun wired() = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)

    // 错误形态：未知 type / 参数不合 / rect 校验不过（ConsoleShapePart.cs:48-74 落空）
    private val invalidTags = listOf(
        "<shape type='bad'>",
        "<shape type='space'>",
        "<shape type='space' param='1,2'>",
        "<shape type='rect' param='0,0,0,10'>",
        "<shape>",
    )
    // 合法形态：space 单参 / rect 单参 num>0 / rect 四参校验通过
    private val validTags = listOf(
        "<shape type='space' param='216px'>",
        "<shape type='rect' param='216px'>",
        "<shape type='rect' param='0px,30px,216px,64px'>",
        "<shape type='rect' param='0,0,10,10'>",
    )

    @Test
    fun `形态判据单一 错误形态落 null`() {
        for (tag in invalidTags) {
            assertNull(CellWidth.shapeGraphicNum(tag), "错误形态不折图形像素 [$tag]")
            assertTrue(CellWidth.isInvalidShapeTag(tag), "错误形态判据 [$tag]")
        }
    }

    @Test
    fun `形态判据单一 合法形态取 num`() {
        for (tag in validTags) {
            assertTrue(CellWidth.shapeGraphicNum(tag) != null, "合法形态 [$tag]")
            assertEquals(false, CellWidth.isInvalidShapeTag(tag), "合法形态判据 [$tag]")
        }
        assertEquals(216L to true, CellWidth.shapeGraphicNum("<shape type='space' param='216px'>"))
    }

    @Test
    fun `rect 四参形态判据照 C# 校验 x 与 w 与 h`() {
        // ConsoleShapePart.cs:61-68：x>=0 && w>0 && h>0 才落 ConsoleRectangleShapePart，
        // 其余落 ConsoleErrorShapePart（:71-74）。钉住这条边界，避免把合法四参误判成
        // 错误形态文本回落（夹具曾把 0,0,10,10 列进错误形态，反致折叠分支被误测）。
        assertTrue(CellWidth.shapeGraphicNum("<shape type='rect' param='0,0,10,10'>") != null)
        assertNull(CellWidth.shapeGraphicNum("<shape type='rect' param='-1,0,10,10'>"), "x<0 校验不过")
        assertNull(CellWidth.shapeGraphicNum("<shape type='rect' param='0,0,0,10'>"), "w=0 校验不过")
        assertNull(CellWidth.shapeGraphicNum("<shape type='rect' param='0,0,10,0'>"), "h=0 校验不过")
    }

    @Test
    fun `错误形态 headless 折行按文本回落且不为零格`() {
        for (tag in invalidTags) {
            val expected = CellWidth.displayCells(tag)
            assertTrue(expected > 0, "错误形态不是 0 格 [$tag]")
            assertEquals(expected, CellWidth.visibleHtmlCells(tag, 0L), "headless 折行计文本格 [$tag]")
        }
    }

    @Test
    fun `错误形态 headless 折行与行内游标同一把尺`() {
        for (tag in invalidTags) {
            val fold = CellWidth.visibleHtmlCells(tag, 0L)
            val cursor = anchorCol(tag, headless())
            assertEquals(fold, cursor, "折行预算与命中游标同值 [$tag]")
        }
    }

    @Test
    fun `错误形态 接线 折行与行内游标同一把尺`() {
        for (tag in invalidTags) {
            val fold = CellWidth.visibleHtmlCells(tag, 18L)
            val cursor = anchorCol(tag, wired())
            assertEquals(CellWidth.displayCells(tag), fold, "接线也按文本回落 [$tag]")
            assertEquals(fold, cursor, "折行预算与命中游标同值 [$tag]")
        }
    }

    @Test
    fun `合法图形 headless 折行与行内游标同为零格`() {
        for (tag in validTags) {
            assertEquals(0, CellWidth.visibleHtmlCells(tag, 0L), "headless 不折算合法图形 [$tag]")
            assertEquals(0, anchorCol(tag, headless()), "headless 游标也不折算合法图形 [$tag]")
        }
    }

    @Test
    fun `合法图形 接线 仍按图形格且读数逐值不变`() {
        val tag = "<shape type='space' param='216px'>"
        assertEquals(24, CellWidth.visibleHtmlCells(tag, 18L), "ceil(216 / (9 x 1.04)) = 24")
        assertEquals(24, anchorCol(tag, wired()), "接线游标同 24 格（既有读数）")
    }

    @Test
    fun `img headless 仍零格 不被错误形态判据波及`() {
        val tag = "<img src='a' width='180px' height='180px'>"
        assertEquals(0, CellWidth.visibleHtmlCells(tag, 0L), "img 不是 shape，headless 记 0")
        assertEquals(0, anchorCol(tag, headless()), "img 不是 shape，headless 游标记 0")
    }

    @Test
    fun `错误形态 headless 像素游标按格乘半字号 不另立一把尺`() {
        val tag = "<shape type='bad'>"
        val sink = headless()
        sink.printHtml("<nobr>" + tag + card() + "</nobr>", false)
        val b = sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()
        assertEquals(CellWidth.visibleHtmlCells(tag, 0L), b.anchorCol, "格与折行同值")
        assertEquals(b.anchorCol * 9, b.anchorPointXPx, "像素 = 格 x divFontSize(18)/2")
    }
}
