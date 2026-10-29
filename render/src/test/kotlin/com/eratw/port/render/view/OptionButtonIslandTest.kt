package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 右侧栏要能枚举 **HTML 浮岛**里的按钮（2026-10-25 补齐的洞）。
 *
 * 现象：某些页面用 `HTML_PRINT_ISLAND` 画面板，按钮长在浮岛的 `<div>` 里：
 * ```
 * <div xpos='560px' ypos='-443px' width='180px' ...><button value='4'>素质编辑</button></div>
 * ```
 * 这类内容不在普通显示行、也不在 `snapshotText()` 里（浮岛是单独一份列表），
 * 于是右侧栏少掉整屏按钮。本用例钉住：**浮岛里的 `<button value=N>` 必须进栏**。
 */
class OptionButtonIslandTest {

    private fun build(islands: List<String>): ConsoleScreen {
        val sink = LineBufferConsoleSink(clientWidth = 120)
        val screen = ConsoleScreen(clientWidth = 120, maxLog = 500, heightLines = 20)
        val feed = ConsoleFeed(sink, screen, null, CellMetrics.DEFAULT)
        sink.printLine("普通一行，不是按钮")
        islands.forEach { sink.addHtmlIsland(it) }
        feed.pump()
        screen.refresh()
        return screen
    }

    @Test
    fun `浮岛里的按钮要进栏`() {
        val screen = build(
            listOf(
                "<div xpos='560px' ypos='-443px' width='180px' height='50px' border='2px' bcolor='#a5a5a5' padding='10px' radius='4px' color='#363636'><button value='4'>素质编辑</button></div>",
                "<div xpos='100px' ypos='-400px' width='180px' height='50px'><button value='5'>能力编辑</button></div>",
            ),
        )
        val values = OptionButtonScanner.scan(screen, maxCount = 64).map { it.value }
        assertTrue(values.contains(4L), "浮岛按钮 value=4 必须进栏；实际 = $values")
        assertTrue(values.contains(5L), "浮岛按钮 value=5 必须进栏；实际 = $values")
    }

    @Test
    fun `浮岛清空后按钮不再出现`() {
        val sink = LineBufferConsoleSink(clientWidth = 120)
        val screen = ConsoleScreen(clientWidth = 120, maxLog = 500, heightLines = 20)
        val feed = ConsoleFeed(sink, screen, null, CellMetrics.DEFAULT)
        sink.addHtmlIsland("<div><button value='4'>素质编辑</button></div>")
        feed.pump()
        screen.refresh()
        assertTrue(OptionButtonScanner.scan(screen).any { it.value == 4L }, "先要有 4")

        sink.clearHtmlIslands()
        feed.force()
        screen.refresh()
        assertTrue(
            OptionButtonScanner.scan(screen).none { it.value == 4L },
            "浮岛清空后 4 不该再出现；实际 = " + OptionButtonScanner.scan(screen).map { it.value },
        )
    }
}
