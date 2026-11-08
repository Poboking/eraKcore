package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.VoidArgument
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueInputSink.submitCbgButtonValue] 的交付面单测（CBG 取色图按钮值的 core 半段）。
 *
 * ## 判据（原版）
 *
 * - 取色图命中：`cbgButtonMap` 在点击点取色，`A == 255` 才算命中，
 *   值 = `pixel.ToArgb() & 0xFFFFFF`（`EmueraConsole.cs:1313-1320`）；
 * - 闸口 = `IsWaitingPrimitive`：`MouseDown` 一进门就 `if (!IsWaitingPrimitive) return;`
 *   （`:1300-1305`），只有 INPUTMOUSEKEY 等待期才会取色并推进；
 * - 交付 = `InputMouseKey(1, button, x, y, result, ...)` → `Process.InputResult5`
 *   整组写 `RESULT:0..5`（`:1327-1337`、`Process.cs:393-401`），**值在 RESULT:4**。
 *
 * ## 本移植的槽位偏差（登记项）
 *
 * 交付契约（`InputOutcome`）只有单个 int/str 通道 ⇒ 值走主通道、sem 的 INPUTMOUSEKEY
 * 分支写 **`RESULT:0`**（原版 `RESULT:4`）。因此本文件后半段用**真 VM 执行面**
 * （`StatementExecutor` + 真 [QueueInputSink]）钉住「投喂后脚本读到该值」，
 * 而不是钉槽位 —— 忠实槽位要扩 `InputOutcome`（不在本刀文件面，见交付「需组长接线」）。
 */
class QueueInputSinkCbgButtonTest {

    /** 假单调时钟（纳秒）：每被读一次前进 1 秒 ⇒ 超时分支可零墙钟驱动。 */
    private fun clockOf(): () -> Long {
        val now = AtomicLong(0L)
        return { now.addAndGet(1_000_000_000L) }
    }

    private fun req(
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        mouseInput: Boolean = false,
        hasDef: Boolean = false,
        defInt: Long = 0L,
        timeLimit: Long = -1L,
    ) = InputRequest(
        type = type,
        form = form,
        mouseInput = mouseInput,
        hasDef = hasDef,
        defInt = defInt,
        timeLimit = timeLimit,
    )

    // ------------------------------------------------------------------
    // 交付分派
    // ------------------------------------------------------------------

    /** INPUTMOUSEKEY 吃按钮值条目：推进并把值交到主通道（sem 写 RESULT:0）。 */
    @Test
    fun `INPUTMOUSEKEY 吃按钮值条目 推进并交付`() {
        val sink = QueueInputSink(timeSource = clockOf())
        assertTrue(sink.submitCbgButtonValue(0x1A2B3CL), "入队应成功")

        val out = sink.waitInput(req(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))

        assertFalse(out.isTimeout)
        assertEquals(0x1A2B3CL, out.intValue, "值走交付主通道")
        assertTrue(out.writesInt, "sem 的 o.writesInt 分支写 RESULT:0")
        assertFalse(out.writesStr, "按钮值不写 RESULTS")
        assertFalse(sink.consumeMesSkip(), "按钮按下不是回车，不带跳过意图")
        assertEquals(1L, sink.lastSubmittedSeq)
        assertEquals(1L, sink.lastConsumedSeq, "条目被等待方吃掉并推进")
        assertTrue(
            sink.recentInputTrail().any { it == "#1 enq cbgbtn=1a2b3c" },
            "流水要能认出按钮值提交，实际=${sink.recentInputTrail()}",
        )
    }

    /** 值 0（取色图黑像素，`A=255` 时合法）照常交付，不因哨兵语义被吞。 */
    @Test
    fun `按钮值 0 照常交付`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitCbgButtonValue(0L)

        val out = sink.waitInput(req(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))

        assertFalse(out.isTimeout)
        assertEquals(0L, out.intValue)
        assertTrue(out.writesInt)
    }

    /**
     * 非 INPUTMOUSEKEY 的形态拒收按钮值：原版 `MouseDown` 在 `!IsWaitingPrimitive` 时
     * 直接 return（`EmueraConsole.cs:1300-1305`），连取色都不发生 ⇒ 丢弃、不推进、
     * 不留给下一次等待。
     */
    @Test
    fun `普通 INPUT 拒收按钮值 丢弃不推进`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitCbgButtonValue(0x123456L)

        val out = sink.waitInput(
            req(type = InputRequest.InputType.INT, hasDef = true, defInt = 9L, timeLimit = 100),
        )

        assertTrue(out.isTimeout, "条目被拒收，这次等待只能靠超时收尾")
        assertEquals(9L, out.intValue, "超时交付默认值")
        assertEquals(1L, sink.dropCbgButtonCount, "拒收单独计数")
        assertEquals(1L, sink.lastDropCbgButtonSeq)
        assertEquals(0, sink.pendingCount, "拒收不留底")
        assertTrue(
            sink.recentInputTrail().any { it == "#1 drop(cbgbtn)" },
            "流水要认出按钮值拒收，实际=${sink.recentInputTrail()}",
        )
    }

    /** 带鼠标的 INPUT（`mouseInput=true`）同样拒收：CBG 值只服务 INPUTMOUSEKEY。 */
    @Test
    fun `带鼠标的 INPUT 也拒收按钮值`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitCbgButtonValue(0x123456L)

        // INT 无默认值时原版是**死等**（`EmueraConsole.cs:1119-1128`：超时空文本解析失败 ⇒
        // 不推进、继续等），测试若不给默认值就会永远挂在 `awaitQuietly` 上（2026-11-06 实测
        // 把整个 test worker 拖死 15 分钟）。这里给默认值让超时有交付面。
        val out = sink.waitInput(req(mouseInput = true, hasDef = true, defInt = 0L, timeLimit = 100))

        assertTrue(out.isTimeout)
        assertEquals(0L, out.intValue, "超时交付默认值")
        assertEquals(1L, sink.dropCbgButtonCount)
    }

    /** 队列已关闭后投喂拒收：同 [QueueInputSink.submit]，不抛、不发号。 */
    @Test
    fun `关闭后投喂拒收 不发号`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.close()

        assertFalse(sink.submitCbgButtonValue(0x123456L))
        assertEquals(0L, sink.lastSubmittedSeq)
    }

    /**
     * **投喂后 VM 读到该值**（端到端：`StatementExecutor` 执行真 `INPUTMOUSEKEY` 语句，
     * 等待方阻塞在真 [QueueInputSink] 上；UI 侧投喂后脚本从 `RESULT` 读回）。
     *
     * 槽位口径 = `RESULT:0`（本移植登记偏差，见类 KDoc）；`ResultValue` 是 ERB 侧唯一的
     * 读取面 —— 脚本写 `RESULT:0` 就能拿到它，这条断言因此等价于「玩家点 CBG 按钮 →
     * 脚本读到按钮值」。
     */
    @Test
    fun `投喂后 VM 的 INPUTMOUSEKEY 读到该值`() {
        val sink = QueueInputSink()
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            input = sink,
        )
        var done = false
        val vm = Thread(
            {
                env.run(instr(FunctionCode.INPUTMOUSEKEY, VoidArgument()))
                done = true
            },
            "cbg-button-vm",
        )
        // daemon：VM 线程若因语义不符而永远停在等待点（INPUTMOUSEKEY 无 TIMES ⇒ 无截止点），
        // 非 daemon 会阻止 test worker JVM 退出，表现为「整个构建挂死」（2026-11-06 实测）。
        // 语义错由下面的 `assertTrue(done)` 报红，而不是把构建悬住。
        vm.isDaemon = true
        vm.start()
        try {
            awaitAwaiting(sink)
            assertTrue(sink.submitCbgButtonValue(0xABCDEFL), "投喂应入队")
            vm.join(5_000)
            assertTrue(done, "INPUTMOUSEKEY 应被一次投喂推进")
            assertEquals(
                0xABCDEFL,
                env.store.readInt("RESULT", longArrayOf(0)),
                "脚本从 RESULT:0 读回按钮值（原版落 RESULT:4，槽位偏差见类 KDoc）",
            )
            assertFalse(env.ctx.variables.isTimeout, "不是超时交付")
        } finally {
            sink.close()
            vm.join(2_000)
        }
    }

    /** 等等待方进入等待态（带超时，红要红成断言而不是挂住）。 */
    private fun awaitAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) Thread.sleep(5)
        assertTrue(sink.isAwaiting, "等待方未进入等待态")
    }
}
