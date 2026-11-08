package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 行内游标的**文本段像素**：宿主真度量优先，格是投影（FONT-MEASURE 文本宽收口）。
 *
 * 背景：上一波已把 div 锚点从「可见文本格数 × 半格」收成**像素游标**
 * （`LineBufferConsoleSink.inlineCursor`），但文本那半截仍是等宽近似
 * `格 × FontSize/2`。原版文本宽是 GDI+ 实测（`StringMeasure.GetDisplayLength`，
 * StringMeasure.cs:42-72；节点宽来自 `ConsoleStyledString.SetWidth`，:75-95），
 * 比例字体（语料 `SETFONT "Saitamaar"`）下两者不同值 ⇒ 锚点/按钮列位的像素会偏。
 * 本类钉三件事：
 *
 * 1. **不接线 = 旧读数逐位一致**（缺省构造点 / golden / headless 零行为）；
 * 2. 接线假度量后**像素按度量走、格数一格不动**（格口径仍 `CellWidth.of`）；
 * 3. 文本 + 图元混排：图元仍按真像素（`CellWidth.graphicWidthPxOrNull`），文本按度量。
 *
 * 断言面走公开的 `printHtml` + [DisplayDivBlock.anchorPointXPx] / `anchorCol`：
 * `inlineCursor` 是 private，不为测试公开化（原版 `ConsoleDivPart.PointX` 的对应物就是
 * `anchorPointXPx`，见 `LineBufferConsoleSink.divAnchor` 的 KDoc）。
 */
class InlineCursorTextPxTest {

    /** 与 `DivAnchorImageTest` 同形的合法 div（width/height 齐备才成块）。 */
    private fun card(xpos: Int = 0) =
        "<div xpos='${xpos}px' ypos='0px' width='300px' height='180px'>台词</div>"

    private fun blockOf(sink: LineBufferConsoleSink): DisplayDivBlock =
        sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()

    /** 假度量：每个**半角** 11px、每个**全角** 22px（比例与等宽的 9/18 故意不同）。 */
    private fun fakeMeasure(text: String): Int {
        var px = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            px += if (CellWidth.isFullWidth(cp)) 22 else 11
            i += Character.charCount(cp)
        }
        return px
    }

    private fun anchored(prefix: String, sink: LineBufferConsoleSink): DisplayDivBlock {
        sink.printHtml(prefix + card(), false)
        return blockOf(sink)
    }

    private fun plainSink() = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)

    private fun wiredSink() = LineBufferConsoleSink(
        clientWidth = 200,
        scriptFontSize = 18,
        textPxOf = ::fakeMeasure,
    )

    // ------------------------------------------------------------------
    // 1) 未接线：像素 = 格 × FontSize/2（回归钉）
    // ------------------------------------------------------------------

    @Test
    fun `未接线时文本锚点像素仍是格乘半字号`() {
        val sink = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)

        val cases = listOf(
            // 前缀, 格数, 旧式像素（格 × 18/2）
            "" to 0,
            "AB" to 2,
            "前缀" to 4,
            "あ" to 2,
            "あい" to 4,
            // 实体先还原成文本再量（与 htmlVisibleText 同口径）
            "A&amp;B" to 3,
            // 落不到图形形态的 shape：按标签原文计文本（CellWidth 口径）
            "<shape type='bad'>" to CellWidth.of("<shape type='bad'>"),
        )
        for ((prefix, cells) in cases) {
            // 同一个 sink 反复 printHtml 会累计多行，逐例用新 sink（块唯一、断言单值）。
            val one = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
            val b = anchored(prefix, one)
            assertEquals(cells, b.anchorCol, "格数走 CellWidth.of（前缀=[$prefix]）")
            assertEquals(cells * 9, b.anchorPointXPx, "未接线像素 = 格 × 18/2（前缀=[$prefix]）")
        }
        // 缺省字号（scriptFontSize = 0 ⇒ divFontSize 回落出厂 18）同一条算式。
        val headless = LineBufferConsoleSink(clientWidth = 200)
        val hb = anchored("AB", headless)
        assertEquals(18, hb.anchorPointXPx, "scriptFontSize 缺省回落 18 ⇒ 2 格 × 9")
        // 未接线时 textPxOf 仍是 null（构造缺省语义）。
        assertNull(sink.textPxOf, "缺省构造不接线，golden / headless 读数不受影响")
    }

    // ------------------------------------------------------------------
    // 2) 接线：像素按宿主度量，格数不变
    // ------------------------------------------------------------------

    @Test
    fun `接线假度量后文本锚点像素按度量走`() {
        // 每次量宽用**新** sink：一个 sink 多次 printHtml 会累计多行，块就不唯一了。
        // 半角段：旧 2 × 9 = 18，假度量 2 × 11 = 22。
        val p1 = anchored("AB", plainSink())
        val w1 = anchored("AB", wiredSink())
        assertEquals(2, w1.anchorCol)
        assertEquals(p1.anchorCol, w1.anchorCol, "格数不随度量接线变化")
        assertEquals(18, p1.anchorPointXPx, "旧式等宽近似")
        assertEquals(22, w1.anchorPointXPx, "接线后按假度量（每半角 11px）")

        // 全角段：旧 4 × 9 = 36，假度量 2 × 22 = 44。
        val p2 = anchored("あい", plainSink())
        val w2 = anchored("あい", wiredSink())
        assertEquals(4, w2.anchorCol)
        assertEquals(p2.anchorCol, w2.anchorCol, "格数不随度量接线变化")
        assertEquals(36, p2.anchorPointXPx)
        assertEquals(44, w2.anchorPointXPx, "接线后按假度量（每全角 22px）")
    }

    @Test
    fun `度量答 null 的段回落旧算式其余段仍按度量`() {
        // 只给半角段真读数：全角段答 null ⇒ 该段回落 格 × 9。
        val mixed = { text: String -> if (text.all { it.code < 0x80 }) fakeMeasure(text) else null }
        val onlyHalf = anchored(
            "AB",
            LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18, textPxOf = mixed),
        )
        assertEquals(22, onlyHalf.anchorPointXPx, "半角段按度量")

        val b = anchored(
            "あい",
            LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18, textPxOf = mixed),
        )
        assertEquals(4, b.anchorCol)
        assertEquals(36, b.anchorPointXPx, "宿主答 null ⇒ 该段落回格 × 18/2（4 × 9）")
    }

    // ------------------------------------------------------------------
    // 3) 文本 + 图元混排：图元真像素，文本按度量
    // ------------------------------------------------------------------

    @Test
    fun `图元仍按真像素文本按度量`() {
        val prefix = "<img src='a' width='180px' height='180px'>AB"

        val p = anchored(prefix, plainSink())
        // 图宽 180px（ConsoleImagePart.cs:66-82）+ 文本 2 格 × 9 = 18。
        assertEquals(180 + 18, p.anchorPointXPx, "未接线：图元像素 + 旧式文本近似")
        assertEquals(20 + 2, p.anchorCol, "图元格 ceil(180 / (9 × 1.04)) = 20，文本 2 格")

        val w = anchored(prefix, wiredSink())
        assertEquals(180 + 22, w.anchorPointXPx, "接线：图元仍按真像素，文本按度量（2 × 11）")
        assertEquals(p.anchorCol, w.anchorCol, "混排格数不随度量接线变化")
    }

    // ------------------------------------------------------------------
    // 4) 图元 miss：原版单出口 Text = AltText（ConsoleImagePart.cs:60-65）
    // ------------------------------------------------------------------

    /** 宿主已接线、但表里没有这张图（含基图高 ≤ 0 的退化登记，见下）。 */
    private val missingImage: (String) -> ImageSizePx? = { null }

    /** 显式 `width` + `height` 的 `<img>`：miss 时两者都不生效，宽按 AltText 文本度量。 */
    private val missTag = "<img src='pic' width='180px' height='50'>"

    private fun missAlt() = CellWidth.imgAltText(missTag, 18L)!!

    @Test
    fun `图元 miss 时行内游标按 AltText 计宽且与折行预算同值`() {
        val sink = LineBufferConsoleSink(
            clientWidth = 1000,
            scriptFontSize = 18,
            imageSizeOf = missingImage,
        )
        val b = anchored(missTag, sink)
        // 重建文案 = `<img src='pic' height='9' width='180px'>`（ConsoleImagePart.cs:45-58）。
        assertEquals("<img src='pic' height='9' width='180px'>", missAlt())
        assertEquals(CellWidth.displayCells(missAlt()), b.anchorCol, "格 = AltText 的显示格（:104-115）")
        assertEquals(
            CellWidth.visibleHtmlCells(missTag, 18L, missingImage),
            b.anchorCol,
            "与同一 sink 的折行预算（visibleHtmlCells）同一把尺",
        )
        assertEquals(40, b.anchorCol, "显式 width='180px' 不生效 ⇒ 40 格，不是 20 格")
        assertEquals(40 * 9, b.anchorPointXPx, "像素走 textWidthPx ⇒ 未接线回落 格 × 18/2")
    }

    @Test
    fun `图元 miss 且接线 textPxOf 时像素按宿主度量`() {
        val sink = LineBufferConsoleSink(
            clientWidth = 1000,
            scriptFontSize = 18,
            imageSizeOf = missingImage,
            textPxOf = ::fakeMeasure,
        )
        val b = anchored(missTag, sink)
        assertEquals(CellWidth.displayCells(missAlt()), b.anchorCol, "格仍是 AltText 的显示格")
        assertEquals(fakeMeasure(missAlt()), b.anchorPointXPx, "与文本段同一条路：宿主真度量优先")
        assertEquals(40 * 11, b.anchorPointXPx, "假度量全半角 11px × 40")
    }

    @Test
    fun `基图高为零按 miss 计宽`() {
        val degenerate: (String) -> ImageSizePx? = { ImageSizePx(100, 0) }
        val sink = LineBufferConsoleSink(
            clientWidth = 1000,
            scriptFontSize = 18,
            imageSizeOf = degenerate,
        )
        val b = anchored(missTag, sink)
        assertEquals(40, b.anchorCol, "原版 :69 整除崩溃 ⇒ 端口登记为 miss（ADR-005）")
        assertEquals(CellWidth.visibleHtmlCells(missTag, 18L, degenerate), b.anchorCol, "与折行预算同一把尺")
    }

    @Test
    fun `接线且命中时行内游标仍按图形宽`() {
        val b = anchored(
            missTag,
            LineBufferConsoleSink(
                clientWidth = 1000,
                scriptFontSize = 18,
                imageSizeOf = { ImageSizePx(64, 32) },
            ),
        )
        assertEquals(20, b.anchorCol, "ceil(180 / (9 × 1.04)) = 20 —— 命中路逐值不变")
        assertEquals(180, b.anchorPointXPx, "显式 width='180px' 照旧（:72-75）")
    }

    @Test
    fun `未接线时显式宽仍是图形宽`() {
        val b = anchored(missTag, LineBufferConsoleSink(clientWidth = 1000, scriptFontSize = 18))
        assertEquals(20, b.anchorCol, "未接线判不了 miss ⇒ 旧读数 20 格")
        assertEquals(180, b.anchorPointXPx, "旧读数 180px")
    }

    @Test
    fun `headless 不折算图形时图元零宽且与折行预算一致`() {
        // scriptFontSize = 0（缺省）⇒ 不做图形折算：visibleHtmlCells 对图形记 0，
        // 行内游标也不得按 AltText 计宽（同一把尺，不自造第二套判据）。
        val prefix = missTag + "AB"
        val headless = LineBufferConsoleSink(clientWidth = 1000, imageSizeOf = missingImage)
        val b = anchored(prefix, headless)
        assertEquals(0, CellWidth.visibleHtmlCells(missTag, 0L, missingImage), "折行预算对图形记 0")
        assertEquals(2, CellWidth.visibleHtmlCells(prefix, 0L, missingImage), "整串只剩 AB 两格")
        assertEquals(CellWidth.visibleHtmlCells(prefix, 0L, missingImage), b.anchorCol)
        assertEquals(2, b.anchorCol, "只剩 AB 两格")
        assertEquals(18, b.anchorPointXPx, "2 格 × divFontSize 18 / 2")
    }
}
