package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ABI-1 位域契约钉（era-arch-v61u.md §7/§10）：三段编码/解码逐位往返、
 * 边界值、NULL 唯一性、越域拒绝、全位域同一性判据。
 */
class EntityIdCodecTest {

    @Test
    fun roundtripAcrossFieldBoundaries() {
        val cases = listOf(
            Triple(1, 0, 0),
            Triple(1, 0, 1),
            Triple(32767, 65535, -1), // 三段全上界；handleSlot -1 = 无符号 0xFFFFFFFF
            Triple(2, 1, Int.MAX_VALUE),
            Triple(16384, 32768, Int.MIN_VALUE),
        )
        for ((pkg, gen, slot) in cases) {
            val id = EntityIdCodec.encode(pkg, gen, slot)
            assertTrue(id > 0, "符号位必须恒 0: $id")
            assertEquals(pkg, EntityIdCodec.pkgShortIdOf(id))
            assertEquals(gen, EntityIdCodec.generationOf(id))
            assertEquals(slot, EntityIdCodec.handleSlotOf(id))
            assertTrue(EntityIdCodec.isWellFormed(id))
            assertTrue(EntityIdCodec.matches(id, pkg, gen, slot))
        }
    }

    @Test
    fun bitLayoutIsFrozen() {
        // 逐位布局钉：pkg 在 48..62、gen 在 32..47、slot 在 0..31。
        assertEquals(1L shl 48, EntityIdCodec.encode(1, 0, 0))
        assertEquals(1L shl 32, EntityIdCodec.encode(1, 1, 0) - (1L shl 48))
        assertEquals(0x7FFFL shl 48, EntityIdCodec.encode(32767, 0, 0))
        assertEquals(0xFFFFL shl 32, EntityIdCodec.encode(1, 65535, 0) - (1L shl 48))
        assertEquals(0xFFFFFFFFL, EntityIdCodec.encode(1, 0, -1) - (1L shl 48))
    }

    @Test
    fun nullIsUniqueAndNeverMatches() {
        assertTrue(EntityIdCodec.isNull(EntityIdCodec.NULL))
        assertFalse(EntityIdCodec.isWellFormed(EntityIdCodec.NULL))
        assertEquals(0, EntityIdCodec.pkgShortIdOf(EntityIdCodec.NULL))
        assertFalse(EntityIdCodec.matches(EntityIdCodec.NULL, 1, 0, 0))
        // pkg=0 编码一律拒绝 ⇒ 全零是唯一合法空值。
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(0, 5, 9) }
    }

    @Test
    fun outOfRangeRejectedNotTruncated() {
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(32768, 0, 0) }
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(1, 65536, 0) }
        assertFailsWith<IllegalArgumentException> { EntityIdCodec.encode(1, -1, 0) }
    }

    @Test
    fun matchesRequiresAllThreeFields() {
        val id = EntityIdCodec.encode(7, 3, 42)
        assertFalse(EntityIdCodec.matches(id, 8, 3, 42)) // pkg 段异
        assertFalse(EntityIdCodec.matches(id, 7, 4, 42)) // generation 异（槽位复用判据）
        assertFalse(EntityIdCodec.matches(id, 7, 3, 43)) // slot 异
        assertFalse(EntityIdCodec.matches(id, 0, 3, 42)) // 越域三段不匹配
        assertFalse(EntityIdCodec.matches(id, 7, 65536, 42))
    }

    @Test
    fun negativeIdIsNotWellFormed() {
        // 符号位被置 1 的 Long 不是合法 EntityId（ERB Long 语义下应为非负）。
        assertFalse(EntityIdCodec.isWellFormed(-1L))
        assertFalse(EntityIdCodec.isWellFormed(Long.MIN_VALUE))
    }
}