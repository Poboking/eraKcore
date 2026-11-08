package com.eratw.port.render.adapter

import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.ImageSizePx
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<img>` 图元 **miss** 的单出口在渲染展平侧的读数，以及**与 core 同尺**的断言。
 *
 * 判据（反编译 `ConsoleImagePart.cs`）：
 * - `:60-65` 图元取不到 ⇒ `Text = AltText` 后 `return`，整段尺寸计算跳过（显式 width/height
 *   都不生效），节点按**文本**画、按文本量宽（`:104-115`）；
 * - `:44-59` AltText 重建（次序 `src` / `srcb` / `srcm` / `height` / `width` / `ypos`，
 *   `srcm` 空串不写、数值属性原始 num 非 0 才写，`Utils.cs:364-371`；px 带后缀、
 *   百分比 `num * FontSize / 100` 整除，`Utils.cs:87-95`）；
 * - `:66-71` 命中且缺宽 ⇒ `DestBaseSize.Width * 高 / DestBaseSize.Height`。
 *
 * 三条链同尺的核心断言：同一段标签原文，core 的 [CellWidth.visibleHtmlCells]
 * （折行 / HTML 回读的共同入口）与这里展平出的段格数必须相等 —— miss 时两边都在量
 * 同一个 AltText，命中时两边都在量同一个派生宽。
 */
class HtmlRunFlattenSizeMissTest {

    private val metrics = CellMetrics(18)

    /** 脚本字号与设备字号同值 ⇒ 百分比与 px 都不折算，期望值可手算。 */
    private val fontPx = 18

    /** 图元查不到（宿主已接线但表里没有）。 */
    private val missSource = HtmlRunFlatten.ImageSizeSource { null }

    /** core 侧的同一份 miss（两边类型不同：一个 `ImageSizeSource`、一个裸函数）。 */
    private val missPx: (String) -> ImageSizePx? = { null }

    private fun sourceOf(w: Int, h: Int): HtmlRunFlatten.ImageSizeSource =
        HtmlRunFlatten.ImageSizeSource { (w shl 16) or h }

    private fun pxOf(w: Int, h: Int): (String) -> ImageSizePx? = { ImageSizePx(w, h) }

    private fun htmlRun(html: String) = TextRun(
        text = html,
        fore = 0xC0C0C0,
        back = 0,
        styleMask = 0,
        fontName = "msgothic",
        kind = RunKind.HTML,
    )

    private fun flatten(html: String, source: HtmlRunFlatten.ImageSizeSource?): List<TextRun> =
        HtmlRunFlatten(metrics, source, scriptFontPixelSize = fontPx).flatten(htmlRun(html))

    // ------------------------------------------------------------------
    // miss：TEXT run + AltText 文案
    // ------------------------------------------------------------------

    @Test
    fun `图元 miss 落 TEXT run 且内容与 core 的 AltText 重建同串`() {
        val tag = "<img width='180px' height='50' srcb='pic_b' src='pic'>"
        val runs = flatten(tag, missSource)
        val run = runs.single()
        assertEquals(RunKind.TEXT, run.kind, "miss 不得出 IMAGE run（原版 Text = AltText，按文本画）")
        assertNull(run.graphic, "miss 段没有图形负载")
        assertEquals(
            "<img src='pic' srcb='pic_b' height='9' width='180px'>",
            run.text,
            "重建次序 src→srcb→height→width，百分比 50 → 9（ConsoleImagePart.cs:52-57 + Utils.cs:87-95）",
        )
        assertEquals(
            CellWidth.visibleHtmlCells(tag, fontPx.toLong(), missPx),
            Grid.cells(run.text),
            "同一标签：core CellWidth 的格数 == render 展平段的文本格数（三条链同尺）",
        )
        assertEquals(53, Grid.cells(run.text), "重建文案 53 半角格")
    }

    @Test
    fun `miss 夹在文本中间时按原文顺序落三段且显式宽不生效`() {
        val runs = flatten("甲<img src='gone' width='20px'>乙", missSource)
        assertEquals(
            listOf("甲", "<img src='gone' width='20px'>", "乙"),
            runs.map { it.text },
            "miss 排在原位置、前后文本不吞",
        )
        // AltText 段本身是 TEXT（原版 Text = AltText 的文本节点）；行尾那段保外层 kind
        // —— 本行一枚图形段都没切出来（[HtmlRunFlatten.flush] 的既有规则：
        // 只有切出过图形段的行才把余下文字降级成 TEXT）。两者都走字形路径，绘制无差别。
        assertEquals(listOf(RunKind.TEXT, RunKind.TEXT, RunKind.HTML), runs.map { it.kind })
        assertTrue(runs.all { it.graphic == null }, "三段全是文本段，显式 width='20px' 一个图形段都没建")
    }

    @Test
    fun `基图高为零按 miss 处理`() {
        val tag = "<img src='gone' width='20px'>"
        val run = flatten(tag, sourceOf(100, 0)).single()
        assertEquals(RunKind.TEXT, run.kind, "heightPx <= 0 的退化登记 ⇒ 原版 :69 的除零点，端口按 miss 落")
        assertEquals("<img src='gone' width='20px'>", run.text)
    }

    // ------------------------------------------------------------------
    // 命中：IMAGE run + 派生宽
    // ------------------------------------------------------------------

    @Test
    fun `图元命中且缺宽时出 IMAGE run 并按基图纵横比派生宽`() {
        val tag = "<img src='pic' height='30px'>"
        val run = flatten(tag, sourceOf(100, 50)).single()
        assertEquals(RunKind.IMAGE, run.kind)
        val g = run.graphic as InlineGraphic.Image
        assertEquals(60, g.widthPx, "100 * 30 / 50（ConsoleImagePart.cs:69）")
        assertEquals(30, g.heightPx)
        assertEquals(7, g.widthCells, "ceil(60 / 9.36) = 7")
        assertEquals(
            CellWidth.visibleHtmlCells(tag, fontPx.toLong(), pxOf(100, 50)),
            g.widthCells,
            "命中支：core 折行格数 == render 图形段格数",
        )
    }

    @Test
    fun `图元命中时显式宽优先于纵横比`() {
        val tag = "<img src='pic' width='180px' height='30px'>"
        val g = flatten(tag, sourceOf(100, 50)).single().graphic as InlineGraphic.Image
        assertEquals(180, g.widthPx, "显式宽走 ConsoleImagePart.cs:72-75")
        assertEquals(20, g.widthCells)
    }

    // ------------------------------------------------------------------
    // 未接线：判不了图元在不在 ⇒ 不判 miss
    // ------------------------------------------------------------------

    @Test
    fun `未接线时不判 miss_保持见方占位`() {
        val g = flatten("<img src='pic'>", null).single().graphic as InlineGraphic.Image
        assertEquals(18, g.widthPx, "见方占位（登记偏差，由 GraphicsSinkBridgeImgDefaultTest 钉住语句路）")
        assertEquals(18, g.heightPx, "缺高 = 一行高")
    }
}
