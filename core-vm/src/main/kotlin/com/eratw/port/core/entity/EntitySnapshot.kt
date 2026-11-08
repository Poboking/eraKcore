package com.eratw.port.core.entity

/**
 * 实体内核的**中间快照形态**（M4-1；[EntityKernel.exportSnapshot] 产出、
 * [EntityPayloadCodec] 编码为 .rt2 实体段字节、[EntityKernel.restore] 灌回）。
 *
 * 三层职责分离：快照（数据形态，可遍历可重映射）↔ 字节（持久形态，纯函数编解码）
 * ↔ 内核（运行形态，只有 restore 一个入口）。SaveService 抓取/恢复都经快照，
 * 不直接碰内核内部数组。
 *
 * ## 稀疏口径
 *
 * 槽表含**全部已触碰槽**（0..高水位，state=0 的空槽也在）——空槽的 generation 必须随
 * 快照走：destroy 时递增的 generation 若不持久，restore 后槽位回 0，旧 EntityId 复活
 * （ABA 串号）。字段列只含**非默认值**（int ≠ 0 / str ≠ null），restore 后未列出的
 * 槽/字段读 0/""（与内核「未写读默认」口径一致）。
 *
 * ## 跨设备重映射（v6.3-c P0-1 方案 B：持久实体引用三面）
 *
 * [remapped] 覆盖载荷内的**两面 + 槽身份**（第三面 entityRefs 变量槽归 VM 变量层）：
 * 1. 槽表的 pkgShortId（实体自身的归属包）；
 * 2. 声明为 [FieldSnapshot.isEntityRef] 的整型字段值（[EntityIdRemapTable.remap]：
 *    只换 pkg 段、未映射包原样、非良构拒绝）；
 * 3. 关系边两端点（同上）。
 */
class EntitySnapshot(
    /** 活槽表（槽序即快照序；restore 按此灌回）。 */
    val slots: List<SlotSnapshot>,
    /** 原型名表（归一，槽表的下标引用）。 */
    val archetypes: List<String>,
    /** 字段快照（稀疏值）。 */
    val fields: List<FieldSnapshot>,
    /** 关系类型名表（归一，边的下标引用）。 */
    val relationKinds: List<String>,
    /** 关系边（端点是完整 EntityId）。 */
    val edges: List<EdgeSnapshot>,
) {

    /** 一个活槽：槽号 + 三段身份 + 生命周期状态。 */
    class SlotSnapshot(
        val slot: Int,
        val generation: Int,
        val pkgShortId: Int,
        val state: Int,
        val archetypeIdx: Int,
    )

    /** 一个字段：声明（名/类型/entity-ref 旗标）+ 稀疏值（槽号 → 值，槽号升序）。 */
    class FieldSnapshot(
        val name: String,
        val type: ComponentFieldType,
        val isEntityRef: Boolean,
        val intValues: List<IntLongPair> = emptyList(),
        val strValues: List<IntStringPair> = emptyList(),
    )

    /**
     * 一条关系边（端点是完整 EntityId，[value] 是边值/关系度）。
     *
     * 值随边持久化：原生 RELATION 格是 Long 值，关系投影协议退场回写靠它才无损
     * （只存「边存在」记不住 100→200）。缺省 0 = 无值语义的旧调用方口径。
     */
    class EdgeSnapshot(val from: Long, val kindIdx: Int, val to: Long, val value: Long = 0L)

    /** (槽号, long 值) 对（避免 Pair 的 first/second 语义漂移）。 */
    class IntLongPair(val slot: Int, val value: Long)

    /** (槽号, 字符串值) 对。 */
    class IntStringPair(val slot: Int, val value: String)

    /**
     * 按重映射表产出新快照（本快照不变；不可变面跨设备导入与读档共用）。
     * [EntityIdRemapTable.remap] 的非良构拒绝在此原样传播（声明为 entity-ref 的
     * 槽里出现非实体值 = Mod 违约，拒绝导入）。
     */
    fun remapped(table: EntityIdRemapTable): EntitySnapshot {
        if (table.isEmpty) return this
        val newSlots = slots.map {
            val newPkg = table.targetShortIdOf(it.pkgShortId) ?: it.pkgShortId
            SlotSnapshot(it.slot, it.generation, newPkg, it.state, it.archetypeIdx)
        }
        val newFields = fields.map { f ->
            if (!f.isEntityRef) {
                f
            } else {
                FieldSnapshot(
                    f.name,
                    f.type,
                    true,
                    f.intValues.map { IntLongPair(it.slot, table.remap(it.value)) },
                    f.strValues,
                )
            }
        }
        // 边值不是引用，重映射只换两端点（值原样带走）。
        val newEdges = edges.map { EdgeSnapshot(table.remap(it.from), it.kindIdx, table.remap(it.to), it.value) }
        return EntitySnapshot(newSlots, archetypes, newFields, relationKinds, newEdges)
    }
}
