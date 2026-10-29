package com.eratw.port.core.fn

import com.eratw.port.core.config.parseBoolLiteral

/**
 * 语句层要读的**兼容性开关**（原版 `Config.CompatiXxx` 静态属性里被执行路径消费的那些）。
 *
 * 读取源与本工程既有口径同源：`BuiltinHost.configOverrides` 就是 `emuera.config`
 * 那一层（spec: `proc/BootHosts.kt` 的 configOverrides 说明；同形态消费点见
 * `HostMethods.kt` 的 ValidExtension）。键的匹配 = `ConfigData.GetItem` 的
 * 「键大写化后比 Name / Text / EngText」（spec: `Runtime.Config/ConfigData.cs:260-284`），
 * 值的解析 = `parseBoolLiteral`（spec: `ConfigItem.cs:227-250`），
 * 无法解析时保留旧值 = 缺省值（spec: `ConfigData.cs:563-586`），不报错、不中断装载。
 *
 * ## SP 门控的唯一判定源（CONTRACT-M4-I §2 i-erbcround）
 *
 * 原版有**两个** Lang id，源文案逐字相同（`Lang.cs:2266` 与 `:3826` 是同一句日文），
 * 但本作 zhs 语言表给了**不同**的译文 ⇒ 语句层与函数层不能共用一个字符串：
 * - [SP_CHARA_CONFIG_IS_OFF]（`Error.SPCharaConfigIsOff`）= 语句层 `ADDSPCHARA` 的抛错文案
 *   （spec: `FunctionIdentifier.cs:1360-1363`）；
 * - [SP_CHARACTER_FEATURE_DISABLED]（`Error.SPCharacterFeatureDisabled`）= 函数层的抛错文案
 *   （spec: `FunctionMethodCreator.cs:3168` GETSPCHARA、`:3224` CsvStrData、`:3258` Csvcstr、
 *   `:3315` CsvData、`:3414` ExistCsv）。
 *
 * 两侧的门控条件一律走 [spCharaEnabled]，禁止在函数体里再硬编码「SP 未开启」的字面量。
 */

/**
 * `CompatiSPChara`（SP 角色总开关）。
 * spec: `ConfigData.cs:106` —— `new ConfigItem<bool>(ConfigCode.CompatiSPChara,
 * "SPキャラを使用する", "Allow SP characters", t: false)`，**缺省 false**；
 * 枚举号 73（spec: `ConfigCode.cs:77`），读入静态属性的点是 `Config.cs:163` + `:377`。
 * 本作 `emuera.config` 第 59 行实测 `ALLOW SP CHARACTERS:NO` ⇒ 关闭。
 */
private val SP_CHARA_KEYS = setOf(
    "COMPATISPCHARA", // ConfigCode 枚举名
    "SPキャラを使用する", // ConfigData.cs:106 的日文 Text
    "ALLOW SP CHARACTERS", // ConfigData.cs:106 的 EngText（emuera.config 落盘形态）
)

/**
 * `Config.CompatiSPChara` 的语句层读出。
 *
 * 无宿主（语句层裸假体）/ 键缺失 / 值不可解析 ⇒ **false**（与 `ConfigData.cs:106` 的缺省一致）。
 */
fun spCharaEnabled(host: BuiltinHost): Boolean {
    val entries = host.configOverrides
    // 快路径：配置文件里的键就是大写英文形态（`emuera.config` 落 `ALLOW SP CHARACTERS`），
    // 先按三个已知键精确查；命中即返回，不再整表扫描 + 逐键 `trim().uppercase()`。
    // 真机采样里旧实现（每调一次扫完整张表）占世界初始化那页 1.5%。
    for (k in SP_CHARA_KEYS) {
        val raw = entries[k] ?: continue
        return parseBoolLiteral(raw.trim()) ?: false
    }
    // 慢路径：保留原有「键 trim 后大小写不敏感」口径（枚举名/日文键的混合大小写形态）。
    val raw = entries.entries
        .firstOrNull { it.key.trim().uppercase() in SP_CHARA_KEYS }
        ?.value ?: return false
    return parseBoolLiteral(raw.trim()) ?: false
}

/**
 * SP 关闭时**语句层**（`ADDSPCHARA`）的错误文案。
 *
 * 原版文案 id = `Lang.Error.SPCharaConfigIsOff`（spec: `Runtime.Utils.EvilMask/Lang.cs:2266`），
 * 抛出点 = `FunctionIdentifier.cs:1360-1363`（`!Config.CompatiSPChara && isSp`，
 * **在任何实参求值之前**）；本串取本作既定口径的 zhs 语言表
 * （`lang/emuera.zhs.xml:631`：`SP角色相关功能不可用(请启用兼容性设置「使用SP角色」)`）。
 */
const val SP_CHARA_CONFIG_IS_OFF: String =
    "SP角色相关功能不可用(请启用兼容性设置「使用SP角色」)"

/**
 * SP 关闭时**函数层**（`GETSPCHARA` 与各 CSV 访问器的 `isSp` 实参）的错误文案。
 *
 * 原版文案 id = `Lang.Error.SPCharacterFeatureDisabled`（spec: `Runtime.Utils.EvilMask/Lang.cs:3826`，
 * 日文源文案与 `SPCharaConfigIsOff` 逐字相同，但 zhs 表是另一条译文），
 * 抛出点见 `FunctionMethodCreator.cs:3168` / `:3224` / `:3258` / `:3315` / `:3414`；
 * 本串取 zhs 语言表（`lang/emuera.zhs.xml:632`）。
 */
const val SP_CHARACTER_FEATURE_DISABLED: String =
    "不能在标准设置下使用SP角色相关功能(请启用兼容性设置「使用SP角色」)"
