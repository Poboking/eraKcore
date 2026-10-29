package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Console.ResetStyle()`（EmueraConsole.cs:2838-2842）与 builtin `GETLINESTR`
 * （FunctionMethodCreator.cs:5894-5912 + EmueraConsole.cs:3420）的语义测试。
 *
 * 期望值全部由 C# 参考实现推导，不用本仓实现回读当期望：
 * - 复位集 = defaultStyle（:206：Config.ForeColor / Regular / null→Config.FontName，
 *   StringStyle.cs:20-27）+ alignment LEFT（:2841）；
 * - ResetStyle **不碰** bgColor（:200/:2844 独立字段）、redraw（:455）、打印缓冲/显示行；
 * - GETLINESTR 是 getStBar 的函数面：重复填充到 >= 可画宽度、逐字符删到 <=，
 *   与「按行号读历史行」无关（单字符串实参，:5899；空串 → ArgIsEmptyString，:5905-5909，
 *   zhs 文案 lang.emuera-zhs.xml:929）。
 */
class ConsoleResetStyleTest {

    // ------------------------------------------------------------------
    // A. ResetStyle：逐字段对照
    // ------------------------------------------------------------------

    /** 复位前显式让 4 项样式非默认，断言复位后逐字段回默认；背景/重绘/缓冲/行计数不动。 */
    @Test
    fun resetStyleRestoresAllFourStyleFieldsAndKeepsTheRest() {
        val s = LineBufferConsoleSink(clientWidth = 80, defaultFontName = "黑体")
        // 开 `UseUserStyle` 轴：出厂 false（EmueraConsole.cs:467），只有 PRINT 系语句写真
        // （FunctionIdentifier.cs:179/:276、Process.cs:1110/:1129/:1149）。本用例手动改样式
        // 再打印，等价于「站在 PRINT 语句里」，不开轴则 Style 取值器恒走 defaultStyle
        // （:471-472），待决段的样式快照会退化。
        s.setUseUserStyle(true)

        // 非默认化：前景、样式位、字体名、对齐 —— 全部与出厂态不同。
        s.setForeColor(255, 0, 0)
        s.setFontStyle(9) // 粗体|下划线
        s.setFont("楷体")
        s.setAlignment(Alignment.CENTER)
        // 非复位面：背景色、重绘（3 折减成 1）、待决打印缓冲。
        s.setBackColor(1, 2, 3)
        s.setRedraw(3)
        s.print("pending", lineEnd = false)

        assertEquals(0xFF0000, s.foregroundColor)
        assertEquals(9, s.fontStyle)
        assertEquals("楷体", s.fontName)
        assertEquals(Alignment.CENTER, s.alignment)

        val revBefore = s.revision
        s.resetStyle()

        // 复位四项 → 出厂态（EmueraConsole.cs:206 defaultStyle + :2841 LEFT）。
        assertNull(s.foregroundColor, "RESETCOLOR 同落点：null = 回 Config.ForeColor")
        assertEquals(0, s.fontStyle, "defaultStyle.FontStyle = Regular")
        assertEquals("黑体", s.fontName, "defaultStyle 的空字体名归一成 Config.FontName（StringStyle.cs:20-27）")
        assertEquals(Alignment.LEFT, s.alignment, "ResetStyle 第 2 句（:2841）")

        // 复位面之外，逐项确认「没被顺手清」。
        assertEquals(0x010203, s.backgroundColor, "bgColor 不在 ResetStyle 方法体里（:2838-2842 未触碰）")
        assertEquals(1, s.redraw, "redraw 不在 ResetStyle 里")
        assertFalse(s.printingBufferEmpty, "待决打印缓冲不属于 ResetStyle")
        assertEquals(revBefore, s.revision, "样式复位不改变 lines()，revision 不自增")
        assertEquals(0L, s.lineCount)

        // 待决行的样式快照是入缓冲时定死的（ConsoleStyledString.cs:26-43），
        // resetStyle 不会追溯改写已入缓冲的段。
        s.newLine()
        val merged = s.snapshot().single()
        val pendingSeg = merged.segments.first { it.text == "pending" }
        assertEquals(0xFF0000, pendingSeg.foreColor)
        assertEquals(9, pendingSeg.styleMask)
    }

    /** 复位之后的新输出逐字段取默认样式（样式残留不得污染 BEGIN 之后的行）。 */
    @Test
    fun postResetOutputUsesDefaultStyle() {
        val s = LineBufferConsoleSink(clientWidth = 80, defaultFontName = "黑体")
        // 开 `UseUserStyle` 轴（理由同上一用例）：不开轴时打印恒取出厂样式，
        // 「复位之后取默认」就成了空断言。
        s.setUseUserStyle(true)
        s.setForeColor(255, 0, 0)
        s.setFontStyle(3) // 粗体|斜体
        s.setFont("楷体")
        s.setAlignment(Alignment.RIGHT)
        s.resetStyle()

        s.printLine("abc")
        val line = s.snapshot().single()
        assertEquals(Alignment.LEFT, line.alignment)
        val seg = line.segments.single()
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor, "未 SETCOLOR 的段用出厂前景（Config.ForeColor，ConfigData.cs:68）")
        assertEquals(0, seg.styleMask)
        assertEquals("黑体", seg.fontName)
    }

    /**
     * 接口缺省组合路径（委托型宿主，如 ProcessRuntime 的 ConsoleBridge 每只 setter
     * 转发真 sink）与 [LineBufferConsoleSink] 的字段直写覆写必须落到**同一终态**，
     * 且组合里不得出现背景/重绘动作（resetStyle 复位面 = 四项，出处见
     * ConsoleSink.resetStyle KDoc）。
     */
    @Test
    fun defaultCompositionMatchesFieldWriteAndTouchesNothingElse() {
        val t = TrackingSink()
        t.setForeColor(255, 0, 0)
        t.setFontStyle(9)
        t.setFont("楷体")
        t.setAlignment(Alignment.RIGHT)
        t.setBackColor(1, 2, 3)
        t.setRedraw(1)

        val rev = LineBufferConsoleSink(clientWidth = 80, defaultFontName = FONT).also {
            it.setForeColor(255, 0, 0)
            it.setFontStyle(9)
            it.setFont("楷体")
            it.setAlignment(Alignment.RIGHT)
        }
        rev.resetStyle()
        val callsBefore = t.calls.size
        t.resetStyle()

        // 终态逐字段一致。
        assertEquals(rev.foregroundColor, t.fore)
        assertEquals(rev.fontStyle, t.style)
        assertEquals(rev.fontName, t.fontApplied)
        assertEquals(rev.alignment, t.align)
        assertNull(t.fore)
        assertEquals(0, t.style)
        assertEquals(FONT, t.fontApplied, "setFont(空串) 恢复默认是 SETFONT 既有语义（EmueraConsole.cs:2826-2835）")
        assertEquals(Alignment.LEFT, t.align)
        // 组合只走四个动作，不碰背景/重绘（只比较 resetStyle 期间的增量调用）。
        assertEquals(
            listOf("resetForeColor", "setFontStyle", "setFont", "setAlignment"),
            t.calls.subList(callsBefore, t.calls.size),
        )
        assertEquals(0x010203, t.back, "backgroundColor 保持非默认 —— 复位面外")
        assertEquals(1, t.redrawMode)
    }

    /** Noop 宿主吃接口缺省复位，不影响其恒默认读数。 */
    @Test
    fun noopSinkResetStyleIsHarmless() {
        NoopConsoleSink.resetStyle()
        assertEquals(0L, NoopConsoleSink.lineCount)
        assertTrue(NoopConsoleSink.printingBufferEmpty)
        assertNull(NoopConsoleSink.foregroundColor)
        assertEquals(Alignment.LEFT, NoopConsoleSink.alignment)
    }

    // ------------------------------------------------------------------
    // 测试夹具
    // ------------------------------------------------------------------

    /**
     * 只经读出侧字段记录样式动作的宿主：不覆写 [ConsoleSink.resetStyle]，
     * 走接口缺省组合，验证「组合出的复位终态」与字段直写一致。
     */
    private class TrackingSink : ConsoleSink {
        val calls = ArrayList<String>()
        var fore: Int? = null
        var back: Int? = null
        var style = 0
        // 不能叫 font（合成的 `<set-font>` 与 ConsoleSink.setFont(String) 同 JVM 签名 ⇒ Platform declaration clash），
        // 也不能叫 fontName（ConsoleSink 已有同名的只读出侧成员）。
        var fontApplied = FONT
        var align = Alignment.LEFT
        var redrawMode = 0

        override fun print(text: String) {}
        override fun printLine(text: String) {}
        override fun printC(text: String, alignRight: Boolean) {}
        override fun printSingleLine(text: String) {}
        override fun printButton(text: String, value: Long) {}
        override fun printButton(text: String, value: String) {}
        override fun printButtonC(text: String, value: Long, alignRight: Boolean) {}
        override fun printButtonC(text: String, value: String, alignRight: Boolean) {}
        override fun printPlain(text: String) {}
        override fun printTemporaryLine(text: String) {}
        override fun printBar() {}
        override fun printCustomBar(text: String) {}
        override fun newLine() {}
        override fun deleteLine(count: Int) {}
        override fun clearText() {}
        override fun setForeColor(r: Int, g: Int, b: Int) {
            calls.add("setForeColor"); fore = (r shl 16) or (g shl 8) or b
        }

        override fun setBackColor(r: Int, g: Int, b: Int) {
            calls.add("setBackColor"); back = (r shl 16) or (g shl 8) or b
        }

        override fun resetForeColor() { calls.add("resetForeColor"); fore = null }
        override fun resetBackColor() { calls.add("resetBackColor"); back = null }
        override fun setFontStyle(mask: Int) { calls.add("setFontStyle"); style = mask }
        override fun setFont(name: String) {
            calls.add("setFont"); fontApplied = if (name.isEmpty()) FONT else name
        }

        override fun setAlignment(kind: Alignment) { calls.add("setAlignment"); align = kind }
        override fun setRedraw(mode: Int) { calls.add("setRedraw"); redrawMode = mode }
        override fun printHtml(text: String, option: Boolean) {}
        override fun addHtmlIsland(text: String) {}
        override fun clearHtmlIslands() {}
        override fun printError(text: String) {}
    }
}

/** 测试注入的 `Config.FontName`（对照 [LineBufferConsoleSink] 的 defaultFontName 通道）。 */
private const val FONT = "测试ゴシック"
