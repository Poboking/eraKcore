package com.eratw.port.render.glyph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **后端一致性套件** —— 任何 [GlyphRaster] 实现都必须继承并跑绿的六条契约（契约 §3.3）。
 *
 * ## 为什么是抽象类而不是某个实现自己的用例
 *
 * [GlyphRaster] 是 `:render` 的抽象边界，真机实现（平台字体栈 + 字形图集）由 `:render-canvas`
 * 注入，本波只有确定性假实现 [StaticGlyphRaster]。判据写在实现自己的测试里，就退化成
 * 「这个实现碰巧这样」；写在抽象类里，新后端只要 `extends` 一次就自动被同六条钉住，
 * 也就不存在「换后端时忘了带某条不变量」的漏检。
 *
 * ## 六条（编号对应 [GlyphRaster] KDoc 的编号条款）
 *
 * 1. 未登记的族名 ⇒ null 且**不抛**（契约 1，`FontFactory.GetFont` 的 catch → null）；
 * 2. 同一入参重复调用 ⇒ 度量逐值相同、位图**逐元素**相同（契约 3，禁时间/随机）；
 * 3. 字号是入参的一部分：同一 (族, 样式, 码位) 在 18px 与 30px 下 `advancePixels` 不同（契约 4）；
 * 4. 位图不变式：`bitmap.size == bitmapWidth * bitmapHeight`；`advancePixels <= 0` 时
 *    `bitmapWidth == 0` 且零长位图（「空位图给零长数组而不是 null」）；
 * 5. 高度口径：`ascentPixels + descentPixels == CellMetrics(sizePx).lineHeight`；
 * 6. 缺字集合优先：`FontCoverage(unsupportedCodePoints = setOf(cp))` ⇒ 该码位 null
 *    （[FontCoverage.covers] 的判序：显式缺字集先于全覆盖集）。
 *
 * ## 子类只提供两样
 *
 * - [raster]：按给定的 [FontCoverage] 表造一个后端实例（缺省表 = 只登记 [familyName] 且全覆盖）；
 * - [familyName]：后端在覆盖表里登记的字体名（= 该后端 `defaultFontName` 的取值）。
 *
 * 真机后端（T4-B）继承本类时**不许放宽断言**：六条里任何一条过不去，都是后端在说谎。
 */
abstract class GlyphRasterConformance {

    /** 后端在 [FontCoverage] 里登记的字体名；缺省覆盖表就登记它一个且全覆盖。 */
    protected abstract val familyName: String

    /**
     * 按 [fonts] 造一个被测后端。
     *
     * 缺省值就是「只登记 [familyName]、覆盖全部码位」——六条契约里不关心覆盖细节的那几条
     * 走缺省，第 6 条自己传显式缺字表。
     */
    protected abstract fun raster(
        fonts: List<FontCoverage> = listOf(FontCoverage(familyName, coversAllCodePoints = true)),
    ): GlyphRaster

    /** 半角 ASCII：缺字分档的「半角 1 格」档，字号变化时 advance 必须跟着变。 */
    private val halfWidth = 'A'.code

    /** 全角 CJK：两侧消费者都默认覆盖的常见字。 */
    private val fullWidth = '漢'.code

    // ------------------------------------------------------------------
    // 1) 不许抛：未登记族名 ⇒ null
    // ------------------------------------------------------------------

    @Test
    fun `契约一 未登记的族名返回 null 且不抛`() {
        val raster = raster()
        val result = try {
            raster.rasterize("No Such Family At All", styleMask = 0, codePoint = halfWidth, sizePx = 18)
        } catch (e: Throwable) {
            fail("[$familyName] 未登记族名必须返回 null（契约 1：不许抛），实际抛了 $e")
        }
        assertNull(result, "[$familyName] 未登记族名必须返回 null")
    }

    // ------------------------------------------------------------------
    // 2) 同一入参重复调用 ⇒ 内容一致（禁时间、禁随机）
    // ------------------------------------------------------------------

    @Test
    fun `契约三 同一入参重复调用的度量与位图逐值相同`() {
        val raster = raster()
        val first = assertNotNull(
            raster.rasterize(familyName, 0, fullWidth, 18),
            "[$familyName] 全覆盖表下 $fullWidth 必须有字形",
        )
        val second = assertNotNull(raster.rasterize(familyName, 0, fullWidth, 18))
        assertEquals(first.advancePixels, second.advancePixels, "[$familyName] advance")
        assertEquals(first.lsbPixels, second.lsbPixels, "[$familyName] lsb")
        assertEquals(first.ascentPixels, second.ascentPixels, "[$familyName] ascent")
        assertEquals(first.descentPixels, second.descentPixels, "[$familyName] descent")
        assertEquals(first.bitmapWidth, second.bitmapWidth, "[$familyName] bitmapWidth")
        assertEquals(first.bitmapHeight, second.bitmapHeight, "[$familyName] bitmapHeight")
        assertContentEquals(first.bitmap, second.bitmap, "[$familyName] 位图必须逐元素相同")
    }

    // ------------------------------------------------------------------
    // 3) 字号是入参的一部分
    // ------------------------------------------------------------------

    @Test
    fun `契约四 同一字形在 18px 与 30px 下前进量不同`() {
        val raster = raster()
        val at18 = assertNotNull(raster.rasterize(familyName, 0, halfWidth, 18), "[$familyName] 18px")
        val at30 = assertNotNull(raster.rasterize(familyName, 0, halfWidth, 30), "[$familyName] 30px")
        assertTrue(
            at18.advancePixels != at30.advancePixels,
            "[$familyName] 实现把字号吞掉了：18px=${at18.advancePixels}、30px=${at30.advancePixels}（契约 4）",
        )
    }

    // ------------------------------------------------------------------
    // 4) 位图不变式
    // ------------------------------------------------------------------

    @Test
    fun `契约位图 size 恒等于宽乘高且空位图给零长数组`() {
        val raster = raster()
        for (codePoint in listOf(halfWidth, fullWidth, 0x1F600)) {
            val glyph = assertNotNull(
                raster.rasterize(familyName, 0, codePoint, 18),
                "[$familyName] 全覆盖表下码位 $codePoint 必须有字形",
            )
            assertEquals(
                glyph.bitmapWidth * glyph.bitmapHeight,
                glyph.bitmap.size,
                "[$familyName] 码位 $codePoint 的位图长度与宽高不符",
            )
            assertTrue(glyph.bitmapWidth >= 0, "[$familyName] 位图宽不许为负")
            assertTrue(glyph.bitmapHeight > 0, "[$familyName] 位图高必须为正（行高不可为 0）")
        }
        // U+0300 是组合附加符号（缺字分档的零宽档）：advance <= 0 ⇒ 宽 0、零长位图。
        val zero = assertNotNull(
            raster.rasterize(familyName, 0, 0x0300, 18),
            "[$familyName] 零宽档码位仍要出图（零长位图），不是 null",
        )
        assertTrue(zero.advancePixels <= 0, "[$familyName] U+0300 的 advance 应为 0，实际 ${zero.advancePixels}")
        assertEquals(0, zero.bitmapWidth, "[$familyName] 零宽字形不许分配位图宽")
        assertEquals(0, zero.bitmap.size, "[$familyName] 零宽字形必须给零长数组而不是 null")
    }

    // ------------------------------------------------------------------
    // 5) 高度口径
    // ------------------------------------------------------------------

    @Test
    fun `契约高度口径 上下高度之和等于该字号的行高`() {
        val raster = raster()
        for (sizePx in listOf(18, 30)) {
            val glyph = assertNotNull(raster.rasterize(familyName, 0, halfWidth, sizePx), "[$familyName] ${sizePx}px")
            assertEquals(
                CellMetrics(sizePx).lineHeight,
                glyph.ascentPixels + glyph.descentPixels,
                "[$familyName] ${sizePx}px 的 ascent + descent 必须等于该字号的行高",
            )
        }
    }

    // ------------------------------------------------------------------
    // 6) 缺字集合优先于全覆盖集
    // ------------------------------------------------------------------

    @Test
    fun `契约六 覆盖表里的显式缺字集优先于全覆盖`() {
        val excluded = halfWidth
        val raster = raster(
            listOf(
                FontCoverage(
                    familyName,
                    coversAllCodePoints = true,
                    unsupportedCodePoints = setOf(excluded),
                ),
            ),
        )
        assertNull(
            raster.rasterize(familyName, 0, excluded, 18),
            "[$familyName] 显式缺字集必须压过 coversAllCodePoints（FontCoverage.covers 的判序）",
        )
        assertNotNull(
            raster.rasterize(familyName, 0, fullWidth, 18),
            "[$familyName] 未被排除的码位仍要出图",
        )
    }
}
