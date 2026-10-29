package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * BH3-SAVEBIN 的**改动档**战场。
 *
 * 未改动档的两条路已被前序工作覆盖：`write(read(bytes))` 靠 [SaveData.sourcePayload]
 * 原字节短路（[com.eratw.port.core.save.SaveRoundTripAuditTest]），剥掉短路后的重编码
 * 与 C# 逐行转写 [com.eratw.port.core.data.SaveBinaryRoundTripAudit3Test] 的
 * `CsWriterRef`（`EraBinaryDataWriter.cs:175-478`）逐字节一致。本文件专攻剩下那半边：
 * **改一个变量再写**，形状是否仍与 PC 一致。
 *
 * ## 判据链（为什么这些断言等价于「与 PC 同形状」）
 * 1. 未改动真档的 `encodePayload(d)` 与 PC 原档正文逐字节相同（本文件真档用例首行自证，
 *    依据同上 CsWriterRef 对拍）⇒ 移植侧编码器对这批数据无损；
 * 2. 条目编码是 `(key, value)` 的纯函数：`m_WriteInt` 四档（`EraBinaryDataWriter.cs:175-196`）、
 *    7-bit 字节前缀 + UTF-16LE（`:63-66`，.NET `BinaryWriter.Write(string)`）、
 *    0 游程标记族（`:203-478`）都在合成电池里逐字节钉住；
 * 3. 因此「保宽改动」（单字节值 ±1、且不动游程上下文）产出的正文与 PC 写同一逻辑改动
 *    的产物**逐字节相同**——本文件用「只差 1 字节、且差值字节就是新值」断言这一点；
 * 4. 长度变化的改动（游程结构改变、varint 换档）没有 PC 参照可对，判据退化为
 *    「读回逐条目相同 + 再写固定点 + 外壳合法」，这一条只能靠 C# 逐句对拍支撑
 *    （见文件头「只能靠 C# 推断」节）。
 *
 * ## 只能靠 C# 推断（无 PC 参照）的部分
 * - 改动档的**压缩外壳字节**：.NET `DeflateStream` 的输出无法用 `java.util.zip.Deflater`
 *   复现（实测 `global.sav`：同一 payload 的 zlib level 6 输出 508 B，.NET 为 510 B），
 *   移植侧按与原版相同的 GZip 头（`1f 8b 08 00 00000000 00 0a`，6 份真档实测一致）
 *   重压，语义等价、PC 可读，但**不逐字节相同**——本文件只断言外壳字段、CRC32、ISIZE
 *   与「解压正文等于改动档编码」，不断言与某个 PC 产物同字节。
 * - 改动档被 PC 读回：按 GZip 规范一致性 + `CreateReader` 判据面
 *   （`EraBinaryDataReader.cs:786-828`）推断，本环境无 PC 可跑。
 *
 * ## 证据行号（C#）
 * - `m_WriteInt`：EraBinaryDataWriter.cs:175-196；`writeData(long[])`：:203-223；
 *   `long[,]`：:225-267；`long[,,]`：:269-332；`string`：:334-344；
 *   `string[]`：:346-367；`string[,]`：:369-412；`string[,,]`：:414-478；
 *   `WriteWithKey`：:83-173；`WriteHeader`：:35-51；`WriteFileType`：:53-56；
 *   GZip 外壳（Dispose）：:480-500。
 * - 读侧对称：`m_ReadInt`：EraBinaryDataReader.cs:33-48；1D：:102-175；2D：:177-292；
 *   3D：:294-459；`CreateReader` 判据面：:786-828。
 * - 帧结构调用点：`SaveToStreamBinary`（NORMAL）：VariableEvaluator.cs:2591-2607；
 *   `SaveChara`（CHARVAR 尾 EOF）：:2247-2275；`SaveVariable`（VAR）：:2321-2353；
 *   `SaveGlobal`（GLOBAL 双 EOF）：:2474-2494；
 *   角色块（Separator/EOC）：CharacterData.cs:513-558。
 */
class SaveFormatMutatedWriteTest {

    // ==================================================================
    // 装置
    // ==================================================================

    /** 合成基准档：NORMAL、双角色块（带 Separator）、标量/一二维三维/字符串/Map/DT 各一。 */
    private fun baseSave(compressed: Boolean = false): SaveData = SaveData(
        fileType = SaveFileKind.NORMAL,
        uniqueCode = 7153L,
        scriptVersion = 0L,
        text = "CH20260326 ",
        characters = listOf(
            listOf(
                SaveEntry.IntValue("NO", 0),
                SaveEntry.StrValue("NAME", "ISASSI"),
                SaveEntry.IntArray1("BASE", longArrayOf(10, 0, 0, 20, 0, 0, 0, 0)),
                SaveEntry.Separator,
                SaveEntry.IntValue("UDFOO", 7),
            ),
            listOf(
                SaveEntry.IntValue("NO", 1),
                SaveEntry.StrValue("NAME", "ERI"),
            ),
        ),
        variables = listOf(
            SaveEntry.IntValue("DAY", 5),
            SaveEntry.StrValue("TITLE", "测试"),
            SaveEntry.IntArray1("FLAG", longArrayOf(0, 1, 0, 0, 0, 208, 0, 0, 0, 0)),
            SaveEntry.IntArray2("GRID", 2, 3, longArrayOf(5, 0, 0, 0, 0, 0)),
            SaveEntry.IntArray3("CUBE", 2, 1, 1, longArrayOf(5, 0)),
            SaveEntry.StrArray1("NAMES", arrayOf("a", null, "bb", null)),
            SaveEntry.MapValue("MAP", listOf("k1" to "v1", "k2" to "")),
        ),
        emData = listOf(SaveEntry.DataTableValue("DT_X", "<schema/>", "<data/>")),
        compressed = compressed,
    )

    /**
     * 「改动档」语义的重建：丢掉 sourceFile/sourcePayload（internal，默认 null）——
     * 改动过的存档没有可短路回写的原字节，必须走重压缩分支；这与真实改动流的收敛结果一致
     * （真带了 source 时 [SaveFormat.write] 比对不等同样回落重压）。
     */
    private fun SaveData.rebuild(
        characters: List<List<SaveEntry>> = this.characters,
        variables: List<SaveEntry> = this.variables,
    ): SaveData = SaveData(
        fileType = fileType,
        uniqueCode = uniqueCode,
        scriptVersion = scriptVersion,
        text = text,
        characters = characters,
        variables = variables,
        emData = emData,
        compressed = compressed,
    )

    /** 只装一条变量的 NORMAL 档：`encodePayload` = 26 字节头 + 条目 + 双 EOF。 */
    private fun onlyVar(e: SaveEntry): SaveData =
        SaveData(SaveFileKind.NORMAL, 0L, 0L, "", variables = listOf(e), compressed = false)

    /** 从 [onlyVar] 的 payload 里切出单条目字节（26 = 1+8+8+1+8：类别/双 Int64/空前缀/角色数 0）。 */
    private fun entryBytes(e: SaveEntry): ByteArray {
        val p = SaveFormat.encodePayload(onlyVar(e))
        return p.copyOfRange(26, p.size - 2)
    }

    private fun hex(s: String): ByteArray {
        val parts = s.trim().split(' ').filter { it.isNotEmpty() }
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun expectBytes(what: String, expectedHex: String, actual: ByteArray) {
        val want = hex(expectedHex)
        assertTrue(
            actual.contentEquals(want),
            "$what：期望 [$expectedHex]，实际 [${actual.toHex()}]",
        )
    }

    private fun u32le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun u64le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun ByteArray.indexOfSub(sub: ByteArray, from: Int = 0): Int {
        if (sub.isEmpty()) return from
        if (sub.size > this.size) return -1
        outer@ for (i in from..(this.size - sub.size)) {
            for (j in sub.indices) {
                if (this[i + j] != sub[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun diffIndices(a: ByteArray, b: ByteArray): List<Int> {
        assertEquals(a.size, b.size, "比对双方长度必须相同（${a.size} vs ${b.size}）")
        val out = ArrayList<Int>()
        for (i in a.indices) if (a[i] != b[i]) out.add(i)
        return out
    }

    private fun gunzip(bytes: ByteArray, off: Int): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes, off, bytes.size - off)).use { it.readBytes() }

    private fun strArrEq(a: Array<String?>, b: Array<String?>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /** 条目深比较（LongArray / Array<String?> 数据类的 equals 是引用相等，必须逐元素）。 */
    private fun entryEq(a: SaveEntry, b: SaveEntry): Boolean = when (a) {
        is SaveEntry.IntValue -> b is SaveEntry.IntValue && a.key == b.key && a.value == b.value
        is SaveEntry.IntArray1 ->
            b is SaveEntry.IntArray1 && a.key == b.key && a.values.contentEquals(b.values)

        is SaveEntry.IntArray2 ->
            b is SaveEntry.IntArray2 && a.key == b.key && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.values.contentEquals(b.values)

        is SaveEntry.IntArray3 ->
            b is SaveEntry.IntArray3 && a.key == b.key && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.dim2 == b.dim2 && a.values.contentEquals(b.values)

        is SaveEntry.StrValue -> b is SaveEntry.StrValue && a.key == b.key && a.value == b.value
        is SaveEntry.StrArray1 ->
            b is SaveEntry.StrArray1 && a.key == b.key && strArrEq(a.values, b.values)

        is SaveEntry.StrArray2 ->
            b is SaveEntry.StrArray2 && a.key == b.key && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                strArrEq(a.values, b.values)

        is SaveEntry.StrArray3 ->
            b is SaveEntry.StrArray3 && a.key == b.key && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.dim2 == b.dim2 && strArrEq(a.values, b.values)

        is SaveEntry.MapValue -> b is SaveEntry.MapValue && a.key == b.key && a.entries == b.entries
        is SaveEntry.XmlValue -> b is SaveEntry.XmlValue && a.key == b.key && a.xml == b.xml
        is SaveEntry.DataTableValue ->
            b is SaveEntry.DataTableValue && a.key == b.key &&
                a.schemaXml == b.schemaXml && a.dataXml == b.dataXml

        is SaveEntry.Separator -> b is SaveEntry.Separator
        else -> false
    }

    /** 整档深比较（不比 sourceFile/sourcePayload/version 留档字段）。 */
    private fun assertDataEq(want: SaveData, got: SaveData, what: String) {
        assertEquals(want.fileType, got.fileType, "$what：类别")
        assertEquals(want.uniqueCode, got.uniqueCode, "$what：游戏码")
        assertEquals(want.scriptVersion, got.scriptVersion, "$what：脚本版本")
        assertEquals(want.text, got.text, "$what：描述")
        assertEquals(want.compressed, got.compressed, "$what：压缩标志")
        assertEquals(want.characters.size, got.characters.size, "$what：角色块数")
        for (i in want.characters.indices) {
            val w = want.characters[i]
            val g = got.characters[i]
            assertEquals(w.size, g.size, "$what：角色块 $i 条目数")
            for (j in w.indices) {
                assertTrue(entryEq(w[j], g[j]), "$what：角色块 $i 条目 $j（键 ${w[j].key}）")
            }
        }
        assertEquals(want.variables.size, got.variables.size, "$what：变量条目数")
        for (j in want.variables.indices) {
            assertTrue(
                entryEq(want.variables[j], got.variables[j]),
                "$what：变量条目 $j（键 ${want.variables[j].key}）",
            )
        }
        assertEquals(want.emData.size, got.emData.size, "$what：EM 条目数")
        for (j in want.emData.indices) {
            assertTrue(entryEq(want.emData[j], got.emData[j]), "$what：EM 条目 $j")
        }
    }

    /** 保宽改动：payload 必须只差 1 字节，且差值字节就是新值（C# m_WriteInt 纯函数性的直接后果）。 */
    private fun assertOneByteDiff(what: String, srcPayload: ByteArray, newPayload: ByteArray, newValue: Long) {
        val diff = diffIndices(srcPayload, newPayload)
        assertEquals(1, diff.size, "$what：保宽改动应只差 1 字节（实际 ${diff.size} 字节，下标 $diff）")
        assertEquals(newValue.toByte(), newPayload[diff[0]], "$what：差值字节必须是新值 $newValue")
    }

    /** 改动档全链路：合法容器 → 头探测 → round-trip 逐条目相同 → 再写固定点 → 外壳字段。 */
    private fun assertMutatedRoundTrip(name: String, mutated: SaveData, what: String) {
        val written = SaveFormat.write(mutated)
        assertTrue(SaveFormat.isBinaryContainer(written), "$name/$what：改动档必须是合法二进制容器")
        val head = assertNotNull(SaveFormat.peek(written), "$name/$what：改动档必须探得出头")
        assertEquals(mutated.uniqueCode, head.uniqueCode, "$name/$what：头游戏码")
        assertEquals(mutated.scriptVersion, head.scriptVersion, "$name/$what：头脚本版本")
        assertEquals(mutated.text, head.text, "$name/$what：头描述")
        assertEquals(mutated.fileType, head.fileType, "$name/$what：头类别")

        val back = SaveFormat.read(written)
        assertDataEq(mutated, back, "$name/$what：改动档 round-trip")
        assertTrue(
            SaveFormat.write(back).contentEquals(written),
            "$name/$what：读回再写必须固定点（write(read(x)) == x）",
        )

        if (mutated.compressed) {
            assertEquals(SaveFormat.MAGIC_GZIP, u64le(written, 0), "$name/$what：压缩分支魔数")
            assertEquals(1808L, u32le(written, 8), "$name/$what：容器版本恒 1808（Writer.cs:45）")
            assertEquals(0L, u32le(written, 12), "$name/$what：校验表条数恒 0（Writer.cs:46-50 死循环）")
            expectBytes("$name/$what：GZip 头", "1F 8B 08 00 00 00 00 00 00 0A", written.copyOfRange(16, 26))
            val payload = gunzip(written, 16)
            assertTrue(
                payload.contentEquals(SaveFormat.encodePayload(mutated)),
                "$name/$what：解压正文必须等于改动档编码",
            )
            val crc = CRC32().apply { update(payload) }.value
            assertEquals(crc, u32le(written, written.size - 8), "$name/$what：CRC32 覆盖正文")
            assertEquals(
                payload.size.toLong() and 0xFFFF_FFFFL,
                u32le(written, written.size - 4),
                "$name/$what：ISIZE = 正文长度",
            )
        } else {
            assertEquals(SaveFormat.MAGIC_PLAIN, u64le(written, 0), "$name/$what：未压缩魔数")
            assertTrue(
                SaveFormat.encodePayload(mutated).contentEquals(written.copyOfRange(16, written.size)),
                "$name/$what：未压缩分支 = 16 字节头 + 正文",
            )
        }
    }

    // ==================================================================
    // 1. 合成档：变长整数四档边界（m_WriteInt，EraBinaryDataWriter.cs:175-196）
    // ==================================================================

    @Test
    fun `变长整数四档边界精确字节且读回对称`() {
        val cases = listOf(
            0L to "00",
            1L to "01",
            -1L to "D0 FF FF",
            207L to "CF",
            208L to "D0 D0 00",
            -208L to "D0 30 FF",
            209L to "D0 D1 00",
            -209L to "D0 2F FF",
            32767L to "D0 FF 7F",
            -32768L to "D0 00 80",
            32768L to "D1 00 80 00 00",
            -32769L to "D1 FF 7F FF FF",
            2147483647L to "D1 FF FF FF 7F",
            -2147483648L to "D1 00 00 00 80",
            9223372036854775807L to "D2 FF FF FF FF FF FF FF 7F",
            Long.MIN_VALUE to "D2 00 00 00 00 00 00 00 80",
        )
        for ((v, wantHex) in cases) {
            // 条目 = 类型字节 00 + 键 "K"（7-bit 字节数 02 + UTF-16LE）+ m_WriteInt(v)
            expectBytes("m_WriteInt($v)", "00 02 4B 00 $wantHex", entryBytes(SaveEntry.IntValue("K", v)))
            val back = SaveFormat.read(SaveFormat.write(onlyVar(SaveEntry.IntValue("K", v))))
            assertEquals(
                SaveEntry.IntValue("K", v), back.variables.single(),
                "m_WriteInt($v) 必须与读侧 m_ReadInt（Reader.cs:33-48）对称",
            )
        }
    }

    // ==================================================================
    // 2. 合成档：0 游程标记族写出条件（Writer.cs:203-332 / :346-478）
    // ==================================================================

    @Test
    fun `零游程标记族在一二三维整数数组的写出条件`() {
        // 1D：键 "A" = 02 41 00；长度恒为 Int32 LE（Writer.cs:205）
        expectBytes("1D 空数组", "01 02 41 00 00 00 00 00 FF", entryBytes(SaveEntry.IntArray1("A", longArrayOf())))
        expectBytes("1D 单值", "01 02 41 00 01 00 00 00 05 FF", entryBytes(SaveEntry.IntArray1("A", longArrayOf(5))))
        expectBytes(
            "1D 前导游程落 F0",
            "01 02 41 00 03 00 00 00 F0 02 05 FF",
            entryBytes(SaveEntry.IntArray1("A", longArrayOf(0, 0, 5))),
        )
        expectBytes(
            "1D 尾游程不落标记（Writer.cs:214-221 只在非零值前冲刷）",
            "01 02 41 00 03 00 00 00 05 FF",
            entryBytes(SaveEntry.IntArray1("A", longArrayOf(5, 0, 0))),
        )
        expectBytes(
            "1D 游程 1 也落标记",
            "01 02 41 00 02 00 00 00 F0 01 05 FF",
            entryBytes(SaveEntry.IntArray1("A", longArrayOf(0, 5))),
        )
        expectBytes(
            "1D 游程后紧跟负值（m_WriteInt 208 档）",
            "01 02 41 00 03 00 00 00 F0 01 01 D0 FF FF FF",
            entryBytes(SaveEntry.IntArray1("A", longArrayOf(0, 1, -1))),
        )

        // 2D：d0 d1 + 行体；整零行走 F1 计数、非整零行落 E0（Writer.cs:225-267）
        expectBytes(
            "2D 全零（两行都整零 ⇒ 零标记）",
            "02 02 41 00 02 00 00 00 03 00 00 00 FF",
            entryBytes(SaveEntry.IntArray2("A", 2, 3, LongArray(6))),
        )
        expectBytes(
            "2D 首行有值次行整零",
            "02 02 41 00 02 00 00 00 03 00 00 00 05 E0 FF",
            entryBytes(SaveEntry.IntArray2("A", 2, 3, longArrayOf(5, 0, 0, 0, 0, 0))),
        )
        expectBytes(
            "2D 首行整零次行有值（F1 在值前冲刷）",
            "02 02 41 00 02 00 00 00 03 00 00 00 F1 01 05 E0 FF",
            entryBytes(SaveEntry.IntArray2("A", 2, 3, longArrayOf(0, 0, 0, 5, 0, 0))),
        )
        expectBytes(
            "2D 行内游程与行尾 E0 并存",
            "02 02 41 00 02 00 00 00 03 00 00 00 F0 01 05 E0 FF",
            entryBytes(SaveEntry.IntArray2("A", 2, 3, longArrayOf(0, 5, 0, 0, 0, 0))),
        )
        expectBytes(
            "2D 单行整零也不落 E0（Writer.cs:256-259）",
            "02 02 41 00 01 00 00 00 03 00 00 00 FF",
            entryBytes(SaveEntry.IntArray2("A", 1, 3, LongArray(3))),
        )

        // 3D：d0 d1 d2 + 面/行/列三级；整零行 num2、整零面 num3（Writer.cs:269-332）
        expectBytes(
            "3D 单值加行列尾标记",
            "03 02 41 00 02 00 00 00 01 00 00 00 01 00 00 00 05 E0 E1 FF",
            entryBytes(SaveEntry.IntArray3("A", 2, 1, 1, longArrayOf(5, 0))),
        )
        expectBytes(
            "3D 全零（零标记一个都不落）",
            "03 02 41 00 02 00 00 00 01 00 00 00 01 00 00 00 FF",
            entryBytes(SaveEntry.IntArray3("A", 2, 1, 1, LongArray(2))),
        )
        expectBytes(
            "3D 行有值、面内另一行整零 ⇒ E0 后 E1",
            "03 02 41 00 01 00 00 00 02 00 00 00 01 00 00 00 05 E0 E1 FF",
            entryBytes(SaveEntry.IntArray3("A", 1, 2, 1, longArrayOf(5, 0))),
        )
    }

    @Test
    fun `字符串数组游程与 D8 标记的写出条件`() {
        // null 与 \"\" 同走游程（Writer.cs:352），值前落 216 = D8（:363）
        expectBytes(
            "Str1D 单值",
            "11 02 41 00 01 00 00 00 D8 02 61 00 FF",
            entryBytes(SaveEntry.StrArray1("A", arrayOf("a"))),
        )
        expectBytes(
            "Str1D null 游程",
            "11 02 41 00 02 00 00 00 F0 01 D8 02 61 00 FF",
            entryBytes(SaveEntry.StrArray1("A", arrayOf(null, "a"))),
        )
        expectBytes(
            "Str1D 尾 null 不落标记",
            "11 02 41 00 02 00 00 00 D8 02 61 00 FF",
            entryBytes(SaveEntry.StrArray1("A", arrayOf("a", null))),
        )
        expectBytes(
            "Str1D 全 null",
            "11 02 41 00 02 00 00 00 FF",
            entryBytes(SaveEntry.StrArray1("A", arrayOf(null, null))),
        )
        expectBytes(
            "Str1D 空串与 null 同编码（Writer.cs:352）",
            "11 02 41 00 02 00 00 00 FF",
            entryBytes(SaveEntry.StrArray1("A", arrayOf("", ""))),
        )
        expectBytes(
            "Str2D 整零行走 F1、行尾 E0",
            "12 02 41 00 02 00 00 00 02 00 00 00 F1 01 D8 02 61 00 E0 FF",
            entryBytes(SaveEntry.StrArray2("A", 2, 2, arrayOf(null, null, "a", null))),
        )
        // 读回：跳过的元素一律 null（Reader.cs:461-519）。StrArray1 是数据类但 values 是
        // Array，equals 走引用相等 ⇒ 必须用 entryEq 逐元素比
        val back = SaveFormat.read(
            SaveFormat.write(onlyVar(SaveEntry.StrArray1("A", arrayOf("a", null, "bb", null)))),
        )
        assertTrue(
            entryEq(SaveEntry.StrArray1("A", arrayOf("a", null, "bb", null)), back.variables.single()),
            "null 与 \"\" 读回都必须是 null（Writer.cs:352 同编码）",
        )
    }

    @Test
    fun `Map 条目按插入序写出且读回保序`() {
        // WriteWithKey 的 Dictionary 分支（Writer.cs:141-154）：Count 为 Int32，随后逐对 key/value
        expectBytes(
            "Map 两键按插入序",
            "20 02 4D 00 02 00 00 00 04 6B 00 31 00 04 76 00 31 00 04 6B 00 32 00 00",
            entryBytes(SaveEntry.MapValue("M", listOf("k1" to "v1", "k2" to ""))),
        )
        val back = SaveFormat.read(
            SaveFormat.write(onlyVar(SaveEntry.MapValue("M", listOf("k1" to "v1", "k2" to "")))),
        )
        assertEquals(SaveEntry.MapValue("M", listOf("k1" to "v1", "k2" to "")), back.variables.single())
    }

    // ==================================================================
    // 3. 合成档：帧结构与角色块顺序（调用点行号见类 KDoc）
    // ==================================================================

    @Test
    fun `四种存档类别的帧结构与 C# 调用点一致`() {
        val chars = listOf(listOf(SaveEntry.IntValue("NO", 0)))
        val vars = listOf(SaveEntry.IntValue("DAY", 5))
        val em = listOf(SaveEntry.DataTableValue("DT", "<s/>", "<d/>"))
        fun frame(kind: SaveFileKind, cs: List<List<SaveEntry>>, vs: List<SaveEntry>, es: List<SaveEntry>) =
            SaveFormat.encodePayload(SaveData(kind, 1L, 2L, "T", cs, vs, es, compressed = false))

        // NORMAL：头 + 角色数 + 块…EOC + 变量段 + EOF + EM 段 + EOF（VariableEvaluator.cs:2591-2607）
        val normal = frame(SaveFileKind.NORMAL, chars, vars, em)
        assertEquals(0x00, normal[0].toInt() and 0xFF, "NORMAL 类别字节（EraSaveFileType.cs:3-9）")
        assertEquals(0xFF, normal[normal.size - 1].toInt() and 0xFF, "NORMAL 末字节 EOF")
        assertTrue(
            normal[normal.size - 2] != 0xFF.toByte(),
            "EM 段非空时 EOF 之前是 DT 数据，不是另一个 EOF",
        )
        // EM 段为空时两个 EOF 相邻（:2603-2606 的两次 WriteEOF 都在）
        val normalNoEm = frame(SaveFileKind.NORMAL, chars, vars, emptyList())
        assertEquals(0xFF, normalNoEm[normalNoEm.size - 2].toInt() and 0xFF, "NORMAL 双 EOF（EM 段为空）")

        // CHARVAR：角色块后还有 WriteEOF()（SaveChara，VariableEvaluator.cs:2270）
        val charvar = frame(SaveFileKind.CHARVAR, chars, emptyList(), emptyList())
        assertEquals(0x03, charvar[0].toInt() and 0xFF)
        assertTrue(charvar.indexOfSub(hex("FE FF")) >= 0, "CHARVAR：最后一个 EOC 之后还有尾 EOF")

        // GLOBAL：无角色数；变量段 + EOF + EM 段 + EOF（SaveGlobal，:2483-2493）
        val global = frame(SaveFileKind.GLOBAL, emptyList(), vars, em)
        assertEquals(0x01, global[0].toInt() and 0xFF)
        assertEquals(0xFF, global[global.size - 1].toInt() and 0xFF)
        val globalNoEm = frame(SaveFileKind.GLOBAL, emptyList(), vars, emptyList())
        assertEquals(0xFF, globalNoEm[globalNoEm.size - 2].toInt() and 0xFF, "GLOBAL 双 EOF（EM 段为空也写）")

        // VAR：只有变量段 + 单 EOF，无角色数无双 EOF（SaveVariable，:2332-2353）
        val varf = frame(SaveFileKind.VAR, emptyList(), vars, emptyList())
        assertEquals(0x02, varf[0].toInt() and 0xFF)
        assertEquals(0xFF, varf[varf.size - 1].toInt() and 0xFF)
        assertTrue(varf[varf.size - 2] != 0xFF.toByte(), "VAR 只有一个 EOF")
    }

    @Test
    fun `角色块条目序分隔符 EOC 与尾 EOF`() {
        val p = SaveFormat.encodePayload(baseSave())
        // 块 0 尾部：Separator(FD) + UDFOO 条目（00 + 键 + 值 07）+ EOC(FE)
        // （CharacterData.cs:545-557）
        val sepTail = hex("FD 00 0A 55 00 44 00 46 00 4F 00 4F 00 07 FE")
        val at = p.indexOfSub(sepTail)
        assertTrue(at >= 0, "Separator 必须紧贴用户自定义变量之前、EOC 收尾；实际 payload 无此序列")
        expectBytes("Separator + UDFOO + EOC", sepTail.toHex(), p.copyOfRange(at, at + sepTail.size))
        // 读回：条目序与 Separator 位置保持
        val back = SaveFormat.read(SaveFormat.write(baseSave()))
        assertEquals(5, back.characters[0].size, "角色块 0 条目数")
        assertTrue(back.characters[0][3] is SaveEntry.Separator, "Separator 必须读回在第 4 位")
        assertDataEq(baseSave(), back, "角色块序 round-trip")
    }

    // ==================================================================
    // 4. 合成档：改动档 round-trip
    // ==================================================================

    @Test
    fun `合成档同时改动标量字符串数组角色块后 round-trip`() {
        val base = baseSave()
        val newVars = base.variables.toMutableList().also { l ->
            l[l.indexOfFirst { it is SaveEntry.IntValue && it.key == "DAY" }] =
                SaveEntry.IntValue("DAY", 6) // 保宽
            l[l.indexOfFirst { it is SaveEntry.StrValue && it.key == "TITLE" }] =
                SaveEntry.StrValue("TITLE", "改动后的标题") // 长度变化
            l[l.indexOfFirst { it is SaveEntry.IntArray1 && it.key == "FLAG" }] =
                SaveEntry.IntArray1("FLAG", longArrayOf(0, 1, 42, 0, 0, 208, 0, 0, 0, 0)) // 游程结构变
            l[l.indexOfFirst { it is SaveEntry.StrArray1 && it.key == "NAMES" }] =
                SaveEntry.StrArray1("NAMES", arrayOf("a", "XY", "bb", null)) // null 改非空
            l[l.indexOfFirst { it is SaveEntry.IntArray2 && it.key == "GRID" }] =
                SaveEntry.IntArray2("GRID", 2, 3, longArrayOf(1000, 0, 0, 0, 0, 0)) // varint 换档
        }
        val newBlk = base.characters[0].toMutableList().also { b ->
            b[b.indexOfFirst { it is SaveEntry.IntValue && it.key == "UDFOO" }] =
                SaveEntry.IntValue("UDFOO", 8) // 角色块内、Separator 之后
        }
        val mutated = base.rebuild(
            characters = base.characters.toMutableList().also { it[0] = newBlk },
            variables = newVars,
        )
        assertMutatedRoundTrip("合成档", mutated, "六处混合改动")
        // 六处里 DAY 与 UDFOO 保宽、其余四处变长 ⇒ 总长必须变（变长的部分由下面两个用例逐字节钉）
        assertTrue(
            SaveFormat.encodePayload(mutated).size > SaveFormat.encodePayload(base).size,
            "TITLE 变长 + FLAG/NAMES 游程结构变 ⇒ 正文必须变长",
        )
    }

    @Test
    fun `保宽改动的 payload 只差值字节`() {
        val base = baseSave()
        val p0 = SaveFormat.encodePayload(base)

        // ① 变量标量 DAY 5 → 6
        val m1 = base.rebuild(
            variables = base.variables.toMutableList().also { l ->
                val i = l.indexOfFirst { it is SaveEntry.IntValue && it.key == "DAY" }
                l[i] = (l[i] as SaveEntry.IntValue).copy(value = 6)
            },
        )
        assertOneByteDiff("变量 DAY 5→6", p0, SaveFormat.encodePayload(m1), 6)

        // ② 角色块标量 NO 0 → 1（Separator 之前的条目，游程上下文不动）
        val m2 = base.rebuild(
            characters = base.characters.toMutableList().also { blocks ->
                val blk = blocks[0].toMutableList()
                val i = blk.indexOfFirst { it is SaveEntry.IntValue }
                blk[i] = (blk[i] as SaveEntry.IntValue).copy(value = 1)
                blocks[0] = blk
            },
        )
        assertOneByteDiff("角色块 NO 0→1", p0, SaveFormat.encodePayload(m2), 1)

        // ③ 两处叠加仍只差两字节 ⇒ 编码是逐条目独立的纯函数
        val m3 = base.rebuild(
            characters = m2.characters,
            variables = m1.variables,
        )
        assertEquals(2, diffIndices(p0, SaveFormat.encodePayload(m3)).size, "两处保宽改动叠加")
        assertMutatedRoundTrip("合成档", m3, "双保宽改动")
    }

    @Test
    fun `零元素改非零改变游程结构后精确读回`() {
        val base = baseSave()
        val p0 = SaveFormat.encodePayload(base)

        // 1D：FLAG[2] 0 → 42（F0 02 被 2A 顶掉一部分，净增 1 字节）
        val flagOld = base.variables.first { it is SaveEntry.IntArray1 && it.key == "FLAG" } as SaveEntry.IntArray1
        val flagNew = flagOld.copy(values = flagOld.values.copyOf().also { it[2] = 42L })
        expectBytes(
            "FLAG 原编码",
            "01 08 46 00 4C 00 41 00 47 00 0A 00 00 00 F0 01 01 F0 03 D0 D0 00 FF",
            entryBytes(flagOld),
        )
        expectBytes(
            "FLAG 零改非零",
            "01 08 46 00 4C 00 41 00 47 00 0A 00 00 00 F0 01 01 2A F0 02 D0 D0 00 FF",
            entryBytes(flagNew),
        )
        val m1 = base.rebuild(
            variables = base.variables.toMutableList().also {
                it[it.indexOfFirst { e -> e is SaveEntry.IntArray1 && e.key == "FLAG" }] = flagNew
            },
        )
        assertEquals(p0.size + 1, SaveFormat.encodePayload(m1).size, "1D 零改非零净增 1 字节")
        assertMutatedRoundTrip("合成档", m1, "FLAG[2] 0→42")

        // 2D：GRID 原为 [5,0,0 | 0,0,0]（首行有值落 E0、次行整零被计数）；[1][1] 0 → 7 后
        // 冲出 F1（整零行）+ F0（行内游程）+ 值 + E0
        val gridOld = base.variables.first { it is SaveEntry.IntArray2 && it.key == "GRID" }
            as SaveEntry.IntArray2
        val gridNew = gridOld.copy(values = longArrayOf(0, 0, 0, 0, 7, 0))
        expectBytes(
            "GRID 原编码（次行整零 ⇒ 零标记）",
            "02 08 47 00 52 00 49 00 44 00 02 00 00 00 03 00 00 00 05 E0 FF",
            entryBytes(gridOld),
        )
        expectBytes(
            "GRID[1][1] 0→7",
            "02 08 47 00 52 00 49 00 44 00 02 00 00 00 03 00 00 00 F1 01 F0 01 07 E0 FF",
            entryBytes(gridNew),
        )
        val m2 = base.rebuild(
            variables = base.variables.toMutableList().also {
                it[it.indexOfFirst { e -> e is SaveEntry.IntArray2 && e.key == "GRID" }] = gridNew
            },
        )
        assertMutatedRoundTrip("合成档", m2, "GRID[1][1] 0→7")
    }

    @Test
    fun `整零行整零平面计数跨变长整数边界`() {
        // 2D：210 行中前 209 行整零 ⇒ F1 + m_WriteInt(209) = D0 D1 00（跨 208 档）
        val grid = SaveEntry.IntArray2("A", 210, 3, LongArray(210 * 3) { if (it == 209 * 3) 5L else 0L })
        expectBytes(
            "2D 209 个整零行",
            "02 02 41 00 D2 00 00 00 03 00 00 00 F1 D0 D1 00 05 E0 FF",
            entryBytes(grid),
        )
        // 3D：210 个面前 209 面整零 ⇒ F2 + m_WriteInt(209)
        val cube = SaveEntry.IntArray3("A", 210, 1, 1, LongArray(210) { if (it == 209) 5L else 0L })
        expectBytes(
            "3D 209 个整零面",
            "03 02 41 00 D2 00 00 00 01 00 00 00 01 00 00 00 F2 D0 D1 00 05 E0 E1 FF",
            entryBytes(cube),
        )
        // 1D 游程 208 / 209 各跨一档
        expectBytes(
            "1D 游程 208（m_WriteInt 208 档）",
            "01 02 41 00 D0 00 00 00 F0 CF 01 FF",
            entryBytes(SaveEntry.IntArray1("A", LongArray(208) { if (it == 207) 1L else 0L })),
        )
        expectBytes(
            "1D 游程 209（D0 D1 00）",
            "01 02 41 00 D1 00 00 00 F0 D0 D0 00 01 FF",
            entryBytes(SaveEntry.IntArray1("A", LongArray(209) { if (it == 208) 1L else 0L })),
        )
        // 字符串 1D 游程 209
        expectBytes(
            "Str1D 游程 209",
            "11 02 41 00 D1 00 00 00 F0 D0 D0 00 D8 02 61 00 FF",
            entryBytes(SaveEntry.StrArray1("A", Array<String?>(209) { if (it == 208) "a" else null })),
        )
        // 全部读回对称
        for (e in listOf(grid, cube)) {
            val back = SaveFormat.read(SaveFormat.write(onlyVar(e)))
            assertTrue(entryEq(e, back.variables.single()), "${e.key} 跨档游程读回")
        }
    }

    // ==================================================================
    // 5. 合成档：容器外壳与 isBinaryContainer 判据面
    // ==================================================================

    @Test
    fun `改动档容器外壳与 isBinaryContainer 判据面`() {
        val mutated = baseSave(compressed = true).rebuild(
            variables = baseSave().variables.toMutableList().also { l ->
                l[l.indexOfFirst { it is SaveEntry.IntValue && it.key == "DAY" }] =
                    SaveEntry.IntValue("DAY", 6)
            },
        )
        val good = SaveFormat.write(mutated)
        assertTrue(SaveFormat.isBinaryContainer(good), "改动档必须是容器（CreateReader 非 null）")

        // 魔数不符 ⇒ 非容器（Reader.cs:796-799）
        val badMagic = good.copyOf().also { it[0] = 0x00 }
        assertFalse(SaveFormat.isBinaryContainer(badMagic), "魔数不符必须判非容器")

        // 版本非 1808 ⇒ 非容器（Reader.cs:820-824）
        val badVersion = good.copyOf().also { it[8] = 0x0F; it[9] = 0x07 } // 1808 → 1807
        assertFalse(SaveFormat.isBinaryContainer(badVersion), "版本非 1808 必须判非容器")

        // 不足 16 字节 ⇒ 非容器（Reader.cs:790-793）
        assertFalse(SaveFormat.isBinaryContainer(good.copyOfRange(0, 15)), "15 字节必须判非容器")

        // 头完好、正文坏（CRC 改坏）⇒ 解压在 CreateReader 的 try 内 ⇒ 非容器（:810-813 + :826-828）
        val badBody = good.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 0xFF).toByte() }
        assertFalse(SaveFormat.isBinaryContainer(badBody), "头完好正文坏的档必须判「不是容器」")
        assertNull(SaveFormat.peek(badBody), "同上：peek 给 null 而不是抛穿")

        // 未压缩分支同口径
        val plain = SaveFormat.write(baseSave(compressed = false))
        assertTrue(SaveFormat.isBinaryContainer(plain))
        assertEquals(SaveFormat.MAGIC_PLAIN, u64le(plain, 0))
    }

    // ==================================================================
    // 6. 真档：改一个变量再写（ERATW_SAVE_SAMPLES 指向 sav 目录；未设置即 skip）
    // ==================================================================

    /**
     * 真档改动档 round-trip。样本目录走环境变量 `ERATW_SAVE_SAMPLES`（语料
     * `eratw-画蛇添足版eratw4.980/sav` 的 6 份 .sav：global + save00..03 + save1000）；
     * 未设置或不是目录时按 JUnit Assumptions **跳过**（可见的 skipped，不是静默通过），
     * 目录下没有 .sav 则断言失败。
     *
     * 四类改动逐文件各做一遍（目标都是从档里现找，不写死变量名）：
     * 1. 角色块标量保宽 ±1（NORMAL 才有角色块）：payload 只差 1 字节且差值字节就是新值；
     * 2. 全局数组元素保宽 ±1（1..206 → 2..207，m_WriteInt 恒单字节）：同上；
     * 3. 数组零元素改非零：游程结构改变（长度变化），只断言读回精确 + 固定点；
     * 4. 字符串数组 null 元素改非空：D8 标记从无到有。
     * 每类改动都过 [assertMutatedRoundTrip]：容器判据面、头探测、逐条目 round-trip、
     * 再写固定点、GZip 外壳字段（CRC32/ISIZE/解压正文）。
     *
     * 「保宽只差 1 字节」为什么等于 PC 同形状：未改动档的 `encodePayload(d)` 与本文件
     * 首行断言那样与 PC 原档正文逐字节相同（CsWriterRef 对拍，Audit3），条目编码又是
     * (key, value) 的纯函数 ⇒ 改动档正文与 PC 写同一逻辑改动的产物逐字节相同；压缩外壳
     * 不逐字节相同（Java Deflater vs .NET DeflateStream），见类 KDoc「只能靠 C# 推断」。
     */
    @Test
    fun `真档改一个变量再写形状保持`() {
        val dir = System.getenv("ERATW_SAVE_SAMPLES")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        Assumptions.assumeTrue(
            dir != null && Files.isDirectory(dir),
            "ERATW_SAVE_SAMPLES 未设置或不是目录：$dir",
        )
        val root = dir!!
        val files = Files.list(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sav") }
                .sorted()
                .toList()
        }
        assertTrue(files.isNotEmpty(), "$root 下没有 .sav 样本")

        for (f in files) {
            val name = f.fileName.toString()
            val bytes = Files.readAllBytes(f)
            val d = SaveFormat.read(bytes)
            val srcPayload = requireNotNull(SaveFormat.sourcePayloadOf(bytes)) { "$name 应解出容器正文" }
            assertTrue(
                SaveFormat.encodePayload(d).contentEquals(srcPayload),
                "$name：未改动档重编码必须与 PC 原正文逐字节相同（否则 1 字节差判据失效）",
            )

            // ---- ① 角色块标量保宽 ±1（CharacterData.cs:513-558 的条目序原样保留）----
            if (d.fileType == SaveFileKind.NORMAL) {
                val ci = d.characters.indexOfFirst { blk ->
                    blk.any { it is SaveEntry.IntValue && it.value >= 0L && it.value <= 206L }
                }
                assertTrue(ci >= 0, "$name：角色块里应找得到 0..206 的标量")
                val blk = d.characters[ci]
                val ei = blk.indexOfFirst { it is SaveEntry.IntValue && it.value >= 0L && it.value <= 206L }
                val old = blk[ei] as SaveEntry.IntValue
                val newBlk = blk.toMutableList().also { it[ei] = old.copy(value = old.value + 1) }
                val mutated = d.rebuild(characters = d.characters.toMutableList().also { it[ci] = newBlk })
                assertOneByteDiff(
                    "$name 角色块 ${old.key} ${old.value}→${old.value + 1}",
                    srcPayload,
                    SaveFormat.encodePayload(mutated),
                    old.value + 1,
                )
                assertMutatedRoundTrip(name, mutated, "角色块标量 ${old.key}")
            }

            // ---- ② 全局数组元素保宽 ±1（1..206 → 2..207）----
            val ai = d.variables.indexOfFirst { e ->
                e is SaveEntry.IntArray1 && e.values.any { it >= 1L && it <= 206L }
            }
            assertTrue(ai >= 0, "$name：变量块里应找得到 1..206 的数组元素")
            val arrOld = d.variables[ai] as SaveEntry.IntArray1
            val j = arrOld.values.indexOfFirst { it >= 1L && it <= 206L }
            val arrNew = arrOld.values.copyOf().also { it[j] = it[j] + 1 }
            val mutated2 = d.rebuild(
                variables = d.variables.toMutableList().also { it[ai] = arrOld.copy(values = arrNew) },
            )
            assertOneByteDiff(
                "$name 数组 ${arrOld.key}[$j] ${arrOld.values[j]}→${arrNew[j]}",
                srcPayload,
                SaveFormat.encodePayload(mutated2),
                arrNew[j],
            )
            assertMutatedRoundTrip(name, mutated2, "数组元素保宽 ${arrOld.key}[$j]")

            // ---- ③ 数组零元素改非零（游程结构改变，长度变化）----
            val zi = d.variables.indexOfFirst { e ->
                e is SaveEntry.IntArray1 && e.values.any { it == 0L }
            }
            assertTrue(zi >= 0, "$name：变量块里应找得到含零元素的 IntArray1")
            val zOld = d.variables[zi] as SaveEntry.IntArray1
            val k = zOld.values.indexOfFirst { it == 0L }
            val zNew = zOld.values.copyOf().also { it[k] = 42L }
            val mutated3 = d.rebuild(
                variables = d.variables.toMutableList().also { it[zi] = zOld.copy(values = zNew) },
            )
            assertMutatedRoundTrip(name, mutated3, "零元素改非零 ${zOld.key}[$k]")

            // ---- ④ 字符串数组 null 元素改非空 ----
            val si = d.variables.indexOfFirst { e ->
                e is SaveEntry.StrArray1 && e.values.any { it == null }
            }
            assertTrue(si >= 0, "$name：变量块里应找得到含 null 的 StrArray1")
            val sOld = d.variables[si] as SaveEntry.StrArray1
            val m = sOld.values.indexOfFirst { it == null }
            val sNew = sOld.values.copyOf().also { it[m] = "MH" }
            val mutated4 = d.rebuild(
                variables = d.variables.toMutableList().also { it[si] = sOld.copy(values = sNew) },
            )
            assertMutatedRoundTrip(name, mutated4, "null 串改非空 ${sOld.key}[$m]")

            println(
                "[save-mutated] $name kind=${d.fileType} chars=${d.characters.size} " +
                    "vars=${d.variables.size} em=${d.emData.size} " +
                    "角色块标量/数组保宽/零改非零/null改非空 四类改动全部 round-trip 通过",
            )
        }
    }
}
