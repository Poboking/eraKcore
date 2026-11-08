package com.eratw.port.core.patch

import java.util.PriorityQueue

/**
 * 模块链接序裁决（M2-1 Linker 的前半，纯算法、无 IO）。
 *
 * 蓝图 §11 M2 Gate：「A/B/C 独立二进制任意组合免重编（前提：依赖满足、冲突策略明确
 * ——**缺依赖的组合必须拒绝而非强跑**）」。链接器在拼接物理池之前必须先定序：
 *
 * - [ModuleDescriptor.requires] 是**硬依赖**：目标包必须在本组合里，否则整组拒绝
 *   （缺依赖强跑 = 链接期按「函数可能不存在」猜，正是 Gate 禁止的）；
 * - [ModuleDescriptor.loadAfter] 是**软顺序提示**：目标在组合里就排在它后面，
 *   不在就忽略（允许「有 A 时靠后、没 A 时照常」的组合写法）；
 * - 无约束关系的包保持**输入序**（稳定排序）：同一份 Manifest 集合两次链接得到同一
 *   条序 —— 链接产物缓存指纹（ABI-8）依赖这个确定性；
 * - 依赖环（requires 或 loadAfter 构成）整组拒绝，报告环上剩余的包名。
 *
 * 返回的是**整包描述符的链接序**（base 不在其中；base 恒排第 0，见 §1）。
 * 本类不校验函数级依赖（externalCalls 的解析要等 LSC 胜者表），也不读字节。
 */
object ModuleLinkOrder {

    /**
     * 按 requires/loadAfter 求确定性拓扑序。
     *
     * @throws LinkOrderException 缺硬依赖、包重复、依赖环。
     */
    fun plan(packages: List<ModuleDescriptor>): List<ModuleDescriptor> {
        if (packages.isEmpty()) return emptyList()

        val indexById = HashMap<String, Int>(packages.size * 2)
        for ((i, p) in packages.withIndex()) {
            val existing = indexById.put(p.packageId, i)
            if (existing != null) {
                throw LinkOrderException(
                    "链接组合里 packageId 重复：${p.packageId}（下标 $existing 与 $i）",
                )
            }
        }

        val indegree = IntArray(packages.size)
        val dependents = Array(packages.size) { ArrayList<Int>(2) }
        val edgeSeen = HashSet<Long>()

        fun addEdge(from: Int, to: Int) {
            val edge = (from.toLong() shl 32) or to.toLong()
            if (edgeSeen.add(edge)) {
                dependents[from].add(to)
                indegree[to]++
            }
        }

        for ((i, p) in packages.withIndex()) {
            for (required in p.requires) {
                val j = indexById[required]
                    ?: throw LinkOrderException(
                        "包 [${p.packageId}] 缺少硬依赖 [$required]（组合里没有这个包，拒绝强跑）",
                    )
                if (j == i) {
                    throw LinkOrderException("包 [${p.packageId}] 的 requires 指向自身（依赖环）")
                }
                addEdge(j, i)
            }
            for (after in p.loadAfter) {
                val j = indexById[after] ?: continue
                if (j == i) {
                    throw LinkOrderException("包 [${p.packageId}] 的 loadAfter 指向自身（依赖环）")
                }
                addEdge(j, i)
            }
        }

        // 稳定拓扑：就绪集合按输入下标出队 ⇒ 无约束包保持输入序。
        val ready = PriorityQueue<Int>()
        for (i in indegree.indices) if (indegree[i] == 0) ready.add(i)
        val out = ArrayList<ModuleDescriptor>(packages.size)
        while (ready.isNotEmpty()) {
            val i = ready.poll()
            out += packages[i]
            for (dep in dependents[i]) {
                if (--indegree[dep] == 0) ready.add(dep)
            }
        }
        if (out.size != packages.size) {
            val blocked = packages.indices
                .filter { indegree[it] > 0 }
                .map { packages[it].packageId }
            throw LinkOrderException("模块依赖环：${blocked.joinToString(", ")}")
        }
        return out
    }
}

/** 链接组合无法定序（缺硬依赖 / 重复包 / 依赖环）——整组拒绝，不猜、不强跑。 */
class LinkOrderException(message: String) : RuntimeException(message)
