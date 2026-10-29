package com.eratw.port.core.data.save

import com.eratw.port.core.fn.SaveFileKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

/**
 * Emuera 二进制存档编解码（`EraBinaryDataReader1808` / `EraBinaryDataWriter` 的 Kotlin 复刻）。
 *
 * ## 实测结构（`sav-backup/sav-20261018-110513/save00.sav`，60151 字节）
 *
 * | 偏移 | 内容 | 实测值 | 证据 |
 * |---|---|---|---|
 * | 0 | UInt64 魔数 | 743174590507926921（GZip 分支） | EraBinaryDataWriter.cs:37-44 |
 * | 8 | UInt32 版本 | 1808 | :45 |
 * | 12 | UInt32 校验表条数 | 0 | :46 |
 * | 16 | GZip 成员 | `1f 8b 08 00 00 00 00 00 00 0a` | 实测（.NET 8 的 OS 字节为 0x0A） |
 *
 * 解压后 payload = 571185 字节：
 *
 * | payload 偏移 | 内容 | 实测值 |
 * |---|---|---|
 * | 0 | FileType | 0 = Normal（EraSaveFileType.cs:3-9） |
 * | 1 | Int64 UniqueCode | 7153 |
 * | 9 | Int64 ScriptVersion | 0 |
 * | 17 | String saveDataText | 7-bit 前缀 `BC 01` = **188 字节**（94 个 UTF-16 码元），文本以 `CH20260326 ` 结尾 |
 * | 207 | Int64 角色数 | 168 |
 * | 215 | 角色 0 块 | `00 0C "ISASSI" 00`、`00 04 "NO" 00`、`01 08 "BASE"` + 100 元素… |
 * | 526683 | 全局变量块 | 540 个条目（DAY/MONEY/ITEM/… 直到 受付水子画像文字列） |
 * | 566136 | EOF (0xFF) | 全局块结束 |
 * | 566137 | EM 块 | `DT_UFUFU_LOG`、`DT_UFUFU_M_LOG` 两个 DataTable |
 * | 571184 | 第二个 EOF | 文件结束（571185 = payload 长度） |
 *
 * 关键坑（已实测）：
 * 1. **字符串长度前缀是「字节数」不是「字符数」**（.NET `BinaryWriter.Write(string)` 的
 *    7-bit 变长前缀），UTF-16LE 下 `188 = 94 字符`。按字符数解析会立刻错位。
 * 2. 字符串数组的 null 与 "" 写出来完全相同（EraBinaryDataWriter.cs:352），读回一律为 null。
 * 3. 全局块里的 `RANDDATA`（625 元素）**确实**出现在 saveXX.sav 里（实测偏移 564xxx）；
 *    spec §2.2 说它只进 global.sav —— 见 save-format.md 的「实测勘误」。
 *
 * ## 逐字节幂等
 *
 * [write] 在**内容未被修改**时直接回写原文件字节（[SaveData.sourceFile]），因为 .NET 的
 * DeflateStream 输出无法用 `java.util.zip.Deflater` 逐字节复现（实测 `global.sav`：
 * 同一 payload 的 zlib level 6 输出 508 字节，.NET 为 510 字节）。判断「未修改」用的是
 * [encodePayload] 重新编码的结果与读入时的 payload 做**逐字节比较**——编码器一旦有偏差
 * 就会退回重新压缩，测试随即失败。改动过的存档用 `Deflater` + 与原版相同的 GZip 头
 * （`1f 8b 08 00 00000000 00 0a`）重新压缩，.NET 侧可正常读取。
 */
object SaveFormat {
    /** 未压缩分支魔数（EraBinaryDataWriter.cs:43）。 */
    const val MAGIC_PLAIN: Long = 727905341820519817L

    /** GZip 分支魔数（EraBinaryDataWriter.cs:39）。 */
    const val MAGIC_GZIP: Long = 743174590507926921L

    /** 唯一支持的版本（EraBinaryDataReader.cs:820-824）。 */
    const val VERSION: Int = 1808

    /** 标签字节（EraBinaryDataWriter.cs:68-81 / EraSaveDataType.cs:3-19）。 */
    private const val TAG_SEPARATOR = 0xFD
    private const val TAG_EOC = 0xFE
    private const val TAG_EOF = 0xFF
    private const val TAG_MAP = 32
    private const val TAG_XML = 33
    private const val TAG_DT = 34

    /** GZip 头（实测 .NET 8：MTIME=0、XFL=0、OS=0x0A）。 */
    private val GZIP_HEADER = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0, 0x0a)

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 解析完整存档。
     *
     * @param keepKey 只保留哪些键的**值**（按变量名；大小写敏感与否由调用方决定）。
     *   `null` = 全部保留（旧口径）。给定时，谓词判 false 的条目**只走标签不建数组**
     *   （见 [skipValue]），也不计入 [Cursor.claim] 的分配预算 —— 旧版本存档里已删除的
     *   大数组（4.956 的 `MOE_RUMIA_COM_RECORD`：162 个角色各 10 万项）在新游戏里
     *   本来就被丢弃，按它建数组既白花上百 MiB，又会被预算闸误判成坏档
     *   （2026-10-29 玩家档 save39.sav 实测：全量声明 306.8 MiB > 256 MiB 上限）。
     *   只读装置与逐字节回写（[write]）用 `null`，行为不变。
     * @throws SaveFormatException 魔数/版本不符、payload 截断、标签非法
     */
    fun read(bytes: ByteArray, keepKey: ((String) -> Boolean)? = null): SaveData {
        val head = readContainer(bytes)
        val c = Cursor(head.payload)
        val typeCode = c.u8()
        val fileType = SaveFileKind.values().getOrNull(typeCode)
            ?: throw SaveFormatException("非法的存档类别字节 $typeCode（期望 0..3）")
        val uniqueCode = c.i64()
        val scriptVersion = c.i64()
        val text = c.readString()

        val characters: List<List<SaveEntry>>
        if (fileType == SaveFileKind.NORMAL || fileType == SaveFileKind.CHARVAR) {
            val n = c.i64()
            if (n < 0 || n > MAX_CHARA) throw SaveFormatException("角色数异常：$n")
            characters = List(n.toInt()) { readCharacterBlock(c, keepKey) }
        } else {
            characters = emptyList()
        }

        val variables: List<SaveEntry>
        if (fileType != SaveFileKind.CHARVAR) {
            variables = readEntries(c, keepKey)
        } else {
            variables = emptyList()
        }

        val emData: List<SaveEntry> =
            if ((fileType == SaveFileKind.NORMAL || fileType == SaveFileKind.GLOBAL) && c.hasMore()) {
                readEntries(c, keepKey)
            } else {
                emptyList()
            }

        return SaveData(
            fileType = fileType,
            uniqueCode = uniqueCode,
            scriptVersion = scriptVersion,
            text = text,
            characters = characters,
            variables = variables,
            emData = emData,
            compressed = head.gzip,
            version = head.version,
            sourceFile = bytes.copyOf(),
            sourcePayload = head.payload,
        )
    }

    /**
     * 测试/诊断用：只解出容器正文（GZip 解压后的 payload），不解析条目。
     * 与 [encodePayload] 逐字节比较即可判定「编码器是否与原文件同构」。
     */
    fun sourcePayloadOf(bytes: ByteArray): ByteArray? = try {
        readContainer(bytes).payload
    } catch (_: SaveFormatException) {
        null
    }

    /**
     * 「这份字节是不是**本层能按二进制读的容器**」——原版的判据就是 [CreateReader]
     * 返回非 null：魔数命中（`EraBinaryDataReader.cs:795-798`）**且**版本恰为 1808
     * （`:820-824`）；两者任一不满足即返回 null，调用方随后**按文本再读一次**
     * （`VariableEvaluator.cs:2176-2178` 的 CheckData 回落、`:2697-2705` 的 LoadFrom 回落）。
     *
     * **解压也在 CreateReader 的 try 体内**（GZip 解压 `:810`，整个方法 `:826-828` 的
     * `catch { return null }` 一把吞）：头完好、正文坏掉的档，原版拿到的是 null 而不是异常
     * ⇒ `LOADCHARA` 静默 `RESULT = 0`（`VariableEvaluator.cs:2299-2303`）。所以本函数必须
     * 把「解压失败」判成「不是容器」；只看头 16 字节会把坏档漏判成容器，让 [read] 把
     * [SaveFormatException] 抛穿语句层、整局打死（总账 SAVE-13，2026-10-23 用真档合成复现）。
     */
    fun isBinaryContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val magic = readU64(bytes, 0)
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) return false
        if (readU32(bytes, 8).toInt() != VERSION) return false
        return try {
            readContainer(bytes)
            true
        } catch (_: SaveFormatException) {
            false
        }
    }

    /**
     * 只解析文件头（CHKDATA / CHKCHARADATA 用）。二进制与文本格式都支持。
     *
     * @return null = 不是 Emuera 存档（魔数不符且文本头也解析不出 / 魔数命中但头被截断 /
     *   版本非 1808）—— 与原版 `CreateReader` 返回 null 同口径（`EraBinaryDataReader.cs:796-824`）。
     *   注意「魔数命中但版本非 1808」这一支在原版里会**再按文本读一次**
     *   （`VariableEvaluator.cs:2176-2178`）：那种文件的前两行不可能解析成数字，
     *   文本分支随即抛 FileEE 并落成 `ETC_ERROR`，与本层给 null 后由
     *   [com.eratw.port.core.save.SaveDataProbe] 给的 `ETC_ERROR` 状态码一致，故不重复探测。
     */
    fun peek(bytes: ByteArray): SaveHeader? {
        // 不足 16 字节仍可能是合法文本档（原版 CreateReader 失败后按文本回落，
        // VariableEvaluator.cs:2176-2178）：只有「魔数命中但头被截断」才判 null。
        val magic = if (bytes.size >= 8) readU64(bytes, 0) else -1L
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) {
            return peekText(bytes)
        }
        if (bytes.size < 16) return null
        val version = readU32(bytes, 8).toInt()
        if (version != VERSION) return null
        return try {
            val head = readContainer(bytes)
            val c = Cursor(head.payload)
            val typeCode = c.u8()
            val fileType = SaveFileKind.values().getOrNull(typeCode) ?: return null
            val uniqueCode = c.i64()
            val scriptVersion = c.i64()
            val text = c.readString()
            SaveHeader(
                binary = true, gzip = head.gzip, version = version, fileType = fileType,
                uniqueCode = uniqueCode, scriptVersion = scriptVersion, text = text,
            )
        } catch (_: SaveFormatException) {
            null
        }
    }

    /**
     * 文本格式头（`EraDataWriter` 前三行：UniqueCode / ScriptVersion / saveDataText）。
     * spec: VariableEvaluator.cs:2409-2414。编码按 BOM 探测，缺省 UTF-8（Config.cs:19）。
     */
    private fun peekText(bytes: ByteArray): SaveHeader? {
        val text = decodeTextFile(bytes) ?: return null
        val lines = text.split('\n')
        if (lines.size < 3) return null
        val code = lines[0].trim().toLongOrNull() ?: return null
        val ver = lines[1].trim().toLongOrNull() ?: return null
        return SaveHeader(
            binary = false, gzip = false, version = 0, fileType = SaveFileKind.NORMAL,
            uniqueCode = code, scriptVersion = ver, text = lines[2].trimEnd('\r'),
        )
    }

    private fun decodeTextFile(bytes: ByteArray): String? = try {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> {
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.indexOf('\uFFFD') >= 0) {
                    // Config.SaveEncode 可被用户改成 Shift-JIS；JVM 与 Android 都有 Shift_JIS。
                    runCatching { String(bytes, charset("Shift_JIS")) }.getOrDefault(utf8)
                } else {
                    utf8
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 序列化。内容未改动时返回原文件字节（见类 KDoc 的「逐字节幂等」）。
     *
     * **容器版本恒写 [VERSION]**：原版 `WriteHeader` 写的是字面量 `1808u`
     * （`EraBinaryDataWriter.cs:45`），与 `gamebase.ScriptVersion`（存档**内容**里的脚本版本，
     * 是另一个字段）无关。移植侧曾经写 `data.version`，于是
     * `SaveData(version = 其它)` 能产出一个自己（和原版 `CreateReader`，
     * `EraBinaryDataReader.cs:820-824`）都读不回来的文件 —— 已按原版收口。
     */
    fun write(data: SaveData): ByteArray {
        val payload = encodePayload(data)
        val src = data.sourceFile
        val srcPayload = data.sourcePayload
        if (src != null && srcPayload != null && payload.contentEquals(srcPayload)) return src.copyOf()

        val out = ByteArrayOutputStream(payload.size / 3 + 32)
        writeU64(out, if (data.compressed) MAGIC_GZIP else MAGIC_PLAIN)
        writeU32(out, VERSION.toLong())
        writeU32(out, 0L)
        if (data.compressed) out.write(gzip(payload)) else out.write(payload)
        return out.toByteArray()
    }

    /** 只编码 payload（不含 16 字节头 / GZip 外壳）。测试用，也是幂等判据的比较对象。 */
    fun encodePayload(data: SaveData): ByteArray {
        val o = Out()
        o.u8(data.fileType.ordinal)
        o.i64(data.uniqueCode)
        o.i64(data.scriptVersion)
        o.str(data.text)

        if (data.fileType == SaveFileKind.NORMAL || data.fileType == SaveFileKind.CHARVAR) {
            o.i64(data.characters.size.toLong())
            for (block in data.characters) {
                for (e in block) o.entry(e)
                o.u8(TAG_EOC)
            }
            // 角色存档（CHARVAR）在最后一个角色块后还有 EOF：SaveChara 末尾 WriteEOF()
            // （VariableEvaluator.cs:2270）。LoadChara 只读 num 个角色、不消费它（:2308-2314），
            // 但 PC 产出的 chara 文件确实带这一字节，补上才能 write(read(f))==f。
            if (data.fileType == SaveFileKind.CHARVAR) o.u8(TAG_EOF)
        }
        if (data.fileType != SaveFileKind.CHARVAR) {
            for (e in data.variables) o.entry(e)
            o.u8(TAG_EOF)
        }
        if (data.fileType == SaveFileKind.NORMAL || data.fileType == SaveFileKind.GLOBAL) {
            for (e in data.emData) o.entry(e)
            o.u8(TAG_EOF)
        }
        return o.toByteArray()
    }

    // ==================================================================
    // payload 读取细节
    // ==================================================================

    private fun readCharacterBlock(c: Cursor, keepKey: ((String) -> Boolean)?): List<SaveEntry> {
        val list = ArrayList<SaveEntry>()
        while (true) {
            if (!c.hasMore()) throw SaveFormatException("角色块在 EOC 前截断（偏移 ${c.pos}）")
            val tag = c.u8()
            when (tag) {
                TAG_EOC -> return list
                TAG_SEPARATOR -> list.add(SaveEntry.Separator)
                TAG_EOF -> return list // 原版对缺失 EOC 的旧档宽容；照读
                else -> c.entryWithTag(tag, keepKey)?.let(list::add)
            }
        }
    }

    /**
     * 读条目直到 EOF（消费 EOF 本身）。
     *
     * **EOC(254) 与 Separator(253) 在这里都非法**：变量块与 EM 块的读取循环是
     * `VariableData.LoadVariableBinary`（VariableData.cs:4081，经 `LoadFromStreamBinary` :3999-4004
     * 的 `while (LoadVariableBinary(...))` 同时驱动变量段与 EM 段），它的 switch 只有
     * Map/Xml/DT/EOF/Int/Str/六种数组共十二个分支（:4093-4206），253 与 254 都落到
     * `default:` 抛 `FileEE(AbnormalData)`（VariableData.cs:4207-4208）。
     * 两个标签只在角色块里合法（`CharacterData.LoadFromStreamBinary` 的
     * `case Separator:` CharacterData.cs:591、`case EOC: case EOF: return;` :672-674），
     * 写侧也只在那里出现：`WriteSeparator()` 全仓唯一调用点是 CharacterData.cs:548，
     * 变量/EM 段的写侧 `SaveToStreamBinary`（VariableData.cs:3980-3997）只用 `WriteWithKey`，
     * 既不写 253 也不写 254 —— 所以真档不会命中这两条，命中即损坏档。
     * 原版 `LOADDATA` 之后的装载段没有 catch（VariableEvaluator.cs:2687-2707 + :2609-2642），
     * 移植侧同口径抛 [SaveFormatException]，由
     * [com.eratw.port.core.save.FileSystemOps] 落成 `CodeEE`。
     *
     * **读到 payload 末尾仍没见到 EOF 同样是坏档**：原版 `LoadVariableBinary`
     * （VariableData.cs:4081-4208）只在读到 `EraSaveDataType.EOF` 时返回 false 结束循环，
     * 底层 `BinaryReader` 越界会抛 `EndOfStreamException`；不存在「无声结束」这条路。
     * 早期移植版 `if (!c.hasMore()) return list` 会把截断档读成「变量只装了一半」的合法结果，
     * 现与 [readCharacterBlock] 同口径改为抛 [SaveFormatException]。
     */
    private fun readEntries(c: Cursor, keepKey: ((String) -> Boolean)?): List<SaveEntry> {
        val list = ArrayList<SaveEntry>()
        while (true) {
            if (!c.hasMore()) {
                throw SaveFormatException("变量块/EM 块在 EOF 前截断（偏移 ${c.pos}）")
            }
            val tag = c.u8()
            when (tag) {
                TAG_EOF -> return list
                TAG_SEPARATOR, TAG_EOC -> throw SaveFormatException(
                    "变量块/EM 块里出现 ${if (tag == TAG_EOC) "EOC(0xFE)" else "Separator(0xFD)"}" +
                        "（偏移 ${c.pos - 1}）—— 原版 VariableData.cs:4207-4208 抛 AbnormalData",
                )
                else -> c.entryWithTag(tag, keepKey)?.let(list::add)
            }
        }
    }

    /**
     * 多维数组的元素总数：**逐维**做溢出安全乘法，同时过三道闸 ——
     * 元素数 ≤ [MAX_ARRAY_ELEMENTS]（与 readLength 同口径）、单条目分配字节 ≤ [MAX_ENTRY_BYTES]、
     * 本次读档累计分配 ≤ [MAX_TOTAL_BYTES]（经 [Cursor.claim]）。
     *
     * 原版是 `new long[num, num2]` / `new string[num, num2, num3]`
     * （`EraBinaryDataReader.cs:177-292` / `:294-` 3D，以及字符串数组的同名分支），由 .NET
     * 按维度分配、不做 32 位预乘；移植侧若照抄 `d0 * d1 * d2` 的 Int 乘法，大维度会在溢出后
     * 变成负数 ⇒ `NegativeArraySizeException`，或变成一个偏小的正数 ⇒ 后续 `a[i * d1 + j]`
     * 抛 `ArrayIndexOutOfBoundsException`。两处都不是 [SaveFormatException]。
     *
     * 只挡 `Int.MAX_VALUE` 也不够：60 字节的坏档即可声明 `20000 × 20000`（4 亿元素，乘积没过
     * Int 上界）⇒ `LongArray(4e8)` 要 3.2 GB，读档直接 OOM；`LongArray(2_000_000_000)` 是 16 GB，
     * `readLength` 放行后的单维大数同样会掀翻堆（`OutOfMemoryError` 是 `Error`，读档的 catch 接不住）。
     * 每乘一维之前先判 `total > 上限 / d`，Long 域不会溢出。
     *
     * 不拿 `payload.size` 当元素数上界：二进制数组是 0 游程压缩的（`0xF0` / `0xE0` 一类标记
     * + 变长整数，EraBinaryDataWriter.cs:203-332），**几字节的 run 可以展开成上亿个 0**
     * —— 合法大数组的编码长度与水合长度无关，用 payload 长度卡会误杀真档。
     * 能真正约束内存的是「解压后 payload 上限 + 单条目/累计分配预算」这两道闸。
     */
    private fun Cursor.dimProduct(key: String, what: String, width: Int, vararg dims: Int): Int {
        var total = 1L
        for (d in dims) {
            if (d < 0) throw SaveFormatException("$what $key 的数组维度为负：$d")
            // 先比后乘：`dims` 最多 3 个 Int，朴素相乘会溢出 Long（1e8^3 = 1e24 > Long 上界）
            if (d > 0) {
                if (total > MAX_ARRAY_ELEMENTS.toLong() / d) {
                    throw SaveFormatException(
                        "$what $key 的数组元素数超过上限 $MAX_ARRAY_ELEMENTS" +
                            "（维度 ${dims.joinToString("x")}）",
                    )
                }
                if (total > MAX_ENTRY_BYTES / width / d) {
                    throw SaveFormatException(
                        "$what $key 声明维度 ${dims.joinToString("×")}，元素数超过单条目上限" +
                            "（$MAX_ENTRY_BYTES 字节 ÷ 每元素 $width 字节）",
                    )
                }
            }
            total *= d
        }
        return claim(key, what, total, width)
    }

    /**
     * 零游程跳转后的下标：Long 域相加，再判 `0 <= v < limit`（[allowEnd] 时放宽到 `<= limit`）。
     *
     * 原版读侧对 `0xE0/0xF0/0xF1/0xF2` 直接做 `i += num` / `j += num`
     * （`EraBinaryDataReader.cs:125-137`、`:204-245`、`:320-` 3D），负值或超限跳转由 .NET
     * 多维数组的下标检查兜住；移植侧的一维线性下标 `i * d1 + j` 却可能把越界组合算回数组
     * 内部 ⇒ 静默错写。跳转一律在 Long 域判界，`readVarInt` 的 64 位值也先加再收窄，
     * 不会像 `.toInt()` 那样把超大跳转截成小正数。
     *
     * **跳过类标签严格 `< limit`**：`0xF0/F1/F2` 只在「后面紧跟一个非零值」时才被写出
     * （`EraBinaryDataWriter.cs:242-254` / `:291-309`），落点必然是一个真实的值位置。
     * **行尾/平面尾标签 `0xE0/E1` 允许 `== limit`**：写侧对最后一个非全零行/平面也会写
     * 行尾（`EraBinaryDataWriter.cs:256-266` / `:321-329`），读侧此时 `i == d0`（`j == d1`）
     * 是合法状态，随后应当直接见到 EOF。
     */
    private fun jumpIndex(
        key: String,
        kind: String,
        what: String,
        base: Int,
        delta: Long,
        limit: Int,
        allowEnd: Boolean = false,
    ): Int {
        val v = base.toLong() + delta
        val max = if (allowEnd) limit else limit - 1
        if (v < 0 || v > max) {
            throw SaveFormatException("$kind $key 的${what}跳转到 $v，超出 0..$max")
        }
        return v.toInt()
    }

    /**
     * 读一条变量记录；[keepKey] 判 false 时返回 null（值已按 [skipValue] 走完、不建数组）。
     *
     * 键名本身总是读掉：未知变量与已知变量在流里的形状完全相同，只有值要不要留下不同。
     */
    private fun Cursor.entryWithTag(tag: Int, keepKey: ((String) -> Boolean)? = null): SaveEntry? {
        val key = readString()
        // 扩展段（Map/Xml/DT）的键**不是变量名**：它们是 `SAVEMAP`/`SAVEXML`/`SAVEDATA`
        // 声明的名字，走 ConstantData 的表而不是变量注册表 ⇒ 用「变量名是否已知」筛会误删
        // （SaveEmPoolTest / SaveRoundTripAuditTest 实证）。它们本来也不按存档尺寸建大数组，
        // 不参与那条预算，故一律保留。
        val variableEntry = tag != TAG_MAP && tag != TAG_XML && tag != TAG_DT
        if (keepKey != null && variableEntry && !keepKey(key)) {
            skipValue(tag, key)
            return null
        }
        return when (tag) {
            0 -> SaveEntry.IntValue(key, readVarInt())
            1 -> {
                val n = claim(key, "Int1D", readLength().toLong(), ELEMENT_BYTES)
                val a = LongArray(n)
                var i = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    if (b == 0xF0) {
                        i = jumpIndex(key, "Int1D", "下标", i, readVarInt(), n)
                        continue
                    }
                    if (i < 0 || i >= n) throw SaveFormatException("Int1D $key 的元素数超过声明的 $n")
                    a[i++] = intValue(b)
                }
                SaveEntry.IntArray1(key, a)
            }
            2 -> {
                val d0 = readLength(); val d1 = readLength()
                val a = LongArray(dimProduct(key, "Int2D", ELEMENT_BYTES, d0, d1))
                var i = 0; var j = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF1 -> {
                            i = jumpIndex(key, "Int2D", "行下标", i, readVarInt(), d0)
                            j = 0
                        }

                        0xE0 -> {
                            i = jumpIndex(key, "Int2D", "行下标", i, 1L, d0, allowEnd = true)
                            j = 0
                        }

                        0xF0 -> j = jumpIndex(key, "Int2D", "列下标", j, readVarInt(), d1)
                        else -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1) throw SaveFormatException("Int2D $key 越界")
                            a[i * d1 + j] = intValue(b); j++
                        }
                    }
                }
                SaveEntry.IntArray2(key, d0, d1, a)
            }
            3 -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                val a = LongArray(dimProduct(key, "Int3D", ELEMENT_BYTES, d0, d1, d2))
                var i = 0; var j = 0; var k = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF2 -> {
                            i = jumpIndex(key, "Int3D", "平面下标", i, readVarInt(), d0)
                            j = 0; k = 0
                        }

                        0xE1 -> {
                            i = jumpIndex(key, "Int3D", "平面下标", i, 1L, d0, allowEnd = true)
                            j = 0; k = 0
                        }

                        0xF1 -> {
                            j = jumpIndex(key, "Int3D", "行下标", j, readVarInt(), d1)
                            k = 0
                        }

                        0xE0 -> {
                            j = jumpIndex(key, "Int3D", "行下标", j, 1L, d1, allowEnd = true)
                            k = 0
                        }

                        0xF0 -> k = jumpIndex(key, "Int3D", "列下标", k, readVarInt(), d2)
                        else -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1 || k < 0 || k >= d2) {
                                throw SaveFormatException("Int3D $key 越界")
                            }
                            a[(i * d1 + j) * d2 + k] = intValue(b); k++
                        }
                    }
                }
                SaveEntry.IntArray3(key, d0, d1, d2, a)
            }
            16 -> SaveEntry.StrValue(key, readString())
            17 -> {
                val n = claim(key, "Str1D", readLength().toLong(), REF_BYTES)
                val a = arrayOfNulls<String>(n)
                var i = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF0 -> i = jumpIndex(key, "Str1D", "下标", i, readVarInt(), n)
                        0xD8 -> {
                            if (i < 0 || i >= n) throw SaveFormatException("Str1D $key 的元素数超过声明的 $n")
                            a[i++] = readString()
                        }
                        else -> throw SaveFormatException("Str1D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray1(key, a)
            }
            18 -> {
                val d0 = readLength(); val d1 = readLength()
                val a = arrayOfNulls<String>(dimProduct(key, "Str2D", REF_BYTES, d0, d1))
                var i = 0; var j = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF1 -> {
                            i = jumpIndex(key, "Str2D", "行下标", i, readVarInt(), d0)
                            j = 0
                        }

                        0xE0 -> {
                            i = jumpIndex(key, "Str2D", "行下标", i, 1L, d0, allowEnd = true)
                            j = 0
                        }

                        0xF0 -> j = jumpIndex(key, "Str2D", "列下标", j, readVarInt(), d1)
                        0xD8 -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1) throw SaveFormatException("Str2D $key 越界")
                            a[i * d1 + j] = readString(); j++
                        }
                        else -> throw SaveFormatException("Str2D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray2(key, d0, d1, a)
            }
            19 -> {
                val d0 = readLength(); val d1 = readLength(); val d2 = readLength()
                val a = arrayOfNulls<String>(dimProduct(key, "Str3D", REF_BYTES, d0, d1, d2))
                var i = 0; var j = 0; var k = 0
                while (true) {
                    val b = u8()
                    if (b == TAG_EOF) break
                    when (b) {
                        0xF2 -> {
                            i = jumpIndex(key, "Str3D", "平面下标", i, readVarInt(), d0)
                            j = 0; k = 0
                        }

                        0xE1 -> {
                            i = jumpIndex(key, "Str3D", "平面下标", i, 1L, d0, allowEnd = true)
                            j = 0; k = 0
                        }

                        0xF1 -> {
                            j = jumpIndex(key, "Str3D", "行下标", j, readVarInt(), d1)
                            k = 0
                        }

                        0xE0 -> {
                            j = jumpIndex(key, "Str3D", "行下标", j, 1L, d1, allowEnd = true)
                            k = 0
                        }

                        0xF0 -> k = jumpIndex(key, "Str3D", "列下标", k, readVarInt(), d2)
                        0xD8 -> {
                            if (i < 0 || i >= d0 || j < 0 || j >= d1 || k < 0 || k >= d2) {
                                throw SaveFormatException("Str3D $key 越界")
                            }
                            a[(i * d1 + j) * d2 + k] = readString(); k++
                        }
                        else -> throw SaveFormatException("Str3D $key 的非法标签 $b")
                    }
                }
                SaveEntry.StrArray3(key, d0, d1, d2, a)
            }
            TAG_MAP -> {
                val n = claim(key, "Map", readLength().toLong(), REF_BYTES)
                val m = ArrayList<Pair<String, String>>(n)
                repeat(n) { m.add(readString() to readString()) }
                SaveEntry.MapValue(key, m)
            }
            TAG_XML -> SaveEntry.XmlValue(key, readString())
            TAG_DT -> SaveEntry.DataTableValue(key, readString(), readString())
            else -> throw SaveFormatException("未知的条目类型字节 $tag（变量 $key，偏移 ${pos - 1}）")
        }
    }

    /**
     * 只走标签、不建数组地跳过一条「当前游戏里已不存在」的变量值。
     *
     * 原版读到未声明变量时也把值读完即丢，但它是先按**存档声明的尺寸**建临时数组再扔
     * （`VariableData.cs` 的 `ReadStrArray(null, needInit: true)` 一族）：4.956 时代
     * 每角色 10 万项的 `MOE_RUMIA_COM_RECORD` × 162 个角色，读完就是上百 MiB 的垃圾。
     * 在 512 MiB 机型上这一步足以把读档打成 OOM，而且这些字节还会被 [Cursor.claim]
     * 计入累计分配预算，使整份档在 256 MiB 闸前被误判成坏档（2026-10-29 真机实测）。
     *
     * 标签非法仍抛 [SaveFormatException]：跳过的条目同样是档的一部分，
     * 「键不认识」不能成为放过坏档的理由。
     */
    private fun Cursor.skipValue(tag: Int, key: String) {
        when (tag) {
            0 -> readVarInt()
            1 -> {
                readLength(); skipArray(key, str = false)
            }

            2 -> {
                readLength(); readLength(); skipArray(key, str = false)
            }

            3 -> {
                readLength(); readLength(); readLength(); skipArray(key, str = false)
            }

            16 -> readString()
            17 -> {
                readLength(); skipArray(key, str = true)
            }

            18 -> {
                readLength(); readLength(); skipArray(key, str = true)
            }

            19 -> {
                readLength(); readLength(); readLength(); skipArray(key, str = true)
            }

            TAG_MAP -> {
                val n = readLength()
                repeat(n) { readString(); readString() }
            }

            TAG_XML -> readString()
            TAG_DT -> {
                readString(); readString()
            }

            else -> throw SaveFormatException("未知的条目类型字节 $tag（变量 $key，偏移 ${pos - 1}）")
        }
    }

    /**
     * 数组值的纯游标推进：标签集与各 [entryWithTag] 分支逐一对应
     * （`0xF0/0xF1/0xF2` 带变长计数、`0xE0/0xE1` 无载荷、`0xD8` + 字符串、其余是值）。
     * 维度已在调用处由 [readLength] 消费并判界，这里不再需要它们。
     */
    private fun Cursor.skipArray(key: String, str: Boolean) {
        while (true) {
            val b = u8()
            when (b) {
                TAG_EOF -> return
                0xF0, 0xF1, 0xF2 -> readVarInt()
                0xE0, 0xE1 -> Unit
                0xD8 -> if (str) readString() else {
                    throw SaveFormatException("Int 数组 $key 出现字符串标签 0xD8（偏移 ${pos - 1}）")
                }

                else -> if (str) {
                    throw SaveFormatException("Str 数组 $key 的非法标签 $b（偏移 ${pos - 1}）")
                } else {
                    intValue(b)
                }
            }
        }
    }

    // ==================================================================
    // 容器（16 字节头 + GZip）
    // ==================================================================

    private class Container(val gzip: Boolean, val version: Int, val payload: ByteArray)

    private fun readContainer(bytes: ByteArray): Container {
        if (bytes.size < 16) throw SaveFormatException("文件长度 ${bytes.size} < 16")
        val magic = readU64(bytes, 0)
        if (magic != MAGIC_PLAIN && magic != MAGIC_GZIP) {
            throw SaveFormatException("魔数不符：$magic（不是 Emuera 二进制存档）")
        }
        val version = readU32(bytes, 8).toInt()
        if (version != VERSION) throw SaveFormatException("不支持的存档版本 $version（只认 1808）")
        val tableCount = readU32(bytes, 12)
        if (tableCount > 1_000_000L) throw SaveFormatException("校验表条数异常：$tableCount")
        val bodyOff = 16 + tableCount.toInt() * 4
        if (bodyOff > bytes.size) throw SaveFormatException("校验表越界")
        val payload = if (magic == MAGIC_GZIP) {
            gunzip(bytes, bodyOff)
        } else {
            bytes.copyOfRange(bodyOff, bytes.size)
        }
        return Container(magic == MAGIC_GZIP, version, payload)
    }

    /**
     * GZip 解压，**带上限分块读**。
     *
     * 原版 `EraBinaryDataReader.CreateReader` 把 `GZipStream` 交给 `BinaryReader` 一路读
     * （`EraBinaryDataReader.cs:807-815`），没有任何长度闸：几十 KB 的压缩体可以解出数 GB，
     * `OutOfMemoryError` 是 `Error`，读档链的 catch 接不住。移植侧按 [gunzipLimit] 边读边计数，
     * 超限即抛 [SaveFormatException]（坏档 ⇒ 与原版「读档失败」同一条产品路径）
     * —— `CHKDATA` / `CHKCHARADATA` 的 [peek] 也走这里，同一道闸。
     *
     * @param maxBytes 只给测试注入更小的预算用；生产调用一律用 [gunzipLimit]
     */
    internal fun gunzip(bytes: ByteArray, off: Int, maxBytes: Long = gunzipLimit(bytes.size)): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(bytes, off, bytes.size - off)).use { ins ->
            // 预分配按「压缩体 ×4」的乐观估计，但夹在 64 B..1 MiB：真档约 7 倍压缩，
            // 而大文件的初始缓冲不该一次吃满（ByteArrayOutputStream 会立刻分配）。
            val hint = ((bytes.size - off).toLong() * 4).coerceIn(64L, 1L shl 20).toInt()
            val out = ByteArrayOutputStream(hint)
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw SaveFormatException(
                        "GZip 解压后超过上限 $maxBytes 字节（压缩体 ${bytes.size - off} 字节，疑似解压炸弹）",
                    )
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: SaveFormatException) {
        throw e
    } catch (e: Exception) {
        throw SaveFormatException("GZip 解压失败：${e.message}")
    }

    /**
     * 解压体积上限：`min(64 MiB, max(1 MiB, 文件字节 × 256))`。
     *
     * 实测正常档的压缩比 ≈ 9.5×（60151 B 解出 571185 B），256× 留出两个数量级余量；
     * 64 MiB 是单份存档正文的堆安全上限（见 [MAX_ARRAY_ELEMENTS] 的同一条理由）。
     */
    private fun gunzipLimit(compressedBytes: Int): Long {
        val scaled = compressedBytes.toLong() * 256L
        return minOf(GZIP_MAX_BYTES, maxOf(GZIP_MIN_BYTES, scaled))
    }

    /** GZip 解压上限的上下界（理由见 [gunzipLimit]）。 */
    private const val GZIP_MIN_BYTES = 1L * 1024 * 1024
    private const val GZIP_MAX_BYTES = 64L * 1024 * 1024

    /**
     * 与 .NET 8 `GZipStream` 同壳的压缩（头 10 字节 + raw deflate + CRC32 + ISIZE）。
     * 头字段实测：MTIME=0 / XFL=0 / OS=0x0A；deflate 数据本身与 .NET 不保证逐字节相同。
     */
    private fun gzip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size / 3 + 64)
        out.write(GZIP_HEADER)
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            def.setInput(payload)
            def.finish()
            val buf = ByteArray(1 shl 16)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            def.end()
        }
        val crc = CRC32().apply { update(payload) }.value
        writeU32(out, crc)
        writeU32(out, payload.size.toLong() and 0xFFFF_FFFFL)
        return out.toByteArray()
    }

    // ==================================================================
    // 低层游标 / 写入器
    // ==================================================================

    private const val MAX_CHARA = 1_000_000

    /**
     * 单条数组的元素上限（也用于 `readLength` 的单维长度 / Map 条数判定）。
     *
     * 取 16,000,000 而不是 `Int.MAX_VALUE`：`LongArray` 一项 8 B（16M 项 = 128 MiB），
     * 字符串数组一项 4~8 B；更大的声明只会把堆打碎，`OutOfMemoryError` 是 `Error`，
     * 读档链的 `catch` 接不住。实测存档正文整份才 571 KB（`SaveFormat` 类 KDoc 的表），
     * 真实数组与 16M 差着两个数量级。原版没有这一层（`EraBinaryDataReader.cs:106-109`
     * 直接 `new long[num]`），属移植侧的堆安全约束。
     */
    private const val MAX_ARRAY_ELEMENTS = 16_000_000

    /**
     * 解压后 payload 上限（256 MiB）。原版 `GZipStream` 由 .NET 按需扩容、没有闸
     * （`EraBinaryDataReader.cs:807-815`）；移植侧不给上限的话，一个几百 KB 的
     * GZip 炸弹就能把进程打穿。这个值取 [MAX_TOTAL_BYTES] 同量级：真实样本解压后
     * 是 571,185 字节（见类 KDoc），留 400 倍余量。
     */
    private const val MAX_PAYLOAD_BYTES: Long = 256L * 1024 * 1024

    /**
     * 单条目数组的分配预算（64 MiB ≈ 838 万个 Long）。只对**声明**的维度生效，
     * 不引用 [MAX_PAYLOAD_BYTES] 作元素数上界（0 游程压缩让两者无关，见 [dimProduct]）。
     * 参照：eratw 的系统数组最大是 TA/TB 的 (100,100,100) = 100 万元素 = 8 MiB
     * （specs 的 variables.yaml 里 `multi_dim_arrays` 一节），此处留 8 倍余量。
     */
    private const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    /**
     * 一次读档的累计分配预算（256 MiB）。单个条目 64 MiB 的上限挡不住「很多条目各要
     * 几十 MB」：解压上限是 256 MiB，一个条目头只要几字节 ⇒ 同一个小文件能声明出
     * 上千个巨型数组。累计预算把整次解码的分配钉在 [MAX_PAYLOAD_BYTES] 同量级。
     * 参照：6 个实机样本里最大的 save00.sav 解压后 571 KB，解码出的数组约 7 MiB。
     */
    private const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** `LongArray` 每元素 8 字节。 */
    private const val ELEMENT_BYTES = 8

    /**
     * 字符串数组按 8 字节/引用估算（64 位 JVM / ART 的压缩引用是 4 字节，
     * 这里取**宽**的一侧：预算只该偏保守）。
     */
    private const val REF_BYTES = 8

    private class Cursor(val b: ByteArray) {
        var pos = 0

        /** 已申报的分配字节数（[claim] 累加）。 */
        private var allocated = 0L

        /**
         * 分配预算：声明 [elements] 个元素、每个 [width] 字节。
         *
         * 原版 `.NET` 直接 `new long[n]`，坏档的大维度由 OOM 兜底（进程级崩溃）；
         * 移植侧要落成可诊断的 [SaveFormatException]（[com.eratw.port.core.save.FileSystemOps]
         * 会转成 `CodeEE`），所以先按字节算账再分配。
         *
         * @return 元素数（已校验在 Int 范围内）
         */
        fun claim(key: String, what: String, elements: Long, width: Int): Int {
            if (elements < 0 || elements > Int.MAX_VALUE) {
                throw SaveFormatException("$what $key 的元素数 $elements 超出可分配上限（偏移 $pos）")
            }
            val bytes = elements * width
            if (bytes > MAX_ENTRY_BYTES) {
                throw SaveFormatException(
                    "$what $key 声明 $elements 个元素 ≈ $bytes 字节，" +
                        "超过单条目上限 $MAX_ENTRY_BYTES 字节（偏移 $pos）",
                )
            }
            allocated += bytes
            if (allocated > MAX_TOTAL_BYTES) {
                throw SaveFormatException(
                    "$what $key 使本次读档的累计分配超过上限 $MAX_TOTAL_BYTES 字节（偏移 $pos）",
                )
            }
            return elements.toInt()
        }

        fun hasMore(): Boolean = pos < b.size

        fun u8(): Int {
            if (pos >= b.size) throw SaveFormatException("payload 截断（偏移 $pos）")
            return b[pos++].toInt() and 0xFF
        }

        fun i16(): Int {
            need(2)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v.toShort().toInt()
        }

        fun i32(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun i64(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        /** `m_WriteInt` 的对称读法（EraBinaryDataWriter.cs:175-196）。 */
        fun readVarInt(): Long {
            val first = u8()
            return when {
                first <= 207 -> first.toLong()
                first == 208 -> i16().toLong()
                first == 209 -> i32().toLong()
                first == 210 -> i64()
                else -> throw SaveFormatException("非法变长整数标签 $first（偏移 ${pos - 1}）")
            }
        }

        fun intValue(tag: Int): Long = when {
            tag <= 207 -> tag.toLong()
            tag == 208 -> i16().toLong()
            tag == 209 -> i32().toLong()
            tag == 210 -> i64()
            else -> throw SaveFormatException("非法整数值标签 $tag（偏移 ${pos - 1}）")
        }

        /** 7-bit 字节长度 + UTF-16LE 原始码元。 */
        fun readString(): String {
            val byteLen = readS7()
            if (byteLen < 0 || byteLen > b.size - pos) {
                throw SaveFormatException("字符串长度 $byteLen 越界（偏移 $pos，剩余 ${b.size - pos}）")
            }
            if ((byteLen and 1) != 0) throw SaveFormatException("UTF-16 字符串字节数为奇数：$byteLen")
            val chars = CharArray(byteLen / 2)
            for (i in chars.indices) {
                val lo = b[pos + 2 * i].toInt() and 0xFF
                val hi = b[pos + 2 * i + 1].toInt() and 0xFF
                chars[i] = (lo or (hi shl 8)).toChar()
            }
            pos += byteLen
            return String(chars)
        }

        private fun readS7(): Int {
            var n = 0
            var shift = 0
            while (true) {
                val x = u8()
                n = n or ((x and 0x7F) shl shift)
                if ((x and 0x80) == 0) return n
                shift += 7
                if (shift > 28) throw SaveFormatException("7-bit 长度前缀过长")
            }
        }

        fun readLength(): Int {
            val n = i32()
            if (n < 0 || n > MAX_ARRAY_ELEMENTS) {
                throw SaveFormatException("数组长度异常：$n（偏移 ${pos - 4}，上限 $MAX_ARRAY_ELEMENTS）")
            }
            return n
        }

        private fun need(n: Int) {
            if (pos + n > b.size) throw SaveFormatException("payload 截断（偏移 $pos 需要 $n 字节）")
        }
    }

    private class Out {
        private val buf = ByteArrayOutputStream(1 shl 16)

        fun u8(v: Int) = buf.write(v and 0xFF)

        fun i32(v: Int) {
            for (i in 0 until 4) buf.write((v ushr (8 * i)) and 0xFF)
        }

        fun i64(v: Long) {
            for (i in 0 until 8) buf.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun varInt(v: Long) {
            when {
                v in 0..207 -> u8(v.toInt())
                v in Short.MIN_VALUE..Short.MAX_VALUE -> {
                    u8(208)
                    val s = v.toInt()
                    buf.write(s and 0xFF); buf.write((s ushr 8) and 0xFF)
                }
                v in Int.MIN_VALUE..Int.MAX_VALUE -> {
                    u8(209)
                    i32(v.toInt())
                }
                else -> {
                    u8(210)
                    i64(v)
                }
            }
        }

        fun str(s: String) {
            val byteLen = s.length * 2
            var n = byteLen
            while (true) {
                val x = n and 0x7F
                n = n ushr 7
                if (n != 0) buf.write(x or 0x80) else { buf.write(x); break }
            }
            for (ch in s) {
                val c = ch.code
                buf.write(c and 0xFF)
                buf.write((c ushr 8) and 0xFF)
            }
        }

        fun entry(e: SaveEntry) {
            when (e) {
                is SaveEntry.IntValue -> { u8(0); str(e.key); varInt(e.value) }
                is SaveEntry.IntArray1 -> { u8(1); str(e.key); int1(e.values) }
                is SaveEntry.IntArray2 -> { u8(2); str(e.key); int2(e) }
                is SaveEntry.IntArray3 -> { u8(3); str(e.key); int3(e) }
                is SaveEntry.StrValue -> { u8(16); str(e.key); str(e.value) }
                is SaveEntry.StrArray1 -> { u8(17); str(e.key); str1(e.values) }
                is SaveEntry.StrArray2 -> { u8(18); str(e.key); str2(e) }
                is SaveEntry.StrArray3 -> { u8(19); str(e.key); str3(e) }
                is SaveEntry.MapValue -> {
                    u8(TAG_MAP); str(e.key); i32(e.entries.size)
                    for ((k, v) in e.entries) { str(k); str(v) }
                }
                is SaveEntry.XmlValue -> { u8(TAG_XML); str(e.key); str(e.xml) }
                is SaveEntry.DataTableValue -> {
                    u8(TAG_DT); str(e.key); str(e.schemaXml); str(e.dataXml)
                }
                SaveEntry.Separator -> u8(TAG_SEPARATOR)
            }
        }

        /** 0 游程压缩的 1D 整数数组（EraBinaryDataWriter.cs:203-223）。 */
        fun int1(a: LongArray) {
            i32(a.size)
            var run = 0
            for (v in a) {
                if (v == 0L) { run++; continue }
                if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                varInt(v)
            }
            u8(TAG_EOF)
        }

        fun int2(e: SaveEntry.IntArray2) {
            i32(e.dim0); i32(e.dim1)
            var run = 0; var zeroRows = 0
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    val v = e.values[i * e.dim1 + j]
                    if (v == 0L) { run++; continue }
                    if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                    if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                    varInt(v)
                }
                if (run == e.dim1) zeroRows++ else u8(0xE0)
                run = 0
            }
            u8(TAG_EOF)
        }

        fun int3(e: SaveEntry.IntArray3) {
            i32(e.dim0); i32(e.dim1); i32(e.dim2)
            var run = 0; var zeroRows = 0; var zeroPlanes = 0
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    for (k in 0 until e.dim2) {
                        val v = e.values[(i * e.dim1 + j) * e.dim2 + k]
                        if (v == 0L) { run++; continue }
                        if (zeroPlanes > 0) { u8(0xF2); varInt(zeroPlanes.toLong()); zeroPlanes = 0 }
                        if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                        if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                        varInt(v)
                    }
                    if (run == e.dim2) zeroRows++ else u8(0xE0)
                    run = 0
                }
                if (zeroRows == e.dim1) zeroPlanes++ else u8(0xE1)
                zeroRows = 0
            }
            u8(TAG_EOF)
        }

        fun str1(a: Array<String?>) {
            i32(a.size)
            var run = 0
            for (s in a) {
                if (s.isNullOrEmpty()) { run++; continue }
                if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                u8(0xD8); str(s)
            }
            u8(TAG_EOF)
        }

        fun str2(e: SaveEntry.StrArray2) {
            i32(e.dim0); i32(e.dim1)
            var run = 0; var zeroRows = 0
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    val s = e.values[i * e.dim1 + j]
                    if (s.isNullOrEmpty()) { run++; continue }
                    if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                    if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                    u8(0xD8); str(s)
                }
                if (run == e.dim1) zeroRows++ else u8(0xE0)
                run = 0
            }
            u8(TAG_EOF)
        }

        fun str3(e: SaveEntry.StrArray3) {
            i32(e.dim0); i32(e.dim1); i32(e.dim2)
            var run = 0; var zeroRows = 0; var zeroPlanes = 0
            for (i in 0 until e.dim0) {
                for (j in 0 until e.dim1) {
                    for (k in 0 until e.dim2) {
                        val s = e.values[(i * e.dim1 + j) * e.dim2 + k]
                        if (s.isNullOrEmpty()) { run++; continue }
                        if (zeroPlanes > 0) { u8(0xF2); varInt(zeroPlanes.toLong()); zeroPlanes = 0 }
                        if (zeroRows > 0) { u8(0xF1); varInt(zeroRows.toLong()); zeroRows = 0 }
                        if (run > 0) { u8(0xF0); varInt(run.toLong()); run = 0 }
                        u8(0xD8); str(s)
                    }
                    if (run == e.dim2) zeroRows++ else u8(0xE0)
                    run = 0
                }
                if (zeroRows == e.dim1) zeroPlanes++ else u8(0xE1)
                zeroRows = 0
            }
            u8(TAG_EOF)
        }

        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    // ==================================================================
    // 定长整数
    // ==================================================================

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun writeU64(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
}

/** 存档头（[SaveFormat.peek] 的结果）。 */
data class SaveHeader(
    val binary: Boolean,
    val gzip: Boolean,
    val version: Int,
    val fileType: SaveFileKind,
    val uniqueCode: Long,
    val scriptVersion: Long,
    val text: String,
)

/** 存档解析/写入失败（不是 Emuera 存档、截断、标签非法等）。 */
class SaveFormatException(message: String) : RuntimeException(message)
