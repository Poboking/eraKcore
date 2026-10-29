package com.eratw.port.render.glyph

/**
 * 一次光栅化的产物：字形的像素度量 + 位图（ARGB `0xAARRGGBB`，行主序）。
 *
 * 度量一律是**该字号下**的像素值（字号从 [GlyphRaster.rasterize] 的入参来，
 * spec: FontFactory.cs:9,18 的字典键含字号），不是任何"默认字号"的值。
 *
 * @property advancePixels 水平前进量（像素）；转半角格只走 [CellMetrics.pixelsToCells]
 * @property lsbPixels 左侧像素偏移（left side bearing，可负）；没有度量表时为 0
 * @property ascentPixels 基线以上高度（像素）
 * @property descentPixels 基线以下高度（像素）
 * @property bitmapWidth 位图宽（像素）
 * @property bitmapHeight 位图高（像素）
 * @property bitmap ARGB 像素，长度 `bitmapWidth * bitmapHeight`；空位图给零长数组而不是 null
 */
class RasterizedGlyph(
    val advancePixels: Int,
    val ascentPixels: Int,
    val descentPixels: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val bitmap: IntArray,
    val lsbPixels: Int = 0,
)

/**
 * 字形光栅化器：`:render` 侧的抽象边界。Android 真实现（平台字体度量 + 字形图集）后续由
 * `:render-canvas` 注入；本波只提供确定性假实现 [StaticGlyphRaster]。
 *
 * ## 契约
 *
 * 1. **不许抛**：字体不可用、码位缺字、内部异常一律返回 null，由 [GlyphCache] 走回退链。
 *    这条照抄 `FontFactory.GetFont` 的 `try`/`catch` 后 `return null`（spec: `FontFactory.cs:52-54`）。
 * 2. 字体名**区分大小写**：原版所有字体查找都是 `FontFamily.Name == text` 的序号比较
 *    （spec: `FontFactory.cs:40`、`FunctionMethodCreator.cs:3493,3502` 的 CHKFONT）。
 *    注意官方不一致：`StringStyle` 的相等判定是**忽略大小写**的
 *    （spec: `StringStyle.cs:59,73`），所以相邻同字体不同大小写的文本段会被合并成一条
 *    `ConsoleStyledString`，但底层的 Font 对象与度量仍按两个桶各走各的。
 *    本波沿用「缓存桶 = 序号口径」，见 [GlyphCache] 的「分桶口径」一节。
 * 3. 同一入参重复调用必须返回**内容一致**的结果（禁时间、禁随机、禁字体回写状态）。
 * 4. **字号是入参的一部分**：同一个 (字体, 样式, 码位) 在不同字号下必须给不同的度量，
 *    实现不许把字号吞掉。原版 `FontFactory` 的字典键是 `(字体名, 字号, 样式)`
 *    （spec: `FontFactory.cs:9,18-25`），本接口只是把同一口径下推到 glyph 粒度。
 */
interface GlyphRaster {

    /**
     * 回退链的第二跳：默认字体名。
     * 空字体名先归一到它（spec: `FontFactory.cs:13-17`）；配置字体不可用时原版在启动阶段就把
     * `Config.FontName` 整体换成内置的 `BIZ UDGothic`（spec: `Config.cs:501-523`），
     * 所以"默认字体"是一个具体可用的字体名，而不是运行期每次现查。
     */
    val defaultFontName: String

    /**
     * 用 [fontName] 在 [sizePx] 字号下光栅化 [codePoint] 的 [styleMask] 字形。
     *
     * @param styleMask 脚本侧样式位：1=粗体 2=斜体 4=删除线 8=下划线
     *   （spec: `Process.cs:1512-1527` 写、`FunctionMethodCreator.cs:3712-3727` 读，
     *   与 [com.eratw.port.render.Grid.STYLE_BOLD] 等常量同口径）。
     *   注意它与 GDI+ `FontStyle` 的枚举值**不同**（那边 4=Underline、8=Strikeout），
     *   所以样式位到平台绘制标记的映射必须显式做，禁止把 [styleMask] 直接当 `FontStyle` 传下去。
     * @param sizePx 字号（像素）。非正值由调用方（[GlyphCache]）先归一成正数再传进来，
     *   实现按 [CellMetrics] 的下限口径处理即可（spec: `Config.cs:431-434` 把字号 8 当底线）。
     * @param codePoint UTF-16 码元（口径见 [CellMetrics.cellsOf]）
     * @return 光栅结果；该字体不可用或不覆盖该码位时返回 null（触发回退，不许抛）
     */
    fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph?
}
