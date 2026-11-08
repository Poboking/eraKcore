package com.eratw.port.core.compile

import com.eratw.port.core.compile.CorpusCompileValidator.LineSource
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 语料/编译面**失败归因**的判据来源与坐标系（A14，lane compreadout 第三程，2026-11-05）。
 *
 * 命题（派工第 2、3、4 节）：`CorpusCompileValidator.toFailure` 旧写法用正则在异常 message 里刮
 * 「行:N」再拿去等 `Statement.line`。本文件钉的是换掉之后的四条读数级性质：
 * ① message 前缀形状怎么变，归因读数一字不动（并显式钉住「旧刮法在这一格上会动」，防本用例空转）；
 * ② 拿不到结构化行号时如实记 [LineSource.NO_STRUCTURED_LINE] 且 `line = -1`，**不**回退成切片头行；
 * ③ 0 基/1 基不差一（1 基只走唯一出口 [erbDisplayLine]，并用「按 displayLine 回读源文件必须读到
 *    那条语句」当「指对行」的定义）；
 * ④ [LexError] 臂与非 `CompileException` 臂的读数形状（异常类型、行号来源，以及函数级 -1
 *    与「无结构化行号」在读数上分得开）。
 *
 * ## 坐标系（逐条读生产侧赋值处，不信 KDoc）
 * - `LogicalLine.lineNo` = **0 基**：`core/lexer/LogicalLineReader.kt:171` `private var curNo = 0`，
 *   `LogicalLineReader.kt:15` 属性本体（注释 `:14` 写明 0 基）。
 * - `Statement.line` = **0 基**：`core/stmt/LineParser.kt:144/149/150` 原样把 `line.lineNo` 交给
 *   `parseCodeLine/parseLabelLine/parseSharpLine`，语句构造体再原样收下（如 `LineParser.kt:259`
 *   `PrintStmt(spec, arg, lineNo, content)`）。
 * - `CompileException.line` = **0 基**：抛点取语句自身行 —— `core/compile/ErbProgramCompiler.kt:1047`
 *   （RETURNF 闸，本文件探针走这一条）、`:562`（未知语句）、`:966/:975`
 *   （`statements.lastOrNull()?.line`）；`-1` 只出现在函数级错误（`:319/:386/:393/:432/:982`）。
 * - `LexError.lineNo` = **0 基**：`core/lexer/LogicalLineReader.kt:220/225/236/239` 全部传 `curNo`，
 *   缺省 -1（`core/lexer/LexError.kt:25`）。
 * - **message 与字段不同基**：`core/stmt/ParseSupport.kt:45` 烤 `行:${erbDisplayLine(line)}`（1 基）
 *   而 `:41` 的字段是 0 基；`core/compile/CompileError.kt:24` 自 errtext5 起同样烤
 *   `erbDisplayLine(line)`（1 基，本波合流头 `b44a4d91` 已含）⇒ 编译/解析两条消息都比字段大 1，
 *   按消息反查 `Statement.line` 必差一行（canary 见 [deviceAttributesTheThrowingLineAndDisplayLineRoundTrips]）。
 * - 对外 1 基唯一出口：`core/err/CodeEE.kt:98` `erbDisplayLine(line0)`（负数原样返回）。
 *
 * ## 断言纪律
 * 全部**读数级**（具体行号 / 语句码 / 命中数 / 分组键值 / tsv 单元格），失败时把期望与实际都写进消息；
 * 不写 `report.contains("…")`、更不写 `源码.contains("erbDisplayLine")` 这种会被 import 与 KDoc
 * 白送过去的文件级断言（`f843f46d` 提交信息第五节记的就是这么一枚假闸）。
 */
class CorpusFailureAttributionTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 探针语料（物理行号 = 0 基下标）：
     * 第 1 行 `@ATTR_PROBE`（0 基 0）/ 第 2 行 `PRINTL 1`（0 基 1）/
     * 第 3 行 `RETURNF 2`（0 基 2，抛点）/ 第 4 行 `PRINTL 3`（0 基 3，差一时会被误指到这一条）。
     */
    private val probePhysical = listOf("@ATTR_PROBE", "PRINTL 1", "RETURNF 2", "PRINTL 3")

    /** 抛点消息正文（`CompileException` 的后半段；前缀由异常自己拼，本文件只当形状来喂）。 */
    private val probeBody =
        "RETURNF 只能在 #FUNCTION/#FUNCTIONS 内使用（spec: FunctionIdentifier.cs:3455-3458 CanNotUseReturnf）"

    private fun kindOf(text: String): LineKind = when {
        text.startsWith("@") -> LineKind.FUNCTION_LABEL
        text.startsWith("$") -> LineKind.GOTO_LABEL
        text.startsWith("#") -> LineKind.SHARP
        else -> LineKind.CODE
    }

    /** 行号交给真解析器（`LogicalLine(..., i, ...)`，i 从 0 起 = 0 基物理下标），不自造坐标系。 */
    private fun parseAll(lines: List<String>, file: String): List<Statement> {
        val parser = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            parser.parseLogicalLine(LogicalLine(kindOf(text), file, i, text))
        }
    }

    private fun probeSlice(): CorpusCompileValidator.Slice {
        val body = parseAll(probePhysical, "attr-probe.ERB").filterNot { it is FuncLabelStmt }
        return CorpusCompileValidator.Slice(
            key = "ATTR_PROBE", params = 0, isMethod = false,
            stmts = body, file = "attr-probe.ERB", line = 0,
        )
    }

    /** 夹具自证：语句行号就是 0 基物理下标，否则本文件全程在测一个假坐标系。 */
    @Test
    fun fixtureStatementsCarryZeroBasedPhysicalLines() {
        val slice = probeSlice()
        assertEquals(
            listOf("PRINTL", "RETURNF", "PRINTL"),
            slice.stmts.map { it.code.name },
            "夹具应恰好 3 条语句（PRINTL/RETURNF/PRINTL），实际=${slice.stmts.map { it.code.name to it.line }}",
        )
        assertEquals(listOf(1, 2, 3), slice.stmts.map { it.line }, "Statement.line 必须是 0 基物理下标")
        assertEquals(0, slice.line, "@函数 头行是 0 基 0")
        assertEquals("RETURNF 2", slice.stmts[1].raw, "0 基 2 这一格就是抛点原文")
    }

    private class DeviceRun(val root: Path, val file: Path, val result: CorpusCompileValidator.Result)

    /** 真装置（tempdir mini 语料，只写 build 之外的临时目录，不碰共享语料根）。 */
    private fun deviceRun(@TempDir dir: Path, lines: List<String>, name: String, extra: List<Path> = emptyList()): DeviceRun {
        val erbRoot = dir.resolve("ERB")
        Files.createDirectories(erbRoot)
        val file = erbRoot.resolve(name)
        Files.write(file, lines.joinToString("\r\n").toByteArray(Charsets.UTF_8))
        val r = CorpusCompileValidator.compile(listOf(file) + extra, erbRoot)
        return DeviceRun(erbRoot, file, r)
    }

    private fun feed(
        message: String,
        structuredLine: Int?,
        lineSource: LineSource,
        category: String = CorpusCompileValidator.CAT_RULE,
        slice: CorpusCompileValidator.Slice = probeSlice(),
    ): CorpusCompileValidator.Failure = CorpusCompileValidator.toFailure(
        slice, "ATTR_PROBE", message, category, structuredLine, lineSource,
    )

    // ------------------------------------------------------------------
    // ① message 形状换了 ⇒ 归因读数一字不动（并钉住旧刮法会动 = 本用例非空转）
    // ------------------------------------------------------------------

    @Test
    fun attributionIgnoresMessageShapeAndPinsLegacyDrift() {
        val shapes = linkedMapOf(
            "旧 0 基前缀（errtext5 前）" to "编译错误(函数:ATTR_PROBE, 行:2): $probeBody",
            "现基 1 基前缀（errtext5 后）" to "编译错误(函数:ATTR_PROBE, 行:3): $probeBody",
            "整族换文案" to "COMPILE-FAILED <<<ATTR_PROBE>>> @L3 :: RETURNF misuse",
            "消息里带诱饵行号" to "编译错误(函数:ATTR_PROBE, 行:999): $probeBody",
            "空消息" to "",
        )
        for ((name, msg) in shapes) {
            val f = feed(msg, structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION)
            assertEquals(2, f.line, "$name：判据是结构化字段 ⇒ 0 基读数恒 2（期望 2 / 实际 ${f.line}；message=${f.message}）")
            assertEquals(3, f.displayLine, "$name：1 基出口恒 3 = 物理第 3 行（期望 3 / 实际 ${f.displayLine}）")
            assertEquals("RETURNF", f.stmtCode, "$name：归因语句码不许跟着文案走（期望 RETURNF / 实际 ${f.stmtCode}）")
            assertEquals("RETURNF 2", f.stmtRaw, "$name：归因原文同上（实际 ${f.stmtRaw}）")
            assertEquals(1, f.stmtHitCount, "$name：0 基 2 在切片里只该命中 1 条（实际 ${f.stmtHitCount}）")
            assertEquals(LineSource.COMPILE_EXCEPTION, f.lineSource, "$name：来源标注不许跟着文案走（实际 ${f.lineSource}）")
            assertTrue(f.hasStructuredLine, "$name：COMPILE_EXCEPTION 属结构化来源")
            assertTrue(f.attributed, "$name：这一格应算归因成功")
            assertEquals(0, f.sliceLine, "$name：@函数 头行只活在上下文列（实际 ${f.sliceLine}）")
        }
        // 非空转自证：同一批 message 交给旧刮法，读数**确实**会漂 —— 这正是换判据的理由
        assertEquals(2, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("旧 0 基前缀（errtext5 前）")))
        assertEquals(3, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("现基 1 基前缀（errtext5 后）")))
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("整族换文案")))
        assertEquals(999, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("消息里带诱饵行号")))
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("空消息")))
        assertTrue(
            CorpusCompileValidator.legacyScrapedLine(shapes.getValue("现基 1 基前缀（errtext5 后）")) != 2,
            "errtext5 起消息烤 1 基数，与 0 基字段不等 ⇒ 按消息反查必差一",
        )
    }

    /**
     * 差一的**可执行复现**（派工第 3 节）：1 基 message + 0 基键面 ⇒ 旧写法指到下一条语句；
     * 判据换成结构化字段后指回抛点。
     */
    @Test
    fun oneBasedMessageMovesLegacyTargetByOneButNotTheStructuredReadout() {
        val slice = probeSlice()
        val oneBased = "编译错误(函数:ATTR_PROBE, 行:3): $probeBody"
        val legacyLine = CorpusCompileValidator.legacyScrapedLine(oneBased)
        assertEquals(3, legacyLine, "旧刮法在这条消息上拿到 1 基数")
        val legacyStmt = slice.stmts.firstOrNull { it.line == legacyLine }
        assertEquals("PRINTL", legacyStmt?.code?.name, "旧刮法落在物理第 4 行那条 PRINTL 上（差一现证；实际 ${legacyStmt?.code?.name}）")
        assertEquals("PRINTL 3", legacyStmt?.raw, "错指的就是紧邻抛点的下一条语句")
        val f = feed(oneBased, structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION)
        assertEquals("RETURNF", f.stmtCode, "新判据仍指抛点")
        assertTrue(
            legacyStmt?.code?.name != f.stmtCode,
            "新旧路径必须在这一格分岔，否则本用例什么都没测到（旧=${legacyStmt?.code?.name} 新=${f.stmtCode}）",
        )
        // 抛点在切片尾行时，旧写法的差一不是「错一条」而是整条掉进 (未知)
        val tail = "编译错误(函数:ATTR_PROBE, 行:4): $probeBody"
        val tailLegacy = CorpusCompileValidator.legacyScrapedLine(tail)
        assertEquals(4, tailLegacy)
        assertNull(slice.stmts.firstOrNull { it.line == tailLegacy }, "0 基键面上没有第 5 条 ⇒ 旧写法归因全丢")
        val tailNew = feed(tail, structuredLine = 3, lineSource = LineSource.COMPILE_EXCEPTION)
        assertEquals("PRINTL", tailNew.stmtCode, "同一形状，新判据照样归到 0 基 3 那条")
        assertEquals("PRINTL 3", tailNew.stmtRaw)
    }

    /**
     * 不等 errtext、**现基**就已经差一的一族：解析层 `StmtParseException`
     * 字段 0 基（`ParseSupport.kt:41`）、message 已烤 1 基（`:45`）。
     */
    @Test
    fun parseExceptionFieldAndMessageAlreadyDifferByOne() {
        val e = assertFailsWith<StmtParseException> {
            LineParser(SymbolTable()).parseLogicalLine(LogicalLine(LineKind.GOTO_LABEL, "p.ERB", 7, "$"))
        }
        assertEquals(7, e.line, "字段是 0 基（期望 7 / 实际 ${e.line}）")
        assertEquals(8, e.displayLine, "异常自带的 1 基出口 = 字段 +1（实际 ${e.displayLine}）")
        val scraped = CorpusCompileValidator.legacyScrapedLine(e.message ?: "")
        assertEquals(8, scraped, "message 里烤的是 1 基数（期望 8 / 实际 $scraped；message=${e.message}）")
        assertTrue(e.line != scraped, "字段基 != 消息基 ⇒ 本基点按 message 反查今天就已经差一")
        val st = CorpusCompileValidator.structuredLineOf(e)
        assertEquals(7, st?.first ?: -999, "装置判据取字段（0 基 7），不是消息")
        assertEquals(LineSource.STMT_PARSE, st?.second, "来源标注实际 ${st?.second}")
    }

    // ------------------------------------------------------------------
    // ② 无结构化行号 ⇒ 如实记 -1，不伪装成切片头行
    // ------------------------------------------------------------------

    @Test
    fun missingStructuredLineIsRecordedAsIsNotAsSliceHead() {
        val slice = probeSlice()
        val boom = IllegalStateException("实现炸了，转述里恰好有一句「行:9」")
        assertNull(CorpusCompileValidator.structuredLineOf(boom), "裸异常没有位置字段 ⇒ 判据必须是「没有」")
        val f = feed(
            "内部错误: ${boom::class.simpleName}: ${boom.message}",
            structuredLine = null,
            lineSource = LineSource.NO_STRUCTURED_LINE,
            category = CorpusCompileValidator.CAT_CRASH,
        )
        assertEquals(-1, f.line, "无结构化行号 ⇒ -1；既不许是消息里的 9，也不许是切片头行 0（期望 -1 / 实际 ${f.line}）")
        assertTrue(f.line != 9, "消息里的「行:9」不是判据")
        assertTrue(f.line != slice.line, "旧实现干的就是把 @函数 头行填进 line 冒充归因（slice.line=${slice.line}）")
        assertEquals(-1, f.displayLine, "displayLine 对负数原样返回（口径同 erbDisplayLine）；实际 ${f.displayLine}")
        assertEquals(LineSource.NO_STRUCTURED_LINE, f.lineSource)
        assertFalse(f.hasStructuredLine, "NO_STRUCTURED_LINE 属非结构化来源")
        assertFalse(f.attributed)
        assertNull(f.stmtCode, "无行号 ⇒ 不得给出语句码")
        assertEquals(0, f.stmtHitCount, "无行号 ⇒ 命中数 0")
        assertEquals(0, f.sliceLine, "头行只作上下文列")

        // 对照组：函数级 -1（异常自己声明的语义）与「压根没有字段」必须读得开
        val fnLevel = CompileException("ATTR_PROBE", -1, "函数重复声明: ATTR_PROBE")
        val st = CorpusCompileValidator.structuredLineOf(fnLevel)
        assertEquals(-1, st?.first ?: 999, "函数级错误的字段值就是 -1")
        assertEquals(LineSource.FUNCTION_LEVEL, st?.second, "来源要写成「函数级」，不是「查无」")
        assertTrue(LineSource.FUNCTION_LEVEL.structured, "结构化 -1 与无结构化 -1 不是一回事")
        assertFalse(LineSource.NO_STRUCTURED_LINE.structured)
        val g = feed(fnLevel.message ?: "", structuredLine = -1, lineSource = LineSource.FUNCTION_LEVEL)
        assertEquals(-1, g.line)
        assertTrue(g.hasStructuredLine, "函数级 -1 仍算「有结构化读数」")
        assertFalse(g.attributed)
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(fnLevel.message ?: ""), "本基点函数级消息烤的是 行:-1")
    }

    /** 真装置里的非结构化臂：文件读取失败（IO 没有行概念），读数不得带上任何行号。 */
    @Test
    fun deviceRecordsFileReadFailureWithoutAnyLine(@TempDir dir: Path) {
        val missing = dir.resolve("ERB/ATTR_MISSING.ERB")
        assertFalse(Files.exists(missing), "前置：这份文件必须不存在")
        val run = deviceRun(dir, probePhysical, "ATTR_PROBE.ERB", extra = listOf(missing))
        val r = run.result
        val read = r.failures.firstOrNull { it.category == CorpusCompileValidator.CAT_READ }
        assertTrue(
            read != null,
            "读盘失败臂应产出一条 CAT_READ，实际=${r.failures.map { it.category to it.lineSource }}",
        )
        read!!
        assertEquals(-1, read.line, "IO 异常没有行概念 ⇒ 如实 -1（期望 -1 / 实际 ${read.line}）")
        assertEquals(LineSource.FILE_READ, read.lineSource)
        assertFalse(read.hasStructuredLine)
        assertFalse(read.attributed)
        assertNull(read.stmtCode)
        assertEquals(-1, read.sliceLine, "读盘失败连切片都没有，头行列不得被填成 0")
        assertTrue(
            read.message.contains("NoSuchFileException"),
            "读取臂要把异常类名留下（期望含 NoSuchFileException / 实际 ${read.message}）",
        )
        // 结果级读数：这条进 (未知) 桶、计进「无结构化行号」，且不计进「有行号查无」
        assertEquals(1L, r.failureByCode["(未知)"] ?: 0L, "(未知) 桶应恰好 1 条，实际=${r.failureByCode}")
        assertEquals(1L, r.unknownCodeFailures)
        assertEquals(0, r.lineMissFailures, "无结构化 != 有行号查无")
        assertEquals(1, r.unstructuredLineFailures)
        assertEquals(1, r.structuredLineFailures, "另一条 RETURNF 失败仍是结构化")
        assertEquals(2, r.failures.size)
        assertEquals(1L, r.failureByCategory[CorpusCompileValidator.CAT_READ] ?: 0L)
        assertEquals(1L, r.failureByCategory[CorpusCompileValidator.CAT_RULE] ?: 0L, "探针自身的降级拒绝仍是 1 条")
    }

    // ------------------------------------------------------------------
    // ③ 坐标系：0 基读数 + 1 基出口；按 displayLine 回读源文件就是那条语句
    // ------------------------------------------------------------------

    @Test
    fun deviceAttributesTheThrowingLineAndDisplayLineRoundTrips(@TempDir dir: Path) {
        val run = deviceRun(dir, probePhysical, "ATTR_PROBE.ERB")
        val r = run.result
        assertEquals(1, r.failures.size, "探针语料应恰好 1 条编译失败，实际=${r.failures.map { it.message }}")
        val f = r.failures[0]
        assertEquals("RETURNF", f.stmtCode, "归因应落在抛点（期望 RETURNF / 实际 ${f.stmtCode}，raw=${f.stmtRaw}）")
        assertEquals(2, f.line, "Failure.line = 0 基内部下标（物理第 3 行 ⇒ 2）；实际 ${f.line}")
        assertEquals(3, f.displayLine, "displayLine = 1 基 = 物理行号；实际 ${f.displayLine}")
        assertEquals(erbDisplayLine(f.line), f.displayLine, "1 基只许走唯一出口 erbDisplayLine")
        assertEquals(
            "RETURNF 2", run.file.toFile().readLines(Charsets.UTF_8)[f.displayLine - 1].trim(),
            "按 displayLine 回读源文件必须读到抛点本身（这一条就是「指对行」的定义；读错一行即差一）",
        )
        assertEquals(LineSource.COMPILE_EXCEPTION, f.lineSource)
        assertEquals(0, f.sliceLine, "@函数 头行 0 基 0，只进上下文列")
        assertEquals(1, f.stmtHitCount, "0 基 2 在切片里只命中 1 条")
        assertTrue(f.hasStructuredLine && f.attributed)
        // errtext5 之后 message 内嵌 1 基（`CompileError.kt:24` 走 `erbDisplayLine`），字段仍 0 基
        // ⇒ 旧→新：旧刮法与 `Failure.line` **相差 1**（旧写法把 1 基数当 0 基键，必指下一条）。
        assertEquals(
            3, CorpusCompileValidator.legacyScrapedLine(f.message),
            "旧→新：message 内嵌 1 基行号 = 字段 +1（message=${f.message}）",
        )
        assertEquals(
            f.line + 1, CorpusCompileValidator.legacyScrapedLine(f.message),
            "旧刮法（1 基）与结构化字段（0 基）差一 —— 这就是装置改读异常字段的原因",
        )
        assertEquals(
            "PRINTL 3", probePhysical[CorpusCompileValidator.legacyScrapedLine(f.message)],
            "把 1 基数当 0 基下标用 ⇒ 落到抛点的下一行（差一现证）",
        )
        assertEquals(1, r.structuredLineFailures)
        assertEquals(0, r.unstructuredLineFailures)
        assertEquals(1, r.attributedFailures)
        assertEquals(100.0, r.attributionRatePercent, 1e-9)
        assertEquals(0L, r.unknownCodeFailures, "归因成功时 (未知) 必须 0")
        assertEquals(1L, r.failureByCode["RETURNF"] ?: 0L, "FunctionCode 分布要记到 RETURNF，实际=${r.failureByCode}")
        assertEquals(1, r.failures.count { it.signaturePrefixStripped }, "装置自己拼的前缀应被认出来（签名不碎）")
    }

    /** failures.tsv 的逐格读数：两基两列 + 来源 + 命中数 + 上下文列 + 前缀剥离位。 */
    @Test
    fun failuresTsvHasBothBasesLabelledPerRow(@TempDir dir: Path) {
        val run = deviceRun(dir, probePhysical, "ATTR_PROBE.ERB")
        val out = CorpusCompileValidator.writeReport(run.result, run.root, dir.resolve("report"))
        val rows = Files.readAllLines(out.resolve("failures.tsv"))
        assertEquals(2, rows.size, "1 条失败 ⇒ 表头 + 1 行，实际=${rows.size}")
        val header = rows[0].split("\t")
        val cols = rows[1].split("\t")
        assertEquals(header.size, cols.size, "列数必须对齐（期望 ${header.size} / 实际 ${cols.size}）")
        fun col(name: String): String {
            val i = header.indexOf(name)
            assertTrue(i >= 0, "tsv 缺列 $name，实际表头=$header")
            return cols[i]
        }
        assertEquals("2", col("line0"), "line0 = 0 基内部下标")
        assertEquals("3", col("line1"), "line1 = 1 基展示行号")
        assertEquals("CompileException.line(0基)", col("lineSource"))
        assertEquals("yes", col("attributed"))
        assertEquals("1", col("stmtHits"))
        assertEquals("0", col("sliceLine0"))
        assertEquals("1", col("sliceLine1"), "@函数 头行的 1 基 = 物理第 1 行")
        assertEquals("RETURNF", col("stmtCode"))
        assertEquals("yes", col("prefixStripped"))
        assertEquals(run.result.failures[0].displayLine.toString(), col("line1"), "tsv 的 1 基列就是 erbDisplayLine 的读数")
    }

    // ------------------------------------------------------------------
    // ④ LexError 臂 / 非 CompileException 臂的读数形状（装置级 + 判据级）
    // ------------------------------------------------------------------

    @Test
    fun lexErrorArmKeepsStructuredZeroBasedLineAndType(@TempDir dir: Path) {
        val run = deviceRun(dir, listOf("@LEX_PROBE", "PRINTL 1", "}", "PRINTL 2"), "LEX_PROBE.ERB")
        val r = run.result
        assertEquals(1, r.parseFailureLines, "孤立 '}' 应恰好计 1 条解析失败行，实际样本=${r.parseFailureSamples}")
        assertEquals(1, r.parseFailures, "含失败行的切片要毒化跳过（不进编译分母）")
        assertEquals(0, r.failures.size, "被毒化的切片不产编译失败")
        assertEquals(1, r.parseFailureLinesStructured, "LexError 的行号来自结构化字段 lineNo")
        assertEquals(0, r.parseFailureLinesUnstructured)
        assertEquals(
            r.parseFailureLines, r.parseFailureLinesStructured + r.parseFailureLinesUnstructured,
            "两栏必须闭合（实际 ${r.parseFailureLinesStructured}+${r.parseFailureLinesUnstructured} vs ${r.parseFailureLines}）",
        )
        val g = r.parseGroups.values.single()
        assertEquals(1, g.lines)
        assertEquals(1, g.types["LexError"] ?: 0, "异常类型是结构化读数（期望 LexError=1 / 实际 ${g.types}）")
        assertEquals(1, g.lineSources[LineSource.LEX_ERROR.label] ?: 0, "行号来源列要写清是哪个字段（实际 ${g.lineSources}）")
        assertEquals("LexError=1", g.typeSummary)
        assertEquals("${LineSource.LEX_ERROR.label}=1", g.lineSourceSummary)
        // 样本行：0 基 2 ⇒ 给人读的是 1 基 3，两基都标出来
        assertTrue(
            g.sample.contains(":3(1基; 0基=2)"),
            "样本必须标明几基（期望含 :3(1基; 0基=2) / 实际 ${g.sample}）",
        )
        assertEquals("}", run.file.toFile().readLines(Charsets.UTF_8)[2].trim(), "0 基 2 回读源文件就是那个孤立 '}'")
        // 判据级：LexError.lineNo 缺省 -1 时按「拿不到」处理，不进 LEX_ERROR
        val noPos = LexError("无位置的词法错误")
        assertEquals(-1, noPos.lineNo, "前置：LexError 默认 lineNo = -1")
        assertNull(CorpusCompileValidator.structuredLineOf(noPos), "lineNo < 0 ⇒ 判据是「没有」，不是拿 -1 冒充")
        val st = CorpusCompileValidator.structuredLineOf(LexError("带位置的词法错误", lineNo = 4))
        assertEquals(4, st?.first ?: -999)
        assertEquals(LineSource.LEX_ERROR, st?.second)
    }

    /** 非 `CompileException` 的崩溃臂：拿得到字段就拿，拿不到就如实留空；类型两臂都留。 */
    @Test
    fun crashArmKeepsStructuredLineWhenPresentAndTypeAlways() {
        val codeee = com.eratw.port.core.err.CodeEE("脚本运行错误", funcName = "ATTR_PROBE", line = 2, pc = -1)
        val st = CorpusCompileValidator.structuredLineOf(codeee)
        assertEquals(2, st?.first ?: -999, "CodeEE.line 与 Statement.line 同基（0 基）")
        assertEquals(LineSource.CODEEE, st?.second)
        val f = feed(
            "内部错误: ${codeee::class.simpleName}: ${codeee.message}",
            structuredLine = st?.first,
            lineSource = LineSource.CODEEE,
            category = CorpusCompileValidator.CAT_CRASH,
        )
        assertEquals("RETURNF", f.stmtCode, "崩溃臂拿到结构化行号时同样归因（期望 RETURNF / 实际 ${f.stmtCode}）")
        assertEquals(2, f.line)
        assertEquals(3, f.displayLine)
        assertEquals(LineSource.CODEEE, f.lineSource)
        assertTrue(f.hasStructuredLine)
        assertEquals(
            -1, CorpusCompileValidator.legacyScrapedLine(codeee.message ?: ""),
            "CodeEE 的文案不烤行号（位置走字段）⇒ 旧写法在这条上直接掉进 (未知)（message=${codeee.message}）",
        )
        // CodeEE.line = -1：来源降级为非结构化，与 COMPILE_EXCEPTION/FUNCTION_LEVEL 都分得开
        val st2 = CorpusCompileValidator.structuredLineOf(com.eratw.port.core.err.CodeEE("无位置"))
        assertEquals(-1, st2?.first ?: 999)
        assertEquals(LineSource.NO_STRUCTURED_LINE, st2?.second, "CodeEE.line = -1 不该标成 CODEEE 来源")
        // ExprParseException 也在判据表里（0 基字段）
        val expr = com.eratw.port.core.expr.ExprParseException(3, 0, "表达式解析失败")
        val st3 = CorpusCompileValidator.structuredLineOf(expr)
        assertEquals(3, st3?.first ?: -999)
        assertEquals(LineSource.EXPR_PARSE, st3?.second)
    }

    // ------------------------------------------------------------------
    // 签名分组：文案耦合度只以「前缀认没认出来」的读数形式存在
    // ------------------------------------------------------------------

    @Test
    fun signatureGroupingCountsUnstrippedPrefixInsteadOfSilentlySplitting() {
        val known = feed("编译错误(函数:ATTR_PROBE, 行:2): $probeBody", 2, LineSource.COMPILE_EXCEPTION)
        val otherFunc = feed("编译错误(函数:ATTR_OTHER, 行:99): $probeBody", 2, LineSource.COMPILE_EXCEPTION)
        assertEquals(
            known.signature, otherFunc.signature,
            "函数名/行号不该把同族失败碎成两个签名（期望 ${known.signature} / 实际 ${otherFunc.signature}）",
        )
        assertTrue(known.signaturePrefixStripped && otherFunc.signaturePrefixStripped)
        val newShape = feed("COMPILE-FAILED <<<ATTR_PROBE>>> :: RETURNF misuse", 2, LineSource.COMPILE_EXCEPTION)
        assertEquals("RETURNF", newShape.stmtCode, "文案换形状不动归因")
        assertFalse(newShape.signaturePrefixStripped, "前缀认不出来必须能被计数，而不是静默把整条消息当签名")
        assertTrue(
            newShape.signature != known.signature,
            "分组碎裂本身要看得见（实际 same=${newShape.signature == known.signature}）",
        )
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(newShape.message), "旧写法在这条上刮不到行号")
    }
}
