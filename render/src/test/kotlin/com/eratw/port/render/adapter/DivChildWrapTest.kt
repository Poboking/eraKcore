package com.eratw.port.render.adapter

import com.eratw.port.render.Grid
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * div **子行**按 SubDivisionWidth 折行（2026-11-08 翻案）。
 *
 * 断言的原版语义（相对 reference/emuera-cs/）：
 * - 递归子解析尾部同样走 `ButtonsToDisplayLines(subDiv: true, divWidth: SubDivisionWidth)`
 *   —— `HtmlManager.cs:636-642` 的递归 + `:698` 的折行分支；
 * - 折行宽 `num = divWidth > 0 ? divWidth : Config.DrawableWidth`（`PrintStringBuffer.cs:205`）、
 *   超宽判 `PointX + Width <= num`（:217）、字符级最长前缀切开（`getDivideIndex` :224-240）；
 * - `Config.ButtonWrap` 出厂 false（`ConfigData.cs:83`）⇒ 恒走字符切分支（PSB :222 首支）；
 * - 不可切节点（图形段）行中时落行重排、行首时整段放行（PSB :236-240）；
 * - `noBreak`（`<nobr>`）整段不折（PSB :217 的 `nobr` 支）；
 * - 每条折出的可视行各自 `SetAlignment(align, customWidth: SubDivisionWidth)`
 *   （HM :702-704，ConsoleDisplayLine.cs:80-83）。
 *
 * 神签诗文的真机现场：24 号档半角格 ≈ 12.48px，内容盒 430px → `pixelsToCells = 35`
 * ⇒ 一行 17 个全角字（34 格），第 18 字起折到下一行 —— 旧实现整段 44 格溢出被内容盒
 * clip，真机上表现为「八眸」之后被截断。
 */
class DivChildWrapTest {

    /** 神签第 47 签诗文（22 个全角字 = 44 格）。 */
    private val poem = "切莫遗忘，病魔恒久；蛛网暗藏，八眸玉珠迟暮。"

    /** 真机 24 号档：半角格 = 24/2 × 1.04 = 12.48px。 */
    private val metrics24 = CellMetrics(24, 28)

    private fun textOf(row: DivChildDraw.Row): String = row.runs.joinToString("") { it.text }

    @Test
    fun `超宽全角子行按块宽折行 不再整段溢出被裁`() {
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics24),
            poem,
            subDivisionWidthPx = 430,
            metrics = metrics24,
        )
        assertEquals(2, rows.size, "430px（35 格）装不下 44 格 ⇒ 折两行（PSB :224-240）")
        assertEquals(poem.substring(0, 17), textOf(rows[0]), "第一行取最长前缀：17 全角字 = 34 格 ≤ 35 格")
        assertEquals(poem.substring(17), textOf(rows[1]), "余部另起一行，不丢字")
        assertEquals(34, rows[0].cmds.last().colEnd, "第一行末列 = 34（≤ 块宽 35 格），不再溢出块右沿")
        assertEquals(0, rows[1].cmds.first().colStart, "折出的新行从列 0 起（PSB :244-249 重置 PointX）")
    }

    @Test
    fun `nobr 子行整段不折`() {
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics24),
            "<nobr>$poem</nobr>",
            subDivisionWidthPx = 430,
            metrics = metrics24,
        )
        assertEquals(1, rows.size, "noBreak 恒进放行支（PSB :217 的 nobr 条件）")
        assertEquals(poem, textOf(rows.single()))
        assertEquals(44, rows.single().cmds.last().colEnd, "超宽整段放行，裁剪仍归绘制侧内容盒 clip")
    }

    @Test
    fun `图形段不可切 行中时整段挪到下一行`() {
        val metrics = CellMetrics(18, 19)
        // 块宽 54px → 6 格；文本 AB 占 4 格，图段 5 格 ⇒ 行中放不下、图段落行（PSB :236-240）。
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics),
            "AB<shape type='rect' param='40px' color='#00FF00'>",
            subDivisionWidthPx = 54,
            metrics = metrics,
        )
        assertEquals(2, rows.size, "行中不可切节点整段挪行（divideIndex == 0 且当前行非空）")
        assertTrue(rows[0].runs.all { it.graphic == null }, "第一行只剩文本段")
        assertTrue(rows[0].cmds.all { it.graphic == null })
        assertEquals(0, rows[1].cmds.first().colStart, "图段落行后从列 0 起")
    }

    @Test
    fun `块宽非正时整段单行 交给绘制侧裁剪`() {
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics24),
            poem,
            subDivisionWidthPx = 0,
            metrics = metrics24,
        )
        assertEquals(1, rows.size, "块宽非正 ⇒ 本端口不折（内容盒此时只剩 ≤2px 裁窗，见 DivChildDraw 的 KDoc）")
        assertEquals(poem, textOf(rows.single()))
    }

    @Test
    fun `折出的每行各自算对齐偏移`() {
        // CENTER：num3 = customWidth/2 − 行宽/2（ConsoleDisplayLine.cs:80）；块宽 35 格。
        val rows = DivChildDraw.wrap(
            HtmlRunFlatten(metrics24),
            "<p align='center'>$poem</p>",
            subDivisionWidthPx = 430,
            metrics = metrics24,
        )
        assertEquals(2, rows.size)
        assertEquals(35 / 2 - 34 / 2, rows[0].alignOffsetCells, "第一行按自身 34 格算偏移")
        assertEquals(35 / 2 - 10 / 2, rows[1].alignOffsetCells, "第二行按自身 10 格算偏移（不是整段 44 格）")
        assertEquals(34, Grid.cellsExpanded(textOf(rows[0])))
    }
}
