package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ScreenLayout] 的行为锁死（CONTRACT-UP3 §3）：期望值全部**手算写死**，来源 =
 * 下沉前 `render-canvas` 的 `CanvasConsoleView` 现逻辑（`widthOnlyMetrics`
 * `CanvasConsoleView.kt:265-269`、构造期兜底 `:216-228`、`applyPageFit` `:276-301`
 * → `PageFitter.fit` `PageFitter.kt:93-115`）—— 先取数、再搬逻辑，搬完跑同一张对拍表。
 *
 * B 段（`CanvasConsoleView` 三处推导点切本共享函数）后本表不改一字：View 侧行为
 * 由「同一公式 + 同一入参」保持，render-canvas 的编译/闸门另行覆盖。
 *
 * 锚点读数：BOARD UP1 行回执的横屏缺省 = 2402×1080 画布 / 110 列 / 字 42px /
 * 行高 44 / 24 行 —— 对应本表 `2400 宽 400dpi 110 列` 用例（字 42、行高 44、
 * 画布宽 2402、1080 高装 24 行），四处读数互相咬合。
 */
class ScreenLayoutTest {

    // ------------------------------------------------------------------
    // 固定列模式（widthOnlyMetrics 口径，CanvasConsoleView.kt:265-269）
    // ------------------------------------------------------------------

    @Test
    fun `固定列_2400宽400dpi_110列_字42行高44_画布2402`() {
        val layout = ScreenLayout.fixedColumns(2400, densityDpi = 400, columns = 110)
        // 2400 / (110*0.52 + 1/6 + 0.03) = 2400 / 57.3967 = 41.816 → 42
        assertEquals(42, layout.fontPixelSize)
        // 41.816 * 19/18 = 44.140 → 44（BOARD UP1 回执同读数）
        assertEquals(44, layout.lineHeight)
        assertEquals(110, layout.columns)
        assertEquals(110, layout.clientWidth)
        // 画布宽 = cellsToPixels(110) = (110 * 42/2*1.04).toInt() = 2402（BOARD 回执 2402×1080）
        assertEquals(2402, layout.canvasWidthPx)
        assertEquals(2402, layout.metrics.cellsToPixels(110))
        // 1080 高装 1080/44 = 24 行（BOARD 回执「24 行」）
        assertEquals(24, layout.heightLinesFor(1080))
        // 半角格宽 = 42/2*1.04 = 21.84（StringMeasure.cs:37 同式）
        assertEquals(21.84f, layout.metrics.cellPixelWidth)
    }

    @Test
    fun `固定列_短边1080_字号随宽缩`() {
        val layout = ScreenLayout.fixedColumns(1080, densityDpi = 400, columns = 110)
        // 1080 / 57.3967 = 18.817 → 19；19 的行高 = 18.817*19/18 = 19.86 → 20
        assertEquals(19, layout.fontPixelSize)
        assertEquals(20, layout.lineHeight)
    }

    @Test
    fun `固定列_PC对齐档_1797宽199列_字17行高18`() {
        val layout = ScreenLayout.fixedColumns(1797, densityDpi = 160, columns = 199)
        // 分母 199*0.52+0.1967 = 103.677；1797/103.677 = 17.334 → 17；行高 18.296 → 18
        // （PC 基准是固定字号 18 ⇒ 199 列；反推方向带 +1/6+0.03 偏置得 17，原样锁死不修正）
        assertEquals(17, layout.fontPixelSize)
        assertEquals(18, layout.lineHeight)
        assertEquals(199, layout.columns)
    }

    @Test
    fun `固定列_QHD宽1440_500dpi_字25行高26`() {
        val layout = ScreenLayout.fixedColumns(1440, densityDpi = 500, columns = 110)
        // 1440 / 57.3967 = 25.089 → 25；行高 26.483 → 26
        assertEquals(25, layout.fontPixelSize)
        assertEquals(26, layout.lineHeight)
    }

    @Test
    fun `固定列_极窄宽_反推不足下限退18dp兜底`() {
        // 150 / 57.3967 = 2.61 < 8 ⇒ font = 18dp * (160/160) = 18；行高 = 19（设计基准档）
        val mock = ScreenLayout.fixedColumns(150, densityDpi = 160, columns = 110)
        assertEquals(18, mock.fontPixelSize)
        assertEquals(19, mock.lineHeight)

        // 420dpi 下同宽：18dp * 2.625 = 47.25 → 47；行高 49.875 → 50
        val hd = ScreenLayout.fixedColumns(200, densityDpi = 420, columns = 110)
        assertEquals(47, hd.fontPixelSize)
        assertEquals(50, hd.lineHeight)
    }

    @Test
    fun `固定列_拿不到屏参_宽0与mock同解走18dp`() {
        // CanvasConsoleView 构造期兜底（:216-228）：mock context 宽为 0 ⇒ 18dp*density
        val zero = ScreenLayout.fixedColumns(0, densityDpi = 420, columns = 110)
        assertEquals(47, zero.fontPixelSize)
        assertEquals(50, zero.lineHeight)
        // 与「极窄触发兜底」同解（widthOnlyMetrics 的 byWidth < 8 与构造期宽 0 落同一分支）
        assertEquals(zero.fontPixelSize, ScreenLayout.fixedColumns(200, densityDpi = 420, columns = 110).fontPixelSize)
    }

    @Test
    fun `固定列_非法列数抬1_兜底档不被负宽炸`() {
        assertEquals(1, ScreenLayout.fixedColumns(2400, 400, columns = 0).columns)
        // 负宽同走 18dp 兜底，不抛
        assertEquals(18, ScreenLayout.fixedColumns(-5, densityDpi = 160).fontPixelSize)
    }

    // ------------------------------------------------------------------
    // 适应屏幕模式（applyPageFit → PageFitter 口径，PageFitter.kt:93-115）
    // ------------------------------------------------------------------

    @Test
    fun `适应屏_25行页_铺满1080高_字40行高42列115`() {
        val layout = ScreenLayout.fit(2400, 1080, pageRows = 25)
        // 字号听高度：1080/25/1.0556 = 40.91 → 40（宽度给的 54 更大，不约束）
        assertEquals(40, layout.fontPixelSize)
        assertEquals(42, layout.lineHeight)
        // 列数铺满宽：(2400 / (40*0.52)).toInt() = 115
        assertEquals(115, layout.columns)
        assertEquals(115, layout.clientWidth)
        // 一页 25 行 × 42 = 1050 ≤ 1080：这一页正好装下
        assertTrue(25 * layout.lineHeight <= 1080)
    }

    @Test
    fun `适应屏_49行页_字号停在下限30_内容滚动`() {
        val layout = ScreenLayout.fit(2400, 1080, pageRows = 49)
        assertEquals(PageFitter.MIN_READABLE_FONT_PX, layout.fontPixelSize)
        assertEquals(31, layout.lineHeight)
        assertEquals(153, layout.columns)
        assertTrue(49 * layout.lineHeight > 1080, "49 行装不下 ⇒ 字号优先，滚动")
    }

    @Test
    fun `适应屏_纯图行页_标题35带加1行_36行同触下限`() {
        // 标题页 = 35 条横带（ResourceManager.ERB:8-10）+ 1 行余量；1080/36 = 30 行高
        // ⇒ 字号 28.4 被可读下限抬到 30 —— 纯图行页与 49 行文字页落同一下限档
        val layout = ScreenLayout.fit(2400, 1080, pageRows = 36)
        assertEquals(30, layout.fontPixelSize)
        assertEquals(31, layout.lineHeight)
        assertEquals(153, layout.columns)
    }

    @Test
    fun `适应屏_窄屏Redmi档_宽度保底minColumns`() {
        // 720 宽：宽度给的字号只有 16.5，被下限抬到 30 后列数只剩 46 ⇒ 列数下限 84 兜住
        val layout = ScreenLayout.fit(720, 1600, pageRows = 12)
        assertEquals(30, layout.fontPixelSize)
        assertEquals(31, layout.lineHeight)
        assertEquals(84, layout.columns)
    }

    @Test
    fun `适应屏_既有会话的clientWidth当下限_字号被宽度约束`() {
        // applyPageFit 的口径：minColumns = console 构造期定死的 clientWidth（改宽要换 sink）
        val layout = ScreenLayout.fit(2318, 1080, pageRows = 25, minColumns = 110)
        assertEquals(40, layout.fontPixelSize)
        assertEquals(42, layout.lineHeight)
        assertEquals(111, layout.columns, "列数铺满 2318 宽，不低于下限 110")
        assertTrue(layout.columns >= 110)
    }

    // ------------------------------------------------------------------
    // 共性口径
    // ------------------------------------------------------------------

    @Test
    fun `两模式都过CellMetrics夹取_字号不小于8_行高不小于字号`() {
        for (layout in listOf(
                ScreenLayout.fixedColumns(2400, 400, 110),
                ScreenLayout.fixedColumns(60, 160, 110),
                ScreenLayout.fit(2400, 1080, 25),
                ScreenLayout.fit(200, 100, 200),
            )
        ) {
            assertTrue(layout.fontPixelSize >= CellMetrics.MIN_FONT_PIXEL_SIZE)
            assertTrue(layout.lineHeight >= layout.fontPixelSize)
            assertEquals(layout.fontPixelSize, layout.metrics.fontPixelSize)
            assertEquals(layout.lineHeight, layout.metrics.lineHeight)
        }
    }

    @Test
    fun `heightLines_零或负高兜1_正常向下取整`() {
        val layout = ScreenLayout.fixedColumns(2400, 400, 110) // 行高 44
        assertEquals(1, layout.heightLinesFor(0))
        assertEquals(1, layout.heightLinesFor(-100))
        assertEquals(1, layout.heightLinesFor(43))
        assertEquals(1, layout.heightLinesFor(44))
        assertEquals(2, layout.heightLinesFor(88))
        assertEquals(24, layout.heightLinesFor(1080))
    }
}
