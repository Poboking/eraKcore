package com.eratw.port.render.adapter

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4 参 `<shape type='rect'>` 的**像素条带**行为（2026-10-23 真机「少女祈祷中」裸标签红）。
 *
 * ## 现象
 *
 * 语料 `ERB/魔改内容/PRINT系/UI_Components_&_Tables.ERB` 的 `@HTML_RECT_BAR` 渐变分支把进度条
 * 切成上百个 `param='0, 30, 1, 64'`（1 单位 = `FontSize / 100` 像素，真机字号 40 ⇒ 0.4px）
 * 的小矩形。真机整屏显示成 `<shape type='rect' param="0, 30, 1, 64" color="#7F7FDA">` 原文。
 *
 * ## 两处口径错误（本文件逐个钉住）
 *
 * 1. **判定用了整数**：原版 `Utils.MixedNum.ToPixelf` 是**浮点**（spec: `Utils.cs:60-71`），
 *    `1 * 18 / 100 = 0.18f` 满足 `Width > 0f`（spec: `ConsoleShapePart.cs:61-68`）；移植侧走
 *    `base * num / 100` 整数除 ⇒ 0 ⇒ 每块都被判「不可渲染」⇒ 落叶 AltText 变文本。
 * 2. **占宽按块各算一格**：本层折行的宽度单位是半角格，逐块 `cellsFor` 会让 40px 的条吃掉
 *    上百格（≈2000px）。改成连续条带按「总宽 ÷ 半角格宽」折整格数、逐格采样颜色。
 *
 * 夹具字号 18px ⇒ 半角步进 9.36px（[CellMetrics.cellPixelWidth]），期望值都按此手算。
 */
class HtmlShapeBandTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> =
        HtmlRunFlatten(metrics).flatten(
            TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML),
        )

    /** 渐变条：`chunks` 个 `unitPx` 宽的小矩形，颜色依次 `#000000`、`#000001`… */
    private fun gradientBar(chunks: Int, unitPx: Int, yUnits: Int = 30, hUnits: Int = 64): String =
        buildString {
            for (i in 0 until chunks) {
                append("<shape type='rect' param='0, ").append(yUnits).append(", ")
                    .append(unitPx).append("px, ").append(hUnits).append("' color='#")
                    .append(i.toString(16).uppercase().padStart(6, '0')).append("'>")
            }
        }

    /**
     * 9 块 × 2px = 18px 总宽 ⇒ 折 2 格（`ceil(18 / 9.36)`），每格 9px；
     * 颜色取每格区间中点所在块：中点 4.5px 落在第 3 块（累计 6px）⇒ `#000002`，
     * 中点 13.5px 落在第 7 块（累计 14px）⇒ `#000006`。
     */
    @Test
    fun `渐变条合成为一个带渐变的矩形且不回落成文本`() {
        val runs = flatten(gradientBar(9, 2) + "[3％]少女祈祷中…")
        // ① 最要紧的一条：裸标签绝不能再出现在文本段里
        assertTrue(
            runs.none { it.kind == RunKind.TEXT && it.text.contains("<shape") },
            "4 参 rect 不许回落成 AltText 文本：${runs.map { it.text }}",
        )
        val rects = runs.filter { it.graphic is InlineGraphic.Rect }.map { it.graphic as InlineGraphic.Rect }
        // ② 整条折成**一个**矩形：宽高按像素、占宽按格，渐变停点按位置降采样
        assertEquals(1, rects.size, "整条合成为一个段（像素级平滑，不再逐格出块）")
        val bar = rects[0]
        assertEquals(2, bar.widthCells, "18px / 9.36px ⇒ 2 格")
        assertEquals(18, bar.widthPx, "像素宽 = 各块之和")
        assertEquals(5, bar.yOffsetPx, "y = 18 * 30 / 100 = 5.4 ⇒ 5")
        assertEquals(11, bar.heightPx, "h = 18 * 64 / 100 = 11.52 ⇒ 11")
        val stops = bar.gradientArgb
        assertTrue(stops != null && stops.size == 2, "2 格 ⇒ 2 个渐变停点：${stops?.toList()}")
        assertEquals(0xFF000002L.toInt(), stops!![0], "首停点取第 1 格区间中点所在块的颜色")
        assertEquals(0xFF000006L.toInt(), stops[1], "次停点同理")
        assertEquals(stops[0], bar.colorArgb, "纯色字段取首停点（无 shader 的绘制侧仍有可见色）")
        // ③ 条带之后的文字照常接在后面（占宽跟着条带走）
        assertEquals("[3％]少女祈祷中…", runs.last().text)
    }

    /** 单块 4 参 rect 不参与合并，逐像素口径与改前一致（`px` 后缀 ⇒ 原值）。 */
    @Test
    fun `单块四参矩形口径不变`() {
        val g = flatten("<shape type='rect' param='0,1px,440px,2px' color='#FF8800'>")
            .single().graphic as InlineGraphic.Rect
        assertEquals(0, g.xOffsetPx)
        assertEquals(1, g.yOffsetPx)
        assertEquals(440, g.widthPx)
        assertEquals(2, g.heightPx)
        assertEquals(48, g.widthCells, "ceil(440 / 9.36) = 48")
    }

    /** 不同纵偏移/不同高度的两块不许并成一条带（各自的矩形要落在各自的位置上）。 */
    @Test
    fun `不同高或不同纵偏移的块不合并`() {
        val runs = flatten(
            "<shape type='rect' param='0, 10, 100px, 20px' color='#110000'>" +
                "<shape type='rect' param='0, 40, 100px, 20px' color='#220000'>",
        )
        val rects = runs.filter { it.graphic is InlineGraphic.Rect }.map { it.graphic as InlineGraphic.Rect }
        assertEquals(2, rects.size)
        assertEquals(listOf(1, 7), rects.map { it.yOffsetPx }, "y = 18 * 10 / 100 = 1.8 ⇒ 1；18 * 40 / 100 = 7.2 ⇒ 7")
        assertEquals(listOf(20, 20), rects.map { it.heightPx }, "h 带 px 后缀 ⇒ 原值 20")
        assertEquals(listOf(0xFF110000L.toInt(), 0xFF220000L.toInt()), rects.map { it.colorArgb })
    }

    /** 高度不足 1px 的细条照 `ConsoleRectangleShapePart.cs:28-33` 抬到 1px，不许整段消失。 */
    @Test
    fun `微小高度抬到一像素`() {
        val g = flatten("<shape type='rect' param='0, 0, 10px, 1' color='#FFFFFF'>")
            .single().graphic as InlineGraphic.Rect
        assertEquals(1, g.heightPx, "h = 18 * 1 / 100 = 0.18 ⇒ 0，但原版抬到 1")
        assertEquals(10, g.widthPx)
    }
}
