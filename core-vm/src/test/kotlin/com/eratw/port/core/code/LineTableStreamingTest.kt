package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * M5-BOOT：行号表的**流式压实**必须与旧的两段式 `IntArray → LineTable.of` 逐条同解。
 *
 * 动机：装载侧原来先建 4.89M 条 `IntArray`（19.6 MiB），再压成 2 B/条；
 * [LineTable.ofStreaming] 直接从段 6 的 `i32` 写紧凑表，省掉那块临时对象。
 * 等价性覆盖三种取值：未知（-1）、直存（0..65533）、溢出（负数 / ≥ 65534）。
 */
class LineTableStreamingTest {

    private fun viaOld(lines: IntArray): LineTable = LineTable.of(lines)

    private fun viaStream(lines: IntArray): LineTable {
        var i = 0
        return LineTable.ofStreaming(lines.size) { lines[i++] }
    }

    @Test
    fun `三种取值与旧压实逐条同解`() {
        val lines = intArrayOf(-1, 0, 1, 65533, 65534, 65535, 70_000, -2, 42, 999_999)
        val old = viaOld(lines)
        val stream = viaStream(lines)
        assertEquals(old.count, stream.count)
        assertEquals(old.bytes, stream.bytes, "溢出表大小也必须一致")
        for (i in lines.indices) assertEquals(old.lineOf(i), stream.lineOf(i), "pc=$i")
        assertContentEquals(old.toIntArray(), stream.toIntArray())
    }

    @Test
    fun `全未知表不分配溢出项`() {
        val stream = viaStream(IntArray(1000) { -1 })
        assertEquals(1000, stream.count)
        assertEquals(3 * 16L + 1000 * 2L, stream.bytes, "只有主表 + 3 个数组头")
        assertEquals(-1, stream.lineOf(0))
        assertEquals(-1, stream.lineOf(999))
        assertEquals(-1, stream.lineOf(-1), "越界回落 -1")
        assertEquals(-1, stream.lineOf(1000))
    }

    @Test
    fun `空表与旧实现同解`() {
        assertEquals(0, viaStream(IntArray(0)).count)
        assertEquals(-1, viaStream(IntArray(0)).lineOf(0))
    }
}
