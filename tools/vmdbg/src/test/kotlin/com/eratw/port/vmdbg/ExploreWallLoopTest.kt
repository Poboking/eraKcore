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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * obs-soakwall 任务一：喂入侧墙钟死循环的收口 + 同种发现重复抑制护栏（定长）。
 *
 * 立项现场（组长实测 main@44aa7958，--soak-min 1）：findings.jsonl 8 分钟 42 MB /
 * 28,964 行，全部 kind=WALL_LIMIT、全部 episode=1，相邻两条时间戳差 0～37 ms，
 * detail 里的墙钟读数从 60,325ms 一路涨到 508,789ms（「期间应答 N 次输入」逐条 +1）——
 * 旧 feederBody 的 `catch (WallLimitExceeded)` 只记一条发现就解除停车、不收口本局，
 * 预算已耗尽于是泵每回到同一等待点都再抛再记，「记一条 = 一次输入」死循环。
 *
 * 钉住的口径（三条，逐个用例）：
 * 1. [FindingGuard]：同种前 kindCap 条、全局前 totalCap 条**原样放行**（护栏不吞第一次的真现场），
 *    超出只累计抑制数；桶数定长（未知 kind 归并共享桶，无无界累加）。
 * 2. 驱动器投递面：泵侧每局一条 WALL_LIMIT（EX_LOOP 直调、局数 > 单种上限）——
 *    放行条 == 上限、抑制条 == 局数-上限、每局仍正常终态收口。
 * 3. 喂入侧死循环回归：SeedFile + 队列面等待点、墙钟预算必超——
 *    run() 必须在有限时间归（修前这一局永不终态）、每局至多一条 WALL_LIMIT 台账、
 *    不再有「同一局重复灌台账」的形态。
 */
class ExploreWallLoopTest {

    private lateinit var tmp: Path
    private lateinit var work: Path
    private lateinit var outDir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-wallloop-test")
        work = tmp.resolve("work")
        outDir = tmp.resolve("explore-out")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun silentErr(): PrintStream = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)

    // ------------------------------------------------------------------
    // ① 护栏本体（纯单元）
    // ------------------------------------------------------------------

    @Test
    fun guardPassesFirstScenesThenSuppressesAndAnnouncesOnce() {
        val g = FindingGuard(kindCap = 3, totalCap = 10)
        // 前 3 条原样放行——护栏绝不吞第一次的真现场。
        repeat(3) { i ->
            val p = g.allow("WALL_LIMIT")
            assertTrue(p.delivered, "第 ${i + 1} 条必须放行（kindCap=3）")
            assertFalse(p.justMuted)
        }
        // 第 4 条起抑制；justMuted 只在首个被压静音时亮一次。
        val m1 = g.allow("WALL_LIMIT")
        assertFalse(m1.delivered)
        assertTrue(m1.justMuted, "首次抑制要可被调用方宣告")
        val m2 = g.allow("WALL_LIMIT")
        assertFalse(m2.delivered)
        assertFalse(m2.justMuted, "同一 kind 的静音宣告只打一次")
        assertEquals(2, g.suppressed)
        // 另一 kind 有自己的额度（互不挤占单种闸）。
        assertTrue(g.allow("CRASH").delivered)
        // 总闸：放满 totalCap 后任何 kind 都进不了。
        val g2 = FindingGuard(kindCap = 100, totalCap = 2)
        assertTrue(g2.allow("A").delivered)
        assertTrue(g2.allow("B").delivered)
        assertFalse(g2.allow("C").delivered, "totalCap 之外的第二道闸")
        assertEquals(1, g2.suppressed)
        // 桶数定长：未知 kind 超过 FINDING_KIND_KEYS_MAX 后归并共享桶（映射不随 kind 无限膨胀），
        // 共享桶同样受 kindCap 约束——桶满后新 kind 只能被抑制。
        val g4 = FindingGuard(kindCap = 1, totalCap = Int.MAX_VALUE)
        repeat(ExploreDefaults.FINDING_KIND_KEYS_MAX) { assertTrue(g4.allow("K$it").delivered) }
        assertTrue(g4.allow("overflow-new").delivered, "满桶后首个新 kind 进共享桶，享共享桶自身额度")
        assertFalse(g4.allow("overflow-new2").delivered, "共享桶额度耗尽后新 kind 一律抑制（无界累加不存在）")
    }

    // ------------------------------------------------------------------
    // ② 驱动器泵侧：多局各一条 WALL_LIMIT，超出单种上限的进抑制
    // ------------------------------------------------------------------

    @Test
    fun pumpSideWallFindingsAreCappedPerKindAcrossEpisodes() {
        // EX_LOOP 直调 + 小墙钟：每局恰一条 WALL_LIMIT（pumpBody 捕获即终态）。
        // maxEpisodes 给到 kindCap 之上 ⇒ 放行 = kindCap、抑制 = episodes - kindCap，
        // 台账行数有上界、且每局照常收口（局数不因护栏被吞掉终态记账）。
        val built = ExploreTestCorpus.build(tmp, withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val delivered = ArrayList<String>()
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(
                maxEpisodes = 8, wallMs = 40, maxDepth = 8, outDir = outDir,
                findingKindCap = 3, findingTotalCap = 100,
            ),
            findings = FindingSink { kind, _, _, _ -> delivered.add(kind) },
            plan = ExplorePlan.DirectCall("EX_LOOP", LongArray(0), emptyArray()),
            policyProvider = { object : ExplorePolicy {
                private var n = 0L
                override fun choose(wp: WaitPoint) = ExploreAction.FeedInt(n++)
                override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
            } },
            err = silentErr(),
        )
        val result = driver.run()
        assertEquals(8, result.episodes, driver.episodeLogs.joinToString { it.outcome.name + ";" })
        assertTrue(driver.episodeLogs.all { it.outcome == OutcomeKind.WALL_LIMIT }, driver.episodeLogs.map { it.outcome.name }.toString())
        assertEquals(3, delivered.count { it == "WALL_LIMIT" }, "放行条 == kindCap（第一次的真现场都在）")
        assertEquals(5, result.findingsSuppressed, "抑制条 = 8 局 - 3 放行")
        assertEquals(3, result.findingsCount, "findingsCount 只数真实投递")
    }

    // ------------------------------------------------------------------
    // ③ 喂入侧死循环回归：SeedFile + 队列面 + 必超墙钟 ⇒ 有限时间归、每局至多一条
    // ------------------------------------------------------------------

    @Test
    fun feederSideWallLimitTerminatesEpisodeOnceInsteadOfLooping() {
        // 现场复刻（最小版）：种子锚点 = Train_CallShowUserCom（第 1 日指令选择，队列面等待，
        // BeginFlow.stepTrainShowUserCom → awaitUserComInput，正是修前 28,964 行台账的发源地形）。
        // wallMs=1 ⇒ 喂入线程首次代查预算必抛。修前：记一条→解除停车→泵回同一点→再抛再记，
        // run() 永不归；修后：首条现场入台账 + feedWallPending 挂起 + 泵下一步界 WALL_LIMIT 终态。
        val built = ExploreTestCorpus.build(tmp, withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        Files.createDirectories(outDir)
        SnapShots.saveSeed(outDir, SnapShots.take(assembled.session, assembled.sandboxDir, ""), Cli.RAND_SEED)
        val delivered = ArrayList<String>()
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(
                maxEpisodes = 3, wallMs = 1, maxDepth = 0, outDir = outDir,
                findingKindCap = 100, findingTotalCap = 1000,
            ),
            findings = FindingSink { kind, _, _, _ -> delivered.add(kind) },
            plan = ExplorePlan.SeedFile(outDir.resolve("seed.dbg")),
            policyProvider = { object : ExplorePolicy {
                override fun choose(wp: WaitPoint) = ExploreAction.FeedInt(0L)
                override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
            } },
            err = silentErr(),
        )
        val t0 = System.nanoTime()
        val result = driver.run()
        val ranMs = (System.nanoTime() - t0) / 1_000_000L
        // 有限时间归（修前这一局永不终态，run 走不到返回）。
        assertTrue(ranMs < 120_000, "run 必须在分钟级以下归，实得 ${ranMs}ms")
        val wallFindings = delivered.count { it == "WALL_LIMIT" }
        // 核心红线：墙钟台账不再按输入循环灌行——WALL_LIMIT 至多每局一条
        // （修前形态：episode=1 单局灌出 28,964 行，条数/局数 ≈ 应答输入数）。
        assertTrue(wallFindings >= 1, "首条现场必须入台账（护栏不吞真现场）：$delivered")
        assertTrue(wallFindings <= result.episodes, "每局至多一条：$wallFindings 条 / ${result.episodes} 局 / 台账 $delivered")
        assertTrue(delivered.size <= 8, "整轮台账极小（后续终态由既有 CRASH/EPISODE_DONE 口径分流）：$delivered")
        assertEquals(0, result.findingsSuppressed, "kindCap=100 内不该触发抑制")
        assertTrue(result.episodes >= 1, "至少收口一局：${driver.episodeLogs.map { it.outcome.name }}")
    }
}
