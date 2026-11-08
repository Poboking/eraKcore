package com.eratw.port.render.button

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.grid.LineFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对拍族 ②「按钮 / 链接布局与 PRINTBUTTON 系列」—— M6-RENDER-AUDIT。
 *
 * 逐条口径（C# 出处 → 本仓落地 → 结论）：
 *
 * | # | C# 出处 | 本仓落地 | 结论 |
 * |---|---|---|---|
 * | 1 | `EmueraConsole.cs:2358-2361` 逐行、**倒序**遍历 `Buttons`，`PointX > x \|\| PointX + Width < x` 跳过 | [ButtonLayer.hit] 的 `col in colStart until colEnd` | 闭区间 → 半开区间（ADR-003，见第 2 条）；倒序在互不重叠时无差别 |
 * | 2 | 同上：`x == PointX + Width` 仍判命中（只有 `<` 才跳过） | 右端开区间，`col == colEnd` 不命中 | **有意差异**：整数格没有「右端像素」；相邻按钮共格时本仓归右钮，原版也归右钮（倒序先查右钮），故仅末钮右沿多一格差异 |
 * | 3 | `EmueraConsole.cs:2368` 段内还要比 `Top/Bottom`（行内纵向范围） | **IMG-A4 翻案（2026-11-06）**：图形段入表时同记行内纵向门，[ButtonLayer.hitInline] 按**图元节点**的 `[Top, Bottom]` 闭区间判（立绘 `ConsoleImagePart.cs:31-33,81,93`、矩形件 `ConsoleRectangleShapePart.cs:19-21,34-35`、Space/文本缺省带 `AConsoleDisplayNode.cs:29-31`）；无纵向信息的 [ButtonLayer.hit] 维持整行口径（调用方切换前行为逐位不变）。旧注「文本段 `AConsoleColoredPart` 恒为整行」有误：缺省带是 `[0, FontSize]`，语料 `LINE HEIGHT == FONT SIZE == 18`（`emuera.config:22-23`）下才等价整行 —— 见用例「立绘图段的行内纵向矩形」 |
 * | 4 | `EmueraConsole.cs:2377-2381` 非按钮段照样命中（只为测 tooltip），`IsButton` 才产出候选 | 只有 `RunKind.BUTTON && buttonValue != null` 入表 | 一致（`value == null` 的非按钮段不产候选，`EmueraConsole.cs:2439` 要求 `IsButton`） |
 * | 5 | `ConsoleButtonString.cs:300-304` `Width = 末段右沿 - 首段左沿` | 一条 run 的 `Grid.cells` 就是一个区间；跨行时每行各一条 | 一致（`ConsoleButtonString.cs:187-255` + `PrintStringBuffer.cs:224-235`） |
 * | 6 | `ConsoleButtonString.cs:157-163` `LockPointX` 只被 HTML `pos` 用 | 本层不建模绝对定位 | 一致（HTML `pos` 在 mini-HTML 子集外，`MiniHtml` 命中即抛 UNSUPPORTED_TAG） |
 * | 7 | `ConsoleDisplayLine.cs:79-90` 对齐位移加到每个 `PointX`；`ConsoleButtonString.cs:307-315` 宽度不动 | [ConsoleAdapter.alignmentShift] → `DisplayRow.indentCells` → `attach` 的列游标起点 | 一致（本例逐格断言 CENTER 的 `num3`） |
 * | 8 | `EmueraConsole.cs:3293-3307` `IsNullOrEmpty(str)` 的空按钮根本不入流 | `attach` 的 `width > 0` 守卫 | 一致（零宽段同时被 `ConsoleButtonString.cs:273-277` 压成 `Width = -1`） |
 * | 9 | `EmueraConsole.cs:2435-2459` 数字等待期字符串按钮被排除（连着焦点都不算） | [ButtonAcceptance.INT] 的 `value is Long` | 一致 |
 * | 10 | `EmueraConsole.cs:395-428` `SelectedString` 三支（IntValue→`Input`、StrValue→`Inputs`、AnyValue→按 `IsInteger` 分） | [ButtonLayer.selectedString] 的 `value.toString()` | 一致（数值按钮构造时已把 `Inputs` 固成 `Input.ToString()`，`ConsoleButtonString.cs:85`） |
 * | 11 | `Process.cs:1107-1124` 按操作数类型分派 long / string 重载 | `buttonValue` 存 Long / String | 一致 |
 *
 * 第 2 条的具体读数（本仓取半开）：按钮文本 `OK` 起点列 1 ⇒ 区间 `[1,3)`；
 * 列 0 / 列 3 都算「不在按钮上」，列 1、2 命中。原版在同一几何下只有列 3 的
 * 最右端像素会落进 `PointX + Width == x`，像素宽度归一化成整格后该像素属于下一格。
 */
class ButtonHitAuditTest {

    private fun text(s: String) = TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun button(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun strButton(s: String, v: String) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun line(vararg runs: TextRun, align: Alignment = Alignment.LEFT) =
        RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true, align = align)

    private fun hit(line: RenderLine, width: Int, drawable: Int = width): ButtonLayer {
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(width), listOf(line), drawable))
        return layer
    }

    /** 第 1/2/5 条：`A` + 按钮 `OK`(7) + `B` ⇒ 区间 `[1,3)`，左闭右开。 */
    @Test
    fun `PRINTBUTTON 命中区间左闭右开`() {
        val layer = hit(line(text("A"), button("OK", 7L), text("B")), width = 20)
        assertEquals(1, layer.all().size)
        val t = layer.all()[0]
        assertEquals(0, t.row)
        assertEquals(1, t.colStart)
        assertEquals(3, t.colEnd)
        assertEquals(7L, t.value)
        assertNull(layer.hit(0, 0))
        assertEquals(7L, layer.hit(0, 1)?.value)
        assertEquals(7L, layer.hit(0, 2)?.value)
        assertNull(layer.hit(0, 3))
        assertNull(layer.hit(0, 4)) // 行内游标之后也是空白
        assertEquals("7", layer.click(0, 1))
    }

    /** 第 9/10 条：数字等待期字符串按钮不可点；ANY 期两类都可点且回环各自取值。 */
    @Test
    fun `数值等待期过滤字符串按钮`() {
        val layer = hit(line(button("N", 42L), text(" "), strButton("S", "abc")), width = 20)
        assertEquals(2, layer.all().size)
        assertEquals(42L, layer.hit(0, 0, ButtonAcceptance.INT)?.value)
        assertNull(layer.hit(0, 2, ButtonAcceptance.INT))
        assertNull(layer.click(0, 2, ButtonAcceptance.INT))
        // BINPUTS（STR）两支都收
        assertEquals(42L, layer.hit(0, 0, ButtonAcceptance.STR)?.value)
        assertEquals("abc", layer.hit(0, 2, ButtonAcceptance.STR)?.value)
        // 回环：Long → 十进制串，String → 原串
        assertEquals("42", layer.click(0, 0))
        assertEquals("abc", layer.click(0, 2))
    }

    /** 第 5 条：按钮跨行拆分后每行一条命中，`row` 是**显示行**下标。 */
    @Test
    fun `跨行按钮每行各一条命中`() {
        val layer = hit(line(text("AB"), button("CDEFGH", 9L)), width = 5)
        val rows = layer.all()
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.row })
        assertEquals(listOf(2, 0), rows.map { it.colStart })
        assertEquals(listOf(5, 3), rows.map { it.colEnd })
        assertEquals(listOf(9L, 9L), rows.map { it.value })
        assertEquals("9", layer.click(1, 0))
    }

    /** 第 7 条：CENTER 位移 `num3 = drawable/2 - cells/2`（`ConsoleDisplayLine.cs:80,86-90`）加到命中列上。 */
    @Test
    fun `居中行的命中列随整行平移`() {
        val l = line(button("OK", 5L), text("XY"), align = Alignment.CENTER)
        // 行宽 4、可绘制 10 ⇒ num3 = 5 - 2 = 3
        val rows = ConsoleAdapter.fold(LineFolder(10), listOf(l), 10)
        assertEquals(3, rows[0].indentCells)
        val layer = ButtonLayer()
        layer.attach(rows)
        assertEquals(3, layer.all()[0].colStart)
        assertEquals(5, layer.all()[0].colEnd)
        assertNull(layer.hit(0, 2))
        assertEquals(5L, layer.hit(0, 4)?.value)
    }

    /** 第 7 条右对齐 + 超宽行：`num3 = drawable - cells` 可为负，命中列整段右移后仍按半开区间判。 */
    @Test
    fun `右对齐超宽行的负位移照抄不夹`() {
        // 行宽 7、可绘制 5 ⇒ num3 = 5 - 7 = -2（ConsoleDisplayLine.cs:83,86-90）
        assertEquals(-2, ConsoleAdapter.alignmentShift(Alignment.RIGHT, 7, 5))
        val row = DisplayRow(
            runs = listOf(text("ABCDEF"), button("G", 1L)),
            cells = 7,
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.RIGHT,
            indentCells = -2,
        )
        val layer = ButtonLayer()
        layer.attach(listOf(row))
        val t = layer.all()[0]
        assertEquals(4, t.colStart) // 6 - 2
        assertEquals(5, t.colEnd)
        assertEquals(1L, layer.hit(0, 4)?.value)
        assertNull(layer.hit(0, 3))
    }

    /** 第 8 条：空文本按钮不入表（`EmueraConsole.cs:3295,3303` 的空串守卫）。 */
    @Test
    fun `空文本按钮不产生命中区`() {
        val layer = hit(line(text("A"), button("", 3L), text("B")), width = 10)
        assertTrue(layer.all().isEmpty())
        assertNull(layer.click(0, 1))
    }

    /** `acceptance` 只筛类型不改几何：同一格在 ANY 下命中、INT 下对字符串按钮为空。 */
    @Test
    fun `类型筛选不改变几何`() {
        val layer = hit(line(strButton("S", "s")), width = 10)
        assertEquals(0, layer.hit(0, 0, ButtonAcceptance.ANY)?.colStart)
        assertEquals(0, layer.hit(0, 0, ButtonAcceptance.STR)?.colStart)
        assertNull(layer.hit(0, 0, ButtonAcceptance.INT))
        assertEquals(1, layer.all()[0].colEnd)
    }

    // ===== IMG-A4 I2：行内纵向命中（EmueraConsole.cs:2368 的 Top/Bottom 半边） =====

    /** 图段按钮 run（text 恒空串，graphic 带像素盒折算的格数）。 */
    private fun imgButton(
        widthCells: Int,
        yOffsetPx: Int,
        heightPx: Int,
        v: Long,
        id: Int = 7,
    ) = TextRun(
        "",
        fore = 0xFFFFFF,
        back = null,
        styleMask = 0,
        fontName = "msgothic",
        kind = RunKind.IMAGE,
        buttonValue = v,
        buttonId = id,
        graphic = InlineGraphic.Image("standee", widthPx = widthCells * 9, heightPx = heightPx, yOffsetPx = yOffsetPx, widthCells = widthCells),
    )

    /** 第 3 条（IMG-A4 翻案）：半行高立绘 `y∈[9,18]` —— 盒内闭区间命中、盒外不命中、旧口径 `hit` 仍整行。 */
    @Test
    fun `立绘图段的行内纵向矩形`() {
        val row = DisplayRow(runs = listOf(imgButton(widthCells = 3, yOffsetPx = 9, heightPx = 9, v = 1L)), cells = 3, isLogicalLine = true, isLineEnd = true)
        val layer = ButtonLayer()
        layer.attach(listOf(row))
        val t = layer.all().single()
        assertEquals(0, t.colStart)
        assertEquals(3, t.colEnd)
        // 闭区间两端（:2368 的 < > 跳过判 ⇒ y ∈ [Top, Bottom] 都算命中；:2420 逃逸支同形）
        assertEquals(1L, layer.hitInline(0, 1, 9f, 18)?.value)
        assertEquals(1L, layer.hitInline(0, 1, 18f, 18)?.value)
        // 盒外（本行其余像素）不命中 —— 「按整行」的翻案点
        assertNull(layer.hitInline(0, 1, 0f, 18))
        assertNull(layer.hitInline(0, 1, 8.9f, 18))
        // 无纵向信息的旧口径（hit）仍整行可点：调用方（CanvasConsoleView）切换前行为逐位不变
        assertEquals(1L, layer.hit(0, 1)?.value)
    }

    /** 第 3 条：矩形件纵盒下沿 = `Math.Max(FontSize, y+h)`（`ConsoleRectangleShapePart.cs:35`）⇒ 盖住整行字。 */
    @Test
    fun `矩形件纵盒至少盖一行字`() {
        val rect = TextRun(
            "",
            fore = 0xFFFFFF,
            back = null,
            styleMask = 0,
            fontName = "msgothic",
            kind = RunKind.SHAPE,
            buttonValue = "s",
            buttonId = 3,
            graphic = InlineGraphic.Rect(xOffsetPx = 0, yOffsetPx = 0, widthPx = 36, heightPx = 2, colorArgb = 0xFF0000, widthCells = 4),
        )
        val row = DisplayRow(runs = listOf(rect), cells = 4, isLogicalLine = true, isLineEnd = true)
        val layer = ButtonLayer()
        layer.attach(listOf(row))
        // bottom = max(18, 0+2) = 18 ⇒ 行内任何 y 都命中
        assertEquals("s", layer.hitInline(0, 0, 0f, 18)?.value)
        assertEquals("s", layer.hitInline(0, 0, 17.9f, 18)?.value)
        // 负 ypos 的上沿照抄 `Math.Min(0, rect.Y)`（:34）
        val shifted = DisplayRow(
            runs = listOf(
                TextRun(
                    "", fore = 0xFFFFFF, back = null, styleMask = 0, fontName = "msgothic",
                    kind = RunKind.SHAPE, buttonValue = "t", buttonId = 4,
                    graphic = InlineGraphic.Rect(xOffsetPx = 0, yOffsetPx = -5, widthPx = 36, heightPx = 30, colorArgb = 0xFF0000, widthCells = 4),
                ),
            ),
            cells = 4,
            isLogicalLine = true,
            isLineEnd = true,
        )
        val layer2 = ButtonLayer()
        layer2.attach(listOf(shifted))
        assertNull(layer2.hitInline(0, 0, 25.1f, 18)) // bottom = max(18, 25) = 25 ⇒ 25.1 出盒
        assertEquals("t", layer2.hitInline(0, 0, 25f, 18)?.value)
    }

    /** 第 3 条：混排按钮 —— 文本列无门（整行带），图段列按节点盒加严；同源按钮并成一条命中区。 */
    @Test
    fun `混排按钮文本列整行可点图段列按纵盒判`() {
        val textPart = TextRun(
            "OK", fore = 0xFFFFFF, back = null, styleMask = 0, fontName = "msgothic",
            kind = RunKind.BUTTON, buttonValue = 5L, buttonId = 9,
        )
        val imgPart = imgButton(widthCells = 2, yOffsetPx = 0, heightPx = 9, v = 5L, id = 9)
        val row = DisplayRow(runs = listOf(textPart, imgPart), cells = 4, isLogicalLine = true, isLineEnd = true)
        val layer = ButtonLayer()
        layer.attach(listOf(row))
        val t = layer.all().single()
        assertEquals(0, t.colStart)
        assertEquals(4, t.colEnd) // 同源（buttonId=9）文本段(2格) + 图段(2格)并成一条（ConsoleButtonString.cs:292-303）
        // 文本列（0..1）：无门 —— 原版文本节点缺省带 [0, FontSize]，语料口径下 = 整行
        assertEquals(5L, layer.hitInline(0, 0, 17f, 18)?.value)
        // 图段列（2..3）：y 出了 [0, 9] 不命中
        assertEquals(5L, layer.hitInline(0, 2, 5f, 18)?.value)
        assertNull(layer.hitInline(0, 2, 12f, 18))
    }
}
