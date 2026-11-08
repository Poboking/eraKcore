package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * fix-longsoak 任务二：队列面（feeder 构造的 [WaitPoint]）栈顶缺口回归。
 *
 * 立项背景（车道任务书）：真机玩家报「玩着玩着卡死」，桌面复跑需要「慢页落在哪个等待点 +
 * 哪个函数」的台账；修前 feeder 侧的 `WaitPoint(stackTop = "")` 是硬编码空串，队列面一旦被
 * 记进慢步/发现台账就定不了名。
 *
 * 钉住的口径（两条用例）：
 * 1. **队列面构造的等待点栈顶非空**（[ExploreDriver.queueFaceStackTopLocked]）：用
 *   「SYSTEM_TITLE 缺失 → beginTitle 落 awaitOpeningInput（队列面）」的发源地形
 *   （同 ExploreBootTest.bootViaQueueFaceFeedsSystemInput），从 boot-steps.jsonl 的
 *   queueFace=true 行取证：活帧优先「函数名:pc」，帧退卷时退「state:状态机态名」。
 * 2. **浸泡档慢步台账 stackTop 非空**：slowStepMs=0 造「全部成慢步」的读态（0 阈值 =
 *   每条 elapsedMs>=0 都入选，不真的把单步跑慢、用例不烧墙钟），断言 [SoakMeter.slowSteps]
 *   与逐行台账（stepJsonl = soak-steps.jsonl 的行形状）的 stackTop 均非空。
 *
 * 全部用 [ExploreTestCorpus] 合成语料，不依赖真语料与 ERATW_* 环境变量。
 */
class ExploreFeederStackTopTest {

    private lateinit var tmp: Path
    private lateinit var work: Path
    private lateinit var outDir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-feederstack-test")
        work = tmp.resolve("work")
        outDir = tmp.resolve("explore-out")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun silentErr(): PrintStream = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)

    private fun readBootTrace(): List<Map<String, String>> =
        Files.readAllLines(outDir.resolve(ExploreDefaults.BOOT_STEPS_LEDGER), Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { Jsonl.parseLine(it) ?: error("boot-steps 行解析失败：$it") }

    @Test
    fun queueFaceWaitPointStackTopIsNonBlank() {
        // 发源地形：SYSTEM_TITLE 缺失 → 标题面 = BeginHost.awaitOpeningInput（队列面，
        // 喂入线程构造 WaitPoint）。修前该行 stackTop 恒 ""，本断言必红。
        val built = ExploreTestCorpus.build(tmp, withSystemTitle = false)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 2, wallMs = 10_000L, maxDepth = 8, outDir = outDir),
            findings = FindingSink { _, _, _, _ -> },
            plan = ExplorePlan.Boot(ExploreBoot.parseLines(listOf("0 ;;"))),
            policyProvider = {
                object : ExplorePolicy {
                    override fun choose(wp: WaitPoint) = ExploreAction.Abandon("queue-face-test")
                    override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
                }
            },
            err = silentErr(),
        )
        val t0 = System.nanoTime()
        val result = driver.run()
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 120_000L, "run 必须有限时间归")
        assertTrue(result.bootFailure == null, "引导应到种子：" + (result.bootFailure ?: ""))
        val queueRows = readBootTrace().filter { it["queueFace"] == "true" }
        assertTrue(queueRows.isNotEmpty(), "队列面引导步至少一行（boot-steps.jsonl 的 queueFace=true）")
        queueRows.forEach { row ->
            val st = row["stackTop"] ?: ""
            assertTrue(st.isNotBlank(), "队列面等待点栈顶非空（修前恒空串），实得行：$row")
            assertTrue(
                st.startsWith("state:") || ':' in st,
                "栈顶形状需为 函数名:pc 或 state:态名，实得「$st」",
            )
        }
    }

    @Test
    fun soakSlowStepLedgerRecordsNonBlankStackTop() {
        // 浸泡档 + 零阈值：每条已结算样本都进 slowSteps()；stackTop 由 markSubmit 钉、
        // 结算时原样进台账（慢页定名的取证链）。DirectCall 面 = 脚本 INPUT 等待在活帧上，
        // VmStackReader 应给出「EX_FORK:pc」——若这里塌回空串，说明钉现场/结算链断了。
        val built = ExploreTestCorpus.build(tmp, withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val meter = SoakMeter(slowStepMs = 0L)
        val seen = ArrayList<WaitPoint>()
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(
                maxEpisodes = 2, wallMs = 10_000L, maxDepth = 0, outDir = outDir,
                soak = true, soakWallMs = 60_000L, idleMinMs = 0L, idleMaxMs = 0L, slowStepMs = 0L,
            ),
            findings = FindingSink { _, _, _, _ -> },
            plan = ExplorePlan.DirectCall("EX_FORK", LongArray(0), emptyArray()),
            policyProvider = {
                object : ExplorePolicy {
                    override fun choose(wp: WaitPoint): ExploreAction {
                        seen.add(wp)
                        return ExploreAction.FeedInt(1L)
                    }
                    override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
                }
            },
            err = silentErr(),
            meter = meter,
        )
        val t0 = System.nanoTime()
        driver.run()
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 120_000L, "run 必须有限时间归")
        val slow = meter.slowSteps()
        assertTrue(
            slow.isNotEmpty(),
            "浸泡档至少结算出一条慢步样本（阈值 0 = 全入选），实得 n=${meter.count()}；局读数：" +
                driver.episodeLogs.joinToString { it.outcome.name + "(" + it.note + ")" },
        )
        slow.forEach { s -> assertTrue(s.stackTop.isNotBlank(), "慢步台账 stackTop 非空：$s") }
        val jsonl = meter.stepJsonl().lines().filter { it.isNotBlank() }
        jsonl.forEach { line ->
            val parsed = Jsonl.parseLine(line)
            assertTrue(parsed != null && (parsed["stackTop"] ?: "").isNotBlank(), "台账行 stackTop 非空：$line")
        }
    }
}
