package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整屏底色（`SETBGCOLOR` 活值）经 [ConsoleFeed] 发布进 [ConsoleScreen.backgroundArgb] 的钉子
 * （2026-11 玩家反馈「时停背景不变色只有文字背景变色」的修复回归）。
 *
 * 原版口径：`SETBGCOLOR` 写的是控制台级字段 `bgColor`（EmueraConsole.cs:200、
 * `SetBgColor` :2843-2846），每帧 `graph.Clear(bgColor)` 清**整张**画布（`:1816`）——
 * 值一变、一行不打，下一帧整个界面就换色。时停入口 `COMF355 時間停止.ERB:16` 的
 * `SETBGCOLOR C_THE_WORLD` 落的就是这一层。
 *
 * 关键形态：**SETBGCOLOR 之后不打印任何行**——`sink.revision` 不前进，pump 若只盯
 * revision 就会把新底色漏到「下一次有新行时」才发布，屏上就是玩家看到的「背景不变色」。
 */
class ConsoleFeedBackgroundTest {

    @Test
    fun `SETBGCOLOR 不打印行也当帧发布整屏底色`() {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        val feed = ConsoleFeed(sink, screen)

        sink.printLine("变色前的行")
        feed.pump()
        assertEquals(0xFF000000.toInt(), screen.backgroundArgb, "未显式 SETBGCOLOR 回落 Config.BackColor 黑（ConfigData.cs:69）")

        // 时停入口的原型：只改色，不打印（revision 不动）。
        sink.setBackColor(0x11, 0x22, 0x33)
        assertTrue(feed.hasUnpublishedContent(), "活值变了就是未发布内容，重绘循环必须 invalidate")
        feed.pump()
        assertEquals(0xFF112233.toInt(), screen.backgroundArgb, "整屏底色当帧换色，不等新行")

        // 解除时停的原型：RESETBGCOLOR / 换天气背景（時間停止解除.ERB:493-497）。
        sink.resetBackColor()
        feed.pump()
        assertEquals(0xFF000000.toInt(), screen.backgroundArgb, "RESETBGCOLOR（FunctionIdentifier.cs:1515）回默认色")
    }

    @Test
    fun `重复 pump 幂等且底色不回退`() {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        val feed = ConsoleFeed(sink, screen)

        sink.setBackColor(0x36, 0x36, 0x36)
        feed.pump()
        feed.pump()
        assertEquals(0xFF363636.toInt(), screen.backgroundArgb, "revision 未变 + 底色已同步 ⇒ 幂等空转")
        assertTrue(!feed.hasUnpublishedContent(), "稳态帧不再触发 invalidate")
    }

    /** CONFIG-WIRE U5 合流接线：RESETBGCOLOR 的回落源 = 装配注入的 Config.BackColor，不是工厂常量。 */
    @Test
    fun `RESETBGCOLOR 回落装配注入的 ConfigBackColor 而非工厂常量`() {
        val sink = LineBufferConsoleSink(clientWidth = 20, defaultBackColor = 0x00FF00)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 100, heightLines = 8)
        val feed = ConsoleFeed(sink, screen)

        feed.pump()
        assertEquals(0xFF00FF00.toInt(), screen.backgroundArgb, "未 SETBGCOLOR：bgColor 初值 = Config.BackColor（EmueraConsole.cs:200）")

        sink.setBackColor(0, 0, 1)
        feed.pump()
        assertEquals(0xFF000001.toInt(), screen.backgroundArgb, "显式活值优先")

        sink.resetBackColor()
        feed.pump()
        assertEquals(0xFF00FF00.toInt(), screen.backgroundArgb, "RESETBGCOLOR（FunctionIdentifier.cs:1515）回落注入值")
    }
}
