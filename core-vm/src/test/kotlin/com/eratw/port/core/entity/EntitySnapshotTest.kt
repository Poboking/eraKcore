package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [EntitySnapshot] / [EntityPayloadCodec] / [EntityKernel.exportSnapshot] / [EntityKernel.restore]
 * 的判据（M4-1 SaveService 面）。
 *
 * 断言面：字节往返 + restore 后行为等价（生命周期/字段/边/query 全对拍）、
 * generation 与退休槽跨快照持久（防旧 id 复活）、pkg 重映射三面（槽身份 /
 * entity-ref 字段 / 边端点）、编解码与 restore 的 fail-closed 负控。
 */
class EntitySnapshotTest {

    private val schemas = listOf(
        ComponentSchema(
            "pkg",
            "Profile",
            listOf(
                ComponentFieldSpec("level"),
                ComponentFieldSpec("name", ComponentFieldType.STR),
                ComponentFieldSpec("mother", isEntityRef = true),
            ),
        ),
    )
    private val relations = listOf(RelationshipSchema("PARENT"))
    private val archetypes = listOf(EntityArchetype("PERSON", listOf("Profile")))

    private fun newKernel(): EntityKernel = EntityKernel(schemas, relations, archetypes, 8)

    /** 造一个有数据/边/已删槽的内核，返回（kernel, a, b, c, deadId）。 */
    /** 夹具：真实 id 由 kernel 返回值取（dead 槽会被 a 复用，generation 已递增——禁止硬编码猜槽位）。 */
    private class Seed(val k: EntityKernel, val a: Long, val b: Long, val c: Long, val dead: Long)

    private fun seeded(): Seed {
        val k = newKernel()
        val dead = k.create(2, "PERSON")
        k.setInt(dead, "level", 5L)
        assertTrue(k.destroy(dead))
        val a = k.create(1, "PERSON")
        val b = k.create(2, "PERSON")
        val c = k.create(3, "PERSON")
        k.setInt(a, "level", 42L)
        k.setStr(a, "name", " alice ")
        k.setInt(b, "mother", a)
        assertTrue(k.addRelation(a, "PARENT", b))
        assertTrue(k.addRelation(c, "PARENT", a))
        return Seed(k, a, b, c, dead)
    }

    @Test
    fun `字节往返与restore后行为等价`() {
        val src = seeded()
        val bytes = EntityPayloadCodec.encode(src.k.exportSnapshot())
        val snap = EntityPayloadCodec.decode(bytes)
        val dst = newKernel()
        dst.restore(snap)

        // 生命周期对拍：四个真实 id（含已删的）逐一。
        val a = src.a
        val b = src.b
        val c = src.c
        val dead = src.dead
        assertTrue(dst.exists(a) && dst.exists(b) && dst.exists(c))
        assertFalse(dst.exists(dead))
        assertEquals(EntityLifecycle.ACTIVE, dst.stateOf(a))
        assertEquals(3, dst.liveCount)
        // 字段值对拍（int 非零 / str / entity-ref）。
        assertEquals(42L, dst.getInt(a, "level"))
        assertEquals(" alice ", dst.getStr(a, "name"))
        assertEquals(a, dst.getInt(b, "mother"))
        assertEquals(0L, dst.getInt(c, "level"))
        assertEquals("", dst.getStr(c, "name"))
        // 边对拍（含入向）：a→PARENT→b、c→PARENT→a ⇒ b 的入边 [a]、a 的入边 [c]。
        assertContentEquals(longArrayOf(b), dst.queryRelations(a, "PARENT", outgoing = true))
        assertContentEquals(longArrayOf(a), dst.queryRelations(b, "PARENT", outgoing = false))
        assertContentEquals(longArrayOf(c), dst.queryRelations(a, "PARENT", outgoing = false))
        // query 对拍（槽序）。
        assertContentEquals(longArrayOf(a, b, c), dst.query("PERSON", 0))
        // pkg 段保留：继续 create 不破坏既有身份。
        assertEquals(3, EntityIdCodec.pkgShortIdOf(c))
    }

    @Test
    fun `generation与退休槽跨快照持久`() {
        // 先把槽 0 的 generation 推到顶格并销毁（退休），再建两个活实体。
        val k = newKernel()
        var id = k.create(1, "PERSON")
        val slot0 = EntityIdCodec.handleSlotOf(id)
        repeat(EntityIdCodec.MAX_GENERATION) {
            assertTrue(k.destroy(id))
            id = k.create(1, "PERSON")
        }
        assertTrue(k.destroy(id)) // 顶格销毁 → 槽 0 退休
        val live1 = k.create(2, "PERSON")
        val live2 = k.create(2, "PERSON")
        assertNotEquals(slot0, EntityIdCodec.handleSlotOf(live1)) // 退休槽不被复用，先落新槽
        assertTrue(k.destroy(live2)) // live2 的槽 generation=1（部分递增）

        val snap = EntityPayloadCodec.decode(EntityPayloadCodec.encode(k.exportSnapshot()))
        val dst = newKernel()
        dst.restore(snap)
        // 退休槽在 restore 后仍不被分配（freelist 重建的退休判据走通）。
        val next = dst.create(2, "PERSON")
        assertNotEquals(slot0, EntityIdCodec.handleSlotOf(next))
        // live1 槽（generation=0）继续存在；live2 的槽是空的且 generation=1 ——
        // 下一次 destroy 循环复用它时 generation 从 1 递增（不回 0）。
        assertTrue(dst.exists(live1))
        val reused = dst.create(2, "PERSON")
        dst.destroy(reused)
        val again = dst.create(2, "PERSON")
        assertTrue(
            EntityIdCodec.generationOf(again) >= 1,
            "空槽的递增 generation 必须随快照持久（否则旧 id 复活）",
        )
    }

    @Test
    fun `pkg重映射覆盖槽身份与entityRef字段与边端点`() {
        val src = seeded()
        val snap = src.k.exportSnapshot()
        // 2 号包 → 7 号（1 号内核包不参与；3 号未映射原样）。
        val table = EntityIdRemapTable.of(mapOf(2 to 7))
        val remapped = snap.remapped(table)
        val dst = newKernel()
        dst.restore(remapped)
        // 各实体的真实 id 经同一张表（未映射包原样；pkg 段替换、gen/slot 保留）。
        val a = table.remap(src.a)
        val b = table.remap(src.b)
        val c = table.remap(src.c)
        assertTrue(dst.exists(a) && dst.exists(b) && dst.exists(c))
        // entity-ref 字段（mother 槽里存的是 a）跟着换。
        assertEquals(a, dst.getInt(b, "mother"))
        // 边端点跟着换。
        assertContentEquals(longArrayOf(b), dst.queryRelations(a, "PARENT", outgoing = true))
        // 非 entity-ref 字段不重映射（level=42 是普通值）。
        assertEquals(42L, dst.getInt(a, "level"))
    }

    @Test
    fun `边值跨字节往返与restore无损`() {
        val src = seeded()
        // 原生 RELATION 格是 Long 度数（语料与 100/200 比大小）⇒ 边值必须随快照走。
        assertTrue(src.k.setRelationValue(src.a, "PARENT", src.b, 200L))
        assertTrue(src.k.setRelationValue(src.c, "PARENT", src.a, -1234567890123L))
        val bytes = EntityPayloadCodec.encode(src.k.exportSnapshot())
        val snap = EntityPayloadCodec.decode(bytes)
        assertEquals(2, snap.edges.size)
        assertEquals(200L, snap.edges.first { it.from == src.a }.value)
        assertEquals(-1234567890123L, snap.edges.first { it.from == src.c }.value)
        val dst = newKernel()
        dst.restore(snap)
        assertEquals(200L, dst.getRelationValue(src.a, "PARENT", src.b))
        assertEquals(-1234567890123L, dst.getRelationValue(src.c, "PARENT", src.a))
        // 未设值的边仍是 0（不是残值）。
        assertEquals(0L, dst.getRelationValue(src.b, "PARENT", src.a))
    }

    @Test
    fun `pkg重映射保留边值原样`() {
        val src = seeded()
        assertTrue(src.k.setRelationValue(src.a, "PARENT", src.b, 150L))
        val table = EntityIdRemapTable.of(mapOf(2 to 7))
        val remapped = src.k.exportSnapshot().remapped(table)
        // 值不是引用：换端点不换值。
        assertEquals(150L, remapped.edges.single { it.from == table.remap(src.a) }.value)
        val dst = newKernel()
        dst.restore(remapped)
        assertEquals(150L, dst.getRelationValue(table.remap(src.a), "PARENT", table.remap(src.b)))
    }

    // ------------------------------------------------------------------
    // 负控
    // ------------------------------------------------------------------

    @Test
    fun `实体段v1拒载不做双版本读取`() {
        val bytes = EntityPayloadCodec.encode(seeded().k.exportSnapshot())
        // 大端 u32 版本头：末字节改 1 = v1（无边值的旧布局）。ABI-6 未冻结、无已发存档 ⇒ 直接拒。
        bytes[3] = 1
        val ex = assertFailsWith<IllegalArgumentException> { EntityPayloadCodec.decode(bytes) }
        assertTrue("版本" in ex.message!!, ex.message)
    }

    @Test
    fun `截断一律拒载且不抛缓冲区异常`() {
        val bytes = EntityPayloadCodec.encode(seeded().k.exportSnapshot())
        for (cut in intArrayOf(0, 3, 7, 20, 61, bytes.size - 8, bytes.size - 1)) {
            assertFailsWith<IllegalArgumentException>("cut=$cut") {
                EntityPayloadCodec.decode(bytes.copyOfRange(0, cut))
            }
        }
        // 版本头都不完整时给「截断」口径，而不是把 BufferUnderflowException 漏给调用方。
        val ex = assertFailsWith<IllegalArgumentException> {
            EntityPayloadCodec.decode(bytes.copyOfRange(0, 3))
        }
        assertTrue("截断" in ex.message!!, ex.message)
    }

    @Test
    fun `尾部残留拒绝`() {
        val bytes = EntityPayloadCodec.encode(seeded().k.exportSnapshot())
        val ex = assertFailsWith<IllegalArgumentException> {
            EntityPayloadCodec.decode(bytes + byteArrayOf(0))
        }
        assertTrue("残留" in ex.message!!, ex.message)
    }

    @Test
    fun `版本不支持拒绝`() {
        val bytes = EntityPayloadCodec.encode(seeded().k.exportSnapshot())
        bytes[0] = 9
        val ex = assertFailsWith<IllegalArgumentException> { EntityPayloadCodec.decode(bytes) }
        assertTrue("版本" in ex.message!!, ex.message)
    }

    @Test
    fun `restore非空内核拒绝`() {
        val src = seeded()
        val snap = EntityPayloadCodec.decode(EntityPayloadCodec.encode(src.k.exportSnapshot()))
        val ex = assertFailsWith<IllegalArgumentException> { src.k.restore(snap) }
        assertTrue("空内核" in ex.message!!, ex.message)
    }

    @Test
    fun `快照字段未注册拒绝且内核保持空态`() {
        val src = seeded()
        val snap = src.k.exportSnapshot()
        val dst = newKernel()
        val bogus = EntitySnapshot(
            snap.slots,
            snap.archetypes,
            snap.fields + EntitySnapshot.FieldSnapshot(
                "GHOST_FIELD",
                ComponentFieldType.INT,
                false,
                listOf(EntitySnapshot.IntLongPair(1, 1L)),
            ),
            snap.relationKinds,
            snap.edges,
        )
        val ex = assertFailsWith<IllegalArgumentException> { dst.restore(bogus) }
        assertTrue("未注册" in ex.message!!, ex.message)
        // 预校验先于写入：失败的 restore 不留半灌状态。
        assertEquals(0, dst.liveCount)
        dst.restore(src.k.exportSnapshot()) // 仍可正常灌入
        assertEquals(3, dst.liveCount)
    }

    @Test
    fun `悬空边拒绝`() {
        val src = seeded()
        val snap = src.k.exportSnapshot()
        val bogus = EntitySnapshot(
            snap.slots,
            snap.archetypes,
            snap.fields,
            snap.relationKinds,
            snap.edges + EntitySnapshot.EdgeSnapshot(0x00FF_00FF_00FFL, 0, snap.slots[1].let {
                EntityIdCodec.encode(it.pkgShortId, it.generation, it.slot)
            }),
        )
        val dst = newKernel()
        val ex = assertFailsWith<IllegalArgumentException> { dst.restore(bogus) }
        assertTrue("悬空" in ex.message!!, ex.message)
    }

    @Test
    fun `entityRef非良构值解码拒绝`() {
        val k = newKernel()
        val a = k.create(1, "PERSON")
        // 违约写入：entity-ref 字段塞了非实体值（内核不管内容，快照编解码判良构）。
        assertTrue(k.setInt(a, "mother", 12345L))
        val ex = assertFailsWith<IllegalArgumentException> {
            EntityPayloadCodec.decode(EntityPayloadCodec.encode(k.exportSnapshot()))
        }
        assertTrue("不是良构" in ex.message!!, ex.message)
    }
}
