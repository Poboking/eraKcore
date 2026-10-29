package com.eratw.port.core.save

import com.eratw.port.core.vars.VariableStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * SAVE-01 收口：`Config.UseSaveFolder` 由 NO 变 YES 时，把游戏根下的旧档迁进存档文件夹。
 *
 * ## 原版出处（`reference/emuera-cs/MinorShift.Emuera.Runtime.Config/Config.cs`）
 *
 * 触发点 `:482-485`（`if (UseSaveFolder && !Directory.Exists(SavDir)) createSavDirAndMoveFiles();`）
 * + 本体 `:551-590`；`SavDir` 的两条分支 = `:471-477`（真 ⇒ ExeDir + `sav` + sep）与
 * `:478-481`（假 ⇒ ExeDir）。逐句对照写在 [SaveFolderMigration] 的类 KDoc，本文件按
 * 「出口」逐条钉读数。弹框文案取语料 `eratw-画蛇添足版eratw4.980/lang/emuera.zhs.xml`
 * 的 :786 / :787 / :792 / :793 / :798 / :806-807 / :823-824（日文原文 `Lang.cs:4054-4078`；
 * 参照物 `reference/emuera-cs/Dialog.cs:16-28`）。
 *
 * ## 改前 / 改后（契约 §3.1 要求的两步）
 *
 * 修复前的引擎缺陷读数：移植侧没有任何代码碰过这件事（`core/proc/BootHosts.kt:389` 只留了句
 * 注释），所以玩家把开关改成 YES 后旧档**永远留在根目录**，而 `SavDir` 只认 `sav` 一条候选
 * （`SaveDataProbe.candidatePaths`）⇒ 标题存档列表全空。
 * [迁移前现状：宿主没有目录面就一个字节都不动] 把这条错读数钉成**可观察事实**（旧档不动 +
 * 询问一次都没发生），[原版读数：根目录旧档搬进 sav 且清单与原版逐条对应] 再钉原版读数。
 * 生产装配路径见 [FileSystemOps 构造期就完成迁移（GameSession 第 8 步同形）]：
 * `GameSession` 第 8 步就是 `FileSystemOps(store, RootedSaveFileIo(gameRoot, rooted.fileOps), saveConfig)`
 * （`core/proc/GameSession.kt:402`）⇒ 不是「只有测试在调」。
 */
class SaveFolderMigrationTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 只实现 [SaveFileIo] 的假体 = **修复前的实现形态**（与生产曾用过的 `FileOpsSaveIo` 同形）。 */
    private class TableOnlyIo(val files: MutableMap<String, ByteArray> = LinkedHashMap()) : SaveFileIo {
        override fun exists(path: String): Boolean = files.containsKey(path.replace('\\', '/'))
        override fun read(path: String): ByteArray? = files[path.replace('\\', '/')]
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path.replace('\\', '/')] = bytes
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path.replace('\\', '/')) != null
    }

    /**
     * 带目录面的内存表假体（[SaveFolderIo] 的语义面与 [RootedSaveFileIo] 一致），
     * 用来造真磁盘上做不出来的失败出口。
     *
     * @param folderExists 初始时存档文件夹在不在（Config.cs:482 的判据）
     * @param createFails 造 Config.cs:556-561 的建目录失败
     * @param failMoveOf 这些源路径的 `File.Move` 失败（造 :586-589 的中途失败）
     * @param vanishingFolder 建完就「被外部删掉」：两次目录查询都答 false ⇒ 落 :568-572
     * @param moves 成功发生的搬运（源 to 目标），用来钉**次序**与「失败即止」
     */
    private class FakeFolderIo(
        val files: MutableMap<String, ByteArray> = LinkedHashMap(),
        var folderExists: Boolean = false,
        var createFails: Boolean = false,
        var failMoveOf: Set<String> = emptySet(),
        var vanishingFolder: Boolean = false,
        val moves: MutableList<Pair<String, String>> = ArrayList(),
    ) : SaveFileIo, SaveFolderIo {

        override fun exists(path: String): Boolean = files.containsKey(path.replace('\\', '/'))
        override fun read(path: String): ByteArray? = files[path.replace('\\', '/')]
        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path.replace('\\', '/')] = bytes
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path.replace('\\', '/')) != null

        override fun saveFolderExists(): Boolean = if (vanishingFolder) false else folderExists

        override fun createSaveFolder(): Boolean {
            if (createFails) return false
            folderExists = true
            return true
        }

        override fun rootSaveFileNames(): List<String> = files.keys
            .filter { !it.contains('/') && SaveFolderMigration.matchesSavePattern(it) }
            .sorted()

        override fun moveToFile(fromRel: String, toRel: String): Boolean {
            val src = fromRel.replace('\\', '/')
            val dst = toRel.replace('\\', '/')
            if (src in failMoveOf) return false
            val content = files[src] ?: return false
            if (files.containsKey(dst)) return false // 原版 File.Move 在目标已存在时抛 ⇒ 源文件也不动
            files.remove(src)
            files[dst] = content
            moves += src to dst
            return true
        }
    }

    /** 记录每一次询问（原版 `Dialog.ShowPrompt`，Config.cs:564）的文案与次序，并按 [answer] 应答。 */
    private class Asks(private val answer: Boolean = true) {
        val asked = ArrayList<Pair<String, String>>()
        val prompt: (title: String, message: String) -> Boolean = { title, message ->
            asked += title to message
            answer
        }
    }

    private fun bytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    private fun text(b: ByteArray?): String = String(b ?: ByteArray(0), StandardCharsets.UTF_8)

    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.write(p, bytes(content))
    }

    /** 根下**全部普通文件**的「相对路径 → 内容」快照（两次快照相等 = 一个字节都没动）。 */
    private fun tree(root: Path): Map<String, String> =
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .collect(java.util.stream.Collectors.toList())
        }.associateWith { rel -> text(Files.readAllBytes(root.resolve(rel))) }

    private fun saveFolderOn(): SaveConfig = SaveConfig(useSaveFolder = true)

    // ------------------------------------------------------------------
    // 1. 改前钉住（现状）与改后（原版读数）
    // ------------------------------------------------------------------

    /**
     * **现状复现**（修复前的行为）：宿主没有目录面 ⇒ 迁移整段不可达，根目录的
     * `save00.sav` / `global.sav` 原地不动、`sav` 里什么都没有、询问一次也没发生。
     *
     * 这正是 BOARD「引擎缺陷总账」SAVE-01 记的那条：开关为 YES 时 `SavDir` 只剩 `sav`
     * 一条候选（Config.cs:471-481 + `SaveDataProbe.candidatePaths`），旧档还在根目录
     * ⇒ 标题存档列表全空。
     */
    @Test
    fun `迁移前现状：宿主没有目录面就一个字节都不动`() {
        val io = TableOnlyIo(linkedMapOf("save00.sav" to bytes("旧档"), "global.sav" to bytes("全局旧档")))
        val asked = Asks()
        val r = SaveFolderMigration.migrate(io, saveFolderOn(), asked.prompt)

        assertEquals(SaveMigrationOutcome.IoNotFolderCapable, r.outcome)
        assertEquals(emptyList(), r.moved)
        assertFalse(io.exists("sav/save00.sav"), "没搬 ⇒ 文件夹里不该有副本（:577/:583 未被执行）")
        assertTrue(io.exists("save00.sav") && io.exists("global.sav"), "旧档仍在根（今天的错读数）")
        assertTrue(asked.asked.isEmpty(), "不可达的迁移不该弹框")
    }

    /**
     * 同一条夹具换成**带目录面的宿主**（生产 = [RootedSaveFileIo]）后的原版读数：
     * `global.sav`（精确名，:562/:575-577）与 `save` 打头的 sav 档（通配，:563/:579-584）
     * 全部搬进存档文件夹，**文件名不变**、根下不留副本、非候选条目一个都不碰。
     */
    @Test
    fun `原版读数：根目录旧档搬进 sav 且清单与原版逐条对应`() {
        val io = FakeFolderIo(
            LinkedHashMap<String, ByteArray>().apply {
                put("global.sav", bytes("全局旧档"))
                put("save00.sav", bytes("槽零"))
                put("save03.sav", bytes("槽三"))
                put("save1000.sav", bytes("四位槽（原版通配同样命中）"))
                // 逐条对应「不在迁移面」的那几类：
                put("notes.sav", bytes("不以 save 开头"))
                put("savenotes.txt", bytes("不以 .sav 结尾"))
                put("save01.dat", bytes("角色档/变量档走 Program.DatDir，与开关无关"))
                put("emuera.config", bytes("配置也不搬"))
            },
        )
        val asked = Asks()
        val r = SaveFolderMigration.migrate(io, saveFolderOn(), asked.prompt)

        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertEquals(
            listOf("sav/global.sav", "sav/save00.sav", "sav/save03.sav", "sav/save1000.sav"),
            r.moved.sorted(),
            "搬运清单 = global.sav（:577）+ save*.sav（:583），逐条同名落地",
        )
        assertEquals("global.sav" to "sav/global.sav", io.moves.first(), "Config.cs:575-578 先搬 global.sav")
        assertEquals("槽零", text(io.files["sav/save00.sav"]))
        assertFalse(io.exists("save00.sav"), "根目录不得留副本")
        for (keep in listOf("notes.sav", "savenotes.txt", "save01.dat", "emuera.config")) {
            assertTrue(io.exists(keep), "$keep 不该被动（通配不命中）")
        }
        assertEquals(
            listOf(SaveFolderMigration.TITLE_DATA_TRANSFER to SaveFolderMigration.MES_SAV_FOLDER_CREATED),
            asked.asked,
            "只问一次，标题/正文照 :564 的那条 ShowPrompt",
        )
    }

    /** 真磁盘版（[RootedSaveFileIo]，与 `RootedFileOps` 同一个应用可写根）：磁盘清单与原版一致。 */
    @Test
    fun `真根目录迁移后磁盘清单与原版一致`(@TempDir root: Path) {
        write(root, "global.sav", "G")
        write(root, "save00.sav", "A")
        write(root, "SAVE01.SAV", "B") // Windows 目录不分大小写 ⇒ 同样是候选（:563）
        write(root, "data.txt", "T")
        write(root, "sav2/save09.sav", "SUB") // TopDirectoryOnly ⇒ 子目录条目不搬（:563）
        write(root, "dat/chara_x.dat", "C")
        Files.createDirectories(root.resolve("save02.sav")) // 同名**目录**：GetFiles 不返回目录

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), saveFolderOn())
        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertEquals(listOf("sav/SAVE01.SAV", "sav/global.sav", "sav/save00.sav"), r.moved.sorted())
        assertEquals("A", text(Files.readAllBytes(root.resolve("sav/save00.sav"))))
        assertEquals("B", text(Files.readAllBytes(root.resolve("sav/SAVE01.SAV"))), "不改名、不折大小写")
        assertFalse(Files.exists(root.resolve("save00.sav")), "根下不留副本")
        assertFalse(Files.exists(root.resolve("SAVE01.SAV")))
        assertTrue(Files.isRegularFile(root.resolve("data.txt")))
        assertTrue(Files.isRegularFile(root.resolve("sav2/save09.sav")), "子目录条目不搬（TopDirectoryOnly）")
        assertTrue(Files.isRegularFile(root.resolve("dat/chara_x.dat")))
        assertTrue(Files.isDirectory(root.resolve("save02.sav")), "同名目录不是 GetFiles 的条目")
    }

    // ------------------------------------------------------------------
    // 2. 幂等 + 一次性守卫（Config.cs:482）
    // ------------------------------------------------------------------

    /**
     * 幂等：第二次起落 [SaveMigrationOutcome.FolderAlreadyExists]（`:482` 的
     * `!Directory.Exists(SavDir)`），磁盘状态与首次完全一致，询问也不会再发生。
     */
    @Test
    fun `重复执行幂等：第二次落目录已存在且清单不变`(@TempDir root: Path) {
        write(root, "global.sav", "G")
        write(root, "save00.sav", "A")
        write(root, "save05.sav", "E")
        val io = RootedSaveFileIo(root)
        val cfg = SaveConfig.ERATW

        val first = SaveFolderMigration.migrate(io, cfg)
        assertEquals(SaveMigrationOutcome.Completed, first.outcome)
        val afterFirst = tree(root)

        val asked = Asks()
        for (i in 1..3) {
            val again = SaveFolderMigration.migrate(io, cfg, asked.prompt)
            assertEquals(SaveMigrationOutcome.FolderAlreadyExists, again.outcome, "第 ${i + 1} 次仍不该动手（:482）")
            assertEquals(emptyList(), again.moved)
            assertNull(again.notice)
            assertEquals(afterFirst, tree(root), "重复执行的磁盘状态必须一模一样")
        }
        assertTrue(asked.asked.isEmpty(), "目录已在 ⇒ 不该再弹框")
    }

    /**
     * 建目录**先于**判空（`:555` 在 `:562-564` 之前）⇒ 根下没东西时文件夹照样建，
     * 但连框都不弹、也没有任何搬运（`:564` 的 `!num && files.Length == 0` 短路）。
     */
    @Test
    fun `根下没东西时只建目录不弹框`(@TempDir root: Path) {
        write(root, "readme.txt", "没有存档")
        val asked = Asks()
        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), saveFolderOn(), asked.prompt)

        assertEquals(SaveMigrationOutcome.NothingToMove, r.outcome)
        assertTrue(Files.isDirectory(root.resolve("sav")), "Config.cs:555 先建目录")
        assertTrue(asked.asked.isEmpty(), "没东西可搬时原版不弹框（:564 前半）")
        assertEquals(emptyList(), r.moved)
        // 下一次装载：目录已在 ⇒ 永不再问也不搬
        assertEquals(
            SaveMigrationOutcome.FolderAlreadyExists,
            SaveFolderMigration.migrate(RootedSaveFileIo(root), saveFolderOn(), asked.prompt).outcome,
        )
        assertTrue(asked.asked.isEmpty(), "两轮都不该弹框")
    }

    /**
     * 玩家选 No（`:564-567` 的后半）：文件夹保留、文件全不动；而且因为 `:482` 只看目录，
     * **下一次装载也不会再问** ⇒ 存档列表照旧空。这是官方的一次性行为，照抄不修。
     */
    @Test
    fun `选否时文件不动且下一次不再被问（原版一次性）`(@TempDir root: Path) {
        write(root, "global.sav", "G")
        write(root, "save00.sav", "A")
        val io = RootedSaveFileIo(root)
        val cfg = saveFolderOn()
        val declined = Asks(answer = false)

        val r = SaveFolderMigration.migrate(io, cfg, declined.prompt)
        assertEquals(SaveMigrationOutcome.Declined, r.outcome)
        assertTrue(Files.isDirectory(root.resolve("sav")), "文件夹是弹框**前**建的（:555）")
        assertTrue(Files.isRegularFile(root.resolve("save00.sav")), "选 No ⇒ 一条都不搬")
        assertTrue(Files.isRegularFile(root.resolve("global.sav")))
        assertEquals(1, declined.asked.size)

        val askedAgain = Asks()
        assertEquals(SaveMigrationOutcome.FolderAlreadyExists, SaveFolderMigration.migrate(io, cfg, askedAgain.prompt).outcome)
        assertTrue(askedAgain.asked.isEmpty(), "原版没有「再问一次」的通道（:482）")
        assertTrue(Files.isRegularFile(root.resolve("save00.sav")), "旧档永久留在根（官方行为）")
    }

    // ------------------------------------------------------------------
    // 3. 开关为 NO（Config.cs:478-481）
    // ------------------------------------------------------------------

    /**
     * `UseSaveFolder` = NO ⇒ **一个字节都不动**：不建目录、不列举、不弹框
     * （`:478-481` 的 else 把 `SavDir` 定成 ExeDir，`:482` 的守卫因开关为假而整段不进）。
     */
    @Test
    fun `开关为 NO 时一个字节都不动`(@TempDir root: Path) {
        write(root, "global.sav", "G")
        write(root, "save00.sav", "A")
        write(root, "save01.sav", "B")
        write(root, "data.txt", "T")
        val before = tree(root)
        val asked = Asks()

        val off = SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = false)
        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), off, asked.prompt)
        assertEquals(SaveMigrationOutcome.SwitchOff, r.outcome)
        assertEquals(before, tree(root), "开关关着 ⇒ 磁盘状态逐项相等")
        assertFalse(Files.exists(root.resolve("sav")), "NO 时原版不建存档文件夹（Config.cs:478-481）")
        assertTrue(asked.asked.isEmpty())
        // 出厂默认（ConfigData.cs:97 = false）同判
        assertEquals(SaveMigrationOutcome.SwitchOff, SaveFolderMigration.migrate(RootedSaveFileIo(root), SaveConfig.DEFAULT).outcome)
    }

    // ------------------------------------------------------------------
    // 4. 失败方向（Config.cs:556-561 / :568-572 / :586-589）
    // ------------------------------------------------------------------

    /**
     * 建目录失败（真磁盘形态：根下已有同名**普通文件**）：原版 `Dialog.Show(创建文件夹失败,
     * 创建sav文件夹失败)` 后 return ⇒ 一条都不搬、也不询问（`:556-561`）。
     */
    @Test
    fun `建目录失败时只弹框不搬运`(@TempDir root: Path) {
        write(root, "save00.sav", "A")
        write(root, "global.sav", "G")
        write(root, "sav", "占位的普通文件")
        val asked = Asks()

        val r = SaveFolderMigration.migrate(RootedSaveFileIo(root), saveFolderOn(), asked.prompt)
        assertEquals(SaveMigrationOutcome.FolderCreateFailed, r.outcome)
        assertEquals(
            SaveMigrationDialog(SaveFolderMigration.TITLE_FOLDER_CREATION_FAILURE, SaveFolderMigration.MES_FAILED_CREATE_SAV_FOLDER),
            r.notice,
        )
        assertEquals(emptyList(), r.moved)
        assertTrue(asked.asked.isEmpty(), ":556-561 的出口在建目录之后、询问之前")
        assertEquals("占位的普通文件", text(Files.readAllBytes(root.resolve("sav"))), "同名文件不该被覆盖或删掉")
        assertTrue(Files.isRegularFile(root.resolve("save00.sav")), "失败 ⇒ 旧档仍在根")
        assertTrue(Files.isRegularFile(root.resolve("global.sav")))
    }

    /** 同上那条出口，由宿主直接报 false（`Files.createDirectories` 的异常收敛在实现里）。 */
    @Test
    fun `建目录返回 false 走同一条弹框出口`() {
        val io = FakeFolderIo(LinkedHashMap<String, ByteArray>().apply { put("save00.sav", bytes("A")) }, createFails = true)
        val r = SaveFolderMigration.migrate(io, saveFolderOn())
        assertEquals(SaveMigrationOutcome.FolderCreateFailed, r.outcome)
        assertNotNull(r.notice)
        assertEquals(listOf("save00.sav"), io.files.keys.toList(), "没搬 ⇒ 表里还是根路径")
        assertFalse(io.folderExists)
        assertTrue(io.moves.isEmpty())
    }

    /**
     * **搬到一半失败**（`:586-589`）：一个 try 包住整批 ⇒ 已搬的**不回滚**、剩下的留在根、
     * 只弹一条 `数据移动失败 / sav文件移动失败`；不重试、不抛给调用方（开局照常进行）。
     */
    @Test
    fun `搬到一半失败时已搬不回滚剩余不重试`() {
        val io = FakeFolderIo(
            LinkedHashMap<String, ByteArray>().apply {
                put("global.sav", bytes("G"))
                put("save00.sav", bytes("A"))
                put("save01.sav", bytes("B"))
                put("save02.sav", bytes("C"))
            },
            failMoveOf = setOf("save01.sav"),
        )
        val r = SaveFolderMigration.migrate(io, saveFolderOn())

        assertEquals(SaveMigrationOutcome.MoveFailed, r.outcome)
        assertEquals("save01.sav", r.failedFile, "第一个失败点即止")
        assertEquals(listOf("sav/global.sav", "sav/save00.sav"), r.moved, "已搬的算数，不回滚（:586-589）")
        assertEquals(
            mapOf("save01.sav" to "B", "save02.sav" to "C"),
            io.files.filterKeys { !it.contains('/') }.mapValues { text(it.value) },
            "剩余条目留在根（save02 也不再尝试）",
        )
        assertNull(io.files["save00.sav"], "已搬走的条目在根已不存在")
        assertEquals(
            SaveMigrationDialog(SaveFolderMigration.TITLE_DATA_TRANSFER_FAILURE, SaveFolderMigration.MES_FAILED_MOVE_SAV_FILES),
            r.notice,
        )
        assertEquals(2, io.moves.size, "只发生两次真移动（global + save00），失败那次不计数")
        // 文件夹还在 ⇒ 下一次装载直接落 FolderAlreadyExists，剩余条目永久留在根（官方行为）
        assertEquals(SaveMigrationOutcome.FolderAlreadyExists, SaveFolderMigration.migrate(io, saveFolderOn()).outcome)
    }

    /**
     * 目标同名已存在时**不覆盖**：原版 `File.Move` 抛 `IOException` ⇒ 同一条失败出口。
     * （原版没有冲突处理分支：它只在「刚建的空文件夹」里落地，撞上同名就是整批中止。）
     */
    @Test
    fun `目标已存在时不覆盖按搬运失败处理`() {
        val io = FakeFolderIo(
            LinkedHashMap<String, ByteArray>().apply {
                put("save00.sav", bytes("根目录旧档"))
                put("sav/save00.sav", bytes("已存在的同名档"))
            },
        )
        val r = SaveFolderMigration.migrate(io, saveFolderOn())
        assertEquals(SaveMigrationOutcome.MoveFailed, r.outcome)
        assertEquals("已存在的同名档", text(io.files["sav/save00.sav"]), "绝不覆盖目标")
        assertEquals("根目录旧档", text(io.files["save00.sav"]))
        assertTrue(io.moves.isEmpty())
    }

    /** 询问后文件夹被外部删掉（`:568-572`）：弹 `数据移动失败 / 创建了sav文件夹但没有找到…`，一条都不搬。 */
    @Test
    fun `询问后文件夹消失走缺目录出口`() {
        val io = FakeFolderIo(
            LinkedHashMap<String, ByteArray>().apply {
                put("global.sav", bytes("G"))
                put("save00.sav", bytes("A"))
            },
            vanishingFolder = true,
        )
        val r = SaveFolderMigration.migrate(io, saveFolderOn())
        assertEquals(SaveMigrationOutcome.FolderMissing, r.outcome)
        assertEquals(
            SaveMigrationDialog(SaveFolderMigration.TITLE_DATA_TRANSFER_FAILURE, SaveFolderMigration.MES_MISSING_SAV_FOLDER),
            r.notice,
        )
        assertEquals(emptyList(), r.moved)
        assertTrue(io.moves.isEmpty(), "复检之后就再没有移动")
        assertTrue(io.exists("save00.sav") && io.exists("global.sav"), "旧档仍在根")
    }

    // ------------------------------------------------------------------
    // 5. 生产可达（GameSession 第 8 步同形）
    // ------------------------------------------------------------------

    /**
     * 存档层装配即迁移：`GameSession` 第 8 步就是
     * `FileSystemOps(store, RootedSaveFileIo(gameRoot, rooted.fileOps), saveConfig)`
     * （`core/proc/GameSession.kt:402`），而 `saveConfig.useSaveFolder` 与它同源
     * （`:347-351` 的 `effective.bool(ConfigKey.UseSaveFolder)`）⇒ 构造这一步就把旧档搬完，
     * 标题列表随后只认 `sav` 一条（`SaveDataProbe.candidatePaths` 的单候选口径）。
     */
    @Test
    fun `FileSystemOps 构造期就完成迁移（GameSession 第 8 步同形）`(@TempDir root: Path) {
        write(root, "global.sav", "G")
        write(root, "save00.sav", "A")
        val store = VariableStore()
        val ops = FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

        assertEquals(SaveMigrationOutcome.Completed, ops.saveFolderMigration.outcome)
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")))
        assertFalse(Files.exists(root.resolve("save00.sav")))
        // 记录面（宿主读这一份决定要不要补弹框）
        val log = SaveFolderMigrationLog.of(store)
        assertNotNull(log)
        assertEquals(SaveMigrationOutcome.Completed, log.latest?.outcome)
        assertEquals(1, log.movedCount, "真搬动过才计数")
        // 搬完的槽位照旧可写可读（同一份 SaveConfig ⇒ 探测与落盘同源）
        assertTrue(ops.saveData(0, "新描述"), "SAVEDATA 写 sav/save00.sav")
        assertTrue(Files.isRegularFile(root.resolve("sav/save00.sav")))
        // 重建会话（第二个 FileSystemOps，同根）⇒ 目录已在，幂等且不重复计数
        val store2 = VariableStore()
        assertEquals(
            SaveMigrationOutcome.FolderAlreadyExists,
            FileSystemOps(store2, RootedSaveFileIo(root), SaveConfig.ERATW).saveFolderMigration.outcome,
        )
        assertEquals(0, SaveFolderMigrationLog.of(store2)?.movedCount, "没搬就不计数")
    }

    /** 开关关着（出厂默认 `ConfigData.cs:97` = false）时装配存档层不碰磁盘。 */
    @Test
    fun `开关关闭时装配存档层不碰磁盘`(@TempDir root: Path) {
        write(root, "save00.sav", "A")
        val before = tree(root)
        val ops = FileSystemOps(VariableStore(), RootedSaveFileIo(root), SaveConfig(systemSaveInBinary = true))
        assertEquals(SaveMigrationOutcome.SwitchOff, ops.saveFolderMigration.outcome)
        assertEquals(before, tree(root))
        assertFalse(Files.exists(root.resolve("sav")))
    }

    /** 假体宿主（没有目录面）装配存档层不炸、也不伪造「搬成功」。 */
    @Test
    fun `无目录面的宿主装配存档层只登记结局`() {
        val io = TableOnlyIo(linkedMapOf("save00.sav" to bytes("A")))
        val ops = FileSystemOps(VariableStore(), io, SaveConfig.ERATW)
        assertEquals(SaveMigrationOutcome.IoNotFolderCapable, ops.saveFolderMigration.outcome)
        assertNull(ops.saveFolderMigration.notice)
        assertTrue(io.exists("save00.sav"), "假体没有目录面 ⇒ 不动它的表")
    }

    // ------------------------------------------------------------------
    // 6. 口径细节
    // ------------------------------------------------------------------

    /** 通配本体（`save*.sav`，:563/:579）：`*` 可匹配空串 ⇒ `save.sav` 命中；前缀/后缀缺一即不命中。 */
    @Test
    fun `通配判定与原版一致`() {
        val hits = listOf("save.sav", "save00.sav", "save1000.sav", "SAVE07.SAV", "saveX.sav")
        val misses = listOf("notes.sav", "save01.txt", "sav00.sav", "global.sav", ".sav", "save", "s.sav", "save.sav.bak")
        for (n in hits) assertTrue(SaveFolderMigration.matchesSavePattern(n), "$n 应命中（Config.cs:563）")
        for (n in misses) assertFalse(SaveFolderMigration.matchesSavePattern(n), "$n 不该命中")
        assertEquals("global.sav", SaveFolderMigration.GLOBAL_FILE, "global 走精确名（:562/:575），不吃通配")
        assertEquals("sav", SaveFolderMigration.SAV_FOLDER, "文件夹名原版写死（:470 / :474-476）")
    }

    /**
     * 迁移的每个动作都锁在这条根内（`RootedFileOps` 的应用可写根口径：同一个 `resolve`，
     * 越界一律拒绝，SaveFileIo.kt 的 `resolve` 段）。
     */
    @Test
    fun `迁移的所有动作都锁在应用可写根内`(@TempDir work: Path) {
        val gameRoot = Files.createDirectories(work.resolve("game"))
        val outside = work.resolve("secret.sav")
        Files.write(outside, bytes("根外"))
        write(gameRoot, "save00.sav", "A")

        val io = RootedSaveFileIo(gameRoot)
        assertEquals(listOf("save00.sav"), io.rootSaveFileNames(), "候选只来自根的直接子项")
        assertFalse(io.moveToFile("save00.sav", "../../escape.sav"), "目标越界 ⇒ 拒绝")
        assertFalse(io.moveToFile("../secret.sav", "sav/x.sav"), "源越界 ⇒ 拒绝")
        assertFalse(io.moveToFile("save00.sav", "sav/../../escape2.sav"), "规范化后越界同样拒绝")
        assertFalse(Files.exists(work.resolve("escape.sav")), "根外没被写出任何东西")
        assertFalse(Files.exists(work.resolve("escape2.sav")))
        assertTrue(Files.isRegularFile(outside), "根外文件原样")
        assertEquals("根外", text(Files.readAllBytes(outside)))
        assertTrue(Files.isRegularFile(gameRoot.resolve("save00.sav")), "越界失败不该把源文件搬走")

        val r = SaveFolderMigration.migrate(io, saveFolderOn())
        assertEquals(SaveMigrationOutcome.Completed, r.outcome)
        assertEquals(setOf("sav/save00.sav"), tree(gameRoot).keys)
        assertEquals(setOf("secret.sav", "game/sav/save00.sav"), tree(work).keys, "根外清单只剩自己那条")
    }

    /** 落点与 `SAVEDATA` / `LOADGLOBAL` 同源（都拼 [SaveConfig.saveFolderPrefix]，Config.cs:471-477）。 */
    @Test
    fun `目录名与落点跟 SaveConfig 同源`(@TempDir root: Path) {
        write(root, "save00.sav", "A")
        write(root, "global.sav", "G")
        val cfg = saveFolderOn()
        assertEquals("sav/save00.sav", cfg.normalPath(0), "迁移落点必须等于 SAVEDATA 的落点")
        assertEquals("sav/global.sav", cfg.globalPath())
        assertEquals(SaveMigrationOutcome.Completed, SaveFolderMigration.migrate(RootedSaveFileIo(root), cfg).outcome)
        assertEquals("A", text(Files.readAllBytes(root.resolve(cfg.normalPath(0)))), "搬完 SAVEDATA 认的就是这条路径")
        assertEquals("G", text(Files.readAllBytes(root.resolve(cfg.globalPath()))))
    }
}
