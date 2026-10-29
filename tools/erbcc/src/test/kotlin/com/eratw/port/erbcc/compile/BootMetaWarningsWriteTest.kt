package com.eratw.port.erbcc.compile

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.erbcc.LoadWarning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 装载期告警 → `program.meta` 侧车 `load-warnings` 段（M11-E warnmeta）的写出端对拍。
 *
 * 判据链：`CorpusCompile.compile` 组装的 [CompileResult.warnings]（save-15a 通道：
 * `SymbolTable.duplicateFunctionWarnings` → `CorpusScan.warnings` → 语句层
 * `LineParser.warnings`，段序固定）⇒ 逐条直存进 [BootMetadata.loadWarnings] ⇒
 * [BootMetaWriter] 落盘 ⇒ [BootMetadata.read] 读回，三处必须逐条一致；
 * 另锁两条不回归：`program-sha256` 配对（判据⑤）与侧车字节确定性。
 *
 * 告警文案/行号口径与 `LoadWarningsGoldenTest`（基准
 * `.workbuddy/golden-pc-loadwarnings.txt`）同源：合成语料刻意复刻基准的两类触发点 ——
 * 非事件 `@` 重名（spec: ErbLoader.cs:441-451）与 INPUTS 参数过多
 * （spec: ArgumentBuilder.cs:30-40，触发点 ArgumentParser.cs:1157）。
 *
 * 空清单与 null 的区分、计数校验细节在 core-vm 的 `BootMetaWarningsSectionTest`；
 * 本类只管生产管线接线。
 */
class BootMetaWarningsWriteTest {

    /** 两类告警各一条的最小合成语料（段序 = 符号表在前、语句层在后）。 */
    private fun writeWarnCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("01_dup.ERB"),
            """
            @DUP
            PRINTL FIRST
            @DUP
            PRINTL SECOND
            INPUTS "ANS", 0, 1
            """.trimIndent().toByteArray(Charsets.UTF_8) + "\n".toByteArray(),
        )
    }

    /** 无告警触发点的干净语料（同 `BootMetaRoundTripTest` 的拓扑，通道仍必须写出）。 */
    private fun writeCleanCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8))
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @SYSTEM_TITLE
            PRINTFORML INIT={G_CONST}
            PRINTL HELLO
            """.trimIndent().toByteArray(Charsets.UTF_8) + "\n".toByteArray(),
        )
    }

    private fun CompileResult.warningsAsEntries(): List<BootMetadata.LoadWarning> =
        warnings.map { BootMetadata.LoadWarning(it.file, it.line, it.message) }

    private fun render(w: LoadWarning): String = "${w.file}:${w.line} | ${w.message}"

    // ------------------------------------------------------------------

    @Test
    fun `compileCorpus 的告警清单逐条进侧车并读回一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeWarnCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败: ${result.failures.take(5)}")

        // 1) 通道内容 = save-15a 两类告警，文案/行号按 golden 口径（1 基行号）
        val rendered = result.warnings.map(::render)
        assertTrue(
            rendered.contains(
                "01_dup.ERB:3 | 函数\"@DUP\"已经被定义过(01_dup.ERB的1行)",
            ),
            "重复非事件定义必须进清单（ErbLoader.cs:441-451）；实际: $rendered",
        )
        assertTrue(
            rendered.contains("01_dup.ERB:5 | INPUTS命令:参数过多"),
            "参数过多必须进清单（ArgumentBuilder.cs:30-40）；实际: $rendered",
        )
        // 段序：符号表告警在语句层告警之前（装载第一遍 → ParseScript）
        val iDup = rendered.indexOfFirst { it.startsWith("01_dup.ERB:3 |") }
        val iArgs = rendered.indexOfFirst { it.endsWith("INPUTS命令:参数过多") }
        assertTrue(iDup in 0 until iArgs, "段序必须「重复定义 → 语句参数」；实际: $rendered")

        // 2) 模型接线：bootMetadata.loadWarnings = warnings 逐条直存（保序、不去重、不重排）
        val entries = result.warningsAsEntries()
        assertEquals(result.warnings.size, entries.size)
        assertEquals(entries, result.bootMetadata.loadWarnings, "侧车模型必须携带同一份清单")

        // 3) 落盘 → 读回：文本闭环 + 段位次（str-constants 之后、end 之前）
        val text = result.bootMetadata.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals(entries, back.loadWarnings, "文本往返逐条一致")
        assertEquals(text, back.encodeText(), "模型 → 文本 → 模型 → 文本 逐字节闭环")
        val lines = text.split('\n')
        val iWarn = lines.indexOfFirst { it.startsWith("load-warnings ") }
        val iStr = lines.indexOfFirst { it.startsWith("str-constants ") }
        val iEnd = lines.indexOf("end")
        assertTrue(iStr in 0 until iWarn, "新段必须排在 str-constants 之后: iStr=$iStr iWarn=$iWarn")
        assertTrue(iWarn < iEnd, "新段必须在 end 之前: iWarn=$iWarn iEnd=$iEnd")
        assertEquals(
            entries.size,
            lines.count { it.startsWith("load-warn ") },
            "段头计数必须等于条目行数",
        )
    }

    @Test
    fun `侧车文件读回的告警与 program-sha256 配对同时成立`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeWarnCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        val metaPath = assertNotNull(report.metaPath, "正式调用点必须写侧车")
        val read = BootMetadata.read(metaPath)

        // 判据③：设备侧读回的清单 = 编译侧清单（逐条 file/line/message）
        assertEquals(result.warningsAsEntries(), read.loadWarnings, "落盘读回与 CompileResult.warnings 一致")
        // 判据⑤：program-sha256 对账口径不回归（ErbcArtifacts 既有语义）
        assertEquals(report.artifact.sha256, read.programSha256, "侧车头部必须等于容器摘要")
        assertEquals(report.artifact.sha256, ErbcArtifacts.sha256Hex(Files.readAllBytes(report.artifactPath)))
        assertEquals(report.meta!!.sha256, ErbcArtifacts.sha256Hex(Files.readAllBytes(metaPath)))
    }

    @Test
    fun `两次编译告警段字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeWarnCorpus(corpus)
        val t1 = CorpusCompile.compile(corpus).bootMetadata.encodeText()
        val t2 = CorpusCompile.compile(corpus).bootMetadata.encodeText()
        assertEquals(t1, t2, "同一语料两次编译侧车逐字节相同（告警清单保序直存，不引入运行期状态）")
    }

    @Test
    fun `干净语料也写出空清单段 与无通道的 null 区分明确`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCleanCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "干净语料必须编译通过；失败: ${result.failures.take(5)}")

        // 生产管线通道恒开：清单非 null（哪怕零告警）⇒ 段必写（含 `load-warnings 0`）
        val entries = result.bootMetadata.loadWarnings
        assertNotNull(entries, "CorpusCompile 产出必须带告警通道（null = 没有通道，只属于旧调用点）")
        assertEquals(result.warnings.size, entries.size, "清单条数 = CompileResult.warnings 条数")
        val text = result.bootMetadata.encodeText()
        val expectedHead = if (entries.isEmpty()) "load-warnings 0" else "load-warnings ${entries.size}"
        assertTrue(text.contains("$expectedHead\n"), "段头计数必须与实际条数一致: $expectedHead")

        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        val read = BootMetadata.read(report.metaPath!!)
        assertEquals(entries, read.loadWarnings, "空/零清单经落盘读回仍与 null 区分明确")
    }

    @Test
    fun `无通道元数据经新写出端仍是旧语法字节（新写出旧读兼容通道）`() {
        // 旧调用点/手构元数据（loadWarnings = null）经新写出端：不得出现本段，
        // 文本可被任何版本读侧接受 —— 「新写出·旧读不炸」的字节级判据。
        val m = BootMetadata(functions = result0Functions(), engineTag = "erbcc compat-probe")
        val text = BootMetaWriter.text(m, programSha256 = null)
        assertTrue(!text.contains("load-warnings"), "无通道模型不得写出本段: $text")
        val back = BootMetadata.parse(text)
        assertNull(back.loadWarnings)
        assertEquals(text, back.encodeText(), "兼容通道闭环")
    }

    /** 最小可用函数元数据一条（只为让侧车非平凡，不参与断言）。 */
    private fun result0Functions(): List<com.eratw.port.core.proc.FuncMeta> = listOf(
        com.eratw.port.core.proc.FuncMeta(
            index = 0, key = "MAIN", name = "MAIN", defIndex = 1, position = "main.ERB:1",
        ),
    )
}
