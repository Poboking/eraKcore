package com.eratw.port.core.entity

/**
 * EntityId 位域编解码 —— v6.1-u ABI-1 冻结契约（era-arch-v61u.md §7/§10）。
 *
 * 位布局（64 位，直接塞进 ERB 的 Long 变量，符号位恒 0）：
 * ```
 * [Bit63]    = 0（恒零，保证 ERB Long 语义下非负）
 * [Bit48..62] = pkgShortId，15 位，取值 1..32767（0 = NULL 保留，见 ABI-2）
 * [Bit32..47] = generation，16 位，槽位复用时单调递增防悬空引用
 * [Bit0..31]  = handleSlot，32 位，**稳定句柄槽**（按无符号解释）
 * ```
 *
 * **handleSlot 不是 SoA 物理行**（v6.2-c 审计裁定）：SoA 允许 swap-remove 压缩，
 * 物理行会移动；句柄槽经 Handle Table（generation + denseRow）间接映射到物理行，
 * 身份终身不变。generation 用尽（65535 次复用）的句柄槽**永久退休**禁止回收，
 * 防 16 位回绕 ABA； retirement 诊断计数挂内核（M4 落实现）。
 *
 * `0L` = NULL（[NULL]）：pkgShortId=0 的编码一律拒绝，故全零是唯一合法的空值。
 *
 * **resolveAlive 必须全位域比对**（pkg + generation + slot 三段同时相等）：
 * 只比 handleSlot 会把「槽位复用后的新实体」误认为旧引用——[matches] 即该判据。
 *
 * 纯函数、无状态；core 层纯 JVM 铁律（AGENTS.md §2.4）。
 */
object EntityIdCodec {

    /** 空引用。ERB 侧 `RESULT == 0` 即判空。 */
    const val NULL: Long = 0L

    /** `__core__` 的固定短 ID（ABI-2：1 号保留给内核包）。 */
    const val CORE_PKG_SHORT_ID: Int = 1

    /** pkgShortId 上界（15 位全 1）。分配器见 PackageShortIdRegistry。 */
    const val MAX_PKG_SHORT_ID: Int = 0x7FFF

    /** generation 上界（16 位全 1）。 */
    const val MAX_GENERATION: Int = 0xFFFF

    private const val PKG_MASK: Long = 0x7FFFL
    private const val GEN_MASK: Long = 0xFFFFL
    private const val SLOT_MASK: Long = 0xFFFFFFFFL

    /**
     * 组装 EntityId。三段任一越域当场 [IllegalArgumentException]，不静默截断。
     *
     * [handleSlot] 按无符号 32 位解释（负 Int 合法，对应 ≥ 2^31 的槽位——
     * 实际容量远到不了，但契约不依赖 Int 符号）。
     */
    fun encode(pkgShortId: Int, generation: Int, handleSlot: Int): Long {
        require(pkgShortId in 1..MAX_PKG_SHORT_ID) {
            "pkgShortId 越域: $pkgShortId（合法 1..$MAX_PKG_SHORT_ID；0 是 NULL 保留）"
        }
        require(generation in 0..MAX_GENERATION) {
            "generation 越域: $generation（合法 0..$MAX_GENERATION）"
        }
        return ((pkgShortId.toLong() and PKG_MASK) shl 48) or
            ((generation.toLong() and GEN_MASK) shl 32) or
            (handleSlot.toLong() and SLOT_MASK)
    }

    /** 取 pkgShortId 段；NULL 返回 0。 */
    fun pkgShortIdOf(id: Long): Int = ((id ushr 48) and PKG_MASK).toInt()

    /** 取 generation 段；NULL 返回 0。 */
    fun generationOf(id: Long): Int = ((id ushr 32) and GEN_MASK).toInt()

    /** 取 handleSlot 段（低 32 位原样，按无符号解释）；NULL 返回 0。 */
    fun handleSlotOf(id: Long): Int = (id and SLOT_MASK).toInt()

    fun isNull(id: Long): Boolean = id == NULL

    /**
     * 形态合法性：非 NULL、符号位为 0、pkg 段非 0。
     * 不查存活（存活是 EntityKernel 的 resolveAlive 职责）。
     */
    fun isWellFormed(id: Long): Boolean =
        id != NULL && id >= 0 && pkgShortIdOf(id) != 0

    /**
     * 全位域同一性判据（resolveAlive 的比对半边）：
     * id 恰好由这三段组装而来。NULL 与任何三段都不匹配。
     */
    fun matches(id: Long, pkgShortId: Int, generation: Int, handleSlot: Int): Boolean {
        if (id == NULL) return false
        if (pkgShortId !in 1..MAX_PKG_SHORT_ID || generation !in 0..MAX_GENERATION) return false
        return id == encode(pkgShortId, generation, handleSlot)
    }
}