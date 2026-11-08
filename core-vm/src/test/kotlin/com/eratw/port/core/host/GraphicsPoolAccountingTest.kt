package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [GraphicsPool] 诊断读数（[GraphicsPool.stats]）与 [GraphicsBuffer.pixelBytes] 的回归。
 *
 * 钉的是 OOM 归因用的三个读数口径（本波只做可观测：不改像素存储、不加淘汰）：
 * - `entries` = 登记条目数，**含** [GraphicsPool.get] 惰性建出的未创建空条目
 *   （`AppContents.GetGraphics`，AppContents.cs:28-37）；
 * - `created` = `isCreated` 为真的条目数（`width > 0 && height > 0`）；
 * - `pixelBytes` = 各条目已驻留像素的 `pixels.size * 4L` 之和。
 *
 * 读数生命周期：`get` 建空条目 → `createFromArgb` 落像素 → `dispose` 释放 → `clear` 归零；
 * `dispose` 只清缓冲内容，**不删**登记条目（原版 GDispose 不动 gList）。
 */
class GraphicsPoolAccountingTest {

    @Test
    fun `get 新 id 只登记未创建条目`() {
        val pool = GraphicsPool()
        val buffer = pool.get(7)
        val stats = pool.stats()
        assertEquals(1, stats.entries, "get 必须惰性登记一个条目")
        assertEquals(0, stats.created, "新条目未创建（isCreated=false）")
        assertEquals(0L, stats.pixelBytes, "未创建条目没有像素")
        assertEquals(0L, buffer.pixelBytes)
        assertFalse(buffer.isCreated)
        // 同一 id 再取不新增条目、不重复计数
        assertSame(buffer, pool.get(7))
        assertEquals(1, pool.stats().entries)
    }

    @Test
    fun `createFromArgb 后 pixelBytes 按宽高四字节增长`() {
        val pool = GraphicsPool()
        val first = pool.get(1)
        assertTrue(first.createFromArgb(4, 3, IntArray(12)))
        assertEquals(48L, first.pixelBytes, "pixelBytes = 像素数 * 4")
        val second = pool.get(2)
        assertTrue(second.createFromArgb(2, 2, IntArray(4)))
        // 第三个 id 只 get 未创建：计入 entries、不计入 created
        pool.get(3)
        val stats = pool.stats()
        assertEquals(3, stats.entries)
        assertEquals(2, stats.created)
        assertEquals(48L + 16L, stats.pixelBytes, "多条目按各自像素数累加")
    }

    @Test
    fun `dispose 后 created 与 pixelBytes 回落但条目仍在`() {
        val pool = GraphicsPool()
        val buffer = pool.get(5)
        assertTrue(buffer.createFromArgb(3, 2, IntArray(6)))
        assertEquals(24L, pool.stats().pixelBytes)
        buffer.dispose()
        val stats = pool.stats()
        assertEquals(1, stats.entries, "dispose 不删登记条目（原版只 GDispose，不动 gList）")
        assertEquals(0, stats.created)
        assertEquals(0L, stats.pixelBytes)
        assertEquals(0L, buffer.pixelBytes)
        assertFalse(buffer.isCreated)
    }

    @Test
    fun `clear 后全部读数归零`() {
        val pool = GraphicsPool()
        assertTrue(pool.get(1).createFromArgb(2, 2, IntArray(4)))
        assertTrue(pool.get(2).createFromArgb(4, 4, IntArray(16)))
        assertEquals(16L + 64L, pool.stats().pixelBytes)
        pool.clear()
        val stats = pool.stats()
        assertEquals(0, stats.entries)
        assertEquals(0, stats.created)
        assertEquals(0L, stats.pixelBytes)
        assertNull(pool.peek(1), "clear 后按编号不再有条目（再 get 会重新惰性建）")
    }

    /**
     * 延迟缓冲（CSV 资源表）的读数口径：诊断不该为了报数触发解码 —— 未落地像素前
     * 按 0 字节计（此时尺寸已登记，`isCreated` 为真）；首像素访问落地后才计入。
     */
    @Test
    fun `延迟缓冲未落地像素前按零字节计`() {
        val pool = GraphicsPool()
        val deferred = pool.get(9)
        assertTrue(deferred.createDeferred(2, 2) { IntArray(4) })
        var stats = pool.stats()
        assertEquals(1, stats.created, "延迟缓冲尺寸已登记，isCreated 为真")
        assertEquals(0L, stats.pixelBytes, "读数不得为报数把图解码出来")
        assertEquals(4, deferred.pixels.size, "首像素访问：延迟源落地")
        assertEquals(16L, deferred.pixelBytes)
        stats = pool.stats()
        assertEquals(16L, stats.pixelBytes)
    }

    /**
     * 2026-10-30 稳定性审计 #2：`evictDeferred` 必须走 [GraphicsBuffer.storePixels] 记账。
     *
     * 旧写法直接 `pixelData = IntArray(0)` 绕过记账：trim 之后 `residentBytes()` 停在旧值
     * （虚高），之后条目被移出池时 `removeFromPoolAccounting` 再扣一次就成**负数** ——
     * 而 GameHost 内存采样把 -1 当「无数据」哨兵，负数与哨兵混在同一列（`gfx=`），没法排障。
     */
    @Test
    fun `evictDeferred 的驻留读数随像素换代回收重解清池全程不归负`() {
        val pool = GraphicsPool()
        val sheet = pool.get(60)
        assertTrue(sheet.createDeferredFromFile(2, 2, "resources/audit2.webp") { IntArray(4) { 0x55000000 } })
        assertEquals(0L, pool.residentBytes(), "未落地按 0 计")
        sheet.pixels
        assertEquals(16L, pool.residentBytes(), "解码落地：4 像素 × 4 字节")
        assertEquals(4L, sheet.evictDeferred())
        assertEquals(0L, pool.residentBytes(), "回收后池级读数必须同步归零（修复前这里停在 16）")
        assertEquals(0L, pool.stats().pixelBytes)
        sheet.pixels
        assertEquals(16L, pool.residentBytes(), "重解再计入")
        // 回收态（像素已空、accountedBytes 已归零）时条目被移出池：不得扣成负数
        sheet.evictDeferred()
        pool.clear()
        assertEquals(0L, pool.residentBytes(), "清池后必须恰好归零（修复前这里是 -16）")
    }
}
