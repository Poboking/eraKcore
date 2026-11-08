package com.eratw.port.render.adapter

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RenderLine
import com.eratw.port.render.TextRun
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.view.ConsoleFrame
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * div 覆盖绘制**消费端接线**（CONTRACT-DIV-WIRE W1/W2/W5）—— HTMLDIV-B1 落了模型与
 * `DivOverlayPlanner` 之后，本波把「谁来消费」钉住：
 *
 * - `ConsoleAdapter.adapt` 按 [DisplayDivBlock.sourceRange] 把块区间从段原文剔除
 *   （停用过渡期内联；原版块零宽，`ConsoleDivPart.cs:217-219` 空 SetWidth）；
 * - `ConsoleAdapter.fold` 把块按锚列落到锚排（折点边界归前一排，PrintStringBuffer.cs:217
 *   的零宽节点判式）、PRINTN 并入两行块拼接（核心层并入行同口径）；
 * - `ConsoleScreen.divOverlayScan` + `DivOverlayPlanner.planFrame` 出一帧的两侧清单
 *   （窗口外 margin 行的高块照扫，ConsoleEscapedParts.cs:127-129 的行区间过滤）；
 * - `ScreenComposer.composeInto` 按 behind → 正文 → front 的全局层序消费
 *   （EmueraConsole.cs:1829-1857 的 depth 0 正文槽），子行落内容盒
 *   （ConsoleDivPart.cs:196-203）。
 *
 * headless 装置：字号 18 / 行高 19（`CellMetrics()`），脚本 px 恒等（scriptFont = 0）。
 */
class DivOverlayWireTest {

    private val metrics = CellMetrics()

    private fun block(
        anchorCol: Int = 0,
        anchorPointXPx: Int = anchorCol * 9, // 核心层口径：锚列 × FontSize/2（等宽近似）
        xOffsetPx: Int = 0,
        widthPx: Int = 100,
        heightPx: Int = 50,
        pointY: Int = 0,
        depth: Int = 1,
        escaped: Boolean = true,
        relative: Boolean = true,
        bg: Int? = 0x191970,
        children: List<String> = emptyList(),
        sourceRange: IntRange = IntRange.EMPTY,
        sourceSegment: Int = 0,
    ) = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = anchorCol,
        anchorPointXPx = anchorPointXPx,
        xOffsetPx = xOffsetPx,
        divXOffsetPx = 0,
        pointYPx = pointY,
        yOffsetPx = 0,
        widthPx = widthPx,
        heightPx = heightPx,
        subDivisionWidthPx = 0,
        depth = depth,
        backgroundColor = bg,
        margin = emptyList(),
        padding = emptyList(),
        border = emptyList(),
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = relative,
        isEscaped = escaped,
        children = children.map { DisplayDivChildLine(it) },
        sourceRange = sourceRange,
        sourceSegment = sourceSegment,
    )

    private fun htmlLine(
        text: String,
        divBlocks: List<DisplayDivBlock> = emptyList(),
    ) = LineBufferConsoleSink.DisplayLine(
        isLineEnd = true,
        isLogicalLine = true,
        segments = listOf(
            LineBufferConsoleSink.Segment(text, 0xC0C0C0, 0, "黑体", LineBufferConsoleSink.Kind.HTML, false),
        ),
        divBlocks = divBlocks,
    )

    // ------------------------------------------------------------------
    // W1a：adapt 剔除块区间（停用过渡期内联）
    // ------------------------------------------------------------------

    @Test
    fun `adapt 按块区间剔除正文 块全量透传`() {
        val head = "<div width='100px' height='50px' color='#191970'>"
        val tail = "尾文"
        val seg = head + "块子行" + "</div>" + tail
        val range = 0 until (head + "块子行" + "</div>").length
        val rendered = ConsoleAdapter.adapt(
            htmlLine(seg, listOf(block(sourceRange = range, children = listOf("块子行")))),
        )
        // 区间整段切掉（含两端标签）：正文只剩块后文本 —— 原版块零宽，块后列不后移。
        assertEquals(listOf(tail), rendered.runs.map { it.text })
        assertEquals(1, rendered.divBlocks.size)
        assertEquals("块子行", rendered.divBlocks[0].children.single().html)
    }

    @Test
    fun `区间不可考的块不剔除原文`() {
        val seg = "<div width='100px' height='50px'>块子行</div>尾文"
        val rendered = ConsoleAdapter.adapt(htmlLine(seg, listOf(block())))
        // sourceRange.isEmpty()（解析层没给）：无法圈区间 ⇒ 保持过渡期原文（登记偏差）。
        assertEquals(listOf(seg), rendered.runs.map { it.text })
    }

    @Test
    fun `不带块的行 adapt 输出不变`() {
        val seg = "<b>粗</b>体"
        val rendered = ConsoleAdapter.adapt(htmlLine(seg))
        assertEquals(listOf(seg), rendered.runs.map { it.text })
    }

    // ------------------------------------------------------------------
    // W1b：fold 落排 + PRINTN 并入拼接
    // ------------------------------------------------------------------

    @Test
    fun `fold 把块落到锚列所在的排 折点边界归前一排`() {
        val folder = com.eratw.port.render.grid.LineFolder(10)
        // 12 格文本在 10 格宽下折成 10 + 2 两排。
        val line = RenderLine(
            runs = listOf(TextRun("aaaaabbbbbcc", 0xC0C0C0, null, 0, "黑体")),
            isLogicalLine = true,
            isLineEnd = true,
            divBlocks = listOf(block(anchorCol = 5), block(anchorCol = 10), block(anchorCol = 11)),
        )
        val rows = ConsoleAdapter.fold(folder, listOf(line))
        assertEquals(2, rows.size)
        // 锚列 10 = 恰在折点上（第一排 10 格的末尾）：零宽节点 `PointX + Width <= num`
        // 在 PointX == num 成立，节点留在当前（前一）排（PrintStringBuffer.cs:217）。
        // 锚列 11 落在第二排内。
        assertEquals(listOf(5, 10), rows[0].divBlocks.map { it.anchorCol })
        assertEquals(listOf(11), rows[1].divBlocks.map { it.anchorCol })
    }

    @Test
    fun `PRINTN 并入把两行的块按序拼接`() {
        val buffer = LogBuffer.fromConfig(100)
        val prev = DisplayRow(
            runs = listOf(TextRun("a", 0xC0C0C0, null, 0, "黑体")),
            cells = 1,
            isLogicalLine = true,
            isLineEnd = false,
            divBlocks = listOf(block(anchorCol = 0)),
        )
        val next = DisplayRow(
            runs = listOf(TextRun("b", 0xC0C0C0, null, 0, "黑体")),
            cells = 1,
            isLogicalLine = false,
            isLineEnd = true,
            divBlocks = listOf(block(anchorCol = 1)),
        )
        val landed = ConsoleAdapter.appendMerged(buffer, listOf(prev, next))
        assertEquals(1, landed.size)
        assertEquals(listOf(0, 1), landed[0].divBlocks.map { it.anchorCol })
    }

    // ------------------------------------------------------------------
    // W1c：divOverlayScan + planFrame（一帧的两侧清单）
    // ------------------------------------------------------------------

    @Test
    fun `planFrame 合并各行的块 未逃逸不进 两侧 depth 降序`() {
        val planner = DivOverlayPlanner(metrics, 0, HtmlRunFlatten(metrics))
        val rows = listOf(
            DivOverlayRow(listOf(block(depth = 1, pointY = 19)), anchorTopPx = 380, lineNo = 20),
            DivOverlayRow(listOf(block(depth = -1, pointY = 19)), anchorTopPx = 399, lineNo = 21),
            // 未逃逸块没有绘制面（ConsoleButtonString.cs:361-364）：planFrame 也不进清单。
            DivOverlayRow(listOf(block(depth = 2, escaped = false)), anchorTopPx = 418, lineNo = 22),
        )
        val frame = planner.planFrame(rows, canvasHeightPx = 760, visibleTopLine = 0, visibleBottomLine = 40)
        assertEquals(listOf(1), frame.behind.map { it.depth })
        assertEquals(listOf(-1), frame.front.map { it.depth })
        assertTrue(frame.behind[0].isRelative)
    }

    @Test
    fun `planFrame 窗口外锚行的相对块按登记区间过滤 absolute 恒进`() {
        val planner = DivOverlayPlanner(metrics, 0, HtmlRunFlatten(metrics))
        // 锚行 lineNo=5（窗口 20..40 之外的上沿 margin），块 top=0..bottom=50 →
        // 登记区间 = ceil(0/19)+5 .. floor(49/19)+5 = 5..7，与 20..40 不相交 ⇒
        // 相对块被过滤（ConsoleEscapedParts.cs:127-129）；absolute 块恒画（:77 的 type==3）。
        val frame2 = planner.planFrame(
            listOf(DivOverlayRow(listOf(block(relative = true)), anchorTopPx = -100, lineNo = 5)),
            canvasHeightPx = 760,
            visibleTopLine = 20,
            visibleBottomLine = 40,
        )
        assertTrue(frame2.isEmpty)
        val frame3 = planner.planFrame(
            listOf(DivOverlayRow(listOf(block(relative = false)), anchorTopPx = -100, lineNo = 5)),
            canvasHeightPx = 760,
            visibleTopLine = 20,
            visibleBottomLine = 40,
        )
        assertFalse(frame3.isEmpty)
        // absolute 锚画布底（ConsoleDivPart.cs:178）：y = 画布高 - PointY - Height。
        assertEquals(760 - 0 - 50, frame3.behind.single().borderRectPx.y)
    }

    @Test
    fun `divOverlayScan 只挑携带块的行 margin 行也在场`() {
        val screen = ConsoleScreen(40, 100, 20)
        val lines = listOf(
            htmlLine("第一行"),
            htmlLine("第二行", listOf(block())),
            htmlLine("第三行"),
        )
        screen.submitSnapshot(ConsoleAdapter.adapt(lines))
        // margin 0：窗口内 3 行里只有第 2 行带块。
        val scan0 = screen.divOverlayScan(0)
        assertEquals(listOf(1), scan0.map { it.retainedIndex })
        // margin 2：窗口外一行的块行同样进扫描（窗口只有 3 行，行号 0..2 全在窗口内，
        // 这里钉的是「margin 扩的是扫描窗而不是结果过滤」）。
        val scan2 = screen.divOverlayScan(2)
        assertEquals(listOf(1), scan2.map { it.retainedIndex })
        assertEquals(1, scan2[0].lineNo)
    }

    // ------------------------------------------------------------------
    // W1d/W5：composeInto 消费（像素级：块底色在正文下、前块压正文、子行落内容盒）
    // ------------------------------------------------------------------

    private fun wireLines(): List<LineBufferConsoleSink.DisplayLine> {
        val head = "<div width='100px' height='50px' color='#191970'>"
        val range = 0 until (head + "块子行" + "</div>").length
        return listOf(
            // 行 1：behind 块（depth 1，底色藏青、右移 200px 避开正文列）+ 前块（depth -1，
            // pointY=19 压到行 2 的正文上、红底）。
            LineBufferConsoleSink.DisplayLine(
                isLineEnd = true,
                isLogicalLine = true,
                segments = listOf(
                    LineBufferConsoleSink.Segment(
                        head + "块子行" + "</div>" + "正文",
                        0xC0C0C0, 0, "黑体", LineBufferConsoleSink.Kind.HTML, false,
                    ),
                ),
                divBlocks = listOf(
                    block(xOffsetPx = 200, depth = 1, children = listOf("块子行"), sourceRange = range),
                    block(depth = -1, pointY = 19, bg = 0xFF0000),
                ),
            ),
            LineBufferConsoleSink.DisplayLine(
                isLineEnd = true,
                isLogicalLine = true,
                segments = listOf(
                    LineBufferConsoleSink.Segment("第二行", 0xC0C0C0, 0, "黑体", LineBufferConsoleSink.Kind.TEXT, false),
                ),
            ),
        )
    }

    private fun wireScreen(): Triple<ConsoleScreen, ScreenComposer, Int> {
        val cw = 40
        val heightLines = 20
        val canvasHeightPx = heightLines * metrics.lineHeight
        val screen = ConsoleScreen(cw, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(wireLines()))
        val composer = ScreenComposer(cw, heightLines, metrics, canvasHeightPx = canvasHeightPx)
        return Triple(screen, composer, canvasHeightPx)
    }

    private fun planOverlay(screen: ConsoleScreen, composer: ScreenComposer, canvasHeightPx: Int): DivOverlayFrame {
        val rowCount = screen.visibleRows().size
        val base = screen.visibleRowBase
        val firstIndex = screen.log.firstIndex
        val scan = screen.divOverlayScan(canvasHeightPx / metrics.lineHeight + 1)
        assertEquals(listOf(0), scan.map { it.retainedIndex })
        val rows = scan.map {
            DivOverlayRow(it.blocks, composer.rowTopPx(it.retainedIndex - base, rowCount), it.lineNo)
        }
        return composer.divPlanner.planFrame(
            rows = rows,
            canvasHeightPx = canvasHeightPx,
            visibleTopLine = firstIndex + base,
            visibleBottomLine = firstIndex + base + rowCount - 1,
        )
    }

    @Test
    fun `合成 正文剔除子行 块底色垫在正文下 前块压在正文上`() {
        val (screen, composer, canvasHeightPx) = wireScreen()
        // 过渡期已停：正文快照不再含块区间的子行文本（块零宽，锚行只剩块后文本）。
        assertFalse(screen.snapshotText().any { it.contains("块子行") })
        val frame = planOverlay(screen, composer, canvasHeightPx)
        // behind（depth 1）与 front（depth -1）各一颗。
        assertEquals(listOf(1), frame.behind.map { it.depth })
        assertEquals(listOf(-1), frame.front.map { it.depth })
        val rowCount = screen.visibleRows().size
        val raster = composer.composeInto(
            target = Raster(composer.canvasWidthPx, composer.canvasHeightPx),
            commands = screen.list.render(),
            rowCount = rowCount,
            overlay = frame,
        )
        val rowTop0 = composer.rowTopPx(0, rowCount)
        val rowTop1 = composer.rowTopPx(1, rowCount)
        fun px(x: Int, y: Int): Int = raster.pixels[y * raster.width + x]
        fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)
        // 1) behind 块底色（x=200..302，避开子行文本 200..254 与正文 0..36）铺在正文之下：
        //    该点不在任何字形上 ⇒ 块底色原色。
        assertEquals(opaque(0x191970), px(280, rowTop0 + 5))
        // 2) 子行「块子行」落内容盒（盒顶 = borderRect 顶，无 margin/border/padding 收缩）：
        //    首字形像素 = 子行前景色（字形块占位画法）。
        assertEquals(opaque(0xC0C0C0), px(210, rowTop0 + 9))
        // 3) 前块（depth -1）压在行 2 正文上：行 2 首字形的像素 = 前块底色。
        assertEquals(opaque(0xFF0000), px(4, rowTop1 + 9))
        // 4) depth 0 槽仍在：行 1 正文画在 behind 块之上（前块从行 2 顶开始，盖不到它）。
        assertEquals(opaque(0xC0C0C0), px(4, rowTop0 + 9))
    }

    @Test
    fun `ConsoleFrame 覆盖块在场与退场都走整屏`() {
        val (screen, composer, canvasHeightPx) = wireScreen()
        val frame = ConsoleFrame(composer)
        val rects = screen.list.dirtyStrips()
        frame.draw(screen, rects)
        assertTrue(frame.lastWasFull)
        // 幂等提交（无脏区）也整屏：块在场，按行擦背景会把块擦出洞。
        val rects2 = screen.submitSnapshot(ConsoleAdapter.adapt(wireLines()))
        assertFalse(rects2.isNotEmpty())
        frame.draw(screen, rects2)
        assertTrue(frame.lastWasFull)
        // 退场帧（同一批两行、只是块的锚行换成普通行）：行窗口不变、行签名同值
        // ⇒ 唯一的整屏依据就是「上一帧有块」，退场帧必须仍整屏一次清掉旧块像素。
        val plain = listOf(
            htmlLine("第一行"),
            LineBufferConsoleSink.DisplayLine(
                isLineEnd = true,
                isLogicalLine = true,
                segments = listOf(
                    LineBufferConsoleSink.Segment("第二行", 0xC0C0C0, 0, "黑体", LineBufferConsoleSink.Kind.TEXT, false),
                ),
            ),
        )
        val rects3 = screen.submitSnapshot(ConsoleAdapter.adapt(plain))
        frame.draw(screen, rects3)
        assertTrue(frame.lastWasFull)
        // 之后的稳态帧回到按行（无块、无脏区 ⇒ 稳态早退）。
        frame.draw(screen, emptyList())
        assertFalse(frame.lastWasFull)
        assertEquals(canvasHeightPx, composer.canvasHeightPx)
    }
}
