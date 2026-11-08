package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ENGFIX-3 · 官方死循环保护（**墙钟 + 问玩家**）的移植侧真值表。
 *
 * ## oracle（本文件每条断言都对着这两处，行号是复核过的）
 * * skia `unpacked/skia-decompiled/MinorShift.Emuera.GameProc/Process.cs:470-489`
 *   （`checkInfiniteLoop`）与 `:989-992`（采样频率），复位对 `:464-468`
 *   （`UpdateCheckInfiniteLoopState`，全工程唯一调用点 `EmueraConsole.cs:849` 的 `Await`）
 *   与 `:415-418`（`DoScript` 入口）；官方同文写法在
 *   `reference/emuera-cs/MinorShift.Emuera.GameProc/Process.cs:493-512` 与 `:1012-1014`。
 * * 配置项：skia/官方 `ConfigData.cs:75` 注册缺省 5000 ms（`> 0` 是开关，0 = 完全关闭），
 *   本仓唯一登记处 `core/config/ConfigSpec.kt:132`。
 *
 * ## 形状（移植侧怎么落这三条硬结论）
 * 1. **计数单位是行**：移植侧的行 = 语句 marker 的派发次数（`op in 29..197`，
 *    一条 ERB 语句行恰好一枚 marker）；每满 [VirtualMachine] 的 `INFINITE_LOOP_CHECK_LINES`
 *    = 10000 行才看一次时钟（≙ 原版的 `state.lineCount % 10000 == 0`）。
 * 2. **判据是墙钟**：段落起点 ≙ 原版 `startTime`，复位点 = 让出边界
 *    （[VmHost.yieldBoundaryCount]）与「玩家答继续」。
 * 3. **撞线不是异常**：经 [VmHost.askInfiniteLoop] 问宿主一次，答案决定一切 ——
 *    [InfiniteLoopAnswer.Continue] ⇒ 行数与计时双双归零继续跑；
 *    [InfiniteLoopAnswer.Exit] ⇒ 才抛 [StepLimitExceeded]
 *    （[LoopGuardCause.PlayerChoseExit]）；
 *    [InfiniteLoopAnswer.NoUi] ⇒ skia `:478` 的 `view.Enabled == false` 分支，
 *    **什么都不做**（不归零、不中止，下一个 10000 行界再按同样判据看一眼）。
 *
 * ⇒ 本文件的核心不变量：**只要宿主没有明确答「退出」，跑多久、跑多少行都不会中止脚本**。
 * 步数/时长那两条自造闸退成 [LoopGuardCause.DeviceBudget]（见 [VmConfig] 类头），
 * 本文件的用例一律把它们推到不可能触发的位置，确保测的是官方那一维。
 *
 * ## 时钟的确定性
 * core 直接用 `System.nanoTime()`，测试不给假时钟（引擎侧也没有可注入的时钟位）。
 * 需要「墙钟已越过阈值」时，宿主在**每个 10000 行段落的第一条语句**上真实 `sleep`
 * 一次（阈值取 1 ms），于是不依赖机器速度：段落里必然攒够墙钟。
 * 反过来「不该问」的用例靠**行数不到 10000** 或**阈值为 0** 保证，与快慢无关。
 */
class InfiniteLoopGuardTest {

    private companion object {
        /** 原版采样频率（`Process.cs:990` 的 `state.lineCount % 10000 == 0`）。 */
        const val CHECK_LINES = 10_000

        /** 本文件统一用的墙钟阈值：1 ms（> 0 即开关打开，口径同 `Process.cs:989`）。 */
        const val ALERT_MS = 1L

        /** 段落里真实睡一次，保证该段落的墙钟读数必过 [ALERT_MS]。 */
        const val SEGMENT_SLEEP_MS = 5L
    }

    /** 语句 marker 的载体：`PRINTL`，语义全在测试宿主里做（判据与真语料无关）。 */
    private fun printl(): Statement =
        InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL")

    /**
     * `iterations` 次语句 marker 派发的循环体（`_ADD`/`_LT`/`_JUMP*` 都不是语句 marker，
     * 因此**行数 == iterations**，逐行推进与原版 `lineCount` 一一对得上）。
     */
    private fun markerLoop(iterations: Int): Program {
        val src = """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R3, $iterations
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _LT R2, R0, R3
              _JUMP_IF_FALSE R2, done
              _JUMP loop
            done:
              _HALT
        """.trimIndent()
        return Assembler.assemble(src, statements = listOf(printl()))
    }

    /**
     * 记录每一次告警的宿主。
     *
     * @param answer 对每次告警的答复（[InfiniteLoopAnswer.Exit] 带语言档）
     * @param sourceName 覆写 [VmHost.infiniteLoopSourceName] 的返回值（null = 不覆写，走缺省）
     * @param sleepPerSegment 每段第一条语句真实睡一次，让该段墙钟必然越过阈值
     */
    private class AlertHost(
        private val answer: InfiniteLoopAnswer,
        private val sourceName: String? = null,
        private val sleepPerSegment: Boolean = true,
    ) : VmHost {
        override val evalContext: EvalContext? = null

        /** 派发过的语句 marker 次数 = 移植侧的「行」。 */
        var dispatches = 0
            private set

        /** 最近一次派发时宿主看到的键面行号（[StmtEnv.line]，与 VM 同一口径）。 */
        var lastEnvLine = -1
            private set

        /** 宿主收到的告警现场，按到达顺序。 */
        val alerts = mutableListOf<InfiniteLoopAlert>()

        /**
         * 每次告警发生时，宿主**上一条**已派发的 marker 的键面行号（[StmtEnv.line]）。
         * 本文件的循环体只有一行 marker，所以它与触发告警的那枚 marker 同一源行；
         * 用它比拿 `vm.lastPc` 反推行号更准（后者在 run 结束后已是 `_HALT` 那一行）。
         */
        val lineAtAsk = mutableListOf<Int>()

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            dispatches++
            lastEnvLine = env.line
            if (sleepPerSegment && dispatches % CHECK_LINES == 1) {
                Thread.sleep(SEGMENT_SLEEP_MS)
            }
            return StmtOutcome.Normal
        }

        override fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
            alerts += alert
            lineAtAsk += lastEnvLine
            return answer
        }

        override fun infiniteLoopSourceName(functionKey: String?): String? = sourceName
    }

    /** 只跑官方那一维：两个装置预算推到不可能触发（`Long.MAX_VALUE` / 0 = 关）。 */
    private val officialOnly = VmConfig(
        maxStepsPerRun = Long.MAX_VALUE,
        maxRunMillis = 0L,
        infiniteLoopAlertTimeMs = ALERT_MS,
    )

    // ------------------------------------------------------------------
    // 1. 合法重负载不再被判死
    // ------------------------------------------------------------------

    /**
     * 原版结论 1 的直接锁：**没有告警通道**（缺省实现 = [InfiniteLoopAnswer.NoUi]）时，
     * 重负载跑满 5 个「10000 行 + 墙钟越限」的段落也一次都不中止 ——
     * skia `Process.cs:478` 的 `view.Enabled == false` 分支连告警都不发。
     */
    @Test
    fun `没有告警通道的宿主跑满重负载也不中止`() {
        val program = markerLoop(5 * CHECK_LINES)
        val quietHost = object : VmHost {
            override val evalContext: EvalContext? = null
            var dispatches = 0
                private set

            override fun print(text: String) {}

            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
                dispatches++
                // 段落第一条睡一次：墙钟一定越过阈值，只有「不弹框就不中止」这条能救它。
                if (dispatches % CHECK_LINES == 1) Thread.sleep(SEGMENT_SLEEP_MS)
                return StmtOutcome.Normal
            }
            // askInfiniteLoop 不覆写 ⇒ 缺省 NoUi（≙ 原版没有可用 UI）。
        }
        val vm = VirtualMachine(program, quietHost, officialOnly)

        vm.run()

        assertEquals(5 * CHECK_LINES, quietHost.dispatches, "整条链必须一行不少跑完")
        assertTrue(vm.executedSteps > 5L * CHECK_LINES, "正常返回：executedSteps=${vm.executedSteps}")
    }

    /** 阈值 0 = 整条保护关闭（`Process.cs:989` 的 `Config.InfiniteLoopAlertTime > 0` 开关位）。 */
    @Test
    fun `阈值 0 时完全关闭：一次都不问`() {
        val program = markerLoop(3 * CHECK_LINES)
        val host = AlertHost(answer = InfiniteLoopAnswer.Exit(), sleepPerSegment = true)
        val vm = VirtualMachine(
            program,
            host,
            VmConfig(maxStepsPerRun = Long.MAX_VALUE, infiniteLoopAlertTimeMs = 0L),
        )

        vm.run()

        assertEquals(0, host.alerts.size, "InfiniteLoopAlertTime = 0 ⇒ 连采样都不做")
        assertEquals(3 * CHECK_LINES, host.dispatches)
    }

    // ------------------------------------------------------------------
    // 2. 采样频率：每 10000 行一次（不是每条指令、不是每一秒）
    // ------------------------------------------------------------------

    /** 行数不足 10000 ⇒ 墙钟再久也不过问一句（原版 `lineCount % 10000 == 0` 那一刀）。 */
    @Test
    fun `不足一万行时即使墙钟早已越限也不告警`() {
        val program = markerLoop(CHECK_LINES - 1000)
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(0, host.alerts.size, "9000 行 ⇒ 采样点还没到，问一次都算过采样")
        assertEquals(CHECK_LINES - 1000, host.dispatches)
    }

    /** 恰好一万行的第一枚 marker 上是第一个采样点（≙ `lineCount` 满 10000 那一次）。 */
    @Test
    fun `第一个采样点恰在第九千九百九十九行之后`() {
        val program = markerLoop(CHECK_LINES + 50)
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(1, host.alerts.size, "只越过一次 10000 行界")
        val alert = host.alerts.single()
        assertEquals(CHECK_LINES.toLong(), alert.linesExecuted, "采样点 = 第 10000 行：${alert.linesExecuted}")
        assertTrue(alert.elapsedMs >= ALERT_MS, "告警必须带越过阈值的墙钟读数：${alert.elapsedMs}")
        assertEquals(ALERT_MS, alert.alertTimeMs, "现场要带回本次触发用的阈值，事后才答得出「为什么问」")
        assertEquals(CHECK_LINES + 50, host.dispatches)
    }

    /** 玩家答「继续」⇒ 行数与计时**成对归零**，于是每满一万行才再问一次。 */
    @Test
    fun `继续之后行数归零，每段各问一次`() {
        val program = markerLoop(5 * CHECK_LINES)
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(5, host.alerts.size, "五段落 ⇒ 五次采样，且一次都没中止")
        host.alerts.forEachIndexed { i, alert ->
            assertEquals(
                CHECK_LINES.toLong(),
                alert.linesExecuted,
                "第 ${i + 1} 次的行数必须从 0 重算（skia Process.cs:486-487 的成对归零），" +
                    "实际 ${alert.linesExecuted}",
            )
            assertTrue(
                alert.elapsedMs >= ALERT_MS,
                "第 ${i + 1} 次的计时也要从段落起点重启（:487 的 startTime.Restart）：${alert.elapsedMs}",
            )
        }
        assertEquals(5 * CHECK_LINES, host.dispatches, "从头到尾一行没少")
    }

    /**
     * skia 与「只归零继续」的差别锁在这里：`view.Enabled == false` 那条分支
     * **一个字段都不改**（`Process.cs:478` 的 if 块整体跳过），所以行数继续累加、
     * 下一问落在 20000 行而不是再一个 10000 行。
     */
    @Test
    fun `没有 UI 时什么都不做：不归零也不中止`() {
        val program = markerLoop(3 * CHECK_LINES)
        val host = AlertHost(answer = InfiniteLoopAnswer.NoUi)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(3, host.alerts.size, "问在 10000 / 20000 / 30000 行：界只往后推，不是一问定终身")
        assertEquals(CHECK_LINES.toLong(), host.alerts[0].linesExecuted)
        assertEquals(
            2L * CHECK_LINES,
            host.alerts[1].linesExecuted,
            "NoUi 是 skia 的「什么都不做」，不是「归零继续」：行数要能看出没被清零",
        )
        assertEquals(
            3L * CHECK_LINES,
            host.alerts[2].linesExecuted,
            "第三问同样带着**累计**行数 ⇒ 复位只发生在「玩家答继续」那一条",
        )
        assertEquals(3 * CHECK_LINES, host.dispatches)
    }

    // ------------------------------------------------------------------
    // 3. 只有玩家答「退出」才中止，且文案是官方那句
    // ------------------------------------------------------------------

    @Test
    fun `玩家选退出时抛官方那句 CodeEE`() {
        val program = markerLoop(2 * CHECK_LINES)
        val host = AlertHost(answer = InfiniteLoopAnswer.Exit())
        val vm = VirtualMachine(program, host, officialOnly)

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }

        assertEquals(1, host.alerts.size, "第一次告警就该终止，不再往后跑")
        assertEquals(LoopGuardCause.PlayerChoseExit, e.guardCause)
        assertEquals(
            InfiniteLoopTexts.EXIT_ZHS,
            e.message,
            "玩家退出路径的 message 必须是官方 Lang.Error.SelectExitInfiniteLoopMB 原文，" +
                "一个字都不许加（skia Process.cs:484）",
        )
        assertNotNull(e.alert, "现场随异常带回：宿主事后要能说出为什么问、答了什么")
        assertEquals(CHECK_LINES.toLong(), e.alert!!.linesExecuted)
        assertEquals(ALERT_MS, e.alert!!.alertTimeMs)
        assertTrue(e.alert!!.elapsedMs >= ALERT_MS)
        assertTrue(e.line >= 1, "抛点带行号：${e.line}")
        assertTrue(e.pc >= 0, "抛点带 pc：${e.pc}")
        assertEquals("main", e.funcName)
        // 采样点在那枚 marker **派发之前**（判据不过就先问，答退出就直接终止），
        // 所以走到抛点时已派发 9999 条、第 10000 条尚未执行 ⇒ 一行都不多跑。
        assertEquals(CHECK_LINES - 1, host.dispatches)
    }

    /** 框用日文画，中止那句也必须是日文（框与屏上的错同语言，口径见 [InfiniteLoopTexts]）。 */
    @Test
    fun `退出文案跟随宿主画框用的语言档`() {
        val ja = AlertHost(answer = InfiniteLoopAnswer.Exit(textVariant = com.eratw.port.core.lexer.LangVariant.JA))
        val vmJa = VirtualMachine(markerLoop(CHECK_LINES + 10), ja, officialOnly)
        val eJa = assertFailsWith<StepLimitExceeded> { vmJa.run() }
        assertEquals(InfiniteLoopTexts.EXIT_JA, eJa.message)

        val zhs = AlertHost(answer = InfiniteLoopAnswer.Exit())
        val vmZhs = VirtualMachine(markerLoop(CHECK_LINES + 10), zhs, officialOnly)
        val eZhs = assertFailsWith<StepLimitExceeded> { vmZhs.run() }
        assertEquals(InfiniteLoopTexts.EXIT_ZHS, eZhs.message)
        assertEquals(
            ja.alerts.single().textVariant,
            com.eratw.port.core.lexer.LangVariant.ZHS,
            "VM 问的时候按使用者面缺省档给现场（宿主可以改档再答）",
        )
    }

    // ------------------------------------------------------------------
    // 4. 告警现场的三件来源
    // ------------------------------------------------------------------

    /** 正文第 0 位是**源文件名**（原版 `currentLine.Position.Value.Filename`，`Process.cs:481`）。 */
    @Test
    fun `宿主给得出源文件名时现场用它`() {
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue, sourceName = "test/loop.erb")
        val vm = VirtualMachine(markerLoop(CHECK_LINES + 10), host, officialOnly)

        vm.run()

        val alert = host.alerts.single()
        assertEquals("test/loop.erb", alert.sourceName)
        assertEquals("test/loop.erb", alert.displaySource)
        assertTrue(alert.message().contains("test/loop.erb"), "正文第 0 位用文件名：${alert.message()}")
    }

    /** 装载产物里没有文件名（裸 Program / `.erbc` v1）⇒ 回落函数书写名，**不编造文件名**。 */
    @Test
    fun `宿主给不出源文件名时回落函数名`() {
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(markerLoop(CHECK_LINES + 10), host, officialOnly)

        vm.run()

        val alert = host.alerts.single()
        assertNull(alert.sourceName, "缺省 infiniteLoopSourceName 返回 null ⇒ 现场如实说没有文件名")
        assertEquals("main", alert.functionName)
        assertEquals("main", alert.displaySource)
    }

    /** 行号与 pc 落在告警那一刻的语句上（取证用，判据本身不看它们）。 */
    @Test
    fun `现场带当前行号且指向告警所在的那条语句`() {
        val program = markerLoop(CHECK_LINES + 10)
        val host = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        val alert = host.alerts.single()
        // 现场行号 = 键面行号（[StmtEnv.line]，产线口径 = `Program.lineOf`，0 基）经
        // 全工程唯一出口 `CodeEE.kt:98` 的 `erbDisplayLine` 归一 ⇒ **只加一次一**。
        // 注意本文件用的是 `Assembler` 的 fixture，它的行表本来就是 1 基
        // （`Assembler.kt:122` 的 `currentLine = idx + 1`，`CodeEE.kt:84-86` 已把那条登记为
        // 「不同坐标系、不该接本出口」的特例），所以这里断言的是**关系**（差恰好 1）而不是绝对值。
        assertEquals(
            host.lineAtAsk.single() + 1,
            alert.lineNo.toInt(),
            "现场行号必须只归一一次：现场 ${alert.lineNo}，键面 ${host.lineAtAsk.single()}",
        )
        assertTrue(alert.lineNo >= 1L, "行号下界：${alert.lineNo}")
    }

    // ------------------------------------------------------------------
    // 5. 段落边界：让出即复位（原版成对复位的那一处）
    // ------------------------------------------------------------------

    /**
     * 让出边界（输入等待返回 / `AWAIT` 逐帧让出）之后，行数与墙钟**成对归零**：
     * ≙ skia `Process.cs:464-468` 的 `UpdateCheckInfiniteLoopState()`
     * （`EmueraConsole.cs:849` 的 `Await` 里调）与 `:415-418` 的 `DoScript` 入口。
     * 于是「每次只跑几千行、总共跑几万次」的正常游戏节奏一次都不会被告警。
     */
    @Test
    fun `让出边界成对归零行数与计时`() {
        val segments = 6
        val perSegment = CHECK_LINES / 2 // 每段 5000 行 ⇒ 单看段落永远攒不到采样点
        val src = """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R3, ${segments * perSegment}
            loop:
              PRINTL stmt#0
              _ADD R0, R0, R1
              _LT R2, R0, R3
              _JUMP_IF_FALSE R2, done
              _JUMP loop
            done:
              _HALT
        """.trimIndent()
        val program = Assembler.assemble(src, statements = listOf(printl()))
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            var dispatches = 0
                private set
            var boundaries = 0L
                private set
            val alerts = mutableListOf<InfiniteLoopAlert>()

            override fun print(text: String) {}

            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
                dispatches++
                if (dispatches % perSegment == 1) Thread.sleep(SEGMENT_SLEEP_MS)
                // 每段结尾模拟一次真实让出（输入等待返回）：边界读数 +1
                if (dispatches % perSegment == 0) boundaries++
                return StmtOutcome.Normal
            }

            override fun yieldBoundaryCount(): Long = boundaries

            override fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
                alerts += alert
                return InfiniteLoopAnswer.Continue
            }
        }
        val vm = VirtualMachine(program, host, officialOnly)

        vm.run()

        assertEquals(segments * perSegment, host.dispatches)
        assertEquals(segments, host.boundaries.toInt(), "六次让出边界")
        assertEquals(
            0,
            host.alerts.size,
            "总行数 ${segments * perSegment} 早已过一万，但每一段只有 $perSegment 行 ⇒ " +
                "按段落复位的口径一次都不该问（原版同形）",
        )
    }

    // ------------------------------------------------------------------
    // 6. 官方判据与装置预算互不越权
    // ------------------------------------------------------------------

    /**
     * 装置预算（[VmConfig.maxStepsPerRun]，[LoopGuardCause.DeviceBudget]）仍然是**独立**那条：
     * 它撞线时不带告警现场、文案是移植层的步数句，且**绝不会被官方那一维触发**。
     * 反过来，官方那一维撞线时不看步数。两条路各走各的，宿主按成因分开收口。
     */
    @Test
    fun `装置预算路径与官方告警路径的现场互不越权`() {
        // (a) 官方告警开着、步数预算也开着：一万行的段落先撞**告警**（预算给足）。
        val alertHost = AlertHost(answer = InfiniteLoopAnswer.Continue)
        val alertingVm = VirtualMachine(
            markerLoop(CHECK_LINES + 10),
            alertHost,
            VmConfig(maxStepsPerRun = Long.MAX_VALUE, infiniteLoopAlertTimeMs = ALERT_MS),
        )
        alertingVm.run()
        assertEquals(1, alertHost.alerts.size)

        // (b) 同一程序、官方告警关掉、步数预算收到很小：撞的是**预算**，没有告警现场。
        val budgetHost = AlertHost(answer = InfiniteLoopAnswer.Exit(), sleepPerSegment = false)
        val budgetVm = VirtualMachine(
            markerLoop(CHECK_LINES + 10),
            budgetHost,
            VmConfig(maxStepsPerRun = 500L, infiniteLoopAlertTimeMs = 0L),
        )
        val e = assertFailsWith<StepLimitExceeded> { budgetVm.run() }
        assertEquals(LoopGuardCause.DeviceBudget, e.guardCause)
        assertNull(e.alert, "预算路径不产出官方告警现场")
        assertTrue(
            e.message.orEmpty().contains("执行步数超过上限(501)，疑似死循环"),
            "既有步数文案逐字不变（台账/失败屏口径）：${e.message}",
        )
        assertEquals(0, budgetHost.alerts.size, "阈值 0 ⇒ 官方那一维一次都没问过")
    }
}
