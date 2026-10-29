package com.eratw.port.render.glyph

import com.eratw.port.render.GlyphKey

/**
 * 字形由回退链的哪一跳提供（链的定义见 [GlyphCache]）。
 *
 * - [REQUESTED]：请求字体自己画出来了（空字体名先归一到默认字体，那一跳也算请求字体，
 *   spec: `FontFactory.cs:13-17`）
 * - [DEFAULT]：请求字体不可用或不覆盖该码位，由默认字体补上
 *   （对应原版启动期的字体替换，spec: `Config.cs:501-523`）
 * - [NOTDEF]：两跳都拿不到字形，画方框占位
 */
enum class GlyphSource {
    REQUESTED,
    DEFAULT,
    NOTDEF,
}

/**
 * 缓存下来的一个字形：度量 + 位图。
 *
 * 实例身份即缓存身份 —— 同一桶在未被淘汰期间 [GlyphCache.get] 必返同一实例
 * （对应原版「同一 (字体名, 字号, 样式) 只持有一个 Font 对象」的复用口径，
 * spec: `FontFactory.cs:9,18-25,42,48`）。因此本类故意不做结构化 `equals`，
 * 单测用 `assertSame` 断言复用、用 `assertNotSame` 断言淘汰与**跨字号不串桶**。
 *
 * @property key 当初的**请求键**（原样保留，含大小写、空字体名与未归一的 `size`；
 *   缓存桶可能已归一，见 [GlyphCache.bucketOf] 的口径）
 * @property servedFont 实际出图的字体名；[GlyphSource.NOTDEF] 时为默认字体名
 * @property servedBy 回退链命中的第几跳
 * @property sizePx 本条字形的**生效字号**（已归一：`<= 0` 换成默认字号，低于
 *   [CellMetrics.MIN_FONT_PIXEL_SIZE] 抬到下限，见 [GlyphCache.normalizeSize]）——
 *   度量与位图都是这个字号的，光栅路径与方框路径报的也必须是同一个值
 * @property advanceCells 半角格前进量 —— **折行/脏矩形只认这个**（换算入口 [CellMetrics.pixelsToCells]）
 * @property advancePixels 像素前进量（真实度量走 §3 的 advance 表，缺表时是 [CellMetrics] 的网格退化值，
 *   出处见 [TextMetrics.measure]）
 * @property lsbPixels 左侧像素偏移（§3 的 lsb，向零截断；没有度量表时是 0）
 * @property ascentPixels 基线以上像素高度（退化实现给字号，spec: `ConfigData.cs:66` 口径的占位值）
 * @property descentPixels 基线以下像素高度 = 行高 - 字号（行高保证不小于字号，
 *   spec: `Config.cs:436-439`），所以该值恒非负
 * @property bitmapWidth 位图宽（像素）
 * @property bitmapHeight 位图高（像素）
 * @property bitmap ARGB 行主序位图；[GlyphSource.NOTDEF] 时是空心方框
 */
class GlyphEntry(
    val key: GlyphKey,
    val servedFont: String,
    val servedBy: GlyphSource,
    val sizePx: Int,
    val advanceCells: Int,
    val advancePixels: Int,
    val lsbPixels: Int = 0,
    val ascentPixels: Int,
    val descentPixels: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val bitmap: IntArray,
) {

    /** 是否走了方框兜底（缺字）。 */
    val isNotdef: Boolean
        get() = servedBy == GlyphSource.NOTDEF

    /** 是否由默认字体代画（含方框兜底，它们都用默认字体名）。 */
    val isFallback: Boolean
        get() = servedBy != GlyphSource.REQUESTED

    override fun toString(): String =
        "GlyphEntry(key=$key, servedBy=$servedBy, servedFont=$servedFont, sizePx=$sizePx, advanceCells=$advanceCells)"
}
