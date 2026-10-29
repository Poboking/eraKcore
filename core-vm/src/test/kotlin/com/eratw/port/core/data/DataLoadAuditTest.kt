package com.eratw.port.core.data

import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameBaseLoader
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.VariableSizeLoader
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.CsvNameTable
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * DATA-AUDIT（M7 波次 w7-dataaudit）：`core/data` 装载层与 C# 原版逐条对拍。
 *
 * 覆盖面（对照点全部落在 ConstantData.cs / GameBase.cs / EraStreamReader.cs 的行号上）：
 * 1. 默认尺寸表（`setDefaultArrayLength`，ConstantData.cs:171-259）逐项；
 * 2. 29 槽文件名与装载顺序（`LoadData`，ConstantData.cs:682-710）逐条；
 * 3. 装载顺序语义：VariableSize 先于名字表（:675 → :676-680）、名字表先于角色模板（:682-710 → :738）、
 *    ITEMPRICE 长度来自 ITEMNAME（:681）；
 * 4. 列口径：CSV 名字列**不** trim（:1802）而 .als 别名列 trim（:1876）；
 * 5. 已按 M9-data 修正：STR 槽 10 只装值不建反向索引（:713-716）、CanForbid 负数尺寸
 *    改写为 0（:347-356）；仍按现状钉桩 + TODO(verify)：花括号续行、越界下标自动扩容。
 *
 * 语料只读；缺 `ERATW_CORPUS` / `ERATW_GAMEDIR` 时那条真实语料用例 assume-skip，
 * 不硬编码任何主机绝对路径。
 */
class DataLoadAuditTest {

    private fun <T> withTempDir(prefix: String, block: (Path) -> T): T {
        val dir = createTempDirectory(prefix)
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 语料 `CSV` 目录：`ERATW_GAMEDIR` 优先，其次从 `ERATW_CORPUS` 向上找。 */
    private fun corpusCsvDir(): Path? {
        for (key in listOf("ERATW_GAMEDIR", "ERATW_CORPUS")) {
            val raw = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            var cur: Path? = Path.of(raw).toAbsolutePath().normalize()
            repeat(4) {
                val dir = cur ?: return@repeat
                if (dir.fileName?.toString().equals("CSV", ignoreCase = true) && Files.isDirectory(dir)) {
                    return dir
                }
                val nested = dir.resolve("CSV")
                if (Files.isDirectory(nested)) return nested
                cur = dir.parent
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 1. 默认尺寸表
    // ------------------------------------------------------------------

    @Test
    fun `默认尺寸表逐项对照 setDefaultArrayLength`() {
        val sizes = VariableSizes()

        // ConstantData.cs:173-201：MaxDataList[0..28] 逐槽字面值
        val expectMax = listOf(
            100, 100, 1000, 200, 1000, 100, 1000, 100, 1000, 100,
            20000, 100, 100, 10000, 1000, 1000, 100, 100, 1000, 1,
            1, 20000, 100, 100, 1000, 100, 100, 100, 100,
        )
        assertEquals(29, sizes.maxDataList.size)
        assertEquals(expectMax, sizes.maxDataList.toList(), "MaxDataList 29 槽")

        // ConstantData.cs:212-218：int1D 全 1000，[3]=10000、[60]=MaxDataList[6]=1000、[64]=625
        assertEquals(65, sizes.int1D.size)
        for (i in sizes.int1D.indices) {
            val want = when (i) {
                3 -> 10000
                60 -> 1000
                64 -> 625
                else -> 1000
            }
            assertEquals(want, sizes.int1D[i], "VariableIntArrayLength[$i]")
        }

        // ConstantData.cs:219-223：str1D 全 100，[1]=MaxDataList[10]=20000
        assertEquals(7, sizes.str1D.size)
        for (i in sizes.str1D.indices) {
            assertEquals(if (i == 1) 20000 else 100, sizes.str1D[i], "VariableStrArrayLength[$i]")
        }

        // ConstantData.cs:224-239：2 维默认 (100,100)、3 维默认 (100,100,100)
        // （C# 用 long 打包 429496729700 / 109951267635300，解开就是这两个形状）
        assertEquals(6, sizes.int2D.size)
        for (i in sizes.int2D.indices) {
            assertEquals(listOf(100, 100), sizes.int2D[i].toList(), "VariableIntArray2DLength[$i]")
        }
        assertEquals(2, sizes.int3D.size)
        for (i in sizes.int3D.indices) {
            assertEquals(listOf(100, 100, 100), sizes.int3D[i].toList(), "VariableIntArray3DLength[$i]")
        }

        // ConstantData.cs:240-251：charaInt1D 全 100，[3]/[9]=1000、[10]/[15]=200
        assertEquals(84, sizes.charaInt1D.size)
        for (i in sizes.charaInt1D.indices) {
            val want = when (i) {
                3, 9 -> 1000
                10, 15 -> 200
                else -> 100
            }
            assertEquals(want, sizes.charaInt1D[i], "CharacterIntArrayLength[$i]")
        }
        assertEquals(listOf(100), sizes.charaStr1D.toList(), "CharacterStrArrayLength")

        // ConstantData.cs:252-255：CharacterIntArray2DLength[0] = 4294967297 → (1,1)
        assertEquals(listOf(1, 1), sizes.charaInt2D[0].toList(), "CharacterIntArray2DLength[0]")
    }

    // ------------------------------------------------------------------
    // 2. 29 槽文件名 / 顺序
    // ------------------------------------------------------------------

    @Test
    fun `29 槽文件名与装载顺序逐条对照 LoadData`() {
        // ConstantData.cs:682-710 的 loadDataTo 调用序列，位置即槽号
        val expect = listOf(
            "Abl.csv",     // 0  ABL
            "Exp.csv",     // 1  EXP
            "Talent.csv",  // 2  TALENT
            "Palam.csv",   // 3  PALAM
            "Train.csv",   // 4  TRAIN
            "Mark.csv",    // 5  MARK
            "Item.csv",    // 6  ITEM（第 3 列 → ItemPrice，:688）
            "Base.csv",    // 7  BASE
            "Source.csv",  // 8  SOURCE
            "Ex.csv",      // 9  EX
            "Str.csv",     // 10 STR
            "Equip.csv",   // 11 EQUIP
            "Tequip.csv",  // 12 TEQUIP
            "Flag.csv",    // 13 FLAG
            "Tflag.csv",   // 14 TFLAG
            "Cflag.csv",   // 15 CFLAG
            "Tcvar.csv",   // 16 TCVAR
            "Cstr.csv",    // 17 CSTR
            "Stain.csv",   // 18 STAIN
            "Cdflag1.csv", // 19 CDFLAG1
            "Cdflag2.csv", // 20 CDFLAG2
            "Strname.csv", // 21 STRNAME
            "Tstr.csv",    // 22 TSTR
            "Savestr.csv", // 23 SAVESTR
            "Global.csv",  // 24 GLOBAL
            "Globals.csv", // 25 GLOBALS
            "Day.csv",     // 26 DAY
            "Time.csv",    // 27 TIME
            "Money.csv",   // 28 MONEY
        )
        assertEquals(expect, GameDataLoader.SLOT_FILES)
        // JUEL 不是名字表槽：ConstantData.cs:682-710 没有 JUEL.CSV（JUEL 的键走 PALAM 名字表，:1654-1659）
        assertTrue(GameDataLoader.SLOT_FILES.none { it.equals("Juel.csv", ignoreCase = true) })
    }

    // ------------------------------------------------------------------
    // 3. 装载顺序
    // ------------------------------------------------------------------

    @Test
    fun `VariableSize 先于名字表装载`() = withTempDir("eratw-audit-order1") { dir ->
        // ConstantData.cs:675 loadVariableSizeData → :676-680 names[i] = new string[MaxDataList[i]]
        // 故名字表容量必须是 VariableSize 调整后的值（150），而不是缺省 100。
        dir.resolve("VariableSize.csv").writeText("ABLNAME,150\n")
        dir.resolve("Abl.csv").writeText("149,尾名\n")

        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        val table = data.registry.csvTables[0]
        assertEquals(150, data.registry.sizes.maxDataList[0])
        assertEquals(150, table.size)
        assertEquals("尾名", table[149])
        assertEquals(149, table.keywordIndex("尾名"))
        // 若顺序反了：容量 100 ⇒ 149 越界（C# :1791-1795）或被自动扩容成 1000，两条断言都会红
        assertTrue(data.warnings.isEmpty(), data.warnings.toString())
    }

    @Test
    fun `名字表先于角色模板且界来自 VARSIZE`() = withTempDir("eratw-audit-order2") { dir ->
        // ConstantData.cs:682-710 装名字表 → :738 loadCharacterData；模板的键反查与界都依赖前两者
        dir.resolve("VariableSize.csv").writeText("ABL,200\n")
        dir.resolve("Abl.csv").writeText("151,稀有\n")
        val charaDir = Files.createDirectories(dir.resolve("Chara"))
        charaDir.resolve("Chara3.csv").writeText(
            "番号,3,\n" +
                "名前,测试娘,\n" +
                "能力,稀有,4\n" +   // 反向查名 → 151；只有名字表已装才命中
                "能力,99,2\n" +
                "能力,200,1\n",     // 恰在界上 → 越界；界来自 charaInt1D[2]=200（非缺省 100）
        )

        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        assertEquals(200, data.registry.sizes.charaInt1D[2])
        val tmpl = data.templates.single()
        assertEquals(3L, tmpl.no)
        assertEquals(4L, tmpl.abl[151])
        assertEquals(2L, tmpl.abl[99])
        assertNull(tmpl.abl[200])
        assertTrue(
            data.warnings.any { it.contains("越界") && it.contains("200") },
            data.warnings.toString(),
        )
    }

    @Test
    fun `ITEM 价格数组长度随 ITEMNAME`() = withTempDir("eratw-audit-itemprice") { dir ->
        // ConstantData.cs:681 ItemPrice = new long[MaxDataList[6]]；:688 把 ItemPrice 传给 ITEM 槽
        dir.resolve("VariableSize.csv").writeText("ITEMNAME,1500\n")
        dir.resolve("Item.csv").writeText("1400,末品,500\n")

        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        assertEquals(1500, data.registry.sizes.maxDataList[6])
        assertEquals(1500, data.registry.sizes.int1D[60])
        assertEquals(1500, data.itemPrices.size)
        assertEquals("末品", data.registry.csvTables[6][1400])
        assertEquals(500L, data.itemPrices[1400])
        assertTrue(data.warnings.isEmpty(), data.warnings.toString())
    }

    // ------------------------------------------------------------------
    // 4. 列口径
    // ------------------------------------------------------------------

    @Test
    fun `CSV 名字列不 trim 而 als 别名列 trim`() = withTempDir("eratw-audit-trim") { dir ->
        // :1802 array[num2] = source[range] 原样切片（不 trim）；:1876 别名走 array[1].Trim()。
        // :718-723 建反向索引时用的也是原始 array[k]，故「 甲 」这种带空白的名字按原串可查、按 trim 后不可查。
        dir.resolve("Abl.csv").writeText("0, 甲 \n")
        dir.resolve("Abl.als").writeText("2, 乙 \n")

        val warnings = ArrayList<String>()
        val table = CsvNameTable(0, 100)
        CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)

        assertEquals(" 甲 ", table[0])
        assertEquals(0, table.keywordIndex(" 甲 "))
        assertNull(table.keywordIndex("甲"))
        assertEquals(2, table.keywordIndex("乙"))
        assertNull(table.keywordIndex(" 乙 "))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `空 CSV 仍装载同名 als`() = withTempDir("eratw-audit-emptycsv") { dir ->
        // ConstantData.cs:1766-1815 循环读 0 行后，:1834-1838 仍无条件检查同名 .als
        dir.resolve("Abl.csv").writeText(";整文件只有注释\n\n")
        dir.resolve("Abl.als").writeText("0,别名甲\n")

        val warnings = ArrayList<String>()
        val table = CsvNameTable(0, 100)
        CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)

        assertEquals("", table[0])
        assertEquals(0, table.keywordIndex("别名甲"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    // ------------------------------------------------------------------
    // 5. GAMEBASE
    // ------------------------------------------------------------------

    @Test
    fun `GAMEBASE 文本键取原文且未知键静默`() = withTempDir("eratw-audit-gamebase") { dir ->
        // GameBase.cs:128 的 array[1].Trim() 丢弃返回值；:150-161 直接赋原串
        dir.resolve("GameBase.csv").writeText(
            "タイトル, 标题甲 \n" +
                "作者,作者乙\n" +
                "弄ってみた人,丙\n" +
                "バージョン,proto\n",
        )

        val warnings = ArrayList<String>()
        val gb = GameBaseLoader.load(dir, warnings)

        assertEquals(" 标题甲 ", gb.title)
        assertEquals("作者乙", gb.author)
        assertEquals(0L, gb.version)          // proto 非整数 → 0（:138-140）
        assertEquals(-1L, gb.allowVersion)    // 键缺失 → 字段初值（GameBase.cs:27）
        assertEquals(-1L, gb.defaultChara)    // 键缺失 → 字段初值（GameBase.cs:37）
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    // ------------------------------------------------------------------
    // 6. 已登记分歧：按现状钉桩
    // ------------------------------------------------------------------

    @Test
    fun `STR 槽 10 只装名字不建关键字索引`() = withTempDir("eratw-audit-slot10") { dir ->
        // ConstantData.cs:711-716：建 nameToIntDics 时 j == 10 直接 continue（Str.csv 存的是
        // 字符串内容、不是名字）；names[10] 仍由 :1802 写值，:725-736 的别名合并也整段跳过。
        dir.resolve("Str.csv").writeText("1,鳥居\n2,境内\n")
        dir.resolve("Str.als").writeText("3,别名\n")

        val warnings = ArrayList<String>()
        val table = CsvNameTable(10, 100)
        CsvLoader.loadNameTable(dir, "Str.csv", 10, table, null, warnings)

        assertEquals("鳥居", table[1])
        assertEquals("境内", table[2])
        // C# 的 nameToIntDics[10] 恒为空 ⇒ 名字与 .als 别名都不进反查
        assertNull(table.keywordIndex("鳥居"))
        assertNull(table.keywordIndex("境内"))
        assertNull(table.keywordIndex("别名"))
        assertEquals("", table[3])            // .als 只进反查、不写 names[]；槽 10 连反查也不进

        // 对照：其它槽仍按 :711-724 建索引，本次修正只动槽 10。
        dir.resolve("Abl.csv").writeText("1,能力\n")
        val abl = CsvNameTable(0, 100)
        CsvLoader.loadNameTable(dir, "Abl.csv", 0, abl, null, warnings)
        assertEquals(1, abl.keywordIndex("能力"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `花括号续行按原版拼接或抛错`() = withTempDir("eratw-audit-brace") { dir ->
        // EraStreamReader.cs:130-184：剥过前导空白后行首是 `}` → 抛 CodeEE；行首 `{` 必须整行、
        // 且会把后续行拼成一行。LOADDATA/CSV 装载链把 CodeEE 当致命错误上抛，不再降级成告警。
        dir.resolve("Abl.csv").writeText("0,甲\n}\n1,乙\n")
        assertFailsWith<CodeEE> {
            CsvLoader.loadNameTable(dir, "Abl.csv", 0, CsvNameTable(0, 100), null, ArrayList())
        }

        // 正常形态：块内两行拼成一条逻辑行，分隔符 = ReplaceContinuationBR（本作 `" "`）。
        dir.resolve("Abl.csv").writeText("0,甲\n{\n1,乙\n}\n2,丙\n")
        val warnings = ArrayList<String>()
        val table = CsvNameTable(0, 100)
        CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)
        assertEquals("甲", table[0])
        assertEquals("乙 ", table[1], "拼接线带一个尾随空格（EraStreamReader.cs:179）")
        assertEquals("丙", table[2])
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `CanForbid 变量负数尺寸改写为 0 表示禁用`() = withTempDir("eratw-audit-canforbid") { dir ->
        // DAY = 720896 带 __CAN_FORBID__（65536）。ConstantData.cs:340-357：负数在 CanForbid 变量上
        // 被改写成 0（"禁用数组"）而不是拒绝；0 落到 switch default（:537-544）写
        // VariableIntArrayLength[0] = 0，decideActualArraySize（:577-580）再把 DAYNAME 名字表拉成 0。
        dir.resolve("VariableSize.csv").writeText("DAY,-1\n")

        val sizes = VariableSizes()
        val warnings = ArrayList<String>()
        VariableSizeLoader.load(dir, sizes, warnings)

        assertEquals(0, sizes.int1D[0])
        assertEquals(0, sizes.maxDataList[26])
        assertTrue(warnings.isEmpty(), warnings.toString())

        // 禁用形态在存储层可表示：VarDef.isForbid（dims 含 0）→ 任何下标访问抛 CodeEE
        val registry = VariableRegistry(sizes)
        val store = VariableStore(registry)
        assertFailsWith<CodeEE> { store.readInt("DAY", longArrayOf(0)) }

        // 0 与非 CanForbid 负数仍拒绝（ArrayLengthIs0 :342-345 / CanNotDisable :347-351）
        dir.resolve("VariableSize.csv").writeText("DAY,0\nRESULT,-1\n")
        val sizes2 = VariableSizes()
        val warnings2 = ArrayList<String>()
        VariableSizeLoader.load(dir, sizes2, warnings2)
        assertEquals(1000, sizes2.int1D[0])          // DAY,0 → 拒绝
        assertEquals(100, sizes2.maxDataList[26])
        assertEquals(1000, sizes2.int1D[10])         // RESULT（无 CanForbid）, -1 → 拒绝
        assertTrue(warnings2.any { it.contains("无法为 0") && it.contains("DAY,0") }, warnings2.toString())
        assertTrue(
            warnings2.any { it.contains("不能为负") && it.contains("RESULT,-1") },
            warnings2.toString(),
        )

        // 负数禁用时多余尺寸按 1 维口径告警（ConstantData.cs:352-355 IgnoreNDData）
        dir.resolve("VariableSize.csv").writeText("DAY,-1,5\n")
        val sizes3 = VariableSizes()
        val warnings3 = ArrayList<String>()
        VariableSizeLoader.load(dir, sizes3, warnings3)
        assertEquals(0, sizes3.int1D[0])
        assertEquals(0, sizes3.maxDataList[26])
        assertTrue(warnings3.any { it.contains("忽略") }, warnings3.toString())
    }

    @Test
    fun `下标越界不扩容，告警跳过对齐原版`() = withTempDir("eratw-audit-oor") { dir ->
        // C# ConstantData.cs:1791-1795：result >= array.Length → OoRArray 告警 + continue，
        // names[150] 永远为空、表长仍是 100，且**没有**扩容分支。
        // （2026-10-22 移除了此前的 autoGrowByCsvExtent 试探性扩容——与官方口径不符且在本语料
        //   是死代码，GameDataLoader KDoc 有完整取舍记录；本用例由原「越界下标自动扩容是移植侧
        //   分歧」改钉对齐后的新口径。）
        dir.resolve("Mark.csv").writeText("150,越界名\n")

        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        assertEquals(100, data.registry.sizes.maxDataList[5])
        assertEquals(100, data.registry.csvTables[5].size)
        assertNull(data.registry.csvTables[5].keywordIndex("越界名"))
        assertTrue(
            data.warnings.any { it.contains("越界") && it.contains("Mark.csv") },
            data.warnings.toString(),
        )
    }

    // ------------------------------------------------------------------
    // 7. 真实语料（只读；缺环境变量 assume-skip）
    // ------------------------------------------------------------------

    @Test
    fun `真实语料 STR 槽只装值不建反查`() {
        val dir = corpusCsvDir()
        Assumptions.assumeTrue(dir != null, "跳过：未设置 ERATW_GAMEDIR/ERATW_CORPUS 或找不到 CSV 目录")
        val data = GameDataLoader.load(dir!!)
        assertNotNull(data)

        val tables = data.registry.csvTables
        // Str.csv:1,鳥居 / 2,境内 …（真实语料只读装载）
        assertEquals("鳥居", tables[10][1])
        // C# 的 nameToIntDics[10] 恒空（ConstantData.cs:713-716）⇒ 与 PC 侧一致，按名查不到
        assertNull(tables[10].keywordIndex("鳥居"))
        // 与 STR 真正使用的 STRNAME 槽 21 是两回事（本语料 STRNAME 表为空）
        assertEquals("", tables[21][0])
    }
}
