package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.GameBaseData
import java.nio.file.Path

/**
 * GAMEBASE.CSV 装载。spec: `GameBase.LoadGameBaseCsv`（GameBase.cs:106-211）。
 *
 * 键位逐条对照原版 switch（GameBase.cs:141-190）：
 * - `コード` → gameCode（=0 时原版另有 SaveCodeIs0 告警，本移植只记录）
 * - `バージョン` → version；非整数时原版 `out` 落 0 且 `ScriptVersionDefined=false`，
 *   本游戏实值 "proto" 正是该路径 → version=0
 * - `バージョン違い認める` → allowVersion（"0012" → 12）
 * - `最初からいるキャラ` → defaultChara（键缺失才保持原版默认 -1；值坏掉落 0，见下）
 * - `アイテムなし` → noItem
 * - `タイトル` / `作者` / `製作年` / `追加情報` → title / author / year / info
 * - `バージョン情報URL` → url；`バージョン名` → versionName
 * - `ウィンドウタイトル`：只用于窗口标题（GameBase.cs:162-164 + :200-210 的派生），本端口无消费方，跳过
 * - `動作に必要なEmueraのバージョン`：⚠️ 不是纯 UI —— 原版解析失败或要求版本更高时
 *   `return false` 拒绝启动（GameBase.cs:165-181）。本语料无该键（实测 GameBase.csv 7 行），
 *   本移植不实现该门槛；换游戏时若带该键需接线（登记项）。
 *
 * 注意：本游戏作者行的键是 `弄ってみた人`，**不在原版 switch 里**（原版只认 `作者`），
 * 故 author 保持空串——这是原版行为，不是装载遗漏。
 *
 * `tryatoi`（值解析，GameBase.cs:82-104）口径：先整体 `long.TryParse`（两端空白 + 可选符号 +
 * ASCII 数字），失败再退到「开头连续 **`char.IsNumber`** 字符（Unicode 类别 N）」整段再 `TryParse`
 * （`0012abc` → 12；`1２3` → 整段含全角数字 ⇒ TryParse 拒 ⇒ 0），再失败取 **0**。
 * 谓词与两段式细节见 [tryatoi]。
 * 注意：解析失败时原版 `out` 形参已被写成 0 ⇒ 字段被赋 0（**不是保持默认**），
 * 故 `最初からいるキャラ,` 这种空值在 PC 侧得到 0 而不是 -1。本装载器按此照抄。
 * 文本键（タイトル/作者/…）取第 2 列**原文**（GameBase.cs:128 的 `array[1].Trim();` 是丢弃结果的
 * 空调用，原版并未 trim）。
 */
object GameBaseLoader {

    fun load(dir: Path, warnings: MutableList<String>): GameBaseData {
        val path = CsvLoader.resolveFile(dir, "GameBase.csv") ?: return GameBaseData()
        var gameCode = 0L
        var version = 0L
        var versionDefined: Boolean? = null
        var allowVersion = -1L
        var defaultChara = -1L
        var noItem = 0L
        var title = ""
        var author = ""
        var year = ""
        var info = ""
        var url = ""
        var versionName = ""
        for (raw in CsvLoader.readEnabledLines(path, warnings, "GameBase.csv")) {
            val cols = CsvLoader.splitRow(raw)
            if (cols.size < 2) continue
            val key = cols[0]
            when (key) {
                "コード" -> {
                    // GameBase.cs:132-137：先赋值再判 0
                    gameCode = tryatoi(cols[1]) ?: 0L
                    if (gameCode == 0L) {
                        warnings.add("GameBase.csv: 代码为 0 或非整数（原版 SaveCodeIs0 告警）")
                    }
                }
                // GameBase.cs:138-140：ScriptVersionDefined = tryatoi(...)，ScriptVersion 同源落 0
                "バージョン" -> {
                    // GameBase.cs:138-140：ScriptVersionDefined = tryatoi(array[1], out ScriptVersion)
                    // ⇒ 这一位记的是「能不能解析出数字」，与解析出来的值是不是 0 无关；
                    // tryatoi 失败时 out 落 0（GameBase.cs:82-104），所以 version 位与真值位要分开记。
                    val parsed = tryatoi(cols[1])
                    version = parsed ?: 0L
                    versionDefined = parsed != null
                }
                "バージョン違い認める" -> allowVersion = tryatoi(cols[1]) ?: 0L
                "最初からいるキャラ" -> defaultChara = tryatoi(cols[1]) ?: 0L
                "アイテムなし" -> noItem = tryatoi(cols[1]) ?: 0L
                "タイトル" -> title = cols[1]
                "作者" -> author = cols[1]
                "製作年" -> year = cols[1]
                "追加情報" -> info = cols[1]
                "バージョン情報URL" -> url = cols[1]
                "バージョン名" -> versionName = cols[1]
                else -> Unit // 未识别键：原版同样静默忽略（switch default 缺省）
            }
        }
        return GameBaseData(
            author = author, info = info, year = year, title = title, url = url,
            versionName = versionName, gameCode = gameCode, version = version,
            versionDefined = versionDefined,
            allowVersion = allowVersion, defaultChara = defaultChara, noItem = noItem,
        )
    }

    /**
     * C# `GameBase.tryatoi`（GameBase.cs:82-104）：整体 `long.TryParse` → 退到「开头连续
     * **`char.IsNumber`** 字符」再 `long.TryParse` → 失败 null。调用方以 0 兜底
     * （原版 `out` 形参在两条 TryParse 失败路径上都被写成 0，:83 / :98）。
     *
     * 第二条的取串谓词是 `char.IsNumber`（:93，Unicode 类别 N = Nd ∪ Nl ∪ No），**不是**「ASCII 数字」：
     * 它会把连在一起的数字类字符**全部**收进串里再整体解析，所以 `1２3`（半角 1 + 全角 ２ + 半角 3）
     * 在原版收 "1２3" → `long.TryParse` 只认 ASCII ⇒ 失败 ⇒ 落 **0**；
     * 若按「遇到首个非 ASCII 数字就截断」实现会得到 1 —— 差一个数量级，故这里照抄 IsNumber +
     * 整体解析的两段式。⚠️ 实测本语料 `CSV/GameBase.csv` 全部 7 行的值列**无任何类别 N 的非 ASCII
     * 字符**（`コード,7153` / `バージョン,proto` / `バージョン違い認める,0012` / `製作年,2013～2026`…）
     * ⇒ 两条实现对本语料同解；`～`(U+FF5E) 是 Po 不是 N，不会进数字串。
     */
    private fun tryatoi(raw: String): Long? {
        dotnetLongOrNull(raw)?.let { return it }
        var end = 0
        while (end < raw.length && isDotNetNumber(raw[end])) end++
        if (end == 0) return null
        // 注意：这里解析的是**整段** IsNumber 串（原版 :98），不是截到首个 ASCII 数字。
        return dotnetLongOrNull(raw.substring(0, end))
    }

    /** .NET `char.IsNumber`：Unicode 类别 N（Nd / Nl / No）。 */
    private fun isDotNetNumber(c: Char): Boolean =
        when (Character.getType(c).toInt()) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt(),
            -> true

            else -> false
        }

    /** .NET `long.TryParse` 的 `NumberStyles.Integer`：两端空白 + 可选符号 + ASCII 数字。 */
    private fun dotnetLongOrNull(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var i = 0
        if (s[0] == '+' || s[0] == '-') i = 1
        if (i >= s.length) return null
        for (k in i until s.length) if (s[k] !in '0'..'9') return null
        return s.toLongOrNull()
    }
}
