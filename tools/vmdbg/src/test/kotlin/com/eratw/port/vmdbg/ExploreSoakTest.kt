package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 浸泡档（obs-soak）单测：分位口径 + 慢步成节 + 随机策略确定性 + 端到端读数。
 *
 * 分位钉死为「最近秩」，与产品侧 `app/TurnPercentiles.kt`（只读参考）同一整数算式
 * `(q100 × n + 99) / 100`——这里用可手算的样本集逐值对齐，证明移植口径一致。
 * 端到端用 [ExploreTestCorpus] 合成语料的 `EX_FORK` 直调（不碰真语料、不依赖 ERATW_*），
 * 断言「提交→到点」逐条进台账且计数自洽（= explore.jsonl 行数）。
 */
class ExploreSoakTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-soak-test")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** 造一个纯耗时的样本（其余字段填占位，只测分位）。 */
    private fun s(ms: Long, ep: Long = 1L, top: String = "EX_FORK:9", act: String = "FeedInt(0)", idx: Int = 0, out: String = "ADVANCED") =
        SoakMeter.Sample(ms, ep, top, act, idx, out)

    private fun meterWith(values: List<Long>, slow: Long = 5000L): SoakMeter {
        val m = SoakMeter(slow)
        values.forEachIndexed { i, v -> m.record(s(v, idx = i)) }
        return m
    }

    // ------------------------------------------------------------------
    // 分位口径（最近秩，对齐 TurnPercentiles）
    // ------------------------------------------------------------------

    @Test
    fun percentileNearestRankMatchesTurnPercentiles() {
        // 样本 1..20（升序）：n=20，偶数 p50 取下中位（第 10 小=10）；p95 第 19 小=19；max 第 20 小=20。
        val m = meterWith((1L..20L).toList())
        assertEquals(20, m.count())
        assertEquals(10L, m.p50(), "n=20 偶数 p50=下中位（第10小）")
        assertEquals(19L, m.p95(), "n=20 p95=第19小")
        assertEquals(20L, m.max(), "max=percentile(100)=第20小")
        // n<20 时 p95 恒等于 max（1..19）：(95*19+99)/100 = 19 → 第19小=19=max。
        val m19 = meterWith((1L..19L).toList())
        assertEquals(19L, m19.p95(), "n≤19 时 p95==max（最近秩定义）")
        assertEquals(m19.max(), m19.p95())
        // 单样本：三分位全等于该读数。
        val m1 = meterWith(listOf(42L))
        assertEquals(42L, m1.p50())
        assertEquals(42L, m1.p95())
        assertEquals(42L, m1.max())
    }

    @Test
    fun percentileUnorderedInputStillCorrect() {
        // 乱序录入不影响分位（内部排序）；尾部劣化样本对齐取证形态 2/2342/15192/58515。
        val vals = listOf(58515L, 2L, 15192L, 2342L)
        val m = meterWith(vals)
        assertEquals(58515L, m.max())
        // n=4：p50 rank=(50*4+99)/100=2 → 第2小=2342；p95 rank=(95*4+99)/100=4 → 第4小=max。
        assertEquals(2342L, m.p50())
        assertEquals(58515L, m.p95())
    }

    @Test
    fun emptyMeterHasNoReadings() {
        val m = SoakMeter(5000L)
        assertEquals(0, m.count())
        assertEquals(0L, m.p50())
        assertEquals(0L, m.p95())
        assertEquals(0L, m.max())
        assertEquals("n=0 无耗时读数", m.summaryLine())
        assertTrue(m.slowSteps().isEmpty())
    }

    @Test
    fun slowStepSectionCarriesFnPcAndCount() {
        val m = meterWith(listOf(100L, 5100L, 6000L, 4999L), slow = 5000L)
        // 慢步阈值 ≥5000：5100 与 6000（4999 不算）；降序 6000 先。
        val slow = m.slowSteps()
        assertEquals(2, slow.size)
        assertEquals(6000L, slow[0].elapsedMs)
        val summary = m.summaryLine()
        assertTrue("n=4" in summary && "slow(>=5000ms)=2" in summary, summary)
        val report = m.renderReport(seed = 20260226L, wallMin = 20L)
        assertTrue("EX_FORK:9" in report, report)
        assertTrue("[慢步 elapsedMs>=5000]" in report, report)
    }

    @Test
    fun stepJsonlOneLinePerStep() {
        val m = meterWith(listOf(1L, 2L, 3L))
        val lines = m.stepJsonl().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(3, lines.size)
        val m0 = Jsonl.parseLine(lines[0])!!
        assertEquals("1", m0["elapsedMs"])
        assertTrue(m0["outcome"] == "ADVANCED")
        // obs-soak512：手造样本未采样堆水位 = -1 进台账（字段恒在，读数口径「-1=未采样」）。
        assertEquals("-1", m0["heapUsedMi"])
    }

    // ------------------------------------------------------------------
    // 堆水位与堆 OOM（obs-soak512：512m 挡堆压浸泡的取证面）
    // ------------------------------------------------------------------

    @Test
    fun heapPeakAndCeilingRenderIntoSoakReport() {
        val m = SoakMeter(5000L)
        // 未采样的报告：heapPeakMi=-1 显式「未采样」，挡位读数仍恒在（java 拉起行的自证）。
        val bare = m.renderReport(seed = 20260226L, wallMin = 10L)
        assertTrue("heapPeakMi=-1（未采样）" in bare, bare)
        assertTrue("heapCeilingMi=" in bare, bare)
        assertEquals(-1L, m.heapPeakMi())
        assertTrue(m.heapCeilingMi() > 0L, "挡位读数必须为正（本进程 -Xmx）")

        // 采样后：峰值 = 样本 heapUsedMi 上界（非最后一条），逐条水位进 soak-steps.jsonl。
        m.record(SoakMeter.Sample(2L, 1L, "EX_FORK:9", "0", 0, "ADVANCED", heapUsedMi = 180L))
        m.record(SoakMeter.Sample(3L, 1L, "EX_FORK:9", "1", 1, "ADVANCED", heapUsedMi = 505L))
        m.record(SoakMeter.Sample(4L, 1L, "EX_FORK:9", "0", 2, "ADVANCED", heapUsedMi = 300L))
        assertEquals(505L, m.heapPeakMi())
        val report = m.renderReport(seed = 20260226L, wallMin = 10L)
        assertTrue("heapPeakMi=505" in report, report)
        assertTrue("（离挡位余量 " in report, report)
        val rows = m.stepJsonl().lineSequence().filter { it.isNotBlank() }.map { Jsonl.parseLine(it)!! }.toList()
        assertEquals(listOf("180", "505", "300"), rows.map { it["heapUsedMi"] })
    }

    @Test
    fun oomSectionRendersAndStaysSeparateFromGateHangs() {
        val m = SoakMeter(5000L)
        m.record(SoakMeter.Sample(9_000L, 1L, "EX_FORK:9", "0", 0, "CRASH", heapUsedMi = 508L))
        // 无 OOM：专节显式「0 条 / 无」，与「忘了带闸」可分辨（hangScenes 同款口径）。
        val none = m.renderReport(seed = 20260226L, wallMin = 10L)
        assertTrue("[堆 OOM（OutOfMemoryError，obs-soak512）] 共 0 条" in none, none)
        assertTrue("（无：本轮没有步被堆 OOM 打断）" in none, none)

        m.oom(
            HangScene(
                elapsedMs = 23_000L, episode = 2L, stepIndex = 17, action = "0",
                scene = linkedMapOf(
                    "message" to "Java heap space",
                    "heapUsedMi" to "511",
                    "heapCeilingMi" to "512",
                    "stackTop" to "EX_FORK:9",
                ),
            )
        )
        val report = m.renderReport(seed = 20260226L, wallMin = 10L)
        assertTrue("[堆 OOM（OutOfMemoryError，obs-soak512）] 共 1 条" in report, report)
        assertTrue("23000ms ep=2 step=17 action=0 已用=511Mi/挡位=512Mi 提交面=EX_FORK:9" in report, report)
        assertTrue("message=Java heap space" in report, report)
        // 与墙钟闸专节互不混：OOM 不进 hang 表。
        assertTrue("[单步墙钟闸命中 --step-wall-ms=-] 共 0 条" in report, report)
        assertEquals(1, m.ooms().size)
    }

    // ------------------------------------------------------------------
    // 随机策略：确定性 + 不弃局 + 合法候选
    // ------------------------------------------------------------------

    private fun intWp(tail: String) = WaitPoint(
        request = InputRequest(type = InputRequest.InputType.INT),
        anyKey = false,
        buttons = listOf(ButtonOpt("[0]否", 0), ButtonOpt("[1]是", 1)),
        consoleTail = listOf(tail),
        stackTop = "FN:1",
    )

    @Test
    fun soakPolicyDeterministicAndNeverAbandons() {
        val seq = (0 until 40).map { intWp("屏$it") }
        val a = SoakPolicy(20260226L)
        val b = SoakPolicy(20260226L)
        val la = seq.map { a.choose(it) }
        val lb = seq.map { b.choose(it) }
        assertEquals(la, lb, "同 seed 同 WaitPoint 序列 ⇒ 同决定（可复跑）")
        // 永不弃局；且每个决定落在该等待点的候选集内（合法，不会触发驱动器 ILLEGAL_ACTION）。
        la.forEachIndexed { i, act ->
            assertTrue(act !is ExploreAction.Abandon, "浸泡策略不弃局")
            assertTrue(act in exploreCandidateActions(seq[i]), "决定必须是候选之一")
        }
        // 允许重复（真人连点）：40 次里同值候选应出现多次（随机非枚举）。
        val values = la.filterIsInstance<ExploreAction.FeedInt>().map { it.value }
        assertTrue(values.distinct().size < values.size, "随机连点应有重复取值")
    }

    // ------------------------------------------------------------------
    // 端到端：DirectCall EX_FORK 跑浸泡，逐步耗时进台账且计数自洽
    // ------------------------------------------------------------------

    private fun assembleSoakSink(): Pair<ExploreInputSink, Assembler.Assembled> {
        val built = ExploreTestCorpus.build(tmp, withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(
            ExploreTestCorpus.args(built, tmp.resolve("work")),
            sinkOverride = sink,
        )
        return sink to assembled
    }

    @Test
    fun soakRunRecordsPerStepElapsedIntoLedger() {
        val (sink, assembled) = assembleSoakSink()
        val outDir = tmp.resolve("soak-out")
        val meter = SoakMeter(5000L)
        val ledger = ExploreOut(outDir, program = assembled.program, functions = assembled.metadata.functions)
        val cfg = ExploreConfig(
            maxEpisodes = 6, // 二次安全闸：浸泡跑满 6 局即停（idle=0、soakWallMs 给足 → 局数先到）
            wallMs = 30_000,
            maxDepth = 24, // soak 门控会关掉分支入栈，此值不影响
            outDir = outDir,
            soak = true,
            soakWallMs = 600_000L,
            idleMinMs = 0L,
            idleMaxMs = 0L,
            slowStepMs = 5000L,
        )
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = cfg,
            findings = ExploreOutFindingSink(ledger),
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policyProvider = { SoakPolicy(20260226L) },
            err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
            out = ledger,
            meter = meter,
        )
        val result = driver.run()

        // 跑满 6 局（wall 未到、max-episodes 先到）。
        assertEquals(6, result.episodes, driver.episodeLogs.joinToString { it.outcome.name })
        // 每局 EX_FORK 至少 1 次 INPUT → 至少 6 步「提交→到点」读数。
        assertTrue(meter.count() >= 6, "meter.count=${meter.count()}")
        // 逐条台账自洽：meter 样本数 = explore.jsonl 行数（每个交付步两边各一行）。
        val rows = Files.readAllLines(outDir.resolve("explore.jsonl"), Charsets.UTF_8).count { it.isNotBlank() }
        assertEquals(meter.count(), rows, "soak 步数与 explore.jsonl 行数应一一对应")
        // 分位单调：p50 ≤ p95 ≤ max。
        assertTrue(meter.p50() <= meter.p95() && meter.p95() <= meter.max(), meter.summaryLine())
        // 浸泡关掉分支枚举：局局 EPISODE_DONE（直调返回），无快照栈换支。
        assertTrue(driver.episodeLogs.all { it.outcome == OutcomeKind.EPISODE_DONE }, driver.episodeLogs.map { it.outcome.name }.toString())
    }

    @Test
    fun nonSoakRunLeavesMeterAndSinkUntouched() {
        // 回归锚：soak=false 时既不接线 sink.soak，也不产生任何 meter 样本（既有探索路径零变化）。
        val (sink, assembled) = assembleSoakSink()
        val outDir = tmp.resolve("nonsoak-out")
        val meter = SoakMeter(5000L)
        val ledger = ExploreOut(outDir, program = assembled.program, functions = assembled.metadata.functions)
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 5, wallMs = 30_000, maxDepth = 8, outDir = outDir),
            findings = ExploreOutFindingSink(ledger),
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policyProvider = { ExhaustivePolicy(20260226L) },
            err = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
            out = ledger,
            meter = meter,
        )
        val result = driver.run()
        assertNull(sink.soak, "非浸泡不接线 sink.soak（run 的 finally 也会置 null）")
        assertEquals(0, meter.count(), "非浸泡不产生耗时样本")
        // 探索正常收敛（非浸泡走全排列/弃局机制，局数由策略决定，此处只验证驱动器未被浸泡分支破坏）。
        assertTrue(result.episodes >= 1, "非浸泡仍正常收工：episodes=${result.episodes}")
    }

    // ------------------------------------------------------------------
    // 单步墙钟闸（obs-midhang：--step-wall-ms 与每局墙钟/全局步闸分离）
    // ------------------------------------------------------------------

    @Test
    fun stepWallGateKnobIsOffByDefault() {
        // 缺省逐字不变：ExploreConfig.stepWallMs=0（= VmConfig.maxRunMillis 关闭），
        // 且驱动器没有别的途径能把墙钟闸打开（装置改动只在显式点名时生效）。
        val cfg = ExploreConfig(outDir = tmp)
        assertEquals(0L, cfg.stepWallMs, "墙钟闸缺省必须关闭")
        assertEquals(30L * 60_000L, cfg.wallMs, "每局墙钟缺省不动")
    }

    @Test
    fun timeGateAndStepGateAreDistinguishedByElapsedMs() {
        // 同异常两种闸：时间闸带真实段耗时（≥0），步数闸恒 -1（Errors.kt:66 的 elapsedMs 文档口径）。
        val stepGate = com.eratw.port.core.code.StepLimitExceeded(20_000_000L, 219, 4096, "EVENTFIRST")
        val timeGate = com.eratw.port.core.code.StepLimitExceeded(
            1_208_042L, 615, 8123, "GETMAP", callChain = "MAP_DISPLAY → CHARA_MOVEMENT → GETMAP", elapsedMs = 30001L,
        )
        assertTrue(!isSegmentTimeGate(stepGate), "步数闸不得被判成时间闸")
        assertTrue(isSegmentTimeGate(timeGate), "elapsedMs>=0 即时间闸")
        // 文案可分辨（玩家/台账据此分清「指令太多」与「这一段跑太久」）。
        assertTrue("执行步数超过上限" in stepGate.message!!, stepGate.message)
        assertTrue("执行时间超过上限" in timeGate.message!!, timeGate.message)
    }

    @Test
    fun timeGateSceneCarriesThrowPointVerbatim() {
        val timeGate = com.eratw.port.core.code.StepLimitExceeded(
            1_208_042L, 615, 8123, "GETMAP", callChain = "MAP_DISPLAY → CHARA_MOVEMENT → GETMAP", elapsedMs = 30001L,
        )
        val scene = stepLimitScene(timeGate, stepWallMs = 30_000L)
        // 六项现场全部来自异常自带字段（抛点就地构造），键名固定。
        assertEquals(
            listOf("funcName", "line", "pc", "steps", "elapsedMs", "callChain", "stepWallMs"),
            scene.keys.toList(),
        )
        assertEquals("GETMAP", scene["funcName"])
        assertEquals("615", scene["line"])
        assertEquals("8123", scene["pc"])
        assertEquals("1208042", scene["steps"])
        assertEquals("30001", scene["elapsedMs"])
        assertEquals("MAP_DISPLAY → CHARA_MOVEMENT → GETMAP", scene["callChain"])
        assertEquals("30000", scene["stepWallMs"])
    }

    @Test
    fun hangScenesRenderIntoSoakReportSortedByElapsed() {
        val m = SoakMeter(5000L)
        m.record(s(120L, idx = 0))
        m.record(s(9_000L, idx = 1, out = "STEP_LIMIT"))
        val report = m.renderReport(seed = 20260226L, wallMin = 6L)
        // 无命中：专节仍成节并显式写「无」，避免与「忘了带闸」混读。
        assertTrue("[单步墙钟闸命中 --step-wall-ms=-] 共 0 条" in report, report)
        assertTrue("（无：本轮没有段落被墙钟闸中断）" in report, report)

        m.hang(HangScene(30_001L, episode = 2L, stepIndex = 31, action = "FeedInt(1)",
            scene = stepLimitScene(
                com.eratw.port.core.code.StepLimitExceeded(
                    1_208_042L, 615, 8123, "GETMAP",
                    callChain = "MAP_DISPLAY → CHARA_MOVEMENT → GETMAP", elapsedMs = 30_001L,
                ),
                stepWallMs = 30_000L,
            )))
        m.hang(HangScene(45_002L, episode = 3L, stepIndex = 7, action = "FeedInt(0)",
            scene = stepLimitScene(
                com.eratw.port.core.code.StepLimitExceeded(
                    5_000_000L, 100, 222, "CHARA_MOVEMENT",
                    callChain = "UPDATE_MAIN_UPDATE → CHARA_MOVEMENT", elapsedMs = 45_002L,
                ),
                stepWallMs = 30_000L,
            )))
        val withHangs = m.renderReport(seed = 20260226L, wallMin = 6L)
        assertTrue("共 2 条" in withHangs, withHangs)
        assertTrue("[单步墙钟闸命中 --step-wall-ms=30000]" in withHangs, withHangs)
        // 耗时降序 + 最内层函数/行号/段指令数/调用链逐条成行。
        val first = withHangs.indexOf("45002ms")
        val second = withHangs.indexOf("30001ms")
        assertTrue(first in 0..<second, "挂起专节须按段耗时降序：$withHangs")
        assertTrue("最内层=CHARA_MOVEMENT 行=100" in withHangs, withHangs)
        assertTrue("段指令=1208042" in withHangs, withHangs)
        assertTrue("调用链(外→内)=MAP_DISPLAY → CHARA_MOVEMENT → GETMAP" in withHangs, withHangs)
        // 慢步专节与挂起专节互不混（9000ms 仍只在慢步里）。
        assertTrue("slow(>=5000ms)=1" in withHangs, withHangs)
    }
}
