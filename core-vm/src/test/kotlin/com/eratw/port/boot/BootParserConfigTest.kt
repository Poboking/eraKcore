package com.eratw.port.boot

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M11-E bootcfg（遗留项④）：boot 装置 `RealCorpusCompile` 的 `ParserConfig` 传参。
 *
 * 覆盖三层判据（CONTRACT-M11-E §5 bootcfg）：
 * 1. **取值**（[parserConfigFromOverrides 用例组]）：三键从 `configOverrides`
 *    （`emuera.config` 那一层；键 = 条目标签大写英文形）读取，口径与
 *    `SystemVariableBootstrap.systemSaveInBinaryFrom` 同款 ——
 *    键缺失/值解析失败 ⇒ 出厂默认 false（spec: ConfigData.cs:98/:112/:113 注册、
 *    ConfigItem.cs:89-95 保留旧值、ConfigData.cs:563-586 无效值只记警告）；
 * 2. **喂进构造**（[sliceLineParser 分叉用例组]）：三键经 [RealCorpusCompile.sliceLineParser]
 *    缝进 `LineParser` 的 `config` 构造参（不碰 LineParser.kt 本体，Q2 在飞）；
 *    键翻转的分叉逐键对拍 C# 消费点：
 *    - `SystemIgnoreStringSet`：字符串变量 `=` 赋值 ⇒ level 2 错误
 *      （spec: ArgumentParser.cs:1024-1029 `case OperatorCode.Assignment:` →
 *      `assignwarn(Lang.Error.StrAssignIsPrihibited, line, 2)`，Lang.cs:2062；
 *      移植侧 ArgBuilders.kt:1446-1453）；
 *    - `CompatiRAND`：`RAND` 省略下标 / `RAND:0` 的解析闸
 *      （spec: VariableParser.cs:163-168，关时报错、开时合法）；
 *    - `SystemNoTarget`：角色变量下标补全（spec: VariableParser.cs:90-117；
 *      开时「给一缺其余」不再补 TARGET 而是报「下标不能省略」）；
 * 3. **语料现值零回退**（语料闸门用例）：三条在 `emuera.config:52/:65/:66` 全为 NO ⇒
 *    [RealCorpusCompile.parserConfigFromOverrides] 返回等于出厂 [ParserConfig]，
 *    且 `RootedBuiltinHost(gameRoot).configOverrides` 与装置用的
 *    [loadConfigOverrides]（其无宿主形态，BootHosts.kt 同一合并器）取到同一读数 ——
 *    boot 既有切片/global #DIM/失败数读数不动。
 *
 * 语料门控：环境变量缺失时 `assumeTrue` 跳过（对齐 `ConfigChainWireTest`/
 * `MmapLoaderTest` 的既有 boot 门控模式，不假绿）。
 */
class BootParserConfigTest {

    // ------------------------------------------------------------------
    // 1) 取值：parserConfigFromOverrides
    // ------------------------------------------------------------------

    private val enCompatiRAND = ConfigSpecTable.specOf(ConfigKey.CompatiRAND)!!.upperEnLabel
    private val enSystemNoTarget = ConfigSpecTable.specOf(ConfigKey.SystemNoTarget)!!.upperEnLabel
    private val enSystemIgnoreStringSet =
        ConfigSpecTable.specOf(ConfigKey.SystemIgnoreStringSet)!!.upperEnLabel

    @Test
    fun `空覆盖表 ⇒ 三键全出厂 false 等于 ParserConfig 缺省`() {
        assertEquals(ParserConfig(), RealCorpusCompile.parserConfigFromOverrides(emptyMap()))
    }

    @Test
    fun `英文标签 YES 三键全部翻进 ParserConfig`() {
        // 键名 = 大写英文标签（configOverrides 的实际形状，BootHosts.kt mergedOverrideMap）。
        val flipped = mapOf(
            enCompatiRAND to "YES",
            enSystemNoTarget to "YES",
            enSystemIgnoreStringSet to "YES",
        )
        assertEquals(
            ParserConfig(systemNoTarget = true, compatiRAND = true, systemIgnoreStringSet = true),
            RealCorpusCompile.parserConfigFromOverrides(flipped),
        )
    }

    @Test
    fun `日文标签与整数形态同样命中`() {
        // 日文标签形（spec: ConfigData.cs:273 的 Text 分支）与整数值（:234-238 `!=0` 即真）。
        val jp = ConfigSpecTable.specOf(ConfigKey.CompatiRAND)!!.upperJpLabel
        assertEquals(
            ParserConfig(compatiRAND = true),
            RealCorpusCompile.parserConfigFromOverrides(mapOf(jp to "1")),
        )
    }

    @Test
    fun `键大小写与首尾空白容忍（GetConfigItem 的 Trim+大写比较）`() {
        // spec: ConfigData.cs:262-284 + :539（array[0].Trim()）。
        val messy = mapOf("  imitate behavior for rand " to "YES")
        assertEquals(
            ParserConfig(compatiRAND = true),
            RealCorpusCompile.parserConfigFromOverrides(messy),
        )
    }

    @Test
    fun `值解析失败或为 NO ⇒ 保留出厂 false`() {
        // spec: ConfigItem.cs:89-95（无效值保留旧值）+ ConfigData.cs:563-586（只记警告）。
        val garbage = mapOf(enSystemIgnoreStringSet to "MAYBE", enCompatiRAND to "NO")
        assertEquals(ParserConfig(), RealCorpusCompile.parserConfigFromOverrides(garbage))
    }

    @Test
    fun `单键翻转只动自己那条`() {
        val onlyIgnore = mapOf(enSystemIgnoreStringSet to "YES")
        assertEquals(
            ParserConfig(systemIgnoreStringSet = true),
            RealCorpusCompile.parserConfigFromOverrides(onlyIgnore),
        )
        val onlyNoTarget = mapOf(enSystemNoTarget to "前") // ConfigItem.cs:244-248 `前`=true
        assertEquals(
            ParserConfig(systemNoTarget = true),
            RealCorpusCompile.parserConfigFromOverrides(onlyNoTarget),
        )
    }

    // ------------------------------------------------------------------
    // 2) 喂进构造：sliceLineParser（compile 的 LineParser 构造缝）分叉对拍
    // ------------------------------------------------------------------

    private fun seam(parserConfig: ParserConfig): LineParser = RealCorpusCompile.sliceLineParser(
        SymbolTable()
            .declareVariable("T", OperandType.STR, 0)
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("CFLAG", OperandType.INT, 1, isCharacterData = true),
        macros = emptyMap(),
        parserConfig = parserConfig,
    )

    private fun parse(p: LineParser, text: String): Statement? =
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "boot-parser-config.erb", 10, text))

    @Test
    fun `出厂口径 SystemIgnoreStringSet=关 ⇒ 字符串等号赋值按 FORM 原文解析`() {
        // spec: ArgumentParser.cs:1031-1041 —— 关时右值是 AnalyseFormattedString 原文。
        // 与 core/stmt/SystemIgnoreStringSetTest 的关态断言同形（SAVE-09b 口径复用）。
        val s = assertIs<AssignStmt>(parse(seam(ParserConfig()), "T = \"a=b(c)\""))
        assertEquals(OperandType.STR, s.dest.operandType)
        assertEquals("\"a=b(c)\"", (s.args as SpSetArgument).constStr)
    }

    @Test
    fun `翻转 SystemIgnoreStringSet=YES ⇒ 字符串等号赋值判 level 2 错误（分叉可见）`() {
        // spec: ArgumentParser.cs:1026-1029 —— `Config.SystemIgnoreStringSet` 打开时
        // `assignwarn(Lang.Error.StrAssignIsPrihibited.Text, line, 2, isBackComp: false)`，
        // level 2 = `line.IsError`（ArgumentBuilder.cs:21-26）；移植侧语句层纪律是抛
        // StmtParseException（ArgBuilders.kt:1451-1453，文案同 Lang.cs:2062 zhs 译文）。
        val e = assertFailsWith<StmtParseException> {
            parse(seam(RealCorpusCompile.parserConfigFromOverrides(mapOf(enSystemIgnoreStringSet to "YES"))), "T = \"abc\"")
        }
        assertTrue(e.message!!.contains("直接赋值字符串被禁用"), e.message ?: "(无消息)")
    }

    @Test
    fun `翻转 CompatiRAND ⇒ RAND 省略下标从报错变合法（分叉可见）`() {
        // spec: VariableParser.cs:163-165（关：OmittedRandArg）；开时省略下标补 0 合法
        //（VariableParser.cs:160-166 的 ZeroTerm 分支）。口径同 CompatiRandKeyTest §解析面。
        val off = assertFailsWith<StmtParseException> { parse(seam(ParserConfig()), "A = RAND") }
        assertTrue(off.message!!.contains("RAND 不能省略下标"), off.message ?: "(无消息)")
        assertIs<AssignStmt>(
            parse(seam(RealCorpusCompile.parserConfigFromOverrides(mapOf(enCompatiRAND to "YES"))), "A = RAND"),
        )
    }

    @Test
    fun `翻转 SystemNoTarget ⇒ 角色变量单下标补全从合法变报错（分叉可见）`() {
        // spec: VariableParser.cs:90-101 —— 关时 `CFLAG:0` 前面补 TARGET（[TARGET, 0]）；
        // 开时省略位不补 ⇒ 「下标不能省略」（:96/:117 一族）。移植侧 Parser.kt:548-556。
        assertIs<AssignStmt>(parse(seam(ParserConfig()), "A = CFLAG:0"))
        val e = assertFailsWith<StmtParseException> {
            parse(seam(RealCorpusCompile.parserConfigFromOverrides(mapOf(enSystemNoTarget to "YES"))), "A = CFLAG:0")
        }
        assertTrue(e.message!!.contains("下标不能省略"), e.message ?: "(无消息)")
    }

    // ------------------------------------------------------------------
    // 3) 语料现值：零回退实证（判据③）+ 同源核对（判据①）
    // ------------------------------------------------------------------

    /**
     * 游戏根发现：`ERATW_GAMEDIR`（显式即权威）→ `ERATW_CORPUS` 的上一级 →
     * [RealCorpus] 自动发现链的上一级；三处都要 `emuera.config` 真实存在才算数，
     * 否则返回 null 由调用方 `assumeTrue` 跳过（不猜路径、不假绿）。
     */
    private fun gameRootOrNull(): Path? {
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
            val p = Path.of(g).toAbsolutePath().normalize()
            return if (Files.isRegularFile(p.resolve("emuera.config"))) p else null
        }
        System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
            val p = Path.of(c).toAbsolutePath().normalize().parent ?: return null
            return if (Files.isRegularFile(p.resolve("emuera.config"))) p else null
        }
        val root = runCatching { RealCorpus.load()?.root?.parent }.getOrNull()
        return if (root != null && Files.isRegularFile(root.resolve("emuera.config"))) root else null
    }

    @Test
    fun `语料现值三键全 NO ⇒ ParserConfig 出厂且两同源读数一致`() {
        val gameRoot = gameRootOrNull()
        assumeTrue(gameRoot != null, "缺 ERATW_GAMEDIR/ERATW_CORPUS 或无 emuera.config：跳过语料三键对账")

        // 判据①：生产 erbcc 用 `RootedBuiltinHost(gameRoot).configOverrides`
        //（CorpusCompile.kt:651 同款），装置侧用 loadConfigOverrides（其无宿主形态）——
        // 三键在两边必须读出同一个值。
        val hostOverrides = RootedBuiltinHost(gameRoot!!).configOverrides
        val rigOverrides = loadConfigOverrides(gameRoot)

        // 判据③的取数过程：覆盖表原值（出厂 false ⇒ NO 即零翻转）。
        assertEquals("NO", hostOverrides[enCompatiRAND], "CompatiRAND 原值（语料 emuera.config:52）")
        assertEquals("NO", hostOverrides[enSystemNoTarget], "SystemNoTarget 原值（语料 emuera.config:65）")
        assertEquals("NO", hostOverrides[enSystemIgnoreStringSet], "SystemIgnoreStringSet 原值（语料 emuera.config:66）")
        assertEquals("NO", rigOverrides[enCompatiRAND], "loadConfigOverrides 与宿主表同值")
        assertEquals("NO", rigOverrides[enSystemNoTarget])
        assertEquals("NO", rigOverrides[enSystemIgnoreStringSet])

        // 现值 ⇒ 出厂 ParserConfig —— boot 既有切片读数（global #DIM、失败数等）零回退的前提。
        assertEquals(ParserConfig(), RealCorpusCompile.parserConfigFromOverrides(hostOverrides))
        assertEquals(ParserConfig(), RealCorpusCompile.parserConfigFromOverrides(rigOverrides))

        // 行号锚：三键确实落在 emuera.config 的 :52/:65/:66（本类 KDoc 与交付报告的取数依据）。
        // 按 UTF-8 解码整 bytes（readAllLines 对非法字节会抛；本文件实测纯 ASCII 标签行）。
        val lines = String(Files.readAllBytes(gameRoot.resolve("emuera.config")), Charsets.UTF_8).lines()
        assertTrue(
            lines.getOrNull(51)?.trim()?.startsWith("IMITATE BEHAVIOR FOR RAND:NO") == true,
            "emuera.config:52 期望 IMITATE BEHAVIOR FOR RAND:NO，实际 ${lines.getOrNull(51)}",
        )
        assertTrue(
            lines.getOrNull(64)?.trim()
                ?.startsWith("DO NOT AUTO-COMPLETE ARGUMENTS FOR CHARACTER VARIABLES:NO") == true,
            "emuera.config:65 实际 ${lines.getOrNull(64)}",
        )
        assertTrue(
            lines.getOrNull(65)?.trim()
                ?.startsWith("STRING VARIABLE ASSIGNMENT ON VALID WITH STRING EXPRESSION:NO") == true,
            "emuera.config:66 实际 ${lines.getOrNull(65)}",
        )
    }
}
