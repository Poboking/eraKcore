package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MR-2a-1 / D 契约钉（era-arch-v61u.md §16 主方案 D）：
 * 虚拟 NO 分配（单调、永不复用、避让原生 CSV 编号）+ 持久化快照 fail-closed 恢复 +
 * 角色下标唯一解析入口（无 Mod 旁路逐位等价、投射/未投射/悬空三态）。
 */
class VirtualCharaNoRegistryTest {

    private fun id(pkg: Int, gen: Int, slot: Int): Long = EntityIdCodec.encode(pkg, gen, slot)

    @Test
    fun allocationStartsAtBaseAndIsMonotonic() {
        val reg = VirtualCharaNoRegistry()
        val a = id(1, 0, 1)
        val b = id(1, 0, 2)
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE, reg.allocate(a))
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1, reg.allocate(b))
        // 同一实体幂等：投射重建不换身份。
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE, reg.allocate(a))
        assertEquals(2, reg.size)
        assertEquals(a, reg.entityOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE))
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1, reg.virtualNoOf(b))
    }

    @Test
    fun retiredNumbersAreNeverReused() {
        val reg = VirtualCharaNoRegistry()
        val a = id(1, 0, 1)
        val b = id(1, 0, 2)
        val noA = reg.allocate(a)
        assertEquals(noA, reg.retire(a))
        assertNull(reg.virtualNoOf(a))
        assertNull(reg.entityOf(noA))
        val noB = reg.allocate(b)
        // 号退休而非回收：新实体必须拿到下一个号。
        assertTrue(noB > noA, "退休号被复用：$noA → $noB")
        assertEquals(noA + 1, noB)
        assertNull(reg.retire(a), "没有号的实体 retire 返回 null")
    }

    @Test
    fun allocationSkipsNativeCsvNumbers() {
        // 10000/10001 是「现存 CSV 编号」（构造一个极端语料验证 fail-closed 避让）。
        val natives = setOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE, VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1)
        val reg = VirtualCharaNoRegistry { it in natives }
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 2, reg.allocate(id(1, 0, 1)))
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 3, reg.allocate(id(1, 0, 2)))
        assertFailsWith<IllegalArgumentException> { reg.allocate(0L) }
    }

    @Test
    fun snapshotRestoreKeepsRetiredFrontier() {
        val reg = VirtualCharaNoRegistry()
        val a = id(2, 3, 7)
        val b = id(2, 3, 8)
        val noA = reg.allocate(a)
        reg.allocate(b)
        reg.retire(a) // a 的号退休；恢复后仍不得复用
        val restored = VirtualCharaNoRegistry.restore(reg.snapshot())
        val c = id(2, 4, 9)
        val noC = restored.allocate(c)
        assertTrue(noC > noA + 1, "恢复后复用了退休号：$noC")
        assertEquals(b, restored.entityOf(noA + 1))
        assertEquals(noC, restored.allocate(c))
    }

    @Test
    fun restoreRejectsInconsistentSnapshots() {
        val base = VirtualCharaNoRegistry.VIRTUAL_NO_BASE
        // 号越界
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.restore(
                VirtualCharaNoRegistry.Snapshot(mapOf(base - 1 to id(1, 0, 1)), base),
            )
        }
        // NULL 实体
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.restore(
                VirtualCharaNoRegistry.Snapshot(mapOf(base to 0L), base + 1),
            )
        }
        // 一号多实体（同一实体两个号）
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.restore(
                VirtualCharaNoRegistry.Snapshot(mapOf(base to id(1, 0, 1), base + 1 to id(1, 0, 1)), base + 2),
            )
        }
        // 续发游标会复用已用的号
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.restore(
                VirtualCharaNoRegistry.Snapshot(mapOf(base + 5 to id(1, 0, 1)), base + 5),
            )
        }
        // 与原生 CSV 编号冲突
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.restore(
                VirtualCharaNoRegistry.Snapshot(mapOf(base to id(1, 0, 1)), base + 1),
                isNativeCsvNo = { it == base },
            )
        }
    }

    @Test
    fun resolverPassesNativeIndicesThrough() {
        val resolver = VirtualCharaIndexResolver(VirtualCharaNoRegistry()) { -1 }
        for (row in 0 until 10) {
            assertEquals(row, resolver.resolve(row.toLong(), 10))
        }
        // 最大合法原生下标恰好是 charaNum - 1。
        assertEquals(9, resolver.resolve(9L, 10))
    }

    @Test
    fun emptyRegistryIsExactBypass() {
        // 无 Mod：v >= CHARANUM 一律 OoR，与不做间址时逐位一致；负数与超大值同样 OoR。
        val resolver = VirtualCharaIndexResolver(VirtualCharaNoRegistry()) { -1 }
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(10L, 10))
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(Long.MAX_VALUE, 10))
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(-1L, 10))
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(Int.MAX_VALUE.toLong() + 1, 10))
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(0L, 0), "空角色表里 0 也在原生域外")
    }

    @Test
    fun resolverMapsProjectedVirtualNoToCurrentRow() {
        val reg = VirtualCharaNoRegistry()
        val entity = id(3, 1, 42)
        val virtualNo = reg.allocate(entity)
        var row = 2
        val resolver = VirtualCharaIndexResolver(reg) { if (it == entity) row else -1 }
        assertEquals(2, resolver.resolve(virtualNo.toLong(), 5))
        // 投射行变化（SORTCHARA/换出后重投射）——解析层每次现查，不缓存行。
        row = 4
        assertEquals(4, resolver.resolve(virtualNo.toLong(), 5))
        // 未投射（rowOfEntity = -1）⇒ 与悬空下标同语义。
        row = -1
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(virtualNo.toLong(), 5))
        // 行失效（越出当前 CHARANUM）⇒ OoR。
        row = 9
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve(virtualNo.toLong(), 5))
    }

    @Test
    fun resolverLeavesUnknownHighNumbersOutOfRange() {
        val reg = VirtualCharaNoRegistry()
        val virtualNo = reg.allocate(id(4, 0, 1))
        val resolver = VirtualCharaIndexResolver(reg) { 1 }
        // 未分配的号（虚拟域内但注册表没有）⇒ OoR，不猜、不回落到行号。
        assertEquals(VirtualCharaIndexResolver.OOR, resolver.resolve((virtualNo + 1).toLong(), 5))
        // 负数 CHARANUM 是宿主契约错误。
        assertFailsWith<IllegalArgumentException> { resolver.resolve(0L, -1) }
    }

    // ------------------------------------------------------------------
    // D-1：.rt2 二进制编解码 / 并集
    // ------------------------------------------------------------------

    @Test
    fun codecRoundtripKeepsBindingsAndRetiredFrontier() {
        val reg = VirtualCharaNoRegistry()
        val a = id(1, 0, 1)
        val b = id(1, 0, 2)
        val noA = reg.allocate(a)
        reg.allocate(b)
        reg.retire(a)

        val decoded = VirtualCharaNoRegistry.decode(reg.encode())
        assertEquals(1, decoded.size)
        assertEquals(b, decoded.entityOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1))
        assertNull(decoded.entityOf(noA), "退休号不得随编解码复活")
        // 续发游标跨往返保留：新实体拿到的号必须大于已退休的 noA。
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 2, decoded.allocate(id(1, 0, 3)))
    }

    @Test
    fun codecRejectsStructuralCorruption() {
        val reg = VirtualCharaNoRegistry()
        reg.allocate(id(1, 0, 1))
        reg.allocate(id(1, 0, 2))
        val good = reg.encode()
        fun patched(offset: Int, value: Int): ByteArray =
            good.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(offset, value) }

        assertFailsWith<IllegalArgumentException> { VirtualCharaNoRegistry.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(good.copyOfRange(0, 10))
        }
        val badMagic = good.copyOf().also { it[0] = 0 }
        val magic = assertFailsWith<IllegalArgumentException> { VirtualCharaNoRegistry.decode(badMagic) }
        assertTrue(magic.message!!.contains("魔数"), "实际: ${magic.message}")
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(good.copyOf().also { it[5] = 9 })
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(good + byteArrayOf(0))
        }
        // 首条虚拟 NO 落到原生域（< 10000）。
        assertFailsWith<IllegalArgumentException> { VirtualCharaNoRegistry.decode(patched(14, 9_999)) }
        // 首条绑定 NULL 实体。
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(good.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(18, 0L) })
        }
        // 第二条虚拟 NO 改成与首条相同（14 起首条 no；26 起第二条 no）。
        val dup = assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(patched(26, VirtualCharaNoRegistry.VIRTUAL_NO_BASE))
        }
        assertTrue(dup.message!!.contains("重复"), "实际: ${dup.message}")
        // 续发游标不大于已用最大值 ⇒ 恢复会复用退休号，必须拒。
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(patched(6, VirtualCharaNoRegistry.VIRTUAL_NO_BASE))
        }
    }

    @Test
    fun decodeAppliesNativeCsvConflictGate() {
        val reg = VirtualCharaNoRegistry()
        val used = reg.allocate(id(1, 0, 1))
        assertFailsWith<IllegalArgumentException> {
            VirtualCharaNoRegistry.decode(reg.encode()) { it == used }
        }
    }

    @Test
    fun mergeIsUnionAndRejectsIdentitySplit() {
        val local = VirtualCharaNoRegistry()
        val e1 = id(1, 0, 1)
        val e2 = id(1, 0, 2)
        val e3 = id(1, 0, 3)
        local.allocate(e1)   // 10000

        val other = VirtualCharaNoRegistry.restore(
            VirtualCharaNoRegistry.Snapshot(mapOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1 to e2), VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 2),
        )
        local.merge(other)
        assertEquals(e2, local.entityOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1))
        assertEquals(2, local.size)
        // 游标取大值：并集后新号必须是 10002，不复用任何已退休号。
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 2, local.allocate(e3))
        // 同绑定重复并（读档重放）幂等。
        local.merge(other)

        // 冲突 1：同号不同实体。
        val clashNo = VirtualCharaNoRegistry.restore(
            VirtualCharaNoRegistry.Snapshot(mapOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE to id(2, 0, 1)), VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 1),
        )
        assertFailsWith<IllegalArgumentException> { local.merge(clashNo) }
        // 冲突 2：同实体不同号。
        val clashEntity = VirtualCharaNoRegistry.restore(
            VirtualCharaNoRegistry.Snapshot(mapOf(VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 5 to e1), VirtualCharaNoRegistry.VIRTUAL_NO_BASE + 6),
        )
        assertFailsWith<IllegalArgumentException> { local.merge(clashEntity) }
        // 冲突后本地状态未半并（两遍校验先于写入）。
        assertEquals(VirtualCharaNoRegistry.VIRTUAL_NO_BASE, local.virtualNoOf(e1))
        assertNull(local.virtualNoOf(id(2, 0, 1)))
    }

    @Test
    fun mergeSnapshotRoundTripsThroughCodec() {
        val src = VirtualCharaNoRegistry()
        val e = id(5, 0, 7)
        val no = src.allocate(e)
        val dst = VirtualCharaNoRegistry()
        dst.mergeSnapshot(src.encode())
        assertEquals(e, dst.entityOf(no))
        assertFailsWith<IllegalArgumentException> { dst.mergeSnapshot(byteArrayOf(1, 2, 3)) }
    }
}
