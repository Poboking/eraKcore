package com.eratw.port.core.entity

import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.ModuleDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-4：外观数据契约的可执行形态（era-arch §16 / §13-30）。
 *
 * ## 契约
 *
 * 外观（发色/服色/立绘/部件）**不是引擎概念**：core 不建 Appearance 类、不认任何字段名。
 * Mod 用通用 [ComponentSchema] 声明一组 INT/STR 字段（必要时带 `isEntityRef`），
 * 数据落在实体内核的列里、随 `.rt2` 实体段持久化；渲染是 host/UI 独立波次（MOD-7），
 * 只经 schema 描述与本合约定的字段名读值。
 *
 * 本套件用**故意不可猜的字段名**（`ZZLOOK_*`）走全链路——声明事务 → 冻结快照 →
 * 内核读写 → 快照编解码往返。任何「core 偷偷认识某个业务字段名」的实现都会在这组
 * 名字上失败；反过来说，只要这组名字能跑通，契约对任何名字都成立。
 *
 * ## 明确不测的
 *
 * 业务词 grep 门禁（core 源码零业务字段名）归 M4-3 的脏活扫描，不在本套件；
 * 渲染 hook / 资源解析归 MOD-7。
 */
class AppearanceComponentContractTest {

    /** 外观组件：四个字段覆盖 STR/INT/EntityRef 三类声明，名字全部是虚构的。 */
    private val component = ComponentSchema(
        packageId = "mod.look",
        name = "ZZLOOK",
        fields = listOf(
            ComponentFieldSpec("ZZLOOK_ALPHA", ComponentFieldType.STR),
            ComponentFieldSpec("ZZLOOK_OMEGA", ComponentFieldType.STR),
            ComponentFieldSpec("ZZLOOK_TIER", ComponentFieldType.INT),
            ComponentFieldSpec("ZZLOOK_PARENT", ComponentFieldType.INT, isEntityRef = true),
        ),
    )

    private fun declare(): DeclarationSnapshot {
        val descriptor = ModuleDescriptor(
            packageId = "mod.look",
            componentSchemas = listOf(component),
        )
        return DeclarationRegistry().addPackage(descriptor, "look.manifest").freeze()
    }

    private fun kernel(): EntityKernel = EntityKernel(
        schemas = listOf(component),
        relationshipSchemas = emptyList(),
        archetypes = listOf(EntityArchetype("ZZLOOK_HOST", listOf("ZZLOOK"))),
    )

    @Test
    fun `外观 schema 经声明事务冻结快照可达`() {
        val snapshot = declare()
        val declared = assertNotNull(snapshot.componentSchema("ZZLOOK"), "组件名按声明登记")
        assertEquals(4, declared.fields.size)
        // 名字归一（OrdinalIgnoreCase 口径）：原文大小写不同也查得到。
        assertNotNull(snapshot.componentSchema("zzlook"), "组件名查询必须大小写不敏感")
        // EntityRef 字段集合 = 跨设备重映射遍历面（持久引用三面之一）。
        assertEquals(setOf("ZZLOOK_PARENT"), declared.normalizedEntityRefFieldKeys())
    }

    @Test
    fun `任意命名字段在内核可读写而未声明字段失败`() {
        val k = kernel()
        val id = k.create(1, "ZZLOOK_HOST")
        assertTrue(k.setStr(id, "ZZLOOK_ALPHA", "髪_赤"))
        assertTrue(k.setStr(id, "ZZLOOK_OMEGA", "服_白"))
        assertTrue(k.setInt(id, "ZZLOOK_TIER", 3L))
        assertEquals("髪_赤", k.getStr(id, "ZZLOOK_ALPHA"))
        assertEquals("服_白", k.getStr(id, "zzlook_omega"), "字段读取按归一名字寻址")
        assertEquals(3L, k.getInt(id, "ZZLOOK_TIER"))
        // 未声明字段不隐式建列：set 失败、读回哨兵（0 / ""）——外观扩展不能靠打错名字生效。
        assertFalse(k.setStr(id, "ZZLOOK_UNDECLARED", "x"))
        assertFalse(k.setInt(id, "ZZLOOK_UNDECLARED", 1L))
        assertEquals("", k.getStr(id, "ZZLOOK_UNDECLARED"))
        assertEquals(0L, k.getInt(id, "ZZLOOK_UNDECLARED"))
    }

    @Test
    fun `外观字段完整过 rt2 实体段往返且身份不变`() {
        val k1 = kernel()
        val id = k1.create(1, "ZZLOOK_HOST")
        k1.setStr(id, "ZZLOOK_ALPHA", "髪_赤")
        k1.setStr(id, "ZZLOOK_OMEGA", "服_白")
        k1.setInt(id, "ZZLOOK_TIER", 3L)

        val bytes = EntityPayloadCodec.encode(k1.exportSnapshot())
        val k2 = kernel()
        k2.restore(EntityPayloadCodec.decode(bytes))

        assertTrue(k2.exists(id), "EntityId 必须跨往返存活（generation/槽位不变）")
        assertEquals("髪_赤", k2.getStr(id, "ZZLOOK_ALPHA"), "UTF-8 字符串无损")
        assertEquals("服_白", k2.getStr(id, "ZZLOOK_OMEGA"))
        assertEquals(3L, k2.getInt(id, "ZZLOOK_TIER"))
        // 声明面只来自 Mod：内核不认识这四个名字里的任何一个。
        assertFalse(k2.setStr(id, "HAIR_COLOR", "x"), "core 不得隐式认识业务字段名")
    }
}
