package com.eratw.port.erbcc.compile

import com.eratw.port.core.loader.StmtOffsetSidecar
import com.eratw.port.erbcc.cmdCheck
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 产物新鲜度检查（[ArtifactFreshness]）的可执行判据。
 *
 * 核心用例是「**改了源码但没重编 → 必须被检出**」：此前 `manifest.txt` 里虽然记着每个源文件的
 * SHA-256，但没有任何读侧代码拿它跟磁盘对账，产物过期在整条链上不可见。
 *
 * 其余用例逐个钉死判据来源：容器字节数/摘要（清单 `artifact` 行）、段表（`MappedErbc.open`）、
 * `program.meta` 的 `program-sha256`、`program.stmt.idx` 的语句条数（容器段 3 的第二个 u32）。
 */
class ArtifactFreshnessTest {

    // ==================================================================
    // 装置
    // ==================================================================

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("00_const.ERH"),
            """
            #DEFINE G_FLAG 1
            #DIM CONST G_CONST = 7
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @SYSTEM_TITLE
            #DIM NN
            NN = 1
            PRINTL HELLO
            PRINTFORML C={G_CONST}
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        Files.write(
            root.resolve("02_util.ERB"),
            """
            @MINI_UTIL
            PRINTL UTIL
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    /** 编译 + 落盘，返回 (corpus, out, WriteReport)。 */
    private fun build(dir: Path): Triple<Path, Path, WriteReport> {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.take(5)}")
        val out = dir.resolve("out")
        val report = ErbcArtifacts.write(
            out, result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        return Triple(corpus, out, report)
    }

    private fun kinds(report: FreshnessReport): List<FreshnessIssueKind> = report.issues.map { it.kind }

    /**
     * 把落盘目录整棵复制到 [dst]（保留相对路径），返回 dst。
     *
     * 破坏容器的用例必须走副本：`ErbcArtifacts.write` 落侧车时会经 `StmtOffsetSidecar.write`
     * 映射一次同目录容器取配对凭证（CMP-01），而 **Windows 上活映射会锁住同名文件的写入**
     *（`MappedErbc` 类末 TODO(verify) 第 (3) 条已实测记过这条平台差异，真机/桌面都如此）。
     * 原地覆盖会抛 `FileSystemException`，那是平台行为不是被测逻辑 —— 换一条路径写就干净。
     */
    private fun copyTree(src: Path, dst: Path): Path {
        Files.walk(src).use { s ->
            s.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel.toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return dst
    }

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    fun `刚落盘的产物无新鲜度问题`(@TempDir dir: Path) {
        val (corpus, out, report) = build(dir)
        // 侧车齐、清单齐：三个文件都在
        assertTrue(Files.isRegularFile(report.artifactPath))
        assertTrue(Files.isRegularFile(report.manifestPath))
        assertNotNull(report.metaPath)
        assertTrue(Files.isRegularFile(out.resolve("erbc.v1/${StmtOffsetSidecar.FILE_NAME}")), "段 3 侧车必须落盘")

        val fresh = ArtifactFreshness.check(out, corpus)
        assertTrue(fresh.ok, "刚编译完的产物不该有问题: ${fresh.issues}")
        assertEquals(0, fresh.issues.size)
    }

    @Test
    fun `改了源码没重编被检出`(@TempDir dir: Path) {
        val (corpus, out, _) = build(dir)
        // 只改内容，不改文件名/数量：唯一能发现的手段就是清单里的 SHA-256
        Files.write(
            corpus.resolve("01_main.ERB"),
            """
            @SYSTEM_TITLE
            PRINTL HELLO_CHANGED
            PRINTFORML C={G_CONST}
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

        val stale = ArtifactFreshness.check(out, corpus)
        assertFalse(stale.ok, "改源不重编必须被检出")
        assertEquals(listOf(FreshnessIssueKind.SOURCE_CHANGED), kinds(stale), "只该报这一条: ${stale.issues}")
        assertTrue(
            stale.issues.single().detail.contains("01_main.ERB"),
            "问题必须点名文件: ${stale.issues.single().detail}",
        )

        // CLI 同口径：退出码 1
        assertEquals(1, cmdCheck(listOf("--corpus", corpus.toString(), "--out", out.toString())))
    }

    @Test
    fun `新增与删除源文件被检出`(@TempDir dir: Path) {
        val (corpus, out, _) = build(dir)
        Files.delete(corpus.resolve("02_util.ERB"))
        Files.write(corpus.resolve("03_new.ERB"), "@MINI_NEW\nPRINTL NEW\n".toByteArray(Charsets.UTF_8))

        val stale = ArtifactFreshness.check(out, corpus)
        val ks = kinds(stale)
        assertTrue(FreshnessIssueKind.SOURCE_MISSING in ks, "删除的源文件必须报: ${stale.issues}")
        assertTrue(FreshnessIssueKind.SOURCE_ADDED in ks, "新增的源文件必须报: ${stale.issues}")
        assertTrue(
            stale.issues.any { it.kind == FreshnessIssueKind.SOURCE_ADDED && it.detail.contains("03_new.ERB") },
            "新增条目要点名: ${stale.issues}",
        )
    }

    @Test
    fun `半截容器被检出`(@TempDir dir: Path) {
        val (corpus, out, report) = build(dir)
        val bytes = Files.readAllBytes(report.artifactPath)
        // 模拟「写到一半进程被杀」：长度只剩 60%。破坏在副本上做（活映射会锁住原文件，见 copyTree）。
        val damaged = copyTree(out, dir.resolve("out-trunc"))
        val damagedArtifact = damaged.resolve(out.relativize(report.artifactPath).toString())
        Files.write(damagedArtifact, bytes.copyOf(bytes.size * 3 / 5))

        val stale = ArtifactFreshness.check(damaged, corpus)
        assertFalse(stale.ok)
        val ks = kinds(stale)
        assertTrue(
            FreshnessIssueKind.ARTIFACT_SIZE in ks || FreshnessIssueKind.CONTAINER_INVALID in ks,
            "半截容器必须被字节数或段表挡下: ${stale.issues}",
        )
        // 装载侧同样装不回来（不是「静默可用」）
        assertTrue(
            runCatching { com.eratw.port.core.loader.ProgramLoader.loadFile(damagedArtifact) }.isFailure,
            "半截容器必须装载失败",
        )
    }

    @Test
    fun `魔数被破坏的容器被检出`(@TempDir dir: Path) {
        val (corpus, out, report) = build(dir)
        val bytes = Files.readAllBytes(report.artifactPath)
        bytes[0] = 'X'.code.toByte()
        val damaged = copyTree(out, dir.resolve("out-magic"))
        val damagedArtifact = damaged.resolve(out.relativize(report.artifactPath).toString())
        Files.write(damagedArtifact, bytes)

        val stale = ArtifactFreshness.check(damaged, corpus)
        val ks = kinds(stale)
        assertTrue(FreshnessIssueKind.ARTIFACT_SHA in ks, "内容变了摘要必须不符: ${stale.issues}")
        assertTrue(FreshnessIssueKind.CONTAINER_INVALID in ks, "魔数坏了段表检查必须失败: ${stale.issues}")
    }

    @Test
    fun `段3侧车与容器不是一对被检出`(@TempDir dir: Path) {
        val (corpus, out, report) = build(dir)
        val container = com.eratw.port.core.loader.MappedErbc.open(report.artifactPath)
        val stmtCount = try {
            assertTrue(container.isPresent(com.eratw.port.core.code.ErbcFormat.SECTION_STATEMENTS), "必须有语句段")
            container.u32At(container.offsetOf(com.eratw.port.core.code.ErbcFormat.SECTION_STATEMENTS) + 4)
        } finally {
            container.close()
        }
        assertTrue(stmtCount > 0, "合成语料必须有语句池")

        // 换成「条数对不上」的表（结构自洽、尺寸自洽，只是不属于这份容器）。
        // `recordBytes` 必须传**真实**记录区长度：CMP-01 起 `StmtOffsetSidecar.write` 会自己
        // 从同目录容器算配对凭证（CRC32），传 0 直接抛「侧车不能无配对凭证落盘」。
        // 这里从落盘的那份侧车头里取回原值（MAGIC 8 + VERSION 4 + count 4 ⇒ 偏移 16 的 i32），
        // 这样本用例只改「条数」这一个变量，配对凭证仍然是真的。
        val sidecarPath = report.artifactPath.resolveSibling(StmtOffsetSidecar.FILE_NAME)
        val existing = Files.readAllBytes(sidecarPath)
        val recordBytes = java.nio.ByteBuffer.wrap(existing)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .getInt(16)
        assertTrue(recordBytes > 0, "落盘的侧车必须带真实记录区长度，实际 $recordBytes")
        StmtOffsetSidecar.write(
            sidecarPath,
            IntArray(stmtCount + 1) { it },
            recordBytes,
        )

        val stale = ArtifactFreshness.check(out, corpus)
        assertTrue(FreshnessIssueKind.SIDECAR_STALE in kinds(stale), "条数不符必须报: ${stale.issues}")
    }

    @Test
    fun `元数据侧车摘要与容器不符被检出`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过")
        val out = dir.resolve("out")
        val report = ErbcArtifacts.write(
            out, result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        // 重写侧车：容器摘要换成别的（模拟「只换了容器没换侧车」）
        BootMetaWriter.write(report.metaPath!!, result.bootMetadata, "0".repeat(64))

        val stale = ArtifactFreshness.check(out, corpus)
        assertTrue(FreshnessIssueKind.META_STALE in kinds(stale), "侧车摘要不符必须报: ${stale.issues}")
    }

    @Test
    fun `缺清单时直接报 MANIFEST_MISSING`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val out = dir.resolve("out")
        Files.createDirectories(out.resolve(ArtifactFreshness.ARTIFACT_DIR_NAME))

        val report = ArtifactFreshness.check(out, corpus)
        assertEquals(listOf(FreshnessIssueKind.MANIFEST_MISSING), kinds(report))
        assertEquals(1, cmdCheck(listOf("--corpus", corpus.toString(), "--out", out.toString())))
    }
}
