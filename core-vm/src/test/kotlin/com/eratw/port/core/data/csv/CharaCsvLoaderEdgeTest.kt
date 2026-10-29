package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.VariableRegistry
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHARA CSV 的边缘面（语料里不出现、但换游戏/换目录布局就会碰到的那些）：
 * 多份同 No 的合并序、列数不齐、空行/注释/BOM/CP932、文件名编号、`@n` 标签。
 * 语料实测读数不变（168 文件 / 10,708 启用行）由 corpusSmoke 守，这里只钉合成边界。
 *
 * spec: `ConstantData.cs:1257-1521 loadCharacterData/loadCharacterDataFile` +
 * `:1568-1745 toCharacterTemplate`；文件序 = `Config.cs:654-712 getFiles`。
 */
class CharaCsvLoaderEdgeTest {

    private fun tempDir(): Path = createTempDirectory("eratw-chara-edge")

    private fun load(dir: Path, warnings: MutableList<String> = ArrayList()) =
        CharaCsvLoader.load(dir, VariableRegistry(), warnings)

    /**
     * 同 No 的多份文件是**追加**不是覆盖：原版逐文件 `CharacterTmplList.Add`（:1492），
     * 同 No 只在 :1291-1301 报 DuplicateCharaDefine（level 1），两份模板都留着；
     * 最终 `Sort` 按 No（:1503）⇒ 同 No 的先后 = 文件序。
     */
    @Test
    fun `同名多份角色 CSV 追加而非覆盖`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara1 甲.csv").writeText("番号,1\n名前,甲\n")
            dir.resolve("Chara2 乙.csv").writeText("番号,1\n名前,乙\n")
            val t = load(dir)
            assertEquals(listOf(1L, 1L), t.map { it.no })
            assertEquals(listOf("甲", "乙"), t.map { it.name })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 文件序 = `Config.getFiles` 的深度优先前序（Config.cs:678-712：本层文件按名序先出，
     * 再按目录名序递归），**不是**全路径字典序 —— 后者会把 `Chara0/Chara5.csv` 排到 `Chara9.csv` 前。
     * 顺序只影响同 No 模板的先后，所以用同 No 两份来观察。
     */
    @Test
    fun `文件序为本层先于子目录`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara9.csv").writeText("番号,1\n名前,根\n")
            dir.resolve("Chara0").createDirectories()
            dir.resolve("Chara0/Chara5.csv").writeText("番号,1\n名前,子\n")
            assertEquals(listOf("根", "子"), load(dir).map { it.name })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 列数不齐：两列数值行补 1（:1735-1738），单列行 MissingComma 跳过（:1453-1456）。 */
    @Test
    fun `两列数值行值补一而单列行跳过`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara3.csv").writeText("番号,9\n基礎,0\n基礎,1,\n名前,甲\n名前\n")
            val w = ArrayList<String>()
            val t = load(dir, w).single()
            assertEquals(mapOf(0 to 1L, 1 to 1L), t.maxbase)
            assertEquals("甲", t.name)
            assertTrue(w.any { it.contains("列数不足") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 两列文本行的第 2 列即原文：`名前,` 把名字写成空串（:1583-1598 无条件赋值）。 */
    @Test
    fun `两列文本行空值即空名`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara4.csv").writeText("番号,9\n名前,甲\n名前,\n")
            assertEquals("", load(dir).single().name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 首列为空 → StartedComma 跳过（:1457-1460），后续行照常。 */
    @Test
    fun `首列为空的行跳过`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara5.csv").writeText("番号,9\n,x\n名前,甲\n")
            val w = ArrayList<String>()
            assertEquals("甲", load(dir, w).single().name)
            assertTrue(w.any { it.contains("行首为空") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 空行 / 纯注释行（含前导空格或 tab 的注释）全被 ReadEnabledLine 吃掉，不产生告警
     * （EraStreamReader.cs:96-187：SkipWhiteSpace 后 EOS 即跳过）。
     * BOM 由解码器消费（EncodingHandler.cs:23-26 / Preload.cs:29-32）。
     */
    @Test
    fun `空行注释行与 BOM 均不产生告警`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara6.csv").writeText("\uFEFF番号,9\n\n;注释\n\t\t;注释二\n   \n名前,甲\n")
            val w = ArrayList<String>()
            val t = load(dir, w).single()
            assertEquals("甲", t.name)
            assertTrue(w.isEmpty(), w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 非 UTF-8 回落 CP932（EncodingHandler.cs:37-40）；带 BOM 的文件**不**回落（Preload.cs:29-33）。 */
    @Test
    fun `CP932 回落与 BOM 文件不回落`() {
        val dir = tempDir()
        try {
            val cp932 = Charset.forName("windows-31J")
            dir.resolve("Chara7.csv").writeBytes("番号,9\n名前,テスト\n".toByteArray(cp932))
            assertEquals("テスト", load(dir).single().name)

            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            dir.resolve("Chara8.csv").writeBytes(bom + "番号,8\n名前,テスト\n".toByteArray(cp932))
            val w = ArrayList<String>()
            // 原版：Preload 走 UTF8BOMEncoding 严格解码失败 → AbnormalEncode 告警 + null 行 →
            // OpenOnCache 里 NullReference → :1505-1516 吃掉 ⇒ 该文件 0 个模板。
            assertEquals(listOf(9L), load(dir, w).map { it.no })
            assertTrue(w.any { it.contains("打开文件失败") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * CHARA CSV **没有** `@n` 语义：:1581-1674 是对整串（大写后）精确匹配的 switch，
     * `フラグ@1` 落 default → CanNotInterpreted 跳过（:1671-1673）。
     * 语料 168 个文件 0 行带 `@`（2026-10-24 实测），所以只在合成用例里体现。
     */
    @Test
    fun `at n 后缀标签不识别`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara9.csv").writeText("番号,9\nフラグ@1,1,5\nフラグ,1,7\n")
            val w = ArrayList<String>()
            val t = load(dir, w).single()
            assertEquals(mapOf(1 to 7L), t.cflag)
            assertTrue(w.any { it.contains("无法识别的标签") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 文件名编号的数字集是 `char.IsDigit`（Unicode Nd，:1479），但 `Convert.ToInt64`（:1486）
     * 只吃 ASCII ⇒ 全角数字 / 超 Long 范围抛异常 → :1505-1516 的 catch：模板未 Add（:1492 在后）、
     * 本文件剩余行不读 ⇒ 该文件 0 个模板。移植侧此前会漏 NumberFormatException。
     */
    @Test
    fun `文件名编号非 ASCII 数字时中止本文件`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara１.csv").writeText("番号,9\n名前,甲\n")
            dir.resolve("Chara99999999999999999999.csv").writeText("番号,8\n名前,乙\n")
            dir.resolve("Chara10.csv").writeText("番号,7\n名前,丙\n")
            val w = ArrayList<String>()
            assertEquals(listOf(7L), load(dir, w).map { it.no })
            assertEquals(2, w.count { it.contains("无法解析") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 数值键负数 / 越界都走 OoRArray 跳过（:1687-1691，界 = CharacterIntArrayLength[1] = 100）。 */
    @Test
    fun `数值键负数与越界跳过`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara11.csv").writeText("番号,9\n基礎,-1,5\n基礎,100,5\n基礎,99,5\n")
            val w = ArrayList<String>()
            assertEquals(mapOf(99 to 5L), load(dir, w).single().maxbase)
            assertEquals(2, w.count { it.contains("越界") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 同文件第二个 `番号` 只跳过**该行**（:1463-1467 的 continue），后续行仍追加进第一个模板 ——
     * 不是另起一个模板、也不是中止文件。
     */
    @Test
    fun `重复编号行只跳过该行后续仍归首个模板`() {
        val dir = tempDir()
        try {
            dir.resolve("Chara12.csv").writeText("番号,9\n名前,甲\n番号,10\n名前,乙\n")
            val w = ArrayList<String>()
            val t = load(dir, w).single()
            assertEquals(9L, t.no)
            assertEquals("乙", t.name)
            assertTrue(w.any { it.contains("重复定义角色编号") }, w.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
