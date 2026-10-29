package com.eratw.port.erbcc.compile

import com.eratw.port.erbcc.cmdCompile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 写侧错误恢复（[AtomicWrite] + [ErbcArtifacts] 的落盘顺序）。
 *
 * 三条断言口径：
 * 1. **半截不可见**：写失败/被杀只可能停在 `<name>.tmp`，目标路径保持旧内容；
 * 2. **无残留**：成功路径不留 `.tmp`；
 * 3. **单文件失败不污染产物**：编译是全语料单一 `Program`，任何一个切片抛错都只进
 *    `failures`（`CorpusCompile.kt:440-455`），`cmdCompile` 在落盘前就返回 1
 *    （`Main.kt` 的 `!result.ok` 分支）—— 既有产物逐字节不变。
 */
class AtomicWriteTest {

    @Test
    fun `覆盖已存在文件且不留临时文件`(@TempDir dir: Path) {
        val target = dir.resolve("program.erbc")
        Files.write(target, "old".toByteArray(Charsets.UTF_8))

        AtomicWrite.write(target, "new".toByteArray(Charsets.UTF_8))

        assertContentEquals("new".toByteArray(Charsets.UTF_8), Files.readAllBytes(target))
        assertFalse(Files.exists(dir.resolve("program.erbc${AtomicWrite.TEMP_SUFFIX}")), "不得留 .tmp")
    }

    @Test
    fun `写失败时目标保持旧内容且不留临时文件`(@TempDir dir: Path) {
        val target = dir.resolve("program.erbc")
        Files.write(target, "old".toByteArray(Charsets.UTF_8))
        // 让临时文件路径不可写：同名的目录（Files.write 到目录必抛）
        Files.createDirectory(dir.resolve("program.erbc${AtomicWrite.TEMP_SUFFIX}"))

        assertFailsWith<Exception> { AtomicWrite.write(target, "new".toByteArray(Charsets.UTF_8)) }

        assertContentEquals("old".toByteArray(Charsets.UTF_8), Files.readAllBytes(target), "旧产物必须原样保留")
    }

    @Test
    fun `产物三件套原子落盘且无 tmp 残留`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(
            corpus.resolve("01_main.ERB"),
            "@SYSTEM_TITLE\n#DIM NN\nNN = 1\nPRINTL HI\n".toByteArray(Charsets.UTF_8),
        )
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过: ${result.failures.take(3)}")

        val out = dir.resolve("out")
        val report = ErbcArtifacts.write(
            out, result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata,
        )
        val artifactDir = report.artifactDir
        val leftovers = Files.list(artifactDir).use { s ->
            s.filter { it.name.endsWith(AtomicWrite.TEMP_SUFFIX) }.count()
        }
        assertEquals(0L, leftovers, "产物目录不得留 .tmp")
        for (f in listOf("program.erbc", "program.meta", "manifest.txt", "program.stmt.idx")) {
            assertTrue(Files.isRegularFile(artifactDir.resolve(f)), "缺 $f")
        }
        // 清单是提交记录：它的 artifact 摘要必须等于磁盘容器的摘要
        val manifest = String(Files.readAllBytes(report.manifestPath), Charsets.UTF_8)
        val diskSha = ErbcArtifacts.sha256Hex(Files.readAllBytes(report.artifactPath))
        assertTrue(manifest.contains(" $diskSha\n"), "清单摘要必须等于磁盘容器摘要")
    }

    @Test
    fun `编译失败不触碰既有产物`(@TempDir dir: Path) {
        // 1) 先产出一份好产物
        val good = dir.resolve("good")
        Files.createDirectories(good)
        Files.write(good.resolve("01_main.ERB"), "@SYSTEM_TITLE\nPRINTL HI\n".toByteArray(Charsets.UTF_8))
        val out = dir.resolve("out")
        assertEquals(0, cmdCompile(listOf("--corpus", good.toString(), "--out", out.toString())))
        val artifact = out.resolve("erbc.v1/program.erbc")
        val manifest = out.resolve("erbc.v1/manifest.txt")
        val artifactBefore = Files.readAllBytes(artifact)
        val manifestBefore = Files.readAllBytes(manifest)

        // 2) 换一份必失败语料（未闭合 IF，与 CompileErbcTest 的失败路径同形）落同一个 --out
        val broken = dir.resolve("broken")
        Files.createDirectories(broken)
        Files.write(
            broken.resolve("01_main.ERB"),
            "@MINI_BROKEN\nIF 1 > 0\n\tPRINTL NEVER_END\n".toByteArray(Charsets.UTF_8),
        )
        assertEquals(1, cmdCompile(listOf("--corpus", broken.toString(), "--out", out.toString())), "必须返回 1")

        // 3) 既有产物逐字节不变，且没有 tmp 残留
        assertContentEquals(artifactBefore, Files.readAllBytes(artifact), "失败编译不得改写容器")
        assertContentEquals(manifestBefore, Files.readAllBytes(manifest), "失败编译不得改写清单")
        val leftovers = Files.list(out.resolve("erbc.v1")).use { s ->
            s.filter { it.name.endsWith(AtomicWrite.TEMP_SUFFIX) }.count()
        }
        assertEquals(0L, leftovers, "失败路径不得留 .tmp")
    }
}
