package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueInputSink.submitMappedColor] 的交付面单测（INPUT(6/3,color) 消费面的 core 半段）。
 *
 * 判据全部来自原版窗口侧的鼠标按下分支（`MinorShift.Emuera.Forms/MainWindow.cs`
 * `mainPicBox_MouseDown`）：
 * - INPUTMOUSEKEY（`IsWaitingPrimitive`，spec: `EmueraConsole.cs:383-392`）等待下鼠标按下
 *   推进等待并取色（`MainWindow.cs:790`/`:795`）；
 * - INPUT 系带鼠标（`IsWaintingInputWithMouse`，spec: `EmueraConsole.cs:286-293`）等待下
 *   命中按钮时写色（`MainWindow.cs:828`）；
 * - 其余等待态鼠标按下不写色（`MainWindow.cs:806`/`:824`/`:851` 三个分支都要求等待态匹配）。
 *
 * 本移植的槽位偏差（色走交付主通道 RESULT:0 而非原版 RESULT:6/3）是登记项，
 * 交付判定断到 [com.eratw.port.core.host.InputOutcome] 为止；sem 层把
 * `writesInt` 分支写进 RESULT:0 的既有行为由 StatementExecutor 侧钉住，不在本文件。
 * 交付分派表与逐形态证据见 [QueueInputSink.interpretMappedColor] 的 KDoc。
 *
 * 自包含：不依赖语料环境变量；需要越过截止点的用例注入假单调时钟（每读一次前进 1 秒）。
 */
class QueueInputSinkMappedColorTest {

    /** 假单调时钟（纳秒）：每被读一次前进 1 秒 ⇒ 进入等待后的第一次截止判定必然越过 deadline。 */
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
        defStr: String = "",
        timeLimit: Long = -1L,
    ) = InputRequest(
        type = type,
        form = form,
        mouseInput = mouseInput,
        hasDef = hasDef,
        defInt = defInt,
        defStr = defStr,
        timeLimit = timeLimit,
    )

    // ------------------------------------------------------------------

    /**
     * INPUTMOUSEKEY 等待吃色条目：推进 + 色走交付主通道（writesInt=true ⇒ sem 写 RESULT:0；
     * 原版写 RESULT:6 的槽位偏差见 [QueueInputSink.interpretMappedColor] 的 KDoc）。
     * 色条目不带跳过意图（mesSkip 恒假）：取色不是回车，不能把 MesSkip 置真
     * （原版取色在 `MainWindow.cs:795`，与 `:1373` 的 `MesSkip = keySkip` 无涉）。
     */
    @Test
    fun `INPUTMOUSEKEY 吃色条目 推进并交付色值`() {
        val sink = QueueInputSink(timeSource = clockOf())
        assertTrue(sink.submitMappedColor(0x1A2B3CL), "入队应成功")

        val out = sink.waitInput(req(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))

        assertFalse(out.isTimeout, "未超时的取色交付")
        assertEquals(0x1A2B3CL, out.intValue, "色值走交付主通道（RESULT:0）")
        assertTrue(out.writesInt, "sem 的 o.writesInt 分支要写 RESULT:0")
        assertFalse(out.writesStr, "取色交付不写 RESULTS")
        assertFalse(sink.consumeMesSkip(), "取色不带跳过意图")
        assertEquals(1L, sink.lastSubmittedSeq)
        assertEquals(1L, sink.lastConsumedSeq, "色条目被等待方吃掉并推进")
        val trail = sink.recentInputTrail()
        assertTrue(trail.any { it == "#1 enq color=1a2b3c" }, "流水要能认出取色提交，实际=$trail")
        assertTrue(trail.any { it.startsWith("#1 con ") }, "消费侧同号，实际=$trail")
    }

    /** 色值 0（遮罩黑像素）也是命中、照常交付：原版 `GetMappedColor` 对黑像素同样返回 0L。 */
    @Test
    fun `INPUTMOUSEKEY 吃黑像素色值 0 照常交付`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(0L)

        val out = sink.waitInput(req(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))

        assertFalse(out.isTimeout)
        assertEquals(0L, out.intValue)
        assertTrue(out.writesInt)
    }

    /** INPUT 系带鼠标 + INT：色走交付主通道（INT 通道；原版写 RESULT:3，槽位偏差同上）。 */
    @Test
    fun `带鼠标的 INT 等待吃色条目 交付色值`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(654_321L)

        val out = sink.waitInput(req(type = InputRequest.InputType.INT, mouseInput = true))

        assertFalse(out.isTimeout)
        assertEquals(654_321L, out.intValue)
        assertTrue(out.writesInt)
        assertFalse(out.writesStr)
    }

    /** INPUT 系带鼠标 + STR：色以十进制串交付 RESULTS（与 `InputString` 的槽类型同型）。 */
    @Test
    fun `带鼠标的 STR 等待吃色条目 交付十进制串`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(0xABCDEFL)

        val out = sink.waitInput(req(type = InputRequest.InputType.STR, mouseInput = true))

        assertFalse(out.isTimeout)
        assertEquals("11259375", out.strValue, "0xABCDEF 的十进制串")
        assertFalse(out.writesInt)
        assertTrue(out.writesStr)
    }

    /**
     * 鼠标未开的 INPUT 拒收色条目：丢弃、不推进（超时收尾交付默认值）、
     * **不留给下一次等待**（原版鼠标按下对该等待态无支可落，`MainWindow.cs:824`/`:851`）。
     * 单独计数 `drop(color)`，不混进 parse（色条目 text 恒空）。
     */
    @Test
    fun `无鼠标的 INPUT 拒收色条目 丢弃不推进也不留底`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(0x123456L)

        val out = sink.waitInput(
            req(type = InputRequest.InputType.INT, hasDef = true, defInt = 9L, timeLimit = 100),
        )

        assertTrue(out.isTimeout, "色条目被拒收，这次等待只能靠超时收尾")
        assertEquals(9L, out.intValue, "超时交付默认值")
        assertEquals(1L, sink.dropColorCount, "拒收单独计数")
        assertEquals(1L, sink.lastDropColorSeq)
        assertEquals(0, sink.pendingCount, "拒收不留底：不落到下一次等待")
        assertTrue(
            sink.recentInputTrail().any { it == "#1 drop(color)" },
            "流水要认出取色拒收，实际=${sink.recentInputTrail()}",
        )

        // 下一次等待不得凭空吃到那次色：重新投喂才收。
        var delivered: Long? = null
        val waiter = Thread {
            delivered = sink.waitInput(req(type = InputRequest.InputType.INT)).intValue
        }
        waiter.start()
        awaitAwaiting(sink)
        sink.submit("7")
        waiter.join(2_000)
        assertEquals(7L, delivered, "下一次等待只吃新提交")
    }

    /** INPUTANY（ANY_VALUE，无鼠标参数，spec: `FunctionIdentifier.cs:2095-2111`）同样拒收色条目。 */
    @Test
    fun `ANY_VALUE 拒收色条目 超时收尾`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(0x123456L)

        val out = sink.waitInput(req(form = InputRequest.RequestForm.ANY_VALUE, timeLimit = 100))

        assertTrue(out.isTimeout)
        assertEquals("", out.strValue, "ANY_VALUE 超时交付空串")
        assertEquals(1L, sink.dropColorCount)
        assertEquals(0, sink.pendingCount)
    }

    /** WAIT 系（readAnyKey）吃色条目 = 一次推进、不写值（原版左键即回车，`MainWindow.cs:806-821`）。 */
    @Test
    fun `WAIT 系吃色条目 只推进不写值`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitMappedColor(0x123456L)

        sink.readAnyKey()

        assertEquals(1L, sink.lastConsumedSeq, "色条目被 WAIT 家族吃掉")
        assertFalse(sink.consumeMesSkip(), "取色不带跳过意图")
        assertEquals(0, sink.pendingCount)
    }

    /** 队列已关闭后投喂拒收：同 [QueueInputSink.submit]，不抛、不发号。 */
    @Test
    fun `关闭后投喂拒收 不发号`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.close()

        assertFalse(sink.submitMappedColor(0x123456L))
        assertEquals(0L, sink.lastSubmittedSeq, "关闭时提交不发号")
    }

    /** 等等待方进入等待态（带超时，红要红成断言而不是挂住）。 */
    private fun awaitAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) Thread.sleep(5)
        assertTrue(sink.isAwaiting, "等待方未进入等待态")
    }
}
