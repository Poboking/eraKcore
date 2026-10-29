package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.GraphicsLayer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「按钮里先有图形段」时右侧栏是否还能枚举到它（2026-10-24 真机证据）。
 *
 * 存档页槽位 0 的原始 markup（`dev "lines 80"` 实测）：
 * ```
 * <button value='0'><shape type='space' param='63px'><font color='#777777'>[   0] - ----</font></button>
 * ```
 * 槽位 1..39 没有那个 `<shape type='space'>` 前缀 —— 真机 `chips` 恰好**只缺 value=0**，
 * 说明「按钮内先落图形段」这条路径把按钮丢了。本用例用同一份 markup 复现：
 * 必须**挂图形层**（`ConsoleFeed.kt:84` 里 flatten 只在 graphics != null 时才创建），
 * 否则 HTML 不会被展平、按钮根本不存在。
 */
class ButtonWithLeadingShapeTest {

    private val metrics = CellMetrics(18)

    private class FakeSizes : HtmlRunFlatten.ImageSizeSource {
        override fun destSize(resource: String): Int? = null
    }

    private fun build(html: List<String>): ConsoleScreen {
        val sink = LineBufferConsoleSink(clientWidth = 120)
        val screen = ConsoleScreen(clientWidth = 120, maxLog = 500, heightLines = 20)
        val feed = ConsoleFeed(sink, screen, GraphicsLayer(), metrics, FakeSizes())
        html.forEach { sink.printHtml(it, false) }
        feed.pump()
        screen.refresh()
        return screen
    }

    @Test
    fun `按钮内先有 shape 段时按钮仍要进栏`() {
        val screen = build(
            listOf(
                // 真实的槽位 0：按钮内以 <shape type='space'> 开头
                "<button value='0'><shape type='space' param='63px'><font color='#777777'>[   0] - ----</font></button>",
                // 普通槽位
                "<button value='1'>[   1] - ----</button>",
                "<button value='2'>[   2] - ----</button>",
            ),
        )
        val values = OptionButtonScanner.scan(screen, maxCount = 64).map { it.value }
        val dbg = "values=" + values + " chipRows=" + screen.chipRows().size +
            " hitRows=" + screen.hitRows().size + " buttons=" + screen.buttons.all().size
        assertTrue(values.contains(0L), "槽位 0（按钮内带 shape 段）必须进栏。$dbg")
        assertTrue(values.contains(1L), "槽位 1 必须在。$dbg")
        assertTrue(values.contains(2L), "槽位 2 必须在。$dbg")
    }
}
