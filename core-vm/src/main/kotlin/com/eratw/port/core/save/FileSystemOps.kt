package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.vars.VariableStore

/**
 * `SystemOps` 的真文件实现：`SAVEGLOBAL` / `LOADGLOBAL` / `SAVEDATA` / `LOADDATA` / `DELDATA`
 * 以及角色档（`SAVECHARA` / `LOADCHARA`）。
 *
 * 落盘格式与文件名全部走 [SaveConfig] + [SaveCodec]（二进制容器 / 文本格式两态），
 * IO 走 [SaveFileIo]（生产 = [RootedSaveFileIo]，测试 = 内存假体）。
 *
 * ## 分支语义（逐条照原版）
 * - `SAVEDATA`：`SaveTo` 里**吞掉一切异常返回 false**（VariableEvaluator.cs:2644-2685），
 *   语句层据此打印 `UnexpectedErrorInSavedata`（Process.cs:1343-1347，sem 已接）。
 * - `LOADDATA`：先 `CheckData(...).State != 0` → `CodeEE(LoadCorruptedData)`
 *   （Process.cs:1853-1856）；`CheckData` 的四个分支见 [SaveDataProbe]。
 *   装载前 `SetDefaultValue` + `SetDefaultLocalValue`（VariableEvaluator.cs:2625-2626 / :2440-2441），
 *   结束后 `LASTLOAD_NO = dataIndex`（:2706），最后 `ClearFunctionList();
 *   SystemState = LoadData_DataLoaded`（Process.cs:1860-1861，交给 [SaveLoadHandoff]）。
 * - `SAVEGLOBAL`：任何 `SystemException` → `CodeEE(ErrorSavingGlobalData)`（:2506-2509）；
 *   C# 的 try 从建目录/开文件就开始了，抓取（`SaveGlobalToStream`）同样在 catch 范围内。
 * - `LOADGLOBAL`：文件不存在 / 类别不符 / 游戏码不符 / 版本不符 / 任何异常 → **false**，
 *   不抛（:2513-2589）；`RESULT` 由语句层写（FunctionIdentifier.cs:1825-1833，sem 已接）。
 *   读前 `RemoveEMGlobalData()`（:2523）。**不碰 LASTLOAD_***（全局装载没有那两行赋值）。
 * - `DELDATA`：文件不存在 → no-op；只读 → `CodeEE(DelReadOnlyFile)`（:2710-2721）。
 * - `SAVECHARA` / `LOADCHARA`：角色档**恒为二进制**（SaveChara 只有 `EraBinaryDataWriter` 分支，
 *   :2248-2283；LoadChara 走 `CreateReader`，文本档读到 null 直接返回，:2298-2303），
 *   但**是否 GZip** 仍由 writer 的构造条件 `SystemSaveInBinary && ZipSaveData` 决定
 *   （EraBinaryDataWriter.cs:22-32）：文本配置下是未压缩二进制（`MAGIC_PLAIN`），
 *   故写侧压缩标志走 `compressed()` 而不是常量。
 *   SaveChara **不 catch**（:2252-2283）：写盘失败必须冒错，不能静默成功。
 *   `LOADCHARA` 的 `RESULT = 0/1` 由**本实现**写（:2288 / :2316），接口签名保持不变。
 *
 * ## Map / Xml / DT 扩展段（`emData`）
 *
 * 写：`SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary`（VariableData.cs:3911-3959）
 * 经 [SaveCapture] → `core/fn/EmDataPools.snapshot`；读：`LoadVariableBinary` 的
 * Map/Xml/DT 三支（:4095-4124）经 [EmDataPools.restore]，接受集 = `Save* ∪ Global*`。
 * 名字表来自 [EmSaveSets]（`VarExt*.csv`）；未装配 = 空表 = 不写不读
 * （`ConstantData.cs:1311-1316` 的六表清空形态）。
 *
 * ## 构造期的一次性旧档迁移（SAVE-01）
 *
 * 原版在装载配置时（`Config.SetConfig` 尾，`Config.cs:482-485`）判「`UseSaveFolder` 为真且
 * `sav` 目录还不存在」，就把根目录的 `global.sav` 与 `save` 开头的 sav 档 `File.Move` 进
 * `sav`（`Config.cs:551-590`）。移植侧的等价时机 = 存档层装配（`GameSession` 第 8 步建本类，
 * `core/proc/GameSession.kt:402`），实现见 [SaveFolderMigration]；`UseSaveFolder` 关时
 * 本类**一个字节都不碰**（`Config.cs:478-481` 的 else 分支不建目录）。
 * 结局同时挂进 [SaveFolderMigrationLog]（`store.aux`），宿主装配后可读。
 */
class FileSystemOps(
    private val store: VariableStore,
    private val io: SaveFileIo,
    private val config: SaveConfig = SaveConfig.DEFAULT,
    /** 扩展段名字表；缺省从 [SaveWiring] 现读（`GameSession` 装配），未装配 = 空表。 */
    private val emSets: EmSaveSets = SaveWiring.of(store)?.emSets ?: EmSaveSets.EMPTY,
    /**
     * 原版 `Dialog.ShowPrompt(数据移动, 已创建sav文件夹…移动吗？)`（`Config.cs:564`）的应答通道；
     * 缺省 [SaveFolderMigration.AUTO_ACCEPT]（core 层无法弹框，玩家打开开关本身即「是」）。
     * 宿主要把真弹窗接上时在 `GameSession` 的构造点传入 ⇒ 交付报告「需组长接线」。
     */
    private val saveMigrationPrompt: (title: String, message: String) -> Boolean =
        SaveFolderMigration.AUTO_ACCEPT,
) : SystemOps {

    /**
     * 构造期旧档迁移的结局（SAVE-01）。口径与出口逐条见 [SaveFolderMigration]：
     * 只有 `io` 带目录面（生产 = [RootedSaveFileIo]）才可能真搬，其余假体回
     * [SaveMigrationOutcome.IoNotFolderCapable] 且不碰磁盘。
     */
    val saveFolderMigration: SaveFolderMigrationResult

    init {
        val result = SaveFolderMigration.migrate(io, config, saveMigrationPrompt)
        SaveFolderMigrationLog.install(store).add(result)
        saveFolderMigration = result
    }

    /**
     * 存档里的这个变量名当前游戏是否还声明着 —— 与 [com.eratw.port.core.data.save.SaveData]
     * 的 `applyEntry` 同一查找口径（系统/编译期注册表 → 静态用户变量，含 `CHARADATA`）。
     *
     * 交给 [SaveCodec.read] 只用于「**不存在的变量不建数组**」：旧版本存档里已删除的大数组
     * （4.956 的 `MOE_RUMIA_COM_RECORD`：162 角色 × 10 万项）只走标签、不进 [LongArray] /
     * [Array] 分配，也不会把读档的累计分配预算顶穿（2026-10-29 玩家档 save39.sav）。
     * 判定偏保守没有副作用：判「在」而实际不存在的键，仍由 `applyEntry` 丢弃。
     */
    private fun isKnownSaveKey(key: String): Boolean =
        store.registry.def(key) != null || store.userVar(key, null) != null

    // ------------------------------------------------------------------
    // SAVEDATA / LOADDATA / DELDATA
    // ------------------------------------------------------------------

    /** [SystemOps.saveData]：false = 原版 `SaveTo` 的失败返回（语句层打印错误行）。 */
    override fun saveData(no: Int, text: String): Boolean = try {
        val data = SaveCapture.normal(store, text, compressed = compressed(), emData = emSets.save)
        val bytes = SaveCodec.write(data, store.registry, config)
        io.write(config.normalPath(no), bytes)
    } catch (_: Throwable) {
        // 原版 catch (Exception) { return false; }（VariableEvaluator.cs:2666-2669）
        false
    }

    /**
     * [SystemOps.loadData]：成功即把整帧写回变量池；失败按原版抛 [CodeEE]。
     *
     * @throws CodeEE 文件不存在 / 头探测非 OK（`LoadCorruptedData`），或装载中途异常
     */
    override fun loadData(no: Int) {
        val path = config.normalPath(no)
        val bytes = (if (io.exists(path)) io.read(path) else null)
            ?: throw CodeEE(MES_LOAD_CORRUPTED)
        val probe = SaveDataProbe.checkBytes(
            bytes, SaveFileKind.NORMAL, store.registry.gameBase, scriptVersionDefined(),
        )
        if (probe.state != EraDataState.OK) throw CodeEE(MES_LOAD_CORRUPTED)
        // RemoveEMSaveData()（VariableEvaluator.cs:2696）：开档后、装载前先清本档参与的扩展段
        EmDataPools.clear(store, emSets.save)
        // SetDefaultValue + SetDefaultLocalValue（VariableEvaluator.cs:2625-2626）在**解析前**：
        // 原版 LoadFromStreamBinary 先复位再逐块解析（:2625-2637），坏档中途 FileEE 时变量池
        // 已是默认值；此前复位在 SaveCodec.read 之后，TRYC 接住后池里残留旧局读数
        // （2026-10-26 fixcore 审查 save-B1）。SaveCodec.read 只查 registry、不写池，前移安全。
        store.resetValues()
        store.resetLocalValues()
        val data = try {
            SaveCodec.read(bytes, store.registry, SaveFileKind.NORMAL, ::isKnownSaveKey)
        } catch (_: Exception) {
            throw CodeEE(MES_LOAD_CORRUPTED)
        }
        try {
            data.applyTo(store, restoreCharacters = true, setLastLoad = true)
            // LoadVariableBinary 的第二段（EM 条目，:4095-4124）；接受集 = Save* ∪ Global*。
            EmDataPools.restore(store, data.emData, emSets.loadAccept)
        } catch (_: Throwable) {
            throw CodeEE(MES_LOAD_UNEXPECTED)
        }
        store.lastLoadNo = no.toLong() // :2706
        // Process.cs:1860-1861 —— 先清函数栈、后置 LoadData_DataLoaded（顺序照原版）
        SaveLoadHandoff.requestDataLoaded(store)
    }

    /** [SystemOps.delData]。spec: VariableEvaluator.cs:2710-2721 */
    override fun delData(no: Int) {
        val path = config.normalPath(no)
        if (!io.exists(path)) return
        if (io.isReadOnly(path)) throw CodeEE(MES_DEL_READONLY.replace("{0}", path))
        // 原版对 File.Delete 的 IO 异常不 catch（会冒泡）；这里只能收敛到同一句 Lang 文案。
        if (!io.delete(path)) throw CodeEE(MES_DEL_READONLY.replace("{0}", path))
    }

    // ------------------------------------------------------------------
    // SAVEGLOBAL / LOADGLOBAL
    // ------------------------------------------------------------------

    /** [SystemOps.saveGlobal]。spec: VariableEvaluator.cs:2474-2511 */
    override fun saveGlobal() {
        val bytes = try {
            // C# 的 try 从 `CreateSavDir` / `FileStream` 就开始，`SaveGlobalToStream`（抓取）
            // 也在其中，统一由 `catch (SystemException)` 收敛到 ErrorSavingGlobalData
            // （VariableEvaluator.cs:2476-2509）⇒ 采集异常同样必须落成同一句文案。
            val data = SaveCapture.global(store, compressed = compressed(), emData = emSets.global)
            SaveCodec.write(data, store.registry, config)
        } catch (_: Throwable) {
            throw CodeEE(MES_SAVE_GLOBAL)
        }
        if (!io.write(config.globalPath(), bytes)) throw CodeEE(MES_SAVE_GLOBAL)
    }

    /** [SystemOps.loadGlobal]：true = RESULT 1。spec: VariableEvaluator.cs:2513-2589 */
    override fun loadGlobal(): Boolean {
        val path = config.globalPath()
        if (!io.exists(path)) return false
        // RemoveEMGlobalData()（:2523）：原版在打开文件**之前**清（文件不存在时不清）
        EmDataPools.clear(store, emSets.global)
        val bytes = io.read(path) ?: return false
        val probe = SaveDataProbe.checkBytes(
            bytes, SaveFileKind.GLOBAL, store.registry.gameBase, scriptVersionDefined(),
        )
        if (probe.state != EraDataState.OK) return false
        val data = try {
            SaveCodec.read(bytes, store.registry, SaveFileKind.GLOBAL, ::isKnownSaveKey)
        } catch (_: Throwable) {
            return false
        }
        if (data.fileType != SaveFileKind.GLOBAL) return false
        return try {
            data.applyTo(store, restoreCharacters = false, setLastLoad = false)
            // LoadFromStreamBinary 的第二段（EM 条目，:2547 → :4095-4124）
            EmDataPools.restore(store, data.emData, emSets.loadAccept)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // SAVECHARA / LOADCHARA（角色档恒二进制）
    // ------------------------------------------------------------------

    /**
     * [SystemOps.saveChara]。spec: VariableEvaluator.cs:2248-2283
     *
     * 文件名口径 = `{DatDir}chara_{filename}.dat`（:2054-2057）；原版 `CheckDatFilename`
     * 的返回值被丢弃（:2251），故 `SAVECHARA "A.dat"` 会得到 `chara_A.dat.dat`（照抄）。
     *
     * 两条失败路径按原版「抛异常 → 语句层打印错误行」收口（`Process.cs:571-592` 的
     * `handleExceptionInSystemProc`，CodeEE 分支 :574-578，调用点在 :462-476）：
     * - 文件名含 `Path.GetInvalidFileNameChars()` 里的字符（含分隔符）→ [CodeEE]；
     *   PC 侧是 `new FileStream` 抛异常（:2258），Android 侧若不拦会**新建子目录**后静默成功。
     * - 落盘返回 false（磁盘满 / 不可写 / 路径被拒）→ [CodeEE]；原版同样是 `FileStream`
     *   写入失败抛异常，`SaveChara` 只包 `try/finally`、不 catch（:2255-2282）。
     *
     * @throws CodeEE 文件名非法，或写盘失败
     */
    override fun saveChara(nos: List<Long>, filename: String, text: String) {
        // 角色档**恒为二进制容器**，但「是否 GZip」仍由 EraBinaryDataWriter 的构造条件决定：
        // `Config.SystemSaveInBinary && Config.ZipSaveData`（EraBinaryDataWriter.cs:22-32），
        // SaveChara 用的就是这个 writer（:2259）。文本配置下原版写出的是**未压缩二进制**
        // （MAGIC_PLAIN），不是 GZip 壳 —— 故不能硬编码 compressed = true。
        DatFileName.check(filename)?.let { throw CodeEE(it) }
        val bytes = CharaFileIO.encode(store, nos, text, compressed = compressed())
        val path = config.charaPath(filename)
        // 原版 SaveChara 只有 try/finally、**不 catch**（VariableEvaluator.cs:2252-2283）：
        // 写盘失败会以 SystemException 冒泡，绝不能被当成成功。本层 [SaveFileIo.write]
        // 把失败压成 false，故在这里还原成可诊断错误（带目标路径）。
        if (!io.write(path, bytes)) throw CodeEE(MES_SAVE_CHARA.replace("{0}", path))
    }

    /**
     * [SystemOps.loadChara]。spec: VariableEvaluator.cs:2285-2330
     *
     * `RESULT` 由**本实现**写（原版 `VariableEvaluator.LoadChara` 内写，:2288 / :2316）：
     * - 入口先 `RESULT = 0`（:2288），文件不存在直接返回 ⇒ 保持 0（:2289-2292）；
     * - 类别 / 游戏码 / 版本不符 ⇒ 保持 0（:2300-2305）；
     * - 追加角色成功 ⇒ `RESULT = 1`（:2315-2316）。
     *
     * 走「实现写 store」而不是把 [SystemOps.loadChara] 的返回类型改成 Boolean：
     * 语句层（`StatementExecutor.LOADCHARA`）零改动，既有假体（测试 / 装置的 `SystemOps`）
     * 也不会因为签名变化而返工。
     *
     * 「不存在」与「读失败」分开（原版两种结局不同）：
     * - 不存在：`File.Exists` 为假 ⇒ 静默返回、`RESULT = 0`（:2289-2292）。非法文件名
     *   （含分隔符 / 控制字符）在 `File.Exists` 上同样只是 false，故这里也静默 ——
     *   `LOADCHARA` 原版**不调用** `CheckDatFilename`，不能凭空抛错。
     * - 存在但读不出来：原版 `new FileStream` 的 IO 异常会冒泡（`LoadChara` 只包
     *   `try/finally`，:2295-2329）⇒ 这里抛 [CodeEE]，不静默当空档。
     *
     * @throws CodeEE 文件存在但读取失败
     */
    override fun loadChara(filename: String) {
        store.writeInt(RESULT_VAR, longArrayOf(0L), 0L) // :2288
        val path = config.charaPath(filename)
        if (!io.exists(path)) return // :2289-2292
        val bytes = io.read(path) ?: throw CodeEE(MES_LOAD_UNEXPECTED)
        // 原版 LoadChara 只包 try/finally、**不 catch**（VariableEvaluator.cs:2295-2329）：
        // 损坏档的 FileEE 会冒泡。这里同口径，只由 CharaFileIO 把「类别/游戏码/版本不符」
        // 落成 false（RESULT 保持 0 的那三个分支）。
        if (CharaFileIO.load(bytes, store, scriptVersionDefined())) {
            store.writeInt(RESULT_VAR, longArrayOf(0L), 1L) // :2316
        }
    }

    // ------------------------------------------------------------------
    private fun compressed(): Boolean = config.systemSaveInBinary && config.zipSaveData

    /**
     * `GameBase.ScriptVersionDefined`（GameBase.cs:25 + :138-140）。
     *
     * 会话装配经 [SaveWiring] 交出这一位时用它；否则给 null，由
     * [com.eratw.port.core.data.save.GameBaseGate.checkVersion] 自己按
     * `gameBase.versionDefined` → `version != 0` 的次序退级（真值源头见
     * [com.eratw.port.core.vars.GameBaseData.versionDefined]，由 `GameBaseLoader` 填）。
     */
    private fun scriptVersionDefined(): Boolean? = SaveWiring.of(store)?.scriptVersionDefined

    companion object {
        /** `RESULT` 的系统变量名（一维整型槽 0，`VariableData.cs` 的 `RESULT` 注册名）。 */
        private const val RESULT_VAR = "RESULT"

        /** `Lang.Error.LoadCorruptedData`（lang/emuera.zhs.xml:439）。 */
        const val MES_LOAD_CORRUPTED: String = "尝试载入数据错误的文件"

        /** `Lang.Error.UnexpectedErrorInLoaddata`（lang/emuera.zhs.xml:704）。 */
        const val MES_LOAD_UNEXPECTED: String = "读取文件时发生意料之外的错误"

        /** `Lang.Error.ErrorSavingGlobalData`（lang/emuera.zhs.xml:282）。 */
        const val MES_SAVE_GLOBAL: String = "保存全局数据时发生错误"

        /**
         * `SAVECHARA` 写盘失败的文案，`{0}` = 目标路径。
         *
         * 原版没有对应 Lang 条目：SaveChara 不 catch，失败是 `FileStream` 的
         * SystemException（IOException / UnauthorizedAccessException）直接冒泡
         * （VariableEvaluator.cs:2252-2283），屏上是 .NET 的本地化异常文案。
         * 本层 [SaveFileIo.write] 只回 Boolean，无法重放原始异常，故按同族 MES_SAVE_GLOBAL
         * 的句式（`Lang.Error.ErrorSavingGlobalData` = 「グローバルデータの保存中にエラーが
         * 発生しました」，Lang.cs:1502）给一条可诊断的固定文案并保留路径。
         */
        const val MES_SAVE_CHARA: String = "保存角色数据时发生错误\"{0}\""

        /** `Lang.Error.DelReadOnlyFile`（lang/emuera.zhs.xml:227，`{0}` = 路径）。 */
        const val MES_DEL_READONLY: String = "无法删除指定的只读文件\"{0}\""

        /**
         * 便捷工厂：`<gameRoot>` 落盘。
         *
         * @param useSaveFolder 原版 `UseSaveFolder`；为真时存档落在 `<root>/sav/`
         */
        fun rooted(
            store: VariableStore,
            root: java.nio.file.Path,
            config: SaveConfig = SaveConfig.DEFAULT,
            ops: com.eratw.port.core.host.FileOps? = null,
        ): FileSystemOps = FileSystemOps(store, RootedSaveFileIo(root, ops), config)
    }
}
