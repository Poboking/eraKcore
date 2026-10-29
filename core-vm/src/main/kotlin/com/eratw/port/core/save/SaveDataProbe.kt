package com.eratw.port.core.save

import com.eratw.port.core.data.save.GameBaseGate
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveHeader
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveDataCheck
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.GameBaseData
import java.util.Locale

/**
 * 存档槽头探测（原版 `VEvaluator.CheckData` → `CheckDataByFilename` 的移植口径）。
 *
 * ## SavDir 的唯一口径（断言级；M4-L savefolder 逐行核对）
 *
 * 原版 `SavDir` 只在 `Config.SetConfig`（`Config.cs:296` 起的方法体）里被赋值，赋值方向由
 * `UseSaveFolder` 的**互斥三元分支**钉死，读取侧没有任何「另一条目录也试一次」的回退：
 * - `Config.cs:147` 字段声明、`Config.cs:367` 从条目取现值
 *   （`instance.GetConfigValue<bool>(ConfigCode.UseSaveFolder)`；条目注册默认 false =
 *   `ConfigData.cs:97`），二者都在 `SetConfig` 体内 ⇒ 一次配置装载只算一次；
 * - `Config.cs:471-477`：`if (UseSaveFolder) SavDir = Program.ExeDir + "sav" + DirectorySeparatorChar`；
 * - `Config.cs:478-481`：`else SavDir = Program.ExeDir`；两条分支互斥，`SavDir` 恒为单值；
 * - `Config.cs:482-485` + `Config.cs:551-590`：开关打开而 `sav` 目录不存在时，原版把根目录的
 *   `global.sav`（:577）与 `save*.sav`（:579-584）**物理 `File.Move` 进 `sav`** ——
 *   「旧位置的文件」是靠迁移消解的，不是靠双探测读到的。这是「另一侧不算存在」的官方实现方式；
 * - `VariableEvaluator.cs:2018-2037` 拼路径：`getSaveDataPathG()` = `SavDir + "global.sav"`
 *   （:2020，**不吃下标**）、`getSaveDataPath(int)` = `SavDir + "save" + index:00 + ".sav"`
 *   （:2027-2031）、`getSaveDataPath(string)`（:2036）同一条 `SavDir`；
 * - 变量档/角色档拼的是 `Program.DatDir`（`VariableEvaluator.cs:2039-2057`；目录定义
 *   `Program.cs:216-218` = `ExeDir\dat\`），**与 `UseSaveFolder` 无关**
 *   ⇒ [candidatePaths] 对 VAR/CHARVAR 恒为单候选；
 * - `VariableEvaluator.cs:2138-2157`（`CheckData`）按类型只取上面**一条**路径，交给
 *   `CheckDataByFilename`（:2159）；后者对这一个 filename 只做一次 `File.Exists`（:2162）；
 * - 写/读/删同一条路径：`SaveTo`（:2644-2646）、`LoadFrom`（:2687-2693）、`DelData`（:2710-2713）；
 * - 语句调用点：`SAVEDATA` = `Process.cs:1327-1349`（:1344 进 `SaveTo`）、
 *   `LOADDATA` = `Process.cs:1841-1863`（:1852 先 `CheckData(..., Normal)`、:1856 再 `LoadFrom`）。
 *
 * ⇒ **结论（断言级）**：`UseSaveFolder == true` ⇒ 存档槽只可能位于 `ExeDir\sav\`；
 * `false` ⇒ 只可能位于 `ExeDir`。任何「两个目录都探」的读法都是移植侧自造，原版没有对应物。
 * 唯一的近邻例外是 `Config.ForceSavDir`（`Config.cs:470` 恒为 `ExeDir\sav\`，不受开关影响），
 * 它只服务 `SAVETEXT`/`LOADTEXT` 的 force 形参（`FunctionMethodCreator.cs:7978`、`:8061`），
 * 与 `.sav` 存档槽无关，本类不使用它。
 *
 * ## 逐分支对应 `VariableEvaluator.cs:2159-2246`
 *
 * - 不存在 → `FILENOTFOUND` + `"----"`（:2162-2166）；
 * - 二进制头类别与查询类别不符 → `ETC_ERROR` + CorruptedSaveData（:2196-2201）；
 * - UniqueCode 不符 → `GAME_ERROR` + DifferentGame（:2203-2207，判定本体 `GameBase.cs:60-67`，
 *   存档码 0 豁免）；
 * - 版本不符 → `VIRSION_ERROR` + DifferentVersion（:2209-2214，判定本体 `GameBase.cs:69-80`）；
 * - 全部通过 → `OK` + 存档描述文本（:2216-2217）。
 *
 * 文本格式存档（二进制魔数不符时原版回落 `EraDataReader` 逐行读，:2176-2194）：
 * **原版文本分支不检查存档类别**（ReadFileType 只有二进制容器里才有），
 * 故 [check] 对 `SaveHeader.binary == false` 的头跳过类别比对。
 *
 * ## 口径的**唯一取值点**（接线现状）
 *
 * 条目现值由 `ConfigMethods.useSaveFolder(store)`（`core/fn/ConfigMethods.kt:208`）交出，
 * 宿主侧交出面是 `RootedBuiltinHost.useSaveFolder`（`core/proc/BootHosts.kt:197`）；
 * 会话装配把同一个值写进 [SaveWiring]（`core/proc/GameSession.kt:233-244`），函数层
 * `CHKDATA` 从同一份接线现读（`core/fn/SaveMethods.kt:54-56`）⇒ 探测层已是单候选口径。
 * 落盘侧同源：`SaveConfig.normalPath` / `globalPath`（`core/save/SaveConfig.kt:38-44`）
 * 走 `if (useSaveFolder) "sav/" else ""` 的**单前缀**，与本类 `useSaveFolder != null` 的候选恒等。
 *
 * 两处旧「残留缺口」均已收口（2026-10-26 fixcore 审查复核更正，勿再报）：
 * ① `RootedBuiltinHost.checkSaveData`（`core/proc/BootHosts.kt:422-439`）已把
 * [useSaveFolder] 现值传入 [candidatePaths]（M4-L 组长接线），宿主存在性检查与函数层
 * 同为单候选；② `SaveFormat.peek` 的 16 字节门已挪进二进制魔数分支
 * （`SaveFormat.kt:176-183`），短文本档经 `peekText` 给 OK，与
 * `VariableEvaluator.cs:2176-2194` 的文本回落一致。
 *
 * 同类差异（状态码同为 `ETC_ERROR`，只有 `DataMes` 文案差）：原版文本分支解析失败时给
 * `Lang.Error.LoadError.Text` 或 `FileEE.Message`（`VariableEvaluator.cs:2219-2228`），
 * GZip 壳坏掉的容器原版也是「回落文本 → 再失败」，本层统一给 [MES_CORRUPTED]。
 *
 * ## `ScriptVersionDefined` 的传位通道（M10-SAVE）
 *
 * 版本判定本体已收进 [com.eratw.port.core.data.save.GameBaseGate]（三条放行逐条对
 * `GameBase.cs:69-80`），本层新增 [check] / [checkBytes] 的 `scriptVersionDefined` 形参，
 * 会话侧由 [SaveWiring.scriptVersionDefined] 交出真值（[com.eratw.port.core.save.FileSystemOps]
 * 的 `LOADDATA` / `LOADGLOBAL` / `LOADCHARA` 已接）。
 * 真值的**源头**已接通（组长接线）：[com.eratw.port.core.vars.GameBaseData.versionDefined]
 * 由 `GameBaseLoader` 按 `GameBase.cs:138-140` 的 `ScriptVersionDefined = tryatoi(…)` 填，
 * [com.eratw.port.core.data.save.GameBaseGate.checkVersion] 按
 * 「调用方交出的位 → `gameBase.versionDefined` → `version != 0` 近似」三级次序取。
 * 因此本层与 `CharaFileIO`/`CHKCHARADATA` 都不必再各自传参 —— 三个读侧入口同解。
 * 近似那一档只在「GameBase.csv 的 `バージョン` 写了 0 且 `バージョン違い認める` > 0」的游戏上偏松
 * （eraTW 的该值是 `proto` ⇒ 两侧同判，实测读数不变）。
 *
 * `UseSaveFolder` 的生效时机（2026-10-23 回 reference 核对，原 `TODO(verify)` 闭环）：
 * `UseSaveFolder` 与 `SavDir` 的唯一重算点都在 `Config.SetConfig` 里（`Config.cs:296` 起，
 * `:367` 读开关、`:471-481` 算 `SavDir`、`:482-484` 顺带触发旧档迁移）；运行期第二个调用点
 * `MainWindow.cs:1667` 只服务于剪贴板菜单项，与存档目录无关。设置页保存后原版走
 * `MainWindow.cs:1066-1069` 的 `SaveReboot ⇒ Reboot()` —— 原版靠**整程序重启**生效，
 * 不是原地重算。所以移植侧「改开关要重启会话」与原版同构；差的那一口是「原版自动重启、
 * 移植侧要用户手动」，属设置页 UX 面（M5-CONFIG 已登记「当前重启生效」），不是本层缺陷。
 *
 * 描述文本口径：三条错误文本逐字取 `Lang.Error.CorruptedSaveData` / `DifferentGame` /
 * `DifferentVersion` 的中文语言包原文（lang/emuera.zhs.xml:328 / :326 / :327；2026-10-23
 * 已对 reference 反编译源核实，替换早前自造的「存档文件已损坏 / 存档的游戏代码不同」）。
 * 同一状态码的另一个消费点 `CHKCHARADATA`（`core/fn/CharaFileMethods.kt`）已改为直接取用
 * 本对象的四个常量（M11-A L8 收口，SAVE-05），两侧不再有第二份措辞。
 *
 * 头解析复用 [SaveFormat.peek]（`core/data/save`，二进制 + 文本两式），不造第二套解析器。
 */
object SaveDataProbe {

    /** `FILENOTFOUND` 分支的描述文本（VariableEvaluator.cs:2165 的 `"----"`）。 */
    const val MES_NOT_FOUND: String = "----"

    /** `ETC_ERROR` 分支描述（`Lang.Error.CorruptedSaveData`，lang/emuera.zhs.xml:328）。 */
    const val MES_CORRUPTED: String = "存档数据已损坏"

    /** `Lang.Error.LoadError.Text`（lang/emuera.zhs.xml:329）——原版读档抛异常时的描述。 */
    const val MES_LOAD_ERROR: String = "加载时发生错误"

    /** `GAME_ERROR` 分支描述（`Lang.Error.DifferentGame`，lang/emuera.zhs.xml:326）。 */
    const val MES_DIFFERENT_GAME: String = "试图读取的存档属于另一个游戏"

    /** `VIRSION_ERROR` 分支描述（`Lang.Error.DifferentVersion`，lang/emuera.zhs.xml:327）。 */
    const val MES_DIFFERENT_VERSION: String = "存档版本不同"

    /**
     * 槽位候选相对路径。**原版恒为单候选**（类 KDoc 的 SavDir 三条分支 + `CheckData`
     * 的 `VariableEvaluator.cs:2138-2156` 只组一条 filename）；多出的第二个候选只在
     * 装配缺失的装置场合保留。
     *
     * VAR/CHARVAR 单候选，目录取 [BuiltinHost.datDir]（`Program.cs:216-218` 的 `DatDir`，
     * 与 `UseSaveFolder` 无关，`VariableEvaluator.cs:2039-2047`）；
     * GLOBAL 不吃下标（`getSaveDataPathG`，`VariableEvaluator.cs:2018-2021`）。
     *
     * NORMAL/GLOBAL 的目录由 [useSaveFolder] 决定，口径就是 `SavDir`（`Config.cs:471-481`）：
     * - `true`  ⇒ 只探 `sav/`（`SavDir = ExeDir + "sav" + sep`，`Config.cs:471-477`）；
     * - `false` ⇒ 只探根目录（`SavDir = Program.ExeDir`，`Config.cs:478-481`）；
     * - `null`  ⇒ **调用方没交出口径**（会话没装 [SaveWiring] 且宿主没有条目表），
     *   保持旧的双候选保守回落 —— 移植侧自造、非原版语义，为的是不新增报红
     *   （纯单测 / erbcc 自检没有装配段）。真语料上两者取值相同：语料根目录没有任何
     *   `.sav`（实测：存档全在 `sav/`，且 `emuera.config:51` 与 `CSV/_default.config:17`
     *   都是 YES ⇒ `SavDir` = `ExeDir\sav\`），所以该回落不会改变开局读数。
     *   已接线：`RootedBuiltinHost.checkSaveData`（`core/proc/BootHosts.kt:429`）现传
     *   `useSaveFolder` 现值（M4-L 组长接线），宿主侧不再走 `null` 双候选分支
     *   （2026-10-26 fixcore 审查复核更正）。
     */
    fun candidatePaths(
        host: BuiltinHost,
        kind: SaveFileKind,
        no: Long,
        useSaveFolder: Boolean? = null,
    ): List<String> {
        val dat = host.datDir.trimEnd('/', '\\')
        return when (kind) {
            SaveFileKind.NORMAL -> saveCandidates(saveName(no), useSaveFolder)
            SaveFileKind.GLOBAL -> saveCandidates("global.sav", useSaveFolder)
            SaveFileKind.VAR -> listOf("$dat/" + String.format(Locale.ROOT, "var_%02d.dat", no))
            SaveFileKind.CHARVAR -> listOf("$dat/" + String.format(Locale.ROOT, "chara_%02d.dat", no))
        }
    }

    /**
     * 普通/全局档的目录候选：`true`/`false` 各只有一个（`Config.cs:471-481` 的互斥分支），
     * `null` 是装配缺失时的保守回落，见 [candidatePaths]。
     */
    private fun saveCandidates(name: String, useSaveFolder: Boolean?): List<String> = when (useSaveFolder) {
        true -> listOf("sav/$name")
        false -> listOf(name)
        null -> listOf(name, "sav/$name")
    }

    /** `getSaveDataPath(int)`（VariableEvaluator.cs:2023-2032）：`save{index:00}.sav`。 */
    fun saveName(no: Long): String = String.format(Locale.ROOT, "save%02d.sav", no)

    /**
     * 完整探测。返回 null 只表示宿主探测能力未接线（无 [BuiltinHost.fileOps]）——
     * 调用方必须按「宿主未接线」抛错，禁止静默回落成 `FILENOTFOUND`（那是伪造读数）。
     * 文件存在但不是可读普通文件（权限、坏盘）按原版 `CheckDataByFilename` 的 catch 分支
     * 返回 `ETC_ERROR`（VariableEvaluator.cs:2219-2228），不再冒充「宿主未接线」。
     * 目录槽位在 `File.Exists` 口径下不算存在（生产宿主 `RootedBuiltinHost.checkSaveData`
     * 用 `isRegularFile` 判存在，先落 `FILENOTFOUND`）。
     *
     * @param useSaveFolder `Config.UseSaveFolder` 的条目现值（唯一取值点 `ConfigMethods.useSaveFolder`，
     *   spec: `Config.cs:147` + `:367` + `:471-481`）。生产路径由 `CHKDATA` 从 [SaveWiring] 现读后传入
     *   （`core/fn/SaveMethods.kt:54-56`）；null = 调用方没交出口径，保持双候选保守回落，
     *   见 [candidatePaths] 的第三条分支。
     * @param scriptVersionDefined `GameBase.ScriptVersionDefined`（`GameBase.cs:25`），
     *   即 GameBase.csv 的 `バージョン` 行**是否存在且能解析出数字**
     *   （`GameBase.cs:138-140`：`ScriptVersionDefined = tryatoi(array[1], out ScriptVersion)`）。
     *   null = 调用方没交出该位，退回 `version == 0` 的近似，见 [checkVersion]。
     */
    fun check(
        host: BuiltinHost,
        kind: SaveFileKind,
        no: Long,
        gameBase: GameBaseData,
        useSaveFolder: Boolean? = null,
        scriptVersionDefined: Boolean? = null,
    ): SaveDataCheck? {
        val ops = host.fileOps ?: return null
        val path = candidatePaths(host, kind, no, useSaveFolder).firstOrNull { ops.exists(it) }
            ?: return SaveDataCheck(EraDataState.FILENOTFOUND, MES_NOT_FOUND) // :2162-2166
        val bytes = host.readFileBytes(path)
            ?: return SaveDataCheck(EraDataState.ETC_ERROR, MES_LOAD_ERROR) // :2219-2228
        return checkBytes(bytes, kind, gameBase, scriptVersionDefined)
    }

    /**
     * 纯字节探测（[check] 的读盘之后半程；单独暴露给「调用方已有文件内容」的场景与单测）。
     * 分支序与 `CheckDataByFilename`（VariableEvaluator.cs:2159-2217）一致。
     */
    fun checkBytes(
        bytes: ByteArray,
        kind: SaveFileKind,
        gameBase: GameBaseData,
        scriptVersionDefined: Boolean? = null,
    ): SaveDataCheck {
        val head: SaveHeader = SaveFormat.peek(bytes)
            ?: return SaveDataCheck(EraDataState.ETC_ERROR, MES_CORRUPTED)
        // 原版只在二进制容器里比类别（:2196-2201）；文本分支（:2176-2194）无类型可比。
        if (head.binary && head.fileType != kind) {
            return SaveDataCheck(EraDataState.ETC_ERROR, MES_CORRUPTED) // :2197-2201
        }
        if (!uniqueCodeMatches(gameBase, head.uniqueCode)) {
            return SaveDataCheck(EraDataState.GAME_ERROR, MES_DIFFERENT_GAME) // :2202-2206
        }
        if (!checkVersion(gameBase, head.scriptVersion, scriptVersionDefined)) {
            return SaveDataCheck(EraDataState.VIRSION_ERROR, MES_DIFFERENT_VERSION) // :2207-2212
        }
        return SaveDataCheck(EraDataState.OK, head.text) // :2213-2215
    }

    /** `GameBase.UniqueCodeEqualTo`（GameBase.cs:60-67）：存档码 0 豁免。 */
    private fun uniqueCodeMatches(gameBase: GameBaseData, saveCode: Long): Boolean =
        GameBaseGate.uniqueCodeEqualTo(gameBase, saveCode)

    /**
     * `GameBase.CheckVersion`（GameBase.cs:69-80）——口径本体在
     * [com.eratw.port.core.data.save.GameBaseGate]（三条放行与 `ScriptVersionDefined`
     * 的近似差异逐条见其类 KDoc），本层只负责把 [scriptVersionDefined] 传下去。
     */
    fun checkVersion(
        gameBase: GameBaseData,
        saveVersion: Long,
        scriptVersionDefined: Boolean? = null,
    ): Boolean = GameBaseGate.checkVersion(gameBase, saveVersion, scriptVersionDefined)
}
