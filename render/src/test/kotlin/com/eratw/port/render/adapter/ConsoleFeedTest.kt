package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ConsoleFeed] 的桥接判据（CONSOLE-FEED）。
 *
 * 输入是真实的 [LineBufferConsoleSink]（`:core-vm` 的生产 sink）与真实的 [ConsoleScreen]，
 * 不 mock：脏区、折行、PRINTN 并入全部走既有的 `ConsoleLogSync` / `DisplayList`。
 * 期望值来自 C# 的整屏快照形态与「没有新行就不重绘」：
 * * 整屏快照 = `displayLineList`（EmueraConsole.cs:238）；
 * * PRINTN 并入末行、绝对行号不变（EmueraConsole.cs:2947-2970）；
 * * CLEARLINE 从尾部按逻辑行删（EmueraConsole.cs:2999-3051）；
 * * 幂等对应 `RefreshStrings` 的水位早退（EmueraConsole.cs:1758）与画完才写水位（:1942-1949）。
 *
 * 宽度取 20 半角格、高 5 行，所有夹具都远小于一屏，脏区行号不会被视口滚动污染。
 */
class ConsoleFeedTest {

    private fun consoleSink(width: Int = 20) = LineBufferConsoleSink(clientWidth = width)

    private fun consoleScreen(width: Int = 20, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    /** 首次 pump：把 sink 当前整屏快照交出去，屏幕文本逐行一致，脏区覆盖整屏。 */
    @Test
    fun `首次 pump 交整屏快照且屏幕文本与 sink 逐行一致`() {
        val sink = consoleSink()
        sink.printLine("abc")
        sink.printLine("あい")
        val feed = ConsoleFeed(sink, consoleScreen())

        assertEquals(2L, sink.revision, "两次 printLine 各产生一条显示行")
        val rects = feed.pump()

        assertEquals(listOf("abc", "あい"), feed.screen.snapshotText())
        assertEquals(1, rects.size, "首次提交把整屏标脏（DisplayList 从空列表起步）")
        assertEquals(0, rects.single().rowStart)
        assertEquals(2, rects.single().rowEnd)

        assertEquals(2L, sink.revision, "pump 只读 sink，不得反过来改它")
    }

    /** 同一 revision 再 pump：零脏区、屏幕不变（幂等，对应 EmueraConsole.cs:1758 的早退）。 */
    @Test
    fun `重复 pump 脏区为空且屏幕不变`() {
        val sink = consoleSink()
        sink.printLine("abc")
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()

        assertTrue(feed.pump().isEmpty())
        assertTrue(feed.pump().isEmpty())
        assertEquals(listOf("abc"), feed.screen.snapshotText())
    }

    /** 追加一行后只脏新行（增量行的脏区从旧行数开始）。 */
    @Test
    fun `追加行后只脏新增行`() {
        val sink = consoleSink()
        sink.printLine("one")
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()

        sink.printLine("two")
        val rects = feed.pump()

        assertEquals(1, rects.size)
        assertEquals(1, rects.single().rowStart, "旧行 0 未变，不该进脏区")
        assertEquals(2, rects.single().rowEnd)
        assertEquals(listOf("one", "two"), feed.screen.snapshotText())
    }

    /**
     * 清屏：本 sink 没有整屏 clear，CLEARLINE 删光显示行是屏幕侧等价的「clear」路径
     * （`deleteLine` 从尾部按逻辑行删，EmueraConsole.cs:2999-3051）。删光后 revision 变化、
     * 屏幕文本为空，脏区非空（旧行要被擦掉）。
     */
    @Test
    fun `CLEARLINE 删光后 revision 变化且屏幕为空`() {
        val sink = consoleSink()
        sink.printLine("a")
        sink.printLine("b")
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()
        val before = sink.revision

        sink.deleteLine(2)

        assertTrue(sink.revision > before, "删除显示行必须让 revision 前进")
        val rects = feed.pump()
        assertTrue(rects.isNotEmpty(), "被删掉的旧行要进脏区才能擦屏")
        assertTrue(feed.screen.snapshotText().isEmpty())
    }

    /** PRINTN 合并：revision 变化，屏上仍是一条逻辑行、文本不断行（EmueraConsole.cs:2947-2970）。 */
    @Test
    fun `PRINTN 合并后 revision 变化且文本不断行`() {
        val sink = consoleSink()
        sink.print("abc", false)
        sink.newLine()
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()
        assertEquals(listOf("abc"), feed.screen.snapshotText())
        val before = sink.revision

        sink.print("def", true)
        sink.newLine()

        assertTrue(sink.revision > before)
        assertEquals(1, sink.displayLineCount, "PRINTN 续行并入末行，显示行数不增")

        val rects = feed.pump()
        assertEquals(1, rects.size)
        assertEquals(0, rects.single().rowStart, "被改写的只有末行 0")
        assertEquals(1, rects.single().rowEnd)
        assertEquals(listOf("abcdef"), feed.screen.snapshotText(), "一条逻辑行、不断行")
    }

    /** 构造期宽度校验：折行宽度与显示宽度必须同源，不一致直接抛。 */
    @Test
    fun `sink 与 screen 宽度不一致时构造抛错`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConsoleFeed(
                LineBufferConsoleSink(clientWidth = 18),
                ConsoleScreen(clientWidth = 19, maxLog = 100, heightLines = 4),
            )
        }
        assertTrue(error.message.orEmpty().contains("clientWidth"), "错误信息要带上宽度口径：${error.message}")
    }

    /** 只进打印缓冲、只改样式/对齐/重绘时 lines() 不变 ⇒ revision 不变、pump 空；flush 后才可见。 */
    @Test
    fun `未 flush 的打印缓冲与样式改动不改变 revision`() {
        val sink = consoleSink()
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()
        val before = sink.revision

        sink.print("pending")
        sink.setForeColor(255, 0, 0)
        sink.setAlignment(Alignment.CENTER)
        sink.setRedraw(1)

        assertEquals(before, sink.revision, "可见显示行没有变化")
        assertTrue(feed.pump().isEmpty())
        assertTrue(feed.screen.snapshotText().isEmpty())

        sink.newLine()
        assertTrue(sink.revision > before, "flush 产行后才算可见变化")
        feed.pump()
        assertEquals(listOf("pending"), feed.screen.snapshotText())
    }

    /** REUSELASTLINE 临时行：增删都推动 revision（EmueraConsole.cs:2891-2893、:3053-3056）。 */
    @Test
    fun `临时行的增删推动 revision 且屏幕跟随替换`() {
        val sink = consoleSink()
        sink.printLine("base")
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()
        val beforeTemp = sink.revision

        sink.printTemporaryLine("tmp")
        assertTrue(sink.revision > beforeTemp, "追加临时行是可见变化")
        feed.pump()
        assertEquals(listOf("base", "tmp"), feed.screen.snapshotText())

        sink.printLine("next")
        feed.pump()
        assertEquals(listOf("base", "next"), feed.screen.snapshotText(), "下一次正常输出先删临时行")
    }

    /** force 忽略 revision：屏幕被别的路径清掉后能把当前快照补回来（首帧/尺寸变化路径）。 */
    @Test
    fun `force 忽略 revision 重交快照`() {
        val sink = consoleSink()
        sink.printLine("abc")
        val feed = ConsoleFeed(sink, consoleScreen())
        feed.pump()

        feed.screen.clear()
        assertTrue(feed.screen.snapshotText().isEmpty())
        assertTrue(feed.pump().isEmpty(), "revision 未变时 pump 不补（幂等）")

        val rects = feed.force()
        assertTrue(rects.isNotEmpty())
        assertEquals(listOf("abc"), feed.screen.snapshotText())
    }

    /** 脏矩形是半开行区间：首行追加的 rect 与 DisplayList 口径一致（行号 0 基）。 */
    @Test
    fun `首行脏区行区间是半开的`() {
        val sink = consoleSink()
        val feed = ConsoleFeed(sink, consoleScreen())
        sink.printLine("x")
        val rects = feed.pump()
        val rect: DirtyRect = rects.single()
        assertEquals(0, rect.rowStart)
        assertEquals(1, rect.rowEnd)
    }
}
