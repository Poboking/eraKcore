package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 开机装载失败分类（[BootFailures]）与三条真实失败路径的对拍。
 *
 * 判据 = 宿主 `GameHost` 的失败分支都必须落到既有文案，不许静默、不许新造词：
 * 1. 产物缺失（`ProgramLoader.loadMapped` 打开不存在的 `program.erbc`）→
 *    [BootFailureKind.MISSING_ARTIFACT]，`cause` 链里必须真是 [NoSuchFileException]；
 * 2. 段损坏（截断 / CODE 段计数撒谎）→ [BootFailureKind.CORRUPT_ARTIFACT]，
 *    文案 = 装载器原话（`ProgramLoader.validateSections` 那一层，`ProgramLoader.kt:348-390`）；
 * 3. 映射失败（[FileSystemException]：占用/权限/地址空间）→ [BootFailureKind.MAPPING_FAILED]，
 *    包装层带「映射 [a,b) 失败」时原样保留（`MappedErbc.kt:527-543`）；
 * 4. 堆不足（`OutOfMemoryError`，含包装/其它 cause）→ [BootFailureKind.HEAP_TOO_SMALL]，
 *    固定句子 [BootFailures.HEAP_SHORTAGE_TEXT]，**前置**于上面三条。
 *
 * 另外钉住「分类不吞其它错误」（装配期 `require` 走 OTHER）与「cause 成环有界」。
 */
class BootFailureTest {

    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun writeArtifact(
        dir: Path,
        bytes: ByteArray,
    ): Path {
        val f = dir.resolve("program.erbc")
        Files.write(f, bytes)
        return f
    }

    // ==================================================================
    // 1. 文件缺失
    // ==================================================================

    @Test
    fun `产物文件缺失分类为 MISSING_ARTIFACT 且原因是 NoSuchFileException`(@TempDir dir: Path) {
        val absent = dir.resolve("erbc.v1").resolve("program.erbc")
        val e = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(absent) }
        assertTrue(e.cause is NoSuchFileException, "打开失败必须保留 NoSuchFileException：${e.cause}")

        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.MISSING_ARTIFACT, failure.kind)
        assertTrue(failure.detail!!.contains("program.erbc"), "detail 应指向缺失文件：${failure.detail}")
    }

    @Test
    fun `宿主自己抛的 NoSuchFileException 同样分类为 MISSING_ARTIFACT`(@TempDir dir: Path) {
        // GameHost.requireArtifact 在 UI 线程检查与 VM 线程装载之间补的那一道。
        val f = dir.resolve("program.meta")
        val failure = BootFailures.classify(NoSuchFileException(f.toString()))
        assertEquals(BootFailureKind.MISSING_ARTIFACT, failure.kind)
        assertEquals(f.toString(), failure.detail)
    }

    @Test
    fun `侧车在检查后消失仍分类为 MISSING_ARTIFACT`(@TempDir dir: Path) {
        // BootMetadata.read 必须保留 IOException 的 cause，否则这里会被误判成段损坏。
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.read(dir.resolve("program.meta")) }
        assertTrue(e.cause is NoSuchFileException, "读取失败必须保留 cause：${e.cause}")
        assertEquals(BootFailureKind.MISSING_ARTIFACT, BootFailures.classify(e).kind)
    }

    // ==================================================================
    // 2. 段损坏
    // ==================================================================

    @Test
    fun `截断的产物分类为 CORRUPT_ARTIFACT 且文案是装载器原话`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = writeArtifact(dir, bytes.copyOf(20))
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(f) }

        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, failure.kind)
        assertEquals(e.message, failure.detail, "宿主只该给装载器原话加标题，不该改写")
        assertTrue(failure.detail!!.contains(f.toString()), "原话应带产物路径：${failure.detail}")
    }

    @Test
    fun `CODE 段计数撒谎分类为 CORRUPT_ARTIFACT 且带段长原话`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram()).copyOf()
        val codeOff = ProgramLoader.probe(bytes, null).offsets[ErbcFormat.SECTION_CODE]
        writeLe32(bytes, codeOff, 9_999) // 段区间装不下 9,999 条指令
        val f = writeArtifact(dir, bytes)
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(f) }

        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, failure.kind)
        assertTrue(failure.detail!!.contains("CODE 段声明 9999 条指令"), failure.detail!!)
    }

    @Test
    fun `空文件分类为 CORRUPT_ARTIFACT`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ByteArray(0))
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(f) }
        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, failure.kind)
        assertTrue(failure.detail!!.contains("文件不足文件头长度"), failure.detail!!)
    }

    @Test
    fun `侧车格式错分类为 CORRUPT_ARTIFACT`() {
        val e = assertFailsWith<BootMetaFormatException> { BootMetadata.parse("这不是侧车") }
        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, failure.kind)
        assertEquals(e.message, failure.detail)
    }

    // ==================================================================
    // 3. 映射失败（FileSystemException）
    // ==================================================================

    @Test
    fun `装载器包装的映射失败保留区间与原因`() {
        val path = "/game/erbc.v1/program.erbc"
        val denied = AccessDeniedException(path, null, "另一个程序正在使用此文件")
        val e = ErbcLoadException("$path: 映射 [0,4096) 失败（AccessDeniedException: 另一个程序正在使用此文件）", denied)

        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.MAPPING_FAILED, failure.kind)
        assertEquals(e.message, failure.detail, "包装层原话（含映射区间）必须原样落屏")
    }

    @Test
    fun `裸 FileSystemException 按既有读取失败口径补路径`() {
        val denied = AccessDeniedException("/game/erbc.v1/program.meta", null, "拒绝访问。")
        val failure = BootFailures.classify(denied)
        assertEquals(BootFailureKind.MAPPING_FAILED, failure.kind)
        assertTrue(
            failure.detail!!.startsWith("/game/erbc.v1/program.meta: 读取失败（AccessDeniedException: 拒绝访问。"),
            failure.detail!!,
        )
    }

    @Test
    fun `缺失优先于文件系统分支`() {
        // NoSuchFileException 是 FileSystemException 的子类：分类必须落到缺失，不是映射失败。
        val e = ErbcLoadException(
            "/game/erbc.v1/program.erbc: 打开失败（NoSuchFileException: /game/erbc.v1/program.erbc）",
            NoSuchFileException("/game/erbc.v1/program.erbc"),
        )
        assertEquals(BootFailureKind.MISSING_ARTIFACT, BootFailures.classify(e).kind)
    }

    // ==================================================================
    // 4. 堆不足（前置分支）
    // ==================================================================

    @Test
    fun `OutOfMemoryError 分类为 HEAP_TOO_SMALL 且落固定文案`() {
        val failure = BootFailures.classify(OutOfMemoryError("Java heap space"))
        assertEquals(BootFailureKind.HEAP_TOO_SMALL, failure.kind)
        assertEquals(BootFailures.HEAP_SHORTAGE_TEXT, failure.detail)
        assertEquals(
            "堆不足（全量装载的解码峰值约需 350–420 MiB；largeHeap 已开，仍不足请用 ≥6 GB RAM 机型）",
            failure.detail,
            "文案逐字锁定：宿主只加「启动失败：」标题",
        )
        assertTrue("Java heap space" !in failure.detail!!, "Java 原话不许落屏：${failure.detail}")
    }

    @Test
    fun `包在装载器包装里的 OOM 仍前置到 HEAP_TOO_SMALL`() {
        val wrapped = ErbcLoadException(
            "/game/erbc.v1/program.erbc: 解码中断（OutOfMemoryError: Java heap space）",
            OutOfMemoryError("Java heap space"),
        )
        assertEquals(BootFailureKind.HEAP_TOO_SMALL, BootFailures.classify(wrapped).kind)
    }

    @Test
    fun `堆不足优先于 cause 链里的缺失与格式异常`() {
        // 预检/OOM 与文件异常同时出现时，观众只该看到「堆不足」这一句。
        val oom = OutOfMemoryError("Failed to allocate a 1048576 byte allocation")
        oom.initCause(ErbcFormatException("段 CODE 声明 9999 条指令"))
        val failure = BootFailures.classify(oom)
        assertEquals(BootFailureKind.HEAP_TOO_SMALL, failure.kind)
        assertEquals(BootFailures.HEAP_SHORTAGE_TEXT, failure.detail)
    }

    @Test
    fun `非堆错误不被误报成 HEAP_TOO_SMALL`() {
        // NegativeArraySizeException 是 RuntimeException 不是 OutOfMemoryError：走原有格式/其它分支。
        assertEquals(BootFailureKind.OTHER, BootFailures.classify(IllegalStateException("x")).kind)
    }

    // ==================================================================
    // 5. 其它错误与 cause 环
    // ==================================================================

    @Test
    fun `装配期错误不冒充装载失败`() {
        val e = IllegalArgumentException("侧车与 Program 不配对：函数下标 9 越过 program.functions（3）")
        val failure = BootFailures.classify(e)
        assertEquals(BootFailureKind.OTHER, failure.kind)
        assertEquals(e.message, failure.detail)
    }

    @Test
    fun `cause 成环时分类有界且不抛异常`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a) // Java 只禁自指，两节点环合法
        val failure = BootFailures.classify(a)
        assertEquals(BootFailureKind.OTHER, failure.kind)
        assertEquals("a", failure.detail)
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
