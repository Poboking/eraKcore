package com.eratw.port.core.save

import java.nio.charset.Charset
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
 */
data class SaveConfig(
    val systemSaveInBinary: Boolean = false,
    val zipSaveData: Boolean = false,
    val useSaveFolder: Boolean = false,
    val datDir: String = "dat",
    val newline: String = "\n",
    val charset: Charset = Charsets.UTF_8,
    val writeBom: Boolean = true,
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
