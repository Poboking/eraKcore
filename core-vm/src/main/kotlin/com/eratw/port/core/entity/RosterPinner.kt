package com.eratw.port.core.entity

/**
 * 宿主角色行操作桥（M5-1 接缝，M5-2 接真实 VariableStore/CharacterTable；测试用 fake）。
 *
 * **数据搬运归宿主**：`__native_chara__` 组件快照的 schema 形态由宿主声明侧决定
 * （哪些 CSV 列进组件字段），本桥只约定**调用时序**，不知道任何列名 —— 业务零耦合
 * 铁律（EntityContracts KDoc）在桥面上同样成立。
 *
 * 实现方注意（M5-2 接线时逐条核对）：
 * - [allocateRow]：ADDVOIDCHARA/按模板 ADDCHARA 语义（新行在尾部；失败 -1）；
 * - [releaseRow]：deleteCharacter + **引擎已知指针清扫**（TARGET 全数组/ASSI/PLAYER
 *   于 deletedPos 置默认、大于者减 1）。**RELATION 残留不在这里清**：那是
 *   [RelationProjectionSynchronizer] 的 scoped 职责（先回写图、再只清退场者自己的行与列）；
 *   桥若顺手全表清零，会抹掉原生角色之间的关系（§7 关系投影协议替代粗暴清零）；
 * - [pourFromEntity]/[captureToEntity]：注水/脱水（`__native_chara__` 组件快照 ↔ 活行）。
 */
interface RosterBridge {

    /** 分配一个原生角色行（尾部追加）；行满返回 -1。 */
    fun allocateRow(): Int

    /** 删除角色行（含引擎指针清扫）。 */
    fun releaseRow(charIndex: Int)

    /** 注水：实体组件快照 → 活行（登场时；[project] 的落库步）。 */
    fun pourFromEntity(entityId: Long, charIndex: Int)

    /** 脱水：活行数据 → 实体组件快照（退场与读档前；[unproject]/[syncFromCharaSlot] 的抓取步）。 */
    fun captureToEntity(entityId: Long, charIndex: Int)
}

/**
 * Active Roster Pinning 的核心实现（M5-1；[ICharaSlotProjector] 的落点）。
 *
 * ## 本类管什么、不管什么
 *
 * - **管**：EntityId ↔ charIndex 投射映射的生命周期（双向、幂等、平移、清扫钩子）
 *   与注水/脱水的**调用时序**（退场先脱水后删行；读档前全量脱水）；
 * - **不管**：行数据的 schema 形态（[RosterBridge] 归宿主）、删除行的引擎指针清扫
 *   细节（[RosterBridge.releaseRow] 的实现义务）、活动调用栈禁止重排的 VM 侧闸
 *   （M5-2 的转移边界检查，Mutation Boundary 归蓝图 §7）。
 *
 * ## 平移口径（行删除后其余投射实体）
 *
 * 引擎行删除使大于 deletedPos 的行号全部左移 1 —— 投射映射同步平移
 * （[onCharacterRowDeleted] / [unproject] 共用同一 [shiftAfter]）。
 * Mod 引用铁律（接口 KDoc）：持久引用只存 EntityId；本映射表就是「用前现换 charIndex」
 * 的唯一换算点，映射错位 = 串号，平移与删除钩子由此 fail-closed（未知行号拒绝）。
 * ## 关系投影协议的时序由本类强制
 *
 * [relations] 非 null 时，进场登记挂在 [project] 的映射登记之后、退场回写挂在
 * [unproject] 的**脱水之后删行之前**、引擎直接删行挂在 [onCharacterRowDeleted]。
 * 协议不靠宿主「记得调」——行增删只有这一个出口，挂在这里就不可能漏（§7 第 3 条）。
 * 退场报告经 `relations.lastReport` 取（非 clean 即有改动没进图，宿主上诊断面）。
 *
 * @param relations 关系投影同步器；null = 该宿主不投射关系（纯 Base 运行，旁路零开销）。
 */
class RosterPinner(
    private val kernel: IEntityKernel,
    private val bridge: RosterBridge,
    private val relations: RelationProjectionSynchronizer? = null,
) : ICharaSlotProjector {

    private val entityToChara = HashMap<Long, Int>()
    private val charaToEntity = HashMap<Int, Long>()

    override fun project(entityId: Long): Int {
        entityToChara[entityId]?.let { return it } // 幂等：已投射返回当前行号
        if (!kernel.exists(entityId)) return -1
        val row = bridge.allocateRow()
        if (row < 0) return -1
        bridge.pourFromEntity(entityId, row)
        entityToChara[entityId] = row
        charaToEntity[row] = entityId
        relations?.onProjected(entityId, row)
        return row
    }

    override fun unproject(entityId: Long): Boolean {
        val row = entityToChara[entityId] ?: return false
        // 时序铁律：先脱水（活行 → 实体组件，拿到未清残留的原始行），再关系回写 + 清残留，
        // 最后删行（删了就抓不到格子了）。三步顺序不可重排。
        bridge.captureToEntity(entityId, row)
        relations?.onUnprojecting(entityId, row)
        bridge.releaseRow(row)
        entityToChara.remove(entityId)
        charaToEntity.remove(row)
        shiftAfter(row)
        return true
    }

    override fun charIndexOf(entityId: Long): Int = entityToChara[entityId] ?: -1

    override fun entityAt(charIndex: Int): Long = charaToEntity[charIndex] ?: EntityIdCodec.NULL

    override fun projectedEntities(): LongArray = entityToChara.keys.toLongArray().also { it.sort() }

    override fun syncFromCharaSlot(entityId: Long) {
        val row = entityToChara[entityId] ?: return
        bridge.captureToEntity(entityId, row)
    }

    override fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray) {
        val newEntityToChara = HashMap<Long, Int>(entityToChara.size * 2)
        for ((entity, oldRow) in entityToChara) {
            val newRow = oldToNewIndexMap.getOrNull(oldRow)
            require(newRow != null && newRow!! >= 0) {
                "行排列钩子把已投射实体（EntityId=${entity.toString(16)}，行 $oldRow）映射到 -1" +
                    "——先 unproject 再排列，或修正排列表（fail-closed）"
            }
            require(!newEntityToChara.containsValue(newRow)) {
                "行排列钩子把两个已投射实体折到同一行 $newRow——排列表损坏，拒绝"
            }
            newEntityToChara[entity] = newRow
        }
        rewrite(newEntityToChara)
    }

    override fun onCharacterRowDeleted(deletedIndex: Int) {
        val entity = charaToEntity.remove(deletedIndex)
        if (entity != null) {
            entityToChara.remove(entity)
            // 行已被引擎删掉，格子无从读取：丢基线并计损失（ADR-005 即时语义，不假装无损）。
            relations?.forget(entity)
        }
        shiftAfter(deletedIndex)
    }

    /** 大于 [deletedIndex] 的行号左移 1（两向映射同步重建）。 */
    private fun shiftAfter(deletedIndex: Int) {
        rewrite(
            entityToChara.entries.associate { (entity, row) ->
                entity to if (row > deletedIndex) row - 1 else row
            },
        )
    }

    private fun rewrite(mapping: Map<Long, Int>) {
        entityToChara.clear()
        charaToEntity.clear()
        for ((entity, row) in mapping) {
            entityToChara[entity] = row
            charaToEntity[row] = entity
        }
    }

    /** 当前投射实体数（诊断/配额面）。 */
    val projectedCount: Int get() = entityToChara.size
}
