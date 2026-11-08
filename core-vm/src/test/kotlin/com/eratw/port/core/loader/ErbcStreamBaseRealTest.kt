package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.CompositeCodeSource
import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.ProgramBase
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R7-D3 阶段 1：C3 的 `ErbcStreamBaseTest` 三条核心判据在**真产物**上的复刻
 * （契约 CONTRACT-R7-D.md §2/D3 阶段 1；本类只新增测试，禁碰任何 main 源文件）。
 *
 * ## 门（savepin 波 2026-11-05 改成显式门，文案自证）
 * 真产物 = `:tools:erbcc:compileCorpus` 写出的 `program.erbc`（332 MB 级），**不是语料游戏根的组成部分**：
 * 2026-11-05 实读两棵语料树（`eratw-画蛇添足版eratw4.980`、`eraTW4.981-261017-EAGLOV3`）
 * 递归 `*.erbc` **各 0 个文件**、也没有 `program/` 目录 ⇒ 「设了 `ERATW_GAMEDIR`」从来不等于
 * 「真产物可用」，旧门词把这件事写成「未设置环境变量」，读数人照着查环境变量什么也查不到。
 * 现在 [gateMessage] 逐候选自证：认到的根、拼出来的候选路径、每个候选缺什么。
 *
 * 档位（与 `core-vm/build.gradle.kts` 的 `wireRealArtifacts()` 同源）：
 * - `-PwithRealArtifacts=true :core-vm:test` 或 `:core-vm:gateRealArtifacts` ⇒ 连带导出
 *   `ERATW_REAL_ERBC_REQUIRE=1`，产物不在位当场 [AssertionError] **判红**（口径同
 *   [com.eratw.port.perf.SessionRetainmentCanaryTest] 的 `ERATW_CANARY_REQUIRE`）；
 * - §5 标准闸不带档位 ⇒ 三条用例走**可见的 skipped**（332 MB 产物 + 十几分钟真跑，
 *   按 AGENTS.md 的口径不能无条件塞进标准闸），skip 文案就是上面那份自证清单。
 *
 * 候选顺序（[realErbcCandidates]）：`ERATW_ERBC`（直接指文件）→ `ERATW_ERBC_DIR`（产物目录）→
 * `ERATW_GAMEDIR`（游戏根下 `program.erbc`，兼容 `BootMetaParamDefaultsTest.realMetaPath` 的老口径，
 * 按上面的实读它现在必然落空，但留在候选清单里让文案能说出「为什么落空」）。
 * **跑真产物用例必须带变量 + `cleanTest`/`--rerun`**（环境变量不在 test 任务 inputs，UP-TO-DATE 会假绿）。
 *
 * 内存纪律（`ErbcStreamEquivalenceTest` 先例：真产物全读档 [Program] 堆驻留 GB 级，
 * 3g 测试堆**一次只允许驻留一个**重 Program）：
 * - 判据①（真产物做 base + mini 补丁）：真产物**映射档**（指令流零堆驻留、池惰性）
 *   与 mini 补丁同驻 —— 轻载组合，允许双驻留逐位对拍；
 * - 判据②（真产物零基线两路）：全读档快照 → `System.gc()` → 映射档快照，顺序驻留，
 *   两份重 Program 永不同驻；
 * - 判据③（拼接）：真产物映射档做 base + 堆内 mini 补丁 tail，全量逐字对拍
 *   （`MappedCodeSource.word` 是绝对位置读，单次读无页分配，约 1,920 万字 × 2 可承受）。
 *
 * 定性读数（装载时长 / 线程分配字节 / `codeResidentBytes`）以 `REALTIMING {…}` JSONL 行
 * println 到测试输出，由组长摘录落 `.tmp/inbox/r7d3/`（测试自身不写数表，保持纯验证面）。
 */
class ErbcStreamBaseRealTest {

    // ==================================================================
    // 门与读数装置
    // ==================================================================

    /** 显式门：真产物档（`-PwithRealArtifacts=true` / `:core-vm:gateRealArtifacts`）导出它。 */
    private companion object {
        const val REAL_ERBC_REQUIRE_ENV = "ERATW_REAL_ERBC_REQUIRE"

        /** 语料根里 `program.erbc` 的落点（老口径的兼容候选；2026-11-05 实读两根都没有）。 */
        const val PROGRAM_FILE = "program.erbc"
    }

    /** 真产物档已开启（档位导出 `ERATW_REAL_ERBC_REQUIRE=1`）⇒ 产物缺席必须判红。 */
    private fun requireRealErbc(): Boolean = System.getenv(REAL_ERBC_REQUIRE_ENV)?.trim() == "1"

    /**
     * 逐候选（文件路径 + 判定文案），顺序即查找顺序；[realErbcPath] 取第一个「是普通文件」的。
     *
     * 判定文案必须说清「这个候选是从哪个变量认来的、拼出来是什么、缺什么」——
     * 门词的用途是让读数人不用读代码就能判断该补哪一步。
     */
    private fun realErbcCandidates(): List<Pair<Path, String>> = listOfNotNull(
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val p = Path.of(raw)
            p to verdict(p, "ERATW_ERBC=$raw")
        },
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val p = Path.of(raw)
            val f = if (Files.isDirectory(p)) p.resolve(PROGRAM_FILE) else p
            f to verdict(f, "ERATW_ERBC_DIR=$raw（拼出 $f）")
        },
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val f = Path.of(raw).resolve(PROGRAM_FILE)
            f to verdict(f, "ERATW_GAMEDIR=$raw（拼出 $f；语料根不产 program.erbc，2026-11-05 实读 4.980/4.981 两根各 0 个 .erbc）")
        },
    )

    /** 单个候选的判定：在位 / 缺文件（附父目录内容，说明「目录是空的」还是「没有这类产物」）。 */
    private fun verdict(file: Path, source: String): String {
        if (Files.isRegularFile(file)) return "$source —— 在位（${Files.size(file)} 字节）✓"
        val parent = file.parent
        val listing = if (parent != null && Files.isDirectory(parent)) {
            val names = runCatching {
                Files.list(parent).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            }.getOrElse { return "$source —— 父目录列不出来：${it.message}" }
            val erbc = names.filter { it.endsWith(".erbc") }
            "父目录 ${names.size} 项，其中 .erbc = $erbc；前 12 项 = ${names.take(12)}"
        } else {
            "父目录不在位"
        }
        return "$source —— 缺 ${file.fileName}（$listing）"
    }

    private fun realErbcPath(): Path? = realErbcCandidates().firstOrNull { Files.isRegularFile(it.first) }?.first

    /** 环境变量实况（未设置也要说出来：门词的用途是让读数人不用读代码就知道缺哪一环）。 */
    private fun envState(name: String): String =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: "<未设置>"

    /**
     * 候选清单 + 环境实况 + 缺省产物落点（skipped 与判红共用这一条出处，两处文案不会漂）。
     *
     * 缺省产物落点是**只报不载**的：`ERATW_REAL_ERBC_REQUIRE` 没导出时即使它存在也不自动去装载
     * （332 MB 产物 + 十几分钟真跑不属于 §5 标准闸），但读数人必须看见「产物到底在不在」。
     */
    private fun gateDetail(): String {
        val cands = realErbcCandidates()
        val tried = if (cands.isEmpty()) {
            "  - （三个候选变量都没设：ERATW_ERBC / ERATW_ERBC_DIR / ERATW_GAMEDIR）"
        } else {
            cands.joinToString("\n") { "  - ${it.second}" }
        }
        val defaultArtifact = Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1", PROGRAM_FILE)
        return buildString {
            appendLine("环境实况：ERATW_ERBC=${envState("ERATW_ERBC")}，" +
                "ERATW_ERBC_DIR=${envState("ERATW_ERBC_DIR")}，ERATW_GAMEDIR=${envState("ERATW_GAMEDIR")}")
            appendLine("试过的候选（按查找顺序）：")
            appendLine(tried)
            appendLine("缺省产物落点 ${defaultArtifact.toAbsolutePath().normalize()}：" +
                verdict(defaultArtifact, "由 :tools:erbcc:compileCorpus 写出"))
            append("真产物只由 :tools:erbcc:compileCorpus 产出（332 MB 级，装它要 3g 堆 + 十几分钟 ⇒ 显式档，" +
                "不在 §5 标准闸里无条件跑）；语料游戏根**不含** program.erbc" +
                "（2026-11-05 实读：4.980 与 4.981 两棵树递归 *.erbc 各 0 个文件、也没有 program/ 目录），" +
                "所以「设了 ERATW_GAMEDIR」不等于「真产物可用」")
        }
    }

    /**
     * skipped 的文案（**自证**，不再把原因推给「未设置环境变量」）：
     * 本类三条是「真产物档」的用例，档由 `-PwithRealArtifacts=true` 或 `:core-vm:gateRealArtifacts` 开；
     * 没开档时 `ERATW_GAMEDIR` 即使设了也不代表有产物（见 [gateDetail] 的实读结论）。
     */
    private fun gateMessage(): String =
        "真产物档未开启（显式门 $REAL_ERBC_REQUIRE_ENV=1 由 -PwithRealArtifacts=true 或 " +
            ":core-vm:gateRealArtifacts 导出；开了而产物不在位会判红不是跳过）。本轮实况：" + gateDetail()

    /** 产物缺席 + 档位已开 ⇒ 判红（不许退回 skipped）。 */
    private fun failMissingArtifact(): Nothing = throw AssertionError(
        "真产物档已开启（$REAL_ERBC_REQUIRE_ENV=1）但没有可用的 $PROGRAM_FILE：\n" + gateDetail(),
    )

    /** 门：拿到路径就返回；拿不到时按档位判红或走可见 skip（skip 原因同时 println，闸的 XML 里可取证）。 */
    private fun realErbcOrGate(): Path {
        realErbcPath()?.let { return it }
        if (requireRealErbc()) failMissingArtifact()
        println("[erbc-real-gate] ${gateMessage()}")
        assumeTrue(false, gateMessage())
        error("不可达：assumeTrue(false) 已抛 TestAbortedException")
    }

    /** 当前线程已分配字节（HotSpot `com.sun.management.ThreadMXBean`；不可用时 -1）。 */
    private fun allocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return -1L
        if (!bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return -1L
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    private fun realTiming(stage: String, elapsedMs: Long, allocated: Long, program: Program, note: String = "") {
        val fields = buildString {
            append("\"stage\":\"").append(stage).append('"')
            append(",\"elapsedMs\":").append(elapsedMs)
            append(",\"allocatedBytes\":").append(allocated)
            append(",\"codeResidentBytes\":").append(program.codeResidentBytes)
            append(",\"instructionCount\":").append(program.instructionCount)
            append(",\"functions\":").append(program.functions.size)
            if (note.isNotEmpty()) append(",\"note\":\"").append(note).append('"')
        }
        println("REALTIMING {$fields}")
    }

    // ==================================================================
    // 判据②（真产物零基线，I1）：两装载路径读数快照逐位一致
    // ==================================================================

    /**
     * 8 访问点读数快照（重产物不能双驻留，读数后即弃 Program）：
     * 六基线与 instructionBase、code/lines 全量哈希、常量/串全量哈希、
     * 表达式/语句采样哈希（惰性解码贵，512 条均匀步长，`ErbcStreamEquivalenceTest` 口径）、
     * 函数表全字段哈希、callArgs 全量哈希、取指采样哈希（4,096 点）、entry/globalSlots、
     * `codeResidentBytes`。
     */
    private data class RealReadings(
        val codeBase: Int,
        val functionBase: Int,
        val statementBase: Int,
        val stringBase: Int,
        val longBase: Int,
        val exprBase: Int,
        val instructionBase: Int,
        val instructionCount: Int,
        val codeHash: Int,
        val linesHash: Int,
        val constantCount: Int,
        val constantHash: Int,
        val stringCount: Int,
        val stringHash: Int,
        val exprCount: Int,
        val exprSampleHash: Int,
        val statementCount: Int,
        val statementSampleHash: Int,
        val functionCount: Int,
        val functionsHash: Int,
        val callArgsKeysHash: Int,
        val callArgsHash: Int,
        val fetchSampleHash: Int,
        val entryFunction: Int,
        val globalSlots: Int,
        val codeResidentBytes: Long,
    )

    private fun readingsOf(program: Program): RealReadings {
        // 取指采样：全区间均匀 4,096 点（op/a/b/c/lineOf 捆绑哈希，钉「字节读歪」类分歧）
        val total = program.instructionCount
        val step = max(1, total / 4_096)
        var fetchHash = 17
        for (pc in program.instructionBase until total step step) {
            fetchHash = fetchHash * 31 + program.op(pc)
            fetchHash = fetchHash * 31 + program.a(pc)
            fetchHash = fetchHash * 31 + program.b(pc)
            fetchHash = fetchHash * 31 + program.c(pc)
            fetchHash = fetchHash * 31 + program.lineOf(pc)
        }
        // 表达式/语句采样：512 条均匀步长（映射档触发惰性解码，两路同一深度）
        val exprStep = max(1, (program.exprCount - program.exprBase) / 512)
        var exprHash = 19
        for (i in program.exprBase until program.exprCount step exprStep) {
            exprHash = exprHash * 31 + program.expr(i).hashCode()
        }
        val stmtStep = max(1, (program.statementCount - program.statementBase) / 512)
        var stmtHash = 23
        for (i in program.statementBase until program.statementCount step stmtStep) {
            val s = program.statement(i)
            // 语句池采样必须用内容性字符串口径(`ErbcStreamEquivalenceTest:314-318` 先例):
            // Statement 实现类全为非 data class 且无 hashCode 覆写,s.hashCode() 是
            // identityHashCode,两次独立装载的对象身份必然不同 ⇒ 哈希必然假差
            stmtHash = stmtHash * 31 + (s.javaClass.simpleName + "|" + s.code.name + "|" + s.line + "|" + s.raw).hashCode()
        }
        // 函数表全字段（4,216 条，便宜）
        var fnHash = 29
        for (f in program.functions) {
            fnHash = fnHash * 31 + f.name.hashCode()
            fnHash = fnHash * 31 + f.entryPc
            fnHash = fnHash * 31 + f.arity
            fnHash = fnHash * 31 + f.slotCount
            fnHash = fnHash * 31 + f.paramNames.toList().hashCode()
            fnHash = fnHash * 31 + f.labelNames.toList().hashCode()
            fnHash = fnHash * 31 + f.labelPcs.toList().hashCode()
        }
        // callArgs 全量（键集 + 逐站点实参）
        var caKeysHash = program.callArgs.keys.hashCode()
        var caHash = 31
        for ((pc, args) in program.callArgs) {
            caHash = caHash * 31 + pc.hashCode()
            caHash = caHash * 31 + args.hashCode()
        }
        return RealReadings(
            codeBase = program.codeBase,
            functionBase = program.functionBase,
            statementBase = program.statementBase,
            stringBase = program.stringBase,
            longBase = program.longBase,
            exprBase = program.exprBase,
            instructionBase = program.instructionBase,
            instructionCount = program.instructionCount,
            codeHash = program.code.contentHashCode(),
            linesHash = program.lines.contentHashCode(),
            constantCount = program.constantCount,
            constantHash = run {
                var h = 37
                for (i in program.longBase until program.constantCount) h = h * 31 + program.constant(i).hashCode()
                h
            },
            stringCount = program.stringCount,
            stringHash = run {
                var h = 41
                for (i in program.stringBase until program.stringCount) h = h * 31 + program.string(i).hashCode()
                h
            },
            exprCount = program.exprCount,
            exprSampleHash = exprHash,
            statementCount = program.statementCount,
            statementSampleHash = stmtHash,
            functionCount = program.functions.size,
            functionsHash = fnHash,
            callArgsKeysHash = caKeysHash,
            callArgsHash = caHash,
            fetchSampleHash = fetchHash,
            entryFunction = program.entryFunction,
            globalSlots = program.globalSlots,
            codeResidentBytes = program.codeResidentBytes,
        )
    }

    @Test
    fun `真产物零基线 全读档与映射档读数快照逐位一致`() {
        val path = realErbcOrGate()

        val t0 = System.nanoTime()
        val a0 = allocatedBytes()
        val heap = ProgramLoader.loadFile(path)
        val t1 = System.nanoTime()
        val a1 = allocatedBytes()
        realTiming("full-read load", (t1 - t0) / 1_000_000L, a1 - a0, heap)
        val heapReadings = readingsOf(heap)

        System.gc() // 一次只驻留一个重 Program：全读档读数取完即弃

        val t2 = System.nanoTime()
        val a2 = allocatedBytes()
        val mapped = ProgramLoader.loadMapped(path)
        val t3 = System.nanoTime()
        val a3 = allocatedBytes()
        realTiming("mapped load", (t3 - t2) / 1_000_000L, a3 - a2, mapped)
        val mappedReadings = readingsOf(mapped)

        // 逐字段对拍（assertContentEquals 给出首差字段名，比整体 equals 好定位）
        assertContentEquals(
            listOf(
                heapReadings.codeBase, heapReadings.functionBase, heapReadings.statementBase,
                heapReadings.stringBase, heapReadings.longBase, heapReadings.exprBase,
                heapReadings.instructionBase, heapReadings.instructionCount,
            ),
            listOf(
                mappedReadings.codeBase, mappedReadings.functionBase, mappedReadings.statementBase,
                mappedReadings.stringBase, mappedReadings.longBase, mappedReadings.exprBase,
                mappedReadings.instructionBase, mappedReadings.instructionCount,
            ),
            "六基线 + instructionBase + instructionCount",
        )
        assertEquals(heapReadings.codeHash, mappedReadings.codeHash, "code 全量哈希")
        assertEquals(heapReadings.linesHash, mappedReadings.linesHash, "lines 全量哈希")
        assertEquals(heapReadings.constantCount, mappedReadings.constantCount, "常量池条数")
        assertEquals(heapReadings.constantHash, mappedReadings.constantHash, "常量池全量哈希")
        assertEquals(heapReadings.stringCount, mappedReadings.stringCount, "字符串池条数")
        assertEquals(heapReadings.stringHash, mappedReadings.stringHash, "字符串池全量哈希")
        assertEquals(heapReadings.exprCount, mappedReadings.exprCount, "表达式池条数")
        assertEquals(heapReadings.exprSampleHash, mappedReadings.exprSampleHash, "表达式池采样哈希")
        assertEquals(heapReadings.statementCount, mappedReadings.statementCount, "语句池条数")
        assertEquals(heapReadings.statementSampleHash, mappedReadings.statementSampleHash, "语句池采样哈希")
        assertEquals(heapReadings.functionCount, mappedReadings.functionCount, "函数表条数")
        assertEquals(heapReadings.functionsHash, mappedReadings.functionsHash, "函数表全字段哈希")
        assertEquals(heapReadings.callArgsKeysHash, mappedReadings.callArgsKeysHash, "callArgs 键集哈希")
        assertEquals(heapReadings.callArgsHash, mappedReadings.callArgsHash, "callArgs 全量哈希")
        assertEquals(heapReadings.fetchSampleHash, mappedReadings.fetchSampleHash, "取指采样哈希")
        assertEquals(heapReadings.entryFunction, mappedReadings.entryFunction, "入口函数")
        assertEquals(heapReadings.globalSlots, mappedReadings.globalSlots, "全局槽")
        assertEquals(0L, mappedReadings.codeResidentBytes, "映射档指令流零堆驻留")
    }

    // ==================================================================
    // 判据①（真产物做 base + mini 补丁）：两装载路径 8 访问点逐位一致
    // ==================================================================

    @Test
    fun `真产物做base 带基线mini补丁 两装载路径 8 访问点逐位一致`(@TempDir dir: Path) {
        val realPath = realErbcOrGate()

        // 真产物映射档（轻：指令流零堆驻留、池惰性）拿六计数，与 mini 补丁 Program 同驻安全
        val realMapped = loadMappedReal(realPath)
        assertEquals(0L, realMapped.codeResidentBytes, "真产物映射档指令流必须零堆驻留")
        val counts = countsOf(realMapped)

        // mini 补丁（基线 = 真产物真实计数）：_CALL 指向 base 区段**真函数**（绝对下标中段）
        val base = counts.toProgramBase()
        val callTarget = max(0, counts.functions / 2)
        val patch = segmentProgramCalling(counts, callTarget)
        val bytes = ErbcWriter.encode(patch)
        val f = dir.resolve("patch-on-real-base.erbc")
        Files.write(f, bytes)

        val heap = ErbcReader.decode(bytes, base)
        val mapped = loadMappedReal(f, base)
        assertProgramsIdentical(heap, mapped, "真产物 base + mini 补丁：映射档 vs 全读档")
        // `_CALL` 的 base 区段下标在两路指令流里原样保留（装载期 validate 只放行不改写）
        assertEquals(callTarget, heap.a(patch.instructionBase), "全读档 _CALL a 保留 base 区段下标")
        assertEquals(callTarget, mapped.a(patch.instructionBase), "映射档 _CALL a 保留 base 区段下标")
    }

    // ==================================================================
    // 判据③（真产物 mmap base + 堆内补丁拼接）：全量逐字对拍 + base 零堆驻留
    // ==================================================================

    @Test
    fun `真产物mmap base与堆内补丁拼接 全量逐字对拍 且base零堆驻留`() {
        val realPath = realErbcOrGate()

        val mmapBase = loadMappedReal(realPath, ProgramBase.NONE)
        assertEquals(0L, mmapBase.codeResidentBytes, "映射装载的 base 指令流必须零堆驻留")

        val counts = countsOf(mmapBase)
        val patch = segmentProgram(counts, withCallArgs = true)
        val tailWords = patch.code
        val composite = CompositeCodeSource(mmapBase.codeSource, tailWords)

        assertEquals(mmapBase.codeSource.wordCount + tailWords.size, composite.wordCount, "拼接后总字数")
        assertEquals(mmapBase.codeSource.pageWords, composite.pageWords, "页宽必须继承 base")

        // 全量逐字对拍（~1,920 万字 × 2 绝对位置读；手写循环发现首差即 fail，
        // 不用逐字 assertEquals —— JUnit 断言开销会放大两个数量级）
        val baseWords = mmapBase.codeSource.wordCount
        var mismatches = 0
        var firstBad = -1L
        for (i in 0 until composite.wordCount) {
            val expected = if (i < baseWords) mmapBase.codeSource.word(i) else tailWords[i - baseWords]
            if (composite.word(i) != expected) {
                if (firstBad < 0L) firstBad = i.toLong()
                mismatches++
                if (mismatches > 8) break // 首差定位足够，别刷屏
            }
        }
        assertEquals(0, mismatches, "拼接取指全量逐字对拍失配（首差字 $firstBad，base 区间右端 $baseWords）")

        // 页口径：整页直通区逐页内容相等（真产物 base 19,202,316 字 = 4,688 页 + 268 字尾，
        // `CompositeCodeSource` KDoc 载明的真实形态——跨界页必然存在），跨界页走 fillPage 逐字
        val pageWords = composite.pageWords
        val wholeBasePages = baseWords / pageWords
        assertTrue(wholeBasePages >= 1, "真产物 base 必然多于一页，直通分支必须被踩到")
        for (p in 0 until wholeBasePages) {
            assertContentEquals(mmapBase.codeSource.page(p), composite.page(p), "整页 base 区间页 $p")
        }
        val dst = IntArray(pageWords)
        for (p in wholeBasePages until composite.pageCount) {
            val page = composite.page(p)
            val filled = composite.fillPage(p, dst)
            assertEquals(page.size, filled, "尾区页 $p 的 fillPage 长度")
            for (k in 0 until filled) {
                assertEquals(page[k], dst[k], "尾区页 $p 字 $k 的 fillPage 读数")
            }
        }
        // 禁整段物化：拼接与全量对拍全程之后，base 的指令流堆驻留仍为 0
        assertEquals(0L, mmapBase.codeResidentBytes, "拼接对拍全程 base 指令流不许落堆")
    }

    // ==================================================================
    // 装置（与 `ErbcStreamBaseTest` 同源；真产物门 + 映射档生命周期同构）
    // ==================================================================

    private data class SegmentCounts(
        val codeWords: Int,
        val functions: Int,
        val statements: Int,
        val strings: Int,
        val longs: Int,
        val exprs: Int,
    )

    private fun SegmentCounts.toProgramBase(): ProgramBase = ProgramBase(
        codeBase = codeWords,
        functionBase = functions,
        statementBase = statements,
        stringBase = strings,
        longBase = longs,
        exprBase = exprs,
    )

    private fun countsOf(p: Program): SegmentCounts = SegmentCounts(
        codeWords = p.codeSource.wordCount,
        functions = p.functions.size,
        statements = p.statementCount,
        strings = p.stringCount,
        longs = p.constantCount,
        exprs = p.exprCount,
    )

    /** 与 `ErbcStreamBaseTest.segmentProgram` 同源（带段 7/标签/四池绝对引用）。 */
    private fun segmentProgram(counts: SegmentCounts, withCallArgs: Boolean): Program {
        val b = CodeBuilder(
            codeBase = counts.codeWords,
            statementBase = counts.statements,
            stringBase = counts.strings,
            longBase = counts.longs,
            exprBase = counts.exprs,
            functionBase = counts.functions,
        )
        val main = b.declareFunction("mod_main", b.here(), arity = 1, slotCount = 3)
        val c = b.constant(7L)
        val s = b.string("mod-str")
        val e = b.expr(IntLiteral(1L, 0, 0))
        val st = b.statement(RendStmt(VoidArgument(), 0, "REND"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._EVAL_INT, e, SlotAddr.local(2), 0, 3)
        b.emit(Opcode.DATA, st, 0, SlotAddr.NONE, 4)
        val callPc = b.emit(Opcode._CALL, 0, 0, SlotAddr.NONE, 5)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 6)
        val sub = b.declareFunction("mod_sub", b.here(), arity = 0, slotCount = 1)
        b.patch(callPc, 1, sub)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 7)
        b.declareLabel(main, "TAIL", callPc)
        if (withCallArgs) {
            b.declareCallArgs(callPc, listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "LV", present = true)))
        }
        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(sub, 1)
        return b.build(main)
    }

    /** 与 `ErbcStreamBaseTest.segmentProgramCalling` 同源（`_CALL` 指向任意绝对函数下标）。 */
    private fun segmentProgramCalling(counts: SegmentCounts, callTarget: Int): Program {
        val b = CodeBuilder(
            codeBase = counts.codeWords,
            statementBase = counts.statements,
            stringBase = counts.strings,
            longBase = counts.longs,
            exprBase = counts.exprs,
            functionBase = counts.functions,
        )
        val main = b.declareFunction("mod_hook", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, callTarget, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 2)
        b.setFunctionSlots(main, 1)
        return b.build(main)
    }

    /**
     * 映射档装载（生命周期与 `ProgramLoader.loadMappedHandle` 同构，同 `ErbcStreamBaseTest`）：
     * 只有解出的 Program 确实带惰性池时才把产物通道留给它，其余一切路径关通道。
     * 真产物通道靠 GC 兜底（Windows 只读映射锁，不影响其他用例的共享读）。
     */
    private fun loadMappedReal(path: Path, base: ProgramBase = ProgramBase.NONE): Program {
        val mapped = MappedErbc.open(path)
        var keepChannel = false
        try {
            val program = mapped.toProgram(base = base)
            keepChannel = program.holdsLazyAnyPool
            return program
        } finally {
            if (!keepChannel) mapped.close()
        }
    }

    /** 8 访问点逐位对拍（照抄 `ErbcStreamBaseTest.assertProgramsIdentical`——同一条口径不在两处维护语义）。 */
    private fun assertProgramsIdentical(expected: Program, actual: Program, ctx: String) {
        assertEquals(expected.codeBase, actual.codeBase, "$ctx：codeBase")
        assertEquals(expected.statementBase, actual.statementBase, "$ctx：statementBase")
        assertEquals(expected.stringBase, actual.stringBase, "$ctx：stringBase")
        assertEquals(expected.longBase, actual.longBase, "$ctx：longBase")
        assertEquals(expected.exprBase, actual.exprBase, "$ctx：exprBase")
        assertEquals(expected.functionBase, actual.functionBase, "$ctx：functionBase")
        assertEquals(expected.instructionBase, actual.instructionBase, "$ctx：instructionBase")
        assertEquals(expected.instructionCount, actual.instructionCount, "$ctx：instructionCount（绝对口径）")
        assertContentEquals(expected.code, actual.code, "$ctx：code 本段窗口")
        assertContentEquals(expected.lines, actual.lines, "$ctx：lines 本段窗口")

        for (pc in expected.instructionBase until expected.instructionCount) {
            assertEquals(expected.op(pc), actual.op(pc), "$ctx：pc=$pc 的 op")
            assertEquals(expected.a(pc), actual.a(pc), "$ctx：pc=$pc 的 a")
            assertEquals(expected.b(pc), actual.b(pc), "$ctx：pc=$pc 的 b")
            assertEquals(expected.c(pc), actual.c(pc), "$ctx：pc=$pc 的 c")
            assertEquals(expected.lineOf(pc), actual.lineOf(pc), "$ctx：pc=$pc 的 lineOf")
        }

        assertEquals(expected.constantCount, actual.constantCount, "$ctx：常量池条数")
        for (i in expected.longBase until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$ctx：常量池 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$ctx：字符串池条数")
        for (i in expected.stringBase until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$ctx：字符串池 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$ctx：表达式池条数")
        for (i in expected.exprBase until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$ctx：表达式池 $i")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$ctx：语句池条数")
        for (i in expected.statementBase until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "$ctx：语句池 $i 类")
            assertEquals(e.code, a.code, "$ctx：语句池 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$ctx：语句池 $i 行号")
            assertEquals(e.raw, a.raw, "$ctx：语句池 $i 原文")
        }

        assertEquals(expected.functions.size, actual.functions.size, "$ctx：函数表条数")
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$ctx：函数 $i 名")
            assertEquals(e.entryPc, a.entryPc, "$ctx：函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "$ctx：函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$ctx：函数 ${e.name} slotCount")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "$ctx：函数 ${e.name} 形参名")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$ctx：函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$ctx：函数 ${e.name} 标签 pc")
        }

        assertEquals(expected.callArgs.keys, actual.callArgs.keys, "$ctx：调用点 pc 键集")
        assertEquals(expected.callArgs, actual.callArgs, "$ctx：调用点实参表")
        for (pc in expected.callArgs.keys) {
            assertEquals(expected.callArgsAt(pc), actual.callArgsAt(pc), "$ctx：pc=$pc 的 callArgsAt")
        }

        assertEquals(expected.entryFunction, actual.entryFunction, "$ctx：入口函数（表内序号）")
        assertEquals(expected.globalSlots, actual.globalSlots, "$ctx：全局槽")
    }
}