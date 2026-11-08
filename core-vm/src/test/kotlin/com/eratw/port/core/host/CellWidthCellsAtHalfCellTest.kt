package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `px → 半角格` 的唯一取整实现 [CellWidth.cellsAtHalfCell] 的逐值钉（C1 度量源单源化）。
 *
 * 期望值全部按原式手算，不拿实现回读当期望：
 * - 正宽 `ceil(px / 半角格宽)`、不足 1 格抬 1；负宽严格镜像 `-ceil(|px| / 半角格宽)`、不足 1 格
 *   回退 `-1`；`px == 0` / 半角格宽 ≤ 0 → 0 格（`InlineGraphic.cellsFor` 的既有语义，
 *   见 `render/displaylist/DrawCmd.kt`，原版像素域带符号累加锚
 *   `ConsoleButtonString.cs:281`/`:337` 的 `num += Width`）；
 * - 半角格宽 = `FontSize / 2f * 1.04f`（spec: `StringMeasure.cs:37` 的
 *   `fontDisplaySize = Config.DefaultFont.Size / 2f * 1.04f`）；字号 18 ⇒ 9.36px，
 *   基准 `fixtures/golden/baseline/pc-golden-root.txt:253` 的 199 个 `=` 把半角步进钉成 9px
 *   （`:61` 的 99 个 `━` 钉全角 18px）。
 */
class CellWidthCellsAtHalfCellTest {

    @Test
    fun `零宽与无基尺都记零格`() {
        assertEquals(0, CellWidth.cellsAtHalfCell(0L, 10f), "0px 无段可占")
        assertEquals(0, CellWidth.cellsAtHalfCell(0L, 0f))
        assertEquals(0, CellWidth.cellsAtHalfCell(100L, 0f), "半角格宽 0 = headless 不折算")
        assertEquals(0, CellWidth.cellsAtHalfCell(100L, -1f), "半角格宽为负 = 无基尺")
        assertEquals(0, CellWidth.cellsAtHalfCell(-100L, 0f), "负宽同样先过无基尺判")
    }

    @Test
    fun `正宽向上取整且不足一格抬一`() {
        // 半角格宽 10px
        assertEquals(3, CellWidth.cellsAtHalfCell(25L, 10f), "ceil(2.5) = 3")
        assertEquals(2, CellWidth.cellsAtHalfCell(20L, 10f), "整除不抬")
        assertEquals(3, CellWidth.cellsAtHalfCell(21L, 10f), "整除余一抬一格")
        assertEquals(1, CellWidth.cellsAtHalfCell(1L, 10f), "不足 1 格抬 1")
        assertEquals(1, CellWidth.cellsAtHalfCell(9L, 10f))
        // 字号 18：半角格 9.36px（18 / 2f * 1.04f）
        val half18 = 18L / 2f * 1.04f
        assertEquals(24, CellWidth.cellsAtHalfCell(216L, half18), "ceil(216 / 9.36) = 24")
        assertEquals(2, CellWidth.cellsAtHalfCell(18L, half18), "ceil(1.92) = 2")
        assertEquals(1, CellWidth.cellsAtHalfCell(1L, half18))
    }

    @Test
    fun `负宽取正宽的严格镜像且不足一格回退一格`() {
        assertEquals(-3, CellWidth.cellsAtHalfCell(-25L, 10f))
        assertEquals(-2, CellWidth.cellsAtHalfCell(-20L, 10f))
        assertEquals(-1, CellWidth.cellsAtHalfCell(-5L, 10f), "不足 1 格也回退 1 格")
        for (px in listOf(1L, 9L, 10L, 11L, 25L, 47L, 100L, 476L)) {
            assertEquals(
                CellWidth.cellsAtHalfCell(px, 10f),
                -CellWidth.cellsAtHalfCell(-px, 10f),
                "±${px}px 必须互为镜像（配对的 space 写法净位移为 0）",
            )
        }
    }

    @Test
    fun `Int域边界值不回绕`() {
        // 半角格宽 1px：绝对值在 Long 域算，Float → Int 越界时 Kotlin `toInt` 饱和到
        // Int.MAX_VALUE，负侧取反后得 -Int.MAX_VALUE —— 不是 `-Int.MIN_VALUE` 那种回绕。
        assertEquals(-Int.MAX_VALUE, CellWidth.cellsAtHalfCell(Int.MIN_VALUE.toLong(), 1f))
        assertEquals(Int.MAX_VALUE, CellWidth.cellsAtHalfCell(-(Int.MIN_VALUE.toLong()), 1f))
        assertEquals(Int.MAX_VALUE, CellWidth.cellsAtHalfCell(Int.MAX_VALUE.toLong(), 1f))
        assertEquals(-Int.MAX_VALUE, CellWidth.cellsAtHalfCell(-(Int.MAX_VALUE.toLong()), 1f))
        // 半角格宽 10px：两侧严格镜像，且是百万级（不是回绕成的 1 格）
        val min = CellWidth.cellsAtHalfCell(Int.MIN_VALUE.toLong(), 10f)
        assertEquals(-CellWidth.cellsAtHalfCell(-(Int.MIN_VALUE.toLong()), 10f), min, "±2147483648 严格镜像")
        assertTrue(kotlin.math.abs(min) > 200_000_000, "大宽记大格数：$min")
    }

    @Test
    fun `graphicCells等价于脚本字号折半角格后转调`() {
        // 遍历面覆盖真实字号档（8..72）与正负宽，逐值对拍「字号 → 半角格宽」这一层
        for (fontPx in 0L..72L) {
            val half = fontPx / 2f * 1.04f
            for (scriptPx in -200L..200L) {
                assertEquals(
                    CellWidth.cellsAtHalfCell(scriptPx, half),
                    CellWidth.graphicCells(scriptPx, fontPx),
                    "fontPx=$fontPx scriptPx=$scriptPx",
                )
            }
        }
        // 无字号（headless 不折算图形）：半角格宽非正 ⇒ 0 格，与接线前逐位一致
        assertEquals(0, CellWidth.graphicCells(216L, 0L))
        assertEquals(0, CellWidth.graphicCells(216L, -18L))
    }

    @Test
    fun `1点04系数参与求值`() {
        // 判别式：字号 100 ⇒ 半角格宽 52px（1.04 系数）而不是 50px（把系数错写成 1.0）。
        // 101 / 52 = 1.942 → 2 格；101 / 50 = 2.02 → 3 格。
        assertEquals(2, CellWidth.graphicCells(101L, 100L), "半角格宽 52px（StringMeasure.cs:37 的 1.04）")
        assertEquals(2, CellWidth.cellsAtHalfCell(101L, 100L / 2f * 1.04f))
    }
}
