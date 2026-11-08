package com.eratw.port.render.button

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.view.GraphicGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * B1 定向单测：[ButtonLayer.hitInline] 的纵向门必须随「行压缩」的 k 缩放。
 *
 * ## 缺陷
 *
 * [com.eratw.port.render.view.RowLayout] 会把**纯图行**的行高压到低于执行行高
 * （标题横带那一档），绘制侧随即把图按
 * `k = GraphicGeometry.artRowScale(drawH, rowHeight, lineHeight, rowSpan)` 缩短
 * （`CanvasConsoleView.drawImage` 的 `place[3] * k`）。命中侧此前按段自带的
 * `heightPx` 判纵盒 ⇒ 压缩行上的图按钮，点击盒比图的实际覆盖高出一截 ——
 * 图上方那段空白也吃点击。语料形态 `ERB/BODY_INFO/GET_BODY_SETS.ERB:115`
 * （`<button><img ...>` 的按钮图落在压缩行上）可达。
 *
 * ## 断言口径
 *
 * 同一张图（16px 高）在两种布局下：
 * - k = 1（行高 21 = 执行行高）：纵盒 `[0, 16]`；
 * - k = 6/16 = 0.375（行高被压到 6）：纵盒 `[0, 6]`。
 *
 * 于是 y = 3 两边都命中，y = 10 只在压缩前命中。期望的 k 全部由
 * [GraphicGeometry.rowScaleOf]（绘制侧同一入口）单算出来，不拿实现回读。
 */
class ButtonLayerRowScaleHitTest {

    /** 执行行高（`Config.LineHeight` 的落地值；语料口径 21px 一档）。 */
    private val lineHeight = 21f

    /** 生效字号（`Config.FontSize`）。 */
    private val fontSize = 18

    /** 标题横带那一档的图：16px 高、2 格宽。 */
    private fun imageRun(
        heightPx: Int = 16,
        yOffsetPx: Int = 0,
        widthCells: Int = 2,
        value: Any? = 90001L,
        id: Int = 1,
    ): TextRun = TextRun(
        text = "",
        fore = 0xFFFFFF,
        back = 0,
        styleMask = 0,
        fontName = "msgothic",
        kind = RunKind.IMAGE,
        buttonValue = value,
        buttonId = id,
        graphic = InlineGraphic.Image(
            resource = "TW_title001",
            widthPx = 32,
            heightPx = heightPx,
            yOffsetPx = yOffsetPx,
            widthCells = widthCells,
        ),
    )

    private fun row(vararg runs: TextRun): DisplayRow {
        var cells = 0
        for (run in runs) {
            cells += if (run.graphic != null) run.graphic!!.widthCells else Grid.cells(run.text)
        }
        return DisplayRow(runs.toList(), cells, isLogicalLine = true, isLineEnd = true)
    }

    private fun layer(vararg runs: TextRun) = ButtonLayer().also { it.attach(listOf(row(*runs))) }

    /**
     * k 的期望值就是绘制侧的算式：16px 的图压进 6px 的行 ⇒ `6 / 16`。
     * 这一条同时是「两侧同一份算式」的钉子：改 [GraphicGeometry.rowScaleOf] 会同时改到这里。
     */
    @Test
    fun `压缩行的k与绘制侧同一份算式`() {
        val k = GraphicGeometry.rowScaleOf(
            pureArtRow = true,
            drawH = 16f,
            rowHeight = 6f,
            lineHeight = lineHeight,
            rowSpan = 0,
        )
        assertEquals(6f / 16f, k, 1e-6f)
        // 绘制侧的实画高 = heightPx × k，必须正好等于被压的那一行高。
        assertEquals(6f, 16f * k, 1e-4f)
    }

    /** k < 1：图内的点（y ∈ [0, 6]）照旧命中，压缩前的下沿（16）已经不是可点区。 */
    @Test
    fun `压缩行里图内的点命中_压缩前才覆盖的竖带不命中`() {
        val layer = layer(imageRun())
        // 图内：纵盒被压到 [0, 6]
        assertNotNull(
            layer.hitInline(0, 0, 3f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "y=3 ∈ [0, 6]，图上确实有像素",
        )
        assertNotNull(
            layer.hitInline(0, 0, 6f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "下沿闭区间（EmueraConsole.cs:2368 的 <=）仍算命中",
        )
        // 压缩前才覆盖的竖带：y ∈ (6, 16]
        assertNull(
            layer.hitInline(0, 0, 10f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "y=10 只在压缩前的 16px 盒里，行压缩后那里是空白 ⇒ 不得命中",
        )
        assertNull(
            layer.hitInline(0, 0, 16f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "压缩前盒的下沿同样不再是可点区",
        )
    }

    /** k = 1（行高未被压）：纵盒保持 `[0, heightPx]`，行为与加 k 之前逐位相同。 */
    @Test
    fun `未压缩行k为1纵盒不变`() {
        val layer = layer(imageRun())
        assertNotNull(layer.hitInline(0, 0, 10f, fontSize, rowHeightPx = lineHeight, lineHeightPx = lineHeight))
        assertNotNull(
            layer.hitInline(0, 0, 16f, fontSize, rowHeightPx = lineHeight, lineHeightPx = lineHeight),
            "下沿闭区间",
        )
        assertNull(
            layer.hitInline(0, 0, 16.1f, fontSize, rowHeightPx = lineHeight, lineHeightPx = lineHeight),
            "出盒即不命中",
        )
    }

    /**
     * 不给行几何（`rowHeightPx` / `lineHeightPx` 缺省 0）⇒ k 恒 1，也就是**压缩前的旧口径**：
     * 既有调用方（无纵向信息的那批）行为逐位不变，绝不能在没拿到布局时擅自缩盒。
     */
    @Test
    fun `不给行几何时退回压缩前口径`() {
        val layer = layer(imageRun())
        assertNotNull(
            layer.hitInline(0, 0, 10f, fontSize),
            "lineHeight<=0 ⇒ artRowScale 的守卫让它返回 1，命中盒与旧口径一致",
        )
        assertEquals(
            GraphicGeometry.rowScaleOf(true, 16f, 0f, 0f, 0),
            1f,
            "缺行高/行高为 0 时 k 必须是 1（artRowScale 的第一条守卫）",
        )
    }

    /**
     * **上沿不乘 k**：绘制侧的行内起点是 `rowTop + yOffsetPx`（`CanvasConsoleView.drawImage`
     * 的 `top`），`yOffsetPx` 不参与缩放。所以 `ypos=4` 的图在压缩行上的纵盒是 `[4, 4 + 6]`，
     * 不是 `[4 × k, 4 × k + 6]`（后者会把 4 以上的空白算成可点、又把真正的图底漏掉）。
     */
    @Test
    fun `纵盒上沿不随k缩放`() {
        val layer = layer(imageRun(yOffsetPx = 4))
        assertNull(layer.hitInline(0, 0, 3.5f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "y=3.5 在图上方空白（上沿仍是 ypos=4）")
        assertNotNull(layer.hitInline(0, 0, 4f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight))
        assertNotNull(layer.hitInline(0, 0, 9.5f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "图底 = 4 + 16 × 0.375 = 10")
        assertNull(layer.hitInline(0, 0, 10.5f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight),
            "越过缩放后的图底不命中（若上沿也乘 k，盒会落在 [1.5, 7.5]，这里就会错命中）")
    }

    /**
     * 只有 [InlineGraphic.Image] 会被绘制侧缩放：`drawRect` 画的色块不走 `artRowScale`，
     * 所以 [GraphicGeometry.nodeRowScale] 对 `Rect` 恒 1 —— 色块在压缩行上照样整块可点。
     */
    @Test
    fun `色块段不随行压缩缩放`() {
        val rectRun = TextRun(
            text = "",
            fore = 0xFFFFFF,
            back = 0,
            styleMask = 0,
            fontName = "msgothic",
            kind = RunKind.SHAPE,
            buttonValue = 7L,
            buttonId = 2,
            graphic = InlineGraphic.Rect(
                xOffsetPx = 0,
                yOffsetPx = 0,
                widthPx = 32,
                heightPx = 16,
                colorArgb = 0x00FF00,
                widthCells = 2,
            ),
        )
        val layer = layer(rectRun)
        assertEquals(1f, GraphicGeometry.nodeRowScale(rectRun.graphic!!, true, 6f, lineHeight))
        // Rect 纵盒 = [min(0,0), max(FontSize, 0+16)] = [0, 18]，k=1 ⇒ y=10 仍命中
        assertNotNull(layer.hitInline(0, 0, 10f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight))
    }

    /** 带 `rowSpan` 的合并竖带块：绘制侧第一条守卫让它恒不缩，命中侧同样恒 1。 */
    @Test
    fun `跨行竖带块不随行压缩缩放`() {
        val bandRun = TextRun(
            text = "",
            fore = 0xFFFFFF,
            back = 0,
            styleMask = 0,
            fontName = "msgothic",
            kind = RunKind.IMAGE,
            buttonValue = 5L,
            buttonId = 3,
            graphic = InlineGraphic.Image(
                resource = "TW_title000",
                widthPx = 32,
                heightPx = 16,
                yOffsetPx = 0,
                widthCells = 2,
                rowSpan = 3,
            ),
        )
        val layer = layer(bandRun)
        assertEquals(
            1f,
            GraphicGeometry.rowScaleOf(true, 16f, 6f, lineHeight, 3),
            "rowSpan > 0 ⇒ artRowScale 第一条守卫返回 1",
        )
        assertNotNull(layer.hitInline(0, 0, 10f, fontSize, rowHeightPx = 6f, lineHeightPx = lineHeight))
    }
}
