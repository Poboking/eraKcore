package com.eratw.port.render.view

/**
 * 行栅格位图缓存 —— **纯逻辑，值类型泛化**（真机放 `Bitmap`，单测放假对象），原版
 * `bitmapCacheArray = new ConsoleButtonString[256]` 在本仓的对应物。
 *
 * ## 原版是什么
 *
 * `ConsoleButtonString.DrawTo`（spec: `ConsoleButtonString.cs:317-366`，字段 `:15`、槽位数组
 * `EmueraConsole.cs:70-76`）在「一行含多于一个绘制段」**且首次绘制**时，新建一张行宽 32bpp
 * 位图，把该行各段 `DrawToBitmap` 烤进去，之后每帧只做一次 `DrawImageUnscaled`；
 * 失效是 256 槽**环形 FIFO**轮替（`:340-353`），行对象被 `MaxLog` 挤出即弃。
 *
 * 两个必须记住的口径：
 *
 * 1. **它是性能项，不是正确性项**。默认关；由脚本函数 `BITMAP_CACHE_ENABLE` 置位
 *    （`FunctionMethodCreator.cs:8674` 写 `bitmapCacheEnabledForNextLine`、`:9305` 注册名，
 *    函数**恒返回 0**），新行各自继承该开关（`EmueraConsole.cs:2946`），脚本自己关
 *    （语料实证：`ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:31` 开、`:145` 关）。
 *    关掉它画面照样逐段重画、像素不变 —— 原版从不靠它保正确。
 * 2. 因此本缓存命中与否**都不允许改变像素**。它只在「这一行反正要重新栅格化」时
 *    省掉逐段重放，把成本换成一次贴图。
 *
 * ## 本仓为什么也要它
 *
 * 真机侧加了保留面（见 [ScreenSurfacePlanner]）之后，稳态帧本来就只重栅格化变化的行，
 * 缓存的收益集中在**整幅帧**：滚动回看、背景层存在、内联图形进出、显式置脏这些
 * 必须重画全部可见行的场合 —— 内容没变的行直接贴回位图，不再逐段重放。
 * 色付き地图正是最坏的一档：每 tick 42 行整屏重印（`qol_MAP.ERB:32/39` 先 CLEARLINE 再原地重印），
 * 原版在这种情况下也扛不住 5k 次 GDI 调用，才需要这个缓存。
 *
 * ## 键与判据
 *
 * 键 = 行内容签名（32 位，调用方按行算，口径同保留面的跳过判定）。命中还要求
 * **几何与段数逐项相等**（行高、内容宽、命令条数），不等即判未命中并替换 ——
 * 缓存绝不允许把「另一种版面下的烤图」贴上去。签名列碰撞的暴露面与保留面的跳过判定
 * 同源（同一把签名已经在决定「这行要不要重画」），不额外新增风险类别。
 *
 * 容量按原版 256 槽，**插入序 FIFO**（淘汰序与写入序一致，可测可复现）；淘汰时回调
 * [onEvict] 让调用方回收位图。
 */
class RowRasterCache<V : Any>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val costOf: (V) -> Long = { 0L },
    private val onEvict: (V) -> Unit = {},
) {

    /** 一条缓存：烤好的值 加 命中判据 加 它占的字节。 */
    class Entry<V : Any>(
        val value: V,
        val heightPx: Int,
        val widthPx: Int,
        val cmdCount: Int,
        val bytes: Long,
    )

    private val map = HashMap<Int, Entry<V>>(capacity * 2)
    private val order = ArrayDeque<Int>(capacity)

    /** 当前累计字节（[maxBytes] 是它的软上限）。 */
    var bytes: Long = 0L; private set

    /** 取证读数。 */
    var hits: Long = 0L; private set
    var misses: Long = 0L; private set
    var evictions: Long = 0L; private set
    var stores: Long = 0L; private set
    /** 因为「一条就超预算」而放不进去的次数 —— 真机若这项高，说明预算该调。 */
    var refusedOversize: Long = 0L; private set

    val size: Int get() = map.size

    /**
     * 查一行。返回非 null ⇒ 可以直接贴图；判据不等（换了行高/内容宽/段数）视为未命中，
     * 顺手把过期条目淘汰掉，避免下次还撞同一个错条目。
     */
    fun find(sig: Int, heightPx: Int, widthPx: Int, cmdCount: Int): Entry<V>? {
        val e = map[sig] ?: run { misses += 1L; return null }
        if (e.heightPx != heightPx || e.widthPx != widthPx || e.cmdCount != cmdCount) {
            // 同签名不同几何：这条已经不可用。移除后按未命中处理，调用方会重新烤并 put。
            remove(sig)
            misses += 1L
            return null
        }
        hits += 1L
        return e
    }

    /** 烤好一行后放入。同签名旧条目先释放（位图尺寸可能不同）。 */
    fun put(sig: Int, value: V, heightPx: Int, widthPx: Int, cmdCount: Int) {
        if (map.containsKey(sig)) remove(sig)
        val cost = costOf(value).coerceAtLeast(0L)
        if (cost > maxBytes) {
            // 单行就超预算 ⇒ 不缓存（照样直接栅格化，只是不省第二次）。
            refusedOversize += 1L
            onEvict(value)
            return
        }
        map[sig] = Entry(value, heightPx, widthPx, cmdCount, cost)
        order.addLast(sig)
        bytes += cost
        stores += 1L
        while (map.size > capacity || bytes > maxBytes) {
            val oldest = order.removeFirst()
            val gone = map.remove(oldest) ?: continue
            bytes -= gone.bytes
            onEvict(gone.value)
            evictions += 1L
        }
    }

    /** 丢一条（几何变化导致条目不可用时由 [find] 调用，也可由外部主动清）。 */
    fun remove(sig: Int): Boolean {
        val e = map.remove(sig) ?: return false
        order.remove(sig)
        bytes -= e.bytes
        onEvict(e.value)
        return true
    }

    /** 全部清空（surface 重建 / 尺寸变化 / 从窗口摘掉）。 */
    fun clear() {
        if (map.isEmpty() && order.isEmpty()) return
        for (e in map.values) onEvict(e.value)
        map.clear()
        order.clear()
        bytes = 0L
    }

    /** 当前是否已缓存这一签名（不含几何判据，测试与取证用）。 */
    operator fun contains(sig: Int): Boolean = map.containsKey(sig)

    companion object {
        /** 与原版 `bitmapCacheArray` 同规模（spec: `EmueraConsole.cs:70-76`）。 */
        const val DEFAULT_CAPACITY = 256

        /**
         * 字节预算。原版按槽位算（256 条 × 行宽位图）在 PC 上不过几百 MB 量级，
         * 手机上不行：一块 2400×44 的 ARGB_8888 行位图就是 422 KB，256 槽能吃掉 105 MB。
         * 所以槽位数照抄原版，**实际由字节预算先约束**（FIFO 淘汰）。
         *
         * 下限要盖住**一整屏**：2400 宽 × 43 行高 × 42 行 ≈ 17.3 MB。预算比一屏小的话，
         * 全幅帧会边插边把开头几行挤掉，下一帧再烤回来 —— 缓存退化成恒等空转。
         */
        const val DEFAULT_MAX_BYTES = 24L * 1024L * 1024L
    }
}
