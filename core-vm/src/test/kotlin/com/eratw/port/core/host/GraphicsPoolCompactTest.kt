package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [GraphicsPool] 的空壳条目摘表（2026-11-08 OOM-A 波，CONTRACT-OOM-A §2）。
 *
 * 钉的是「`buffers` 只增不减」这半句旧登记的收口：
 * - [GraphicsPool.releaseUnusedLocked] dispose 过的条目（画过的合成图、无文件源）必须
 *   从条目表摘掉 —— 旧口径下只剩全零壳挂表里，语料 `@GET_EMPTY_G`
 *   （グラフィック特殊効果.ERB:254-267）每帧扫空编号发新 gid，壳随游玩时长线性累积
 *   （2026-11-07 玩家报告 5783b90 的图形池读数 = 1543 项）。
 * - 摘表后 [GraphicsPool.get] 重建空条目，`isCreated == false` ⇒ `GCREATED == 0`，
 *   与 PC 原版释放后可复用的语义一致（GraphicsImage.cs:528-560）—— 这条必须锁住，
 *   防止将来有人把「摘表」升级成「报错」。
 * - 文件源缓冲（工作表）不 dispose 也不摘；guards 名单内的缓冲不动。
 * - 记账守恒：摘表不走记账路径（dispose 已成对扣回），重建后驻留仍为零。
 *
 * 全部小尺寸（64×64 = 16 KiB 像素级），预算档行为与真尺寸同形（口径同 [GraphicsPoolBudgetTest]）。
 */
class GraphicsPoolCompactTest {

    private val side = 64

    /** 造一张「画过的合成图」（无文件源、有像素、isCreated）—— 第三档/水位档的 dispose 对象。 */
    private fun composited(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        buffer.create(side, side)
        buffer.setPixel(0, 0, 0xFF112233.toInt())
        return buffer
    }

    // ------------------------------------------------------------------
    // ① dispose 后空壳被摘表；get 重建为未创建（GCREATED==0 语义）
    // ------------------------------------------------------------------

    @Test
    fun `releaseUnused dispose 过的合成图空壳条目被摘除`() {
        val pool = GraphicsPool()
        val buffer = composited(pool, 1)
        assertTrue(buffer.isCreated)
        assertEquals(1, pool.stats().entries)

        val released = pool.releaseUnused(SpriteEvictScratch(), minPixels = side, budgetBytes = null)

        assertEquals(1, released.buffers, "dispose 计数不变")
        assertNull(pool.peek(1), "空壳条目必须摘表 —— 旧代码在这里返回 dispose 后的全零壳")
        assertEquals(0, pool.stats().entries, "条目数读数随之回落（旧口径恒 1，只增不减）")
    }

    @Test
    fun `摘表后 get 重建空条目且 GCREATED 语义与 PC 原版释放后一致`() {
        val pool = GraphicsPool()
        composited(pool, 1)
        pool.releaseUnused(SpriteEvictScratch(), minPixels = side, budgetBytes = null)

        val again = pool.get(1)

        assertFalse(again.isCreated, "重建条目 = 未创建（GCREATED==0，@GET_EMPTY_G 靠它复用 gid）")
        assertEquals(0, again.width, "未创建宽高读 0（GraphicsImage.cs:44-54 同口径）")
        assertEquals(0, again.height)
        assertEquals(1, pool.stats().entries, "get 惰性重建一条，不多建")
        // 重建对象可以照常再 create（复用链路不因摘表断裂）
        again.create(side, side)
        assertTrue(again.isCreated)
    }

    // ------------------------------------------------------------------
    // ② 记账守恒：dispose 扣回不受摘表影响，重建不虚增
    // ------------------------------------------------------------------

    @Test
    fun `摘表前后池级驻留读数守恒`() {
        val pool = GraphicsPool()
        val buffer = composited(pool, 1)
        val createdBytes = pool.residentBytes()
        assertEquals(side.toLong() * side * 4L, createdBytes, "64×64 ARGB 驻留")

        pool.releaseUnused(SpriteEvictScratch(), minPixels = side, budgetBytes = null)

        assertEquals(0L, pool.residentBytes(), "dispose 已成对扣回，摘表不动记账")
        pool.get(1)
        assertEquals(0L, pool.residentBytes(), "重建的空条目驻留仍为零")
        assertEquals(0L, buffer.retainedBytes, "旧对象账目也归零（dispose 时扣回）")
    }

    // ------------------------------------------------------------------
    // ③ 文件源缓冲：不 dispose、不摘表（尺寸与重解语义挂在条目上）
    // ------------------------------------------------------------------

    @Test
    fun `文件源工作表不被摘表`() {
        val pool = GraphicsPool()
        val sheet = pool.get(1)
        assertTrue(
            sheet.createDeferredFromFile(side, side, "resources/compact-sheet.webp") {
                IntArray(side * side)
            },
        )
        sheet.pixels // 首访问落地（不改写 ⇒ fileSource 保留）
        assertTrue(sheet.fileSource != null, "前置：文件源还在（画写不变量的等价臂）")

        pool.releaseUnused(SpriteEvictScratch(), minPixels = side, budgetBytes = null)

        assertSame(sheet, pool.peek(1), "文件源条目原样留在表里")
        assertTrue(sheet.isCreated, "宽高与创建态逐字不变")
        assertEquals(side, sheet.width)
    }

    // ------------------------------------------------------------------
    // ④ guards 名单内的缓冲：不 dispose、不摘表、同一对象
    // ------------------------------------------------------------------

    @Test
    fun `guards 名单内的合成图不被摘表`() {
        val pool = GraphicsPool()
        val buffer = composited(pool, 1)
        val guards = SpriteEvictScratch()
        assertTrue(guards.addGuard(buffer))

        pool.releaseUnused(guards, minPixels = side, budgetBytes = null)

        assertSame(buffer, pool.peek(1), "被护栏护住的条目原样保留")
        assertTrue(buffer.isCreated)
        assertEquals(pool.stats().pixelBytes, pool.residentBytes(), "stats 与原子计数恒等")
    }

    // ------------------------------------------------------------------
    // ⑤ 多张空壳批量摘除（报告里 1543 项的最小复现形状）
    // ------------------------------------------------------------------

    @Test
    fun `批量合成图回收后条目表清空`() {
        val pool = GraphicsPool()
        repeat(4) { composited(pool, it) }
        assertEquals(4, pool.stats().entries)

        val released = pool.releaseUnused(SpriteEvictScratch(), minPixels = side, budgetBytes = null)

        assertEquals(4, released.buffers)
        assertEquals(0, pool.stats().entries, "四张壳全部摘除 —— 报告里 1543 项的累积形态由此收口")
        assertEquals(0L, pool.residentBytes())
    }
}
