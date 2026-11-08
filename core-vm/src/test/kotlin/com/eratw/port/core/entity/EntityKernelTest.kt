package com.eratw.port.core.entity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [EntityKernel] SoA + Handle Table 的判据（M4-1）。
 *
 * 断言面：生命周期三态与 generation 悬空、槽复用残值清零、未注册字段/类型不符
 * fail-closed、query 的槽序与 pkg 段保留、关系边 dedupe/autoInverse/级联/无向双问、
 * generation 到顶槽退休（0xFFFF 次真实循环到达，不复用旧槽）。
 */
class EntityKernelTest {

    private fun kernel(
        initialCapacity: Int = 8,
        schemas: List<ComponentSchema> = listOf(
            ComponentSchema("pkg", "Profile", listOf(ComponentFieldSpec("level"), ComponentFieldSpec("name", ComponentFieldType.STR))),
            ComponentSchema("pkg", "Extra", listOf(ComponentFieldSpec("MISC_STR", ComponentFieldType.STR))),
        ),
        relations: List<RelationshipSchema> = listOf(
            RelationshipSchema("PARENT"),
            RelationshipSchema("BOND", directed = false),
            RelationshipSchema("MIRROR", autoInverse = true, inverseName = "MIRRORED_BY"),
            RelationshipSchema("KEEP", cascadeOnDestroy = false),
        ),
        archetypes: List<EntityArchetype> = listOf(
            EntityArchetype("PERSON", listOf("Profile", "Extra")),
            EntityArchetype("THING", listOf("Profile")),
        ),
    ): EntityKernel = EntityKernel(schemas, relations, archetypes, initialCapacity)

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Test
    fun `建实与销毁与generation悬空`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        assertTrue(EntityIdCodec.isWellFormed(a))
        assertEquals(EntityLifecycle.ACTIVE, k.stateOf(a))
        assertEquals(1, k.liveCount)
        assertTrue(k.destroy(a))
        assertEquals(0, k.stateOf(a))
        assertFalse(k.exists(a))
        assertEquals(0, k.liveCount)
        // destroy 后旧 id 悬空；重建同槽实体得到新 generation 的 id。
        val b = k.create(1, "PERSON")
        assertTrue(k.exists(b))
        assertNotEquals(a, b)
        assertEquals(EntityIdCodec.generationOf(a) + 1, EntityIdCodec.generationOf(b))
        assertEquals(EntityIdCodec.handleSlotOf(a), EntityIdCodec.handleSlotOf(b))
        assertFalse(k.destroy(a))
        assertFalse(k.destroy(EntityIdCodec.NULL))
    }

    @Test
    fun `三态切换与RETIRED留数据`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        assertTrue(k.setInt(a, "level", 7L))
        assertTrue(k.setState(a, EntityLifecycle.INACTIVE))
        assertEquals(EntityLifecycle.INACTIVE, k.stateOf(a))
        assertTrue(k.setState(a, EntityLifecycle.RETIRED))
        // RETIRED 也算存在（接口口径）：留数据、可读写。
        assertTrue(k.exists(a))
        assertEquals(7L, k.getInt(a, "level"))
        assertTrue(k.setInt(a, "level", 9L))
        assertEquals(9L, k.getInt(a, "level"))
        assertFalse(k.setState(a, 99))
        assertFalse(k.setState(a, 0))
    }

    @Test
    fun `槽复用残值清零`() {
        val k = kernel(initialCapacity = 4)
        val a = k.create(1, "PERSON")
        assertTrue(k.setInt(a, "level", 42L))
        assertTrue(k.setStr(a, "name", "旧实体"))
        assertTrue(k.destroy(a))
        val b = k.create(1, "PERSON")
        assertEquals(EntityIdCodec.handleSlotOf(a), EntityIdCodec.handleSlotOf(b))
        // 同槽新实体：组件列必须干净（不清 = 读到被删实体的数据）。
        assertEquals(0L, k.getInt(b, "level"))
        assertEquals("", k.getStr(b, "name"))
    }

    @Test
    fun `未注册字段与类型不符拒绝`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        // 未注册字段：读失败哨兵、写 false。
        assertEquals(0L, k.getInt(a, "NOSUCH"))
        assertEquals("", k.getStr(a, "NOSUCH"))
        assertFalse(k.setInt(a, "NOSUCH", 1L))
        assertFalse(k.setStr(a, "NOSUCH", "x"))
        // 类型不符：int 字段按 str 通道读写皆失败（口径同接口 KDoc）。
        assertFalse(k.setStr(a, "level", "x"))
        assertEquals("", k.getStr(a, "level"))
        assertFalse(k.setInt(a, "name", 1L))
        // 键大写归一。
        assertTrue(k.setInt(a, "LEVEL", 3L))
        assertEquals(3L, k.getInt(a, "level"))
        // 悬空 id 读写失败。
        assertFalse(k.setInt(0x00FF_0000_0001L, "level", 1L))
        assertEquals(0L, k.getInt(0x00FF_0000_0001L, "level"))
    }

    @Test
    fun `query按原型与状态过滤且槽序升序`() {
        val k = kernel(initialCapacity = 4)
        val p1 = k.create(1, "PERSON")
        val t1 = k.create(1, "THING")
        val p2 = k.create(2, "PERSON")
        k.setState(t1, EntityLifecycle.RETIRED)
        // PERSON 全部（2 个，pkg 各自保留）。
        val persons = k.query("PERSON", 0)
        assertContentEquals(longArrayOf(p1, p2), persons)
        assertEquals(1, EntityIdCodec.pkgShortIdOf(p1))
        assertEquals(2, EntityIdCodec.pkgShortIdOf(p2))
        // RETIRED 的 THING：state=3 命中（空数组对拍恒用 assertContentEquals：
        // assertEquals 对 LongArray 是身份比较，两个空数组也不等）。
        assertContentEquals(longArrayOf(t1), k.query("THING", EntityLifecycle.RETIRED))
        assertContentEquals(LongArray(0), k.query("THING", EntityLifecycle.ACTIVE))
    }

    @Test
    fun `generation到顶槽退休永不复用`() {
        val k = kernel(initialCapacity = 4)
        var id = k.create(1, "PERSON")
        val slot = EntityIdCodec.handleSlotOf(id)
        // 真实循环把 generation 推到 0xFFFF（65,535 次 O(1) 往返，毫秒级）。
        repeat(EntityIdCodec.MAX_GENERATION) {
            assertTrue(k.destroy(id))
            id = k.create(1, "PERSON")
        }
        assertEquals(EntityIdCodec.MAX_GENERATION, EntityIdCodec.generationOf(id))
        assertEquals(slot, EntityIdCodec.handleSlotOf(id))
        // 第 0xFFFF 次 destroy：槽退休，下一次分配必须落到别的槽。
        assertTrue(k.destroy(id))
        val next = k.create(1, "PERSON")
        assertNotEquals(slot, EntityIdCodec.handleSlotOf(next))
        assertTrue(EntityIdCodec.generationOf(next) == 0)
        // 退休槽上的旧 id 仍判不存在（悬空）。
        assertFalse(k.exists(id))
    }

    @Test
    fun `原型未注册拒绝`() {
        val k = kernel()
        val ex = assertFailsWith<IllegalArgumentException> { k.create(1, "GHOST") }
        assertTrue("原型未注册" in ex.message!!, ex.message)
    }

    // ------------------------------------------------------------------
    // 关系边
    // ------------------------------------------------------------------

    @Test
    fun `关系加删查与dedupe`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        val c = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "PARENT", b))
        // dedupe：重复加返回 false。
        assertFalse(k.addRelation(a, "PARENT", b))
        assertTrue(k.hasRelation(a, "PARENT", b))
        assertFalse(k.hasRelation(b, "PARENT", a))
        assertTrue(k.addRelation(a, "PARENT", c))
        assertContentEquals(longArrayOf(b, c), k.queryRelations(a, "PARENT", outgoing = true))
        assertContentEquals(longArrayOf(a), k.queryRelations(b, "PARENT", outgoing = false))
        assertTrue(k.removeRelation(a, "PARENT", b))
        assertFalse(k.hasRelation(a, "PARENT", b))
        assertFalse(k.removeRelation(a, "PARENT", b))
        // 未注册 kind / 端点不存在。
        assertFalse(k.addRelation(a, "NOKIND", b))
        assertFalse(k.addRelation(a, "PARENT", 0x00FF_0000_0009L))
        assertFalse(k.removeRelation(a, "NOKIND", b))
        // 键大小写归一。
        assertTrue(k.hasRelation(a, "parent", c))
    }

    @Test
    fun `无向边双向问询与去重`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "BOND", b))
        // 无向：反向问询同一条边判真；反向再加 = 重复。
        assertTrue(k.hasRelation(b, "BOND", a))
        assertFalse(k.addRelation(b, "BOND", a))
        assertContentEquals(longArrayOf(a), k.queryRelations(b, "BOND", outgoing = false))
        // 摘任一方向 = 整条边消失。
        assertTrue(k.removeRelation(b, "BOND", a))
        assertFalse(k.hasRelation(a, "BOND", b))
    }

    @Test
    fun `autoInverse自动反边`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "MIRROR", b))
        // 反向 kind 的边自动建立：作为独立边可查。
        assertTrue(k.hasRelation(b, "MIRRORED_BY", a))
        // 主边摘除 → 自动反边同步消失。
        assertTrue(k.removeRelation(a, "MIRROR", b))
        assertFalse(k.hasRelation(b, "MIRRORED_BY", a))
        // 反边作为普通边：其 schema 无 autoInverse，不会再折返。
        assertTrue(k.addRelation(b, "MIRRORED_BY", a))
        assertFalse(k.hasRelation(a, "MIRROR", b))
    }

    @Test
    fun `destroy无条件摘除直接相连的边`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "PARENT", b))
        assertTrue(k.addRelation(b, "KEEP", a))
        // 销毁 a：两端挂着的边全 kind 摘除（悬空边 = 数据错误；cascadeOnDestroy=false
        // 的「保留历史」语义 v1 不消费，见 EntityKernel.removeEdgesOf 的裁定 KDoc）。
        assertTrue(k.destroy(a))
        assertFalse(k.hasRelation(a, "PARENT", b))
        assertFalse(k.hasRelation(b, "KEEP", a))
        assertContentEquals(LongArray(0), k.queryRelations(b, "KEEP", outgoing = true))
        assertContentEquals(LongArray(0), k.queryRelations(b, "KEEP", outgoing = false))
    }

    // ------------------------------------------------------------------
    // 边值（关系投影协议：原生 RELATION 格是 Long 度数，回写必须无损）
    // ------------------------------------------------------------------

    @Test
    fun `边值读写与未建边不隐式建边`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.hasRelationKind("PARENT"))
        assertTrue(k.hasRelationKind("parent"))
        assertFalse(k.hasRelationKind("NOKIND"))
        // 建边即值 0；未建边读 0、写 false（存在性先于值）。
        assertEquals(0L, k.getRelationValue(a, "PARENT", b))
        assertFalse(k.setRelationValue(a, "PARENT", b, 100L))
        assertFalse(k.hasRelation(a, "PARENT", b))
        assertTrue(k.addRelation(a, "PARENT", b))
        assertEquals(0L, k.getRelationValue(a, "PARENT", b))
        assertTrue(k.setRelationValue(a, "PARENT", b, 200L))
        assertEquals(200L, k.getRelationValue(a, "PARENT", b))
        // 有向边：反向不是同一条边。
        assertEquals(0L, k.getRelationValue(b, "PARENT", a))
        assertFalse(k.setRelationValue(b, "PARENT", a, 7L))
        // 未注册 kind 一律 false / 0；键大小写归一。
        assertFalse(k.setRelationValue(a, "NOKIND", b, 1L))
        assertEquals(0L, k.getRelationValue(a, "NOKIND", b))
        assertTrue(k.setRelationValue(a, "parent", b, -5L))
        assertEquals(-5L, k.getRelationValue(a, "PARENT", b))
        // 悬空端点：读 0、写 false。
        assertTrue(k.destroy(b))
        assertEquals(0L, k.getRelationValue(a, "PARENT", b))
        assertFalse(k.setRelationValue(a, "PARENT", b, 1L))
    }

    @Test
    fun `无向边值只存一份反向读写同值`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "BOND", b))
        assertTrue(k.setRelationValue(a, "BOND", b, 100L))
        // 反向读 = 同一条出边上的值（不会分叉成两份）。
        assertEquals(100L, k.getRelationValue(b, "BOND", a))
        assertTrue(k.setRelationValue(b, "BOND", a, 200L))
        assertEquals(200L, k.getRelationValue(a, "BOND", b))
        // 快照里只有一条边（值随边）。
        val edges = k.exportSnapshot().edges
        assertEquals(1, edges.size)
        assertEquals(200L, edges.single().value)
        assertTrue(k.removeRelation(b, "BOND", a))
        assertEquals(0L, k.getRelationValue(a, "BOND", b))
    }

    @Test
    fun `autoInverse反边是独立边各有自己的值`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "MIRROR", b))
        assertTrue(k.setRelationValue(a, "MIRROR", b, 7L))
        // 自动反边建出来时值 0，不抄主边的值。
        assertEquals(0L, k.getRelationValue(b, "MIRRORED_BY", a))
        assertTrue(k.setRelationValue(b, "MIRRORED_BY", a, 9L))
        assertEquals(7L, k.getRelationValue(a, "MIRROR", b))
        assertEquals(9L, k.getRelationValue(b, "MIRRORED_BY", a))
        assertEquals(2, k.exportSnapshot().edges.size)
        // 摘主边 → 反边连同值一起消失；重建后值归零（不留残值）。
        assertTrue(k.removeRelation(a, "MIRROR", b))
        assertEquals(0L, k.getRelationValue(b, "MIRRORED_BY", a))
        assertFalse(k.hasRelation(b, "MIRRORED_BY", a))
        assertTrue(k.addRelation(a, "MIRROR", b))
        assertEquals(0L, k.getRelationValue(a, "MIRROR", b))
    }

    @Test
    fun `摘边与重建边值归零`() {
        val k = kernel()
        val a = k.create(1, "PERSON")
        val b = k.create(1, "PERSON")
        assertTrue(k.addRelation(a, "PARENT", b))
        assertTrue(k.setRelationValue(a, "PARENT", b, 12345L))
        assertTrue(k.removeRelation(a, "PARENT", b))
        assertEquals(0L, k.getRelationValue(a, "PARENT", b))
        assertTrue(k.addRelation(a, "PARENT", b))
        assertEquals(0L, k.getRelationValue(a, "PARENT", b))
        // 销毁对端连带清值：重建同槽实体（新 generation）也不继承旧值。
        assertTrue(k.setRelationValue(a, "PARENT", b, 999L))
        assertTrue(k.destroy(b))
        val b2 = k.create(1, "PERSON")
        assertEquals(0L, k.getRelationValue(a, "PARENT", b2))
        assertTrue(k.addRelation(a, "PARENT", b2))
        assertEquals(0L, k.getRelationValue(a, "PARENT", b2))
    }
}
