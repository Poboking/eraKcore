package com.eratw.port.render.adapter

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * div 覆盖块的覆盖绘制规划（CONTRACT-HTMLDIV-B1 D3）。
 *
 * 断言的原版语义（相对 reference/emuera-cs/）：
 * - 矩形两形态与 width+2 fudge：`ConsoleDivPart.DrawTo`（CDP :174-178）；
 * - margin/border/padding 三段收缩：CDP :179-195；
 * - 子行逐行落点（内容盒顶 + 行序 × 行高）：CDP :196-203；
 * - 未逃逸块无绘制面：`ConsoleButtonString.DrawTo` :361-364 + `FilterEscaped` :165-185
 *   + 登记 EmueraConsole.cs:2916-2925；
 * - depth 降序与 depth 0 正文槽：EmueraConsole.cs:1829-1857；
 * - relative 块按登记行区间过滤、absolute 块恒画：ConsoleEscapedParts.cs:77/:127-129。
 */
class DivOverlayTest {

    private val metrics = CellMetrics() // 字号 18、行高 19（ConfigData.cs:66-67）

    private fun planner(scriptFont: Int = 0) = DivOverlayPlanner(metrics, scriptFont, HtmlRunFlatten(metrics))

    private fun rect(x: Int, y: Int, w: Int, h: Int) = DivOverlayPlanner.Rect(x, y, w, h)

    private fun block(
        widthPx: Int,
        heightPx: Int,
        pointY: Int = 0,
        xOffset: Int = 0,
        depth: Int = 0,
        relative: Boolean = true,
        escaped: Boolean = true,
        margin: List<Int> = emptyList(),
        padding: List<Int> = emptyList(),
        border: List<Int> = emptyList(),
        children: List<String> = emptyList(),
        bg: Int? = null,
        subDiv: Int = 0,
    ) = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = 0,
        anchorPointXPx = 0,
        xOffsetPx = xOffset,
        divXOffsetPx = 0,
        pointYPx = pointY,
        yOffsetPx = 0,
        widthPx = widthPx,
        heightPx = heightPx,
        subDivisionWidthPx = subDiv,
        depth = depth,
        backgroundColor = bg,
        margin = margin,
        padding = padding,
        border = border,
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = relative,
        isEscaped = escaped,
        children = children.map { DisplayDivChildLine(it) },
        sourceRange = IntRange.EMPTY,
    )

    @Test
    fun `relative 矩形锚在行条上加 ypos 宽加 2`() {
        // CDP :178 relative 支：rect = (PointX + xOffset, 行条顶 + PointY, width + 2, Height)
        val plan = planner().plan(
            blocks = listOf(block(widthPx = 100, heightPx = 50, pointY = 19, xOffset = 9)),
            anchorTopPx = 380,
            anchorPointXPx = 90,
            canvasHeightPx = 760,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 20,
        )
        val r = plan.all.single().borderRectPx
        assertEquals(99, r.x, "锚列 90 + xOffset 9（PointX + xOffset）")
        assertEquals(399, r.y, "行条顶 380 + ypos 19")
        assertEquals(102, r.width, "width + 2 的官方 fudge")
        assertEquals(50, r.height)
    }

    @Test
    fun `absolute 矩形锚画布底且 X 不含锚列`() {
        // CDP :178 absolute 支：rect = (xOffset, 画布高 − PointY − Height, width + 2, Height)
        val plan = planner().plan(
            blocks = listOf(block(widthPx = 100, heightPx = 50, pointY = 40, xOffset = 9, relative = false)),
            anchorTopPx = 0,
            anchorPointXPx = 999,
            canvasHeightPx = 760,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 0,
        )
        val r = plan.all.single().borderRectPx
        assertEquals(9, r.x, "absolute 不加锚列（锚列 999 不进 X）")
        assertEquals(760 - 40 - 50, r.y, "Y = MainPicBox.Height − PointY − Height")
        assertEquals(102, r.width)
    }

    @Test
    fun `margin 收缩画边框 padding 再收缩出内容盒 子行逐行落点`() {
        // CDP :179-203：margin → DrawBorder → border → padding → 内容盒 → 子行
        val plan = planner().plan(
            blocks = listOf(
                block(
                    widthPx = 100,
                    heightPx = 60,
                    margin = listOf(1, 2, 3, 4),
                    border = listOf(2, 2, 2, 2),
                    padding = listOf(5, 6, 5, 6),
                    children = listOf("L1", "L2", "L3"),
                    subDiv = 90,
                ),
            ),
            anchorTopPx = 100,
            anchorPointXPx = 0,
            canvasHeightPx = 800,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 0,
        )
        val b = plan.all.single()
        // margin 收缩：x+4、y+1、w−6、h−4
        assertEquals(rect(4, 101, 96, 56), b.borderRectPx, "margin[3]/[0]/[1]/[2] 各向收缩（CDP :181）")
        // border 再收缩：x+2、y+2、w−4、h−4；padding 再收缩：x+6、y+5、w−12、h−10
        assertEquals(rect(12, 108, 80, 42), b.contentBoxPx, "border（CDP :190）+ padding（:194）逐段收缩")
        val rows = b.children
        assertEquals(3, rows.size)
        assertEquals(12, rows[0].xPx, "子行 X = 内容盒左沿")
        assertEquals(108, rows[0].yPx, "子行 Y 从内容盒顶起（CDP :197 的 pointY = rect.Y）")
        assertEquals(108 + metrics.lineHeight, rows[1].yPx, "逐行 +LineHeight（CDP :202）")
        assertEquals(108 + metrics.lineHeight * 2, rows[2].yPx)
        assertEquals(90, rows[0].subDivisionWidthPx, "子层折行/对齐宽透传（HM :698/:702-704）")
    }

    @Test
    fun `未逃逸的块不进规划`() {
        val plan = planner().plan(
            blocks = listOf(block(widthPx = 10, heightPx = 10, escaped = false)),
            anchorTopPx = 0,
            anchorPointXPx = 0,
            canvasHeightPx = 800,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 0,
        )
        assertTrue(plan.all.isEmpty(), "ConsoleButtonString.cs:361-364 跳过 div + 只有逃逸块登记")
    }

    @Test
    fun `relative 块按登记行区间过滤 absolute 块恒画`() {
        // 登记：top = ceil(Top/LH)+lineNo、bottom = floor(max(0,Bottom−1)/LH)+lineNo
        //（EmueraConsole.cs:2923）；相交判据 ConsoleEscapedParts.cs:127-129。
        // 块 Top=0、Bottom=38 → 登记 [lineNo, lineNo+1]；可见区间 [10, 12] 不相交。
        val rel = block(widthPx = 10, heightPx = 38, pointY = 0, relative = true)
        val abs = block(widthPx = 10, heightPx = 38, pointY = 0, relative = false)
        val plan = planner().plan(
            blocks = listOf(rel, abs),
            anchorTopPx = 0,
            anchorPointXPx = 0,
            canvasHeightPx = 800,
            visibleTopLine = 10,
            visibleBottomLine = 12,
            anchorLineNo = 0,
        )
        assertEquals(1, plan.all.size, "relative 不相交被过滤、absolute 恒画")
        assertEquals(false, plan.all.single().isRelative)
    }

    @Test
    fun `depth 降序分两侧 正文槽夹在中间`() {
        // EmueraConsole.cs:1830 的 depth 降序 + :1850 的 depth 0 正文槽
        val plan = planner().plan(
            blocks = listOf(
                block(widthPx = 10, heightPx = 38, depth = 1),
                block(widthPx = 10, heightPx = 38, depth = 3),
                block(widthPx = 10, heightPx = 38, depth = -2),
                block(widthPx = 10, heightPx = 38, depth = -1),
            ),
            anchorTopPx = 0,
            anchorPointXPx = 0,
            canvasHeightPx = 800,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 0,
        )
        assertEquals(listOf(3, 1), plan.behindText.map { it.depth }, "depth>0 降序，画在正文底")
        assertEquals(listOf(-1, -2), plan.inFrontOfText.map { it.depth }, "depth<0 降序，画在正文前")
    }

    @Test
    fun `背景色补 alpha 透明底不画`() {
        // CDP :70 的 `| 0xFF000000`；color<0（没写）→ Transparent → DrawBorder :76 跳过填充
        val plan = planner().plan(
            blocks = listOf(
                block(widthPx = 10, heightPx = 38, bg = 0x363636),
                block(widthPx = 10, heightPx = 38, bg = null),
            ),
            anchorTopPx = 0,
            anchorPointXPx = 0,
            canvasHeightPx = 800,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 0,
        )
        assertEquals(0xFF363636.toInt(), plan.all[0].backgroundArgb)
        assertNull(plan.all[1].backgroundArgb, "Transparent 块不画底（DrawBorder :76 的判据）")
    }

    @Test
    fun `脚本像素按字号折算`() {
        // scriptPixels：设备px = 脚本px × 真机字号 / 基准字号（CellMetrics.scriptPixels）。
        // 本类 metrics 是缺省 18 号；基准字号 9 ⇒ 全部几何翻倍。
        val plan = planner(scriptFont = 9).plan(
            blocks = listOf(block(widthPx = 100, heightPx = 50, pointY = 19, xOffset = 9)),
            anchorTopPx = 380,
            anchorPointXPx = 90,
            canvasHeightPx = 1520,
            visibleTopLine = 0,
            visibleBottomLine = 40,
            anchorLineNo = 20,
        )
        val r = plan.all.single().borderRectPx
        assertEquals(198, r.x, "锚列与 xOffset 同折：90×2 + 9×2")
        assertEquals(418, r.y, "行条顶不折算（设备域），ypos 19×2=38")
        assertEquals(202, r.width, "width×2 后再加 2 的 fudge")
        assertEquals(100, r.height)
    }

    @Test
    fun `神签 24 号档诗文超宽折两行 后续子行顺延`() {
        // 语料实值（ERB/DLC/神签/神签.ERB:80）：width 480px、border 5px、padding 20px
        // ⇒ SubDivisionWidth = 480 − 2×5 − 2×20 = 430（HM :619-635）；24 号档恒等折算
        // ⇒ 430 ÷ 12.48 ≈ 35 格，22 全角字的诗文（44 格）必须折成 17 + 5 两行。
        val m24 = CellMetrics(24, 28)
        val poem = "切莫遗忘，病魔恒久；蛛网暗藏，八眸玉珠迟暮。"
        val plan = DivOverlayPlanner(m24, 0, HtmlRunFlatten(m24)).plan(
            blocks = listOf(
                block(
                    widthPx = 480,
                    heightPx = 731,
                    border = listOf(5, 5, 5, 5),
                    padding = listOf(20, 20, 20, 20),
                    subDiv = 430,
                    children = listOf(poem, "运势：凶险异常，堕渊陷途，绝无宽宥"),
                ),
            ),
            anchorTopPx = 100,
            anchorPointXPx = 0,
            canvasHeightPx = 2000,
            visibleTopLine = 0,
            visibleBottomLine = 100,
            anchorLineNo = 0,
        )
        val b = plan.all.single()
        assertEquals(rect(25, 125, 432, 681), b.contentBoxPx, "width+2 fudge 后逐段收缩：480+2−10−40")
        val rows = b.children
        assertEquals(3, rows.size, "诗文折 2 行 + 运势明细 1 行")
        assertEquals(34, rows[0].cmds.last().colEnd, "第一行 17 全角字 = 34 格 ≤ 430px 的 35 格")
        assertEquals(0, rows[1].cmds.first().colStart, "折出的第二行从列 0 起（PSB :244-249）")
        assertEquals(10, rows[1].cmds.last().colEnd, "余下 5 全角字 = 10 格")
        assertEquals(m24.lineHeight, rows[1].yPx - rows[0].yPx, "折行占一个行高（CDP :202）")
        assertEquals(125 + m24.lineHeight * 2, rows[2].yPx, "其后子行整体顺延一行")
        assertEquals(430, rows[0].subDivisionWidthPx, "24 号档 sourceFont=真机字号 ⇒ 折算恒等")
    }
}
