package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.data.text.DetectedTextDecoder
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableRegistry
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.Charset
import java.text.DecimalFormatSymbols
import java.util.Locale

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
 * 2 个 Float 标量（fork 4.981 尾段 dataFloat；4.980 与 1.824 没有这一段）
 * 7 个 Float1D（fork 4.981 尾段 dataFloatArray，各以 __FINISHED 收尾）
 * __EMUERA_1808_STRAT__
 * 每角色：6 或 9 段扩展（键值对 / 键+行列表，各段以 __EMU_SEPARATOR__ 收尾）
 * 8 段系统扩展 + 6 或 9 段用户 #DIM / #DIMF SAVEDATA 扩展（各段均以 __EMU_SEPARATOR__ 收尾）
 * ```
 * 全局档 `global.sav`：
 * ```
 * UniqueCode
 * ScriptVersion
 * GLOBAL 数组（槽位 63）
 * GLOBALS 数组（槽位 5，VariableData.cs:3804-3806 dataStringArray[5]）
 * __EMUERA_1808_STRAT__
 * 6 或 9 段用户 #DIM / #DIMF GLOBAL SAVEDATA 扩展
 * ```
 *
 * 证据：VariableEvaluator.cs:2409-2426（普通写）/ :2428-2472（普通读）/
 * :2474-2511（全局写）/ :2513-2589（全局读）；VariableData.cs:3516-3534（正文 60+1）、
 * :3556-3626（8 系统段 + 6 用户段）；CharacterData.cs:351-369（正文 2+2+17）、
 * :391-423（6 段）；EraDataWriter.cs:27-219；EraDataReader.cs:39-530。
 *
 * ## 经典段浮点尾段（fork 4.981 追加，spec variables.yaml 的 save_note_classic）
 *
 * fork 在经典正文之后、扩展区标记之前多写两段浮点（`VariableData.cs:766-769` 写、
 * `:792-795` 读；调用点是 `varData.SaveToStream`，`VariableEvaluator.cs:2792-2793`）：
 *
 * - `dataFloat`：长度 2（复用 `__COUNT_STRING__`，`VariableData.cs:155`），
 *   一行一个 `value.ToString("G")`（当前文化，`EraDataStream.cs:686-690`）；
 * - `dataFloatArray`：长度 7（复用 `__COUNT_STRING_ARRAY__`，`:156`），
 *   **逐个全写、不截尾零**（与 long 数组的「截到最后非零」不同构，`:719-727`），
 *   每个数组以 `__FINISHED` 收尾。7 个系统数组在 fork 构造期就置为空数组
 *   （`:157-159`）且再无扩容点 ⇒ 实盘形态是 7 个孤立的 `__FINISHED` 行。
 *
 * **4.980 / 1.824 的经典档没有这段**（spec 同名条目），读侧按「经典正文后下一行不是数字
 * ⇒ 无尾段、直接进扩展区」分流（[readClassicFloatTail]），老档不补偿、不抛错。
 * 读到尾段也**不回填变量池**：`dataFloat` 的两格在移植侧没有系统槽名（slot0 未命名、
 * slot1 = RESULTF 且 `IsSavedata=false`），7 个系统浮点数组也没有槽名 —— 保框架、不复值
 * （见 [classicFloatScalar]）。写侧同理：两格按 `SYSTEM_FLOAT_SCALAR_NAMES` 查
 * [SaveEntry.FloatValue]（只有 RESULTF 有名，正常抓取永远没有它 ⇒ 0），7 个数组恒空。
 *
 * 角色正文的**次序与全局段不同**：floatCount 夹在 intCount 与 intArrayCount 之间
 * （`CharacterData.cs:392-408`），顺序为 str / int / float / intArray / strArray / floatArray。
 * fork 里 `__COUNT_SAVE_CHARACTER_FLOAT__ = 0`、`__COUNT_SAVE_CHARACTER_FLOAT_ARRAY__ = 0`
 * （`VariableCode.cs:137` / `:166`）⇒ 实际不落字节，但次序必须照抄，见 [writeCharacterBody]。
 *
 * ## 扩展区的**段集**（4.980 六段 vs fork 4.981 九段）
 *
 * fork 把「浮点」插进扩展区的每一类型之后，两处各加三段，系统区一段不加：
 *
 * | 区域 | 4.980 | fork 4.981 | 证据 |
 * |---|---|---|---|
 * | 角色扩展 | STR0 INT0 STR1 INT1 STR2 INT2 | STR0 INT0 **FLT0** STR1 INT1 **FLT1** STR2 INT2 **FLT2** | `CharacterData.cs:391-423` → fork `:437-503`；读侧 fork `:505-515` 依次调 9 只 reader |
 * | 系统扩展 | 8 段 | **仍 8 段** | fork `VariableData.cs:798-856` 的 `GetExtSaveList` 只有 int/str 六组 + 二维三维两组；系统浮点走经典尾段（上一节） |
 * | 用户扩展（普通档） | STR1 INT1 STR2 INT2 STR3 INT3 | 中间插 **FLT1 / FLT2 / FLT3**，共 9 段 | 4.980 `VariableData.cs:3598-3625`（桶表长度 6）→ fork `:858-876`（桶表长度 :81，`for i < 9`）；读侧 fork `:936-944` |
 * | 用户扩展（全局档） | 6 段 | 9 段 | 4.980 `VariableData.cs:3818` → fork `:1080-1101`；读侧 fork `:1103-1113` |
 *
 * 桶号换算：fork 声明期 `type = (Dimension - 1) * 3` 再 `IsString → 0 / IsInteger → 1 /
 * 其余（浮点）→ 2`（`VariableData.cs:499-505`）⇒ 浮点落桶 2 / 5 / 8；4.980 是
 * `Dimension * 2 - 2 + (IsString ? 0 : 1)`（reference `VariableData.cs:3275-3278`）⇒ 6 桶。
 *
 * 段集由 [SaveConfig.textExtensionSegments] 显式声明（**读写同一处取值**），
 * 不从盘上判 —— 理由见该对象的 KDoc（标记行两版同为 `__EMUERA_1808_STRAT__`，
 * 而移植侧自浮点尾段那一路起就无条件写尾段，尾段的有无只说明「经典正文怎么读」、
 * 不说明「扩展区有几段」）。
 *
 * 三条落地口径：
 * 1. 浮点段在 fork 里**没有系统变量可落**：带 `__SAVE_EXTENDED__` 的 19 个 code 全是
 *    `__INTEGER__` / `__STRING__`（`VariableCode.cs:96-202`）⇒ `GetExtSaveList(Float, *)`
 *    返回空表（`VariableIdentifier.cs:214-220`），盘上形态是**一个孤立的分隔符行**；
 *    段里真有值也只会被消费掉、不进条目（查表赋值，fork `VariableData.cs:892-931`）。
 * 2. 浮点没有 code 位（`VariableCode.cs:8-22` 只有 `__INTEGER__` / `__STRING__`，
 *    `RESULTF` 是 `0x01 | __EXTENDED__`）⇒ [matching] 对浮点段改判 [VarType.FLOAT] + 维度，
 *    整数与字符串段仍走位判据，一条都不改。
 * 3. 用户浮点只有 1 维可达：fork 声明期 `#DIMF` 仅 `case 1` 建 token，其余维度直接
 *    `AbnormalVarDeclaration`（`VariableData.cs:469-473`）⇒ FLT2 / FLT3 两段的**写侧**
 *    在本仓只有对拍夹具能触发（真抓取产不出），读侧必须照消费（PC 段的分隔符不能少读）。
 *
 * 数值行的形态与零规则见 [writeExtended] / [readExtended]：标量 `key:G` 且 `0.0` 不落
 * （fork `EraDataStream.cs:776-783`）、1D 截到最后一个非零项（`:949-966`）、
 * 2D 整行零落空行（`:968-1008`）、3D 每平面 `i{ … }`（`:1011-1067`）；
 * 2D / 3D 浮点段同样受 `emu_version` 门槛（`< 1708` / `< 1729` 直接返回，`:539-540` / `:584-585`），
 * 门槛只写在 [consumedAt] 一处。
 *
 * ## 读侧的**扩展区档位**（M10-SAVE 收口）
 *
 * 标记行不止一种，原版按命中的标记给 `EraDataReader.DataVersion`
 * （`EraDataReader.cs:14-26` 五个常量、`:133-175` 的赋值），随后按档位分流：
 *
 * | 档位 | 角色扩展段 | 系统扩展段 | 用户扩展段 | 证据 |
 * |---|---|---|---|---|
 * | 无标记（正文后直接结束） | — | — | — | `VariableEvaluator.cs:2453-2456`（`SeekEmuStart` 为 false 直接 return） |
 * | 1700 | **4 段** | **4 段**（无 2D/3D） | **不读** | `VariableEvaluator.cs:2457-2462` + `CharacterData.cs:477-511`；`EraDataReader.cs:351-354` / `:415-418`；`VariableData.cs:3694-3697` |
 * | 1708 | 4 段 | **6 段**（无 3D） | **不读** | 同上 + `EraDataReader.cs:438-441` / `:516-519`（3D 两段门槛 1729） |
 * | `1729..1802` | 4 段 | 8 段 | **不读** | 同上（四个 early-return 都不命中） |
 * | `1803..1807` | **6 或 9 段**（段集开关） | 8 段 | **不读** | `VariableEvaluator.cs:2464-2470` + `CharacterData.cs:425-475`；`VariableData.cs:3694-3697` |
 * | `>= 1808` | **6 或 9 段**（段集开关） | 8 段 | **6 或 9 段**（段集开关） | `VariableData.cs:3698-3756`；fork `:936-1003` |
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
 * 全局档**不看档位**：只要 `SeekEmuStart` 命中任意标记就读取用户全局扩展
 * （`VariableEvaluator.cs:2563-2566` → `VariableData.LoadGlobalFromStream1808`，:3849-3880），
 * 但这几段里的 2D / 3D 仍受上面同一组 `emu_version` 门槛约束（那几个读函数是共用的）。
 *
 * TODO(verify)：旧档 writer 的反编译码不在 reference（现存只有 1808 的
 * `VariableData.SaveToStreamExtended`，:3556-3626），无法直接确认 1700/1708 实际写了几段。
 * 本层按 reader 的消费口径落地（只消费该档存在的段，写了但不该读的尾段不消费也不报错），
 * 与「原版会怎么读」逐行一致；若旧 writer 真写满 8 段，尾部多出的分隔符只是不被读，
 * 不构成错误。卡点：语料 `sav/` 与 `<internal-workdir>/<sample-backup>/` 全是 1808 二进制档，无旧文本档实测。
 * 同一组门槛也落在**用户扩展段**上：`LoadGlobalFromStream1808`（`VariableData.cs:3850-3855`）
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

    /**
     * 扩展段的形状。`STR*` / `INT*` 的分组键来自 `VariableCode` 的位（[SEG_MASK]），
     * `FLT*` 没有位可用 —— fork 的浮点类型**不占**任何 code 位
     * （`VariableCode.cs:8-22` 只有 `__INTEGER__` / `__STRING__`，`RESULTF` 是
     * `0x01 | __EXTENDED__`、`:124`），分段归属由 `VariableDescriptor` 的 Kind 给出
     * （`VariableIdentifier.cs:196-200` 按 `(desc.Kind, desc.Dimension)` 建 `extSaveListDic`）
     * ⇒ 浮点段的变量筛选走 [matching] 的类型分支，不查 code 位。
     */
    private enum class Kind { STR0, INT0, FLT0, STR1, INT1, FLT1, STR2, INT2, FLT2, STR3, INT3, FLT3 }

    /** 该形状是否浮点段（浮点段没有 code 位，见 [Kind]）。 */
    private val Kind.isFloat: Boolean
        get() = this == Kind.FLT0 || this == Kind.FLT1 || this == Kind.FLT2 || this == Kind.FLT3

    /** 该形状的元素维度（0 标量 / 1 / 2 / 3），与 [Kind] 的尾数字一致。 */
    private val Kind.dimension: Int get() = when (this) {
        Kind.STR0, Kind.INT0, Kind.FLT0 -> 0
        Kind.STR1, Kind.INT1, Kind.FLT1 -> 1
        Kind.STR2, Kind.INT2, Kind.FLT2 -> 2
        Kind.STR3, Kind.INT3, Kind.FLT3 -> 3
    }

    private data class Seg(val key: Int, val kind: Kind)

    // 浮点段的 Seg：key = 0 且不参与 code 位匹配（理由见 [Kind]）。
    // 只有角色区出现浮点段（`CharacterData.cs:437-503`）；系统区 8 段不含浮点
    // （fork 的 `VariableData.SaveToStreamExtended`，:798-856），用户区直接列 [Kind]。
    private val SEG_FLT0 = Seg(0, Kind.FLT0)
    private val SEG_FLT1 = Seg(0, Kind.FLT1)
    private val SEG_FLT2 = Seg(0, Kind.FLT2)

    /** `VariableData.SaveToStreamExtended` 的 8 段顺序（VariableData.cs:3558-3597）。 */
    private val VAR_SEGS = listOf(
        Seg(KEY_STR0, Kind.STR0), Seg(KEY_INT0, Kind.INT0),
        Seg(KEY_STR1, Kind.STR1), Seg(KEY_INT1, Kind.INT1),
        Seg(KEY_STR2, Kind.STR2), Seg(KEY_INT2, Kind.INT2),
        Seg(KEY_STR3, Kind.STR3), Seg(KEY_INT3, Kind.INT3),
    )

    /**
     * `CharacterData.SaveToStreamExtended` 的 6 段顺序（CharacterData.cs:393-422），
     * 即 [TextExtensionSegments.CLASSIC_6] 的角色扩展段表。
     */
    private val CHARA_SEGS = listOf(
        Seg(KEY_C_STR0, Kind.STR0), Seg(KEY_C_INT0, Kind.INT0),
        Seg(KEY_C_STR1, Kind.STR1), Seg(KEY_C_INT1, Kind.INT1),
        Seg(KEY_C_STR2, Kind.STR2), Seg(KEY_C_INT2, Kind.INT2),
    )

    /**
     * fork 4.981 的角色扩展段 = 上面 6 段各类型后插一段浮点，共 9 段
     * （`CharacterData.cs:437-503`：String-Scalar / Integer-Scalar / **Float-Scalar** /
     * String-Array1D / Integer-Array1D / **Float-Array1D** / String-Array2D /
     * Integer-Array2D / **Float-Array2D**，每组各一个 `EmuSeparete()`）。
     * 读侧同序：`LoadFromStreamExtended` 依次调 `ReadStringExtended` / `ReadInt64Extended` /
     * `ReadDoubleExtended` / `ReadStringArrayExtended` / `ReadInt64ArrayExtended` /
     * `ReadDoubleArrayExtended` / `ReadStringArray2DExtended` / `ReadInt64Array2DExtended` /
     * `ReadDoubleArray2DExtended`（`CharacterData.cs:505-515`）。
     */
    private val CHARA_SEGS_FLOAT_9 = listOf(
        Seg(KEY_C_STR0, Kind.STR0), Seg(KEY_C_INT0, Kind.INT0), SEG_FLT0,
        Seg(KEY_C_STR1, Kind.STR1), Seg(KEY_C_INT1, Kind.INT1), SEG_FLT1,
        Seg(KEY_C_STR2, Kind.STR2), Seg(KEY_C_INT2, Kind.INT2), SEG_FLT2,
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

    /**
     * 用户变量 6 段的形状顺序（VariableData.cs:3598-3625 / :3850-3855），
     * 即 [TextExtensionSegments.CLASSIC_6] 的用户段表。
     */
    private val USER_KINDS = listOf(Kind.STR1, Kind.INT1, Kind.STR2, Kind.INT2, Kind.STR3, Kind.INT3)

    /**
     * fork 4.981 的用户段表 = 每类型后插浮点，共 9 段（`VariableData.cs:858-876` 普通档、
     * `:1080-1101` 全局档：`for (i in 0..8)` 走 `userDefinedSaveVarList[i]`，每桶一个
     * `EmuSeparete()`）。桶号由声明期算出：`type = (Dimension - 1) * 3` 再
     * `IsString → 0 / IsInteger → 1 / 其余（浮点）→ 2`（`VariableData.cs:499-505`）
     * ⇒ 浮点落桶 **2 / 5 / 8**。读侧同序：`ReadStringArrayExtended` →
     * `ReadInt64ArrayExtended` → `ReadDoubleArrayExtended` → 2D 三只 → 3D 三只
     * （`VariableData.cs:936-944` 普通档、`:1105-1113` 全局档）。
     */
    private val USER_KINDS_FLOAT_9 = listOf(
        Kind.STR1, Kind.INT1, Kind.FLT1,
        Kind.STR2, Kind.INT2, Kind.FLT2,
        Kind.STR3, Kind.INT3, Kind.FLT3,
    )

    /**
     * 角色扩展段表。写侧不传 [tier]（自己写的标记恒 1808）；读侧传命中的档位，
     * `< 1803` 两版都是 4 段（fork 的 `LoadFromStreamExtended_Old1802`，
     * fork-src CharacterData.cs:569-599 仍只读前四段）⇒ 段集开关只对 1803 及以上生效。
     */
    private fun charaSegsFor(segments: TextExtensionSegments, tier: Int = TIER_1808): List<Seg> = when {
        tier < TIER_1803 -> CHARA_OLD1802_SEGS
        segments == TextExtensionSegments.FLOAT_9 -> CHARA_SEGS_FLOAT_9
        else -> CHARA_SEGS
    }

    /** 用户段表（普通档与全局档共用；[TextExtensionSegments.CLASSIC_6] = 现有 6 段）。 */
    private fun userKindsFor(segments: TextExtensionSegments): List<Kind> =
        if (segments == TextExtensionSegments.FLOAT_9) USER_KINDS_FLOAT_9 else USER_KINDS

    private const val GLOBAL_INT_SLOT = 63
    private const val GLOBALS_STR_SLOT = 5
    private const val SAVESTR_SLOT = 0

    /** `dataFloat` 的长度 = `__COUNT_STRING__`（fork-src VariableData.cs:155，值 2）。 */
    private const val FLOAT_SCALAR_COUNT = 2

    /** `dataFloatArray` 的长度 = `__COUNT_STRING_ARRAY__`（fork-src VariableData.cs:156，值 7）。 */
    private const val FLOAT_ARRAY_COUNT = 7

    /**
     * 系统 `dataFloat` 槽名：0 号在 fork 里没有变量名（TODO(verify)：归属未钉），
     * 1 号是 `RESULTF`（spec variables.yaml 的 float_vars.scalars）。两者都不是 SAVEDATA
     * （`__EXTENDED__` ⇒ IsSavedata=false），所以正常抓取不会带 [SaveEntry.FloatValue]；
     * 表里留名是为了「条目优先」这一条写侧口径可兑现（[classicFloatScalar]）。
     */
    private val SYSTEM_FLOAT_SCALAR_NAMES: List<String?> = listOf(null, "RESULTF")

    // 角色正文分段计数（CharacterData.cs:392-408 的次序 + fork 的常量值）
    private const val CHARA_STR_COUNT = 2                // __COUNT_SAVE_CHARACTER_STRING__
    private const val CHARA_INT_COUNT = 2                // __COUNT_SAVE_CHARACTER_INTEGER__
    private const val CHARA_FLOAT_COUNT = 0              // VariableCode.cs:137
    private const val CHARA_INT_ARRAY_COUNT = 17         // __COUNT_SAVE_CHARACTER_INTEGER_ARRAY__
    private const val CHARA_STR_ARRAY_COUNT = 0          // __COUNT_SAVE_CHARACTER_STRING_ARRAY__
    private const val CHARA_FLOAT_ARRAY_COUNT = 0        // VariableCode.cs:166

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
        /** 浮点尾段的「当前文化」（`double.ToString("G")` 不带 InvariantCulture，EraDataStream.cs:686-690）。 */
        locale: Locale = Locale.getDefault(),
        /** 扩展区段集；默认 [TextExtensionSegments.CLASSIC_6] ⇒ 既有产物字节逐位不变。 */
        segments: TextExtensionSegments = TextExtensionSegments.CLASSIC_6,
    ): ByteArray {
        if (data.fileType != SaveFileKind.NORMAL && data.fileType != SaveFileKind.GLOBAL) {
            throw SaveFormatException("文本格式只支持 Normal / Global（实际 ${data.fileType}）")
        }
        val t = Tables(registry)
        val vars = LinkedHashMap<String, SaveEntry>()
        for (e in data.variables) if (e !is SaveEntry.Separator) vars[e.key.uppercase()] = e
        val userVars = data.variables.filter { registry.def(it.key) == null }
        val w = Writer(newline, decimalSeparatorOf(locale))

        w.int(data.uniqueCode)
        w.int(data.scriptVersion)
        when (data.fileType) {
            SaveFileKind.NORMAL -> {
                w.text(data.text)
                w.int(data.characters.size.toLong())
                for (block in data.characters) writeCharacterBody(w, t, block)
                for (slot in 0 until 60) w.intArray1(t.int1BySlot[slot]?.let { vars[it.name.uppercase()] })
                w.strArray1(t.str1BySlot[SAVESTR_SLOT]?.let { vars[it.name.uppercase()] })
                writeClassicFloatTail(w, vars)
                w.text(EMU_START)
                val charaSegs = charaSegsFor(segments)
                for (block in data.characters) writeCharacterExtended(w, registry, block, charaSegs)
                for (seg in VAR_SEGS) {
                    writeSystemSegment(w, registry, seg, vars)
                    w.text(SEPARATOR)
                }
                for (kind in userKindsFor(segments)) {
                    writeUserSegment(w, kind, userVars.filter { kindOf(it) == kind })
                    w.text(SEPARATOR)
                }
            }

            else -> {
                w.intArray1(t.int1BySlot[GLOBAL_INT_SLOT]?.let { vars[it.name.uppercase()] })
                w.strArray1(t.str1BySlot[GLOBALS_STR_SLOT]?.let { vars[it.name.uppercase()] })
                w.text(EMU_START)
                for (kind in userKindsFor(segments)) {
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

    /**
     * 角色正文（`CharacterData.cs:392-408` 的分段次序）：str / int / **float** / intArray /
     * strArray / **floatArray**。floatCount 夹在 intCount 与 intArrayCount **之间**，与全局段
     * 「数组之后收尾」不可混用。fork 的三个浮点计数恒 0（`VariableCode.cs:137` / `:166`）⇒
     * 实盘仍是 2 String + 2 Int + 17 Int1D + 0 Str1D，写出来的循环只为把次序钉在代码里。
     */
    private fun writeCharacterBody(w: Writer, t: Tables, block: List<SaveEntry>) {
        val m = byKey(block)
        // 槽名一律大写查：[byKey] 的键是 `e.key.uppercase()`，而 `def.name` 本体不保证大写
        // （registry 内部同样按 `def.name.uppercase()` 建键）。漏一层 uppercase 会静默写成
        // 空名 / 0（丢 NAME·CALL·NO·ISASSI），与下面 int1 槽的口径必须一致。
        for (slot in 0 until CHARA_STR_COUNT) w.text(valueOf(m[t.charaStr0[slot]?.name?.uppercase()], ""))
        for (slot in 0 until CHARA_INT_COUNT) w.int(intOf(m[t.charaInt0[slot]?.name?.uppercase()]))
        for (slot in 0 until CHARA_FLOAT_COUNT) w.double(0.0)
        for (slot in 0 until CHARA_INT_ARRAY_COUNT) w.intArray1(t.charaInt1[slot]?.let { m[it.name.uppercase()] })
        for (slot in 0 until CHARA_STR_ARRAY_COUNT) w.strArray1(null)
        for (slot in 0 until CHARA_FLOAT_ARRAY_COUNT) w.doubleArray1(DoubleArray(0))
    }

    /**
     * 角色扩展：[TextExtensionSegments.CLASSIC_6] 走 6 段系统变量（CharacterData.cs:391-423），
     * [TextExtensionSegments.FLOAT_9] 走 9 段（CharacterData.cs:437-503）。用户 CHARADATA 变量在
     * 文本格式里不存在（见类 KDoc 差异 2），这里显式跳过。
     */
    private fun writeCharacterExtended(
        w: Writer,
        registry: VariableRegistry,
        block: List<SaveEntry>,
        segs: List<Seg>,
    ) {
        val m = byKey(block)
        for (seg in segs) {
            for (def in matching(registry, seg, chara = true)) {
                writeExtended(w, m[def.name.uppercase()], seg.kind)
            }
            w.text(SEPARATOR)
        }
    }

    /**
     * fork 4.981 的经典浮点尾段（`VariableData.cs:766-769`）：先 2 个标量、再 7 个 1 维数组。
     *
     * 标量按 [SYSTEM_FLOAT_SCALAR_NAMES] 找 [SaveEntry.FloatValue]；两格在移植侧都**未建模**
     * （slot0 无名、slot1 的 RESULTF 不是 SAVEDATA），正常抓取没有条目 ⇒ 写 `0`（.NET G 对
     * 0.0 的形态，`EraDataStream.cs:686-690`）。7 个系统浮点数组在 fork 里恒为空数组
     * （`VariableData.cs:157-159`，无扩容点）⇒ 各写一个孤立的 `__FINISHED`。这是
     * 「未建模 ⇒ 保框架、不复值」，不是丢了可存数据。
     *
     * 用户 `#DIMF SAVEDATA` 的浮点条目**不走这里**（它们属于扩展区的浮点段，
     * 见类 KDoc「扩展区的段集」与 [TextExtensionSegments.FLOAT_9]）。
     */
    private fun writeClassicFloatTail(w: Writer, vars: Map<String, SaveEntry>) {
        for (slot in 0 until FLOAT_SCALAR_COUNT) w.double(classicFloatScalar(vars, slot))
        repeat(FLOAT_ARRAY_COUNT) { w.doubleArray1(DoubleArray(0)) }
    }

    private fun classicFloatScalar(vars: Map<String, SaveEntry>, slot: Int): Double {
        val name = SYSTEM_FLOAT_SCALAR_NAMES.getOrNull(slot) ?: return 0.0
        return (vars[name] as? SaveEntry.FloatValue)?.value ?: 0.0
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

    /**
     * 条目形状 → 用户段的桶（`VariableData.cs:499-505` 的 `(Dimension - 1) * 3 + kindIdx`）。
     *
     * 0 维条目落 **1 维**段是既有口径（`#DIM` 用户变量本就至少 1 维；`StrValue` / `IntValue`
     * 早就这么归，见下），浮点照抄成 `FloatValue → FLT1` 而不是新立一条规则：
     * fork 的 `#DIMF` 只建 1 维 token（`VariableData.cs:469-473`，0 维落
     * `AbnormalVarDeclaration`）⇒ 用户区没有 0 维浮点桶，这一支在真档里不可达，
     * 只是让四种浮点条目形都有归属、不会静默蒸发。
     */
    private fun kindOf(e: SaveEntry): Kind? = when (e) {
        is SaveEntry.IntValue, is SaveEntry.IntArray1 -> Kind.INT1
        is SaveEntry.IntArray2 -> Kind.INT2
        is SaveEntry.IntArray3 -> Kind.INT3
        is SaveEntry.StrValue, is SaveEntry.StrArray1 -> Kind.STR1
        is SaveEntry.StrArray2 -> Kind.STR2
        is SaveEntry.StrArray3 -> Kind.STR3
        is SaveEntry.FloatValue, is SaveEntry.FloatArray1 -> Kind.FLT1
        is SaveEntry.FloatArray2 -> Kind.FLT2
        is SaveEntry.FloatArray3 -> Kind.FLT3
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

            // ---------------- 浮点四段（fork 4.981）----------------
            Kind.FLT0 -> {
                val v = (e as? SaveEntry.FloatValue)?.value ?: return
                w.extDoubleScalar(e.key, v)
            }

            Kind.FLT1 -> {
                val a = when (e) {
                    is SaveEntry.FloatArray1 -> e.values
                    is SaveEntry.FloatValue -> doubleArrayOf(e.value)
                    else -> return
                }
                w.extDoubleArray1(e.key, a)
            }

            Kind.FLT2 -> {
                val a = e as? SaveEntry.FloatArray2 ?: return
                w.extDoubleArray2(e.key, a)
            }

            Kind.FLT3 -> {
                val a = e as? SaveEntry.FloatArray3 ?: return
                w.extDoubleArray3(e.key, a)
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
    fun read(
        bytes: ByteArray,
        registry: VariableRegistry,
        kind: SaveFileKind,
        /** 浮点行的「当前文化」解析口径（`double.TryParse` 不带 InvariantCulture，EraDataStream.cs:74-83）。 */
        locale: Locale = Locale.getDefault(),
        /** 扩展区段集；读 PC 的 4.981 文本档要传 [TextExtensionSegments.FLOAT_9]（理由见其 KDoc）。 */
        segments: TextExtensionSegments = TextExtensionSegments.CLASSIC_6,
    ): SaveData {
        val t = Tables(registry)
        val c = Cursor(decode(bytes), decimalSeparatorOf(locale), groupingSeparatorOf(locale))
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
                readClassicFloatTail(c)
                // 扩展区档位由标记行决定（EraDataReader.cs:133-175 的 emu_version）；
                // 没有标记（文件在正文后就结束）⇒ 完全不读扩展区（VariableEvaluator.cs:2453-2456）。
                val tier = if (c.hasMore()) c.seekEmuStart() else null
                if (tier != null) {
                    // 档位分流（VariableEvaluator.cs:2457-2471 + VariableData.cs:3694-3697）：
                    // < 1803 的角色扩展段只有 4 段（两版同，CharacterData.cs:477-511 /
                    // fork :569-599），< 1808 不读用户扩展（读完系统扩展即返回）。
                    // 1803 及以上的角色段数与 1808 及以上的用户段数由 [segments] 决定
                    // —— 标记行给不出这个信息，理由见 [TextExtensionSegments]。
                    val charaSegs = charaSegsFor(segments, tier)
                    for (block in blocks) readCharacterExtended(c, registry, block, charaSegs)
                    for (seg in systemSegsFor(tier)) readSystemSegment(c, registry, seg, variables)
                    if (tier >= TIER_1808) {
                        for (k in userKindsFor(segments)) variables.addAll(readUserSegment(c, k, tier))
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
                    for (k in userKindsFor(segments)) variables.addAll(readUserSegment(c, k, tier))
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

    /**
     * 读 fork 4.981 的经典浮点尾段；4.980 老档按「下一行不是数字」判无尾段
     * （spec variables.yaml 的 save_note_classic）。
     *
     * 判据不靠版本标记：fork 与 4.980 的扩展区标记都是 `__EMUERA_1808_STRAT__`，
     * 盘上唯一区分点是尾段本身 —— 旧档经典正文后直接是标记行（或 EOF），新档是 2 行数字。
     * 读到尾段也**不回填变量池**（两格系统槽 + 7 个系统数组在移植侧没有系统槽名），
     * 只按原版长度消费：标量用 [Cursor.double]（EraDataStream.cs:74-83）；
     * 数组用 [Cursor.doubleArray1]（`:113-133`），声明长度 0 ⇒ 只跳到 FINISHER、不解析值。
     */
    private fun readClassicFloatTail(c: Cursor) {
        val head = c.peek() ?: return
        if (c.parseDoubleOrNull(head) == null) return
        repeat(FLOAT_SCALAR_COUNT) { c.double() }
        repeat(FLOAT_ARRAY_COUNT) { c.doubleArray1(limit = 0) }
    }

    /**
     * 角色正文（`CharacterData.cs:409-435` 的读侧次序）：str / int / **float** / intArray /
     * strArray / **floatArray**。fork 的三个浮点计数恒 0（`VariableCode.cs:137` / `:166`）⇒
     * 盘上没有这些行；读侧保留零次循环，顺便钉住「float 在 int 与 intArray 之间」。
     */
    private fun readCharacterBody(c: Cursor, t: Tables): MutableList<SaveEntry> {
        val out = ArrayList<SaveEntry>()
        for (slot in 0 until CHARA_STR_COUNT) {
            val s = c.text()
            t.charaStr0[slot]?.let { out.add(SaveEntry.StrValue(it.name, s)) }
        }
        for (slot in 0 until CHARA_INT_COUNT) {
            val v = c.int64()
            t.charaInt0[slot]?.let { out.add(SaveEntry.IntValue(it.name, v)) }
        }
        repeat(CHARA_FLOAT_COUNT) { c.double() }
        for (slot in 0 until CHARA_INT_ARRAY_COUNT) {
            val def = t.charaInt1[slot]
            val values = c.intArray1(def?.dims?.getOrNull(0) ?: Int.MAX_VALUE)
            if (def != null) out.add(SaveEntry.IntArray1(def.name, padInts(values, def.dims[0])))
        }
        repeat(CHARA_STR_ARRAY_COUNT) { c.strArray1() }
        repeat(CHARA_FLOAT_ARRAY_COUNT) { c.doubleArray1() }
        return out
    }

    /**
     * 角色扩展段。1803 及以上按 [segments] 走 6 或 9 段，1803 以下走 **Old1802 的 4 段**
     * （4.980 `CharacterData.cs:477-511`、fork `CharacterData.cs:569-599` 都只调
     * `ReadStringExtended` / `ReadInt64Extended` / `ReadStringArrayExtended` /
     * `ReadInt64ArrayExtended` 四只 reader），分流点 `VariableEvaluator.cs:2457-2470`。
     * 段表由 [charaSegsFor] 给、消费由 [readExtended] 一处实现 ⇒ 两条档位 × 两个段集不可能读歪。
     */
    private fun readCharacterExtended(
        c: Cursor,
        registry: VariableRegistry,
        block: MutableList<SaveEntry>,
        segs: List<Seg>,
    ) {
        for (seg in segs) block.addAll(readExtended(c, seg.kind, matching(registry, seg, chara = true)))
    }

    /**
     * 系统扩展段：只认 code 上带 `__SAVE_EXTENDED__` 且分段 key 匹配的变量。
     * fork 没给系统区加浮点段（`VariableData.cs:798-856` 仍按 8 组 `GetExtSaveList` 走，
     * 系统浮点走的是经典尾段），所以本函数只会收到非浮点段；[readExtended] 是共用的。
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
        out.addAll(readExtended(c, seg.kind, matching(registry, seg, chara = false)))
    }

    /**
     * 消费**一段**扩展区，返回条目（写侧的对偶，见 [writeExtended]）。
     *
     * [defs] 非 null（系统段 / 角色段）：只认这批名字，且**按给定序**产出 —— 与原版
     * 「reader 出字典，再 foreach `GetExtSaveList` 查表赋值」的同一条次序
     * （fork-src CharacterData.cs:519-566、VariableData.cs:892-931），重写时字节才稳定。
     * [defs] 为 null（用户段）：整段按**文件序**收下，未知名字也保留，
     * 由 [SaveData.applyTo] 按「不认识就跳过」处置（VariableData.cs:949-1003 的 TryGetValue 同口径）。
     */
    private fun readExtended(c: Cursor, kind: Kind, defs: List<VarDef>?): List<SaveEntry> = when (kind) {
        Kind.STR0 -> c.stringExtended().extendedEntries(defs) { n, v -> SaveEntry.StrValue(n, v) }
        Kind.INT0 -> c.intExtended().extendedEntries(defs) { n, v -> SaveEntry.IntValue(n, v) }
        Kind.FLT0 -> c.doubleExtended().extendedEntries(defs) { n, v -> SaveEntry.FloatValue(n, v) }

        Kind.STR1 -> c.strArrayExtended().extendedEntries(defs) { n, v -> SaveEntry.StrArray1(n, toNullableArray(v)) }
        Kind.INT1 -> c.intArrayExtended().extendedEntries(defs) { n, v -> SaveEntry.IntArray1(n, v.toLongArray()) }
        Kind.FLT1 -> c.doubleArrayExtended().extendedEntries(defs) { n, v -> SaveEntry.FloatArray1(n, v.toDoubleArray()) }

        // 字符串 2D / 3D 段原版只认「直接一个分隔符」这一种形态（EraDataReader.cs:398-418 / :477-497）
        Kind.STR2, Kind.STR3 -> {
            c.expectSeparatorOnly()
            emptyList()
        }

        Kind.INT2 -> c.intArray2DExtended().extendedEntries(defs) { n, v -> array2Of(n, v) }
        Kind.FLT2 -> c.doubleArray2DExtended().extendedEntries(defs) { n, v -> floatArray2Of(n, v) }
        Kind.INT3 -> c.intArray3DExtended().extendedEntries(defs) { n, v -> array3Of(n, v) }
        Kind.FLT3 -> c.doubleArray3DExtended().extendedEntries(defs) { n, v -> floatArray3Of(n, v) }
    }

    /** [readExtended] 的两种取序（见其 KDoc）。 */
    private fun <T> Map<String, T>.extendedEntries(
        defs: List<VarDef>?,
        build: (String, T) -> SaveEntry,
    ): List<SaveEntry> = if (defs == null) {
        entries.map { (name, value) -> build(name, value) }
    } else {
        defs.mapNotNull { def -> this[def.name]?.let { build(def.name, it) } }
    }

    /** 用户变量段：名字未知时也保留条目（[SaveData.applyTo] 按「不认识就跳过」处理）。 */
    private fun readUserSegment(c: Cursor, kind: Kind, tier: Int): List<SaveEntry> {
        if (!consumedAt(kind, tier)) return emptyList()
        if (kind.dimension == 0) return emptyList() // 用户变量没有 0 维形态（三版同）
        return readExtended(c, kind, null)
    }

    /**
     * 该段在 [tier] 档里是否存在（存在才消费分隔符）。
     *
     * 门槛逐条取 `EraDataReader` 的早退分支：
     * - 2D（STR2 / INT2 / **FLT2**）：`emu_version < 1708` 直接返回，不读
     *   （`ReadInt64Array2DExtended` EraDataReader.cs:351、`ReadStringArray2DExtended` :415、
     *   fork 的 `ReadDoubleArray2DExtended` EraDataStream.cs:539-540）；
     * - 3D（STR3 / INT3 / **FLT3**）：`emu_version < 1729` 直接返回，不读
     *   （:438 / :516 / fork `ReadDoubleArray3DExtended` EraDataStream.cs:584-585）；
     * - 0 维与 1 维（含 fork 新增的 `ReadDoubleExtended` :257、`ReadDoubleArrayExtended` :499）
     *   **没有**早退分支 ⇒ 各档一律消费。
     *
     * 角色扩展段用不到本方法：1803 档才带 2D 段（`CharacterData.cs:431-432`），
     * 而 1803 ≥ 1708；1803 以下走 4 段（`CharacterData.cs:477-511`），没有 2D / 3D。
     */
    private fun consumedAt(kind: Kind, tier: Int): Boolean = when (kind) {
        Kind.STR2, Kind.INT2, Kind.FLT2 -> tier >= TIER_1708
        Kind.STR3, Kind.INT3, Kind.FLT3 -> tier >= TIER_1729
        else -> true
    }

    /**
     * 段 → 该段的系统变量清单（`VariableIdentifier.GetExtSaveList` 的移植形）。
     *
     * `STR*` / `INT*` 沿用 code 位判据（`VariableCode.cs:9-10` 的 `__INTEGER__` / `__STRING__`
     * 两位 + 维度位 + 角色位，与 `GetExtSaveList` 的 `(Kind, Dimension)` 分组等价）。
     * **浮点段不能查位**：fork 的浮点不占任何 kind 位（`VariableCode.cs:8-22` 没有
     * `__FLOAT__`，`RESULTF` 只有 `__EXTENDED__`），分组靠 `VariableDescriptor` 的 Kind
     * （`VariableIdentifier.cs:196-200`）⇒ 这里改判 [VarDef.type]，其余两个条件（`SAVE_EXTENDED`
     * 位、角色归属）与整数分支同一条。
     * 实际结果：fork 里带 `SAVE_EXTENDED` 的系统变量只有整数与字符串
     * （`VariableCode.cs:96-202` 的 19 个 `SAVE_EXTENDED` 定义全是 `__INTEGER__` / `__STRING__`）
     * ⇒ 系统 / 角色的浮点段恒为空段（一个分隔符行），与 PC 盘上形态一致。
     */
    private fun matching(registry: VariableRegistry, seg: Seg, chara: Boolean): List<VarDef> =
        registry.all().filter {
            it.isCharacterData == chara &&
                (it.code and VariableCode.SAVE_EXTENDED) == VariableCode.SAVE_EXTENDED &&
                (
                    if (seg.kind.isFloat) it.type == VarType.FLOAT && it.dimension == seg.kind.dimension
                    else (it.code and SEG_MASK) == seg.key
                    )
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

    /** 浮点 2D（fork `ReadDoubleArray2DExtended`，EraDataStream.cs:534-577 交出 `List<double[]>`）压平。 */
    private fun floatArray2Of(key: String, rows: List<DoubleArray>): SaveEntry.FloatArray2 {
        val d0 = rows.size
        val d1 = rows.maxOfOrNull { it.size } ?: 0
        val values = DoubleArray(dimProduct(key, d0, d1))
        for (i in 0 until d0) rows[i].copyInto(values, i * d1, 0, rows[i].size)
        return SaveEntry.FloatArray2(key, d0, d1, values)
    }

    /** 浮点 3D（fork `ReadDoubleArray3DExtended`，:579-633 交出 `List<List<double[]>>`）压平。 */
    private fun floatArray3Of(key: String, planes: List<List<DoubleArray>>): SaveEntry.FloatArray3 {
        val d0 = planes.size
        val d1 = planes.maxOfOrNull { it.size } ?: 0
        val d2 = planes.maxOfOrNull { p -> p.maxOfOrNull { it.size } ?: 0 } ?: 0
        val values = DoubleArray(dimProduct(key, d0, d1, d2))
        for (i in 0 until d0) {
            for (j in 0 until planes[i].size) {
                val row = planes[i][j]
                row.copyInto(values, (i * d1 + j) * d2, 0, row.size)
            }
        }
        return SaveEntry.FloatArray3(key, d0, d1, d2, values)
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

    /**
     * 文本档解码 = `EncodingHandler.DetectEncoding` 那条链（spec: EncodingHandler.cs:15-41，
     * 口径详见 verify-notes §4.1 与 [com.eratw.port.core.data.text.DetectedTextDecoder] 的 KDoc）：
     * BOM → 严格 UTF-8 → 严格 CP932 → **失败即整文件拒读**。
     *
     * 这里以前用「U+FFFD 嗅探 + 宽松 Shift_JIS + 兜底」的启发式：一份本身含 U+FFFD 的存档
     * （任何一次乱码写回都会留下）会被整篇按 Shift_JIS 重解，把完好文案全变乱码，而读→改→写
     * 闭环会把乱码**固化进档**。原版在解码失败时是直接放弃该文件（`EraStreamReader.Open`
     * 返回 false，spec: EraStreamReader.cs:50-59），判据是严格解码的成功/失败二分，不是猜。
     */
    private fun decode(bytes: ByteArray): String =
        DetectedTextDecoder.decode(bytes)
            ?: throw SaveFormatException("存档文本编码既不属严格 UTF-8 也不属严格 CP932，整文件拒读")

    private class Writer(private val newline: String, private val decimalSeparator: Char) {
        val text = StringBuilder(1 shl 16)

        fun text(s: String) {
            text.append(s).append(newline)
        }

        fun int(v: Long) = text(v.toString())

        /** `EraDataWriter.Write(double)`（EraDataStream.cs:686-690）：`value.ToString("G")`，当前文化。 */
        fun double(v: Double) = text(formatDoubleG(v, decimalSeparator))

        /** `EraDataWriter.Write(double[])`（EraDataStream.cs:719-727）：逐个全写、不截尾零，再 `__FINISHED`。 */
        fun doubleArray1(a: DoubleArray) {
            for (x in a) double(x)
            text(FINISHED)
        }

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

        // ---------------- 浮点四段（fork 4.981 的 WriteExtended 重载）----------------
        // 三条与整数段同构的规则、但**取值条件用 `!= 0.0`**（IEEE 下 -0.0 判等 0.0 ⇒ 负零不落）；
        // 数值行的形态一律是 `double.ToString("G")`（当前文化，见 [formatDoubleG]）。

        /**
         * `WriteExtended(key, double)`（EraDataStream.cs:776-783）：0 不写，余 `key:G`。
         *
         * 原版这里是 `string.Format("{0}:{1}", key, value)` 而**不是** `ToString("G")`，
         * 两者在 .NET 8 对同一文化的取值逐字相同（12 个代表值 × en-US / de-DE / ja-JP /
         * invariant 全等，探针 `<work-root>/_g8_evidence/probe-g8.txt`），故与 [double] 共用换算。
         */
        fun extDoubleScalar(key: String, v: Double) {
            if (v == 0.0) return
            text("$key:${doubleText(v)}")
        }

        /** `WriteExtended(key, double[])`（:949-966）：截到最后一个非零项，全零则整条不落。 */
        fun extDoubleArray1(key: String, a: DoubleArray) {
            var last = -1
            for (i in a.indices) if (a[i] != 0.0) last = i
            if (last < 0) return
            text(key)
            for (i in 0..last) text(doubleText(a[i]))
            text(FINISHED)
        }

        /** `WriteExtended(key, double[,])`（:968-1008）：行末空行表示整行零。 */
        fun extDoubleArray2(key: String, e: SaveEntry.FloatArray2) {
            var lastRow = -1
            val lastCol = IntArray(e.dim0)
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    if (e.values[i * e.dim1 + j] != 0.0) {
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
                    sb.append(doubleText(e.values[i * e.dim1 + j]))
                }
                text(sb.toString())
            }
            text(FINISHED)
        }

        /** `WriteExtended(key, double[,,])`（:1011-1067）：每个平面以「i{」开头、「}」收尾。 */
        fun extDoubleArray3(key: String, e: SaveEntry.FloatArray3) {
            var lastPlane = -1
            val planeRows = IntArray(e.dim0)
            val lastCol = Array(e.dim0) { IntArray(e.dim1) }
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    for (k in 0 until e.dim2) {
                        if (e.values[(i * e.dim1 + j) * e.dim2 + k] != 0.0) {
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
                        sb.append(doubleText(e.values[(i * e.dim1 + j) * e.dim2 + k]))
                    }
                    text(sb.toString())
                }
                text("}")
            }
            text(FINISHED)
        }

        /** 一行浮点的形态：`value.ToString("G")`（当前文化），与 [double] 同一个换算。 */
        private fun doubleText(v: Double): String = formatDoubleG(v, decimalSeparator)
    }

    private class Cursor(
        text: String,
        private val decimalSeparator: Char,
        private val groupingSeparator: Char,
    ) {
        private val lines: List<String> = text.split('\n').map { it.removeSuffix("\r") }
        private var pos = 0

        fun hasMore(): Boolean = pos < lines.size

        /** 只看下一行、不前进（尾段探测用）。 */
        fun peek(): String? = lines.getOrNull(pos)

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
         * `ReadDouble`（EraDataStream.cs:74-83）：`double.TryParse`，**不带** InvariantCulture
         * ⇒ 小数点、分组符都按调用方给的当前文化判读。原版失败抛
         * `CanNotInterpretNum`；本层同 [int64] 的口径抛 [SaveFormatException]。
         */
        fun double(): Double {
            val s = text()
            return parseDoubleOrNull(s)
                ?: throw SaveFormatException("存档第 $pos 行的数字不合法：\"$s\"")
        }

        /**
         * `ReadDoubleArray`（EraDataStream.cs:113-133）：读到 `__FINISHED`；只解析前 [limit] 项。
         *
         * 与原版逐行同构：`i >= array.Length` 时 `continue` **不 TryParse**（`:124-125`），
         * 所以声明长度 0 的系统浮点数组会接受任意值行、只在 FINISHER 停；FINISHER 之后
         * 「剩余格清 0.0」（`:132-133`）在移植侧是零格（[limit] = 声明长度）。
         */
        fun doubleArray1(limit: Int = 0): DoubleArray {
            val out = DoubleArray(limit.coerceAtLeast(0))
            var index = 0
            while (true) {
                val s = text()
                if (s == FINISHED) return out // 剩余格保持 0.0（`:132-133`）
                if (index >= limit) {
                    index++
                    continue // 超长只消费不解析（`:124-125` 的 continue 在 TryParse 之前）
                }
                out[index] = parseDoubleOrNull(s) ?: throw SaveFormatException("存档数组项不是数字：\"$s\"")
                index++
            }
        }

        /**
         * `double.TryParse`（当前文化）的等价物，覆盖存档会出现的写法：
         * 符号 / 小数点 / 指数、前后空白、分组符忽略（.NET 的 `NumberStyles.Float` 默认带
         * AllowThousands）；NaN / ±Infinity 按不变文化写法学接受。
         * 拒绝 JVM 扩展写法（十六进制浮点、`1d` 后缀、`0x1p3`），原版同样判失败。
         */
        fun parseDoubleOrNull(s: String): Double? {
            var t = s.trim()
            if (t.isEmpty()) return null
            when (t) {
                "NaN" -> return Double.NaN
                "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
                "-Infinity" -> return Double.NEGATIVE_INFINITY
            }
            if (groupingSeparator != decimalSeparator) t = t.replace(groupingSeparator.toString(), "")
            if (decimalSeparator != '.') t = t.replace(decimalSeparator, '.')
            if (!DOUBLE_PATTERN.matches(t)) return null
            return t.toDoubleOrNull()
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

        /**
         * `ReadDoubleExtended`（fork EraDataStream.cs:257-282）：`key:G` 一行一项，
         * 撞 `__FINISHED` 是「存档形式不正确」、撞分隔符收尾；**没有** `emu_version` 门槛。
         * 值行的文化口径同 [double]（`double.TryParse`，:277）。
         */
        fun doubleExtended(): Map<String, Double> {
            val m = LinkedHashMap<String, Double>()
            while (true) {
                val s = text()
                if (s == FINISHED) throw SaveFormatException("扩展段里出现了 $FINISHED（存档形式不正确）")
                if (s == SEPARATOR) return m
                val idx = s.indexOf(':')
                if (idx < 0) throw SaveFormatException("扩展行的键值格式不正确：\"$s\"")
                val v = parseDoubleOrNull(s.substring(idx + 1))
                    ?: throw SaveFormatException("扩展项 ${s.substring(0, idx)} 的值不是数字")
                m.putIfAbsent(s.substring(0, idx), v)
            }
        }

        /** `ReadDoubleArrayExtended`（:499-532）：键行 + 值行到 `__FINISHED`；无版本门槛。 */
        fun doubleArrayExtended(): Map<String, List<Double>> {
            val m = LinkedHashMap<String, List<Double>>()
            while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val list = ArrayList<Double>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) break
                    list.add(parseDoubleOrNull(s) ?: throw SaveFormatException("扩展数组项不是数字：\"$s\""))
                }
                m.putIfAbsent(key, list)
            }
        }

        /** `ReadDoubleArray2DExtended`（:534-577）：一行一行的 CSV，空行 = 整行零；门槛 1708 由 [consumedAt] 管。 */
        fun doubleArray2DExtended(): Map<String, List<DoubleArray>> {
            val m = LinkedHashMap<String, List<DoubleArray>>()
            while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val rows = ArrayList<DoubleArray>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) break
                    rows.add(if (s.isEmpty()) DoubleArray(0) else parseCsvDoubles(s))
                }
                m.putIfAbsent(key, rows)
            }
        }

        /** `ReadDoubleArray3DExtended`（:579-633）：含 `{` 的行开一个平面、`}` 收尾；门槛 1729 同上。 */
        fun doubleArray3DExtended(): Map<String, List<List<DoubleArray>>> {
            val m = LinkedHashMap<String, List<List<DoubleArray>>>()
            outer@ while (true) {
                val key = text()
                if (key == FINISHED) throw SaveFormatException("扩展段的数组键缺失（存档形式不正确）")
                if (key == SEPARATOR) return m
                val planes = ArrayList<List<DoubleArray>>()
                while (true) {
                    val s = text()
                    if (s == SEPARATOR) throw SaveFormatException("数组项里出现了 $SEPARATOR（存档形式不正确）")
                    if (s == FINISHED) {
                        m.putIfAbsent(key, planes)
                        continue@outer
                    }
                    if (!s.contains('{')) continue
                    val rows = ArrayList<DoubleArray>()
                    while (true) {
                        val row = text()
                        if (row == "}") break
                        rows.add(if (row.isEmpty()) DoubleArray(0) else parseCsvDoubles(row))
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

        /** 浮点行的 CSV（fork 的 `double.TryParse(tokens[x])`，EraDataStream.cs:569-571 / :623-624）。 */
        private fun parseCsvDoubles(s: String): DoubleArray {
            val parts = s.split(',')
            return DoubleArray(parts.size) { i ->
                parseDoubleOrNull(parts[i]) ?: throw SaveFormatException("扩展数组项不是数字：\"${parts[i]}\"")
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

/** 存档里的浮点行写法（见 [SaveTextFormat] 内部 `Cursor.parseDoubleOrNull`）。 */
private val DOUBLE_PATTERN = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")

/** 当前文化的小数点字符（`double.TryParse` / `ToString("G")` 都不带 InvariantCulture）。 */
private fun decimalSeparatorOf(locale: Locale): Char =
    DecimalFormatSymbols.getInstance(locale).decimalSeparator

/** 当前文化的分组符；原版 `NumberStyles.Float` 默认带 AllowThousands，解析时忽略它。 */
private fun groupingSeparatorOf(locale: Locale): Char =
    DecimalFormatSymbols.getInstance(locale).groupingSeparator

/**
 * .NET 8 `double.ToString("G")` 的复刻（写侧证据：`EraDataStream.cs:686-690` 逐字取
 * `value.ToString("G")`；期望值由本机 dotnet 8.0.425 探针取得并钉在 SaveTextFloatTailTest）。
 *
 * - 最短可回读数字（.NET Core 3.0+ 的 R 与 G 同源）；整数不带 `.0`（`0.0` 写成 `0`、
 *   `-0.0` 写成 `-0`）；
 * - 定点/科学计数阈：十进制指数 -4..16 用定点，其余写 `d[.ddd]E±NN`（指数至少两位）；
 * - 小数点跟随当前文化（de-DE 出 `1,5`），指数段不本地化；NaN 与 ±∞ 用不变文化写法。
 *
 * 与 .NET 的已知差异：`∞` 在 zh/ja 文化下 .NET 出 `∞`，这里恒出 `Infinity`（存档不会写入
 * 非有限值，TODO(verify)）；tie-break 类极端 double 的最短位形可能与 .NET 的 Ryu 差一位
 * —— 实测 `9.999999999999999e16` 的 double，.NET 出 `99999999999999980`、JDK 出
 * `99999999999999984`（两者都 17 位、都往返到同一 double），`5E-324` 亦有同类差异。
 * 存档里的浮点来自游戏数值，不涉及这些边界；要逐字节对齐 PC 文本档需移植 Ryu 的
 * tie-break 规则（TODO(verify)）。
 */
private fun formatDoubleG(v: Double, decimalSeparator: Char): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    if (v == 0.0) return if (java.lang.Double.doubleToRawLongBits(v) != 0L) "-0" else "0"
    val negative = v < 0
    val bd = BigDecimal.valueOf(if (negative) -v else v).stripTrailingZeros()
    val digits = bd.unscaledValue().abs().toString()
    val exp = bd.precision() - bd.scale() - 1
    val body = if (exp in -4..16) fixedPoint(digits, exp) else scientific(digits, exp)
    val localized = if (decimalSeparator == '.') body else body.replace('.', decimalSeparator)
    return if (negative) "-$localized" else localized
}

/** `-4 <= exp <= 16` 的定点写法（`digits` 是去掉尾零的有效数字串）。 */
private fun fixedPoint(digits: String, exp: Int): String {
    if (exp >= 0) {
        val intLen = exp + 1
        return if (digits.length <= intLen) {
            digits + "0".repeat(intLen - digits.length)
        } else {
            digits.substring(0, intLen) + "." + digits.substring(intLen)
        }
    }
    return "0." + "0".repeat(-exp - 1) + digits
}

/** 科学计数写法：`d[.ddd]E±NN`（指数至少两位，同 .NET 的 `E-05` / `E+308`）。 */
private fun scientific(digits: String, exp: Int): String {
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    val sign = if (exp >= 0) "+" else "-"
    val e = kotlin.math.abs(exp).toString().padStart(2, '0')
    return "${mantissa}E$sign$e"
}
