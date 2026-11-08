package com.eratw.port.core.save

import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.EntityPayloadCodec
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.VirtualCharaNoRegistry

/**
 * 一个逻辑存档单元的**抓取/恢复编排核心**（ERA Runtime 2.0 §8；M4-1 的 SaveService 面）。
 *
 * `saveNN.sav` + `saveNN.sav.rt2` = 一个逻辑存档。本类只管 .rt2 侧的**顺序不变量**与
 * 配对完整性判定；.sav 侧沿用既有 [SaveCapture]/[SaveFileIo]，事务日志落盘/fsync/换名/
 * .bak 轮转/三语 RestoreReport 属 M1-5 脏活，恢复判定一律走 [SaveRecovery.plan]。
 *
 * ## 三条顺序不变量（本类存在的理由）
 *
 * 1. **先脱水后抓取**（§7 存档前同步）：抓 .rt2 前必须对**全部**投射实体调
 *    [ICharaSlotProjector.syncFromCharaSlot]，再 [EntityKernel.exportSnapshot]。
 *    顺序反了 = 快照滞后于活行数据，玩家读档后「孩子身上的改动没了」。枚举入口是
 *    [ICharaSlotProjector.projectedEntities]，不靠调用方自己记得有哪些实体。
 * 2. **先并注册表后灌实体**：恢复时依次 [VirtualCharaNoRegistry.mergeSnapshot]（虚拟 NO
 *    ↔ EntityId，§16 第 6 点）→ [PackageShortIdRegistry.mergeSnapshot] → [EntityKernel.restore]。
 *    注册表并集是**独立成立**的操作（§7：装载 = 全局注册表 ∪ 磁盘全部 sidecar 快照，
 *    孤儿片也要并进来锁短 ID），所以它不与实体灌入同事务；但实体 id 的 pkg 段要有注册表
 *    backing，且 `.sav` 角色块里的虚拟 NO 要有身份映射，故两者都必须先并。冲突在并集期
 *    就抛（[com.eratw.port.core.entity.RegistryConflictException]），此时内核仍是空态。
 * 3. **绑定校验先于任何写入**：三元组（boundUniqueCode + scriptVersion + saveTextHash）
 *    不符 ⇒ 不碰注册表、不碰内核，返回 [RestoreOutcome.BindingMismatch] 交宿主上三语警告
 *    （§8 兼容性矩阵：PC 玩过后传回安卓 = 警告「扩展状态可能滞后或丢失」，不是静默应用）。
 *
 * ## 孤儿片原样透传
 *
 * 未激活 Mod 的分片必须逐字节回写（§8 .rt2 容器 / §7 孤儿锁定），否则「禁用 school.mod
 * 读存再启用无损」这条 M4 Gate 直接破。本类把 active 与 orphan 两组合并，并**拒绝同一
 * packageId 同时出现在两组**——一个包要么激活（重新抓取）要么孤儿（透传），不能既新又旧；
 * 出现重复即宿主分类错了，当场报错而不是让后写入的分片静默盖掉前一个。
 *
 * ## 全局档不带实体
 *
 * [captureGlobalBundle] 的实体载荷恒空（§8：SAVEGLOBAL 不带实体载荷，实体按槽走）。
 * 分成两个入口而不是一个布尔开关，是为了让「实体永不进 global.rt2」在调用点显式可见。
 *
 * 本类无文件 IO、无可变全局；内核/注册表/投射器由宿主构造注入（AGENTS §2.4）。
 */
class SaveService(
    private val kernel: EntityKernel,
    private val projector: ICharaSlotProjector,
    private val pkgRegistry: PackageShortIdRegistry,
    /**
     * 虚拟 NO 注册表（§16 方案 D）。null = 无投射能力的档位：抓取写**空段**，
     * 恢复遇到非空段直接报错（fail-closed，不静默丢身份映射）。
     *
     * 虚拟 NO ↔ EntityId 是**每槽身份**：本类在抓取时写入 `.rt2`、恢复时**先于实体图**
     * 并入（§16 第 6 点）；global.rt2 恒不带该段。
     */
    private val virtualNoRegistry: VirtualCharaNoRegistry? = null,
) {

    /** 恢复结果。失配不是异常——宿主要据此上警告并继续以 Base 状态运行。 */
    sealed interface RestoreOutcome {
        /** 注册表已并、实体已灌。 */
        data object Applied : RestoreOutcome

        /** 三元组失配：未做任何写入，宿主上三语警告（扩展状态可能滞后或丢失）。 */
        data class BindingMismatch(val expected: SidecarBinding, val actual: SidecarBinding) : RestoreOutcome
    }

    /**
     * 抓取实体图载荷：**先对全部投射实体脱水**，再导出快照、再编码。
     * 顺序即不变量 1，不可重排。
     */
    fun captureEntityPayload(): ByteArray {
        for (id in projector.projectedEntities()) {
            projector.syncFromCharaSlot(id)
        }
        return EntityPayloadCodec.encode(kernel.exportSnapshot())
    }

    /**
     * 抓取一个槽的 .rt2 逻辑内容（实体图 + 注册表快照 + 分片）。
     *
     * @param activeChunks 本次激活 Package 的新分片（由 M1-4/M4-3 抽离面产出）
     * @param orphanChunks 未激活 Package 的旧分片（逐字节透传，本类不解释内容）
     * @throws IllegalArgumentException 同一 packageId 同时出现在两组，或任一组内重复
     */
    fun captureBundle(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk> = emptyList(),
        orphanChunks: List<PackageChunk> = emptyList(),
    ): SidecarSaveBundle = SidecarSaveBundle(
        binding = binding,
        registrySnapshot = pkgRegistry.encode(),
        entityGraphPayload = captureEntityPayload(),
        chunks = mergeChunks(activeChunks, orphanChunks),
        virtualNoSnapshot = virtualNoRegistry?.encode() ?: ByteArray(0),
    )

    /**
     * 抓取 global.rt2（跨槽共享，与 global.sav 配对）：**实体载荷恒空**，只带注册表快照
     * 与 Mod GLOBAL 变量分片。写触发 = SAVEGLOBAL（见 PersistenceDomains 路由）。
     */
    fun captureGlobalBundle(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk> = emptyList(),
        orphanChunks: List<PackageChunk> = emptyList(),
    ): SidecarSaveBundle = SidecarSaveBundle(
        binding = binding,
        registrySnapshot = pkgRegistry.encode(),
        entityGraphPayload = ByteArray(0),
        chunks = mergeChunks(activeChunks, orphanChunks),
        // 虚拟 NO 是每槽身份（§7 唯一性范围）：global.rt2 不带该段。
        virtualNoSnapshot = ByteArray(0),
    )

    /**
     * 恢复一个**槽** .rt2：绑定校验 → 并注册表 → 灌实体（顺序即不变量 2/3）。
     *
     * 只用于 saveNN.sav.rt2；global.rt2 的实体载荷恒空（[captureGlobalBundle]），
     * 误喂进来当场拒绝而不是让 codec 抛一句看不懂的「字节不足」——LOADGLOBAL 只读
     * Mod GLOBAL 分片，实体永不跨槽。判据成立的前提：空内核经 [EntityPayloadCodec.encode]
     * 也会写版本头与名表（≥8 字节），故「零长载荷」唯一地标识全局档。
     *
     * 内核非空时 [EntityKernel.restore] 自己拒绝（混档恢复不可达）；本方法不预先清空
     * 内核——「读档前复位」是原版语义且由宿主装载路负责（ADR-005，§8 先复位后解析不动）。
     *
     * @throws com.eratw.port.core.entity.RegistryConflictException 注册表并集冲突（内核未动）
     * @throws IllegalArgumentException 载荷损坏/快照声明未注册（内核预校验先于写入，半灌不可达）
     */
    fun restoreBundle(bundle: SidecarSaveBundle, expectedBinding: SidecarBinding): RestoreOutcome {
        if (!bundle.matchesBinding(expectedBinding)) {
            return RestoreOutcome.BindingMismatch(expectedBinding, bundle.binding)
        }
        require(bundle.entityGraphPayload.isNotEmpty()) {
            "global.rt2 不带实体载荷，不能用 restoreBundle 恢复实体（LOADGLOBAL 只读 Mod GLOBAL 分片）"
        }
        // 读档序（§16 第 6 点）：虚拟 NO 注册表 → 包注册表 → 实体图。
        // 虚拟 NO 是 .sav 角色块里被 vanilla 变量存下的身份；不先恢复它，
        // 角色块里的 NO≥10000 会被当成悬空下标。
        applyVirtualNoSnapshot(bundle.virtualNoSnapshot)
        pkgRegistry.mergeSnapshot(bundle.registrySnapshot)
        kernel.restore(EntityPayloadCodec.decode(bundle.entityGraphPayload))
        return RestoreOutcome.Applied
    }

    /**
     * 并入 `.rt2` 的虚拟 NO 注册表段（空段 = 无投射实体，直接返回）。
     *
     * 宿主未注入注册表却读到非空段 ⇒ 装配缺失，**报错而不是静默丢映射**：丢掉等于
     * 「子嗣的 NO 在新一局里全部悬空」，比读档失败更糟。
     */
    private fun applyVirtualNoSnapshot(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val registry = requireNotNull(virtualNoRegistry) {
            "sidecar 带虚拟 NO 注册表段，但宿主未注入 VirtualCharaNoRegistry（Mod 档装配缺失）"
        }
        registry.mergeSnapshot(bytes)
    }

    /**
     * 只并注册表快照（孤儿片/全局 bin 的装载面用；不灌实体）。
     * 并集独立成立，故与 [restoreBundle] 分开暴露。
     */
    fun mergeRegistrySnapshot(bytes: ByteArray) {
        pkgRegistry.mergeSnapshot(bytes)
    }

    private fun mergeChunks(
        active: List<PackageChunk>,
        orphan: List<PackageChunk>,
    ): List<PackageChunk> {
        val activeIds = HashSet<String>(active.size * 2)
        for (chunk in active) {
            require(activeIds.add(chunk.packageId)) { "激活分片 packageId 重复: ${chunk.packageId}" }
        }
        val orphanIds = HashSet<String>(orphan.size * 2)
        for (chunk in orphan) {
            require(orphanIds.add(chunk.packageId)) { "孤儿分片 packageId 重复: ${chunk.packageId}" }
            require(chunk.packageId !in activeIds) {
                "packageId [${chunk.packageId}] 同时在激活组与孤儿组——一个包要么重新抓取要么原样透传，不能既新又旧"
            }
        }
        return active + orphan
    }
}