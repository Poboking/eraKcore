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
        gameDirName: String = "g1",
        titleErb: String? = null,
    ): Pair<ExploreRunResult, ExploreDriver> {
        val built = ExploreTestCorpus.build(tmp.resolve(gameDirName), withSystemTitle, titleErb)
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
        // 设定完毕/私室前后有真机没有的 PRINTW——三个 Enter 步吸收；尾部按 2026-11-01 取证补一枚
        // 「可选立绘」步），18 步 + 终点「第1日」。
        val r = ExploreBoot.BUILTIN_START
        assertEquals(18, r.steps.size)
        assertEquals("第1日", r.terminal)
        assertEquals(
            listOf("Enter", "0", "0", "Enter", "9999", "0", "1100", "3", "1999", "Enter", "Enter", "0", "Enter", "100", "1", "1", "0", "0"),
            r.steps.map { it.input },
        )
        // FLOW.md §2 引注过的屏文钉在期望里；未引屏文的步骤留空（不检查）
        assertEquals("请选择游戏模式", r.steps[2].expect)
        assertEquals("设定完毕", r.steps[4].expect)
        assertEquals("设定完毕", r.steps[9].expect)
        assertEquals("请选择地图的种类", r.steps[7].expect)
        assertEquals("完成自定义阶段", r.steps[13].expect)
        assertEquals("", r.steps[0].expect)
        // 第 15 步（obs-bootrecipe 甲类裁定）：期望串 = 屏上面头原文，不是 FLOW.md 的转述
        assertEquals(
            "素質技能の変更", r.steps[14].expect,
            "2026-11-01 屏上取证：该面选项是 [0]-保留/[1]-変更/[2]-不変更，「全部覆盖」四字屏上零出现",
        )
        assertEquals("1", r.steps[14].input, "输入位不变：[1]-変更 就是 FLOW.md 想按的那一刀")
        // 第 16/17 步：转述串零命中 / 面按条件开火 ⇒ 只推进不判定（留空），取证后再钉回
        assertEquals("", r.steps[15].expect, "口上版本选择面的真实屏文未取证，不许拿转述当屏文钉")
        assertEquals("1", r.steps[15].input)
        assertEquals("", r.steps[16].expect, "本局实际落在魔眼路线选择等 mod 面上，位次不可钉")
        assertEquals("0", r.steps[16].input)
        // 第 18 步 = 2026-11-01 boot-steps.jsonl 取证到的「可选立绘」ASK_YN 面（原记在第 17 位是位次错）
        assertEquals("可选立绘", r.steps[17].expect)
        assertEquals("0", r.steps[17].input, "[0] 是 = 缄默跳过此次立绘更新")
        // 全链只允许「有出处的屏文」进期望：逐条非空期望必须是本仓取证过的串
        val pinned = r.steps.map { it.expect }.filter { it.isNotEmpty() }
        assertTrue("全部覆盖" !in pinned, "转述串不得再进配方（期望只钉屏上取证过的原文）：" + pinned)
        assertTrue("用新的" !in pinned, "同上：" + pinned)
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
        // 引导失败即落逐步台账，且失败那一行自带「这一面究竟是什么」：等待点形态 + 本次新增屏上行
        // （obs-bootrecipe 复现第 15 步时靠这两格把「口上技能变更面根本没到」钉死，不必反推转写）
        assertTrue(
            Files.isRegularFile(outDir.resolve(ExploreDefaults.BOOT_STEPS_LEDGER)),
            "引导失败应落 " + ExploreDefaults.BOOT_STEPS_LEDGER,
        )
        val rows = readBootTrace()
        assertEquals(1, rows.size, "失败步只有期望不中这一行")
        assertEquals("EXPECT_MISS", rows[0]["event"])
        assertEquals("1", rows[0]["step"])
        assertEquals("此屏绝不会出现这七个字", rows[0]["expect"])
        assertEquals("false", rows[0]["anyKey"], "本例的标题面是取值面（INPUT），不是任意键面")
        assertTrue("ex-title-menu" in (rows[0]["newText"] ?: ""), "新增屏上行应带出这一面刚打的字：" + rows[0]["newText"])
        // 引导失败 = 退出码 1 的数据源；种子路径不报
        assertEquals(null, result.seedPath)
    }

    // ------------------------------------------------------------------
    // 期望门与推进面的口径（obs-bootrecipe）：任意键面不经食谱
    // ------------------------------------------------------------------

    /**
     * 判式本体（纯函数，不依赖产物）：
     * - 期望留空 = 不判定（只推进），空屏也算过；
     * - 命中 = 屏尾**任一行**含该串，逐行判、不跨行拼串；
     * - 不做 trim / 全半角归一：屏上原文什么样就什么样。
     * 用词以真语料实际屏文为例证：第 15 步那次量到的面是「正在匹配经验与能力等级…」+
     * 「游戏开始处理完成」，期望串「全部覆盖」不在其中 ⇒ 判不中（这正是装置报红的原因，
     * 不是文案漂移）。
     */
    @Test
    fun bootExpectHitIsRowLocalAndEmptyMeansNoGate() {
        val day1Tail = listOf(
            "正在匹配经验与能力等级…",
            "游戏开始处理完成",
            "[100]完成自定义阶段并开始游戏",
        )
        // 空期望 = 容错步（本局不出现这一面时只推进不判定）
        assertTrue(bootExpectHit("", emptyList()), "空期望应恒过（连空屏都过）")
        assertTrue(bootExpectHit("", day1Tail), "空期望应恒过（非空屏）")
        // 逐行 contains：命中行可以不是末行
        assertTrue(bootExpectHit("完成自定义阶段", day1Tail), "命中行不必是末行")
        // 屏上确实没有 ⇒ 不中（obs-midhang 那次的第 15 面就是这个形态）
        assertTrue(!bootExpectHit("全部覆盖", day1Tail), "「全部覆盖」不在开局处理面上")
        // 跨行不拼串：文案被排版拆成两行时不得算命中（假绿形态）
        val split = listOf("口上技能变", "更：保留/全部覆盖/不变更")
        assertTrue(!bootExpectHit("口上技能变更：全部覆盖", split), "拆行的期望串不算命中")
        // 归一口径：半/全角与首尾空格一律不折叠
        assertTrue(!bootExpectHit("全部覆盖 ", listOf("全部覆盖")), "不做 trim 归一")
    }

    /** 推进步判式：留空与 Enter（大小写随意）= 推进步；数字/文本 = 取值步。 */
    @Test
    fun enterInputJudgeOnlyAcceptsBlankAndEnterToken() {
        assertTrue(isEnterInput(""), "留空 = 推进步")
        assertTrue(isEnterInput("   "), "纯空白 = 推进步")
        assertTrue(isEnterInput("Enter"))
        assertTrue(isEnterInput("enter"), "大小写不敏感")
        assertTrue(!isEnterInput("0"), "数字步是取值步")
        assertTrue(!isEnterInput("100"), "按钮号步是取值步")
        assertTrue(!isEnterInput("ENTER_KEY"), "文本步是取值步")
    }

    /**
     * 任意键面**不消费**取值步（obs-bootrecipe 修正的口径，ExploreBoot 头 KDoc 自 DBG6 就这么写的）：
     * 合成语料的标题链是「PRINTW 任意键面（此时屏上还没有期望串）→ PRINTL 钉住的屏文 → INPUT 取值面」，
     * 旧实现第 1 步的期望门抢在 anykey 宽容之前、在 PRINTW 面就开火 ⇒ 引导必死；
     * 现实现放过任意键面（不消费步、不判期望），期望在它真正交付输入的那个取值面上判定 ⇒ 引导过、落种子。
     */
    @Test
    fun anyKeyFaceDoesNotConsumeRecipeStepAndExpectationLandsOnValueFace() {
        val (result, _) = drive(
            withSystemTitle = true,
            gameDirName = "g5",
            // PRINTW = 任意键等待点（readAnyKey 路径）；INPUT = 取值等待点
            titleErb = """
                @SYSTEM_TITLE
                #DIM DYNAMIC EXT
                PRINTW ex-anykey-face-before-pin
                PRINTL ex-pin-on-value-face
                INPUT
                EXT = RESULT
                IF EXT == 0
                	BEGIN FIRST
                ELSE
                	QUIT
                ENDIF
            """.trimIndent(),
            recipeLines = listOf("0 ;; ex-pin-on-value-face"),
        )
        assertEquals(null, result.bootFailure, result.bootFailure ?: "")
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg")), "任意键面放过之后应到种子")
        val rows = readBootTrace()
        // 台账形状：1 行 ANYKEY_AUTO（不消费步）+ 1 行 STEP（消费第 1 步）+ 1 行 SEED
        assertEquals(3, rows.size, rows.toString())
        assertEquals("ANYKEY_AUTO", rows[0]["event"])
        assertEquals("0", rows[0]["step"], "任意键面不推进步序")
        assertEquals("0", rows[0]["pendingInput"], "放行的面不该吞掉步值，待喂的仍是第 1 步")
        assertTrue((rows[0]["stackTop"] ?: "").isNotBlank(), "放行的面自带栈顶")
        assertEquals("STEP", rows[1]["event"])
        assertEquals("1", rows[1]["step"])
        assertEquals("false", rows[1]["anyKey"], "第 1 步应落在取值面上判定期望")
        assertEquals("SEED", rows[2]["event"])
        // 自动放行不喂输入 ⇒ 不该出现在种子打点之前的任何重放链里（链 = 字面喂过的输入序列）
        assertTrue(Files.isRegularFile(outDir.resolve("seed.dbg.json")), "种子侧车应随种子一起落盘")
    }

    /**
     * 纯任意键死循环护栏：任意键面不经食谱 ⇒ 没有闸就会一路放行到墙钟尽头。
     * 合成语料造一个 PRINTW 循环（10 万圈），引导应在自动放行累计超上限时报红并留下台账。
     */
    @Test
    fun anyKeyAutoPassGuardFailsBootAtLimit() {
        val (result, _) = drive(
            withSystemTitle = true,
            gameDirName = "g6",
            titleErb = """
                @SYSTEM_TITLE
                #DIM DYNAMIC EXK
                FOR EXK, 0, 100000
                	PRINTW ex-anykey-spin
                NEXT
                INPUT
                BEGIN FIRST
            """.trimIndent(),
            recipeLines = listOf("0 ;; ex-never-on-this-face"),
        )
        assertNotNull(result.bootFailure, "纯 anykey 环必须有闸")
        assertTrue("自动放行" in result.bootFailure!!, "报红应指到自动放行上限：" + result.bootFailure)
        val rows = readBootTrace()
        assertEquals(
            ExploreDefaults.BOOT_ANYKEY_AUTO_MAX + 1,
            rows.count { it["event"] == "ANYKEY_AUTO" },
            "自动放行行数 = 上限 + 触发上限的那一次",
        )
        assertTrue(rows.none { it["event"] == "STEP" }, "纯任意键环里一步食谱都不该被消费：" + rows.last())
        assertEquals(
            (ExploreDefaults.BOOT_ANYKEY_AUTO_MAX + 1).toString(),
            rows.last()["autoCount"],
            "触发上限的那一行就是最后一行，且自带 autoCount",
        )
    }

    /** 读回引导逐步台账（每行一个 JSON 对象，键值一律按字符串比）。 */
    private fun readBootTrace(): List<Map<String, String>> =
        Files.readAllLines(outDir.resolve(ExploreDefaults.BOOT_STEPS_LEDGER), Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { line ->
                val rows = Regex("\"([A-Za-z]+)\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line)
                rows.associate { it.groupValues[1] to it.groupValues[2] }
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
