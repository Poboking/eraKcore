package com.eratw.port.core.save

import java.nio.charset.Charset
import java.util.Locale
/**
 * 存档落盘策略（原版 `Config` 里与存档读写相关的四项 + 文本行尾/编码）。
 *
 * 逐项默认值取 `ConfigData` 的出厂值；eratw 实机用的三项在 `emuera.config` 里被改写
 * （`USE SAV FOLDER:YES` / `USE THE BINARY FORMAT FOR SAVING DATA:YES` /
 * `COMPRESS SAVE DATA:YES`），装配层用 [ERATW] 预设即可。
 *
 * 路径口径（spec: VariableEvaluator.cs:2018-2047、Config.cs:470-481）：
 * - `SavDir` = `ExeDir`，`UseSaveFolder` 为真时 = `ExeDir + "sav/"`；
 * - 普通档 = `SavDir + "save{no:00}.sav"`；全局档 = `SavDir + "global.sav"`；
 * - 角色档 / 变量档 = `DatDir + "chara_{name}.dat"` / `"var_{name}.dat"`。
 * 本类只产出**相对**路径，`ExeDir` 由 [SaveFileIo] 的实现注入。
 *
 * @property systemSaveInBinary `SystemSaveInBinary`（ConfigData.cs:107，默认 false）
 * @property zipSaveData        `ZipSaveData`（ConfigData.cs:128，默认 false；仅二进制分支生效，
 *   EraBinaryDataWriter.cs:22-32）
 * @property useSaveFolder      `UseSaveFolder`（ConfigData.cs:97，默认 false）
 * @property datDir             原版 `Program.DatDir` 的相对形（SaveDataProbe 的既有口径 = `dat`）
 * @property newline            文本格式行尾。.NET `StreamWriter.WriteLine` 用 `Environment.NewLine`
 *   （Windows = CRLF）；JVM/Android 侧固定 LF，读侧两种都收（EraDataReader 用 `ReadLine`）。
 * @property charset            文本格式编码。原版 `Config.SaveEncode` 恒为 UTF-8 with BOM
 *   （Config.cs:19、EncodingHandler.cs:13）。
 * @property writeBom           写出时是否带 UTF-8 BOM（原版恒带）
 * @property textExtensionSegments 文本档扩展区的段集（[TextExtensionSegments]）；默认
 *   [TextExtensionSegments.CLASSIC_6] = 4.980 口径，产物字节与既有一致。
 * @property locale             文本档浮点行的**当前文化**（`double.ToString("G")` /
 *   `double.TryParse` 都不带 InvariantCulture，EraDataStream.cs:686-690 / :74-83）。
 *   `null` = 每次调用现取 `Locale.getDefault()`（默认，与加本字段之前逐字等价）。
 *   刻意用「null = 现取」而不是 `= Locale.getDefault()`：[ERATW] / [DEFAULT] 是 companion
 *   里的 `val` 单例，默认值会在**类初始化那一刻**被冻结，而宿主（Android）运行期可以改
 *   默认文化 —— 那种冻结就是行为变化。
 */
data class SaveConfig(
    val systemSaveInBinary: Boolean = false,
    val zipSaveData: Boolean = false,
    val useSaveFolder: Boolean = false,
    val datDir: String = "dat",
    val newline: String = "\n",
    val charset: Charset = Charsets.UTF_8,
    val writeBom: Boolean = true,
    val textExtensionSegments: TextExtensionSegments = TextExtensionSegments.CLASSIC_6,
    val locale: Locale? = null,
) {
    /** `SavDir` 的相对前缀（`UseSaveFolder` 打开时是 `sav/`）。spec: Config.cs:470-481 */
    val saveFolderPrefix: String get() = if (useSaveFolder) "sav/" else ""

    /** `getSaveDataPath(int)`：`{SavDir}save{index:00}.sav`。spec: VariableEvaluator.cs:2023-2032 */
    fun normalPath(no: Int): String = saveFolderPrefix + SaveDataProbe.saveName(no.toLong())

    /** `getSaveDataPathG()`：`{SavDir}global.sav`（不吃下标）。spec: VariableEvaluator.cs:2018-2021 */
    fun globalPath(): String = saveFolderPrefix + "global.sav"

    /**
     * `getSaveDataPathC(string)`：`{DatDir}chara_{name}.dat`。spec: VariableEvaluator.cs:2054-2057
     *
     * [name] 的合法性由调用方先过 [DatFileName.check]（原版靠 `new FileStream` 兜底，
     * 见该对象的 KDoc）；本方法保持纯拼接，供「存在性探测」这类**不该抛**的路径复用。
     */
    fun charaPath(name: String): String = "${datDir.trimEnd('/', '\\')}/chara_$name.dat"

    /** `getSaveDataPathV(string)`：`{DatDir}var_{name}.dat`。spec: VariableEvaluator.cs:2049-2052 */
    fun varPath(name: String): String = "${datDir.trimEnd('/', '\\')}/var_$name.dat"

    companion object {
        /**
         * eratw 实机口径（`eratw-画蛇添足版eratw4.980/emuera.config` 的 51/60/74 行）：
         * `USE SAV FOLDER:YES` + `USE THE BINARY FORMAT FOR SAVING DATA:YES` + `COMPRESS SAVE DATA:YES`。
         */
        val ERATW: SaveConfig = SaveConfig(
            systemSaveInBinary = true,
            zipSaveData = true,
            useSaveFolder = true,
        )

        /** 出厂默认（ConfigData）：文本格式、不压缩、存档放 ExeDir 根。 */
        val DEFAULT: SaveConfig = SaveConfig()
    }
}

/**
 * 文本档**扩展区**的段集口径（`SaveTextFormat` 的角色扩展段数与用户扩展段数）。
 *
 * ## 为什么需要这个开关，而不是从盘上判出来
 *
 * 4.980 与 fork 4.981 的扩展区标记行**是同一个字面量** `__EMUERA_1808_STRAT__`
 * （写侧 `EraDataWriter.EMU_START = EraDataReader.EMU_1808_START`，fork-src
 * EraDataStream.cs:674 / :49；读侧赋 `emu_version = 1808`，:198-200）⇒ 标记行给不出目标版本。
 *
 * 曾考虑用「经典浮点尾段在不在」当信号（4.981 在经典正文后多写 `dataFloat` 2 行 +
 * 7 个 `dataFloatArray`，fork-src VariableData.cs:766-769，4.980 没有），**判据不成立**：
 * 移植侧为了读 PC 的 4.981 档，自浮点尾段那一路起就**无条件**写这段尾段
 * （见 `SaveTextFormat.writeClassicFloatTail` 与 `SaveFormatAuditTest` 里
 * 「带尾段 + 6 段扩展」的夹具），
 * 于是「有尾段」同时出现在 4.980 段集与 4.981 段集的档里 —— 它有区分力的方向是
 * 「经典正文怎么读」，不是「扩展区有几段」。全局档更没有尾段可读
 * （`SaveGlobalToStream` 只写 GLOBAL / GLOBALS 两个数组，fork-src VariableData.cs:1060-1066），
 * 盘上没有任何区分点。
 *
 * 因此本开关由装配层显式声明，**读写同一处取值**：
 * - 写：`CLASSIC_6` = 现有产物字节（不破坏既有档，也不需要 ADR）；`FLOAT_9` 才写 9 段。
 * - 读：`CLASSIC_6` = 现有读法；读 PC 的 4.981 文本档（带角色或 `#DIMF SAVEDATA`）
 *   必须声明 `FLOAT_9`。声明错的后果不是静默错位就是抛
 *   [com.eratw.port.core.data.save.SaveFormatException]（段内容被当成下一段的键行、
 *   或 `__EMU_SEPARATOR__` 落进数组值里），不会静默吞掉。
 *
 * 两个段集的确切差异（**系统 8 段两边都不变**，fork 没给系统段加浮点：
 * `VariableData.SaveToStreamExtended` 仍是 8 个 `GetExtSaveList` 组 + 9 桶用户变量，
 * fork-src VariableData.cs:798-877）：
 *
 * | 区域 | CLASSIC_6（4.980） | FLOAT_9（fork 4.981） |
 * |---|---|---|
 * | 角色扩展 | STR0 / INT0 / STR1 / INT1 / STR2 / INT2（`CharacterData.cs:391-423`） | STR0 / INT0 / **FLT0** / STR1 / INT1 / **FLT1** / STR2 / INT2 / **FLT2**（`CharacterData.cs:437-503`） |
 * | 用户扩展（普通档） | STR1 INT1 STR2 INT2 STR3 INT3（`VariableData.cs:3598-3625`，`userDefinedSaveVarList` 长度 6） | 同上但按 `(Dimension-1)*3 + kindIdx` 重排，浮点落桶 2/5/8（`VariableData.cs:858-876`，列表长度 :81） |
 * | 用户扩展（全局档） | 6 段（`VariableData.cs:3818`） | 9 段（`VariableData.cs:1080-1101`） |
 *
 * 用户桶号 → 形状的换算在 fork 的声明期：`type = (ret.Dimension - 1) * 3` 再加
 * `IsString ? 0 : IsInteger ? 1 : 2`（fork-src VariableData.cs:499-505）；
 * 4.980 是 `Dimension * 2 - 2 + (IsString ? 0 : 1)`（reference VariableData.cs:3275-3278）。
 *
 * @property CLASSIC_6 4.980 / 1.824 的 6 段扩展区（默认，保持既有产物字节）
 * @property FLOAT_9   fork 4.981 的 9 段扩展区（每类型后插浮点段）
 */
enum class TextExtensionSegments { CLASSIC_6, FLOAT_9 }

/**
 * 角色档 / 变量档的文件名清洗（`VariableEvaluator.CheckDatFilename`，VariableEvaluator.cs:2104-2116）。
 *
 * 原版**丢弃**了它的返回值（`SaveChara` :2251、`SaveVariable` :2335），真正拦住非法名的是
 * 紧随其后的 `new FileStream(...)`：Windows 上含 `Path.GetInvalidFileNameChars()`
 * 集合（控制字符 + `" * / : < > ? \ |`，:2110）的路径会抛异常。
 * 移植侧的落盘实现 [RootedSaveFileIo.write] 会替父目录**创建子目录**
 * （SaveFileIo.kt:67），于是 `SAVECHARA "A/B", ...` 在 Android 上会静默写出
 * `dat/chara_A/B.dat`，而 PC 侧必然失败 —— 兼容性要求「PC 写不成的名字，Android 同样写不成」，
 * 故落盘前按同一字符集合拒绝，异常按既有口径落 [com.eratw.port.core.err.CodeEE]。
 *
 * 三处**不拒绝**（逐条对过原版，不是遗漏）：
 * 1. **空名**：`chara_` + `""` + `.dat` = `chara_.dat` 是合法路径，原版 `CheckDatFilename`
 *    即便返回 `NothingFileName` 也被丢弃，`FileStream` 照写成功；
 * 2. `..`：拼成 `chara_...dat`，不含分隔符因而无法越界，原版同样照写；
 * 3. 读侧（`LOADCHARA`）：原版只做 `File.Exists`（:2289-2292），对非法路径返回 false 而**不抛**
 *    ⇒ `RESULT` 保持 0，见 [FileSystemOps.loadChara]。
 */
object DatFileName {

    /** `Lang.Error.InvalidFileName`（lang/emuera.zhs.xml:325，对应 Lang.cs:1482）。 */
    const val MES_INVALID: String = "文件名中含有非法字符"

    /** `Path.GetInvalidFileNameChars()` 里的非控制字符；控制字符另按码位 `< 0x20` 判。 */
    private const val INVALID_NON_CONTROL = "\"*/:<>?\\|"

    /** null = 可落盘；否则是 `Lang.Error.InvalidFileName` 的文案。 */
    fun check(name: String): String? =
        if (name.any { it.code < 0x20 || it in INVALID_NON_CONTROL }) MES_INVALID else null
}
