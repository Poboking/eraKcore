package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.ErbcArtifacts
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * explore 驱动器与输入宿主（CONTRACT-DBG6 §4.E1.7 的 ①②④ + tried 护栏 / 墙钟 / 步数闸）：
 * - ① sink 翻译矩阵：FeedInt / asTimeout / FeedStr / PassAnyKey / Abandon / 非法组合 →
 *   InputOutcome 各形态与异常逐个断言；
 * - ② 快照栈分支：合成语料造「输入分岔」函数，全排列逐支走完、每支输入链逐字可重放、
 *   无重放分歧；
 * - ④ 预算旋钮：max-episodes=2 恰停两局；wall-min 折 WALL_LIMIT；步数闸折 STEP_LIMIT。
 * 全部用 [ExploreTestCorpus] 合成语料，不碰真语料、不依赖 ERATW_* 环境变量。
 */
class ExploreTest {

    private lateinit var tmp: Path
    private lateinit var built: ExploreTestCorpus.Built
    private lateinit var work: Path
    private lateinit var outDir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-explore-test")
        built = ExploreTestCorpus.build(tmp, withSystemTitle = true)
        work = tmp.resolve("work")
        outDir = tmp.resolve("explore-out")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 组装一个接好 explore sink 的会话（Assembler 的 sinkOverride 注入口）。 */
    private fun assemble(sink: ExploreInputSink): Assembler.Assembled =
        Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)

    /** 直调计划的驱动器；findings 记进 [findingKinds] 供断言。 */
    private fun newDriver(
        assembled: Assembler.Assembled,
        sink: ExploreInputSink,
        plan: ExplorePlan,
        policy: ExplorePolicy,
        findingKinds: MutableList<String>,
        maxEpisodes: Int = 50,
        wallMs: Long = 30_000,
        maxDepth: Int = 8,
        policyProvider: (() -> ExplorePolicy)? = null,
    ): ExploreDriver =
        ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = maxEpisodes, wallMs = wallMs, maxDepth = maxDepth, outDir = outDir),
            findings = FindingSink { kind, _, _, _ -> findingKinds.add(kind) },
            plan = plan,
            policyProvider = policyProvider ?: { policy },
            err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
        )

    /**
     * 按键序计数策略：每个「栈顶指纹」（EX_FORK 的两个 INPUT 各有独立 pc，VmStackReader 可分辨）
     * 维护访问计数，第 n 次访问按 [byVisit] 给动作。deterministic（同键同 visit ⇒ 同决定）。
     */
    private class CountingPolicy(private val byVisit: (Int) -> ExploreAction) : ExplorePolicy {
        private val visits = HashMap<String, Int>()
        override fun choose(wp: WaitPoint): ExploreAction {
            val key = wp.stackTop
            val n = visits.getOrDefault(key, 0)
            visits[key] = n + 1
            return byVisit(n)
        }
        override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
    }

    private fun exhaustiveScript(): CountingPolicy =
        CountingPolicy { visit ->
            when (visit) {
                0 -> ExploreAction.FeedInt(1L)
                1 -> ExploreAction.FeedInt(2L)
                else -> ExploreAction.Abandon("exhausted")
            }
        }

    // ------------------------------------------------------------------
    // ① sink 翻译矩阵
    // ------------------------------------------------------------------

    @Test
    fun sinkTranslationMatrix() {
        val sink = ExploreInputSink()
        val observed = ArrayList<Pair<WaitPoint, ExploreAction>>()
        val fakeEnv = object : WaitEnv {
            override fun consoleTail(max: Int): List<String> = listOf("line-a", "line-b")
            override fun drainButtons(): List<ButtonOpt> = listOf(ButtonOpt("按钮三", 3L))
            override fun stackTop(): String = "EX_FORK:12"
        }
        val queue = ArrayDeque<ExploreAction>()
        sink.hooks = object : ExploreHooks {
            override fun env(): WaitEnv = fakeEnv
            override fun decide(wp: WaitPoint): ExploreAction = queue.removeFirst()
            override fun onObserved(wp: WaitPoint, action: ExploreAction) {
                observed.add(wp to action)
            }
        }
        fun intRequest() = InputRequest(type = InputRequest.InputType.INT)
        fun strRequest() = InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "def")

        // FeedInt → InputOutcome(intValue=v)，wp 全量观测（request 原样 + 按钮集 + 尾 + 栈顶）
        queue.add(ExploreAction.FeedInt(5L))
        assertEquals(InputOutcome(intValue = 5L), sink.waitInput(intRequest()))
        val (wp1, act1) = observed[0]
        assertEquals(false, wp1.anyKey)
        assertEquals(intRequest(), wp1.request)
        assertEquals(1, wp1.buttons.size)
        assertEquals(3L, wp1.buttons[0].value)
        assertEquals(listOf("line-a", "line-b"), wp1.consoleTail)
        assertEquals("EX_FORK:12", wp1.stackTop)
        assertEquals(ExploreAction.FeedInt(5L), act1)

        // FeedInt(asTimeout) → isTimeout=true（超时模拟 = 交付值 + isTimeout，InputSink.kt:126-135）
        queue.add(ExploreAction.FeedInt(2L, asTimeout = true))
        assertEquals(InputOutcome(intValue = 2L, isTimeout = true), sink.waitInput(intRequest()))

        // FeedStr → InputOutcome(strValue=s)，仅 STR 请求合法
        queue.add(ExploreAction.FeedStr("回答"))
        assertEquals(InputOutcome(strValue = "回答"), sink.waitInput(strRequest()))

        // PassAnyKey 经 readAnyKey（anyKey=true）→ 正常放行、无返回值
        queue.add(ExploreAction.PassAnyKey)
        sink.readAnyKey(anyKey = true)
        val (wp4, _) = observed[3]
        assertEquals(true, wp4.anyKey)
        assertEquals(InputRequest.RequestForm.ANY_KEY, wp4.request.form)

        // Abandon → ExploreAbandon（无发现类别；终局由驱动器折 ABANDONED）
        queue.add(ExploreAction.Abandon("不玩了"))
        val abandon = assertFailsWith<ExploreAbandon> { sink.waitInput(intRequest()) }
        assertEquals("不玩了", abandon.message)
        assertEquals(null, abandon.findingKind)

        // 非法组合四连：全部按 Abandon 处理并携带 ILLEGAL_ACTION 发现类别（CONTRACT-DBG6 §3）
        queue.add(ExploreAction.PassAnyKey)
        val illegal1 = assertFailsWith<ExploreAbandon> { sink.waitInput(intRequest()) }
        assertEquals("ILLEGAL_ACTION", illegal1.findingKind)

        queue.add(ExploreAction.FeedStr("x"))
        val illegal2 = assertFailsWith<ExploreAbandon> { sink.waitInput(intRequest()) }
        assertEquals("ILLEGAL_ACTION", illegal2.findingKind)

        queue.add(ExploreAction.FeedInt(1L))
        val illegal3 = assertFailsWith<ExploreAbandon> { sink.waitInput(strRequest()) }
        assertEquals("ILLEGAL_ACTION", illegal3.findingKind)

        queue.add(ExploreAction.FeedInt(1L))
        val illegal4 = assertFailsWith<ExploreAbandon> { sink.readAnyKey(anyKey = true) }
        assertEquals("ILLEGAL_ACTION", illegal4.findingKind)
    }

    // ------------------------------------------------------------------
    // ② 快照栈分支：全排列逐支 + 输入链可重放
    // ------------------------------------------------------------------

    @Test
    fun forkBranchesExploredWithReplayableChains() {
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val findingKinds = ArrayList<String>()
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policy = exhaustiveScript(),
            findingKinds = findingKinds,
        )
        val result = driver.run()

        // 五局：根(1,1) → 弹 T2 重放[1] 喂(1,2) → 弹 T2' 重放[1] 耗尽弃局 → 弹 T1 重放[] 喂(2)
        // → 弹 T1' 重放[] 耗尽弃局。快照栈空 → 收工。
        assertEquals(5, result.episodes, driver.episodeLogs.joinToString { it.outcome.name + ";" })
        assertEquals(
            listOf("EPISODE_DONE", "EPISODE_DONE", "ABANDONED", "EPISODE_DONE", "ABANDONED"),
            driver.episodeLogs.map { it.outcome.name },
        )
        // 三支输出全部现身（ex-a1b1 / ex-a1b2 / ex-other），每支都走到 ex-end 收尾
        val allTail = driver.episodeLogs.flatMap { it.tail }.joinToString("\n")
        assertTrue("ex-a1b1" in allTail, allTail)
        assertTrue("ex-a1b2" in allTail, allTail)
        assertTrue("ex-other" in allTail, allTail)
        assertTrue(driver.episodeLogs.all { l -> l.tail.any { it.contains("ex-end") } }, allTail)

        // 每支输入链逐字落账（actionLabel 口径：数字原样）——可直接喂回驱动器重放
        val chains = driver.episodeLogs.map { it.chain }
        assertTrue(listOf("1", "1") in chains, chains.toString())
        assertTrue(listOf("1", "2") in chains, chains.toString())
        assertTrue(listOf("2") in chains, chains.toString())
        assertTrue(emptyList<String>() in chains, chains.toString())

        // 无重放分歧、无崩溃（重放与首局分歧会记 REPLAY_DIVERGE 并弃局，见驱动器 decideLocked）
        assertTrue(findingKinds.isEmpty(), findingKinds.toString())

        // onOutcome 只回喂自由决定：每局至多贡献一次 ADVANCED（重放段不喂，CONTRACT-DBG6 §3 语义）
        val advanced = result.outcomeCounts[OutcomeKind.ADVANCED] ?: 0
        assertTrue(advanced >= 1, result.outcomeCounts.toString())
    }

    @Test
    fun triedGuardStopsAmnesiacPolicy() {
        // 恒回同一动作的「无记忆策略」：重放回分支点再给同一动作 → tried 护栏立即弃局，
        // 探索在有限局内收敛（不无限打转，CONTRACT-DBG6 §4.E1.2 的终止性）。
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val findingKinds = ArrayList<String>()
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policy = CountingPolicy { ExploreAction.FeedInt(1L) },
            findingKinds = findingKinds,
            maxEpisodes = 50,
        )
        val result = driver.run()
        assertEquals(3, result.episodes, driver.episodeLogs.joinToString { it.outcome.name + ";" })
        assertTrue(findingKinds.none { it == "CRASH" }, findingKinds.toString())
    }

    // ------------------------------------------------------------------
    // ④ 预算旋钮
    // ------------------------------------------------------------------

    @Test
    fun maxEpisodesCapStopsExploration() {
        // max-episodes=2： fork 语料的全排列需要 5 局，恰停两局（栈里还有任务没弹完）。
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policy = exhaustiveScript(),
            findingKinds = ArrayList(),
            maxEpisodes = 2,
        )
        val result = driver.run()
        assertEquals(2, result.episodes)
        assertEquals(2, driver.episodeLogs.size)
    }

    @Test
    fun wallBudgetTripsOnInputLoop() {
        // EX_LOOP 每圈一次 INPUT（输入是让出边界，步数闸永不触发）→ 墙钟兜底折 WALL_LIMIT。
        // 每个 INPUT 都是候选可判的分支点：局内逐个入栈，墙钟断局后按 max-episodes 逐支弹栈，
        // 每支都再次墙钟断局——恰跑满 max-episodes=3 局，局局 WALL_LIMIT。
        //
        // 策略必须**按访问序变值**（FeedInt(visit)），不能用恒同动作：EX_LOOP 全部 INPUT 是
        // 同一条语句（同 pc = 同指纹），弹栈重放耗尽后回到分支点时，恒同动作会先撞 tried
        // 护栏折成 ABANDONED（第三轮实锤 [WALL_LIMIT, ABANDONED, ABANDONED]）——那是护栏的
        // 正当行为（triedGuardStopsAmnesiacPolicy 专测它）；本测要让墙钟当终结者，
        // 重访分支点就得给新动作（FeedInt(visit) 恒不同于该点已试值，护栏永不误触）。
        //
        // 预算 2000ms：驱动器在播种后做 save/load 自往返暖机（ExploreDriver.run），冷启动
        // 不再吃局内预算；但仍要给足余量，避免首局的遗留开销把某一局在首个检查点前掐死
        // （二轮 200ms 实锤：第二局 restore 冷启动超预算 → 零 push → 栈空 → 只跑 2 局）。
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val findingKinds = ArrayList<String>()
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_LOOP", LongArray(0), emptyArray()),
            policy = CountingPolicy { visit -> ExploreAction.FeedInt(visit.toLong()) },
            findingKinds = findingKinds,
            maxEpisodes = 3,
            wallMs = 2000,
        )
        val result = driver.run()
        assertEquals(3, result.episodes, driver.episodeLogs.joinToString { it.outcome.name + ";" })
        assertTrue(
            driver.episodeLogs.all { it.outcome == OutcomeKind.WALL_LIMIT },
            driver.episodeLogs.map { it.outcome.name }.toString(),
        )
        assertTrue("WALL_LIMIT" in findingKinds, findingKinds.toString())
    }

    @Test
    fun stepLimitTripsOnBusyLoop() {
        // EX_SPIN 纯 CPU 空转不碰输入 → 会话步数闸（--steps 透传；直调缺省 5,000,000）折 STEP_LIMIT。
        val sink = ExploreInputSink()
        val stepsArgs = Args.parse(
            listOf(
                "--erbc", built.erbcDir.toString(), "--game", built.gameRoot.toString(),
                "--work", work.toString(), "--steps", "1000",
            ),
        )
        val assembled = Assembler.assemble(stepsArgs, sinkOverride = sink)
        val findingKinds = ArrayList<String>()
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_SPIN", LongArray(0), emptyArray()),
            policy = CountingPolicy { ExploreAction.FeedInt(0L) },
            findingKinds = findingKinds,
        )
        val result = driver.run()
        assertEquals(1, result.episodes)
        assertEquals("STEP_LIMIT", driver.episodeLogs.single().outcome.name)
        assertTrue("STEP_LIMIT" in findingKinds, findingKinds.toString())
    }

    // ------------------------------------------------------------------
    // 种子往返
    // ------------------------------------------------------------------

    @Test
    fun seedSnapshotRoundTrip() {
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val session = assembled.session
        val stateName = session.runtime.systemState.name
        val snap = SnapShots.take(session, assembled.sandboxDir, stateName)
        SnapShots.saveSeed(outDir, snap, Cli.RAND_SEED)
        val loaded = SnapShots.loadSeed(outDir.resolve("seed.dbg"))
        assertTrue(snap.bytes.contentEquals(loaded.bytes), "种子字节应逐字节一致")
        assertTrue(snap.rng.contentEquals(loaded.rng), "RNG 状态应一致")
        assertEquals(stateName, loaded.systemStateName)
        // 还原后变量面回到快照点：MONEY 应等于快照时的读数（装配态即种子的一致性证据）
        val moneyAtSnap = runCatching { session.store.readInt("MONEY", longArrayOf(0L)) }.getOrNull()
        SnapShots.restore(session, assembled.sandboxDir, loaded)
        if (moneyAtSnap != null) {
            assertEquals(moneyAtSnap, session.store.readInt("MONEY", longArrayOf(0L)))
        }
    }

    // ------------------------------------------------------------------
    // 直调模式收尾后的会话仍可复用（长驻单进程的起码要求）
    // ------------------------------------------------------------------

    @Test
    fun driverLeavesSessionReusableAfterRun() {
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policy = exhaustiveScript(),
            findingKinds = ArrayList(),
        )
        driver.run()
        // run 收工后再直调一次：会话与运行期未被驱动器破坏（process 仍活着）
        val session = assembled.session
        session.runtime.callFunction("EX_PASS", LongArray(0), emptyArray())
        assertTrue(session.console.loggedLines().any { it.contains("ex-pass-ok") })
    }

    // ------------------------------------------------------------------
    // E2 台账接线（DBG7）：out 旁路喂 ExploreOut，决策语义零变化
    // ------------------------------------------------------------------

    @Test
    fun exploreOutWiredRecordsStepsLedgerCoverageAndDetachesProbes() {
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val ledDir = outDir.resolve("ledger")
        val ledger = ExploreOut(ledDir, program = assembled.program, functions = assembled.metadata.functions)
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 50, wallMs = 30_000, maxDepth = 8, outDir = outDir),
            findings = ExploreOutFindingSink(ledger),
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policyProvider = { exhaustiveScript() },
            err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
            out = ledger,
        )
        val result = driver.run()
        // 局序列与既立断言（forkBranchesExploredWithReplayableChains）同源：根(1,1) → 弹 T2 重放[1]
        // 喂(1,2) → 弹 T2' 重放[1] 耗尽弃局 → 弹 T1 重放[] 喂(2) → 弹 T1' 重放[] 耗尽弃局（快照栈
        // 的 chain 不含分支点动作本体，tried 单记——首版本用例误沿恒值策略用例的 3 局期望，闸红对齐）。
        assertEquals(5, result.episodes, driver.episodeLogs.joinToString { it.outcome.name + ";" })
        assertEquals(
            listOf("EPISODE_DONE", "EPISODE_DONE", "ABANDONED", "EPISODE_DONE", "ABANDONED"),
            driver.episodeLogs.map { it.outcome.name },
        )
        // 逐步台账（重放步也记 —— 链完整性，见 Explore.kt 头 KDoc），按上列任务树逐行对齐：
        // 每个被喂的步在其结局揭晓时落一行（下一等待点到达 = ADVANCED / 局终态 = 局结局）；
        // 弃局动作不喂不入账 → ep3/ep5 各只有重放步一行，ep5 连重放步都没有（chain=[] 无步可放）。
        val steps = Files.readAllLines(ledDir.resolve("explore.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(
            listOf(
                Triple("1", "FeedInt(1)", "ADVANCED"), // ep1 W1 自由步（结局在 W2 揭晓）
                Triple("1", "FeedInt(1)", "EPISODE_DONE"), // ep1 W2 自由步（局终态揭晓）
                Triple("2", "FeedInt(1)", "ADVANCED"), // ep2 重放步（T2 的 chain=[1]）
                Triple("2", "FeedInt(2)", "EPISODE_DONE"), // ep2 W2 自由步 → ex-other
                Triple("3", "FeedInt(1)", "ADVANCED"), // ep3 重放步；W2 耗尽弃局不喂不入账
                Triple("4", "FeedInt(2)", "EPISODE_DONE"), // ep4 W1 自由步（ex-other，无第二 INPUT）
            ),
            steps.map { s ->
                val m = Jsonl.parseLine(s)!!
                Triple(m["episode"]!!, m["action"]!!, m["outcome"]!!)
            },
            steps.joinToString("\n"),
        )
        val s0 = Jsonl.parseLine(steps[0])!!
        assertTrue(s0["fp"]!!.isNotEmpty(), "指纹字段非空")
        assertTrue(s0["req"]!!.isNotEmpty() && s0["plan"]!!.isNotEmpty(), "request 摘要与候选计划非空")
        // 覆盖计数：探针挂过（EX_FORK 独进 → executedFunctions=1）且收工后卸下置 null
        val cov = Files.readString(ledDir.resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("executedFunctions=1" in cov, cov)
        assertTrue("EX_FORK covered=" in cov, cov)
        assertNull(assembled.session.runtime.functionProbe, "收工探针卸下（生产零行为路径）")
        assertNull(assembled.session.runtime.statementProbe)
        // 无发现 → findings.jsonl 不落（ExploreOut 懒开，无 finding 调用就不建文件；
        // ABANDONED 局不落发现——弃局是正常探索机制，非 CRASH/WALL/STEP 类）
        assertTrue(!Files.isRegularFile(ledDir.resolve("findings.jsonl")))
    }

    @Test
    fun exploreOutNullKeepsLegacyShape() {
        // out=null（缺省）：零旁路 —— 不建台账目录文件、探针不动（既有单测路径的回归锚）。
        // 局数/序列照既立 5 局断言（forkBranchesExploredWithReplayableChains）对齐。
        val sink = ExploreInputSink()
        val assembled = assemble(sink)
        val ledDir = outDir.resolve("should-not-exist")
        val driver = newDriver(
            assembled, sink,
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policy = exhaustiveScript(),
            findingKinds = ArrayList(),
        )
        val result = driver.run()
        assertEquals(5, result.episodes)
        assertEquals(
            listOf("EPISODE_DONE", "EPISODE_DONE", "ABANDONED", "EPISODE_DONE", "ABANDONED"),
            driver.episodeLogs.map { it.outcome.name },
        )
        assertTrue(!Files.isDirectory(ledDir), "out=null 不建台账目录")
        assertNull(assembled.session.runtime.functionProbe, "out=null 探针从未挂上")
    }
}

/**
 * explore 合成语料装置（CONTRACT-DBG6 E1 单测口径：临时目录写 mini ERB → erbcc 编译 →
 * erbc.v1 产物目录；不依赖真语料，ERATW_* 环境变量一概不用。模式照抄 MiniCorpus，
 * 变量名避开 CORE-06 的单字母保留名（MiniCorpus 的 @MINI_INPUTLOOP 现用 LI/LX 作局部名，
 * 本装置不用单字母）。
 *
 * 函数清单：
 * - `SYSTEM_TITLE` / `EVENTFIRST`（withSystemTitle=true 才编入）：脚本面引导链的最小可跑形态——
 *   标题菜单一次 INPUT（0 = 开始）→ BEGIN FIRST → EVENTFIRST 打出终点子串「第1日」；
 * - `EX_FORK`：两处 INPUT 的输入分岔（EXX 分岔后仅在 1 支再问 EXY）——快照栈分支的全排列面；
 * - `EX_LOOP`：FOR 百万圈每圈 INPUT——墙钟闸的回归面（输入是让出边界，步数闸永不触发；
 *   圈变量由 FOR/NEXT 驱动，不受输入影响）；
 * - `EX_SPIN`：FOR 一亿次空转——步数闸的回归面（纯 CPU 不碰输入）；
 * - `EX_PASS`：直调后打印一行——会话复用的健康检查。
 *
 * ## INPUT 的写法（首跑实锤，勿回退）
 *
 * 必须用裸 `INPUT` + `EXX = RESULT` 两句，不能写 `INPUT EXX`：INPUT 家族的实参槽是
 * `SpInputsArgument(def, mouse, canSkip)`（core/stmt/Argument.kt:303）——**没有目标变量位**，
 * 引擎把交付写进 RESULT（StatementExecutor.kt:1396-1397 的 writeInt(RESULT, ...)），
 * `INPUT EXX` 里的 EXX 只是被当实参求值、从不被赋值。真语料的正形即
 * 裸 INPUT + 判 RESULT（VERSION_UP.ERB:61-70：INPUT / IF RESULT == 0）。
 * 用了 `INPUT EXX` 的症状：EXX 恒 0，EX_FORK 的第二处 INPUT 永远到不了
 * （forkBranchesExploredWithReplayableChains / triedGuardStopsAmnesiacPolicy 首跑 3 局/2 局收工）。
 */
internal object ExploreTestCorpus {

    private const val ERB_COMMON = """; vmdbg explore 合成语料（CONTRACT-DBG6 E1 单测装置）
@EVENTFIRST
	PRINTL ex-eventfirst 第1日

@EX_FORK
	#DIM DYNAMIC EXX
	#DIM DYNAMIC EXY
	INPUT
	EXX = RESULT
	IF EXX == 1
		PRINTL ex-a1
		INPUT
		EXY = RESULT
		IF EXY == 1
			PRINTL ex-a1b1
		ELSE
			PRINTL ex-a1b2
		ENDIF
	ELSE
		PRINTL ex-other
	ENDIF
	PRINTL ex-end

@EX_LOOP
	#DIM DYNAMIC EXI
	FOR EXI, 0, 1000000
		INPUT
	NEXT

@EX_SPIN
	#DIM DYNAMIC EXJ
	FOR EXJ, 0, 100000000
	NEXT

@EX_PASS
	PRINTL ex-pass-ok
"""

    private const val ERB_TITLE = """@SYSTEM_TITLE
	#DIM DYNAMIC EXT
	PRINTL ex-title-menu
	INPUT
	EXT = RESULT
	IF EXT == 0
		BEGIN FIRST
	ELSE
		QUIT
	ENDIF
"""

    class Built(val gameRoot: Path, val erbcDir: Path)

    /** 在 [tmp] 下建 `game/`（空 emuera.config + ERB/explore.erb）并编译出 erbc.v1。 */
    fun build(tmp: Path, withSystemTitle: Boolean): Built {
        val game = tmp.resolve("game")
        val erbDir = game.resolve("ERB")
        Files.createDirectories(erbDir)
        // 空配置：三层配置链读不到键时全部走注册缺省（RootedBuiltinHost.applyChainTo 容缺）。
        Files.writeString(game.resolve("emuera.config"), "")
        val erb = if (withSystemTitle) ERB_TITLE + "\n" + ERB_COMMON else ERB_COMMON
        Files.writeString(erbDir.resolve("explore.erb"), erb)
        val result = CorpusCompile.compile(erbDir)
        check(result.ok) { "explore 合成语料编译失败: ${result.failures.joinToString("; ")}" }
        val outDir = tmp.resolve("erbc-out")
        ErbcArtifacts.write(outDir, result.program!!, erbDir, result.sourceFiles, result.stats, result.bootMetadata)
        return Built(game, outDir.resolve("erbc.v1"))
    }

    /** 组一组装配参数（--erbc/--game/--work 前置）。 */
    fun args(built: Built, work: Path): Args = Args.parse(
        listOf(
            "--erbc", built.erbcDir.toString(),
            "--game", built.gameRoot.toString(),
            "--work", work.toString(),
        ),
    )
}
