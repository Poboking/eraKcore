package com.eratw.port.boot

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.ConsoleBeginHost
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.proc.loadConfigOverrides
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * **真机形态**的开局推进自证装置（M10-PLAY）：真实语料 → `BEGIN TITLE` → 首页选单 →
 * 连续 ≥30 次「等待 → 提交 → 推进」往返且无 CodeEE。
 *
 * 与 [GameBootSmoke] 的区别只在**输入通道**：那边走 `ScriptedInputSink`（等待立即返回，
 * 无阻塞、无队列），这里走生产的 `QueueInputSink` + `ConsoleBeginHost` —— 与 `GameHost`
 * 真机装配同一对象图（语句层 `INPUT` 家族与 BEGIN 状态机共用一条阻塞队列），
 * 「UI 代理线程」按原版回车口径把 `ERATW_BOOT_INPUTS` 的脚本喂进队列：
 * - 脚本耗尽后：带默认值的 INT 取默认值、无默认取 0；STR 取默认或空串；WAIT 系空提交 ——
 *   与 `ScriptedInputSink` 的作答策略逐条同构（[GameBootSmoke] 类文档），
 *   因此两边的推进读数可以互相解释；
 * - 提交一律 `mesSkip = false`（输入法回车那一路，`MainWindow.cs:1407-1414` →
 *   `EmueraConsole.PressEnterKey(keySkip = false, ...)`，`EmueraConsole.cs:1371-1373`）；
 * - 只在「有等待且队列已空」时喂下一条 —— 队列允许先入队后取值（[QueueInputSink]
 *   类文档第 1 条），代理只是不抢跑，读数与手点一致。
 *
 * 「回合」的计量是两层：
 * 1. **往返数**（roundTrips）：一条提交被等待消费（队列从 1 回落到 0）记一次 ——
 *    「等输入 → 提交 → 脚本推进」链本身的判据；
 * 2. **完成回合数**（turnsEnded）：驱动循环里 `EVENTTURNEND` 实际被调用（`Process.cs:2530-2538`）
 *    的计数 —— 游戏日内的闭环。
 * 判据取 1（≥30），2 作报告读数：开局自定义阶段可能先吃掉若干往返，
 * 断言 2 ≥ 30 会把「菜单推进正常」误判成失败。
 *
 * 环境变量：
 * - `ERATW_CORPUS` / `ERATW_BOOT_LIMIT` / `ERATW_BOOT_MAX_STEPS`：与 [GameBootSmoke] 同口径；
 * - `ERATW_BOOT_CONSOLE_WIDTH`：控制台宽（半角格，缺省 1800）—— 传真机窄列量级（72 等）
 *   复现 `MAXWIDTH()` 系补齐算式在窄窗口下的行为；
 * - `ERATW_BOOT_INPUTS`：脚本（逗号/分号分隔整数），同 [GameBootSmoke]；**未给时缺省 `0,0`**
 *   （标题的 `TINPUT 20000,2,...` 默认值是 DEMO，会掉进 6573 帧的 BADAPPLE 动画，见 [run]）；
 * - `ERATW_BOOT_ROUNDS`：目标往返数，缺省 30；
 * - `ERATW_BOOT_PLAY_SECONDS`：墙钟预算，缺省 600（超时收口并把已推进读数写报告，
 *   「第几回合停在哪」正是本装置的产出）；
 * - `ERATW_PLAY_REPORT`：报告路径，缺省 `build/boot-play-report.md`。
 *
 * 收口形态：达标后由旁路看门狗关队列，VM 线程以 `InputSinkClosedException` 结束当前 run
 * （`stoppedBy = CLOSED`）—— 原版一次 run 在需要输入时就返回（`Process.cs:444-470`），
 * 移植侧的等待是阻塞式，run 会跨过几十条输入，驱动循环到点看不见 `roundTrips`，只能这样收。
 *
 * 存档宿主与 [GameBootSmoke] 同款最小桩（SAVEGLOBAL no-op / LOADGLOBAL false），
 * 语料只读铁律不因本装置放宽（AGENTS §1）。
 */
object BootPlayChain {

    private const val DEFAULT_ROUNDS = 30
    /** 步数闸缺省 = 生产缺省（[GameSession.DEFAULT_MAX_STEPS_PER_RUN]）：装置必须反映产品口径。 */
    private const val DEFAULT_MAX_STEPS = GameSession.DEFAULT_MAX_STEPS_PER_RUN
    private const val DEFAULT_BUDGET_SECONDS = 600L
    private const val RAND_SEED = 20260226L

    /**
     * `ERATW_BOOT_INPUTS` 未给时的缺省脚本：`0,0`（开局选「开始游戏」再确认新游戏）。
     * 依据与踩坑记录见 [run] 里喂输入那段注释。
     */
    private const val DEFAULT_INPUTS = "0,0"

    /** 目标往返数（`ERATW_BOOT_ROUNDS`，缺省 30）—— 断言与驱动共用同一个数。 */
    val targetRounds: Int
        get() = System.getenv("ERATW_BOOT_ROUNDS")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ROUNDS

    /** 驱动结果（报告与单测断言的唯一数据源）。 */
    class Outcome(
        val corpus: String,
        val compiledFiles: Int,
        val degradedByOom: Boolean,
        val roundTrips: Int,
        val turnsEnded: Int,
        val steps: Long,
        val consoleLines: Int,
        val codeEe: CodeEE?,
        val error: Throwable?,
        val stoppedBy: String,
        val lastLines: List<String>,
        val statesVisited: List<String>,
    ) {
        val passed: Boolean
            get() = codeEe == null && error == null && roundTrips >= targetRounds
    }

    fun run(): Outcome {
        val t0 = System.nanoTime()
        val budgetNs = (System.getenv("ERATW_BOOT_PLAY_SECONDS")?.toLongOrNull()
            ?.takeIf { it > 0 } ?: DEFAULT_BUDGET_SECONDS) * 1_000_000_000L
        val target = targetRounds

        val corpus = RealCorpus.load()
            ?: return Outcome(
                corpus = "(未找到语料)", compiledFiles = 0, degradedByOom = false,
                roundTrips = 0, turnsEnded = 0, steps = 0, consoleLines = 0,
                codeEe = null, error = null, stoppedBy = "CORPUS_NOT_FOUND",
                lastLines = emptyList(), statesVisited = emptyList(),
            )
        val gameRoot = corpus.root.parent ?: corpus.root
        val limit = System.getenv("ERATW_BOOT_LIMIT")?.toIntOrNull()
        // M11-E bootcfg（遗留项④）：与 [GameBootSmoke] 同款 —— 覆盖表读一次，
        // saveInBinary 与解析期三键（ParserConfig）同源取值，喂进切片 LineParser 构造。
        val bootOverrides = loadConfigOverrides(gameRoot)
        val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(bootOverrides)
        val bootParserConfig = RealCorpusCompile.parserConfigFromOverrides(bootOverrides)
        var degraded = false
        val model = try {
            RealCorpusCompile.compile(corpus, limit, saveInBinary, bootParserConfig)
        } catch (e: OutOfMemoryError) {
            if (limit != null) throw e
            System.gc()
            degraded = true
            RealCorpusCompile.compile(corpus, 400, saveInBinary, bootParserConfig)
        }
        val program = model.program
            ?: return Outcome(
                corpus = corpus.toString(), compiledFiles = model.stats.loadedFiles,
                degradedByOom = degraded, roundTrips = 0, turnsEnded = 0, steps = 0,
                consoleLines = 0, codeEe = null,
                error = IllegalStateException("Program 构建失败：${model.buildError}"),
                stoppedBy = "PROGRAM_BUILD", lastLines = emptyList(), statesVisited = emptyList(),
            )

        // ---- 生产装配（对象图对齐 GameSession；控制台用生产 LineBufferConsoleSink）----
        // `ERATW_BOOT_CONSOLE_WIDTH`：窄列复现旋钮 —— 缺省 1800（PC 基准 pc-golden-root.txt:689）；
        // 传真机量级（如 72）时，`MAXWIDTH()`（=STRLENS(DRAWLINESTR)=stBar 长度）随之变窄，
        // 语料里 `str * (MAXWIDTH()-len)` 形态的补齐算式（如 UPDATE_MAIN.ERB:956）在
        // 窄窗口下的行为与真机同形（2026-10-25「负数(-16)不能与字符串相乘」排查用）。
        val consoleWidth = System.getenv("ERATW_BOOT_CONSOLE_WIDTH")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: 1800
        val console = LineBufferConsoleSink(
            clientWidth = consoleWidth,
            printCWidth = 25, // 原版 Config.PrintCLength（语料 emuera.config 为 25）
            maxLines = 900, // 语料 emuera.config MAX HISTORY LOG LINES
        )
        val inputSink = QueueInputSink()
        val beginHost = ConsoleBeginHost(inputSink, console)
        val host = ConsoleBuiltinHost(gameRoot, console)
        val configStore = EmueraConfigParser.loadChainFromDisk(gameRoot).store
        val effective = EffectiveConfig.of(configStore)
        val gameData = GameDataLoader.load(gameRoot.resolve("CSV"))
        val store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            VarConfig(systemSaveInBinary = saveInBinary),
        )
        gameData?.let { data ->
            for (t in data.templates) store.registerTemplate(t)
            store.loadItemPrice(data.itemPrices)
            store.reloadStrNames()
        }
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
        else repeat(4) { store.characters.addBare() }
        SystemVariableBootstrap.install(
            store,
            model.globalDimDecls.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            model.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
        )
        for ((fk, spec) in model.userVarDecls) {
            runCatching { store.declareUserVar(spec, fk) }
        }
        // ERD 用户变量关键字表：与 GameSession 同源（`UseERD` 关 = 不建表，ErhLoader.cs:269-272）。
        if (effective.bool(ConfigKey.UseERD)) UserKeywordTables.install(store, gameRoot)
        // 生产存档层（M10-PLAY）：开局流第一段就要 SAVEDATA（NEWGAME.ERB:205
        // saveManageSaveGame），GameBootSmoke 的最小桩只兜 SAVEGLOBAL/LOADGLOBAL，那条
        // 真缺口在这里以**真实现**接上：写根指向 build 下的 scratch 目录（全新安装语义，
        // `loadGlobal=false` 自然成立），语料目录照旧一个字节不写（AGENTS §1 铁律）。
        val saveRoot = Path.of("build", "boot-play-save").toAbsolutePath().normalize()
        runCatching { saveRoot.toFile().deleteRecursively() } // 每次跑都从「全新安装」语义起
        Files.createDirectories(saveRoot)
        val saveRooted = RootedBuiltinHost(saveRoot)
        val saveConfig = SaveConfig(
            systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
            zipSaveData = effective.bool(ConfigKey.ZipSaveData),
            useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
        )
        SaveWiring.install(
            store,
            saveConfig,
            effective.saveDataNos,
            EmSaveSets.parse(gameRoot.resolve("CSV")),
        )
        val rt = ProcessRuntime(
            program,
            store,
            model.table,
            VmConfig(maxStepsPerRun = System.getenv("ERATW_BOOT_MAX_STEPS")?.toLongOrNull() ?: DEFAULT_MAX_STEPS),
            consoleSink = console,
            semConfig = SemConfig(printCPerLine = 5, saveDataNos = effective.saveDataNos),
            systemOps = FileSystemOps(store, RootedSaveFileIo(saveRoot, saveRooted.fileOps), saveConfig),
            builtinHost = host,
            randSeed = RAND_SEED,
            beginHost = beginHost,
            inputSink = inputSink,
        )
        rt.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                model.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
        })
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> model.dataSections[program.statement(index)] })

        // ---- UI 代理线程：按原版回车口径喂 QueueInputSink ----
        // 缺省脚本 `0,0`：语料 TITLE.ERB:84 是 `TINPUT 20000,2,0,"开始播放DEMO"`，
        // 第 2 参才是默认值（`ArgumentParser.cs:1453-1496` 的 SP_TINPUT 形参序
        // time,def,disp,timeout,mouse,canskip）⇒ 默认 2 = DEMO = `@BADAPPLE`（`:115` → `:123`），
        // 那是 6573 帧的逐帧 ASCII 动画（`:135-136` CALL LOADTEXT），单次 run 就要吃掉
        // 3,000 万条指令以上 —— 判据是「连续推进」，不是「看完动画」，所以缺省先选
        // 0（开始游戏）再答 0（TITLE.ERB:99 的 ASK_YN「那当然」⇒ :105-108 RESETDATA +
        // ADDCHARA 0 + BEGIN FIRST）。要专门跑 DEMO 就显式给 `ERATW_BOOT_INPUTS=2`。
        val script = ArrayDeque(
            (System.getenv("ERATW_BOOT_INPUTS")?.takeIf { it.isNotBlank() } ?: DEFAULT_INPUTS)
                .split(',', ';')
                .mapNotNull { it.trim().toLongOrNull() },
        )
        val feeder = Feeder(inputSink, script)
        val feederThread = Thread({ feeder.loop() }, "eratw-play-feeder").apply { isDaemon = true }
        feederThread.start()

        // ---- 看门狗：达到目标往返立即收口 ----
        // 原版一次 run 的边界是 `console.IsRunning`（`Process.cs:444-470` 的 DoScript 外层 while），
        // 需要输入时 run 就返回；本移植的等待是**阻塞式**（VM 线程停在 `waitInput` 里），
        // 一次 run 会跨过几十上百条输入，驱动循环要到 run 返回才看得见 `roundTrips`。
        // 所以到点必须由旁路把队列关掉：VM 线程随即抛 InputSinkClosedException，
        // 本装置把它记成 `CLOSED`（既不是异常读数也不算 CodeEE）。
        val watchdog = Thread({
            while (!feeder.isStopped && feeder.roundTrips < target) {
                try {
                    Thread.sleep(20L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            if (feeder.roundTrips >= target) inputSink.close()
        }, "eratw-play-watchdog").apply { isDaemon = true }
        watchdog.start()

        // ---- 驱动：begin(TITLE) → resumeSystem 循环（GameHost.drive 的同一形态） ----
        var stoppedBy = "TARGET_ROUNDS"
        var error: Throwable? = null
        var codeEe: CodeEE? = null
        val states = ArrayList<String>()
        var turnsEnded = 0
        // 单次 run 的步数每次 run 开头清零、收尾（含异常退出）由 execute 的 finally 落数，
        // 所以逐次累加每次 handler 之后的读数即可；CodeEE 那一步也带真实步数。
        var stepsTotal = 0L
        try {
            rt.begin(BeginType.TITLE)
            stepsTotal += executedSteps(rt)
            states += "TITLE"
            while (true) {
                if (feeder.roundTrips >= target) break
                if (System.nanoTime() - t0 > budgetNs) {
                    stoppedBy = "BUDGET"
                    break
                }
                val step = rt.resumeSystem()
                stepsTotal += executedSteps(rt)
                // 脚本 QUIT 在 VM 内同步收栈，对状态机不可见（原版 ConsoleState.Quit 的
                // IsRunning=false 形态，EmueraConsole.cs:731-734 + :270-284）：粘滞位由
                // ProcessRuntime 记账，宿主泵据此停转（GameHost.drive 同形）。
                if (rt.quitRequested) {
                    stoppedBy = "QUIT"
                    break
                }
                if (step.calledFunction == "EVENTTURNEND") turnsEnded++
                if (states.lastOrNull() != step.to.name) states += step.to.name
                when (step.outcome) {
                    StepOutcome.FAILED -> {
                        stoppedBy = "SCRIPT_END（Normal 的官方失败口径：脚本栈空且无挂起 BEGIN，Process.cs:2959-2962）"
                        break
                    }
                    StepOutcome.BLOCKED -> {
                        stoppedBy = "BLOCKED:" + (step.blocker?.kind?.name ?: "?") +
                            " " + (step.blocker?.message ?: "")
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: CodeEE) {
            codeEe = e
            stoppedBy = "CodeEE"
        } catch (e: InputSinkClosedException) {
            stoppedBy = "CLOSED"
        } catch (t: Throwable) {
            error = t
            stoppedBy = t.javaClass.simpleName
        } finally {
            feeder.stop()
            inputSink.close()
        }

        val outcome = Outcome(
            corpus = corpus.toString(),
            compiledFiles = model.stats.loadedFiles,
            degradedByOom = degraded,
            roundTrips = feeder.roundTrips,
            turnsEnded = turnsEnded,
            steps = stepsTotal,
            consoleLines = console.snapshot().size,
            codeEe = codeEe,
            error = error,
            stoppedBy = stoppedBy,
            lastLines = console.snapshot().takeLast(12).map { line ->
                line.segments.joinToString("") { it.text }
            },
            statesVisited = states.take(60),
        )
        writeReport(render(outcome))
        return outcome
    }

    /**
     * UI 代理（单线程喂队列、串行提交）。
     *
     * 往返计数：一条提交被等待消费 = 队列从 1 回落到 0（本代理保证队列里至多一条待发，
     * 因此「消费」与「完成一次等待」一一对应；限时超时那条例外，超时未消费则继续等，
     * 下一条等待把它取走，计数仍一致）。
     */
    private class Feeder(
        private val sink: QueueInputSink,
        private val script: ArrayDeque<Long>,
    ) {
        @Volatile
        var roundTrips: Int = 0
            private set

        @Volatile
        private var stopped = false

        /** 看门狗用它判断代理是否还在跑（见 [run] 里的收口说明）。 */
        val isStopped: Boolean get() = stopped

        fun stop() {
            stopped = true
        }

        fun loop() {
            var awaitingLastSubmission = false
            while (!stopped) {
                try {
                    if (awaitingLastSubmission && sink.pendingCount == 0) {
                        roundTrips++
                        awaitingLastSubmission = false
                    }
                    if (!awaitingLastSubmission && sink.isAwaiting && sink.pendingCount == 0) {
                        val req: InputRequest? = sink.awaiting
                        val text = when {
                            req == null || sink.awaitingAnyKey -> ""
                            req.type == InputRequest.InputType.INT -> {
                                val scripted = script.removeFirstOrNull()
                                (scripted ?: (if (req.hasDef) req.defInt else 0L)).toString()
                            }
                            else -> if (req.hasDef) req.defStr else ""
                        }
                        // 回车那一路 mesSkip = false（MainWindow.cs:1407-1414 → EmueraConsole.cs:1371-1373）
                        if (sink.submit(text)) awaitingLastSubmission = true
                    }
                    Thread.sleep(1L)
                } catch (e: InterruptedException) {
                    return
                } catch (t: Throwable) {
                    stopped = true
                }
            }
        }
    }

    /** 反射读 `ProcessRuntime.vm` 的 `executedSteps`（与 [GameBootSmoke] 同口径的装置限制）。 */
    private fun executedSteps(rt: ProcessRuntime): Long = runCatching {
        val f = ProcessRuntime::class.java.getDeclaredField("vm")
        f.isAccessible = true
        val vm = f.get(rt) ?: return@runCatching 0L
        val getter = vm.javaClass.getMethod("getExecutedSteps")
        (getter.invoke(vm) as Number).toLong()
    }.getOrDefault(0L)

    private fun reportPath(): Path {
        val override = System.getenv("ERATW_PLAY_REPORT")?.trim()?.takeIf { it.isNotEmpty() }
        return if (override != null) Path.of(override).toAbsolutePath().normalize()
        else Path.of("build", "boot-play-report.md").toAbsolutePath().normalize()
    }

    private fun writeReport(text: String) {
        runCatching {
            val p = reportPath()
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
    }

    private fun render(o: Outcome): String = buildString {
        appendLine("# 真机形态开局推进自证（bootPlayChain）")
        appendLine()
        appendLine("- 语料: `${o.corpus}`（装载 ${o.compiledFiles} 文件${if (o.degradedByOom) "，**OOM 降级切片**" else ""}）")
        appendLine("- 目标往返: $targetRounds；实际往返: **${o.roundTrips}**；完成回合(EVENTTURNEND): ${o.turnsEnded}")
        appendLine("- 指令数: ${o.steps}；控制台行数: ${o.consoleLines}")
        appendLine("- 指令数口径: 只累加**正常收尾**的 run（`Vm.kt:268/327` 在 run 开头清零、只在返回时写数），")
        appendLine("  被 CodeEE 或看门狗关队列打断的那一次不计入 ⇒ 它是下限而不是全量。")
        appendLine("- 收口原因: `${o.stoppedBy}`")
        appendLine("- CodeEE: ${o.codeEe?.let { "${it.message}（函数=${it.funcName} 行=${it.line} pc=${it.pc}）" } ?: "无"}")
        appendLine("- 其它异常: ${o.error?.let { it::class.simpleName + ": " + it.message } ?: "无"}")
        appendLine("- 结果: **${if (o.passed) "PASS" else "FAIL"}**")
        appendLine()
        appendLine("## 状态序列（按访问序去重，前 60）")
        appendLine()
        appendLine("```text")
        o.statesVisited.forEach { appendLine(it) }
        appendLine("```")
        appendLine()
        appendLine("## 控制台尾部 12 行")
        appendLine()
        appendLine("```text")
        o.lastLines.forEach { appendLine(it) }
        appendLine("```")
    }
}
