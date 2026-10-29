package com.eratw.port.uipreview

import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.MetricProvenance
import com.eratw.port.render.glyph.FallbackWidthClass
import com.eratw.port.render.glyph.StaticGlyphRaster
import com.eratw.port.render.glyph.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AwtGlyphRaster] 的单元口径（CONTRACT-UP2 验收①的单元面）：
 * advance 只认度量旁表、位图几何照 Static 契约、确定性、缺字四路降级。
 * 布局逐位对拍（整链）在 [InkLayoutParityTest]。
 */
class AwtGlyphRasterTest {

    /** 工具装配同款度量：classpath 成品 + Saitamaar 替代族（PreviewRenderer.SUBSTITUTE_FONT_FAMILY）。 */
    private fun toolMetrics(cellMetrics: CellMetrics = CellMetrics.DEFAULT): TextMetrics =
        TextMetrics(
            com.eratw.port.core.font.FontSidecarCatalog(defaultFamily = CellMetrics.DEFAULT_FONT_NAME).apply {
                reader = TextMetrics.classpathReader()
                substituteFamily = PreviewRenderer.SUBSTITUTE_FONT_FAMILY
            },
            cellMetrics,
        )

    @Test
    fun `advance 与度量旁表逐值一致 位图几何照 Static 契约`() {
        val cellMetrics = CellMetrics(18, 19)
        val metrics = toolMetrics(cellMetrics)
        val raster = AwtGlyphRaster(cellMetrics = cellMetrics, metrics = metrics)
        val converter = metrics.cellMetricsFor(18)
        for (codePoint in intArrayOf('A'.code, 'あ'.code, '漢'.code)) {
            val glyph = raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, codePoint, 18)
            assertNotNull(glyph, "码位 U+${codePoint.toString(16)} 应有字形（随包 Saitamaar 覆盖）")
            val expected = metrics.advancePixels(codePoint, CellMetrics.DEFAULT_FONT_NAME, 18)
                ?: if (converter.fallbackClassOf(codePoint) == FallbackWidthClass.ZERO) 0
                else converter.cellsToPixels(converter.cellsOf(codePoint))
            assertEquals(expected, glyph.advancePixels, "advance 必须来自旁表，禁 AWT 测量")
            assertEquals(if (expected > 0) expected else 0, glyph.bitmapWidth)
            assertEquals(converter.lineHeight, glyph.bitmapHeight)
        }
    }

    @Test
    fun `同入参两次调用逐位相同`() {
        val cellMetrics = CellMetrics(18, 19)
        val metrics = toolMetrics(cellMetrics)
        val raster = AwtGlyphRaster(cellMetrics = cellMetrics, metrics = metrics)
        val a = raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 3, '漢'.code, 18)
        val b = raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 3, '漢'.code, 18)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a.advancePixels, b.advancePixels)
        assertEquals(a.bitmapWidth, b.bitmapWidth)
        assertTrue(a.bitmap.contentEquals(b.bitmap), "位图逐位相同（确定性契约）")
    }

    @Test
    fun `真字形墨迹是覆盖率掩码 非全零且核心全墨`() {
        val cellMetrics = CellMetrics(18, 19)
        val raster = AwtGlyphRaster(cellMetrics = cellMetrics, metrics = toolMetrics(cellMetrics))
        val glyph = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, '漢'.code, 18))
        val solid = glyph.bitmap.filter { it ushr 24 == 255 }
        assertTrue(solid.isNotEmpty(), "笔画核心应有全墨像素")
        // 掩码语义：全墨像素 RGB 恒白（着色在绘制侧按 alpha 覆盖率做）。
        assertTrue(solid.all { it and 0xFFFFFF == 0xFFFFFF })
    }

    @Test
    fun `表缺行返回 null（缺字走回退链）`() {
        // 极小 sidecar：只有 'A'（0x41）一行，其余码位按「cmap 没有即缺行」判缺字。
        val metrics = TextMetrics.fromSidecarText("#family Tiny\n#unitsPerEm 1280\n41 600 0\n")
        val raster = AwtGlyphRaster(metrics = metrics)
        assertNull(raster.rasterize("Tiny", 0, '中'.code, 18), "表缺行 = 缺字 → null（回退链下一跳）")
        val a = assertNotNull(raster.rasterize("Tiny", 0, 'A'.code, 18))
        // 表行 advance 600 设计单元 × 18/1280 = 8.4375 → 逐码位向零截断一次。
        assertEquals(8, a.advancePixels)
    }

    @Test
    fun `TTF 资源缺失按这一跳无字形处理 不抛`() {
        val metrics = TextMetrics.gridOnly()
        val raster = AwtGlyphRaster(
            metrics = metrics,
            fontResources = mapOf("Nope" to "fonts/no-such-font.ttf"),
            fallbackResource = "fonts/no-such-font.ttf",
        )
        assertNull(raster.rasterize("Nope", 0, 'A'.code, 18), "字体载入失败 → null（契约第 1 条：不许抛）")
    }

    @Test
    fun `与 Static 共享度量时 advance 逐值相等 位图内容必须不同`() {
        val cellMetrics = CellMetrics(18, 19)
        val metrics = toolMetrics(cellMetrics)
        val awt = AwtGlyphRaster(cellMetrics = cellMetrics, metrics = metrics)
        val statik = StaticGlyphRaster(cellMetrics = cellMetrics, metrics = metrics)
        for (codePoint in intArrayOf('A'.code, 'あ'.code, '漢'.code)) {
            val a = assertNotNull(awt.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, codePoint, 18))
            val s = assertNotNull(statik.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, codePoint, 18))
            assertEquals(s.advancePixels, a.advancePixels)
            assertEquals(s.bitmapWidth, a.bitmapWidth)
            assertEquals(s.bitmapHeight, a.bitmapHeight)
            // 墨迹必须真变了：Static 是实心占位（alpha 全 FF），Awt 是 AA 掩码（有透明像素）。
            assertNotEquals(s.bitmap.toList(), a.bitmap.toList(), "占位 → 真字形，位图内容必须不同")
        }
    }

    @Test
    fun `字号是入参的一部分 同码位不同字号不同度量`() {
        val metrics = toolMetrics()
        val raster = AwtGlyphRaster(metrics = metrics)
        val small = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, '漢'.code, 18))
        val large = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, '漢'.code, 42))
        assertNotEquals(small.advancePixels, large.advancePixels)
        assertEquals(CellMetrics(42).lineHeight, large.bitmapHeight)
    }

    @Test
    fun `删除线与下划线加墨不加宽`() {
        val cellMetrics = CellMetrics(18, 19)
        val raster = AwtGlyphRaster(cellMetrics = cellMetrics, metrics = toolMetrics(cellMetrics))
        val plain = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 0, 'A'.code, 42))
        val strike = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 4, 'A'.code, 42))
        val underline = assertNotNull(raster.rasterize(CellMetrics.DEFAULT_FONT_NAME, 8, 'A'.code, 42))
        // 手画线必须在位图内留下额外墨；advance 不变（布局不因样式改）。
        assertEquals(plain.advancePixels, strike.advancePixels)
        assertEquals(plain.advancePixels, underline.advancePixels)
        assertTrue(strike.bitmap.count { it ushr 24 > 0 } > plain.bitmap.count { it ushr 24 > 0 })
        assertTrue(underline.bitmap.count { it ushr 24 > 0 } > plain.bitmap.count { it ushr 24 > 0 })
    }

    @Test
    fun `工具装配的度量链真接到随包 sidecar 非退化`() {
        val measurement = toolMetrics().measure("漢字テスト", "", 42)
        assertEquals(
            MetricProvenance.ADVANCE_TABLE,
            measurement.provenance,
            "装配断线时这里会落 DEGRADED_GRID（整链无表），首差先查 substituteFamily 接线",
        )
    }
}
