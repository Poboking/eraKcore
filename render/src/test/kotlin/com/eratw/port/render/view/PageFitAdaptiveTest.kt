package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PageFitter] 的口径钉子 —— **一页行数由内容量出来，字号取「铺满这一页的最大值」**。
 *
 * 2026-10-25 重写（主人两次反馈后的结论）：
 * - 写死 44 行 ⇒ 短页面字号只有 22px（字太小）；
 * - 写死 25 行 ⇒ 49 行的存档页被切（偏离「一页铺满」初衷）；
 * - 正确做法：行数 = 实际内容行数，行高 = 可见高 ÷ 行数，
 *   字号 = min(高度给的字号, 宽度给的字号)，下限取**原版 18px**。
 */
class PageFitAdaptiveTest {

    @Test
    fun `短页_25行时字号吃满高度_1080px 约 40px`() {
        val fit = PageFitter.fit(2400, 1080, pageRows = 25)
        assertEquals(25, fit.rows)
        assertEquals(40, fit.fontPixelSize, "1080/25/1.0556 ≈ 40")
        assertEquals((40 * PageFitter.LINE_HEIGHT_RATIO).toInt(), fit.lineHeight)
    }

    @Test
    fun `长页_49行时字号停在下限而不是缩成小字`() {
        val fit = PageFitter.fit(2400, 1080, pageRows = 49)
        assertEquals(49, fit.rows)
        assertEquals(PageFitter.MIN_READABLE_FONT_PX, fit.fontPixelSize, "49 行装不下 ⇒ 停在下限 30px，内容滚动")
        assertTrue(fit.fontPixelSize >= PageFitter.MIN_READABLE_FONT_PX)
        val shorter = PageFitter.fit(2400, 1080, pageRows = 30)
        assertTrue(shorter.fontPixelSize >= fit.fontPixelSize, "页越短字越大或持平（都触底时持平）")
    }

    @Test
    fun `不知道行数时落兜底 25 行`() {
        val unknown = PageFitter.fit(2400, 1080, pageRows = 0)
        assertEquals(PageFitter.FALLBACK_PAGE_ROWS, unknown.rows)
        assertEquals(
            PageFitter.fit(2400, 1080, pageRows = PageFitter.FALLBACK_PAGE_ROWS).fontPixelSize,
            unknown.fontPixelSize,
        )
    }

    @Test
    fun `行数超出一屏容量时字号停在下限_内容改为滚动`() {
        val fit = PageFitter.fit(2400, 1080, pageRows = 200)
        assertEquals(200, fit.rows, "行数如实记录（调试读数要看得到这一页多长）")
        assertEquals(PageFitter.MIN_READABLE_FONT_PX, fit.fontPixelSize, "不再往下缩：停在下限")
        assertTrue(1080 / fit.lineHeight < 200, "此页确实超过一屏容量 ⇒ 只能滚动")
    }

    @Test
    fun `宽度不够时字号听宽度的`() {
        val fit = PageFitter.fit(1200, 2000, pageRows = 25)
        val fontByWidth = (1200.0 / PageFitter.DEFAULT_MIN_COLUMNS / PageFitter.HALF_CELL_RATIO).toInt()
        val fontByHeight = (2000.0 / 25 / PageFitter.LINE_HEIGHT_RATIO).toInt()
        assertEquals(
            minOf(fontByHeight, fontByWidth).coerceAtLeast(PageFitter.MIN_READABLE_FONT_PX),
            fit.fontPixelSize,
        )
    }

    @Test
    fun `列数铺满可用宽度且不少于下限`() {
        val fit = PageFitter.fit(2318, 1080, pageRows = 25)
        val expected = (2318 / (fit.fontPixelSize * PageFitter.HALF_CELL_RATIO)).toInt()
        assertEquals(maxOf(expected, PageFitter.DEFAULT_MIN_COLUMNS), fit.columns)
    }

    @Test
    fun `一页行数被夹在合理区间`() {
        assertEquals(PageFitter.PAGE_ROWS_RANGE.first, PageFitter.fit(2400, 1080, pageRows = 1).rows)
        assertEquals(PageFitter.PAGE_ROWS_RANGE.last, PageFitter.fit(2400, 1080, pageRows = 9999).rows)
    }
}

/**
 * [ConsoleScreen.pageExtentRows] 的口径钉子：**量出「当前这一页」有多行**。
 *
 * 量法：从日志底部往上数连续行，遇到空行（页与页的分隔）就停；页尾的空行先跳过。
 * 这是「适应屏幕」能自适应各种页面的前提 —— 标题页 3 行、存档页 49 行都能量准。
 */
class PageExtentRowsTest {

    private fun row(text: String): DisplayRow = DisplayRow(
        runs = listOf(
            TextRun(
                text = text,
                fore = 0xC0C0C0,
                back = 0x000000,
                styleMask = 0,
                fontName = "",
                kind = RunKind.TEXT,
            ),
        ),
        cells = text.length,
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun blankRow(): DisplayRow = DisplayRow(
        runs = emptyList(),
        cells = 0,
        isLogicalLine = true,
        isLineEnd = true,
    )

    @Test
    fun `量出当前页 49 行_遇到真空行就停`() {
        val screen = ConsoleScreen(clientWidth = 80, maxLog = 500, heightLines = 10)
        repeat(5) { screen.log.append(row("上一页的第 $it 行")) }
        screen.log.append(blankRow())
        repeat(49) { i -> screen.log.append(row("SLOT $i 存档槽位")) }
        screen.refresh()
        assertEquals(49, screen.pageExtentRows(), "空行之后的那一块就是这一页")
    }

    @Test
    fun `页尾多余空行不算进行数`() {
        val screen = ConsoleScreen(clientWidth = 80, maxLog = 500, heightLines = 10)
        screen.log.append(row("页内一行"))
        screen.log.append(row("页内二行"))
        screen.log.append(blankRow())
        screen.log.append(blankRow())
        screen.refresh()
        assertEquals(2, screen.pageExtentRows())
    }

    @Test
    fun `空日志量到 0`() {
        val screen = ConsoleScreen(clientWidth = 80, maxLog = 500, heightLines = 10)
        assertEquals(0, screen.pageExtentRows())
    }

    @Test
    fun `整屏没有空行时按上限量`() {
        val screen = ConsoleScreen(clientWidth = 80, maxLog = 500, heightLines = 10)
        repeat(30) { screen.log.append(row("连续第 $it 行")) }
        screen.refresh()
        assertEquals(30, screen.pageExtentRows(maxRows = 200))
        assertEquals(10, screen.pageExtentRows(maxRows = 10), "maxRows 是量取上限")
    }
}
