package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `LOADDATA` 状态机接线（M6-SAVE-PROC）：写端在 [SaveLoadHandoff]，三个消费点各一组断言。
 *
 * C# 依据：
 * - `Process.cs:1860-1862` —— 读档成功后 `state.ClearFunctionList()`（整栈丢弃）+
 *   `state.SystemState = LoadData_DataLoaded` + `return false`（脚本不再往下跑）；
 * - `ProcessState.cs:202-217` —— `ClearFunctionList()` 干两件事：函数栈整体作废、`begintype`
 *   归 NULL（**不是**逐帧 `Return`，所以调用方也不能接着执行）；
 * - `Process.cs:445-452` —— 外层 `while (state.ScriptEnd) runSystemProc()`：函数栈空就以
 *   现成的 `SystemState` 进 handler，不再走 `Begin(type)`；
 * - `Process.cs:2660-2683` + `:2033-2035` —— `beginDataLoaded` → `endSystemLoad` →
 *   `endEventLoad` 三态与它们各自的状态迁移。
 */
class LoadDataHandoffTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 读档成功的宿主：只做一件事 —— 按 [FileSystemOps] 的口径写 [SaveLoadHandoff]。 */
    private class HandoffOps(
        private val store: VariableStore,
        private val failMessage: String? = null,
    ) : SystemOps {
        val loaded = ArrayList<Int>()

        override fun loadData(no: Int) {
            if (failMessage != null) throw CodeEE(failMessage)
            loaded.add(no)
            // Process.cs:1860-1861（顺序：清栈信号 → 状态位）
            SaveLoadHandoff.requestDataLoaded(store)
        }
    }

    /** 记临时图片卸载次数的 BEGIN 宿主（`Process.cs:2671-2672`）。 */
    private class CountingHost : BeginHost {
        var constUnloads = 0
        var graphicsUnloads = 0

        override fun unloadTempLoadedConstImageNames() {
            constUnloads++
        }

        override fun unloadTempLoadedGraphicsImageNames() {
            graphicsUnloads++
        }
    }

    private fun loadDataStmt(no: Long): Statement =
        InstructionStmt(FunctionCode.LOADDATA, ExpressionArgument(IntLiteral(no)), 1, "LOADDATA $no")

    /**
     * `main` → `mid` → `LOADDATA`，两帧的 LOADDATA **之后**各有一条 PRINTL：
     * 整栈丢弃时两条都不许上屏（逐帧弹的错语义会让 `after-load` / `after-mid` 至少跑一条）。
     */
    private fun loadProgram(): Program = Assembler.assemble(
        """
        .func main slots=2
          _CALL mid, 0, none
          _PRINT_LITERAL "after-mid", 1
          _HALT

        .func mid slots=2
          LOADDATA stmt#0
          _PRINT_LITERAL "after-load", 1
          _RET

        .func system_loadend slots=1
          _RET

        .func eventload slots=1
          _RET

        .func show_shop slots=1
          _RET
        """.trimIndent(),
        statements = listOf(loadDataStmt(1)),
    )

    /** 按 asm 名找下标，键/名用 ERB 口径（大写）。 */
    private fun meta(
        program: Program,
        asmName: String,
        erbName: String = asmName.uppercase(),
        isEvent: Boolean = false,
    ): FuncMeta {
        val idx = program.functions.indexOfFirst { it.name.equals(asmName, ignoreCase = true) }
        assertTrue(idx >= 0, "函数 $asmName 不在程序中")
        return FuncMeta(index = idx, key = erbName.uppercase(), name = erbName, isEvent = isEvent)
    }

    private fun loadMetas(program: Program): List<FuncMeta> = listOf(
        meta(program, "main", "MAIN"),
        meta(program, "mid", "MID"),
        meta(program, "system_loadend", "SYSTEM_LOADEND"),
        meta(program, "eventload", "EVENTLOAD", isEvent = true),
        meta(program, "show_shop", "SHOW_SHOP"),
    )

    private fun runtime(
        program: Program,
        store: VariableStore,
        ops: SystemOps?,
        host: BeginHost = BeginHost.NONE,
    ): ProcessRuntime = ProcessRuntime(
        program, store, FunctionTable(loadMetas(program)), VmConfig(),
        systemOps = ops, beginHost = host,
    )

    // ------------------------------------------------------------------
    // 1. 整栈丢弃（Process.cs:1860 / ProcessState.cs:202-217）
    // ------------------------------------------------------------------

    /**
     * `LOADDATA` 成功 ⇒ [StmtOutcome.Unwind]：本帧与调用方一起作废，返回槽不写、
     * 两条 PRINTL 都不执行；作用域仍成对退出（`callDepth() == 0`）。
     */
    @Test
    fun `LOADDATA 成功后整栈丢弃而不是逐帧弹`() {
        val store = VariableStore()
        val ops = HandoffOps(store)
        val program = loadProgram()
        val rt = runtime(program, store, ops)

        assertEquals(0L, rt.callFunction("MAIN"), "Unwind 的返回值走 run 的 result 通道（= 0L）")
        assertEquals(listOf(1), ops.loaded)
        assertEquals("", rt.output.toString(), "两帧 LOADDATA 之后的指令都不许再执行")
        assertEquals(0, store.callDepth(), "整栈丢弃后变量层作用域必须归零（enter/exit 配对）")

        val handoff = SaveLoadHandoff.of(store)
        assertTrue(handoff != null, "读档成功必须留下交接槽")
        assertEquals(SystemStateCode.LoadData_DataLoaded, handoff!!.systemState)
        assertFalse(handoff.functionListCleared, "清栈信号已被 executeStatement 一次性取走")
    }

    /**
     * VM 层单独钉死 [StmtOutcome.Unwind] 的语义：即使处于 `depth == 1`，也**不写返回槽**
     * （`_CALL` 的 R0 保持 0）且整个 run 立即结束 —— 与 [StmtOutcome.Stop] 的单帧弹出相反。
     */
    @Test
    fun `StmtOutcome Unwind 结束整次 run 且不写返回槽`() {
        val seen = ArrayList<String>()
        var entered = 0
        var exited = 0
        val printed = StringBuilder()
        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            override fun print(text: String) {
                printed.append(text)
            }

            override fun onFunctionEnter(funcIndex: Int) {
                entered++
            }

            override fun onFunctionExit(funcIndex: Int) {
                exited++
            }

            override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
                seen.add(env.functionKey)
                return StmtOutcome.Unwind(42L)
            }
        }
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL inner, 0, R0
              _PRINT_LITERAL "after-inner", 1
              _HALT

            .func inner slots=1
              LOADDATA stmt#0
              _PRINT_LITERAL "after-stmt", 1
              _RET
            """.trimIndent(),
            statements = listOf(loadDataStmt(0)),
        )
        val vm = VirtualMachine(program, host)

        assertEquals(42L, vm.run(0), "Unwind 的值经 run 返回（原版 runScriptProc 的返回值无从读起）")
        assertEquals(listOf("INNER"), seen, "只执行到 Unwind 那条语句")
        assertEquals("", printed.toString(), "调用方与被告帧的后续指令都不执行")
        assertEquals(2L, vm.executedSteps, "_CALL 一步 + marker 一步")
        assertEquals(2, entered, "入口帧 + 被调帧各 enter 一次")
        assertEquals(entered, exited, "unwindScopes 必须把两帧的作用域都退掉")
    }

    // ------------------------------------------------------------------
    // 2. 状态链（Process.cs:2660-2683）
    // ------------------------------------------------------------------

    /**
     * 读档后宿主逐步驱动：交接步 → `beginDataLoaded` → `endSystemLoad` → `endEventLoad`。
     *
     * 交接步**不跑 handler**（`Process.cs:445-452`：函数栈空就直接进 handler；移植侧把
     * 「置状态」与「跑 handler」拆成两步），此后三步各调一个脚本函数。
     * 终点是 `endAutoSave()` 的 `Shop_CallShowShop`（`:2599-2600`），即脚本回到正常系统链，
     * 不是原版本的 `Normal`（`Normal` 只由 FIRST/TURNEND 等入口写）。
     */
    @Test
    fun `读档后状态链 DataLoaded 到 endEventLoad 逐节推进`() {
        val store = VariableStore()
        val ops = HandoffOps(store)
        val host = CountingHost()
        val rt = runtime(loadProgram(), store, ops, host)

        rt.callFunction("MAIN")

        val adopt = rt.resumeSystem()
        assertEquals(SystemStateCode.Normal, adopt.from, "进入交接步之前的状态")
        assertEquals("(loaddata handoff)", adopt.handler)
        assertEquals(SystemStateCode.LoadData_DataLoaded, adopt.to)
        assertEquals(StepOutcome.CONTINUE, adopt.outcome, "只置状态、不调脚本（Process.cs:445-452）")
        assertNull(adopt.calledFunction)
        assertNull(SaveLoadHandoff.of(store)!!.systemState, "状态位一次性消费")

        val dataLoaded = rt.resumeSystem()
        assertEquals("beginDataLoaded", dataLoaded.handler)
        assertEquals(SystemStateCode.LoadData_DataLoaded, dataLoaded.from)
        assertEquals(SystemStateCode.LoadData_CallSystemLoad, dataLoaded.to, "Process.cs:2662")
        assertEquals("SYSTEM_LOADEND", dataLoaded.calledFunction)
        assertEquals(StepOutcome.CALLED, dataLoaded.outcome)

        val systemLoad = rt.resumeSystem()
        assertEquals("endSystemLoad", systemLoad.handler)
        assertEquals(SystemStateCode.LoadData_CallSystemLoad, systemLoad.from)
        assertEquals(SystemStateCode.LoadData_CallEventLoad, systemLoad.to, "Process.cs:2673")
        assertEquals("EVENTLOAD", systemLoad.calledFunction)
        assertEquals(StepOutcome.CALLED, systemLoad.outcome)
        assertEquals(1, host.constUnloads, "AppContents.UnloadTempLoadedConstImageNames（:2671）")
        assertEquals(1, host.graphicsUnloads, "AppContents.UnloadTempLoadedGraphicsImageNames（:2672）")

        val eventLoad = rt.resumeSystem()
        assertEquals("endEventLoad", eventLoad.handler)
        assertEquals(SystemStateCode.LoadData_CallEventLoad, eventLoad.from)
        assertEquals(SystemStateCode.Shop_CallShowShop, eventLoad.to, "endAutoSave（Process.cs:2599）")
        assertEquals("SHOW_SHOP", eventLoad.calledFunction, "Process.cs:2600")
        assertEquals(StepOutcome.CALLED, eventLoad.outcome)

        // 交接位已清：宿主再次选择入口时不被劫持。
        assertEquals(SystemStateCode.Shop_CallShowShop, rt.systemState, "读档链终点")
        // LOADDATA 的成功路径即使碰巧以 ExecResult.Quit 表达，也**不得**置退出粘滞位：
        // 原版语义是清函数栈继续跑（ExecuteResult.QUIT_FOR_LOADDATA 家族，不是真退出）。
        assertFalse(rt.quitRequested, "读档交接不是退出（宿主泵不得因此停转）")
        val reenter = rt.beginStep(BeginType.TITLE)
        assertEquals("beginTitle", reenter.handler, "宿主选择照常走 TITLE 入口（未被交接步顶掉）")
        assertEquals(SystemStateCode.Openning, reenter.to, "SYSTEM_TITLE 缺失 → 缺省标题菜单等输入")
    }

    /**
     * `SYSTEM_LOADEND` / `EVENTLOAD` 缺失（`force=false`）：原版当场调下一段的 handler
     * （`Process.cs:2663-2666` / `:2674-2677`）。拆步实现里等价于「状态已指向下一节」，
     * 下一步直接进 `endSystemLoad` / `endEventLoad`，序列不停顿。
     */
    @Test
    fun `目标缺失时状态仍推进到下一节`() {
        // 只留 main / mid，SYSTEM_LOADEND、EVENTLOAD、SHOW_SHOP 全缺失。
        val program = Assembler.assemble(
            """
            .func main slots=1
              LOADDATA stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(loadDataStmt(0)),
        )
        val store = VariableStore()
        val rt = ProcessRuntime(
            program, store, FunctionTable(listOf(meta(program, "main", "MAIN"))), VmConfig(),
            systemOps = HandoffOps(store), beginHost = CountingHost(),
        )
        rt.callFunction("MAIN")
        rt.resumeSystem() // 交接步

        val dataLoaded = rt.resumeSystem()
        assertEquals(StepOutcome.NOT_CALLED, dataLoaded.outcome)
        assertEquals(SystemStateCode.LoadData_CallSystemLoad, dataLoaded.to)

        val systemLoad = rt.resumeSystem()
        assertEquals("endSystemLoad", systemLoad.handler)
        assertEquals(StepOutcome.NOT_CALLED, systemLoad.outcome)
        assertEquals(SystemStateCode.LoadData_CallEventLoad, systemLoad.to)
    }

    // ------------------------------------------------------------------
    // 3. 失败路径（Process.cs:1844-1859 抛错时什么都不交接）
    // ------------------------------------------------------------------

    /**
     * 读档失败（`CheckData` / `LoadFrom` 抛 `CodeEE`）：**不写**交接槽，控制流仍按原路
     * 结束当前帧；下一次 `resumeSystem()` 走的是原状态（`Normal` 的 `endNormal`），
     * 不会跳进 `LoadData_DataLoaded`。
     */
    @Test
    fun `读档失败不置状态且继续原流程`() {
        val store = VariableStore()
        val ops = HandoffOps(store, failMessage = "存档数据已损坏")
        val rt = runtime(loadProgram(), store, ops)

        val e = assertFailsWith<CodeEE> { rt.callFunction("MAIN") }
        assertEquals("存档数据已损坏", e.message)
        assertNull(SaveLoadHandoff.of(store), "失败路径不建交接槽")
        assertEquals(SystemStateCode.Normal, rt.systemState, "系统状态保持原值")

        val step = rt.resumeSystem()
        assertEquals("endNormal", step.handler, "状态没被劫持（Process.cs:2959-2962）")
        assertEquals(StepOutcome.FAILED, step.outcome)
        assertEquals("", rt.output.toString(), "失败后两帧的后续指令同样不执行")
    }
}
