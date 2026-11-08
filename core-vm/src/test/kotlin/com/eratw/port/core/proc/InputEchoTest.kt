package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueInputSink] 键入回显（ECHO-INPUT）定向单测 —— 钉的是原版
 * `doInputToEmueraProgram` 末尾 `Print(str)` + `PrintFlush(force:false)`
 * （`EmueraConsole.cs:1280-1282`）的形态矩阵与「这行参与 LINECOUNT、可被 deleteLine 删」的
 * 引擎清理口径（`Process.cs:2194`/`:2498`/`:2648` 的删除对象就是它）。
 *
 * 断言基准（行号均为 reference/emuera-cs 的 C# 行）：
 * - 每次取值输入**交付**都回显一行普通行（非临时、无按钮），空文本经 `Print` 空串早退
 *   （:3167-3172）不回显；
 * - 回显文本 = 交付消费点上的生效 `str`：文本提交经 `\e` 剥离（:1435-1438）与 `OneInput`
 *   截断（:1440-1443）；取值形态「空 + 默认值 + 非计时中」替换成默认值串（:1123/:1193）；
 * - 超时交付 = `endTimer → RunEmueraProgram("")`（:1068）的空文本：有默认值的取值等待
 *   回显默认值串（IsRunningTimer 已假，:375-377 + :1120-1123/:1190-1194），其余不回显；
 * - 解析失败（`return false`，:1125-1128）、VOID（:1417-1420 入口早退）、遮罩取色
 *   （`MainWindow.cs:795`/`:828` 变量直写）不回显。
 *
 * 驱动方式沿用 [QueueInputSinkTest]：提交先入队、再同步 [QueueInputSink.waitInput]；
 * 限时用例注「每读一次 +1 秒」的自走假时钟（进入等待后的第一次截止判定必然越过 deadline，
 * 全程不占墙钟），死等路径的后半程用后台线程等进等待态后再提交。
 * console 侧用生产装配同一条接线：`onEchoInput → console.printLine`
 * （`GameSession`/`BootPlayChain` 各有一行同形装配）。
 */
class InputEchoTest {

    /**
     * console + sink + 同一条生产装配线的装置。[timeSource] 注假时钟时每读一次 +1 秒
     * （与 `QueueInputSinkTest.限时整数无默认值` 同款），保证限时判定一次到位、不占墙钟。
     */
    private class Rig(timeSource: () -> Long = { System.nanoTime() }) {
        val console = LineBufferConsoleSink()
        val sink = QueueInputSink(timeSource = timeSource)
        /** 回调收到的回显文本流水（与 console 侧并列记录，便于断言「回调本身被调了几次」。） */
        val echoes = ArrayList<String>()

        init {
            sink.onEchoInput = { text ->
                echoes += text
                console.printLine(text)
            }
        }

        fun lineTexts(): List<String> =
            console.displayedLines.map { line -> line.segments.joinToString("") { it.text } }

        fun lastLine(): LineBufferConsoleSink.DisplayLine = console.displayedLines.last()
    }

    /** 等等待方进入等待态（[QueueInputSink.isAwaiting] 置起），带超时不挂测试。 */
    private fun awaitUntilAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) {
            Thread.sleep(5)
        }
        assertTrue(sink.isAwaiting, "等待方未进入等待态（提交前必须已在等待里）")
    }

    private fun intRequest(
        hasDef: Boolean = false,
        defInt: Long = 0L,
        timeLimit: Long = -1L,
        oneInput: Boolean = false,
        mouseInput: Boolean = false,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
    ): InputRequest = InputRequest(
        type = InputRequest.InputType.INT,
        form = form,
        oneInput = oneInput,
        hasDef = hasDef,
        defInt = defInt,
        timeLimit = timeLimit,
        mouseInput = mouseInput,
    )

    // ------------------------------------------------------------------
    // 文本提交：普通行、非临时、无按钮
    // ------------------------------------------------------------------

    @Test
    fun `取值整数交付回显一行普通非临时行`() {
        val rig = Rig()
        rig.sink.submit("42")
        val outcome = rig.sink.waitInput(intRequest())

        assertEquals(42L, outcome.intValue)
        assertEquals(listOf("42"), rig.echoes, "回显文本 = 交付生效文本（:1280 的 str）")
        assertEquals(1L, rig.console.lineCount, "回显行计入 LINECOUNT")
        val line = rig.lastLine()
        assertEquals("42", line.segments.joinToString("") { it.text })
        assertFalse(line.isTemporary, "回显是普通行，不走临时行通道（Print，非 PrintTemporaryLine）")
        assertTrue(line.buttons.isEmpty(), "回显无按钮（Print 的 force_button=false，:3167-3172）")
        assertTrue(line.isLogicalLine, "回显行结掉逻辑行（:1282 PrintFlush 出的是完整行）")
    }

    @Test
    fun `未接线零行为`() {
        val rig = Rig()
        rig.sink.onEchoInput = null
        rig.sink.submit("42")
        rig.sink.waitInput(intRequest())
        assertEquals(0L, rig.console.lineCount, "onEchoInput = null ⇒ 零行为（宿主没接就什么都不发生）")
    }

    // ------------------------------------------------------------------
    // 不回显的交付/不交付
    // ------------------------------------------------------------------

    @Test
    fun `解析失败不回显 下一条照常回显`() {
        val rig = Rig()
        rig.sink.submit("abc")
        rig.sink.submit("7")
        assertEquals(7L, rig.sink.waitInput(intRequest()).intValue)
        assertEquals(listOf("7"), rig.echoes, "解析失败在 :1125-1128 return false，早退在 :1280 之前")
        assertEquals(1, rig.console.lineCount)
    }

    @Test
    fun `WAIT空回车不回显`() {
        val rig = Rig()
        rig.sink.submit("")
        rig.sink.readAnyKey()
        assertTrue(rig.echoes.isEmpty(), "空文本经 Print 空串早退（:3167-3172）不回显")
        assertEquals(0, rig.console.lineCount)
    }

    @Test
    fun `遮罩取色不回显`() {
        val rig = Rig()
        rig.sink.submitMappedColor(0x112233)
        val outcome = rig.sink.waitInput(intRequest(mouseInput = true))
        assertEquals(0x112233L, outcome.intValue, "色条目照常交付")
        assertTrue(rig.echoes.isEmpty(), "取色是变量直写（MainWindow.cs:795/:828），不经文本通道")
        assertEquals(0, rig.console.lineCount)
    }

    @Test
    fun `VOID时限交付不回显`() {
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        rig.sink.submit("x") // VOID 的键盘提交在 PressEnterKey 入口早退（:1417-1420）
        val request = InputRequest(
            type = InputRequest.InputType.INT,
            form = InputRequest.RequestForm.VOID,
            timeLimit = 1_000L,
        )
        val outcome = rig.sink.waitInput(request)
        assertTrue(outcome.isTimeout)
        assertTrue(rig.echoes.isEmpty(), "VOID 无文本通道（:1417-1420 早退 ⇒ 走不到 :1280）")
        assertEquals(0, rig.console.lineCount)
    }

    // ------------------------------------------------------------------
    // 默认值替换与超时交付
    // ------------------------------------------------------------------

    @Test
    fun `空提交带默认值整数回显默认值串`() {
        val rig = Rig()
        rig.sink.submit("")
        val outcome = rig.sink.waitInput(intRequest(hasDef = true, defInt = 77))
        assertEquals(77L, outcome.intValue)
        assertEquals(listOf("77"), rig.echoes, ":1123 str = result.ToString() ⇒ 回显默认值串而非空串")
        assertEquals(listOf("77"), rig.lineTexts())
    }

    @Test
    fun `空提交带默认值字符串回显默认值串`() {
        val rig = Rig()
        rig.sink.submit("")
        rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串"),
        )
        assertEquals(listOf("默认串"), rig.echoes, ":1193 str = DefStrValue")
    }

    @Test
    fun `限时等待计时中空提交不吃默认值`() {
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        rig.sink.submit("") // 计时中的空串：INT 解析失败（:1120 的 !IsRunningTimer 不成立）
        rig.sink.submit("9")
        assertEquals(9L, rig.sink.waitInput(intRequest(timeLimit = 10_000L)).intValue)
        assertEquals(listOf("9"), rig.echoes, "丢弃的那条不回显，交付的那条回显原文")
        assertEquals(1, rig.console.lineCount)
    }

    @Test
    fun `超时交付回显默认值整数`() {
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        val outcome = rig.sink.waitInput(intRequest(hasDef = true, defInt = 3, timeLimit = 1_000L))
        assertEquals(3L, outcome.intValue)
        assertTrue(outcome.isTimeout)
        assertEquals(listOf("3"), rig.echoes, "endTimer → RunEmueraProgram(\"\")（:1068）的空文本吃默认值后回显")
        assertEquals(1, rig.console.lineCount)
    }

    @Test
    fun `超时交付回显默认值字符串`() {
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        val outcome = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "待机", timeLimit = 1_000L),
        )
        assertEquals("待机", outcome.strValue)
        assertTrue(outcome.isTimeout)
        assertEquals(listOf("待机"), rig.echoes)
    }

    @Test
    fun `超时ANY_VALUE不回显`() {
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        val any = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, form = InputRequest.RequestForm.ANY_VALUE, timeLimit = 1_000L),
        )
        assertTrue(any.isTimeout)
        assertTrue(rig.echoes.isEmpty(), "超时空串直落 :1280 ⇒ Print(\"\") 早退")
        assertEquals(0, rig.console.lineCount)
    }

    @Test
    fun `死等路径超时后到货的提交照常回显`() {
        // INT 无默认值 ⇒ 超时不推进（:1119-1128）：后台线程开等，主线程等进等待态再提交。
        val clock = AtomicLong(0L)
        val rig = Rig { clock.addAndGet(1_000_000_000L) }
        val ref = AtomicReference<Pair<Long, Boolean>?>()
        val t = thread(name = "echo-int-no-def-deadwait") {
            val outcome = rig.sink.waitInput(intRequest(timeLimit = 100))
            ref.set(outcome.intValue to outcome.isTimeout)
        }
        awaitUntilAwaiting(rig.sink)
        rig.sink.submitInt(7)
        t.join(2_000)
        assertFalse(t.isAlive, "真提交应当推进等待")
        assertEquals(7L to true, ref.get(), "粘滞：一次限时等待内过点后靠提交推进也带 isTimeout")
        assertEquals(listOf("7"), rig.echoes, "交付即回显，与推进来源无关")
        assertEquals(1L, rig.console.lineCount)
    }

    // ------------------------------------------------------------------
    // 非取值形态与 ANY_VALUE：原文回显
    // ------------------------------------------------------------------

    @Test
    fun `ANY_VALUE原文回显不分整数串`() {
        val rig = Rig()
        rig.sink.submit("42")
        val int = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.INT, form = InputRequest.RequestForm.ANY_VALUE),
        )
        assertTrue(int.writesInt, ":1256-1274 解析得动写 RESULT")
        assertFalse(int.writesStr)
        rig.sink.submit("甲")
        val str = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, form = InputRequest.RequestForm.ANY_VALUE),
        )
        assertEquals("甲", str.strValue)
        assertFalse(str.writesInt)
        assertEquals(listOf("42", "甲"), rig.echoes, "两个分支 str 都原样进 :1280")
        assertEquals(2, rig.console.lineCount)
    }

    @Test
    fun `WAIT回车回显并剥离跳过标记`() {
        val rig = Rig()
        rig.sink.submit("跳过\\e") // ESCAPE_SKIP = "\\e"（companion 私有，测试用同形字面量）
        rig.sink.readAnyKey()
        assertTrue(rig.sink.consumeMesSkip(), "\\e 命中 ⇒ MesSkip（:1435-1438）")
        assertEquals(listOf("跳过"), rig.echoes, "回显文本是剥离后的生效文本")
        assertEquals(listOf("跳过"), rig.lineTexts())
    }

    @Test
    fun `INPUTMOUSEKEY文本提交回显`() {
        val rig = Rig()
        rig.sink.submit("5")
        val outcome = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.INT, form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY),
        )
        assertFalse(outcome.writesInt, "switch 无 case ⇒ 不写槽（:1276-1287）")
        assertFalse(outcome.writesStr)
        assertEquals(listOf("5"), rig.echoes, "str 直落 :1280 ⇒ 原文回显")
        assertEquals(1, rig.console.lineCount)
    }

    @Test
    fun `oneInput截断后的生效文本回显`() {
        val rig = Rig()
        rig.sink.submit("AB")
        val outcome = rig.sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, oneInput = true),
        )
        assertEquals("A", outcome.strValue, ":1440-1443 截断")
        assertEquals(listOf("A"), rig.echoes, "回显的是截断后的 str（:1280 的实参）")
    }

    // ------------------------------------------------------------------
    // 与 console 的交互：PRINTC 残留行合并 + deleteLine 清理口径
    // ------------------------------------------------------------------

    @Test
    fun `回显与未结的PRINTC残留行合并`() {
        val rig = Rig()
        val request = intRequest()
        rig.console.print("名字:", lineEnd = false) // 无换行 ⇒ 打印缓冲里挂着半句（PRINTN/PRINTC 的形态）
        rig.console.noteInputWait(request) // sem 漏斗的等待入口：flushPending（:1107 的等价物）
        rig.sink.submit("777") // 整数请求 ⇒ 必须能交付（解析失败且队列已空会死等）
        rig.sink.waitInput(request)

        assertEquals(listOf("名字:777"), rig.lineTexts(), "回显行与残留半句合并成一行（原版 PRINTC 提示 + INPUT 的屏面）")
        assertEquals(1L, rig.console.lineCount)
        assertTrue(rig.lastLine().isLogicalLine, "合并后的行结掉逻辑行")
    }

    @Test
    fun `deleteLine删的是回显行不是真行`() {
        val rig = Rig()
        rig.console.printLine("菜单") // 屏上真行（按钮行在真机就是这一类）
        val request = intRequest()
        rig.sink.submit("abc") // 解析失败：丢弃
        rig.sink.submit("42")
        rig.sink.waitInput(request)
        assertEquals(2L, rig.console.lineCount, "真行 + 回显行")

        rig.console.deleteLine(1) // Process.cs:2498 的同形调用
        assertEquals(1L, rig.console.lineCount, "deleteLine(1) 删掉回显行")
        assertEquals(listOf("菜单"), rig.lineTexts(), "屏上真行完好 —— 无回显时这一刀会误删它（2026-11-04 真机实证）")
    }

    @Test
    fun `临时行语义不被回显污染`() {
        val rig = Rig()
        rig.sink.submit("42")
        rig.sink.waitInput(intRequest())
        assertFalse(rig.lastLine().isTemporary, "回显行是普通行")
        rig.console.printTemporaryLine("提示") // 引擎清理路径画的临时行（:2499 同形）
        assertTrue(rig.lastLine().isTemporary, "printTemporaryLine 的临时语义独立于回显通道")
        assertEquals(listOf("42", "提示"), rig.lineTexts())
    }
}
