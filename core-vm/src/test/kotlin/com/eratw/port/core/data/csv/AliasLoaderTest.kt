package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CsvNameTable
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
 * `.als` 别名装载测试（ALIAS-LOAD）。
 *
 * 语义权威：`ConstantData.loadAliases`（ConstantData.cs:1841-1903）与调用点
 * `ConstantData.loadDataTo`（:1834-1838）；合并口径 `ConstantData.cs:711-737`
 * （CSV 名先入、别名只在键不存在时补）。
 *
 * 分两层：合成 CSV+ALS 锁行语义边角；真实语料（`ERATW_CORPUS`）锁装载规模与样例。
 * 语料缺失时 assume-skip，不硬编码任何主机绝对路径。
 */
class AliasLoaderTest {

    /**
     * 从 `ERATW_CORPUS` 向上找含 `CSV` 子目录的语料根；兼容变量直接指向 `.../ERB`
     * 或游戏根，也兼容直接指向 `.../CSV`。
     */
    private fun corpusCsvDir(): Path? {
        val raw = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var cur: Path? = Path.of(raw).toAbsolutePath().normalize()
        repeat(4) {
            val dir = cur ?: return null
            if (dir.fileName?.toString().equals("CSV", ignoreCase = true) && Files.isDirectory(dir)) {
                return dir
            }
            val nested = dir.resolve("CSV")
            if (Files.isDirectory(nested)) return nested
            cur = dir.parent
        }
        return null
    }

    private fun requireCsvDir(): Path {
        val dir = corpusCsvDir()
        Assumptions.assumeTrue(dir != null, "跳过：未设置 ERATW_CORPUS 或该目录下找不到 CSV 目录")
        return dir!!
    }

    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = createTempDirectory("eratw-als")
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `合成数据 别名并入关键字索引且 CSV 名优先`() = withTempDir { dir ->
        // 文件名大小写故意与调用名不同：resolveFile 必须按目录项大小写不敏感命中。
        dir.resolve("BASE.CSV").writeText("\uFEFF0,体力\n1,気力\n")
        dir.resolve("base.als").writeText(
            "\uFEFF;注释行\n" +
                "\n" +
                "0,体力\n" +          // 与 CSV 名相同 → 静默补空（原版只查别名字典，:1877）
                "1, 气力 \n" +        // 两侧空白 → trim（:1876）
                "0,情绪\n" +
                "10,情緒\n" +
                "2,ローター,3000,\n", // 第 3 列起忽略（Item.als 的真实形态）
        )
        val warnings = ArrayList<String>()
        val table = CsvNameTable(7, 100)
        CsvLoader.loadNameTable(dir, "Base.csv", 7, table, null, warnings)

        assertEquals("体力", table[0])                      // CSV 名字数组不被别名改写
        assertEquals("気力", table[1])
        assertEquals(0, table.keywordIndex("体力"))          // CSV 名先入，别名不覆盖
        assertEquals(1, table.keywordIndex("気力"))
        assertEquals(1, table.keywordIndex("气力"))          // 只有 .als 才有的简写
        assertEquals(0, table.keywordIndex("情绪"))
        assertEquals(10, table.keywordIndex("情緒"))
        assertEquals(2, table.keywordIndex("ローター"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `合成数据 重复别名与坏行告警`() = withTempDir { dir ->
        dir.resolve("Abl.csv").writeText("0,Ａ\n")
        dir.resolve("Abl.als").writeText(
            "5,别名甲\n" +
                "6,别名甲\n" +      // 同文件内重复 → 告警且保留首次序号（:1877-1884）
                "x,坏序号\n" +      // 首列非 int → 告警
                "7\n" +             // 列数 < 2 → 告警
                ",空序号\n" +        // 首列空串 → 非 int 告警
                "0,Ａ\n",            // 与 CSV 名相同 → 静默
        )
        val warnings = ArrayList<String>()
        val table = CsvNameTable(0, 100)
        CsvLoader.loadNameTable(dir, "Abl.csv", 0, table, null, warnings)

        assertEquals(5, table.keywordIndex("别名甲"))        // 首次序号生效
        assertNull(table.keywordIndex("坏序号"))
        assertNull(table.keywordIndex("空序号"))
        assertEquals(4, warnings.size, warnings.toString())
        assertEquals(1, warnings.count { it.contains("已被定义") }, warnings.toString())
        assertEquals(1, warnings.count { it.contains("列数不足") }, warnings.toString())
        assertEquals(2, warnings.count { it.contains("首列非整数") }, warnings.toString())
    }

    @Test
    fun `合成数据 别名序号越界不校验`() = withTempDir { dir ->
        dir.resolve("Mark.csv").writeText("0,已有\n")
        dir.resolve("Mark.als").writeText("999,越界别名\n-3,负序号\n")
        val warnings = ArrayList<String>()
        val table = CsvNameTable(5, 100)
        CsvLoader.loadNameTable(dir, "Mark.csv", 5, table, null, warnings)

        // 原版 dictionary.Add 只记序号（:1883），范围由使用端检查；CsvNameTable.setKeyword 同样不校验。
        assertEquals(999, table.keywordIndex("越界别名"))
        assertEquals(-3, table.keywordIndex("负序号"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `合成数据 CSV 缺失时别名静默不装载`() = withTempDir { dir ->
        dir.resolve("Orphan.als").writeText("0,孤儿别名\n")
        val warnings = ArrayList<String>()
        val orphan = CsvNameTable(5, 100)
        CsvLoader.loadNameTable(dir, "Orphan.csv", 5, orphan, null, warnings)
        // C# loadDataTo 在 File.Exists 检查处就返回（:1749-1752），别名文件不会被读。
        assertNull(orphan.keywordIndex("孤儿别名"))
        assertTrue(warnings.isEmpty(), warnings.toString())

        dir.resolve("Solo.csv").writeText("0,独行\n")
        val solo = CsvNameTable(5, 100)
        CsvLoader.loadNameTable(dir, "Solo.csv", 5, solo, null, warnings)
        // 同名 .als 缺失 → 静默（C# `if (File.Exists(text))`，:1835）。
        assertEquals(0, solo.keywordIndex("独行"))
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `真实语料 als 装载规模与样例`() {
        val dir = requireCsvDir()
        val physical = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().endsWith(".als", ignoreCase = true) }.toList()
        }
        assertEquals(19, physical.size, "语料 CSV 目录应有 19 个 .als")

        // 29 槽里有同名 .als 的槽数。Juel.csv/.als 都存在，但 JUEL 不在原版 29 槽装载清单里
        // （ConstantData.cs:682-710 没有 JUEL.CSV），故 Juel.als 无槽可入、原版同样不装载；
        // JUEL 关键字用的是 PALAM 名字表（ConstantData.cs:943-949 / :1654-1659）。
        val loadable = GameDataLoader.SLOT_FILES.count { f ->
            CsvLoader.resolveFile(dir, "${f.substringBeforeLast('.')}.als") != null
        }
        assertEquals(18, loadable)
        assertNotNull(CsvLoader.resolveFile(dir, "Juel.csv"))
        assertNotNull(CsvLoader.resolveFile(dir, "Juel.als"))
        assertTrue(GameDataLoader.SLOT_FILES.none { it.equals("Juel.csv", ignoreCase = true) })

        val data = GameDataLoader.load(dir)
        assertNotNull(data)
        val tables = data.registry.csvTables

        // Abl.als: 9,亲密（CSV 是繁体 親密）；42,战斗能力（CSV 是 戦闘能力）
        assertEquals(9, tables[0].keywordIndex("亲密"))
        assertEquals(9, tables[0].keywordIndex("親密"))
        assertEquals(42, tables[0].keywordIndex("战斗能力"))
        // Base.als: 0,体力 与 CSV 同名；10,情绪 / 10,情緒（CSV 是 ムード）
        assertEquals(0, tables[7].keywordIndex("体力"))
        assertEquals(10, tables[7].keywordIndex("情绪"))
        assertEquals(10, tables[7].keywordIndex("情緒"))
        // Item.als: 0,ローター（与 CSV 同名），别名列后多余的金额列被忽略
        assertEquals(0, tables[6].keywordIndex("ローター"))
        // ex.als: 6,二重绝顶（CSV 是 二重絶頂）
        assertEquals(6, tables[9].keywordIndex("二重绝顶"))

        // GETNUM 路径（VarMethods.kt:476-483 → VariableRegistry.keywordIndex）。
        assertEquals(9, data.registry.keywordIndex("ABL", 1, "亲密"))
        assertEquals(10, data.registry.keywordIndex("BASE", 1, "情绪"))

        // CHARA*.csv 模板键反查：168 个 Chara 文件都有 基礎,情緒,1500 / 基礎,憤怒,1000，
        // 这两行只有经 Base.als（:11/:15）才能命中（Base.csv 的 10 是 ムード、12 是 怒り）。
        val zero = data.templates.first { it.no == 0L }
        assertEquals(1500L, zero.maxbase[10])
        assertEquals(1000L, zero.maxbase[12])
        assertTrue(
            data.warnings.none { it.contains("情緒") || it.contains("憤怒") || it.contains("已被定义") },
            data.warnings.toString(),
        )
    }
}
