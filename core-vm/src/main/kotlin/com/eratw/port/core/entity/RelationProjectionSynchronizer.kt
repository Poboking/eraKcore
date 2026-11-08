package com.eratw.port.core.entity

/** roster 的一行：物理行号 + 稳定列键（[RelationProjectionSynchronizer] 的 addressing 单位）。 */
class RosterRow(val charIndex: Int, val columnKey: Long)

/**
 * 宿主桥：原生 RELATION 矩阵的格子读写 + roster 行表 + 行的图身份
 * （M5-1 接缝，M5-2 接真实 VariableStore/CharacterTable；测试用 fake）。
 *
 * **列键的物理含义归宿主**：引擎既有口径是关键字反查表给出的整数
 * （ConstantData.cs:743-754 把角色 Name/Callname/Nickname 映射到 CSV NO），
 * 投射实体走 VirtualCharaNoRegistry 的虚拟 NO。本桥只要求列键在**本次投射期内稳定且唯一**，
 * 不要求它是哪一种编号——同步器不解释业务，也不替换 RELATION 索引语义（§13「明确不做」）。
 */
interface RelationProjectionBridge {
    /** 原生格默认值（Config.RelationDef；CharacterData.cs:163-167 新行全填此值）。 */
    val relationDefault: Long

    /** 读一格；[targetKey] 是列键（宿主翻译成物理列下标）。 */
    fun readCell(charIndex: Int, targetKey: Long): Long

    /** 写一格。 */
    fun writeCell(charIndex: Int, targetKey: Long, value: Long)

    /**
     * 当前 roster 的**全部**行（原生角色 + 已投射实体），行号升序、列键互异。
     * 未锚的原生行也要列出来——退场清残留与「无锚改动」诊断都靠它才完整。
     */
    fun rosterRows(): List<RosterRow>

    /**
     * 该行在实体图里的身份；无锚实体返回 [EntityIdCodec.NULL]。
     * 生产实现 = 投射映射反查（[ICharaSlotProjector.entityAt]）或原生锚实体注册表。
     */
    fun graphIdentityOf(charIndex: Int): Long
}

/**
 * 关系投影协议（ERA Runtime 2.0 §7 Roster 安全不变量第 3 条；M5-1 核心）。
 *
 * ## 三层与职责
 *
 * ```
 * 原生 RELATION 矩阵  ←→  本同步器  ←→  实体关系图（EntityKernel）
 * ```
 *
 * - **进场**（[onProjected]，投射成功并登记映射之后）：把当前 roster 里**有图身份**的
 *   其它端点与本实体之间的关系边值写进原生格（两个方向各一格），恢复「当前活动角色间的
 *   关系子矩阵」；随后逐格实读存为基线。
 * - **退场**（[onUnprojecting]，**必须在删行之前**）：按基线差分读取投射期间的原生变化，
 *   写回实体图，然后**只清退场者自己的行与列**——不是全表清零。
 * - **图是唯一持久真相**：Child 与非 roster 扩展实体的关系从不进原生矩阵，因此本同步器
 *   一个格子都不碰它，「永不丢失」由构造成立而不是靠小心。
 * - **多实体同时投射**：各自一份基线；某实体退场清完残留后把它的列键从其它在册基线里摘掉
 *   （[dropKeyFromOtherBaselines]），否则别人退场时会把「本协议自己清的残留」当成脚本改动
 *   回写，反而摘掉图里一条好边。
 *
 * ## 为什么替代粗暴「RELATION 残留清零」
 *
 * 全表清零会抹掉原生角色之间的关系（MASTER↔原生角色的恋慕度等），而且清之前不回写的话，
 * 投射期间原版互动函数改出来的关系度（语料实测 `RELATION:A:B` 与 100/200 比大小，
 * ANOTHER_TALK.ERB:102/:186）在退场时直接蒸发。本协议把「清残留」收窄到退场者自己的
 * 行与列，并把清之前的值差分回图。边值随边持久化（ABI-6 实体段 v2）才使这一步无损。
 *
 * ## 原生索引语义不动（§13「明确不做」）
 *
 * 原生 RELATION 是 per-character 一维整型数组 + 关键字反查表
 * （VariableData.cs:3087 `CharaInt1DVariableToken`；ConstantData.cs:743-754 把
 * 角色 Name/Callname/Nickname 映射到 **CSV NO** 作为关键字值）。本同步器**不解释**
 * 列键的物理含义：格子 addressing 全部经 [RelationProjectionBridge]，由宿主按引擎
 * 既有口径翻译（生产 = 关键字反查给出的整数；投射实体 = VirtualCharaNoRegistry 的虚拟 NO）。
 *
 * ## 列键稳定性与行排列
 *
 * 基线按**列键**存，不存行号；退场时重新解析当前行号。所以 SORTCHARA/DELCHARA 引起的
 * 行平移**不需要**本同步器做任何重映射（[ICharaSlotProjector.onCharacterRowsPermuted]
 * 只管投射映射）。前提是宿主给的列键在**本次投射期内稳定**：虚拟 NO 满足
 * （VirtualCharaNoRegistry 只在撤销投射时 retire，行排列不动号）。退场时列键若变了，
 * 当场拒绝而不是按漂移键回写（那会把值写到别人身上）。
 *
 * ## 诚实边界（不静默丢数据）
 *
 * 读得到但写不进图的改动一律进 [RelationWriteBackReport.unwritten]：
 * - `NO_ANCHOR`：对端是原生行但宿主没给它锚实体（M5-2 接线缺口，必须上诊断面）；
 * - `ENTITY_GONE`：对端实体在投射期间被 destroy 了。
 *
 * 基线里的列键若已不在 roster（原版脚本自己 DELCHARA 了那个角色），格子已随行的
 * RELATION 数组消失，无从读取 ⇒ 记进 [RelationWriteBackReport.goneKeys]。
 * 行被引擎直接删掉而没走 [onUnprojecting] 的，走 [forget] 并计 [lostWriteBacks]
 * （原版 DELCHARA 即时语义不动，ADR-005；损失可见，不假装无损）。
 *
 * 另一处原生口径自带的不可区分：边值**恰等于** [RelationProjectionBridge.relationDefault]
 * 时与「无边」在原生矩阵里是同一形态，回写按摘边处理（[writeBack]）。这不是本协议的损失，
 * 是原生格本身只有一个 Long、没有「存在位」。
 *
 * 实例状态（core 禁静态可变全局，AGENTS §2.4）；线程约束由宿主串行化保证。
 *
 * @param projectionKind 投影到原生矩阵的关系类型名（Mod 声明，引擎不猜业务）。
 *   原生格一对端点只有**一个**值，所以只允许一个 kind 投影；声明第二个 kind 会撞格，
 *   由声明事务拒绝——本类构造只收一个，结构上不可能撞。
 *   该 kind 还必须是**有向、无 autoInverse** 的普通边（构造期按内核冻结声明 fail-closed）：
 *   原生 A:B 与 B:A 是两个独立格，无向边只存一份值、自动反边的反向格属于另一种 kind，
 *   两者都会让进场读空/退场折叠 ⇒ 有损。M2 声明事务应在更早的提交期就拒掉这种声明。
 */
class RelationProjectionSynchronizer(
    private val kernel: IEntityKernel,
    private val bridge: RelationProjectionBridge,
    private val projectionKind: String,
) {

    init {
        require(projectionKind.isNotBlank()) { "投影关系类型名为空" }
        // 读内核里冻结的那一份声明，不信调用方递来的副本（副本不一致 = 静默有损）。
        val schema = kernel.relationSchemaOf(projectionKind)
        require(schema != null) {
            "投影关系类型未注册: $projectionKind（声明事务先行，运行期不隐式建关系类型）"
        }
        require(schema.directed) {
            "投影关系类型 $projectionKind 声明为无向——原生 RELATION 的 A:B 与 B:A 是两个独立格，" +
                "无向边只存一份值，退场回写会把两格折成一份（有损）。投影 kind 必须是有向边"
        }
        require(!schema.autoInverse) {
            "投影关系类型 $projectionKind 启用 autoInverse——反向格对应的是 ${schema.inverseName} 那条边" +
                "而不是本 kind，进场会按本 kind 查反向格从而读空（有损）。投影 kind 必须是普通有向边"
        }
    }

    /** 一次退场回写的结果。[isClean] 为假即有改动没进图，宿主必须上诊断面。 */
    class RelationWriteBackReport(
        /** 成功写回图的改动数（建边/改值/按默认值摘边都算一次）。 */
        val applied: Int,
        /** 读得到但写不进图的改动（原因逐条带）。 */
        val unwritten: List<UnwrittenChange>,
        /** 基线里已不在 roster 的列键（格子随行消失，无从读取）。 */
        val goneKeys: List<Long>,
    ) {
        enum class Reason { NO_ANCHOR, ENTITY_GONE }

        class UnwrittenChange(
            val fromKey: Long,
            val toKey: Long,
            val oldValue: Long,
            val newValue: Long,
            val reason: Reason,
        )

        val isClean: Boolean get() = unwritten.isEmpty() && goneKeys.isEmpty()
    }

    private class Baseline(
        val columnKey: Long,
        /** 本实体行上、指向各列键的值。 */
        val outgoing: MutableMap<Long, Long>,
        /** 各列键所属行上、指向本实体列键的值。 */
        val incoming: MutableMap<Long, Long>,
    )

    private val baselines = HashMap<Long, Baseline>()
    private val unwrittenLog = ArrayList<RelationWriteBackReport.UnwrittenChange>()
    private val goneKeyLog = ArrayList<Long>()

    /** 当前登记在册的投射数（诊断/配额面）。 */
    val activeProjections: Int get() = baselines.size

    /** 历次回写里没写进图的改动（累计诊断面；非空即有损，不许静默）。 */
    val unwrittenChanges: List<RelationWriteBackReport.UnwrittenChange> get() = unwrittenLog.toList()

    /** 历次回写里已无从读取的列键数（累计诊断面）。 */
    val goneKeyCount: Int get() = goneKeyLog.size

    /** 最近一次 [onUnprojecting] 的报告（宿主在 unproject 之后读它上屏/上报）。 */
    var lastReport: RelationWriteBackReport? = null
        private set

    /** 行被引擎直接删掉、没走退场回写的次数（ADR-005 即时语义下的可见损失）。 */
    var lostWriteBacks: Int = 0
        private set

    /**
     * 进场：恢复关系子矩阵并登记基线。由 [RosterPinner.project] 在映射登记后调用。
     *
     * @throws IllegalArgumentException 实体非良构 / 行不在 roster / 重复登记 / 列键重复
     */
    fun onProjected(entityId: Long, charIndex: Int) {
        require(EntityIdCodec.isWellFormed(entityId)) { "进场实体非良构: ${entityId.toString(16)}" }
        require(charIndex >= 0) { "进场行号为负: $charIndex" }
        require(entityId !in baselines) {
            "重复进场登记（EntityId=${entityId.toString(16)}）——基线会被覆盖，投射期间的变化就查不出来了"
        }
        val rows = validatedRows()
        val me = rows.firstOrNull { it.charIndex == charIndex }
            ?: throw IllegalArgumentException("进场行 $charIndex 不在 roster 里（宿主桥与角色表不同步）")
        // 预校验先于任何写格：身份非良构时一格都不写（半投影状态不可达）。
        val identities = HashMap<Int, Long>(rows.size * 2)
        for (row in rows) {
            val identity = bridge.graphIdentityOf(row.charIndex)
            require(identity == EntityIdCodec.NULL || EntityIdCodec.isWellFormed(identity)) {
                "roster 行 ${row.charIndex} 的图身份非良构: ${identity.toString(16)}"
            }
            identities[row.charIndex] = identity
        }
        val default = bridge.relationDefault
        // 1. 投影：只对有图身份的其它端点写格（两个方向）。无锚原生行保持默认。
        for (other in rows) {
            if (other.charIndex == charIndex || other.columnKey == me.columnKey) continue
            val identity = identities.getValue(other.charIndex)
            if (identity == EntityIdCodec.NULL) continue
            bridge.writeCell(charIndex, other.columnKey, graphValueOr(entityId, identity, default))
            bridge.writeCell(other.charIndex, me.columnKey, graphValueOr(identity, entityId, default))
        }
        // 2. 基线：逐格实读（含无锚列），退场按差分回写而不是按「我写过什么」回写。
        val outgoing = HashMap<Long, Long>(rows.size * 2)
        val incoming = HashMap<Long, Long>(rows.size * 2)
        for (other in rows) {
            if (other.charIndex == charIndex || other.columnKey == me.columnKey) continue
            outgoing[other.columnKey] = bridge.readCell(charIndex, other.columnKey)
            incoming[other.columnKey] = bridge.readCell(other.charIndex, me.columnKey)
        }
        baselines[entityId] = Baseline(me.columnKey, outgoing, incoming)
    }

    /**
     * 退场：差分回写 + 清自己的行与列。**必须在删行之前调用**（删了就抓不到格子了），
     * 由 [RosterPinner.unproject] 在脱水之后、releaseRow 之前调用。
     *
     * 脱水先于本步：`__native_chara__` 要拿到**未清残留**的原始行快照；本步清完残留
     * 再删行，两边都不丢。
     *
     * @throws IllegalArgumentException 无基线（进场没登记/已回写）/ 行不在 roster / 列键漂移
     */
    fun onUnprojecting(entityId: Long, charIndex: Int): RelationWriteBackReport {
        // 预校验先于摘基线：拒绝时状态原样留着（与 EntityKernel.restore 同纪律）。
        val baseline = baselines[entityId]
            ?: throw IllegalArgumentException(
                "退场回写无基线（EntityId=${entityId.toString(16)}）——进场登记缺失或已回写过",
            )
        val rows = validatedRows()
        val me = rows.firstOrNull { it.charIndex == charIndex }
            ?: throw IllegalArgumentException("退场行 $charIndex 不在 roster 里（删行早于回写，时序违约）")
        require(me.columnKey == baseline.columnKey) {
            "退场时列键漂移（进场 ${baseline.columnKey} → 现在 ${me.columnKey}）——拒绝按漂移键回写"
        }
        baselines.remove(entityId)
        val default = bridge.relationDefault
        val byKey = HashMap<Long, RosterRow>(rows.size * 2)
        for (row in rows) {
            if (row.charIndex == charIndex || row.columnKey == baseline.columnKey) continue
            byKey[row.columnKey] = row
        }
        // 基线键 ∪ 当前键：投射期间新加入 roster 的角色也要查（它的格基线按默认值算）。
        val keys = LinkedHashSet<Long>(baseline.outgoing.size + byKey.size)
        keys.addAll(baseline.outgoing.keys)
        keys.addAll(baseline.incoming.keys)
        keys.addAll(byKey.keys)
        var applied = 0
        val unwritten = ArrayList<RelationWriteBackReport.UnwrittenChange>()
        val gone = ArrayList<Long>()
        for (key in keys) {
            val other = byKey[key]
            if (other == null) {
                gone += key
                continue
            }
            val baseOut = baseline.outgoing[key] ?: default
            val baseIn = baseline.incoming[key] ?: default
            val nowOut = bridge.readCell(charIndex, key)
            val nowIn = bridge.readCell(other.charIndex, baseline.columnKey)
            val identity = bridge.graphIdentityOf(other.charIndex)
            if (nowOut != baseOut) {
                applied += writeBack(entityId, identity, baseline.columnKey, key, baseOut, nowOut, unwritten)
            }
            if (nowIn != baseIn) {
                applied += writeBack(identity, entityId, key, baseline.columnKey, baseIn, nowIn, unwritten)
            }
        }
        // 清残留：只清退场者自己的行与列（自反格不碰）。别人的行之间的关系一格不动。
        for (other in rows) {
            if (other.charIndex == charIndex || other.columnKey == baseline.columnKey) continue
            bridge.writeCell(other.charIndex, baseline.columnKey, default)
            bridge.writeCell(charIndex, other.columnKey, default)
        }
        dropKeyFromOtherBaselines(baseline.columnKey)
        unwrittenLog.addAll(unwritten)
        goneKeyLog.addAll(gone)
        val report = RelationWriteBackReport(applied, unwritten, gone)
        lastReport = report
        return report
    }

    /**
     * 行被引擎直接删掉（原版 DELCHARA 等）时丢弃基线：格子已随行消失，回写不可达。
     * 由 [RosterPinner.onCharacterRowDeleted] 调用；损失计入 [lostWriteBacks]，不假装无损。
     */
    fun forget(entityId: Long): Boolean {
        val baseline = baselines.remove(entityId) ?: return false
        dropKeyFromOtherBaselines(baseline.columnKey)
        lostWriteBacks++
        return true
    }

    /**
     * 把 [columnKey] 从其它在册基线里摘掉（调用前本实体的基线已摘）。
     *
     * 退场清残留把该列键的两格写成了默认值；若别人的基线还留着这两格，他退场时会把
     * **本协议自己清的残留**当成脚本改动回写，从而摘掉图里一条好边（自伤式数据丢失）。
     * 这两格在本次退场的差分里已经回写过，摘掉不丢任何脚本改动。
     * [forget] 同理：行已被引擎删掉，格子无从读取，损失只在 [lostWriteBacks] 记一次，
     * 不按在册投射数重复报 goneKeys。
     */
    private fun dropKeyFromOtherBaselines(columnKey: Long) {
        for (baseline in baselines.values) {
            baseline.outgoing.remove(columnKey)
            baseline.incoming.remove(columnKey)
        }
    }

    /** 写回一条改动；写不进图时记诊断并返回 0，不抛（退场流程不能因诊断卡住删行）。 */
    private fun writeBack(
        from: Long,
        to: Long,
        fromKey: Long,
        toKey: Long,
        oldValue: Long,
        newValue: Long,
        unwritten: MutableList<RelationWriteBackReport.UnwrittenChange>,
    ): Int {
        if (from == EntityIdCodec.NULL || to == EntityIdCodec.NULL) {
            unwritten += RelationWriteBackReport.UnwrittenChange(
                fromKey, toKey, oldValue, newValue, RelationWriteBackReport.Reason.NO_ANCHOR,
            )
            return 0
        }
        if (!kernel.exists(from) || !kernel.exists(to)) {
            unwritten += RelationWriteBackReport.UnwrittenChange(
                fromKey, toKey, oldValue, newValue, RelationWriteBackReport.Reason.ENTITY_GONE,
            )
            return 0
        }
        if (newValue == bridge.relationDefault) {
            // 原生默认值 = 无关系：图里边跟着摘（没有就跳过，幂等）。
            return if (kernel.removeRelation(from, projectionKind, to)) 1 else 0
        }
        if (!kernel.hasRelation(from, projectionKind, to)) {
            check(kernel.addRelation(from, projectionKind, to)) {
                "回写建边失败（类型 $projectionKind 未注册或端点不存在）"
            }
        }
        check(kernel.setRelationValue(from, projectionKind, to, newValue)) {
            "回写边值失败（类型 $projectionKind，边刚建却不在）"
        }
        return 1
    }

    private fun graphValueOr(from: Long, to: Long, default: Long): Long =
        if (kernel.hasRelation(from, projectionKind, to)) kernel.getRelationValue(from, projectionKind, to) else default

    /** roster 行表校验：行号非负且互异、列键互异（重复即宿主桥损坏，fail-closed）。 */
    private fun validatedRows(): List<RosterRow> {
        val rows = bridge.rosterRows()
        val seenRows = HashSet<Int>(rows.size * 2)
        val seenKeys = HashSet<Long>(rows.size * 2)
        for (row in rows) {
            require(row.charIndex >= 0) { "roster 行号为负: ${row.charIndex}" }
            require(seenRows.add(row.charIndex)) { "roster 行号重复: ${row.charIndex}" }
            require(seenKeys.add(row.columnKey)) {
                "roster 列键重复: ${row.columnKey}（行 ${row.charIndex}）——列键必须是稳定唯一身份，不得用会撞号的口径"
            }
        }
        return rows
    }
}