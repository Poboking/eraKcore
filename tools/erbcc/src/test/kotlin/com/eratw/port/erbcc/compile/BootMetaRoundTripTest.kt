package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintDataStmt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 侧车落盘 + 装载的等价性对拍（BOOT-SESSION 的关键判据）。
 *
 * 判据：**同一份合成语料，两条路各跑一次 `BEGIN TITLE`，控制台行逐行相同**：
 * - 路 A：`CorpusCompile.compile` → 直接建 [GameSession]（元数据在内存里）；
 * - 路 B：`ErbcArtifacts.write`（同时落 `program.erbc` + `program.meta`）→
 *   [ProgramLoader] + [BootMetadata.read] → [GameSession]。
 *
 * 外加两条硬约束的读数：
 * - 侧车两次写出**逐字节相同**；
 * - 落侧车**不改** `program.erbc` 的字节；`manifest.txt` 只按 CMP-74 的新口径多登记
 *   侧车条目（旧断言「侧车不进清单」已随缺陷一起撤销）。
 *
 * 合成语料刻意包含三类「只在侧车里」的信息：全局 `#DIM CONST`、私有 `#DIM`（含私有 CONST）、
 * `PRINTDATA` 数据节 —— 任意一类没进侧车，路 B 的输出就会与路 A 分叉。
 */
class BootMetaRoundTripTest {

    // ==================================================================
    // 合成语料
    // ==================================================================

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        fun erb(name: String, text: String) {
            Files.write(root.resolve(name), text.toByteArray(Charsets.UTF_8))
        }
        // ERH 里的全局常量：装载期由 SystemVariableBootstrap.install 从侧车的 globalDims 原文注册
        erb("00_const.ERH", "#DIM CONST G_CONST = 7\n")
        erb(
            "01_main.ERB",
            """
            ; 合成小语料：全局常量 + 私有 #DIM + 数据节 + 标签
            @SYSTEM_TITLE
            #DIM LOC_OK
            #DIM CONST LOC_C = 3
            LOC_OK = G_CONST + LOC_C
            PRINTFORML INIT={LOC_OK}
            PRINTL HELLO
            ${'$'}LOOP_TAIL
            PRINTDATA
            DATA AAA
            DATA BBB
            ENDDATA
            PRINTL TAILED
            """.trimIndent(),
        )
    }

    private fun compiled(corpus: Path): CompileResult {
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        return r
    }

    /** 两条路共用的会话参数（宽度/种子必须一致，否则 PRINTDATA 的随机组会分叉）。 */
    private fun session(root: Path, program: Program, metadata: BootMetadata): GameSession = GameSession(
        gameRoot = root,
        program = program,
        metadata = metadata,
        consoleWidth = 199,
        randSeed = 20260226L,
    )

    private fun titleLines(session: GameSession): List<String> {
        try {
            assertTrue(session.begin(BeginType.TITLE), "SYSTEM_TITLE 存在，begin 应执行它")
            return session.console.loggedLines()
        } finally {
            session.close()
        }
    }

    // ==================================================================
    // 等价性
    // ==================================================================

    @Test
    fun `源码直建与 erbc 加侧车装载 两条路 BEGIN TITLE 逐行相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = compiled(corpus)
        val metadata = result.bootMetadata

        // 路 A：内存产物直建
        val linesA = titleLines(session(dir, result.program!!, metadata))

        // 路 B：落盘（含侧车）→ 装载
        val report = ErbcArtifacts.write(dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats, metadata)
        val metaPath = assertNotNull(report.metaPath, "落盘必须同时写侧车")
        assertEquals(BootMetaWriter.FILE_NAME, metaPath.fileName.toString())
        val loadedProgram = ProgramLoader.loadFile(report.artifactPath)
        val loadedMeta = BootMetadata.read(metaPath)
        val linesB = titleLines(session(dir, loadedProgram, loadedMeta))

        assertEquals(linesA, linesB, "两条路的控制台行必须逐行相同（A=$linesA / B=$linesB）")
        // 语义自证：三类只在侧车里的信息都真的生效了
        assertTrue(linesA.contains("INIT=10"), "全局 7 + 私有 3 必须算出 10（侧车 #DIM 真的注册了）: $linesA")
        // PRINTDATA 的 DATA 不带换行（原版 DAT1DL1 同形，基准 pc-golden-root.txt:238），
        // 选中项与下一条 PRINTL TAILED 拼在同一显示行。
        assertTrue(linesA.any { it.endsWith("TAILED") }, "ENDDATA 之后的语句照常执行: $linesA")
        assertTrue(linesA.any { it.startsWith("AAA") || it.startsWith("BBB") }, "PRINTDATA 必须命中侧车里的数据节: $linesA")
        assertTrue(loadedMeta.labels.containsKey("SYSTEM_TITLE"), "装载后的标签表仍按函数键索引: ${loadedMeta.labels.keys}")
        assertEquals("01_main.ERB", loadedMeta.fileOf("system_title"), "函数键 → 源文件（大小写归一）")
        assertEquals(report.artifact.sha256, loadedMeta.programSha256, "侧车头部的 program-sha256 必须等于容器摘要")
    }

    @Test
    fun `侧车两次写出逐字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val r1 = compiled(corpus)
        val r2 = compiled(corpus)

        val w1 = ErbcArtifacts.write(dir.resolve("o1"), r1.program!!, corpus, r1.sourceFiles, r1.stats, r1.bootMetadata)
        val w2 = ErbcArtifacts.write(dir.resolve("o2"), r2.program!!, corpus, r2.sourceFiles, r2.stats, r2.bootMetadata)
        val m1 = assertNotNull(w1.metaPath)
        val m2 = assertNotNull(w2.metaPath)
        assertContentEquals(Files.readAllBytes(m1), Files.readAllBytes(m2), "侧车两次编译必须逐字节相同")
        assertContentEquals(
            Files.readAllBytes(w1.artifactPath),
            Files.readAllBytes(w2.artifactPath),
            "同一语料两次编译的 program.erbc 必须逐字节相同（既有判据不变）",
        )
        assertEquals(w1.meta!!.sha256, w2.meta!!.sha256)
        // 文本形态：LF 结尾、无 BOM、无裸 CR
        val bytes = Files.readAllBytes(m1)
        assertTrue(bytes.isNotEmpty())
        assertEquals('\n'.code.toByte(), bytes.last(), "侧车必须以 LF 结尾")
        assertTrue(bytes.size < 3 || !(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()))
        assertTrue(Files.readString(m1).none { it == '\r' }, "侧车不得含裸 CR")
    }

    @Test
    fun `落侧车不改 program_erbc 字节且清单按新口径登记侧车`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = compiled(corpus)
        val program = result.program!!

        val plain = ErbcArtifacts.write(dir.resolve("plain"), program, corpus, result.sourceFiles, result.stats)
        val withMeta = ErbcArtifacts.write(
            dir.resolve("meta"), program, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        assertNull(plain.metaPath, "不传元数据时不写侧车（旧调用点行为）")
        assertContentEquals(
            Files.readAllBytes(plain.artifactPath),
            Files.readAllBytes(withMeta.artifactPath),
            "program.erbc 字节不得因侧车而变",
        )
        // CMP-74：侧车**必须**进清单（清单 = 随包文件集的权威来源）；program.erbc 仍是同一份。
        assertEquals("erbc.v1/program.meta", withMeta.meta?.relPath, "侧车条目路径")
        assertTrue(Files.isRegularFile(withMeta.metaPath!!))
        val plainManifest = Files.readString(plain.manifestPath)
        val withMetaManifest = Files.readString(withMeta.manifestPath)
        assertTrue(
            plainManifest.contains("artifacts 2\n"),
            "不写元数据时产物表 = program.erbc + program.stmt.idx: $plainManifest",
        )
        assertTrue(
            withMetaManifest.contains("artifacts 3\n"),
            "写侧车后 artifacts 必须变成 3（program.erbc + program.meta + program.stmt.idx）: $withMetaManifest",
        )
        assertTrue(
            withMetaManifest.contains("artifact erbc.v1/program.meta "),
            "侧车行必须带字节数与 SHA-256: $withMetaManifest",
        )
        // 构建面清单仍必须自证 program.erbc 的摘要（旧判据不丢）
        assertTrue(
            withMetaManifest.contains("artifact erbc.v1/program.erbc "),
            "program.erbc 条目不得被侧车顶掉: $withMetaManifest",
        )
    }

    @Test
    fun `侧车文件可被 BootMetadata 读回且与模型文本一致`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = compiled(corpus)
        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        val read = BootMetadata.read(report.metaPath!!)
        val model = result.bootMetadata
        assertEquals(model.labels, read.labels, "标签表往返")
        assertEquals(model.dataSections, read.dataSections, "数据节往返（Expr 结构相等）")
        assertEquals(model.globalDims, read.globalDims, "全局 #DIM 原文往返")
        assertEquals(model.privateDims.size, read.privateDims.size, "私有 #DIM 条数往返")
        assertEquals(model.functions, read.functions, "函数元数据往返")
        assertEquals(model.dimParseConfig, read.dimParseConfig, "宏/常量表往返")
        assertEquals(BootMetaWriter.engineTag(), read.engineTag, "engine 头 = 写出端标识")
    }

    @Test
    fun `数据节键必须是池里的消费者语句下标`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = compiled(corpus)
        val program = result.program!!
        assertTrue(result.bootMetadata.dataSections.isNotEmpty(), "合成语料有一个 PRINTDATA，必须收进侧车")
        for (idx in result.bootMetadata.dataSections.keys) {
            assertTrue(idx in 0 until program.statementCount, "下标越界: $idx")
            val stmt = program.statement(idx)
            assertTrue(
                stmt is PrintDataStmt || (stmt is DataStmt && stmt.code == FunctionCode.STRDATA),
                "下标 $idx 指向的不是数据节消费者: ${stmt::class.simpleName}",
            )
        }
    }

    // ==================================================================
    // 真语料（ERATW_CORPUS）：只对元数据，不跑 4.8M 指令
    // ==================================================================

    @Test
    fun `真语料的侧车与 CompileResult 一致`() {
        val raw = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        assumeTrue(raw != null, "未设置 ERATW_CORPUS，跳过真语料侧车用例")
        val corpus = Path.of(raw!!)
        assumeTrue(Files.isDirectory(corpus), "语料目录不存在: $corpus")
        // 本模块的 test worker 默认堆是 512m；全量/大子集编译需要更多。给不足就跳过（判据仍在合成用例里）。
        val heap = Runtime.getRuntime().maxMemory()
        assumeTrue(
            heap >= 1024L * 1024 * 1024,
            "test worker 堆 ${heap / 1048576}MiB < 1024MiB，跳过大子集编译（需要更大堆时由组长调 :tools:erbcc:test 的 maxHeapSize）",
        )
        val limit = System.getenv("ERATW_BOOTMETA_LIMIT")?.toIntOrNull()?.takeIf { it > 0 } ?: 250
        val result = CorpusCompile.compile(corpus, limit)
        val meta = result.bootMetadata
        val program = assertNotNull(result.program, "子集编译应产出 Program")

        println(
            "[bootmeta] 语料子集 $limit 文件：函数 ${meta.functions.size}  标签函数 ${meta.labels.size}" +
                "（${meta.labels.values.sumOf { it.size }} 条）  数据节 ${meta.dataSections.size}  " +
                "全局 #DIM ${meta.globalDims.size}  私有 #DIM ${meta.privateDims.size}  " +
                "宏 ${meta.dimParseConfig.macros.size}  常量 ${meta.dimParseConfig.intConstants.size}",
        )
        assertTrue(meta.functions.isNotEmpty(), "函数元数据不能为空")
        assertTrue(meta.labels.isNotEmpty(), "真实语料必有标签")
        assertTrue(meta.globalDims.isNotEmpty(), "前 $limit 个文件里必有全局 #DIM")

        // 侧车往返：文本 → 模型 → 文本 闭环，且计数与 CompileResult 的模型一致
        val text = meta.encodeText()
        val back = BootMetadata.parse(text)
        assertEquals(text, back.encodeText(), "真语料侧车文本必须闭环")
        assertEquals(meta.labels.size, back.labels.size, "标签函数数")
        assertEquals(meta.labels.values.sumOf { it.size }, back.labels.values.sumOf { it.size }, "标签条数")
        assertEquals(meta.dataSections.size, back.dataSections.size, "数据节数")
        assertEquals(meta.globalDims.size, back.globalDims.size, "全局 #DIM 条数")
        assertEquals(meta.privateDims.size, back.privateDims.size, "私有 #DIM 条数")
        assertEquals(meta.functions.size, back.functions.size, "函数元数据条数")

        // 数据节的键必须落在池里的消费者语句上；登记数不得超过池里的消费者数
        val consumers = (0 until program.statementCount).count { i ->
            val s = program.statement(i)
            s is PrintDataStmt || (s is DataStmt && s.code == FunctionCode.STRDATA)
        }
        assertTrue(
            meta.dataSections.size <= consumers,
            "数据节登记 ${meta.dataSections.size} 条，池里消费者只有 $consumers 条",
        )
        for (idx in meta.dataSections.keys) {
            assertTrue(idx in 0 until program.statementCount, "数据节下标越界: $idx")
        }
        println("[bootmeta] 池里 PRINTDATA/STRDATA 语句 $consumers 条，侧车登记 ${meta.dataSections.size} 条")
    }
}
