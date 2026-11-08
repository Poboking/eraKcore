package com.eratw.port.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 相位阶梯的**账面算术**自证（对齐 `HeapRootLedgerTest` 的位置：算式钉在这儿，探针本体只采数）。
 *
 * 断言的是判读口径本身，不是任何基准字节数：
 * - 差分按分面键对齐、缺键按 0（跨相位可比的前提）；
 * - 边际尺 7,933 B/条（`HeapRootAttributionProbe` 的等距抽样实测，落在 [MARGINAL_STMT_BYTES]）
 *   的正反算；
 * - 「单调不回吐」的两半（每步不越容差回落 + 累计过容差）各自都能单独否决；
 * - 单位数为 0 一律给 0（无定义不硬造数，这是交付纪律不是数学）。
 */
class PhaseHeapLadderTest {

    private val mib = 1024L * 1024L

    @Test
    fun `分面差分按键对齐且缺键按零`() {
        val prev = mapOf("8 控制台行表" to 10L * mib, "4 语句对象树(骨架+物化)" to 5L * mib)
        val cur = mapOf("8 控制台行表" to 14L * mib, "9 图面(gfx/sprite/cbg)" to 2L * mib)
        val d = PhaseLadderMath.facetDiff(prev, cur)
        assertEquals(4L * mib, d["8 控制台行表"])
        assertEquals(-5L * mib, d["4 语句对象树(骨架+物化)"], "上一相位有、这一相位没有 = 负增量（回吐），不许抹成 0")
        assertEquals(2L * mib, d["9 图面(gfx/sprite/cbg)"])
        assertEquals(3, d.size, "并集键，不是交集")
    }

    @Test
    fun `边际尺正反算与无定义档`() {
        assertEquals(1_000L, PhaseLadderMath.stmtCountFromBytes(1_000L * MARGINAL_STMT_BYTES, MARGINAL_STMT_BYTES))
        assertEquals(7_933L, MARGINAL_STMT_BYTES, "尺的钉点：上一路抽样实测 每条物化语句 7,933 B")
        assertEquals(0L, PhaseLadderMath.stmtCountFromBytes(12345L, 0L), "尺为 0 = 无定义，给 0 而不是抛")
        assertEquals(0L, PhaseLadderMath.stmtCountFromBytes(12345L, -1L))
    }

    @Test
    fun `每单位净增在单位数为零时给零`() {
        assertEquals(2L * mib, PhaseLadderMath.perUnitGrowth(10L * mib, 26L * mib, 8))
        assertEquals(-1L * mib, PhaseLadderMath.perUnitGrowth(10L * mib, 8L * mib, 2), "回吐也给负数，不改号")
        assertEquals(0L, PhaseLadderMath.perUnitGrowth(10L * mib, 20L * mib, 0))
        assertEquals(0L, PhaseLadderMath.perUnitGrowth(10L * mib, 20L * mib, -3))
    }

    @Test
    fun `单调不回吐的判据两半各能否决`() {
        val mono = listOf(69L * mib, 74L * mib, 80L * mib, 85L * mib)
        assertTrue(PhaseLadderMath.isMonotoneGrowth(mono, 2 * mib))

        val rollback = listOf(69L * mib, 74L * mib, 68L * mib, 80L * mib)
        assertFalse(PhaseLadderMath.isMonotoneGrowth(rollback, 2 * mib), "中途一次 3 MiB 级回落 ⇒ 不是单调不回吐")

        val noise = listOf(70L * mib, 70L * mib + mib, 70L * mib + 2 * mib)
        assertFalse(PhaseLadderMath.isMonotoneGrowth(noise, 2 * mib), "累计没越过容差 ⇒ 只当噪声，不当增长")

        assertFalse(PhaseLadderMath.isMonotoneGrowth(listOf(1L, 2L), 2 * mib), "两点定不了单调")
        assertFalse(PhaseLadderMath.isMonotoneGrowth(emptyList(), 2 * mib))
    }

    @Test
    fun `逐点增量首点补零`() {
        val s = listOf(10L, 13L, 11L, 20L)
        assertEquals(listOf(0L, 3L, -2L, 9L), PhaseLadderMath.stepDeltas(s))
        assertEquals(emptyList<Long>(), PhaseLadderMath.stepDeltas(emptyList()))
    }

    @Test
    fun `排行取前三与越界收敛`() {
        val totals = mapOf("A" to 5L, "B" to 9L, "C" to 1L, "D" to 3L)
        assertEquals(listOf("B" to 9L, "A" to 5L, "D" to 3L), PhaseLadderMath.topSurfaces(totals, 3))
        assertEquals(4, PhaseLadderMath.topSurfaces(totals, 99).size, "n 越界按表长收，不抛")
        assertEquals(0, PhaseLadderMath.topSurfaces(totals, -1).size)
        assertEquals(listOf("B" to 9L), PhaseLadderMath.topDiffs(mapOf("B" to 9L), 3))
    }

    @Test
    fun `分面键表与探针出行顺序同源`() {
        // ORDER 少一个键 ⇒ 差分表会漏面（跨相位对齐靠行键，键名漂了就是静默漏账）
        assertEquals(13, PhaseSurface.ORDER.size)
        assertTrue(PhaseSurface.ORDER.contains(PhaseSurface.CONSOLE))
        assertTrue(PhaseSurface.ORDER.contains(PhaseSurface.STATEMENTS))
        assertTrue(PhaseSurface.ORDER.contains(PhaseSurface.GRAPHICS))
        assertTrue(PhaseSurface.ORDER.contains(PhaseSurface.RENDER), "渲染侧那一面必须出现在表里（哪怕只声明不入账）")
        assertEquals(PhaseSurface.ORDER.size, PhaseSurface.ORDER.toSet().size, "键不许重复")
    }
}
