package com.eratw.port.render.glyph

import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「AA 画 / 老虎机字符画错位」修复的定向闸：字体感知排版尺 [FontWidthRuler]。
 *
 * 读数全部手算自随包 sidecar 的设计单元（unitsPerEm 与 advance 实测自同一批 TTF 的 hmtx）
 * 与 `CellMetrics` 的半格像素宽（字号 18 → 9.36px，spec: `StringMeasure.cs:37`）：
 * - Sarasa Mono SC：upem 1000，半角 500、全角 1000、块元素 2589 = 500 ⇒ 9px / 18px / 9px。
 * - Saitamaar：upem 1280，`:` = 240、`U+200A` = 80、`U+2009` = 160、`▉` = 880
 *   ⇒ 3.375px / 1.125px / 2.25px / 12.375px（×18/1280）。
 *
 * 两条主线：
 * 1. 老虎机 `SETFONT "MS Gothic"` 的块元素必须按半角 1 格，且 82 个 `▂` 的上框
 *    不再被算成 164 格（否则横屏 136 列必被误折行）；
 * 2. 觉的 AA 画 `SETFONT "Saitamaar"` 的微调空格必须保留亚格宽，不再被放大成整格；
 * 3. 默认等宽正文逐值不变 + 缺表时确定性退化，保证既有黄金基准与 headless 装置不动。
 */
class FontWidthRulerTest {

    private val ruler = FontWidthRuler()

    @Test
    fun `老虎机块元素按 MS Gothic 实际落到的等宽族给半角`() {
        // MS Gothic 经别名表折到 Sarasa Mono SC；hmtx 的 U+2581..2589 都是 500/1000。
        for (cp in intArrayOf(0x2581, 0x2582, 0x2588, 0x2589)) {
            assertEquals(
                1, ruler.roundedCells(cp, "MS Gothic"),
                "U+${cp.toString(16)} 在等宽族里是半角",
            )
        }
        // 上框 82 个 ▂：旧口径 164 格（横屏 136 列必被误折行），新尺 82 格。
        val topFrame = "▂".repeat(82)
        assertEquals(82, ruler.widthRoundedCells(topFrame, "MS Gothic"))
        assertEquals(164, Grid.cells(topFrame), "静态 CP932 表把块元素算成 2 格（旧行为）")
        // 图案行：作者让每个转轮恰好 25 个半角字符，▉ 与 ; 混用也必须等宽。
        val pure = ";".repeat(25)
        val filled = ";;;" + "▉".repeat(19) + ";;;"
        assertEquals(25, ruler.widthRoundedCells(filled, "MS Gothic"))
        assertEquals(ruler.widthRoundedCells(pure, "MS Gothic"), ruler.widthRoundedCells(filled, "MS Gothic"))
    }

    @Test
    fun `觉的 AA 画微调空格保留真实亚格宽`() {
        val saitamaar = "Saitamaar"
        val halfCell = CellMetrics.DEFAULT.cellPixelWidth // 9.36px
        // 3.375 / 9.36 = 0.3606 格，不再被静态表放大成 1 格（9.36px，2.67 倍）
        val colon = ruler.advanceCells(0x3A, saitamaar)
        assertTrue(colon in 0.35f..0.37f, "冒号真实宽 3.375px ⇒ 0.36 格，实到 $colon")
        // 1.125 / 9.36 = 0.1202 格，越过 SNAP_TOLERANCE 不吸附成 0
        val hair = ruler.advanceCells(0x200A, saitamaar)
        assertTrue(hair in 0.11f..0.13f, "发丝空格真实宽 1.125px ⇒ 0.12 格，实到 $hair")
        // 2.25 / 9.36 = 0.2404 格
        val thin = ruler.advanceCells(0x2009, saitamaar)
        assertTrue(thin in 0.23f..0.25f, "细空格真实宽 2.25px ⇒ 0.24 格，实到 $thin")
        // 折行预算整串一次取整：20 个发丝空格 ≈ 2.4 格，逐字取整会全抹成 0。
        val hairLine = "\u200A".repeat(20)
        assertEquals(2, ruler.widthRoundedCells(hairLine, saitamaar))
        assertEquals(0, ruler.roundedCells(0x200A, saitamaar), "单字取整仍是 0")
        //  sanity：半格像素宽确为 9.36
        assertEquals(9.36f, halfCell)
    }

    @Test
    fun `默认等宽正文逐值不变`() {
        val cases = mapOf(
            0x41 to 1,          // 'A' 500/1000 → 9px → 吸附 1
            0x20 to 1,          // 空格 500/1000 → 9px → 吸附 1
            0xFF71 to 1,        // 半角假名 ｱ 500/1000 → 9px → 吸附 1
            0x6F22 to 2,        // 漢 1000/1000 → 18px → 吸附 2
            0x3000 to 2,        // 全角空格 1000/1000 → 18px → 吸附 2
        )
        for ((cp, expected) in cases) {
            assertEquals(expected, ruler.roundedCells(cp, ""), "U+${cp.toString(16)}")
            assertEquals(expected, ruler.roundedCells(cp, "黑体"), "别名 黑体 U+${cp.toString(16)}")
            assertEquals(Grid.cellWidth(cp.toChar()), expected, "与静态表同值 U+${cp.toString(16)}")
        }
        assertEquals(Grid.cells("ABC漢字"), ruler.widthRoundedCells("ABC漢字", ""))
        // 框线 ━ 是已登记的平台字体差异：PC 黑体 18px（静态表 2 格），随包 Sarasa 9px。
        // 本尺按字体真值给 1 格；DRAWLINE 的填充数量与绘制推进同尺，屏上仍填满一行。
        assertEquals(2, Grid.cellWidth('━'))
        assertEquals(1, ruler.roundedCells(0x2501, ""))
    }

    @Test
    fun `缺表时确定性退化到静态网格`() {
        val degraded = FontWidthRuler(metrics = TextMetrics.gridOnly())
        assertEquals(2, degraded.roundedCells(0x2589, "MS Gothic"))
        // 退化后逐值就是 CellMetrics.cellsOf：ASCII 半角 1 格、全角冒号 2 格。
        // 0x3A 曾是误写成 2 的期望（冒号在 CP932 单字节档），改正后本例才真的在验「退化」。
        assertEquals(1, degraded.roundedCells(0x3A, "Saitamaar"), "ASCII 冒号在静态网格里是半角 1 格")
        assertEquals(2, degraded.roundedCells(0xFF1A, "Saitamaar"), "全角冒号在静态网格里是全角 2 格")
        assertEquals(1, degraded.roundedCells(0x41, ""))
        assertEquals(Grid.cells("ABC漢字"), degraded.widthRoundedCells("ABC漢字", ""))
    }

    @Test
    fun `换字号同批失效缓存`() {
        val larger = FontWidthRuler()
        // 字号 36：半格 18.72px；Sarasa 半角 500/1000*36 = 18px ⇒ 0.9615 格仍吸附 1。
        larger.cellMetrics = CellMetrics(requestedFontPixelSize = 36)
        assertEquals(1, larger.roundedCells(0x41, ""))
        assertEquals(2, larger.roundedCells(0x6F22, ""))
        assertEquals(1, larger.roundedCells(0x2589, "MS Gothic"))
        // Saitamaar 冒号 240/1280*36 = 6.75px / 18.72 = 0.3606 格（比值不随字号变）
        assertTrue(larger.advanceCells(0x3A, "Saitamaar") in 0.35f..0.37f)
    }
}
