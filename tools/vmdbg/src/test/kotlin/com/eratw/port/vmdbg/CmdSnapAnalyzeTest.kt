package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `snapshot validate|expand|diff` 的 CLI 面用例（MODERN-T3 R1）：用 [MiniCorpus] 产**真快照**
 * 跑三个子命令，并钉死「分析器不装配会话」—— 分析路径的入参只有快照文件，不读
 * `--erbc/--game`，也不建沙箱（源码哨兵用例）。
 *
 * 退出码口径：0 PASS / 1 用法 / 2 主文件缺失 / 3 INVALID（CONTRACT-MODERN-T3-R1 §3.2）。
 */
class CmdSnapAnalyzeTest {

    private lateinit var tmp: Path
    private lateinit var built: MiniCorpus.Built
    private lateinit var work: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-snap-analyze-test")
        built = MiniCorpus.build(tmp)
        work = tmp.resolve("work")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun assemble(): Assembler.Assembled =
        Assembler.assemble(Args.parse(MiniCorpus.args(built, work).toList()))

    private fun run(vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val ebuf = ByteArrayOutputStream()
        val code = Cli.run(
            arrayOf(*args),
            out = PrintStream(buf, true, Charsets.UTF_8),
            err = PrintStream(ebuf, true, Charsets.UTF_8),
        )
        return code to buf.toString(Charsets.UTF_8) + ebuf.toString(Charsets.UTF_8)
    }

    /** 用既有 `snapshot` 落一份真快照（主文件 + sidecar），返回主文件路径。 */
    private fun captureSnapshot(): Path {
        val snap = work.resolve("snapshot.dbg")
        val code = CmdSnap.snapshot(assemble(), snap, PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(snap), "快照主文件应落盘: $snap")
        assertTrue(Files.isRegularFile(CmdSnap.sidecarPath(snap)), "sidecar 应落盘")
        return snap
    }

    private fun syntheticSidecar(rngLen: Int): String = buildString {
        append("{\"format\":1")
        append(",\"createdAt\":\"2026-11-08T10:00:00+08:00\"")
        append(",\"commit\":\"\"")
        append(",\"randSeed\":20260226")
        append(",\"rngState\":[").append((0 until rngLen).joinToString(",") { "0" }).append(']')
        append(",\"consoleTail\":[]")
        append(",\"funcStack\":[]")
        append(",\"funcDepth\":0}")
    }

    private fun binary(magic: Long, version: Int): ByteArray {
        val b = ByteArray(40)
        for (k in 0 until 8) b[k] = ((magic ushr (8 * k)) and 0xFFL).toByte()
        for (k in 0 until 4) b[8 + k] = ((version ushr (8 * k)) and 0xFF).toByte()
        return b
    }

    // ------------------------------------------------------------------
    // validate
    // ------------------------------------------------------------------

    @Test
    fun validateRealSnapshotPasses() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("container=text" in text, text)
        assertTrue("mainSha256=" in text, text)
        assertTrue("rngState.len=625" in text, text)
        assertTrue("funcDepth=" in text, text)
        assertTrue("sidecar=" in text && "sidecar=absent" !in text, text)
    }

    @Test
    fun validateMissingMainIsExit2() {
        val (code, text) = run("snapshot", "validate", tmp.resolve("nope.dbg").toString())
        assertEquals(2, code, text)
        assertTrue("status=MISSING" in text, text)
        assertTrue("快照主文件不存在" in text, text)
    }

    @Test
    fun validateMissingSidecarIsOnlyWarning() {
        val snap = captureSnapshot()
        Files.delete(CmdSnap.sidecarPath(snap))
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("sidecar=absent" in text, text)
        assertTrue("warning=sidecar 缺席" in text, text)
    }

    @Test
    fun validateBrokenSidecarIsExit3() {
        val snap = captureSnapshot()
        Files.writeString(CmdSnap.sidecarPath(snap), "{not json", Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", snap.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("sidecar 不可解析" in text, text)
    }

    @Test
    fun validateRngLengthViolationIsExit3() {
        val main = work.resolve("bad-rng.dbg")
        Files.createDirectories(work)
        Files.writeString(main, "12345\n1|2\n", Charsets.UTF_8)
        Files.writeString(CmdSnap.sidecarPath(main), syntheticSidecar(624), Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", main.toString())
        assertEquals(3, code, text)
        assertTrue("status=INVALID" in text, text)
        assertTrue("rngState 长度=624" in text, text)
    }

    @Test
    fun validateBinaryVersionMismatchIsExit3() {
        val main = work.resolve("bad-version.dbg")
        Files.createDirectories(work)
        Files.write(main, binary(SnapSidecar.BINARY_MAGIC, 1807))
        Files.writeString(CmdSnap.sidecarPath(main), syntheticSidecar(0), Charsets.UTF_8)
        val (code, text) = run("snapshot", "validate", main.toString())
        assertEquals(3, code, text)
        assertTrue("container=binary" in text, text)
        assertTrue("版本=1807" in text, text)
    }

    // ------------------------------------------------------------------
    // expand
    // ------------------------------------------------------------------

    @Test
    fun expandRealSnapshotPrintsSections() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "expand", snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("vars=not-expanded(offline; use restore)" in text, text)
        assertTrue("--- func stack (top first) ---" in text, text)
        assertTrue("--- console tail (oldest first) ---" in text, text)
        assertTrue("rngState.len=625" in text, text)
        assertTrue("rngState.digest=" in text, text)
        assertTrue("mainSha256=" in text, text)
    }

    @Test
    fun expandOutWritesFileAndPrintsOneLine() {
        val snap = captureSnapshot()
        val target = tmp.resolve("expanded.txt")
        val (code, text) = run("snapshot", "expand", snap.toString(), "--out", target.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS out=" in text, text)
        assertTrue(target.toString() in text, text)
        val written = Files.readString(target, Charsets.UTF_8)
        assertTrue("mainSha256=" in written, written)
        assertTrue("vars=not-expanded" in written, written)
    }

    // ------------------------------------------------------------------
    // diff
    // ------------------------------------------------------------------

    @Test
    fun diffSameSnapshotReportsSame() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString(), snap.toString())
        assertEquals(0, code, text)
        assertTrue("status=PASS" in text, text)
        assertTrue("main.bytesSame=true" in text, text)
        assertTrue("same=true" in text, text)
        assertTrue(text.trimEnd().endsWith("same=true"), text)
    }

    @Test
    fun diffChangedSidecarStillExitsZero() {
        val snapA = captureSnapshot()
        val snapB = work.resolve("b.dbg")
        Files.copy(snapA, snapB)
        val sideA = Files.readString(CmdSnap.sidecarPath(snapA), Charsets.UTF_8)
        val sideB = sideA.replace("\"randSeed\":20260226", "\"randSeed\":7")
        assertTrue(sideB != sideA, "randSeed 改写应生效")
        Files.writeString(CmdSnap.sidecarPath(snapB), sideB, Charsets.UTF_8)

        val (code, text) = run("snapshot", "diff", snapA.toString(), snapB.toString())
        assertEquals(0, code, text)
        assertTrue("main.bytesSame=true" in text, text)
        assertTrue("randSeed.a=20260226 randSeed.b=7 same=false" in text, text)
        assertTrue(text.trimEnd().endsWith("same=false"), text)
    }

    @Test
    fun diffMissingSecondPathIsUsageError() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString())
        assertEquals(1, code, text)
        assertTrue("diff 缺少第二个快照路径" in text, text)
    }

    @Test
    fun diffMissingMainIsExit2() {
        val snap = captureSnapshot()
        val (code, text) = run("snapshot", "diff", snap.toString(), tmp.resolve("nope.dbg").toString())
        assertEquals(2, code, text)
        assertTrue("status=MISSING" in text, text)
    }

    // ------------------------------------------------------------------
    // 离线铁律（源码哨兵）
    // ------------------------------------------------------------------

    @Test
    fun analyzerSourcesNeverAssembleSession() {
        for (name in listOf("SnapSidecar.kt", "CmdSnapAnalyze.kt")) {
            val candidates = listOf(
                File("src/main/kotlin/com/eratw/port/vmdbg/$name"),
                File("tools/vmdbg/src/main/kotlin/com/eratw/port/vmdbg/$name"),
            )
            val file = candidates.firstOrNull { it.isFile }
                ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
            val src = file.readText(Charsets.UTF_8)
            // 钉「调用」而不是字样：KDoc 里引用 [Assembler.assemble] 说明「不调它」是允许的，
            // 真调用一定带 '('。
            assertTrue("Assembler.assemble(" !in src, "$name 属离线分析器，不得装配会话（出现 Assembler.assemble 调用）")
            assertTrue("GameSession" !in src, "$name 不得依赖 GameSession")
        }
    }
}
