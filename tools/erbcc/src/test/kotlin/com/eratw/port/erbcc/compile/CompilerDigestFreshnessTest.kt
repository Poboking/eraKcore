package com.eratw.port.erbcc.compile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 编译器指纹（[ErbcCompilerDigest]）的三条判据：
 *
 * 1. 同一源码树两次计算逐字节相同，且按固定行序写进 `manifest.txt`（`compiler-digest` /
 *    `compiler-sources`）—— 既有「同一语料两次编译清单相同」的不变量不能破；
 * 2. 清单的 digest 与**当前源码树**重算值不一致 ⇒ `ArtifactFreshness.check` 报
 *    [FreshnessIssueKind.COMPILER_STALE]（真机红 J 的旧产物形态）；
 * 3. 清单没有 `compiler-digest` 行（旧格式/旧编译器产物）⇒ 同样报 `COMPILER_STALE`，
 *    不因为「字段缺失」而静默放行。
 *
 * 用例 2/3 用「另一份合成源码树」模拟编译器被改过：**不动**真实 `core-vm`（那是已完成的
 * 修复，`1c1c3e0d`），只把 `check` 的 `compilerRepoRoot` 形参指向假仓库根。
 */
class CompilerDigestFreshnessTest {

    // ==================================================================
    // 装置
    // ==================================================================

    /** 造一棵最小「仓库」：只放 `core-vm/src/main/kotlin` 下两个 `.kt`（指纹只看这个子树）。 */
    private fun writeCompilerTree(repoRoot: Path, compilerBody: String = "fun compile() = 1\n") {
        val src = repoRoot.resolve(ErbcCompilerDigest.SOURCE_ROOT_REL)
        val compiler = src.resolve("com/eratw/port/core/compile/ErbProgramCompiler.kt")
        val writer = src.resolve("com/eratw/port/core/code/ErbcWriter.kt")
        Files.createDirectories(compiler.parent)
        Files.createDirectories(writer.parent)
        Files.write(compiler, compilerBody.toByteArray(Charsets.UTF_8))
        Files.write(writer, "fun write() = 2\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(
            root.resolve("00_const.ERH"),
            "#DEFINE G_FLAG 1\n#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8),
        )
        Files.write(
            root.resolve("01_main.ERB"),
            "@SYSTEM_TITLE\n#DIM NN\nNN = 1\nPRINTL HELLO\nPRINTFORML C={G_CONST}\n".toByteArray(Charsets.UTF_8),
        )
    }

    /** 编译 + 落盘，返回 (corpus, out)；manifest 用的 digest 由 [ErbcArtifacts.write] 自动定位仓库根算出。 */
    private fun build(dir: Path): Pair<Path, Path> {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val result = CorpusCompile.compile(corpus)
        assertTrue(result.ok, "合成语料必须编译通过；失败 ${result.failures.take(5)}")
        val out = dir.resolve("out")
        ErbcArtifacts.write(out, result.program!!, corpus, result.sourceFiles, result.stats, result.bootMetadata)
        return corpus to out
    }

    private fun manifestPathOf(out: Path): Path =
        out.resolve(ArtifactFreshness.ARTIFACT_DIR_NAME).resolve(ArtifactFreshness.MANIFEST_NAME)

    // ==================================================================
    // 用例
    // ==================================================================

    @Test
    fun `同一编译器源码树 digest 稳定且按固定行序写进 manifest`(@TempDir dir: Path) {
        val repo = dir.resolve("repo")
        writeCompilerTree(repo)

        val first = ErbcCompilerDigest.compute(repo)
        val second = ErbcCompilerDigest.compute(repo)
        assertEquals(first, second, "同一源码树两次计算必须逐字节相同（确定性）")
        assertEquals(2, first.fileCount, "参与文件数 = 源码树里的 .kt 个数")
        assertTrue(
            Regex("[0-9a-f]{64}").matches(first.digest),
            "digest 必须是 64 位小写十六进制：${first.digest}",
        )

        // 改一个字节 ⇒ 指纹必变（这是「编译器改了没重编」能检出的根）
        writeCompilerTree(repo, compilerBody = "fun compile() = 99\n")
        val changed = ErbcCompilerDigest.compute(repo)
        assertNotEquals(first.digest, changed.digest, "编译器源码改一个字节 digest 必须变")
        assertEquals(first.fileCount, changed.fileCount)

        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        val text = ErbcArtifacts.manifestText(
            corpus,
            listOf(HashEntry("erbc.v1/program.erbc", 1L, "0".repeat(64))),
            emptyList(),
            first.digest,
            first.fileCount,
        )
        assertTrue(text.contains("compiler-digest ${first.digest}\n"), "清单必须逐字写 digest：\n$text")
        assertTrue(text.contains("compiler-sources 2\n"), "清单必须写参与文件数：\n$text")
        assertTrue(
            text.indexOf("compiler-digest ") > text.indexOf("\nerbcc "),
            "指纹行固定排在 erbcc 之后（行序由输入决定）：\n$text",
        )
    }

    @Test
    fun `编译器源码与清单不一致时报 COMPILER_STALE`(@TempDir dir: Path) {
        val (corpus, out) = build(dir)
        val repo = assertNotNull(
            ErbcCompilerDigest.locateRepoRoot(out),
            "测试必须在仓库内运行（user.dir 或本类 class 位置能上溯到仓库根）",
        )
        val current = ErbcCompilerDigest.compute(repo)

        // 对照：同一棵真实源码树 ⇒ 无问题，且 summary 能拿到当前指纹
        val fresh = ArtifactFreshness.check(out, corpus, repo)
        assertTrue(fresh.ok, "刚落盘的产物不该有问题：${fresh.issues}")
        assertEquals(current.digest, fresh.compilerDigest, "check 必须把当前源码树指纹挂到报告上")
        assertTrue(
            fresh.summary().contains("compiler-digest ${current.digest}"),
            "summary 必须上屏当前指纹（人可直接与 manifest 比对）：${fresh.summary()}",
        )

        // 另一棵内容不同的源码树 ⇒ 产物被判为「旧编译器产出」
        val otherRepo = dir.resolve("other-repo")
        writeCompilerTree(otherRepo, compilerBody = "fun compile() = 3\n")
        val stale = ArtifactFreshness.check(out, corpus, otherRepo)
        assertFalse(stale.ok, "指纹不符必须报红")
        assertEquals(
            listOf(FreshnessIssueKind.COMPILER_STALE),
            stale.issues.map { it.kind },
            "只该报编译器指纹一条：${stale.issues}",
        )
        val detail = stale.issues.single().detail
        assertTrue(detail.contains("旧编译器") && detail.contains("compileCorpus"), "上屏文案必须可执行：$detail")
        assertTrue(detail.contains(current.digest), "文案要点名清单里的指纹，便于比对：$detail")
    }

    @Test
    fun `清单缺 compiler-digest 行时报 COMPILER_STALE`(@TempDir dir: Path) {
        val (corpus, out) = build(dir)
        val repo = assertNotNull(ErbcCompilerDigest.locateRepoRoot(out))
        val manifest = manifestPathOf(out)
        val kept = Files.readAllLines(manifest, Charsets.UTF_8)
            .filterNot { it.startsWith("compiler-digest ") || it.startsWith("compiler-sources ") }
        Files.write(manifest, kept, Charsets.UTF_8)

        val stale = ArtifactFreshness.check(out, corpus, repo)
        assertEquals(
            listOf(FreshnessIssueKind.COMPILER_STALE),
            stale.issues.map { it.kind },
            "缺指纹行 = 旧格式产物，必须报红：${stale.issues}",
        )
        assertTrue(
            stale.issues.single().detail.contains("没有 compiler-digest 行"),
            "文案要点名缺字段：${stale.issues.single().detail}",
        )
    }
}
