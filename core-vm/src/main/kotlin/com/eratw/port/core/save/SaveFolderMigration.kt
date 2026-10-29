package com.eratw.port.core.save

import com.eratw.port.core.vars.VariableStore

/**
 * 旧档迁移需要的**目录能力**（[SaveFileIo] 之外的最小增量面）。
 *
 * 为什么不把这几个方法直接并进 [SaveFileIo]：那个接口是存档读写的主干，既有假体
 * （测试里的内存表、[FileOpsSaveIo]）全都只实现它。目录面单独开一个接口后，
 * 「这个宿主能不能做迁移」变成运行期可判定的事（`io is SaveFolderIo`），
 * 假体不会因签名变化而返工 —— 与 [SaveFileIo.isReadOnly] 给缺省值的同一条兼容纪律。
 *
 * 生产实现 = [RootedSaveFileIo]（落点注入 `<gameRoot>`，与 `RootedFileOps` 同一个应用可写根，
 * 路径归一 + 越界拒绝复用它的 `resolve`，见 SaveFileIo.kt:94-100）。
 */
interface SaveFolderIo {

    /** 原版 `Directory.Exists(Config.SavDir)`（Config.cs:482；目录名 = [SaveConfig.saveFolderPrefix]）。 */
    fun saveFolderExists(): Boolean

    /**
     * 原版 `Directory.CreateDirectory(SavDir)`（Config.cs:555）：**已存在也算成功**（.NET 语义），
     * 失败（只读根 / 权限 / 根下已有同名**普通文件**）返回 false ⇒ 调用方走 Config.cs:557-561 的
     * 「弹框 + 直接 return」出口（原版 catch 的是全部异常，不区分原因）。
     */
    fun createSaveFolder(): Boolean

    /**
     * 原版 `Directory.GetFiles(Program.ExeDir, "save*.sav", SearchOption.TopDirectoryOnly)`
     * （Config.cs:563 判空用、:579 真搬用 —— 两次调用都照抄）。
     *
     * 返回**相对根**的文件名（顶层目录，所以不含分隔符）；只含普通文件（.NET 的 `GetFiles`
     * 不返回同名目录）。排序是为可复现而加的，原版顺序由文件系统决定（排序不改变最终状态，
     * 只在「搬到一半失败」时决定哪些条目已经进了 sav —— 见 [SaveFolderMigration] 类 KDoc 的
     * 顺序口径一段）。
     */
    fun rootSaveFileNames(): List<String>

    /**
     * 原版 `File.Move(src, dst)`（Config.cs:577 搬 global.sav、:583 搬 save 档）。
     *
     * 目标已存在时 .NET 抛 `IOException` ⇒ 本方法返回 false，**不覆盖**
     * （给 `REPLACE_EXISTING` 就是语义改动：原版在目标存在时是「整批迁移中止 + 弹框」）。
     */
    fun moveToFile(fromRel: String, toRel: String): Boolean
}

/** 迁移一次的结局（每个值对应原版一条出口，逐条见 [SaveFolderMigration] 的 KDoc 与行号）。 */
enum class SaveMigrationOutcome {
    /** `UseSaveFolder` 为假：原版走 `Config.cs:478-481` 的 else（`SavDir = ExeDir`），既不建目录也不搬。 */
    SwitchOff,

    /** 宿主没有目录面（内存假体 / [FileOpsSaveIo]）⇒ 迁移不可达，静默跳过。 */
    IoNotFolderCapable,

    /** `sav` 目录已存在（Config.cs:482 的 `!Directory.Exists` 守卫）⇒ 什么都不做（幂等的来源）。 */
    FolderAlreadyExists,

    /** 建目录失败：原版 `Dialog.Show(创建文件夹失败, 创建sav文件夹失败)` 后 return（Config.cs:556-561）。 */
    FolderCreateFailed,

    /** 根下既没有 `global.sav` 也没有 `save` 打头的 sav 档 ⇒ 原版**不弹框**直接 return（Config.cs:562-567）。 */
    NothingToMove,

    /** 用户在 `Dialog.ShowPrompt` 选了 No ⇒ 目录留着、文件不动（Config.cs:564-567）。 */
    Declined,

    /** 刚建的目录又不见了（原版 :568-572 防的是「弹框期间被外部删掉」）：弹框 + return。 */
    FolderMissing,

    /** 全部条目搬运成功（Config.cs:573-585 的 try 走到底）。 */
    Completed,

    /**
     * 搬运中途失败（Config.cs:586-589 的 catch）：**已搬的不回滚**、剩余留在根目录、只弹一条框。
     * 原版没有重试机制 ⇒ 下一次进 [FolderAlreadyExists] 分支，剩余条目永久留在根（官方行为，照抄）。
     */
    MoveFailed,
}

/**
 * 原版 `Dialog.Show(title, text)` / `Dialog.ShowPrompt(title, text)` 要弹的那一条
 * （Dialog.cs:16-19 与 :21-28）。移植侧 core 层不能弹 UI，所以文案随
 * [SaveFolderMigrationResult.notice] 交出，由宿主（platform/app）负责展示。
 *
 * 文案逐字取语料语言包 `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml`（与 reference 里的
 * `MinorShift.Emuera.Properties.lang.emuera-zhs.xml` 同值）；日文原文见 `Lang.cs` 的行号。
 */
data class SaveMigrationDialog(val title: String, val message: String)

/** [SaveFolderMigration.migrate] 的一次结果。 */
data class SaveFolderMigrationResult(
    val outcome: SaveMigrationOutcome,
    /** 已成功搬进 sav 的**相对根**路径（[SaveMigrationOutcome.MoveFailed] 时是部分清单）。 */
    val moved: List<String> = emptyList(),
    /** 只在 [SaveMigrationOutcome.MoveFailed] 非空：搬不动的那一条（相对根）。 */
    val failedFile: String? = null,
    /** 原版要弹的框（成功路径 = null，因为原版弹的只有四条失败/询问出口）。 */
    val notice: SaveMigrationDialog? = null,
) {
    /** 这次是否真的搬走过至少一个文件。 */
    val filesMoved: Boolean get() = moved.isNotEmpty()
}

/**
 * 迁移尝试的记录面（挂在 [VariableStore.aux]，宿主装配后可读；铁律 4：状态不进静态全局）。
 *
 * 为什么要记：原版迁移是**一次性**事件（`Config.cs:482` 的守卫 + 桌面弹框），Android 侧没有
 * 弹框就更容易「悄悄没搬」。把每次尝试的结局留在会话状态里，设置页/诊断面才有东西可显示。
 */
class SaveFolderMigrationLog {

    private val entries = ArrayList<SaveFolderMigrationResult>()

    /** 全部尝试（按发生顺序）。 */
    val results: List<SaveFolderMigrationResult> get() = entries.toList()

    /** 最近一次结局；从未尝试过 = null。 */
    val latest: SaveFolderMigrationResult? get() = entries.lastOrNull()

    /** 只有真正搬动过文件的尝试才计数（宿主用来决定「要不要提示玩家」）。 */
    val movedCount: Int get() = entries.count { it.filesMoved }

    internal fun add(result: SaveFolderMigrationResult) {
        entries += result
    }

    companion object {

        /** aux 槽名（能力家族前缀 + 小写，约定见 [com.eratw.port.core.host.RuntimeAux] 类 KDoc）。 */
        const val AUX_SLOT: String = "save.folder.migration"

        /** 现读；从未有 [FileSystemOps] 装配过 = null。 */
        fun of(store: VariableStore): SaveFolderMigrationLog? = store.aux.peek(AUX_SLOT)

        /** 取（不存在则建）本会话的记录面。 */
        fun install(store: VariableStore): SaveFolderMigrationLog = store.aux.slot(AUX_SLOT) { SaveFolderMigrationLog() }
    }
}

/**
 * SAVE-01：`Config.UseSaveFolder` 由 NO 变 YES 时，把游戏根下的旧档迁进 sav 文件夹。
 *
 * ## 原版逐句（reference/emuera-cs/MinorShift.Emuera.Runtime.Config/Config.cs）
 *
 * 触发点（`Config.SetConfig`，`Config.cs:296` 起，末尾 `:482-485` 调迁移）的调用方只有两处：
 * - 启动装载：`Program.cs:95` `ConfigData.Instance.LoadConfig()` → `ConfigData.cs:479-494`
 *   （三层 `_default` / `emuera.config` / `_fixed` 依次 loadConfig 后，`:494` 调 `Config.SetConfig`）；
 * - `MainWindow.cs:1667`（剪贴板菜单项点一下后重算条目）—— 与存档无关，是全仓唯一的二次调用点。
 * 游戏内设置页**不**走 `Config.SetConfig`：`ConfigDialog.cs:928`/`:1116` 只 `configData.SaveConfig()`
 * 写盘，按钮文案是 `SaveAndRestart`（`ConfigDialog.cs:622`）+ 「改动要重启才生效」（`:624`）
 * ⇒ 原版「玩家把开关改成 YES」的迁移实际发生在**下一次启动装载配置时**。
 * 移植侧的等价点 = 会话装配（[FileSystemOps] 构造期，见下方「生产可达性」）：
 * `GameHost` 也是设置页保存后重建会话（`app/.../GameHost.kt:730`），运行期不重算开关
 * （与 `SaveDataProbe` 类 KDoc 记的 TODO 同一条口径）。
 *
 * `SetConfig` 尾部的目录分支：
 * - `:471-477` `UseSaveFolder` 为真 ⇒ `SavDir = ExeDir + "sav" + sep`；
 * - `:478-481` 为假 ⇒ `SavDir = ExeDir`（**没有**别处再赋 `SavDir`）；
 * - `:482-485` `if (UseSaveFolder && !Directory.Exists(SavDir)) createSavDirAndMoveFiles();`
 *   ⇒ 触发条件是「开关为真 **且** sav 目录尚不存在」，不是「比较新旧条目值」。
 *   第一次迁移后目录必然存在 ⇒ 同一次进程内、以及下一次进程装载配置时都不再触发（幂等的真源）。
 *
 * 本体 `createSavDirAndMoveFiles()`（`:551-590`）：
 * 1. `:553-556` 建目录；`:557-561` catch **全部**异常 ⇒ `Dialog.Show(创建文件夹失败, 创建sav文件夹失败)`
 *    + `return`（不搬、不重试、不抛给调用方 ⇒ 开局照常进行）。
 * 2. `:562` `num = File.Exists(ExeDir + "global.sav")`；`:563` 枚举根目录 `save` 打头的 sav 档。
 * 3. `:564-567` `(!num && files.Length == 0) || !Dialog.ShowPrompt(数据移动, 已创建sav文件夹…移动吗？)`
 *    ⇒ `return`：**没东西可搬时连框都不弹**；有东西但玩家选 No ⇒ 目录已建、文件留在原地。
 * 4. `:568-572` 再查一次目录（弹框期间被人删了）⇒ `Dialog.Show(数据移动失败, 创建了sav文件夹但没有找到…)`
 *    + `return`。
 * 5. `:573-585` 一个 try 包住整批搬运：`:575-578` 先搬 `global.sav`（**精确文件名**，不吃通配）；
 *    `:579` **重新枚举**（原版这里真的调了第二次 `GetFiles`，此时 global 已不在根）；
 *    `:581-584` 逐条 `File.Move(text, SavDir + Path.GetFileName(text))` ⇒ **同名落地、不改名、不合并子目录**。
 * 6. `:586-589` catch 全部异常 ⇒ `Dialog.Show(数据移动失败, sav文件移动失败)`；**没有 rollback**，
 *    已搬的留在 sav、没搬的留在根，且因为目录已存在，后续装载再也不会补搬（`Config.cs:482`）。
 *
 * 命名冲突：原版**不可能**遇到目标已存在（sav 是刚建的空目录），所以没有任何冲突处理分支；
 * 真撞上（外部先占了同名文件）就是 `File.Move` 抛异常 ⇒ 落到第 6 条。本层同形：
 * [SaveFolderIo.moveToFile] 不给覆盖语义。
 *
 * ## 与原版刻意保留的三点差异（都登记在此，不是遗漏）
 *
 * 1. **通配大小写**：`GetFiles` 在 Windows 上大小写不敏感 ⇒ `matchesSavePattern` 一律按小写比；
 *    Android 的 ext4 大小写敏感，`SAVE01.SAV` 在那边既是候选也能搬走（搬运按实际名字，不改名）。
 * 2. **排序**：原版顺序由文件系统决定；本层按名字升序（Config.cs:563/579 的返回序对可观测行为
 *    只影响「搬到一半失败时谁先过去」）。
 * 3. **8.3 短名**：.NET Framework 的通配还会命中短文件名（`*.sav` 匹配扩展名以 sav 开头的
 *    长名条目这类历史行为）。TODO(verify)：真机（PC 侧）是否出现过这种命中无法验证 ——
 *    本层只认「`save` 前缀 + `.sav` 后缀」的长名，语料 5 份存档全在 sav 下、根目录 0 个 sav 档，
 *    因此开局读数不受影响。
 *
 * ## 生产可达性
 *
 * 调用点 = [FileSystemOps] 构造期（SAVE-01 的落点）：`GameSession` 第 8 步
 * （core/proc/GameSession.kt:402）建 `FileSystemOps(store, RootedSaveFileIo(gameRoot, rooted.fileOps), saveConfig)`
 * ⇒ 只要会话装配（含 Android 的 `GameHost` 第 730 行）就会跑一次迁移判定，
 * `UseSaveFolder` 与 `SavDir` 用的是同一份 [SaveConfig]（`:347-351`）⇒ 探测与落盘同源
 * （`SaveDataProbe.candidatePaths` 的单候选口径，Config.cs:471-481）。
 * 原版的桌面询问框在 core 层无法呈现 ⇒ [migrate] 的 `prompt` 形参缺省 [AUTO_ACCEPT]
 * （选「移动」才是原版玩家改配置后的预期：否则标题存档列表全空）；
 * 要接真弹窗由组长在 `GameSession` 传该形参（交付报告「需组长接线」）。
 */
object SaveFolderMigration {

    /** 原版 `Program.ExeDir + "global.sav"` 的文件名（Config.cs:562 / :575 / :577）。 */
    const val GLOBAL_FILE: String = "global.sav"

    /**
     * 存档文件夹名 —— 原版在 `Config.cs:470`（`ForceSavDir`）与 `:474-476`（`SavDir`）两处都写死
     * `"sav"`，条目表里没有任何可改它的键；移植侧 [SaveConfig.saveFolderPrefix] 的 `sav/`
     * 就是这一位（SaveConfig.kt:38）。
     */
    const val SAV_FOLDER: String = "sav"

    /** `Lang.MessageBox.DataTransfer`（语料 lang/emuera.zhs.xml:786；日文原文 Lang.cs:4066「データ移動」）。 */
    const val TITLE_DATA_TRANSFER: String = "数据移动"

    /** `Lang.MessageBox.DataTransferFailure`（:787；Lang.cs:4074「データ移動失敗」）。 */
    const val TITLE_DATA_TRANSFER_FAILURE: String = "数据移动失败"

    /** `Lang.MessageBox.FolderCreationFailure`（:798；Lang.cs:4054「フォルダ作成失敗」）。 */
    const val TITLE_FOLDER_CREATION_FAILURE: String = "创建文件夹失败"

    /** `Lang.MessageBox.FailedCreateSavFolder`（:792；Lang.cs:4058）。Config.cs:559。 */
    const val MES_FAILED_CREATE_SAV_FOLDER: String = "创建sav文件夹失败"

    /** `Lang.MessageBox.FailedMoveSavFiles`（:793；Lang.cs:4078）。Config.cs:588。 */
    const val MES_FAILED_MOVE_SAV_FILES: String = "sav文件移动失败"

    /** `Lang.MessageBox.SavFolderCreated`（:823-824；Lang.cs:4062）。Config.cs:564 的询问正文。 */
    const val MES_SAV_FOLDER_CREATED: String = "已创建sav文件夹\n将现在的数据移动到sav文件夹中吗？"

    /** `Lang.MessageBox.MissingSavFolder`（:806-807；Lang.cs:4070）。Config.cs:570。 */
    const val MES_MISSING_SAV_FOLDER: String = "创建了sav文件夹但没有找到\n将其删除吗？"

    /**
     * 缺省应答 = 「是」（原版 `Dialog.ShowPrompt` 的 Yes ⇒ true，Dialog.cs:21-28）。
     * core 层没有弹框能力，而「玩家把开关打开」这个动作本身已经表达了意图 ⇒ 缺省按 Yes 走。
     */
    val AUTO_ACCEPT: (title: String, message: String) -> Boolean = { _, _ -> true }

    /**
     * 原版 `save` 打头的 sav 档通配（`Directory.GetFiles(ExeDir, "save*.sav")`，Config.cs:563/:579）。
     *
     * `*` 可匹配空串 ⇒ 最短命中是 `save.sav`（8 个字符）；比较不分大小写（Windows 目录语义）。
     * 只看文件名本身，因此 `sav/save01.sav` 这类子目录条目天然不在候选里（TopDirectoryOnly）。
     */
    fun matchesSavePattern(name: String): Boolean {
        val n = name.lowercase()
        return n.length >= "save.sav".length && n.startsWith("save") && n.endsWith(".sav")
    }

    /**
     * 跑一次迁移判定（[SaveFolderMigration] 类 KDoc 的 6 步，逐条带 Config.cs 行号）。
     *
     * @param io 存档 IO；只有同时实现 [SaveFolderIo] 的宿主（生产 = [RootedSaveFileIo]）才真搬，
     *   其余假体回 [SaveMigrationOutcome.IoNotFolderCapable] 且**一个字节都不碰**。
     * @param config 生效的落盘策略：[SaveConfig.useSaveFolder] = false 时原样退出（Config.cs:478-481），
     *   目录与落地名都取 [SaveConfig.saveFolderPrefix] / [SaveConfig.globalPath]（同一条 SavDir 口径）。
     * @param prompt 原版 `Dialog.ShowPrompt`（Config.cs:564）；缺省 [AUTO_ACCEPT]。
     *   只在「根下有东西可搬」时才被调用（原版 :564 的短路顺序：先判空再弹框）。
     */
    fun migrate(
        io: SaveFileIo,
        config: SaveConfig,
        prompt: (title: String, message: String) -> Boolean = AUTO_ACCEPT,
    ): SaveFolderMigrationResult {
        // Config.cs:478-481 + :482 —— 开关为假时连目录都不建。
        if (!config.useSaveFolder) return SaveFolderMigrationResult(SaveMigrationOutcome.SwitchOff)
        if (io !is SaveFolderIo) return SaveFolderMigrationResult(SaveMigrationOutcome.IoNotFolderCapable)

        // Config.cs:482 —— 目录已存在 ⇒ 整段跳过（幂等）。
        if (io.saveFolderExists()) return SaveFolderMigrationResult(SaveMigrationOutcome.FolderAlreadyExists)

        // Config.cs:553-561 —— 建目录失败只弹框、不搬。
        if (!io.createSaveFolder()) {
            return SaveFolderMigrationResult(
                SaveMigrationOutcome.FolderCreateFailed,
                notice = SaveMigrationDialog(TITLE_FOLDER_CREATION_FAILURE, MES_FAILED_CREATE_SAV_FOLDER),
            )
        }

        // Config.cs:562-563 —— 有没有东西可搬（global 精确名 + save 通配）。
        val hasGlobal = io.exists(GLOBAL_FILE)
        val candidates = io.rootSaveFileNames()
        // Config.cs:564-567 —— 没东西 ⇒ 静默 return；有东西才弹框，选 No ⇒ 目录留着、文件不动。
        if (!hasGlobal && candidates.isEmpty()) return SaveFolderMigrationResult(SaveMigrationOutcome.NothingToMove)
        if (!prompt(TITLE_DATA_TRANSFER, MES_SAV_FOLDER_CREATED)) {
            return SaveFolderMigrationResult(SaveMigrationOutcome.Declined)
        }
        // Config.cs:568-572 —— 弹框期间目录被人删掉。
        if (!io.saveFolderExists()) {
            return SaveFolderMigrationResult(
                SaveMigrationOutcome.FolderMissing,
                notice = SaveMigrationDialog(TITLE_DATA_TRANSFER_FAILURE, MES_MISSING_SAV_FOLDER),
            )
        }

        // Config.cs:573-585 —— 一个 try 包住整批：global 先、再重新枚举逐条搬。
        val moved = ArrayList<String>()
        if (hasGlobal) {
            if (!io.moveToFile(GLOBAL_FILE, config.globalPath())) {
                return moveFailed(moved, GLOBAL_FILE)
            }
            moved += config.saveFolderPrefix + GLOBAL_FILE
        }
        for (name in io.rootSaveFileNames()) {
            if (!io.moveToFile(name, config.saveFolderPrefix + name)) return moveFailed(moved, name)
            moved += config.saveFolderPrefix + name
        }
        return SaveFolderMigrationResult(SaveMigrationOutcome.Completed, moved)
    }

    /** Config.cs:586-589：catch 全部异常 ⇒ 只弹一条框，已搬不回滚、剩余不重试。 */
    private fun moveFailed(moved: List<String>, failed: String): SaveFolderMigrationResult =
        SaveFolderMigrationResult(
            SaveMigrationOutcome.MoveFailed,
            moved = moved.toList(),
            failedFile = failed,
            notice = SaveMigrationDialog(TITLE_DATA_TRANSFER_FAILURE, MES_FAILED_MOVE_SAV_FILES),
        )
}
