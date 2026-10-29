package com.eratw.port.render.grid

import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对拍族 ①「换行 / 折行 与全角·半角·增补平面宽度」—— M6-RENDER-AUDIT。
 *
 * 逐条口径（每条一行：C# 出处 → 本仓落地 → 结论）：
 *
 * | # | C# 出处 | 本仓落地 | 结论 |
 * |---|---|---|---|
 * | 1 | `PrintStringBuffer.cs:202` `setWidthToButtonList` 起点 `pointx = 0` | [LineFolder] 的 `relayout(from)` 从 0 起排 | 一致（逐行重排，`PrintStringBuffer.cs:244-249`） |
 * | 2 | `PrintStringBuffer.cs:217` `nobr \|\| PointX + Width <= num` 才入行 | `fold` 的 `noBr \|\| seg.pointX + seg.width <= clientWidth` | 一致（整数网格替代像素 `num = Config.DrawableWidth`，`PrintStringBuffer.cs:205`） |
 * | 3 | `PrintStringBuffer.cs:222` 三分支（ButtonWrap / 行首 / 非按钮） | `fold` 的 `!buttonWrap \|\| current.isEmpty() \|\| (...)` | 一致（`Config.ButtonWrap` 默认 false，`ConfigData.cs:83`；`CompatiLinefeedAs1739` 默认 false，`ConfigData.cs:102`） |
 * | 4 | `PrintStringBuffer.cs:424-441` 逐部件累加，溢出部件可拆则成候选 | `divideIndex` 的 `if (fitted != 0 \|\| p.canDivide) candidate = p` | 一致；「不可拆部件跳过且不计宽」那条（内层 if 无 else）也照抄 |
 * | 5 | `PrintStringBuffer.cs:453-496` 二分求最大可容纳前缀 | `maxPrefixChars` 线性扫描 | 逐值等价（宽度随字符数单调；整数网格无亚像素抖动） |
 * | 6 | `ConsoleButtonString.cs:206-229` 按 `Text.Length` 累计切点 | `FoldSegment.divideAt` 按 `p.text.length` 累计 | 一致（同为 UTF-16 `Char` 下标域） |
 * | 7 | `ConsoleButtonString.cs:231-234` 右半为空返回 null | `divideAt` 的 `if (right.isEmpty()) return null` | 一致 |
 * | 8 | `PrintStringBuffer.cs:236-240` 前缀为 0 且当前行空 → 整段入行（允许 `cells > divWidth`） | `fold` 的 `div != 0 \|\| current.isEmpty()` 分支 | 一致（用 `clientWidth = 1` 的半角/全角边界钉住） |
 * | 9 | `ConsoleButtonString.cs:247-253` 拆出的右半继承按钮四元组 | `FoldPiece.splitAt` 复用 `skeleton`（含 `buttonValue`） | 一致 |
 * | 10 | `ConsoleButtonString.cs:257-279` 空段 `Width` 记为 -1（哨兵） | 空段按 0 格 | 已登记差异（本仓类 KDoc「已知取舍」；-1 只用于「未测量」判定） |
 * | 11 | `ConsoleStyledString.cs:60-62` `Substring` 按 UTF-16 下标，理论上可劈开代理对 | `step` / `splitAt` 按码点前进 | 有意更严（整数网格不做半字符）；宽度仍逐 `Char` 累加，见 `AstralWidthTest` |
 * | 12 | `StringMeasure.cs:57-62` TAB 展开只在 **GRAPHICS** 分支里 | [Grid.cells] / [Grid.expandTabs] 无条件展开 8 空格 | **口径分歧（见下）** |
 * | 13 | `EmueraConsole.cs:3255` 宽度问 `Encoding.GetEncoding("Shift-JIS").GetByteCount` | [com.eratw.port.core.host.CellWidth] 的 `U+0000..U+001F` + `U+0020..U+007E` + `U+007F` + `U+0080` + `U+00A5` + `U+FF61..U+FF9F` | 一致（控制符/`¥` 已按实测改成 1 格，见下） |
 *
 * ## 第 12 条的实测口径
 *
 * 原版默认 `TextDrawingMode.TEXTRENDERER`（`ConfigData.cs:55`，`Config.cs:456-459` 把 WINAPI 也改回它），
 * 该分支 `StringMeasure.cs:44-46` 直接把字符交给 `TextRenderer.MeasureText`，**不经过**
 * `:57-62` 的 `s.Replace("\t", "        ")`；TAB 只在 GRAPHICS 分支被换成 8 空格。
 * 本仓把「8 空格」当成全局宽度口径（[Grid.expandTabs]），并把展开后的文本一路交给绘制。
 * GDI 对 `\t` 的实际度量（`TextFormatFlags.NoPrefix | NoPadding`、无 `DT_EXPANDTABS`）
 * 本机量不了（卡点：无 Windows GUI 读数），故这条登记为 TODO(verify) 而不是「已对齐」。
 * TODO(verify)：真机接入 `GlyphCache.measureCells` 时按实际 advance 复核；基准
 * `fixtures/golden/baseline/pc-golden-root.txt` 的 700 行里没有屏上 TAB（3 个 TAB 全是 ERB 源码回显）。
 *
 * ## 第 13 条的单字节档（.NET 读数在下面用例里逐值登记）
 *
 * 本机 `dotnet 9.0.200` + `Encoding.GetEncoding(932)`（需 `CodePagesEncodingProvider`）实测：
 * `U+0000..U+001F` / `U+007F` / `U+0080` / `U+00A5` 各 **1 字节**（`¥` best-fit 到 `0x5C`）。
 * 该读数在 2026-10-21 由 M6-RENDER-AUDIT 的缺口清单转成本表的一部分：`CellWidth.isFullWidth`
 * 现在把这四档（共 35 个码点）算作半角 1 格，与 `:3255` 的字节数口径一致
 * （逐点账在 `grid/GridWidthSingleSourceTest` 的 D 类表）。
 * 表外字（`U+203E`、`U+2016`、`U+2212`、`U+301C`）实得 1 字节是**替换回退 `?`**，与
 * [`com.eratw.port.core.host.CellWidth`] 的「表外字按全角」是同一条已登记 TODO，不属于本族新分歧。
 */
class LineFolderAuditTest {

    private fun text(s: String) = TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun button(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun line(vararg runs: TextRun) = RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true)

    /** 第 2/5/6/7 条：宽度 4、两段 `ab` + `cdef` —— 切点 `2`，右半从 0 重排（`PrintStringBuffer.cs:224-249`）。 */
    @Test
    fun `放不下的段按最大前缀切开且右半从行首重排`() {
        val rows = LineFolder(clientWidth = 4).fold(line(text("ab"), text("cdef")))
        assertEquals(2, rows.size)
        assertEquals(listOf("ab", "cd"), rows[0].runs.map { it.text })
        assertEquals(4, rows[0].cells)
        assertEquals(listOf("ef"), rows[1].runs.map { it.text })
        assertEquals(2, rows[1].cells)
        // 首行标记只在逻辑行首行（PrintStringBuffer.cs:206、:243）
        assertTrue(rows[0].isLogicalLine)
        assertTrue(!rows[1].isLogicalLine)
    }

    /**
     * 第 8 条：连一个字符都放不下时宁可行溢出也不劈字符（`PrintStringBuffer.cs:236-240`）。
     * 宽度 1 格放一个全角 `あ`（2 格）：前缀 0 ⇒ 整段入行，`cells` 大于 `clientWidth`。
     */
    @Test
    fun `单字宽于整行时整段入行且允许溢出`() {
        val rows = LineFolder(clientWidth = 1).fold(line(text("あA")))
        assertEquals(1, rows.size)
        assertEquals(listOf("あA"), rows[0].runs.map { it.text })
        assertEquals(3, rows[0].cells)
    }

    /** 第 9 条：按钮跨行拆分，每行各成一段且 `buttonValue` 相同（`ConsoleButtonString.cs:247-251`）。 */
    @Test
    fun `按钮跨行拆分后每行各一段且值相同`() {
        val rows = LineFolder(clientWidth = 5).fold(line(text("AB"), button("CDEFGH", 7L)))
        assertEquals(2, rows.size)
        assertEquals(listOf("AB", "CDE"), rows[0].runs.map { it.text })
        assertEquals(5, rows[0].cells)
        assertEquals(listOf("FGH"), rows[1].runs.map { it.text })
        assertEquals(3, rows[1].cells)
        assertEquals(7L, rows[0].runs[1].buttonValue)
        assertEquals(7L, rows[1].runs[0].buttonValue)
        assertEquals(RunKind.BUTTON, rows[1].runs[0].kind)
    }

    /**
     * 第 12 条：TAB 先展开成 8 个空格再参与折行（现状口径）。
     * `"a\tb"` → 10 格；宽 8 切成前缀 8 字符（`a` + 7 空格）+ 余下 `" b"`。
     * 注：原版 TEXTRENDERER 分支不展开（`StringMeasure.cs:44-46` vs `:57-62`），见类 KDoc。
     */
    @Test
    fun `TAB 按 8 空格展开后再折行`() {
        val rows = LineFolder(clientWidth = 8).fold(line(text("a\tb")))
        assertEquals(2, rows.size)
        assertEquals("a" + " ".repeat(7), rows[0].runs[0].text)
        assertEquals(8, rows[0].cells)
        assertEquals(" b", rows[1].runs[0].text)
        assertEquals(2, rows[1].cells)
        // 整串 10 格（1 + 8 + 1），与逐码点累加同式
        assertEquals(10, Grid.cells("a\tb"))
    }

    /**
     * 第 13 条：宽度表逐值登记（半角两段 / 全角 / 代理对 / 三处已登记分歧）。
     *
     * 逐值：`A`=1、`あ`=2、`U+FF61`（半角句点）=1、`U+FFA0`=2、`U+2501`=2
     * （基准 `pc-golden-root.txt:61` 的 99 个 `━` 填满 1797px ⇒ 单字 2 格）；
     * 代理对 `U+1F600` 逐码元累加 = 4（核心层 `CellWidth.of` 按码位 = 2，已登记驻留差异）。
     */
    @Test
    fun `宽度表逐值登记`() {
        assertEquals(1, Grid.cells("A"))
        assertEquals(2, Grid.cells("あ"))
        assertEquals(1, Grid.cells("\uFF61"))
        assertEquals(2, Grid.cells("\uFFA0"))
        assertEquals(2, Grid.cells("\u2501"))
        assertEquals(4, Grid.cells("\uD83D\uDE00"))
        assertEquals(2, Grid.cells("あA") - Grid.cells("A"))
    }

    /**
     * 第 13 条的单字节档与已登记分歧逐值钉住（`.NET` `GetEncoding(932).GetByteCount` 实测：
     * C0/DEL/U+0080/U+00A5 各 1 字节）。
     *
     * 2026-10-21 之前这几条记的是 2 格（旧表按「其余一律 2 格」），
     * M6-RENDER-AUDIT 把这条缺口登记后按 `.NET` 读数改成 1 格；谁再动 `CellWidth` 这张表，
     * 这条与 `grid/GridWidthSingleSourceTest` 的 A/D 类表会先炸。
     */
    @Test
    fun `CP932 可编码字与单字节档逐值`() {
        // 一致：ASCII 可打印 1 字节、全角假名 2 字节
        assertEquals(1, Grid.cells("\u0020"))
        assertEquals(1, Grid.cells("\u007E"))
        assertEquals(2, Grid.cells("\u4E00"))
        // .NET: U+0000 → 1 字节（0x00 直传）⇒ 1 格
        assertEquals(1, Grid.cells("\u0000"))
        // .NET: U+007F → 1 字节（0x7F 直传）
        assertEquals(1, Grid.cells("\u007F"))
        // .NET: U+00A5 → 1 字节 0x5C（best-fit 到半角 ¥；与 ASCII 反斜杠同格）
        assertEquals(1, Grid.cells("\u00A5"))
        assertEquals(1, Grid.cells("\\"))
        // .NET: U+0080 → 1 字节 0x80（C1 控制符同样在单字节区）
        assertEquals(1, Grid.cells("\u0080"))
        // TAB 的裸码元也是 1 格，整串路径先展开成 8 空格（StringMeasure.cs:57-61）
        assertEquals(1, Grid.cellWidth('\t'))
        assertEquals(8, Grid.cells("\t"))
    }

    /** 空内容返回空表（`PrintStringBuffer.cs:198-201`）；空串 run 仍产出一行（`cells` 0）。 */
    @Test
    fun `零段返回空表且空串段仍成行`() {
        assertEquals(0, LineFolder(clientWidth = 8).fold(RenderLine(emptyList(), true, true)).size)
        val rows = LineFolder(clientWidth = 8).fold(line(text("")))
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].cells)
    }
}
