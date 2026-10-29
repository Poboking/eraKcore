package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-65 必红夹具：留痕/notes「产了没人发」+ `--limit` 产物在 manifest 里不可区分。
 *
 * 缺陷面（BOARD CMP-65，证据 `CorpusCompile.kt:214/355/538/563`、`ErbcArtifacts.kt:148-180`）：
 * - `CorpusScan.warnings`（读失败 / 二进制跳过 / 未闭合预处理区间 / 带余参指令）在 compile
 *   路径**无人消费**：`CorpusScan.scan` 收进 [CorpusScan.warnings] 后直接进
 *   `CompileResolver`，全仓没有一处读 `.warnings`；
 * - `CompileResult.notes`（合成定义 :355、未定义 CALL 空体桩 :538、源文件读失败 :563）
 *   全 main 源 **0 引用** —— 生产路径既不打印也不落盘；
 * - `--limit` 产物与全量产物在 manifest 里**不可区分**（`source-files` 仍按全量登记），
 *   `Main.kt:65` 的「manifest 会注明」是对用户的承诺，当前没有兑现。
 *
 * 判据：notes 必须进同一可见面（`CompileResult.notes`）并在 compile 收尾打印计数+样例；
 * manifest 必须能区分受限产物（`build-mode limited` + `file-limit N`），全量档写
 * `build-mode full`。
 */
class CorpusNotesManifestTest {

    /** 两个文件：一个带未闭合 `[SKIPSTART]`（预扫留痕 level 1），一个正常。 */
    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("01_open.ERB"),
            "@L15_OPEN\n[SKIPSTART]\nPRINTL HIDDEN\n".toByteArray(Charsets.UTF_8),
        )
        Files.write(root.resolve("02_ok.ERB"), "@L15_OK\nPRINTL OK\n".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `预扫描告警必须并入 notes 并在收尾可见`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；failures=${r.failures.take(3)}")
        // 预扫侧确实产了告警（旧实现：躺在 CorpusScan.warnings 里没人读）
        assertTrue(
            r.notes.any { it.contains("预处理区间未闭合") },
            "CorpusScan.warnings 必须并入 CompileResult.notes，实际 notes=${r.notes}",
        )
        assertTrue(
            r.notes.any { it.contains("01_open.ERB") },
            "告警必须带定位（文件:行号），实际 notes=${r.notes}",
        )
    }

    @Test
    fun `limit 产物在 manifest 里可区分且 full 档有显式标记`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)

        // 受限档：2 文件里只切 1 个
        val limited = CorpusCompile.compile(corpus, fileLimit = 1)
        assertTrue(limited.ok, "受限档编译也必须成功；failures=${limited.failures.take(3)}")
        assertEquals(1, limited.stats.fileLimit, "受限档必须把生效的 fileLimit 记进读数")
        val program = assertNotNull(limited.program)
        val limitedReport = ErbcArtifacts.write(
            dir.resolve("out-limited"), program, corpus, limited.sourceFiles, limited.stats, limited.bootMetadata,
        )
        val limitedManifest = Files.readString(limitedReport.manifestPath)
        assertTrue(
            limitedManifest.contains("build-mode limited\n"),
            "受限产物必须在 manifest 里可区分（build-mode limited）: $limitedManifest",
        )
        assertTrue(limitedManifest.contains("file-limit 1\n"), "受限产物必须登记生效的 file-limit: $limitedManifest")
        // 清单仍按全量语料登记（语义 = 语料清单，不是「本次切了哪些」），但至少有 build-mode 兜住院
        assertTrue(limitedManifest.contains("source-files 2\n"), "语料清单口径不变: $limitedManifest")

        // 全量档：同一语料不传 fileLimit
        val full = CorpusCompile.compile(corpus)
        assertTrue(full.ok, "全量档编译必须成功；failures=${full.failures.take(3)}")
        assertEquals(null, full.stats.fileLimit, "全量档 fileLimit 必须为空")
        val fullReport = ErbcArtifacts.write(
            dir.resolve("out-full"), assertNotNull(full.program), corpus, full.sourceFiles, full.stats, full.bootMetadata,
        )
        val fullManifest = Files.readString(fullReport.manifestPath)
        assertTrue(fullManifest.contains("build-mode full\n"), "全量档必须显式写 build-mode full: $fullManifest")
        assertTrue(!fullManifest.contains("file-limit"), "全量档不得出现 file-limit: $fullManifest")
    }
}
