package com.eratw.port.render.adapter

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.glyph.CellMetrics

/**
 * div 覆盖块的**覆盖绘制规划器**（CONTRACT-HTMLDIV-B1 D3）—— 原版逃逸块覆盖绘制的
 * 纯 JVM 直译，两头渲染器（`ScreenComposer` 的 headless 栅格与 app 侧 `CanvasConsoleView`）
 * 共用这一份规划，不许各画各的。
 *
 * ## 原版绘制管线（逐条带行号，相对 reference/emuera-cs/）
 *
 * 1. **登记**：行入列时 `FilterEscaped`（ConsoleButtonString.cs:165-185）挑出
 *    `Top < 0 || Bottom > LineHeight` 的节点置 `IsEscaped`，EmueraConsole.cs:2916-2925
 *    把它们（连同行号/depth/行单位的上下沿）登记进 `ConsoleEscapedParts`；
 *    `ConsoleButtonString.DrawTo` 对 div 节点**整体跳过**（:361-364）—— 块不在正文行里画。
 * 2. **取景**：每帧 `GetPartsInRange`（ConsoleEscapedParts.cs:114-145）：absolute 块
 *    无条件进（它们锚在画布上、与滚动无关），relative 块按登记的行区间与可见行区间
 *    相交判（ConsoleEscapedParts.cs:127-129）。
 * 3. **层序**：EscapedParts 按 **depth 降序**与背景图（cbgList）合并逐层画
 *    （EmueraConsole.cs:1829-1873）；**depth 0 槽画正文行**（:1850-1857）——
 *    depth > 0 的块在正文底下、负 depth 在正文之上。
 * 4. **块内几何**：`ConsoleDivPart.DrawTo`（ConsoleDivPart.cs:174-206）——
 *    relative：`rect = (PointX + xOffset, 行条顶 + PointY, width + 2, Height)`；
 *    absolute：`rect = (xOffset, 画布高 − PointY − Height, width + 2, Height)`
 *    （:178，锚画布底、X 不含锚列）；
 *    margin 收缩（:179-182）→ `SetClip(rect)` → `DrawBorder(背景+边框+圆角)`（:183-187）→
 *    border 收缩（:188-191）→ padding 收缩（:192-195）→ 内容盒 → 子行从内容盒顶逐行
 *    落点（每行 +LineHeight，:196-203）→ `ResetClip`。
 *
 * ## 本类的输出
 *
 * [Plan] 是**排好序的绘制清单**：消费方按序画，在 [Plan.behindText] 与 [Plan.inFrontOfText]
 * 之间画正文行（原版 depth 0 槽）。子行以**折行后的可视行**交付（[ChildRow] 携带
 * [DivChildDraw.wrap] 产出的 runs + 命令 + 对齐偏移；绘制与命中吃同一份产物）——
 * 原版递归子解析的尾部同样经 `ButtonsToDisplayLines(subDiv: true, SubDivisionWidth)`
 * 折行（HM :636-642、:698），折出的每条 `SetAlignment(align, customWidth=SubDivisionWidth)`
 * （HM :702-704 → ConsoleDisplayLine.cs:80-83，CENTER/RIGHT 偏移按块宽算，不是按
 * DrawableWidth）。
 *
 * ## 接线现状（CONTRACT-DIV-WIRE 已接线）
 *
 * 两个绘制器都经 [planFrame] 消费本规划（CONTRACT-DIV-WIRE W1/W2）：
 * - `view/ScreenComposer.composeInto`：behind 层画在正文前、front 层画在正文后
 *   （原版 depth 0 正文槽的整数档写法）；`view/ConsoleFrame` 在帧入口算好
 *   [DivOverlayFrame] 传进来；
 * - app 侧 `CanvasConsoleView.drawScreen`：同一份 [DivOverlayFrame] 喂 Android Canvas
 *   （背景圆角矩形 + 四边条带 + 子行），坐标同一套行顶表。
 * 正文行不再携带块区间原文 —— `ConsoleAdapter.adapt` 按 [DisplayDivBlock.sourceRange]
 * 把块区间从段原文里剔除（停用过渡期内联），子行只走覆盖层。
 */
class DivOverlayPlanner(
    private val metrics: CellMetrics,
    /** 脚本像素的基准字号（引擎生效 `Config.FontSize`）；0/负 = 不折算（headless 恒等档）。 */
    private val scriptFontPixelSize: Int = 0,
    /**
     * div 子行展平器（[DivChildDraw.wrap] 的展平入口；构造点自持专用实例 ——
     * `HtmlRunFlatten` 有逐串状态，别与正文展平器共用）。命中侧吃 plan 产物的
     * [ChildRow.runs]，不再各自展平，「画在哪」与「点哪里」天然同源。
     */
    private val childFlattener: HtmlRunFlatten,
) {

    /** 画布矩形（设备像素，块锚定坐标系内）。 */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * 块内的一行**可视子行**（折行后；内容盒内逐行落点，ConsoleDivPart.cs:197-203）。
     *
     * 一条逻辑子行（`DisplayDivBlock.children` 的行原文）经 [DivChildDraw.wrap] 按
     * 块宽折开，折出几条可视行这里就出几个元素 —— `children` 的下标即命中侧的
     * `DivFocus.childRow`（可视行号），绘制/命中两侧同源。
     *
     * @property html 所属逻辑子行原文（HTML；追溯用 —— 绘制与命中都不再经它展平）。
     * @property xPx 可视行左沿（内容盒左沿；行自身的对齐偏移在 [alignOffsetCells]，
     *   消费方按格折像素后加在 x 上 —— 与 `SetAlignment(align, customWidth)` 同式）。
     * @property yPx 可视行行条上沿（内容盒顶 + 可视行序 × 行高；折行使后续逻辑子行的
     *   落点顺延，与原版 `Lines` 数组逐行 +LineHeight 同形，CDP :196-203）。
     * @property subDivisionWidthPx 子层折行/对齐宽（HM :618-635，设备像素，已折算）；
     *   本行就是按它折出来的，透传给消费方折格（[CellMetrics.pixelsToCells]）算对齐偏移。
     * @property runs 本可视行的 run 切片（`DivChildDraw.Row.runs`）—— 命中侧
     *   `buttonSpans` 的输入。
     * @property cmds 本可视行的绘制命令（`DivChildDraw.Row.cmds`）。
     * @property alignOffsetCells 对齐偏移（半角格，可为负）—— `SetAlignment` 的
     *   `num3` 按块宽折格的口径（ConsoleDisplayLine.cs:86-90）。
     */
    data class ChildRow(
        val html: String,
        val xPx: Int,
        val yPx: Int,
        val subDivisionWidthPx: Int,
        val runs: List<TextRun>,
        val cmds: List<DrawCmd>,
        val alignOffsetCells: Int,
    )

    /** 一颗块的绘制项（margin 收缩后的边框矩形 + 背景/边框/圆角 + 内容盒 + 子行）。 */
    data class Block(
        val depth: Int,
        /** margin 收缩后的矩形（DrawBorder 的入参，ConsoleDivPart.cs:181-186）。 */
        val borderRectPx: Rect,
        /** 块底色 ARGB；null = Transparent（CDP :70 的 else 支），不画底。 */
        val backgroundArgb: Int?,
        /** 四边宽（设备像素；CSS 序 top/right/bottom/left，0 = 该边不画，DrawBorder :92/:109）。 */
        val borderWidthsPx: List<Int>,
        /** 四边框色 ARGB（CDP :90-98 的 `| 0xFF000000`；空表 = bcolor 没写，绘制侧回落
         *   `Config.ForeColor`，DrawBorder :94/:111）。 */
        val borderColorArgb: List<Int>,
        /** 四角圆角半径（设备像素；DrawBorder :38-68 逐角判 radius[i]==0 走直角）。 */
        val radiusPx: List<Int>,
        /** border+padding 收缩后的内容盒（:190-195）；子行落点与裁剪基准。 */
        val contentBoxPx: Rect,
        /** 子行（内容盒内逐行）。 */
        val children: List<ChildRow>,
        val isRelative: Boolean,
    )

    /**
     * 规划产物：[behindText] 画在正文之前、[inFrontOfText] 画在正文之后（两者各自已是
     * depth 降序），正文行画在两层之间 —— 即原版 depth 0 槽（EmueraConsole.cs:1850-1857）。
     */
    data class Plan(
        /** depth > 0 的块（画在正文**之前** = 正文底下），depth 降序。 */
        val behindText: List<Block>,
        /** depth < 0 的块（画在正文**之后** = 正文前面），depth 降序。 */
        val inFrontOfText: List<Block>,
        /** absolute 块锚定的画布高（ConsoleDivPart.cs:178 的 MainPicBox.Height）。 */
        val canvasHeightPx: Int,
    ) {
        /** 全部块（behind + front，各内已是 depth 降序）——命中/调试用。 */
        val all: List<Block> get() = behindText + inFrontOfText

        companion object {
            /** 无块规划（正文前后都不插层）。 */
            val EMPTY: Plan = Plan(emptyList(), emptyList(), 0)
        }
    }

    /**
     * 规划一颗锚行的块。
     *
     * @param blocks 该行的 [DisplayDivBlock]（core 侧产物，几何为脚本像素）。
     * @param anchorTopPx 锚行**行条上沿**在画布上的 y（行条高 = [CellMetrics.lineHeight]；
     *   对应原版 `DrawPartTo` 传给 `DrawTo` 的 pointY，EmueraConsole.cs:1870）。
     * @param anchorPointXPx 锚行内块的零宽锚列折成的设备像素（`ConsoleDivPart.PointX`，
     *   相对定位矩形 X 的加数，ConsoleDivPart.cs:178）。
     * @param canvasHeightPx 画布高（absolute 块锚底：ConsoleDivPart.cs:178）。
     * @param visibleTopLine 可见行区间上界（ConsoleEscapedParts.GetPartsInRange 的 `top`，
     *   官方口径 `num3 = num2 − (高/行高 + 1)`，EmueraConsole.cs:1806-1807）。
     * @param visibleBottomLine 可见行区间下界（`num2 = ScrollBar.Value − 1`，:1806）。
     * @param anchorLineNo 锚行绝对行号（登记行单位的基准，EmueraConsole.cs:2923）。
     */
    fun plan(
        blocks: List<DisplayDivBlock>,
        anchorTopPx: Int,
        anchorPointXPx: Int,
        canvasHeightPx: Int,
        visibleTopLine: Long,
        visibleBottomLine: Long,
        anchorLineNo: Long,
    ): Plan {
        if (blocks.isEmpty()) return Plan.EMPTY
        val lineHeight = metrics.lineHeight
        val behind = ArrayList<Block>()
        val front = ArrayList<Block>()
        for (block in blocks) {
            // 未逃逸的块没有覆盖绘制面（ConsoleButtonString.cs:361-364 跳过 div +
            // EmueraConsole.cs:2916-2925 只登记逃逸块）。
            if (!block.isEscaped) continue
            val top = scale(block.topPx)
            val bottom = scale(block.bottomPx)
            if (block.isRelative) {
                // 登记的行单位区间（EmueraConsole.cs:2923 的 ceil/floor 折算）与可见区间相交才画
                //（ConsoleEscapedParts.cs:127-129 对 relative（type==1）的判据）。
                val regTop = ceilDiv(top, lineHeight) + anchorLineNo
                val regBottom = floorDiv(maxOf(0, bottom - 1), lineHeight) + anchorLineNo
                val overlaps = regTop <= visibleBottomLine + 1 && regBottom >= visibleTopLine
                if (!overlaps) continue
            }
            // absolute 块无条件进（ConsoleEscapedParts.cs:77 的 type==3 恒过 :128 的第一支）
            // margin 先收缩再画边框（CDP :179-186 的 rect → SetClip → DrawBorder 次序）
            val borderRect = shrink(
                blockRect(block, anchorTopPx, anchorPointXPx, canvasHeightPx),
                block.margin.map { scale(it) },
            )
            val bg = block.backgroundColor?.let { (0xFF shl 24) or (it and 0xFFFFFF) }
            val borderW = block.border.map { scale(it) }
            val afterBorder = shrink(borderRect, block.border.map { scale(it) })
            val contentBox = shrink(afterBorder, block.padding.map { scale(it) })
            val children = ArrayList<ChildRow>(block.children.size)
            val scaledSubWidth = scale(block.subDivisionWidthPx)
            var childY = contentBox.y
            for (child in block.children) {
                // 逻辑子行 → 折行后的可视行（HM :698 的 subDiv 档）；每条可视行占一个
                // 行高（原版 Lines 数组逐行 +LineHeight，CDP :196-203），后续子行顺延。
                for (row in DivChildDraw.wrap(childFlattener, child.html, scaledSubWidth, metrics)) {
                    children.add(
                        ChildRow(
                            html = child.html,
                            xPx = contentBox.x,
                            yPx = childY,
                            subDivisionWidthPx = scaledSubWidth,
                            runs = row.runs,
                            cmds = row.cmds,
                            alignOffsetCells = row.alignOffsetCells,
                        ),
                    )
                    childY += lineHeight
                }
            }
            val item = Block(
                depth = block.depth,
                borderRectPx = borderRect,
                backgroundArgb = bg,
                borderWidthsPx = borderW,
                borderColorArgb = block.borderColors.map { (0xFF shl 24) or (it and 0xFFFFFF) },
                radiusPx = block.radius.map { scale(it) },
                contentBoxPx = contentBox,
                children = children,
                isRelative = block.isRelative,
            )
            if (block.depth > 0) behind.add(item) else front.add(item)
        }
        // depth 降序（EmueraConsole.cs:1830 的 `-a.CompareTo(b)`）：大的先画（更靠底层）。
        behind.sortByDescending { it.depth }
        front.sortByDescending { it.depth }
        return Plan(behind, front, canvasHeightPx)
    }

    /** 块矩形（ConsoleDivPart.cs:178；宽 +2 的官方 fudge 原样保留）。 */
    private fun blockRect(block: DisplayDivBlock, anchorTopPx: Int, anchorPointXPx: Int, canvasHeightPx: Int): Rect {
        val x = scale(block.xOffsetPx)
        val w = scale(block.widthPx) + 2
        val h = scale(block.heightPx)
        return if (block.isRelative) {
            Rect(scale(anchorPointXPx) + x, anchorTopPx + scale(block.pointYPx), w, h)
        } else {
            // absolute：X 只用 xOffset（不含锚列），Y 锚画布底（CDP :178）
            Rect(x, canvasHeightPx - scale(block.pointYPx) - h, w, h)
        }
    }

    /** margin/border/padding 的收缩式（ConsoleDivPart.cs:181/:190/:194 同一条）。 */
    private fun shrink(r: Rect, insets: List<Int>): Rect {
        if (insets.size < 4) return r
        val top = insets[0]
        val right = insets[1]
        val bottom = insets[2]
        val left = insets[3]
        return Rect(r.x + left, r.y + top, r.width - left - right, r.height - top - bottom)
    }

    /** 脚本像素 → 设备像素（`CellMetrics.scriptPixels`，与图形段同一把尺）。 */
    private fun scale(scriptPx: Int): Int = metrics.scriptPixels(scriptPx, scriptFontPixelSize)

    /** 登记行单位的上沿（EmueraConsole.cs:2923 的 `Math.Ceiling(Top / LineHeight)`）。 */
    private fun ceilDiv(v: Int, d: Int): Long {
        if (v <= 0) return (v / d).toLong()
        return (((v - 1) / d) + 1).toLong()
    }

    /** 登记行单位的下沿（:2923 的 `Math.Floor(max(0, Bottom - 1) / LineHeight)`）。 */
    private fun floorDiv(v: Int, d: Int): Long = (v / d).toLong()

    /**
     * 规划一帧：对扫描窗里每颗块各调一次 [plan]（块的锚列像素随块自带 —— 一行多块时
     * 各块锚列不同，`plan` 的单值锚列参数按块喂），再按 depth 降序合并成两侧。
     *
     * 与原版取景的对应：`GetPartsInRange`（ConsoleEscapedParts.cs:114-145）按**登记行区间**
     * 过滤 relative 块、absolute 块恒进；这里的过滤在 [plan] 内逐块做（入参
     * [visibleTopLine]/[visibleBottomLine] 与每行的 [DivOverlayRow.lineNo]）。扫描窗由
     * 消费方给：窗口行 + 上下各约一屏的 margin 行 —— 锚行在窗口外但登记区间与窗口
     * 相交的高块（语料 TITLE.ERB:79 的 `ypos=-CLIENTHEIGHT+行高` 就是 52 行的块）在原版
     * 仍会画（EmueraConsole.cs:1870 的落点公式按行号算、不按窗口成员算），margin 行
     * 就是给它们留的；块高超过 margin 的仍会漏，TODO(verify) 登记（语料 40 处 div 的
     * 最大纵向跨度即 TITLE 的 ~52 行，一屏 margin 全覆盖）。
     *
     * @param rows 扫描窗内携带块的行（按行号升序；空块行由消费方滤掉）
     * @param canvasHeightPx 画布高（absolute 块锚底：ConsoleDivPart.cs:178）
     * @param visibleTopLine 可见行区间上界（ConsoleEscapedParts.GetPartsInRange 的 `top`）
     * @param visibleBottomLine 可见行区间下界（`num2`）
     */
    fun planFrame(
        rows: List<DivOverlayRow>,
        canvasHeightPx: Int,
        visibleTopLine: Long,
        visibleBottomLine: Long,
    ): DivOverlayFrame {
        if (rows.isEmpty()) return DivOverlayFrame.EMPTY
        val behind = ArrayList<Block>(4)
        val front = ArrayList<Block>(2)
        for (row in rows) {
            for (block in row.blocks) {
                val plan = plan(
                    blocks = listOf(block),
                    anchorTopPx = row.anchorTopPx,
                    anchorPointXPx = block.anchorPointXPx,
                    canvasHeightPx = canvasHeightPx,
                    visibleTopLine = visibleTopLine,
                    visibleBottomLine = visibleBottomLine,
                    anchorLineNo = row.lineNo,
                )
                behind.addAll(plan.behindText)
                front.addAll(plan.inFrontOfText)
            }
        }
        if (behind.isEmpty() && front.isEmpty()) return DivOverlayFrame.EMPTY
        // plan() 逐块调用已经各自排过序；跨行合并后要重排（Kotlin sortByDescending 稳定，
        // 同 depth 保持行号序 —— 与原版登记序同向，ConsoleEscapedParts.Add 逐行登记）。
        behind.sortByDescending { it.depth }
        front.sortByDescending { it.depth }
        return DivOverlayFrame(behind, front)
    }
}

/**
 * 一帧的覆盖绘制清单（CONTRACT-DIV-WIRE W1/W2 的消费面）：[behind] 画在正文之前、
 * [front] 画在正文之后，各自 depth 降序 —— 即原版「depth > 0 槽 → depth 0 正文行 →
 * depth < 0 槽」的全局层序（EmueraConsole.cs:1829-1857：正 depth 键先于 0、负 depth 键
 * 后于 0，正文行全部在 `num7 == 0` 那一轮里自上而下画完）。
 *
 * 块的矩形已经是**画布坐标**的设备像素（锚行行条顶已折进 [Block]），
 * 消费方按序画即可，不再需要行信息。立绘（cbgList）与块在原版是**同一轮合并序**
 * （:1834-1848 按 z/depth 降序逐层），移植侧两头绘制器都沿用既有的近似：
 * 立绘 z >= 0 → 块 behind → 正文 → 块 front → 立绘 z < 0（ScreenComposer.composeInto
 * 的双趟循环），同键交错不复刻 —— 与 ScreenComposer 类文档「三层顺序」同一登记。
 */
class DivOverlayFrame(
    /** depth > 0 的块（正文底下），depth 降序。 */
    val behind: List<DivOverlayPlanner.Block>,
    /** depth < 0 的块（正文上面），depth 降序。 */
    val front: List<DivOverlayPlanner.Block>,
) {
    /** 本帧没有任何可见块（绝大多数帧的常态；消费方据此走原有快路径）。 */
    val isEmpty: Boolean get() = behind.isEmpty() && front.isEmpty()

    companion object {
        /** 无块帧（零分配单例）。 */
        val EMPTY: DivOverlayFrame = DivOverlayFrame(emptyList(), emptyList())
    }
}

/**
 * 覆盖绘制的**扫描行**：一条携带块的锚行 + 它的行条顶像素 + 绝对行号。
 * 由消费方在帧入口组装（`view/ConsoleScreen.divOverlayScan` 给扫描范围，
 * 行条顶由各自的行顶表折算 —— headless 是 `ScreenComposer.rowTopPx`、
 * 真机是 `RowLayout.plan` 的行表），[DivOverlayPlanner.planFrame] 只吃成品。
 *
 * @property blocks 该行锚定的块（[DisplayRow.divBlocks]，空表行不该进扫描结果）。
 * @property anchorTopPx 锚行行条上沿（画布坐标，可为负 = 窗口上沿之外的溢出行）。
 * @property lineNo 绝对行号（相对块的可见性过滤基准，EmueraConsole.cs:1865-1870 的
 *   `LineNo - shift`；shift 已由 LogBuffer 的 firstIndex 口径吸收）。
 */
class DivOverlayRow(
    val blocks: List<DisplayDivBlock>,
    val anchorTopPx: Int,
    val lineNo: Long,
)
