package com.eratw.port.core.patch

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import java.security.MessageDigest
import java.util.Locale

/**
 * 链接产物缓存指纹（ABI-8 候选；era-arch-v61u.md §2/GPT 二审 P1-5）。
 *
 * Mod 组合变更时链接一次、产物落盘缓存 `linked_<fingerprint>.erbc`，启动按指纹命中
 * 直接 mmap（ADR-011 零解析）。**任何能改变编译/链接/运行语义的量都必须进指纹**——
 * 漏一个因子 = 旧缓存被错配到新语义（指令流按旧链接序/旧 ISA 解释）。
 *
 * ## 十因子
 *
 * base 内容哈希、链接序模块产物哈希链、CompilerABI、LinkerVersion、BytecodeISA 版本、
 * BuiltinRegistry 指纹、RuntimeProfile 指纹、ResolutionPolicy、变量/实体 schema 指纹、
 * **规范化后的 Manifest/Override 解析结果**（v6.3-c P1-5：声明顺序、键归一后再哈希，
 * 防同内容不同序列化漂移）。
 *
 * ## 归一口径（哪些因子排序、哪些保持序）
 *
 * - **排序归一**（顺序无语义的字段集合）：`overrideFunctions`（Set 语义）、
 *   `requires` / `loadAfter`（[ModuleLinkOrder.plan] 会重排，源顺序不影响产物）、
 *   componentSchemas / entityArchetypes / relationshipSchemas / csvPatches /
 *   csvValuePatches / entityRefs / globalDims / privateDims 的**名字集合**——
 *   名字大写归一（NamePolicy 口径）后排序；
 * - **保持列表序**（顺序有物理/叠加语义）：`externalCalls`（列表序 = 哨兵槽位）、
 *   [moduleArtifactSha256s]（链接序 = 函数窗口分配序）、csvPatches/csvValuePatches
 *   的**条目序**（同格叠加按序生效）——只按列表序哈希、绝不排序。
 */
object LinkFingerprint {

    /** 指纹输入（不可变快照；字段语义见类 KDoc 的十因子）。 */
    class Inputs(
        val baseProgramSha256: String,
        /** 链接序模块产物哈希（[ModuleLinkOrder.plan] 后的序）；列表序进指纹。 */
        val moduleArtifactSha256s: List<String>,
        val compilerAbi: String,
        val linkerVersion: String,
        val bytecodeIsaVersion: Int,
        val builtinRegistryFingerprint: String,
        val runtimeProfileFingerprint: String,
        val resolutionPolicy: ResolverPolicy,
        val variableSchemaFingerprint: String,
        val entitySchemaFingerprint: String,
        /** 链接组合的全部 Manifest（链接序）。 */
        val manifests: List<ModuleDescriptor>,
    )

    /** 计算 SHA-256 指纹（十六进制小写）。 */
    fun compute(inputs: Inputs): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun line(key: String, value: String) {
            digest.update("$key=$value\n".toByteArray(Charsets.UTF_8))
        }
        line("base", inputs.baseProgramSha256)
        line("modules", inputs.moduleArtifactSha256s.joinToString(","))
        line("compilerAbi", inputs.compilerAbi)
        line("linker", inputs.linkerVersion)
        line("isa", inputs.bytecodeIsaVersion.toString())
        line("builtin", inputs.builtinRegistryFingerprint)
        line("profile", inputs.runtimeProfileFingerprint)
        line("policy", inputs.resolutionPolicy.name)
        line("varSchema", inputs.variableSchemaFingerprint)
        line("entitySchema", inputs.entitySchemaFingerprint)
        // 规范化 Manifest：字段级稳定渲染（顺序无关集排序归一，列表序保持）。
        for (m in inputs.manifests) {
            line("pkg", m.packageId)
            line("version", m.version)
            line("requires", m.requires.map { it.uppercase(Locale.ROOT) }.sorted().joinToString(","))
            line("loadAfter", m.loadAfter.map { it.uppercase(Locale.ROOT) }.sorted().joinToString(","))
            // 哨兵槽语义：externalCalls 保持列表序。
            line("ext", m.externalCalls.joinToString(","))
            line("override", m.normalizedOverrideKeys().sorted().joinToString(","))
            line("entityRefs", m.normalizedEntityRefKeys().sorted().joinToString(","))
            line("csvKeyword", m.csvPatches.joinToString(",") { it.render() })
            line("csvValue", m.csvValuePatches.joinToString(",") { it.render() })
            line("schema", m.componentSchemas.map { it.render() }.sorted().joinToString("|"))
            line("archetype", m.entityArchetypes.map { it.render() }.sorted().joinToString("|"))
            line("relation", m.relationshipSchemas.map { it.render() }.sorted().joinToString("|"))
            line("dims", m.globalDims.map { it.render() }.sorted().joinToString("|"))
            line("privateDims", m.privateDims.entries
                .sortedBy { it.key.uppercase(Locale.ROOT) }
                .joinToString("|") { (k, v) ->
                    k.uppercase(Locale.ROOT) + ":" + v.map { it.render() }.sorted().joinToString(",")
                })
            line("macros", m.exportedMacros.entries
                .sortedBy { it.key.uppercase(Locale.ROOT) }
                .joinToString(",") { (k, v) -> k.uppercase(Locale.ROOT) + "=" + v })
            line("artifact", m.artifactSha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- 各声明的稳定渲染（值域上归一；不引入第二套规范化规则） ----

    private fun CsvKeywordPatch.render(): String =
        "${tableName.uppercase(Locale.ROOT)}[$index]${keyword.uppercase(Locale.ROOT)}" + if (isAlias) "@alias" else ""

    private fun CsvValuePatch.render(): String =
        "${table.uppercase(Locale.ROOT)}:${row.uppercase(Locale.ROOT)}:${column.uppercase(Locale.ROOT)}" +
            ":${op.name}:" + (value ?: "")

    private fun ComponentSchema.render(): String =
        name.uppercase(Locale.ROOT) + "(" + fields.joinToString(",") { f ->
            f.name.uppercase(Locale.ROOT) + ":" + f.type.name + if (f.isEntityRef) "!ref" else ""
        } + ")"

    private fun EntityArchetype.render(): String =
        name.uppercase(Locale.ROOT) + "{" + components.map { it.uppercase(Locale.ROOT) }.sorted().joinToString(",") + "}"

    private fun RelationshipSchema.render(): String = name.uppercase(Locale.ROOT) +
        (if (directed) ">" else "<") +
        (if (dedupeEdges) "d" else "") + (if (cascadeOnDestroy) "c" else "") +
        (if (autoInverse) "->" + inverseName!!.uppercase(Locale.ROOT) else "")

    private fun DimSpec.render(): String = name.uppercase(Locale.ROOT) + (if (isStr) "$" else "") +
        "[" + dims.joinToString(",") + "]" + (if (isConst) "c" else "") +
        (if (isRef) "r" else "") + (if (isGlobal) "g" else "")
}
