package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 字符串驻留池：去重、空串哨兵、重置、越界。 */
class StringTableTest {

    @Test
    fun `下标 0 固定为空串`() {
        val pool = StringTable()
        assertEquals("", pool.lookup(0))
        assertEquals(1, pool.distinctCount)
        assertEquals(0, pool.intern(""))
    }

    @Test
    fun `相同内容只占一个槽位`() {
        val pool = StringTable()
        val a = pool.intern("フラン")
        val b = pool.intern("フラン")
        val c = pool.intern("フランドール")
        assertEquals(a, b)
        assertEquals(a + 1, c)
        assertEquals(3, pool.distinctCount) // "" + 两个不同串
        assertEquals("フラン", pool.lookup(a))
    }

    @Test
    fun `idOf 对未驻留串返回 null`() {
        val pool = StringTable()
        pool.intern("x")
        assertEquals(1, pool.idOf("x"))
        assertNull(pool.idOf("y"))
    }

    @Test
    fun `clear 后回到只剩空串`() {
        val pool = StringTable()
        pool.intern("a")
        pool.intern("b")
        pool.clear()
        assertEquals(1, pool.distinctCount)
        assertEquals(1, pool.intern("a")) // "" 固定占 0
    }

    @Test
    fun `越界下标抛 CodeEE`() {
        val pool = StringTable()
        assertFailsWith<CodeEE> { pool.lookup(-1) }
        assertFailsWith<CodeEE> { pool.lookup(99) }
    }

    // ---------------- compact（复位链的串表半边，契约见 StringTable.compact 的三条） ----------------

    @Test
    fun `compact 保留项逐字不变且按旧下标升序重编号`() {
        val pool = StringTable()
        val ids = IntArray(6) { pool.intern("s$it") } // 1..5（0 恒空串）
        val live = pool.newLiveMask()
        live[ids[2]] = true
        live[ids[4]] = true
        val snapshot = listOf(pool.lookup(ids[2]), pool.lookup(ids[4]))
        val remap = pool.compact(live)

        assertEquals(1, remap[ids[2]], "首个保留项接在空串之后")
        assertEquals(2, remap[ids[4]], "第二个保留项按旧序编号（升序 = 确定函数，不看哈希序）")
        assertEquals(snapshot[0], pool.lookup(remap[ids[2]]), "逐字不变：契约 1")
        assertEquals(snapshot[1], pool.lookup(remap[ids[4]]), "逐字不变：契约 1")
        assertEquals("", pool.lookup(0), "0 恒为空串且恒映到 0")
        assertEquals(3, pool.distinctCount, "只留空串 + 两条保留项")
        assertEquals(ids.size + 1, remap.size, "remap 长度 = 压实前的条数")
    }

    @Test
    fun `compact 未保留的旧下标映到 -1 读到即响`() {
        val pool = StringTable()
        val a = pool.intern("留着")
        val b = pool.intern("丢弃")
        val live = pool.newLiveMask()
        live[a] = true
        val remap = pool.compact(live)

        assertEquals(-1, remap[b], "漏登记的持有者读到的是越界，不是另一条串的静默错值（契约 3）")
        assertFailsWith<CodeEE> { pool.lookup(remap[b]) }
        assertNull(pool.idOf("丢弃"), "未保留的串本体连反查项一起归还")
        assertEquals(a, remap[a], "只留空串 + 一条 ⇒ 该项恰好回到原位")
    }

    @Test
    fun `compact 全存活时恒等映射且不重建表`() {
        val pool = StringTable()
        val a = pool.intern("甲")
        val b = pool.intern("乙")
        val live = pool.newLiveMask()
        for (i in 1 until pool.distinctCount) live[i] = true
        val remap = pool.compact(live)

        assertEquals(a, remap[a], "全存活 = 恒等映射")
        assertEquals(b, remap[b], "全存活 = 恒等映射")
        assertEquals(3, pool.distinctCount, "零收益的压实不付重建成本")
        assertEquals(b, pool.idOf("乙"), "表没换，旧的反查项仍在原位")
    }

    @Test
    fun `compact 后新 intern 接在保留项之后`() {
        val pool = StringTable()
        repeat(50) { pool.intern("junk$it") }
        val keep = pool.intern("GLOBALS 槽里的串")   // 压实前的旧下标
        val live = pool.newLiveMask()
        live[keep] = true
        val remap = pool.compact(live)

        assertEquals(1, remap[keep], "保留项重编号后是小下标（持有者要改写的就是这一格）")
        assertEquals("GLOBALS 槽里的串", pool.lookup(remap[keep]), "逐字不变：契约 1")
        assertEquals(2, pool.intern("复位后再写的串"), "新串接在存活项之后，不复用被丢弃的旧位")
        assertEquals(3, pool.distinctCount)
        assertEquals(2, pool.intern("复位后再写的串"), "驻留去重在压实后照旧成立")
    }
}
