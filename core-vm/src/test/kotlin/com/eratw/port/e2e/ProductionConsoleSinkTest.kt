package com.eratw.port.e2e

import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONSOLE-2 残项的落地判据：E2E 的宿主控制台 = 生产 sink
 * （[LineBufferConsoleSink] + [CellWidth] 那套行模型），经 [ProductionConsoleSink]
 * 门面读回装置要的五样东西。本类只测**门面映射与生产实现被接到的那几条口径**，
 * 不重复 `GoldenTextParityTest`（那条测的是「同一调用序喂两 sink 文本相同」）。
 *
 * 每条断言都钉在基准文本的某一行上（`fixtures/golden/baseline/pc-golden-root.txt`，
 * 下称基准），期望值来自 C# 行号或基准，不是实现回读。
 */
class ProductionConsoleSinkTest {

    /** 装置用的控制台（PC 环境三值：折行 199 格 / PrintCLength 25 / 字体黑体，见 [GoldenPipeline.newConsole]）。 */
    private fun console() = GoldenPipeline.newConsole()

    /** 门面背后的生产 sink（断言「状态只有一份」时要直接读它）。 */
    private fun sinkOf(c: GoldenConsoleSink): LineBufferConsoleSink = (c as ProductionConsoleSink).sink

    // ------------------------------------------------------------------
    // 1. HTML 段的行文本：日志与显示行都要「可见文本」
    // ------------------------------------------------------------------

    /**
     * 基准 :294 `HTMLPLAIN` ← GOLDEN_02_PRINT.ERB:250 的 `HTML_PRINT "<b>HTML</b>PLAIN"`。
     *
     * 生产 sink 的 Kind.HTML 段存**原文**（供 `:render` 排版），所以门面必须过
     * [LineBufferConsoleSink.htmlVisibleText]（去标签 + 还原实体，规则 = Html2PlainText，
     * HtmlManager.cs:714-717 与 Unescape :744-834）。不剥标签就会在这一行撞红。
     */
    @Test
    fun htmlPrintLineTextIsVisibleTextNotRawTags() {
        val c = console()
        c.printHtml("<b>HTML</b>PLAIN", option = false)
        assertEquals(listOf("HTMLPLAIN"), c.loggedLines(), "日志文本里不该有标签（基准 :294）")

        val lines = c.displayLines()
        assertEquals(1, lines.size)
        assertEquals("HTMLPLAIN", lines[0].text, "GETDISPLAYLINE 取的是 ConsoleDisplayLine.ToString()（基准 :306）")
        // 行体 html 是「可见文本再过 Escape 表」：无特殊字符时与 text 相同
        // （外壳 <p align>/<nobr> 由 fn 侧 displayLine2Html 负责，基准 :301）
        assertEquals("HTMLPLAIN", lines[0].html)
    }

    /** 实体还原：`&amp;` 在日志文本里是 `&`，再被 html 侧转义回 `&amp;`（同一张表，不会二次转义）。 */
    @Test
    fun htmlEntitiesAreUnescapedInTextAndReescapedInHtml() {
        val c = console()
        c.printHtml("<i>A&amp;B</i>", option = false)
        assertEquals(listOf("A&B"), c.loggedLines(), "Unescape 口径（HtmlManager.cs:744-834）")
        assertEquals("A&amp;B", c.displayLines()[0].html, "行体 html = 可见文本过 Escape 表（:372-378）")
    }

    // ------------------------------------------------------------------
    // 2. 样式快照：读生产 sink 的状态，门面不自存一份
    // ------------------------------------------------------------------

    /**
     * GETCOLOR 家族的三项环境缺省色 + 字体名。
     *
     * 基准取证：:266 `BYNAME=16711680 DEF=12632256 FOCUS=16776960`（DEF = 192,192,192、
     * FOCUS = 255,255,0 = ConfigData.cs:68/:70 的缺省），:280 `FONT_DEFAULT=黑体`
     * （= 游戏 emuera.config:21，不是 ConfigData.cs:65 的出厂缺省 ＭＳ ゴシック）。
     */
    @Test
    fun styleSnapshotUsesProductionStateAndPcEnvironmentDefaults() {
        val c = console()
        val s = c.styleSnapshot()
        assertEquals(null, s.fore, "未 SETCOLOR 时是 null（RESETCOLOR 同口径），由 fn 侧回落 defFore")
        assertEquals(listOf(192, 192, 192), s.defFore.toList(), "Config.ForeColor（ConfigData.cs:68）")
        assertEquals(listOf(0, 0, 0), s.defBack.toList(), "Config.BackColor（ConfigData.cs:69）")
        assertEquals(listOf(255, 255, 0), s.focus.toList(), "Config.FocusColor = FromArgb(255,255,0)（ConfigData.cs:70，基准 :266）")
        assertEquals("黑体", s.fontName, "宿主环境字体名（基准 :280）")
        assertEquals(0, s.alignment, "CURRENTALIGN 的编号：0=LEFT（DisplayLineAlignment.cs:5-7）")
        assertEquals(0, s.redraw, "ConsoleRedraw.None（ConsoleRedraw.cs:8-9）")

        // 状态只有一份：样式调用落到生产 sink，快照读回来的也是它，门面不留影子
        c.setForeColor(1, 2, 3)
        assertEquals(0x010203, sinkOf(c).foregroundColor, "写入侧 = 生产 sink 自己的字段")
        assertEquals(listOf(1, 2, 3), c.styleSnapshot().fore?.toList(), "读出侧 = 同一份状态的拆解")
        c.setFontStyle(3)
        assertEquals(3, sinkOf(c).fontStyle)
        assertEquals(3, c.styleSnapshot().fontStyleMask, "GETSTYLE（FunctionMethodCreator.cs:3699-3730）")

        // REDRAW 2 → 0、REDRAW 1 → 1（bit0 折减，EmueraConsole.cs:1694-1703；基准 :287-288 两行 REDRAW=0/1）
        c.setRedraw(2)
        assertEquals(0, c.styleSnapshot().redraw, "REDRAW 2：bit1 只是立即重绘，不改状态")
        c.setRedraw(1)
        assertEquals(1, c.styleSnapshot().redraw)

        // SETFONT 空串恢复宿主环境字体名（EmueraConsole.cs:2826-2835）
        c.setFont("Saitamaar")
        assertEquals("Saitamaar", c.styleSnapshot().fontName)
        c.setFont("")
        assertEquals("黑体", c.styleSnapshot().fontName, "恢复的是注入项 defaultFontName，不是出厂缺省")
    }

    // ------------------------------------------------------------------
    // 3. 打印中缓冲：读取即 flush（原版口径），不是读取即丢弃
    // ------------------------------------------------------------------

    /**
     * 基准 :302 `HTML_POPPRINTINGSTR=[]`：调用点在 PRINTFORML 之后，缓冲已空 → 空表。
     *
     * 非空一侧（M4-N `n-hostconsole` 改钉，旧断言「弹出的行仍算已提交显示行」是把两条路径混成一条）：
     * 原版 `EmueraConsole.cs:3578-3588` 的 `PopDisplayingLines()` 只有 `return printBuffer.Flush(...)`
     * 一句，**不调** `addRangeDisplayLine`；对照 `:3375-3386` 的 `PrintFlush(bool)` —— 那里才是
     * `printBuffer.Flush(...)` **后面紧跟** `addRangeDisplayLine(lineList)`。
     * 而 `PrintStringBuffer.Flush`（`PrintStringBuffer.cs:179-186`）末尾 `clearBuffer()`
     * （`:273-278` 只清 builder/stringList/buttonList）⇒ 弹出去的行**不进显示列表**，
     * 也不动 revision；缓冲清空 ⇒ LINEISEMPTY 转真。
     */
    @Test
    fun printingBufferIsEmptyAfterFlushAndDropsLinesWhenPopped() {
        val c = console()
        c.printLine("PRINTFORML-SHAPE")
        assertEquals(emptyList(), c.poppingLines(), "已 flush → 空表（基准 :302）")
        assertTrue(c.printingBufferEmpty, "LINEISEMPTY 的数据源同口径（基准 :305 EMPTY=1）")

        c.print("半行", lineEnd = false)
        assertEquals(false, c.printingBufferEmpty, "PRINTN 之后缓冲非空")
        val popped = c.poppingLines()
        assertEquals(1, popped.size, "非空则 flush 并交出行")
        assertEquals("半行", popped[0].text)
        assertEquals(listOf("PRINTFORML-SHAPE"), c.loggedLines(), "弹出走的不是 PrintFlush ⇒ 不进显示列表")
        assertTrue(c.printingBufferEmpty, "Flush 末尾 clearBuffer ⇒ 缓冲清空")
    }

    // ------------------------------------------------------------------
    // 4. 临时行与 LINECOUNT：既有对拍口径不许松动
    // ------------------------------------------------------------------

    /**
     * `EmueraConsole.cs:2891-2893`：末行是临时行时，下一条显示行加入前先删掉它。
     * 于是 REUSELASTLINE 计了 LINECOUNT 又立刻被减回去，**净贡献 0**（基准 :247
     * `AFTER-REUSE LINE-DELTA=5` = 6 行减 1，与不带临时行的同一段差一致）。
     */
    @Test
    fun temporaryLineReplacedByNextDisplayLineNetsZeroLines() {
        val c = console()
        val lc0 = c.lineCount
        c.printLine("LINE-A")
        c.printLine("LINE-B")
        c.printTemporaryLine("REUSED")
        c.printLine("LINE-C")
        assertEquals(3L, c.lineCount - lc0, "临时行被下一条显示行替换，净贡献 0")
        assertEquals(listOf("LINE-A", "LINE-B", "LINE-C"), c.loggedLines(), "临时行不进日志文本")
        assertEquals(3, c.displayLines().size, "临时行也不在已提交显示行里")
    }

    // ------------------------------------------------------------------
    // 5. 折行宽度与 PRINTC 补齐：走生产的 CellWidth 那一份表
    // ------------------------------------------------------------------

    /**
     * PRINT-C 的 12 项 x 25 格 = 300 格按 199 折成 199 + 101 两条显示行
     * （基准 :215-216 两行的实际长度），分隔线各填满 199 格（基准 :253-255）。
     * 单位是半角格，换算只在 [CellWidth] 一处。
     */
    @Test
    fun printCWidthAndFoldingMatchPcBaselineNumbers() {
        val c = console()
        listOf("AA", "BB", "C1", "D2", "EE", "FF", "G3", "H4", "II", "JJ", "K5", "L6").forEach {
            c.printC(it, alignRight = it == "AA" || it == "C1" || it == "EE" || it == "G3" || it == "II" || it == "K5")
        }
        c.newLine()
        val rows = c.loggedLines()
        assertEquals(2, rows.size, "300 格在 199 格宽度下折成两条显示行")
        assertEquals(199, rows[0].length)
        assertEquals(101, rows[1].length)

        val bars = console()
        bars.printBar()
        bars.newLine()
        bars.printCustomBar("-2")
        bars.newLine()
        assertEquals(199, bars.loggedLines()[0].length, "DRAWLINE 填满 DrawableWidth（基准 :254）")
        assertEquals(199, bars.loggedLines()[1].length, "DRAWLINEFORM 同宽（基准 :255）")
        assertTrue(bars.loggedLines()[1].startsWith("-2-2"))
    }
}
