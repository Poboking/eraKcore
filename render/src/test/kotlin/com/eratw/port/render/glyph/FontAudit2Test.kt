package com.eratw.port.render.glyph

import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.render.Grid
import com.eratw.port.render.GlyphKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 审计二波 L03（字体/字形/度量）的钉子 —— 每条断言都对准一处真实缺陷的前后边界。
 *
 * 期望值一律**手算**：夹具的设计单元（`SidecarFixtures.kt`，fontTools 实测自语料
 * `font/Saitamaar.ttf`）＋ §3 换算式 `design * size / unitsPerEm` ＋ [CellMetrics] 的量化式
 * （spec: `StringMeasure.cs:37,65`）。没有一条从实现回读。
 *
 * | 用例 | 缺陷 | 证据 |
 * |---|---|---|
 * | 低于下限的字号不另立一桶 | 桶字号 ≠ 真正量/画的字号（5 与 8 两桶同度量，`sizePx` 报 5） | `Config.cs:431-434` |
 * | 方框兜底按抬过的字号报 | 同一桶两跳报两种"生效字号" | `Config.cs:431-434` + `GlyphEntry` 的 `sizePx` 承诺 |
 * | 代理对按一个码位进桶 | 逐码元量出两个代理项桶，绘制侧（按码位造键）永不请求 | `DisplayList.kt:624-636`、`ConsoleStyledString.cs:60-62` |
 * | 串级测量先展开 TAB | 整串测量不展开 TAB ⇒ 每个 TAB 少 7 个空格宽，且落进"缺行"退化 | `StringMeasure.cs:57-62` |
 * | 缺行退化原因指向缺行 | 兜底文案把"个别码位缺行"说成"整表没有" | §3 缺行语义 + `FunctionMethodCreator.cs:6585` |
 * | 尾随空白计入串级宽度 | 串级测量保留尾随空格（不许按 GDI+ 默认裁掉） | `StringMeasure.cs:13` |
 * | 随包 sidecar 能被解析器读通 | 成品写的是**无前导零**的码点（`20 400 0`），解析器必须收 | 契约 M4-C §3 + `fonts/saitamaar-advance.txt` |
 */
class FontAudit2Test {

    private val font = CellMetrics.DEFAULT_FONT_NAME

    private fun key(codePoint: Int, fontName: String = font, styleMask: Int = 0, size: Int = 0) =
        GlyphKey(fontName, styleMask, codePoint, size)

    private fun fixtureMetrics(alias: String? = font): TextMetrics =
        TextMetrics.fromSidecarText(SIDECAR_FIXTURE_PROPORTIONAL, alias = alias)

    // ===== 字号归一到生效字号（Config.cs:431-434）=====

    @Test
    fun `低于字号下限的字号不另立一桶且生效字号一致`() {
        // 原版在**配置装载**时就把 FontSize < 8 抬到 8（spec: Config.cs:431-434
        // `if (FontSize < 8) { FontSize = 8; }`），FontFactory 之后拿到的永远是这个生效值
        // （spec: FontFactory.cs:18-20 的字典键与 `new Font(text, Config.FontSize, …)`）。
        // 旧写法只归一 `<= 0`，于是 size=5 与 size=8 是两个桶、量出同一套 8px 度量，
        // 而条目的 sizePx 分别报 5 与 8 —— 桶字号不是"生效字号"。
        val raster = StaticGlyphRaster()
        val cache = GlyphCache(16, raster)
        val at5 = cache.get(key('A'.code, size = 5))
        val at8 = cache.get(key('A'.code, size = 8))
        assertSame(at5, at8, "size 5 与 size 8 必须是同一个桶（下界抬到 8 之后同桶）")
        assertEquals(1, cache.size)
        // 生效字号 = 8：字号 8 的半角格 = 8/2*1.04 = 4.16 → advance = trunc(4.16) = 4；
        // 行高取配置默认 19（Config.cs:436-439 只在行高 < 字号时抬行高，8 < 19 不动）。
        assertEquals(8, at5.sizePx, "sizePx 必须是真正拿去量的那个字号")
        assertEquals(4, at5.advancePixels)
        assertEquals(1, at5.advanceCells)   // ((4-1)/4.16 + 0.95) = 1.671 → 1
        assertEquals(8, at5.ascentPixels)
        assertEquals(11, at5.descentPixels) // 19 - 8
        assertEquals(19, at5.bitmapHeight)
        // 光栅化器收到的也必须是抬过的字号（否则真机会按 5px 建平台字体，与条目里的 8px 度量脱节）
        assertEquals(listOf("$font|0|${'A'.code}|8"), raster.calls)
        // 归一规则本身：<=0 → 默认字号；1..7 → 8；其余原样
        assertEquals(18, cache.normalizeSize(0))
        assertEquals(18, cache.normalizeSize(-9))
        assertEquals(8, cache.normalizeSize(1))
        assertEquals(8, cache.normalizeSize(7))
        assertEquals(30, cache.normalizeSize(30))
        // 换算器同桶同实例：size 5 与 8 取到同一个 CellMetrics（浮点逐字节稳定）
        assertSame(cache.cellMetricsFor(5), cache.cellMetricsFor(8))
        // 与默认字号仍然分桶（18 是另一个桶）
        assertNotSame(at5, cache.get(key('A'.code, size = 18)))
    }

    @Test
    fun `方框兜底按抬过的字号报同一个生效字号`() {
        // notdef 跳用 converter.fontPixelSize 当 sizePx、光栅跳用桶值；下界没抬的时候
        // 同一个桶会出现 5 与 8 两个"生效字号"（旧行为）。抬之后两跳必须一致。
        val raster = StaticGlyphRaster(fonts = listOf(FontCoverage(font, codePoints = setOf('A'.code))))
        val cache = GlyphCache(16, raster)
        val boxAt5 = cache.get(key('あ'.code, size = 5))
        val boxAt8 = cache.get(key('あ'.code, size = 8))
        assertEquals(GlyphSource.NOTDEF, boxAt5.servedBy)
        assertSame(boxAt5, boxAt8)
        assertEquals(8, boxAt5.sizePx)
        // 方框按全角 2 格：cellsToPixels(2) = trunc(2*4.16) = 8；行高 19（不是字号）
        assertEquals(2, boxAt5.advanceCells)
        assertEquals(8, boxAt5.advancePixels)
        assertEquals(8, boxAt5.bitmapWidth)
        assertEquals(19, boxAt5.bitmapHeight)
        assertEquals(19, boxAt5.ascentPixels + boxAt5.descentPixels)
    }

    // ===== 代理对：一个码位一个字形（DisplayList 的键粒度）=====

    @Test
    fun `代理对按一个码位进桶且格数与布局一致`() {
        // 绘制侧的键按**码位**造（`DisplayList.glyphKeys` 用 Character.toCodePoint 合成一对代理，
        // spec: DisplayList.kt:624-636）；原版一对代理在 GDI+ 眼里也是一个字形
        // （spec: ConsoleStyledString.cs:60-62 的 Substring 只做段切分，测量整串交出去）。
        // 旧写法逐 UTF-16 码元 → 造两个代理项桶，绘制侧永不请求，且"顺手把字形焐热"这句是假的。
        val astral = 0x1F600
        val text = String(Character.toChars(astral))
        val cache = GlyphCache(16, StaticGlyphRaster())
        // 格数沿用已登记的 4 格模型（CellMetrics.cellsOf：高/低代理项各 2 格），与布局同值
        assertEquals(4, Grid.cells(text))
        assertEquals(4, cache.measureCells(text, font, 0, 18))
        // 像素：cellsToPixels(4) = trunc(4*9.36) = 37
        assertEquals(37, cache.measurePixels(text, font, 0, 18))
        // 只造一个桶，且就是绘制侧会请求的那个码位；两个代理项半不得留下
        assertEquals(1, cache.size)
        assertTrue(cache.containsCached(key(astral, size = 18)))
        assertFalse(cache.containsCached(key(0xD83D, size = 18)))
        assertFalse(cache.containsCached(key(0xDE00, size = 18)))
        val entry = cache.get(key(astral, size = 18))
        assertEquals(4, entry.advanceCells)
        assertEquals(37, entry.advancePixels)
        assertEquals(37 * 19, entry.bitmap.size)
        // 未成对的单个代理项仍按 2 格（CellWidth 的"其余一律全角"），码位迭代不改变它
        assertEquals(2, cache.measureCells("\uD83D", font, 0, 18))
    }

    // ===== TAB 展开口径（StringMeasure.cs:57-62）=====

    @Test
    fun `串级测量先展开 TAB 再取表`() {
        // 原版 GRAPHICS 模式测宽前 `s = s.Replace("\t", "        ")`（spec: StringMeasure.cs:57-62）。
        // 夹具：空格 U+0020 = 400 设计单元 → 字号 16 时 400*16/1280 = 5px；A/B = 800 → 10px。
        // 旧行为：§3 表里没有 U+0009 这一行 ⇒ TAB 落进"缺行"退化，只按 1 格（8px）算，
        // 每个 TAB 少 7 个空格宽，折行会把 8 格的位置当 1 格用。
        val metrics = fixtureMetrics()
        assertEquals(10, metrics.measurePixels("A", font, 16))
        assertEquals(40, metrics.measurePixels("\t", font, 16))      // 8 × 5px
        assertEquals(60, metrics.measurePixels("A\tB", font, 16))    // 10 + 40 + 10
        val read = metrics.measure("A\tB", font, 16)
        assertEquals(0, read.degradedCodePoints, "TAB 展成空格之后不该再落进缺行退化")
        assertEquals(MetricProvenance.ADVANCE_TABLE, read.provenance)
        assertEquals(8, read.cells)                                  // ((60-1)/8.32 + 0.95) = 8.04 → 8
        assertEquals(5, metrics.measureCells("\t", font, 16))        // ((40-1)/8.32 + 0.95) = 5.63 → 5
    }

    @Test
    fun `TAB 在逐字与整串两条入口上像素同值格数各异`() {
        // 两条入口的**像素**必须同值（都按同一张表逐码位累加）：逐字 10+8*5+10 = 60。
        // **格数**允许不同（契约 §0.5 已登记）：逐字 2+8*1+2 = 12，整串一次量化 = 8。
        val metrics = fixtureMetrics()
        val cache = GlyphCache(16, StaticGlyphRaster(metrics = metrics))
        assertEquals(60, cache.measurePixels("A\tB", font, 0, 16))
        assertEquals(60, metrics.measurePixels("A\tB", font, 16))
        assertEquals(12, cache.measureCells("A\tB", font, 0, 16))
        assertEquals(8, metrics.measureCells("A\tB", font, 16))
    }

    @Test
    fun `尾随空白计入串级宽度`() {
        // 原版 GRAPHICS 模式用的是 `StringFormatFlags.MeasureTrailingSpaces`（spec: StringMeasure.cs:13），
        // 尾随空格**要**计宽（GDI+ 的 MeasureString 默认会裁掉，那是另一条口径）。
        val metrics = fixtureMetrics()
        assertEquals(10, metrics.measurePixels("A", font, 16))
        assertEquals(15, metrics.measurePixels("A ", font, 16))
        assertEquals(15, metrics.measurePixels(" A", font, 16))
        assertEquals(20, metrics.measurePixels("A  ", font, 16))
        assertEquals(40, metrics.measurePixels("        ", font, 16))
    }

    // ===== 退化原因不许冒充（缺行 ≠ 整表缺失）=====

    @Test
    fun `缺行退化原因指向缺行而不是整表缺失`() {
        // 只有表、没有读取口：chain 非空且 lastReason == null（put 不写原因）。
        // 旧兜底文案在这条路径上说"chain 里没有任何表覆盖所请求字体"——事实相反，表在，
        // 只是 U+3044 没有行（§3：cmap 没有的码点不出行）。
        val catalog = FontSidecarCatalog(defaultFamily = font)
        catalog.put(FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL), alias = font)
        val read = TextMetrics(catalog).measure("Aい", font, 16)
        assertEquals(MetricProvenance.MIXED_TABLE_GRID, read.provenance)
        assertEquals(1, read.degradedCodePoints)
        assertEquals(26, read.pixels)   // A = 10；い 缺行 → 全角 2 格 → trunc(2*8.32) = 16
        assertEquals(3, read.cells)     // ((26-1)/8.32 + 0.95) = 3.955 → 3
        val reason = read.reason!!
        assertTrue(reason.contains("缺行"), reason)
        assertTrue(reason.contains(SIDECAR_FIXTURE_FAMILY), reason)
        assertFalse(reason.contains("没有任何表"), reason)
    }

    // ===== 随包成品与解析器的格式契约 =====

    @Test
    fun `随包 sidecar 能被解析器读通`() {
        // 成品（c-fontsub 的 build_subset.py 产物）码点字段**没有前导零**：首行数据是 `20 400 0`。
        // 契约 M4-C §3 只要求"十六进制大写、无前缀"，解析器必须收这种写法，否则整份表被判畸形、
        // 静默退回等宽网格（`FontAdvanceTable.parse` 对未知构造是抛，而 catalog 会把抛收成
        // "这一跳没有表"）。这里的数值同样出自语料 hmtx 实测，与夹具逐项一致。
        val text = TextMetrics.classpathReader().read("fonts/saitamaar-advance.txt")
        assertTrue(text != null, "随包成品应在 :render 的 classpath 资源 fonts 目录下")
        val table = FontAdvanceTable.parse(text, origin = "fonts/saitamaar-advance.txt")
        assertEquals("Saitamaar", table.family)
        assertEquals(1280, table.unitsPerEm)
        assertEquals(16, table.referenceSizePx)
        assertEquals(400, table.advanceDesign(' '.code))
        assertEquals(800, table.advanceDesign('A'.code))
        assertEquals(1200, table.advanceDesign(0x3042))
        assertEquals(1280, table.advanceDesign(0x4E2D))
        assertEquals(80, table.lsbDesign(0x4E2D))
        // 接上成品后串级读数就是真实度量（不是退化）：A = 800*16/1280 = 10px，
        // 一个 TAB = 8 个空格 = 8*5px = 40px（展开口径见上）
        val metrics = TextMetrics.classpath()
        assertEquals(10, metrics.measurePixels("A", "Saitamaar", 16))
        assertEquals(40, metrics.measurePixels("\t", "Saitamaar", 16))
        assertEquals(MetricProvenance.ADVANCE_TABLE, metrics.measure("A\tB", "Saitamaar", 16).provenance)
    }
}
