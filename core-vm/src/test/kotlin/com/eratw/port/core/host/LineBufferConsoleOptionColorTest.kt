package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 按钮显式颜色进入选项快照的专项测试（布娃娃调色板就是这条通道的真实消费者）。
 *
 * 与 [LineBufferConsoleOptionsTest] 分开，避免把既有大型快照测试类继续撑大；
 * 本类钉「颜色在打印瞬间快照、跨行/去重不丢、HTML 内外层样式都能带出」，以及
 * 「普通彩色按钮保留底色，但只有色块字形才进调色板通道」。
 */
class LineBufferConsoleOptionColorTest {

    private fun sink(): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 40, maxLines = 200)

    private fun note(
        sink: LineBufferConsoleSink,
        type: InputRequest.InputType = InputRequest.InputType.INT,
    ) {
        sink.noteInputWait(InputRequest(type = type, form = InputRequest.RequestForm.VALUE))
    }

    /**
     * 布娃娃系统的调色板（`MOB_SYSTEM.ERB:274-285`）：`SETCOLOR COLOR_PALETTE:i` +
     * `PRINTBUTTON "■", 80000+i` + `RESETCOLOR`。颜色必须在按钮入缓冲时快照；若等到
     * `toOption()` 再读当前色，`RESETCOLOR` 已经把 128 枚都洗成默认色。
     */
    @Test
    fun `显式 PRINTBUTTON 保留打印瞬间的前景色_128 色调色板逐一对应`() {
        val s = LineBufferConsoleSink(clientWidth = 400, maxLines = 200)
        s.setUseUserStyle(true)
        val colors = (0 until 128).map { (0x123456 + it * 0x010203) and 0xFFFFFF }
        for (i in colors.indices) {
            val rgb = colors[i]
            s.setForeColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            s.printButton("■", 80000L + i)
            s.print(" ")
        }
        s.newLine()
        note(s)

        val opts = s.currentOptions()
        assertEquals(128, opts.size, "调色板 8×16 的 128 枚按钮必须一枚不少")
        assertEquals((0 until 128).map { 80000L + it }, opts.map { it.value })
        assertEquals(colors, opts.map { it.colorRgb }, "每枚按钮的颜色必须精确等于打印时的 SETCOLOR")
        assertTrue(opts.all { it.label == "■" }, "调色板按钮的屏上原文仍是色块字形")
        assertTrue(opts.all { it.visual == OptionVisual.COLOR_SWATCH }, "引擎必须把色块标成显式视觉类型")
    }

    @Test
    fun `RESETCOLOR 之后的按钮不再带显式颜色`() {
        val s = sink()
        s.setUseUserStyle(true)
        s.setForeColor(0x12, 0x34, 0x56)
        s.printButton("■", 1L)
        s.resetForeColor()
        s.printButton("●", 2L)
        s.newLine()
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf(1L, 2L), opts.map { it.value })
        assertEquals(listOf(0x123456, null), opts.map { it.colorRgb })
        assertEquals(listOf(OptionVisual.COLOR_SWATCH, OptionVisual.TEXT), opts.map { it.visual })
    }

    @Test
    fun `按钮折行成多条同源记录时颜色仍保留`() {
        // 宽 6：一颗 8 格按钮会被拆成两行，但两条 Button 共用 groupId，右栏只留一枚。
        val s = LineBufferConsoleSink(clientWidth = 6, maxLines = 200)
        s.setUseUserStyle(true)
        s.setForeColor(0xAB, 0xCD, 0xEF)
        s.printButton("■■■■", 7L)
        s.newLine()
        note(s)

        val opt = s.currentOptions().single()
        assertEquals(7L, opt.value)
        assertEquals(0xABCDEF, opt.colorRgb, "跨行重放不能把源按钮的显式色丢掉")
        assertEquals(OptionVisual.COLOR_SWATCH, opt.visual)
    }

    @Test
    fun `外层 font 颜色在闭合标签处停止_不染后面的按钮`() {
        val s = sink()
        s.printHtml(
            "<font color='#112233'><button value='1'>■</button></font>" +
                "<button value='2'>■</button>",
            option = false,
        )
        note(s)

        val opts = s.currentOptions()
        assertEquals(listOf(1L, 2L), opts.map { it.value })
        assertEquals(listOf(0x112233, null), opts.map { it.colorRgb })
        assertEquals(listOf(OptionVisual.COLOR_SWATCH, OptionVisual.TEXT), opts.map { it.visual })
    }

    @Test
    fun `HTML 按钮正文的 font 颜色进入选项快照`() {
        val s = sink()
        s.printHtml(
            "<button value='1'><font color='#12AB34'>■</font></button>" +
                "<button value='2'><shape type='rect' param='10,10,10,10' color='lime'>■</shape></button>",
            option = false,
        )
        note(s)

        val opts = s.currentOptions().associateBy { it.value }
        assertEquals(0x12AB34, opts.getValue(1L).colorRgb)
        assertEquals(0x00FF00, opts.getValue(2L).colorRgb, "shape color 与命名色都要认")
        assertTrue(opts.values.all { it.visual == OptionVisual.COLOR_SWATCH })
    }
    @Test
    fun `普通彩色按钮保留颜色但不进入色块通道`() {
        val s = sink()
        s.setUseUserStyle(true)
        s.setForeColor(0x12, 0x34, 0x56)
        s.printButton("普通选项", 1L)
        s.printButton("移动[400]", 2L)
        s.newLine()
        s.printHtml(
            "<font color='#64fa8c'><button value='3'>约会[405]</button></font>",
            option = false,
        )
        note(s)

        val opts = s.currentOptions().associateBy { it.value }
        assertEquals(setOf<Any>(1L, 2L, 3L), opts.keys)
        assertEquals(0x123456, opts.getValue(1L).colorRgb)
        assertEquals(0x123456, opts.getValue(2L).colorRgb)
        assertEquals(0x64FA8C, opts.getValue(3L).colorRgb, "HTML 外层着色也要随按钮保留")
        assertTrue(opts.values.all { it.visual == OptionVisual.TEXT }, "普通彩色按钮不能变成色块")
    }

    @Test
    fun paletteMaskRequiresEightContiguousSwatchesOnOneGameLine() {
        fun swatch(value: Long, serial: Long) = ConsoleOption(
            value = value,
            label = "■",
            origin = OptionOrigin.EXPLICIT,
            lineSerial = serial,
            colorRgb = 0x112233,
            visual = OptionVisual.COLOR_SWATCH,
        )
        assertFalse(paletteSwatchMask((0 until 7).map { swatch(it.toLong(), 1L) }).any { it })
        assertTrue(paletteSwatchMask((0 until 8).map { swatch(it.toLong(), 1L) }).all { it })
        assertFalse(
            paletteSwatchMask(
                (0 until 4).map { swatch(it.toLong(), 1L) } +
                    (0 until 4).map { swatch(10L + it, 2L) },
            ).any { it },
            "跨行拼不成 8 枚调色板",
        )

        val mixed = listOf(
            ConsoleOption(1L, "页签", OptionOrigin.EXPLICIT, lineSerial = 3L, colorRgb = 0x123456),
        ) + (0 until 8).map { swatch(10L + it, 3L) } +
            listOf(ConsoleOption(99L, "结束", OptionOrigin.EXPLICIT, lineSerial = 3L, colorRgb = 0x654321))
        assertEquals(
            listOf(false) + List(8) { true } + listOf(false),
            paletteSwatchMask(mixed).toList(),
        )
    }

    @Test
    fun colorBlockLabelOnlyAcceptsPureBlockGlyphs() {
        assertTrue(isColorBlockLabel("■"))
        assertTrue(isColorBlockLabel(" ● "))
        assertTrue(isColorBlockLabel("■\uFE0F"))
        assertTrue(isColorBlockLabel("■\u200B"))
        assertFalse(isColorBlockLabel("■ 说明"))
        assertFalse(isColorBlockLabel("■x"))
        assertFalse(isColorBlockLabel("红色"))
        assertFalse(isColorBlockLabel(""))
        assertFalse(isColorBlockLabel("   "))
    }

}
