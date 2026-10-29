package com.eratw.port.render.glyph

import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.render.GlyphKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * m-glyph · [GlyphCache] 的缓存语义闸。
 *
 * 期望值来源只有两处：黄金基准的分节字面（`fixtures/golden/baseline/pc-golden-root.txt`）
 * 与反编译 C# 的行号；LRU 的淘汰序由测试里**手算的访问序**推出，不从实现回读。
 */
class GlyphCacheTest {

    private val font = CellMetrics.DEFAULT_FONT_NAME

    private fun key(codePoint: Int, fontName: String = font, styleMask: Int = 0, size: Int = 0) =
        GlyphKey(fontName, styleMask, codePoint, size)

    @Test
    fun `同键二次取得同一实例且只光栅化一次`() {
        // 原版口径：同一 (字体名, 字号, 样式) 只持有一个 Font 对象（spec: FontFactory.cs:9,18-25）
        val raster = StaticGlyphRaster()
        val cache = GlyphCache(16, raster)
        val first = cache.get(key('A'.code))
        val second = cache.get(key('A'.code))
        assertSame(first, second)
        assertEquals(1, raster.callCount)
        assertEquals(1, cache.size)
        assertEquals(1L, cache.hitCount)
        assertEquals(1L, cache.missCount)
        assertEquals(0.5, cache.hitRate, 1e-12)
        assertEquals(GlyphSource.REQUESTED, first.servedBy)
        assertFalse(first.isFallback)
    }

    @Test
    fun `命中率按访问序列统计`() {
        val cache = GlyphCache(16, StaticGlyphRaster())
        val codes = listOf('A'.code, 'B'.code, 'C'.code)
        repeat(4) { round ->
            codes.forEach { code -> cache.get(key(code)) }
            // 第 1 轮全未命中，第 2..4 轮全命中：9 命中 / 12 请求
            if (round == 3) {
                assertEquals(9L, cache.hitCount)
                assertEquals(3L, cache.missCount)
            }
        }
        assertEquals(12L, cache.requestCount)
        assertEquals(0.75, cache.hitRate, 1e-12)
        assertEquals(3, cache.size)
        // 一个请求都没有时命中率给 0.0（不做 0/0）
        assertEquals(0.0, GlyphCache(0, StaticGlyphRaster()).hitRate, 1e-12)
    }

    @Test
    fun `样式位分桶取基准的 1 3 4 8 0 五个掩码`() {
        // 掩码序列 spec: fixtures/golden/baseline/pc-golden-root.txt:274-278
        //（GOLDEN_02_PRINT.ERB:216-225；FONTBOLD/FONTITALIC 累加出 3，FONTSTYLE 整体替换）
        // 掩码到 FontStyle 的映射 spec: Process.cs:1512-1527（读回 FunctionMethodCreator.cs:3712-3727）
        val cache = GlyphCache(16, StaticGlyphRaster())
        val masks = listOf(1, 3, 4, 8, 0)
        val entries = masks.map { mask -> cache.get(key('漢'.code, styleMask = mask)) }
        assertEquals(5, cache.size)
        assertEquals(5L, cache.missCount)
        assertEquals(0L, cache.hitCount)
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                assertNotSame(entries[i], entries[j], "样式桶串味：下标 $i 与 $j")
                assertFalse(
                    entries[i].bitmap.contentEquals(entries[j].bitmap),
                    "样式桶串味：位图相同 下标 $i 与 $j",
                )
            }
            // 样式不改半角格前进量（假实现按等宽模型；真机由平台度量给）
            assertEquals(2, entries[i].advanceCells)
            assertSame(entries[i], cache.get(key('漢'.code, styleMask = masks[i])))
        }
        assertEquals(5L, cache.hitCount)
    }

    @Test
    fun `空字体名归一到默认字体并与之同桶`() {
        // spec: FontFactory.cs:13-17（空名换成 Config.FontName，同一个字典键）
        val raster = StaticGlyphRaster()
        val cache = GlyphCache(16, raster)
        val viaEmpty = cache.get(key('A'.code, fontName = ""))
        val viaDefault = cache.get(key('A'.code, fontName = font))
        assertSame(viaEmpty, viaDefault)
        assertEquals(1, cache.size)
        // 请求即默认字体，只走一跳（流水尾巴是归一后的字号 = GlyphCache.defaultSizePx = 18）
        assertEquals(listOf("$font|0|${'A'.code}|$DEFAULT_SIZE"), raster.calls)
        assertEquals(0L, cache.defaultFallbackCount)
    }

    @Test
    fun `字体名分桶区分大小写`() {
        // spec: FontFactory.cs:40 与 CHKFONT 的 FunctionMethodCreator.cs:3493,3502 都是序号比较；
        // 官方另一处 StringStyle.cs:59,73 忽略大小写（文本段合并层），本波不抹平该不一致
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, coversAllCodePoints = true),
                FontCoverage("Arial", codePoints = setOf('A'.code)),
                FontCoverage("arial", codePoints = setOf('A'.code)),
            ),
        )
        val cache = GlyphCache(16, raster)
        val upper = cache.get(key('A'.code, fontName = "Arial"))
        val lower = cache.get(key('A'.code, fontName = "arial"))
        assertNotSame(upper, lower)
        assertEquals(2, cache.size)
        assertTrue(cache.containsCached(key('A'.code, fontName = "Arial")))
        assertTrue(upper.servedBy == GlyphSource.REQUESTED && lower.servedBy == GlyphSource.REQUESTED)
        // 同码位同样式不同大小写：内容相同但条目不同（分桶按请求名，不按内容）
        assertTrue(upper.bitmap.contentEquals(lower.bitmap))
        assertEquals(upper.advanceCells, lower.advanceCells)
    }

    @Test
    fun `读命中刷新访问序淘汰最久未用`() {
        val cache = GlyphCache(2, StaticGlyphRaster())
        cache.get(key('A'.code))
        cache.get(key('B'.code))
        cache.get(key('A'.code))
        cache.get(key('C'.code))
        // 手算：插入 A,B 后访问 A -> [B,A]，插入 C 溢出淘汰的是 B 而不是先插入的 A
        assertEquals(listOf(key('B'.code)), cache.evictedKeys())
        assertEquals(1L, cache.evictionCount)
        assertEquals(listOf(key('A'.code), key('C'.code)), cache.keysOldestFirst())
        assertFalse(cache.containsCached(key('B'.code)))
        assertTrue(cache.containsCached(key('A'.code)))
    }

    @Test
    fun `淘汰序只由访问序决定`() {
        // 手算（上限 3，角色下标序列 0,1,2,0,3,4,1,1,2）：
        //   put0 -> [0]；put1 -> [0,1]；put2 -> [0,1,2]；get0 命中 -> [1,2,0]
        //   put3 溢出 -> 淘汰 1 -> [2,0,3]；put4 溢出 -> 淘汰 2 -> [0,3,4]
        //   get1 未命中 put1 -> [3,4,1]（淘汰 0）；get1 命中 -> 不变；put2 -> [4,1,2]（淘汰 3）
        //   淘汰序 = [1,2,0,3]，常驻（旧→新）= [4,1,2]，命中 2 / 未命中 7
        val access = listOf(0, 1, 2, 0, 3, 4, 1, 1, 2)
        val expectedEvicted = listOf(1, 2, 0, 3)
        val expectedResident = listOf(4, 1, 2)
        val halfWidthRoles = listOf('A'.code, 'B'.code, 'C'.code, 'D'.code, 'E'.code)
        // 另一组码位：哈希值与桶布局完全不同（全角/半角混排），但扮演同样的角色
        val mixedRoles = listOf('漢'.code, 'あ'.code, 'Ａ'.code, 'Ⅶ'.code, '堷'.code)
        val runA = runSequence(halfWidthRoles, access)
        val runB = runSequence(mixedRoles, access)
        for (run in listOf(runA, runB)) {
            assertEquals(expectedEvicted, run.evictedRoles)
            assertEquals(expectedResident, run.residentRoles)
            assertEquals(3, run.cache.size)
            assertEquals(4L, run.cache.evictionCount)
            assertEquals(2L, run.cache.hitCount)
            assertEquals(7L, run.cache.missCount)
            assertEquals(9L, run.cache.requestCount)
        }
        // 两组键的哈希布局不同（同角色不同 hashCode），淘汰相对序仍一致
        assertNotEquals(runA.hashCodes, runB.hashCodes)
    }

    @Test
    fun `上限为 1 时换键即淘汰`() {
        val cache = GlyphCache(1, StaticGlyphRaster())
        val firstA = cache.get(key('A'.code))
        assertSame(firstA, cache.get(key('A'.code)))
        cache.get(key('B'.code))
        val secondA = cache.get(key('A'.code))
        assertNotSame(firstA, secondA)
        // 手算：[A] -> 命中 -> put B 淘汰 A -> [B] -> put A 淘汰 B -> [A]
        assertEquals(1, cache.size)
        assertEquals(2L, cache.evictionCount)
        assertEquals(3L, cache.missCount)
        assertEquals(1L, cache.hitCount)
        assertEquals(listOf(key('A'.code), key('B'.code)), cache.evictedKeys())
        assertEquals(listOf(key('A'.code)), cache.keysOldestFirst())
        assertFalse(cache.containsCached(key('B'.code)))
    }

    @Test
    fun `上限为 0 或负数时不缓存直通`() {
        val raster = StaticGlyphRaster()
        val cache = GlyphCache(0, raster)
        val first = cache.get(key('A'.code))
        val second = cache.get(key('A'.code))
        assertNotSame(first, second)
        assertEquals(first.advanceCells, second.advanceCells)
        assertTrue(first.bitmap.contentEquals(second.bitmap))
        assertEquals(0, cache.size)
        assertEquals(0, cache.maxEntries)
        assertEquals(2, raster.callCount)
        assertEquals(0L, cache.hitCount)
        assertEquals(0.0, cache.hitRate, 1e-12)
        // 负数夹到 0（沿用原版"先夹后用"的配置口径，spec: Config.cs:431-434、Config.cs:451-454）
        val negative = GlyphCache(-7, StaticGlyphRaster())
        assertEquals(0, negative.maxEntries)
        negative.get(key('A'.code))
        assertEquals(0, negative.size)
    }

    @Test
    fun `回退链第二跳由默认字体代画`() {
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, coversAllCodePoints = true),
                FontCoverage(FONT_OTHER, codePoints = setOf('A'.code)),
            ),
        )
        val cache = GlyphCache(16, raster)
        val entry = cache.get(key('漢'.code, fontName = FONT_OTHER))
        assertEquals(GlyphSource.DEFAULT, entry.servedBy)
        assertEquals(font, entry.servedFont)
        assertTrue(entry.isFallback)
        assertEquals(2, entry.advanceCells)
        assertEquals(1L, cache.defaultFallbackCount)
        assertEquals(0L, cache.notdefCount)
        // 跳序：请求字体先试、默认字体补上，恰两跳（两跳带同一个字号）
        assertEquals(listOf("$FONT_OTHER|0|${'漢'.code}|$DEFAULT_SIZE", "$font|0|${'漢'.code}|$DEFAULT_SIZE"), raster.calls)
    }

    @Test
    fun `回退链第三跳给方框且不抛并按请求键缓存`() {
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, codePoints = setOf('A'.code)),
                FontCoverage(FONT_OTHER, codePoints = setOf('A'.code)),
            ),
        )
        val cache = GlyphCache(16, raster)
        val request = key('あ'.code, fontName = FONT_OTHER)
        val entry = cache.get(request)
        assertEquals(GlyphSource.NOTDEF, entry.servedBy)
        assertTrue(entry.isNotdef)
        assertSame(request, entry.key)
        assertEquals(1L, cache.notdefCount)
        assertEquals(listOf("$FONT_OTHER|0|${'あ'.code}|$DEFAULT_SIZE", "$font|0|${'あ'.code}|$DEFAULT_SIZE"), raster.calls)
        // 方框仍占全角 2 格：缺字不许让折行少进
        assertEquals(2, entry.advanceCells)
        assertEquals(18, entry.advancePixels)
        assertEquals(18, entry.bitmapWidth)
        assertEquals(19, entry.bitmapHeight)
        assertEquals(GlyphCache.NOTDEF_FRAME_COLOR, entry.bitmap[0])
        assertEquals(0, entry.bitmap[9 * 18 + 9])
        assertEquals(GlyphCache.NOTDEF_FRAME_COLOR, entry.bitmap[entry.bitmap.size - 1])
        // 缺字结果按请求键常驻：第二次取不再光栅化
        assertSame(entry, cache.get(request))
        assertEquals(2, raster.callCount)
        assertTrue(cache.containsCached(request))
    }

    @Test
    fun `光栅化器抛异常不得外传`() {
        // 复刻 FontFactory.cs:52-54：catch 后当这一跳没有字形，回退链继续
        val raster = ThrowingRaster(font)
        val cache = GlyphCache(16, raster)
        val entry = cache.get(key('A'.code, fontName = FONT_OTHER))
        assertEquals(2, raster.calls)
        assertEquals(GlyphSource.NOTDEF, entry.servedBy)
        assertEquals(1, entry.advanceCells)
        assertEquals(1L, cache.notdefCount)
        assertFailsWith<IllegalStateException> { raster.explodeDirectly() }
    }

    @Test
    fun `前进格宽与基准的半角全角口径一致`() {
        // spec: EmueraConsole.cs:3255（Shift-JIS 字节数 半角 1 / 全角 2）
        // 基准实测 pc-golden-root.txt:135 WIDTH_FULL=6 WIDTH_HALF=3（GOLDEN_01_BASIC.ERB:122）
        val cache = GlyphCache(64, StaticGlyphRaster())
        assertEquals(1, cache.get(key('A'.code)).advanceCells)
        assertEquals(9, cache.get(key('A'.code)).advancePixels)
        assertEquals(1, cache.get(key(' '.code)).advanceCells)
        assertEquals(1, cache.get(key('ｱ'.code)).advanceCells)
        assertEquals(2, cache.get(key('あ'.code)).advanceCells)
        assertEquals(18, cache.get(key('あ'.code)).advancePixels)
        assertEquals(3, cache.measureCells("ABC", font, 0, DEFAULT_SIZE))
        assertEquals(6, cache.measureCells("ＡＢＣ", font, 0, DEFAULT_SIZE))
        assertEquals(0, cache.measureCells("", font, 0, DEFAULT_SIZE))
    }

    @Test
    fun `measureCells 先展开 TAB`() {
        // spec: StringMeasure.cs:59-61（GRAPHICS 模式测量前把 TAB 换成 8 个空格，不做制表位对齐）
        val cache = GlyphCache(64, StaticGlyphRaster())
        assertEquals(10, cache.measureCells("a\tb", font, 0, DEFAULT_SIZE))
        assertEquals(16, cache.measureCells("\t\t", font, 0, DEFAULT_SIZE))
    }

    @Test
    fun `格宽一律经像素到半角格的换算入口`() {
        val cache = GlyphCache(64, StaticGlyphRaster())
        val codes = listOf('A'.code, 'あ'.code, 'ｱ'.code, '漢'.code, ' '.code, '½'.code)
        for (code in codes) {
            val entry = cache.get(key(code))
            assertEquals(cache.cellMetrics.pixelsToCells(entry.advancePixels), entry.advanceCells, "码位 $code")
        }
    }

    @Test
    fun `零宽字形不钳位`() {
        // 原版 GDI 量出 0 宽就是 0 宽，缓存不做防御性抬升（ADR-005：不"顺手修好"）
        val cache = GlyphCache(16, ZeroWidthRaster(font))
        val entry = cache.get(key('A'.code))
        assertEquals(0, entry.advanceCells)
        assertEquals(0, entry.advancePixels)
        assertEquals(GlyphSource.REQUESTED, entry.servedBy)
    }

    @Test
    fun `清空后重建得到新实例且统计保留`() {
        val cache = GlyphCache(16, StaticGlyphRaster())
        val first = cache.get(key('A'.code))
        cache.clear()
        assertEquals(0, cache.size)
        assertFalse(cache.containsCached(key('A'.code)))
        val second = cache.get(key('A'.code))
        assertNotSame(first, second)
        assertEquals(2L, cache.missCount)
        assertEquals(0L, cache.hitCount)
        assertEquals(0L, cache.evictionCount)
    }

    @Test
    fun `条目 toString 暴露回退来源便于首差定位`() {
        val entry = GlyphCache(4, StaticGlyphRaster()).get(key('あ'.code))
        assertTrue(entry.toString().contains("servedBy=REQUESTED"), entry.toString())
        assertTrue(entry.toString().contains("advanceCells=2"), entry.toString())
    }

    // ===== GLYPH-KEY-SIZE：字号真正入桶（原版 FontFactory 的键含字号，spec: FontFactory.cs:9,18-25）=====

    @Test
    fun `同码位不同字号必是不同实例不同度量`() {
        // 手算：字号 18 → 每格 9.36px（StringMeasure.cs:37）→ 半角 advance = trunc(9.36) = 9，
        //       格数 = ((9-1)/9.36+0.95) = 1.805 → 1；
        //       字号 30 → 每格 15.6px → advance = trunc(15.6) = 15，格数 = ((15-1)/15.6+0.95) = 1.847 → 1；
        //       行高按 Config.cs:436-439（配置默认 19 与字号取大）→ 字号 30 的行高是 30。
        val raster = StaticGlyphRaster()
        val cache = GlyphCache(16, raster)
        val at18 = cache.get(key('A'.code, size = 18))
        val at30 = cache.get(key('A'.code, size = 30))
        assertNotSame(at18, at30)
        assertEquals(2, cache.size)
        assertEquals(9, at18.advancePixels)
        assertEquals(15, at30.advancePixels)
        assertEquals(1, at18.advanceCells)
        assertEquals(1, at30.advanceCells)
        assertEquals(18, at18.sizePx)
        assertEquals(30, at30.sizePx)
        assertEquals(30, at30.ascentPixels)
        assertEquals(0, at30.descentPixels)
        assertEquals(15, at30.bitmapWidth)
        assertEquals(30, at30.bitmapHeight)
        assertEquals(15 * 30, at30.bitmap.size)
        // 同一桶二次取用仍是同实例，且不产生第二次光栅化
        assertSame(at18, cache.get(key('A'.code, size = 18)))
        assertSame(at30, cache.get(key('A'.code, size = 30)))
        assertEquals(2, raster.callCount)
    }

    @Test
    fun `未给字号与给默认字号是同桶`() {
        // GlyphKey.size 的兼容默认值是 0（"调用方没给"），归一到 GlyphCache.defaultSizePx 才入桶；
        // 这不是"一个缓存只服务一个字号"，而是"没写字号时用哪个字号"。
        val raster = StaticGlyphRaster(cellMetrics = CellMetrics(20))
        val cache = GlyphCache(16, raster, CellMetrics(20))
        val unspecified = cache.get(key('A'.code))
        val explicit = cache.get(key('A'.code, size = 20))
        assertSame(unspecified, explicit)
        assertEquals(1, cache.size)
        assertEquals(20, unspecified.sizePx)
        // 光栅化器收到的必须是**归一后**的字号，否则同一个字形会按 0 字号去量
        assertEquals(listOf("$font|0|${'A'.code}|20"), raster.calls)
        // 换算器也必须是该字号自己的（每格 20/2*1.04 = 10.4 → advance = trunc(10.4) = 10；
        // 格数 = ((10-1)/10.4 + 0.95) = 1.815 → 1）
        assertEquals(10, unspecified.advancePixels)
        assertSame(cache.cellMetrics, cache.cellMetricsFor(20))
        assertEquals(1, unspecified.advanceCells)
    }

    @Test
    fun `缺字回退链在指定字号下仍是两跳且两跳同字号`() {
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, coversAllCodePoints = true),
                FontCoverage(FONT_OTHER, codePoints = setOf('A'.code)),
            ),
        )
        val cache = GlyphCache(16, raster)
        val entry = cache.get(key('漢'.code, fontName = FONT_OTHER, size = 30))
        assertEquals(GlyphSource.DEFAULT, entry.servedBy)
        assertEquals(30, entry.sizePx)
        assertEquals(listOf("$FONT_OTHER|0|${'漢'.code}|30", "$font|0|${'漢'.code}|30"), raster.calls)
        // 字号 30 的全角：每格 15.6px → advance = trunc(2*15.6) = 31
        assertEquals(31, entry.advancePixels)
        assertEquals(2, entry.advanceCells)
    }

    @Test
    fun `方框兜底按该字号的行高与前进量给`() {
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, codePoints = setOf('A'.code)),
                FontCoverage(FONT_OTHER, codePoints = setOf('A'.code)),
            ),
        )
        val cache = GlyphCache(16, raster)
        val entry = cache.get(key('あ'.code, fontName = FONT_OTHER, size = 30))
        assertEquals(GlyphSource.NOTDEF, entry.servedBy)
        assertEquals(30, entry.sizePx)
        assertEquals(2, entry.advanceCells)
        assertEquals(31, entry.advancePixels)
        assertEquals(31, entry.bitmapWidth)
        assertEquals(30, entry.bitmapHeight)
        assertEquals(0, entry.lsbPixels)
        assertEquals(GlyphCache.NOTDEF_FRAME_COLOR, entry.bitmap[0])
        // 内部像素 = 第 15 行第 15 列（行 0..29、列 0..30，距边框都 >= 1）
        assertEquals(0, entry.bitmap[15 * 31 + 15])
        assertEquals(1L, cache.notdefCount)
    }

    @Test
    fun `真实度量进来后前进量不再是等宽网格值`() {
        // 夹具见 SidecarFixtures.kt（数值是 fontTools 实测 Saitamaar 的 hmtx，不是实现回读）：
        // U+0041 advance = 800 设计单元、unitsPerEm = 1280 → 字号 16 时 800*16/1280 = 10.0；
        // 而等宽网格在字号 16 只给 trunc(1*8.32) = 8 —— 两个值不同才证明表真的接进来了。
        val metrics = TextMetrics.fromSidecarText(SIDECAR_FIXTURE_PROPORTIONAL, alias = font)
        val raster = StaticGlyphRaster(defaultFontName = font, metrics = metrics)
        val cache = GlyphCache(16, raster)
        val at16 = cache.get(key('A'.code, size = 16))
        assertEquals(10, at16.advancePixels)
        // 格数：((10-1)/8.32 + 0.95) = 2.031 → 2（真实比例宽度下拉丁字母不再恰好占半格）
        assertEquals(2, at16.advanceCells)
        // 同码位换字号：800*18/1280 = 11.25 → 截断 11（另一桶、另一实例）
        val at18 = cache.get(key('A'.code, size = 18))
        assertEquals(11, at18.advancePixels)
        assertNotSame(at16, at18)
        // lsb 也随字号换算：U+0042 的 lsb = 80 设计单元 → 80*16/1280 = 1.0
        assertEquals(1, cache.get(key('B'.code, size = 16)).lsbPixels)
        // 串级入口跟着真实度量走（逐字口径）：半角 A 现在各占 2 格 → "ABC" 是 6 格而不是 3 格
        assertEquals(2, cache.get(key('C'.code, size = 16)).advanceCells)
        assertEquals(6, cache.measureCells("ABC", font, 0, 16))
        assertEquals(31, cache.measurePixels("ABC", font, 0, 16))
    }

    @Test
    fun `接了表但表里没有这一行就按缺字走回退链`() {
        // §3：源字体 cmap 没有的码点**不出行**，缺行即缺字 —— 不许静默按 0 或按网格糊过去。
        // 这里把同一张 8 行夹具表同时挂到请求族与默认族名下（两跳都有表、两跳都缺同一行），
        // 才能把"缺行"与"这一族压根没接表"分开：后者按退化出图（DEGRADED_GRID），不是缺字。
        val catalog = FontSidecarCatalog(defaultFamily = font, substituteFamily = null)
        val table = FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL)
        catalog.put(table, alias = FONT_OTHER)
        catalog.put(table, alias = font)
        val metrics = TextMetrics(catalog)
        val raster = StaticGlyphRaster(
            defaultFontName = font,
            fonts = listOf(
                FontCoverage(font, coversAllCodePoints = true),
                FontCoverage(FONT_OTHER, coversAllCodePoints = true),
            ),
            metrics = metrics,
        )
        val cache = GlyphCache(16, raster)
        // U+3042（あ）在表里 → 请求族一跳命中：1200*16/1280 = 15.0
        val hit = cache.get(key('あ'.code, fontName = FONT_OTHER, size = 16))
        assertEquals(GlyphSource.REQUESTED, hit.servedBy)
        assertEquals(15, hit.advancePixels)
        assertEquals(listOf("$FONT_OTHER|0|${'あ'.code}|16"), raster.calls)
        // U+3044（い）不在表里（夹具只有 8 行）→ 请求族返回 null → 默认族挂的是同一张表、同样缺行
        // → 方框兜底；缺字仍占满 2 格（trunc(2*8.32) = 16 像素），折行不许因为缺字少走
        val miss = cache.get(key('い'.code, fontName = FONT_OTHER, size = 16))
        assertEquals(GlyphSource.NOTDEF, miss.servedBy)
        assertEquals(2, miss.advanceCells)
        assertEquals(16, miss.advancePixels)
        // 两跳都试过了：请求族一次、默认族一次
        assertEquals(
            listOf("$FONT_OTHER|0|${'い'.code}|16", "$font|0|${'い'.code}|16"),
            raster.calls.subList(raster.calls.size - 2, raster.calls.size),
        )
    }

    @Test
    fun `淘汰仍只看访问序且字号参与键`() {
        val cache = GlyphCache(2, StaticGlyphRaster())
        cache.get(key('A'.code, size = 18))
        cache.get(key('A'.code, size = 30))
        cache.get(key('A'.code, size = 18))
        cache.get(key('B'.code, size = 18))
        // 手算：入 A18,A30 → 命中 A18（序变 [A30,A18]）→ 入 B18 溢出，淘汰最久未用的 A30
        assertEquals(listOf(key('A'.code, size = 30)), cache.evictedKeys())
        assertEquals(1L, cache.evictionCount)
        assertEquals(listOf(key('A'.code, size = 18), key('B'.code, size = 18)), cache.keysOldestFirst())
        assertFalse(cache.containsCached(key('A'.code, size = 30)))
        assertTrue(cache.containsCached(key('A'.code, size = 18)))
    }

    @Test
    fun `成品缺失时退化成网格值且出处可读出`() {
        // classpath 里本波没有 c-fontsub 的成品 → 必须确定性退化，不许抛、也不许宣称真实度量。
        val metrics = TextMetrics.classpath()
        val read = metrics.measure("ABC", font, 16)
        assertEquals(MetricProvenance.DEGRADED_GRID, read.provenance)
        assertEquals(3, read.degradedCodePoints)
        assertNotNull(read.reason, "退化必须给得出原因")
        assertTrue(read.reason!!.isNotEmpty())
        val raster = StaticGlyphRaster(defaultFontName = font, metrics = metrics)
        val cache = GlyphCache(16, raster)
        assertEquals(8, cache.get(key('A'.code, size = 16)).advancePixels)
    }

    private class SequenceRun(
        val cache: GlyphCache,
        val evictedRoles: List<Int>,
        val residentRoles: List<Int>,
        val hashCodes: List<Int>,
    )

    /** 按角色下标把访问序列翻译成码位，再把缓存吐回的键映射回角色，得到可手算的相对序。 */
    private fun runSequence(roles: List<Int>, access: List<Int>): SequenceRun {
        val cache = GlyphCache(3, StaticGlyphRaster())
        val roleByCode = roles.withIndex().associate { (index, code) -> code to index }
        for (role in access) {
            cache.get(key(roles[role]))
        }
        return SequenceRun(
            cache = cache,
            evictedRoles = cache.evictedKeys().map { roleByCode.getValue(it.codePoint) },
            residentRoles = cache.keysOldestFirst().map { roleByCode.getValue(it.codePoint) },
            hashCodes = roles.map { code -> key(code).hashCode() },
        )
    }

    companion object {
        /** 配置里的默认字体名（spec: `ConfigData.cs:65`），用来当"请求字体"。 */
        private const val FONT_OTHER: String = "ＭＳ ゴシック"

        /** `GlyphKey.size = 0`（未给字号）归一后的字号：配置默认 18（spec: `ConfigData.cs:66`）。 */
        private const val DEFAULT_SIZE: Int = 18
    }
}

/** 光栅化器抛异常的场景替身：先计数再抛，另给一个可直接断言"确实会抛"的入口。 */
private class ThrowingRaster(override val defaultFontName: String) : GlyphRaster {

    var calls: Int = 0
        private set

    override fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? {
        calls++
        throw IllegalStateException("模拟 Android 字体栈不可用：$fontName / 样式 $styleMask / 码位 $codePoint / 字号 $sizePx")
    }

    fun explodeDirectly() {
        calls++
        throw IllegalStateException("对照断言：本替身确实会抛")
    }
}

/** 度量恒为 0 宽的光栅化器。 */
private class ZeroWidthRaster(override val defaultFontName: String) : GlyphRaster {

    override fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph =
        RasterizedGlyph(
            advancePixels = 0,
            ascentPixels = CellMetrics.DEFAULT_FONT_PIXEL_SIZE,
            descentPixels = CellMetrics.DEFAULT_LINE_HEIGHT - CellMetrics.DEFAULT_FONT_PIXEL_SIZE,
            bitmapWidth = 0,
            bitmapHeight = CellMetrics.DEFAULT_LINE_HEIGHT,
            bitmap = IntArray(0),
        )
}
