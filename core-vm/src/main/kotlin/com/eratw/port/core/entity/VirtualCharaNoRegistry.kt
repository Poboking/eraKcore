package com.eratw.port.core.entity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 虚拟角色编号注册表（§16 主方案 D：虚拟 NO ≥ 10000 + 角色下标解析层）。
 *
 * ## 为什么有这一层
 *
 * 原版语料把 `(NO:ARG)` 当角色下标用（`CFLAG:(NO:ARG)`、`TALENT:MASTER:恋人 = NO:ARG`
 * 这类惯用法），依赖「NO == 行号」的巧合。扩展实体（子嗣/路人）不能占用这套编号：
 * 语料还有两道硬墙 —— `人物数量上限 = 200`（DIM.ERH:209）与 `EX_CHARANUM = 301`
 * （DIM.ERH:211）。方案 D 不跟语料妥协：给投射实体分配**虚拟 NO**，在 VM 的角色下标
 * 解析入口加一层间接（与函数 dispatch、Handle Table 同一手法），语料一行不用改。
 *
 * ## 不变量
 *
 * - 分配从 [VIRTUAL_NO_BASE] 起**槽内单调递增**；先查 [isNativeCsvNo]，
 *   不与现存 CSV 编号冲突（冲突就继续向上找）；
 * - **永不复用**：[retire] 只解绑、不让 nextCandidate 回退；撤销投射也用 [retire]
 *   （实体若再投射会拿到新号，旧号作废）——这保证「被 vanilla 变量存下的虚拟 NO」
 *   不会在实体消失后被另一个实体冒名；
 * - 同一实体重复 [allocate] 幂等返回同一号（投射重建不该换身份）；
 * - 号只在**本 SaveSlot 身份空间**内有意义（§7 唯一性范围），随 .rt2 持久化
 *   （D-1 落编解码，本文件只冻结数据形状与校验）。
 *
 * **无 Mod 时旁路**：注册表为空时 [lookup] 恒 `null`，解析层对所有原生下标走原分支
 * （§16 的「无 Mod 时完全旁路、golden 逐位不变」）。本类不参与任何热路径状态写入。
 *
 * @param isNativeCsvNo 现存 CSV 编号判定（生产 = CharacterTable 的模板 NO 集合）。
 *   **必须由宿主喂**：注册表不能自己扫 CSV（core 不认识游戏数据装载）。
 */
class VirtualCharaNoRegistry(
    private val isNativeCsvNo: (Int) -> Boolean = { false },
) {
    private val entityToVirtualNo = HashMap<Long, Int>()
    private val virtualNoToEntity = HashMap<Int, Long>()
    private var nextCandidate: Int = VIRTUAL_NO_BASE

    /** 已分配条数（诊断/容量面）。 */
    val size: Int get() = virtualNoToEntity.size

    /**
     * 为 [entityId] 分配/取回虚拟 NO。
     *
     * @throws IllegalArgumentException EntityId 为 NULL（0）—— 未建实体不配号。
     * @throws IllegalStateException 虚拟编号空间耗尽（Int 上界；实际语料不可达，
     *   但 fail-closed 好过回绕成负下标被当成「原生角色」）。
     */
    fun allocate(entityId: Long): Int {
        require(entityId != 0L) { "EntityId 为 NULL（0），不能分配虚拟 NO" }
        entityToVirtualNo[entityId]?.let { return it }
        var candidate = if (nextCandidate < VIRTUAL_NO_BASE) VIRTUAL_NO_BASE else nextCandidate
        while (candidate <= Int.MAX_VALUE &&
            (isNativeCsvNo(candidate) || virtualNoToEntity.containsKey(candidate))
        ) {
            candidate++
        }
        check(candidate <= Int.MAX_VALUE) {
            "虚拟 NO 空间耗尽（候选越过 Int.MAX_VALUE）：已分配 $size 个"
        }
        entityToVirtualNo[entityId] = candidate
        virtualNoToEntity[candidate] = entityId
        nextCandidate = if (candidate == Int.MAX_VALUE) Int.MAX_VALUE else candidate + 1
        return candidate
    }

    /** 该实体当前的虚拟 NO；未分配/已退休 = null。 */
    fun virtualNoOf(entityId: Long): Int? = entityToVirtualNo[entityId]

    /** 虚拟 NO 当前绑定的实体；未绑定 = null。 */
    fun entityOf(virtualNo: Int): Long? = virtualNoToEntity[virtualNo]

    /**
     * 实体离场/销毁：解绑并让号退休（**号永不回到可分配池**）。
     * 返回被退休的号；该实体本来就没有号时返回 null。
     */
    fun retire(entityId: Long): Int? {
        val no = entityToVirtualNo.remove(entityId) ?: return null
        virtualNoToEntity.remove(no)
        return no
    }

    /** 持久化快照（.rt2 注册表段的载荷；二进制形态见 [encode]/[decode]）。 */
    fun snapshot(): Snapshot = Snapshot(virtualNoToEntity.toMap(), nextCandidate)

    /** 注册表持久化形状：虚拟 NO → EntityId，外加续发游标。 */
    data class Snapshot(
        val entries: Map<Int, Long>,
        val nextCandidate: Int,
    )

    /**
     * 序列化为 `.rt2` 的虚拟 NO 注册表段（D-1）。布局（大端）：
     *
     * ```
     * i32  魔数 "VNO1" = 0x564E4F31
     * u16  版本 = 1
     * i32  nextCandidate
     * i32  count
     *   × i32 virtualNo, i64 entityId
     * ```
     */
    fun encode(): ByteArray {
        val snap = snapshot()
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(FORMAT_VERSION)
            out.writeInt(snap.nextCandidate)
            out.writeInt(snap.entries.size)
            for ((no, entityId) in snap.entries) {
                out.writeInt(no)
                out.writeLong(entityId)
            }
        }
        return buf.toByteArray()
    }

    /**
     * 并集：读档 = 本地注册表 ∪ 磁盘快照（口径同
     * [PackageShortIdRegistry.mergeSnapshot]：并集独立成立，不与实体灌入同事务）。
     *
     * **两遍**：先全量校验（号域 / 原生 CSV 冲突 / 同号同实体 / 同实体同号），全部通过
     * 才写入 —— 冲突绝不半并。续发游标取二者较大值（两侧各自保证 > 自身最大已用号，
     * 取大后仍成立，退休号不会被复用）。
     */
    fun merge(other: VirtualCharaNoRegistry) {
        for ((no, entityId) in other.virtualNoToEntity) {
            require(no >= VIRTUAL_NO_BASE) { "并入的虚拟 NO 越界：$no（下界 $VIRTUAL_NO_BASE）" }
            require(!isNativeCsvNo(no)) { "并入的虚拟 NO $no 与现存 CSV 编号冲突" }
            val mine = virtualNoToEntity[no]
            require(mine == null || mine == entityId) { "虚拟 NO $no 分裂：本地 $mine vs 快照 $entityId" }
            val myNo = entityToVirtualNo[entityId]
            require(myNo == null || myNo == no) { "实体 $entityId 分裂：本地 $myNo vs 快照 $no" }
        }
        for ((no, entityId) in other.virtualNoToEntity) {
            virtualNoToEntity[no] = entityId
            entityToVirtualNo[entityId] = no
        }
        if (other.nextCandidate > nextCandidate) nextCandidate = other.nextCandidate
    }

    /** 解码 [encode] 产物并 [merge] 并入（.rt2 恢复面；结构损坏由 [decode] 拒绝）。 */
    fun mergeSnapshot(bytes: ByteArray) {
        merge(decode(bytes, isNativeCsvNo))
    }

    companion object {
        /**
         * 虚拟 NO 起点。取 10000：远高于语料硬墙 `人物数量上限 = 200`（DIM.ERH:209）
         * 与 `EX_CHARANUM = 301`（DIM.ERH:211），同时仍让投影实体的 NO 一眼可辨。
         */
        const val VIRTUAL_NO_BASE: Int = 10_000

        /** 魔数 "VNO1"（虚拟 NO 注册表段，D-1）。 */
        const val MAGIC: Int = 0x564E4F31

        const val FORMAT_VERSION: Int = 1

        /** 条目数上界（2^20）：引擎实际实体量级 10^3，留足余量同时挡畸形产物。 */
        const val MAX_ENTRIES: Int = 1 shl 20

        /**
         * 从 [encode] 产物重建。结构/边界全量校验（长度 / 魔数 / 版本 / 条目数 / 号域 /
         * NULL 实体 / 重复 / 尾部多余字节），之后走 [restore] 的语义校验
         * （原生 CSV 冲突、续发游标 > 已用最大值）。
         *
         * 任何损坏抛 [IllegalArgumentException]，不猜、不修复。
         */
        fun decode(
            bytes: ByteArray,
            isNativeCsvNo: (Int) -> Boolean = { false },
        ): VirtualCharaNoRegistry {
            fun fail(msg: String): Nothing = throw IllegalArgumentException("虚拟 NO 注册表快照损坏: $msg")
            if (bytes.size < 14) fail("长度不足: ${bytes.size}")
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) fail("魔数不符")
            val version = input.readUnsignedShort()
            if (version != FORMAT_VERSION) fail("版本 $version 不支持（本引擎 v$FORMAT_VERSION）")
            val nextCandidate = input.readInt()
            val count = input.readInt()
            if (count < 0 || count > MAX_ENTRIES) fail("条目数越界: $count")
            if (nextCandidate < VIRTUAL_NO_BASE) fail("续发游标越界: $nextCandidate")
            val entries = HashMap<Int, Long>(count * 2)
            repeat(count) {
                val no = input.readInt()
                val entityId = input.readLong()
                if (no < VIRTUAL_NO_BASE) fail("虚拟 NO 越界: $no")
                if (entityId == 0L) fail("虚拟 NO $no 绑定 NULL 实体")
                if (entries.put(no, entityId) != null) fail("虚拟 NO 重复: $no")
            }
            if (input.available() != 0) fail("尾部多余字节")
            return restore(Snapshot(entries, nextCandidate), isNativeCsvNo)
        }

        /**
         * 从 .rt2 快照恢复（读档序：先注册表、后 .sav 角色块 —— §16 第 6 点）。
         *
         * 逐项 fail-closed：越界号 / NULL 实体 / 一个实体多号 / 号与原生 CSV 冲突 /
         * 续发游标不大于已用最大值，一律拒绝恢复，不猜、不修复。
         */
        fun restore(
            snapshot: Snapshot,
            isNativeCsvNo: (Int) -> Boolean = { false },
        ): VirtualCharaNoRegistry {
            require(snapshot.nextCandidate >= VIRTUAL_NO_BASE) {
                "虚拟 NO 续发游标越界：${snapshot.nextCandidate}（下界 $VIRTUAL_NO_BASE）"
            }
            val registry = VirtualCharaNoRegistry(isNativeCsvNo)
            var maxUsed = VIRTUAL_NO_BASE - 1
            for ((no, entityId) in snapshot.entries) {
                require(no >= VIRTUAL_NO_BASE) { "持久化虚拟 NO 越界：$no（下界 $VIRTUAL_NO_BASE）" }
                require(entityId != 0L) { "持久化虚拟 NO $no 绑定 NULL 实体" }
                require(!isNativeCsvNo(no)) { "持久化虚拟 NO $no 与现存 CSV 编号冲突" }
                require(registry.virtualNoToEntity[no] == null) { "持久化虚拟 NO 重复：$no" }
                require(registry.entityToVirtualNo[entityId] == null) {
                    "同一实体被持久化了两个虚拟 NO：${registry.entityToVirtualNo[entityId]} 与 $no"
                }
                registry.virtualNoToEntity[no] = entityId
                registry.entityToVirtualNo[entityId] = no
                if (no > maxUsed) maxUsed = no
            }
            require(snapshot.nextCandidate > maxUsed) {
                "虚拟 NO 续发游标 ${snapshot.nextCandidate} 不大于已用最大值 $maxUsed（会复用已退休的号）"
            }
            registry.nextCandidate = snapshot.nextCandidate
            return registry
        }
    }
}

/**
 * 角色下标的**唯一解析入口**（§16 D 核心；接线面 = D-2/MR-2a-2 的访问点收敛）。
 *
 * ```
 * resolveCharaIndex(v) = if (v < CHARANUM) v else registry.rowOf(v) ?: OoR
 * ```
 *
 * 语义与不做间址时逐位一致：
 * - `0 <= v < charaNum` → 原值（原生角色行，行为零变化）；
 * - 负数 / 超过 Int 域 → [OOR]（原版越界，调用点照旧抛 `OoRCharaVarArg`）；
 * - `v >= charaNum` 且注册表为空 → [OOR]（无 Mod 时的完全旁路：只多一次比较）；
 * - `v >= charaNum` 且命中虚拟 NO → 实体的**当前投射行**；未投射/行失效 → [OOR]
 *   （§16 边界：实体未投射时被 vanilla 存下的虚拟 NO 解析失败，与悬空下标同语义）。
 *
 * [rowOfEntity] 由宿主桥提供（生产 = `ICharaSlotProjector.charIndexOf`）。解析层不持有
 * 投射状态，也不做懒投影 —— 投影/退场只允许在 VM 让出控制权的安全边界发生（§7）。
 */
class VirtualCharaIndexResolver(
    private val registry: VirtualCharaNoRegistry,
    private val rowOfEntity: (Long) -> Int,
) {
    /**
     * 解析角色下标。返回行号或 [OOR]。
     *
     * [charaNum] 是解析时刻的 `CHARANUM`（角色行数）；负数非法。
     */
    fun resolve(raw: Long, charaNum: Int): Int {
        require(charaNum >= 0) { "CHARANUM 不能为负：$charaNum" }
        if (raw < 0L) return OOR
        if (raw < charaNum) return raw.toInt()
        if (raw > Int.MAX_VALUE) return OOR
        val entityId = registry.entityOf(raw.toInt()) ?: return OOR
        val row = rowOfEntity(entityId)
        return if (row in 0 until charaNum) row else OOR
    }

    companion object {
        /** 越界哨兵（行号恒非负；调用点据此抛官方 `OoRCharaVarArg`）。 */
        const val OOR: Int = -1
    }
}
