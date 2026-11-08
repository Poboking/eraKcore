package com.eratw.port.core.patch

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [LinkFingerprint] 十因子判据（ABI-8 候选）。
 *
 * 断言面：同输入同指纹；**任一因子变化 ⇒ 指纹变**（逐因子）；归一判定——
 * 顺序无语义的集合（overrideFunctions/requires/schema 名单）顺序漂移不影响指纹，
 * 列表序有语义的（externalCalls 哨兵槽 / 模块哈希链接序）顺序变化改变指纹。
 */
class LinkFingerprintTest {

    private fun manifest(
        pkg: String = "mod.a",
        overrides: Set<String> = emptySet(),
        external: List<String> = emptyList(),
        requires: List<String> = emptyList(),
        schemas: List<ComponentSchema> = emptyList(),
        archetypes: List<EntityArchetype> = emptyList(),
    ): ModuleDescriptor = ModuleDescriptor(
        packageId = pkg,
        requires = requires,
        externalCalls = external,
        overrideFunctions = overrides,
        componentSchemas = schemas,
        entityArchetypes = archetypes,
        artifactSha256 = "sha-$pkg",
    )

    private fun inputs(
        base: String = "base-sha",
        modules: List<String> = listOf("m1", "m2"),
        policy: ResolverPolicy = ResolverPolicy.MODERN,
        manifests: List<ModuleDescriptor> = listOf(manifest()),
        isa: Int = 2,
    ): LinkFingerprint.Inputs = LinkFingerprint.Inputs(
        baseProgramSha256 = base,
        moduleArtifactSha256s = modules,
        compilerAbi = "erbcc-1",
        linkerVersion = "link-1",
        bytecodeIsaVersion = isa,
        builtinRegistryFingerprint = "builtin-1",
        runtimeProfileFingerprint = "profile-1",
        resolutionPolicy = policy,
        variableSchemaFingerprint = "var-1",
        entitySchemaFingerprint = "ent-1",
        manifests = manifests,
    )

    @Test
    fun `同输入同指纹且形如sha256`() {
        val a = LinkFingerprint.compute(inputs())
        val b = LinkFingerprint.compute(inputs())
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `任一因子变化指纹变`() {
        val base = LinkFingerprint.compute(inputs())
        assertNotEquals(base, LinkFingerprint.compute(inputs(base = "base-sha2")))
        assertNotEquals(base, LinkFingerprint.compute(inputs(modules = listOf("m2", "m1")))) // 链接序进指纹
        assertNotEquals(base, LinkFingerprint.compute(inputs(modules = listOf("m1"))))       // 组合成员变化
        assertNotEquals(base, LinkFingerprint.compute(inputs(policy = ResolverPolicy.LEGACY_LAST_WINS)))
        assertNotEquals(base, LinkFingerprint.compute(inputs(isa = 3)))
        // Manifest 内容变化。
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(overrides = setOf("F1"))))))
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("F1"))))))
        assertNotEquals(base, LinkFingerprint.compute(inputs(manifests = listOf(manifest(pkg = "mod.b")))))
    }

    @Test
    fun `顺序无语义的集合排序归一`() {
        val f1 = LinkFingerprint.compute(
            inputs(manifests = listOf(manifest(overrides = setOf("F1", "F2"), requires = listOf("x", "y")))),
        )
        // 顺序漂移（含大小写漂移）同指纹：声明顺序、键归一后再哈希（v6.3-c P1-5）。
        val f2 = LinkFingerprint.compute(
            inputs(manifests = listOf(manifest(overrides = setOf("f2", "f1"), requires = listOf("Y", "X")))),
        )
        assertEquals(f1, f2)
    }

    @Test
    fun `哨兵槽与列表序保持`() {
        // externalCalls 列表序 = 哨兵槽位（有物理语义），顺序变化 ⇒ 指纹变。
        val f1 = LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("A", "B")))))
        val f2 = LinkFingerprint.compute(inputs(manifests = listOf(manifest(external = listOf("B", "A")))))
        assertNotEquals(f1, f2)
    }
}
