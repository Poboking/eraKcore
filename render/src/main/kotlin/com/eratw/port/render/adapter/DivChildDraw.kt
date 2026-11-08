package com.eratw.port.render.adapter

import com.eratw.port.render.GlyphKey
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.glyph.CellMetrics

/**
 * div **子行**的绘制准备（CONTRACT-DIV-WIRE W1/W2）—— 把 `DivOverlayPlanner.Block.children`
 * 的行原文折成两头绘制器共用的 [DrawCmd] 清单：headless 栅格（`ScreenComposer`）与
 * 真机画布（`CanvasConsoleView`）吃同一份产物，与正文同一套字形路径
 * （`DivOverlayPlanner` 类文档「本类的输出」的约定落点）。
 *
 * ## 原版口径
 *
 * 子行是块内的 `ConsoleDisplayLine`（ConsoleDivPart.Children，CDP :38-40），`DrawTo`
 * 在内容盒 clip 内自盒顶逐行落点（CDP :196-203，行高步进）。移植侧每个子行原文经
 * [HtmlRunFlatten] 展平（与正文同一套宽容解析 —— 子行里有样式/按钮/图形标签时同一条
 * 路径消费），再按 `ConsoleDisplayLine.SetAlignment` 的 customWidth 口径算对齐偏移：
 * `HtmlManager.cs:698` 把子行交给 `SetAlignment(state.Alignment)`、`:702-704` 传的
 * customWidth 是 SubDivisionWidth（HM :618-635）—— CENTER/RIGHT 的偏移按**块宽**算，
 * 不是按 DrawableWidth（`ConsoleDisplayLine.cs:80-83`）。
 *
 * **子行按 SubDivisionWidth 折行**（2026-11-08 翻案，真机神签诗文被内容盒裁切的根因）：
 * `<div>` 开标签置 `StartingSubDivision`（HM :1369）后，主循环 ：606-648 由外层帧
 * （`parent == null`）算好 SubDivisionWidth（HM :618-635，width − margin/border/padding
 * 左右四项）并**递归** `html2DisplayLine`（:636-642）——递归帧与外层共享同一字符流，
 * 解析的正是 div 的子内容；子内容里的 `</div>` 把递归帧的 `CurrentDivTag` 置空
 * （HM :1034）后，递归帧在 ：608-611 break 出主循环、走到 ：698 的
 * `subDiv: true, SubDivisionWidth` 分支 —— 即递归帧尾部**同样**经
 * `PrintStringBuffer.ButtonsToDisplayLines` 折行（PSB :196-266）：折行宽
 * `num = divWidth > 0 ? divWidth : Config.DrawableWidth`（PSB :205）、超宽判
 * `PointX + Width <= num`（:217）、字符级切开走 `getDivideIndex`（:224-240、:414-496，
 * 最长前缀宽 ≤ 剩余宽的二分；等宽网格下与逐码位累加同结果）。`Config.ButtonWrap`
 * 出厂 false（ConfigData.cs:83 的 `t: false`）⇒ PSB :222 判式恒进字符切分支。
 * 折出的每条子行 `SetAlignment(align, customWidth: SubDivisionWidth)`（HM :702-704），
 * `ConsoleDivPart.Lines` 就是**折行后的可视行数组**（HM :646）—— 旧裁定「子行不折行、
 * 超宽被 clip 裁掉」是对 HM :698 的误读（「不经打印缓冲」不成立），本波废弃。
 *
 * 本类把这条折行落成 [wrap]：一条逻辑子行 → 若干**可视行**（[Row]），切分点按
 * `Grid` 等宽格模型（与绘制推进的 `cellPixelWidth` 同一把尺）、`noBreak`（`<nobr>`）
 * 整段免折、图形段（img/shape）不可切 —— 超宽且行中时整段挪到下一行（PSB :236-240
 * 的 divideIndex==0 且 list2 非空 → 落行重排）、行首时放行整段（:236-239 的
 * `list2.Count <= 0` 支）。裁剪（内容盒 SetClip）仍归绘制侧，兜底极窄块。
 *
 * ## 绘制语义的边界（消费方须知）
 *
 * - 产出的 [DrawCmd.row] 恒 0（合成行号）：子行不在显示列表里、不参与脏区与命中
 *   —— 原版子行的按钮也不进 `displayLineList` 各行的 Buttons（命中扫描只走
 *   `EmueraConsole.cs:2358-2361` 的行列表），可点性随之缺席是**原版形状**。
 * - 图形段（子行里的 img/shape）照常带 [com.eratw.port.render.displaylist.InlineGraphic]
 *   负载，绘制侧的图形路径可用；headless 占位栅格对图形段本来就不铺像素
 *   （`ScreenComposer.drawRun` 只吃字形），子行同样如此。
 * - TAB 展开、代理对合成码位与 `DisplayList.prepare` 同一套（[Grid.expandTabs] +
 *   逐码位一个 [GlyphKey]），保证子行与正文的字形分桶一致。
 */
object DivChildDraw {

    /**
     * 一条**可视行**（折行后）的绘制产物：run 清单 + 命令清单 + 对齐偏移（半角格，可为负）。
     *
     * @property runs 本可视行的 run 切片 —— 命中侧（`ButtonLayer.collectDivHits`）拿它走
     *   [buttonSpans]，与绘制侧的 [cmds] 同源（同一次 [wrap] 的产物，「画在哪」与「点哪里」不分叉）。
     * @property cmds 本可视行的绘制命令（[toCmds] 产物；列游标从 0 起）。
     * @property alignOffsetCells 对齐偏移（半角格）：`SetAlignment` 的 `num3`
     *   （ConsoleDisplayLine.cs:86-90）按 customWidth=块宽折格的口径，LEFT 恒 0、
     *   CENTER/RIGHT 按本可视行占宽算（见类文档）。
     */
    data class Row(
        val runs: List<TextRun>,
        val cmds: List<DrawCmd>,
        val alignOffsetCells: Int,
    )

    /**
     * 展平一条子行原文并按块宽折成若干可视行（[Row]）。
     *
     * 折行语义 = `ButtonsToDisplayLines(..., subDiv: true, divWidth: SubDivisionWidth)`
     * 的子行档（PSB :196-266，逐支对应见类文档）：
     * - `noBreak`（子行 html 里的 `<nobr>`，HM :698 传给折行器的 `nobr`）或块宽非正
     *   （[subDivisionWidthPx] <= 0 / 折不出 1 格）→ 整段单行（超宽交给绘制侧内容盒 clip）。
     *   块宽非正的原版会回落 `Config.DrawableWidth` 折行（PSB :205），但该档下内容盒宽
     *   只剩 `|width| − margin/border/padding`（通常 ≤2px，见 CDP :103-134 的收缩），
     *   折与不折的可见结果都是那几像素的裁窗，故按不折处理；
     * - 文本 run 超宽 → 按码位切**最长前缀**进当前行（getDivideIndex 的等宽格档），
     *   余部作为新 run 重新参与下一轮判定（可能一行放不下、连折多行）；
     * - 图形 run（img/shape）不可切：行中时整段挪到下一行（PSB :236-240 的
     *   divideIndex==0 且 list2 非空 → 落行重排）、行首时放行整段（:236-239 的
     *   `list2.Count <= 0` 支）；
     * - 每条可视行的对齐偏移独立按本行占宽算（原版折行发生在 SetAlignment 之前，
     *   `SetAlignment` 逐条作用于折后的每一行，HM :698-710）。
     *
     * @param flattener 专属的 HTML 展平器（消费方自持一个，别与正文展平器共用实例
     *   —— `HtmlRunFlatten` 有逐串状态，虽然每次 [HtmlRunFlatten.flatten] 都复位，
     *   分开持有让两处的调用序互不干扰）
     * @param html 逻辑子行原文（`DisplayDivBlock.children` 的行原文）
     * @param subDivisionWidthPx 子层折行/对齐宽（设备像素；`DivOverlayPlanner` 已按
     *   `scriptPixels` 折算 —— 与绘制推进同一把尺）
     * @param metrics 像素 → 半角格换算（块宽折格用；与绘制侧同一份）
     */
    fun wrap(
        flattener: HtmlRunFlatten,
        html: String,
        subDivisionWidthPx: Int,
        metrics: CellMetrics,
    ): List<Row> {
        val line = flattenLineOf(flattener, html)
        val widthCells = if (subDivisionWidthPx <= 0) 0 else metrics.pixelsToCells(subDivisionWidthPx)
        if (line.noBreak || widthCells <= 0) {
            return listOf(rowOf(line.runs, line.align, widthCells))
        }
        val work = ArrayDeque(line.runs)
        val rows = ArrayList<Row>(2)
        var cur = ArrayList<TextRun>()
        var curCells = 0
        while (work.isNotEmpty()) {
            val run = work.first()
            val w = cellsOf(run)
            if (curCells + w <= widthCells) {
                cur.add(work.removeFirst())
                curCells += w
                continue
            }
            if (run.graphic == null) {
                // 文本段：切最长前缀进当前行（PSB :224-240 的 getDivideIndex>0 支）。
                val expanded = Grid.expandTabs(run.text)
                val fit = longestPrefixFit(expanded, widthCells - curCells)
                if (fit > 0) {
                    cur.add(run.copy(text = expanded.substring(0, fit)))
                    rows.add(rowOf(cur, line.align, widthCells))
                    cur = ArrayList()
                    curCells = 0
                    work[0] = run.copy(text = expanded.substring(fit))
                    continue
                }
                if (cur.isNotEmpty()) {
                    // 一格都切不进且本行已有内容 → 整段挪到下一行（PSB :236-240 落行重排）。
                    rows.add(rowOf(cur, line.align, widthCells))
                    cur = ArrayList()
                    curCells = 0
                    continue
                }
            } else if (cur.isNotEmpty()) {
                // 图形段不可切：行中 → 挪行（同上）；行首 → 走放行支。
                rows.add(rowOf(cur, line.align, widthCells))
                cur = ArrayList()
                curCells = 0
                continue
            }
            // 行首放行整段（PSB :236-239 的 `divideIndex != 0 || list2.Count <= 0` 支）。
            cur.add(work.removeFirst())
            curCells += w
        }
        if (cur.isNotEmpty()) rows.add(rowOf(cur, line.align, widthCells))
        return rows
    }

    /** 一批 run 的占宽和（格）：图形段取 `widthCells`（负 space 保号回退）、文本段走 TAB 展开宽度。 */
    private fun cellsOf(run: TextRun): Int = run.graphic?.widthCells ?: Grid.cells(run.text)

    /** 收束一条可视行：runs + 命令化 + 对齐偏移（alignmentShift 的 customWidth=块宽口径）。 */
    private fun rowOf(runs: List<TextRun>, align: com.eratw.port.core.host.Alignment, widthCells: Int): Row {
        var cells = 0
        for (run in runs) cells += cellsOf(run)
        return Row(
            runs = runs,
            cmds = toCmds(runs),
            alignOffsetCells = ConsoleAdapter.alignmentShift(align, cells, widthCells),
        )
    }

    /**
     * 不超 [availCells] 格的最长前缀（UTF-16 长度；0 = 一格都装不下）。
     * 与 `DisplayList.visibleSuffix` 同一套码位口径：代理对成对计宽、不拆对。
     */
    private fun longestPrefixFit(text: String, availCells: Int): Int {
        if (availCells <= 0) return 0
        var col = 0
        var i = 0
        while (i < text.length) {
            val step = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            var w = Grid.cellWidth(text[i])
            if (step == 2) w += Grid.cellWidth(text[i + 1])
            if (col + w > availCells) break
            col += w
            i += step
        }
        return i
    }

    /**
     * 子行原文 → 展平后的 [RenderLine]（[wrap] 的唯一入口）。
     *
     * 初始样式取展平器的缺省态：原版递归子解析新建 `HtmlAnalzeState`，`GetSS` 缺省取
     * userStyle（HtmlManager.cs:536 的「每串 HTML 一份状态」）；子行里没有 `<p align>`/
     * 样式标签时一切走缺省。
     */
    private fun flattenLineOf(flattener: HtmlRunFlatten, html: String): RenderLine {
        val seed = TextRun(
            text = html,
            fore = 0xC0C0C0,
            back = null,
            styleMask = 0,
            fontName = "ＭＳ ゴシック",
            kind = RunKind.HTML,
            htmlOption = false,
        )
        return flattener.flattenLine(
            RenderLine(runs = listOf(seed), isLogicalLine = true, isLineEnd = true),
        )
    }

    /**
     * 子行里一条**可点源按钮**的命中区间：格区间 + 纵带（相对子行行条上沿）+ 值/文案。
     *
     * 入表闸与 `button/ButtonLayer.attach` 同一条（该函数的「入表闸有两支」）：
     * 文本按钮段要求 `kind == BUTTON` 且带值、列宽大于 0；图形段要求它属于某条源按钮区间
     * （`buttonId != -1`，由 `HtmlRunFlatten` 在 `<button>` 包 `<img>` 时落上）。
     * 合并口径也按**源按钮身份**（同 `buttonId` + 列相接 + 值相同）—— 与
     * `ConsoleButtonString.cs:292-303` 的「一条按钮含文本片与图段」同形。
     *
     * 纵带出处：文本节点 `Top = 0`／`Bottom = Config.FontSize`
     * （`AConsoleDisplayNode.cs:29-31`）；图段取 `ypos + 高`（`ConsoleImagePart.cs:31,33,81-93`
     * 的 `top`/`bottom`）；色块段 `Top = min(0, rect.Y)`／`Bottom = max(FontSize, rect.Y + rect.H)`
     * （`ConsoleRectangleShapePart.cs:34-35`）。这几条正是 `ConsoleDivPart.TestChildHitbox`
     * 的逐节点判据（CDP :159）里用到的 `Top`/`Bottom`。
     *
     * @property colStart 可视行内起始半角格（**不含**对齐偏移 —— 偏移在
     *   [Row.alignOffsetCells] 里另给，两块消费方各自决定是否消费）
     * @property colEnd 结束半角格（右开）
     * @property topPx 纵带上沿（相对子行行条上沿，可为负 = 画到行上方）
     * @property bottomPx 纵带下沿（相对子行行条上沿）
     */
    data class ChildButtonSpan(
        val colStart: Int,
        val colEnd: Int,
        val value: Any,
        val label: String,
        val buttonId: Int,
        val topPx: Int,
        val bottomPx: Int,
        val tooltip: String?,
    )

    /**
     * 展平产物 → 可点源按钮区间（列游标与 [toCmds] 同一套：图形段读 [InlineGraphic.widthCells]、
     * 文本段先展开 TAB，整段出左界的裁前导码点与命令化一致）。
     *
     * @param runs 一条可视行的 run 切片（`DivOverlayPlanner.ChildRow.runs`）
     * @param metrics 取字号做文本节点的纵带（`AConsoleDisplayNode.cs:31` 的 `Config.FontSize`）
     */
    fun buttonSpans(runs: List<TextRun>, metrics: CellMetrics): List<ChildButtonSpan> {
        if (runs.isEmpty()) return emptyList()
        val out = ArrayList<ChildButtonSpan>(2)
        val fontPx = metrics.fontPixelSize
        var col = 0
        for (run in runs) {
            val graphic = run.graphic
            val cells: Int
            val visibleCells: Int
            if (graphic != null) {
                cells = graphic.widthCells
                if (cells < 0 && graphic is com.eratw.port.render.displaylist.InlineGraphic.Space) {
                    col += cells
                    continue
                }
                if (cells <= 0) continue
                visibleCells = cells
            } else {
                val text = Grid.expandTabs(run.text)
                cells = Grid.cellsExpanded(text)
                if (cells <= 0) continue
                val visible = if (col < 0) visibleSuffix(text, col) else text
                visibleCells = Grid.cellsExpanded(visible)
                if (visibleCells <= 0 && col >= 0) continue
            }
            val end = col + cells
            if (end <= 0) {
                col = end
                continue
            }
            val startCol = maxOf(col, 0)
            val endCol = if (graphic != null) end else startCol + visibleCells
            val value = run.buttonValue
            val graphicInSourceButton = graphic != null &&
                (run.kind == RunKind.IMAGE || run.kind == RunKind.SHAPE) &&
                run.buttonId != ID_UNKNOWN
            if (value != null && (run.kind == RunKind.BUTTON || graphicInSourceButton)) {
                val band = bandOf(run, fontPx)
                val last = out.lastOrNull()
                if (last != null && last.colEnd == startCol && last.value == value &&
                    last.buttonId == run.buttonId
                ) {
                    out[out.size - 1] = ChildButtonSpan(
                        colStart = last.colStart,
                        colEnd = endCol,
                        value = value,
                        label = last.label + run.text,
                        buttonId = last.buttonId,
                        topPx = minOf(last.topPx, band[0]),
                        bottomPx = maxOf(last.bottomPx, band[1]),
                        tooltip = last.tooltip ?: run.tooltip,
                    )
                } else {
                    out.add(
                        ChildButtonSpan(
                            colStart = startCol,
                            colEnd = endCol,
                            value = value,
                            label = run.text,
                            buttonId = run.buttonId,
                            topPx = band[0],
                            bottomPx = band[1],
                            tooltip = run.tooltip,
                        ),
                    )
                }
            }
            col = end
        }
        return out
    }

    /** 一条 run 的纵带 `[top, bottom)`（相对行条上沿；出处见 [ChildButtonSpan] 的 KDoc）。 */
    private fun bandOf(run: TextRun, fontPx: Int): IntArray = when (val g = run.graphic) {
        null -> intArrayOf(0, fontPx)
        is com.eratw.port.render.displaylist.InlineGraphic.Image -> intArrayOf(g.yOffsetPx, g.yOffsetPx + g.heightPx)
        is com.eratw.port.render.displaylist.InlineGraphic.Rect ->
            intArrayOf(minOf(0, g.yOffsetPx), maxOf(fontPx, g.yOffsetPx + g.heightPx))
        is com.eratw.port.render.displaylist.InlineGraphic.Space -> intArrayOf(0, fontPx)
    }

    /** [TextRun.buttonId] 的「身份未知」取值（与 `button/ButtonLayer` 的 `ID_UNKNOWN` 同值）。 */
    private const val ID_UNKNOWN: Int = -1

    /**
     * 展平产物 → 合成命令（row 恒 0；列游标从 0 起）。左界口径与
     * `displaylist/DisplayList.prepare` 同一套：整段出左界的不产命令、跨界的裁掉
     * 前导码点（子行的对齐偏移可以是负的 —— RIGHT 对齐把文本推出内容盒左沿时，
     * 原版由 SetClip 裁掉，这里裁命令列，像素裁剪仍归绘制侧的内容盒）。
     * 右界**不裁**：内容盒右沿的裁剪是像素级 clip，不是列裁剪（超宽子行溢出可见，
     * 被内容盒 clip 截住，ConsoleDivPart.cs:195）。
     */
    private fun toCmds(runs: List<TextRun>): List<DrawCmd> {
        if (runs.isEmpty()) return emptyList()
        val out = ArrayList<DrawCmd>(runs.size)
        var col = 0
        for (run in runs) {
            val graphic = run.graphic
            if (graphic != null) {
                val cells = graphic.widthCells
                if (cells < 0 && graphic is com.eratw.port.render.displaylist.InlineGraphic.Space) {
                    col += cells
                    continue
                }
                if (cells <= 0) continue
                val end = col + cells
                if (end <= 0) {
                    col = end
                    continue
                }
                out.add(
                    DrawCmd(
                        row = 0,
                        colStart = maxOf(col, 0),
                        colEnd = end,
                        fore = run.fore,
                        back = run.back,
                        styleMask = run.styleMask,
                        kind = run.kind,
                        glyphs = emptyList(),
                        graphic = graphic,
                        focusFore = run.focusFore,
                    ),
                )
                col = end
                continue
            }
            val text = Grid.expandTabs(run.text)
            val cells = Grid.cellsExpanded(text)
            if (cells <= 0) continue
            val end = col + cells
            if (end <= 0) {
                col = end
                continue
            }
            val visible = if (col < 0) visibleSuffix(text, col) else text
            val glyphs = glyphKeys(run, visible)
            if (glyphs.isEmpty()) {
                col = end
                continue
            }
            out.add(
                DrawCmd(
                    row = 0,
                    colStart = maxOf(col, 0),
                    colEnd = maxOf(col, 0) + Grid.cellsExpanded(visible),
                    fore = run.fore,
                    back = run.back,
                    styleMask = run.styleMask,
                    kind = run.kind,
                    glyphs = glyphs,
                    focusFore = run.focusFore,
                ),
            )
            col = end
        }
        return out
    }

    /** 裁掉「整码点都落在左界之外」的前缀（跨界码点保留）—— `DisplayList.visibleSuffix` 同式。 */
    private fun visibleSuffix(text: String, startCol: Int): String {
        if (startCol >= 0) return text
        var index = 0
        var col = startCol
        while (index < text.length) {
            val step = if (Character.isHighSurrogate(text[index]) && index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) 2 else 1
            var cells = Grid.cellWidth(text[index])
            if (step == 2) cells += Grid.cellWidth(text[index + 1])
            if (col + cells > 0) break
            col += cells
            index += step
        }
        return if (index == 0) text else text.substring(index)
    }

    /** 字形引用序列 —— 与 `displaylist/DisplayList.glyphKeys` 同一套（逐码位一个、代理对合成）。 */
    private fun glyphKeys(run: TextRun, expandedText: String): List<GlyphKey> {
        val out = ArrayList<GlyphKey>(expandedText.length)
        var i = 0
        while (i < expandedText.length) {
            val ch = expandedText[i]
            if (Character.isHighSurrogate(ch) && i + 1 < expandedText.length && Character.isLowSurrogate(expandedText[i + 1])) {
                out.add(GlyphKey(run.fontName, run.styleMask, Character.toCodePoint(ch, expandedText[i + 1])))
                i += 2
            } else {
                out.add(GlyphKey(run.fontName, run.styleMask, ch.code))
                i++
            }
        }
        return out
    }
}
