package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 降级态 PNG 载荷的**账本归属**（2026-11-06 长时间游玩 OOM 根因回归钉）。
 *
 * ## 判死的缺陷
 *
 * [GraphicsBuffer.demotePixelsToPng] 把「画过的」合成缓冲的像素阵列换成 PNG 闭包，
 * 而旧版 [GraphicsBuffer.publishSurfaceLocked] 只按 `pixelData.size * 4` 记账 ——
 * 降级代 `pixelData` 是空数组 ⇒ 池级账本 `residentBytes()` 归零，可那份 PNG
 * `ByteArray` 仍被闭包强引用着、仍占 Dalvik 堆的大对象空间。
 *
 * 于是「释放」只是把驻留从账本内搬到了账本外，四道判据一起失明：
 * - [GraphicsPool.get] 每语句点的预算闸按 `resident <= budget` 一进门就早退；
 * - 预算闸第二档对已在降级态的缓冲调 `demotePixelsToPng` 恒收 0；
 * - `RunTrail.shouldEvictCompositedSprites` 的条件一 `resident > budget` 恒假，
 *   唯一会 dispose 降级缓冲、真正释放 PNG 的第三档一次都不触发；
 * - 真机报告行与 LiveSetCensus 读同一根账本，账上 0、堆里几百 MiB。
 *
 * 而 [GraphicsPool.buffers] 只增不减、语料 `@GET_EMPTY_G` 每帧发新 gid ⇒ 每降级一张
 * 就留一份账外 PNG，随游玩时长线性累积，直到 OOM。玩家看到的就是「玩久了有时闪退」。
 *
 * ## 本套钉的东西
 *
 * 修法不是加阈值，是让账本与实物恒等：降级载荷按实际字节数入账，
 * 晋升 / dispose 自动成对扣回（同一处 `getAndSet` 算差，无单侧路径）。
 * 账本说真话之后，逐出档重新看得见这批驻留并能真正收回它。
 *
 * 全部小尺寸（64x64，16 KiB 像素级）：账本是字节判据，与真机 600x600 / 8000x4000
 * 的分配形状同构；PNG 大小用 [GraphicsBuffer.toSavePng] 在降级前精确取，
 * 不做「大于 0」这种含糊断言。
 */
class GraphicsDemotedPayloadLedgerTest {

    private val side = 64
    private val sheetPixels = side.toLong() * side
    private val sheetBytes = sheetPixels * 4L

    /** 一张脚本画过、无文件源、无重解源的合成缓冲 —— 降级档的典型候选。 */
    private fun painted(pool: GraphicsPool, id: Int, seed: Int = 0x7F000001): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { idx -> seed + idx }))
        return buffer
    }

    private fun sprite(name: String, buffer: GraphicsBuffer) =
        SpritePool.Sprite(name = name, width = side, height = side, buffer = buffer)

    @Test
    fun `降级后的 PNG 载荷按实际字节数进账本不许归零`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val pngBytes = buffer.toSavePng().size.toLong()
        assertEquals(sheetBytes, pool.residentBytes(), "前置：像素驻留")

        assertEquals(sheetPixels, buffer.demotePixelsToPng(), "降级按像素条数计")

        assertEquals(0L, buffer.pixelBytes, "像素阵列确实退场")
        assertTrue(buffer.isPixelsDemoted)
        assertTrue(buffer.isCreated, "GCREATED 不翻（GraphicsImage.cs:44-54）")
        // 旧代码：这里是 0，而 PNG 还在堆里（下面逐字复原就是账本说谎的铁证）。
        assertEquals(pngBytes, pool.residentBytes(), "账本必须记下仍被闭包强引用的 PNG 载荷")
        assertEquals(pngBytes, buffer.retainedBytes, "分项读数与池级账本同源")
        assertEquals(pool.stats().pixelBytes, pool.residentBytes(), "原子计数与遍历读数恒等")
        assertTrue(pngBytes in 1 until sheetBytes, "本夹具的 PNG 必须真的省了内存")

        // 载荷确实还在：首次真读逐字解回原像素（旧代码也做得到，正是账本说谎的证据）。
        val restored = buffer.pixels
        assertEquals(sheetPixels, restored.size.toLong())
        assertEquals(0x7F000001, restored[0])
    }

    @Test
    fun `逐张降级时账本按载荷线性累积而不是恒为零`() {
        val pool = GraphicsPool()
        var accumulated = 0L
        repeat(6) { i ->
            val buffer = painted(pool, i + 1, seed = 0x01000000 + i * 0x00110000)
            accumulated += buffer.toSavePng().size.toLong()
            assertTrue(buffer.demotePixelsToPng() > 0L)
            assertEquals(
                accumulated, pool.residentBytes(),
                "第 ${i + 1} 张降级后账本 = 已积压的全部载荷；旧代码恒为 0 ⇒ 长游玩无上界累积",
            )
            assertEquals(pool.stats().pixelBytes, pool.residentBytes())
        }
        assertTrue(accumulated > 0L)
    }

    @Test
    fun `合成图逐出档能真正收回降级态载荷`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val buffer = painted(pool, 1)
        val pngBytes = buffer.toSavePng().size.toLong()
        assertTrue(buffer.demotePixelsToPng() > 0L)
        // 4.981 把合成结果登记成具名 Sprite（HIGHRES_CHARA.ERB:185），
        // 前两档正因为这份引用而够不着它，第三档是唯一的释放路径。
        sprites.put(sprite("OUT_STAND", buffer))

        val resident = pool.residentBytes()
        assertTrue(resident > 0L, "账本看得见载荷 ⇒ shouldEvictCompositedSprites 的条件一才可能成立")

        val r = sprites.evictCompositedSpritesWithinBudget(resident, 0L)

        assertEquals(1, r.sprites, "逐出档必须摘到这张降级缓冲")
        // 存档求援（GameHost.releaseUnusedGraphics）把这发交回的像素条数乘 4 当作
        // 「已回收字节」；它必须是降级载荷的真实量级（旧账本里这里恒 0 ⇒ 求援判死）。
        assertEquals(pngBytes / 4L, r.pixels, "释放量按 retainedBytes 计，不含像素阵列（已退场）")
        assertNull(sprites.find("OUT_STAND"), "SPRITECREATED 转假 ⇒ 语料自己重算")
        assertFalse(buffer.isCreated, "dispose 才真正释放那份 PNG 载荷")
        assertEquals(0L, pool.residentBytes(), "载荷随 dispose 成对扣回，账上不留残余")
        assertEquals(0L, buffer.retainedBytes)
        assertEquals(0, buffer.pixels.size, "降级载荷已随整代作废，不再可解回")
        assertTrue(pngBytes > 0L)
    }

    @Test
    fun `晋升与 dispose 把载荷成对扣回不漂移不为负`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val pngBytes = buffer.toSavePng().size.toLong()
        assertTrue(buffer.demotePixelsToPng() > 0L)
        assertEquals(pngBytes, pool.residentBytes())

        buffer.pixels // 透明晋升：闭包作废、像素阵列入账
        assertFalse(buffer.isPixelsDemoted)
        assertEquals(sheetBytes, pool.residentBytes(), "载荷扣回 + 像素入账，一步算差")
        assertEquals(sheetBytes, buffer.retainedBytes)

        assertTrue(buffer.demotePixelsToPng() > 0L)
        assertEquals(pngBytes, pool.residentBytes(), "再降一发仍精确，不欠账")
        buffer.dispose()
        assertEquals(0L, pool.residentBytes(), "dispose 一次扣清")
        assertEquals(0L, buffer.retainedBytes)
        pool.clear()
        assertEquals(0L, pool.residentBytes(), "清池不得把已作废的载荷扣成负数")
    }

    @Test
    fun `预算闸按真实载荷判停且不把账本记没`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val pngBytes = buffer.toSavePng().size.toLong()
        assertTrue(buffer.demotePixelsToPng() > 0L)

        // 预算恰好等于载荷 ⇒ 判停成立，闸不该虚报释放。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(pngBytes))
        assertEquals(pngBytes, pool.residentBytes())
        // 预算再压一字节 ⇒ 闸确实进了循环；降级态无可再降，freed 为 0，
        // 但绝不能像旧代码那样「一进门就以为已经收干净」。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(pngBytes - 1L))
        assertEquals(pngBytes, pool.residentBytes(), "不能凭空把载荷记没")
    }

    @Test
    fun `混合形态下原子账本与遍历读数恒等`() {
        val pool = GraphicsPool()
        pool.get(90) // 惰性建的空条目
        val live = painted(pool, 91)
        val demoted = painted(pool, 92)
        assertTrue(demoted.demotePixelsToPng() > 0L)
        val disposed = painted(pool, 93)
        disposed.dispose()

        val expected = sheetBytes + demoted.retainedBytes
        assertEquals(expected, pool.residentBytes())
        assertEquals(expected, pool.stats().pixelBytes)
        assertEquals(0L, disposed.retainedBytes)
        assertEquals(4, pool.stats().entries)
        assertEquals(2, pool.stats().created, "降级不动 GCREATED，dispose 才翻")
        assertEquals(1, pool.stats().demoted)
        assertEquals(sheetBytes, live.retainedBytes)
    }
}
