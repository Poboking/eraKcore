package com.eratw.port.render.glyph

/**
 * [StaticGlyphRaster] 跑完 [GlyphRasterConformance] 的六条契约（本波唯一的 `GlyphRaster` 实现）。
 *
 * 覆盖表默认 = 「只登记 [CellMetrics.DEFAULT_FONT_NAME] 且覆盖全部码位」，与该类
 * `fonts` 形参的缺省值同口径；`metrics` 用缺省的 `TextMetrics.gridOnly` —— 六条契约都
 * 不该依赖真度量表，否则「没接表的后端」就通不过自己的套件。
 */
class StaticGlyphRasterConformanceTest : GlyphRasterConformance() {

    override val familyName: String = CellMetrics.DEFAULT_FONT_NAME

    override fun raster(fonts: List<FontCoverage>): GlyphRaster = StaticGlyphRaster(fonts = fonts)
}
