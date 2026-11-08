package com.eratw.port.render.view

import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.displaylist.InlineGraphic
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主图形层状态 —— 语句层的六条图形语句在渲染侧的**唯一落点**（M10-GFX）。
 *
 * ## 它凭什么存在
 *
 * `PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE` 是**内联**段：原版把它们 append 进打印缓冲，
 * 与文本节点排在同一条显示行里（spec: `EmueraConsole.cs:3192-3200` 的 `PrintImg` /
 * `PrintShape` → `printBuffer.Append(...)`）。但语句层只把它们交给 [com.eratw.port.core.host.GraphicsSink]，
 * 文字流走的是另一条 `ConsoleSink` —— 两条流在 core 里没有汇合点，`LineBufferConsoleSink`
 * 也没有图形入口（那是别的 lane 的文件）。所以本类充当那个汇合点：
 * 图形 sink 把段投进 [inline]，`adapter/ConsoleFeed` 在每次向屏幕提交快照时把它们排空，
 * 并**记账到最后一条显示行的实例上**（M11-A L4 / GFX-04）。
 *
 * 记账之后段就跟着那条行活到它被摘除为止 —— 原版正是这个形态：内联段是打印缓冲的节点，
 * 只有 `deleteLine` / MaxLog 淘汰 / `ClearDisplay` 会摘它，滚动与后续打印都不动它
 * （spec: `EmueraConsole.cs:3007-3031`、`:2988-2996`、`:2801-2813`）。`ConsoleFeed` 用行实例
 * 身份做锚，`PRINTN` 并入换新行实例时把段迁到新行（同 `:2956-2966` 的
 * `ChangeStr(prev.Buttons + line.Buttons)`）；改前那段只在提交那一刻贴一次、下一次更新就消失。
 * TODO(verify)：未 flush 的待决文本与内联段的先后（`PRINTN` 之后、换行之前打印的段，原版属于
 * **即将成型的那条行**，本侧只能贴在当时的末行）仍未取证，
 * 卡点：语料里 `PRINT_IMG` / `PRINT_RECT` 的内联形态只有 20 来处且都独立成行
 * （`ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:418` 那种 gauge），
 * 需要真机截图判据才能决定要不要给 `LineBufferConsoleSink` 开图形入口。
 *
 * `SETBGIMAGE` / `REMOVEBGIMAGE` / `CLEARBGIMAGE` 不是内联段，是**整屏背景**：
 * 原版把它们烘进一张 `bakedBackground`，在 `Clear(bgColor)` 之后、文本之前整幅画上
 * （spec: `EmueraConsole.cs:1816-1820`），列表按 depth **降序**排（`:904` 的比较器），
 * 每条按「等比适配 + 水平居中 + 顶对齐」画（`:947-955`）。本类的 [background] 就是那张
 * 列表的形态，具体画在绘制侧（`:render-canvas`）。
 *
 * ## 线程
 *
 * 生产者是 VM 线程（图形 sink），消费者是渲染线程（`ConsoleFeed` / `CanvasConsoleView`）。
 * 内联段用 [ConcurrentLinkedQueue]（无锁单链，两端各一个线程正好）；背景表整体换代
 * —— [background] 永远是**不可变快照**，写侧构造新列表后一次性替换字段，读侧拿到的
 * 快照不会看到中间态。[revision] 是这套状态的单调水位，绘制侧用它判断「要不要重绘」。
 *
 * 本类没有任何静态可变状态（AGENTS.md 铁律 4）：每装配一份 [com.eratw.port.core.proc.GameSession]
 * 就 new 一个，挂在宿主侧而不是 `store.aux`（它不属于脚本可见状态）。
 *
 * ## 背景层的准入判定（2026-10-21 审计补）
 *
 * 原版 `AddBackgroundImage` 外面裹着一道条件（spec: `EmueraConsole.cs:899`）：
 * `if (AppContents.GetSprite(name) is SpriteF spr)` —— 名字查不到、或者查到的不是
 * `SpriteF`（整幅基图；`SpriteG` 是子矩形、`SpriteAnime` 是动画，都不算）时**整条语句静默不成立**，
 * 那一层永远不进 `backgroundList`：既不会被 `BakeBackground`（:905）烘出来，
 * 之后资源加载上了也不会补画。移植侧原来无条件收下，于是
 * 「`SETBGIMAGE` 打在资源就绪之前」在安卓上会**迟一步冒出来**，屏上与 PC 不一致。
 * 准入判据因此收成构造参数 [backgroundAdmissible]（默认恒真 = 旧行为，
 * 不给判据的宿主/单测不受影响）：`GameHost` 那侧应传
 * 「`AppContents` 查得到且是整幅基图」的谓词（`SpriteBitmapSource` 那份表就是数据源）。
 *
 * 同一条语句的另一处差别按原版保留：`RemoveBackground` 在没找到时
 * `RemoveAt(-1)` 会抛（spec: `EmueraConsole.cs:917`），本层不抛（见该方法 KDoc）。
 *
 * @param backgroundAdmissible 背景资源准入判定（`EmueraConsole.cs:899` 的那道 `is SpriteF`）
 */
class GraphicsLayer(
    private val backgroundAdmissible: (String) -> Boolean = ALWAYS_ADMISSIBLE,
) {

    /** 一条背景图层摆位（`ConsoleBackground` + 它的 `zdepth` 键，spec: `EmueraConsole.cs:897-907`）。 */
    data class BgPlacement(
        val resource: String,
        val depth: Int,
        /** 不透明度 0..255（原版 `opacity` 是 `num/255f`，spec: `FunctionIdentifier.cs:1539`）。 */
        val opacity: Int,
    )

    /**
     * SETIMAGELAYERL 一条图层的**渲染侧快照**（WIRE-3 / C-IMAGE-LAYER@1）。
     *
     * 字段与 `GraphicsSink.setImageLayer` 同名同序；由 [GraphicsSinkBridge] 在 VM 线程构造。
     * [lineNo] 是**显示行**索引（原版 `GetLineNo`，非 `LINECOUNT`）；[opacity] 原样 0..255。
     * 屏幕坐标换算见 [ImageLayerGeometry]（公式 `specs/isa.yaml:2574`）。
     */
    data class ImageLayerSnapshot(
        val spriteName: String,
        val depth: Long,
        val lineNo: Int,
        val xpos: Int,
        val ypos: Int,
        val width: Int,
        val height: Int,
        val opacity: Int,
        val followScroll: Boolean,
    )

    /**
     * TEXT_BGC_ON 的**文字衬底快照**（WIRE-4 / C-TEXT-BG@1 消费面）。
     *
     * [rgb] = `0xRRGGBB`；[opacityPercent] = 0..100 百分比（alpha 折算 = `opacity × 255 / 100`，
     * `specs/isa.yaml:2456`）。清除态（`TEXT_BGC_OFF` / `rgb < 0`）由 [textBackground] 的 null 表达。
     */
    data class TextBgSnapshot(val rgb: Int, val opacityPercent: Int)

    private val inline = ConcurrentLinkedQueue<InlineGraphic>()

    /** 单调水位：内联段入队、背景换代都会推进它，绘制侧据此判断需不需要重画。 */
    val revision: AtomicLong = AtomicLong(0L)

    /**
     * 内联段的**到达版本**（单调递增，只增不减）：每次 [enqueue] 推进一格，[drainInline] 与
     * [reset] 都不动它（排空不是到达，清队列也不是）。
     *
     * 与 [revision] 的分工：[revision] 是**整层**的水位（内联段 / 背景 / 图层 / 衬底 / CBG 共用），
     * 绘制侧拿它判「要不要重画」；本值只数**内联段到达**，给消费侧判「有没有已到达但还没发布
     * 进屏的内联段」。后者原来拿 [hasPendingInline] 这个**队列状态**当代理，而队列由发布方
     * 自己排空 —— 状态代理与发布水位在「排空了但没交出去」「被 reset 清空」「换了消费侧」
     * 这些形态上会分叉；版本比较不会。
     *
     * 纪律（`ConsoleFeed.publish` 一侧）：读版本在 [drainInline] **之前**，记水位在整帧提交
     * **成功之后**。反过来（先排空再读）会让两次操作之间到达的段被算进「已发布」，那一批就
     * 永远交不上去。
     *
     * 只由生产线程（VM 线程）推进；读侧（渲染线程）只读。
     */
    private val inlineRev = AtomicLong(0L)

    /** 内联段的到达版本（口径与纪律见 [inlineRev] 的 KDoc）。 */
    fun inlineRevision(): Long = inlineRev.get()

    @Volatile
    private var bg: List<BgPlacement> = emptyList()

    /** 当前背景层（**已按 depth 降序**排好，绘制侧直接顺序画即可）。 */
    val background: List<BgPlacement> get() = bg

    /**
     * SETIMAGELAYERL 图层表：写侧（VM 线程）维护的按 depth 索引表，同 depth 覆盖。
     *
     * 保留口径 = `LinkedHashMap` 的插入序（同 depth 覆盖**保留原插入位置**）。读侧只读
     * [imageLayers] 的不可变快照，不碰本表。
     */
    private val imageLayerIndex = LinkedHashMap<Long, ImageLayerSnapshot>()

    /** 当前图层快照（**不可变**；写侧构造新列表后整体替换，读侧看不到中间态）。 */
    @Volatile
    private var imgLayers: List<ImageLayerSnapshot> = emptyList()

    /**
     * 当前图层列表（按 depth 索引口径 = 写侧 `LinkedHashMap` 的插入序）。
     *
     * 生产者是 VM 线程，消费者是渲染线程；与 [background] 同一无锁不可变快照语义。
     */
    val imageLayers: List<ImageLayerSnapshot> get() = imgLayers

    /** 当前文字衬底（null = 无衬底）。`setTextBgColor` 与 `TEXT_BGC_OFF` 的落点。 */
    @Volatile
    private var textBg: TextBgSnapshot? = null

    /** 当前文字衬底快照；null = 清除态。 */
    val textBackground: TextBgSnapshot? get() = textBg

    /**
     * CBG 图层快照（`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE` / `CBGCLEAR*` 的落点）。
     *
     * 字段与 `com.eratw.port.core.host.CbgLayerSnapshot` 同源（core 侧就是渲染侧的契约 DTO，
     * 本层不再复制一份，避免两处字段名漂移）；列表**已按 depth 降序**，写侧在 VM 线程
     * 整体换代，读侧拿到的快照看不到中间态（与 [background] / [imageLayers] 同一纪律）。
     * 空表 = 没有 CBG 图层。
     */
    @Volatile
    private var cbg: List<CbgLayerSnapshot> = emptyList()

    /** 当前 CBG 图层快照（不可变，按 depth 降序）。 */
    val cbgLayers: List<CbgLayerSnapshot> get() = cbg

    /**
     * `GraphicsSink.setCbgLayers` 的落点（VM 线程）：整体换代并推进 [revision]。
     *
     * 不做排序、不做拷贝：core 的 `CbgPool.snapshot` 每次给一份新建的只读列表
     * （已按 depth 降序，`EmueraConsole.cs:645-646` 的 `Add + Sort` 语义）。
     */
    fun setCbgLayers(layers: List<CbgLayerSnapshot>) {
        cbg = layers
        revision.incrementAndGet()
    }

    /**
     * **队列状态**查询：此刻队列里还有没有未排空的段。
     *
     * 它回答的是「队列空不空」，**不是**「有没有待发布的内容」—— 发布方自己排空队列，
     * 所以这个布尔在排空之后就变假，哪怕那一批内容因为提交失败根本没上屏；[reset] 清队列
     * 也会让它变假。发布判据的唯一权威是 [inlineRevision] 与消费侧记下的发布水位
     * （`ConsoleFeed.publishedInlineRevision`，见 [com.eratw.port.render.adapter.ConsoleFeed.pump]）。
     * 本方法保留给测试、预览与诊断路径。
     */
    fun hasPendingInline(): Boolean = !inline.isEmpty()

    /**
     * 图形语句入队一段（VM 线程）。
     *
     * 顺序写死：先入队、再推进 [inlineRev]、最后推进 [revision]。反过来的话消费侧可能先
     * 看到新版本、排空时却还没有那一段，水位一记就把这一段永久漏掉（见 [inlineRev] 的 KDoc）。
     */
    fun enqueue(graphic: InlineGraphic) {
        inline.add(graphic)
        inlineRev.incrementAndGet()
        revision.incrementAndGet()
    }

    /**
     * 排空内联队列（渲染线程，`ConsoleFeed` 提交快照的那一步调）。
     * 返回的列表按入队顺序，空队列返回空表且不分配。
     *
     * 不动 [inlineRevision]：排空是消费动作，不是到达（水位只由 [enqueue] 推进）。
     * 调用方要在**排空之前**读版本，成功上屏之后再记水位。
     */
    fun drainInline(): List<InlineGraphic> {
        if (inline.isEmpty()) return emptyList()
        val out = ArrayList<InlineGraphic>()
        while (true) {
            val g = inline.poll() ?: break
            out.add(g)
        }
        return out
    }

    /**
     * `SETBGIMAGE`：追加一层并按 depth 降序重排（spec: `EmueraConsole.cs:899-906`）。
     *
     * 原版只在 `AppContents.GetSprite(name) is SpriteF` 时才追加（`:899`），查不到或
     * 类型不对就**静默什么都不做**（连 `BakeBackground` 都不调，:905 在 `if` 里）。
     * 这条判定由 [backgroundAdmissible] 承担；不给判据（默认恒真）时本层收下每一层，
     * 未命中的层在绘制侧同样画不出来，但与 PC 的差别是**资源后来就绪时会补画**，
     * 而 PC 永远不补（类文档「背景层的准入判定」）。接线归宿主侧一行。
     */
    fun addBackground(placement: BgPlacement) {
        if (!backgroundAdmissible(placement.resource)) {
            return
        }
        val next = ArrayList(bg)
        next.add(placement)
        // 比较器 = `(v1.Key < v2.Key) ? 1 : -1`（EmueraConsole.cs:904）：depth 大的在前，
        // 相等时原版保持既有相对序（List.Sort 在 .NET 里对相等元素不保证稳定，
        // 这里用稳定排序，同 depth 按入列先后 —— 与「后加入的盖在先加入的上面」相反，
        // TODO(verify)：同 depth 多层的遮挡次序没有 PC 截图可比）。
        next.sortByDescending { it.depth }
        bg = next
        revision.incrementAndGet()
    }

    /**
     * `REMOVEBGIMAGE`：删掉**第一个**名字命中的层（spec: `EmueraConsole.cs:915-919` ——
     * `FindIndex` + `RemoveAt`，未命中时 `RemoveAt(-1)` 会抛异常，原版没夹；
     * 这里不抛：语句层的返回码与告警已归 `core/fn`，宿主侧再崩一次没有判据）。
     */
    fun removeBackground(resource: String) {
        val index = bg.indexOfFirst { it.resource == resource }
        if (index < 0) return
        val next = ArrayList(bg)
        next.removeAt(index)
        bg = next
        revision.incrementAndGet()
    }

    /** `CLEARBGIMAGE`（spec: `EmueraConsole.cs:909-913`）。 */
    fun clearBackground() {
        bg = emptyList()
        revision.incrementAndGet()
    }

    /**
     * `SETIMAGELAYERL`：登记/覆盖一条行锚定图层（VM 线程）。
     *
     * 同 depth 覆盖（`LinkedHashMap` 键），写入构造新快照并推进 [revision] —— 与
     * [background] 的换代同一无锁语义。`height <= 0` 的回落与 `lineNo` 的显示行口径
     * 已由 core 算好（见 `GraphicsSink.setImageLayer` 的 KDoc），本层原样收下。
     */
    fun setImageLayer(snapshot: ImageLayerSnapshot) {
        imageLayerIndex[snapshot.depth] = snapshot
        imgLayers = ArrayList(imageLayerIndex.values)
        revision.incrementAndGet()
    }

    /**
     * `TEXT_BGC_ON` / `TEXT_BGC_OFF`：设置/清除文字衬底（VM 线程）。
     *
     * `rgb < 0` ⇒ 清除（置 null，`TEXT_BGC_OFF` 的 `(-1, 0)` 哨兵，core 无需另设入口）；
     * 否则 [rgb] = `0xRRGGBB`、[opacityPercent] = 0..100。写入推进 [revision]。
     */
    fun setTextBackground(rgb: Int, opacityPercent: Int) {
        textBg = if (rgb < 0) null else TextBgSnapshot(rgb and 0xFFFFFF, opacityPercent)
        revision.incrementAndGet()
    }

    /**
     * 整层作废（会话重启 / 读档后重建；清背景也清未落的内联段、图层与文字衬底）。
     *
     * 队列里还没被排空的段随会话一起丢弃（既有语义），[inlineRevision] 因此**不推进** ——
     * 丢弃不是到达。若丢弃的那一刻队列里还有未发布的到达（消费侧水位落后一格），水位会
     * 停在原地：下一次 `ConsoleFeed.pump` 因此多交一帧幂等快照
     * （`ConsoleScreen.submitSnapshot` 内容没变返回空脏区），不会漏也不会重画。
     */
    fun reset() {
        inline.clear()
        bg = emptyList()
        imageLayerIndex.clear()
        imgLayers = emptyList()
        textBg = null
        cbg = emptyList()
        revision.incrementAndGet()
    }

    private companion object {
        /** 不给判据时的准入判定：一律收下（= 本层改动前的行为）。 */
        val ALWAYS_ADMISSIBLE: (String) -> Boolean = { true }
    }
}
