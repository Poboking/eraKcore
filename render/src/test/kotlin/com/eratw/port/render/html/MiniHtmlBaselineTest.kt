package com.eratw.port.render.html

import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 黄金基准 SECTION:HTML 逐条字面期望。
 * 基准：`fixtures/golden/baseline/pc-golden-root.txt:293-303`；
 * 输入字面值：`fixtures/golden/GOLDEN_02_PRINT.ERB:249-262`。
 * 期望值全部取自基准行原文，禁止拿本实现回读当期望。
 */
class MiniHtmlBaselineTest {

    private val m = MiniHtml()

    /** 基准 :294 `HTMLPLAIN`（ERB:250 `HTML_PRINT "<b>HTML</b>PLAIN"`）：两条样式段。 */
    @Test
    fun `基准 HTMLPLAIN 的段结构`() {
        val runs = m.toRuns("<b>HTML</b>PLAIN")
        assertEquals(listOf("HTML", "PLAIN"), runs.map { it.text })
        assertEquals(listOf(Grid.STYLE_BOLD, 0), runs.map { it.styleMask })
        assertTrue(runs.all { it.kind == RunKind.TEXT })
        // 入缓冲即快照：第一段粗体不回改第二段（ConsoleStyledString.cs:21-40）
        assertEquals(runs[0].fore, runs[1].fore)
    }

    /** 基准 :299 `HTML_STRINGLEN=4`（输入 `<i>AB</i>CD`）：plainLength 同口径 = 4。 */
    @Test
    fun `基准 HTML_STRINGLEN 口径`() {
        assertEquals(4, m.plainLength("<i>AB</i>CD"))
        // 第二参非 0 的像素口径 = 36（基准同半角 FontSize/2=9）；本 lane 单位是半角格
        assertEquals(4, Grid.cells("ABCD"))
    }

    /** 基准 :298 `TAGSPLIT_N=4 R1=AB` 与 :300 `HTML_TOPLAINTEXT=ABCD`。 */
    @Test
    fun `基准标签切分与纯文本还原`() {
        val runs = m.toRuns("<i>AB</i>CD")
        assertEquals("ABCD", runs.joinToString("") { it.text })
        assertEquals(Grid.STYLE_ITALIC, runs[0].styleMask)
        assertEquals(0, runs[1].styleMask)
    }

    /** 基准 :301 `HTML_ESCAPE=&lt;a&gt;`（输入 `<a>`，ERB:257）。 */
    @Test
    fun `基准 HTML_ESCAPE 转义集`() {
        assertEquals("&lt;a&gt;", m.escape("<a>"))
        // 五字符全表（HtmlManager.cs:164-166）；' 用 &apos; 不是 &#39;
        assertEquals("&amp;&lt;&gt;&quot;&apos;", m.escape("&<>\"'"))
        assertEquals("plain 原样", m.escape("plain 原样"))
    }

    /** 基准 :302 `HTML_GETPRINTEDSTR=[<p align='left'><nobr>…</nobr></p>]` 的外壳可回解。 */
    @Test
    fun `基准打印行外壳回解`() {
        val html = "<p align='left'><nobr>HTML_STRINGLINES=2</nobr></p>"
        val doc = m.parse(html)
        assertEquals(1, doc.lines.size)
        assertEquals(MiniHtmlAlign.LEFT, doc.align)
        assertTrue(doc.nobr)
        assertEquals("HTML_STRINGLINES=2", doc.lines[0].plainText)
        // left 的列起点恒 0（ConsoleDisplayLine.cs:72-77）
        assertEquals(0, doc.alignStartColumn(0, Grid.DEFAULT_CLIENT_WIDTH))
    }

    /** 基准 :304 `HTML_STRINGLEN` 系列之外，`<b>HTML</b>PLAIN` 的 plainLength=9。 */
    @Test
    fun `基准 HTML_PRINT 的 plainLength`() {
        assertEquals(9, m.plainLength("<b>HTML</b>PLAIN"))
    }
}
