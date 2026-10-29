package com.eratw.port.render.html

import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun

/**
 * 行对齐，对应原版 `DisplayLineAlignment`（DisplayLineAlignment.cs:3-8）。
 * 由 `<p align='left|center|right'>` 设定（HtmlManager.cs:1129-1144 的取值分派）。
 */
enum class MiniHtmlAlign { LEFT, CENTER, RIGHT }

/**
 * 一条显示段：文本段或图片占位段。两者都携带映射到 [TextRun] 的结果；
 * 图片段额外携带 `img` 属性（渲染取图归 m-sprite 路）。
 *
 * @property run 交给显示列表的段（图片段 `text` 恒为空串、kind 为 [RunKind.HTML]）
 */
sealed class HtmlSegment(val run: TextRun) {

    /** 样式快照的纯文本段（原版 `ConsoleStyledString`，入缓冲即固定样式，
     *  ConsoleStyledString.cs:21-40；对应 Kernel 注释「样式在入缓冲时快照」）。 */
    class Text(run: TextRun) : HtmlSegment(run)

    /**
     * `img` 占位段（原版 `ConsoleImagePart`，构造点 HtmlManager.cs:1221）。
     *
     * 属性名与实参顺序按原版抄：`src`/`srcb`/`srcm` 三个名 + `height`/`width`/`ypos`
     * 三个 `MixedNum`（`ConsoleImagePart.cs:37` 的形参序是 height 在前、width 在后，
     * 与标签名序不同，取值分派在 `HtmlManager.cs:1199-1213`）。
     *
     * @property src 必需属性 `src`（缺失抛 NotSetAttribute，HtmlManager.cs:1217-1220）
     * @property srcB 可选 `srcb`（换色变体，:1183-1189，重复即抛 :1187）
     * @property srcM 可选 `srcm`（混合变体，:1191-1197，重复即抛 :1195）
     * @property width 可选 `width`（MixedNum 数值，`:1203-1206`；`px` 后缀见 [widthIsPx]，
     *   Utils.cs:342-362）
     * @property height 可选 `height`（`:1199-1202`）
     * @property yPos 可选 `ypos`（`:1209-1213`）
     * @property widthIsPx `width` 带 px 后缀（原版 isPx 语义，Utils.cs:347-354）
     * @property heightIsPx 同上，作用于 `height`
     *
     * **数值怎么变成像素**（上层取图时用得上，`ConsoleImagePart.cs:66-81`）：
     * `null` 或 0 都按「未给」处理 —— 高落到 `Config.FontSize`（`:66`），
     * 宽落到按立绘宽高比推出的等比值（`:67-71`）；给了值则 `isPx` 为真取原值、
     * 为假按字号百分比 `FontSize * num / 100`（宽 `:72-79`、高同式在 `:66`、
     * ypos 同式在 `:81`）。
     *
     * TODO(verify) 口径缺口：[yPos] 丢掉了 `px` 标志 —— 解析侧
     * `MiniHtml.kt:402-405` 只取 `mixedNum(value).first`，而原版 ypos 与宽高分同两路
     * （`ConsoleImagePart.cs:81` 的 `isPx ? num : num * FontSize / 100`）。
     * 卡点：补齐 `yPosIsPx` 要动 `MiniHtml.kt`（本 lane 只独占本文件），
     * 已写进交付报告的「需组长接线」；在此之前本字段只能当**未归一原始值**用，
     * 不得直接当像素偏移参与摆位。
     *
     * TODO(verify) 绑定时机：原版在**建段时**就把名字解析成 sprite 引用
     * （`ConsoleImagePart.cs:60` 的 `AppContents.GetSprite(ResourceName)`，`srcb`/`srcm`
     * 同法在 `:96/:100`；查不到就把段文本换成 alt 串 `:61-64`），后续 `SPRITEDISPOSE`
     * 只让引用变成死条目、段的绑定不变。本模型只存名字，解析发生在绘制侧
     * （`SpriteTable.find`），于是「先建段后释放」与「先释放后建段」在原版不等价、
     * 在本层等价。卡点：本 lane 无 `<img>` + `SPRITEDISPOSE` 交错的可比基准读数
     * （基准 SPRITE 分节 `pc-golden-root.txt:660-673` 走 GVIEW 的分立路径，不经 HTML）。
     */
    class Image(
        val src: String,
        val srcB: String?,
        val srcM: String?,
        val width: Int?,
        val height: Int?,
        val yPos: Int?,
        val widthIsPx: Boolean,
        val heightIsPx: Boolean,
        run: TextRun,
    ) : HtmlSegment(run)
}

/**
 * 一个由 `<br>` / 裸换行切出的逻辑行（原版在 `FlagBr` 处 `cssToButton + null` 断行，
 * HtmlManager.cs:650-658；null 分隔符在 PrintStringBuffer.cs:207-215 逐条成行）。
 */
data class HtmlLine(val segments: List<HtmlSegment>) {

    /** 本行的纯文本（图片不计，与 [MiniHtml.plainLength] 同口径）。 */
    val plainText: String
        get() = segments.filterIsInstance<HtmlSegment.Text>().joinToString("") { it.run.text }

    /**
     * 本行半角格宽度（图片段记 0：core 的 `HTML_STRINGLEN` 同款近似，见 [MiniHtml.plainLength]）。
     *
     * 与原版的两处已知分歧（都因为原版给图片段算的是**像素宽**、不是格宽）：
     * sprite 存在时段宽 = `ConsoleImagePart.cs:67-80` 那套换算出来的 `Width`
     * （`SetWidth` 对此段不做二次测量，`ConsoleImagePart.cs:104-115` 的两个分支都跳过它）；
     * sprite 不存在时段文本被换成 alt 串（`:61-64`）、宽度按整串实测（`:110-114`）。
     * 本层两者一律记 0，故带图的行在 CENTER/RIGHT 对齐时会比原版偏右/偏左一截。
     */
    val cells: Int
        get() = segments.filterIsInstance<HtmlSegment.Text>().sumOf { Grid.cells(it.run.text) }

    /** 本行的 [TextRun] 序列。 */
    val runs: List<TextRun>
        get() = segments.map { it.run }
}

/**
 * `MiniHtml.parse` 的产物：行结构 + 行级属性（对齐、`nobr`）。
 *
 * @property lines 逻辑行序列（`<br>` 断行；行尾空段会被原版丢弃，PrintStringBuffer.cs:258-261）
 * @property align `<p align>` 的对齐；缺省 LEFT（DisplayLineAlignment 枚举默认，HtmlManager.cs:108）
 * @property nobr 是否带 `<nobr>`（true 时上层不得折行，HtmlManager.cs:698 把 FlagNobr 传给
 *   `ButtonsToDisplayLines(nobr)`，PrintStringBuffer.cs:217 直接放行不折）
 */
data class MiniHtmlDoc(
    val lines: List<HtmlLine>,
    val align: MiniHtmlAlign = MiniHtmlAlign.LEFT,
    val nobr: Boolean = false,
) {

    /** 展平成 [TextRun] 序列：行间插入换行哨兵段（`text` 为 "\n"、kind 为 [RunKind.HTML]）。 */
    val runs: List<TextRun>
        get() {
            val out = ArrayList<TextRun>()
            lines.forEachIndexed { i, line ->
                if (i > 0) out.add(brSentinel(line))
                out.addAll(line.runs)
            }
            return out
        }

    /**
     * 对齐落列：本行在 `clientWidthCells` 宽的可视区里应起始的第几列（半角格）。
     *
     * 复刻 `ConsoleDisplayLine.SetAlignment`（ConsoleDisplayLine.cs:50-91）的三种取值，
     * 但把像素换算成网格列（本模块的宽度单位是半角格，见 Kernel `Grid` 注释）：
     * - LEFT：恒为 0（:72-77 —— 原版对逻辑行是「早退、一个字都不挪」（`:73-76`），
     *   只有折出来的后续行才把起点压到 0（`:77` 再经 `:86-89` 平移）；本层段起点恒 0，
     *   两条路同值）；
     * - CENTER：`clientWidth/2 - cells/2`（:79-81，算式在 `:80`，整数除法与 C# 一致）；
     * - RIGHT：`clientWidth - cells`（:82-84，算式在 `:83`）。
     * 原版还会减去按钮的 `PointX` 起点（:68、:86-90）；本 lane 无按钮段，起点恒 0，
     * 按钮列锁定归 m-button 路。行宽超出可视区时结果为负（原版同样照画溢出，不做钳制）。
     *
     * 传参口径要求（原版的分母不是窗口全宽）：`:80`/`:83` 用的是
     * `customWidth > 0 ? customWidth : Config.DrawableWidth`，而 `Config.DrawableWidth`
     * 已经是「窗口宽减 shift」（Config.cs:466 的 `WindowX - DrawingParam_ShapePositionShift`，
     * shift 同文件 `:461-465`），`customWidth` 则是 div 的子宽
     * （`HtmlManager.cs:704` 传 `SubDivisionWidth`、`:708` 不传）。
     * 所以 [clientWidthCells] 必须由上层按同一口径给（可绘宽、不是 1800 的窗口宽），
     * 否则 CENTER/RIGHT 整体右移 shift 那几像素（18 号字下 shift=3，不足一格；`Math.Max(2,
     * FontSize/6)` 在字号 12 及以下恒 2，其后随字号线性增长，字号 54 以上时 shift 已达 9
     * 即吃掉一整个半角格）。
     */
    fun alignColumn(cells: Int, clientWidthCells: Int): Int = when (align) {
        MiniHtmlAlign.LEFT -> 0
        MiniHtmlAlign.CENTER -> clientWidthCells / 2 - cells / 2
        MiniHtmlAlign.RIGHT -> clientWidthCells - cells
    }

    /** 按行号取对齐列起点。 */
    fun alignStartColumn(lineIndex: Int, clientWidthCells: Int): Int =
        alignColumn(lines[lineIndex].cells, clientWidthCells)

    private fun brSentinel(line: HtmlLine): TextRun {
        val style = line.runs.firstOrNull()
        return if (style == null) {
            TextRun(BR_MARK, MiniHtml.FORE_DEFAULT, MiniHtml.BACK_DEFAULT, 0, MiniHtml.FONT_DEFAULT, RunKind.HTML)
        } else {
            TextRun(BR_MARK, style.fore, style.back, style.styleMask, style.fontName, RunKind.HTML)
        }
    }

    companion object {
        /** 换行哨兵文本：展平序列里唯一的 [RunKind.HTML] 非空文本段就是它。 */
        const val BR_MARK: String = "\n"
    }
}
