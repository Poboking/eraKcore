package com.eratw.port.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 节点偏移紧凑表（MEM10 刀 G）= 「块基 u32 + 块内 u16 相对偏移」的往返与退化判据。
 *
 * ## 钉住什么
 *
 * 1. **往返**：任意单调偏移序列经 [NodeOffsets.Builder] 压缩后，[NodeOffsets.get] 逐条还原
 *    与输入逐字节相同（块边界 511/512/513 与尾块都要覆盖）。
 * 2. **退化**：块内跨度过 65535 时该块必须走 `overflow` 的绝对偏移表，取值仍正确
 *    （真语料 0 块，但判据不能只靠语料）。
 * 3. **装配纪律**：非单调输入当场抛错（边界扫描本来就是升序，跳变说明扫描器坏了）。
 */
class NodeOffsetsTest {

    private fun roundTrip(offsets: IntArray) {
        val b = NodeOffsets.Builder(offsets.size)
        for (o in offsets) b.add(o)
        val table = b.build()
        assertEquals(offsets.size, table.size)
        for (i in offsets.indices) assertEquals(offsets[i], table.get(i), "第 $i 条")
    }

    @Test
    fun `往返：块边界与尾块`() {
        for (n in intArrayOf(0, 1, 511, 512, 513, 1_024, 1_025)) {
            val offsets = IntArray(n) { it * 27 + (it % 7) }
            roundTrip(offsets)
        }
    }

    @Test
    fun `往返：平均块跨度贴近 u16 上限`() {
        // 每块 512 条、每条 127 B ⇒ 块跨度 ≈ 65,024 < 65,535：全部块走 u16 腿。
        val n = 4_096
        roundTrip(IntArray(n) { it * 127 })
    }

    @Test
    fun `退化：块跨度过 65535 的块走绝对偏移表`() {
        val n = 1_536
        val offsets = IntArray(n) { i ->
            when {
                i < 600 -> i * 20
                // 第 1 块（512..1023）里出现一次大跳，跨度必然越 u16
                i < 700 -> 600 * 20 + (i - 599) * 100_000
                else -> 600 * 20 + 100 * 100_000 + (i - 699) * 20
            }
        }
        roundTrip(offsets)
    }

    @Test
    fun `适配：稠密 IntArray 的 of 与 Builder 逐条相同`() {
        val src = IntArray(3_000) { it * 31 }
        val table = NodeOffsets.of(src)
        assertEquals(src.size, table.size)
        for (i in src.indices) assertEquals(src[i], table.get(i))
    }

    @Test
    fun `装配纪律：非单调输入当场抛错`() {
        val b = NodeOffsets.Builder(4)
        b.add(10)
        b.add(20)
        assertFailsWith<IllegalStateException> { b.add(15) }
    }

    @Test
    fun `装配纪律：条数不足与超出都抛错`() {
        val short = NodeOffsets.Builder(3)
        short.add(0)
        short.add(8)
        assertFailsWith<IllegalStateException> { short.build() }

        val over = NodeOffsets.Builder(1)
        over.add(0)
        assertFailsWith<IllegalStateException> { over.add(4) }
    }
}
