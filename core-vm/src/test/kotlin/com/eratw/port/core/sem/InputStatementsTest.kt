package com.eratw.port.core.sem

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WAIT / INPUT 家族执行语义。
 * spec: FunctionIdentifier.cs:762-1099 + 2113-2529（BINPUT）。
 */
class InputStatementsTest {

    private fun inputsArg(def: Long? = null, mouse: Long? = null, canSkip: Long? = null) =
        SpInputsArgument(
            def = def?.let { com.eratw.port.core.expr.IntLiteral(it) },
            mouse = mouse?.let { com.eratw.port.core.expr.IntLiteral(it) },
            canSkip = canSkip?.let { com.eratw.port.core.expr.IntLiteral(it) },
        )

    // ---------------- WAIT 家族 ----------------

    @Test
    fun `WAIT 等待任意键且允许消息跳过`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertEquals(listOf(false to false), env.input.anyKeyCalls)
    }

    @Test
    fun `FORCEWAIT 禁止消息跳过中断`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.FORCEWAIT, VoidArgument()))
        assertEquals(listOf(false to true), env.input.anyKeyCalls)
    }

    @Test
    fun `WAITANYKEY anykey 为 true`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.WAITANYKEY, VoidArgument()))
        assertEquals(listOf(true to false), env.input.anyKeyCalls)
    }

    /**
     * 右侧选项栏的冻结入口：WAIT 系在等待前也要通知控制台（形态 = ENTER/ANY_KEY），
     * 让 `LineBufferConsoleSink.noteInputWait` 清空上一页快照，故事正文里的 `[N]` 不残留。
     */
    @Test
    fun `WAIT 等待前通知控制台清空选项快照`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        val note = env.console.waitNotes.single()
        assertEquals(InputRequest.RequestForm.ENTER_KEY, note.form)
    }

    /** 取值等待把**同一个请求**交给控制台冻结选项（INT/STR 过滤与输入队列同源）。 */
    @Test
    fun `INPUT 等待前把取值请求交给控制台冻结选项`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.INPUT, inputsArg()))
        val note = env.console.waitNotes.single()
        assertEquals(InputRequest.RequestForm.VALUE, note.form)
        assertEquals(InputRequest.InputType.INT, note.type)
        assertEquals(env.input.requests.single(), note)
    }

    // ---------------- TWAIT ----------------

    /**
     * 原版只有**一次**真等待：`Console.ReadAnyKey()`（FunctionIdentifier.cs:810）与随后的
     * `Console.WaitInput(req)`（:823）都只是给控制台改字段（EmueraConsole.cs:876-895 / :858-874），
     * `DoInstruction` 不返回控制台就不重绘，所以第一次等待被第二次整个覆盖，用户答不上来；
     * 第二次是 `Timelimit = 第1参`、形态 EnterKey（第2参为 0）的限时等待。
     * 旧实现多了一次「真等一次回车」，会把 `TWAIT 1500, 0` 变成「先等按键、再等 1500ms」。
     */
    @Test
    fun `TWAIT 只有一次 EnterKey 形态限时等待并回写超时`() {
        val env = SemTestEnv(InputOutcome(isTimeout = true))
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(2000), IntLiteral(0))))
        assertTrue(env.input.anyKeyCalls.isEmpty(), "第 1 次 ReadAnyKey 被第 2 次 WaitInput 覆盖，不得真等（EmueraConsole.cs:864-865）")
        val req = env.input.requests.single()
        assertEquals(2000L, req.timeLimit)
        assertEquals(
            InputRequest.RequestForm.ENTER_KEY,
            req.form,
            "第2参=0 ⇒ InputType.EnterKey，不是 INT 取值请求（FunctionIdentifier.cs:814-821）",
        )
        assertTrue(env.ctx.variables.isTimeout, "超时结果要落到 ISTIMEOUT（EmueraConsole.cs:1043）")
    }

    /** y≠0 时原版把这次等待改成 Void（只等时限、不收文本），仍然只有一次（:818-821）。 */
    @Test
    fun `TWAIT y 非 0 是 Void 形态的限时等待`() {
        val env = SemTestEnv(InputOutcome(isTimeout = false))
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(500), IntLiteral(1))))
        assertEquals(1, env.input.requests.size, "y≠0 不得追加第二次等待")
        val req = env.input.requests.single()
        assertEquals(500L, req.timeLimit)
        assertEquals(InputRequest.RequestForm.VOID, req.form, "第2参≠0 ⇒ InputType.Void（FunctionIdentifier.cs:818-821）")
        assertFalse(req.needValue, "Void 不需要值 ⇒ 跳过态下被输入循环跑过（InputRequest.cs:31-41）")
        assertFalse(env.ctx.variables.isTimeout)
    }

    /**
     * 消息跳过中 TWAIT 不等待（:1451-1455 的 NeedValue=false 自动推进），
     * 但原版照样调了 `WaitInput` ⇒ `presetTimer`/`setTimer` 把 `isTimeout` 清零
     * （EmueraConsole.cs:866-873 / :994-996），所以 ISTIMEOUT 的旧真值在这里被抹掉。
     */
    @Test
    fun `TWAIT 在消息跳过中不等待但照旧清零 ISTIMEOUT`() {
        val env = SemTestEnv(InputOutcome(isTimeout = true))
        env.ctx.variables.isTimeout = true
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(1000), IntLiteral(1))))
        assertTrue(env.input.requests.isEmpty(), "跳过态下这次 Void 等待被输入循环跑过")
        assertFalse(env.ctx.variables.isTimeout, "武装计时器即清零 isTimeout（EmueraConsole.cs:996）")
    }

    // ---------------- 消息跳过（MesSkip） ----------------

    @Test
    fun `WAIT 在消息跳过中不发起等待`() {
        val env = SemTestEnv()
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertTrue(env.input.anyKeyCalls.isEmpty(), "NeedValue=false 的等待由输入循环跑过（EmueraConsole.cs:1451-1455）")
    }

    @Test
    fun `FORCEWAIT 在消息跳过中仍等待且提交后清零`() {
        val env = SemTestEnv()
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.FORCEWAIT, VoidArgument()))
        assertEquals(listOf(false to true), env.input.anyKeyCalls, "StopMesskip 不受跳过影响")
        assertFalse(env.ctx.mesSkip, "循环后 MesSkip=false（EmueraConsole.cs:1456）")
    }

    @Test
    fun `WAIT 消费跳过提交后置 mesSkip`() {
        val env = SemTestEnv()
        env.input.nextMesSkip = true
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertTrue(env.ctx.mesSkip, "keySkip=true 的提交把跳过态落到 ExecContext（EmueraConsole.cs:1373）")
    }

    @Test
    fun `FORCEWAIT 的普通提交把 mesSkip 清零`() {
        val env = SemTestEnv()
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.FORCEWAIT, VoidArgument()))
        assertFalse(env.ctx.mesSkip, "FORCEWAIT 消费普通提交后跳过态结束（EmueraConsole.cs:1451-1456）")
    }

    @Test
    fun `取值输入结束后跳过态终止`() {
        val env = SemTestEnv(InputOutcome(intValue = 1))
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertTrue(env.input.anyKeyCalls.isEmpty(), "跳过态下 WAIT 不等待")
        env.run(instr(FunctionCode.INPUT, inputsArg()))
        assertFalse(env.ctx.mesSkip, "取值等待前的清零（EmueraConsole.cs:1451-1456）")
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertEquals(listOf(false to false), env.input.anyKeyCalls, "普通提交之后 WAIT 恢复等待")
    }

    @Test
    fun `取值输入消费跳过提交后 下一次 WAIT 继续跳过`() {
        val env = SemTestEnv(InputOutcome(intValue = 1))
        env.input.nextMesSkip = true
        env.run(instr(FunctionCode.INPUT, inputsArg()))
        assertTrue(env.ctx.mesSkip, "本次提交的 keySkip 在取值之后生效（EmueraConsole.cs:1373）")
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertTrue(env.input.anyKeyCalls.isEmpty(), "跳过态延续到下一条 WAIT")
    }

    // ---------------- INPUT / INPUTS ----------------

    @Test
    fun `INPUT 结果写 RESULT`() {
        val env = SemTestEnv(InputOutcome(intValue = 123))
        env.run(instr(FunctionCode.INPUT, inputsArg()))
        assertEquals(123L, env.store.readInt("RESULT", longArrayOf(0)))
        val req = env.input.requests.single()
        assertEquals(InputRequest.InputType.INT, req.type)
        assertFalse(req.oneInput)
    }

    @Test
    fun `INPUTS 结果写 RESULTS`() {
        val env = SemTestEnv(InputOutcome(strValue = "hello"))
        env.run(instr(FunctionCode.INPUTS, inputsArg()))
        assertEquals("hello", env.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals(InputRequest.InputType.STR, env.input.requests.single().type)
    }

    @Test
    fun `ONEINPUT 请求单键输入`() {
        val env = SemTestEnv(InputOutcome(intValue = 7))
        env.run(instr(FunctionCode.ONEINPUT, inputsArg()))
        val req = env.input.requests.single()
        assertTrue(req.oneInput)
        assertEquals(7L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `INPUT 带默认值参数写入请求`() {
        val env = SemTestEnv(InputOutcome(intValue = 9))
        env.run(instr(FunctionCode.INPUT, inputsArg(def = 42, mouse = 1)))
        val req = env.input.requests.single()
        assertTrue(req.hasDef)
        assertEquals(42L, req.defInt)
        assertTrue(req.mouseInput)
        assertEquals(9L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `INPUT 在消息跳过中直接取默认值不等待`() {
        val env = SemTestEnv()
        env.ctx.mesSkip = true
        env.run(instr(FunctionCode.INPUT, inputsArg(def = 77, canSkip = 1)))
        assertTrue(env.input.requests.isEmpty())
        assertEquals(77L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    // ---------------- TINPUT 家族 ----------------

    @Test
    fun `TINPUT 限时与默认值语义`() {
        val env = SemTestEnv(InputOutcome(intValue = 5, isTimeout = true))
        env.run(
            instr(
                FunctionCode.TINPUT,
                SpTInputsArgument(
                    time = com.eratw.port.core.expr.IntLiteral(2000),
                    def = com.eratw.port.core.expr.IntLiteral(11),
                    disp = null,
                    timeout = null,
                    mouse = null,
                    canSkip = null,
                ),
            ),
        )
        val req = env.input.requests.single()
        assertEquals(2000L, req.timeLimit)
        assertTrue(req.hasDef)
        assertEquals(11L, req.defInt)
        assertTrue(req.displayTime) // disp 缺省为显示
        assertEquals(env.ctx.config.timeupLabel, req.timeUpMessage)
        assertEquals(5L, env.store.readInt("RESULT", longArrayOf(0)))
        assertTrue(env.ctx.variables.isTimeout)
    }

    @Test
    fun `TINPUTS 超时提示语自定义`() {
        val env = SemTestEnv(InputOutcome(strValue = "s"))
        env.run(
            instr(
                FunctionCode.TINPUTS,
                SpTInputsArgument(
                    time = com.eratw.port.core.expr.IntLiteral(100),
                    def = com.eratw.port.core.expr.StrLiteral("d"),
                    disp = com.eratw.port.core.expr.IntLiteral(0),
                    timeout = com.eratw.port.core.expr.StrLiteral("超时啦"),
                    mouse = null,
                    canSkip = null,
                ),
            ),
        )
        val req = env.input.requests.single()
        assertEquals("超时啦", req.timeUpMessage)
        assertFalse(req.displayTime)
        assertEquals("d", req.defStr)
        assertEquals("s", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    /**
     * TINPUT 的 CanSkip 快速路径原版**不调** `WaitInput`（FunctionIdentifier.cs:1034-1044：
     * 直接写 RESULT/RESULT_ARRAY[1] 默认值后收工）⇒ `presetTimer` 不挂（EmueraConsole.cs:866-873）
     * ⇒ `setTimer` 不跑 ⇒ `isTimeout` 一个字节都不动，旧值原样保留。
     * 与「真等待路径武装即清零」（:994-996）互为对照，是 ISTIMEOUT 复位点逐点对齐的一环。
     */
    @Test
    fun `TINPUT 消息跳过快速路径不动 ISTIMEOUT`() {
        val env = SemTestEnv()
        env.ctx.variables.isTimeout = true
        env.ctx.mesSkip = true
        env.run(
            instr(
                FunctionCode.TINPUT,
                SpTInputsArgument(
                    time = com.eratw.port.core.expr.IntLiteral(1000),
                    def = com.eratw.port.core.expr.IntLiteral(55),
                    disp = null,
                    timeout = null,
                    mouse = com.eratw.port.core.expr.IntLiteral(0),
                    canSkip = com.eratw.port.core.expr.IntLiteral(1),
                ),
            ),
        )
        assertTrue(env.input.requests.isEmpty(), "CanSkip 路径不发起等待（FunctionIdentifier.cs:1034-1044）")
        assertEquals(55L, env.store.readInt("RESULT", longArrayOf(0)), "mouse=0 ⇒ 写 RESULT 默认值（:1036-1038）")
        assertTrue(
            env.ctx.variables.isTimeout,
            "没有 WaitInput ⇒ setTimer 不跑 ⇒ 旧值保留（EmueraConsole.cs:866-873/994-996）",
        )
    }

    // ---------------- 其他输入 ----------------

    @Test
    fun `INPUTANY 整数交付只写 RESULT 不改 RESULTS`() {
        val env = SemTestEnv(InputOutcome(intValue = 3, strValue = "any"))
        env.store.writeStr("RESULTS", longArrayOf(0), "旧值")
        env.run(instr(FunctionCode.INPUTANY, VoidArgument()))
        assertEquals(3L, env.store.readInt("RESULT", longArrayOf(0)))
        assertEquals(
            "旧值",
            env.store.readStr("RESULTS", longArrayOf(0)),
            "AnyValue 分支整数/字符串二选一交付（EmueraConsole.cs:1256-1274），本移植只产整数交付",
        )
    }

    /**
     * 非限时等待不改写 ISTIMEOUT：原版 `isTimeout` 只在限时等待开始时被 `setTimer()` 清零
     * （`presetTimer` 仅 `Timelimit > 0` 才挂起，EmueraConsole.cs:866-873 / :994-996），
     * INPUT 系不带时限 ⇒ 上一次限时等待留下的旧值一直保留到下一次限时等待。
     */
    @Test
    fun `INPUT 不改写 ISTIMEOUT 旧值`() {
        val env = SemTestEnv(InputOutcome(intValue = 1))
        env.ctx.variables.isTimeout = true
        env.run(instr(FunctionCode.INPUT, inputsArg()))
        assertTrue(env.ctx.variables.isTimeout, "INPUT 不改写 isTimeout（EmueraConsole.cs:866-873/994-996）")
        assertEquals(1L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    /**
     * INPUTMOUSEKEY 超时走 `endTimer` 的 PrimitiveMouseKey 分支（EmueraConsole.cs:1044-1048）：
     * `InputMouseKey(4, 0, 0, 0, 0, 0)` → `InputResult5` 整组写 RESULT:0..5
     * （Process.cs:393-401），r0 = 4 是「超时交付」类型位。
     */
    @Test
    fun `INPUTMOUSEKEY 超时写 RESULT 类型位 4 其余清零`() {
        val env = SemTestEnv(InputOutcome(isTimeout = true))
        env.store.writeInt("RESULT", longArrayOf(1), 9L)
        env.run(instr(FunctionCode.INPUTMOUSEKEY, intArg(1000)))
        assertTrue(env.ctx.variables.isTimeout)
        assertEquals(4L, env.store.readInt("RESULT", longArrayOf(0)), "超时交付类型位（EmueraConsole.cs:1046）")
        for (i in 1L..5L) {
            assertEquals(0L, env.store.readInt("RESULT", longArrayOf(i)), "RESULT:$i 整组清零（Process.cs:393-401）")
        }
    }

    @Test
    fun `INPUTMOUSEKEY 无时限参数时不限时`() {
        val env = SemTestEnv(InputOutcome(intValue = 7))
        env.run(instr(FunctionCode.INPUTMOUSEKEY, VoidArgument()))
        assertEquals(-1L, env.input.requests.single().timeLimit, "缺参 = num=0 ⇒ Timelimit 保持 -1（FunctionIdentifier.cs:2081-2085）")
        assertEquals(7L, env.store.readInt("RESULT", longArrayOf(0)))
        assertFalse(env.ctx.variables.isTimeout, "非限时交付不改写 isTimeout")
    }

    @Test
    fun `BINPUT 先换行再等待`() {
        val env = SemTestEnv(InputOutcome(intValue = 1))
        env.run(instr(FunctionCode.BINPUT, inputsArg(def = 2)))
        assertEquals(listOf("newLine"), env.console.events)
        assertEquals(1L, env.store.readInt("RESULT", longArrayOf(0)))
    }

    @Test
    fun `AWAIT 延迟并校验范围`() {
        val env = SemTestEnv()
        val delays = mutableListOf<Long>()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            timer = object : com.eratw.port.core.host.TimerSink {
                override fun delay(millis: Long) {
                    delays.add(millis)
                }
            },
        )
        env.run(instr(FunctionCode.AWAIT, intArg(500)))
        assertEquals(listOf(500L), delays)
        org.junit.jupiter.api.assertThrows<com.eratw.port.core.err.CodeEE> {
            env.run(instr(FunctionCode.AWAIT, intArg(-1)))
        }
    }

    /**
     * M10-AWAIT：`AWAIT` 必须计成死循环闸的让出边界。
     *
     * 原版把死循环保护的复位点**唯一地**放在 `EmueraConsole.Await` 里
     * （`Process.cs:487-491` 的 `UpdateCheckInfiniteLoopState()` 全工程只被
     * `EmueraConsole.cs:849` 调用一次）。本移植若不把 `AWAIT` 记成边界，
     * `@BADAPPLE`（`TITLE.ERB:123`，`FOR count, 1, 6573` 每帧 `AWAIT`/`AWAIT 16`）
     * 这种整段只靠帧让出的动画会在一次 run 里攒满段落计数撞 `StepLimitExceeded`。
     */
    @Test
    fun `AWAIT 每次执行都推进让出边界计数`() {
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            timer = object : com.eratw.port.core.host.TimerSink {
                override fun delay(millis: Long) {}
            },
        )
        assertEquals(0L, env.executor.yieldBoundaryCount, "起点：还没跨任何边界")
        env.run(instr(FunctionCode.AWAIT, intArg(16)))
        env.run(instr(FunctionCode.AWAIT, intArg(0)))
        assertEquals(2L, env.executor.yieldBoundaryCount, "两帧 AWAIT ⇒ 两次段落交界")
        org.junit.jupiter.api.assertThrows<com.eratw.port.core.err.CodeEE> {
            env.run(instr(FunctionCode.AWAIT, intArg(-1)))
        }
        assertEquals(2L, env.executor.yieldBoundaryCount, "参数非法的 AWAIT 没有发生让出，不得记账")
    }
}
