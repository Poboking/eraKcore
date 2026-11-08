package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ConsoleFeed.pump` 取帧走 [LineBufferConsoleSink.frameSnapshot]（一屏一帧）的钉子。
 *
 * 场景照 2026-10-31「点料理选项后游戏界面无尽上下跳」的最小复现：脚本在
 * `REDRAW 0` 冻结段里「整屏 CLEARLINE + 重画」且**不等输入**（`AWAIT 1` 让出环），
 * 泵在 revision 前进之后、循环体中途才来取快照 —— 修复前拿到的是被清掉一半的
 * 中间态（屏幕上菜单忽隐忽现 = 上下跳），修复后必须拿到发布时刻的完整帧。
 */
class ConsoleFeedAtomicFrameTest {

    private val metrics = CellMetrics(18)

    @Test
    fun `pump after mid-loop writes still renders the published frame`() {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        val feed = ConsoleFeed(sink, screen, null, metrics)

        sink.printLine("料理菜单头")
        sink.printLine("料理菜单行一")
        sink.holdFrame()
        sink.printLine("冻结段收尾行")
        sink.publishFrame() // AWAIT 让出边界：整帧发布

        // 循环体下一圈：回环头清屏 + 只画出一行（泵若读活表就会把这块中间态交上屏）。
        sink.holdFrame()
        sink.deleteLine(3)
        sink.printLine("下一圈第一行")

        feed.pump()
        assertEquals(
            listOf("料理菜单头", "料理菜单行一", "冻结段收尾行"),
            screen.snapshotText(),
            "泵晚到也只能看到发布时刻的完整帧，循环体中间态不上屏",
        )
    }

    @Test
    fun `streaming frames between yields still reach the screen`() {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        val feed = ConsoleFeed(sink, screen, null, metrics)

        sink.printLine("直通一行")
        feed.pump()
        assertEquals(listOf("直通一行"), screen.snapshotText(), "无冻结段的直通语义不变")
    }
}
