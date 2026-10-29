package com.eratw.port.render.grid

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun

/**
 * 折行与列布局 —— 把一条逻辑行 [RenderLine] 折成若干 [DisplayRow]。
 *
 * ## 单位与口径
 *
 * 宽度一律用 [Grid] 的整数网格（半角 1 格 / 全角 2 格，口径出处
 * `EmueraConsole.cs:3255` 的 `Encoding.GetEncoding("Shift-JIS").GetByteCount`），
 * `clientWidth` 是一行可用的半角格数。原版按像素折行（`StringMeasure.cs:42-72`），
 * 本实现按 ADR-001 的重设计区改成整数网格，两者只在「不拆字符 + 取最大可容纳前缀」
 * 这一层语义上等价。
 *
 * ## 算法来源（逐条对应）
 *
 * 本类是 `PrintStringBuffer.ButtonsToDisplayLines`（`PrintStringBuffer.cs:196-266`）的直译：
 * - 「段」（内部 [FoldSegment]）对应原版 `ConsoleButtonString`：若干样式段
 *   （`ConsoleStyledString`）组成的一个整体。按钮段就是一条 [RunKind.BUTTON] 的 run 独自成段，
 *   相邻非按钮 run 合并成一段 —— 对应 `PrintStringBuffer.cs:280-292` 的 `fromCssToButton`
 *   把待 flush 的样式段拼成一个 `ConsoleButtonString`；
 * - 段内位置用 `pointX`（半角格）表达，初回布局对应 `setWidthToButtonList`
 *   （`PrintStringBuffer.cs:391-412`），断行后从当前段起整体重排
 *   （`PrintStringBuffer.cs:244-249`）；
 * - 放不下时先求「当前行还放得下的最大前缀字符数」（`getDivideIndex`，
 *   `PrintStringBuffer.cs:414-496`），结果 `> 0` 就地拆段（`DivideAt`，
 *   `ConsoleButtonString.cs:187-255`）；拆出的后半继承 `IsButton`/`Input`/`Inputs`
 *   （`ConsoleButtonString.cs:247-251`）—— 这就是「按钮跨行拆分后每行各成一段且值相同」；
 * - `divideIndex == 0`（连一个字符都放不下）且当前行为空时，整段原样入行并允许
 *   `cells` 超过 `clientWidth`（`PrintStringBuffer.cs:236-240`）：宁可行溢出也不把字符劈开。
 *   此时产出的 [DisplayRow.cells] 可以大于 `clientWidth`，下游（m-displaylist / m-button）
 *   必须按「溢出行做列裁剪」处理，不能假设 `cells` 恒小于等于 `clientWidth`；
 * - `firstLine` 只对逻辑行的首行成立（`PrintStringBuffer.cs:193,206`，断行后置 false 见
 *   `PrintStringBuffer.cs:212,243`；落到行上是 `ConsoleDisplayLine.cs:45`），其余行恒 false；
 *   本实现把首行取输入 `RenderLine.isLogicalLine`（原版此处恒传 true），便于上层表达
 *   「本行是 PRINTN 续接、不是逻辑行首」的情形；
 * - `isLineEnd` 默认 true（`ConsoleDisplayLine.cs:16,32`），只有**最后**一行被赋上逻辑行的
 *   `lineEnd`（`PrintStringBuffer.cs:183`），所以折行产生的中间行也是 `isLineEnd = true`，
 *   并入点只认逻辑行尾（消费侧 `EmueraConsole.cs:2947-2968`）；
 * - `noBr` 即原版 `nobr` 分支，一行不折（`PrintStringBuffer.cs:217`；
 *   调用侧 `FlushSingleLine` `PrintStringBuffer.cs:170-177`）。
 *
 * ## 空行
 *
 * 内容长度为零的输入返回**空列表**（`PrintStringBuffer.cs:198-201` 的
 * `buttonList.Count == 0 → Array.Empty`）。原版靠调用侧兜底：`PrintFlush` 在缓冲区为空时先
 * `Append(" ", Style)` 再 flush（`EmueraConsole.cs:3379-3382`），所以屏上的空行是
 * 「一个空格、cells 等于 1」而不是零段的行。适配层（CONSOLE-2）要复刻这条，别指望本类造空行。
 * 注意区分：`runs` 里有一条「空串 run」时 `buttonList.Count` 不为 0，仍会产出一行
 * （cells 为 0），这条也照原样保留。
 *
 * ## 已知取舍
 *
 * - `Config.ButtonWrap`、`Config.CompatiLinefeedAs1739` 的默认值都是 `false`
 *   （`ConfigData.cs:83`、`ConfigData.cs:102`），故默认路径下非按钮段与按钮段一视同仁地就地拆；
 *   两个开关留成构造参数，真机开放该设置时可直接复用（见 [fold] 的判定式）。
 * - 空段的宽度在原版 `CalcWidth` 里被写成哨兵值 `-1`（`ConsoleButtonString.cs:273-276`），
 *   它只用于「未测量 / 无效」判定，不是布局语义；整数网格里按 0 格处理，
 *   因此不会出现「后续段 `pointX` 被空段拉负 1 像素」的原版现象。
 *   TODO(verify)：像素模式下这 1 像素重叠要不要复刻，等真机截图验收再定（卡点：无像素度量）。
 * - 分割点按**码点**取值，代理对（`Character.charCount` 大于 1）不会被劈成两半：这一点比原版更严
 *   —— 原版按 UTF-16 索引 `Substring`（`ConsoleStyledString.cs:60-62`），理论上能把代理对拆坏，
 *   契约的「不得在字符中间拆」按码点落地。总格数仍与 [Grid.cells] 的逐字符累加一致。
 * - 增补平面（合法代理对）在本类的索引空间里占 **2 个 `Char`**、按 [Grid.cells] 记 **4 格**；
 *   核心层 `core/host/CellWidth.kt` 的 `of` 按**码位**记 **2 格** —— 这条粒度差是已登记的驻留差异，
 *   本波**不动**。理由是本类的索引空间与原版同域（`PrintStringBuffer.cs:453-496` 在 `text.Length`
 *   上二分、`:471,483` 取 `Slice(0, num4)`；`ConsoleStyledString.cs:54-73` 的 `DivideAt` 用
 *   Char 下标 `Substring`），宽度与索引必须同域 ⇒「段宽」（`FoldPiece.cells`）与前缀宽度
 *   （[pieceCellsAt] + [maxPrefixChars]）只能**一起**改成码位口径，只改一半会让折行判定与
 *   收行格数互相矛盾。边界、代价与三条理由在 `render/.../Kernel.kt` 的 `Grid` KDoc
 *   「增补平面（U+10000 以上）的口径边界」节，三层对照表与折行落点在新文件
 *   `render/src/test/kotlin/com/eratw/port/render/grid/AstralWidthTest.kt`。
 * - 拆完右半为空时原版会把 `null` 插回列表（`PrintStringBuffer.cs:232`），并在下一轮
 *   `:209-215` 据此多收一条**空行**，且随后断行的重排循环在 null 处止步
 *   （`:245` 的 `buttonList[j] != null` 条件）、让后续部件顶着旧 `pointX` 走一巡；
 *   本实现不造空行、溢出段照常收行、`from` 之后照常规重排。整数网格里纯文本段不可达
 *   （溢出段的 `divideIndex` 必严格小于段内字符总数），**可达路径只有图形段**：
 *   图形段在字符域记 0（见 [FoldPiece.charSpan]），「图形段 + 尾部文本」的拆分点会吃满
 *   段内全部字符、右半落空。差异限定在「null 后的旧 pointX 连锁」这一退化角落，登记于此。
 * - 不可拆部件（`ConsoleImagePart` / `ConsoleShapePart`，`CanDivide` 为 false，
 *   spec: `ConsoleImagePart.cs:35`、`ConsoleShapePart.cs:8-10`）自 M10-fold 波起支持：
 *   [FoldPiece.canDivide] 按 `skeleton.graphic` 判定，`divideIndex` 复刻
 *   `PrintStringBuffer.cs:425-441`「行首放不下且不可拆 ⇒ 跳过该部件、**不计进外层 `num`**、
 *   继续看下一个」的无 else 分支（ADR-005 原样保留，不修），候选部件不可拆时前缀贡献
 *   0 字符（`:455-458` 返回 -1、`:442-449` 只加正数）。
 *
 *   被跳过部件的宽度**仍进节点自己的 `PointX`**：`CalcPointX` 按全部部件宽度顺排
 *   （`ConsoleButtonString.cs:292-304`），而部件级前缀余量读的正是这个值
 *   （`PrintStringBuffer.cs:468` 的 `divWidth - consoleStyledString.PointX`），不是外层
 *   那个不含跳过宽度的 `num`。所以「图形在段首 + 后续文本」在窄屏上前缀恒 0、整段
 *   原样溢出入一行（`:236-240`），而不是按外层 `num` 把文本劈到下一行。图形段在中后段
 *   且放不下时**整段挪到下一行、只画一次**；段宽超过可绘制宽时同样走 `:236-240`
 *   （`divideIndex == 0` 且当前行为空 ⇒ 整段原样入行、`cells` 允许超出 `clientWidth`，
 *   由下游做列裁剪），与原版对超宽文本段的溢出处置同一条分支。
 *
 * @param clientWidth 一行可用的半角格数（黄金基准 `CLIENTWIDTH=1800` 是像素口径，
 *   `pc-golden-root.txt:689`，需由字形缓存换算成格数后传入，理由见 `Grid` 的 KDoc）
 * @param buttonWrap 对应 `Config.ButtonWrap`：true 时按钮段不中途折行，整段挪到次行
 * @param compatiLinefeedAs1739 对应 `Config.CompatiLinefeedAs1739`：true 时非按钮段也不折行
 *   （1739 及以前的行为），仅当行首为空时才硬放进行
 */
class LineFolder(
    val clientWidth: Int,
    private val buttonWrap: Boolean = DEFAULT_BUTTON_WRAP,
    private val compatiLinefeedAs1739: Boolean = DEFAULT_COMPATI_LINEFEED_AS_1739,
) {

    init {
        require(clientWidth >= 1) { "clientWidth 必须至少 1 半角格，实到 " + clientWidth }
    }

    /**
     * 折一条逻辑行。
     *
     * @param line 逻辑行：一段或多段样式 run，按钮以 [RunKind.BUTTON] 标记并带 `buttonValue`
     * @param noBr 对应原版 `nobr`（`FlushSingleLine` 用）：true 时一行不折，
     *   产出的单行 `cells` 可以超过 `clientWidth`
     * @return 折行后的显示行；输入无内容时为空列表
     */
    fun fold(line: RenderLine, noBr: Boolean = false): List<DisplayRow> {
        val segments = group(line.runs)
        if (segments.isEmpty()) {
            return emptyList()
        }
        relayout(segments, from = 0)

        val rows = ArrayList<DisplayRow>()
        val current = ArrayList<FoldSegment>()
        var firstLine = true
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            if (noBr || seg.pointX + seg.width <= clientWidth) {
                current.add(seg)
                i++
                continue
            }
            if (!buttonWrap || current.isEmpty() || (!seg.isButton && !compatiLinefeedAs1739)) {
                val div = divideIndex(seg)
                if (div > 0) {
                    val rest = seg.divideAt(div)
                    current.add(seg)
                    // 对应 C# 的 Insert(i + 1, 右半) 后紧跟一次 i++：下一轮要处理的正是右半，
                    // 它由随后的断行 + relayout 挪到新行行首（PrintStringBuffer.cs:232-234,242-250）。
                    // rest == null（原版把 null 插回 :232、下一轮 :209-215 据此收行）只在
                    // **图形段落在段尾、其字符游标域又被前缀吃满**时可达：本实现照旧不造那条
                    // 空行，溢出段随循环尾部的收行落进当前行（差异账见类 KDoc「已知取舍」）。
                    i++
                    if (rest != null) {
                        segments.add(i, rest)
                    }
                } else if (div != 0 || current.isEmpty()) {
                    current.add(seg)
                    i++
                    continue
                }
            }
            rows.add(rowOf(current, line, firstLine, last = false))
            firstLine = false
            relayout(segments, from = i)
        }
        if (current.isNotEmpty()) {
            rows.add(rowOf(current, line, firstLine, last = true))
        }
        return rows
    }

    /**
     * run 列表 → 段列表：按钮 run 独自成段，相邻非按钮 run 合并成一段。
     * TAB 在这里就展开成 8 空格（`StringMeasure.cs:57-62`），后续一律按展开后的文本度量，
     * 落到 [DisplayRow] 的 run 文本也是展开后的（整数网格下游不再处理制表位）。
     */
    private fun group(runs: List<TextRun>): ArrayList<FoldSegment> {
        val out = ArrayList<FoldSegment>()
        for (run in runs) {
            val piece = FoldPiece(skeleton = run.copy(text = ""), text = Grid.expandTabs(run.text))
            val isButton = run.kind == RunKind.BUTTON
            val last = if (out.isEmpty()) null else out[out.size - 1]
            if (isButton || last == null || last.isButton) {
                out.add(FoldSegment(isButton = isButton, pieces = ArrayList(listOf(piece))))
            } else {
                last.pieces.add(piece)
                // 段宽必须随合并同步增长：FoldSegment.width 的构造期求和只覆盖首件，
                // 不补这一步则多件文本段的 width 恒等于第一件，折行判定（fold 主循环
                // 的 `seg.pointX + seg.width <= clientWidth`）会把整段误判为「放得下」。
                last.width += piece.cells
            }
        }
        return out
    }

    /**
     * 本段在当前行上「还放得下的字符数」（`getDivideIndex`，`PrintStringBuffer.cs:414-451`）。
     *
     * 直译口径：先整块累加放得下的部件，遇到第一个放不下的部件就在这个部件里取最大前缀
     * （`PrintStringBuffer.cs:453-496` 是二分）。整数网格下前缀宽度单调，二分的结论与
     * 「线性扫到放不下为止」逐字符相同，故这里用线性扫描（见 [maxPrefixChars]）。
     *
     * 两个列游标必须分开记：外层拟合判定用 C# 的 `num`（本函数的 `x`，只累计**放得下**的
     * 部件，被跳过的不可拆部件不占它，`PrintStringBuffer.cs:435-440` 只在 else 分支里
     * `num += Width`）；候选件的前缀余量用**它自己的 `PointX`**（本函数的 `layout` 在候选处
     * 的快照），那个值按全部部件宽度顺排（`ConsoleButtonString.cs:292-304`），含被跳过的
     * 图形段（`PrintStringBuffer.cs:468`）。屏宽 20、段 = [48 格图形, 30 字] 时外层跳过图形
     * （`x` 仍 0），文本候选的余量却是 `20 - 48 = -28` ⇒ 前缀 0 ⇒ 整段走 `:236-240`
     * 溢出成一行；若拿外层 `x` 求余量就会多劈一行。
     */
    private fun divideIndex(seg: FoldSegment): Int {
        var x = seg.pointX
        var layout = seg.pointX
        var chars = 0
        var fitted = 0
        var candidate: FoldPiece? = null
        var candidateX = 0
        for (p in seg.pieces) {
            if (x + p.cells > clientWidth) {
                if (fitted != 0 || p.canDivide) {
                    candidate = p
                    candidateX = layout
                    break
                }
                // 行首就放不下、又不可拆（图形段）：跳过这个部件且**不计进 `x`**，
                // 继续看下一个 —— `PrintStringBuffer.cs:425-441` 的内层 if 没有 else 分支，
                // 原版就是这么写的，照抄不修（ADR-005）。它的宽度仍留在 `layout` 里，
                // 后续候选件的前缀余量按节点自身 `PointX` 算（`:468`），那里是含它的。
                layout += p.cells
            } else {
                fitted++
                chars += p.charSpan
                x += p.cells
                layout += p.cells
            }
        }
        val cand = candidate ?: return chars
        if (!cand.canDivide) {
            // 候选不可拆：原版部件级 getDivideIndex 直接返回 -1（`PrintStringBuffer.cs:455-458`），
            // 外层只在 `> 0` 时将前缀字符数累加（`:442-449`），等价于本件一个字符都不进拆分点。
            // 典型形态「文本(10) + 图形(48)」在 20 格屏：拆分点 = 10，图形段整体挪到下一行。
            return chars
        }
        val inner = maxPrefixChars(cand, clientWidth - candidateX)
        if (inner > 0) {
            chars += inner
        }
        return chars
    }

    /** 从 [FoldPiece.text] 起最多能放进 `remaining` 格的前缀长度（返回 `Char` 个数）。 */
    private fun maxPrefixChars(piece: FoldPiece, remaining: Int): Int {
        val s = piece.text
        var used = 0
        var index = 0
        while (index < s.length) {
            val w = pieceCellsAt(s, index)
            if (used + w > remaining) {
                break
            }
            used += w
            index += step(s, index)
        }
        return index
    }

    /**
     * 断行后把 `from` 及其后的段整体重排到新的一行（`PrintStringBuffer.cs:244-249`）。
     * 原版一次排到列表尾；等价性在于每段的 `pointX` 只在它真正入行时才被消费。
     */
    private fun relayout(segments: List<FoldSegment>, from: Int) {
        var x = 0
        for (j in from until segments.size) {
            val s = segments[j]
            s.pointX = x
            x += s.width
        }
    }

    /** 收行：段内部件按序落成 run，并累加本行的半角格总数。 */
    private fun rowOf(current: ArrayList<FoldSegment>, line: RenderLine, firstLine: Boolean, last: Boolean): DisplayRow {
        val out = ArrayList<TextRun>()
        var cells = 0
        for (seg in current) {
            for (p in seg.pieces) {
                out.add(p.toRun())
                cells += p.cells
            }
        }
        current.clear()
        return DisplayRow(
            runs = out,
            cells = cells,
            isLogicalLine = firstLine && line.isLogicalLine,
            isLineEnd = if (last) line.isLineEnd else true,
        )
    }

    companion object {
        /** `Config.ButtonWrap` 默认 false（`ConfigData.cs:83`）。 */
        const val DEFAULT_BUTTON_WRAP = false

        /** `Config.CompatiLinefeedAs1739` 默认 false（`ConfigData.cs:102`）。 */
        const val DEFAULT_COMPATI_LINEFEED_AS_1739 = false

        /**
         * 索引处的码点占几格；代理对按两个字符合计（2 + 2 = 4），与 [Grid.cells] 的逐字符
         * 累加**同式** —— 这正是「段宽」与「前缀宽度」不许分家的另一半：沿 [step] 逐码点求和
         * 必须逐值等于整串的 [Grid.cells]（`FoldPiece.cells` 走的就是整串），
         * `grid/AstralWidthTest` 对每一条登记串都断言这条等式。
         *
         * 单独一个代理项半（未成对）按 [Grid.cellWidth] 记 2 格，与核心层
         * `core/host/CellWidth.kt` 的 `of`（逐码位）在该处**同值**；两层只在**合法代理对**上
         * 差一倍（4 vs 2）—— 边界见类 KDoc 与 `Grid` 的 KDoc，逐值账在 `grid/AstralWidthTest`。
         */
        internal fun pieceCellsAt(s: String, index: Int): Int {
            val first = Grid.cellWidth(s[index])
            if (!isSurrogatePairStart(s, index)) {
                return first
            }
            return first + Grid.cellWidth(s[index + 1])
        }

        /**
         * 索引处往后跳多少个 `Char` 才是下一个码点（合法代理对 2、其余 1）。
         *
         * 返回的是 **`Char` 个数**，索引空间是 UTF-16 —— 与原版同域：`PrintStringBuffer.cs:471-495`
         * 的二分在 `text.Length` 与 `Slice(0, num4)` 上走，`ConsoleStyledString.cs:54-73` 的
         * `DivideAt` 也是 Char 下标。**不要**把它改成「按码位索引」：`FoldSegment.divideAt`
         * 的入参 `divChars` 与 `FoldPiece.splitAt` 的 `substring` 都吃 Char 下标，全链必须同域
         * （理由与重钉清单见类 KDoc 与 `Grid` 的 KDoc「增补平面（U+10000 以上）的口径边界」节）。
         */
        internal fun step(s: String, index: Int): Int = if (isSurrogatePairStart(s, index)) 2 else 1

        private fun isSurrogatePairStart(s: String, index: Int): Boolean =
            Character.isHighSurrogate(s[index]) && index + 1 < s.length && Character.isLowSurrogate(s[index + 1])
    }
}

/**
 * 段内的一个样式部件，对应原版 `AConsoleDisplayNode` 的两个家族：纯文本件走
 * `ConsoleStyledString`（可拆，`ConsoleStyledString.cs:15`），带 [InlineGraphic]
 * 负载的图形件走 `ConsoleImagePart` / `ConsoleShapePart`（不可拆，
 * `ConsoleImagePart.cs:35`、`ConsoleShapePart.cs:8-10`），判定见 [canDivide]。
 *
 * [skeleton] 只携带除文本以外的样式与按钮信息（其 `text` 恒为空串），真正的文本由 [text]
 * 持有并在拆段时原地缩短 —— 与 `ConsoleStyledString.DivideAt`（`ConsoleStyledString.cs:54-73`）
 * 「左半原地截断、右半新建同样式实例」一致：右半继承 Font、Color、ButtonColor、StringStyle，
 * 图形件还继承 `graphic` 负载（这正是不可拆的原因：拆一次就多画一张图）。
 */
private class FoldPiece(val skeleton: TextRun, var text: String) {

    /**
     * 本件占的半角格数。
     *
     * **图形件一律取节点自带的 [InlineGraphic.widthCells]，不数文本**：文本负载只是
     * `HtmlRunFlatten.emitGraphic` 垫的占位空格，而负 space（`param='-Npx'`）的文本**恒为空串**
     * （`emitGraphicRun` 对负宽度给空串，否则 `" ".repeat(负)` 直接抛）—— 按文本数就会把
     * 「回退」记成「不占」，整行的宽度账凭空多出一张图的宽。原版数的是**部件宽**，而且带符号：
     * `ConsoleSpacePart.cs:11` 把矩形宽原样存进 `WidthF`（负值保留）、`:20-24` 的 `SetWidth`
     * 直写 `Width = (int)(subPixel + WidthF)`；`ConsoleDisplayLine.cs:63-66` 的对齐宽度是
     * `num += consoleButtonString.Width` 的**有符号求和**，`ConsoleButtonString.cs:292-304`
     * 的 `CalcPointX` 同样 `num += part.Width`。于是负 space 既把后续图层拉回立绘起点，
     * 也把行宽扣回一张图的量 —— 两者必须同时成立，否则居中平移算错。
     *
     * 正值图形件两种数法逐值相同（`emitGraphicRun` 就按 `widthCells` 铺空格），所以本条只改
     * 负 space 一支；文本件仍走 [Grid.cellsExpanded]（TAB 已在 [LineFolder] 的 `group` 里展开）。
     */
    var cells: Int = skeleton.graphic?.widthCells ?: Grid.cellsExpanded(text)

    /**
     * 图形段（M10-GFX 的 [InlineGraphic] 负载，经 `adapter/HtmlRunFlatten` 展平而来）不可拆，
     * 其余可拆：原版 `ConsoleStyledString.CanDivide` 为 true（`ConsoleStyledString.cs:15`），
     * `ConsoleImagePart.CanDivide` 为 false（`ConsoleImagePart.cs:35`），
     * `ConsoleShapePart`（矩形与空白的抽象基类，两个子类都继承此值）同为 false
     * （`ConsoleShapePart.cs:8-10`）。拆段会把 `skeleton.graphic` 随 `skeleton` 抄给左右两半
     * （[splitAt] 的 `skeleton` 继承，同 `ConsoleButtonString.cs:247-251` 抄 `IsButton` 一族），
     * 图形段一旦跨行被劈就**每半各画一次同一个图** —— 窄屏上 48 格的横条被画 3 次即此缺陷。
     */
    val canDivide: Boolean = skeleton.graphic == null

    /**
     * 本件在 [FoldSegment.divideAt] 的字符索引空间里占多少字符。
     *
     * 图形段记 **0**：原版 `ConsoleImagePart` 构造即 `base.Text = ""`（`ConsoleImagePart.cs:41`），
     * 宽度是节点自带的像素宽、不进字符域（`:67-80` 直写 `base.Width`，`SetWidth` 只在
     * sprite 查不到时才去量 AltText：`:104-115`）；`getDivideIndex` 的字符累加
     * （`PrintStringBuffer.cs:438` 取 `Text.Length`）与 `DivideAt` 的字符游标
     * （`ConsoleButtonString.cs:205` 同取 `Text.Length`）因此都**绕得开**图形段，
     * 拆分点永远落不进图形段内部。移植侧图形段的 [text] 是 `HtmlRunFlatten` 垫的等量空格
     * （只为 [cells] 的占宽口径），在字符域必须按 0 记账，否则 [divideIndex] 的返回值会把
     * 拆分点落进空格垫里、经 [splitAt] 复制出两份 graphic 负载。
     * TODO(verify)：sprite 查不到时原版 `Text = AltText`（`ConsoleImagePart.cs:63`），
     * 该段变「不可拆的真文本」；移植侧查名发生在绘制侧，折行层拿不到这个信息，
     * 回落形态（占宽按格数、按原文画 AltText）归绘制侧，卡点：折行层无 sprite 查询口。
     */
    val charSpan: Int
        get() = if (canDivide) text.length else 0

    /** 拆出后半件（本件只留前缀），对应 `ConsoleStyledString.DivideAt(index)`。 */
    fun splitAt(charCount: Int): FoldPiece {
        val rest = FoldPiece(skeleton = skeleton, text = text.substring(charCount))
        text = text.substring(0, charCount)
        cells = Grid.cells(text)
        return rest
    }

    /** 落到显示行上的 run：样式与 `buttonValue` 原样继承，只换文本。 */
    fun toRun(): TextRun = skeleton.copy(text = text)

    override fun toString(): String = text
}

/**
 * 一个折行单位，对应原版 `ConsoleButtonString`（`ConsoleButtonString.cs:13-402`）。
 *
 * [isButton] 就是原版的 `IsButton`（`ConsoleButtonString.cs:33`）。按钮值挂在各部件的
 * [TextRun.buttonValue] 上，拆段时随 [FoldPiece.splitAt] 的 `skeleton` 原样继承 ——
 * 对应原版把 `IsButton`、`Input`、`Inputs`、`Generation` 抄给后半件
 * （`ConsoleButtonString.cs:247-251`），因此跨行按钮在每一行各成一段且值相同。
 */
private class FoldSegment(val isButton: Boolean, val pieces: ArrayList<FoldPiece>) {

    /** 本段总宽度（半角格）。 */
    var width: Int = sumCells(pieces)

    /** 本段在当前行上的起始列（半角格，含头不含尾）。 */
    var pointX: Int = 0

    /**
     * 在第 [divChars] 个字符处拆成两半：本段保留左半，返回右半。
     * 右半为空时返回 null（对应 `ConsoleButtonString.cs:231-234`）。
     */
    fun divideAt(divChars: Int): FoldSegment? {
        if (divChars <= 0) {
            return null
        }
        val left = ArrayList<FoldPiece>()
        val right = ArrayList<FoldPiece>()
        var consumed = 0
        var switched = false
        for (p in pieces) {
            if (switched) {
                right.add(p)
                continue
            }
            // 字符游标走 [FoldPiece.charSpan]（图形段记 0）：原版 `ConsoleButtonString.DivideAt`
            // 的 `num += strArray[i].Text.Length`（`ConsoleButtonString.cs:205,227`）对
            // `Text = ""` 的图形节点（`ConsoleImagePart.cs:41`）恒加 0，拆分点落不进图形段内部；
            // 若真落进（divIndex 恰等于该处的累计字符数）走 `:220-224` 的中支，整段进左半、
            // 其后的部件全部进右半 —— 下面的 `divChars == consumed + len` 支就是它的直译。
            val len = p.charSpan
            when {
                divChars < consumed + len -> {
                    left.add(p)
                    right.add(p.splitAt(divChars - consumed))
                    switched = true
                }
                divChars == consumed + len -> {
                    left.add(p)
                    switched = true
                }
                else -> {
                    left.add(p)
                    consumed += len
                }
            }
        }
        if (right.isEmpty()) {
            return null
        }
        pieces.clear()
        pieces.addAll(left)
        width = sumCells(pieces)
        val rest = FoldSegment(isButton = isButton, pieces = right)
        rest.pointX = pointX + width
        return rest
    }

    private companion object {
        fun sumCells(list: List<FoldPiece>): Int {
            var total = 0
            for (p in list) {
                total += p.cells
            }
            return total
        }
    }
}
