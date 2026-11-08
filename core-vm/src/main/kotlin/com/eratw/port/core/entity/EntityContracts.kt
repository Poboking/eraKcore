package com.eratw.port.core.entity

import java.util.Locale

/**
 * 领域无关实体内核与角色桥契约 —— v6.1-u §7（era-arch-v61u.md）。
 *
 * **业务零耦合铁律**：本文件与实现严禁出现任何特定 Mod 词汇（child/offspring/school
 * 之类）。内核只认识 EntityId、任意命名的 Component 字段、任意命名的有向边 Relation、
 * 以及「EntityId ↔ CharacterTable 物理行号」的通用投射。具体业务全部由 Mod 脚本用
 * 这些原语搭建（行为调度归 ERB 函数 + 事件钩子，引擎不发明行为树/权限系统）。
 *
 * 实现落点（M4）：EntityKernel 为 SoA 列式存储 + Schema 注册字段槽位，挂 RuntimeAux；
 * **拒绝** HashMap-of-HashMap 装箱实现。本文件只冻结接口形状，不含实现。
 */

/**
 * 实体生命周期三态（ERB 侧以 Int 暴露，`ENTITY_GET_STATE/SET_STATE` 直读直写）。
 *
 * RETIRED 与销毁不同：**留数据与关系、退出常规轮询**——考古/复活类业务由 Mod 自决。
 * destroy 才是真删（级联清关系边，槽位 generation 递增）。
 */
object EntityLifecycle {
    const val ACTIVE: Int = 1
    const val INACTIVE: Int = 2
    const val RETIRED: Int = 3

    fun isValid(state: Int): Boolean = state == ACTIVE || state == INACTIVE || state == RETIRED
}

/** 组件字段类型（v6.4-c，GPT 三审 P0-3A：字段级类型声明）。 */
enum class ComponentFieldType { INT, STR }

/**
 * 组件字段声明。
 *
 * [isEntityRef] 只对整型字段合法：跨设备 shortId 重映射时，引擎按 **schema 声明**
 * 遍历该字段并改写其中的 EntityId；未声明为 EntityRef 的整型字段一律不猜值
 * （普通整数与 EntityId 在 Long 上不可区分，诚实边界同蓝图 §7）。
 */
data class ComponentFieldSpec(
    val name: String,
    val type: ComponentFieldType = ComponentFieldType.INT,
    val isEntityRef: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "组件字段名不能为空" }
        require(!isEntityRef || type == ComponentFieldType.INT) {
            "entity-ref 字段必须是整型: $name"
        }
    }
}

/**
 * 组件 Schema 声明（DeclarationRegistry 的 component-schemas 域载荷）。
 *
 * 字段按名字注册（归一后不区分大小写）；未注册字段的读写返回失败而不是隐式建列
 * （见 [IEntityKernel]）。[normalizedEntityRefFieldKeys] 供跨设备重映射遍历
 * ——持久实体引用三面之一（另两面：关系边端点、Manifest entityRefs 变量槽）。
 */
data class ComponentSchema(
    val packageId: String,
    val name: String,
    val fields: List<ComponentFieldSpec> = emptyList(),
) {
    init {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
        require(name.isNotBlank()) { "组件名不能为空" }
        val seen = HashSet<String>(fields.size * 2)
        for (f in fields) {
            require(seen.add(f.name.uppercase(Locale.ROOT))) { "组件字段重名: ${f.name}" }
        }
    }

    /** entity-ref 字段名归一集合（跨设备重映射遍历面）。 */
    fun normalizedEntityRefFieldKeys(): Set<String> =
        fields.filter { it.isEntityRef }.mapTo(HashSet()) { it.name.uppercase(Locale.ROOT) }
}

/**
 * 实体原型声明（DeclarationRegistry 的 entity-archetypes 域载荷，ABI-5）。
 *
 * [components] 引用组件 Schema 名（全局命名空间 —— `IEntityKernel.getInt(id, field)` 与
 * `create(pkgShortId, archetype)` 都是按名字寻址，没有 schema 参数可消歧；同名 schema
 * 或同名字段跨包出现一律在声明事务提交期拒绝，见 DeclarationRegistry）。
 * 原型可以引用其它 Package 声明的 schema（组合 = 声明层并集，依赖满足由 M2 链接器验）。
 * 该引用是否解析得到在提交期判定，运行期 `create` 只读冻结快照。
 */
data class EntityArchetype(
    val name: String,
    val components: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "原型名不能为空" }
        val seen = HashSet<String>(components.size * 2)
        for (c in components) {
            require(c.isNotBlank()) { "原型 $name 的组件名不能为空" }
            require(seen.add(c.uppercase(Locale.ROOT))) { "原型 $name 的组件重复: $c" }
        }
    }
}

/**
 * 关系类型声明（DeclarationRegistry 的 relationship-schemas 域载荷，ABI-5）。
 *
 * 蓝图 §7：方向语义、重复边策略、级联规则、是否维护自动反向边——**业务含义**
 * （例如「PARENT 能有几个」）归 Mod 声明，不进引擎。环允许（家族树业务自决）。
 *
 * 缺省口径 = 有向、去重、destroy 级联清边、不自动建反向边：最保守的一组，Mod 想要
 * 别的语义必须显式声明，避免「默认行为」替 Mod 猜业务意图。
 *
 * [inverseName] 只在 [autoInverse] 时允许出现：自动反向边要写一条**反向类型**的边，
 * 名字必须显式给出且不能与自身同名（否则 addRelation 会无限折返）。
 */
data class RelationshipSchema(
    val name: String,
    val directed: Boolean = true,
    val dedupeEdges: Boolean = true,
    val cascadeOnDestroy: Boolean = true,
    val autoInverse: Boolean = false,
    val inverseName: String? = null,
) {
    init {
        require(name.isNotBlank()) { "关系类型名不能为空" }
        if (autoInverse) {
            require(directed) { "无向关系 $name 不需要自动反向边（两个方向本就是同一条边）" }
            require(!inverseName.isNullOrBlank()) { "autoInverse 的关系 $name 必须声明 inverseName" }
            require(!inverseName.equals(name, ignoreCase = true)) {
                "关系 $name 的 inverseName 不能与自身同名"
            }
        } else {
            require(inverseName == null) { "未启用 autoInverse 的关系 $name 不应声明 inverseName" }
        }
    }
}

/**
 * 通用实体内核接口（M4 实现，双栖内置函数 ENTITY_* 族的唯一后端）。
 *
 * 字段（Component）按名字注册于 Schema（DeclarationRegistry 的 component-schemas 域），
 * 未注册字段的读写返回失败而不是隐式建列——声明事务先行，运行期只读 Schema。
 *
 * 所有 id 参数须 [EntityIdCodec.isWellFormed]；resolveAlive 走全位域比对
 * （pkg + generation + slot），悬空/复用引用一律按不存在处理。
 */
interface IEntityKernel {

    /** 建实体（初始 ACTIVE）。archetype 须已注册；返回新 EntityId。 */
    fun create(pkgShortId: Int, archetype: String): Long

    /** 真删：级联清关系边、槽位 generation 递增。不存在返回 false。 */
    fun destroy(id: Long): Boolean

    /** 存活判定（全位域比对 + 未销毁；RETIRED 也算存在）。 */
    fun exists(id: Long): Boolean

    /** 不存在返回 0（三态恒 ≥ 1，0 即失败哨兵）。 */
    fun stateOf(id: Long): Int

    fun setState(id: Long, state: Int): Boolean

    /** 未注册字段 / 不存在返回 0。字段类型不符按失败处理。 */
    fun getInt(id: Long, field: String): Long

    fun setInt(id: Long, field: String, value: Long): Boolean

    fun getStr(id: Long, field: String): String

    fun setStr(id: Long, field: String, value: String): Boolean

    /** 有向边 from --kind--> to。任一端不存在返回 false。 */
    fun addRelation(from: Long, kind: String, to: Long): Boolean

    /** 关系类型是否已注册（声明事务先行；投影/双栖内置函数用它 fail-closed 校验声明）。 */
    fun hasRelationKind(kind: String): Boolean

    /**
     * 读**已冻结的**关系类型声明；未注册返回 null。
     *
     * 存在理由 = 关系投影协议要按声明的方向语义 fail-closed（无向边/自动反边会把原生
     * 矩阵的两个独立格折成一份值）。判定必须读内核里注册的那一份，不能信调用方递来的
     * 副本——副本与注册声明不一致时，按副本判就是静默有损。
     */
    fun relationSchemaOf(kind: String): RelationshipSchema?

    fun removeRelation(from: Long, kind: String, to: Long): Boolean

    fun hasRelation(from: Long, kind: String, to: Long): Boolean

    /**
     * 边值（关系度）；无边返回 0。无向边按同一条边读（与 [hasRelation] 同口径）。
     *
     * 存在理由 = §7 关系投影协议：原生 RELATION 格是 **Long 值**（语料实测
     * `RELATION:(TALKER:0):(CHARA:LOCAL)` 与 100/200 比大小，ANOTHER_TALK.ERB:102/:186），
     * 退场要把投射期间的原生变化**无损**同步回实体图——只记「边存在」的话，
     * 100→200 这种变化在退场时就蒸发了。值恒随边持久化（ABI-6 实体段 v2）。
     *
     * 自动反向边是**另一条边**（另一种 kind），有自己的值；本方法不代它作答。
     */
    fun getRelationValue(from: Long, kind: String, to: Long): Long

    /**
     * 改既有边的值；边不存在/类型未注册返回 false（**不隐式建边**，与「不隐式建列」同纪律）。
     * 无向边两个方向写到同一条边（值只存一份，不会分叉）。
     */
    fun setRelationValue(from: Long, kind: String, to: Long, value: Long): Boolean

    /**
     * 按 archetype + 生命周期筛实体（state=0 表示不限）。
     * 返回稠密数组供 ENTITY_QUERY 写入具名 ERB 一维整型数组。
     */
    fun query(archetype: String, state: Int): LongArray

    /** 邻接查询：outgoing=true 出边 / false 入边（ENTITY_QUERY_REL 的 dir 语义）。 */
    fun queryRelations(id: Long, kind: String, outgoing: Boolean): LongArray
}

/**
 * Active Roster Pinning 角色桥（M5 实现）。
 *
 * **禁单次调用级借槽**：登场独占原生角色槽（ADDVOIDCHARA/按模板 ADDCHARA +
 * `__native_chara__` 组件快照注水），退场脱水 + deleteCharacter + 引擎已知指针清扫。
 *
 * **Mod 引用铁律**：跨帧/跨天持久化引用扩展角色只存 EntityId，用前现换 charIndex；
 * 严禁持久化临时 charIndex（行排列/删除随时发生）。
 */
interface ICharaSlotProjector {

    /**
     * 登场投射：独占一个原生角色行并注水。返回 charIndex；失败 -1。
     * 已投射的实体幂等返回当前行号。
     */
    fun project(entityId: Long): Int

    /**
     * 退场：先脱水（活行数据写回 `__native_chara__`），再 deleteCharacter +
     * 引擎指针清扫（TARGET 全数组/ASSI/PLAYER 等于 deletedPos 置默认、大于者减 1）
     * + 其余投射映射平移。未投射返回 false。
     *
     * RELATION 残留**不在这里粗暴清零**：清残留是 [RelationProjectionSynchronizer]
     * 的 scoped 职责（先回写图、再只清退场者自己的行与列），见 §7 关系投影协议。
     * 宿主桥若顺手全表清零，会把原生角色之间的关系一并抹掉。
     */
    fun unproject(entityId: Long): Boolean

    /** 当前映射行号；未投射 -1。调用方不得持久化此值。 */
    fun charIndexOf(entityId: Long): Int

    /** 行号反查；该行无投射实体返回 [EntityIdCodec.NULL]。 */
    fun entityAt(charIndex: Int): Long

    /**
     * 当前全部已投射实体的 EntityId（**EntityId 数值升序**，与 ENTITY_QUERY 同口径）。
     *
     * 存在理由 = §8「存档前同步」不变量：SaveService 抓取 .rt2 前必须对**全部**投射实体
     * 调 [syncFromCharaSlot]，否则活行数据滞后于快照。没有枚举入口这条不变量就无法实现
     * （只能靠调用方自己记得有哪些实体——那正是「架构变质」的形态）。
     *
     * 返回数组是快照拷贝，调用方可自由持有；不得把 [charIndexOf] 的行号当持久身份。
     */
    fun projectedEntities(): LongArray

    /**
     * 存档前同步：把活行数据脱水入 `__native_chara__` 组件。
     * SaveService 抓取 .rt2 前对**全部**投射实体调用（枚举入口 = [projectedEntities]），
     * 否则快照滞后。
     */
    fun syncFromCharaSlot(entityId: Long)

    /**
     * 行排列钩子：接 VariableStore 的 swapCharacters/sortCharactersByInt/ByStr/
     * pickUpCharacters。map[oldIndex] = newIndex（-1 = 该行已删）。
     */
    fun onCharacterRowsPermuted(oldToNewIndexMap: IntArray)

    /** 行删除钩子：接 VariableStore.deleteCharacter；本实体已投射则同步解映射。 */
    fun onCharacterRowDeleted(deletedIndex: Int)
}