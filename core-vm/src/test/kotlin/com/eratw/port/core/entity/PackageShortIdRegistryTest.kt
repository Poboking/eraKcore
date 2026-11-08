package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ABI-2 注册表契约钉（era-arch-v61u.md §7/§10）：0/1 保留、单调分配、
 * 孤儿锁定、并集合并与双向冲突拒绝、二进制往返与损坏拒绝。
 */
class PackageShortIdRegistryTest {

    @Test
    fun corePreRegisteredAndZeroReserved() {
        val reg = PackageShortIdRegistry()
        assertEquals(1, reg.shortIdOf(PackageShortIdRegistry.CORE_PACKAGE_ID))
        assertEquals(PackageShortIdRegistry.CORE_PACKAGE_ID, reg.packageIdOf(1))
        assertNull(reg.packageIdOf(0)) // 0 = NULL 保留，永不分配
        assertEquals(2, reg.allocate("org.era.first"))
        assertEquals(3, reg.allocate("org.era.second"))
    }

    @Test
    fun allocateIsIdempotentAndMonotonic() {
        val reg = PackageShortIdRegistry()
        val a = reg.allocate("a")
        val b = reg.allocate("b")
        assertEquals(a, reg.allocate("a")) // 幂等
        assertEquals(b, reg.allocate("b"))
        assertTrue(b > a)
        assertEquals("a", reg.packageIdOf(a))
        assertEquals("b", reg.packageIdOf(b))
        assertFailsWith<IllegalArgumentException> { reg.allocate("") }
    }

    @Test
    fun lockRequiresRegistration() {
        val reg = PackageShortIdRegistry()
        val id = reg.allocate("orphan.pkg")
        assertFalse(reg.isLocked(id))
        reg.lock(id)
        assertTrue(reg.isLocked(id))
        assertFailsWith<IllegalArgumentException> { reg.lock(999) }
    }

    @Test
    fun encodeDecodeRoundtripPreservesLocksAndWaterline() {
        val reg = PackageShortIdRegistry()
        val a = reg.allocate("pkg.a")
        reg.allocate("pkg.b")
        reg.lock(a)
        val restored = PackageShortIdRegistry.decode(reg.encode())
        assertEquals(reg.count, restored.count)
        assertEquals(a, restored.shortIdOf("pkg.a"))
        assertEquals(2 + 2, restored.allocate("pkg.c")) // 水位线延续，不撞已分配
        assertTrue(restored.isLocked(a))
        assertEquals(1, restored.shortIdOf(PackageShortIdRegistry.CORE_PACKAGE_ID))
    }

    @Test
    fun mergeIsUnionWithIdempotentOverlap() {
        // 真实形态：sidecar 快照派生自同一全局注册表（共享分配史），再增新条目。
        val left = PackageShortIdRegistry()
        left.allocate("shared") // 2
        left.allocate("left.only") // 3
        val right = PackageShortIdRegistry.decode(left.encode())
        right.allocate("right.only") // 4
        right.lock(4)

        left.mergeSnapshot(right.encode())
        assertEquals(4, left.count) // core + shared + left.only + right.only
        assertEquals(2, left.shortIdOf("shared"))
        assertEquals(4, left.shortIdOf("right.only"))
        assertTrue(left.isLocked(4))
        assertEquals(5, left.allocate("next")) // 水位取双方最大
    }

    @Test
    fun mergeRejectsBothConflictDirections() {
        // 同包异 ID：右树先占 2 号给别的包，再给 shared 分 3 号。
        val left = PackageShortIdRegistry()
        left.allocate("shared") // 2
        val right = PackageShortIdRegistry()
        right.allocate("blocker") // 2
        right.allocate("shared") // 3
        assertFailsWith<RegistryConflictException> { left.mergeSnapshot(right.encode()) }

        // 同 ID 异包：左 2=aaa，右 2=bbb。
        val l2 = PackageShortIdRegistry()
        l2.allocate("aaa")
        val r2 = PackageShortIdRegistry()
        r2.allocate("bbb")
        assertFailsWith<RegistryConflictException> { l2.mergeSnapshot(r2.encode()) }
    }

    @Test
    fun decodeRejectsCorruption() {
        val reg = PackageShortIdRegistry()
        reg.allocate("x")
        val good = reg.encode()
        assertFailsWith<IllegalArgumentException> { PackageShortIdRegistry.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PackageShortIdRegistry.decode(good.copyOfRange(0, 8)) }
        val badMagic = good.copyOf()
        badMagic[0] = 0
        assertFailsWith<IllegalArgumentException> { PackageShortIdRegistry.decode(badMagic) }
        val badVersion = good.copyOf()
        badVersion[5] = 99
        assertFailsWith<IllegalArgumentException> { PackageShortIdRegistry.decode(badVersion) }
        val trailing = good + byteArrayOf(0)
        assertFailsWith<IllegalArgumentException> { PackageShortIdRegistry.decode(trailing) }
    }
}