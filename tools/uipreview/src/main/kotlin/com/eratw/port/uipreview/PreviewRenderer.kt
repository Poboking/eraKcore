package com.eratw.port.uipreview

import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.render.Grid
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.GlyphCache
import com.eratw.port.render.glyph.GlyphRaster
import com.eratw.port.render.glyph.TextMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import com.eratw.port.render.view.ConsoleFrame
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.ScreenComposer
import com.eratw.port.render.view.ScreenLayout
import com.eratw.port.render.view.ScreenScene
import com.eratw.port.render.adapter.ConsoleFeed
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * 桌面渲染装配体（CONTRACT-UP1 §4.3-4.5）：真 core-vm 会话 + 真 render 链的组装点。
 *
 * ## 几何反推（`--columns` / `--screen` / 设备档案，权威 = `ScreenLayout`）
 *
 * - `columns`（半角格，缺省 110）= `GameSession.consoleWidth`（core 侧折行宽）与
 *   `ConsoleScreen.clientWidth`（render 侧窗口宽）—— 两处必须同值（`ConsoleFeed` 构造期要求，
 *   ConsoleFeed.kt:77）。
 * - 字号/行高由 [ScreenLayout.fixedColumns] 统一反推（UP3 下沉的真机公式，
 *   `CanvasConsoleView.widthOnlyMetrics` 同式：`font = screenW / (columns*1.04/2 + 1/6 + 0.03)`
 *   `roundToInt` 收整；低于字号下限退 `18dp × densityDpi/160` 兜底）。组长接线后本类
 *   不再自持反推式；整数字号的固有偏差（画布宽与 `--screen` 宽差 ≤ columns 像素，
 *   极端宽兜底档除外）如实登记，不做亚像素缩放。
 * - `heightLines = screenH / lineHeight`（向下取整，至少 1）；
 *   `ScreenComposer.canvasHeightPx` 显式传 `screenH` —— 画布高**精确**等于 `--screen` 高
 *   （「内部分辨率固定为该值」），顶上不足一行的余数由绘制口径的负 y 溢出行吃掉
 *   （`ScreenComposer.rowTopPx` 可为负、`Raster` 静默裁剪，与真机同构）。
 *
 * ## 线程契约（ADR-003「单生产单消费」在本工具内的映射，KDoc 钉死）
 *
 * - **VM 线程（生产者）**：`GameSession.begin(TITLE)` + `resumeSystem()` 循环（[runSessionLoop]，
 *   停转条件照 `GameHost.pumpUntilTerminal`：close / QUIT / BLOCKED / FAILED）。它写 console
 *   sink（core 内部），并通过 [JvmGraphicsSink] 写 `GraphicsLayer` 队列 —— 与 app 生产装配同线程
 *   归属（`GameHost.installGraphics` 的 KDoc 明示 sink 在 VM 线程被调用）。
 * - **泵线程（唯一消费者）**：只有它在 [pumpOnce] 里触碰 `ConsoleFeed` / `ConsoleScreen` /
 *   `ConsoleFrame` / `ButtonLayer` / `Viewport`（ADR-003 的消费侧独占）。周期调
 *   `ConsoleFeed.pump()`（revision 不变零成本，ConsoleFeed.kt:120-128），有变化才
 *   `ConsoleFrame.draw` → PNG 编码 → 发布不可变快照进 [publishedFrame]。
 * - **HTTP 线程（观察者 + 提交者）**：只读 [publishedFrame] / [publishedUi] 两个
 *   `AtomicReference` 与 `session.inputSink.awaiting` / `awaitingAnyKey`（`@Volatile` 只读快照，
 *   QueueInputSink 的 KDoc 明示允许跨线程观察）；写入只走两条路：`QueueInputSink.submit`
 *   （内部锁，多 HTTP 线程并发提交安全）与 [requestTap] / [requestScroll] 的请求队列 ——
 *   tap 要读 `ButtonLayer`、scroll 要动 `Viewport`，两者都归泵线程独占，所以请求进队、
 *   泵线程排空执行（[drainRequests]）。
 */
class PreviewRenderer(
    val session: GameSession,
    /** 目标列数（半角格）。 */
    val columns: Int,
    /** `--screen` 宽（像素）。 */
    val screenW: Int,
    /** `--screen` 高（像素）；同时是画布的精确高度。 */
    val screenH: Int,
    /**
     * 设备密度（`DisplayMetrics.densityDpi` 口径，设备档案经 serve 贯通进来）：
     * 只进 [ScreenLayout.fixedColumns] 的 18dp 兜底——反推字号低于
     * [CellMetrics.MIN_FONT_PIXEL_SIZE] 时按 `18dp` 落像素（真机 `widthOnlyMetrics` 同解）。
     * 裸 `--screen`（无档案）缺省 160。
     */
    densityDpi: Int = ScreenLayout.DENSITY_BASE_DPI,
    /** 单次 `resumeSystem` 的墙钟预算（毫秒，0=关）；见 [WallGuard]。 */
    wallMs: Long = DEFAULT_WALL_MS,
    /**
     * 字形光栅器覆盖（CONTRACT-UP2 §4.2）：null（缺省）= 用 [AwtGlyphRaster]（真字形）；
     * 布局对拍测试注 [com.eratw.port.render.glyph.StaticGlyphRaster] 进来。两实例共享同一
     * [textMetrics]（对拍同源的硬要求）。
     */
    rasterOverride: GlyphRaster? = null,
) {

    init {
        require(columns >= 1) { "columns 必须至少 1 半角格，实到 $columns" }
        require(screenW >= 1 && screenH >= 1) { "--screen 尺寸必须为正，实到 ${screenW}x$screenH" }
    }

    /**
     * 生效版面——字号/行高/列宽的**唯一权威**（组长接线：`ScreenLayout.fixedColumns`，
     * 与真机 `CanvasConsoleView` 同一份下沉公式；UP1 时代的 floor/ceil 择近旧式退役，
     * 极端宽（低于字号下限）从贴 8px 改为 18dp×密度兜底 = 真机行为）。
     */
    val layout: ScreenLayout = ScreenLayout.fixedColumns(screenW, densityDpi, columns)

    /** 反推出的生效字号（像素）：[layout] 的字号。 */
    val fontPixelSize: Int = layout.fontPixelSize

    /** 生效度量：[layout] 的实例（必须与 `ConsoleFeed` 的 metrics 同一实例，该类构造期要求）。 */
    val metrics: CellMetrics = layout.metrics

    /** 命中窗口行数。 */
    val heightLines: Int = layout.heightLinesFor(screenH)

    /** 渲染侧屏幕（消费侧装配体）。 */
    val screen: ConsoleScreen = ConsoleScreen(
        clientWidth = columns,
        maxLog = Grid.MAX_LOG_DEFAULT,
        heightLines = heightLines,
    )

    /**
     * 按名取图口（sprite 表 → 文件兜底；同时喂 [feed] 的展平询宽与 [InkPainter]）。
     *
     * 声明在 [composer] 之前：`ScreenComposer` 的 div 子行展平器要拿同一份尺寸源
     * （`<img>` 缺宽推宽 + 图元 miss 判定的唯一入口），Kotlin 属性初始化按声明顺序执行。
     */
    val imageFrames: JvmImageFrames = JvmImageFrames(session.gameRoot, session.store.aux)

    /** 几何与排版换算（画布高精确 = `--screen` 高，见类 KDoc）。 */
    val composer: ScreenComposer = ScreenComposer(
        clientWidthCells = columns,
        heightLines = heightLines,
        metrics = metrics,
        canvasHeightPx = screenH,
        // div 子行里的 `<img>` 与正文同一条尺寸源（原版 `ConsoleImagePart.cs:60-71` 的
        // 查表在构造期只有一次机会）：喂了才判得出「图元 miss ⇒ 按 AltText 当文本」，
        // 不喂就退回旧的见方占位 / 静默跳过。
        sizeSource = imageFrames,
    )

    private val frame = ConsoleFrame(composer)

    /**
     * 度量旁表（CONTRACT-UP2 §4.2）：接 render classpath 的 §3 成品（c-fontsub 交付面），
     * `substituteFamily = "Saitamaar"`（语料自带字体的子集，font-pack-manifest.txt）补
     * 默认族「黑体」的缺表位 —— FontSidecarCatalog 回退链（FontSidecar.kt:428-440）的
     * 装配决策。StaticGlyphRaster 与 AwtGlyphRaster **共享同一实例**（布局对拍同源）。
     */
    val textMetrics: TextMetrics = TextMetrics(
        FontSidecarCatalog(defaultFamily = CellMetrics.DEFAULT_FONT_NAME).apply {
            reader = TextMetrics.classpathReader()
            substituteFamily = SUBSTITUTE_FONT_FAMILY
        },
        metrics,
    )

    /** 字形光栅器（真字形；测试可经 [rasterOverride] 注 Static 占位）。 */
    val glyphRaster: GlyphRaster =
        rasterOverride ?: AwtGlyphRaster(cellMetrics = metrics, metrics = textMetrics)

    /**
     * 字形缓存（真墨迹的取数口）：**归泵线程独占**（GlyphCache 非线程安全，ADR-003
     * 消费侧独占同款）；advance 全部来自 [textMetrics]（铁不变量：AWT 只出墨）。
     */
    val glyphCache: GlyphCache = GlyphCache(DEFAULT_GLYPH_CACHE_ENTRIES, glyphRaster, metrics)

    /** 图形层：内联段并发队列 + 背景层快照；准入判据 = 整幅基图（原版 `is SpriteF` 口径）。 */
    val graphicsLayer: GraphicsLayer = GraphicsLayer(backgroundAdmissible = imageFrames::backgroundAdmissible)

    /** 帧后处理真墨迹（擦占位 / 垫背景 / 字形与图形段铺墨；见 [InkPainter] 类文档）。 */
    private val inkPainter: InkPainter = InkPainter(composer, metrics, glyphCache, imageFrames)

    /**
     * CBG 图层的名字 → [Raster] 面（D 刀：预览显示 CBG）。
     *
     * `ScreenComposer.drawCbgLayer` 在 headless 侧只认 [SpriteTable] 里的名字；真机那边
     * 走 `ImageFrameSource`（`SpriteBitmapSource.frame`）。本工具没有那份表，于是在泵线程
     * 把 `JvmImageFrames.frame(name)` 的帧裁剪成一张独立 [Raster] 登记进来 ——
     * 裁剪范围 = 帧的 `srcX/srcY/srcW/srcH`，**不含** `DestBaseSize` 缩放（缩放由
     * `drawCbgLayer` 按快照的 `widthPx/heightPx` 做，与真机同一套 DTO 口径）。
     *
     * 线程：只在泵线程读写（ADR-003 消费侧独占，与 [glyphCache] 同一条）。
     */
    private val cbgSprites: SpriteTable = SpriteTable()

    /** 已登记进 [cbgSprites] 的名字（为空名补登记一次；图后来才就绪时不重试，见 `pumpOnce`）。 */
    private val cbgSpriteNames: MutableSet<String> = HashSet()

    /**
     * 本帧的 [ScreenScene]（D 刀）：把 `GraphicsLayer.cbgLayers` 带进合成。
     *
     * 只有 CBG 一张面：背景层仍由 [InkPainter] 后处理（理由见其类文档第 2 趟），
     * 立绘摆位在预览里没有 producer（`ScreenScene.sprites` 是测试面）。**已知次序差**：
     * `InkPainter` 的垫背景发生在 `compose` 之后，于是「有 `SETBGIMAGE` 且有 CBG」时
     * 背景会盖住 CBG —— 原版次序是 bg(`EmueraConsole.cs:1817-1820`) → cbg(`:1834-1848`)
     * → 正文。`InkPainter` 不在本刀文件面，TODO(verify) 登记给 Lead（卡点：
     * 垫背景要挪进 `compose`，那需要 `drawSprite` 支持 contain 缩放 + 擦占位不能毁背景）。
     *
     * @return 空表 ⇒ [ScreenScene]（缺省实例，零分配路径）
     */
    private fun sceneOf(): ScreenScene {
        val layers = graphicsLayer.cbgLayers
        if (layers.isEmpty()) return ScreenScene()
        for (layer in layers) {
            registerCbgSprite(layer.spriteName)
            registerCbgSprite(layer.spriteNameB)
        }
        return ScreenScene(cbgLayers = layers)
    }

    /** 按名登记一张 CBG 位图（重复名字只裁一次）。 */
    private fun registerCbgSprite(name: String?) {
        if (name == null || !cbgSpriteNames.add(name)) return
        val frame = imageFrames.frame(name) ?: return
        val decoded = frame.handle as? DecodedPixels ?: return
        val raster = cropFrame(decoded, frame.srcX, frame.srcY, frame.srcW, frame.srcH) ?: return
        cbgSprites.add(name, raster)
    }

    /**
     * 从整幅解码像素里裁出帧矩形（越界夹取；宽高非正 = 整幅）。
     *
     * 与 `SpriteBitmapSource.spriteFrame` 的静态分支同式（`:236-240` 的夹取）；
     * 动画帧的源矩形在 core 侧已按 `DestBaseSize` 归一（`SPRITEANIMEADDFRAME` 的
     * `Normalize` 段），这里只需守父图边界。
     */
    private fun cropFrame(decoded: DecodedPixels, srcX: Int, srcY: Int, srcW: Int, srcH: Int): Raster? {
        if (decoded.width <= 0 || decoded.height <= 0) return null
        val x = srcX.coerceIn(0, decoded.width - 1)
        val y = srcY.coerceIn(0, decoded.height - 1)
        val w = (if (srcW > 0) srcW else decoded.width).coerceIn(1, decoded.width - x)
        val h = (if (srcH > 0) srcH else decoded.height).coerceIn(1, decoded.height - y)
        val raster = Raster(w, h)
        for (row in 0 until h) {
            System.arraycopy(decoded.pixels, (y + row) * decoded.width + x, raster.pixels, row * w, w)
        }
        return raster
    }

    /**
     * 核心层行缓冲 → 渲染层屏幕的桥（生产喂法，`ConsoleFeed` 的唯一消费者 = 泵线程）。
     *
     * 不再传第 6 个位置参数：那里原来是「`<img>` 最大绘制宽」，2026-10-26 按 ADR-005 去掉了
     * 这条移植侧发明的夹宽（`HtmlRunFlatten.emitImage` 的 KDoc）。竖带合并的两个参数保持缺省 0
     * （= 不合并），预览因此呈现的是**未合并**的原生排版；真机由 `CanvasConsoleView`
     * 喂 `bandAvailWidthPx`/`bandMaxHeightPx` 打开合并，两者的差异是既有的。
     */
    val feed: ConsoleFeed = ConsoleFeed(
        session.console,
        screen,
        graphicsLayer,
        metrics,
        imageFrames,
        // 脚本 px 基准字号（2026-11-05 接上）：与 sink 的 `scriptFontSize` 同源
        // （都是会话生效配置字号）。此前有意缺省 0（px 即设备像素的旧口径），那时
        // 折行把图形计 0 格、两边没有可对齐的东西；遗留①收口后折行与渲染必须同尺，
        // 预览不接的话 HTML img/shape 行在预览里就是「折行 24 格、实占 13 格」的错位
        // 形态（2026-11-05 桌面截图实测）。`ImageFrameTest` 钉的是 PRINT_IMG /
        // PRINT_RECT（JvmGraphicsSink 路径，不经 HtmlRunFlatten），本参数不触它。
        scriptFontPixelSize = session.scriptFontPixelSize,
    )

    /** 墙钟预算：VM 循环记进度，`GuardTimerSink` 在 AWAIT 检查。 */
    val guard: WallGuard = WallGuard(wallMs)

    init {
        // 两个构造期注入点（与 app/vmdbg 同款）：图形 sink 与 AWAIT 墙钟闸。
        // JvmGraphicsSink 走 PRINT_IMG/PRINT_RECT 路径（无脚本 px 折算参数）；
        // HTML img/shape 的脚本尺在 [feed] 的 `scriptFontPixelSize`（与 sink 同源）。
        // `sizeSource` 与 [feed] / [composer] 同一条：PRINT_IMG 的图元 miss 也按 AltText
        // 量宽（原版 `ConsoleImagePart.cs:60-65` 的单出口），不喂就仍是见方占位。
        session.runtime.execContext.graphics = JvmGraphicsSink(
            graphicsLayer,
            metrics,
            sizeSource = imageFrames,
        )
        session.runtime.execContext.timer = GuardTimerSink(guard)
    }

    // ------------------------------------------------------------------
    // 发布面（HTTP 线程只读）
    // ------------------------------------------------------------------

    /** 已发布的一帧：PNG 字节 + 帧序号（`/frame.png?rev=` 的 rev 就是它，单调递增）。 */
    class FrameShot(val frameRev: Long, val width: Int, val height: Int, val png: ByteArray)

    /** 一颗按钮的命中信息快照（泵线程算好，HTTP 线程直接读；坐标是画布像素）。 */
    class UiButton(
        val hitRow: Int,
        val colStart: Int,
        val colEnd: Int,
        val label: String,
        val value: Any?,
        val xPx: Int,
        val yPx: Int,
    )

    /**
     * 一颗 div 子行按钮的 `/state` 快照（DIV-WIRE-2 余项④）。
     *
     * 与 [UiButton] 的分工：那个是**格**命中表（`ConsoleScreen.buttons.all()`）的投影，
     * 只覆盖普通行；div 子行按钮住的是命中层的**像素**条目（`ButtonLayer.divTargets()`，
     * 造表口径照 `ConsoleDivPart.cs:137-172` 的 TestChildHitbox），用本类型单独发布。
     *
     * **几何口径（与 [UiButton] 同一把尺，与绘制几何分开）**：
     * - 本类型 = **命中geometry**：直接搬命中层条目自带的设备像素
     *   （`ButtonLayer` 建表时按 `cellPx` / `rowHeightPx` 折好的 run 命中矩形：
     *   x = 盒左 + 内缩 + 格游标像素、y = 子行带顶、w = 格数 × 格宽、h = 一行高），
     *   序列化侧**原样输出**，不再查 `composer.rowTopPx` / `metrics.cellsToPixels` 折算 ——
     *   保证「报出来的点 = 点得中的点」。
     * - 若同一 `/state` 里存在 `divs[]`（绘制快照键），那是**绘制几何**：宽 = `widthPx + 2`
     *   （`ConsoleDivPart.cs:178` 的官方 fudge）、子行 y 由行顶表折算。两者**不许互相折算**。
     *
     * @property blockIndex 块号：`DivOverlayPlanner.Plan.all`（behindText + inFrontOfText
     *   拼接序，`DivOverlay.kt:107`）的全局下标 —— 与 `ButtonLayer.DivFocus.blockIndex`
     *   同一套编号（组长裁定 2026-11-06）
     * @property childRow div 子行序号（0 起，`ConsoleDivPart.cs:197-203` 的 children 序）
     * @property depth div 覆盖层次（`EmueraConsole.cs:1830` 的 depth 降序）
     * @property colStart / colEnd run 在本子行内的半角格区间（0 起，不含子行 indent 位移）
     * @property xPx / yPx / widthPx / heightPx 命中矩形（画布像素浮点，原样搬）
     * @property value 按钮回环值（Long / String），与 `buttons[].value` 同口径
     * @property label 按钮文案（run 文本拼接）
     */
    class UiDivButton(
        val blockIndex: Int,
        val childRow: Int,
        val depth: Int,
        val colStart: Int,
        val colEnd: Int,
        val xPx: Float,
        val yPx: Float,
        val widthPx: Float,
        val heightPx: Float,
        val value: Any?,
        val label: String,
    )

    /** `/state` 的按钮面快照：sink 版本 + 按钮表 + 行数读数 + div 子行按钮。 */
    class UiSnapshot(
        val sinkRev: Long,
        val buttons: List<UiButton>,
        val hitRows: Int,
        val visibleRows: Int,
        /**
         * div 子行里的可点条目（DIV-WIRE-2 余项④）。
         *
         * 数据源 = `ConsoleScreen.divTargets()`（命中层的像素条目，见 [UiDivButton]）；
         * 本类只做「命中层条目 → 快照」的搬运，px **原样搬**，不二次折算。
         * 缺省空表：没有 div 按钮的帧与接线前逐字节相同。
         */
        val divButtons: List<UiDivButton> = emptyList(),
    )

    private val frameSeq = AtomicLong(0L)
    private val publishedFrame = AtomicReference<FrameShot?>(null)
    private val publishedUi = AtomicReference<UiSnapshot?>(null)

    /** 当前已发布的帧；null = 还没有出过帧。 */
    val currentFrame: FrameShot? get() = publishedFrame.get()

    /** 当前按钮/行数快照；null = 泵还没跑过。 */
    val currentUi: UiSnapshot? get() = publishedUi.get()

    /** VM 循环落进去的失败描述（BLOCKED / FAILED / 异常终局）；null = 没有失败。 */
    @Volatile
    var failure: String? = null
        private set

    /** VM 循环是否已结束（QUIT 或终局）。 */
    @Volatile
    var vmEnded: Boolean = false
        private set

    /** VM 循环记一次墙钟进度（[runSessionLoop] 每次推进返回后调）。 */
    fun progress() {
        guard.progress()
    }

    /** VM 循环落失败终局（[runSessionLoop] 专用入口；[failure] 保持私有 set）。 */
    internal fun noteFailure(message: String) {
        failure = message
        vmEnded = true
    }

    /** VM 循环落 QUIT 终局。 */
    internal fun noteQuit() {
        vmEnded = true
    }

    // ------------------------------------------------------------------
    // 请求队列（HTTP 线程生产 → 泵线程消费）
    // ------------------------------------------------------------------

    private val tapQueue = ConcurrentLinkedQueue<Pair<Int, Int>>()
    private val scrollQueue = ConcurrentLinkedQueue<Int>()

    /** 提交一次点击（画布像素坐标；命中与否由泵线程判）。恒受理，结果看下一帧/等待态。 */
    fun requestTap(x: Int, y: Int) {
        tapQueue.add(x to y)
    }

    /** 提交一次滚动：正 = 向下翻（贴底方向），负 = 向上翻历史。 */
    fun requestScroll(rows: Int) {
        if (rows != 0) scrollQueue.add(rows)
    }

    private var scrollDirty = false

    /** 上一次重画时的背景层水位（泵线程独占；层换代判脏用，见 [pumpOnce]）。 */
    private var lastPaintedLayerRev = 0L

    private fun drainRequests() {
        while (true) {
            val tap = tapQueue.poll() ?: break
            handleTap(tap.first, tap.second)
        }
        while (true) {
            val rows = scrollQueue.poll() ?: break
            handleScroll(rows)
        }
    }

    /**
     * 命中链（CONTRACT-UP1 §4.5；与真机同代码，只有坐标换算是本路的）：
     * 画布像素 → 半角格列（`CellMetrics.pixelsToCells`，与绘制的 `cellsToPixels` 同一条量化式）
     * → 底对齐行式（`ScreenComposer.rowTopPx = canvasH - (rowCount - row) * lineHeight` 的逆：
     * `vRow = rowCount - ceil((canvasH - y) / lineHeight)`）→ 按
     * `hitRowBase - visibleRowBase` 换到命中口径（`ConsoleScreen.hitRowToSnapshotRow` 的反向）
     * → `ConsoleScreen.click(row, col, acceptance)`。
     *
     * 命中 → `selectedString` 进 `QueueInputSink.submit(text, false)`；未命中 → 空白点击口径
     * [blankTap]（参照 app 侧 `BlankTapPolicy`：只有「正等回车/任意键」才补空回车，
     * 数值/字符串等待不补 —— 原版 `MainWindow.cs:806` 的 `IsWaitingEnterKey` 判据）。
     */
    private fun handleTap(x: Int, y: Int) {
        if (x < 0 || y < 0 || y >= composer.canvasHeightPx) {
            blankTap()
            return
        }
        val col = metrics.pixelsToCells(x)
        val rowCount = screen.visibleRows().size
        val fromBottom = composer.canvasHeightPx - y
        val idxFromBottom = ceil(fromBottom / metrics.lineHeight.toDouble()).toInt().coerceAtLeast(1)
        val vRow = rowCount - idxFromBottom
        if (rowCount <= 0 || vRow < 0 || vRow >= rowCount) {
            blankTap()
            return
        }
        val hitRow = vRow - (screen.hitRowBase - screen.visibleRowBase)
        val acc = acceptance()
        val text = screen.click(hitRow, col, acc)
        if (text != null) {
            session.inputSink.submit(text, false)
        } else {
            blankTap()
        }
    }

    /** 空白点击：只有等回车/任意键才补空回车（BlankTapPolicy 口径，见 [handleTap] 的 KDoc）。 */
    private fun blankTap() {
        if (session.inputSink.awaitingAnyKey) {
            session.inputSink.submit("", false)
        }
    }

    /** 等待类型 → 按钮可点性（`GameHost.acceptance()` 的 `GameHost.kt:485-493` 同款映射）。 */
    private fun acceptance(): ButtonAcceptance {
        val sink = session.inputSink
        if (sink.awaitingAnyKey) return ButtonAcceptance.ANY
        return when (sink.awaiting?.type) {
            InputRequest.InputType.INT -> ButtonAcceptance.INT
            InputRequest.InputType.STR -> ButtonAcceptance.STR
            null -> ButtonAcceptance.ANY
        }
    }

    /**
     * 滚动：`Viewport.scrollDown/scrollUp`（`scroll/Viewport.kt:133-138`）+ `screen.refresh()`。
     * 视口变化不进 sink revision，所以置 [scrollDirty] 让下一次 [pumpOnce] 强制整屏重画
     * （`ConsoleFrame.invalidate` —— 按行重绘的行段基准是上一次提交，视口挪动后整屏最稳，
     * 桌面 MVP 不差这一次全量）。
     */
    private fun handleScroll(rows: Int) {
        val vp = screen.viewport
        if (rows > 0) vp.scrollDown(rows) else vp.scrollUp(-rows)
        screen.refresh()
        scrollDirty = true
    }

    // ------------------------------------------------------------------
    // 泵循环
    // ------------------------------------------------------------------

    /**
     * 泵一次：排空请求 → `ConsoleFeed.pump()`（revision 不变零成本）→ 有变化（或首帧）
     * → `ConsoleFrame.draw` → [InkPainter] 真墨迹后处理 → PNG 编码 → 发布。返回是否发布了新帧。
     *
     * 必须只在泵线程调（线程契约见类 KDoc；GlyphCache / InkPainter 也归泵线程独占）；
     * 测试把它当同步函数用。
     */
    fun pumpOnce(): Boolean {
        drainRequests()
        val rects = feed.pump()
        // 背景层换代判脏：ConsoleFeed.pump 只看 sink.revision 与内联队列（ConsoleFeed.kt:120-128），
        // 不看 GraphicsLayer.revision —— 单独的 SETBGIMAGE/REMOVEBGIMAGE（无文本输出伴随）不会
        // 触发重发布，背景垫图会迟到一页。泵线程用层水位补这个缺口（层快照读侧语义见
        // GraphicsLayer 的「不可变快照」注）。
        val layerRev = graphicsLayer.revision.get()
        val layerChanged = layerRev != lastPaintedLayerRev
        lastPaintedLayerRev = layerRev
        val dirty = rects.isNotEmpty() || scrollDirty || layerChanged
        scrollDirty = false
        if (publishedFrame.get() != null && !dirty) {
            publishUi()
            return false
        }
        // UP2：每个发布帧都全量重绘 —— InkPainter 的擦占位/垫背景三趟后处理建立在
        // 「画布是本帧 compose 的完整结果」之上；桌面工具性能非目标，且背景/立绘路径
        // render 本就强制全量（ConsoleFrame 类文档）。滚动强制全量的旧语义被它覆盖。
        frame.invalidate()
        val raster = frame.draw(screen, rects, sceneOf(), cbgSprites)
        inkPainter.paint(raster, screen, graphicsLayer.background)
        publishUi()
        if (raster.width <= 0 || raster.height <= 0) {
            return false
        }
        val shot = FrameShot(frameSeq.incrementAndGet(), raster.width, raster.height, encodePng(raster))
        publishedFrame.set(shot)
        return true
    }

    /** 按钮表/行数快照（泵线程读 `ButtonLayer`/`ConsoleScreen`，HTTP 线程只读结果）。 */
    private fun publishUi() {
        val rowCount = screen.visibleRows().size
        val buttons = screen.buttons.all().map { t ->
            val snapRow = screen.hitRowToSnapshotRow(t.row)
            val y = if (snapRow != null) composer.rowTopPx(snapRow, rowCount) + metrics.lineHeight / 2 else -1
            UiButton(
                hitRow = t.row,
                colStart = t.colStart,
                colEnd = t.colEnd,
                label = t.label,
                value = t.value,
                xPx = metrics.cellsToPixels((t.colStart + t.colEnd) / 2),
                yPx = y,
            )
        }
        // div 子行按钮（DIV-WIRE-2 余项④）：数据源 = `ConsoleScreen.divTargets()`（命中层
        // 像素条目）。px **原样搬**（不折算，见 [UiDivButton] 的 KDoc）。
        // 前提：帧入口（`ConsoleFrame.draw`）已调 `screen.updateDivOverlay(...)` 喂同帧规划，
        // 否则命中表为空 —— 头less 路径的这行接线在 `ConsoleFrame` 帧入口内。
        publishedUi.set(
            UiSnapshot(
                sinkRev = session.console.revision,
                buttons = buttons,
                hitRows = screen.hitRows().size,
                visibleRows = rowCount,
                divButtons = buildUiDivButtons(screen.divTargets()),
            ),
        )
    }

    /** 起泵线程（守护线程，[pollMs] 周期；`stopped` 置位后退出）。 */
    fun startPump(pollMs: Long = 30L): Thread {
        val t = Thread({
            while (!stopped) {
                try {
                    pumpOnce()
                } catch (t: Throwable) {
                    // 泵线程是消费者，异常不能无声吞掉：记录进 [pumpError] 供 /state 暴露，
                    // stderr 留痕给进程日志；下一周期照常重试（半态由各组件自己的幂等性兜住）。
                    pumpError = "${t.javaClass.simpleName}: ${t.message}"
                    System.err.println("uipreview pump error: $pumpError")
                }
                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "uipreview-pump")
        t.isDaemon = true
        t.start()
        return t
    }

    /** 最近一次泵循环异常（诊断面；null = 没有异常）。 */
    @Volatile
    var pumpError: String? = null
        private set

    @Volatile
    private var stopped = false

    /** 停泵并关会话（关闭输入队列，阻塞中的 VM 线程按 `InputSinkClosedException` 收敛）。 */
    fun close() {
        stopped = true
        runCatching { session.close() }
    }

    companion object {
        /**
         * 度量链的替代族（CONTRACT-UP2 §4.2）：`Saitamaar` = 语料自带字体
         * （`font/Saitamaar.ttf`）的随包子集家族名（subset-build-manifest.txt 的 family-name），
         * 与 [AwtGlyphRaster] 的 fallbackResource 同源 —— 墨迹家族 = advance 家族的硬约束。
         */
        const val SUBSTITUTE_FONT_FAMILY = "Saitamaar"

        /** 字形缓存条目上限：预览窗口一屏字形几百个，1024 够整屏常驻。 */
        const val DEFAULT_GLYPH_CACHE_ENTRIES = 1024

        /**
         * serve 的墙钟预算缺省（毫秒）：单次 `resumeSystem` 内的 AWAIT 风暴兜底。
         * `TODO(verify)`：真语料 LazyloadingV3 装载环单次推进的真实时长无桌面读数，
         * 若误杀长装载，用 `--wall-ms 0` 关闭（旋钮在 [Cli]）。
         */
        const val DEFAULT_WALL_MS = 120_000L

        /**
         * 字号反推（组长接线后 = [ScreenLayout.fixedColumns] 的同式委托）。
         * UP1 时代的 floor/ceil 双候选择近旧式已退役：旧式在极端宽（390px/110 列）贴
         * 8px 字号下限，真机权威是 `18dp × density` 兜底；正常宽度档两式同解
         * （2400/110 → 42），差异点钉在 ScreenLayoutTest 的对拍表。
         */
        fun deriveFont(screenW: Int, columns: Int, densityDpi: Int = ScreenLayout.DENSITY_BASE_DPI): Int =
            ScreenLayout.fixedColumns(screenW, densityDpi, columns).fontPixelSize

        /**
         * 命中层的 div 条目 → `/state` 的 divButtons 快照（**纯函数**：不碰渲染器状态，便于单测）。
         *
         * 几何**原样搬** [ButtonLayer.DivHit] 的 Float 像素：那是命中层按 `cellPx` /
         * `rowHeightPx` / 行顶公式折好的 **run 命中矩形**（x = 盒左 + 格游标像素、
         * y = 子行带顶、w = 格数 × 格宽、h = 纵带高，见 `ButtonLayer.attachDiv`），
         * 这里**不取整也不折算** —— 不再查 `composer.rowTopPx` / `metrics.cellsToPixels`
         * （那会变成第二套算术，出现「报出来的点 ≠ 点得中的点」）。
         *
         * 与绘制几何（若同帧存在 `divs[]` 快照键：宽 = `width + 2`、y 由行顶表折算，
         * `ConsoleDivPart.cs:178`）是两个面，见 [UiDivButton] 的 KDoc。
         */
        internal fun buildUiDivButtons(targets: List<ButtonLayer.DivHit>): List<UiDivButton> {
            if (targets.isEmpty()) {
                return emptyList()
            }
            val out = ArrayList<UiDivButton>(targets.size)
            for (hit in targets) {
                out.add(
                    UiDivButton(
                        blockIndex = hit.blockIndex,
                        childRow = hit.childRow,
                        depth = hit.depth,
                        colStart = hit.colStart,
                        colEnd = hit.colEnd,
                        xPx = hit.xPx,
                        yPx = hit.yPx,
                        widthPx = hit.widthPx,
                        heightPx = hit.heightPx,
                        value = hit.value,
                        label = hit.label,
                    ),
                )
            }
            return out
        }
    }
}

/**
 * `Raster`（`0xAARRGGBB` 行主序 IntArray）→ PNG 字节。
 * `BufferedImage.setRGB` 的 int 数组语义就是 ARGB 打包，与 `Raster.pixels` 同构，零转换。
 */
internal fun encodePng(raster: Raster): ByteArray {
    val img = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, raster.width, raster.height, raster.pixels, 0, raster.width)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

/** VM 循环的终局分类（`serve` 的退出码映射见 [Cli]）。 */
enum class TerminalKind {
    /** 脚本 QUIT，正常收尾。 */
    QUIT,

    /** 系统状态机停转（BLOCKED / FAILED）。 */
    BLOCKED,

    /** 运行期异常（含墙钟超限 [WallLimitExceeded]、`InputSinkClosedException` 之外的 Throwable）。 */
    ERROR,
}

/** VM 循环终局：类别 + 人读消息（`/state` 的 failure 与 serve 退出码共用）。 */
class Terminal(val kind: TerminalKind, val message: String?)

/**
 * VM 循环（生产者）：`begin(TITLE)` + `resumeSystem()` 重直到停转。
 * 停转条件与消息格式照 `GameHost.pumpUntilTerminal`（`GameHost.kt:1005-1056`）：
 * 只有 close（`InputSinkClosedException` → ERROR 收敛）、QUIT（`runtime.quitRequested`）、
 * BLOCKED/FAILED 三类出口；其余 outcome（AWAIT_INPUT / CONTINUE / CALLED / REENTERED）照旧循环。
 * 每次 `resumeSystem` 返回记一次墙钟进度（[PreviewRenderer.progress]）。
 *
 * 只能在 VM 线程调一次；[renderer] 可为 null（纯会话装置，无渲染面）。
 */
fun runSessionLoop(session: GameSession, renderer: PreviewRenderer?): Terminal {
    val beginOk = try {
        session.begin(BeginType.TITLE)
    } catch (t: Throwable) {
        val msg = "begin(TITLE) 异常：${t.message ?: t.javaClass.simpleName}"
        renderer?.noteFailure(msg)
        return Terminal(TerminalKind.ERROR, msg)
    }
    if (!beginOk) {
        val msg = "begin(TITLE) 被拒：函数表里没有 SYSTEM_TITLE（产物与侧车不配对或产物是旁路编译档）"
        renderer?.noteFailure(msg)
        return Terminal(TerminalKind.ERROR, msg)
    }
    while (!session.runtime.quitRequested) {
        val step = try {
            session.resumeSystem()
        } catch (e: WallLimitExceeded) {
            renderer?.noteFailure(e.message ?: "墙钟超限")
            return Terminal(TerminalKind.ERROR, e.message)
        } catch (e: InputSinkClosedException) {
            renderer?.noteFailure("输入队列已关闭（宿主退出）")
            return Terminal(TerminalKind.ERROR, e.message)
        } catch (t: Throwable) {
            // CodeEE 带脚本位置时一并落进失败串（诊断用；无位置的异常保持原格式）。
            val where = (t as? com.eratw.port.core.err.CodeEE)?.let { e ->
                if (e.funcName == null && e.line < 0 && e.pc < 0) null
                else "@${e.funcName ?: "?"}#${e.line} (pc=${e.pc})"
            }
            val msg = "VM 异常：${t.javaClass.simpleName}: ${t.message}" +
                (where?.let { " [$it]" } ?: "")
            renderer?.noteFailure(msg)
            return Terminal(TerminalKind.ERROR, msg)
        }
        renderer?.progress()
        if (step.outcome == StepOutcome.BLOCKED || step.outcome == StepOutcome.FAILED) {
            val msg = "系统状态机停在 ${step.from.name}：" +
                (step.blocker?.message ?: "未知阻塞") +
                "（${step.blocker?.evidence ?: "无证据"}）"
            renderer?.noteFailure(msg)
            return Terminal(TerminalKind.BLOCKED, msg)
        }
    }
    renderer?.noteQuit()
    return Terminal(TerminalKind.QUIT, null)
}
