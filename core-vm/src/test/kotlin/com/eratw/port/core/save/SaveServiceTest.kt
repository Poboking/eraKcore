package com.eratw.port.core.save

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.EntityPayloadCodec
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RegistryConflictException
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.entity.RosterBridge
import com.eratw.port.core.entity.RosterPinner
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SaveService] 编排不变量判据（M4-1，蓝图 §7 存档前同步 / §8 一个逻辑存档单元）。
 *
 * 断言面（每条都是「架构不变质」的承重墙，不是覆盖率凑数）：
 * - **先脱水后抓取**：全部投射实体按 EntityId 升序脱水，且脱水发生在 export 之前
 *   （用「活行值只经脱水才进组件」的 fake 桥证明——顺序反了载荷里就是旧值）；
 * - **先并注册表后灌实体**：并集冲突时内核与本地注册表都未被动过；
 * - **绑定校验先于任何写入**：三元组任一字段失配 ⇒ 零写入 + 结构化失配结果；
 * - 孤儿片逐字节透传（含 0x00/0xFF/0x0D 等会被文本层改写的字节）与分片分类冲突拒绝；
 * - global.rt2 实体载荷恒空、不触发脱水、且不可喂进槽恢复入口；
 * - 虚拟 NO 注册表随槽 .rt2 往返、global.rt2 恒空、宿主未装配时非空段 fail-closed；
 * - 恢复不预先清空内核（「先复位后解析」是宿主装载路的原版语义，ADR-005 不动）。
 */
class SaveServiceTest {

    private val schemas = listOf(
        ComponentSchema(
            "pkg",
            "Profile",
            listOf(ComponentFieldSpec("level"), ComponentFieldSpec("name", ComponentFieldType.STR)),
        ),
    )
    private val relations = listOf(RelationshipSchema("PARENT"))
    private val archetypes = listOf(EntityArchetype("PERSON", listOf("Profile")))
    private val binding = SidecarBinding(uniqueCode = 0x1234_5678L, scriptVersion = 7L, saveTextHash = 99)

    private fun newKernel(): EntityKernel = EntityKernel(schemas, relations, archetypes, 8)

    /**
     * fake 桥：[captureToEntity] 把「活行数据」写回组件——真实脱水语义的最小模型。
     * 于是「载荷里的 level 是不是活行值」直接证明脱水先于 export，不靠调用序日志自说自话。
     */
    private class DehydratingBridge(private val k: EntityKernel) : RosterBridge {
        val calls = ArrayList<String>()
        val liveRow = HashMap<Int, Long>()
        private var nextRow = 0

        override fun allocateRow(): Int = nextRow++
        override fun releaseRow(charIndex: Int) {
            calls += "release:$charIndex"
            liveRow.remove(charIndex)
        }

        override fun pourFromEntity(entityId: Long, charIndex: Int) {
            calls += "pour:$entityId->$charIndex"
        }

        override fun captureToEntity(entityId: Long, charIndex: Int) {
            calls += "capture:$entityId<-$charIndex"
            k.setInt(entityId, "level", liveRow[charIndex] ?: 0L)
        }
    }

    private class Fixture(
        val k: EntityKernel,
        val reg: PackageShortIdRegistry,
        val bridge: DehydratingBridge,
        val p: RosterPinner,
        val vreg: VirtualCharaNoRegistry,
        val svc: SaveService,
    )

    private fun fixture(injectVirtualRegistry: Boolean = true): Fixture {
        val k = newKernel()
        val reg = PackageShortIdRegistry()
        reg.allocate("school.mod") // 2
        val orphanId = reg.allocate("old.mod") // 3
        reg.lock(orphanId) // 孤儿片仍在磁盘上 ⇒ 短 ID 锁定
        val bridge = DehydratingBridge(k)
        val p = RosterPinner(k, bridge)
        val vreg = VirtualCharaNoRegistry()
        val svc = SaveService(k, p, reg, if (injectVirtualRegistry) vreg else null)
        return Fixture(k, reg, bridge, p, vreg, svc)
    }

    /** 把载荷灌进一个空内核，便于按 EntityId 读回字段值。 */
    private fun into(payload: ByteArray): EntityKernel = newKernel().also {
        it.restore(EntityPayloadCodec.decode(payload))
    }

    // ------------------------------------------------------------------
    // 不变量 1：先脱水后抓取
    // ------------------------------------------------------------------

    @Test
    fun `抓取前对全部投射实体按EntityId升序脱水`() {
        val f = fixture()
        val e1 = f.k.create(2, "PERSON")
        val e2 = f.k.create(2, "PERSON")
        // 故意反行序投射：e2 占行 0、e1 占行 1。枚举口径必须是 EntityId 升序而不是行序。
        val r2 = f.p.project(e2)
        val r1 = f.p.project(e1)
        assertEquals(0, r2)
        assertEquals(1, r1)
        f.bridge.liveRow[r1] = 111L
        f.bridge.liveRow[r2] = 222L
        f.bridge.calls.clear()

        val payload = f.svc.captureEntityPayload()

        assertEquals(listOf("capture:$e1<-$r1", "capture:$e2<-$r2"), f.bridge.calls)
        // 活行值进了载荷 ⇒ 脱水确实发生在 export 之前（顺序反了这里会是 0）。
        val dst = into(payload)
        assertEquals(111L, dst.getInt(e1, "level"))
        assertEquals(222L, dst.getInt(e2, "level"))
    }

    @Test
    fun `未投射实体不被脱水`() {
        val f = fixture()
        val projected = f.k.create(2, "PERSON")
        val loose = f.k.create(2, "PERSON")
        val row = f.p.project(projected)
        f.bridge.liveRow[row] = 42L
        f.bridge.calls.clear()

        val dst = into(f.svc.captureEntityPayload())

        assertEquals(listOf("capture:$projected<-$row"), f.bridge.calls)
        assertEquals(42L, dst.getInt(projected, "level"))
        assertEquals(0L, dst.getInt(loose, "level"), "未投射实体没有活行，不该被脱水改写")
        assertTrue(dst.exists(loose), "未投射实体仍在快照里（它只是没登场）")
    }

    @Test
    fun `无投射实体时抓取不触发任何桥调用`() {
        val f = fixture()
        val e = f.k.create(2, "PERSON")
        f.k.setInt(e, "level", 5L)
        f.bridge.calls.clear()
        val dst = into(f.svc.captureEntityPayload())
        assertTrue(f.bridge.calls.isEmpty())
        assertEquals(5L, dst.getInt(e, "level"))
    }

    // ------------------------------------------------------------------
    // 槽 bundle 往返
    // ------------------------------------------------------------------

    @Test
    fun `槽bundle经容器字节往返后恢复等价`() {
        val src = fixture()
        val a = src.k.create(2, "PERSON")
        val b = src.k.create(2, "PERSON")
        src.k.setStr(a, "name", " alice ")
        assertTrue(src.k.addRelation(a, "PARENT", b))
        val row = src.p.project(a)
        src.bridge.liveRow[row] = 77L

        val bundle = src.svc.captureBundle(
            binding,
            activeChunks = listOf(PackageChunk("school.mod", byteArrayOf(1, 2, 3))),
            orphanChunks = listOf(PackageChunk("old.mod", byteArrayOf(9, 8))),
        )
        val back = SidecarSaveBundle.decode(bundle.encode())

        val dst = fixture()
        assertEquals(SaveService.RestoreOutcome.Applied, dst.svc.restoreBundle(back, binding))
        assertEquals(77L, dst.k.getInt(a, "level"))
        assertEquals(" alice ", dst.k.getStr(a, "name"))
        assertContentEquals(longArrayOf(b), dst.k.queryRelations(a, "PARENT", outgoing = true))
        assertEquals(2, dst.k.liveCount)
        // 注册表快照随档走：短 ID 与孤儿锁一并还原。
        assertEquals(2, dst.reg.shortIdOf("school.mod"))
        assertEquals(3, dst.reg.shortIdOf("old.mod"))
        assertTrue(dst.reg.isLocked(3))
        // 分片序 = 激活组后接孤儿组，载荷逐字节不变。
        assertEquals(listOf("school.mod", "old.mod"), back.chunks.map { it.packageId })
        assertContentEquals(byteArrayOf(1, 2, 3), back.chunks[0].payload)
        assertContentEquals(byteArrayOf(9, 8), back.chunks[1].payload)
    }

    // ------------------------------------------------------------------
    // 不变量 3：绑定校验先于任何写入
    // ------------------------------------------------------------------

    @Test
    fun `绑定三元组任一字段失配则零写入`() {
        val src = fixture()
        src.reg.allocate("extra.mod") // 失配时若误并注册表，这一条会漏进来
        src.k.create(2, "PERSON")
        val bundle = src.svc.captureBundle(binding)

        val badBindings = listOf(
            binding.copy(uniqueCode = binding.uniqueCode + 1),
            binding.copy(scriptVersion = binding.scriptVersion + 1),
            binding.copy(saveTextHash = binding.saveTextHash + 1),
        )
        for (bad in badBindings) {
            val dst = fixture()
            val outcome = dst.svc.restoreBundle(bundle, bad)
            val mismatch = assertIs<SaveService.RestoreOutcome.BindingMismatch>(outcome, "$bad")
            assertEquals(bad, mismatch.expected)
            assertEquals(binding, mismatch.actual)
            assertEquals(0, dst.k.liveCount, "$bad：内核未动")
            assertEquals(3, dst.reg.count, "$bad：注册表未并")
            assertNull(dst.reg.shortIdOf("extra.mod"), "$bad：注册表未并")
        }
    }

    // ------------------------------------------------------------------
    // 不变量 2：先并注册表后灌实体
    // ------------------------------------------------------------------

    @Test
    fun `注册表分裂冲突时内核与本地注册表都未动`() {
        val src = fixture()
        src.k.create(2, "PERSON")
        val payload = src.svc.captureEntityPayload()
        // 造一份分裂快照：school.mod 在对面是 5 号（本地是 2 号）。
        val split = PackageShortIdRegistry()
        split.allocate("a.mod") // 2
        split.allocate("b.mod") // 3
        split.allocate("c.mod") // 4
        split.allocate("school.mod") // 5
        val bad = SidecarSaveBundle(binding, split.encode(), payload, emptyList())

        val dst = fixture()
        val ex = assertFailsWith<RegistryConflictException> { dst.svc.restoreBundle(bad, binding) }
        assertTrue("school.mod" in ex.message!!, ex.message)
        assertEquals(0, dst.k.liveCount, "并注册表先于灌实体：冲突时内核仍是空态")
        assertEquals(2, dst.reg.shortIdOf("school.mod"))
        assertEquals(3, dst.reg.count)
        assertNull(dst.reg.shortIdOf("a.mod"))
    }

    @Test
    fun `只并注册表不灌实体`() {
        val src = fixture()
        src.reg.allocate("extra.mod") // 4
        src.k.create(2, "PERSON")
        val dst = fixture()
        dst.svc.mergeRegistrySnapshot(src.reg.encode())
        assertEquals(4, dst.reg.shortIdOf("extra.mod"))
        assertEquals(0, dst.k.liveCount, "并集独立成立，不该顺手灌实体")
    }

    @Test
    fun `恢复不预先清空内核`() {
        val src = fixture()
        src.k.create(2, "PERSON")
        val bundle = src.svc.captureBundle(binding)
        val dst = fixture()
        dst.k.create(2, "PERSON") // 宿主尚未复位
        val ex = assertFailsWith<IllegalArgumentException> { dst.svc.restoreBundle(bundle, binding) }
        assertTrue("空内核" in ex.message!!, ex.message)
        assertEquals(1, dst.k.liveCount, "复位是宿主装载路的原版语义（ADR-005），SaveService 不代劳")
    }

    // ------------------------------------------------------------------
    // 虚拟 NO 注册表（D-1）：每槽身份，先于实体图恢复
    // ------------------------------------------------------------------

    @Test
    fun `虚拟NO注册表随槽档往返`() {
        val src = fixture()
        val e = src.k.create(2, "PERSON")
        src.k.setStr(e, "name", "kid")
        val no = src.vreg.allocate(e)

        val back = SidecarSaveBundle.decode(src.svc.captureBundle(binding).encode())
        assertTrue(back.virtualNoSnapshot.isNotEmpty())

        val dst = fixture()
        assertEquals(SaveService.RestoreOutcome.Applied, dst.svc.restoreBundle(back, binding))
        assertEquals(no, dst.vreg.virtualNoOf(e), "身份映射按 EntityId 原样恢复")
        assertEquals(e, dst.vreg.entityOf(no))
        assertEquals("kid", dst.k.getStr(e, "name"))
        // 续发游标随档：读档后新实体不得复用旧号（含退休号）。
        assertEquals(no + 1, dst.vreg.allocate(src.k.create(2, "PERSON")))
    }

    @Test
    fun `未装配虚拟NO注册表的宿主读非空段fail_closed`() {
        val bare = fixture(injectVirtualRegistry = false)
        assertTrue(bare.svc.captureBundle(binding).virtualNoSnapshot.isEmpty(), "无投射能力 ⇒ 空段")

        val mod = fixture()
        val e = mod.k.create(2, "PERSON")
        mod.vreg.allocate(e)
        val bundle = mod.svc.captureBundle(binding)
        val ex = assertFailsWith<IllegalArgumentException> { bare.svc.restoreBundle(bundle, binding) }
        assertTrue("VirtualCharaNoRegistry" in ex.message!!, ex.message)
        assertEquals(0, bare.k.liveCount, "静默丢映射比读档失败更糟：报错且不灌实体")
    }

    @Test
    fun `虚拟NO分裂冲突时内核不灌且不半并`() {
        val src = fixture()
        val no = src.vreg.allocate(src.k.create(2, "PERSON"))
        val bundle = src.svc.captureBundle(binding)

        val dst = fixture()
        assertEquals(no, dst.vreg.allocate(777L), "构造同号不同实体")
        val ex = assertFailsWith<IllegalArgumentException> { dst.svc.restoreBundle(bundle, binding) }
        assertTrue("分裂" in ex.message!!, ex.message)
        assertEquals(0, dst.k.liveCount, "冲突在并集期抛出：内核仍是空态")
        assertEquals(777L, dst.vreg.entityOf(no), "两遍校验先于写入：本地映射未被半并覆盖")
    }

    // ------------------------------------------------------------------
    // 全局档
    // ------------------------------------------------------------------

    @Test
    fun `全局档实体载荷恒空且不触发脱水`() {
        val f = fixture()
        val e = f.k.create(2, "PERSON")
        val row = f.p.project(e)
        f.bridge.liveRow[row] = 55L
        f.bridge.calls.clear()

        val global = f.svc.captureGlobalBundle(
            binding,
            orphanChunks = listOf(PackageChunk("old.mod", byteArrayOf(7))),
        )
        assertTrue(global.entityGraphPayload.isEmpty(), "SAVEGLOBAL 不带实体载荷（实体按槽走）")
        assertTrue(f.bridge.calls.isEmpty(), "全局档不该碰活行")
        assertTrue(global.registrySnapshot.isNotEmpty())
        assertTrue(global.virtualNoSnapshot.isEmpty(), "虚拟 NO 是每槽身份，global.rt2 恒不带该段")
        assertEquals(listOf("old.mod"), global.chunks.map { it.packageId })
        assertTrue(SidecarSaveBundle.decode(global.encode()).entityGraphPayload.isEmpty())
        // 判据前提：空内核编码也写版本头与名表 ⇒ 零长载荷唯一地标识全局档。
        assertTrue(EntityPayloadCodec.encode(newKernel().exportSnapshot()).isNotEmpty())
        assertEquals(0L, f.k.getInt(e, "level"), "未脱水 ⇒ 组件仍是旧值")
    }

    @Test
    fun `全局档不可喂进槽恢复入口`() {
        val f = fixture()
        val global = f.svc.captureGlobalBundle(binding)
        val ex = assertFailsWith<IllegalArgumentException> { f.svc.restoreBundle(global, binding) }
        assertTrue("global.rt2" in ex.message!!, ex.message)
    }

    // ------------------------------------------------------------------
    // 孤儿片透传与分类冲突
    // ------------------------------------------------------------------

    @Test
    fun `孤儿片逐字节透传含控制字节`() {
        val f = fixture()
        // 0x00 / 0xFF / 0x0D / 0x0A / 0x1A：任何文本化或 trim 都会在这里露馅。
        val raw = byteArrayOf(0, -1, 127, -128, 13, 10, 26, 32)
        val bundle = f.svc.captureBundle(
            binding,
            orphanChunks = listOf(PackageChunk("old.mod", raw)),
        )
        val back = SidecarSaveBundle.decode(bundle.encode())
        assertEquals(1, back.chunks.size)
        assertEquals("old.mod", back.chunks[0].packageId)
        assertContentEquals(raw, back.chunks[0].payload)
    }

    @Test
    fun `分片分类冲突当场拒绝`() {
        val f = fixture()
        val dupActive = assertFailsWith<IllegalArgumentException> {
            f.svc.captureBundle(
                binding,
                activeChunks = listOf(PackageChunk("p.mod", ByteArray(0)), PackageChunk("p.mod", ByteArray(0))),
            )
        }
        assertTrue("激活分片" in dupActive.message!!, dupActive.message)

        val dupOrphan = assertFailsWith<IllegalArgumentException> {
            f.svc.captureBundle(
                binding,
                orphanChunks = listOf(PackageChunk("p.mod", ByteArray(0)), PackageChunk("p.mod", ByteArray(0))),
            )
        }
        assertTrue("孤儿分片" in dupOrphan.message!!, dupOrphan.message)

        val both = assertFailsWith<IllegalArgumentException> {
            f.svc.captureBundle(
                binding,
                activeChunks = listOf(PackageChunk("p.mod", byteArrayOf(1))),
                orphanChunks = listOf(PackageChunk("p.mod", byteArrayOf(2))),
            )
        }
        assertTrue("既新又旧" in both.message!!, both.message)
    }
}