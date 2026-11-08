package com.eratw.port.core.compile

import com.eratw.port.core.compile.CorpusCompileValidator.LineSource
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

/**
 * 语料/编译面**失败归因**的判据来源与坐标系（lane compreadout，A14，2026-11-05）。
 *
 * 钉的是 `CorpusCompileValidator` 这条链：
 * `异常自带的行号字段（0 基）` → `Failure.line` → 切片语句反查 → 报告 / `failures.tsv` 的两基列。
 * 判据**不**再来自 message 里的「行:N」子串（旧写法留在 [CorpusCompileValidator.legacyScrapedLine]，
 * 只供本文件做「改前 vs 改后」对账）。
 *
 * 坐标系出处（逐条读生产侧赋值处核过，不是照抄 KDoc）：
 * - `Statement.line` = **0 基**：`LogicalLineReader.kt:171` `curNo` 从 0 起、`:287` 把 `curNo`
 *   直接交进 `LogicalLine.lineNo`；`LineParser.kt:141-150` 原样传进语句构造体。
 * - `CompileException.line` = **0 基**：抛点全部取语句行
 *   （`ErbProgramCompiler.kt:562/946/976/1200/1462/2857/3068` 等；`-1` = 函数级错误）。
 *   它的 message 侧已由 errtext5 收口（`CompileError.kt:24` `行:${erbDisplayLine(line)}`，本波
 *   合流头 `b44a4d91` 已含）⇒ **消息基 1 基、字段基 0 基**，旧的「按消息反查 `Statement.line`」
 *   因此整体差一行。这一笔由 [deviceAttributesTheThrowingStatementByItsZeroBasedLine] 的 canary
 *   （旧刮法 = 字段 +1）与 [oneBasedMessageMakesLegacyPointAtTheNextStatement] 两处钉住。
 * - `LexError.lineNo` = **0 基**（`LogicalLineReader.kt:220/230` 取 `curNo`）。
 * - `StmtParseException.line` = **0 基**，它的 message **已经是 1 基**
 *   （`ParseSupport.kt:41` vs `:45` `行:${erbDisplayLine(line)}`）—— 同一个异常里字段与文案
 *   今天就差一，是「按消息反查」不可信的现证（[parseExceptionFieldAndMessageDifferByOneToday]）。
 * - 对外 1 基只有一处出口：`err/CodeEE.kt:98` `erbDisplayLine`。
 *
 * 防假闸纪律：断言一律**逐条读数级**（具体行号 / 语句码 / 表格单元格 / 计数），
 * 不写 `report.contains("行")` 这种会被 import 与 KDoc 白送过去的形状。
 */
class CorpusCompileAttributionTest {

    // ------------------------------------------------------------------
    // 造具
    // ------------------------------------------------------------------

    /** 把文本按「行号 = 0 基物理行」解析成语句（[lineOffset] 对应文件里的起始物理行下标）。 */
    private fun parseLines(
        lines: List<String>,
        lineOffset: Int = 0,
        file: String = "attr-probe.erb",
    ): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, file, lineOffset + i, text))
        }
    }

    /**
     * 探针语料（物理行 ↔ 0 基下标）：
     * ```text
     * 第 1 行  @ATTR_PROBE   （0 基 0）
     * 第 2 行  PRINTL 1      （0 基 1）
     * 第 3 行  RETURNF 2     （0 基 2）← 非 #FUNCTION 内 ⇒ 编译期拒绝，抛点在这一条
     * 第 4 行  PRINTL 3      （0 基 3）← 归因差一时会被误指到这一条
     * ```
     */
    private val probeLines = listOf("@ATTR_PROBE", "PRINTL 1", "RETURNF 2", "PRINTL 3")

    /** 探针函数体切片：语句行号 = 0 基 1/2/3，`@` 头行 = 0 基 0。 */
    private fun probeSlice(): CorpusCompileValidator.Slice {
        val stmts = parseLines(probeLines.drop(1), lineOffset = 1)
        return CorpusCompileValidator.Slice(
            key = "ATTR_PROBE", params = 0, isMethod = false, stmts = stmts,
            file = "attr-probe.erb", line = 0,
        )
    }

    private class DeviceRun(val file: Path, val erbRoot: Path, val result: CorpusCompileValidator.Result)

    /** 用**真装置**跑一份 mini 语料（tempdir 下的 ERB 根）。 */
    private fun runDevice(dir: Path, content: List<String>, name: String = "ATTR_PROBE.ERB"): DeviceRun {
        val erbRoot = dir.resolve("ERB")
        Files.createDirectories(erbRoot)
        val file = erbRoot.resolve(name)
        Files.writeString(file, content.joinToString("\r\n"))
        return DeviceRun(file, erbRoot, CorpusCompileValidator.compile(listOf(file), erbRoot))
    }

    /** 只往 Result 里塞已造好的 Failure，走与生产 `addFailure` 同一套分组/计数面。 */
    private fun CorpusCompileValidator.Result.collectForTest(f: CorpusCompileValidator.Failure) {
        failures.add(f)
        failureGroups.getOrPut(f.signature) { mutableListOf() }.add(f)
        failureByCode.merge(f.stmtCode ?: "(未知)", 1L, Long::plus)
        failureByCategory.merge(f.category, 1L, Long::plus)
    }

    // ------------------------------------------------------------------
    // 1) 真装置：归因吃结构化字段；两基各一列；指的就是物理那一行
    // ------------------------------------------------------------------

    @Test
    fun deviceAttributesTheThrowingStatementByItsZeroBasedLine(@TempDir dir: Path) {
        val run = runDevice(dir, probeLines)
        val r = run.result
        assertEquals(1, r.failures.size, "探针语料应恰好 1 条编译失败，实际=${r.failures.map { it.message }}")
        val f = r.failures[0]

        assertEquals("RETURNF", f.stmtCode, "归因语句码应是抛异常的那条，实际 raw=${f.stmtRaw}")
        assertEquals("RETURNF 2", f.stmtRaw)
        assertEquals(2, f.line, "Failure.line 必须是 0 基内部下标（物理第 3 行 ⇒ 2）")
        assertEquals(3, f.displayLine, "1 基展示行号必须等于物理行号（唯一出口 erbDisplayLine）")
        assertEquals(
            "RETURNF 2",
            run.file.toFile().readLines()[f.displayLine - 1].trim(),
            "按 displayLine 回读源文件必须读到那条语句本身（「指对行」的定义）",
        )
        assertEquals(LineSource.COMPILE_EXCEPTION, f.lineSource, "行号来源要标成结构化字段，不是消息刮取")
        assertTrue(f.hasStructuredLine)
        assertTrue(f.attributed)
        assertEquals(1, f.stmtHitCount, "0 基下标 2 在切片里只该命中 1 条语句")
        assertEquals(0, f.sliceLine, "@函数 头行只作上下文列，不进 Failure.line")

        assertEquals(1L, r.failureByCode["RETURNF"], "FunctionCode 分布要记到 RETURNF，实际=${r.failureByCode}")
        assertEquals(0L, r.failureByCode["(未知)"] ?: 0L, "归因成功时 (未知) 桶必须是 0")
        assertEquals(r.failures.count { !it.attributed }.toLong(), r.unknownCodeFailures, "(未知) 与逐条读数恒等")
        assertEquals(1, r.attributedFailures)
        assertEquals(1, r.structuredLineFailures)
        assertEquals(0, r.unstructuredLineFailures)

        // errtext5 之后 message 内嵌的是 **1 基**（`CompileError.kt:24` 走 `erbDisplayLine`），
        // 而 `Failure.line` 恒 0 基 ⇒ 旧刮法与字段**相差 1**。这正是装置改读异常字段的原因：
        // 旧写法在本条上会把同一条失败指到物理第 4 行那条 `PRINTL 3`。
        assertEquals(3, CorpusCompileValidator.legacyScrapedLine(f.message), "旧→新：message 内嵌 1 基行号 = 字段 +1")
        assertEquals(f.line + 1, CorpusCompileValidator.legacyScrapedLine(f.message), "旧刮法（1 基）与结构化字段（0 基）差一")
        assertEquals("PRINTL 3", probeLines[CorpusCompileValidator.legacyScrapedLine(f.message)],
            "把 1 基数当 0 基下标用 ⇒ 落到抛点的下一行（差一现证；这正是换判据的理由）")
    }

    /** 报告与 `failures.tsv` 的**逐格**读数。 */
    @Test
    fun reportAndTsvCarryBothBasesAndLineSource(@TempDir dir: Path) {
        val run = runDevice(dir, probeLines)
        val r = run.result
        assertEquals(1, r.failures.size)
        val f = r.failures[0]
        val out = CorpusCompileValidator.writeReport(r, run.erbRoot, dir.resolve("report"))

        val tsv = Files.readAllLines(out.resolve("failures.tsv"))
        val header = tsv[0].split("\t")
        assertEquals(
            listOf(
                "file", "line0", "line1", "lineSource", "attributed", "stmtHits", "sliceLine0", "sliceLine1",
                "function", "category", "stmtCode", "signature", "prefixStripped", "message", "stmtRaw",
            ),
            header,
            "failures.tsv 必须把两基与行号来源各自成列",
        )
        val cols = tsv[1].split("\t")
        fun col(name: String): String = cols[header.indexOf(name)]
        assertEquals("2", col("line0"))
        assertEquals("3", col("line1"))
        assertEquals("CompileException.line(0基)", col("lineSource"))
        assertEquals("yes", col("attributed"))
        assertEquals("1", col("stmtHits"))
        assertEquals("0", col("sliceLine0"))
        assertEquals("1", col("sliceLine1"), "@函数 头行的 1 基 = 物理第 1 行")
        assertEquals("RETURNF", col("stmtCode"))
        assertEquals("yes", col("prefixStripped"))
        assertEquals(f.file, col("file"))
        assertEquals(f.displayLine.toString(), col("line1"), "tsv 的 1 基列必须就是 erbDisplayLine 的读数")
        assertEquals(erbDisplayLine(f.sliceLine).toString(), col("sliceLine1"))

        val md = Files.readAllLines(out.resolve("summary.md"))
        val rows = md.filter { it.startsWith("|") }.map { row -> row.trim('|').split("|").map { it.trim() } }
        fun readout(label: String): String = rows.first { it[0] == label }[1]
        assertEquals("1", readout("编译失败条数"))
        assertEquals("1", readout("有结构化行号"))
        assertEquals("0", readout("无结构化行号"))
        assertEquals("1", readout("归因成功（命中切片语句）"))
        assertEquals("100.0%", readout("归因成功率"))
        assertEquals("0", readout("有行号但切片里查无此行"))
        assertEquals("0", readout("(未知) 桶"))
        assertEquals("0", readout("签名前缀未剥离"))
        // 行号来源分布表里必须出现结构化来源这一格
        assertTrue(
            rows.any { it[0] == LineSource.COMPILE_EXCEPTION.label && it[1] == "1" },
            "§4A 行号来源分布要按来源逐条计数；实际来源行=${rows.filter { it.size == 2 }.take(6)}",
        )
        // §5 给人的复现行是 1 基，并把 0 基留在旁边
        assertTrue(
            md.any { it.startsWith("- 复现(最小样本):") && it.contains(":3`") && it.contains("0 基下标 2") },
            "§5 复现行应以 1 基定位、同时标出 0 基内部下标；实际=${md.filter { it.startsWith("- 复现") }}",
        )
    }

    // ------------------------------------------------------------------
    // 2) 自证闸：message 形状怎么改都不动归因（逐条读数级）
    // ------------------------------------------------------------------

    private val probeBody = "RETURNF 只能在 #FUNCTION/#FUNCTIONS 内使用（spec: FunctionIdentifier.cs:3455-3458）"

    private fun attributeWith(message: String) = CorpusCompileValidator.toFailure(
        slice = probeSlice(),
        key = "ATTR_PROBE",
        message = message,
        category = CorpusCompileValidator.CAT_RULE,
        structuredLine = 2,
        lineSource = LineSource.COMPILE_EXCEPTION,
    )

    @Test
    fun messageShapeDoesNotMoveAttribution() {
        val shapes = linkedMapOf(
            "旧 0 基前缀（errtext5 前）" to "编译错误(函数:ATTR_PROBE, 行:2): $probeBody",
            "现基 1 基前缀（errtext5 后）" to "编译错误(函数:ATTR_PROBE, 行:3): $probeBody",
            "整族换文案" to "COMPILE-FAILED <<<ATTR_PROBE>>> @L3 :: RETURNF misuse",
            "带诱饵行号" to "编译错误(函数:ATTR_PROBE, 行:999): $probeBody",
            "空消息" to "",
        )
        for ((name, msg) in shapes) {
            val f = attributeWith(msg)
            assertEquals(2, f.line, "$name：0 基行号恒 2，不跟 message 走")
            assertEquals(3, f.displayLine, "$name：1 基恒 3（物理第 3 行）")
            assertEquals("RETURNF", f.stmtCode, "$name：五种形状下都必须归到 RETURNF 这一条")
            assertEquals("RETURNF 2", f.stmtRaw, "$name：归因原文同上")
            assertEquals(1, f.stmtHitCount, "$name：命中条数同上")
            assertEquals(LineSource.COMPILE_EXCEPTION, f.lineSource, "$name：来源标注同上")
            assertTrue(f.attributed && f.hasStructuredLine, "$name：归因与背书同上")
        }
        // 反向自证：旧刮法在这五种形状上**会**漂 —— 这就是本枚用例存在的理由
        assertEquals(2, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("旧 0 基前缀（errtext5 前）")))
        assertEquals(3, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("现基 1 基前缀（errtext5 后）")))
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("整族换文案")))
        assertEquals(999, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("带诱饵行号")))
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(shapes.getValue("空消息")))
    }

    /**
     * **差一用例**：errtext 那笔（`f843f46d`：`CompileError.kt` 的消息基换 1 基）合进来后，
     * 旧的正则刮取会把同一条失败指到**下一条语句**；语句尾行时干脆整条掉进 `(未知)`。
     * 新判据（读 `CompileException.line`）两种形状都不动。
     */
    @Test
    fun oneBasedMessageMakesLegacyPointAtTheNextStatement() {
        val slice = probeSlice()
        val oneBasedMessage = "编译错误(函数:ATTR_PROBE, 行:3): $probeBody"
        val legacyLine = CorpusCompileValidator.legacyScrapedLine(oneBasedMessage)
        val legacyStmt = slice.stmts.firstOrNull { it.line == legacyLine }
        assertEquals(3, legacyLine, "旧路径在 1 基消息上刮到的是 1 基数")
        assertEquals("PRINTL", legacyStmt?.code?.name, "旧路径因此指到了下一条语句（差一现证）")
        assertEquals("PRINTL 3", legacyStmt?.raw)

        val f = CorpusCompileValidator.toFailure(
            slice, "ATTR_PROBE", oneBasedMessage, CorpusCompileValidator.CAT_RULE,
            structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION,
        )
        assertEquals(2, f.line)
        assertEquals("RETURNF", f.stmtCode)
        assertNotEquals(legacyStmt?.code?.name, f.stmtCode, "新旧路径必须在这一格分岔，否则本用例没测到差一")

        // 抛点在切片尾行：旧路径连「错的一条」都没有 ⇒ 整条 (未知)
        val tailSlice = CorpusCompileValidator.Slice(
            key = "ATTR_TAIL", params = 0, isMethod = false,
            stmts = parseLines(listOf("PRINTL 1", "RETURNF 2"), lineOffset = 0), file = "t.erb", line = -1,
        )
        val tailMessage = "编译错误(函数:ATTR_TAIL, 行:2): $probeBody"
        val legacyTail = CorpusCompileValidator.legacyScrapedLine(tailMessage)
        assertEquals(2, legacyTail)
        assertNull(tailSlice.stmts.firstOrNull { it.line == legacyTail }, "尾行差一 ⇒ 查无此行 ⇒ (未知)")
        val newTail = CorpusCompileValidator.toFailure(
            tailSlice, "ATTR_TAIL", tailMessage, CorpusCompileValidator.CAT_RULE,
            structuredLine = 1, lineSource = LineSource.COMPILE_EXCEPTION,
        )
        assertEquals("RETURNF", newTail.stmtCode, "同一形状，新路径照样归到抛点")
    }

    /**
     * **今天就已经差一**的一族（不需要等 errtext）：解析层 `StmtParseException`
     * 字段 0 基、message 已 1 基（`ParseSupport.kt:41` vs `:45`）。
     * 编译装置的解析臂过去用的是「当前逻辑行的行号」（0 基，恰好与字段同基），
     * 现在显式改读异常字段并标注来源。
     */
    @Test
    fun parseExceptionFieldAndMessageDifferByOneToday() {
        val e = assertFailsWith<StmtParseException> {
            LineParser(SymbolTable()).parseCodeLine("= 1", 7)
        }
        assertEquals(7, e.line, "字段是 0 基")
        assertEquals(8, e.displayLine, "异常自己的 1 基出口 = 字段 +1")
        val scraped = CorpusCompileValidator.legacyScrapedLine(e.message ?: "")
        assertEquals(8, scraped, "message 里烤的是 1 基")
        assertNotEquals(e.line, scraped, "同一异常里字段与文案不等 ⇒ 按消息反查必差一")
        val st = CorpusCompileValidator.structuredLineOf(e)
        assertEquals(7, st?.first, "本装置判据取字段（0 基）")
        assertEquals(LineSource.STMT_PARSE, st?.second)
    }

    // ------------------------------------------------------------------
    // 3) 无结构化行号 ⇒ 如实记，不伪装成切片头行
    // ------------------------------------------------------------------

    @Test
    fun crashWithoutStructuredLineIsRecordedHonestlyNotAsSliceHead() {
        val slice = probeSlice()
        val decoy = IllegalStateException("内部炸了，顺手转述了一句「行:9」")
        val st = CorpusCompileValidator.structuredLineOf(decoy)
        assertNull(st, "裸异常没有位置字段 ⇒ 判据必须是「没有」")
        val f = CorpusCompileValidator.toFailure(
            slice, "ATTR_PROBE", "内部错误: ${decoy::class.simpleName}: ${decoy.message}",
            CorpusCompileValidator.CAT_CRASH, structuredLine = st?.first,
            lineSource = st?.second ?: LineSource.NO_STRUCTURED_LINE,
        )
        assertEquals(-1, f.line, "无结构化行号 ⇒ 如实 -1，不许取消息诱饵里的 9")
        assertNotEquals(9, f.line)
        assertNotEquals(slice.line, f.line, "更不许伪装成 @函数 头行（旧实现干的就是这件事）")
        assertEquals(-1, f.displayLine, "无位置的 -1 原样返回，不变成 0")
        assertEquals(0, f.sliceLine, "切片头行只活在上下文列")
        assertEquals(LineSource.NO_STRUCTURED_LINE, f.lineSource)
        assertFalse(f.hasStructuredLine)
        assertFalse(f.attributed)
        assertNull(f.stmtCode)
        assertEquals(0, f.stmtHitCount)

        val r = CorpusCompileValidator.Result()
        r.collectForTest(f)
        assertEquals(1, r.failures.size)
        assertEquals(0, r.structuredLineFailures)
        assertEquals(1, r.unstructuredLineFailures)
        assertEquals(0, r.lineMissFailures, "「压根没行号」与「有行号查无」必须分栏")
        assertEquals(1L, r.unknownCodeFailures)
        assertEquals(0L, r.failureByCode["RETURNF"] ?: 0L)
        assertEquals(0, r.attributedFailures)
        assertEquals(0.0, r.attributionRatePercent, 1e-9)
        assertEquals(mapOf(LineSource.NO_STRUCTURED_LINE.label to 1L), r.failureByLineSource)
    }

    /** 函数级错误（`CompileException.line = -1`）与「拿不到行号」在读数上必须分得开。 */
    @Test
    fun functionLevelErrorIsDistinctFromMissingLine() {
        val slice = probeSlice()
        val e = CompileException("ATTR_PROBE", -1, "函数重复声明: ATTR_PROBE")
        val st = CorpusCompileValidator.structuredLineOf(e)
        assertEquals(-1, st?.first)
        assertEquals(LineSource.FUNCTION_LEVEL, st?.second, "-1 是异常声明的函数级语义，不是缺字段")
        assertTrue(LineSource.FUNCTION_LEVEL.structured)
        assertFalse(LineSource.NO_STRUCTURED_LINE.structured)
        val f = CorpusCompileValidator.toFailure(
            slice, "ATTR_PROBE", e.message ?: "", CorpusCompileValidator.CAT_RULE,
            structuredLine = st?.first, lineSource = st!!.second,
        )
        assertEquals(-1, f.line)
        assertEquals(0, f.sliceLine, "上下文列照旧给 @函数 头行")
        assertTrue(f.hasStructuredLine, "结构化 -1 与「无结构化行号」不是一回事")
        assertFalse(f.attributed)
        // 旧实现在这一格上是**伪装**：刮到 -1 ⇒ line = slice.line（把函数头行报成错误行）
        assertEquals(-1, CorpusCompileValidator.legacyScrapedLine(e.message ?: ""))
    }

    // ------------------------------------------------------------------
    // 4) LexError / 解析臂：结构化行号 + 异常类型都留在读数里
    // ------------------------------------------------------------------

    @Test
    fun lexErrorArmKeepsZeroBasedLineAndExceptionType(@TempDir dir: Path) {
        val run = runDevice(
            dir,
            listOf(
                "@LEX_PROBE",
                "PRINTL 1",
                "}", // 孤立行连结终止符 ⇒ LexError（LogicalLineReader.kt:220，行号取 0 基 curNo）
                "PRINTL 2",
            ),
            name = "LEX_PROBE.ERB",
        )
        val r = run.result
        assertEquals(1, r.parseFailureLines, "词法错误要计一条解析失败行，实际=${r.parseFailureSamples}")
        assertEquals(1, r.parseFailureLinesStructured, "行号来自 LexError.lineNo（结构化字段）")
        assertEquals(0, r.parseFailureLinesUnstructured)
        assertEquals(1, r.parseFailures, "含失败行的切片要毒化跳过")
        assertEquals(0, r.failures.size, "被毒化的切片不进编译分母")
        val g = r.parseGroups.values.single()
        assertEquals(1, g.types["LexError"], "异常类型是结构化读数，不靠「词法错误」四个字")
        assertEquals(1, g.lineSources[LineSource.LEX_ERROR.label])
        assertTrue(g.sample.contains(":3(1基; 0基=2)"), "样本行号必须标明几基；实际=${g.sample}")
    }

    @Test
    fun parseArmUsesTheExceptionLineFieldAndKeepsType(@TempDir dir: Path) {
        val run = runDevice(
            dir,
            listOf("@PARSE_PROBE", "PRINTL 1", "= 1", "PRINTL 2"),
            name = "PARSE_PROBE.ERB",
        )
        val r = run.result
        assertEquals(1, r.parseFailureLines, "实际样本=${r.parseFailureSamples}")
        assertEquals(1, r.parseFailureLinesStructured, "解析异常带 0 基字段 ⇒ 记成结构化")
        val g = r.parseGroups.values.single()
        assertEquals(1, g.types["StmtParseException"], "类型读数不靠文案；实际=${g.typeSummary}")
        assertEquals(1, g.lineSources[LineSource.STMT_PARSE.label])
        assertTrue(g.sample.contains(":3(1基; 0基=2)"), "0 基字段 2 ⇒ 给人 1 基 3；实际=${g.sample}")
    }

    /** 三条计数恒等式：来源分类不许漏条、不许有暗道。 */
    @Test
    fun lineSourceCountsAreClosed(@TempDir dir: Path) {
        val run = runDevice(dir, probeLines)
        val r = run.result
        assertEquals(r.parseFailureLines, r.parseFailureLinesStructured + r.parseFailureLinesUnstructured)
        assertEquals(r.failures.size, r.structuredLineFailures + r.unstructuredLineFailures)
        assertEquals(
            r.failures.size, r.attributedFailures + r.lineMissFailures + r.unstructuredLineFailures,
            "归因成功 / 有行号查无 / 无结构化行号 三栏必须正好盖满失败条数",
        )
    }

    // ------------------------------------------------------------------
    // 5) 签名分组：归因不看文案，文案换形状只让「未剥离」读数跳出来
    // ------------------------------------------------------------------

    @Test
    fun signatureGroupsStablyAcrossFunctionNameAndLine() {
        val a = CorpusCompileValidator.toFailure(
            probeSlice(), "ATTR_PROBE", "编译错误(函数:ATTR_PROBE, 行:2): $probeBody",
            CorpusCompileValidator.CAT_RULE, structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION,
        )
        val b = CorpusCompileValidator.toFailure(
            probeSlice(), "ATTR_OTHER", "编译错误(函数:ATTR_OTHER, 行:99): $probeBody",
            CorpusCompileValidator.CAT_RULE, structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION,
        )
        assertEquals(a.signature, b.signature, "函数名/行号不该把同一族失败碎成两个签名")
        assertTrue(a.signaturePrefixStripped && b.signaturePrefixStripped)
        val c = CorpusCompileValidator.toFailure(
            probeSlice(), "ATTR_PROBE", "COMPILE-FAILED <<<ATTR_PROBE>>> :: RETURNF misuse",
            CorpusCompileValidator.CAT_RULE, structuredLine = 2, lineSource = LineSource.COMPILE_EXCEPTION,
        )
        assertEquals("RETURNF", c.stmtCode, "前缀换形状也不动归因")
        assertFalse(c.signaturePrefixStripped, "前缀认不出来要被计数，不能静默")
        assertNotEquals(a.signature, c.signature)
        val r = CorpusCompileValidator.Result()
        r.collectForTest(a)
        r.collectForTest(c)
        assertEquals(1, r.unstrippedSignatures)
        assertEquals(2, r.failureGroups.size, "分组碎裂的条数就是报告 §4A「签名前缀未剥离」要看的那一格")
    }

    /** 1 基出口只有一处：`-1` 原样、`0 → 1`。 */
    @Test
    fun displayLineUsesTheSingleOutlet() {
        assertEquals(3, erbDisplayLine(2))
        assertEquals(1, erbDisplayLine(0))
        assertEquals(-1, erbDisplayLine(-1), "无位置的 -1 原样返回，不变成 0")
    }

    // ------------------------------------------------------------------
    // 6) 4.980 语料对账（env 门；默认跳过，不拖慢全闸）
    // ------------------------------------------------------------------

    /**
     * 在 4.980 语料根跑一遍本装置的编译面，把归因读数落到
     * `build/compile-report-attribution/`（`ERATW_COMPILE_REPORT` 可覆盖）。开法：
     * ```
     * ERATW_CORPUS=<语料根>/ERB ERATW_ATTRIBUTION_CORPUS=1 ./gradlew :core-vm:test --tests "*CorpusCompileAttributionTest*"
     * ```
     * 只读语料、只写 build/ 下；不改判停、不缩清单（agents.md §5 的 ERB 子树口径）。
     */
    @Test
    fun corpus4980AttributionReadout() {
        val corpus = System.getenv("ERATW_CORPUS")
        val flag = System.getenv("ERATW_ATTRIBUTION_CORPUS")
        Assumptions.assumeTrue(
            !corpus.isNullOrBlank() && flag == "1",
            "未设 ERATW_CORPUS 或 ERATW_ATTRIBUTION_CORPUS≠1：语料对账按假设跳过（计 skipped，不静默 PASS）",
        )
        val root = Paths.get(corpus)
        val files = CorpusCompileValidator.listCorpusFiles(root)
        val r = CorpusCompileValidator.compile(files, root)
        r.runScope = if (files.size >= 3_900) "FULL" else "LIMITED(${files.size})"
        val out = CorpusCompileValidator.writeReport(
            r, root,
            Paths.get(System.getenv("ERATW_COMPILE_REPORT") ?: "build/compile-report-attribution"),
        )
        println("语料对账：文件 ${r.files} 切片 ${r.slicesTotal} 编译失败 ${r.failures.size} 解析失败 ${r.parseFailures} 切片/${r.parseFailureLines} 行")
        println(
            (
                "归因读数（新·结构化判据）：有结构化行号 ${r.structuredLineFailures} / 无结构化 ${r.unstructuredLineFailures} / " +
                    "归因成功 ${r.attributedFailures}（%.1f%%）/ 有行号查无 ${r.lineMissFailures} / (未知) ${r.unknownCodeFailures} / " +
                    "前缀未剥离 ${r.unstrippedSignatures}"
                ).format(r.attributionRatePercent),
        )
        val legacyUnknown = r.failures.count { CorpusCompileValidator.legacyScrapedLine(it.message) < 0 }
        val legacyDrift = r.failures.count {
            val l = CorpusCompileValidator.legacyScrapedLine(it.message)
            l >= 0 && l != it.line
        }
        println("归因读数（旧·同批失败按 message 刮取反推）：掉进 (未知) $legacyUnknown / 与结构化字段不等 $legacyDrift")
        println("报告: $out/summary.md")
        assertEquals(r.failures.size, r.structuredLineFailures + r.unstructuredLineFailures)
        assertEquals(r.parseFailureLines, r.parseFailureLinesStructured + r.parseFailureLinesUnstructured)
    }

    /**
     * 同一语料的**前 N 个文件**（LIMITED 子集，prescan 符号表不完整 ⇒ 会出真失败行）：
     * 绿态 FULL 的失败集是空集，解析臂那几条新读数（异常类型 / 行号来源 / 几基样本）在 FULL 上
     * 全是 0，看不出接线是否真的在跑。这一枚只打印、不判闸（判闸永远是 FULL 的 `:corpusSmoke`）。
     *
     * 用途：给交付报告摆「本装置在真语料上拿到的结构化读数长什么样」，
     * 并核 FULL/LIMITED 两种形状下 `parseFailureLines = 结构化 + 回落` 恒等式都成立。
     */
    @Test
    fun corpusSubsetExposesParseArmReadouts() {
        val corpus = System.getenv("ERATW_CORPUS")
        val flag = System.getenv("ERATW_ATTRIBUTION_CORPUS")
        Assumptions.assumeTrue(
            !corpus.isNullOrBlank() && flag == "1",
            "未设 ERATW_CORPUS 或 ERATW_ATTRIBUTION_CORPUS≠1：子集读数按假设跳过",
        )
        val root = Paths.get(corpus)
        val subsetFiles = (System.getenv("ERATW_ATTRIBUTION_LIMIT") ?: "700").toInt()
        val files = CorpusCompileValidator.listCorpusFiles(root).take(subsetFiles)
        val r = CorpusCompileValidator.compile(files, root)
        r.runScope = "LIMITED(${files.size})"
        println("子集读数：文件 ${r.files} 切片 ${r.slicesTotal} 编译失败 ${r.failures.size} 解析失败 ${r.parseFailures} 切片/${r.parseFailureLines} 行")
        println(
            "解析失败行号来源：结构化字段 ${r.parseFailureLinesStructured} / 回落当前逻辑行 ${r.parseFailureLinesUnstructured}" +
                "（恒等式 ${r.parseFailureLinesStructured + r.parseFailureLinesUnstructured} == ${r.parseFailureLines}）",
        )
        for (g in r.parseGroups.values.sortedByDescending { it.lines }) {
            println("  组 ${g.lines} 行 / ${g.slices} 切片 ｜类型 ${g.typeSummary}｜行号来源 ${g.lineSourceSummary}｜签名 ${g.signature}")
        }
        println(
            "编译失败归因：有结构化行号 ${r.structuredLineFailures} / 无结构化 ${r.unstructuredLineFailures} / " +
                "归因成功 ${r.attributedFailures} / (未知) ${r.unknownCodeFailures} / 前缀未剥离 ${r.unstrippedSignatures}",
        )
        assertEquals(r.parseFailureLines, r.parseFailureLinesStructured + r.parseFailureLinesUnstructured)
        assertEquals(r.failures.size, r.structuredLineFailures + r.unstructuredLineFailures)
        assertEquals(r.failures.size, r.attributedFailures + r.lineMissFailures + r.unstructuredLineFailures)
        assertTrue(files.isNotEmpty(), "子集清单为空：ERATW_CORPUS 口径有问题")
    }
}
