package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.diag.LiveSetCensus
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.host.ParallelTask
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val MIB = 1024L * 1024L

/** 一个区间的读数（区间 = 相邻两次边界收口之间）。 */
private data class Row(
    val label: String,
    /** 区间结束时的已用堆（`totalMemory - freeMemory`），与 `LoadTrace` 同口径。 */
    val boundary: Long,
    /** 区间内的池峰值之和（JVM 记的高水位，不受采样点位置影响）。 */
    val peak: Long,
    /** 区间内的分配总量（`getThreadAllocatedBytes` 增量）。 */
    val alloc: Long,
    /** 区间结束时的已提交堆（ART `footprint` 的对照位）。 */
    val committed: Long,
    val ms: Long,
)

private val heapPools: List<java.lang.management.MemoryPoolMXBean> by lazy {
    runCatching {
        ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    }.getOrDefault(emptyList())
}

/** HotSpot 的分配计数器；拿不到时相关列全落 -1，不影响装载本身。 */
private val allocBean: com.sun.management.ThreadMXBean? by lazy {
    runCatching { ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean }.getOrNull()
}

/**
 * 进程内所有活线程的分配字节之和。
 *
 * 按线程求和而不是只读装载线程：`begin(TITLE)` 跑在脚本线程上，只读主线程计数器会把
 * 「卡在 ready」那一类（装载链走完、死在脚本里）的分配量报成 0。线程数在装载期只有几条，
 * 每个边界求一次和的成本可以忽略。
 */
private fun allocated(): Long = runCatching {
    val bean = allocBean ?: return -1L
    val ids = bean.allThreadIds
    if (ids.isEmpty()) return -1L
    bean.getThreadAllocatedBytes(ids).sum()
}.getOrDefault(-1L)

private fun usedMiB(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / MIB }

private fun committedMiB(): Long = Runtime.getRuntime().totalMemory() / MIB

private fun peakSumMiB(): Long = runCatching {
    heapPools.sumOf { it.peakUsage?.used ?: 0L } / MIB
}.getOrDefault(-1L)

private val origin = System.nanoTime()
private fun sinceMs(): Long = (System.nanoTime() - origin) / 1_000_000L

private val rows = ArrayList<Row>(64)
private var openLabel = "start"
private var openAlloc = 0L
private var openMs = 0L

/** 本次运行的挡位（`main` 里赋值）；[seamGc] 的旧口径阈值要按它算。 */
private var probeMaxMiB = 0L

/** 交界回收档：`always`（现口径）/ `gated`（2026-10-30 之前的旧口径），也是报告文件名。 */
private var probeSeamMode = "always"

/**
 * 当前已装载的 `Program` / 会话句柄（供 [censusSnapshot] 读 C-CENSUS@1 分项）。
 * 装载段与解码内部的段边界上两者还是 null ⇒ 分项按 census 的 null 口径给 -1（不是 0）。
 */
private var liveProgram: Program? = null
private var liveSession: GameSession? = null

/** 预检估算（`ProgramLoader.estimateHeap` 的返回），用于报告里的 `estimateHeap` 复标节。 */
private var preflightEstimate: ProgramLoader.HeapEstimate? = null

/** 关键边界上的 C-CENSUS@1 分项读数（`标签 -> 行`），写进报告。 */
private val censusRows = ArrayList<Pair<String, String>>(8)

/** 收一个区间、落一行读数，并立刻开下一段（[label] 归刚收口的那段）。 */
private fun boundary(label: String): Row {
    val alloc = allocated()
    val row = Row(
        label = label,
        boundary = usedMiB(),
        peak = peakSumMiB(),
        alloc = if (alloc >= 0 && openAlloc >= 0) (alloc - openAlloc) / MIB else -1L,
        committed = committedMiB(),
        ms = sinceMs() - openMs,
    )
    rows += row
    println(
        "  %-36s 边界=%4d  段内峰值=%4d  段内分配=%5d  已提交=%4d  %6d ms".format(
            row.label, row.boundary, row.peak, row.alloc, row.committed, row.ms,
        ),
    )
    runCatching { heapPools.forEach { it.resetPeakUsage() } }
    openLabel = label
    openAlloc = alloc
    openMs = sinceMs()
    return row
}

/**
 * 在当前边界读一次 `LiveSetCensus`（C-CENSUS@1）分项，落一行 `census@<label> key=value …`。
 *
 * 只读面：`program` 未装载、会话未装配时对应分项按 census 的 null 口径给 **-1**（不是 0）；
 * 图形两段在桌面传 null（headless 无池）恒 -1，**不折 0 充数**。字段名与
 * `devtools/heap_phase_probe.py` 的 `RAW` 行向后对齐（两装置同一把尺），新增项一律向后追加。
 */
private fun censusSnapshot(label: String) {
    val c = runCatching {
        LiveSetCensus.census(liveProgram, liveSession?.store, liveSession?.console, null, null, true)
    }.getOrNull()
    val line = if (c == null) {
        "census@$label 读不到（LiveSetCensus 抛出）"
    } else {
        "census@$label bookedSumBytes=${c.bookedSumBytes()} bookedMissing=${c.bookedMissingFieldCount()}" +
            " materializedStatements=${c.materializedStatements} materializedFunctions=${c.materializedFunctions}" +
            " flatResidentSegments=${c.flatResidentSegments} flatBuiltSegments=${c.flatBuiltSegments}" +
            " codeResidentBytes=${c.codeResidentBytes} lineTableBytes=${c.lineTableResidentBytes}" +
            " callArgIndexBytes=${c.callArgIndexResidentBytes} strPoolChars=${c.stringPoolChars}" +
            " globalIntDeclaredBytes=${c.globalIntBytes} globalIntResidentBytes=${c.globalIntResidentBytes}" +
            " charaCount=${c.charaCount} charaCapacity=${c.charaCapacity}" +
            " charaIntBytes=${c.charaIntBytes} charaStrBytes=${c.charaStrBytes}" +
            " charaReserveBytes=${c.charaReserveBytes}" +
            " gfxResidentPixelBytes=${c.gfxResidentPixelBytes} csvResidentPixelBytes=${c.csvResidentPixelBytes}"
    }
    censusRows += label to line
    println("  $line")
}

private fun dumpReport() {
    println()
    println("### 按段内分配排序（临时胖子清单）")
    rows.filter { it.alloc >= 0 }.sortedByDescending { it.alloc }.take(12).forEach {
        println("  ${it.alloc} MiB  ${it.label}（边界只留 ${it.boundary} MiB）")
    }
    val byPeak = rows.filter { it.peak >= 0 }.sortedByDescending { it.peak }
    println()
    println("### 按段内池峰值排序（真正的装载高水位）")
    byPeak.take(12).forEach { println("  ${it.peak} MiB  ${it.label}（边界读数 ${it.boundary} MiB）") }
    println()
    val boundaryPeak = rows.maxByOrNull { it.boundary }
    val committedPeak = rows.maxByOrNull { it.committed }
    println("  段边界最高 = ${boundaryPeak?.boundary} MiB @ ${boundaryPeak?.label}" +
        "（这就是玩家屏上「峰值 / 卡在」那一句）")
    println("  池峰值最高 = ${byPeak.firstOrNull()?.peak} MiB @ ${byPeak.firstOrNull()?.label}")
    println("  已提交最高 = ${committedPeak?.committed} MiB @ ${committedPeak?.label}" +
        "（ART footprint 的对照位）")
}

private fun failLine(t: Throwable) {    println()
    println("!! 出事区间：$openLabel 之后（上一段收口于 ${rows.lastOrNull()?.label ?: "无"}）")
    println("!! ${t.javaClass.name}: ${t.message?.lineSequence()?.firstOrNull()}")
    val trace = t.stackTrace
    trace.take(20).forEach { println("     at $it") }
    if (trace.size > 20) println("     … 共 ${trace.size} 帧")
    var cause = t.cause
    var depth = 0
    while (cause != null && depth < 3) {
        println("   caused by ${cause.javaClass.name}: ${cause.message?.lineSequence()?.firstOrNull()}")
        cause.stackTrace.take(8).forEach { println("     at $it") }
        cause = cause.cause
        depth++
    }
}

/**
 * 装载链交界回收（`meta` 前 / `session` 前）—— 本探针唯一带「产品行为对照」的动作。
 *
 * `ERATW_PROBE_SEAM=gated` = 2026-10-30 之前的宿主口径（已用堆不到上限的 55% 就不收）；
 * 缺省 `always` = 现口径（交界恒收，见 `LoadGcPolicy.collectAtSeam`）。两档各跑一次，
 * 差值就是这一刀在 512 MiB 挡上真正省下来的余量 —— 桌面量不出来，就没有别的装置能给出这个数。
 */
private fun seamGc(stage: String) {
    val gated = System.getenv("ERATW_PROBE_SEAM")?.trim()?.lowercase() == "gated"
    val used = usedMiB()
    val trigger = maxOf(24L, probeMaxMiB * 55L / 100L)
    if (!gated || used >= trigger) {
        Runtime.getRuntime().gc()
        println("  [seamGc] $stage used=${used}MiB ⇒ 收")
    } else {
        println("  [seamGc] $stage used=${used}MiB < ${trigger}MiB ⇒ 跳过（旧口径）")
    }
    boundary("gc.$stage")
}

/**
 * 设备挡位（真机 `largeHeap` = 512 MiB）装载链探针 —— 给「采样峰值远低于上限却报堆不足」补归因。
 *
 * ## 为什么要这个探针
 *
 * 2026-10-30 玩家两张 release 包失败屏：「峰值 267 MiB / 堆上限 512 MiB / 卡在
 * program.池重灌与下标保真」与「峰值 265 MiB / 卡在 ready」。旧屏那句「卡在 X」的 X 是
 * **采样到峰值的那一段**（`LoadTrace.screenSummary` 在 2026-10-30 之前的形态），**不是失败点**；
 * 而装载埋点只在**段边界**采样 —— 于是这两张图真正说的是：两次采样之间那一段把堆吃穿了，
 * 屏上留下的却是上一段的旧读数。整仓此前没有装置能回答「那一段吃了多少」：桌面闸跑在 3g/6g
 * 挡（见 `core-vm/build.gradle.kts` 各 Test 任务的 `maxHeapSize`），真机侧又从没拿到堆转储。
 *
 * ## 与真机同口径的部分
 *
 * 装配顺序照 `GameHost.loadAndRun` / `assembleSession`：预检前收垃圾 → `loadMapped`
 * （`compat = false`、`flatStatements = false`，设备侧扁平池关着）→ 解码回调里按宿主同样的
 * 判据催 GC → meta → `GameSession` → `begin(TITLE)`。边界读数口径也与 `LoadTrace` 逐字相同
 * （`totalMemory - freeMemory`），所以「边界」这一列可以直接对着玩家屏上那句读。
 *
 * ## 探针多出来的三列（这才是归因）
 *
 * - **段内分配** = `ThreadMXBean.getThreadAllocatedBytes` 的区间增量：这一段**一共** new 了多少
 *   字节，与回收时机无关。它是「临时胖子」清单的直接证据 —— 分配 300 MiB 而边界只涨 30 MiB 的
 *   区间，就是靠 GC 时机救回来的那种区间。
 * - **段内峰值** = 每个区间开始前 `resetPeakUsage()`、结束时读各堆池 `peakUsage` 之和：JVM 自己
 *   记的区间高水位，**不受采样点位置影响**，正是失败屏缺的那个数。G1 下它对短于一个 GC 周期的
 *   尖峰仍会低估（口径同 [HeapMeter.peak]），所以三列并排看，不单独拿任何一列下结论。
 * - **已提交** = `totalMemory()`：JVM 里 = 已向系统要到的堆，是 ART `footprint` 最近似的对照读数。
 *
 * ## 用法
 *
 * ```
 * ERATW_ERBC_DIR=<含 program.erbc 的目录> ERATW_GAMEDIR=<游戏根> \
 * ./gradlew --configure-on-demand :core-vm:deviceTierProbe                    # 缺省 -Xmx512m
 * ERATW_PROBE_XMX=3g ./gradlew --configure-on-demand :core-vm:deviceTierProbe  # 对照挡
 * ```
 *
 * 退出码：0 = 装载链在本挡位走完；1 = 中途抛异常（含 `OutOfMemoryError`），报告里「出事区间」
 * 一行与该区间的分配增量就是归因读数；2 = 缺产物或缺游戏根。
 *
 * **测量用途**：不改任何语义、不写游戏根（`GameSession` 的 `writeRoot` 缺省 = 拒写口径）。
 */
fun main() {
    val erbcDir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?: System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).parent }
    val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    if (erbcDir == null || !Files.isRegularFile(erbcDir.resolve("program.erbc"))) {
        println("[device-tier] 缺产物：设 ERATW_ERBC_DIR 指向含 program.erbc 的目录")
        exitProcess(2)
    }
    if (gameRoot == null || !Files.isDirectory(gameRoot)) {
        println("[device-tier] 缺游戏根：设 ERATW_GAMEDIR")
        exitProcess(2)
    }
    val programFile = erbcDir.resolve("program.erbc")
    val metaFile = erbcDir.resolve("program.meta")
    val maxMiB = Runtime.getRuntime().maxMemory() / MIB
    probeMaxMiB = maxMiB
    probeSeamMode = if (System.getenv("ERATW_PROBE_SEAM")?.trim()?.lowercase() == "gated") "gated" else "always"
    println("[device-tier] erbc=$programFile (${Files.size(programFile) / MIB} MiB)")
    println("[device-tier] gameRoot=$gameRoot")
    println("[device-tier] jvm=${HeapMeter.jvmName()} 收集器=${HeapMeter.gcNames()} 挡位=${maxMiB} MiB")
    println("[device-tier] 口径：设备侧 flatStatements=false / compat=false（与 GameHost.assembleSession 一致）")
    println()
    println("== 逐区间读数（边界=totalMemory-freeMemory 同 LoadTrace；段内峰值=该区间池高水位；段内分配=该区间 new 总量）==")

    runCatching { allocated() } // 先探一次分配计数器，避免首次读数把懒初始化算进第一段
    boundary("start")

    var exitCode = 0
    var session: GameSession? = null
    try {
        // ---- 预检（宿主同一段：先收垃圾再读产物头）----
        Runtime.getRuntime().gc()
        boundary("gc.preflight")
        val estimate = ProgramLoader.estimateHeap(programFile, metaFile)
        preflightEstimate = estimate
        println("  preflight 需=${estimate.requiredMiB}MiB 上限=${estimate.maxMemoryMiB}MiB sufficient=${estimate.sufficient}")
        boundary("preflight")

        // 设备口径：预检放行后就起 `eratw-meta`（`GameHost` 的 `BeginAfterPreflight.begin()`），
        // 侧车解析与下面的 program 解码**并发**。
        val parallelMeta = System.getenv("ERATW_PROBE_META")?.trim()?.lowercase() != "serial"
        val metaTask = if (parallelMeta && Files.isRegularFile(metaFile)) {
            ParallelTask("eratw-meta") { BootMetadata.read(metaFile) }.start()
        } else {
            null
        }
        println("  meta 档 = ${if (metaTask != null) "并行（设备口径）" else "串行（兼容档对照）"}")

        // ---- program 解码：设备口径（扁平池关）+ 宿主同样的催 GC 判据 ----
        val gcTrigger = maxOf(24L, maxMiB * 55L / 100L)
        val mark = LoadStageMark { stage ->
            boundary(stage)
            val used = usedMiB()
            if (
                stage.endsWith("池根表") || stage.endsWith("局部串池") || stage.endsWith("语句记录 8/8")
            ) {
                if (used >= gcTrigger) {
                    Runtime.getRuntime().gc()
                    println("  [gcHint] $stage 已用=${used}MiB ≥ ${gcTrigger}MiB ⇒ 收")
                } else {
                    println("  [gcHint] $stage 已用=${used}MiB < ${gcTrigger}MiB ⇒ 跳过")
                }
                boundary("gc.$stage")
            }
        }
        val program = ProgramLoader.loadMapped(
            programFile,
            compat = false,
            flatStatements = false,
            mark = mark,
        )
        boundary("program")
        liveProgram = program
        censusSnapshot("program")

        // ---- meta（49 MiB 侧车）----
        // 设备口径是**并行**的：`GameHost` 在堆预检放行后就起 `eratw-meta`，与 program 解码
        // 同时跑（省 8 s）。本探针默认照同一条路走 —— 侧车解析的分配峰值与解码峰值**叠加**
        // 还是**错开**，正是 512 MiB 挡位贴边与否的分水岭，串行复现不出来。
        // `ERATW_PROBE_META=serial` 换回串行档做对照（兼容装载档就是串行）。
        // 交界回收：宿主在进 meta 之前先收一次（并行档也收 —— 收的是解码留下的垃圾，
        // 与侧车线程还在不在跑无关）。`ERATW_PROBE_SEAM=gated` 换回「按 55% 阈值决定收不收」
        // 的旧口径，用来量这一刀到底省了多少。
        seamGc("meta")
        val metadata = if (metaTask != null) {
            metaTask.await() ?: error("meta 并行任务返回 null")
        } else {
            BootMetadata.read(metaFile)
        }
        boundary("meta")

        // ---- 会话装配（CSV 总装 + #DIM + ProcessRuntime）----
        seamGc("session")
        val fresh = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
        )
        session = fresh
        boundary("session")
        boundary("ready")
        liveSession = fresh
        censusSnapshot("ready")

        // ---- begin(TITLE)：屏上「卡在 ready」的那一类，出事点在脚本线程 ----
        var titleError: Throwable? = null
        val title = Thread {
            try {
                fresh.runtime.begin(BeginType.TITLE)
            } catch (t: Throwable) {
                titleError = t
            }
        }.apply { name = "device-tier-title"; isDaemon = true }
        title.start()
        // 标题链在语料自己的惰性装载环里可以跑很久（`BOARD` 的「首启两分钟」同一条路），
        // 所以给一个可换挡的等待上限：`ERATW_PROBE_TITLE_MS`，缺省 60 s。这一段量的是
        // 「ready 之后死掉」那一类失败（玩家第二张屏就是 `卡在 ready`）。
        title.join(System.getenv("ERATW_PROBE_TITLE_MS")?.toLongOrNull() ?: 60_000L)
        titleError?.let { throw it }
        boundary("title")
        censusSnapshot("title")
        if (title.isAlive) println("  begin(TITLE) 未到阻塞点（本探针只量装载，不判这个）")
    } catch (t: Throwable) {
        failLine(t)
        exitCode = 1
    } finally {
        boundary("end:$openLabel")
        censusSnapshot("end:$openLabel")
        println()
        println("### 全区间原始读数")
        rows.forEachIndexed { i, r ->
            println("  #${i + 1} ${r.label}: 边界=${r.boundary}MiB 段内峰值=${r.peak}MiB 段内分配=${r.alloc}MiB 已提交=${r.committed}MiB ${r.ms}ms")
        }
        dumpReport()
        writeReport(maxMiB, programFile, gameRoot, exitCode)
        runCatching { session?.close() }
        println()
        println("[device-tier] 结束 exit=$exitCode")
    }
    exitProcess(exitCode)
}

/**
 * 把逐区间读数落成 UTF-8 报告（Windows 控制台是 GBK，中文段名在 stdout 里会花掉；
 * 归因读数要能贴进 BOARD，所以文件才是权威出口）。与 `perfBaseline` / `bootSmoke` 同惯例。
 */
private fun writeReport(maxMiB: Long, erbc: Path, gameRoot: Path, exitCode: Int) {
    val out = Path.of("build", "device-tier-report-$probeSeamMode.md")
    val sb = StringBuilder()
    sb.appendLine("# 设备挡位装载探针（真产物 + 设备口径装配）")
    sb.appendLine()
    sb.appendLine("- 产物：`$erbc`")
    sb.appendLine("- 游戏根：`$gameRoot`")
    sb.appendLine("- JVM：${HeapMeter.jvmName()}，收集器 ${HeapMeter.gcNames()}，挡位 **$maxMiB MiB**")
    sb.appendLine("- 口径：`flatStatements = false` / `compat = false`（与 `GameHost.assembleSession` 一致）；" +
        "meta 档 = ${if (System.getenv("ERATW_PROBE_META")?.trim()?.lowercase() == "serial") "串行" else "并行（设备口径）"}；" +
        "交界回收 = **$probeSeamMode**（`always` = 现口径恒收，`gated` = 旧口径按 55% 阈值）")
    sb.appendLine("- 结果：exit=$exitCode（0 = 本挡位装得下；1 = 中途抛异常，见下）")
    sb.appendLine()
    sb.appendLine("| # | 区间 | 边界 MiB | 段内池峰值 MiB | 段内分配 MiB | 已提交 MiB | 耗时 ms |")
    sb.appendLine("|---:|---|---:|---:|---:|---:|---:|")
    rows.forEachIndexed { i, r ->
        sb.appendLine("| ${i + 1} | ${r.label} | ${r.boundary} | ${r.peak} | ${r.alloc} | ${r.committed} | ${r.ms} |")
    }

    // ---- C-CENSUS@1 分项（关键边界各一行；-1 = 桌面取不到/未装配，不折 0）----
    if (censusRows.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("## C-CENSUS@1 分项（`LiveSetCensus` 只读面；与 `devtools/heap_phase_probe.py` 同尺）")
        sb.appendLine()
        sb.appendLine("- 口径：估算账面，非 dominator tree；`-1` = 桌面取不到（图形两段传 null）或该相位结构未装配；" +
            "装载段内部（`program` 之前）program 未建 ⇒ program 面 -1。")
        sb.appendLine()
        sb.appendLine("```")
        censusRows.forEach { (_, line) -> sb.appendLine(line) }
        sb.appendLine("```")
    }

    // ---- estimateHeap 复标（D1）----
    val artifactBytes = runCatching { Files.size(erbc) }.getOrDefault(-1L)
    val artifactMiB = if (artifactBytes < 0) -1L else artifactBytes / MIB
    val est = preflightEstimate
    val peakRow = rows.filter { it.peak >= 0 }.maxByOrNull { it.peak }
    val boundaryRow = rows.maxByOrNull { it.boundary }
    sb.appendLine()
    sb.appendLine("## `estimateHeap` 复标（D1：1.45×/0.15× 是否成立）")
    sb.appendLine()
    sb.appendLine("- 产物 `$erbc` = **$artifactMiB MiB**（$artifactBytes B）；" +
        "预检 `requiredBytes=${est?.requiredBytes ?: -1}`（${est?.requiredMiB ?: -1} MiB）、" +
        "`sufficient=${est?.sufficient ?: "?"}`（只作判别，**不改产品系数**）。")
    val requiredRatio = if (est != null && artifactBytes > 0) {
        "%.3f".format(est.requiredBytes.toDouble() / artifactBytes.toDouble())
    } else {
        "-"
    }
    sb.appendLine("- 预检口径：`decoded = 产物 × 29/20`、`meta = 侧车 × 3/20` ⇒ " +
        "本产物 required/artifact = **$requiredRatio ×**" +
        "（与 `ProgramLoader.kt` 注释的 1.45× 口径同源）。")
    if (peakRow != null && artifactBytes > 0) {
        sb.appendLine("- **本装置实测**（HotSpot+G1、`--xmx 512m`、`flatStatements=false`、`compat=false`，非真机 ART）：" +
            "装载链段内池峰值最高 **${peakRow.peak} MiB** @ `${peakRow.label}`" +
            " = **%.3f × 产物**；段边界最高 ${boundaryRow?.boundary ?: -1} MiB @ `${boundaryRow?.label ?: "-"}`。"
                .format(peakRow.peak.toDouble() / artifactMiB.toDouble()))
    }
    // D1 复标的目标比值 = 装载期峰值(program.*) ÷ 产物字节（不含会话/标题这两段装后分配）。
    val loadPeak = rows.filter { it.label.startsWith("program") && it.peak >= 0 }.maxByOrNull { it.peak }
    if (loadPeak != null && artifactBytes > 0) {
        sb.appendLine("- **装载段（`program.*`）**内池峰值最高 = **${loadPeak.peak} MiB** @ `${loadPeak.label}`" +
            " = **%.3f × 产物**（D1 复标的目标比值）。"
                .format(loadPeak.peak.toDouble() / artifactMiB.toDouble()))
    }
    sb.appendLine("- **结论方向**：桌面 HotSpot 上装载期 live 峰值远低于 `1.45 ×` 产物（1.45× 是「扁平池压扁即弃」档标定）；" +
        "本装置跑的是**设备口径 `flatStatements=false`** ⇒ 不建扁平池，故桌面口径下 1.45× 明显**高估**。" +
        "**ART 上的真实比值未证** ⇒ `TODO(verify)`（D1，需真机 `load.peak ÷ 产物字节`，本机无设备）。")
    sb.appendLine()

    val body = sb.toString()
    runCatching {
        Files.createDirectories(out.parent)
        Files.write(out, body.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        println("[device-tier] 报告写入 $out")
    }.onFailure { println("[device-tier] 报告写入失败：${it.message}") }
    // C-PROBE@1 的读数落点：`ERATW_PROBE_OUT` 给目录/文件时另写一份到 `.local/heap-phase/**`（可复读）。
    System.getenv("ERATW_PROBE_OUT")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        val target = Path.of(raw).let { if (Files.isDirectory(it)) it.resolve(out.fileName) else it }
        runCatching {
            Files.createDirectories(target.toAbsolutePath().parent)
            Files.write(target, body.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            println("[device-tier] 读数副本写入 $target")
        }.onFailure { println("[device-tier] 读数副本写入失败：${it.message}") }
    }
}
