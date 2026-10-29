package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.math.roundToInt

/**
 * 「屏幕像素 → 控制台几何」的共享推导（UP3 从 render-canvas 的 `CanvasConsoleView` 下沉）。
 *
 * 纯 JVM、零 Android 依赖：真机 View（`CanvasConsoleView`）与桌面预览（`tools/uipreview`
 * 的设备档案解析）共用同一份公式，保证「预览算出的版面 = 真机版面」。两类消费方：
 *
 * - **既有会话不改宽**：`CanvasConsoleView.applyPageFit` 里 console 的 `clientWidth`
 *   构造期定死（改宽要换 sink），fit 模式只把推导结果喂给绘制度量；
 * - **新开会话**（uipreview serve / 换设备档案）：`clientWidth` 是 boot 参数
 *   （`GameSession.consoleWidth`），可直接采 [clientWidth]。
 *
 * ## 两种模式（与 `CanvasConsoleView` 构造参数 `screenFitEnabled` 一一对应）
 *
 * 1. **固定列**（[fixedColumns]，`screenFitEnabled=false` 的 `widthOnlyMetrics` 口径，
 *    `CanvasConsoleView.kt:265-269`）：目标列数定字号，一行正好铺 [columns] 个半角格；
 *    行高 = 字号 × 19/18，与页面行数无关 ⇒ 长页面超出屏幕（需要滚动）。
 * 2. **适应屏幕**（[fit]，`applyPageFit` 的 `PageFitter.metricsFor` 口径，
 *    `CanvasConsoleView.kt:276-301` → `PageFitter.fit`）：一页行数 × 行高 = 可见高、
 *    列数铺满可见宽；行数由**当前页内容**量出（`ConsoleScreen.pageExtentRows`），
 *    字号取「铺满这一页的最大值」，下限 [PageFitter.MIN_READABLE_FONT_PX]。
 *
 * ## 公式出处（原版）
 *
 * - 半角格宽 `fontDisplaySize = FontSize / 2 * 1.04`（spec: `StringMeasure.cs:37`）⇒
 *   字号反推 `font ≈ width / (columns * 0.52)`；固定列模式分母带 `+1/6+0.03` 的
 *   0.2 像素级偏置，是 `widthOnlyMetrics` 的原样（不修正，行为锁死）。
 * - 行高/字号 = 19/18（spec: `ConfigData.cs:66-67`；`PageFitter.LINE_HEIGHT_RATIO` 同源）。
 * - 字号 <8 抬 8、行高 < 字号抬字号（spec: `Config.cs:431-434`、`:436-439`）由
 *   [CellMetrics] 构造期夹取，本类不重复实现。
 * - 拿不到真实屏宽（`widthPx <= 0`：mock context / View 未布局）或反推值低于
 *   [CellMetrics.MIN_FONT_PIXEL_SIZE] 时，退设计基准 `18dp`（`BASE_FONT_DP` 按密度落像素，
 *   spec: `ConfigData.cs:66`）—— 与 `CanvasConsoleView` 构造期兜底同解
 *   （`CanvasConsoleView.kt:216-228`、`:267`）。
 *
 * @property fontPixelSize 生效字号（像素，已过 [CellMetrics] 下限夹取）。
 * @property lineHeight 生效行高（像素，≥ [fontPixelSize]）。
 * @property columns 推导目标列数（半角格）：固定列模式 = 入参目标；fit 模式 = 铺满可见宽的推导值。
 * @property metrics 与绘制/折行侧共享的度量实例（必须整链同一实例，`ConsoleFeed` 构造期要求）。
 */
data class ScreenLayout(
    val fontPixelSize: Int,
    val lineHeight: Int,
    val columns: Int,
    val metrics: CellMetrics,
) {

    /** 会话/控制台窗口宽（半角格）：新开会话的 `consoleWidth` / `ConsoleScreen.clientWidth`。 */
    val clientWidth: Int get() = columns

    /** 画布像素宽（`ScreenComposer.canvasWidthPx` 同式：`cellsToPixels(clientWidth)`）。 */
    val canvasWidthPx: Int get() = metrics.cellsToPixels(columns)

    /** 画布装得下的行数（向下取整，至少 1）；画布高由消费方另给（可 ≠ heightPx × 整行）。 */
    fun heightLinesFor(heightPx: Int): Int = if (heightPx <= 0) 1 else (heightPx / lineHeight).coerceAtLeast(1)

    companion object {

        /** 缺省目标列数（半角格）：2400px 长边下字号约 42px ≈ 15dp，手机可读档（`CanvasConsoleView.kt:1998` 同值）。 */
        const val DEFAULT_COLUMNS: Int = 110

        /** 拿不到屏参时的设计基准字号 18dp（spec: `ConfigData.cs:66`；`CanvasConsoleView.BASE_FONT_DP` 同值）。 */
        const val BASE_FONT_DP: Float = 18f

        /** 行高/字号 = 19/18（spec: `ConfigData.cs:66-67`；`PageFitter.LINE_HEIGHT_RATIO` 同源）。 */
        const val LINE_HEIGHT_RATIO: Float = 19f / 18f

        /** dp 基准密度（Android `DisplayMetrics` 口径：density = densityDpi / 160）。 */
        const val DENSITY_BASE_DPI: Int = 160

        /**
         * 固定列模式（`CanvasConsoleView.widthOnlyMetrics` 的下沉，公式逐字符原样）：
         * `font = widthPx / (columns * 1.04 / 2 + 1/6 + 0.03)`，`roundToInt` 收整；
         * `widthPx <= 0`（拿不到真实屏宽）或反推值 < [CellMetrics.MIN_FONT_PIXEL_SIZE]
         * 时退 `BASE_FONT_DP * densityDpi / 160`（= 构造期/mock 兜底同解）。
         *
         * 分母的 `+1/6+0.03` 是 `widthOnlyMetrics` 的原样偏置（约 0.2 像素，2016 年
         * `CanvasConsoleView` 首版留下），行为锁死不改；与「无偏置的
         * `width / (columns * 0.52)`」在取整边界可差 1px，见 ScreenLayoutTest 的对拍表。
         *
         * @param widthPx 可绘制宽（像素）；≤0 = 拿不到真实屏宽（mock / 未布局），走 18dp 兜底。
         * @param densityDpi 设备密度（`DisplayMetrics.densityDpi`；兜底字号 = `18dp` 落像素）。
         * @param columns 反推目标列数（半角格，≤0 抬 1）；缺省 110（手机可读档）。
         */
        fun fixedColumns(
            widthPx: Int,
            densityDpi: Int = DENSITY_BASE_DPI,
            columns: Int = DEFAULT_COLUMNS,
        ): ScreenLayout {
            val target = columns.coerceAtLeast(1)
            val byWidth = widthPx / (target * CellMetrics.CELL_PIXEL_FACTOR / 2f + 1f / 6f + 0.03f)
            val font = if (byWidth < CellMetrics.MIN_FONT_PIXEL_SIZE) {
                BASE_FONT_DP * (densityDpi.toFloat() / DENSITY_BASE_DPI)
            } else {
                byWidth
            }
            val metrics = CellMetrics(font.roundToInt(), (font * LINE_HEIGHT_RATIO).roundToInt())
            return ScreenLayout(metrics.fontPixelSize, metrics.lineHeight, target, metrics)
        }

        /**
         * 适应屏幕模式（`CanvasConsoleView.applyPageFit` → `PageFitter.metricsFor` 的下沉）：
         * 一页行数 × 行高 = 可见高、列数铺满可见宽、字号取「铺满这一页的最大值」。
         * 反推与夹取全部委托 [PageFitter.fit]（单一实现，别处不复刻）。
         *
         * @param widthPx 可绘制宽（像素，≤0 按 1 算）。
         * @param heightPx 可见高（像素，≤0 按 1 算）。
         * @param pageRows 这一页实际行数（0/负 = 未知，落 [PageFitter.FALLBACK_PAGE_ROWS]；
         *   由调用方量出，见 `ConsoleScreen.pageExtentRows`）。
         * @param minColumns 列数下限（既有会话传 console 的 `clientWidth` —— 宽度上限用
         *   实际列数，字号抬上去后字形才不横向溢出；新会话用缺省 84）。
         */
        fun fit(
            widthPx: Int,
            heightPx: Int,
            pageRows: Int,
            minColumns: Int = PageFitter.DEFAULT_MIN_COLUMNS,
        ): ScreenLayout {
            val f = PageFitter.fit(widthPx, heightPx, pageRows, minColumns)
            val metrics = CellMetrics(f.fontPixelSize, f.lineHeight)
            return ScreenLayout(metrics.fontPixelSize, metrics.lineHeight, f.columns, metrics)
        }
    }
}
