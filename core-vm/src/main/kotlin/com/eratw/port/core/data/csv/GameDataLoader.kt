package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import java.nio.file.Path

/**
 * 游戏数据总装：一次装载 = VariableSize → 29 槽名字表 → GAMEBASE → 角色模板。
 *
 * 顺序依据 ConstantData.LoadData（ConstantData.cs:672-757）：
 * 1. `loadVariableSizeData`（:680）——必须先于名字表：29 槽长度（MaxDataList）以它调整后的为准；
 * 2. 29 个 `loadDataTo`（:685-713）——槽号与文件名逐条照抄，见 [SLOT_FILES]；
 * 3. `loadCharacterData`（:750）——角色模板在名字表**之后**：模板行的值要走名字表反查
 *    （如 `基礎,体力,2000` 的「体力」查 base.csv  dic）；
 * 4. GAMEBASE 不在 LoadData 里——它由 Process 启动流程单独装载（GameBase.cs:106，
 *    Process.cs:283），本总装按同一数据口径一并读出，供 registry 构造注入。
 *
 * 用法：
 * ```
 * val data = GameDataLoader.load(csvDir) ?: return  // 目录缺失 → null（优雅降级）
 * val store = VariableStore(data.registry)
 * data.templates.forEach { store.registerTemplate(it) }
 * ```
 */
class GameData(
    val gameBase: GameBaseData,
    val registry: VariableRegistry,
    val templates: List<CharacterTemplate>,
    val warnings: List<String>,
    /**
     * ITEM 价格（Item.csv 第 3 列）。原版是独立的 `long[] ItemPrice`（ConstantData.cs:713），
     * 本移植的 ITEMPRICE 是系统变量（store int1D[60]），故价格在此暂存，
     * **由调用方在建 store 后写入**：`writeInt("ITEMPRICE", longArrayOf(i), price)`。
     */
    val itemPrices: LongArray,
)

object GameDataLoader {

    /**
     * 29 槽 → 文件名。spec: ConstantData.cs:685-713 的 loadDataTo 调用序列（顺序即槽号）。
     * 槽 6（ITEM）额外填 ItemPrice（本移植 = `sizes.int1D[60]`，VariableSizes.kt:79 同口径）。
     */
    val SLOT_FILES: List<String> = listOf(
        "Abl.csv",     // 0 ABL
        "Exp.csv",     // 1 EXP
        "Talent.csv",  // 2 TALENT
        "Palam.csv",   // 3 PALAM
        "Train.csv",   // 4 TRAIN
        "Mark.csv",    // 5 MARK
        "Item.csv",    // 6 ITEM（+ItemPrice）
        "Base.csv",    // 7 BASE
        "Source.csv",  // 8 SOURCE
        "Ex.csv",      // 9 EX
        "Str.csv",     // 10 STR
        "Equip.csv",   // 11 EQUIP
        "Tequip.csv",  // 12 TEQUIP
        "Flag.csv",    // 13 FLAG
        "Tflag.csv",   // 14 TFLAG
        "Cflag.csv",   // 15 CFLAG
        "Tcvar.csv",   // 16 TCVAR
        "Cstr.csv",    // 17 CSTR
        "Stain.csv",   // 18 STAIN
        "Cdflag1.csv", // 19 CDFLAG1
        "Cdflag2.csv", // 20 CDFLAG2
        "Strname.csv", // 21 STRNAME
        "Tstr.csv",    // 22 TSTR
        "Savestr.csv", // 23 SAVESTR
        "Global.csv",  // 24 GLOBAL
        "Globals.csv", // 25 GLOBALS
        "Day.csv",     // 26 DAY
        "Time.csv",    // 27 TIME
        "Money.csv",   // 28 MONEY（moneyIndex）
    )

    /**
     * 装载全部游戏数据。目录不存在 / 不可读 → null（调用方按"无数据"降级，
     * 例如 CI 环境没有游戏数据时 E2E 仍应能跑其余分节）。
     *
     * 下标越界口径：**不扩容**。原版 names 数组在 LoadData 一次性定型
     * `names[i] = new string[MaxDataList[i]]`（ConstantData.cs:678），loadDataTo 对越界下标
     * 只告警跳过（OoRArray + continue，ConstantData.cs:1791-1795）、对 0 长度数组告警后
     * 终止本文件（ProhibitedArrayName + break，:1786-1790），没有按 CSV 内容扩容的分支。
     * 超出 MaxDataList 的下标由 [CsvLoader.loadNameTable] 的越界跳过承接。
     * （2026-10-22 移除了此前的 autoGrowByCsvExtent 试探性扩容——与官方口径不符且在本语料是死代码。）
     */
    fun load(csvDir: Path): GameData? {
        if (!java.nio.file.Files.isDirectory(csvDir)) return null
        val warnings = ArrayList<String>()
        val sizes = VariableSizes()
        VariableSizeLoader.load(csvDir, sizes, warnings)
        // GameBase 先读：registry 的 gameBase 是构造期只读字段（VariableRegistry.kt:58）
        val gameBase = GameBaseLoader.load(csvDir, warnings)
        val registry = VariableRegistry(sizes, gameBase)
        val itemPrices = LongArray(sizes.maxDataList[6])
        for (slot in SLOT_FILES.indices) {
            val prices = if (slot == 6) itemPrices else null
            CsvLoader.loadNameTable(csvDir, SLOT_FILES[slot], slot, registry.csvTables[slot], prices, warnings)
        }
        val templates = CharaCsvLoader.load(csvDir, registry, warnings)
        return GameData(gameBase, registry, templates, warnings, itemPrices)
    }
}
