package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics

/**
 * 「一页正好铺满一屏」的度量解算器（纯 JVM，可直接单测）。
 *
 * ## 为什么在引擎层做
 *
 * 原版 Emuera 是固定窗口：窗口 760×480、字号 18、行高 19（`ConfigData.cs:56,57,66,67`）
 * ⇒ 一页 = **25 行**（480÷19）、每行 84 半角格（760÷9）。脚本按这个版面排版，
 * 所以「一页」就是 25 行。
 *
 * 手机屏幕比例和 4:3 差很远，如果只按**屏宽**反推字号（旧做法），可见行数会随输入栏
 * 显隐在 22~25 之间跳 —— 输入栏一展开只剩 22 行，脚本那一页 25 行就有 3 行被顶到屏幕上方，
 * 玩家必须上滑才能看全（主人实测：存档界面看不全）。
 *
 * 本解算器把**可见区高度**也纳入反推：
 *   lineHeight = 可见高 ÷ 一页行数，font = lineHeight ÷ (19/18)，
 *   columns    = 可见宽 ÷ 半角格宽（半角格宽 = font ÷ 2 × 1.04，`StringMeasure.cs:37`）
 * 于是「一页行数 × 行高」恰好等于可见高、列数自动铺满可见宽，一页既不用滚也不用裁剪。
 *
 * ## 与 [CellMetrics] 的分工
 *
 * 本类只做**反推**（给定的像素区 → 字号/行高/列数）；实际字号的上下限夹取
 * （字号 <8 抬到 8、行高 < 字号抬到字号）仍由 [CellMetrics] 按其 KDoc 口径处理，
 * 所以这里返回的 [PageFit.fontPixelSize] 还要过一遍 [CellMetrics] 才生效。
 */
data class PageFit(
    /** 反推出的字号（像素，已取整且 ≥ [PageFitter.MIN_FONT_PX]）。 */
    val fontPixelSize: Int,
    /** 反推出的行高（像素，= 可见高 ÷ 一页行数，已取整）。 */
    val lineHeight: Int,
    /** 反推出的目标列数（半角格）：铺满可见宽。 */
    val columns: Int,
    /** 一屏行数（= 入参 [PageFitter.fit] 的 pageRows，夹到可用范围）。 */
    val rows: Int,
)

object PageFitter {

    /**
     * 拿不到当前页行数时的兜底行数（原版版面：窗口 480 ÷ 行高 19 = 25 行，`ConfigData.cs:57,67`）。
     *
     * **它不是「一页固定 25 行」** —— 正常路径永远用 [com.eratw.port.render.view.ConsoleScreen.pageExtentRows]
     * 量出来的实际行数；只有控制台还是空的、或量不出内容边界时才用它。
     */
    const val FALLBACK_PAGE_ROWS: Int = 25

    /** 兼容旧名（壳层偏好 / 调试口还在引用）。 */
    const val DEFAULT_PAGE_ROWS: Int = FALLBACK_PAGE_ROWS

    /** 行高 / 字号 = 19/18（`ConfigData.cs:66-67`；`CanvasConsoleView.LINE_HEIGHT_RATIO` 同值）。 */
    const val LINE_HEIGHT_RATIO: Float = 19f / 18f

    /** 半角格宽 / 字号 = 1.04/2（`StringMeasure.cs:37` 的 `Size / 2f * 1.04f`）。 */
    const val HALF_CELL_RATIO: Float = 1.04f / 2f

    /** 字号下限：原版 <8 抬到 8（`Config.cs:431-434`）。 */
    const val MIN_FONT_PX: Int = 8

    /**
     * 字号可读下限 = **30px**（2026-10-25 主人第二次反馈后定的档）。
     *
     * 依据：这台 2400×1080 屏 density≈3，现代手机正文 14-16sp ≈ 42-48px；
     * 「参考现代手机规格、小上几号」≈ 30-32px。原先的 18px（原版 PC 字号）在手机上
     * 只有 6sp 左右 —— 存档页那种 30+ 行的页面会缩成一堆小字挤在左边，正是主人指出的问题。
     *
     * 代价：一页内容超过「1080 ÷ 30×1.0556 ≈ 34 行」时就装不下了（存档页 57 行 ⇒ 要滚动）。
     * 这是物理限制：57 行 × 30px 行高 = 1808px > 1080px。**字号优先，装不下就滚动**。
     */
    const val MIN_READABLE_FONT_PX: Int = 30

    /** 一页行数的合理范围：少于 12 行没必要缩、多于 200 行按 200 算（再长就滚）。 */
    val PAGE_ROWS_RANGE: IntRange = 12..200

    /**
     * 反推一页的度量 —— **「一页内容整屏铺满 + 字号取最大」**。
     *
     * 口径（2026-10-25 重写；旧版把一页行数当固定常数，是设计错误）：
     * 1. 行高 = 可见高 ÷ **这一页实际行数**（由调用方量出来，见 `ConsoleScreen.pageExtentRows`）
     *    ⇒ 这一页正好铺满可见高，一行不剩一行不缺；
     * 2. 字号随行高 = 行高 ÷ (19/18)，于是**页面行数越少字号越大**（短页面吃满屏）；
     * 3. 字号再受**宽度**约束：至少保证 [minColumns] 列（默认 84 = 原版 WindowX 760 ÷ 半角格 9.36），
     *    否则宽屏手机上字号会被高度抬得过大、横向只剩几列；
     * 4. 低于 [MIN_READABLE_FONT_PX]（原版 18px）时不再缩 —— 该页真的超过一屏容量，只能滚动。
     *
     * @param availableWidthPx 游戏区**可绘制宽**（像素）
     * @param availableHeightPx 游戏区**可绘制高**（像素）
     * @param pageRows 这一页**实际**行数（0/负数 = 未知，落 [FALLBACK_PAGE_ROWS]）
     * @param minColumns 列数下限（默认 84，原版版面；宽屏下保底可读）
     */
    fun fit(
        availableWidthPx: Int,
        availableHeightPx: Int,
        pageRows: Int = 0,
        minColumns: Int = DEFAULT_MIN_COLUMNS,
    ): PageFit {
        val width = availableWidthPx.coerceAtLeast(1)
        val height = availableHeightPx.coerceAtLeast(1)
        val rows = (if (pageRows <= 0) FALLBACK_PAGE_ROWS else pageRows)
            .coerceIn(PAGE_ROWS_RANGE.first, PAGE_ROWS_RANGE.last)
        // ① 高度给出的字号：这一页铺满可见高
        val fontByHeight = (height.toDouble() / rows / LINE_HEIGHT_RATIO).toInt()
        // ② 宽度给出的字号上限：保证至少 minColumns 列
        val fontByWidth = (width.toDouble() / minColumns / HALF_CELL_RATIO).toInt()
        val font = minOf(fontByHeight, fontByWidth)
            .coerceAtLeast(MIN_READABLE_FONT_PX)
            .coerceAtLeast(MIN_FONT_PX)
        // 行高跟字号成比例（原版 19/18），不四舍五入到 0
        val lineHeight = (font * LINE_HEIGHT_RATIO).toInt().coerceAtLeast(font)
        val halfCell = font * HALF_CELL_RATIO
        val columns = if (halfCell <= 0f) minColumns else (width / halfCell).toInt().coerceAtLeast(minColumns)
        return PageFit(font, lineHeight, columns, rows)
    }

    /** 列数下限缺省值：原版 `WindowX=760 ÷ 半角格 9.36 ≈ 81`，取 84 与 `ConfigData` 的整列口径一致。 */
    const val DEFAULT_MIN_COLUMNS: Int = 84

    /**
     * 便捷入口：直接产出可交给渲染层的 [CellMetrics]（已按 [CellMetrics] 的口径夹取）。
     */
    fun metricsFor(
        availableWidthPx: Int,
        availableHeightPx: Int,
        pageRows: Int = 0,
        minColumns: Int = DEFAULT_MIN_COLUMNS,
    ): CellMetrics {
        val fit = fit(availableWidthPx, availableHeightPx, pageRows, minColumns)
        return CellMetrics(fit.fontPixelSize, fit.lineHeight)
    }
}
