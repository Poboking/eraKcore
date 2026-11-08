package com.eratw.port.core.patch

import com.eratw.port.core.code.SymbolCatalog
import java.util.Locale

/**
 * 外部导入解析表（M2-1 Linker 的核心裁决面之一，ABI-4/7）。
 *
 * Module 容器里 `_CALL.a < 0` 是**外部导入哨兵**：`extIdx = -(a + 1)` 指向本 Package
 * [ModuleDescriptor.externalCalls] 的**列表下标**；链接期把每个导入回填成 LSC 胜者的
 * **物理下标**后，运行期再也看不到负哨兵（[CallSentinels]）。
 *
 * ## 为什么在“全部模块的 LSC 建好之后”解析
 *
 * 导入绑定的是**最终胜者**（受 dispatch 影响），不是“链接序在前的那个定义”。两阶段：
 * ① `ModuleLinkOrder.plan` 定序 → ② 编译产物拼成物理函数表 → ③ `SymbolCatalog.build`
 * 得 LSC 胜者 → ④ 本类按 LSC 解析导入。与依赖顺序无关的绑定让「A/B/C 任意组合免重编」
 * 在函数级也成立；`requires` 只约束声明/数据依赖，不约束函数提供者。
 *
 * ## fail-closed 判据（任一条即整组拒绝）
 *
 * - 导入名在该 Package 的 `externalCalls` 里重复（忽略大小写）——一个名字两个哨兵槽，
 *   回填哪个都是猜；
 * - 导入名找不到**非事件**胜者：事件不参与覆盖、不进 bindings，不能当普通函数导入
 *   （事件名调用走 CALLEVENT 通道）；
 * - 导入名为空白；
 * - 同一 packageId 在组合里出现两次（先于 [ModuleLinkOrder.plan] 就该拒，这里再兜一次）。
 *
 * 解析不做 trim / 大小写改写以外的事：键按 `uppercase(Locale.ROOT)` 归一（与
 * `FuncMeta.key` / `Config.StrComper` 同口径），**原文保留**用于诊断与回填日志。
 */
class ExternalImportTable private constructor(
    private val byPackage: Map<String, PackageImports>,
) {
    /** 一个 Package 的外部导入序（= `externalCalls` 列表序，即哨兵下标序）。 */
    class PackageImports internal constructor(
        val packageId: String,
        /** 按 `externalCalls` 列表序；`importIndex` 就是哨兵解码出的 extIdx。 */
        val imports: List<ResolvedImport>,
    ) {
        private val byKey: Map<String, ResolvedImport> =
            imports.associateBy { it.key }

        /** 原文名（忽略大小写）→ 解析结果；未声明导入 = null。 */
        fun importOf(name: String): ResolvedImport? = byKey[name.uppercase(Locale.ROOT)]

        /** 链接器回填用：extIdx → 物理下标（按哨兵序）。 */
        fun physicalIndices(): IntArray = IntArray(imports.size) { imports[it].physicalIndex }
    }

    /** 一条已解析导入：原文名、归一键、哨兵槽位、LSC 胜者物理下标。 */
    data class ResolvedImport(
        val name: String,
        val key: String,
        val importIndex: Int,
        val physicalIndex: Int,
    )

    val packageIds: Set<String> get() = byPackage.keys

    fun importsOf(packageId: String): PackageImports? = byPackage[packageId]

    /** 原文名（忽略大小写）→ 胜者物理下标；未声明导入或未知包 = null。 */
    fun physicalIndexOf(packageId: String, name: String): Int? =
        byPackage[packageId]?.importOf(name)?.physicalIndex

    /** 原文名（忽略大小写）→ 哨兵槽位（= `externalCalls` 列表下标）；未声明 = null。 */
    fun importIndexOf(packageId: String, name: String): Int? =
        byPackage[packageId]?.importOf(name)?.importIndex

    companion object {
        /**
         * 按 [packages]（**链接序**，[ModuleLinkOrder.plan] 的产物）与最终 LSC 解析全部导入。
         *
         * @throws ImportResolutionException 任一 Package 的导入无法解析（整组拒绝）。
         */
        fun resolve(
            packages: List<ModuleDescriptor>,
            catalog: SymbolCatalog,
        ): ExternalImportTable {
            val byPackage = LinkedHashMap<String, PackageImports>(packages.size * 2)
            for (descriptor in packages) {
                if (byPackage.containsKey(descriptor.packageId)) {
                    throw ImportResolutionException(
                        descriptor.packageId,
                        "",
                        "packageId 在链接组合里重复（导入表无法归属）",
                    )
                }
                val imports = ArrayList<ResolvedImport>(descriptor.externalCalls.size)
                val seenKeys = HashSet<String>(descriptor.externalCalls.size * 2)
                for ((index, raw) in descriptor.externalCalls.withIndex()) {
                    if (raw.isBlank()) {
                        throw ImportResolutionException(
                            descriptor.packageId,
                            raw,
                            "externalCalls 第 $index 项为空",
                        )
                    }
                    val key = raw.uppercase(Locale.ROOT)
                    if (!seenKeys.add(key)) {
                        throw ImportResolutionException(
                            descriptor.packageId,
                            raw,
                            "externalCalls 里重复导入同一个名字（忽略大小写键 $key）",
                        )
                    }
                    val physical = catalog.winnerIndexOf(key)
                    if (physical < 0) {
                        val reason = if (catalog.eventMembersOf(key).isNotEmpty()) {
                            "目标是事件（事件不参与覆盖、不进胜者表，调用走事件通道）"
                        } else {
                            "组合里没有任何非事件函数提供这个名字（缺依赖或名字拼错）"
                        }
                        throw ImportResolutionException(descriptor.packageId, raw, reason)
                    }
                    imports += ResolvedImport(raw, key, index, physical)
                }
                byPackage[descriptor.packageId] = PackageImports(descriptor.packageId, imports)
            }
            return ExternalImportTable(byPackage)
        }
    }
}

/** 外部导入无法解析（缺提供者 / 导入重复 / 事件名误当函数）——整组拒绝，不猜。 */
class ImportResolutionException(
    val packageId: String,
    val importedName: String,
    val reason: String,
) : RuntimeException(
    if (importedName.isEmpty()) {
        "外部导入无法解析：[$packageId] —— $reason"
    } else {
        "外部导入无法解析：[$packageId] $importedName —— $reason"
    },
)
