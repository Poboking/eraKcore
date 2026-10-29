package com.eratw.port.erbcc.compile

import com.eratw.port.core.code.ErbcFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-74 必红夹具：`manifest.txt` 的产物表必须覆盖**随包文件集**并能逐条对账。
 *
 * 缺陷面（BOARD CMP-74，证据 `ErbcArtifacts.kt:200` 的 `artifacts 1` 字面量）：
 * 清单只登记 `program.erbc`，而同一目录里随包的 `program.meta`（装机必需）与
 * `program.stmt.idx`（装载提速侧车）**不在表里** ⇒ 清单与随包文件集字段集不相交，
 * `inspect_apk.py` 只能读 `package.report`，两边无法互相校验。
 *
 * 判据（本用例锁死的三条）：
 * 1. 新清单带 `manifest-format 2`（缺该行 = 旧格式 v1，见 §「新旧可区分」）；
 * 2. `artifacts N` 的 N 与 `artifact` 行数相等，且表 = 产物目录里除 `manifest.txt`
 *    自身之外的全部普通文件（多一个/少一个都红）；
 * 3. 每行「相对路径 字节数 SHA-256」与磁盘字节逐位一致。
 *
 * 分片态由打包期追加 `package-artifact` 表（`app/build.gradle.kts` 的
 * `stageGameAssets`），本用例只钉 erbcc 落盘后的建站口径。
 */
class ManifestArtifactTableTest {

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8))
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @MINI_MAIN
            PRINTL HELLO
            PRINTFORML P={G_CONST}
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    /** `artifact <rel> <bytes> <sha256>` 三元组。 */
    private data class Row(val rel: String, val bytes: Long, val sha: String)

    private fun parseArtifactRows(manifest: String): List<Row> =
        manifest.lineSequence()
            .filter { it.startsWith("artifact ") }
            .map { line ->
                val p = line.split(' ')
                assertEquals(4, p.size, "artifact 行结构应为「artifact 相对路径 字节数 SHA-256」: $line")
                Row(p[1], p[2].toLong(), p[3])
            }
            .toList()

    @Test
    fun `manifest 产物表覆盖随包文件集且逐条 sha 对账`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.take(5)}")
        assertNotNull(result.program)

        val out = dir.resolve("out")
        val report = ErbcArtifacts.write(
            out, result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        val manifest = Files.readString(report.manifestPath)

        // ---- 1. 产物表 = 目录里的真实文件集（除 manifest.txt 自身）----
        val rows = parseArtifactRows(manifest)
        val declaredCount = manifest.lineSequence()
            .firstOrNull { it.startsWith("artifacts ") }
            ?.removePrefix("artifacts ")?.trim()?.toInt()
        assertEquals(rows.size, declaredCount, "artifacts N 必须等于 artifact 行数（N=${declaredCount}）")
        assertEquals(
            rows.size, rows.map { it.rel }.toSet().size,
            "artifact 相对路径不得重复: ${rows.map { it.rel }}",
        )
        val outRoot = report.artifactDir.parent
        val onDisk = Files.list(report.artifactDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { report.artifactDir.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }
        val declaredRel = rows.map { it.rel.substringAfter("erbc.v1/") }.sorted()
        assertEquals(
            (onDisk - "manifest.txt").sorted(), declaredRel,
            "产物表必须恰好是产物目录里除 manifest.txt 之外的全部文件（多登记/漏登记都红）",
        )

        // ---- 2. 每条与磁盘逐位对账；三个已知随包文件必须都在表里 ----
        for (rel in listOf("erbc.v1/program.erbc", "erbc.v1/program.meta", "erbc.v1/program.stmt.idx")) {
            assertTrue(rows.any { it.rel == rel }, "产物表缺 $rel: ${rows.map { it.rel }}")
        }
        for (row in rows) {
            val file = outRoot.resolve(row.rel)
            assertTrue(Files.isRegularFile(file), "清单登记了 $file，磁盘上没有（清单与随包文件集不可对账）")
            assertEquals(Files.size(file), row.bytes, "字节数不符: ${row.rel}")
            assertEquals(ErbcArtifacts.sha256Hex(Files.readAllBytes(file)), row.sha, "SHA-256 不符: ${row.rel}")
        }

        // ---- 3. 新旧可区分（放在最后：旧格式的首要症状是产物表漏登记，见上面断言）----
        val declaredFormat = manifest.lineSequence()
            .firstOrNull { it.startsWith("manifest-format ") }
            ?.removePrefix("manifest-format ")
            ?.trim()
        assertEquals(
            "2", declaredFormat,
            "新清单必须带 manifest-format 2（缺失 = 旧格式 v1，清单与随包文件集无法对账）: " +
                manifest.lineSequence().take(4).toList(),
        )
        // 容器版本字段仍与容器实字节同源（不是清单格式版本）
        assertEquals(
            "${ErbcFormat.LATEST_VERSION}",
            manifest.lineSequence().firstOrNull { it.startsWith("container-version ") }
                ?.removePrefix("container-version ")?.trim(),
            "container-version 不得被 manifest-format 挪用",
        )
    }
}
