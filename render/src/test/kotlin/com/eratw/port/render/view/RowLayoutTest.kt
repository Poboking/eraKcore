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
     * 手推（新口径）：图高 100px **高于**行高 38 ⇒ 第 3 行不可压（见 §3.5 的判据）⇒
     * `artRows = 0`、`textRows = 10`（第 0 行 + 第 1,2 + 第 4..9 共 6 行零命令行 + 图行本身
     * 按整行高算）⇒ `artAdvance` 不参与 ⇒ 每行高 38、`total = 380`、
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
     * 手推：360px 高于行高 38 ⇒ 第 6 行不可压 ⇒ `artRows = 0`、`textRows = 16`
     * ⇒ `total = 16*38 = 608`、`y = 1000 - 608 = 392` ⇒ `tops[6] = 392 + 6*38 = 620` ⇒
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
    // 3.5) 高于一行的图所在行**不参与压缩**（2026-11「图片遮住文字」）
    // ------------------------------------------------------------------

    /**
     * 立绘那一行的行高不得被压 —— 压了就会让图越出脚本预留的空行、盖住后面的文字。
     *
     * 形态照真机 41/43 档（`@PRINT_IMAGE` / `@HTML_PRINTL_PX`）：正文 10 行、立绘行 1 行、
     * 脚本预留的 9 条 `<br>` 空行、再一行台词。窗口 22 行、内容区 380px 高 ⇒ 内容必然超高
     * （`remain = 380 - 21*38 = -418 < 0`），正是玩家「历史攒满一屏」的现场。
     *
     * 手推（修复后）：立绘 360px > 行高 38 ⇒ **不可压** ⇒ `textRows = 21`、`artRows = 0` ⇒
     * 每行高 38、`total = 836`、`y = 380 - 836 = -456` ⇒ `tops[10] = -456 + 10*38 = -76`、
     * 图底 `= -76 + 360 = 284`；台词行 `tops[20] = -456 + 20*38 = 304` ⇒
     * **图底 284 < 台词行顶 304，不压字**（余量 20px = 行高 38 − 图 360/10 的取整差）。
     *
     * 修复前（把立绘行也压成 `minArt = 6`）：`total = 21*38 + 6 = 804`、`y = -424`、
     * `tops[10] = -44`、图底 `= 316`，而 `tops[20]` 仍是 304 ⇒ **压字 12px**；
     * 且这张图因 `heightPx > lineHeightPx` 被判越行（`GraphicGeometry.escapesLine`），
     * 绘制侧把它排进**第二趟铺墨**、画在文字之上 —— 净效果就是玩家看到的「图盖住字」。
     */
    @Test
    fun `高于一行的图所在行不参与压缩`() {
        val cmds = ArrayList<DrawCmd>()
        for (i in 0..9) cmds.add(text(i))
        cmds.add(image(10, heightPx = 360, widthPx = 360))
        // 第 11..19 行是脚本预留的空行（零命令），第 20/21 行是台词
        cmds.add(text(20))
        cmds.add(text(21))
        val p = plan(cmds, windowRows = 22, contentBottom = 380f)

        assertEquals(38f, p.heights[10], 1e-4f, "立绘行必须保持整行高（修复前被压成 6）")
        assertTrue(p.artOnly[10], "「只有图片段」这个标记不变，绘制侧仍走纯图行那条")
        assertEquals(-76f, p.tops[10], 1e-4f)
        assertEquals(304f, p.tops[20], 1e-4f, "台词行行顶")
        val imgBottom = p.tops[10] + 360f
        assertEquals(284f, imgBottom, 1e-4f)
        assertTrue(
            imgBottom <= p.tops[20],
            "图底 $imgBottom 必须落在台词行顶 ${p.tops[20]} 之上（修复前是 316 ⇒ 压字 12px）",
        )
    }

    /**
     * 可压判据与 [GraphicGeometry.artRowScale] 同源：**恰好等于行高**的图仍可压。
     *
     * `artRowScale(drawH = 38, rowHeight = 6, lineHeight = 38)` 走到最后一行
     * `rowHeight / drawH`（`drawH > lineHeight` 不成立 ⇒ 不返回 1），图被缩到 6px =
     * 行高本身 ⇒ 不会越行。所以 `imageHeight <= lineHeightPx` 是「能压」的充要条件。
     *
     * 手推：5 行各一条 38px 图、窗口 5 行、内容区 100px ⇒ `remain = 100 - 0 = 100`、
     * `fit = 20` ⇒ `artAdvance = 20`（与 16px 横带那一档同解）。
     */
    @Test
    fun `恰好一行高的图仍可压`() {
        val cmds = (0 until 5).map { image(it, heightPx = 38) }
        val p = plan(cmds, windowRows = 5, contentBottom = 100f)
        for (i in 0 until 5) {
            assertEquals(20f, p.heights[i], 1e-4f, "第 $i 行按 artAdvance 压")
            assertTrue(p.artOnly[i])
            // 绘制侧的实画高 = drawH × k，k 由 artRowScale 给出，必须正好等于行高
            val k = GraphicGeometry.artRowScale(38f, p.heights[i], lineHeight, 0)
            assertEquals(p.heights[i], 38f * k, 1e-4f, "实画高必须等于压缩后的行高")
        }
    }

    /**
     * 混合形态：同一屏里既有可压的横带行（16px）也有不可压的立绘行（360px）。
     * 只有横带行吃 `artAdvance`，立绘行保持整行高 —— 两个判据互不牵连。
     *
     * 手推：窗口 4 行、内容区 130px ⇒ 立绘那一行不可压 ⇒ `textRows = 3`（第 1/3 行 + 立绘行
     * 本身按整行高算）、`artRows = 1` ⇒ `remain = 130 - 3*38 = 16`、`fit = 16/1 = 16` ⇒
     * `artAdvance = 16.coerceIn(6, 38) = 16` ⇒ `heights = [16, 38, 38, 38]`、`total = 130`、
     * `y = 0` ⇒ `tops = [0, 16, 54, 92]`。
     */
    @Test
    fun `横带行可压而立绘行不压`() {
        val cmds = ArrayList<DrawCmd>()
        cmds.add(image(0, heightPx = 16))   // 标题横带：可压
        cmds.add(text(1))
        cmds.add(image(2, heightPx = 360))  // 立绘：不可压
        cmds.add(text(3))
        val p = plan(cmds, windowRows = 4, contentBottom = 130f)
        assertEquals(16f, p.heights[0], 1e-4f, "16px 横带行按 artAdvance = 16 压")
        assertEquals(38f, p.heights[1], 1e-4f)
        assertEquals(38f, p.heights[2], 1e-4f, "360px 立绘行不压")
        assertEquals(38f, p.heights[3], 1e-4f)
        assertTrue(p.artOnly[0] && p.artOnly[2], "两行的纯图标记都在")
        assertEquals(0f, p.tops[0], 1e-4f)
        assertEquals(16f, p.tops[1], 1e-4f)
        assertEquals(54f, p.tops[2], 1e-4f)
        assertEquals(92f, p.tops[3], 1e-4f)
    }

    /**
     * 合并竖带块的首行（`rowSpan > 0`）同样不参与压缩 —— 即使那块图**本身不高于一行**。
     *
     * 形态：`ImageBandMerge` 把若干条等宽等高横带合成一块，`rowsNeeded = 1`
     * （`drawHeight = 30 ≤ 行高 38`）、`rowSpan = 1`。绘制侧对这条命令**先判先返回**、
     * 走 `drawBandStack` 按 `image.heightPx` 逐片铺满、完全不看行高
     * （`CanvasConsoleView.kt:2603-2631` 的 `val destH = image.heightPx.toFloat()`）——
     * 也就是说 `artRowScale` 的 `rowSpan > 0` 守卫**在这条路上根本走不到**，
     * 布局侧必须自己把这一档排除，否则行高被压而图照铺，溢出无人兜。
     *
     * 手推（判据漏掉 `rowSpan` 时的错解）：`artRows = 1`、`textRows = 1`、
     * `remain = 60 - 38 = 22` ⇒ `artAdvance = 22` ⇒ 块行高 22 而图铺 30 ⇒ **溢出 8px**。
     * 新判据按整行高算：`artRows = 0`、`textRows = 2` ⇒ `heights = [38, 38]`、
     * `total = 76`、`y = 60 - 76 = -16` ⇒ `tops = [-16, 22]`，块 30px 占 `[-16, 14]`，
     * 落在自己那一行 `[-16, 22]` 之内 ⇒ 不压下一行。
     */
    @Test
    fun `合并竖带块的首行不参与压缩`() {
        val merged = DrawCmd(
            row = 0, colStart = 0, colEnd = 10, fore = 0xFFFFFF, back = 0x000000,
            styleMask = 0, kind = RunKind.IMAGE, glyphs = emptyList(),
            graphic = InlineGraphic.Image(
                resource = "TW_title000", widthPx = 200, heightPx = 30,
                yOffsetPx = 0, widthCells = 10,
                rowSpan = 1, bandResources = listOf("TW_title000", "TW_title001"),
            ),
        )
        val p = plan(listOf(merged, text(1)), windowRows = 2, contentBottom = 60f)
        assertEquals(38f, p.heights[0], 1e-4f, "竖带块行保持整行高（漏判会被压到 22）")
        assertTrue(p.artOnly[0], "它仍是「只有图片段」的行，绘制侧照旧走纯图行那一支")
        assertEquals(-16f, p.tops[0], 1e-4f)
        assertEquals(22f, p.tops[1], 1e-4f)
        assertTrue(
            p.tops[0] + 30f <= p.tops[1],
            "块底 ${p.tops[0] + 30f} 不得越过下一行行顶 ${p.tops[1]}（漏判时是 8px 溢出）",
        )
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
     * 窗口只有 1 行：360px 高于行高 38 ⇒ 不可压 ⇒ `artRows = 0`、`textRows = 1`
     * ⇒ `heights[0] = 38`、`total = 38` ⇒ `tops[0] = 100 - 38 = 62`。
     * （判据改之前这条走 `artRows = 1`、`fit = 100` 被 `coerceIn` 夹回 38，读数同值 ——
     * 断言不变，但只有新判据下「夹回 38」才是**语义正确**的结果而不是巧合。）
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
