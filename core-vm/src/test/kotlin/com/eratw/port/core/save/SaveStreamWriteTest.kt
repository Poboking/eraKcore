package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.VariableRegistry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OOM-B（2026-11-08）**存档流式落盘**的定向钉子。
 *
 * 旧的写法是「payload → gzip 副本 → 容器 BAOS → toByteArray」四份并存；流式口把
 * 16 字节头 + GZip 直接写进目标文件（生产 = 同目录临时文件），只留 payload 一份
 * （逐字节幂等比对必须物化它）。本文件钉的是**优化不改语义**：
 * 1. [SaveFormat.writeTo] 与旧 [SaveFormat.write] 产物逐字节相同（压缩 / 未压缩 / 幂等短路三态）；
 * 2. [SaveCodec.writeTo] 与 [SaveCodec.write] 同字节；
 * 3. [RootedSaveFileIo] 流式写与字节写的落盘结果相同、不留临时文件；
 * 4. producer 自身抛 Exception ⇒ false 且**不回落原地截断**（旧档原样保留）；
 * 5. producer 抛 `OutOfMemoryError` ⇒ 上抛（`Error` 契约不变）且旧档不动；
 * 6. 接口默认实现把 producer 字节转交旧 `write`（既有假体不破坏）。
 *
 * 字节口径的 C# 依据见 [SaveFormat.writeTo] 与 `SaveFormat` 类 KDoc（存档格式未变，
 * 本项只改「字节怎么落到盘上」）。
 */
class SaveStreamWriteTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun saveData(compressed: Boolean): SaveData = SaveData(
        fileType = SaveFileKind.NORMAL,
        uniqueCode = 7153L,
        scriptVersion = 0L,
        text = "CH20261108 ",
        characters = listOf(
            listOf(SaveEntry.IntValue("NO", 0), SaveEntry.StrValue("NAME", "ISASSI")),
        ),
        variables = listOf(
            SaveEntry.IntValue("DAY", 5),
            SaveEntry.IntArray1("FLAG", longArrayOf(0, 1, 0, 0, 208)),
            SaveEntry.StrArray1("NAMES", arrayOf("a", null, "bb")),
        ),
        compressed = compressed,
    )

    private fun <T> withTempRoot(block: (Path) -> T): T {
        val root = Files.createTempDirectory("eratw-stream")
        try {
            return block(root)
        } finally {
            Files.walk(root).use { s ->
                s.sorted(Comparator.reverseOrder<Path>()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun dirEntries(dir: Path): List<String> = Files.list(dir).use { s ->
        s.map { it.fileName.toString() }.sorted().collect(Collectors.toList())
    }

    private fun leftoverTemps(root: Path): List<String> = Files.walk(root).use { s ->
        s.filter { p ->
            val n = p.fileName.toString()
            n.startsWith(".eratw-save-") && n.endsWith(".tmp")
        }.map { it.toString() }.collect(Collectors.toList())
    }

    private fun streamed(block: (OutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(block).toByteArray()

    // ------------------------------------------------------------------
    // 1. 序列化层：流式口与旧 write 同字节
    // ------------------------------------------------------------------

    @Test
    fun `SaveFormat 流式写与旧 write 逐字节相同`() {
        for (compressed in listOf(false, true)) {
            val data = saveData(compressed)
            val old = SaveFormat.write(data)
            val viaStream = streamed { out -> SaveFormat.writeTo(data, out) }
            assertTrue(
                old.contentEquals(viaStream),
                "compressed=$compressed：流式口产物必须与旧 write 逐字节相同（${old.size} vs ${viaStream.size}）",
            )
        }
    }

    @Test
    fun `幂等短路时流式口写回原档字节`() {
        val bytes = SaveFormat.write(saveData(compressed = true))
        val reopened = SaveFormat.read(bytes)
        val viaStream = streamed { out -> SaveFormat.writeTo(reopened, out) }
        assertTrue(bytes.contentEquals(viaStream), "未改动档必须原样回写 sourceFile 字节（不重压缩）")
        assertEquals(bytes.size, SaveFormat.write(reopened).size, "旧 write 的短路行为不变")
    }

    @Test
    fun `SaveCodec 流式口与物化口同字节`() {
        val config = SaveConfig(systemSaveInBinary = true, zipSaveData = true)
        val data = saveData(compressed = true)
        val old = SaveCodec.write(data, VariableRegistry(), config)
        val viaStream = streamed { out -> SaveCodec.writeTo(data, VariableRegistry(), config, out) }
        assertTrue(old.contentEquals(viaStream), "SaveCodec.writeTo 必须与 write 同字节")
    }

    // ------------------------------------------------------------------
    // 2. 文件层：真流式落盘
    // ------------------------------------------------------------------

    @Test
    fun `RootedSaveFileIo 流式写与字节写落盘结果一致且不留临时文件`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val bytes = SaveFormat.write(saveData(compressed = true))

        assertTrue(io.write("save00.sav") { it.write(bytes) }, "流式写应成功")
        assertContentEquals(bytes, io.read("save00.sav"), "读回字节必须一致")
        assertEquals(listOf("save00.sav"), dirEntries(root), "成功后目录里只能有目标文件")
        assertEquals(emptyList(), leftoverTemps(root), "成功后不得残留临时文件")

        // 覆盖：流式口同样必须整体替换（旧档更长时尾部不得残留）
        val shorter = byteArrayOf(1, 2, 3)
        assertTrue(io.write("save00.sav") { it.write(shorter) }, "覆盖应成功")
        assertContentEquals(shorter, io.read("save00.sav"), "覆盖后长度必须收缩")
    }

    @Test
    fun `producer 抛异常返回 false 且旧档原样保留`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val old = ByteArray(4096) { (it % 251).toByte() }
        assertTrue(io.write("save00.sav", old), "先落一份旧档")

        // ① 一个字节都没写就失败
        assertFalse(io.write("save00.sav") { throw IllegalStateException("模拟序列化失败") })
        assertContentEquals(old, io.read("save00.sav"), "序列化失败不得回落原地截断旧档")

        // ② 写了半截再失败：仍按 producer 失败处理（临时文件被清掉，目标不动）
        assertFalse(
            io.write("save00.sav") {
                it.write(byteArrayOf(9, 9, 9))
                throw IllegalStateException("模拟半截失败")
            },
        )
        assertContentEquals(old, io.read("save00.sav"), "半截失败同样不得截断旧档")
        assertEquals(emptyList(), leftoverTemps(root), "producer 失败必须清掉临时文件")
    }

    @Test
    fun `producer 抛 OOM 时上抛且旧档原样保留`() = withTempRoot { root ->
        val io = RootedSaveFileIo(root)
        val old = ByteArray(256) { 7 }
        assertTrue(io.write("save00.sav", old), "先落一份旧档")

        assertFailsWith<OutOfMemoryError> {
            io.write("save00.sav") { throw OutOfMemoryError("模拟堆贴顶") }
        }
        assertContentEquals(old, io.read("save00.sav"), "OOM 不得截断旧档（`Error` 契约不变）")
        assertEquals(emptyList(), leftoverTemps(root), "OOM 路径必须清掉临时文件")
    }

    @Test
    fun `接口默认实现把 producer 字节转交旧 write`() {
        val captured = ArrayList<Pair<String, ByteArray>>()
        val fake = object : SaveFileIo {
            override fun exists(path: String): Boolean = false
            override fun read(path: String): ByteArray? = null
            override fun write(path: String, bytes: ByteArray): Boolean {
                captured.add(path to bytes)
                return true
            }

            override fun delete(path: String): Boolean = false
        }

        assertTrue(fake.write("save00.sav") { it.write(byteArrayOf(4, 5, 6)) }, "默认实现应成功")
        assertEquals(listOf("save00.sav"), captured.map { it.first }, "默认实现必须转交旧 write")
        assertContentEquals(byteArrayOf(4, 5, 6), captured.single().second, "字节必须原样转交")

        assertFalse(fake.write("save01.sav") { throw IllegalStateException("x") }, "producer Exception ⇒ false")
        assertEquals(1, captured.size, "producer 失败不得再调旧 write")
    }
}
