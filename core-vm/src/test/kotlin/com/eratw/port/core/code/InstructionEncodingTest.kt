package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 指令编码：定长 4×u32、操作数寻址模式、池化。
 * 约束来源：ADR-004（定长 4×u32 + i64 + 字符串驻留）。
 */
class InstructionEncodingTest {

    @Test
    fun `一条指令占四个 u32 字`() {
        val ins = Instruction(Opcode._SET_INT, a = 3, b = 7, c = 9)
        val buf = IntArray(Instruction.WORDS * 2)
        ins.encodeTo(buf, 0)
        ins.encodeTo(buf, Instruction.WORDS)

        assertEquals(Opcode._SET_INT, buf[0])
        assertEquals(3, buf[1])
        assertEquals(7, buf[2])
        assertEquals(9, buf[3])
        assertEquals(8, buf.size)
        assertEquals(ins, Instruction.decodeFrom(buf, Instruction.WORDS))
    }

    @Test
    fun `负跳转偏移往返不丢位`() {
        val ins = Instruction(Opcode._JUMP, a = -12345)
        val buf = IntArray(Instruction.WORDS)
        ins.encodeTo(buf, 0)
        assertEquals(-12345, Instruction.decodeFrom(buf, 0).a)
    }

    @Test
    fun `全局槽用 bit31 编码且与局部槽不混淆`() {
        val g = SlotAddr.global(3)
        val r = SlotAddr.local(3)

        assertNotEquals(g, r)
        assertTrue(SlotAddr.isGlobal(g))
        assertTrue(!SlotAddr.isGlobal(r))
        assertEquals(3, SlotAddr.index(g))
        assertEquals("G3", SlotAddr.format(g))
        assertEquals("R3", SlotAddr.format(r))
        assertEquals("none", SlotAddr.format(SlotAddr.NONE))
    }

    @Test
    fun `局部槽不接受负数而全局槽拒绝越界`() {
        assertFailsWith<IllegalArgumentException> { SlotAddr.local(-1) }
        assertFailsWith<IllegalArgumentException> { SlotAddr.global(Int.MIN_VALUE) }
    }

    @Test
    fun `i64 立即数走常量池且去重`() {
        val b = CodeBuilder()
        val i0 = b.constant(42L)
        val i1 = b.constant(42L)
        val i2 = b.constant(Long.MIN_VALUE)
        val i3 = b.constant(Long.MAX_VALUE)

        assertEquals(i0, i1)
        assertNotEquals(i0, i2)
        assertNotEquals(i2, i3)

        b.emit(Opcode._SET_INT, 0, i0, line = 1)
        b.emit(Opcode._SET_INT, 1, i2, line = 2)
        b.emit(Opcode._SET_INT, 2, i3, line = 3)
        b.declareFunction("main", 0, 0, 3)
        val p = b.build(0)

        assertEquals(Long.MIN_VALUE, p.constant(p.b(1)))
        assertEquals(Long.MAX_VALUE, p.constant(p.b(2)))
    }

    @Test
    fun `字符串池驻留同一实例`() {
        val b = CodeBuilder()
        val s0 = b.string("ハロー")
        val s1 = b.string("ハロー")
        val s2 = b.string("hello")
        assertEquals(s0, s1)
        assertNotEquals(s0, s2)

        b.emit(Opcode._SET_STR, 0, s0, line = 1)
        b.declareFunction("main", 0, 0, 1)
        val p = b.build(0)
        assertEquals("ハロー", p.string(p.b(0)))
    }

    @Test
    fun `行号表与指令一一对应`() {
        val p = Assembler.assemble(
            """
            _NOP
            _HALT
            """.trimIndent(),
        )
        assertEquals(2, p.instructionCount)
        assertEquals(1, p.lineOf(0))
        assertEquals(2, p.lineOf(1))
    }
}
