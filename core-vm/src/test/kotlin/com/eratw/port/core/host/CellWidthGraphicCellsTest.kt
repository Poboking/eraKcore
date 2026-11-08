package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图形段（`<img>` / `<shape>`）格宽换算的公式钉（2026-11-05 遗留①收口）。
 *
 * 判据 = [CellWidth.graphicScriptPx] + [CellWidth.graphicCells] 与渲染侧
 * `HtmlRunFlatten.graphicSize` 的同尺口径：量化式 `ceil(px / (字号 × 0.52f))`
 * （半角步进 = `FontSize/2 × 1.04`，spec: `StringMeasure.cs:37`；ceil 与不足 1 格
 * 抬 ±1 的镜像 = `DrawCmd.cellsFor` 的既有语义）。字号 18 ⇒ 半角格 9.36px。
 */
class CellWidthGraphicCellsTest {

    @Test
    fun `graphicScriptPx_px形态原值_百分比按脚本字号折算`() {
        assertEquals(216L, CellWidth.graphicScriptPx(216L, isPx = true, scriptFontPx = 18L))
        // 100% 字号 = 18 script px；与 HTML 侧 `param='100'` 的读数一致（HtmlStrict toPixelF 同式）
        assertEquals(18L, CellWidth.graphicScriptPx(100L, isPx = false, scriptFontPx = 18L))
        assertEquals(9L, CellWidth.graphicScriptPx(50L, isPx = false, scriptFontPx = 18L))
        // 负值保留符号（负 space = 回退推进，语料 TEMP_CHARA_LIST 用）
        assertEquals(-180L, CellWidth.graphicScriptPx(-180L, isPx = true, scriptFontPx = 18L))
    }

    @Test
    fun `graphicCells_量化为向上取整且不足一格抬一`() {
        assertEquals(24, CellWidth.graphicCells(216L, 18L), "ceil(216 / 9.36) = 24")
        assertEquals(2, CellWidth.graphicCells(18L, 18L), "ceil(18 / 9.36) = 2（1.92 抬 2）")
        assertEquals(1, CellWidth.graphicCells(1L, 18L), "不足 1 格抬 1")
        assertEquals(0, CellWidth.graphicCells(0L, 18L))
        assertEquals(0, CellWidth.graphicCells(216L, 0L), "无基尺（缺省关）= 0")
    }

    @Test
    fun `graphicCells_负宽镜像为回退推进`() {
        assertEquals(-24, CellWidth.graphicCells(-216L, 18L), "与正宽严格镜像（DrawCmd.cellsFor 同判）")
        assertEquals(-1, CellWidth.graphicCells(-1L, 18L))
    }

    @Test
    fun `visibleHtmlCells_图形行按渲染模型计入格宽`() {
        // CHARALIST 立绘行形态：img 显式宽 + 按钮文本
        val img = "<img src='a' width='216px' height='144px'>设置角色"
        // 216px → 24 格；设置角色 = 4 全角 = 8 格
        assertEquals(32, CellWidth.visibleHtmlCells(img, 18L))
        // 语料自定义角色菜单的缩进 shape（裸数字 = 字号百分比）：100 → 18px → 2 格
        val menu = "<nobr><shape type='space' param='100'><button value='0'>[  0] 你进行自定义角色</button>"
        // shape 2 格 + "[  0] 你进行自定义角色" = 5 + 1 + 16 = 22 格（nobr/button 标签不算）
        assertEquals(24, CellWidth.visibleHtmlCells(menu, 18L))
        // rect 单参：进度条块 216px → 24 格
        assertEquals(24, CellWidth.visibleHtmlCells("<shape type='rect' param='216px' color='red'>", 18L))
        // rect 四参（x,y,w,h）：占宽 = w（横向偏移不推进）
        assertEquals(
            24,
            CellWidth.visibleHtmlCells("<shape type='rect' param='0px, 30px, 216px, 64px'>", 18L),
            "占宽 = w = 216px → 24 格（ConsoleRectangleShapePart 的 X+Width 口径里只推进 w）",
        )
        // 负 space：回退推进（净宽为负的段）
        assertEquals(
            -20 + 4,
            CellWidth.visibleHtmlCells("<shape type='space' param='-180px'>文字", 18L),
            "-180px → -20 格 + 文字 4 格（语料地图覆盖行形态）",
        )
    }

    @Test
    fun `visibleHtmlCells_缺字号时图形计零_文本不变`() {
        // 0（缺省）= 既有行为：图形段 0 格（headless 测试与改造前逐值相同）
        assertEquals(8, CellWidth.visibleHtmlCells("<img src='a' width='216px'>设置角色"))
        assertEquals(2, CellWidth.visibleHtmlCells("<shape type='space' param='216px'>ab"))
    }
}
