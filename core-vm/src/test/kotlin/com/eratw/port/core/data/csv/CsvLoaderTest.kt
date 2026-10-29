package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSV 装载层测试。分两层：
 * 1. 真实游戏数据（`ERATW_GAMEDIR` / `ERATW_CORPUS`，缺变量则 assume-skip）——断言离线核对过的字面值；
 * 2. 合成数据（临时目录）——锁注释/BOM/CRLF/越界/重复/默认值等边角语义。
 */
class CsvLoaderTest {

    /**
     * 语料 `CSV` 目录：`ERATW_GAMEDIR` 优先，其次从 `ERATW_CORPUS` 向上找（后者常指到 `.../ERB`）。
     * 不用任何主机绝对路径兜底 —— 缺环境变量时 assume-skip（契约 §1.6）。
     */
    private fun gameDir(): Path? {
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

    private fun requireGameDir(): Path {
        val dir = gameDir()
        org.junit.jupiter.api.Assumptions.assumeTrue(
            dir != null,
            "跳过：未设置 ERATW_GAMEDIR/ERATW_CORPUS 或找不到 CSV 目录",
        )
        return dir!!
    }

    @Test
    fun `真实游戏数据 GAMEBASE 与角色模板`() {
        val dir = requireGameDir()
        val data = GameDataLoader.load(dir)
        assertNotNull(data)

        // GAMEBASE（离线核对 GameBase.csv 原文 + GameBase.cs 键位）
        assertEquals("eraThe World【画蛇添足版】", data.gameBase.title)
        assertEquals(7153L, data.gameBase.gameCode)
        assertEquals(12L, data.gameBase.allowVersion)          // バージョン違い認める,0012
        assertEquals(0L, data.gameBase.version)                 // バージョン,proto → 非整数 → 保持 0
        assertEquals(-1L, data.gameBase.defaultChara)           // 无「最初からいるキャラ」行
        assertEquals("", data.gameBase.author)                  // 作者键是「弄ってみた人」，原版不认

        // 29 槽：ABL=0 / BASE=7（槽号见 GameDataLoader.SLOT_FILES 注释）
        assertEquals("Ｃ感覚", data.registry.csvTables[0][0])   // Abl.csv: 0,Ｃ感覚
        assertEquals("体力", data.registry.csvTables[7][0])       // Base.csv: 0,体力
        assertEquals(0, data.registry.csvTables[7].keywordIndex("体力"))
        assertEquals(12, data.registry.csvTables[0].keywordIndex("技巧"))
        // ITEM 价格（Item.csv 第 3 列，348 行）
        assertEquals(3000L, data.itemPrices[0])                  // 0,跳蛋,3000
        assertEquals(9000L, data.itemPrices[1])                  // 1,電動按摩棒,9000

        // 角色模板（Chara 目录 168 个文件）
        assertTrue(data.templates.size > 100, "模板数应 >100，实际 ${data.templates.size}")
        val zero = data.templates.first { it.no == 0L }
        assertEquals(0L, zero.csvNo)                             // Chara0.csv → csvNo=0
        assertEquals("你", zero.name)
        assertEquals("你", zero.callname)
        assertEquals(2000L, zero.maxbase[0])                     // 基礎,体力,2000（体力=0）
        assertEquals(2000L, zero.maxbase[1])                     // 基礎,気力,2000
        assertEquals(2L, zero.abl[12])                           // 能力,技巧,2（技巧=12）
        assertEquals(2L, zero.talent[2])                          // 素質,性別,2（性別=2）
        // 情緒/憤怒 不在 Base.csv，但 Base.als:11/:15 给出别名 → 原版经 nameToIntDics 可反查
        // （ConstantData.cs:711-737），Chara0.csv:8/:10 的 基礎,情緒,1500 / 基礎,憤怒,1000 生效
        assertEquals(1500L, zero.maxbase[10])
        assertEquals(1000L, zero.maxbase[12])
        // 灵梦（Chara1 霊夢.csv）：csvNo=1、NO=1
        val reimu = data.templates.first { it.csvNo == 1L }
        assertEquals(1L, reimu.no)
        assertEquals("博丽 灵梦", reimu.name)
        assertEquals("灵梦", reimu.callname)
        // 按 No 升序（ConstantData.cs:1513）
        assertEquals(data.templates.sortedBy { it.no }, data.templates)

        // store 注入后用变量级读取断言（对拍基准 pc-golden-root.txt:411-420：
        // CSVNAME=你 / NAME0=你 / BASE0_0=2000）
        val store = VariableStore(data.registry)
        data.templates.forEach { store.registerTemplate(it) }
        store.addCharacter(0L)
        assertEquals("你", store.readStr("NAME", longArrayOf(0)))
        assertEquals(2000L, store.readInt("BASE", longArrayOf(0, 0)))
    }

    @Test
    fun `合成数据 注释 BOM CRLF 与越界`() {
        val dir = createTempDirectory("eratw-csv")
        try {
            dir.resolve("Abl.csv").writeText(
                "\uFEFF;注释行\r\n\r\n0,Ａ\r\n1,Ｂ\r\n999,越界\r\nx,非整数\r\n2",
            )
            val warnings = ArrayList<String>()
            val table = com.eratw.port.core.vars.CsvNameTable(0, 100)
            CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)
            assertEquals("Ａ", table[0])
            assertEquals("Ｂ", table[1])
            assertEquals("", table[2])          // 列数不足 → 跳过
            assertEquals("", table[50])          // 越界（999）→ 不写
            assertTrue(warnings.any { it.contains("越界") }, warnings.toString())
            assertTrue(warnings.any { it.contains("非整数") }, warnings.toString())
            // 文件不存在 → 静默（无告警）
            val w2 = ArrayList<String>()
            CsvLoader.loadNameTable(dir, "NoSuch.csv", 0, table, null, w2)
            assertTrue(w2.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 ITEM 价格第三列`() {
        val dir = createTempDirectory("eratw-csv")
        try {
            dir.resolve("Item.csv").writeText("0,物品甲,3000\n1,物品乙,坏价格\n2,物品丙\n")
            val warnings = ArrayList<String>()
            val table = com.eratw.port.core.vars.CsvNameTable(6, 100)
            val prices = LongArray(100)
            CsvLoader.loadNameTable(dir, "Item.csv", 6, table, prices, warnings)
            assertEquals("物品甲", table[0])
            assertEquals(3000L, prices[0])
            assertEquals(0L, prices[1])         // 坏价格 → 告警且不写
            assertEquals(0L, prices[2])          // 缺第三列 → 不写
            assertTrue(warnings.any { it.contains("价格列非整数") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 角色模板 行语义`() {
        val dir = createTempDirectory("eratw-csv")
        try {
            // 名字表（BASE 槽 7）：体力=0、気力=1、射精=2
            dir.resolve("Base.csv").writeText("0,体力\n1,気力\n2,射精\n")
            // 角色文件：BOM + 注释 + 数据行前置于编号 + 重复编号 + 英文标签 + 名字反查 + 默认值 1
            dir.resolve("Chara7 测试.csv").writeText(
                "\uFEFFNAME,提前的数据\n" +
                    "番号,7,\n" +
                    "名前,测试娘,\n" +
                    "CALLNAME,测试,\n" +
                    "BASE,体力,1500\n" +
                    "BASE,気力,800\n" +
                    "BASE,不存在,999\n" +
                    "BASE,射精\n" +
                    "番号,8,\n" +
                    "TALENT,2,1\n" +
                    "CSTR,0,第一段\n" +
                    ";尾注释\n",
            )
            val data = GameDataLoader.load(dir)
            assertNotNull(data)
            val tmpl = data.templates.single()
            assertEquals(7L, tmpl.no)
            assertEquals(7L, tmpl.csvNo)               // 文件名 Chara7 → 7
            assertEquals("测试娘", tmpl.name)
            assertEquals("测试", tmpl.callname)
            assertEquals(1500L, tmpl.maxbase[0])       // 名字反查
            assertEquals(800L, tmpl.maxbase[1])
            assertEquals(1L, tmpl.maxbase[2])          // 缺第三列 → 默认 1（射精=2）
            assertNull(tmpl.maxbase[10])               // 名字表没有的键 → 跳过
            assertEquals(1L, tmpl.talent[2])           // 纯整数键 + 默认值 1
            assertEquals("第一段", tmpl.cstr[0])
            // 告警覆盖：编号前的数据行 / 重复编号 / 未定义键
            assertTrue(data.warnings.any { it.contains("编号定义前") }, data.warnings.toString())
            assertTrue(data.warnings.any { it.contains("重复定义角色编号") }, data.warnings.toString())
            assertTrue(data.warnings.any { it.contains("键未定义") }, data.warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 编码探测链 CP932 回退与失败跳过`() {
        val dir = createTempDirectory("eratw-csv-enc")
        try {
            val warnings = ArrayList<String>()
            // CP932 字节（无效 UTF-8）：0x95 0x5C = 「表」→ EncodingHandler.cs:37-40 的回退支
            java.nio.file.Files.write(
                dir.resolve("Talent.csv"),
                byteArrayOf(0x30, 0x2C, 0x95.toByte(), 0x5C, 0x0D, 0x0A),
            )
            val talent = com.eratw.port.core.vars.CsvNameTable(2, 100)
            CsvLoader.loadNameTable(dir, "Talent.csv", 2, talent, null, warnings)
            assertEquals("表", talent[0])
            assertTrue(warnings.isEmpty(), warnings.toString())

            // UTF-16LE BOM（FF FE）→ StreamReader 嗅探支（DetectEncoding :27-32）
            java.nio.file.Files.write(
                dir.resolve("Exp.csv"),
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "0,甲\n".toByteArray(Charsets.UTF_16LE),
            )
            val exp = com.eratw.port.core.vars.CsvNameTable(1, 100)
            CsvLoader.loadNameTable(dir, "Exp.csv", 1, exp, null, warnings)
            assertEquals("甲", exp[0])

            // UTF-8 / CP932 都解不动（0x80、0xFF）→ 原版 ReadAllLines 抛 → Open false →
            // FailedOpenFile 跳过整个文件（ConstantData.cs:1756-1760）
            java.nio.file.Files.write(dir.resolve("Mark.csv"), byteArrayOf(0x80.toByte(), 0xFF.toByte()))
            val mark = com.eratw.port.core.vars.CsvNameTable(5, 100)
            CsvLoader.loadNameTable(dir, "Mark.csv", 5, mark, null, warnings)
            assertEquals("", mark[0])
            assertTrue(warnings.any { it.contains("Mark.csv") && it.contains("打开文件失败") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 全角空格 行首空白字段内容`() {
        val dir = createTempDirectory("eratw-csv-u3000")
        try {
            // 行首 U+3000 = 空白（SystemAllowFullSpace 默认 true，LexicalAnalyzer.cs:677-684）；
            // 字段内 U+3000 = 内容（loadDataTo 只按逗号切分、名字列不 trim，ConstantData.cs:1802）
            dir.resolve("Palam.csv").writeText("　0,Ａ　Ｂ\n1　,乙\n")
            val warnings = ArrayList<String>()
            val table = com.eratw.port.core.vars.CsvNameTable(3, 100)
            CsvLoader.loadNameTable(dir, "Palam.csv", 3, table, null, warnings)
            assertEquals("Ａ　Ｂ", table[0])
            assertEquals(0, table.keywordIndex("Ａ　Ｂ"))
            assertNull(table.keywordIndex("Ａ"))     // U+3000 没被从名字里吃掉
            assertEquals("乙", table[1])              // 首列尾随 U+3000：.NET TryParse 视作空白
            assertTrue(warnings.isEmpty(), warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 下标越界不扩容`() {
        val dir = createTempDirectory("eratw-csv-grow")
        try {
            // 原版 names 数组一次定型（ConstantData.cs:678），越界只告警跳过（:1791-1795）
            dir.resolve("VariableSize.csv").writeText("FLAG,100\n")
            dir.resolve("Flag.csv").writeText("0,甲\n500,越界\n")
            val data = GameDataLoader.load(dir)
            assertNotNull(data)
            assertEquals(100, data.registry.sizes.maxDataList[13])   // 未被 CSV 内容扩容
            assertEquals(100, data.registry.csvTables[13].size)
            assertEquals("甲", data.registry.csvTables[13][0])
            assertTrue(data.warnings.any { it.contains("下标 500 越界") }, data.warnings.toString())
            assertTrue(data.warnings.none { it.contains("自动扩容") }, data.warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 零长槽 ProhibitedArrayName 终止本文件`() {
        val dir = createTempDirectory("eratw-csv-zero")
        try {
            // ConstantData.cs:1786-1790：array.Length == 0 → 告警 + break（不是逐行越界）
            dir.resolve("Palam.csv").writeText("0,甲\n1,乙\n")
            val warnings = ArrayList<String>()
            val table = com.eratw.port.core.vars.CsvNameTable(3, 0)
            CsvLoader.loadNameTable(dir, "Palam.csv", 3, table, null, warnings)
            assertEquals(0, table.size)
            assertEquals(1, warnings.size, warnings.toString())      // 只一条：break 生效
            assertTrue(warnings[0].contains("长度为 0"), warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
