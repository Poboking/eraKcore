package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ScreenLayout] 行为锁死。两段口径：
 *
 * 1. [ScreenLayout.fixedColumns] 是**桌面预览**（`tools/uipreview --columns N`）的历史公式，
 *    期望值全部手算写死；
 * 2. [ScreenLayout.resolve] 是**真机唯一入口**：字号/行距是输入，列数/行数是输出。
 *    这一段的每个读数都对应「旧链路会翻车」的场景，锁住 2026-11-03 单源重构的不变式：
 *    页面内容不再参与字号推导，改 `FONT SIZE`/`LINE HEIGHT` 是真设置。
 */
class ScreenLayoutTest {

    // ------------------------------------------------------------------
    // 固定列模式（widthOnlyMetrics 口径；只给预览）
    // ------------------------------------------------------------------

    @Test
    fun `固定列_2400宽400dpi_110列_字42行高44_画布2402`() {
        val layout = ScreenLayout.fixedColumns(2400, densityDpi = 400, columns = 110)
        // 2400 / (110*0.52 + 1/6 + 0.03) = 2400 / 57.3967 = 41.816 → 42
        assertEquals(42, layout.fontPixelSize)
        // 41.816 * 19/18 = 44.140 → 44
        assertEquals(44, layout.lineHeight)
        assertEquals(110, layout.columns)
        assertEquals(110, layout.clientWidth)
        // 画布宽 = cellsToPixels(110) = (110 * 42/2*1.04).toInt() = 2402
        assertEquals(2402, layout.canvasWidthPx)
        assertEquals(2402, layout.metrics.cellsToPixels(110))
        // 1080 高装 1080/44 = 24 行
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
        val zero = ScreenLayout.fixedColumns(0, densityDpi = 420, columns = 110)
        assertEquals(47, zero.fontPixelSize)
        assertEquals(50, zero.lineHeight)
        assertEquals(zero.fontPixelSize, ScreenLayout.fixedColumns(200, densityDpi = 420, columns = 110).fontPixelSize)
    }

    @Test
    fun `固定列_非法列数抬1_兜底档不被负宽炸`() {
        assertEquals(1, ScreenLayout.fixedColumns(2400, 400, columns = 0).columns)
        // 负宽同走 18dp 兜底，不抛
        assertEquals(18, ScreenLayout.fixedColumns(-5, densityDpi = 160).fontPixelSize)
    }

    // ------------------------------------------------------------------
    // 单源解算（真机口径：字号是输入，列数/行数是输出）
    // ------------------------------------------------------------------

    @Test
    fun `解算_2400宽400dpi_配置18x19_字33行高35列139_30行`() {
        // 18 × (400/160) × 0.75 = 33.75 → 34；列数守护 136 用 floor 反推：
        // 2400 / (136 × 0.52) = 33.936 → 33（字号压小 1px，实际 2400/(33×0.52)=139.86 → 139 列）
        // 行高 33 × 19/18 = 34.83 → 35；行数 1080 / 35 = 30
        val layout = ScreenLayout.resolve(2400, 1080, 400, 18, 19)
        assertEquals(33, layout.fontPixelSize)
        assertEquals(35, layout.lineHeight)
        assertEquals(139, layout.columns)
        assertEquals(30, layout.rows)
        assertTrue(layout.columns >= ScreenLayout.DEFAULT_COLUMNS, "列数守护 136 必须真的铺满 ≥136 列")
        assertTrue(layout.metrics.cellsToPixels(layout.columns) <= 2400, "列数不得越出可绘制宽")
        assertTrue(layout.rows * layout.lineHeight <= 1080, "行数不得越出可见高")
    }

    @Test
    fun `解算_1080窄屏_列数守护把字号压到15_仍铺得下136列`() {
        // 18 × (440/160) × 0.75 = 37.125，但 1080 宽铺不下 136 列 ⇒ floor 反推到 1080/70.72 = 15.27 → 15
        val layout = ScreenLayout.resolve(1080, 1080, 440, 18, 19)
        assertEquals(15, layout.fontPixelSize)
        assertEquals(16, layout.lineHeight)
        assertEquals(138, layout.columns)
        assertTrue(layout.columns >= ScreenLayout.DEFAULT_COLUMNS, "列数守护 136 必须真的铺满")
        val shift = maxOf(2, layout.fontPixelSize / 6)
        val drawable = ((1080 - shift) / layout.metrics.cellPixelWidth).toInt()
        assertTrue(drawable >= 128, "实铺 $drawable 格 < 128，地图会折行")
    }

    @Test
    fun `解算_FONT SIZE 是输入_关掉守护后调大真的变大`() {
        val base = ScreenLayout.resolve(2400, 1080, 400, 18, 19, minColumns = 0)
        val bigger = ScreenLayout.resolve(2400, 1080, 400, 24, 25, minColumns = 0)
        // 18 → 34px / 135 列；24 → 45px / 102 列（字号变大、列数变少）
        assertEquals(34, base.fontPixelSize)
        assertEquals(45, bigger.fontPixelSize)
        assertTrue(bigger.lineHeight > base.lineHeight)
        assertTrue(bigger.columns < base.columns)
    }

    @Test
    fun `解算_列数守护可调_守护压回默认档_调低后字号生效`() {
        // 同一块屏：守护 136 时 24 的字号被 floor 反推压到 33；守护关掉后 24 生效为 45。
        val capped = ScreenLayout.resolve(2400, 1080, 400, 24, 25)
        val free = ScreenLayout.resolve(2400, 1080, 400, 24, 25, minColumns = 0)
        assertEquals(33, capped.fontPixelSize, "守护生效时字号被压小（保地图，不是设置失效）")
        assertEquals(45, free.fontPixelSize, "守护关掉后 FONT SIZE 必须生效")
    }

    @Test
    fun `解算_显示大小档_大档真放大_小档真缩小`() {
        val standard = ScreenLayout.resolve(2400, 1080, 400, 18, 19, 136, 136f / 136f)
        // 大档：倍率 136/90 = 1.5111，18×2.5×0.75×1.5111 = 51.0 → 51；守护 90 恰好铺 90 列
        val large = ScreenLayout.resolve(2400, 1080, 400, 18, 19, 90, 136f / 90f)
        assertEquals(33, standard.fontPixelSize)
        assertEquals(51, large.fontPixelSize)
        assertEquals(90, large.columns)
        assertTrue(large.fontPixelSize > standard.fontPixelSize, "大档必须真的把字号放大，不能是无效档")
        assertTrue(large.columns < standard.columns, "大档的字号更大 ⇒ 列数更少")
        // 小档：倍率 136/150 = 0.9067，33.75×0.9067 = 30.6 → 31，守护 150 压到 30，实铺 153 列
        val small = ScreenLayout.resolve(2400, 1080, 400, 18, 19, 150, 136f / 150f)
        assertEquals(30, small.fontPixelSize)
        assertTrue(small.columns >= 150, "小档的列数守护 150 必须真的铺满")
        assertTrue(small.fontPixelSize < standard.fontPixelSize, "小档必须真的把字号缩小")
    }

    @Test
    fun `解算_LINE HEIGHT 是输入_只改行距不改字号`() {
        val base = ScreenLayout.resolve(2400, 1080, 400, 18, 19)
        val loose = ScreenLayout.resolve(2400, 1080, 400, 18, 24)
        assertEquals(base.fontPixelSize, loose.fontPixelSize, "行距不得反过来改字号")
        // 33 × 24/18 = 44；1080 / 44 = 24 行
        assertEquals(44, loose.lineHeight)
        assertEquals(24, loose.rows)
    }

    @Test
    fun `解算_未布局_宽0高0不炸且守住列下限`() {
        val zero = ScreenLayout.resolve(0, 0, 400, 18, 19, minColumns = 136)
        assertEquals(34, zero.fontPixelSize)
        assertEquals(136, zero.columns)
        assertEquals(1, zero.rows)
    }

    @Test
    fun `解算_配置行高非法时回落到19比18`() {
        val layout = ScreenLayout.resolve(2400, 1080, 400, 18, -1)
        assertEquals(33, layout.fontPixelSize)
        assertEquals(35, layout.lineHeight)
    }

    // ------------------------------------------------------------------
    // 不变式：常见屏宽都放得下 128 格地图行
    // ------------------------------------------------------------------

    /**
     * 语料最宽的整行地图是 128 半角格（`ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:10`
     * 的 `#DIMS COLORCOLUMNS,128`）。旧链路把字号与页行数互推，翻页/输入栏显隐都可能让
     * 实际可绘制格跌破 128；单源解算只依赖屏宽，必须钉住这条不变式：
     *
     * 1. 实铺格数 ≥ 128（否则地图被折成「主行 + 尾巴行」，玩家反馈的红魔馆一层残缺）；
     * 2. 列数不越出可绘制宽（否则右侧被裁，另一种「残缺」）。
     *
     * 实铺格数按 `CanvasConsoleView.rebuildScreen` 的算式复算：`floor((宽 - shift) / 半角格)`，
     * shift = `max(2, 字号 / 6)`（`MappingColorGeometry.ShapePositionShift`，spec: `Config.cs:461-466`）。
     */
    @Test
    fun `解算_缺省136列守护_常见屏宽都放得下128格地图行`() {
        val screens = listOf(
            1080 to 440, 1280 to 480, 1440 to 480, 1600 to 480,
            1920 to 400, 2340 to 400, 2400 to 400, 2560 to 400, 3000 to 400,
        )
        for ((width, dpi) in screens) {
            val layout = ScreenLayout.resolve(
                width, 1080, dpi, 18, 19, ScreenLayout.DEFAULT_COLUMNS,
            )
            val shift = maxOf(2, layout.fontPixelSize / 6)
            val drawableCells = ((width - shift) / layout.metrics.cellPixelWidth).toInt()
            assertTrue(
                drawableCells >= 128,
                "宽 $width：字号 ${layout.fontPixelSize}、列数 ${layout.columns}、实铺 $drawableCells 格 < 128（地图会折行）",
            )
            assertTrue(
                layout.columns >= ScreenLayout.DEFAULT_COLUMNS,
                "宽 $width：列数守护 136 只铺了 ${layout.columns} 列（floor 反推必须真的达标）",
            )
            assertTrue(
                layout.metrics.cellsToPixels(layout.columns) <= width,
                "宽 $width：反推 ${layout.columns} 列要 ${layout.metrics.cellsToPixels(layout.columns)}px，越界会被裁",
            )
        }
    }

    @Test
    fun `两种口径都过CellMetrics夹取_字号不小于8_行高不小于字号`() {
        for (layout in listOf(
            ScreenLayout.fixedColumns(2400, 400, 110),
            ScreenLayout.fixedColumns(60, 160, 110),
            ScreenLayout.resolve(2400, 1080, 400, 18, 19),
            ScreenLayout.resolve(200, 100, 400, 18, 19),
            ScreenLayout.resolve(200, 100, 400, 8, 6),
        )) {
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
