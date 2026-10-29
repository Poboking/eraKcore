package com.eratw.port.erbcc.audit

import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-79：「审计落盘后再装载对账」的必红/必绿夹具。
 *
 * 背景：`SymbolLinkAudit.audit` 的命名空间来自**编译机内存产物**
 * （`result.bootMetadata` / `result.program`），全程不碰 `.erbc` + `program.meta`，
 * 于是侧车编解码 / `ErbcReader` 解码 / `ProgramLoader.internStatementArgs` 的丢失或误折
 * 零判据。本类钉四件事：
 *
 * 1. 合法产物：内存产物 vs 落盘再装载**逐字段相等**（三读数 + 名字集合哈希 + 侧车模型）；
 * 2. 侧车**少写一条私有 #DIM**（并同步改计数）：对账必须红（`meta.private-dims` 与
 *    `runtime.private-vars` 同时不等）——这条通道在 CMP-79 之前**不存在**（没有 API 可调，
 *    本文件在旧代码上编译不过，即「必红」的上界）；
 * 3. 篡改侧车一条 `file` 记录：`meta.files` 必须红；
 * 4. 篡改侧车一条 `label` 的 pc：`meta.labels` 必须红。
 *
 * 真语料档（`ERATW_CORPUS` + 堆 >= 1 GiB）只跑前 N 个文件（`ERATW_ROUNDTRIP_LIMIT`，
 * 缺省 20）：全量产物档由组长用 `erbcc audit --roundtrip` 串行过闸。
 */
class SymbolLinkAuditRoundTripTest {

    @TempDir
    lateinit var dir: Path

    /**
     * 合成语料：全局 `#DIM CONST` + 私有 `#DIM` + `PRINTDATA` 数据节 + `$` 标签。
     * 四类信息都在侧车里（容器不含），任一类没进/读不回都会让对账红。
     */
    private fun writeGame(): Path {
        val game = dir.resolve("game")
        val erb = game.resolve("ERB")
        Files.createDirectories(erb)
        Files.createDirectories(game.resolve("CSV"))
        Files.writeString(erb.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n")
        Files.writeString(
            erb.resolve("01_main.ERB"),
            """
            @SYSTEM_TITLE
            #DIM LOC_OK
            #DIM CONST LOC_C = 3
            LOC_OK = G_CONST + LOC_C
            PRINTFORML INIT={LOC_OK}
            ${'$'}LOOP_TAIL
            PRINTDATA
            DATA AAA
            DATA BBB
            ENDDATA
            PRINTL TAILED
            """.trimIndent(),
        )
        return game
    }

    /** 独立落盘（不经对账），便于测试先落盘、再手改侧车。 */
    private fun writeArtifacts(game: Path, out: Path): ErbcWriteFixture {
        val corpus = game.resolve("ERB")
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过: ${result.failures}")
        val program = assertNotNull(result.program, "合成语料必须有 Program")
        val write = ErbcArtifacts.write(out, program, corpus, result.sourceFiles, result.stats, result.bootMetadata)
        val metaPath = assertNotNull(write.metaPath, "落盘必须同时写侧车")
        return ErbcWriteFixture(write.artifactPath, metaPath)
    }

    private class ErbcWriteFixture(val artifactPath: Path, val metaPath: Path)

    private fun readMeta(path: Path): MutableList<String> = Files.readString(path).lines().toMutableList()

    private fun writeMeta(path: Path, lines: List<String>) {
        Files.writeString(path, lines.joinToString("\n") + "\n")
    }

    // ==================================================================
    // 1. 合法产物：必须全绿
    // ==================================================================

    @Test
    fun `合法产物落盘再装载 逐字段对账全绿`() {
        val game = writeGame()
        val out = dir.resolve("out")
        val report = SymbolLinkAudit.roundTrip(game.resolve("ERB"), game, artifactDir = out)
        assertTrue(
            report.ok,
            "合法产物对账必须全绿；不等项 = ${report.mismatches.joinToString(" | ")}",
        )
        // 三读数都真的读到东西（不是「两边都是 0」的空绿）
        val globals = report.checks.single { it.name == "runtime.globals" }
        val privateVars = report.checks.single { it.name == "runtime.private-vars" }
        val systemVars = report.checks.single { it.name == "runtime.system-vars" }
        assertTrue(globals.inMemory.toInt() > 0, "全局用户变量数应 > 0: ${globals.inMemory}")
        // 私有 #DIM 两条：LOC_OK（普通）与 LOC_C（`#DIM CONST`，也是私有声明）
        assertEquals("2", privateVars.inMemory, "合成语料恰好两条私有 #DIM（LOC_OK / LOC_C）")
        assertTrue(systemVars.inMemory.toInt() > 0, "系统变量数应 > 0: ${systemVars.inMemory}")
        assertTrue(report.nameSetSize > 0, "使用点名字集合不应为空")
        assertEquals(64, report.nameSetHash.length, "名字集合哈希应是 sha256 十六进制")
        // 指定 artifactDir 时产物必须保留（复现面）
        assertTrue(Files.isRegularFile(report.metaPath), "对账产物必须留在 --artifact-dir")
        assertTrue(Files.isRegularFile(report.artifactPath), "program.erbc 必须留在 --artifact-dir")
        // 明细 TSV 可落盘
        val tsv = dir.resolve("roundtrip.tsv")
        report.writeTsv(tsv)
        assertTrue(Files.readString(tsv).startsWith("status\tcheck\tin_memory\treloaded\tdetail\n"))
    }

    // ==================================================================
    // 2. 必红：侧车少写一条私有 #DIM
    // ==================================================================

    @Test
    fun `侧车少写一条私有 dim 对账必红`() {
        val game = writeGame()
        val fixture = writeArtifacts(game, dir.resolve("out"))
        val lines = readMeta(fixture.metaPath)
        val dimIdx = lines.indexOfFirst { it.startsWith("private-dim ") }
        assertTrue(dimIdx >= 0, "夹具自检：侧车必须含 private-dim 记录: $lines")
        val countIdx = lines.indexOfFirst { it.startsWith("private-dims ") }
        val declared = lines[countIdx].removePrefix("private-dims ").trim().toInt()
        assertTrue(declared > 0, "夹具自检：private-dims 计数应 > 0")
        lines.removeAt(dimIdx)
        lines[countIdx] = "private-dims ${declared - 1}"
        writeMeta(fixture.metaPath, lines)

        val report = SymbolLinkAudit.verifyArtifacts(
            game.resolve("ERB"), game, fixture.artifactPath, fixture.metaPath,
        )
        assertFalse(report.ok, "少一条私有 dim 必须红，实际全绿: ${report.checks}")
        val names = report.mismatches.map { it.name }
        assertTrue("meta.private-dims" in names, "侧车模型那条私有 #DIM 必须不等: $names")
        assertTrue(
            "runtime.private-vars" in names,
            "运行期私有变量注册数必须不等（真装载链少了这条声明）: $names",
        )
        val detail = report.mismatches.single { it.name == "meta.private-dims" }.detail
        assertTrue(detail.contains("条数不同") || detail.contains("首差"), "必须指出首差: $detail")
    }

    // ==================================================================
    // 3. 必红：篡改一条 file 记录
    // ==================================================================

    @Test
    fun `篡改侧车一条 file 记录对账必红`() {
        val game = writeGame()
        val fixture = writeArtifacts(game, dir.resolve("out"))
        val lines = readMeta(fixture.metaPath)
        val fileIdx = lines.indexOfFirst { it.startsWith("file ") }
        assertTrue(fileIdx >= 0, "夹具自检：侧车必须含 file 记录")
        val original = lines[fileIdx]
        lines[fileIdx] = original.substringBeforeLast('"') + "篡改.ERB\""
        assertTrue(lines[fileIdx] != original, "夹具自检：必须改到 file 路径: $original")
        writeMeta(fixture.metaPath, lines)

        val report = SymbolLinkAudit.verifyArtifacts(
            game.resolve("ERB"), game, fixture.artifactPath, fixture.metaPath,
        )
        assertFalse(report.ok, "篡改 file 记录必须红: ${report.checks}")
        assertTrue("meta.files" in report.mismatches.map { it.name }, "meta.files 必须不等: ${report.mismatches}")
    }

    // ==================================================================
    // 4. 必红：篡改一条 label 的 pc
    // ==================================================================

    @Test
    fun `篡改侧车一条 label 的 pc 对账必红`() {
        val game = writeGame()
        val fixture = writeArtifacts(game, dir.resolve("out"))
        val lines = readMeta(fixture.metaPath)
        val labelIdx = lines.indexOfFirst { it.startsWith("label ") }
        assertTrue(labelIdx >= 0, "夹具自检：合成语料有 \$标签")
        val original = lines[labelIdx]
        val parts = original.substringAfter("label \"").split("\" ") // [名字, pc]
        val pc = parts[1].trim().toInt()
        lines[labelIdx] = "label \"${parts[0]}\" ${pc + 1}"
        assertTrue(lines[labelIdx] != original, "夹具自检：必须改到 label pc: $original")
        writeMeta(fixture.metaPath, lines)

        val report = SymbolLinkAudit.verifyArtifacts(
            game.resolve("ERB"), game, fixture.artifactPath, fixture.metaPath,
        )
        assertFalse(report.ok, "篡改 label pc 必须红: ${report.checks}")
        assertTrue("meta.labels" in report.mismatches.map { it.name }, "meta.labels 必须不等: ${report.mismatches}")
    }

    // ==================================================================
    // 5. CLI 接线：`erbcc audit --roundtrip`
    // ==================================================================

    @Test
    fun `audit --roundtrip 命令入口退出码 0 并落对账明细`() {
        val game = writeGame()
        val out = dir.resolve("cli-out")
        val tsv = dir.resolve("cli-roundtrip.tsv")
        val code = com.eratw.port.erbcc.cmdAudit(
            listOf(
                "--corpus", game.resolve("ERB").toString(),
                "--game-root", game.toString(),
                "--roundtrip",
                "--artifact-dir", out.toString(),
                "--out", tsv.toString(),
            ),
        )
        assertEquals(0, code, "干净语料的对账命令必须退出 0")
        val rows = Files.readString(tsv).lines().filter { it.isNotEmpty() }
        assertTrue(rows.first() == "status\tcheck\tin_memory\treloaded\tdetail", "表头: ${rows.first()}")
        assertTrue(rows.drop(1).all { it.startsWith("OK\t") }, "干净语料全部读数应为 OK: $rows")
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/program.meta")), "--artifact-dir 下必须留侧车")
    }

    // ==================================================================
    // 6. 真语料子集（环境变量门控）
    // ==================================================================

    @Test
    fun `真语料子集落盘再装载对账`() {
        val raw = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        assumeTrue(raw != null, "未设置 ERATW_CORPUS，跳过真语料对账")
        val corpus = Path.of(raw!!)
        assumeTrue(Files.isDirectory(corpus), "语料目录不存在: $corpus")
        // test worker 默认堆 512m：全量/大子集编译需要更多，给不足就跳过（判据在合成用例里）。
        val heap = Runtime.getRuntime().maxMemory()
        assumeTrue(
            heap >= 1024L * 1024 * 1024,
            "test worker 堆 ${heap / 1048576}MiB < 1024MiB，跳过大子集对账（需要更大堆时由组长调 :tools:erbcc:test 的 maxHeapSize）",
        )
        val limit = System.getenv("ERATW_ROUNDTRIP_LIMIT")?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val game = corpus.parent ?: corpus
        val report = SymbolLinkAudit.roundTrip(corpus, game, fileLimit = limit)
        println(
            "[roundtrip] 真语料前 $limit 文件：名字集合 ${report.nameSetSize} 个" +
                "（sha256 ${report.nameSetHash.take(16)}…）  对拍 ${report.checks.size} 项  " +
                "${report.elapsedMs} ms",
        )
        for (c in report.checks) {
            println("  ${if (c.equal) "OK  " else "DIFF"} ${c.name}\t内存=${c.inMemory}\t装载=${c.reloaded}")
        }
        assertTrue(report.ok, "真语料子集对账必须绿: ${report.mismatches.joinToString(" | ")}")
    }
}
