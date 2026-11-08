package com.eratw.port.core.patch

import com.eratw.port.core.code.Program
import com.eratw.port.core.proc.FuncMeta

/**
 * 链接输入契约 —— M2-1 Linker 的输入面（ABI-4；era-arch-v61u.md §1/§2/§4）。
 *
 * Module（未链接的 .erbc 容器形态）在进链接器前由容器读取方展开成本接口的
 * 三样数据 + 一个签名查询；链接器只消费本接口，**不读容器字节、不碰 IO**
 * （容器读写是 M2-2 执行路次；legacy .patch 转码器（M2-4）的产物同样落本接口，
 * 运行期单管线）。
 *
 * ## 三样数据
 *
 * - [descriptor]：Manifest 核心字段（[ModuleDescriptor]，ABI-5）——链接序裁决
 *   （[ModuleLinkOrder.plan]）、覆盖声明（[ModuleDescriptor.policyFor]）、
 *   外部导入（[ModuleDescriptor.externalCalls]）都从这里取；
 * - [program]：**零基线**模块编译产物（六个基线全 0；模块按 base=0 编译，
 *   指令流与四池的绝对下标在链接期才分配）。注意与 ProgramOverlay 的「带基线
 *   补丁窗口」是两种形态：带基线产物基线取自目标 base 的计数、可直接 overlay；
 *   零基线 Module 对任何 base 都对不上那道窗口闸，必须由链接器自己分配函数窗口、
 *   回填指令操作数（这是 M2-1 链接器另起拼装路的结构原因）；
 * - [functions]：运行期元数据，与 [program] 的函数表**同序同长**（functions[i]
 *   描述函数表第 i 条记录）；[FuncMeta.index] 为**模块内 0 基稠密下标**
 *   （0..size-1），链接期改写为绝对物理下标（[LinkCandidateAssembler]）。
 *
 * [program] 的 entryFunction 语义归编译侧（模块没有运行入口），本契约不判；
 * 链接产物的入口恒为 base 的入口（铁律：模块只追加函数，不改入口）。
 *
 * ## 签名查询（M2-5 erbcc 导出的消费面）
 *
 * [signatureHashOf] 返回完整调用签名指纹（参数类型/默认值/REF/维度/返回通道的
 * 全量摘要，ABI-7 v6.4-c）；查询键与 [FuncMeta.key] 同口径（大写归一）。
 * **未知键或导出侧未提供 = 空串**，不是异常：MODERN 下显式覆盖遇到空指纹被
 * [com.eratw.port.core.code.OverrideSignatureException] 拒绝（fail-closed，
 * 不拿 arity 猜）；legacy profile 不设闸。容器里没有签名段时实现方恒返空串。
 *
 * ## Linked 产物形状（链接器出口，M2-1 单位 C 交付）
 *
 * 链接器消费一组本接口（已按 [ModuleLinkOrder.plan] 定序）+ base 产物，产出：
 * **Program**（拼接结果，可交给 VM）+ **SymbolCatalog**（LSC，ABI-7）+
 * **ExternalImportTable**（导入回填记录）+ **hasOverrides**（catalog 判据透传）+
 * **moduleOrder**（链接序，诊断与缓存指纹 ABI-8 用）。容器、序裁决、候选装配、
 * 拼装、自检各归各文件，本接口不承载链接逻辑。
 */
interface LinkableModule {

    /** Manifest 核心字段（ABI-5）。 */
    val descriptor: ModuleDescriptor

    /** 零基线模块编译产物（六个基线全 0，[validate] 判）。 */
    val program: Program

    /** 与 [program] 函数表同序同长的运行期元数据；[FuncMeta.index] = 模块内 0 基下标。 */
    val functions: List<FuncMeta>

    /** 完整签名指纹（M2-5 导出）；未知键或未导出 = 空串（覆盖闸按 fail-closed 拒）。 */
    fun signatureHashOf(functionKey: String): String

    companion object {
        /**
         * 形状校验（链接器入口与容器读取方共用的 fail-closed 判据；任一违反 = 整组拒绝）：
         *
         * 1. [program] 六基线全 0 —— 零基线模块形态；带基线的补丁窗口不是本契约的
         *    输入形态（那是 ProgramOverlay 的直拼路，两条路不许混）；
         * 2. [functions] 与 [program] 的函数表同长 —— FuncMeta 与函数记录一一对应，
         *    短了丢元数据、长了引用不存在的函数记录；
         * 3. [functions] 的 [FuncMeta.index] 稠密 0 基 —— 第 i 条的 index 必为 i；
         *    空洞或重复会让绝对下标改写后函数窗口重叠，取用静默落错段
         *    （[com.eratw.port.core.code.SymbolCatalog.build] 的重复下标拒绝兜底，
         *    但报错位置离病根远，这里在源头判）。
         *
         * @throws IllegalArgumentException 任一判据命中（消息带 packageId 定位）。
         */
        fun validate(module: LinkableModule) {
            val p = module.program
            val pkg = module.descriptor.packageId
            require(
                p.codeBase == 0 && p.statementBase == 0 && p.stringBase == 0 &&
                    p.longBase == 0 && p.exprBase == 0 && p.functionBase == 0,
            ) {
                "模块 [$pkg] 的编译产物不是零基线形态（codeBase=${p.codeBase}" +
                    " statementBase=${p.statementBase} stringBase=${p.stringBase}" +
                    " longBase=${p.longBase} exprBase=${p.exprBase} functionBase=${p.functionBase}）" +
                    "——链接器只收零基线 Module；带基线补丁窗口走 ProgramOverlay 直拼路"
            }
            val fns = module.functions
            require(fns.size == p.functions.size) {
                "模块 [$pkg] 的 functions（${fns.size} 条）与编译产物函数表" +
                    "（${p.functions.size} 条）不同长——FuncMeta 与函数记录必须一一对应"
            }
            for (i in fns.indices) {
                val idx = fns[i].index
                require(idx == i) {
                    "模块 [$pkg] 的 functions[$i].index=$idx 不是模块内 0 基稠密下标" +
                        "（期望 $i；空洞或重复会让链接后的函数窗口重叠）"
                }
            }
        }
    }
}
