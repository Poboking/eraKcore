package com.eratw.port.render.glyph

/**
 * 一个字体在本波假光栅化器里的覆盖范围。
 *
 * @property name 字体名（序号口径比较，见 [GlyphRaster] 契约第 2 条）
 * @property coversAllCodePoints 是否覆盖全部码位（默认真字体用它近似"装了就能画"）
 * @property codePoints 精确覆盖集（[coversAllCodePoints] 为 false 时生效）
 * @property unsupportedCodePoints 显式缺字集：**优先级最高**，用来构造回退链用例
 */
data class FontCoverage(
    val name: String,
    val coversAllCodePoints: Boolean = false,
    val codePoints: Set<Int> = emptySet(),
    val unsupportedCodePoints: Set<Int> = emptySet(),
) {

    /** 该字体能否画出 [codePoint]（缺字集优先于全覆盖集）。 */
    fun covers(codePoint: Int): Boolean = when {
        unsupportedCodePoints.contains(codePoint) -> false
        coversAllCodePoints -> true
        else -> codePoints.contains(codePoint)
    }
}

/**
 * 本波用的**确定性**光栅化器：没有 Android 字体栈，就用「§3 度量表 + 查表位图」造可复现的度量与位图。
 *
 * ## 度量从哪来
 *
 * 前进量与 lsb 一律经 [metrics]（[TextMetrics] → CONTRACT-M4-C §3 的 advance 表，
 * 设计单元按 `design * sizePx / unitsPerEm` 换算）；**字号是入参**，同一码位在不同字号下
 * 得到不同度量（spec: `FontFactory.cs:9,18-25` 的字典键含字号）。
 * [metrics] 默认是 [TextMetrics.gridOnly]（没接任何表）→ 前进量按
 * [missingGlyphAdvancePixels] 的缺字分档给（半角 1 / 全角 2 / 零宽 0 格，增补平面按布局口径
 * 4 格），与设备上有无系统回退字无关；出处由 [TextMeasurement.provenance] /
 * [TextMetrics.degradedReason] 说明，不许把退化值当真实度量宣称（契约 §0.5）。
 *
 * ## 缺字
 *
 * 两道判据都在 [rasterize] 里，返回 null 让 [GlyphCache] 走回退链（不抛）：
 * 1. 字体没登记 / 在 [FontCoverage] 里被显式排除；
 * 2. 该族**接了表**但表里没有这一行 —— §3 规定 cmap 没有的码点不出行，缺行即缺字。
 * 没接表时第 2 条不生效（不知道有没有 ≠ 没有，退化成网格值而不是否决）。
 *
 * 于是「整条回退链一张表都没有」这条路径仍然出图（[MetricProvenance.DEGRADED_GRID] 的口径：
 * 退化不等于否决），advance 由 [missingGlyphAdvancePixels] 给 —— 只看码位、与设备装了什么
 * 系统字体无关（GFX-12），零宽档 0 步进、增补平面按已登记的 4 格、其余按半角 1 / 全角 2 格。
 *
 * ## 位图
 *
 * 产出的位图内容只为把「不同桶的条目互不串味」这件事断言出来，不是渲染结果：
 * 像素值 = alpha 段 `0xFF000000` 叠加「样式位放在第 16..23 位」再叠加「码元放在第 0..15 位」，
 * 于是同码位不同样式必然是不同位图，真机实现应整体覆盖本类。
 *
 * 每次调用都会往 [calls] 追加一条 `字体名|样式位|码元|字号` 流水（按调用顺序、不含时间与随机），
 * 单测靠它断言回退链的跳数与顺序，而不是靠实现自己回读。
 *
 * @property cellMetrics 默认字号的像素 ↔ 半角格换算器（其余字号向 [metrics] 取）
 * @property fonts 字体覆盖表（字体名 → [FontCoverage]）
 * @property metrics 真实度量入口（§3 表 + 退化口径都在它里面）
 */
class StaticGlyphRaster(
    override val defaultFontName: String = CellMetrics.DEFAULT_FONT_NAME,
    val cellMetrics: CellMetrics = CellMetrics.DEFAULT,
    fonts: List<FontCoverage> = listOf(FontCoverage(defaultFontName, coversAllCodePoints = true)),
    val metrics: TextMetrics = TextMetrics.gridOnly(cellMetrics),
) : GlyphRaster {

    val fonts: Map<String, FontCoverage> = fonts.associateBy { it.name }

    private val callLog: MutableList<String> = ArrayList()

    val calls: List<String>
        get() = callLog.toList()

    /** 调用次数（回退链断言用）。 */
    val callCount: Int
        get() = callLog.size

    /**
     * 查表光栅化。字体未登记、码位缺字、或接了表但表里没有这一行 → null
     * （[GlyphRaster] 契约第 1 条：不许抛）。
     */
    override fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? {
        callLog += "$fontName|$styleMask|$codePoint|$sizePx"
        val coverage = fonts[fontName] ?: return null
        if (!coverage.covers(codePoint)) {
            return null
        }
        if (!metrics.covers(codePoint, fontName)) {
            return null
        }
        val converter = metrics.cellMetricsFor(sizePx)
        // 缺度量行时的 advance 走 `cellsOf`（CP932 字节口径：BMP 表外字 2 格、代理对逐码元
        // 各 2 格 = 4 格），**不是** [CellMetrics.fallbackAdvancePixels]（原版整串测量口径：
        // 组合符 0 格、增补平面 2 格）。两者在两处不同，这是**已拍板维持现状**的口径，理由：
        //  - 布局侧 `Grid.cells` / `Grid.cellWidth` 用的就是字节口径（PC 基准 199 列 =
        //    `EmueraConsole.cs:3255` 的 `GetByteCount`，见 `fixtures/golden/baseline/
        //    pc-golden-root.txt:61/:253` 的 99×U+2501 = 1797px 读数），改一格就是改整张版面；
        //  - 字形 advance 与布局格数**必须同值**，否则「画 18px、占 37px」会在屏上留缝/压字；
        //    单改这一侧比两侧都宽更糟。
        // 要改成原版测量口径就必须**两侧一起**改（`Grid` + 本处 + `TextMetrics.measureCells`），
        // 并重录 PC 基准与 E2E 自产快照 —— 那是独立一波，不属于本缺陷的收口面。
        // TODO(verify)：真机截图对拍 U+0300..U+036F 组合符与增补平面 emoji 的屏上宽度后定案。
        val advance = metrics.advancePixels(codePoint, fontName, sizePx)
            ?: missingGlyphAdvancePixels(codePoint, converter)
        val bitmapHeight = converter.lineHeight
        val bitmapWidth = if (advance > 0) advance else 0
        val pixel = pixelOf(styleMask, codePoint)
        return RasterizedGlyph(
            advancePixels = advance,
            ascentPixels = converter.fontPixelSize,
            descentPixels = bitmapHeight - converter.fontPixelSize,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            bitmap = IntArray(bitmapWidth * bitmapHeight) { pixel },
            lsbPixels = metrics.lsbPixels(codePoint, fontName, sizePx),
        )
    }

    /**
     * 回退链里**没有任何一张表**给出这一行时的 advance（像素）—— 确定性、只看码位，
     * 与设备上装了什么系统字体无关（GFX-12）。口径直接取 [CellMetrics.fallbackAdvancePixels]
     * （= `fallbackClassOf` 的档位 × `cellsToPixels`），**与表驱动路径
     * [TextMetrics.resolve] 用同一条分档**，改档位时只有这一处要跟着改。
     *
     * - **零宽档**（Mn/Me 组合附加符号、变体选择符）：0 步进。原版整串测量
     *   （spec: `StringMeasure.cs:63-65` 的 `MeasureCharacterRanges` 一次量整串、
     *   `:44-46` 的 `TextRenderer.MeasureText` 同样整段交给 GDI），组合符并进基字簇、
     *   不另占位。改前这里走 `cellsOf`（表外字一律全角 = 2 格），于是含组合符的串在
     *   [GlyphCache.measureCells] 上比 [TextMetrics.measure] 多出 2 格/字
     *   （「量宽按并簇、绘制按占位」分叉）；改后两跳对同一串给同一读数。
     * - **半角档 / 全角档**：1 格 / 2 格，与分档表逐值同值（改前改后读数不变）。
     * - **增补平面**：按 [CellMetrics.cellsOf] 的 **4 格**（两个 UTF-16 码元各 2 格），
     *   与布局侧 [com.eratw.port.render.Grid.cells] 的推进口径一致 ——
     *   `FontAudit2Test` 的「代理对按一个码位进桶且格数与布局一致」钉的就是这条（4 格 / 37px），
     *   绘制侧 `ScreenComposer.drawRun` 也按码元各 2 格推进；分档表
     *   （[CellMetrics.fallbackClassOf]）给增补平面 2 格，那是**缺字档位表**的另一条口径，
     *   `FallbackWidthTest` 已登记「度量 2 格 / 布局 4 格」的粒度差。
     *   本跳取布局口径（4 格）⇒ 绘制推进与量宽同档，不制造新的分叉。
     *
     * TODO(verify)：原版一个代理对在 GDI+ 眼里是一个字形，`.notdef` 的前进量由字体自带，
     * C# 侧没有逐码位的缺字 advance 可对拍（`ConsoleStyledString.cs:60-62` 只做段切分，
     * 绘制整段交给 `DrawString`；`Utils.cs` 的 `ToPixelf` 只换算脚本侧 px 参数，与字形无关）
     * ⇒ 增补平面的 4 格与零宽档的 0 步进都要在真机截图验收时复核。
     */
    private fun missingGlyphAdvancePixels(codePoint: Int, converter: CellMetrics): Int {
        if (converter.fallbackClassOf(codePoint) == FallbackWidthClass.ZERO) {
            return 0
        }
        return converter.cellsToPixels(converter.cellsOf(codePoint))
    }

    /** 假位图的像素值：alpha 恒 `FF`，样式位与码元各占一段，保证"不同桶不同内容"。 */
    private fun pixelOf(styleMask: Int, codePoint: Int): Int =
        (0xFF shl 24) or ((styleMask and 0xFF) shl 16) or (codePoint and 0xFFFF)
}
