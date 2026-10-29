package com.eratw.port.render.button

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.DisplayList
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GFX-02 命中侧：`<button>` 包 `<img>` 的图按钮命中区。
 *
 * 接缝形状按契约 L3 与 L2 约定消费 `TextRun(kind = IMAGE 或 SHAPE, graphic, buttonValue, buttonId)`
 * （两边都不给 `TextRun`/`RunKind` 加字段）。期望值全部来自反编译 C# 的 `文件:行号`，
 * 没有一条拿实现回读：
 * - 一条 `ConsoleButtonString` 的 `StrArray` 同时含文本片与 `ConsoleImagePart`
 *   （`ConsoleButtonString.cs:19`，累加跨度 `:292-303`）⇒ 同按钮的文本段 + 图段并成**一条**命中区；
 *   数组长度口径 `ConsoleDisplayLine.cs:18,28`（一条按钮只占一个下标）。
 * - 图段字符跨度恒 0（`ConsoleImagePart.cs:41` 的 `base.Text = ""`）但像素盒非零
 *   （`:66-82` 写 `base.Width`）⇒ 本层图段宽度读 `graphic.widthCells`，与绘制侧
 *   `displaylist/DisplayList.prepare`（`:496`）同源，保证「画在哪 = 点哪里」。
 * - 非按钮 kind / 手工 run（`buttonId == -1`）带着残值不得变可点 ⇒ 零宽图段与缺编号图段不入表。
 */
class ButtonLayerImageHitTest {

    private val img = InlineGraphic.Image(
        resource = "body.png",
        widthPx = 72,
        heightPx = 18,
        yOffsetPx = 0,
        widthCells = 4,
    )

    /** 文本按钮段（`kind = BUTTON`，`buttonId` 同源）。 */
    private fun button(text: String, value: Any?, id: Int) =
        TextRun(text, 0xFFFFFF, 0x000000, 0, "msgothic", RunKind.BUTTON, value, buttonId = id)

    /**
     * 图按钮段（接缝形态）。[text] 恒为空串 —— 复刻原版 `ConsoleImagePart.cs:41` 的 `base.Text = ""`
     * （字符跨度 0）；宽度全靠 [graphic] 的 `widthCells`（像素盒折算）。生产侧 [HtmlRunFlatten]
     * 可能填等量空格占宽，但本层只读 `graphic.widthCells`，两种文本都对geometry无影响。
     */
    private fun graphicRun(
        value: Any?,
        id: Int,
        graphic: InlineGraphic,
        kind: RunKind,
    ) = TextRun("", 0xFFFFFF, 0x000000, 0, "msgothic", kind, value, buttonId = id, graphic = graphic)

    private fun imageRun(value: Any?, id: Int, widthCells: Int) =
        graphicRun(value, id, imgOf(widthCells), RunKind.IMAGE)

    private fun imgOf(widthCells: Int): InlineGraphic = img.copy(widthCells = widthCells)

    /** 行的 `cells` 按绘制同源的口径累加（图段读 widthCells、文本段读字符格数），仅供快照元数据。 */
    private fun row(vararg runs: TextRun): DisplayRow {
        var cells = 0
        for (run in runs) {
            cells += if (run.graphic != null) run.graphic!!.widthCells else Grid.cells(run.text)
        }
        return DisplayRow(runs.toList(), cells, true, true)
    }

    /** 图按钮所在列命中：提交值 = 该按钮 `buttonValue`（零字符跨度但像素盒 4 格可点）。 */
    @Test
    fun `img 按钮所在列命中提交该按钮的 buttonValue`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(imageRun(100L, id = 0, widthCells = 4))))
        assertEquals(listOf(HitTarget(0, 0, 4, 100L)), layer.all())
        assertEquals(100L, layer.hit(0, 0)?.value)
        assertEquals(100L, layer.hit(0, 3)?.value)
        assertNull(layer.hit(0, 4), "半开区间，第 4 列已出图按钮右沿")
        assertEquals("100", layer.click(0, 2, ButtonAcceptance.INT))
        assertEquals("100", layer.click(0, 2, ButtonAcceptance.STR))
    }

    /**
     * 同一条源按钮的文本段 + 图段并成**一条**命中区：
     * 原版 `ConsoleButtonString.cs:19,292-303` 把两类节点的 Width 累加进同一条跨度，
     * `ConsoleDisplayLine.cs:18,28` 一条按钮只占一个下标。
     */
    @Test
    fun `同按钮的文本段与图段并成一条命中区`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(
                    button("AB", 100L, id = 0),
                    imageRun(100L, id = 0, widthCells = 4),
                )
            )
        )
        assertEquals(1, layer.all().size, "同一 buttonId 的文本段 + 图段只该有一条命中区")
        assertEquals(HitTarget(0, 0, 6, 100L), layer.all().single())
        assertEquals(100L, layer.hit(0, 1)?.value)
        assertEquals(100L, layer.hit(0, 5)?.value)
        assertNull(layer.hit(0, 6))
        assertEquals("100", layer.click(0, 4))
    }

    /** 图段在前、文本段在后同样并成一条（合并与段序无关，`ConsoleButtonString.cs:292-303`）。 */
    @Test
    fun `图段在前文本段在后仍并成一条`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(
                    imageRun(9L, id = 3, widthCells = 2),
                    button("CD", 9L, id = 3),
                )
            )
        )
        assertEquals(listOf(HitTarget(0, 0, 4, 9L)), layer.all())
        assertEquals(9L, layer.hit(0, 0)?.value)
        assertEquals(9L, layer.hit(0, 3)?.value)
    }

    /**
     * 两条**不同**源按钮（`buttonId` 不同）即使值相同、列号相接也不并
     * （`PrintStringBuffer.cs:103-123` 两次 `AppendButton` 是两条 `ConsoleButtonString`）。
     */
    @Test
    fun `图段并入只认同源按钮 不同编号各成一条`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                row(
                    button("AB", 7L, id = 0),
                    imageRun(7L, id = 1, widthCells = 4),
                )
            )
        )
        assertEquals(listOf(HitTarget(0, 0, 2, 7L), HitTarget(0, 2, 6, 7L)), layer.all())
    }

    /** 零宽图段（`graphic.widthCells == 0`）不入表：半开区间为空、永远命中不了。 */
    @Test
    fun `零宽图段不入表`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(imageRun(5L, id = 0, widthCells = 0))))
        assertTrue(layer.all().isEmpty(), "零宽图段不进命中表")
        assertNull(layer.hit(0, 0))
    }

    /**
     * `buttonId` 缺失（-1）的图段维持不入表：残值防 —— 手工构造的图形 run 不得因为一个
     * 残留的 `buttonValue` 变可点（这是被替换掉的旧 kind 检查要防的东西换了形态落地）。
     */
    @Test
    fun `buttonId 缺失的图段带残值也不入表`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(imageRun(7L, id = -1, widthCells = 3))))
        assertTrue(layer.all().isEmpty(), "非按钮区间内的图段（编号 -1）不可点")
        assertNull(layer.hit(0, 1))
    }

    /** 字符跨度 0（`text` 空串）但像素盒非零：宽度必须来自 `graphic.widthCells`（`ConsoleImagePart.cs:41,66-82`）。 */
    @Test
    fun `空文本跨度的图段按像素盒格宽入表`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(imageRun(1L, id = 0, widthCells = 3))))
        assertEquals(HitTarget(0, 0, 3, 1L), layer.all().single(), "runWidth 读 widthCells 而非 Grid.cells(空串)=0")
        assertNotNullHit(layer, 0, 0)
        assertNotNullHit(layer, 0, 2)
        assertNull(layer.hit(0, 3))
    }

    /** SHAPE 段的图按钮同样入表（`kind = SHAPE`，色块形态，`ConsoleShapePart` 与图段共用列游标）。 */
    @Test
    fun `色块图段按钮也入表`() {
        val rect = InlineGraphic.Rect(xOffsetPx = 0, yOffsetPx = 0, widthPx = 36, heightPx = 18, colorArgb = 0x00FF00, widthCells = 2)
        val layer = ButtonLayer()
        layer.attach(listOf(row(graphicRun(8L, id = 0, rect, RunKind.SHAPE))))
        assertEquals(listOf(HitTarget(0, 0, 2, 8L)), layer.all())
        assertEquals(8L, layer.hit(0, 1)?.value)
    }

    /**
     * 「画在哪 = 点哪里」：同一行喂给 [ButtonLayer] 与 [DisplayList]，图段的绘制列区间必须
     * 落在合并后的命中区间内，且列号完全对齐（`DisplayList.prepare:496` 对图段读 `graphic.widthCells`，
     * 与 [ButtonLayer] 的 [runWidth] 同一式）。这是 `CanvasConsoleView`（Android 模块、无纯 JVM 测试源集）
     * 命中映射的纯 JVM 等价断言：`tap` 把像素折成列后交给 `ButtonLayer.hit`，绘制读 `DrawCmd.colStart/colEnd`。
     */
    @Test
    fun `图段绘制列与命中列同源对齐`() {
        val rows = listOf(
            row(
                button("AB", 100L, id = 0),
                imageRun(100L, id = 0, widthCells = 4),
            )
        )
        val layer = ButtonLayer()
        layer.attach(rows)
        val hit = layer.all().single()
        assertEquals(HitTarget(0, 0, 6, 100L), hit)

        val displayList = DisplayList(clientWidth = 1800)
        displayList.submit(rows)
        val graphicCmd = displayList.render().single { it.row == 0 && it.graphic != null }
        assertEquals(2, graphicCmd.colStart, "图段绘制起点 = 文本段宽之后（与命名列游标同址）")
        assertEquals(6, graphicCmd.colEnd, "图段绘制右沿 = widthCells 推进后（与命中右沿同址）")
        assertTrue(
            graphicCmd.colStart >= hit.colStart && graphicCmd.colEnd <= hit.colEnd,
            "绘制列区间必须被命中区间覆盖（画在哪 = 点哪里）",
        )
        // 点图段中间的列（4），落在绘制 [2,6) 也落在命中 [0,6) ⇒ 提交同一按钮值。
        assertEquals(100L, layer.hit(0, 4)?.value)
    }

    private fun assertNotNullHit(layer: ButtonLayer, row: Int, col: Int) {
        assertTrue(layer.hit(row, col) != null, "命中应存在：row=$row col=$col")
    }
}
