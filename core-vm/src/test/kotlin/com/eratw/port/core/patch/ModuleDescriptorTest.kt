package com.eratw.port.core.patch

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ABI-4/5 契约钉（era-arch-v61u.md v6.2-c §2/§5）：哨兵判序、导入编号封顶（fail-closed）、
 * DIRECT 位与既有四标志不相交、覆盖政策归一、描述符形状。
 */
class ModuleDescriptorTest {

    @Test
    fun externalSentinelRoundtripAndDecisionOrder() {
        for (idx in intArrayOf(0, 1, 42, CallSentinels.IMPORT_INDEX_MAX)) {
            val a = CallSentinels.external(idx)
            assertTrue(a < 0)
            assertTrue(CallSentinels.isExternal(a))
            assertEquals(idx, CallSentinels.externalIndexOf(a))
        }
        // 两个 super 哨兵也是负数，判序必须先排除它们。
        assertFalse(CallSentinels.isExternal(CallSentinels.CALL_SUPER_SENTINEL))
        assertFalse(CallSentinels.isExternal(CallSentinels.TRY_CALL_SUPER_SENTINEL))
        assertFalse(CallSentinels.isExternal(0))
        assertFalse(CallSentinels.isExternal(7))
        assertFailsWith<IllegalArgumentException> {
            CallSentinels.externalIndexOf(CallSentinels.CALL_SUPER_SENTINEL)
        }
    }

    @Test
    fun importIndexCappedFailClosed() {
        // 旧洞：extIdx = Int.MAX_VALUE ⇒ -(idx+1) 回绕成 CALL_SUPER 哨兵。
        // 现在合法域显式封顶，越界在编码期就拒绝，不依赖「正常不会那么多」。
        assertFailsWith<IllegalArgumentException> { CallSentinels.external(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { CallSentinels.external(CallSentinels.IMPORT_INDEX_MAX + 1) }
        assertFailsWith<IllegalArgumentException> { CallSentinels.external(-1) }
        // 封顶值本身合法，且编码不与任何哨兵重叠。
        val atCap = CallSentinels.external(CallSentinels.IMPORT_INDEX_MAX)
        assertTrue(atCap != CallSentinels.CALL_SUPER_SENTINEL)
        assertTrue(atCap != CallSentinels.TRY_CALL_SUPER_SENTINEL)
    }

    @Test
    fun directFlagDisjointFromExistingCallFlags() {
        // bit27 空闲钉：与 bit31/30/29/28 四标志逐位不相交（Opcode.kt:785-801）。
        val occupied = Opcode.TRY_FLAG or Opcode.JUMP_FLAG or
            Opcode.CALLF_FLAG or Opcode.STR_RET_FLAG
        assertEquals(0, CallSentinels.DIRECT_CALL_FLAG and occupied)
        assertEquals(0x08000000, CallSentinels.DIRECT_CALL_FLAG)
        // M2 起 DIRECT 位已占用：ARG_COUNT_MASK 必须排除 bit27（2026-11-07 掩码扩展落地，
        // 旧钉「仍含 bit27」的警示期结束）——否则链接器发射的 DIRECT 位被旧 VM 当实参个数
        //（审计 4.3 的 ISA 版本化根据）。本钉改为正向判据，真源 = Opcode.DIRECT_CALL_FLAG。
        assertEquals(0, CallSentinels.DIRECT_CALL_FLAG and Opcode.ARG_COUNT_MASK)
        assertEquals(CallSentinels.DIRECT_CALL_FLAG, Opcode.DIRECT_CALL_FLAG)
        // 正向：正常实参数（≤2^26 量级）不再混入任何标志位。
        assertEquals(12345, 12345 and Opcode.ARG_COUNT_MASK)
    }

    @Test
    fun overridePolicyNormalizesKeys() {
        val desc = ModuleDescriptor(
            packageId = "org.era.test",
            overrideFunctions = setOf("com300", "EventTurnEnd"),
        )
        assertEquals(OverridePolicy.OVERWRITE, desc.policyFor("COM300"))
        assertEquals(OverridePolicy.OVERWRITE, desc.policyFor("eventturnend"))
        assertEquals(OverridePolicy.FORBID, desc.policyFor("OTHER"))
        assertEquals(setOf("COM300", "EVENTTURNEND"), desc.normalizedOverrideKeys())
        assertFailsWith<IllegalArgumentException> { ModuleDescriptor(packageId = "") }
    }

    @Test
    fun descriptorReusesAuthoritativeDimSpec() {
        val spec = DimSpec(name = "MOD_STATE", isStr = false, dims = intArrayOf(10), isGlobal = true)
        val desc = ModuleDescriptor(packageId = "org.era.test", globalDims = listOf(spec))
        // 同一类型直传，无第二套声明形态（统一铁律 5）。
        assertTrue(desc.globalDims.single() === spec)
        assertEquals(10, desc.globalDims.single().totalSize)
    }

    @Test
    fun componentSchemasCarryTypedEntityRefFields() {
        // v6.4-c（GPT 三审 P0-3A）：Manifest 承载组件 schema，EntityRef 字段进重映射遍历面。
        val schema = ComponentSchema(
            packageId = "org.era.school",
            name = "Education",
            fields = listOf(
                ComponentFieldSpec("mentorId", ComponentFieldType.INT, isEntityRef = true),
                ComponentFieldSpec("bestFriendId", ComponentFieldType.INT, isEntityRef = true),
                ComponentFieldSpec("note", ComponentFieldType.STR),
            ),
        )
        val desc = ModuleDescriptor(packageId = "org.era.school", componentSchemas = listOf(schema))
        assertTrue(desc.componentSchemas.single() === schema)
        assertEquals(setOf("MENTORID", "BESTFRIENDID"), schema.normalizedEntityRefFieldKeys())
    }

    @Test
    fun entityRefDeclarationNormalizesAndDefaultsEmpty() {
        // v6.3-c P0-1 方案 B：跨设备重映射只认显式声明的 entity-ref 键。
        val desc = ModuleDescriptor(
            packageId = "org.era.offspring",
            entityRefs = setOf("selected_child", "Last_Student_Id"),
        )
        assertEquals(setOf("SELECTED_CHILD", "LAST_STUDENT_ID"), desc.normalizedEntityRefKeys())
        // 未声明 = 空集：普通 Long 不被自动当作实体引用（诚实边界）。
        assertTrue(ModuleDescriptor(packageId = "org.era.plain").normalizedEntityRefKeys().isEmpty())
    }
}