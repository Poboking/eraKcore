package com.eratw.port.core.data.save

import com.eratw.port.core.vars.GameBaseData

/**
 * `GameBase` 的两条存档校验（`GameBase.cs:60-80`）——读侧三个入口（`CHKDATA` 的
 * [com.eratw.port.core.save.SaveDataProbe]、`LOADDATA` / `LOADGLOBAL` 的
 * [com.eratw.port.core.save.FileSystemOps]、`LOADCHARA` 的 [CharaFileIO]）共用同一条口径，
 * 免得三处各写一份近似。
 *
 * ## `ScriptVersionDefined` 为什么必须有独立一位
 *
 * 原版 `GameBase.CheckVersion`（GameBase.cs:69-80）第 1 条放行读的是**布尔位**
 * `ScriptVersionDefined`（GameBase.cs:25），它由 GameBase.csv 的 `バージョン` 行
 * 「有没有写、能不能解析出数字」决定（GameBase.cs:138-140 的
 * `ScriptVersionDefined = tryatoi(array[1], out ScriptVersion)`），**与解析出来的数值是不是 0 无关**：
 * `バージョン,0` ⇒ `ScriptVersionDefined = true` 且 `ScriptVersion = 0`，此时第 1 条不成立，
 * 只能走第 2/3 条；`バージョン,proto` ⇒ `ScriptVersionDefined = false` 且 `ScriptVersion = 0`
 * （tryatoi 失败时 `out` 落 0，GameBase.cs:82-104），此时第 1 条成立。
 *
 * [GameBaseData.versionDefined] 就是这一位，由 CSV 装载层按 `GameBase.cs:138-140` 填
 * （`GameBaseLoader` 里与 `version` 数值位分开记）；它没被交出（null，手工夹具或无 GAMEBASE.CSV）时
 * 退回 `version != 0` 的近似：该近似只在「`VERSION` 写了 0 且兼容下限 > 0」的游戏上偏松
 * （移植侧判通过、原版判 `VIRSION_ERROR`）。eraTW 实测不受影响：其 GameBase.csv 的
 * `バージョン` 值为 `proto`（`CsvDataAuditTest` / `GameBaseLoader` KDoc 同源）⇒ 两侧同走第 1 条。
 *
 * ## 三条放行条件（逐条对应 GameBase.cs:69-80）
 * 1. `!ScriptVersionDefined && target != 1000` → 通过（对旧档宽容）；
 * 2. `ScriptCompatibleMinVersion <= target` → 通过（该字段缺省 -1，GameBase.cs:27，
 *    由 GameBase.csv 的 `バージョン違い認める` 赋值，GameBase.cs:141-143）；
 * 3. `ScriptVersion == target`。
 */
object GameBaseGate {

    /**
     * `GameBase.UniqueCodeEqualTo`（GameBase.cs:60-67）：存档侧写 0 时豁免，
     * 否则必须与 `ScriptUniqueCode` 相等。
     */
    fun uniqueCodeEqualTo(gameBase: GameBaseData, target: Long): Boolean =
        target == 0L || target == gameBase.gameCode

    /**
     * `GameBase.CheckVersion`（GameBase.cs:69-80）。
     *
     * @param scriptVersionDefined 真值；null = 调用方没单独交出这一位，按
     *   [GameBaseData.versionDefined] → `version != 0` 的次序退回（差异与原因见类 KDoc）。
     */
    fun checkVersion(
        gameBase: GameBaseData,
        target: Long,
        scriptVersionDefined: Boolean? = null,
    ): Boolean {
        val defined = scriptVersionDefined ?: gameBase.versionDefined ?: (gameBase.version != 0L)
        if (!defined && target != 1000L) return true
        if (gameBase.allowVersion <= target) return true
        return gameBase.version == target
    }
}
