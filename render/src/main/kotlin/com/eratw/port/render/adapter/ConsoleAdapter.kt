package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.scroll.Viewport
import java.util.IdentityHashMap

/**
 * 控制台产物 → 渲染层产物的适配器（M4-ADAPT）。
 *
 * 输入是 `:core-vm` 的生产 sink 产物 `core/host/LineBufferConsoleSink.kt` 的
 * `Kind`（该文件 36 行）、`Segment`（51 行）、`Button`（69 行）、`DisplayLine`（86 行），
 * 输出是 [RenderLine] / [TextRun]，并负责把 [RenderLine] 落成 [DisplayRow]、把 [DisplayRow]
 * 并入 [LogBuffer]。依赖方向 `:render -> :core-vm` 单向成立，`:core-vm` 永不反向 import 本包。
 *
 * ## 1 样式只在映射时搬运，绝不重解析
 *
 * 原版把颜色/字体/样式固定在每个 `ConsoleStyledString` 的构造点上
 * （spec: `ConsoleStyledString.cs:26-43`），核心层的 `Segment` 已经按同一口径做了快照
 * （spec: `LineBufferConsoleSink.kt:38-58`）。因此本类**逐项照抄** `foreColor`/`styleMask`/
 * `fontName`/`htmlOption`，既不解析颜色名、也不解析 HTML 标签、也不合并或拆散非按钮段。
 * `Kind` 与 [RunKind] 五个枚举项同名同序（`LineBufferConsoleSink.kt:36` 对
 * `Kernel.kt:37`），按名字映射，不做任何“顺手归一”。
 *
 * ## 2 按钮：一个按钮一条 run（并段口径）
 *
 * 原版 `AppendButton` 每次都先 `fromCssToButton()` 把此前的样式段收走，再把**本按钮自己的
 * 那一个** `ConsoleStyledString` 包成一条 `ConsoleButtonString`
 * （spec: `PrintStringBuffer.cs:103-123`、`:280-292`、`:294-316`）。所以相邻两个
 * `PRINTBUTTON` 在原版是两条互不相干的按钮段，命中区各自等于自己的文本宽。
 * 而核心层的 `RowBuilder.put`（`LineBufferConsoleSink.kt:466-476`）会把样式相同的相邻
 * BUTTON 段**并成一条 Segment**，只在 `buttons` 里留下各自的列区间；若适配层照 Segment
 * 一条 run 直出，`ButtonLayer` 就会把整条 run 当成一个按钮 —— 命中区偏大。
 * 故本类按 `buttons` 的列区间把 BUTTON 段的文本**切回一按钮一 run**，
 * `buttonValue` 逐 run 携带（跨行按钮在每一行各一条 Segment、各一条 Button，
 * spec: `LineBufferConsoleSink.kt:60-69` 对 `ConsoleButtonString.cs:187-215`），
 * 同时逐 run 带上**源按钮编号** [TextRun.buttonId]（= 该按钮在本行 `buttons` 列表里的下标；
 * 行内区间两两不相交 ⇒ 下标即身份）。渲染层按样式把一条按钮拆成多条 run 之后，
 * 只有这个编号能把「同一按钮的多个样式片」（`ConsoleButtonString.cs:19,296-303`）
 * 与「两个值相同、列号相接的独立按钮」（`PrintStringBuffer.cs:103-123`）分开，
 * 于是 `ButtonLayer` 从 run 文本重算出的命中列是原版 `PointX` 的等价物，
 * 而命中**条数**等于原版 `ConsoleDisplayLine.buttons` 的数组长度（`ConsoleDisplayLine.cs:18,28`）。
 * TODO(verify)：切点用 [Grid.cells] 度量、段宽来自核心层自己的等宽表
 * （`LineBufferConsoleSink.kt:758-770` 的东亚宽字符区间），两表在 U+2000..U+2E7F 一类
 * 符号上结论不同（例：`※` 核心层 1 格、[Grid] 2 格）。两表必须统一为 [Grid] 一处，
 * 已由「需组长接线」提出；本波不为此再造第三套宽度函数。
 * TODO(verify)：原版还有**自动按钮**这一条 —— 普通文本里的 `[123]` 会在
 * `fromCssToButton` → `createButtons` 里被 `ButtonStringCreator.SplitButton` 变成可点段
 * （spec: `PrintStringBuffer.cs:280-292`、`:326-345`，`ButtonStringCreator.cs:11,24-27,51-68`）。
 * 该条已由 `core/host/ButtonStringCreator` 在**核心层的折行入口**落地
 * （`core/host/LineBufferConsoleSink.kt` 的 `splitAutoButtons`），核心层因此会给出
 * `Kind.BUTTON` 段 + 没有显式 PRINTBUTTON 的 [LineBufferConsoleSink.DisplayLine.buttons] 区间；
 * 本类照旧只按 `buttons` 的列区间切段（[runsOf]），不在这里解析 `[...]`。
 *
 * ## 3 空行：先塞一个空格再折行
 *
 * 原版 `PrintFlush(force)` 在缓冲为空时先 `Append(" ", Style)` 再 flush
 * （spec: `EmueraConsole.cs:3379-3382`），所以屏上的空行是「一个空格、占 1 格」的行。
 * `LineFolder` 对零内容输入直接返回空表（spec: `PrintStringBuffer.cs:198-201`，
 * 该类 KDoc 亦声明“适配层要复刻这条，别指望本类造空行”），故这条补空格在本适配层落地：
 * [adapt] 见到「无任何段」或「全部段文本为空」的 `DisplayLine` 时，补一条单空格 run。
 * 样式取该行的第一段（有则用它的颜色/字体/样式，无则用核心层默认值
 * `LineBufferConsoleSink.kt:831-837`，即 `Config.ForeColor`/`Config.FontName` 默认，
 * spec: `ConfigData.cs:65,68`）。
 *
 * ## 4 取行窗口：绘制与命中的**起行公式恒等**，差的是那一排被裁掉的行
 *
 * 原版两处的写法不同、**算出来的 `num3` 逐值相同**：
 * - 绘制 `num = MainPicBox.Height - LineHeight; num3 = num2 - (num / LineHeight + 1)`
 *   （spec: `EmueraConsole.cs:1805-1807`）；
 * - 命中 `num3 = num2 - MainPicBox.Height / LineHeight`（spec: `EmueraConsole.cs:2328-2331`）。
 *
 * 恒等式：`(H - L) / L + 1 == H / L`（`H >= L` 时，`floor((H-L)/L) = floor(H/L) - 1`），
 * 所以「绘制比命中多读一排」在 C# 里**并不存在** —— 两处只是同一窗口的两种写法。
 * 逐值读数见 `adapter/ConsoleAdapterTest` 的 `绘制与命中的起行公式恒等且窗口逐行等价`
 * 与 `scroll/ViewportAuditTest` 的 `绘制窗口多出的那一行完全在画布外`。
 *
 * 本仓的 [Viewport.heightLines] 是**命中窗口的行数**（基准 54 = `955 / 18 + 1`，
 * `view/ConsoleScreen.DEFAULT_HEIGHT_LINES`），`topRingIndex` 的算式是
 * `num2 - heightLines + 1 - extraAbove`（`scroll/Viewport.kt:173-180`）：
 * - 命中传 `HIT_EXTRA_ABOVE = 0` ⇒ 与 C# 的 `num2 - H / L` 逐值相同；
 * - 绘制传 `DRAW_EXTRA_ABOVE = 1` ⇒ 在同一个窗口之上**再往前一行**，那一行在画布上
 *   完全被裁掉（`top + lineHeight <= 0`，`view/ScreenComposer.rowTopPx` 的读数），
 *   所以屏上可见内容与命中窗口**逐行相同**：`rowsForDraw.drop(1) == rowsForHit`
 *   （行数不足一屏、顶行被夹到 0 时两支**整体相同**，见 :1808-1811 与 :2329-2332）。
 *
 * 结论：两个常量不是「两套口径」，而是**同一窗口的两种取法**（绘制多要一排不可见行，
 * 好让首帧/滚动后的合成器有溢出内容）；两个入口保留是为了让装配层不写 1 与 0，
 * 而**等价性由测试逐行钉住**，不许有人把 `DRAW_EXTRA_ABOVE` 改成 0 或把窗口挪半行。
 * `extraAbove` 的字面量在本仓只出现在这两个常量上，
 * 装配层（`view/ConsoleScreen`）一律经本类取行与取基准。
 * 行数基准：[topRowForDraw] / [topRowForHit] 与 [rowsForDraw] / [rowsForHit] 出自同一对常量。
 *
 * ## 5 PRINTN 续行在日志侧的可见后果
 *
 * 原版 `addDisplayLine` 在末行 `IsLineEnd == false` 时先 `deleteLine(1)`（`lineNo--`，
 * spec: `EmueraConsole.cs:3013`）、把新行的按钮整体右移旧行尾列后拼成一条，再
 * `displayLineList.Add(line)` 并 `line.LineNo = lineNo`（spec: `EmueraConsole.cs:2945,2947-2970`）
 * —— 删号再取号，所以**绝对行号不变**，日志看起来从来没断行。
 * 本类的 [appendMerged] 用 [LogBuffer.removeLast] + [LogBuffer.append] 复刻同一条（两者合起来
 * 与 `replaceLast` 等价且保留原号），[mergedRow] 复刻按钮拼接与列号右移。
 * [LogBuffer.removeLast] 是**逻辑行**粒度（从尾部走到 `IsLogicalLine` 首排才收口，
 * spec: `EmueraConsole.cs:3007-3021`），所以被并入的 PRINTN 行即使折成多排也整条出列 ——
 * 与 `deleteLine(1)` 逐值相同；同一粒度也约束着快照差分的回撤计次（见 [ConsoleLogSync.submit]）。
 * **标志位只有一个口径**：`addDisplayLine` 把拼接结果写回**新行对象**（`line.ChangeStr(...)`
 * 只换 `buttons` 数组，spec: `ConsoleDisplayLine.cs:102-110`），入列入的也是这个新对象
 * （spec: `EmueraConsole.cs:2969`），而 `IsLogicalLine` 是只读、只在构造时赋值
 * （spec: `ConsoleDisplayLine.cs:12,45`）—— 所以并入行的 `isLogicalLine` 取**新行**的，
 * `isLineEnd`/`temporary`/`align` 同样取新行的（对齐快照只作用在新行对象上，
 * spec: `EmueraConsole.cs:2943`；并入判定读的是**旧行**的 `IsLineEnd`，spec: `:2950`；
 * `IsTemporary` 同 `IsLogicalLine` 一样是构造期固定的只读字段，spec: `ConsoleDisplayLine.cs:14,46`；
 * `LINECOUNT` 的自增条件「新行 `IsLogicalLine` 且入列后的末行 `IsLineEnd`」见 `EmueraConsole.cs:2971-2977`）。
 * 核心层自己已经在 `appendGroup`/`mergeRows` 里并过一次（`LineBufferConsoleSink.kt:727-759`，
 * 它写作 `prev.copy(...)` ⇒ 逻辑行起点沿用旧行，与本条不同），
 * 所以适配层的这条只对「把快照逐条喂给 LogBuffer」的调用方生效：重复提交同一条未断行日志
 * 不会多出第二行（见 [ConsoleLogSync]）。
 * TODO(verify)：核心层那条 `prev.copy` 与本条的 `isLogicalLine` 分歧要由 CONSOLE-2 收口到一处
 * （卡点：core 的 host 目录不归本路，且分歧只在「PRINTN 的行本身是折行续行」这一形态上可见；
 * 原版 `deleteLine` 的 `num` 按**逻辑行**计数（spec: `EmueraConsole.cs:3014-3021`）还会连带
 * 多删行、并在 `:3032-3036` 把 `lineNo` 归零，本仓 [LogBuffer] 的 KDoc 已声明不建模 DELETELINE 侧）。
 *
 * ## 6 `firstIndex` 与显示列表下标：绝对行号是权威
 *
 * **权威序号是 [LogBuffer] 的绝对行号**（`0 until totalLines`，spec: `EmueraConsole.cs:2945,2970,507`）。
 * `DisplayList` 的行下标只是「保留区内的相对位置」，淘汰头部行时**整屏下标一起前移**，
 * 换算式就是原版那句 `shift = (GetLineNo > MaxLog) ? GetLineNo - MaxLog : 0` 然后
 * `下标 = LineNo - shift`（spec: `EmueraConsole.cs:1865-1868`、`:2402`），
 * 而 [LogBuffer.firstIndex] 与那句逐项相等（`firstIndex = totalLines - size`，满时等于
 * `totalLines - maxLog`）。于是：
 * * 绝对行号 → 下标：[positionOf]；下标 → 绝对行号：[lineNoAt]。
 * * 跨淘汰稳定的比较/寻址一律用绝对行号；`DirtyRect` 与 [HitTarget] 的下标只在本次快照内有效。
 *
 * ## 7 宽度模型二选一：本路选等宽网格
 *
 * 本类所有列数/格数只走 [Grid.cells]（半角 1 / 全角 2，spec: `EmueraConsole.cs:3255` 的
 * Shift-JIS 字节数口径 + `Kernel.kt:69-82`）。真实字体度量（advance 表、像素）归
 * `render` 的 glyph 目录（c-glyphmetrics 路，`CellMetrics` 是像素与半角格之间的唯一换算入口），
 * 本类不调用它、也不自造第三套宽度函数。
 *
 * ## 线程模型
 *
 * 本对象无可变状态（纯函数）；有状态的增量提交在 [ConsoleLogSync]，同样是实例字段、无静态可变全局。
 */
object ConsoleAdapter {

    /**
     * 绘制取行相对命中窗口多要的排数（1）。C# 的 `:1805-1807` 与 `:2328-2331` 算出的 `num3`
     * 逐值相同（恒等式见类 KDoc 第 4 条），本仓这一排是**被画布裁掉的那排**，
     * 使 `rowsForDraw.drop(1) == rowsForHit`。
     */
    const val DRAW_EXTRA_ABOVE: Int = 1

    /** 命中检测取行的溢出排数（0，spec: `EmueraConsole.cs:2328-2331`；与 C# 的 `num3` 逐值相同）。 */
    const val HIT_EXTRA_ABOVE: Int = 0

    /** 空行补齐用的单空格（spec: `EmueraConsole.cs:3379-3382`）。 */
    private const val EMPTY_LINE_TEXT = " "

    /**
     * 一条显示行：`DisplayLine` → [RenderLine]。
     *
     * 透传项：`isLogicalLine`（折行首段标记，spec: `PrintStringBuffer.cs:193,206`）、
     * `isLineEnd`（PRINTN 未收尾标记，spec: `EmueraConsole.cs:2948-2977`）、
     * `alignment`（ALIGNMENT 快照，spec: `Process.cs:1536-1552`）、`isTemporary`
     * （REUSELASTLINE，spec: `EmueraConsole.cs:2891-2893`）、`backColor`（非按钮 run 逐条落到
     * [TextRun.back]，因为 SETBGCOLOR 是控制台全局色、核心层按行快照）。
     *
     * 唯一的例外是**按钮段**的 [TextRun.back]：它承载原版 `StringStyle.ButtonColor`
     * （缺省 = `Config.FocusColor`，spec: `StringStyle.cs:11,22`、`ConfigData.cs:70`），
     * 因为绘制侧在焦点命中时正是从这一槽取字色（`view/ScreenComposer.glyphFore` 对
     * `ConsoleStyledString.cs:105-111`）。缺省 [LineBufferConsoleSink.DEFAULT_FOCUS_COLOR]
     * 是出厂值，真机上应按 `emuera.config` 的 FOCUSCOLOR 经 [focusColor] 注入。
     *
     * @param focusColor 按钮 run 覆盖的焦点色 `0xRRGGBB`；非按钮段不受影响
     */
    fun adapt(
        line: LineBufferConsoleSink.DisplayLine,
        focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
    ): RenderLine {
        val runs = runsOf(line, focusColor)
        return RenderLine(
            // 空行补空格只对**打印缓冲**行成立（spec: `EmueraConsole.cs:3379-3382` 的
            // PrintFlush `Append(" ", Style)` + `PrintStringBuffer.cs:198-201` 的空表分支）；
            // **直印 HTML 行**（Kind.HTML）在原版走 `addRangeDisplayLine(Html2DisplayLine(...))`
            // （EmueraConsole.cs:3217-3225），不经 PrintFlush，C# 不补 —— GFX-03 的
            // br/div 结构切行切出的空行文本必须是 ""，行的存活由展平器 `out.isEmpty()`
            // 的占位分支负责（全标签行进脏区防残影，见 HtmlRunFlatten.flatten）。
            runs = if (isEmptyLine(runs) && runs.none { it.kind == RunKind.HTML }) listOf(emptySpaceRun(line)) else runs,
            isLogicalLine = line.isLogicalLine,
            isLineEnd = line.isLineEnd,
            align = line.alignment,
            temporary = line.isTemporary,
            noBreak = line.noBreak,
        )
    }

    /** 整屏快照：最旧在前，逐条 [adapt]，顺序与条数都不改动（spec: `EmueraConsole.cs:238` 的 `displayLineList`）。 */
    fun adapt(
        lines: List<LineBufferConsoleSink.DisplayLine>,
        focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
    ): List<RenderLine> = lines.map { adapt(it, focusColor) }

    /**
     * 折行 + 列偏移回填：[RenderLine] → [DisplayRow]。
     *
     * 折行本身是 `grid` 路的职责（[LineFolder]），本函数只负责把它没带的两项补上：
     * `align`/`temporary` 透传，以及按原版 `SetAlignment` 算出的 [DisplayRow.indentCells]。
     * 原版在 `addDisplayLine` 里对**每一条显示行**分别调用 `SetAlignment`
     * （spec: `EmueraConsole.cs:2937-2944`），所以折行产生的每一行各自对齐、各自平移。
     *
     * `noBreak` **原样交给折行器**：PRINTSINGLE / REUSELASTLINE / 独立错误行在原版走
     * `FlushSingleLine` 的 `nobr: true` 分支、一行不折（`PrintStringBuffer.cs:170-177` 的
     * `:173`；调用点 `EmueraConsole.cs:3147-3165`、`:3053-3056`、`:3098-3115`），折行判定
     * 读的正是它（`PrintStringBuffer.cs:217` 的 `if (nobr || PointX + Width <= num)`）。
     * 不传下去的话，超宽的 PRINTSINGLE 行会被 [LineFolder] 按宽度二次拆开 —— 原版只会画到
     * 右沿外。`LineFolder.fold(line, noBr)` 的 `noBr` 参数就是这条（同一份直译）。
     */
    fun fold(
        folder: LineFolder,
        lines: List<RenderLine>,
        drawableWidthCells: Int = folder.clientWidth,
    ): List<DisplayRow> = lines.flatMap { line ->
        folder.fold(line, noBr = line.noBreak).map { row ->
            row.copy(
                align = line.align,
                temporary = line.temporary,
                indentCells = alignmentShift(line.align, row.cells, drawableWidthCells),
            )
        }
    }

    /**
     * 整行列偏移（半角格，可为负）。
     *
     * 直译 `ConsoleDisplayLine.SetAlignment`（spec: `ConsoleDisplayLine.cs:50-91`）：
     * `num` = 本行所有段宽之和 = [cells]（原版连非按钮段也是 `ConsoleButtonString`，
     * spec: `PrintStringBuffer.cs:326-345`），`pointX` = 首段左沿（整数网格里折行后恒 0，
     * spec: `LineFolder` 的 `relayout` 从 0 起排），于是三条分支的返回值：
     * * LEFT：`IsLogicalLine` 时直接 return（`:72-78`），否则目标列 `num2 = 0` ⇒
     *   偏移 `0 - pointX = 0`。两条分支都是 0，故 LEFT 恒不平移。
     * * CENTER：`num2 = DrawableWidth / 2 - num / 2`（`:80`；整数除法向零截断，Kotlin 同语义）。
     * * RIGHT：`num2 = DrawableWidth - num`（`:83`）。
     * 最后 `num3 = num2 - pointX`（`:86-90`）经 `ShiftPositionX` 加到每个段的 `PointX` 上
     * （spec: `ConsoleButtonString.cs:307-315`）—— 本函数返回的就是这个 `num3`。
     *
     * 原版 `aligned` 的一次性守卫（spec: `ConsoleDisplayLine.cs:52-56`）在这里不需要：
     * 本函数是纯函数、不改行，重复调用结果相同。
     *
     * @param cells 本行内容宽度（不含偏移）
     * @param drawableWidthCells 可绘制宽度，对应 `Config.DrawableWidth`（spec: `Config.cs:466`）
     * @return 需要加到段列号上的偏移；超宽行在 RIGHT/CENTER 下会得到负值（原版同样会把
     *   `PointX` 移成负数、画到左边界外），由下游自行裁剪
     */
    fun alignmentShift(align: Alignment, cells: Int, drawableWidthCells: Int): Int = when (align) {
        Alignment.LEFT -> 0
        Alignment.CENTER -> (drawableWidthCells / 2) - (cells / 2)
        Alignment.RIGHT -> drawableWidthCells - cells
    }

    /**
     * 绘制取行：官方口径 `extraAbove = 1`（spec: `EmueraConsole.cs:1805-1807`）。
     *
     * 比 [rowsForHit] **多在最上面一排**，那一排在画布上完全被裁掉
     * （`view/ScreenComposer.rowTopPx` 的 `top + lineHeight <= 0`），所以两支的**可见行逐行相同**：
     * `rowsForDraw(..).drop(1) == rowsForHit(..)`（行数不足一屏、顶行夹到 0 时两支整体相同）。
     * C# 两处的 `num3` 本来就逐值恒等 —— 见类 KDoc 第 4 条的恒等式。
     */
    fun rowsForDraw(viewport: Viewport, buffer: LogBuffer): List<DisplayRow> =
        viewport.rows(buffer, DRAW_EXTRA_ABOVE)

    /**
     * 命中取行：官方口径 `extraAbove = 0`（spec: `EmueraConsole.cs:2328-2331`）。
     * 与 [rowsForDraw] 的差只有最上面那一排被裁掉的行（见 [rowsForDraw]）。
     */
    fun rowsForHit(viewport: Viewport, buffer: LogBuffer): List<DisplayRow> =
        viewport.rows(buffer, HIT_EXTRA_ABOVE)

    /**
     * 绘制口径的顶行**保留区下标**（官方 `num3 = num2 - ((Height - LineHeight) / LineHeight + 1)`，
     * spec: `EmueraConsole.cs:1805-1807`），与 [rowsForDraw] 是同一批行的行号基准；
     * 未被夹到 0 时等于 [topRowForHit] 减 1（那一排即被裁掉的那排）。
     * 装配层要「行窗口 + 它的基准」时用这条，`extraAbove` 的字面量不必在调用侧出现。
     */
    fun topRowForDraw(viewport: Viewport, retainedLines: Int = viewport.retainedLines): Int =
        viewport.topRingIndex(retainedLines, DRAW_EXTRA_ABOVE)

    /**
     * 命中口径的顶行**保留区下标**（官方 `num3 = num2 - Height / LineHeight`，spec: `EmueraConsole.cs:2328-2331`）。
     * 与 C# 逐值相同（`heightLines` 就是 `Height / LineHeight + 1` 的行数口径）。
     */
    fun topRowForHit(viewport: Viewport, retainedLines: Int = viewport.retainedLines): Int =
        viewport.topRingIndex(retainedLines, HIT_EXTRA_ABOVE)

    /**
     * 一批显示行的命中表（[HitTarget] 列表）。
     *
     * 几何口径全部委托给 `button` 路的 [ButtonLayer]（区间半开、跨行各一条、空文本不入表），
     * 本函数只是适配层的门面：调用方交出 `DisplayRow` 就拿到与原版 `Buttons` 数组等价的条目，
     * 不在此重复实现任何列计算（spec: `EmueraConsole.cs:2358-2370`、`ConsoleButtonString.cs:300-304`）。
     */
    fun hitTargets(rows: List<DisplayRow>): List<HitTarget> {
        val layer = ButtonLayer()
        layer.attach(rows)
        return layer.all()
    }

    /**
     * 绝对行号 → 保留区下标（spec: `EmueraConsole.cs:1865-1868`、`:2402` 的
     * `LineNo - shift`，`shift = firstIndex`）。已被淘汰或还没生成的号返回 null。
     */
    fun positionOf(buffer: LogBuffer, lineNo: Long): Int? {
        if (lineNo < buffer.firstIndex || lineNo >= buffer.totalLines) {
            return null
        }
        return (lineNo - buffer.firstIndex).toInt()
    }

    /** 保留区下标 → 绝对行号（[positionOf] 的逆运算；越界返回 null）。 */
    fun lineNoAt(buffer: LogBuffer, position: Int): Long? {
        if (position < 0 || position >= buffer.size) {
            return null
        }
        return buffer.firstIndex + position.toLong()
    }

    /**
     * 按原版 `addDisplayLine` 的入列规则把行并进日志缓冲（spec: `EmueraConsole.cs:2885-2997`）：
     * 1. 末行是临时行 → 先删（`:2891-2893`）；
     * 2. 末行未断行（PRINTN）→ 删掉旧的、把新行拼到它后面再入列，**绝对行号不变**
     *    （`:2947-2968` + `:2945` + `:3013`）；标志位全部取新行（见 [mergedRow]）；
     * 3. 其余直接追加（`:2969-2970`）。
     *
     * 这条是**全仓唯一的入列规则**：`view/ConsoleScreen` 的增量批与 [ConsoleLogSync] 的快照
     * 回撤后重放都调它，装配层不许再写一份（M4-D 的 ADAPT-CONSUME）。
     *
     * @return 本批结束后**真正还留在缓冲尾部**的那些行。合并或临时行先删发生时，被摘掉的行
     *   已经不在缓冲里，不再出现在返回值中，故条数少于入参；调用方拿它做脏区/快照对照才不会
     *   指向一条已经不存在的行。
     */
    fun appendMerged(buffer: LogBuffer, rows: List<DisplayRow>): List<DisplayRow> {
        val landed = ArrayList<DisplayRow>(rows.size)
        for (row in rows) {
            val temporary = buffer.last()
            if (temporary?.temporary == true) {
                buffer.removeLast()
                retract(landed, temporary)
            }
            val prev = buffer.last()
            if (prev != null && !prev.isLineEnd) {
                buffer.removeLast()
                retract(landed, prev)
                val merged = mergedRow(prev, row)
                buffer.append(merged)
                landed.add(merged)
            } else {
                buffer.append(row)
                landed.add(row)
            }
        }
        return landed
    }

    /** 本批内刚被删掉的行如果还在返回值里就摘走（同一批内才会命中；跨批删的不关本批的事）。 */
    private fun retract(landed: ArrayList<DisplayRow>, removed: DisplayRow?) {
        if (removed != null && landed.lastOrNull() === removed) {
            landed.removeAt(landed.lastIndex)
        }
    }

    /**
     * PRINTN 续行的拼接结果 —— **全仓唯一一份**并入实现（装配层 `view/ConsoleScreen` 亦走这里）。
     *
     * 对应 `line.ShiftPositionX(prev.Buttons[^1].PointX + prev.Buttons[^1].Width)` 后
     * `line.ChangeStr(prev.Buttons + line.Buttons)`（spec: `EmueraConsole.cs:2955-2966`、
     * `ConsoleDisplayLine.cs:93-110`）：旧行段留在原位、新行段整体右移旧行的宽度，
     * 在整数网格上等价于「段序首尾相接、`cells` 相加」。
     * 标志位口径：入列的对象是**新行**（spec: `EmueraConsole.cs:2966` 的 `line.ChangeStr(...)`
     * 只换 `buttons` 数组、`:2969` 的 `displayLineList.Add(line)` 加的还是那个新行，
     * 而 `IsLogicalLine`/`IsTemporary` 都是只读、只在构造时赋值，spec: `ConsoleDisplayLine.cs:12,14,45-46`），
     * 所以四个标志**全部取新行**：`isLogicalLine`、`isLineEnd`（一次 flush 只有**末行**被写成
     * `isLastLineEnd`，spec: `PrintStringBuffer.cs:183`；`LINECOUNT` 的条件读的就是这个新对象，
     * spec: `EmueraConsole.cs:2971-2977`）、`temporary`、`align`（spec: `EmueraConsole.cs:2943`）。
     *
     * TODO(verify)：`prev.indentCells != 0` 时原版是「旧行按旧行对齐、新行按新行对齐后再拼接」，
     * 偏移只作用在各自那半；本函数把 `indentCells` 记成新行的值，绘制侧需要按段分别偏移才能
     * 逐像素等价。ALIGNMENT CENTER/RIGHT 与 PRINTN 的组合在语料里没找到实例，卡点：无可比读数。
     */
    private fun mergedRow(prev: DisplayRow, next: DisplayRow): DisplayRow {
        // 源按钮编号（[TextRun.buttonId]）只保证**行内**唯一（= 本行 buttons 下标），
        // 两条行拼成一行的时侯必须把后一段的编号整体抬到前一段之上，否则「前一行末按钮」
        // 与「后一行首按钮」会撞号 —— 值相同且列号相接时 ButtonLayer 会把它们并成一条，
        // 而原版是两条独立 `ConsoleButtonString`（PrintStringBuffer.cs:103-123）。
        val offset = (prev.runs.maxOfOrNull { it.buttonId } ?: -1) + 1
        return DisplayRow(
            runs = prev.runs + next.runs.map { if (it.buttonId >= 0) it.copy(buttonId = it.buttonId + offset) else it },
            cells = prev.cells + next.cells,
            isLogicalLine = next.isLogicalLine,
            isLineEnd = next.isLineEnd,
            align = next.align,
            indentCells = next.indentCells,
            temporary = next.temporary,
        )
    }

    /**
     * 段 → run：非 BUTTON 段一条 run 照抄；BUTTON 段按 `buttons` 的列区间切成一按钮一 run。
     *
     * 段文本与列区间的对应关系来自核心层的排版方式：`RowBuilder.put` 逐码点推进列号
     * （`LineBufferConsoleSink.kt` 的 `put`/`buildRows`），一个 `ButtonSpan` 的文本连续占列
     * `[startCol, endCol)`，样式相同的相邻按钮才会并成一条 Segment。
     *
     * **一条按钮可以横跨多个样式段**：原版 `ConsoleButtonString` 的 `StrArray` 就是若干个
     * `ConsoleStyledString`（`ConsoleButtonString.cs:19,296-299` 的 `createButton(cssList, …)`），
     * 自动按钮的整串分支（`ButtonStringCreator.cs:63-73`）与 `createButtons` 的按字符切分
     * （`PrintStringBuffer.cs:347-387`）都会把**不同样式**的节点装进同一个按钮。
     * 所以切分用「本条按钮还剩多少格」跨段累计，而不是每段重新按整条按钮宽度切。
     *
     * 每条带值的 run 同时带上**源按钮编号** [TextRun.buttonId] = 该按钮在本行 `buttons`
     * 列表里的下标：行内按钮区间两两不相交 ⇒ 下标即身份。跨样式段的同一按钮因此编号相同，
     * `button/ButtonLayer.attach` 才会把它们并回**一条**命中区；而两个值相同、列号相接的
     * 独立按钮编号不同，各自成条（条数口径 = `ConsoleDisplayLine.cs:18,28` 的数组长度，
     * 原版两次 `AppendButton` 是两条，`PrintStringBuffer.cs:103-123`）。
     */
    private fun runsOf(
        line: LineBufferConsoleSink.DisplayLine,
        focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
    ): List<TextRun> {
        val out = ArrayList<TextRun>(line.segments.size)
        val buttons = line.buttons
        var next = 0
        var remaining = if (buttons.isEmpty()) 0 else buttons[0].endCol - buttons[0].startCol
        for (seg in line.segments) {
            val kind = runKind(seg.kind)
            if (kind != RunKind.BUTTON) {
                out.add(runOf(seg, line, null, seg.text, -1, focusColor))
                continue
            }
            val text = seg.text
            var p = 0
            while (p < text.length && next < buttons.size && remaining > 0) {
                val cut = cutEnd(text, p, remaining)
                remaining -= Grid.cells(text.substring(p, cut))
                out.add(runOf(seg, line, buttons[next].value, text.substring(p, cut), next, focusColor))
                p = cut
                if (remaining <= 0) {
                    next++
                    remaining = if (next < buttons.size) buttons[next].endCol - buttons[next].startCol else 0
                }
            }
            if (p < text.length) {
                // 按钮区间用尽（或本段压根没有对应区间）后剩下的文本：仍按 BUTTON 段搬运，
                // 但不带值 —— ButtonLayer 对 buttonValue == null 的段不入命中表
                // （spec: `render/.../button/ButtonLayer.kt` 的 attach），宁可不命中也不要错命中。
                out.add(runOf(seg, line, null, text.substring(p), -1, focusColor))
            }
        }
        return out
    }

    /**
     * 单段的字段搬运：颜色/样式/字体/HTML 选项一律照抄，不做任何再解析。
     * [text] 是这条 run 实际承载的文本 —— 按钮段按 `Button` 列区间切过之后，
     * 每条 run 只带自己那一片，不带整段原文。
     * [buttonId] 是源按钮编号（-1 = 不带值/身份未知），口径见 [runsOf]。
     *
     * `back` 槽分两种语义：非按钮段落 `line.backColor`（SETBGCOLOR 的行快照），
     * **按钮段落 [focusColor]** —— 绘制侧把按钮段的 `back` 当原版 `ButtonColor` 用
     * （`view/ScreenComposer.glyphFore` 对 `ConsoleStyledString.cs:105-111`），而 `ButtonColor`
     * 的缺省就是 `Config.FocusColor`（spec: `StringStyle.cs:11,22`、`ConfigData.cs:70`）。
     * 若这里仍填行背景色（默认 0x000000），指着按钮时字色会被涂成背景色，黑底黑字不可见。
     */
    private fun runOf(
        seg: LineBufferConsoleSink.Segment,
        line: LineBufferConsoleSink.DisplayLine,
        buttonValue: Any?,
        text: String,
        buttonId: Int,
        focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
    ): TextRun {
        val kind = runKind(seg.kind)
        return TextRun(
            text = text,
            fore = seg.foreColor,
            back = if (kind == RunKind.BUTTON) focusColor else line.backColor,
            styleMask = seg.styleMask,
            fontName = seg.fontName,
            kind = kind,
            buttonValue = buttonValue,
            htmlOption = seg.htmlOption,
            buttonId = buttonId,
        )
    }

    /** `Kind` 与 [RunKind] 同名对应（TEXT/BUTTON/PLAIN/HTML/ERROR），按名字映射，不重排不合并。 */
    private fun runKind(kind: LineBufferConsoleSink.Kind): RunKind = RunKind.valueOf(kind.name)

    /** 从 [from] 起取「[Grid.cells] 累计格数首次达到 [wantCells]」的最短前缀右边界（不劈代理对）。 */
    private fun cutEnd(text: String, from: Int, wantCells: Int): Int {
        var k = nextCodePoint(text, from)
        while (k < text.length && Grid.cells(text.substring(from, k)) < wantCells) {
            k = nextCodePoint(text, k)
        }
        return k
    }

    /** 下一个码点的起始下标（代理对整体前进 2）。 */
    private fun nextCodePoint(s: String, i: Int): Int =
        if (i < s.length && Character.isHighSurrogate(s[i]) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
            i + 2
        } else {
            i + 1
        }

    /**
     * 零内容行：没有任何段，或所有段文本都为空（spec: `PrintStringBuffer.cs:198-201` 的空表分支）。
     *
     * 注意判据的**适用面**：补空格（[emptySpaceRun]）只对打印缓冲行成立；直印 HTML 行
     * （段 kind 为 [RunKind.HTML]）在原版不经 PrintFlush，空行文本保持 ""（见 [adapt]）。
     */
    private fun isEmptyLine(runs: List<TextRun>): Boolean = runs.all { it.text.isEmpty() }

    /** 空行补的那个空格（spec: `EmueraConsole.cs:3379-3382` 的 `Append(" ", Style)`）。 */
    private fun emptySpaceRun(line: LineBufferConsoleSink.DisplayLine): TextRun {
        val first = line.segments.firstOrNull()
        return TextRun(
            text = EMPTY_LINE_TEXT,
            fore = first?.foreColor ?: LineBufferConsoleSink.DEFAULT_FORE_COLOR,
            back = line.backColor,
            styleMask = first?.styleMask ?: 0,
            fontName = first?.fontName ?: LineBufferConsoleSink.DEFAULT_FONT_NAME,
            kind = first?.let { runKind(it.kind) } ?: RunKind.TEXT,
        )
    }
}

/**
 * 整屏快照的**逐行实例缓存**（DEV-05a）：[ConsoleAdapter.adapt]（List 入口）的带记忆版。
 *
 * ## 为什么键是实例身份而不是结构相等
 *
 * 生产路径每帧把整份日志重新 adapt 一遍（逐行新建 RenderLine、run、按钮切段的 substring），
 * 动画与长日志场景在 UI 线程产生 GC 抖动（logcat Skipped frames）。核心层的
 * [LineBufferConsoleSink.DisplayLine] 全字段 val（LineBufferConsoleSink.kt:205-214），
 * [LineBufferConsoleSink.Segment] 同（:139-146）；core 保留区的行实例在两次快照之间不变 ——
 * 追加只加新对象，CLEARLINE 与 MaxLog 只从两端摘对象（LineBufferConsoleSink.kt:688-711、
 * :1615-1618），PRINTN 并入用 copy 重建该行（:1607-1613）。这条「实例身份稳定」正是
 * [ConsoleFeed] 内联图形锚点表已经在依赖的事实，本缓存只是同一事实的第二个消费方。
 * 因此「同一实例」⇔「行内容未变」⇔ 可复用上一次的 [RenderLine]；而**结构相等的不同实例**
 * （CLEARLINE 之后重打的同文行）必须各自重建，不能把旧行的产物错配给新行 ——
 * 这与锚点表 [ConsoleFeed.adoptMergedTail] 用实例不用文字相等是同一条纪律。
 *
 * ## 失效路径（全部由「按当前快照重建整张表」自然覆盖，无需显式失效钩子）
 *
 * * **追加**：新行是新实例 → 未命中重建，旧行继续命中；
 * * **PRINTN 并入与改写**：core 用 copy 重建该行（新实例）→ 该行未命中，其余行命中；
 * * **CLEARLINE 与 MaxLog 淘汰**：行实例从快照消失 → 重建表时随之出表，不残留、不泄漏；
 *   之后同文新行是新实例 → 重建；
 * * **焦点色变化**：按钮 run 的 back 槽随 focusColor 走，换色等于整表作废。
 *
 * ## 输出等价性
 *
 * [ConsoleAdapter.adapt] 是纯函数（输入只有行与 focusColor，见该对象的线程模型节），
 * 命中返回的实例与重新 adapt 的结果逐字段相同，下游（flattenHtml 的实例复用、
 * ConsoleLogSync 的前缀比较、锚点重贴的行拷贝）消费的只是值 —— 渲染输出逐字节等价，
 * 本类不做任何语义决定（纯性能改动）。
 *
 * ## 状态与线程
 *
 * 只有实例字段 [cache] 与 [cacheFocus]，无静态可变状态（AGENTS.md 铁律 4）；
 * 单消费线程使用（与 [ConsoleFeed] 的其余实例字段同一约定，ADR-003）。
 * 表容量每帧等于快照长度，不随日志历史增长。
 */
internal class ConsoleAdaptCache {

    /** 上一次 adapt 的「行实例 → 产物」表；IdentityHashMap 取实例身份而非结构相等。 */
    private var cache: MutableMap<LineBufferConsoleSink.DisplayLine, RenderLine> = IdentityHashMap()

    /** [cache] 建表时的 focusColor；与请求值不同即整表作废（见类文档「焦点色变化」条）。 */
    private var cacheFocus: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR

    /**
     * 整屏 adapt：快照里每一行，命中缓存就原样复用上一次的 [RenderLine] 实例，
     * 未命中（新行、重建行、换焦点色）才走 [ConsoleAdapter.adapt] 逐行新建。
     * 返回顺序与条数和快照严格一致，与 List 入口逐项相同；同时按当前快照重建缓存表，
     * 上一次快照里存在、这一次不在的行随之出表。
     */
    fun adapt(
        snapshot: List<LineBufferConsoleSink.DisplayLine>,
        focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
    ): List<RenderLine> {
        val reusable = if (focusColor == cacheFocus) cache else null
        val fresh = IdentityHashMap<LineBufferConsoleSink.DisplayLine, RenderLine>(snapshot.size * 2 + 1)
        val out = ArrayList<RenderLine>(snapshot.size)
        for (line in snapshot) {
            val rendered = reusable?.get(line) ?: ConsoleAdapter.adapt(line, focusColor)
            fresh[line] = rendered
            out.add(rendered)
        }
        cache = fresh
        cacheFocus = focusColor
        return out
    }
}

/**
 * 快照 → [LogBuffer] 的增量提交器（M4-ADAPT 接线⑤「日志不断行」的落地件）。
 *
 * ## 为什么需要它
 *
 * 核心层给的是**整屏快照**（`LineBufferConsoleSink.snapshot()`，对应原版
 * `displayLineList`，spec: `EmueraConsole.cs:238`），而 PRINTN 的并入是**就地改写末行**
 * （spec: `EmueraConsole.cs:2947-2970`：`deleteLine(1)` 让出号、`Add` 再取回同一个号），
 * 快照条数因此不增长。调用方若把快照逐条 `append` 进日志，同一个逻辑行会被写两遍。
 * 本类记住上一次快照，按「最长相同前缀」找出真正变动的尾部，先回撤旧尾部再走
 * [ConsoleAdapter.appendMerged]，于是：
 * * 同一快照重复提交 → 零改动（幂等）；
 * * PRINTN 续行 → 末行被替换、绝对行号不变（spec: `EmueraConsole.cs:2945` + `:3013`）；
 * * 快照变短（CLEARLINE / DELETELINE）→ 尾部按**逻辑行**回撤（spec: `EmueraConsole.cs:2999-3051`；
 *   `removeLast` 一次删一整条，回撤次数 = 逻辑行组数，见 [submit]）。
 *
 * ## 两个入口 = 两种喂法（一个缓冲只用一种）
 *   * [submit]：调用方交**整屏快照**（核心层 `snapshot()` 的形态），本类负责差分与回撤；
 *   * [appendBatch]：调用方交**增量批**（已经知道哪些行是新的），本类只折行 + 入列，不比较、
 *     不动 [published]。装配层 `view/ConsoleScreen` 两条都提供（`submit` 与 `submitSnapshot`），
 *     但两条**不许混在同一个缓冲上**（理由见 [appendBatch] 的 KDoc）。
 *
 * ## 回撤为什么用 removeLast 而不是 replaceLast
 *
 * [LogBuffer.replaceLast] 是「原地换末行且保号」，`removeLast` + `append` 组合出来的
 * `totalLines`/`firstIndex`/`logicalLines` 与它逐项相等；选后者是因为原版就是**先删后加**
 * （spec: `EmueraConsole.cs:2954` 的 `deleteLine(1)` 与 `:2969` 的 `displayLineList.Add`），
 * 且折行行数可能不止 1 行，`replaceLast` 换不了多行。
 *
 * ## 状态
 *
 * 只有实例字段 [published]，无静态可变状态（AGENTS.md 铁律 4）；与 [LogBuffer] 一样假定
 * 单线程使用（ADR-003）。
 */
class ConsoleLogSync(
    private val buffer: LogBuffer,
    private val folder: LineFolder,
    private val drawableWidthCells: Int = folder.clientWidth,
) {

    /** 上一次提交的快照（折行之前的 `RenderLine` 列表）。 */
    private var published: List<RenderLine> = emptyList()

    /** 已提交的快照条数（诊断用；与 [buffer] 的行数不一定相等，折行会放大）。 */
    val publishedCount: Int
        get() = published.size

    /**
     * 提交一次整屏快照，返回本次**新落到缓冲尾部**的行（无变化返回空表）。
     *
     * 尾部回撤按**逻辑行**计次，不是物理排数：[LogBuffer.removeLast] 一次调用整条删掉一个
     * 逻辑行（直译 `deleteLine(1)` 的收口循环 —— 从尾部逐显示行出列、删到 `IsLogicalLine`
     * 首排才收口，spec: `EmueraConsole.cs:3007-3021`），所以循环次数必须是回撤区间里
     * 「逻辑行首排」（`isLogicalLine == true` 的排，spec: `PrintStringBuffer.cs:193,206`）的个数。
     * 按排数计会让折过的逻辑行整条多删（旧实现缺陷，由 `回撤按逻辑行计次` 系列用例钉住）。
     * 变化点若落在某逻辑行的**折行续排**上（首排在相同前缀里、续排变了），回撤与重放都从
     * 那条逻辑行的**首排**开始：整条删掉、再从首排重放入列 —— 重放时续排经
     * [ConsoleAdapter.appendMerged] 的并入分支拼回，行号逐排先退后进、总量守恒
     * （`deleteLine(1)` 的 `lineNo--`（spec: `EmueraConsole.cs:3013`）对重放 `Add` 的
     * `lineNo++`（spec: `:2970`））。
     *
     * 回撤旧尾部之后，新尾部一律交 [appendBatch] 入列 —— 并入规则只有一份（[ConsoleAdapter.appendMerged]）。
     *
     * TODO(verify)：快照侧只对「快照条目粒度」的变化做差分；核心层快照里一条逻辑行折出的
     * 各排是独立条目，若核心层折行宽与本类 `drawableWidthCells` 不一致，回撤重放后的折行
     * 形态会与快照不同（重放按本类宽度重折）。生产路径两边同为 199 格（`GoldenPipeline.PC_DRAWABLE_WIDTH`），
     * 形态一致；宽度分叉的调用方需自行保证两处口径相同。
     *
     * @param lines `ConsoleAdapter.adapt(snapshot())` 的结果，最旧在前
     */
    fun submit(lines: List<RenderLine>): List<DisplayRow> {
        val same = commonPrefixLength(published, lines)
        // 回撤起点：变化点（或收缩后的尾部）落在折行续排上时，回退到所属逻辑行的首排，
        // 让「整条逻辑行」一起回撤、重放也从首排开始（逻辑行粒度，EmueraConsole.cs:3007-3021）。
        var start = same
        while (start < published.size && start > 0 && !published[start].isLogicalLine) {
            start--
        }
        val staleRows = if (start < published.size) {
            ConsoleAdapter.fold(folder, published.subList(start, published.size), drawableWidthCells)
        } else {
            emptyList()
        }
        // removeLast 一次删一整条逻辑行 ⇒ 循环次数 = 回撤区间里逻辑行首排的个数
        val retractLines = staleRows.count { it.isLogicalLine }
        var removed = 0
        while (removed < retractLines && buffer.removeLast()) {
            removed++
        }
        published = lines
        return appendBatch(lines.subList(start, lines.size))
    }

    /**
     * 增量批入口：调用方交下来的就是「本次新增的行」而不是整屏快照时用这条
     * （`view/ConsoleScreen.submit` 走它）。折行 + 入列全部委托适配层，本函数只做顺序：
     * [ConsoleAdapter.fold]（带上 ALIGNMENT 的列偏移与 REUSELASTLINE 的临时行标记，
     * spec: `EmueraConsole.cs:2937-2944`）→ [ConsoleAdapter.appendMerged]
     * （临时行先删 `:2891-2893` + PRINTN 并入 `:2947-2968`）。
     *
     * 与 [submit] 的分工：本函数**不比较、不回撤、不动 [published]** ——
     * 一个喂法只用一种入口，两种喂法不许混在同一个缓冲上（混用会让 [submit] 的前缀比较把
     * 增量批漏过的行当成「没发过」而重放一遍）。切换喂法前先 [reset] 并自行把缓冲清空。
     *
     * @return 落到缓冲尾部的那些行（并入发生时条数少于入参）
     */
    fun appendBatch(lines: List<RenderLine>): List<DisplayRow> =
        ConsoleAdapter.appendMerged(buffer, ConsoleAdapter.fold(folder, lines, drawableWidthCells))

    /** 与 [LogBuffer.clear] 配对使用：丢掉记忆，下一次提交按全新快照处理。 */
    fun reset() {
        published = emptyList()
    }

    private fun commonPrefixLength(a: List<RenderLine>, b: List<RenderLine>): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] == b[i]) {
            i++
        }
        return i
    }
}
