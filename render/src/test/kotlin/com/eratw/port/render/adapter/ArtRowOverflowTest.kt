package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicGeometry
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.RowLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 玩家报的「图片会遮住文字」的现场判据（2026-11 真机截图 + 反馈原话）。
 *
 * ## 现场
 *
 * 真机 41/43 档（旧 110 列档：字号 41 / 行高 43，值保留作夹具），历史攒满一屏之后跑
 * 移动指令，脚本按 `@PRINT_IMAGE` / `@HTML_PRINTL_PX` 那一套打立绘：
 * `<img height='1000'>`（= 10 倍字号 = 410px 高）+ `PRINTFORML` ×9 条预留空行 + 台词。
 * 屏上就是「立绘旁边/下面的台词被立绘压住一截」。
 *
 * ## 因果链（三段，缺一段都不成立）
 *
 * 1. **布局段**：`RowLayout.plan` 把立绘那一行当「纯图行」等分压缩。历史攒满时
 *    `remain = 可用高 - textRows × 行高 < 0` ⇒ `artAdvance` 掉到
 *    `MIN_ART_ROW_HEIGHT` = 6px，立绘行只剩 6px；而脚本预留的 9 条空行仍是 9 × 43。
 *    整块 `6 + 387 = 393` 比图矮 **17px**。
 * 2. **绘制段**：`GraphicGeometry.artRowScale` 拒绝把高于一行的图缩进行高
 *    （`drawH > lineHeight ⇒ 1f`，2026-10-26 修「立绘被压成 21px」时加的守卫）⇒ 图照
 *    410px 画，底部越过预留块 17px，压到下一行台词的顶部。
 * 3. **铺墨段**：`GraphicGeometry.escapesLine` 判 `heightPx > lineHeightPx` ⇒ 这张图进
 *    「溢出部件」名单，`CanvasConsoleView.drawScreen` 的第二趟铺墨把它**画在文字之上**
 *    （原版 `EmueraConsole.cs:1858-1872` 的补铺语义）。于是不是「文字盖图」而是
 *    **「图盖文字」**，玩家看到的就是被遮住。
 *
 * 修法在布局段：立绘行不参与压缩（判据与 `artRowScale` 同源），整块回到 10 × 43 = 430px
 * ≥ 图 410px，图底落回预留块内。本文件钉的就是这条不变量。
 *
 * ## 为什么 PC 上不会出现
 *
 * 原版行距恒为 `Config.LineHeight`（`EmueraConsole.cs:1855` 的 `num += Config.LineHeight`），
 * **从不压行高**；压缩是端口为标题图（35 条 16px 横带）自己加的手段，不该外溢到立绘。
 */
class ArtRowOverflowTest {

    /** 旧 110 列档一屏（旧 `PageFitter.fit(2400, 1000, 20)` 的解：字号 41 / 行高 43）。 */
    private val metrics = CellMetrics(41, 43)
    private val lineHeight = metrics.lineHeight.toFloat()

    /** 内容区高度取真机截图那档（1080 高屏减去上下内边距后约 721）。 */
    private val viewHeight = 721f

    /** 只查表不画像素的假取图口：立绘基准 500×700（纵横比决定缺宽时的图宽）。 */
    private class FakeSizes(private val w: Int, private val h: Int) : HtmlRunFlatten.ImageSizeSource {
        override fun destSize(resource: String): Int? = (w shl 16) or h
    }

    /**
     * 一个立绘块：图行 + 脚本预留的 9 条空行 + 台词行。
     * `PRINTFORML` 在 core 侧是「空缓冲先 Append(" ")」⇒ 落成一条空格行，与语料同形。
     */
    private fun block(sink: LineBufferConsoleSink, line: String) {
        sink.printHtml("<img src='立絵' height='1000'>", false)
        repeat(9) { sink.newLine() }
        sink.printLine(line)
    }

    private fun build(historyLines: Int, blocks: Int): Pair<ConsoleScreen, RowLayout.Plan> {
        val sink = LineBufferConsoleSink(clientWidth = 136)
        repeat(historyLines) { sink.printLine("第${it}行历史文字内容占位") }
        repeat(blocks) { block(sink, "第${it}个立绘的台词") }
        val screen = ConsoleScreen(clientWidth = 136, maxLog = 400, heightLines = 40)
        val feed = ConsoleFeed(sink, screen, GraphicsLayer(), metrics, FakeSizes(500, 700))
        feed.pump()
        val cmds = screen.list.render()
        val rows = screen.list.rowCount
        return screen to RowLayout.plan(cmds, rows, 0f, viewHeight, lineHeight, 6f)
    }

    /**
     * 该行是否**有可见字形**的文字行。
     *
     * 脚本预留的空行不是空的：`PRINTFORML` 在 core 侧走「空缓冲先 Append(" ")」
     * （`LineBufferConsoleSink.newLine`），落成一条**空格行**，所以不能用
     * 「`glyphs` 非空」判可见 —— 立绘本来就要盖过那些预留空行，把它们算进来会把
     * 判据变成「立绘不许溢出」，那是另一回事。这里按码位排除空格（半角/全角）。
     */
    private fun isGlyphRow(screen: ConsoleScreen, row: Int): Boolean =
        screen.list.render().any { c ->
            c.row == row && c.graphic == null &&
                c.glyphs.any { it.codePoint != 0x20 && it.codePoint != 0x3000 }
        }

    @Test
    fun `历史攒满一屏时立绘不压台词`() {
        val (screen, plan) = build(historyLines = 24, blocks = 2)
        val cmds = screen.list.render()
        val images = cmds.filter { it.graphic is InlineGraphic.Image }
        assertEquals(2, images.size, "两个立绘块")

        for (cmd in images) {
            val g = cmd.graphic as InlineGraphic.Image
            val row = cmd.row
            assertEquals(410, g.heightPx, "height='1000' ⇒ 10 × 字号 41 = 410px")
            assertEquals(
                lineHeight, plan.heights[row], 1e-4f,
                "立绘行必须保持整行高 —— 被压成 MIN_ART_ROW_HEIGHT(6) 就是压字的直接原因",
            )
            assertTrue(plan.artOnly[row], "纯图行标记不变（绘制侧仍走立绘那条）")

            // 绘制侧口径：k 由 artRowScale 给出（高于一行的图不缩 ⇒ k = 1）
            val k = GraphicGeometry.artRowScale(g.heightPx.toFloat(), plan.heights[row], lineHeight, g.rowSpan)
            val drawnBottom = plan.tops[row] + g.yOffsetPx + g.heightPx * k
            // 该块之后第一条**有字形**的行（跳过预留空行）
            val nextGlyphRow = (row + 1 until plan.tops.size).firstOrNull { isGlyphRow(screen, it) }
            assertTrue(nextGlyphRow != null, "第 $row 行之后必须有台词行")
            assertTrue(
                drawnBottom <= plan.tops[nextGlyphRow] + 1e-3f,
                "立绘实画底 $drawnBottom 不得越过台词行顶 ${plan.tops[nextGlyphRow]}" +
                    "（row=$row top=${plan.tops[row]} h=${plan.heights[row]} k=$k）",
            )
        }
    }

    /**
     * 机制的另一半：这张图**确实**会被判越行 ⇒ 第二趟铺墨画在文字之上。
     * 所以「图底不越台词行顶」这条几何不变量是唯一能挡住压字的东西，不是可选项。
     */
    @Test
    fun `立绘被判越行所以压在文字之上`() {
        val (screen, _) = build(historyLines = 4, blocks = 1)
        val g = screen.list.render().first { it.graphic is InlineGraphic.Image }.graphic as InlineGraphic.Image
        assertTrue(
            GraphicGeometry.escapesLine(g, metrics.fontPixelSize, lineHeight),
            "410px 的立绘必然越行（原版 FilterEscaped 的判式），第二趟铺墨会把它画在文字之上",
        )
    }

    /**
     * 历史很少（内容装得下）时行表不压缩，立绘行的行高与空档都应与上面那条一致 ——
     * 修复前后这条都该绿，用来证明修法没有把「不压缩」的正常档改动。
     */
    @Test
    fun `内容装得下时立绘行也是整行高`() {
        val (screen, plan) = build(historyLines = 2, blocks = 1)
        val row = screen.list.render().first { it.graphic is InlineGraphic.Image }.row
        assertEquals(lineHeight, plan.heights[row], 1e-4f)
        // 整块 10 行：图行 + 9 条预留空行
        val glyphRow = (row + 1 until plan.tops.size).first { isGlyphRow(screen, it) }
        assertEquals(
            row + 10, glyphRow,
            "台词行必须正好落在图行之后第 10 行（脚本预留 ceil(410/43)=10 行的同一口径）",
        )
        assertTrue(plan.tops[glyphRow] - (plan.tops[row] + 410f) >= 0f, "图底在台词行顶之上")
    }
}
