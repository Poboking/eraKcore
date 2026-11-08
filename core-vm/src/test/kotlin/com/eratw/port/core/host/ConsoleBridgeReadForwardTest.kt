package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H9（2026-11 挖虫波 W2-gfxconc）：[ConsoleBridge] 读出侧的**真转发**。
 *
 * 病灶与 BOARD 里 noteInputWait 的同型事故一致：[ConsoleSink] 的这批成员缺省实现是
 * **静默缺省**（null / 0 / LEFT），桥接器漏转发时 `ctx.console` 的读者永远拿不到真 sink
 * 的活状态 —— 不报错、不炸，只是所有读数坍缩回「宿主未接线」形态（潜伏陷阱口径，
 * MED/LOW）。修法取**补真转发**而不是 fail-fast：
 * - `displayedLines` / `popPrintingBuffer` 的「未接线」语义**就是** null（fn 层据此抛
 *   CodeEE，ConsoleSink 对应 KDoc），error 会抢在语句层之前炸穿 delegate==null 的合法形态；
 * - 其余五项的缺省值即 ConsoleSink KDoc 声明的出厂态，读方依赖「未设置 ⇒ 回落
 *   Config.ForeColor/BackColor、SETFONT 空串」这条链，error 会掐断它。
 */
class ConsoleBridgeReadForwardTest {

    private fun bridgeOver(sink: LineBufferConsoleSink?): ConsoleSink =
        ConsoleBridge(sink, null, StringBuilder(), Long.MAX_VALUE)

    @Test
    fun `七个读成员经桥接到真 sink 的活状态`() {
        val sink = LineBufferConsoleSink(clientWidth = 40, defaultFontName = "MS Gothic")
        val bridge = bridgeOver(sink)

        // 写侧本就转发；读侧七个成员修复前全部坍缩缺省。
        bridge.setBackColor(0, 255, 0)
        assertEquals(0x00FF00, bridge.backgroundColor, "backgroundColor 漏转发时恒 null")

        bridge.setFont("黑体")
        assertEquals("黑体", bridge.fontName)
        assertEquals("MS Gothic", bridge.defaultFontName, "defaultFontName 是 sink 的构造注入项")

        bridge.setAlignment(Alignment.RIGHT)
        assertEquals(Alignment.RIGHT, bridge.alignment, "alignment 漏转发时恒 LEFT")

        bridge.setRedraw(1)
        assertEquals(1, bridge.redraw, "redraw 漏转发时恒 0")

        bridge.printLine("桥接行")
        val lines = bridge.displayedLines
        assertNotNull(lines, "displayedLines 漏转发时恒 null（GETDISPLAYLINE 会误报宿主未接线）")
        assertTrue(lines.isNotEmpty())

        // popPrintingBuffer：读取即消费，桥接器必须**恰好一次**触达 delegate。
        bridge.print("待决", lineEnd = false)
        val popped = bridge.popPrintingBuffer
        assertNotNull(popped, "popPrintingBuffer 漏转发时恒 null")
        assertEquals(1, popped.size)
        assertEquals(0, bridge.popPrintingBuffer!!.size, "第二次读必须为空（弹出即清空，不得弹两次）")
        assertEquals(sink.displayedLines!!.size, bridge.displayedLines!!.size, "弹出侧不进显示行（原口径不回归）")
    }

    @Test
    fun `delegate 缺席时回落接口缺省与转发前逐字相同`() {
        val bridge = bridgeOver(null)
        assertNull(bridge.displayedLines)
        assertNull(bridge.popPrintingBuffer)
        assertNull(bridge.backgroundColor)
        assertNull(bridge.fontName)
        assertNull(bridge.defaultFontName)
        assertEquals(Alignment.LEFT, bridge.alignment)
        assertEquals(0, bridge.redraw)
        // 既有四个读成员的历史口径不动（同一回落形态）：
        assertEquals(0L, bridge.lineCount)
        assertEquals(0, bridge.fontStyle)
        assertTrue(bridge.printingBufferEmpty)
        assertNull(bridge.foregroundColor)
    }
}
