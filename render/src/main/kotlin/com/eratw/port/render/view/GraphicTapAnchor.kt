package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic

/**
 * 「点在溢出图形上」的**锚行回退** —— 点击立绘无反应的修法落点。
 *
 * ## 问题：命中表没有纵向维度
 *
 * `ButtonLayer.attach` 建的命中区是「行 × 列」两维：图形按钮段只登记在**锚行**（`<img>`
 * 所在的那条显示行）的列区间上。而高于一行的立绘按原版口径**向下溢出**、压在脚本预留的
 * 空行上（原版从不按行高缩图：`EmueraConsole.cs:1855` 的 `num += Config.LineHeight`
 * 与行内容无关，`ConsoleImagePart.DrawTo` 的 `destRect` 高由脚本给，`ConsoleImagePart.cs:82,93`；
 * 语料 `GET_BODY_SETS.ERB:115` 的按钮图高 9~18 行）—— 图身覆盖的那几排**没有任何命中区**，
 * 点上去 `ButtonLayer.hit` 落空，装配层取值等待下补一条空回车，玩家看到「点击立绘无反应」。
 *
 * ## 原版怎么做（本函数的出处）
 *
 * - `ConsoleButtonString.cs:165-185` 的 `FilterEscaped()`：把 `Top < 0 || Bottom > LineHeight`
 *   （`ConsoleButtonString.cs:171`）的部件收进 `escaped`；
 * - `EmueraConsole.cs:2916-2923`：行入列时把溢出部件按
 *   `ceil(Top/LineHeight)+lineNo .. floor(max(0,Bottom-1)/LineHeight)+lineNo` 登记进
 *   `ConsoleEscapedParts`（`ConsoleEscapedParts.cs`，按 depth 键控）—— **部件覆盖到的每一条**
 *   显示行都拿到一份登记；
 * - `EmueraConsole.cs:2420`：命中判定对 escaped 部件用**节点像素盒**的纵向判式
 *   `num4 >= item.Top && num4 <= item.Bottom`（两端都算命中），列向仍是 `PointX <= x && PointX + Width >= x`。
 *
 * 即原版命中 = 列判定（`:2366-2382` 逐节点）∪ 纵向像素盒判定（`:2420` escaped 支）。
 * 本端口命中层只复刻了前者；本函数补的是后者：**点在哪画出的像素盒里，就解析回锚行的按钮**。
 *
 * ## 几何口径（与绘制严格同源）
 *
 * 「画在哪」由 `CanvasConsoleView.drawScreen` 决定：两趟铺墨都以 `layout.tops[cmd.row]`
 * 为行原点（escape 支见 drawScreen 的第二趟），段纵盒相对行顶 = [GraphicGeometry.escapedLineBox]
 * 的 `[top, bottom]`（Image = `[yOffsetPx, yOffsetPx + heightPx]`，`ConsoleImagePart.cs:81,93`）。
 * 所以回退判定 = `tapY ∈ [tops[anchor] + boxTop, tops[anchor] + boxBottom]`，
 * 与 `drawScreen` 逐值同一份算式，保证「画在哪 = 点哪里」。
 *
 * **不逐符号复刻** `EmueraConsole.cs:2268-2460` 的 `MoveMouse`（其滑窗变量 `num4` 的坐标
 * 基准在反编译源里前后混乱、不可验证）；按移植侧自身的绘制几何判，语义等价于
 * 「像素盒包含点击点」。纵向判式两端取**闭区间**，与 `:2420` 的 `>= / <=` 一致。
 *
 * ## 压缩行的纵向 k（B1）
 *
 * [RowLayout] 会把纯图行的行高压到低于执行行高，绘制侧随即按
 * `k = GraphicGeometry.artRowScale(...)` 把图缩短（`CanvasConsoleView.drawImage` 的
 * `place[3] * k`）。本函数的纵盒此前用段自带尺寸（= 压缩前的盒），压缩行上就比图的实际
 * 覆盖高出一截 —— 点在图上方那段空白也会解析回锚行 = **错命中**。
 *
 * 现在同一份 k 由 [GraphicGeometry.nodeRowScale] 给（与绘制侧共一份算式，命中侧不另抄），
 * 纵盒高度按 k 收窄（[GraphicGeometry.scaledVerticalBox]，上沿不动：绘制侧的 `ypos`
 * 本来就不乘 k）。
 *
 * **与 `tops` 近似式的冲突，按保守侧收口**：本函数拿到的 `tops` 只是行顶表，
 * 「这一行的行高」要靠 `tops[i+1] - tops[i]` 反推（末行没有下一行 ⇒ 推不出）。
 * 因此：
 * 1. 调用方**有** `RowLayout.Plan` 时必须把 `heights` / `artOnly` / `lineHeightPx` 一起传
 *    （绘制侧用的就是这三个值，逐值同源）；
 * 2. 不传 `heights` 时退化为 `tops` 差分近似，末行（无下一行）按「行高未知」= k 1；
 * 3. `lineHeightPx` 缺省 0 ⇒ [GraphicGeometry.artRowScale] 的 `lineHeight <= 0` 守卫让 k 恒 1，
 *    即**压缩前的旧口径**（宽松侧、可能多命中）。这一支只为既有调用方行为逐位不变而保留，
 *    新接线（`CanvasConsoleView.pointerCellRow`）必须补齐三个参数 —— 见交付清单。
 *    TODO(verify)：不传 `lineHeightPx` 时无法区分「图高于一行所以不缩」与「行被压了该缩」，
 *    在拿到行几何之前不猜；卡点＝调用方接线归组长。
 * 4. `artOnly` 缺省按 true（纯图行）算：非纯图行的 `rowHeight` 恒等于执行行高，`artRowScale`
 *    在那一档本来就返回 1，默认值不产生差别；真被压过时宁可少命中，不可错命中。
 *
 * ## 候选闸（与 `ButtonLayer.attach` 的图形段入表闸逐条相同）
 *
 * 锚行候选段必须同时满足：`graphic != null`、kind 是 IMAGE/SHAPE、`buttonId != -1`、
 * `buttonValue != null`、占宽 > 0，且 `tapCol` 落在段列区间（半开区间）内。列游标口径与
 * [com.eratw.port.render.button.ButtonLayer.attach] 同式：`indentCells` 起、图段读
 * `graphic.widthCells`、文本段 `Grid.cells(text)`。手工构造的 run（编号 -1）与非按钮 kind
 * 带残值的段一律不当锚 —— 不得因为一个残留的值把不可点图形变可点。
 *
 * ## 扫描序
 *
 * 自 `tapRow` 出发按距离**由近到远、先上后下**（0, -1, +1, -2, +2, …）：立绘向下溢出，
 * 锚行几乎总在点击行上方，就近先命中；负 `ypos` 段理论上可把锚挂到点击行下方，向下那支兜住。
 * 纵盒判定本身已把答案钉死，扫描序只影响「多个候选都盖住该点」时的取舍 —— 取**最近的锚**。
 * 锚行若在绘制窗口的顶排（命中口径的行号为负）之外，调用方换算后自然落空，与修复前同象。
 *
 * 纯 JVM：不碰 Android API、无可变静态状态（AGENTS.md 铁律 4）；每次点击调用一次，
 * 低频路径，不做缓存。
 */
object GraphicTapAnchor {

    /**
     * 解析点击点所属**溢出图形按钮**的锚行（绘制口径行号 = [DisplayRow] 表下标）。
     *
     * @param rows 绘制口径的显示行（调用方传 `ConsoleScreen.visibleRows()`，与 `tops` 同下标）
     * @param tops 行顶表（`RowLayout.Plan.tops`，与 `rows` 同下标、同坐标系）
     * @param tapRow 点击点的行（`RowLayout.Plan.rowIndex` 的产物，绘制口径）
     * @param tapCol 点击点的列（半角格）
     * @param tapY 点击点的 y（视图像素，与 `tops` 同基准）
     * @param fontSizePx 生效字号（`Config.FontSize`，矩形段纵盒下沿的默认值）
     * @param heights 各行行高（`RowLayout.Plan.heights`，与 `tops` 同下标）；null = 用
     *   `tops[i+1] - tops[i]` 近似（末行推不出 ⇒ 按未知处理，k = 1）
     * @param artOnly 各行是否纯图行（`RowLayout.Plan.artOnly`）；null = 一律按纯图行算
     * @param lineHeightPx 执行行高（与绘制侧同一个 `Config.LineHeight` 落地值）；
     *   非正 = 未知 ⇒ 不做纵向缩放（压缩前的旧口径，见类文档的保守侧说明）
     * @return 锚行的绘制口径行号；没有候选覆盖该点返回 -1（调用方维持原行为）
     */
    fun resolveAnchorRow(
        rows: List<DisplayRow>,
        tops: FloatArray,
        tapRow: Int,
        tapCol: Int,
        tapY: Float,
        fontSizePx: Int,
        heights: FloatArray? = null,
        artOnly: BooleanArray? = null,
        lineHeightPx: Float = 0f,
    ): Int {
        val n = minOf(rows.size, tops.size)
        if (n == 0 || tapCol < 0) return -1
        // 距离 0 先查一次，之后每轮先上 (-d) 后下 (+d)。
        if (tapRow in 0 until n && anchorsAt(rows[tapRow], tops[tapRow], rowHeightAt(tapRow, tops, heights, n),
                artOnly?.getOrNull(tapRow) ?: true, lineHeightPx, tapCol, tapY, fontSizePx)) {
            return tapRow
        }
        var d = 1
        while (d < n) {
            val up = tapRow - d
            if (up >= 0 && anchorsAt(rows[up], tops[up], rowHeightAt(up, tops, heights, n),
                    artOnly?.getOrNull(up) ?: true, lineHeightPx, tapCol, tapY, fontSizePx)) {
                return up
            }
            val down = tapRow + d
            if (down < n && anchorsAt(rows[down], tops[down], rowHeightAt(down, tops, heights, n),
                    artOnly?.getOrNull(down) ?: true, lineHeightPx, tapCol, tapY, fontSizePx)) {
                return down
            }
            d++
        }
        return -1
    }

    /**
     * 第 [index] 行的行高：优先用调用方给的 `heights`（`RowLayout.Plan.heights`，与绘制侧逐值同源），
     * 否则用行顶差分近似 —— 末行没有下一行可减，返回 0（= 行高未知，`artRowScale` 会退回 k 1）。
     */
    private fun rowHeightAt(index: Int, tops: FloatArray, heights: FloatArray?, n: Int): Float {
        heights?.getOrNull(index)?.let { if (it > 0f) return it }
        if (index + 1 >= n) return 0f
        val h = tops[index + 1] - tops[index]
        return if (h > 0f) h else 0f
    }

    /** 锚行该点的判定：列区间与纵向像素盒都盖住点击点（闸与出处见类文档）。 */
    private fun anchorsAt(
        row: DisplayRow,
        rowTop: Float,
        rowHeight: Float,
        pureArtRow: Boolean,
        lineHeightPx: Float,
        tapCol: Int,
        tapY: Float,
        fontSizePx: Int,
    ): Boolean {
        var col = row.indentCells
        val runs = row.runs
        var i = 0
        while (i < runs.size) {
            val run = runs[i]
            val graphic = run.graphic
            val width = if (graphic != null) graphic.widthCells else Grid.cells(run.text)
            if (graphic != null &&
                width > 0 &&
                run.buttonValue != null &&
                run.buttonId != ID_UNKNOWN &&
                (run.kind == RunKind.IMAGE || run.kind == RunKind.SHAPE) &&
                tapCol in col until col + width
            ) {
                val box = GraphicGeometry.escapedLineBox(graphic, fontSizePx)
                // B1：与绘制侧同一个 k（[GraphicGeometry.nodeRowScale]），只缩高度。
                val k = GraphicGeometry.nodeRowScale(graphic, pureArtRow, rowHeight, lineHeightPx)
                val hitBox = GraphicGeometry.scaledVerticalBox(box[0], box[1], k)
                if (tapY >= rowTop + hitBox[0] && tapY <= rowTop + hitBox[1]) {
                    return true
                }
            }
            col += width
            i++
        }
        return false
    }

    private const val ID_UNKNOWN = -1
}
