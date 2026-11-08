package com.eratw.port.render

import com.eratw.port.render.glyph.GlyphCache
import com.eratw.port.render.glyph.StaticGlyphRaster
import com.eratw.port.render.html.MiniHtml
import com.eratw.port.render.scroll.LogBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 稳定性波 L10（render 文本栈：字形 / 网格 / 滚动 / 字体 / HTML）的定向回归。
 *
 * 每条用例都对应一处「无界增长 / 预分配爆量 / 算法退化」的具体触发条件，
 * 断言的是**可证伪的结构读数**（条数、精确计数、耗时上界），不是实现回读：
 *
 *  1. [GlyphCache] 的淘汰历史与 [StaticGlyphRaster] 的调用流水都是诊断用日志，
 *     旧实现按调用逐条追加、整场会话无界增长；用例把请求数推过上限，
 *     断言「日志条数封顶」而「计数仍精确」。
 *  2. [LogBuffer] 旧实现按 `MAXLOG` 一次性 `arrayOfNulls`，配置写大就**在构造期**
 *     要一整张表（`MAXLOG` 无上限，`resolveMaxLog` 只抬下限）—— 用例直接拿
 *     `Int.MAX_VALUE` 构造，旧实现抛 `OutOfMemoryError`，新实现按需增长。
 *  3. [MiniHtml] 旧实现在**每处理一个标签**时为找下一个裸换行从 `pos` 重扫到串尾，
 *     「多标签 + 尾部换行」是 O(n²)；用例以 20 万标签 + 尾部换行计时，
 *     旧实现远超上界，新实现毫秒级，并同场复核换行/注释的切行语义未变。
 *
 * 语义保持：三条都不改任何脚本可见行为 —— 淘汰/调用计数、行号、缓冲内容、
 * HTML 解析结果逐值不变，只把「内部记账/内部存储」从无界或全量预分配改成有界。
 */
class StabilityL10RenderTextStackTest {

    private fun row(marker: String): DisplayRow = DisplayRow(
        runs = listOf(TextRun(marker, 0xC0C0C0, 0, 0, "黑体", RunKind.TEXT)),
        cells = marker.length,
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun marker(row: DisplayRow): String = row.runs.first().text

    @Test
    fun `字形缓存淘汰历史封顶且淘汰计数精确`() {
        // 触发条件：缓存上限 1，连续请求 1200 个不同码位 —— 每次请求都淘汰上一桶。
        // 旧后果：evictedEntries 逐条追加且无消费方（生产代码只用 evictionCount），
        // 整场会话的淘汰历史永久驻留（每条约一个 GlyphKey + 字体名字符串）。
        val cache = GlyphCache(1, StaticGlyphRaster())
        val base = 0x4E00
        val total = 1200
        for (i in 0 until total) {
            cache.get(GlyphKey("黑体", 0, base + i))
        }
        assertEquals(total - 1L, cache.evictionCount, "淘汰计数必须逐次精确，不随历史封顶丢失")
        val history = cache.evictedKeys()
        assertTrue(history.size <= 256, "淘汰历史条数必须有上限（实得 ${history.size} 条）")
        assertEquals(256, history.size, "封顶后保留最近 256 条")
        // 淘汰序 = base+0 .. base+1198；最近 256 条即 base+943 .. base+1198
        assertEquals(base + total - 1 - 256, history.first().codePoint)
        assertEquals(base + total - 2, history.last().codePoint)
        assertEquals(listOf(GlyphKey("黑体", 0, base + total - 1)), cache.keysOldestFirst())
    }

    @Test
    fun `光栅器调用流水封顶且调用计数精确`() {
        // 触发条件：直接对默认光栅器光栅化 3000 个码位（GlyphCache 上限 0 或反复淘汰时
        // 生产路径同样会走到）。旧后果：callLog 每调一次追加一条字符串，永不清理，
        // 而它只是给单测看回退链的诊断日志。
        val raster = StaticGlyphRaster()
        val total = 3000
        val font = raster.defaultFontName
        for (i in 0 until total) {
            val codePoint = 0x21 + (i % 0x5D)
            assertNotNull(raster.rasterize(font, 0, codePoint, 18), "默认字体覆盖全码位，应出字形")
        }
        assertEquals(total, raster.callCount, "调用计数必须精确（含被截掉的历史）")
        assertTrue(raster.calls.size <= 1024, "调用流水条数必须有上限（实得 ${raster.calls.size} 条）")
        assertEquals(1024, raster.calls.size, "封顶后保留最近 1024 条")
        assertEquals("$font|0|${0x21 + (total - 1) % 0x5D}|18", raster.calls.last())
    }

    @Test
    fun `日志缓冲超大配置容量不在构造期预分配整表`() {
        // 触发条件：MAXLOG 配置值写成接近 Int 上限（resolveMaxLog 只抬 500 下限、不设上限），
        // 旧实现构造期就 arrayOfNulls(maxLog) ⇒ 启动即 OutOfMemoryError，
        // 而原版 List 是随行数增长、同样的配置在 PC 上不会当场炸。
        val buffer = LogBuffer(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, buffer.maxLog, "生效上限仍等于配置值，不夹")
        assertEquals(0, buffer.size)
        val rows = (0 until 300).map { row("L$it") }
        rows.forEach(buffer::append)
        assertEquals(300, buffer.size)
        assertEquals(300L, buffer.totalLines)
        assertEquals(0L, buffer.firstIndex)
        assertEquals(rows, buffer.rowsAt(0, 300), "扩容后行序与内容逐行不变")
        assertEquals(rows.last(), buffer.last())
        assertEquals(rows[7], buffer[7L])
    }

    @Test
    fun `小容量日志缓冲的淘汰与行号口径不变`() {
        // 与上一条同源的回归护栏：按需增长不得改掉环形淘汰的既有读数。
        val buffer = LogBuffer(4)
        (0 until 6).forEach { buffer.append(row("L$it")) }
        assertEquals(4, buffer.size)
        assertEquals(6L, buffer.totalLines)
        assertEquals(2L, buffer.firstIndex)
        assertEquals(listOf("L2", "L3", "L4", "L5"), buffer.all().map(::marker))
        assertEquals(null, buffer[1L], "已淘汰的行号取不到")
    }

    @Test
    fun `迷你HTML长串多标签的换行扫描不退化`() {
        // 触发条件：一段 20 万个 <b>x</b> 再加一个尾部裸换行（脚本拼出来的长 HTML 就是这个形状）。
        // 旧后果：主循环每处理一个标签都 indexOf('\n', pos) 重扫到串尾 ⇒ O(n²)，
        // 长串在脚本线程上要跑十几秒（真机表现为「打印一下就卡死」）。
        val tagCount = 200_000
        val sb = StringBuilder(tagCount * 8 + 1)
        repeat(tagCount) { sb.append("<b>x</b>") }
        sb.append('\n')
        val html = sb.toString()

        val startedAt = System.nanoTime()
        val doc = MiniHtml().parse(html)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertEquals(1, doc.lines.size, "尾部换行只收一行，不产生空尾行")
        assertEquals(tagCount, doc.lines[0].plainText.length)
        assertEquals(tagCount, doc.lines[0].cells, "'x' 半角 1 格")
        assertTrue(elapsedMs < 5_000L, "20 万标签 + 尾部换行必须线性完成，实测 ${elapsedMs} ms")

        // 换行/注释的切行语义同场复核（增量游标不得吃掉或重复裸换行）
        val two = MiniHtml().parse("<b>a</b>\n<b>b</b>")
        assertEquals(2, two.lines.size)
        assertEquals(listOf("a", "b"), two.lines.map { it.plainText })
        val comment = MiniHtml().parse("<!--\n--><b>z</b>\n")
        assertEquals(1, comment.lines.size, "注释里的裸换行不算断行")
        assertEquals("z", comment.lines[0].plainText)
    }
}
