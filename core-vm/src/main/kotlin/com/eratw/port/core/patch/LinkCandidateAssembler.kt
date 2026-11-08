package com.eratw.port.core.patch

import com.eratw.port.core.code.SymbolCandidate
import com.eratw.port.core.proc.FuncMeta

/**
 * LSC 候选装配（M2-1 单位 B；纯函数、无 IO，输入输出可穷举）。
 *
 * 链接器把 base 的函数元数据 + 各模块的 [LinkableModule.functions] 合成一个候选列表，
 * 喂给 [com.eratw.port.core.code.SymbolCatalog.build]：
 *
 * - **base 恒第 0**（era-arch-v61u.md §1）：绝对函数下标空间的前 baseFunctionCount 位
 *   归 base（其 FuncMeta.index 原值即绝对下标，不改写）；
 * - 模块窗口**稠密、无空洞**：第 i 个模块占 `[starts[i], starts[i] + 函数数)`，
 *   starts 由 [functionStarts] 按链接序累加得出（前一个窗口的终点即下一个的起点）；
 * - [assemble] 把模块内 0 基下标改写成绝对下标，并从 descriptor / 签名查询装配
 *   [SymbolCandidate] 的覆盖声明与签名指纹。
 *
 * 候选列表序 = **base 候选在前 + 模块候选按链接序**：这就是 [SymbolCandidate] 的
 * 「候选列表顺序 = 链接序」契约的物理来源，胜者链与 super 链都按它定方向。
 *
 * [FuncMeta.defIndex]（装载序号，事件同桶内按它升序执行）随 index 一起改写为
 * 绝对下标：链接形态下「装载序」就是 base 装载序 + 模块链接序，与绝对下标序一致 ——
 * 跨模块同名事件的桶内执行序由此按链接序落定，不需要链接器另做一次排序。
 */
object LinkCandidateAssembler {

    /**
     * 计算各模块在绝对函数下标空间里的起始下标（base 恒第 0，窗口稠密无空洞）。
     *
     * 返回数组与 [modules] 同长：`starts[0] = baseFunctionCount`，
     * `starts[i+1] = starts[i] + modules[i].functions.size`。
     *
     * @throws IllegalArgumentException [baseFunctionCount] 为负。
     */
    fun functionStarts(baseFunctionCount: Int, modules: List<LinkableModule>): IntArray {
        require(baseFunctionCount >= 0) { "base 函数数为负: $baseFunctionCount" }
        val starts = IntArray(modules.size)
        var acc = baseFunctionCount
        for ((i, m) in modules.withIndex()) {
            starts[i] = acc
            acc += m.functions.size
        }
        return starts
    }

    /**
     * 装配模块候选：逐模块按 [functionStarts] 给出的窗口把模块内下标改写为绝对下标。
     *
     * 输出只含**模块**候选；base 候选由 [baseCandidates] 装配（或链接器直接按
     * base 元数据构造），拼接序恒 base 在前。
     *
     * fail-closed 判据（任一命中 = 链接期整组拒绝）：
     * - [modules] 与 [functionStarts] 不同长 —— 窗口分配与模块集脱节；
     * - 模块内 [FuncMeta.index] 不是 0 基稠密（第 i 条的 index != i）—— 改写后的
     *   绝对窗口会重叠或错位；与 [LinkableModule.validate] 判据 3 同一条，
     *   这里是绕过 validate 直喂装配时的源头兜底；
     * - 窗口重叠或绝对下标越 Int 非负域 —— 用 Long 算术判，溢出当场拒绝
     *   （口径同 [CallRelocation] 的净偏移越界）。
     *
     * @throws IllegalArgumentException 任一判据命中（消息带 packageId 与模块序号）。
     */
    fun assemble(modules: List<LinkableModule>, functionStarts: IntArray): List<SymbolCandidate> {
        require(modules.size == functionStarts.size) {
            "模块数（${modules.size}）与起始下标数（${functionStarts.size}）不同长" +
                "——窗口分配必须与模块集一一对应"
        }
        // 预分配只求省扩容；溢出正确性由下面的窗口越界判据兜（Long 算术）。
        val out = ArrayList<SymbolCandidate>(modules.sumOf { it.functions.size })
        for ((mi, m) in modules.withIndex()) {
            val start = functionStarts[mi]
            require(start >= 0) {
                "模块 [${m.descriptor.packageId}]（第 $mi 个）起始下标为负: $start"
            }
            val fns = m.functions
            for ((i, meta) in fns.withIndex()) {
                require(meta.index == i) {
                    "模块 [${m.descriptor.packageId}] 的 functions[$i].index=${meta.index}" +
                        "不是模块内 0 基稠密下标（期望 $i）——先过 LinkableModule.validate"
                }
            }
            // 窗口重叠/溢出判在模块尾（Long 算术防 Int 溢出回绕成合法值）。
            val end = start.toLong() + fns.size.toLong()
            require(end <= Int.MAX_VALUE.toLong()) {
                "模块 [${m.descriptor.packageId}]（第 $mi 个）的窗口 [$start, $end) 越 Int 非负域" +
                    "——绝对函数下标空间溢出"
            }
            if (mi + 1 < functionStarts.size && functionStarts[mi + 1] < end) {
                val next = modules[mi + 1].descriptor.packageId
                throw IllegalArgumentException(
                    "模块窗口重叠：[${m.descriptor.packageId}] 占 [$start, $end)，" +
                        "下一个模块 [$next] 起始于 ${functionStarts[mi + 1]}" +
                        "——窗口必须稠密无空洞",
                )
            }
            for (meta in fns) {
                val abs = start + meta.index
                out += SymbolCandidate(
                    meta = meta.copy(index = abs, defIndex = abs),
                    declaredOverride = m.descriptor.policyFor(meta.key) == OverridePolicy.OVERWRITE,
                    ownerPackageId = m.descriptor.packageId,
                    signatureHash = m.signatureHashOf(meta.key),
                )
            }
        }
        return out
    }

    /**
     * 装配 base 候选（不改写下标：base 的 [FuncMeta.index] 原值即绝对下标）。
     *
     * - [ownerPackageId]：base 在诊断与冲突报告里的归属名（空 = catalog 显示「?」）；
     * - [signatureHashOf]：base 侧签名指纹的查询接缝 —— 完整签名导出（M2-5）落地后
     *   由宿主从 base 侧车喂入；过渡期缺省恒空串，MODERN 下覆盖 base 函数将被
     *   签名闸拒绝（fail-closed：旧产物无签名信息，放行等于拿 arity 猜）；
     * - declaredOverride 恒 false：base 无覆盖声明，legacy 重名语义由目标
     *   [com.eratw.port.core.code.ResolverPolicy] 表达，不走逐函数声明。
     */
    fun baseCandidates(
        baseMetas: List<FuncMeta>,
        ownerPackageId: String = "",
        signatureHashOf: (String) -> String = { "" },
    ): List<SymbolCandidate> = baseMetas.map { meta ->
        SymbolCandidate(
            meta = meta,
            declaredOverride = false,
            ownerPackageId = ownerPackageId,
            signatureHash = signatureHashOf(meta.key),
        )
    }
}
