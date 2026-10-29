package com.eratw.port.core.fn

import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FN-11（`<font bcolor>` 的分部背景）+ FN-12（`GETDISPLAYLINE` / `HTML_GETPRINTEDSTR` 回读的
 * img/shape 段给 AltText）。
 *
 * ## 钉的是原版哪几处
 *
 * 1. 分部背景。`StringStyle.ButtonColor` 由 `getStringStyleStartingTag`（HtmlManager.cs:887-891）
 *    以**值比较**写进 `<font ... bcolor='#RRGGBB'>`：只有 `ButtonColor != Config.FocusColor`
 *    （缺省 0xFFFF00，ConfigData.cs:70）才写。ERB 侧能改它的只有 HTML 的 `<font bcolor>`
 *    （`SETCOLOR` 只动前景，EmueraConsole.cs:2823 的 `ColorChanged`），所以非 HTML 部的
 *    背景恒为 FocusColor ⇒ 行体本来就不该给它写 `bcolor`（`styleStartTag` 的现状是对的）。
 *    HTML 部则要记账：[ConsolePrintedLine.htmlParts] 的
 *    [HtmlPrintStyle.buttonColor] 就是该分部的背景（null = 等于 FocusColor）。
 * 2. 图元段的文本回读 = `ConsoleDisplayLine.ToString()`（ConsoleDisplayLine.cs:127-140）
 *    → `ConsoleButtonString.ToString()`（ConsoleButtonString.cs:375-387）→ 各节点
 *    `ToString()`；`ConsoleImagePart.ToString()` / `ConsoleShapePart.ToString()` 都回
 *    `AltText`（ConsoleImagePart.cs:117-124、ConsoleShapePart.cs:82-89），即**重建**出来的
 *    标签文案，不是输入原文：
 *    - img：固定次序 `src srcb srcm height width ypos`，每个 MixedNum 走
 *      `Utils.AddTagMixedNumArg`（`num == 0` 整条不写、非 px 值按 `num * FontSize / 100`
 *      归一，ConsoleImagePart.cs:41-59 + Utils.cs:364-371）；
 *    - shape：`type` 小写、`param` 原值以 `", "` 分隔（px 项补后缀、**不**归一）、
 *      `color` 只在设过时写、`bcolor` 只在 != FocusColor 时写（ConsoleShapePart.cs:12-45）。
 * 3. 行体 html 的原文口径不变：HTML 段在 [ConsolePrintedLine.html] 里保留原文
 *    （HtmlManager.cs:446-464 是原版逐节点重建的等价物，见 `consolePrintedLine` 的 KDoc）。
 *
 * ## 语料可达性
 *
 * `bcolor` 在 ERB 里实测 20 次 / 14 文件（剔注释与口上行后）；`GETDISPLAYLINE` 0 命中
 * ⇒ 本文件全部是**合成**用例。既有黄金对拍（纯文本行）不经过本文件的触发分支：
 * 段里既没有 `<img>` / `<shape>` 也没有 `<font bcolor` 时，回读文本仍走
 * `Html2PlainText`（去标签 + 还原实体，HtmlManager.cs:714-717），逐字与改前相同
 * （最后两条用例钉这一点）。
 *
 * 装置口径：真 [LineBufferConsoleSink] + [consolePrintedLine]，`defaultFontName` 取 sink
 * 的缺省 —— 与本仓生产装配（`ConsoleBuiltinHost.consoleLines`）同一条映射路径，
 * 且 `GETDISPLAYLINE` 返回的正是 [ConsolePrintedLine.text]（HtmlMethods.kt 的注册处）。
 */
class HtmlBcolorAndImageTextTest {

    // ===== FN-11：<font bcolor> 的分部背景 =====

    @Test
    fun `font bcolor 记为分部背景 且行体原文保留背景色`() {
        val line = cbLine("<font bcolor='#112233'>X</font>")

        // 文本不受背景影响。
        assertEquals("X", line.text)
        // 行体 = HTML 原文（bcolor 照旧在里面，回读 html 因此带背景语义）。
        assertEquals("<font bcolor='#112233'>X</font>", line.html)
        // 分部背景：HtmlManager.cs:887-891 的值比较之后剩下的那一份。
        assertEquals(0x112233, line.htmlParts.single().style.buttonColor)
    }

    @Test
    fun `font bcolor 等于 FocusColor 视为没写`() {
        // 缺省 Config.FocusColor = 0xFFFF00（ConfigData.cs:70）；指定成它等价于没写
        // （HtmlManager.cs:887-891 是值比较，不看「有没有写」）。
        val line = cbLine("<font bcolor='#FFFF00'>X</font>")

        assertEquals("X", line.text)
        assertNull(line.htmlParts.single().style.buttonColor)
    }

    @Test
    fun `内层 font 不写 bcolor 时继承外层分部背景`() {
        // HM :1651-1667：新帧把父帧的 color/bcolor/face 抄进自己。
        val line = cbLine("<font bcolor='#EE2C2C'><font color='red'>X</font></font>")

        assertEquals("X", line.text)
        val style = line.htmlParts.single().style
        assertEquals(0xEE2C2C, style.buttonColor)
        assertEquals(0xFF0000, style.fore)
    }

    @Test
    fun `同一行两个 bcolor 帧各记一份`() {
        val line = cbLine("<font bcolor='#112233'>A</font><font bcolor='#445566'>B</font>")

        assertEquals("AB", line.text)
        assertEquals(
            listOf(0x112233, 0x445566),
            line.htmlParts.map { it.style.buttonColor },
        )
    }

    // ===== FN-12：img / shape 的文本回读 =====

    @Test
    fun `img 段回读为重建的 AltText 行体仍是原文`() {
        // 输入用双引号，AltText 固定单引号 + 固定属性次序（ConsoleImagePart.cs:41-59）。
        val line = cbLine("<img src=\"foo\">")

        assertEquals("<img src='foo'>", line.text)
        assertEquals("<img src=\"foo\">", line.html)
        assertEquals(HtmlRunKind.IMAGE, line.htmlParts.single().kind)
    }

    @Test
    fun `img 的 height width 按 FontSize 百分比归一`() {
        // Utils.AddTagMixedNumArg → MixedNum.BuilderString（Utils.cs:87-98）：
        // 非 px 值 = num * FontSize / 100，缺省 FontSize = 18（ConfigData.cs:66）。
        val line = cbLine("<img src='a.png' height='50' width='100'>")

        assertEquals("<img src='a.png' height='9' width='18'>", line.text)
        assertEquals("<img src='a.png' height='50' width='100'>", line.html)
    }

    @Test
    fun `shape 段回读为重建的 AltText param 用逗号加空格分隔`() {
        // ConsoleShapePart.cs:20-31：param 逐项输出原始 num、", " 分隔、不做百分比归一。
        val line = cbLine("<shape type='rect' param='0,30,1,64'>")

        assertEquals("<shape type='rect' param='0, 30, 1, 64'>", line.text)
        assertEquals("<shape type='rect' param='0,30,1,64'>", line.html)
    }

    @Test
    fun `shape 段回读带 color 与 bcolor 但都不改分部背景`() {
        // shape 自己的 color/bcolor 写进它自己的 AltText（ConsoleShapePart.cs:33-44），
        // 不是分部背景（那是 StringStyle.ButtonColor，只有 <font bcolor> 会改）。
        val line = cbLine("<shape type='space' param='10px' color='red' bcolor='#112233'>")

        assertEquals(
            "<shape type='space' param='10px' color='#FF0000' bcolor='#112233'>",
            line.text,
        )
        assertNull(line.htmlParts.single().style.buttonColor)
    }

    @Test
    fun `img 夹在文本中间时按节点序拼接`() {
        val line = cbLine("<b>A</b><img src='x'>C")

        assertEquals("A<img src='x'>C", line.text)
        assertEquals(
            listOf(HtmlRunKind.TEXT, HtmlRunKind.IMAGE, HtmlRunKind.TEXT),
            line.htmlParts.map { it.kind },
        )
        // 行体原文不受影响（标签与文本次序都是输入本身）。
        assertEquals("<b>A</b><img src='x'>C", line.html)
    }

    // ===== 读数不变：没触发标签的行与改前逐字相同 =====

    @Test
    fun `纯文本行文本与行体逐字不变`() {
        val sink = LineBufferConsoleSink()
        sink.printLine("PLAIN")
        val line = consolePrintedLine(sink.lines().single(), sink.defaultFontName)

        assertEquals("PLAIN", line.text)
        assertEquals("PLAIN", line.html)
        assertTrue(line.htmlParts.isEmpty(), "没有 HTML 段就没有分部快照")
    }

    @Test
    fun `无 bcolor 无图元的 HTML 行仍走 Html2PlainText`() {
        val line = cbLine("<b>AB</b>CD")

        assertEquals("ABCD", line.text)
        assertEquals("<b>AB</b>CD", line.html)
        assertTrue(line.htmlParts.isEmpty(), "不触发解析 ⇒ 不记录分部")
    }

    @Test
    fun `实体还原与标签剥离口径不变`() {
        val line = cbLine("A&amp;B<i>C</i>")

        assertEquals("A&BC", line.text)
        assertEquals("A&amp;B<i>C</i>", line.html)
        assertTrue(line.htmlParts.isEmpty())
    }

    @Test
    fun `惰性视图 consolePrintedLines 走同一条映射`() {
        val sink = LineBufferConsoleSink()
        sink.printHtml("<img src='foo'>", option = false)
        val view = consolePrintedLines(sink.lines(), sink.defaultFontName)

        assertEquals("<img src='foo'>", view.single().text)
    }

    // ===== 装置 =====

    /**
     * 打印一行 HTML 并取它的回读模型。
     *
     * `option = false` 是直印路径（EmueraConsole.cs:3217-3225）：sink 按结构切行，
     * 本例没有 `<br>`，故必得一条显示行。`defaultFontName` 用 sink 的缺省 =
     * `Config.FontName` 的出厂值，与本仓生产装配同源。
     */
    private fun cbLine(html: String): ConsolePrintedLine {
        val sink = LineBufferConsoleSink()
        sink.printHtml(html, option = false)
        return consolePrintedLine(sink.lines().single(), sink.defaultFontName)
    }
}
