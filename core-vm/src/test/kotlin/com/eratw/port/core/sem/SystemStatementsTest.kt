package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 流程结果 / 进程状态 / 调试 / 系统钩子语句。
 */
class SystemStatementsTest {

    // ---------------- 流程结果 ----------------

    @Test
    fun `QUIT 返回 Quit 结果`() {
        val env = SemTestEnv()
        val r = env.run(flowStmt(FunctionCode.QUIT))
        assertEquals(ExecResult.Quit(), r)
    }

    @Test
    fun `QUIT_AND_RESTART 与 FORCE_QUIT 标志`() {
        val env = SemTestEnv()
        assertEquals(ExecResult.Quit(restart = true), env.run(flowStmt(FunctionCode.QUIT_AND_RESTART)))
        assertEquals(ExecResult.Quit(force = true), env.run(flowStmt(FunctionCode.FORCE_QUIT)))
    }

    @Test
    fun `BEGIN 返回目标`() {
        val env = SemTestEnv()
        val r = env.run(instr(FunctionCode.BEGIN, strArg("TITLE")))
        assertEquals(ExecResult.Begin("TITLE"), r)
    }

    // ---------------- 进程状态 ----------------

    @Test
    fun `SKIPDISP 置位并写 RESULT`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SKIPDISP, intArg(1)))
        assertTrue(env.ctx.skipPrint)
        assertEquals(1L, env.store.readInt("RESULT", longArrayOf(0)))
        env.run(instr(FunctionCode.SKIPDISP, intArg(0)))
        assertEquals(false, env.ctx.skipPrint)
    }

    @Test
    fun `NOSKIP ENDNOSKIP 保存恢复 skipPrint`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SKIPDISP, intArg(1)))
        env.run(instr(FunctionCode.NOSKIP, VoidArgument()))
        assertEquals(false, env.ctx.skipPrint)
        assertTrue(env.ctx.saveSkip)
        env.run(instr(FunctionCode.ENDNOSKIP, VoidArgument()))
        assertEquals(true, env.ctx.skipPrint)
    }

    @Test
    fun `RESETDATA 重置显示状态`() {
        val env = SemTestEnv()
        env.ctx.skipPrint = true
        env.ctx.mesSkip = true
        env.store.writeStr("SAVEDATA_TEXT", longArrayOf(), "t")
        env.run(instr(FunctionCode.RESETDATA, VoidArgument()))
        assertEquals(false, env.ctx.skipPrint)
        assertEquals(false, env.ctx.mesSkip)
        // SAVEDATA_TEXT 是系统字符串变量本体（VariableEvaluator.cs:119-129），随变量复位清空
        assertEquals("", env.store.readStr("SAVEDATA_TEXT", longArrayOf()))
    }

    @Test
    fun `PUTFORM 累加存档文本`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.PUTFORM, strArg("A")))
        env.run(instr(FunctionCode.PUTFORM, strArg("B")))
        assertEquals("AB", env.store.readStr("SAVEDATA_TEXT", longArrayOf()))
    }

    // ---------------- 断言与抛错 ----------------

    @Test
    fun `ASSERT 参数 0 报错`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.ASSERT, intArg(0))) }
        assertTrue(e.message!!.contains("ASSERT"), e.message)
        env.run(instr(FunctionCode.ASSERT, intArg(1)))
    }

    @Test
    fun `THROW 抛出带文本的 CodeEE`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.THROW, strArg("自定义错误"))) }
        assertEquals("自定义错误", e.message)
    }

    @Test
    fun `未实现语句抛 CodeEE`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.SAVEVAR, VoidArgument())) }
        assertTrue(e.message!!.contains("尚未实现"), e.message)
    }

    // ---------------- 调试 ----------------

    @Test
    fun `DEBUGPRINT 写调试 sink`() {
        val env = SemTestEnv()
        val logs = mutableListOf<String>()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            debug = object : com.eratw.port.core.host.DebugSink {
                override fun print(text: String) {
                    logs.add(text)
                }

                override fun printLine(text: String) {
                    logs.add(text + "\n")
                }

                override fun clear() {}
            },
        )
        env.run(instr(FunctionCode.DEBUGPRINT, strArg("d1")))
        env.run(instr(FunctionCode.DEBUGPRINTL, strArg("d2")))
        assertEquals(listOf("d1", "d2\n"), logs)
    }

    // ---------------- 系统钩子（SystemOps 缺省报错） ----------------

    @Test
    fun `LOADGAME 无 SystemOps 仍按原版语义迁移状态`() {
        // 原版 LOADGAME 只有「门 + saveCurrentState + SaveLoadData」三句，没有宿主调用
        // （FunctionIdentifier.cs:2967-2980）⇒ systemOps 缺省不得再报「未实现」。
        val env = SemTestEnv()
        val r = env.run(instr(FunctionCode.LOADGAME, VoidArgument()))
        assertEquals(ExecResult.Normal, r)
        assertEquals(
            com.eratw.port.core.proc.SystemStateCode.LoadGame_Begin,
            com.eratw.port.core.host.SaveLoadHandoff.of(env.store)!!.systemState,
        )
    }

    /**
     * `SAVEGAME` / `LOADGAME` 的 `__CAN_SAVE__` 门（FunctionIdentifier.cs:2969-2977）：
     * 标志位缺位时抛 `CodeEE("不能使用{当前函数名}命令")`（Lang.Error.CanNotUseInstruction，
     * lang/emuera.zhs.xml:785；模板只有一个 `{0}`，`state.Scope` = 函数栈顶函数名、
     * 空栈为空串，ProcessState.cs:88-97 —— 第二实参 "SAVEGAME/LOADGAME" 被丢弃是官方怪癖）。
     */
    @Test
    fun `SAVEGAME LOADGAME 在 CAN_SAVE 缺位时被门挡下`() {
        val env = SemTestEnv()
        env.ctx.canSave = false
        val save = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.SAVEGAME, VoidArgument())) }
        assertEquals("不能使用命令", save.message)
        val load = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.LOADGAME, VoidArgument())) }
        assertEquals("不能使用命令", load.message)
        // 函数栈顶名字进报文（ProcessState.cs:93-96 的 Scope 取栈顶 FunctionName）
        env.store.enterFunction("EVENTSHOP")
        val inFunc = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.SAVEGAME, VoidArgument())) }
        assertEquals("不能使用EVENTSHOP命令", inFunc.message)
    }

    @Test
    fun `SAVEGAME 门放行后写 SaveGame_Begin 交接`() {
        // 门放行后原版紧接着 saveCurrentState(single: true) + SaveLoadData(true)
        // （FunctionIdentifier.cs:2978-2979）⇒ 语句层留下 SaveGame_Begin 交接。
        val env = SemTestEnv()
        val r = env.run(instr(FunctionCode.SAVEGAME, VoidArgument()))
        assertEquals(ExecResult.Normal, r)
        assertEquals(
            com.eratw.port.core.proc.SystemStateCode.SaveGame_Begin,
            com.eratw.port.core.host.SaveLoadHandoff.of(env.store)!!.systemState,
        )
    }

    @Test
    fun `SAVEDATA 参数校验`() {
        val env = SemTestEnv()
        assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.SAVEDATA,
                    com.eratw.port.core.stmt.SpSaveDataArgument(iLit(-1), sLit("t")),
                ),
            )
        }
        assertFailsWith<CodeEE> {
            env.run(
                instr(
                    FunctionCode.SAVEDATA,
                    com.eratw.port.core.stmt.SpSaveDataArgument(iLit(0), sLit("a\nb")),
                ),
            )
        }
    }

    @Test
    fun `SAVEDATA 保存失败打印错误行`() {
        val env = SemTestEnv()
        env.ctx.systemOps = object : SystemOps {
            override fun saveData(no: Int, text: String): Boolean = false
        }
        env.run(instr(FunctionCode.SAVEDATA, com.eratw.port.core.stmt.SpSaveDataArgument(iLit(1), sLit("t"))))
        assertEquals(listOf("printError(SAVEDATA命令在存档时发生了意料之外的错误)"), env.console.events)
    }

    @Test
    fun `LOADGLOBAL 成功写 RESULT`() {
        val env = SemTestEnv()
        env.ctx.systemOps = object : SystemOps {
            override fun loadGlobal(): Boolean = true
        }
        env.run(instr(FunctionCode.LOADGLOBAL, VoidArgument()))
        assertEquals(1L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    // ---------------- GETTIME ----------------

    @Test
    fun `GETTIME 写 RESULT 数值与 RESULTS 时间串`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.GETTIME, VoidArgument()))
        val num = env.store.readInt("RESULT", longArrayOf(0))
        assertTrue(num > 0, "RESULT 应为正，实际 $num")
        val s = env.store.readStr("RESULTS", longArrayOf(0))
        assertTrue(Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""").matches(s), "RESULTS 格式不符：$s")
    }
}
