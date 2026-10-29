package com.eratw.port.render.glyph

import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.FontSidecarReader
import com.eratw.port.render.Grid

/**
 * 一次串级文本测量的**出处**（契约 §0.5：等宽网格与真实字体度量是两条口径，不许互相冒充，
 * 所以每条读数都必须自己说清是哪一条）。
 *
 * - [ADVANCE_TABLE]：整串每个码位都从 §3 的 advance 表取到了前进量 —— 这是真实度量那条线。
 * - [DEGRADED_GRID]：一条表都没有（成品未接入 / 宿主没给读取口），整串按 [CellMetrics.cellsOf]
 *   的等宽网格给读数 —— 这是**退化**，不是真实度量，[TextMeasurement.reason] 必须带上原因。
 * - [MIXED_TABLE_GRID]：有表但个别码位缺行（§3：cmap 没有的码点不出行），缺的那些按
 *   [CellMetrics.fallbackClassOf] 的缺字分档给确定性 advance（GFX-12）——同样是退化，
 *   计数在 [TextMeasurement.degradedCodePoints]。
 */
enum class MetricProvenance {
    ADVANCE_TABLE,
    MIXED_TABLE_GRID,
    DEGRADED_GRID,
}

/**
 * [TextMetrics] 的产物：像素宽 + 半角格宽 + 出处。
 *
 * @property pixels 整串前进量（像素，**一次**向零截断）
 * @property cells 整串半角格数（由 [pixels] 经 [CellMetrics.pixelsToCells] 量化一次得到）
 * @property provenance 读数出处（不许把 DEGRADED/MIXED 当真实度量宣称）
 * @property degradedCodePoints 落在网格退化上的码位数
 * @property tableFamily 第一张命中的表家族名；[MetricProvenance.DEGRADED_GRID] 时为 null
 * @property reason 退化原因（真实度量可用时为 null）
 * @property sizePx 本次测量使用的生效字号（已经过 [TextMetrics.normalizeSize]）
 */
class TextMeasurement(
    val pixels: Int,
    val cells: Int,
    val provenance: MetricProvenance,
    val degradedCodePoints: Int,
    val tableFamily: String?,
    val reason: String?,
    val sizePx: Int,
)

/**
 * 单**码位**的度量读数，显式区分「随包字体有字形」与「无字形，按宽度档给确定性 advance」。
 *
 * 这就是 GFX-12 的那条分界线：`hasGlyph == true` 时 [advancePixels] 来自 §3 度量表；
 * `false` 时来自 [CellMetrics.fallbackAdvancePixels]，**与设备上有没有系统回退字无关**
 * （度量层从不查系统字体，所以 A 设备与 B 设备读到同一序列）。
 *
 * @property codePoint 完整码位（增补平面是一个码位，不是两个代理项）
 * @property hasGlyph 随包字体的回退链里是否有该码位的行（§3：cmap 没有即缺行）
 * @property advancePixels 该码位的前进量（像素，**逐码位**向零截断一次）
 * @property cells 该码位占的半角格（有字形时由 [advancePixels] 量化；缺字形时是档位值）
 * @property fallbackClass 缺字形时命中的档位；有字形时为 null
 */
class CodePointMetric(
    val codePoint: Int,
    val hasGlyph: Boolean,
    val advancePixels: Int,
    val cells: Int,
    val fallbackClass: FallbackWidthClass?,
)

/**
 * 真实字体度量与文本测量（GLYPH-KEY-SIZE / M4-REALCHECK ②③）。
 *
 * ## 数据来源
 *
 * 度量表是 CONTRACT-M4-C §3 的 sidecar（`fonts/<family-slug>-advance.txt`，advance 与 lsb 都是
 * **字体设计单元**），解析与回退链由 `:core-vm` 的 `com.eratw.port.core.font` 包（[FontAdvanceTable] /
 * [FontSidecarCatalog]）单点实现（依赖方向 `:render -> :core-vm`，两边共用一份解析器才不会各自漂移；
 * `GGETTEXTSIZE` 在 core 侧量的也是同一张表）。本类只负责渲染层的两条换算：
 * 设计单元 → 像素、像素 → 半角格。
 *
 * ## 换算只发生一次
 *
 * `design * sizePx / unitsPerEm` 用 `Float` 累加（与原版 GDI+ 的 float 中间量同精度，
 * spec: `FunctionMethodCreator.cs:6585-6587`），整串求和完才截断一次；
 * **读取时不许二次近似**（契约 §3 第 3 条），量化到整格只发生在 [CellMetrics.pixelsToCells]
 * 这一步（像素 → 半角格的那条线）。
 *
 * ## 字号分桶
 *
 * [defaultSizePx] 以外的字号一律按该字号自己的 [CellMetrics] 换算
 * （原版 `FontFactory` 的字典键含字号，spec: `FontFactory.cs:9,18-25`），
 * 所以同一个类可以服务多个字号，**没有"一个实例只服务一个字号"的约定**。
 * 入参字号 `<= 0` 表示调用方没给（`com.eratw.port.render.GlyphKey.size` 的兼容默认值是 0），
 * 归一到 [defaultSizePx]；**`1..7` 原样保留**——原版 `GGETTEXTSIZE` 的 emSize 一路喂给
 * `new Font(name, emSize, …)`（spec: `FunctionMethodCreator.cs:6584`），没有下限检查，
 * `Config.cs:431-434` 的"小于 8 抬到 8"钳的是**配置值 FontSize**，不是这个函数入参。
 * 只有像素 ↔ 半角格的换算器 [cellMetricsFor] 沿用 [CellMetrics] 的下限（它有自己的钳位）；
 * 缓存侧的 [GlyphCache.normalizeSize] 则是对齐配置域的，两者域不同，不要互相套用。
 *
 * ## 缺表时的行为（确定性退化，不许抛、不许冒充）
 *
 * 成品缺失（本波 c-fontsub 还在产 render 模块 resources 下的 fonts 目录，那里暂时没有文件）时
 * [measure] 走 [MetricProvenance.DEGRADED_GRID]，数值取 [CellMetrics.cellsToPixels] 的网格宽，
 * 同时把可读原因从 [reason] 与 [FontSidecarCatalog.lastReason] 带出来：调用方一眼能看出
 * 这个数不是真实度量。任何路径都不抛异常（抛出去会让一条打印语句打死游戏）。
 *
 * ## 缺字形：确定性宽度回退（GFX-12）
 *
 * 随包子集只覆盖 5,268/8,076 个 required 码点，缺的 2,808 个在真机上回落到**系统字体**，
 * 宽度随设备变 ⇒ 混排错位、`HTML_STRINGLEN` 与折行读数不稳定。本类的口径是：缺字形时
 * **不查系统字体**，按 [CellMetrics.fallbackClassOf] 的码位档位给 advance（组合符 0 格、
 * 半角档 1 格、其余 2 格）。于是同一字符串在「有系统回退字」与「没有」两台设备上读到同一序列，
 * 出处仍如实标成 [MetricProvenance.MIXED_TABLE_GRID]（不是真实度量）。
 * 逐码位的判定结果由 [resolve] / [advanceSequence] 暴露（`CodePointMetric.hasGlyph`）。
 *
 * @property catalog §3 表的目录（回退链、缓存、退化原因都在那里）
 * @property cellMetrics 默认字号的换算器；同时也是"未显式给字号"时用的那一个
 */
class TextMetrics(
    val catalog: FontSidecarCatalog = FontSidecarCatalog(),
    val cellMetrics: CellMetrics = CellMetrics.DEFAULT,
) {

    /** 未显式给字号时的归一目标（= [cellMetrics] 的生效字号）。 */
    val defaultSizePx: Int get() = cellMetrics.fontPixelSize

    private val converters = HashMap<Int, CellMetrics>()

    init {
        converters[cellMetrics.fontPixelSize] = cellMetrics
    }

    /** 该字号的换算器（同字号复用同一实例，保证 [CellMetrics.cellPixelWidth] 的浮点值稳定）。 */
    fun cellMetricsFor(sizePx: Int): CellMetrics {
        val normalized = normalizeSize(sizePx)
        return converters.getOrPut(normalized) { CellMetrics(normalized) }
    }

    /** 字号归一：`<= 0`（调用方没给）→ [defaultSizePx]；其余原样。 */
    fun normalizeSize(sizePx: Int): Int = if (sizePx <= 0) defaultSizePx else sizePx

    /**
     * 整串测量：真实 advance 优先，缺表/缺行按缺字分档给确定性 advance，并在结果里如实标出处。
     *
     * 逐**码位**取表（§3 一码点一行），代理对合成一个码位；测量前先把 TAB 展成 8 个空格
     * （spec: `StringMeasure.cs:57-62`：GRAPHICS 模式测宽前 `s.Replace("\t", "        ")`，
     * 同 [com.eratw.port.render.Grid.expandTabs] 与 [GlyphCache.measureCells]）。
     * 不展开会让含 TAB 的串在**第二条**入口下少 7 个空格宽（§3 表里没有 U+0009 这一行，
     * 于是 TAB 落进"缺行"，只按 1 格算，而折行用的 `Grid.cells` 按 8 格留位）。
     * 缺行码位按 [CellMetrics.fallbackAdvancePixels] 的档位补（GFX-12：组合符 0 格、
     * 半角 1 格、其余 2 格，含增补平面按**一个码位** 2 格），**不查系统字体** ——
     * 同一串在有/无系统回退字的设备上读数相同。
     * 串级前进量与 `GGETTEXTSIZE`（core 侧）同一条公式（整串求和后**一次**向零截断，
     * spec: `FunctionMethodCreator.cs:6585-6587`），两处可以互相冒充的地方只在**单位**：
     * 这里额外给出半角格（折行要用），core 只给像素（位图/RESULT 要用）。
     * TODO(verify)：core 的 `GGETTEXTSIZE` 走 GDI+ `MeasureString`（`:6585`），对 TAB 是否
     * 也是 8 空格宽无处取证（基准没有含 TAB 的测量断言），所以含 TAB 的串上两条入口的
     * **像素**读数暂时不同；要贴 PC 需实机对拍 GDI+ 的制表符前进量。
     */
    fun measure(text: String, fontName: String, sizePx: Int): TextMeasurement {
        val size = normalizeSize(sizePx)
        val converter = cellMetricsFor(size)
        val chain = catalog.chainFor(fontName)
        val measured = Grid.expandTabs(text)
        var total = 0f
        var degraded = 0
        var i = 0
        while (i < measured.length) {
            val codePoint = measured.codePointAt(i)
            i += Character.charCount(codePoint)
            val advance = firstAdvance(chain, codePoint, size)
            if (advance == null) {
                degraded++
                total += converter.fallbackAdvancePixels(codePoint).toFloat()
            } else {
                total += advance
            }
        }
        // 向零截断，不做防御性抬升（原版 (long) 强转同样是截断，spec: FunctionMethodCreator.cs:6587；
        // 负前进量按 ADR-005 原样带上，不"顺手修好"）。
        val pixels = total.toInt()
        val provenance = when {
            chain.isEmpty() -> MetricProvenance.DEGRADED_GRID
            degraded > 0 -> MetricProvenance.MIXED_TABLE_GRID
            else -> MetricProvenance.ADVANCE_TABLE
        }
        return TextMeasurement(
            pixels = pixels,
            cells = converter.pixelsToCells(pixels),
            provenance = provenance,
            degradedCodePoints = degraded,
            tableFamily = chain.firstOrNull()?.family,
            reason = reasonOf(provenance, chain, degraded, fontName),
            sizePx = size,
        )
    }

    /**
     * 单码位解析：**有字形**取 §3 表，**无字形**取 [CellMetrics.fallbackClassOf] 的确定性档位。
     *
     * 这是 [measure] 的逐字版本，也是"缺字"这件事在度量层的唯一判据（`hasGlyph`）。
     * 与 [measure] 的差别只在取整时机：这里是逐码位截断（绘制/图集布局要每字自己的前进量），
     * [measure] 是整串求和后截断一次（原版 `MeasureString` 的口径，
     * spec: `FunctionMethodCreator.cs:6585-6587`）。两条都不许互相冒充（契约 §0.5）。
     */
    fun resolve(codePoint: Int, fontName: String, sizePx: Int): CodePointMetric {
        val size = normalizeSize(sizePx)
        val converter = cellMetricsFor(size)
        val advance = firstAdvance(catalog.chainFor(fontName), codePoint, size)
        if (advance != null) {
            val pixels = advance.toInt()
            return CodePointMetric(codePoint, true, pixels, converter.pixelsToCells(pixels), null)
        }
        val fallback = converter.fallbackClassOf(codePoint)
        return CodePointMetric(
            codePoint = codePoint,
            hasGlyph = false,
            advancePixels = converter.cellsToPixels(fallback.cells),
            cells = fallback.cells,
            fallbackClass = fallback,
        )
    }

    /**
     * 整串的逐码位读数序列（TAB 先展成 8 个空格，口径同 [measure]）。
     *
     * GFX-12 的验收入口：同一串在两台设备上必须给出**逐项相同**的
     * `(codePoint, hasGlyph, advancePixels, cells, fallbackClass)` 序列。
     */
    fun advanceSequence(text: String, fontName: String, sizePx: Int): List<CodePointMetric> {
        val measured = Grid.expandTabs(text)
        val out = ArrayList<CodePointMetric>(measured.length)
        var i = 0
        while (i < measured.length) {
            val codePoint = measured.codePointAt(i)
            i += Character.charCount(codePoint)
            out += resolve(codePoint, fontName, sizePx)
        }
        return out
    }

    /**
     * 出处对应的可读原因（真实度量可用时是 null）。
     *
     * 三档各自给对的默认文案：旧写法只有一句"chain 里没有任何表覆盖所请求字体"当兜底，
     * 于是在 [MetricProvenance.MIXED_TABLE_GRID]（表在、只是个别码位缺行）且目录里没有
     * 上一轮留存的 [FontSidecarCatalog.lastReason] 时，退化原因会把"缺行"说成"整表没有"
     * ——首差时按这句话去查文件会查错方向（`FontAdvanceTable.parse` 的缺行语义见 §3：
     * cmap 没有的码点不出行）。
     */
    private fun reasonOf(
        provenance: MetricProvenance,
        chain: List<FontAdvanceTable>,
        degradedCodePoints: Int,
        requestedFamily: String,
    ): String? = when (provenance) {
        MetricProvenance.ADVANCE_TABLE -> null
        MetricProvenance.MIXED_TABLE_GRID -> catalog.lastReason
            ?: "§3 度量表缺行：$degradedCodePoints 个码位在回退链（" +
            chain.joinToString("/") { it.family } +
            "）里没有行，按缺字分档（组合符 0 格 / 半角 1 格 / 其余 2 格）给确定性 advance"
        MetricProvenance.DEGRADED_GRID -> catalog.lastReason
            ?: "§3 度量表不可用：回退链里没有任何表（请求族「$requestedFamily」，空名已归一到默认族）"
    }

    /** 整串前进量（像素）。出处不在这个返回值里 —— 要判真假用 [measure]。 */
    fun measurePixels(text: String, fontName: String, sizePx: Int): Int = measure(text, fontName, sizePx).pixels

    /** 整串半角格数（真机折行的取数入口；整串一次量化，与 [GlyphCache.measureCells] 的逐字口径可差 1 格）。 */
    fun measureCells(text: String, fontName: String, sizePx: Int): Int = measure(text, fontName, sizePx).cells

    /**
     * 单码位前进量（像素，向零截断一次）；回退链上没有任何表覆盖它时返回 null。
     *
     * null 是**信息**不是失败：调用方（[StaticGlyphRaster]）据此走缺字回退链或网格退化。
     * 要"缺字形时也给一个确定性 advance"用 [resolve]（GFX-12），本函数保持"表里没有就是 null"。
     */
    fun advancePixels(codePoint: Int, fontName: String, sizePx: Int): Int? {
        val size = normalizeSize(sizePx)
        val advance = firstAdvance(catalog.chainFor(fontName), codePoint, size) ?: return null
        return advance.toInt()
    }

    /** 单码位 lsb（像素，向零截断）；没有度量表或缺行时给 0（§3 的 lsb 只随 advance 出行）。 */
    fun lsbPixels(codePoint: Int, fontName: String, sizePx: Int): Int {
        val size = normalizeSize(sizePx)
        for (table in catalog.chainFor(fontName)) {
            val lsb = table.lsbPixels(codePoint, size.toLong())
            if (lsb != null) {
                return lsb.toInt()
            }
        }
        return 0
    }

    /**
     * 该字体的回退链里是否存在覆盖 [codePoint] 的表（§3：cmap 没有该码点即缺行 → 缺字，
     * 回退链该跳下一跳）。没有接入任何表时给 true —— "不知道有没有这个字"不能当成"没有"，
     * 那是 [MetricProvenance.DEGRADED_GRID] 的口径（退化不等于否决）。
     */
    fun covers(codePoint: Int, fontName: String): Boolean {
        val chain = catalog.chainFor(fontName)
        if (chain.isEmpty()) {
            return true
        }
        for (table in chain) {
            if (table.covers(codePoint)) {
                return true
            }
        }
        return false
    }

    /** 最近一次"没拿到表"的可读原因；真实度量可用时也可能是上一轮的旧值，故只在出处非 ADVANCE_TABLE 时采信。 */
    fun degradedReason(): String? = catalog.lastReason

    private fun firstAdvance(chain: List<FontAdvanceTable>, codePoint: Int, sizePx: Int): Float? {
        for (table in chain) {
            val advance = table.advancePixels(codePoint, sizePx.toLong())
            if (advance != null) {
                return advance
            }
        }
        return null
    }

    companion object {
        /**
         * 从 classpath 读 §3 成品的读取口。
         *
         * 成品位置（契约 §3 / §2 c-fontsub）：`render/src/main/resources/fonts/<family-slug>-advance.txt`，
         * 打进 `:render` 的 jar 后就是 classpath 资源 `fonts/...`。
         * 读不到返回 null —— 那是**缺失**，由 [measure] 确定性退化，不抛。
         */
        fun classpathReader(loader: ClassLoader = TextMetrics::class.java.classLoader): FontSidecarReader =
            FontSidecarReader { path ->
                val stream = loader.getResourceAsStream(path)
                stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            }

        /**
         * 接 classpath 成品目录的默认实例。
         *
         * 本波成品还不存在（render 模块 resources 下的 fonts 目录归 c-fontsub），所以这个入口
         * 建出来的实例一律走 [MetricProvenance.DEGRADED_GRID]，[degradedReason] 给得出原因。
         */
        fun classpath(cellMetrics: CellMetrics = CellMetrics.DEFAULT): TextMetrics {
            val catalog = FontSidecarCatalog(defaultFamily = CellMetrics.DEFAULT_FONT_NAME)
            catalog.reader = classpathReader()
            return TextMetrics(catalog, cellMetrics)
        }

        /**
         * 用一段 §3 文本直接建表并挂上目录 —— 装配期与单测的入口（单测**夹具**就走这条）。
         *
         * @param alias 额外别名（脚本侧请求名，例如「黑体」由子集代画时把子集表同时挂到该名下）
         * @throws com.eratw.port.core.font.FontSidecarFormatException 文本里有未知构造（异常文案带行号）
         */
        fun fromSidecarText(
            text: String,
            alias: String? = null,
            cellMetrics: CellMetrics = CellMetrics.DEFAULT,
        ): TextMetrics {
            val catalog = FontSidecarCatalog(defaultFamily = CellMetrics.DEFAULT_FONT_NAME)
            catalog.reader = classpathReader()
            val table = FontAdvanceTable.parse(text)
            catalog.put(table, alias)
            return TextMetrics(catalog, cellMetrics)
        }

        /**
         * 纯退化实例（不接任何表）：等价于"成品缺失"的环境，读数出处一律 [MetricProvenance.DEGRADED_GRID]。
         *
         * 注意这条与旧口径的区别：退化值来自 [CellMetrics.cellsToPixels]，与等宽网格同源，
         * 但**出处被显式标成 DEGRADED_GRID**，调用方拿不到"真实度量"的借口（契约 §0.5）。
         */
        fun gridOnly(cellMetrics: CellMetrics = CellMetrics.DEFAULT): TextMetrics =
            TextMetrics(FontSidecarCatalog(defaultFamily = CellMetrics.DEFAULT_FONT_NAME), cellMetrics)
    }
}
