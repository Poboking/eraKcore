package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FlatPoolBudget] 的纯判式用例：堆挡位 × 放开档两维的边界、常量自洽、[FlatPoolBudget.DISABLED] 形态。
 *
 * 判据三条：
 * 1. 堆 < 512 MiB ⇒ [FlatPoolBudget.DISABLED]（片数 0、两个字节读数 0），512 MiB 整是开关点
 *    （**含** 512：判式是 `<` 不是 `<=`）；
 * 2. 片数只两档 32 / 64（保守 / 放开），且放开档的收益只体现在**片缓存份额**
 *    （[FlatPoolBudget.residentBytes] = 片数 × 1 MiB）；
 * 3. [FlatPoolBudget.reserveBytes] 恒等于实测增量 114 MiB、**不随片数缩放**
 *    （`SegmentedFlatStmt.kt:52-61`：32 片 / 64 片 / 1,024 条每片三组读数同量级 ⇒ 增量大头是
 *    执行路径固定开销，调参无效；按片数去缩这笔预留就是少报）。
 */
class FlatPoolBudgetTest {

    private val mib = 1024L * 1024L

    /** 判式的两条边：256 / 511 一律 DISABLED，512 / 768 起按档位给片数。 */
    @Test
    fun `堆挡位边界：256 与 511 MiB 关闭，512 与 768 MiB 按档位开`() {
        val heaps = listOf(256L, 511L, 512L, 768L).map { it * mib }
        for (heap in heaps) {
            for (generous in listOf(false, true)) {
                val budget = FlatPoolBudget.forHeap(heap, generous)
                val label = "${heap / mib} MiB generous=$generous"
                if (heap < FlatPoolBudget.MIN_HEAP_BYTES) {
                    assertEquals(FlatPoolBudget.DISABLED, budget, "$label 必须原样给 DISABLED（含字节读数 0）")
                    assertFalse(budget.enabled, "$label 不许 enabled")
                    assertEquals(0, budget.maxSegments, "$label 片数必须 0")
                    assertEquals(0L, budget.residentBytes, "$label 片缓存份额必须 0")
                    assertEquals(0L, budget.reserveBytes, "$label 预留必须 0（池不会被建）")
                } else {
                    assertTrue(budget.enabled, "$label 必须 enabled")
                    assertEquals(FlatPoolBudget.SEGMENT_STATEMENTS, budget.segmentStatements, "$label 片容量")
                    val allowed = if (generous) {
                        FlatPoolBudget.GENEROUS_MAX_SEGMENTS
                    } else {
                        FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS
                    }
                    assertEquals(allowed, budget.maxSegments, "$label 片数只两档")
                    assertEquals(
                        budget.maxSegments * FlatPoolBudget.SEGMENT_ESTIMATED_BYTES,
                        budget.residentBytes,
                        "$label resident = 片数 × 1 MiB",
                    )
                    assertEquals(FlatPoolBudget.MEASURED_INCREMENT_BYTES, budget.reserveBytes, "$label 预留恒 114 MiB")
                }
            }
        }
    }

    /** 开关点两侧各差一字节：判式是 `<`，恰好 512 MiB 必须开。 */
    @Test
    fun `开关点差一字节：512 MiB 整开、减一字节关`() {
        val at = FlatPoolBudget.forHeap(FlatPoolBudget.MIN_HEAP_BYTES, generous = false)
        val below = FlatPoolBudget.forHeap(FlatPoolBudget.MIN_HEAP_BYTES - 1L, generous = false)
        assertTrue(at.enabled, "恰好 512 MiB 必须开（判式 < 而不是 <=）")
        assertFalse(below.enabled, "511.999… MiB（512 MiB − 1 B）必须关")
        assertEquals(FlatPoolBudget.DISABLED, below)
        assertEquals(FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS, at.maxSegments)
    }

    /** 常量自洽：三条读数与产品护栏同源（512 MiB 挡的护栏在 ProgramLoader 的「两者取大」一节）。 */
    @Test
    fun `常量自洽：片容量 4096、片估 1 MiB、增量 114 MiB、下限 512 MiB`() {
        assertEquals(4096, FlatPoolBudget.SEGMENT_STATEMENTS)
        assertEquals(1L * mib, FlatPoolBudget.SEGMENT_ESTIMATED_BYTES)
        assertEquals(114L * mib, FlatPoolBudget.MEASURED_INCREMENT_BYTES)
        assertEquals(512L * mib, FlatPoolBudget.MIN_HEAP_BYTES)
        assertEquals(32, FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS)
        assertEquals(64, FlatPoolBudget.GENEROUS_MAX_SEGMENTS)
        // 放开档只是「多 32 片缓存份额」，预留一分不加 —— 这条就是「调参无效」的账面形态。
        val conservative = FlatPoolBudget.forHeap(FlatPoolBudget.MIN_HEAP_BYTES, generous = false)
        val generous = FlatPoolBudget.forHeap(FlatPoolBudget.MIN_HEAP_BYTES, generous = true)
        assertEquals(conservative.reserveBytes, generous.reserveBytes, "预留不随片数缩放")
        assertEquals(
            FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS * FlatPoolBudget.SEGMENT_ESTIMATED_BYTES,
            conservative.residentBytes,
        )
        assertEquals(
            FlatPoolBudget.GENEROUS_MAX_SEGMENTS * FlatPoolBudget.SEGMENT_ESTIMATED_BYTES,
            generous.residentBytes,
        )
        assertEquals(
            (FlatPoolBudget.GENEROUS_MAX_SEGMENTS - FlatPoolBudget.CONSERVATIVE_MAX_SEGMENTS) * mib,
            generous.residentBytes - conservative.residentBytes,
            "放开档与保守档的差额只在片缓存份额上（32 MiB）",
        )
        // 片缓存份额（≤64 MiB）远小于实测增量预留（114 MiB）：两笔读数不许互相顶替。
        assertTrue(
            generous.residentBytes < generous.reserveBytes,
            "resident 是片缓存份额、reserve 是总增量上界，占比 ${generous.residentBytes} vs ${generous.reserveBytes}",
        )
    }

    /** [FlatPoolBudget.DISABLED] 的形态：片数 0、两笔读数 0、enabled 为假。 */
    @Test
    fun `DISABLED 形态：片数 0 两个字节读数 0`() {
        assertEquals(0, FlatPoolBudget.DISABLED.maxSegments)
        assertEquals(0L, FlatPoolBudget.DISABLED.residentBytes)
        assertEquals(0L, FlatPoolBudget.DISABLED.reserveBytes)
        assertFalse(FlatPoolBudget.DISABLED.enabled)
        // 片容量照旧带着（降级到 1 片时 Program 还要按它切片），不是 0。
        assertEquals(FlatPoolBudget.SEGMENT_STATEMENTS, FlatPoolBudget.DISABLED.segmentStatements)
        // 判式是多处喂同一个 DISABLED 单例 ⇒ 用等值而不只是同值比对，防止有人改成每次新建却漂移。
        assertEquals(FlatPoolBudget.DISABLED, FlatPoolBudget.forHeap(0L, generous = true))
        assertEquals(FlatPoolBudget.DISABLED, FlatPoolBudget.forHeap(Byte.MAX_VALUE.toLong(), generous = true))
    }
}
