package com.eratw.port.erbcc.compile

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * CMP-64 必红夹具：切片/编译侧对前 8KB 含 NUL 的二进制 ERB **静默 continue**。
 *
 * 缺陷面（BOARD CMP-64，证据 `CorpusCompile.kt:242`）：
 * - 不留 notes / warning，也不进 `parseFailures`（那是「失败」，二进制不是失败）；
 * - 计数上「看不出少了什么」：`stats.corpusFiles` 取 `allFiles.size`（照旧含它），
 *   `stats.slices` 只少一个文件的切片，和「这个文件本来就没有 @ 函数」不可区分；
 * - 后果：产物整文件缺失、闸全绿。
 *
 * 对照口径：预扫侧 `CorpusScan.readForScan`（`CorpusScan.kt:252-255`）**留痕后跳过**
 * （`LoadWarning`「按二进制跳过（前 8KB 出现 NUL）」），原版对读不开的文件是
 * `CodeEE FailedOpenFile`（spec: `ErhLoader.cs:81-84`）—— 两侧都该可见，只有编译侧静默。
 *
 * 修法口径：清单仍登记（`sourceFiles` 走 `allFiles`，与 `CompileErbcTest` 的
 * 「登记但不解析」同口径），另加 `stats.binarySkipped` 计数 + notes 留痕，并由
 * [CorpusCompile.compile] 收尾统一把 notes 计数与样例打到 stderr（CMP-65 的消费面）。
 */
class CorpusNulSkipTest {

    private val nulPattern = "@L15_NUL\n".toByteArray(Charsets.UTF_8) +
        byteArrayOf(0) + "\nRETURN\n".toByteArray(Charsets.UTF_8)

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("01_ok.ERB"), "@L15_OK\nPRINTL OK\n".toByteArray(Charsets.UTF_8))
        // 前 8KB 内出现 NUL：02 号文件整体按二进制跳过
        Files.write(root.resolve("02_nul.ERB"), nulPattern)
    }

    private fun compileCapturingStderr(corpus: Path): Pair<CompileResult, String> {
        val buf = ByteArrayOutputStream()
        val stream = PrintStream(buf, true, "UTF-8")
        val saved = System.err
        System.setErr(stream)
        try {
            return CorpusCompile.compile(corpus) to buf.toString("UTF-8")
        } finally {
            System.setErr(saved)
            stream.flush()
            stream.close()
        }
    }

    @Test
    fun `NUL 二进制 erb 必须计数留痕且仍进清单`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val (r, stderr) = compileCapturingStderr(corpus)

        assertTrue(r.ok, "NUL 文件不得让整批编译失败；failures=${r.failures.take(3)}")
        // ① 计数：源清单与 corpusFiles 都含它（清单口径 = 登记但不解析）
        assertEquals(2, r.stats.corpusFiles, "corpusFiles 取 allFiles.size，NUL 文件必须在分母里")
        assertTrue(r.sourceFiles.any { it.relPath == "02_nul.ERB" }, "NUL 文件仍必须进 manifest 源清单")
        // ② 独立的跳过读数（旧实现静默 continue，这个字段不存在）
        assertEquals(1, r.stats.binarySkipped, "必须单独计数被当二进制跳过的源文件")
        // ③ notes 留痕（旧实现 0 条）
        assertTrue(
            r.notes.any { it.contains("02_nul.ERB") && it.contains("二进制") },
            "notes 必须留痕（文件名 + 二进制跳过）: ${r.notes}",
        )
        // ④ compile 收尾把 notes 计数与样例打到 stderr（旧实现不打印）
        assertTrue(stderr.contains("02_nul.ERB"), "compile 收尾必须打印 notes 样例，实际 stderr=$stderr")
        assertTrue(
            Regex("notes\\s*\\d+").containsMatchIn(stderr),
            "compile 收尾必须给 notes 计数（`notes <n> 条`），实际 stderr=$stderr",
        )
    }
}
