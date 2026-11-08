package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `C-WINDOW-LAYOUT@1` 的定向用例：客户区窗口宽/高接入 [ScreenLayout.resolve] 后的 I1–I4 不变式。
 *
 * ## 为什么用例里再写一份「改动前算式」
 *
 * I1 是**回归零变化**的红线：三个新参数全 ≤0 时，字号/行高/列数/行数必须与改动前逐值相同。
 * 这个「改动前」只能以等价金标的形式钉住 —— 用例内独立复刻 2026-11-03 单源重构后的
 * [ScreenLayout.resolve] 旧五步（字号 → 行高 → 列数守护 → 列数 → 行数），**不**引用生产分支，
 * 否则生产公式漂移时两边一起漂、用例恒绿。口径同计划 T7 第 1 条。
 *
 * ## 数值出处（原版窗口语义，spec 锚）
 *
 * - 客户区宽 = `Config.WindowX`（spec: `MainWindow.cs:687-688`），客户区高 =
 *   `WindowY - LineHeight`（spec: `MainWindow.cs:726`）；
 * - 水平居中：左右均分、整数除余 1 px 归左（R4.2）；
 * - 同尺逆折算：`k = 真机字号 / 脚本字号`，播种脚本宽 = `round(版面宽 / k)`
 *   （C-WINDOW-SEED 的同源验收算式）。
 *
 * 真机观感（调窄后左右留白、命中同源）仍是 `TODO(verify)`：本机无设备。
 */
class ScreenLayoutWindowLayoutTest {

    // ------------------------------------------------------------------
    // 等价金标：改动前的 resolve 五步（逐字复刻，不引用生产分支）
    // ------------------------------------------------------------------

    private data class Legacy(val fontPixelSize: Int, val lineHeight: Int, val columns: Int, val rows: Int)

    private fun legacyColumnsFor(widthPx: Int, fontPx: Int): Int {
        val cellPx = fontPx / 2f * CellMetrics.CELL_PIXEL_FACTOR
        return if (cellPx <= 0f) 1 else (widthPx / cellPx).toInt().coerceAtLeast(1)
    }

    private fun legacyResolve(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        configFontPx: Int,
        configLineHeightPx: Int = -1,
        minColumns: Int = ScreenLayout.DEFAULT_COLUMNS,
        fontScale: Float = 1f,
    ): Legacy {
        val density = densityDpi.toFloat() / ScreenLayout.DENSITY_BASE_DPI
        val ratio = if (configLineHeightPx > 0 && configFontPx > 0) {
            configLineHeightPx.toFloat() / configFontPx
        } else {
            ScreenLayout.LINE_HEIGHT_RATIO
        }
        val scale = if (fontScale > 0f && fontScale.isFinite()) fontScale else 1f
        var font = (configFontPx.coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE) *
            density * ScreenLayout.PC_PIXEL_TO_DP * scale).roundToInt()
            .coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE)
        if (minColumns > 0 && widthPx > 0) {
            var capFont = (widthPx /
                (minColumns * CellMetrics.CELL_PIXEL_FACTOR / 2f)).toInt()
                .coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE)
            while (capFont > CellMetrics.MIN_FONT_PIXEL_SIZE &&
                legacyColumnsFor(widthPx, capFont) < minColumns
            ) {
                capFont--
            }
            if (capFont < font) font = capFont
        }
        val line = (font * ratio).roundToInt().coerceAtLeast(font)
        val metrics = CellMetrics(font, line)
        val columns = if (widthPx > 0) {
            (widthPx / metrics.cellPixelWidth).toInt().coerceAtLeast(1)
        } else {
            minColumns.coerceAtLeast(1)
        }
        val rows = if (heightPx > 0) (heightPx / metrics.lineHeight).coerceAtLeast(1) else 1
        return Legacy(metrics.fontPixelSize, metrics.lineHeight, columns, rows)
    }

    // ------------------------------------------------------------------
    // I1：三新参数全 ≤0 ⇒ 与改动前逐值相同（回归零变化）
    // ------------------------------------------------------------------

    @Test
    fun `I1 未接线时字号行高列数行数与改动前逐值相同`() {
        // 4 组覆盖：宽屏铺满、窄屏触发列数守护、大字号守护、超宽屏不触发守护。
        val combos = listOf(
            intArrayOf(2400, 1080, 400, 18),
            intArrayOf(1080, 1080, 440, 18),
            intArrayOf(2400, 1400, 420, 24),
            intArrayOf(8000, 3000, 240, 18),
        )
        for (c in combos) {
            val w = c[0]
            val h = c[1]
            val dpi = c[2]
            val cfgFont = c[3]
            val wireOff = ScreenLayout.resolve(widthPx = w, heightPx = h, densityDpi = dpi, configFontPx = cfgFont)
            val legacy = legacyResolve(w, h, dpi, cfgFont)
            val tag = "combo=($w,$h,$dpi,$cfgFont)"
            assertEquals(legacy.fontPixelSize, wireOff.fontPixelSize, "$tag 字号")
            assertEquals(legacy.lineHeight, wireOff.lineHeight, "$tag 行高")
            assertEquals(legacy.columns, wireOff.columns, "$tag 列数")
            assertEquals(legacy.rows, wireOff.rows, "$tag 行数")
            // 未提供物理内容宽 ⇒ 版面宽退回调用方的可绘制宽；未接线 ⇒ 不走居中。
            assertEquals(w, wireOff.layoutWidthPx, "$tag layoutWidthPx 退回可绘制宽")
            assertEquals(0, wireOff.contentInsetLeftPx, "$tag 未接线不得出现居中偏移")
        }
    }

    @Test
    fun `I1 未布局宽0列数沿用列数守护下限且行数为1`() {
        val legacy = legacyResolve(0, 0, 400, 18)
        val wireOff = ScreenLayout.resolve(widthPx = 0, heightPx = 0, densityDpi = 400, configFontPx = 18)
        assertEquals(legacy.fontPixelSize, wireOff.fontPixelSize)
        assertEquals(legacy.lineHeight, wireOff.lineHeight)
        assertEquals(ScreenLayout.DEFAULT_COLUMNS, wireOff.columns, "未布局时列数沿用守护下限 136（与改动前同分支）")
        assertEquals(1, wireOff.rows)
        assertEquals(0, wireOff.layoutWidthPx)
        assertEquals(0, wireOff.contentInsetLeftPx)
    }

    // ------------------------------------------------------------------
    // I2：窗口宽 ≥ 物理内容宽 ⇒ 满宽、无偏移、列数与改动前同值
    // ------------------------------------------------------------------

    @Test
    fun `I2 窗口宽不小于物理内容宽时满宽且列数不变`() {
        val contentWidthPx = 2400
        // widthPx 是调用方已扣掉右端形状预留的可绘制宽（这里预留 5px = 字号 33 的 shift）。
        val drawableWidthPx = contentWidthPx - 5
        val legacy = legacyResolve(drawableWidthPx, 2400, 400, 18)
        val wired = ScreenLayout.resolve(
            widthPx = drawableWidthPx,
            heightPx = 2400,
            densityDpi = 400,
            configFontPx = 18,
            contentWidthPx = contentWidthPx,
            windowWidthPx = 3000,
        )
        assertEquals(0, wired.contentInsetLeftPx, "窗口宽 ≥ 内容宽 ⇒ 居中偏移 0")
        assertEquals(contentWidthPx, wired.layoutWidthPx, "窗口宽 ≥ 内容宽 ⇒ 版面宽夹到物理内容宽")
        assertEquals(legacy.columns, wired.columns, "满宽时列数与改动前同值")
        assertEquals(legacy.fontPixelSize, wired.fontPixelSize)
        assertEquals(legacy.rows, wired.rows)
    }

    // ------------------------------------------------------------------
    // 居中读数：左右均分、余 1 px 归左
    // ------------------------------------------------------------------

    @Test
    fun `居中_2400内容宽_窗口1200得偏移600_窗口1201得偏移599`() {
        val contentWidthPx = 2400
        val even = ScreenLayout.resolve(
            widthPx = 2395,
            heightPx = 2400,
            densityDpi = 400,
            configFontPx = 18,
            contentWidthPx = contentWidthPx,
            windowWidthPx = 1200,
        )
        // (2400 - 1200) / 2 = 600
        assertEquals(600, even.contentInsetLeftPx)
        assertEquals(1200, even.layoutWidthPx)

        val odd = ScreenLayout.resolve(
            widthPx = 2395,
            heightPx = 2400,
            densityDpi = 400,
            configFontPx = 18,
            contentWidthPx = contentWidthPx,
            windowWidthPx = 1201,
        )
        // (2400 - 1201) / 2 = 1199 / 2 = 599（整数除，余 1 px 归左）
        assertEquals(599, odd.contentInsetLeftPx)
        assertEquals(1201, odd.layoutWidthPx)
    }

    // ------------------------------------------------------------------
    // I3 / I4：偏移非负、版面宽 + 两倍偏移不越出内容宽、列数随窗口宽单调不减
    // ------------------------------------------------------------------

    @Test
    fun `I3 偏移非负且版面宽加两倍偏移不越出内容宽`() {
        val contentWidthPx = 2400
        for (windowWidthPx in listOf(1, 5, 100, 601, 1200, 1201, 2399, 2400, 2401, 5000)) {
            val layout = ScreenLayout.resolve(
                widthPx = 2395,
                heightPx = 2400,
                densityDpi = 400,
                configFontPx = 18,
                contentWidthPx = contentWidthPx,
                windowWidthPx = windowWidthPx,
            )
            assertTrue(layout.contentInsetLeftPx >= 0, "window=$windowWidthPx：偏移必须非负")
            assertTrue(
                layout.layoutWidthPx + 2 * layout.contentInsetLeftPx <= contentWidthPx + 1,
                "window=$windowWidthPx：版面宽 + 2*偏移 越出内容宽（允许 ≤1px 的整数除残差）",
            )
            assertTrue(
                layout.layoutWidthPx <= contentWidthPx,
                "window=$windowWidthPx：版面宽不得超过物理内容宽",
            )
        }
    }

    @Test
    fun `I4 列数随窗口宽单调不减且至少1列`() {
        var previous = 0
        for (windowWidthPx in 1..2400 step 50) {
            val layout = ScreenLayout.resolve(
                widthPx = 2395,
                heightPx = 2400,
                densityDpi = 400,
                configFontPx = 18,
                contentWidthPx = 2400,
                windowWidthPx = windowWidthPx,
            )
            assertTrue(layout.columns >= 1, "window=$windowWidthPx：列数至少 1")
            assertTrue(
                layout.columns >= previous,
                "window=$windowWidthPx：列数随窗口宽单调不减（前值 $previous，现 ${layout.columns}）",
            )
            previous = layout.columns
        }
    }

    // ------------------------------------------------------------------
    // rows：客户区高决定可见行数；不接线时与改动前同值
    // ------------------------------------------------------------------

    @Test
    fun `客户区高小时行数减少_不小于物理高时与未接线同值`() {
        val heightPx = 2400
        val unwired = ScreenLayout.resolve(widthPx = 2395, heightPx = heightPx, densityDpi = 400, configFontPx = 18)
        val legacy = legacyResolve(2395, heightPx, 400, 18)
        assertEquals(legacy.rows, unwired.rows)

        val short = ScreenLayout.resolve(
            widthPx = 2395,
            heightPx = heightPx,
            densityDpi = 400,
            configFontPx = 18,
            contentWidthPx = 2400,
            windowWidthPx = 2400,
            windowHeightPx = 1000,
        )
        // 1000 / 35 = 28（行高 33 × 19/18 = 34.83 → 35）
        assertEquals(28, short.rows)
        assertEquals(35, short.lineHeight)

        val tallEnough = ScreenLayout.resolve(
            widthPx = 2395,
            heightPx = heightPx,
            densityDpi = 400,
            configFontPx = 18,
            contentWidthPx = 2400,
            windowWidthPx = 2400,
            windowHeightPx = 5000,
        )
        assertEquals(unwired.rows, tallEnough.rows, "窗口高 ≥ 物理高 ⇒ rows 与未接线同值（保持现行观感）")
    }

    // ------------------------------------------------------------------
    // 同尺：脚本 px 版面宽与屏上列数差不到 1 格（C-WINDOW-SEED 的验收算式）
    // ------------------------------------------------------------------

    @Test
    fun `同尺逆折算后核心可绘制宽与屏上列数差不到一格`() {
        val cases = listOf(
            intArrayOf(2400, 400, 18),
            intArrayOf(1080, 440, 18),
            intArrayOf(1440, 500, 24),
        )
        for (c in cases) {
            val contentWidthPx = c[0]
            val densityDpi = c[1]
            val configFontPx = c[2]
            // View 侧实际排版宽（设备 px）：满宽档（WindowX 折算后 ≥ 屏宽）。
            val viewLayout = ScreenLayout.resolve(
                widthPx = contentWidthPx - 5,
                heightPx = contentWidthPx,
                densityDpi = densityDpi,
                configFontPx = configFontPx,
                contentWidthPx = contentWidthPx,
                windowWidthPx = contentWidthPx,
            )
            val k = viewLayout.fontPixelSize.toFloat() / configFontPx
            // View.windowWidthScriptPx() 的算式：round(版面宽 / k)。
            val seedWindowX = (viewLayout.layoutWidthPx.toFloat() / k).roundToInt()
            val scriptShift = (configFontPx / 6).coerceAtLeast(2)
            val coreDrawablePx = seedWindowX - scriptShift
            val scriptCellPx = configFontPx / 2f * CellMetrics.CELL_PIXEL_FACTOR
            val columnsOnScreen = viewLayout.columns
            val columnsByCore = floor(coreDrawablePx / scriptCellPx).toInt()
            assertTrue(
                kotlin.math.abs(columnsOnScreen - columnsByCore) <= 1,
                "content=$contentWidthPx dpi=$densityDpi cfgFont=$configFontPx：屏上列数 " +
                    "$columnsOnScreen 与核心折行列数 $columnsByCore 差超过 1 格（同尺逆折算失效）",
            )
        }
    }
}
