package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 右侧栏「原始 HTML 兜底」钉子（2026-10-24 真机证据）。
 *
 * 存档页真实 markup（`dev "lines 80"` 实测）：
 * - 槽位 0：`<button value='0'><shape type='space' param='63px'><font color='#777777'>[   0] - ----</font></button>`
 * - 翻页：`value='9996'..'9999'`；EXIT：**`value='10000'`**
 * 真机 `chips` 曾只列出 44 条、缺 `value=0`。本用例断言：**只要页面上印了 `<button value=N>`，
 * 右侧栏就必须能列出 N**（判据 = 原版「有按钮对象即可点」）。
 */
class OptionButtonHtmlFallbackTest {

    private fun build(html: List<String>): ConsoleScreen {
        val sink = LineBufferConsoleSink(clientWidth = 120)
        val screen = ConsoleScreen(clientWidth = 120, maxLog = 500, heightLines = 20)
        val feed = ConsoleFeed(sink, screen, null, CellMetrics.DEFAULT)
        html.forEach { sink.printHtml(it, false) }
        feed.pump()
        screen.refresh()
        return screen
    }

    @Test
    fun `带 shape 前缀的槽位0与EXIT都要进栏`() {
        val screen = build(
            listOf(
                "<p align='center'>存档界面</p>",
                "<button value='0'><shape type='space' param='63px'><font color='#777777'>[   0] - ----</font></button>",
                "<button value='1'>[   1] - 自动存档</button>",
                "<p align='center'><button value='9997'>[ > ]</button><button value='9999'>[ >>> ]</button></p>",
                "<p align='center'><button value='10000'>[ EXIT ]</button></p>",
            ),
        )
        val values = OptionButtonScanner.scan(screen, maxCount = 64).map { it.value }
        assertTrue(values.contains(0L), "槽位 0 必须在；实际 = $values")
        assertTrue(values.contains(1L), "槽位 1 必须在；实际 = $values")
        assertTrue(values.contains(9997L), "翻页按钮必须在；实际 = $values")
        assertTrue(values.contains(10000L), "EXIT 必须在；实际 = $values")
    }

    @Test
    fun `同一 value 有多处时取最靠下的那一处`() {
        val screen = build(
            listOf(
                "<button value='0'>[0] 开始游戏（旧页）</button>",
                "<p align='center'>转折</p>",
                "<button value='0'>[   0] - 新页槽位</button>",
            ),
        )
        val zero = OptionButtonScanner.scan(screen, maxCount = 64).filter { it.value == 0L }
        assertTrue(zero.isNotEmpty(), "value=0 要存在")
        assertTrue(zero.size == 1, "同一 value 只保留最靠下的一处；实际 = $zero")
    }
}
