package com.eratw.port.core.host

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H1（2026-11 挖虫波 W2-gfxconc）：[GraphicsBuffer] 的**换代发布**口径。
 *
 * 旧实现把 width/height/pixelData/pendingPixels 摊成普通散字段：读侧只在 [GraphicsBuffer.pixels]
 * 的监视器里解码，写侧（create/createFrom/createDeferred/dispose）完全不取锁，于是有两类病：
 * 1. **撕裂** —— 迟到的延迟解码把「旧尺寸的那一代像素」发布进「新尺寸」的几何里，
 *    稳态就持有 `pixels.size != width*height` 的错配对象；
 * 2. **复活** —— dispose 之后同一笔解码照常落像素并重入池级记账（`poolResidentBytes`），
 *    而回收凭据已被 dispose 清掉：这块字节既画不到、也收不回，读数永久虚高。
 *
 * 新口径：几何+像素+两级延迟源收拢成不可变 `Surface`，@Volatile 整代发布；一切换代（含解码
 * 落地）收进同一把实例监视器 —— 解码与 dispose/create 串行，迟到结果要么被随后的 dispose
 * 成对扣回记账，要么发现自己那代已被替换而整代作废（记账成对作废）。
 * 本文件钉的正是这两条「修复前必红、修复后恒绿」的稳态不变量。
 */
class GraphicsBufferPublicationTest {

    private fun awaitQuietly(threads: List<Thread>) {
        for (t in threads) t.join(TimeUnit.SECONDS.toMillis(30))
        for (t in threads) assertFalse(t.isAlive, "线程 ${t.name} 未收敛")
    }

    /**
     * 撕裂钉：延迟解码进行中，VM 侧同缓冲 `createFrom` 换代；join 后**稳态**必须满足
     * `pixels.size == width*height`，池级记账与像素逐字节同源。
     *
     * 旧写法：解码线程拿着 2x2 的产物在无锁写侧换完尺寸后照样发布 ⇒ 稳态 pixels=4、
     * w*h=6、residentBytes 混账；新写法两侧在同一把锁上串行，稳态恒自洽。
     */
    @Test
    fun `迟到的延迟解码不会撕裂进新一代几何`() {
        val pool = GraphicsPool()
        val buf = pool.get(70)
        assertTrue(buf.createDeferred(2, 2) {
            Thread.sleep(60) // 模拟真机整图解码窗口
            IntArray(4) { 0x11 }
        })
        val decoder = Thread({ buf.pixels }, "gfx-decode").also { it.start() }
        Thread.sleep(10) // 让解码先入锁
        buf.createFrom(3, 2, IntArray(6) { 0x22 })
        awaitQuietly(listOf(decoder))

        assertEquals(6, buf.pixels.size, "稳态像素数必须等于宽×高（换代不得撕裂）")
        assertEquals(3, buf.width)
        assertEquals(2, buf.height)
        assertEquals(buf.pixels.size * 4L, buf.pixelBytes)
        assertEquals(buf.pixels.size * 4L, pool.residentBytes(), "池级记账必须与在任一代像素同源")
    }

    /**
     * 复活钉：解码进行中 dispose —— join 后缓冲读为空、`isCreated` 关死、记账恒 0，
     * 清池也不会扣成负数（「记账成对作废」恒等式）。
     *
     * 旧写法：dispose 不取锁，先一步把记账清零；随后解码落地走 `storePixels` 把 16 字节
     * **重入账**并复活像素数组 ⇒ `residentBytes()==16` 且永远收不回去。
     */
    @Test
    fun `dispose 后迟到的解码不复活像素也不重入账`() {
        val pool = GraphicsPool()
        val buf = pool.get(71)
        assertTrue(buf.createDeferred(2, 2) {
            Thread.sleep(60)
            IntArray(4) { 0x33 }
        })
        val decoder = Thread({ buf.pixels }, "gfx-decode-dispose").also { it.start() }
        Thread.sleep(10)
        buf.dispose()
        awaitQuietly(listOf(decoder))

        assertEquals(0, buf.pixels.size, "已 dispose 的缓冲不得持有像素（不复活）")
        assertEquals(0, buf.width)
        assertEquals(0, buf.height)
        assertFalse(buf.isCreated)
        assertEquals(0L, pool.residentBytes(), "dispose 与换代记账成对作废：驻留读数恒 0")
        assertEquals(0L, buf.evictDeferred(), "复活路径连回收资格都不该留")
        pool.clear()
        assertEquals(0L, pool.residentBytes(), "清池不得把已作废的账扣成负数")
    }

    /**
     * draw 早退钉：dispose 之后一切画写入口（setPixel/clear/fillRect/blitFrom）都是 no-op，
     * 不炸、不复活、不记账；文件源也不会被「裁没的一帧」误作废。
     */
    @Test
    fun `dispose 后画写入口整体早退`() {
        val pool = GraphicsPool()
        val buf = pool.get(72)
        buf.create(2, 2)
        val src = pool.get(73)
        src.createFrom(2, 2, IntArray(4) { 0xFF00FF00.toInt() })
        buf.dispose()

        buf.setPixel(0, 0, 0xFF0000FF.toInt())
        buf.clear(0xFFFFFFFF.toInt())
        buf.fillRect(0, 0, 2, 2, 0xFF0000FF.toInt(), replace = true)
        buf.blitFrom(src, 0, 0, 2, 2, 0, 0, 2, 2)
        buf.drawLine(0, 0, 1, 1, 0xFF0000FF.toInt())

        assertEquals(0, buf.pixels.size)
        assertEquals(0L, buf.pixelBytes)
        assertEquals(16L, pool.residentBytes(), "只有 src(2x2) 还驻留：被 dispose 的 buf 一分不计")
        assertEquals(0, buf.pixelAt(0, 0))
    }

    /**
     * 换代快照一致性（双线程压测）：写侧循环换代（create/createFrom/dispose/createDeferred），
     * 每代像素**整数组同色**；读侧循环取 [GraphicsBuffer.pixels]，任何时刻拿到的数组必须
     * 「整代同色或为空」——元素级混合（旧实现里读者可读到换一半的引用/尺寸组合）必须为 0 次。
     */
    @Test
    fun `并发换代下像素数组恒整代发布`() {
        val buf = GraphicsBuffer(74)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val mixed = java.util.concurrent.atomic.AtomicInteger(0)
        val writer = Thread({
            var round = 0
            while (!stop.get()) {
                when (round % 4) {
                    0 -> buf.create(2, 2)
                    1 -> buf.createFrom(3, 2, IntArray(6).also { it.fill(0xAAAA0000.toInt()) })
                    2 -> assertTrue(buf.createDeferred(1, 1) { IntArray(1) { 0xBBBB0000.toInt() } })
                    else -> buf.dispose()
                }
                round++
            }
        }, "gfx-writer")
        val reader = Thread({
            while (!stop.get()) {
                val px = buf.pixels
                if (px.isEmpty()) continue
                // 整代判据：非空数组的每个元素都必须等于该代填充色
                //（create(2,2) 全 0、createFrom 全 0xAAAA0000、延迟解出 0xBBBB0000）。
                if (px.size != 4 && px.size != 6 && px.size != 1) mixed.incrementAndGet()
                val first = px[0]
                for (v in px) if (v != first) { mixed.incrementAndGet(); break }
            }
        }, "gfx-reader")
        writer.start()
        reader.start()
        Thread.sleep(250)
        stop.set(true)
        awaitQuietly(listOf(writer, reader))
        assertEquals(0, mixed.get(), "读侧不得看到跨代混合的像素数组")
    }
}
