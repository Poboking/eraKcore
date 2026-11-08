package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HtmlRunFlatten] 的 `pos` 绝对定位消费判据（CONTRACT-POS-WIRE P1/P2）。
 *
 * ## 判据来源（原版逐条）
 *
 * | 口径 | C# 出处 | 本文件用例 |
 * |---|---|---|
 * | `pos='N'` 解析进 `HtmlAnalzeStateButtonTag.PointX/PointXisLocked` | `HtmlManager.cs:1523-1535` | 语料锚那条 |
 * | `cssToButton` 整组 `LockPointX`（value 有无 / clearbutton 无关） | `HtmlManager.cs:848-851` | 按钮组那条 |
 * | `LockPointX`：`PointX = rel_px * FontSize / 100`（行左缘绝对像素） | `ConsoleButtonString.cs:157-163` | 语料锚那条 |
 * | 游标接力 `pointx = PointX + Width`，锁定点在游标前也不夹取 | `PrintStringBuffer.cs:393-411` | 接力 / 回退两条 |
 * | 折行判 `PointX + Width <= DrawableWidth` 里的 PointX = 锁定值 | `PrintStringBuffer.cs:217` | 语料锚那条（宽度合计） |
 * | 空组不建按钮、锁不落地（`list.Count > 0` 守卫） | `HtmlManager.cs:659,:675` | 空组那条 |
 * | pos 要求 nobr + 左对齐（串尾校验，严格路已复刻） | `HtmlManager.cs:683-697` | nobr 行那条 |
 * | `SetAlignment` 平移不豁免锁定按钮 | `ConsoleDisplayLine.cs:62-90`、`ConsoleButtonString.cs:307-315` | （间距段随行内容平移，由既有 align 面覆盖） |
 *
 * ## 语料活命中（CONTRACT-POS-WIRE 背景条）
 *
 * `ERB/カラム機能/カラム機能.ERB:169`：
 * `HTML += @"<nonbutton pos='{COLUMN_GRAPH:LCOUNT:3}'><img src='COLUMN_{LCOUNT}'
 * height='{COLUMN_GRAPH:LCOUNT:2}' ypos='-{COLUMN_GRAPH:LCOUNT:4}'></nonbutton>"`，
 * 外层 `<nobr>`（:143/:171）。`COLUMN_GRAPH:X:3` 来自 `COLUMNMOVE`（:83-85）：
 * 首屏三列 pos = 0 / 2050 / 0 —— **同行内非单调**，后组的锁定点落在当前游标之前
 * （原版直接叠画）。移植侧用带号 [InlineGraphic.Space] 间隔段表达：正 gap 空白推进、
 * 负 gap 列游标回退（XRAY 同款机制，`DisplayList.prepare` 对负 space 只退游标不产命令）。
 *
 * 夹具字号 18px ⇒ 半角步进 `18 / 2 * 1.04 = 9.36px`；pos 目标格 = `ceil(18 * N / 100 / 9.36)`
 * （`LockPointX` 的字号百分比 + `InlineGraphic.cellsFor`，与 img 尺寸同一把尺）。
 * N=100 → 2 格、N=300 → 6 格、N=2050 → 40 格（px=369）。
 */
class HtmlRunFlattenPosTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> =
        HtmlRunFlatten(metrics).flatten(htmlRun(html))

    private fun htmlRun(html: String, htmlOption: Boolean = false) =
        TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML, htmlOption = htmlOption)

    private fun textRun(text: String) =
        TextRun(text, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.TEXT)

    /** 一批 run 的占宽和（与 `DisplayList.prepare` 的列游标累加同式）。 */
    private fun widthOf(runs: List<TextRun>): Int =
        runs.sumOf { it.graphic?.widthCells ?: Grid.cells(it.text) }

    // ==================================================================
    // 语料活命中锚（カラム機能.ERB:169）
    // ==================================================================

    /**
     * 语料锚：`<nonbutton pos='2050'><img ...></nonbutton>`（カラム機能.ERB:169，
     * `COLUMNMOVE(1, 2050, 1000)` 的 :3 = 2050、:2 = 1000）。锁定点 = 2050 * 18 / 100
     * = 369px = 40 格（ceil(369 / 9.36)）；img 无 width ⇒ 见方 180px = 20 格。
     * 间隔段在 img 之前、不带按钮身份与提示；行宽合计含间隔段（折行判 :217 的 PointX 项）。
     */
    @Test
    fun `语料锚 nonbutton pos 的 img 前插绝对定位间隔段`() {
        val runs = flatten("<nobr><nonbutton pos='2050'><img src='COLUMN_1' height='1000' ypos='-1000'></nonbutton></nobr>")
        assertEquals(2, runs.size)
        val spacer = runs[0]
        val g = spacer.graphic
        assertTrue(g is InlineGraphic.Space, "间隔段该是 Space 图形段")
        assertEquals(40, g.widthCells, "ceil(2050 * 18 / 100 / 9.36) = ceil(369 / 9.36) = 40")
        assertEquals(" ".repeat(40), spacer.text, "正间隔段的文本负载按占宽铺空格（emitGraphicRun 同约定）")
        assertEquals(RunKind.SHAPE, spacer.kind)
        assertNull(spacer.buttonValue, "gap 不是节点，不进按钮命中")
        assertEquals(-1, spacer.buttonId)
        assertNull(spacer.tooltip)
        val img = runs[1].graphic as InlineGraphic.Image
        assertEquals("COLUMN_1", img.resource)
        assertEquals(180, img.heightPx, "height='1000' → 18 * 1000 / 100")
        assertEquals(20, img.widthCells, "缺 width 按见方 180px 占格")
        // 折行判的网格同构：行宽合计 = 锁定点 40 + 组宽 20（PrintStringBuffer.cs:217 的
        // PointX + Width，PointX 项由间隔段承载）。
        assertEquals(60, widthOf(runs))
    }

    /** 语料行外层的 `<nobr>` 落 noBreak（直印行，HM :698 把 state.FlagNobr 交给折行判）。 */
    @Test
    fun `语料行的 nobr 落直印行 noBreak 且间隔段仍在`() {
        val line = HtmlRunFlatten(metrics).flattenLine(
            RenderLine(
                runs = listOf(htmlRun("<nobr><nonbutton pos='100'>A</nonbutton></nobr>")),
                isLogicalLine = true, isLineEnd = true,
            ),
        )
        assertTrue(line.noBreak, "nobr 行不折行（:217 的 nobr 支）")
        assertEquals(2, line.runs.size, "间隔段 + A")
        assertEquals(2, line.runs[0].graphic?.widthCells)
    }

    // ==================================================================
    // 游标接力（PrintStringBuffer.cs:393-411）
    // ==================================================================

    /**
     * 接力：pos 组之后的内容从「锁定落点 + 组宽」继续（`pointx = PointX + Width`），
     * 不重置回锁定点、也不重置回 0。
     */
    @Test
    fun `pos 组后的内容从落点加组宽继续`() {
        val runs = flatten("<nonbutton pos='100' title='t'>A</nonbutton>B")
        assertEquals(3, runs.size)
        assertEquals(2, runs[0].graphic?.widthCells, "N=100 → 目标 2 格")
        assertEquals("A", runs[1].text)
        assertEquals("t", runs[1].tooltip, "tooltip 落在组内容上")
        assertNull(runs[0].tooltip, "间隔段不带提示")
        assertEquals("B", runs[2].text)
        // 游标：间隔 2 + A(1) = 3，B 从 3 继续（= PointX(2) + Width(1)）。
        assertEquals(3, widthOf(runs.subList(0, 2)))
        assertEquals(4, widthOf(runs))
    }

    /** 非单调：后组的锁定点在游标之前 ⇒ 负宽间隔段回退（原版不夹取、直接叠画）。 */
    @Test
    fun `锁定点在游标之前时负宽间隔段回退`() {
        // 语料首屏的列位形态（カラム機能.ERB:145-146 COLUMNMOVE(0,0,1000)/(1,2050,1000)
        // 与 :169 的拼接次序）：pos=300 的列在前、pos=0 的列在后。
        val runs = flatten(
            "<nonbutton pos='300'><img src='a' width='100'></nonbutton>" +
                "<nonbutton pos='0'><img src='b' width='100'></nonbutton>",
        )
        assertEquals(4, runs.size)
        assertEquals(6, runs[0].graphic?.widthCells, "N=300 → 6 格")
        assertEquals(2, runs[1].graphic?.widthCells, "width='100' → 18px → 2 格")
        val back = runs[2].graphic
        assertTrue(back is InlineGraphic.Space)
        assertEquals(-8, back.widthCells, "游标 8 → 锁定点 0：gap = -8")
        assertEquals("", runs[2].text, "负间隔段无文本负载（负宽无法用空格表达）")
        assertEquals(2, runs[3].graphic?.widthCells)
        assertEquals(2, widthOf(runs), "回退后两组图叠在 0..2 与 6..8，行末游标 = 2")
    }

    /** `pos='0'` 即回到行首（原版 PointX = 0，PrintStringBuffer.cs:404-405 不夹取）。 */
    @Test
    fun `pos 零值把后续内容拉回行首`() {
        val runs = flatten("ab<nonbutton pos='0'>x</nonbutton>")
        // "ab"(2) → pos='0' 目标 0 → gap = -2 → 回退；x 落在列 0（与 "ab" 叠）。
        assertEquals(3, runs.size)
        assertEquals("ab", runs[0].text)
        assertEquals(-2, runs[1].graphic?.widthCells)
        assertEquals("x", runs[2].text)
    }

    /** 负 pos：锁定点在行左缘之外（`LockPointX` 的整数除截断与 C# 同形）。 */
    @Test
    fun `负 pos 落负列由左界裁`() {
        val runs = flatten("<nonbutton pos='-100'>x</nonbutton>")
        assertEquals(2, runs.size)
        assertEquals(-2, runs[0].graphic?.widthCells, "-100 * 18 / 100 = -18px → -2 格")
        assertEquals("x", runs[1].text)
    }

    // ==================================================================
    // 组边界与身份
    // ==================================================================

    /** 空组不锁：原版 `list.Count > 0` 守卫不建按钮（HM :659,:675），间隔段也不该出现。 */
    @Test
    fun `空 pos 组不插间隔段`() {
        val runs = flatten("<nonbutton pos='100'></nonbutton>x")
        assertEquals(1, runs.size)
        assertEquals("x", runs[0].text)
        assertNull(runs[0].graphic)
    }

    /**
     * `<button pos>` 的命中区从锁定点起、不含间隔段（gap 不是 `ConsoleButtonString` 的节点，
     * HM :848-851 只锁按钮组自身的 PointX）。
     */
    @Test
    fun `按钮组的命中身份不落在间隔段上`() {
        val runs = flatten("<button pos='100' value='7'>OK</button>")
        assertEquals(2, runs.size)
        assertEquals(2, runs[0].graphic?.widthCells)
        assertNull(runs[0].buttonValue, "间隔段无按钮值 ⇒ ButtonLayer 不在 gap 建命中区")
        assertEquals(-1, runs[0].buttonId)
        assertEquals(7L, runs[1].buttonValue, "OK 段带值")
        assertEquals("OK", runs[1].text)
    }

    /** pos 与 value 有无无关：无 value 的 `<button pos>`（原版 IsButton=false）照样落位。 */
    @Test
    fun `无 value 的 button pos 照样落位`() {
        val runs = flatten("<button pos='100'>hi</button>")
        assertEquals(2, runs.size)
        assertEquals(2, runs[0].graphic?.widthCells)
        assertEquals("hi", runs[1].text)
        assertNull(runs[1].buttonValue, "原版 IsButton = text7 != null（HM :1547），无 value 不可点")
    }

    /** `pos` 解析不出（原版 CodeEE，HM :1529-1531）时宽容层不锁不抛。 */
    @Test
    fun `pos 值畸形时不锁不抛`() {
        val runs = flatten("<nonbutton pos='12px'>x</nonbutton>")
        assertEquals(1, runs.size)
        assertEquals("x", runs[0].text)
        assertNull(runs[0].graphic)
    }

    // ==================================================================
    // 混排行与 div 协同
    // ==================================================================

    /**
     * toPrintBuffer 混排行：HTML 段之前的内容计入行级游标种子 —— 原版 pos 锁定的是
     * **整条缓冲行**的绝对点位（锁定值随 `ConsoleButtonString` 进打印缓冲、随普通 Flush
     * 一起排游标，PrintStringBuffer.cs:393-411 不区分段来源）。
     */
    @Test
    fun `混排行的 pos 从行首起算`() {
        val runs = HtmlRunFlatten(metrics).flattenAll(
            listOf(textRun("abcd"), htmlRun("<nonbutton pos='300'>x</nonbutton>", htmlOption = true)),
        )
        assertEquals(3, runs.size)
        assertEquals("abcd", runs[0].text)
        assertEquals(2, runs[1].graphic?.widthCells, "游标 4 → 目标 6：gap = 2")
        assertEquals("x", runs[2].text)
    }

    /**
     * div 剔块协同：pos 组整个落在被剔除的 div 区间内时，**主行不出现间隔段**——
     * 剔除发生在适配层源文本上（`ConsoleAdapter.stripDivSegments`，区间含 div 两端标签），
     * flatten 根本见不到这个 pos 标签。原版对应物：div 子行是递归子解析的独立显示行
     * （HM :636-642，自己的 buttonList 从 0 排游标），pos 相对**子行左缘**落位。
     */
    @Test
    fun `pos 组在 div 剔除区间内时主行无间隔段`() {
        val html = "<div><nonbutton pos='100'>x</nonbutton></div>tail"
        val line = LineBufferConsoleSink.DisplayLine(
            isLineEnd = true,
            segments = listOf(
                LineBufferConsoleSink.Segment(html, foreColor = 0xC0C0C0, styleMask = 0, fontName = "msgothic", kind = LineBufferConsoleSink.Kind.HTML),
            ),
            divBlocks = listOf(
                divBlock(sourceRange = 0 until html.indexOf("</div>") + 6),
            ),
        )
        val renderLine = ConsoleAdapter.adapt(line)
        val flattened = HtmlRunFlatten(metrics).flattenLine(renderLine)
        val texts = flattened.runs.map { it.text }
        assertTrue("tail" in texts.joinToString(""), "剔除后剩 tail：${texts}")
        assertTrue(flattened.runs.none { it.graphic is InlineGraphic.Space }, "被剔除的 pos 不得在主行留间隔段")
    }

    /**
     * div **子行**里的 pos 相对子行左缘落位（原版递归子解析 HM :636-642 的 buttonList
     * 从 0 起排游标；`SetAlignment` 平移不豁免锁定按钮，ConsoleDisplayLine.cs:62-90，
     * 所以子行的对齐偏移照常作用在间隔段上——它随子行内容走）。
     */
    @Test
    fun `div 子行内的 pos 相对子行左缘落位`() {
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics),
            "<nonbutton pos='100'>A</nonbutton>",
            subDivisionWidthPx = 0,
            metrics = metrics,
        )
        val cmds = rows.single().cmds
        assertEquals(2, cmds.size, "间隔段命令 + 文本命令")
        assertEquals(0, cmds[0].colStart, "间隔段从子行列 0 起")
        assertEquals(2, cmds[0].colEnd)
        assertEquals(2, cmds[1].colStart, "A 落在子行列 2（= 锁定点）")
    }

    // ------------------------------------------------------------------

    /** 最小 [com.eratw.port.core.host.DisplayDivBlock]（几何字段与本测试无关，填 0/空表）。 */
    private fun divBlock(sourceRange: IntRange) =
        com.eratw.port.core.host.DisplayDivBlock(
            altHeadTag = "<div>",
            anchorCol = 0,
            anchorPointXPx = 0,
            xOffsetPx = 0,
            divXOffsetPx = 0,
            pointYPx = 0,
            yOffsetPx = 0,
            widthPx = 0,
            heightPx = 0,
            subDivisionWidthPx = 0,
            depth = 0,
            backgroundColor = null,
            margin = emptyList(),
            padding = emptyList(),
            border = emptyList(),
            radius = emptyList(),
            borderColors = emptyList(),
            isRelative = true,
            isEscaped = false,
            children = emptyList(),
            sourceRange = sourceRange,
            sourceSegment = 0,
        )
}
