package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 可执行状态机：TITLE→FIRST、TRAIN 的缺失分支、脚本 BEGIN 的重入、SHOP 自动存档链、
 * Normal 的失败返回、以及每步阻塞点五元组。
 *
 * 每条断言的 C# 依据见 [SystemStateGraph] 的节点 evidence。
 */
class BeginFlowTest {

    // ------------------------------------------------------------------
    // 假体
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val functions = HashSet<String>()
        val events = HashSet<String>()
        val called = ArrayList<String>()

        var resultValue: Long = 0L
        var systemResultValue: Long = 0L
        var nextComValue: Long = -1L
        var selectComValue: Long = 7L
        var ct: Boolean = false
        var pending: BeginType? = null
        var trainCount: Int? = null
        val trainNames = HashMap<Int, String>()
        var afterCall: ((String) -> Unit)? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            val key = name.uppercase()
            val exists = if (isEvent) events.contains(key) else functions.contains(key)
            if (!exists) {
                // 假体复刻生产侧文案（Lang.Error.FuncIsNotFound，zhs:736 / Lang.cs:3126；
                // 真抛点 Process.cs:2090-2099 的 force 分支），不得留旧自造串。
                if (force) throw CodeEE("找不到函数\"@$name\"")
                return false
            }
            called.add(name)
            afterCall?.invoke(key)
            return true
        }

        override fun result(): Long = resultValue
        override fun setResult(value: Long) {
            resultValue = value
        }
        override fun systemResult(): Long = systemResultValue
        override fun setSystemResult(value: Long) {
            systemResultValue = value
        }

        override fun nextCom(): Long = nextComValue
        override fun setNextCom(value: Long) {
            nextComValue = value
        }

        override fun selectCom(): Long = selectComValue
        override fun setSelectCom(value: Long) {
            selectComValue = value
        }

        override fun isCTrain(): Boolean = ct
        override fun clearCommands(): Boolean {
            val was = ct
            ct = false
            return was
        }

        override fun beginTrainReset() {
            nextComValue = -1L
        }

        /** 回合复位流水（`VariableEvaluator.UpdateAfter*`）。 */
        val resets = ArrayList<String>()

        override fun afterShowUserComReset() {
            resets += "afterShowUserCom"
        }

        override fun afterInputComReset() {
            resets += "afterInputCom"
        }

        override fun afterSourceCheckReset() {
            resets += "afterSourceCheck"
        }

        override fun trainName(code: Int): String? = trainNames[code]
        override fun trainNameCount(): Int? = trainCount

        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    private class FakeHost : BeginHost {
        var openingInput: Long? = null
        var userComInput: Long? = null
        var ablupInput: Long? = null
        var shopInput: Long? = null
        var defaultTitleCount = 0
        var newGameCount = 0
        var autoSaveEnabled = false
        var temporaryLine = false
        var resetStyleCount = 0

        /** `awaitUserComInput` 被调到的次数：一条指令只许吃一次提交（`Process.cs:426-430`）。 */
        var userComInputCalls = 0

        override fun printDefaultTitle() {
            defaultTitleCount++
        }

        override fun resetStyle() {
            resetStyleCount++
        }

        override fun awaitOpeningInput(): Long? = openingInput

        override fun onNewGameSelected() {
            newGameCount++
        }

        override fun autoSave(): Boolean = autoSaveEnabled
        override fun awaitUserComInput(): Long? {
            userComInputCalls++
            return userComInput
        }
        override fun awaitAblupInput(): Long? = ablupInput
        override fun awaitShopInput(): Long? = shopInput
        override fun lastLineIsTemporary(): Boolean = temporaryLine
    }

    // ------------------------------------------------------------------
    // 标题
    // ------------------------------------------------------------------

    @Test
    fun `TITLE 有 SYSTEM_TITLE 时调用一次并回到 Normal`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TITLE)
        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("SYSTEM_TITLE", step.calledFunction)
        assertEquals(SystemStateCode.Normal, flow.state)
        assertEquals(listOf("SYSTEM_TITLE"), calls.called)
        assertEquals("Process.cs:2136-2140", step.evidence)
    }

    /**
     * `beginTitle` 的样式复位（组长接线 M4-M，落点 `BeginFlow.stepTitle` → `BeginHost.resetStyle`）：
     * 原版方法体 `Process.cs:2105-2113` 的次序是 `:2111 skipPrint = false` →
     * `:2112 console.ResetStyle()` → `:2113 deleteAllPrevState()`，复位在 **SYSTEM_TITLE 派发（:2136）之前**，
     * 与 `Process.cs:2107-2110` 的 CTrain 早退**之后**。复位集四项口径见 `BeginHost.resetStyle` KDoc
     * （`EmueraConsole.cs:2838-2842`）。
     */
    @Test
    fun `TITLE 分支在派发 SYSTEM_TITLE 之前先复位样式`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val host = FakeHost()
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TITLE)
        flow.step()

        assertEquals(1, host.resetStyleCount, "Process.cs:2112 console.ResetStyle() 每进入 beginTitle 一次复位一次")
        assertEquals(listOf("SYSTEM_TITLE"), calls.called, "复位不打掉派发")
    }

    /** CTrain 早退（`Process.cs:2107-2110`）在复位（`:2112`）**之前** ⇒ 那条分支不复位样式。 */
    @Test
    fun `TITLE 的 CTrain 早退不复位样式`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE"; ct = true }
        val host = FakeHost()
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TITLE)
        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals("Process.cs:2107-2110", step.evidence)
        assertEquals(0, host.resetStyleCount, "早退路径不触发 :2112 的复位")
        assertEquals(emptyList(), calls.called)
    }

    @Test
    fun `TITLE 缺失时画默认标题并在无输入宿主上阻塞`() {
        val calls = FakeCalls()
        val host = FakeHost()
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TITLE)
        val step = flow.step()

        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertEquals(1, host.defaultTitleCount)
        assertEquals(SystemStateCode.Openning, flow.state)
        val blocker = assertNotNull(step.blocker)
        assertEquals(BlockerKind.HOST_MISSING, blocker.kind)
        assertEquals("SYSTEM_TITLE", blocker.function)
        assertEquals(-1, blocker.line)
        assertEquals(-1, blocker.pc)
        assertTrue(blocker.tuple().startsWith("function=SYSTEM_TITLE"))
    }

    @Test
    fun `Openning 的 systemResult 0 走新游戏并派发 EVENTFIRST`() {
        val calls = FakeCalls().apply { events += "EVENTFIRST" }
        val host = FakeHost().apply { openingInput = 0L }
        val flow = BeginFlow(calls, host)

        flow.select(BeginType.TITLE)
        assertEquals(StepOutcome.AWAIT_INPUT, flow.step().outcome)
        assertEquals(SystemStateCode.Openning, flow.state)
        assertEquals(0L, calls.systemResultValue)

        val newGame = flow.step()
        assertEquals(StepOutcome.CONTINUE, newGame.outcome)
        assertEquals(SystemStateCode.First_Begin, newGame.to)
        assertEquals(1, host.newGameCount)

        val first = flow.step()
        assertEquals(StepOutcome.CALLED, first.outcome)
        assertEquals("EVENTFIRST", first.calledFunction)
        assertEquals(SystemStateCode.Normal, flow.state)
    }

    @Test
    fun `Openning 的 systemResult 非法时重等输入`() {
        val calls = FakeCalls()
        val host = FakeHost().apply { openingInput = 2L }
        val flow = BeginFlow(calls, host)

        flow.select(BeginType.TITLE)
        assertEquals(StepOutcome.AWAIT_INPUT, flow.step().outcome)

        val retry = flow.step()
        assertEquals(StepOutcome.AWAIT_INPUT, retry.outcome)
        assertEquals(SystemStateCode.Openning, retry.to)
        assertEquals(BlockerKind.HOST_INPUT, assertNotNull(retry.blocker).kind)
    }

    @Test
    fun `FIRST 缺失且 force 时抛 CodeEE`() {
        val calls = FakeCalls()
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.FIRST)
        assertFailsWith<CodeEE> { flow.step() }
    }

    // ------------------------------------------------------------------
    // 训练
    // ------------------------------------------------------------------

    @Test
    fun `TRAIN 的 EVENTTRAIN 缺失返回 NOT_CALLED 且下一步走 SHOW_STATUS`() {
        val calls = FakeCalls().apply { functions += "SHOW_STATUS" }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.TRAIN)
        val begin = flow.step()
        assertEquals(StepOutcome.NOT_CALLED, begin.outcome)
        assertEquals(SystemStateCode.Train_CallEventTrain, flow.state)
        assertEquals(-1L, calls.nextComValue) // beginTrainReset 必须先于 NEXTCOM 判定

        val status = flow.step()
        assertEquals(StepOutcome.CALLED, status.outcome)
        assertEquals("SHOW_STATUS", status.calledFunction)
        assertEquals(SystemStateCode.Train_CallShowStatus, flow.state)
    }

    @Test
    fun `TrainName 未接宿主时 COM_ABLE 枚举报告阻塞点`() {
        val calls = FakeCalls().apply { functions += "SHOW_STATUS" }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TRAIN)
        flow.step() // beginTrain: EVENTTRAIN 缺失
        flow.step() // endCallEventTrain: NEXTCOM=-1 → SHOW_STATUS
        flow.step() // endCallShowStatus → Train_CallComAbleXX

        val step = flow.step()
        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertEquals(SystemStateCode.Train_CallComAbleXX, step.to)
        val blocker = assertNotNull(step.blocker)
        assertEquals(BlockerKind.HOST_MISSING, blocker.kind)
        assertEquals("SHOW_USERCOM", blocker.function)
        assertTrue(blocker.message.contains("TrainName"))
    }

    @Test
    fun `TrainName 表存在时逐个 COM_ABLE 并在遍历完后 SHOW_USERCOM`() {
        val calls = FakeCalls().apply {
            functions += "SHOW_STATUS"
            functions += "COM_ABLE1"
            functions += "SHOW_USERCOM"
            trainCount = 2
            trainNames[0] = "愛撫"
            trainNames[1] = "舐陰"
        }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TRAIN)
        flow.step()
        flow.step()
        flow.step() // → Train_CallComAbleXX

        // n=0（COM_ABLE0 缺失，走 ComAbleDefault 登记）→ n=1（存在，调用后返回）
        val first = flow.step()
        assertEquals(StepOutcome.CALLED, first.outcome)
        assertEquals("COM_ABLE1", first.calledFunction)
        assertEquals(SystemStateCode.Train_CallComAbleXX, flow.state)

        val done = flow.step()
        assertEquals(StepOutcome.CALLED, done.outcome)
        assertEquals("SHOW_USERCOM", done.calledFunction)
        assertEquals(SystemStateCode.Train_CallShowUserCom, flow.state)
    }

    @Test
    fun `NEXTCOM 非负时先派发 EVENTCOM`() {
        val calls = FakeCalls().apply {
            events += "EVENTCOM"
            nextComValue = 5L
        }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TRAIN)
        flow.step()
        calls.nextComValue = 5L // beginTrainReset 会清成 -1；模拟脚本随后写入 NEXTCOM

        val step = flow.step()
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("EVENTCOM", step.calledFunction)
        assertEquals(SystemStateCode.Train_CallEventCom, flow.state)
        assertEquals(5L, calls.selectComValue)
        assertEquals(0L, calls.nextComValue)
    }

    /**
     * `trainWaitInput`（`Process.cs:2341-2375`，M10-PLAY 落地）：按钮号命中 comAble 登记
     * ⇒ `SELECTCOM = 训练码` 并派发 EVENTCOM（`:2344-2349` + `:2361-2364`）；
     * 没命中 ⇒ `Train_CallEventComEnd` + USERCOM force（`:2366-2373`）。
     * 登记点两处：`:2270`（COM_ABLE 返回且 RESULT!=0）与 `:2297`（COM_ABLE 缺失且
     * ComAbleDefault!=0）——本用例全走 `:2297`（两个 COM_ABLE 都未定义）。
     * **按钮号从 0 起**：`endCallShowStatus` 置 `lastAddCom = -1`（`:2248`），
     * `:2292` 先自增再写 `comAble[lastAddCom]`（`:2297`）⇒ `comAble[0]=0`、`comAble[1]=1`
     * （界面上就是「前戯[  0]」「本戯[  1]」，`getTrainComString` `:2257-2261`）。
     */
    @Test
    fun `trainWaitInput 命中登记派发 EVENTCOM 未登记走 USERCOM`() {
        fun freshFlow(userComInput: Long): Triple<FakeCalls, FakeHost, BeginFlow> {
            val calls = FakeCalls().apply {
                functions += "SHOW_STATUS"
                functions += "SHOW_USERCOM"
                functions += "USERCOM"
                events += "EVENTCOM"
                trainCount = 2
                trainNames[0] = "前戯"
                trainNames[1] = "本戯"
            }
            val host = FakeHost().apply { this.userComInput = userComInput }
            val flow = BeginFlow(calls, host)
            flow.select(BeginType.TRAIN)
            flow.step() // beginTrain：EVENTTRAIN 缺失
            flow.step() // endCallEventTrain：NEXTCOM=-1 -> SHOW_STATUS
            flow.step() // endCallShowStatus：lastCalledComable=-1、lastAddCom=-1、comAble 清空
            flow.step() // COM_ABLE0/1 均缺失 -> 登记 comAble[0]=0、comAble[1]=1，SHOW_USERCOM 后到 Train_CallShowUserCom
            flow.step() // endCallShowUserCom：消费提交写 systemResult -> Train_WaitInput
            return Triple(calls, host, flow)
        }

        val (hitCalls, hitHost, hitFlow) = freshFlow(userComInput = 1L)
        val hit = hitFlow.step()
        assertEquals(StepOutcome.CALLED, hit.outcome, "按钮 1 登记为训练码 1 => EVENTCOM")
        assertEquals("EVENTCOM", hit.calledFunction)
        assertEquals(SystemStateCode.Train_CallEventCom, hitFlow.state)
        assertEquals(1L, hitCalls.selectComValue)
        assertEquals(
            1, hitHost.userComInputCalls,
            "整条链只向宿主取一次输入：trainWaitInput 读 systemResult，不再等第二次（Process.cs:426-430 / :2346-2348）",
        )

        // 登记号 0 也是有效按钮：num = 0 走 `num >= 0` 那支（:2362），不得当成无效值。
        val (zeroCalls, zeroHost, zeroFlow) = freshFlow(userComInput = 0L)
        assertEquals("EVENTCOM", zeroFlow.step().calledFunction)
        assertEquals(0L, zeroCalls.selectComValue)
        assertEquals(1, zeroHost.userComInputCalls)

        val (missCalls, missHost, missFlow) = freshFlow(userComInput = 99L)
        val miss = missFlow.step()
        assertEquals(StepOutcome.CALLED, miss.outcome, "按钮 99 未登记 => USERCOM（force）")
        assertEquals("USERCOM", miss.calledFunction)
        assertEquals(SystemStateCode.Train_CallEventComEnd, missFlow.state)
        assertEquals(99L, missCalls.systemResultValue)
        assertEquals(1, missHost.userComInputCalls)
        // Process.cs:2372 —— 原版在这条 else 支里先写**脚本变量** `vEvaluator.RESULT =
        // systemResult` 再调 USERCOM。USERCOM 的分派全靠它：语料 USERCOM.ERB:15 的
        // `LOCAL = RESULT`、:85 的 `SELECTCOM = RESULT`、:109 的
        // `TRYCALLFORM COM_ABLE{SELECTCOM}`。漏写 ⇒ SELECTCOM 取陈旧值、
        // COM_ABLE<n> 永不命中 ⇒ 点指令零输出静默退回（真机红 O 的根因）。
        assertEquals(99L, missCalls.resultValue, "RESULT 必须等于提交的指令号，USERCOM 靠它分派")
        assertEquals(99L, missCalls.systemResultValue, "systemResult 槽独立保留同一个值")
    }

    /**
     * 红 O 回归钉桩：真机「点移动[400] 零输出静默退回」的根因是 `trainWaitInput` 的
     * else 支漏写脚本变量 `RESULT`（`Process.cs:2372` 的 `vEvaluator.RESULT = systemResult`）。
     *
     * 语料 `USERCOM.ERB` 的三步全押在这个变量上：`:15 LOCAL = RESULT`、
     * `:85 SELECTCOM = RESULT`、`:109 TRYCALLFORM COM_ABLE{SELECTCOM}`。
     * 只要没写，`SELECTCOM` 就取上一次脚本残留值，`COM_ABLE400` 永不命中，
     * `:110 SIF RESULT == 0 / RETURN 0` 静默退出 —— 屏幕上一行不出。
     *
     * 这里用真实指令号 400（`CSV/Train.csv:190` 的 `400,移動`）走完整条
     * 未登记 → USERCOM 分支，钉死「交给脚本的 RESULT 就是提交值」。
     */
    @Test
    fun `未登记指令走 USERCOM 时 RESULT 必须是提交的指令号（红 O）`() {
        val calls = FakeCalls().apply {
            functions += "SHOW_STATUS"
            functions += "SHOW_USERCOM"
            functions += "USERCOM"
            trainCount = 401
            trainNames[400] = "移動"
        }
        val host = FakeHost().apply { userComInput = 400L }
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TRAIN)
        flow.step() // beginTrain：EVENTTRAIN 缺失
        flow.step() // endCallEventTrain：NEXTCOM=-1 -> SHOW_STATUS
        flow.step() // endCallShowStatus：comAble 清空
        flow.step() // 枚举 COM_ABLE<n>：仅 400 有名字；COM_ABLE400 缺失 ⇒ 按 ComAbleDefault 登记
        flow.step() // endCallShowUserCom：消费提交写 systemResult -> Train_WaitInput

        val step = flow.step() // trainWaitInput：按钮 400 未登记（comAble 只有 1 格）=> USERCOM
        assertEquals("USERCOM", step.calledFunction, "按钮 400 不在 comAble 里 => USERCOM 兜底")
        assertEquals(SystemStateCode.Train_CallEventComEnd, flow.state)
        assertEquals(
            400L, calls.resultValue,
            "Process.cs:2372 —— USERCOM 看到的 RESULT 必须是 400，否则 SELECTCOM 落陈旧值、COM_ABLE400 不命中",
        )
    }

    /** CTrain 那支（`Process.cs:2351-2359`）维持未接：报 BLOCKED 不伪造。 */
    @Test
    fun `CTrain 时 trainWaitInput 仍报告未接阻塞`() {
        val calls = FakeCalls().apply {
            functions += "SHOW_STATUS"
            functions += "SHOW_USERCOM"
            // COM_ABLE 枚举要有 TrainName 表可遍历，否则流程停在 endCallComAbleXX 的
            // HOST_MISSING 阻塞，走不到 Train_WaitInput（那步的阻塞消息里没有 CTrain）。
            trainCount = 1
            trainNames[0] = "前戯"
        }
        val host = FakeHost().apply { userComInput = 1L }
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TRAIN)
        flow.step()
        flow.step()
        flow.step()
        flow.step()
        // endCallShowUserCom 的 CTrain 说明：这里手动切 CTrain 位（模拟 CALLTRAIN 队列在场）
        calls.ct = true
        flow.step() // endCallShowUserCom（AWAIT_INPUT，消费 1）
        val step = flow.step() // Train_WaitInput 的 CTrain 支
        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertNotNull(step.blocker)
        assertTrue(step.blocker!!.message.contains("CTrain"))
    }

    // ------------------------------------------------------------------
    // 回合/输入后变量复位（P1：VariableEvaluator.UpdateAfter*）
    // ------------------------------------------------------------------

    /**
     * 三处回合复位按时机触发：`endCallShowUserCom`（`Process.cs:2327`）→
     * `UpdateAfterShowUsercom`；`callEventCom`（`:2386`）→ `UpdateAfterInputCom`；
     * `endCallSourceCheck`（`:2415`）→ `UpdateAfterSourceCheck`
     * （实现在 `VariableEvaluator.cs:1719-1778`）。
     */
    @Test
    fun `回合复位在 ShowUserCom callEventCom 与 SourceCheck 三处按时机触发`() {
        val calls = FakeCalls().apply {
            functions += "SHOW_STATUS"
            functions += "SHOW_USERCOM"
            functions += "COM0"
            functions += "SOURCE_CHECK"
            events += "EVENTCOM"
            trainCount = 1
            trainNames[0] = "前戯"
            resultValue = 1L // endCallComXX 走 SOURCE_CHECK 分支（Process.cs:2404-2410）
        }
        val host = FakeHost().apply { userComInput = 0L }
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TRAIN)
        flow.step() // beginTrain：EVENTTRAIN 缺失
        flow.step() // endCallEventTrain：NEXTCOM=-1 -> SHOW_STATUS
        flow.step() // endCallShowStatus -> Train_CallComAbleXX
        flow.step() // COM_ABLE0 缺失 -> 登记 comAble[0]=0 -> SHOW_USERCOM
        assertEquals(emptyList<String>(), calls.resets, "endCallShowUserCom 之前不得复位")

        flow.step() // endCallShowUserCom：复位 + 消费提交
        assertEquals(listOf("afterShowUserCom"), calls.resets)

        flow.step() // trainWaitInput -> EVENTCOM（callEventCom）
        assertEquals(listOf("afterShowUserCom", "afterInputCom"), calls.resets)

        flow.step() // endEventCom -> COM0
        flow.step() // endCallComXX：RESULT!=0 -> SOURCE_CHECK
        assertEquals(
            listOf("afterShowUserCom", "afterInputCom"), calls.resets,
            "SOURCE 只在 endCallSourceCheck 复位（Process.cs:2415），SOURCE_CHECK 之前不动",
        )

        flow.step() // endCallSourceCheck：复位 SOURCE + EVENTCOMEND
        assertEquals(
            listOf("afterShowUserCom", "afterInputCom", "afterSourceCheck"), calls.resets,
        )
    }

    /** `endCallEventTrain` 走 NEXTCOM 时同样经 `callEventCom`（`Process.cs:2233 → :2386`）复位 NOWEX。 */
    @Test
    fun `NEXTCOM 派发 EVENTCOM 之前同样复位 NOWEX`() {
        val calls = FakeCalls().apply { events += "EVENTCOM" }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TRAIN)
        flow.step() // beginTrain：EVENTTRAIN 缺失（beginTrainReset 把 NEXTCOM 清成 -1）
        calls.nextComValue = 5L // 模拟 EVENTTRAIN 里写了 NEXTCOM

        val step = flow.step()
        assertEquals("EVENTCOM", step.calledFunction)
        assertEquals(listOf("afterInputCom"), calls.resets)
    }

    // ------------------------------------------------------------------
    // 脚本 BEGIN 的重入
    // ------------------------------------------------------------------

    @Test
    fun `脚本 BEGIN 在下一次 step 开头切换状态`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TITLE)
        flow.step()
        assertEquals(SystemStateCode.Normal, flow.state)

        calls.pending = BeginType.SHOP
        val step = flow.step()
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals("(script BEGIN)", step.handler)
        assertEquals(SystemStateCode.Shop_Begin, flow.state)
        assertEquals(null, calls.pending)
    }

    /**
     * P0（BEGIN 延迟消费）：`SYSTEM_TITLE` 里 `BEGIN FIRST`（语料 TITLE.ERB:117）。
     *
     * 原版 `BEGIN` 在语句里就 `state.Return(0L)`（`FunctionIdentifier.cs:2930-2936`），
     * `Return` 在函数栈清空当刻判 `begintype != 0` 并当场 `Begin()`（`ProcessState.cs:352-362`）
     * ⇒ 脚本一返回状态就是 `First_Begin`，宿主 `while (state != Normal)`
     * （`GameHost.kt:518`）不会提前退出。此前移植侧把状态写回 Normal 再留下 pending，
     * 宿主看到「Normal + pending」直接退出，FIRST 永不执行（真机选「开始游戏」后卡住）。
     */
    @Test
    fun `SYSTEM_TITLE 里 BEGIN FIRST 在脚本返回时立即转态`() {
        val calls = FakeCalls().apply {
            functions += "SYSTEM_TITLE"
            events += "EVENTFIRST"
        }
        calls.afterCall = { name -> if (name == "SYSTEM_TITLE") calls.pending = BeginType.FIRST }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.TITLE)
        val step = flow.step()
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals("beginTitle", step.handler)
        assertEquals("SYSTEM_TITLE", step.calledFunction)
        assertEquals(SystemStateCode.First_Begin, flow.state)
        assertEquals(SystemStateCode.First_Begin, step.to)
        assertEquals(null, calls.pending, "pending 必须在脚本返回同一步被消费掉")

        // 下一次 step 直接进 beginFirst（不再有额外的「先消费 pending」空转步）。
        val first = flow.step()
        assertEquals(StepOutcome.CALLED, first.outcome)
        assertEquals("EVENTFIRST", first.calledFunction)
        assertEquals(SystemStateCode.Normal, flow.state)
    }

    /**
     * P0 同形：`EVENTFIRST` 里 `BEGIN SHOP`（语料 SYSTEM.ERB:30）。
     *
     * `beginFirst` 先写 Normal（`Process.cs:2203-2207`）再派发事件，所以 `Begin()` 时刻
     * `sysStateCode == Normal` ⇒ `calledWhenNormal = true`（`ProcessState.cs:227-236`），
     * 自动存档（`:2556`）按正常回合处理。
     */
    @Test
    fun `EVENTFIRST 里 BEGIN SHOP 在事件返回时立即转态并记正常回合`() {
        val calls = FakeCalls().apply { events += "EVENTFIRST" }
        calls.afterCall = { name -> if (name == "EVENTFIRST") calls.pending = BeginType.SHOP }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.FIRST)
        val step = flow.step()
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals("beginFirst", step.handler)
        assertEquals("EVENTFIRST", step.calledFunction)
        assertEquals(SystemStateCode.Shop_Begin, flow.state)
        assertEquals(null, calls.pending)
        assertTrue(flow.calledWhenNormal, "Begin() 时刻状态是 Normal（ProcessState.cs:227-236）")
    }

    /**
     * P0 同形：`EVENTTURNEND` 里 `BEGIN SHOP`（语料 EVENTTURNEND.ERB:300，逐回合必经）。
     *
     * 原版 `beginTurnend` 的 `state.SystemState = Normal` 排在 `callFunction` 之后
     * （`Process.cs:2535-2536`），而 `callFunction` 只推帧 ⇒ 脚本体执行时状态已是 Normal。
     * 移植侧必须把这次赋值提前到调用前，否则 `calledWhenNormal` 会误判为 false。
     */
    @Test
    fun `EVENTTURNEND 里 BEGIN SHOP 在事件返回时立即转态并记正常回合`() {
        val calls = FakeCalls().apply { events += "EVENTTURNEND" }
        calls.afterCall = { name -> if (name == "EVENTTURNEND") calls.pending = BeginType.SHOP }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.TURNEND)
        val step = flow.step()
        assertEquals(StepOutcome.REENTERED, step.outcome)
        assertEquals(SystemStateCode.Shop_Begin, flow.state)
        assertEquals(null, calls.pending)
        assertTrue(flow.calledWhenNormal, "脚本执行期间状态已回到 Normal（Process.cs:2535-2536）")
    }

    // ------------------------------------------------------------------
    // 商店 / 自动存档
    // ------------------------------------------------------------------

    @Test
    fun `SHOP 的 EVENTSHOP 缺失时 endCallEventShop 落到 SHOW_SHOP`() {
        val calls = FakeCalls().apply { functions += "SHOW_SHOP" }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.SHOP)
        assertEquals(StepOutcome.NOT_CALLED, flow.step().outcome)
        assertEquals(SystemStateCode.Shop_CallEventShop, flow.state)

        val show = flow.step()
        assertEquals(StepOutcome.CALLED, show.outcome)
        assertEquals("SHOW_SHOP", show.calledFunction)
        assertEquals(SystemStateCode.Shop_CallShowShop, flow.state)
    }

    @Test
    fun `EVENTSHOP 里执行 BEGIN TURNEND 时 endAutoSave 不切状态`() {
        val calls = FakeCalls().apply { events += "EVENTSHOP" }
        calls.afterCall = { name -> if (name == "EVENTSHOP") calls.pending = BeginType.TURNEND }
        val flow = BeginFlow(calls, FakeHost())

        flow.select(BeginType.SHOP)
        assertEquals(StepOutcome.CALLED, flow.step().outcome)
        assertEquals(SystemStateCode.Shop_CallEventShop, flow.state)

        val autoSave = flow.step()
        assertEquals(StepOutcome.CONTINUE, autoSave.outcome)
        assertEquals(SystemStateCode.AutoSave_Skipped, flow.state)

        val reenter = flow.step()
        assertEquals(StepOutcome.REENTERED, reenter.outcome)
        assertEquals(SystemStateCode.Turnend_Begin, flow.state)
    }

    // ------------------------------------------------------------------
    // 失败返回
    // ------------------------------------------------------------------

    @Test
    fun `宿主 begin SHOP 的 calledWhenNormal 恒为 false 而脚本 BEGIN SHOP 可为 true`() {
        // 宿主入口 Begin(type)：ProcessState.cs:276 先把 sysStateCode 置 Title_Begin，
        // 于是 :228 的 `== Normal` 恒 false（官方怪行为，ADR-005 照抄）。
        val hostFlow = BeginFlow(FakeCalls(), FakeHost())
        assertEquals(SystemStateCode.Normal, hostFlow.state)
        hostFlow.select(BeginType.SHOP)
        assertEquals(false, hostFlow.calledWhenNormal)

        // 脚本入口（无参 Begin()）：当前状态原样保留，所以从 Normal 进商店为 true。
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val scriptFlow = BeginFlow(calls, FakeHost())
        scriptFlow.select(BeginType.TITLE)
        scriptFlow.step()
        assertEquals(SystemStateCode.Normal, scriptFlow.state)
        calls.pending = BeginType.SHOP
        scriptFlow.step()
        assertEquals(true, scriptFlow.calledWhenNormal)
    }

    @Test
    fun `Normal 且无挂起 BEGIN 时返回 FAILED 而不是抛异常`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val flow = BeginFlow(calls, FakeHost())
        flow.select(BeginType.TITLE)
        flow.step()

        val step = flow.step()
        assertEquals(StepOutcome.FAILED, step.outcome)
        assertEquals("endNormal", step.handler)
        val blocker = assertNotNull(step.blocker)
        assertEquals(BlockerKind.OFFICIAL_FAILURE, blocker.kind)
        assertTrue(blocker.tuple().contains("line=-1 pc=-1"))
        assertTrue(blocker.message.contains("予期しないスクリプトの終了"))
    }

    /**
     * 终局粘滞：endNormal 的 FAILED 是一次性的错误终局（EmueraConsole.cs:1094 的
     * `state != ConsoleState.Error` 门控让下一次回车不再重跑 DoScript，:1280-1287
     * 只回显不喂程序），第一次之后重复 step 必须幂等返回同一终局——不复活、
     * 不消费挂起 BEGIN、不进任何输入等待（宿主「崩一次还能玩」口径的收口点）。
     */
    @Test
    fun `endNormal FAILED 是终局 重复 step 不复活不消费挂起 BEGIN`() {
        val calls = FakeCalls().apply { functions += "SYSTEM_TITLE" }
        val host = FakeHost()
        val flow = BeginFlow(calls, host)
        flow.select(BeginType.TITLE)
        flow.step() // SYSTEM_TITLE 正常返回，栈空

        val first = flow.step()
        assertEquals(StepOutcome.FAILED, first.outcome)
        assertEquals("endNormal", first.handler)

        // 终局之后再补一个挂起 BEGIN、再泵：不得被「复活」成重入。
        calls.pending = BeginType.SHOP
        val hostBefore = host.userComInputCalls
        val again = flow.step()
        assertEquals(StepOutcome.FAILED, again.outcome)
        assertEquals("endNormal", again.handler)
        assertEquals(first.blocker, again.blocker, "终局步必须幂等（同一 FAILED 读数）")
        assertEquals(BeginType.SHOP, calls.pending, "粘滞终局不得消费挂起 BEGIN")
        assertEquals(hostBefore, host.userComInputCalls, "终局后不得进入任何输入等待")
    }

    /**
     * `DOTRAIN <n>`（`Process.cs:1931-1954`）：doTrain 步必须把参数写进 SELECTCOM
     * （`:2380`），再派发 EVENTCOM。语料的 `USERCOM → DOTRAIN 999` 主链就靠这条
     * （真正命令号由 `@SOURCE_CHECK` 从 TFLAG:3 还原，SOURCE.ERB:26）。
     */
    @Test
    fun `DOTRAIN 置 doTrainSelectCom 并在 doTrain 步把 SELECTCOM 写成参数`() {
        val calls = FakeCalls().apply {
            events += "EVENTCOM"
            trainCount = 1000
        }
        val flow = BeginFlow(calls, FakeHost())
        flow.enterState(SystemStateCode.Train_CallShowUserCom)
        flow.doTrain(999)
        assertEquals(SystemStateCode.Train_DoTrain, flow.state)

        val step = flow.step()
        assertEquals("EVENTCOM", step.calledFunction)
        assertEquals(SystemStateCode.Train_CallEventCom, flow.state)
        assertEquals(999L, calls.selectComValue, "doTrain 先 SELECTCOM = doTrainSelectCom（Process.cs:2380）")
        assertEquals(listOf("afterShowUserCom", "afterInputCom"), calls.resets)
    }

    /** 状态门与两条参数边界文案（`Process.cs:1934-1953` / zhs `:730-732`）。 */
    @Test
    fun `DOTRAIN 状态门与参数边界文案`() {
        val calls = FakeCalls().apply { trainCount = 10 }
        val flow = BeginFlow(calls, FakeHost())

        val e1 = assertFailsWith<CodeEE> { flow.doTrain(0) }
        assertEquals("DOTRAIN命令不能在此位置执行", e1.message)

        flow.enterState(SystemStateCode.Train_CallShowUserCom)
        val e2 = assertFailsWith<CodeEE> { flow.doTrain(-1) }
        assertEquals("对DOTRAIN命令传入了小于0的值", e2.message)
        val e3 = assertFailsWith<CodeEE> { flow.doTrain(10) }
        assertEquals("对DOTRAIN命令传入了大于TRAINNAME数组元素数的值", e3.message)
        assertEquals(SystemStateCode.Train_CallShowUserCom, flow.state, "参数非法不得转态")
    }
}
