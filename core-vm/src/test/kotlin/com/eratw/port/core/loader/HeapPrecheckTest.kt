package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.FlatPoolBudget
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.host.MemoryTier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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

    /**
     * 档位接线的整链用例（2026-11-03 内存分档刀）：性质落下之后，**预检报出的**就是 0.7×
     * 而不是 0.8×。
     *
     * 为什么必须走 [ProgramLoader.estimateHeap] 而不是只测 `estimatedHeapBytes(generous = …)`：
     * 生产唯一的调用口是前者，「档位函数查了、但预检那条链没接」这种断线只有走整链才量得出来
     * —— 显式传参测不到它。挡位用 `maxMemoryOverride` 注入 ⇒ 判档与判定同一个读数，
     * 也不依赖测试 JVM 自己的堆挡位。
     */
    @Test
    fun `放开档的预检系数走的是整链而不是只有函数参数`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir, metaBytes = 0)
        val artifact = Files.size(program)
        val max512 = 512L * 1024L * 1024L
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            val conservative = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512)
            assertEquals(80L, ProgramLoader.artifactResidentPercent(max512), "没落性质 ⇒ 报告读数是保守档那一条")

            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            val generous = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512)

            assertEquals(artifact * 4L / 5L, conservative.requiredBytes, "没落性质 ⇒ 保守档 ×4/5")
            assertEquals(
                artifact * 7L / 10L,
                generous.requiredBytes,
                "性质落下 ⇒ 必须换成 ×7/10（把 estimateHeap 里的档位查询摘掉就红）",
            )
            assertTrue(generous.requiredBytes < conservative.requiredBytes)
            // 系数读数与档位那行同源（MemoryTierReadout 走 artifactResidentPercent）。
            assertEquals(70L, ProgramLoader.artifactResidentPercent(max512))
            // 256 MiB 挡即使落了性质也读保守档（产品护栏，判式第二条）。
            assertEquals(
                80L,
                ProgramLoader.artifactResidentPercent(256L * 1024L * 1024L),
                "256 MiB 挡不许被版本线打穿",
            )
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
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
     *   = 进程稳态 = 444.8 MiB（1.416×）⇒ 系数抬到 1.45，全量报 462 MiB；
     * - **2026-11-03 重标**：波次 D 分段惰性扁池 + Android `flatStatements=false` 之后整池扁平流
     *   不复存在，`HeapWallProbeTest` 在 512 MiB JVM 实测 4.981 装载 live 峰值 55–121 MiB、
     *   装载后常驻 +10 MiB ⇒ 系数收到 0.8（仍是实测最坏值的 ~2.6 倍），全量报 258 MiB。
     *   旧 1.45× 报 540 MiB 正是 4.981 在 512 MiB 真机被判「堆不足」的直接原因。
     *
     * 512 MiB 挡位现在留 ~254 MiB 给侧车/session/运行期；256 MiB 挡仍被拒（产品护栏：
     * 该挡位游玩期放不下图像预算，装载能不能过不改变结论）。
     */
    @Test
    fun `全量产物估算落在 250 到 270 MiB 且 512 MB 堆判够`() {
        // 与 KDoc / BOARD 记录的随包产物同源：329,320,658 B + 侧车 49,119,649 B / 4,888,254 指令
        // （2026-10-24 全量重编后的读数；每次重编都要同步本组常量）。
        val required = ProgramLoader.estimatedHeapBytes(
            artifactBytes = 329_320_658L,
            metaBytes = 49_119_649L,
            instructionCount = 4_888_254,
        )
        val mib = required / (1024L * 1024L)
        assertEquals(258L, mib, "0.8×产物 + 0.15×侧车 必须报这个数（1.45× 报 462，512 挡被拦死）：$required B")
        // 判据不是「比某个历史高台高」而是「比实测 live 峰值高」：`HeapWallProbeTest` 在
        // 512 MiB JVM 上实测这两份产物装载 live 峰值 43–129 MiB（最坏≈0.41×产物），
        // 0.8× 是刻意保守的上界（≈2.6× 最坏值），同时不再把 512 挡错判成不足。
        assertTrue(mib in 250L..270L, "估算 $mib MiB 必须盖住实测 live 峰值（≤129 MiB）且给 512 挡留足余量")

        val heap512 = ProgramLoader.HeapEstimate(
            artifactBytes = 329_320_658L,
            metaBytes = 49_119_649L,
            instructionCount = 4_888_254,
            requiredBytes = required,
            maxMemoryBytes = 512L * 1024L * 1024L,
        )
        assertTrue(heap512.sufficient, "512 MB 堆必须判够（4.981 真机被这一条拦死过）：$heap512")
        assertEquals(
            253L,
            (512L * 1024L * 1024L - required) / (1024L * 1024L),
            "留给侧车与 session 的余量（512 − 258.3 ≈ 253）",
        )

        val heap256 = heap512.copy(maxMemoryBytes = 256L * 1024L * 1024L)
        assertFalse(heap256.sufficient, "256 MB 堆仍必须判不足（产品护栏：该挡位游玩期放不下图像预算）：$heap256")

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

    @Test
    fun `两份产物齐缺时预检必须报 erbc 而不是 meta`(@TempDir dir: Path) {
        // P12（挖虫波第三批）：旧次序先 `Files.size(metaPath)` 再开 `.erbc`，
        // 「解压中断 / 产物整组丢失」这种**两份都缺**的现场抛的是指向 **meta** 的
        // 裸 `NoSuchFileException`；`BootFailures.classify` 于是把「缺产物」报成
        // 「缺 meta」，失败屏文案与真实缺口不一致。装载主链（[ProgramLoader.loadMapped]）
        // 本来就是先开产物再读侧车，预检同序。
        // 异常形状注意：`MappedErbc.open` 会把 IOException 包成 [ErbcLoadException]
        //（cause = NoSuchFileException），所以判据落在 cause 的文件与分类上，
        // 不是裸异常上抛 —— 前任这半截写的是旧假设，编译期即被拒。
        val absent = dir.resolve("program.erbc")
        val absentMeta = dir.resolve("program.meta")
        val e = assertFailsWith<ErbcLoadException> {
            ProgramLoader.estimateHeap(absent, absentMeta, maxMemoryOverride = Long.MAX_VALUE)
        }
        val cause = e.cause
        assertTrue(cause is NoSuchFileException, "cause 必须是 NoSuchFileException，实际：$cause")
        // `NoSuchFileException.getFile()` 是 String（JDK 契约），不是 Path —— 折成字符串对拍。
        assertEquals(absent.toString(), (cause as NoSuchFileException).file, "缺口必须指向 program.erbc")
        assertEquals(BootFailureKind.MISSING_ARTIFACT, BootFailures.classify(e).kind)
    }

    @Test
    fun `CODE 段缺失时预检与装载同式报错而不是估成零条`(@TempDir dir: Path) {
        // P13（挖虫波第三批）：旧预检 `if (isPresent(CODE)) instructionCount() else 0`，
        // 把「没有 CODE 段」估成 0 条指令 ⇒ 需求极小 ⇒ 放行，随后装载链在
        // `MappedErbc.startOrThrow(SECTION_CODE)`（MappedErbc.kt:540-544）抛
        // 「段 CODE 不存在」——预检的读数与真实失败互相矛盾，日志里还留下一条
        // `instructionCount = 0` 的假读数。现在复用装载的同一条判据，在同一处抛出。
        val (program, meta) = writeArtifacts(dir)
        val bytes = Files.readAllBytes(program)
        val header = ProgramLoader.probe(bytes, null)
        val codeOff = header.offsets[ErbcFormat.SECTION_CODE]
        assertTrue(codeOff > 0, "样本必须带 CODE 段")
        // 段表第 i 项在 `HEADER_BYTES + i*4`（ErbcFormat.firstSectionOffset），写 0 = 声明该段不存在
        writeLe32(bytes, ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_CODE * 4, 0)
        Files.write(program, bytes)

        val e = assertFailsWith<ErbcFormatException> {
            ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = Long.MAX_VALUE)
        }
        assertTrue(
            e.message!!.contains("段 CODE 不存在"),
            "报错要复用装载链的原话（MappedErbc.startOrThrow），实际：${e.message}",
        )
        assertEquals(
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailures.classify(e).kind,
            "CODE 缺失是产物损坏，不是「堆不足」也不是「够」",
        )
    }

    // ==================================================================
    // 4. 分摊语句池（flat）的预留：两态读数（T2 车道）
    // ==================================================================

    /**
     * `flatStatements=false`（生产默认 = Android 现状）必须与加这一刀之前**逐字相同**：
     * 预留 0、`requiredBytes` 与三参公式逐字节相等。既有 258 MiB 那组断言不改一行，
     * 这条只补「显式读回 0」的可观测证据。
     */
    @Test
    fun `flat 关闭时预检读数与 flatReserveBytes 逐字不变`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir, metaBytes = 10 * 1024)
        val max512 = 512L * 1024L * 1024L
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            val off = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512)
            assertEquals(0L, off.flatReserveBytes, "flat 关 ⇒ 不记预留")
            assertEquals(
                ProgramLoader.estimatedHeapBytes(off.artifactBytes, off.metaBytes, off.instructionCount),
                off.requiredBytes,
                "默认参数（generous=false、flatReserveBytes=0）下必须与原式逐字节相同",
            )
            // 缺省参就是生产口径：显式传 false 与不传必须同一个读数。
            val offExplicit = ProgramLoader.estimateHeap(
                program,
                meta,
                maxMemoryOverride = max512,
                flatStatements = false,
            )
            assertEquals(off, offExplicit)
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
    }

    /**
     * `flatStatements=true` ⇒ 预检要求**恰好多出 114 MiB**（`SegmentedFlatStmt.kt:52-61` 的真机
     * 实测增量），且预留额度两档（32 / 64 片）**一分不加**（增量大头是执行路径固定开销，
     * 调参无效）。堆 < 512 MiB 时预算判式给 DISABLED（预留 0）—— 那一挡本来就被 258 MiB 判不足。
     */
    @Test
    fun `flat 打开时预检要求的恰好多出 114 MiB 且不随片数档缩放`(@TempDir dir: Path) {
        val (program, meta) = writeArtifacts(dir, metaBytes = 0)
        val max512 = 512L * 1024L * 1024L
        val max256 = 256L * 1024L * 1024L
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            val off = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512)
            val on = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512, flatStatements = true)
            assertEquals(FlatPoolBudget.MEASURED_INCREMENT_BYTES, on.flatReserveBytes)
            assertEquals(
                off.requiredBytes + FlatPoolBudget.MEASURED_INCREMENT_BYTES,
                on.requiredBytes,
                "flat 开恰加 114 MiB（把 estimatedHeapBytes 里的 + flatReserveBytes 摘掉这里就红）",
            )

            // 放开档：产物系数换 ×7/10，但预留仍是 114 MiB（不随片数缩放）。
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            val offGenerous = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max512)
            val onGenerous = ProgramLoader.estimateHeap(
                program,
                meta,
                maxMemoryOverride = max512,
                flatStatements = true,
            )
            assertEquals(FlatPoolBudget.MEASURED_INCREMENT_BYTES, onGenerous.flatReserveBytes)
            assertEquals(offGenerous.requiredBytes + FlatPoolBudget.MEASURED_INCREMENT_BYTES, onGenerous.requiredBytes)

            // 256 MiB 挡：预算 DISABLED ⇒ 预留 0（池不会被建：258 MiB > 256 MiB 恒判不足）。
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            val on256 = ProgramLoader.estimateHeap(program, meta, maxMemoryOverride = max256, flatStatements = true)
            assertEquals(0L, on256.flatReserveBytes, "堆 < 512 MiB 预算 DISABLED：不加预留")
            assertEquals(0L, FlatPoolBudget.forHeap(max256, generous = true).reserveBytes)
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
    }

    /**
     * 全量语料（329,320,658 B / 49,119,649 B / 4,888,254 指令）两态：flat 关 = 258 MiB（不动），
     * flat 开 = 258 + 114 = **372 MiB** ⇒ 512 MiB 挡仍判够（余 139.7 MiB），256 MiB 挡仍判不足。
     *
     * 这条钉的是契约 §3.4 的登记口径；`requiredBytes` 用 [ProgramLoader.estimatedHeapBytes]
     * 的第 5 参算，判够/不足用 [ProgramLoader.HeapEstimate] 直接构造（同既有 258 MiB 那条的写法）。
     */
    @Test
    fun `全量产物 flat 两态：258 与 372 MiB，512 够 256 不够`() {
        val artifact = 329_320_658L
        val meta = 49_119_649L
        val instructions = 4_888_254
        val mib = 1024L * 1024L

        val off = ProgramLoader.estimatedHeapBytes(artifact, meta, instructions)
        val on = ProgramLoader.estimatedHeapBytes(
            artifact,
            meta,
            instructions,
            flatReserveBytes = FlatPoolBudget.MEASURED_INCREMENT_BYTES,
        )
        assertEquals(258L, off / mib, "flat 关 = 既有读数 258 MiB（本刀不许动它）")
        assertEquals(372L, on / mib, "flat 开 = 258 + 114 = 372 MiB")
        assertEquals(FlatPoolBudget.MEASURED_INCREMENT_BYTES, on - off, "增量恰好是 114 MiB")

        val estimate512 = ProgramLoader.HeapEstimate(
            artifactBytes = artifact,
            metaBytes = meta,
            instructionCount = instructions,
            requiredBytes = on,
            maxMemoryBytes = 512L * mib,
            flatReserveBytes = FlatPoolBudget.MEASURED_INCREMENT_BYTES,
        )
        assertTrue(estimate512.sufficient, "flat 开的 372 MiB 在 512 挡仍判够：$estimate512")
        assertEquals(
            139L,
            (512L * mib - on) / mib,
            "余量 139.7 MiB（契约 §3.4 的「余 140」是四舍五入；整数米向下取整为 139）",
        )
        assertFalse(estimate512.copy(maxMemoryBytes = 256L * mib).sufficient, "256 MiB 挡仍必须判不足")

        // 256 MiB 挡的预检真值：预算 DISABLED ⇒ 仍是 258 MiB，依然 > 256 MiB（产品护栏不走样）。
        val reserveAt256 = FlatPoolBudget.forHeap(256L * mib, generous = true).reserveBytes
        assertEquals(0L, reserveAt256)
        val requiredAt256 = ProgramLoader.estimatedHeapBytes(
            artifact,
            meta,
            instructions,
            flatReserveBytes = reserveAt256,
        )
        assertEquals(258L, requiredAt256 / mib)
        assertTrue(requiredAt256 > 256L * mib, "256 MiB 挡恒判不足这条护栏没被本刀动到：$requiredAt256 B")
    }
}


