package com.eratw.port.core.entity

import java.util.Locale

/**
 * 实体内核实现（M4-1；[IEntityKernel] 的唯一后端，双栖内置函数 ENTITY_* 族的落点）。
 *
 * ## 存储形态：SoA 列式 + Handle Table（蓝图 §7：「拒绝 HashMap-of-HashMap 装箱」）
 *
 * - **Handle Table**：[generations]/[states]/[archetypes] 三条数组与槽位平行 ——
 *   [EntityIdCodec.handleSlotOf] 直接数组寻址，存活判定 = 槽在界内 &&
 *   generation 全位域比对（悬空/复用引用按不存在处理，接口 KDoc 口径）；
 * - **组件列**：字段名（大写归一）→ 列（LongArray / Array<String?>），列与槽空间平行、
 *   按需建列（注册字段很多但实 touches 的少）；读路径 = 一次 hash + 一次数组读，
 *   不是 per-entity Map；
 * - **关系边**：出边/入边两张邻接表（实体图是小图，节点 = 有关系的实体数）。
 *
 * ## 声明事务先行（ABI-5 运行期只读冻结快照）
 *
 * schema / 关系类型 / 原型全部由构造注入（声明事务 [com.eratw.port.core.patch.DeclarationRegistry]
 * 提交后的冻结快照），运行期**不隐式建列、不隐式建关系类型**：
 * 未注册字段读写失败（int 失败哨兵 0 / str 失败 "" / set 返回 false）、未注册关系 kind
 * 与未注册 archetype 建边/建实体返回 false —— 与 [IEntityKernel] 的 KDoc 口径逐条对应。
 *
 * ## generation 退休（v6.2-c ABI-1 负控面）
 *
 * [EntityIdCodec.MAX_GENERATION] 到顶的槽**永不复用**（进 [retiredSlots]）——递增回绕
 * 会让旧 EntityId 指到新实体（ABA），fail-closed：槽耗尽优于身份串号。
 *
 * 挂载点：实例由宿主挂 `store.aux`（RuntimeAux），本类是纯 JVM 对象、无 Android API、
 * 无静态可变全局（AGENTS §2 铁律 4）。
 */
class EntityKernel(
    schemas: Collection<ComponentSchema>,
    relationshipSchemas: Collection<RelationshipSchema>,
    archetypes: Collection<EntityArchetype>,
    initialCapacity: Int = 64,
) : IEntityKernel {

    // ---- 声明快照（构造期冻结） ----

    /** 字段名（大写归一）→ 声明（类型 + entity-ref 旗标，快照重映射遍历面用）。 */
    private val fieldTypes: Map<String, ComponentFieldSpec>

    /** 关系类型名（大写归一）→ schema。 */
    private val relationSchemas: Map<String, RelationshipSchema>

    /** 自动反向边：kind → 反向 kind（仅 autoInverse 的条目）。 */
    private val inverseOf: Map<String, String>

    /** 原型名（大写归一）集合。 */
    private val archetypeKeys: Set<String>

    init {
        val seenFields = HashMap<String, ComponentFieldSpec>()
        for (s in schemas) {
            for (f in s.fields) {
                val key = f.name.uppercase(Locale.ROOT)
                val prev = seenFields.put(key, f)
                require(prev == null || prev.type == f.type && prev.isEntityRef == f.isEntityRef) {
                    "字段 $key 跨 schema 声明冲突（声明事务应已拦，内核快照构造兜底）"
                }
            }
        }
        fieldTypes = seenFields
        val rel = HashMap<String, RelationshipSchema>()
        val inv = HashMap<String, String>()
        for (r in relationshipSchemas) {
            val key = r.name.uppercase(Locale.ROOT)
            require(rel.put(key, r) == null) { "关系类型 $key 重复（声明事务应已拦）" }
            if (r.autoInverse) {
                val invKey = r.inverseName!!.uppercase(Locale.ROOT)
                // 反向类型自动注册为**派生 schema**（directed、无再折返；去重/级联继承主
                // schema），公开入口 addRelation/removeRelation/hasRelation 对它可写可查
                // —— 否则 inverseName 只活在 writeEdge 内部，公开面查表即拒。
                // 用户显式声明了同名 schema 时保留用户版（无折返死循环风险：autoInverse
                // 只在 addRelation 写一层、writeEdge 不递归）。
                rel.putIfAbsent(
                    invKey,
                    RelationshipSchema(
                        name = r.inverseName!!,
                        directed = true,
                        dedupeEdges = r.dedupeEdges,
                        cascadeOnDestroy = r.cascadeOnDestroy,
                    ),
                )
                inv[key] = invKey
            }
        }
        relationSchemas = rel
        inverseOf = inv
        archetypeKeys = archetypes.mapTo(HashSet()) { it.name.uppercase(Locale.ROOT) }
        require(initialCapacity > 0) { "初始容量必须为正: $initialCapacity" }
    }

    // ---- Handle Table（与槽位平行的 SoA 三条） ----

    /** 槽容量（generations/states/archetypes 与全部已建列的公共长度）。 */
    private var capacity: Int = initialCapacity

    /** 槽 generation（槽分配起不变，destroy 时 +1；退休槽保持顶值不再用）。 */
    private var generations = IntArray(initialCapacity)

    /** 槽的 pkgShortId（create 时登记；query 重建 EntityId 的唯一来源——pkg 不随槽存就丢身份）。 */
    private var pkgBySlot = IntArray(initialCapacity)

    /** 槽生命周期状态；0 = 空槽（[EntityLifecycle.ACTIVE] 起三态）。 */
    private var states = IntArray(initialCapacity)

    /** 槽的 archetype 名（大写归一；空槽 null）。 */
    private var archetypeBySlot = arrayOfNulls<String>(initialCapacity)

    /** 空闲槽栈（destroy 释放、未退休的槽）。 */
    private val freeSlots = ArrayDeque<Int>()

    /** generation 到顶、永不复用的槽（fail-closed：槽耗尽优于身份串号）。 */
    private val retiredSlots = HashSet<Int>()

    /** 已分配（states != 0）实体数（诊断/配额面）。 */
    var liveCount: Int = 0
        private set

    // ---- SoA 组件列（按需建） ----

    private val intColumns = HashMap<String, LongArray>()
    private val strColumns = HashMap<String, Array<String?>>()

    // ---- 关系邻接表 ----

    /**
     * 一条出边：对端 EntityId + 边值。值只存**出边这一份**（入边表只存端点），
     * 两张表不会各持一份值而分叉；无向边单向存储，反向读值走同一条出边。
     */
    private class RelationEdge(val to: Long, var value: Long)

    /** 出边：from → kind → 边列表（保持插入序）。 */
    private val outEdges = HashMap<Long, HashMap<String, ArrayList<RelationEdge>>>()

    /** 入边：to → kind → froms（级联清边与入边查询用；与 outEdges 严格互为镜像）。 */
    private val inEdges = HashMap<Long, HashMap<String, ArrayList<Long>>>()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun create(pkgShortId: Int, archetype: String): Long {
        require(pkgShortId in 1..EntityIdCodec.MAX_PKG_SHORT_ID) {
            "pkgShortId 越域: $pkgShortId（合法 1..${EntityIdCodec.MAX_PKG_SHORT_ID}）"
        }
        val archKey = archetype.uppercase(Locale.ROOT)
        require(archKey in archetypeKeys) { "原型未注册: $archetype" }
        val slot = allocateSlot() ?: return EntityIdCodec.NULL
        // 槽复用必须清组件列残值（列按槽位索引，不清 = 新实体读到被删实体的数据）。
        for (col in intColumns.values) col[slot] = 0L
        for (col in strColumns.values) col[slot] = null
        states[slot] = EntityLifecycle.ACTIVE
        archetypeBySlot[slot] = archKey
        pkgBySlot[slot] = pkgShortId
        liveCount++
        return EntityIdCodec.encode(pkgShortId, generations[slot], slot)
    }

    /** 取一个可用槽（空闲栈优先，否则增长）；无可用（全退休且禁增长？增长无上限）恒成功。 */
    private fun allocateSlot(): Int? {
        freeSlots.removeLastOrNull()?.let { return it }
        val slot = liveCapacityHighWater
        if (slot == capacity) {
            if (capacity >= MAX_CAPACITY) return null
            val newCap = minOf(capacity * 2, MAX_CAPACITY)
            generations = generations.copyOf(newCap)
            states = states.copyOf(newCap)
            archetypeBySlot = archetypeBySlot.copyOf(newCap)
            pkgBySlot = pkgBySlot.copyOf(newCap)
            growAllColumns(newCap)
            capacity = newCap
        }
        liveCapacityHighWater = slot + 1
        return slot
    }

    private var liveCapacityHighWater: Int = 0

    private fun growAllColumns(newCap: Int) {
        for ((k, col) in intColumns) {
            if (col.size < newCap) intColumns[k] = col.copyOf(newCap)
        }
        for ((k, col) in strColumns) {
            if (col.size < newCap) strColumns[k] = col.copyOf(newCap)
        }
    }

    override fun destroy(id: Long): Boolean {
        val slot = slotOf(id) ?: return false
        states[slot] = 0
        archetypeBySlot[slot] = null
        liveCount--
        // 级联清边（双向），再回收槽。
        removeEdgesOf(id)
        if (generations[slot] >= EntityIdCodec.MAX_GENERATION) {
            // 到顶：递增回绕会让旧 id 指到新实体 —— 槽退休，永不复用。
            retiredSlots.add(slot)
        } else {
            generations[slot]++
            freeSlots.addLast(slot)
        }
        return true
    }

    override fun exists(id: Long): Boolean = slotOf(id) != null

    /** 全位域比对取槽号；不存在（悬空/复用/NULL/非良构）返回 null。 */
    private fun slotOf(id: Long): Int? {
        if (!EntityIdCodec.isWellFormed(id) || EntityIdCodec.isNull(id)) return null
        val slot = EntityIdCodec.handleSlotOf(id)
        if (slot >= liveCapacityHighWater) return null
        if (states[slot] == 0) return null
        if (generations[slot] != EntityIdCodec.generationOf(id)) return null
        return slot
    }

    override fun stateOf(id: Long): Int {
        val slot = slotOf(id) ?: return 0
        return states[slot]
    }

    override fun setState(id: Long, state: Int): Boolean {
        if (!EntityLifecycle.isValid(state)) return false
        val slot = slotOf(id) ?: return false
        states[slot] = state
        return true
    }

    // ------------------------------------------------------------------
    // 组件字段（SoA 列）
    // ------------------------------------------------------------------

    override fun getInt(id: Long, field: String): Long {
        val slot = slotOf(id) ?: return 0L
        if (fieldTypes[field.uppercase(Locale.ROOT)]?.type != ComponentFieldType.INT) return 0L
        return intColumns[field.uppercase(Locale.ROOT)]?.get(slot) ?: 0L
    }

    override fun setInt(id: Long, field: String, value: Long): Boolean {
        val slot = slotOf(id) ?: return false
        val key = field.uppercase(Locale.ROOT)
        if (fieldTypes[key]?.type != ComponentFieldType.INT) return false
        val col = intColumns.getOrPut(key) { LongArray(capacity) }
        col[slot] = value
        return true
    }

    override fun getStr(id: Long, field: String): String {
        val slot = slotOf(id) ?: return ""
        val key = field.uppercase(Locale.ROOT)
        if (fieldTypes[key]?.type != ComponentFieldType.STR) return ""
        return strColumns[key]?.get(slot) ?: ""
    }

    override fun setStr(id: Long, field: String, value: String): Boolean {
        val slot = slotOf(id) ?: return false
        val key = field.uppercase(Locale.ROOT)
        if (fieldTypes[key]?.type != ComponentFieldType.STR) return false
        val col = strColumns.getOrPut(key) { arrayOfNulls(capacity) }
        col[slot] = value
        return true
    }

    // ------------------------------------------------------------------
    // 关系边
    // ------------------------------------------------------------------

    override fun hasRelationKind(kind: String): Boolean = relationSchemaOf(kind) != null

    override fun relationSchemaOf(kind: String): RelationshipSchema? =
        relationSchemas[kind.uppercase(Locale.ROOT)]

    override fun addRelation(from: Long, kind: String, to: Long): Boolean {
        val schema = relationSchemas[kind.uppercase(Locale.ROOT)] ?: return false
        if (slotOf(from) == null || slotOf(to) == null) return false
        if (!writeEdge(from, kind, to, schema.dedupeEdges)) return false
        // 自动反向边：走同一 writeEdge（内部路径不再递归），dedupe 口径一致。
        val inverse = inverseOf[kind.uppercase(Locale.ROOT)]
        if (inverse != null) writeEdge(to, inverse, from, schema.dedupeEdges)
        return true
    }

    /** 内部写边（不含 autoInverse 递归）；dedupe 命中返回 false，**不留空表残留**。 */
    private fun writeEdge(from: Long, kind: String, to: Long, dedupe: Boolean, value: Long = 0L): Boolean {
        val key = kind.uppercase(Locale.ROOT)
        // dedupe 先查后写（查在既有表上，不 getOrPut —— 命中即 false，不残留空表）。
        if (dedupe) {
            if (findEdge(from, key, to) != null) return false
            if (relationSchemas[key]?.directed == false) {
                // 无向边：反方向命中同一条边，也算重复。
                val back = inEdges[from]?.get(key)
                if (back != null && to in back) return false
            }
        }
        outEdges.getOrPut(from) { HashMap() }.getOrPut(key) { ArrayList() }.add(RelationEdge(to, value))
        inEdges.getOrPut(to) { HashMap() }.getOrPut(key) { ArrayList() }.add(from)
        return true
    }

    /** 找一条出边；不存在 null（无向边反向问询时查对端的出表，值仍只有一份）。 */
    private fun findEdge(from: Long, key: String, to: Long): RelationEdge? =
        outEdges[from]?.get(key)?.firstOrNull { it.to == to }

    override fun removeRelation(from: Long, kind: String, to: Long): Boolean {
        val key = kind.uppercase(Locale.ROOT)
        if (!relationSchemas.containsKey(key)) return false
        // 无向边单向存储（from→to 一条）：任一方向的摘除请求都按同一条边处理。
        val removed = unlink(from, key, to) ||
            (relationSchemas[key]?.directed == false && unlink(to, key, from))
        // 自动反向边同步摘除（幂等：没有就跳过）。
        inverseOf[key]?.let { unlink(to, it, from) }
        return removed
    }

    /** 摘一条边（双向同步）；不存在返回 false。 */
    private fun unlink(from: Long, key: String, to: Long): Boolean {
        val out = outEdges[from]?.get(key) ?: return false
        val idx = out.indexOfFirst { it.to == to }
        if (idx < 0) return false
        out.removeAt(idx)
        val ins = inEdges[to]?.get(key)
        ins?.remove(from)
        if (out.isEmpty()) {
            outEdges[from]!!.remove(key)
            if (outEdges[from]!!.isEmpty()) outEdges.remove(from)
        }
        if (ins != null && ins.isEmpty()) {
            inEdges[to]!!.remove(key)
            if (inEdges[to]!!.isEmpty()) inEdges.remove(to)
        }
        return true
    }

    override fun hasRelation(from: Long, kind: String, to: Long): Boolean {
        val key = kind.uppercase(Locale.ROOT)
        if (findEdge(from, key, to) != null) return true
        // 无向边单向存储（from→to 一条）：反向问询按同一条边判真。
        // （判空必须在无向检查**之后**——from 无出表不代表无向边不存在。）
        if (relationSchemas[key]?.directed == false) {
            return findEdge(to, key, from) != null
        }
        return false
    }

    override fun getRelationValue(from: Long, kind: String, to: Long): Long {
        val key = kind.uppercase(Locale.ROOT)
        findEdge(from, key, to)?.let { return it.value }
        // 无向边：反向问询读同一条出边上的值（值只存一份，不会分叉）。
        if (relationSchemas[key]?.directed == false) {
            return findEdge(to, key, from)?.value ?: 0L
        }
        return 0L
    }

    override fun setRelationValue(from: Long, kind: String, to: Long, value: Long): Boolean {
        val key = kind.uppercase(Locale.ROOT)
        if (!relationSchemas.containsKey(key)) return false
        findEdge(from, key, to)?.let {
            it.value = value
            return true
        }
        if (relationSchemas[key]?.directed == false) {
            findEdge(to, key, from)?.let {
                it.value = value
                return true
            }
        }
        // 边不存在：不隐式建边（建边是 addRelation 的职责，声明与存在性先于值）。
        return false
    }

    override fun query(archetype: String, state: Int): LongArray {
        require(state == 0 || EntityLifecycle.isValid(state)) { "生命周期状态非法: $state" }
        val archKey = archetype.uppercase(Locale.ROOT)
        require(archKey in archetypeKeys) { "原型未注册: $archetype" }
        val out = ArrayList<Long>(liveCount)
        // 稠密槽序遍历（v6.3-c P1-2：EntityId 升序改判稠密槽序——同 pkg 下槽序即 id 序）。
        for (slot in 0 until liveCapacityHighWater) {
            val st = states[slot]
            if (st == 0) continue
            if (state != 0 && st != state) continue
            if (archetypeBySlot[slot] != archKey) continue
            out += EntityIdCodec.encode(pkgBySlot[slot], generations[slot], slot)
        }
        return out.toLongArray()
    }

    override fun queryRelations(id: Long, kind: String, outgoing: Boolean): LongArray {
        val key = kind.uppercase(Locale.ROOT)
        if (outgoing) {
            val list = outEdges[id]?.get(key) ?: return LongArray(0)
            return LongArray(list.size) { list[it].to }
        }
        val list = inEdges[id]?.get(key) ?: return LongArray(0)
        return list.toLongArray()
    }

    // ------------------------------------------------------------------
    // 内部：级联清边
    // ------------------------------------------------------------------

    /**
     * 清 [id] 为端点的全部边（**无条件全 kind 清**；双向同步）。
     *
     * 裁定（v1，本内核）：悬空端点的边是数据错误（queryRelations 会返回悬空 id，
     * 违反「悬空引用按不存在处理」的接口口径），destroy 直接相连的边一律摘除。
     * [RelationshipSchema.cascadeOnDestroy] 的「保留历史痕迹」语义留真实消费场景
     * 落地后再启用（届时过滤按端点存在性判，槽复用的 generation 比对天然防串号），
     * schema 字段在声明层保留、内核 v1 不消费。
     */
    private fun removeEdgesOf(id: Long) {
        outEdges.remove(id)?.let { out ->
            for ((kind, edges) in out) {
                for (edge in edges) {
                    inEdges[edge.to]?.get(kind)?.remove(id)
                    if (inEdges[edge.to]?.get(kind)?.isEmpty() == true) inEdges[edge.to]?.remove(kind)
                }
            }
        }
        inEdges.remove(id)?.let { ins ->
            for ((kind, froms) in ins) {
                for (from in froms) {
                    // 出边表存的是 RelationEdge（带值），按端点找下标摘，不能 remove(id)。
                    outEdges[from]?.get(kind)?.let { out ->
                        val idx = out.indexOfFirst { it.to == id }
                        if (idx >= 0) out.removeAt(idx)
                        if (out.isEmpty()) outEdges[from]!!.remove(kind)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 快照出入口（M4-1 SaveService 面；三层职责见 EntitySnapshot 的 KDoc）
    // ------------------------------------------------------------------

    /**
     * 抓取当前全部实体状态：**全部已触碰槽**（含空槽的 generation，防旧 id 复活）+
     * 列的非默认值（仅活槽）+ 边全量。SaveService 读档前调用（投射实体的行数据
     * 脱水归 [ICharaSlotProjector.syncFromCharaSlot]，在本调用之前完成）。
     */
    fun exportSnapshot(): EntitySnapshot {
        val archetypeIdx = HashMap<String, Int>()
        val archetypeNames = ArrayList<String>()
        val slotSnaps = ArrayList<EntitySnapshot.SlotSnapshot>(liveCapacityHighWater)
        for (slot in 0 until liveCapacityHighWater) {
            val st = states[slot]
            val arch = archetypeBySlot[slot]
            val idx = if (arch == null) 0 else {
                archetypeIdx.getOrPut(arch) { archetypeNames.size.also { archetypeNames += arch } }
            }
            slotSnaps += EntitySnapshot.SlotSnapshot(slot, generations[slot], pkgBySlot[slot], st, idx)
        }
        val fieldSnaps = ArrayList<EntitySnapshot.FieldSnapshot>(fieldTypes.size)
        for ((name, spec) in fieldTypes) {
            val values = ArrayList<EntitySnapshot.IntLongPair>()
            val strValues = ArrayList<EntitySnapshot.IntStringPair>()
            when (spec.type) {
                ComponentFieldType.INT -> intColumns[name]?.let { col ->
                    for (slot in 0 until liveCapacityHighWater) {
                        if (states[slot] != 0 && col[slot] != 0L) {
                            values += EntitySnapshot.IntLongPair(slot, col[slot])
                        }
                    }
                }
                ComponentFieldType.STR -> strColumns[name]?.let { col ->
                    for (slot in 0 until liveCapacityHighWater) {
                        if (states[slot] != 0 && col[slot] != null) {
                            strValues += EntitySnapshot.IntStringPair(slot, col[slot]!!)
                        }
                    }
                }
            }
            fieldSnaps += EntitySnapshot.FieldSnapshot(name, spec.type, spec.isEntityRef, values, strValues)
        }
        val kindNames = ArrayList<String>(relationSchemas.size)
        val kindIdx = HashMap<String, Int>()
        val edges = ArrayList<EntitySnapshot.EdgeSnapshot>()
        for ((id, kinds) in outEdges) {
            for ((kind, edgesOfKind) in kinds) {
                val idx = kindIdx.getOrPut(kind) { kindNames.size.also { kindNames += kind } }
                for (e in edgesOfKind) edges += EntitySnapshot.EdgeSnapshot(id, idx, e.to, e.value)
            }
        }
        return EntitySnapshot(slotSnaps, archetypeNames, fieldSnaps, kindNames, edges)
    }

    /**
     * 把快照灌回本内核（读档恢复；只允许灌入**空内核**——非空即混档，拒绝）。
     *
     * fail-closed：快照的原型/关系类型/字段未在本内核声明快照里注册、槽重复、
     * 边端点悬空（不在快照实体集内）、entity-ref 值非良构 —— 任一命中拒绝，
     * 内核保持灌入前的空态（预校验先于任何写入，半灌状态不可达）。
     */
    fun restore(snapshot: EntitySnapshot) {
        require(
            liveCount == 0 && liveCapacityHighWater == 0 &&
                intColumns.isEmpty() && strColumns.isEmpty() && outEdges.isEmpty() && inEdges.isEmpty(),
        ) { "restore 只允许灌入空内核——当前已有实体/列/边（混档恢复拒绝）" }
        // 预校验（全部通过才开始写入）。
        for (name in snapshot.archetypes) {
            require(name in archetypeKeys) { "快照原型未注册: $name" }
        }
        for (kind in snapshot.relationKinds) {
            require(kind in relationSchemas) { "快照关系类型未注册: $kind" }
        }
        for (f in snapshot.fields) {
            val key = f.name.uppercase(Locale.ROOT)
            val spec = fieldTypes[key]
            require(spec != null) { "快照字段未注册: ${f.name}" }
            require(spec.type == f.type) { "快照字段 ${f.name} 类型不符: 快照 ${f.type} 声明 ${spec.type}" }
            if (f.isEntityRef) {
                for (p in f.intValues) {
                    require(EntityIdCodec.isWellFormed(p.value)) {
                        "快照 entity-ref 字段 ${f.name} 的值非良构: ${p.value.toString(16)}"
                    }
                }
            }
        }
        val seenSlots = HashSet<Int>()
        var maxSlot = -1
        for (s in snapshot.slots) {
            require(s.slot >= 0) { "快照槽号为负: ${s.slot}" }
            require(seenSlots.add(s.slot)) { "快照槽 ${s.slot} 重复" }
            maxSlot = maxOf(maxSlot, s.slot)
        }
        val entityIds = HashSet<Long>(snapshot.slots.size * 2)
        for (s in snapshot.slots) {
            if (s.state != 0) entityIds += EntityIdCodec.encode(s.pkgShortId, s.generation, s.slot)
        }
        for (e in snapshot.edges) {
            require(e.from in entityIds && e.to in entityIds) {
                "快照边端点不在实体集里（悬空边拒绝）：${e.from.toString(16)} → ${e.to.toString(16)}"
            }
        }
        // 灌入（校验已全过）。
        ensureCapacityFor(maxSlot + 1)
        for (s in snapshot.slots) {
            states[s.slot] = s.state
            generations[s.slot] = s.generation
            pkgBySlot[s.slot] = s.pkgShortId
            if (s.state != 0) {
                archetypeBySlot[s.slot] = snapshot.archetypes[s.archetypeIdx]
                liveCount++
            }
        }
        liveCapacityHighWater = maxSlot + 1
        for (f in snapshot.fields) {
            val key = f.name.uppercase(Locale.ROOT)
            when (f.type) {
                ComponentFieldType.INT -> {
                    val col = intColumns.getOrPut(key) { LongArray(capacity) }
                    for (p in f.intValues) col[p.slot] = p.value
                }
                ComponentFieldType.STR -> {
                    val col = strColumns.getOrPut(key) { arrayOfNulls(capacity) }
                    for (p in f.strValues) col[p.slot] = p.value
                }
            }
        }
        // 空闲/退休槽重建：退休判据 = generation 顶格（与 destroy 的口径一致）。
        for (slot in 0 until liveCapacityHighWater) {
            if (states[slot] == 0) {
                if (generations[slot] >= EntityIdCodec.MAX_GENERATION) retiredSlots.add(slot)
                else freeSlots.addLast(slot)
            }
        }
        for (e in snapshot.edges) {
            writeEdge(e.from, snapshot.relationKinds[e.kindIdx], e.to, dedupe = false, value = e.value)
        }
    }

    /** 扩容到至少 [minCapacity]（restore 专用；槽数组与已建列同步）。 */
    private fun ensureCapacityFor(minCapacity: Int) {
        if (minCapacity <= capacity) return
        var newCap = capacity
        while (newCap < minCapacity) newCap *= 2
        generations = generations.copyOf(newCap)
        states = states.copyOf(newCap)
        archetypeBySlot = archetypeBySlot.copyOf(newCap)
        pkgBySlot = pkgBySlot.copyOf(newCap)
        growAllColumns(newCap)
        capacity = newCap
    }

    private companion object {
        /** 槽容量硬上界（fail-closed；5000 实体基准的 100 倍余量）。 */
        const val MAX_CAPACITY: Int = 1 shl 20
    }
}
