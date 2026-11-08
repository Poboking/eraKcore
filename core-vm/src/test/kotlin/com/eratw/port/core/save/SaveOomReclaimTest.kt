package com.eratw.port.core.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SAVEDATA` 的堆贴顶补救链（[FileSystemOps.reclaimMemory]）。
 *
 * ## 为什么单独建一份文件
 *
 * 这条链的触发条件是 `OutOfMemoryError` —— 在 JVM 单测里**造不出真实的堆贴顶**
 * （要么把测试进程弄死，要么得开一个 `-Xmx` 极小的子进程，两者都不该进常规闸）。
 * 所以这里用「[SaveFileIo.write] 第一次抛 OOM」来模拟：被测的是**编排**
 * （接住 OOM → 求援 → 重试一次 → 不再循环），而不是「内存真的满了」这件事本身。
 *
 * 真实触发条件与设备证据见 [FileSystemOps.reclaimMemory] 的 KDoc
 * （2026-11-02 玩家「玩久了存档失败、重启就好」+ `aa5a956c` 的 167 MiB 图形池实证）。
 *
 * ## 断言的是哪几条契约
 *
 * 1. 每次存档先走**预防档**（`urgent=false`），堆不高时实现方返回 0 就什么都不做；
 * 2. OOM → **求援档**（`urgent=true`）被调用一次 → 重试 → 成功返回 true；
 * 3. 求援交出 0（没腾出空间）→ **不重试**（再试只会再 OOM）；
 * 4. 未接线（`reclaimMemory == null`）→ 与补丁前逐字相同：一次失败、返回 false；
 * 5. 非 OOM 的异常 → 只有预防档，**不惊动求援**（普通 IO 失败不该回收图形缓冲）；
 * 6. 重试再贴顶 → 只重试一次，不循环；
 * 7. `SAVEGLOBAL` / `SAVECHARA` **没有**求援链 ⇒ 落盘 OOM 折回原文案的 `CodeEE`，不上抛
 *    （`SaveFileIo` 生产实现现在会把 `Error` 上抛，折回 false 的位置在 `FileSystemOps` 的
 *    `writeTreatOomAsFailure`）。
 *
 * 每个用例都用 `calls` 记下每次调用的 `urgent` 值，断言的是**两档的次序与次数**，
 * 而不是笼统的调用数 —— 预防档与求援档的语义完全不同，混在一起数会掩盖回归。
 */
class SaveOomReclaimTest {

    /**
     * 按脚本抛异常的假 IO：[failures] 依次出队，队列空后正常落盘。
     *
     * [writes] 记的是**进入 write 的次数**（含抛异常那次），用来断言「重试发生了几次」。
     */
    private class ScriptedIo(private val failures: MutableList<Throwable>) : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        var writes = 0

        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            writes++
            if (failures.isNotEmpty()) throw failures.removeAt(0)
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path) != null
    }

    /** 与语料出厂一致的二进制 + 压缩档（`SaveConfig.ERATW` 的同形子集）。 */
    private fun binary() = SaveConfig(systemSaveInBinary = true, zipSaveData = true)

    /** 环形缓冲是进程级单例：每个用例从空表开始，免得读到上一条用例的留痕。 */
    @BeforeTest
    fun clearDiagnostics() {
        SaveDiagnostics.clear()
    }

    @Test
    fun `堆贴顶时先求援回收再重试一次，成功返回 true`() {
        val io = ScriptedIo(mutableListOf(OutOfMemoryError("模拟堆贴顶")))
        val cfg = binary()
        val fs = FileSystemOps(VariableStore(), io, cfg)
        val calls = mutableListOf<Boolean>()
        fs.reclaimMemory = { urgent ->
            calls.add(urgent)
            8L * 1024 * 1024
        }

        assertTrue(fs.saveData(0, "oom-retry"), "回收腾出空间后，重试应当成功")
        assertEquals(listOf(false, true), calls, "先预防档、后求援档，各一次")
        assertEquals(2, io.writes, "落盘应进入两次（首次贴顶 + 重试成功）")
        assertTrue(io.files.containsKey(cfg.normalPath(0)), "存档文件应当真的写出来")
        assertTrue(SaveDiagnostics.snapshot().any { it.contains("堆贴顶") }, "留痕要写明是堆贴顶，而不是普通写失败")
    }

    @Test
    fun `求援腾不出空间时不重试，按原版口径失败`() {
        val io = ScriptedIo(mutableListOf(OutOfMemoryError("模拟堆贴顶")))
        val fs = FileSystemOps(VariableStore(), io, binary())
        val calls = mutableListOf<Boolean>()
        fs.reclaimMemory = { urgent ->
            calls.add(urgent)
            0L
        }

        assertFalse(fs.saveData(0, "oom-no-reclaim"), "回收量为 0 时不该假装成功")
        assertEquals(listOf(false, true), calls, "两档都要问一次 —— 求援档靠返回值判断能不能重试")
        assertEquals(1, io.writes, "腾不出空间就不许重试 —— 再试只会再 OOM")
    }

    @Test
    fun `未接线求援时行为与补丁前一致`() {
        val io = ScriptedIo(mutableListOf(OutOfMemoryError("模拟堆贴顶")))
        val fs = FileSystemOps(VariableStore(), io, binary())
        // 刻意不设 reclaimMemory：装置 / 纯单测的形态。

        assertFalse(fs.saveData(0, "oom-unwired"))
        assertEquals(1, io.writes, "未接线时不得凭空多出一次落盘")
    }

    @Test
    fun `非 OOM 异常只走预防档，不惊动求援`() {
        val io = ScriptedIo(mutableListOf(java.io.IOException("磁盘满")))
        val fs = FileSystemOps(VariableStore(), io, binary())
        val calls = mutableListOf<Boolean>()
        fs.reclaimMemory = { urgent ->
            calls.add(urgent)
            8L * 1024 * 1024
        }

        assertFalse(fs.saveData(0, "io-error"))
        assertEquals(
            listOf(false),
            calls,
            "普通 IO 失败只该有预防档 —— 回收图形缓冲解决不了磁盘满",
        )
        assertEquals(1, io.writes)
    }

    @Test
    fun `重试再贴顶只重试一次，不循环`() {
        val io = ScriptedIo(
            mutableListOf(OutOfMemoryError("第一次"), OutOfMemoryError("第二次")),
        )
        val fs = FileSystemOps(VariableStore(), io, binary())
        val calls = mutableListOf<Boolean>()
        fs.reclaimMemory = { urgent ->
            calls.add(urgent)
            8L * 1024 * 1024
        }

        assertFalse(fs.saveData(0, "oom-twice"), "两次都贴顶就该按失败收口")
        assertEquals(2, io.writes, "重试只许一次")
        assertEquals(listOf(false, true), calls, "预防一次 + 求援一次，不再循环")
    }

    // ------------------------------------------------------------------
    // SAVEGLOBAL / SAVECHARA：这两条链没有求援重试，OOM 必须折成原文案
    // ------------------------------------------------------------------

    /**
     * `SAVEGLOBAL` 落盘贴顶 ⇒ 折成 [FileSystemOps.MES_SAVE_GLOBAL] 的 `CodeEE`，
     * 不把 `OutOfMemoryError` 抛给脚本层。
     *
     * 存在的理由：`SaveFileIo.write`（生产实现 `RootedSaveFileIo`）现在把 `Error` **上抛**
     * —— 快路 OOM 若继续往下走退路，第一句就是 `TRUNCATE_EXISTING`，等于为一次注定再 OOM
     * 的写去截断玩家已有的好档。普通档靠 [FileSystemOps.saveData] 的求援重试链接住这个
     * 上抛；全局档/角色档没有那条链，这里钉的就是「上抛之后在本层折回 false」。
     */
    @Test
    fun `SAVEGLOBAL 落盘贴顶折成原文案，不上抛 Error`() {
        val io = ScriptedIo(mutableListOf(OutOfMemoryError("模拟堆贴顶")))
        val fs = FileSystemOps(VariableStore(), io, binary())

        val e = assertFailsWith<CodeEE> { fs.saveGlobal() }
        assertEquals(FileSystemOps.MES_SAVE_GLOBAL, e.message, "文案须与磁盘满同一条（改动前后逐字相同）")
        assertEquals(1, io.writes, "全局档不求援、不重试，只落一次盘")
    }

    /** `SAVECHARA` 落盘贴顶：同 `SAVEGLOBAL` 那条，但文案是带目标路径的那一条。 */
    @Test
    fun `SAVECHARA 落盘贴顶折成原文案，不上抛 Error`() {
        val io = ScriptedIo(mutableListOf(OutOfMemoryError("模拟堆贴顶")))
        val store = VariableStore().also { s ->
            s.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス", maxbase = mapOf(0 to 10L)))
            s.addCharacter(1L)
        }

        val e = assertFailsWith<CodeEE> { FileSystemOps(store, io, binary()).saveChara(listOf(0L), "X", "desc") }
        assertTrue(
            e.message!!.startsWith(FileSystemOps.MES_SAVE_CHARA.substringBefore("{0}")),
            "实为 ${e.message}",
        )
        assertTrue(e.message!!.contains("chara_X.dat"), "写失败要能诊断到落点：${e.message}")
        assertEquals(1, io.writes, "角色档同样不求援、不重试")
    }
}
