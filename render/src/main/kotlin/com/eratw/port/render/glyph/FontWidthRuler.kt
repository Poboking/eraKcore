package com.eratw.port.render.glyph

import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.FontDisplayWidth
import com.eratw.port.render.Grid
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.font.GlyphFallback
import kotlin.math.abs
import kotlin.math.round

/**
 * **字体感知的排版尺** —— 本次「AA 画 / 老虎机字符画错位」修复的单一宽度来源。
 *
 * ## 修的是什么
 *
 * 原版按当前段的字体用 GDI+ 实测像素宽逐段累加定位
 * （`ConsoleStyledString.cs:25,82` 的 `Font = GetFont(style.Fontname)` +
 * `Width = sm.GetDisplayLength(Text, Font)`，`ConsoleDisplayLine.cs:66` 的 `num += node.Width`；
 * `StringMeasure.cs:42-72`）。本端口此前把宽度钉成一张**与字体无关**的静态 CP932 格数表
 * （`core/host/CellWidth.kt` 的 `isHalfWidth` → `Grid.cellWidth`），再用
 * `Paint.textScaleX` 把每个字形拉伸塞进那个格子：`SETFONT` 只换了字形长相，没换排版尺。
 * 于是两处必然错位：
 * - 块元素 `▉ U+2589` / `▂ U+2582` 在 CP932 表里是「表外字」⇒ 2 格，而随包等宽字体
 *   的 hmtx 给的是半角 500/1000 ⇒ 实际 1 格。红魔馆老虎机的图案每行恰好 25 个半角字符，
 *   在本尺下逐行 25~44 格乱跳，上框 82 个字符被算成 164 格还会被误折行。
 * - `SETFONT "Saitamaar"` 是比例字体：细空格 `U+2009` 真实 2.25px、发丝空格 `U+200A`
 *   真实 1.125px、`:` / `.` 真实 3.375px，静态表一律给 18px 或 9px（放大 8~16 倍）。
 *   AA 画靠这些字符做像素级微调，各行数量不同 ⇒ 膨胀量不同 ⇒ 整幅画散架。
 *
 * ## 口径
 *
 * 1. **先归一字体名**：`BundledFontFamilies.resolve` 把脚本侧请求名
 *    （`ＭＳ ゴシック` / `MS Gothic` / `黑体` / `Saitamaar` …）折到实际随包族名，
 *    该族名与 `fonts/<slug>-advance.txt` 的 `#family` 指令逐字相同 ⇒ 查得到表。
 * 2. **按码位取真实 advance**：`TextMetrics.advancePixelsExact` 从 §3 sidecar 取未截断的
 *    设计单元换算值，除以 [CellMetrics.cellPixelWidth] 得浮点半角格。
 * 3. **缺表 / 缺行一律退化到静态网格**：`CellMetrics.cellsOf` 给回原来的 1 / 2 / 4 格，
 *    不猜系统字体宽度（GFX-12 的确定性口径），也不把退化值当真实度量。
 *    所以 headless、黄金基准以及所有未接表的装置**逐值不变**。
 * 4. **整格吸附**：严格 1:2 的等宽族，半角 9px / 全角 18px 落在
 *    0.9615 / 1.923 格（半格宽 = 字号 / 2 × 1.04，见 `StringMeasure.cs:37`）。
 *    距最近整数不超过 [SNAP_TOLERANCE] 时吸附成整数，既保住既有「按格推进 +
 *    textScaleX 1.04 填满格」的观感，也让折行 / 命中 / 列区间仍是整数格。
 *    比例字体的亚格宽（0.12 / 0.36 格）不吸附 ⇒ 绘制侧得以按真实像素推进。
 *
 * ## 两条出口不许混
 *
 * - [advanceCells] / [widthCells]：**浮点格**，给真机绘制的逐字形推进用。
 * - [roundedCells] / [widthRoundedCells]：**整数格**，给折行预算、按钮命中、
 *   `DrawCmd` 列区间用（这些结构在契约里是 Int）。整串版本先浮点累加再一次取整，
 *   避免逐码位取整把比例字体的窄字符全抹成 0。
 *
 * TODO(verify)：整数格折行是 ADR-001 的重设计（原版默认是 TEXTRENDERER 的纯像素折行），
 * 比例字体下「整串取整的段宽」与「逐字取整的前缀宽度」不严格同式，只影响折行切点；
 * 语料里比例字体只用于整幅 AA 画（单段、不折行），要贴 PC 的纯像素折行须把列游标整体
 * 浮点化并同步重钉 `ButtonLayer` / `DisplayList` / `LineFolder`，属跨模块重设计。
 *
 * @property metrics §3 度量表的读取口与换算器（装配期可换成 Android assets 的读取口）
 * @property initialCellMetrics 生效字号 / 半格像素宽；换字号时经 [cellMetrics] 同批改写
 */
class FontWidthRuler(
    val metrics: TextMetrics = TextMetrics.classpath(),
    initialCellMetrics: CellMetrics = CellMetrics.DEFAULT,
) {

    /** 生效换算器。换字号必须同批改它（缓存按字号失效），否则推进量与真机绘制不同尺。 */
    var cellMetrics: CellMetrics = initialCellMetrics
        set(value) {
            if (value !== field) {
                field = value
                cache.clear()
            }
        }

    /**
     * 按「实际族 + 码位」缓存浮点格宽：折行与绘制每帧会问同一批码点几千次，
     * 每次都走 `FontSidecarCatalog.chainFor` + `FontAdvanceTable.advancePixels` 太贵。
     * 两层 Map 而不是打包 Long 键，避免族名哈希碰撞把两个字的读数混在一起。
     */
    private val cache = HashMap<String, HashMap<Int, Float>>(8)

    /**
     * 单码位的浮点半角格宽（真实 advance 优先，缺表/缺行退化到等宽网格）。
     *
     * @param fontName 段的请求字体名（可空；空名归一到默认族）
     */
    fun advanceCells(codePoint: Int, fontName: String): Float {
        val family = BundledFontFamilies.resolve(fontName).family
        val perFamily = cache.getOrPut(family) { HashMap(256) }
        val cached = perFamily[codePoint]
        if (cached != null) return cached
        val value = compute(codePoint, family)
        perFamily[codePoint] = value
        return value
    }

    /** 单码位的整数半角格宽（折行前缀 / 图形占位用）。 */
    fun roundedCells(codePoint: Int, fontName: String): Int =
        round(advanceCells(codePoint, fontName)).toInt()

    /** 整串的浮点半角格宽（TAB 先展开成 8 空格，口径同 `Grid.cells`）。 */
    fun widthCells(text: String, fontName: String): Float {
        val expanded = Grid.expandTabs(text)
        var total = 0f
        var i = 0
        while (i < expanded.length) {
            val codePoint = expanded.codePointAt(i)
            i += Character.charCount(codePoint)
            total += advanceCells(codePoint, fontName)
        }
        return total
    }

    /** 整串一次取整的整数半角格宽（段宽 / 列区间用）。 */
    fun widthRoundedCells(text: String, fontName: String): Int =
        round(widthCells(text, fontName)).toInt()

    /**
     * 投影成 `:core-vm` 的 [FontDisplayWidth] 契约，供**整数格全链**共用。
     *
     * 核心层折行（`LineBufferConsoleSink.layoutRows`）、渲染层二次折行（`LineFolder`）、
     * 列区间（`DisplayList`）、命中列（`ButtonLayer`）与适配层按钮切分（`ConsoleAdapter`）
     * 都吃这一个实例 —— 只要有一处还留着静态 `Grid.cells`，`SETFONT` 段在那一处就会
     * 与屏上推进量分家，字符画重新错位。装配点必须把**同一只** ruler 投影出来的实例
     * 同时交给 core 与 render 两侧。
     *
     * TAB 特判：核心层折行逐**码位**问 `codePointCells`，而 [widthCells] 是先
     * `Grid.expandTabs` 再量（8 个空格 × 半角 1 格 = 8 格）。若这里让 TAB 落进
     * `compute` → `CellMetrics.cellsOf(0x09)` = 1 格，就会把接线前 [CellWidth.displayCodePoint]
     * 的 8 格改窄 7 格，含 TAB 的制表行整体错位。绘制侧永远拿不到 TAB 码位
     * （`DisplayList.glyphKeys` 前的 `Grid.expandTabs` 已展开），故这条只属于整数格契约。
     */
    fun asDisplayWidth(): FontDisplayWidth = object : FontDisplayWidth {
        override fun codePointCells(codePoint: Int, fontName: String): Int =
            if (codePoint == '\t'.code) CellWidth.TAB_CELLS else roundedCells(codePoint, fontName)

        override fun cells(text: String, fontName: String): Int =
            widthRoundedCells(text, fontName)
    }

    private fun compute(codePoint: Int, family: String): Float {
        // 与真机 CanvasConsoleView.effectiveFamily 的缺字回落**同源**（都走 GlyphFallback）：
        // 请求族 → Sarasa → Unifont。链的成员与顺序只由 GlyphFallback.chainFor 定义，
        // 两处不再各写一遍（判据仍各自不同：这里问 sidecar 有没有行，那边问 Paint.hasGlyph）。
        // 逐跳问 sidecar 的 hasGlyph（表里有行 = 子集 cmap 有该码点），首命中即取它的 advance，
        // 于是「这一字形实际由哪一族画出」与「按哪一族的表推进」不会分家。
        val requested = BundledFontFamilies.byFamily(family) ?: BundledFontFamilies.default
        for (candidate in GlyphFallback.chainFor(requested)) {
            val px = metrics.advancePixelsExact(codePoint, candidate.family, cellMetrics.fontPixelSize)
            if (px == null || !px.isFinite() || px < 0f) continue
            val cells = px / cellMetrics.cellPixelWidth
            val nearest = round(cells)
            return if (abs(cells - nearest) <= SNAP_TOLERANCE) nearest else cells
        }
        // 全链都没有表 / 都没有这一行：回落静态网格，读数与接线前逐值相同。
        return cellMetrics.cellsOf(codePoint).toFloat()
    }

    companion object {
        /**
         * 整格吸附容差（半角格）。0.10 同时满足：
         * 半角 9px / 9.36px = 0.9615（差 0.0385，吸附 1）、
         * 全角 18px / 9.36px = 1.923（差 0.077，吸附 2）、
         * Saitamaar 发丝空格 1.125px / 9.36px = 0.120（差 0.120，**不**吸附 0）。
         */
        const val SNAP_TOLERANCE: Float = 0.10f
    }
}
