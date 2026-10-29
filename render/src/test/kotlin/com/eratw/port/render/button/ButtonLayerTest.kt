package com.eratw.port.render.button

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ButtonLayer` 命中区与点击回环。
 *
 * 期望值全部来自黄金基准 `fixtures/golden/baseline/pc-golden-root.txt` 的
 * `SECTION:BUTTON-PLAIN` 节（256-262 行，语句源 `fixtures/golden/GOLDEN_02_PRINT.ERB:185-196`）
 * 与反编译 C# 的 `文件:行号`，没有任何一条是拿实现回读出来的：
 * - 257 行 `BTN`（宽 3）= `PRINTBUTTON "BTN", 1` → Long 值 1
 * - 258 行 `[X]`（宽 3）= `PRINTBUTTON "[X]", "SVAL"` → String 值 "SVAL"
 * - 259 行 23 空格 + `BC`（宽 25）= `PRINTBUTTONC "BC", 2`，补齐宽度 25 由 291 行的
 *   `PRINTCLENGTH=25` 固定；补齐串整体进按钮（`EmueraConsole.cs:3317-3322` 把
 *   `CreateTypeCString(...)` 的结果直接交给 `AppendButton`），所以命中区是整行 25 格
 * - 260 行 `BLC` + 22 空格（宽 25）= `PRINTBUTTONLC "BLC", 3`，同理整行 25 格
 *
 * 文件末尾的 DLIST-ALIGN 一节另判「命中列要跟着整行位移走」，位移值按
 * `ConsoleDisplayLine.cs:70-90` 三条分支手算（基准宽度取注释里写死的格数）。
 *
 * 触屏外扩（安卓 48dp）那条没有 PC 基准可比，只断言本层自洽的不变量（覆盖原始区间、
 * 行内不重叠、宽度达标），卡点记在 `ButtonLayer.touchHitboxes` 的 TODO(verify)。
 */
class ButtonLayerTest {

    private fun text(s: String) = TextRun(s, 0xFFFFFF, 0x000000, 0, "msgothic")

    private fun button(s: String, value: Any?, id: Int = -1) =
        TextRun(s, 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.BUTTON, value, buttonId = id)

    private fun row(vararg runs: TextRun) =
        DisplayRow(runs.toList(), runs.sumOf { Grid.cells(it.text) }, true, true)

    /**
     * 带整行位移的行：`indent` 是手算的 `num3`（`ConsoleDisplayLine.SetAlignment` 的返回值），
     * 三条分支见 spec: `ConsoleDisplayLine.cs:72-83`：LEFT = 0、CENTER = `基准/2 - cells/2`、
     * RIGHT = `基准 - cells`；`pointX` 在折行后的整数网格里恒 0（`LineFolder.relayout` 从 0 起排），
     * 所以 `num3 = num2 - pointX` 就等于 `num2`（spec: `ConsoleDisplayLine.cs:86-90`）。
     */
    private fun shiftedRow(indent: Int, align: Alignment, vararg runs: TextRun) =
        DisplayRow(
            runs = runs.toList(),
            cells = runs.sumOf { Grid.cells(it.text) },
            isLogicalLine = true,
            isLineEnd = true,
            align = align,
            indentCells = indent,
        )

    /**
     * 按钮的 `title` 要落进命中区，供壳层长按显示（原版 PC 是 hover ToolTip，
     * spec: `EmueraConsole.cs:1891-1917`）。同一源按钮被样式标签拆成多段时，
     * 提示取先出现的非 null（合并分支）。
     */
    @Test
    fun `按钮提示随命中区带出`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(
                    TextRun("剧", 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.BUTTON, 7L, buttonId = 3, tooltip = "初始剧本<br>内容量适中"),
                    TextRun(
                        "本", 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.BUTTON, 7L,
                        buttonId = 3, tooltip = "初始剧本<br>内容量适中",
                    ),
                ),
            ),
        )
        val hit = layer.hit(0, 0)
        assertNotNull(hit)
        assertEquals("剧本", hit.label, "命中区仍是一条按钮")
        // 提示区间随每个段铺开：覆盖整条按钮的起点与内部格都能查到
        //（flatten 把同一份 buttonTitle 印到按钮的每个 run 上）。
        assertEquals("初始剧本<br>内容量适中", layer.tooltip(0, 0))
        assertEquals("初始剧本<br>内容量适中", layer.tooltip(0, 1))
        assertEquals(null, layer.tooltip(0, 9), "行外没有提示")
    }

    /**
     * `<nonbutton title='…'>` 的提示挂在**不可点**文本上：长按查得到，点击仍无命中
     * （原版非按钮的 `ConsoleButtonString` 也带 `Title`，`HtmlManager.cs:841-847`）。
     */
    @Test
    fun `不可点文本也能带提示且不变成可点`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(row(TextRun("过滤提示", 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.TEXT, null, tooltip = "当前显示角色均为有剧本角色"))),
        )
        assertEquals("当前显示角色均为有剧本角色", layer.tooltip(0, 1))
        assertNull(layer.hit(0, 1), "提示不得让不可点文本进入命中表")
        assertTrue(layer.all().isEmpty())
    }

    /** 基准 257 行：`BTN` 三格，`colStart` 与 `colEnd - 1` 命中，`colEnd` 不命中（半开区间）。 */
    @Test
    fun `边界三点 colStart 与 colEnd 减一命中 colEnd 不命中`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("BTN", 1L))))
        assertEquals(listOf(HitTarget(0, 0, 3, 1L)), layer.all())
        assertNotNull(layer.hit(0, 0))
        assertNotNull(layer.hit(0, 2))
        assertNull(layer.hit(0, 3))
        assertEquals(1L, layer.hit(0, 2)?.value)
        assertEquals(0, layer.hit(0, 0)?.colStart)
        assertEquals(3, layer.all().single().colEnd)
    }

    /** 按钮前后的普通段照常推进列游标，非按钮列返回 null。 */
    @Test
    fun `非按钮列返回 null 前后文本段参与列游标`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(text("AB"), button("CD", 9L), text("EF"))))
        assertEquals(listOf(HitTarget(0, 2, 4, 9L)), layer.all())
        assertNull(layer.hit(0, 0))
        assertNull(layer.hit(0, 1))
        assertEquals(9L, layer.hit(0, 2)?.value)
        assertEquals(9L, layer.hit(0, 3)?.value)
        assertNull(layer.hit(0, 4))
        assertNull(layer.hit(0, 5))
    }

    /** 同行相邻两按钮：区间首尾相接、互不重叠（列游标单调右移）。 */
    @Test
    fun `同行相邻两按钮 区间相接不重叠`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("A", 10L), text("-"), button("BB", 20L))))
        assertEquals(2, layer.all().size)
        assertEquals(HitTarget(0, 0, 1, 10L), layer.all()[0])
        assertEquals(HitTarget(0, 2, 4, 20L), layer.all()[1])
        assertEquals(10L, layer.hit(0, 0)?.value)
        assertNull(layer.hit(0, 1))
        assertEquals(20L, layer.hit(0, 2)?.value)
        assertRowIntervalsDoNotOverlap(layer.all())
    }

    /** 基准 258 行：`PRINTBUTTON "[X]", "SVAL"` 的值是 String（不是 Long）。 */
    @Test
    fun `命中值分派 字符串按钮 value 为 String`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("[X]", "SVAL"))))
        val target = layer.hit(0, 1)
        assertNotNull(target)
        assertEquals("SVAL", target.value)
        assertTrue(target.value is String)
        assertEquals("SVAL", layer.selectedString(target, ButtonAcceptance.STR))
    }

    /** 基准 257 行：数值按钮的 value 是 Long；回环串是十进制（`ConsoleButtonString.cs:85`）。 */
    @Test
    fun `命中值分派 数值按钮 value 为 Long 回环串取十进制`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("BTN", 1L))))
        val target = layer.hit(0, 0)
        assertNotNull(target)
        assertTrue(target.value is Long)
        assertEquals("1", layer.selectedString(target, ButtonAcceptance.INT))
        assertEquals("1", layer.selectedString(target, ButtonAcceptance.STR))
        assertEquals("1", layer.selectedString(target, ButtonAcceptance.ANY))
    }

    /**
     * 数值等待（BINPUT，`FunctionIdentifier.cs:2131` 的 `InputType.IntButton`）期间
     * 字符串按钮连被指着都不算（`EmueraConsole.cs:2439,2456`）；
     * 字符串等待（BINPUTS，`FunctionIdentifier.cs:2235`）期间数值按钮也可点
     * （`EmueraConsole.cs:415-417` 读 `Inputs`，而数值按钮构造时就有
     * `Inputs = input.ToString()`，`ConsoleButtonString.cs:85`）。
     */
    @Test
    fun `数值等待期 字符串按钮不可点 字符串等待期两类都可点`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("[X]", "SVAL"), button("BTN", 1L))))
        assertNull(layer.hit(0, 0, ButtonAcceptance.INT))
        assertNull(layer.hit(0, 2, ButtonAcceptance.INT))
        assertEquals(1L, layer.hit(0, 4, ButtonAcceptance.INT)?.value)
        assertEquals("SVAL", layer.hit(0, 0, ButtonAcceptance.STR)?.value)
        assertEquals(1L, layer.hit(0, 4, ButtonAcceptance.STR)?.value)
        assertEquals("1", layer.click(0, 4, ButtonAcceptance.STR))
        assertNull(layer.click(0, 0, ButtonAcceptance.INT))
        assertEquals("SVAL", layer.click(0, 0, ButtonAcceptance.ANY))
    }

    /**
     * 跨行按钮：各行一条且 `value` 同一实例 —— 原版 `DivideAt` 把
     * `Input/Inputs/IsInteger` 整份抄给后半截（`ConsoleButtonString.cs:247-251`），
     * 后半截交给下一行重排（`PrintStringBuffer.cs:224-235,242-249`，与 m-grid 拆分语义一致）。
     */
    @Test
    fun `跨行按钮 各行一条且 value 同实例`() {
        val layer = ButtonLayer()
        val shared = "SVAL"
        layer.attach(
            listOf(
                row(text("AB"), button("CD", shared)),
                row(button("EF", shared), text("GH")),
            )
        )
        assertEquals(2, layer.all().size)
        assertEquals(HitTarget(0, 2, 4, shared), layer.all()[0])
        assertEquals(HitTarget(1, 0, 2, shared), layer.all()[1])
        assertSame(layer.all()[0].value, layer.all()[1].value)
        assertEquals(shared, layer.hit(0, 3)?.value)
        assertEquals(shared, layer.hit(1, 1)?.value)
        assertNull(layer.hit(0, 4))
        assertNull(layer.hit(1, 2))
    }

    /**
     * 重复 value 的两个按钮仍是两条独立条目，各自命中各自。
     */
    @Test
    fun `重复 value 的两个按钮 各自独立条目`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("A", 7L), text("--"), button("B", 7L))))
        assertEquals(2, layer.all().size)
        assertEquals(HitTarget(0, 0, 1, 7L), layer.all()[0])
        assertEquals(HitTarget(0, 3, 4, 7L), layer.all()[1])
        assertEquals(0, layer.hit(0, 0)?.colStart)
        assertEquals(3, layer.hit(0, 3)?.colStart)
        assertEquals(7L, layer.hit(0, 0)?.value)
        assertEquals(7L, layer.hit(0, 3)?.value)
        assertNull(layer.hit(0, 1))
        assertNull(layer.hit(0, 2))
    }

    // ------------------------------------------------------------------
    // 条数口径：一条原版 ConsoleButtonString = 一条命中区
    //   ConsoleDisplayLine.cs:18,28 的 buttons 数组；ConsoleButtonString.cs:19、:296-303 的 StrArray。
    //   渲染层把一条按钮按样式搬成多条 run，只有源按钮编号（TextRun.buttonId）能把它
    //   与「两个值相同、列号相接的独立按钮」分开。
    // ------------------------------------------------------------------

    /**
     * 同一源按钮的两个样式片（`buttonId` 相同、列号相接、值相同）并成**一条**命中区：
     * 原版 `StrArray` 装两个 `ConsoleStyledString` 的仍是一个 `ConsoleButtonString`
     * （ConsoleButtonString.cs:19、:296-303），`ConsoleDisplayLine.Buttons` 长度 1。
     */
    @Test
    fun `同一源按钮的相邻同值片并成一条命中区`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("AB", 7L, id = 0), button("CD", 7L, id = 0))))
        assertEquals(listOf(HitTarget(0, 0, 4, 7L)), layer.all())
        assertEquals(7L, layer.hit(0, 0)?.value)
        assertEquals(7L, layer.hit(0, 3)?.value)
        assertNull(layer.hit(0, 4))
    }

    /**
     * 两个**不同**源按钮即使值相同、列号相接也是两条命中区：
     * 原版两次 `AppendButton` 是两条 `ConsoleButtonString`（PrintStringBuffer.cs:103-123），
     * `ConsoleDisplayLine.Buttons` 长度 2，各自 `Input` 各读各的（EmueraConsole.cs:411-417）。
     */
    @Test
    fun `两个相邻同值但源按钮不同的按钮是两条命中区`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("AB", 7L, id = 0), button("CD", 7L, id = 1))))
        assertEquals(
            listOf(HitTarget(0, 0, 2, 7L), HitTarget(0, 2, 4, 7L)),
            layer.all(),
            "值与列号都相接，源按钮编号不同 ⇒ 两条",
        )
        assertEquals("7", layer.click(0, 0))
        assertEquals("7", layer.click(0, 3))
    }

    /** `buttonId == -1`（身份未知的手工 run）退回老口径：列号相接 + 值相同就并。 */
    @Test
    fun `身份未知的相邻同值 run 按老口径合并`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("AB", 7L), button("CD", 7L))))
        assertEquals(listOf(HitTarget(0, 0, 4, 7L)), layer.all())
    }

    /** 空 rows：无条目、任何坐标都不命中。 */
    @Test
    fun `空 rows 空表且恒不命中`() {
        val layer = ButtonLayer()
        layer.attach(emptyList())
        assertTrue(layer.all().isEmpty())
        assertNull(layer.hit(0, 0))
        assertNull(layer.hit(-1, 0))
        assertNull(layer.hit(0, -1))
        assertNull(layer.hit(9, 9))
    }

    /**
     * 零宽按钮不入表：空文本按钮原版根本不打印
     * （`EmueraConsole.cs:3295,3303` 的 `string.IsNullOrEmpty` 守卫）；
     * `buttonValue` 为 null 的段与非 BUTTON kind 的段都不算按钮。
     */
    @Test
    fun `零宽按钮不入表 非按钮 kind 与 null 值也不入表`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(
                    button("", 1L),
                    button("B", 2L),
                    button("C", null),
                    TextRun("D", 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.PLAIN, 3L),
                )
            )
        )
        assertEquals(listOf(HitTarget(0, 0, 1, 2L)), layer.all())
        assertEquals(2L, layer.hit(0, 0)?.value)
        assertNull(layer.hit(0, 1))
    }

    /** 重新 attach 整表替换（原版按 `Generation` 作废旧按钮，`EmueraConsole.cs:2439,2448`）。 */
    @Test
    fun `重入 attach 整表替换并清指针态`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("A", 1L))))
        layer.setPointer(0, 0, pressed = true)
        assertTrue(layer.isPressed(layer.all().single()))
        layer.attach(listOf(row(text("xx"), button("yy", 2L))))
        assertEquals(1, layer.all().size)
        assertEquals(HitTarget(0, 2, 4, 2L), layer.all().single())
        assertNull(layer.hit(0, 0))
        val fresh = layer.all().single()
        assertFalse(layer.isFocused(fresh))
        assertFalse(layer.isPressed(fresh))
        layer.attach(emptyList())
        assertTrue(layer.all().isEmpty())
    }

    /** 焦点/按下只是绘制标记：设了指针也不得改变 `hit` 的结果与条目表。 */
    @Test
    fun `焦点与按下态只影响绘制标记 不改变命中`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("BTN", 1L), text("--"), button("[X]", "SVAL"))))
        val intTarget = layer.hit(0, 1)
        val strTarget = layer.hit(0, 6)
        assertNotNull(intTarget)
        assertNotNull(strTarget)
        layer.setPointer(0, 1, pressed = false)
        assertEquals(intTarget, layer.hit(0, 1))
        assertTrue(layer.isFocused(intTarget))
        assertFalse(layer.isPressed(intTarget))
        layer.setPointer(0, 1, pressed = true)
        assertEquals(intTarget, layer.hit(0, 1))
        assertTrue(layer.isFocused(intTarget))
        assertTrue(layer.isPressed(intTarget))
        assertFalse(layer.isFocused(strTarget))
        assertEquals(listOf(HitTarget(0, 0, 3, 1L), HitTarget(0, 5, 8, "SVAL")), layer.all())
        layer.clearPointer()
        assertFalse(layer.isFocused(intTarget))
        assertFalse(layer.isPressed(intTarget))
        assertEquals(intTarget, layer.hit(0, 1))
    }

    /** 基准 259 行：`PRINTBUTTONC "BC", 2` 右对齐补齐 25 格，补齐段也在命中区内。 */
    @Test
    fun `基准 PRINTBUTTONC 整行 25 格都是命中区`() {
        val label = " ".repeat(23) + "BC"
        val layer = ButtonLayer()
        layer.attach(listOf(row(button(label, 2L))))
        assertEquals(25, layer.all().single().colEnd)
        assertEquals(HitTarget(0, 0, 25, 2L), layer.all().single())
        assertEquals(2L, layer.hit(0, 0)?.value)
        assertEquals(2L, layer.hit(0, 24)?.value)
        assertNull(layer.hit(0, 25))
        assertEquals("2", layer.click(0, 0, ButtonAcceptance.INT))
    }

    /** 基准 260 行：`PRINTBUTTONLC "BLC", 3` 左对齐补齐 25 格。 */
    @Test
    fun `基准 PRINTBUTTONLC 整行 25 格都是命中区`() {
        val label = "BLC" + " ".repeat(22)
        val layer = ButtonLayer()
        layer.attach(listOf(row(button(label, 3L))))
        assertEquals(HitTarget(0, 0, 25, 3L), layer.all().single())
        assertEquals(3L, layer.hit(0, 24)?.value)
        assertNull(layer.hit(0, 25))
    }

    /** 基准 257 到 260 四行一起 attach：行号即 rows 下标，各行独立。 */
    @Test
    fun `基准四行按钮 行号即 rows 下标`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(button("BTN", 1L)),
                row(button("[X]", "SVAL")),
                row(button(" ".repeat(23) + "BC", 2L)),
                row(button("BLC" + " ".repeat(22), 3L)),
            )
        )
        assertEquals(
            listOf(
                HitTarget(0, 0, 3, 1L),
                HitTarget(1, 0, 3, "SVAL"),
                HitTarget(2, 0, 25, 2L),
                HitTarget(3, 0, 25, 3L),
            ),
            layer.all()
        )
        assertNull(layer.hit(1, 0, ButtonAcceptance.INT))
        assertEquals(2L, layer.hit(2, 23, ButtonAcceptance.INT)?.value)
        assertEquals("SVAL", layer.hit(1, 2, ButtonAcceptance.STR)?.value)
    }

    /** 全半角混排列宽：一个全角字符占 2 格 ⇒ 命中区按半角格数计。 */
    @Test
    fun `全半角混排 按钮区间按半角格计`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("按钮A", 5L))))
        assertEquals(HitTarget(0, 0, 5, 5L), layer.all().single())
        assertNotNull(layer.hit(0, 4))
        assertNull(layer.hit(0, 5))
    }

    /**
     * 触屏外扩：宽度不足补到最小尺寸，一侧夹不住时差额让给另一侧；
     * 外扩后的集合仍覆盖各自原始区间且行内两两不相交。
     */
    @Test
    fun `触屏外扩 补足最小宽度且不吞邻居`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("BTN", 1L))))
        val wide = layer.touchHitboxes(8)
        assertEquals(listOf(HitTarget(0, 0, 8, 1L)), wide)
        assertRowIntervalsDoNotOverlap(wide)

        val tight = ButtonLayer()
        tight.attach(listOf(row(button("A", 1L), button("BB", 2L))))
        val expanded = tight.touchHitboxes(8)
        assertEquals(2, expanded.size)
        // 前一个被后一个的原始左沿（1）夹死，只能保持原区间
        assertEquals(HitTarget(0, 0, 1, 1L), expanded[0])
        // 后一个左侧被夹住，差额全让给右：[1, 9) 宽度 8
        assertEquals(HitTarget(0, 1, 9, 2L), expanded[1])
        assertEquals(8, expanded[1].colEnd - expanded[1].colStart)
        assertRowIntervalsDoNotOverlap(expanded)

        val room = ButtonLayer()
        room.attach(listOf(row(button("A", 1L), text("x".repeat(20)), button("B", 2L))))
        val grown = room.touchHitboxes(6)
        assertEquals(6, grown[0].colEnd - grown[0].colStart)
        assertEquals(6, grown[1].colEnd - grown[1].colStart)
        assertTrue(grown[0].colStart <= 0 && grown[0].colEnd >= 1)
        assertTrue(grown[1].colStart <= 21 && grown[1].colEnd >= 22)
        assertRowIntervalsDoNotOverlap(grown)
    }

    /** 最小尺寸不大于 1 时无需外扩，原样返回。 */
    @Test
    fun `触屏外扩 minWidthCells 不大于 1 原样返回`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("BTN", 1L), text("-"), button("X", "SVAL"))))
        assertEquals(layer.all(), layer.touchHitboxes(1))
        assertEquals(layer.all(), layer.touchHitboxes(0))
        assertEquals(layer.all(), layer.touchHitboxes(-5))
        assertEquals(HitTarget(0, 0, 3, 1L), layer.touchHitboxes(3)[0])
    }

    // ------------------------------------------------------------------
    // DLIST-ALIGN：命中列必须跟着整行位移走
    //   位移算式 spec: ConsoleDisplayLine.cs:70-90；施加 spec: ConsoleButtonString.cs:307-315
    //   （`PointX += shiftX`，`Width` 不动）；命中判定读的正是这份被平移过的 PointX
    //   spec: EmueraConsole.cs:2358-2361。
    // ------------------------------------------------------------------

    /**
     * **同一行**（同一个 row 下标、同一段内容）在 CENTER 与 LEFT 下命中列必须不同
     * —— 契约 §2 d-align 指定的证伪判据。
     *
     * 内容 `[button "BTN"(3), text "--"(2)]` 共 5 格，基准宽度取 20 半角格
     * （对应 `Config.DrawableWidth`，spec: `Config.cs:466`）：
     * - LEFT：`num2 = 0`、`num3 = 0 - 0 = 0`（spec: `ConsoleDisplayLine.cs:72-78,86-90`）；
     * - CENTER：`num2 = 20/2 - 5/2 = 10 - 2 = 8`（spec: `ConsoleDisplayLine.cs:80`）。
     * 于是同一段按钮文本，LEFT 命中 `[0,3)`、CENTER 命中 `[8,11)`。
     */
    @Test
    fun `同一行 CENTER 与 LEFT 的命中列不同`() {
        val left = ButtonLayer()
        left.attach(listOf(shiftedRow(0, Alignment.LEFT, button("BTN", 1L), text("--"))))
        val center = ButtonLayer()
        center.attach(listOf(shiftedRow(8, Alignment.CENTER, button("BTN", 1L), text("--"))))

        assertEquals(listOf(HitTarget(0, 0, 3, 1L)), left.all())
        assertEquals(listOf(HitTarget(0, 8, 11, 1L)), center.all())
        // 行 0 的第 0 格：LEFT 命中、CENTER 不命中（CENTER 行前面 8 格是位移出来的空白）。
        assertEquals(1L, left.hit(0, 0)?.value)
        assertNull(center.hit(0, 0))
        assertNull(center.hit(0, 7))
        // 行 0 的第 8 格：CENTER 命中、LEFT 不命中。
        assertEquals(1L, center.hit(0, 8)?.value)
        assertEquals(1L, center.hit(0, 10)?.value)
        assertNull(center.hit(0, 11))
        assertNull(left.hit(0, 8))
        assertEquals(
            left.all().single().let { it.colEnd - it.colStart },
            center.all().single().let { it.colEnd - it.colStart },
            "位移只移 PointX、不改 Width（spec: ConsoleButtonString.cs:307-315）",
        )
        // 回环串与对齐无关。
        assertEquals("1", center.click(0, 9, ButtonAcceptance.INT))
    }

    /**
     * 三种对齐同内容各按自己的位移入表（RIGHT 手算 `20 - 5 = 15`，spec: `ConsoleDisplayLine.cs:83`），
     * 行内前导文本段照样推进游标 —— 位移只加在游标起点上。
     */
    @Test
    fun `三种对齐的命中列各按位移平移且区间不重叠`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                shiftedRow(0, Alignment.LEFT, text("--"), button("BTN", 1L)),
                shiftedRow(8, Alignment.CENTER, text("--"), button("BTN", 2L)),
                shiftedRow(15, Alignment.RIGHT, text("--"), button("BTN", 3L)),
            )
        )
        assertEquals(
            listOf(
                HitTarget(0, 2, 5, 1L),
                HitTarget(1, 10, 13, 2L),
                HitTarget(2, 17, 20, 3L),
            ),
            layer.all(),
        )
        assertRowIntervalsDoNotOverlap(layer.all())
        assertEquals(2L, layer.hit(1, 12)?.value)
        assertEquals(3L, layer.hit(2, 17)?.value)
        assertNull(layer.hit(1, 8), "CENTER 行的前导文本占 8、9 两格，第 8 格不该算按钮")
    }

    /**
     * 折行产生的每一行**各自**平移（`SetAlignment` 在 `addDisplayLine` 里逐行调用，
     * spec: `EmueraConsole.cs:2937-2944`），所以跨行按钮的后半截在次行的起点是
     * 「本行游标（从本行位移起）+ 次行前导段」而不是 0。
     *
     * 基准宽度取 10 格：首行 10 格满行 ⇒ CENTER `10/2 - 10/2 = 0`；
     * 次行 4 格 ⇒ CENTER `10/2 - 4/2 = 5 - 2 = 3`（spec: `ConsoleDisplayLine.cs:80`）。
     */
    @Test
    fun `折行的两行各自平移 跨行按钮值同实例`() {
        val layer = ButtonLayer()
        val shared = "SVAL"
        layer.attach(
            listOf(
                shiftedRow(0, Alignment.CENTER, button("AB", shared), text("abcdefgh")),
                shiftedRow(3, Alignment.CENTER, button("CD", shared), text("ef")),
            )
        )
        assertEquals(listOf(HitTarget(0, 0, 2, shared), HitTarget(1, 3, 5, shared)), layer.all())
        assertSame(layer.all()[0].value, layer.all()[1].value, "DivideAt 把值整份抄给后半截（ConsoleButtonString.cs:247-251）")
        assertNotNull(layer.hit(1, 3))
        assertNull(layer.hit(1, 2), "次行的前两格是位移出来的空白，不属于按钮")
        assertNull(layer.hit(1, 5))
    }

    /**
     * 位移为负（超宽行在 RIGHT 下 `num2 = 基准 - num` 为负，spec: `ConsoleDisplayLine.cs:83`；
     * `ShiftPositionX` 只做加法、可以把 `PointX` 移成负数，spec: `ConsoleButtonString.cs:307-315`）：
     * 本层不把 `colStart` 夹回 0，整段出左界的按钮在可见列上恒不命中
     * —— 与原版命中式 `PointX > x || PointX + Width < x`（spec: `EmueraConsole.cs:2361`，
     * `x` 是 `MainPicBox` 内的非负像素坐标，spec: `EmueraConsole.cs:2321`）逐值同形。
     */
    @Test
    fun `负位移 出左界的按钮不入可见列 回到屏内的按钮可点`() {
        // 两行的内容宽度都是 14 格，基准宽度取 8 格（比内容窄，模拟超宽行）：
        // RIGHT 的 num2 = 8 - 14 = -6（spec: ConsoleDisplayLine.cs:83），num3 = num2 - pointX = -6（:86-90）。
        val off = shiftedRow(-6, Alignment.RIGHT, button("AB", 1L), text("abcdefghijkl"))
        val back = shiftedRow(-6, Alignment.RIGHT, text("abcdefghij"), button("klmn", 7L))
        val layer = ButtonLayer()
        layer.attach(listOf(off, back))
        assertEquals(
            listOf(
                HitTarget(0, -6, -4, 1L),
                HitTarget(1, 4, 8, 7L),
            ),
            layer.all(),
            "区间左沿保持位移后的原值，不夹到 0",
        )
        assertNull(layer.hit(0, 0))
        assertNull(layer.hit(0, 5), "整段画到左沿外的按钮不可点")
        assertNull(layer.hit(0, -1), "屏幕坐标不含负列（原版 x 取自 MainPicBox）")
        assertEquals(7L, layer.hit(1, 4)?.value)
        assertEquals(7L, layer.hit(1, 7)?.value)
        assertNull(layer.hit(1, 3))
        assertNull(layer.hit(1, 8))
        assertEquals("7", layer.click(1, 6, ButtonAcceptance.INT))
    }

    /**
     * `temporary` 不产生任何命中差别：显示行的 `IsTemporary`（spec: `ConsoleDisplayLine.cs:14,46`）
     * 只被 `LastLineIsTemporary` 一个属性读（spec: `EmueraConsole.cs:513-522`），而它的调用点
     * 全在入列侧与语句侧（spec: `EmueraConsole.cs:2891-2893`、`Process.cs:2426,2514,2644`）；
     * 命中循环（spec: `EmueraConsole.cs:2354-2361`）里没有临时行分支 ——
     * 临时行在屏上的那一帧按钮照旧可点。删除动作归 `adapter/ConsoleAdapter.appendMerged`。
     */
    @Test
    fun `临时行标记不改变命中列与可点性`() {
        val formal = ButtonLayer()
        formal.attach(listOf(shiftedRow(8, Alignment.CENTER, button("BTN", 1L), text("--"))))
        val temporary = ButtonLayer()
        temporary.attach(
            listOf(
                DisplayRow(
                    runs = listOf(button("BTN", 1L), text("--")),
                    cells = 5,
                    isLogicalLine = true,
                    isLineEnd = true,
                    align = Alignment.CENTER,
                    indentCells = 8,
                    temporary = true,
                )
            )
        )
        assertEquals(formal.all(), temporary.all(), "临时行的命中区间必须与正式行逐字段相同")
        assertEquals(1L, temporary.hit(0, 8)?.value)
        assertNull(temporary.hit(0, 7))
    }

    /**
     * 触屏外扩的左界是**本行最左列**（位移后的值），不再是硬编码的 0 列：
     * 无位移行两者相同（既有两条外扩用例都不带位移，断言不变），负位移行则不越过自己最左的内容列。
     * 本条是本层自洽不变量（安卓 48dp 无 PC 基准可比，卡点见 `touchHitboxes` 的 TODO(verify)）。
     */
    @Test
    fun `触屏外扩的左界跟着位移走`() {
        val layer = ButtonLayer()
        layer.attach(listOf(shiftedRow(-6, Alignment.RIGHT, button("AB", 1L), text("abcdefghijkl"))))
        val expanded = layer.touchHitboxes(8).single()
        assertEquals(HitTarget(0, -6, 2, 1L), expanded, "左界夹到本行最左列 -6，差额让给右侧")
        assertEquals(8, expanded.colEnd - expanded.colStart)
        assertTrue(expanded.colStart <= -6 && expanded.colEnd >= -4, "外扩必须覆盖原始区间 [-6, -4)")
    }

    /** 不变量：同一行内目标区间两两不相交（按 attach 序逐对检查）。 */
    private fun assertRowIntervalsDoNotOverlap(targets: List<HitTarget>) {
        targets.groupBy { it.row }.values.forEach { perRow ->
            for (i in 1 until perRow.size) {
                assertTrue(perRow[i - 1].colEnd <= perRow[i].colStart, "同行区间重叠：$perRow")
            }
        }
    }
}
