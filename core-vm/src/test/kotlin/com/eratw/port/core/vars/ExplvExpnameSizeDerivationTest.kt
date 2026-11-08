package com.eratw.port.core.vars

import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.VariableSizeLoader
import com.eratw.port.core.err.CodeEE
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EXPLV / EXPNAME 的尺寸推导链对账（lane `vars-explv`，2026-11-02）。
 *
 * ## 为什么要这条对账
 *
 * 端点上报里有两条「启动失败 + CodeEE」都写着 **长度 200**：
 * - `变量 EXPLV 的第 1 维下标 5002 越界（长度 200）`（语料 SET_MINIMUM_EXP 的 `EXPLV:(MINIMUM_ABL + 2|3)`）
 * - `变量 EXPNAME 的第 1 维下标 666 越界（长度 200）`
 *
 * 「两个不同来源的变量都正好是 200」看着像我们把长度写死了，所以必须把**原版怎么算出 200**
 * 这条链逐项钉住，结论是：200 不是常数，是语料自己带来的读数 —— 语料根目录下 CSV 里的
 * `VariableSize.csv` 有 `EXPLV,200`（该文件 :33）、`EXPNAME,200`（:78）、`EXP,200`（:97）
 * 三行，而原版长度正是由这三行推出来的。本文件断言的是**推导链**，不是把 200 抄第二遍。
 *
 * ## 原版口径（逐条带行号）
 *
 * 1. 出厂默认：全局 1 维整数 `VariableIntArrayLength` 全为 1000（ConstantData.cs:212-215），
 *    角色 1 维整数 `CharacterIntArrayLength` 全为 100（:240-243），名字表 `MaxDataList` 的
 *    EXPNAME 槽 = 100（:175）。⇒ 没有语料 VariableSize.csv 时 EXPLV=1000、EXPNAME=100。
 * 2. 名字表长度只认 `MaxDataList`：`names[i] = new string[MaxDataList[i]]`
 *    （ConstantData.cs:676-680）——**与对应 CSV 的行数无关**；越界行只告警跳过
 *    （loadDataTo，ConstantData.cs:1791-1795）。EXP.CSV 实际只有 113 行、最大编号 138，
 *    既不把 EXPNAME 撑大也不缩小。
 * 3. 主数组 ↔ 名字表联动 `_decideActualArraySize_sub(EXP, EXPNAME, CharacterIntArrayLength)`
 *    （调用点 ConstantData.cs:587，函数体 :553-581）：只改 EXPNAME ⇒ 角色 EXP 跟随（:574-576）；
 *    只改 EXP ⇒ EXPNAME 跟随（:578-580）；两边都改且不等 ⇒ 同时取最大值并告警（:559-571）。
 *    EXPLV 不在任何联动组里（它是独立的全局 1 维），所以它的长度只有「默认 1000 / CSV 改写」两条路。
 * 4. 越界判定本体：`array[index]` 先抛 CLR 的 IndexOutOfRange，术语化文案由
 *    `CheckElement` 补抛 `CodeEE`（VariableData.cs:81-87，OoRVarArg 三参：变量名/维度号/下标），
 *    表达式层原样重抛（VariableTerm.cs:57-106、FixedVariableTerm.cs:65-100）⇒ 原版照样抛。
 *
 * ## 破坏即红（证明本文件真在验推导链）
 *
 * - 把 [VariableSizes.decideActualArraySize] 的 EXP↔EXPNAME 联动摘掉 ⇒ 第 2、3 组用例红；
 * - 把名字表长度改成「按 CSV 行数扩容」⇒ 第 4 组用例红；
 * - 把 `checkFlat` 的越界检查放宽/删掉 ⇒ 第 5 组用例红（这正是本次要挡住的「顺手修好」）。
 */
class ExplvExpnameSizeDerivationTest {

    /** 把一段 VariableSize.csv 文本落到临时目录，返回该目录（编码走 UTF-8，与纯 ASCII 内容等价）。 */
    private fun writeVariableSizeCsv(dir: Path, body: String): Path {
        val path = dir.resolve("VariableSize.csv")
        Files.write(path, body.toByteArray(Charsets.UTF_8))
        return path
    }

    /** 只喂 VariableSize.csv 时装出来的注册表（等价原版 LoadData 的第 1 步 + 建表）。 */
    private fun registryOf(dir: Path, warnings: MutableList<String>): VariableRegistry {
        val sizes = VariableSizes()
        VariableSizeLoader.load(dir, sizes, warnings)
        return VariableRegistry(sizes)
    }

    private fun dimsOf(registry: VariableRegistry, name: String): List<Int> =
        registry.mustDef(name).dims.toList()

    // ------------------------------------------------------------------
    // 1) 出厂读数：没有语料 VariableSize.csv 时必须是原版默认
    // ------------------------------------------------------------------

    @Test
    fun 出厂读数explv是全局一千而expname与exp是一百() {
        val registry = VariableRegistry()
        // ConstantData.cs:212-215（VariableIntArrayLength 全 1000，EXPLV 无特例）
        assertEquals(listOf(1000), dimsOf(registry, "EXPLV"), "出厂 EXPLV")
        // ConstantData.cs:175（MaxDataList 的 EXPNAME 槽 = 100）+ :676-680（names 按它分配）
        assertEquals(listOf(100), dimsOf(registry, "EXPNAME"), "出厂 EXPNAME")
        // ConstantData.cs:240-243（CharacterIntArrayLength 全 100，EXP 无特例）
        assertEquals(listOf(100), dimsOf(registry, "EXP"), "出厂角色 EXP")
        // 名字表容量与 VarDef 同源（VariableRegistry.kt:77 取 sizes.maxDataList）
        assertEquals(100, registry.csvTables[1].capacity, "EXP.CSV 槽（1）的表容量")
    }

    // ------------------------------------------------------------------
    // 2 / 3) 主数组 ↔ 名字表联动（_decideActualArraySize_sub 的两个单侧分支）
    // ------------------------------------------------------------------

    @Test
    fun 只声明expname时角色exp跟随(@TempDir dir: Path) {
        writeVariableSizeCsv(dir, "EXPNAME,300\n")
        val warnings = ArrayList<String>()
        val registry = registryOf(dir, warnings)
        assertEquals(listOf(300), dimsOf(registry, "EXPNAME"), "EXPNAME 取声明值")
        // ConstantData.cs:574-576：只改名字表 ⇒ arraylength[EXP] = MaxDataList[EXPNAME]
        assertEquals(listOf(300), dimsOf(registry, "EXP"), "角色 EXP 必须跟随名字表")
        assertTrue(warnings.isEmpty(), "单侧声明不该告警：$warnings")
    }

    @Test
    fun 只声明exp时expname跟随(@TempDir dir: Path) {
        writeVariableSizeCsv(dir, "EXP,250\n")
        val warnings = ArrayList<String>()
        val registry = registryOf(dir, warnings)
        // ConstantData.cs:578-580：只改主数组 ⇒ MaxDataList[EXPNAME] = arraylength[EXP]
        assertEquals(listOf(250), dimsOf(registry, "EXPNAME"), "EXPNAME 必须跟随角色 EXP")
        assertEquals(listOf(250), dimsOf(registry, "EXP"), "角色 EXP 取声明值")
        assertEquals(250, registry.csvTables[1].capacity, "名字表容量同步")
        assertTrue(warnings.isEmpty(), "单侧声明不该告警：$warnings")
    }

    @Test
    fun 两侧都声明且不等时取最大值并告警(@TempDir dir: Path) {
        writeVariableSizeCsv(dir, "EXP,200\nEXPNAME,150\n")
        val warnings = ArrayList<String>()
        val registry = registryOf(dir, warnings)
        // ConstantData.cs:559-571：Math.Max 双向写入 + DifferentVarSize 告警
        assertEquals(listOf(200), dimsOf(registry, "EXP"), "EXP 取两侧最大值")
        assertEquals(listOf(200), dimsOf(registry, "EXPNAME"), "EXPNAME 取两侧最大值")
        assertTrue(
            warnings.any { it.contains("EXP") && it.contains("EXPNAME") },
            "尺寸不一致必须留告警：$warnings",
        )
    }

    // ------------------------------------------------------------------
    // 4) 名字表长度不随 CSV 行数扩容（ConstantData.cs:676-680 / :1791-1795）
    // ------------------------------------------------------------------

    @Test
    fun 名字表按MaxDataList定死不随csv行数扩容(@TempDir dir: Path) {
        writeVariableSizeCsv(dir, "EXPNAME,100\n")
        val registry = registryOf(dir, ArrayList())
        // 造一份 120 行（编号 0..119）的 Exp.csv：后 20 行在原版是越界行 ⇒ 只告警跳过。
        val rows = (0..119).joinToString("\n") { "$it,e$it" }
        Files.write(dir.resolve("Exp.csv"), rows.toByteArray(Charsets.UTF_8))
        val warnings = ArrayList<String>()
        CsvLoader.loadNameTable(dir, "Exp.csv", 1, registry.csvTables[1], null, warnings)
        assertEquals(100, registry.csvTables[1].capacity, "容量必须由 MaxDataList 定死，不随行数长")
        assertEquals(listOf(100), dimsOf(registry, "EXPNAME"), "EXPNAME 的 VarDef 维度同样不扩容")
        assertEquals("e0", registry.csvTables[1].get(0), "界内行照常读")
        assertTrue(warnings.isNotEmpty(), "越界行必须留告警：$warnings")
    }

    // ------------------------------------------------------------------
    // 5) 语料读数：真语料的 VariableSize.csv 把三个量都写成 200，且越界照常抛
    // ------------------------------------------------------------------

    /** 语料 CSV 目录（与 `DimVarSizesSnapshotTest` 同一取法：ERATW_CORPUS 的父级旁的 CSV）。 */
    private fun corpusCsvDir(): Path? {
        val corpus = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        Assumptions.assumeTrue(
            corpus != null && java.io.File(corpus).isDirectory,
            "未设 ERATW_CORPUS：跳过语料对账用例",
        )
        val root = Paths.get(corpus!!)
        val csv = root.parent?.resolve("CSV") ?: return null
        Assumptions.assumeTrue(java.io.File(csv.toString()).isDirectory, "语料旁没有 CSV 目录：$csv")
        return csv
    }

    @Test
    fun 语料读数下explv与expname都是二百() {
        val csv = corpusCsvDir() ?: return
        val warnings = ArrayList<String>()
        val registry = registryOf(csv, warnings)
        // 语料 VariableSize.csv 的 EXPLV,200 / EXPNAME,200 / EXP,200 三行 ⇒ 上报里的「长度 200」
        assertEquals(listOf(200), dimsOf(registry, "EXPLV"), "语料口径下的 EXPLV")
        assertEquals(listOf(200), dimsOf(registry, "EXPNAME"), "语料口径下的 EXPNAME")
        assertEquals(listOf(200), dimsOf(registry, "EXP"), "语料口径下的角色 EXP")
        // 越界判定本体保持原版形状：界内可读，界外照抛。
        // 文案 = 官方 Error.OoRVarArg 逐字（Lang.cs:1638 / 语料根 emuera.zhs.xml:585 /
        // reference 表 :364）：`数组变量"{0}"的第{1}参数({2})超出数组范围`
        // —— 官方串**不带长度**（原版实参只有 varName / 维号 / 下标，
        // spec: UserDefinedVariableToken.cs:62），所以「长度 200」由上面的 dims 断言钉，
        // 不再靠屏上文案（2026-11-04 lane errtext 收口）。
        val store = VariableStore(registry)
        assertEquals(0L, store.readInt("EXPLV", longArrayOf(199)), "EXPLV[199] 界内可读")
        val e1 = assertFailsWith<CodeEE> { store.readInt("EXPLV", longArrayOf(5002)) }
        assertEquals("数组变量\"EXPLV\"的第1参数(5002)超出数组范围", e1.message)
        assertEquals("", store.readStr("EXPNAME", longArrayOf(199)), "EXPNAME[199] 界内可读")
        val e2 = assertFailsWith<CodeEE> { store.readStr("EXPNAME", longArrayOf(666)) }
        assertEquals("数组变量\"EXPNAME\"的第1参数(666)超出数组范围", e2.message)
    }

    /**
     * 同族审计的机检部分：语料 VariableSize.csv 的每一行都必须被我们的名字解析认下并写进尺寸表。
     *
     * 为什么这条重要：长度表的「出厂值」和「语料值」之间只隔着一层名字解析 —— 一旦某个名字在我们
     * 的 [VariableSizes.variableCode] 里解析不出来（而原版 `VariableIdentifier.GetVariableId`
     * 认它，VariableIdentifier.cs:61-93），我们就**静默**用出厂值，PC 用 CSV 值 ⇒ 同一个下标
     * 两端判性相反（我们不炸 / PC 炸，或反过来）。这类分歧不会自己冒出来，只能逐行对账。
     *
     * 原版侧的口径：VariableCode 里 A..Z 是真实条目（VariableCode.cs:54 `A = 720926`），
     * 所以语料那 26 行 `A,200`…`Z,200` 在 PC 上照常生效，不许当成「未知变量」跳过。
     */
    @Test
    fun 语料每一行尺寸都被认下() {
        val csv = corpusCsvDir() ?: return
        val warnings = ArrayList<String>()
        registryOf(csv, warnings)
        val rejected = warnings.filter {
            it.contains("未知") || it.contains("不可改") || it.contains("不可修改") ||
                it.contains("非整数") || it.contains("列数不足")
        }
        assertTrue(rejected.isEmpty(), "语料尺寸表里有我们解析不掉的行（会与原版长度表静默分歧）：$rejected")
        // 反向确认：行数与「被认下」的量级对得上（语料该文件共 96 行两列 + 6 行三列）
        val rows = CsvLoader.readEnabledLines(csv.resolve("VariableSize.csv"))
        assertTrue(rows.size >= 100, "语料 VariableSize.csv 行数异常：${rows.size}")
    }
}
