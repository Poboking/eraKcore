package com.eratw.port.core.save

import com.eratw.port.core.data.save.SaveData
import com.eratw.port.core.data.save.SaveEntry
import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 真实存档（`sav/save00.sav`）的**逐字段回环审计** + EM 全局/静态容器的**三口径对拍**。
 *
 * ## 原版出处（本路逐条实读，行号可复现）
 *
 * 存盘侧（写序 = 段序）：`VariableEvaluator.SaveToStreamBinary`
 * （`MinorShift.Emuera.Runtime.Script.Statements.Variable/VariableEvaluator.cs:2591-2607`）
 * - `:2593` `WriteHeader()` → 容器头（`MinorShift.Emuera.Runtime.Utils/EraBinaryDataWriter.cs:35-51`：
 *   `:39` GZip 魔数 `743174590507926921uL`、`:43` 明文魔数 `727905341820519817uL`、
 *   `:45` 版本 `1808u`、`:46` 校验表条数 `0u`、`:47-50` 循环上界恒 0 ⇒ 表体恒不存在）；
 * - `:2594` `WriteFileType(Normal)`（1 字节）、`:2595` `WriteInt64(ScriptUniqueCode)`、
 *   `:2596` `WriteInt64(ScriptVersion)`、`:2597` `WriteString(saveDataText)`、
 *   `:2598` `WriteInt64(CharacterList.Count)` + `:2599-2602` 每角色 `SaveToStreamBinary`、
 *   `:2603` `SaveToStreamBinary`（全局条目）+ `:2604` `WriteEOF()`、
 *   `:2605` `SaveEMDataToStreamBinary`（扩展段）+ `:2606` `WriteEOF()`；
 * - 全局档同构但**没有角色数**：`SaveGlobal`（`:2474-2511`），文本行恒空串（`:2488`），
 *   扩展段走 `SaveGlobalEMDataToStreamBinary`（`VariableData.cs:3911-3934`）。
 *
 * 装载侧：`EraBinaryDataReader.CreateReader`（`EraBinaryDataReader.cs:786-830`，
 * `:790` 长度 <16 直接 null、`:796` 双魔数白名单、`:802-806` 校验表读出、
 * `:807-815` GZip 解压、`:820-824` 只认 1808）→ `LoadVariableBinary`
 * （`VariableData.cs:4081-4124`：`:4085-4092` 未知/禁用的变量名读掉再丢、
 * `:4099/4109/4119` 扩展段接受集 = `Save* || Global*`）。
 *
 * 复位侧（三口径的三个调用点）：
 * - `ResetData` → `RemoveEMSaveData`（`VariableEvaluator.cs:1336-1346` + `VariableData.cs:4006-4029`）；
 * - `ResetGlobalData` → `RemoveEMGlobalData` + `RemoveEMStaticData` + `SetDefaultGlobalValue`
 *   （`VariableEvaluator.cs:1348-1353` + `VariableData.cs:4031-4054 / :4056-4079`）；
 * - `LoadGlobal` 读前 `RemoveEMGlobalData`（`VariableEvaluator.cs:2523`）、
 *   `LoadFrom` 读前 `RemoveEMSaveData`（`:2696`）。
 *
 * ## 本路的三分类结论（断言/登记分别对应哪一类）
 *
 * ① **原样字节一致**：容器头 16 字节、GZip 外壳 10 字节头、整个 payload
 *   （[SaveFormat.encodePayload] 与源 payload 逐字节相等，见 [payload 与源文件逐字节一致]）。
 * ② **移植侧自造/简化**（逐项给原版行号 + 差异，只在 KDoc/报告里登记，不硬造实现）：
 *   - `SaveFormat.write` 的「内容未改动 ⇒ 原样回写源文件字节」分支
 *     （`SaveFormat.kt:214-226`）：原版**没有**这一分支，`SaveToStreamBinary`（`:2591-2607`）
 *     每次都重新序列化。存在理由 = Kotlin 的 `Deflater` 与 .NET `GZipStream` 不逐字节同构
 *     （`specs/save-format.md` §6.3 实测同一 payload 508 vs 510 字节），
 *     回环幂等只能靠回写。 ⇒ 本文件对**重压缩分支**单独跑一次回环（见
 *     [临时目录真文件回环逐字段一致]），证明「不靠回写也能语义还原」。
 *   - `EmSaveSets.parse` 每次返回全新实例 ⇒ 三张 `*DTs` 名单不会累积；
 *     原版 `loadGlobalVarExSetting` 只清 6 张表（`ConstantData.cs:1311-1316`，**三张 DTs 不清**），
 *     二次调用会累积。语料只调一次 ⇒ 读数相同（登记）。
 *   - `SaveCapture.systemEntries` 的筛选/顺序按 `registry.allInVarTokenDicOrder()`
 *     （= C# varTokenDic 的 Add 序，`VariableData.cs:3014-3179` ↔ `:3980-3997` 的枚举序；
 *     SAVE-12 修复前曾错用按存储形态分组的构造序）：
 *     档内 540 条全局条目中，移植侧**未注册**的用户 `#DIM` 变量（真档尾部有非 ASCII 名字）
 *     在重新抓取时会消失 ⇒ 只有「读进同一份注册表再抓回」才等价（见报告）。
 *   - `dimProduct` 的 32 位预乘上界保护（`SaveFormat.kt:300-305`）：原版由 .NET 直接按维度
 *     分配（`EraBinaryDataReader.cs:186/305/529/630`），无此检查。
 * ③ **无法判定**（`TODO(verify)`）：
 *   - `DT` 段重新快照后的 schema/data XML 是否与 .NET `WriteXmlSchema`/`WriteXml` 逐字节同构
 *     —— 本路只断言「读回的行数/存在性」，字节差异写进报告文件不钉死；
 *     卡点：语料两张 DT 都是**空表**（data 段恒 `<DocumentElement />`），无行可对照。
 *   - 文本格式（`SystemSaveInBinary=NO`）存档的真机样本缺失：语料 5 个 `.sav` 全是 GZip 二进制
 *     容器，[SaveTextFormat] 的逐字段回环**没有真实存档可对拍**，只有合成用例。
 *
 * ## 与 `n-stmtreset` 的接口约定
 *
 * [EmSaveSets.staticSets] 是本路新增的第三张名单（`STATIC_MAPS`/`STATIC_XMLS`/`STATIC_DTS`，
 * `ConstantData.cs:1388-1408` → `:150/153/156`）。它**不进** [EmSaveSets.loadAccept]
 * （`LoadVariableBinary` 只收 `Save* ∪ Global*`：`VariableData.cs:4099/4109/4119` 实读无 `Static*` 支），
 * 也**不参与抓取**（全仓没有 `SaveStaticEMDataToStreamBinary`）⇒ static 容器永不写档、读不回来。
 * 唯一消费者是 RESETGLOBAL 的清空步：`StatementExecutor` 的 RESETGLOBAL 分支补
 * `EmDataPools.clear(store, em.staticSets)`（对方路的接线）。
 */
class SaveRoundTripAuditTest {

    // ------------------------------------------------------------------
    // 装置：样本定位（**只读**，语料与仓内备份都不写）
    // ------------------------------------------------------------------

    /**
     * 样本根目录：语料 `ERATW_GAMEDIR/sav`（实机档）优先，
     * 回落仓内备份 `.workbuddy/sav-backup/sav-20261018-110513`（`SaveFormatTest` 同一批样本）。
     * 两处都拿不到 ⇒ 相关用例 assume-skip（不伪造读数）。
     */
    private fun backupDir(): Path? = sequenceOf(
        Path.of("../../.workbuddy/sav-backup/sav-20261018-110513"),
        Path.of(".workbuddy/sav-backup/sav-20261018-110513"),
        Path.of("../.workbuddy/sav-backup/sav-20261018-110513"),
    ).firstOrNull { Files.isDirectory(it) }

    /**
     * 单个样本：语料 `sav` 目录里有就用语料的（`save00.sav` 与仓内备份**同一份字节**，
     * sha256 前缀 27883f1663969468 实测一致），没有（如 `global.sav` 语料侧不存在）回落备份。
     */
    private fun sample(name: String): Path? {
        val gamedir = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        if (gamedir != null) {
            val p = Path.of(gamedir, "sav", name)
            if (Files.isRegularFile(p)) return p
        }
        return backupDir()?.resolve(name)?.takeIf { Files.isRegularFile(it) }
    }

    /** 语料 CSV 目录（[VarExt 名单只有两条 SAVE_DTS] 用）；拿不到则 null。 */
    private fun corpusCsvDir(): Path? {
        for (key in listOf("ERATW_GAMEDIR", "ERATW_CORPUS")) {
            val v = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val root = Path.of(v)
            val direct = root.resolve("CSV")
            if (Files.isDirectory(direct)) return direct
            val parent = root.parent?.resolve("CSV")
            if (parent != null && Files.isDirectory(parent)) return parent
        }
        return null
    }

    private class MemorySaveIo : SaveFileIo {
        val files = LinkedHashMap<String, ByteArray>()
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun read(path: String): ByteArray? = files[path]?.copyOf()

        override fun write(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        override fun delete(path: String): Boolean = files.remove(path) != null
    }

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    private fun ctx(store: VariableStore, clock: Long = 1_700_000_000_000L): BuiltinEvalContext {
        val base = BaseCtx()
        base.variables = store
        val host = object : BuiltinHost {
            override fun clockMillis(): Long = clock
        }
        return BuiltinEvalContext(base, store, host)
    }

    private fun int(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
        (c.callFunction(name, a.toList()) as Value.IntV).v

    private fun str(c: BuiltinEvalContext, name: String, vararg a: Value?): String =
        (c.callFunction(name, a.toList()) as Value.StrV).v

    private fun s(v: String): Value = Value.StrV(v)

    /** 真档的 `ScriptUniqueCode` = 7153（实测），注册表必须同码才能过 `CHKDATA`/`LOADDATA` 头探测。 */
    private val realCode = 7153L

    private fun realStore(): VariableStore =
        VariableStore(VariableRegistry(gameBase = GameBaseData(gameCode = realCode, version = 0L)))

    /** eratw 实机口径（`emuera.config` 的 51/60/74 行：二进制 + 压缩 + `sav` 子目录）。 */
    private fun eratwConfig(): SaveConfig = SaveConfig.ERATW

    // ------------------------------------------------------------------
    // 1. 容器头（①类：原样字节）
    // ------------------------------------------------------------------

    /**
     * 容器头 16 字节逐字段对拍 `EraBinaryDataWriter.WriteHeader`（`:35-51`）。
     * 实测量（`sav/save00.sav`）：60151 字节文件、GZip 魔数、版本 1808、校验表条数 0、
     * GZip 外壳头 `1f 8b 08 00 00 00 00 00 00 0a`（.NET 8 的 OS 字节 0x0A）。
     */
    @Test
    fun `容器头三字段与原版写侧常量对拍`() {
        val f = sample("save00.sav")
        assumeTrue(f != null, "跳过：找不到真实存档样本（ERATW_GAMEDIR/sav 与仓内备份都不在位）")
        val bytes = Files.readAllBytes(f!!)
        assertEquals(60151L, bytes.size.toLong(), "save00.sav 文件长度实测")

        var magic = 0L
        for (i in 0 until 8) magic = magic or ((bytes[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(SaveFormat.MAGIC_GZIP, magic, "EraBinaryDataWriter.cs:39 的 GZip 分支魔数")
        assertEquals(SaveFormat.MAGIC_PLAIN, 727905341820519817L, "EraBinaryDataWriter.cs:43 的明文分支魔数")
        assertEquals(SaveFormat.VERSION.toLong(), u32(bytes, 8), "EraBinaryDataWriter.cs:45 的版本 1808")
        assertEquals(0L, u32(bytes, 12), "EraBinaryDataWriter.cs:46 的校验表条数恒 0（:47-50 的循环上界也是 0）")

        val shell = bytes.copyOfRange(16, 26)
        assertEquals(
            "1f8b080000000000000a",
            shell.joinToString("") { "%02x".format(it) },
            "GZip 外壳头：MTIME=0 / XFL=0 / OS=0x0A（实测，CreateReader 的解压入口 EraBinaryDataReader.cs:807-815）",
        )
        assertTrue(SaveCodec.isBinary(bytes), "魔数即判二进制（SaveCodec.kt:18-23 ↔ EraBinaryDataReader.cs:796）")
    }

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    // ------------------------------------------------------------------
    // 2. payload 头部字段（①类）
    // ------------------------------------------------------------------

    /**
     * `SaveToStreamBinary` 的头部四字段 + 角色数（`VariableEvaluator.cs:2594-2598`）。
     * 实测：fileType=0（Normal）、UniqueCode=7153、ScriptVersion=0、描述 94 字符（UTF-16LE 188 字节）、
     * 角色数 168 ⇒ 头部共 207 字节（1 类别 + 8 + 8 + 1 长度前缀 + 188 + 8 角色数）。
     */
    @Test
    fun `payload 头部字段与写出序逐一对拍`() {
        val d = readSample("save00.sav") ?: return
        assertEquals(SaveFileKind.NORMAL, d.fileType, ":2594 WriteFileType(Normal)")
        assertEquals(realCode, d.uniqueCode, ":2595 ScriptUniqueCode 实测 7153")
        assertEquals(0L, d.scriptVersion, ":2596 ScriptVersion 实测 0（游戏侧未定义）")
        assertEquals(94, d.text.length, "描述文本字符数实测 94（UTF-16LE ⇒ 188 字节）")
        // 组长改钉：真档实测的分隔符是 **斜杠**（`2026/05/18 14:22:54 `，逐码位 002F），
        // 不是 worker 写的连字符 —— 原版时间戳由 `Process.cs` 按 `yyyy/MM/dd HH:mm:ss` 拼串。
        assertTrue(d.text.startsWith("2026/05/18 14:22:54 "),
            ":2597 时间戳头（Process.cs 拼串，实测分隔符 002F 斜杠）：${d.text}",
        )
        assertTrue(d.text.contains("第  1日(春之月1日)"), "天数行（两个空格实测量）：${d.text}")
        // 组长改钉：真档这一格是**简体「莲」**（U+83B2），worker 写成繁体蓮（U+84EE）。
        assertTrue(d.text.contains("命莲寺"), "角色名槽：${d.text}")
        assertTrue(
            d.text.endsWith("Ver 4.980 - 日语交流用画蛇添足版CH20260326 "),
            "版本尾巴含尾空格（实测）：${d.text}",
        )
        assertEquals(168, d.characters.size, ":2598 CharacterList.Count 实测 168")
        assertEquals(540, d.variables.size, "全局条目数实测 540（:2603 SaveToStreamBinary）")
        assertEquals(2, d.emData.size, "扩展段条目数实测 2（:2605 SaveEMDataToStreamBinary）")
        assertEquals(571185L, SaveFormat.encodePayload(d).size.toLong(), "payload 实测长度 571185")
    }

    // ------------------------------------------------------------------
    // 3. 段内值（①类：变长整数/游程压缩对真数据成立）
    // ------------------------------------------------------------------

    /**
     * 真档的 1D 整型数组逐元素读回：`DAY`（`Int1D`，100 元素，实测非零位 0/2/3 = 1/1/1）、
     * `RANDDATA`（`Int1D`，**625 元素全 0**）。
     *
     * `RANDDATA` 出现在普通档全局段是 `specs/save-format.md` §6.4 的实测勘误
     * （原版 `SaveToStreamBinary` 只排 `IsGlobal`，`VariableData.cs:3980-3997`）；
     * 全 0 数组靠 `0xF0 + 游程` 压成 1 个游程（`EraBinaryDataWriter.cs:203-223`），
     * 是「零游程编解码」的最佳真实回归点。
     */
    @Test
    fun `真档 1D 整型数组逐元素读回`() {
        val d = readSample("save00.sav") ?: return
        val day = d.variables.filterIsInstance<SaveEntry.IntArray1>().firstOrNull { it.key == "DAY" }
        assertTrue(day != null, "真档全局段应含 DAY")
        assertEquals(100, day!!.values.size, "DAY 维度实测 100")
        assertEquals(1L, day.values[0], "DAY[0] 实测 1")
        assertEquals(0L, day.values[1], "DAY[1] 实测 0（零游程后的下一段）")
        assertEquals(1L, day.values[2], "DAY[2] 实测 1")
        assertEquals(1L, day.values[3], "DAY[3] 实测 1")
        assertEquals(3L, day.values.count { it != 0L }.toLong(), "DAY 只有 3 个非零（实测）")

        val rand = d.variables.filterIsInstance<SaveEntry.IntArray1>().firstOrNull { it.key == "RANDDATA" }
        assertTrue(rand != null, "RANDDATA 确实在普通档（§6.4 勘误）")
        assertEquals(625, rand!!.values.size, "RANDDATA 元素数实测 625")
        assertEquals(0L, rand.values.count { it != 0L }.toLong(), "RANDDATA 全 0（实测 ⇒ 单条零游程编码）")

        // 全局段里不该混进扩展段条目类型（两段各自 EOF：VariableEvaluator.cs:2604 / :2606）
        assertTrue(
            d.variables.none {
                it is SaveEntry.MapValue || it is SaveEntry.XmlValue || it is SaveEntry.DataTableValue
            },
            "Map/Xml/DT 只出现在 EOF 之后的扩展段",
        )
        // 每个角色块以 EOC 收尾 ⇒ 分隔符（类型字节 253）实测总数 168（168 块各一）
        assertEquals(
            168L,
            d.characters.sumOf { b -> b.count { it === SaveEntry.Separator } }.toLong(),
            "角色块内分隔符总数实测 168（CharacterData.cs:545-555 的系统/用户变量分界）",
        )
        assertEquals(15626L, d.characters.sumOf { it.size }.toLong(), "角色条目总数实测 15626（含 168 个分隔符）")
    }

    // ------------------------------------------------------------------
    // 4. 回环：编码侧逐字节 + 原样回写分支（①/②）
    // ------------------------------------------------------------------

    /**
     * **①类的核心判据**：`encodePayload(read(save00)) == 源 payload`（逐字节）。
     * 这一条与容器/压缩无关，只判「读出来的模型 + 写回去的字节 = 原文件正文」，
     * 即 `VariableEvaluator.cs:2591-2607` 的序列化布局在移植侧同构。
     */
    @Test
    fun `payload 与源文件逐字节一致`() {
        val d = readSample("save00.sav") ?: return
        val bytes = Files.readAllBytes(sample("save00.sav")!!)
        val src = SaveFormat.sourcePayloadOf(bytes)
        assertTrue(src != null, "源 payload 解不出来")
        val back = SaveFormat.encodePayload(d)
        val diff = firstDiff(back, src!!)
        assertTrue(diff < 0, "第 $diff 字节起分叉（期望 src=${src.size} 实得 ${back.size}）")
        assertEquals(src.size.toLong(), back.size.toLong(), "payload 长度")
    }

    /**
     * **②类登记**：`SaveFormat.write` 对未改动的存档**原样回写源字节**
     * （`SaveFormat.kt:214-226`），原版没有这一分支。
     * 本用例钉住「回写 = 源文件」这一事实，并同时证明 payload 分支与回写分支同源
     * （⇒ 幂等读数不是「Kotlin 压缩器与 .NET 同构」的证据，见 §6.3）。
     */
    @Test
    fun `write 对未改动存档原样回写是移植侧自造分支`() {
        val f = sample("save00.sav")
        if (f == null) {
            assumeTrue(false, "跳过：找不到真实存档样本")
            return
        }
        val bytes = Files.readAllBytes(f)
        val d = SaveFormat.read(bytes)
        assertTrue(SaveFormat.write(d).contentEquals(bytes), "回写分支：write(read(f)) == f")
        // 自造分支的判据：把同一模型重建成「无源文件」的存档后，容器字节走真实压缩路径 ⇒ 长度可变
        val rebuilt = withoutSource(d)
        val srcPayload = SaveFormat.sourcePayloadOf(bytes)
        assertTrue(srcPayload != null, "源 payload 解不出来")
        assertTrue(
            SaveFormat.encodePayload(rebuilt).contentEquals(srcPayload!!),
            "重建只影响外壳，不影响 payload",
        )
    }

    // ------------------------------------------------------------------
    // 5. 回环：写出到临时目录 → 再读（真文件 + 真压缩）
    // ------------------------------------------------------------------

    /**
     * 「读 → 写出到**临时目录** → 再读」的完整回环，走移植侧自己的 GZip 分支
     * （[withoutSource] 清掉回写依据，逼出 `SaveFormat.kt:220-225` 的真实压缩路径）。
     *
     * 逐字段对拍：容器头三字段、类别/游戏码/版本/描述、角色数、540 条全局条目、2 条扩展段条目
     * 全部按 [sameEntry] 深比较（数组比内容，`LongArray`/`Array<String?>` 不能 `==`）。
     * 落盘用 [RootedSaveFileIo]（生产实现）写 `sav/save00.sav` 到临时目录。
     */
    @Test
    fun `临时目录真文件回环逐字段一致`(@TempDir tmp: Path) {
        val src = sample("save00.sav")
        if (src == null) {
            assumeTrue(false, "跳过：找不到真实存档样本")
            return
        }
        val bytes = Files.readAllBytes(src)
        val a = SaveFormat.read(bytes)
        val out = RootedSaveFileIo(tmp, null)
        assertTrue(out.write("sav/save00.sav", SaveFormat.write(withoutSource(a))), "落临时目录失败")

        val onDisk = out.read("sav/save00.sav")
        assertTrue(onDisk != null, "再读失败")
        val head = onDisk!!
        assertEquals(SaveFormat.MAGIC_GZIP, le64(head, 0), "重压缩后仍是 GZip 魔数（EraBinaryDataWriter.cs:39）")
        assertEquals(SaveFormat.VERSION.toLong(), u32(head, 8), "版本 1808（:45）")
        assertEquals(0L, u32(head, 12), "校验表条数恒 0（:46）")

        val b = SaveFormat.read(head)
        assertEquals(a.fileType, b.fileType, "类别")
        assertEquals(a.uniqueCode, b.uniqueCode, "游戏码")
        assertEquals(a.scriptVersion, b.scriptVersion, "脚本版本")
        assertEquals(a.text, b.text, "描述文本")
        assertEquals(a.compressed, b.compressed, "压缩标志")
        assertEquals(a.version, b.version, "容器版本")
        assertEquals(a.characters.size, b.characters.size, "角色块数")
        assertEquals(a.variables.size, b.variables.size, "全局条目数")
        assertEquals(a.emData.size, b.emData.size, "扩展段条目数")
        for (i in a.variables.indices) {
            assertTrue(sameEntry(a.variables[i], b.variables[i]), "全局条目第 $i 条（${a.variables[i].key}）不一致")
        }
        for (i in a.emData.indices) {
            assertTrue(sameEntry(a.emData[i], b.emData[i]), "扩展段条目第 $i 条（${a.emData[i].key}）不一致")
        }
        for (bi in a.characters.indices) {
            val ka = a.characters[bi]
            val kb = b.characters[bi]
            assertEquals(ka.size, kb.size, "角色块 $bi 的条目数")
            for (i in ka.indices) {
                assertTrue(sameEntry(ka[i], kb[i]), "角色块 $bi 第 $i 条（${ka[i].key}）不一致")
            }
        }
        // 语义等价的最终判据：重编码后与源 payload 逐字节相等（外壳不同不影响正文）
        val srcPayload = SaveFormat.sourcePayloadOf(bytes)
        assertTrue(srcPayload != null, "源 payload 解不出来")
        assertTrue(
            SaveFormat.encodePayload(b).contentEquals(srcPayload!!),
            "再读再编码应还原出与源正文相同的 payload",
        )
    }

    /**
     * 扩展段（DT）在真档里的实测形态：两张表名 = 语料 `CSV/VarExtGameData.csv` 的
     * `SAVE_DTS` 两条（`:37`/`:38`），条目类型都是 `DataTableValue`，
     * data 段恒 `<DocumentElement />`（**空表、0 行**）。
     */
    @Test
    fun `真档扩展段只有两张 DT 且都是空表`() {
        val d = readSample("save00.sav") ?: return
        assertEquals(listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"), d.emData.map { it.key }, "档内扩展段名字与顺序")
        assertTrue(d.emData.all { it is SaveEntry.DataTableValue }, "两条都是 DT（类型字节 34）")
        for (e in d.emData.filterIsInstance<SaveEntry.DataTableValue>()) {
            assertTrue(e.schemaXml.contains("DT_UFUFU"), "schema 含表名：${e.schemaXml.take(40)}")
            assertEquals("<DocumentElement />", e.dataXml, "data 段实测为空表")
        }
    }

    // ------------------------------------------------------------------
    // 6. 经 FileSystemOps 的语义回环（真档 → store → 再写档 → 再读）
    // ------------------------------------------------------------------

    /**
     * 真档经 [FileSystemOps.loadData] 进池、再经 [SaveCapture.normal] 抓回、再读进**第二个池**：
     * - 头探测通过（`SaveDataProbe.checkBytes` ↔ `VariableEvaluator.cs:2159-2217`）；
     * - `LASTLOAD_*` 与描述文本按 `:2627-2629` 落地；
     * - `DAY[0]` 等真值写进池（`LoadVariableBinary` 的整型支，`VariableData.cs:4125+`）；
     * - 扩展段按 `Save* ∪ Global*` 接受集还原（`:4099`），重新抓档时两张 DT 又写回去。
     */
    @Test
    fun `真档经语句层往返`() {
        val src = sample("save00.sav")
        if (src == null) {
            assumeTrue(false, "跳过：找不到真实存档样本")
            return
        }
        val sets = EmSaveSets(save = EmDataNames(dts = listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG")))

        val io = MemorySaveIo()
        io.files["sav/save00.sav"] = Files.readAllBytes(src)
        val s1 = realStore()
        FileSystemOps(s1, io, eratwConfig(), sets).loadData(0)

        assertEquals(0L, s1.lastLoadNo, "LASTLOAD_NO = dataIndex（VariableEvaluator.cs:2706）")
        val text = SaveFormat.read(io.files.getValue("sav/save00.sav")).text
        assertEquals(text, s1.lastLoadText, "LASTLOAD_TEXT（:2627-2629）")
        assertEquals(1L, s1.readInt("DAY", longArrayOf(0L)), "真档 DAY[0] = 1 已进池")
        assertEquals(0L, s1.readInt("RANDDATA", longArrayOf(0L)), "真档 RANDDATA[0] = 0")
        assertEquals(168, s1.characters.count, "角色块 168 个（:2598）")

        val c = ctx(s1)
        assertEquals(1L, int(c, "DT_EXIST", s("DT_UFUFU_LOG")), "扩展段还原：DT 表存在（VariableData.cs:4119-4121）")
        assertEquals(0L, int(c, "DT_ROW_LENGTH", s("DT_UFUFU_LOG")), "data 段是空表 ⇒ 0 行")
        // 列数不做断言：语料 schema 的类型元素由 .NET WriteXmlSchema 生成，
        // 移植侧重新快照的 schema 文本尚未证明逐字节同构（见类 KDoc 的 ③ TODO(verify)），只记录。
        report("DT_UFUFU_LOG 还原后列数（仅记录）= ${int(c, "DT_COLUMN_LENGTH", s("DT_UFUFU_LOG"))}")

        // 抓回 → 落临时文件式 io → 再读进第二个池
        assertTrue(FileSystemOps(s1, io, eratwConfig(), sets).saveData(1, "audit"), "重抓档失败")
        val again = SaveFormat.read(io.files.getValue("sav/save01.sav"))
        assertEquals(listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"), again.emData.map { it.key }, "重抓的扩展段还是两张表")
        assertTrue(again.variables.any { it.key == "DAY" }, "重抓的全局段应含系统变量 DAY")
        assertTrue(again.variables.any { it.key == "RANDDATA" }, "§6.4：RANDDATA 必须在普通档全局段")

        val s2 = realStore()
        FileSystemOps(s2, io, eratwConfig(), sets).loadData(1)
        val c2 = ctx(s2)
        assertEquals(1L, int(c2, "DT_EXIST", s("DT_UFUFU_M_LOG")), "第二次还原成功")
        assertEquals(1L, s2.readInt("DAY", longArrayOf(0L)), "重抓档再读回，DAY[0] 仍是 1")
        assertEquals(168, s2.characters.count, "角色块数量守恒")
        assertEquals("audit", s2.lastLoadText, "重抓档的描述文本 = saveData 的实参（VariableEvaluator.cs:2597）")
    }

    // ------------------------------------------------------------------
    // 7. CHKDATA 的头探测（真档）
    // ------------------------------------------------------------------

    /**
     * `CheckDataByFilename`（`VariableEvaluator.cs:2159-2217`）的四分支在真档上的读数：
     * 同码同类别 ⇒ OK + 描述；类别不符 ⇒ ETC_ERROR；游戏码不符 ⇒ GAME_ERROR。
     * 版本判定走 `GameBase.cs:69-80` 的「未定义 + 存档版本 != 1000 ⇒ 通过」一支（实测 ScriptVersion=0）。
     */
    @Test
    fun `真档头探测四分支读数`() {
        val src = sample("save00.sav")
        if (src == null) {
            assumeTrue(false, "跳过：找不到真实存档样本")
            return
        }
        val bytes = Files.readAllBytes(src)
        val gb = GameBaseData(gameCode = realCode, version = 0L)

        val ok = SaveDataProbe.checkBytes(bytes, SaveFileKind.NORMAL, gb)
        assertEquals(EraDataState.OK, ok.state, "CHKDATA 对真档应判 OK")
        assertEquals(SaveFormat.read(bytes).text, ok.mes, "OK 分支返回描述文本（:2216-2217）")

        assertEquals(
            EraDataState.ETC_ERROR,
            SaveDataProbe.checkBytes(bytes, SaveFileKind.GLOBAL, gb).state,
            "类别不符（:2196-2201）",
        )
        assertEquals(
            EraDataState.GAME_ERROR,
            SaveDataProbe.checkBytes(bytes, SaveFileKind.NORMAL, GameBaseData(gameCode = 1L)).state,
            "游戏码不符（:2203-2207）",
        )
        // 组长改钉（worker 把豁免判据的两侧写反了）：原版 `UniqueCodeEqualTo(long target)`
        // （`GameBase.cs:60-67`）里 `target == 0 ⇒ true` 的 `target` 就是调用点
        // `VariableEvaluator.cs:2203` 传进去的 `eraBinaryDataReader.ReadInt64()`
        // ⇒ 豁免的是**存档文件里的码**为 0，不是游戏侧的码为 0。
        // 所以「游戏码 0、存档码 7153」必须照旧判 GAME_ERROR（下条），
        // 而把存档码改写成 0 才走豁免 ⇒ OK（末条，非空转：把判据写反即红）。
        val gbZeroSide = GameBaseData(gameCode = 0L)
        assertEquals(
            EraDataState.GAME_ERROR,
            SaveDataProbe.checkBytes(bytes, SaveFileKind.NORMAL, gbZeroSide).state,
            "豁免不看游戏侧码（`GameBase.cs:60-67` 的 target = 存档读出的码）",
        )
        val zeroCodeSave = SaveFormat.read(bytes).let { d ->
            SaveData(
                fileType = d.fileType,
                uniqueCode = 0L,
                scriptVersion = d.scriptVersion,
                text = d.text,
                characters = d.characters,
                variables = d.variables,
                emData = d.emData,
                compressed = d.compressed,
                version = d.version,
            )
        }
        assertEquals(
            EraDataState.OK,
            SaveDataProbe.checkBytes(
                SaveFormat.write(withoutSource(zeroCodeSave)), SaveFileKind.NORMAL, gb,
            ).state,
            "存档码 0 ⇒ 豁免（`GameBase.cs:60-67`）",
        )
    }

    /** `global.sav`（若有）：类别 1、无角色数、描述恒空串、扩展段为空（语料没有 `GLOBAL_*` 生效行）。 */
    @Test
    fun `全局档没有角色块且扩展段为空`() {
        val d = readSample("global.sav") ?: return
        assertEquals(SaveFileKind.GLOBAL, d.fileType, "WriteFileType(Global)（VariableEvaluator.cs:2485）")
        assertEquals("", d.text, ":2488 WriteString(\"\")")
        assertTrue(d.characters.isEmpty(), "全局档不写角色数（:2483-2493 无 :2598 那一步）")
        assertTrue(d.emData.isEmpty(), "语料 GLOBAL_* 三条指令全是注释行 ⇒ 扩展段空")
        assertEquals(25, d.variables.size, "global.sav 全局条目数实测 25")
        assertEquals(listOf("GLOBAL", "GLOBALS"), d.variables.take(2).map { it.key }, "前两条实测")
    }

    // ------------------------------------------------------------------
    // 8. EM 名单：语料读数 + 三口径
    // ------------------------------------------------------------------

    /**
     * 语料 `CSV/VarExtGameData.csv` 的实测读数：**38 行里只有 2 行生效**
     * （`:37 SAVE_DTS, DT_UFUFU_LOG`、`:38 SAVE_DTS, DT_UFUFU_M_LOG`），
     * `SAVE_XMLS`（`:12`）、`SAVE_MAPS`（`:13`）、`GLOBAL_DTS`（`:28`）、`STATIC_DTS`（`:35`）
     * 全是 `;` 注释行 ⇒ `GLOBAL_*`/`STATIC_*` 六张名单在本语料下是**空集**。
     *
     * ⇒ 闸门安全证据：本路新增的 `staticSets` 与大小写口径**不会**改变真语料上的任何读数
     * （扩展段仍是那两张 DT，`loadAccept` 不变，回环与 RESETGLOBAL 读数不变）。
     */
    @Test
    fun `VarExt 名单只有两条 SAVE_DTS`() {
        val maybe = corpusCsvDir()
        assumeTrue(maybe != null, "跳过：未设置 ERATW_GAMEDIR/ERATW_CORPUS 或找不到 CSV 目录")
        val dir = maybe!!
        val csv = dir.resolve("VarExtGameData.csv")
        assumeTrue(Files.isRegularFile(csv), "跳过：缺少 $csv")

        // 实测：该文件是 LF 换行、无尾换行、UTF-8 BOM；按 '\n' 切出 38 段
        val raw = String(Files.readAllBytes(csv), Charsets.UTF_8)
        val lines = raw.trimStart(Char(0xFEFF)).split('\n').map { it.trimEnd('\r') }
        assertEquals(38, lines.size, "按 LF 切分的段数实测 38（37 个 LF、文件末尾无换行）")
        val trimmed = lines.map { it.trim() }
        assertEquals(34, trimmed.count { it.isNotEmpty() }, "非空行实测 34")
        assertEquals(4, trimmed.count { it.isEmpty() }, "空行实测 4")
        assertEquals(32, trimmed.count { it.startsWith(";") }, "分号注释行实测 32")
        val enabled = trimmed.filter { it.isNotEmpty() && !it.startsWith(";") }
        assertEquals(listOf("SAVE_DTS, DT_UFUFU_LOG", "SAVE_DTS, DT_UFUFU_M_LOG"), enabled, "生效行实测原文（仅 2 条）")

        val p = EmSaveSets.parse(dir)
        assertEquals(listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"), p.save.dts, "SAVE_DTS 两条")
        assertTrue(p.save.maps.isEmpty() && p.save.xmls.isEmpty(), "SAVE_MAPS/SAVE_XMLS 全是注释行")
        assertTrue(p.global.isEmpty, "GLOBAL_* 三张名单为空（语料 :23-:28 全注释）")
        assertTrue(p.staticSets.isEmpty, "STATIC_* 三张名单为空（语料 :31-:35 全注释）")
        assertEquals(listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"), p.loadAccept.dts, "接受集 = Save* ∪ Global*（VariableData.cs:4099）")
    }

    /**
     * **三口径的本体判据**：`STATIC_MAPS`/`STATIC_XMLS`/`STATIC_DTS` 进 [EmSaveSets.staticSets]
     * （`ConstantData.cs:1388-1408`），但**不进** [EmSaveSets.loadAccept]
     * （`LoadVariableBinary` 的三支只判 `Save* || Global*`：`VariableData.cs:4099/4109/4119`），
     * 也不进 `save`/`global` ⇒ static 容器永不写档、读不回来，只被 RESETGLOBAL 清
     * （`VariableData.cs:4056-4079` ← `VariableEvaluator.cs:1351`）。
     */
    @Test
    fun `STATIC 三表只进复位口径`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val csv = root.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(
            csv.resolve("VarExtStatic.csv"),
            listOf(
                "SAVE_DTS, SD1",
                "GLOBAL_DTS, GD1",
                "STATIC_MAPS, SM1 , SM2",
                "STATIC_XMLS, SX1",
                "STATIC_DTS, SDT1",
            ).joinToString("\r\n").toByteArray(Charsets.UTF_8),
        )
        val p = EmSaveSets.parse(csv)
        assertEquals(listOf("SM1", "SM2"), p.staticSets.maps, "后段 Trim（ConstantData.cs:1399）")
        assertEquals(listOf("SX1"), p.staticSets.xmls)
        assertEquals(listOf("SDT1"), p.staticSets.dts)
        assertTrue(p.staticSets.maps.all { it !in p.save.maps }, "static 不混进 save")
        assertEquals(listOf("SD1", "GD1"), p.loadAccept.dts, "接受集里没有 STATIC_DTS（:4099/4109/4119）")
        assertTrue(p.loadAccept.maps.isEmpty(), "接受集里没有 STATIC_MAPS")
        assertTrue(p.loadAccept.xmls.isEmpty(), "接受集里没有 STATIC_XMLS")
        assertNotEquals(p.staticSets, p.loadAccept, "两口径必须可区分")
        assertTrue(EmSaveSets.EMPTY.staticSets.isEmpty, "缺省 = 空（ConstantData.cs:1315-1316 的清空形态）")
    }

    /**
     * 指令名的大小写口径：`array[0].Equals(指令名, Config.StringComparison)`（`ConstantData.cs:1388` 等九处），
     * `StringComparison` 由 `IgnoreCase` 决定（`Config.cs:299-309`），而 `IgnoreCase` **出厂默认 true**
     * （`ConfigData.cs:45` 的 `t: true`）且本语料三层配置都没有该键 ⇒ 生效口径是忽略大小写。
     */
    @Test
    fun `指令名默认忽略大小写`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val csv = root.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(
            csv.resolve("VarExtCase.csv"),
            listOf("save_dts, LOWER1", "Static_Maps, LOWER2", "SAVE_XMLS, X1").joinToString("\r\n").toByteArray(),
        )
        val loose = EmSaveSets.parse(csv, ignoreCase = true)
        assertEquals(listOf("LOWER1"), loose.save.dts, "出厂默认（IgnoreCase=YES）认小写指令名")
        assertEquals(listOf("LOWER2"), loose.staticSets.maps)
        assertEquals(listOf("X1"), loose.save.xmls)

        val strict = EmSaveSets.parse(csv, ignoreCase = false)
        assertTrue(strict.save.dts.isEmpty() && strict.staticSets.maps.isEmpty(), "IgnoreCase=NO ⇒ 只认大写")
        assertEquals(listOf("X1"), strict.save.xmls, "精确匹配仍认原本的大写行")
    }

    /**
     * static 名字的**清空**口径（RESETGLOBAL 将复用的同一实现）：
     * `RemoveEMStaticData`（`VariableData.cs:4056-4079`）与 `RemoveEMSaveData`（`:4006-4029`）
     * 逐行同型 —— MAP 只 `Clear()`（`:4062`）、XML 走 `Remove`（`:4069`）、DT 只 `Clear()`（`:4076`）。
     *
     * 造法：先把名字表整体交给写档（⇒ 档内没有扩展段），读档时把 **`staticSets` 作为本次参与清空的
     * 名字集**传入（[FileSystemOps.loadData] 的清空实参即 `emSets.save`，见其 :78）。
     * 这条用例同时钉住本路的接口约定：名字来源是 `STATIC_*` 解析结果。
     */
    @Test
    fun `static 名字的清空口径与 save 同型`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val csv = root.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(
            csv.resolve("VarExtReset.csv"),
            listOf("STATIC_MAPS, ST_M", "STATIC_XMLS, ST_X", "STATIC_DTS, ST_D").joinToString("\r\n").toByteArray(),
        )
        val sets = EmSaveSets.parse(csv)
        assertTrue(sets.loadAccept.isEmpty, "static 名字不进接受集（VariableData.cs:4099）")

        val io = MemorySaveIo()
        val s = realStore()
        val c = ctx(s)
        // 写档用空名字表 ⇒ 档内不含扩展段，读档只会执行「清空」，读数不被还原掩盖
        assertTrue(FileSystemOps(s, io, SaveConfig(systemSaveInBinary = true), EmSaveSets.EMPTY).saveData(2, "no-em"))

        assertEquals(1L, int(c, "MAP_CREATE", s("ST_M")))
        int(c, "MAP_SET", s("ST_M"), s("K"), s("V"))
        assertEquals(1L, int(c, "XML_DOCUMENT", s("ST_X"), s("<r><a>1</a></r>")))
        assertEquals(1L, int(c, "DT_CREATE", s("ST_D")))
        assertEquals(1L, int(c, "DT_COLUMN_ADD", s("ST_D"), s("NAME")))
        int(c, "DT_ROW_ADD", s("ST_D"), s("NAME"), s("R1"))

        FileSystemOps(s, io, SaveConfig(systemSaveInBinary = true), EmSaveSets(save = sets.staticSets)).loadData(2)

        assertEquals(1L, int(c, "MAP_EXIST", s("ST_M")), "MAP 只 Clear()（:4062），表本身留着")
        assertEquals(0L, int(c, "MAP_SIZE", s("ST_M")))
        assertEquals(0L, int(c, "XML_EXIST", s("ST_X")), "XML 是 Remove（:4069）")
        assertEquals(1L, int(c, "DT_EXIST", s("ST_D")), "DT 只 Clear()（:4076）")
        assertEquals(0L, int(c, "DT_ROW_LENGTH", s("ST_D")), "行清空")
        assertEquals(2L, int(c, "DT_COLUMN_LENGTH", s("ST_D")), "列定义不受影响（id + NAME）")

        // 接受集为空 ⇒ 即便档里有同名条目也不会被还原（static 永不读回来）。这里用 GLOBAL 名反证：
        // GLOBAL_MAPS 进接受集（:4099），STATIC_MAPS 不进。
        val g = EmSaveSets(global = EmDataNames(maps = listOf("ST_M")))
        assertTrue(!sets.loadAccept.maps.contains("ST_M"), "static 名不在装载接受集")
        assertTrue(g.loadAccept.maps.contains("ST_M"), "同一名字挂在 GLOBAL_ 下就会被接受")
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun readSample(name: String): SaveData? {
        val f = sample(name)
        if (f == null) {
            assumeTrue(false, "跳过：找不到真实存档样本 $name")
            return null
        }
        return SaveFormat.read(Files.readAllBytes(f))
    }

    /** 去掉「原样回写」依据，逼出真实压缩分支（构造参数的默认值就是 null）。 */
    private fun withoutSource(d: SaveData): SaveData = SaveData(
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

    private fun firstDiff(a: ByteArray, b: ByteArray): Long {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i.toLong()
        return if (a.size == b.size) -1L else n.toLong()
    }

    private fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** 深比较：数组比内容，`null` 与空串**不等价**（原版跳过项读回 null，`EraBinaryDataReader.cs:481-497`）。 */
    private fun sameEntry(a: SaveEntry, b: SaveEntry): Boolean {
        if (a.key != b.key) return false
        if (a === SaveEntry.Separator) return b === SaveEntry.Separator
        return when (a) {
            is SaveEntry.IntValue -> b is SaveEntry.IntValue && a.value == b.value
            is SaveEntry.IntArray1 -> b is SaveEntry.IntArray1 && a.values.contentEquals(b.values)
            is SaveEntry.IntArray2 -> b is SaveEntry.IntArray2 && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.values.contentEquals(b.values)

            is SaveEntry.IntArray3 -> b is SaveEntry.IntArray3 && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.dim2 == b.dim2 && a.values.contentEquals(b.values)

            is SaveEntry.StrValue -> b is SaveEntry.StrValue && a.value == b.value
            is SaveEntry.StrArray1 -> b is SaveEntry.StrArray1 && a.values.contentEquals(b.values)
            is SaveEntry.StrArray2 -> b is SaveEntry.StrArray2 && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.values.contentEquals(b.values)

            is SaveEntry.StrArray3 -> b is SaveEntry.StrArray3 && a.dim0 == b.dim0 && a.dim1 == b.dim1 &&
                a.dim2 == b.dim2 && a.values.contentEquals(b.values)

            is SaveEntry.MapValue -> b is SaveEntry.MapValue && a.entries == b.entries
            is SaveEntry.XmlValue -> b is SaveEntry.XmlValue && a.xml == b.xml
            is SaveEntry.DataTableValue ->
                b is SaveEntry.DataTableValue && a.schemaXml == b.schemaXml && a.dataXml == b.dataXml

            SaveEntry.Separator -> b === SaveEntry.Separator
        }
    }

    private fun report(line: String) {
        runCatching {
            val f = File("build/save-round-trip-audit.txt")
            f.parentFile?.mkdirs()
            f.appendText(line + "\n", Charsets.UTF_8)
        }
        println(line)
    }
}
