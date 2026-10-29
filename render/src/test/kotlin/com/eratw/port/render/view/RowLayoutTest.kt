package com.eratw.port.render.view

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RowLayout.plan] 的行为锁死（CONTRACT-ANIME §4 的 1–5 号钉子 + `rowIndex`）。
 *
 * 期望值全部**按 §3.1 的算式手推**、注释里写清算式，没有一个数字是从实现里抄的：
 * 行表长度恒 = 窗口行数（原版 `EmueraConsole.cs:1852-1856` 的逐行
 * `num += Config.LineHeight`，与该行有无内容无关）；底对齐 `y = contentBottom - total`；
 * `artAdvance = (remain > 0 ? remain / artRows : minArt).coerceIn(minArt, lineHeight)`。
 *
 * 缺省尺：`contentTop = 0`、`lineHeightPx = 38f`（真机 V2199A 档 = 字号 36 × 19/18）、
 * `minArtRowHeightPx = 6f`（= `CanvasConsoleView.MIN_ART_ROW_HEIGHT`）。
 */
class RowLayoutTest {

    private val lineHeight = 38f
    private val minArt = 6f

    /** 一条纯文字命令（`graphic = null` ⇒ 归并进「文字行」）。 */
    private fun text(row: Int, colEnd: Int = 8) = DrawCmd(
        row = row, colStart = 0, colEnd = colEnd, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.TEXT, glyphs = emptyList(),
    )

    /** 一条内联图命令（`heightPx` 决定该行纵盒下沿）。 */
    private fun image(row: Int, heightPx: Int, yOffsetPx: Int = 0, widthPx: Int = heightPx) = DrawCmd(
        row = row, colStart = 0, colEnd = 10, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.IMAGE, glyphs = emptyList(),
        graphic = InlineGraphic.Image(
            resource = "立绘X", widthPx = widthPx, heightPx = heightPx,
            yOffsetPx = yOffsetPx, widthCells = 10,
        ),
    )

    private fun plan(
        cmds: List<DrawCmd>,
        windowRows: Int,
        contentBottom: Float,
        contentTop: Float = 0f,
    ) = RowLayout.plan(cmds, windowRows, contentTop, contentBottom, lineHeight, minArt)

    // ------------------------------------------------------------------
    // 1) 尾部空行也建行（缺陷 A 的正面钉子）
    // ------------------------------------------------------------------

    /**
     * 窗口 10 行，命令只落在第 0 行（字）与第 3 行（图，高 100px）；第 4..9 行是
     * `<br>` 那种**零命令**空行。
     *
     * 手推（新口径）：`textRows = 9`（第 0 行 + 第 1,2 + 第 4..9 共 6 行零命令行）、
     * `artRows = 1` ⇒ `remain = 380 - 9*38 = 38`、`fit = 38/1 = 38`、
     * `artAdvance = 38.coerceIn(6, 38) = 38` ⇒ 每行高 38、`total = 380`、
     * `y = 380 - 380 = 0` ⇒ `tops[i] = 38*i`。
     * 旧实现（按 cmds 极值）在这里给 `tops.size == 4`、图行贴死 `contentBottom`。
     */
    @Test
    fun `尾部空行也建行`() {
        val p = plan(listOf(text(0), image(3, heightPx = 100)), windowRows = 10, contentBottom = 380f)
        assertEquals(10, p.tops.size, "行表长度恒为窗口行数，零命令行也必须建行")
        assertEquals(10, p.heights.size)
        assertEquals(10, p.artOnly.size)
        // 末行底边 == contentBottom（底对齐吃满整窗，尾部空行把图行顶回上面去）
        assertEquals(380f, p.tops[9] + p.heights[9], 1e-4f)
        // 图行（第 3 行）纵盒不得越出 contentBottom
        assertTrue(
            p.tops[3] + 100f <= 380f,
            "图底 ${p.tops[3] + 100f} 应落在 contentBottom 380 以内（旧实现是 ${228f + 3 * 38f + 100f}）",
        )
        assertEquals(114f, p.tops[3], 1e-4f)
    }

    /**
     * 动图那一屏的真实形态（= 探针的贴底场景）：窗口 16 行、正文 0..5 行、
     * 第 6 行 360px 立绘、第 7..15 行是 `PRINT_IMAGE` 预留的 9 条 `<br>`（零命令）。
     *
     * 手推：`textRows = 6 + 9 = 15`、`artRows = 1` ⇒ `remain = 1000 - 15*38 = 430`、
     * `fit = 430` ⇒ `artAdvance = coerceIn(6, 38) = 38` ⇒ `total = 16*38 = 608`、
     * `y = 1000 - 608 = 392` ⇒ `tops[6] = 392 + 6*38 = 620` ⇒
     * `imgBottom = 620 + 360 = 980` ⇒ `overflowBelowBottom = 980 - 1000 = -20`（落在屏内）。
     * 修复前实测：`tableRows = 7`、`tops[6] = 962`、`imgBottom = 1322`、溢出 `+322px`。
     */
    @Test
    fun `动图形态的溢出读数落在屏内`() {
        val cmds = ArrayList<DrawCmd>()
        for (i in 0..5) cmds.add(text(i))
        cmds.add(image(6, heightPx = 360, widthPx = 360))
        val p = plan(cmds, windowRows = 16, contentBottom = 1000f)
        assertEquals(16, p.tops.size, "窗口 16 行 ⇒ 行表 16 行（修复前是 7）")
        assertEquals(620f, p.tops[6], 1e-4f)
        assertEquals(-20f, p.tops[6] + 360f - 1000f, 1e-4f, "图底相对 contentBottom 的溢出量")
        assertTrue(p.artOnly[6], "第 6 行只有图片段 ⇒ 纯图行")
        assertFalse(p.artOnly[7], "第 7 行零命令 = 普通文字行，不是纯图行")
    }

    // ------------------------------------------------------------------
    // 2) 无压缩时与等距原点逐值相同
    // ------------------------------------------------------------------

    /**
     * 每行都有文字命令 ⇒ 不触发任何压缩：`heights[i] == lineHeightPx` 且
     * `tops[i] == contentBottom - (windowRows - i) * lineHeightPx`。
     *
     * 后一式就是 [ScreenComposer.rowTopPx]（`view/ScreenComposer.kt:142`
     * = `canvasHeightPx - (rowCount - row) * lineHeight`）与
     * `CanvasConsoleView.contentOriginY` 的等距表；本刀之后两张表同一把尺。
     */
    @Test
    fun `无压缩时与等距原点逐值相同`() {
        val windowRows = 6
        val contentBottom = 250f
        val cmds = (0 until windowRows).map { text(it) }
        val p = plan(cmds, windowRows, contentBottom)
        val composer = ScreenComposer(
            clientWidthCells = 110,
            heightLines = windowRows,
            metrics = CellMetrics(36, 38),
            canvasHeightPx = contentBottom.toInt(),
        )
        for (i in 0 until windowRows) {
            assertEquals(lineHeight, p.heights[i], 1e-4f, "第 $i 行不该被压缩")
            val expected = contentBottom - (windowRows - i) * lineHeight
            assertEquals(expected, p.tops[i], 1e-4f, "第 $i 行行顶按底对齐等距推")
            assertEquals(composer.rowTopPx(i, windowRows).toFloat(), p.tops[i], 1e-4f)
        }
    }

    // ------------------------------------------------------------------
    // 3) 纯图片行仍可压缩（标题页防回归）
    // ------------------------------------------------------------------

    /**
     * 一屏全是纯图行（每行一条 16px 横带）⇒ 走等分压缩那一支。
     *
     * 手推：`textRows = 0`、`artRows = 5` ⇒ `remain = 100`、`fit = 100/5 = 20`、
     * `artAdvance = 20.coerceIn(6, 38) = 20` ⇒ 每行高 20、`total = 100`、
     * `y = 100 - 100 = 0` ⇒ `tops = [0, 20, 40, 60, 80]`。
     * 与修复前逐值相同（旧实现同样每行都有命令），这条只防回归。
     */
    @Test
    fun `纯图片行仍可压缩`() {
        val cmds = (0 until 5).map { image(it, heightPx = 16) }
        val p = plan(cmds, windowRows = 5, contentBottom = 100f)
        assertEquals(5, p.tops.size)
        for (i in 0 until 5) {
            assertEquals(20f, p.heights[i], 1e-4f, "第 $i 行压缩后的行高")
            assertEquals((20 * i).toFloat(), p.tops[i], 1e-4f)
            assertTrue(p.artOnly[i])
        }
    }

    /**
     * 可用高度非正（`contentBottom <= contentTop`）时按 `available.coerceAtLeast(0f)`
     * 走、不抛：`remain = 0 - 0 = 0` 不进等分那一支 ⇒ `artAdvance = 6`。
     * `total = 18` ⇒ `y = 0 - 18 = -18` ⇒ `tops = [-18, -12, -6]`（负行顶由 clipRect 裁）。
     */
    @Test
    fun `可用高度非正时不抛`() {
        val cmds = (0 until 3).map { image(it, heightPx = 16) }
        val p = RowLayout.plan(cmds, 3, contentTop = 10f, contentBottom = 0f, lineHeight, minArt)
        assertEquals(3, p.tops.size)
        for (i in 0 until 3) {
            assertEquals(6f, p.heights[i], 1e-4f)
            assertEquals((-18 + 6 * i).toFloat(), p.tops[i], 1e-4f)
        }
    }

    // ------------------------------------------------------------------
    // 4) 窗口行数 0 / 1 / 负数
    // ------------------------------------------------------------------

    @Test
    fun `窗口行数为 0 或负数返回空表`() {
        val cmds = listOf(text(0), image(1, heightPx = 360))
        for (rows in intArrayOf(0, -1, -3)) {
            val p = plan(cmds, windowRows = rows, contentBottom = 1000f)
            assertEquals(0, p.tops.size, "windowRows=$rows ⇒ 空表")
            assertEquals(0, p.heights.size)
            assertEquals(0, p.artOnly.size)
            assertEquals(-1, p.rowIndex(0f))
        }
    }

    /**
     * 窗口只有 1 行：`textRows = 0`、`artRows = 1` ⇒ `remain = 100`、`fit = 100` ⇒
     * `artAdvance = coerceIn(6, 38) = 38`、`total = 38` ⇒ `tops[0] = 100 - 38 = 62`。
     */
    @Test
    fun `窗口只有 1 行`() {
        val p = plan(listOf(image(0, heightPx = 360)), windowRows = 1, contentBottom = 100f)
        assertEquals(1, p.tops.size)
        assertEquals(62f, p.tops[0], 1e-4f)
        assertEquals(38f, p.heights[0], 1e-4f)
        assertTrue(p.artOnly[0])
    }

    /** 零命令 + 正窗口行数 = 一屏空白行（仍旧按窗口建行、底对齐），不是空表。 */
    @Test
    fun `空屏也按窗口建行`() {
        val p = plan(emptyList(), windowRows = 4, contentBottom = 152f)
        assertEquals(4, p.tops.size)
        for (i in 0 until 4) {
            assertEquals(38f, p.heights[i], 1e-4f)
            assertEquals((i * 38).toFloat(), p.tops[i], 1e-4f)
            assertFalse(p.artOnly[i])
        }
    }

    // ------------------------------------------------------------------
    // 5) 区间内空行行高不变（只补尾部/头部，不改中段）
    // ------------------------------------------------------------------

    /**
     * 命令落在第 0、5 行，中间 4 行零命令：这 4 行的 `heights` 与修复前**逐值相同**
     * （旧实现靠 `[minRow, maxRow]` 连续下标侥幸拿到 `lineHeightPx`）。
     */
    @Test
    fun `区间内空行行高不变`() {
        val p = plan(listOf(text(0), text(5)), windowRows = 10, contentBottom = 380f)
        assertEquals(10, p.tops.size)
        for (i in 1..4) {
            assertEquals(lineHeight, p.heights[i], 1e-4f, "第 $i 行零命令仍是普通文字行高")
            assertFalse(p.artOnly[i])
        }
        // 中段行顶连续：相邻两行差一个行高（旧实现同值）
        for (i in 1..4) {
            assertEquals(lineHeight, p.tops[i] - p.tops[i - 1], 1e-4f)
        }
    }

    // ------------------------------------------------------------------
    // rowIndex（旧 RowMetrics.indexOf 原样搬）
    // ------------------------------------------------------------------

    @Test
    fun `rowIndex 命中与越界`() {
        val p = plan(listOf(text(0), image(3, heightPx = 100)), windowRows = 10, contentBottom = 380f)
        assertEquals(3, p.rowIndex(114f))
        assertEquals(3, p.rowIndex(151.99f))
        assertEquals(4, p.rowIndex(152f))
        assertEquals(0, p.rowIndex(0f))
        assertEquals(-1, p.rowIndex(380f), "contentBottom 以下不属于任何行")
        assertEquals(-1, p.rowIndex(-0.5f), "内容区以上不属于任何行")
    }
}
