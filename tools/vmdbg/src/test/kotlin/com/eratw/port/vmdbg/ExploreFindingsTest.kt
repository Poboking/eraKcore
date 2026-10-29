package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.FuncMeta
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTRACT-DBG6 §4.E2.3 ③④：findings 分类矩阵 + replay 往返，外加 coverage 落盘面
 * （单元直喂探针回调 + MiniCorpus 合成语料的真探针挂卸各一）。
 * 全部合成样本：临时目录 + MiniCorpus，不碰真语料、不依赖 ERATW_* 环境变量。
 */
class ExploreFindingsTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("vmdbg-explore-out-test")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** 合成 INT 等待点（缺省 = 按钮值 0/1、无限时缺省、栈顶 FN:1、单行尾）。 */
    private fun intWp(
        tail: List<String> = listOf("标题菜单"),
        stackTop: String = "FN:1",
    ) = WaitPoint(
        request = InputRequest(type = InputRequest.InputType.INT),
        anyKey = false,
        buttons = listOf(ButtonOpt("[0]是", 0), ButtonOpt("[1]否", 1)),
        consoleTail = tail,
        stackTop = stackTop,
    )

    /** findings 行的 kind 提取（行含数组字段，Jsonl.parseLine 读不了，按原文子串取）。 */
    private fun kindOf(line: String): String = line.substringAfter("\"kind\":\"").substringBefore('"')

    // ------------------------------------------------------------------
    // ③ 发现分类矩阵
    // ------------------------------------------------------------------

    @Test
    fun findingKindMatrix() {
        assertEquals(FindingKind.CRASH, ExploreOut.findingKindOf(OutcomeKind.CRASH))
        assertEquals(FindingKind.WALL_LIMIT, ExploreOut.findingKindOf(OutcomeKind.WALL_LIMIT))
        assertEquals(FindingKind.STEP_LIMIT, ExploreOut.findingKindOf(OutcomeKind.STEP_LIMIT))
        assertNull(ExploreOut.findingKindOf(OutcomeKind.ADVANCED), "正常推进非发现")
        assertNull(ExploreOut.findingKindOf(OutcomeKind.ABANDONED), "弃局非发现")
        assertNull(ExploreOut.findingKindOf(OutcomeKind.EPISODE_DONE), "局终非发现")
    }

    @Test
    fun floodRunLengthMatrix() {
        assertEquals(0, ExploreOut.maxConsecutiveErrorLines(emptyList()))
        assertEquals(0, ExploreOut.maxConsecutiveErrorLines(listOf("正常", "没问题")))
        assertEquals(3, ExploreOut.maxConsecutiveErrorLines(listOf("错误a", "错误b", "错误c")))
        assertEquals(3, ExploreOut.maxConsecutiveErrorLines(listOf("x", "错误a", "错误b", "错误c", "y", "错误d")), "取最长连续段")
        assertEquals(6, ExploreOut.maxConsecutiveErrorLines((1..6).map { "错误行$it" }))
        // run-length 语义以实现为准：返回最长连续段长本身；是否构成发现由调用方按 ≥阈值
        // 判定（单行「错误」段长 1 < 5，不构成 CODEEE_FLOOD）。
        assertEquals(1, ExploreOut.maxConsecutiveErrorLines(listOf("错误")))
        assertEquals(2, ExploreOut.maxConsecutiveErrorLines(listOf("エラー1", "エラー2"), "エラー"), "标记可换（真语料若为日文报错可换针）")
    }

    @Test
    fun errorMarkerHitsEngineErrorWording() {
        // DBG7 的 TODO(verify) 闭环：ERROR_MARKER=「错误」对实测错误文案的命中矩阵。
        // 合同钉值本身也锁死（改动须同步核对本矩阵）。
        assertEquals("错误", ExploreOut.ERROR_MARKER, "CONTRACT-DBG6 §4.E2.2 钉值")
        // 命中①：本引擎唯一的控制台错误通道 printError 全库唯一落点（core-vm
        // sem/StatementExecutor.kt:1038，SAVEDATA 意外错误原文）。
        assertEquals(1, ExploreOut.maxConsecutiveErrorLines(listOf("SAVEDATA命令在存档时发生了意料之外的错误")))
        // 命中②：原版脚本错误的控制台行模板（C# Process.cs:618-653 全走 PrintError；
        // 中文模板 emuera-zhs.xml:696「{0}错误发生在:{1}」/ :698「错误内容:{0}」）。
        assertEquals(
            2,
            ExploreOut.maxConsecutiveErrorLines(
                listOf("EVENTFIRST错误发生在:ERB/EVENTFIRST.ERB:12", "错误内容:变量 DAY 越界（角色数 1）"),
            ),
        )
        // 边界：宿主停机文案（emuera-zhs.xml:1021，FLOW.md §3 引）不含「错误」——它不经控制台
        // 错误行通道（本引擎 CodeEE 以异常上抛给宿主，不落 console），不构成需要 FLOOD 捕获的行。
        assertEquals(0, ExploreOut.maxConsecutiveErrorLines(listOf("由于ERB脚本出现无法解释的行，Emuera停止运行")))
    }

    @Test
    fun endToEndLedgerFindingsAndReplay() {
        val dir = tmp.resolve("out")
        val out = ExploreOut(dir)
        out.open()
        out.logBootStep("0", "标题")
        out.logBootStep("0", "模式选择")
        val wpA = intWp()
        out.logStep(1, wpA, ExploreAction.FeedInt(0), OutcomeKind.ADVANCED)
        // 洪泛尾：连续 6 行含「错误」→ logStep 自动记一条 CODEEE_FLOOD
        val floodTail = (1..6).map { "错误行$it" }
        val wpFlood = intWp(tail = floodTail)
        out.logStep(1, wpFlood, ExploreAction.FeedInt(1), OutcomeKind.ADVANCED)
        val fpFlood = waitPointFingerprint(wpFlood)
        // 驱动器显式落 CRASH（带终态现场）
        out.finding(
            FindingKind.CRASH, "boom: CodeEE",
            episode = 1, fingerprint = fpFlood, wp = wpFlood,
            consoleTail = floodTail + listOf("由于ERB脚本出现无法解释的行"),
        )
        // 干净尾 → 洪泛段复位；再洪泛 → 第二条 CODEEE_FLOOD（不与首条合并）
        out.logStep(1, intWp(tail = listOf("正常行")), ExploreAction.FeedInt(2), OutcomeKind.ADVANCED)
        out.logStep(1, intWp(tail = (1..5).map { "错误x$it" }), ExploreAction.FeedInt(3), OutcomeKind.ADVANCED)
        out.close()

        // explore.jsonl：4 行、逐步字段齐
        val steps = Files.readAllLines(dir.resolve("explore.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(4, steps.size, "四步四行")
        val s0 = Jsonl.parseLine(steps[0])!!
        assertEquals("1", s0["episode"])
        assertEquals("ADVANCED", s0["outcome"])
        assertEquals("FeedInt(0)", s0["action"])
        assertEquals("0,1", s0["btns"], "按钮值逗号串")
        assertEquals("VALUE/INT def=- t=-1", s0["req"], "request 摘要")
        assertEquals(waitPointFingerprint(wpA), s0["fp"], "指纹 = waitPointFingerprint")
        assertTrue(s0["plan"]!!.contains("FeedInt(0)"), "plan 含候选计划")
        assertTrue(s0["ts"]!!.toLong() > 0, "ts 为 epoch 毫秒")

        // findings.jsonl：kind 序 = 自动 FLOOD、显式 CRASH、二次自动 FLOOD
        val findings = Files.readAllLines(dir.resolve("findings.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(3, findings.size)
        assertEquals(listOf("CODEEE_FLOOD", "CRASH", "CODEEE_FLOOD"), findings.map { kindOf(it) })

        // CRASH 行：全链（boot 2 步 + explore 2 步）+ 超时位 + 终态尾 + 现场字段
        val crash = findings[1]
        assertTrue("\"episode\":1" in crash, crash)
        assertTrue("\"stackTop\":\"FN:1\"" in crash, crash)
        assertTrue("\"req\":\"VALUE/INT def=- t=-1\"" in crash, crash)
        assertTrue(
            "\"chain\":[\"0 ;; 标题\",\"0 ;; 模式选择\",\"0 ;; ADVANCED\",\"1 ;; ADVANCED\"]" in crash,
            crash,
        )
        assertTrue("\"chainTimeout\":[false,false,false,false]" in crash, crash)
        assertTrue("由于ERB脚本出现无法解释的行" in crash, "console 尾随行逐字保真")
        assertTrue("\"fp\":\"$fpFlood\"" in crash, crash)

        // replay/2.txt（CRASH = 第 2 条发现）可按 boot 食谱语法整读
        val replay = parseBootRecipe(Files.readString(dir.resolve("replay").resolve("2.txt"), Charsets.UTF_8))
        assertEquals(
            listOf("0" to "标题", "0" to "模式选择", "0" to "ADVANCED", "1" to "ADVANCED"),
            replay,
            "重放链 = 种子以来全部已喂输入",
        )
        assertTrue(Files.isRegularFile(dir.resolve("replay").resolve("1.txt")), "每条发现各一份重放链")
        assertTrue(Files.isRegularFile(dir.resolve("replay").resolve("3.txt")))
    }

    @Test
    fun stepLimitFindingAndTimeoutBitFidelity() {
        val dir = tmp.resolve("out-timeout")
        val out = ExploreOut(dir)
        out.open()
        val timed = WaitPoint(
            request = InputRequest(type = InputRequest.InputType.INT, hasDef = true, defInt = 2, timeLimit = 20_000),
            anyKey = false,
            buttons = emptyList(),
            consoleTail = listOf("标题"),
            stackTop = "TITLE:0",
        )
        out.logStep(1, timed, ExploreAction.FeedInt(2, asTimeout = true), OutcomeKind.WALL_LIMIT)
        // 驱动器按 findingKindOf 分流落 WALL_LIMIT；超时位在 chainTimeout 保真
        out.finding(ExploreOut.findingKindOf(OutcomeKind.WALL_LIMIT)!!, "墙钟超限", episode = 1)
        out.close()
        val findings = Files.readAllLines(dir.resolve("findings.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(1, findings.size)
        val line = findings[0]
        assertTrue("\"kind\":\"WALL_LIMIT\"" in line, line)
        assertTrue("\"chain\":[\"2 ;; WALL_LIMIT\"]" in line, line)
        assertTrue("\"chainTimeout\":[true]" in line, "asTimeout 位在发现行保真")
        assertEquals(
            listOf("2" to "WALL_LIMIT"),
            parseBootRecipe(Files.readString(dir.resolve("replay").resolve("1.txt"), Charsets.UTF_8)),
            "超时步的重放行 = 交付值（位本体在 chainTimeout）",
        )
    }

    // ------------------------------------------------------------------
    // ④ replay 往返（boot 食谱语法）
    // ------------------------------------------------------------------

    @Test
    fun recipeLineRoundTrip() {
        // 渲染形状（分隔符两侧各一空格）：非空输入无注记 = 裸行；空输入（PassAnyKey）恒带分隔符
        assertEquals("0 ;; 标题", renderRecipeLine("0", "标题"))
        assertEquals(" ;; anykey", renderRecipeLine("", "anykey"))
        assertEquals("abc", renderRecipeLine("abc", ""))
        assertEquals(" ;;", renderRecipeLine("", ""))
        // 往返：渲染 → 解析 → 原对
        val cases = listOf(
            Pair("0", "标题"),
            Pair("", "anykey"),
            Pair("abc", ""),
            Pair("2", "WALL_LIMIT"),
            Pair("9999", ""),
        )
        for ((input, note) in cases) {
            val line = renderRecipeLine(input, note)
            val (ri, rn) = parseBootRecipeLine(line)!!
            assertEquals(input, ri, "输入往返: $line")
            assertEquals(note, rn, "注记往返: $line")
        }
        // 注释与空行跳过；无分隔符整行作输入
        assertEquals(0, parseBootRecipe("# 注释\n\n   \n").size)
        assertEquals(listOf("1" to "", "" to "x"), parseBootRecipe("1\n ;;x"))
        assertNull(parseBootRecipeLine(""))
    }

    // ------------------------------------------------------------------
    // 编号与 --resume 语义
    // ------------------------------------------------------------------

    @Test
    fun resumeContinuesNumberingAndFreshRunTruncates() {
        val dir = tmp.resolve("out-resume")
        val first = ExploreOut(dir)
        first.open()
        first.logBootStep("0", "标题")
        first.finding(FindingKind.CRASH, "第一次")
        first.close()
        assertEquals(1, Files.readAllLines(dir.resolve("findings.jsonl"), Charsets.UTF_8).count { it.isNotBlank() })

        // --resume：续号，旧发现保留；链按进程口径重记（resume = 新进程新种子，引导链重走）
        val second = ExploreOut(dir, resume = true)
        second.open()
        second.logBootStep("5", "续跑步")
        second.finding(FindingKind.WALL_LIMIT, "第二次")
        second.close()
        val lines = Files.readAllLines(dir.resolve("findings.jsonl"), Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertEquals(listOf("CRASH", "WALL_LIMIT"), lines.map { kindOf(it) })
        assertEquals(
            listOf("5" to "续跑步"),
            parseBootRecipe(Files.readString(dir.resolve("replay").resolve("2.txt"), Charsets.UTF_8)),
            "resume 的重放链 = 本进程种子以来序列（不跨进程合并）",
        )

        // 非 resume：清空重来，编号重置
        val third = ExploreOut(dir)
        third.open()
        assertTrue(!Files.isRegularFile(dir.resolve("findings.jsonl")), "非 resume open 清掉旧 findings")
        third.finding(FindingKind.STEP_LIMIT, "第三次")
        third.close()
        assertEquals(1, Files.readAllLines(dir.resolve("findings.jsonl"), Charsets.UTF_8).count { it.isNotBlank() })
        assertTrue(Files.isRegularFile(dir.resolve("replay").resolve("1.txt")), "编号从 1 重来")
    }

    // ------------------------------------------------------------------
    // coverage：计数 + coverage.txt
    // ------------------------------------------------------------------

    @Test
    fun coverageRecorderAccumulatesAndWritesTxt() {
        val dir = tmp.resolve("out-cov")
        val out = ExploreOut(dir)
        out.open()
        out.coverage.onFunctionEnter(FuncMeta(index = 0, key = "FN_A"))
        out.coverage.onFunctionEnter(null)
        out.coverage.onStatement("FN_A", 10)
        out.coverage.onStatement("FN_A", 10) // 同 pc 去重
        out.coverage.onStatement("FN_A", 12)
        out.coverage.onStatement("FN_B", 40)
        out.close()
        // 函数集 = FunctionProbe 进帧函数（null-meta 帧进独立计数、不进名字集）：
        // FN_B 只有语句回调、从未进帧 → 不在函数集（合同 §4.E2.2：函数集挂 FunctionProbe、
        // 语句集挂 StatementProbe，两集合来源正交）。
        assertEquals(1, out.coverage.executedFunctionCount())
        assertEquals(listOf("FN_A"), out.coverage.executedFunctionKeys())
        assertEquals(2, out.coverage.statementCountOf("FN_A"))
        assertEquals(1, out.coverage.statementCountOf("FN_B"), "语句集按 StatementProbe 口径照记")
        assertEquals(3, out.coverage.executedStatementCount())
        assertEquals(1, out.coverage.nullMetaFrameCount())

        val txt = Files.readString(dir.resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("executedFunctions=1" in txt, txt)
        assertTrue("executedStatements=3/-1" in txt, "无 program → 总数 -1、不出百分比")
        assertTrue("sweepBaseline=12.3%" in txt, "合同钉值基线")
        assertTrue("FN_A covered=2 stmts=-1" in txt, "无分母 → stmts=-1")
        assertTrue("TODO(verify)" in txt, "增量口径边界明示")
    }

    @Test
    fun coverageProbeAttachOnMiniCorpus() {
        // 真探针挂卸链（MiniCorpus 合成语料）：attach → callFunction → detach → 落盘
        val built = MiniCorpus.build(tmp)
        val work = tmp.resolve("work")
        val assembled = Assembler.assemble(Args.parse(MiniCorpus.args(built, work).toList()))
        val dir = tmp.resolve("out-cov2")
        val out = ExploreOut(dir, program = assembled.program, functions = assembled.metadata.functions)
        out.open()
        val rt = assembled.session.runtime
        out.coverage.attach(rt)
        rt.callFunction("MINI_PASS", LongArray(0), Array(0) { "" })
        out.coverage.detach(rt)
        out.close()
        // 卸下后置 null（生产零行为路径）
        assertNull(assembled.session.runtime.functionProbe)
        assertNull(assembled.session.runtime.statementProbe)

        val txt = Files.readString(dir.resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("MINI_PASS" in txt, txt)
        // 读数与 SweepTest:199-215 同 corpus 同机制基线一致：PRINTL + 编译器合成句 = 2 条
        // 语句 marker，全执行 → covered=2 / stmts=2 / 100.0%（分母 = 区间 marker 扫描）。
        assertTrue("MINI_PASS covered=2 stmts=2 pct=100.0" in txt, txt)
        assertTrue("executedFunctions=1" in txt, txt)
        assertTrue("executedStatements=2/" in txt, "全局分子 = MINI_PASS 分子（仅该函数执行）: $txt")
    }

    // ------------------------------------------------------------------
    // sweep 台账对照（DBG7：coverage.txt 的数据源两态）
    // ------------------------------------------------------------------

    @Test
    fun sweepLedgerCompareWrittenWhenLedgerPresent() {
        val dir = tmp.resolve("out-sweep-cmp")
        val sweepDir = tmp.resolve("sweep8")
        Files.createDirectories(sweepDir)
        // 形状照抄桌面 sweep 的 SweptRecord.toJson：meta 行无 key 字段自然跳过；
        // covered=-1（区间不可得）的记录滤除不进对照。
        Files.writeString(
            sweepDir.resolve("sweep.jsonl"),
            "{\"meta\":\"sweep\"}\n" +
                "{\"fn\":\"FN_A\",\"key\":\"FN_A\",\"status\":\"PASS\",\"covered\":1,\"stmts\":4}\n" +
                "{\"fn\":\"FN_B\",\"key\":\"FN_B\",\"status\":\"PASS\",\"covered\":3,\"stmts\":3}\n" +
                "{\"fn\":\"FN_C\",\"key\":\"FN_C\",\"status\":\"CODEEE\",\"covered\":-1,\"stmts\":-1}\n",
            Charsets.UTF_8,
        )
        // 目录候选 → 取其下 sweep.jsonl（--sweep-ledger <目录> 的口径）
        val out = ExploreOut(dir, sweepLedgers = listOf(sweepDir))
        out.open()
        out.coverage.onFunctionEnter(FuncMeta(index = 0, key = "FN_A"))
        out.coverage.onStatement("FN_A", 10)
        out.coverage.onStatement("FN_A", 11)
        out.coverage.onStatement("FN_A", 12) // explore 3 > sweep 1 → 多触 +2
        out.coverage.onFunctionEnter(FuncMeta(index = 1, key = "FN_B"))
        out.coverage.onStatement("FN_B", 40) // explore 1 < sweep 3 → sweep 多触
        out.close()
        val txt = Files.readString(dir.resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("[sweep 对照]" in txt, txt)
        assertTrue("数据源=" in txt && "带 covered/stmts 读数的记录 2 条" in txt, txt)
        assertTrue("共测函数=2" in txt, txt)
        assertTrue("explore 多触=1" in txt, txt)
        assertTrue("sweep 多触=1 持平=0" in txt, txt)
        assertTrue("FN_A explore=3 sweep=1 (+2)" in txt, txt)
    }

    @Test
    fun sweepLedgerMissingOrBlindLeavesTodoWithSource() {
        // 态一：台账不存在 → TODO(verify) 注明已探测路径（DBG7 实测：红 J 冒烟现场只有
        // 引导失败三件套、dbg-diff-e 是真机台账，都不是本对照数据源 —— 路径口径写进提示）。
        val dir = tmp.resolve("out-sweep-miss")
        val missing = tmp.resolve("no-such-sweep").resolve("sweep.jsonl")
        val out = ExploreOut(dir, sweepLedgers = listOf(missing))
        out.open()
        out.close()
        var txt = Files.readString(dir.resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("TODO(verify)" in txt, txt)
        assertTrue("sweep 台账数据源不存在" in txt, txt)
        assertTrue(missing.toString() in txt, "已探测路径注明：$txt")
        assertTrue("真机 hawkeye 台账" in txt, "数据源口径注明：$txt")

        // 态二：台账存在但无 covered/stmts 字段（真机 report.jsonl 即此形态）→ 对照不可做，原因注明。
        val dir2 = tmp.resolve("out-sweep-blind")
        val blind = dir2.resolve("report.jsonl")
        Files.createDirectories(blind.parent)
        Files.writeString(
            blind,
            "{\"meta\":\"device-sweep\",\"shard\":0,\"n\":8}\n" +
                "{\"fn\":\"ANOTHERTARGET_TALK\",\"key\":\"ANOTHERTARGET_TALK\",\"status\":\"CODEEE\",\"steps\":-1,\"elapsedMs\":455}\n",
            Charsets.UTF_8,
        )
        val out2 = ExploreOut(dir2.resolve("out"), sweepLedgers = listOf(blind))
        out2.open()
        out2.close()
        txt = Files.readString(dir2.resolve("out").resolve("coverage.txt"), Charsets.UTF_8)
        assertTrue("台账存在但无 covered/stmts 读数" in txt, txt)
        assertTrue("TODO(verify)" in txt, txt)
    }
}
