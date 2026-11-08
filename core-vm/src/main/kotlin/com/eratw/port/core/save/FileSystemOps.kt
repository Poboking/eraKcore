package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.update.UpdateCheckDecision
import com.eratw.port.core.update.UpdateCheckHost
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
 *
 * ## UPDATECHECK（本波接真）
 *
 * [updateCheck] 是 `UPDATECHECK` 语句的生产实现：判序与六个 RESULT 读数全部下沉在
 * [UpdateCheckDecision]（逐行证据见那里的表），本类只做编排 —— 读 `GameBaseData` 的
 * `url` / `versionName`（`store.registry.gameBase`，装载侧 `GameBaseLoader` 填），
 * 网络/取流/询问/开链接四件事交给注入的 [UpdateCheckHost]。
 * **没注入宿主时仍抛 [CodeEE]**（[SystemOps] 的「未实现」口径），注入后不会：
 * 本语料的 `CSV/GameBase.csv` 没有 `バージョン情報URL` 行 ⇒ URL 恒空 ⇒ 前三道闸落 RESULT=3，
 * 与原版同解（判据与单测见 `core/update/UpdateCheckStatementTest`）。
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
    /**
     * `UPDATECHECK` 的宿主能力面（网络 / 取流 / 询问 / 开链接），见 [UpdateCheckHost]。
     *
     * 为什么是 `var` 而不是只有构造参数：本类的**构造点在 `GameSession` 第 8 步**
     * （`core/proc/GameSession.kt:721`），而 `GameSession` 不在本路的所有权面上
     * （CONTRACT-UPDATE §2 的 U-F 行）—— 于是生产装配走「会话建好，宿主立刻补上」：
     * `GameHost` 在 `assembleSession` 里把实现赋给 `runtime.execContext.systemOps`
     * 上这个属性。写入方是装配线程（真机 = VM 线程，且早于任何脚本语句），
     * 读入方是同一个 VM 线程，与 `ExecContext.graphics` / `timer` 的「`var` + 单线程写」
     * 同一条纪律；语句层每次现读，所以换实现与构造期传入逐次执行等价。
     *
     * null（缺省）= 未接线 ⇒ `UPDATECHECK` 抛 [CodeEE]，与 [SystemOps.updateCheck] 的旧口径一致；
     * 装置（纯 JVM 单测）传假体，真机传 `platform/update` 的取流件 + `GameHost` 的询问件。
     */
    var updateCheckHost: UpdateCheckHost? = null,
) : SystemOps {

    /**
     * 堆贴顶时向宿主求援：回收可释放的图形缓冲，返回**实际释放的字节数**（0 = 没腾出东西）。
     *
     * ## 为什么需要这条缝
     *
     * 2026-11-02 玩家反馈「游玩时间太久了，可能导致存档失败，重启游戏后又正常了」
     * （eratw 4.980 / 0.2.35 / **没开**「即时存档」插件）。三条现象指向的机制是
     * **堆随时间累积**，不是存档本身：
     * - `aa5a956c` 的设备实证（HONOR ALI-AN00 / 0.2.35）：启动 BeginFlow 建 657 张 G_ID、
     *   **167 MiB 像素驻留**，95 秒长页一路顶到 512 MiB 堆 OOM —— 越玩越满；
     * - 存档要**一次性**分配「角色/变量对象图 + 完整 payload（实测 560 KB）+ 压缩副本」
     *   （[SaveCapture.normal] → `SaveCodec.write` → [SaveFormat.write] 的四份并存），
     *   堆在 90%+ 时这一笔分不出来；
     * - `OutOfMemoryError` 是 `Error`，原版口径的 `catch (Throwable) { false }` 会把它和
     *   普通 IO 失败混成同一个 `false` ⇒ 屏上只剩
     *   `SAVEDATA命令在存档时发生了意料之外的错误`；
     * - 重启清堆 ⇒ 立刻恢复，与「存储满 / 权限 / 目录口径」那类问题（上一轮修的）不同。
     *
     * ## 契约
     *
     * - `urgent = false`（**存档前预防档**）：每次 [saveData] 开头调用一次。实现方**自己判水位**
     *   （建议 ≥90% 才动手），水位不高就返回 0 —— 正常游玩零影响，不能每存一次档都收画面；
     * - `urgent = true`（**OOM 后求援档**）：只在已经抛过 [OutOfMemoryError] 之后调用，
     *   实现方应**无条件**尽量腾空间，不再看水位与冷却；
     * - 返回值 = 实际释放的字节数；**求援档返回 0 ⇒ 不重试**（再试只会再 OOM），
     *   按原版口径返回 `false`；返回 > 0 ⇒ 重试**一次**（走同一段 `capture → encode → write`）；
     * - 实现方允许有副作用（回收可能让脚本手里留着的中间图变空白）——
     *   预防档靠水位把这个代价压到「本来就要出事」的时刻，求援档则接受它；
     * - 抛异常一律当 0 处理（本类自己 catch），不许让求援把存档失败升级成崩溃。
     *
     * null（缺省）= 未接线（纯单测 / erbcc 自检 / 装置传了别的 `systemOps`）⇒ 行为与
     * 本条补丁之前逐字相同：一次失败、留痕、返回 false。生产由 `GameHost` 在会话装配后
     * 补上（与 [updateCheckHost] 同一条「`var` + 装配线程单写」纪律）。
     */
    var reclaimMemory: ((urgent: Boolean) -> Long)? = null

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

    /**
     * [SystemOps.saveData]：false = 原版 `SaveTo` 的失败返回（语句层打印错误行）。
     *
     * **OOM 单独摘出来**（2026-11-02 玩家「玩久了存档失败、重启就好」）：`OutOfMemoryError`
     * 是 `Error`，若只留 `catch (Throwable)` 就和普通 IO 失败糊成同一个 `false`，屏上只剩
     * 一句「意料之外的错误」。这里先接住它 → 向宿主求援回收 → 重试一次（见 [reclaimMemory]）；
     * 求援腾不出空间才按失败收口。非 OOM 的异常路径与改动前**逐字相同**。
     */
    override fun saveData(no: Int, text: String): Boolean {
        val path = config.normalPath(no)
        // 预防档：堆已经很高就先腾一次，避免下面直接撞 OOM。
        // 水位由实现方判（钩子返回 0 = 没动手），所以正常存档不受影响。
        try {
            reclaimMemory?.invoke(false)
        } catch (_: Throwable) {
            // 预防档失败不影响存档本身：下面照常走，真失败还有求援档兜。
        }
        // 阶段标记只为留痕（见 SaveDiagnostics 的类文档）：原版把这一段整个吞成 false，
        // 玩家报「不能覆盖旧存档」时无法区分「抓变量池失败 / 序列化失败 / 落盘失败」。
        var stage = "capture"
        try {
            return captureAndWrite(no, text, path) { stage = it }
        } catch (e: OutOfMemoryError) {
            // 首试 stage 一并带过去：encode/write 段的 OOM 留痕若恒标 capture(oom)，
            // 会把排障引向「抓变量池」而真凶在序列化/落盘（2026-11-02 审查修正）。
            return retryAfterOom(no, text, path, e, stage)
        } catch (e: Throwable) {
            // 原版 catch (Exception) { return false; }（VariableEvaluator.cs:2666-2669）
            SaveDiagnostics.report("SAVEDATA", path, stage, e, saveDirContext())
            return false
        }
    }

    /**
     * 抓变量池 → 序列化 → 落盘，三段逐个报给 [mark]（留痕用）。
     *
     * 抽成单独一段是为了让 OOM 重试**走同一份实现**：重试若另写一遍，两条路迟早分叉。
     */
    private fun captureAndWrite(no: Int, text: String, path: String, mark: (String) -> Unit): Boolean {
        mark("capture")
        val data = SaveCapture.normal(store, text, compressed = compressed(), emData = emSets.save)
        mark("encode")
        // 流式落盘（OOM-B，2026-11-08）：payload 物化后，16 字节头 + GZip 直接写进目标文件
        // （生产 = 临时文件），不再生成「gzip 副本 + 容器 BAOS + 终拷贝」三份。
        // 首个字节真正开写才切 write 段（见 [StageMarkingOutputStream]）：encodePayload 就
        // 贴顶的 OOM 仍记在 encode，落盘侧贴顶才记 write。
        return io.write(path) { out ->
            SaveCodec.writeTo(data, store.registry, config, StageMarkingOutputStream(out) { mark("write") })
        }
    }

    /**
     * 首个字节真正写进 [out] 时把 stage 切到 write（OOM-B 流式口）。
     *
     * 流式写把「encodePayload 物化」与「落盘」挤进了同一次 [SaveFileIo.write] 调用；
     * [com.eratw.port.core.data.save.SaveFormat.writeTo] 的字节在 payload 编完之后才开写，
     * 所以「第一次 write」≈ encode 段结束。丢了这层标记，encode/write 两段会糊成一段，
     * 排障分不清是序列化还是落盘贴顶（`retryAfterOom` 的首差 stage 依赖它）。
     */
    private class StageMarkingOutputStream(
        private val out: java.io.OutputStream,
        private val onFirstWrite: () -> Unit,
    ) : java.io.OutputStream() {
        private var marked = false

        private fun beforeWrite() {
            if (marked) return
            marked = true
            onFirstWrite()
        }

        override fun write(b: Int) {
            beforeWrite()
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            beforeWrite()
            out.write(b, off, len)
        }

        override fun flush() = out.flush()
    }

    /**
     * 全局档 / 角色档的落盘：把 [SaveFileIo.write] 现在会**上抛**的 `OutOfMemoryError` 折回
     * 「写失败」，这两条链的屏上文案与判据因此与改动前逐字相同。
     *
     * 为什么普通档 [captureAndWrite] 不这样：`SAVEDATA` 外面套着 [retryAfterOom] 的求援重试
     * （提交 0452e8dd），那条链只认得从 `io.write` **抛出**的 OOM；写入阶段的 OOM 若在这里
     * 静默成 false，玩家腾不出内存就只能看着好档被截断（`SaveFileIo` 的退路先截断再写）。
     */
    private fun writeTreatOomAsFailure(path: String, bytes: ByteArray): Boolean = try {
        io.write(path, bytes)
    } catch (_: OutOfMemoryError) {
        false
    }

    /**
     * 堆贴顶后的补救：向宿主求援回收可释放的图形缓冲，腾出空间就**重试一次**存档。
     *
     * 判据与取舍：
     * - 只跑在 [OutOfMemoryError] 之后 ⇒ 正常游玩零影响，回收的副作用（脚本手里的中间图
     *   可能变空白）只在「本来就要存不上」的时刻兑现；
     * - 回收量 0（未接线 / 无可回收项）⇒ **不重试**：再试一次只会再 OOM，白受一次罪；
     * - 重试再失败 ⇒ 留痕后按原版口径返回 false，不再循环（避免 OOM 死循环）。
     *
     * 留痕次序是「先回收、后写日志」：OOM 现场拼字符串/取栈本身也要内存，
     * 等腾出空间再写才不会把「存档失败」升级成「留痕也失败」。
     */
    private fun retryAfterOom(no: Int, text: String, path: String, first: OutOfMemoryError, firstStage: String): Boolean {
        val reclaimed = try {
            reclaimMemory?.invoke(true) ?: 0L
        } catch (_: Throwable) {
            0L
        }
        SaveDiagnostics.note(
            "SAVEDATA",
            path,
            "堆贴顶(OutOfMemoryError) 已回收=${reclaimed / 1024}KiB" +
                if (reclaimed > 0L) "，重试一次" else "，不重试",
        )
        if (reclaimed <= 0L) {
            SaveDiagnostics.report("SAVEDATA", path, "$firstStage(oom)", first, saveDirContext())
            return false
        }
        var stage = "capture(retry)"
        return try {
            captureAndWrite(no, text, path) { stage = it }
        } catch (e: OutOfMemoryError) {
            // 重试又贴顶：不再循环，按失败收口（再来一次也只是再 OOM）。
            SaveDiagnostics.report("SAVEDATA", path, "retry(oom)", e, saveDirContext())
            false
        } catch (e: Throwable) {
            SaveDiagnostics.report("SAVEDATA", path, stage, e, saveDirContext())
            false
        }
    }

    /**
     * 存档目录的现场快照（诊断用，只在 [saveData] 失败时取）。
     *
     * 为什么要把目录清单也打出来：玩家报的「不能覆盖旧存档」在桌面（JVM）上复现不出来
     * （同一份语料 + 同一份存档，覆盖 / 新建 / 删档三条链全绿，见
     * `PluginSaveSlotFlowTest`），分叉只可能在 Android 侧的落盘。现场最需要的三件事是
     * 「`SavDir` 到底解析到哪个目录 / 该目录在不在 / 里面已经有谁」，这三条拿到就能把
     * 「目录口径不一致」与「写失败」区分开，而不必让玩家再复现一次。
     *
     * 只报**相对**信息（目录名 + 文件名 + 体积），不报绝对路径：错误报告与 logcat 都可能
     * 被玩家贴到公开场合，`/data/user/0/<包名>/...` 没有诊断价值。
     */
    private fun saveDirContext(): String = try {
        describeSaveDir()
    } catch (_: Throwable) {
        // 本函数跑在**失败的 catch 块里**，其中就包括 OOM：列目录与字符串拼接都要分配内存，
        // 在这里再抛一次会把「存档失败」升级成「进程崩溃」，比没有现场更糟。
        "现场不可用"
    }

    private fun describeSaveDir(): String {
        val rooted = io as? RootedSaveFileIo ?: return "io=${io.javaClass.simpleName}（无目录面）"
        val dir = if (config.useSaveFolder) rooted.rootedPath.resolve(SaveFolderMigration.SAV_FOLDER)
        else rooted.rootedPath
        val label = if (config.useSaveFolder) "sav/" else "<root>"
        val entries = runCatching {
            if (!java.nio.file.Files.isDirectory(dir)) emptyList()
            else java.nio.file.Files.list(dir).use { s ->
                s.map { p ->
                    val size = runCatching { java.nio.file.Files.size(p) }.getOrNull()
                    "${p.fileName}(${size ?: "?"})"
                }.sorted().toList()
            }
        }.getOrElse { listOf("<列目录失败 ${it.javaClass.simpleName}>") }
        return "dir=$label 存在=${java.nio.file.Files.isDirectory(dir)} " +
            "binary=${config.systemSaveInBinary} zip=${config.zipSaveData} " +
            "条目=${entries.ifEmpty { listOf("(空)") }}"
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
            SaveCodec.read(bytes, store.registry, SaveFileKind.NORMAL, ::isKnownSaveKey, config)
        } catch (_: Exception) {
            throw CodeEE(MES_LOAD_CORRUPTED)
        }
        try {
            data.applyTo(store, restoreCharacters = true, setLastLoad = true, floatSink = store::writeFloat)
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
        if (!writeTreatOomAsFailure(config.globalPath(), bytes)) throw CodeEE(MES_SAVE_GLOBAL)
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
            SaveCodec.read(bytes, store.registry, SaveFileKind.GLOBAL, ::isKnownSaveKey, config)
        } catch (_: Throwable) {
            return false
        }
        if (data.fileType != SaveFileKind.GLOBAL) return false
        return try {
            data.applyTo(
                store,
                restoreCharacters = false,
                setLastLoad = false,
                floatSink = store::writeFloat,
            )
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
        if (!writeTreatOomAsFailure(path, bytes)) throw CodeEE(MES_SAVE_CHARA.replace("{0}", path))
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
    // UPDATECHECK（决策表在 core/update/UpdateCheckDecision.kt，本类只编排）
    // ------------------------------------------------------------------

    /**
     * [SystemOps.updateCheck]。spec: `FunctionIdentifier.cs:2757-2839`（逐行判序见
     * [UpdateCheckDecision] 的表格）。
     *
     * 编排 = 原版的方法体形状，一次不落：
     * 1. `:2767-2783` 三道纯闸（配置禁令 → 网络 → URL 判空）由 [UpdateCheckDecision.precheck] 判，
     *    **未放行时一个请求都不发**（原版 `WebClient` 在 `:2778` 只是 new 对象，`OpenRead` 在 `:2786`）；
     *    禁令为真时连网络探针都不按（原版 `:2769` 先 `return`，`:2772` 在它之后）；
     * 2. `:2786-2791` 取流读前两行 = [UpdateCheckHost.fetchVersionInfo]；
     * 3. `:2792-2825` 内容判定 = [UpdateCheckDecision.afterFetch]；要问玩家才调
     *    [UpdateCheckHost.askOpenLink]（原版 `MessageBox.Show`，`:2802`）；
     * 4. 最终读数由一次性表 [UpdateCheckDecision.decide] 给出（与第 1/3 步同解 ——
     *    三步都用**同一份**宿主探针读数，不重访探针）；
     * 5. `RESULT = 2` 之后才 [UpdateCheckHost.openDownloadLink]（原版 `Process.Start`，`:2805-2809`），
     *    打开失败按原版的 catch 把 2 改写成 3（`:2827-2832` 在写 2 之后仍能把 RESULT 覆盖）。
     *
     * 宿主探针抛异常一律收敛成「拿不到」，不外抛：取流抛 ⇒ `remote = null` ⇒ 3；
     * 询问抛 ⇒ `accepted = null` ⇒ 3。与原版「任何异常 ⇒ RESULT=3」同解
     * （`:2827-2832`、`:2834-2837`），也保证 VM 线程不会因为宿主实现炸掉整条语句链。
     * 唯一的例外是前两道探针：原版它们排在 try 外（`try` 从 `:2784` 才开始），抛出时不进
     * RESULT=3 那条 catch 而是整条语句报错。本编排因此**不**把 [UpdateCheckHost.forbidUpdateCheck]
     * 与 [UpdateCheckHost.isNetworkAvailable] 包进 runCatching —— 真机上那两位的实现自带兜底
     * （配置读不到按「禁止」=4、网卡枚举失败按「没网」=5），异常不该由 core 代它决定。
     *
     * @throws CodeEE 未注入 [updateCheckHost]（`SystemOps` 的「未实现」口径，仅装置/未接线场景）
     */
    override fun updateCheck(): Long {
        val host = updateCheckHost ?: throw CodeEE(MES_UPDATECHECK_NO_HOST)
        val gameBase = store.registry.gameBase
        val url = gameBase.url
        val forbidden = host.forbidUpdateCheck()
        // 禁令命中时**不去按网络探针**：原版 `:2769` 的 `return` 排在 `:2772` 的
        // `GetIsNetworkAvailable()` 之前。这里的 false 在决策表里永远不会被消费
        // （[UpdateCheckDecision.precheck] 的第一条就是禁令 ⇒ 直接落 4），
        // 顺序由 `UpdateCheckStatementTest.precheckAndAfterFetchArePrefixesOfTheTable` 钉住。
        val networkAvailable = if (forbidden) false else host.isNetworkAvailable()
        // 第 2 步：precheck 没放行就 return，取流一次都不会发生（原版 :2779-2783 排在 OpenRead 之前）。
        val remote: UpdateCheckDecision.RemoteInfo? =
            when (val pre = UpdateCheckDecision.precheck(forbidden, networkAvailable, url)) {
                is UpdateCheckDecision.Precheck.Settled -> return pre.result
                is UpdateCheckDecision.Precheck.Fetch ->
                    runCatching { host.fetchVersionInfo(pre.updateCheckUrl) }.getOrNull()
            }
        val outcome = UpdateCheckDecision.afterFetch(remote, gameBase.versionName)
        val accepted: Boolean? = when (outcome) {
            is UpdateCheckDecision.FetchOutcome.Settled -> null // 这一步不需要玩家表态
            is UpdateCheckDecision.FetchOutcome.Ask -> runCatching {
                host.askOpenLink(
                    UpdateCheckDecision.ASK_TITLE,
                    UpdateCheckDecision.askMessage(outcome.newVersionName, outcome.downloadUrl),
                )
            }.getOrNull()
        }
        val result = UpdateCheckDecision.decide(
            forbidUpdateCheck = forbidden,
            networkAvailable = networkAvailable,
            updateCheckUrl = url,
            remote = remote,
            localVersionName = gameBase.versionName,
            userAccepted = accepted,
        )
        if (result == UpdateCheckDecision.RESULT_OPENED && outcome is UpdateCheckDecision.FetchOutcome.Ask) {
            // 打开失败 ⇒ 3（原版 :2804 先写 2、:2805-2809 的 Process.Start 抛出后被 :2827-2832 的
            // catch 覆盖成 3 —— RESULT 是活字段，写过的值不作数）。
            return runCatching { host.openDownloadLink(outcome.downloadUrl) }
                .fold(onSuccess = { UpdateCheckDecision.RESULT_OPENED }, onFailure = { UpdateCheckDecision.RESULT_ERROR })
        }
        return result
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
         * `UPDATECHECK` 在**宿主未接线**时的报错文案（移植侧自定义，不是原版 Lang 条目）。
         *
         * 原版没有这条：它的取流/弹框/开链接都写在语句方法体里（`FunctionIdentifier.cs:2786-2809`），
         * 异常一律被两个 catch 收敛成 RESULT=3（`:2827-2837`）。这条只在「装配方根本没给
         * [UpdateCheckHost]」时出现 —— 装配缺陷不该被伪装成语义读数，所以显式报错而不是
         * 静默落 3（接线之后本串在生产路径不可达；口径见 [SystemOps.updateCheck]）。
         */
        const val MES_UPDATECHECK_NO_HOST: String = "UPDATECHECK 需要宿主提供网络能力（SystemOps）"

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
