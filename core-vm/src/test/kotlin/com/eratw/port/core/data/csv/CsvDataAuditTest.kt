package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CsvNameTable
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * DATA-AUDIT（M6-B §2.3）：CSV / .als / 角色模板装载层与 C# 的逐条对拍。
 *
 * 覆盖 2026-10-20 审计查实的四类偏差：
 * 1. 角色模板的界用的是 **CharacterIntArrayLength 具名槽**（MARK 5 / EXP 4 / ABL 2 / BASE 1 /
 *    TALENT 3 / CFLAG 9 / EQUIP 12 / JUEL 10 / RELATION 11，ConstantData.cs:1599-1666），
 *    不是 CSV 名表槽号 —— 旧实现按名表槽取界，本语料会丢 3,134 行合法模板数据；
 * 2. 名字表关键字索引按**最终数组、下标升序**建立（ConstantData.cs:711-724），同名取最小下标；
 * 3. GAMEBASE 数值键走 `tryatoi`（GameBase.cs:82-104：TryParse → 开头数字串 → 0）；
 * 4. ITEM 价格列 `long.TryParse` 容忍前导空白（ConstantData.cs:1806）。
 *
 * 合成数据用例不依赖宿主；真实语料用例从 `ERATW_CORPUS`/`ERATW_GAMEDIR` 找 `CSV` 目录，
 * 找不到就 assume-skip，不硬编码任何主机路径。
 */
class CsvDataAuditTest {

    // ---------------------------------------------------------------- 合成数据

    /**
     * 本语料 `CSV/VariableSize.csv` 角色数组段（:93-115）落成的尺寸。
     * 用来把模板界钉在与真实装载一致的数值上。
     */
    private fun corpusSizedRegistry(): VariableRegistry {
        val sizes = VariableSizes()
        fun set(code: Int, n: Int) {
            sizes.applySize(code, intArrayOf(n))
        }
        set(VariableCode.BASE, 100)
        set(VariableCode.MAXBASE, 100)
        set(VariableCode.ABL, 100)
        set(VariableCode.TALENT, 1000)
        set(VariableCode.EXP, 200)
        set(VariableCode.MARK, 100)
        set(VariableCode.PALAM, 200)
        set(VariableCode.SOURCE, 250)
        set(VariableCode.EX, 100)
        set(VariableCode.CFLAG, 10000)
        set(VariableCode.JUEL, 200)
        set(VariableCode.RELATION, 200)
        set(VariableCode.EQUIP, 100)
        set(VariableCode.TEQUIP, 1000)
        set(VariableCode.STAIN, 100)
        set(VariableCode.GOTJUEL, 200)
        set(VariableCode.TCVAR, 1000)
        set(VariableCode.CSTR, 100)
        return VariableRegistry(sizes, GameBaseData())
    }

    @Test
    fun `合成数据 角色模板界按 CharacterIntArrayLength 具名槽`() {
        val dir = createTempDirectory("eratw-chara-bound")
        try {
            dir.resolve("Chara9 界测试.csv").writeText(
                "番号,9,\n" +
                    "名前,界测试,\n" +
                    "フラグ,931,387\n" +          // CFLAG 界 = charaInt1D[9] = 10000
                    "フラグ,310,5000;地位\n" +     // 值取开头数字
                    "素質,300,1\n" +             // TALENT 界 = charaInt1D[3] = 1000
                    "素質,99,1\n" +
                    "経験,199,1\n" +             // EXP 界 = charaInt1D[4] = 200
                    "珠,199,1\n" +               // JUEL 界 = charaInt1D[10] = 200
                    "相性,199,1\n" +             // RELATION 界 = charaInt1D[11] = 200
                    "装着物,99,1\n" +            // EQUIP 界 = charaInt1D[12] = 100
                    "能力,99,1\n" +              // ABL 界 = charaInt1D[2] = 100
                    "基礎,99,1\n" +              // BASE 界 = charaInt1D[1]（MAXBASE）= 100
                    "刻印,99,1\n" +              // MARK 界 = charaInt1D[5] = 100
                    "フラグ,10000,1\n" +         // = 界 → 越界
                    "素質,1000,1\n" +            // = 界 → 越界
                    "相性,200,1\n",              // = 界 → 越界
            )
            val warnings = ArrayList<String>()
            val list = CharaCsvLoader.load(dir, corpusSizedRegistry(), warnings)
            assertEquals(1, list.size, warnings.toString())
            val t = list.single()
            assertEquals(9L, t.no)
            assertEquals("界测试", t.name)
            // 旧实现（界 = CSV 名表槽）会把这些全部丢掉：CFLAG 用 charaInt1D[15]=200、
            // TALENT 用 charaInt1D[2]=100、EXP 用 charaInt1D[1]=100。
            assertEquals(387L, t.cflag[931])
            assertEquals(5000L, t.cflag[310])
            assertEquals(1L, t.talent[300])
            assertEquals(1L, t.talent[99])
            assertEquals(1L, t.exp[199])
            assertEquals(1L, t.juel[199])
            assertEquals(1L, t.relation[199])
            assertEquals(1L, t.equip[99])
            assertEquals(1L, t.abl[99])
            assertEquals(1L, t.maxbase[99])
            assertEquals(1L, t.mark[99])
            // 恰好三条越界（= 界的边界值），RELATION 也必须有界（旧实现 dicSlot<0 分支完全无界）
            val oob = warnings.filter { it.contains("越界") }
            assertEquals(3, oob.size, warnings.toString())
            assertTrue(oob.any { it.contains("フラグ") && it.contains("10000") }, oob.toString())
            assertTrue(oob.any { it.contains("素質") && it.contains("1000") }, oob.toString())
            assertTrue(oob.any { it.contains("相性") && it.contains("200") }, oob.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 名字表关键字同名取最小下标且重复下标后写覆盖`() {
        val dir = createTempDirectory("eratw-nameidx")
        try {
            // 行序故意与下标序相反：原版按最终 names 数组、下标升序建索引（ConstantData.cs:711-724）
            dir.resolve("Abl.csv").writeText("7,B\n7,C\n5,A\n2,A\n")
            val warnings = ArrayList<String>()
            val table = CsvNameTable(0, 100)
            CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)
            assertEquals("C", table[7])              // 同下标后写覆盖（ConstantData.cs:1802）
            assertEquals("A", table[5])
            assertEquals("A", table[2])
            assertNull(table.keywordIndex("B"))      // 被覆盖掉的名字不进索引
            assertEquals(7, table.keywordIndex("C"))
            assertEquals(2, table.keywordIndex("A")) // 同名取最小下标，不是先出现的 5
            assertEquals(1, warnings.count { it.contains("已定义") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 ITEM 价格列容忍前导空白`() {
        val dir = createTempDirectory("eratw-price")
        try {
            // C# long.TryParse(source[..].TrimEnd())：TryParse 本身容前导空白（ConstantData.cs:1806）
            dir.resolve("Item.csv").writeText("0,甲, 3000\n1,乙,3000 ;备注\n")
            val warnings = ArrayList<String>()
            val table = CsvNameTable(6, 100)
            val prices = LongArray(100)
            CsvLoader.loadNameTable(dir, "Item.csv", 6, table, prices, warnings)
            assertEquals(3000L, prices[0])
            assertEquals(0L, prices[1])              // 尾随注释 → 整体解析失败，价格不写
            assertTrue(warnings.any { it.contains("价格列非整数") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 GAMEBASE tryatoi 口径`() {
        val dir = createTempDirectory("eratw-gamebase")
        try {
            dir.resolve("GameBase.csv").writeText(
                "コード, 7000\n" +
                    "バージョン,12abc\n" +              // TryParse 失败 → 退到开头数字串
                    "バージョン違い認める,abc\n" +         // 两条都失败 → 0（不是保持 -1）
                    "最初からいるキャラ,\n" +            // 空值 → 0
                    "アイテムなし, 12 \n",
            )
            val w = ArrayList<String>()
            val base = GameBaseLoader.load(dir, w)
            assertEquals(7000L, base.gameCode)
            assertEquals(12L, base.version)
            assertEquals(0L, base.allowVersion)     // GameBase.cs:141-143 失败写 0
            assertEquals(0L, base.defaultChara)     // GameBase.cs:144-146 失败写 0
            assertEquals(12L, base.noItem)
            assertTrue(w.isEmpty(), w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 GAMEBASE 代码不可解析写 0 并告警`() {
        val dir = createTempDirectory("eratw-gamebase0")
        try {
            dir.resolve("GameBase.csv").writeText("コード,abc\n")
            val w = ArrayList<String>()
            val base = GameBaseLoader.load(dir, w)
            assertEquals(0L, base.gameCode)
            assertTrue(w.any { it.contains("代码为 0") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 行口径 CR LF 全角空白与注释`() {
        val dir = createTempDirectory("eratw-lines")
        try {
            val p = dir.resolve("Lines.csv")
            // File.ReadAllLines 口径：\r\n / \r / \n 都是换行。
            // 前导空白集 = 空格 / \t，**不含 U+3000** —— LexicalAnalyzer.SkipWhiteSpace:677-683
            // 先 `if (current != '\u3000') break;` 再 `if (!Config.SystemAllowFullSpace) return num;`，
            // 本游戏 emuera.config 实测 `WHITESPACE INCLUDES FULL-WIDTH SPACE:NO`
            // ⇒ 全角空格不是空白：它后面的 `;` 不构成注释，整行作为数据行产出给下游。
            Files.write(
                p,
                (
                    "\uFEFF0,甲\r" +
                        "1,乙\r\u3000;全角空白后的注释\r" +
                        "\t;tab 注释\r\n" +
                        " 2,丙\r\n" +
                        "3,丁"
                    ).toByteArray(Charsets.UTF_8),
            )
            assertEquals(
                listOf("0,甲", "1,乙", "\u3000;全角空白后的注释", "2,丙", "3,丁"),
                CsvLoader.readEnabledLines(p),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 真实语料

    /** 语料根 → `CSV` 目录；`ERATW_CORPUS` 优先（可能指到 `.../ERB`），其次 `ERATW_GAMEDIR`。 */
    private fun corpusCsvDir(): Path? {
        for (key in listOf("ERATW_CORPUS", "ERATW_GAMEDIR")) {
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

    private fun requireCsvDir(): Path {
        val dir = corpusCsvDir()
        Assumptions.assumeTrue(dir != null, "跳过：未设置 ERATW_CORPUS/ERATW_GAMEDIR 或找不到 CSV 目录")
        return dir!!
    }

    @Test
    fun `真实语料 VariableSize 消费侧尺寸`() {
        val dir = requireCsvDir()
        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        val sizes = data.registry.sizes

        // CSV/VariableSize.csv（139 个 split 行：数据 102 / 注释 34 / 空行 3）逐段消费：
        // 名字表段 :17-87 / 角色段 :93-110 / 字符段 :114-115 / 临时变量 :119-124 / 2 维 :128-133
        assertEquals(10000, sizes.maxDataList[13])   // FLAGNAME,10000
        assertEquals(10000, sizes.maxDataList[15])   // CFLAG,10000 + CFLAGNAME,10000
        assertEquals(1000, sizes.maxDataList[4])     // TRAINNAME,1000
        assertEquals(20000, sizes.maxDataList[10])   // STR,20000
        assertEquals(1000, sizes.maxDataList[6])     // ITEMNAME,1000
        assertEquals(10000, sizes.int1D[3])          // FLAG,10000
        assertEquals(1000, sizes.int1D[60])          // ITEMPRICE ← ITEMNAME
        assertEquals(20000, sizes.str1D[1])          // STR,20000
        assertEquals(200, sizes.int1D[VariableCode.lowIndex(VariableCode.LOCAL_VAR)])
        assertEquals(200, sizes.int1D[VariableCode.lowIndex(VariableCode.ARG)])
        assertEquals(100, sizes.str1D[VariableCode.lowIndex(VariableCode.LOCALS)])
        assertEquals(1000, sizes.charaInt1D[3])      // TALENT,1000
        assertEquals(10000, sizes.charaInt1D[9])     // CFLAG,10000
        assertEquals(200, sizes.charaInt1D[10])      // JUEL,200
        assertEquals(100, sizes.charaInt1D[12])      // EQUIP,100
        assertEquals(100, sizes.charaStr1D[0])       // CSTR,100
        // DITEMTYPE,100,100（VariableSize.csv:128）→ int2D[0]
        assertEquals(listOf(100, 100), sizes.int2D[0].toList())

        // 本语料无重复定义 / 无拒绝行
        assertTrue(
            data.warnings.none { it.contains("VariableSize.csv") },
            data.warnings.filter { it.contains("VariableSize.csv") }.take(5).toString(),
        )
    }

    @Test
    fun `真实语料 29 槽名字表读数`() {
        val dir = requireCsvDir()
        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        val t = data.registry.csvTables

        // 存在且非空的槽：首项 / 尾项逐槽核对（文件行数见交付报告表格）
        assertEquals("Ｃ感覚", t[0][0]); assertEquals("TSLv", t[0][60])
        assertEquals("Ｃ経験", t[1][0]); assertEquals("站式小人飞机杯Ａ绝顶经验", t[1][138])
        assertEquals("処女", t[2][0]); assertEquals("濃厚精液殘留", t[2][711])
        assertEquals("快Ｃ", t[3][0]); assertEquals("否定", t[3][100])
        assertEquals("愛撫", t[4][0]); assertEquals("自定义指令", t[4][381])
        assertEquals("苦痛刻印", t[5][0]); assertEquals("成長", t[5][8])
        assertEquals("跳蛋", t[6][0]); assertEquals("永久凍土欠片", t[6][998])
        assertEquals("体力", t[7][0]); assertEquals("絶頂余韻", t[7][35])
        assertEquals("快Ｃ", t[8][0]); assertEquals("魔力印记", t[8][233])
        assertEquals("Ｃ絶頂", t[9][0]); assertEquals("妊娠判定用精液量", t[9][80])
        assertEquals("鳥居", t[10][1]); assertEquals("武力", t[10][19585])
        assertEquals("飾品", t[11][1]); assertEquals("腕部装束", t[11][22])
        assertEquals("下半身着衣状況", t[12][0]); assertEquals("PREV_PANTY_STATE", t[12][905])
        assertEquals("休憩FLAG", t[13][0]); assertEquals("DEFAULT_PREGNANCY_MODE", t[13][9126])
        assertEquals("射精部位", t[14][1]); assertEquals("WeddingNight", t[14][917])
        assertEquals("既成事実", t[15][1]); assertEquals("LatestEggGiftDay", t[15][9173])
        assertEquals("射精部位", t[16][2]); assertEquals("口服避孕药（假）", t[16][998])
        assertEquals("FIRSTTIME", t[17][0]); assertEquals("童貞喪失履歴", t[17][48])
        assertEquals("口", t[18][0]); assertEquals("腸内", t[18][7])
        assertEquals("総日数", t[26][0]); assertEquals("依頼期限５", t[26][45])
        assertEquals("時間", t[27][0]); assertEquals("虹", t[27][7])

        // 缺文件的 7 槽（Cdflag1/Cdflag2/Tstr/Savestr/Global/Globals/Money）：空表且不扩容
        for (slot in listOf(19, 20, 22, 23, 24, 25, 28)) {
            assertEquals("", t[slot][0], "槽 $slot 应为空表")
        }
        // Strname.csv 存在但 0 条数据行（整文件只有 BOM + 注释）→ 空表但容量仍是缺省 20000
        assertEquals("", t[21][0])
        assertEquals(20000, t[21].size)

        // ITEM 价格（第 3 列）：0,跳蛋,3000, / 998,永久凍土欠片,99999,;cirno danmaku item
        assertEquals(3000L, data.itemPrices[0])
        assertEquals(99999L, data.itemPrices[998])

        // Train.csv 同文件内「自慰」有两个下标 9/451，「外出」405/699 → 取最小下标
        assertEquals(9, t[4].keywordIndex("自慰"))
        assertEquals(405, t[4].keywordIndex("外出"))
        // .als 别名只补空：CFLAG.als 的 9173 与 CSV 同名，取 CSV 侧
        assertEquals(9173, t[15].keywordIndex("LatestEggGiftDay"))
    }

    @Test
    fun `真实语料 角色模板界与装载告警`() {
        val dir = requireCsvDir()
        val data = GameDataLoader.load(dir)
        assertNotNull(data)

        // 168 个 CHARA*.csv（CSV/Chara/ 子目录），No 0..167 各一，按 No 升序
        assertEquals(168, data.templates.size)
        assertEquals(0L, data.templates.first().no)
        assertEquals(167L, data.templates.last().no)
        assertEquals(data.templates.sortedBy { it.no }, data.templates)

        // 灵梦：フラグ,弾幕特能,387 → CFLAG 931（≥ 旧界 200）；素質,酒耐性,1 → TALENT 121（≥ 旧界 100）；
        // フラグ,来訪時間,540;9時 → CFLAG 352 且注释被截断
        val reimu = data.templates.first { it.no == 1L }
        assertEquals("博丽 灵梦", reimu.name)
        assertEquals(387L, reimu.cflag[931])
        assertEquals(5000L, reimu.cflag[310])
        assertEquals(540L, reimu.cflag[352])
        assertEquals(1L, reimu.talent[121])

        // 审计口径：本语料装载全程 0 条告警（越界/重复下标/自动扩容/价格/VariableSize 都没有）
        assertTrue(data.warnings.isEmpty(), data.warnings.take(8).toString())
        assertTrue(
            data.warnings.none { it.contains("越界") },
            data.warnings.filter { it.contains("越界") }.take(5).toString(),
        )
        assertTrue(
            data.warnings.none { it.contains("已定义") },
            data.warnings.filter { it.contains("已定义") }.take(5).toString(),
        )
        assertTrue(
            data.warnings.none { it.contains("自动扩容") },
            data.warnings.filter { it.contains("自动扩容") }.take(5).toString(),
        )
    }
}
