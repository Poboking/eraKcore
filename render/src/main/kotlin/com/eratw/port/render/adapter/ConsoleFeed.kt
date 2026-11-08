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
 * 本类不提前造锁。[prefetch] 的回调由消费者线程同步调用（只做查表入队），线程与解码责任都在实现方。
 *
 * ## 宽度
 *
 * 折行宽度与显示宽度必须同源，否则列号会漂：构造时要求
 * `sink.clientWidth == screen.clientWidth`，不一致直接抛 [IllegalArgumentException]。
 * 会话期尺寸变化（LIVE-REFLOW）走 [swapScreen]：核心侧先 `sink.resize(newWidth)`，再换一只
 * 同宽的新 [ConsoleScreen]；宽度不一致同样抛错 —— 不许两套坐标系并存。
 *
 * @param sink 生产侧行缓冲（`:core-vm` 的 `LineBufferConsoleSink`）
 * @param screen 消费侧装配体（`:render` 的 `ConsoleScreen`）；本类不改它的任何配置，
 *   只在 [swapScreen] 时整只换掉（对外经只读属性 [ConsoleFeed.screen] 取）
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
    screen: ConsoleScreen,
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
     * 0（缺省）= 不折算，与接入前逐值相同。
     *
     * **它不跟 [updateLayout] 的屏上字号换代走，这是有意而非漏接**（AUD-r2 复查判死，2026-11-04）：
     * 本值是**配置**尺（唯一生产来源 `GameSession.scriptFontPixelSize`，proc/GameSession.kt:546
     * `= effective.fontSize`；同文件 :448-461 明写「不接受宿主注入的真机 page-fit 字号」，
     * 因为语料按钮网格拿 `GETCONFIG("フォントサイズ")` 当尺），而 `updateLayout` 喂的是**屏上**
     * page-fit 尺（render-canvas/.../CanvasConsoleView.kt:1672-1676）—— 两把尺本来就不同源，
     * 让它们互相跟随会把补齐段落折算两次。屏上字号在会话期冻结
     * （同文件 :1584-1595 与其 :1679 的「字号不在会话期重算」），所以「基准与屏上尺不同代」
     * 在生产路径上不可达；真要热更配置字号，出口是**重开会话**（重建 ConsoleFeed 时重新取值），
     * 不是让本参数追 `updateLayout`。
     */
    private val scriptFontPixelSize: Int = 0,
    /**
     * 图来了的通知（**后台预取**入口，生产接线是 `SpriteBitmapSource.prefetchAsync`）。
     *
     * 每次 [publish] 扫一遍展平后的图片段，把**新**资源名交给它（见 [prefetchImages]）。
     * 本类只在 UI 线程做字符串查表与入队，**不解码**；解码与缓存都在回调那边。
     * null（缺省）= 零行为，与接入前逐值相同。
     *
     * 放在参数表最后：既有调用点用的是位置参数（最多到 `sizeSource`）与命名参数，
     * 追加在尾部不会改任何现有实参的绑定。
     */
    private val prefetch: ((String) -> Unit)? = null,
) {

    /**
     * 当前消费侧装配体的实现格。唯一写入口是 [swapScreen]（LIVE-REFLOW 换屏）；
     * 对外一律经只读的 [screen] 取。声明在 `init` 之前：属性初始化与 init 块按声明序执行。
     */
    private var currentScreen: ConsoleScreen = screen

    /**
     * 当前消费侧装配体（只读）。
     *
     * 会话期尺寸变化（LIVE-REFLOW）会把它整只换掉，但**对外仍是只读访问**：读到的一定是
     * 「此刻生效的那一只」。换屏的唯一入口是 [swapScreen]，本类不改屏的任何配置。
     */
    val screen: ConsoleScreen
        get() = currentScreen

    /**
     * 核心 sink 的**当前折行宽**（半角格）。
     *
     * 为什么单独开这个口：`:render` 对 `:core-vm` 是 `implementation` 依赖，[sink] 的类型
     * （`LineBufferConsoleSink`）在 `:render-canvas` / `:app` 的编译面上看不见，消费方只能
     * 读这个纯 `Int`。
     *
     * 它与 [screen] 的 `clientWidth` **可以不相等**，而且那一格正是 LIVE-REFLOW 的交接窗口：
     * 宿主先 `sink.resize(columns)`（本值已是新宽），屏还没换（[screen] 仍是旧宽），
     * 换屏由 [swapScreen] 完成并用 `require` 把两者重新钉回相等。
     * 所以「核心是否已按新列数重排」只能问这里，问 [screen] 会读到上一代的宽度。
     */
    val sinkClientWidth: Int get() = sink.clientWidth

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

    /**
     * LIVE-REFLOW：把消费侧换成按新宽度新建的 [ConsoleScreen]，并把「已发布到旧屏」的记账全部归零，
     * 让下一次发布从空屏重放整份快照。
     *
     * ## 分工（谁调 resize、谁 force）
     *
     * 核心侧的 `sink.resize(newWidth)` 必须**已经**完成，本方法只校验不代做：
     * 折行宽度与显示宽度必须同源（见类文档「宽度」节），两套坐标系并存时列号必漂，
     * 所以宽度不等一律抛 [IllegalArgumentException]，而不是换一半再让调用方收拾。
     *
     * 本方法**不自己 force**：换屏只是「换 + 清」，重放交给调用方。`CanvasConsoleView`
     * 换完屏还要先把像素度量推到新尺寸（`syncFeedLayout()` → [updateLayout]，它会连带 force 一次），
     * 再显式 [force] 兜底 —— 顺序写死在调用方，本类不猜。
     *
     * ## 清掉的状态（全是「与旧屏同生共死」的那几项）
     *
     * - [adaptCache]：整表按行实例缓存上一次 adapt 的产物，旧屏的产物一律重算；
     * - [previousTail]：PRINTN 并入的认领门槛，指的是旧屏那一批末行实例；
     * - [publishedRevision] / [publishedInlineRevision] / [publishedIslandsVersion] 三道发布水位：
     *   置回「还没交过」。于是即使调用方漏了 [force]，下一次 [pump] 也会整屏重放 —— 新屏的
     *   日志是空的，`ConsoleLogSync.submit` 从空缓冲开始逐行追加（浮岛同理，新屏还没有任何浮岛）。
     * - [inlineAnchors]：**本波接受内联图形锚点丢失**。`PRINT_IMG` / `PRINT_RECT` /
     *   `PRINT_SPACE` 已经贴进旧屏那些行，换屏后不再重贴（见「已知差异」）。
     *
     * [metrics]、`flattener`、竖带参数**不动**：它们的换代入口仍是 [updateLayout]，
     * 换屏不改折行策略也不改字号口径。
     *
     * ## 已知差异 / 后续
     *
     * 锚点表当前挂的是 `DisplayLine` 的**实例身份**（见 [attachInline]），换屏后那些实例只对旧屏
     * 的行表有意义 ⇒ 清空是必要的第一步。终态是让锚点跟着核心 journal 的 `logicalId`
     * 走（LIVE-REFLOW L1 契约的 journal 字段），那时换屏可以把内联图形原样重贴。
     *
     * ## 前提
     *
     * [next] 必须是**空屏**（新建的 `ConsoleScreen`）：`submitSnapshot` 的差分会与它自己的
     * 已发布快照比较，带着旧内容的屏会被当成「快照变长」再追加一遍。
     * 只在渲染线程（[pump] 的同一消费者）调用。
     *
     * @return 真的换了屏返回 true；[next] 就是当前屏（无需重建）返回 false，
     *   且**不动任何状态**（水位、缓存、锚点一个都没碰）
     * @throws IllegalArgumentException `next.clientWidth` 与 [sink] 的 `clientWidth` 不一致
     */
    fun swapScreen(next: ConsoleScreen): Boolean {
        require(next.clientWidth == sink.clientWidth) {
            "ConsoleFeed 换屏的宽度必须与 sink 一致：sink.clientWidth=${sink.clientWidth}，" +
                "next.clientWidth=${next.clientWidth}"
        }
        // 同一只屏 = 没有要重建的东西：早退，不碰水位（返回 false 的合法含义见契约 §1）。
        if (next === currentScreen) return false
        currentScreen = next
        adaptCache = ConsoleAdaptCache()
        previousTail = null
        publishedRevision = null
        publishedInlineRevision = null
        publishedIslandsVersion = -1L
        inlineAnchors.clear()
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
     * 已发布进 [screen] 的**内联段到达水位**（null = 还没交过）。
     *
     * 判「有没有待发布的内联段」只认这一格，不再问 `GraphicsLayer.hasPendingInline()`：
     * 那是**队列状态**（发布方自己排空、`reset` 也清队列），拿状态当「发布过没有」的代理，
     * 在「排空了但没交出去」等形态上会与水位分叉。生产侧每次 `GraphicsLayer.enqueue`
     * 推进一格到达版本（`GraphicsLayer.inlineRevision`）。
     *
     * 纪律与 [publishedRevision] 完全相同：**版本先读、后做活，整帧提交成功才记**
     * （见 [publish]）。中途抛异常时水位不动，下一帧自然重试。
     */
    private var publishedInlineRevision: Long? = null

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
     *
     * 整只可换：换屏（[swapScreen]）后旧表里存的是**旧屏那次发布**的产物，一律重算。
     */
    private var adaptCache = ConsoleAdaptCache()

    /** 已经请求过预取的资源名（每个资源只请求一次；超过上限整表清空，见 [prefetchImages]）。 */
    private val prefetchSeen = HashSet<String>()

    /**
     * 有变化才交：sink 的 revision 与内联段到达版本都等于上次发布的水位时返回空表（幂等）；
     * 否则把当前整屏快照交给 [screen]，返回 [ConsoleScreen.submitSnapshot] 的脏区，并记住这次
     * 发布的这两道水位。
     *
     * 「要不要交」是**一次版本比较**（见 [publishedRevision] / [publishedInlineRevision]），
     * 不是对生产侧状态的探测：文字流看 [LineBufferConsoleSink.revision]，图形流看
     * `GraphicsLayer.inlineRevision()`，两者互不覆盖 —— 图形语句不推进 sink 的 revision，
     * 文字流也不推进内联版本，任何一路前进都要交一帧。
     *
     * 单生产单消费：读 revision 与取快照之间生产侧若再写，直通帧（闸开逐条发布）本就允许
     * 读到更新的内容（流式打印语义）；冻结段批量发布的帧由 sink 在发布时刻抓行表
     * （[LineBufferConsoleSink.frameSnapshot]），之后 VM 继续写也不会把中间态漏进这一帧。
     *
     * 「交哪一份」只由 sink 的发布边界裁决（frameSnapshot 恒返回一份发布帧），本类不探测
     * 生产侧队列或行表的中间态；发布判据只做上述两次版本比较。这就是 A2 的发布边界单权威：
     * 水位决定「要不要交」，发布边界决定「交什么」，不存在绕过判据的状态代理。撤掉上游
     * 版本判据的防回归用例见 [FramePublishBoundaryTest] 的「排空队列仍有一版未发布」。
     */
    fun pump(): List<DirtyRect> {
        // 整屏底色（SETBGCOLOR 的活值）与浮岛同理：不参与 revision，每帧「问一次」。
        // 值变了就地更新 [screen]，绘制侧经 [ConsoleScreen.backgroundArgb] 的指纹判定整幅重铺。
        publishBackground()
        // 浮岛不参与 revision（core 侧口径），但要每帧「问一次」。2026-10-29 起不再无条件搬：
        // `sink.htmlIslands()` 是加锁 + 整表拷贝，而地图/外出屏的浮岛可能有几十条字符串
        // （`HTML_PRINT_ISLAND` 画的面板），每帧拷一次纯属给 UI 线程制造分配。改成按水位搬，
        // 内容没变就一次 Long 比较（见 `LineBufferConsoleSink.htmlIslandsVersion`）。
        val islandsVersion = sink.htmlIslandsVersion()
        if (islandsVersion != publishedIslandsVersion) {
            val islands = sink.htmlIslands()
            if (islands.isNotEmpty() || screen.islandHtml().isNotEmpty()) screen.updateIslands(islands)
            // 水位在**做完活之后**才记，与 [publish] 里 `publishedRevision` 同一条纪律：
            // 先记水位再做活，中途抛一次（展平/提交里的 RuntimeException）就把这一版内容
            // 当成「已发布」，之后再也不会重交 —— 屏上永久停在旧内容。反过来记，
            // 异常时水位不动，下一帧自然重试。
            publishedIslandsVersion = islandsVersion
        }
        val rev = sink.revision
        if (rev == publishedRevision && !hasUnpublishedInline()) return emptyList()
        return publish(rev)
    }

    /**
     * 忽略 revision 重交一次（首帧 / 尺寸变化 / 屏幕被别的路径清过之后用）。
     * 内容没变时 [ConsoleScreen.submitSnapshot] 本身幂等，返回空表。
     */
    fun force(): List<DirtyRect> {
        // 与 [pump] 同一条纪律：版本**先读、后记**，做活成功才记水位。先记再做活的话，
        // `updateIslands` 中途抛一次就把这一版浮岛当成已发布，之后屏上永久停在旧内容。
        // 读在取内容之前：反过来的话，两读之间生产侧前进一格就会记下一个比内容更新的
        // 版本号，那一格更新再也交不上来。
        publishBackground()
        val islandsVersion = sink.htmlIslandsVersion()
        screen.updateIslands(sink.htmlIslands())
        publishedIslandsVersion = islandsVersion
        return publish(sink.revision)
    }

    /**
     * 把 sink 的活值底色（SETBGCOLOR / RESETBGCOLOR，spec: `EmueraConsole.cs:2843-2846` /
     * FunctionIdentifier.cs:1505-1517）发布进 [screen]。
     *
     * 原版这个值是控制台级字段 `bgColor`（EmueraConsole.cs:200），每帧 `graph.Clear(bgColor)`
     * 清**整张**画布（`:1816`）——不随行快照、也不等新行打印才生效。null（未显式设置）回落
     * `Config.BackColor` 缺省黑（ConfigData.cs:69）。幂等：值没变就一次 Int 比较返回。
     */
    private fun publishBackground() {
        val bg = sinkBackgroundArgb()
        if (bg != screen.backgroundArgb) screen.updateBackground(bg)
    }

    /** sink 的活值底色 → 不透明 ARGB；null = 未显式设置，回落装配注入的 [LineBufferConsoleSink.defaultBackColor]（CONFIG-WIRE U5：Config.BackColor 条目，替代工厂常量）。 */
    private fun sinkBackgroundArgb(): Int =
        (0xFF shl 24) or ((sink.backgroundColor ?: sink.defaultBackColor) and 0xFFFFFF)

    /**
     * 有没有已到达但还没发布进 [screen] 的内容（`sink.revision` 前进、内联段到达版本前进、
     * 或整屏底色与屏上当前值不一致）。
     *
     * UI 重绘循环的「要不要 invalidate」判据就该用它而不是真的 [pump] 一次：pump 是
     * **发布动作**（发布后 [publishedRevision] 立即前移，脏区信息随之丢失），循环侧
     * 只需要布尔答案。2026-10-22 真机收口：循环每帧 pump 会把「发布」与「询问」混在一起。
     *
     * 三条判据与 [pump] 的早退条件**逐条同源**（同一套水位、同一次比较），否则会出现
     * 「循环说没变化、pump 却交了一帧」或反向的静默丢帧。
     */
    fun hasUnpublishedContent(): Boolean {
        return sink.revision != publishedRevision || hasUnpublishedInline() ||
            sinkBackgroundArgb() != screen.backgroundArgb
    }

    /**
     * 有没有**已到达但还没发布**的内联段：`GraphicsLayer.inlineRevision()` 与本次已记的
     * 发布水位比一次（口径与纪律见 [publishedInlineRevision]）。
     *
     * 不再问 `GraphicsLayer.hasPendingInline()`：那是队列状态，排空即假 —— 而排空发生在
     * [publish] 里，是否「真交出去了」只有水位知道。
     */
    private fun hasUnpublishedInline(): Boolean {
        val layer = graphics ?: return false
        return layer.inlineRevision() != publishedInlineRevision
    }

    private fun publish(rev: Long): List<DirtyRect> {
        // 内联段到达版本**先读**：`drainInline()` 会把队列排空，排空之后再读就永远读到
        // 「已排空那一版」，两次操作之间到达的段会被当成已发布、永久漏掉。先读的代价是
        // 「读完之后、排空之前到达的段既进了这一帧又被记成未发布」—— 下一帧多交一次
        // 幂等快照（`submitSnapshot` 内容没变返回空表），只多一次比较，不会重画。
        val layer = graphics
        val inlineRev = layer?.inlineRevision()
        // 快照只取一次：锚点表按**同一批** DisplayLine 实例寻址（见 attachInline），
        // 与适配、展平共用这份列表才不会在两次读之间把行对象换掉。
        // 取帧走 frameSnapshot（一屏一帧）：冻结段批量发布的帧拿发布时刻抓取的行表，
        // 轮询周期之后再来取也不会读到让出边界之后循环体的中间态（料理菜单搜索环
        // 「无尽上下跳」的落点）；直通/首帧情形与旧 sink.snapshot() 逐字相同。
        val snapshot = sink.frameSnapshot()
        // 适配的按钮切段与 core 折行**同一把尺**：`LineBufferConsoleSink.fontWidth` 是
        // 装配期注入的那只（真机 = `CanvasConsoleView.fontDisplayWidth`），这里不再开第二个参数
        // —— 结构上不可能拿到与核心折行不同的实例，`SETFONT` 段的按钮列位不会与折行分家。
        var lines = adaptCache.adapt(snapshot, fontWidth = sink.fontWidth)
        val flat = flattener
        if (flat != null) lines = flattenHtml(lines, flat)
        if (layer != null) {
            val incoming = layer.drainInline()
            // 没锚点也没新段（绝大多数帧）不进锚点表，省掉一次 IdentityHashMap 分配。
            if (inlineAnchors.isNotEmpty() || incoming.isNotEmpty()) {
                lines = attachInline(snapshot, lines, incoming)
            }
        }
        // 立绘主路径的后台预取：扫描放在 [attachInline] 之后（把语句侧 `PRINT_IMG` 的段也算上）、
        // 竖带合并之前（合并会把连续图片行折成一张，之后 run 里就没有逐带的名字了）。
        if (prefetch != null) prefetchImages(lines)
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
        // 两道水位都只在**整帧提交成功之后**记：展平 / 锚点 / 提交中途抛异常时水位不动，
        // 下一帧自然重试（与 [pump] 里浮岛水位的纪律同一条）。先记再做活的话，一次失败就会
        // 让这一版内容被当成「已发布」，屏上永久停在旧内容。
        publishedInlineRevision = inlineRev
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
     * 把当前快照里的图片段资源名交给 [prefetch]（立绘首帧卡顿的对策）。
     *
     * 本函数在 UI 线程跑（[publish] 的调用者），**只做字符串查表与入队，不解码**：
     * 真正的解码在回调实现那边（生产侧是 `SpriteBitmapSource` 的单线程后台执行器）。
     *
     * 去重表 [prefetchSeen] 是必须的：`<img>` 段每次 [publish] 都重新展平（它们活在 sink 的
     * 行文本里），没有这张表就等于每帧往预取队列里灌同一批名字。每个资源只请求一次；
     * 表超过 [PREFETCH_SEEN_LIMIT] 条就整表清空（长日志滚动会不断带来新名字）——
     * 清空只让老名字多排一次队，而那边「已在缓存」的判据会让这次排队直接返回。
     *
     * 取图失败/资源名查不到不影响本函数：预取是 best-effort，绘制侧没赶上就照旧同步解码。
     */
    private fun prefetchImages(lines: List<RenderLine>) {
        val cb = prefetch ?: return
        // 只看**可视窗口附近**，不扫整份日志：日志最多 900 行，历史图一旦被预取进来，就会
        // 把在屏图挤出 16 MiB 的 fileCache（LruCache 按字节淘汰），绘制侧反而多解一次 ——
        // 预取变成负优化。窗口外的图滚到时走同步解码（会卡一下，但不空白）。
        val from = (lines.size - (screen.heightLines + PREFETCH_WINDOW_EXTRA)).coerceAtLeast(0)
        for (i in from until lines.size) {
            for (run in lines[i].runs) {
                val resource = (run.graphic as? InlineGraphic.Image)?.resource ?: continue
                if (prefetchSeen.size > PREFETCH_SEEN_LIMIT) prefetchSeen.clear()
                if (!prefetchSeen.add(resource)) continue
                cb(resource)
            }
        }
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
                    back = null,
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
        const val DEFAULT_INLINE_FONT = "ＭＳ ゴシック"

        /** 空屏合成行在锚点目标表里的哨兵下标（真实行下标恒非负）。 */
        const val SYNTHETIC_LINE = -1

        /** 预取去重表的条目上限；超了整表清空（见 [prefetchImages]）。 */
        const val PREFETCH_SEEN_LIMIT = 4096

        /**
         * 可视窗口外再多预取几行（滚动余量）。窗口外的历史图不预取 —— 它们会把在屏图
         * 挤出 16 MiB 的 fileCache，反而让绘制侧多解一次（见 [prefetchImages]）。
         */
        const val PREFETCH_WINDOW_EXTRA = 4
    }
}
