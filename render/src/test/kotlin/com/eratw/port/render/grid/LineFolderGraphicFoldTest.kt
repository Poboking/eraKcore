package com.eratw.port.render.grid

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `LineFolder` 对**内联图形段**（M10-GFX 的 [InlineGraphic] 三形态，经
 * `adapter/HtmlRunFlatten` 展平成带 `graphic` 负载的 run）的折行判据（M10-fold 波）。
 *
 * 原版口径三条，逐条带行号：
 * - 图形段不可拆：`ConsoleImagePart.cs:35`、`ConsoleShapePart.cs:8-10` 的 `CanDivide => false`
 *   （文本件 `ConsoleStyledString.cs:15` 为 true）；
 * - 字符域记 0、宽度是节点自带像素宽：`ConsoleImagePart.cs:41` 构造即 `Text = ""`，
 *   `:67-80` 直写 `Width`，`SetWidth`（`:104-115`）只在 sprite 查不到时才量 AltText ——
 *   于是 `getDivideIndex` 的字符累加（`PrintStringBuffer.cs:438`）与 `DivideAt` 的游标
 *   （`ConsoleButtonString.cs:205,227`）都绕得开图形段；
 * - 行首放不下且不可拆 ⇒ 跳过该部件、**不计其宽度**、继续看下一个
 *   （`PrintStringBuffer.cs:425-441` 内层 if 无 else，ADR-005 照抄不修）；
 *   段宽超过可绘制宽时整段原样入行、行溢出（`PrintStringBuffer.cs:236-240`，
 *   与超宽文本段同一条分支）。
 *
 * 图形段的 `text` 是 `HtmlRunFlatten.emitGraphic` 垫的等量半角空格（占宽口径），
 * 所以这里的期望值既钉「graphic 负载出现次数」，也钉「空格段照常推进列游标」。
 */
class LineFolderGraphicFoldTest {

    private fun text(s: String) = TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun numButton(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    /** 与 `HtmlRunFlatten.emitGraphic` 同构：文本负载 = widthCells 个半角空格。 */
    private fun graphicRun(g: InlineGraphic): TextRun = TextRun(
        text = " ".repeat(g.widthCells),
        fore = 0xFFFFFF,
        back = 0x000000,
        styleMask = 0,
        fontName = "msgothic",
        kind = if (g is InlineGraphic.Image) RunKind.IMAGE else RunKind.SHAPE,
        graphic = g,
    )

    private fun rect(cells: Int) = InlineGraphic.Rect(
        xOffsetPx = 0, yOffsetPx = 0, widthPx = cells * 10, heightPx = 2,
        colorArgb = 0xFF8800, widthCells = cells,
    )

    private fun line(vararg runs: TextRun) =
        RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true)

    private fun texts(row: DisplayRow): List<String> = row.runs.map { it.text }

    private fun graphicCount(rows: List<DisplayRow>): Int = rows.sumOf { row -> row.runs.count { it.graphic != null } }

    // ------------------------------------------------------------------
    // 判据 ①：宽图形段整段下移、不重复画
    // ------------------------------------------------------------------

    @Test
    fun `文本后的宽图形段整段挪到次行且只画一次`() {
        // 手算（宽 20）：段 = [文本 10 格, rect 48 格]。divideIndex：文本放得下（chars=10，
        // `PrintStringBuffer.cs:436-440`），rect 放不下且 fitted != 0 ⇒ 候选（`:427-433`）；
        // 候选不可拆 ⇒ 部件级 getDivideIndex 返回 -1（`:455-458`），前缀贡献 0 ⇒ div=10。
        // DivideAt(10) 在文本与图形边界落刀（ConsoleButtonString.cs:220-224），右半 = [rect]
        // 整体挪到新行行首，再按 :236-240 溢出入行。缺陷形态（可拆时）是 rect 被劈成
        // 20/20/8 三段、每段各带一份负载画三次 —— 本用例钉的就是「只出现一次」。
        val rows = LineFolder(clientWidth = 20).fold(line(text("0123456789"), graphicRun(rect(48))))
        assertEquals(2, rows.size)
        assertEquals(1, graphicCount(rows), "图形负载不得随拆段复制")
        assertEquals(listOf("0123456789"), texts(rows[0]))
        assertEquals(10, rows[0].cells)
        assertEquals(1, rows[1].runs.size)
        assertNotNull(rows[1].runs[0].graphic)
        assertEquals(48, rows[1].cells, "整段溢出：cells 允许大于 clientWidth（PrintStringBuffer.cs:236-240）")
        assertEquals(" ".repeat(48), texts(rows[1]).single())
        // 首行标志只在第一行（PrintStringBuffer.cs:206,212,243）
        assertEquals(true, rows[0].isLogicalLine)
        assertEquals(false, rows[1].isLogicalLine)
    }

    @Test
    fun `立绘（Image 段）与矩形同口径整段不拆`() {
        // Image 与 Rect 走同一条 CanDivide=false（ConsoleImagePart.cs:35），
        // 30 格立绘在宽 20 屏上同样整段挪次行、只画一次。
        val img = InlineGraphic.Image(resource = "bodyGraph_1", widthPx = 300, heightPx = 500, yOffsetPx = 0, widthCells = 30)
        val rows = LineFolder(clientWidth = 20).fold(line(text("abcdefghij"), graphicRun(img)))
        assertEquals(2, rows.size)
        assertEquals(1, graphicCount(rows))
        assertEquals("bodyGraph_1", (rows[1].runs.single().graphic as InlineGraphic.Image).resource)
    }

    // ------------------------------------------------------------------
    // 判据 ②：混排时列游标按 widthCells 口径连续
    // ------------------------------------------------------------------

    @Test
    fun `图形段夹在文本中时列游标按占宽连续推进`() {
        // 手算（宽 20）：段 = [文 4, space 6, 文 30]。4+6=10 格放得下（space 计宽不计字符，
        // 对应原版图形节点 Text.Length=0 参与 Width 累加：PrintStringBuffer.cs:438-439），
        // 尾部文本候选 ⇒ 前缀 10 字符 ⇒ 首行 4+6+10 = 20 格整，次行 20 字符恰好占满。
        val rows = LineFolder(clientWidth = 20).fold(
            line(text("abcd"), graphicRun(InlineGraphic.Space(6)), text("E".repeat(30)))
        )
        assertEquals(2, rows.size)
        assertEquals(1, graphicCount(rows))
        assertEquals(20, rows[0].cells)
        assertEquals(listOf("abcd", " ".repeat(6), "E".repeat(10)), texts(rows[0]))
        assertEquals(20, rows[1].cells)
        assertEquals(listOf("E".repeat(20)), texts(rows[1]))
        // 脏矩形状元：图形段前后的 run 在 toRun 中原样继承 graphic（本用例只钉前一段带、后一段不带）
        assertNotNull(rows[0].runs[1].graphic)
        assertNull(rows[0].runs[2].graphic)
    }

    // ------------------------------------------------------------------
    // 判据 ③：段宽超过整行的处置（原版 :236-240 的溢出入行）
    // ------------------------------------------------------------------

    @Test
    fun `超宽图形段独占溢出单行`() {
        // 纯图形段 48 格 > 20：divideIndex 恒 0（部件被跳过且不计宽，:425-441），
        // 当前行为空 ⇒ 整段原样入行、cells 超出 clientWidth（:236-240），下游按列裁剪。
        val rows = LineFolder(clientWidth = 20).fold(line(graphicRun(rect(48))))
        assertEquals(1, rows.size)
        assertEquals(1, graphicCount(rows))
        assertEquals(48, rows[0].cells)
    }

    @Test
    fun `超宽图形段后接按钮各归一行`() {
        // 段 1 = [rect 48]（独立段因下一段是按钮，group 按 ConsoleButtonString 断段）：
        // div=0 且行空 ⇒ 溢出入行；段 2 = 按钮 "AB"：pointX 48 顶到界外，
        // divideIndex 部件级前缀 0（divWidth - PointX 为负，:468-496 的二分退 0）⇒ 收行、
        // 重排（:242-249）后按钮整段进次行。
        val rows = LineFolder(clientWidth = 20).fold(line(graphicRun(rect(48)), numButton("AB", 7L)))
        assertEquals(2, rows.size)
        assertEquals(1, graphicCount(rows))
        assertEquals(48, rows[0].cells)
        assertEquals(listOf("AB"), texts(rows[1]))
    }

    @Test
    fun `段尾图形段吃满字符域时整段溢出且不造空行`() {
        // 手算（宽 20）：段 = [rect 48, 文 5]。rect 被跳过（不计宽），5 格文本放得下 ⇒
        // div = 5 = 段内字符总数 ⇒ DivideAt 右半为空 ⇒ 原版插 null（:232）多收一条空行
        // （:209-215）；本实现按类 KDoc 的登记口径不造空行：溢出段照常单行收掉。
        val rows = LineFolder(clientWidth = 20).fold(line(graphicRun(rect(48)), text("hello")))
        assertEquals(1, rows.size)
        assertEquals(1, graphicCount(rows))
        assertEquals(listOf(" ".repeat(48), "hello"), texts(rows[0]))
        assertEquals(53, rows[0].cells)
    }

    @Test
    fun `行首宽图形被跳过后文本候选按含图形的PointX求前缀`() {
        // 反例（宽 20）：段 = [rect 48, 文本 30 格]。
        // 外层 `num` 在图形放不下且不可拆时原样跳过（不计宽，PrintStringBuffer.cs:425-441），
        // 于是文本的**拟合判定**是 0 + 30 > 20 ⇒ 成为候选；但部件级前缀余量读的是节点
        // 自身 `PointX`（PrintStringBuffer.cs:468），那个值含被跳过的 48 格 ⇒ 20 - 48 = -28
        // ⇒ 前缀 0 ⇒ 原版走 :236-240 整段溢出入一行：48 + 30 = 78 格。
        // 缺陷形态是拿外层的 num 求余量（20 - 0）⇒ 前缀 20 ⇒ 多劈一行 68 / 10。
        val rows = LineFolder(clientWidth = 20).fold(line(graphicRun(rect(48)), text("0123456789".repeat(3))))
        assertEquals(1, rows.size, "前缀 0 ⇒ 整段原样入行，不再多出一行")
        assertEquals(1, graphicCount(rows))
        assertEquals(78, rows[0].cells, "48 格图形 + 30 格文本")
        assertEquals(listOf(" ".repeat(48), "0123456789".repeat(3)), texts(rows[0]))
        assertEquals(true, rows[0].isLogicalLine)
    }

    // ------------------------------------------------------------------
    // 判据 ④：反向防误接 —— 纯文本折行读数与改动前逐字相同
    // ------------------------------------------------------------------

    @Test
    fun `纯文本多段折行读数不变`() {
        // 特征化钉桩：宽 10，段 = [一二三(6), abcd(4), ＥＦ(4)]（两字符都是全角）。
        // 一二三 + abcd 恰好 10 格（判定式 :217 与 :427 的 <= 语义），ＥＦ 候选前缀 0 ⇒
        // 边界切在字符 7；与 `LineFolderTest` 既有用例同一条链，纯文本件 charSpan ==
        // text.length，canDivide == true，本波改动对其**零影响**。
        val rows = LineFolder(clientWidth = 10).fold(line(text("一二三"), text("abcd"), text("ＥＦ")))
        assertEquals(2, rows.size)
        assertEquals(0, graphicCount(rows))
        assertEquals(listOf("一二三", "abcd"), texts(rows[0]))
        assertEquals(10, rows[0].cells)
        assertEquals(listOf("ＥＦ"), texts(rows[1]))
        assertEquals(4, rows[1].cells)
    }

    @Test
    fun `纯文本按钮跨行拆分读数不变`() {
        // 特征化钉桩：数值按钮跨行就地拆（ButtonWrap 默认 false，ConfigData.cs:83），
        // 拆出的两半各继承 buttonValue（ConsoleButtonString.cs:247-251）—— 与
        // `LineFolderTest` 的「数值按钮跨行拆分后每行各成一段且值相同」同式：宽 6、
        // 文本 2 + 按钮 8 ⇒ 首行 2+4、次行按钮残段 4。
        val rows = LineFolder(clientWidth = 6).fold(line(text("ab"), numButton("01234567", 42L)))
        assertEquals(2, rows.size)
        assertEquals(0, graphicCount(rows))
        assertEquals(listOf("ab", "0123"), texts(rows[0]))
        assertEquals(listOf("4567"), texts(rows[1]))
        // 首行是「文本段 + 按钮左半」两条 run：文本件 buttonValue 为 null，按钮左半继承 42。
        assertEquals(listOf(null, 42L), rows[0].runs.map { it.buttonValue })
        assertEquals(42L, rows[1].runs.single().buttonValue)
    }
}
