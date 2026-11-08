package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableRegistry
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CHARA CSV 装载语义锁定（ConstantData.cs:1431-1745）。
 *
 * 名字表由本测试先经 [CsvLoader.loadNameTable] 装入 registry（原版顺序：
 * LoadData 先 29 槽后 loadCharacterData，ConstantData.cs:676-738）。
 */
class CharaCsvLoaderTest {

    private fun tempDir(): Path = createTempDirectory("eratw-chara")

    /** 装 Base.csv（槽 7）作为名字反查来源，再装载 CHARA 文件。 */
    private fun loadWithBase(dir: Path, chara: String, baseCsv: String = "0,体力\n1,気力\n2,射精\n"): Pair<List<CharacterTemplate>, MutableList<String>> {
        val warnings = ArrayList<String>()
        val registry = VariableRegistry()
        dir.resolve("Base.csv").writeText(baseCsv)
        CsvLoader.loadNameTable(dir, "Base.csv", 7, registry.csvTables[7], null, warnings)
        dir.resolve("Chara9 测试.csv").writeText(chara)
        val templates = CharaCsvLoader.load(dir, registry, warnings)
        return templates to warnings
    }

    /**
     * 名字反查键是**未 trim** 的原串（ConstantData.cs:1695 `TryGetValue(tokens[1])`）；
     * 只有数字键吃 TrimEnd（:1686）。行首空白已被 SkipWhiteSpace 剥掉、字段内空白保留。
     */
    @Test
    fun `名字反查键保留字段内空白`() {
        val dir = tempDir()
        try {
            val (templates, warnings) = loadWithBase(
                dir,
                "番号,9\n基礎,体力 ,1500\n基礎,気力,1200\n",
            )
            val t = templates.single()
            assertNull(t.maxbase[0])          // 「体力␣」带尾空格 → 原版匹配失败 → 跳过
            assertEquals(1200L, t.maxbase[1])
            assertTrue(warnings.any { it.contains("键未定义") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 同一下标重复：VarKeyAreadyDefined 告警 + 后写覆盖（ConstantData.cs:1739-1743）。 */
    @Test
    fun `数值标签重复下标告警且覆盖`() {
        val dir = tempDir()
        try {
            val (templates, warnings) = loadWithBase(dir, "番号,9\n能力,12,1\n能力,12,5\n")
            assertEquals(5L, templates.single().abl[12])
            assertTrue(warnings.any { it.contains("重复定义") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 官方 bug 复刻（ADR-005）：CSTR 缺第 3 列 → MissingThirdIdentifier 告警（:1723-1726）后
     * tokens[2] 越界崩（:1731）→ catch（:1505-1511）⇒ 本文件剩余行不再处理。
     */
    @Test
    fun `CSTR 缺第三列中止本文件`() {
        val dir = tempDir()
        try {
            val (templates, warnings) = loadWithBase(
                dir,
                "番号,9\nCSTR,0\n名前,後続無視\nCSTR,1,二番目\n",
            )
            val t = templates.single()
            assertEquals(9L, t.no)
            assertEquals("", t.name)          // 中止后的「名前」行被跳过
            assertTrue(t.cstr.isEmpty())
            assertTrue(warnings.any { it.contains("缺少第 3 列") }, warnings.toString())
            assertTrue(warnings.any { it.contains("剩余行不再读") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** CSTR 重复下标同样先告警再覆盖（ConstantData.cs:1727-1731）。 */
    @Test
    fun `CSTR 重复下标告警且覆盖`() {
        val dir = tempDir()
        try {
            val (templates, warnings) = loadWithBase(dir, "番号,9\nCSTR,0,一\nCSTR,0,二\n")
            assertEquals("二", templates.single().cstr[0])
            assertTrue(warnings.any { it.contains("重复定义") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 未识别标签告警 CanNotInterpreted（ConstantData.cs:1671-1673）。 */
    @Test
    fun `未识别标签告警`() {
        val dir = tempDir()
        try {
            val (templates, warnings) = loadWithBase(dir, "番号,9\n怪标签,1,2\n基礎,体力,10\n")
            assertEquals(10L, templates.single().maxbase[0])   // 未识别行不影响后续
            assertTrue(warnings.any { it.contains("无法识别的标签") }, warnings.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // B6（2026-11-06）：同层排序 = 全路径 + ja-JP 文化比较（Config.cs:654-712 / :681）
    // ------------------------------------------------------------------

    /**
     * 观测通道：最终 `sortBy no` 是**稳定**排序 ⇒ 同 `番号` 的模板保持文件序 ⇒
     * 用同名番号 + 可分辨的 `名前` 把文件序读出来（类 KDoc「顺序只在同一 No 多份模板时
     * 可见」的口径）。
     */
    private fun namesOf(dir: Path): List<String> {
        val warnings = ArrayList<String>()
        return CharaCsvLoader.load(dir, VariableRegistry(), warnings).map { it.name }
    }

    /**
     * 深度优先前序 + 同层 ja-JP 文化序（B6 翻案，旧实现是 ordinal 文件名序）：
     * - 根层 `Chara9.csv` 先于全部子目录递归（Config.cs:683-704 文件循环在目录循环前）；
     * - 子目录序按文化比较：`Charaア < Charaい`（五十音 a 行先于 i 行；ordinal 是
     *   い 0x3044 < ア 0x30A2 反序 —— 旧实现会给出 い→ア，本用例钉的就是这个翻案）。
     * 依据：Config.cs:681/:697 的 `Array.Sort`（Comparer&lt;string&gt;.Default = CurrentCulture），
     * 文化取值链见 CharaCsvLoader.PATH_COLLATOR 的 KDoc。
     */
    @Test
    fun `同层子目录按 ja-JP 文化序 递归 根层文件先出`() {
        val root = tempDir()
        try {
            root.resolve("Chara9.csv").writeText("番号,1\n名前,根层\n")
            val dirI = root.resolve("Charaい").also { it.toFile().mkdirs() }
            dirI.resolve("Chara5.csv").writeText("番号,1\n名前,い行\n")
            val dirA = root.resolve("Charaア").also { it.toFile().mkdirs() }
            dirA.resolve("Chara0.csv").writeText("番号,1\n名前,ア行\n")
            assertEquals(listOf("根层", "ア行", "い行"), namesOf(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * 同层 ASCII 分叉（NTFS 安全形）：文化序 `CharaAa.csv < CharaAB.csv`
     * （第 2 字母按主权重 a&lt;b），ordinal 是 `B`(0x42) &lt; `a`(0x61) 反序 ——
     * JVM Temurin 17 探针实测（2026-11-06）：ordinal compare = -31、collator = +1。
     * （纯同字母大小写分叉 `Charaa`/`CharaA` 在 NTFS 上被大小写不敏感合并成同一文件，
     * 装置用例不可构造，改用这组。）
     */
    @Test
    fun `同层 ASCII 混合大小写分叉按文化序`() {
        val root = tempDir()
        try {
            root.resolve("CharaAB.csv").writeText("番号,2\n名前,大写尾\n")
            root.resolve("CharaAa.csv").writeText("番号,2\n名前,小写尾\n")
            assertEquals(listOf("小写尾", "大写尾"), namesOf(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
