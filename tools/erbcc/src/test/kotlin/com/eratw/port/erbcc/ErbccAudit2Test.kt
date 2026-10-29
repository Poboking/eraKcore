package com.eratw.port.erbcc

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmException
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.io.TempDir

/**
 * L18 erbcc 工具链 / CodeEE 复任审计（lane audit2/l18-erbcc）。钉死三处口径，
 * 每条带规约 / 反编译 C# 行号：
 *
 * 1. **manifest 版本号 = 磁盘容器实字节**（[ErbcArtifacts]，specs/erbc-format.md:490 §8.7）。
 *    派生工具的 `container-version` / `format-version` 必须等于 [ErbcFormat.LATEST_VERSION]，
 *    不得写死 [ErbcFormat.VERSION_1]；本用例同时从 `program.erbc` 首部解出真实版本对账。
 * 2. **`.erbc` 再编码恒等**：装载回 `Program` 后 `ErbcWriter.encode` 必须逐字节还原落盘字节。
 * 3. **CodeEE 继承 / 消息**：message 恰为传入的 errormes（位置是结构化字段，不揉进串，
 *    spec: EmueraException.cs:9-13 `base(errormes)` + `Position` 独立字段），VM 保护闸子类
 *    （[StepLimitExceeded] / [CallStackOverflow]）继承 [CodeEE] 仍可被 `catch (CodeEE)` 兜住
 *    （spec: CodeEE.cs:3 `CodeEE : EmueraException`；移植侧 Errors.kt 的 typealias 单源裁决）。
 * 4. **bridge → 失败条目映射**：`CorpusCompile` 把 [com.eratw.port.core.compile.CompileException]
 *    落成带 `文件:行号` 与 `[sigKey]` 前缀的 [com.eratw.port.erbcc.compile.CompileFailure]
 *    （CorpusCompile.kt:386-388）。
 *
 * 目录名 `erbc.v1` 是**冻结的跨模块路径常量**（app/platform/GameHost/GameAssetInstaller/GameStorage
 * 均以字面量 `erbc.v1` 取产物），与容器版本解耦 —— §8.7 的「产物目录名用 LATEST_VERSION」一侧属
 * 三侧联动的组长接线面，本用例只钉「解耦是有意的」这一事实，不断言目录名等于版本。
 */
class ErbccAudit2Test {

    private fun writeCorpus(root: Path) {
        Files.createDirectories(root)
        Files.write(root.resolve("00_const.ERH"), "#DIM CONST G_CONST = 7\n".toByteArray(Charsets.UTF_8))
        Files.write(
            root.resolve("01_main.ERB"),
            """
            @MINI_P(ARG, ARG:1)
            PRINTFORML P={ARG:1}

            @MINI_MAIN
            CALL MINI_P(1, 2)
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
    }

    private fun compile(dir: Path): com.eratw.port.erbcc.compile.CompileResult {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val r = CorpusCompile.compile(corpus)
        assertTrue(r.ok, "合成语料必须编译通过；失败 ${r.failures.size} 条: ${r.failures.take(5)}")
        assertNotNull(r.program)
        return r
    }

    private fun versionField(manifest: String, key: String): Int? =
        manifest.lineSequence()
            .firstOrNull { it.startsWith("$key ") }
            ?.removePrefix("$key ")?.trim()?.toIntOrNull()

    @Test
    fun `manifest 版本号与 program erbc 首部真实版本同源`(@TempDir dir: Path) {
        val result = compile(dir)
        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, dir.resolve("corpus"),
            result.sourceFiles, result.stats, result.bootMetadata,
        )
        val bytes = Files.readAllBytes(report.artifactPath)
        val actualVersion = ErbcReader.probe(bytes).version

        // 规约 §8.7（specs/erbc-format.md:490）：默认产出 LATEST_VERSION，容器实字节必须是它。
        assertEquals(ErbcFormat.LATEST_VERSION, actualVersion, "program.erbc 首部 version 应为 LATEST_VERSION")
        val manifest = Files.readString(report.manifestPath)
        val cv = assertNotNull(versionField(manifest, "container-version"), "缺 container-version 行")
        val fv = assertNotNull(versionField(manifest, "format-version"), "缺 format-version 行")
        // 修复点：清单版本号 == 磁盘字节 == LATEST_VERSION，而非写死的 VERSION_1。
        assertEquals(actualVersion, cv, "manifest container-version 必须等于容器实字节版本")
        assertEquals(ErbcFormat.LATEST_VERSION, cv, "container-version 不得写死 VERSION_1")
        assertEquals(actualVersion, fv, "manifest format-version 必须等于容器实字节版本")
        if (ErbcFormat.LATEST_VERSION != ErbcFormat.VERSION_1) {
            assertFalse(manifest.contains("container-version ${ErbcFormat.VERSION_1}\n"), "仍写死 v1？")
            assertFalse(manifest.contains("format-version ${ErbcFormat.VERSION_1}\n"), "仍写死 v1？")
        }
    }

    @Test
    fun `目录名与容器版本有意解耦 目录名冻结为 erbc v1`(@TempDir dir: Path) {
        val result = compile(dir)
        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, dir.resolve("corpus"),
            result.sourceFiles, result.stats, result.bootMetadata,
        )
        // 目录名 = app/platform 侧固定读取的跨模块常量；容器真实版本走 manifest 字段。
        assertEquals("erbc.v1", report.artifactPath.parent.fileName.toString())
        assertEquals("erbc.v1/program.erbc", report.artifact.relPath)
        assertEquals("erbc.v1/${com.eratw.port.erbcc.compile.BootMetaWriter.FILE_NAME}", report.meta?.relPath)
        val manifest = Files.readString(report.manifestPath)
        assertEquals(
            ErbcFormat.LATEST_VERSION, versionField(manifest, "container-version"),
            "目录名可停留在 erbc.v1，但 container-version 字段必须反映 v2 容器",
        )
    }

    @Test
    fun `落盘产物装载后再编码逐字节恒等`(@TempDir dir: Path) {
        val result = compile(dir)
        val report = ErbcArtifacts.write(
            dir.resolve("out"), result.program!!, dir.resolve("corpus"),
            result.sourceFiles, result.stats, result.bootMetadata,
        )
        val bytes = Files.readAllBytes(report.artifactPath)
        val loaded = ProgramLoader.load(bytes, "program.erbc")
        assertArrayEquals(bytes, ErbcWriter.encode(loaded), "装载 → 再编码必须逐字节还原落盘字节")
        assertTrue(
            ErbcReader.probe(bytes).hasSection(ErbcFormat.SECTION_PARAM_NAMES),
            "带形参项的产物必须携带 v2 段 8（PARAM_NAMES）",
        )
    }

    @Test
    fun `manifest 字段修复后仍确定性 两次写逐字节相同`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        writeCorpus(corpus)
        val r1 = CorpusCompile.compile(corpus)
        val r2 = CorpusCompile.compile(corpus)
        val a = ErbcArtifacts.write(dir.resolve("o1"), r1.program!!, corpus, r1.sourceFiles, r1.stats, r1.bootMetadata)
        val b = ErbcArtifacts.write(dir.resolve("o2"), r2.program!!, corpus, r2.sourceFiles, r2.stats, r2.bootMetadata)
        assertEquals(
            Files.readString(a.manifestPath), Files.readString(b.manifestPath),
            "同一语料两次编译的 manifest 必须逐字节相同（改动后仍成立）",
        )
        assertArrayEquals(Files.readAllBytes(a.artifactPath), Files.readAllBytes(b.artifactPath))
    }

    @Test
    fun `CodeEE 消息只带 errormes 位置为结构化字段`() {
        // spec: EmueraException.cs:9-13 —— base(errormes) 令 Message == errormes，Position 独立字段；
        //        CodeEE.cs:3 —— CodeEE : EmueraException（脚本错误基类）。
        val e = CodeEE("不正な識別子です", funcName = "FOO", line = 12, pc = 3)
        assertEquals("不正な識別子です", e.message, "message 必须原样是 errormes，不拼位置")
        assertEquals("FOO", e.funcName)
        assertEquals(12, e.line)
        assertEquals(3, e.pc)
        val bare = CodeEE("仅文案")
        assertEquals(-1, bare.line, "line 未提供时默认 -1（未知）")
        assertEquals(-1, bare.pc, "pc 未提供时默认 -1")
    }

    @Test
    fun `VM 保护闸子类继承 CodeEE 仍可被 catch`() {
        // spec: Errors.kt VmException : CodeEE；CodeEE.kt open（全工程 catch (CodeEE) 兜住）。
        val step = StepLimitExceeded(steps = 10L, line = 5, pc = 7, funcName = "F")
        val stack = CallStackOverflow(depth = 9, line = 1, pc = 2, funcName = "G")
        for (e in listOf<VmException>(step, stack)) {
            var caught: CodeEE? = null
            try {
                throw e
            } catch (c: CodeEE) {
                caught = c
            }
            assertSame(e, caught, "子类必须能被 catch (CodeEE) 兜住（单一真源裁决）")
        }
    }

    @Test
    fun `bridge 编译异常落成带文件行号与 sigKey 前缀的失败条目`(@TempDir dir: Path) {
        val corpus = dir.resolve("corpus")
        Files.createDirectories(corpus)
        Files.write(
            corpus.resolve("01_broken.ERB"),
            """
            @MINI_BROKEN
            IF 1 > 0
            	PRINTL NEVER_END
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val result = CorpusCompile.compile(corpus)
        assertFalse(result.ok, "未闭合 IF 必须判失败")
        val mapped = result.failures.firstOrNull { it.message.contains("[MINI_BROKEN]") }
        assertNotNull(mapped, "CompileException 应落成 [MINI_BROKEN] 前缀的失败条目（CorpusCompile.kt:386-388）")
        assertTrue(mapped.file.endsWith("01_broken.ERB"), "失败条目必须带源文件: ${mapped.file}")
        assertTrue(mapped.line >= 1, "行号取异常行或标签行，必须 1 起: ${mapped.line}")
        assertTrue(mapped.toString().startsWith("01_broken.ERB:${mapped.line} "), "失败条目串 = 文件:行号 前缀")
    }
}
