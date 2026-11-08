package com.eratw.port.render.font

/**
 * 请求字体名 → 登记族实例的**实例级**缓存（渲染热路径去分配）。
 *
 * ## 为什么要有这一层
 *
 * [BundledFontFamilies.resolve] 每次调用都要两轮归一：先 `normalize(requested)` 建一个新
 * 字符串，未命中再对八个登记族的族名各 `normalize` 一遍。
 * 绘制热路径是**每字形每帧两次**调用（选 Typeface 一次、算基线一次），全屏两千字形 × 60 帧
 * 就是每秒数十万个临时字符串；高频小对象分配会喂出周期性 GC，玩家侧表现为「隔一会儿整屏
 * 抖一下」（同类问题的实测读数见 `CanvasConsoleView.drawRun` 的 KDoc）。
 *
 * 这里把「请求名 → 族」的结果按**请求名原样**记住：命中直接返回登记表里的**同一个实例**，
 * 归一与查表都省掉。键不归一、值由 [BundledFontFamilies.resolve] 唯一决定，所以不存在
 * 「两个不同字体名被错误折成同一份结果」的可能 —— 与登记表本身的语义逐字一致。
 *
 * ## 语义
 *
 *   * null / 纯空白：**不登记**，直返 [BundledFontFamilies.default]（调用方大量走这条）；
 *   * 命中：返回同一实例，调用方可用 `===` 判族；
 *   * 未命中：调 [BundledFontFamilies.resolve] 并登记；表满（达到 [maxEntries]）时整表
 *     `clear` 再登记 —— 不做 LRU 淘汰，字体名的基数是个位数，正常永远碰不到上限。
 *
 * ## 线程
 *
 * **仅 UI 线程使用，无锁**。实例归 `CanvasConsoleView` 持有，只在 onDraw / 命中路径上访问；
 * 不要把它挂到会被多线程共享的单例上（需要时各自 new 一份）。
 *
 * @param maxEntries 表内最多登记多少个**不同请求名**；必须为正。
 * @see BundledFontFamilies.resolve 无缓存的原函数（语义权威）
 */
class ResolvedFontFamilyCache(private val maxEntries: Int = 64) {

    init {
        require(maxEntries > 0) { "maxEntries 必须为正：$maxEntries" }
    }

    /** 请求名（原样）→ 登记族。空白名不入表，命中率由逐段稳定的 `GlyphKey.fontName` 保证。 */
    private val cache = HashMap<String, BundledFontFamily>()

    /**
     * 取请求名对应的登记族。**永不返回 null、永不抛**（同 [BundledFontFamilies.resolve]）。
     *
     * @param requested 配置/字形上带的字体名；null 或纯空白落默认族
     */
    fun resolve(requested: String?): BundledFontFamily {
        if (requested.isNullOrBlank()) return BundledFontFamilies.default
        cache[requested]?.let { return it }
        val entry = BundledFontFamilies.resolve(requested)
        // 简单上限：满了整表作废再登记，避免淘汰逻辑本身成为热路径开销。
        if (cache.size >= maxEntries) cache.clear()
        cache[requested] = entry
        return entry
    }
}
