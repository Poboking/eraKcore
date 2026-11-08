package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MR-1a / ABI-2 契约钉：跨设备导入的 EntityId 重映射（§7）
 * —— 只换 pkg 段、保留 generation/handleSlot、未映射不动、非良构拒绝、映射单射。
 */
class EntityIdRemapTableTest {

    private fun id(pkg: Int, gen: Int, slot: Int): Long = EntityIdCodec.encode(pkg, gen, slot)

    @Test
    fun remapReplacesOnlyPackageSegment() {
        val table = EntityIdRemapTable.of(mapOf(2 to 9))
        val before = id(2, 5, 0x4000)
        val after = table.remap(before)
        assertEquals(9, EntityIdCodec.pkgShortIdOf(after))
        assertEquals(5, EntityIdCodec.generationOf(after))
        assertEquals(0x4000, EntityIdCodec.handleSlotOf(after))
        // 未映射的包原样不动（不猜、不顺手改）。
        val other = id(3, 5, 0x4000)
        assertEquals(other, table.remap(other))
        // NULL 透传。
        assertEquals(EntityIdCodec.NULL, table.remap(EntityIdCodec.NULL))
        // 恒等表 = 无操作。
        assertEquals(before, EntityIdRemapTable.IDENTITY.remap(before))
        assertTrue(EntityIdRemapTable.IDENTITY.isEmpty)
    }

    @Test
    fun remapRejectsMalformedValues() {
        val table = EntityIdRemapTable.of(mapOf(2 to 9))
        // 符号位为 1（负数）：不是 EntityId 形态。
        assertFailsWith<IllegalArgumentException> { table.remap(-1L) }
        // pkg 段为 0 的非 NULL 值：只有 NULL 可以为 0 段。
        assertFailsWith<IllegalArgumentException> { table.remap(1L shl 32) }
    }

    @Test
    fun mappingMustBeInjectiveAndInRange() {
        // 两旧 → 一新 = 合并两个分支的实体集合，v1 不支持。
        assertFailsWith<IllegalArgumentException> {
            EntityIdRemapTable.of(mapOf(2 to 7, 3 to 7))
        }
        // 越域（0 是 NULL 保留；上界外）。
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(0 to 7)) }
        assertFailsWith<IllegalArgumentException> {
            EntityIdRemapTable.of(mapOf(EntityIdCodec.MAX_PKG_SHORT_ID + 1 to 7))
        }
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(2 to 0)) }
        // 内核包短 ID 1 不参与重映射。
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(1 to 7)) }
        assertFailsWith<IllegalArgumentException> { EntityIdRemapTable.of(mapOf(2 to 1)) }
        // 恒等项允许（语义是无操作）。
        val identityEntry = EntityIdRemapTable.of(mapOf(5 to 5))
        assertEquals(5, EntityIdCodec.pkgShortIdOf(identityEntry.remap(id(5, 1, 1))))
    }

    @Test
    fun mappingsAreDefensiveCopy() {
        val source = HashMap<Int, Int>()
        source[2] = 9
        val table = EntityIdRemapTable.of(source)
        source[2] = 10
        source[3] = 11
        assertEquals(mapOf(2 to 9), table.mappings)
        assertEquals(9, table.targetShortIdOf(2))
        assertNull(table.targetShortIdOf(3))
    }
}
