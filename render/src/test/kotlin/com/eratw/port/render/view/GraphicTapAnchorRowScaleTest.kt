package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B1 定向单测：[GraphicTapAnchor.resolveAnchorRow] 的纵盒随「行压缩」的 k 缩放。
 *
 * 与 `ButtonLayerRowScaleHitTest` 同一份几何、同一个入口
 * （[GraphicGeometry.nodeRowScale] / [GraphicGeometry.scaledVerticalBox]）：一个管行内命中，
 * 一个管溢出图（跨行立绘）的锚行回退，两边不能各算一份 k。
 *
 * ## 布局
 *
 * 第 0 行是「压缩纯图行」：行高 6px、图高 16px ⇒ k = 6/16 = 0.375，纵盒 `[0, 6]`；
 * 第 1、2 行是普通文字行（21px）。`tops = [0, 6, 27]`。
 *
 * 点第 1 行、y = 10：压缩**前**的盒 `[0, 16]` 盖得住，压缩后盖不住 ⇒ 必须不解析
 * （否则玩家点在图下方空白上也会触发立绘按钮 = 错命中）。
 */
class GraphicTapAnchorRowScaleTest {

    private val lineHeight = 21f
    private val fontSize = 18

    /** 压缩纯图行的行高（`RowLayout.Plan.heights[0]`）。 */
    private val artRowHeight = 6f

    private fun imageRun(
        heightPx: Int,
        widthCells: Int = 20,
        buttonId: Int = 1,
        value: Any? = 90001L,
        rowSpan: Int = 0,
    ): TextRun = TextRun(
        text = "",
        fore = 0,
        back = 0,
        styleMask = 0,
        fontName = "msgothic",
        kind = RunKind.IMAGE,
        buttonValue = value,
        buttonId = buttonId,
        graphic = InlineGraphic.Image(
            resource = "立绘",
            widthPx = 180,
            heightPx = heightPx,
            yOffsetPx = 0,
            widthCells = widthCells,
            rowSpan = rowSpan,
        ),
    )

    private fun rowOf(vararg runs: TextRun, indentCells: Int = 0): DisplayRow {
        var cells = 0
        for (run in runs) cells += run.graphic?.widthCells ?: 0
        return DisplayRow(runs.toList(), cells, isLogicalLine = true, isLineEnd = true, indentCells = indentCells)
    }

    private fun blank() = DisplayRow(emptyList(), cells = 0, isLogicalLine = true, isLineEnd = true)

    /** 压缩纯图行 + 两条空行（与 `RowLayout.plan` 的产物同形：heights/tops 逐行累加）。 */
    private fun compressedRows(): List<DisplayRow> =
        listOf(rowOf(imageRun(16)), blank(), blank())

    private fun compressedTops(): FloatArray = floatArrayOf(0f, artRowHeight, artRowHeight + lineHeight)

    /** 绘制侧口径的行表三项：heights / artOnly / lineHeight（与 `CanvasConsoleView` 同源交付）。 */
    private val heights = floatArrayOf(6f, 21f, 21f)
    private val artOnly = booleanArrayOf(true, false, false)

    /** 图内的点命中（y=3 ∈ 压缩后的 [0, 6]）。 */
    @Test
    fun `压缩行图内的点解析回锚行`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 3f,
            fontSizePx = fontSize,
            heights = heights,
            artOnly = artOnly,
            lineHeightPx = lineHeight,
        )
        assertEquals(0, anchor)
    }

    /** 压缩前才覆盖的竖带（y=10 ∈ (6, 16]）不解析 —— 那一带屏上没有像素。 */
    @Test
    fun `压缩前才覆盖的竖带不解析`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
            heights = heights,
            artOnly = artOnly,
            lineHeightPx = lineHeight,
        )
        assertEquals(-1, anchor, "k=0.375 时纵盒只到 y=6，y=10 是空白")
    }

    /**
     * 不给行几何时退回压缩前口径（`lineHeightPx <= 0` ⇒ `artRowScale` 恒 1）：
     * 既有调用方行为逐位不变，且这一支是**宽松侧**（可能多命中），所以类文档要求
     * 新接线必须把 `heights` / `artOnly` / `lineHeightPx` 一起传。
     */
    @Test
    fun `不给行几何时退回压缩前口径`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
        )
        assertEquals(0, anchor, "旧口径：压缩前的 16px 盒仍盖住 y=10")
    }

    /** `heights` 缺省时用 `tops` 差分近似（末行推不出），结果与显式给 heights 一致。 */
    @Test
    fun `heights缺省时按tops差分近似`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
            artOnly = artOnly,
            lineHeightPx = lineHeight,
        )
        assertEquals(-1, anchor, "tops[1] - tops[0] = 6 = 压过的行高")
    }

    /** `artOnly` 缺省按纯图行算（保守侧）：压缩行上仍按 k 缩，不会因为缺一个布尔就多命中。 */
    @Test
    fun `artOnly缺省按纯图行算`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
            heights = heights,
            lineHeightPx = lineHeight,
        )
        assertEquals(-1, anchor)
    }

    /** 非纯图行（`artOnly=false`）⇒ k 恒 1：绘制侧在那种行上不缩图。 */
    @Test
    fun `非纯图行不缩放`() {
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = compressedRows(),
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
            heights = heights,
            artOnly = booleanArrayOf(false, false, false),
            lineHeightPx = lineHeight,
        )
        assertEquals(0, anchor, "artOnly=false ⇒ rowScaleOf 恒 1，纵盒仍是 [0, 16]")
    }

    /**
     * 高于一行的立绘（本项目 `GraphicTapAnchor` 的主要服务对象）**恒不缩**：
     * `artRowScale` 的第二条守卫（`drawH > lineHeight`）让它返回 1 —— 修 B1 不能把
     * 「点立绘无反应」那个修复弄回去。语料形态 `GET_BODY_SETS.ERB:115` 的 360px 立绘。
     */
    @Test
    fun `高于一行的立绘不随行压缩缩放`() {
        val rows = listOf(rowOf(imageRun(360)), blank(), blank(), blank(), blank())
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = rows,
            tops = floatArrayOf(0f, lineHeight, 2 * lineHeight, 3 * lineHeight, 4 * lineHeight),
            tapRow = 4,
            tapCol = 10,
            tapY = 4 * lineHeight + 10f,
            fontSizePx = fontSize,
            heights = floatArrayOf(lineHeight, lineHeight, lineHeight, lineHeight, lineHeight),
            artOnly = booleanArrayOf(true, false, false, false, false),
            lineHeightPx = lineHeight,
        )
        assertEquals(0, anchor, "360px 立绘的纵盒 [0, 360] 原样保留")
    }

    /** 跨行竖带块（`rowSpan > 0`）同样恒不缩（`artRowScale` 第一条守卫）。 */
    @Test
    fun `跨行竖带块不随行压缩缩放`() {
        val rows = listOf(rowOf(imageRun(16, rowSpan = 3)), blank(), blank())
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = rows,
            tops = compressedTops(),
            tapRow = 1,
            tapCol = 10,
            tapY = 10f,
            fontSizePx = fontSize,
            heights = heights,
            artOnly = artOnly,
            lineHeightPx = lineHeight,
        )
        assertEquals(0, anchor, "rowSpan > 0 ⇒ k = 1")
    }
}
