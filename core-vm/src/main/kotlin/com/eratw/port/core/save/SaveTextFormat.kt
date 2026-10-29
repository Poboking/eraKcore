package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableRegistry
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 文本格式存档编解码（`EraDataWriter` / `EraDataReader` 的 Kotlin 复刻）。
 *
 * 只在 `SystemSaveInBinary == false` 时使用（ConfigData.cs:107 的出厂默认；eratw 实机是
 * 二进制 + GZip，见 [SaveConfig.ERATW]）。
 *
 * ## 布局（spec/save-format.md §2.2 的文本分支，逐条对 `EraDataWriter.cs` / `EraDataReader.cs`）
 *
 * 普通档 `save{no:00}.sav`：
 * ```
 * UniqueCode
 * ScriptVersion
 * saveDataText
 * 角色数
 * 每角色：2 个 String（NAME/CALLNAME）
 *         2 个 Int（ISASSI/NO）
 *         17 个 Int1D（charaInt1D 槽位 0..16）
 *         0 个 Str1D
 * 60 个 Int1D（dataIntegerArray 槽位 0..59，含 52..59 的空占位）
 * 1 个 Str1D（SAVESTR，槽位 0）
 * __EMUERA_1808_STRAT__
 * 每角色：6 段扩展（键值对 / 键+行列表，各段以 __EMU_SEPARATOR__ 收尾）
 * 8 段系统扩展 + 6 段用户 #DIM SAVEDATA 扩展（各段均以 __EMU_SEPARATOR__ 收尾）
 * ```
 * 全局档 `global.sav`：
 * ```
 * UniqueCode
 * ScriptVersion
 * GLOBAL 数组（槽位 63）
 * GLOBALS 数组（槽位 5，VariableData.cs:3804-3806 dataStringArray[5]）
 * __EMUERA_1808_STRAT__
 * 6 段用户 #DIM GLOBAL SAVEDATA 扩展
 * ```
 *
 * 证据：VariableEvaluator.cs:2409-2426（普通写）/ :2428-2472（普通读）/
 * :2474-2511（全局写）/ :2513-2589（全局读）；VariableData.cs:3516-3534（正文 60+1）、
 * :3556-3626（8 系统段 + 6 用户段）；CharacterData.cs:351-369（正文 2+2+17）、
 * :391-423（6 段）；EraDataWriter.cs:27-219；EraDataReader.cs:39-530。
 *
 * ## 读侧的**扩展区档位**（M10-SAVE 收口）
 *
 * 标记行不止一种，原版按命中的标记给 `EraDataReader.DataVersion`
 * （`EraDataReader.cs:14-26` 五个常量、`:133-175` 的赋值），随后按档位分流：
 *
 * | 档位 | 角色扩展段 | 系统扩展段 | 用户 6 段 | 证据 |
 * |---|---|---|---|---|
 * | 无标记（正文后直接结束） | — | — | — | `VariableEvaluator.cs:2453-2456`（`SeekEmuStart` 为 false 直接 return） |
 * | 1700 | **4 段** | **4 段**（无 2D/3D） | **不读** | `VariableEvaluator.cs:2457-2462` + `CharacterData.cs:477-511`；`EraDataReader.cs:351-354` / `:415-418`；`VariableData.cs:3694-3697` |
 * | 1708 | 4 段 | **6 段**（无 3D） | **不读** | 同上 + `EraDataReader.cs:438-441` / `:516-519`（3D 两段门槛 1729） |
 * | `1729..1802` | 4 段 | 8 段 | **不读** | 同上（四个 early-return 都不命中） |
 * | `1803..1807` | 6 段 | 8 段 | **不读** | `VariableEvaluator.cs:2464-2470` + `CharacterData.cs:425-475`；`VariableData.cs:3694-3697` |
 * | `>= 1808` | 6 段 | 8 段 | 6 段 | `VariableData.cs:3698-3756` |
 *
 * 系统扩展段的**消费段数**不是固定 8，也不是靠分段数探测出来的：`LoadFromStreamExtended`
 * 虽然恒调 8 个 `ReadXxxExtended`（`VariableData.cs:3630-3637`），但 2D 的两个 reader 在
 * `emu_version < 1708` 时**直接返回空字典、一行都不读**（`EraDataReader.cs:351-354` /
 * `:415-418`），3D 的两个在 `< 1729` 时同样直接返回（`:438-441` / `:516-519`）—— 旧档里
 * 本就没有这几段。于是 1700 档盘上只有 4 段（STR0/INT0/STR1/INT1）、1708 档 6 段、
 * 1729 及以上 8 段；读侧若一律消费 8 段，就会在文件尾把下一行当段键、抛「存档在读取字符串
 * 时意外结束」。本层由单一谓词 [consumedAt] 判门槛、[systemSegsFor] 按它裁剪段表，
 * 不做「聪明」的内容探测。
 *
 * 全局档**不看档位**：只要 `SeekEmuStart` 命中任意标记就读 6 段用户全局扩展
 * （`VariableEvaluator.cs:2563-2566` → `VariableData.LoadGlobalFromStream1808`，:3849-3880），
 * 但这 6 段里的 2D / 3D 仍受上面同一组 `emu_version` 门槛约束（那几个读函数是共用的）。
 *
 * TODO(verify)：旧档 writer 的反编译码不在 reference（现存只有 1808 的
 * `VariableData.SaveToStreamExtended`，:3556-3626），无法直接确认 1700/1708 实际写了几段。
 * 本层按 reader 的消费口径落地（只消费该档存在的段，写了但不该读的尾段不消费也不报错），
 * 与「原版会怎么读」逐行一致；若旧 writer 真写满 8 段，尾部多出的分隔符只是不被读，
 * 不构成错误。卡点：语料 `sav/` 与 `.workbuddy/sav-backup/` 全是 1808 二进制档，无旧文本档实测。
 * 同一组门槛也落在**用户 6 段**上：`LoadGlobalFromStream1808`（`VariableData.cs:3850-3855`）
 * 用的就是这批共用 reader，而全局档只要 `SeekEmuStart` 命中任意标记就会调它
 * （`VariableEvaluator.cs:2563-2566`，**不看档位**）⇒ 1700 / 1708 档的全局文本档里
 * 2D / 3D 用户段同样不能消费分隔符（[readUserSegment] 的 `tier` 参数）。同样无旧档实测。
 *
 * ## 与原版的已知差异
 * 1. 行尾固定 `\n`（[SaveConfig.newline] 可改）；.NET `StreamWriter.WriteLine` 用
 *    `Environment.NewLine`。读侧两种都收，故跨平台可读。
 * 2. 文本格式**不携带 CHARADATA 用户变量**（CharacterData.cs:391-423 只有 6 个系统段，
 *    用户段只在二进制块 :546-556）。原版在非二进制存档下本来就禁止
 *    `#DIM CHARADATA SAVEDATA`（UserDefinedVariableData.cs:465-475），故不可达；
 *    本实现同口径丢弃。
 * 3. 字符串 2D / 3D 扩展段原版直接 `throw NotImplementedException`（EraDataWriter.cs:272-275、
 *    :344-347），只在段内没有任何变量时才不会被调用；本实现写成「空段 + 分隔符」，
 *    因为非二进制存档下多维字符串 SAVEDATA 已被 DimParser 禁止（同一 C# 依据）。
 * 4. 未知变量名（别的游戏版本写下的扩展项）读入时丢弃 —— 原版按 `GetExtSaveList` 查表，
 *    同样丢弃（VariableData.cs:3638+）。
 */
object SaveTextFormat {

    /** `EraDataWriter.FINISHER`（EraDataWriter.cs:11）。 */
    const val FINISHED: String = "__FINISHED"

    /** `EraDataWriter.EMU_START`（EraDataWriter.cs:13，源码拼写照抄 STRAT）。 */
    const val EMU_START: String = "__EMUERA_1808_STRAT__"

    /** `EraDataWriter.EMU_SEPARATOR`（EraDataWriter.cs:15）。 */
    const val SEPARATOR: String = "__EMU_SEPARATOR__"

    /** `EraDataReader.SeekEmuStart` 的五个档位（EraDataReader.cs:14-26 的常量 + :133-175 的赋值）。 */
    private const val TIER_1700 = 1700
    private const val TIER_1708 = 1708
    private const val TIER_1729 = 1729

    /** `VariableEvaluator.cs:2457` 的分流点：`DataVersion < 1803` ⇒ 角色扩展走 Old1802。 */
    private const val TIER_1803 = 1803

    /** `VariableData.cs:3694` 的分流点：`version < 1808` ⇒ 读完 8 段系统扩展即返回。 */
    private const val TIER_1808 = 1808

    /**
     * 旧标记 → 档位（`EraDataReader.cs:14-26` 的五个常量 + `:133-175` 的赋值）：
     * `__EMUERA_STRAT__`=1700、`1708`、`1729`、`1803`、`1808`。
     */
    private val EMU_START_TIERS: Map<String, Int> = linkedMapOf(
        "__EMUERA_STRAT__" to TIER_1700,
        "__EMUERA_1708_STRAT__" to TIER_1708,
        "__EMUERA_1729_STRAT__" to TIER_1729,
        "__EMUERA_1803_STRAT__" to TIER_1803,
        EMU_START to TIER_1808,
    )

    /** `VariableIdentifier` 的分段掩码 `0x281E0000`（GetExtSaveList 的 key 掩码）。 */
    private const val SEG_MASK = 673054720

    // 系统段 key（VariableData / CharacterData 的 GetExtSaveList 实参，见类 KDoc）
    private const val KEY_STR0 = 262144          // 0x040000 __STRING__
    private const val KEY_INT0 = 131072          // 0x020000 __INTEGER__
    private const val KEY_STR1 = 786432          // 0x0C0000 全局 1D 字符串
    private const val KEY_INT1 = 655360          // 0x0A0000 全局 1D 整数
    private const val KEY_STR2 = 134479872       // 0x08040000
    private const val KEY_INT2 = 134348800       // 0x08020000
    private const val KEY_STR3 = 537133056       // 0x20040000
    private const val KEY_INT3 = 537001984       // 0x20020000
    private const val KEY_C_STR0 = 1310720       // 0x140000 角色 0D 字符串
    private const val KEY_C_INT0 = 1179648       // 0x120000 角色 0D 整数
    private const val KEY_C_STR1 = 1835008       // 0x1C0000 角色 1D 字符串
    private const val KEY_C_INT1 = 1703936       // 0x1A0000 角色 1D 整数
    private const val KEY_C_STR2 = 135528448     // 0x8140000
    private const val KEY_C_INT2 = 135397376     // 0x8120000

    /** 扩展段的形状（写侧按此顺序分段；读侧按同一顺序消费）。 */
    private enum class Kind { STR0, INT0, STR1, INT1, STR2, INT2, STR3, INT3 }

    private data class Seg(val key: Int, val kind: Kind)

    /** `VariableData.SaveToStreamExtended` 的 8 段顺序（VariableData.cs:3558-3597）。 */
    private val VAR_SEGS = listOf(
        Seg(KEY_STR0, Kind.STR0), Seg(KEY_INT0, Kind.INT0),
        Seg(KEY_STR1, Kind.STR1), Seg(KEY_INT1, Kind.INT1),
        Seg(KEY_STR2, Kind.STR2), Seg(KEY_INT2, Kind.INT2),
        Seg(KEY_STR3, Kind.STR3), Seg(KEY_INT3, Kind.INT3),
    )

    /** `CharacterData.SaveToStreamExtended` 的 6 段顺序（CharacterData.cs:393-422）。 */
    private val CHARA_SEGS = listOf(
        Seg(KEY_C_STR0, Kind.STR0), Seg(KEY_C_INT0, Kind.INT0),
        Seg(KEY_C_STR1, Kind.STR1), Seg(KEY_C_INT1, Kind.INT1),
        Seg(KEY_C_STR2, Kind.STR2), Seg(KEY_C_INT2, Kind.INT2),
    )

    /**
     * Old1802（`DataVersion < 1803`）的角色扩展段 = 前 4 段
     * （`CharacterData.LoadFromStreamExtended_Old1802`，CharacterData.cs:477-511 只读
     * String-Extended / Int64-Extended / StringArray-Extended / Int64Array-Extended 四段，
     * 2D 两段的读取在 1803 分支 CharacterData.cs:431-432 才出现）。
     */
    private val CHARA_OLD1802_SEGS: List<Seg> = CHARA_SEGS.take(4)

    /**
     * 旧档系统扩展段实际被消费的前缀：`LoadFromStreamExtended` 恒按 8 段顺序调用 reader
     * （VariableData.cs:3630-3637），但 2D 的两个 reader 在 `emu_version < 1708` 时直接返回、
     * 不消费行（EraDataReader.cs:351-354 / :415-418），两个 3D reader 在 `< 1729` 时同样
     * （:438-441 / :516-519）⇒ 1700 只读前 4 段、1708 前 6 段、1729 及以上 8 段。
     * 门槛本身只写在 [consumedAt] 一处（用户 6 段用的是同一批 reader，同样按它裁剪）。
     */
    private fun systemSegsFor(tier: Int): List<Seg> = VAR_SEGS.filter { consumedAt(it.kind, tier) }

    /** 用户变量 6 段的形状顺序（VariableData.cs:3598-3625 / :3850-3855）。 */
    private val USER_KINDS = listOf(Kind.STR1, Kind.INT1, Kind.STR2, Kind.INT2, Kind.STR3, Kind.INT3)

    private const val GLOBAL_INT_SLOT = 63
    private const val GLOBALS_STR_SLOT = 5
    private const val SAVESTR_SLOT = 0

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 把 [data] 编码成文本存档字节。
     *
     * @throws SaveFormatException 只支持 [SaveFileKind.NORMAL] / [SaveFileKind.GLOBAL]
     */
    fun write(
        data: SaveData,
        registry: VariableRegistry,
        newline: String = "\n",
        charset: Charset = Charsets.UTF_8,
        writeBom: Boolean = true,
    ): ByteArray {
        if (data.fileType != SaveFileKind.NORMAL && data.fileType != SaveFileKind.GLOBAL) {
            throw SaveFormatException("文本格式只支持 Normal / Global（实际 ${data.fileType}）")
        }
        val t = Tables(registry)
        val vars = LinkedHashMap<String, SaveEntry>()
        for (e in data.variables) if (e !is SaveEntry.Separator) vars[e.key.uppercase()] = e
        val userVars = data.variables.filter { registry.def(it.key) == null }
        val w = Writer(newline)

        w.int(data.uniqueCode)
        w.int(data.scriptVersion)
        when (data.fileType) {
            SaveFileKind.NORMAL -> {
                w.text(data.text)
                w.int(data.characters.size.toLong())
                for (block in data.characters) writeCharacterBody(w, t, block)
                for (slot in 0 until 60) w.intArray1(t.int1BySlot[slot]?.let { vars[it.name.uppercase()] })
                w.strArray1(t.str1BySlot[SAVESTR_SLOT]?.let { vars[it.name.uppercase()] })
                w.text(EMU_START)
                for (block in data.characters) writeCharacterExtended(w, registry, block)
                for (seg in VAR_SEGS) {
                    writeSystemSegment(w, registry, seg, vars)
                    w.text(SEPARATOR)
                }
                for (kind in USER_KINDS) {
                    writeUserSegment(w, kind, userVars.filter { kindOf(it) == kind })
                    w.text(SEPARATOR)
                }
            }

            else -> {
                w.intArray1(t.int1BySlot[GLOBAL_INT_SLOT]?.let { vars[it.name.uppercase()] })
                w.strArray1(t.str1BySlot[GLOBALS_STR_SLOT]?.let { vars[it.name.uppercase()] })
                w.text(EMU_START)
                for (kind in USER_KINDS) {
                    writeUserSegment(w, kind, userVars.filter { kindOf(it) == kind })
                    w.text(SEPARATOR)
                }
            }
        }

        val body = w.text.toString().toByteArray(charset)
        if (!writeBom || charset != Charsets.UTF_8) return body
        val out = ByteArrayOutputStream(body.size + 3)
        out.write(0xEF); out.write(0xBB); out.write(0xBF)
        out.write(body)
        return out.toByteArray()
    }

    /** 角色正文：2 String + 2 Int + 17 Int1D + 0 Str1D（CharacterData.cs:353-368）。 */
    private fun writeCharacterBody(w: Writer, t: Tables, block: List<SaveEntry>) {
        val m = byKey(block)
        for (slot in 0 until 2) w.text(valueOf(m[t.charaStr0[slot]?.name], ""))
        for (slot in 0 until 2) w.int(intOf(m[t.charaInt0[slot]?.name]))
        for (slot in 0 until 17) w.intArray1(t.charaInt1[slot]?.let { m[it.name.uppercase()] })
    }

    /**
     * 角色扩展：6 段系统变量（CharacterData.cs:391-423）。用户 CHARADATA 变量在文本格式里
     * 不存在（见类 KDoc 差异 2），这里显式跳过。
     */
    private fun writeCharacterExtended(w: Writer, registry: VariableRegistry, block: List<SaveEntry>) {
        val m = byKey(block)
        for (seg in CHARA_SEGS) {
            for (def in matching(registry, seg, chara = true)) {
                writeExtended(w, m[def.name.uppercase()], seg.kind)
            }
            w.text(SEPARATOR)
        }
    }

    /** 8 段系统扩展：只写 code 上带 `__SAVE_EXTENDED__` 的项（VariableData.cs:3558-3597）。 */
    private fun writeSystemSegment(
        w: Writer,
        registry: VariableRegistry,
        seg: Seg,
        vars: Map<String, SaveEntry>,
    ) {
        for (def in matching(registry, seg, chara = false)) {
            writeExtended(w, vars[def.name.uppercase()], seg.kind)
        }
    }

    /** 用户变量段：键 = 变量名，值形见 [Kind]（VariableData.cs:3598-3625）。 */
    private fun writeUserSegment(w: Writer, kind: Kind, entries: List<SaveEntry>) {
        for (e in entries) writeExtended(w, e, kind)
    }

    private fun kindOf(e: SaveEntry): Kind? = when (e) {
        is SaveEntry.IntValue, is SaveEntry.IntArray1 -> Kind.INT1
        is SaveEntry.IntArray2 -> Kind.INT2
        is SaveEntry.IntArray3 -> Kind.INT3
        is SaveEntry.StrValue, is SaveEntry.StrArray1 -> Kind.STR1
        is SaveEntry.StrArray2 -> Kind.STR2
        is SaveEntry.StrArray3 -> Kind.STR3
        else -> null
    }

    /**
     * 单条扩展项（`EraDataWriter.WriteExtended` 的四个重载，EraDataWriter.cs:129-342）。
     * 0 / 空串 / 全零数组一律不写（省行，也让读侧「缺项 = 保持原值」成立）。
     */
    private fun writeExtended(w: Writer, e: SaveEntry?, kind: Kind) {
        when (kind) {
            Kind.STR0 -> {
                val s = (e as? SaveEntry.StrValue)?.value ?: return
                if (s.isNotEmpty()) w.text("${e.key}:$s")
            }

            Kind.INT0 -> {
                val v = (e as? SaveEntry.IntValue)?.value ?: return
                if (v != 0L) w.text("${e.key}:$v")
            }

            Kind.STR1 -> {
                val a = when (e) {
                    is SaveEntry.StrArray1 -> e.values
                    is SaveEntry.StrValue -> arrayOf<String?>(e.value)
                    else -> return
                }
                w.extStrArray1(e.key, a)
            }

            Kind.INT1 -> {
                val a = when (e) {
                    is SaveEntry.IntArray1 -> e.values
                    is SaveEntry.IntValue -> longArrayOf(e.value)
                    else -> return
                }
                w.extIntArray1(e.key, a)
            }

            Kind.STR2 -> Unit // 原版 WriteExtended(string[,]) 未实现（EraDataWriter.cs:272-275）
            Kind.INT2 -> {
                val a = e as? SaveEntry.IntArray2 ?: return
                w.extIntArray2(e.key, a)
            }

            Kind.STR3 -> Unit // 原版 WriteExtended(string[,,]) 未实现（EraDataWriter.cs:344-347）
            Kind.INT3 -> {
                val a = e as? SaveEntry.IntArray3 ?: return
                w.extIntArray3(e.key, a)
            }
        }
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 解析文本存档。
     *
     * @param kind 文件类别。文本容器**没有类别字节**（类别只存在于二进制头，EraSaveFileType.cs:3-9），
     *   原版由调用方拆成 `LoadFromStream` / `LoadGlobalFromStream` 两条路径
     *   （VariableEvaluator.cs:2428 / :2513），故本层必须显式传入。
     * @throws SaveFormatException 截断 / 数字不合法 / 扩展段格式不符
     */
    fun read(bytes: ByteArray, registry: VariableRegistry, kind: SaveFileKind): SaveData {
        val t = Tables(registry)
        val c = Cursor(decode(bytes))
        val uniqueCode = c.int64()
        val scriptVersion = c.int64()
        return when (kind) {
            SaveFileKind.NORMAL -> {
                val text = c.text()
                val n = c.int64()
                if (n < 0 || n > MAX_CHARA) throw SaveFormatException("角色数异常：$n")
                val blocks = List(n.toInt()) { readCharacterBody(c, t) }
                val variables = ArrayList<SaveEntry>()
                for (slot in 0 until 60) {
                    val def = t.int1BySlot[slot]
                    val values = c.intArray1(def?.dims?.getOrNull(0) ?: Int.MAX_VALUE)
                    if (def != null) variables.add(SaveEntry.IntArray1(def.name, padInts(values, def.dims[0])))
                }
                val savestr = c.strArray1()
                t.str1BySlot[SAVESTR_SLOT]?.let { def ->
                    variables.add(SaveEntry.StrArray1(def.name, padStrs(savestr, def.dims[0])))
                }
                // 扩展区档位由标记行决定（EraDataReader.cs:133-175 的 emu_version）；
                // 没有标记（文件在正文后就结束）⇒ 完全不读扩展区（VariableEvaluator.cs:2453-2456）。
                val tier = if (c.hasMore()) c.seekEmuStart() else null
                if (tier != null) {
                    // 档位分流（VariableEvaluator.cs:2457-2471 + VariableData.cs:3694-3697）：
                    // < 1803 的角色扩展段只有 4 段（CharacterData.cs:477-511），
                    // < 1808 不读 6 段用户扩展（读完系统扩展即返回）。
                    for (block in blocks) {
                        if (tier < TIER_1803) readCharacterExtended(c, registry, block, CHARA_OLD1802_SEGS)
                        else readCharacterExtended(c, registry, block)
                    }
                    for (seg in systemSegsFor(tier)) readSystemSegment(c, registry, seg, variables)
                    if (tier >= TIER_1808) {
                        for (k in USER_KINDS) variables.addAll(readUserSegment(c, k, tier))
                    }
                }
                SaveData(
                    fileType = SaveFileKind.NORMAL,
                    uniqueCode = uniqueCode,
                    scriptVersion = scriptVersion,
                    text = text,
                    characters = blocks,
                    variables = variables,
                    compressed = false,
                )
            }

            SaveFileKind.GLOBAL -> {
                val variables = ArrayList<SaveEntry>()
                val ints = c.intArray1(t.int1BySlot[GLOBAL_INT_SLOT]?.dims?.getOrNull(0) ?: Int.MAX_VALUE)
                t.int1BySlot[GLOBAL_INT_SLOT]?.let { def ->
                    variables.add(SaveEntry.IntArray1(def.name, padInts(ints, def.dims[0])))
                }
                val strs = c.strArray1()
                t.str1BySlot[GLOBALS_STR_SLOT]?.let { def ->
                    variables.add(SaveEntry.StrArray1(def.name, padStrs(strs, def.dims[0])))
                }
                // 全局档不分管位：只要 SeekEmuStart 命中任意标记就**调用** 6 个用户全局扩展
                // reader（VariableEvaluator.cs:2563-2566 → VariableData.LoadGlobalFromStream1808，
                // :3850-3855），与普通档「< 1808 读完系统扩展就 return」的口径**不同**，照抄。
                // 但这几个 reader 与普通档用的是同一批，2D / 3D 段仍按 [consumedAt] 的
                // emu_version 门槛决定是否消费行（低档盘上没有这几段）。
                val tier = if (c.hasMore()) c.seekEmuStart() else null
                if (tier != null) {
                    for (k in USER_KINDS) variables.addAll(readUserSegment(c, k, tier))
                }
                SaveData(
                    fileType = SaveFileKind.GLOBAL,
                    uniqueCode = uniqueCode,
                    scriptVersion = scriptVersion,
                    text = "",
                    variables = variables,
                    compressed = false,
                )
            }

            else -> throw SaveFormatException("文本格式只支持 Normal / Global（实际 $kind）")
        }
    }

    /** 角色正文（CharacterData.cs:371-389）：2 String + 2 Int + 17 Int1D + 0 Str1D。 */
    private fun readCharacterBody(c: Cursor, t: Tables): MutableList<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        for (slot in 0 until 2) {
            val s = c.text()
            t.charaStr0[slot]?.let { out.add(SaveEntry.StrValue(it.name, s)) }
        }
        for (slot in 0 until 2) {
            val v = c.int64()
            t.charaInt0[slot]?.let { out.add(SaveEntry.IntValue(it.name, v)) }
        }
        for (slot in 0 until 17) {
            val def = t.charaInt1[slot]
            val values = c.intArray1(def?.dims?.getOrNull(0) ?: Int.MAX_VALUE)
            if (def != null) out.add(SaveEntry.IntArray1(def.name, padInts(values, def.dims[0])))
        }
        return out
    }

    /**
     * 角色扩展段。1803 及以上走 6 段（`CharacterData.cs:425-475`：STR0 / INT0 / STR1 / INT1 /
     * STR2 / INT2）；1803 以下走 **Old1802 的 4 段**（`CharacterData.cs:477-511` 只调
     * `ReadStringExtended` / `ReadInt64Extended` / `ReadStringArrayExtended` /
     * `ReadInt64ArrayExtended`，即前四段），分流点 `VariableEvaluator.cs:2457-2470`。
     * 用同一例程、把段表传进去 ⇒ 两条档位不可能读歪。
     */
    private fun readCharacterExtended(
        c: Cursor,
        registry: VariableRegistry,
        block: MutableList<SaveEntry>,
        segs: List<Seg> = CHARA_SEGS,
    ) {
        for (seg in segs) {
            val defs = matching(registry, seg, chara = true)
            when (seg.kind) {
                Kind.STR0 -> {
                    val m = c.stringExtended()
                    for (def in defs) m[def.name]?.let { block.add(SaveEntry.StrValue(def.name, it)) }
                }

                Kind.INT0 -> {
                    val m = c.intExtended()
                    for (def in defs) m[def.name]?.let { block.add(SaveEntry.IntValue(def.name, it)) }
                }

                Kind.STR1 -> {
                    val m = c.strArrayExtended()
                    for (def in defs) m[def.name]?.let { block.add(SaveEntry.StrArray1(def.name, toNullableArray(it))) }
                }

                Kind.INT1 -> {
                    val m = c.intArrayExtended()
                    for (def in defs) m[def.name]?.let { block.add(SaveEntry.IntArray1(def.name, it.toLongArray())) }
                }

                Kind.STR2, Kind.STR3 -> c.expectSeparatorOnly()

                Kind.INT2 -> {
                    val m = c.intArray2DExtended()
                    for (def in defs) m[def.name]?.let { block.add(array2Of(def.name, it)) }
                }

                Kind.INT3 -> {
                    val m = c.intArray3DExtended()
                    for (def in defs) m[def.name]?.let { block.add(array3Of(def.name, it)) }
                }
            }
        }
    }

    /**
     * 系统扩展段：只认 code 上带 `__SAVE_EXTENDED__` 且分段 key 匹配的变量。
     *
     * 2D / 3D 段在低档位**不存在**、不能消费它们的分隔符（原版那几个 reader 在
     * `emu_version` 低于门槛时直接返回空字典、一行都不读，EraDataReader.cs:351-354 / :415-418 /
     * :438-441 / :516-519）—— 该裁剪由调用点的 [systemSegsFor]（→ [consumedAt]）负责，
     * 本函数按段原样消费。
     */
    private fun readSystemSegment(
        c: Cursor,
        registry: VariableRegistry,
        seg: Seg,
        out: MutableList<SaveEntry>,
    ) {
        val defs = matching(registry, seg, chara = false)
        when (seg.kind) {
            Kind.STR0 -> {
                val m = c.stringExtended()
                for (def in defs) m[def.name]?.let { out.add(SaveEntry.StrValue(def.name, it)) }
            }

            Kind.INT0 -> {
                val m = c.intExtended()
                for (def in defs) m[def.name]?.let { out.add(SaveEntry.IntValue(def.name, it)) }
            }

            Kind.STR1 -> {
                val m = c.strArrayExtended()
                for (def in defs) m[def.name]?.let { out.add(SaveEntry.StrArray1(def.name, toNullableArray(it))) }
            }

            Kind.INT1 -> {
                val m = c.intArrayExtended()
                for (def in defs) m[def.name]?.let { out.add(SaveEntry.IntArray1(def.name, it.toLongArray())) }
            }

            Kind.STR2, Kind.STR3 -> c.expectSeparatorOnly()

            Kind.INT2 -> {
                val m = c.intArray2DExtended()
                for (def in defs) m[def.name]?.let { out.add(array2Of(def.name, it)) }
            }

            Kind.INT3 -> {
                val m = c.intArray3DExtended()
                for (def in defs) m[def.name]?.let { out.add(array3Of(def.name, it)) }
            }
        }
    }

    /** 用户变量段：名字未知时也保留条目（[SaveData.applyTo] 按「不认识就跳过」处理）。 */
    private fun readUserSegment(c: Cursor, kind: Kind, tier: Int): List<SaveEntry> {
        if (!consumedAt(kind, tier)) return emptyList()
        return when (kind) {
            Kind.STR0, Kind.INT0 -> emptyList() // 用户变量没有 0 维形态
            Kind.STR1 -> c.strArrayExtended().map { (k, v) -> SaveEntry.StrArray1(k, toNullableArray(v)) }
            Kind.INT1 -> c.intArrayExtended().map { (k, v) -> SaveEntry.IntArray1(k, v.toLongArray()) }
            Kind.STR2, Kind.STR3 -> {
                c.expectSeparatorOnly()
                emptyList()
            }

            Kind.INT2 -> c.intArray2DExtended().map { (k, v) -> array2Of(k, v) }
            Kind.INT3 -> c.intArray3DExtended().map { (k, v) -> array3Of(k, v) }
        }
    }

    /**
     * 该段在 [tier] 档里是否存在（存在才消费分隔符）。
     *
     * 门槛逐条取 `EraDataReader` 的早退分支：
     * - 2D（STR2 / INT2）：`emu_version < 1708` 直接返回，不读（`ReadInt64Array2DExtended`
     *   EraDataReader.cs:351、`ReadStringArray2DExtended` :415）；
     * - 3D（STR3 / INT3）：`emu_version < 1729` 直接返回，不读（`ReadInt64Array3DExtended`
     *   :438、`ReadStringArray3DExtended` :516）。
     *
     * 角色扩展段用不到本方法：1803 档才带 2D 段（`CharacterData.cs:431-432`），
     * 而 1803 ≥ 1708；1803 以下走 4 段（`CharacterData.cs:477-511`），没有 2D / 3D。
     */
    private fun consumedAt(kind: Kind, tier: Int): Boolean = when (kind) {
        Kind.STR2, Kind.INT2 -> tier >= TIER_1708
        Kind.STR3, Kind.INT3 -> tier >= TIER_1729
        else -> true
    }

    private fun matching(registry: VariableRegistry, seg: Seg, chara: Boolean): List<VarDef> =
        registry.all().filter {
            it.isCharacterData == chara &&
                (it.code and VariableCode.SAVE_EXTENDED) == VariableCode.SAVE_EXTENDED &&
                (it.code and SEG_MASK) == seg.key
        }

    // ------------------------------------------------------------------
    // 2D/3D 值 → 条目（行主序压平，缺列补 0；与 copyListToArray2D/3D 的 min 口径一致）
    // ------------------------------------------------------------------

    private fun array2Of(key: String, rows: List<LongArray>): SaveEntry.IntArray2 {
        val d0 = rows.size
        val d1 = rows.maxOfOrNull { it.size } ?: 0
        val values = LongArray(dimProduct(key, d0, d1))
        for (i in 0 until d0) rows[i].copyInto(values, i * d1, 0, rows[i].size)
        return SaveEntry.IntArray2(key, d0, d1, values)
    }

    private fun array3Of(key: String, planes: List<List<LongArray>>): SaveEntry.IntArray3 {
        val d0 = planes.size
        val d1 = planes.maxOfOrNull { it.size } ?: 0
        val d2 = planes.maxOfOrNull { p -> p.maxOfOrNull { it.size } ?: 0 } ?: 0
        val values = LongArray(dimProduct(key, d0, d1, d2))
        for (i in 0 until d0) {
            for (j in 0 until planes[i].size) {
                val row = planes[i][j]
                row.copyInto(values, (i * d1 + j) * d2, 0, row.size)
            }
        }
        return SaveEntry.IntArray3(key, d0, d1, d2, values)
    }

    /**
     * 2D/3D 扩展项的元素总数：逐维、Long 域判定后再乘，超过 [MAX_ARRAY_ELEMENTS] 即抛
     * [SaveFormatException]。
     *
     * 读侧 `ReadInt64Array2DExtended` / `ReadInt64Array3DExtended` 把值读成
     * `List<long[]>` / `List<List<long[]>>`（`EraDataReader.cs:344-406` / `:431-507`），
     * 维度由行数/最长行隐式给出；移植侧再把它们压平成 `LongArray(d0 * d1)` /
     * `LongArray(d0 * d1 * d2)` 交给 [SaveData] —— 两个 Int 乘法在这里可溢出成负数
     * 或偏小的正数，前者抛 `NegativeArraySizeException`、后者让 `copyInto` 越界。
     * 上限值与 `SaveFormat` 同一理由：`OutOfMemoryError` 是 `Error`，读档链接不住。
     */
    private fun dimProduct(key: String, vararg dims: Int): Int {
        var total = 1L
        for (d in dims) {
            if (d < 0) throw SaveFormatException("$key 的数组维度为负：$d")
            if (d > 0 && total > MAX_ARRAY_ELEMENTS.toLong() / d) {
                throw SaveFormatException(
                    "$key 的数组元素数超过上限 $MAX_ARRAY_ELEMENTS（维度 ${dims.joinToString("x")}）",
                )
            }
            total *= d
        }
        return total.toInt()
    }

    // ==================================================================
    // 编解码基础设施
    // ==================================================================

    private const val MAX_CHARA = 1_000_000

    /** 2D/3D 扩展项压平后的元素上限（取值理由见 [dimProduct]）。 */
    private const val MAX_ARRAY_ELEMENTS = 16_000_000

    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

        else -> {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.indexOf('\uFFFD') >= 0) {
                // 原版 SaveEncode 恒为 UTF-8；旧档可能是别的编码（Config.Encode 路径）。
                runCatching { String(bytes, Charset.forName("Shift_JIS")) }.getOrDefault(utf8)
            } else {
                utf8
            }
        }
    }

    private class Writer(private val newline: String) {
        val text = StringBuilder(1 shl 16)

        fun text(s: String) {
            text.append(s).append(newline)
        }

        fun int(v: Long) = text(v.toString())

        /** `EraDataWriter.Write(long[])`：截到最后一个非零项，再写 `__FINISHED`（:52-76）。 */
        fun intArray1(e: SaveEntry?) {
            val a = when (e) {
                is SaveEntry.IntArray1 -> e.values
                is SaveEntry.IntValue -> longArrayOf(e.value)
                else -> LongArray(0)
            }
            var last = -1
            for (i in a.indices) if (a[i] != 0L) last = i
            for (i in 0..last) int(a[i])
            text(FINISHED)
        }

        /** `EraDataWriter.Write(string[])`：截到最后一个非空项（:78-109）。 */
        fun strArray1(e: SaveEntry?) {
            val a = when (e) {
                is SaveEntry.StrArray1 -> e.values
                is SaveEntry.StrValue -> arrayOf<String?>(e.value)
                else -> emptyArray()
            }
            var last = -1
            for (i in a.indices) if (!a[i].isNullOrEmpty()) last = i
            for (i in 0..last) text(a[i] ?: "")
            text(FINISHED)
        }

        /** `WriteExtended(key, long[])`（:153-181）。 */
        fun extIntArray1(key: String, a: LongArray) {
            var last = -1
            for (i in a.indices) if (a[i] != 0L) last = i
            if (last < 0) return
            text(key)
            for (i in 0..last) int(a[i])
            text(FINISHED)
        }

        /** `WriteExtended(key, string[])`（:183-219）。 */
        fun extStrArray1(key: String, a: Array<String?>) {
            var last = -1
            for (i in a.indices) if (!a[i].isNullOrEmpty()) last = i
            if (last < 0) return
            text(key)
            for (i in 0..last) text(a[i] ?: "")
            text(FINISHED)
        }

        /** `WriteExtended(key, long[,])`（:221-270）：行末空行表示整行零。 */
        fun extIntArray2(key: String, e: SaveEntry.IntArray2) {
            var lastRow = -1
            val lastCol = IntArray(e.dim0)
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    if (e.values[i * e.dim1 + j] != 0L) {
                        lastRow = i
                        lastCol[i] = j + 1
                    }
                }
            }
            if (lastRow < 0) return
            text(key)
            for (i in 0..lastRow) {
                if (lastCol[i] == 0) {
                    text("")
                    continue
                }
                val sb = StringBuilder()
                for (j in 0 until lastCol[i]) {
                    if (j > 0) sb.append(',')
                    sb.append(e.values[i * e.dim1 + j])
                }
                text(sb.toString())
            }
            text(FINISHED)
        }

        /** `WriteExtended(key, long[,,])`（:277-342）：每个平面以「i{」开头、「}」收尾。 */
        fun extIntArray3(key: String, e: SaveEntry.IntArray3) {
            var lastPlane = -1
            val planeRows = IntArray(e.dim0)
            val lastCol = Array(e.dim0) { IntArray(e.dim1) }
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    for (k in 0 until e.dim2) {
                        if (e.values[(i * e.dim1 + j) * e.dim2 + k] != 0L) {
                            lastPlane = i
                            planeRows[i] = j + 1
                            lastCol[i][j] = k + 1
                        }
                    }
                }
            }
            if (lastPlane < 0) return
            text(key)
            for (i in 0..lastPlane) {
                text("$i{")
                if (planeRows[i] == 0) {
                    text("}")
                    continue
                }
                for (j in 0 until planeRows[i]) {
                    if (lastCol[i][j] == 0) {
                        text("")
                        continue
                    }
                    val sb = StringBuilder()
                    for (k in 0 until lastCol[i][j]) {
                        if (k > 0) sb.append(',')
                        sb.append(e.values[(i * e.dim1 + j) * e.dim2 + k])
                    }
                    text(sb.toString())
                }
                text("}")
            }
            text(FINISHED)
        }
    }

    private class Cursor(text: String) {
        private val lines: List<String> = text.split('\n').map { it.removeSuffix("\r") }
        private var pos = 0

        fun hasMore(): Boolean = pos < lines.size

        fun text(): String {
            if (pos >= lines.size) throw SaveFormatException("存档在读取字符串时意外结束（行 ${pos + 1}）")
            return lines[pos++]
        }

        fun int64(): Long {
            val s = text()
            return s.trim().toLongOrNull()
                ?: throw SaveFormatException("存档第 ${pos} 行的数字不合法：\"$s\"")
        }

        /**
         * `ReadInt64Array`（:61-97）：读到 `__FINISHED`；只解析前 [limit] 项。
         *
         * 原版按 `i < array.Length` 才 `long.TryParse`（:84-91），**多余行只消费不解析**
         * （数组长度由调用点的变量声明给出）；行数不足时由调用方补 0。
         */
        fun intArray1(limit: Int = Int.MAX_VALUE): LongArray {
            val out = ArrayList<Long>(if (limit < 64) limit.coerceAtLeast(0) else 64)
            var index = 0
            while (true) {
                val s = text()
                if (s == FINISHED) return out.toLongArray()
                if (index++ < limit) {
                    out.add(s.trim().toLongOrNull() ?: throw SaveFormatException("存档数组项不是数字：\"$s\""))
                }
            }
        }

        /** `ReadStringArray`（:99-131）。 */
        fun strArray1(): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val s = text()
                if (s == FINISHED) return out
                out.add(s)
            }
        }

        /**
         * `SeekEmuStart`（`EraDataReader.cs:133-175`）：逐行往后读，命中五个标记之一就返回**档位**
         * （1700 / 1708 / 1729 / 1803 / 1808，`:151-174` 的 `emu_version` 赋值），
         * 读到文件尾仍没命中返回 null（`:147-150` 的 `return false` ⇒ 调用方
         * `VariableEvaluator.cs:2453-2456` 直接结束，不碰扩展区）。
         */
        fun seekEmuStart(): Int? {
            while (pos < lines.size) {
                val s = text()
                EMU_START_TIERS[s]?.let { return it }
            }
            return null
        }

        /** `ReadStringExtended`（:177-209）。 */
        fun stringExtended(): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            while (true) {
                val s = text()
                if (s == FINISHED) throw SaveFormatException("扩展段里出现了 $FINISHED（存档形式不正确）")
                if (s == SEPARATOR) return m
                val idx = s.indexOf(':')
                if (idx < 0) throw SaveFormatException("扩展行的键值格式不正确：\"$s\"")
                m.putIfAbsent(s.substring(0, idx), s.substring(idx + 1))
            }
        }

        /** `ReadInt64Extended`（:211-246）。 */
        fun intExtended(): Map<String, Long> {
            val m = LinkedHashMap<String, Long>()
            while (true) {
                val s = text()
                if (s == FINISHED) throw SaveFormatException("扩展段里出现了 $FINISHED（存档形式不正确）")
                if (s == SEPARATOR) return m
                val idx = s.indexOf(':')
                if (idx < 0) throw SaveFormatException("扩展行的键值格式不正确：\"$s\"")
                val v = s.substring(idx + 1).trim().toLongOrNull()
                    ?: throw SaveFormatException("扩展项 ${s.substring(0, idx)} 的值不是数字")
                m.putIfAbsent(s.substring(0, idx), v)
            }
        }

        /** `ReadInt64ArrayExtended`（:248-296）。 */
        fun intArrayExtended(): Map<String, List<Long>> {
            val m = LinkedHashMap<String, List<Long>>()
            while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val list = ArrayList<Long>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) break
                    list.add(s.trim().toLongOrNull() ?: throw SaveFormatException("扩展数组项不是数字：\"$s\""))
                }
                m.putIfAbsent(key, list)
            }
        }

        /** `ReadStringArrayExtended`（:298-342）。 */
        fun strArrayExtended(): Map<String, List<String>> {
            val m = LinkedHashMap<String, List<String>>()
            while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val list = ArrayList<String>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) break
                    list.add(s)
                }
                m.putIfAbsent(key, list)
            }
        }

        /** `ReadInt64Array2DExtended`（:344-406）。 */
        fun intArray2DExtended(): Map<String, List<LongArray>> {
            val m = LinkedHashMap<String, List<LongArray>>()
            while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val rows = ArrayList<LongArray>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) break
                    rows.add(if (s.isEmpty()) LongArray(0) else parseCsvLongs(s))
                }
                m.putIfAbsent(key, rows)
            }
        }

        /** `ReadInt64Array3DExtended`（:431-507）。 */
        fun intArray3DExtended(): Map<String, List<List<LongArray>>> {
            val m = LinkedHashMap<String, List<List<LongArray>>>()
            outer@ while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val planes = ArrayList<List<LongArray>>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) {
                        m.putIfAbsent(key, planes)
                        continue@outer
                    }
                    if (!s.contains('{')) continue
                    val rows = ArrayList<LongArray>()
                    while (true) {
                        val row = text()
                        if (row == "}") break
                        rows.add(if (row.isEmpty()) LongArray(0) else parseCsvLongs(row))
                    }
                    planes.add(rows)
                }
            }
        }

        /** 字符串 2D/3D 扩展段在原版只有「直接分隔符」一种合法形态（:408-429 / :509-530）。 */
        fun expectSeparatorOnly() {
            val s = text()
            if (s == SEPARATOR) return
            if (s == FINISHED) throw SaveFormatException("扩展段提前结束（存档形式不正确）")
            throw SaveFormatException("文本格式不支持字符串多维变量")
        }

        private fun parseCsvLongs(s: String): LongArray {
            val parts = s.split(',')
            return LongArray(parts.size) { i ->
                parts[i].trim().toLongOrNull() ?: throw SaveFormatException("扩展数组项不是数字：\"${parts[i]}\"")
            }
        }
    }

    // ------------------------------------------------------------------
    // 查表
    // ------------------------------------------------------------------

    private class Tables(registry: VariableRegistry) {
        val int1BySlot = HashMap<Int, VarDef>()
        val str1BySlot = HashMap<Int, VarDef>()
        val charaStr0 = HashMap<Int, VarDef>()
        val charaInt0 = HashMap<Int, VarDef>()
        val charaInt1 = HashMap<Int, VarDef>()

        init {
            for (def in registry.all()) {
                when (def.storage) {
                    Storage.G_INT1 -> int1BySlot[def.index] = def
                    Storage.G_STR1 -> str1BySlot[def.index] = def
                    Storage.C_STR0 -> charaStr0[def.index] = def
                    Storage.C_INT0 -> charaInt0[def.index] = def
                    Storage.C_INT1 -> charaInt1[def.index] = def
                    else -> Unit
                }
            }
        }
    }

    private fun byKey(block: List<SaveEntry>): Map<String, SaveEntry> {
        val m = HashMap<String, SaveEntry>()
        for (e in block) if (e !is SaveEntry.Separator) m[e.key.uppercase()] = e
        return m
    }

    private fun valueOf(e: SaveEntry?, fallback: String): String =
        (e as? SaveEntry.StrValue)?.value ?: fallback

    private fun intOf(e: SaveEntry?): Long = (e as? SaveEntry.IntValue)?.value ?: 0L

    private fun padInts(src: LongArray, n: Int): LongArray {
        if (src.size == n) return src
        val out = LongArray(n)
        src.copyInto(out, 0, 0, minOf(n, src.size))
        return out
    }

    private fun padStrs(src: List<String>, n: Int): Array<String?> =
        Array(n) { i -> src.getOrNull(i) ?: "" }

    private fun toNullableArray(src: List<String>): Array<String?> =
        Array(src.size) { src[it] }
}
