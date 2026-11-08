package com.eratw.port.render.view

import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.glyph.FontWidthRuler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「同一把尺贯穿全链」的**屏级接线闸**（AA 画/老虎机字符画错位修复，2026-11）。
 *
 * [FontWidthRulerTest] 钉的是尺本身的读数；本类钉的是「[ConsoleScreen] 把那只尺
 * 真的递进了折行（[com.eratw.port.render.grid.LineFolder]）与列区间
 * （[com.eratw.port.render.displaylist.DisplayList]）」—— 只改尺不接线的话，这两个闸会静默
 * 退回静态网格（缺省 [com.eratw.port.core.host.StaticFontDisplayWidth]），单测绿而屏上照旧错位。
 *
 * 读数依据（与 [FontWidthRulerTest] 同源，随包 sidecar 实测）：
 * - 老虎机 `SETFONT "MS Gothic"`：块元素 `▂ U+2582` 在等宽族里是半角 1 格，
 *   静态 CP932 表按「表外字」给 2 格 —— 82 字符的上框被算成 164 格；
 * - 觉的 AA 画 `SETFONT "Saitamaar"`：发丝空格 `U+200A` 真实 1.125px ⇒ 0.12 格，
 *   20 个 ≈ 2.4 格整串一次取整 = 2 格；静态表给 20 × 2 = 40 格。
 */
class ConsoleScreenFontWidthWireTest {

    private val ruler = FontWidthRuler()

    /** 红魔馆老虎机上框：CASINO_SLOT_UTIL.ERB:643 `UNICODE(0x2582)*82`，`SETFONT "MS Gothic"`。 */
    private val slotTopFrame =
        ScreenRows.line(ScreenRows.run("▂".repeat(82), fontName = "MS Gothic"))

    /** 觉 AA 画的微调空格行：20 个发丝空格，`SETFONT "Saitamaar"`。 */
    private val hairAdjustRow =
        ScreenRows.line(ScreenRows.run(" ".repeat(20), fontName = "Saitamaar"))

    @Test
    fun `接尺的屏按字体真值折行`() {
        val wired = ConsoleScreen(100, 64, fontWidth = ruler.asDisplayWidth())
        wired.submitSnapshot(listOf(slotTopFrame))
        // 82 格 < 100：一整行，不再被静态表的 164 格误折。
        assertEquals(1, wired.visibleRows().size)
        assertEquals(82, wired.visibleRows().single().cells)
    }

    @Test
    fun `缺省屏保持静态网格读数`() {
        val legacy = ConsoleScreen(100, 64)
        legacy.submitSnapshot(listOf(slotTopFrame))
        // 接线前口径：82 × 2 = 164 格 > 100 ⇒ 折两行。缺省尺必须逐值保住它。
        assertEquals(2, legacy.visibleRows().size)
    }

    @Test
    fun `列区间按尺的亚格累加推进`() {
        val wired = ConsoleScreen(200, 64, fontWidth = ruler.asDisplayWidth())
        wired.submitSnapshot(listOf(hairAdjustRow))
        // 20 × 0.12 格整串一次取整 = 2；静态表是 20 × 2 = 40。
        val cmd: DrawCmd = wired.list.render().single()
        assertEquals(2, cmd.colEnd - cmd.colStart)
    }

    @Test
    fun `缺省屏的列区间与接线前同值`() {
        val legacy = ConsoleScreen(200, 64)
        legacy.submitSnapshot(listOf(hairAdjustRow))
        val cmd: DrawCmd = legacy.list.render().single()
        assertEquals(40, cmd.colEnd - cmd.colStart)
    }
}
