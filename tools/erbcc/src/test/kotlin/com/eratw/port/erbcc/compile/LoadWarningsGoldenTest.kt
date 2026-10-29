package com.eratw.port.erbcc.compile

import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.erbcc.LoadWarning
import com.eratw.port.erbcc.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 装载期告警通道对拍 PC 基准 `.workbuddy/golden-pc-loadwarnings.txt`（5 行）。
 *
 * 基准的采集口径（`.workbuddy/tools/golden_check.py:13,50`）：从原版 `emuera.log` 里抓
 * `警告Lv{level}:{file}:第{line}行:{message}`（`EmueraConsole.PrintWarning` 的 Warning1 模板，
 * Lang.cs / zhs 表 `Error.Warning1`），再拼上夹具该行的原文成
 * `文件:行 | 文案 | SRC: 原文`。前两列才是移植侧的判据（`SRC` 是采集脚本从夹具补的）。
 *
 * ## 基准的两处夹具漂移（本测试按**当前**夹具断言，逐条列明）
 *
 * 1. `GOLDEN_06_INPUT_AUTO.ERB` 在基准采集之后长过 9 行（`:38-46` 的 `TONEINPUT*` 注释+语句块，
 *    以及 `:59-66` 的 `ONEBINPUTS` 段）。基准 `:5` 记的 `BINPUTS` 在 **45** 行，
 *    当前夹具在 **54** 行（`+9` 正是那 9 行）。文案与 `SRC` 原文不变。
 * 2. 同一处新增的 `ONEBINPUTS "OBS", 0, 1`（当前 `:65`）在原版**同样**会报参数过多
 *    （`ONEBINPUTS_Instruction` 用 `FunctionArgumentType.SP_INPUTS`，FunctionIdentifier.cs:2426-2432；
 *    该 builder 的超限分支 ArgumentParser.cs:1155-1163），基准里没有这一行只是因为采集早于该语句。
 *    夹具自己在 `:59-63` 就写明了这个告警。
 *
 * 故 5 行里 **4 行逐字一致**（含行号），第 5 行文案一致、行号按当前夹具校正，并多出基准未覆盖的
 * `ONEBINPUTS` 一行。见 [goldenFiveLinesInLoadOrder]。
 *
 * ## 覆盖范围
 *
 * 只覆盖本波落地的两类告警（重复 `@FUNCTION` / `INPUTS` 系参数过多）。原版
 * `ParserMediator.Warn` 全族 253 处调用点其余部分尚未接线，不在本测试断言范围内
 * （见交付消息「下一批接点建议」）。
 */
class LoadWarningsGoldenTest {

    private fun repoRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve("fixtures/golden/GOLDEN_12_SYMBOLS.ERB"))) return dir
            dir = dir.parent
        }
        error("找不到仓库根（从 ${System.getProperty("user.dir")} 向上未发现 fixtures/golden/GOLDEN_12_SYMBOLS.ERB）")
    }

    /**
     * 与 [CorpusCompile.compile] 同口径解析**单个** ERB 文件的语句层告警。
     *
     * 装配与生产管线逐项对齐：`CorpusScan.scan` 建私有变量/返回类型表 → `CompileResolver`
     * 作 [LineParser] 的解析器 → `LogicalLineReader` 读物理行。行级异常按生产口径吞掉
     * （`CorpusCompile` 把它记进 parseFailures 并继续），**已产生的告警不丢**。
     */
    private fun statementWarnings(file: Path): List<LoadWarning> {
        val rel = file.fileName.toString()
        val scan = CorpusScan.scan(listOf(file))
        val parser = LineParser(CompileResolver(scan), lexerConfig = LexerConfig())
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(Files.readAllBytes(file)),
            rel,
            macroNames = scan.macroNames,
        )
        while (true) {
            val line = try {
                reader.readLogicalLine() ?: break
            } catch (_: LexError) {
                continue
            }
            try {
                parser.parseLogicalLine(line)
            } catch (_: Exception) {
                // 原版这类行是 level 2（IsError）——不属本通道，生产管线记 parseFailures。
            }
        }
        try { reader.fileEnd() } catch (_: Exception) {}
        // 行号 +1：`LogicalLine.lineNo` / `StmtWarning.line` 是 0 基，原版
        // `ScriptPosition.LineNo = srcLineNo + 1`（ScriptPosition.cs:10-16）是 1 基，
        // 告警基准（emuera.log 的「第N行」）同样是 1 基。
        return parser.warnings.map { LoadWarning(rel, it.line + 1, it.message) }
    }

    /** 渲染口径 = 基准前两列（`文件:行 | 文案`）。 */
    private fun render(w: LoadWarning): String = "${w.file}:${w.line} | ${w.message}"

    // -----------------------------------------------------------------------
    // 1) 重复 @FUNCTION：基准 :1-:3
    // -----------------------------------------------------------------------

    @Test
    fun duplicateFunctionWarningsMatchGoldenLinesOneToThree() {
        val table = SymbolTable.build(repoRoot().resolve("fixtures/golden"))
        val got = table.duplicateFunctionWarnings().map { render(it) }
        assertEquals(
            listOf(
                // golden-pc-loadwarnings.txt:1
                "GOLDEN_12_SYMBOLS.ERB:43 | 函数\"@G12_FUNC\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的39行)",
                // :2 —— 参照的是**首条**定义（48），不是紧邻的上一条（52）
                "GOLDEN_12_SYMBOLS.ERB:52 | 函数\"@G12_PLAIN\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的48行)",
                // :3 —— 第 3 次定义仍指向 48
                "GOLDEN_12_SYMBOLS.ERB:56 | 函数\"@G12_PLAIN\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的48行)",
            ).joinToString("\n"),
            got.joinToString("\n"),
        )
    }

    /**
     * 事件函数不进告警（spec: ErbLoader.cs:443 `!IsEvent`）：夹具里 `EVENTTURNEND`×3、
     * `EVENTFIRST`×2 都是重名组，但一条都不许出现。
     */
    @Test
    fun duplicateFunctionWarningsSkipEventLabels() {
        val table = SymbolTable.build(repoRoot().resolve("fixtures/golden"))
        val warns = table.duplicateFunctionWarnings()
        assertEquals(3, warns.size, "非事件重名组 2 个 ⇒ 被遮蔽定义 1+2=3 条；实际: $warns")
        assertTrue(
            warns.none { it.message.contains("EVENTTURNEND") || it.message.contains("EVENTFIRST") },
            "事件重名不告警（ErbLoader.cs:443）；实际: $warns",
        )
        // 参照点必须是首条定义：G12_PLAIN 的首条在 48 行（夹具 :48/:52/:56 三次定义）
        assertEquals(2, warns.count { it.message.contains("@G12_PLAIN") && it.message.contains("的48行") })
    }

    // -----------------------------------------------------------------------
    // 2) INPUTS / BINPUTS 参数过多：基准 :4-:5
    // -----------------------------------------------------------------------

    @Test
    fun tooManyArgWarningsMatchGoldenLinesFourToFive() {
        val file = repoRoot().resolve("fixtures/golden/GOLDEN_06_INPUT_AUTO.ERB")
        val got = statementWarnings(file)
            .filter { it.message.endsWith("命令:参数过多") }
            .map { render(it) }
        assertEquals(
            listOf(
                // golden-pc-loadwarnings.txt:4（夹具 :30 `INPUTS "ANS", 0, 1`，行号未漂移）
                "GOLDEN_06_INPUT_AUTO.ERB:30 | INPUTS命令:参数过多",
                // :5 文案一致；行号 45 → 54（夹具采集后长过 9 行，见类 KDoc 漂移 1）
                "GOLDEN_06_INPUT_AUTO.ERB:54 | BINPUTS命令:参数过多",
                // 基准未覆盖：夹具 :65 `ONEBINPUTS "OBS", 0, 1` 走同一个 SP_INPUTS（漂移 2）
                "GOLDEN_06_INPUT_AUTO.ERB:65 | ONEBINPUTS命令:参数过多",
            ).joinToString("\n"),
            got.joinToString("\n"),
        )
    }

    /**
     * 文案必须是原版拼接式 `语句名 + "命令:" + "参数过多"`
     * （ArgumentBuilder.cs:30-40 的 `line.Function.Name + Lang.Error.Instruction.Text + mes`；
     * `Instruction` = "命令:"，Lang.cs:1918；`TooManyArg` = "参数过多"，Lang.cs:1930）。
     * 旧移植文案「X 的参数过多（多余的将被忽略）」不得再出现。
     */
    @Test
    fun tooManyArgTextHasNoLegacySuffix() {
        val file = repoRoot().resolve("fixtures/golden/GOLDEN_06_INPUT_AUTO.ERB")
        val warns = statementWarnings(file)
        assertTrue(
            warns.none { it.message.contains("多余的将被忽略") },
            "旧文案应已被替换；实际: ${warns.map { it.message }}",
        )
        assertTrue(
            warns.any { it.message == "INPUTS命令:参数过多" },
            "INPUTS 必须给出原版文案；实际: ${warns.map { it.message }}",
        )
    }

    // -----------------------------------------------------------------------
    // 3) 段序：重复定义（装载第一遍）在语句参数（ParseScript）之前
    // -----------------------------------------------------------------------

    /**
     * 原版顺序 = 「逐文件装载（重复定义告警随 AddLabel 立刻打）→ `ParseScript` 统一建参数
     * （ErbLoader.cs:786-800）」⇒ 基准 5 行是「3 条重复 → 2 条参数过多」。
     *
     * 本仓 [CorpusCompile] 的段序与之一致（`CompileResult.warnings` 段序：
     * 符号表重复定义 → 预扫描 → 语句参数），此处按同一段序拼接两份产出做整块对拍。
     */
    @Test
    fun goldenFiveLinesInLoadOrder() {
        val root = repoRoot().resolve("fixtures/golden")
        val dup = SymbolTable.build(root).duplicateFunctionWarnings()
        // 夹具漂移 2 的 ONEBINPUTS 不在基准里，单列在 [tooManyArgWarningsMatchGoldenLinesFourToFive]；
        // 这里只拼基准覆盖的 5 行，否则整块对拍会被那条「基准之后才出现的语句」打破。
        val arg = statementWarnings(root.resolve("GOLDEN_06_INPUT_AUTO.ERB"))
            .filter { it.message.endsWith("命令:参数过多") && !it.message.startsWith("ONEBINPUTS") }
        val got = (dup + arg).map { render(it) }
        assertEquals(
            listOf(
                "GOLDEN_12_SYMBOLS.ERB:43 | 函数\"@G12_FUNC\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的39行)",
                "GOLDEN_12_SYMBOLS.ERB:52 | 函数\"@G12_PLAIN\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的48行)",
                "GOLDEN_12_SYMBOLS.ERB:56 | 函数\"@G12_PLAIN\"已经被定义过(GOLDEN_12_SYMBOLS.ERB的48行)",
                "GOLDEN_06_INPUT_AUTO.ERB:30 | INPUTS命令:参数过多",
                "GOLDEN_06_INPUT_AUTO.ERB:54 | BINPUTS命令:参数过多",
            ).joinToString("\n"),
            got.joinToString("\n"),
        )
    }
}
