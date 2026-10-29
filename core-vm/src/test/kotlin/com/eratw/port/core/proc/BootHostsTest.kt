package com.eratw.port.core.proc

import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 默认宿主面的行为：根目录映射、`ENUMFILES` 相对路径、越界拒绝、存档槽探测。
 *
 * 这些能力是 `GCREATEFROMFILE` / `ENUMFILES` / `CHKDATA` 的前置（函数层的宿主依赖见
 * `GraphicsMethods.kt:90`、`HostMethods.kt:48`）；没有它们，资源管理脚本一越过常量
 * 解析就会停在「需要宿主文件能力」。
 */
class BootHostsTest {

    private fun tempRoot(): Path = Files.createTempDirectory("eratw-boot2-host")

    private fun deleteTree(root: Path) {
        val all = Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
        for (p in all) Files.deleteIfExists(p)
    }

    @Test
    fun `相对路径与反斜杠都能读 枚举返回 root 相对路径`() {
        val root = tempRoot()
        try {
            Files.createDirectories(root.resolve("resources"))
            Files.writeString(root.resolve("resources/a.xml"), "<a/>")
            Files.writeString(root.resolve("resources/b.txt"), "txt")
            Files.createDirectories(root.resolve("resources/sub"))
            Files.writeString(root.resolve("resources/sub/c.xml"), "<c/>")

            val ops = RootedFileOps(root)
            assertEquals("<a/>", ops.readText("resources/a.xml"))
            assertEquals("<a/>", ops.readText("resources\\a.xml"))
            assertTrue(ops.exists("resources"))
            assertTrue(ops.exists("resources/a.xml"))

            assertEquals(listOf("resources/a.xml"), ops.list("resources", "*.xml", recursive = false))
            assertEquals(
                listOf("resources/a.xml", "resources/sub/c.xml"),
                ops.list("resources", "*.xml", recursive = true),
            )
            // 目录不存在 → 空表（ENUMFILES 的 -1 由函数层判 Directory.Exists 得出）
            assertTrue(ops.list("missing", "*.xml", recursive = false).isEmpty())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `越界路径一律视为不存在`() {
        val root = tempRoot()
        try {
            Files.writeString(root.resolve("inside.txt"), "ok")
            val ops = RootedFileOps(root)
            assertNull(ops.readText("../outside.txt"))
            assertNull(ops.readText("/etc/passwd"))
            assertFalse(ops.exists("../outside.txt"))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `RootedBuiltinHost 提供时钟 文件与无存档时的 CHKDATA 结果`() {
        val root = tempRoot()
        try {
            Files.writeString(root.resolve("note.txt"), "hello")
            val host = RootedBuiltinHost(root) { 42L }

            assertEquals(42L, host.clockMillis())
            assertEquals("hello", host.fileOps.readText("note.txt"))
            assertEquals("hello", String(assertNotNull(host.readFileBytes("note.txt")), Charsets.UTF_8))

            val check = assertNotNull(host.checkSaveData(0L, SaveFileKind.NORMAL))
            assertEquals(EraDataState.FILENOTFOUND, check.state)
            assertEquals("----", check.mes)

            // 存档名口径 save{index:00}.sav（VariableEvaluator.cs:2023-2032）。本临时根没有
            // emuera.config ⇒ UseSaveFolder = 注册默认 false（ConfigData.cs:97）⇒ SavDir = ExeDir，
            // 只认根目录那一条（Config.cs:478-481）；命中真实档返回 null（宿主只判存在性，
            // GAME_ERROR/VIRSION_ERROR/OK 三个分支要真实 gameBase，归函数层补全）。
            Files.writeString(root.resolve("save00.sav"), "x")
            assertNull(host.checkSaveData(0L, SaveFileKind.NORMAL))
            Files.createDirectories(root.resolve("sav"))
            Files.writeString(root.resolve("sav/save01.sav"), "x")
            // 组长接线（M4-L l-savefolder）：开关关着时 sav/ 里的同名档**不算**存在 ——
            // 原版靠 Config.SetConfig 的 File.Move 迁移旧档（Config.cs:482-485 + :551-590），
            // 不靠「两边都读一遍」。
            val offSide = assertNotNull(host.checkSaveData(1L, SaveFileKind.NORMAL))
            assertEquals(EraDataState.FILENOTFOUND, offSide.state)
            assertEquals("----", offSide.mes)

            // 反向读数：写 emuera.config 打开开关后重新装配宿主 ⇒ 只认 sav/，根目录那档落空
            Files.writeString(root.resolve("emuera.config"), "USE SAV FOLDER:YES\n")
            val on = RootedBuiltinHost(root, clock = { 42L })
            assertTrue(on.useSaveFolder, "emuera.config 的 YES 必须经三层链进到条目现值")
            assertNull(on.checkSaveData(1L, SaveFileKind.NORMAL))
            val onSide = assertNotNull(on.checkSaveData(0L, SaveFileKind.NORMAL))
            assertEquals(EraDataState.FILENOTFOUND, onSide.state)
        } finally {
            deleteTree(root)
        }
    }

    /**
     * 真机开局停在 `RM_タイトル画像用意 行7`（2026-10-21 vivo V2199A，
     * `执行步数超过上限(5000001)，疑似死循环`），而 `GCREATEFROMFILE` 的 ContentDir 前缀
     * 已按 spec 接上（`FunctionMethodCreator.cs:6890-6894` + `Program.cs:219-221`）。
     * 这条用例把剩下的分叉一次问清：**真实游戏根下，宿主到底读不读得到 `resources/` 里的字节**，
     * 以及生产实际用的 `ConsoleBuiltinHost` 包装层有没有把 `readFileBytes` 委托掉
     * （Kotlin 接口委托对**带默认实现的成员**是否转发到被委托对象，是本仓已踩过两次的形状）。
     *
     * 语料缺失（`ERATW_GAMEDIR` / `ERATW_CORPUS` 都没导）时整条跳过，与 `WebpDecoderTest`
     * 的真实语料用例同一口径。
     */
    @Test
    fun `真实游戏根下 resources 图片读得到且包装层不丢二进制读`() {
        val gameRoot = realGameRoot() ?: return
        val rel = "resources/title.webp"
        val abs = gameRoot.resolve(rel)
        assertTrue(Files.isRegularFile(abs), "语料缺 $abs（本用例按真机那张 1041x557 标题图取证）")
        val size = Files.size(abs).toInt()

        val rooted = RootedBuiltinHost(gameRoot)
        val bytes = assertNotNull(rooted.readFileBytes(rel), "$rel 读不到字节 ⇒ 宿主根或 resolvePath 有问题")
        assertEquals(size, bytes.size, "$rel 字节数")
        // 裸名按原版口径就是读不到（PC 上同样落在 ExeDir 找不到 ⇒ 函数返回 0），钉住前缀确实生效
        assertNull(rooted.readFileBytes("title.webp"), "裸名不该在游戏根命中")

        val console = ConsoleBuiltinHost(gameRoot)
        val wrapped = assertNotNull(
            console.readFileBytes(rel),
            "ConsoleBuiltinHost 没把 readFileBytes 委托给 RootedBuiltinHost ⇒ 真机上等于没装解码器",
        )
        assertEquals(size, wrapped.size, "ConsoleBuiltinHost 的 $rel 字节数")
    }

    private fun realGameRoot(): Path? {
        System.getenv("ERATW_GAMEDIR")?.let { c ->
            val p = runCatching { Path.of(c) }.getOrNull()
            if (p != null && Files.isDirectory(p)) return p
        }
        System.getenv("ERATW_CORPUS")?.let { c ->
            val p = runCatching { Path.of(c) }.getOrNull()?.parent
            if (p != null && Files.isDirectory(p)) return p
        }
        return null
    }
}
