package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HTML 段**文本回读**的两个配置项注入面（`Config.FontSize` / `Config.FocusColor`）。
 *
 * ## 钉的是哪条语义
 *
 * `GETDISPLAYLINE`（`ConsoleDisplayLine.ToString()`，ConsoleDisplayLine.cs:127-140）与
 * `HTML_GETPRINTEDSTR` 的行体都要**重建** `<img>` / `<shape>` 的 AltText，而重建文案里
 * 有两个数不是常量：
 * - `<img>` 的 height/width/ypos 若是百分比形态（不带 `px`），按
 *   `num * Config.FontSize / 100` 归一（`Utils.AddTagMixedNumArg`，Utils.cs:364-371 →
 *   `MixedNum.BuilderString` :87-98；落在 `ConsoleImagePart.cs:45-59`）；
 * - `<font bcolor>`（HtmlManager.cs:887-891）与 `<shape bcolor>`
 *   （ConsoleShapePart.cs:39-44）都是**值比较**：`!= Config.FocusColor` 才写进回读串。
 *
 * 两者缺省 = 出厂配置（`ConfigData.cs:66` 字号 18、`:70` 焦点色 0xFFFF00）。**改动前**这两处
 * 走的是 `htmlPrintDocument` 的出厂缺省常量，玩家把 `Config.FontSize` 或
 * `HIGHLIGHT COLOR` 改成非出厂值后，回读出的像素数字/颜色与屏上分叉 —— 本文件第 1 组用例
 * 是「默认参数零行为」的回归钉，第 2/3 组是「注入后随配置变」的正向钉，第 4 组跑生产装配
 * 路径（[ConsoleBuiltinHost] → `printedLinesOf` → [consolePrintedLines]）。
 *
 * ## 装置口径
 *
 * 前 3 组直接对 [consolePrintedLine] 注入参数（与 `HtmlBcolorAndImageTextTest` 同一装置形态）；
 * 第 4 组用真 [LineBufferConsoleSink] + [ConsoleBuiltinHost] + `GETDISPLAYLINE`，即
 * `GameSession` 的生产装配形状。所有期望值都是**具体数字/颜色**，不写「与默认不同」这种弱断言。
 */
class HtmlReadbackConfigTest {

    // ===== 1) 默认参数：与改动前逐字节一致（回归钉） =====

    /**
     * 不传 `fontSize` / `focusColor` 时，回读串仍是**出厂口径**的读数 —— 与显式传
     * `18 / 0xFFFF00` 逐字节相同（同一函数的两个入口只是参数化，不许各自算一套）。
     *
     * 期望值出处：`50 * 18 / 100 = 9`、`100 * 18 / 100 = 18`、`25 * 18 / 100 = 4`
     * （Long/C# int 整除，Utils.cs:87-98）。
     */
    @Test
    fun `默认参数的回读串等于显式出厂值_且为改动前读数`() {
        val html = "<img src='a.png' height='50' width='100' ypos='25'>"
        val sink = LineBufferConsoleSink()
        sink.printHtml(html, option = false)
        val line = sink.lines().single()

        // 入口 A：全部走缺省（未接线调用点 / 改动前形态）。
        val implicit = consolePrintedLine(line, sink.defaultFontName)
        // 入口 B：显式传出厂值（同一语义的显式写法）。
        val explicit = consolePrintedLine(line, sink.defaultFontName, 0L, null, 18L, 0xFFFF00)

        assertEquals("<img src='a.png' height='9' width='18' ypos='4'>", implicit.text)
        assertEquals(implicit.text, explicit.text, "缺省必须就是出厂值，不是另一套折算")
        assertEquals(implicit.html, explicit.html)
        assertEquals(implicit.align, explicit.align)
        // 行体仍是原文（配置只改回读文案，不动行体）。
        assertEquals(html, implicit.html)
    }

    /** 惰性视图 [consolePrintedLines] 的缺省与显式出厂值同样逐字节一致。 */
    @Test
    fun `consolePrintedLines 缺省与显式出厂值一致`() {
        val sink = LineBufferConsoleSink()
        sink.printHtml("<font bcolor='#FFFF00'>X</font>", option = false)

        val implicit = consolePrintedLines(sink.lines(), sink.defaultFontName).single()
        val explicit = consolePrintedLines(sink.lines(), sink.defaultFontName, 0L, null, 18L, 0xFFFF00)
            .single()

        assertEquals("X", implicit.text)
        // 值等于出厂 FocusColor ⇒ 省略（HtmlManager.cs:887-891 的值比较）。
        assertNull(implicit.htmlParts.single().style.buttonColor)
        assertEquals(implicit.text, explicit.text)
        assertNull(explicit.htmlParts.single().style.buttonColor)
    }

    // ===== 2) fontSize 注入：img 百分比折算随配置变 =====

    /**
     * `Config.FontSize` = 36 时同一份 HTML 的 AltText 数字翻倍：
     * `50 * 36 / 100 = 18`、`100 * 36 / 100 = 36`、`25 * 36 / 100 = 9`。
     */
    @Test
    fun `注入 fontSize 36 时 img 的 height width ypos 按新字号归一`() {
        val html = "<img src='a.png' height='50' width='100' ypos='25'>"

        assertEquals(
            "<img src='a.png' height='9' width='18' ypos='4'>",
            readback(html, fontSize = 18L),
            "出厂 18 的基准读数（与第 1 组同值，作为对照）",
        )
        assertEquals(
            "<img src='a.png' height='18' width='36' ypos='9'>",
            readback(html, fontSize = 36L),
        )
    }

    /**
     * px 形态**不参与**百分比折算（原版 `MixedNum.BuilderString` 对 px 原样输出）：
     * 注入 36 也不能把 `height='50px'` 改成别的数。
     */
    @Test
    fun `注入 fontSize 不影响 px 形态的属性`() {
        val html = "<img src='a.png' height='50px' width='100px'>"

        assertEquals(html, readback(html, fontSize = 36L))
        assertEquals(html, readback(html, fontSize = 18L))
    }

    // ===== 3) focusColor 注入：bcolor 省略判据随配置变 =====

    /**
     * `<font bcolor='#FFFF00'>`：出厂焦点色 0xFFFF00 时视为没写（分部背景 null）；
     * 玩家把 `HIGHLIGHT COLOR` 改成别的值后，同一枚 `bcolor` 就必须落进分部背景。
     */
    @Test
    fun `注入非出厂 focusColor 后 font bcolor 不再视为出厂值`() {
        val html = "<font bcolor='#FFFF00'>X</font>"

        assertNull(fontBcolor(readbackLine(html, fontSize = 18L)), "等于出厂焦点色 ⇒ 省略")
        assertEquals(
            0xFFFF00,
            fontBcolor(readbackLine(html, focusColor = 0x00FF00)),
            "焦点色改成 00FF00 后，#FFFF00 不再等于它 ⇒ 记录",
        )
    }

    /** 判据是**值比较**：`bcolor` 等于注入的焦点色时仍然省略（不是「非出厂就一律写」）。 */
    @Test
    fun `bcolor 与注入的焦点色同值时仍省略`() {
        val html = "<font bcolor='#112233'>X</font>"

        assertEquals(0x112233, fontBcolor(readbackLine(html, focusColor = 0xFFFF00)))
        assertNull(
            fontBcolor(readbackLine(html, focusColor = 0x112233)),
            "注入的焦点色 = #112233 ⇒ 值相等 ⇒ 省略",
        )
    }

    /**
     * `<shape bcolor>` 走同一判据，且直接体现在**回读串**上（ConsoleShapePart.cs:39-44）：
     * 出厂焦点色下不写 `bcolor`，注入非出厂焦点色后写出来。
     */
    @Test
    fun `shape 回读串的 bcolor 属性随注入的 focusColor 出现或省略`() {
        val html = "<shape type='space' param='10px' bcolor='#FFFF00'>"

        assertEquals(
            "<shape type='space' param='10px'>",
            readback(html, focusColor = 0xFFFF00),
        )
        assertEquals(
            "<shape type='space' param='10px' bcolor='#FFFF00'>",
            readback(html, focusColor = 0x00FF00),
        )
    }

    // ===== 4) 生产装配路径：ConsoleBuiltinHost 注入会话生效值 =====

    /**
     * sink 喂会话生效值（`scriptFontSize` = 生效 `Config.FontSize`，`focusColor` = 生效
     * `Config.FocusColor`，两者都是 `GameSession` 的装配参数）时，`GETDISPLAYLINE`
     * 必须回读到随配置变的数字与颜色 —— 这正是 lane A 留下的开放 TODO 的验收面。
     */
    @Test
    fun `ConsoleBuiltinHost 回读链随 sink 的会话字号与焦点色`() = withTempRoot("eratw-html-rb-cfg") { root ->
        val imgSink = LineBufferConsoleSink(clientWidth = 199, scriptFontSize = 36, focusColor = 0x00FF00)
        imgSink.printHtml("<img src='a.png' height='50'>", option = false)
        val imgHost: BuiltinHost = ConsoleBuiltinHost(root, imgSink)
        assertEquals(
            "<img src='a.png' height='18'>",
            wireStr(imgHost, "GETDISPLAYLINE", num(0)),
            "字号 36 ⇒ 50 * 36 / 100 = 18（出厂 18 时是 9）",
        )

        val shapeSink = LineBufferConsoleSink(clientWidth = 199, focusColor = 0x00FF00)
        shapeSink.printHtml("<shape type='space' param='10px' bcolor='#FFFF00'>", option = false)
        val shapeHost: BuiltinHost = ConsoleBuiltinHost(root, shapeSink)
        assertEquals(
            "<shape type='space' param='10px' bcolor='#FFFF00'>",
            wireStr(shapeHost, "GETDISPLAYLINE", num(0)),
            "焦点色 00FF00 ⇒ #FFFF00 不再等于它 ⇒ 回读串带 bcolor",
        )
    }

    /**
     * 生产路径的**零行为回归钉**：sink 不喂配置（`scriptFontSize = 0` 即未接线）时，
     * 回读链回落本宿主的 `Config.FontSize`（缺省 18）与出厂焦点色，读数与改动前逐字一致。
     *
     * 外壳形状照 `HtmlPrintedStrRoundTripTest`：`<p align='…'><nobr>行体</nobr></p>`
     * （HtmlManager.cs:388-403、:446-464）。注意两条读数**故意不同源**：
     * - `GETDISPLAYLINE` = `ConsoleDisplayLine.ToString()`，图元给**重建的 AltText**
     *   （百分比属性按 FontSize 归一）⇒ 这里是 `height='9'`；
     * - `HTML_GETPRINTEDSTR` 的行体是本仓 sink 保留的**原文**（HtmlMethods.kt 的
     *   `consolePrintedLine` 对 HTML 段 `html.append(rowBody)`）⇒ 属性仍是 `height='50'`。
     *   两个配置项都**不**改行体原文，这条同时钉住「注入不污染行体」。
     */
    @Test
    fun `sink 未接配置时 ConsoleBuiltinHost 仍按出厂 18 与 0xFFFF00 回读`() =
        withTempRoot("eratw-html-rb-default") { root ->
            val sink = LineBufferConsoleSink(clientWidth = 199)
            sink.printHtml("<img src='a.png' height='50' ypos='25'>", option = false)
            val host: BuiltinHost = ConsoleBuiltinHost(root, sink)

            assertEquals("<img src='a.png' height='9' ypos='4'>", wireStr(host, "GETDISPLAYLINE", num(0)))
            // 外壳 + 行体原文：`HTML_GETPRINTEDSTR` 的一次完整读数（改动前逐字节相同）。
            assertEquals(
                "<p align='left'><nobr><img src='a.png' height='50' ypos='25'></nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )

            val shapeSink = LineBufferConsoleSink(clientWidth = 199)
            shapeSink.printHtml("<shape type='space' param='10px' bcolor='#FFFF00'>", option = false)
            val shapeHost: BuiltinHost = ConsoleBuiltinHost(root, shapeSink)
            assertEquals(
                "<shape type='space' param='10px'>",
                wireStr(shapeHost, "GETDISPLAYLINE", num(0)),
                "出厂焦点色 ⇒ bcolor 省略（与改动前同读数）",
            )
        }

    /**
     * sink 没有字号、但宿主自己被喂了会话字号（`ConsoleBuiltinHost(fontSize = 36)`，
     * 例如自定义 sink 的装配点）：回读文案走宿主那一份，而列坐标仍按 sink 的 0（不计格）。
     */
    @Test
    fun `sink 无字号时回落宿主的 Config FontSize`() = withTempRoot("eratw-html-rb-hostfont") { root ->
        val sink = LineBufferConsoleSink(clientWidth = 199)
        sink.printHtml("<img src='a.png' height='50'>", option = false)
        val host: BuiltinHost = ConsoleBuiltinHost(root, sink, fontSize = 36L)

        assertEquals(
            "<img src='a.png' height='18'>",
            wireStr(host, "GETDISPLAYLINE", num(0)),
            "50 * 36 / 100 = 18：注入的是宿主字号，不是出厂 18",
        )
    }

    // ===== 装置 =====

    /** 打印一行 HTML 并取回读模型（`option = false` = 直印路径，EmueraConsole.cs:3217-3225）。 */
    private fun readbackLine(
        html: String,
        fontSize: Long = 18L,
        focusColor: Int = 0xFFFF00,
    ): ConsolePrintedLine {
        val sink = LineBufferConsoleSink()
        sink.printHtml(html, option = false)
        return consolePrintedLine(sink.lines().single(), sink.defaultFontName, 0L, null, fontSize, focusColor)
    }

    private fun readback(
        html: String,
        fontSize: Long = 18L,
        focusColor: Int = 0xFFFF00,
    ): String = readbackLine(html, fontSize, focusColor).text

    private fun fontBcolor(line: ConsolePrintedLine): Int? = line.htmlParts.single().style.buttonColor

    private fun <T> withTempRoot(prefix: String, body: (Path) -> T): T {
        val root = Files.createTempDirectory(prefix)
        try {
            return body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 只承接 [BuiltinEvalContext] 的变量回调；内建函数求值不经过它。 */
    private class RbBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("RbBaseCtx 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    private fun wireStr(host: BuiltinHost, name: String, vararg args: Value): String {
        val store = VariableStore()
        val base = RbBaseCtx()
        base.variables = store
        val v = BuiltinEvalContext(base, store, host).callFunction(name, args.toList())
        return (v as Value.StrV).v
    }

    private fun num(n: Long): Value = Value.IntV(n)
}
