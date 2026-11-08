package com.eratw.port.core.vars

import com.eratw.port.core.entity.EntityIdCodec
import java.util.Locale

/**
 * 持久化域与载体的**派生规则**（ERA Runtime 2.0 §8 持久化域矩阵；ABI-6 的路由面）。
 *
 * ## 域不是 Mod 的自由选择
 *
 * §8 的裁定原文：「域**由声明种类派生**，不是 Mod 自由选择——#DIM GLOBAL 之所以进全局档
 * 是原版语义，不引入可与声明脱钩的 PersistenceDomain 自由枚举」。因此本文件**不提供**
 * 任何「Mod 声明自己属于哪个域」的入口：[PersistenceDomains.domainOf] 只读 [VarDef] 上
 * 既有的三个旗标，Manifest 里没有 domain 字段可填。域枚举存在只是为了给派生结果命名，
 * 不是第二份真相。
 *
 * ## 派生优先级（实证锚点，不是约定）
 *
 * `SaveCapture.systemEntries` 的过滤是 `!isSavedata || isCharacterData → 跳过`，再按
 * `isGlobal != global → 跳过`（SaveCapture.kt:117-118；C# 同口径 VariableData.cs:3966/:3985）。
 * 即：**CHARACTER 先于 GLOBAL 判定**（角色数据既不进普通档也不进全局档，它进角色块），
 * 不可存档的变量（!isSavedata）根本不进任何域。本函数逐条复刻该优先级：
 *
 * ```
 * !isSavedata        → null（不持久化）
 * isCharacterData    → CHARACTER
 * isGlobal           → GLOBAL
 * else               → SLOT
 * ```
 *
 * GLOBAL/GLOBALS 恒 isSavedata 但只被全局档抓取（specs/variables.yaml:202/:254），
 * 所以「存档 1 创建的 Mod 全局变量切到存档 2」的答案是**跟随原版 GLOBAL 语义跨槽共享**，
 * 不是 SaveService 的裁量。
 *
 * ## 载体按 Base/Mod 分流（.sav 纯净纪律）
 *
 * 同一域在 Base 与 Mod 两侧落不同载体：Base 走原版 .sav/global.sav/角色块，Mod 走 .rt2
 * （按 Package 分片 / 按稳定角色身份键 / 实体图）。这是「.sav 只存原版变量」与
 * 「PC 兼容性矩阵」两条裁定的落点——Mod 注入位上的值若漏进 .sav，PC 不认识这些位置、
 * Mod 重组后下标漂移即错位（§8 Mod 注入 CSV 值抽离）。
 */
enum class PersistenceDomain {
    /** 每槽（saveNN.sav / saveNN.sav.rt2）。 */
    SLOT,

    /** 跨槽共享（global.sav / global.rt2，仅 SAVEGLOBAL 落盘）。 */
    GLOBAL,

    /** 角色块 / 稳定角色身份键。 */
    CHARACTER,

    /** 实体组件与关系图（恒 .rt2，按槽走；SAVEGLOBAL 不带实体载荷）。 */
    ENTITY,
}

/** 物理载体。Mod 侧一律 .rt2 家族，Base 侧一律原版载体。 */
enum class SaveCarrier {
    SAV_SLOT,
    SAV_CHARA_BLOCK,
    GLOBAL_SAV,
    GLOBAL_RT2,
    RT2_CHUNK,
    RT2_CHARA,
    RT2_ENTITY,
}

/** 存档操作（写触发与读触发配对：SAVEGAME↔LOADGAME、SAVEGLOBAL↔LOADGLOBAL）。 */
enum class SaveOperation {
    SAVEGAME,
    LOADGAME,
    SAVEGLOBAL,
    LOADGLOBAL,
}

/** 一条派生结果：域 + 载体 + 写触发 + 读触发。 */
data class PersistenceRoute(
    val domain: PersistenceDomain,
    val carrier: SaveCarrier,
    val writeTrigger: SaveOperation,
    val readTrigger: SaveOperation,
)

/**
 * Mod 注入 CSV 值的**稳定角色身份**（§8 v6.4-c 持久化键修正，GPT 三审 P0-4）。
 *
 * ## 为什么只有两种形态、且没有「注册下标」这一种
 *
 * 持久化键 = `(PackageId, TableId, CanonicalKeyword, CharIdentity, Value)`，CharIdentity
 * **只许稳定身份**：
 *
 * - 原版 CSV 定义角色 → **CSV 番号（NO 值）**：随档以角色块数据落盘
 *   （CharaFileIO.kt:216-217 逐格含 NO）。DELCHARA 平移的是**注册下标**而非 NO
 *   （VariableStore.kt:2010、CharacterTable.kt:282-286 实证前移）——拿注册下标做键，
 *   删一个人之后全部值串位。故本类型**在结构上不提供下标形态**：不靠纪律靠构造。
 * - 投射的扩展角色 → **EntityId**（随 .rt2 实体载荷）。
 *
 * **ADDVOIDCHARA 匿名角色（NO=0）v1 不支持**：CharacterTemplate.pseudo() 只带 no=0/csvNo=0，
 * CharacterTable 无实例 ID 列，reference 亦无 UniqueIdentifier ⇒ 没有任何稳定实例身份。
 * [CsvNo] 构造期拒绝 0 与负数，把这条边界钉成不可表达，而不是留给实现者「记得判断」。
 * 将来需要则加实例注册表波次，不用位置猜身份。
 *
 * 同一 CSV 番号多实例（语料若允许同角色重复 ADDCHARA）共享 Mod 注入值——v1 文档明示；
 * 实例级区分需走投射实体 EntityId 路径。
 */
sealed interface CharIdentity {
    /** 原版 CSV 番号（NO 值）；0 = 匿名角色，v1 不支持持久化，构造期拒绝。 */
    data class CsvNo(val no: Int) : CharIdentity {
        init {
            require(no > 0) { "CSV 番号必须 > 0（NO=0 是 ADDVOIDCHARA 匿名角色，无稳定实例身份，v1 不支持）: $no" }
        }
    }

    /** 投射扩展角色的 EntityId；NULL 与非良构一律拒绝。 */
    data class Entity(val entityId: Long) : CharIdentity {
        init {
            require(entityId != EntityIdCodec.NULL) { "角色身份不能是 NULL EntityId" }
            require(EntityIdCodec.isWellFormed(entityId)) {
                "角色身份 EntityId 非良构: ${entityId.toString(16)}"
            }
        }
    }
}

/**
 * Mod 注入 CSV 值的持久化键（值本身由 M1-4 抽离面携带，本类只钉键的形状）。
 *
 * **[keyword] 保持原样大小写，不做 uppercase 归一**——CSV 关键字是 ERB 命名归一规则的
 * 例外：`ConstantData.cs:118/:120` 的默认比较器是精确匹配，运行期取值走
 * `table[keyword]`（VariableStore.kt:1047）。把关键字大写化会取不到格子。
 * 变量名/函数名/schema 名仍按 `Locale.ROOT` 大写归一（Config.cs:33），两者不可混用。
 */
class ModCsvValueKey(
    val packageId: String,
    val tableId: String,
    val keyword: String,
    val identity: CharIdentity,
) {
    init {
        require(packageId.isNotBlank()) { "packageId 为空" }
        require(tableId.isNotBlank()) { "tableId 为空" }
        require(keyword.isNotBlank()) { "CSV 关键字为空" }
    }

    /** 诊断/对账用的稳定文本形（关键字原样，身份按形态渲染）。 */
    override fun toString(): String = when (identity) {
        is CharIdentity.CsvNo -> "$packageId/$tableId[$keyword]@NO=${identity.no}"
        is CharIdentity.Entity -> "$packageId/$tableId[$keyword]@EID=${identity.entityId.toString(16)}"
    }
}

/** 域派生与载体路由的唯一入口（纯函数，无状态）。 */
object PersistenceDomains {

    /**
     * 从变量元信息派生持久化域；不可存档返回 null。
     *
     * 优先级复刻 SaveCapture.kt:117-118（CHARACTER 先于 GLOBAL，!isSavedata 不入域）。
     */
    fun domainOf(def: VarDef): PersistenceDomain? = when {
        !def.isSavedata -> null
        def.isCharacterData -> PersistenceDomain.CHARACTER
        def.isGlobal -> PersistenceDomain.GLOBAL
        else -> PersistenceDomain.SLOT
    }

    /**
     * 域 + Base/Mod → 载体与触发配对。
     *
     * [isMod] 是装载期事实（该声明来自哪个 Package），不是 Mod 可填的字段。
     * ENTITY 域与 isMod 无关（实体恒 .rt2、按槽走）。
     */
    fun routeOf(domain: PersistenceDomain, isMod: Boolean): PersistenceRoute = when (domain) {
        PersistenceDomain.SLOT -> if (isMod) {
            PersistenceRoute(domain, SaveCarrier.RT2_CHUNK, SaveOperation.SAVEGAME, SaveOperation.LOADGAME)
        } else {
            PersistenceRoute(domain, SaveCarrier.SAV_SLOT, SaveOperation.SAVEGAME, SaveOperation.LOADGAME)
        }
        PersistenceDomain.GLOBAL -> if (isMod) {
            PersistenceRoute(domain, SaveCarrier.GLOBAL_RT2, SaveOperation.SAVEGLOBAL, SaveOperation.LOADGLOBAL)
        } else {
            PersistenceRoute(domain, SaveCarrier.GLOBAL_SAV, SaveOperation.SAVEGLOBAL, SaveOperation.LOADGLOBAL)
        }
        PersistenceDomain.CHARACTER -> if (isMod) {
            PersistenceRoute(domain, SaveCarrier.RT2_CHARA, SaveOperation.SAVEGAME, SaveOperation.LOADGAME)
        } else {
            PersistenceRoute(domain, SaveCarrier.SAV_CHARA_BLOCK, SaveOperation.SAVEGAME, SaveOperation.LOADGAME)
        }
        PersistenceDomain.ENTITY ->
            PersistenceRoute(domain, SaveCarrier.RT2_ENTITY, SaveOperation.SAVEGAME, SaveOperation.LOADGAME)
    }

    /** 便捷入口：不可存档返回 null（与 [domainOf] 同口径）。 */
    fun routeOf(def: VarDef, isMod: Boolean): PersistenceRoute? =
        domainOf(def)?.let { routeOf(it, isMod) }
}

/**
 * 角色身份键的归一辅助：**表名**按变量层口径大写归一（表名是标识符），
 * **关键字原样保留**（见 [ModCsvValueKey]）。分开两个函数是为了让「哪一层归一」
 * 在调用点显式可见，避免一个 `normalize()` 把关键字一起大写化。
 */
object CsvIdentityKeys {
    /** 表名/包名等标识符归一（Config.cs:33 的 OrdinalIgnoreCase 口径）。 */
    fun normalizeIdentifier(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /**
     * 构造持久化键：表名归一、关键字原样。
     * [rawKeyword] 只做非空校验，绝不改大小写。
     */
    fun keyOf(packageId: String, rawTableId: String, rawKeyword: String, identity: CharIdentity): ModCsvValueKey =
        ModCsvValueKey(
            packageId = normalizeIdentifier(packageId),
            tableId = normalizeIdentifier(rawTableId),
            keyword = rawKeyword,
            identity = identity,
        )
}