package com.eratw.port.core.save

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RootedSaveFileIo.write] 的原子替换钉子（安卓 LMK 杀进程下的截断档防护）。
 *
 * 实现契约（见 [RootedSaveFileIo.write] KDoc）：同目录临时文件 → `force(true)` →
 * 换名（`ATOMIC_MOVE` + `REPLACE_EXISTING`，平台不支持或拒绝覆盖时退回普通 replace move）。
 * 这里只钉**对外可观测语义**：
 * 1. 新档写入后读回字节一致，且成功后不留临时文件；
 * 2. 覆盖已有档时内容完全替换（旧档更长也不残留尾部），目标已存在不得误报失败；
 * 3. 越界路径仍返回 false 且根内根外都不产生文件；
 * 4. 父目录缺失自动创建；
 * 5. 写失败返回 false，既有目标不被破坏，临时文件被清掉。
 *
 * 用 `Files.createTempDirectory` + finally 递归清理（不引入 JUnit4 的 TemporaryFolder）。
 */
class SaveFileIoAtomicWriteTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun <T> withTempRoot(block: (Path) -> T): T {
        val root = Files.createTempDirectory("eratw-atomic")
        try {
            return block(root)
        } finally {
            Files.walk(root).use { s ->
                s.sorted(Comparator.reverseOrder<Path>()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /** [dir] 下的直接子项名（升序，仅求可复现）。 */
    private fun dirEntries(dir: Path): List<String> = Files.list(dir).use { s ->
        s.map { it.fileName.toString() }.sorted().collect(Collectors.toList())
    }

    /** 递归找出残留的临时文件（前缀/后缀与 [RootedSaveFileIo.write] 的私有常量同源）。 */
    private fun leftoverTemps(root: Path): List<String> = Files.walk(root).use { s ->
        s.filter { p ->
            val n = p.fileName.toString()
            n.startsWith(".eratw-save-") && n.endsWith(".tmp")
        }.map { it.toString() }.collect(Collectors.toList())
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `新文件写入后读回字节一致且不留临时文件`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val payload = byteArrayOf(0x45, 0x52, 0x41, 0x00, 0x7F, -1)

        assertTrue(io.write("save00.sav", payload), "首次写入应成功")
        assertContentEquals(payload, io.read("save00.sav"), "读回字节必须与写入一致")
        assertEquals(listOf("save00.sav"), dirEntries(root), "成功后目录里只能有目标文件")
        assertEquals(emptyList(), leftoverTemps(root), "成功后不得残留临时文件")
    }

    @Test
    fun `覆盖已有文件时内容完全替换且旧内容不残留`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val old = ByteArray(4096) { (it % 251).toByte() }
        assertTrue(io.write("save00.sav", old), "先落一份大档")

        val new = byteArrayOf(1, 2, 3)
        assertTrue(io.write("save00.sav", new), "目标已存在时也必须替换成功，不得被 ATOMIC_MOVE 误报失败")
        assertContentEquals(new, io.read("save00.sav"), "覆盖后长度必须收缩，旧档尾部不得残留")
        assertContentEquals(new, Files.readAllBytes(root.resolve("save00.sav")), "直接读盘也应是新内容")
        assertEquals(listOf("save00.sav"), dirEntries(root), "替换后不得残留临时文件")
    }

    @Test
    fun `越界路径返回 false 且不产生任何文件`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)

        assertFalse(io.write("../escape.sav", byteArrayOf(1)), "越界路径必须拒绝")
        assertFalse(Files.exists(root.parent.resolve("escape.sav")), "根外不得出现目标文件")
        assertFalse(Files.exists(root.resolve("escape.sav")), "根内不得出现同名残留")
        assertEquals(emptyList(), dirEntries(root), "越界时连临时文件都不该落进根里")
        assertEquals(emptyList(), leftoverTemps(root), "越界路径不得留下临时文件")
    }

    @Test
    fun `父目录不存在时自动创建`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)

        assertTrue(io.write("sav/deep/save00.sav", byteArrayOf(9, 8)), "父目录缺失应自动创建后写入")
        assertContentEquals(byteArrayOf(9, 8), io.read("sav/deep/save00.sav"), "嵌套路径读回一致")
        assertEquals(listOf("save00.sav"), dirEntries(root.resolve("sav/deep")), "嵌套目录里不得残留临时文件")
    }

    @Test
    fun `写入失败返回 false 且清理临时文件`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val blocker = root.resolve("save00.sav")
        // 目标被一个非空目录占位：换名必然失败（Windows AccessDenied / POSIX ENOTDIR 族）。
        Files.createDirectories(blocker)
        Files.writeString(blocker.resolve("keep.txt"), "x")

        assertFalse(io.write("save00.sav", byteArrayOf(1, 2)), "目标是目录 → 写失败 → false，不抛异常")
        assertTrue(Files.isDirectory(blocker), "失败不得破坏既有目录")
        assertEquals(listOf("keep.txt"), dirEntries(blocker), "目录内容必须原样")
        assertEquals(emptyList(), leftoverTemps(root), "失败路径必须清掉临时文件")
    }

    // ------------------------------------------------------------------
    // 退路（2026-11-01 玩家「不能覆盖旧存档」）
    // ------------------------------------------------------------------

    /**
     * 快路的峰值占用是「旧档 + 临时文件同时存在」，覆盖 N 字节要 2N；原版原地截断只要 N。
     * 存储吃紧时快路抛 IOException，旧实现直接折成 false ⇒ 屏上
     * `SAVEDATA命令在存档时发生了意料之外的错误`，而存到空槽照样成功。
     *
     * 这里钉退路本体：**它必须真能把字节写对**（新建 + 覆盖两种），否则「退一步」只是
     * 把失败推后一层。
     */
    @Test
    fun `退路原地覆盖写出正确字节且旧档尾部不残留`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val target = root.resolve("save07.sav")

        assertTrue(io.writeInPlace(target, byteArrayOf(5, 6, 7)), "退路必须能新建文件")
        assertContentEquals(byteArrayOf(5, 6, 7), Files.readAllBytes(target), "新建后字节一致")

        val old = ByteArray(8192) { (it % 97).toByte() }
        Files.write(target, old)
        assertTrue(io.writeInPlace(target, byteArrayOf(1)), "退路必须能覆盖已存在文件")
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(target), "覆盖后长度必须收缩，旧档尾部不得残留")
    }

    /**
     * 正常设备**不得**因为这条退路而永久降级：快路成功时不许写「退路」留痕，
     * 也不许多留任何文件。否则「原子换名」这层崩溃安全等于被悄悄摘掉。
     */
    @Test
    fun `快路成功时不写退路留痕`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        SaveDiagnostics.clear()

        assertTrue(io.write("save00.sav", ByteArray(1024) { 1 }), "首次写入应成功")
        assertTrue(io.write("save00.sav", ByteArray(2048) { 2 }), "覆盖应成功")
        assertEquals(listOf("save00.sav"), dirEntries(root), "快路成功不得多留文件")

        val trail = SaveDiagnostics.snapshot()
        assertEquals(emptyList(), trail.filter { it.contains("退路") }, "快路成功时不该出现退路留痕：$trail")
    }
}
