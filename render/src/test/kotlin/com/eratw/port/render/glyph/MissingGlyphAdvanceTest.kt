package com.eratw.port.render.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 缺字回退的**确定性前进量**（GFX-12）。
 *
 * 缺口：回退链里一张度量表都没有时，缺字前进量走 `cellsOf`（表外字一律全角 = 2 格）⇒
 * 组合附加符号（Mn/Me）与变体选择符也拿 2 格，与分档表
 * （[CellMetrics.fallbackClassOf]）的零宽档 0 步进分叉。
 *
 * 收口口径：**组合附加符号/变体选择符取 0 步进**（并入基字簇），
 * 其余码位维持布局口径（半角 1 格 / 全角 2 格 / 增补平面 4 格）——
 * 增补平面取 4 格是 `FontAudit2Test`「格数与布局一致」的既有钉子
 * （分档表的 2 格是另一条口径，`FallbackWidthTest` 已登记该粒度差）。
 *
 * 期望像素按默认字号 18 推（[CellMetrics.cellPixelWidth] = 18 / 2 × 1.04 = 9.36）：
 * 半角 1 格 → 9 px、全角 2 格 → 18 px、零宽 0 格 → 0 px。
 */
class MissingGlyphAdvanceTest {

    /** 一张表都没有 ⇒ 走缺字分档。 */
    private val raster = StaticGlyphRaster()

    private fun advance(codePoint: Int): Int? =
        raster.rasterize(raster.defaultFontName, 0, codePoint, CellMetrics.DEFAULT_FONT_PIXEL_SIZE)
            ?.advancePixels

    @Test
    fun `零宽档 组合附加符号与变体选择符 前进量 0`() {
        assertEquals(0, advance(0x0301), "U+0301 组合附加符号 = 零宽档，0 步进")
        assertEquals(0, advance(0x20D0), "U+20D0 组合附加符号 = 零宽档，0 步进")
        assertEquals(0, advance(0xFE0F), "U+FE0F 变体选择符 = 零宽档，0 步进")
    }

    @Test
    fun `半角档 前进量一个半角格`() {
        assertEquals(9, advance(0x41), "ASCII 'A' = 半角 1 格 × 9.36 → 9 px")
        assertEquals(9, advance(0x00A5), "U+00A5 半角档 = 1 格 → 9 px")
    }

    @Test
    fun `全角档 前进量两个半角格`() {
        assertEquals(18, advance(0x4E2D), "CJK '中' = 全角 2 格 × 9.36 → 18 px")
        assertEquals(18, advance(0xFF01), "U+FF01 全角 ASCII = 2 格 → 18 px")
    }

    @Test
    fun `增补平面按布局口径四个半角格 与 FontAudit2Test 同值`() {
        // 增补平面取**布局口径**（两个 UTF-16 码元各 2 格 = 4 格 → 37 px）：
        // `FontAudit2Test` 的「代理对按一个码位进桶且格数与布局一致」钉的就是 4 格 / 37px，
        // 绘制侧 ScreenComposer.drawRun 也按码元各 2 格推进。分档表给的是 2 格
        // （`FallbackWidthTest` 已登记「度量 2 格 / 布局 4 格」的粒度差），本跳不取分档表那一档，
        // 免得绘制推进 4 格而量宽 2 格。
        assertEquals(37, advance(0x1F000), "增补平面 = 4 格 × 9.36 → 37 px（布局口径）")
    }

    @Test
    fun `缺字回退只看码位 与设备装了什么字体无关`() {
        // 同一码位在两套不同覆盖表下取值相同：覆盖集只决定「画不画」，不决定「多宽」。
        val onlyA = StaticGlyphRaster(
            fonts = listOf(FontCoverage(CellMetrics.DEFAULT_FONT_NAME, codePoints = setOf(0x41))),
        )
        val glyph = onlyA.rasterize(onlyA.defaultFontName, 0, 0x41, CellMetrics.DEFAULT_FONT_PIXEL_SIZE)
        assertNotNull(glyph, "覆盖集内的码位仍要出图")
        assertEquals(9, glyph.advancePixels, "覆盖集内的码位前进量同分档表")
    }

    @Test
    fun `覆盖集外的码位仍回退为 null 由回退链接管`() {
        val onlyA = StaticGlyphRaster(
            fonts = listOf(FontCoverage(CellMetrics.DEFAULT_FONT_NAME, codePoints = setOf(0x41))),
        )
        assertNull(
            onlyA.rasterize(onlyA.defaultFontName, 0, 0x4E2D, CellMetrics.DEFAULT_FONT_PIXEL_SIZE),
            "覆盖集外 ⇒ 返回 null 让回退链接管（GlyphRaster 契约第 1 条）",
        )
    }
}
