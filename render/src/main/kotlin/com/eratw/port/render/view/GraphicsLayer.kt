package com.eratw.port.render.view

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

    private val inline = ConcurrentLinkedQueue<InlineGraphic>()

    /** 单调水位：内联段入队、背景换代都会推进它，绘制侧据此判断需不需要重画。 */
    val revision: AtomicLong = AtomicLong(0L)

    @Volatile
    private var bg: List<BgPlacement> = emptyList()

    /** 当前背景层（**已按 depth 降序**排好，绘制侧直接顺序画即可）。 */
    val background: List<BgPlacement> get() = bg

    /** 有没有待落的内联段（`ConsoleFeed.pump` 用它决定「文字流没动但图来了」也要交）。 */
    fun hasPendingInline(): Boolean = !inline.isEmpty()

    /** 图形语句入队一段（VM 线程）。 */
    fun enqueue(graphic: InlineGraphic) {
        inline.add(graphic)
        revision.incrementAndGet()
    }

    /**
     * 排空内联队列（渲染线程，`ConsoleFeed` 提交快照的那一步调）。
     * 返回的列表按入队顺序，空队列返回空表且不分配。
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

    /** 整层作废（会话重启 / 读档后重建；清背景也清未落的内联段）。 */
    fun reset() {
        inline.clear()
        bg = emptyList()
        revision.incrementAndGet()
    }

    private companion object {
        /** 不给判据时的准入判定：一律收下（= 本层改动前的行为）。 */
        val ALWAYS_ADMISSIBLE: (String) -> Boolean = { true }
    }
}
