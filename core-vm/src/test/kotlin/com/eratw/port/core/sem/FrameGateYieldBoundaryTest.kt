package com.eratw.port.core.sem

import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.stmt.FunctionCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 语句层让出边界的帧闸接线回归钉子（CONSOLE-FRAMEGATE，main 提交 9b2d8e9 修复面）。
 *
 * 原版是 WinForms 单线程协作调度：paint 只在 `Application.DoEvents()` 等消息泵运行点
 * 有机会执行 —— `EmueraConsole.Await` 第一步就是 `RefreshStrings(force_Paint: true)`
 * （EmueraConsole.cs:847），输入等待置 state=WaitInput 后主循环退到消息循环同样给 paint
 * 让路。移植侧是双线程，等价落点 = 三个让出边界各调一次 publishFrame（等前上屏）/
 * holdFrame（返回后重新冻结）：
 *
 * - `AWAIT`：睡前发布、睡后冻结（StatementExecutor 的 AWAIT 分支）；
 * - `WAIT` / `FORCEWAIT` / `WAITANYKEY`（readAnyKey 路径）：等前发布、等后冻结；
 * - `INPUT` 系（waitInput 路径）：等前发布、等后冻结。
 *
 * 顺序钉死为 publishFrame 在前、holdFrame 在后；漏掉任何一侧都会复现
 * 「画面卡在上一帧」或「脚本级高频抖动」。
 */
class FrameGateYieldBoundaryTest {

    /** 记录帧闸事件的 Fake sink（FakeConsoleSink 对 publish/hold 用接口缺省 no-op）。 */
    private class GateRecordingSink : FakeConsoleSink() {
        override fun publishFrame() {
            events.add("publishFrame")
        }

        override fun holdFrame() {
            events.add("holdFrame")
        }
    }

    private fun envWith(console: GateRecordingSink): SemTestEnv {
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = console,
            input = env.input,
        )
        return env
    }

    /** AWAIT：发布 → 睡 → 冻结，三步顺序钉死（原版 Await 第一步强制重绘，EmueraConsole.cs:847）。 */
    @Test
    fun awaitPublishesBeforeSleepHoldsAfter() {
        val console = GateRecordingSink()
        val env = envWith(console)
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = console,
            input = env.input,
            timer = object : TimerSink {
                override fun delay(millis: Long) {
                    console.events.add("delay")
                }
            },
        )
        env.run(instr(FunctionCode.AWAIT, intArg(1)))
        assertEquals(listOf("publishFrame", "delay", "holdFrame"), console.events)
    }

    /** WAIT（readAnyKey 路径）：等前发布、等后冻结。 */
    @Test
    fun waitPublishesBeforeHoldAfter() {
        val console = GateRecordingSink()
        val env = envWith(console)
        env.run(instr(FunctionCode.WAIT))
        assertEquals(listOf("publishFrame", "holdFrame"), console.events)
    }

    /** FORCEWAIT（readAnyKey stopMesSkip 路径）：接线与 WAIT 相同。 */
    @Test
    fun forceWaitPublishesBeforeHoldAfter() {
        val console = GateRecordingSink()
        val env = envWith(console)
        env.run(instr(FunctionCode.FORCEWAIT))
        assertEquals(listOf("publishFrame", "holdFrame"), console.events)
    }

    /** INPUTANY（waitInput 路径）：等前发布、等后冻结。 */
    @Test
    fun inputWaitPublishesBeforeHoldAfter() {
        val console = GateRecordingSink()
        val env = envWith(console)
        env.run(instr(FunctionCode.INPUTANY))
        assertEquals(listOf("publishFrame", "holdFrame"), console.events)
    }
}
