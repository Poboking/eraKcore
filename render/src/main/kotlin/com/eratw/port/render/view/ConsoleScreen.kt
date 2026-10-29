package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.adapter.ConsoleLogSync
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.displaylist.DisplayList
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.scroll.Viewport

/**
 * 一屏控制台的装配体（M4-SCREEN）：把已合流的 `scroll` / `grid` / `displaylist` / `button`
 * 四路串成原版 `EmueraConsole` 的那个「日志缓冲 + 折行 + 显示列表 + 滚动条 + 按钮命中」整体，
 * 并额外产出**确定性文本快照**（M4 出口判据「三处截图与 PC 版行列位置一致」在纯 JVM 里的尺子，
 * `docs/eratw-项目实施方案.md` 的 M4 节；本机没有 Android SDK，截图级验收只能等真机）。
 *
 * ## 数据流（单向、无锁、无静态可变状态）
 *
 * ```
 * RenderLine ──ConsoleAdapter.fold（LineFolder + ALIGNMENT 偏移）──> DisplayRow
 *      ──ConsoleAdapter.appendMerged（临时行先删 + PRINTN 并入）──> LogBuffer
 *                                                                        │
 *                            Viewport + ConsoleAdapter 的取行入口 <───────┘
 *                                 │                              │
 *              topRowForDraw / rowsForDraw（DRAW_EXTRA_ABOVE）    topRowForHit / rowsForHit（HIT_EXTRA_ABOVE）
 *                                 │                              │
 *                       DisplayList.submit               ButtonLayer.attach
 *                                 │                              │
 *                           DirtyRect 列表                 HitTarget 查询
 * ```
 *
 * 各段职责与出处（本类只负责**接线与顺序**，不复算任何一段的语义）：
 * - 折行：[LineFolder] 是 `PrintStringBuffer.ButtonsToDisplayLines`（PrintStringBuffer.cs:196-266）
 *   的直译，宽度用 [Grid.cells] 的等宽字符网格（契约 §0.5 的两条口径之一）；本类经
 *   `ConsoleAdapter.fold` 调用它，顺带把 `align`/`indentCells`/`temporary` 落到 [DisplayRow]
 *   （EmueraConsole.cs:2937-2944 逐行 `SetAlignment`；偏移换算 `ConsoleDisplayLine.cs:50-90`）；
 * - 存储：[LogBuffer] 的淘汰与行号口径见其类文档（EmueraConsole.cs:2988-2996 丢最旧、
 *   :2945 + :2970 先行号后自增）；
 * - 并入：REUSELASTLINE 的临时行先删与 PRINTN 的「删尾行 + 拼成新行再入列」**都不在本类**，
 *   唯一一份在 `ConsoleAdapter.appendMerged`（EmueraConsole.cs:2891-2893 与 :2947-2968，
 *   被删行由 `deleteLine` 负责 :2999-3051；标志位全部取新行，见 `mergedRow` 的证据）；
 *   本类的两条入口（增量批 [submit]、整屏快照 [submitSnapshot]）都经
 *   `ConsoleLogSync` 走到那条，快照侧还多一层「最长相同前缀」差分（EmueraConsole.cs:238 的
 *   `displayLineList` 是整屏形态）；
 * - 视口：[Viewport] 把 WinForms 滚动条的 `Value`/`Maximum` 换成 `offsetFromEnd`
 *   （EmueraConsole.cs:2543-2567、:1786-1795）；
 * - 脏区：[DisplayList.submit] 的幂等不变量对应原版 `lastDrawnLineNo` 那对读写
 *   （EmueraConsole.cs:1758 早退、:1942-1949 收尾写水位）；
 * - 命中：[ButtonLayer] 吃显示行、产 [HitTarget]（EmueraConsole.cs:2361、:2439 + :2448）。
 *
 * ## 取行窗口：绘制与命中的**起行公式恒等**
 *
 * C# 两处写法不同、`num3` 逐值相同（恒等式 `(H - L) / L + 1 == H / L`，`H = MainPicBox.Height`、
 * `L = LineHeight`）：
 * - 绘制 `num3 = num2 - ((H - L) / L + 1)`（EmueraConsole.cs:1805-1807）；
 * - 命中 `num3 = num2 - H / L`（EmueraConsole.cs:2328-2331）。
 *
 * 本仓保留两条入口与两个常量，但它们**不是两套口径**：`ConsoleAdapter.HIT_EXTRA_ABOVE`
 * （0）给出与 C# 逐值相同的窗口 [hitRows]，`ConsoleAdapter.DRAW_EXTRA_ABOVE`（1）在同一个窗口
 * 之上多要一排**被画布完全裁掉**的行 —— 于是 `visibleRows().drop(1) == hitRows()`
 * （行数不足一屏、顶行夹到 0 时两支整体相同；屏幕即可见的行逐行相同）。
 * 逐值读数钉在 `adapter/ConsoleAdapterTest` 与 `scroll/ViewportAuditTest`。
 *
 * 两个窗口的下标基准分别是 [visibleRowBase] 与 [hitRowBase]（都相对保留区，0 起），
 * 换算入口 [hitRowToSnapshotRow]。**快照 [snapshotText] 走绘制口径** —— 它要对的是屏上的
 * 行列位置，而屏上画出来的正是绘制口径那几行。
 *
 * ## 行号权威（写死）
 *
 * [LogBuffer.firstIndex] 起的**绝对行号**是唯一权威序号。[DisplayList] 与 [ButtonLayer]
 * 里的行下标都只是「本次提交窗口内的位置下标」，换算式固定为：
 * `绝对行号 = log.firstIndex + 窗口基准下标 + 窗口内下标`，反向式同理；
 * 这两步的**实现**在适配层（`ConsoleAdapter.positionOf` / `lineNoAt`），本类的
 * [lineNumberToSnapshotRow] 与 [snapshotRowToLineNumber] 只是在它外面套一层「是否在绘制窗口内」。
 * 原版同一件事写作 `lineNo - shift`，`shift = (GetLineNo > MaxLog) ? GetLineNo - MaxLog : 0`
 * （EmueraConsole.cs:1865-1868、:2402），与 [LogBuffer.firstIndex] 逐项相等。
 *
 * ## 本类**不做**的事（各有归属，别在这里补）
 * - PRINTN 并入与临时行先删：唯一一份在 `ConsoleAdapter.appendMerged`（EmueraConsole.cs:2891-2893、
 *   :2947-2968），快照差分在 `ConsoleLogSync.submit`；本类两条入口都只按顺序调用它们。
 *   （ADAPT-CONSUME：装配层曾自带一份，两份的标志位口径已经分叉过，故本波收掉。）
 * - 取行多要的那一排：`extraAbove` 的 1 与 0 只在 `ConsoleAdapter.DRAW_EXTRA_ABOVE` /
 *   `HIT_EXTRA_ABOVE` 上（EmueraConsole.cs:1805-1807 对 :2328-2331，两式逐值恒等），本类不写死。
 * - 空行补空格：原版在 `PrintFlush` 缓冲区为空时先 `Append(" ", Style)`
 *   （EmueraConsole.cs:3379-3382），归适配层 `ConsoleAdapter.adapt`；本类收到零内容 `RenderLine`
 *   就一行都不追加（LineFolder 的 PrintStringBuffer.cs:198-201 口径）。
 * - 列偏移的**换算**：ALIGNMENT CENTER/RIGHT 的整行平移由 `ConsoleAdapter.alignmentShift` 算，
 *   经 `ConsoleAdapter.fold` 落到 [DisplayRow.indentCells]/[DisplayRow.align]（EmueraConsole.cs:2937-2944
 *   逐行 `SetAlignment` + ConsoleDisplayLine.cs:50-90）；本类只负责让这两个字段活着到日志里，
 *   **绘制与命中怎么消费它**归 DLIST-ALIGN（displaylist 目录与 button 目录，本波 d-align 在改）。
 *   PRINTC 的定宽补齐与之无关 —— 它在核心层就被补成空格，夹具里以**前导空格 run** 的形式出现
 *   （`CreateTypeCString`，EmueraConsole.cs:3246-3291，右对齐补前导空格见 :3267-3275，
 *   实测基准 `pc-golden-root.txt:431` 每格 25 半角格、5 格一行）。
 * - 样式重解析、颜色换算：段的 `fore`/`back`/`styleMask` 从 [RenderLine] 原样透传。
 * - 像素级排版：那是 [ScreenComposer] 与本包之外字形度量路（真实字体度量口径）的事。
 *
 * @param clientWidth 一行的**窗口**宽度（半角格，不是像素；黄金基准 1800px 在 9px 半角步进下
 *   = 200 格，步进 9px 由基准 `pc-golden-root.txt:253` 的 199 字符分隔条反推，
 *   见 `sprite/DrawLineBar.kt` 的类文档）。**画布裁剪与按钮占格标尺**用这个宽；
 *   折行与对齐读的是 [drawableWidthCells]，两者在原版就不是同一个数（见下）
 * @param maxLog 配置口径的日志上限，交 [LogBuffer.fromConfig] 解析（`Config.cs:451-455` 的
 *   500 下限与 `ConfigData.cs:62` 的 5000 默认都在**配置层**夹，裸构造不再夹取——LOG-FLOOR）；
 *   实际生效容量读 [log] 的 `maxLog`
 * @param drawableWidthCells 折行与 ALIGNMENT 对齐用的**可绘制宽度**（半角格），对应原版
 *   `Config.DrawableWidth = WindowX - max(2, FontSize/6)`（spec: `Config.cs:461-466`）——
 *   折行判据 `buttonList[i].PointX + buttonList[i].Width <= num` 的 `num` 取的是它而不是窗口宽
 *   （spec: `PrintStringBuffer.cs:205`），`SetAlignment` 的 CENTER/RIGHT 也读它
 *   （spec: `ConsoleDisplayLine.cs:80,83`）。黄金基准下它是 199 格而窗口是 200 格：
 *   3px 形状位移吃掉第 200 格（1797px ÷ 9px，见 [GOLDEN_DRAWABLE_WIDTH]）；默认等于
 *   [clientWidth]，黄金装配必须显式传 [GOLDEN_DRAWABLE_WIDTH]
 * @param heightLines 命中窗口的行数，默认 54 = 基准 `CLIENTHEIGHT=955`（:688）／
 *   语料 `emuera.config:23` 的 LINE HEIGHT 18 再按 EmueraConsole.cs:2328 取 `Height / LineHeight + 1`；
 *   绘制窗口在此之上多一排被画布裁掉的行（`ConsoleAdapter.DRAW_EXTRA_ABOVE`）
 * @param stats 帧统计（W8-RENDERPERF）；null（默认）时零开销。要 `render.frame ms=…` 的 `ms`
 *   覆盖「提交 + 绘制」整帧，把同一个实例交给 `view/ConsoleFrame`；只有 debug 构建才注入
 */
class ConsoleScreen(
    val clientWidth: Int,
    val maxLog: Int,
    val heightLines: Int = DEFAULT_HEIGHT_LINES,
    val drawableWidthCells: Int = clientWidth,
    val stats: FrameStats? = null,
) {

    init {
        require(clientWidth >= 1) { "clientWidth 必须至少 1 半角格，实到 $clientWidth" }
        require(drawableWidthCells >= 1) { "drawableWidthCells 必须至少 1 半角格，实到 $drawableWidthCells" }
    }

    /** 历史存储：折行后的显示行全部落在这里（保留区容量由 [LogBuffer] 决定）。 */
    val log: LogBuffer = LogBuffer.fromConfig(maxLog)

    /**
     * 折行器：等宽字符网格口径（契约 §0.5 的第一条）。宽度是 [drawableWidthCells] ——
     * 原版折行判据读 `Config.DrawableWidth`（PrintStringBuffer.cs:205），不是窗口宽；
     * `ConsoleLogSync` 的对齐偏移也从这个折行器取宽（`ConsoleDisplayLine.cs:80,83` 同读
     * `DrawableWidth`）。
     */
    val folder: LineFolder = LineFolder(drawableWidthCells)

    /**
     * 日志提交器（适配层件）：本类的两条入口都从它走 —— 增量批用
     * `ConsoleLogSync.appendBatch`、整屏快照用 `ConsoleLogSync.submit`，
     * 二者最终都落到 `ConsoleAdapter.appendMerged` 那**唯一一份**入列规则上
     * （EmueraConsole.cs:2891-2893 临时行先删、:2947-2968 PRINTN 并入）。
     * 声明顺序在 [log] 与 [folder] 之后：它俩就是它绑定的缓冲与折行器。
     */
    val logSync: ConsoleLogSync = ConsoleLogSync(log, folder)

    /** 保留显示列表：只装「本次提交窗口」的行，行数上限正好是绘制口径的行数。 */
    val list: DisplayList = DisplayList(clientWidth, maxRows = heightLines + ConsoleAdapter.DRAW_EXTRA_ABOVE)

    /** 滚动视口：唯一的滚动状态是 `offsetFromEnd`。 */
    val viewport: Viewport = Viewport(heightLines)

    /** 按钮命中层：行坐标是 [hitRows] 的下标。 */
    val buttons: ButtonLayer = ButtonLayer()

    private var visibleRowsCached: List<DisplayRow> = emptyList()
    private var hitRowsCached: List<DisplayRow> = emptyList()
    private var visibleRowBaseCached: Int = 0
    private var hitRowBaseCached: Int = 0

    /**
     * 提交一批逻辑行（**增量批**喂法）：折行 + 入列全部交 [logSync]（内部即
     * `ConsoleAdapter.fold` 与 `ConsoleAdapter.appendMerged`）→ 视口对齐 → 重建命中表 → 提交显示列表。
     *
     * 与 [submitSnapshot] 二选一，别在同一个实例上混着用（差分口径不同，理由见
     * `ConsoleLogSync.appendBatch` 的 KDoc）。
     *
     * @return 本次相对上次提交变化的行级脏区（绘制口径的行下标）；内容无变化时返回空列表
     *   （幂等不变量，对应 EmueraConsole.cs:1758 的「没有新行就不重绘」）
     */
    fun submit(lines: List<RenderLine>): List<DirtyRect> {
        logSync.appendBatch(lines)
        return refresh()
    }

    /**
     * 提交一次**整屏快照**（生产喂法：核心层 `LineBufferConsoleSink.snapshot()` 经
     * `ConsoleAdapter.adapt` 的产物，最旧在前；对应原版 `displayLineList` 的形态，
     * EmueraConsole.cs:238）。差分、回撤旧尾部、并入全部在 `ConsoleLogSync.submit` 里
     * （PRINTN 就地改写末行 ⇒ 快照条数不增，:2947-2970；快照变短 ⇒ 尾部按行回撤，:2999-3051），
     * 本方法只负责其后的视口／命中／显示列表那一段。
     *
     * @return 脏区，口径与 [submit] 相同；同一个快照重复提交返回**空列表**（幂等）
     */
    fun submitSnapshot(snapshot: List<RenderLine>): List<DirtyRect> {
        logSync.submit(snapshot)
        return refresh()
    }

    /**
     * 只重算视口之后的环节（滚动、改窗口高度、清空后调用）；不追加任何行。
     *
     * 原版滚动条变化后走的是同一趟：`verticalScrollBarUpdate`（EmueraConsole.cs:2543-2567）
     * 之后由 `RefreshStrings` 触发重绘，取行区间按新 `Value` 现算（:1805-1812）。
     * 两条取行入口（绘制 / 命中）与行号基准都出自适配层（`ConsoleAdapter.rowsForDraw` /
     * `rowsForHit` 与 `topRowForDraw` / `topRowForHit`），`extraAbove` 的 1 与 0 不在本类。
     * 两支窗口的可见行逐行相同：`visibleRows().drop(1) == hitRows()`。
     */
    /**
     * 供右侧「大按钮」栏枚举的**更宽窗口**：日志尾部最多 [limit] 行，含屏幕外的行。
     *
     * 为什么不用 [hitRows]：命中层只装**可见窗口**。存档/读档页一页约 49 行
     * （`隨時操作存檔.ERB:153-156` 的 40 槽位循环 + 槽位 1000 + 翻页行 + EXIT），
     * 屏幕只显示得下 44~45 行时，顶部 8~9 个槽位的按钮**不在命中层**，右侧栏就少列了它们
     * （主人实测：39 个存档只认到 31 个）。
     * 栏里的按钮点下去提交的是按钮**值**，不依赖可见性，所以这里取宽窗口是对的。
     *
     * 实现：临时建一个高度 = [limit] 的 [Viewport] 贴底取行（不触碰真实视口状态）。
     */
    /**
     * HTML 浮岛原文（HTML_PRINT_ISLAND / HTML_PRINT_ISLAND_CLEAR）。
     *
     * 渲染层目前不画浮岛（原版把它们贴在窗口固定位置），但右侧栏要枚举浮岛里的
     * `button value=N`：那类页面（div + button 画的面板）的按钮只在浮岛里，
     * 不在普通显示行里，漏了它们右侧栏就少一整屏按钮。
     *
     * 浮岛变化不自增 sink.revision（core 侧口径见 LineBufferConsoleSink KDoc），
     * 所以由 ConsoleFeed.pump 每帧单独搬一次。
     */
    @Volatile
    private var islands: List<String> = emptyList()

    /** 当前浮岛原文快照（只读）。 */
    fun islandHtml(): List<String> = islands

    /** 装配层 / ConsoleFeed 更新浮岛快照。 */
    fun updateIslands(list: List<String>) {
        islands = if (list.isEmpty()) emptyList() else list.toList()
    }

    /**
     * **当前这一页实际有多少行** —— 「适应屏幕」据此反推字号（见 `PageFitter.fit`）。
     *
     * ## 为什么不能写死行数
     *
     * 一页内容有多长取决于**当前页面**：标题页 3 行、存档页 49 行。写死 25 行则长页被切，
     * 写死 44 行则短页字小到看不清（2026-10-24/25 两次都栽在这上面）。玩家手机尺寸也各不相同，
     * 唯一的通用做法是**量内容**。
     *
     * ## 量法
     *
     * 自底向上累计连续行，遇到**空白行**就停：本作每个页面（[PRINT] 打出来的一屏）之后
     * 都有一条空行与前文分隔，空行即是页边界。日志里没有空行、或整屏都连续时，
     * 顶多量到 [maxRows] 行——上限以内的估计是安全的（字号宁小勿溢出）。
     *
     * @param maxRows 最多往上量多少行（默认 200：超过一屏容量很多页，再量也没意义）
     */
    fun pageExtentRows(maxRows: Int = 200): Int {
        val size = log.size
        if (size <= 0) return 0
        val n = minOf(size, maxRows.coerceAtLeast(1))
        val rows = log.rowsAt((size - n).toLong(), n)
        var count = 0
        for (i in rows.indices.reversed()) {
            val row = rows[i]
            // 「空行」只认**真的没有任何 run**：图片行的 run 是占宽用的等宽空格
            // （`HtmlRunFlatten` 的图形段负载），按 `text.isBlank()` 判会把它当空行，
            // 标题页那几行图片行就被漏掉了（主人 2026-10-25 指出）。
            val blank = row.runs.isEmpty()
            if (blank) {
                // 页尾的空行先跳过（它属于页与页之间的分隔），再来一条空行就是页边界
                if (count == 0) continue
                break
            }
            count++
        }
        return count
    }

    fun chipRows(limit: Int = CHIP_WINDOW_ROWS): List<DisplayRow> {
        val n = minOf(limit.coerceIn(1, CHIP_WINDOW_MAX), log.size)
        if (n <= 0) return emptyList()
        return log.rowsAt((log.size - n).toLong(), n)
    }

    fun refresh(): List<DirtyRect> {
        stats?.beginFrame()
        viewport.sync(log)
        visibleRowBaseCached = ConsoleAdapter.topRowForDraw(viewport)
        hitRowBaseCached = ConsoleAdapter.topRowForHit(viewport)
        visibleRowsCached = ConsoleAdapter.rowsForDraw(viewport, log)
        hitRowsCached = ConsoleAdapter.rowsForHit(viewport, log)
        buttons.attach(hitRowsCached)
        return list.submit(visibleRowsCached)
    }

    /**
     * 屏上要画出来的行（绘制入口：`extraAbove = ConsoleAdapter.DRAW_EXTRA_ABOVE`，
     * EmueraConsole.cs:1805-1807）。行下标 0 对应绝对行号 [visibleRowBase] + `log.firstIndex`；
     * 下标 0 那一排在画布上完全被裁掉，故 `visibleRows().drop(1) == [hitRows]`。
     */
    fun visibleRows(): List<DisplayRow> = visibleRowsCached

    /**
     * 参与命中检测的行（命中入口：`extraAbove = ConsoleAdapter.HIT_EXTRA_ABOVE`，
     * EmueraConsole.cs:2328-2331）—— 与 C# 的 `num3 = num2 - Height / LineHeight` 逐值相同。
     */
    fun hitRows(): List<DisplayRow> = hitRowsCached

    /** 绘制口径顶行的保留区下标（0 基；空视口时与 [hitRowBase] 一样为 0）。与 [visibleRows] 同批快照。 */
    val visibleRowBase: Int
        get() = visibleRowBaseCached

    /** 命中口径顶行的保留区下标。与 [hitRows] 同批快照。 */
    val hitRowBase: Int
        get() = hitRowBaseCached

    /**
     * 命中行下标 → 快照／[visibleRows] 行下标；不在绘制窗口内返回 null。
     * 绘制窗口的顶行下标比命中窗口**更靠前**（那一排被画布裁掉的溢出行在它前面），
     * 所以偏移是 `hitRowBase - visibleRowBase`：窗口够长、顶行没被夹到 0 时正好是
     * `ConsoleAdapter.DRAW_EXTRA_ABOVE - ConsoleAdapter.HIT_EXTRA_ABOVE`（见类文档「取行窗口」），
     * 行数不足一屏时两者同为 0、偏移归 0。
     */
    fun hitRowToSnapshotRow(hitRow: Int): Int? {
        if (hitRow < 0 || hitRow >= hitRowsCached.size) {
            return null
        }
        val snapshot = hitRow + (hitRowBase - visibleRowBase)
        return if (snapshot in visibleRowsCached.indices) snapshot else null
    }

    /**
     * 命中口径的 [HitTarget] → 绘制口径（行下标经 [hitRowToSnapshotRow] 换算，
     * 列区间原样保留）；不在绘制窗口内返回 null。
     * 脏矩形与绘制命令的行坐标是**绘制口径**，命中表是**命中口径**，
     * 绘制侧要用焦点态就必须先过这一步（不许把两个口径混成一个）。
     */
    fun mapToSnapshotRow(target: HitTarget?): HitTarget? {
        if (target == null) {
            return null
        }
        val row = hitRowToSnapshotRow(target.row) ?: return null
        return HitTarget(row, target.colStart, target.colEnd, target.value)
    }

    /** 绘制侧指（按）位置（命中口径行列）→ 绘制口径的焦点按钮；未命中／不在屏上返回 null。 */
    fun focusedTarget(pointing: Pair<Int, Int>?): HitTarget? =
        mapToSnapshotRow(pointing?.let { buttons.hit(it.first, it.second) })

    /**
     * 绝对行号（[LogBuffer] 的权威序号）→ 快照行下标；不在绘制窗口内返回 null。
     * 「行号 → 保留区下标」这一步本身是 `ConsoleAdapter.positionOf`（原版 `lineNo - shift`，
     * EmueraConsole.cs:1865-1868、:2402）；已被淘汰或还没生成的号由它返回 null，
     * 本方法只再判「在不在绘制窗口内」。
     */
    fun lineNumberToSnapshotRow(lineNumber: Long): Int? {
        val ring = ConsoleAdapter.positionOf(log, lineNumber) ?: return null
        return if (ring in visibleRowBase until visibleRowBase + visibleRowsCached.size) {
            ring - visibleRowBase
        } else {
            null
        }
    }

    /**
     * 快照行下标（绘制口径，[visibleRows] 的下标）→ 绝对行号；不在绘制窗口内返回 null。
     * [lineNumberToSnapshotRow] 的逆运算，「保留区下标 → 行号」这一步用
     * `ConsoleAdapter.lineNoAt`（跨淘汰稳定的比较与寻址都用它取到的绝对行号）。
     */
    fun snapshotRowToLineNumber(row: Int): Long? {
        if (row < 0 || row >= visibleRowsCached.size) {
            return null
        }
        return ConsoleAdapter.lineNoAt(log, visibleRowBase + row)
    }

    /**
     * 命中查询：[row] 是 [hitRows] 的下标（命中口径，不是快照行下标），[col] 是半角格列。
     * 不做类型筛选（`ButtonAcceptance.ANY`）。
     */
    fun hit(row: Int, col: Int): HitTarget? = buttons.hit(row, col)

    /** 带类型筛选的命中查询（数值等待期字符串按钮不算命中，EmueraConsole.cs:2439、:2456）。 */
    fun hit(row: Int, col: Int, acceptance: ButtonAcceptance): HitTarget? = buttons.hit(row, col, acceptance)

    /**
     * 长按提示：该格上 `title` 的原文（null = 无提示）。
     *
     * 原版 PC 用鼠标悬停取这份文本做 ToolTip（spec: `EmueraConsole.cs:1891-1917`）；
     * 安卓没有 hover，壳层改由长按调用本方法（见 `CanvasConsoleView.tooltipAt`）。
     * 查询走 [ButtonLayer.tooltip]：`<nonbutton title>` 这种不可点文本也带提示。
     */
    fun tooltip(row: Int, col: Int): String? = buttons.tooltip(row, col)

    /** 点击回环：命中则返回喂给 VM 的字符串（口径见 `button/ButtonLayer.kt` 的类文档）。 */
    fun click(row: Int, col: Int, acceptance: ButtonAcceptance = ButtonAcceptance.ANY): String? =
        buttons.hit(row, col, acceptance)?.let { buttons.selectedString(it, acceptance) }

    /**
     * **文本快照**：逐显示行（绘制口径，[visibleRows]）把该行的段文本按序拼起来。
     *
     * 确定性保证（单测 `快照字节级可复现` 断言同输入两次逐字节相同）：
     * - 行的来源是 [LogBuffer] 环形槽位的**追加序**（无 HashMap 迭代序参与）；
     * - 段的来源是 `DisplayRow.runs`，由 [LineFolder] 按输入段序原样落成，顺序即打印序；
     * - TAB 已在折行时展开成 8 空格（StringMeasure.cs:57-60），这里不再做任何二次处理；
     * - 不裁剪、不 trim、不排序：`cells` 超出 `clientWidth` 的溢出行原样出文本
     *   （列裁剪是 DisplayList 提交期的绘制侧行为，快照要的是「逻辑列位置」）。
     *
     * 列位置可读性：文本里的空格 run 就是列游标本身（半角 1 格、全角 2 格，
     * [Grid.cells]），所以「第几格开始」用 `Grid.cells(line.substring(0, i))` 数即可；
     * 要直接读按钮占的格子，用 [snapshotButtonCells]。
     */
    fun snapshotText(): List<String> = visibleRowsCached.map { row -> rowText(row) }

    /**
     * 按钮占格快照：与 [hitRows] 同口径、同行序，每行一条长度 [clientWidth] 的标尺串 ——
     * 按钮覆盖的半角格写 `B`，其余写 `.`（全角段在串里同样占两格）。
     *
     * 串上的下标是**屏上绝对列**：按钮区间来自 `ButtonLayer` 的 [HitTarget]，其列号在
     * `ButtonLayer.attach` 里已经以 [DisplayRow.indentCells] 为起点
     * （spec: `ConsoleDisplayLine.cs:86-90` + `ConsoleButtonString.cs:307-315`），
     * 所以标尺长度必须恒为 [clientWidth]，不能用 `row.cells` —— CENTER/RIGHT 行有按钮时
     * 按钮落在 `indentCells` 之后，按内容宽度截断会把 `B` 全部截掉（宽 20、CENTER、
     * 内容 "BTN" 时按钮在 9..12，内容宽只有 3）。
     *
     * 行内区间是**左闭右开**的半开区间（`button/ButtonLayer.kt` 类文档，
     * 换成整数网格后避免相邻按钮共用一格；原版像素口径两端都算命中，EmueraConsole.cs:2361）。
     */
    fun snapshotButtonCells(): List<String> {
        val out = ArrayList<String>(hitRowsCached.size)
        for (rowIndex in hitRowsCached.indices) {
            val marks = CharArray(clientWidth) { '.' }
            for (target in buttons.all()) {
                if (target.row != rowIndex) {
                    continue
                }
                val from = maxOf(target.colStart, 0)
                val until = minOf(target.colEnd, marks.size)
                for (cell in from until until) {
                    marks[cell] = 'B'
                }
            }
            out.add(String(marks))
        }
        return out
    }

    /** 快照的单一字符串形态：行间用 LF 连接（供夹具比对），行尾空格原样保留。 */
    fun snapshotTextJoined(): String = snapshotText().joinToString("\n")

    /**
     * 清空整屏（原版 `ClearDisplay`：列表清空、`logicalLineCount = 0`、`deletedLines = 0`、
     * `lineNo = 0`，EmueraConsole.cs:2801-2813），并把显示列表交一份空快照、滚动条回初始态、
     * 快照提交器的「上次快照」记忆一起丢掉（`ConsoleLogSync.reset`）。
     *
     * @return 清空产生的脏区（原版此处 `lastDrawnLineNo = -1` 强制整屏重绘，:2813 之后刷新）
     */
    fun clear(): List<DirtyRect> {
        log.clear()
        logSync.reset()
        viewport.onCleared()
        return refresh()
    }

    /** 一行的快照文本：段按打印序原样拼接，无分隔符、不 trim（TAB 已在折行时展开）。 */
    private fun rowText(row: DisplayRow): String {
        val sb = StringBuilder(row.cells.coerceAtLeast(0))
        for (run in row.runs) {
            sb.append(run.text)
        }
        return sb.toString()
    }

    companion object {

        /** 右侧栏枚举窗口的默认行数（够覆盖一整个长页面的按钮）。 */
        const val CHIP_WINDOW_ROWS: Int = 64

        /** 右侧栏枚举窗口上限（防极端配置把整本日志扫一遍）。 */
        const val CHIP_WINDOW_MAX: Int = 256

        /**
         * 绘制入口比命中入口多要的行数 —— 适配层两个常量之差
         * （`ConsoleAdapter.DRAW_EXTRA_ABOVE` 对 `HIT_EXTRA_ABOVE`）。
         * 这一排**在画布上完全被裁掉**（C# 两式的 `num3` 逐值恒等，EmueraConsole.cs:1805-1807
         * 对 :2328-2331），所以它是「同一窗口多取一排」，不是第二套可见口径。
         * 本类不再自己写 1 与 0；要读那两个数请用适配层常量本身。
         */
        const val EXTRA_ABOVE_ROWS: Int = ConsoleAdapter.DRAW_EXTRA_ABOVE - ConsoleAdapter.HIT_EXTRA_ABOVE

        /**
         * 基准窗口高 955（`pc-golden-root.txt:688`）／行高 18（`emuera.config:23`）的命中口径行数。
         * 黄金窗口尺寸在本仓只有这一处字面量，`ScreenComposer` 的同名常量引用它。
         */
        const val DEFAULT_HEIGHT_LINES: Int = 955 / 18 + 1

        /**
         * 黄金基准的可用宽度（半角格）：1800px（基准 `:689`）／9px 半角步进
         * （步进由基准 `:253` 的 199 字符分隔条反推，见 `sprite/DrawLineBar.kt`）。
         * 只作**窗口宽**（画布裁剪、按钮标尺），不进折行器 —— 折行读 [GOLDEN_DRAWABLE_WIDTH]。
         */
        const val GOLDEN_CLIENT_WIDTH: Int = Grid.DEFAULT_CLIENT_WIDTH / 9

        /**
         * 黄金基准的折行／对齐宽度（半角格）：`Config.DrawableWidth = WindowX - max(2, FontSize/6)`
         * = 1800px - 3px = 1797px（spec: `Config.cs:461-466`），9px 半角步进下 = 199 格。
         * 原版折行判据 `PointX + Width <= num` 的 `num` 就是它（spec: `PrintStringBuffer.cs:205`），
         * CENTER/RIGHT 的整行平移也读它（spec: `ConsoleDisplayLine.cs:80,83`）；
         * 与核心层黄金管线的 `GoldenPipeline.PC_DRAWABLE_WIDTH`（199）同一口径。
         * 差的那 1 格：窗口 200 格里最后 3px 被形状位移吃掉，第 200 格装不进 DrawableWidth。
         */
        const val GOLDEN_DRAWABLE_WIDTH: Int = 1797 / 9
    }
}

/**
 * 构造一行的便捷工厂（夹具与真机绑定层共用）。
 *
 * 样式一律**入队时固定**，不在渲染期回看当前样式 —— 原版 `ConsoleStyledString` 在构造时就
 * 抄下 Color／FontStyle／Fontname（ConsoleStyledString.cs:26-43），所以 SETCOLOR 只影响后续段。
 */
object ScreenRows {

    /** 默认前景色 `0xC0C0C0`：基准实测 `DEF=` 读数（`pc-golden-root.txt:269`，GETDEFCOLOR）。 */
    const val DEFAULT_FORE: Int = 0xC0C0C0

    /** 默认背景色 `0x000000`：基准实测 `BGRESET=` 读数（`pc-golden-root.txt:271`，GETDEFBGCOLOR 见 :270）。 */
    const val DEFAULT_BACK: Int = 0x000000

    /** 基准默认字体名：`pc-golden-root.txt:279-280` 的 `FONT=`／`FONT_DEFAULT=`。 */
    const val DEFAULT_FONT: String = "黑体"

    /** 一段普通文本。 */
    fun run(
        text: String,
        fore: Int = DEFAULT_FORE,
        back: Int = DEFAULT_BACK,
        styleMask: Int = 0,
        fontName: String = DEFAULT_FONT,
        kind: RunKind = RunKind.TEXT,
        buttonValue: Any? = null,
        focusFore: Int = TextRun.DEFAULT_FOCUS_COLOR,
    ): TextRun = TextRun(
        text = text,
        fore = fore,
        back = back,
        styleMask = styleMask,
        fontName = fontName,
        kind = kind,
        buttonValue = buttonValue,
        focusFore = focusFore,
    )

    /**
     * 前导空白段：占 [cells] 个半角格（不足一格的那个半角位会被舍掉，
     * 因为空白本身按半角计，1 格 = 1 个空格）。
     *
     * 这是本波表达「列偏移」的唯一手段 —— `DisplayRow` 还没有列偏移／对齐字段（Kernel.kt 归
     * c-adapt 独占），而原版 PRINTC 居中／右对齐本来就是把空格写进段文本里
     * （EmueraConsole.cs:3267-3279，实测基准 `:431` 每格 25 半角格、`:259` 的 23 个前导空格）。
     */
    fun pad(cells: Int, fontName: String = DEFAULT_FONT): TextRun =
        run(text = " ".repeat(if (cells < 0) 0 else cells), fontName = fontName)

    /** 数值按钮段（PRINTBUTTON 的 long 重载，EmueraConsole.cs:3301-3307）。 */
    fun button(text: String, value: Long): TextRun = run(text = text, kind = RunKind.BUTTON, buttonValue = value)

    /** 字符串按钮段（PRINTBUTTON 的 string 重载，EmueraConsole.cs:3293-3299）。 */
    fun button(text: String, value: String): TextRun = run(text = text, kind = RunKind.BUTTON, buttonValue = value)

    /**
     * 一条完整逻辑行。[isLogicalLine] 默认 true；PRINTN 续接行传 false
     * （`isLogicalLine` 只有逻辑行首行为 true，PrintStringBuffer.cs:193、:206）。
     */
    fun line(
        vararg runs: TextRun,
        isLogicalLine: Boolean = true,
        isLineEnd: Boolean = true,
    ): RenderLine = RenderLine(runs = runs.toList(), isLogicalLine = isLogicalLine, isLineEnd = isLineEnd)

    /**
     * 一条文本行（单个段）。空串原样交下去 —— 折行器对零内容行返回空列表
     * （PrintStringBuffer.cs:198-201），原版靠调用侧先塞一个空格（EmueraConsole.cs:3379-3382），
     * 那是适配层的活，本函数**不代做**；要复刻屏上空行请传 `" "`（基准 `:65` 实测就是一个空格）。
     */
    fun textLine(
        text: String,
        fore: Int = DEFAULT_FORE,
        back: Int = DEFAULT_BACK,
        isLogicalLine: Boolean = true,
        isLineEnd: Boolean = true,
    ): RenderLine = line(
        run(text = text, fore = fore, back = back),
        isLogicalLine = isLogicalLine,
        isLineEnd = isLineEnd,
    )
}
