package com.eratw.port.render.view

import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.HitTarget
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayRow
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable

/**
 * 一帧的驱动件：**持有画布**、只重绘脏行（W8-RENDERPERF）。
 *
 * ## 它解决的问题
 *
 * `ScreenComposer.compose` 每帧新建一张 `Raster`（黄金窗口 200 格 × 54 行 ⇒ 1800 × 954 像素
 * ⇒ 6.9 MB 的 `IntArray`），并且整屏重画。真机上 60 fps 等于每秒 400 MB 垃圾 + 每帧
 * 55 行的全量字形铺块 —— 滚动必掉帧。本类把这两件事都收掉：
 *
 * 1. **画布只建一次**：几何不变就一直复用同一个 [Raster]；
 * 2. **只重绘脏行段**：拿 `DisplayList.dirtyStrips`（最小刷新单元 = 一行 × 该行新旧列段），
 *    逐段填背景 + 只画段内命令。像素结果与整屏重绘**逐像素相同**（同一套
 *    `ScreenComposer` 的换算与裁剪，单测逐帧对拍）。
 *
 * ## 什么时候退回整屏
 *
 * 按行重绘有三个前提（见 `ScreenComposer.repaintRows`），任一不成立就整屏重绘：
 * - 首帧、[invalidate]、行窗口行数变了（窗口 resize / 上翻历史改变行数）；
 * - 背景色变了，或者**场景里有立绘**（立绘本类不碰，宁可整屏）；
 * - **场景里有 CBG 图层 / 有 CBG 按钮按下态**：两条都是整屏层，换层或换按钮图会跨行
 *   改像素（动画 CBG 还每帧都在变，见 [cbgAnimeClock]）；
 * - 焦点按钮变了（换色会改一整段的字色，可能跨行）。
 *
 * 这一切都是**像素等价**的取舍，不是语义分叉：两种路径对同一状态画出的画布逐像素相同。
 *
 * ## 用法
 *
 * 典型渲染循环（Android 侧每帧调一次）：
 * ```
 * val rects = screen.submitSnapshot(adapter.adapt(sink.snapshot()))
 * val canvas = frame.draw(screen, rects, scene, sprites, pointing)
 * ```
 * `rects` 必须就是**这一次** `submit` 的返回值：行段缓存的生命周期到下一次提交为止
 * （`DisplayList.dirtyStrips` 的 KDoc）。
 *
 * ## 状态
 *
 * 画布、上一次的场景/焦点/行数、命令缓冲全是实例字段；无静态可变全局（AGENTS.md 铁律 4）。
 * 与 [ConsoleScreen] / [ScreenComposer] 一样假定单线程（ADR-003）。
 *
 * @param composer 几何与排版换算的唯一来源（半角格 ↔ 像素、字形块铺法）
 * @param stats 帧统计；null（默认）时零开销。要 `ms` 覆盖「提交 + 绘制」整帧，
 *   请把**同一个**实例也挂到 `ConsoleScreen` 的 `stats` 参数上
 * @param animeTimeSource 动画 CBG 的单调毫秒时钟（默认 `System.nanoTime()/1_000_000`）。
 *   逐帧推进的唯一入口就在这里：core 的 CBG 快照只在 `CBG*` 语句执行时推送，没有逐帧
 *   重推通道，所以「动画 CBG 会动」押在绘制侧每次重画时喂时钟上
 *   （见 [CbgDrawOrder.CbgAnimeClock]）。测试注入假时钟即可零墙钟地驱动帧号。
 */
class ConsoleFrame(
    val composer: ScreenComposer,
    val stats: FrameStats? = null,
    private val animeTimeSource: () -> Long = DEFAULT_ANIME_TIME_SOURCE,
) {

    /** 默认场景（背景黑、无立绘）：作为默认参数的**同一个实例**，免得每帧新建一个 `ScreenScene`。 */
    private val defaultScene: ScreenScene = ScreenScene()

    /** 默认空立绘表（同上；`SpriteTable` 里带三张 `HashMap`，每帧新建可就白省了）。 */
    private val defaultSprites: SpriteTable = SpriteTable()

    /** 复用的画布；首帧（或 [invalidate] 之后）在这条路径上创建。 */
    var canvas: Raster? = null
        private set

    /** 最近一帧的绘制命令数（`FrameStats.draw` 的来源，也供测试断言）。 */
    var lastDrawCount: Int = 0
        private set

    /** 最近一帧重绘的行数（`FrameStats.rows` 的来源，也供测试断言）。 */
    var lastRowsRepainted: Int = 0
        private set

    /** 最近一帧是否走了整屏重绘。 */
    var lastWasFull: Boolean = false
        private set

    /** 上一次的完整场景（背景 + 立绘摆位）；不同就整屏。 */
    private var previousScene: ScreenScene? = null

    /** 上一次的焦点按钮；不同就整屏（换色可能跨行）。 */
    private var previousFocus: HitTarget? = null

    /** 上一次的行窗口行数；不同就整屏（行顶 y 的基准变了）。 */
    private var previousRows: Int = -1

    /** 上一帧是否有 div 覆盖块（CONTRACT-DIV-WIRE W1）：在场或刚退场都整屏（见 [draw]）。 */
    private var previousOverlay: Boolean = false

    /** 画布里是否已经是一帧完整结果。 */
    private var ready: Boolean = false

    /** 复用的命令缓冲（[DisplayList.renderInto] 写进来，不每帧新建列表）。 */
    private val commands = ArrayList<DrawCmd>(256)

    /**
     * 动画 CBG 的层内计时起点（原版 `SpriteAnime.StartTime`，`SpriteAnime.cs:48-52`）。
     *
     * 只在本类（绘制侧）维护：帧号 = `(now - 该层首次被画) % totaltime`，与
     * [CbgDrawOrder.frameGeometry] / `SpriteAnimeTimeline.frameAt` 同一份算法。
     * 宿主的重画节奏决定实际帧率（`SETANIMETIMER` 的读数归 platform 的排程，
     * 见 `SpriteAnimeTimeline.repaintIntervalMs`）。
     */
    private val cbgAnimeClock: CbgDrawOrder.CbgAnimeClock = CbgDrawOrder.CbgAnimeClock(animeTimeSource)

    /** 丢掉画布内容（表面重建、尺寸变化、强制重绘）：下一次 [draw] 整屏重绘。 */
    fun invalidate() {
        ready = false
        previousScene = null
        previousFocus = null
        previousRows = -1
        previousOverlay = false
        stats?.discardFrame()
    }

    /**
     * 画一帧。
     *
     * @param screen 当前屏（命令、脏行段、行窗口都从它取）
     * @param rects 本次 `submit` / `submitSnapshot` 返回的脏区（空表 = 这一帧没有任何变化）；
     *   与 `screen.list.dirtyStrips()` 必须来自同一次提交 —— 前者决定「要不要画」，
     *   后者给出「画哪几行、擦到哪一列」
     * @param scene 背景色与立绘摆位
     * @param sprites 立绘注册表（只在整屏路径用到；场景里有立绘时本类不做按行重绘）
     * @param pointing 绘制侧的指（按）位置（命中口径），null 表示无焦点
     * @return 本帧画布（复用同一个实例，不要跨帧持有像素快照）
     */
    fun draw(
        screen: ConsoleScreen,
        rects: List<DirtyRect>,
        scene: ScreenScene = defaultScene,
        sprites: SpriteTable = defaultSprites,
        pointing: Pair<Int, Int>? = null,
    ): Raster {
        val focused = screen.focusedTarget(pointing)
        val rowCount = screen.visibleRows().size
        // div 覆盖块帧（CONTRACT-DIV-WIRE W1）：窗口 + 一屏 margin 行里扫块、按行顶表
        // 规划。在场（或上一帧在场而这一帧退场）都走整屏 —— 块的像素横跨行带，
        // 按行擦背景会把块擦出洞，「不留陈迹」只能整屏（W2 的同一条在真机侧由
        // CanvasConsoleView 的全幅判定兜）。
        val overlay = divOverlayFrame(screen)
        // div 命中表的同一帧喂参（DIV-WIRE-2 W3）：命中几何必须与这一帧的绘制用**同一份**
        // frame（同一行顶/同一坐标原点），否则 pointer 命中与画出来的块会错位。
        // overlay 为空时该口内部 clearDiv()，避免上一帧的 div 命中残留成幽灵可点区。
        screen.updateDivOverlay(overlay, composer.metrics, composer.canvasHeightPx)
        val target = canvas
        val full = !ready || target == null || previousRows != rowCount ||
            previousScene != scene || previousFocus != focused ||
            scene.sprites.isNotEmpty() || scene.cbgLayers.isNotEmpty() ||
            scene.pressedCbgButtons.isNotEmpty() ||
            (scene.backgroundArgb ushr 24) != 255 ||
            !overlay.isEmpty || previousOverlay
        val raster = if (target == null) {
            Raster(composer.canvasWidthPx, composer.canvasHeightPx).also { canvas = it }
        } else {
            target
        }
        if (full) {
            screen.list.renderInto(commands)
            // 动画 CBG 的层内时钟：只在真的有动画帧时才取时间（静态 CBG 零代价）。
            // retainOnly 先剪掉已消失的层，避免起点表随会话无限增长。
            var animeProvider: ((CbgLayerSnapshot) -> Long)? = null
            if (scene.cbgLayers.isNotEmpty()) {
                cbgAnimeClock.retainOnly(scene.cbgLayers)
                if (scene.cbgLayers.any { it.frames.isNotEmpty() }) {
                    animeProvider = { layer -> cbgAnimeClock.elapsedMsOf(layer) }
                }
            }
            // div 子行按钮的焦点/按下（DIV-WIRE-2）：按按钮身份算（ConsoleButtonString.cs:317-320），
            // 与格坐标的 focusedButton 是两条独立焦点通道，两个都要交给合成器。
            composer.composeInto(
                raster, commands, rowCount, scene, sprites, focused, overlay,
                focusedDiv = screen.focusedDiv(),
                pressedDiv = screen.pressedDiv(),
                cbgAnimeNowMs = animeProvider,
            )
            lastDrawCount = commands.size
            lastRowsRepainted = rowCount
            lastWasFull = true
            previousScene = scene
            previousFocus = focused
            previousRows = rowCount
            previousOverlay = !overlay.isEmpty
            ready = true
        } else {
            val strips = screen.list.dirtyStrips()
            if (rects.isEmpty() || strips.isEmpty()) {
                // 稳态帧：屏上没有任何变化 ⇒ 一个像素都不动、一条命令都不发。
                lastDrawCount = 0
                lastRowsRepainted = 0
                lastWasFull = false
                stats?.endFrame(0, 0)
                return raster
            }
            screen.list.renderInto(commands)
            lastRowsRepainted = composer.repaintRows(
                target = raster,
                commands = commands,
                rowCount = rowCount,
                strips = strips,
                backgroundArgb = scene.backgroundArgb,
                focusedButton = focused,
            )
            lastDrawCount = countDrawn(commands, strips)
            lastWasFull = false
        }
        stats?.endFrame(lastDrawCount, lastRowsRepainted)
        return raster
    }

    /**
     * 本帧的 div 覆盖帧（CONTRACT-DIV-WIRE W1）：[ConsoleScreen.divOverlayScan] 扫
     * 「窗口 + 一屏 margin」的块行，行条顶用 [ScreenComposer.rowTopPx] 折算 —— 窗口外的
     * margin 行按行号公式外推（可为负，与正文行的溢出行同一条公式）。
     * 可见行区间取 [ConsoleScreen.visibleRowBase] 起的绝对行号（原版 `num3`/`num2`，
     * EmueraConsole.cs:1805-1807 + GetPartsInRange 的 shift 已由 firstIndex 口径吸收）。
     */
    private fun divOverlayFrame(screen: ConsoleScreen): DivOverlayFrame {
        val rowCount = screen.visibleRows().size
        if (rowCount <= 0) return DivOverlayFrame.EMPTY
        val lineHeight = composer.metrics.lineHeight
        if (lineHeight <= 0) return DivOverlayFrame.EMPTY
        val scan = screen.divOverlayScan(composer.canvasHeightPx / lineHeight + 1)
        if (scan.isEmpty()) return DivOverlayFrame.EMPTY
        val base = screen.visibleRowBase
        val firstIndex = screen.log.firstIndex
        val rows = ArrayList<DivOverlayRow>(scan.size)
        for (r in scan) {
            // windowRow 可为负（窗口上沿外的 margin 行）：rowTopPx 对它是线性外推，
            // 与「正文第 0 排被画布裁掉」的同一套几何。
            rows.add(DivOverlayRow(r.blocks, composer.rowTopPx(r.retainedIndex - base, rowCount), r.lineNo))
        }
        return composer.divPlanner.planFrame(
            rows = rows,
            canvasHeightPx = composer.canvasHeightPx,
            visibleTopLine = firstIndex + base,
            visibleBottomLine = firstIndex + base + rowCount - 1,
        )
    }

    /** 行段覆盖到的命令数（两指针单遍，O(命令数 + 行段数)，不分配）。 */
    private fun countDrawn(commands: List<DrawCmd>, strips: List<DirtyRect>): Int {
        var total = 0
        var s = 0
        var i = 0
        while (i < commands.size) {
            val row = commands[i].row
            while (s < strips.size && strips[s].rowEnd <= row) {
                s++
            }
            if (s >= strips.size) {
                break
            }
            if (row >= strips[s].rowStart) {
                total++
            }
            i++
        }
        return total
    }

    private companion object {
        /** 默认动画时钟（单调毫秒；与 [CbgDrawOrder.CbgAnimeClock] 的缺省同源）。 */
        val DEFAULT_ANIME_TIME_SOURCE: () -> Long = { System.nanoTime() / 1_000_000L }
    }
}
