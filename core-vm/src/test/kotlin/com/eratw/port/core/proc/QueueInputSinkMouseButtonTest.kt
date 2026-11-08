package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueInputSink.submitButtonClick] 的交付面单测（玩家「敬茶/演奏 + 子选单点击无反应」案的 core 半段）。
 *
 * 判据来自 `MinorShift.Emuera.Forms/MainWindow.cs` `mainPicBox_MouseDown` 的
 * `IsWaintingInputWithMouse` 分支（:824-849）：命中按钮时窗口侧先写
 * `RESULTS:1 = selectedString`（:834）与 `RESULT:1 = 1/2/3`（:837/:842/:847），
 * 之后 `PressEnterKey(keySkip, selectedString, changedByMouse: true)` 把按钮值交到
 * `RESULT:0`（`EmueraConsole.cs:1135`）。语料 `QOL_USERCOM.ERB @COM_OPTION` 读
 * `MOUSE_BTN = RESULT:1`，`IF MOUSE_BTN == 1` 才切子选项；旧队列把按钮点击当纯文本，
 * 这一位永远是 0 ⇒ 有黄框但无变化。
 *
 * 形态闸逐条对照原版：只有 `VALUE` + `mouseInput` 的等待吃这两个附加槽；
 * 键盘提交、无鼠标等待、WAIT 系（:806-821 左键只是一次回车）、`INPUTANY`、
 * `INPUTMOUSEKEY`、解析失败与超时收尾都不写。
 *
 * 自包含：不依赖语料环境变量；越过截止点的用例注入假单调时钟（每读一次前进 1 秒）。
 */
class QueueInputSinkMouseButtonTest {

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

    /**
     * 主场景：`INPUT -1, 1` 等待下点了一个值为 2 的子选单按钮。
     * 主值照旧走 `RESULT:0`，同时交付 `RESULT:1 = 1`（左键）与 `RESULTS:1 = "2"`。
     */
    @Test
    fun `带鼠标 INT 等待吃按钮点击 主值与左键位一并交付`() {
        val sink = QueueInputSink(timeSource = clockOf())
        assertTrue(sink.submitButtonClick("2"), "入队应成功")

        val out = sink.waitInput(req(mouseInput = true))

        assertFalse(out.isTimeout)
        assertEquals(2L, out.intValue, "按钮值 → RESULT:0")
        assertTrue(out.writesInt)
        assertFalse(out.writesStr)
        assertEquals(mapOf(1 to 1L), out.resultIntSlots, "左键位 → RESULT:1（MainWindow.cs:847）")
        assertEquals(mapOf(1 to "2"), out.resultStrSlots, "按钮文本 → RESULTS:1（:834）")
        assertEquals(1L, sink.lastConsumedSeq, "按钮条目被等待方吃掉并推进")
        val trail = sink.recentInputTrail()
        assertTrue(trail.any { it == "#1 enq btn=1 len=1" }, "流水要能认出按钮点击，实际=$trail")
        assertTrue(trail.any { it.startsWith("#1 con ") }, "消费侧同号，实际=$trail")
    }

    /**
     * 与语料逐字同形：`INPUT -1, 1` ⇒ `hasDef = true` / `defInt = -1` / `mouseInput = true`。
     * 默认值只在「空文本且不在计时中」时替代（EmueraConsole.cs:1123/:1193），按钮值 "0"
     * 是有效选项（`INRANGE(0, 0, OPTIONNUM)`）⇒ 主值必须是 0 而不是默认值 -1，
     * 且照样附加左键位。
     */
    @Test
    fun `INPUT -1 1 点中选项 0 不被默认值顶掉 且仍附加左键位`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("0")

        val out = sink.waitInput(req(mouseInput = true, hasDef = true, defInt = -1L))

        assertFalse(out.isTimeout)
        assertEquals(0L, out.intValue, "按钮值 0 覆盖默认值 -1")
        assertEquals(mapOf(1 to 1L), out.resultIntSlots)
        assertEquals(mapOf(1 to "0"), out.resultStrSlots)
    }

    /**
     * 两条投喂口只差「来源标记」这一维：`mesSkip` 不得在按钮口被悄悄吞掉
     * （`PressEnterKey` 的 `keySkip`，EmueraConsole.cs:1371-1373；原版左键命中传 false，
     * 但宿主把跳过意图连同按钮来源一起投来时仍要照常落到 sem 层）。
     */
    @Test
    fun `按钮点击携带跳过意图时不丢 mesSkip`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("2", 1, true)

        val out = sink.waitInput(req(mouseInput = true))

        assertEquals(2L, out.intValue)
        assertEquals(mapOf(1 to 1L), out.resultIntSlots)
        assertTrue(sink.consumeMesSkip(), "跳过意图要照常交付给 sem 层")
    }

    /** STR 通道同样附带按钮位；`RESULTS:1` 记的是按钮 value 原文（多字节文本也原样）。 */
    @Test
    fun `带鼠标 STR 等待吃按钮点击 同样附带按钮位与原文`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("演奏（子选单）")

        val out = sink.waitInput(req(type = InputRequest.InputType.STR, mouseInput = true))

        assertEquals("演奏（子选单）", out.strValue)
        assertFalse(out.writesInt)
        assertTrue(out.writesStr)
        assertEquals(mapOf(1 to 1L), out.resultIntSlots)
        assertEquals(mapOf(1 to "演奏（子选单）"), out.resultStrSlots, "原版 InputString(1, selectedString) 用原文")
    }

    /** 右键 / 中键分别写 2 / 3（`MainWindow.cs:842`/`:837`）；Android 触摸恒用左键 1。 */
    @Test
    fun `右键与中键分别写 2 与 3`() {
        for ((button, expected) in listOf(2 to 2L, 3 to 3L)) {
            val sink = QueueInputSink(timeSource = clockOf())
            sink.submitButtonClick("7", button)

            val out = sink.waitInput(req(mouseInput = true))

            assertEquals(7L, out.intValue)
            assertEquals(expected, out.resultIntSlots[1], "鼠标按钮位 = $button")
            assertEquals("7", out.resultStrSlots[1])
        }
    }

    /**
     * 形态闸①：`mouseInput == false` 的输入等待不吃附加槽
     * （原版 `:824`/`:851` 都要求 `IsWaintingInputWithMouse`）。
     */
    @Test
    fun `无鼠标的 INPUT 吃按钮点击 不附加槽位`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("2")

        val out = sink.waitInput(req(mouseInput = false))

        assertEquals(2L, out.intValue, "按钮值仍照常交付")
        assertTrue(out.resultIntSlots.isEmpty(), "不得把非鼠标等待误标成鼠标点击")
        assertTrue(out.resultStrSlots.isEmpty())
    }

    /** 形态闸②：键盘提交即使落在带鼠标的等待上也不写按钮位（原版键盘路径无 `InputInteger(1L, …)`）。 */
    @Test
    fun `键盘提交落在带鼠标等待上 不附加槽位`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submit("2")

        val out = sink.waitInput(req(mouseInput = true))

        assertEquals(2L, out.intValue)
        assertTrue(out.resultIntSlots.isEmpty())
        assertTrue(out.resultStrSlots.isEmpty())
        assertTrue(sink.recentInputTrail().any { it == "#1 enq len=1 skip=false" }, "键盘提交走普通 enq 流水")
    }

    /** 形态闸③：WAIT 系左键只是一次回车（`:806-821`），推进但不写任何值、也不写按钮位。 */
    @Test
    fun `WAIT 系吃按钮点击 只推进不写值也不附加槽位`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("2")

        val out = sink.waitInput(req(form = InputRequest.RequestForm.ENTER_KEY, mouseInput = true))

        assertFalse(out.writesInt)
        assertFalse(out.writesStr)
        assertTrue(out.resultIntSlots.isEmpty())
        assertTrue(out.resultStrSlots.isEmpty())
        assertEquals(1L, sink.lastConsumedSeq)
    }

    /** WAIT 家族的 [QueueInputSink.readAnyKey] 也照常吃掉按钮条目，不因新维度卡住。 */
    @Test
    fun `readAnyKey 也照常吃掉按钮点击条目`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("5")

        sink.readAnyKey()

        assertEquals(1L, sink.lastConsumedSeq)
        assertFalse(sink.consumeMesSkip(), "按钮点击不带跳过意图（左键 keySkip=false，:848）")
        assertEquals(0, sink.pendingCount)
    }

    /** 形态闸④：`INPUTANY` 与 `INPUTMOUSEKEY` 都不是带鼠标的取值等待，不附加槽位。 */
    @Test
    fun `ANY_VALUE 与 PRIMITIVE_MOUSE_KEY 不附加槽位`() {
        val anyValue = QueueInputSink(timeSource = clockOf())
        anyValue.submitButtonClick("2")
        val outAny = anyValue.waitInput(req(form = InputRequest.RequestForm.ANY_VALUE))
        assertEquals(2L, outAny.intValue)
        assertTrue(outAny.resultIntSlots.isEmpty())

        val primitive = QueueInputSink(timeSource = clockOf())
        primitive.submitButtonClick("2")
        val outPrimitive = primitive.waitInput(req(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))
        assertFalse(outPrimitive.writesInt)
        assertTrue(outPrimitive.resultIntSlots.isEmpty())
    }

    /**
     * 按钮值解析失败（`EmueraConsole.cs:1125-1128`）：这次提交被丢弃、不交付任何槽位，
     * 等待靠超时收尾。`RESULT:1` 不能被半途那次点击写脏。
     */
    @Test
    fun `按钮值解析失败被丢弃 不落槽位并由超时收尾`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.submitButtonClick("not-a-number")

        val out = sink.waitInput(req(mouseInput = true, hasDef = true, defInt = 9L, timeLimit = 100))

        assertTrue(out.isTimeout)
        assertEquals(9L, out.intValue, "超时交付默认值")
        assertTrue(out.resultIntSlots.isEmpty(), "被丢弃的点击不许写 RESULT:1")
        assertTrue(out.resultStrSlots.isEmpty())
        assertEquals(1L, sink.dropParseCount, "解析失败单独计数")
        assertEquals(0, sink.pendingCount)
    }

    /** 没有任何提交的纯超时收尾（`endTimer` :1068）同样不带附加槽。 */
    @Test
    fun `纯超时收尾不带附加槽`() {
        val sink = QueueInputSink(timeSource = clockOf())

        val out = sink.waitInput(req(mouseInput = true, hasDef = true, defInt = 5L, timeLimit = 100))

        assertTrue(out.isTimeout)
        assertEquals(5L, out.intValue)
        assertTrue(out.resultIntSlots.isEmpty())
        assertTrue(out.resultStrSlots.isEmpty())
        assertTrue(sink.recentInputTrail().any { it.startsWith("tmo ") }, "超时流水不占 seq")
    }

    /** 队列已关闭后投喂拒收：同 [QueueInputSink.submit]，不抛、不发号。 */
    @Test
    fun `关闭后投喂拒收 不发号`() {
        val sink = QueueInputSink(timeSource = clockOf())
        sink.close()

        assertFalse(sink.submitButtonClick("2"))
        assertEquals(0L, sink.lastSubmittedSeq)
    }
}
