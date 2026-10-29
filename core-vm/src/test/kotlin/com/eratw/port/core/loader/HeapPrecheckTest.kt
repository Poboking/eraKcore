package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 装载前堆预检（[ProgramLoader.estimateHeap] / [ProgramLoader.estimatedHeapBytes]）。
 *
 * 判据三条：
 * 1. 只读头就能拿到判据 —— CODE 条数与产物/侧车字节，不解码；
 * 2. 阈值可注入（`maxMemoryOverride`），通过/不足两态各钉一次；段 3 惰性化（M10-MEM）之后
 *    全量产物对 512 MB 堆必判**够**、对 256 MB 必判不足（旧读数是「512 必不足」，口径已换，见下）；
 * 3. 预检**不替装载器判错**：产物缺失仍是 `MISSING_ARTIFACT`、头截断仍是 `CORRUPT_ARTIFACT`，
 *    与 `loadMapped` 同一套 [BootFailures] 分类。
 *
 * 标定数字（2026-10-24 合流后本机实测，`ERATW_ERBC` 真实产物用例）：`program.erbc` 329,320,658 B /
 * 4,888,254 条指令 / 侧车 49,119,649 B（即 BOARD 记录的随包产物），惰性档**催过 GC 的
 * 解码实时高水位** 230.0 MiB（0.726×产物）、装载后 post-GC 常驻 +100.4 MiB（0.317×）；
 * 估算按 1.1×产物 + 0.15×侧车 ⇒ 352.5 MiB（报 352），也就是在 live 高水位之上留 1.5× 余量给 ART 的
 * footprint 涨阶。**标定基准必须与磁盘产物同源**（CMP-23）：本文件的 README 与 KDoc 必须
 * 每次重编后同步更新——旧用例曾拿 328,799,098 B / 4,800,579 条这组更早的读数对
 * 331,892,378 B 的 KDoc，两边差 3 MiB 级、口径不可对账。
 * 整池时代的 628.6 MiB 已作废，换口径的理由写在 `ProgramLoader.estimatedHeapBytes` 的 KDoc 里，
 * 「预检不许少报」由 `LazyStatementDecodeTest.真实产物：催过 GC 的解码实时高水位被预检估算包住` 钉住。
 */
class HeapPrecheckTest {

    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    /** 写一份真产物 + 一份占位侧车（侧车内容不参与估算，只看字节数）。 */
    private fun writeArtifacts(
        dir: Path,
        metaBytes: Int = 4 * 1024,
    ): Pair<Path, Path> {
        val program = dir.resolve("program.erbc")
        Files.write(program, ErbcWriter.encode(sampleProgram()))
        val meta = dir.resolve("program.meta")
        Files.write(meta, ByteArray(metaBytes))
        return program to meta
    }

    // ==================================================================
    // 1. 通过 / 不足两态（注入堆上限）
    // ==================================================================

    @Test
    fun `堆上限等于估算值时判通过`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir)
        val probe = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = 0L)
        val exact = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = probe.requiredBytes)

        assertTrue(exact.sufficient, "maxMemory == required 必须算够（>= 而不是 >）：$exact")
        assertEquals(probe.requiredBytes, exact.requiredBytes, "注入只换上限，不改估算")
    }

    @Test
    fun `堆上限差一字节就判不足`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir)
        val probe = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = 0L)
        val short = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = probe.requiredBytes - 1)

        assertFalse(short.sufficient, "差一字节必须判不足：$short")
    }

    @Test
    fun `不注入时用 Runtime maxMemory 作上限`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir)
        val estimate = ProgramLoader.estimateHeap(program, meta)

        assertEquals(Runtime.getRuntime().maxMemory(), estimate.maxMemoryBytes)
        assertEquals(estimate.maxMemoryBytes >= estimate.requiredBytes, estimate.sufficient)
    }

    @Test
    fun `估算读的是头里的 CODE 条数与两侧字节数`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir, metaBytes = 10 * 1024)
        val estimate = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = Long.MAX_VALUE)

        assertEquals(1, estimate.instructionCount, "合成产物只有一条 _HALT")
        assertEquals(Files.size(program), estimate.artifactBytes)
        assertEquals(10L * 1024L, estimate.metaBytes)
        assertEquals(
            ProgramLoader.estimatedHeapBytes(estimate.artifactBytes, estimate.metaBytes, 1),
            estimate.requiredBytes,
            "入口与公式必须同一实现",
        )
    }

    // ==================================================================
    // 2. 全量语料标定：段 3 惰性化之后 512 MB 堆必判**够**
    // ==================================================================

    /**
     * 这一条钉的是 M10-MEM 的出口判据「真机 `sufficient=true`、不再需要 `force_load`」。
     *
     * 口径换了三次，别再拿旧读数对：
     * - 整池解码时代标的是**装载后常驻** 628.6 MiB（产物 2.0×）⇒ 全量恒报 634 MiB、512 判不足；
     * - 段 3 惰性化（M10-MEM）之后装载后常驻只有 +100.4 MiB（0.317×），预检取的是**解码内部
     *   催过 GC 的实时高水位** 230.0 MiB（0.726×），1.1× 在它之上留 1.5× 余量 ⇒ 352 MiB；
     * - **T2-64 波次 C「压扁即弃」**（2026-10-28）：扁平池构建挪进装载链的 FLAT 段，装载高水位
     *   = 进程稳态 = 444.8 MiB（1.416×）⇒ 系数抬到 1.45，全量报 462 MiB。
     *
     * ⚠️ 512 MiB 挡位的余量因此从 159 MiB 收到 ~50 MiB（侧车 `program.meta` 一份就 46.8 MiB）。
     * 形式上仍判够（本用例继续钉这条），但实际已经贴边 —— 要不要改产品口径（512 挡位标成
     * 需要 force_load / 把扁平流再惰性化）是组长的决定，见 `ProgramLoader.estimatedHeapBytes`
     * 的警告段与 lane B 交付消息。ART 侧读数仍是 TODO(verify)（本机无设备）。
     */
    @Test
    fun `全量产物估算落在 450 到 470 MiB 且 512 MB 堆判够`() {
        // 与 KDoc / BOARD 记录的随包产物同源：329,320,658 B + 侧车 49,119,649 B / 4,888,254 指令
        // （2026-10-24 全量重编后的读数；每次重编都要同步本组常量）。
        val required = ProgramLoader.estimatedHeapBytes(
            artifactBytes = 329_320_658L,
            metaBytes = 49_119_649L,
            instructionCount = 4_888_254,
        )
        val mib = required / (1024L * 1024L)
        assertEquals(462L, mib, "1.45×产物 + 0.15×侧车 必须报这个数（波次 C 前的 1.1× 口径报 352）：$required B")
        assertTrue(mib in 450L..470L, "估算 $mib MiB 必须落在波次 C 装载高水位（444.8 MiB）之上且留余量")

        val heap512 = ProgramLoader.HeapEstimate(
            artifactBytes = 329_320_658L,
            metaBytes = 49_119_649L,
            instructionCount = 4_888_254,
            requiredBytes = required,
            maxMemoryBytes = 512L * 1024L * 1024L,
        )
        assertTrue(heap512.sufficient, "512 MB 堆仍须判够（否则 M10-MEM 的出口判据打不到）：$heap512")
        // 余量从波次 C 前的 159 MiB 收到 49 MiB —— 这条钉的是"判够"，不是"余量宽裕"；
        // 真实贴边风险见 method KDoc 的警告（侧车 46.8 MiB 一份就吃掉大半）。
        assertEquals(49L, (512L * 1024L * 1024L - required) / (1024L * 1024L), "留给侧车与 session 的余量（512 − 462.4 ≈ 49）")

        val heap256 = heap512.copy(maxMemoryBytes = 256L * 1024L * 1024L)
        assertFalse(heap256.sufficient, "256 MB 堆仍必须判不足（估算不是随便调小的）：$heap256")

        val heap768 = heap512.copy(maxMemoryBytes = 768L * 1024L * 1024L)
        assertTrue(heap768.sufficient, "768 MB 堆应判够（余量约 $mib MiB）：$heap768")
    }

    @Test
    fun `CODE 条数下界按真实常驻口径：lines 2 B 条 + code 0 常驻`() {
        // M8-COD-EMAP 之后 code 是只读映射（堆内 0 常驻），lines 是 u16 紧凑表（2 B/条）
        // ⇒ 每条指令的堆常驻只有 2 B，不是旧口径的 20 B（4 字 × 4 B + 4 B）。
        assertEquals(9_787_218L, ProgramLoader.estimatedHeapBytes(0L, 0L, 4_893_609))
        assertEquals(2L, ProgramLoader.estimatedHeapBytes(0L, 0L, 1))
    }

    // ==================================================================
    // 3. 撒谎的 CODE 计数：预检与真实装载同式（CMP-23）
    // ==================================================================

    private fun writeLe32(
        bytes: ByteArray,
        at: Int,
        v: Int,
    ) {
        for (k in 0..3) bytes[at + k] = (v ushr (8 * k)).toByte()
    }

    /**
     * 旧实现只做 `instructionCount() > 0` 就按「条数 × 20 B」估需求：把头里的 CODE
     * 计数改成 `Int.MAX_VALUE` 会报 **42.9 GB**（2,147,483,647 × 20 B），失败屏误报
     * 「堆不足」，而真相是产物损坏。新实现先跑与 `load`/`loadMapped` 同一条
     * `checkSectionLayout` ⇒ 计数与段长不符在估算之前就被拒成 `CORRUPT_ARTIFACT`。
     */
    @Test
    fun `撒谎的 CODE 计数在估算前被段布局校验拒掉，不会报到 42 9 GB`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir)
        val bytes = Files.readAllBytes(program)
        val header = ProgramLoader.probe(bytes, null)
        val codeOff = header.offsets[ErbcFormat.SECTION_CODE]
        assertTrue(codeOff > 0, "样本必须带 CODE 段")
        writeLe32(bytes, codeOff, Int.MAX_VALUE)
        Files.write(program, bytes)

        // 旧判据（「只判 > 0」）确实放行这个计数：单独钉住，证明修复点不是空话。
        MappedErbc.open(program).use { m ->
            assertEquals(Int.MAX_VALUE, m.instructionCount(), "instructionCount() 只判正数（旧预检的唯一判据）")
        }

        val e = assertFailsWith<ErbcFormatException>("预检必须与真实装载同式：先跑段布局校验") {
            ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = 64L * 1024L * 1024L)
        }
        assertTrue(
            e.message!!.contains("CODE 段声明") && e.message!!.contains("本段区间只剩"),
            "拒绝理由必须是段布局校验的原话，实际：${e.message}",
        )
        assertEquals(
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailures.classify(e).kind,
            "撒谎计数是产物损坏，不是「堆不足」",
        )
        // 旧口径的量级自证：Int.MAX_VALUE × 20 B = 42,949,672,940 B ≈ 42.9 GB（换成真实
        // 2 B/条也是 4.3 GB）—— 这正是要堵的失败屏误报量级。
        assertEquals(
            42L,
            Int.MAX_VALUE.toLong() * 20L / 1_000_000_000L,
            "旧口径（20 B/条）把撒谎计数报到 42.9 GB —— 十进制 GB 的整数位是 42",
        )
    }

    // ==================================================================
    // 3. 预检不替装载器判错
    // ==================================================================

    @Test
    fun `产物缺失时预检归 MISSING_ARTIFACT`(@TempDir dir: Path) {
        val absent = dir.resolve("program.erbc")
        val absentMeta = dir.resolve("program.meta")
        val e = assertFailsWith<Throwable> {
            ProgramLoader.estimateHeap(absent, absentMeta, maxMemoryOverride = Long.MAX_VALUE)
        }
        assertEquals(BootFailureKind.MISSING_ARTIFACT, BootFailures.classify(e).kind, "缺失判定必须与装载同源：$e")
    }

    @Test
    fun `头截断时预检归 CORRUPT_ARTIFACT`(@TempDir dir: Path) {
        val program = dir.resolve("program.erbc")
        Files.write(program, ErbcWriter.encode(sampleProgram()).copyOf(20))
        val meta = dir.resolve("program.meta")
        Files.write(meta, ByteArray(16))

        val e = assertFailsWith<Throwable> {
            ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = Long.MAX_VALUE)
        }
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, BootFailures.classify(e).kind, "头畸形必须原样落到格式分支：$e")
    }
}


