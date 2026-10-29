package com.eratw.port.core.config

import java.util.Locale

/**
 * 一次 `ConfigData` 的全部条目状态 —— C# 的 `configArray` / `replaceArray` / `debugArray`
 * 三个列表（spec: `ConfigData.cs:23-27`）合成一个实例对象。
 *
 * 本类是**实例**而不是单例：原版 `ConfigData.Instance` 是静态可变全局
 * （spec: `ConfigData.cs:21`、`:29-36` 的静态构造），移植侧按铁律 4 不许有那种东西，
 * 所以这里由调用方（M5 的宿主装配层，或 `store.aux` 一类的会话状态）持有并传递。
 *
 * 键查找的三个入口与 C# 的三处循环一一对应，**匹配范围各不相同**，这是本类最需要小心的地方：
 *   * [resolveGame] 比 `Name` / `Text` / `EngText`（spec: `ConfigData.cs:262-284`）；
 *   * [resolveReplace] 与 [resolveDebug] 只比 `Name` / `Text`
 *     （spec: `ConfigData.cs:298-316`、`:330-348`）—— 英文标签在 `fixed.config` 一类的
 *     主文件里对 `replaceArray` 成员有效吗？**无效**，因为主文件的读取只走
 *     `GetConfigItem`（spec: `ConfigData.cs:539`），压根不查后两组；
 *   * [resolveAny] 是 `GetItem(string)` 的三级串联（spec: `ConfigData.cs:236-248`），
 *     供 `GETCONFIG` 一类的 ERB 读数用（spec: `ConfigData.cs:350-352`）。
 */
class ConfigStore(specs: List<ConfigSpec> = ConfigSpecTable.all) {

    private val ordered: List<ConfigItem> = specs.map { ConfigItem(it) }

    /** 顺序即注册顺序，写出配置文件时要按它遍历（spec: `ConfigData.cs:430-453`）。 */
    val items: List<ConfigItem> get() = ordered

    private val byKey: Map<ConfigKey, ConfigItem> = ordered.associateBy { it.spec.key }

    /** 按标识取条目 —— `GetConfigItem(ConfigCode)` / `GetReplaceItem` / `GetDebugItem` 的合写（spec: `ConfigData.cs:222-234`）。 */
    fun item(key: ConfigKey): ConfigItem? = byKey[key]

    /** 读数用的强版本：条目不存在就立即失败，而不是把 null 传到调用点。 */
    fun itemOf(key: ConfigKey): ConfigItem = checkNotNull(byKey[key]) { "配置条目表里没有 $key" }

    fun itemsOf(group: ConfigGroup): List<ConfigItem> = ordered.filter { it.spec.group === group }

    /** 主文件（`emuera.config` / `default.config` / `fixed.config`）的键解析口径。 */
    fun resolveGame(upperKey: String): ConfigItem? = firstMatch(ConfigGroup.GAME, upperKey, englishLabel = true)

    /** `_Replace.csv` 的键解析口径（不认英文标签）。 */
    fun resolveReplace(upperKey: String): ConfigItem? = firstMatch(ConfigGroup.REPLACE, upperKey, englishLabel = false)

    /** `debug.config` 的键解析口径（不认英文标签）。 */
    fun resolveDebug(upperKey: String): ConfigItem? = firstMatch(ConfigGroup.DEBUG, upperKey, englishLabel = false)

    /**
     * `GetItem(string)` 的三级串联（spec: `ConfigData.cs:236-248`）：config → replace → debug，
     * 键先 `ToUpperInvariant`（spec: `ConfigData.cs:264`、`:300`、`:332`）。
     * ERB 侧的 `GETCONFIG` / `GETCONFIGS` 用的就是这条（读数形状见
     * `ConfigData.GetConfigValueInERB`，spec: `ConfigData.cs:350-422`）。
     * M4-J j-dispatch 已合流：该读数的实现（`core/fn/ConfigMethods.kt`）与 `core/fn/DebugMiscMethods.kt`
     * 的 GETLINESTR 都吃**本层这一份表**；`DebugMiscMethods` 曾自带的私有条目表
     * （曾有 `CONFIG_ROWS` 125 行手抄 + 一套平行值模型与解析器）已删除，避免两张表各自漂移。
     */
    fun resolveAny(rawKey: String): ConfigItem? {
        val upperKey = rawKey.uppercase(Locale.ROOT) // ConfigData.cs:264 / :300 / :332 各自的大写化
        return resolveGame(upperKey) ?: resolveReplace(upperKey) ?: resolveDebug(upperKey)
    }

    /** 当前值快照（不可变副本），给单测与告警回显用；原版没有对应物（它直接读静态字段）。 */
    fun snapshot(): Map<ConfigKey, ConfigValue> = ordered.associate { it.spec.key to it.value }

    /**
     * `ReLoadConfig` 的前半段：**只清 `configArray` 的钉位**
     * （spec: `ConfigData.cs:466-474` 遍历的是 `configArray`，`debugArray` / `replaceArray`
     * 的 `Fixed` 永不清除）。原版如此，本层照抄；实际上后两组本来也没有钉位入口
     * （只有 `loadConfig(text, fix: true)` 会置位，spec: `ConfigData.cs:573-576`）。
     */
    fun unfixGameItems() {
        itemsOf(ConfigGroup.GAME).forEach { it.fixed = false }
    }

    /** `ConfigData.Copy`（spec: `ConfigData.cs:177-202`）：逐项 `CopyTo`，钉位一并带过去。 */
    fun copy(): ConfigStore {
        val other = ConfigStore(specsOfThis())
        for (i in ordered.indices) {
            ordered[i].copyTo(other.items[i])
        }
        return other
    }

    private fun specsOfThis(): List<ConfigSpec> = ordered.map { it.spec }

    private fun firstMatch(group: ConfigGroup, upperKey: String, englishLabel: Boolean): ConfigItem? {
        for (item in ordered) {
            if (item.spec.group === group && item.spec.matchesUpperKey(upperKey, englishLabel)) {
                return item
            }
        }
        return null // 对应三处查找的 `return null`（ConfigData.cs:283 / :315 / :347）
    }
}
