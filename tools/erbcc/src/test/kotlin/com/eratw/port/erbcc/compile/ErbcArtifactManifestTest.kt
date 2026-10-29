package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * manifest / 侧车口径与 [ErbcArtifacts] 的一致性（M6B-FRONT 审计的 erbcc 侧）。
 *
 * 本用例补 [WriteReport] 的**清单字段自洽**：产物条目、源文件条目、侧车条目的
 * 相对路径 / 字节数 / SHA-256 都与磁盘上的真实字节一致。
 *
 * CMP-74 修正后的口径：`program.meta` / `program.stmt.idx` **必须**进 manifest 的
 * `artifacts` 表（清单 = 随包文件集的权威来源）；旧断言「侧车不进清单」正是缺陷面。
 *
 * 口径依据：侧车与容器「同目录、同基名、不同后缀」，配对靠侧车头部的 `program-sha256`
 * （`ErbcArtifacts` KDoc 的「确定性」与「清单 = 随包文件集的权威来源」两节；
 * 写出端见 [BootMetaWriter]）。
 */
class ErbcArtifactManifestTest {

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8))
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @SYSTEM_TITLE
            PRINTL HELLO
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    private fun compile(corpus: Path): CompileResult {
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        return r
    }

    @Test
    fun `清单与侧车条目逐字段对齐磁盘字节`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = compile(corpus)
        val program: Program = result.program!!
        val report = ErbcArtifacts.write(
            dir.resolve("out"), program, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )

        // ---- 产物条目 ----
        val artifactBytes = Files.readAllBytes(report.artifactPath)
        assertEquals(artifactBytes.size.toLong(), report.artifact.bytes, "artifact 字节数")
        assertEquals(ErbcArtifacts.sha256Hex(artifactBytes), report.artifact.sha256, "artifact SHA-256")
        assertEquals("erbc.v${com.eratw.port.core.code.ErbcFormat.VERSION_1}/program.erbc", report.artifact.relPath)

        // ---- 侧车条目：与产物同目录同基名，字段对齐真实文件 ----
        val metaPath = assertNotNull(report.metaPath, "正式调用点必须写侧车")
        val metaEntry = assertNotNull(report.meta, "侧车必须带内容寻址条目")
        assertEquals(report.artifactPath.parent, metaPath.parent, "侧车与容器同目录")
        assertEquals("program.meta", metaPath.fileName.toString())
        assertEquals("erbc.v${com.eratw.port.core.code.ErbcFormat.VERSION_1}/program.meta", metaEntry.relPath)
        val metaBytes = Files.readAllBytes(metaPath)
        assertEquals(metaBytes.size.toLong(), metaEntry.bytes, "侧车字节数")
        assertEquals(ErbcArtifacts.sha256Hex(metaBytes), metaEntry.sha256, "侧车 SHA-256")
        // 配对校验：侧车头部记容器摘要（装载侧 BootMetadata.read 会读这一行；值带引号，见 encodeText）
        val metaText = String(metaBytes, Charsets.UTF_8)
        assertTrue(
            metaText.contains("program-sha256 \"${report.artifact.sha256}\"\n"),
            "侧车头部必须记 program.erbc 的 sha256：${metaText.lineSequence().take(3).toList()}",
        )

        // ---- manifest：LF-only、产物表覆盖侧车（CMP-74）、源文件行数自洽 ----
        val manifestBytes = Files.readAllBytes(report.manifestPath)
        val manifest = String(manifestBytes, Charsets.UTF_8)
        assertTrue(manifest.none { it == '\r' }, "manifest 必须 LF 换行")
        assertTrue(manifest.endsWith("\n"), "manifest 必须以 LF 结尾")
        assertTrue(manifest.contains("manifest-format 2\n"), "新清单必须带格式标记（CMP-74）")
        assertEquals(
            report.artifacts.size,
            manifest.lineSequence().count { it.startsWith("artifact ") },
            "artifact 行数 = 清单登记的随包文件数（program.meta / program.stmt.idx 必须在内）",
        )
        val artifactRels = manifest.lineSequence()
            .filter { it.startsWith("artifact ") }
            .map { it.split(' ')[1] }
            .toList()
        assertTrue(artifactRels.any { it.endsWith("/program.meta") }, "侧车必须进 manifest：$artifactRels")
        assertTrue(artifactRels.any { it.endsWith("/program.stmt.idx") }, "偏移侧车必须进 manifest：$artifactRels")
        assertEquals(
            report.sources.size,
            manifest.lineSequence().count { it.startsWith("source ") },
            "source 行数 = 源文件条目数",
        )
        assertTrue(manifest.contains("source-files ${report.sources.size}\n"), "source-files 计数")
        // 源条目按相对路径排序（确定性构建，不含时间戳/主机名）
        assertEquals(report.sources.sortedBy { it.relPath }, report.sources, "源条目顺序 = 相对路径序")
        // ERH 也必须进清单（它是装载期语义的一部分：宏与 #DIM CONST）
        assertTrue(report.sources.any { it.relPath.endsWith(".ERH", ignoreCase = true) }, "ERH 必须进 manifest")
        for (s in report.sources) {
            val bytes = Files.readAllBytes(corpus.resolve(s.relPath))
            assertEquals(bytes.size.toLong(), s.bytes, "源文件字节数: ${s.relPath}")
            assertEquals(ErbcArtifacts.sha256Hex(bytes), s.sha256, "源文件 SHA-256: ${s.relPath}")
        }

        // ---- 装载侧读回：侧车摘要与清单摘要同源 ----
        val loaded = BootMetadata.read(metaPath)
        assertEquals(report.artifact.sha256, loaded.programSha256, "装回来的 programSha256 必须与 list 条目一致")
    }
}
