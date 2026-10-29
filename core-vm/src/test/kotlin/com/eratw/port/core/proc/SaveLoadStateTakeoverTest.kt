package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * M11-E saveflow：`SAVEGAME` / `LOADGAME` 交接后 `SaveGame_Begin` / `LoadGame_Begin` 的
 * **状态接管**（beginSaveGame / beginLoadGame，`Process.cs:2685-2697`，注册 `:2023` / `:2027`），
 * 以及「快照栈」（`prevStateList`）**不需要在 VM 侧显式实现**的核对登记。
 *
 * ## C# 依据（逐条，行号均为本地 reference 实测）
 *
 * - 语句半边（M11-D save-11 已落地，本测试只交叉验证交接序列）：
 *   `SAVELOADGAME_Instruction.DoInstruction`（`FunctionIdentifier.cs:2967-2980`）=
 *   `__CAN_SAVE__` 门（`:2969-2977`）+ `saveCurrentState(single: true)`（`:2978`）+
 *   `SaveLoadData(isSave)`（`:2979`）。`saveCurrentState`（`Process.cs:1959-1966`）两句：
 *   `prevStateList.Add(state)`（`:1963`）与 `state = state.Clone()`（`:1964`）——`single`
 *   形参**从未被读**（官方死参数，其余调用点 `:354` / `:362`、`DebugDialog.cs:185`、
 *   `EmueraConsole.cs:2171` 全走同一对语句）。`Clone()`（`ProcessState.cs:475-484`）只带
 *   `isClone` / `currentLine` / `sysStateCode` / `begintype`，**不复制 functionList**
 *   （字段空初始化 `ProcessState.cs:20` 一带）⇒ `ScriptEnd`（`ProcessState.cs:34`）立即为真。
 *   `SaveLoadData`（`ProcessState.cs:190-200`）：save 支 `sysStateCode = SaveGame_Begin`
 *   （`:194`），load 支 `LoadGame_Begin`（`:198`）。注意契约简报写「ProcessState.cs:190-195」，
 *   实测方法体到 `:200`，load 分支的赋值在 `:198`。
 * - handler 半边（本路新接管，`BeginFlow.stepSaveOrLoadGameBegin`）：
 *   `beginSaveGame`（`Process.cs:2685-2690`）与 `beginLoadGame`（`:2692-2697`）逐行三句
 *   同形 —— `PrintSingleLine(SaveQuestion / LoadQuestion)`（`:2687` / `:2694`，日原文
 *   「何番にセーブしますか？」「何番をロードしますか？」`Lang.cs:4334` / `:4338`，zhs 译文
 *   「保存至几号存档？」「读取几号存档？」`lang.emuera-zhs.xml:1024` / `:1025`）→
 *   同值状态写（`:2688` / `:2695`）→ `printSaveDataText()`（`:2689` / `:2696`）。
 *   `printSaveDataText`（`:2757-2815`）后半：`setWaitInput()`（`:2802`）+ 换态
 *   `:2803-2810`（SaveGame_Begin→SaveGame_WaitInput，LoadGame_Begin→LoadGame_WaitInput）；
 *   前半的槽位列表（`:2758-2801`，`CheckData` 读档 + 99 槽仅非 SaveGame 显示
 *   `:2790-2799` + `[100] 戻る` `:2801`）属宿主 ⇒ [BeginHost.printSaveDataSlots] 默认 no-op。
 * - 其后的存档 UI handler（saveGameWaitInput `:2817-2855`、saveGameWaitInputOverwrite
 *   `:2857-2878`、endCallSaveInfo `:2880-2889`、loadGameWaitInput `:2891-2957`，注册
 *   `:2024-2026` / `:2028-2030`）**未接管**：落到 `BeginFlow.step` 的「未接管」阻塞步
 *   （BLOCKED + NOT_IMPLEMENTED），本测试把它钉成序列的最后一环。
 *
 * ## 快照栈核对结论：**不需要**在 VM 侧显式实现（登记，禁止造轮子）
 *
 * 原版 `prevStateList`（声明 `Process.cs:90`、构造 `:181`）在 SAVEGAME/LOADGAME 链上的
 * 全部读写点：
 * - 唯一写（core 语句面）：`saveCurrentState`（`:1959-1966`，被 `FunctionIdentifier.cs:2978` 调）；
 *   其余写点属 RELOADERB（`:354` / `:362`）与调试控制台（`DebugDialog.cs:185`、
 *   `EmueraConsole.cs:2171`）—— 这些状态/入口移植侧都不存在或未接线。
 * - 读（= 恢复/作废）：`loadPrevState`（`:1968-1976`）在 `:2821`（存档界面「戻る」）、
 *   `:2888`（保存完成收口）、`:2901`（读档界面「戻る」）、`:2966`（endReloaderb）；
 *   `deletePrevState`（`:1978-1984`）在 `:2955`（真读档成功，弃帧）；`deleteAllPrevState`
 *   （`:1986-1993`）在 `beginTitle` 样式复位尾部 `:2113`。
 * ⇒ **VM 可达面内，栈内容没有任何读者**：四个 `loadPrevState` 读点全在未接管的存档 UI
 * handler / RELOADERB 里。`saveCurrentState` 在同帧的可观测效果只有一个 —— 换上的 Clone
 * 函数栈为空 ⇒ 本次 run 结束，该效果已由 M11-D save-11 用
 * `SaveLoadHandoff.functionListCleared` → `StmtOutcome.Unwind`（整栈丢弃）等价落地
 * （`ProcessRuntime.executeStatement` 的交接分支；`begintype` 原样保留的口径见
 * `ProcessState.cs:482`，M11-D 已钉测）。
 *
 * **已知偏差（M11-D 起就登记在案，非本路新增）**：原版 SAVEGAME 是「挂起 + 回继续」——
 * `loadPrevState` 接回旧帧后脚本从 SAVEGAME 行的下一条继续跑（`Process.cs:1011` 的
 * `ShiftNextLine()` 先于 `:1050` 的 `DoInstruction`，压栈快照的 currentLine 已过该行）；
 * 移植侧整栈丢弃 ⇒ 「戻る/保存完成」后**不回继续脚本**。等组长接线存档 UI handler 时，
 * 「回来」必须另设复现通道（宿主做完整个存档界面后重新 `begin(...)` 入口，或引入真正的
 * 数据化帧栈），而不是现在预先造一个无人消费的 `prevStateList` 克隆 —— 后者才是造轮子。
 *
 * ## 与基准差异
 *
 * - `SaveGame_Begin` / `LoadGame_Begin` 的下一步由「未接管 BLOCKED」变为接管步（AWAIT_INPUT）
 *   —— 这正是本波任务；M11-D 的 `SaveGameHandoffTest` 里钉旧行为的用例需组长同步更新
 *   （回执给出 patch）。
 * - 原版 handler 嵌套直调（beginSaveGame 内直调 printSaveDataText）拆成**一步**承接：
 *   状态序列与副作用顺序一致，与类头「一步一 handler」的既有拆分口径同源。
 */
class SaveLoadStateTakeoverTest {

    // ------------------------------------------------------------------
    // 假体（FakeCalls 同 BeginFlowTest 口径；FakeHost 只记存档界面两钩子）
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val called = ArrayList<String>()
        var systemResultValue: Long = 0L
        var pending: BeginType? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            called.add(name)
            return false
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = systemResultValue
        override fun setSystemResult(value: Long) {
            systemResultValue = value
        }

        override fun nextCom(): Long = -1L
        override fun setNextCom(value: Long) {}
        override fun selectCom(): Long = 0L
        override fun setSelectCom(value: Long) {}
        override fun isCTrain(): Boolean = false
        override fun clearCommands(): Boolean = false
        override fun beginTrainReset() {}
        override fun afterShowUserComReset() {}
        override fun afterInputComReset() {}
        override fun afterSourceCheckReset() {}
        override fun trainName(code: Int): String? = null
        override fun trainNameCount(): Int? = null
        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    /** 记录 [BeginHost.writeLine]（PrintSingleLine 通道）与 [BeginHost.printSaveDataSlots]。 */
    private class RecordingHost : BeginHost {
        val lines = ArrayList<String>()
        val slotScreenCalls = ArrayList<Boolean>()

        override fun writeLine(text: String) {
            lines += text
        }

        override fun printSaveDataSlots(isSave: Boolean) {
            slotScreenCalls += isSave
        }
    }

    // ------------------------------------------------------------------
    // proc 装置（同 SaveGameHandoffTest 的程序形状：main → mid → SAVEGAME/LOADGAME）
    // ------------------------------------------------------------------

    private fun stmt(code: FunctionCode) = InstructionStmt(code, VoidArgument(), 1, code.name)

    private fun meta(program: Program, asmName: String, erbName: String = asmName.uppercase()): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(asmName, ignoreCase = true) }
        assertFalse(idx < 0, "函数 $asmName 不在程序中")
        return FuncMeta(index = idx, key = erbName.uppercase(), name = erbName)
    }

    private fun saveProgram(code: FunctionCode): Program = Assembler.assemble(
        """
        .func main slots=2
          _CALL mid, 0, none
          _PRINT_LITERAL "after-mid", 1
          _HALT

        .func mid slots=2
          ${code.name} stmt#0
          _PRINT_LITERAL "after-save", 1
          _RET
        """.trimIndent(),
        statements = listOf(stmt(code)),
    )

    private fun runtime(program: Program, store: VariableStore, host: BeginHost): ProcessRuntime =
        ProcessRuntime(
            program, store,
            FunctionTable(listOf(meta(program, "main", "MAIN"), meta(program, "mid", "MID"))),
            VmConfig(),
            beginHost = host,
        )

    // ------------------------------------------------------------------
    // BeginFlow 直连：接管步逐句对拍
    // ------------------------------------------------------------------

    @Test
    fun `SaveGame_Begin 接管步是 beginSaveGame：问题行 + 槽位钩子 + 换 SaveGame_WaitInput 等输入`() {
        // Process.cs:2685-2690（注册 :2023）+ printSaveDataText 后半 :2802-2805。
        val calls = FakeCalls()
        val host = RecordingHost()
        val flow = BeginFlow(calls, host)
        flow.enterState(SystemStateCode.SaveGame_Begin)

        val step = flow.step()
        assertEquals("beginSaveGame", step.handler, "Process.cs:2023 注册的 handler 名")
        assertEquals(SystemStateCode.SaveGame_Begin, step.from)
        assertEquals(SystemStateCode.SaveGame_WaitInput, step.to, "Process.cs:2803-2805 换态")
        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome, "setWaitInput（Process.cs:2802）")
        assertEquals(BlockerKind.HOST_INPUT, step.blocker!!.kind)
        assertNull(step.calledFunction)
        assertEquals(emptyList<String>(), calls.called, "beginSaveGame/beginLoadGame 不调任何脚本（:2685-2690 无 callFunction）")
        assertEquals(listOf("保存至几号存档？"), host.lines, ":2687 PrintSingleLine(SaveQuestion)；zhs 译文 xml:1024")
        assertEquals(listOf(true), host.slotScreenCalls, ":2689 printSaveDataText 的槽位列表半边交宿主（:2758-2801）")
        assertEquals("Process.cs:2685-2690 / :2757-2815", step.evidence)
    }

    @Test
    fun `LoadGame_Begin 接管步是 beginLoadGame：问题行 + 槽位钩子 + 换 LoadGame_WaitInput 等输入`() {
        // Process.cs:2692-2697（注册 :2027）+ printSaveDataText 后半 :2807-2809。
        val calls = FakeCalls()
        val host = RecordingHost()
        val flow = BeginFlow(calls, host)
        flow.enterState(SystemStateCode.LoadGame_Begin)

        val step = flow.step()
        assertEquals("beginLoadGame", step.handler, "Process.cs:2027 注册的 handler 名")
        assertEquals(SystemStateCode.LoadGame_Begin, step.from)
        assertEquals(SystemStateCode.LoadGame_WaitInput, step.to, "Process.cs:2807-2809 换态")
        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome, "setWaitInput（Process.cs:2802）")
        assertEquals(emptyList<String>(), calls.called)
        assertEquals(listOf("读取几号存档？"), host.lines, ":2694 PrintSingleLine(LoadQuestion)；zhs 译文 xml:1025")
        assertEquals(listOf(false), host.slotScreenCalls)
        assertEquals("Process.cs:2692-2697 / :2757-2815", step.evidence)
    }

    @Test
    fun `无宿主钩子（BeginHost 默认 no-op）时接管步不阻塞，直接交还控制权`() {
        // BeginHost.NONE（默认实现）：writeLine / printSaveDataSlots 都是 no-op（:2757-2815 的
        // 宿主半边没有能力也不伪造）；状态机仍按 :2802-2810 走完换态。step() **返回即未阻塞**
        // —— 本移植的等待是宿主驱动式的，AWAIT_INPUT 不是挂起点。
        val flow = BeginFlow(FakeCalls())
        flow.enterState(SystemStateCode.SaveGame_Begin)

        val takeover = flow.step()
        assertEquals(StepOutcome.AWAIT_INPUT, takeover.outcome)
        assertEquals(SystemStateCode.SaveGame_WaitInput, flow.state, "Process.cs:2803-2805")

        // 再泵一步：存档 UI handler（saveGameWaitInput :2817-2855）未接管 ⇒ 可诊断的阻塞步，
        // 状态原位、不崩溃、不反复吞输入。
        val blocked = flow.step()
        assertEquals(SystemStateCode.SaveGame_WaitInput, blocked.from)
        assertEquals(StepOutcome.BLOCKED, blocked.outcome)
        assertEquals(BlockerKind.NOT_IMPLEMENTED, blocked.blocker!!.kind)
        assertEquals(SystemStateCode.SaveGame_WaitInput, blocked.to)
    }

    // ------------------------------------------------------------------
    // 端到端状态流转序列（M11-D 交接步 → M11-E 接管步 → 未接管的存档 UI）
    // ------------------------------------------------------------------

    @Test
    fun `SAVEGAME 之后状态流转序列：Normal→Begin→WaitInput→BLOCKED 全程不崩不挂`() {
        val store = VariableStore()
        val host = RecordingHost()
        val rt = runtime(saveProgram(FunctionCode.SAVEGAME), store, host)
        rt.callFunction("MAIN")
        assertEquals("", rt.output.toString(), "SAVEGAME 之后的语句一条都不执行（Process.cs:1964 换 clone）")

        // 第 1 步：M11-D save-11 的交接步 —— 只置状态、跳过本步 handler（Process.cs:445-452）。
        val adopt = rt.resumeSystem()
        assertEquals(SystemStateCode.Normal, adopt.from)
        assertEquals(SystemStateCode.SaveGame_Begin, adopt.to, "ProcessState.cs:194 SaveLoadData(true)")
        assertEquals("beginSaveGame", adopt.handler, "Process.cs:2023")
        assertEquals(StepOutcome.CONTINUE, adopt.outcome)
        assertNull(SaveLoadHandoff.of(store)!!.systemState, "状态位一次性消费")

        // 第 2 步：M11-E 接管步（beginSaveGame，Process.cs:2685-2690 → :2803-2805）。
        val takeover = rt.resumeSystem()
        assertEquals(SystemStateCode.SaveGame_Begin, takeover.from)
        assertEquals("beginSaveGame", takeover.handler)
        assertEquals(SystemStateCode.SaveGame_WaitInput, takeover.to)
        assertEquals(StepOutcome.AWAIT_INPUT, takeover.outcome)
        assertEquals(listOf("保存至几号存档？"), host.lines, ":2687 的问题行经生产宿主 writeLine 上屏")
        assertEquals(listOf(true), host.slotScreenCalls, ":2689 printSaveDataText 交宿主钩子一次")

        // 第 3 步：存档 UI handler（:2817-2855）未接管 ⇒ 阻塞步（不是崩溃、不是挂起）。
        val blocked = rt.resumeSystem()
        assertEquals(SystemStateCode.SaveGame_WaitInput, blocked.from)
        assertEquals(StepOutcome.BLOCKED, blocked.outcome)
        assertEquals(BlockerKind.NOT_IMPLEMENTED, blocked.blocker!!.kind)

        // 序列快照（四元组 = from/handler/to/outcome 的流转主线）。
        assertEquals(
            listOf("Normal→SaveGame_Begin", "SaveGame_Begin→SaveGame_WaitInput", "SaveGame_WaitInput→SaveGame_WaitInput"),
            listOf(
                "${adopt.from.name}→${adopt.to.name}",
                "${takeover.from.name}→${takeover.to.name}",
                "${blocked.from.name}→${blocked.to.name}",
            ),
        )
    }

    @Test
    fun `LOADGAME 之后状态流转序列镜像：Normal→LoadGame_Begin→LoadGame_WaitInput→BLOCKED`() {
        val store = VariableStore()
        val host = RecordingHost()
        val rt = runtime(saveProgram(FunctionCode.LOADGAME), store, host)
        rt.callFunction("MAIN")

        val adopt = rt.resumeSystem()
        assertEquals(SystemStateCode.Normal, adopt.from)
        assertEquals(SystemStateCode.LoadGame_Begin, adopt.to, "ProcessState.cs:198 SaveLoadData(false)")
        assertEquals("beginLoadGame", adopt.handler, "Process.cs:2027")
        assertEquals(StepOutcome.CONTINUE, adopt.outcome)

        val takeover = rt.resumeSystem()
        assertEquals(SystemStateCode.LoadGame_Begin, takeover.from)
        assertEquals("beginLoadGame", takeover.handler)
        assertEquals(SystemStateCode.LoadGame_WaitInput, takeover.to, "Process.cs:2807-2809")
        assertEquals(StepOutcome.AWAIT_INPUT, takeover.outcome)
        assertEquals(listOf("读取几号存档？"), host.lines, ":2694 的问题行")
        assertEquals(listOf(false), host.slotScreenCalls)

        val blocked = rt.resumeSystem()
        assertEquals(SystemStateCode.LoadGame_WaitInput, blocked.from)
        assertEquals(StepOutcome.BLOCKED, blocked.outcome)
        assertEquals(BlockerKind.NOT_IMPLEMENTED, blocked.blocker!!.kind)
    }

    @Test
    fun `快照栈登记：交接消费后 VM 面内不留任何可恢复帧， prevStateList 不复刻`() {
        // 核对结论（类 KDoc「快照栈核对结论」）：saveCurrentState（Process.cs:1959-1966）在
        // VM 可达面内只剩「Clone 不复制 functionList ⇒ 本次 run 结束」一个可观测效果
        // （ProcessState.cs:475-484 + :34），已由 functionListCleared → Unwind 等价；
        // 栈内容唯一读者 loadPrevState（Process.cs:1968-1976，调用点 :2821/:2888/:2901/:2966）
        // 全在未接线的存档 UI handler / RELOADERB 里 ⇒ **不需要**显式快照栈。
        // 本用例把「消费后零残留」钉死：残留即意味着存在第二消费方或轮子。
        val store = VariableStore()
        val rt = runtime(saveProgram(FunctionCode.SAVEGAME), store, RecordingHost())
        rt.callFunction("MAIN")
        rt.resumeSystem() // 交接步消费 systemState
        rt.resumeSystem() // 接管步（beginSaveGame）

        val handoff = SaveLoadHandoff.of(store)!!
        assertNull(handoff.systemState, "systemState 已被交接步一次性取走（Process.cs:1861 口径）")
        assertFalse(handoff.functionListCleared, "清栈信号已被 executeStatement 一次性取走（Process.cs:1964 口径）")
        assertEquals(0, store.callDepth(), "整栈丢弃后变量层作用域归零，无挂起帧可供恢复")
    }
}
