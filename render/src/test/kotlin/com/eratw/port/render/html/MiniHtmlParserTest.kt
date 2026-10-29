package com.eratw.port.render.html

import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 解析语义回归：样式位叠加与交叉闭合、`nobr` 禁折行标记、align→列、
 * 断行、实体转义、`img` 口径、原版错误分支照抄（每条断言带 HtmlManager.cs 行号）。
 */
class MiniHtmlParserTest {

    private val m = MiniHtml()

    private fun expect(kind: MiniHtmlErrorKind, html: String) {
        val e = assertFailsWith(MiniHtmlException::class, "应抛 $kind：$html") { m.parse(html) }
        assertEquals(kind, e.kind, "错误类别不符：$html")
    }

    // ---------------------------------------------------------------- 样式位

    @Test
    fun `样式位叠加与嵌套`() {
        val runs = m.toRuns("<b><i>A</i>B</b>C")
        assertEquals(listOf("A", "B", "C"), runs.map { it.text })
        assertEquals(Grid.STYLE_BOLD or Grid.STYLE_ITALIC, runs[0].styleMask)
        assertEquals(Grid.STYLE_BOLD, runs[1].styleMask)
        assertEquals(0, runs[2].styleMask)
    }

    /** 官方行为：样式是异或位、不是标签栈，交叉闭合合法（HtmlManager.cs:977-983）。 */
    @Test
    fun `交叉闭合不报错`() {
        val runs = m.toRuns("<b><i>A</b></i>B")
        assertEquals(Grid.STYLE_BOLD or Grid.STYLE_ITALIC, runs[0].styleMask)
        assertEquals("B", runs[1].text)
        assertEquals(0, runs[1].styleMask)
    }

    /** `s`/`u` 位（Kernel 口径 4=删除线 8=下划线，Process.cs:1508-1530）。 */
    @Test
    fun `删除线与下划线位`() {
        assertEquals(Grid.STYLE_STRIKE, m.toRuns("<s>A</s>")[0].styleMask)
        assertEquals(Grid.STYLE_UNDERLINE, m.toRuns("<u>A</u>")[0].styleMask)
        assertEquals(Grid.STYLE_STRIKE or Grid.STYLE_UNDERLINE, m.toRuns("<s><u>A</u></s>")[0].styleMask)
    }

    /** 重复开样式抛 DuplicateTag（:1075-1078）；闭标签不校验开标签名（:977-981）。 */
    @Test
    fun `样式错误分支`() {
        expect(MiniHtmlErrorKind.DUPLICATE_TAG, "<b>A<b>")
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "<i>A</b>")
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "</b>A")
        expect(MiniHtmlErrorKind.TAG_NOT_CLOSED, "<b>A")
        expect(MiniHtmlErrorKind.TAG_NOT_CLOSED, "<font color='red'>A")
    }

    /** 标签名大小写不敏感（:966、:1059 ToLower）。 */
    @Test
    fun `标签名大小写`() {
        assertEquals(Grid.STYLE_BOLD, m.toRuns("<B>A</B>")[0].styleMask)
    }

    // ---------------------------------------------------------------- 字体与颜色

    @Test
    fun `font 颜色与字体名快照`() {
        val runs = m.toRuns("<font color='#FF0000'>A</font>B")
        assertEquals(0xFF0000, runs[0].fore)
        assertEquals(MiniHtml.FORE_DEFAULT, runs[1].fore)
        assertEquals(MiniHtml.BACK_DEFAULT, runs[0].back)
    }

    /** 嵌套 font 缺省属性继承外层（:1651-1667）。 */
    @Test
    fun `font 嵌套继承`() {
        val runs = m.toRuns("<font color='#FF0000'>A<font face='F'>B</font>C</font>D")
        assertEquals(4, runs.size)
        assertEquals(0xFF0000, runs[1].fore)
        assertEquals("F", runs[1].fontName)
        assertEquals(MiniHtml.FONT_DEFAULT, runs[2].fontName)
        assertEquals(MiniHtml.FORE_DEFAULT, runs[3].fore)
    }

    /**
     * 命名色（语料实测六名 + 大小写不敏感）取自 core 层唯一权威表 `ColorNames`
     * ——见 MiniHtmlColorNamesTest 的逐名等长等值断言，这里只留最小抽样。
     * 不断 `silver`（0xC0C0C0 与 [MiniHtml.FORE_DEFAULT] 同值，断言无区分度）。
     */
    @Test
    fun `颜色名表`() {
        assertEquals(0x808080, m.toRuns("<font color='gray'>A</font>")[0].fore)
        assertEquals(0x00FF00, m.toRuns("<font color='lime'>A</font>")[0].fore)
        assertEquals(0x00FFFF, m.toRuns("<font color='aqua'>A</font>")[0].fore)
        assertEquals(0xFFD700, m.toRuns("<font color='gold'>A</font>")[0].fore)
        assertEquals(0xFFA500, m.toRuns("<font color='orange'>A</font>")[0].fore)
        assertEquals(0xFFFF00, m.toRuns("<font color='yellow'>A</font>")[0].fore)
        // 框架的真名别名（各自独立的 KnownColor 成员）与大小写不敏感、同名同值
        assertEquals(0x00FFFF, m.toRuns("<font color='cyan'>A</font>")[0].fore)
        assertEquals(0xFF00FF, m.toRuns("<font color='Fuchsia'>A</font>")[0].fore)
        assertEquals(0x000000, m.toRuns("<font color='BLACK'>A</font>")[0].fore)
        // 收口前的旧表自带 `grey`，但框架 `Color.FromName('grey').A == 0`，
        // 原版即按未知名抛 InvalidColorName（HtmlManager.cs:1705-1706、:1718）→ 口径照原样复刻。
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='grey'>A</font>")
    }

    /** 颜色错误分支（:1680-1725），含裸十六进制「能解析仍拒绝」的官方怪癖（:1712-1720）。 */
    @Test
    fun `颜色错误分支`() {
        expect(MiniHtmlErrorKind.COLOR_REQUIRED, "<font color=''>A</font>")
        // 官方结构：越界分支在裸 catch 内，OoRColorValue 被转吞为 CanNotInterpretNumValue
        // （HtmlManager.cs:1690-1701），照抄——COLOR_OUT_OF_RANGE 是原版死代码
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#1000000'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#GG'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_TRANSPARENT_UNSUPPORTED, "<font color='transparent'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='FFFF00'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='bogus'>A</font>")
    }

    @Test
    fun `font 标签错误分支`() {
        expect(MiniHtmlErrorKind.FONT_WITHOUT_ATTRIBUTE, "<font>A</font>")
        expect(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, "<font color='red' color='blue'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_ATTRIBUTE, "<font size='12'>A</font>")
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "</font>A")
    }

    // ---------------------------------------------------------------- p 与 nobr

    @Test
    fun `align 落列`() {
        // ConsoleDisplayLine.cs:79-84：CENTER = w/2 - cells/2；RIGHT = w - cells（整数除）
        val center = m.parse("<p align='center'>AB</p>")
        assertEquals(MiniHtmlAlign.CENTER, center.align)
        assertEquals(4, center.alignColumn(center.lines[0].cells, 10))
        val right = m.parse("<p align='right'>あA</p>")
        assertEquals(3, right.lines[0].cells)
        assertEquals(7, right.alignColumn(right.lines[0].cells, 10))
        // 行宽超可视区：原版照画溢出、不钳制（:82-90 只做位移）
        assertEquals(-2, right.alignColumn(right.lines[0].cells, 1))
    }

    /** nobr 禁折行标记上抛给上层（:698 把 FlagNobr 交给 ButtonsToDisplayLines）。 */
    @Test
    fun `nobr 标记`() {
        val doc = m.parse("<nobr>AB</nobr>")
        assertTrue(doc.nobr)
        assertFalse(doc.lines[0].segments.isEmpty())
        assertFalse(m.parse("AB").nobr)
    }

    @Test
    fun `p 与 nobr 错误分支`() {
        // nobr 之后开 p：DuplicateTag（:1113-1116，外壳顺序必须 p 先 nobr 后）
        expect(MiniHtmlErrorKind.DUPLICATE_TAG, "<nobr><p align='center'>A</nobr></p>")
        // 原版开 p 只查重 nobr（:1113-1116），连开两个 p 合法；连闭两个才抛
        assertEquals(MiniHtmlAlign.LEFT, m.parse("<p align='left'><p align='left'>A</p>").align)
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "<p align='left'>A</p></p>")
        expect(MiniHtmlErrorKind.P_WITHOUT_ATTRIBUTE, "<p>A</p>")
        expect(MiniHtmlErrorKind.UNKNOWN_P_ATTRIBUTE, "<p class='x'>A</p>")
        expect(MiniHtmlErrorKind.UNKNOWN_ALIGN_VALUE, "<p align='middle'>A</p>")
        expect(MiniHtmlErrorKind.TAG_ERROR, "<p align='left' x='y'>A</p>")
        // LineHead：只被文本置 false，标签不重置（:94、:574）
        expect(MiniHtmlErrorKind.TAG_NOT_AT_LINE_HEAD, "A<nobr>")
        expect(MiniHtmlErrorKind.TAG_NOT_AT_LINE_HEAD, "A<p align='left'>B</p>")
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "</p>A")
        expect(MiniHtmlErrorKind.TEXT_AFTER_P, "<p align='left'>A</p>B")
        expect(MiniHtmlErrorKind.TEXT_AFTER_NOBR, "<nobr>A</nobr>B")
        expect(MiniHtmlErrorKind.DUPLICATE_TAG, "<b><b>A</b></b>")
    }

    /** 原版不要求闭合 p/nobr（:667-674 只查 div/button/样式/字体栈），照抄。 */
    @Test
    fun `p 与 nobr 可以不闭合`() {
        assertEquals("A", m.parse("<p align='right'>A").lines[0].plainText)
        assertEquals(MiniHtmlAlign.RIGHT, m.parse("<p align='right'>A").align)
        assertTrue(m.parse("<nobr>A").nobr)
    }

    // ---------------------------------------------------------------- 断行与空行

    /** br 与裸换行同权（:588-592）；串尾空行按原版丢弃（PrintStringBuffer.cs:258-261）。 */
    @Test
    fun `断行`() {
        val doc = m.parse("<b>A</b><br>C")
        assertEquals(2, doc.lines.size)
        assertEquals("A", doc.lines[0].plainText)
        assertEquals("C", doc.lines[1].plainText)
        assertEquals(1, m.parse("AB<br>").lines.size)
        assertEquals(1, m.parse("<br>").lines.size)
        assertTrue(m.parse("<br>").lines[0].segments.isEmpty())
        assertEquals(2, m.parse("X\nY").lines.size)
        // 连发两个 br：中间落一条空行（null 分隔符逐条成行，PrintStringBuffer.cs:207-215）
        val twoBr = m.parse("X<br><br>Y")
        assertEquals(listOf("X", "", "Y"), twoBr.lines.map { it.plainText })
        assertEquals(0, m.parse("").lines.size)
        assertEquals(0, m.plainLength(""))
    }

    /** toRuns 展平：行间是 `"\n"` 的 HTML 哨兵段（MiniHtmlDoc.BR_MARK）。 */
    @Test
    fun `toRuns 换行哨兵`() {
        val runs = m.toRuns("A<br>B")
        assertEquals(listOf("A", MiniHtmlDoc.BR_MARK, "B"), runs.map { it.text })
        assertEquals(RunKind.HTML, runs[1].kind)
        assertEquals(RunKind.TEXT, runs[0].kind)
    }

    // ---------------------------------------------------------------- 实体与注释

    /** `&nbsp;` 还原成半角空格是官方行为（:783-785）。 */
    @Test
    fun `实体还原与口径`() {
        assertEquals("<a>", m.unescape("&lt;a&gt;"))
        assertEquals("<&", m.unescape("&LT;&Amp;"))
        assertEquals("A", m.unescape("&#65;"))
        assertEquals("A", m.unescape("&#x41;"))
        assertEquals(" ", m.unescape("&nbsp;"))
        assertEquals(1, m.plainLength("&nbsp;"))
        assertEquals("A&B", m.parse("A&amp;B").lines[0].plainText)
        expect(MiniHtmlErrorKind.ENTITY_MISSING_SEMICOLON, "A&B")
        expect(MiniHtmlErrorKind.ENTITY_AMP_SEMI, "A&;B")
        expect(MiniHtmlErrorKind.ENTITY_INVALID_REF, "&bogus;")
        expect(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, "&#70000;")
        // 实体还原出的 `<` 不是标签起点：plainLength 里它按 1 格计
        assertEquals(1, m.plainLength("&lt;"))
    }

    /** 注释只在整串含 `<!--` 时才检查（:534、:577-586）。 */
    @Test
    fun `注释跳过`() {
        assertEquals("12", m.parse("<b>1<!-- hid -->2</b>").lines[0].plainText)
        expect(MiniHtmlErrorKind.COMMENT_UNTERMINATED, "A<!-- x")
        // 不含 `<!--` 的串里，`<--` 不是注释：落到未知标签
        expect(MiniHtmlErrorKind.TAG_ERROR, "A<--x-->B")
    }

    // ---------------------------------------------------------------- 标签与结构错误

    @Test
    fun `标签结构错误`() {
        expect(MiniHtmlErrorKind.TAG_ERROR, "<>A</>")
        expect(MiniHtmlErrorKind.TAG_ERROR, "<xyz>A</xyz>")
        expect(MiniHtmlErrorKind.UNKNOWN_CLOSE_TAG, "</xyz>")
        expect(MiniHtmlErrorKind.TAG_UNTERMINATED, "<b>A<i")
        expect(MiniHtmlErrorKind.TAG_UNTERMINATED, "<b>A</b")
        // 属性语法必须是 名字='字面量'（原版 break 直落 :1677）
        expect(MiniHtmlErrorKind.TAG_ERROR, "<p align=left>A</p>")
        expect(MiniHtmlErrorKind.TAG_ERROR, "<font color='red>A</font>")
        // 子集之外的原版已知标签
        expect(MiniHtmlErrorKind.UNSUPPORTED_TAG, "<button value='1'>A</button>")
        expect(MiniHtmlErrorKind.UNSUPPORTED_TAG, "<shape type='space' param='10'>")
    }

    /** 样式标签不许带属性（:1071-1074、:1082-1092）。 */
    @Test
    fun `样式标签属性错误`() {
        expect(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, "<b x='1'>A</b>")
        expect(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, "<br x='1'>")
        expect(MiniHtmlErrorKind.ATTRIBUTE_ON_STYLE_TAG, "<nobr x='1'>A</nobr>")
    }

    // ---------------------------------------------------------------- img

    /** img 占位段：文本恒空、kind HTML；plainLength 口径记 0（与 core 一致）。 */
    @Test
    fun `img 占位与口径`() {
        val doc = m.parse("A<img src='a.png' width='12px' height='30'>B")
        val line = doc.lines[0]
        assertEquals(3, line.segments.size)
        val img = line.segments[1] as HtmlSegment.Image
        assertEquals("a.png", img.src)
        assertEquals(12, img.width)
        assertTrue(img.widthIsPx)
        assertEquals(30, img.height)
        assertEquals(2, m.plainLength("A<img src='a.png'>B"))
        val runs = m.toRuns("A<img src='a.png'>B")
        assertEquals(RunKind.HTML, runs[1].kind)
        assertEquals("", runs[1].text)
    }

    @Test
    fun `img 错误分支`() {
        expect(MiniHtmlErrorKind.IMG_WITHOUT_ATTRIBUTE, "<img>")
        expect(MiniHtmlErrorKind.IMG_SRC_MISSING, "<img width='10'>A</img>")
        expect(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, "<img src='a' src='b'>A")
        expect(MiniHtmlErrorKind.UNKNOWN_ATTRIBUTE, "<img src='a' foo='1'>A")
        expect(MiniHtmlErrorKind.ATTRIBUTE_NOT_NUMBER, "<img src='a' width='big'>A")
    }

    // ---------------------------------------------------------------- 半角全角与复用

    @Test
    fun `全半角格口径`() {
        assertEquals(3, m.plainLength("<b>あA</b>"))
        assertEquals(3, m.plainLength("<nobr>あA</nobr>"))
        // TAB 先展开成 8 空格再度量（StringMeasure.cs:57-60 经 Grid.cells）
        assertEquals(8, m.plainLength("\t"))
    }

    /** 实例无跨调用状态（禁静态可变全局）；不同默认色的实例互不串味。 */
    @Test
    fun `实例隔离`() {
        val white = MiniHtml(defaultFore = 0xFFFFFF)
        assertEquals(0xFFFFFF, white.toRuns("A")[0].fore)
        assertEquals(MiniHtml.FORE_DEFAULT, m.toRuns("A")[0].fore)
        assertEquals(4, m.plainLength("<i>AB</i>CD"))
    }
}
