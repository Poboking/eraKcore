package com.eratw.port.render.displaylist

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.Grid
import com.eratw.port.render.TextRun

/**
 * 保留显示列表 + 行级脏矩形合并（M4-DLIST，ADR-001 重设计区）。
 *
 * ## 职责边界
 *
 * 输入是 m-grid 折行产物 [DisplayRow] 的**整屏快照**（调用方每次把当前要显示的行全量交进来），
 * 输出两样东西：
 * - [submit]：本次快照相对上一次的变化，压缩成行级 [DirtyRect] 列表；
 * - [render]：可直接交给绘制侧的只读 [DrawCmd] 列表。
 *
 * **`submit` 之后绘制只读列表，不再遍历文本**：字形引用（[GlyphKey]）与列偏移在 `submit`
 * 里一次性算好并按行缓存，干净行复用上一次的那份对象（`assertSame` 级别的复用，
 * 见单测 `render只复用既有DrawCmd实例不再遍历文本`）。这条缓存语义对标原版
 * `CalcWidth` 只对 `Width <= 0` 的节点测量（spec: `ConsoleButtonString.cs:257-270`）、
 * `SetAlignment` 的 `aligned` 一次守卫（spec: `ConsoleDisplayLine.cs:50-56`）、
 * `DrawTo` 只遍历既有节点（spec: `ConsoleDisplayLine.cs:118-125`）。
 *
 * ## 列位移的消费点（DLIST-ALIGN）
 *
 * 对齐位移由生产者算在 [DisplayRow.indentCells] 上（口径 `ConsoleAdapter.alignmentShift`，
 * 直译 `ConsoleDisplayLine.SetAlignment` 的三条分支，spec: `ConsoleDisplayLine.cs:70-90`），
 * 本类只负责**把它加到段列号上** —— 整数网格上等价于原版
 * `ConsoleDisplayLine.ShiftPositionX` → `ConsoleButtonString.ShiftPositionX` 那一步
 * 「`PointX += shiftX`、宽度不动」（spec: `ConsoleDisplayLine.cs:86-90`、
 * `ConsoleButtonString.cs:307-315`）。
 * 位移可负（超宽行在 CENTER/RIGHT 下为负，spec: `ConsoleDisplayLine.cs:80,83` 的减法）：
 * 原版不做跳过、由 GDI 把画布外的部分裁掉（spec: `ConsoleStyledString.cs:119,129-138` 四条绘制
 * 分支都直接把 `PointX` 当绘制起点，没有「越界就跳过」的判断）。本层把绘制坐标系定在
 * 可绘制窗口上（[DrawCmd.colStart] 非负），所以除了夹列号，还要把**整码点落在左界之外**的
 * 前导 [GlyphKey] 一起裁掉 —— 只夹列号不裁字形会让整段文字右移 `|位移|` 格；
 * 整段出左界的段不产命令（与「右侧越界段被丢弃」同一条既有口径，详见 [prepare]）。
 * 本类**不**从 [DisplayRow.align] 重算偏移：分支算术只许有一份（否则与适配层那份必然漂移）。
 *
 * ## 幂等（帧率保证的关键不变量）
 *
 * 内容没有变化的重复 `submit` 返回**空**脏区。原版同一条规则的形态是
 * 「没有新行就不重绘」：`RefreshStrings` 在 `lastDrawnLineNo == lineNo` 时直接 return
 * （spec: `EmueraConsole.cs:1758`），画完之后才把水位写成 `lastDrawnLineNo = lineNo`
 * （spec: `EmueraConsole.cs:1942-1949`）。我们把它前移到提交阶段并做成结构性的：
 * VM 空转一帧不产生任何重绘工作量。
 * 位移参与 [DisplayRow] 的结构相等，所以「同一行改对齐、列位置变了」必然被判脏，
 * 而同一份带位移的快照重复提交仍然幂等。
 *
 * ## 脏区的列跨度：新旧两侧「实际画到的列段」的并集
 *
 * 擦除宽度取**上一次该行列段与本次列段的并**，两侧都直接由 [DrawCmd] 的列区间给出
 * （[cmdLo]/[cmdHi]）：新侧是本行刚算好的命令，旧侧读 [rowCmds] 里的缓存，不重测文本。
 * 无位移时并集就是既有口径「`cells` 夹到 `clientWidth`」；有位移时若只按新列段擦，
 * 旧列段的像素会留在屏上（残影）。原版没有这个问题是因为它整行擦满
 * （`ConsoleDisplayLine.Clear` 的矩形宽取 `Config.WindowX`，spec: `ConsoleDisplayLine.cs:112-116`），
 * 本层刻意收窄到内容列段（ADR-003 的行级脏粒度），所以必须自己覆盖两侧。
 * 列段一律是**窗口内**的列（左沿非负、右沿不大于 `clientWidth`），与脏矩形坐标系一致。
 *
 * ## 两级刷新单元：整行带（[submit]）与行段（[dirtyStrips]）
 *
 * 消费侧有两条用法，粒度不同但出自同一次比较：
 * - **[submit] 的带**：相邻脏行并成一条 `[rowStart, rowEnd)` 的整行带，列跨度取带内并集
 *   （口径见上一节）。这是既有契约（单测逐条钉死），适合「整行擦掉再整行重画」的消费侧。
 * - **[dirtyStrips] 的行段**：**最小刷新单元 = 一条显示行 × 该行自己的新旧列段并集**，
 *   不再被邻行的宽行拖着擦。合并策略是「零浪费才算赚」：相邻两行（或两条已合成的行段）
 *   只有在 `并集格数 × 行数 <= 各行自身格数之和` 时才并（即合并后一格都不多擦），
 *   否则另起一条。逐例：55 行等宽满行 ⇒ 合成 1 条（与逐行擦的像素量相同，省 54 条命令）；
 *   一条 200 格的尾部状态栏挨着 54 条 40 格的行 ⇒ 拆开（否则要多擦 54 × 160 格）。
 *
 * 两条口径互不干扰：[submit] 的返回值一个字都没动，[dirtyStrips] 只是同一批
 * 脏标记的另一种分组，供 `view/ConsoleFrame` 逐行局部重绘。
 *
 * ## 每帧分配（W8-RENDERPERF）
 *
 * 稳态帧（快照没变化）**一次分配都不做**：行快照、命令表、脏标记、列跨度、并查集数组
 * 全是实例字段上的复用缓冲（[rows]/[rowsNext]、[rowCmds]/[cmdsNext]、[marks]），
 * 每次 `submit` 只重写已用前缀；只有「行数或命令数创新高」时才扩容。
 * 绘制侧同理：[renderInto] 写进调用方给的缓冲，[render] 才新建列表。
 * 复用只换容器、不换语义：判脏仍是 [DisplayRow] 的结构相等（不看对象身份），
 * 干净行仍返回**同一个** [DrawCmd] 实例，输出逐字段不变。
 * 线程约定见下节：复用缓冲把「单线程」从建议升级成前提。
 *
 * ## 行数上限与脏区裁剪
 *
 * `maxRows` 默认 `Grid.MAX_LOG_DEFAULT` = 5000（spec: `ConfigData.cs:62`），
 * 行为照抄原版显示列表的尾部保留：超过上限就从头部丢行
 * （`displayLineList.Count > Config.MaxLog` 时 `RemoveAt(0)`，spec: `EmueraConsole.cs:2988-2996`）。
 * 丢头会让**每条现存行的行下标整体前移**，所以按行下标对齐比较时整屏都算变化 ——
 * 脏区因此天然被裁剪到 `[0, maxRows)`，被丢弃的超上限行不进入任何 `DirtyRect`
 * （它们从未被画过，也没有坐标可指）。
 * 注意：配置项 `MaxLog` 的「小于 500 抬到 500」夹取（spec: `Config.cs:451-455`）归
 * 配置层 / m-scroll 路，本类对显式传入的正数 `maxRows` 不加抬升 ——
 * 否则单测与小窗口工具就没法用窄列表了。传 `<= 0` 才回落到默认 5000。
 *
 * **运行期只升不降**：窗口变高或「适应屏幕」把字号调小之后，可见行数会超过
 * 构造期按首帧行高算出的容量；[ensureCapacity] 允许绑定层把上限抬到新窗口大小，
 * 否则 `submit` 会按旧上限丢掉窗口顶行，表现为「一页内容只画得下前几行 + 顶部黑带」
 * （真机标题页 47 行 / 视口被钉在 28 行，2026-10-25 实测）。上限从不下调：
 * 已有脏区缓存与行引用都按旧下标，缩容只会平白触发整屏重画。
 *
 * ## 线程模型（ADR-003）
 *
 * 单生产（VM 线程）/ 单消费（渲染线程）。本类**不加锁、不起线程、不碰任何 Android API**，
 * 也没有静态可变状态：全部状态是实例私有的复用缓冲，且对外只暴露 [DirtyRect]、[DrawCmd]
 * 这类深不可变值对象。**复用缓冲要求 `submit` 与 `render` 严格串行**（同一线程，或调用方
 * 自己保证上一次渲染读完再提交下一次）：跨线程时把整个 `DisplayList` 连同脏区一次性发布
 * （安全发布 = 有 happens-before 边界的移交，例如把引用写进 `volatile` 字段或经 Handler 投递），
 * 移交之后由渲染线程独占读取。
 * 真机上若要「VM 持续提交、渲染线程逐帧消费」，需要的是双缓冲换代（两组实例轮转），
 * 归 M4-SKEL/组长接线，本类不提前造锁。
 *
 * @property clientWidth 可绘制宽度（半角格）。列跨度一律裁剪到它 ——
 *   原版的同类边界是 `Config.DrawableWidth`（spec: `Config.cs:466`，`WindowX` 减一个位移量）
 *   与画布宽 `window.MainPicBox.Width`（spec: `EmueraConsole.cs:1877`）。
 *   位移后的段同样以它为右夹边界：对齐偏移的**基准宽度**由生产者在 [DisplayRow.indentCells]
 *   里用掉（`SetAlignment` 读的是 `Config.DrawableWidth`，spec: `ConsoleDisplayLine.cs:80,83`），
 *   两者不一致时（例如折行宽度与显示列表宽度不同）内容会越出右沿，本类按边界裁列而不是重算偏移。
 * @property maxRows 保留列表的最大行数；传 `<= 0` 时取 `Grid.MAX_LOG_DEFAULT`
 */
class DisplayList(
    val clientWidth: Int,
    maxRows: Int = Grid.MAX_LOG_DEFAULT,
) {
    /** 保留列表的行数上限（[maxRows] 参数非法时取 `Grid.MAX_LOG_DEFAULT`）。运行期可由 [ensureCapacity] 上抬。 */
    var maxRows: Int = if (maxRows <= 0) Grid.MAX_LOG_DEFAULT else maxRows
        private set

    init {
        require(clientWidth > 0) { "clientWidth 必须为正，当前 $clientWidth" }
    }

    /**
     * 把保留上限抬到至少 [rows] 行（只升不降）。
     *
     * 窗口变高 / 字号调小之后，绑定层按可见高算出的行数会超过构造期容量；
     * 不抬上限时 [retainTail] 会从窗口顶行开始丢，玩家看到的是一页被截断 + 顶部黑带。
     * 只在 UI 线程（与 [submit] 同一条串行序列）调用。
     *
     * @return 是否真的上抬过
     */
    fun ensureCapacity(rows: Int): Boolean {
        if (rows <= maxRows) return false
        maxRows = rows
        return true
    }

    /** 当前保留的行（与 [render] 的行下标同一坐标系）；与 [rowsNext] 轮转，避免每帧复制。 */
    private var rows: ArrayList<DisplayRow> = ArrayList()

    /** [rows] 的轮转缓冲：本次快照的尾部保留结果先落在这里，判脏完成后再交换。 */
    private var rowsNext: ArrayList<DisplayRow> = ArrayList()

    /** 与 [rows] 同下标的只读绘制命令；干净行在上一次提交的基础上原样复用。 */
    private var rowCmds: ArrayList<List<DrawCmd>> = ArrayList()

    /** [rowCmds] 的轮转缓冲（与 [rowsNext] 同步交换）。 */
    private var cmdsNext: ArrayList<List<DrawCmd>> = ArrayList()

    /** 行级脏标记（复用，只重写已用前缀）。 */
    private var marks = BooleanArray(0)

    /** 变化行的新命令（复用；非变化项在每次提交时清空，防止持有旧行）。 */
    private var fresh: Array<List<DrawCmd>?> = emptyArray()

    /** 变化行的列跨度并集（左沿，复用）。 */
    private var spanLo = IntArray(0)

    /** 变化行的列跨度并集（右开，复用）。 */
    private var spanHi = IntArray(0)

    /** 上一次提交的行数（[marks]/[spanLo]/[spanHi] 的有效长度）。 */
    private var markedRows = 0

    /** 上一次提交判脏的行数（0 表示稳态帧，[dirtyStrips] 直接给空表、不分配）。 */
    private var dirtyRowCount = 0

    /** [renderInto] 与 [render] 用来标记「要取哪几行命令」的复用掩码；与 [marks] 分开，别互相踩。 */
    private var pickRows = BooleanArray(0)

    /** [runsInto] 的下标对输出缓冲（复用）。 */
    private var runPairs = IntArray(0)

    /** [dirtyStrips] 的惰性缓存；[submit] 时置空。 */
    private var stripsCache: List<DirtyRect>? = null

    /** 行级并查集（复用；[submit] 里 [RowUnionFind.reset] 后即用）。 */
    private val unionFind = RowUnionFind()

    /** 保留列表的行数（不超过 [maxRows]）。 */
    val rowCount: Int
        get() = rows.size

    /**
     * 提交一次整屏行快照，返回相对上次提交的行级脏区（按 `rowStart` 升序，相邻脏行已合并）。
     *
     * 脏区的列跨度取「上一次真正画到屏上的列段」与「本次要画到屏上的列段」的并：
     * 两侧都由 [DrawCmd] 的列区间给出（旧侧直接读 [rowCmds] 缓存，不重测），
     * 所以对齐位移改了列段时，旧列段一样会被擦干净。
     *
     * 判脏口径仍是 [DisplayRow] 的**结构相等**（不看对象身份，也不区分「哪些字段影响绘制」）：
     * 只改 `isLineEnd` / `temporary` 的行照样算脏 —— 这条是既有契约（单测
     * `PRINTN未结束行的并入只脏末行` 钉的就是它），本波只改容器、不改判据。
     *
     * @param rows 本次要显示的全部行；超出 [maxRows] 的部分按「保留末尾 [maxRows] 行」处理
     * @return 空列表表示画面没有任何变化（幂等不变量）
     */
    fun submit(rows: List<DisplayRow>): List<DirtyRect> {
        val old = this.rows
        val next = this.rowsNext
        retainTail(rows, next)
        val n = maxOf(old.size, next.size)
        prepareScratch(n)
        stripsCache = null
        markedRows = 0
        dirtyRowCount = 0

        var dirtyCount = 0
        var i = 0
        while (i < n) {
            val a = if (i < old.size) old[i] else null
            val b = if (i < next.size) next[i] else null
            if (a == null || b == null || a != b) {
                marks[i] = true
                dirtyCount++
                val cmds = if (b == null) emptyList() else prepare(i, b)
                fresh[i] = cmds
                val previous = if (i < rowCmds.size) rowCmds[i] else emptyList()
                val lo = minOf(cmdLo(cmds), cmdLo(previous))
                val hi = maxOf(cmdHi(cmds), cmdHi(previous))
                spanLo[i] = lo
                spanHi[i] = hi
            }
            i++
        }
        markedRows = n
        dirtyRowCount = dirtyCount
        if (dirtyCount == 0) {
            return emptyList()
        }

        unionFind.reset(n)
        val pairCount = unionFind.runsInto(marks, runPairs)
        val rects = ArrayList<DirtyRect>(pairCount)
        var k = 0
        while (k < pairCount) {
            val start = runPairs[k * 2]
            val end = runPairs[k * 2 + 1]
            rects.add(bandOf(start, end))
            k++
        }

        // 命令表重建：干净行继承旧实例；变化行用上面算好的那份（不测第二遍）。
        val oldCmds = this.rowCmds
        val nextCmds = this.cmdsNext
        nextCmds.clear()
        i = 0
        while (i < next.size) {
            nextCmds.add(if (marks[i]) fresh[i] ?: emptyList() else oldCmds[i])
            i++
        }
        this.rows = next
        this.rowsNext = old
        this.rowCmds = nextCmds
        this.cmdsNext = oldCmds
        return rects
    }

    /**
     * 上一次 [submit] 的**行段**：最小刷新单元 = 一条显示行 × 该行的新旧列段并集。
     *
     * 合并策略「零浪费才算赚」（详见类文档的「两级刷新单元」）：相邻行只有在
     * `并集格数 × 行数 <= 各行自身格数之和` 时才并成一条，故任何一条行段擦掉的格子
     * 都不会多于逐行擦的总和。返回按 `rowStart` 升序、行区间互不相交。
     *
     * 有效窗口是「上一次 [submit] 之前」：再次 `submit` 会作废上一次的结果。
     * 没有脏行时返回空表（稳态帧不分配）。
     */
    fun dirtyStrips(): List<DirtyRect> {
        stripsCache?.let { return it }
        val n = markedRows
        val out: List<DirtyRect>
        if (dirtyRowCount == 0) {
            out = emptyList()
        } else {
            val list = ArrayList<DirtyRect>()
            var row = 0
            while (row < n) {
                if (!marks[row]) {
                    row++
                    continue
                }
                var lo = spanLo[row]
                var hi = spanHi[row]
                var rowsInStrip = 1
                var ownCells = spanWidth(lo, hi)
                var end = row
                while (end + 1 < n && marks[end + 1]) {
                    val nextLo = minOf(lo, spanLo[end + 1])
                    val nextHi = maxOf(hi, spanHi[end + 1])
                    val nextRows = rowsInStrip + 1
                    val nextOwn = ownCells + spanWidth(spanLo[end + 1], spanHi[end + 1])
                    if (spanWidth(nextLo, nextHi) * nextRows > nextOwn) {
                        break
                    }
                    lo = nextLo
                    hi = nextHi
                    rowsInStrip = nextRows
                    ownCells = nextOwn
                    end++
                }
                list.add(stripOf(row, end, lo, hi))
                row = end + 1
            }
            out = list
        }
        stripsCache = out
        return out
    }

    /** 全量只读绘制命令（首帧、尺寸变化、上翻查看历史时用）。 */
    fun render(): List<DrawCmd> {
        var total = 0
        var i = 0
        while (i < rowCmds.size) {
            total += rowCmds[i].size
            i++
        }
        val out = ArrayList<DrawCmd>(total)
        flattenInto(0, rows.size, null, out)
        return out
    }

    /**
     * 全量绘制命令写进调用方给的缓冲（稳态帧零分配；[ConsoleFrame] 与画布路
     * `CanvasConsoleView.drawScreen` 都复用它攒整帧命令）。
     * 语义与 [render] 逐项相同，只换了输出容器。
     */
    fun renderInto(out: MutableList<DrawCmd>) {
        out.clear()
        flattenInto(0, rows.size, null, out)
    }

    /**
     * 只取脏区覆盖到的行（稳态帧路径：脏哪几行就交哪几行的命令）。
     *
     * @param rects [submit] 返回的脏区；越界部分按保留列表的坐标裁剪
     */
    fun render(rects: List<DirtyRect>): List<DrawCmd> {
        if (rects.isEmpty()) {
            return emptyList()
        }
        val pick = markRows(rects)
        var total = 0
        var i = 0
        while (i < rowCmds.size) {
            if (pick[i]) total += rowCmds[i].size
            i++
        }
        val out = ArrayList<DrawCmd>(total)
        flattenInto(0, rows.size, pick, out)
        return out
    }

    /**
     * [render] 的零分配版本：把脏区覆盖到的行写进 [out]（先清空）。
     * 行区间口径与 [render] 完全一致（越界裁剪、重复覆盖不重复出命令）。
     */
    fun renderInto(rects: List<DirtyRect>, out: MutableList<DrawCmd>) {
        out.clear()
        if (rects.isEmpty()) {
            return
        }
        flattenInto(0, rows.size, markRows(rects), out)
    }

    /**
     * 原版 `MaxLog` 的头部丢弃：只保留末尾 [maxRows] 行
     * （spec: `EmueraConsole.cs:2988-2996`）。
     *
     * 结果写进 [out]（先清空）：仍是一份与调用方列表脱钩的副本 —— 调用方之后改动传进来的
     * 列表不会影响本类的判脏基准；只是不再每帧新建容器。
     */
    private fun retainTail(source: List<DisplayRow>, out: ArrayList<DisplayRow>) {
        out.clear()
        if (source.size <= maxRows) {
            var i = 0
            while (i < source.size) {
                out.add(source[i])
                i++
            }
        } else {
            var i = source.size - maxRows
            while (i < source.size) {
                out.add(source[i])
                i++
            }
        }
    }

    /** 把复用缓冲撑到至少 [n] 项（只在创新高时分配），并清掉已用前缀的残留。 */
    private fun prepareScratch(n: Int) {
        if (marks.size < n) {
            var cap = if (marks.size < 8) 8 else marks.size
            while (cap < n) {
                cap *= 2
            }
            marks = BooleanArray(cap)
            fresh = arrayOfNulls(cap)
            spanLo = IntArray(cap)
            spanHi = IntArray(cap)
            runPairs = IntArray(cap * 2)
        }
        var i = 0
        while (i < n) {
            marks[i] = false
            fresh[i] = null
            spanLo[i] = Int.MAX_VALUE
            spanHi[i] = 0
            i++
        }
    }

    /** 一条整行带的 [DirtyRect]（列跨度取带内并集；整带无可画内容时回落到零宽、左沿 0）。 */
    private fun bandOf(start: Int, end: Int): DirtyRect {
        var lo = Int.MAX_VALUE
        var hi = 0
        var i = start
        while (i <= end) {
            lo = minOf(lo, spanLo[i])
            hi = maxOf(hi, spanHi[i])
            i++
        }
        return DirtyRect(start, end + 1, if (lo > hi) 0 else lo, hi)
    }

    /** 一条行段的 [DirtyRect]（左沿非负、零宽时两侧都给 0，与 [bandOf] 同形态）。 */
    private fun stripOf(start: Int, endInclusive: Int, lo: Int, hi: Int): DirtyRect =
        DirtyRect(start, endInclusive + 1, if (lo > hi) 0 else lo, if (lo > hi) 0 else hi)

    /** 行段宽度（格）；整行无可画内容（[spanLo] 为哨兵）时为 0。 */
    private fun spanWidth(lo: Int, hi: Int): Int = if (lo > hi) 0 else hi - lo

    /** 按 [rects] 填 [pickRows]（只重写已用前缀；越界按保留列表裁剪）并返回它。 */
    private fun markRows(rects: List<DirtyRect>): BooleanArray {
        if (pickRows.size < rows.size) {
            var cap = if (pickRows.size < 8) 8 else pickRows.size
            while (cap < rows.size) {
                cap *= 2
            }
            pickRows = BooleanArray(cap)
        }
        var i = 0
        while (i < rows.size) {
            pickRows[i] = false
            i++
        }
        var r = 0
        while (r < rects.size) {
            val rect = rects[r]
            val from = maxOf(rect.rowStart, 0)
            val until = minOf(rect.rowEnd, rows.size)
            var row = from
            while (row < until) {
                pickRows[row] = true
                row++
            }
            r++
        }
        return pickRows
    }

    /**
     * 一行 -> 若干 [DrawCmd]（提交期一次性完成的工作：测列宽、展开字形引用、套对齐位移）。
     *
     * 段列偏移按 [Grid.cellWidth] 的「半角 1 / 全角 2」等宽网格累加
     * （PRINTC 补齐宽度用的就是 Shift-JIS 字节数口径，spec: `EmueraConsole.cs:3248-3257`，
     * Kernel 的 `Grid` 已把它定为 M4 的宽度模型）；
     * 超出 `clientWidth` 的段被裁到边界之后丢弃，折行本身是 m-grid 的职责，本类不二次折行。
     *
     * 列游标从 [DisplayRow.indentCells] 起算而不是从 0 起算 —— 这一步就是原版
     * `SetAlignment` 末尾那次 `ShiftPositionX(num3)`（spec: `ConsoleDisplayLine.cs:86-90`、
     * `ConsoleButtonString.cs:307-315`：只加 `PointX`，`Width` 保持不变），
     * 因此位移既不改字形、也不改段宽，只改列起点。
     *
     * 负位移下段会有一截落在左边界之外。本层把绘制列坐标系定在**可绘制窗口**内
     * （[DrawCmd] 的 `colStart` 非负，见其 `init`），所以除了把 `colStart` 夹到 0，
     * 还必须把**整码点落在左界之外**的那些前导字形从 [GlyphKey] 表里丢掉 —— 只夹列号不裁字形
     * 会让整段文字右移 `|位移|` 格（绘制侧 [DrawCmd.glyphs] 是从 `colStart` 起逐格铺的）。
     * 落在左边界**线上**的那个码点原样保留：原版由 GDI 做亚像素裁剪
     * （spec: `ConsoleStyledString.cs:119,129-138`），整数网格只能在码点粒度上取舍，
     * 故全角字跨左边界时最多多画 1 格。TODO(verify)：真机有像素度量后改成亚格裁剪，
     * 卡点：本波 [Grid] 只有整数格。
     * 整段出左界（`col + cells <= 0`）时不产命令，但游标照常推进，
     * 所以「前面出左界、后面回到屏内」的行不会丢段；右侧越界仍按既有口径只夹 `colEnd`
     * （多出来的尾字形由绘制侧在 `clientWidthCells` 处停笔，不需要在这里裁）。
     *
     * 分配（W8-RENDERPERF）：TAB 只展开一次、格数走 [Grid.cellsExpanded] 不再二次扫 TAB，
     * 左界裁剪按码元累加而不是逐码点切子串。产出的 [GlyphKey] 是**保留缓存**本身
     * （行不变就一直用），不是临时对象。
     */
    private fun prepare(row: Int, display: DisplayRow): List<DrawCmd> {
        val runs = display.runs
        if (runs.isEmpty()) {
            return emptyList()
        }
        val out = ArrayList<DrawCmd>(runs.size)
        var col = display.indentCells
        var r = 0
        while (r < runs.size) {
            val run = runs[r]
            val graphic = run.graphic
            val text = Grid.expandTabs(run.text)
            val cells = if (graphic != null) graphic.widthCells else Grid.cellsExpanded(text)
            if (cells < 0 && graphic is InlineGraphic.Space) {
                // 负 space 是原版真实回退：`ConsoleSpacePart.WidthF` 为负（ConsoleSpacePart.cs:18-23），
                // 排版 `num += part.Width` 把后续部件左移（ConsoleButtonString.cs 的 CalcPointX）。
                // XRAY 图层靠它叠回立绘起点；这里只回退列游标，不产命令。原版也不把 PointX
                // 夹回 0（GDI 自行裁画布外）。
                //
                // 这一支必须在「列已满就收摊」的判定**之前**：立绘恰好占满一行时 col 已等于
                // clientWidth，先 break 掉就再也回退不回去、后面的图层一张都画不出来。
                col += cells
                r++
                continue
            }
            if (col >= clientWidth) {
                break
            }
            if (cells <= 0) {
                r++
                continue
            }
            if (col + cells <= 0) {
                col += cells
                r++
                continue
            }
            val start = if (col > 0) col else 0
            if (graphic != null) {
                // 图形段：占宽自带（`widthCells`），没有字形可铺，也不做左界的按码点裁剪
                // （原版同样整段平移，`ConsoleImagePart.cs:170-173` 的 destRect.X =
                // PointX + shift，负位移时 GDI 裁掉画布外那截）。
                // 右沿必须按**几何右沿** `col + cells` 夹，而不是 `start + cells`：负位移下
                // `start` 已被夹到 0，拿它加宽度会把左侧被裁掉的 |col| 格也算成可见列
                // （整图右移、脏区多算）。可见区间恒为 `[max(col,0), min(col+cells, clientWidth))`。
                out.add(
                    DrawCmd(
                        row, start, minOf(col + cells, clientWidth),
                        run.fore, run.back, run.styleMask, run.kind, emptyList(), graphic,
                        focusFore = run.focusFore,
                    ),
                )
                col += cells
                r++
                continue
            }
            val visible = visibleSuffix(text, col)
            val glyphs = glyphKeys(run, visible)
            if (glyphs.isEmpty()) {
                col += cells
                r++
                continue
            }
            // 右沿按「实际会画到的列」给：从 start 起铺完 visible 的格数，再夹到 clientWidth。
            // 位移非负时这与旧口径 `min(col + cells, clientWidth)` 逐项相等；
            // 负位移下跨左界的那个码点整格保留，右沿因此比几何右沿多出被裁掉的那点零头。
            val end = minOf(start + Grid.cellsExpanded(visible), clientWidth)
            out.add(
                DrawCmd(
                    row, start, end, run.fore, run.back, run.styleMask, run.kind, glyphs,
                    focusFore = run.focusFore,
                ),
            )
            col += cells
            r++
        }
        return out
    }

    /**
     * 从 [text] 起裁掉「整段都落在左边界之外」的前导码点，返回仍会出现在可绘制窗口里的后缀。
     * [startCol] 是本段左沿（可为负）。跨界那个码点保留（理由见 [prepare]）。
     * TAB 必须先展开再进来 —— [Grid.cellsExpanded] 与 [glyphKeys] 用的都是展开后的文本。
     *
     * 每个码点的格数直接取 [Grid.cellWidth] 的两项之和（代理对 2 + 2），
     * 与 [Grid.cellsExpanded] 对同一段文本的逐码元累加**逐值相等**：[text] 已无 TAB，
     * 所以这里不再为每个码点切一次子串（旧写法每码点分配一个 `String`）。
     */
    private fun visibleSuffix(text: String, startCol: Int): String {
        if (startCol >= 0) {
            return text
        }
        var index = 0
        var col = startCol
        while (index < text.length) {
            val step = if (isSurrogatePair(text, index)) 2 else 1
            var cells = Grid.cellWidth(text[index])
            if (step == 2) {
                cells += Grid.cellWidth(text[index + 1])
            }
            if (col + cells > 0) {
                break
            }
            col += cells
            index += step
        }
        return if (index == 0) text else text.substring(index)
    }

    /** 代理对判定（与 [glyphKeys] 同一套码点步进）。 */
    private fun isSurrogatePair(s: String, index: Int): Boolean =
        Character.isHighSurrogate(s[index]) && index + 1 < s.length && Character.isLowSurrogate(s[index + 1])

    /**
     * 一批 [DrawCmd] 覆盖到的最左列；空表返回 `Int.MAX_VALUE`，让 [submit] 的并集把它当
     * 「这一侧不贡献区间」处理（整行被位移推出窗口、或本来就没有可画内容）。
     *
     * 逐项下标循环而不是 `minOfOrNull`：后者在 `List` 上会产生一个迭代器（每脏行一次）。
     */
    private fun cmdLo(cmds: List<DrawCmd>): Int {
        var lo = Int.MAX_VALUE
        var i = 0
        while (i < cmds.size) {
            val v = cmds[i].colStart
            if (v < lo) {
                lo = v
            }
            i++
        }
        return lo
    }

    /** 一批 [DrawCmd] 覆盖到的最右列（右开）；空表返回 0。 */
    private fun cmdHi(cmds: List<DrawCmd>): Int {
        var hi = 0
        var i = 0
        while (i < cmds.size) {
            val v = cmds[i].colEnd
            if (v > hi) {
                hi = v
            }
            i++
        }
        return hi
    }

    /**
     * 字形引用序列（[GlyphKey] = 字体名 + 样式位 + 码位，分桶口径
     * spec: `ConsoleStyledString.cs:21-40`）；代理对合成一个码位。
     *
     * 入参 [expandedText] 是**已经** `Grid.expandTabs` 展开、并按左边界裁过前导码点的文本
     * （TAB 展开口径 spec: `StringMeasure.cs:59-61`；裁剪见 [prepare] 与 [visibleSuffix]），
     * 所以这里只负责「一码位一个引用」，不再读 [TextRun.text] 原文。
     *
     * TODO(verify) 一个增补字符只出 1 个 [GlyphKey]，但 `Grid.cellWidth` 会把两个代理项各计 2 格，
     * 于是「字形个数」与「占用格数」在 emoji 上不相等。整行列跨度的权威是 m-grid 给的
     * `DisplayRow.cells`（本类不改），emoji 在语料里的实测占比归 t-font 路的码点闭包清单。
     */
    private fun glyphKeys(run: TextRun, expandedText: String): List<GlyphKey> {
        val out = ArrayList<GlyphKey>(expandedText.length)
        var i = 0
        while (i < expandedText.length) {
            val ch = expandedText[i]
            if (isSurrogatePair(expandedText, i)) {
                out.add(GlyphKey(run.fontName, run.styleMask, Character.toCodePoint(ch, expandedText[i + 1])))
                i += 2
            } else {
                out.add(GlyphKey(run.fontName, run.styleMask, ch.code))
                i += 1
            }
        }
        return out
    }

    /** 把行区间 `[from, until)` 里（[mark] 为 null 时不过滤）的命令追加到 [out]。 */
    private fun flattenInto(from: Int, until: Int, mark: BooleanArray?, out: MutableList<DrawCmd>) {
        val lo = maxOf(from, 0)
        val hi = minOf(until, rows.size)
        var i = lo
        while (i < hi) {
            if (mark == null || mark[i]) {
                // 逐项 add 而不是 addAll：ArrayList.addAll(Collection) 会先 toArray()，
                // 每行一个临时数组（稳态帧里这就是唯一的分配残留）。
                val cmds = rowCmds[i]
                var k = 0
                while (k < cmds.size) {
                    out.add(cmds[k])
                    k++
                }
            }
            i++
        }
    }
}
