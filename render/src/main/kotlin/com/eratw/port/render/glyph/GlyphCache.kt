package com.eratw.port.render.glyph

import com.eratw.port.render.Grid
import com.eratw.port.render.GlyphKey

/**
 * 缓存桶：归一化后的 (字体名, 样式位, 字号, 码位)。
 *
 * 与 [GlyphKey] 的差别只有两处：空字体名换成默认字体名、字号 `<= 0` 归一到
 * [GlyphCache.defaultSizePx]；其余一律原样，所以它是私有类型，
 * [GlyphCache.keysOldestFirst] 与 [GlyphCache.evictedKeys] 一律吐回请求键。
 *
 * 字号必须是桶的一部分：原版 `FontFactory` 的字典键就是 `(字体名, 字号, 样式)`
 * （spec: `FontFactory.cs:9,18-25`），同一个码位换字号就是另一个字形（度量、位图都不同）。
 */
private data class BucketKey(val fontName: String, val styleMask: Int, val sizePx: Int, val codePoint: Int)

/**
 * 字形缓存（ADR-001 重设计区的交付物本体：「字形缓存 + 脏矩形」里的前者）。
 *
 * 原版每帧用 GDI+ 直接 `DrawString` 整段文本，不做字形级缓存
 * （spec: `ConsoleStyledString.cs:119,129,133,138`），移动端功耗不可接受，
 * 所以这里把「一个 (字体, 样式, 字号, 码位) 只测量/光栅化一次」变成结构约束。
 * 原版的同类做法只有一层：`FontFactory` 用字典按 `(字体名, 字号, 样式)` 复用 Font 对象
 * （spec: `FontFactory.cs:9,18-25`），本类把它从 Font 粒度下推到 glyph 粒度。
 *
 * ## 分桶口径
 *
 * 键 = 字体名（序号口径，空名先换默认字体）+ 样式位 + **字号** + 码位，四者任一不同必不串桶。
 * 字号分桶是 GLYPH-KEY-SIZE 这一路的正题：`com.eratw.port.render.GlyphKey.size` 引入后，
 * **一个缓存同时服务多个字号**，字号变更不需要重建缓存；`size <= 0` 是"调用方没给字号"的
 * 兼容取值（`GlyphKey` 的默认值），按 [defaultSizePx] 归一后入桶，所以「不写字号」与
 * 「写 [defaultSizePx]」是同桶同实例 —— 与空字体名和默认字体名同桶是同一个道理
 * （spec: `FontFactory.cs:13-17`）。低于 [CellMetrics.MIN_FONT_PIXEL_SIZE] 的字号在入桶前
 * 同样被抬起（[normalizeSize]，spec: `Config.cs:431-434`），所以桶里的字号恒等于
 * 真正拿去量/画的字号。
 * 样式位分桶是硬要求：原版 `ConsoleStyledString` 在构造时就把 `FontStyle`/`Fontname` 固定进段
 * （spec: `ConsoleStyledString.cs:26-43`），基准里脚本能产出的样式掩码序列是
 * `1, 3, 4, 8, 0`（`fixtures/golden/baseline/pc-golden-root.txt:274-278`，
 * 由 `GOLDEN_02_PRINT.ERB:216-225` 的 FONTBOLD/FONTITALIC/FONTSTYLE/FONTREGULAR 序列打出，
 * 其中 FONTBOLD+FONTITALIC 是**累加**的所以出现 3，而 `FONTSTYLE n` 是整体替换）。
 * 字体名的大小写敏感性沿用 `FontFactory.cs:40` / CHKFONT（`FunctionMethodCreator.cs:3493,3502`）
 * 的序号比较；与 `StringStyle.cs:59,73` 的忽略大小写相等判定是官方自身的不一致，本波不抹平。
 *
 * ## 度量与字号的关系
 *
 * 前进量像素由 [raster] 给（真实度量走 [TextMetrics] → §3 的 advance 表，按该桶的字号换算），
 * 像素 → 半角格由该字号自己的 [CellMetrics] 换算（[cellMetricsFor]），
 * 所以同一个码位在 18 与 30 两个桶里的 `advancePixels` 与 `advanceCells` 都不同，
 * 但同一个桶内两次取用必返同一实例。
 *
 * ## LRU 淘汰为什么是确定性的
 *
 * 容器是 `accessOrder = true` 的 `LinkedHashMap`：它的迭代序由**访问历史**串成的链表决定，
 * 与哈希分布无关，所以「谁最久未被取用」在任何 JVM、任何键的 hashCode 组合下都唯一确定。
 * 淘汰只在 [get] 里显式做（弹出链表头并记入 [evictedKeys]），因此同一调用序列必然得到
 * 同一淘汰序列；本类不读时间、不用随机、不做并行。
 *
 * ## 缺字回退链（不得抛）
 *
 * 请求字体 → 默认字体 → 方框。三跳都不许抛：光栅化器异常按「这一跳没拿到字形」处理，
 * 照抄 `FontFactory.cs:52-54` 的 `catch` 后 `return null`。
 * 与原版的差异（如实记录，不静默）：原版当 **Font 对象本身构造失败** 时把整段标 `Error`，
 * 宽度归 0 且完全不绘制（spec: `ConsoleStyledString.cs:26-30,77-81,99-102`）；
 * 只有「Font 可用但码位缺字」才由 GDI+ 的 .notdef 字形画方框（那几个绘制调用前没有任何
 * 码位检查）。本波把两种情形统一收成第三跳的方框，方框宽度按 [CellMetrics.cellsOf] 的
 * Shift-JIS 半角格模型给。
 * TODO(verify)：.notdef 的真实前进宽度是字体自带的，无解密行号可依据，真机截图验收时复核。
 *
 * ## 线程与单位
 *
 * 非线程安全（ADR-003 的单生产/单消费由上层保证，本波不加锁不起线程）。
 * 宽度一律**半角格**；像素只出现在 [RasterizedGlyph] 与位图尺寸里，
 * 像素到格必须经 [CellMetrics]（真机折行的唯一来源），且必须用**该桶字号自己的**那一个
 * （[cellMetricsFor]），不许拿 [cellMetrics] 这个默认字号的换算器去换算别的字号。
 *
 * @property maxEntries 常驻条目上限；负数夹到 0（沿用原版对配置值先夹后用的口径，
 *   spec: `Config.cs:431-434`、`Config.cs:451-454`）。0 表示**不缓存**（直通：每次都新建实例）。
 * @property raster 字形光栅化器；本波默认 [StaticGlyphRaster]（确定性实现，度量走 §3 表或退化），
 *   真机由 `:render-canvas` 注入 Android 实现。
 * @property cellMetrics 默认字号（[defaultSizePx]）的像素 ↔ 半角格换算器；必须与 [raster] 的度量同源，
 *   否则 [GlyphEntry.advanceCells] 与位图宽会脱节。
 * @property defaultSizePx 请求侧没给字号（`GlyphKey.size <= 0`）时的归一目标；
 *   默认取 [cellMetrics] 的生效字号（配置默认 18，spec: `ConfigData.cs:66`）。
 *   入桶前还会过一次 [CellMetrics.MIN_FONT_PIXEL_SIZE] 的下限（[normalizeSize]）。
 */
class GlyphCache(
    requestedMaxEntries: Int,
    val raster: GlyphRaster = StaticGlyphRaster(),
    val cellMetrics: CellMetrics = CellMetrics.DEFAULT,
    val defaultSizePx: Int = cellMetrics.fontPixelSize,
) {

    val maxEntries: Int = if (requestedMaxEntries < 0) 0 else requestedMaxEntries

    private val cached = LinkedHashMap<BucketKey, GlyphEntry>(16, 0.75f, true)
    private val evictedEntries: MutableList<GlyphKey> = ArrayList()
    private val converters = HashMap<Int, CellMetrics>()

    init {
        // 默认字号的换算器就是传进来的那一个（实例同一，测试可以 assertSame 判"没有另起一份"）。
        converters[cellMetrics.fontPixelSize] = cellMetrics
    }

    /**
     * 该字号的像素 ↔ 半角格换算器；同字号复用同一实例（浮点 [CellMetrics.cellPixelWidth]
     * 因此逐字节稳定），`<= 0` 先按 [defaultSizePx] 归一、`< [CellMetrics.MIN_FONT_PIXEL_SIZE]`
     * 抬到下限（与 [normalizeSize] 同式）。
     *
     * 只按键查、从不读迭代序，所以缓存的换算结果与字号出现顺序无关（确定性）。
     */
    fun cellMetricsFor(sizePx: Int): CellMetrics {
        val normalized = normalizeSize(sizePx)
        return converters.getOrPut(normalized) { CellMetrics(normalized) }
    }

    /**
     * 字号归一：`<= 0` = 调用方没给字号（`GlyphKey.size` 的兼容默认值）→ [defaultSizePx]；
     * 其余**小于 [CellMetrics.MIN_FONT_PIXEL_SIZE] 的抬到下限**（spec: `Config.cs:431-434`
     * `if (FontSize < 8) FontSize = 8;`）。
     *
     * 下限不是可有可无的美化，它保证「桶里的字号 = 真正拿去量/画的字号」：
     * [CellMetrics] 自己就把 `< 8` 抬到 8（[CellMetrics.fontPixelSize]，同一条 `Config.cs:431-434`），
     * 所以旧写法把 `size = 5` 与 `size = 8` 分进两个桶却量出同一套 8px 度量，而且
     * [GlyphEntry.sizePx] 在光栅路径写桶值 5（[build]）、在方框路径写换算器值 8（[notdef]）——
     * 同一个桶两种"生效字号"，`advancePixels`/`bitmap*` 是 8px 的、`sizePx` 却报 5，
     * 真机拿 `sizePx` 去建平台字体就会与条目里的度量脱节（[GlyphEntry] 的 KDoc 承诺
     * `sizePx` 是"本条字形的生效字号"）。
     */
    fun normalizeSize(sizePx: Int): Int {
        val requested = if (sizePx <= 0) defaultSizePx else sizePx
        return if (requested < CellMetrics.MIN_FONT_PIXEL_SIZE) CellMetrics.MIN_FONT_PIXEL_SIZE else requested
    }

    var hitCount: Long = 0L
        private set

    var missCount: Long = 0L
        private set

    var evictionCount: Long = 0L
        private set

    /** 由默认字体代画的条目数（回退链第二跳命中）。 */
    var defaultFallbackCount: Long = 0L
        private set

    /** 方框兜底的条目数（回退链第三跳）。 */
    var notdefCount: Long = 0L
        private set

    /** 当前常驻条目数。 */
    val size: Int
        get() = cached.size

    /** 总请求数 = 命中 + 未命中。 */
    val requestCount: Long
        get() = hitCount + missCount

    /** 命中率；一次请求都没有时给 0.0（不做 0/0）。 */
    val hitRate: Double
        get() = if (requestCount == 0L) 0.0 else hitCount.toDouble() / requestCount.toDouble()

    /**
     * 取字形（缓存的唯一读入口）。
     *
     * 命中即刷新访问序（LRU 的"最近使用"含读命中）；未命中则走回退链构造一条，
     * 并在 [maxEntries] 大于 0 时按桶挂回链表尾部，超出上限就从头部淘汰。
     * 空字体名与默认字体名同桶、`size <= 0` 与 [defaultSizePx] 同桶，
     * 故各自取到**同一实例**（spec: `FontFactory.cs:13-17,18-25` —— 字号是键的一部分，
     * 不同字号必是不同实例）。
     */
    fun get(key: GlyphKey): GlyphEntry {
        val bucket = bucketOf(key)
        val reused = cached[bucket]
        if (reused != null) {
            hitCount++
            return reused
        }
        missCount++
        val entry = build(key, bucket)
        if (maxEntries > 0) {
            cached[bucket] = entry
            while (cached.size > maxEntries) {
                val eldestBucket = cached.keys.first()
                val evicted = cached.remove(eldestBucket)
                if (evicted != null) {
                    evictedEntries += evicted.key
                }
                evictionCount++
            }
        }
        return entry
    }

    /**
     * 一段文本的半角格总宽 —— 逐字走缓存的取数入口（绘制路径用它，因为顺手把字形焐热了）。
     *
     * 逐**码位**取字形（[Character.charCount] 跳过代理对），先把 TAB 展成
     * 8 个空格（spec: `StringMeasure.cs:59-61` 的 GRAPHICS 模式分支，同 [com.eratw.port.render.Grid.expandTabs]；
     * 模式差异见 [CellMetrics] 的「换算公式取自原版 GRAPHICS 模式」）。
     *
     * 为什么按码位而不是旧写法的逐 UTF-16 码元：
     * - 绘制侧的键就是按码位造的（`displaylist/DisplayList.glyphKeys` 用 `Character.toCodePoint`
     *   合成一对代理，`DisplayList.kt:624-636`），按码元量出来的代理项桶永远没人请求，
     *   等于白占 LRU 槽，且"顺手把字形焐热"这句承诺对增补字符是假的；
     * - 原版一对代理在 GDI+ 眼里是**一个字形**：`ConsoleStyledString.cs:60-62` 的
     *   `Substring` 只用于段切分，整串测量在 `StringMeasure.cs:51-72` 交出去量；
     * - 格数不变：[CellMetrics.cellsOf] 对增补码位给「高/低代理项各 2 格 = 4 格」
     *   （`CellMetrics.kt` 的 KDoc，integration 已定），与 `Grid.cells` 的逐码元累加同值，
     *   所以 emoji 的 4 格推进不因本改动变化。
     *
     * 口径提醒：这里是**逐字**累加各桶的 [GlyphEntry.advanceCells]；
     * [TextMetrics.measureCells] 是**整串一次**量化。两条都合法但可以在格边界上差 1 格，
     * 取数时按用途选一条并写清（折行用整串那条，位图/图集布局用逐字这条）。
     *
     * TODO(verify)：§3 允许给增补码位出行，但**当前**随包成品（`fonts/saitamaar-advance.txt`
     * 的 5269 行）一行都没有，所以增补字符走 `CellMetrics.cellsOf` 的 4 格与布局一致。
     * 若将来出了行，这一跳的 `advancePixels` 就按表走，`pixelsToCells` 可能只给 2 格，
     * 而布局按 `Grid.cells` 的逐码元累加仍留 4 格 —— 与 `grid/AstralWidthTest` 已登记的
     * 「4 格 vs `CellWidth.of` 的 2 格」是同一处口径分叉，收敛要让折行侧也改走像素度量
     * （组长裁决）；本类不在零宽之外另做钳位（口径见 `GlyphCacheTest` 的「零宽字形不钳位」）。
     */
    fun measureCells(text: String, fontName: String, styleMask: Int, sizePx: Int): Int {
        val expanded = Grid.expandTabs(text)
        var total = 0
        var i = 0
        while (i < expanded.length) {
            val codePoint = expanded.codePointAt(i)
            i += Character.charCount(codePoint)
            total += get(GlyphKey(fontName, styleMask, codePoint, sizePx)).advanceCells
        }
        return total
    }

    /**
     * 一段文本的像素总宽（逐字累加 [GlyphEntry.advancePixels]，码位粒度与 TAB 展开口径同
     * [measureCells]）。
     *
     * 与 [TextMetrics.measurePixels]（整串一次换算一次截断，贴原版 `MeasureString` 的
     * spec: `FunctionMethodCreator.cs:6585-6587`）可差若干个 1：这里是"每个字形各自占多少像素"
     * 的合计，位图拼接只能按它来；串级口径归 [TextMetrics]，两条不许互相冒充（契约 §0.5）。
     */
    fun measurePixels(text: String, fontName: String, styleMask: Int, sizePx: Int): Int {
        val expanded = Grid.expandTabs(text)
        var total = 0
        var i = 0
        while (i < expanded.length) {
            val codePoint = expanded.codePointAt(i)
            i += Character.charCount(codePoint)
            total += get(GlyphKey(fontName, styleMask, codePoint, sizePx)).advancePixels
        }
        return total
    }

    /** 该请求键当前是否常驻（不改变访问序，因此不影响 LRU）。 */
    fun containsCached(key: GlyphKey): Boolean = cached.containsKey(bucketOf(key))

    /** 当前常驻条目，**最久未用在前**（迭代序 = 淘汰序）。 */
    fun keysOldestFirst(): List<GlyphKey> = cached.values.map { it.key }

    /** 历史淘汰序列（按发生先后）。 */
    fun evictedKeys(): List<GlyphKey> = evictedEntries.toList()

    /** 清空条目（不动统计，也不动 [evictedKeys] 的历史）。 */
    fun clear() {
        cached.clear()
    }

    /** 桶归一化：空字体名 → 默认字体名（spec: `FontFactory.cs:13-17`）、未给字号 → [defaultSizePx]。 */
    private fun bucketOf(key: GlyphKey): BucketKey {
        val fontName = if (key.fontName.isEmpty()) raster.defaultFontName else key.fontName
        return BucketKey(fontName, key.styleMask, normalizeSize(key.size), key.codePoint)
    }

    /** 回退链第一跳 + 第二跳；两跳都空手则第三跳方框。两跳都带**同一个字号**。 */
    private fun build(key: GlyphKey, bucket: BucketKey): GlyphEntry {
        val requestedFont = bucket.fontName
        val defaultFont = raster.defaultFontName
        val sizePx = bucket.sizePx
        val converter = cellMetricsFor(sizePx)
        val hopCount = if (requestedFont == defaultFont) 1 else 2
        for (hop in 0 until hopCount) {
            val fontName = if (hop == 0) requestedFont else defaultFont
            val glyph = rasterizeSafely(fontName, key.styleMask, key.codePoint, sizePx)
            if (glyph != null) {
                if (hop > 0) {
                    defaultFallbackCount++
                }
                return GlyphEntry(
                    key = key,
                    servedFont = fontName,
                    servedBy = if (hop == 0) GlyphSource.REQUESTED else GlyphSource.DEFAULT,
                    advanceCells = converter.pixelsToCells(glyph.advancePixels),
                    advancePixels = glyph.advancePixels,
                    lsbPixels = glyph.lsbPixels,
                    ascentPixels = glyph.ascentPixels,
                    descentPixels = glyph.descentPixels,
                    bitmapWidth = glyph.bitmapWidth,
                    bitmapHeight = glyph.bitmapHeight,
                    bitmap = glyph.bitmap,
                    sizePx = sizePx,
                )
            }
        }
        notdefCount++
        return notdef(key, converter)
    }

    /** 光栅化并吞掉一切异常：照抄 `FontFactory.cs:52-54`（catch 后当"这一跳没有字形"）。 */
    private fun rasterizeSafely(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? =
        try {
            raster.rasterize(fontName, styleMask, codePoint, sizePx)
        } catch (ignored: Exception) {
            null
        }

    /**
     * 第三跳：方框占位。
     *
     * 宽度仍按半角格模型给，保证缺字不会让折行少进或多进；位图是 1 像素边的空心矩形，
     * 真机侧应由 Android 光栅化器直接出 .notdef 位图（本波只做占位）。
     */
    private fun notdef(key: GlyphKey, converter: CellMetrics): GlyphEntry {
        val cells = converter.cellsOf(key.codePoint)
        val advancePixels = converter.cellsToPixels(cells)
        val width = if (advancePixels > 0) advancePixels else 1
        val height = converter.lineHeight
        val pixels = IntArray(width * height) { index ->
            val row = index / width
            val column = index % width
            val onFrame = row == 0 || row == height - 1 || column == 0 || column == width - 1
            if (onFrame) NOTDEF_FRAME_COLOR else 0
        }
        return GlyphEntry(
            key = key,
            servedFont = raster.defaultFontName,
            servedBy = GlyphSource.NOTDEF,
            advanceCells = cells,
            advancePixels = advancePixels,
            lsbPixels = 0,
            ascentPixels = converter.fontPixelSize,
            descentPixels = height - converter.fontPixelSize,
            bitmapWidth = width,
            bitmapHeight = height,
            bitmap = pixels,
            sizePx = converter.fontPixelSize,
        )
    }

    companion object {
        /**
         * 方框占位的边框色，ARGB `0xAARRGGBB` 的 `0xFF808080`（alpha 满、RGB 中灰）。
         * 写成补码字面量是因为 `0xFF808080` 在 Kotlin 里是 Long，`const val` 不接受转换调用；
         * `-0x7F7F80 == 0xFF808080.toInt()`（纯占位，真机由平台字形覆盖）。
         */
        const val NOTDEF_FRAME_COLOR: Int = -0x7F7F80
    }
}
