package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 缺陷 B 的判式钉子（CONTRACT-ANIME §4 的 6–9 号）：
 * [GraphicGeometry.escapedLineBox] / [GraphicGeometry.escapesLine] 与原版
 * `ConsoleButtonString.cs:165-185` 的 `FilterEscaped` 同口径
 * （判式 `p.Top < 0 || p.Bottom > Config.LineHeight`，`:171`）。
 *
 * 尺子取**真机 V2199A 档**：字号 36、行高 38（`CellMetrics(36, 38)`）。
 * 图片段一律经 [ConsoleFeed] 的真实展平（`HtmlRunFlatten`）拿，不手搓 `Image`，
 * 这样 `height='1000'` → `36 * 1000 / 100 = 360`（`ConsoleImagePart.cs:66` 的百分比支）
 * 这条折算链也被钉住。
 *
 * 本表**只钉判式**（§4 的 9 号：一份纯函数两个消费点）；两趟铺墨的次序在
 * `CanvasConsoleView`（Android 模块，无 test source set）与 `InkPainter` 里接同一个函数，
 * 不在此复制循环。
 */
class AnimeEscapePaintOrderTest {

    private val metrics = CellMetrics(36, 38)
    private val fontSizePx = 36
    private val lineHeightPx = 38f

    /** 真 sink → ConsoleFeed → ConsoleScreen 的展平路（与探针同一套装配）。 */
    private fun flattenCmds(vararg html: String): List<DrawCmd> {
        val s = LineBufferConsoleSink(clientWidth = 110, maxLines = 400)
        val screen = ConsoleScreen(clientWidth = 110, maxLog = 400, heightLines = 24)
        val f = ConsoleFeed(s, screen, GraphicsLayer(), metrics, null)
        for (h in html) s.printHtml(h, false)
        s.publishFrame()
        f.pump()
        return screen.list.render()
    }

    private fun firstImage(cmds: List<DrawCmd>): InlineGraphic.Image {
        for (c in cmds) {
            val g = c.graphic
            if (g is InlineGraphic.Image) return g
        }
        throw AssertionError("展平结果里没有图片段：$cmds")
    }

    // ------------------------------------------------------------------
    // 6) 立绘段溢出、标题横带不溢出、负 ypos 叠层溢出
    // ------------------------------------------------------------------

    /**
     * `@PRINT_IMAGE` 打的那条 `<img height='1000'>`（语料
     * `ERB/ステータス表示関連/IMAGE.ERB:528`，只读）：字号 36 下展平成
     * `heightPx = 36*1000/100 = 360`、`ypos = 0`
     * ⇒ 纵盒 `[0, 360]`（`ConsoleImagePart.cs:81,82,93`），`360 > 38` ⇒ **溢出**。
     */
    @Test
    fun `立绘段落进 escape 名单`() {
        val img = firstImage(flattenCmds("<p align='left'><nobr><img src='立绘X' height='1000'>"))
        assertEquals(360, img.heightPx, "百分比支：FontSize * num / 100")
        assertEquals(0, img.yOffsetPx)
        assertEquals(
            listOf(0, 360),
            GraphicGeometry.escapedLineBox(img, fontSizePx).toList(),
            "top = ypos、bottom = top + 高",
        )
        assertTrue(GraphicGeometry.escapesLine(img, fontSizePx, lineHeightPx))
    }

    /**
     * 标题图的 16px 横带（`height='16px'`、`ypos = 0`）：纵盒 `[0, 16]`，
     * `16 <= 38` ⇒ **不溢出**（原版 `FilterEscaped` 不收它，铺墨仍在自己那一趟里）。
     */
    @Test
    fun `标题横带不进 escape 名单`() {
        val img = firstImage(flattenCmds("<img src='TW_title01' height='16px'>"))
        assertEquals(16, img.heightPx, "px 形态原值（scriptFontPixelSize = 0 ⇒ 不折算）")
        assertEquals(listOf(0, 16), GraphicGeometry.escapedLineBox(img, fontSizePx).toList())
        assertFalse(GraphicGeometry.escapesLine(img, fontSizePx, lineHeightPx))
    }

    /**
     * 负 `ypos` 的叠层（XRAY 那种把图往上压一截的写法）：`top = -5 < 0` ⇒ **溢出**
     * （判式左支，`ConsoleButtonString.cs:171` 的 `p.Top < 0`）。
     */
    @Test
    fun `负 ypos 的叠层进 escape 名单`() {
        val img = firstImage(flattenCmds("<img src='立绘Y' height='30px' ypos='-5px'>"))
        assertEquals(-5, img.yOffsetPx)
        assertEquals(30, img.heightPx)
        assertEquals(listOf(-5, 25), GraphicGeometry.escapedLineBox(img, fontSizePx).toList())
        assertTrue(GraphicGeometry.escapesLine(img, fontSizePx, lineHeightPx))
    }

    // ------------------------------------------------------------------
    // 7) Rect 三支
    // ------------------------------------------------------------------

    private fun rect(y: Int, h: Int) = InlineGraphic.Rect(
        xOffsetPx = 0, yOffsetPx = y, widthPx = 100, heightPx = h,
        colorArgb = 0xFF00FF00.toInt(), widthCells = 6,
    )

    /**
     * `ConsoleRectangleShapePart.cs:34,35`：`top = Math.Min(0, rect.Y)`、
     * `bottom = Math.Max(Config.FontSize, rect.Y + rect.Height)` —— 纵盒**至少**盖住一行字。
     * `y = 1, h = 2` ⇒ `[0, max(36, 3)] = [0, 36]`，`36 <= 38` ⇒ 不溢出。
     */
    @Test
    fun `细横条矩形不算溢出`() {
        val g = rect(y = 1, h = 2)
        assertEquals(listOf(0, 36), GraphicGeometry.escapedLineBox(g, fontSizePx).toList())
        assertFalse(GraphicGeometry.escapesLine(g, fontSizePx, lineHeightPx))
    }

    /** `y = 0, h = 200` ⇒ `[0, 200]`，`200 > 38` ⇒ 溢出（进度条底、大色块那一类）。 */
    @Test
    fun `高矩形进 escape 名单`() {
        val g = rect(y = 0, h = 200)
        assertEquals(listOf(0, 200), GraphicGeometry.escapedLineBox(g, fontSizePx).toList())
        assertTrue(GraphicGeometry.escapesLine(g, fontSizePx, lineHeightPx))
    }

    /** `y = -5, h = 10` ⇒ `[-5, 36]`，`top < 0` ⇒ 溢出（左支）。 */
    @Test
    fun `负偏移矩形进 escape 名单`() {
        val g = rect(y = -5, h = 10)
        assertEquals(listOf(-5, 36), GraphicGeometry.escapedLineBox(g, fontSizePx).toList())
        assertTrue(GraphicGeometry.escapesLine(g, fontSizePx, lineHeightPx))
    }

    // ------------------------------------------------------------------
    // 8) Space 恒不溢出
    // ------------------------------------------------------------------

    /** `<shape type='space'>` 只推列游标、不画任何东西（原版 `ConsoleSpacePart`）⇒ 恒 false。 */
    @Test
    fun `space 段恒不溢出`() {
        for (cells in intArrayOf(0, 4, -40)) {
            val g = InlineGraphic.Space(widthCells = cells)
            assertEquals(listOf(0, 0), GraphicGeometry.escapedLineBox(g, fontSizePx).toList())
            assertFalse(GraphicGeometry.escapesLine(g, fontSizePx, lineHeightPx))
        }
    }

    // ------------------------------------------------------------------
    // 9) 判式把一帧切成「普通趟 / escape 趟」两拨（不动绘制循环）
    // ------------------------------------------------------------------

    /**
     * 动图那一屏（图行 + 9 条 `<br>` + 前置正文）走真实展平，按判式分拨：
     * **只有那条图片命令**进 escape 趟，文字命令一条都不进
     * （文本件 `Top = 0`、`Bottom = Config.FontSize = 36 <= 38`，
     * `AConsoleDisplayNode.cs:29,31` ⇒ 原版也从不把它们收进名单）。
     * 这就是两趟铺墨的划分依据；次序本身（趟内行升序）由消费点保证，本表不复制循环。
     */
    @Test
    fun `一帧里只有立绘段进第二趟`() {
        val s = LineBufferConsoleSink(clientWidth = 110, maxLines = 400)
        val screen = ConsoleScreen(clientWidth = 110, maxLog = 400, heightLines = 24)
        val f = ConsoleFeed(s, screen, GraphicsLayer(), metrics, null)
        repeat(6) { i -> s.printLine("正文行%02d".format(i)) }
        s.printHtml("<p align='left'><nobr><img src='立绘X' height='1000'>", false)
        repeat(9) { s.printHtml("<br>", false) }
        s.publishFrame()
        f.pump()
        val cmds = screen.list.render()
        var escaping = 0
        var normal = 0
        for (c in cmds) {
            val g = c.graphic
            val hit = g != null && GraphicGeometry.escapesLine(g, fontSizePx, lineHeightPx)
            if (hit) escaping++ else normal++
        }
        assertEquals(1, escaping, "escape 趟只该收那条 360px 立绘")
        assertEquals(cmds.size - 1, normal, "文字段一律留在第一趟")
    }

    /**
     * `heightPx <= 0` 的段按原版当「不溢出」处理（下沿不超过上沿）：
     * `ypos = 0, h = 0` ⇒ `[0, 0]` ⇒ false。
     * 原版对负高是取负翻正（`ConsoleImagePart.cs:88-92`，`Y -= Height; num = -Height`），
     * **不在本刀范围** ⇒ TODO(verify) 负高段翻正后 `top` 下移到 `top + |h|`、
     * 理论上能同时越出下沿；卡点＝本端没有负高语料实测（展平侧原样交进来）。
     */
    @Test
    fun `零高与非正高段不判溢出`() {
        val zero = InlineGraphic.Image(
            resource = "空", widthPx = 0, heightPx = 0, yOffsetPx = 0, widthCells = 1,
        )
        assertEquals(listOf(0, 0), GraphicGeometry.escapedLineBox(zero, fontSizePx).toList())
        assertFalse(GraphicGeometry.escapesLine(zero, fontSizePx, lineHeightPx))
        val negative = zero.copy(heightPx = -100)
        assertEquals(listOf(0, -100), GraphicGeometry.escapedLineBox(negative, fontSizePx).toList())
        assertFalse(GraphicGeometry.escapesLine(negative, fontSizePx, lineHeightPx))
    }
}
