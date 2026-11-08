package com.eratw.port.core.proc

import com.eratw.port.core.code.ResolverPolicy

/**
 * 目标语料版本（本仓双线维护 4.980 / 4.981）。
 *
 * **v1 只声明、不分叉**：任何「按版本走不同行为」的规则都必须落成
 * [RuntimeCompatibilityProfile] 的**具名 facet** 并经 ABI 评审，不许在消费点散写
 * `if (profile.corpusVersion == …)` —— 散写就是第二套代码路径，正是 §9 明令禁止的形态。
 *
 * 加值（例如将来 4.982）= 编译期在本枚举的消费方全红，强制逐个决定「新版本是否复用
 * 旧语义」；不给缺省回落，避免新语料静默沿用旧口径。
 */
enum class CorpusVersion { V4_980, V4_981 }

/**
 * 标识符归一口径（§5 NamePolicy）。**只有一个实现值**：原版标识符字典就是
 * `StringComparer.OrdinalIgnoreCase`（reference: Config.cs:33，切换点在 :303/:308），
 * 移植侧归一实现 = trim + 大写（[com.eratw.port.core.patch.NamePolicy.canonical]）。
 *
 * 单值枚举是**能力标识**，不是旋钮：原版没有「大小写敏感标识符」这个模式。CSV 关键字
 * 的区分大小写是**数据层**规则（`ConstantData.cs:118/:120`），不是这里的分叉点。
 */
enum class IdentifierFolding { ORDINAL_IGNORE_CASE }

/**
 * 事件注册口径（§6 / M3-1）。四桶执行序
 * `ONLY(0) → PRI(1) → NORMAL(2) → LATER(3)`，同桶按链接序：
 * 分桶见 reference: `LabelDictionary.cs:90-105`，逐桶推进见 `CalledFunction.cs:228-243`。
 *
 * **`CompatiCallEvent` 不在这里**：它是玩家可改的 Config 键
 * （[com.eratw.port.core.config.ConfigKey.CompatiCallEvent]，`ConfigCode.cs:75`，出厂
 * false），由 [ProcessRuntime] 的 config 通道单点读取。把它塞进 profile 会造成
 * 「Config 键 vs profile facet」两份真源。
 */
enum class EventRegistrationPolicy { FOUR_BUCKET }

/**
 * 装载策略的能力标识（§9.1）。v1 = **EAGER_ONLY**：本移植尚未实现 `UseLazyLoading`
 * （全仓只有 config 键、无消费代码），兼容性报告必须如实展示这一能力标识，防后来者
 * 误以为已完整复刻原版装载行为。
 *
 * 懒激活落地后新增 `LAZY_ACTIVATION`：加值会让所有消费方的 exhaustive `when` 编译期变红
 * —— 这正是要求，六通道同覆盖（§9.1）不许漏一条。
 */
enum class LazyLoadingPolicy { EAGER_ONLY }

/**
 * 脚本重载语义（§9）。原版 `ReloadErb` 把 ERB 目录**重新装进既有 labelDic**
 * （reference: `Process.cs:350-358`：`saveCurrentState` → `System_Reloaderb` →
 * `LoadErbDir(..., labelDic)`；局部重载 `:360-367` 同形）⇒ 重注册，胜者可随之改变。
 *
 * 对 Mod 架构的约束（§9.1）：Legacy 下 LSC 必须支持**受控增量重解析**
 * （同一 [com.eratw.port.core.code.SymbolCatalog] 构建器重算受影响键），不是第二份符号真相；
 * Modern 下重载不得改变解析结果。
 */
enum class ReloadPolicy { RE_REGISTER }

/**
 * 存档装载顺序（§8）。原版 LOADDATA **先复位再解析**：
 * reference: `VariableEvaluator.cs:2626-2627`（`SetDefaultValue` / `SetDefaultLocalValue`
 * 先于读角色块），移植侧台账见 `specs/verify-notes.md:1445`。
 *
 * 与 Mod 的关系：`.rt2` 的实体/注册表恢复也必须遵守同一顺序 —— 先复位到声明缺省，再灌
 * 载存档数据，否则「存档里缺的域」会继承上一局残值。
 */
enum class SaveLoadPolicy { RESET_THEN_PARSE }

/**
 * 运行期兼容档案（§9）：**一个参数对象、一条管线**，不是第二套代码路径。
 *
 * ## 六个 facet + 版本声明
 *
 * | facet | 声明什么 | 真源 |
 * |---|---|---|
 * | [corpusVersion] | 目标语料版本（4.980 / 4.981） | [CorpusVersion] |
 * | [functionResolution] | 符号胜者选择（last-wins / 唯一符号 + 显式覆盖） | [ResolverPolicy] |
 * | [identifierFolding] | 标识符归一 | [IdentifierFolding] |
 * | [eventRegistration] | 事件四桶 | [EventRegistrationPolicy] |
 * | [lazyLoading] | 装载能力标识 | [LazyLoadingPolicy] |
 * | [reload] | 重载语义 | [ReloadPolicy] |
 * | [saveLoad] | 存档装载顺序 | [SaveLoadPolicy] |
 *
 * 逐函数覆盖政策 [com.eratw.port.core.patch.OverridePolicy] 不在这里：它是 **Module 侧
 * 逐函数声明**（MODERN 下生效），profile 只决定「是否承认覆盖」这一档
 * （[functionResolution] = MODERN 才看它）。
 *
 * ## 三条纪律
 *
 * 1. **所有字段都是枚举**：profile 只表达「引擎声明了哪条兼容事实」，不装布尔/字符串/列表
 *    —— 后者会变成自由旋钮，正是反模式。构造器已把这一点写进类型。
 * 2. **单值枚举是能力标识**：加值 = 所有消费方编译期变红，强制逐个接线；不给运行时缺省回落。
 * 3. **用户可配置项不进 profile**（例：`CompatiCallEvent` 留在
 *    [com.eratw.port.core.config.ConfigKey]）：一个事实只有一个出处。
 *
 * ## 接线状态（M1-0）
 *
 * 本波只落**类型**：生产路径尚未读取本档案，[com.eratw.port.core.patch.ModuleLinker.link]
 * 的 `policy` 参数仍由调用方显式传 [ResolverPolicy]。装配（GameSession / GameHost 选档 →
 * 链接器 / ProcessRuntime）是 M1/M2-2 的接线面，不在这里假装已完成。
 *
 * @param corpusVersion 目标语料版本（v1 仅声明，见 [CorpusVersion]）
 * @param functionResolution 符号解析政策（LEGACY_LAST_WINS / MODERN）
 */
data class RuntimeCompatibilityProfile(
    val corpusVersion: CorpusVersion,
    val functionResolution: ResolverPolicy,
    val identifierFolding: IdentifierFolding = IdentifierFolding.ORDINAL_IGNORE_CASE,
    val eventRegistration: EventRegistrationPolicy = EventRegistrationPolicy.FOUR_BUCKET,
    val lazyLoading: LazyLoadingPolicy = LazyLoadingPolicy.EAGER_ONLY,
    val reload: ReloadPolicy = ReloadPolicy.RE_REGISTER,
    val saveLoad: SaveLoadPolicy = SaveLoadPolicy.RESET_THEN_PARSE,
) {

    companion object {

        /**
         * Legacy-ERATW 档（4.980 语料）：普通函数 last-wins（真实注册序）、事件四桶、
         * 官方 bug 全复刻 —— 即**本移植当前的全部行为**。目标是现有语料逐位不变。
         *
         * 注意：4.980 / 4.981 两档目前**逐字段相同**，只差 [corpusVersion]
         * （见单测）。这不是偷懒：两个档案的差异必须先落成具名 facet 才能出现，
         * 不许靠「读版本号在消费点分叉」偷偷产生。
         */
        val LEGACY_ERATW_4980: RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(CorpusVersion.V4_980, ResolverPolicy.LEGACY_LAST_WINS)

        /** Legacy-ERATW 档（4.981 语料）；与 4.980 档只差版本声明。 */
        val LEGACY_ERATW_4981: RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(CorpusVersion.V4_981, ResolverPolicy.LEGACY_LAST_WINS)

        /**
         * Modern 档：默认唯一符号（同名未声明覆盖 = 链接期拒绝），覆盖须显式声明并过
         * 完整签名闸（[com.eratw.port.core.code.SymbolCatalog]）。
         *
         * 语料版本仍须显式给：Modern 改变的是**符号解析**，不是运行哪套语料。
         */
        fun modern(corpusVersion: CorpusVersion): RuntimeCompatibilityProfile =
            RuntimeCompatibilityProfile(corpusVersion, ResolverPolicy.MODERN)
    }
}
