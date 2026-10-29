package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.proc.SystemStateCode
import com.eratw.port.core.stmt.FunctionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SAVEGAME` / `LOADGAME` 的语句层语义（M11-D SAVE-11）。
 *
 * 原版 `SAVELOADGAME_Instruction.DoInstruction`（`FunctionIdentifier.cs:2967-2980`）只有三句，
 * 逐句对位：
 * 1. `__CAN_SAVE__` 门（`:2969-2977`）—— 断言在 `SystemStatementsTest` 的
 *    「SAVEGAME LOADGAME 在 CAN_SAVE 缺位时被门挡下」。
 * 2. `saveCurrentState(single: true)`（`:2978` → `Process.cs:1959-1966`）：
 *    `prevStateList.Add(state)` + `state = state.Clone()`；`Clone()` 不复制 functionList
 *    （`ProcessState.cs:475-484`）⇒ `ScriptEnd`（`:34`）为真，脚本本次运行结束。
 *    移植侧 = [SaveLoadHandoff.functionListCleared] 信号（整栈丢弃由 proc 侧改写成
 *    `StmtOutcome.Unwind`，断言在 `proc/SaveGameHandoffTest`）。
 * 3. `state.SaveLoadData(isSave)`（`:2979` → `ProcessState.cs:190-200`）：
 *    `sysStateCode = SaveGame_Begin(256)` / `LoadGame_Begin(272)`。
 *
 * 另钉两条本层口径：
 * - DoInstruction 是 `void`，**不写 RESULT**（与 `BEGIN` 的 `state.Return(0L)` 相反，
 *   `FunctionIdentifier.cs:2934` 对照 `:2967-2980`）。
 * - 宿主前置闸是移植侧扩展（原版无宿主调用），位于状态迁移**之前**：宿主拒绝时不留
 *   半进入状态。
 */
class SaveLoadGameSemanticsTest {

    /** 记下「钩子被调时交接槽是什么状态」，用于钉死钩子先于状态迁移。 */
    private class RecordingOps(private val store: com.eratw.port.core.vars.VariableStore) : SystemOps {
        val calls = ArrayList<String>()
        val stateAtCall = ArrayList<SystemStateCode?>()

        override fun saveGame() {
            calls.add("saveGame")
            stateAtCall.add(SaveLoadHandoff.of(store)?.systemState)
        }

        override fun loadGame() {
            calls.add("loadGame")
            stateAtCall.add(SaveLoadHandoff.of(store)?.systemState)
        }
    }

    @Test
    fun `SAVEGAME 放行后交接 SaveGame_Begin 且作废脚本栈`() {
        val env = SemTestEnv()
        val result = env.run(instr(FunctionCode.SAVEGAME))
        assertEquals(ExecResult.Normal, result, "DoInstruction 无返回值（FunctionIdentifier.cs:2967-2980）")

        val handoff = assertNotNull(SaveLoadHandoff.of(env.store), "语句层必须留下交接槽")
        // ProcessState.cs:194 —— sysStateCode = SaveGame_Begin
        assertEquals(SystemStateCode.SaveGame_Begin, handoff.systemState)
        // Process.cs:1964 —— state 换成 Clone() 出的空 functionList 实例 ⇒ 脚本栈作废
        assertTrue(handoff.functionListCleared, "saveCurrentState 的换 clone 等价于整栈丢弃")

        // 不写 RESULT（对照 BEGIN 的 state.Return(0L)，FunctionIdentifier.cs:2934）
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)))
        assertEquals(emptyList<String>(), env.console.events, "两条语句都不打印任何东西")
    }

    @Test
    fun `LOADGAME 放行后交接 LoadGame_Begin 且作废脚本栈`() {
        val env = SemTestEnv()
        assertEquals(ExecResult.Normal, env.run(instr(FunctionCode.LOADGAME)))
        val handoff = assertNotNull(SaveLoadHandoff.of(env.store))
        // ProcessState.cs:198 —— sysStateCode = LoadGame_Begin
        assertEquals(SystemStateCode.LoadGame_Begin, handoff.systemState)
        assertTrue(handoff.functionListCleared)
        assertEquals(0L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `宿主前置闸先于状态迁移`() {
        // 每条语句各用一只新装置：钩子被调时交接槽必须还不存在（= 迁移尚未发生），
        // 这样「宿主拒绝时不留半进入状态」才有意义。
        val saveEnv = SemTestEnv()
        val saveOps = RecordingOps(saveEnv.store)
        saveEnv.ctx.systemOps = saveOps
        saveEnv.run(instr(FunctionCode.SAVEGAME))
        assertEquals(listOf("saveGame"), saveOps.calls, "SAVEGAME 叫 saveGame 闸")
        assertEquals(listOf<SystemStateCode?>(null), saveOps.stateAtCall, "钩子先于状态迁移")

        val loadEnv = SemTestEnv()
        val loadOps = RecordingOps(loadEnv.store)
        loadEnv.ctx.systemOps = loadOps
        loadEnv.run(instr(FunctionCode.LOADGAME))
        assertEquals(listOf("loadGame"), loadOps.calls, "LOADGAME 叫 loadGame 闸")
        assertEquals(listOf<SystemStateCode?>(null), loadOps.stateAtCall, "钩子先于状态迁移")
    }

    @Test
    fun `宿主闸拒绝时状态不迁移`() {
        val env = SemTestEnv()
        env.ctx.systemOps = object : SystemOps {
            override fun saveGame(): Unit = throw CodeEE("装置不进入存档界面")
            override fun loadGame(): Unit = throw CodeEE("装置不进入读档界面")
        }

        val save = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.SAVEGAME)) }
        assertEquals("装置不进入存档界面", save.message)
        assertNull(SaveLoadHandoff.of(env.store), "拒绝路径不建交接槽（同 LOADDATA 失败路径口径）")

        val load = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.LOADGAME)) }
        assertEquals("装置不进入读档界面", load.message)
        assertNull(SaveLoadHandoff.of(env.store))
    }

    @Test
    fun `无 SystemOps 时仍按原版语义迁移`() {
        // 原版这两条语句不依赖任何宿主能力（FunctionIdentifier.cs:2978-2979 都是进程内动作），
        // 故 systemOps == null 不得再报「未实现」。
        val env = SemTestEnv()
        assertNull(env.ctx.systemOps)
        assertEquals(ExecResult.Normal, env.run(instr(FunctionCode.SAVEGAME)))
        assertEquals(
            SystemStateCode.SaveGame_Begin,
            SaveLoadHandoff.of(env.store)!!.systemState,
        )
    }
}
