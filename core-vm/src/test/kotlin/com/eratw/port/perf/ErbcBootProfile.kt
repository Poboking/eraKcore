package com.eratw.port.perf

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 真机同款冷启链路的桌面计时（2026-10-23，**只测量，不改语义**）。
 *
 * ## 为什么要这个探针
 *
 * 负责人实测「每次装载 erbc 要两分钟」「引擎一页 44 秒」。`LoadTimingProbe` 只量了
 * `ProgramLoader.loadMapped` + `BootMetadata.read` 两段；缺的是**之后**的三段：
 * `GameSession` 装配（CSV 总装 + 27,977 条 #DIM + ProcessRuntime 建表）、
 * `begin(TITLE)` 到首个阻塞点（含游戏脚本自己的资源初始化）、以及它们各占多少。
 *
 * 本探针按**真机装配顺序**跑（`GameHost → GameSession` 的参数面一致），逐段打毫秒，
 * 输出可直接对照真机 `logcat` 的 `load.* times=` 行。JVM 绝对值 ≠ ART，但**段间比例**
 * 是同一份代码、同一份产物，用来排序「极端优化」的落点。
 *
 * ## 用法
 *
 * ```
 * ERATW_ERBC_DIR=<含 program.erbc/program.meta 的目录> \
 * ERATW_GAMEDIR=<游戏根> \
 * ./gradlew --configure-on-demand :core-vm:erbcBootProfile
 * ```
 *
 * 缺产物时打印提示并退出 0（不污染闸门）。
 */
fun main() {
    val erbcDir = findErbcDir()
    if (erbcDir == null) {
        println("[boot-profile] 未找到 erbc 产物（设 ERATW_ERBC_DIR 指向含 program.erbc 的目录）")
        exitProcess(0)
    }
    val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { Path.of(it) }
    if (gameRoot == null || !Files.isDirectory(gameRoot)) {
        println("[boot-profile] 未找到游戏根（设 ERATW_GAMEDIR）")
        exitProcess(0)
    }
    val programFile = erbcDir.resolve("program.erbc")
    val metaFile = erbcDir.resolve("program.meta")
    if (!Files.isRegularFile(programFile)) {
        println("[boot-profile] 缺 program.erbc：$programFile")
        exitProcess(0)
    }
    println("[boot-profile] erbc=$programFile (${Files.size(programFile) / (1024 * 1024)} MiB)")
    println("[boot-profile] meta=${if (Files.isRegularFile(metaFile)) "${Files.size(metaFile) / (1024 * 1024)} MiB" else "缺失"}")
    println("[boot-profile] gameRoot=$gameRoot")
    println("[boot-profile] jvm=${System.getProperty("java.version")} maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB")

    // ---- 1) Program（真机同款惰性 mmap 分段解码）----
    val stageMs = LinkedHashMap<String, Long>()
    var last = System.nanoTime()
    val mark = LoadStageMark { stage ->
        val now = System.nanoTime()
        stageMs[stage] = (now - last) / 1_000_000L
        last = now
    }
    val tProgram = System.nanoTime()
    val program = ProgramLoader.loadMapped(programFile, mark)
    val programMs = (System.nanoTime() - tProgram) / 1_000_000L

    // ---- 2) meta（49MB 侧车解析）----
    val tMeta = System.nanoTime()
    val metadata = if (Files.isRegularFile(metaFile)) BootMetadata.read(metaFile) else error("缺 program.meta")
    val metaMs = (System.nanoTime() - tMeta) / 1_000_000L

    // ---- 3) 会话装配（CSV 总装 + #DIM 安装 + ProcessRuntime 建表）----
    val tSession = System.nanoTime()
    val session = GameSession(
        gameRoot = gameRoot,
        program = program,
        metadata = metadata,
        consoleWidth = 199,
    )
    val sessionMs = (System.nanoTime() - tSession) / 1_000_000L

    // ---- 4) begin(TITLE) → 首个阻塞点（游戏脚本资源初始化在这一段）----
    // 放在单独线程里跑：脚本可能阻塞在输入等待（TINPUT），用 join 超时兜底。
    var titleMs = -1L
    var titleErr: Throwable? = null
    var titleLines = 0
    val tTitle = System.nanoTime()
    val th = Thread {
        try {
            session.runtime.begin(BeginType.TITLE)
        } catch (t: Throwable) {
            titleErr = t
        }
    }.apply { name = "boot-profile-title"; isDaemon = true; start() }

    // 穷采样器：每 25ms 抓一次 TITLE 线程栈，聚合叶子帧 —— 「一页 44s」到底烧在哪。
    val leafCounts = LinkedHashMap<String, Int>()
    val deepCounts = LinkedHashMap<String, Int>()
    val sampler = Thread {
        while (th.isAlive && !th.isInterrupted) {
            val st = th.stackTrace
            if (st.isNotEmpty()) {
                val leaf = st[0]
                val leafKey = "${leaf.className.substringAfterLast('.')}.${leaf.methodName}"
                leafCounts[leafKey] = (leafCounts[leafKey] ?: 0) + 1
                if (st.size >= 3) {
                    val deep = (0..2).joinToString(" <- ") {
                        "${st[it].className.substringAfterLast('.')}.${st[it].methodName}"
                    }
                    deepCounts[deep] = (deepCounts[deep] ?: 0) + 1
                }
            }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }.apply { name = "boot-profile-sampler"; isDaemon = true; start() }

    th.join(180_000L)
    titleMs = (System.nanoTime() - tTitle) / 1_000_000L
    titleLines = session.console.snapshot().size
    val titleBlocked = th.isAlive
    sampler.interrupt()

    // ---- 5) 翻页计时：脚本停在输入等待后，每提交一次输入走到下一个稳定等待点算一页 ----
    // 真机「一页 44s」就是这一段在设备上的耗时；桌面数字按 ART/JIT 差放大后对照。
    val pageTimes = ArrayList<Pair<Int, Long>>()
    if (titleBlocked) {
        repeat(8) { round ->
            Thread.sleep(300)
            val before = session.console.snapshot().size
            val t = System.nanoTime()
            session.inputSink.submit("0")
            var lastCount = before
            var stableSince = System.nanoTime()
            while (th.isAlive && (System.nanoTime() - t) < 120_000_000_000L) {
                Thread.sleep(20)
                val c = session.console.snapshot().size
                if (c != lastCount) {
                    lastCount = c
                    stableSince = System.nanoTime()
                } else if (System.nanoTime() - stableSince > 300_000_000L && th.state == Thread.State.WAITING) {
                    break
                }
            }
            val ms = (System.nanoTime() - t) / 1_000_000L
            pageTimes += (lastCount - before) to ms
            if (!th.isAlive) return@repeat
        }
    }

    println()
    println("| 段 | 耗时 |")
    println("|---|---:|")
    println("| program.erbc（loadMapped） | **${programMs} ms** |")
    stageMs.forEach { (k, ms) -> println("| &nbsp;&nbsp;${k} | ${ms} ms |") }
    println("| program.meta（49MB 解析） | **${metaMs} ms** |")
    println("| GameSession 装配（CSV+#DIM+运行期） | **${sessionMs} ms** |")
    println("| begin(TITLE) → 首个阻塞点 | **${titleMs} ms**${if (titleBlocked) "（超时仍在跑）" else ""} |")
    println("| 其中控制台行数 | ${titleLines} 行 |")
    println(
        "| TITLE 结果 | " +
            (titleErr?.let { "${it::class.simpleName}: ${it.message}" } ?: if (titleBlocked) "阻塞中" else "正常返回") +
            " |",
    )
    println()
    val total = programMs + metaMs + sessionMs + titleMs
    println("[boot-profile] 合计 ${total} ms（program ${programMs} + meta ${metaMs} + session ${sessionMs} + TITLE ${titleMs}）")
    println("[boot-profile] meta 段占装载 ${pct(metaMs, programMs + metaMs + sessionMs)}%，session 段占 ${pct(sessionMs, programMs + metaMs + sessionMs)}%")

    // ---- 热点：TITLE 线程叶子帧 top 20 ----
    println()
    println("### 翻页耗时（每次提交 `0` → 下一个稳定等待点）")
    println("| 页 | 新增行 | 耗时 |")
    println("|---:|---:|---:|")
    pageTimes.forEachIndexed { i, (lines, ms) -> println("| ${i + 1} | $lines | **${ms} ms** |") }
    if (pageTimes.isEmpty()) {
        println("| - | - | TITLE 未进入输入等待，未测 |")
    }
    println()
    println("### TITLE 线程热点（25ms 采样，叶子帧 top 20）")
    println("| 次数 | 叶子帧 |")
    println("|---:|---|")
    leafCounts.entries.sortedByDescending { it.value }.take(20).forEach { (k, v) ->
        println("| $v | `$k` |")
    }
    println()
    println("### TITLE 线程热点（栈顶三层，top 15）")
    println("| 次数 | 栈 |")
    println("|---:|---|")
    deepCounts.entries.sortedByDescending { it.value }.take(15).forEach { (k, v) ->
        println("| $v | `$k` |")
    }

    // 释放会话避免 daemon 线程持堆退出时报 OOM（探针不关心退出码）
    runCatching { session.close() }
    exitProcess(0)
}

private fun pct(part: Long, total: Long): Int = if (total <= 0) 0 else (part * 100 / total).toInt()

/** 与 `LoadTimingProbe`/`PoolDecompAuditTest` 同一套查找顺序。 */
private fun findErbcDir(): Path? {
    System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
    System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
    val candidates = listOf(
        Path.of("build", "erbc-out", "erbc.v1"),
        Path.of("build", "erbc.v1"),
        Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
    )
    return candidates.firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
}
