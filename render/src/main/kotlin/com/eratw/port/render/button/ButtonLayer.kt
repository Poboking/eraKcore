package com.eratw.port.render.button

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic

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
class ButtonLayer {

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

    private class TipRange(val row: Int, val start: Int, val end: Int, val text: String)

    /** 上一次 [attach] 的行窗口（逐元素引用比较的基准，见 [attach] 的复用分支）。 */
    private var attachedRows: List<DisplayRow>? = null

    private var pointerRow = -1
    private var pointerCol = -1
    private var pointerAcceptance = ButtonAcceptance.ANY
    private var pointerDown = false

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
                    } else {
                        targets.add(HitTarget(rowIndex, col, col + width, value, run.text))
                        ids.add(run.buttonId)
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

    /** 当前命中表，行优先、行内列升序（即 [attach] 的扫描序，确定性）。 */
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
        return if (graphic != null) graphic.widthCells else Grid.cells(run.text)
    }

    private companion object {
        /** [TextRun.buttonId] 的「身份未知」取值：手工构造的 run 走老口径（值相同 + 列号相接就并）。 */
        const val ID_UNKNOWN = -1
    }
}
