package com.eratw.port.core.save

import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

/**
 * 存档**写路径**的往返验收：真实 PC 存档（`.workbuddy/sav-backup/sav-20261018-110513/`，
 * 仓内只读样本）读 → 写 → 再读，逐字段对拍。
 *
 * ## 本文件钉住的三层
 *
 * 1. **编码层**（`core/data/save` 的 `SaveFormat`，由本路调用而不是修改）：
 *    `read` → 重建同内容 `SaveData`（丢掉 `sourceFile` 原样回写捷径）→ `write` → `read`，
 *    六份样本的**每个条目**必须逐项相同，且 payload 逐字节等于原文件 payload。
 *    这条是 [SaveCodec] 写分支的证据：一旦 `0xF0/0xE0/0xE1/0xF1/0xF2` 游程编码或
 *    7-bit 字符串前缀错一位，读回就会漂。
 * 2. **容器层**：重新压缩的分支必须是 `.NET 8 GZipStream` 同壳 ——
 *    魔数 `743174590507926921`（EraBinaryDataWriter.cs:39）、版本 1808（:45）、
 *    校验表条数恒 0（:46）、GZip 头 `1f 8b 08 00 00000000 00 0a`（实测），
 *    尾部 CRC32 + ISIZE 自洽。.NET 侧只要求「能解压」（spec/save-format.md §6.3），
 *    故不要求 deflate 字节与 .NET 一致。
 * 3. **装配层**（`core/save` 目录）：真实存档 → 变量池（CSV 注册表 + ERH 全局 `#DIM`）→
 *    `FileSystemOps.saveData` → 新变量池，系统/角色/全局字段逐项一致，
 *    写出的文件能被现有探测路径（[SaveDataProbe.checkBytes]）判 OK。
 *
 * ## 哪些字段**还不能**写（本路实测，详见交付报告）
 *
 * - **ERH 全局 `#DIM SAVEDATA` 未注册时整批丢失**：写侧筛选只认 `VarDef.isSavedata`
 *   （`SaveCapture` 类 KDoc；spec VariableData.cs:3980-3997），没走过
 *   `SystemVariableBootstrap.install` 的变量池里没有这些变量 ⇒ 只有 63 个系统条目可写
 *   （真档 540 条）。本文件的变量池用例因此**先**装 ERH 声明，与 `GameSession` 第 4 步同源。
 * - **`NICKNAME` / `MASTERNAME` 只在「被赋过值」的角色块里落条目**：原版靠
 *   `EraBinaryDataWriter.WriteWithKey(string, object)` 的 `v is T` 分派、`null` 时整条不落
 *   （EraBinaryDataWriter.cs:83-173），移植侧按值等价收窄在 `data/save/CharaFileIO` 的
 *   `isNullInOriginal`（总账 SAVE-04 收口，B5 L4）；见 [额外角色字段]。
 * - **Map/Xml/DT 扩展段**要求 `FileSystemOps` 拿到 `EmSaveSets`（`VarExt*.csv`）；
 *   未装配 = 空表 = 不写（`ConstantData.cs:1311-1316`）。本文件装 `ERATW_GAMEDIR/CSV` 的真表。
 */
class SaveRoundTripTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 仓内只读样本目录；缺样本按假设失败跳过（不伪造读数）。 */
    private fun samplesDir(): Path? = SAMPLE_CANDIDATES
        .map { Path.of(it).toAbsolutePath().normalize() }
        .firstOrNull { Files.isDirectory(it) }

    /** 游戏根：`ERATW_GAMEDIR` 显式给出即权威；回落 `ERATW_CORPUS`（指到 ERB 子树）的父目录。 */
    private fun gameDir(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(corpus).parent
    }

    private fun sample(name: String): ByteArray? {
        val dir = samplesDir() ?: return null
        val f = dir.resolve(name)
        return if (Files.isRegularFile(f)) Files.readAllBytes(f) else null
    }

    private fun store(): VariableStore = VariableStore()

    private fun VariableStore.addHero(name: String = "アリス"): Int = addCharacter(
        CharacterTemplate(
            no = 1, name = name, callname = name, maxbase = mapOf(0 to 10L),
        ),
    )

    private fun idx(vararg v: Long): LongArray = v

    /** 内存 [SaveFileIo]（路径键与生产实现同为相对路径）。 */
    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean {
            files.remove(path)
            return true
        }
    }

    /**
     * 条目签名：把条目压成一个可比字符串。int 数组**保留维度与尾零**（写读两侧同形），
     * 字符串数组把 null 归一成空串（写侧 null 与 "" 等同，EraBinaryDataWriter.cs:352）。
     */
    private fun sig(e: SaveEntry): String = when (e) {
        is SaveEntry.IntValue -> "I:${e.value}"
        is SaveEntry.IntArray1 -> "I1:" + e.values.joinToString(",")
        is SaveEntry.IntArray2 -> "I2:${e.dim0}x${e.dim1}:" + e.values.joinToString(",")
        is SaveEntry.IntArray3 -> "I3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.joinToString(",")
        is SaveEntry.StrValue -> "S:${e.value}"
        is SaveEntry.StrArray1 -> "S1:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.StrArray2 -> "S2:${e.dim0}x${e.dim1}:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.StrArray3 -> "S3:${e.dim0}x${e.dim1}x${e.dim2}:" + e.values.joinToString(",") { it ?: "" }
        is SaveEntry.MapValue -> "M:" + e.entries.joinToString(",") { "${it.first}=${it.second}" }
        is SaveEntry.XmlValue -> "X:${e.xml}"
        is SaveEntry.DataTableValue -> "D:${e.schemaXml}\u0000${e.dataXml}"
        SaveEntry.Separator -> "SEP"
    }

    private fun byKey(entries: List<SaveEntry>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (e in entries) if (e !is SaveEntry.Separator) m[e.key] = sig(e)
        return m
    }

    /** 两个 blocks 的键 → 值差异（`orig` 有而 `back` 无/不同 ⇒ 「缺」「差」；反之「多」）。 */
    private fun diffBlocks(label: String, orig: List<SaveEntry>, back: List<SaveEntry>): List<String> {
        val out = ArrayList<String>()
        val a = byKey(orig)
        val b = byKey(back)
        for ((k, v) in a) {
            val w = b[k]
            when {
                w == null -> out.add("$label 缺 $k")
                w != v -> out.add("$label 差 $k：${v.take(60)} ≠ ${w.take(60)}")
            }
        }
        for (k in b.keys) if (!a.containsKey(k)) out.add("$label 多 $k")
        return out
    }

    /** 只查「原档有、写回没有/不同」的方向（写侧允许产出原档没有的已知变量，见 [额外角色字段]）。 */
    private fun missingOnly(label: String, orig: List<SaveEntry>, back: List<SaveEntry>): List<String> {
        val out = ArrayList<String>()
        val b = byKey(back)
        for ((k, v) in byKey(orig)) {
            val w = b[k]
            when {
                w == null -> out.add("$label 缺 $k")
                w != v -> out.add("$label 差 $k")
            }
        }
        return out
    }

    /** 整帧差异（头 + 角色块 + 全局块）。 */
    private fun diffFrame(orig: SaveData, back: SaveData, withEm: Boolean = true): List<String> {
        val out = ArrayList<String>()
        if (orig.fileType != back.fileType) out.add("类别 ${orig.fileType} ≠ ${back.fileType}")
        if (orig.uniqueCode != back.uniqueCode) out.add("UniqueCode ${orig.uniqueCode} ≠ ${back.uniqueCode}")
        if (orig.scriptVersion != back.scriptVersion) {
            out.add("ScriptVersion ${orig.scriptVersion} ≠ ${back.scriptVersion}")
        }
        if (orig.text != back.text) out.add("描述「${orig.text}」≠「${back.text}」")
        if (orig.characters.size != back.characters.size) {
            out.add("角色数 ${orig.characters.size} ≠ ${back.characters.size}")
        }
        for (i in 0 until minOf(orig.characters.size, back.characters.size)) {
            out += diffBlocks("角色$i", orig.characters[i], back.characters[i])
        }
        out += diffBlocks("全局", orig.variables, back.variables)
        if (withEm) out += diffBlocks("EM", orig.emData, back.emData)
        return out
    }

    /** 重建同内容的 [SaveData]（不带 `sourceFile` ⇒ 强制走编码器而不是原样回写）。 */
    private fun rebuild(d: SaveData): SaveData = SaveData(
        fileType = d.fileType,
        uniqueCode = d.uniqueCode,
        scriptVersion = d.scriptVersion,
        text = d.text,
        characters = d.characters,
        variables = d.variables,
        emData = d.emData,
        compressed = d.compressed,
        version = d.version,
    )

    /**
     * UTF-16LE 字节级串搜索（不经过 `decode`）：
     * 二进制 payload 含随机字节，用「解码成字符串再正则」会被代理对/奇数字节干扰，
     * 故直接按 `char` 低位在前逐字节比较。仅用于「样本里有没有这个变量名」这类存在性判定。
     */
    private fun countUtf16(bytes: ByteArray, s: String): Int {
        if (s.isEmpty()) return 0
        val pat = ByteArray(s.length * 2)
        for (i in s.indices) {
            pat[2 * i] = (s[i].code and 0xFF).toByte()
            pat[2 * i + 1] = ((s[i].code ushr 8) and 0xFF).toByte()
        }
        var n = 0
        var i = 0
        while (i + pat.size <= bytes.size) {
            var hit = true
            for (j in pat.indices) {
                if (bytes[i + j] != pat[j]) {
                    hit = false
                    break
                }
            }
            if (hit) n++
            i++
        }
        return n
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    // ==================================================================
    // 1. 编码层：真实存档 read → （强制重编码）→ read
    // ==================================================================

    /**
     * 六份真实样本（5 个 Normal + 1 个 Global）读→写→再读，**每个条目逐项相同**。
     *
     * 判据不是「字节相等」而是「字段相等」：重编码分支必然换一套 deflate 字节
     * （spec §6.3 实测 .NET 510 B vs zlib 508 B），故这里只要求 payload 相等 + 读回逐项相等。
     * 条目覆盖：168 个角色块 × 27 系统条目 + 用户角色变量、540 条全局条目、2 个 DataTable。
     */
    @Test
    fun `真实存档重编码后逐项一致`() {
        val names = listOf("save00.sav", "save01.sav", "save02.sav", "save03.sav", "save1000.sav", "global.sav")
        val missing = names.filter { sample(it) == null }
        Assumptions.assumeTrue(missing.isEmpty(), "缺少 PC 存档样本：$missing")

        val diffs = ArrayList<String>()
        for (name in names) {
            val src = sample(name)!!
            val orig = SaveFormat.read(src)
            val rebuilt = rebuild(orig)
            val payloadOrig = SaveFormat.sourcePayloadOf(src)
            assertNotNull(payloadOrig, "$name: 应有 GZip 容器")

            // 编码器对真实数据逐字节同构（写侧 RLE / 7-bit 前缀 / 类型字节的证据）
            val payloadNew = SaveFormat.encodePayload(rebuilt)
            if (!payloadNew.contentEquals(payloadOrig)) {
                diffs.add("$name: payload 重编码不等（${payloadOrig.size} vs ${payloadNew.size}）")
                continue
            }

            val written = SaveFormat.write(rebuilt)
            if (written.contentEquals(src)) {
                diffs.add("$name: 重编码分支不应退回原文件字节（sourceFile 已剥掉）")
            }
            val back = SaveFormat.read(written)
            diffs += diffFrame(orig, back).map { "$name: $it" }
        }
        assertTrue(diffs.isEmpty(), "真实存档写读不一致：\n" + diffs.take(30).joinToString("\n"))
    }

    /** 未改动存档：`SaveFormat.write` 直接回写原字节（spec §6.3 的 6/6 幂等机制）。 */
    @Test
    fun `未改动存档写回原字节`() {
        val src = sample("save00.sav")
        Assumptions.assumeTrue(src != null, "缺少 save00.sav 样本")
        val back = SaveFormat.write(SaveFormat.read(src!!))
        assertTrue(back.contentEquals(src), "未改动档必须原样回写（SaveFormat.write 的 sourceFile 分支）")
    }

    // ==================================================================
    // 2. 容器层
    // ==================================================================

    /**
     * 重新压缩的容器必须与 `.NET 8 GZipStream` 同壳：
     * 魔数/版本/校验表（EraBinaryDataWriter.cs:37-50）+ GZip 头（spec §6.1 实测）
     * + CRC32/ISIZE（GZip 尾，spec §6.1 第二条）。
     */
    @Test
    fun `重新压缩的容器与 GZip 同壳`() {
        val src = sample("save00.sav")
        Assumptions.assumeTrue(src != null, "缺少 save00.sav 样本")
        val written = SaveFormat.write(rebuild(SaveFormat.read(src!!)))

        var magic = 0L
        for (i in 0 until 8) magic = magic or ((written[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(SaveFormat.MAGIC_GZIP, magic, "GZip 分支魔数（EraBinaryDataWriter.cs:39）")
        assertEquals(1808L, readU32(written, 8), "版本号恒 1808（:45）")
        assertEquals(0L, readU32(written, 12), "校验表条数恒 0（:46，无有效校验和）")

        val gzipHead = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0, 0, 0, 0, 0, 0x0a)
        assertTrue(
            written.copyOfRange(16, 26).contentEquals(gzipHead),
            "GZip 头应为 1f 8b 08 00 00000000 00 0a（MTIME=0 / XFL=0 / OS=0x0A，spec §6.1）",
        )

        val payload = SaveFormat.sourcePayloadOf(written)
        assertNotNull(payload, "写出的文件应能被同一读路径解出 payload")
        val crc = CRC32().apply { update(payload) }.value
        assertEquals(crc, readU32(written, written.size - 8), "GZip 尾 CRC32 应覆盖 payload")
        assertEquals(
            payload.size.toLong() and 0xFFFF_FFFFL,
            readU32(written, written.size - 4),
            "GZip 尾 ISIZE = payload 长度",
        )
    }

    // ==================================================================
    // 3. 装配层：真实存档 → 变量池 → 写盘 → 另一个变量池
    // ==================================================================

    /**
     * 真实档经 [FileSystemOps.saveData] 落盘，再读回另一个变量池：系统与角色字段逐项一致。
     *
     * 变量池的注册面与 [com.eratw.port.core.proc.GameSession] 同源：
     * `GameDataLoader`（CSV）+ `SystemVariableBootstrap.install`（ERH 全局 `#DIM`）。
     * 少了后者就只有 63 条系统变量可写（真档 540 条），本用例会把差额暴露成「缺」。
     */
    @Test
    fun `真实存档经变量池写盘后逐项一致`(@TempDir tmp: Path) {
        val src = sample("save00.sav")
        Assumptions.assumeTrue(src != null, "缺少 save00.sav 样本")
        val game = gameDir()
        Assumptions.assumeTrue(game != null, "缺少 ERATW_GAMEDIR")
        val csv = game!!.resolve("CSV")
        val data = GameDataLoader.load(csv)
        Assumptions.assumeTrue(data != null, "缺少 ERATW_GAMEDIR/CSV，无法建真实变量池")

        val root = Files.createDirectories(tmp.resolve("game"))
        val sav = Files.createDirectories(root.resolve("sav"))
        Files.write(sav.resolve("save00.sav"), src!!)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW

        val store = realStore(data!!)
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadData(0)
        assertEquals(168, store.characters.count, "样本实测角色数 168（spec §6.2）")

        val orig = SaveFormat.read(src)
        val text = orig.text
        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveData(20, text), "SAVEDATA 应成功")

        val written = Files.readAllBytes(sav.resolve("save20.sav"))
        assertEquals(1808L, readU32(written, 8), "写出的档仍是 1808 容器")
        val back = SaveFormat.read(written)
        assertEquals(orig.characters.size, back.characters.size, "角色数")
        assertEquals(orig.variables.size, back.variables.size, "全局条目数（540）")

        // 全局块：540 条逐项相等（RANDDATA 也在其中，spec §6.4）
        val globalDiffs = diffBlocks("全局", orig.variables, back.variables)
        assertTrue(globalDiffs.isEmpty(), "全局块差异：\n" + globalDiffs.take(20).joinToString("\n"))
        assertEquals("RANDDATA", orig.variables.first { it.key == "RANDDATA" }.key, "真档全局块含 RANDDATA")

        // 角色块：原档的每个条目都必须原样写回
        val charDiffs = ArrayList<String>()
        for (i in 0 until orig.characters.size) {
            val o = byKey(orig.characters[i])
            val b = byKey(back.characters[i])
            for ((k, v) in o) {
                val w = b[k]
                if (w == null) charDiffs.add("角色$i 缺 $k") else if (w != v) charDiffs.add("角色$i 差 $k")
            }
        }
        assertTrue(charDiffs.isEmpty(), "角色块丢字段：\n" + charDiffs.take(20).joinToString("\n"))

        // 不该再多出任何角色字段：`NICKNAME`/`MASTERNAME` 为空的格原版整条不落（总账 SAVE-04）
        val extra = LinkedHashSet<String>()
        for (i in 0 until orig.characters.size) {
            val o = byKey(orig.characters[i])
            for (k in byKey(back.characters[i]).keys) if (!o.containsKey(k)) extra.add(k)
        }
        assertEquals(emptySet<String>(), extra, "额外角色字段应为空（原版按值省略 null 格，见 [额外角色字段]）")

        // 描述原样（SAVEDATA_TEXT 是写侧入参，Process.cs:2872）
        assertEquals(text, back.text, "描述文本")

        // 写出的档必须被现有探测路径判 OK（类别 / 游戏码 / 版本三重校验）
        val probe = SaveDataProbe.checkBytes(written, SaveFileKind.NORMAL, GameBaseData(gameCode = 7153L))
        assertEquals(EraDataState.OK, probe.state, "写出的 save20.sav 应被 CHKDATA 判 OK：${probe.mes}")
        assertEquals(text, probe.mes, "探测到的描述应等于写入的描述")
    }

    /**
     * 额外角色字段 `NICKNAME` / `MASTERNAME`（总账 SAVE-04，B5 L4 已收口写侧）。
     *
     * 现象曾为「本仓写出的每个角色块比 PC 样本多这两条」。**归因（2026-10-23 更正，此前写的
     * 「实机走 `VarCodeInt < 2` 那一支」是错的）**：
     * - `isSavedata` 两侧都判真：`NICKNAME = 286523394`、`MASTERNAME = 286523395` 都带
     *   `__SAVE_EXTENDED__`，`VariableToken` 构造第一支即置 true（C# `VariableToken.cs:88-96`；
     *   码值 `VariableCode.cs:17`/`:132-133`；移植侧 `core/vars/VariableCode.kt:259-261` 同规则）
     *   ⇒ 注册表没标错，下面那条现状断言仍然成立；
     * - `CharacterData.SaveToStreamBinary` 也确实把这两格交给了 writer（`CharacterData.cs:515-529`）；
     * - 真正的排除点在 writer：`WriteWithKey(string, object)` 全程按 `v is T` 运行时类型分派且
     *   **末尾没有 else**（`EraBinaryDataWriter.cs:83-173`），`dataString` 是 `new string[4]`
     *   （`CharacterData.cs:25`/`:58`），模板的 `Nickname`/`Mastername` 只在 CSV 出现
     *   `あだ名`/`主人の呼び方` 时才被赋值（`CharacterTemplate.cs:17`/`:19`；`ConstantData.cs:1591-1598`），
     *   ADDCHARA 原样拷进第 3/4 格（`CharacterData.cs:134-135`）⇒ **没赋过值就是 null ⇒ 整条不落**。
     *   真档实测（`sav/save00.sav` 逐条目解析，168 块）：分隔符前恒 27 条系统条目、无 `NICKNAME`；
     *   `MASTERNAME` 只在块 26/61 出现（值 = 昴）。
     *
     * 收口落点：`core/data/save/CharaFileIO` 的 `isNullInOriginal`（按「值为空串」判原版的 null ——
     * 移植侧字符串走驻留池、下标 0 恒为空串，没有 null 位可查，这是**已知的存档结构侧偏离**，
     * 不丢信息：省掉的空条目读回仍是池 0）。残留：`NAME`/`CALLNAME` 取**字面空串**时原版会落条目、
     * 移植侧无法与「未赋值」区分（`TODO(verify)`，真档 168/168 块名字非空，无可参照样本）。
     */
    @Test
    fun `额外角色字段`() {
        val src = sample("save00.sav")
        Assumptions.assumeTrue(src != null, "缺少 save00.sav 样本")
        val payload = SaveFormat.sourcePayloadOf(src!!)
        assertNotNull(payload)
        assertEquals(0, countUtf16(payload, "NICKNAME"), "PC 样本 payload 里不出现 NICKNAME（UTF-16LE 字节搜索）")
        // 走同一条判定入口，钉住「本仓认为它进档」这一现状（上修则会红，提醒同步本用例）
        assertTrue(
            VariableCode.isSavedata(286523394, "NICKNAME"),
            "现状：isSavedata(NICKNAME)=true（VariableCode.kt:259-261 / C# VariableToken.cs:93-96）",
        )
    }

    /** `global.sav`（25 条全局条目，无角色块）经变量池写盘后逐项一致。 */
    @Test
    fun `真实全局档经变量池写盘后逐项一致`(@TempDir tmp: Path) {
        val src = sample("global.sav")
        Assumptions.assumeTrue(src != null, "缺少 global.sav 样本")
        val game = gameDir()
        Assumptions.assumeTrue(game != null, "缺少 ERATW_GAMEDIR")
        val csv = game!!.resolve("CSV")
        val data = GameDataLoader.load(csv)
        Assumptions.assumeTrue(data != null, "缺少 ERATW_GAMEDIR/CSV")

        val root = Files.createDirectories(tmp.resolve("game"))
        Files.createDirectories(root.resolve("sav"))
        Files.write(root.resolve("sav/global.sav"), src!!)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW

        val store = realStore(data!!)
        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadGlobal(), "LOADGLOBAL 应成功")
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveGlobal()

        val written = Files.readAllBytes(root.resolve("sav/global.sav"))
        val orig = SaveFormat.read(src)
        val back = SaveFormat.read(written)
        assertEquals(SaveFileKind.GLOBAL, back.fileType, "类别（EraSaveFileType.Global）")
        assertEquals(25, orig.variables.size, "样本实测全局条目数 25")
        val diffs = diffBlocks("全局", orig.variables, back.variables)
        assertTrue(diffs.isEmpty(), "全局档字段差异：\n" + diffs.take(20).joinToString("\n"))
        assertEquals("", back.text, "全局档描述恒空串（VariableEvaluator.cs:2488）")
    }

    /** 改动一个字段后写盘：读回只见该字段变化，其余字段仍与真档一致。 */
    @Test
    fun `改动单字段后写盘只该字段变化`(@TempDir tmp: Path) {
        val src = sample("save00.sav")
        Assumptions.assumeTrue(src != null, "缺少 save00.sav 样本")
        val game = gameDir()
        Assumptions.assumeTrue(game != null, "缺少 ERATW_GAMEDIR")
        val csv = game!!.resolve("CSV")
        val data = GameDataLoader.load(csv)
        Assumptions.assumeTrue(data != null, "缺少 ERATW_GAMEDIR/CSV")

        val root = Files.createDirectories(tmp.resolve("game"))
        val sav = Files.createDirectories(root.resolve("sav"))
        Files.write(sav.resolve("save00.sav"), src!!)
        val sets = EmSaveSets.parse(csv)
        val cfg = SaveConfig.ERATW
        val store = realStore(data!!)
        FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).loadData(0)

        val orig = SaveFormat.read(src)
        val moneyKey = "MONEY"
        val moneyIdx = orig.variables.indexOfFirst { it.key == moneyKey }
        assertTrue(moneyIdx >= 0, "真档全局块应有 MONEY")
        val before = (orig.variables[moneyIdx] as SaveEntry.IntArray1).values.toList()
        val slot = before.indexOfFirst { it != 0L }.let { if (it < 0) 0 else it }
        val newValue = before[slot] + 12345L
        store.writeInt(moneyKey, idx(slot.toLong()), newValue)

        assertTrue(FileSystemOps(store, RootedSaveFileIo(root), cfg, sets).saveData(21, orig.text))
        val written = Files.readAllBytes(sav.resolve("save21.sav"))
        assertTrue(!written.contentEquals(src), "改了字段就不该原样回写")

        val back = SaveFormat.read(written)
        val after = (back.variables.first { it.key == moneyKey } as SaveEntry.IntArray1).values.toList()
        assertEquals(before.size, after.size, "MONEY 数组长度")
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(listOf(slot), changed, "只有被改的那个元素变化")
        assertEquals(newValue, after[slot], "改后的值")
        assertEquals(orig.text, back.text, "描述不变")

        // 其余全局条目与全部角色条目逐项不变
        val others = orig.variables.filter { it.key != moneyKey }
        val othersBack = back.variables.filter { it.key != moneyKey }
        assertTrue(diffBlocks("全局(除 MONEY)", others, othersBack).isEmpty(), "其余全局条目不应变化")
        assertTrue(
            missingOnly("角色0", orig.characters[0], back.characters[0]).isEmpty(),
            "角色块不应丢字段或改值：${missingOnly("角色0", orig.characters[0], back.characters[0]).take(5)}",
        )
    }

    // ==================================================================
    // 4. 文本格式（SystemSaveInBinary=false 的默认分支）
    // ==================================================================

    /**
     * 文本分支（`EraDataWriter` 复刻）写出的档必须能被**同一读路径**读回：
     * `__FINISHED` 数组收尾（EraDataWriter.cs:75/108）、`__EMUERA_1808_STRAT__` 扩展区
     * （:117）、`__EMU_SEPARATOR__` 分段（:126）、UTF-8 BOM（spec §1.4）。
     *
     * 校验方式：写 → 读回新变量池 → 关键字段相等；再写一次 → 字节稳定（确定性）。
     */
    @Test
    fun `文本格式写盘可被读回且二次写稳定`() {
        val io = MemorySaveIo()
        val s = store()
        s.addHero()
        s.declareUserVar(
            DimParser.parse(
                "#DIM", "SAVEDATA UI, 3", isPrivate = false, DimParseConfig(systemSaveInBinary = false),
            ),
        )
        s.writeInt("DAY", idx(0), 42)
        s.writeInt("MONEY", idx(3), 987654)
        s.writeStr("SAVESTR", idx(0), "保存文字𠮟")
        s.writeInt("CFLAG", idx(0, 5), 17)
        s.writeStr("CSTR", idx(0, 1), "角色文")
        s.writeInt("UI", idx(2), 7)

        val cfg = SaveConfig(systemSaveInBinary = false, zipSaveData = false, useSaveFolder = true)
        val fs = FileSystemOps(s, io, cfg)
        assertTrue(fs.saveData(2, "文本描述"))
        val first = io.files.getValue("sav/save02.sav")

        assertEquals(0xEF, first[0].toInt() and 0xFF, "文本档首字节应为 UTF-8 BOM（spec §1.4）")
        val text = String(first, 3, first.size - 3, Charsets.UTF_8)
        assertTrue(text.contains(SaveTextFormat.EMU_START), "应带 1808 扩展区标记")
        assertTrue(text.contains(SaveTextFormat.FINISHED), "数组应以 __FINISHED 收尾")
        assertTrue(text.contains(SaveTextFormat.SEPARATOR), "扩展段以 __EMU_SEPARATOR__ 分段")

        // 读回另一个池
        val dst = store()
        dst.addHero()
        dst.declareUserVar(
            DimParser.parse(
                "#DIM", "SAVEDATA UI, 3", isPrivate = false, DimParseConfig(systemSaveInBinary = false),
            ),
        )
        FileSystemOps(dst, io, cfg).loadData(2)
        assertEquals(42L, dst.readInt("DAY", idx(0)), "DAY")
        assertEquals(987654L, dst.readInt("MONEY", idx(3)), "MONEY")
        assertEquals("保存文字𠮟", dst.readStr("SAVESTR", idx(0)), "SAVESTR（增补平面字符）")
        assertEquals(17L, dst.readInt("CFLAG", idx(0, 5)), "CFLAG")
        assertEquals("角色文", dst.readStr("CSTR", idx(0, 1)), "CSTR")
        assertEquals(7L, dst.readInt("UI", idx(2)), "用户 #DIM SAVEDATA")
        assertEquals("文本描述", dst.lastLoadText, "SAVEDATA_TEXT")

        // 二次写：同状态必须逐字节相同（文本分支的确定性）
        assertTrue(FileSystemOps(dst, io, cfg).saveData(2, "文本描述"))
        assertTrue(first.contentEquals(io.files.getValue("sav/save02.sav")), "同状态二次写应逐字节相同")
    }

    // ==================================================================
    // 5. 落盘路径（SavDir 单候选）与角色档压缩标志
    // ==================================================================

    /**
     * `SavDir` 单候选：`UseSaveFolder=true` ⇒ 只写 `<root>/sav/`，根目录不留副本；
     * 写路径（[SaveConfig.normalPath]）与探测路径（[SaveDataProbe.candidatePaths]）同一字符串
     * （C# 写读删同源：VariableEvaluator.cs:2646 / :2689 / :2712 / :2144；Config.cs:471-481）。
     */
    @Test
    fun `落盘路径与探测路径同源且只在 sav`(@TempDir root: Path) {
        val store = store()
        val cfg = SaveConfig.ERATW
        val host = RootedBuiltinHost(root)
        for (no in listOf(0L, 3L, 79L)) {
            assertEquals(
                listOf(cfg.normalPath(no.toInt())),
                SaveDataProbe.candidatePaths(host, SaveFileKind.NORMAL, no, cfg.useSaveFolder),
                "探测候选必须等于落盘路径（no=$no）",
            )
        }
        assertEquals(listOf(cfg.globalPath()), SaveDataProbe.candidatePaths(host, SaveFileKind.GLOBAL, 0L, true))

        assertTrue(FileSystemOps.rooted(store, root, cfg).saveData(4, "desc"))
        assertTrue(Files.isRegularFile(root.resolve("sav/save04.sav")), "应落在 sav/（Config.cs:471-477）")
        assertTrue(!Files.exists(root.resolve("save04.sav")), "根目录不得留副本（原版靠迁移而非双写）")
    }

    /**
     * `SAVECHARA` 的容器压缩标志 = `SystemSaveInBinary && ZipSaveData`
     * （`EraBinaryDataWriter..ctor`，EraBinaryDataWriter.cs:22-32；SaveChara 用它，:2259）。
     *
     * 回归点：写侧曾经硬编码 `compressed = true`，在文本配置下会产出 GZip 魔数的角色档，
     * 与原版「文本配置 ⇒ 未压缩二进制角色档（MAGIC_PLAIN）」不符。
     */
    @Test
    fun `角色档压缩标志跟随配置`() {
        val configs = listOf(
            SaveConfig.ERATW, // binary + zip ⇒ GZip 壳
            SaveConfig(systemSaveInBinary = true, zipSaveData = false), // 二进制不压缩 ⇒ MAGIC_PLAIN
            SaveConfig(systemSaveInBinary = false, zipSaveData = true), // 文本配置下 ZipSaveData 不生效
        )
        for (cfg in configs) {
            val io = MemorySaveIo()
            val s = store()
            s.addHero()
            s.writeInt("CFLAG", idx(0, 0), 5)
            FileSystemOps(s, io, cfg).saveChara(listOf(0L), "T", "chara")
            val bytes = io.files.getValue("dat/chara_T.dat")
            var magic = 0L
            for (i in 0 until 8) magic = magic or ((bytes[i].toLong() and 0xFF) shl (8 * i))
            val expected = if (cfg.systemSaveInBinary && cfg.zipSaveData) SaveFormat.MAGIC_GZIP else SaveFormat.MAGIC_PLAIN
            assertEquals(expected, magic, "压缩标志应为 SystemSaveInBinary && ZipSaveData（cfg=$cfg）")

            // 两种壳都必须能被 LOADCHARA 读回（CreateReader 认两个魔数，EraBinaryDataReader.cs:786-830）
            val dst = store()
            FileSystemOps(dst, io, cfg).loadChara("T")
            assertEquals(1, dst.characters.count, "LOADCHARA 应追加 1 个角色（cfg=$cfg）")
            assertEquals(5L, dst.readInt("CFLAG", idx(0, 0)), "角色字段回读")
        }
    }

    // ==================================================================
    // 工具：真实变量池（CSV 注册表 + ERH 全局 #DIM）
    // ==================================================================

    /**
     * 建一个与 `GameSession` 前 4 步同源的变量池：CSV 数据 → 模板 → ERH 全局 `#DIM`。
     *
     * ERH 声明按**文本行**收集（`#DIM`/`#DIMS` 行），再交给
     * [SystemVariableBootstrap.install] 的不动点环 —— 与该类的生产用法一致
     * （`core/proc/GameSession.kt:207-212`）。这是写侧能覆盖 540 条真档条目的前提：
     * `SaveCapture` 只认已注册变量的 `isSavedata` 位，未注册的 477 条会被静默跳过。
     */
    private fun realStore(data: GameData): VariableStore {
        val store = VariableStore(data.registry)
        data.templates.forEach { store.registerTemplate(it) }
        store.loadItemPrice(data.itemPrices)
        store.reloadStrNames()
        val game = gameDir() ?: error("真实变量池需要 ERATW_GAMEDIR（调用方已 assume）")
        val inst = SystemVariableBootstrap.install(
            store,
            erhDeclarations(game),
            DimParseConfig(systemSaveInBinary = true),
        )
        assertTrue(inst.declared > 5000, "ERH 全局 #DIM 应注册 >5000 条（实测 7181），实际 ${inst.declared}")
        return store
    }

    /** 扫 `ERB` 子树下的 ERH，收集 `#DIM`/`#DIMS` 原文（行号取文件物理行，报告可定位）。 */
    private fun erhDeclarations(gameDir: Path): List<SystemVariableBootstrap.RawDeclaration> {
        val root = gameDir.resolve("ERB")
        val out = ArrayList<SystemVariableBootstrap.RawDeclaration>()
        if (!Files.isDirectory(root)) return out
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".erh", ignoreCase = true) }
                .sorted()
                .forEach { p ->
                    val bytes = runCatching { Files.readAllBytes(p) }.getOrNull() ?: return@forEach
                    // 与语料门同口径：前 8KB 有 NUL 视为二进制，不解析
                    if (bytes.indexOf(0.toByte()) in 0 until minOf(bytes.size, 8192)) return@forEach
                    val rel = root.relativize(p).toString().replace('\\', '/')
                    var no = 0
                    for (raw in String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").split('\n')) {
                        no++
                        val line = raw.trimStart('\uFEFF', ' ', '\t')
                        if (!line.startsWith("#") || line.length < 4) continue
                        val kw = line.substring(1).takeWhile { !it.isWhitespace() }.uppercase()
                        if (kw == "DIM" || kw == "DIMS") {
                            out.add(SystemVariableBootstrap.RawDeclaration(line, rel, no))
                        }
                    }
                }
        }
        return out
    }

    private companion object {
        /** 样本候选：gradle test 的 cwd 是模块目录 `core-vm/`，故上溯两级即仓库根。 */
        val SAMPLE_CANDIDATES = listOf(
            "../../.workbuddy/sav-backup/sav-20261018-110513",
            "../.workbuddy/sav-backup/sav-20261018-110513",
            ".workbuddy/sav-backup/sav-20261018-110513",
        )
    }
}
