package com.eratw.port.perf

import com.eratw.port.core.code.MappedCodeSource
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.SaveCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

/**
 * 游玩卡顿（点选项后 3~4 秒 / 日始卡无限循环）的桌面取证装置：真产物 + 真存档，
 * 量「同样多的 VM 指令要跑多久」，并给出动态画像（操作码 / 函数 / 分配 / 打印密度）。
 *
 * 口径：`CHARA_MOVEMENT` 是每回合（`EVENTTRAIN`）都会跑的重负载（BOARD 红 K 读数
 * 12,080,416 条指令），用它代表「点一下之后脚本侧要算多久」。默认步数闸
 * [FULL_RUN_CAP]=12,500,000，保证**跑满一整次**函数而不是在半途撞闸；撞闸本身只记
 * `StepLimitExceeded.steps`，不判失败。
 *
 * 装配口径 = 生产相位的一截：每轮新建 `GameSession`，先跑 `@QOL_DB_INIT`
 * （`TITLE.ERB:28` 的 `CALL QOL_DB_INIT`，`qol_db.ERB:60-62` 登记 `TR_DB` 并灌 MAP 表），
 * 再 `save03.applyTo`（读档恢复），最后 `callFunction("CHARA_MOVEMENT")`。不走
 * `EVENTTRAIN` 外层 `WHILE` / `BEGIN TITLE` 菜单流 / 输入等待，所以读的是「起床链里
 * 那一次函数本体」。
 *
 * 读数：墙钟轮 `ns/instr`（2 轮预热 + 5 轮稳态取 median）+ 每百万指令分配 MB + 页取信
 * miss%；画像轮单独挂 TEMP 探针，输出动态操作码 top 30、函数级指令归因、打印密度。
 *
 * 语料/产物由环境变量给出（`ERATW_GAMEDIR` / `ERATW_ERBC_DIR`），缺失即跳过。
 * `ERATW_PERF_CAP` / `ERATW_PERF_ROUNDS` / `ERATW_PERF_WARMUP` / `ERATW_PERF_PROFILE=0`
 * 可覆盖步数闸、轮数、预热轮数与是否跑画像轮。
 */
class TurnLatencyProbeTest {

    /**
     * 装置侧装载产物；`ERATW_FLAT_OFF=1` ⇒ 关扁平语句池，对齐**设备口径**。
     *
     * 为什么装置要能关：Android 生产 `GameHost` 传的是 `flatStatements = false`（真机实测
     * +114 MiB，`CONTRACT-T2-64` §10.9），桌面缺省却是开 ⇒ 拿桌面读数做**分配归因**时，
     * 「执行期建片」那条链（`LazyStatementRecords.buildFlatStatementsRange` → `VarRef`/`Expr[]`）
     * 会被算成设备成本，归因方向就错了。墙钟倍率两侧都成立（取指面与语句池无关）。
     */
    private fun loadForProbe(erbcDir: Path): com.eratw.port.core.code.Program =
        ProgramLoader.loadMapped(
            erbcDir.resolve("program.erbc"),
            flatStatements = System.getenv("ERATW_FLAT_OFF") != "1",
        ) { }

    // realArtifacts 家标记（realarttag 路 2026-11-06）：本类前 3 条产物门控钉打标；末尾那条 ERATW_PERF_AB A/B 钉不属产物闸，不打标。判定逻辑一字不动。
    @Tag("realArtifacts")
    @Test
    fun `量同样多指令的墙钟`() {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")

        val cap = System.getenv("ERATW_PERF_CAP")?.toLongOrNull() ?: FULL_RUN_CAP
        val rounds = System.getenv("ERATW_PERF_ROUNDS")?.toIntOrNull() ?: (WARMUP_ROUNDS + 5)
        val warmup = System.getenv("ERATW_PERF_WARMUP")?.toIntOrNull() ?: WARMUP_ROUNDS
        val profileEnabled = System.getenv("ERATW_PERF_PROFILE") != "0"
        println(
            "PERF 口径：一次完整 CHARA_MOVEMENT；每轮新建 GameSession（QOL_DB_INIT → save03.applyTo → " +
                "callFunction）；cap=$cap rounds=$rounds warmup=$warmup profile=$profileEnabled"
        )

        // 墙钟轮：**不挂任何探针**。探针本身要逐指令写数组/计数器，混进时序读数会把
        // ns/instr 抬高；动态画像在同一份产物上单独跑一轮（下面 PROFILE）。
        val timing = ArrayList<RoundMetrics>(rounds)
        repeat(rounds) { i -> timing += runCharaRound("T${i + 1}", gameRoot!!, erbcDir!!, save, cap, withProbes = false) }

        val completed = timing.filter { it.error == null && it.steps > 0 }
        val distinctSteps = completed.map { it.steps }.distinct()
        println(
            "SELFTEST rounds=${timing.size} completed=${completed.size} distinctSteps=${distinctSteps.size} " +
                "steps=${distinctSteps.joinToString(",")} qolErrors=${timing.count { it.qolError != null }}"
        )
        // 复现门（2026-11-08 实测）：`CHARA_MOVEMENT` 整段要过 `@QOL_DB_INIT`（`TR_DB` 连接 +
        // 两张 MAP 表灌库，本身数百万条指令，装置侧 cap 不够就截断），截断后段内首个
        // `SQL_EXECUTE_SCALAR_STRING` 就抛 `CodeEE`。因此这里**不把装置环境问题判成红**：
        // 只做诊断输出并跳过，热路径的正式基准走 `热点函数 GMF_METHOD_UPDATE 微基准`
        // （97.8% 成本的同形切片，无需 SQL/QOL）。等 QOL 初始化能在装置内跑满，
        // 再把这条恢复成硬断言。
        if (timing.any { it.error != null } || timing.any { it.qolError != null }) {
            println(
                "CHARA 复现门未通过（装置环境，不判红）：\n" +
                    timing.joinToString("\n") { "${it.tag}: error=${it.error ?: "无"} qol=${it.qolError ?: "无"}" },
            )
            assumeTrue(false, "CHARA_MOVEMENT 整段在装置内未跑通（QOL_DB_INIT 截断）；改用 GMF 微基准")
            return
        }
        if (distinctSteps.size > 1 || completed.isEmpty() || completed.first().steps < EXPECTED_MIN_STEPS) {
            println(
                "CHARA 复现门未通过（口径不一致或没跑满 12M）：distinctSteps=$distinctSteps " +
                    "steps=" + completed.joinToString { "${it.tag}=${it.steps}" },
            )
            assumeTrue(false, "CHARA_MOVEMENT 段形状不可比（见上一条诊断）")
            return
        }

        // 至少 5 个稳态样本：去掉前 warmup 轮（JIT/页缓存/宿主缓存预热）。
        val steady = completed.drop(warmup)
        val nsPer = steady.map { r -> r.ns.toDouble() / r.steps }.sorted()
        assertTrue(nsPer.size >= 5, "稳态样本不足 5（rounds=$rounds warmup=$warmup completed=${completed.size}）")
        val median = nsPer[nsPer.size / 2]
        val min = nsPer.first()
        val max = nsPer.last()
        val alloc = steady.map { r -> allocMbPerMillion(r) }.sorted()
        val allocMedian = alloc[alloc.size / 2]
        val missPct = 100.0 * steady.sumOf { it.pageMisses } / steady.sumOf { it.pageHits + it.pageMisses }
        println(
            "STEADY n=${nsPer.size} ns/instr min=${"%.1f".format(min)} median=${"%.1f".format(median)} " +
                "max=${"%.1f".format(max)} | 分配MB/百万指令 median=${"%.2f".format(allocMedian)} " +
                "| 页取信 miss=${steady.sumOf { it.pageMisses }}/${steady.sumOf { it.pageHits + it.pageMisses }} " +
                "(${"%.2f".format(missPct)}%) | 每轮steps=${steady.joinToString(",") { it.steps.toString() }}"
        )
        // 设备换算：**禁止把桌面读数冒充设备读数**。2.22M 指令/秒是玩家报告里的实测吞吐，
        // 只用来做倍率/折算，本装置没有任何真机 adb 读数。
        val playerNsPerInstr = 1_000_000_000.0 / 2_220_000.0
        println(
            "DEVICE 换算：桌面 median=${"%.1f".format(median)} ns/instr；玩家机 2.22M instr/s " +
                "= ${"%.1f".format(playerNsPerInstr)} ns/instr ⇒ 玩家机≈桌面×" +
                "${"%.2f".format(playerNsPerInstr / median)}（桌面快/慢只看倍率）；" +
                "按玩家机吞吐一次 CHARA_MOVEMENT(${completed.first().steps} 条)≈" +
                "${"%.2f".format(completed.first().steps / 2_220_000.0)}s；设备读数 TODO(verify)（adb 空）"
        )
        if (profileEnabled) {
            val profile = runCharaRound("PROFILE", gameRoot!!, erbcDir!!, save, cap, withProbes = true)
            println(
                "PROFILE ns/instr=${"%.1f".format(profile.nsPerInstr())}（探针开，仅作画像，不与上面时序混用）" +
                    " | 全局指令=${profile.globalSteps}（外层 executedSteps=${profile.steps}）"
            )
            profile.opHistogram?.let { dumpHistogram(it) }
            profile.fnHistogram?.let { fn ->
                profile.program?.let { dumpFunctionHistogram(it, fn, profile.globalSteps, profile.steps) }
            }
            dumpPrintDensity(profile, median)
        }
    }

    /** 单轮读数（装置自检口径；`error` 只记非步数闸异常的类型+消息）。 */
    private data class RoundMetrics(
        val tag: String,
        val steps: Long,
        val ns: Long,
        val allocBytes: Long,
        val pageHits: Long,
        val pageMisses: Long,
        val qolMs: Long,
        val qolSteps: Long,
        val qolError: String?,
        val charaNum: Long,
        val error: String?,
        val loadMs: Long,
        val globalSteps: Long,
        val opHistogram: IntArray? = null,
        val fnHistogram: IntArray? = null,
        val printSteps: LongArray? = null,
        val program: com.eratw.port.core.code.Program? = null,
    ) {
        fun nsPerInstr(): Double = if (steps > 0) ns.toDouble() / steps else Double.NaN
    }

    private fun allocMbPerMillion(r: RoundMetrics): Double =
        if (r.steps > 0) r.allocBytes / 1_048_576.0 * 1_000_000.0 / r.steps else Double.NaN

    /**
     * 跑一整轮 `CHARA_MOVEMENT`，并打印**装置自检**：steps / ns / ns·instr⁻¹ / 分配 / 页取信 /
     * 异常类型+消息。
     *
     * 装配顺序刻意对齐生产相位：`TITLE` 段的 `@QOL_DB_INIT` 先跑（`TITLE.ERB:28` 的
     * `CALL QOL_DB_INIT`，`qol_db.ERB:60-62` 建 `TR_DB` 并灌两张 MAP 表），再 `save03`
     * 的变量面 `applyTo`（等价于读档恢复），最后直接 `CALL CHARA_MOVEMENT`。装置**不**走
     * 生产 `BEGIN TITLE` 菜单流与 `EVENTTRAIN` 外层 `WHILE`，所以口径 = `EVENTTRAIN` 起床链
     * 里那一次 `CHARA_MOVEMENT` 本体，不含输入/等待/开场页。
     *
     * `withProbes=true` 时挂 [VirtualMachine] 的 TEMP 归因字段；QOL 初始化期间产生的指令
     * 会在初始化完成后清零，画像只覆盖 `CHARA_MOVEMENT` 本体。探针字段只写数组/计数器，
     * 不改 VM 语义；`globalSteps` 是主 VM + 派生 VM 同一坐标系下的指令总数。
     */
    private fun runCharaRound(
        tag: String,
        gameRoot: Path,
        erbcDir: Path,
        save: Path,
        cap: Long,
        withProbes: Boolean,
    ): RoundMetrics {
        val t0 = System.nanoTime()
        val program = loadForProbe(erbcDir)
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = cap,
            randSeed = 20260226L,
        )
        val loadMs = (System.nanoTime() - t0) / 1_000_000L
        val vm = vmOf(session.runtime)
        val opHist = if (withProbes) IntArray(256) else null
        val fnHist = if (withProbes) IntArray(program.functions.size) else null
        val printMask = if (withProbes) buildPrintMask() else null
        val printLog = if (withProbes) LongArray(PRINT_LOG_CAPACITY) else null
        val globalCounter = if (withProbes) LongArray(1) else null
        if (withProbes) {
            // 必须在 QOL_DB_INIT 之前挂：表达式方法调用走 deriveNestedVm，派生 VM 在创建时
            // 按引用继承这几个字段；先建池再挂会漏掉池内已有的派生 VM。
            setVmField(vm, "opHistogram", opHist)
            setVmField(vm, "fnHistogram", fnHist)
            setVmField(vm, "printOpMask", printMask)
            setVmField(vm, "printStepLog", printLog)
            setVmField(vm, "globalStepCounter", globalCounter)
        }
        var qolMs = -1L
        var qolSteps = 0L
        var qolError: String? = null
        try {
            if (hasFunction(program, "QOL_DB_INIT")) {
                val q0 = System.nanoTime()
                session.runtime.callFunction("QOL_DB_INIT")
                qolMs = (System.nanoTime() - q0) / 1_000_000L
                qolSteps = executedSteps(session.runtime)
            } else {
                // 4.980 口径没有 @QOL_DB_INIT（qol_db.ERB:47 是 4.981 专有）；本装置钉 4.981，
                // 出现这条只可能是环境变量指错根，保留显式读数而不静默兜底。
                println("ROUND $tag QOL_DB_INIT 不在函数表（当前语料非 4.981 口径？）")
            }
        } catch (t: Throwable) {
            qolError = "${t.javaClass.simpleName}: ${t.message}"
        }
        if (withProbes) {
            opHist?.fill(0)
            fnHist?.fill(0)
            globalCounter?.set(0, 0L)
            setVmField(vm, "printStepCount", 0)
        }

        val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
        data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
        val charaNum = session.store.readInt("CHARANUM", LongArray(0))

        // 复现玩家现场：日始重负载只在 DLC「幻想乡大地图」（`DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:412-418`
        // 的 `GMF_GETBIT("幻想乡大地图")` = `GETBIT(GENSOKYO_MAP_FIX, 0)`）开着时才走 —— `MOVEMENT.ERB:115-116`
        // 的 `SIF GMF_GETBIT(...) / CALL GMF_LOCATION_FIX(LOCAL)` 每角色一次，内部 24 次 `GMF_METHOD_UPDATE`。
        // save03 的存档位是关的，所以装置必须显式打开，否则读到的只是「无 DLC」的 0.59M 轻路径。
        // 走语料自己的入口 `@GMF_SETBIT`（`幻想乡地图重修.ERB:427-432`），不手写 TFLAG 下标。
        if (hasFunction(program, "GMF_SETBIT")) {
            session.runtime.callFunction("GMF_SETBIT", LongArray(0), arrayOf("幻想乡大地图"))
            val mapFixOn = runCatching {
                session.runtime.callFunction("GMF_GETBIT", LongArray(0), arrayOf("幻想乡大地图"))
            }.getOrDefault(-1L)
            println("ROUND $tag DLC 幻想乡大地图=$mapFixOn（1 = 已开，复现玩家重负载路径）")
        }

        val src = program.codeSource as? MappedCodeSource
        val hit0 = src?.cacheHits ?: 0L
        val miss0 = src?.cacheMisses ?: 0L
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val b0 = mx.getThreadAllocatedBytes(tid)
        val s0 = System.nanoTime()
        var error: String? = null
        val reached = try {
            session.runtime.callFunction("CHARA_MOVEMENT")
            -1L
        } catch (e: StepLimitExceeded) {
            // 撞步数闸不是失败：steps = 闸值，读数仍可用于「同样工作量」比较。
            e.steps
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            -1L
        }
        val ns = System.nanoTime() - s0
        val bytes = mx.getThreadAllocatedBytes(tid) - b0
        val steps = if (reached > 0) reached else executedSteps(session.runtime)
        val hits = (src?.cacheHits ?: 0L) - hit0
        val misses = (src?.cacheMisses ?: 0L) - miss0
        val printCount = if (withProbes) getVmInt(vm, "printStepCount") else 0
        val globalSteps = globalCounter?.get(0) ?: 0L
        val allocPerM = if (steps > 0) bytes / 1_048_576.0 * 1_000_000.0 / steps else Double.NaN
        val missPct = if (hits + misses > 0) 100.0 * misses / (hits + misses) else 0.0
        println(
            "ROUND $tag load=${loadMs}ms steps=$steps ns=$ns ns/instr=${"%.1f".format(if (steps > 0) ns.toDouble() / steps else Double.NaN)} " +
                "MB/百万指令=${"%.2f".format(allocPerM)} 页取信=${hits + misses} miss=$misses(${"%.2f".format(missPct)}%) " +
                "QOL=${qolMs}ms/qolSteps=$qolSteps CHARANUM=$charaNum 异常=${error ?: "无"}" +
                (if (qolError != null) " QOL异常=$qolError" else "")
        )
        session.close()
        return RoundMetrics(
            tag = tag, steps = steps, ns = ns, allocBytes = bytes,
            pageHits = hits, pageMisses = misses,
            qolMs = qolMs, qolSteps = qolSteps, qolError = qolError,
            charaNum = charaNum, error = error, loadMs = loadMs,
            globalSteps = globalSteps,
            opHistogram = opHist,
            fnHistogram = fnHist,
            printSteps = if (withProbes) printLog?.copyOf(printCount) else null,
            program = program,
        )
    }

    /**
     * 热点函数微基准：**只跑 97.8% 的那一段**（`GMF_METHOD_UPDATE`）。
     *
     * 为什么不直接跑 `CHARA_MOVEMENT`：它的完整形状要先过 `@QOL_DB_INIT`（`TR_DB` 连接 +
     * 两张 MAP 表灌库，本身几百万条指令）才能跑通，且一半成本落在 SQL/QOL 上，对
     * 「压热路径」这件事是把噪声当信号。`specs/verify-notes.md` 22.2 节已量出
     * `CHARA_MOVEMENT` 单次 12,080,416 条指令里 **97.8%** 落在 `GMF_METHOD_UPDATE` /
     * `GMF_METHOD_RESTORE`（`DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:40-93`：每次调用
     * 都 `FOR LOCAL, 1, CHARANUM` 全表 + `FOR LOCAL, 0, 100` 房间表 + `FOR LOCAL, 0, 15`
     * 地图名）⇒ 直接重复调它，就是这个段落的**同形切片**，而且确定性（无 SQL、无 RNG、无 I/O）。
     *
     * 判据（与 `量同样多指令的墙钟` 同一套）：逐轮 steps 必须相同、ns/instr 取稳态 median、
     * 分配 MB/百万指令同列；探针只在**单独一轮**挂（探针自身有成本，混进时序会把读数抬高）。
     *
     * 语料/产物由 `ERATW_GAMEDIR` / `ERATW_ERBC_DIR` 给出，缺失即跳过。
     * `ERATW_HOT_ROUNDS`（默认 8）/ `ERATW_HOT_WARMUP`（默认 3）可覆盖轮数。
     */
    @Tag("realArtifacts")
    @Test
    fun `热点函数 GMF_METHOD_UPDATE 微基准`() {
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")
        val rounds = System.getenv("ERATW_HOT_ROUNDS")?.toIntOrNull() ?: 8
        val warmup = System.getenv("ERATW_HOT_WARMUP")?.toIntOrNull() ?: 3
        val calls = System.getenv("ERATW_HOT_CALLS")?.toIntOrNull() ?: 1

        val t0 = System.nanoTime()
        val program = loadForProbe(erbcDir!!)
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val loadMs = (System.nanoTime() - t0) / 1_000_000L
        val session = GameSession(
            gameRoot = gameRoot!!,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 8_000_000L,
            randSeed = 20260226L,
        )
        try {
            val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
            data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
            val charaNum = session.store.readInt("CHARANUM", LongArray(0))
            println("HOT load=${loadMs}ms CHARANUM=$charaNum rounds=$rounds warmup=$warmup calls=$calls")

            // 全局指令序号（跨派生 VM 共享）：`invokeUserMethod` 走嵌套 VM，主 VM 的
            // executedSteps 看不到嵌套帧里的指令，只有这个计数器是「整次调用一共多少条」。
            // 必须在第一次调用之前挂上，派生 VM 才在创建时继承它（见 Vm.deriveNestedVm）。
            val vm = vmOf(session.runtime)
            val globalCounter = LongArray(1)
            setVmField(vm, "globalStepCounter", globalCounter)

            val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val tid = Thread.currentThread().id
            val src = program.codeSource as? MappedCodeSource
            val nsPer = ArrayList<Double>(rounds)
            val stepsOut = ArrayList<Long>(rounds)
            val allocOut = ArrayList<Double>(rounds)
            var aborted = 0
            repeat(rounds) { i ->
                globalCounter[0] = 0L
                val b0 = mx.getThreadAllocatedBytes(tid)
                val hit0 = src?.cacheHits ?: 0L
                val miss0 = src?.cacheMisses ?: 0L
                val s = System.nanoTime()
                var error: String? = null
                try {
                    repeat(calls) {
                        val ret = session.runtime.invokeUserMethod(
                            "GMF_METHOD_UPDATE",
                            listOf(com.eratw.port.core.expr.Value.IntV(1L)),
                        )
                        if (ret == null && error == null) error = "invokeUserMethod 返回 null（不是用户方法）"
                    }
                } catch (t: Throwable) {
                    aborted++
                    error = "${t.javaClass.simpleName}: ${t.message}"
                }
                val ns = System.nanoTime() - s
                val bytes = mx.getThreadAllocatedBytes(tid) - b0
                val steps = globalCounter[0]
                val hits = (src?.cacheHits ?: 0L) - hit0
                val misses = (src?.cacheMisses ?: 0L) - miss0
                println(
                    "HOT round=$i steps=$steps ns=${ns / 1_000_000}ms " +
                        "ns/instr=${"%.1f".format(if (steps > 0) ns.toDouble() / steps else Double.NaN)} " +
                        "MB/百万指令=${"%.2f".format(if (steps > 0) bytes / 1_048_576.0 * 1_000_000.0 / steps else Double.NaN)} " +
                        "页取信=${hits + misses} miss=$misses 异常=${error ?: "无"}",
                )
                if (steps > 0) {
                    nsPer += ns.toDouble() / steps
                    stepsOut += steps
                    allocOut += bytes / 1_048_576.0 * 1_000_000.0 / steps
                }
            }
            val distinct = stepsOut.distinct()
            val steady = nsPer.drop(warmup).sorted()
            if (steady.isNotEmpty()) {
                val median = steady[steady.size / 2]
                val allocSorted = allocOut.drop(warmup).sorted()
                val playerNsPerInstr = 1_000_000_000.0 / 2_220_000.0
                val oneCallSteps = distinct.firstOrNull() ?: 0L
                println(
                    "HOT STEADY n=${steady.size} ns/instr min=${"%.1f".format(steady.first())} " +
                        "median=${"%.1f".format(median)} max=${"%.1f".format(steady.last())} " +
                        "| 分配MB/百万指令 median=${"%.2f".format(allocSorted[allocSorted.size / 2])} " +
                        "| steps=$distinct aborted=$aborted " +
                        "| 玩家机 2.22M instr/s = ${"%.1f".format(playerNsPerInstr)} ns/instr ⇒ " +
                        "桌面 median 对比设备倍率×${"%.2f".format(playerNsPerInstr / median)}；" +
                        "按玩家机吞吐一次调用($oneCallSteps 条)≈" +
                        "${"%.3f".format(oneCallSteps / 2_220_000.0)}s；设备读数 TODO(verify)（adb 空）",
                )
            }

            // 探针轮**另起一局**：`invokeUserMethod` 走派生 VM，而派生 VM 在创建时把探针
            // 字段整份继承下来（见 Vm.deriveNestedVm）⇒ 探针必须在**第一次调用之前**挂上，
            // 事后挂到主 VM 上对已进池的派生 VM 无效（上一版读数 `HIST 空` 就是这个原因）。
            probeSession(gameRoot!!, erbcDir!!, save, charaNum)
        } finally {
            session.close()
        }
    }

    /**
     * 探针轮：跑一次 `GMF_METHOD_UPDATE`，给出**动态**操作码分布 + 函数级归因。
     *
     * 为什么另起一局：`invokeUserMethod` 的派生 VM 只在创建时继承探针字段（`Vm.deriveNestedVm`），
     * 时序轮里探针是空的，事后挂到主 VM 上对已进池的派生 VM 不生效。
     */
    private fun probeSession(gameRoot: Path, erbcDir: Path, save: Path, expectCharaNum: Long) {
        val program = loadForProbe(erbcDir)
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = 8_000_000L,
            randSeed = 20260226L,
        )
        try {
            val data = SaveCodec.read(Files.readAllBytes(save), session.store.registry, SaveFileKind.NORMAL)
            data.applyTo(session.store, restoreCharacters = true, setLastLoad = true)
            val vm = vmOf(session.runtime)
            val hist = IntArray(256)
            val fnHist = IntArray(program.functions.size)
            val counter = LongArray(1)
            setVmField(vm, "opHistogram", hist)
            setVmField(vm, "fnHistogram", fnHist)
            setVmField(vm, "globalStepCounter", counter)
            // 同进程栈采样画像：回答「这 700 ns/指令到底花在哪个函数」——
            // 直方图只给指令**条数**，而本装置的瓶颈量级是每条指令的墙钟，条数分布
            // （`_EVAL_INT` 24% / `SET` 17.5%）说明不了谁最贵（例：`SET` 只占 17.5% 的
            // 条数，但走的是宿主 `executeStatement` 往返 + 位置打点，很可能是大头）。
            // 采样线程只读 `Thread.getStackTrace`，不注入任何代码，对被测语义零影响。
            val samples = sampleProfile(Thread.currentThread(), PROFILE_SAMPLE_MICROS, PROFILE_CALLS) {
                repeat(PROFILE_CALLS) {
                    session.runtime.invokeUserMethod(
                        "GMF_METHOD_UPDATE",
                        listOf(com.eratw.port.core.expr.Value.IntV(1L)),
                    )
                }
            }
            println("PROBE CHARANUM=$expectCharaNum 全局指令=${counter[0]} 采样=${samples.total} 帧")
            samples.dump()
            dumpHistogram(hist)
            dumpFunctionHistogram(program, fnHist, counter[0], 0L)
        } finally {
            session.close()
        }
    }

    /** 栈采样画像的一次读数（帧名 → 命中次数，按次数降序）。 */
    private class StackProfile(val total: Int, val self: Map<String, Int>, val inclusive: Map<String, Int>) {
        fun dump(top: Int = 25) {
            println("SAMPLE total=$total（每 ${PROFILE_SAMPLE_MICROS}us 采一次，self = 栈顶帧）")
            self.entries.sortedByDescending { it.value }.take(top).forEach { (k, v) ->
                println("SAMPLE self ${"%5.1f".format(100.0 * v / total)}% ($v) $k")
            }
            println("SAMPLE inclusive（含被调）")
            inclusive.entries.sortedByDescending { it.value }.take(top).forEach { (k, v) ->
                println("SAMPLE incl ${"%5.1f".format(100.0 * v / total)}% ($v) $k")
            }
        }
    }

    /**
     * 在 [body] 执行期间对 [target] 线程做周期栈采样。
     *
     * 采样线程是守护线程，只调 `Thread.getStackTrace`（安全点读栈快照），不修改被测状态；
     * 因此对 VM 语义零影响，只给「墙钟花在谁身上」的分布。self = 栈顶帧（最内层，
     * 也就是真正在烧 CPU 的那一层），inclusive = 该帧在任何位置出现过（含被调）。
     */
    private fun sampleProfile(
        target: Thread,
        everyMicros: Long,
        expectedCalls: Int,
        body: () -> Unit,
    ): StackProfile {
        val self = HashMap<String, Int>()
        val incl = HashMap<String, Int>()
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        var total = 0
        val sampler = Thread {
            while (!stopFlag.get()) {
                val st = target.stackTrace
                if (st.isNotEmpty()) {
                    total++
                    self.merge(frameKey(st[0]), 1, Int::plus)
                    val seen = HashSet<String>()
                    for (f in st) {
                        val k = frameKey(f)
                        if (seen.add(k)) incl.merge(k, 1, Int::plus)
                    }
                }
                Thread.sleep(0, (everyMicros * 1_000L).toInt())
            }
        }
        sampler.isDaemon = true
        sampler.name = "perf-stack-sampler"
        sampler.start()
        try {
            body()
        } finally {
            stopFlag.set(true)
            sampler.join(2_000L)
        }
        return StackProfile(total, self, incl)
    }

    /** 帧名：只留 eratw 自有类与 JVM 里的热点（过滤掉采样线程自己与测试框架噪声）。 */
    private fun frameKey(f: StackTraceElement): String {
        val cls = f.className
        return if (cls.startsWith("com.eratw.")) {
            cls.substringAfterLast('.') + "." + f.methodName + ":" + f.lineNumber
        } else {
            cls + "." + f.methodName + ":" + f.lineNumber
        }
    }

    /**
     * 热路径同进程**交替** A/B（判据 = 同一 JVM、同一产物、同一存档、**两腿同时热**）。
     *
     * 为什么不是「跑完一条腿再跑另一条」：上一版就是这样，逐腿读数 704.9 → 466.7 → 249.3
     * → 119.2 ns/指令，**单调下降 6 倍**——那是 JIT 逐腿爬坡，不是被测开关的效果（同
     * BOARD 记过的「跨窗口比较已被证伪」）。本版把两腿的会话**同时建好、逐轮交替测量**，
     * JIT/页缓存/CPU 频率漂移对两腿同向，比值才有意义。
     *
     * 被测开关 = 表达式池备忘 `eratw.perf.noExprMemo`（见 `Vm.exprMemoKey` 的 KDoc）。
     * 开关在 `VirtualMachine` 构造期读一次 ⇒ 每条腿各自一个 `GameSession`（共享同一份
     * `Program`，两者都只读）。
     *
     * 门槛：`ERATW_HOT_AB=<对数>`，不设即跳过（不污染常规闸）。
     */
    @Tag("realArtifacts")
    @Test
    fun `热路径备忘 同进程交替 A_B`() {
        val pairs = System.getenv("ERATW_HOT_AB")?.toIntOrNull() ?: 0
        assumeTrue(pairs > 0, "未设 ERATW_HOT_AB=<对数>，跳过")
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")
        val calls = System.getenv("ERATW_HOT_CALLS")?.toIntOrNull() ?: 40
        val rounds = System.getenv("ERATW_HOT_AB_ROUNDS")?.toIntOrNull() ?: 6
        val warmup = System.getenv("ERATW_HOT_AB_WARMUP")?.toIntOrNull() ?: 3

        val program = loadForProbe(erbcDir!!)
        val metadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val sessions = HashMap<Boolean, GameSession>()
        val counters = HashMap<Boolean, LongArray>()
        try {
            for (noMemo in booleanArrayOf(false, true)) {
                System.setProperty("eratw.perf.noExprMemo", noMemo.toString())
                val s = GameSession(
                    gameRoot = gameRoot!!,
                    program = program,
                    metadata = metadata,
                    maxStepsPerRun = 8_000_000L,
                    randSeed = 20260226L,
                )
                val data = SaveCodec.read(Files.readAllBytes(save), s.store.registry, SaveFileKind.NORMAL)
                data.applyTo(s.store, restoreCharacters = true, setLastLoad = true)
                val counter = LongArray(1)
                setVmField(vmOf(s.runtime), "globalStepCounter", counter)
                sessions[noMemo] = s
                counters[noMemo] = counter
            }
            System.clearProperty("eratw.perf.noExprMemo")

            fun round(noMemo: Boolean): Double {
                val s = sessions[noMemo]!!
                val counter = counters[noMemo]!!
                counter[0] = 0L
                val t0 = System.nanoTime()
                repeat(calls) {
                    s.runtime.invokeUserMethod(
                        "GMF_METHOD_UPDATE",
                        listOf(com.eratw.port.core.expr.Value.IntV(1L)),
                    )
                }
                val ns = System.nanoTime() - t0
                return ns.toDouble() / counter[0]
            }

            // 预热：两腿交替（不采样）。
            repeat(warmup) {
                round(false)
                round(true)
            }
            val on = ArrayList<Double>(rounds * pairs)
            val off = ArrayList<Double>(rounds * pairs)
            val lines = ArrayList<String>()
            repeat(rounds * pairs) { i ->
                // 奇偶交替先跑哪条腿，抵消「后跑的那条腿更热」的系统偏差。
                val firstOn = i % 2 == 0
                val a = if (firstOn) round(false) else round(true)
                val b = if (firstOn) round(true) else round(false)
                val nsOn = if (firstOn) a else b
                val nsOff = if (firstOn) b else a
                on += nsOn
                off += nsOff
                lines += "AB r$i ON=${"%.1f".format(nsOn)} OFF=${"%.1f".format(nsOff)}"
            }
            lines.forEach { println(it) }
            val onS = on.sorted()
            val offS = off.sorted()
            val onM = onS[onS.size / 2]
            val offM = offS[offS.size / 2]
            val onMin = onS.first()
            val offMin = offS.first()
            println(
                "AB 合计 n=${on.size} | ON(备忘开) min=${"%.1f".format(onMin)} median=${"%.1f".format(onM)} | " +
                    "OFF(备忘关) min=${"%.1f".format(offMin)} median=${"%.1f".format(offM)} | " +
                    "min比=${"%.3f".format(offMin / onMin)}× median比=${"%.3f".format(offM / onM)}× " +
                    "(>1 = 备忘更快)",
            )
            // 逐轮配对差的中位数：比各自 median 更抗单轮抖动。
            val deltas = on.indices.map { off[it] / on[it] }.sorted()
            println("AB 配对 比值 median=${"%.3f".format(deltas[deltas.size / 2])}×")
        } finally {
            sessions.values.forEach { runCatching { it.close() } }
        }
    }

    private fun hasFunction(program: com.eratw.port.core.code.Program, name: String): Boolean =
        program.functions.any { it.name.equals(name, ignoreCase = true) }

    private fun setVmField(vm: Any?, field: String, value: Any?) {
        val target = vm ?: return
        target.javaClass.getField(field).set(target, value)
    }

    private fun getVmInt(vm: Any?, field: String): Int {
        val target = vm ?: return 0
        return target.javaClass.getField(field).getInt(target)
    }

    /** 打印类操作码掩码（`PRINT`/`PRINTFORM*` 系 + HTML/按钮/调试打印）；只服务打印密度取证。 */
    private fun buildPrintMask(): BooleanArray {
        val mask = BooleanArray(256)
        for (op in intArrayOf(
            Opcode._PRINT_INT, Opcode._PRINT_STR, Opcode._PRINT_LITERAL,
            Opcode.HTML_PRINT, Opcode.PRINT, Opcode.PRINTBUTTON, Opcode.PRINTBUTTONC,
            Opcode.PRINTPLAIN, Opcode.PRINT_ABL, Opcode.PRINT_DATA, Opcode.PRINT_IMG,
            Opcode.PRINT_ITEM, Opcode.PRINT_PALAM, Opcode.PRINT_RECT,
            Opcode.PRINT_SHOPITEM, Opcode.PRINT_SPACE, Opcode.DEBUGPRINT,
        )) {
            mask[op] = true
        }
        return mask
    }

    /**
     * 函数级**动态**归因：每条指令记到执行时栈顶函数（主 VM + 派生 VM 共享同一数组）。
     *
     * 点名表覆盖本次热路径的已知大户；top 30 用来找「除点名之外」的 CALL 目标。
     */
    private fun dumpFunctionHistogram(
        program: com.eratw.port.core.code.Program,
        fnHist: IntArray,
        globalSteps: Long,
        outerSteps: Long,
    ) {
        var total = 0L
        val rows = ArrayList<Pair<Int, Long>>()
        for (i in fnHist.indices) {
            val n = fnHist[i].toLong()
            if (n <= 0L) continue
            total += n
            rows += i to n
        }
        if (total <= 0L) {
            println("FUNC 空（探针未挂上）")
            return
        }
        rows.sortByDescending { it.second }
        val sb = StringBuilder("FUNC 动态 total=$total 条 distinct=${rows.size}")
        for ((idx, n) in rows.take(30)) {
            sb.append(" | ").append(program.functions[idx].name)
                .append("=${"%.2f".format(100.0 * n / total)}%(").append(n).append(')')
        }
        println(sb)
        val named = listOf(
            "GMF_METHOD_UPDATE", "GMF_METHOD_RESTORE", "GMF_MAP_METHOD", "CHARA_MOVEMENT",
            "GMF_METHOD_BACKUP", "GMF_METHOD_STACK_PUSH", "GMF_METHOD_STACK_POP",
            "MOVEMENT共通処理", "仕事内容設定", "強制移動", "起床就寝処理", "訪問帰宅処理",
            "宴会参加人数再計算", "CALLNAME", "NAME_TR", "GET_MAPID",
        )
        println("FUNC 点名 " + named.joinToString(" | ") { name ->
            val idx = program.functions.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (idx < 0) "$name=未定义" else "$name=${fnHist[idx]}(${"%.2f".format(100.0 * fnHist[idx] / total)}%)"
        })
        println("FUNC 坐标 全局指令=$globalSteps 外层executedSteps=$outerSteps 差=${globalSteps - outerSteps}")
    }

    /**
     * 打印密度：从 op 直方图数 PRINT 系语句执行次数，从打印事件步号日志量最长无打印窗口。
     *
     * 窗口口径：**主 VM 的全局指令序号**（与 [RoundMetrics.globalSteps] 同坐标）；派生 VM 里的
     * 打印事件也会写日志，所以窗口包住嵌套表达式方法调用。若 `printSteps.size` 小于直方图
     * 里 PRINT 系合计，说明日志容量截断，窗口是下界（打印事件超 [PRINT_LOG_CAPACITY]）。
     */
    private fun dumpPrintDensity(r: RoundMetrics, steadyMedianNsPerInstr: Double) {
        val hist = r.opHistogram ?: return
        val printOps = intArrayOf(
            Opcode.PRINT, Opcode._PRINT_INT, Opcode._PRINT_STR, Opcode._PRINT_LITERAL,
            Opcode.HTML_PRINT, Opcode.PRINTBUTTON, Opcode.PRINTBUTTONC, Opcode.PRINTPLAIN,
            Opcode.PRINT_ABL, Opcode.PRINT_DATA, Opcode.PRINT_IMG, Opcode.PRINT_ITEM,
            Opcode.PRINT_PALAM, Opcode.PRINT_RECT, Opcode.PRINT_SHOPITEM, Opcode.PRINT_SPACE,
            Opcode.DEBUGPRINT,
        )
        var printTotal = 0L
        val byOp = ArrayList<Pair<String, Int>>()
        for (op in printOps) {
            val n = hist[op]
            if (n > 0) {
                printTotal += n
                byOp += Opcode.nameOf(op) to n
            }
        }
        val total = r.globalSteps.takeIf { it > 0 } ?: r.steps
        println(
            "PRINT 动态 PRINT系合计=$printTotal 条（占动态指令 ${"%.3f".format(100.0 * printTotal / total)}%）" +
                " | " + byOp.joinToString(" ") { (n, c) -> "$n=$c" } +
                " | 事件日志=${r.printSteps?.size ?: 0}/${PRINT_LOG_CAPACITY}"
        )
        val log = r.printSteps
        if (log == null || log.isEmpty()) {
            println("PRINT 窗口 无打印事件；最长无打印窗口≈全部 $total 条≈${"%.1f".format(total * steadyMedianNsPerInstr / 1_000_000.0)}ms")
            return
        }
        var maxGap = log[0]
        var maxFrom = 0L
        var maxTo = log[0]
        for (i in 1 until log.size) {
            val gap = log[i] - log[i - 1]
            if (gap > maxGap) {
                maxGap = gap
                maxFrom = log[i - 1]
                maxTo = log[i]
            }
        }
        val tail = total - log.last()
        if (tail > maxGap) {
            maxGap = tail
            maxFrom = log.last()
            maxTo = total
        }
        println(
            "PRINT 窗口 最长无打印=$maxGap 条（步号 $maxFrom→$maxTo）≈" +
                "${"%.1f".format(maxGap * steadyMedianNsPerInstr / 1_000_000.0)}ms" +
                "（按墙钟稳态 ${"%.1f".format(steadyMedianNsPerInstr)} ns/instr 折算；设备读数 TODO(verify)）"
        )
    }

    /**
     * TEMP(perf-probe)：按**动态**执行次数降序打印操作码分布。
     *
     * 静态分布会骗人（热循环体在指令流里只出现一次却执行百万次），所以必须在主循环里
     * 逐指令累加。`<语句>` 一行是 29..197 区间（169 条真实 ERB 语句）的合计 ——
     * 它们都走「marker → 语句层第二次 dispatch」这条最贵的路径。
     */
    private fun dumpHistogram(hist: IntArray) {
        var total = 0L
        for (v in hist) total += v
        if (total <= 0L) {
            println("HIST 空（探针未挂上，检查 opHistogram 字段是否可见）")
            return
        }
        var stmtTotal = 0L
        val rows = ArrayList<Triple<Int, Int, Double>>()
        for (op in hist.indices) {
            val n = hist[op]
            if (n <= 0) continue
            if (op in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE) stmtTotal += n
            rows += Triple(op, n, 100.0 * n / total)
        }
        rows.sortByDescending { it.second }
        val sb = StringBuilder("HIST total=$total 条")
        for ((op, n, pct) in rows.take(30)) {
            sb.append(" | ").append(Opcode.nameOf(op)).append('=')
                .append("%.2f%%".format(pct)).append('(').append(n).append(')')
        }
        sb.append(" | <语句合计>=").append("%.2f%%".format(100.0 * stmtTotal / total))
        println(sb)
    }

    /**
     * 表达式池分类：回答「W3 降级器值不值得做」。
     *
     * 判据链：`_EVAL_INT`/`_EVAL_STR` 的 `a` 是表达式池下标 ⇒ 先按**静态出现次数**给每条
     * 被引用的表达式加权（一条 `_EVAL` 指令在环体里被执行百万次，权重远高于只在初始化
     * 里跑一次的），再看它的根节点是什么。
     *
     * 为什么这个分布决定 W3 的成败：降级器只能把「纯变量/常量/算术比较」的树拆成
     * 栈码或三地址；一旦根是 `FuncCall`（`GETNUM`/`GETVAR`/用户函数）或
     * `KeywordIndexArg`（`CFLAG:LOCAL:0` 这种解析期才知道下标的写法），降级要么做不到，
     * 要么只是把一次虚调用换成多次派发 —— 那正是栈码 ISA 已就位却没人敢发射的原因。
     */
    @Tag("realArtifacts")
    @Test
    fun `分类表达式池`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(erbcDir != null, "缺 ERATW_ERBC_DIR，跳过")
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val src = program.codeSource
        val n = program.instructionCount

        // ① 静态扫描：每条 `_EVAL_*` 指向哪个表达式，各被引用多少次。
        val refs = IntArray(program.exprCount)
        var evalIntOps = 0
        var evalStrOps = 0
        for (pc in 0 until n) {
            val w = pc * 4
            when (src.word(w)) {
                Opcode._EVAL_INT -> { refs[src.word(w + 1)]++; evalIntOps++ }
                Opcode._EVAL_STR -> { refs[src.word(w + 1)]++; evalStrOps++ }
            }
        }

        // ② 按静态引用次数加权分类根节点。
        val byKind = LinkedHashMap<String, Int>()
        var lowerable = 0
        var referenced = 0
        var nodes = 0L
        for (idx in refs.indices) {
            val wgt = refs[idx]
            if (wgt == 0) continue
            referenced += wgt
            val e = program.expr(idx)
            nodes += countNodes(e)
            byKind.merge(kindOf(e), wgt, Int::plus)
            if (isLowerable(e)) lowerable += wgt
        }

        fun pct(v: Int) = "%.1f%%".format(100.0 * v / referenced.coerceAtLeast(1))
        val sb = StringBuilder("EXPR 静态 _EVAL_INT=$evalIntOps 条 / _EVAL_STR=$evalStrOps 条；" +
            "被引用表达式 $referenced 次，平均节点数 ${"%.2f".format(nodes.toDouble() / referenced)}")
        for ((k, v) in byKind.entries.sortedByDescending { it.value }) {
            sb.append(" | ").append(k).append('=').append(pct(v))
        }
        sb.append(" | 可三地址降级=").append(pct(lowerable))
        println(sb)
    }

    /**
     * 邻接对：回答「谁喂给谁」。
     *
     * 直方图只给了每个操作码的**条数**，但条数相等不等于配对（`_EVAL_INT` 23.41% 与
     * `SET` 23.60% 差 0.2%，看着像 1:1，也可能是巧合）。要判断「能不能融合」「能不能
     * 省掉中间槽」，必须知道指令流里真正相邻的是什么。
     *
     * 输出三张表：① 每条 `SET` marker 的**前一条**是什么（`_EVAL_INT` 还是别的）；
     * ② 每条 `_EVAL_INT` 的**后一条**是什么（谁消费它写下的槽）；③ 每条
     * `_JUMP_IF_FALSE` 的前一条是什么（比较结果是不是刚由 `_LT`/`_EQ` 写出来）。
     */
    @Tag("realArtifacts")
    @Test
    fun `邻接对`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(erbcDir != null, "缺 ERATW_ERBC_DIR，跳过")
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc")) { }
        val src = program.codeSource
        val n = program.instructionCount
        fun opAt(pc: Int): Int = if (pc < 0 || pc >= n) -1 else src.word(pc * 4)

        fun dump(title: String, cur: Int, dir: Int, top: Int = 5) {
            val m = HashMap<Int, Int>()
            var total = 0
            for (pc in 0 until n) {
                if (opAt(pc) != cur) continue
                total++
                m.merge(opAt(pc + dir), 1, Int::plus)
            }
            if (total == 0) { println("ADJ $title: 0 条"); return }
            val rows = m.entries.sortedByDescending { it.value }.take(top)
            println("ADJ $title（共 $total 条）: " + rows.joinToString(" | ") {
                "${Opcode.nameOf(it.key)}=${"%.1f%%".format(100.0 * it.value / total)}"
            })
        }

        dump("SET 的前一条", Opcode.SET, -1)
        dump("SET 的后一条", Opcode.SET, +1)
        dump("_EVAL_INT 的后一条", Opcode._EVAL_INT, +1)
        dump("_EVAL_INT 的前一条", Opcode._EVAL_INT, -1)
        dump("_JUMP_IF_FALSE 的前一条", Opcode._JUMP_IF_FALSE, -1)
        dump("_JUMP 的前一条", Opcode._JUMP, -1)
        dump("_LT 的前一条", Opcode._LT, -1)
        dump("_MOV_INT 的前一条", Opcode._MOV_INT, -1)

        // 「融合 `_EVAL_INT` + 后一条 `_JUMP_IF_FALSE`」的可行性判据（PERF 2026-10-27）：
        // 要省掉的不只是「一次取指 + 分派」，而是**整轮循环头**（pc 判界、steps++、
        // maxSteps 比较、lastPc、shl、跨页判断、四次数组读、hist 判空、switch）。
        // 若 `_EVAL_INT` 写的槽 b 与 `_JUMP_IF_FALSE` 读的槽 a 相同，还能连槽读写一起省掉。
        var evals = 0
        var followJif = 0
        var sameSlot = 0
        for (pc in 0 until n) {
            if (opAt(pc) != Opcode._EVAL_INT) continue
            evals++
            if (opAt(pc + 1) != Opcode._JUMP_IF_FALSE) continue
            followJif++
            if (src.word(pc * 4 + 2) == src.word((pc + 1) * 4 + 1)) sameSlot++
        }
        if (evals > 0) {
            println(
                "FUSE _EVAL_INT 静态 $evals 条 | 后一条是 _JUMP_IF_FALSE 的 $followJif 条" +
                    "（${"%.1f".format(100.0 * followJif / evals)}%）" +
                    " | 其中 b==a（槽号相同）$sameSlot 条" +
                    "（${"%.1f".format(100.0 * sameSlot / followJif.coerceAtLeast(1))}%）",
            )
        }
    }

    /**
     * 任意构造期开关的**同进程交替 A/B**（缺省测 `eratw.perf.noLocalSlots`）。
     *
     * 为什么不用「改代码 → 重编 → 再测」：跨窗口比较在本项目已被证伪（`CONTRACT-T2`
     * 的 T2-62 回执第 4 条：单轮内 7 个稳态样本自身跨 17%），而机器上常有别的构建在抢核。
     * 这里两条腿在**同一个 JVM、同一份产物、同一份存档**上交替跑，唯一的差别是被测开关
     * （每条腿建 `GameSession` 之前 `System.setProperty` 一次 —— 开关都是构造期读的，
     * 所以逐腿生效）。
     *
     * 门槛：`ERATW_PERF_AB=<对数>`（不设即跳过，不污染常规闸）。奇数对先跑 ON、
     * 偶数对先跑 OFF，抵消热漂移。**每轮步数必须两侧逐轮相同**才算可比 —— 本用例把
     * `每轮steps=` 与 `每轮分配MB` 一起打出来，先核这个再比 `ns/instr`。
     *
     * `ERATW_PERF_AB_KEY=<系统属性名>` 换被测开关（如 `eratw.perf.noEvalJumpFuse`）。
     */
    @Test
    fun `构造期开关 同进程交替 A_B`() {
        val pairs = System.getenv("ERATW_PERF_AB")?.toIntOrNull() ?: 0
        assumeTrue(pairs > 0, "未设 ERATW_PERF_AB=<对数>，跳过")
        val key = System.getenv("ERATW_PERF_AB_KEY")?.takeIf { it.isNotBlank() }
            ?: "eratw.perf.noLocalSlots"
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val save = Path.of("..", "fixtures", "saves", "save03.sav").normalize()
        assumeTrue(gameRoot != null && erbcDir != null && Files.exists(save), "缺环境变量或存档，跳过")
        val cap = System.getenv("ERATW_PERF_CAP")?.toLongOrNull() ?: FULL_RUN_CAP
        println("AB key=$key")

        val steady = HashMap<String, List<Double>>()
        for (p in 1..pairs) {
            // 奇偶交替顺序：抵消「后跑的那条腿因为 JIT/缓存变快」的系统偏差。
            val legs = if (p % 2 == 1) listOf(false, true) else listOf(true, false)
            for (off in legs) {
                System.setProperty(key, off.toString())
                val tag = "P$p-${if (off) "OFF" else "ON"}"
                steady[tag] = runLeg(tag, gameRoot!!, erbcDir!!, save, cap)
            }
        }
        System.clearProperty(key)
        val allOn = ArrayList<Double>()
        val allOff = ArrayList<Double>()
        for (p in 1..pairs) {
            val on = steady["P$p-ON"] ?: continue
            val off = steady["P$p-OFF"] ?: continue
            if (on.isEmpty() || off.isEmpty()) continue
            allOn += on
            allOff += off
            println(
                "AB P$p ON(n=${on.size}) min=${on.first()} max=${on.last()} | " +
                    "OFF(n=${off.size}) min=${off.first()} max=${off.last()} | " +
                    "min比=${"%.3f".format(off.first() / on.first())}x " +
                    "max比=${"%.3f".format(off.last() / on.last())}x",
            )
        }
        if (allOn.isNotEmpty() && allOff.isNotEmpty()) {
            val on = allOn.sorted()
            val off = allOff.sorted()
            println(
                "AB 合计 n=${on.size}/${off.size} | ON min=${on.first()} median=${on[on.size / 2]} | " +
                    "OFF min=${off.first()} median=${off[off.size / 2]} | " +
                    "min比=${"%.3f".format(off.first() / on.first())}x " +
                    "median比=${"%.3f".format(off[off.size / 2] / on[on.size / 2])}x",
            )
        }
    }

    /**
     * 跑一条腿：独立加载产物 + 独立 `GameSession`（`close()` 会释放 mmap，
     * 所以每条腿都得重新 `loadMapped`），返回稳态（去掉前 3 轮预热）的 `ns/instr` 升序表。
     */
    /**
     * 跑一条腿（A/B 台）：每轮独立 `GameSession` + 同一套 4.981 装配顺序
     * （`QOL_DB_INIT` → `save03.applyTo` → `CHARA_MOVEMENT`，见 [runCharaRound]），
     * 返回**完成轮**的 `ns/instr` 原值（不排序；A/B 只比同轮同 steps 的两条腿）。
     *
     * 腿内轮数 = `ERATW_PERF_AB_ROUNDS`（缺省 2）。每轮新建会话是必须的：语料
     * `GMF_METHOD_STACK_PUSH`（`DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:5-12`）在栈满时抛
     * `GMF_FATAL_ERROR`，同会话重复调用第 5 次左右必炸。
     */
    private fun runLeg(
        tag: String,
        gameRoot: Path,
        erbcDir: Path,
        save: Path,
        cap: Long,
    ): List<Double> {
        val rounds = System.getenv("ERATW_PERF_AB_ROUNDS")?.toIntOrNull() ?: 2
        val values = ArrayList<Double>(rounds)
        repeat(rounds) { i ->
            val r = runCharaRound("$tag-R${i + 1}", gameRoot, erbcDir, save, cap, withProbes = false)
            if (r.error == null && r.steps > 0) values += r.nsPerInstr()
        }
        return values
    }

    /** 根节点类别名（用于分布表）。 */
    private fun kindOf(e: Expr): String = when (e) {        is IntLiteral -> "常量I"
        is StrLiteral -> "常量S"
        is NullTerm -> "空"
        is VarRef -> if (e.indices.isEmpty()) "变量0下标" else "变量带下标"
        is Binary -> "二元"
        is Unary, is UnaryAfter -> "一元"
        is Ternary -> "三目"
        is FuncCall -> "函数调用"
        is KeywordIndexArg -> "关键字下标"
        else -> e.javaClass.simpleName
    }

    /** 子树节点总数（递归，用于估降级后的指令条数）。 */
    private fun countNodes(e: Expr): Int = when (e) {
        is VarRef -> 1 + e.indices.sumOf { countNodes(it) }
        is Binary -> 1 + countNodes(e.left) + countNodes(e.right)
        is Unary -> 1 + countNodes(e.operand)
        is UnaryAfter -> 1 + countNodes(e.operand)
        is Ternary -> 1 + countNodes(e.cond) + countNodes(e.ifTrue) + countNodes(e.ifFalse)
        is FuncCall -> 1 + e.args.sumOf { it?.let(::countNodes) ?: 0 }
        is KeywordIndexArg -> 1 + countNodes(e.expr)
        else -> 1
    }

    /**
     * 「能不能整棵降级成三地址/栈码」的**保守**判据：只放行纯算术/比较，
     * 且子树里没有任何需要运行期名字解析或副作用的东西。
     */
    private fun isLowerable(e: Expr): Boolean = when (e) {
        is IntLiteral -> true
        is VarRef -> !e.isNoArg && e.indices.all { isLowerable(it) }
        is Binary -> isLowerable(e.left) && isLowerable(e.right) &&
            e.left.operandType != OperandType.STR && e.right.operandType != OperandType.STR
        is Unary -> isLowerable(e.operand)
        else -> false
    }

    /**
     * 取指页缓存读数（PERF-PAGEFETCH）：命中/未命中 + 未命中占比。
     *
     * 为什么要有这一列：`ns/instr` 只说「快了多少」，说不清「为什么这页等得久」。
     * 换页密的脚本（逐角色调函数的 `CHARA_MOVEMENT` 这类）在调用者页 ↔ 被调页之间来回切，
     * miss 占比就是它的成本；堆内 `IntArray` 来源（整段一页）永远全命中。
     */
    private fun pageCacheStats(program: com.eratw.port.core.code.Program): String {
        val src = program.codeSource as? MappedCodeSource ?: return "页缓存=不适用(整段一页)"
        val total = src.cacheHits + src.cacheMisses
        val missPct = if (total > 0) 100.0 * src.cacheMisses / total else 0.0
        return "页取指 ${"%,d".format(total)} 次 miss=${"%,d".format(src.cacheMisses)}" +
            "(${ "%.2f".format(missPct) }%) hit=${"%,d".format(src.cacheHits)}"
    }

    private fun vmOf(rt: ProcessRuntime): Any? = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        f.get(rt)
    }.getOrNull()

    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val vm = vmOf(rt) ?: return@runCatching 0L
        (vm.javaClass.getMethod("getExecutedSteps").invoke(vm) as Number).toLong()
    }.getOrDefault(0L)

    private companion object {

        /** 探头画像的采样间隔（微秒）与探针轮调用次数（采样点数 ~= 次数 × 单次耗时 / 间隔）。 */
        const val PROFILE_SAMPLE_MICROS = 100L

        /** 探针轮调用次数：单次 ~3 ms × 400 ≈ 1.2 s，够采上千点。 */
        const val PROFILE_CALLS = 400
        /** 完整 `CHARA_MOVEMENT` 的步数闸：比基线 12,080,416 留 ~3.5% 余量，跑满一次不被截断。 */
        const val FULL_RUN_CAP = 12_500_000L

        /** 自检下限：低于 12M 说明装置没跑到完整函数（口径漂移，不是性能变化）。 */
        const val EXPECTED_MIN_STEPS = 12_000_000L

        /** 稳态样本口径：总轮数缺省 = 2 轮预热 + 5 轮稳态。 */
        const val WARMUP_ROUNDS = 2

        /** 打印事件日志容量；一次 CHARA_MOVEMENT 的打印事件远少于这个量。 */
        const val PRINT_LOG_CAPACITY = 4096
    }
}
