package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import java.util.Locale

/**
 * Module 描述符与声明契约 —— ABI-4/ABI-5 候选面（era-arch-v61u.md §2/§5/§10；
 * 逐项过 Gate 后才冻结）。
 *
 * 一个 .erbc 容器家族、两种形态：Module（未链接：导入表 + 重定位表 + 负下标哨兵）
 * 与 Linked（已链接：LSC/分派/事件注册段）。本文件冻结 Module 侧的**描述符**
 * （Manifest 核心字段）；容器字节读写是 M2 执行路次，不在本波。
 *
 * legacy `.patch` 只在导入期转码为本描述符形态（ADR-011），运行期不再有第二条管线。
 */

/**
 * 函数覆盖政策（Modern profile 下逐函数生效；Legacy-ERATW profile 忽略本枚举、
 * 恒 last-wins，见 era-arch-v61u.md §9）。
 */
enum class OverridePolicy {
    /** 默认：同名非事件函数已存在则链接期拒绝。 */
    FORBID,

    /** 显式覆盖：胜出并把前实现压入 super 链（CALL_SUPER 可回溯）。 */
    OVERWRITE,
}

/**
 * 声明式 CSV 字典注入项（CFLAG/TALENT/ABL/ITEM 等表的无损扩展）。
 *
 * 二形语义（era-arch-v61u.md §5）：
 * - [isAlias] = false：写 `names[index]` + keywords 反查表；
 * - [isAlias] = true：仅写 keywords 反查表（.als 别名语义）。
 *
 * 装载期强制 trim [keyword]；冲突检测含「关键字已绑他位」与「下标超容量」。
 * [index] = -1 = 请求自动分配（v6.2-c，事务提交期尾部追加空闲位；key↔下标映射
 * 持久化进 .rt2，Mod 组合变化读档按 key 重映射）。
 * TODO(verify)：.als 语义锚点待对照 C# CSV/ERD 装载收口。
 */
data class CsvKeywordPatch(
    val tableName: String,
    val index: Int,
    val keyword: String,
    val isAlias: Boolean = false,
) {
    init {
        require(tableName.isNotBlank()) { "csv-patches 的表名不能为空" }
        require(keyword.isNotEmpty()) { "csv-patches 的关键字不能为空（$tableName）" }
        require(index >= -1) { "csv-patches 的 index 只能是 -1（自动分配）或非负下标：$index" }
    }
}

/**
 * CSV **值补丁**的操作种类（§8 值补丁，RimWorld patch 语义的声明式最小集）。
 *
 * - [ADD]：目标格不存在时写入（存在时按数据层语义追加，是否允许由消费侧按列类型判）；
 * - [REPLACE]：目标格存在时改写；
 * - [REMOVE]：清空目标格。
 *
 * 声明事务**不执行**任何数据写入，只按链接序把同格写者排成一条链（末位生效，
 * 诊断保留全部写者）；真正落到 CSV 行上是 M1-4 的稳定身份键与值装载面。
 */
enum class CsvPatchOp { ADD, REPLACE, REMOVE }

/**
 * CSV 值补丁声明（csv-patches 域的第二形，`CsvKeywordPatch` 是关键字注入形）。
 *
 * 目标 = 表名 + 行身份 + 列关键字。**行身份用字符串**：原版 CSV 行的稳定身份是
 * 「CSV 番号」或「首列关键字」，两者在声明层都不该被固化成某次装载的物理行号
 * （§8：Mod 注入值抽离的稳定身份键；DELCHARA 后不串位）。
 *
 * [value] 与 [op] 的配对在构造期判死：REMOVE 必须无值，ADD/REPLACE 必须有值 ——
 * 「有值却删除」「无值却写入」都是声明写错，不留到消费侧猜。
 */
data class CsvValuePatch(
    val table: String,
    val row: String,
    val column: String,
    val op: CsvPatchOp,
    val value: String? = null,
) {
    init {
        require(table.isNotBlank()) { "csv 值补丁的表名不能为空" }
        require(row.isNotBlank()) { "csv 值补丁的行身份不能为空（$table）" }
        require(column.isNotBlank()) { "csv 值补丁的列关键字不能为空（$table/$row）" }
        require((op == CsvPatchOp.REMOVE) == (value == null)) {
            "csv 值补丁 $table/$row/$column 的 op 与 value 不配对：op=$op value=$value" +
                "（REMOVE 必须无值，ADD/REPLACE 必须有值）"
        }
    }
}

/**
 * 位置无关模块描述符（Manifest 核心字段，ABI-5）。
 *
 * - [externalCalls]：本模块 `_CALL(22)` 引用的外部函数名表；指令字侧以负下标
 *   哨兵 `a = -(extIdx + 1)` 引用（见 [CallSentinels.external]）；
 * - [overrideFunctions]：显式声明 OVERWRITE 的非事件函数键集合（装载期归一大写）；
 *   不在集合内的重名 = FORBID，Modern profile 链接期拒绝；
 * - [globalDims]/[privateDims]：复用 core 权威 [DimSpec]（vars/UserVariables.kt），
 *   不另造第二套声明类型；声明事务（DeclarationRegistry）统一校验；
 * - [csvPatches]：进同一声明事务的 csv-patches 域；
 * - [entityRefs]：显式声明「持久保存 EntityId 的整型变量键」集合（v6.3-c，GPT 二审
 *   P0-1 方案 B）。跨设备导入发生 pkgShortId 冲突重映射时，引擎只遍历：实体图组件载荷
 *   中按 [ComponentSchema] 声明为 EntityRef 的字段 + 关系边端点 + 各包声明的
 *   entityRefs 变量槽（v6.4-c：组件字段级类型进 schema，三面缺一不可）。
 *   **普通 Long 变量不被自动当作实体引用**——引擎无法从数值区分整数与 EntityId，
 *   未声明的旧 ID 不重映射（诚实边界，文档明示；Mod 引用铁律要求持久引用必声明）。
 *   声明事务校验：键必须存在于本包 dims 声明且为整型，否则整包拒绝；
 * - [componentSchemas]：组件字段级声明（v6.4-c，GPT 三审 P0-3A），进声明事务
 *   component-schemas 域；字段可声明 EntityRef（仅整型），跨设备重映射按 schema 遍历，
 *   未类型化整型字段不重映射；
 * - [csvValuePatches]：csv-patches 域的值操作形（add/replace/remove，同格按链接序叠加、
 *   末位生效）；
 * - [entityArchetypes] / [relationshipSchemas]：声明事务的第四、五域（v6.4-c 收口：
 *   五域一张 Registry，不再游离）；
 * - [exportedMacros]：导出的 .ERH #DEFINE 宏（跨包依赖编译期可见）；
 * - [artifactSha256]：本模块 .erbc 字节流摘要（链接指纹与出包对账用）。
 */
data class ModuleDescriptor(
    val packageId: String,
    val version: String = "",
    val requires: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
    val externalCalls: List<String> = emptyList(),
    val overrideFunctions: Set<String> = emptySet(),
    val globalDims: List<DimSpec> = emptyList(),
    val privateDims: Map<String, List<DimSpec>> = emptyMap(),
    val csvPatches: List<CsvKeywordPatch> = emptyList(),
    /**
     * csv-patches 域的值操作形（v6.4-c / §8 值补丁）：按链接序叠加、同格末位生效。
     * 与 [csvPatches] 同进声明事务（DeclarationRegistry 的 csv-patches 域）。
     */
    val csvValuePatches: List<CsvValuePatch> = emptyList(),
    val entityRefs: Set<String> = emptySet(),
    val componentSchemas: List<ComponentSchema> = emptyList(),
    /**
     * entity-archetypes 声明域（v6.4-c）：原型名 + 组件 schema 名引用。
     * 提交期校验引用可解析；运行期 `IEntityKernel.create` 只读冻结快照。
     */
    val entityArchetypes: List<EntityArchetype> = emptyList(),
    /**
     * relationship-schemas 声明域（v6.4-c）：关系类型的方向/去重/级联/自动反向边语义。
     * 名字是全局命名空间（`addRelation(from, kind, to)` 按名寻址），跨包重名 = 冲突。
     */
    val relationshipSchemas: List<RelationshipSchema> = emptyList(),
    val exportedMacros: Map<String, String> = emptyMap(),
    val artifactSha256: String = "",
) {
    init {
        require(packageId.isNotEmpty()) { "packageId 不能为空" }
    }

    /** 覆盖声明键归一（大写，IGNORE CASE 口径同 Config.StrComper）。 */
    fun normalizedOverrideKeys(): Set<String> =
        overrideFunctions.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }

    /** entity-ref 声明键归一（口径同覆盖键）。 */
    fun normalizedEntityRefKeys(): Set<String> =
        entityRefs.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }

    /** 某函数键是否显式声明了覆盖（键先归一）。 */
    fun policyFor(functionKey: String): OverridePolicy =
        if (functionKey.uppercase(Locale.ROOT) in normalizedOverrideKeys()) {
            OverridePolicy.OVERWRITE
        } else {
            OverridePolicy.FORBID
        }
}

/**
 * `_CALL.a` 哨兵协议（ABI-4）——链接期回填，运行期不可见。
 *
 * 三支判序（顺序不可换，两个 super 哨兵也是负数）：
 * 1. `a == CALL_SUPER_SENTINEL` → 回填「前一同名实现物理下标 + DIRECT 位」；
 *    无 super 时：CALL_SUPER 链接期报错，TRYCALL_SUPER 折 NOP——**NOP 不写任何
 *    返回槽**（RESULT/RESULTS/RESULTF 保持调用前值，同官方 TRYCALL 缺目标语义）；
 * 2. `a == TRY_CALL_SUPER_SENTINEL` → 同上但无 super 折 NOP；
 * 3. `a < 0`（其余负值）→ 外部导入：`extIdx = -(a + 1)`，查
 *    [ModuleDescriptor.externalCalls] 后回填胜者物理下标（走 dispatch）；
 * 4. `a >= 0` → 模块内下标，净偏移重定位后按 LSC 胜者回填。
 *
 * DIRECT 位 = `_CALL.b` bit27（0x08000000，实树核验空闲：OpcodeTable.kt:785-801
 * 四标志位 bit31/30/29/28 已占）。置位直调物理下标、绕过 dispatch——
 * CALL_SUPER 与切面 wrapper 内层调用专用，防 dispatch 二次跳转成环。
 */
object CallSentinels {
    /**
     * Mod 面 `CALL SUPER` 的表面名（D-3 kojo 派发覆盖契约，era-arch §16）。
     *
     * **只在 Mod 包编译档保留**（`ErbProgramCompiler(allowSuperCalls = true)`）：
     * 语法 = `CALL SUPER(<实参…>)` / `TRYCALL SUPER(<实参…>)`，编译成
     * [CALL_SUPER_SENTINEL] / [TRY_CALL_SUPER_SENTINEL]，实参照常登记 callArgs；
     * 链接期回填「本函数的前一同名实现」并置 DIRECT 位。
     *
     * **不新增语句/操作码**：复用 `CALL` 语句形态，旧 `.erbc` 的语句 op 空间与四份
     * 序列化面一字不动（`FunctionCode` 的 ordinal 就是语句 op，加值会错位）。
     *
     * base 编译档不保留该名（`allowSuperCalls = false` 时 `SUPER` 仍是普通函数名）：
     * 4.980/4.981 语料实测 `@SUPER` 定义与 `CALL SUPER` 调用各 **0 处**，普通语料
     * 无回退风险；Mod 包内禁止定义 `@SUPER`（编译期拒绝），消除「保留名 vs 用户函数」两义。
     *
     * `JUMP SUPER` / `TRYCCALL SUPER` 不开放：super 是调用链语义而非跳转；TRYCCALL
     * 需要命中标志槽，而哨兵无 super 时整条折 NOP，catch 落点没有定义。
     */
    const val SUPER_CALL_NAME: String = "SUPER"

    /** CALL_SUPER 编译期哨兵。 */
    const val CALL_SUPER_SENTINEL: Int = Int.MIN_VALUE

    /** TRYCALL_SUPER 编译期哨兵（无 super 折 NOP）。 */
    const val TRY_CALL_SUPER_SENTINEL: Int = Int.MIN_VALUE + 1

    /**
     * 导入编号上界（v6.2-c fail-closed 裁定）：`-(importIdx+1)` 在
     * importIdx = Int.MAX_VALUE 时回绕成 [CALL_SUPER_SENTINEL]——合法域必须
     * 显式封顶并在容器读取期校验，不依赖「正常不会那么多」。取 2^20 与
     * 装载器 FUNC_COUNT_MAX 同量级；越界导入 = 容器损坏，拒载。
     */
    const val IMPORT_INDEX_MAX: Int = 1 shl 20

    /**
     * `_CALL.b` bit27：直调物理下标、绕过 dispatch。
     *
     * M2 起真源在 [com.eratw.port.core.code.Opcode.DIRECT_CALL_FLAG]（与
     * [com.eratw.port.core.code.Opcode.ARG_COUNT_MASK] 的掩码扩展同点落地：
     * 掩码必须先排除 bit27，链接器再发射，本常量保留为 patch 侧的转发钉）。
     */
    const val DIRECT_CALL_FLAG: Int = com.eratw.port.core.code.Opcode.DIRECT_CALL_FLAG

    /** 外部导入哨兵编码：extIdx → 负下标。越界当场拒绝。 */
    fun external(extIdx: Int): Int {
        require(extIdx in 0..IMPORT_INDEX_MAX) { "extIdx 越域: $extIdx（合法 0..$IMPORT_INDEX_MAX）" }
        return -(extIdx + 1)
    }

    /** 是否外部导入哨兵（先排除两个 super 哨兵）。 */
    fun isExternal(a: Int): Boolean =
        a < 0 && a != CALL_SUPER_SENTINEL && a != TRY_CALL_SUPER_SENTINEL

    /** 外部导入哨兵解码；非外部哨兵抛 [IllegalArgumentException]。 */
    fun externalIndexOf(a: Int): Int {
        require(isExternal(a)) { "不是外部导入哨兵: $a" }
        return -(a + 1)
    }
}