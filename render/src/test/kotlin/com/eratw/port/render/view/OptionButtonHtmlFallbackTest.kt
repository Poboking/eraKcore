package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * R-F8（2026-10-28，W4 车道）：兜底路（`scanHtmlButtons`）原先只认数字 value
     * （`value='?(\d+)'?`），字符串按钮（`value='exit'`）在命中层为空时整条漏掉。
     * 原版 `value` 是字符串域：字面量整串先收下（`HtmlManager.cs:1511` 的 `text7 = text8`），
     * 闭标签才分岔 `long.TryParse`（`:1543-1545`：`ButtonValueInt` + `ButtonValueStr` 恒存原文），
     * 命中层 `adapter/HtmlRunFlatten.kt` 同型（`toLongOrNull() ?: v`）。
     * 放宽成 `([^'>]+)` 后按同一形态分流：纯数字折 Long、其余落字符串通道
     * （`scan` 里 `stringValues` 表，与命中层字符串按钮同一去重键）。
     */
    @Test
    fun `兜底路吃到字符串 value 且数字支口径不变`() {
        val found = OptionButtonScanner.scanHtmlButtons(
            listOf(
                "<button value='exit'>[ EXIT ]</button>",
                "<p align='center'><button value='0'><shape type='space' param='63px'>[   0] - ----</button></p>",
                "<button value=\"戻る\">戻る</button>",
            ),
        )
        val exit = found.single { it.value == "exit" }
        assertEquals("[ EXIT ]", exit.label, "字符串按钮的标签口径与数字支同式（剥标签、去序号前缀）")
        assertEquals(null, exit.ordinal, "字符串按钮没有序号徽标")
        assertEquals("exit", exit.submitText, "提交值 = 原串（与命中层 stringValues 通道同口径）")
        assertTrue(found.any { it.value == 0L }, "数字支不回归：纯数字仍折 Long（INT 闸只放行 Long）")
        assertTrue(found.any { it.value == "戻る" }, "双引号形态的 value 剥引号后同样进字符串通道")
        assertEquals(
            listOf(0L),
            found.filter { it.value == 0L || it.value == "0" }.map { it.value },
            "数字串折 Long，不再另生字符串分身",
        )
    }

    @Test
    fun `命中层为空时兜底路把字符串按钮送进右侧栏`() {
        // 走完整管线（含 flatten）时字符串按钮本就有命中层通道；这里直接喂 scan() 的兜底输入，
        // 钉「只要页面上印了 <button value='exit'>，栏里就必须有 exit」这一条（与数字支同判据）。
        val fallback = OptionButtonScanner.scanHtmlButtons(listOf("<button value='go_on'>继续</button>"))
        assertEquals(1, fallback.size)
        assertEquals("go_on", fallback.single().value)
        assertEquals("继续", fallback.single().label)
    }
}
