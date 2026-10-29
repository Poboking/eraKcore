package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PressEnterKey` 段内跳过标记（字面反斜杠 + `e`）的回归闸（`EmueraConsole.cs:1435-1439`）。
 *
 * C# 在每段输入进 `doInputToEmueraProgram` 之前先做：
 * `if (text.Contains("\\e")) { text = text.Replace("\\e", ""); MesSkip = true; }`
 * —— 即「剥离全部字面反斜杠+e（**不是** ESC 控制符 U+001B）并置 MesSkip」。
 * 剥离发生在 OneInput 截断（:1440）与取值之前；Void 形态在 :1417 早已早退，
 * 因此 Void 期间到达的提交**不**走这段（不剥离、不置位）。
 *
 * 自包含：不依赖语料；限时分支用注入的假单调时钟零墙钟推进。
 * 测试里用 Kotlin raw string `\e` 表达字面两字符（raw string 不处理转义）。
 */
class QueueInputEscapeSkipTest {

    private fun intRequest(oneInput: Boolean = false): InputRequest =
        InputRequest(type = InputRequest.InputType.INT, oneInput = oneInput)

    private fun strRequest(): InputRequest = InputRequest(type = InputRequest.InputType.STR)

    /** 假单调时钟：每读一次前进 [stepNanos]，进入等待后第一次判定即越过 deadline。 */
    private fun clockOf(stepNanos: Long): () -> Long {
        val now = AtomicLong(0L)
        return { now.addAndGet(stepNanos) }
    }

    @Test
    fun `STR 取值剥离全部跳过标记且置标志`() {
        val sink = QueueInputSink()
        sink.submit("""abc\e""")
        assertEquals("abc", sink.waitInput(strRequest()).strValue, "字面反斜杠+e 被剥离（:1437）")
        assertTrue(sink.consumeMesSkip(), "命中跳过标记即 MesSkip 置真（:1438）")
        assertFalse(sink.consumeMesSkip(), "读过即清")
    }

    @Test
    fun `多处跳过标记一次剥净`() {
        val sink = QueueInputSink()
        sink.submit("""a\eb\ec""")
        assertEquals("abc", sink.waitInput(strRequest()).strValue, "Replace 移除所有标记（:1437）")
        assertTrue(sink.consumeMesSkip())
    }

    @Test
    fun `INT 取值剥离标记后按数字解析`() {
        val sink = QueueInputSink()
        sink.submit("""7\e""")
        assertEquals(7L, sink.waitInput(intRequest()).intValue, "剥离后才是可解析数字")
        assertTrue(sink.consumeMesSkip())
    }

    @Test
    fun `剥离先于 OneInput 截断`() {
        // 若顺序颠倒（先截断「\e1」→「\」），解析必失败、等待无法返回。
        val sink = QueueInputSink()
        sink.submit("""\e1""")
        assertEquals(1L, sink.waitInput(intRequest(oneInput = true)).intValue, "先剥离再截断（:1435-1443 的顺序）")
        assertTrue(sink.consumeMesSkip())
    }

    @Test
    fun `readAnyKey 也识别标记并置跳过标志`() {
        val sink = QueueInputSink()
        sink.submit("""\e""")
        sink.readAnyKey()
        assertTrue(sink.consumeMesSkip(), "WAIT 家族同样在段处理里命中标记（:1435-1438）")
    }

    @Test
    fun `无标记的普通提交不置跳过标志`() {
        val sink = QueueInputSink()
        sink.submit("plain")
        assertEquals("plain", sink.waitInput(strRequest()).strValue)
        assertFalse(sink.consumeMesSkip(), "不含标记且 keySkip=false ⇒ 不置位（回归）")
    }

    @Test
    fun `标记与 keySkip 取或`() {
        val sink = QueueInputSink()
        sink.submit("x", mesSkip = true)
        sink.waitInput(strRequest())
        assertTrue(sink.consumeMesSkip(), "keySkip 与标记同为 MesSkip 的来源（入口 :1373 + :1438）")
    }

    @Test
    fun `VOID 丢弃的提交不做剥离也不置标志`() {
        // Void 在 :1417 早退，早于 :1435 的标记处理；那次回车根本不进程序。
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("""\e""")
        val out = sink.waitInput(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VOID,
                timeLimit = 100,
            ),
        )
        assertTrue(out.isTimeout)
        assertEquals(0, sink.pendingCount, "Void 丢弃这条提交（:1417-1420）")
        assertFalse(sink.consumeMesSkip(), "Void 早退在标记处理之前 ⇒ 不置跳过（:1417 vs :1435）")
    }
}