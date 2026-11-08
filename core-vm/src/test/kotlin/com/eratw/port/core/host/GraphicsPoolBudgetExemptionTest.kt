package com.eratw.port.core.host

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 预算档里那几条**「不可知 / 恰好够 / 从未发生过」的豁免支**（lane eng-forced，任务 D5）。
 *
 * ## 为什么要单独钉
 *
 * 这些支在生产里靠「桌面读不到真机挡位」被动成立：`defaultPixelBudgetBytes` 对不可知堆回落
 * `Long.MAX_VALUE`、逐出判式在「驻留 ≤ 预算」时零动作、`msSinceLastWrite` 从未写过时给
 * `Long.MAX_VALUE`。它们过去只靠算式本身被读着，没有一条用例能**证伪**：
 * 把回落改回 0（预算档在桌面就变成「一像素都不许留」）、把判停的 `<=` 写成 `<`
 * （每发都多退一张）、把从未写过的哨兵写成 0（渲染层以为「刚写完」，位图永不重建）——
 * 三种都编译得过、桌面全绿。
 *
 * ## 口径
 *
 * 全部小尺寸（64x64 = 16 KiB 像素级）：预算档是**字节**判据，行为与真机 8000x4000 工作表同形，
 * 单测不拿真尺寸图烧堆（同 [GraphicsPoolBudgetTest] 类 KDoc）。生产行为一个字都没改：
 * 本类只调既有 API。
 */
class GraphicsPoolBudgetExemptionTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384
    private val mib = 1024L * 1024L

    @AfterTest
    fun clearOverrides() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    /** 建一张「文件源还在」的 G_ID 工作表并落地像素（口径同 `GCREATEFROMFILE` 的延迟形态）。 */
    private fun materializedSheet(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(
            buffer.createDeferredFromFile(side, side, "resources/exempt-sheet-$id.webp") {
                IntArray(side * side) { idx -> 0x01000000 or idx }
            },
        )
        buffer.pixels
        return buffer
    }

    // ------------------------------------------------------------------
    // 不可知堆 ⇒ 预算 = Long.MAX_VALUE ⇒ 预算档不生效
    // ------------------------------------------------------------------

    @Test
    fun `堆上限不可知时预算回落 Long MAX 值`() {
        // 三形同一条口径：无上限（0）、负读数、以及 Java 侧「不可知」惯用的哨兵。
        assertEquals(Long.MAX_VALUE, GraphicsPool.defaultPixelBudgetBytes(0L))
        assertEquals(Long.MAX_VALUE, GraphicsPool.defaultPixelBudgetBytes(-1L))
        assertEquals(Long.MAX_VALUE, GraphicsPool.defaultPixelBudgetBytes(Long.MAX_VALUE))
        // 只认**恰好**那个哨兵：MAX-1 是真读数，按 ÷4 算（否则「接近上限」的真机挡位会被静默放开）。
        assertEquals((Long.MAX_VALUE - 1L) / GraphicsPool.PIXEL_BUDGET_DIVISOR, GraphicsPool.defaultPixelBudgetBytes(Long.MAX_VALUE - 1L))
        // 对照：可知堆照旧 ÷4（这一格红了说明上面的 MAX 断言是因为「压根不除」而绿）。
        assertEquals(512L * mib / 4L, GraphicsPool.defaultPixelBudgetBytes(512L * mib))
    }

    @Test
    fun `预算为 Long MAX 值时预算档一个缓冲都不退`() {
        val pool = GraphicsPool()
        val a = materializedSheet(pool, 1)
        val b = materializedSheet(pool, 2)
        assertEquals(sheetBytes * 2, pool.residentBytes())

        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(Long.MAX_VALUE), "不可知堆 ⇒ 判据恒不成立")
        assertEquals(sheetBytes * 2, pool.residentBytes(), "驻留读数一格不动")
        assertEquals(sheetBytes, a.pixelBytes)
        assertEquals(sheetBytes, b.pixelBytes)

        // 对照：同一池、同一批候选，预算收到 0 ⇒ 两张全退。上面那个 0 是预算给的，
        // 不是「压根没候选」（否则本用例改成恒 0 也会绿）。
        assertEquals(sheetPixels * 2, pool.evictDeferredPixelsWithinBudget(0L))
        assertEquals(0L, pool.residentBytes())
    }

    @Test
    fun `预算恰好等于驻留时零动作`() {
        val pool = GraphicsPool()
        val a = materializedSheet(pool, 3)
        materializedSheet(pool, 4)
        assertEquals(sheetBytes * 2, pool.residentBytes())

        // 判停是「≤ 预算就停」（`<=`）：恰好等于预算那一格不许退，也不许多退一张。
        assertEquals(0L, pool.evictDeferredPixelsWithinBudget(sheetBytes * 2))
        assertEquals(sheetBytes * 2, pool.residentBytes(), "恰好等于预算 ⇒ 一像素都不许动")
        assertEquals(sheetBytes, a.pixelBytes)

        // 对照：预算少一个字节 ⇒ 正好退一张（`<` 写成 `<=` 这一格会变成 0）。
        assertEquals(sheetPixels, pool.evictDeferredPixelsWithinBudget(sheetBytes * 2 - 1L))
        assertEquals(sheetBytes, pool.residentBytes())
    }

    @Test
    fun `预算档不可知时连画过的缓冲也不 dispose`() {
        val pool = GraphicsPool()
        val painted = pool.get(5)
        assertTrue(painted.createFromArgb(side, side, IntArray(side * side) { 0x7F000001 }))
        assertEquals(sheetBytes, pool.residentBytes())

        val result = pool.releaseUnused(exclude = emptySet(), minPixels = 1, budgetBytes = Long.MAX_VALUE)

        assertEquals(0, result.buffers, "不可知堆 ⇒ 真 dispose 那一档也不许动手")
        assertEquals(0L, result.bytes)
        assertTrue(painted.isCreated, "画过的那张必须还在（dispose 后脚本手里的 gid 会变未创建）")
        assertEquals(sheetBytes, pool.residentBytes())

        // 对照：预算收到 0 ⇒ 同一张被 dispose（证明上面的 0 是预算给的）。
        assertEquals(1, pool.releaseUnused(emptySet(), 1, budgetBytes = 0L).buffers)
        assertFalse(painted.isCreated)
    }

    // ------------------------------------------------------------------
    // 「从未写过」的哨兵：渲染层的稳定期判据靠它
    // ------------------------------------------------------------------

    @Test
    fun `从未写过像素的缓冲报 Long MAX 值表示已稳定`() {
        val pool = GraphicsPool()
        val fresh = pool.get(6)
        // 渲染层判据是 `msSinceLastWrite() < REBUILD_SETTLE_MS`（16 ms）：哨兵必须是「远大于任何
        // 稳定期」的数，写成 0 就等于「刚写完」⇒ 合成期间的位图重建永远被推迟、画面停在旧帧。
        assertEquals(Long.MAX_VALUE, fresh.msSinceLastWrite(), "从未写过 = 已稳定")

        fresh.create(side, side)
        // `create` 只换一代表面、不推进写代数（它不是一笔「写」）⇒ 读数仍是哨兵。
        assertEquals(Long.MAX_VALUE, fresh.msSinceLastWrite(), "建表本身不是一笔写")
        // 真落一笔写之后必须换成有限读数 —— 渲染层的稳定期判据靠「有限值 < 16 ms」才推迟重建。
        fresh.setPixel(0, 0, 0xFF010203.toInt())
        val afterWrite = fresh.msSinceLastWrite()
        assertTrue(afterWrite < Long.MAX_VALUE, "写过之后必须换成有限读数（否则稳定期判据永远不成立）")
        assertTrue(afterWrite >= 0L, "单调时钟差值不许为负")
    }
}
