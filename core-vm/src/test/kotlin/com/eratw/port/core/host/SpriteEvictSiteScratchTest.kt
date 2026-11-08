package com.eratw.port.core.host

import com.eratw.port.core.host.SpritePool.Sprite
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **oomsrc2 波（2026-11-05）—— GameHost 两处遗留调用点的分配读数**。
 *
 * oomsrc 那一波把 [SpriteEvictScratch] 与四个逐出入口的可选 `scratch` 参数落了地，
 * 但只接了一发（`GameHost.releaseImagePixelsIfHeapHigh` 的负号段**预算档**，
 * 见 `SpriteEvictScratchTest`），并把两处没接的调用点留给组长：
 * 1. `GameHost.releaseResourceImagePixels` → [SpritePool.evictDeferredPixels]（**全表档**，
 *    80%/92% 水位档、系统压力档、页中检查点、存档求援、UI 的 `onTrimMemory` 都走这一发）；
 * 2. `GameHost.evictCompositedSpritesIfOverBudget` → [SpritePool.evictCompositedSpritesWithinBudget]
 *    （**合成图档**，触发判式本身就是堆 ≥ 80%）。
 *
 * 本文件给这两处各钉一条**自己的**两臂读数（复现臂 = 不传面时闸为记账现搭容器；
 * 修复臂 = 同一块面第二次走完整扫描时稳态零分配），形状逐字对着接线后的调用：
 * 全表档传 `scratch = 面`、合成图档传 `exclude`（CBG 名单）+ `scratch = 面`。
 *
 * **摘除臂的做法**（破坏性验证）：把对应闸里的 `scratch ?: SpriteEvictScratch()` 改成
 * 恒现建 ⇒ 本文件对应的「修复臂」断言变红（另会连带 `SpriteEvictScratchTest` 的
 * `全表闸传面与不传面等价`，那条钉的正是「外部面真的被闸用了」），复原后复绿。
 *
 * 分配计数用 HotSpot `com.sun.management.ThreadMXBean` 的线程累计分配
 * （与 `SpriteEvictScratchTest` / `GraphicsBufferBlitFromTest` 同款装置；
 * 非 HotSpot JVM 按 assumeTrue 跳过）。真机（贴顶堆）读数本文件给不出 —— 见交付 TODO(verify)。
 */
class SpriteEvictSiteScratchTest {

    /** 装置尺寸：2×2 像素、16 字节驻留 —— 判据是字节算式，与真实图同形。 */
    private val side = 2
    private val bytesPerSheet = side.toLong() * side * 4L
    private val pixelsPerSheet = side.toLong() * side

    private fun madeBuffer(index: Int): GraphicsBuffer {
        // 负号 id + create（不带 deferredSource）⇒ evictDeferred 恒 0：驻留不动，
        // 全表档每发都走完整遍历 —— 正是「反复现搭容器」的放大形状。
        val buffer = GraphicsBuffer(-(index + 1))
        buffer.create(side, side)
        return buffer
    }

    /** n 张独立缓冲、每缓冲一个名的资源表（全表档的扫描面）。 */
    private fun buildResourcePool(n: Int): SpritePool {
        val pool = SpritePool()
        for (i in 0 until n) pool.putResource(Sprite("PLAIN$i", side, side, madeBuffer(i)))
        return pool
    }

    /**
     * 合成图档的现场形状：资源表 n 张（全部进**护栏面**，逐条 addGuard）+ 运行表 n 个具名
     * 合成 Sprite（全部进**候选面**，`fileSource == null` 且 `isCreated` 且非动画）。
     */
    private fun buildCompositedPool(n: Int): SpritePool {
        val pool = SpritePool()
        for (i in 0 until n) pool.putResource(Sprite("PLAIN$i", side, side, madeBuffer(i)))
        for (i in 0 until n) pool.put(Sprite("OUT_$i", side, side, GraphicsBuffer(i + 1).apply { create(side, side) }))
        return pool
    }

    private fun requireAllocBean(): com.sun.management.ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        assumeTrue(
            bean.getThreadAllocatedBytes(Thread.currentThread().id) != -1L,
            "线程分配计数未开启，跳过分配读数钉",
        )
        return bean
    }

    // ------------------------------------------------------------------
    // 调用点①：releaseResourceImagePixels → SpritePool.evictDeferredPixels（全表档）
    // ------------------------------------------------------------------

    @Test
    fun `全表档贴顶形状下不传面现搭容器、传面稳态零分配`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 20_000
        val pool = buildResourcePool(n)
        assertEquals(bytesPerSheet * n, pool.resourceResidentPixelBytes(), "装置：n 张去重驻留")

        // 修复臂：同一块面**第二次**完整遍历（预装填把候选表与序数组长到水位）。
        val scratch = SpriteEvictScratch()
        assertEquals(0L, pool.evictDeferredPixels(scratch), "装置：无 deferredSource ⇒ 一张都退不掉")
        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reusedFreed = pool.evictDeferredPixels(scratch)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        // 复现臂：不传面（接线前的调用形状）⇒ 闸每发现搭一块面并长到同一水位。
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val freshFreed = pool.evictDeferredPixels()
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("oomsrc2.site1 n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(reusedFreed, freshFreed, "传面/不传面 freed 逐字一致（本装置恒 0）")
        assertEquals(0L, reusedFreed)
        assertTrue(
            reusedDelta < 2_048L,
            "修复臂：复用面的全表扫描稳态零分配（$n 张实测 $reusedDelta 字节）—— " +
                "GameHost.releaseResourceImagePixels 传面后就是这条臂",
        )
        assertTrue(
            freshDelta > 100_000L,
            "复现臂：不传面时闸为记账现搭容器，$n 张实测只分配了 $freshDelta 字节 —— " +
                "贴顶堆（<1% free，报告 6c08e8bb-0 现场）上这笔就是压垮点",
        )
    }

    // ------------------------------------------------------------------
    // 调用点②：evictCompositedSpritesIfOverBudget → evictCompositedSpritesWithinBudget
    // ------------------------------------------------------------------

    @Test
    fun `合成图档贴顶形状下不传面现搭容器、传面稳态零分配`() {
        val bean = requireAllocBean()
        val tid = Thread.currentThread().id
        val n = 10_000
        val resident = bytesPerSheet * n
        val scratch = SpriteEvictScratch()

        // 修复臂：预热一发（护栏面与候选面都长到水位）后，换一张同规模的池再走一遍。
        // 这一档会把选中的缓冲 dispose 并从运行表摘名 ⇒ 每次测量都要新池，池的构造在计时之外。
        buildCompositedPool(n).evictCompositedSpritesWithinBudget(resident, 0L, emptySet(), scratch)
        val warmPool = buildCompositedPool(n)
        val beforeReused = bean.getThreadAllocatedBytes(tid)
        val reused = warmPool.evictCompositedSpritesWithinBudget(resident, 0L, emptySet(), scratch)
        val reusedDelta = bean.getThreadAllocatedBytes(tid) - beforeReused

        // 复现臂：不传面（接线前的调用形状，GameHost 那处正是 `evict(resident, budget, keep)`）。
        val freshPool = buildCompositedPool(n)
        val beforeFresh = bean.getThreadAllocatedBytes(tid)
        val fresh = freshPool.evictCompositedSpritesWithinBudget(resident, 0L, emptySet())
        val freshDelta = bean.getThreadAllocatedBytes(tid) - beforeFresh

        println("oomsrc2.site2 n=$n reused=${reusedDelta}B fresh=${freshDelta}B")
        assertEquals(n, reused.sprites, "传面臂：n 个名字全摘（预算 0 ⇒ 候选穷尽才停）")
        assertEquals(fresh.sprites, reused.sprites, "传面/不传面摘名数逐字一致")
        assertEquals(fresh.pixels, reused.pixels, "传面/不传面释放条数逐字一致")
        assertEquals(pixelsPerSheet * n, reused.pixels)
        assertTrue(
            reusedDelta < 2_048L,
            "修复臂：复用面的护栏装载 + 候选选择稳态零分配（$n+$n 张实测 $reusedDelta 字节）—— " +
                "GameHost.evictCompositedSpritesIfOverBudget 传面后就是这条臂",
        )
        assertTrue(
            freshDelta > 100_000L,
            "复现臂：不传面时闸现搭护栏面与候选面，$n+$n 张实测只分配了 $freshDelta 字节 —— " +
                "这一档的触发判式本身就是堆 ≥ 80%，与 6c08e8bb 簇同族",
        )
    }

    @Test
    fun `合成图档传面时 CBG 名单真的进了护栏面`() {
        // 接线形状对照：GameHost 传的是 `keep` = CBG 图层正在引用的缓冲（Sprite 那一半刻意不传，
        // 它正是候选集）。这条钉「外部面被闸用了 + exclude 语义与面形无关」。
        val pool = SpritePool()
        val cbgBuffer = GraphicsBuffer(9_001).apply { create(side, side) }
        pool.put(Sprite("CBG_PINNED", side, side, cbgBuffer))
        val target = GraphicsBuffer(9_002).apply { create(side, side) }
        pool.put(Sprite("OUT_STAND", side, side, target))
        val scratch = SpriteEvictScratch()

        val r = pool.evictCompositedSpritesWithinBudget(
            cbgBuffer.pixelBytes + target.pixelBytes,
            0L,
            exclude = setOf(cbgBuffer),
            scratch = scratch,
        )
        assertEquals(1, r.sprites, "exclude 里的那张不许动（旧口径逐字不变）")
        assertEquals(pixelsPerSheet, r.pixels)
        assertEquals(1, scratch.candidateCount(), "候选面装的就是被 dispose 的那一张 ⇒ 面真被闸用了")
        assertTrue(scratch.containsCandidate(target))
        assertTrue(scratch.containsGuard(cbgBuffer), "CBG 名单落进护栏面（宿主传的那一份）")
        assertEquals(0L, target.pixelBytes, "选中的缓冲已 dispose")
        assertEquals(bytesPerSheet, cbgBuffer.pixelBytes, "护住的那张驻留不动")
    }
}
