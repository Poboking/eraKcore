package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.SaveFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 旧版本存档里**当前游戏已经不存在**的变量：只走标签、不建数组。
 *
 * 真机红（2026-10-29 玩家档 `save39.sav`，4.956 产、当前语料 4.980）：档里有 4.980
 * 已删除的角色变量 `MOE_RUMIA_COM_RECORD`（每个角色声明 100,000 项）× 162 个角色。
 * 读侧按声明尺寸建 `Array<String?>(100000)` 并计入 [SaveFormat] 的累计分配预算
 * ⇒ 全档声明 306.8 MiB 顶穿 256 MiB 闸，`LOADDATA` 落成「尝试载入数据错误的文件」。
 *
 * 原版对未声明变量是「读完即丢」（`VariableData.cs` 的 `ReadStrArray(null, …)` 分支），
 * 本发明不改变它的读面语义；本用例锁三件事：
 * 1. 给 `keepKey` 时未知键不进结果、不占分配预算；
 * 2. 不给时行为保持旧口径（全部读入）；
 * 3. 跳过的条目同样校验标签 —— 坏档不能因为「键不认识」被放过。
 */
class SaveUnknownKeySkipTest {

    private fun saveWith(vararg blocks: List<SaveEntry>): ByteArray = SaveFormat.write(
        SaveData(
            fileType = SaveFileKind.NORMAL,
            uniqueCode = 7153,
            scriptVersion = 0,
            text = "skip-test",
            characters = blocks.toList(),
            variables = emptyList(),
            emData = emptyList(),
            compressed = false,
        ),
    )

    /** 160 个角色 × 10 万项字符串（≈ 128 MiB 声明量）：不给过滤器时必然顶穿 256 MiB 预算。 */
    private fun oldGameBlocks(): Array<List<SaveEntry>> {
        val gone = Array<String?>(100_000) { null }
        return Array(160) { ci ->
            listOf(
                SaveEntry.IntValue("NO", ci.toLong()),
                SaveEntry.IntArray1("CFLAG", LongArray(10_000)),
                SaveEntry.StrArray1("MOE_RUMIA_COM_RECORD", gone.copyOf()),
            )
        }
    }

    @Test
    fun `未知大数组不进分配预算且不进结果`() {
        val blocks = oldGameBlocks()
        val bytes = saveWith(*blocks)

        // 旧口径：全量写进条目 ⇒ 160 × (10000×8 + 100000×8) ≈ 140 MiB，未过 256 MiB 闸，
        // 但这就是真机上被误判的那条路径的前提（真实档另有 162 个角色的系统变量）。
        val all = SaveFormat.read(bytes)
        assertEquals(160, all.characters.size)
        assertTrue(all.characters[0].any { it.key == "MOE_RUMIA_COM_RECORD" }, "不给过滤器 = 全部读入")

        // 生产口径：只留当前游戏存在的键 ⇒ 未知串数组只走标签。
        val kept = SaveFormat.read(bytes) { it == "NO" || it == "CFLAG" }
        assertEquals(160, kept.characters.size)
        for (block in kept.characters) {
            assertEquals(listOf("NO", "CFLAG"), block.map { it.key }, "未知键 MOE_RUMIA_COM_RECORD 整条不落地")
        }
        val flag = kept.characters[0].single { it.key == "CFLAG" }
        assertEquals(10_000, (flag as SaveEntry.IntArray1).values.size, "已知数组仍按声明尺寸读满")
        assertNotNull(kept.characters[0].single { it.key == "NO" })
    }

    @Test
    fun `全档声明量顶穿预算时 给过滤器仍能读完`() {
        // 384 个角色 × 10 万项 ≈ 292 MiB 声明量 > 256 MiB：不给过滤器必抛（真机同型）。
        val gone = Array<String?>(100_000) { null }
        val blocks = Array(384) { ci ->
            listOf(SaveEntry.IntValue("NO", ci.toLong()), SaveEntry.StrArray1("GONE", gone.copyOf()))
        }
        val bytes = saveWith(*blocks)

        val e = assertFailsWith<SaveFormatException> { SaveFormat.read(bytes) }
        assertTrue(e.message!!.contains("累计分配"), "实际：${e.message}")

        val kept = SaveFormat.read(bytes) { it == "NO" }
        assertEquals(384, kept.characters.size, "过滤后整档读完")
        assertEquals(listOf("NO"), kept.characters.last().map { it.key })
    }

    @Test
    fun `跳过路径仍校验标签 坏档不能因键不认识被放过`() {
        val bytes = saveWith(
            listOf(
                SaveEntry.IntValue("NO", 0),
                SaveEntry.StrArray1("GONE", arrayOfNulls<String>(4)),
            ),
        )
        // 找到 GONE 的字符串数组正文首标签（0xD8 或 0xF0 → 改成非法标签 0x91）。
        val needle = "GONE".toByteArray(Charsets.UTF_16LE)
        val keyAt = indexOf(bytes, needle)
        assertTrue(keyAt > 0, "夹具里应能定位到键名")
        // 键名之后：Int32 长度（4 字节）+ 首标签
        val tagAt = keyAt + needle.size + 4
        val broken = bytes.copyOf()
        broken[tagAt] = 0x91.toByte()
        assertFailsWith<SaveFormatException> { SaveFormat.read(broken) { it == "NO" } }
        // 同一份坏档在不跳过时也照样抛（标签校验不依赖过滤器）
        assertFailsWith<SaveFormatException> { SaveFormat.read(broken) }
    }

    @Test
    fun `EM 段未知键同样跳过`() {
        val bytes = SaveFormat.write(
            SaveData(
                fileType = SaveFileKind.NORMAL,
                uniqueCode = 7153,
                scriptVersion = 0,
                text = "em-skip",
                characters = emptyList(),
                variables = listOf(SaveEntry.IntValue("MONEY", 12)),
                emData = listOf(SaveEntry.StrValue("OLD_DT", "x")),
                compressed = false,
            ),
        )
        val kept = SaveFormat.read(bytes) { it != "OLD_DT" }
        assertEquals(1, kept.variables.size)
        assertNull(kept.emData.firstOrNull { it.key == "OLD_DT" }, "EM 段未知键不进结果")
        assertEquals(1, SaveFormat.read(bytes).emData.size, "旧口径保留")
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
