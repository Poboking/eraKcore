package com.eratw.port.core.fn

import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.ImageSizePx
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GFX 段列坐标：`consolePrintedLine` 的段宽必须与 sink 排版同一把尺（图形感知）。
 *
 * ## 钉的是原版哪一步
 *
 * 原版 `DisplayLine2Html`（HtmlManager.cs:415-470）逐**节点**包 `<button>`，节点位置由
 * `ConsoleButtonString` 的列区间决定；而列区间的宽度来自各节点的 `SetWidth`
 * （`ConsoleImagePart.cs:60-87`、`ConsoleShapePart.cs:46-79`：图元按属性折算像素，不是 0）。
 * 本仓的列坐标系是半角格，唯一换算 = [CellWidth.visibleHtmlCells]（图形支经
 * [CellWidth.graphicCells] 与渲染侧 `DrawCmd.cellsFor` 同式），sink 的 `segmentWidth` /
 * `visibleHtmlWidth` 用的就是它。
 *
 * 收口前 `consolePrintedLine` 对 HTML 段量「剥标签后的可见文本」（`CellWidth.of(plain)`），
 * `<img>` / `<shape>` 因此记 0 格：段列偏小，紧随其后的按钮（`line.buttons` 的列号是 sink
 * 带图形宽算的）落不进本段或落到错段，`HTML_GETPRINTEDSTR` / `GETDISPLAYLINE` 回读的
 * `<button>` 包裹位置与屏上不一致。本文件的三个正向用例即该缺陷的回归锚。
 *
 * ## 尺寸口径
 *
 * 夹具字号固定 18（`ConfigData.cs:66` 出厂值）。格数**不写死**，一律与
 * `CellWidth.graphicCells(px, 18)` 比 —— 量化式是唯一真相，本文件不复制第二份。
 * 当前读数：`40px` → 5 格、`90px` → 10 格、无 width 的 300×100 图（`height='18px'`
 * ⇒ 54px）→ 6 格。
 *
 * 最后一例钉「纯文本行零变化」：接不接图形尺，非 HTML 段的列坐标与行体必须逐字相同
 * （非 HTML 段仍走 `CellWidth.of`）。
 */
class HtmlPrintedWidthGraphicsTest {

    /** 夹具产物：sink 本体（取 defaultFontName / imageSizeOf）与它排出的唯一条显示行。 */
    private class Fixture(
        val sink: LineBufferConsoleSink,
        val line: LineBufferConsoleSink.DisplayLine,
    ) {
        /** 按 sink 自持的同一把尺回读（生产装配点 [com.eratw.port.core.proc.ConsoleBuiltinHost] 的形状）。 */
        fun printed(): ConsolePrintedLine = consolePrintedLine(
            line,
            sink.defaultFontName,
            sink.scriptFontSize.toLong(),
            sink.imageSizeOf,
        )
    }

    /** HTML 段后面接一颗 PRINTBUTTON（原版 AppendButton，PrintStringBuffer.cs:103-123）。 */
    private fun htmlThenButton(
        html: String,
        scriptFontSize: Int = 18,
        imageSizeOf: ((String) -> ImageSizePx?)? = null,
    ): Fixture {
        val sink = LineBufferConsoleSink(scriptFontSize = scriptFontSize, imageSizeOf = imageSizeOf)
        sink.printHtml(html, option = true)
        sink.printButton(BUTTON_TEXT, BUTTON_VALUE)
        sink.newLine()
        return Fixture(sink, sink.lines().single())
    }

    // ===== 显式 width 的 <img> =====

    @Test
    fun `img 显式 width 后按钮起始列按图形格数 回读带 button`() {
        // `<img>` 必须带 `src`（HtmlStrictParser.openImg，HtmlStrict.kt:716 的 CodeEE 判式），
        // 与语料里的实际写法一致；宽度只由 width 决定，src 不参与量宽。
        val html = "<img src='a.png' width='40px' height='18px'>"
        val fx = htmlThenButton(html)

        // sink 侧（排版真相）：按钮列 = 图元占格数，不是 0。
        val expectedCells = CellWidth.graphicCells(40L, 18L)
        assertEquals(5, expectedCells, "40px / (18/2*1.04) 向上取整 = 5 格")
        assertEquals(expectedCells, fx.line.buttons.single().startCol)

        // 回读侧（本收口面）：按钮必须开在 HTML 段**之后**、包住按钮文本，
        // 而不是整颗丢失（收口前 widths[HTML] = 0 ⇒ 按钮开不出来）。
        val printed = fx.printed()
        assertEquals("$html<button value='1'>$BUTTON_TEXT</button>", printed.html)
        assertTrue(printed.html.indexOf("<button") > printed.html.indexOf("<img"))
    }

    // ===== <shape> =====

    @Test
    fun `shape space 后按钮起始列同样推进`() {
        val html = "<shape type='space' param='90px'>"
        val fx = htmlThenButton(html)

        val expectedCells = CellWidth.graphicCells(90L, 18L)
        assertEquals(10, expectedCells, "90px / (18/2*1.04) 向上取整 = 10 格")
        assertEquals(expectedCells, fx.line.buttons.single().startCol)
        assertEquals("$html<button value='1'>$BUTTON_TEXT</button>", fx.printed().html)
    }

    // ===== 缺 width 的 <img>：按宿主尺寸源推宽（IMG-A4 I1 的同一条通道） =====

    @Test
    fun `img 缺 width 时按宿主尺寸源推宽后按钮起始列同步`() {
        // 原版 ConsoleImagePart.cs:66-70：缺 width ⇒ 高 × 基图纵横比 = 300*18/100 = 54px。
        val sizes: (String) -> ImageSizePx? = { src ->
            if (src.equals("a.png", ignoreCase = true)) ImageSizePx(300, 100) else null
        }
        val html = "<img src='a.png' height='18px'>"
        val fx = htmlThenButton(html, imageSizeOf = sizes)

        val expectedCells = CellWidth.graphicCells(54L, 18L)
        assertEquals(6, expectedCells, "54px / (18/2*1.04) 向上取整 = 6 格")
        assertEquals(expectedCells, fx.line.buttons.single().startCol)
        assertEquals("$html<button value='1'>$BUTTON_TEXT</button>", fx.printed().html)
    }

    // ===== 纯文本：接不接图形尺都逐值不变 =====

    @Test
    fun `纯文本行读数与接入图形尺前逐值一致`() {
        fun run(scriptFontSize: Int): ConsolePrintedLine {
            val sink = LineBufferConsoleSink(scriptFontSize = scriptFontSize)
            sink.print("AB")
            sink.printButton("依", 2L)
            sink.newLine()
            return consolePrintedLine(
                sink.lines().single(),
                sink.defaultFontName,
                scriptFontSize.toLong(),
            )
        }

        val legacy = run(0) // 改动前形态：无图形尺
        val armed = run(18) // 接了图形尺
        assertEquals("AB依", armed.text)
        assertEquals("AB<button value='2'>依</button>", armed.html)
        assertEquals(legacy.text, armed.text)
        assertEquals(legacy.html, armed.html)
    }

    private companion object {
        const val BUTTON_TEXT = "继续"
        const val BUTTON_VALUE = 1L
    }
}
