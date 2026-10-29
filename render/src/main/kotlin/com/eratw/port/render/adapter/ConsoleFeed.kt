package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import java.util.IdentityHashMap

/**
 * 核心层行缓冲 → 渲染层屏幕的整屏快照桥（CONSOLE-FEED）。
 *
 * ## 数据流
 *
 * `LineBufferConsoleSink.lines()`（原版 `displayLineList`，EmueraConsole.cs:238）
 * → [ConsoleAdapter.adapt]（段/按钮/对齐/临时行原样搬运，见该对象的类文档）
 * → [ConsoleScreen.submitSnapshot]（差分与 PRINTN 并入在 `ConsoleLogSync.submit`，
 * EmueraConsole.cs:2947-2970；快照变短时的回撤见 :2999-3051）。
 *
 * 本类只做两件事：**判断要不要交**（读 [LineBufferConsoleSink.revision]）与
 * **交哪一份**（[LineBufferConsoleSink.snapshot] 的当前值）。差分、折行、入列、脏区一律不在这里
 * 重写 —— 它们的唯一实现分别在 `ConsoleLogSync`、`LineFolder`、`ConsoleAdapter.appendMerged`
 * 与 `DisplayList`（见 [ConsoleScreen] 的类文档）。
 *
 * ## 幂等
 *
 * 原版「没有新行就不重绘」的形态是 `RefreshStrings` 在 `lastDrawnLineNo == lineNo` 时早退
 * （EmueraConsole.cs:1758），画完再把水位写成 lastDrawnLineNo（:1942-1949）。本桥把这条水位
 * 换成 [LineBufferConsoleSink.revision]：同一 revision 重复 [pump] 直接返回空表，
 * 不触碰 [screen] 的任何状态。
 *
 * ## 线程模型
 *
 * 单生产（VM 线程写 [sink]）单消费（渲染线程调 [pump]），**不加锁、不启线程**（ADR-003）。
 * 本类没有静态可变状态；跨线程发布仍由调用方做安全移交（把本对象整体交给渲染线程后再独占使用），
 * 本类不提前造锁。
 *
 * ## 宽度
 *
 * 折行宽度与显示宽度必须同源，否则列号会漂：构造时要求
 * `sink.clientWidth == screen.clientWidth`，不一致直接抛 [IllegalArgumentException]。
 * 尺寸变化后重建了 [ConsoleScreen] 的调用方应新造一个本对象（或用 [force] 重交一次）。
 *
 * @param sink 生产侧行缓冲（`:core-vm` 的 `LineBufferConsoleSink`）
 * @param screen 消费侧装配体（`:render` 的 `ConsoleScreen`）；本类不改它的任何配置
 * @param graphics 宿主图形层（M10-GFX）；非 null 时每次提交都做两件事：
 *   ① 把 `HTML_PRINT` 段里的 `<img>` / `<shape>` 展平成图形段（见 [HtmlRunFlatten]），
 *   ② 排空 [GraphicsLayer] 的内联队列，把 `PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE`
 *   按**锚点表**持久化到对应显示行（见 [attachInline]）。null（默认）= 两条都不做，
 *   与接入前逐项相同。
 * @param metrics 像素 ↔ 半角格换算器；**必须**与绘制侧（`CanvasConsoleView`）同一份，
 *   图形段的占宽换算与它同源。仅在 [graphics] 非 null 时用到。
 *   绘制侧「适应屏幕」改字号后由 [updateLayout] 更新并重展平，不要停留在构造期快照。
 * @param sizeSource `<img>` 缺 `width` 时按纵横比推宽所需的基图尺寸来源（同上）
 */
class ConsoleFeed(
    val sink: LineBufferConsoleSink,
    val screen: ConsoleScreen,
    private val graphics: GraphicsLayer? = null,
    private var metrics: CellMetrics = CellMetrics.DEFAULT,
    private val sizeSource: HtmlRunFlatten.ImageSizeSource? = null,
    /** 竖带图块（首页标题图）合并：可用宽 / 容许块高（像素）；0 = 不合并（默认关）。 */
    private var bandAvailWidthPx: Int = 0,
    private var bandMaxHeightPx: Int = 0,
    /**
     * 脚本 `px` 的基准字号（= 引擎生效 `Config.FontSize`），透传给 [HtmlRunFlatten]，
     * 让 `<img>` / `<shape>` 的 `px` 形态与百分比形态同尺（口径见
     * [com.eratw.port.render.glyph.CellMetrics.scriptPixels]）。
     * 0（缺省）= 不折算，与接入前逐值相同；**不随 [updateLayout] 的字号换代丢失**。
     */
    private val scriptFontPixelSize: Int = 0,
) {

    init {
        require(sink.clientWidth == screen.clientWidth) {
            "ConsoleFeed 的宽度必须一致：sink.clientWidth=${sink.clientWidth}，" +
                "screen.clientWidth=${screen.clientWidth}"
        }
    }

    /** 只有挂了图形层才建展平器（演示路径与全部既有测试的构造点零开销、零行为变化）。 */
    private var flattener: HtmlRunFlatten? =
        graphics?.let { HtmlRunFlatten(metrics, sizeSource, scriptFontPixelSize) }

    /**
     * 更新图形度量（字号 / 行高 / 窗口宽 / 竖带块高）并按新度量**重交一次**当前快照。
     *
     * 「适应屏幕」会在页面加载后改字号（真机 36px → 30px）。展平发生在 [publish] 里，
     * `<img>` 的缺省高与占宽格数都取自构造期的 [metrics]；不更新的话图片仍按旧字号
     * 排版，和新的行高对不上（带与带重叠、压到下一行文字）。这里换掉展平器再
     * [force] 一次，让已发布的行重新展平 —— `ConsoleScreen.submitSnapshot` 幂等，
     * 内容没变只会得到空脏区。
     *
     * 只在渲染线程（[pump] 的同一消费者）调用。
     *
     * @return 是否有参数变化并已重交
     */
    fun updateLayout(
        metrics: CellMetrics,
        bandAvailWidthPx: Int,
        bandMaxHeightPx: Int,
    ): Boolean {
        // CellMetrics 没有重写 equals（普通类），只能逐字段比。
        if (this.metrics.fontPixelSize == metrics.fontPixelSize &&
            this.metrics.lineHeight == metrics.lineHeight &&
            this.metrics.cellPixelWidth == metrics.cellPixelWidth &&
            this.bandAvailWidthPx == bandAvailWidthPx &&
            this.bandMaxHeightPx == bandMaxHeightPx
        ) {
            return false
        }
        this.metrics = metrics
        this.bandAvailWidthPx = bandAvailWidthPx
        this.bandMaxHeightPx = bandMaxHeightPx
        flattener = graphics?.let { HtmlRunFlatten(metrics, sizeSource, scriptFontPixelSize) }
        force()
        return true
    }

    /** 上一次竖带合并的读数（调试通道 `view`）。 */
    private var dbgBand: String = "band=off"
    private var dbgBandFirst: String = "-"

    /** 竖带合并的排障读数：窗口参数、首条带尺寸、合并前后行数。 */
    fun bandDebug(): String = dbgBand

    private fun firstBandSize(lines: List<RenderLine>): String {
        for (line in lines) {
            val run = line.runs.singleOrNull() ?: continue
            if (run.kind != RunKind.IMAGE) continue
            val g = run.graphic as? InlineGraphic.Image ?: continue
            return "${g.widthPx}x${g.heightPx}"
        }
        return "-"
    }

    /**
     * 已发布的 revision；null = 还没交过。首次 [pump] 必须无条件交一次，
     * 否则「先打印、后接线」的启动场景会永远漏掉开场内容。
     */
    private var publishedRevision: Long? = null

    /**
     * 已搬进 [screen] 的浮岛水位（-1 = 还没搬过，首次 [pump] 必须无条件搬一次）。
     *
     * 口径见 `LineBufferConsoleSink.htmlIslandsVersion` 的 KDoc：浮岛不参与 [LineBufferConsoleSink.revision]，
     * 但**内容**只在 `HTML_PRINT_ISLAND` / `HTML_PRINT_ISLAND_CLEAR` 时变，按水位搬即可。
     */
    private var publishedIslandsVersion: Long = -1L

    /**
     * 持久内联图形锚点表（GFX-04 路线 2）—— 语义、逐形态纪律与证据见 [attachInline] 的 KDoc。
     * 按建立顺序排列：同一行上的多批图形按这个顺序重贴。
     */
    private val inlineAnchors = ArrayList<InlineAnchor>()

    /**
     * 上一次发布时**底表**（未贴内联图形）的末行实例。PRINTN 并入只发生在末行
     * （LineBufferConsoleSink.kt:1576-1581 判的正是 `displayLines.last()`），
     * 认领并入后的新行时用它做门槛（见 [adoptMergedTail]）。
     */
    private var previousTail: LineBufferConsoleSink.DisplayLine? = null

    /**
     * 整屏 adapt 的逐行实例缓存（DEV-05a）。每帧对整份日志重新 adapt（逐行新建
     * RenderLine、run、按钮切段的 substring）是动画与长日志 UI 线程 GC 抖动的来源之一；
     * 行实例在两次快照之间稳定（与上方锚点表依赖的是同一条 core 事实），行内容未变就
     * 不重建。失效路径与输出等价性见 [ConsoleAdaptCache] 的类文档。
     */
    private val adaptCache = ConsoleAdaptCache()

    /**
     * 有变化才交：revision 与上次发布相同返回空表（幂等）；否则把当前整屏快照交给 [screen]，
     * 返回 [ConsoleScreen.submitSnapshot] 的脏区，并记住这次发布的 revision。
     *
     * 单生产单消费：读 revision 与取快照之间不允许生产侧再写 [sink]（ADR-003 的线程约定）。
     */
    fun pump(): List<DirtyRect> {
        // 浮岛不参与 revision（core 侧口径），但要每帧「问一次」。2026-10-29 起不再无条件搬：
        // `sink.htmlIslands()` 是加锁 + 整表拷贝，而地图/外出屏的浮岛可能有几十条字符串
        // （`HTML_PRINT_ISLAND` 画的面板），每帧拷一次纯属给 UI 线程制造分配。改成按水位搬，
        // 内容没变就一次 Long 比较（见 `LineBufferConsoleSink.htmlIslandsVersion`）。
        val islandsVersion = sink.htmlIslandsVersion()
        if (islandsVersion != publishedIslandsVersion) {
            publishedIslandsVersion = islandsVersion
            val islands = sink.htmlIslands()
            if (islands.isNotEmpty() || screen.islandHtml().isNotEmpty()) screen.updateIslands(islands)
        }
        val rev = sink.revision
        val pending = graphics?.hasPendingInline() == true
        if (rev == publishedRevision && !pending) return emptyList()
        return publish(rev)
    }

    /**
     * 忽略 revision 重交一次（首帧 / 尺寸变化 / 屏幕被别的路径清过之后用）。
     * 内容没变时 [ConsoleScreen.submitSnapshot] 本身幂等，返回空表。
     */
    fun force(): List<DirtyRect> {
        publishedIslandsVersion = sink.htmlIslandsVersion()
        screen.updateIslands(sink.htmlIslands())
        return publish(sink.revision)
    }

    /**
     * 有没有已到达但还没发布进 [screen] 的内容（`sink.revision` 前进或内联段排队）。
     *
     * UI 重绘循环的「要不要 invalidate」判据就该用它而不是真的 [pump] 一次：pump 是
     * **发布动作**（发布后 [publishedRevision] 立即前移，脏区信息随之丢失），循环侧
     * 只需要布尔答案。2026-10-22 真机收口：循环每帧 pump 会把「发布」与「询问」混在一起。
     */
    fun hasUnpublishedContent(): Boolean {
        val pending = graphics?.hasPendingInline() == true
        return sink.revision != publishedRevision || pending
    }

    private fun publish(rev: Long): List<DirtyRect> {
        // 快照只取一次：锚点表按**同一批** DisplayLine 实例寻址（见 attachInline），
        // 与适配、展平共用这份列表才不会在两次读之间把行对象换掉。
        val snapshot = sink.snapshot()
        var lines = adaptCache.adapt(snapshot)
        val layer = graphics
        val flat = flattener
        if (flat != null) lines = flattenHtml(lines, flat)
        if (layer != null) {
            val incoming = layer.drainInline()
            // 没锚点也没新段（绝大多数帧）不进锚点表，省掉一次 IdentityHashMap 分配。
            if (inlineAnchors.isNotEmpty() || incoming.isNotEmpty()) {
                lines = attachInline(snapshot, lines, incoming)
            }
        }
        // 竖带图块（首页标题图 35 条横带）合并成一张等比缩放的图；未配置时零开销。
        //
        // 必须在 [attachInline] **之后**：锚点表的行下标来自未合并的 `snapshot`，先合并
        // 再贴锚点会按错位的下标插 run（或越界）。合并后的块行因此天然不会带内联图形
        // （`runs.size != 1` ⇒ 不参与合并，宁可漏合）。
        if (bandAvailWidthPx > 0 && bandMaxHeightPx > 0) {
            val before = lines.size
            dbgBandFirst = firstBandSize(lines)
            lines = ImageBandMerge.merge(
                lines = lines,
                lineHeightPx = metrics.lineHeight,
                cellPixelWidth = metrics.cellPixelWidth,
                availWidthPx = bandAvailWidthPx,
                maxBlockHeightPx = bandMaxHeightPx,
            )
            dbgBand = "bandW=$bandAvailWidthPx bandH=$bandMaxHeightPx band=$dbgBandFirst:$before->${lines.size}"
        }
        val rects = screen.submitSnapshot(lines)
        previousTail = snapshot.lastOrNull()
        publishedRevision = rev
        return rects
    }

    /** 逐行展平 HTML 段；行（runs 与直印 HTML 的 align/noBreak）没变化时**复用同一个 RenderLine 实例**
     *  （幂等判脏要看结构相等，这里省的是拷贝）。 */
    private fun flattenHtml(lines: List<RenderLine>, flat: HtmlRunFlatten): List<RenderLine> {
        var result: MutableList<RenderLine>? = null
        for (i in lines.indices) {
            val line = lines[i]
            val newLine = flat.flattenLine(line)
            if (newLine !== line) {
                if (result == null) result = ArrayList(lines)
                result[i] = newLine
            }
        }
        return result ?: lines
    }

    /**
     * 把 [GraphicsLayer] 排空出来的内联图形段接进当前快照，并**持久**贴着锚点行走。
     *
     * ## 原版语义
     *
     * 图形段是打印缓冲里的**节点**：`PrintImg` 把 `ConsoleImagePart` append 进 `printBuffer`
     * （`EmueraConsole.cs:3192-3195`），`PrintShape` 同理 append `ConsoleShapePart`
     * （`:3197-3200`），随该行进 `displayLineList`（`:238`）后**永久留在日志里**。
     * 本桥拿到的是与文字流分离的独立队列（`GraphicsLayer.inline`），排空一次就没了；
     * 只贴当前帧的话，下一次控制台更新这批 run 就不在快照里 —— `PRINT_IMG` /
     * `PRINT_RECT` / `PRINT_SPACE` 只亮一帧，图形段把行折宽时消失还会连带下面几行上移。
     * HTML 的 `<img>` 不受影响：原文仍在 sink 的行文本里，每次 [publish] 都经
     * [flattenHtml] 重新展平。
     *
     * ## 路线 2：锚点表（只在本类，不碰 core）
     *
     * [inlineAnchors] 记「这批图形 run 属于哪一条显示行」。锚点用的不是可变下标，而是
     * `sink.snapshot()` 里那条 [LineBufferConsoleSink.DisplayLine] **实例本身**：核心层保留区
     * 的行对象在两次快照之间不变（追加只加新对象，CLEARLINE 与 MaxLog 只从两端摘对象，
     * 见 LineBufferConsoleSink.kt:688-711、:1615-1618），实例身份因此在「行还在不在」这件事上
     * 稳定。每次 [publish] 都按实例找回行下标、把持久 run 重新贴回去，连续两帧（乃至任意多帧）
     * 都不丢。
     * TODO(verify)：core 若按路线 1 给 `DisplayLine` 补行号或行 id（本类现在拿不到 sink 的
     * 绝对行号），锚点身份应换成那个 id；卡点：`LineBufferConsoleSink` 不暴露行号。
     *
     * 插入位置 [InlineAnchor.insertAt] = 锚定当时该行**基础 run 数**（即打印游标所在处；
     * 原版节点顺序里图形排在它之后打印的文本前面）。行被 PRINTN 续写后图形因此留在旧文字与
     * 续写文字之间，而不是漂到行尾。
     *
     * ## 逐形态的锚点纪律
     *
     * - **PRINTN 并入**：core 的 `appendGroup` 在末行 `isLineEnd == false` 时用 `mergeRows`
     *   重建该行（LineBufferConsoleSink.kt:1576-1581、:1607-1613 的
     *   `next.copy(segments = prev.segments + next.segments)`；原版是 `deleteLine(1)` 让号
     *   再 `Add` 取回同一个号，`EmueraConsole.cs:2947-2970`、`:3013`）。旧对象消失，但旧行的
     *   `Segment` 实例原样留在新行前缀里，[adoptMergedTail] 按「同一批实例为前缀」认领，
     *   锚点跟着同一逻辑行走。
     * - **CLEARLINE**：core 从尾部按逻辑行摘对象（LineBufferConsoleSink.kt:688-711，
     *   原版 `EmueraConsole.cs:2999-3051`）；实例找不回、也不是并入形态 ⇒ 锚点作废，
     *   不残留悬空图形。
     * - **MaxLog 淘汰**：core 从头部丢最旧行（LineBufferConsoleSink.kt:1615-1618，
     *   原版 `EmueraConsole.cs:2988-2996`）；同理作废，绝不会把图形贴到前面或后面别的行。
     * - **临时行**（REUSELASTLINE）：临时行被下一次正常输出摘掉
     *   （LineBufferConsoleSink.kt:1571-1574，原版 `EmueraConsole.cs:2891-2893`），
     *   锚在它上面的图形随之消失。
     *
     * ## 空屏
     *
     * 屏上还没有行就遇到 `PRINT_IMG` 时，锚点没有源行（[InlineAnchor.line] 为 null），
     * 输出一条合成行接住图形（既有「空屏时内联段自建一行」形态）；第一条真实行出现后，
     * 合成锚点挂到它的**行首**（原版图形节点在打印缓冲里先于后续文本，
     * `EmueraConsole.cs:3192-3195`）。
     *
     * ## 与文字流仍是两条流的近似
     *
     * 图形 sink 与 `ConsoleSink` 在 core 里没有汇合点（路线 1 才补），所以「图形在行内的精确
     * 节点位置」只能是近似：本层取「锚定时刻该行的 run 数」为插入点；若 `PRINT_IMG` 之前还有
     * 未 flush 的 PRINTN 文本、之后与图形一起落进同一条行，图形会排在那截文本之前，与原版
     * 节点序不一致。
     * TODO(verify)：上述形态需要真机截图核对；卡点：render 侧读不到 sink 打印缓冲的内容。
     */
    private fun attachInline(
        snapshot: List<LineBufferConsoleSink.DisplayLine>,
        lines: List<RenderLine>,
        incoming: List<InlineGraphic>,
    ): List<RenderLine> {
        // 行实例 → 当前快照下标。IdentityHashMap：行对象是 data class，这里要的正是**同一实例**
        // 而不是结构相等（同文行在日志里可以有很多条）。
        val positionOf = IdentityHashMap<LineBufferConsoleSink.DisplayLine, Int>(snapshot.size * 2 + 1)
        for (i in snapshot.indices) positionOf[snapshot[i]] = i

        // 1) 既有锚点逐条找回目标行；找不回（行被清/被淘汰/临时行被替换）就随行作废。
        val live = ArrayList<InlineAnchor>(inlineAnchors.size + 1)
        val target = ArrayList<Int>(inlineAnchors.size + 1)
        for (a in inlineAnchors) {
            val src = a.line
            if (src == null) {
                if (snapshot.isEmpty()) {
                    live.add(a)
                    target.add(SYNTHETIC_LINE)
                } else {
                    // 空屏时代的图形：第一条真实行就是它的行（见上方「空屏」节）。
                    a.line = snapshot[0]
                    a.insertAt = 0
                    live.add(a)
                    target.add(0)
                }
                continue
            }
            val exact: Int? = positionOf[src]
            if (exact != null) {
                live.add(a)
                target.add(exact)
                continue
            }
            val adopted = adoptMergedTail(src, snapshot)
            if (adopted != null) {
                a.line = snapshot[adopted]
                live.add(a)
                target.add(adopted)
            }
        }

        // 2) 本帧新到的图形：贴到当前最后一条行（原版打印游标恒在行末），空屏则用合成行。
        val newRuns = inlineRuns(incoming)
        if (newRuns.isNotEmpty()) {
            if (snapshot.isEmpty()) {
                val synthetic = target.indexOf(SYNTHETIC_LINE)
                if (synthetic >= 0) {
                    live[synthetic].runs.addAll(newRuns)
                } else {
                    live.add(InlineAnchor(null, 0, newRuns))
                    target.add(SYNTHETIC_LINE)
                }
            } else {
                live.add(InlineAnchor(snapshot.last(), lines[lines.lastIndex].runs.size, newRuns))
                target.add(snapshot.lastIndex)
            }
        }

        inlineAnchors.clear()
        inlineAnchors.addAll(live)

        if (snapshot.isEmpty()) {
            val synthetic = live.getOrNull(target.indexOf(SYNTHETIC_LINE)) ?: return lines
            return listOf(
                RenderLine(
                    runs = ArrayList(synthetic.runs),
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            )
        }

        // 3) 按目标行分组重贴；同一行的多批图形按建立顺序插入，插入点是「基础 run 坐标」，
        //    后面的锚点要把前面已插入的 run 数加回去（各锚点建立时看到的都是同一条基础行）。
        val groups = LinkedHashMap<Int, MutableList<InlineAnchor>>()
        for (k in live.indices) {
            val row = target[k]
            if (row == SYNTHETIC_LINE) continue
            groups.getOrPut(row) { ArrayList() }.add(live[k])
        }
        if (groups.isEmpty()) return lines
        var out: MutableList<RenderLine>? = null
        for ((row, group) in groups) {
            val base = lines[row]
            var runs: List<TextRun> = base.runs
            val applied = ArrayList<Pair<Int, Int>>(group.size)
            for (a in group) {
                var at = a.insertAt
                for ((baseAt, width) in applied) {
                    if (baseAt <= a.insertAt) at += width
                }
                at = at.coerceIn(0, runs.size)
                val merged = ArrayList<TextRun>(runs.size + a.runs.size)
                merged.addAll(runs.subList(0, at))
                merged.addAll(a.runs)
                merged.addAll(runs.subList(at, runs.size))
                runs = merged
                applied.add(a.insertAt to a.runs.size)
            }
            if (out == null) out = ArrayList(lines)
            out[row] = base.copy(runs = runs)
        }
        return out ?: lines
    }

    /** 图形负载 → 显示 run（占宽口径与 [HtmlRunFlatten] 的图形段一致：等量半角空格）。 */
    private fun inlineRuns(pending: List<InlineGraphic>): MutableList<TextRun> {
        if (pending.isEmpty()) return ArrayList<TextRun>(0)
        val runs = ArrayList<TextRun>(pending.size)
        for (g in pending) {
            if (g.widthCells <= 0) continue
            runs.add(
                TextRun(
                    text = " ".repeat(g.widthCells),
                    fore = DEFAULT_INLINE_FORE,
                    back = DEFAULT_INLINE_BACK,
                    styleMask = 0,
                    fontName = DEFAULT_INLINE_FONT,
                    kind = if (g is InlineGraphic.Image) RunKind.IMAGE else RunKind.SHAPE,
                    graphic = g,
                ),
            )
        }
        return runs
    }

    /**
     * 认领被 PRINTN 并入重建的末行，返回它在新快照里的下标；不是这种形态返回 null。
     *
     * 三个条件同时成立才认领：
     * 1. [src] 是**上一次底表的末行** —— core 只在 `displayLines.last()` 上合并
     *    （LineBufferConsoleSink.kt:1576-1581）；
     * 2. [src] 未收尾（`isLineEnd == false`）—— 合并分支的门槛就是它（同一处）；
     * 3. 新行把 [src] 的 `Segment` **实例**原样留在前缀里 —— `mergeRows` 的
     *    `prev.segments + next.segments`（LineBufferConsoleSink.kt:1607-1613）。
     *    用实例而不用文字相等：CLEARLINE 之后重打的同文行必须拿到空锚点，
     *    不能被旧图形污染。
     */
    private fun adoptMergedTail(
        src: LineBufferConsoleSink.DisplayLine,
        snapshot: List<LineBufferConsoleSink.DisplayLine>,
    ): Int? {
        if (src !== previousTail || src.isLineEnd || src.segments.isEmpty()) {
            return null
        }
        var i = snapshot.size - 1
        while (i >= 0) {
            val row = snapshot[i]
            if (row === src || row.segments.size <= src.segments.size) {
                i--
                continue
            }
            var samePrefix = true
            var k = 0
            while (k < src.segments.size) {
                if (row.segments[k] !== src.segments[k]) {
                    samePrefix = false
                    break
                }
                k++
            }
            if (samePrefix) {
                return i
            }
            i--
        }
        return null
    }

    /**
     * 一批持久内联图形的锚点。
     *
     * @property line 承载本批 run 的源显示行实例；null = 空屏时自建的合成行（见 [attachInline]）
     * @property insertAt 在本行**基础 run** 里的插入下标（锚定当时的打印游标位置）
     * @property runs 本批图形 run；重贴时逐帧复用同一批对象，结构相等因此稳定（快照差分幂等）
     */
    private class InlineAnchor(
        var line: LineBufferConsoleSink.DisplayLine?,
        var insertAt: Int,
        val runs: MutableList<TextRun>,
    )

    private companion object {
        /** 内联图形段的外层色：语料里图形段都由 `<img>`/`PRINT_IMG` 自带尺寸，颜色不参与绘制。 */
        const val DEFAULT_INLINE_FORE = 0xC0C0C0
        const val DEFAULT_INLINE_BACK = 0x000000
        const val DEFAULT_INLINE_FONT = "ＭＳ ゴシック"

        /** 空屏合成行在锚点目标表里的哨兵下标（真实行下标恒非负）。 */
        const val SYNTHETIC_LINE = -1
    }
}
