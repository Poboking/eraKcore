package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * U8 警告旗标接线（erbcc 编译警告面）的定向单测。
 *
 * 覆盖三键（判定与 C# 锚点见 [CorpusCompile] 收尾段 W1/W2/W3 注释）：
 * 1. **出厂零漂移**：显式写全出厂值（ConfigData.cs 注册缺省）的 emuera.config 与「无配置文件」
 *    两次编译，warnings / dimWarnings / notes 逐项一致 —— 硬验收；
 * 2. **DisplayWarningLevel**（ParserMediator.cs:54/:111/:132）：level 2 压 level 1、level 3 全静默；
 * 3. **WarnBackCompatibility**（ParserMediator.cs:132）：NO 押掉 REPEAT ≤0 告警（ArgumentParser.cs:1221）；
 * 4. **FunctionNotFoundWarning**（ErbLoader.cs:956-993）：DISPLAY 逐目标、ONCE 每文件首个、
 *    数字串常量按底层序数折；汇总行门复刻 ErbLoader.cs:904-907（挂在
 *    FunctionNotCalledWarning != IGNORE 上，压制计数 > 0 才打 —— IGNORE 全压制、
 *    ONCE 同文件后续撞点也计入）。
 */
class WarnFlagWiringTest {

    @TempDir
    lateinit var tmp: Path

    private val corpusRoot: Path get() = tmp.resolve("corpus")

    /**
     * 单文件语料（行号故意安排成 `REPEAT 0` 在第 3 行、`CALL "UNDEF_TARGET"` 在第 6 行）：
     * 一条 backcompat 告警 + 一个未定义静态 CALL 目标，两个闸各有一个可观测对象。
     */
    private fun writeSingleCorpus() {
        val root = corpusRoot
        Files.createDirectories(root)
        val text = listOf(
            "@MAIN_FUNC",
            "PRINTL MAIN",
            "REPEAT 0",
            "PRINTL IN",
            "REND",
            "CALL \"UNDEF_TARGET\"",
            "PRINTL AFTER",
        ).joinToString("\n")
        Files.write(root.resolve("01_main.ERB"), text.toByteArray(Charsets.UTF_8))
    }

    private fun writeConfig(lines: List<String>) {
        Files.write(tmp.resolve("emuera.config"), lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    // emuera.config 的键是 ConfigData 注册英文标签（C# ConfigData.cs:262-284 GetConfigItem
    // 的 EngText 分支 —— 枚举名如 DisplayWarningLevel 因大小写不匹配不被接受，erbcc 同口径，
    // 见 core-vm ConfigSpec.kt:77-85 matchesUpperKey）。
    private val kWarnLevel = "Minimum warning level"
    private val kBackCompat = "Eramaker compatibility warning"
    private val kFnf = "Function is not found warning"
    private val kFnc = "Function not called warning"

    private fun triples(result: CompileResult) =
        result.warnings.map { Triple(it.file, it.line, it.message) }

    /** REPEAT ≤0 backcompat 告警的文案后缀（语句层告警带「REPEAT命令:」前缀）。 */
    private val repeatWarnSuffix = "检测到REPEAT重复的次数为0或以下(eramaker会抛出一个错误)"
    private val notFoundPrefix = "没有找到指定的函数\"@"

    // ------------------------------------------------------------------
    // 1) 出厂零漂移（硬验收）
    // ------------------------------------------------------------------

    @Test
    fun `factory config compiles identical to no config`() {
        writeSingleCorpus()
        val noCfg = CorpusCompile.compile(corpusRoot)
        assertTrue(
            noCfg.ok,
            "failures=" + noCfg.failures.joinToString(" | ") { "${it.file}:${it.line}:${it.message}" },
        )
        // 基线本身必须真的有可闸对象：REPEAT ≤0 告警在、编译有可观测产物
        assertTrue(
            triples(noCfg).any { it.third.endsWith(repeatWarnSuffix) },
            "warnings=" + triples(noCfg).joinToString(" | ") { "${it.first}:${it.second}:${it.third}" },
        )
        assertTrue(noCfg.notes.isNotEmpty())

        writeConfig(
            listOf(
                "$kWarnLevel:1",
                "$kBackCompat:YES",
                "$kFnf:IGNORE",
                "$kFnc:IGNORE",
                "DisplayReport:NO",
            ),
        )
        val factory = CorpusCompile.compile(corpusRoot)
        assertTrue(factory.ok)
        assertEquals(triples(noCfg), triples(factory))
        assertEquals(noCfg.dimWarnings.map { it.message }, factory.dimWarnings.map { it.message })
        assertEquals(noCfg.notes, factory.notes)
        assertEquals(noCfg.failures.map { "${it.file}:${it.line}:${it.message}" }, factory.failures.map { "${it.file}:${it.line}:${it.message}" })
    }

    // ------------------------------------------------------------------
    // 2) DisplayWarningLevel（W1）
    // ------------------------------------------------------------------

    @Test
    fun `display warning level 2 silences level 1 warnings`() {
        writeSingleCorpus()
        writeConfig(listOf("$kWarnLevel:2"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok, "notes=" + r.notes.joinToString(" | "))
        assertTrue(
            triples(r).none { it.third.endsWith(repeatWarnSuffix) },
            "notes=" + r.notes.joinToString(" | "),
        )
    }

    @Test
    fun `display warning level 3 silences everything`() {
        writeSingleCorpus()
        writeConfig(listOf("$kWarnLevel:3"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(triples(r).isEmpty())
        assertTrue(r.dimWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3) WarnBackCompatibility（W2）
    // ------------------------------------------------------------------

    @Test
    fun `warn back compatibility off drops repeat warning`() {
        writeSingleCorpus()
        writeConfig(listOf("$kBackCompat:NO"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(triples(r).none { it.third.endsWith(repeatWarnSuffix) })
    }

    @Test
    fun `warn back compatibility on keeps repeat warning`() {
        writeSingleCorpus()
        writeConfig(listOf("$kBackCompat:YES"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(triples(r).any { it.third.endsWith(repeatWarnSuffix) })
    }

    // ------------------------------------------------------------------
    // 4) FunctionNotFoundWarning（W3）
    // ------------------------------------------------------------------

    @Test
    fun `fnf display emits per unique target with site`() {
        writeSingleCorpus()
        writeConfig(listOf("$kFnf:DISPLAY"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok, "warnings=" + triples(r).joinToString(" | ") { "${it.first}:${it.second}:${it.third}" })
        val hits = triples(r).filter { it.third.startsWith(notFoundPrefix) }
        assertEquals(1, hits.size)
        // erbcc 语句行是 0 基（core-vm LineParser.kt:54 LogicalLine.lineNo 口径）：文件第 6 行 → 5
        assertEquals(5, hits[0].second)
        assertTrue(hits[0].first.contains("01_main"))
    }

    @Test
    fun `fnf numeric constant maps to underlying ordinal`() {
        writeSingleCorpus()
        // ConfigValue.kt:80-86：数字串按底层值解析（3 = DISPLAY），落逐点告警分支
        writeConfig(listOf("$kFnf:3"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(triples(r).any { it.third.startsWith(notFoundPrefix) })
    }

    @Test
    fun `fnf once emits only first file`() {
        val root = corpusRoot
        Files.createDirectories(root)
        fun erb(name: String, vararg lines: String) {
            Files.write(root.resolve(name), lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        }
        erb("01_a.ERB", "@A_FUNC", "CALL \"MISS_A\"")
        erb("02_b.ERB", "@B_FUNC", "CALL \"MISS_B\"")
        writeConfig(listOf("$kFnf:ONCE"))
        val r = CorpusCompile.compile(root)
        assertTrue(r.ok, "warnings=" + triples(r).joinToString(" | ") { "${it.first}:${it.second}:${it.third}" })
        // C# ErbLoader.cs:843-853 的 ONCE 口径 = 每文件首个撞点告警：两个文件各告一次
        val hits = triples(r).filter { it.third.startsWith(notFoundPrefix) }
        assertEquals(2, hits.size)
        // 0 基行：文件第 2 行 → 1
        assertTrue(hits.all { it.second == 1 })
        assertTrue(hits.any { it.first.contains("01_a") })
        assertTrue(hits.any { it.first.contains("02_b") })
    }

    @Test
    fun `fnf ignore summary gated by function not called warning`() {
        writeSingleCorpus()
        // ErbLoader.cs:904-907：FNF 汇总行门 = DisplayWarningLevel<=2 且 FunctionNotCalledWarning != IGNORE
        writeConfig(listOf("$kFnc:LATER"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(triples(r).none { it.third.startsWith(notFoundPrefix) })
        assertTrue(r.notes.any { it == "警告Lv2:已忽略1个调用了未定义函数的警告" })

        writeConfig(listOf("$kFnc:IGNORE"))
        val r2 = CorpusCompile.compile(corpusRoot)
        assertTrue(r2.ok)
        assertTrue(r2.notes.none { it.startsWith("警告Lv2:") })
    }

    @Test
    fun `fnf once counts suppressed same-file hits in summary`() {
        val root = corpusRoot
        Files.createDirectories(root)
        Files.write(
            root.resolve("01_a.ERB"),
            listOf("@A_FUNC", "CALL \"MISS_A\"", "CALL \"MISS_B\"").joinToString("\n").toByteArray(Charsets.UTF_8),
        )
        // ErbLoader.cs:968-977 + :984-990：ONCE 档同文件首个之后的撞点 flag=true ⇒
        // ignoredFNFWarningCount++，汇总行门只看计数 > 0（:904-907），不限旗标为 IGNORE。
        writeConfig(listOf("$kFnf:ONCE", "$kFnc:LATER"))
        val r = CorpusCompile.compile(root)
        assertTrue(r.ok, "warnings=" + triples(r).joinToString(" | ") { "${it.first}:${it.second}:${it.third}" })
        val hits = triples(r).filter { it.third.startsWith(notFoundPrefix) }
        assertEquals(1, hits.size, "同文件只告首个撞点")
        assertTrue(hits[0].third.contains("MISS_A"))
        assertTrue(r.notes.any { it == "警告Lv2:已忽略1个调用了未定义函数的警告" })
    }

    @Test
    fun `fnf ignore summary silenced by display warning level 3`() {
        writeSingleCorpus()
        writeConfig(listOf("$kFnc:LATER", "$kWarnLevel:3"))
        val r = CorpusCompile.compile(corpusRoot)
        assertTrue(r.ok)
        assertTrue(r.notes.none { it.startsWith("警告Lv2:") })
    }
}
