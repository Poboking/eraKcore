package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RowRasterCache] 的行为闸（纯 JVM，值类型用假对象）。
 *
 * 对齐基准：原版 `ConsoleButtonString.DrawTo` 的行位图（spec: `ConsoleButtonString.cs:317-366`）
 * 与 256 槽轮替（`EmueraConsole.cs:70-76`、`:340-353`）。两处口径必须记住：
 * 原版是**性能项**（默认关，脚本 `BITMAP_CACHE_ENABLE` 显式开，恒返回 0，
 * `FunctionMethodCreator.cs:8674`/`:9305`），命中与否都不许改变像素；本仓把它做成自动，
 * 所以「判据不等即作废」这条比原版更要紧 —— 宁可未命中重烤，不可贴错图。
 */
class RowRasterCacheTest {

    private class Fake

    @Test
    fun `命中要求几何与段数逐项相等`() {
        val c = RowRasterCache<Fake>()
        val v = Fake()
        c.put(sig = 7, value = v, heightPx = 40, widthPx = 2400, cmdCount = 3)
        assertSame(v, c.find(7, 40, 2400, 3)?.value, "同签名同几何 ⇒ 命中")
        assertNull(c.find(7, 44, 2400, 3), "行高变了 ⇒ 那张烤图不可用")
        assertNull(c.find(7, 40, 2392, 3), "内容宽变了 ⇒ 同上")
        assertNull(c.find(7, 40, 2400, 9), "段数变了 ⇒ 同上")
    }

    @Test
    fun `判据不等时条目被淘汰并回调释放`() {
        val released = ArrayList<Fake>()
        val c = RowRasterCache<Fake>(onEvict = { released += it })
        val old = Fake()
        c.put(7, old, 40, 2400, 3)
        assertNull(c.find(7, 40, 2400, 4), "段数不同 ⇒ 未命中")
        assertEquals(listOf(old), released, "不可用条目当场释放，不等 FIFO 淘汰")
        assertFalse(7 in c)
        val fresh = Fake()
        c.put(7, fresh, 40, 2400, 4)
        assertSame(fresh, c.find(7, 40, 2400, 4)?.value)
    }

    @Test
    fun `同签名重复放入先释放旧值`() {
        val released = ArrayList<Fake>()
        val c = RowRasterCache<Fake>(onEvict = { released += it })
        val a = Fake(); val b = Fake()
        c.put(1, a, 40, 2400, 2)
        c.put(1, b, 40, 2400, 2)
        assertEquals(listOf(a), released)
        assertEquals(1, c.size, "同签名只留一条，不能把容量吃成两条")
        assertSame(b, c.find(1, 40, 2400, 2)?.value)
    }

    @Test
    fun `容量256按插入序FIFO淘汰`() {
        val released = ArrayList<Fake>()
        val c = RowRasterCache<Fake>(capacity = 4, onEvict = { released += it })
        val vs = (0 until 6).map { Fake().also { v -> c.put(it, v, 40, 2400, 1) } }
        assertEquals(4, c.size)
        assertEquals(listOf(vs[0], vs[1]), released, "插满后按插入序逐条挤出（原版是环形 FIFO，不是 LRU）")
        assertFalse(0 in c); assertFalse(1 in c)
        assertTrue(2 in c); assertTrue(5 in c)
        assertEquals(2L, c.evictions)
    }

    @Test
    fun `默认容量与原版同规模`() {
        // 原版：bitmapCacheArray = new ConsoleButtonString[256]（EmueraConsole.cs:70-76）
        assertEquals(256, RowRasterCache.DEFAULT_CAPACITY)
    }

    @Test
    fun `clear释放全部且幂等`() {
        val released = ArrayList<Fake>()
        val c = RowRasterCache<Fake>(capacity = 8, onEvict = { released += it })
        val a = Fake(); val b = Fake()
        c.put(1, a, 40, 2400, 1); c.put(2, b, 40, 2400, 1)
        c.clear()
        assertEquals(setOf(a, b), released.toSet())
        assertEquals(0, c.size)
        released.clear()
        c.clear()
        assertTrue(released.isEmpty(), "空缓存再 clear 不许重复释放（位图二次 recycle 会崩）")
    }

    @Test
    fun `命中率读数可取证`() {
        val c = RowRasterCache<Fake>()
        c.put(9, Fake(), 40, 2400, 1)
        c.find(9, 40, 2400, 1); c.find(9, 40, 2400, 1); c.find(10, 40, 2400, 1)
        assertEquals(2L, c.hits)
        assertEquals(1L, c.misses)
        assertEquals(1L, c.stores)
    }

    @Test
    fun `字节预算先于槽位数约束`() {
        val released = ArrayList<Fake>()
        // 每条算 10 字节、预算 25 ⇒ 第 3 条进来就要挤掉最老的（原版按槽位，真机按字节）
        val c = RowRasterCache<Fake>(capacity = 256, maxBytes = 25L, costOf = { 10L }, onEvict = { released += it })
        val a = Fake(); val b = Fake(); val d = Fake()
        c.put(1, a, 40, 2400, 2); c.put(2, b, 40, 2400, 2); c.put(3, d, 40, 2400, 2)
        assertEquals(2, c.size, "25 字节的预算只装得下两条 10 字节")
        assertEquals(20L, c.bytes, "留在预算内的是两条")
        assertEquals(listOf(a), released)
        assertFalse(1 in c); assertTrue(2 in c); assertTrue(3 in c)
        assertEquals(1L, c.evictions)
    }

    @Test
    fun `单行就超预算时拒缓存但立刻释放`() {
        val released = ArrayList<Fake>()
        val c = RowRasterCache<Fake>(maxBytes = 5L, costOf = { 10L }, onEvict = { released += it })
        val v = Fake()
        c.put(1, v, 40, 2400, 2)
        assertEquals(0, c.size)
        assertEquals(1L, c.refusedOversize)
        assertEquals(listOf(v), released, "烤好的位图不许漏在手外")
    }

    @Test
    fun `remove与clear同步字节账`() {
        val c = RowRasterCache<Fake>(maxBytes = 100L, costOf = { 10L })
        c.put(1, Fake(), 40, 2400, 2); c.put(2, Fake(), 40, 2400, 2)
        assertEquals(20L, c.bytes)
        assertTrue(c.remove(1))
        assertEquals(10L, c.bytes)
        c.clear()
        assertEquals(0L, c.bytes)
        assertFalse(c.remove(1), "不存在条目返回 false 且不回调")
    }
}
