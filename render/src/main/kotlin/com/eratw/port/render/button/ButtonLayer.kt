package com.eratw.port.render.button

import com.eratw.port.core.host.FontDisplayWidth
import com.eratw.port.core.host.StaticFontDisplayWidth
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.DivChildDraw
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.GraphicGeometry

/**
 * `PRINTBUTTON` 命中区与点击回环（契约 m-button）。
 *
 * ## 输入与坐标
 * [attach] 吃 `grid` 路折行后的 [DisplayRow]；列坐标是**半角格**（口径同 [Grid]，
 * 不是像素），行号是 rows 的下标（显示行，不是逻辑行）。
 *
 * ## 列位移的消费点（DLIST-ALIGN）
 * 列游标的起点不是 0 而是 `DisplayRow.indentCells` —— ALIGNMENT CENTER/RIGHT 的整行平移
 * 在原版是入列时一次 `ShiftPositionX(num3)`，把偏移加到该行**每个**按钮的 `PointX` 上
 * （spec: `ConsoleDisplayLine.cs:86-90`、`ConsoleButtonString.cs:307-315`），而命中判定
 * 读的就是这份被平移过的 `PointX`（spec: `EmueraConsole.cs:2358-2361`）。
 * 本层与绘制侧（`displaylist/DisplayList.prepare`）用同一个来源，所以「画在哪」与
 * 「点哪里算」永远同列。偏移的可负性照抄：超宽行在 CENTER/RIGHT 下 `num2` 为负
 * （spec: `ConsoleDisplayLine.cs:80,83` 的减法），于是 `colStart`/`colEnd` 可以是负数，
 * 本层**不**把它夹回 0 —— 原版也没夹（`EmueraConsole.cs:2361` 直接比大小），
 * 完全出左界的按钮因此在可见列上恒不命中，与「画到左沿外看不见」一致。
 * 基准宽度（`Config.DrawableWidth`，spec: `Config.cs:466`）只在生产者那一侧用一次，
 * 本层不从 [DisplayRow.align] 重算偏移（分支算术只许有一份）。
 *
 * ## 区间：左闭右开
 * 每个按钮占 `[colStart, colEnd)`。原版在像素域判命中是
 * `PointX > x || PointX + Width < x`（两端都算命中，`EmueraConsole.cs:2361`；
 * 逃逸段那支写作 `PointX <= x && PointX + Width >= x`，`EmueraConsole.cs:2420`）。
 * 换成整数网格后若仍取闭区间，相邻按钮会共用同一格，故本层取半开区间
 * （与 ADR-003 的 `DirtyRect` 同口径）：`colEnd` 恰好是下一个按钮的首格。
 * 区间宽度 = 按钮段文本的格数（[Grid.cells]），列游标对**所有**段推进 ——
 * 一个按钮的 span 就是它首段左沿到末段右沿（`ConsoleButtonString.cs:300-304`；
 * `:292-303` 的累加循环把同一条按钮的**文本节点与图形节点**的 `Width` 一并加进同一条跨度，
 * 一条 `ConsoleButtonString` 无论含几段都只占 `ConsoleDisplayLine.Buttons` 的一个下标，
 * `ConsoleDisplayLine.cs:18,28`）。
 * **图形段的宽度有两种口径必须分清**：原版 `ConsoleImagePart.cs:41` 把 `base.Text` 钉成空串
 * （⇒ 字符跨度 0，[Grid.cells] 量出来也是 0），但它的**像素盒非零**（`:66-82` 按 img 属性
 * 或基图纵横比写 `base.Width`）。移植侧在生产侧就把这份像素盒折算成 [InlineGraphic.widthCells]
 * 随段带过来，所以本层图段宽度读 `graphic.widthCells` 而不是 `Grid.cells(text)`（见 [runWidth]）——
 * 与绘制侧 `displaylist/DisplayList.prepare`（`:496` 同一式）同源，保证「画在哪 = 点哪里」。
 *
 * ## 行内互不重叠 / 跨行各一条
 * 列游标单调右移 ⇒ 同一行的目标区间两两不相交；原版把同一行的按钮按打印序排进
 * 一条 `ConsoleDisplayLine.Buttons`（`PrintStringBuffer.cs:217-221,188-194`）。
 * 按钮跨行折行时，在每一行各成一条 [HitTarget] 且 `value` 相同 ——
 * 原版 `DivideAt` 把 `IsButton/IsInteger/Input/Inputs/Generation` 整份抄给拆出来的
 * 后半截（`ConsoleButtonString.cs:247-251`），后半截被插回 buttonList 交给下一行
 * （`PrintStringBuffer.cs:224-235`）并在下一行从 0 重新排点（`PrintStringBuffer.cs:242-249`），
 * 所以跨行的第二段 `colStart` 就是本行游标累加出来的位置（通常是 0）。
 *
 * ## 行内纵向命中（IMG-A4 I2）
 *
 * 原版命中路由除列判外还对**每个节点**判行内纵向范围 `num4 < Top || num4 > Bottom`
 * （`EmueraConsole.cs:2368`；`num4` 是点击点相对行顶的像素，`:2333,2356` 折出）。
 * 节点盒逐类出处：立绘图 `ConsoleImagePart.cs:31-33,81,93`（`[ypos, ypos+高]`）、
 * 矩形件 `ConsoleRectangleShapePart.cs:19-21,34-35`、文本件与 Space
 * `AConsoleDisplayNode.cs:29-31` 的缺省 `[0, FontSize]`。本层此前「按整行判定，无纵向
 * 子范围」—— 那条口径对文本段成立（缺省带在语料 `LINE HEIGHT == FONT SIZE == 18` 下
 * 就是整行），对立绘段**不成立**（`<img height='9px'>` 只盖半行，行内其余像素原版点不中）。
 * A4 起图形段入表时同记一道**行内纵向门**（[attach] 的 `gate`），带纵向信息的调用方走
 * [hitInline] 加严判定；无纵向信息的既有调用方走 [hit]，行为逐位不变。生产调用链已切换
 * （`CanvasConsoleView` 的点击/按下高亮/长按提示/`MOUSEB()` 四条路都走 `hitInline`，
 * 见该类的 `cellHitAt`），纯 JVM 侧未接线的调用方仍可用 [hit] 拿到接线前口径。
 * 溢出图形（跨行立绘）的「点到图身上」解析归 `view/GraphicTapAnchor`（逃逸支
 * `EmueraConsole.cs:2420` 的同构面），与本函数的非逃逸支互补。
 *
 * ## 值的类型与回环
 * `value` 是 Long（数值按钮）或 String（字符串按钮），源自 `PRINTBUTTON` 的
 * 操作数类型分派：`GetOperandType() == typeof(long)` 走 `PrintButton(text, long)`，
 * 否则走 `PrintButton(text, string)`（`Process.cs:1107-1124`，类型判定在 1115；
 * 两个重载见 `EmueraConsole.cs:3293-3307`）。构造侧的固定语义：数值按钮
 * `IsInteger = true` 且 `Inputs = input.ToString()`（`ConsoleButtonString.cs:81-95`，
 * 84-87 行），字符串按钮 `IsInteger = false` 只有 `Inputs`（`ConsoleButtonString.cs:97-110`）。
 * 因此点击回环永远产出一个字符串，规则见 [selectedString]。
 * 谁在等待点击由 [ButtonAcceptance] 表达。
 *
 * ## 临时行照常参与命中
 * [DisplayRow.temporary]（REUSELASTLINE 产生的临时行，spec: `EmueraConsole.cs:2891-2893`）
 * 在本层**不产生任何几何或筛选差别**：显示行上的 `IsTemporary` 字段（spec: `ConsoleDisplayLine.cs:14,46`）
 * 只被 `LastLineIsTemporary` 一个属性读（spec: `EmueraConsole.cs:513-522`），而这个属性的
 * 调用点全在**入列侧与语句侧**（`EmueraConsole.cs:2891-2893` 入列前删掉上一条临时行、
 * `Process.cs:2426,2514,2644` 的流程判定），绘制循环与命中循环（spec:
 * `ConsoleDisplayLine.cs:118-125`、`EmueraConsole.cs:2354-2361`）里没有一条临时行判断。
 * 临时行在屏上的那一帧就是普通行，按钮可点。
 * 「删除」这件事归日志/入列侧（`adapter/ConsoleAdapter.appendMerged` 已落地），本层不越界重做。
 *
 * ## 稳态复用（W8-RENDERPERF）
 * 命中表只由「行的几何 + 按钮值」决定，与这些对象的身份无关。所以 [attach] 先做一次
 * **逐元素引用**比较：行窗口与上一次是同一批 [DisplayRow] 实例（滚动跟随、VM 空转、
 * 只重绘某一行的帧都是这种形态）时直接返回，表内容逐项相同 —— 省掉整屏宽度度量与
 * 一整个 `HitTarget` 数组。指针态照旧清空（[clearPointer] 在任何分支之前执行），
 * 可观察行为不变。
 *
 * ## 状态与世代
 * 每次 [attach] 整表替换 —— 对应原版按 `Generation` 作废旧按钮
 * （创建时取号并推进：`ConsoleButtonString.cs:89-93,104-108`；
 * 命中筛选要求 `Generation == lastButtonGeneration`：`EmueraConsole.cs:2439,2448`）。
 * 层内只有实例字段，无静态可变全局（AGENTS.md 铁律 4）。
 *
 * ## 焦点 / 按下态
 * 只是绘制标记：[setPointer] 记录指（按）位置，[isFocused]/[isPressed] 供绘制路查询，
 * [hit] 的结果与之完全无关。原版同构 —— `DrawTo` 先算 `isFocus`/`isSelecting`
 * 再只把它们传给绘制（`ConsoleButtonString.cs:317-321,368-373`），
 * 而 `ButtonIsSelected`/`ButtonIsPointing` 只做引用比较（`EmueraConsole.cs:830-838`）。
 */
class ButtonLayer(
    /**
     * 字体感知宽度尺：缺省静态网格（与接线前 [Grid.cells] 逐值相同）。
     * 命中列区间必须与 [com.eratw.port.render.displaylist.DisplayList] 的列推进同一把尺，
     * 否则 `SETFONT` 段上「看得见」与「点得到」会错位。
     */
    private val fontWidth: FontDisplayWidth = StaticFontDisplayWidth,
) {

    private val targets = ArrayList<HitTarget>()

    /**
     * 与 [targets] 同下标的源按钮编号（[TextRun.buttonId]）；并入同一按钮时与 [targets] 一起只改末项。
     * 复用同一个容器（每次 [attach] 先 clear）：重建是滚动稳态帧的常态，别每帧新建一张表。
     */
    private val ids = ArrayList<Int>()

    /**
     * 长按提示区间（行优先、行内列升序），与 [targets] **分开**存。
     *
     * `<nonbutton title='…'>` 的提示挂在**不可点**文本上（原版连非按钮的
     * `ConsoleButtonString.Title` 也会赋值，`HtmlManager.cs:841-847`），若并进
     * [targets] 就会把不可点文本变成可点；所以另开一张只服务 [tooltip] 的区间表。
     * 每次 [attach] 先 clear，与 [targets] 同生命周期。
     */
    private val tipRanges = ArrayList<TipRange>()

    /**
     * 与 [targets] 同下标的**行内纵向门**表（IMG-A4 I2）：条目 = 该目标跨度里的图形段
     * （原版 `ConsoleImagePart` / `ConsoleShapePart` 节点）的列区间，命中时按图元节点的
     * `Top/Bottom` 行内纵盒判（[hitInline]）。null = 该目标全由文本段组成（无纵向门，
     * 原版文本节点的 `Top/Bottom` 恒为 `[0, FontSize]` ≈ 语料口径下的整行）。
     * 每次 [attach] 先 clear，与 [targets] 同生命周期；稳态复用分支不重建。
     */
    private val inlineGates = ArrayList<ArrayList<InlineGate>?>()

    private class TipRange(val row: Int, val start: Int, val end: Int, val text: String)

    /**
     * 行内纵向门（IMG-A4 I2）：目标跨度里一个图形段的列区间 + 图元负载。
     * 纵盒不在 [attach] 时算 —— [InlineGraphic.Rect] 的下沿依赖生效字号
     * （`ConsoleRectangleShapePart.cs:35` 的 `Math.Max(Config.FontSize, …)`），
     * 而本层是纯格口径层、不持字号，命中时随 [hitInline] 的入参现算（见 [nodeVerticalBox]）。
     */
    private class InlineGate(val colStart: Int, val colEnd: Int, val graphic: InlineGraphic)

    /** 上一次 [attach] 的行窗口（逐元素引用比较的基准，见 [attach] 的复用分支）。 */
    private var attachedRows: List<DisplayRow>? = null

    private var pointerRow = -1
    private var pointerCol = -1
    private var pointerAcceptance = ButtonAcceptance.ANY
    private var pointerDown = false

    /**
     * div 子行按钮的命中表（帧口径：像素几何）。
     *
     * 与 [targets] **分开存**：格坐标表只服务正文行（原版 `displayLineList` 各行，
     * `EmueraConsole.cs:2358-2361`），div 子行的按钮根本不在那张表里（CDP 子行不进
     * 行列表），命中走原版逃逸段那一条 `TestChildHitbox`（`ConsoleDivPart.cs:134-172`）。
     * 每次 [attachDiv] 整表替换，顺序即原版命中扫描序（见 [attachDiv]）。
     */
    private val divHits = ArrayList<DivHit>()

    /** 与 [divHits] **同下标**的提示原文（null = 该按钮没有 `title`）。 */
    private val divTips = ArrayList<String?>()

    /** 与 [divHits] 的 `blockIndex` **同下标**的块命中矩形（C# CDP :140 的盒矩形预判）。 */
    private val divBlockBoxes = ArrayList<DivBox>()

    /** 本帧 div 命中几何（像素 ↔ 行列换算的唯一输入）；null = 没有 div 覆盖层。 */
    private var divGeometry: DivHitGeometry? = null

    /** 像素指针（div 命中/焦点用；与 [pointerRow]/[pointerCol] 的格坐标指针各自独立）。 */
    private var pointerXPx = NO_POINTER
    private var pointerYPx = NO_POINTER
    private var pointerPixelDown = false

    /** 块命中矩形（设备像素，画布坐标；C# `ConsoleDivPart.cs:140` 的 `Rectangle` 近似）。 */
    private class DivBox(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * div 子行按钮的**焦点标记**：块下标 + 子行号 + 该按钮在子行内的格区间。
     *
     * 判据与原版同形：焦点按**按钮身份**算，与它在不在 div 内无关
     * （`ConsoleButtonString.cs:317-320` 的 `isFocus = IsButton && ButtonIsSelected(this)`）；
     * 绘制侧拿它比对「块下标 + 子行号 + 格区间相交」决定换不换色
     * （`ConsoleStyledString.cs:105-111`）。
     *
     * @property blockIndex `DivOverlayPlanner.Plan.all` 的下标（每帧稳定，见 [attachDiv]）
     * @property childRow 块内子行号（`DivOverlayPlanner.Block.children` 的下标）
     * @property colStart 子行内起始半角格（右开区间的左沿）
     * @property colEnd 子行内结束半角格（右开）
     */
    data class DivFocus(
        val blockIndex: Int,
        val childRow: Int,
        val colStart: Int,
        val colEnd: Int,
    )

    /**
     * div 子行按钮的命中项（**像素**几何，画布坐标）。
     *
     * 几何出处是 `ConsoleDivPart.TestChildHitbox`（CDP :134-172）：块矩形宽用脚本 `width`
     * （**不是**绘制侧的 `width + 2`，CDP :140 对 :178）、子行左沿 = 内容盒左沿
     * （CDP :134 的 `ShiftChildrenX(PointX + xOffset + divXOffset)`）、子行行条顶 =
     * 内容盒顶 + 子行号 × 行高（CDP :169 的 `relPointY += LineHeight`）。
     *
     * @property blockIndex `DivOverlayPlanner.Plan.all` 的下标（与 [DivFocus.blockIndex] 同源）
     * @property childRow 块内子行号
     * @property depth 块层序（命中优先级：负 → 正文行 → 0 → 正，见 [hitAt]）
     * @property colStart 子行内起始半角格
     * @property colEnd 子行内结束半角格（右开）
     * @property value 按钮值（与 [TextRun.buttonValue] 同源：Long 或 String）
     * @property label 按钮上印的字（图形按钮为空串）
     * @property xPx 命中矩形左沿（画布像素）
     * @property yPx 命中矩形上沿（画布像素）
     * @property widthPx 命中矩形宽（像素）
     * @property heightPx 命中矩形高（像素）
     */
    class DivHit(
        val blockIndex: Int,
        val childRow: Int,
        val depth: Int,
        val colStart: Int,
        val colEnd: Int,
        val value: Any,
        val label: String,
        val xPx: Float,
        val yPx: Float,
        val widthPx: Float,
        val heightPx: Float,
    ) {
        override fun toString(): String =
            "DivHit(block=$blockIndex, row=$childRow, depth=$depth, " +
                "cols=[$colStart,$colEnd), value=$value, label=$label, " +
                "rect=($xPx,$yPx,$widthPx,$heightPx))"
    }

    /**
     * div 命中/绘制的**帧几何**：像素 ↔ 行列换算的唯一输入（帧入口每帧给一次）。
     *
     * @property metrics 像素 ↔ 半角格换算器（与绘制侧同一份，否则「画在哪」与「点哪里」分叉）
     * @property rowCount 绘制口径行数（`ConsoleScreen.visibleRows().size`）
     * @property canvasHeightPx 画布高（行顶公式 `canvasHeight - (rowCount - row) * lineHeight` 的基准）
     * @property hitRowOffset 绘制行下标 → 命中行下标 的加数
     *   （`ConsoleScreen.visibleRowBase - hitRowBase`；绘制窗口比命中窗口多一排被裁掉的溢出行）
     */
    class DivHitGeometry(
        val metrics: CellMetrics,
        val rowCount: Int,
        val canvasHeightPx: Int,
        val hitRowOffset: Int,
    )

    /**
     * 重建命中表：按 `rows` 序、行内按段序扫描，列游标对全部段推进，
     * 起点是**本行的列偏移** [DisplayRow.indentCells]（不是 0）。
     *
     * 每行的偏移独立生效 —— 原版 `SetAlignment` 在 `addDisplayLine` 里对**每一条显示行**
     * 分别调用（spec: `EmueraConsole.cs:2937-2944`），折行产生的每一行按自己的宽度平移，
     * 所以这里按行取偏移、不在行间累积。
     * 两点出处差异要注意（都不需要本层动手，但口径要记对）：`SetAlignment` 自己带
     * 「只算一次」的闸（spec: `ConsoleDisplayLine.cs:52-56` 的 `aligned`），而 `PRINTN`
     * 那种「上一行不是行尾」的并入分支在**它之后**（spec: `EmueraConsole.cs:2947-2967`），
     * 并入时按前一条行的右沿整段搬一次（`:2955` 的 `ShiftPositionX(PointX + Width)`），
     * 不再重算对齐 —— 所以「按行取偏移」成立的前提是上游已经把并入与折行都算进
     * [DisplayRow.indentCells]（`adapter/ConsoleAdapter` 那一侧的职责，本层不重做）。
     *
     * 入表闸有两支，都不许放宽成「带值即入表」：
     * - **文本按钮段**：`kind == RunKind.BUTTON`、带 `buttonValue` 且宽度大于 0 才进表（空文本按钮
     *   原版根本不入流，`EmueraConsole.cs:3295,3303` 的 `if (!string.IsNullOrEmpty(str))` 守卫；
     *   零宽段在原版也会被压成 `Width = -1`（`ConsoleButtonString.cs:273-277`），落到半开区间上
     *   就是空区间、永远命中不了，所以直接不入表）。非按钮 kind 即使带着值也不入表 —— 源侧的文本
     *   按钮区间只由 [RunKind.BUTTON] 段承载（核心层的 `RowBuilder.addButton` 只被 `ButtonSpan`
     *   片调用）。
     * - **图形段**（[RunKind.IMAGE] / [RunKind.SHAPE]，`graphic != null`）：只有它属于某条**源按钮
     *   区间**才入表，即同时满足 `buttonValue != null` 且 `buttonId != ID_UNKNOWN`（编号与值同源，
     *   由 `adapter/HtmlRunFlatten` 在 `<button>` 包 `<img>` 时一并落上，原版对应一条
     *   `ConsoleButtonString` 的 `StrArray` 同时含文本片与 `ConsoleImagePart`，
     *   `ConsoleButtonString.cs:19,292-303`）。图段宽度取 `graphic.widthCells`（[runWidth]）。
     *   这条「kind 是图形段 + 编号有效」的双闸就是原来那条 kind 检查要防的东西换了形态落地：
     *   **手工构造的图形 run（[TextRun.buttonId] 默认 -1）与非按钮 kind 带着残值的段一律不入表**，
     *   不得因为一个残留的值变可点；零宽图段（`graphic.widthCells <= 0`）与 `buttonId` 缺失（-1）
     *   的图段维持不入表。
     *
     * **合并口径按源按钮身份，不按「值相同」**：一条原版 `ConsoleButtonString` 的 `StrArray`
     * 可以是多个样式段，也可以**文本片与 `ConsoleImagePart` 混装**（`ConsoleButtonString.cs:19,296-303`），
     * 渲染层把这些段搬成多条 run 但它们同属一个按钮（[TextRun.buttonId] 相同）⇒ 命中表只该有**一条**
     * （`ConsoleDisplayLine.cs:18,28` 的数组长度口径）：同一条按钮的文本段与其后（或其前）的图段
     * 在列号相接、值相同时并成**同一条**命中区，跨度 = 首段左沿到末段右沿（含图段的 `widthCells`，
     * `ConsoleButtonString.cs:292-303`）。反过来，两次显式 `PRINTBUTTON`
     * 即使值相同、列号相接也是两条独立按钮（`PrintStringBuffer.cs:103-123` 每次
     * `AppendButton` 都 `fromCssToButton` 后 `createButton`）⇒ 两条命中区，各自的 `Input`
     * 各读各的（`EmueraConsole.cs:411-417`）。`buttonId` 为 -1（手工构造的 run、身份未知）时
     * 退回「列号相接 + 值相同就并」的老口径。
     */
    fun attach(rows: List<DisplayRow>) {
        clearPointer()
        if (sameRows(attachedRows, rows)) {
            return
        }
        targets.clear()
        ids.clear()
        tipRanges.clear()
        inlineGates.clear()
        var rowIndex = 0
        while (rowIndex < rows.size) {
            val row = rows[rowIndex]
            var col = row.indentCells
            val runs = row.runs
            var r = 0
            while (r < runs.size) {
                val run = runs[r]
                val width = runWidth(run)
                val value = run.buttonValue
                // 两支入表闸，都不放宽成「带值即入表」（见本函数 KDoc 的「入表闸有两支」）。
                // 图形段必须是 IMAGE/SHAPE 且带着有效的源按钮编号才入表；手工 run 编号默认 -1，
                // PLAIN/HTML/ERROR/TEXT 等 kind 恒无 graphic，都进不来。
                val graphicInSourceButton = run.graphic != null &&
                    (run.kind == RunKind.IMAGE || run.kind == RunKind.SHAPE) &&
                    run.buttonId != ID_UNKNOWN
                // IMG-A4 I2：入表的图形段同时成为该目标的行内纵向门（命中时按图元节点
                // 的 Top/Bottom 判，见 [hitInline]）。文本段无门（原版文本节点恒为整行带）。
                val gate = if (graphicInSourceButton && width > 0) {
                    InlineGate(col, col + width, run.graphic!!)
                } else {
                    null
                }
                if (value != null && width > 0 && (run.kind == RunKind.BUTTON || graphicInSourceButton)) {
                    val lastIndex = targets.size - 1
                    val last = if (lastIndex >= 0) targets[lastIndex] else null
                    val lastId = if (lastIndex >= 0) ids[lastIndex] else ID_UNKNOWN
                    val sameSource = lastId == run.buttonId
                    if (last != null && last.row == rowIndex && last.colEnd == col && last.value == value && sameSource) {
                        targets[lastIndex] = HitTarget(
                            rowIndex,
                            last.colStart,
                            col + width,
                            value,
                            last.label + run.text,
                        )
                        if (gate != null) {
                            val gates = inlineGates[lastIndex] ?: ArrayList<InlineGate>(2).also {
                                inlineGates[lastIndex] = it
                            }
                            gates.add(gate)
                        }
                    } else {
                        targets.add(HitTarget(rowIndex, col, col + width, value, run.text))
                        ids.add(run.buttonId)
                        inlineGates.add(if (gate != null) ArrayList(listOf(gate)) else null)
                    }
                }
                run.tooltip?.let { tip ->
                    if (width > 0) tipRanges.add(TipRange(rowIndex, col, col + width, tip))
                }
                col += width
                r++
            }
            rowIndex++
        }
        attachedRows = rows
    }

    /** 行窗口是否与上一次逐元素同一批实例（[attach] 的复用判据；两个空表也算相同）。 */
    private fun sameRows(previous: List<DisplayRow>?, current: List<DisplayRow>): Boolean {
        if (previous === current) {
            return true
        }
        if (previous == null || previous.size != current.size) {
            return false
        }
        var i = 0
        while (i < previous.size) {
            if (previous[i] !== current[i]) {
                return false
            }
            i++
        }
        return true
    }

    /**
     * 当前**格坐标**命中表（行优先、行内列升序，即 [attach] 的扫描序，确定性）。
     *
     * 只列正文行的格坐标表 —— **div 子行按钮不在其中**：原版子行不进
     * `displayLineList` 的行列表（`EmueraConsole.cs:2358-2361` 只扫那些行的 Buttons），
     * 覆盖块的命中走另一条 `ConsoleDivPart.TestChildHitbox`（CDP :134-172）。
     * div 的命中项见 [divTargets]（像素几何）。
     */
    fun all(): List<HitTarget> = targets.toList()

    /** 不做类型筛选的命中查询（等价 `hit(row, col, ButtonAcceptance.ANY)`）。 */
    fun hit(row: Int, col: Int): HitTarget? = hit(row, col, ButtonAcceptance.ANY)

    /**
     * 命中查询：返回覆盖该格的按钮，无则 null。
     *
     * 行内区间互不相交（类文档），所以「第一个覆盖该格的可点候选」就是唯一答案；
     * 多个按钮共用同一 `value` 时它们仍是各自独立的条目，只有回环产出的字符串相同
     * （原版 `BINPUT` 取 `Input`、`BINPUTS` 取 `Inputs`，各自读各自那条，
     * `EmueraConsole.cs:411-417`）。
     * 扫描方向与原版相反要注意：原版按 `Buttons[Length - i - 1]` **倒序**扫
     * （`EmueraConsole.cs:2360`），意即同一条行里后打印的按钮先被接住（重叠时「上面那个」赢）；
     * 本层按 [attach] 的打印序正序取 `firstOrNull`。两者只在行内区间相交时才有差别，
     * 而相交在本层结构性不可能（列游标单调右移 + 每条区间 = 该按钮自己的格数），
     * 所以读数相同；`touchHitboxes` 的外扩也显式保证不越到下一个按钮的原始左沿。
     * [acceptance] 只做类型筛选，不改变几何：数值等待期字符串按钮不算命中
     * （`EmueraConsole.cs:2439,2456`）。
     * 入参 `col` 是**屏幕列**（0 起，负数直接不算命中）—— 原版鼠标坐标取自
     * `MainPicBox`、天然非负（spec: `EmueraConsole.cs:2321` 的 `point.X`），
     * 而条目区间的左沿可以是被对齐位移推成负数的值：条件写成
     * `col in colStart until colEnd` 正是原版 `PointX > x || PointX + Width < x`
     * 的整数网格形态（spec: `EmueraConsole.cs:2361`），整段出左界的按钮因此不可点。
     */
    fun hit(row: Int, col: Int, acceptance: ButtonAcceptance): HitTarget? {
        if (row < 0 || col < 0) {
            return null
        }
        return targets.firstOrNull {
            it.row == row && col in it.colStart until it.colEnd && acceptance.accepts(it.value)
        }
    }

    /**
     * 行内矩形命中（IMG-A4 I2）：在 [hit] 的列门之上加**图元段**的行内纵向门。
     *
     * 原版命中路由对按钮内的每个节点都判 `PointX > x || PointX + Width < x ||
     * num4 < Top || num4 > Bottom`（`EmueraConsole.cs:2368`；`num4` = 点击点相对行顶的
     * 像素，`EmueraConsole.cs:2333,2356` 逐行累加折出）—— 即 x 与 y 都在**该节点自己的
     * 矩形**里才算命中，不是整行。本层列门就是那条 x 判式的整数网格形态（[hit] 的
     * KDoc），此前缺的是纵向半边：本函数补上。两端**闭区间**照抄 `:2368` 的
     * `<`/`>` 跳过判（y ∈ [Top, Bottom]，逃逸支 `:2420` 同形）—— y 是像素域连续量，
     * 没有列门那侧「整数格右沿归下一格」的量化问题，不取半开。
     *
     * 节点纵盒的逐类出处见 [nodeVerticalBox]；文本段无门（覆盖列无门 = 整行带，直接命中）。
     * [InlineGraphic.Space] 的门盒虽按 `[0, FontSize]` 计（`AConsoleDisplayNode.cs:29-31`
     * 的缺省带，Space 不覆写），但那正是整行带 —— 语料口径
     * `LINE HEIGHT == FONT SIZE == 18`（`emuera.config:22-23`）下与「无门」逐位同解
     * （见类文档「行内纵向命中」一节的取舍）。
     *
     * 与 [hit] 的关系：[hit] 是**无纵向信息调用方**的既有口径（整个行高都可点），
     * 行为逐位不变；本函数是带 `yInRowPx`（点击点相对行顶的像素）的**加严面**，
     * 图形段列上 y 出了图元纵盒就不命中。**生产调用链已接**：`CanvasConsoleView` 的
     * `cellHitAt` 把 tapY 折成行内像素后，点击派发（`ConsoleScreen.clickInline`）、按下高亮、
     * 长按提示与 `MOUSEB()` 指向态四条路都走本函数（同一份 `CellHit` 产物）。
     * 溢出图形（跨行立绘）的点击解析归 `view/GraphicTapAnchor`（它自带纵向判式，
     * 且需要跨行解析锚行，与本函数的「同一行内」支互补）。
     *
     * **与本层纵盒的一处已知口径差（不是 bug，是原版同样如此）**：`nodeVerticalBox` 只按
     * `ypos`/`num` 给盒（`ConsoleImagePart.cs:81,93` 的 `Top/Bottom`），**不含** sprite 的
     * `DestBasePosition` 位移 —— 绘制侧 `drawImage` 会把该位移加进目标矩形
     * （`ASpriteSingle.cs:79-85`）。原版命中路由读的也是 `Top/Bottom`（`:2368`），同样不含
     * 位移 ⇒ 两侧都「偏」，方向与量级一致；端口在压缩行（k<1）上把位移也乘了 k，错位反而
     * 更小。带 `DestBasePosition` 的语料形态见 `As06MARK-IV/拼接.xml`，
     * TODO(verify)：真机读数待对拍（卡点＝无 PC 截图）。
     *
     * ## 压缩行的纵向 k（B1）
     *
     * [RowLayout] 会把「纯图行」的行高压到低于执行行高，绘制侧随即把图按
     * `k = GraphicGeometry.artRowScale(...)` 缩短（`CanvasConsoleView.drawImage` 的
     * `place[3] * k`）。命中侧此前按段自带的 `heightPx` 判纵盒 ⇒ 压缩行上的图按钮，
     * 点击盒比图高出一截（图上方的空白也吃点击）。
     *
     * 现在纵盒高度乘同一个 k（[GraphicGeometry.nodeRowScale] / [GraphicGeometry.scaledVerticalBox]，
     * 与绘制侧共一份算式，**上沿不乘**，理由见 [GraphicGeometry.scaledVerticalBox] 的 KDoc）。
     * 行几何由调用方在命中时给：`rowHeightPx` = `RowLayout.Plan.heights[row]`、
     * `lineHeightPx` = `Config.LineHeight` 的落地值、`pureArtRow` = `RowLayout.Plan.artOnly[row]`。
     * 三者缺省（0 / 0 / true）时 [GraphicGeometry.artRowScale] 的 `lineHeight <= 0` 守卫让
     * k 恒 1 = **压缩前的旧口径**，既有调用方行为逐位不变。
     *
     * @param rowHeightPx 该行在当前布局里的高度（像素）；非正 = 调用方不知道行几何（k = 1）
     * @param lineHeightPx 执行行高（像素）；非正 = 同上。**只给 rowHeightPx 不给本参数不会缩放**
     *   （守卫在 [GraphicGeometry.artRowScale] 里），接线时必须一起给
     * @param pureArtRow 该行是否只有图片段（缺省 true：行高没被压时 `artRowScale` 本来就返回 1，
     *   默认值不会误缩；压缩行则按缩后的图高判，方向是「少命中」而非「错命中」）
     */
    fun hitInline(
        row: Int,
        col: Int,
        yInRowPx: Float,
        fontSizePx: Int,
        acceptance: ButtonAcceptance = ButtonAcceptance.ANY,
        rowHeightPx: Float = 0f,
        lineHeightPx: Float = 0f,
        pureArtRow: Boolean = true,
    ): HitTarget? {
        if (row < 0 || col < 0) {
            return null
        }
        for (index in targets.indices) {
            val target = targets[index]
            if (target.row != row || col !in target.colStart until target.colEnd || !acceptance.accepts(target.value)) {
                continue
            }
            // 覆盖该列的段没有纵向门（文本段）→ 原版文本节点的缺省带 ≈ 整行（语料口径下
            // 逐位等价，见 [nodeVerticalBox] 与类文档）→ 直接命中。门存在但列不落在任何门上
            // （混排按钮的文本列）同理。图形段列才按节点盒加严。
            val gates = inlineGates.getOrNull(index) ?: return target
            val gate = gates.firstOrNull { col in it.colStart until it.colEnd } ?: return target
            val box = nodeVerticalBox(gate.graphic, fontSizePx)
            // B1：压缩行按绘制侧同一个 k 收窄纵盒（上沿不动、只缩高度，见 GraphicGeometry）。
            val k = GraphicGeometry.nodeRowScale(gate.graphic, pureArtRow, rowHeightPx, lineHeightPx)
            val hitBox = GraphicGeometry.scaledVerticalBox(box.first, box.second, k)
            // 图形段纵盒没盖住该 y：原版此时继续查同按钮的其余节点（:2366-2382 的
            // foreach-continue），但节点的列区间互不重叠 ⇒ 该列上没有第二个候选，
            // 与「无命中」同解；后续条目互不重叠（见类文档），也不必再扫。
            return if (yInRowPx >= hitBox[0] && yInRowPx <= hitBox[1]) target else null
        }
        return null
    }

    /**
     * 图元节点的行内纵盒 `[Top, Bottom]`（相对行顶，像素）—— 原版命中路由读的那两个字段，
     * 逐类出处：
     * - [InlineGraphic.Image] → `ConsoleImagePart.Top/Bottom`（`ConsoleImagePart.cs:31,33`
     *   覆写 + `:81` 的 `top = ypos 折算`、`:93` 的 `bottom = top + num`）；
     *   本层 [InlineGraphic.Image.yOffsetPx]/[InlineGraphic.Image.heightPx] 就是折算后的
     *   `(top, num)`（负高翻正在生产侧已完成，`InlineGraphic.fixNegativeExtent`）；
     * - [InlineGraphic.Rect] → `ConsoleRectangleShapePart.Top/Bottom`
     *   （`ConsoleRectangleShapePart.cs:19-21` 覆写 + `:34-35`：
     *   `top = Math.Min(0, rect.Y)`、`bottom = Math.Max(Config.FontSize, rect.Y + rect.Height)`，
     *   矩形件无论摆哪都至少盖住一行字）；
     * - [InlineGraphic.Space] → 无覆写，走 `AConsoleDisplayNode.cs:29-31` 的缺省
     *   `[0, FontSize]`（`ConsoleSpacePart` 不动纵盒）。
     *
     * 注意与 `view/GraphicGeometry.escapedLineBox` 的差别：那份是**逃逸判**的纵盒
     * （Space 返回 `[0,0]` = 永不溢出），本份是**命中判**的节点盒 —— Space 参与按钮时
     * 原版按节点缺省带整行判，两者不能混用。
     */
    private fun nodeVerticalBox(graphic: InlineGraphic, fontSizePx: Int): Pair<Int, Int> = when (graphic) {
        is InlineGraphic.Image -> graphic.yOffsetPx to (graphic.yOffsetPx + graphic.heightPx)
        is InlineGraphic.Rect ->
            minOf(0, graphic.yOffsetPx) to maxOf(fontSizePx, graphic.yOffsetPx + graphic.heightPx)
        is InlineGraphic.Space -> 0 to fontSizePx
    }

    /** 命中并回环一步：返回该次点击喂给 VM 的字符串（无命中给 null）。 */
    fun click(row: Int, col: Int, acceptance: ButtonAcceptance = ButtonAcceptance.ANY): String? =
        hit(row, col, acceptance)?.let { selectedString(it, acceptance) }

    /**
     * 长按提示查询：返回覆盖该格的 `title` 原文（null = 该格没有提示）。
     *
     * 提示区间与 [targets] 分开存（见 [tipRanges]）：`<nonbutton title='…'>` 的提示挂在
     * **不可点**文本上，原版同样把 `Title` 赋给非按钮的 `ConsoleButtonString`
     * （spec: `HtmlManager.cs:841-847`），PC 悬停照样弹 ToolTip（`EmueraConsole.cs:1891-1917`）。
     */
    fun tooltip(row: Int, col: Int): String? {
        if (row < 0 || col < 0) return null
        return tipRanges.firstOrNull { it.row == row && col in it.start until it.end }?.text
    }

    /**
     * 点击回环的字符串口径，对齐 `EmueraConsole.SelectedString`
     * （`EmueraConsole.cs:395-428`）：
     * - 数值等待（`IntValue`/`IntButton`）且按钮是数值 → `Input.ToString()`
     *   （`EmueraConsole.cs:411-413`）
     * - 字符串等待（`StrValue`/`StrButton`）→ `Inputs`（`EmueraConsole.cs:415-417`），
     *   数值按钮也走这支：它在构造时已把 `Inputs` 固定成 `Input.ToString()`
     *   （`ConsoleButtonString.cs:85`）
     * - 任意等待（`AnyValue`）→ 数值取 `Input.ToString()`、否则取 `Inputs`
     *   （`EmueraConsole.cs:419-425`）
     * - 数值等待期命中到字符串按钮 → 无结果（`EmueraConsole.cs:2439,2456` 已把它排除）
     *
     * 三支合起来即「数值按钮给十进制串、字符串按钮给原串」；Long 在 Kotlin 侧
     * `toString()` 与 C# `long.ToString()` 都是十进制、无分隔符，口径一致。
     */
    fun selectedString(target: HitTarget, acceptance: ButtonAcceptance): String? {
        if (acceptance == ButtonAcceptance.INT && target.value !is Long) {
            return null
        }
        return target.value.toString()
    }

    /**
     * 记录指针（触摸）位置，只影响绘制标记。
     * [pressed] 为 true 表示按下态（原版 `selectingButton`，松开时才产输入）。
     */
    fun setPointer(row: Int, col: Int, pressed: Boolean, acceptance: ButtonAcceptance = ButtonAcceptance.ANY) {
        pointerRow = row
        pointerCol = col
        pointerDown = pressed
        pointerAcceptance = acceptance
    }

    /**
     * 清空指针态（对应原版鼠标离开 / 刷新后的 `pointingStrings.Clear()`，
     * `EmueraConsole.cs:2291,2308`）。
     */
    fun clearPointer() {
        pointerRow = -1
        pointerCol = -1
        pointerDown = false
        pointerAcceptance = ButtonAcceptance.ANY
    }

    /**
     * 重建 **div 子行**的命中表（CONTRACT-DIV-HIT2）：吃一帧的覆盖规划 + 像素几何。
     *
     * ## 扫描序（= 原版命中扫描序）
     *
     * 原版把逃逸块的 depth 键 **升序**排好逐个处理（`EmueraConsole.cs:2343` 的
     * `Array.Sort(array)` + :2347-2434 的循环），并在第一个键 `>= 0` 处先插一趟**正文行**
     * 扫描（:2350-2396 的 `flag3` 分支）⇒ 命中优先级是
     * **depth < 0（升序）→ 正文行 → depth == 0 → depth > 0（升序）**。
     * 本表就按这个顺序摊平（[hitAt] 直接线性走）：
     * - 负 depth **升序**：越负越靠上（绘制侧 `plan` 是降序画，最后一笔在最上面）；
     * - depth == 0 的块在正文行之后（CDP 的 depth 0 槽：:2403 的 `num7 != 0 || containsKey(0)`）；
     * - 正 depth **升序**：depth 1 盖 depth 2。
     *
     * ## 几何（逐条出处）
     *
     * 块矩形宽用脚本 `width`（**不是**绘制侧的 `width + 2`）：CDP :140 与 :178 的差；
     * 矩形 y 是**内容盒顶**（CDP :140 的 `relPointY + PointY + yOffset`，`yOffset` =
     * margin+padding+border 的上边和）；子行行条顶 = 内容盒顶 + 子行号 × 行高（CDP :169）；
     * 子行左沿 = 内容盒左沿（CDP :134 的 `ShiftChildrenX(PointX + xOffset + divXOffset)`，
     * 其中 `divXOffset` 正是 margin+padding+border 的左边和 ⇒ 恰等于内容盒 x）。
     *
     * **与原版的一处近似**：`margin` 不进 `DivOverlayPlanner.Block`，矩形 x/高按
     * 「margin 全 0」取（即 `borderRect` 的 x 与高，宽减 2）。语料带 margin 的 div 极少，
     * 差异只落在块外沿几像素 —— TODO(verify)：真要精确得让 Block 带上 margin 四值
     * （`adapter/DivOverlay.kt` 是 C 路共用文件，本路不动）。
     *
     * 每帧整表替换（块的像素几何只由「行顶 + 块内几何」决定，与滚动位置无关时也仍按帧给，
     * 免得另造一份失效判据）。子行按钮的 run 切片吃 `DivOverlayPlanner.ChildRow.runs`
     * —— 与绘制侧的 `ChildRow.cmds` **同一次 `DivChildDraw.wrap` 的产物**，画与点因此
     * 同源（重折行/展平都不在命中侧另起一趟）。
     *
     * @param plan 覆盖规划（`DivOverlayPlanner.Plan` 或由 `DivOverlayFrame` 重建的同形清单；
     *   `blockIndex` 就是 `plan.all` 的下标 = 绘制侧 behind/front 两层的拼接下标）
     * @param geometry 像素 ↔ 行列换算（[DivHitGeometry]）
     */
    fun attachDiv(plan: DivOverlayPlanner.Plan, geometry: DivHitGeometry) {
        divHits.clear()
        divTips.clear()
        divBlockBoxes.clear()
        divGeometry = geometry
        val metrics = geometry.metrics
        collectDivHits(plan.behindText, 0, metrics)
        collectDivHits(plan.inFrontOfText, plan.behindText.size, metrics)
        // 组内按 depth 升序、同 depth 保持登记序（Kotlin sortedBy 稳定）：
        // 负 depth → depth 0 → 正 depth 即原版 Array.Sort 的键序（正文行由 hitAt 插在中段）。
        val ordered = ArrayList<DivCandidate>(divHits.size)
        for (i in divHits.indices) ordered.add(DivCandidate(divHits[i], divTips[i]))
        ordered.sortBy { it.hit.depth }
        divHits.clear()
        divTips.clear()
        for (candidate in ordered) {
            divHits.add(candidate.hit)
            divTips.add(candidate.tooltip)
        }
    }

    /** 清空 div 命中表（没有覆盖块的帧；帧入口在 `divOverlay.isEmpty` 时调）。 */
    fun clearDiv() {
        divHits.clear()
        divTips.clear()
        divBlockBoxes.clear()
        divGeometry = null
    }

    /** 本帧 div 子行按钮表（顺序 = 原版命中扫描序，见 [attachDiv]）。 */
    fun divTargets(): List<DivHit> = divHits.toList()

    /**
     * 当前像素指针下的 div 焦点（null = 没有 div 按钮被指着）。
     *
     * 「指着」按原版 `ButtonIsPointing` 的形状（`ConsoleButtonString.cs:320`）落到命中表上：
     * 指针赢下 [hitAt] 那条扫描链才算 —— 被正文行按钮盖住的 div 按钮不算焦点。
     */
    fun focusedDiv(): DivFocus? {
        val hit = hitAt(pointerXPx, pointerYPx) ?: return null
        return DivFocus(hit.blockIndex, hit.childRow, hit.colStart, hit.colEnd)
    }

    /** 当前像素指针的 div 按下态（原版 `isSelecting` 对应的那一路；抬起即灭）。 */
    fun pressedDiv(): DivFocus? = if (pointerPixelDown) focusedDiv() else null

    /**
     * 像素命中：返回该像素下（按原版扫描序）**第一个**命中的 div 子行按钮；
     * null = 该像素没有 div 命中，或正文行按钮按原版优先级赢走了它。
     *
     * 顺序与原版一致（见 [attachDiv]）：负 depth（升序）→ **正文行** → depth 0 → 正 depth
     * （升序）。正文行走 [hit]（格坐标）老路，本函数因此只回 div 项 ——
     * 调用方拿到 null 就落回「像素 → 格 → [hit]」那条既有路径。
     *
     * 块矩形是 CDP :140 的预判（`Rectangle.Contains`）：矩形外不扫子行；子行按钮自身的
     * 矩形再判一次（CDP :152/:159 的 `PointX <= x <= PointX + Width` 与
     * `Top <= y <= Bottom` 换成整数半开区间 —— 与 [hit] 的格区间口径一致）。
     *
     * @param xPx 画布像素 x（可负，负直接不命中：原版鼠标坐标天然非负）
     * @param yPx 画布像素 y
     */
    fun hitAt(xPx: Int, yPx: Int): DivHit? {
        val geometry = divGeometry ?: return null
        if (xPx == NO_POINTER || yPx == NO_POINTER || xPx < 0 || yPx < 0) return null
        var i = 0
        while (i < divHits.size) {
            val hit = divHits[i]
            if (hit.depth >= 0) break
            if (divContains(hit, xPx, yPx)) return hit
            i++
        }
        // 正文行（原版 depth 0 槽的那一趟扫描）：盖住像素就由正文行赢，本函数回 null。
        if (rowTargetAt(geometry, xPx, yPx) != null) return null
        while (i < divHits.size) {
            val hit = divHits[i]
            if (divContains(hit, xPx, yPx)) return hit
            i++
        }
        return null
    }

    /**
     * 像素长按提示（div 与正文行同一优先级链）：返回该像素赢下的提示原文，null = 没有提示。
     *
     * 原版 ToolTip 取的是光标下那条 `ConsoleButtonString.Title`（`EmueraConsole.cs:1891-1917`），
     * 而光标下是哪条按钮由与点击同一套扫描决定 —— 所以这里复用 [hitAt] 的优先级，
     * 只是把「命中」换成「有 title」。
     *
     * @param xPx 画布像素 x
     * @param yPx 画布像素 y
     */
    fun tooltipAt(xPx: Int, yPx: Int): String? {
        val geometry = divGeometry ?: return pixelRowTooltip(null, xPx, yPx)
        if (xPx == NO_POINTER || yPx == NO_POINTER || xPx < 0 || yPx < 0) return null
        var i = 0
        while (i < divHits.size) {
            val hit = divHits[i]
            if (hit.depth >= 0) break
            if (divContains(hit, xPx, yPx)) return divTips[i]
            i++
        }
        val rowHit = rowTargetAt(geometry, xPx, yPx)
        val rowTip = pixelRowTooltip(geometry, xPx, yPx)
        // 正文行赢的判据两条都算：可点按钮（[rowTargetAt]）与不可点文本的 `title`
        // （原版连非按钮的 ConsoleButtonString.Title 也赋值，HtmlManager.cs:841-847）。
        if (rowHit != null || rowTip != null) return rowTip
        while (i < divHits.size) {
            val hit = divHits[i]
            if (divContains(hit, xPx, yPx)) return divTips[i]
            i++
        }
        return null
    }

    /**
     * 记录**像素**指针（div 命中/焦点用）：与 [setPointer] 的格坐标指针各自独立，
     * 因为 div 子行按钮不在行列表里、没有「行下标 × 列」这组坐标。
     *
     * @param xPx 画布像素 x（客户区坐标，与 `hitAt` 同一套）
     * @param yPx 画布像素 y
     * @param pressed 是否按下（原版 `isSelecting` 那一路）
     */
    fun setPointerPx(xPx: Int, yPx: Int, pressed: Boolean) {
        pointerXPx = xPx
        pointerYPx = yPx
        pointerPixelDown = pressed
    }

    /** 清空像素指针（指针离开客户区 / 刷新后）。 */
    fun clearPointerPx() {
        pointerXPx = NO_POINTER
        pointerYPx = NO_POINTER
        pointerPixelDown = false
    }

    /** 收集一层块的命中项（[attachDiv] 的两趟之一；[blockIndexBase] 是该层在 `plan.all` 的起下标）。 */
    private fun collectDivHits(
        blocks: List<DivOverlayPlanner.Block>,
        blockIndexBase: Int,
        metrics: CellMetrics,
    ) {
        for ((offset, block) in blocks.withIndex()) {
            val blockIndex = blockIndexBase + offset
            val rect = block.borderRectPx
            val box = block.contentBoxPx
            while (divBlockBoxes.size <= blockIndex) divBlockBoxes.add(DivBox(0f, 0f, 0f, 0f))
            divBlockBoxes[blockIndex] = DivBox(
                x = rect.x.toFloat(),
                y = box.y.toFloat(),
                width = (rect.width - DIV_WIDTH_FUDGE).toFloat(),
                height = rect.height.toFloat(),
            )
            if (box.width <= 0) continue
            for ((childRow, child) in block.children.withIndex()) {
                // 折行后的可视行：列号仍相对本行左沿（`DrawCmd.colStart` 的口径，焦点比对
                // `DivFocus` 同式），行对齐偏移折像素只加在命中 x 上 —— 与绘制侧的
                // `originX = 内容盒左沿 + alignOffset` 同一式（ConsoleDisplayLine.cs:80-90）。
                val spans = DivChildDraw.buttonSpans(child.runs, metrics)
                if (spans.isEmpty()) continue
                val rowLeftPx = box.x + metrics.cellsToPixels(child.alignOffsetCells)
                for (span in spans) {
                    val x = rowLeftPx + metrics.cellsToPixels(span.colStart)
                    val width = metrics.cellsToPixels(span.colEnd) - metrics.cellsToPixels(span.colStart)
                    divHits.add(
                        DivHit(
                            blockIndex = blockIndex,
                            childRow = childRow,
                            depth = block.depth,
                            colStart = span.colStart,
                            colEnd = span.colEnd,
                            value = span.value,
                            label = span.label,
                            xPx = x.toFloat(),
                            yPx = (child.yPx + span.topPx).toFloat(),
                            widthPx = maxOf(1, width).toFloat(),
                            heightPx = maxOf(1, span.bottomPx - span.topPx).toFloat(),
                        ),
                    )
                    divTips.add(span.tooltip)
                }
            }
        }
    }

    /** 一个候选（命中项 + 提示），排序期用。 */
    private class DivCandidate(val hit: DivHit, val tooltip: String?)

    /** 命中项是否命中该像素（块矩形 CDP :140 预判 + 子行按钮矩形 CDP :152/:159）。 */
    private fun divContains(hit: DivHit, xPx: Int, yPx: Int): Boolean {
        val box = divBlockBoxes.getOrNull(hit.blockIndex) ?: return false
        if (xPx < box.x || xPx >= box.x + box.width || yPx < box.y || yPx >= box.y + box.height) {
            return false
        }
        return xPx >= hit.xPx && xPx < hit.xPx + hit.widthPx &&
            yPx >= hit.yPx && yPx < hit.yPx + hit.heightPx
    }

    /** 像素 → 正文行的命中目标（绘制行号换成命中行号后走 [hit]；不在窗口内返回 null）。 */
    private fun rowTargetAt(geometry: DivHitGeometry, xPx: Int, yPx: Int): HitTarget? {
        val lineHeight = geometry.metrics.lineHeight
        if (lineHeight <= 0 || geometry.rowCount <= 0 || geometry.canvasHeightPx <= 0) return null
        if (yPx >= geometry.canvasHeightPx) return null
        // 行顶公式 rowTopPx(row) = canvasHeight - (rowCount - row) * lineHeight 的逆：
        // 距画布底的整行数 = (canvasHeight - 1 - y) / lineHeight。
        val fromBottom = (geometry.canvasHeightPx - 1 - yPx) / lineHeight
        val drawRow = geometry.rowCount - 1 - fromBottom
        val hitRow = drawRow + geometry.hitRowOffset
        return hit(hitRow, geometry.metrics.pixelsToCells(xPx))
    }

    /** 像素 → 正文行的长按提示（[rowTargetAt] 的同一条换算；行外返回 null）。 */
    private fun pixelRowTooltip(geometry: DivHitGeometry?, xPx: Int, yPx: Int): String? {
        if (geometry == null) return null
        val lineHeight = geometry.metrics.lineHeight
        if (lineHeight <= 0 || geometry.rowCount <= 0 || geometry.canvasHeightPx <= 0) return null
        if (yPx >= geometry.canvasHeightPx) return null
        val fromBottom = (geometry.canvasHeightPx - 1 - yPx) / lineHeight
        val drawRow = geometry.rowCount - 1 - fromBottom
        return tooltip(drawRow + geometry.hitRowOffset, geometry.metrics.pixelsToCells(xPx))
    }

    /** 该目标当前是否处于焦点（指着）态 —— 纯绘制标记。 */
    fun isFocused(target: HitTarget): Boolean = hit(pointerRow, pointerCol, pointerAcceptance) == target

    /** 该目标当前是否处于按下态 —— 纯绘制标记。 */
    fun isPressed(target: HitTarget): Boolean = pointerDown && isFocused(target)

    /**
     * 触屏目标外扩（平台适配的最小触摸尺寸，安卓口径 48dp）。
     *
     * 入参是**半角格**而不是 dp：dp 到格的换算需要实机的 density 与
     * `Config.FontSize`，换算入口留在 Android 侧。原版只有「相对像素 × 字号 / 100」
     * 这一处同类换算（`ConsoleButtonString.cs:157-163` 的 `LockPointX`）。
     *
     * 规则（确定性，与 [attach] 同序；同行按列升序处理，「先到先得」）：
     * - 已达宽度的原样返回；
     * - 宽度不足时左右各扩一半（奇数余数给右侧）；一侧被本行的**最左列**（该行第一个按钮的
     *   `colStart`，ALIGNMENT CENTER/RIGHT 位移后可以是负数；无位移时仍是 0 列）/ 窗口右沿
     *   （[clientWidthCells]，默认取黄金基准的 `CLIENTWIDTH=1800` 半角格口径）/
     *   同行前一个按钮**已外扩后的右沿**夹住时，把差额让给另一侧继续扩；
     * - 右沿最远只到同行下一个按钮的原始左沿，故外扩后的集合仍两两不相交；
     * - 两侧都夹死仍不足 `minWidthCells` 就保持夹到的宽度，宁可小于最小尺寸也不重叠。
     *
     * TODO(verify)：原版是像素域单点命中，没有「触摸最小尺寸」概念；外扩区间可能
     * 覆盖同一按钮的可视文本之外空白（真机上会被用户当作可点区），M4 截图验收需
     * 确认这个外扩是否只做命中提示、绘制仍按原始区间。卡点：没有 Android SDK，
     * density 未知，`48dp` 换算成格数无法在本波固化。
     */
    fun touchHitboxes(minWidthCells: Int, clientWidthCells: Int = Grid.DEFAULT_CLIENT_WIDTH): List<HitTarget> {
        if (minWidthCells <= 1) {
            return all()
        }
        val out = ArrayList<HitTarget>(targets.size)
        var previousRight = 0
        var previousRow = -1
        for ((index, target) in targets.withIndex()) {
            if (target.row != previousRow) {
                previousRow = target.row
                previousRight = 0
            }
            val leftBound = minOf(previousRight, target.colStart)
            val rightBound = nextStartInRow(index, clientWidthCells)
            val expanded = if (target.colEnd - target.colStart >= minWidthCells) {
                target
            } else {
                val slack = minWidthCells - (target.colEnd - target.colStart)
                val leftGrowth = slack / 2
                val rightGrowth = slack - leftGrowth
                var left = target.colStart - leftGrowth
                var right = target.colEnd + rightGrowth
                if (left < leftBound) {
                    right += leftBound - left
                    left = leftBound
                }
                if (right > rightBound) {
                    left -= right - rightBound
                    right = rightBound
                    if (left < leftBound) {
                        left = leftBound
                    }
                }
                HitTarget(target.row, left, right, target.value)
            }
            out.add(expanded)
            previousRight = expanded.colEnd
        }
        return out
    }

    /**
     * 同行后一个按钮的原始左沿（外扩不得越过它）；
     * 没有后继则可一直扩到窗口右沿（[clientWidthCells]）。
     */
    private fun nextStartInRow(index: Int, clientWidthCells: Int): Int {
        val target = targets[index]
        for (j in index + 1 until targets.size) {
            if (targets[j].row == target.row) {
                return targets[j].colStart
            }
        }
        return clientWidthCells.coerceAtLeast(target.colEnd)
    }

    /**
     * 段宽：**文本段量字符、图形段读像素盒折算的格数**，两种口径分岔在这里收（见类文档「区间」节）。
     * - 文本段：`Grid.cells(text)`（半角 1 / 全角 2，与 [Grid.cells] 唯一表同源）。
     * - 图形段：`graphic.widthCells`。原版 `ConsoleImagePart.cs:41` 的 `base.Text = ""` ⇒ 字符跨度 0，
     *   若这里也走 `Grid.cells(text)` 就恒 0、按钮列游标不前进（图段既进不了表、进了也零宽）；
     *   图段的真实宽度是它的像素盒（`ConsoleImagePart.cs:66-82` 写 `base.Width`），生产侧折算成
     *   [InlineGraphic.widthCells] 带过来。取它与绘制侧 `displaylist/DisplayList.prepare`（`:496`
     *   同一式）严格同源，保证「画在哪 = 点哪里」。
     */
    private fun runWidth(run: TextRun): Int {
        val graphic = run.graphic
        return if (graphic != null) graphic.widthCells else fontWidth.cells(run.text, run.fontName)
    }

    private companion object {
        /** [TextRun.buttonId] 的「身份未知」取值：手工构造的 run 走老口径（值相同 + 列号相接就并）。 */
        const val ID_UNKNOWN = -1

        /** 「没有像素指针」哨兵（[pointerXPx]/[pointerYPx] 的初值；合法画布像素非负）。 */
        const val NO_POINTER = Int.MIN_VALUE

        /**
         * 绘制侧块矩形宽里的官方 fudge：`ConsoleDivPart.cs:178` 的 `width + 2`
         * （命中侧 CDP :140 用的是裸 `width`，差的就是这 2 像素）。
         */
        const val DIV_WIDTH_FUDGE = 2
    }
}
