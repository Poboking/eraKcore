package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.NoSaveSystemOps
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.StateStep
import com.eratw.port.core.proc.StepOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// ===========================================================================
// OBS-PHASETITLE 相位阶梯探针：把归因从「装载 + 装配完成」往前推到运行期相位。
//
// 为什么开这一刀：上一路（已合 main）的 `HeapRootAttributionProbe` 把同一份真产物在装配完成
// 钉在 live ≈ 69 MiB，同时**推翻**了「桌面 uipreview 量到的 280 MiB 是装载常驻」—— 280 MiB
// 是标题页**之后**那一相位的稳态。R7 瘦身波的靶子因此是「运行期相位谁在长」，而那张表只到装配完。
// 本探针把同一条逐根口径往前推，逐相位差分：
//   P0 装配完成（对表既有的 ~69 MiB；复现不到同一量级就是本装置有问题，不是产品有问题）
//   P1 begin(TITLE) 跑完、标题页第一次进入等待点
//   P2 走一步进游戏（开局链第一段：撞 STEP_LIMIT / CODEEE / BLOCKED 就如实停在哪儿记哪儿）
//   P3 同一条链反复「等待 → 提交 → 推进」到第 N 页（N 的理由见 [DEFAULT_PAGES]）
//   P4 回标题重画一页（`gotoTitle()` 之后账回不回得来 —— 「有账没收」的直接判据）
//
// 四个口径**不许混为实测**（与 `HeapRootAttributionProbe` 同源）：
//   枚举-精确公式 / 枚举-遍历模型 / jcmd直方图 / GC差。
// 恒等式逐相位各自闭合：入账合计 + 未归类 = live 总额（GC差，已扣 S0），容差 ±10 MiB；
// 任一相位 OVERCOUNT（入账越 live + 容差）⇒ exit 1；BIG_GAP 只警告、未归类逐 MiB 可见。
//
// 刻意**不做**的：
// - 不碰产品代码（整份装置只在测试源集）；不破 ADR-003 的 VM 单线程 —— 脚本的 begin/resumeSystem/
//   gotoTitle 全部只在**一条**自建线程上执行，主线程只做测量与按等待点提交输入（与生产
//   `GameHost` + UI 的分工同形）；
// - 不加 :render / :tools:uipreview 依赖（依赖方向反了）⇒「渲染侧缓存」这一面在本装置里是
//   **声明不入账的 0**，与桌面 280 MiB 的差额按「差额」报，不冒充实测；
// - 不调任何回收/降档动作（`releaseImagePixelsIfHeapHigh` / `evictDeferredPixels` 一次都不碰）：
//   本装置量的是「产品自己走到哪一步占多少」，不是「回收后剩多少」。
// ===========================================================================

private const val MIB = 1024L * 1024L

/**
 * P3 的翻页次数缺省值与取值理由：每页都要连打 GC 取稳态 + 走完开局链的一段，
 * 8 页在 15 min 墙钟预算内跑得完；「单调不回吐」的判据（[PhaseLadderMath.isMonotoneGrowth]）
 * 最少要 3 个点，8 页才留得出一段趋势、看得转弯。可用 `ERATW_PROBE_PAGES` 换挡。
 */
private const val DEFAULT_PAGES = 8

/** 「单调不回吐」的本装置每步容差：2 MiB（`HeapMeter` 单点残差在 MiB 以下量级，留 3 倍余量）。 */
private const val PAGE_MONOTONE_TOL = 2L * MIB

/** 一个翻页点的读数（P3 逐页序列用它；只跑得起廉价计数器，不做对象图遍历）。 */
internal class PagePoint(
    val index: Int,
    val label: String,
    val liveBytes: Long,
    val consoleLines: Int,
    val materializedStmts: Int,
    val gfxEntries: Int,
    val gfxPixelBytes: Long,
    val steps: Long,
    val note: String,
    /** 变量池子块（[VarPoolFacets] 的公式口径，互斥、可逐页差分）—— 台阶归因就钉在这一列上。 */
    val varPool: Map<String, Long> = emptyMap(),
    val varPoolCounts: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "page#$index $label live=${liveBytes / MIB}MiB lines=$consoleLines matStmt=$materializedStmts " +
            "gfx=$gfxEntries/${gfxPixelBytes / MIB}MiB steps=$steps " +
            "角色=${varPoolCounts["角色表.count/容量"] ?: "-"} 冗余=${(varPool["10.1 角色表·整数列 long"] ?: 0L) / MIB}MiB " +
            "note=${note.ifEmpty { "-" }}"
}

/**
 * 阶梯算术（纯函数，进 `PhaseHeapLadderTest` 定向单测；不碰产物、不碰堆）。
 *
 * 把这几条从探针主体剥出来的理由：判读要的是**可断言的口径**（单调不回吐怎么判、每页净增怎么算、
 * 边际尺怎么反推），不是探针里一次性的 println。
 */
internal object PhaseLadderMath {

    /** 逐面差分：同一分面键在两相位的入账差（缺键按 0；正 = 变长）。两侧消费序同源，键可直接对齐。 */
    fun facetDiff(prev: Map<String, Long>, cur: Map<String, Long>): Map<String, Long> {
        val keys = LinkedHashSet<String>()
        keys.addAll(prev.keys)
        keys.addAll(cur.keys)
        val out = LinkedHashMap<String, Long>()
        for (k in keys) out[k] = (cur[k] ?: 0L) - (prev[k] ?: 0L)
        return out
    }

    /** 边际尺反推条数：`bytes / 每条 B`；尺 ≤ 0 时给 0（无定义不硬造数）。 */
    fun stmtCountFromBytes(bytes: Long, marginalBytesPerStmt: Long): Long =
        if (marginalBytesPerStmt <= 0L) 0L else bytes / marginalBytesPerStmt

    /** 每单位（页/回合）净增：末态减初态再除单位数；单位数 ≤ 0 ⇒ 0。 */
    fun perUnitGrowth(firstBytes: Long, lastBytes: Long, units: Int): Long =
        if (units <= 0) 0L else (lastBytes - firstBytes) / units

    /**
     * 「单调不回吐」判据：每一步增量都 ≥ -tolBytes（允许 GC 噪声级的小回落）**且**累计增量 > tolBytes。
     *
     * 为什么带容差：`HeapMeter.settled()` 是连打 5 轮 `System.gc()` 取最小，残差本来就有 MiB 级
     * （`RootLedger` 的 ±10 MiB 总账容差同源）；拿「逐点严格大于」当判据会把噪声读成回吐。
     * 点数 < 3 一律 false：两点定不了「单调」，只定得了一次差。
     */
    fun isMonotoneGrowth(series: List<Long>, tolBytes: Long = PAGE_MONOTONE_TOL): Boolean {
        if (series.size < 3) return false
        var sawGrowth = false
        for (i in 1 until series.size) {
            val d = series[i] - series[i - 1]
            if (d < -tolBytes) return false
            if (d > tolBytes) sawGrowth = true
        }
        return sawGrowth && series.last() - series.first() > tolBytes
    }

    /** 逐点增量表（第 0 点无前驱 ⇒ 补 0）。 */
    fun stepDeltas(series: List<Long>): List<Long> =
        if (series.isEmpty()) emptyList() else series.mapIndexed { i, v -> if (i == 0) 0L else v - series[i - 1] }

    /** 总额前三的面（「最大的三块各归谁」用）。 */
    fun topSurfaces(totals: Map<String, Long>, n: Int = 3): List<Pair<String, Long>> =
        totals.entries.sortedByDescending { it.value }.take(n.coerceAtLeast(0)).map { it.key to it.value }

    /** 增量前三的面（跨相位差分口径，比总额更直接指出「谁在长」）。 */
    fun topDiffs(diff: Map<String, Long>, n: Int = 3): List<Pair<String, Long>> =
        diff.entries.sortedByDescending { it.value }.take(n.coerceAtLeast(0)).map { it.key to it.value }
}

/**
 * 正式入口（`:core-vm:phaseHeapLadder`）。
 *
 * 参数（全走环境变量，与 `heapRootAttribution` / `deviceTierProbe` 同名同义，不发明第三套）：
 * - `ERATW_ERBC_DIR`（或 `ERATW_ERBC`）+ `ERATW_GAMEDIR`：缺任一 exit 2；
 * - `-Pprobe.xmx`：JVM 挡位，缺省 3g；设备挡位复刻 = `-Pprobe.xmx=512m` 配 `ERATW_PROBE_WALK=0`；
 * - `ERATW_PROBE_WALK=0` 关遍历（只出逐相位 live + 计数器）/ `ERATW_PROBE_JCMD=0` 关直方图 /
 *   `ERATW_PERF_ROUNDS=<n>` GC 轮数 / `ERATW_PROBE_FLAT=1` 开扁平池对照；
 * - `ERATW_PROBE_PAGES=<n>` P3 页数（缺省 [DEFAULT_PAGES]）/ `ERATW_PROBE_INPUTS=0,0` 开局脚本
 *   （口径同 `BootPlayChain`）/ `ERATW_PROBE_AWAIT_MS`（单等待点上限，缺省 240 s）/
 *   `ERATW_PROBE_TURN_MS`（单次翻页上限，缺省 300 s）/ `ERATW_PROBE_WALL_MS`（脚本总墙钟预算，缺省 900 s）；
 * - `ERATW_PROBE_OUT=<路径>`：报告落点，缺省 `build/phase-heap-ladder.md`（build 目录，不入库）。
 */
fun main() {
    val erbcDir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?: System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).parent }
    if (erbcDir == null || !Files.isRegularFile(erbcDir.resolve("program.erbc"))) {
        println("[ladder] 缺产物：设 ERATW_ERBC_DIR 指向含 program.erbc 的目录（本探针不回落构建缓存）")
        exitProcess(2)
    }
    val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    if (gameRoot == null || !Files.isDirectory(gameRoot)) {
        println("[ladder] 缺游戏根：设 ERATW_GAMEDIR")
        exitProcess(2)
    }
    val probe = PhaseHeapLadderProbe()
    try {
        exitProcess(probe.run(erbcDir, gameRoot))
    } catch (oome: OutOfMemoryError) {
        println("[ladder] OOM：本挡位养不起相位遍历 —— 换 -Pprobe.xmx=3g，或 ERATW_PROBE_WALK=0 只要逐相位 live")
        exitProcess(1)
    }
}

/** 探针主体（只在测试源集存在；产品面 core-vm main 一个字节都不动）。 */
internal class PhaseHeapLadderProbe {

    private val sizer = SizeModel(SizeModel.detectRefWidth())
    private val report = StringBuilder()
    private val details = StringBuilder()
    private val phases = ArrayList<PhaseReading>()
    private val pages = ArrayList<PagePoint>()

    /** 逐**遍历相位**的变量池子块快照（与 [phases] 同序同标签；来源=公式口径，不入账）。 */
    private val varPoolPhases = ArrayList<Pair<String, VarPoolCounters>>()
    private val jcmdRows = ArrayList<Pair<String, String>>()
    private var overcountPhase: String? = null
    private var s0Bytes = 0L

    private fun line(s: String = "") {
        println(s)
        report.appendLine(s)
    }

    /** 逐相位明细另存一段，落在报告尾部（§7）：主表在前，明细不插进来打乱阅读序。 */
    private fun detail(s: String = "") {
        if (s.isNotEmpty()) println(s)
        details.appendLine(s)
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / MIB
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    private fun signed(v: Long): String = (if (v >= 0) "+" else "") + mib(v)

    private fun group(n: Number): String {
        val s = n.toLong().toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    fun run(erbcDir: Path, gameRoot: Path): Int {
        val walkOn = System.getenv("ERATW_PROBE_WALK")?.trim() != "0"
        val flatOn = System.getenv("ERATW_PROBE_FLAT")?.trim() == "1"
        val pagesN = System.getenv("ERATW_PROBE_PAGES")?.trim()?.toIntOrNull()?.coerceIn(3, 64) ?: DEFAULT_PAGES
        val awaitMs = System.getenv("ERATW_PROBE_AWAIT_MS")?.trim()?.toLongOrNull()?.coerceIn(1_000L, 1_800_000L)
            ?: 240_000L
        val wallMs = System.getenv("ERATW_PROBE_WALL_MS")?.trim()?.toLongOrNull()?.coerceIn(30_000L, 7_200_000L)
            ?: 900_000L
        val programFile = erbcDir.resolve("program.erbc")
        val metaFile = erbcDir.resolve("program.meta")
        if (!Files.isRegularFile(metaFile)) {
            println("[ladder] 缺 program.meta：侧车与产物必须同源")
            return 2
        }

        line("# OBS-PHASETITLE 相位阶梯：装配完成 → 标题页 → 走一步 → 反复翻页（逐相位差分）")
        line()
        line("- 产物：`$erbcDir`（erbc ${group(Files.size(programFile))} B / meta ${group(Files.size(metaFile))} B）")
        line("- 游戏根：`$gameRoot`")
        line("- JVM：${HeapMeter.jvmName()} 挡位=${mib(HeapMeter.max())} MiB 收集器=${HeapMeter.gcNames()} GC轮=${HeapMeter.rounds}")
        line("- 装载口径：`compat=false`、`flatStatements=$flatOn`（缺省 = 设备侧关扁平池，与 `GameHost`/" +
            "`deviceTierProbe`/`heapRootAttribution` 一致）；`systemOps=NoSaveSystemOps`（**不碰盘**）、" +
            "`writeRoot=null`、`randSeed=20260226`、`consoleWidth=199`、meta 串行档 ⇒ P0 与上一路同口径可对表")
        line("- 帧闸：`frameGateEnabled=true`（生产缺省）。本装置 headless、没人读帧，但帧闸只冻结「发布」" +
            "不阻塞脚本线程（`LineBufferConsoleSink.holdFrame`）⇒ 不改相位形状")
        line("- 遍历档：walk=$walkOn（关档只出逐相位 live 与计数器，不分面）")
        line("- 驱动档：P3 到第 $pagesN 页、单等待点上限 ${awaitMs} ms、脚本墙钟预算 ${wallMs} ms、" +
            "输入脚本=`${System.getenv("ERATW_PROBE_INPUTS")?.takeIf { it.isNotBlank() } ?: "0,0（缺省同 BootPlayChain）"}`")
        line("- 相位定义：P0 装配完成 / P1 begin(TITLE) 到第一个等待点 / P2 提交一次后的下一个等待点" +
            "（开局链第一段）/ P3 同一条链走到第 $pagesN 页 / P4 回标题重画一页")
        line()

        s0Bytes = HeapMeter.settled()
        println("[ladder] S0 基线（探针脚手架之外）= ${mib(s0Bytes)} MiB")

        val tLoad = System.nanoTime()
        val program = ProgramLoader.loadMapped(programFile, compat = false, flatStatements = flatOn)
        println("[ladder] program 装载 ${(System.nanoTime() - tLoad) / 1_000_000} ms")
        var metadata: BootMetadata? = BootMetadata.read(metaFile)
        seamGc("meta→session")
        val tSess = System.nanoTime()
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata!!,
            consoleWidth = 199,
            randSeed = 20260226L,
            systemOps = NoSaveSystemOps,
        )
        // 与上一路逐字同口径：`GameSession` 不持侧车本体，装配完它就没有读者 —— 本地引用必须显式置空。
        // 解释器帧的 local slot 在「最后一次使用」之后仍持引用（JIT 之前不会清槽），留着就是
        // 49 MiB 侧车被装置的栈钉住：2026-11-01 首跑 P0 因此比基准高 +21.5 MiB。
        metadata = null
        println("[ladder] session 装配 ${(System.nanoTime() - tSess) / 1_000_000} ms")

        val facets = PhaseFacets(sizer)
        val exitCode = try {
            drivePhases(program, session, facets, walkOn, pagesN, awaitMs, wallMs)
        } catch (t: Throwable) {
            line()
            line("!! 装置出事：${t.javaClass.name}: ${t.message?.lineSequence()?.firstOrNull()}")
            t.stackTrace.take(12).forEach { line("     at $it") }
            1
        } finally {
            writeReport(walkOn, pagesN)
            runCatching { session.close() }
        }
        val code = if (overcountPhase != null && exitCode == 0) 1 else exitCode
        println("[ladder] 结束 exit=$code${overcountPhase?.let { "（$it 判定 OVERCOUNT：有面重复计账）" } ?: ""}")
        return code
    }

    /** 设备宿主同款交界回收（`always` 口径）：进 session 前恒收；稳态 live 与收的时机无关。 */
    private fun seamGc(stage: String) {
        val used = HeapMeter.used() / MIB
        val max = HeapMeter.max() / MIB
        System.gc()
        println("  [seamGc] $stage used=${used}MiB / max=${max}MiB ⇒ 收")
    }

    private fun drivePhases(
        program: Program,
        session: GameSession,
        facets: PhaseFacets,
        walkOn: Boolean,
        pagesN: Int,
        awaitMs: Long,
        wallMs: Long,
    ): Int {
        val wallStart = System.nanoTime()
        val pump = VmPump(session)

        val p0 = readPhase("P0 装配完成", program, session, facets, walkOn)
        line()
        line("- **P0 对表**：上一路 `heapRootAttribution` 同产物同口径给 live=69.3～69.4 MiB" +
            "（3g 档 walk=true：入账 63.6 + 未归类 5.8；512m 档 walk=0：69.3；jcmd 独立读 67.6）。" +
            "本装置 P0=${mib(p0.liveBytes)} MiB，与基准差 ${signed(p0.liveBytes - 69L * MIB)} MiB" +
            "（±10 MiB 容差内即算复现；复现不了就是本装置有问题，不是产品有问题）。")

        pump.start()
        var stopped = ""
        val p1ok = pump.awaitAwaiting(awaitMs)
        val p1 = readPhase(
            if (p1ok) "P1 标题页第一个等待点" else "P1 标题页未到等待点(${pump.stopReason})",
            program, session, facets, walkOn,
        )
        if (!p1ok) stopped = pump.stopReason

        var p2 = p1
        if (stopped.isEmpty()) {
            val note = pump.turnPage()
            p2 = readPhase(
                if (note.isEmpty()) "P2 走一步进游戏(${pump.pageLabel})" else "P2 走一步中断($note)",
                program, session, facets, walkOn,
            )
            if (note.isNotEmpty()) stopped = note
        }

        // ---------- P3：同一条链走到第 pagesN 页（逐页只出 live + 计数器；末页再遍历一次） ----------
        var p3last: PhaseReading? = null
        var i = 2
        while (stopped.isEmpty() && i < pagesN) {
            if ((System.nanoTime() - wallStart) / 1_000_000 > wallMs) { stopped = "WALL_BUDGET ${wallMs}ms"; break }
            val note = pump.turnPage()
            i++
            pages += pagePoint(program, session, "第 $i 页", note)
            println("[ladder] ${pages.last()}")
            if (note.isNotEmpty()) { stopped = note; break }
            if (walkOn && i == pagesN - 1) {
                p3last = readPhase("P3 末页(${pages.last().label}, ${pump.pageLabel})", program, session, facets, walk = true)
            }
        }
        if (stopped.isNotEmpty()) line("- P3 实到第 ${pages.size + 2} 页即停：$stopped（能走多远算多远，后续读数不硬造）")

        // ---------- P4：回标题重画一页（账回不回得来） ----------
        var p4: PhaseReading? = null
        if (stopped.isEmpty()) {
            val back = pump.requestHoldThenGotoTitle(awaitMs, awaitMs)
            if (back.isEmpty()) {
                p4 = readPhase("P4 回标题重画一页(生产循环顶)", program, session, facets, walkOn)
            } else {
                line("- P4 的**生产路径**拿不到（$back）：移植侧的等待是阻塞式，一次 run 会跨过几十条输入" +
                    "（`BootPlayChain` 类文档同一条事实），开局链里循环顶根本回不来")
                val viaUnwind = pump.unwindThenGotoTitle(awaitMs)
                p4 = if (viaUnwind.isEmpty()) {
                    readPhase("P4 回标题复位后(经 CLOSED 收口，非生产路径)", program, session, facets, walkOn)
                } else {
                    line("- P4 的收口路径也没拿到：$viaUnwind")
                    null
                }
            }
        } else {
            line("- P4 跳过：P2/P3 已终局（$stopped），回标题通道在没有循环顶时不可调")
        }
        line("- 泵的动作尾巴（最近 24 步）：${pump.trailText()}")

        emitLiveTable()
        emitDiffTable()
        emitVarPoolPhaseTable()
        emitVarPoolPageTable()
        emitPageSeries(p2)
        emitJcmdSection()
        emitJudgement(p0, p1, p2, p3last, p4, pagesN, stopped)
        return 0
    }

    /**
     * 一相位读数：先按 GC差 取 live，再（可选）跑逐根遍历。
     *
     * 顺序是刻意的：live 必须在**遍历之前**取 —— 访问集本身是几十 MiB 级活物，
     * 先遍历再收会把装置自己的账算进相位（口径与 `HeapRootAttributionProbe` 的对账行同源）。
     */
    private fun readPhase(
        label: String,
        program: Program,
        session: GameSession,
        facets: PhaseFacets,
        walkOn: Boolean = true,
        walk: Boolean = walkOn,
    ): PhaseReading {
        val live = HeapMeter.settled() - s0Bytes
        // jcmd 与 GC差**同刻并排**取（jcmd 自己会先做一次 full GC）：两口径分歧时不靠改判据抹平，
        // 只并排报 —— 2026-11-01 首跑 P1 就是这样抓到 GC差 单口径少读 14 MiB（jcmd 114.2 vs GC差 99.9，
        // 而遍历入账 116.4 站在 jcmd 那一边）。
        val j = if (System.getenv("ERATW_PROBE_JCMD")?.trim() != "0") jcmdHistogram() else null
        val counters = countersOf(session, program)
        val reading = if (walk) {
            facets.attribute(label, live, program, session, counters)
        } else {
            val ledger = RootLedger(live)
            ledger.add("(walk=0)", "只出 live 总额与计数器；各面读数未采", "", 0L, "档位声明",
                note = "设备挡位复刻：-Pprobe.xmx=512m + ERATW_PROBE_WALK=0")
            PhaseReading(label, live, emptyMap(), 0L, live, RootLedger.Verdict.BIG_GAP, ledger, counters, 0L)
        }
        reading.jcmdLiveBytes = (j?.first ?: -1L) - s0Bytes
        if (reading.verdict == RootLedger.Verdict.OVERCOUNT && reading.jcmdLiveBytes > 0 &&
            reading.counted <= reading.jcmdLiveBytes + 10 * MIB
        ) {
            reading.verdictOverride = "SPLIT"
            line("- 口径分歧（$label）：入账 ${mib(reading.counted)} MiB 越过 GC差 live ${mib(live)} MiB，" +
                "但同一时刻 jcmd 权威 live=${mib(reading.jcmdLiveBytes)} MiB 站在入账这一边 ⇒ " +
                "判 **SPLIT**（GC差 少读 ${mib(reading.jcmdLiveBytes - live)} MiB），不判重复计账、不计 exit 1")
        } else if (reading.verdict == RootLedger.Verdict.OVERCOUNT) {
            overcountPhase = label
        }
        phases += reading
        runCatching { varPoolPhases += label to VarPoolFacets(sizer).snapshot(session.store, session.store.characters) }
        println("[ladder] 相位 ${reading.label}：live=${mib(live)} MiB 入账=${mib(reading.counted)} " +
            "未归类=${mib(reading.unclassified)} 判定=${reading.verdictOverride.ifEmpty { reading.verdict.toString() }} " +
            "jcmd=${if (reading.jcmdLiveBytes > 0) mib(reading.jcmdLiveBytes) else "-"} 遍历=${reading.walkMs}ms")
        line()
        detail("### 相位 ${reading.label}：live=${mib(live)} MiB（入账 ${mib(reading.counted)} / " +
            "未归类 ${mib(reading.unclassified)}，判定 ${reading.verdictOverride.ifEmpty { reading.verdict.toString() }}，" +
            "jcmd ${if (reading.jcmdLiveBytes > 0) mib(reading.jcmdLiveBytes) else "-"} MiB，遍历 ${reading.walkMs} ms）")
        detail()
        detail("| 面 | 项 | 计数 | MiB | 来源 | 入账 | 备注 |")
        detail("|---|---|---|---:|---|---|---|")
        for (r in reading.ledger.rows) {
            detail("| ${r.surface} | ${r.item} | ${r.count} | ${if (r.bytes < 0) "(堆外)" else mib(r.bytes)} | " +
                "${r.source} | ${if (r.counted) "是" else "否"} | ${r.note} |")
        }
        detail("| **入账面合计** | | | **${mib(reading.counted)}** | 枚举(公式+遍历) | | |")
        detail("| **未归类（兜底行，不抹平）** | live 总额 - 入账合计 | | **${mib(reading.unclassified)}** | GC差 | 是 | |")
        detail("| **live 总额** | 本相位、GC ${HeapMeter.rounds} 轮取最小（已扣 S0） | | **${mib(live)}** | GC差 | | |")
        if (System.getenv("ERATW_PROBE_JCMD")?.trim() != "0") {
            jcmdRows += label to if (j == null) {
                "jcmd 不可用/超时（不硬造读数）"
            } else {
                val fam = j.second.filter {
                    it.third.startsWith("com.eratw.port.core.expr.Form") || it.third == "com.eratw.port.core.lexer.StrForm"
                }.sumOf { it.second }
                val prim = listOf("[B", "[I", "[Ljava.lang.Object;", "java.lang.String")
                    .mapNotNull { name -> j.second.firstOrNull { it.third == name }?.let { name to it.second } }
                    .joinToString(" / ") { "${it.first}=${mib(it.second)}MiB" }
                val top = j.second.sortedByDescending { it.second }.take(6)
                    .joinToString(" / ") { "${it.third} x${it.first}=${mib(it.second)}MiB" }
                "live 总量=${mib(j.first)} MiB；Form 族=${mib(fam)} MiB；基本数组/串：$prim；top6：$top"
            }
        }
        return reading
    }

    private fun countersOf(session: GameSession, program: Program): Map<String, String> {
        val st = runCatching { session.store.aux.peek<GraphicsPool>(GRAPHICS_SLOT)?.stats() }.getOrNull()
        return linkedMapOf(
            "控制台行数" to runCatching { session.console.displayLineCount.toString() }.getOrDefault("?"),
            "maxLines" to session.console.maxLines.toString(),
            "已物化语句" to program.materializedStatementCount.toString(),
            "G_ID条目" to (st?.entries ?: -1).toString(),
            "G_ID像素MiB" to if (st == null) "-" else mib(st.pixelBytes),
            "已消费输入seq" to session.inputSink.lastConsumedSeq.toString(),
            // 变量池子块的三个决定性计数（OBS-VARPOOL）：角色数/容量、角色表容量冗余、串表条数
            "角色数/容量" to (runCatching {
                "${session.store.characters.count}/${session.store.characters.allocatedCapacity}"
            }.getOrDefault("?")),
            "角色行字节" to runCatching { session.store.characters.rowBytes().toString() }.getOrDefault("?"),
            "串表条数" to runCatching { session.store.pool.distinctCount.toString() }.getOrDefault("?"),
            // `Vm.executedSteps` 只在 run 的正常出口写（`Vm.kt:966/1120/1215`），本装置的读数时机
            // 恒在「parked 在等待点上」⇒ 这一列注定是 0，留着只为证明读数时机没错位，不当步数用。
            "VM步数(仅run出口写,parked恒0)" to executedSteps(session.runtime).toString(),
        )
    }

    private fun pagePoint(program: Program, session: GameSession, label: String, note: String): PagePoint {
        val st = runCatching { session.store.aux.peek<GraphicsPool>(GRAPHICS_SLOT)?.stats() }.getOrNull()
        return PagePoint(
            index = pages.size + 1,
            label = label,
            liveBytes = HeapMeter.settled() - s0Bytes,
            consoleLines = runCatching { session.console.displayLineCount }.getOrDefault(-1),
            materializedStmts = program.materializedStatementCount,
            gfxEntries = st?.entries ?: -1,
            gfxPixelBytes = st?.pixelBytes ?: -1L,
            steps = executedSteps(session.runtime),
            note = note,
            varPool = runCatching { VarPoolFacets(sizer).snapshot(session.store, session.store.characters).bytes }.getOrDefault(emptyMap()),
            varPoolCounts = runCatching { VarPoolFacets(sizer).snapshot(session.store, session.store.characters).counts }.getOrDefault(emptyMap()),
        )
    }

    // ------------------------------------------------------------------
    // 出行
    // ------------------------------------------------------------------

    private fun emitLiveTable() {
        line()
        line("## 1. 逐相位 live 与恒等式（GC差口径，已扣 S0=${mib(s0Bytes)} MiB）")
        line()
        line("| 相位 | live MiB（GC差） | Δ 对上一相位 | 入账合计 | 未归类 | 判定 | jcmd MiB | 遍历 ms | 计数器 |")
        line("|---|---:|---:|---:|---:|---|---:|---:|---|")
        for ((idx, p) in phases.withIndex()) {
            val d = if (idx == 0) 0L else p.liveBytes - phases[idx - 1].liveBytes
            line("| ${p.label} | ${mib(p.liveBytes)} | ${if (idx == 0) "-" else signed(d)} | ${mib(p.counted)} | " +
                "${mib(p.unclassified)} | ${p.verdictOverride.ifEmpty { p.verdict.toString() }} | " +
                "${if (p.jcmdLiveBytes > 0) mib(p.jcmdLiveBytes) else "-"} | ${p.walkMs} | " +
                "${p.counters.entries.joinToString("; ") { "${it.key}=${it.value}" }} |")
        }
        line()
        line("- 「判定」列的 **SPLIT** = 两个 live 口径当场 disagreement（GC差 少读、jcmd 与遍历入账同侧）；" +
            "SPLIT 不算重复计账、不触发 exit 1，但必须并排看到才算数。")
    }

    private fun emitDiffTable() {
        line()
        line("## 2. 逐相位分面差分（面 × 遍历相位；单位 MiB；首列给绝对值，其余列 = 相对上一遍历相位的 Δ）")
        line()
        val walked = phases.filter { it.surfaces.isNotEmpty() }
        if (walked.size < 2) {
            line("- 只有 ${walked.size} 个相位跑到了遍历（walk=0 档或提前收口）⇒ 差分表出行不了，如实落此")
            return
        }
        val totalFirst = walked.first().surfaces.values.sum()
        val totalLast = walked.last().surfaces.values.sum()
        val netAll = totalLast - totalFirst
        line("| 面 | ${walked.joinToString(" | ") { it.label }} | 首末 Δ | 占首末净增 |")
        line("|---|" + walked.joinToString(" |") { "---:" } + "|---:|---:|")
        for (surface in PhaseSurface.ORDER) {
            val vals = walked.map { it.surfaces[surface] ?: 0L }
            val cells = ArrayList<String>(vals.size)
            for (i in vals.indices) cells += if (i == 0) mib(vals[i]) else signed(vals[i] - vals[i - 1])
            val net = vals.last() - vals.first()
            val share = if (netAll == 0L) "-" else "${net * 100 / netAll}%"
            line("| $surface | ${cells.joinToString(" | ")} | ${signed(net)} | $share |")
        }
        val d = PhaseLadderMath.facetDiff(walked.first().surfaces, walked.last().surfaces)
        line("| **入账面合计** | ${walked.joinToString(" | ") { mib(it.counted) }} | ${signed(netAll)} | 100% |")
        line("| **未归类（兜底）** | ${walked.joinToString(" | ") { mib(it.unclassified) }} | " +
            "${signed(walked.last().unclassified - walked.first().unclassified)} | |")
        line("| **live 总额** | ${walked.joinToString(" | ") { mib(it.liveBytes) }} | " +
            "${signed(walked.last().liveBytes - walked.first().liveBytes)} | |")
        line()
        line("- 增量排行（对首个遍历相位 ${walked.first().label}）：" +
            PhaseLadderMath.topDiffs(d, 5).joinToString(" / ") { "${it.first}=${signed(it.second)}MiB" })
        line("- 占比列分母 = 入账合计首末差（${mib(netAll)} MiB）：它直接回答「这段相位里谁在长」。")
    }

    /**
     * §2b 变量池**子块**逐相位表（OBS-VARPOOL）。
     *
     * 口径：`VarPoolFacets` 的公式口径（逐列数组长度 × 元素宽），互斥、不入账、跨相位按键对齐；
     * 与 §2 的遍历口径并排看，两侧求和各成口径，不许相加。
     * 用途：把面 10 的「+58.9 MiB」拆到「角色表整数列长多少 / 其中多少是容量冗余 / 串表长多少」。
     */
    private fun emitVarPoolPhaseTable() {
        line()
        line("## 2b. 变量池子块逐相位（公式口径，互斥、不入账；单位 MiB；除首列外每列 = 相对上一相位的 Δ）")
        line()
        if (varPoolPhases.size < 2) {
            line("- 子块快照不足 2 份（相位没走全或快照异常）⇒ 本表不出，不硬造读数")
            return
        }
        val keys = LinkedHashSet<String>()
        for ((_, vp) in varPoolPhases) keys.addAll(VarPoolBlock.ORDER.filter { vp.bytes.containsKey(it) })
        line("| 子块 | ${varPoolPhases.joinToString(" | ") { it.first }} | 首末 Δ |")
        line("|---|" + varPoolPhases.joinToString(" |") { "---:" } + "|---:|")
        for (k in keys) {
            val vals = varPoolPhases.map { it.second.block(k) }
            val cells = ArrayList<String>(vals.size)
            for (i in vals.indices) cells += if (i == 0) mib(vals[i]) else signed(vals[i] - vals[i - 1])
            line("| $k | ${cells.joinToString(" | ")} | ${signed(vals.last() - vals.first())} |")
        }
        val sums = varPoolPhases.map { it.second.sumBytes() }
        val cells = ArrayList<String>(sums.size)
        for (i in sums.indices) cells += if (i == 0) mib(sums[i]) else signed(sums[i] - sums[i - 1])
        line("| **子块互斥合计（数据面下界）** | ${cells.joinToString(" | ")} | ${signed(sums.last() - sums.first())} |")
        line()
        line("- 决定性计数（逐相位；角色数/容量 · 行字节 · 容量冗余 · 预留倍率 · 串表条数）：")
        for ((label, vp) in varPoolPhases) {
            line("  - $label：角色=${vp.counts["角色表.count/容量"] ?: "-"} 行字节=${vp.counts["角色表.行字节"] ?: "-"} B " +
                "已用=${vp.counts["角色表.已用字节"] ?: "-"} B **冗余=${vp.counts["角色表.容量冗余字节"] ?: "-"} B** " +
                "倍率=${vp.counts["角色表.预留倍率‰"] ?: "-"}‰ 列=${vp.counts["角色表.列数"] ?: "-"} " +
                "系统整数列=${vp.counts["系统整数列.已分配/总列"] ?: "-"} 私有=${vp.counts["用户私有.函数键/变量数"] ?: "-"} " +
                "串表=${vp.counts["串表.条数/背衬容量/表长"] ?: "-"}")
        }
        val first = varPoolPhases.first().second
        val last = varPoolPhases.last().second
        val d = VarPoolMath.topDeltas(first.diff(last), 5)
        line("- 首末子块增量前五：" + d.joinToString(" / ") { "${it.first}=${signed(it.second)}MiB" })
    }

    /**
     * §2c 变量池子块**逐页**表：台阶归因的主证据。
     *
     * 为什么必须逐页：§2 只在 P0/P1/P2/P3 末页/P4 五个点上做遍历，而 +72 MiB 是「一次性台阶」——
     * 五个点里它整块出现在 P2→P3 之间，落不到具体哪一次调用上。子块快照是公式口径（不建访问集），
     * 便宜到每一页都能读，于是「哪一页把哪一块灌进去」直接成表。
     */
    private fun emitVarPoolPageTable() {
        line()
        line("## 2c. 变量池子块逐页序列（台阶归因：+X MiB 出在第几页、哪一块）")
        line()
        val baseline = varPoolPhases.firstOrNull { it.first.startsWith("P2") }?.second
        if (pages.isEmpty()) {
            line("- 一页都没多走成 ⇒ 逐页表不出")
            return
        }
        if (baseline == null) {
            line("- 缺 P2 基线快照 ⇒ 逐页 Δ 不可算，只出绝对值")
        }
        val series = ArrayList<Pair<String, Map<String, Long>>>()
        if (baseline != null) series += "P2(基线)" to baseline.bytes
        for (pg in pages) series += pg.label to pg.varPool
        val keys = LinkedHashSet<String>()
        for ((_, m) in series) keys.addAll(VarPoolBlock.ORDER.filter { m.containsKey(it) })
        line("| 子块 | ${series.joinToString(" | ") { it.first }} | 首末 Δ | 最大单页 Δ（出现在） |")
        line("|---|" + series.joinToString(" |") { "---:" } + "|---:|---|")
        for (k in keys) {
            val vals = series.map { it.second[k] ?: 0L }
            val cells = ArrayList<String>(vals.size)
            for (i in vals.indices) cells += if (i == 0) mib(vals[i]) else signed(vals[i] - vals[i - 1])
            var maxIdx = 1
            var maxD = Long.MIN_VALUE
            for (i in 1 until vals.size) {
                val dd = vals[i] - vals[i - 1]
                if (dd > maxD) { maxD = dd; maxIdx = i }
            }
            val where = if (maxD <= 0L) "-" else "${series[maxIdx].first} ${signed(maxD)}MiB"
            line("| $k | ${cells.joinToString(" | ")} | ${signed(vals.last() - vals.first())} | $where |")
        }
        val sums = series.map { it.second.values.sum() }
        val cells = ArrayList<String>(sums.size)
        for (i in sums.indices) cells += if (i == 0) mib(sums[i]) else signed(sums[i] - sums[i - 1])
        line("| **子块合计** | ${cells.joinToString(" | ")} | ${signed(sums.last() - sums.first())} | |")
        line()
        line("- 逐页角色数/容量与冗余（台阶的直接判据：角色数在长 = 原版口径的按需建角色；只有容量在长 = 移植侧预留）：")
        if (baseline != null) line("  - P2(基线)：角色=${baseline.counts["角色表.count/容量"]} 冗余=${baseline.counts["角色表.容量冗余字节"]} B 串表=${baseline.counts["串表.条数/背衬容量/表长"]}")
        for (pg in pages) line("  - ${pg.label}：角色=${pg.varPoolCounts["角色表.count/容量"] ?: "-"} " +
            "冗余=${pg.varPoolCounts["角色表.容量冗余字节"] ?: "-"} B 串表=${pg.varPoolCounts["串表.条数/背衬容量/表长"] ?: "-"} " +
            "私有=${pg.varPoolCounts["用户私有.函数键/变量数"] ?: "-"} live=${mib(pg.liveBytes)}MiB")
    }

    private fun emitPageSeries(p2: PhaseReading) {
        line()
        line("## 3. P3 逐页序列（每页净增 = 「堆贴顶是设计余量不足还是有账没收」的关键读数）")
        line()
        val series = ArrayList<Long>()
        series += p2.liveBytes
        series += pages.map { it.liveBytes }
        if (pages.isEmpty()) {
            line("- 一页都没多走成（P2 之后脚本就停了）⇒ 逐页序列不可给，停止条件见上面")
            return
        }
        val ruler = MARGINAL_STMT_BYTES
        line("| # | 页 | live MiB | Δ/页 | Δ 尺算条数 | 控制台行数 | 已物化语句 | 物化×尺 MiB | G_ID 条目 | G_ID 像素 MiB | VM 段步数 | 备注 |")
        line("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        for ((i, p) in pages.withIndex()) {
            val prev = series[i]
            val delta = p.liveBytes - prev
            val asStmts = PhaseLadderMath.stmtCountFromBytes(delta, ruler)
            line("| ${i + 1} | ${p.label} | ${mib(p.liveBytes)} | ${signed(delta)} | ${group(asStmts)} | ${p.consoleLines} | " +
                "${p.materializedStmts} | ${mib(p.materializedStmts.toLong() * ruler)} | ${p.gfxEntries} | " +
                "${if (p.gfxPixelBytes < 0) "-" else mib(p.gfxPixelBytes)} | ${p.steps} | ${p.note.ifEmpty { "-" }} |")
        }
        val mono = PhaseLadderMath.isMonotoneGrowth(series, PAGE_MONOTONE_TOL)
        val per = PhaseLadderMath.perUnitGrowth(series.first(), series.last(), series.size - 1)
        line()
        line("- 序列（P2 起算）：${series.joinToString(" / ") { mib(it) }} MiB")
        line("- 逐页增量：" + PhaseLadderMath.stepDeltas(series).drop(1).joinToString(" / ") { signed(it) + " MiB" })
        line("- 单调不回吐（每步 ≥ -2 MiB 且累计 > 2 MiB；判据 `PhaseLadderMath.isMonotoneGrowth`）= **$mono**")
        line("- 每页净增均值 = ${signed(per)} MiB/页 ⇒ 按尺 ${group(PhaseLadderMath.stmtCountFromBytes(per, ruler))} 条语句/页" +
            "（尺算只是外推列，入账看 §2 的分面 Δ）")
    }

    private fun emitJcmdSection() {
        line()
        line("## 4. jcmd 交叉核对（JVM 权威 live，逐相位；来源=直方图，与 GC差 / 枚举口径**互不混用**）")
        line()
        if (jcmdRows.isEmpty()) line("- 未采（ERATW_PROBE_JCMD=0，或本机 java.home 下没有 jcmd）")
        for ((label, text) in jcmdRows) line("- $label：$text")
    }

    private fun emitJudgement(
        p0: PhaseReading,
        p1: PhaseReading,
        p2: PhaseReading,
        p3last: PhaseReading?,
        p4: PhaseReading?,
        pagesN: Int,
        stopped: String,
    ) {
        line()
        line("## 5. 判读（装置只出机械可算的部分；三块归属与「有账没收」的结论写在交付报告里）")
        line()
        val walked = phases.filter { it.surfaces.isNotEmpty() }
        if (walked.isNotEmpty()) {
            val last = walked.last()
            line("- 最后一个遍历相位（${last.label}）总额前三的面：" +
                PhaseLadderMath.topSurfaces(last.surfaces, 3).joinToString(" / ") { "${it.first}=${mib(it.second)}MiB" })
            if (walked.size >= 2) {
                val d = PhaseLadderMath.facetDiff(walked.first().surfaces, last.surfaces)
                line("- 对首个遍历相位（${walked.first().label}）增量前三的面：" +
                    PhaseLadderMath.topDiffs(d, 3).joinToString(" / ") { "${it.first}=${signed(it.second)}MiB" })
            }
        }
        line("- 逐相位 live：P0=${mib(p0.liveBytes)} / P1=${mib(p1.liveBytes)} / P2=${mib(p2.liveBytes)}" +
            (p3last?.let { " / P3末页=${mib(it.liveBytes)}" } ?: "") +
            (p4?.let { " / P4回标题=${mib(it.liveBytes)}" } ?: ""))
        val units = (pages.size + 1).coerceAtLeast(1)
        if (p3last != null) {
            line("- P2→P3末页共 $units 次翻页：live ${signed(p3last.liveBytes - p2.liveBytes)} MiB ⇒ " +
                "${signed(PhaseLadderMath.perUnitGrowth(p2.liveBytes, p3last.liveBytes, units))} MiB/页")
        }
        if (p4 != null && phases.size >= 2) {
            val prev = phases[phases.lastIndex - 1]
            line("- P4 回标题重画一页后 live=${mib(p4.liveBytes)} MiB（相对上一相位 ${signed(p4.liveBytes - prev.liveBytes)} MiB）：" +
                "`ClearDisplay` + `UnloadGraphicList` 之后还不还堆，看这一行")
        }
        if (stopped.isNotEmpty()) line("- 本段停止原因：$stopped")
        if (pagesN > pages.size + 2) line("- 计划 $pagesN 页、实到 ${pages.size + 2} 页")
        line()
        line("- 与桌面 280 MiB 的关系：本装置 classpath 上没有 :render 与 :tools:uipreview ⇒ 本班读数**只覆盖引擎侧**；" +
            "280 MiB 与本班末相位读数的差额 = 渲染侧（字形栅格 / 合成画布）+ 桌面 eager 解码那批像素。" +
            "这条差额按**差额**记，逐类权威读数看 §4 的 jcmd 行。")
        line("- 「慢」（尾部劣化的另一半：整图重画的语句量与字符串 churn）不在本车道，在册欠账见 BOARD 2026-10-30 那条；" +
            "本探针只报堆，不报快慢，也不把两者混成一句。")
        line()
        line("## 6. 逐相位分面明细（每相位一张逐根表；来源列逐项标注，四口径不混为「实测」）")
        line()
        report.append(details)
        line()
        line("## 7. 复现")
        line()
        line("```")
        line("ERATW_ROOT=<work-root>; source \"\$ERATW_ROOT/.tooling/env.sh\"")
        line("export ERATW_GAMEDIR=\"\$ERATW_ROOT/eratw-画蛇添足版eratw4.980\"")
        line("export ERATW_CORPUS=\"\$ERATW_GAMEDIR/ERB\"")
        line("export ERATW_ERBC_DIR=<erbc-dir>")
        line("./gradlew --configure-on-demand :core-vm:phaseHeapLadder -Pprobe.xmx=3g")
        line("旋钮：-Pprobe.xmx=512m + ERATW_PROBE_WALK=0（设备挡位，只出逐相位 live 与计数器）；")
        line("      ERATW_PROBE_PAGES=<n>（P3 页数，缺省 $DEFAULT_PAGES）/ ERATW_PROBE_INPUTS=0,0（开局脚本）/")
        line("      ERATW_PROBE_AWAIT_MS / ERATW_PROBE_TURN_MS / ERATW_PROBE_WALL_MS（等待与墙钟闸）/")
        line("      ERATW_PROBE_WALK=0 / ERATW_PROBE_JCMD=0 / ERATW_PROBE_FLAT=1 / ERATW_PROBE_OUT=<路径> /")
        line("      ERATW_PERF_ROUNDS=<n>（GC 轮数）")
        line("```")
    }

    private fun writeReport(walkOn: Boolean, pagesN: Int) {
        val dst = System.getenv("ERATW_PROBE_OUT")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            ?: Path.of("build", "phase-heap-ladder.md")
        runCatching {
            dst.parent?.let { Files.createDirectories(it) }
            Files.writeString(dst, report.toString(), java.nio.charset.StandardCharsets.UTF_8)
            println("[ladder] 报告：$dst（walk=$walkOn, pages=$pagesN）")
        }.onFailure { println("[ladder] 报告写入失败：${it.message}") }
    }

    /** 反射读 `ProcessRuntime.vm` 的 `executedSteps`（与 `TurnLatencyProbeTest` 同一装置限制）。 */
    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        val vm = f.get(rt) ?: return@runCatching 0L
        (vm.javaClass.getMethod("getExecutedSteps").invoke(vm) as Number).toLong()
    }.getOrDefault(0L)

    /**
     * `jcmd <pid> GC.class_histogram`：JVM 权威 live（来源单独标注，只交叉核对、不入账）。
     *
     * 解析口径与 `HeapRootAttributionProbe.jcmdCrossCheck` 同源，**MULTILINE 必带**：
     * Java 正则的 `$` 缺省只锚整段输入末尾，不带它逐行全灭（上一路实测解析 0 行的根因）。
     */
    private fun jcmdHistogram(): Pair<Long, List<Triple<Long, Long, String>>>? {
        val text = runCatching {
            val jcmd = Path.of(
                System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "jcmd.exe" else "jcmd",
            )
            if (!Files.isRegularFile(jcmd)) return@runCatching null
            val pid = ProcessHandle.current().pid().toString()
            val p = ProcessBuilder(jcmd.toString(), pid, "GC.class_histogram").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(240, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
            if (p.exitValue() != 0) return@runCatching null
            t
        }.getOrNull() ?: return null
        val entries = ArrayList<Triple<Long, Long, String>>()
        Regex("""\s*\d+:\s+(\d+)\s+(\d+)\s+(\S.*?)(?:\s+\([^)]*\))?\s*$""", RegexOption.MULTILINE)
            .findAll(text).forEach { m ->
                entries.add(Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].trim()))
            }
        if (entries.isEmpty()) return null
        return entries.sumOf { it.second } to entries
    }

    // ------------------------------------------------------------------
    // 脚本线程泵（一条线程跑脚本 = ADR-003；测量与提交在探针主线程）
    // ------------------------------------------------------------------

    /**
     * VM 线程泵。
     *
     * 与 `BootPlayChain` 的驱动同一形态，差别只在**没有并发 feeder 线程**：本装置的
     * 「等待 → 提交 → 推进」由主线程按相位边界逐次驱动（[turnPage]），于是每个相位的 live
     * 读数都落在「刚画完一页、且停在等待点上」这个确定时刻 —— 并发 feeder 会把读数时机交给运气，
     * 差分表就没法比。
     *
     * 线程纪律：`begin` / `resumeSystem` / `gotoTitle` 全部只在这条线程执行；主线程只读
     * console 行数、输入队列状态、图形池统计这些**公开诊断面**，不碰 VM 内部状态。
     *
     * 循环顶挂起（[requestHoldThenGotoTitle]）存在的原因：`gotoTitle` 是整栈丢弃 + 状态复位的恢复
     * 通道（九条对应 `EmueraConsole.cs:2569-2581`），只有脚本自己让出循环顶时才是它的安全调用点。
     */
    private class VmPump(private val session: GameSession) {

        @Volatile var terminal: String? = null
            private set

        @Volatile var stopReason: String = ""
            private set

        @Volatile var roundTrips: Int = 0
            private set

        /** 当前页走到哪：`handler=... state=... outcome=...`，落报告当「停在哪」的证据尾巴。 */
        @Volatile var pageLabel: String = "-"
            private set

        private val trail = ArrayDeque<String>()
        private val script = ArrayDeque(
            (System.getenv("ERATW_PROBE_INPUTS")?.takeIf { it.isNotBlank() } ?: "0,0")
                .split(',', ';').mapNotNull { it.trim().toLongOrNull() },
        )
        private val gate = Object()

        @Volatile private var stop = false
        @Volatile private var holdRequested = false
        @Volatile private var heldAtTop = false
        @Volatile private var gotoTitleRequested = false

        /** P4 的收口路径开关：抛 `InputSinkClosedException` 之后要不要顺手跑一次 `gotoTitle()`。 */
        @Volatile private var unwindToTitle = false

        /** 收口路径的复位是否已经跑完（主线程等这个位取 P4 读数）。 */
        @Volatile var resetDone = false
            private set

        /** 收口路径里 `gotoTitle()` 返回的那一步（正常返回才有；再抛就是 null，复位照样完成）。 */
        @Volatile private var gotoTitleStep: StateStep? = null

        val thread: Thread = Thread(::loop, "eratw-phase-vm").apply { isDaemon = true }

        fun start() = thread.start()

        private fun loop() {
            val rt = session.runtime
            try {
                var step = rt.beginStep(BeginType.TITLE)
                note(step)
                while (!stop && terminal == null) {
                    if (holdRequested) {
                        synchronized(gate) {
                            heldAtTop = true
                            while (heldAtTop && !stop) gate.wait(200L)
                        }
                        if (stop) break
                    }
                    if (gotoTitleRequested) {
                        gotoTitleRequested = false
                        step = rt.gotoTitle()
                        note(step)
                        continue
                    }
                    if (rt.quitRequested) {
                        terminalLine("QUIT（脚本 QUIT 粘滞位，$pageLabel）")
                        break
                    }
                    when (step.outcome) {
                        StepOutcome.FAILED -> {
                            terminalLine("SCRIPT_END/FAILED ${step.blocker?.tuple() ?: step.evidence}（$pageLabel）")
                            return
                        }
                        StepOutcome.BLOCKED -> {
                            terminalLine("BLOCKED ${step.blocker?.kind} ${step.blocker?.message ?: ""}（$pageLabel）")
                            return
                        }
                        else -> Unit
                    }
                    step = rt.resumeSystem()
                    note(step)
                }
            } catch (e: CodeEE) {
                terminalLine("CODEEE func=${e.funcName} line=${e.line} pc=${e.pc} msg=${e.message?.lineSequence()?.firstOrNull()}")
            } catch (e: StepLimitExceeded) {
                terminalLine("STEP_LIMIT steps=${e.steps}")
            } catch (e: InputSinkClosedException) {
                if (unwindToTitle) {
                    // 队列已关 ⇒ run 已经整栈展开，脚本线程此刻在 catch 里 = 一个安全点（没有半截 run）。
                    // gotoTitle 的九条里前八条（ClearDisplay / UnloadGraphicList / setRedraw / 样式复位 /
                    // resetData / 清闩锁 / 选 TITLE）都在 `beginFlow.step()` **之前**，那一步会在已关的
                    // 队列上再抛一次 ⇒ 抛出来时复位已完成，主线程要读的「回标题之后的堆」就是这一态。
                    // 这条**不是生产路径**（生产是 UI 线程调 GotoTitle 后照常泵标题），标签里写明了。
                    val g = runCatching { session.runtime.gotoTitle() }
                    gotoTitleStep = g.getOrNull()
                    gotoTitleStep?.let {
                        pageLabel = "handler=${it.handler} state=${it.to} outcome=${it.outcome} fn=${it.calledFunction ?: "-"}"
                    }
                    resetDone = true
                    synchronized(gate) { gate.notifyAll() }
                    terminalLine("CLOSED+GOTO_TITLE_RESET（经收口路径完成回标题复位，非生产路径）")
                } else {
                    terminalLine("CLOSED（输入队列被关）")
                }
            } catch (t: Throwable) {
                terminalLine("${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}")
                t.stackTrace.take(6).forEach { println("        at $it") }
            }
        }

        private fun terminalLine(reason: String) {
            stopReason = reason
            terminal = reason
        }

        private fun note(step: StateStep) {
            pageLabel = "handler=${step.handler} state=${step.to} outcome=${step.outcome} fn=${step.calledFunction ?: "-"}"
            trail.addLast("${step.from}→${step.to} ${step.handler} ${step.outcome} ${step.calledFunction ?: "-"}")
            if (trail.size > 24) trail.removeFirst()
        }

        /** 最近 24 步的动作尾巴（报「停在哪」用）。 */
        fun trailText(): String = trail.joinToString(" | ")

        /** 等到「停在等待点上」或脚本终局/循环顶；true = 到了等待点（或循环顶）。 */
        fun awaitAwaiting(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (terminal != null) return false
                if (heldAtTop) return true
                if (session.inputSink.isAwaiting && session.inputSink.pendingCount == 0) return true
                Thread.sleep(5L)
            }
            terminalLine("AWAIT_TIMEOUT ${timeoutMs}ms（$pageLabel）")
            return false
        }

        /**
         * 翻一页：按 `BootPlayChain` 的作答口径提交一条输入（INT 有脚本用脚本、无脚本用默认值或 0；
         * STR 用默认或空串；回车系空提交），然后等下一个等待点 / 循环顶 / 终局。
         *
         * 提交一律 `mesSkip = false`（输入法回车那一路，`MainWindow.cs:1407-1414` →
         * `EmueraConsole.cs:1371-1373`），口径与 `BootPlayChain` 逐字相同。
         *
         * @return 空串 = 正常到下一个等待点（或循环顶）；否则原样带进报告的停止原因
         */
        fun turnPage(): String {
            val sink = session.inputSink
            terminal?.let { return it }
            if (!sink.isAwaiting) return if (heldAtTop) "" else "没停在等待点（$pageLabel）"
            val seqBefore = sink.lastConsumedSeq
            val req: InputRequest? = sink.awaiting
            val text = when {
                sink.awaitingAnyKey || req == null -> ""
                req.type == InputRequest.InputType.INT ->
                    (script.removeFirstOrNull() ?: if (req.hasDef) req.defInt else 0L).toString()
                else -> if (req.hasDef) req.defStr else ""
            }
            if (!sink.submit(text, mesSkip = false)) return "submit 被拒（队列已关）"
            val capMs = System.getenv("ERATW_PROBE_TURN_MS")?.toLongOrNull() ?: 300_000L
            val deadline = System.nanoTime() + capMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                terminal?.let { return it }
                if (heldAtTop) return ""
                if (sink.lastConsumedSeq > seqBefore) {
                    roundTrips++
                    if (sink.isAwaiting && sink.pendingCount == 0) return ""
                }
                Thread.sleep(5L)
            }
            terminalLine("TURN_TIMEOUT ${capMs}ms（提交后没等到下一个等待点，$pageLabel）")
            return terminal ?: "TURN_TIMEOUT"
        }

        /**
         * P4：请求在下一个循环顶挂起 → 挂上后下令 `gotoTitle()` → 等标题页的等待点。
         *
         * 挂起期间脚本若还停在等待点上，就按同一口径替它答一次（不答就永远到不了循环顶）；
         * 答复策略与 [turnPage] 完全同一条，不给 P4 另立一套语义。
         *
         * @return 空串 = P4 读数可用；否则停止原因（如实报，不硬调、不越线程）
         */
        fun requestHoldThenGotoTitle(holdMs: Long, awaitMs: Long): String {
            terminal?.let { return "脚本已终局：$it" }
            holdRequested = true
            val deadline = System.nanoTime() + holdMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                terminal?.let { holdRequested = false; return "脚本终局：$it" }
                if (heldAtTop) break
                if (session.inputSink.isAwaiting && session.inputSink.pendingCount == 0) {
                    val r = turnPage()
                    if (r.isNotEmpty() && !heldAtTop) { holdRequested = false; return r }
                    continue
                }
                Thread.sleep(5L)
            }
            if (!heldAtTop) {
                holdRequested = false
                return "没拿到循环顶（脚本一直让不出来，$pageLabel）"
            }
            gotoTitleRequested = true
            synchronized(gate) { heldAtTop = false; gate.notifyAll() }
            return if (awaitAwaiting(awaitMs)) "" else (terminal ?: "GOTO_TITLE 后未到等待点")
        }

        /**
         * P4 的第二条路（**非生产路径**，标签里写明）：关队列把当前 run 整栈展开，
         * 让脚本线程停在一个安全点，然后由它自己跑一次 `gotoTitle()` 的复位。
         *
         * 为什么需要这条路：`requestHoldThenGotoTitle` 依赖「脚本自己让出循环顶」，而移植侧的等待是
         * 阻塞式 —— 一次 run 跨过几十条输入（`BootPlayChain` 类文档那条事实），开局链里循环顶回不来。
         * 关队列 = `BootPlayChain` 收口用的同一个动作（旁路看门狗 `inputSink.close()`，
         * 脚本线程以 `InputSinkClosedException` 结束当前 run），不新增任何产品语义。
         *
         * @return 空串 = 复位完成、可以取 P4 读数；否则停止原因
         */
        fun unwindThenGotoTitle(timeoutMs: Long): String {
            if (resetDone) return ""
            unwindToTitle = true
            session.inputSink.close()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (resetDone) return ""
                terminal?.let { if (!unwindToTitle) return "脚本终局：$it" }
                Thread.sleep(5L)
            }
            return "收口后 ${timeoutMs}ms 内没跑完回标题复位（$stopReason）"
        }
    }
}
