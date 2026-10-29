package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SAVEGAME` / `LOADGAME` 的 proc 侧语义（M11-D SAVE-11）：整栈丢弃 + 状态交接消费。
 *
 * C# 依据（逐条）：
 * - `FunctionIdentifier.cs:2978` `GlobalStatic.Process.saveCurrentState(single: true)`；
 *   `Process.cs:1959-1966` 的方法体只有两句：`prevStateList.Add(state)` 与
 *   `state = state.Clone()`（`single` 形参**从未被读**，官方死参数）。
 * - `ProcessState.cs:475-484` 的 `Clone()` 只复制 `isClone` / `currentLine` / `sysStateCode` /
 *   `begintype`，**不复制 functionList**（字段在 `:20` 初始化）⇒ 新 state 的
 *   `ScriptEnd`（`:34` `functionList.Count == currentMin`）立即为真。
 * - 因此 `SAVEGAME` / `LOADGAME` 之后的语句一条都不执行，脚本本次运行结束；
 *   移植侧等价出口是 [StmtOutcome.Unwind]（整栈丢弃，不是逐帧弹）。
 * - `ProcessState.cs:190-200` `SaveLoadData(isSave)`：`sysStateCode = SaveGame_Begin(256)` /
 *   `LoadGame_Begin(272)`，下一步 `runSystemProc()` 进 `beginSaveGame` / `beginLoadGame`
 *   （`Process.cs:2685-2697`，注册 `:2023` / `:2027`）。
 * - 对照项：`ClearFunctionList()`（`ProcessState.cs:202-217`）额外把 `begintype` 归 NULL
 *   （`:216`），而 `Clone()` 是原样复制 `begintype`（`:482`）⇒ 只有 `LOADDATA` 清挂起 BEGIN。
 *
 * 未落地（见交付报告「需组长接线」）：`prevStateList` 快照栈与 `loadPrevState()`
 * （`Process.cs:1968-1976`）—— 存档/读档界面选「戻る」时靠它把脚本状态接回。
 * 当前这两条语句之后脚本**不可恢复**，且 `SaveGame_Begin` / `LoadGame_Begin` 的 handler
 * 尚未接管 ⇒ 下一步是「未接管」阻塞步（不是崩溃）。
 */
class SaveGameHandoffTest {

    private class Env : StmtEnv {
        override val functionKey: String = "MAIN"
        override val pc: Int = -1
        override val line: Int = -1
        override val functionName: String? = "main"
    }

    /** 读档成功的宿主（只做 [SaveLoadHandoff] 记账，同 `FileSystemOps.loadData` 口径）。 */
    private class DataLoadedOps(private val store: VariableStore) : SystemOps {
        override fun loadData(no: Int) {
            SaveLoadHandoff.requestDataLoaded(store) // Process.cs:1860-1861
        }
    }

    private fun stmt(code: FunctionCode) = InstructionStmt(code, VoidArgument(), 1, code.name)

    private fun beginStmt(target: String) =
        InstructionStmt(FunctionCode.BEGIN, ExpressionArgument(StrLiteral(target)), 1, "BEGIN $target")

    private fun loadDataStmt(no: Long) =
        InstructionStmt(FunctionCode.LOADDATA, ExpressionArgument(IntLiteral(no)), 1, "LOADDATA $no")

    private fun meta(program: Program, asmName: String, erbName: String = asmName.uppercase()): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(asmName, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $asmName 不在程序中")
        return FuncMeta(index = idx, key = erbName.uppercase(), name = erbName)
    }

    /**
     * `main` → `mid` → `SAVEGAME`，两条语句之后各有一条 PRINTL：整栈丢弃时两条都不许上屏
     * （逐帧弹的错语义会让 `after-save` / `after-mid` 至少跑一条）。
     */
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

    private fun runtime(program: Program, store: VariableStore, ops: SystemOps? = null): ProcessRuntime =
        ProcessRuntime(
            program, store,
            FunctionTable(listOf(meta(program, "main", "MAIN"), meta(program, "mid", "MID"))),
            VmConfig(),
            systemOps = ops,
        )

    /** 只要语句分派、不要函数表的装置（`_HALT` 空程序）。 */
    private fun bareRuntime(store: VariableStore, ops: SystemOps? = null): ProcessRuntime =
        ProcessRuntime(Assembler.assemble("_HALT"), store, FunctionTable(emptyList()), VmConfig(), systemOps = ops)

    @Test
    fun `SAVEGAME 成功后整栈丢弃且留下 SaveGame_Begin 交接`() {
        val store = VariableStore()
        val rt = runtime(saveProgram(FunctionCode.SAVEGAME), store)

        assertEquals(0L, rt.callFunction("MAIN"), "Unwind 的返回值走 run 的 result 通道（= 0L）")
        assertEquals("", rt.output.toString(), "SAVEGAME 之后的语句一条都不许执行（Process.cs:1964 换 clone）")
        assertEquals(0, store.callDepth(), "整栈丢弃后变量层作用域必须归零（enter/exit 配对）")

        val handoff = assertNotNull(SaveLoadHandoff.of(store), "语句层必须留下交接槽")
        assertEquals(SystemStateCode.SaveGame_Begin, handoff.systemState, "ProcessState.cs:194")
        assertFalse(handoff.functionListCleared, "清栈信号已被 executeStatement 一次性取走")
    }

    @Test
    fun `LOADGAME 成功后整栈丢弃且留下 LoadGame_Begin 交接`() {
        val store = VariableStore()
        val rt = runtime(saveProgram(FunctionCode.LOADGAME), store)

        assertEquals(0L, rt.callFunction("MAIN"))
        assertEquals("", rt.output.toString())
        assertEquals(SystemStateCode.LoadGame_Begin, SaveLoadHandoff.of(store)!!.systemState)
    }

    @Test
    fun `SAVEGAME 交接步进入 beginSaveGame 并接管到 SaveGame_WaitInput`() {
        val store = VariableStore()
        val rt = runtime(saveProgram(FunctionCode.SAVEGAME), store)
        rt.callFunction("MAIN")

        val adopt = rt.resumeSystem()
        assertEquals(SystemStateCode.Normal, adopt.from, "进入交接步之前的状态")
        assertEquals("beginSaveGame", adopt.handler, "Process.cs:2023 注册的 handler")
        assertEquals(SystemStateCode.SaveGame_Begin, adopt.to)
        assertEquals(StepOutcome.CONTINUE, adopt.outcome, "只置状态、不调脚本（Process.cs:445-452）")
        assertNull(adopt.calledFunction)
        assertNull(SaveLoadHandoff.of(store)!!.systemState, "状态位一次性消费")

        // 第 2 步（M11-E saveflow 接管）：beginSaveGame 按 `Process.cs:2685-2690` 走完
        // setWaitInput + 换态（`:2802-2810`）⇒ AWAIT_INPUT 进 SaveGame_WaitInput。
        // 本用例原先把这一步断言成「未接管 BLOCKED」，那是存档界面未落地时的旧行为，已由
        // M11-E 接管取代（同一形状见 SaveLoadStateTakeoverTest）。
        val takeover = rt.resumeSystem()
        assertEquals(SystemStateCode.SaveGame_Begin, takeover.from)
        assertEquals("beginSaveGame", takeover.handler)
        assertEquals(SystemStateCode.SaveGame_WaitInput, takeover.to, "Process.cs:2803-2805")
        assertEquals(StepOutcome.AWAIT_INPUT, takeover.outcome, "setWaitInput（Process.cs:2802）")

        // 第 3 步：存档 UI handler（saveGameWaitInput `Process.cs:2817-2855`）仍未接管
        // ⇒ 可诊断的阻塞步，不是崩溃、也不是静默空转。
        val blocked = rt.resumeSystem()
        assertEquals(SystemStateCode.SaveGame_WaitInput, blocked.from)
        assertEquals(StepOutcome.BLOCKED, blocked.outcome)
        assertEquals(BlockerKind.NOT_IMPLEMENTED, blocked.blocker!!.kind)
    }

    @Test
    fun `LOADGAME 交接步进入 beginLoadGame`() {
        val store = VariableStore()
        val rt = runtime(saveProgram(FunctionCode.LOADGAME), store)
        rt.callFunction("MAIN")

        val adopt = rt.resumeSystem()
        assertEquals("beginLoadGame", adopt.handler, "Process.cs:2027 注册的 handler")
        assertEquals(SystemStateCode.LoadGame_Begin, adopt.to)
        assertEquals(StepOutcome.CONTINUE, adopt.outcome)
    }

    @Test
    fun `SAVEGAME 保留挂起 BEGIN 而 LOADDATA 清空`() {
        // ProcessState.cs:482 —— Clone 原样复制 begintype；ProcessState.cs:216 —— 只有
        // ClearFunctionList 把 begintype 归 NULL。
        val store = VariableStore()
        val rt = bareRuntime(store, DataLoadedOps(store))
        val env = Env()

        rt.executeStatement(beginStmt("TRAIN"), env)
        assertEquals(BeginType.TRAIN, rt.pendingBegin)

        assertEquals(StmtOutcome.Unwind(0L), rt.executeStatement(stmt(FunctionCode.SAVEGAME), env))
        assertEquals(BeginType.TRAIN, rt.pendingBegin, "SAVEGAME 不清挂起 BEGIN（Clone 复制 begintype）")

        assertEquals(StmtOutcome.Unwind(0L), rt.executeStatement(loadDataStmt(1), env))
        assertNull(rt.pendingBegin, "LOADDATA 的 ClearFunctionList 清挂起 BEGIN")
    }
}
