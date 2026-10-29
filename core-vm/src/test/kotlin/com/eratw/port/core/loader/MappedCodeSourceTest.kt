package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.CodeSource
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.IntArrayCodeSource
import com.eratw.port.core.code.MappedCodeSource
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 装载侧的 CODE 段接线（M8-COD-EMAP）：`SECTION_CODE` 不再装配成 `IntArray`，
 * 而是把 `[段首 + 4, 段首 + 4 + 16n)` 的只读映射窗口交给 [MappedCodeSource]，
 * 由 `Program(codeSource = ...)` 持有到 Program 生命周期结束。
 *
 * ## 为什么这组用例必须存在
 *
 * `w8-codeview` 只把**接缝**（[CodeSource] / `Program(codeSource=)` / VM 按页取指）做完了，
 * `Program.code` 的 73.25 MiB 仍在装载期被复制进堆。本波把这根线接上，于是「收益」和
 * 「回归面」同时移动了位置：
 *
 * 1. **收益**：`codeResidentBytes == 0`（映射装载）；线程分配计数里不再出现整段 `IntArray`。
 * 2. **回归面**：解码器/校验器任何一处顺手读 `program.code` 都会把整段重新物化，收益归零，
 *    所以「分配量 < 整段 code 字节」是硬闸（[装载不含整段拷贝]）。
 * 3. **等价**：逐 pc 的 `op/a/b/c` 与 `instructionAt` 必须与 `loadFile` 相同，页视图拼起来
 *    必须逐字等于 `loadFile` 的 `code`，`.erbc` 再编码必须逐字节回到原始容器。
 * 4. **生命周期**：映射块由 Program 持有，`MappedProgramSource.close()` 之后仍可读
 *    （关通道不 unmap），但句柄自身的段读数必须立刻失效（不读悬空映射）。
 *
 * 读数装置沿用 `ErbcStreamPeakTest` 同一把尺子（`HeapMeter` + `ThreadMXBean` 线程分配计数），
 * 基线口径见 `ProgramLoader` 类 KDoc 的表。
 */
class MappedCodeSourceTest {

    // ------------------------------------------------------------------
    // 样本
    // ------------------------------------------------------------------

    /**
     * 量级样本：`instructions` 条真指令 + 常量/字符串/语句池各有一点内容，
     * 与 `MmapLoaderTest.bigProgram` 同构（差异只在池的条目数，便于小样本也走到校验分支）。
     */
    private fun bigProgram(instructions: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val strings = (0 until 60).map { b.string("str-$it-驻留") }
        val consts = (0 until 20).map { b.constant(it.toLong() * 1_000_003L) }
        val stmts = (0 until 20).map {
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), it + 1, "WAIT  #$it"))
        }
        var i = 0
        while (i < instructions) {
            when (i % 5) {
                0 -> b.emit(Opcode._SET_INT, SlotAddr.local(0), consts[i % consts.size], 0, i % 97)
                1 -> b.emit(Opcode._SET_STR, SlotAddr.local(1), strings[i % strings.size], 0, i % 97)
                2 -> b.emit(Opcode._PRINT_LITERAL, strings[(i * 7) % strings.size], 0, SlotAddr.NONE, i % 97)
                3 -> b.emit(Opcode._NOP, 0, 0, 0, -1)
                else -> b.emit(Opcode.WAIT, stmts[i % stmts.size], 0, SlotAddr.NONE, i % 97)
            }
            i++
        }
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(
        dir: Path,
        bytes: ByteArray,
        name: String = "program.erbc",
    ): Path {
        val f = dir.resolve(name)
        Files.write(f, bytes)
        return f
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
    }

    /**
     * 逐字节/逐字的对拍**不用** `assertContentEquals`：它在失败时会把整段数组
     * `Arrays.toString` 进消息（全量产物上那是 3 亿字节 / 2 千万字 → 消息本身 OOM）。
     * 这里只报首个差异下标，失败诊断仍然是精确的。
     */
    private fun assertBytesEqual(
        expected: ByteArray,
        actual: ByteArray,
        ctx: String,
    ) {
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                fail(
                    "$ctx：首个差异在偏移 $i（期望 ${expected[i].toInt() and 0xFF}，" +
                        "实际 ${actual[i].toInt() and 0xFF}）",
                )
            }
        }
        assertEquals(expected.size, actual.size, "$ctx：字节数")
    }

    private fun assertWordsEqual(
        expected: IntArray,
        actual: IntArray,
        ctx: String,
    ) {
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                fail("$ctx：首个差异在下标 $i（期望 ${expected[i]}，实际 ${actual[i]}）")
            }
        }
        assertEquals(expected.size, actual.size, "$ctx：字数")
    }

    /** 线程分配计数（与 `ErbcStreamPeakTest` / `StreamLoadTest` 同一把尺子）。 */
    private class AllocMeter private constructor(
        private val bean: com.sun.management.ThreadMXBean,
        private val tid: Long,
    ) {
        fun read(): Long = bean.getThreadAllocatedBytes(tid)

        companion object {
            fun open(): AllocMeter? = runCatching {
                val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    ?: return null
                if (!bean.isThreadAllocatedMemorySupported) return null
                bean.isThreadAllocatedMemoryEnabled = true
                val tid = Thread.currentThread().id
                bean.getThreadAllocatedBytes(tid)
                AllocMeter(bean, tid)
            }.getOrNull()
        }
    }

    // ==================================================================
    // 1. 常驻口径：映射装载后 code 的堆占用为 0
    // ==================================================================

    @Test
    fun `映射装载后 code 常驻堆字节为 0 且来源是只读映射`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(4_000)))
        val mapped = ProgramLoader.loadMapped(f)

        assertEquals(0L, mapped.codeResidentBytes, "映射来源的指令流常驻堆字节必须为 0")
        assertTrue(
            mapped.codeSource is MappedCodeSource,
            "映射装载的 codeSource 应为 MappedCodeSource，实为 ${mapped.codeSource.javaClass.name}",
        )
        assertEquals(mapped.instructionCount * 4, mapped.codeSource.wordCount, "字数 = 4 字/条指令")
        assertEquals(CodeSource.DEFAULT_PAGE_WORDS, mapped.codeSource.pageWords, "默认页字数（4 的整数倍）")
        assertTrue(mapped.codeSource.pageCount >= 1, "页数至少 1")
        assertTrue(mapped.lineTableResidentBytes > 0L, "行表仍是紧凑堆内表示（本波不动）")

        // 非映射路径不动：loadFile 仍是零拷贝 IntArray 来源
        val full = ProgramLoader.loadFile(f)
        assertTrue(full.codeResidentBytes > 0L, "loadFile 的 code 仍常驻堆内：${full.codeResidentBytes}")
        assertTrue(full.codeSource is IntArrayCodeSource, "loadFile 的 codeSource 仍是 IntArray 视图")
        assertSame(full.code, full.codeSource.page(0), "IntArray 整段一页 ⇒ page(0) 就是入参数组本体")
    }

    // ==================================================================
    // 2. 等价：逐 pc 的四个操作数 + 指令解码
    // ==================================================================

    @Test
    fun `逐 pc 与 loadFile 全等：op a b c 与 instructionAt`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(3_000)))
        val full = ProgramLoader.loadFile(f)
        val mapped = ProgramLoader.loadMapped(f)
        assertEquals(full.instructionCount, mapped.instructionCount, "指令条数")
        assertEquals(full.codeSource.wordCount, mapped.codeSource.wordCount, "码字总数")

        for (pc in 0 until full.instructionCount) {
            assertEquals(full.op(pc), mapped.op(pc), "pc=$pc 的 op")
            assertEquals(full.a(pc), mapped.a(pc), "pc=$pc 的 a")
            assertEquals(full.b(pc), mapped.b(pc), "pc=$pc 的 b")
            assertEquals(full.c(pc), mapped.c(pc), "pc=$pc 的 c")
            assertEquals(full.instructionAt(pc), mapped.instructionAt(pc), "pc=$pc 的 Instruction 解码")
        }

        // 页视图拼起来必须逐字等于整段 code（映射读 vs 堆内数组的唯一口径差：来源）
        assertContentEquals(
            full.code,
            mapped.codeSource.toIntArray(),
            "映射页全量 ${mapped.codeSource.wordCount} 字 == loadFile.code",
        )
    }

    // ==================================================================
    // 3. 再编码：逐字节回到原始容器
    // ==================================================================

    @Test
    fun `再编码逐字节相同：映射装载 == 全读装载 == 原始字节`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(bigProgram(8_000))
        val f = writeArtifact(dir, bytes)
        val mapped = ProgramLoader.loadMapped(f)
        val full = ProgramLoader.loadFile(f)

        // 再编码是冷路径（ErbcWriter 读 program.code / program.lines）：
        // 映射来源在这里物化一次是**契约允许**的，但字节必须与原始容器逐字节相同。
        val againMapped = ErbcWriter.encode(mapped)
        val againFull = ErbcWriter.encode(full)
        assertBytesEqual(bytes, againMapped, "映射装载再编码必须逐字节回到原始容器（${bytes.size} B）")
        assertBytesEqual(againFull, againMapped, "两条装载路径的再编码字节必须相同")
        assertTrue(againMapped.isNotEmpty(), "再编码结果不能为空")
    }

    // ==================================================================
    // 4. 页契约与段首 +4 窗口
    // ==================================================================

    @Test
    fun `页契约：页字数 4 的整数倍，逐页拼接等于整段，窗口跳过段首 u32`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(5_000)))
        ProgramLoader.mapped(f).use { m ->
            val n = m.instructionCount()
            val cs = m.codeSource(n)
            val words = m.readCode()

            assertEquals(n * 4, cs.wordCount, "字数 = 4 × 指令条数")
            assertEquals(0, cs.pageWords % 4, "页字数必须是 4 的整数倍（一条指令不跨页）")
            assertTrue(cs.pageCount >= 2, "5000 条指令（20,004 字 > 默认 4,096 字/页）应跨多页，实得 ${cs.pageCount} 页")
            assertEquals(
                cs.wordCount,
                (0 until cs.pageCount).sumOf { cs.page(it).size },
                "逐页字数之和 == wordCount（尾页可短）",
            )
            assertEquals(words.first(), cs.word(0), "窗口起点 = 段首 u32 之后（+4）")
            assertEquals(words.last(), cs.word(words.size - 1), "窗口终点 = 段末")
            // 页边界两侧的指令都必须完整：1023/1024 是默认页的接缝
            assertEquals(words[1023 * 4], cs.word(1023 * 4), "页接缝前一字")
            assertEquals(words[1024 * 4], cs.word(1024 * 4), "页接缝后一字")
        }
    }

    @Test
    fun `codeSource 的段长判定与 readCode 同一句报错`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(1_200)))
        ProgramLoader.mapped(f).use { m ->
            val n = m.instructionCount()
            val over = assertFailsWith<ErbcDecodeException> { m.codeSource(n + 1) }
            assertTrue(
                over.message!!.contains("CODE 段声明 ${n + 1} 条指令"),
                "条数多于段区间时应报与 readCode/validateSections 同一句：${over.message}",
            )
            val zero = assertFailsWith<ErbcDecodeException> { m.codeSource(0) }
            assertTrue(zero.message!!.contains("必须为正"), "0 条指令应报「必须为正」：${zero.message}")
            // 正例：同一个 n 拿到的视图与 readCode 逐字相同
            assertContentEquals(m.readCode(), m.codeSource(n).toIntArray(), "codeSource 与 readCode 同源")
        }
    }

    // ==================================================================
    // 5. 生命周期：close 之后 Program 仍可读，句柄段读数立刻失效
    // ==================================================================

    @Test
    fun `open 句柄 close 之后 Program 仍可读（映射与 Program 同寿）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(2_000)))
        val expected = ProgramLoader.loadFile(f)

        val handle = ProgramLoader.open(f)
        val program = handle.program()
        assertSame(program, handle.program(), "重复 program() 恒返回同一实例（语句池以实例为键）")
        assertTrue(handle.isMaterialized, "首次 program() 之后应标记已物化")
        handle.close()

        // close 只关通道；Program 持有的映射块仍然有效（JVM 语义：关通道不 unmap）
        val pcs = listOf(0, 1, 1_024, 1_999, expected.instructionCount - 1)
        for (pc in pcs) {
            assertEquals(expected.op(pc), program.op(pc), "close 后 pc=$pc 的 op")
            assertEquals(expected.a(pc), program.a(pc), "close 后 pc=$pc 的 a")
            assertEquals(expected.b(pc), program.b(pc), "close 后 pc=$pc 的 b")
            assertEquals(expected.c(pc), program.c(pc), "close 后 pc=$pc 的 c")
        }
        assertContentEquals(
            expected.code,
            program.codeSource.toIntArray(),
            "close 后整段映射仍逐字可读",
        )
        // 句柄自身的段读数必须立刻失效，不允许读到悬空映射
        assertFailsWith<ErbcLoadException> { handle.instructionCount() }
    }

    // ==================================================================
    // 6. 分配计数：装载链里没有整段 IntArray 复制
    // ==================================================================

    /** 预热：类加载 / JIT / 两个解码器的常量池先碰一遍，再开始计数。 */
    private fun warmUp(dir: Path) {
        val f = dir.resolve("warm.erbc")
        Files.write(f, ErbcWriter.encode(bigProgram(400)))
        ProgramLoader.loadFile(f)
        ProgramLoader.loadMapped(f)
        System.gc()
    }

    @Test
    fun `装载不含整段拷贝：线程分配计数里映射路径小于 code 整段字节`(@TempDir dir: Path) {
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数（com.sun.management.ThreadMXBean）：跳过")
        val m = requireNotNull(meter)
        warmUp(dir)

        val file = dir.resolve("big.erbc")
        Files.write(file, ErbcWriter.encode(bigProgram(200_000)))
        val fileBytes = Files.size(file)

        // loadFile：整份字节 + code/lines 两个 IntArray 都进堆
        var a0 = m.read()
        val full = ProgramLoader.loadFile(file)
        val fullAllocated = m.read() - a0
        val codeBytes = full.instructionCount.toLong() * 16L
        assertTrue(codeBytes > 3L shl 20, "样本 code 段太小（$codeBytes B）：整段复制会被噪声淹没")
        System.gc()

        // loadMapped：段 5 只映射不装配（含段布局校验、引用范围校验、规范化去重）
        a0 = m.read()
        val mapped = ProgramLoader.loadMapped(file)
        val mappedAllocated = m.read() - a0

        assertEquals(0L, mapped.codeResidentBytes, "映射装载的 code 常驻 = 0")
        println(
            "[erbc-codemap] 合成产物 ${mib(fileBytes)} / code ${mib(codeBytes)}：" +
                "loadFile 分配 ${mib(fullAllocated)} / loadMapped 分配 ${mib(mappedAllocated)}" +
                "（省 ${mib(fullAllocated - mappedAllocated)}）",
        )

        // 判据 1：映射路径的分配量必须小于整段 code 本身 —— 装配 IntArray（或任何一处
        // 顺手读 program.code）都会把它顶上去，收益当场归零。
        assertTrue(
            mappedAllocated < codeBytes,
            "loadMapped 分配 ${mib(mappedAllocated)} 不应达到整段 code 字节 ${mib(codeBytes)}：" +
                "装载链里出现了整段复制或 Program.code 物化",
        )
        // 判据 2：两条路径的差额至少要覆盖那一段 code 的 0.9 倍
        assertTrue(
            fullAllocated - mappedAllocated >= codeBytes * 9 / 10,
            "loadFile 比 loadMapped 应多分配至少 0.9×code（code ${mib(codeBytes)}，" +
                "实差 ${mib(fullAllocated - mappedAllocated)}）",
        )
    }

    // ==================================================================
    // 7. 真实产物：code 常驻前/后与分配读数（ERATW_ERBC 动态门）
    // ==================================================================

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    @Test
    fun `真实产物：code 段常驻前后、装载读数与再编码等价`(@TempDir dir: Path) {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过真实产物用例")
        val file = requireNotNull(path)

        // 阶段 1：只驻留映射装载的 Program（打完读数、对拍段字节、写出一份再编码）
        val mappedOut = measureMappedLoad(file, dir)
        // 上面的 73.3 MiB 临时数组与 Program 都在函数返回时出作用域，再付阶段 2 的全读 Program
        System.gc()

        // 阶段 2：全读装载的 Program 再编码 —— 两条路径装载出的 Program 必须编出同一份字节
        val fullOut = reencodeViaLoadFile(file, dir)
        val twoPath = firstDiff(mappedOut, fullOut)
        assertTrue(
            twoPath == null,
            "映射装载与全读装载的 Program 再编码必须逐字节相同：$twoPath",
        )
        println(
            "[erbc-codemap] 再编码：映射装载 ${mib(Files.size(mappedOut))} / 全读装载 ${mib(Files.size(fullOut))}，逐字节相同",
        )
        // 容器级 identity 取决于产物的产出构建（本机 artifact 由 `D:\DSH\eratw-build` 的旧 core 产出），
        // 这里如实报出差异位置，不作为本波判据。
        println("[erbc-codemap] 再编码 vs 原容器：${firstDiff(file, mappedOut) ?: "逐字节相同"}")
    }

    /** 阶段 1：量一次映射装载，断言 code 常驻 0，并把再编码写到 [dir]（返回文件路径）。 */
    private fun measureMappedLoad(
        file: Path,
        dir: Path,
    ): Path {
        val meter = requireNotNull(AllocMeter.open())
        val fileBytes = Files.size(file)
        HeapMeter.resetPeaks()
        val before = HeapMeter.settled()
        val a0 = meter.read()
        val t0 = System.nanoTime()
        val program = ProgramLoader.loadMapped(file)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val allocated = meter.read() - a0
        val peak = HeapMeter.peak()
        val after = HeapMeter.settled()

        // 改前口径：code = 4 字/条 × 4 B；改后常驻 0，仍留在映射页里
        val legacyCode = program.instructionCount.toLong() * 16L
        println(
            "[erbc-codemap] 真实产物 ${mib(fileBytes)} / 指令 ${program.instructionCount}：" +
                "code 常驻 ${mib(program.codeResidentBytes)}（改前 ${mib(legacyCode)}）" +
                " / 行表 ${mib(program.lineTableResidentBytes)}" +
                " / 装载分配 ${mib(allocated)} / 常驻前 ${mib(before)} → 后 ${mib(after)}" +
                "（+${mib(after - before)}）/ 段内峰值 ${mib(peak)} / ${ms} ms",
        )

        assertEquals(0L, program.codeResidentBytes, "真实产物映射装载后 code 常驻 = 0")
        assertTrue(program.instructionCount > 0, "指令条数必须为正")
        assertTrue(
            program.codeSource is MappedCodeSource,
            "真实产物 codeSource 应为 MappedCodeSource，实为 ${program.codeSource.javaClass.name}",
        )

        // 全量对拍段字节（不经过第二个 Program）：映射窗口 == readCode 装配结果
        ProgramLoader.mapped(file).use { raw ->
            val words = raw.readCode()
            assertEquals(program.codeSource.wordCount, words.size, "段表读出的字数")
            assertWordsEqual(words, program.codeSource.toIntArray(), "映射窗口全量 == CODE 段字节")
        }

        // 「改前」口径的同会话证据：把映射来源物化回 IntArray 那一次分配的堆字节。
        // 用线程分配计数（与装载读数同一把尺子）而不是 settled() 差值：后者受 G1 提交/碎片影响，
        // 上百万次 GC 读数的最小值仍可能差出几百 MiB，而这里要钉的是「这一整段数组」
        // （4 × 4,800,579 × 4 B = 76.8 MB，就是改前常驻堆里的那一块）。
        val a1 = meter.read()
        program.code.size // 冷路径物化（ErbcWriter/反汇编同一条路径），只为读数
        val materializeBytes = meter.read() - a1
        println(
            "[erbc-codemap] 常驻对照：code 段常驻 ${mib(program.codeResidentBytes)}；" +
                "同会话物化回 IntArray 的堆分配 ${mib(materializeBytes)}" +
                "（= 目标数组 ${mib(legacyCode)} + 逐页装配的同量临时页，`CodeSource.toIntArray` 口径）" +
                " ⇒ 改前那 ${mib(legacyCode)} 是常驻堆，改后在映射页",
        )
        assertEquals(
            0L,
            program.codeResidentBytes,
            "物化只影响兼容视图，codeResidentBytes 的口径仍是 IntArray 来源 = 0",
        )

        val version = ProgramLoader.probeMapped(file).version
        val out = dir.resolve("reencode-mapped.erbc")
        Files.write(out, ErbcWriter.encode(program, ErbcEncodeOptions(version = version)))
        return out
    }

    /** 阶段 2：全读装载后再编码（读 `code`/`lines` 的冷路径，与映射路径同一份 writer）。 */
    private fun reencodeViaLoadFile(
        file: Path,
        dir: Path,
    ): Path {
        val version = ProgramLoader.probeMapped(file).version
        val program = ProgramLoader.loadFile(file)
        val out = dir.resolve("reencode-loadfile.erbc")
        Files.write(out, ErbcWriter.encode(program, ErbcEncodeOptions(version = version)))
        return out
    }

    /**
     * 两个文件的逐字节比较：相同返回 null，否则返回「首个差异」的可读描述。
     *
     * 不用 `Files.readAllBytes` + `contentEquals`：全量产物上那会同时占两份 313.6 MiB，
     * 而这里的失败诊断只需一个偏移。null 之外的返回值**必须**被断言/打印，不允许静默。
     */
    private fun firstDiff(
        a: Path,
        b: Path,
    ): String? {
        val sa = Files.size(a)
        val sb = Files.size(b)
        Files.newInputStream(a).use { ia ->
            Files.newInputStream(b).use { ib ->
                val ba = ByteArray(1 shl 16)
                val bb = ByteArray(1 shl 16)
                var at = 0L
                while (true) {
                    val na = ia.read(ba)
                    val nb = ib.read(bb)
                    if (na != nb) return "读取块长不同（偏移 $at：$na vs $nb；$sa B vs $sb B）"
                    if (na < 0) break
                    for (k in 0 until na) {
                        if (ba[k] != bb[k]) return "首个差异在偏移 ${at + k}（${a.fileName} $sa B vs ${b.fileName} $sb B）"
                    }
                    at += na
                }
            }
        }
        return if (sa == sb) null else "字节数不同（$sa vs $sb）"
    }
}
