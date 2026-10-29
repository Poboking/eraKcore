package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.UserVar
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore

/**
 * 变量池 → [SaveData]（原版 `VariableEvaluator.SaveToStreamBinary` / `SaveGlobalToStreamBinary`
 * 的抓取侧）。
 *
 * 进档的筛选口径**只认 `VarDef.isSavedata` 位**，不按名字猜（VariableToken.cs:88-154 的位运算
 * 已在 `VariableCode.isSavedata` 复刻）：
 * - 普通档：`isSavedata && !isCharacterData && !isGlobal`（VariableData.cs:3980-3997）
 *   + 非角色非全局的用户 `#DIM SAVEDATA` 静态变量；
 * - 全局档：`isSavedata && isCharacterData == false && isGlobal`（VariableData.cs:3961-3978）
 *   + 用户 `#DIM GLOBAL SAVEDATA` 变量（VariableData.cs:3974-3977）；
 * - 角色块：直接复用 [CharaFileIO.captureCharacter] 的公开入口
 *   [CharaFileIO.capture]（同一份 `isSavedata && isCharacterData && !isGlobal` 口径，
 *   CharacterData.cs:513-558），避免第二套角色字段清单。
 *
 * 不在本对象的旧口径（M6-SAVE-REST 已接）：Map/Xml/DT 扩展段（`emData`）。
 * 原版由 `ConstantData.SaveMaps/SaveXmls/SaveDTs` 决定（VariableData.cs:3911-3959），
 * 本仓名字表来自 [EmSaveSets]（`VarExt*.csv`），池读写走 `core/fn/EmDataPools`
 * （`fn/MapDataTableMethods.kt` 尾部）。
 *
 * ## 已知边界：没注册的变量不写
 *
 * 抓取面完全由 [VariableStore] 的注册表决定（`registry.allInVarTokenDicOrder()` +
 * `savedUserVarList()`），与 `varTokenDic`（VariableData.cs:3982）同口径。因此**必须已经注册**才会进档：
 * 用户 `#DIM SAVEDATA` 变量依赖启动期 ERH 装载（`SystemVariableBootstrap.install`，
 * `GameSession.kt:207-212`）。只建 CSV 注册表、不装 ERH 声明时，eraTW 真档的 540 条全局
 * 条目里只有 63 条系统变量可写，其余 477 条**静默不进档** —— 这不是本对象的 bug，
 * 而是「变量池不知道这些变量存在」的必然结果（读侧 `applyTo` 对未知键同样是丢弃，
 * VariableData.cs:4081+）。装置侧的最小正确做法见 `SaveRoundTripTest.realStore`。
 */
object SaveCapture {

    /**
     * 普通存档（`save{no:00}.sav`）。
     *
     * @param text 存档描述文本（`SAVEDATA_TEXT`；`SAVEDATA` 语句直接传参，UI 存档流程自己拼时间戳，
     *   Process.cs:2573/1327-1349）
     * @param compressed 二进制分支是否 GZip（`SystemSaveInBinary && ZipSaveData`，EraBinaryDataWriter.cs:22-32）
     * @param emData 扩展段名字表（`SAVE_MAPS/SAVE_XMLS/SAVE_DTS`）；空表 = 不写扩展段
     */
    fun normal(
        store: VariableStore,
        text: String,
        compressed: Boolean = true,
        emData: EmDataNames = EmDataNames.EMPTY,
    ): SaveData {
        val gb = store.registry.gameBase
        val count = store.characters.count
        val blocks = if (count == 0) {
            emptyList()
        } else {
            CharaFileIO.capture(store, (0L until count.toLong()).toList(), text, compressed).characters
        }
        return SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = gb.gameCode,
            scriptVersion = gb.version,
            text = text,
            characters = blocks,
            variables = systemEntries(store, global = false) + userEntries(store, global = false),
            emData = EmDataPools.snapshot(store, emData),
            compressed = compressed,
        )
    }

    /**
     * 全局存档（`global.sav`）。描述文本恒空串（VariableEvaluator.cs:2488 `WriteString("")`）。
     *
     * @param emData 扩展段名字表（`GLOBAL_MAPS/GLOBAL_XMLS/GLOBAL_DTS`）；空表 = 不写扩展段
     */
    fun global(
        store: VariableStore,
        compressed: Boolean = true,
        emData: EmDataNames = EmDataNames.EMPTY,
    ): SaveData {
        val gb = store.registry.gameBase
        return SaveData(
            fileType = SaveFileKind.GLOBAL,
            uniqueCode = gb.gameCode,
            scriptVersion = gb.version,
            text = "",
            variables = systemEntries(store, global = true) + userEntries(store, global = true),
            emData = EmDataPools.snapshot(store, emData),
            compressed = compressed,
        )
    }

    // ------------------------------------------------------------------
    // 系统变量
    // ------------------------------------------------------------------

    /**
     * 按 `registry.allInVarTokenDicOrder()`（= C# `varTokenDic` 的 Add 序，VariableData.cs:3014-3179）
     * 枚举并过滤：普通档 `IsSavedata && !IsCharacterData && !IsGlobal`（:3985）、
     * 全局档 `IsSavedata && !IsCharacterData && IsGlobal`（:3966）。
     *
     * 条目序必须对齐 PC 的字典枚举序（:3982 的 foreach；specs/save-format.md §5.3 第 3 条）：
     * 未改档靠 sourceFile 原字节短路不受影响，改档后整档重写时只有用本序才与 PC 字节一致。
     * 注意不是 `registry.all()` 的注册序 —— 那是按存储形态分组的构造序。
     */
    private fun systemEntries(store: VariableStore, global: Boolean): List<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        for (def in store.registry.allInVarTokenDicOrder()) {
            if (!def.isSavedata || def.isCharacterData) continue
            if (def.isGlobal != global) continue
            entryForDef(store, def)?.let { out.add(it) }
        }
        return out
    }

    /** 单个系统变量 → 条目；不可存档的存储形态返回 null（PSEUDO / LOCAL / CSV 常量）。 */
    private fun entryForDef(store: VariableStore, def: VarDef): SaveEntry? = when (def.storage) {
        Storage.G_INT1 -> SaveEntry.IntArray1(
            def.name,
            LongArray(def.dims[0]) { store.readInt(def.name, longArrayOf(it.toLong())) },
        )

        Storage.G_INT2 -> {
            val d0 = def.dims[0]
            val d1 = def.dims[1]
            SaveEntry.IntArray2(
                def.name, d0, d1,
                LongArray(d0 * d1) { k ->
                    store.readInt(def.name, longArrayOf((k / d1).toLong(), (k % d1).toLong()))
                },
            )
        }

        Storage.G_INT3 -> {
            val d0 = def.dims[0]
            val d1 = def.dims[1]
            val d2 = def.dims[2]
            SaveEntry.IntArray3(
                def.name, d0, d1, d2,
                LongArray(d0 * d1 * d2) { k ->
                    store.readInt(
                        def.name,
                        longArrayOf((k / (d1 * d2)).toLong(), ((k / d2) % d1).toLong(), (k % d2).toLong()),
                    )
                },
            )
        }

        Storage.G_STR0 -> SaveEntry.StrValue(def.name, store.readStr(def.name, longArrayOf()))

        Storage.G_STR1 -> SaveEntry.StrArray1(
            def.name,
            Array(def.dims[0]) { store.readStr(def.name, longArrayOf(it.toLong())) },
        )

        else -> null
    }

    // ------------------------------------------------------------------
    // 用户 #DIM 变量
    // ------------------------------------------------------------------

    /**
     * 用户变量分桶口径：`savedUserVarList()` 含所有 `#DIM SAVEDATA`（含同时带 GLOBAL 的），
     * 故这里再按 `def.isGlobal` 二分；CHARADATA 归角色块（[CharaFileIO]），本函数跳过。
     */
    private fun userEntries(store: VariableStore, global: Boolean): List<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        for (uv in store.savedUserVarList()) {
            val def = uv.def
            if (def.isCharacterData) continue
            if (def.isGlobal != global) continue
            entryForUser(uv)?.let { out.add(it) }
        }
        return out
    }

    private fun entryForUser(uv: UserVar): SaveEntry? {
        val def = uv.def
        val dims = def.dims
        val d0 = dims.getOrNull(0) ?: 1
        val d1 = dims.getOrNull(1) ?: 1
        val d2 = dims.getOrNull(2) ?: 1
        return if (def.type == VarType.INT) {
            when (dims.size) {
                0 -> SaveEntry.IntValue(def.name, uv.readInt(0, 0))
                1 -> SaveEntry.IntArray1(def.name, LongArray(d0) { uv.readInt(0, it) })
                2 -> SaveEntry.IntArray2(def.name, d0, d1, LongArray(d0 * d1) { uv.readInt(0, it) })
                3 -> SaveEntry.IntArray3(def.name, d0, d1, d2, LongArray(d0 * d1 * d2) { uv.readInt(0, it) })
                else -> null
            }
        } else {
            when (dims.size) {
                0 -> SaveEntry.StrValue(def.name, uv.readStr(0, 0))
                1 -> SaveEntry.StrArray1(def.name, Array(d0) { uv.readStr(0, it) })
                2 -> SaveEntry.StrArray2(def.name, d0, d1, Array(d0 * d1) { uv.readStr(0, it) })
                3 -> SaveEntry.StrArray3(def.name, d0, d1, d2, Array(d0 * d1 * d2) { uv.readStr(0, it) })
                else -> null
            }
        }
    }
}
