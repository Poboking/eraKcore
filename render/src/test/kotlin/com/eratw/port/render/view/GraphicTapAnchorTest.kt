package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [GraphicTapAnchor] 的定向单测（点击立绘无反应的修法）。
 *
 * 语料形态（只读参考）：`ERB/BODY_INFO/GET_BODY_SETS.ERB:115` 的
 * `<button value='90000+bitPart'><img src width='180px' height='180px'>` ——
 * 图高按一行の高さ × 9~18 折算，锚行只有一行命中区，图身压在脚本预留的空行上。
 * 本组用例把那页简化成「锚行一张高图 + 若干空行」，钉住：
 * 点图身（溢出行）解析回锚行、闸外（列外 / 纵盒外 / 非按钮图）不解析、就近优先。
 */
class GraphicTapAnchorTest {

    /** 行高（像素）：tops 按 `i * LINE` 生成，与 RowLayout 的等距形态同构。 */
    private val line = 20f
    private val fontSize = 18

    private fun tops(n: Int): FloatArray = FloatArray(n) { it * line }

    /** 造一条带按钮图的锚行（列游标口径与 ButtonLayer.attach 同式）。 */
    private fun anchorRow(
        widthCells: Int,
        heightPx: Int,
        buttonId: Int = 1,
        buttonValue: Any? = 90001L,
        kind: RunKind = RunKind.IMAGE,
        indentCells: Int = 0,
    ): DisplayRow {
        val img = TextRun(
            text = "",
            fore = 0,
            back = 0,
            styleMask = 0,
            fontName = "黑体",
            kind = kind,
            buttonValue = buttonValue,
            buttonId = buttonId,
            graphic = InlineGraphic.Image(
                resource = "立绘",
                widthPx = 180,
                heightPx = heightPx,
                yOffsetPx = 0,
                widthCells = widthCells,
            ),
        )
        return DisplayRow(listOf(img), cells = widthCells, isLogicalLine = true, isLineEnd = true, indentCells = indentCells)
    }

    private fun blankRow(): DisplayRow =
        DisplayRow(emptyList(), cells = 0, isLogicalLine = true, isLineEnd = true)

    /** 立绘页：锚行 1 张 20 格宽、360px 高（18 行）的按钮图，其后 19 条脚本预留的空行。 */
    private fun bodyGraphRows(): List<DisplayRow> =
        listOf(anchorRow(widthCells = 20, heightPx = 360)) + List(19) { blankRow() }

    @Test
    fun `点溢出区解析回锚行`() {
        val rows = bodyGraphRows()
        // 点第 3 排图身（行内 y=10，绝对 y=70 ∈ 锚行纵盒 [0, 360]）
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 10, tapY = 3 * line + 10, fontSizePx = fontSize)
        assertEquals(0, anchor)
    }

    @Test
    fun `锚行自身也命中`() {
        val rows = bodyGraphRows()
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 0, tapCol = 10, tapY = 10f, fontSizePx = fontSize)
        assertEquals(0, anchor)
    }

    @Test
    fun `纵盒下沿闭区间命中`() {
        val rows = bodyGraphRows()
        // 原版 escaped 命中是 num4 >= Top && num4 <= Bottom（EmueraConsole.cs:2420）：
        // y 恰等于 box 下沿（360）仍算命中
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 17, tapCol = 10, tapY = 360f, fontSizePx = fontSize)
        assertEquals(0, anchor)
    }

    @Test
    fun `纵盒外不解析`() {
        val rows = bodyGraphRows()
        // 图底 360 = 第 18 排上沿；第 19 排（y=390）已出像素盒
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 19, tapCol = 10, tapY = 19 * line + 10, fontSizePx = fontSize)
        assertEquals(-1, anchor)
    }

    @Test
    fun `列区间外不解析`() {
        val rows = bodyGraphRows()
        // 图占 [0, 20) 列；col=25 在图右侧
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 25, tapY = 3 * line + 10, fontSizePx = fontSize)
        assertEquals(-1, anchor)
    }

    @Test
    fun `列游标从indentCells起算`() {
        val rows = listOf(anchorRow(widthCells = 20, heightPx = 360, indentCells = 5)) + List(19) { blankRow() }
        // CENTER/RIGHT 平移后图占 [5, 25)：col=24 命中、col=4 不命中
        assertEquals(0, GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 24, tapY = 3 * line + 10, fontSizePx = fontSize))
        assertEquals(-1, GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 4, tapY = 3 * line + 10, fontSizePx = fontSize))
    }

    @Test
    fun `无按钮编号的手工图不解析`() {
        val rows = listOf(anchorRow(widthCells = 20, heightPx = 360, buttonId = -1)) + List(19) { blankRow() }
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 10, tapY = 3 * line + 10, fontSizePx = fontSize)
        assertEquals(-1, anchor)
    }

    @Test
    fun `非图形kind带残值不解析`() {
        val rows = listOf(anchorRow(widthCells = 20, heightPx = 360, kind = RunKind.TEXT)) + List(19) { blankRow() }
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = 10, tapY = 3 * line + 10, fontSizePx = fontSize)
        assertEquals(-1, anchor)
    }

    @Test
    fun `多个候选盖住同一点时取最近的锚`() {
        // 第 0 排一张 360px 高图，第 2 排又叠一张 100px 高图（同列）：
        // 点第 5 排（y=110）时 row 2 的纵盒 [40, 140] 比 row 0 的 [0, 360] 近
        val rows = listOf(
            anchorRow(widthCells = 20, heightPx = 360, buttonId = 1),
            blankRow(),
            anchorRow(widthCells = 20, heightPx = 100, buttonId = 2),
            blankRow(),
            blankRow(),
            blankRow(),
        )
        val anchor = GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 5, tapCol = 10, tapY = 5 * line + 10, fontSizePx = fontSize)
        assertEquals(2, anchor)
    }

    @Test
    fun `空表与负列返回-1`() {
        assertEquals(-1, GraphicTapAnchor.resolveAnchorRow(emptyList(), FloatArray(0), tapRow = 0, tapCol = 0, tapY = 0f, fontSizePx = fontSize))
        val rows = bodyGraphRows()
        assertEquals(-1, GraphicTapAnchor.resolveAnchorRow(rows, tops(rows.size), tapRow = 3, tapCol = -1, tapY = 3 * line + 10, fontSizePx = fontSize))
    }
}
