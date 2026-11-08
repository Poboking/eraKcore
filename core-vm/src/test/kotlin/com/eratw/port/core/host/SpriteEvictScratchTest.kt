package com.eratw.port.core.host

import com.eratw.port.core.host.SpritePool.Frame
import com.eratw.port.core.host.SpritePool.Sprite
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [SpriteEvictScratch] 与负号段回收闸的 **oomsrc 波**（2026-11-05）三件事：
 *
 * ① 记账面本体的语义钉：身份判同（`GraphicsBuffer` 未覆写 equals —— `referencedBuffers`
 *   的 KDoc 已写明「等价于引用相等」，去重语义与旧 `HashSet` 逐字相同）、首次登记序、
 *   clear 后判同面归零、扩容（超过 2 的幂装载率触发 rehash）后成员不丢；
 * ② **等价臂**：预算闸/全表闸传面与不传面，freed 条数、退出的缓冲集合、驻留读数逐项
 *   一致（对照 `SpritePoolBudgetTest` 钉的同形场景：共享父图去重 + 动画帧 + protect）；
 * ③ **两臂自证的分配读数**（复现红 = 不传面时闸按池体量现搭容器 —— 真机
 *   `6c08e8bb-0` 里 `HashMap.resize` 524,304 字节在 <1% free 的堆上 OOM 的移植面判据；
 *   修复臂 = 同一块面第二次走完整扫描时稳态零分配）。摘除修复（闸内忽略传入面、每发
 *   现建）即让 ③ 的修复臂变红 —— 破坏性验证按这条做。
 *
 * 分配计数用 HotSpot `com.sun.management.ThreadMXBean` 的线程累计分配（与
 * `GraphicsBufferBlitFromTest` 同款装置；非 HotSpot JVM 按 assumeTrue 跳过）。
 */
class SpriteEvictScratchTest {

    /** 装置尺寸：2×2 像素、16 字节驻留 —— 判据是字节算式，小图与大图形同形。 */
    private val pixelsPerSheet = 4L
    private val bytesPerSheet = pixelsPerSheet * 4L

    private fun madeBuffer(index: Int): GraphicsBuffer {
        // 负号 id + create（不带 deferredSource）⇒ evictDeferred 恒 0：驻留不动，
        // 慢路径每发都走完整遍历 —— 正是「反复现搭容器」的放大形状。
        val buffer = GraphicsBuffer(-(index + 1))
        buffer.create(2, 2)
        return buffer
    }

    /** 直接构造 n 张独立缓冲、每缓冲一个名的资源表（绕开 CSV 装载，只测闸本体）。 */
    private fun buildPlainPool(n: Int): Pair<SpritePool, List<GraphicsBuffer>> {
        val pool = SpritePool()
        val buffers = ArrayList<GraphicsBuffer>(n)
        for (i in 0 until n) {
            val buffer = madeBuffer(i)
            buffers.add(buffer)
            pool.putResource(Sprite("PLAIN$i", 2, 2, buffer))
        }
        return pool to buffers
    }

    /** 共享父图形状：P0 两名片、P1 一名片 + 动画帧两枚（P1、P0）。 */
    private fun buildSharedPool(): Triple<SpritePool, GraphicsBuffer, GraphicsBuffer> {
        val pool = SpritePool()
        val p0 = GraphicsBuffer(-1).apply { createDeferred(2, 2) { IntArray(4) { 7 } } }
        val p1 = GraphicsBuffer(-2).apply { createDeferred(2, 2) { IntArray(4) { 9 } } }
        p0.pixels // 落地（真机由 GDRAWSPRITE 合成读触发）
        p1.pixels
        pool.putResource(Sprite("SHARED_A1", 2, 2, p0))
        pool.putResource(Sprite("SHARED_A2", 2, 2, p0))
        pool.putResource(Sprite("SHARED_B1", 2, 2, p1))
        pool.putResource(
            Sprite(
                "SHARED_ANIME", 2, 2, frames = mutableListOf(
                    Frame(p1, 0, 0, 2, 2, 0, 0, 100),
                    Frame(p0, 0, 0, 2, 2, 0, 0, 100),
                ),
            ),
        )
        return Triple(pool, p0, p1)
    }

    private fun evicted(buffer: GraphicsBuffer): Boolean = buffer.pixelBytes == 0L

    // ------------------------------------------------------------------
    // ① 记账面本体
    // ------------------------------------------------------------------

    @Test
    fun `记账面按引用判同并保持首次登记序`() {
        val scratch = SpriteEvictScratch()
        val a = madeBuffer(0)
        val b = madeBuffer(1)
        val c = madeBuffer(2)
        assertTrue(scratch.addCandidate(b))
        assertTrue(scratch.addCandidate(a))
        assertFalse(scratch.addCandidate(b), "第二次遇到同一对象必须判重")
        assertTrue(scratch.addCandidate(c))
        assertEquals(3, scratch.candidateCount())
        assertSame(b, scratch.candidateAt(0), "序 = 首次登记序，不是插入排序也不是哈希槽序")
        assertSame(a, scratch.candidateAt(1))
        assertSame(c, scratch.candidateAt(2))
        assertTrue(scratch.containsCandidate(a) && scratch.containsCandidate(b) && scratch.containsCandidate(c))
        assertFalse(scratch.containsCandidate(madeBuffer(99)), "没登记的恒不在")

        scratch.addGuard(a)
        scratch.addGuard(a)
        assertTrue(scratch.containsGuard(a))
        assertFalse(scratch.containsGuard(b))

        scratch.clear()
        assertEquals(0, scratch.candidateCount())
        assertFalse(scratch.containsCandidate(a))
        assertFalse(scratch.containsGuard(a), "clear 之后护栏面同样归零（下一发重新装名单）")
        assertTrue(scratch.addCandidate(a), "clear 后可重新收")
    }

    @Test
    fun `记账面扩容后成员与序不丢`() {
        val scratch = SpriteEvictScratch()
        val buffers = (0 until 1000).map { madeBuffer(it) }
        for (buffer in buffers) assertTrue(scratch.addCandidate(buffer))
        // 回头再判一遍全部在场（rehash 若丢项这里就红）。
        for (buffer in buffers) {
            assertTrue(scratch.containsCandidate(buffer), "扩容重挂后成员不丢")
        }
        for (i in buffers.indices) assertSame(buffers[i], scratch.candidateAt(i), "序在扩容后逐位不变")
        assertEquals(1000, scratch.candidateCount())
        assertFalse(scratch.addCandidate(buffers[42]), "扩容后判重仍然成立")
    }

    // ------------------------------------------------------------------
    // ② 传面与不传面逐字等价（判据/序/protect/读数一条不变）
    // ------------------------------------------------------------------

    @Test
    fun `预算闸传面与不传面的退出集合与读数一致`() {
        val (pool, p0, p1) = buildSharedPool()
        assertEquals(bytesPerSheet * 2, pool.resourceResidentPixelBytes(), "去重驻留 = 两张父图")

        // 无面（闸内现建）：预算 = 一张 ⇒ 登记序最前的 P0 先退，退到线上停。
        val freedNull = pool.evictDeferredPixelsWithinBudget(bytesPerSheet)
        assertEquals(pixelsPerSheet, freedNull)
        assertTrue(evicted(p0) && !evicted(p1), "旧口径：首次登记序第一张先退")

        // 重解复原，再走传面臂：退出集合、freed 条数、末态驻留逐项相同。
        p0.pixels
        val scratch = SpriteEvictScratch()
        val freedScratch = pool.evictDeferredPixelsWithinBudget(bytesPerSheet, scratch = scratch)
        assertEquals(freedNull, freedScratch, "传面/不传面 freed 逐字一致")
        assertTrue(evicted(p0) && !evicted(p1), "传面/不传面退出集合逐字一致")
        assertEquals(bytesPerSheet, pool.resourceResidentPixelBytes())
        assertTrue(scratch.candidateCount() >= 2, "外部面真的被闸用了（闸内没有另搭私货）")

        // protect 对照：传 P0 时一张都不许多退（P0 被护 ⇒ 下一张 P1 顶上）。
        p0.pixels
        val guarded = pool.evictDeferredPixelsWithinBudget(bytesPerSheet, protect = p0, scratch = scratch)
        assertEquals(pixelsPerSheet, guarded)
        assertTrue(!evicted(p0) && evicted(p1), "protect 语义与面形无关：护住的那张恒不退")
    }

    @Test
    fun `全表闸传面与不传面等价`() {
        val (pool, p0, p1) = buildSharedPool()
        val scratch = SpriteEvictScratch()
        // 不传面：一次退干净（4 名片去重成 2 张，各 4 像素 ⇒ 8）。
        assertEquals(pixelsPerSheet * 2, pool.evictDeferredPixels())
        p0.pixels
        p1.pixels
        assertEquals(pixelsPerSheet * 2, pool.evictDeferredPixels(scratch), "传面同读数")
        assertEquals(2, scratch.candidateCount(), "共享父图按身份去重：两张面就是两张")
    }

    // ------------------------------------------------------------------
    // ③ 两臂自证：回收闸的自身分配
    // ------------------------------------------------------------------

    /**
     * 分配读数对：n = 20,000 张不可退缓冲（驻留 320 KB > 预算 0 ⇒ 每发都走完整慢路径；
     * evictDeferred 恒 0 ⇒ 驻留不降，形状可复现而不受「退完转快路径」干扰）。
     *
     * - **复现红臂**（不传面）：闸每次调用都要为记账现搭容器 —— 旧形状（本波之前）是
     *   HashSet(40k 槽)+ArrayList+快照 HashSet 三件，新形状不传面时至少一块表+一条序数组，
     *   分配随池体量线性走；钉「下限 > 100 KB」= 报告里 524,304 字节那一笔的同族放大读数。
     * - **修复臂**（同一块面第二次）：候选表与序数组都是复用的槽位，遍历全程纯引用读写，
     *   钉「< 2 KB」。摘除修复（闸忽略传入面、每发现建）即此臂变红。
     */
    @Test
    fun `贴顶形状下不传面闸自己分配传面后稳态零分配`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        val tid = Thread.currentThread().id
        assumeTrue(bean.getThreadAllocatedBytes(tid) != -1L, "线程分配计数未开启，跳过分配读数钉")

        val n = 20_000
        val (pool, _) = buildPlainPool(n)
        val scratch = SpriteEvictScratch()
        // 预装填：让面先把表长到水位，测的第二发才是「稳态」。
        pool.evictDeferredPixelsWithinBudget(0L, scratch = scratch)

        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reusedFreed = pool.evictDeferredPixelsWithinBudget(0L, scratch = scratch)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val freshFreed = pool.evictDeferredPixelsWithinBudget(0L)
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        assertEquals(0L, reusedFreed, "不可退形状：两臂的 freed 都必须是 0（闸没多退东西）")
        assertEquals(0L, freshFreed)
        assertTrue(
            reusedDelta < 2_048L,
            "修复臂：复用面的完整扫描稳态零分配（$n 张全遍历实测 $reusedDelta 字节）",
        )
        assertTrue(
            freshDelta > 100_000L,
            "复现臂：不传面时闸为记账现搭容器，$n 张实测只分配了 $freshDelta 字节 —— " +
                "贴顶堆（<1% free，报告 6c08e8bb-0 现场 3,077,656 free）上这笔就是压垮点",
        )
    }

    @Test
    fun `合成图档传面与不传面等价且护单一致`() {
        val pool = SpritePool()
        val composed = GraphicsBuffer(1)
        composed.create(4, 4)
        val named1 = Sprite("OUT_STAND", 4, 4, composed)
        val named2 = Sprite("OUT_FACE", 4, 4, composed)
        pool.put(named1)
        pool.put(named2)
        val resident = composed.pixelBytes
        val budget = resident / 2L

        val r = pool.evictCompositedSpritesWithinBudget(resident, budget, scratch = SpriteEvictScratch())
        assertEquals(2, r.sprites, "同一个 GID 挂两个名字 ⇒ 两个名字一起消失（旧钉法不变）")
        assertEquals(16L, r.pixels)
        assertNull(pool.find("OUT_STAND"))
        assertNull(pool.find("OUT_FACE"))
        assertEquals(0L, composed.pixelBytes, "选中的缓冲本体已 dispose")
    }
}
