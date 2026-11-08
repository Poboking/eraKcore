package com.eratw.port.core.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L4 稳定性：配置文件读写的行定义与「读不动 ≠ 空文件」。
 *
 * | 判据 | 原版权威 | 改前行状 |
 * |---|---|---|
 * | `\r\n` / `\r` / `\n` 都是行尾；空文件 0 行；末尾换行不产生尾随空行 | `MinorShift.Emuera.Sub/EraStreamReader.cs:52` 的 `File.ReadAllLines` | 只认 `\n`：CR 换行的配置塌成一行 |
 * | `\r` 换行的文件合并写回仍保留 CR，且不多出空行 | 写回侧与读侧同一套行定义（保留式合并的配套口径） | `splitLines` 只认 `\n`；末尾 `\r` 会被判成「没结尾」再补一个 |
 * | 0 字节 ⇒ `PRESENT` / 0 行；非法字节 ⇒ `UNREADABLE` / 0 行 | `ConfigData.cs:514-522`（`Open` 失败静默跳过） | 两者在报告里都是 lineCount 0（本次只加断言，不改判定） |
 *
 * 语义均按「CR 也是行尾」的原版口径；正常 LF / CRLF 语料的读数不变（既有
 * `ConfigParsingTest` / `ConfigPreserveTest` 仍在钉）。
 */
class StabilityL4ConfigIoTest {

    private fun tempDir(): Path = createTempDirectory("eratw-l4-config")

    private fun writeConfig(dir: Path, bytes: ByteArray): Path {
        val path = dir.resolve(EMUERA_CONFIG_FILE_NAME)
        Files.write(path, bytes)
        return path
    }

    private fun storeWith(key: ConfigKey, value: ConfigValue): ConfigStore {
        val store = ConfigStore()
        assertTrue(store.itemOf(key).assign(value), "$key 应当可写（未被 fixed 钉住）")
        return store
    }

    // ------------------------------------------------------------------
    // 读侧行定义
    // ------------------------------------------------------------------

    /** `File.ReadAllLines` 口径：单独的 CR 也是行尾，两个键都要真的落进条目表。 */
    @Test
    fun `CR 换行的配置按两行读取并生效`() {
        val path = writeConfig(tempDir(), "IGNORE CASE:NO\rFONT SIZE:18\r".toByteArray(Charsets.UTF_8))
        val lines = EmueraConfigParser.readConfigLines(path)
        assertEquals(listOf("IGNORE CASE:NO", "FONT SIZE:18"), lines, "CR 是行尾，且末尾 CR 不产生尾随空行")

        val store = ConfigStore()
        val result = EmueraConfigParser.loadGameFile(ConfigFile(EMUERA_CONFIG_FILE_NAME, lines!!), store)
        assertEquals(2, result.records.size, "两行都进记录，不是「一行带整份文件当值」")
        assertFalse(store.itemOf(ConfigKey.IgnoreCase).bool(), "第二行不再被第一行吞掉")
        assertEquals(18, store.itemOf(ConfigKey.FontSize).int())
    }

    /** 末尾换行只结束最后一行，不额外产生空行（`ReadAllLines` 的读数口径）。 */
    @Test
    fun `末尾换行不产生尾随空行`() {
        assertEquals(
            listOf("FONT SIZE:18"),
            EmueraConfigParser.readConfigLines(writeConfig(tempDir(), "FONT SIZE:18\r\n".toByteArray())),
        )
        assertEquals(
            listOf("a", "b"),
            EmueraConfigParser.readConfigLines(writeConfig(tempDir(), "a\rb\r".toByteArray())),
        )
    }

    /** 0 字节是「文件在但空」：`PRESENT` + 0 行，与读不动的 `UNREADABLE` 分开。 */
    @Test
    fun `空文件是 PRESENT 且 0 行`() {
        val dir = tempDir()
        val path = writeConfig(dir, ByteArray(0))
        assertEquals(emptyList(), EmueraConfigParser.readConfigLines(path))

        val load = EmueraConfigParser.loadChainFromDisk(dir)
        val layer = load.layer(ConfigChainRole.EMUERA)
        assertEquals(ConfigLayerPresence.PRESENT, layer.presence, "ConfigData.cs:519-522 只把 Open 失败判为读不动")
        assertEquals(0, layer.lineCount)
        assertEquals(ConfigLayerPresence.ABSENT, load.layer(ConfigChainRole.DEFAULT).presence)
        assertEquals(ConfigLayerPresence.ABSENT, load.layer(ConfigChainRole.FIXED).presence)
    }

    /** 非法字节（UTF-8 与 windows-31J 都解不动）⇒ `UNREADABLE`，不冒充空文件。 */
    @Test
    fun `非法字节的文件是 UNREADABLE`() {
        val dir = tempDir()
        // 0x81 在 UTF-8 里是孤立后续字节、在 CP932 里是被截断的首字节 ⇒ 两种解码都失败。
        val path = writeConfig(dir, byteArrayOf(0x81.toByte()))
        assertEquals(null, EmueraConfigParser.readConfigLines(path))

        val layer = EmueraConfigParser.loadChainFromDisk(dir).layer(ConfigChainRole.EMUERA)
        assertEquals(ConfigLayerPresence.UNREADABLE, layer.presence)
        assertEquals(0, layer.lineCount)
        assertEquals(path, layer.resolvedPath)
    }

    // ------------------------------------------------------------------
    // 写侧：CR 换行的保留式合并
    // ------------------------------------------------------------------

    /** 读→合并→写对 CR 换行文件仍成立：已知键原位替换、缺失键用 CR 追加、不出现双 CR。 */
    @Test
    fun `CR 换行的合并写回保留 CR 且不多出空行`() {
        val original = "FONT SIZE:18\rUNKNOWN:1\r"
        val merged = EmueraConfigWriter.mergeInto(
            original,
            storeWith(ConfigKey.FontSize, ConfigValue.IntValue(22)),
            english = true,
        )
        assertTrue(merged.startsWith("FONT SIZE:22\rUNKNOWN:1\r"), "已知键原位替换，未知行与 CR 原样保留")
        assertFalse(merged.contains('\n'), "整份输出不应出现 LF")
        assertFalse(merged.contains("\r\r"), "末尾 CR 已结束末行，追加前不得再补一个 CR（会产生空行）")
        assertTrue(merged.endsWith("\r"), "原文以 CR 结尾，追加块也以 CR 结尾")
        assertEquals(98, merged.split('\r').count { it.isNotEmpty() }, "2 条已有 + 96 条缺失")
    }

    /** 主流行尾的多数派把 CR 也算进去：CR 多时追加块用 CR。 */
    @Test
    fun `CR 多数派时追加块用 CR`() {
        val original = "FONT SIZE:18\rUNKNOWN:1\rNOTE:2\n"
        val merged = EmueraConfigWriter.mergeInto(
            original,
            storeWith(ConfigKey.FontSize, ConfigValue.IntValue(22)),
            english = true,
        )
        assertTrue(merged.contains("NOTE:2\nIGNORE CASE:YES\r"), "CR 2 票对 LF 1 票 ⇒ 追加块用 CR")
        assertFalse(merged.contains("\r\r"), "已有行各自保留行尾，不叠行尾")
    }
}
