package com.eratw.port.render.button

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * div 子行按钮的**像素命中表**与焦点/按下（CONTRACT-DIV-HIT2 B 路）。
 *
 * 断言的原版语义（相对 reference/emuera-cs/）：
 * - 命中几何 `ConsoleDivPart.TestChildHitbox`（CDP :134-172）：盒矩形宽用脚本 `width`
 *   （**不是**绘制侧 `width + 2`，CDP :140 对 :178）、矩形 y 是内容盒顶（:140 的
 *   `relPointY + PointY + yOffset`）、子行行条顶 = 内容盒顶 + 子行号 × 行高（:169）、
 *   子行左沿 = 内容盒左沿（:134 的 `ShiftChildrenX(PointX + xOffset + divXOffset)`）；
 * - 命中扫描优先级（`EmueraConsole.cs:2343` 的 `Array.Sort` + :2347-2424）：
 *   depth < 0（升序）→ 正文行（:2350-2396 的 depth 0 槽）→ depth == 0 → depth > 0（升序）；
 * - div 子行按钮不进 `displayLineList` 各行的 Buttons（EmueraConsole.cs:2358-2361），
 *   所以格坐标命中表 [ButtonLayer.all] 不受影响。
 */
class ButtonLayerDivHitTest {

    private val metrics = CellMetrics(18, 18)

    private val planner = DivOverlayPlanner(metrics, 0, HtmlRunFlatten(metrics))

    private val canvasHeightPx = 10 * 18

    private val rowCount = 10

    private fun geometry(hitRowOffset: Int = 0) = ButtonLayer.DivHitGeometry(
        metrics = metrics,
        rowCount = rowCount,
        canvasHeightPx = canvasHeightPx,
        hitRowOffset = hitRowOffset,
    )

    private fun block(
        children: List<String>,
        widthPx: Int = 100,
        heightPx: Int = 30,
        depth: Int = 0,
    ) = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = 0,
        anchorPointXPx = 0,
        xOffsetPx = 0,
        divXOffsetPx = 0,
        pointYPx = 0,
        yOffsetPx = 0,
        widthPx = widthPx,
        heightPx = heightPx,
        subDivisionWidthPx = widthPx,
        depth = depth,
        backgroundColor = null,
        margin = emptyList(),
        padding = emptyList(),
        border = emptyList(),
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = true,
        isEscaped = true,
        children = children.map { DisplayDivChildLine(it) },
    )

    private fun plan(
        block: DisplayDivBlock,
        anchorTopPx: Int,
        anchorPointXPx: Int = 0,
    ): DivOverlayPlanner.Plan = planner.plan(
        blocks = listOf(block),
        anchorTopPx = anchorTopPx,
        anchorPointXPx = anchorPointXPx,
        canvasHeightPx = canvasHeightPx,
        visibleTopLine = 0,
        visibleBottomLine = 100,
        anchorLineNo = 0,
    )

    private fun row(vararg runs: TextRun, indent: Int = 0): DisplayRow {
        val cells = runs.sumOf { run ->
            val graphic = run.graphic
            if (graphic != null) graphic.widthCells else Grid.cells(run.text)
        }
        return DisplayRow(
            runs = runs.toList(),
            cells = cells,
            isLogicalLine = true,
            isLineEnd = true,
            indentCells = indent,
        )
    }

    private fun button(text: String, value: Long) = TextRun(
        text = text,
        fore = 0xC0C0C0,
        back = null,
        styleMask = 0,
        fontName = "黑体",
        kind = RunKind.BUTTON,
        buttonValue = value,
    )

    // ---- ① 逐项几何 ----

    @Test
    fun `divTargets 逐项给出子行按钮的像素几何`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 40,
                anchorPointXPx = 18,
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(0, hit.blockIndex, "behind 空、depth 0 落 front ⇒ Plan.all 下标 0")
        assertEquals(0, hit.childRow)
        assertEquals(0, hit.depth)
        assertEquals(0, hit.colStart, "'OK' 从子行列 0 起")
        assertEquals(2, hit.colEnd)
        assertEquals(7L, hit.value, "数值按钮值（HtmlRunFlatten :498 的 toLongOrNull）")
        assertEquals("OK", hit.label)
        // 内容盒 x = PointX(18) + xOffset(0)（CDP :134/:140），y = 行顶 40 + 0（内容盒顶）。
        assertEquals(18f, hit.xPx)
        assertEquals(40f, hit.yPx)
        assertEquals(18f, hit.widthPx, "2 格 × 9px 半角步进")
        assertEquals(18f, hit.heightPx, "文本节点纵带 = Config.FontSize（AConsoleDisplayNode.cs:29-31）")
    }

    @Test
    fun `divTargets 的盒矩形支持负的块左沿`() {
        // ALIGNMENT 的整行位移可以把 PointX 推成负值（ConsoleDisplayLine.cs:80-83），
        // 命中几何与绘制几何同样允许负 x：块内可见部分可点，画布外（负像素）不命中。
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 40,
                anchorPointXPx = -9,
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(-9f, hit.xPx)
        assertEquals(18f, hit.widthPx)
        assertNull(layer.hitAt(-1, 45), "画布外（负像素）不命中")
        layer.setPointerPx(5, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv(), "负左沿之后可见的那一半仍可点")
    }

    // ---- ② 焦点盒内 / 盒外 ----

    @Test
    fun `focusedDiv 命中盒内 盒外为 null`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 40, anchorPointXPx = 18),
            geometry = geometry(),
        )
        layer.setPointerPx(20, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv())
        assertNull(layer.pressedDiv(), "未按下不产生按下态")
        layer.setPointerPx(300, 45, pressed = true)
        assertNull(layer.focusedDiv(), "块外不命中")
        assertNull(layer.pressedDiv(), "按下但没有命中目标")
    }

    @Test
    fun `按下态在命中时成立 抬起即灭`() {
        val layer = ButtonLayer()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 40, anchorPointXPx = 0),
            geometry = geometry(),
        )
        layer.setPointerPx(5, 45, pressed = true)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.pressedDiv())
        layer.setPointerPx(5, 45, pressed = false)
        assertNull(layer.pressedDiv())
    }

    // ---- ③ 与格坐标表的坐标系一致性 ----

    @Test
    fun `divTargets 的像素列与格坐标表的列同尺`() {
        val layer = ButtonLayer()
        // 正文行：缩进 2 格后是按钮 [2,4)。
        layer.attach(listOf(row(button("OK", 7L), indent = 2)))
        val target = layer.hit(0, 2)
        assertEquals(2, target?.colStart)
        // div 块的锚列与正文行缩进同宽：内容盒 x = cellsToPixels(2) = 18。
        layer.attachDiv(
            plan = plan(
                block(listOf("<button value='7'>OK</button>")),
                anchorTopPx = 0,
                anchorPointXPx = metrics.cellsToPixels(2),
            ),
            geometry = geometry(),
        )
        val hit = layer.divTargets().single()
        assertEquals(0, hit.colStart, "子行内的列号从 0 起（对齐偏移不折进列号）")
        assertEquals(
            metrics.cellsToPixels(target?.colStart ?: -1),
            hit.xPx.toInt(),
            "div 命中 x = 内容盒左沿 + cellsToPixels(列)，与绘制/正文行同一把尺",
        )
        assertEquals(
            metrics.cellsToPixels(2) + metrics.cellsToPixels(hit.colStart),
            hit.xPx.toInt(),
        )
    }

    // ---- ④ 优先级：正文行盖 div ----

    @Test
    fun `正文行按钮盖住 depth 0 的 div`() {
        val layer = ButtonLayer()
        // 第 2 行行条顶 = 180 - (10 - 2) * 18 = 36；div 内容盒顶也放在 36。
        layer.attach(listOf(row(), row(), row(button("OK", 7L))))
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 36),
            geometry = geometry(),
        )
        assertTrue(layer.divTargets().isNotEmpty())
        assertNull(layer.hitAt(5, 45), "正文行按钮按 depth 0 槽先扫（EmueraConsole.cs:2350-2396）⇒ div 输")
        assertEquals(7L, layer.hit(2, 0)?.value, "正文行照旧能命中")
    }

    @Test
    fun `负 depth 的 div 盖住正文行`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(), row(), row(button("OK", 7L))))
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>"), depth = -1), anchorTopPx = 36),
            geometry = geometry(),
        )
        val hit = layer.hitAt(5, 45)
        assertEquals(-1, hit?.depth, "负 depth 先于正文行扫（EmueraConsole.cs:2343 的键升序）")
        layer.setPointerPx(5, 45, pressed = false)
        assertEquals(ButtonLayer.DivFocus(0, 0, 0, 2), layer.focusedDiv())
    }

    // ---- ⑤ 格坐标表行为不变 ----

    @Test
    fun `attachDiv 不改格坐标命中表`() {
        val layer = ButtonLayer()
        layer.attach(listOf(row(button("OK", 7L)), row(button("EXIT", 0L), indent = 2)))
        val before = layer.all()
        layer.attachDiv(
            plan = plan(block(listOf("<button value='7'>OK</button>")), anchorTopPx = 0),
            geometry = geometry(),
        )
        assertEquals(before, layer.all(), "div 子行按钮不进 displayLineList 的命中扫描（EmueraConsole.cs:2358-2361）")
        assertTrue(layer.divTargets().isNotEmpty(), "div 命中项在另一张表")
    }
}
