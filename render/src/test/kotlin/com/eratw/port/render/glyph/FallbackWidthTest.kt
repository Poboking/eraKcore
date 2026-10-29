package com.eratw.port.render.glyph

import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GFX-12 · 缺字形时的**确定性**宽度回退。
 *
 * 真机问题：随包子集只覆盖 5,268/8,076 个 required 码点，缺的字回落到系统字体 ⇒ 宽度随设备变。
 * 本用例锁三件事：
 * 1. 分档只看码位（[CellMetrics.fallbackClassOf]），逐档各一条断言；
 * 2. 同一码位「有字形」与「无字形」的 advance 相同 —— 用一份**网格口径**的夹具
 *    （unitsPerEm 1000，半角 500 / 全角 1000 / 组合符 0）证明回退值与真表值逐值相等，
 *    于是 A 设备（有系统回退字）与 B 设备（无）读到同一序列；
 * 3. 布局侧的既有口径**不回退**：`Grid.cells` / [CellMetrics.cellsOf] 对增补平面仍是 4 格
 *    （`grid/AstralWidthTest` 已登记），本路只改度量层，不动折行。
 *
 * 期望值全部手算：字号 16 的半角格 = 16/2*1.04 = 8.32 → 1 格 = trunc(8.32) = 8；
 * 2 格 = trunc(16.64) = 16。字号 18 的半角格 = 9.36 → 1 格 = 9，2 格 = trunc(18.72) = 18。
 */
class FallbackWidthTest {

    private val font = CellMetrics.DEFAULT_FONT_NAME
    private val metrics = CellMetrics()

    /** 网格口径的夹具：advance 恰好等于「半角 1 格 / 全角 2 格」在 16 字号下的像素值。 */
    private val gridConforming = "#family GridProbe\n#unitsPerEm 1000\n#size 16\n" +
        "0020 500 0\n" +
        "0041 500 0\n" +
        "0042 500 0\n" +
        "0301 0 0\n" +
        "3042 1000 0\n" +
        "4E2D 1000 0\n" +
        "E000 1000 0\n" +
        "1F600 1000 0\n"

    /** 同一族的**残缺**表：只留 U+0041 一行，其余全部落进缺字分档。 */
    private val gridConformingPartial = "#family GridProbe\n#unitsPerEm 1000\n0041 500 0\n"

    private fun present(): TextMetrics = TextMetrics.fromSidecarText(gridConforming, alias = font)

    private fun partial(): TextMetrics = TextMetrics.fromSidecarText(gridConformingPartial, alias = font)

    // ===== 分档逐条 =====

    @Test
    fun `半角档是 CP932 单字节那几段`() {
        for (cp in listOf(0x0000, 0x0009, 0x001F, 0x0020, 0x0041, 0x007E, 0x007F, 0x0080, 0x00A5, 0xFF61, 0xFF9F)) {
            assertEquals(FallbackWidthClass.HALF, metrics.fallbackClassOf(cp), "U+${cp.toString(16)}")
        }
        // 边界：U+00A6 不在单字节档，落全角
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(0x00A6))
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(0xFFA0))
    }

    @Test
    fun `组合符与变体选择符零宽`() {
        for (cp in listOf(0x0300, 0x0301, 0x036F, 0x20D0, 0xFE00, 0xFE0F, 0xFE20)) {
            assertEquals(FallbackWidthClass.ZERO, metrics.fallbackClassOf(cp), "U+${cp.toString(16)}")
            assertEquals(0, metrics.fallbackCellsOf(cp))
            assertEquals(0, metrics.fallbackAdvancePixels(cp))
        }
        // 区间边界外立刻回到全角（U+0370 是希腊字母）
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(0x0370))
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(0xFE10))
    }

    @Test
    fun `全角档覆盖东亚宽字符区与增补平面`() {
        for (cp in listOf(0x1100, 0x3042, 0x4E2D, 0x2501, 0xFF01, 0xFFE5, 0xE000, 0xF8FF, 0x1F600, 0x20000, 0x10FFFF)) {
            assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(cp), "U+${cp.toString(16)}")
            assertEquals(2, metrics.fallbackCellsOf(cp))
        }
        // 非法码位与 cellsOf 一样按全角兜底
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(-1))
        assertEquals(FallbackWidthClass.FULL, metrics.fallbackClassOf(0x110000))
        // 字号 18 下：1 格 = 9px，2 格 = 18px（cellsToPixels 的外层截断）
        assertEquals(9, metrics.fallbackAdvancePixels(0x0041))
        assertEquals(18, metrics.fallbackAdvancePixels(0x4E2D))
    }

    // ===== 有字形 / 无字形同值（GFX-12 的核心判据）=====

    @Test
    fun `缺字与不缺字的同一码位 advance 相同`() {
        val withGlyph = present()
        val withoutGlyph = partial()
        // 每类一个代表：半角 ASCII / 全角假名 / 汉字 / 组合符 / 增补平面 / 私用区
        // 半角 ASCII 用 U+0042 作代表：U+0041 是 partial() 里**故意保留**的那一行
        // （另一条用例要测 "Aい" 的半角+缺字混排），拿它当缺字代表必红。
        for (cp in listOf(0x0020, 0x0042, 0x3042, 0x4E2D, 0x0301, 0xE000, 0x1F600)) {
            for (size in listOf(16, 18)) {
                val hit = withGlyph.resolve(cp, font, size)
                val miss = withoutGlyph.resolve(cp, font, size)
                assertEquals(true, hit.hasGlyph, "U+${cp.toString(16)} 应有字形")
                assertEquals(false, miss.hasGlyph, "U+${cp.toString(16)} 应无字形")
                assertEquals(hit.advancePixels, miss.advancePixels, "U+${cp.toString(16)} @ $size")
                assertEquals(hit.cells, miss.cells, "U+${cp.toString(16)} @ $size")
                assertNull(hit.fallbackClass)
                assertNotNull(miss.fallbackClass)
            }
        }
    }

    @Test
    fun `同一串在有表与无表环境下读数相同`() {
        // 设备 A：随包字体有这些字（走 §3 表）；设备 B：没有表、也没有系统回退字（全走分档）。
        // 网格口径的字体上两条读数必须逐值相同 —— 这就是"与字体有没有这个字解耦"。
        val text = "Aあ中\u0301" + String(Character.toChars(0x1F600))
        val withTable = present().measure(text, font, 16)
        val gridOnly = TextMetrics.gridOnly().measure(text, font, 16)
        assertEquals(withTable.pixels, gridOnly.pixels)
        assertEquals(withTable.cells, gridOnly.cells)
        // 出处仍如实区分：有表那条是真实度量，纯退化那条是 DEGRADED_GRID
        assertEquals(MetricProvenance.ADVANCE_TABLE, withTable.provenance)
        assertEquals(MetricProvenance.DEGRADED_GRID, gridOnly.provenance)
        // 手算：A 8 + あ 16 + 中 16 + 组合符 0 + emoji 16 = 56
        assertEquals(56, withTable.pixels)
        // 两次测量逐字段一致（确定性：不读时间、不用随机、不看设备字体）
        val again = present().measure(text, font, 16)
        assertEquals(withTable.pixels, again.pixels)
        assertEquals(withTable.cells, again.cells)
        assertEquals(withTable.degradedCodePoints, again.degradedCodePoints)
        assertEquals(withTable.reason, again.reason)
    }

    @Test
    fun `逐码位序列标出有无字形`() {
        val sequence = TextMetrics.gridOnly().advanceSequence("Aあ\u0301" + String(Character.toChars(0x1F600)), font, 16)
        assertEquals(4, sequence.size)
        assertEquals(listOf(0x41, 0x3042, 0x0301, 0x1F600), sequence.map { it.codePoint })
        assertEquals(listOf(8, 16, 0, 16), sequence.map { it.advancePixels })
        assertEquals(listOf(1, 2, 0, 2), sequence.map { it.cells })
        assertTrue(sequence.none { it.hasGlyph })
        assertEquals(
            listOf(FallbackWidthClass.HALF, FallbackWidthClass.FULL, FallbackWidthClass.ZERO, FallbackWidthClass.FULL),
            sequence.map { it.fallbackClass },
        )
        // 有字形那条：同一串每项都标 true，且 U+0301 的 0 设计单元仍是 0
        val hit = present().advanceSequence("Aあ\u0301", font, 16)
        assertTrue(hit.all { it.hasGlyph })
        assertTrue(hit.all { it.fallbackClass == null })
        assertEquals(0, hit[2].advancePixels)
        assertEquals(0, hit[2].cells)
    }

    // ===== 零宽与增补平面的行为 =====

    @Test
    fun `组合符不占位`() {
        val gridOnly = TextMetrics.gridOnly()
        // 组合符 0 格：e + U+0301 与 e 同宽（原版整串测量把组合符并进基字簇，StringMeasure.cs:63-65）
        assertEquals(gridOnly.measurePixels("e", font, 16), gridOnly.measurePixels("e\u0301", font, 16))
        assertEquals(gridOnly.measureCells("e", font, 16), gridOnly.measureCells("e\u0301", font, 16))
        assertEquals(8, gridOnly.measurePixels("e\u0301", font, 16))
        // 全是组合符的串宽度为 0（不是负数、不抛）
        assertEquals(0, gridOnly.measurePixels("\u0301\u0301", font, 16))
    }

    @Test
    fun `增补平面按一个码位两格而布局侧仍是四格`() {
        val text = String(Character.toChars(0x1F600))
        val gridOnly = TextMetrics.gridOnly()
        // 度量层：一个码位 = 2 格 = 18px（字号 18）
        assertEquals(2, gridOnly.measureCells(text, font, 18))
        assertEquals(18, gridOnly.measurePixels(text, font, 18))
        assertEquals(1, gridOnly.advanceSequence(text, font, 18).size)
        // 布局/字形缓存侧的既有口径**不动**：UTF-16 码元各 2 格 = 4 格（已登记差异，见 AstralWidthTest）
        assertEquals(4, Grid.cells(text))
        assertEquals(4, metrics.cellsOf(0x1F600))
        // 于是这条差异要由组长接线：折行改走像素度量之前，emoji 的布局留位仍比度量多 2 格
        assertEquals(4 - 2, Grid.cells(text) - gridOnly.measureCells(text, font, 18))
    }

    @Test
    fun `私用区按全角两格`() {
        val gridOnly = TextMetrics.gridOnly()
        for (cp in listOf(0xE000, 0xF8FF, 0xF0000, 0x100000)) {
            assertEquals(2, gridOnly.resolve(cp, font, 18).cells, "U+${cp.toString(16)}")
            assertEquals(18, gridOnly.resolve(cp, font, 18).advancePixels)
        }
    }

    @Test
    fun `缺字测量出处如实且原因可读`() {
        val read = partial().measure("Aい", font, 16)
        // A 有行、い 缺行 → MIXED，缺字计数 1，且原因指向"缺行"而不是"整表没有"
        assertEquals(MetricProvenance.MIXED_TABLE_GRID, read.provenance)
        assertEquals(1, read.degradedCodePoints)
        assertEquals(8 + 16, read.pixels)
        assertTrue(read.reason!!.contains("缺行"), read.reason!!)
        assertTrue(read.reason!!.contains("分档"), read.reason!!)
        // 缺字的 resolve 结果里档位是显式的，不是靠 cellsOf 猜
        assertEquals(FallbackWidthClass.FULL, partial().resolve(0x3044, font, 16).fallbackClass)
    }
}
