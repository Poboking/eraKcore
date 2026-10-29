package com.eratw.port.render.displaylist

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `DisplayList` 的行为规约测试（M4-DLIST 关键判据逐条对应）。
 *
 * 期望值全部来自 C# 参考实现与 Kernel 的宽度模型，不拿实现回读当期望：
 * - 幂等「无变化不重绘」spec: `EmueraConsole.cs:1758`（`lastDrawnLineNo == lineNo` 直接 return）
 *   与 `EmueraConsole.cs:1942-1949`（画完才写水位）；
 * - 行级脏粒度 spec: `ConsoleDisplayLine.cs:112-116`（擦一个行高矩形）、`EmueraConsole.cs:1852-1856`（逐行画）；
 * - 行数上限头部丢弃 spec: `EmueraConsole.cs:2988-2996`；默认值 spec: `ConfigData.cs:62`；
 * - PRINTN 未结束行的尾部并入 spec: `EmueraConsole.cs:2947-2967`；
 * - 提交期测量、绘制期不测量 spec: `ConsoleButtonString.cs:257-270`、`ConsoleDisplayLine.cs:118-125`；
 * - 对齐位移落到列起点 spec: `ConsoleDisplayLine.cs:70-90` + `ConsoleButtonString.cs:307-315`
 *   （见文件末尾 DLIST-ALIGN 一节，位移值全部按那三条分支手算）。
 */
class DisplayListTest {

    private fun run(
        text: String,
        fore: Int = 0x112233,
        back: Int = 0xFFFFFF,
        style: Int = 0,
        font: String = "sarasa",
        kind: RunKind = RunKind.TEXT,
    ) = TextRun(text, fore, back, style, font, kind)

    /** 单段行；`cells` 用 Kernel 的半角 1 / 全角 2 口径（与 m-grid 同一宽度模型）。 */
    private fun row(
        text: String,
        style: Int = 0,
        fore: Int = 0x112233,
        logical: Boolean = true,
        lineEnd: Boolean = true,
    ) = DisplayRow(listOf(run(text, fore = fore, style = style)), Grid.cells(text), logical, lineEnd)

    @Test
    fun `首次提交把全部行合成一条脏带`() {
        val dl = DisplayList(clientWidth = 1800)
        val rects = dl.submit(listOf(row("abc"), row("日本語")))
        // "abc" 3 格、"日本語" 6 格：合并后的带取最大列跨度。
        assertEquals(listOf(DirtyRect(0, 2, 0, 6)), rects)
        assertEquals(2, dl.rowCount)
    }

    @Test
    fun `追加一行只脏该行`() {
        val dl = DisplayList(clientWidth = 1800)
        val first = listOf(row("aaa"), row("bbb"))
        dl.submit(first)
        val rects = dl.submit(first + row("cccc"))
        assertEquals(listOf(DirtyRect(2, 3, 0, 4)), rects, "新行之外的行不该被标脏")
        assertEquals(3, dl.rowCount)
    }

    @Test
    fun `样式变化只脏改动行且绘制命令带新样式位`() {
        val dl = DisplayList(clientWidth = 1800)
        dl.submit(listOf(row("aaa"), row("bbb"), row("ccc")))
        val changed = listOf(row("aaa"), row("bbb", style = Grid.STYLE_BOLD), row("ccc"))
        val rects = dl.submit(changed)
        assertEquals(listOf(DirtyRect(1, 2, 0, 3)), rects)
        val cmd = dl.render().single { it.row == 1 }
        assertEquals(Grid.STYLE_BOLD, cmd.styleMask)
        assertEquals(0, dl.render(emptyList()).size, "空脏区不产出绘制命令")
    }

    @Test
    fun `全清时脏区覆盖全部行`() {
        val dl = DisplayList(clientWidth = 1800)
        dl.submit(listOf(row("a"), row("bb中"), row("c")))
        val rects = dl.submit(emptyList())
        // 三条行全部被清空，列跨度取被擦掉内容的最宽者（"bb中" = 2 + 2 = 4 格）。
        assertEquals(listOf(DirtyRect(0, 3, 0, 4)), rects)
        assertEquals(0, dl.rowCount)
        assertEquals(emptyList<DrawCmd>(), dl.render())
    }

    @Test
    fun `内容无变化的重复提交第二次脏区为空`() {
        val dl = DisplayList(clientWidth = 1800)
        val snapshot = listOf(row("hello"), row("世界"))
        assertTrue(dl.submit(snapshot).isNotEmpty(), "首次提交必有脏区")
        assertEquals(emptyList<DirtyRect>(), dl.submit(snapshot), "同一份快照重复提交必须幂等")
        // 换成等值但不同实例的行也一样：脏判定走结构相等，不看对象身份。
        val copy = listOf(row("hello"), row("世界"))
        assertEquals(emptyList<DirtyRect>(), dl.submit(copy))
        assertEquals(2, dl.rowCount)
    }

    @Test
    fun `空对空提交也幂等`() {
        val dl = DisplayList(clientWidth = 40)
        assertEquals(emptyList<DirtyRect>(), dl.submit(emptyList()))
        assertEquals(emptyList<DirtyRect>(), dl.submit(emptyList()))
    }

    @Test
    fun `相邻脏行合并成一条带且干净行能把带切断`() {
        val dl = DisplayList(clientWidth = 1800)
        dl.submit(listOf(row("r0"), row("r1"), row("r2"), row("r3")))
        // 0、1 相邻变脏 -> 合成一条；2 干净；3 变脏 -> 另一条。
        val rects = dl.submit(listOf(row("R0"), row("R1"), row("r2"), row("R3")))
        assertEquals(listOf(DirtyRect(0, 2, 0, 2), DirtyRect(3, 4, 0, 2)), rects)
    }

    @Test
    fun `脏区列跨度覆盖新旧两侧内容`() {
        val dl = DisplayList(clientWidth = 1800)
        dl.submit(listOf(row("0123456789")))
        val rects = dl.submit(listOf(row("ab")))
        assertEquals(listOf(DirtyRect(0, 1, 0, 10)), rects, "旧行更宽时必须按旧宽度擦除")
    }

    @Test
    fun `行数超上限只保留末尾行且脏区裁剪到上限`() {
        val dl = DisplayList(clientWidth = 20, maxRows = 4)
        val six = (0 until 6).map { row("line$it") }
        val rects = dl.submit(six)
        assertEquals(4, dl.rowCount, "超过 maxRows 的行按原版从头部丢弃")
        assertEquals(1, rects.size, "相邻脏行合成一条带")
        assertEquals(4, rects.single().rowEnd, "脏区不得越出 maxRows 坐标系")
        // 被丢弃的头两行不存在坐标，行下标必须全在 [0, maxRows) 内。
        val cmds = dl.render()
        assertEquals(4, cmds.size)
        assertTrue(cmds.all { it.row in 0 until 4 })
        assertEquals(listOf(0, 1, 2, 3), cmds.map { it.row })
        // 保留下来的是末尾 4 行：0 号位现在是 line2。
        assertEquals("line2".map { it.code }, cmds[0].glyphs.map { it.codePoint })
    }

    @Test
    fun `运行期上抬容量后不再丢头部行`() {
        // 真机标题页：首帧 36px/28 行定下 maxRows=29；「适应屏幕」把字号压到 30px 后
        // 可见 34 行，容量不抬就会从窗口顶行开始丢 ⇒ 一页被截断 + 顶部黑带。
        val dl = DisplayList(clientWidth = 20, maxRows = 4)
        assertTrue(dl.ensureCapacity(6), "新窗口更高 ⇒ 必须上抬")
        assertEquals(6, dl.maxRows)
        val six = (0 until 6).map { row("line$it") }
        dl.submit(six)
        assertEquals(6, dl.rowCount, "抬到 6 后六个行都保留")
        assertEquals("line0".map { it.code }, dl.render().first().glyphs.map { it.codePoint })
        assertFalse(dl.ensureCapacity(3), "只升不降：缩容会平白触发整屏重画")
        assertEquals(6, dl.maxRows)
        assertFalse(dl.ensureCapacity(6), "已经够大时不动")
    }

    @Test
    fun `超上限后继续追加会让整屏位移变脏`() {
        val dl = DisplayList(clientWidth = 20, maxRows = 4)
        dl.submit((0 until 5).map { row("line$it") })
        val rects = dl.submit((0 until 6).map { row("line$it") })
        // 行下标整体前移一格，逐行比较时每行都对不上 -> 整屏一条脏带（每行 5 格）。
        assertEquals(listOf(DirtyRect(0, 4, 0, 5)), rects)
    }

    @Test
    fun `超上限的同一份快照重复提交仍为空`() {
        val dl = DisplayList(clientWidth = 20, maxRows = 4)
        val six = (0 until 6).map { row("line$it") }
        dl.submit(six)
        assertEquals(emptyList<DirtyRect>(), dl.submit(six))
    }

    @Test
    fun `段列偏移按半角1全角2累加并裁剪到clientWidth`() {
        val dl = DisplayList(clientWidth = 6)
        val wide = DisplayRow(
            listOf(run("ab"), run("cd中"), run("ef")),
            cells = 10,
            isLogicalLine = true,
            isLineEnd = true,
        )
        dl.submit(listOf(wide))
        val cmds = dl.render()
        assertEquals(2, cmds.size, "第三段落在 clientWidth 之外，不进入绘制列表")
        assertEquals(0, cmds[0].colStart)
        assertEquals(2, cmds[0].colEnd)
        assertEquals(2, cmds[1].colStart)
        assertEquals(6, cmds[1].colEnd, "越界段裁到可绘制宽度边界")
        assertTrue(dl.submit(listOf(wide)).isEmpty())
    }

    @Test
    fun `脏区列跨度不会越出clientWidth`() {
        val dl = DisplayList(clientWidth = 5)
        dl.submit(listOf(row("日本語", style = 0)))
        val rects = dl.submit(listOf(row("日本語", style = Grid.STYLE_ITALIC)))
        assertEquals(listOf(DirtyRect(0, 1, 0, 5)), rects, "行宽超界时脏区被裁到 clientWidth")
    }

    @Test
    fun `字形引用在提交期展开且TAB与代理对已归一`() {
        val dl = DisplayList(clientWidth = 40)
        val tab = DisplayRow(listOf(run("\tab")), Grid.cells("\tab"), true, true)
        val pair = DisplayRow(listOf(run("\uD83D\uDE00x")), Grid.cells("\uD83D\uDE00x"), true, true)
        dl.submit(listOf(tab, pair))
        val cmds = dl.render()
        assertEquals(10, cmds[0].glyphs.size, "TAB 先展开成 8 空格（spec: StringMeasure.cs:59-61）再加 ab，共 10 个码位")
        assertEquals(' '.code, cmds[0].glyphs[0].codePoint)
        assertEquals(10, cmds[0].colEnd)
        assertEquals(2, cmds[1].glyphs.size, "代理对合成一个码位")
        assertEquals(0x1F600, cmds[1].glyphs[0].codePoint)
        assertEquals("sarasa", cmds[1].glyphs[0].fontName)
    }

    @Test
    fun `PRINTN未结束行的并入只脏末行`() {
        val dl = DisplayList(clientWidth = 1800)
        // 原版：末行 IsLineEnd 为假时，新内容与它并成一条再入列（spec: EmueraConsole.cs:2947-2967）。
        dl.submit(listOf(row("A"), row("B", lineEnd = false)))
        val rects = dl.submit(listOf(row("A"), row("B", lineEnd = true)))
        assertEquals(listOf(DirtyRect(1, 2, 0, 1)), rects, "并入只改末行，前一行必须保持干净")
    }

    @Test
    fun `render只复用既有DrawCmd实例不再遍历文本`() {
        val dl = DisplayList(clientWidth = 1800)
        val before = dl.submit(listOf(row("keep0"), row("keep1")))
        assertTrue(before.isNotEmpty())
        val firstRender = dl.render()
        val after = dl.submit(listOf(row("keep0"), row("keep1"), row("fresh2")))
        assertEquals(1, after.size)
        val secondRender = dl.render()
        assertEquals(3, secondRender.size)
        // 干净行的命令对象是同一批实例：说明提交后没有为它们重新量文本。
        assertSame(firstRender[0], secondRender[0])
        assertSame(firstRender[1], secondRender[1])
        // 全量 render 两次得到的内容也必须完全一致（纯只读拼接，无副作用）。
        assertEquals(firstRender, secondRender.subList(0, 2))
        // 只消费脏区的帧路径只给出改动行的命令。
        assertEquals(listOf(secondRender[2]), dl.render(after))
    }

    @Test
    fun `maxRows非正数时回落到原版默认5000`() {
        val dl = DisplayList(clientWidth = 1800, maxRows = 0)
        assertEquals(Grid.MAX_LOG_DEFAULT, dl.maxRows, "默认值 spec: ConfigData.cs:62")
    }

    // ------------------------------------------------------------------
    // DLIST-ALIGN：DisplayRow.indentCells 必须落到 DrawCmd 的列起点
    //   位移算式 spec: ConsoleDisplayLine.cs:70-90（num2 三条分支 + num3 = num2 - pointX）
    //   位移施加 spec: ConsoleDisplayLine.cs:86-90 -> ConsoleButtonString.cs:307-315
    //   （只加 PointX、Width 不动），故段宽与字形序列都不随位移变化。
    // ------------------------------------------------------------------

    /**
     * 带对齐位移的行：`indent` 是**手算**的 `num3`（`ConsoleDisplayLine.SetAlignment` 的返回值），
     * 不是从实现里读回来的。
     *
     * 手算基准宽度统一取 20 半角格（对应 `Config.DrawableWidth`，spec: `Config.cs:466`），
     * 三条分支见 spec: `ConsoleDisplayLine.cs:72-83`：
     * LEFT = 0；CENTER = `20/2 - cells/2`；RIGHT = `20 - cells`。
     * 折行后每行自己走一遍 SetAlignment（spec: `EmueraConsole.cs:2937-2944`），
     * 所以这里的位移是按**本行** `cells` 算的。
     */
    private fun shifted(
        text: String,
        indent: Int,
        align: Alignment = Alignment.CENTER,
        temporary: Boolean = false,
    ) = DisplayRow(
        runs = listOf(run(text)),
        cells = Grid.cells(text),
        isLogicalLine = true,
        isLineEnd = true,
        align = align,
        indentCells = indent,
        temporary = temporary,
    )

    /**
     * `CENTER` 与 `LEFT` 同内容行的绘制列起点必须不同（契约 §2 d-align 指定的证伪判据）。
     *
     * 基准宽度取 20 半角格（对应 `Config.DrawableWidth`，spec: `Config.cs:466`），
     * 内容 "abcd" 占 4 格（`Grid.cells`）：
     * - LEFT：`num2 = 0` 且 `num3 = num2 - pointX = 0`（spec: `ConsoleDisplayLine.cs:72-78,86-90`）；
     * - CENTER：`num2 = 20/2 - 4/2 = 10 - 2 = 8`（spec: `ConsoleDisplayLine.cs:80`）；
     * - RIGHT：`num2 = 20 - 4 = 16`（spec: `ConsoleDisplayLine.cs:83`）。
     */
    @Test
    fun `三种对齐下同一内容的列起点按位移不同而段宽与字形不变`() {
        val dl = DisplayList(clientWidth = 20)
        dl.submit(
            listOf(
                shifted("abcd", indent = 0, align = Alignment.LEFT),
                shifted("abcd", indent = 8),
                shifted("abcd", indent = 16, align = Alignment.RIGHT),
            )
        )
        val cmds = dl.render()
        assertEquals(3, cmds.size)
        assertEquals(listOf(0 to 4, 8 to 12, 16 to 20), cmds.map { it.colStart to it.colEnd })
        assertEquals(listOf(4, 4, 4), cmds.map { it.colEnd - it.colStart }, "位移只移 PointX 不改 Width（ConsoleButtonString.cs:307-315）")
        assertEquals(cmds[0].glyphs, cmds[1].glyphs, "位移不得增删字形")
        assertEquals(cmds[0].glyphs, cmds[2].glyphs)
        // 三行合成一条带，左沿取最左（0）、右沿取最右（20）：擦除必须盖住所有落过内容的列段。
        assertEquals(listOf(DirtyRect(0, 3, 0, 20)), dl.submit(emptyList()))
    }

    /**
     * 同一行从 LEFT 改成 CENTER：脏区必须同时覆盖旧列段 `[0,4)` 与新列段 `[8,12)`
     * （原版靠「整行擦满 `Config.WindowX`」避免残影，spec: `ConsoleDisplayLine.cs:112-116`；
     * 本层把脏区收窄到内容列段，就必须自己取两侧并集）。
     */
    @Test
    fun `改对齐时脏区覆盖新旧两列段`() {
        val dl = DisplayList(clientWidth = 20)
        assertEquals(listOf(DirtyRect(0, 1, 0, 4)), dl.submit(listOf(shifted("abcd", 0, Alignment.LEFT))))
        assertEquals(
            listOf(DirtyRect(0, 1, 0, 12)),
            dl.submit(listOf(shifted("abcd", 8))),
            "旧内容在 0..4、新内容在 8..12：只擦新列段会把旧字留在屏上",
        )
        val cmd = dl.render().single()
        assertEquals(8, cmd.colStart)
        assertEquals(12, cmd.colEnd)
    }

    /**
     * 幂等不许退化：带位移的同一份快照重复提交仍返回空脏区。
     *
     * 首提交的脏带左沿取两行位移的最小值（8），右沿取两行列尾的最大值（`18 + 2 = 20`）：
     * 行 0 的 CENTER 位移 = `20/2 - 4/2 = 8`（spec: `ConsoleDisplayLine.cs:80`），
     * 行 1 的 RIGHT 位移 = `20 - 2 = 18`（spec: `ConsoleDisplayLine.cs:83`）。
     */
    @Test
    fun `带位移的同一份快照重复提交仍幂等`() {
        val dl = DisplayList(clientWidth = 20)
        val snapshot = listOf(shifted("abcd", 8), shifted("ef", 18, Alignment.RIGHT))
        assertEquals(listOf(DirtyRect(0, 2, 8, 20)), dl.submit(snapshot), "一条带覆盖两行各自的列段")
        assertEquals(emptyList<DirtyRect>(), dl.submit(snapshot), "无变化的重复提交必须为空")
        assertEquals(emptyList<DirtyRect>(), dl.submit(List(snapshot.size) { shiftedAt(snapshot, it) }))
        assertEquals(2, dl.rowCount)
    }

    /**
     * 位移后的行仍只脏改动行，干净行的 DrawCmd 实例继续复用（脏矩形幂等与行粒度都不许退化）。
     *
     * 位移手算（基准 20）：`aaa` CENTER = `20/2 - 3/2 = 10 - 1 = 9`（spec: `ConsoleDisplayLine.cs:80`）；
     * `ccc` RIGHT = `20 - 3 = 17`（spec: `ConsoleDisplayLine.cs:83`）；中间行 LEFT = 0。
     */
    @Test
    fun `位移不改变行级脏粒度且干净行仍复用命令实例`() {
        val dl = DisplayList(clientWidth = 20)
        fun snapshot(mid: String) = listOf(
            shifted("aaa", 9),
            DisplayRow(listOf(run(mid)), Grid.cells(mid), true, true, Alignment.LEFT, 0),
            shifted("ccc", 17, Alignment.RIGHT),
        )
        dl.submit(snapshot("bbb"))
        val before = dl.render()
        val rects = dl.submit(snapshot("BBBB"))
        assertEquals(
            listOf(DirtyRect(1, 2, 0, 4)),
            rects,
            "只有中间行变：脏区一行、列跨度取新旧并集（3 与 4 的最大者，位移都是 0）",
        )
        val after = dl.render()
        assertEquals(3, after.size)
        assertSame(before[0], after[0], "位移行的命令对象在没变时必须是同一实例（提交期只测一次）")
        assertSame(before[2], after[2])
        assertEquals(listOf(9, 0, 17), after.map { it.colStart }, "行 0 与行 2 各按自己的位移起画，行 1 是 LEFT")
    }

    /**
     * 负位移（超宽行的 CENTER/RIGHT，spec: `ConsoleDisplayLine.cs:80,83` 的减法可以为负）：
     * 整段出左界的不产命令、被左界切到的段**连字形一起裁**，列游标照常推进。
     * 只夹列号不裁字形会让整段文字右移 `|位移|` 格（绘制侧是从 `colStart` 起逐字形铺的，
     * 见 `view/ScreenComposer.drawRun`）；原版不裁字形是因为它按像素画、由 GDI 裁
     * （spec: `ConsoleStyledString.cs:119,129-138`）。
     */
    @Test
    fun `负位移下出左界的段被裁剪或丢弃且游标继续推进`() {
        val dl = DisplayList(clientWidth = 8)
        // 位移 -6 = 基准 8 - 内容 14（spec: ConsoleDisplayLine.cs:83）；本行 cells 14 > clientWidth，
        // 是 LineFolder 允许溢出的一行（PrintStringBuffer.cs:236-240）。
        val clipped = DisplayRow(
            runs = listOf(run("abcdefghij"), run("klmn")),
            cells = 14,
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.RIGHT,
            indentCells = -6,
        )
        assertEquals(listOf(DirtyRect(0, 1, 0, 8)), dl.submit(listOf(clipped)))
        val cmds = dl.render()
        assertEquals(2, cmds.size, "两段都有一部分落在屏内")
        assertEquals(listOf(0 to 4, 4 to 8), cmds.map { it.colStart to it.colEnd })
        assertEquals("ghij".map { it.code }, cmds[0].glyphs.map { it.codePoint }, "前 6 格在左界外，字形表必须一起裁掉")
        assertEquals("klmn".map { it.code }, cmds[1].glyphs.map { it.codePoint })

        // 首段整体出左界（-6 + 2 = -4 <= 0）→ 不产命令，但游标推进到 -4，次段回屏后照画。
        val dropped = DisplayRow(
            runs = listOf(run("ab"), run("cdefghijklmn")),
            cells = 14,
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.RIGHT,
            indentCells = -6,
        )
        val dl2 = DisplayList(clientWidth = 8)
        dl2.submit(listOf(dropped))
        val only = dl2.render()
        assertEquals(1, only.size, "整段在左边界外时不画（等价原版被裁到零宽）")
        assertEquals(0, only.single().colStart)
        assertEquals(8, only.single().colEnd)
        assertEquals("ghijklmn".map { it.code }, only.single().glyphs.map { it.codePoint })
    }

    /**
     * 跨左边界的那一个**全角**字整字保留（整数网格只能在码点粒度上取舍，不丢内容）：
     * 位移 -1 时 `中` 的左半格在界外，但本层仍从 0 列起铺它，`colEnd` 因此按**实际画到的列**
     * 给（0 + 4 格），比几何右沿 3 多出那半格零头 —— 记在 `DisplayList.prepare` 的
     * TODO(verify)（原版的亚像素裁剪归真机像素度量口径，spec: `ConsoleStyledString.cs:119,129-138`）。
     */
    @Test
    fun `跨左边界的全角字整字保留不丢内容`() {
        val dl = DisplayList(clientWidth = 6)
        val row = DisplayRow(
            runs = listOf(run("中文")),
            cells = 4,
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.CENTER,
            indentCells = -1,
        )
        assertEquals(listOf(DirtyRect(0, 1, 0, 4)), dl.submit(listOf(row)))
        val cmd = dl.render().single()
        assertEquals(0, cmd.colStart)
        assertEquals(4, cmd.colEnd, "右沿按画到的列给：跨界的全角字整格保留")
        assertEquals("中文".map { it.code }, cmd.glyphs.map { it.codePoint }, "跨界的那个全角字不丢")
    }

    /**
     * 负位移下的**图形段**：可见区间按几何列裁剪，不按被夹过的起点重算。
     *
     * 原版 `ConsoleImagePart.DrawTo` 把 `destRect.X = PointX + shift`
     * （spec: `ConsoleImagePart.cs:170-173`），负位移时整幅图左移、画布外那截由 GDI 裁掉，
     * 所以可见列是 `[max(col,0), min(col+cells, clientWidth))`。缺陷形态是 `start` 先夹到 0、
     * 再拿 `start + cells` 当右沿 ⇒ 整图右移 |col| 格、`colEnd` 多算被裁列
     * （宽 20、位移 -5、10 格图 ⇒ 实际可见 10 格而不是 5）。
     */
    @Test
    fun `负位移下图形段按几何列给可见区间`() {
        val dl = DisplayList(clientWidth = 20)
        val image = InlineGraphic.Image(
            resource = "bodyGraph_1",
            widthPx = 100,
            heightPx = 100,
            yOffsetPx = 0,
            widthCells = 10,
        )
        val row = DisplayRow(
            runs = listOf(
                TextRun(" ".repeat(10), 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.IMAGE, graphic = image),
            ),
            cells = 10,
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.RIGHT,
            indentCells = -5,
        )
        assertEquals(listOf(DirtyRect(0, 1, 0, 5)), dl.submit(listOf(row)))
        val cmd = dl.render().single()
        assertEquals(0, cmd.colStart, "左沿被裁到窗口内")
        assertEquals(5, cmd.colEnd, "图占 [-5,5)：可见 0..5，右沿不得按 start+10 算成 10")
        assertEquals(image, cmd.graphic)
        assertTrue(cmd.glyphs.isEmpty(), "图形段没有字形可铺")
    }

    /**
     * 位移后的内容与本层 `clientWidth` 冲突时按右沿裁列，脏区也不越出 `clientWidth`
     * （基准宽度由生产者的 `drawableWidthCells` 决定，spec: `Config.cs:466`；
     * 与 [DisplayList.clientWidth] 不一致属于调用方配置，本层不重算偏移）。
     */
    @Test
    fun `正位移越右界时列与脏区都夹到clientWidth`() {
        val dl = DisplayList(clientWidth = 10)
        // 该行的位移 8 是按基准 20 算的 CENTER（20/2 - 4/2，ConsoleDisplayLine.cs:80）。
        val rects = dl.submit(listOf(shifted("abcd", 8)))
        val cmd = dl.render().single()
        assertEquals(8, cmd.colStart)
        assertEquals(10, cmd.colEnd, "右沿裁到可绘制宽度")
        assertEquals(listOf(DirtyRect(0, 1, 8, 10)), rects)
        assertEquals(emptyList<DirtyRect>(), dl.submit(listOf(shifted("abcd", 8))), "裁剪后的脏区仍要幂等")
    }

    /**
     * `temporary` 不参与几何：同一内容只换临时标记，绘制命令逐字段相同。
     * 依据：显示行的 `IsTemporary`（spec: `ConsoleDisplayLine.cs:14,46`）只被
     * `LastLineIsTemporary` 一个属性读（spec: `EmueraConsole.cs:513-522`），调用点全在入列侧与
     * 语句侧（spec: `EmueraConsole.cs:2891-2893`、`Process.cs:2426,2514,2644`）；
     * 绘制循环（spec: `ConsoleDisplayLine.cs:118-125`）与绘制节点本身
     * （spec: `ConsoleStyledString.cs:119,129-138`）都没有临时行分支。
     * 「删除临时行」归入列侧（`adapter` 的 appendMerged），本层不得顺手实现。
     */
    @Test
    fun `临时标记不改变绘制列区间与字形`() {
        val dl = DisplayList(clientWidth = 20)
        dl.submit(listOf(shifted("abcd", 8, temporary = false)))
        val off = dl.render()
        dl.submit(listOf(shifted("abcd", 8, temporary = true)))
        val on = dl.render()
        assertEquals(off, on, "临时行在屏上的那一帧就是普通行")
        assertEquals(listOf(8), on.map { it.colStart })
    }

    /** 只换 `align` 而位移相同（例如 LEFT 与「折满整行的 CENTER」）时列起点仍由位移唯一决定。 */
    @Test
    fun `列起点只认indentCells不从align重算分支`() {
        val dl = DisplayList(clientWidth = 20)
        // cells 20 的 CENTER 按基准 20 算出的 num2 = 20/2 - 20/2 = 0（ConsoleDisplayLine.cs:80），
        // 与 LEFT 的 0 逐值相同：本层只加 indentCells，不再走一遍三条分支。
        dl.submit(listOf(shifted("abcdefghijklmnopqrst", 0, Alignment.CENTER)))
        val centered = dl.render().single()
        dl.submit(listOf(shifted("abcdefghijklmnopqrst", 0, Alignment.LEFT)))
        val left = dl.render().single()
        assertEquals(centered, left, "同一位移必须得到同一列区间，偏移算式只有生产者那一份")
    }

    /**
     * 负宽 space 是**真实回退**（原版 `ConsoleSpacePart.WidthF` 为负，
     * ConsoleSpacePart.cs:18-23；排版 `num += part.Width` 左移后续部件，ConsoleButtonString
     * 的 CalcPointX）。XRAY 叠图就是「立绘 → `space param='-{W}px'` → 图层」，
     * 折成 0 格会让图层落到立绘右侧，屏上表现为 XRAY 不生效。
     */
    @Test
    fun `负宽space把列游标回退使后续图段叠回起点`() {
        val dl = DisplayList(clientWidth = 20)
        fun image() = InlineGraphic.Image(
            resource = "im_av_chara",
            widthPx = 100,
            heightPx = 100,
            yOffsetPx = 0,
            widthCells = 10,
        )
        val row = DisplayRow(
            runs = listOf(
                TextRun(" ".repeat(10), 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.IMAGE, graphic = image()),
                TextRun("", 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.SHAPE, graphic = InlineGraphic.Space(-10)),
                TextRun(" ".repeat(10), 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.IMAGE, graphic = image()),
            ),
            cells = 10,
            isLogicalLine = true,
            isLineEnd = true,
        )
        dl.submit(listOf(row))
        val cmds = dl.render()
        assertEquals(2, cmds.size, "space 本身不产绘制命令")
        assertEquals(0, cmds[0].colStart)
        assertEquals(10, cmds[0].colEnd)
        assertEquals(0, cmds[1].colStart, "负 space 回退 10 格：第二张图必须叠回起点")
        assertEquals(10, cmds[1].colEnd)
    }

    @Test
    fun `cellsFor负宽与正宽严格互为镜像`() {
        // 半角步进 10px：+25px 占 3 格（上取整），-25px 必须回退 3 格 —— 原版在像素域加减，
        // `-Wpx` 恰好抵消前面那个 `Wpx` 段；负向若向零截断就是 2 格，每叠一层漏 1 格。
        assertEquals(-10, InlineGraphic.cellsFor(-100, 10f))
        assertEquals(-3, InlineGraphic.cellsFor(-25, 10f))
        assertEquals(-1, InlineGraphic.cellsFor(-5, 10f), "不足一格也回退一格，与正向至少占一格对称")
        assertEquals(0, InlineGraphic.cellsFor(0, 10f))
        assertEquals(3, InlineGraphic.cellsFor(25, 10f), "正宽仍向上取整")
        for (px in listOf(1, 9, 10, 11, 25, 47, 100, 476)) {
            assertEquals(
                InlineGraphic.cellsFor(px, 10f),
                -InlineGraphic.cellsFor(-px, 10f),
                "±${px}px 必须互为镜像",
            )
        }
    }

    /**
     * 立绘恰好占满一行时，`col` 已等于 `clientWidth`：收摊判定必须让负 space 先回退，
     * 否则后面的图层一张都画不出来（XRAY 满宽立绘的形态）。
     */
    @Test
    fun `列已满时负space仍回退并让后续图层落回行内`() {
        val dl = DisplayList(clientWidth = 10)
        fun image() = InlineGraphic.Image(
            resource = "im_av_chara",
            widthPx = 100,
            heightPx = 100,
            yOffsetPx = 0,
            widthCells = 10,
        )
        val row = DisplayRow(
            runs = listOf(
                TextRun(" ".repeat(10), 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.IMAGE, graphic = image()),
                TextRun("", 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.SHAPE, graphic = InlineGraphic.Space(-10)),
                TextRun(" ".repeat(10), 0x112233, 0xFFFFFF, 0, "sarasa", RunKind.IMAGE, graphic = image()),
            ),
            cells = 10,
            isLogicalLine = true,
            isLineEnd = true,
        )
        dl.submit(listOf(row))
        val cmds = dl.render()
        assertEquals(2, cmds.size, "满宽也必须画出第二张叠图")
        assertEquals(0, cmds[1].colStart)
        assertEquals(10, cmds[1].colEnd)
    }

    /** 造一条与 `snapshot[index]` **等值但不同实例**的行：幂等判定必须走结构相等，不看对象身份。 */
    private fun shiftedAt(snapshot: List<DisplayRow>, index: Int): DisplayRow {
        val src = snapshot[index]
        return DisplayRow(
            runs = src.runs.toList(),
            cells = src.cells,
            isLogicalLine = src.isLogicalLine,
            isLineEnd = src.isLineEnd,
            align = src.align,
            indentCells = src.indentCells,
            temporary = src.temporary,
        )
    }
}
