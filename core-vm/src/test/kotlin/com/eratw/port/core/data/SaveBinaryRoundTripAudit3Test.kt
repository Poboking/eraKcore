package com.eratw.port.core.data

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 二进制存档**写侧**与读侧的对拍（本路只管 `core/data/save` 这一面）。此前几路的覆盖面是读侧
 * 上限（[com.eratw.port.core.save.SaveHardeningTest]）与文本档段闸
 * （[com.eratw.port.core.save.SaveFormatAuditTest]），本文件补另外半边：
 *
 * 1. **独立参照编码器**：把 `EraBinaryDataWriter` 按 C# 行号逐行转写成 [CsWriterRef]
 *    （`MinorShift.Emuera.Runtime.Utils/EraBinaryDataWriter.cs:175-478`），与被测的
 *    `SaveFormat.Out` 不共享一行实现（字符串字节走 JVM 的 UTF-16LE 编码器、CRC32 走手写
 *    多项式）；固定形状电池 + 定种子随机电池逐个比字节。于是 `0xF0` `0xF1` `0xF2` `0xE0`
 *    `0xE1` `0xD8` 标记族在 1D/2D/3D、int/str、Map 各分支的**写出条件**、**冲刷次序**
 *    （先外后内：面 → 行 → 列）与**流末不冲刷**这三件事是被证出来的，不是被注释声明的。
 * 2. **写侧产出的字节流必须被读侧原样吃回**：同一份字节既过 [SaveFormat.read]
 *    （含 `jumpIndex` 的判界，SaveFormat.kt:403-418），又要求「读回来再编码」逐字节不变。
 * 3. **真档对照**：语料 `sav/` 的 PC 存档用参照编码器重打正文，与原正文逐字节比对，
 *    并统计标记族在真档里的出现次数；读数写 `build/sav-bh3-write-audit.txt`。
 * 4. **读侧新增闸的余量**：`SaveFormat.kt:713-757` 的五道闸（元素数 16M、单条目 64 MiB、
 *    累计 256 MiB、gunzip `min(64MiB, max(1MiB, 文件字节 × 256))`、角色数 100 万）逐一对真档
 *    量余量，读数写 `build/sav-bh3-gate-headroom.txt`；真档一旦被误杀即闸红。
 * 5. **报错口径**：截断 / 越界 / 非法标签一律 [SaveFormatException]；经
 *    [FileSystemOps.loadData] 落成 [CodeEE] 时文案必须是原版 Lang 文本
 *    （`lang.emuera-zhs.xml:728` 的 `Error.LoadCorruptedData`），core 的诊断文本不得冒到屏上。
 *
 * ## 与 C# 的差异（本文件只登记并钉住现状，不改分派层 ⇒ 交付报告「需组长接线」）
 * - 原版读侧把错误**分三种文案**：`AbnormalFileData`「文件数据型异常」
 *   （`EraBinaryDataReader.cs:30` / zhs `:825`）、`AbnormalBinaryData`「二进制数据异常」
 *   （`:45`、`:156`、`:265`、`:425`、`:499`、`:590`、`:730` / zhs `:826`）、
 *   `AbnormalData`「数据异常」（`VariableData.cs:4207-4208` / zhs `:304`）；
 *   移植侧 core 层已按标签分开报错，但 `FileSystemOps.loadData` 一律收成 `LoadCorruptedData`。
 *   要不要三分由组长裁；本文件先把「core 文案不得外泄」这一条钉住。
 * - 原版写侧的压缩开关取自 `Config.SystemSaveInBinary && Config.ZipSaveData`
 *   （`EraBinaryDataWriter.cs:22-32`、头的两分支 `:37-44`），移植侧取 `SaveData.compressed`，语义等价。
 * - 原版读侧越界/负跳转由 .NET 的数组下标检查兜住（抛 `IndexOutOfRangeException`，不是 FileEE），
 *   移植侧统一落 [SaveFormatException]（可读回、可诊断），属移植侧收紧，真档不受影响（本文件 8 节量余量）。
 */
class SaveBinaryRoundTripAudit3Test {

    // ==================================================================
    // 装置一：EraBinaryDataWriter 的逐行转写（oracle）
    // ==================================================================

    /**
     * 参照编码器。方法名与局部变量名都照抄 C#（`num` = 行内零游程、`num2` = 整零行数、
     * `num3` = 整零平面数），逐行对应：
     * - `m_WriteInt` → [mWriteInt]（EraBinaryDataWriter.cs:175-196）
     * - `BinaryWriter.Write(string)`（writer 以 `Encoding.Unicode` 构造，:25/:30）→ [binString]
     * - `writeData(long[])` → [dataInt1]（:203-223）；`long[,]` → [dataInt2]（:225-267）；
     *   `long[,,]` → [dataInt3]（:269-332）
     * - `writeData(string)` → [dataStr]（:334-344，null 落成空串）
     * - `writeData(string[])` → [dataStr1]（:346-367）；`string[,]` → [dataStr2]（:369-412）；
     *   `string[,,]` → [dataStr3]（:414-478）
     * - `WriteWithKey` → [withKey]（:83-173；类型字节 = `EraSaveDataType.cs:3-19`）
     * - `WriteFileType` → 直接落 `(byte)type`（:53-56；`EraSaveFileType.cs:3-9`）
     *
     * [marks] 统计六个游程标记的出现次数，下标 0..5 = `0xF0` `0xF1` `0xF2` `0xE0` `0xE1` `0xD8`。
     */
    private class CsWriterRef {
        val body = ByteArrayOutputStream()
        val marks = IntArray(6)

        private fun put(v: Long, width: Int) {
            for (i in 0 until width) body.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        private fun u8(v: Int) { body.write(v and 0xFF) }
        private fun i32(v: Int) = put(v.toLong(), 4)
        private fun i64(v: Long) = put(v, 8)
        private fun mark(slot: Int, tag: Int) { marks[slot]++; u8(tag) }

        /**
         * `BinaryWriter.Write(string)` = 7-bit 变长**字节数**前缀 + UTF-16LE 码元。
         * 取字节交给 JVM 的 UTF-16LE 编码器（与被测实现的手工拆字节是两条独立路径：
         * 代理对漏掉低半、或前缀按字符数算，都会在这里现形）。
         */
        fun binString(s: String) {
            val raw = s.toByteArray(Charsets.UTF_16LE)
            var n = raw.size
            while (true) {
                val x = n and 0x7F
                n = n ushr 7
                if (n != 0) u8(x or 0x80) else { u8(x); break }
            }
            body.write(raw)
        }

        /** `m_WriteInt`（:175-196）：`0..207` 直写 / `0xD0`+Int16 / `0xD1`+Int32 / `0xD2`+Int64。 */
        fun mWriteInt(v: Long) {
            when {
                v >= 0 && v <= 207 -> u8(v.toInt())
                v >= Short.MIN_VALUE && v <= Short.MAX_VALUE -> { u8(208); put(v, 2) }
                v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> { u8(209); put(v, 4) }
                else -> { u8(210); put(v, 8) }
            }
        }

        fun dataInt1(array: LongArray) { // :203-223
            i32(array.size)
            var num = 0
            for (i in array.indices) {
                if (array[i] == 0L) { num++; continue }
                if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                mWriteInt(array[i])
            }
            u8(255)
        }

        fun dataInt2(d0: Int, d1: Int, rows: Array<LongArray>) { // :225-267
            var num = 0
            var num2 = 0
            i32(d0); i32(d1)
            for (i in 0 until d0) {
                for (j in 0 until d1) {
                    if (rows[i][j] == 0L) { num++; continue }
                    if (num2 > 0) { mark(1, 241); mWriteInt(num2.toLong()); num2 = 0 }
                    if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                    mWriteInt(rows[i][j])
                }
                if (num == d1) num2++ else mark(3, 224)
                num = 0
            }
            u8(255)
        }

        fun dataInt3(d0: Int, d1: Int, d2: Int, planes: Array<Array<LongArray>>) { // :269-332
            var num = 0
            var num2 = 0
            var num3 = 0
            i32(d0); i32(d1); i32(d2)
            for (i in 0 until d0) {
                for (j in 0 until d1) {
                    for (k in 0 until d2) {
                        val v = planes[i][j][k]
                        if (v == 0L) { num++; continue }
                        if (num3 > 0) { mark(2, 242); mWriteInt(num3.toLong()); num3 = 0 }
                        if (num2 > 0) { mark(1, 241); mWriteInt(num2.toLong()); num2 = 0 }
                        if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                        mWriteInt(v)
                    }
                    if (num == d2) num2++ else mark(3, 224)
                    num = 0
                }
                if (num2 == d1) num3++ else mark(4, 225)
                num2 = 0
            }
            u8(255)
        }

        fun dataStr(v: String?) = binString(v ?: "") // :334-344

        fun dataStr1(array: Array<String?>) { // :346-367
            i32(array.size)
            var num = 0
            for (i in array.indices) {
                val s = array[i]
                if (s == null || s.isEmpty()) { num++; continue }
                if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                mark(5, 216); binString(s)
            }
            u8(255)
        }

        fun dataStr2(d0: Int, d1: Int, rows: Array<Array<String?>>) { // :369-412
            var num = 0
            var num2 = 0
            i32(d0); i32(d1)
            for (i in 0 until d0) {
                for (j in 0 until d1) {
                    val s = rows[i][j]
                    if (s == null || s.isEmpty()) { num++; continue }
                    if (num2 > 0) { mark(1, 241); mWriteInt(num2.toLong()); num2 = 0 }
                    if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                    mark(5, 216); binString(s)
                }
                if (num == d1) num2++ else mark(3, 224)
                num = 0
            }
            u8(255)
        }

        fun dataStr3(d0: Int, d1: Int, d2: Int, planes: Array<Array<Array<String?>>>) { // :414-478
            var num = 0
            var num2 = 0
            var num3 = 0
            i32(d0); i32(d1); i32(d2)
            for (i in 0 until d0) {
                for (j in 0 until d1) {
                    for (k in 0 until d2) {
                        val s = planes[i][j][k]
                        if (s == null || s.isEmpty()) { num++; continue }
                        if (num3 > 0) { mark(2, 242); mWriteInt(num3.toLong()); num3 = 0 }
                        if (num2 > 0) { mark(1, 241); mWriteInt(num2.toLong()); num2 = 0 }
                        if (num > 0) { mark(0, 240); mWriteInt(num.toLong()); num = 0 }
                        mark(5, 216); binString(s)
                    }
                    if (num == d2) num2++ else mark(3, 224)
                    num = 0
                }
                if (num2 == d1) num3++ else mark(4, 225)
                num2 = 0
            }
            u8(255)
        }

        /** `WriteWithKey`（:83-173）：类型字节 → 键名 → 值。Map/Xml/DT 的键名同样在这里写。 */
        fun withKey(e: SaveEntry) {
            when (e) {
                is SaveEntry.IntValue -> { u8(0); binString(e.key); mWriteInt(e.value) }
                is SaveEntry.IntArray1 -> { u8(1); binString(e.key); dataInt1(e.values) }
                is SaveEntry.IntArray2 -> { u8(2); binString(e.key); dataInt2(e.dim0, e.dim1, rows2(e.dim0, e.dim1, e.values)) }
                is SaveEntry.IntArray3 -> { u8(3); binString(e.key); dataInt3(e.dim0, e.dim1, e.dim2, planes3(e.dim0, e.dim1, e.dim2, e.values)) }
                is SaveEntry.StrValue -> { u8(16); binString(e.key); dataStr(e.value) }
                is SaveEntry.StrArray1 -> { u8(17); binString(e.key); dataStr1(e.values) }
                is SaveEntry.StrArray2 -> { u8(18); binString(e.key); dataStr2(e.dim0, e.dim1, rowsStr2(e.dim0, e.dim1, e.values)) }
                is SaveEntry.StrArray3 -> { u8(19); binString(e.key); dataStr3(e.dim0, e.dim1, e.dim2, planesStr3(e.dim0, e.dim1, e.dim2, e.values)) }
                is SaveEntry.MapValue -> { // :141-154（Count 是 Int32 定长；键值是裸串，不进游程）
                    u8(32); binString(e.key); i32(e.entries.size)
                    for ((k, v) in e.entries) { binString(k); binString(v) }
                }

                is SaveEntry.XmlValue -> { u8(33); binString(e.key); binString(e.xml) } // :155-160
                is SaveEntry.DataTableValue -> { u8(34); binString(e.key); binString(e.schemaXml); binString(e.dataXml) } // :161-172
                SaveEntry.Separator -> u8(253) // WriteSeparator（:68-71）；全仓唯一调用点 CharacterData.cs:548
            }
        }

        /**
         * 整帧正文：四种 `EraSaveFileType` 的字段序逐条照 C# 调用点抄 ——
         * NORMAL `VariableEvaluator.cs:2591-2607`、GLOBAL `:2483-2492`、VAR `:2343-2353`、
         * CHARVAR `:2259-2270`；角色块本体 `CharacterData.cs:513-557`（EOC 见 `:557`）。
         */
        fun payload(d: SaveData): ByteArray {
            u8(fileTypeByte(d.fileType))
            i64(d.uniqueCode)
            i64(d.scriptVersion)
            binString(d.text)
            when (d.fileType) {
                SaveFileKind.NORMAL -> {
                    i64(d.characters.size.toLong())
                    for (block in d.characters) { for (e in block) withKey(e); u8(254) }
                    for (e in d.variables) withKey(e)
                    u8(255)
                    for (e in d.emData) withKey(e)
                    u8(255)
                }

                SaveFileKind.GLOBAL -> {
                    for (e in d.variables) withKey(e)
                    u8(255)
                    for (e in d.emData) withKey(e)
                    u8(255)
                }

                SaveFileKind.VAR -> {
                    for (e in d.variables) withKey(e)
                    u8(255)
                }

                SaveFileKind.CHARVAR -> {
                    i64(d.characters.size.toLong())
                    for (block in d.characters) { for (e in block) withKey(e); u8(254) }
                    u8(255) // SaveChara 末尾的 WriteEOF（VariableEvaluator.cs:2270）
                }
            }
            return body.toByteArray()
        }

        /** `EraSaveFileType.cs:3-9` 的枚举序 = 落盘的类别字节；不借移植侧的 ordinal。 */
        private fun fileTypeByte(k: SaveFileKind): Int = when (k) {
            SaveFileKind.NORMAL -> 0
            SaveFileKind.GLOBAL -> 1
            SaveFileKind.VAR -> 2
            SaveFileKind.CHARVAR -> 3
        }

        private fun rows2(d0: Int, d1: Int, v: LongArray): Array<LongArray> =
            Array(d0) { i -> LongArray(d1) { j -> v[i * d1 + j] } }

        private fun planes3(d0: Int, d1: Int, d2: Int, v: LongArray): Array<Array<LongArray>> =
            Array(d0) { i -> Array(d1) { j -> LongArray(d2) { k -> v[(i * d1 + j) * d2 + k] } } }

        private fun rowsStr2(d0: Int, d1: Int, v: Array<String?>): Array<Array<String?>> =
            Array(d0) { i -> Array<String?>(d1) { j -> v[i * d1 + j] } }

        private fun planesStr3(d0: Int, d1: Int, d2: Int, v: Array<String?>): Array<Array<Array<String?>>> =
            Array(d0) { i -> Array(d1) { j -> Array<String?>(d2) { k -> v[(i * d1 + j) * d2 + k] } } }
    }

    /** 跑一遍参照编码器：返回正文与标记族计数。 */
    private fun csEncode(d: SaveData): Pair<ByteArray, IntArray> {
        val w = CsWriterRef()
        return w.payload(d) to w.marks.copyOf()
    }

    // ==================================================================
    // 装置二：字节层工具
    // ==================================================================

    private fun putU32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** 未压缩容器：魔数 8 + 版本 4 + 校验表条数 4 + 校验表 + 正文（EraBinaryDataWriter.cs:35-51）。 */
    private fun plainContainer(payload: ByteArray, checksums: LongArray = LongArray(0)): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16 + 4 * checksums.size)
        for (i in 0 until 8) out.write(((SaveFormat.MAGIC_PLAIN ushr (8 * i)) and 0xFF).toInt())
        putU32(out, 1808L)
        putU32(out, checksums.size.toLong())
        for (c in checksums) putU32(out, c)
        out.write(payload)
        return out.toByteArray()
    }

    /** 独立 CRC32（多项式 0xEDB88320，逐位移位），不复用 `java.util.zip.CRC32`。 */
    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFF_FFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            for (i in 0 until 8) crc = (crc ushr 1) xor (0xEDB88320L and -(crc and 1L))
        }
        return crc.inv() and 0xFFFF_FFFFL
    }

    private fun hexOf(b: ByteArray, limit: Int = 72): String =
        b.take(limit).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun firstDiff(expected: ByteArray, actual: ByteArray): String {
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                return "首个差异 @$i：C# 写侧=0x%02X 移植侧=0x%02X".format(
                    expected[i].toInt() and 0xFF, actual[i].toInt() and 0xFF,
                )
            }
        }
        return "长度差：期望 ${expected.size} 实际 ${actual.size}（前 $n 字节相同）"
    }

    private fun assertSameBytes(hint: String, expected: ByteArray, actual: ByteArray) {
        assertTrue(
            expected.contentEquals(actual),
            "$hint —— ${firstDiff(expected, actual)}\nC#: ${hexOf(expected)}\nkt: ${hexOf(actual)}",
        )
    }

    /** 从 [offset] 起逐字节钉死期望值；越过流尾时报「越过流尾」，好区分「错位」与「短了」。 */
    private fun assertHex(hint: String, expected: ByteArray, actual: ByteArray, offset: Int) {
        for (i in expected.indices) {
            val want = expected[i].toInt() and 0xFF
            val at = offset + i
            if (at >= actual.size) {
                throw AssertionError("$hint：偏移 $at 期望 0x%02X，但正文只有 ${actual.size} 字节（越过流尾）".format(want))
            }
            assertEquals(want, actual[at].toInt() and 0xFF, "$hint：偏移 $at 期望 0x%02X 实际 0x%02X".format(want, actual[at].toInt() and 0xFF))
        }
    }

    private fun hexBytes(vararg v: Int): ByteArray = ByteArray(v.size) { i -> v[i].toByte() }

    // ==================================================================
    // 装置三：夹具
    // ==================================================================

    private fun frame(
        kind: SaveFileKind = SaveFileKind.NORMAL,
        code: Long = 0L,
        version: Long = 0L,
        text: String = "",
        characters: List<List<SaveEntry>> = emptyList(),
        variables: List<SaveEntry> = emptyList(),
        emData: List<SaveEntry> = emptyList(),
        compressed: Boolean = false,
    ): SaveData = SaveData(
        fileType = kind, uniqueCode = code, scriptVersion = version, text = text,
        characters = characters, variables = variables, emData = emData, compressed = compressed,
    )

    private fun oneVar(e: SaveEntry): SaveData = frame(variables = listOf(e))

    /** NORMAL 帧的正文起点：类别 1 + 码 8 + 版本 8 + 空描述 1 + 角色数 8 = 26。 */
    private val entryAt = 26

    private fun i1(vararg v: Long) = SaveEntry.IntArray1("A1", longArrayOf(*v))

    private fun i2(d0: Int, d1: Int, vararg cells: Pair<Int, Long>) = SaveEntry.IntArray2(
        "A2", d0, d1, LongArray(d0 * d1).also { a -> cells.forEach { c -> a[c.first] = c.second } },
    )

    private fun i3(d0: Int, d1: Int, d2: Int, vararg cells: Pair<Int, Long>) = SaveEntry.IntArray3(
        "A3", d0, d1, d2, LongArray(d0 * d1 * d2).also { a -> cells.forEach { c -> a[c.first] = c.second } },
    )

    private fun s1(vararg v: String?) = SaveEntry.StrArray1("S1", arrayOf(*v))

    private fun s2(d0: Int, d1: Int, vararg cells: Pair<Int, String?>) = SaveEntry.StrArray2(
        "S2", d0, d1, Array<String?>(d0 * d1) { null }.also { a -> cells.forEach { c -> a[c.first] = c.second } },
    )

    private fun s3(d0: Int, d1: Int, d2: Int, vararg cells: Pair<Int, String?>) = SaveEntry.StrArray3(
        "S3", d0, d1, d2, Array<String?>(d0 * d1 * d2) { null }.also { a -> cells.forEach { c -> a[c.first] = c.second } },
    )

    /** `m_WriteInt` 的四档代表值与边界（C# 条件：`0..207` / short / int / 其余）。 */
    private val tierValues = longArrayOf(
        0L, 1L, 207L, 208L, 32767L, 32768L, -1L, -32768L, -32769L,
        Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
        Int.MAX_VALUE.toLong() + 1L, Int.MIN_VALUE.toLong() - 1L,
        Long.MAX_VALUE, Long.MIN_VALUE,
    )

    /**
     * 固定形状电池：每条对准标记族的一个分支。真档只有普通形状，
     * 0 维、208/210 游程、末平面全零这些边角全靠这里钉。
     */
    private fun shapes(): List<Pair<String, SaveEntry>> = buildList {
        // ---- 1D 整数：writeData 的一维支（:203-223）----
        add("A1 空数组" to i1())
        add("A1 全零（游程不冲刷）" to SaveEntry.IntArray1("A1", LongArray(5)))
        add("A1 前导零" to i1(0, 0, 7))
        add("A1 尾零不冲刷" to i1(7, 0, 0))
        add("A1 中间游程" to i1(1, 0, 0, 0, 2))
        add("A1 游程 207（计数单字节）" to SaveEntry.IntArray1("A1", LongArray(208).also { a -> a[207] = 5L }))
        add("A1 游程 208（计数落 short 档）" to SaveEntry.IntArray1("A1", LongArray(209).also { a -> a[208] = 5L }))
        add("A1 游程 32768（计数落 int 档）" to SaveEntry.IntArray1("A1", LongArray(32769).also { a -> a[32768] = -5L }))
        add("A1 四档全取" to SaveEntry.IntArray1("A1", tierValues))
        add("A1 负值夹在游程里" to i1(0, -1, 0, 0, Long.MIN_VALUE, 0, 208, 0))
        // ---- 2D 整数（:225-267）----
        add("A2 0x0" to i2(0, 0))
        add("A2 0x5（有列无线）" to i2(0, 5))
        add("A2 3x0（有行无列，逐行判全零）" to i2(3, 0))
        add("A2 1x1 非零" to i2(1, 1, 0 to 9L))
        add("A2 2x2 全零" to i2(2, 2))
        add("A2 首行全零" to i2(2, 2, 2 to 1L))
        add("A2 尾行全零（零行计数不冲刷）" to i2(2, 2, 0 to 1L))
        add("A2 中间整行零" to i2(3, 2, 4 to 3L))
        add("A2 行内游程 + 行尾 0xE0" to i2(2, 3, 0 to 1L, 4 to 2L))
        add("A2 每行都非全零（逐行 0xE0）" to i2(3, 2, 0 to 1L, 2 to 2L, 4 to 3L))
        add("A2 整列零但行非零" to i2(2, 2, 1 to 5L, 3 to 6L))
        add("A2 零行 209（计数落 short 档）" to i2(210, 3, 209 * 3 to 7L))
        add("A2 末元素非零" to i2(2, 2, 3 to Long.MAX_VALUE))
        // ---- 3D 整数（:269-332）----
        add("A3 0x0x0" to i3(0, 0, 0))
        add("A3 0x2x3（无线无面）" to i3(0, 2, 3))
        add("A3 2x0x3（中行无列）" to i3(2, 0, 3))
        add("A3 1x1x1" to i3(1, 1, 1, 0 to 4L))
        add("A3 2x2x2 全零" to i3(2, 2, 2))
        add("A3 首平面全零" to i3(2, 2, 2, 4 to 1L))
        add("A3 末平面全零（面数不冲刷）" to i3(2, 2, 2, 0 to 1L))
        add("A3 平面内整行零" to i3(2, 2, 2, 6 to 2L))
        add("A3 行内游程 + 0xE0 + 0xE1" to i3(2, 2, 2, 1 to 3L))
        add("A3 零平面 209（计数落 short 档）" to i3(210, 1, 1, 209 to 6L))
        add("A3 零行 210（计数落 short 档）" to i3(2, 211, 1, 210 to 8L))
        add("A3 d2=1 全链" to i3(2, 2, 1, 2 to 1L))
        add("A3 末元素非零" to i3(2, 2, 2, 7 to Long.MIN_VALUE))
        // ---- 1D 字符串（:346-367）----
        add("S1 空数组" to s1())
        add("S1 全 null（游程不冲刷）" to s1(null, null, null))
        add("S1 全空串（与 null 同形）" to s1("", ""))
        add("S1 单个非空" to s1("x"))
        add("S1 前导空 + 0xD8" to s1(null, "", "x"))
        add("S1 中间游程" to s1("a", null, null, "b"))
        add("S1 尾部空不冲刷" to s1("a", null))
        add("S1 代理对" to s1("\uD842\uDF9F", "あ\uD842\uDF9F"))
        add("S1 含 NUL 码元的串" to s1("\u0000", "a\u0000b"))
        add("S1 长串（前缀两字节）" to s1("あ".repeat(100)))
        add("S1 游程 208（空项数落 short 档）" to s1(*Array<String?>(209) { i -> if (i == 208) "z" else null }))
        // ---- 2D / 3D 字符串（:369-412 / :414-478）----
        add("S2 全 null" to s2(2, 2))
        add("S2 首行全空" to s2(2, 2, 2 to "p"))
        add("S2 尾行全空" to s2(2, 2, 0 to "p"))
        add("S2 行内游程" to s2(2, 3, 0 to "a", 4 to "b"))
        add("S2 0x3" to s2(0, 3))
        add("S2 3x0" to s2(3, 0))
        add("S3 全 null" to s3(2, 2, 2))
        add("S3 首平面全空" to s3(2, 2, 2, 4 to "q"))
        add("S3 末平面全空" to s3(2, 2, 2, 0 to "q"))
        add("S3 行内游程 + 0xE0 + 0xE1" to s3(2, 2, 2, 1 to "r", 7 to "s"))
        add("S3 d1=0" to s3(2, 0, 3))
        // ---- 标量 / Map / Xml / DataTable ----
        add("Int 极值标量" to SaveEntry.IntValue("I", Long.MIN_VALUE))
        add("Str 标量空串" to SaveEntry.StrValue("S", ""))
        add("Str 标量代理对" to SaveEntry.StrValue("S", "\uD842\uDF9F"))
        add("Map 空表" to SaveEntry.MapValue("M", emptyList()))
        add("Map 三项含空键空值" to SaveEntry.MapValue("M", listOf("k1" to "v1", "" to "", "k3" to "\uD842\uDF9F")))
        add("Xml" to SaveEntry.XmlValue("X", "<root><a b=\"1\"/></root>"))
        add("DataTable 两段" to SaveEntry.DataTableValue("D", "<xs:schema/>", "<diffgr:DiffGram/>"))
    }

    /** 定种子随机电池：维度、游程、值档位、null/空串都由随机数决定（不依赖时钟）。 */
    private fun randomShapes(n: Int, seed: Long): List<Pair<String, SaveEntry>> {
        val r = Random(seed)
        fun value(): Long = if (r.nextInt(3) == 0) 0L else tierValues[r.nextInt(tierValues.size)]
        fun maybeStr(): String? = when (r.nextInt(3)) {
            0 -> null
            1 -> ""
            else -> "v${r.nextInt(100)}\uD842\uDF9F"
        }
        return List(n) { idx ->
            val kind = r.nextInt(8)
            val entry: SaveEntry = when (kind) {
                0 -> SaveEntry.IntArray1("A1", LongArray(r.nextInt(12)) { value() })
                1 -> {
                    val d0 = r.nextInt(4); val d1 = r.nextInt(4)
                    SaveEntry.IntArray2("A2", d0, d1, LongArray(d0 * d1) { value() })
                }
                2 -> {
                    val d0 = r.nextInt(3); val d1 = r.nextInt(3); val d2 = r.nextInt(3)
                    SaveEntry.IntArray3("A3", d0, d1, d2, LongArray(d0 * d1 * d2) { value() })
                }
                3 -> SaveEntry.IntValue("I", tierValues[r.nextInt(tierValues.size)])
                4 -> SaveEntry.StrArray1("S1", Array<String?>(r.nextInt(8)) { maybeStr() })
                5 -> {
                    val d0 = r.nextInt(4); val d1 = r.nextInt(4)
                    SaveEntry.StrArray2("S2", d0, d1, Array<String?>(d0 * d1) { maybeStr() })
                }
                6 -> {
                    val d0 = r.nextInt(3); val d1 = r.nextInt(2); val d2 = r.nextInt(3)
                    SaveEntry.StrArray3("S3", d0, d1, d2, Array<String?>(d0 * d1 * d2) { maybeStr() })
                }
                else -> SaveEntry.MapValue("M", List(r.nextInt(4)) { i -> "k$i" to if (i % 3 == 0) "" else "v$i" })
            }
            "rand#$kind-$idx" to entry
        }
    }

    /**
     * 内容的规范化签名（紧凑：数组取 `contentHashCode`，真档的 571 KB 正文不会撑爆字符串）。
     * null 与空串按 C# 写侧同形处理（`EraBinaryDataWriter.cs:352`）。
     */
    private fun sig(e: SaveEntry): String = when (e) {
        is SaveEntry.IntValue -> "I:${e.key}:${e.value}"
        is SaveEntry.IntArray1 -> "A1:${e.key}:${e.values.size}:${e.values.contentHashCode()}"
        is SaveEntry.IntArray2 -> "A2:${e.key}:${e.dim0}x${e.dim1}:${e.values.contentHashCode()}"
        is SaveEntry.IntArray3 -> "A3:${e.key}:${e.dim0}x${e.dim1}x${e.dim2}:${e.values.contentHashCode()}"
        is SaveEntry.StrValue -> "S:${e.key}:${e.value.norm()}"
        is SaveEntry.StrArray1 -> "S1:${e.key}:${e.values.size}:${e.values.map { it.norm() }.hashCode()}"
        is SaveEntry.StrArray2 -> "S2:${e.key}:${e.dim0}x${e.dim1}:${e.values.map { it.norm() }.hashCode()}"
        is SaveEntry.StrArray3 -> "S3:${e.key}:${e.dim0}x${e.dim1}x${e.dim2}:${e.values.map { it.norm() }.hashCode()}"
        is SaveEntry.MapValue -> "M:${e.key}:${e.entries.joinToString("|") { it.first.norm() + "=" + it.second.norm() }}"
        is SaveEntry.XmlValue -> "X:${e.key}:${e.xml}"
        is SaveEntry.DataTableValue -> "D:${e.key}:${e.schemaXml}:${e.dataXml}"
        SaveEntry.Separator -> "SEP"
    }

    /** null 与空串在写侧同形 ⇒ 签名里都记成空串。 */
    private fun String?.norm(): String = if (this.isNullOrEmpty()) "" else this

    private fun sig(d: SaveData): String = buildString {
        append(d.fileType).append('|').append(d.uniqueCode).append('|').append(d.scriptVersion)
        append('|').append(d.text)
        append("|CHARA=").append(d.characters.joinToString("#") { b -> b.joinToString(";") { sig(it) } })
        append("|VAR=").append(d.variables.joinToString(";") { sig(it) })
        append("|EM=").append(d.emData.joinToString(";") { sig(it) })
    }

    // ==================================================================
    // 1. 标记族与 C# 写侧逐字节对拍（固定电池 + 随机电池）
    // ==================================================================

    /**
     * 每条形状 × 四种 `EraSaveFileType` 帧形，逐个比「C# 转写 vs 移植侧」的字节。
     * 判据是**原版会写出来的字节**，不是移植侧算出来的值。
     */
    @Test
    fun `零游程标记族的写出条件与 C# 逐字节一致`() {
        val diffs = ArrayList<String>()
        var frames = 0
        for ((name, e) in shapes()) {
            for (kind in listOf(SaveFileKind.NORMAL, SaveFileKind.GLOBAL, SaveFileKind.VAR, SaveFileKind.CHARVAR)) {
                val hasChara = kind == SaveFileKind.NORMAL || kind == SaveFileKind.CHARVAR
                val hasVar = kind != SaveFileKind.CHARVAR
                val hasEm = kind == SaveFileKind.NORMAL || kind == SaveFileKind.GLOBAL
                val d = frame(
                    kind = kind, code = 7153L, text = "帧",
                    characters = if (hasChara) listOf(listOf(e), listOf(SaveEntry.Separator, e)) else emptyList(),
                    variables = if (hasVar) listOf(e) else emptyList(),
                    emData = if (hasEm) listOf(SaveEntry.MapValue("EM", listOf("a" to "b"))) else emptyList(),
                )
                val expected = csEncode(d).first
                val actual = SaveFormat.encodePayload(d)
                frames++
                if (!expected.contentEquals(actual)) {
                    diffs.add(
                        "$name / $kind —— ${firstDiff(expected, actual)}\n" +
                            "C#: ${hexOf(expected)}\nkt: ${hexOf(actual)}\n" +
                            "标记 F0/F1/F2/E0/E1/D8=${csEncode(d).second.toList()}",
                    )
                }
            }
        }
        assertTrue(diffs.isEmpty(), "写侧与 C# 转写不一致（$frames 帧）：\n" + diffs.take(12).joinToString("\n\n"))
    }

    /** 随机电池：维度、游程、档位、null/空串全随机，同样逐字节比。 */
    @Test
    fun `随机形状的写侧字节与 C# 转写一致`() {
        val diffs = ArrayList<String>()
        for ((name, e) in randomShapes(400, 0x6E7A71L)) {
            val d = oneVar(e)
            val expected = csEncode(d).first
            val actual = SaveFormat.encodePayload(d)
            if (!expected.contentEquals(actual)) {
                diffs.add("$name sig=${sig(e)} —— ${firstDiff(expected, actual)}\nC#: ${hexOf(expected, 96)}\nkt: ${hexOf(actual, 96)}")
            }
        }
        assertTrue(diffs.isEmpty(), "随机 400 形状里 ${diffs.size} 条不一致：\n" + diffs.take(10).joinToString("\n\n"))
    }

    // ==================================================================
    // 2. 写侧产出的字节流必须被读侧原样吃回
    // ==================================================================

    /**
     * 三面一起钉：
     * 1. C# 转写产出的字节流（= PC 落盘形状）必须被 [SaveFormat.read] 吃回，**不得被新增闸误杀**；
     * 2. 读回来的内容与写进去的内容规范化后一致；
     * 3. 再编码一次逐字节不变（写侧对读侧的输出稳定，二次保存不会漂）。
     */
    @Test
    fun `写侧字节流可被读侧吃回且二次编码逐字节稳定`() {
        val bad = ArrayList<String>()
        for ((name, e) in shapes() + randomShapes(200, 0x5A9E7L)) {
            val d = frame(code = 7153L, text = "往返", variables = listOf(e))
            val csBytes = csEncode(d).first
            val back = try {
                SaveFormat.read(plainContainer(csBytes))
            } catch (x: SaveFormatException) {
                bad.add("$name：C# 写侧的字节流被读侧拒绝 —— ${x.message}")
                continue
            } catch (x: RuntimeException) {
                bad.add("$name：读侧抛了非 SaveFormatException 的 ${x::class.simpleName} —— ${x.message}")
                continue
            }
            if (sig(d) != sig(back)) bad.add("$name：读回内容不等\n C#侧入 $${sig(d)}\n 读回 ${sig(back)}")
            val again = SaveFormat.encodePayload(back)
            if (!again.contentEquals(csBytes)) bad.add("$name：二次编码漂移 ${firstDiff(csBytes, again)}")
            // 移植侧自己写出的字节（未压缩分支）也必须与 C# 同形
            val mine = SaveFormat.write(d)
            assertSameBytes("$name：移植侧正文与 C# 一致", csBytes, mine.copyOfRange(16, mine.size))
        }
        assertTrue(bad.isEmpty(), "写侧/读侧不对称（${bad.size} 条）：\n" + bad.take(12).joinToString("\n"))
    }

    /** 四种帧形各自的字段序与收尾符，逐字节对 C# 的四个调用点。 */
    @Test
    fun `四种存档类别的帧结构与 C# 调用点一致`() {
        val block = listOf(
            SaveEntry.IntValue("NO", 3L), SaveEntry.StrValue("NAME", "昴"),
            SaveEntry.Separator, SaveEntry.IntValue("UCV", -1L),
        )
        val entry = SaveEntry.IntArray1("BASE", longArrayOf(0L, 0L, 4L))
        for (kind in listOf(SaveFileKind.NORMAL, SaveFileKind.GLOBAL, SaveFileKind.VAR, SaveFileKind.CHARVAR)) {
            val d = frame(
                kind = kind, code = 7153L, version = 12L,
                text = if (kind == SaveFileKind.GLOBAL) "" else "描述", // SaveGlobal 写空串（VariableEvaluator.cs:2488）
                characters = listOf(block), variables = listOf(entry), emData = listOf(SaveEntry.XmlValue("X", "<r/>")),
            )
            val expected = csEncode(d).first
            val actual = SaveFormat.encodePayload(d)
            assertSameBytes("$kind 帧", expected, actual)
            assertEquals(0xFF, actual.last().toInt() and 0xFF, "$kind 帧末必须是 EOF")
            if (kind == SaveFileKind.CHARVAR) {
                // 最后一个角色块的 EOC 之后补一个 EOF（SaveChara 的 WriteEOF，VariableEvaluator.cs:2270）
                assertEquals(
                    listOf(0xFE, 0xFF), actual.takeLast(2).map { it.toInt() and 0xFF },
                    "CHARVAR 的 EOC 后只补一个 EOF",
                )
            }
            val back = SaveFormat.read(plainContainer(actual))
            assertEquals(kind, back.fileType, "$kind 类别")
            assertSameBytes("$kind 二次编码", actual, SaveFormat.encodePayload(back))
        }
    }

    // ==================================================================
    // 3. jumpIndex 语义：跳过类严格 < limit、行尾类允许 == limit
    // ==================================================================

    /**
     * `jumpIndex`（SaveFormat.kt:403-418）的两条判界与写侧产出形状自洽：
     * - `0xF0/F1/F2` 只在「后面紧跟一个非零值」时被写出（`EraBinaryDataWriter.cs:214-219`、
     *   `:242-253`、`:291-309`），落点必然是真值位 ⇒ 严格 `< limit`；
     * - `0xE0/E1` 是行尾/平面尾标记（`:256-266`、`:311-329`），最后一个非全零行/平面也写 ⇒
     *   落点允许 `== limit`。
     * 另一半是坏档：越界 / 负 / Long 极值跳转必须落 [SaveFormatException]，
     * 且**不得先把 Long 截成 Int**（否则超大跳转会被截成小正数而溜过去）。
     */
    @Test
    fun `游程跳转判界与写侧形状自洽且非法跳转报错`() {
        // 写侧产出的边界：1x2 的行尾 0xE0 使行下标落到 == dim0，读侧必须放行（allowEnd）
        val lastRow = SaveFormat.encodePayload(oneVar(i2(1, 2, 0 to 1L)))
        assertHex(
            "行内游程 0 + 值 1 + 行尾 0xE0 + 三个 EOF",
            hexBytes(0x01, 0xE0, 0xFF, 0xFF, 0xFF), lastRow, lastRow.size - 5,
        )
        assertNotNull(SaveFormat.read(plainContainer(lastRow)), "0xE0 落到 dim0 必须放行")

        // 1x1x1：值之后行尾 0xE0（落到 == dim1）与平面尾 0xE1（落到 == dim0）
        val lastPlane = SaveFormat.encodePayload(oneVar(i3(1, 1, 1, 0 to 5L)))
        assertHex("平面尾 0xE1", hexBytes(0x05, 0xE0, 0xE1, 0xFF, 0xFF, 0xFF), lastPlane, lastPlane.size - 6)
        assertNotNull(SaveFormat.read(plainContainer(lastPlane)), "0xE1 落到 dim0 必须放行")

        fun int1Stream(dim: Int, vararg tail: Int): ByteArray {
            val b = PayloadBuilder()
            b.head(); b.u8(1); b.str("K"); b.i32(dim); tail.forEach { b.u8(it) }
            return plainContainer(b.done())
        }

        // 1D：0xF0 恰好跳到声明长度 ⇒ 落点不是真值位 ⇒ 写侧绝不会产出（strict）
        val reachEnd = assertFailsWith<SaveFormatException>("1D 游程跳到声明长度必须报错") {
            SaveFormat.read(int1Stream(3, 0xF0, 0x03))
        }
        assertTrue(reachEnd.message!!.contains("超出 0..2"), "严格判界文案，实际：${reachEnd.message}")

        // 负游程：不得把下标退回去静默错写
        val negative = assertFailsWith<SaveFormatException>("负游程必须报错") {
            SaveFormat.read(int1Stream(3, 0xF0, 0xD0, 0xFF, 0xFF)) // 0xD0 + Int16(-1)
        }
        assertTrue(negative.message!!.contains("跳转到 -1"), "实际：${negative.message}")

        // Long 极值：先在 Long 域相加，不得 `.toInt()` 截断（Int64 操作数小端：最低位字节在前）
        val huge = assertFailsWith<SaveFormatException>("Long 极值跳转不得被截成小正数") {
            SaveFormat.read(int1Stream(3, 0xF0, 0xD2, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        }
        assertTrue(huge.message!!.contains("9223372036854775807"), "Long 域判界，实际：${huge.message}")
        assertTrue(!huge.message!!.contains("2147483647"), "不得出现截断后的 Int 上界，实际：${huge.message}")

        // 2D：多写一个行尾 ⇒ 行下标落到 dim0+1
        val twoE0 = PayloadBuilder().apply { head(); u8(2); str("K"); i32(1); i32(2); u8(0xE0); u8(0xE0) }
        val e2 = assertFailsWith<SaveFormatException>("二维多写一个行尾必须报错") { SaveFormat.read(plainContainer(twoE0.done())) }
        assertTrue(e2.message!!.contains("超出 0..1"), "实际：${e2.message}")

        // 3D：0xF2 跳到 == dim0（写侧只会在还有值可写时冲刷 ⇒ 落点必然 < dim0）
        val f2 = PayloadBuilder().apply { head(); u8(3); str("K"); i32(2); i32(2); i32(2); u8(0xF2); u8(0x02) }
        val e3 = assertFailsWith<SaveFormatException>("三维平面游程跳到声明平面数必须报错") { SaveFormat.read(plainContainer(f2.done())) }
        assertTrue(e3.message!!.contains("超出 0..1"), "实际：${e3.message}")

        // 字符串数组：第三个 0xD8 落在声明长度之外
        val d8 = PayloadBuilder().apply {
            head(); u8(17); str("K"); i32(2); u8(0xD8); u8(0x00); u8(0xD8); u8(0x00); u8(0xD8); u8(0x00)
        }
        val es = assertFailsWith<SaveFormatException>("字符串数组元素数超过声明长度必须报错") { SaveFormat.read(plainContainer(d8.done())) }
        assertTrue(es.message!!.contains("超过声明的 2"), "实际：${es.message}")

        // 二维列游程跳到 == dim1（0xF0 之后必须有值）
        val colJump = PayloadBuilder().apply { head(); u8(2); str("K"); i32(2); i32(2); u8(0xF0); u8(0x02) }
        val ec = assertFailsWith<SaveFormatException>("二维列游程跳到声明列数必须报错") { SaveFormat.read(plainContainer(colJump.done())) }
        assertTrue(ec.message!!.contains("超出 0..1"), "实际：${ec.message}")
    }

    /** 手写坏档正文的构造器：NORMAL 帧头（类别/码/版本/空描述/角色数 0）之后接条目。 */
    private class PayloadBuilder {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) { o.write(v and 0xFF) }
        fun i32(v: Int) { for (i in 0 until 4) o.write((v ushr (8 * i)) and 0xFF) }
        fun i64(v: Long) { for (i in 0 until 8) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun head() { u8(0); i64(0); i64(0); str(""); i64(0) }

        fun str(s: String) {
            val raw = s.toByteArray(Charsets.UTF_16LE)
            var n = raw.size
            while (true) {
                val x = n and 0x7F
                n = n ushr 7
                if (n != 0) u8(x or 0x80) else { u8(x); break }
            }
            o.write(raw)
        }

        fun done(): ByteArray = o.toByteArray()
    }

    // ==================================================================
    // 4. 变长整数：四档边界与读侧取值范围
    // ==================================================================

    /**
     * `m_WriteInt`（:175-196）与 `m_ReadInt`（`EraBinaryDataReader.cs:33-47`）互为逆：
     * 四档边界逐个钉死期望字节；读侧 `intValue` 的单字节快路径只覆盖 `0..207`
     * （C# 的 `if (b <= 207)`，b 是 byte ⇒ 没有负数歧义），`0xD0` 档是**带符号** Int16。
     */
    @Test
    fun `变长整数四档边界与读侧取值范围`() {
        val expected = linkedMapOf(
            0L to hexBytes(0x00),
            207L to hexBytes(0xCF),
            208L to hexBytes(0xD0, 0xD0, 0x00),
            32767L to hexBytes(0xD0, 0xFF, 0x7F),
            32768L to hexBytes(0xD1, 0x00, 0x80, 0x00, 0x00),
            -1L to hexBytes(0xD0, 0xFF, 0xFF),
            -32768L to hexBytes(0xD0, 0x00, 0x80),
            -32769L to hexBytes(0xD1, 0xFF, 0x7F, 0xFF, 0xFF),
            Int.MAX_VALUE.toLong() to hexBytes(0xD1, 0xFF, 0xFF, 0xFF, 0x7F),
            Int.MIN_VALUE.toLong() to hexBytes(0xD1, 0x00, 0x00, 0x00, 0x80),
            Int.MAX_VALUE.toLong() + 1L to hexBytes(0xD2, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00),
            Int.MIN_VALUE.toLong() - 1L to hexBytes(0xD2, 0xFF, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF),
            Long.MAX_VALUE to hexBytes(0xD2, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F),
            Long.MIN_VALUE to hexBytes(0xD2, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80),
        )
        val diffs = ArrayList<String>()
        for ((v, want) in expected) {
            val one = SaveEntry.IntValue("K", v)
            val payload = SaveFormat.encodePayload(oneVar(one))
            // 条目起点 26：类型字节 1 + 键 "K"（前缀 02 + 41 00）共 4 字节 ⇒ 值落在 30
            assertHex("IntValue($v) 的变长编码", want, payload, entryAt + 4)
            val back = SaveFormat.read(plainContainer(payload)).variables.single() as SaveEntry.IntValue
            assertEquals(v, back.value, "读回 $v（写读互逆）")
            val cs = csEncode(oneVar(one)).first
            if (!cs.contentEquals(payload)) diffs.add("$v：${firstDiff(cs, payload)}")
        }
        assertTrue(diffs.isEmpty(), "四档与 C# 不一致：\n" + diffs.joinToString("\n"))

        // 数组体内的非法档位：落到 `default:`（EraBinaryDataReader.cs:156 抛 AbnormalBinaryData）。
        // 注意 0xF0 在 1D 是合法游程标记，不在这批里；0xF1/0xF2 只在 2D/3D 合法。
        for (tag in listOf(0xD3, 0xD8, 0xE0, 0xE1, 0xF1, 0xF2, 0xFD, 0xFE)) {
            val b = PayloadBuilder().apply { head(); u8(1); str("K"); i32(2); u8(tag) }
            val e = assertFailsWith<SaveFormatException>("一维整数数组里的 0x%02X 必须报错".format(tag)) {
                SaveFormat.read(plainContainer(b.done()))
            }
            assertTrue(
                e.message!!.contains("非法整数值标签"),
                "0x%02X 的报错口径，实际：${e.message}".format(tag),
            )
        }
        // 1D 的游程标记后面接非法整数标签 ⇒ 计数本身就报错（原版 m_ReadInt 的 default 支）
        val badCount = PayloadBuilder().apply { head(); u8(1); str("K"); i32(4); u8(0xF0); u8(0xD3) }
        val bc = assertFailsWith<SaveFormatException>("游程计数自身的标签非法也要报错") {
            SaveFormat.read(plainContainer(badCount.done()))
        }
        assertTrue(bc.message!!.contains("非法变长整数标签 211"), "实际：${bc.message}")
    }

    // ==================================================================
    // 5. 字符串层：空串/null、代理对、前缀档位
    // ==================================================================

    /**
     * `BinaryWriter.Write(string)`（:63-66，writer 由 `Encoding.Unicode` 构造 = :25/:30）：
     * 前缀是 7-bit 的**字节数**。钉四个前缀档位（126/128/16382/16384 字节）与代理对的码元序，
     * 再钉「空串与 null 同形」「Map 的键值不走游程」。
     */
    @Test
    fun `字符串前缀是 UTF 16 字节数且代理对逐码元`() {
        fun textPayload(s: String) = SaveFormat.encodePayload(frame(text = s))

        // 63 个码元 = 126 字节 ⇒ 前缀 1 字节；64 个码元 = 128 字节 ⇒ 前缀 2 字节
        assertHex("126 字节前缀", hexBytes(0x7E), textPayload("a".repeat(63)), 17)
        assertHex("128 字节前缀", hexBytes(0x80, 0x01), textPayload("a".repeat(64)), 17)
        assertHex("16382 字节前缀", hexBytes(0xFE, 0x7F), textPayload("a".repeat(8191)), 17)
        assertHex("16384 字节前缀", hexBytes(0x80, 0x80, 0x01), textPayload("a".repeat(8192)), 17)

        // 代理对 𠮟 = U+20B9F ⇒ D842 DF9F ⇒ UTF-16LE 码元序 42 D8 9F DF（低位字节在前）
        val astral = textPayload("\uD842\uDF9F")
        assertHex("代理对码元", hexBytes(0x04, 0x42, 0xD8, 0x9F, 0xDF), astral, 17)
        assertEquals("\uD842\uDF9F", SaveFormat.read(plainContainer(astral)).text, "代理对读回不得丢低半")
        assertSameBytes("代理对与 C# 同字节", csEncode(frame(text = "\uD842\uDF9F")).first, astral)

        // 数组里的 null 与空串：写侧并入游程（:352），读回一律 null（EraBinaryDataReader.cs:488）
        val nulls = SaveFormat.encodePayload(oneVar(s1(null, "", "x")))
        assertHex(
            "null/空串并入游程", hexBytes(
                0x11, 0x04, 0x53, 0x00, 0x31, 0x00, // 类型 17 + 键 "S1"
                0x03, 0x00, 0x00, 0x00,              // 元素数 Int32 = 3
                0xF0, 0x02,                          // 游程 2
                0xD8, 0x02, 0x78, 0x00,              // 0xD8 + "x"
                0xFF, 0xFF, 0xFF,                    // 数组 EOF + 变量块 EOF + EM 块 EOF
            ),
            nulls, entryAt,
        )
        val back = SaveFormat.read(plainContainer(nulls)).variables.single() as SaveEntry.StrArray1
        assertEquals(listOf<String?>(null, null, "x"), back.values.toList(), "读回 null")
        assertSameBytes("二次编码稳定", nulls, SaveFormat.encodePayload(oneVar(back)))

        // 标量 Str 的空串：前缀 0，与 C# 的 `writeData(null)` 同形（:334-344）
        val emptyScalar = SaveFormat.encodePayload(oneVar(SaveEntry.StrValue("S", "")))
        assertHex("空串标量", hexBytes(0x10, 0x02, 0x53, 0x00, 0x00), emptyScalar, entryAt)
        assertEquals("", (SaveFormat.read(plainContainer(emptyScalar)).variables.single() as SaveEntry.StrValue).value)
        // 键名带代理对：变量名与值同一条编码路径
        val key = SaveFormat.encodePayload(oneVar(SaveEntry.IntValue("\uD842\uDF9F", 5L)))
        assertHex("代理对键名", hexBytes(0x00, 0x04, 0x42, 0xD8, 0x9F, 0xDF, 0x05), key, entryAt)

        // Map：条目数是 Int32 定长，键值是裸串，空键空值照样写出来（:141-154）
        val map = SaveFormat.encodePayload(oneVar(SaveEntry.MapValue("M", listOf("" to "", "k" to "\uD842\uDF9F"))))
        assertHex(
            "Map 条目数 + 空键空值", hexBytes(
                0x20, 0x02, 0x4D, 0x00,   // 类型 32 + 键 "M"
                0x02, 0x00, 0x00, 0x00,   // Count = 2
                0x00, 0x00,               // 空键 + 空值
                0x02, 0x6B, 0x00,         // "k"
                0x04, 0x42, 0xD8, 0x9F, 0xDF, // 代理对值
            ),
            map, entryAt,
        )
        val m = SaveFormat.read(plainContainer(map)).variables.single() as SaveEntry.MapValue
        assertEquals(listOf("" to "", "k" to "\uD842\uDF9F"), m.entries, "Map 保持插入序与空串（:147-151）")

        // 与 C# 同字节（Map / Xml / DataTable）
        for (e in listOf(
            SaveEntry.MapValue("M", listOf("a" to "b")),
            SaveEntry.XmlValue("X", "<root/>"),
            SaveEntry.DataTableValue("D", "<schema/>", "<data/>"),
        )) assertSameBytes("${sig(e)} 与 C# 同字节", csEncode(oneVar(e)).first, SaveFormat.encodePayload(oneVar(e)))
    }

    // ==================================================================
    // 6. 头部与容器：16 字节、条数恒 0、GZip 同壳
    // ==================================================================

    /**
     * `WriteHeader`（:35-51）：魔数 8 + 字面量 `1808u` + 校验表条数 `0u`，表本体的循环上限
     * 是常量 0（`:47` 的 `i < 0L`）⇒ **正文恒从第 16 字节起**。压缩分支把头写在
     * `fileWriter`（= FileStream），GZip 成员紧跟其后（`:37-44` + `Dispose` 的 `:482-493`）。
     */
    @Test
    fun `容器头十六字节且校验表条数恒零`() {
        val payload = SaveFormat.encodePayload(frame(code = 7153L, text = "t"))

        val plain = SaveFormat.write(frame(code = 7153L, text = "t", compressed = false))
        assertEquals(16 + payload.size, plain.size, "未压缩容器 = 16 字节头 + 正文")
        assertEquals(SaveFormat.MAGIC_PLAIN, u64(plain, 0), "未压缩魔数（:43）")
        assertEquals(1808L, u32(plain, 8), "版本恒写字面量 1808（:45）")
        assertEquals(0L, u32(plain, 12), "校验表条数恒 0（:46）")
        assertHex("正文起点", payload, plain, 16)

        val zipped = SaveFormat.write(frame(code = 7153L, text = "t", compressed = true))
        assertEquals(SaveFormat.MAGIC_GZIP, u64(zipped, 0), "GZip 分支魔数（:39）")
        assertEquals(1808L, u32(zipped, 8))
        assertEquals(0L, u32(zipped, 12), "压缩分支同一份头（:37-46）")
        // .NET 8 GZipStream 的头：CM=8、FLG=0、MTIME=0（4 字节）、XFL=0、OS=0x0A（spec §6.1 实测）
        assertHex("GZip 头十字节", hexBytes(0x1F, 0x8B, 0x08, 0x00, 0, 0, 0, 0, 0, 0x0A), zipped, 16)
        val inner = GZIPInputStream(ByteArrayInputStream(zipped, 16, zipped.size - 16)).readBytes()
        assertSameBytes("GZip 成员解出的正文", payload, inner)
        assertEquals(crc32(inner), u32(zipped, zipped.size - 8), "GZip 尾 CRC32（独立实现算）")
        assertEquals(payload.size.toLong() and 0xFFFF_FFFFL, u32(zipped, zipped.size - 4), "GZip 尾 ISIZE")
    }

    /**
     * 反向互通：读侧必须**不看** GZip 头的 MTIME / XFL / OS 三个字段
     * （`GZipStream` 解压同样只看魔数与 CM，`EraBinaryDataReader.cs:807-815`）。
     * `GZIPOutputStream` 写的是「当前时间 + OS=0」，与 .NET 8 的 `MTIME=0 / OS=0x0A`
     * 不同壳、同成员 ⇒ 用它造一份正文，移植侧照样能解。
     */
    @Test
    fun `解压不看 GZip 头的 MTIME 与 OS 字段`() {
        val payload = SaveFormat.encodePayload(frame(code = 1L, text = "异壳"))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(payload) } }.toByteArray()
        assertTrue(
            !gz.copyOfRange(0, 10).contentEquals(hexBytes(0x1F, 0x8B, 0x08, 0x00, 0, 0, 0, 0, 0, 0x0A)),
            "夹具必须与 .NET 8 的头不同，否则这条用例失去意义：${hexOf(gz.copyOfRange(0, 10))}",
        )
        val bytes = plainContainer(gz)
        for (i in 0 until 8) bytes[i] = ((SaveFormat.MAGIC_GZIP ushr (8 * i)) and 0xFF).toInt().toByte()
        val d = SaveFormat.read(bytes)
        assertEquals("异壳", d.text, "MIME/XFL/OS 字段差异不得影响解压")
        assertTrue(d.compressed, "容器判为压缩分支")
        assertSameBytes("解出的正文", payload, SaveFormat.encodePayload(d))
    }

    // ==================================================================
    // 7. 角色块：条目序、角色数、编号复用
    // ==================================================================

    /**
     * 角色块（`CharacterData.cs:513-557`）：系统条目按 `GetVarTokenDic()` 的枚举序逐个
     * `WriteWithKey`，**只有存在用户自定义角色变量时**才先写 `0xFD` 再写它们（:546-556），
     * 块尾 `0xFE`（:557）。角色数是 `CharacterList.Count` 的 Int64（`VariableEvaluator.cs:2598`）
     * ⇒ 「删角色后编号复用」在盘上的表现是：**块序就是装载后的位置序，NO 只是块内一条数据**
     * （`:2631-2636` 逐块 `CharacterList.Add` 后装载，不查 NO）。
     */
    @Test
    fun `角色块条目序角色数与编号复用`() {
        val blocks = listOf(
            listOf(SaveEntry.IntValue("NO", 0L), SaveEntry.StrValue("NAME", "昴"), SaveEntry.IntArray1("BASE", longArrayOf(0L, 3L))),
            listOf(SaveEntry.IntValue("NO", 5L), SaveEntry.StrValue("NAME", "博丽 灵梦"), SaveEntry.IntValue("ISASSI", -1L)),
            listOf(SaveEntry.IntValue("NO", 7L), SaveEntry.StrValue("NAME", "八云 紫"), SaveEntry.Separator, SaveEntry.IntValue("UCV", 42L)),
        )
        val d = frame(code = 7153L, text = "描述", characters = blocks, variables = listOf(SaveEntry.IntValue("DAY", 1L)))
        val expected = csEncode(d).first
        val actual = SaveFormat.encodePayload(d)
        assertSameBytes("角色块帧", expected, actual)

        // 角色数 = 块数（不是 NO 的最大值 + 1）：类别 1 + 码 8 + 版本 8 + 描述串（1 字节前缀 + 4 字节）= 22
        val charaCountAt = 1 + 8 + 8 + 1 + "描述".toByteArray(Charsets.UTF_16LE).size
        assertEquals(3L, u64(actual, charaCountAt), "角色数字段（:2598）")
        // 三个 EOC 收尾三个块
        assertEquals(3, actual.filter { (it.toInt() and 0xFF) == 0xFE }.size, "块尾 EOC 计数")

        val back = SaveFormat.read(plainContainer(actual))
        assertEquals(3, back.characters.size, "读回的块数")
        assertEquals(
            listOf(0L, 5L, 7L),
            back.characters.map { (it.firstOrNull() as SaveEntry.IntValue).value },
            "NO 值原样保留（编号不连续不影响块序）",
        )
        assertEquals(4, back.characters[2].size, "第三个块的 Separator 保留（角色块里 0xFD 合法，CharacterData.cs:591）")
        assertSameBytes("二次编码稳定", actual, SaveFormat.encodePayload(back))

        // 装载：位置 = 流序，NO = 块内数据（与 :2631-2636 同构）
        val store = VariableStore()
        val report = back.applyTo(store, restoreCharacters = true, setLastLoad = true)
        assertEquals(3, store.characters.count, "LOADDATA 后角色数")
        assertEquals(3, report.characters)
        assertEquals(0L, store.readInt("NO", longArrayOf(0L)), "位置 0 的 NO")
        assertEquals(5L, store.readInt("NO", longArrayOf(1L)), "位置 1 的 NO（按流序落位，不是按 NO 落位）")
        assertEquals(7L, store.readInt("NO", longArrayOf(2L)), "位置 2 的 NO")
        assertEquals("八云 紫", store.readStr("NAME", longArrayOf(2L)), "名字随块序落位")

        // 同一份档二次保存：块序与 NO 都不变（编号复用不能被写侧重排；write 已是完整容器，不再包 plainContainer）
        val again = SaveFormat.encodePayload(SaveFormat.read(SaveFormat.write(d)))
        assertSameBytes("重存后字节不变", actual, again)
    }

    /** 空角色块（EOC 立即出现）与零角色帧：读侧不得饿死在块循环里。 */
    @Test
    fun `空角色块与零角色帧`() {
        val emptyBlocks = frame(characters = listOf(emptyList(), emptyList()))
        val expected = csEncode(emptyBlocks).first
        assertSameBytes("两个空块", expected, SaveFormat.encodePayload(emptyBlocks))
        assertEquals(2, SaveFormat.read(plainContainer(expected)).characters.size)
        assertEquals(
            listOf(0, 0),
            SaveFormat.read(plainContainer(expected)).characters.map { it.size },
            "空块读回零条目（EOC 紧跟角色数）",
        )

        val noChara = frame(kind = SaveFileKind.CHARVAR, characters = emptyList())
        val ce = csEncode(noChara).first
        assertSameBytes("零角色 CHARVAR", ce, SaveFormat.encodePayload(noChara))
        val back = SaveFormat.read(plainContainer(ce))
        assertEquals(SaveFileKind.CHARVAR, back.fileType)
        assertTrue(back.characters.isEmpty(), "LoadChara 读 0 个块（VariableEvaluator.cs:2308-2314）")
        assertSameBytes("零角色二次编码", ce, SaveFormat.encodePayload(back))
    }

    // ==================================================================
    // 8. 真档：写侧对拍 + 标记族读数 + 读侧闸余量
    // ==================================================================

    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    /** 语料 `sav/` 优先，回落仓内只读样本目录（与 [com.eratw.port.core.save.SaveFormatAuditTest] 同两处）。 */
    private fun sampleFiles(): List<File> {
        gameDir()?.resolve("sav")?.let { dir ->
            if (Files.isDirectory(dir)) {
                val found = (dir.toFile().listFiles { f: File -> f.isFile && f.name.endsWith(".sav") }?.toList() ?: emptyList())
                    .sortedBy { it.name }
                if (found.isNotEmpty()) return found
            }
        }
        val repo = sequenceOf(
            File("../../.workbuddy/sav-backup/sav-20261018-110513"),
            File("../.workbuddy/sav-backup/sav-20261018-110513"),
            File(".workbuddy/sav-backup/sav-20261018-110513"),
        ).firstOrNull { it.isDirectory } ?: return emptyList()
        return (repo.listFiles { f: File -> f.isFile && f.name.endsWith(".sav") }?.toList() ?: emptyList()).sortedBy { it.name }
    }

    /**
     * **真档正文与 C# 转写逐字节一致**，并统计六个游程标记在真档里的出现次数。
     *
     * 这条比的是「移植侧的解码模型 + 原版写侧算法」能不能重打出 PC 的原字节：
     * 一致 ⇒ 模型对写侧是**无损**的（游程、档位、前缀、帧序一个都不差）；
     * 不一致 ⇒ 要么模型丢了信息（把两种字节形状读成同一个对象），要么参照实现抄错。
     * 标记计数直接取自参照实现，即「原版在这个档里到底写了多少个 `0xF0`」。
     */
    @Test
    fun `真档正文与 C# 转写逐字节一致并统计标记族`() {
        val files = sampleFiles()
        Assumptions.assumeTrue(files.isNotEmpty(), "既无 ERATW_GAMEDIR/sav 也无仓内 sav-backup 样本，跳过真档判据")

        val rows = ArrayList<String>()
        val diffs = ArrayList<String>()
        val totals = IntArray(6)
        for (f in files) {
            val bytes = f.readBytes()
            val d = SaveFormat.read(bytes)
            val payload = requireNotNull(SaveFormat.sourcePayloadOf(bytes)) { "${f.name} 应解出容器正文" }
            val (csBytes, marks) = csEncode(d)
            for (i in 0 until 6) totals[i] += marks[i]
            if (!csBytes.contentEquals(payload)) {
                diffs.add("${f.name}：C# 转写 ${csBytes.size} 字节 vs 原档 ${payload.size} 字节，${firstDiff(csBytes, payload)}")
            }
            rows.add(
                "%-14s 文件=%,d 正文=%,d 比值=%.2f F0=%d F1=%d F2=%d E0=%d E1=%d D8=%d 角色块=%d 全局条目=%d EM=%d".format(
                    f.name, bytes.size, payload.size, payload.size.toDouble() / (bytes.size - 16),
                    marks[0], marks[1], marks[2], marks[3], marks[4], marks[5],
                    d.characters.size, d.variables.size, d.emData.size,
                ),
            )
        }
        val report = rows.joinToString("\n") +
            "\n合计标记：0xF0=%d 0xF1=%d 0xF2=%d 0xE0=%d 0xE1=%d 0xD8=%d\n".format(
                totals[0], totals[1], totals[2], totals[3], totals[4], totals[5],
            )
        File("build").mkdirs()
        File("build/sav-bh3-write-audit.txt").writeText(report, Charsets.UTF_8)
        println("[BH3 写侧对拍]\n$report")
        assertTrue(diffs.isEmpty(), "真档正文与 C# 转写不一致：\n" + diffs.joinToString("\n"))
    }

    /**
     * **真档走重压缩分支后仍可解** + **读侧新增闸对真档的余量**
     * （`SaveFormat.kt:713-757` 五道闸：元素数 16M、单条目 64 MiB、累计 256 MiB、
     * gunzip `min(64MiB, max(1MiB, 文件×256))`、角色数 100 万）。
     * 这条用例本身就是误杀检测器：任何一道闸把真档卡住即闸红。
     */
    @Test
    fun `真档重压缩后可读且读侧上限有余量`() {
        val files = sampleFiles()
        Assumptions.assumeTrue(files.isNotEmpty(), "缺真档样本，跳过闸余量判据")

        val rows = ArrayList<String>()
        val bad = ArrayList<String>()
        for (f in files) {
            val src = f.readBytes()
            val d = SaveFormat.read(src)
            val payload = requireNotNull(SaveFormat.sourcePayloadOf(src)) { "${f.name} 应解出容器正文" }

            // ---- 闸：gunzip 体积上限 ----
            val gate = minOf(64L * 1024 * 1024, maxOf(1L * 1024 * 1024, src.size.toLong() * 256L))
            if (payload.size.toLong() >= gate) bad.add("${f.name}：正文 ${payload.size} 触发 gunzip 上限 $gate（误杀真档）")

            // ---- 重压缩分支（剥掉 sourceFile 的原样回写捷径）----
            val rebuilt = SaveData(
                fileType = d.fileType, uniqueCode = d.uniqueCode, scriptVersion = d.scriptVersion,
                text = d.text, characters = d.characters, variables = d.variables,
                emData = d.emData, compressed = d.compressed, version = d.version,
            )
            val written = SaveFormat.write(rebuilt)
            val back = SaveFormat.read(written)
            assertSameBytes("${f.name}：重压缩后解出的正文", payload, SaveFormat.encodePayload(back))
            assertEquals(sig(d), sig(back), "${f.name}：重压缩往返的字段必须逐项相同")
            if (d.compressed) {
                assertEquals(SaveFormat.MAGIC_GZIP, u64(written, 0), "${f.name}：压缩分支魔数")
                assertEquals(0L, u32(written, 12), "${f.name}：校验表条数恒 0")
                assertHex("${f.name}：GZip 头", hexBytes(0x1F, 0x8B, 0x08, 0x00, 0, 0, 0, 0, 0, 0x0A), written, 16)
                assertEquals(crc32(payload), u32(written, written.size - 8), "${f.name}：CRC32 覆盖正文")
                assertEquals(payload.size.toLong() and 0xFFFF_FFFFL, u32(written, written.size - 4), "${f.name}：ISIZE = 正文长度")
                assertEquals(payload.size, requireNotNull(SaveFormat.sourcePayloadOf(written)).size, "${f.name}：重压缩正文长度不变")
            } else {
                assertEquals(SaveFormat.MAGIC_PLAIN, u64(written, 0), "${f.name}：未压缩魔数")
                assertSameBytes("${f.name}：未压缩分支逐字节同原文件", payload, written.copyOfRange(16, written.size))
            }

            // ---- 闸：元素数 / 单条目字节 / 累计字节 / 角色数 ----
            var total = 0L
            var maxElements = 0
            for (e in d.characters.flatten() + d.variables + d.emData) {
                val n = elements(e) ?: continue
                total += n.toLong() * 8L
                if (n > maxElements) maxElements = n
                if (n > 16_000_000) bad.add("${f.name}：${e.key} 的元素数 $n 超上限 16000000（误杀真档）")
                if (n.toLong() * 8L > 64L * 1024 * 1024) bad.add("${f.name}：${e.key} 单条目 ${n * 8} 字节超 64 MiB")
            }
            if (total >= 256L * 1024 * 1024) bad.add("${f.name}：累计分配 $total 触发 256 MiB 上限（误杀真档）")
            if (d.characters.size > 1_000_000) bad.add("${f.name}：角色数 ${d.characters.size} 触发上限")

            rows.add(
                "%-14s 文件=%,d 正文=%,d 比值=%.2f gunzip闸=%,d 余量=%.1fx 累计分配=%,d 余量=%.1fx 最大元素=%,d 重压缩=%,d".format(
                    f.name, src.size, payload.size, payload.size.toDouble() / (src.size - 16),
                    gate, gate.toDouble() / payload.size, total,
                    (256.0 * 1024 * 1024) / maxOf(1L, total), maxElements, written.size,
                ),
            )
        }
        val report = rows.joinToString("\n") + "\n"
        File("build").mkdirs()
        File("build/sav-bh3-gate-headroom.txt").writeText(report, Charsets.UTF_8)
        println("[BH3 闸余量]\n$report")
        assertTrue(bad.isEmpty(), "读侧闸误杀真档 / 重压缩往返破：\n" + bad.joinToString("\n"))
    }

    /** `claim` 的口径（SaveFormat.kt:774-792）：数组与 Map 按元素数计，每元素 8 字节。 */
    private fun elements(e: SaveEntry): Int? = when (e) {
        is SaveEntry.IntArray1 -> e.values.size
        is SaveEntry.IntArray2 -> e.dim0 * e.dim1
        is SaveEntry.IntArray3 -> e.dim0 * e.dim1 * e.dim2
        is SaveEntry.StrArray1 -> e.values.size
        is SaveEntry.StrArray2 -> e.dim0 * e.dim1
        is SaveEntry.StrArray3 -> e.dim0 * e.dim1 * e.dim2
        is SaveEntry.MapValue -> e.entries.size
        else -> null
    }

    // ==================================================================
    // 9. 报错口径
    // ==================================================================

    /**
     * 截断 / 越界 / 非法标签必须落成 [SaveFormatException]（不能是 OOM、
     * `ArrayIndexOutOfBoundsException`、`NegativeArraySizeException`），并报出卡在哪一字节。
     * 原版的三种文案见 `EraBinaryDataReader.cs:30`（类别）、`:45` 等（二进制标签）、
     * `VariableData.cs:4207-4208`（条目类型）。
     */
    @Test
    fun `截断越界与非法标签都落成 SaveFormatException`() {
        // 类别字节 > 3（EraSaveFileType 只有 0..3；原版 ReadFileType 抛 AbnormalFileData）
        val badKind = PayloadBuilder().apply { u8(4); i64(0); i64(0); str(""); i64(0) }
        val kindErr = assertFailsWith<SaveFormatException>("类别字节 4 必须报错") { SaveFormat.read(plainContainer(badKind.done())) }
        assertTrue(kindErr.message!!.contains("非法的存档类别字节 4"), "实际：${kindErr.message}")

        // 条目类型字节未知（原版落 VariableData.cs:4207-4208）
        val badTag = PayloadBuilder().apply { head(); u8(99); str("K") }
        val tagErr = assertFailsWith<SaveFormatException>("未知条目类型字节必须报错") { SaveFormat.read(plainContainer(badTag.done())) }
        assertTrue(tagErr.message!!.contains("未知的条目类型字节 99"), "实际：${tagErr.message}")

        // 字符串声明长度超过剩余字节
        val longStr = PayloadBuilder().apply { head(); u8(16); str("K"); u8(0xFE); u8(0x7F) }
        val lsErr = assertFailsWith<SaveFormatException>("越界的字符串长度前缀必须报错") { SaveFormat.read(plainContainer(longStr.done())) }
        assertTrue(lsErr.message!!.contains("越界"), "实际：${lsErr.message}")

        // UTF-16 字节数为奇数（原版 .NET 抛 ArgumentException，移植侧收成本地异常）
        val oddStr = PayloadBuilder().apply { head(); u8(16); str("K"); u8(0x03); u8(0x41); u8(0x00); u8(0x42); u8(0xFF) }
        val oddErr = assertFailsWith<SaveFormatException>("UTF-16 字节数为奇数必须报错") { SaveFormat.read(plainContainer(oddStr.done())) }
        assertTrue(oddErr.message!!.contains("奇数"), "实际：${oddErr.message}")

        // 声明长度负 / 超上限：必须是 SaveFormatException，不是 NegativeArraySizeException
        for (declared in listOf(Int.MIN_VALUE, 16_000_001, Int.MAX_VALUE)) {
            val b = PayloadBuilder().apply { head(); u8(1); str("K"); i32(declared) }
            val e = assertFailsWith<SaveFormatException>("声明长度 $declared 必须报错") { SaveFormat.read(plainContainer(b.done())) }
            assertTrue(e.message!!.contains("数组长度异常"), "$declared 的口径，实际：${e.message}")
        }
        // 逐维相乘的溢出防护：65536^3 的乘积没越过 Int 上界但会打碎堆
        val dims = PayloadBuilder().apply { head(); u8(3); str("K"); i32(65_536); i32(65_536); i32(65_536) }
        val dimErr = assertFailsWith<SaveFormatException>("65536 三次的乘积必须被拦住") { SaveFormat.read(plainContainer(dims.done())) }
        assertTrue(dimErr.message!!.contains("元素数"), "实际：${dimErr.message}")

        // 截断：条目读到流尾仍没等到 EOF ⇒ 报错而不是静默少读
        val full = SaveFormat.encodePayload(frame(variables = listOf(SaveEntry.IntArray1("A", longArrayOf(0L, 0L, 5L)))))
        val cut = plainContainer(full.copyOfRange(0, full.size - 2))
        val trunc = assertFailsWith<SaveFormatException>("截断档必须报错") { SaveFormat.read(cut) }
        assertTrue(trunc.message!!.contains("截断"), "实际：${trunc.message}")
        assertNotNull(SaveFormat.peek(cut), "坏档的头探测不受条目层影响（CreateReader 只看容器，EraBinaryDataReader.cs:786-830）")

        // 变量块里的 EOC：原版 `LoadVariableBinary` 的 switch 没有这一支 ⇒ AbnormalData
        val eocBody = PayloadBuilder().apply { head(); u8(0); str("K"); u8(5); u8(0xFE) }
        val eocErr = assertFailsWith<SaveFormatException>("变量块里的 EOC 必须报错") { SaveFormat.read(plainContainer(eocBody.done())) }
        assertTrue(eocErr.message!!.contains("EOC"), "实际：${eocErr.message}")

        // GZip 成员不完整（尾巴的 CRC32 + ISIZE 被砍掉）：也要落 SaveFormatException
        val zipped = SaveFormat.write(frame(text = "压缩", compressed = true))
        val broken = zipped.copyOfRange(0, zipped.size - 8)
        assertFailsWith<SaveFormatException>("GZip 成员缺尾必须报错") { SaveFormat.read(broken) }
    }

    /**
     * `FileSystemOps` 侧的落点：坏档 ⇒ [CodeEE]，且文案必须是原版 Lang 文本
     * `Error.LoadCorruptedData`（`lang.emuera-zhs.xml:728`）；core 层的中文诊断
     * （「截断」「超出」…）**不得**冒到屏上（铁律 1/ADR-005：不在 core 造新文案）。
     */
    @Test
    fun `装载失败经分派层落成原版 Lang 文案`() {
        val full = SaveFormat.encodePayload(frame(variables = listOf(SaveEntry.IntArray1("A", longArrayOf(0L, 0L, 5L)))))
        val truncated = plainContainer(full.copyOfRange(0, full.size - 2))
        val io = object : SaveFileIo {
            val files = HashMap<String, ByteArray>()
            override fun exists(path: String): Boolean = files.containsKey(path)
            override fun read(path: String): ByteArray? = files[path]?.copyOf()
            override fun write(path: String, bytes: ByteArray): Boolean { files[path] = bytes.copyOf(); return true }
            override fun delete(path: String): Boolean = files.remove(path) != null
        }.apply { files["save00.sav"] = truncated }

        val ops = FileSystemOps(VariableStore(), io, SaveConfig(systemSaveInBinary = true, zipSaveData = false))
        val e = assertFailsWith<CodeEE>("LOADDATA 读坏档必须抛 CodeEE") { ops.loadData(0) }
        assertEquals(FileSystemOps.MES_LOAD_CORRUPTED, e.message, "落点文案必须是分派层的 Lang 常量")
        assertEquals("尝试载入数据错误的文件", e.message, "zhs 原文（lang.emuera-zhs.xml:728 的 Error.LoadCorruptedData）")
        for (leak in listOf("截断", "超出", "payload", "0x", "SaveFormat")) {
            assertTrue(!e.message!!.contains(leak), "core 的诊断文本「$leak」不得外泄：${e.message}")
        }

        // SAVEDATA 侧：原版 `SaveTo` 吞掉一切异常返回 false（VariableEvaluator.cs:2666-2669），
        // 文案由语句层用 UnexpectedErrorInSavedata 打 ⇒ 正常路径必须给 true 且字节可回读。
        assertTrue(ops.saveData(0, "x"), "正常写盘返回 true")
        val onDisk = requireNotNull(io.files["save00.sav"]) { "SAVEDATA 应落盘" }
        val reread = SaveFormat.read(onDisk)
        assertSameBytes("落盘容器可被读回", SaveFormat.encodePayload(reread), onDisk.copyOfRange(16, onDisk.size))
        assertEquals("x", reread.text, "SAVEDATA_TEXT 往返")
    }

    // ==================================================================
    // 10. 标记族的出现次数（写侧语义的计数证据）
    // ==================================================================

    /**
     * `0xE0`（224）在 2D/3D 是「行尾」、`0xE1`（225）在 3D 是「平面尾」：写侧对**每一个非全零行/平面**
     * 都写一次（`:256-266`、`:311-329`）；`0xF1/F2` 是「整零行/整零平面」的计数，
     * 只在后面还有非零值时才冲刷 ⇒ **流末的全零尾巴一个标记都不占**。
     *
     * 计数判据来自参照实现，`assertSameBytes` 再把移植侧钉到同一份字节上：
     * 错任何一个分支，要么计数对不上、要么字节对不上。
     */
    @Test
    fun `行尾与平面尾标记的出现次数`() {
        data class Case(
            val label: String,
            val entry: SaveEntry,
            val f0: Int,
            val f1: Int,
            val f2: Int,
            val e0: Int,
            val e1: Int,
            val d8: Int,
            val why: String,
        )
        val cases = listOf(
            Case("A2 1x1 非零", i2(1, 1, 0 to 1L), 0, 0, 0, 1, 0, 0, "一行非全零 ⇒ 一次行尾"),
            Case("A2 2x2 满值", i2(2, 2, 0 to 1L, 1 to 2L, 2 to 3L, 3 to 4L), 0, 0, 0, 2, 0, 0, "两行都非全零 ⇒ 两次行尾，零游程一个不写"),
            Case("A2 2x2 全零", i2(2, 2), 0, 0, 0, 0, 0, 0, "全零行只累加计数，流末不冲刷"),
            Case("A2 3x3 值在第二行", i2(3, 3, 3 to 9L), 0, 1, 0, 1, 0, 0, "首行零 ⇒ 一次 0xF1；值所在行 ⇒ 一次行尾"),
            Case("A2 3x3 值在首行", i2(3, 3, 0 to 9L), 0, 0, 0, 1, 0, 0, "零行计数落在流末 ⇒ 不冲刷"),
            Case("A3 2x2x2 满值", i3(2, 2, 2, *List(8) { i -> i to (i + 1).toLong() }.toTypedArray()), 0, 0, 0, 4, 2, 0, "4 行行尾 + 2 平面平面尾"),
            Case("A3 2x2x2 值在末平面次行", i3(2, 2, 2, 6 to 9L), 0, 1, 1, 1, 1, 0, "首平面全零 ⇒ 0xF2；平面内整行零 ⇒ 0xF1"),
            Case("A3 2x2x2 值在末平面末行中列", i3(2, 2, 2, 5 to 9L), 1, 0, 1, 1, 1, 0, "列游程 ⇒ 0xF0；全零平面 ⇒ 0xF2"),
            Case("A3 1x2x2 末元素", i3(1, 2, 2, 3 to 9L), 1, 1, 0, 1, 1, 0, "行零 ⇒ 0xF1、列零 ⇒ 0xF0、行尾 + 平面尾各一次"),
            Case("A3 全零 2x2x2", i3(2, 2, 2), 0, 0, 0, 0, 0, 0, "全零三维只剩维度与 EOF"),
            Case("S2 2x2 值在末元素", s2(2, 2, 3 to "v"), 1, 1, 0, 1, 0, 1, "空项游程 0xF0 + 零行 0xF1 + 行尾 + 0xD8"),
            Case("S3 2x2x2 值在末元素", s3(2, 2, 2, 7 to "v"), 1, 1, 1, 1, 1, 1, "首平面全空 ⇒ 0xF2；平面内整行空 ⇒ 0xF1"),
            Case("S1 三项含两空", s1(null, "", "x"), 1, 0, 0, 0, 0, 1, "1D 只有 0xF0 与 0xD8"),
        )
        for (c in cases) {
            val (csBytes, marks) = csEncode(oneVar(c.entry))
            assertEquals(
                listOf(c.f0, c.f1, c.f2, c.e0, c.e1, c.d8), marks.toList(),
                "${c.label}（${c.why}）：C# 写侧的标记族计数",
            )
            assertSameBytes("${c.label}：移植侧与 C# 同字节", csBytes, SaveFormat.encodePayload(oneVar(c.entry)))
            assertNotNull(SaveFormat.read(plainContainer(csBytes)), "${c.label}：读侧吃回")
        }
    }

    /**
     * 写侧对「同一份内容」的编码是**唯一**的：把真档读回来、原样再写、再读、再写，
     * 两轮字节必须相同（不随读取顺序、数组实例、Map 迭代序漂移）。
     * 这条是 `SaveCodec.write` 分支能被 PC 侧复读的前提。
     */
    @Test
    fun `写侧对同一内容只有一个字节形状`() {
        val files = sampleFiles()
        Assumptions.assumeTrue(files.isNotEmpty(), "缺真档样本，跳过写侧唯一性判据")
        for (f in files.take(3)) {
            val first = SaveFormat.encodePayload(SaveFormat.read(f.readBytes()))
            val round = { bytes: ByteArray -> SaveFormat.encodePayload(SaveFormat.read(plainContainer(bytes))) }
            // 读→写→读→写：两轮同形
            val second = round(first)
            assertSameBytes("${f.name}：二次读写的正文同形", first, second)
            val third = round(second)
            assertSameBytes("${f.name}：三次读写的正文同形", first, third)
        }
    }
}
