package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.math.roundToInt

/**
 * 「屏幕像素 → 控制台几何」的**唯一解算点**（纯 JVM、零 Android 依赖）。
 *
 * 真机 View（`CanvasConsoleView`）与桌面预览（`tools/uipreview`）都必须从这里取版面。
 *
 * ## 单向派生（2026-11-03 重构：断掉「页行数 ↔ 字号」的反馈环）
 *
 * 旧实现让字号由**当前页内容行数**反推（`PageFitter.fit`），而页行数又由字号、折行、
 * 图片展平共同决定 —— 两个方程互为输入，输入栏显隐/翻页/QoL 地图重印都会改变字号，
 * 表现为「修一处、坏两处」。现在口径反转为：
 *
 * 1. **字号与行距是输入**：来自 emuera.config 的 `FONT SIZE` / `LINE HEIGHT`
 *    （经 [EffectiveConfig] 夹取后的生效值），乘设备密度折算成设备像素；
 * 2. **行数是输出**：`rows = 可见高 / 行高`，内容超过 [rows] 行就滚动（原版窗口语义）；
 * 3. **列数是输出**：`columns = 可绘制宽 / 半角格宽`；
 * 4. 页面内容、图片展平结果**永远不参与**字号/行高/列数推导。
 *
 * 唯一保留的宽度约束是 [minColumns]（列数守护）：屏宽不足以铺下 N 个半角格时把字号
 * 压到「刚好铺下 N 列」，避免 128 格地图行被折成「主行 + 尾巴行」（
 * `ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:10` 的 `#DIMS COLORCOLUMNS,128`）。
 * 它只依赖屏宽，不依赖页面内容 —— 不构成环。守护生效时 [resolve] 的返回值里
 * `fontPixelSize` 就是**实际生效字号**，调用方据此可以告诉玩家「字号被列数保护压到多少」。
 *
 * ## 公式出处（原版）
 *
 * - 半角格宽 `fontDisplaySize = FontSize / 2f * 1.04f`（spec: `StringMeasure.cs:37`）
 *   ⇒ 字号反推 `font ≈ width / (columns * 0.52)`；
 * - 行高/字号 = 19/18（spec: `ConfigData.cs:66-67`），但以配置的 `LINE HEIGHT` 为准；
 * - 字号 <8 抬 8、行高 < 字号抬字号（spec: `Config.cs:431-434`、`:436-439`）由
 *   [CellMetrics] 构造期夹取，本类不重复实现。
 *
 * @property fontPixelSize 生效字号（像素，已过 [CellMetrics] 下限夹取）。
 * @property lineHeight 生效行高（像素，≥ [fontPixelSize]）。
 * @property columns 可绘制的列数（半角格）：`可绘制宽 / 半角格宽`，向下取整、至少 1。
 * @property metrics 与绘制/折行侧共享的度量实例（必须整链同一实例，`ConsoleFeed` 构造期要求）。
 * @property rows 一屏能放下的行数：`可见高 / 行高`；0 = 未给可见高（预览路径）。
 * @property layoutWidthPx 客户区含右端预留的像素宽（设备 px）：窗口宽给定且窄于物理内容区时
 *   是 `min(窗口宽, 物理内容宽)`，否则是物理内容宽；未给物理内容宽时退回 [resolve] 的
 *   `widthPx` 实参（未接线 ⇒ 调用方的可绘制宽）。
 * @property contentInsetLeftPx 客户区在屏上的水平居中偏移（设备 px）：
 *   `(物理内容宽 − [layoutWidthPx]) / 2` 的整数除结果，余 1 px 归左；满宽/未接线 ⇒ 0。
 */
data class ScreenLayout(
    val fontPixelSize: Int,
    val lineHeight: Int,
    val columns: Int,
    val metrics: CellMetrics,
    val rows: Int = 0,
    val layoutWidthPx: Int = 0,
    val contentInsetLeftPx: Int = 0,
) {

    /** 会话/控制台窗口宽（半角格）：新开会话的 `consoleWidth` / `ConsoleScreen.clientWidth`。 */
    val clientWidth: Int get() = columns

    /** 画布像素宽（`ScreenComposer.canvasWidthPx` 同式：`cellsToPixels(clientWidth)`）。 */
    val canvasWidthPx: Int get() = metrics.cellsToPixels(columns)

    /** 画布装得下的行数（向下取整，至少 1）；画布高由消费方另给（可 ≠ heightPx × 整行）。 */
    fun heightLinesFor(heightPx: Int): Int = if (heightPx <= 0) 1 else (heightPx / lineHeight).coerceAtLeast(1)

    companion object {

        /**
         * 缺省列数守护（半角格）= **136**（2400px 长边、配置 18 时约 34px ≈ 13.6dp；
         * 守护会把字号再压到 33px，见 [resolve]）。
         *
         * 下限由语料的地图宽度锁死：最宽地图行是 128 半角格
         * （`ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:10` 的 `#DIMS COLORCOLUMNS,128`），
         * 110 列档会把它折成「主行 + 尾巴行」（玩家反馈的「红魔馆一层地图残缺」）。
         * 128 之上留 8 格余量：地图行可能带行首标记/边框，且字号取整后不给「贴边少一格」留风险。
         * PC 基准是 199 列（`emuera.config:12,22` 的 1800px + 字号 18），手机照抄只有约 7.7dp。
         */
        const val DEFAULT_COLUMNS: Int = 136

        /** 拿不到屏参时的设计基准字号 18dp（spec: `ConfigData.cs:66`）。 */
        const val BASE_FONT_DP: Float = 18f

        /** 行高/字号 = 19/18（spec: `ConfigData.cs:66-67`；配置未给 `LINE HEIGHT` 时用）。 */
        const val LINE_HEIGHT_RATIO: Float = 19f / 18f

        /** dp 基准密度（Android `DisplayMetrics` 口径：density = densityDpi / 160）。 */
        const val DENSITY_BASE_DPI: Int = 160

        /**
         * PC 逻辑 px → 手机 dp 的折算比 = **0.75**（18px → 13.5dp）。
         *
         * 这是**移植可读性口径**，不是物理换算：按 96dpi 物理尺寸折算，18px ≈ 30dp
         * （手机上只有约 26 列）；取 0.75 后 18px 落在 13.5dp —— 2400px/400dpi 屏上
         * 落 34px（列数守护 136 生效时取 33px），与旧 136 列档的现行观感一致，是缺省不跳档的关键。
         * 屏宽不足以铺下 [DEFAULT_COLUMNS] 时仍由列数守护压小字号（见 [resolve]）。
         */
        const val PC_PIXEL_TO_DP: Float = 0.75f

        /**
         * 固定列模式（`widthOnlyMetrics` 的历史公式，逐字符原样；**只给桌面预览**
         * `tools/uipreview` 的「--columns N」用，真机运行期不再走这条）。
         *
         * `font = widthPx / (columns * 1.04 / 2 + 1/6 + 0.03)`，`roundToInt` 收整；
         * `widthPx <= 0`（拿不到真实屏宽）或反推值 < [CellMetrics.MIN_FONT_PIXEL_SIZE]
         * 时退 `BASE_FONT_DP * densityDpi / 160`。
         *
         * @param widthPx 可绘制宽（像素）；≤0 = 拿不到真实屏宽（mock / 未布局），走 18dp 兜底。
         * @param densityDpi 设备密度（`DisplayMetrics.densityDpi`；兜底字号 = `18dp` 落像素）。
         * @param columns 反推目标列数（半角格，≤0 抬 1）；缺省 136。
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
         * **真机唯一入口**：字号/行距是输入，列数与行数是输出（推导见类文档「单向派生」）。
         *
         * 夹取顺序固定为：
         * 1. `font = round(configFont × density × [PC_PIXEL_TO_DP] × fontScale)`，下限 [CellMetrics.MIN_FONT_PIXEL_SIZE]；
         * 2. `line = round(font × configLineHeight / configFont)`，下限 = font；
         * 3. 列数守护：若 `font` 铺不下 [minColumns] 列，把 font 压到
         *    `floor(width / (minColumns × 半角格宽))` 并复核，行高按同一比例重算
         *    （[minColumns] ≤ 0 或宽 ≤ 0 = 不守护）；
         * 4. `columns = floor(可绘制宽 / 半角格宽)`；`rows = floor(height / 行高)`；
         * 5. 客户区宽/高（原版窗口语义，spec: `MainWindow.cs:687-688` / `:726`）：
         *    `layoutWidthPx = min(窗口宽, 物理内容宽)`（未给窗口宽 = 物理内容宽），
         *    `contentInsetLeftPx = (物理内容宽 - layoutWidthPx) / 2`（整数除，余 1 px 归左），
         *    列数按窗口宽内可绘制宽算，行数按客户区高算；三新参数全 ≤0 ⇒ 与本次改动前逐值相同。
         *
         * 第 5 步**不推断窗口宽**：`windowWidthPx <= 0` 就是「未接线」，不许拿屏幕尺寸冒充客户区宽。
         *
         * 第 3 步只会**压小**字号，不会把字号抬高 —— 玩家把 `FONT SIZE` 调大时，
         * 只有屏宽足够（或列数守护调低）才会真正变大；被压小是「保地图」的显式取舍，
         * 不是设置失效。第 3 步用 floor 而非 round：round 会把 cap 抬高 1px，
         * 反而让实际列数比 [minColumns] 少一列（2400px 屏上实测 136 → 135）。
         * 列数守护只看**物理可绘制宽** `widthPx`：窗口宽只决定客户区有多宽，不反过来压字号。
         *
         * @param widthPx 可绘制宽（像素；调用方传**已扣 shift** 的绘制宽，≤0 = 未布局）
         * @param heightPx 可见高（像素，≤0 = 未布局）
         * @param densityDpi 设备密度（`DisplayMetrics.densityDpi`）
         * @param configFontPx emuera.config 的生效字号（脚本逻辑像素，如 18）
         * @param configLineHeightPx emuera.config 的生效行高（脚本逻辑像素，如 19；≤0 按 19/18）
         * @param minColumns 列数守护下限（≤0 = 不守护）
         * @param fontScale 壳层「显示大小」倍率（1 = 跟随 `FONT SIZE`；非正/非有限 = 1）
         * @param contentWidthPx 客户区物理宽（设备 px，**含**调用方扣掉的右端预留）；
         *   ≤0 = 未提供 ⇒ 视为 `widthPx`（未接线路径，字段读数逐值不变）
         * @param windowWidthPx 客户区容宽（设备 px，已按 [CellMetrics.scriptPixels] 折算）；
         *   ≤0 = 未接线 ⇒ 满宽，**不得**自行推断窗口宽
         * @param windowHeightPx 客户区容高（设备 px，已折算）；≤0 = 未接线 ⇒ 沿用物理内容高
         */
        fun resolve(
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            configFontPx: Int,
            configLineHeightPx: Int = -1,
            minColumns: Int = DEFAULT_COLUMNS,
            fontScale: Float = 1f,
            contentWidthPx: Int = 0,
            windowWidthPx: Int = 0,
            windowHeightPx: Int = 0,
        ): ScreenLayout {
            val density = densityDpi.toFloat() / DENSITY_BASE_DPI
            val ratio = if (configLineHeightPx > 0 && configFontPx > 0) {
                configLineHeightPx.toFloat() / configFontPx
            } else {
                LINE_HEIGHT_RATIO
            }
            val scale = if (fontScale > 0f && fontScale.isFinite()) fontScale else 1f
            var font = (configFontPx.coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE) *
                density * PC_PIXEL_TO_DP * scale).roundToInt()
                .coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE)
            if (minColumns > 0 && widthPx > 0) {
                // floor 反推：`0.52 × font ≤ width / N` 才保证 floor(width / (0.52 × font)) ≥ N。
                // 再用与实际列数**完全相同**的算式复核，防浮点边界少一列。
                var capFont = (widthPx /
                    (minColumns * CellMetrics.CELL_PIXEL_FACTOR / 2f)).toInt()
                    .coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE)
                while (capFont > CellMetrics.MIN_FONT_PIXEL_SIZE &&
                    columnsFor(widthPx, capFont) < minColumns
                ) {
                    capFont--
                }
                if (capFont < font) font = capFont
            }
            val line = (font * ratio).roundToInt().coerceAtLeast(font)
            val metrics = CellMetrics(font, line)
            // 客户区几何（原版 MainWindow 语义，spec: `MainWindow.cs:687-688` / `:726`）。
            // 窗口宽/高已由调用方按 CellMetrics.scriptPixels 折算成设备 px；≤0 = 未接线 ⇒ 满宽/整高。
            val hasContentWidth = contentWidthPx > 0
            // 1) 右端预留 = 调用方扣掉的 shift（`widthPx` 已是可绘制宽）；未给物理宽 ⇒ 0。
            val reservedPx = if (hasContentWidth) (contentWidthPx - widthPx).coerceAtLeast(0) else 0
            // 2) 版面客户区宽（含预留）：夹到物理内容宽；未给物理宽 ⇒ 退回调用方的可绘制宽。
            val layoutWidthPx = when {
                !hasContentWidth -> widthPx
                windowWidthPx > 0 -> windowWidthPx.coerceAtMost(contentWidthPx)
                else -> contentWidthPx
            }
            // 3) 水平居中偏移：左右均分、整数除的余 1 px 归左（只由 layoutWidthPx 派生）。
            val contentInsetLeftPx = if (hasContentWidth) {
                ((contentWidthPx - layoutWidthPx) / 2).coerceAtLeast(0)
            } else {
                0
            }
            // 4) 版面内可绘制宽（去掉右端预留）⇒ 列数；未布局（可绘宽 ≤0）的分支与旧实现同形。
            val drawablePx = (layoutWidthPx - reservedPx).coerceAtLeast(0)
            val columns = when {
                drawablePx > 0 -> (drawablePx / metrics.cellPixelWidth).toInt().coerceAtLeast(1)
                widthPx > 0 -> 1
                else -> minColumns.coerceAtLeast(1)
            }
            // 5) 可见行数：客户区高（`WindowY - LineHeight`，调用方算好传入）决定可见行；未接线沿用旧式。
            val rows = if (windowHeightPx > 0) {
                (windowHeightPx.coerceAtMost(heightPx) / metrics.lineHeight).coerceAtLeast(1)
            } else if (heightPx > 0) {
                (heightPx / metrics.lineHeight).coerceAtLeast(1)
            } else {
                1
            }
            return ScreenLayout(
                metrics.fontPixelSize,
                metrics.lineHeight,
                columns,
                metrics,
                rows,
                layoutWidthPx,
                contentInsetLeftPx,
            )
        }

        /**
         * 列数守护复核用的列数读数：按**物理可绘制宽** `widthPx` 算（守护只保「屏宽铺得下 N 列」，
         * 不随客户区窗口宽压字号，见 [resolve] 第 3 步与类文档）。
         */
        private fun columnsFor(widthPx: Int, fontPx: Int): Int {
            val cellPx = fontPx / 2f * CellMetrics.CELL_PIXEL_FACTOR
            return if (cellPx <= 0f) 1 else (widthPx / cellPx).toInt().coerceAtLeast(1)
        }
    }
}
