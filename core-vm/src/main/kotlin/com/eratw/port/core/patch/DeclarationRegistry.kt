package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import java.util.Locale

/**
 * 声明事务的五域（era-arch-v61u.md §5，ABI-5；v6.4-c 收口为「一张 Registry 管所有声明」）。
 *
 * 五域之外的东西不进本事务：函数覆盖归 LSC（§3），事件归事件视图（§6），
 * 持久化域由声明种类推导（§8），不另造自由枚举。
 */
enum class DeclarationDomain(val label: String) {
    GLOBAL_DIM("global-dims"),
    CSV_PATCH("csv-patches"),
    COMPONENT_SCHEMA("component-schemas"),
    ENTITY_ARCHETYPE("entity-archetypes"),
    RELATIONSHIP_SCHEMA("relationship-schemas"),
}

/**
 * 命名作用域（蓝图 §5 的遮蔽链，从内到外）。
 *
 * 顺序即遮蔽优先级：`Function Local > File Private > Package Global > Built-in`
 * （[NamePolicy.shadows] 按 ordinal 判）。声明事务只直接裁决
 * **Package Global** 与 **Built-in** 的关系；内层遮蔽由 ERB 作用域解析器负责。
 */
enum class DeclarationScope { FUNCTION_LOCAL, FILE_PRIVATE, PACKAGE_GLOBAL, BUILT_IN }

/**
 * 声明名的规范形与遮蔽规则（§5「NamePolicy 规范化（Scope, CanonicalName）」）。
 *
 * 规范化 = trim + 大写（[Locale.ROOT]）：原版标识符字典用
 * `StringComparer.OrdinalIgnoreCase`（reference: Config.cs:33，切换点在 :303/:308），
 * 移植侧既有声明路径（ModuleDescriptor 的覆盖键 / entityRefs / ComponentSchema 字段去重）
 * 一律用大写归一，这里沿用同一口径，不另造第二套大小写语义。
 *
 * **例外：CSV 关键字不是这条口径**。原版 `nameToIntDics` / `erdNameToIntDics` 都是
 * 默认比较器（区分大小写的普通字典，reference: ConstantData.cs:118/:120），运行期
 * `keywordToInteger` 也是直接 `table[keyword]`（VariableStore.kt:1047）。所以关键字
 * 冲突检测用**原文精确比较**，不做大写折叠 —— 折叠会把 `MyFlag` 与 `MYFLAG` 误判为同一
 * 关键字，而原版允许它们并存。
 */
object NamePolicy {

    /** 标识符规范形：trim + 大写（OrdinalIgnoreCase 的归一表示）。 */
    fun canonical(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /** [shadow] 是否遮蔽 [target]（内层遮蔽外层）。 */
    fun shadows(shadow: DeclarationScope, target: DeclarationScope): Boolean =
        shadow.ordinal < target.ordinal

    /**
     * 该遮蔽是否一律拒绝（§5：Global×Global / Global×Built-in 拒绝，其余遮蔽只告警）。
     *
     * 同作用域同名 = 重复声明，永远拒绝；Package Global 不得占用 Built-in 的名字。
     * 内层遮蔽外层（Function Local / File Private 遮蔽 Package Global / Built-in）允许。
     */
    fun isForbiddenShadow(shadow: DeclarationScope, target: DeclarationScope): Boolean =
        shadow == target || (shadow == DeclarationScope.PACKAGE_GLOBAL && target == DeclarationScope.BUILT_IN)
}

/** 冲突一侧的定位：Package + 产物/来源 + 声明原文。 */
data class DeclarationSite(
    val packageId: String,
    /** 来源（Manifest 文件名 / 容器标识；测试里可用任意可读串）。 */
    val source: String,
    /** 声明的人类可读形（如 `global-dim MOD_STATE`）。 */
    val declaration: String,
) {
    override fun toString(): String = "$declaration（$packageId @ $source）"
}

/**
 * 一项声明冲突（恒带**双边**定位：谁先、谁又声明了一次、冲突键与原因）。
 *
 * TODO(verify)：蓝图要求冲突报告带「文件:行号」。Manifest 目前不承载逐条声明的源位置
 * （ModuleDescriptor 只有值，没有 ScriptPosition），当前给出 Package + 来源 + 声明原文；
 * 行号要等 Manifest 编解码（M2）带上位置信息后补，不在这里猜。
 */
data class DeclarationConflict(
    val domain: DeclarationDomain,
    val key: String,
    val first: DeclarationSite,
    val second: DeclarationSite,
    val reason: String,
)

/**
 * 声明事务冲突（整包拒绝，无部分注册 —— 不变量 I5 的声明面）。
 *
 * 一次报**全部**冲突而不是 fail-fast：双 Mod 抢一个 CFLAG 时，作者需要同时看到两边，
 * 逐个修再逐个撞只会反复起不来。
 */
class DeclarationConflictException(
    conflicts: List<DeclarationConflict>,
) : RuntimeException(buildMessage(conflicts)) {

    val conflicts: List<DeclarationConflict> = conflicts.toList()

    init {
        require(this.conflicts.isNotEmpty()) { "DeclarationConflictException 至少要有一项冲突" }
    }

    companion object {
        private fun buildMessage(conflicts: List<DeclarationConflict>): String =
            buildString {
                append("声明事务冲突（整包拒绝，无部分注册）：")
                for (c in conflicts) {
                    append("\n  - [").append(c.domain.label).append("] ").append(c.key)
                    append("：").append(c.first).append(" vs ").append(c.second)
                    append(" —— ").append(c.reason)
                }
            }
    }
}

/** 冻结快照里的全局变量声明（值仍是权威 [DimSpec]，不复制第二套声明形态）。 */
data class DeclaredGlobalDim(val packageId: String, val spec: DimSpec)

/** 冻结快照里的组件 schema（Normalized name → 声明包 + schema）。 */
data class DeclaredComponentSchema(val packageId: String, val schema: ComponentSchema)

/** 冻结快照里的实体原型。 */
data class DeclaredArchetype(val packageId: String, val archetype: EntityArchetype)

/** 冻结快照里的关系类型。 */
data class DeclaredRelationship(val packageId: String, val schema: RelationshipSchema)

/**
 * 一条已裁决的 CSV 关键字绑定（两阶段分配之后的结果）。
 *
 * [alias] = true 是 .als 语义（只写关键字反查表，不占命名位）；false 是命名注入
 * （写 `names[index]` + 反查表）。[keyword] 保留原文（区分大小写，见 [NamePolicy]）。
 */
data class CsvKeywordBinding(
    val keyword: String,
    val index: Int,
    val packageId: String,
    val alias: Boolean,
)

/** 值补丁的目标格：表名已归一，行身份/列关键字保留原文（数据层身份，不做大小写折叠）。 */
data class CsvCellKey(val table: String, val row: String, val column: String)

/** 值补丁链上的一位写者（诊断面：末位生效但全部写者留档）。 */
data class CsvValueWriter(val packageId: String, val op: CsvPatchOp, val value: String?)

/**
 * 同格值补丁的终局：按链接序叠加、末位生效，[writers] 是全部写者（按链接序）。
 * [effectiveOp] 为 [CsvPatchOp.REMOVE] 表示该格终局被清空。
 */
data class CsvCellPlan(
    val key: CsvCellKey,
    val effectiveOp: CsvPatchOp,
    val effectiveValue: String?,
    val writers: List<CsvValueWriter>,
)

/**
 * 声明事务提交后的**冻结快照**（VM 启动前唯一可读的声明面）。
 *
 * 所有 Map 在构造期拷贝为只读视图；快照本身不可变，运行期不再合并、不再增删
 * （§5「VM 启动前冻结」）。查询方法接受原文名，内部按 [NamePolicy] 归一。
 */
class DeclarationSnapshot internal constructor(
    val packageIds: List<String>,
    globalDims: Map<String, DeclaredGlobalDim>,
    privateDims: Map<String, Map<String, List<DimSpec>>>,
    declaredCsvKeywords: Map<String, Map<String, CsvKeywordBinding>>,
    effectiveCsvKeywords: Map<String, Map<String, Int>>,
    csvValueCells: Map<CsvCellKey, CsvCellPlan>,
    componentSchemas: Map<String, DeclaredComponentSchema>,
    entityArchetypes: Map<String, DeclaredArchetype>,
    relationshipSchemas: Map<String, DeclaredRelationship>,
) {
    /** 归一全局变量名 → 声明。 */
    val globalDims: Map<String, DeclaredGlobalDim> = globalDims.toMap()

    /** packageId → 函数键 → 私有 `#DIM` 声明（包私有作用域，跨包不冲突）。 */
    val privateDims: Map<String, Map<String, List<DimSpec>>> =
        privateDims.mapValues { (_, m) -> m.toMap() }

    /** 表名（归一）→ 关键字原文 → 本事务写入的绑定。 */
    val declaredCsvKeywords: Map<String, Map<String, CsvKeywordBinding>> =
        declaredCsvKeywords.mapValues { (_, m) -> m.toMap() }

    /**
     * 表名（归一）→ 有效关键字表（base ∪ 本事务声明），消费侧直接喂
     * `VariableStore` 的关键字反查面（raw keyword 精确键）。
     */
    val effectiveCsvKeywords: Map<String, Map<String, Int>> =
        effectiveCsvKeywords.mapValues { (_, m) -> m.toMap() }

    /** (表, 行, 列) → 值补丁终局 + 全部写者。 */
    val csvValueCells: Map<CsvCellKey, CsvCellPlan> = csvValueCells.toMap()

    /** 归一 schema 名 → 声明（全局命名空间）。 */
    val componentSchemas: Map<String, DeclaredComponentSchema> =
        componentSchemas.toMap()

    /** 归一原型名 → 声明（全局命名空间）。 */
    val entityArchetypes: Map<String, DeclaredArchetype> = entityArchetypes.toMap()

    /** 归一关系类型名 → 声明（全局命名空间）。 */
    val relationshipSchemas: Map<String, DeclaredRelationship> =
        relationshipSchemas.toMap()

    fun globalDim(name: String): DimSpec? = globalDims[NamePolicy.canonical(name)]?.spec

    /** 某包某函数的私有声明；函数键按 [NamePolicy] 归一后查（Manifest 侧本就大写归一）。 */
    fun privateDimsOf(packageId: String, functionKey: String): List<DimSpec> =
        privateDims[packageId]?.get(NamePolicy.canonical(functionKey)).orEmpty()

    /** 某表的有效关键字表（含 base）；未知表 = 空表。 */
    fun csvKeywordsFor(table: String): Map<String, Int> =
        effectiveCsvKeywords[NamePolicy.canonical(table)].orEmpty()

    /** 关键字反查（原文精确匹配，口径同原版关键字字典）。 */
    fun csvKeywordIndex(table: String, keyword: String): Int? =
        effectiveCsvKeywords[NamePolicy.canonical(table)]?.get(keyword)

    fun componentSchema(name: String): ComponentSchema? =
        componentSchemas[NamePolicy.canonical(name)]?.schema

    fun archetype(name: String): EntityArchetype? =
        entityArchetypes[NamePolicy.canonical(name)]?.archetype

    fun relationship(name: String): RelationshipSchema? =
        relationshipSchemas[NamePolicy.canonical(name)]?.schema

    fun csvCellPlan(table: String, row: String, column: String): CsvCellPlan? =
        csvValueCells[CsvCellKey(NamePolicy.canonical(table), row, column)]
}

/**
 * 声明事务（§5）：收集 → NamePolicy 规范化 → 命名空间检查 → 双边冲突检测 →
 * 分阶段裁决（CSV 动态 ID 两阶段）→ 原子提交 → 冻结快照。
 *
 * ## 调用形态
 *
 * ```
 * DeclarationRegistry(builtinVariableNames, csvTableCapacities, baseCsvKeywords)
 *     .addPackage(modA, "A.erbc")
 *     .addPackage(modB, "B.erbc")
 *     .freeze()
 * ```
 *
 * 任一冲突抛 [DeclarationConflictException]（**全部**冲突一次报齐，整包拒绝）；
 * 成功后快照不可变，之后 `addPackage` 直接抛 [IllegalStateException]。
 *
 * ## 命名空间（为什么这几个名字必须全局唯一）
 *
 * - **global-dims / global 变量名**：ERB 全局命名空间，原版用 OrdinalIgnoreCase 字典
 *   （reference: Config.cs:33 + VariableData.cs:2904）。跨包重名 = Global×Global 拒绝；
 *   撞内置变量名 = Global×Built-in 拒绝（[builtinVariableNames] 由宿主喂，注册表不猜）。
 * - **component-schema 名 / 字段名**：内核 API 按名字寻址且没有 schema 参数
 *   （`IEntityKernel.getInt(id, field)`、`create(pkgShortId, archetype)`），同名 schema
 *   或同名字段跨包并存会让寻址产生歧义 ⇒ 两者都按全局命名空间拒绝重复。
 * - **entity-archetype 名**：同上（`create` 只收名字）。
 * - **relationship-schema 名**：同上（`addRelation(from, kind, to)` 只收名字）。
 * - **private-dims**：包私有作用域，跨包重名合法；同包同函数内重名拒绝。
 * - **CSV 关键字**：按表隔离、**区分大小写**（[NamePolicy] 的例外段）；同表的关键字
 *   跨包重复 = 冲突（「关键字已绑他位」）。
 *
 * ## CSV 关键字两阶段动态 ID（§5，v6.4-c 三审 P1-3）
 *
 * ① 先为**全部显式下标**（`index >= 0` 且非别名）预留；② 显式冲突检测（同表同格、
 * 下标越容量、下标已被 base 命名、关键字已绑）；③ 再按**确定性包序 = 链接序**为
 * `index = -1` 的请求分配「最小空闲下标」（空闲 = 不在 base 命名位、不在显式预留、
 * 不在别名目标）；④ 建关键字 → 物理下标映射；⑤ 原子提交。
 *
 * 显式永远优先于自动、自动永不复用已被占用的位、分配只依赖链接序（不依赖哈希遍历序），
 * 因此「A 先声明 `-1`、B 后声明显式 0」与「B 先、A 后」得到**同一张终表**（见单测）。
 * 这就是「两 Mod 抢同一显式下标 = 整包拒绝；各用 -1 则永不撞号」的实现面。
 *
 * ## 同格多包值操作
 *
 * 与符号层不同，**数据层可叠加**：同 (表, 行, 列) 的全部值补丁按链接序求值，末位生效，
 * 快照里的 [CsvCellPlan.writers] 保留全部写者供诊断。这里不做冲突拒绝（RimWorld patch
 * 语义）；无法在本层校验的只剩「行列是否存在」——那要等 M1-4 的稳定身份键与数据装载面。
 *
 * @param builtinVariableNames 引擎内置变量名（原文；内部归一大写）。撞名 = 拒绝。
 * @param csvTableCapacities 可打补丁的表 → 声明长度（下标上界）。**未列出的表拒绝
 *   补丁**：容量未知时没有任何合法上界可判，fail-closed 比「猜一个」安全。
 * @param baseCsvKeywords 打补丁前引擎已装载的关键字表（表 → 关键字 → 下标）。用于
 *   冲突检测、占用位计算与快照的有效表合并（别名/注入不能覆盖原版关键字）。
 * @param onCommit 统一清刷入口（§5「提交后清刷 bind cache / userVar memo / handle 表」）。
 *   只在成功提交后调用一次；VM 尚未启动时通常是空实现（缺省）。
 */
class DeclarationRegistry(
    builtinVariableNames: Set<String> = emptySet(),
    csvTableCapacities: Map<String, Int> = emptyMap(),
    baseCsvKeywords: Map<String, Map<String, Int>> = emptyMap(),
    private val onCommit: (DeclarationSnapshot) -> Unit = {},
) {
    private class Staged(val descriptor: ModuleDescriptor, val source: String)

    private val builtinNames: Set<String> =
        builtinVariableNames.mapTo(HashSet()) { NamePolicy.canonical(it) }

    private val capacities: Map<String, Int> =
        csvTableCapacities.entries.associate { (k, v) ->
            require(v >= 0) { "CSV 表容量不能为负：$k = $v" }
            NamePolicy.canonical(k) to v
        }

    private val baseKeywords: Map<String, Map<String, Int>> =
        baseCsvKeywords.mapValues { (_, m) -> m.toMap() }

    private val staged = ArrayList<Staged>()
    private var frozen: DeclarationSnapshot? = null

    /**
     * 追加一个 Package 的声明（按调用序 = 链接序；声明事务的自动分配依赖这个序）。
     *
     * 重复 packageId 立即拒绝：那是同一份 Manifest 被装了两次（或两个产物冒用同一身份），
     * 不是声明冲突，继续走下去只会产生更难定位的双份声明。
     */
    fun addPackage(descriptor: ModuleDescriptor, source: String = descriptor.packageId): DeclarationRegistry {
        check(frozen == null) {
            "声明事务已冻结：不得再追加 Package 声明（${descriptor.packageId}）"
        }
        require(staged.none { it.descriptor.packageId == descriptor.packageId }) {
            "Package 声明重复：${descriptor.packageId}（第二个来源 $source）"
        }
        staged += Staged(descriptor, source.ifBlank { descriptor.packageId })
        return this
    }

    /**
     * 原子提交并冻结。已冻结时直接返回同一快照（幂等；冻结后不可变，重复调用无副作用，
     * 也不再触发 [onCommit]）。
     */
    fun freeze(): DeclarationSnapshot {
        frozen?.let { return it }
        val conflicts = ArrayList<DeclarationConflict>()

        val sources = staged.associate { it.descriptor.packageId to it.source }
        fun site(pkg: String, declaration: String): DeclarationSite =
            DeclarationSite(pkg, sources.getValue(pkg), declaration)

        fun conflict(
            domain: DeclarationDomain,
            key: String,
            first: DeclarationSite,
            second: DeclarationSite,
            reason: String,
        ) {
            conflicts += DeclarationConflict(domain, key, first, second, reason)
        }

        // ---- 域 1：global-dims（全局命名空间；内置名不可占用） ----
        val globalDims = LinkedHashMap<String, DeclaredGlobalDim>()
        val globalSite = HashMap<String, DeclarationSite>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (spec in st.descriptor.globalDims) {
                val key = NamePolicy.canonical(spec.name)
                val here = site(pkg, "global-dim ${spec.name}")
                if (key.isEmpty()) {
                    conflict(DeclarationDomain.GLOBAL_DIM, key, here, here, "全局变量名为空")
                    continue
                }
                val prev = globalDims[key]
                when {
                    prev == null && key in builtinNames -> conflict(
                        DeclarationDomain.GLOBAL_DIM,
                        key,
                        DeclarationSite("__engine__", "built-in", "内置变量 $key"),
                        here,
                        "与内置变量命名空间冲突（Global 不得遮蔽 Built-in）",
                    )

                    prev == null -> {
                        globalDims[key] = DeclaredGlobalDim(pkg, spec)
                        globalSite[key] = here
                    }

                    else -> conflict(
                        DeclarationDomain.GLOBAL_DIM,
                        key,
                        globalSite.getValue(key),
                        here,
                        "同名全局变量已声明（Global×Global 重复；全局命名空间不允许多包同名）",
                    )
                }
            }
        }

        // ---- 私有 #DIM：包私有的，跨包重名合法；同包同函数内重名拒绝 ----
        val privateDims = LinkedHashMap<String, Map<String, List<DimSpec>>>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            val byFunc = LinkedHashMap<String, MutableList<DimSpec>>()
            val seenPerFunc = HashMap<String, HashSet<String>>()
            for ((funcKey, dims) in st.descriptor.privateDims) {
                val funcCanonical = NamePolicy.canonical(funcKey)
                if (funcCanonical.isEmpty()) {
                    val here = site(pkg, "private-dim $funcKey")
                    conflict(DeclarationDomain.GLOBAL_DIM, funcCanonical, here, here, "私有声明的函数键为空")
                    continue
                }
                val seen = seenPerFunc.getOrPut(funcCanonical) { HashSet() }
                for (spec in dims) {
                    val key = NamePolicy.canonical(spec.name)
                    val here = site(pkg, "private-dim $funcKey/${spec.name}")
                    if (key.isEmpty()) {
                        conflict(DeclarationDomain.GLOBAL_DIM, key, here, here, "私有变量名为空")
                        continue
                    }
                    if (!seen.add(key)) {
                        val first = site(pkg, "private-dim $funcKey/$key")
                        conflict(
                            DeclarationDomain.GLOBAL_DIM,
                            key,
                            first,
                            here,
                            "同一函数内私有 #DIM 重名",
                        )
                        continue
                    }
                    byFunc.getOrPut(funcCanonical) { ArrayList() } += spec
                }
            }
            privateDims[pkg] = byFunc.mapValues { (_, v) -> v.toList() }
        }

        // ---- 域 3：component-schemas（schema 名与字段名都是全局命名空间） ----
        val schemas = LinkedHashMap<String, DeclaredComponentSchema>()
        val schemaSite = HashMap<String, DeclarationSite>()
        val fieldOwner = HashMap<String, DeclarationSite>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (schema in st.descriptor.componentSchemas) {
                val key = NamePolicy.canonical(schema.name)
                val here = site(pkg, "component-schema ${schema.name}")
                if (key.isEmpty()) {
                    conflict(DeclarationDomain.COMPONENT_SCHEMA, key, here, here, "组件 schema 名为空")
                    continue
                }
                if (schema.packageId != pkg) {
                    conflict(
                        DeclarationDomain.COMPONENT_SCHEMA,
                        key,
                        here,
                        DeclarationSite(schema.packageId, "?", "schema 自带 packageId"),
                        "schema.packageId 与声明它的 Package 不符",
                    )
                    continue
                }
                val prev = schemas[key]
                if (prev != null) {
                    conflict(
                        DeclarationDomain.COMPONENT_SCHEMA,
                        key,
                        schemaSite.getValue(key),
                        here,
                        "同名组件 schema 已声明（getInt/create 按名字寻址，跨包同名会歧义）",
                    )
                    continue
                }
                for (f in schema.fields) {
                    val fieldKey = NamePolicy.canonical(f.name)
                    val owner = fieldOwner[fieldKey]
                    if (owner != null) {
                        conflict(
                            DeclarationDomain.COMPONENT_SCHEMA,
                            fieldKey,
                            owner,
                            site(pkg, "component-schema ${schema.name} 的字段 ${f.name}"),
                            "字段名已被占（引擎按字段名寻址，跨 schema 必须唯一）",
                        )
                    } else {
                        fieldOwner[fieldKey] = site(pkg, "component-schema ${schema.name} 的字段 ${f.name}")
                    }
                }
                schemas[key] = DeclaredComponentSchema(pkg, schema)
                schemaSite[key] = here
            }
        }

        // ---- 域 5：relationship-schemas（全局命名空间；自动反向边必须能解析） ----
        val relationships = LinkedHashMap<String, DeclaredRelationship>()
        val relationshipSite = HashMap<String, DeclarationSite>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (rel in st.descriptor.relationshipSchemas) {
                val key = NamePolicy.canonical(rel.name)
                val here = site(pkg, "relationship-schema ${rel.name}")
                if (key.isEmpty()) {
                    conflict(DeclarationDomain.RELATIONSHIP_SCHEMA, key, here, here, "关系类型名为空")
                    continue
                }
                val prev = relationships[key]
                if (prev != null) {
                    conflict(
                        DeclarationDomain.RELATIONSHIP_SCHEMA,
                        key,
                        relationshipSite.getValue(key),
                        here,
                        "同名关系类型已声明（addRelation 按名字寻址，跨包同名会歧义）",
                    )
                    continue
                }
                relationships[key] = DeclaredRelationship(pkg, rel)
                relationshipSite[key] = here
            }
        }
        for ((key, declared) in relationships) {
            val rel = declared.schema
            if (!rel.autoInverse) continue
            val inverse = NamePolicy.canonical(rel.inverseName!!)
            if (inverse !in relationships) {
                conflict(
                    DeclarationDomain.RELATIONSHIP_SCHEMA,
                    key,
                    relationshipSite.getValue(key),
                    DeclarationSite(declared.packageId, sources.getValue(declared.packageId), "inverseName ${rel.inverseName}"),
                    "自动反向边指向未声明的关系类型（反向边类型必须也在五域里声明）",
                )
            }
        }

        // ---- 域 4：entity-archetypes（schema 引用在全部 schema 收齐后解析） ----
        val archetypes = LinkedHashMap<String, DeclaredArchetype>()
        val archetypeSite = HashMap<String, DeclarationSite>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (arch in st.descriptor.entityArchetypes) {
                val key = NamePolicy.canonical(arch.name)
                val here = site(pkg, "entity-archetype ${arch.name}")
                if (key.isEmpty()) {
                    conflict(DeclarationDomain.ENTITY_ARCHETYPE, key, here, here, "原型名为空")
                    continue
                }
                val prev = archetypes[key]
                if (prev != null) {
                    conflict(
                        DeclarationDomain.ENTITY_ARCHETYPE,
                        key,
                        archetypeSite.getValue(key),
                        here,
                        "同名原型已声明（create 按名字寻址，跨包同名会歧义）",
                    )
                    continue
                }
                for (component in arch.components) {
                    val schemaKey = NamePolicy.canonical(component)
                    if (schemaKey !in schemas) {
                        conflict(
                            DeclarationDomain.ENTITY_ARCHETYPE,
                            key,
                            here,
                            here,
                            "原型引用了未声明的组件 schema：$component",
                        )
                    }
                }
                archetypes[key] = DeclaredArchetype(pkg, arch)
                archetypeSite[key] = here
            }
        }

        // ---- 域 2：csv-patches（关键字注入两阶段 + 值补丁链接序叠加） ----
        val declaredKeywords = LinkedHashMap<String, LinkedHashMap<String, CsvKeywordBinding>>()
        val effectiveKeywords = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        val occupiedIndices = LinkedHashMap<String, HashSet<Int>>()
        for ((table, keywords) in baseKeywords) {
            val t = NamePolicy.canonical(table)
            effectiveKeywords.getOrPut(t) { LinkedHashMap() }.putAll(keywords)
            occupiedIndices.getOrPut(t) { HashSet() }.addAll(keywords.values)
        }

        var keywordDomainClean = true
        fun csvConflict(
            key: String,
            first: DeclarationSite,
            second: DeclarationSite,
            reason: String,
        ) {
            keywordDomainClean = false
            conflict(DeclarationDomain.CSV_PATCH, key, first, second, reason)
        }

        fun keywordTaken(table: String, keyword: String): DeclarationSite? {
            val t = NamePolicy.canonical(table)
            val inBase = baseKeywords[t]?.containsKey(keyword) == true
            if (inBase) return DeclarationSite("__base__", "csv", "关键字 $keyword")
            return declaredKeywords[t]?.get(keyword)?.let {
                DeclarationSite(it.packageId, sources.getValue(it.packageId), "关键字 ${it.keyword}")
            }
        }

        val explicitReservations = HashMap<String, HashMap<Int, CsvKeywordBinding>>()
        class AutoRequest(val st: Staged, val patch: CsvKeywordPatch, val site: DeclarationSite)

        val autoRequests = ArrayList<AutoRequest>()

        // 阶段 ①（显式预留 + 别名校验）——遍历序只用于报告，不参与终表裁决。
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (patch in st.descriptor.csvPatches) {
                val table = NamePolicy.canonical(patch.tableName)
                val here = site(pkg, "csv-patch ${patch.tableName}:${patch.keyword}#${patch.index}")
                val capacity = capacities[table]
                if (capacity == null) {
                    csvConflict(
                        table,
                        here,
                        here,
                        "未知 CSV 表（须在 csvTableCapacities 中声明容量；容量未知不猜上界）",
                    )
                    continue
                }
                val key = "$table:${patch.keyword}"
                if (patch.isAlias) {
                    if (patch.index < 0) {
                        csvConflict(key, here, here, "别名补丁必须显式指定下标（-1 只用于新增命名关键字）")
                        continue
                    }
                    if (patch.index >= capacity) {
                        csvConflict(key, here, here, "下标 ${patch.index} 越出表容量 $capacity")
                        continue
                    }
                    val taken = keywordTaken(table, patch.keyword)
                    if (taken != null) {
                        csvConflict(key, taken, here, "关键字已绑他位（别名不能覆盖已有绑定）")
                        continue
                    }
                    declaredKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] =
                        CsvKeywordBinding(patch.keyword, patch.index, pkg, alias = true)
                    effectiveKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] = patch.index
                    // 别名目标位同样算占用：自动分配不得把命名关键字塞进别名指向的槽。
                    occupiedIndices.getOrPut(table) { HashSet() }.add(patch.index)
                    continue
                }
                if (patch.index < 0) {
                    autoRequests += AutoRequest(st, patch, here)
                    continue
                }
                if (patch.index >= capacity) {
                    csvConflict(key, here, here, "下标 ${patch.index} 越出表容量 $capacity")
                    continue
                }
                val sameSlot = explicitReservations.getOrPut(table) { HashMap() }[patch.index]
                if (sameSlot != null) {
                    csvConflict(
                        key,
                        DeclarationSite(
                            sameSlot.packageId,
                            sources.getValue(sameSlot.packageId),
                            "关键字 ${sameSlot.keyword}",
                        ),
                        here,
                        "同表同一下标已被另一个声明占用（显式下标不允许两包争抢）",
                    )
                    continue
                }
                if (patch.index in occupiedIndices.getOrPut(table) { HashSet() }) {
                    csvConflict(key, here, here, "下标 ${patch.index} 在 base 表中已有命名（拒绝覆盖原版关键字）")
                    continue
                }
                val taken = keywordTaken(table, patch.keyword)
                if (taken != null) {
                    csvConflict(key, taken, here, "关键字已绑他位（同表关键字必须唯一）")
                    continue
                }
                val binding = CsvKeywordBinding(patch.keyword, patch.index, pkg, alias = false)
                explicitReservations.getValue(table)[patch.index] = binding
                declaredKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] = binding
                effectiveKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] = patch.index
                occupiedIndices.getValue(table).add(patch.index)
            }
        }

        // 阶段 ③（-1 自动分配）：包序 = 链接序，逐包取最小空闲下标。
        // 只有显式阶段干净时才分配 —— 否则终表建立在已判冲突的占位上，报告会互相污染。
        if (keywordDomainClean) {
            for (request in autoRequests) {
                val patch = request.patch
                val table = NamePolicy.canonical(patch.tableName)
                val capacity = capacities.getValue(table)
                val taken = keywordTaken(table, patch.keyword)
                if (taken != null) {
                    csvConflict(
                        "$table:${patch.keyword}",
                        taken,
                        request.site,
                        "关键字已绑他位（自动分配同样受关键字唯一约束）",
                    )
                    continue
                }
                val occupied = occupiedIndices.getOrPut(table) { HashSet() }
                var index = 0
                while (index < capacity && index in occupied) index++
                if (index >= capacity) {
                    csvConflict(
                        "$table:${patch.keyword}",
                        request.site,
                        request.site,
                        "表容量 $capacity 已无空闲位可分配（-1 自动分配失败）",
                    )
                    continue
                }
                declaredKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] =
                    CsvKeywordBinding(patch.keyword, index, request.st.descriptor.packageId, alias = false)
                effectiveKeywords.getOrPut(table) { LinkedHashMap() }[patch.keyword] = index
                occupied.add(index)
            }
        }

        // 值补丁：同格按链接序叠加，末位生效；不做拒绝（数据层可叠加）。
        val cellWriters = LinkedHashMap<CsvCellKey, MutableList<CsvValueWriter>>()
        for (st in staged) {
            val pkg = st.descriptor.packageId
            for (patch in st.descriptor.csvValuePatches) {
                val table = NamePolicy.canonical(patch.table)
                if (table !in capacities) {
                    val here = site(pkg, "csv-value ${patch.table}/${patch.row}/${patch.column}")
                    csvConflict(
                        "$table:${patch.row}/${patch.column}",
                        here,
                        here,
                        "未知 CSV 表（值补丁同样要求显式声明容量）",
                    )
                    continue
                }
                val cell = CsvCellKey(table, patch.row, patch.column)
                cellWriters.getOrPut(cell) { ArrayList() } +=
                    CsvValueWriter(pkg, patch.op, patch.value)
            }
        }
        val csvValueCells = LinkedHashMap<CsvCellKey, CsvCellPlan>()
        for ((cell, writers) in cellWriters) {
            val last = writers.last()
            csvValueCells[cell] = CsvCellPlan(cell, last.op, last.value, writers.toList())
        }

        if (conflicts.isNotEmpty()) throw DeclarationConflictException(conflicts)

        val snapshot = DeclarationSnapshot(
            packageIds = staged.map { it.descriptor.packageId }.toList(),
            globalDims = globalDims,
            privateDims = privateDims,
            declaredCsvKeywords = declaredKeywords,
            effectiveCsvKeywords = effectiveKeywords,
            csvValueCells = csvValueCells,
            componentSchemas = schemas,
            entityArchetypes = archetypes,
            relationshipSchemas = relationships,
        )
        frozen = snapshot
        onCommit(snapshot)
        return snapshot
    }
}
