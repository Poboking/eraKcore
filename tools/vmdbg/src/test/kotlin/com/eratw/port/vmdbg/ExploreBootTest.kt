package com.eratw.port.vmdbg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * explore 引导链（CONTRACT-DBG6 §4.E1.4）：
 * - 配方解析（`输入 ;; 期望子串`、# 注释、坏行报错）；
 * - 脚本面引导：合成语料的 SYSTEM_TITLE 一次 INPUT → BEGIN FIRST → EVENTFIRST 打出终点
 *   子串「第1日」→ 种子快照落盘、锚点记录（FLOW.md §2 链的机制等价缩影）；
 * - 队列面引导：SYSTEM_TITLE 缺失 → beginTitle 落到系统输入队列（ConsoleBeginHost.
 *   awaitOpeningInput）→ 喂入线程按食谱喂 0 → 同样到种子（真语料 SYSTEM_TITLE 存在时
 *   不会走这条，但 BOOT-2 的缺标题形态是既有语义，驱动器必须能喂）；
 * - 期望子串不中 → 引导失败且现场落盘（boot-fail.json + boot-fail-console.txt）、策略不构造。
 * 全部用 [ExploreTestCorpus] 合成语料，不碰真语料。
 */
class ExploreBootTest {

    private lateinit var tmp: Path
    private lateinit var work: Path
    private lateinit var outDir: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-exploreboot-test")
        work = tmp.resolve("work")
        outDir = tmp.resolve("explore-out")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun silentErr(): PrintStream = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)

    /** 直跑一个驱动器（引导计划 + 懒策略供给），返回结果与驱动器。 */
    private fun drive(
        withSystemTitle: Boolean,
        recipeLines: List<String>,
        policyShouldNotBuild: Boolean = false,
    ): Pair<ExploreRunResult, ExploreDriver> {
        val built = ExploreTestCorpus.build(tmp.resolve(if (withSystemTitle) "g1" else "g2"), withSystemTitle)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val findingKinds = ArrayList<String>()
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 5, wallMs = 10_000, maxDepth = 8, outDir = outDir),
            findings = FindingSink { kind, _, _, _ -> findingKinds.add(kind) },
            plan = ExplorePlan.Boot(ExploreBoot.parseLines(recipeLines)),
            policyProvider = {
                if (policyShouldNotBuild) throw IllegalStateException("引导失败路径不应构造策略")
                CountingBootPolicy()
            },
            err = silentErr(),
        )
        return driver.run() to driver
    }

    /** 引导成功后的局数最少化策略：直接弃局（引导断言不关心局内行为）。 */
    private class CountingBootPolicy : ExplorePolicy {
        override fun choose(wp: WaitPoint): ExploreAction = ExploreAction.Abandon("boot-test")
        override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {}
    }

    // ------------------------------------------------------------------
    // 配方解析
    // ------------------------------------------------------------------

    @Test
    fun recipeParseSkipsCommentsBlanksAndBareLines() {
        val recipe = ExploreBoot.parseLines(
            listOf(
                "# 注释行",
                "",
                "   ",
                "0 ;; 开始游戏",
                "Enter",
                "9999 ;; 设定完毕",
            ),
        )
        assertEquals(3, recipe.steps.size)
        assertEquals(BootStep("0", "开始游戏"), recipe.steps[0])
        assertEquals(BootStep("Enter", ""), recipe.steps[1])
        assertEquals(BootStep("9999", "设定完毕"), recipe.steps[2])
        // 终点判定子串恒为「第1日」（CONTRACT-DBG6 §4.E1.4 钉死，不在配方文件里配）
        assertEquals(ExploreBoot.TERMINAL_DAY1, recipe.terminal)
    }

    @Test
    fun recipeParseRejectsEmptyInputAndEmptyFile() {
        val e1 = kotlin.test.assertFailsWith<CliError> { ExploreBoot.parseLines(listOf(";; 只有期望没有输入")) }
        assertTrue("输入为空" in (e1.message ?: ""))
        val e2 = kotlin.test.assertFailsWith<CliError> { ExploreBoot.parseLines(listOf("# 只有注释")) }
        assertTrue("配方步骤" in (e2.message ?: ""))
    }

    @Test
    fun builtinStartRecipeShapeMatchesFlow() {
        // 内置链与 FLOW.md §2 的 START 数字串对应（headless 差异：无存档确认步、
        // 设定完毕/私室前后有真机没有的 PRINTW——三个 Enter 步吸收），17 步 + 终点「第1日」。
        val r = ExploreBoot.BUILTIN_START
        assertEquals(17, r.steps.size)
        assertEquals("第1日", r.terminal)
        assertEquals(
            listOf("Enter", "0", "0", "Enter", "9999", "0", "1100", "3", "1999", "Enter", "Enter", "0", "Enter", "100", "1", "1", "0"),
            r.steps.map { it.input },
        )
        // FLOW.md §2 引注过的屏文钉在期望里；未引屏文的步骤留空（不检查）
        assertEquals("请选择游戏模式", r.steps[2].expect)
        assertEquals("设定完毕", r.steps[4].expect)
        assertEquals("设定完毕", r.steps[9].expect)
        assertEquals("请选择地图的种类", r.steps[7].expect)
        assertEquals("完成自定义阶段", r.steps[13].expect)
        assertEquals("", r.steps[0].expect)
    }

    // ------------------------------------------------------------------
    // 脚本面引导成功 → 种子落盘
    // ------------------------------------------------------------------

    @Test
    fun bootViaScriptFaceReachesTerminalAndTakesSeed() {
        val (result, driver) = drive(
            withSystemTitle = true,
            // 合成语料的标题菜单：PRINTL ex-title-menu 后 INPUT（0=开始）→ BEGIN FIRST →
            // EVENTFIRST 打出「第1日」。一步配方即可走到终点。
            recipeLines = listOf("0 ;; ex-title-menu"),
        )
        assertEquals(null, result.bootFailure, result.bootFailure ?: "")
        // 种子文件落盘 + 锚点记录（终点态 = EVENTFIRST 跑完后的 Normal，见 BeginFlow.stepFirst）
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg")), "seed.dbg 应存在")
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg.json")), "seed sidecar 应存在")
        assertNotNull(result.anchor)
        val sidecar = Files.readString(outDir.resolve("seed.dbg.json"), Charsets.UTF_8)
        assertTrue("\"systemState\":\"${result.anchor!!.name}\"" in sidecar, sidecar)
        assertEquals("Normal", result.anchor!!.name)
        // 引导后的探索：直调语料在 Normal 态无路可走（endNormal FAILED），一局即收
        assertEquals(1, result.episodes)
        assertEquals("EPISODE_DONE", driver.episodeLogs.single().outcome.name)
        assertTrue(driver.episodeLogs.single().tail.any { it.contains("第1日") }, driver.episodeLogs.single().tail.toString())
    }

    // ------------------------------------------------------------------
    // 队列面引导（SYSTEM_TITLE 缺失 → BeginHost 系统输入）
    // ------------------------------------------------------------------

    @Test
    fun bootViaQueueFaceFeedsSystemInput() {
        val (result, driver) = drive(
            withSystemTitle = false,
            // SYSTEM_TITLE 缺失：beginTitle 落到 awaitOpeningInput（队列面），喂入线程按食谱喂 0
            // → endOpenning 0=新游戏 → EVENTFIRST 打出「第1日」→ 种子。期望子串留空（默认标题无屏文）。
            recipeLines = listOf("0 ;;"),
        )
        assertEquals(null, result.bootFailure, result.bootFailure ?: "")
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg")))
        assertNotNull(result.anchor)
        assertEquals(1, result.episodes)
        assertTrue(driver.episodeLogs.single().tail.any { it.contains("第1日") }, driver.episodeLogs.single().tail.toString())
    }

    // ------------------------------------------------------------------
    // 期望子串不中 → 引导失败 + 现场落盘 + 策略不构造（CONTRACT-DBG6 §4.E1.7 ③）
    // ------------------------------------------------------------------

    @Test
    fun bootExpectationMismatchFailsWithSiteDump() {
        val (result, _) = drive(
            withSystemTitle = true,
            recipeLines = listOf("0 ;; 此屏绝不会出现这七个字"),
            policyShouldNotBuild = true,
        )
        assertNotNull(result.bootFailure)
        assertTrue("期望子串未命中" in result.bootFailure!!, result.bootFailure!!)
        // 现场落盘：boot-fail.json（步骤号 + 输入 + 期望）+ 完整控制台文本
        assertTrue(Files.isRegularFile(outDir.resolve("boot-fail.json")))
        assertTrue(Files.isRegularFile(outDir.resolve("boot-fail-console.txt")))
        val site = Files.readString(outDir.resolve("boot-fail.json"), Charsets.UTF_8)
        assertTrue("\"stepIndex\":0" in site, site)
        assertTrue("\"stepInput\":\"0\"" in site, site)
        assertTrue("此屏绝不会出现这七个字" in site, site)
        val console = Files.readString(outDir.resolve("boot-fail-console.txt"), Charsets.UTF_8)
        assertTrue("ex-title-menu" in console, console)
        // 引导失败 = 退出码 1 的数据源；种子路径不报
        assertEquals(null, result.seedPath)
    }

    @Test
    fun bootStateMachineFailureAlsoDumpsSite() {
        // 配方只有一步且耗尽后语料再无等待点、EVENTFIRST 不打终点子串 → 状态机走到
        // Normal+栈空 = endNormal FAILED（BeginFlow 终局粘滞）→ 引导失败现场（非期望不中路径）。
        val built = ExploreTestCorpus.build(tmp.resolve("g3"), withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val noTerminalRecipe = BootRecipe(listOf(BootStep("0", "ex-title-menu")), "绝不会出现的终点串")
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 5, wallMs = 10_000, maxDepth = 8, outDir = outDir),
            findings = FindingSink { _, _, _, _ -> },
            plan = ExplorePlan.Boot(noTerminalRecipe),
            policyProvider = { throw IllegalStateException("不应构造策略") },
            err = silentErr(),
        )
        val result = driver.run()
        assertNotNull(result.bootFailure)
        // 两类失败原因都可能先到：食谱耗尽于非终点等待点（EVENTFIRST 打完屏文后的状态机
        // FAILED 在泵循环先被查到），或状态机终态。现场文件必须在。
        assertTrue(Files.isRegularFile(outDir.resolve("boot-fail.json")))
        assertTrue(Files.isRegularFile(outDir.resolve("boot-fail-console.txt")))
    }

    @Test
    fun exploreOutLedgerBootChainLandsInFindingsReplay() {
        // DBG7 接线：out + ExploreOutFindingSink —— 已消费的引导食谱步经 logBootStep 进 E2 链，
        // 引导失败发现（BOOT_FAIL，String kind 透传）的 replay/<n>.txt = 链的食谱语法重放，
        // 可直接喂回 --boot 复现失败点（第 2 步期望不中）。
        val built = ExploreTestCorpus.build(tmp.resolve("g4"), withSystemTitle = true)
        val sink = ExploreInputSink()
        val assembled = Assembler.assemble(ExploreTestCorpus.args(built, work), sinkOverride = sink)
        val ledDir = outDir.resolve("ledger")
        val ledger = ExploreOut(ledDir, program = assembled.program, functions = assembled.metadata.functions)
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = ExploreConfig(maxEpisodes = 5, wallMs = 10_000, maxDepth = 8, outDir = outDir),
            findings = ExploreOutFindingSink(ledger),
            plan = ExplorePlan.Boot(
                BootRecipe(
                    listOf(BootStep("0", "ex-title-menu"), BootStep("9", "绝不会出现的期望串")),
                    "绝不会出现的终点串",
                ),
            ),
            policyProvider = { throw IllegalStateException("不应构造策略") },
            err = silentErr(),
            out = ledger,
        )
        val result = driver.run()
        assertNotNull(result.bootFailure)
        // findings.jsonl 恰一条 BOOT_FAIL（驱动器原生 kind 经适配器逐字透传，detail 折 k=v 串）
        val findings = Files.readAllLines(ledDir.resolve("findings.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(1, findings.size)
        assertTrue("\"kind\":\"BOOT_FAIL\"" in findings[0], findings[0])
        assertTrue("\"episode\":-1" in findings[0], "引导期发现无局号（缺省 -1）：${findings[0]}")
        // replay/1.txt = 已消费的引导链（第 1 步），食谱语法可往返；第 2 步期望不中未消费不入链
        val replay = parseBootRecipe(Files.readString(ledDir.resolve("replay").resolve("1.txt"), Charsets.UTF_8))
        assertEquals(listOf("0" to "ex-title-menu"), replay, "重放链 = 种子（此为引导起点）以来全部已喂输入")
        // coverage.txt 照落（失败前已触碰的读数；无断言读数值，只验产物在）
        assertTrue(Files.isRegularFile(ledDir.resolve("coverage.txt")))
    }
}
