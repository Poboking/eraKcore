package com.eratw.port.core.save

import com.eratw.port.core.data.save.CharaFileIO
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.data.save.SaveFormatException
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

/**
 * `core/save` 与 `core/data/save` 的**格式对拍**（M7 w7-saveaudit）：逐条对反编译原版
 * `EraBinaryDataReader1808` / `EraBinaryDataWriter` / `EraDataReader` / `EraDataWriter`
 * 与 `VariableEvaluator.CheckData` 的字节口径。断言的是**原版写出来的字节**，不是移植侧算出来的值。
 *
 * ## 覆盖面（六个重点口径）
 *
 * 1. 版本链：容器只认 1808（`EraBinaryDataReader.cs:820-824`）；文本扩展区按
 *    `__EMUERA_*_STRAT__` 判档、`< 1803` 走 Old1802 分支、`< 1808` 不读用户扩展段
 *    （`EraDataReader.cs:133-175`、`VariableEvaluator.cs:2453-2471`、`VariableData.cs:3694-3697`）。
 * 2. 校验和：头部第 12 字节起是「校验表条数 + 表本体」，写侧恒 0 且循环上限为 0
 *    （`EraBinaryDataWriter.cs:45-50`），读侧读表但**从不校验**（`EraBinaryDataReader.cs:800-806`
 *    读进 `data` 后无任何消费点）。
 * 3. 字段顺序：`VariableEvaluator.cs:2591-2607`（NORMAL 二进制）、`:2409-2426`（NORMAL 文本）、
 *    `:2483-2493`（GLOBAL 二进制）、`:2498-2503`（GLOBAL 文本）。
 * 4. 字符串编码：.NET `BinaryWriter.Write(string)` = 7-bit 变长**字节数**前缀 + UTF-16LE 原始码元
 *    （`EraBinaryDataWriter.cs:63-66`；对称读法见 `SaveFormat.kt` 的 Cursor.readString）。
 * 5. UseSaveFolder 单候选：`Config.cs:471-481` 的互斥二元分支 + `VariableEvaluator.cs:2018-2037`
 *    的同一条 `SavDir` ⇒ 探测候选恒为一条，且等于落盘路径。
 * 6. CHKDATA 六条分支：`VariableEvaluator.cs:2159-2246` 的命中序
 *    （不存在 → 类别 → 游戏码 → 版本 → OK），文本分支没有类别比较（`:2176-2194`）。
 *
 * ## 与 C# 读法不同的地方
 *
 * M7 开波时本文件**只钉桩不改产品码**（13 条 `TODO(verify)`）。M10-SAVE 把这 6 条读侧产品缺口
 * 收进了产品码，本文件对应的桩同时改成真断言，现无 `TODO(verify)` 残留：
 * 1. 容器头版本 ⇒ `SaveFormat.write` 恒写 1808（EraBinaryDataWriter.cs:45），
 *    `SaveCodec.isBinary` = 魔数 **且** 版本 1808（= `CreateReader` 非 null 的条件）；
 * 2. 文本扩展区档位 ⇒ `SaveTextFormat` 按标记分 1700/1708/1729/1803/1808 分流；
 * 3. 变量块 / EM 块的 EOC 与 Separator ⇒ `SaveFormat.readEntries` 抛 [SaveFormatException]
 *    （VariableData.cs:4207-4208；`LoadVariableBinary` 的 switch 十二个分支里没有 253/254）；
 * 4~5. `GameBase.CheckVersion` 第 1 条 ⇒ 判定本体收进 `GameBaseGate`，
 *    `ScriptVersionDefined` 由 [com.eratw.port.core.save.SaveWiring] 传入（null 才退近似）；
 * 6. 旧档 Old1802 / 1803 布局 ⇒ 角色扩展 4 段 / 6 段、`< 1808` 不读用户 6 段。
 * 另收两条接力时新核实的读侧口径（都出自主文件面 `core/data/save` 目录）：
 * 变量块里的 Separator(0xFD) 由「宽容」改为同样报错（写侧 `WriteSeparator()` 全仓唯一调用点
 * 是 CharacterData.cs:548，变量/EM 段的 `SaveToStreamBinary` VariableData.cs:3980-3997 不写它
 * ⇒ 真档不受影响，语料 5 份 sav 的只读解析 + 逐字节幂等仍绿）；
 * `CharaFileIO.load` 对**非二进制容器**改为静默返回 false、不再抛（VariableEvaluator.cs:2299-2303
 * 的 `CreateReader == null` 一支）。同一差异仍在 `core/fn/CharaFileMethods.kt`
 * 的 CHKCHARADATA 侧（不在本路文件面 ⇒ 交付报告「需组长接线」）。
 */
class SaveFormatAuditTest {

    // ------------------------------------------------------------------
    // 装置：字节层工具
    // ------------------------------------------------------------------

    /** `EraBinaryDataWriter.cs:43` 的未压缩魔数（ASCII 前带 0x89，后段是 `ERA\r\n\x1a\n`）。 */
    private val magicPlain = intArrayOf(0x89, 0x45, 0x52, 0x41, 0x0D, 0x0A, 0x1A, 0x0A)

    /** `EraBinaryDataWriter.cs:39` 的 GZip 魔数（ASCII 前带 0x89，后段是 `ERAZIP\n`）。 */
    private val magicGzip = intArrayOf(0x89, 0x45, 0x52, 0x41, 0x5A, 0x49, 0x50, 0x0A)

    /** .NET 8 `GZipStream` 的 10 字节头（实测语料 `sav/save00.sav` 偏移 16..25）。 */
    private val gzipHead = intArrayOf(0x1F, 0x8B, 0x08, 0x00, 0, 0, 0, 0, 0, 0x0A)

    private fun normal(
        code: Long = 1L,
        version: Long = 0L,
        text: String = "",
        characters: List<List<SaveEntry>> = emptyList(),
        variables: List<SaveEntry> = emptyList(),
        emData: List<SaveEntry> = emptyList(),
        compressed: Boolean = false,
        containerVersion: Int = SaveFormat.VERSION,
    ): SaveData = SaveData(
        fileType = SaveFileKind.NORMAL,
        uniqueCode = code,
        scriptVersion = version,
        text = text,
        characters = characters,
        variables = variables,
        emData = emData,
        compressed = compressed,
        version = containerVersion,
    )

    private fun global(
        code: Long = 1L,
        version: Long = 0L,
        variables: List<SaveEntry> = emptyList(),
        compressed: Boolean = false,
    ): SaveData = SaveData(
        fileType = SaveFileKind.GLOBAL,
        uniqueCode = code,
        scriptVersion = version,
        text = "",
        variables = variables,
        compressed = compressed,
    )

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

    private fun putU32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    /** 按原版写侧结构手工拼一个容器：魔数 8 + 版本 4 + 校验表条数 4 + 校验表 + 正文。 */
    private fun container(magic: IntArray, version: Int, checksums: LongArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 32)
        for (x in magic) out.write(x)
        putU32(out, version.toLong())
        putU32(out, checksums.size.toLong())
        for (c in checksums) putU32(out, c)
        out.write(payload)
        return out.toByteArray()
    }

    private fun assertSlice(expected: IntArray, actual: ByteArray, offset: Int, hint: String) {
        for ((i, want) in expected.withIndex()) {
            val got = actual[offset + i].toInt() and 0xFF
            assertEquals(want, got, "$hint：偏移 ${offset + i} 期望 0x%02X 实际 0x%02X".format(want, got))
        }
    }

    private fun assertSame(expected: ByteArray, actual: ByteArray, offset: Int, hint: String) {
        for (i in expected.indices) {
            val want = expected[i].toInt() and 0xFF
            val got = actual[offset + i].toInt() and 0xFF
            assertEquals(want, got, "$hint：偏移 ${offset + i} 期望 0x%02X 实际 0x%02X".format(want, got))
        }
    }

    // ------------------------------------------------------------------
    // 1. 容器头 16 字节 + 校验表恒空
    // ------------------------------------------------------------------

    /**
     * `WriteHeader`（`EraBinaryDataWriter.cs:35-51`）只写三个字段：魔数 8 + `1808` 4 + 校验表条数 4；
     * 校验表本体的循环上限是常量 0（`:47` 的 `i < 0L`）⇒ **正文恒从第 16 字节开始**，与条数是否非零无关。
     */
    @Test
    fun `容器头十六字节且校验表计数恒零`() {
        val d = normal(code = 7153L, version = 0L, text = "t", compressed = false)
        val payload = SaveFormat.encodePayload(d)

        val plain = SaveFormat.write(d)
        assertEquals(16 + payload.size, plain.size, "未压缩容器 = 16 字节头 + 正文（:35-51）")
        assertSlice(magicPlain, plain, 0, "未压缩魔数（:43）")
        assertEquals(1808L, u32(plain, 8), "版本字段恒 1808（:45）")
        assertEquals(0L, u32(plain, 12), "校验表条数恒写 0（:46）")
        assertSame(payload, plain, 16, "正文起点")

        val zipped = SaveFormat.write(normal(code = 7153L, text = "t", compressed = true))
        assertSlice(magicGzip, zipped, 0, "GZip 魔数（:39）")
        assertEquals(1808L, u32(zipped, 8))
        assertEquals(0L, u32(zipped, 12), "压缩分支同一份头（:37-46）")
        assertSlice(gzipHead, zipped, 16, ".NET 8 GZipStream 头（实测 OS=0x0A）")
        GZIPInputStream(ByteArrayInputStream(zipped, 16, zipped.size - 16)).use {
            assertSame(payload, it.readBytes(), 0, "GZip 成员解出的正文")
        }
    }

    /**
     * 读侧把校验表当**长度字段**用：`CreateReader` 读条数后按条数读走那么多 UInt32
     * （`EraBinaryDataReader.cs:801-806`），此后 `data` 数组再无消费点 ⇒ 「校验和」纯属占位，
     * 非零也不校验。这里手工造一个带两格校验值的容器：正文必须照常解析，且未改动回写保留原样。
     */
    @Test
    fun `非零校验表按计数跳过且不做校验`() {
        val payload = SaveFormat.encodePayload(normal(code = 7L, version = 3L, text = "校验表占位"))
        val bytes = container(magicPlain, 1808, longArrayOf(0xDEADBEEF, 0x0BADF00D), payload)
        assertEquals(16 + 8 + payload.size, bytes.size)

        val head = SaveFormat.peek(bytes)
        assertNotNull(head, "非零校验表不应影响头解析")
        assertEquals(SaveFileKind.NORMAL, head.fileType)

        val d = SaveFormat.read(bytes)
        assertEquals("校验表占位", d.text)
        assertEquals(7L, d.uniqueCode)
        assertEquals(3L, d.scriptVersion)
        assertSame(payload, SaveFormat.sourcePayloadOf(bytes)!!, 0, "正文应跳过两张校验格")
        // 幂等判据：正文未改 ⇒ write 回原字节（含两张校验格）。原版写侧永远写 0 条，
        // 故「原样保留」只在移植侧的 sourceFile 快路径上成立，不会产出非原版形状的新文件。
        assertTrue(SaveFormat.write(d).contentEquals(bytes), "未改动回写应逐字节保留输入")
    }

    // ------------------------------------------------------------------
    // 2. 版本链
    // ------------------------------------------------------------------

    /**
     * 容器版本只有 1808：`CreateReader` 在版本不匹配时返回 null（`EraBinaryDataReader.cs:820-824`），
     * 调用方随即按**文本**格式回落（`VariableEvaluator.cs:2176-2178`、`:2697-2705`）。
     *
     * 三条口径现在逐条对齐原版（M10-SAVE 收口，原为钉桩）：
     * - `SaveFormat.peek` / `SaveFormat.read` 拒收非 1808；
     * - `SaveFormat.write` **恒写常量 1808**（原版 `EraBinaryDataWriter.cs:45` 的 `fileWriter.Write(1808u)`），
     *   不再跟随 `data.version` ⇒ 移植侧不再产出「自己读不回来」的容器；
     * - `SaveCodec.isBinary` = 魔数 **且** 版本 1808（= `CreateReader` 非 null 的条件），
     *   版本不符的容器按文本分派，与原版同一条回落路径；
     * - `CharaFileIO.load`（`LOADCHARA`）遇到非容器字节按 `:2300` **静默返回 false**，不再抛。
     *
     * 写侧收口不影响双向互通：原版 `CreateReader` 只接受恰为 1808 的容器
     * （`EraBinaryDataReader.cs:820-824`），而 `WriteHeader` 也恒写 1808（`:45`）——
     * 旧口径下 `data.version` 若被传成别的值，产出的文件**原版引擎同样判版本不符**，
     * 收口后移植侧写出的容器恰好落在原版唯一接受的那个值上。
     */
    @Test
    fun `容器版本链只认 1808 且写出侧与分派侧同锁`() {
        val payload = SaveFormat.encodePayload(normal(text = "v"))

        for (v in listOf(1807, 1809)) {
            val bytes = container(magicPlain, v, longArrayOf(), payload)
            assertNull(SaveFormat.peek(bytes), "版本 $v 的 peek 必须为 null（:820-824）")
            assertFailsWith<SaveFormatException>("版本 $v 必须拒绝") { SaveFormat.read(bytes) }
            // 分派侧：CreateReader 返回 null ⇒ 原版按文本再读一次（此处按文本解析必然失败，
            // 但落点不再是「二进制解析失败」，而是文本解析器的第一行数字判定）。
            assertFalse(SaveCodec.isBinary(bytes), "版本 $v 不是本层可按二进制读的容器（:820-824）")
            assertFailsWith<SaveFormatException>("版本 $v 必须落到文本分支") {
                SaveCodec.read(bytes, VariableStore().registry, SaveFileKind.NORMAL)
            }
        }

        // 写出侧：`data.version` 不再决定容器头字节（EraBinaryDataWriter.cs:45 写死 1808u）。
        val stamped = SaveFormat.write(normal(text = "stamped", containerVersion = 1807))
        assertEquals(1808L, u32(stamped, 8), "写侧恒 1808，与 data.version 无关（EraBinaryDataWriter.cs:45）")
        assertEquals(0L, u32(stamped, 12), "校验表条数恒写 0（:46）")
        assertTrue(SaveCodec.isBinary(stamped), "自产容器必须落在自己的二进制分派上")
        val back = SaveFormat.read(stamped)
        assertEquals("stamped", back.text, "自产容器自读（旧口径下这里会抛）")
        assertEquals(1808, back.version, "读回的容器版本恒 1808")
        assertSame(stamped, SaveFormat.write(back), 0, "自产容器的往返逐字节相同")

        // ---- LOADCHARA 入口：非二进制容器必须**静默返回 false**，不抛 ----
        // 原版的 `CreateReader == null` 与「类别不符」「游戏码不符」共用同一条
        // `if (...) return;`（VariableEvaluator.cs:2299-2303）⇒ RESULT 保持 0、无报错；
        // CreateReader 的 null 条件 = 短于 16 字节 / 魔数不符 / 版本非 1808
        // （EraBinaryDataReader.cs:790-794、:796-799、:820-824）。
        val charaData = SaveData(
            fileType = SaveFileKind.CHARVAR, uniqueCode = 0L, scriptVersion = 0L,
            text = "chara", characters = listOf(listOf(SaveEntry.IntValue("K", 5L))),
            compressed = false,
        )
        val charaBytes = SaveFormat.write(charaData)
        assertTrue(CharaFileIO.load(charaBytes, VariableStore()), "合格容器照常读入（对照）")
        val textDat = "1\n0\n__FINISHED\n0\n".toByteArray(StandardCharsets.UTF_8)
        assertFalse(
            CharaFileIO.load(textDat, VariableStore()),
            "文本档 .dat ⇒ CreateReader == null ⇒ 静默 false（VariableEvaluator.cs:2300）",
        )
        assertFalse(
            CharaFileIO.load(
                container(magicPlain, 1807, longArrayOf(), SaveFormat.encodePayload(charaData)),
                VariableStore(),
            ),
            "容器版本 1807 ⇒ 静默 false，不抛（:820-824 → :2300）",
        )
        assertFalse(CharaFileIO.load(byteArrayOf(1, 2, 3), VariableStore()), "短于 16 字节 ⇒ 静默 false（:790-794）")
        assertFailsWith<SaveFormatException>("同一份非容器字节直接走解码层仍要报错（差异只在 chara 入口）") {
            SaveFormat.read(textDat)
        }
    }

    // ------------------------------------------------------------------
    // 3. 字符串编码
    // ------------------------------------------------------------------

    /**
     * .NET `BinaryWriter.Write(string)`（`EraBinaryDataWriter.cs:63-66`，writer 由 `Encoding.Unicode`
     * 构造 = `:25/:30`）：7-bit 变长前缀是**字节数**，正文是 UTF-16LE 原始码元。
     * 故 `"あ𠮟"`（3 个 UTF-16 码元、UTF-8 下 7 字节）的前缀必须是 6。
     */
    @Test
    fun `字符串长度前缀是 UTF 16 字节数`() {
        val text = "あ𠮟"
        assertEquals(7, text.toByteArray(StandardCharsets.UTF_8).size, "UTF-8 字节数 7，用来反证前缀不是 UTF-8 长度")
        assertEquals(6, text.length * 2, "UTF-16 码元 3 个（𠮟 是代理对）⇒ 6 字节")

        val payload = SaveFormat.encodePayload(normal(code = 1L, version = 2L, text = text))
        // 正文布局：类别 1（0）| UniqueCode 8 | ScriptVersion 8 | 描述串（前缀 1 + 6）
        assertEquals(0, payload[0].toInt() and 0xFF, "FileType=Normal=0（EraSaveFileType.cs:3-9）")
        assertEquals(6, payload[17].toInt() and 0xFF, "描述串前缀 = UTF-16 字节数 6")
        // UTF-16LE：あ = 42 30；𠮟 = 代理对 D842 DF9F ⇒ 42 D8 9F DF（低位字节在前）
        assertSlice(intArrayOf(0x42, 0x30, 0x42, 0xD8, 0x9F, 0xDF), payload, 18, "あ / 𠮟 的 UTF-16LE 码元")

        // 变量名同一编码；值 0 落成单字节 0
        val withEntry = SaveFormat.encodePayload(normal(variables = listOf(SaveEntry.IntValue("K", 0L))))
        assertEquals(0, withEntry[26].toInt() and 0xFF, "IntValue 类型字节 0（:87）")
        assertEquals(2, withEntry[27].toInt() and 0xFF, "变量名 K 的 UTF-16 字节数 2")
        assertEquals(0x4B, withEntry[28].toInt() and 0xFF)
        assertEquals(0x00, withEntry[29].toInt() and 0xFF, "UTF-16LE 高位字节")
        assertEquals(0, withEntry[30].toInt() and 0xFF, "值 0 的变长编码单字节（:177-180）")

        val empty = SaveFormat.encodePayload(normal(code = 0L, version = 0L, text = ""))
        assertEquals(0, empty[17].toInt() and 0xFF, "空串前缀 0（null 与空串同形，:334-344）")
    }

    // ------------------------------------------------------------------
    // 4. 变长整数 + 一维零游程
    // ------------------------------------------------------------------

    /**
     * `m_WriteInt`（`EraBinaryDataWriter.cs:175-196`）四档：`0..207` 直写；short 档前置 208；
     * int 档前置 209；其余 210。`writeData(long[])`（`:203-223`）在前导零处写 240 + 零个数。
     * 期望字节由这四个档位直接手算，不经过移植侧编码器。
     */
    @Test
    fun `变长整数四档与一维零游程标签`() {
        val values = longArrayOf(207L, 208L, 32768L, -1L)
        val payload = SaveFormat.encodePayload(normal(variables = listOf(SaveEntry.IntArray1("K", values))))
        // 偏移 26 = 条目起点（类别 1 + 码 8 + 版本 8 + 空描述 1 + 角色数 8）
        assertEquals(0x01, payload[26].toInt() and 0xFF, "IntArray 类型字节 1（:94）")
        assertEquals(2, payload[27].toInt() and 0xFF)
        assertEquals(0x4B, payload[28].toInt() and 0xFF)
        assertEquals(4L, u32(payload, 30), "数组长度 = 元素个数（Int32，:205）")
        assertSlice(
            intArrayOf(
                0xCF, // 207 直写（:177-180）
                0xD0, 0xD0, 0x00, // 208 ⇒ 标签 208 + Int16 208（:181-185）
                0xD1, 0x00, 0x80, 0x00, 0x00, // 32768 ⇒ 标签 209 + Int32（:186-190）
                0xD0, 0xFF, 0xFF, // -1 ⇒ 标签 208 + Int16 -1（:181-185）
                0xFF, // 数组结束（:222）
                0xFF, // 变量块 EOF（VariableEvaluator.cs:2604）
                0xFF, // EM 块 EOF（:2606）
            ),
            payload, 34, "变长整数与 EOF 收尾",
        )
        assertEquals(49, payload.size)

        // 前导零：0,0,5 ⇒ 240 + 零个数 2，然后值 5（:207-221）
        val zeros = SaveFormat.encodePayload(normal(variables = listOf(SaveEntry.IntArray1("K", longArrayOf(0L, 0L, 5L)))))
        assertSlice(intArrayOf(0xF0, 0x02, 0x05), zeros, 34, "零游程标签 240（:214-219）")
    }

    // ------------------------------------------------------------------
    // 5. 二维数组的零行/行尾标签
    // ------------------------------------------------------------------

    /**
     * `writeData(long[,])`（`EraBinaryDataWriter.cs:225-267`）：整行零 ⇒ 记一行（标签 241 + 行数，
     * `:242-247`），非零行尾写 224（`:256-263`）。读侧 241 只跳行、224 只换行（`EraBinaryDataReader.cs:204-233`）。
     */
    @Test
    fun `二维数组整零行标签与行尾标签`() {
        val payload = SaveFormat.encodePayload(
            normal(variables = listOf(SaveEntry.IntArray2("A2", 2, 2, longArrayOf(0L, 0L, 1L, 2L)))),
        )
        assertEquals(2, payload[26].toInt() and 0xFF, "IntArray2D 类型字节 2（:99-105）")
        // 变量名 "A2" 占 2 个 UTF-16 码元 = 4 字节前缀（:63-66）
        assertEquals(4, payload[27].toInt() and 0xFF, "变量名 A2 的 UTF-16 字节数 4")
        assertEquals(2L, u32(payload, 32), "dim0 先写（:231）")
        assertEquals(2L, u32(payload, 36), "dim1 后写（:232）")
        assertSlice(
            intArrayOf(
                0xF1, 0x01, // 整行零 ×1（:244-245）
                0x01, 0x02, // 第二行两个值
                0xE0, // 行尾（:262）
                0xFF, // 数组结束（:266）
                0xFF, // 变量块 EOF
                0xFF, // EM 块 EOF
            ),
            payload, 40, "二维零行 / 行尾",
        )

        // 全零 2×2：零行计数累积到行尾，一个 241 都不写（:256-264 只在遇到非零值时才冲刷）
        val allZero = SaveFormat.encodePayload(
            normal(variables = listOf(SaveEntry.IntArray2("A2", 2, 2, LongArray(4)))),
        )
        assertSlice(intArrayOf(0xFF, 0xFF, 0xFF), allZero, 40, "全零二维数组只剩三个 EOF")
        assertEquals(43, allZero.size)
    }

    // ------------------------------------------------------------------
    // 6. 字段顺序与块终止符
    // ------------------------------------------------------------------

    /**
     * NORMAL 二进制布局（`VariableEvaluator.cs:2591-2607`）：
     * 类别 1 | 游戏码 8 | 脚本版本 8 | 描述串 | 角色数 8 | 每角色（条目… + EOC 254，`CharacterData.cs:557`）
     * | 变量… + EOF 255（`:2604`）| EM… + EOF 255（`:2606`）。
     *
     * CHARVAR（`VariableEvaluator.cs:2248-2270`）多一个「最后一个角色块之后」的 EOF（`:2270`），
     * 且**没有**变量块与 EM 块；`LoadChara` 只读 num 个角色、不消费这个 EOF（`:2308-2314`）。
     */
    @Test
    fun `二进制字段顺序与 EOC EOF 收尾`() {
        val block = listOf<SaveEntry>(SaveEntry.IntValue("K", 5L))
        val payload = SaveFormat.encodePayload(normal(text = "", characters = listOf(block)))
        assertEquals(0, payload[0].toInt() and 0xFF, "类别")
        assertEquals(1L, u32(payload, 1), "UniqueCode 在类别之后（:2595）")
        assertEquals(0L, u32(payload, 9), "ScriptVersion 紧随其后（:2596）")
        assertEquals(0, payload[17].toInt() and 0xFF, "描述串在前（:2597）")
        assertEquals(1L, u32(payload, 18), "角色数（:2598）")
        // 角色块：IntValue("K",5) = 00 | 02 'K' 00 | 05
        assertSlice(intArrayOf(0x00, 0x02, 0x4B, 0x00, 0x05), payload, 26, "角色块条目")
        assertSlice(intArrayOf(0xFE, 0xFF, 0xFF), payload, 31, "EOC 收角色块，随后变量块 EOF 与 EM 块 EOF")
        assertEquals(34, payload.size)

        // CHARVAR：末尾多一个 EOF，且不写变量块（variables 被忽略）
        val charvar = SaveData(
            fileType = SaveFileKind.CHARVAR,
            uniqueCode = 1L,
            scriptVersion = 0L,
            text = "",
            characters = listOf(block),
            variables = listOf(SaveEntry.IntValue("V", 9L)),
            compressed = false,
        )
        val cp = SaveFormat.encodePayload(charvar)
        assertEquals(3, cp[0].toInt() and 0xFF, "CharVar 类别字节 3（EraSaveFileType.cs:3-9）")
        assertEquals(1L, u32(cp, 18), "角色数（:2265）")
        assertSlice(intArrayOf(0xFE, 0xFF), cp, 31, "EOC 之后补一个 EOF（:2270）")
        assertEquals(33, cp.size, "CHARVAR 不写变量块 / EM 块（:2265-2270）")
        val back = SaveFormat.read(SaveFormat.write(charvar))
        assertEquals(SaveFileKind.CHARVAR, back.fileType)
        assertEquals(1, back.characters.size, "LoadChara 只读 num 个角色（:2308-2314）")
    }

    /**
     * 变量块（与 EM 块）的终止符只有 EOF：原版 `LoadVariableBinary` 的 switch 里**没有** EOC 支，
     * 读到 254 落 `default:` 抛 `FileEE(AbnormalData)`（`VariableData.cs:4207-4208`；
     * EOC 的返回点见 `EraBinaryDataReader.cs:82-90`）。EOC 只在角色块里合法
     * （`CharacterData.LoadFromStreamBinary` 的 `case EOC: case EOF: return`，`CharacterData.cs:672-674`，
     * 写侧 `CharacterData.cs:557`）。
     *
     * M10-SAVE 收口：移植侧 `readEntries` 原来把 254 当「变量块结束」并把条目照常读入，
     * 于是损坏档能被正常装载；现在与原版同口径抛 [SaveFormatException]，
     * 装载侧（`FileSystemOps.loadData`）落成 `CodeEE`。
     * 接力时补上同一 `default:` 的另一半 —— Separator(253)：原版的变量/EM 段读循环同样没有它的
     * 分支（`LoadFromStreamBinary` 用同一个 `LoadVariableBinary`，`VariableData.cs:3999-4004`），
     * 而写侧 `WriteSeparator()` 全仓唯一调用点在角色块（`CharacterData.cs:548`），
     * 变量/EM 段的 `SaveToStreamBinary`（`VariableData.cs:3980-3997`）只走 `WriteWithKey`
     * ⇒ 真档里没有 253，命中即损坏档（语料 5 份 sav 只读解析 + 逐字节幂等仍绿即为实证）。
     */
    @Test
    fun `变量块里的 EOC 与 Separator 都和原版同样报错`() {
        val payload = byteArrayOf(
            0x00, // 类别 Normal
            0x07, 0, 0, 0, 0, 0, 0, 0, // UniqueCode 7
            0x00, 0, 0, 0, 0, 0, 0, 0, // ScriptVersion 0
            0x00, // 描述串长度 0
            0x00, 0, 0, 0, 0, 0, 0, 0, // 角色数 0
            0x00, 0x02, 0x4B, 0x00, 0x05, // IntValue(K, 5)
            0xFE.toByte(), // 变量块用 EOC 收尾（VariableData.cs:4207-4208 抛 FileEE）
            0xFF.toByte(), // EM 块 EOF
        )
        val bytes = container(magicPlain, 1808, longArrayOf(), payload)

        val e = assertFailsWith<SaveFormatException>("EOC 收尾的变量块必须判损坏档") { SaveFormat.read(bytes) }
        assertTrue(e.message!!.contains("EOC"), "报错文案点名 EOC，实际：${e.message}")

        // 同一份字节经分派层也必须失败（容器版本 1808 ⇒ 走二进制分支，不是文本回落）
        assertTrue(SaveCodec.isBinary(bytes), "魔数 + 1808 ⇒ 二进制容器")
        assertFailsWith<SaveFormatException> {
            SaveCodec.read(bytes, VariableStore().registry, SaveFileKind.NORMAL)
        }
        // 头探测不看条目，仍给 OK（原版 CreateReader 也只解容器）
        assertNotNull(SaveFormat.peek(bytes), "坏档的头探测不受条目层影响（:786-830）")

        // 对照：同一份正文改用 EOF 收尾 ⇒ 正常读入（证明上一条失败点确实在 EOC 而不是别处）
        val good = payload.toMutableList().also { it[it.size - 2] = 0xFF.toByte() }.toByteArray()
        val d = SaveFormat.read(container(magicPlain, 1808, longArrayOf(), good))
        assertEquals(SaveFileKind.NORMAL, d.fileType)
        assertTrue(
            d.variables.any { it is SaveEntry.IntValue && it.key == "K" && it.value == 5L },
            "EOF 收尾的变量块照常读入",
        )

        // 角色块里 EOC 依旧合法（CharacterData.cs:672-674），不能跟着一起收严
        val charaPayload = SaveFormat.encodePayload(
            normal(characters = listOf(listOf(SaveEntry.IntValue("K", 5L)))),
        )
        val chara = SaveFormat.read(container(magicPlain, 1808, longArrayOf(), charaPayload))
        assertEquals(1, chara.characters.size, "角色块以 EOC 收尾（:2598-2602）")

        // 变量块里的 Separator(0xFD) 落同一个 default ⇒ 同样判损坏档
        // （LoadVariableBinary 的 switch 只有十二个分支，没有 253/254：VariableData.cs:4093-4208）
        val sepPayload = payload.toMutableList().also { it[it.size - 2] = 0xFD.toByte() }.toByteArray()
        val sep = assertFailsWith<SaveFormatException>("变量块里的 0xFD 也必须判损坏档") {
            SaveFormat.read(container(magicPlain, 1808, longArrayOf(), sepPayload))
        }
        assertTrue(sep.message!!.contains("Separator"), "报错文案点名 Separator，实际：${sep.message}")
        assertFailsWith<SaveFormatException>("EM 块里的 0xFD 同样非法") {
            // 变量块只给一个 EOF，随后 EM 块的首字节是 0xFD —— 两个块共用同一个 readEntries
            val emPayload = byteArrayOf(
                0x00, 0x07, 0, 0, 0, 0, 0, 0, 0, 0x00, 0, 0, 0, 0, 0, 0, 0, 0x00,
                0x00, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFD.toByte(),
            )
            SaveFormat.read(container(magicPlain, 1808, longArrayOf(), emPayload))
        }

        // 对照：角色块里的 Separator 合法（`case Separator:` CharacterData.cs:591，
        // 写侧 `WriteSeparator()` 全仓唯一调用点 CharacterData.cs:548）⇒ 收严不能波及角色块
        val charaSep = SaveFormat.read(
            container(
                magicPlain, 1808, longArrayOf(),
                SaveFormat.encodePayload(
                    normal(characters = listOf(listOf(SaveEntry.IntValue("K", 5L), SaveEntry.Separator))),
                ),
            ),
        )
        assertTrue(
            charaSep.characters.single().contains(SaveEntry.Separator),
            "角色块保留 Separator 条目（:591）—— 真语料 save00.sav 就有两个带分隔符的角色块",
        )
    }

    // ------------------------------------------------------------------
    // 7. 文本格式行序与标记
    // ------------------------------------------------------------------
    /**
     * 文本 NORMAL（`VariableEvaluator.cs:2409-2426`）：游戏码 / 脚本版本 / 描述 / 角色数 /
     * 角色正文（2 String + 2 Int + 17 Int1D，`CharacterData.cs:353-369`）/
     * 60 个 Int1D + 1 个 Str1D（`VariableData.cs:3516-3534`）/ `__EMUERA_1808_STRAT__` /
     * 8 段系统扩展 + 6 段用户扩展（各以 `__EMU_SEPARATOR__` 收尾，`VariableData.cs:3556-3626`）。
     *
     * 文本 GLOBAL（`:2498-2503`）：游戏码 / 脚本版本 / GLOBAL / GLOBALS / EMU_START / 6 段用户扩展，
     * **没有描述行**（二进制分支写的 `""` 在文本分支不存在）。
     */
    @Test
    fun `文本格式行序与扩展段标记`() {
        val registry = VariableStore().registry

        val nBytes = SaveTextFormat.write(normal(code = 7153L, version = 0L, text = "desc"), registry)
        assertSlice(intArrayOf(0xEF, 0xBB, 0xBF), nBytes, 0, "UTF-8 BOM（Config.cs:19、EraDataWriter.cs:24）")
        val nl = String(nBytes, 3, nBytes.size - 3, StandardCharsets.UTF_8).split('\n')
        assertEquals("7153", nl[0], "第 1 行 UniqueCode（:2411）")
        assertEquals("0", nl[1], "第 2 行 ScriptVersion（:2412）")
        assertEquals("desc", nl[2], "第 3 行描述（:2413）")
        assertEquals("0", nl[3], "角色数（:2414）")
        assertTrue((4..64).all { nl[it] == "__FINISHED" }, "60 个 Int1D + 1 个 Str1D 全空 ⇒ 61 行 FINISHED")
        assertEquals("__EMUERA_1808_STRAT__", nl[65], "EMU_START 拼写照抄 STRAT（EraDataWriter.cs:13）")
        assertTrue((66..79).all { nl[it] == "__EMU_SEPARATOR__" }, "8 段系统 + 6 段用户扩展")
        assertEquals("", nl[80], "末尾一个换行")
        assertEquals(81, nl.size)

        val gBytes = SaveTextFormat.write(global(code = 7153L, version = 0L), registry)
        val gl = String(gBytes, 3, gBytes.size - 3, StandardCharsets.UTF_8).split('\n')
        assertEquals("7153", gl[0])
        assertEquals("0", gl[1])
        assertEquals("__FINISHED", gl[2], "GLOBAL 数组（槽 63）")
        assertEquals("__FINISHED", gl[3], "GLOBALS 数组（槽 5，VariableData.cs:3804-3814）")
        assertEquals("__EMUERA_1808_STRAT__", gl[4], "全局档没有描述行（:2498-2502）")
        assertTrue((5..10).all { gl[it] == "__EMU_SEPARATOR__" }, "6 段用户全局扩展")
        assertEquals(12, gl.size)

        // 读回自产文本：空变量池也应无异常，类别由调用方给（文本容器没有类别字节）
        val readBack = SaveTextFormat.read(gBytes, registry, SaveFileKind.GLOBAL)
        assertEquals(SaveFileKind.GLOBAL, readBack.fileType)
        assertEquals("", readBack.text, "全局档描述恒空（VariableEvaluator.cs:2488 同源口径）")
        assertTrue(readBack.variables.any { it.key == "GLOBAL" }, "GLOBAL 槽被读成空数组条目")
    }

    /**
     * 文本扩展区的**版本链**：标记集合 `__EMUERA_STRAT__`(1700) / `1708` / `1729` / `1803` / `1808`
     * （`EraDataReader.cs:14-26`、`:133-175`）。原版按 `DataVersion` 分流：
     * `< 1803` 走 `LoadFromStreamExtended_Old1802`（`VariableEvaluator.cs:2457-2462`、
     * `CharacterData.cs:477-511`，角色扩展只有 **4 段**），`< 1808` 在读完 8 段系统扩展后
     * **直接返回**、不读 6 段用户扩展（`VariableData.cs:3694-3697`）。
     *
     * M10-SAVE 收口（原为钉桩）：移植侧已按档位分流 ⇒ 旧档形状能读进来，
     * 且档位错配的形状必须失败（证明分流真的生效，不是「一律宽容」）。
     */
    @Test
    fun `文本扩展区按标记档位分流旧段数`() {
        val registry = VariableStore().registry

        // 装置自证：本用例的旧档拼装器在 1808 档下必须逐字节等于移植侧写侧的产物
        val reference = SaveTextFormat.write(normal(code = 1L, version = 0L, text = "old"), registry)
        assertEquals(
            String(reference, 3, reference.size - 3, StandardCharsets.UTF_8),
            oldNormalText(SaveTextFormat.EMU_START, charaCount = 0, charaSegs = 6, userSegs = 6),
            "旧档拼装器与 1808 写侧同源（VariableEvaluator.cs:2409-2426）",
        )

        // 1803 档：角色 6 段 + 系统 8 段，**没有**用户 6 段 ⇒ 原版读完 8 段即返回
        val t1803 = oldNormalText("__EMUERA_1803_STRAT__", charaCount = 0, charaSegs = 6, userSegs = 0)
        val d1803 = SaveTextFormat.read(t1803.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
        assertEquals(1L, d1803.uniqueCode, "1803 档旧档读完 8 段即返回（VariableData.cs:3694-3697）")
        assertEquals("old", d1803.text)

        // 带角色的 1803 档：同样只读 8 段
        val t1803c = oldNormalText("__EMUERA_1803_STRAT__", charaCount = 2, charaSegs = 6, userSegs = 0)
        assertEquals(2, SaveTextFormat.read(t1803c.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
            .characters.size, "1803 档带角色档可读（CharacterData.cs:425-475）")

        // Old1802 档（< 1803）：角色扩展只有 4 段（CharacterData.cs:477-511）。
        // 系统段随后按 emu_version 消费：2D 两段在 <1708 时不消费（EraDataReader.cs:351-354 /
        // :415-418），3D 两段在 <1729 时不消费（:438-441 / :516-519）⇒ 1700=4、1708=6、1729=8。
        for ((marker, sysSegs) in listOf(
            "__EMUERA_STRAT__" to 4,
            "__EMUERA_1708_STRAT__" to 6,
            "__EMUERA_1729_STRAT__" to 8,
        )) {
            val old = oldNormalText(marker, charaCount = 2, charaSegs = 4, userSegs = 0, sysSegs = sysSegs)
            val d = SaveTextFormat.read(old.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
            assertEquals(2, d.characters.size, "$marker 走 Old1802 四段（VariableEvaluator.cs:2457-2462）")
        }

        // 分流真的生效：1803 档却只给 4 段角色扩展 ⇒ 段数对不上，必须报错而不是静默少读
        assertFailsWith<SaveFormatException>("< 1803 的段数不适用于 1803 档") {
            SaveTextFormat.read(
                oldNormalText("__EMUERA_1803_STRAT__", charaCount = 1, charaSegs = 4, userSegs = 0)
                    .toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL,
            )
        }
        // 反向：1808 档只给 4 段角色扩展 ⇒ 同样报错
        assertFailsWith<SaveFormatException>("1808 档需要 6 段角色扩展") {
            SaveTextFormat.read(
                oldNormalText(SaveTextFormat.EMU_START, charaCount = 1, charaSegs = 4, userSegs = 6)
                    .toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL,
            )
        }
        // 1803 档却带上了 6 段用户扩展 ⇒ 原版读完 8 段就 return，多出来的段落根本不消费
        val extra = oldNormalText("__EMUERA_1803_STRAT__", charaCount = 0, charaSegs = 6, userSegs = 6)
        assertNotNull(
            SaveTextFormat.read(extra.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL),
            "尾随的用户扩展段被忽略（VariableData.cs:3694-3697 的 return）",
        )

        // 全局档不看档位：只要命中标记就读 6 段用户全局扩展（VariableEvaluator.cs:2563-2566）
        for (marker in listOf("__EMUERA_STRAT__", "__EMUERA_1803_STRAT__", SaveTextFormat.EMU_START)) {
            val g = "1\n0\n__FINISHED\n__FINISHED\n$marker\n" +
                (1..6).joinToString("\n", postfix = "\n") { SaveTextFormat.SEPARATOR }
            val d = SaveTextFormat.read(g.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.GLOBAL)
            assertEquals(SaveFileKind.GLOBAL, d.fileType, "$marker 的全局档照读 6 段")
        }
        // 没有标记：正文后即结束 ⇒ 完全不碰扩展区（VariableEvaluator.cs:2453-2456）
        val noMarker = oldNormalText("", charaCount = 1, charaSegs = 0, sysSegs = 0, userSegs = 0)
        val dNoMarker = SaveTextFormat.read(
            noMarker.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL,
        )
        assertEquals(1, dNoMarker.characters.size, "无标记时只读正文（:2453-2456）")
        // 同一份正文补上「全空的扩展区」⇒ 变量条目必须完全一致（扩展区一个条目都没贡献）
        val dEmptyExt = SaveTextFormat.read(
            oldNormalText(SaveTextFormat.EMU_START, charaCount = 1, charaSegs = 6, userSegs = 6)
                .toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL,
        )
        assertEquals(
            dEmptyExt.variables.map { it.key }, dNoMarker.variables.map { it.key },
            "无标记与空扩展区的读数相同（正文之外的段落没有条目）",
        )
    }

    /**
     * 文本旧档的**系统扩展段数**随 `EraDataReader.DataVersion` 变：`LoadFromStreamExtended`
     * 恒按 8 段顺序调用 reader（VariableData.cs:3630-3637），但 2D 两段在 `< 1708`、
     * 3D 两段在 `< 1729` 时直接返回、**不消费行**（EraDataReader.cs:351-354 / :415-418 /
     * :438-441 / :516-519）⇒ 1700 档 4 段、1708 档 6 段、1729 及以上 8 段。
     *
     * 每档钉三次：段数恰好 ⇒ 读得进；少一段 ⇒ 文件尾读不到分隔符，抛「存档在读取字符串时
     * 意外结束」；多一段 ⇒ 多出的分隔符不在 reader 的消费范围里（early-return 照抄，不是把剩余行读完）。
     */
    @Test
    fun `旧档系统扩展段按版本消费 4-6-8 段`() {
        val registry = VariableStore().registry
        val cases = listOf(
            "__EMUERA_STRAT__" to 4,
            "__EMUERA_1708_STRAT__" to 6,
            "__EMUERA_1729_STRAT__" to 8,
        )
        for ((marker, segs) in cases) {
            val ok = oldNormalText(marker, charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = segs)
            val d = SaveTextFormat.read(ok.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
            assertEquals(1L, d.uniqueCode, "$marker 恰好 $segs 段系统扩展应可读")

            val short = oldNormalText(marker, charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = segs - 1)
            val e = assertFailsWith<SaveFormatException>("$marker 少一段必须报错") {
                SaveTextFormat.read(short.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
            }
            assertTrue(
                e.message!!.contains("意外结束"),
                "$marker 少一段的错误应是文件尾（EraDataReader.cs:45 的 NoStrToRead）：${e.message}",
            )

            val extra = oldNormalText(marker, charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = segs + 1)
            assertNotNull(
                SaveTextFormat.read(extra.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL),
                "$marker 多出的分隔符不应被消费（CS 的 2D/3D reader early-return 后不再读）",
            )
        }
    }

    /**
     * 旧档系统 2D/3D 段的落值位置：1708 的 6 段里第 6 段是 INT2（`DITEMTYPE`），
     * 1729 的第 8 段是 INT3（`TA`）；1700 只消费 4 段，即使盘上跟着 2D 段也不读。
     * 读入后再写（1808）→ 再读，2D/3D 值不变。
     */
    @Test
    fun `旧档系统 2D-3D 段按版本读入并可往返`() {
        val registry = VariableStore().registry
        val ff = SaveTextFormat.FINISHED

        val t1700 = oldNormalText(
            "__EMUERA_STRAT__", charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = 5,
            sysSegLines = listOf(
                emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("DITEMTYPE", "7,8", ff),
            ),
        )
        val d1700 = SaveTextFormat.read(t1700.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
        assertTrue(
            d1700.variables.none { it.key == "DITEMTYPE" },
            "1700 档不读 2D 段（EraDataReader.cs:351-354 / :415-418 的 early-return）",
        )

        val t1708 = oldNormalText(
            "__EMUERA_1708_STRAT__", charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = 6,
            sysSegLines = listOf(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("DITEMTYPE", "7,8", ff),
            ),
        )
        val d1708 = SaveTextFormat.read(t1708.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
        val di = d1708.variables.single { it.key == "DITEMTYPE" } as SaveEntry.IntArray2
        assertEquals(1, di.dim0, "DITEMTYPE 行数")
        assertEquals(2, di.dim1, "DITEMTYPE 列数")
        assertEquals(listOf(7L, 8L), di.values.toList(), "DITEMTYPE 值")

        val t1729 = oldNormalText(
            "__EMUERA_1729_STRAT__", charaCount = 0, charaSegs = 0, userSegs = 0, sysSegs = 8,
            sysSegLines = listOf(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("TA", "0{", "9", "}", ff),
            ),
        )
        val d1729 = SaveTextFormat.read(t1729.toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
        val ta = d1729.variables.single { it.key == "TA" } as SaveEntry.IntArray3
        assertEquals(1, ta.dim0, "TA 平面数")
        assertEquals(1, ta.dim1, "TA 行数")
        assertEquals(1, ta.dim2, "TA 列数")
        assertEquals(listOf(9L), ta.values.toList(), "TA 值")

        fun shape(e: SaveEntry): String = when (e) {
            is SaveEntry.IntArray2 -> "I2:${e.dim0}x${e.dim1}:" + e.values.joinToString(",")
            is SaveEntry.IntArray3 -> "I3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.joinToString(",")
            else -> e.toString()
        }
        for (d in listOf(d1708, d1729)) {
            val back = SaveTextFormat.read(SaveTextFormat.write(d, registry), registry, SaveFileKind.NORMAL)
            for (key in listOf("DITEMTYPE", "TA")) {
                val a = d.variables.firstOrNull { it.key == key } ?: continue
                val b = back.variables.firstOrNull { it.key == key }
                assertNotNull(b, "$key 往返后应保留")
                assertEquals(shape(a), shape(b), "$key 往返值应一致")
            }
        }
    }

    /**
     * `ReadInt64Array` 只解析 `i < array.Length`（EraDataReader.cs:84-91）：声明长度之外的行
     * 仍消费到 `__FINISHED`，但**不看内容**（非数字也不抛）。旧实现逐行 `toLongOrNull()`，
     * 多出来的非数字行会把合法档读坏。
     */
    @Test
    fun `基础数组超出声明长度的行不解析`() {
        val sizes = VariableSizes()
        sizes.int1D[0] = 2      // DAY
        sizes.charaInt1D[0] = 2 // BASE
        val registry = VariableRegistry(sizes)
        val ff = SaveTextFormat.FINISHED
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')

        line("1"); line("0"); line("extra"); line("1")
        line(""); line("")
        line("0"); line("0")
        // 角色 BASE（声明 2 项）：2 个数字 + 2 行非数字（都在声明长度之外）
        line("11"); line("12"); line("oops-a"); line("oops-b"); line(ff)
        repeat(16) { line(ff) }
        // 全局 DAY（声明 2 项）：同样
        line("21"); line("22"); line("oops-c"); line("oops-d"); line(ff)
        repeat(59) { line(ff) }
        line(ff) // SAVESTR

        val d = SaveTextFormat.read(sb.toString().toByteArray(StandardCharsets.UTF_8), registry, SaveFileKind.NORMAL)
        val base = d.characters.single().single { it.key == "BASE" } as SaveEntry.IntArray1
        assertEquals(listOf(11L, 12L), base.values.toList(), "BASE 只取声明长度")
        val day = d.variables.single { it.key == "DAY" } as SaveEntry.IntArray1
        assertEquals(listOf(21L, 22L), day.values.toList(), "DAY 只取声明长度")
    }

    /**
     * 按原版文本布局手拼一份**普通档**正文（0 个扩展变量 ⇒ 每段都只剩一行
     * `__EMU_SEPARATOR__`）：4 行头 + 每角色 21 行正文（2 String + 2 Int + 17 Int1D，
     * `CharacterData.cs:353-369`）+ 61 行（60 Int1D + 1 Str1D，`VariableData.cs:3516-3534`）
     * + 标记行（[marker] 为空则不写）+ 每角色 [charaSegs] 段 + [sysSegs] 段系统扩展
     * + [userSegs] 段用户扩展。
     *
     * [sysSegLines] 给第 i 段系统扩展补内容行（写在它自己的 `__EMU_SEPARATOR__` 之前），
     * 空表时产物与 1808 写侧同形（自证见 `文本扩展区按标记档位分流旧段数` 的首个断言）。
     */
    private fun oldNormalText(
        marker: String,
        charaCount: Int,
        charaSegs: Int,
        userSegs: Int,
        sysSegs: Int = 8,
        sysSegLines: List<List<String>> = emptyList(),
    ): String {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("1")
        line("0")
        line("old")
        line(charaCount.toString())
        repeat(charaCount) {
            line("")
            line("")
            line("0")
            line("0")
            repeat(17) { line(SaveTextFormat.FINISHED) }
        }
        repeat(61) { line(SaveTextFormat.FINISHED) }
        if (marker.isNotEmpty()) line(marker)
        repeat(charaCount * charaSegs) { line(SaveTextFormat.SEPARATOR) }
        repeat(sysSegs) { i ->
            sysSegLines.getOrNull(i)?.forEach { line(it) }
            line(SaveTextFormat.SEPARATOR)
        }
        repeat(userSegs) { line(SaveTextFormat.SEPARATOR) }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 8. CHKDATA 六条分支
    // ------------------------------------------------------------------

    /**
     * `CheckDataByFilename`（`VariableEvaluator.cs:2159-2246`）的命中序与状态码，逐条用字节钉住：
     * 不存在 → 1 + `"----"`（`:2162-2166`）；类别不符 → 4（`:2196-2201`）；游戏码不符 → 2
     * （`:2202-2206`，`GameBase.cs:60-67`，存档码 0 豁免）；版本不符 → 3（`:2209-2214`，
     * `GameBase.cs:69-80`）；全过 → 0 + 描述（`:2216-2217`）；头不可解析 → 4（`:2220-2229` catch）。
     *
     * 本用例额外钉**优先级**：类别先于游戏码、游戏码先于版本 —— 三项全错时必须报类别，
     * 两项全错时必须报游戏码。
     */
    @Test
    fun `CHKDATA 六条分支与命中优先级`(@TempDir root: Path) {
        // 分支 1：文件不存在（唯二的磁盘分支之一，VariableEvaluator.cs:2162-2166）
        val host = RootedBuiltinHost(root)
        val miss = SaveDataProbe.check(host, SaveFileKind.NORMAL, 5L, GameBaseData(), false)
        assertNotNull(miss)
        assertEquals(EraDataState.FILENOTFOUND, miss.state)
        assertEquals("----", miss.mes)

        val gb = GameBaseData(gameCode = 100L, version = 5L, allowVersion = 10L)

        // 分支 6：头不可解析（垃圾字节）⇒ ETC_ERROR + 损坏文案（:2225-2229 的 catch）
        val junk = SaveDataProbe.checkBytes(ByteArray(32) { 0xAB.toByte() }, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.ETC_ERROR, junk.state)
        assertEquals(SaveDataProbe.MES_CORRUPTED, junk.mes)

        // 优先级 1：类别不符 + 游戏码不符 + 版本不符 ⇒ 只报类别（:2196-2201 在 :2203 之前）
        val allWrong = SaveFormat.write(global(code = 999L, version = 9L, compressed = true))
        val kindFirst = SaveDataProbe.checkBytes(allWrong, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.ETC_ERROR, kindFirst.state, "类别比对先于游戏码（:2196-2201）")
        assertEquals(SaveDataProbe.MES_CORRUPTED, kindFirst.mes)

        // 优先级 2：游戏码不符 + 版本不符 ⇒ 只报游戏码（:2202-2206 在 :2209 之前）
        val codeWrong = SaveFormat.write(normal(code = 999L, version = 9L, text = "x", compressed = true))
        val gameFirst = SaveDataProbe.checkBytes(codeWrong, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.GAME_ERROR, gameFirst.state, "游戏码比对先于版本（:2202-2206）")
        assertEquals(SaveDataProbe.MES_DIFFERENT_GAME, gameFirst.mes)

        // 分支 4：版本不符（存档 3 < 兼容下限 10，且 != 5）
        val verWrong = SaveFormat.write(normal(code = 100L, version = 3L, text = "x", compressed = true))
        val versionErr = SaveDataProbe.checkBytes(verWrong, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.VIRSION_ERROR, versionErr.state)
        assertEquals(SaveDataProbe.MES_DIFFERENT_VERSION, versionErr.mes)

        // 分支 5：全过 ⇒ OK + 描述（:2216-2217）
        val good = SaveFormat.write(normal(code = 100L, version = 5L, text = "第五槽", compressed = true))
        val ok = SaveDataProbe.checkBytes(good, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.OK, ok.state)
        assertEquals("第五槽", ok.mes)

        // 豁免：存档码 0 不看游戏码（GameBase.cs:60-67）
        val zeroCode = SaveFormat.write(normal(code = 0L, version = 5L, text = "零码", compressed = true))
        assertEquals(EraDataState.OK, SaveDataProbe.checkBytes(zeroCode, SaveFileKind.NORMAL, gb).state)
    }

    /**
     * 短文本档：原版 `EraBinaryDataReader.CreateReader` 失败后无条件回落文本解析
     * （VariableEvaluator.cs:2176-2178），没有「文件太短就不探测」这一层。
     * 5 字节的 `"1\n2\nx"` 是合法文本档（三行：码/版本/描述）。
     */
    @Test
    fun `短于十六字节的文本头仍可探测`() {
        val bytes = "1\n2\nx".toByteArray(StandardCharsets.UTF_8)
        assertTrue(bytes.size < 16)
        val head = SaveFormat.peek(bytes)
        assertNotNull(head, "短文本档不应因长度被判 null（:2176-2178）")
        assertEquals(false, head.binary)
        assertEquals(0, head.version)
        assertEquals(1L, head.uniqueCode)
        assertEquals(2L, head.scriptVersion)
        assertEquals("x", head.text)
    }

    /**
     * 头完好、GZip 正文坏掉的容器：原版 `CreateReader` 把解压与校验表读取放在**同一个 try**
     * 里（`EraBinaryDataReader.cs:810` 解压、`:826-828` 的 `catch ⇒ return null`），所以它给调用方
     * 的是 null 而不是异常 ⇒ `LOADCHARA` 静默 `RESULT = 0`（`VariableEvaluator.cs:2299-2303`）。
     * 移植侧曾只看头 16 字节判容器，坏档于是冒成 [com.eratw.port.core.err.CodeEE] 打死整局
     * （总账 SAVE-13，2026-10-23 用真档合成复现并收口）。
     */
    @Test
    fun `头完好但GZip正文坏的容器判为非容器`() {
        val good = SaveFormat.write(normal(code = 7153L, text = "第 1日", compressed = true))
        assertTrue(good.size > 26, "压缩档至少要越过容器头 + gzip 头")
        assertTrue(SaveFormat.isBinaryContainer(good), "好档仍是二进制容器")

        // 留 16 字节容器头 + 10 字节 gzip 头，正文整体换成 0xFF：deflate 首字节 BTYPE=11 必抛
        val broken = good.copyOfRange(0, 26) + ByteArray(good.size - 26) { 0xFF.toByte() }
        assertFalse(SaveFormat.isBinaryContainer(broken), "解压失败 = CreateReader == null（:826-828）")
        assertNull(SaveFormat.peek(broken), "头探测同口径给 null（CHKDATA 落 ETC_ERROR）")
        assertFailsWith<SaveFormatException> { SaveFormat.read(broken) }
        assertFalse(
            CharaFileIO.load(broken, VariableStore()),
            "LOADCHARA 走「不是容器」分支 ⇒ 原版 RESULT 保持 0（:2299-2303），不得抛 CodeEE",
        )
    }

    /**
     * 文本分支（`EraDataReader`，`VariableEvaluator.cs:2176-2194`）：**没有类别字节**，故不比类别；
     * 但游戏码先查（`:2179-2184`）、版本后查（`:2185-2191`）。`SaveFormat.peek` 对文本头给
     * `binary = false` + `fileType = NORMAL` 的占位值，`SaveDataProbe` 据此跳过类别比对。
     */
    @Test
    fun `文本分支不比类别但先查游戏码`() {
        val gb = GameBaseData(gameCode = 100L, version = 5L, allowVersion = 10L)
        fun text(code: Long, version: Long): ByteArray = "$code\n$version\n文本描述\n".toByteArray(StandardCharsets.UTF_8)

        val head = SaveFormat.peek(text(100L, 5L))
        assertNotNull(head)
        assertEquals(false, head.binary, "文本头 binary=false")
        assertEquals(0, head.version, "文本头没有容器版本（取 0 占位）")

        // 同一份文本按 GLOBAL 查：原版文本分支不读 FileType ⇒ 仍按码/版本判，最终 OK
        val okGlobal = SaveDataProbe.checkBytes(text(100L, 5L), SaveFileKind.GLOBAL, gb)
        assertEquals(EraDataState.OK, okGlobal.state, "文本分支跳过类别比对（:2176-2194）")
        assertEquals("文本描述", okGlobal.mes)

        val gameErr = SaveDataProbe.checkBytes(text(999L, 3L), SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.GAME_ERROR, gameErr.state, "游戏码先于版本（:2179-2184）")

        val verErr = SaveDataProbe.checkBytes(text(100L, 3L), SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.VIRSION_ERROR, verErr.state)
        assertEquals(SaveDataProbe.MES_DIFFERENT_VERSION, verErr.mes)

        // 短到连三行都没有 ⇒ 头不可解析（:2185 读第二行会抛，落 :2225 的 catch）
        val tooShort = SaveDataProbe.checkBytes("100\n".toByteArray(), SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.ETC_ERROR, tooShort.state)
    }

    // ------------------------------------------------------------------
    // 9. 版本判定三条件
    // ------------------------------------------------------------------

    /**
     * `GameBase.CheckVersion`（`GameBase.cs:69-80`）三条放行：
     * 1. 脚本版本**未定义**且存档版本 != 1000；
     * 2. `ScriptCompatibleMinVersion <= 存档版本`（该字段缺省 **-1**，`GameBase.cs:27`）；
     * 3. `ScriptVersion == 存档版本`。
     *
     * 第 1 条读的是布尔位 `ScriptVersionDefined`（`GameBase.cs:25`），由 GameBase.csv 的
     * `バージョン` 行「写了且能解析出数字」置位（`GameBase.cs:138-140`）——**与解析出来的
     * 数值是不是 0 无关**。M10-SAVE 收口（原为「移植侧把未定义近似成 version==0」的钉桩）：
     * [SaveDataProbe.checkBytes] / [SaveDataProbe.check] / [SaveWiring.scriptVersionDefined]
     * 已开出一条传位通道，真值路径按原版判；近似路径只在调用方没交出这一位时生效，
     * 本用例把两条路径的**差异点**（`VERSION` 写了 0 + 兼容下限 > 0 + 存档版本更低）钉成断言。
     */
    @Test
    fun `版本判定三条件与 ScriptVersionDefined 一位之差`() {
        // 未定义（GameBase.csv 没写 バージョン，或值不是数字）+ 存档 1000：
        // 原版第 1 条不成立 ⇒ 走第 2 条（-1 <= 1000）通过（GameBase.cs:71-78）
        val undefined = GameBaseData(gameCode = 1L, version = 0L, allowVersion = 0L)
        val v1000 = SaveFormat.write(normal(code = 1L, version = 1000L, compressed = true))
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(v1000, SaveFileKind.NORMAL, undefined, scriptVersionDefined = false).state,
            "未定义 + 存档 1000 ⇒ 第 2 条放行（:75-78）",
        )

        // 已定义（version=5）+ 兼容下限 10：存档 7 三条全不成立 ⇒ 版本错（:2209-2214）
        val defined = GameBaseData(gameCode = 1L, version = 5L, allowVersion = 10L)
        val v7 = SaveFormat.write(normal(code = 1L, version = 7L, compressed = true))
        assertEquals(
            EraDataState.VIRSION_ERROR,
            SaveDataProbe.checkBytes(v7, SaveFileKind.NORMAL, defined, scriptVersionDefined = true).state,
            "三条全不成立 ⇒ VIRSION_ERROR（:69-80）",
        )
        // 已定义 + 存档版本 == ScriptVersion ⇒ 第 3 条放行
        val v5 = SaveFormat.write(normal(code = 1L, version = 5L, compressed = true))
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(v5, SaveFileKind.NORMAL, defined, scriptVersionDefined = true).state,
            "第 3 条 ScriptVersion == target 放行（:79）",
        )
        // 已定义 + 存档版本 >= 兼容下限 ⇒ 第 2 条放行
        val v12 = SaveFormat.write(normal(code = 1L, version = 12L, compressed = true))
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(v12, SaveFileKind.NORMAL, defined, scriptVersionDefined = true).state,
            "第 2 条 CompatibleMinVersion <= target 放行（:75-78）",
        )

        // 差异点：`バージョン,0` ⇒ ScriptVersionDefined=true 且 ScriptVersion=0（GameBase.cs:138-140）。
        // 原版：第 1 条不成立（已定义）；5 <= 3 不成立；0 == 3 不成立 ⇒ VIRSION_ERROR。
        val zeroDefined = GameBaseData(gameCode = 1L, version = 0L, allowVersion = 5L)
        val v3 = SaveFormat.write(normal(code = 1L, version = 3L, compressed = true))
        assertEquals(
            EraDataState.VIRSION_ERROR,
            SaveDataProbe.checkBytes(v3, SaveFileKind.NORMAL, zeroDefined, scriptVersionDefined = true).state,
            "真值路径按原版判失败（GameBase.cs:25/:139）",
        )
        // 同一份存档 + 未定义 ⇒ 第 1 条放行（存档版本 3 != 1000），两侧在此分叉
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(v3, SaveFileKind.NORMAL, zeroDefined, scriptVersionDefined = false).state,
            "未定义时第 1 条放行（GameBase.cs:71-74）",
        )
        // 未传位（近似口径 `version != 0`）⇒ version=0 被当作未定义 ⇒ 判 OK，
        // 这正是「近似偏松」的唯一可见场合；调用方补上真值即与原版一致。
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(v3, SaveFileKind.NORMAL, zeroDefined).state,
            "缺省走 version==0 近似（需组长把该位接进 GameBaseData）",
        )
        // 判定本体的三条放行逐条对上（GameBaseGate，读档入口与探测入口共用同一份）
        assertEquals(
            false,
            SaveDataProbe.checkVersion(zeroDefined, 3L, scriptVersionDefined = true),
            "GameBaseGate 真值路径",
        )
        assertEquals(true, SaveDataProbe.checkVersion(zeroDefined, 3L, scriptVersionDefined = false))
        assertEquals(true, SaveDataProbe.checkVersion(zeroDefined, 3L), "近似路径与旧口径一致")
    }

    // ------------------------------------------------------------------
    // 10. UseSaveFolder 单候选
    // ------------------------------------------------------------------

    /**
     * `SavDir` 在原版只有一个赋值、两条互斥分支（`Config.cs:471-477` 走 `ExeDir + "sav" + sep`、
     * `:478-481` 走 `ExeDir`），`CheckData` 按类别只组**一条** filename
     * （`VariableEvaluator.cs:2138-2156`）⇒ 探测候选恒为一条，且必须等于落盘路径
     * （`SaveTo` :2646 / `LoadFrom` :2689 / `DelData` :2712 用的是同一个 `getSaveDataPath`）。
     * 移植侧落盘路径是 `SaveConfig.saveFolderPrefix` 的单前缀。
     */
    @Test
    fun `UseSaveFolder 单候选与落盘路径同源`() {
        val host = object : BuiltinHost {}

        val on = SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = true)
        val off = SaveConfig(systemSaveInBinary = true, zipSaveData = true, useSaveFolder = false)

        assertEquals(listOf("sav/save03.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 3L, true))
        assertEquals(listOf("save03.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, 3L, false))
        assertEquals(listOf("sav/global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, true))
        assertEquals(listOf("global.sav"), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 42L, false))

        for (flag in listOf(true, false)) {
            val cfg = if (flag) on else off
            for (no in listOf(0L, 3L, 99L)) {
                assertEquals(
                    listOf(cfg.normalPath(no.toInt())),
                    SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, no, flag),
                    "探测候选必须等于落盘路径（flag=$flag, no=$no）",
                )
            }
            assertEquals(listOf(cfg.globalPath()), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 7L, flag))
            assertTrue(!cfg.globalPath().contains("sav/sav/"), "单前缀不得叠加")
        }

        // 变量档/角色档拼 Program.DatDir（VariableEvaluator.cs:2039-2057），与开关无关
        for (flag in listOf(true, false, null)) {
            assertEquals(listOf("dat/var_02.dat"), SaveDataProbe.candidatePaths(host, SaveFileKind.VAR, 2L, flag))
            assertEquals(listOf("dat/chara_02.dat"), SaveDataProbe.candidatePaths(host, SaveFileKind.CHARVAR, 2L, flag))
        }

        // eratw 实机三项（emuera.config 的 USE SAV FOLDER / BINARY / COMPRESS 都是 YES）
        assertTrue(SaveConfig.ERATW.systemSaveInBinary, "USE THE BINARY FORMAT FOR SAVING DATA:YES")
        assertTrue(SaveConfig.ERATW.zipSaveData, "COMPRESS SAVE DATA:YES")
        assertTrue(SaveConfig.ERATW.useSaveFolder, "USE SAV FOLDER:YES")
        assertEquals("sav/save00.sav", SaveConfig.ERATW.normalPath(0))
        assertEquals("sav/global.sav", SaveConfig.ERATW.globalPath())
    }

    // ------------------------------------------------------------------
    // 11. 真语料：只读解析
    // ------------------------------------------------------------------

    /** 游戏根：`ERATW_GAMEDIR` 给出即权威；回落取 `ERATW_CORPUS`（指到 ERB 子树）的父目录。 */
    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    /**
     * 语料 `sav/` 下的真实存档**只读**解析（一个字节都不写）：容器头三字段、头探测、整帧读取、
     * 以及 `write(read(f)) == f` 的逐字节幂等（等价于「移植侧编码器复现了 .NET 的正文」：
     * `SaveFormat.write` 只在重编码与读入正文逐字节相同时才回原字节）。
     *
     * 读数取样本自身实测字面量：UniqueCode 7153、ScriptVersion 0、角色数 168
     * （与 `SaveFormatTest` 同一批 PC 样本的实测表）。
     */
    @Test
    fun `语料真实存档只读解析与逐字节幂等`() {
        val sav = gameDir()?.resolve("sav")
        Assumptions.assumeTrue(sav != null && Files.isDirectory(sav), "缺 ERATW_GAMEDIR/ERATW_CORPUS，跳过语料判据")
        val files = (sav!!.toFile().listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".sav") }
            .sortedBy { it.name }
        Assumptions.assumeTrue(files.isNotEmpty(), "语料 sav/ 下没有 .sav")

        for (f in files) {
            val bytes = f.readBytes()
            val name = f.name
            assertTrue(bytes.size > 16, "$name 太短")
            assertEquals(SaveFormat.MAGIC_GZIP, u64(bytes, 0), "$name 魔数（实测 ERAZIP）")
            assertEquals(1808L, u32(bytes, 8), "$name 版本（EraBinaryDataWriter.cs:45）")
            assertEquals(0L, u32(bytes, 12), "$name 校验表条数恒 0（:46）")
            assertSlice(gzipHead, bytes, 16, "$name .NET GZipStream 头")

            val head = SaveFormat.peek(bytes)
            assertNotNull(head, "$name 头探测")
            assertEquals(true, head.binary)
            assertEquals(true, head.gzip)
            assertEquals(1808, head.version)
            assertEquals(7153L, head.uniqueCode, "$name UniqueCode 实测")
            assertEquals(0L, head.scriptVersion, "$name ScriptVersion 实测")

            val d = SaveFormat.read(bytes)
            // 类别按文件名分叉：global.sav 的正文是 GLOBAL 容器（分类器判 GLOBAL 是对的），
            // 原断言对所有文件硬编码 NORMAL —— 语料 sav/ 里出现 global.sav 后在本机（有语料的环境）红。
            // NORMAL 专属判据（角色数/描述文本）只对存档文件要求；容器级判据与逐字节幂等对两类都要求。
            val isGlobal = name == SaveConfig.ERATW.globalPath().substringAfterLast('/')
            assertEquals(if (isGlobal) SaveFileKind.GLOBAL else SaveFileKind.NORMAL, d.fileType, "$name 类别")
            if (!isGlobal) {
                assertEquals(168, d.characters.size, "$name 角色数实测")
                assertTrue(d.text.isNotEmpty(), "$name 描述文本")
            }
            assertEquals(7153L, d.uniqueCode)
            assertTrue(SaveFormat.write(d).contentEquals(bytes), "$name 往返必须逐字节相同")
        }
    }

    // ------------------------------------------------------------------
    // 12. 真存档读数（M10-SAVE 的验收判据）
    // ------------------------------------------------------------------

    /**
     * **PC 存档读得进**：语料根 `sav/save00.sav`（只读，一个字节都不写）解出的角色与全局字段，
     * 逐条对**独立测量表**。
     *
     * 期望值不是移植侧解码器自己算出来的：本次另写了一套按 `EraBinaryDataReader1808`
     * 语义（EraBinaryDataReader.cs:33-47 的 `m_ReadInt` 四档、`:102-167` 的 240 零游程、
     * `:186-267` 的 241/224/240 二维标签、`:461-511` 的 216/240 字符串数组）
     * 手工实现的独立解析器量得，与本仓 `SaveFormat` 无共享代码；两侧对同一份字节
     * 消费到 payload 末尾（571185 字节，正好读空），读数一致才算这条判据成立。
     *
     * 覆盖：头四字段 + 描述原文（94 个 UTF-16 码元）+ 168 个角色的 NO/NAME +
     * 两个带 Separator 的角色块 + 540 条全局条目的抽样值 + EM 段两个 DataTable +
     * 探测（`CHKDATA` 口径）与 `applyTo` 进变量池后的回读。
     */
    @Test
    fun `真存档解出的角色与全局数值与 PC 侧一致`() {
        val bytes = saveBytes("save00.sav")
        Assumptions.assumeTrue(bytes != null, "既无 ERATW_GAMEDIR/sav 也无仓内 sav-backup 样本，跳过真档判据")
        val d = SaveFormat.read(bytes!!)

        // ---- 头（VariableEvaluator.cs:2594-2598）----
        assertEquals(SaveFileKind.NORMAL, d.fileType, "FileType=Normal=0（EraSaveFileType.cs:3-9）")
        assertEquals(7153L, d.uniqueCode, "UniqueCode（GameBase.csv コード）")
        assertEquals(0L, d.scriptVersion, "ScriptVersion=0：GameBase.csv 的 バージョン 值是 proto ⇒ 解析失败落 0")
        assertEquals(1808, d.version, "容器版本（实测头第 8 字节起 = 1808）")
        assertTrue(d.compressed, "eratw 实机 ZIP SAVE DATA:YES ⇒ GZip 分支魔数")
        assertEquals(
            "2026/05/18 14:22:54 \u7b2c  1\u65e5(\u6625\u4e4b\u67081\u65e5) " +
                "\u547d\u83b2\u5bfa           \u6634             Ver 4.980 - " +
                "\u65e5\u8bed\u4ea4\u6d41\u7528\u753b\u86c7\u6dfb\u8db3\u7248CH20260326 ",
            d.text,
            "描述原文（PC 侧 PRINT 出来的存档行；94 个 UTF-16 码元，尾随一个空格）",
        )
        assertEquals(94, d.text.length, "描述串长度 = 前缀 BC 01 解出的 188 字节 / 2")

        // ---- 角色块 ----
        assertEquals(168, d.characters.size, "角色数（spec/save-format.md §6.2 实测表）")
        for ((i, block) in d.characters.withIndex()) {
            assertEquals(i.toLong(), intOf(block, "NO"), "角色 $i 的 NO 必须等于块序（实测 168 块 NO=0..167）")
            assertTrue(strOf(block, "NAME").isNotEmpty(), "角色 $i 的 NAME 非空")
        }
        assertEquals("\u6634", strOf(d.characters[0], "NAME"), "角色 0 = 昴")
        assertEquals("\u535a\u4e3d \u7075\u68a6", strOf(d.characters[1], "NAME"), "角色 1 = 博丽 灵梦")
        assertEquals("\u7559\u7434", strOf(d.characters[2], "NAME"), "角色 2 = 留琴")
        assertEquals("\u96fe\u96e8 \u9b54\u7406\u6c99", strOf(d.characters[11], "NAME"), "角色 11 = 雾雨 魔理沙")
        assertEquals("\u516b\u4e91 \u7d2b", strOf(d.characters[26], "NAME"), "角色 26 = 八云 紫")
        assertEquals("\u85e4\u539f \u59b9\u7ea2", strOf(d.characters[61], "NAME"), "角色 61 = 藤原 妹红")
        assertEquals("\u8def\u4eba\u5b50", strOf(d.characters[167], "NAME"), "角色 167 = 路人子")
        // 166 块 93 条目；26 / 61 两块 94 条目，多的那条是索引 25 上的 MASTERNAME（见下方实测）。
        // 每块都在索引 27 有一条 Separator：系统条目写完 → `WriteSeparator` → 用户 CHARADATA
        // 条目（CharacterData.cs:546-556），分隔符在角色块里合法（:672）。
        assertEquals(93, d.characters[0].size, "角色 0 的条目数（系统条目 + 用户条目）")
        assertEquals(94, d.characters[26].size, "角色 26 多一条 MASTERNAME")
        assertEquals(94, d.characters[61].size, "角色 61 多一条 MASTERNAME")
        assertEquals(
            (0 until 168).toList(),
            d.characters.indices.filter { d.characters[it].count { e -> e is SaveEntry.Separator } == 1 },
            "168 块各有且仅有一条 Separator（CharacterData.cs:546-548）",
        )
        assertEquals(
            27,
            d.characters[0].indexOfFirst { it is SaveEntry.Separator },
            "分隔符固定落在系统条目之后（实测 26 条系统条目 + SEP）",
        )
        assertEquals(
            listOf(26, 61),
            d.characters.indices.filter { d.characters[it].size != 93 },
            "只有这两块多出条目（八云 紫 / 藤原 妹红，都有主人）",
        )
        assertEquals(
            "\u6634", strOf(d.characters[26], "MASTERNAME"),
            "实测：PC 只在**有主人**的角色块里写 MASTERNAME（值 = 昴），全档 168 块中 2 块有；" +
                "NICKNAME 一条都没有。归因（2026-10-23 更正）：两列的 isSavedata 两侧都判真，" +
                "排除点在 writer —— `EraBinaryDataWriter.WriteWithKey(string, object)` 按 `v is T` 分派且" +
                "末尾无 else（EraBinaryDataWriter.cs:83-173），null 格整条不落 ⇒ 移植侧写侧已按同口径收窄" +
                "（总账 SAVE-04，`CharaFileIO.isNullInOriginal`；详见 `SaveRoundTripTest.额外角色字段`）",
        )
        assertFalse(d.characters[0].any { it.key == "MASTERNAME" }, "无主人的角色块不写 MASTERNAME")
        assertFalse(d.variables.any { it.key == "NICKNAME" }, "全档没有 NICKNAME 条目")
        assertEquals(0L, intOf(d.characters[0], "ISASSI"), "角色 0 的 ISASSI")

        // ---- 全局块 ----
        assertEquals(540, d.variables.size, "全局条目数（spec §6.2 实测）")
        assertEquals(1L, int1Of(d, "DAY", 0), "DAY[0]")
        assertEquals(1L, int1Of(d, "DAY", 2), "DAY[2]")
        assertEquals(1L, int1Of(d, "DAY", 3), "DAY[3]")
        assertEquals(3, int1Of(d, "DAY").filter { it != 0L }.size, "DAY 只有 3 个非零（共 100 槽）")
        assertEquals(10L, int1Of(d, "ITEM", 81), "ITEM[81]=10，且是 ITEM(1000 槽) 唯一的非零项")
        assertEquals(1, int1Of(d, "ITEM").count { it != 0L })
        assertEquals(0, int1Of(d, "MONEY").count { it != 0L }, "MONEY 全零（100 槽）")
        assertEquals(167L, int1Of(d, "COUNT", 0), "COUNT[0]=167")
        assertEquals(1L, int1Of(d, "TARGET", 0), "TARGET[0]=1")
        assertEquals(-1L, int1Of(d, "ASSI", 0), "ASSI[0]=-1（负值走 208 档变长整数）")
        assertEquals(1L, int1Of(d, "MAIN_MAP", 0), "MAIN_MAP[0]=1")
        assertEquals(1L, int1Of(d, "NOW_TERM", 0), "NOW_TERM[0]=1")
        assertEquals(420L, int1Of(d, "TIME", 3), "TIME[3]=420")
        assertEquals(20260326L, int1Of(d, "modding_version", 0), "modding_version[0]（用户全局变量，走名字查表）")
        assertEquals(100L, int1Of(d, "PALAMLV", 1), "PALAMLV[1]=100")
        assertEquals(20L, int1Of(d, "EXPLV", 3), "EXPLV[3]=20")
        assertEquals(100L, int1Of(d, "FLAG", 832), "FLAG[832]=100")
        assertEquals(26, int1Of(d, "FLAG").count { it != 0L }, "FLAG(10000 槽) 共 26 个非零")
        assertEquals(0, int1Of(d, "RANDDATA").count { it != 0L }, "RANDDATA 625 槽全零（spec §6.4：它确实在 saveXX.sav 里）")
        assertEquals(625, int1Of(d, "RANDDATA").size)
        assertEquals("4.980", str1Of(d, "SAVESTR", 8), "SAVESTR[8]")
        assertEquals("\u547d\u83b2\u5bfa", str1Of(d, "MAPNAME", 1), "MAPNAME[1]=命莲寺")
        assertEquals("\u795e\u793e\u5468\u8fb9", str1Of(d, "MAPNAME", 6), "MAPNAME[6]=神社周边")

        // ---- EM 段（两个 DataTable，VariableData.cs:4115-4124 的 DT 支）----
        assertEquals(2, d.emData.size, "EM 段条目数")
        assertEquals(
            listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"),
            d.emData.map { it.key },
            "EM 段键序（实测 payload 尾段）",
        )
        assertTrue(d.emData.all { it is SaveEntry.DataTableValue }, "两条都是 DataTable")
        assertTrue((d.emData[0] as SaveEntry.DataTableValue).schemaXml.isNotEmpty(), "WriteXmlSchema 产物非空")

        // ---- 探测（CHKDATA 口径，GameBase.csv 实测 コード=7153 / バージョン=proto / 違い認める=0012）----
        val gb = GameBaseData(gameCode = 7153L, version = 0L, allowVersion = 12L)
        for (defined in listOf(null, true, false)) {
            val probe = SaveDataProbe.checkBytes(bytes, SaveFileKind.NORMAL, gb, scriptVersionDefined = defined)
            assertEquals(EraDataState.OK, probe.state, "真档必须判 OK（defined=$defined）：${probe.mes}")
            assertEquals(d.text, probe.mes, "探测描述 = 存档描述（:2216-2217）")
        }

        // ---- 装载（applyTo）：168 个角色与关键数值进变量池 ----
        val store = VariableStore()
        val report = d.applyTo(store, restoreCharacters = true, setLastLoad = true)
        assertEquals(168, store.characters.count, "LOADDATA 后角色数")
        assertEquals(168, report.characters)
        assertEquals(0L, store.lastLoadVersion, "LASTLOAD_VERSION（VariableEvaluator.cs:2627）")
        assertEquals(d.text, store.lastLoadText, "LASTLOAD_TEXT（:2628）")
        assertEquals("\u6634", store.readStr("NAME", longArrayOf(0L)), "角色 0 的名字写进了池子")
        assertEquals("\u516b\u4e91 \u7d2b", store.readStr("NAME", longArrayOf(26L)), "角色 26 的名字")
        assertEquals(167L, store.readInt("COUNT", longArrayOf(0L)), "COUNT[0] 写进了池子")
        assertEquals(1L, store.readInt("DAY", longArrayOf(0L)), "DAY[0] 写进了池子")
        assertEquals(10L, store.readInt("ITEM", longArrayOf(81L)), "ITEM[81] 写进了池子")
    }

    /** 语料 `sav/` 优先，回落仓内只读样本目录（`.workbuddy/sav-backup/sav-20261018-110513`）。 */
    private fun saveBytes(name: String): ByteArray? {
        gameDir()?.resolve("sav")?.resolve(name)?.let { if (Files.isRegularFile(it)) return Files.readAllBytes(it) }
        for (dir in SAMPLE_DIRS) {
            val p = Path.of(dir).resolve(name)
            if (Files.isRegularFile(p)) return Files.readAllBytes(p)
        }
        return null
    }

    private fun intOf(block: List<SaveEntry>, key: String): Long =
        (block.firstOrNull { it.key == key } as? SaveEntry.IntValue)?.value
            ?: throw AssertionError("角色块里没有 $key")

    private fun strOf(block: List<SaveEntry>, key: String): String =
        (block.firstOrNull { it.key == key } as? SaveEntry.StrValue)?.value
            ?: throw AssertionError("角色块里没有 $key")

    private fun int1Of(d: SaveData, key: String, i: Int): Long = int1Of(d, key)[i]

    private fun int1Of(d: SaveData, key: String): LongArray =
        (d.variables.firstOrNull { it.key == key } as? SaveEntry.IntArray1)?.values
            ?: throw AssertionError("全局块里没有 $key")

    private fun str1Of(d: SaveData, key: String, i: Int): String? =
        (d.variables.firstOrNull { it.key == key } as? SaveEntry.StrArray1)?.values?.get(i)
            ?: throw AssertionError("全局块里没有 $key")

    private companion object {
        /** 与 [SaveRoundTripTest] 同一批样本（md5 与语料根 `sav/` 逐字节相同）。 */
        val SAMPLE_DIRS = listOf(
            "../../.workbuddy/sav-backup/sav-20261018-110513",
            "../.workbuddy/sav-backup/sav-20261018-110513",
            ".workbuddy/sav-backup/sav-20261018-110513",
        )
    }
}
