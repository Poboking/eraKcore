package com.eratw.port.core.code

import com.eratw.port.core.proc.FuncMeta
import java.util.Locale

/**
 * LSC（Linked Symbol Catalog）数据模型与纯构建器 —— ABI-7 候选契约
 * （era-arch-v61u.md §3/§10；逐项过 Gate 后才冻结，单测绿 ≠ 架构已验证）。
 *
 * **单一真源不变量**：链接期 Resolve 一次产出本目录，FunctionTable / functionDispatch /
 * MethodTable / EventRegistry 四个视图全部从同一份 LSC 派生、运行期只读。
 * 四调用通道（静态 `_CALL`、`_CALL_BY_NAME`、`_CALL_STR`、表达式 FuncCall）
 * 天然同胜者——分裂在结构上不可能，不靠纪律靠构造。
 *
 * 本文件落契约层：目录数据模型 + dispatch/superChain 的**纯函数构建器**
 * （无 IO、无运行期状态，可单测穷举）。真正的 Linker 装配（池拼接/指令重定位/
 * 元数据 rebase）是 M2 路次，消费本构建器的产物。
 *
 * 分派语义（候选，Gate 后冻结）：
 * - `dispatch[physicalIdx]` = 切面链头 ?: 覆盖胜者 ?: 自身；事件成员恒等。
 *   同时喂 callInto 与 jumpInto 两支（Vm.kt:1465-1477）；`_CALL.b` bit27
 *   DIRECT 位置位时绕过 dispatch 直调物理下标（CALL_SUPER/wrapper 内层专用）。
 * - **分派幂等不变量（v6.2-c，防二次分派）**：`dispatch[dispatch[i]] == dispatch[i]`
 *   恒成立——链头/胜者自身的 dispatch 必指向自己。名字解析通道（_CALL_BY_NAME /
 *   _CALL_STR / 表达式 FuncCall）一律返回**最终执行入口**（= dispatch 后的值），
 *   VM 对解析结果不再二次查表；静态 _CALL 的物理下标才查 dispatch。
 *   三种身份严格分开：逻辑身份 = 规范键（LSC bindings）、实现身份 = 物理下标、
 *   执行入口 = dispatch 值。构建末尾自检该不变量，违反即抛。
 * - `superChain[physicalIdx]` = 前一同名实现物理下标（无则 -1），链序 = 链接序。
 * - **覆盖签名闸（v6.4-c，ABI-7 Gate）**：MODERN 显式覆盖要求覆盖方与被覆盖方的
 *   [SymbolCandidate.signatureHash] 都非空且相等；空指纹（erbcc 未导出完整签名）或
 *   指纹不符一律抛 [OverrideSignatureException]。形参个数相同不代表 ABI 兼容，
 *   不允许把 arity 当签名用。
 * - 无覆盖时 dispatch 为恒等表——baseline 逐指令恒等，golden 零变化是 Gate。
 * - **性能闸（v6.2-c）**：无覆盖/无切面的链接产物不启用 dispatch 查表
 *   （VM 走原始物理下标直调）；恒等表只是语义基准，不是每调用的运行时成本。
 *   无 Mod / 单覆盖 / 名字缓存三组性能基准列入 M2 Gate。
 */

/** 符号类别。事件不参与覆盖；方法（#FUNCTION 族）v1 不开放覆盖但同走胜者解析。 */
enum class SymbolKind { FUNCTION, METHOD, EVENT }

/** Resolve 政策参数（era-arch-v61u.md §9）：同一管线，只改胜者选择与冲突判定。 */
enum class ResolverPolicy {
    /**
     * 普通函数 last-wins（真实注册序）、官方 bug 全复刻。现有语料行为逐位不变。
     * 版本号不进策略枚举（v6.3-c）：目标语料版本由
     * [com.eratw.port.core.proc.RuntimeCompatibilityProfile] 的 `corpusVersion` 字段声明
     * （本仓 4.980/4.981 双线），策略只表达解析语义。
     */
    LEGACY_LAST_WINS,

    /** 默认唯一符号；覆盖须显式声明，否则链接期拒绝。 */
    MODERN,
}

/**
 * 一个待解析的符号候选（物理函数）。[meta] 的 index 即合并后 Program 的物理下标，
 * key 已按 IGNORE CASE 归一（FuncMeta 构造契约）。候选列表顺序 = 链接序。
 */
data class SymbolCandidate(
    val meta: FuncMeta,
    /** Modern profile：本候选是否显式声明覆盖同名前实现（ModuleDescriptor.overrideFunctions）。 */
    val declaredOverride: Boolean = false,
    val ownerPackageId: String = "",
    /**
     * 完整调用签名指纹（M2-5 erbcc 导出；参数类型/默认值/REF/维度/返回通道的全量摘要）。
     *
     * **不是** [FuncMeta] 的 arity：形参个数相同不代表 ABI 兼容（int 换 str、REF 换值、
     * 维度不同都会让覆盖后的调用点读错通道）。空串 = 导出侧没有提供完整签名；
     * MODERN 下显式覆盖遇到空指纹**拒绝**（[OverrideSignatureException]），不拿 arity 猜。
     */
    val signatureHash: String = "",
)

/** LSC 的一条胜者绑定（canonicalName → 物理下标）。 */
data class FunctionBinding(
    val key: String,
    val physicalIndex: Int,
    val kind: SymbolKind,
    val ownerPackageId: String,
    /** 胜者的完整签名指纹（空 = 导出侧未提供；事件绑定恒空）。 */
    val signatureHash: String = "",
)

/** Modern profile 下的未声明重名冲突——链接期拒绝，报告列所有提供者。 */
class SymbolConflictException(
    val key: String,
    val providers: List<String>,
) : RuntimeException("符号 @$key 重名且未声明覆盖；提供者: ${providers.joinToString(", ")}")

/**
 * Modern profile 下显式覆盖的**签名不可验证**（ABI-7 v6.4-c Gate：信息不完整即拒）。
 *
 * 触发面：覆盖方或被覆盖方 [SymbolCandidate.signatureHash] 为空（erbcc 未导出完整签名），
 * 或两侧指纹不相等（形参类型/REF/维度/返回通道不兼容）。两种情况都**拒绝链接**——
 * 放行会让覆盖后的调用点按错误通道取值，且错误出现在运行期某次调用，不在装载期，
 * 定位成本远高于现在拒。
 */
class OverrideSignatureException(
    val key: String,
    val previousPackageId: String,
    val overridingPackageId: String,
    val reason: String,
) : RuntimeException(
    "覆盖 @$key 的签名不可验证（$previousPackageId → $overridingPackageId）：$reason",
)

/**
 * 链接期一次生成、运行期只读的符号目录。经 [build] 构造。
 *
 * [bindings] 只含非事件胜者（一名一胜者）；事件符号在 [events] 里显式一对多
 * （v6.4-c，GPT 三审 P0-1）：同名事件的**全部物理定义按链接序保留**——单一绑定
 * 表达不了多成员，EventRegistry 四桶只从 [events] 派生，不另存第二份事件列表。
 */
class SymbolCatalog private constructor(
    val candidates: List<SymbolCandidate>,
    val bindings: Map<String, FunctionBinding>,
    /** 规范事件名 → 全部物理实现（链接序）。非事件键不存在于此表。 */
    val events: Map<String, List<FunctionBinding>>,
    /** physicalIdx → 实际跳转目标（切面链头 ?: 胜者 ?: 自身；事件恒等）。 */
    val dispatch: IntArray,
    /** physicalIdx → 前一同名实现物理下标（无则 -1）。 */
    val superChain: IntArray,
) {
    /** 规范键的胜者物理下标；无绑定（含事件键）返回 -1。 */
    fun winnerIndexOf(key: String): Int =
        bindings[key.uppercase(Locale.ROOT)]?.physicalIndex ?: -1

    /**
     * `HAS_OVERRIDES` 旗标判据（v6.4-c，GPT 三审 P1-2）：最终 dispatch 存在非恒等项。
     *
     * **不是**「装了 Mod」也**不是**「有 Override 声明」——Base 内部重名在目标 profile 下
     * 也可能产生非恒等绑定（legacy last-wins）；反过来 Mod 只加新函数时 dispatch 恒等。
     * Linked 头按本值决定静态 `_CALL` 是否查 dispatch 表（false = VM 走物理下标直调），
     * 判错会让静态调用走错目标，所以判据只看最终表。
     */
    val hasOverrides: Boolean
        get() {
            for (i in dispatch.indices) if (dispatch[i] != i) return true
            return false
        }

    /** 事件名的全部成员（链接序）；非事件或未知名返回空表。 */
    fun eventMembersOf(key: String): List<FunctionBinding> =
        events[key.uppercase(Locale.ROOT)] ?: emptyList()

    /** CALL_SUPER 目标：该物理实现的前一实现；无则 -1。 */
    fun superIndexOf(physicalIndex: Int): Int =
        if (physicalIndex in superChain.indices) superChain[physicalIndex] else -1

    /** HAS_SUPER() 的后端判据：键的胜者是否有前实现。 */
    fun hasSuper(key: String): Boolean {
        val winner = winnerIndexOf(key)
        return winner >= 0 && superChain[winner] >= 0
    }

    companion object {
        /**
         * 纯构建：候选按链接序解析胜者、super 链与 dispatch 表。
         *
         * [aspectHeads]：目标规范键 → 切面 wrapper 物理下标。**v6.2-c 范围裁定：
         * 通用切面链暂缓出 Mod ABI v1**（审计 P2）——本参数是 ABI-v2 预留接缝，
         * v1 链接器恒传空映射；构建器语义保留供后续波次与测试使用。
         * wrapper 内层调用必须带 DIRECT 位指向下一切面/胜者，防 dispatch 二次跳转成环；
         * wrapper 形参 = 目标 arity，返回值三通道透传（装配侧契约，见蓝图 §3）。
         *
         * 物理下标取 [FuncMeta.index]，须非负且互异；dispatch/superChain 长度 =
         * 最大物理下标 + 1（空洞位 dispatch 恒等、superChain -1，由装配侧保证不引用）。
         */
        fun build(
            candidates: List<SymbolCandidate>,
            policy: ResolverPolicy,
            aspectHeads: Map<String, Int> = emptyMap(),
        ): SymbolCatalog {
            var size = 0
            val seen = HashSet<Int>()
            for (c in candidates) {
                val idx = c.meta.index
                require(idx >= 0) { "物理下标为负: ${c.meta.key} -> $idx" }
                require(seen.add(idx)) { "物理下标重复: $idx" }
                if (idx + 1 > size) size = idx + 1
            }

            val dispatch = IntArray(size) { it }
            val superChain = IntArray(size) { -1 }
            val winners = HashMap<String, Int>()
            val providers = HashMap<String, MutableList<String>>()
            val bindings = HashMap<String, FunctionBinding>()
            val events = HashMap<String, MutableList<FunctionBinding>>()

            for (c in candidates) {
                val meta = c.meta
                val phys = meta.index
                if (meta.isEvent) {
                    // 事件永不参与覆盖，但**全部成员按链接序进事件视图**（一对多基数）。
                    events.getOrPut(meta.key) { ArrayList() }.add(
                        FunctionBinding(meta.key, phys, SymbolKind.EVENT, c.ownerPackageId.ifEmpty { "?" }),
                    )
                    continue
                }
                val key = meta.key
                val kind = if (meta.isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION
                val owner = c.ownerPackageId.ifEmpty { "?" }
                val prev = winners[key]
                if (prev != null) {
                    val list = providers.getOrPut(key) { mutableListOf() }
                    if (list.isEmpty()) {
                        list.add(candidates.first { it.meta.index == prev }.ownerPackageId.ifEmpty { "?" })
                    }
                    list.add(owner)
                    if (policy == ResolverPolicy.MODERN) {
                        if (!c.declaredOverride) throw SymbolConflictException(key, list.toList())
                        // ABI-7 v6.4-c：显式覆盖必须两侧签名完整且相等。
                        // 空指纹 = erbcc 没有导出完整签名，拒绝而不是拿 arity 猜。
                        val prevCandidate = candidates.first { it.meta.index == prev }
                        val prevSig = prevCandidate.signatureHash
                        val newSig = c.signatureHash
                        val prevOwner = prevCandidate.ownerPackageId.ifEmpty { "?" }
                        if (prevSig.isEmpty() || newSig.isEmpty()) {
                            throw OverrideSignatureException(
                                key,
                                prevOwner,
                                owner,
                                "签名信息不完整（declaredOverride 需要完整签名指纹；空指纹不参与覆盖）",
                            )
                        }
                        if (prevSig != newSig) {
                            throw OverrideSignatureException(
                                key,
                                prevOwner,
                                owner,
                                "签名不一致（前 $prevSig / 后 $newSig）",
                            )
                        }
                    }
                    superChain[phys] = prev
                } else {
                    providers[key] = mutableListOf(owner)
                }
                winners[key] = phys
                bindings[key] = FunctionBinding(key, phys, kind, owner, c.signatureHash)
            }

            val normalizedAspects = HashMap<String, Int>()
            for ((key, head) in aspectHeads) {
                val norm = key.uppercase(Locale.ROOT)
                require(head in 0 until size) { "切面链头下标越界: $key -> $head" }
                val headCandidate = candidates.firstOrNull { it.meta.index == head }
                require(headCandidate != null && !headCandidate.meta.isEvent) {
                    "切面链头必须是已注册的非事件函数: $key -> $head"
                }
                normalizedAspects[norm] = head
            }

            for (c in candidates) {
                val meta = c.meta
                if (meta.isEvent) continue
                dispatch[meta.index] = normalizedAspects[meta.key] ?: winners[meta.key] ?: meta.index
            }

            // 分派幂等自检：dispatch 值必须是不动点，名字通道返回执行入口后二次查表不漂移。
            for (i in dispatch.indices) {
                val target = dispatch[i]
                check(dispatch[target] == target) {
                    "分派幂等不变量被破坏: dispatch[$i]=$target 但 dispatch[$target]=${dispatch[target]}"
                }
            }

            return SymbolCatalog(
                candidates.toList(),
                bindings.toMap(),
                events.mapValues { it.value.toList() },
                dispatch,
                superChain,
            )
        }
    }
}