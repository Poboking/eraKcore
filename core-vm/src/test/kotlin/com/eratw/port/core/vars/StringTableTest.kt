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
}
