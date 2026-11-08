package com.eratw.port.render.displaylist

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1「度量源单一权威」的跨链对拍尺：
 * - **图形格**：`pt` 渲染侧 [InlineGraphic.cellsFor] 必须与 core 唯一取整实现
 *   [CellWidth.cellsAtHalfCell] 逐值相等（正 / 负 / 0 / 半角格宽 0 / 半角格宽负 /
 *   `Int.MIN_VALUE` / 大值）；
 * - **文本格**：渲染侧 [Grid.cells]（先展开 TAB 再逐 UTF-16 码元累加）与 core
 *   [CellWidth.of] / [CellWidth.displayCells] 在同一串字符上逐值相等；
 * - **字号折半角格**：`CellWidth.graphicCells(px, fontPx)` 与
 *   `CellWidth.cellsAtHalfCell(px, fontPx / 2f * 1.04f)` 等价。
 *
 * 期望值全部按原式手算或按另一条链的读数对拍，不拿被测实现回读当期望。
 */
class InlineGraphicCellsSingleSourceTest {

    private val halfCells = floatArrayOf(10f, 9.36f, 7.5f, 5f, 1f, 13.13f)

    private val widths = intArrayOf(
        0, 1, 5, 9, 10, 11, 25, 47, 100, 216, 476,
        -1, -5, -9, -10, -11, -25, -47, -100, -216, -476,
        Int.MAX_VALUE, Int.MIN_VALUE,
    )

    @Test
    fun `图形格 渲染cellsFor与core cellsAtHalfCell逐值相等`() {
        for (half in halfCells) {
            for (px in widths) {
                assertEquals(
                    CellWidth.cellsAtHalfCell(px.toLong(), half),
                    InlineGraphic.cellsFor(px, half),
                    "px=$px halfCellPx=$half",
                )
            }
        }
    }

    @Test
    fun `图形格 无基尺两支都记零格`() {
        assertEquals(0, InlineGraphic.cellsFor(216, 0f))
        assertEquals(0, InlineGraphic.cellsFor(216, -9.36f))
        assertEquals(0, InlineGraphic.cellsFor(-216, 0f))
        assertEquals(0, InlineGraphic.cellsFor(-216, -9.36f))
        for (px in widths) {
            assertEquals(CellWidth.cellsAtHalfCell(px.toLong(), 0f), InlineGraphic.cellsFor(px, 0f))
        }
    }

    @Test
    fun `Int点MIN_VALUE 渲染侧不回绕`() {
        val min = InlineGraphic.cellsFor(Int.MIN_VALUE, 10f)
        assertEquals(CellWidth.cellsAtHalfCell(Int.MIN_VALUE.toLong(), 10f), min)
        assertTrue(min < 0 && min != Int.MIN_VALUE, "经 Long 取绝对值不回绕：$min")
        assertEquals(-min, InlineGraphic.cellsFor(Int.MAX_VALUE, 10f), "正侧同量")
    }

    @Test
    fun `文本格 渲染Grid与core of逐值相等`() {
        val samples = listOf(
            "",
            "abc",
            "ABC 123",
            "全角文字",
            "ｱｲｳｴｵ",                 // 半角假名（U+FF71 起，CP932 单字节）
            "\u0000\u0001\u001F\u007F\u0080", // C0 / DEL / U+0080 单字节档
            "\u00A5\u203E",           // U+00A5 / U+203E：CP932 单字节档
            "①②③",
            "　",                       // U+3000 表意空格（全角）
            "─━│┼",
        )
        for (s in samples) {
            assertEquals(CellWidth.of(s), Grid.cells(s), "文本格必须同尺：${s.map { it.code }}")
        }
        assertEquals(0, Grid.cells(""))
        assertEquals(0, CellWidth.of(""))
    }

    @Test
    fun `文本格 TAB走显示宽度口径`() {
        // 原版量宽前把 TAB 展开成 8 个空格（StringMeasure.cs:59-62）；渲染侧 Grid.cells 同样先
        // 展开，所以对含 TAB 的串必须与 CellWidth.displayCells（TAB = 8 格）对拍，
        // 而不是字节数口径的 CellWidth.of（TAB = 1 格）。
        val tabbed = "a\tb"
        assertEquals(10, Grid.cells(tabbed), "1 + 8 + 1")
        assertEquals(CellWidth.displayCells(tabbed), Grid.cells(tabbed))
        assertEquals(3, CellWidth.of(tabbed), "of 是字节数口径（TAB = 1），不用于显示宽度")
    }

    @Test
    fun `增补平面是已登记的驻留差异`() {
        // 两个码元（高/低代理）各 2 格 = 4，而 core 按码位给 2 —— 这条差**不是**本路的收敛对象，
        // 理由与重钉清单见 Kernel.kt 的 Grid KDoc「增补平面（U+10000 以上）的口径边界」节与
        // grid/AstralWidthTest；这里只把关系钉死，防止有人「顺手统一」而无声改动布局。
        val wide = "\uD83D\uDE00" // U+1F600
        assertEquals(4, Grid.cells(wide), "逐码元累加：2 + 2")
        assertEquals(2, CellWidth.of(wide), "按码位：一个码位 2 格")
        assertEquals(2 * CellWidth.of(wide), Grid.cells(wide), "一整对：渲染侧 = 2 × core 侧")
    }

    @Test
    fun `graphicCells与半角格委托式等价`() {
        for (fontPx in 0L..72L) {
            val half = fontPx / 2f * 1.04f
            for (px in -200L..200L) {
                assertEquals(
                    CellWidth.cellsAtHalfCell(px, half),
                    CellWidth.graphicCells(px, fontPx),
                    "fontPx=$fontPx px=$px",
                )
            }
        }
        // 系数判别：字号 100 ⇒ 半角格宽 52px（1.04），101px 记 2 格；若系数错成 1.0（50px）记 3 格。
        assertEquals(2, CellWidth.graphicCells(101L, 100L))
        assertEquals(
            CellWidth.graphicCells(101L, 100L),
            InlineGraphic.cellsFor(101, 100f / 2f * 1.04f),
            "脚本尺与设备尺同式：字号 100 下的半角格宽",
        )
    }
}
