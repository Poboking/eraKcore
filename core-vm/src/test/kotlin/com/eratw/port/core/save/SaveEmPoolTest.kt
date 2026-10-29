package com.eratw.port.core.save

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.EmDataNames
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.proc.SystemStateCode
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M6-SAVE-REST 的扩展段（Map/Xml/DT）与读档收尾。
 *
 * 判据全部来自 C#：
 * - 写：`SaveEMDataToStreamBinary` / `SaveGlobalEMDataToStreamBinary`（VariableData.cs:3911-3959）；
 * - 读：`LoadVariableBinary` 的 Map/Xml/DT 三支（:4095-4124，接受集 = `Save* ∪ Global*`）；
 * - 读前清：`RemoveEMSaveData` / `RemoveEMGlobalData`（:4006-4054，MAP/DT 只清空、XML 移除）；
 * - 名字表：`VarExt*.csv` 的六条指令（ConstantData.cs:1346-1386）；
 * - `LOADCHARA` 的 `RESULT = 0/1`（VariableEvaluator.cs:2288 / :2316）；
 * - 读档后的 `ClearFunctionList + LoadData_DataLoaded`（Process.cs:1860-1861）。
 */
class SaveEmPoolTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 内存 [SaveFileIo]（同 `SaveHostTest` 的口径：路径键是相对路径）。 */
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

    /** 固定时钟（DT 行 id = `Utils.TimePoint()`，基准只依赖位数）。 */
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
    private fun i(v: Long): Value = Value.IntV(v)

    private fun store(): VariableStore = VariableStore()

    private fun addHero(s: VariableStore) {
        s.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス"))
        s.addCharacter(1L)
    }

    /** 扩展段名字表：本文件只用一组名字（save 侧即可覆盖三种条目）。 */
    private val names = EmDataNames(
        maps = listOf("SM"),
        xmls = listOf("SX"),
        dts = listOf("SDT"),
    )

    private fun sets(save: EmDataNames = names, global: EmDataNames = EmDataNames.EMPTY) =
        EmSaveSets(save = save, global = global)

    private fun binary() = SaveConfig(systemSaveInBinary = true, zipSaveData = true)

    // ------------------------------------------------------------------
    // 1. MAP / XML / DT 三条 round-trip
    // ------------------------------------------------------------------

    /**
     * MAP：`DataStringMaps[name]` → `WriteWithKey` 写成类型字节 32（键值对按枚举序），
     * 读回 `DataStringMaps[text] = value` 整表替换（VariableData.cs:3938-3943 / :4095-4102）。
     */
    @Test
    fun `MAP 池经 SAVEDATA 与 LOADDATA 往返`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val fs = FileSystemOps(s, io, binary(), sets())

        assertEquals(1L, int(c, "MAP_CREATE", s("SM")))
        int(c, "MAP_SET", s("SM"), s("K1"), s("V1"))
        int(c, "MAP_SET", s("SM"), s("K2"), s("V2"))
        assertTrue(fs.saveData(0, "map"))

        // 清掉（并把表整个删掉，证明是存档内容恢复而不是残留）
        int(c, "MAP_RELEASE", s("SM"))
        assertEquals(0L, int(c, "MAP_EXIST", s("SM")))

        fs.loadData(0)
        assertEquals(1L, int(c, "MAP_EXIST", s("SM")))
        assertEquals("V1", str(c, "MAP_GET", s("SM"), s("K1")))
        assertEquals("V2", str(c, "MAP_GET", s("SM"), s("K2")))
        assertEquals("K1,K2", str(c, "MAP_GETKEYS", s("SM")), "键序按写入序（.NET Dictionary 枚举序）")
    }

    /**
     * XML：条目值是 `XmlDocument.OuterXml`（EraBinaryDataWriter.cs:155-160），
     * 读回 `LoadXml` 后整档替换（VariableData.cs:4105-4113）。
     */
    @Test
    fun `XML 池经 SAVEDATA 与 LOADDATA 往返`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val fs = FileSystemOps(s, io, binary(), sets())

        assertEquals(1L, int(c, "XML_DOCUMENT", s("SX"), s("<r><a k=\"1\">x</a></r>")))
        assertTrue(fs.saveData(1, "xml"))

        assertEquals(1L, int(c, "XML_RELEASE", s("SX")))
        assertEquals(0L, int(c, "XML_EXIST", s("SX")))

        fs.loadData(1)
        assertEquals(1L, int(c, "XML_EXIST", s("SX")))
        assertEquals("<r><a k=\"1\">x</a></r>", str(c, "XML_TOSTR", s("SX")))
    }

    /**
     * DT：条目是 `WriteXmlSchema` + `WriteXml` 两段（EraBinaryDataWriter.cs:161-172），
     * 读回 `ReadXmlSchema` + `ReadXml`（EraBinaryDataReader.cs:72-80）。
     * 列类型/默认值/主键都经 schema 复原，行经 data 复原（:4115-4123）。
     */
    @Test
    fun `DataTable 池经 SAVEDATA 与 LOADDATA 往返`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val fs = FileSystemOps(s, io, binary(), sets())

        assertEquals(1L, int(c, "DT_CREATE", s("SDT")))
        assertEquals(1L, int(c, "DT_COLUMN_ADD", s("SDT"), s("NAME")))
        assertEquals(1L, int(c, "DT_COLUMN_ADD", s("SDT"), s("VAL"), i(4)))
        int(c, "DT_ROW_ADD", s("SDT"), s("NAME"), s("R1"), s("VAL"), i(10))
        val id2 = int(c, "DT_ROW_ADD", s("SDT"), s("NAME"), s("R2"), s("VAL"), i(20))
        assertTrue(fs.saveData(2, "dt"))

        assertEquals(1L, int(c, "DT_RELEASE", s("SDT")))
        assertEquals(0L, int(c, "DT_EXIST", s("SDT")))

        fs.loadData(2)
        assertEquals(1L, int(c, "DT_EXIST", s("SDT")))
        assertEquals(2L, int(c, "DT_ROW_LENGTH", s("SDT")))
        assertEquals(3L, int(c, "DT_COLUMN_LENGTH", s("SDT")), "id + NAME + VAL")
        assertEquals("R1", str(c, "DT_CELL_GETS", s("SDT"), i(0), s("NAME")))
        assertEquals(10L, int(c, "DT_CELL_GET", s("SDT"), i(0), s("VAL")))
        assertEquals("R2", str(c, "DT_CELL_GETS", s("SDT"), i(1), s("NAME")))
        // 主键值随档回来：按 id 定位同一行（DT_ROW_SET 的 id 形态）
        assertEquals(1L, int(c, "DT_ROW_SET", s("SDT"), i(id2), s("VAL"), i(21)), "返回写入的格数")
        assertEquals(21L, int(c, "DT_CELL_GET", s("SDT"), i(1), s("VAL")))
        // 装回后新行 id 不能与档内 id 撞主键（nextRowId 的单调性）
        val id3 = int(c, "DT_ROW_ADD", s("SDT"), s("NAME"), s("R3"))
        assertNotEquals(id2, id3)
        assertEquals(3L, int(c, "DT_ROW_LENGTH", s("SDT")))
    }

    // ------------------------------------------------------------------
    // 2. 名字表与接受集
    // ------------------------------------------------------------------

    /** 不在 `SAVE_*` 里的表**不进档**（VariableData.cs:3940 的 `ContainsKey` 守卫）。 */
    @Test
    fun `未列入扩展段的表不写档`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val fs = FileSystemOps(s, io, binary(), sets())

        assertEquals(1L, int(c, "MAP_CREATE", s("OTHER")))
        int(c, "MAP_SET", s("OTHER"), s("K"), s("V"))
        assertTrue(fs.saveData(3, "x"))

        int(c, "MAP_RELEASE", s("OTHER"))
        fs.loadData(3)
        assertEquals(0L, int(c, "MAP_EXIST", s("OTHER")), "名字不在表里 ⇒ 既没写也没读")
    }

    /**
     * `RemoveEMSaveData`（VariableData.cs:4006-4029）的三处不对称：
     * MAP 只 `Clear()`（表还在 ⇒ `MAP_EXIST=1`、`MAP_SIZE=0`）、XML `Remove`（`XML_EXIST=0`）、
     * DT 只 `Clear()`（列留着、行清空）。
     *
     * 造法：池里有三样，但存档里**没有**扩展段（写档时用空名字表），而读档时用完整名字表。
     */
    @Test
    fun `读档前清空只对档内参与的名字生效且清法各不相同`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val writer = FileSystemOps(s, io, binary(), EmSaveSets.EMPTY)
        assertTrue(writer.saveData(4, "no-em"))

        assertEquals(1L, int(c, "MAP_CREATE", s("SM")))
        int(c, "MAP_SET", s("SM"), s("K"), s("V"))
        assertEquals(1L, int(c, "XML_DOCUMENT", s("SX"), s("<r><a>1</a></r>")))
        assertEquals(1L, int(c, "DT_CREATE", s("SDT")))
        assertEquals(1L, int(c, "DT_COLUMN_ADD", s("SDT"), s("NAME")))
        int(c, "DT_ROW_ADD", s("SDT"), s("NAME"), s("R1"))

        FileSystemOps(s, io, binary(), sets()).loadData(4)

        assertEquals(1L, int(c, "MAP_EXIST", s("SM")), "MAP 只 Clear()，表本身留着")
        assertEquals(0L, int(c, "MAP_SIZE", s("SM")))
        assertEquals(0L, int(c, "XML_EXIST", s("SX")), "XML 是 Remove")
        assertEquals(1L, int(c, "DT_EXIST", s("SDT")), "DT 只 Clear()，表留着")
        assertEquals(0L, int(c, "DT_ROW_LENGTH", s("SDT")))
        assertEquals(2L, int(c, "DT_COLUMN_LENGTH", s("SDT")), "列定义不受 RemoveEMSaveData 影响")
    }

    // ------------------------------------------------------------------
    // 3. VarExt.csv 解析（ConstantData.cs:1309-1398）
    // ------------------------------------------------------------------

    /**
     * `VarExt*.csv` 的六条指令（大小写敏感精确匹配、后段 Trim、`;` 注释跳过、
     * `TEST.ERB` 这类非 `VarExt` 前缀的文件不读）。语料真实文件只有两条 `SAVE_DTS`。
     */
    @Test
    fun `VarExt 六条指令解析`(@org.junit.jupiter.api.io.TempDir root: Path) {
        val csv = root.resolve("CSV")
        Files.createDirectories(csv)
        Files.write(
            csv.resolve("VarExtGameData.csv"),
            listOf(
                "; 注释行不读",
                "",
                "SAVE_DTS, DT_UFUFU_LOG",
                "SAVE_DTS, DT_UFUFU_M_LOG",
                "SAVE_MAPS, MyDungeonMap , OtherMap",
                "SAVE_XMLS, 0, MyXmlDoc",
                "GLOBAL_DTS, DT_ACHIEVEMENT",
                "GLOBAL_MAPS, GM",
                "GLOBAL_XMLS, GX",
                "SAVE_DTS_NO_MATCH, X",
            ).joinToString("\r\n").toByteArray(Charsets.UTF_8),
        )
        Files.write(csv.resolve("NotVarExt.csv"), "SAVE_DTS, NOPE\r\n".toByteArray())
        Files.createDirectories(csv.resolve("sub"))
        Files.write(csv.resolve("sub").resolve("VarExtSub.csv"), "SAVE_DTS, DT_SUB\r\n".toByteArray())

        val p = EmSaveSets.parse(csv)
        assertEquals(
            setOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG", "DT_SUB"),
            p.save.dts.toSet(),
            "同一条指令可重复；`sub/` 递归读（文件顺序不可观测，读回按名整表替换）",
        )
        assertEquals(listOf("MyDungeonMap", "OtherMap"), p.save.maps, "后段 Trim")
        assertEquals(listOf("0", "MyXmlDoc"), p.save.xmls)
        assertEquals(listOf("DT_ACHIEVEMENT"), p.global.dts)
        assertEquals(listOf("GM"), p.global.maps)
        assertEquals(listOf("GX"), p.global.xmls)
        assertEquals(p.save.union(p.global).dts, p.loadAccept.dts, "接受集 = Save* ∪ Global*")
    }

    /** 目录缺失 / 无 `VarExt*.csv` ⇒ 空表（ConstantData.cs:1311-1316 的清空形态）。 */
    @Test
    fun `VarExt 缺失时为空表`(@org.junit.jupiter.api.io.TempDir root: Path) {
        assertEquals(EmSaveSets.EMPTY, EmSaveSets.parse(root.resolve("nope")))
        Files.createDirectories(root.resolve("CSV"))
        assertEquals(EmSaveSets.EMPTY, EmSaveSets.parse(root.resolve("CSV")))
        assertTrue(EmSaveSets.EMPTY.save.isEmpty)
    }

    /**
     * 真语料读数：`ERATW_GAMEDIR/CSV/VarExtGameData.csv`（带 BOM）只有两条 `SAVE_DTS`，
     * 与 `spec/save-format.md` 记录的实机存档 EM 段（`DT_UFUFU_LOG`、`DT_UFUFU_M_LOG`）一致。
     * 语料不在位时按[假设失败]跳过（不伪造读数）。
     */
    @Test
    fun `真语料的 VarExt 只有两个 SAVE_DTS`() {
        val gamedir = System.getenv("ERATW_GAMEDIR")
        org.junit.jupiter.api.Assumptions.assumeTrue(!gamedir.isNullOrBlank(), "缺少 ERATW_GAMEDIR")
        val csv = Path.of(gamedir, "CSV")
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(csv), "缺少 $csv")
        val p = EmSaveSets.parse(csv)
        assertEquals(listOf("DT_UFUFU_LOG", "DT_UFUFU_M_LOG"), p.save.dts)
        assertTrue(p.save.maps.isEmpty(), "语料没有 SAVE_MAPS")
        assertTrue(p.global.isEmpty, "语料没有 GLOBAL_* 指令")
    }

    // ------------------------------------------------------------------
    // 4. 读档后的状态交接（Process.cs:1860-1862）
    // ------------------------------------------------------------------

    /**
     * `ClearFunctionList(); SystemState = LoadData_DataLoaded;` 在本仓落在
     * `FileSystemOps.loadData` 的收尾（[SaveLoadHandoff]，不新造状态枚举）：
     * 两项都置位、状态就是既有枚举的 `528`，且**一次性可取走**。
     */
    @Test
    fun `LOADDATA 成功后记录 LoadData_DataLoaded 交接`() {
        val io = MemorySaveIo()
        val s = store()
        val fs = FileSystemOps(s, io, binary(), sets())
        assertTrue(fs.saveData(5, "handoff"))

        fs.loadData(5)
        val handoff = assertNotNull(SaveLoadHandoff.of(s), "装配点 = loadData 自身")
        assertTrue(handoff.functionListCleared, "ClearFunctionList 的等价信号先落（Process.cs:1860）")
        assertEquals(SystemStateCode.LoadData_DataLoaded, handoff.systemState, "Process.cs:1861")
        assertEquals(528, SystemStateCode.LoadData_DataLoaded.code, "数值与原版一致（SystemStateCode.cs）")
        assertEquals(SystemStateCode.LoadData_DataLoaded, handoff.takeSystemState())
        assertNull(handoff.takeSystemState(), "一次性消费")
    }

    /** 读档失败 ⇒ 不记交接（状态机不该被半途失败的读档推走）。 */
    @Test
    fun `LOADDATA 失败不记交接`() {
        val io = MemorySaveIo()
        val s = store()
        io.files["save06.sav"] = byteArrayOf(1, 2, 3)
        val fs = FileSystemOps(s, io, binary(), sets())
        try {
            fs.loadData(6)
        } catch (_: CodeEE) {
        }
        assertNull(SaveLoadHandoff.of(s)?.systemState)
    }

    // ------------------------------------------------------------------
    // 5. LOADCHARA 的 RESULT（VariableEvaluator.cs:2288 / :2316）
    // ------------------------------------------------------------------

    /**
     * `LOADCHARA`：入口 `RESULT = 0`；文件不存在 / 类别或游戏码或版本不符保持 0；
     * 追加成功置 1。接口签名不变（实现写 store），语句层零改动。
     */
    @Test
    fun `LOADCHARA 成功写 RESULT 1 缺档写 0`() {
        val io = MemorySaveIo()
        val src = store()
        addHero(src)
        src.writeInt("CFLAG", longArrayOf(0, 2), 31)
        FileSystemOps(src, io, binary()).saveChara(listOf(0L), "C", "chara")

        // 成功：RESULT 1
        val dst = store()
        dst.registerTemplate(CharacterTemplate(no = 1, name = "アリス", callname = "アリス"))
        dst.writeInt("RESULT", longArrayOf(0), 99)
        FileSystemOps(dst, io, binary()).loadChara("C")
        assertEquals(1L, dst.readInt("RESULT", longArrayOf(0)), ":2316")
        assertEquals(1, dst.characters.count)

        // 缺档：RESULT 归 0（不是保持旧值）
        val miss = store()
        miss.writeInt("RESULT", longArrayOf(0), 99)
        FileSystemOps(miss, io, binary()).loadChara("NOPE")
        assertEquals(0L, miss.readInt("RESULT", longArrayOf(0)), ":2288 的入口清零")
        assertEquals(0, miss.characters.count)
    }

    // ------------------------------------------------------------------
    // 6. 全局档的扩展段（VariableData.cs:3911-3934 / :2523）
    // ------------------------------------------------------------------

    /** `SAVEGLOBAL` / `LOADGLOBAL` 走 `GLOBAL_*` 名字表；读前 `RemoveEMGlobalData` 清全局段。 */
    @Test
    fun `全局档扩展段往返且读前清空`() {
        val io = MemorySaveIo()
        val s = store()
        val c = ctx(s)
        val g = EmDataNames(maps = listOf("GM"), xmls = listOf("GX"), dts = listOf("GDT"))
        val fs = FileSystemOps(s, io, binary(), sets(save = EmDataNames.EMPTY, global = g))

        assertEquals(1L, int(c, "MAP_CREATE", s("GM")))
        int(c, "MAP_SET", s("GM"), s("k"), s("v"))
        assertEquals(1L, int(c, "XML_DOCUMENT", s("GX"), s("<r/>")))
        fs.saveGlobal()
        assertTrue(io.exists("global.sav"))

        int(c, "MAP_RELEASE", s("GM"))
        assertEquals(1L, int(c, "XML_RELEASE", s("GX")))
        assertTrue(fs.loadGlobal())
        assertEquals("v", str(c, "MAP_GET", s("GM"), s("k")))
        assertEquals("<r />", str(c, "XML_TOSTR", s("GX")), ".NET 空元素写法 `<r />`（XmlNode 口径）")
    }

    // ------------------------------------------------------------------
    // 7. 名字表判空（[EmDataNames] 的两个哨兵）
    // ------------------------------------------------------------------
    @Test
    fun `扩展段名字表判空`() {
        assertTrue(EmDataNames.EMPTY.isEmpty)
        assertTrue(!names.isEmpty)
    }
}
