package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「同一行到这儿占多宽」只允许**一把尺**的对抗用例集。
 *
 * 来源：2026-11-06 的自审复核（三路只读审计的反例）——折行预算
 * （[CellWidth.visibleHtmlCells]）、行内游标（`LineBufferConsoleSink` 的 div 锚点/按钮列位）
 * 与渲染侧（`render` 的 `LineFolder`/`HtmlRunFlatten`）必须对下列形态给出同一个数：
 * TAB、未闭合 `<`、越界数字实体、负尺寸 `<img>`、`<div>` 块内文本、纯文本段里的 `<`/`&`。
 */
class HtmlWidthRulerTest {

    private fun card(extra: String = "") =
        "<div xpos='0px' ypos='0px' width='100px' height='20px'$extra>台词</div>"

    private fun blockOf(sink: LineBufferConsoleSink): DisplayDivBlock =
        sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()

    // ---- TAB：原版量宽前替 8 空格（StringMeasure.cs:59-62） ----

    @Test
    fun `TAB 按 8 个半角格计宽`() {
        assertEquals(10, CellWidth.displayCells("A\tB"), "A(1) + TAB(8) + B(1)")
        assertEquals(3, CellWidth.of("A\tB"), "字节数口径不动：of 仍是 CP932 单字节 = 1")
        assertEquals(10, CellWidth.visibleHtmlCells("A\tB"), "折行预算同尺")
    }

    /**
     * 增补平面：**显示宽度 4 格、字节数口径 2 格**。
     *
     * 渲染侧 `glyph/CellMetrics.cellsOf` 对增补码位按「高/低代理各一个码元、各 2 格」
     * 建模 = 4 格，`GlyphCache` / `DisplayList` / `ButtonLayer` 的推进与命中列都读它 ⇒
     * 显示宽度必须同值（否则布局比落笔窄一半）；`CellWidth.of` 的按码位 2 格是字节数口径。
     */
    @Test
    fun `增补平面显示宽度四格 字节口径两格`() {
        val emoji = String(Character.toChars(0x1F600))
        assertEquals(4, CellWidth.displayCells(emoji), "与渲染 CellMetrics.cellsOf 同值")
        assertEquals(2, CellWidth.of(emoji), "字节数口径：表外字按全角兜底 = 2")
        // HTML 文本路径同样按显示宽度
        assertEquals(4, CellWidth.visibleHtmlCells(emoji))
        assertEquals(5, CellWidth.visibleHtmlCells("A" + emoji), "A + emoji = 1 + 4")
    }

    @Test
    fun `TAB 在 div 锚点游标里同样是 8 格`() {
        val sink = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
        sink.printHtml("<nobr>A\tB" + card() + "</nobr>", false)
        val b = blockOf(sink)

        assertEquals(10, b.anchorCol, "旧实现 3 格（TAB=1）⇒ 与渲染侧展开 8 空格错位")
        assertEquals(90, b.anchorPointXPx, "10 格 × 18/2")
    }

    // ---- 未闭合 `<`：原版正则不吞（HtmlManager.cs:714-717） ----

    @Test
    fun `未闭合的尖括号按文本计宽`() {
        assertEquals(3, CellWidth.visibleHtmlCells("A<B"), "旧实现当标签一路吃到串尾 ⇒ 1")
        assertEquals(3, CellWidth.visibleHtmlCells("A<B", graphicFontPx = 18), "图形尺同样按文本")
    }

    // ---- 越界数字实体：原版只认 0..65535（HtmlManager.cs:817-831） ----

    @Test
    fun `越界数字实体按字面量计宽`() {
        // "&#x1F600;" 共 9 个半角字符：原版 unescape 认不出（超 0xFFFF）⇒ 字面量留在文本里。
        assertEquals(9, CellWidth.visibleHtmlCells("&#x1F600;"), "旧实现按增补平面算 2 格")
        assertEquals(1, CellWidth.visibleHtmlCells("&#x41;"), "合法实体仍解成 A")
        assertEquals(1, CellWidth.visibleHtmlCells("&amp;"), "命名实体仍解成 &")
    }

    // ---- 负尺寸 `<img>`：原版翻正后 Width 为绝对值（ConsoleImagePart.cs:83-87） ----

    @Test
    fun `负宽 img 的占宽取绝对值 与渲染一致`() {
        val sink = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        sink.printHtml(
            "<nobr><img src='a' width='-180px' height='180px'>" + card() + "</nobr>",
            false,
        )
        val b = blockOf(sink)

        assertEquals(180, b.anchorPointXPx, "负号是镜像标记（flipX），不是往回退")
        assertEquals(20, b.anchorCol, "格投影同绝对值")
        assertEquals(20, CellWidth.visibleHtmlCells("<img src='a' width='-180px' height='180px'>", 18))
    }

    // ---- `<div>` 块内文本不进父行宽（CDP:217-219 零宽节点） ----

    @Test
    fun `div 块内文本不计入父行宽`() {
        val html = card() + card()
        assertEquals(0, CellWidth.visibleHtmlCells(html, graphicFontPx = 18), "两张卡的台词都不算父行宽")
        assertEquals(0, CellWidth.visibleHtmlCells(html), "文本尺同理")
    }

    @Test
    fun `div 之后的按钮列位不被块内文本推后`() {
        val sink = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        sink.printHtml("<nobr>" + card() + "<button value='1'>继续</button></nobr>", false)
        val line = sink.snapshot().single { it.htmlOptions.isNotEmpty() }

        assertEquals(0, line.htmlOptions.single().startCol, "div 零宽 ⇒ 按钮从 0 列起")
    }

    // ---- 纯文本段里的 `<` / `&` 不能被当标签/实体（跨 span 锚点基址） ----

    @Test
    fun `跨 span 锚点基址按段量 不拼接重解析`() {
        val sink = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        // option=true 的 HTML 段与前面的纯文本段**同处一条 pending 行**（不 flush），
        // 这才是「跨 span 基址」的真实形态。纯文本段里的 `<B` 不是标签：5 个半角字符
        // ⇒ 45px（旧式拼接重解析会把 `<B>C` 当标签只算出 2 格）。
        sink.print("A<B>C", false)
        sink.printHtml("<nobr>" + card() + "</nobr>", true)
        sink.newLine()

        assertEquals(5, CellWidth.displayCells("A<B>C"), "行宽/格数口径不变")
        assertEquals(45, blockOf(sink).anchorPointXPx, "基址 = 5 格 × 9")
    }

    @Test
    fun `纯文本段里的实体按原文宽计`() {
        val sink = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        sink.print("&amp;", false) // 纯文本入参：已还原形态，5 个半角字符
        sink.printHtml("<nobr>" + card() + "</nobr>", true)
        sink.newLine()

        assertEquals(45, blockOf(sink).anchorPointXPx, "5 字符 × 9 = 45（被当实体只算 1 格就是旧 bug）")
    }
}
