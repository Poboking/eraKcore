package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `HTML_PRINT` 族的**输出侧**语义：[htmlPrintDocument] 产出的显示行。
 *
 * 判据分四层，每条都带 C# 行号：
 * 1. **基准**：`fixtures/golden/baseline/pc-golden-root.txt:293-307`（SECTION:HTML / LINE-QUERY）
 *    与调用序列 `fixtures/golden/GOLDEN_02_PRINT.ERB:249-266`。
 * 2. **原版语义**：`MinorShift.Emuera.UI.Game/HtmlManager.cs`（下称 HM）的
 *    `html2DisplayLine` :529-712、`tagAnalyze` :951-1678、`HtmlSubstring`/`Unescape` 等；
 *    行切分再叠 `PrintStringBuffer.cs:207-262`。
 * 3. **语料真实片段**：`eratw-画蛇添足版eratw4.980/ERB` 下逐行标出来源文件与行号，
 *    变量已替换成该处实际会出现的值（`%C_*%` / `\@...\@` 这类 ERB 展开不在本层）。
 * 4. **接口回归**：`HTML_GETPRINTEDSTR` 在宿主只给 `text` 不给行体 HTML 时的回退。
 *
 * 不做的事：本文件**不用** `LineBufferConsoleSink`。`printHtml` 把整段 HTML 原文塞进
 * `Kind.HTML` 段是既有实现（`core/host`，不在本路文件面），接线清单见交付报告。
 */
private fun hpRows(html: String): List<HtmlPrintRow> = htmlPrintDocument(html).rows

/** 各行的可见文本，用 `|` 分隔，便于一次断言行数与内容。 */
private fun hpVisible(html: String): String = hpRows(html).joinToString("|") { it.visibleText }

private fun hpOneRun(html: String): HtmlPrintRun {
    val rows = hpRows(html)
    assertEquals(1, rows.size, "行数：$html")
    assertEquals(1, rows[0].runs.size, "段数：$html")
    return rows[0].runs[0]
}

class HtmlPrintSemanticsTest {

    // ===== 1. 基准 294 行：HTML_PRINT "<b>HTML</b>PLAIN" =====

    @Test
    fun `基准 294 行 HTML_PRINT 把 b 标签吃成样式段 上屏 HTMLPLAIN`() {
        // fixtures/golden/GOLDEN_02_PRINT.ERB:250 的原文；基准 :294 只留下可见文本
        val doc = htmlPrintDocument("<b>HTML</b>PLAIN")
        assertEquals(1, doc.rows.size)
        val row = doc.rows[0]
        assertEquals("HTMLPLAIN", row.visibleText)
        // 原版是两段 ConsoleStyledString（HM :573 文本段 + :1080 b 位），样式位各自独立
        assertEquals(2, row.runs.size)
        assertEquals(HtmlStyleBits.BOLD, row.runs[0].style.styleBits)
        assertEquals(0, row.runs[1].style.styleBits)
        assertEquals(Alignment.LEFT, row.align)
        assertFalse(row.noBreak)
        // 9 个半角格（HTMLPLAIN），口径 = CellWidth（CP932 字节数）
        assertEquals(9, row.cells)
    }

    // ===== 2. 行数口径 =====

    @Test
    fun `br 与裸换行的行数照抄 list2 的 null 标记语义`() {
        // HM :650-666 → PrintStringBuffer.cs:207-216：null 标记处结行；
        // 空输入 0 行（:198-201）；尾部 br 不再补行（:259-262 只在残留非空时补）。
        assertEquals(0, hpRows("").size)
        assertEquals("AB", hpVisible("AB"))
        assertEquals("AB", hpVisible("AB<br>"))
        assertEquals("AB", hpVisible("AB\n"))
        assertEquals("|AB", hpVisible("<br>AB"))
        assertEquals("AB|CD", hpVisible("AB<br>CD"))
        assertEquals("AB|CD", hpVisible("AB\nCD"))
        assertEquals("AB||CD", hpVisible("AB<br><br>CD"))
    }

    @Test
    fun `多行共享终态对齐 原版把 state Alignment 写给每条显示行`() {
        // HM :700-710：解析完才逐行 SetAlignment(state.Alignment)
        val rows = hpRows("<p align='center'>A<br>B</p>")
        assertEquals("A|B", rows.joinToString("|") { it.visibleText })
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.align == Alignment.CENTER }, "两行都应是 CENTER")
    }

    // ===== 3. 语料片段：TITLE =====

    @Test
    fun `TITLE 75 行的 shape button br 收成一行两段`() {
        // ERB/TITLE.ERB:75 的原文，{MID_W} 代入 15
        val html = "<shape type='space' param='15px'><button value='0'>[0] 开始游戏</button><br>"
        val doc = htmlPrintDocument(html)
        assertEquals(1, doc.rows.size)
        val row = doc.rows[0]
        assertEquals("[0] 开始游戏", row.visibleText)
        // shape 的 AltText 是**重建**的标签文案（ConsoleShapePart.cs:15-75 → ToString :82-89）
        assertEquals("<shape type='space' param='15px'>[0] 开始游戏", row.plainText)
        assertEquals(HtmlRunKind.SHAPE, row.runs[0].kind)
        assertEquals(HtmlRunKind.TEXT, row.runs[1].kind)
        val btn = row.runs[1].button
        assertTrue(btn != null && btn.isButton)
        assertEquals("0", btn?.value)
        assertTrue(btn?.valueIsInt == true)
        assertEquals(0L, btn?.valueInt)
        // shape 在 <button> 之前，不属于按钮组（HM :1559 起按钮只覆盖其后的节点）
        assertNull(row.runs[0].button)
        // 格子读数：`[0] ` 4 个半角 + 4 个全角 = 12 格
        assertEquals(12, row.cells)
    }

    @Test
    fun `TITLE 59 行的居中图片行 可见文本为空 AltText 是重建文案`() {
        // ERB/TITLE.ERB:59 的原文，TOSTR(LOCAL:0, "000") 代入 001
        val rows = hpRows("<p align='center'><img src='TW_title001'></p>")
        assertEquals(1, rows.size)
        assertEquals(Alignment.CENTER, rows[0].align)
        // 玩家看到的是图，不产生字符；GETDISPLAYLINE 走 ToString 拿到 AltText
        // （ConsoleDisplayLine.cs:120-133 → ConsoleImagePart.cs:117-124）
        assertEquals("", rows[0].visibleText)
        assertFalse(rows[0].hasVisibleText)
        assertEquals("<img src='TW_title001'>", rows[0].plainText)
        assertEquals(HtmlRunKind.IMAGE, rows[0].runs[0].kind)
    }

    // ===== 4. 样式帧 =====

    @Test
    fun `font 帧缺属性显式继承外层 关闭后回退到未指定`() {
        // HM :1651-1667：新帧把父帧的 color/bcolor/face 抄进自己
        val nested = hpOneRun("<font color='red'><font face='黑体'>AB</font></font>")
        assertEquals(0xFF0000, nested.style.fore)
        assertEquals("黑体", nested.style.fontName)
        // 关闭内层只弹一帧：外层仍是红字，但已没有 face
        val rows = hpRows("<font color='red'><font face='黑体'>A</font>B</font>")
        assertEquals(0xFF0000, rows[0].runs[0].style.fore)
        assertEquals("黑体", rows[0].runs[0].style.fontName)
        assertEquals(0xFF0000, rows[0].runs[1].style.fore)
        assertNull(rows[0].runs[1].style.fontName)
        // 全部关闭后回到「未指定」（null 表示回 Config 缺省，不是颜色等于缺省）
        assertEquals("AB", hpVisible("<font color='red'>A</font>B"))
    }

    @Test
    fun `语料 724 行的橙色按钮组 样式与按钮同时命中`() {
        // 魔改内容/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:724，%C_TW_FILTER% 代入 7、
        // %L_LIST_TYPE% 代入 1
        val html =
            "<font color='orange'><button value='7'>[更改列表筛选条件(当前：1)]</button></font>"
        val row = hpRows(html)[0]
        assertEquals("[更改列表筛选条件(当前：1)]", row.visibleText)
        val run = row.runs.single()
        assertEquals(0xFFA500, run.style.fore) // ColorNames 的 orange
        assertEquals("7", run.button?.value)
        assertTrue(run.button?.isButton == true)
        assertEquals(7L, run.button?.valueInt)
    }

    @Test
    fun `语料 1315 行的 gray nonbutton title 居中`() {
        // 魔改内容/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:1315（原文即字面量）
        val html =
            "<p align='center'><font color='gray'><nonbutton title='无法结束处理。列组至少需要一个列'>" +
                "[结束编辑]</nonbutton></font></p>"
        val row = hpRows(html).single()
        assertEquals(Alignment.CENTER, row.align)
        assertEquals("[结束编辑]", row.visibleText)
        val run = row.runs.single()
        assertEquals(0x808080, run.style.fore)
        val btn = run.button
        assertTrue(btn != null)
        assertFalse(btn.isButton) // nonbutton 恒非按钮（HM :1547 value 恒 null）
        assertNull(btn.value)
        assertEquals("无法结束处理。列组至少需要一个列", btn.title)
    }

    // ===== 5. 容错与抛错边界 =====

    @Test
    fun `交叉闭合 b i 合法 未闭合样式在串尾仍抛`() {
        // HM :966-983：闭标签只校验样式位存在后异或，不比对标签名
        val run = hpOneRun("<b><i>AB</b></i>")
        assertEquals("AB", run.text)
        assertEquals(HtmlStyleBits.BOLD or HtmlStyleBits.ITALIC, run.style.styleBits)
        // 串尾校验 :671-674：样式位/字体栈非空 → TagIsNotClosed
        val e = assertFailsWith<CodeEE> { htmlPrintDocument("<i>AB") }
        assertEquals("存在没有结尾的标签", e.message)
        // 字体栈非空同理
        assertEquals("存在没有结尾的标签", assertFailsWith<CodeEE> { htmlPrintDocument("<font color='red'>AB") }.message)
    }

    @Test
    fun `未闭合 p 与 nobr 不抛错 这是原版的容错面`() {
        // 串尾校验（:667-674）只查 div/按钮/样式/字体档，FlagP/FlagNobr 未闭合不算错
        val row = hpRows("<p align='right'><nobr>AB").single()
        assertEquals(Alignment.RIGHT, row.align)
        assertTrue(row.noBreak)
        assertEquals("AB", row.visibleText)
        // 反向：`</p>` / `</nobr>` 之后**尾部**文本才是错（:560-568）
        assertEquals(
            "</p>之后没有文字",
            assertFailsWith<CodeEE> { htmlPrintDocument("<p align='left'>A</p>B") }.message,
        )
        assertEquals(
            "</nobr>后没有文字",
            assertFailsWith<CodeEE> { htmlPrintDocument("<nobr>A</nobr>B") }.message,
        )
    }

    @Test
    fun `clearbutton 把后续 button 降级 且 notooltip 清标题`() {
        // HM :1549-1556：FlagClearButton 期间 IsButton=false，notooltip 时 ButtonTitle=null
        val kept = hpRows("<clearbutton><button value='1' title='t'>A</button></clearbutton>")[0]
        assertFalse(kept.runs.single().button!!.isButton)
        assertEquals("t", kept.runs.single().button!!.title)
        val cleared = hpRows(
            "<clearbutton notooltip='true'><button value='1' title='t'>A</button></clearbutton>",
        )[0]
        assertNull(cleared.runs.single().button!!.title)
    }

    @Test
    fun `没有 value 的 button 不是按钮 官方怪癖`() {
        // HM :1547：IsButton = text7 != null —— 没有 value 的 <button> 只是普通文本
        val noValue = hpRows("<button>A</button>")[0].runs.single().button!!
        assertFalse(noValue.isButton)
        assertNull(noValue.value)
        // long.TryParse（:1543）：整数串才置 ButtonIsInteger
        val intBtn = hpRows("<button value='12'>A</button>")[0].runs.single().button!!
        assertTrue(intBtn.valueIsInt)
        assertEquals(12L, intBtn.valueInt)
        val strBtn = hpRows("<button value='abc'>A</button>")[0].runs.single().button!!
        assertFalse(strBtn.valueIsInt)
        assertEquals(0L, strBtn.valueInt)
        assertEquals("abc", strBtn.value)
    }

    @Test
    fun `button value 的 long TryParse 允许首尾空白 不接受内部空白`() {
        // HM :1543 long.TryParse 默认 NumberStyles.Integer：允许首尾空白与一个正负号；
        // Kotlin toLongOrNull 不跳空白，故解析前 trim（内部空白仍失败，与 .NET 一致）。
        val padded = hpRows("<button value=' 12'>A</button>")[0].runs.single().button!!
        assertTrue(padded.valueIsInt)
        assertEquals(12L, padded.valueInt)
        // ButtonValueStr 保留未 trim 的原串（HM :1511/:1545）
        assertEquals(" 12", padded.value)
        val negative = hpRows("<button value='-3 '>A</button>")[0].runs.single().button!!
        assertTrue(negative.valueIsInt)
        assertEquals(-3L, negative.valueInt)
        val inner = hpRows("<button value='1 2'>A</button>")[0].runs.single().button!!
        assertFalse(inner.valueIsInt)
    }

    // ===== 6. div 浮动块 =====

    @Test
    fun `语料 438 行的 nobr img div 组合 浮动块子行单独收`() {
        // 魔改内容/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:438 的骨架，
        // 三处 {…} 表达式与 %L_SPR% / %L_BUTTONTEMP% 代入实际会出现的值
        val html = "<nobr>　<img src='TW_face1' width='10px' height='10px' ypos='30px'>" +
            "<div width='100px' height='20px' xpos='-20px'>" +
            "<button value='7'>[设置每页显示人数]</button></div></nobr>"
        val row = hpRows(html).single()
        assertEquals(listOf(HtmlRunKind.TEXT, HtmlRunKind.IMAGE, HtmlRunKind.DIV), row.runs.map { it.kind })
        // 玩家看到的字符：行内空格 + 浮动块里的按钮文字（图片不产生字符）
        assertEquals("　[设置每页显示人数]", row.visibleText)
        val div = row.runs[2]
        assertEquals(1, div.divRows.size)
        assertEquals("[设置每页显示人数]", div.divRows[0].visibleText)
        assertEquals("7", div.divRows[0].runs[0].button?.value)
        assertTrue(row.noBreak) // <nobr> 在串首，HM :1093-1102
        // AltText 是重建文案：属性次序固定 height → width → ypos
        // （ConsoleImagePart.cs:41-59），不是输入里的 width → height
        assertEquals(
            "　<img src='TW_face1' height='10px' width='10px' ypos='30px'>[设置每页显示人数]",
            row.plainText,
        )
    }

    @Test
    fun `img 的 AltText 按原版归一 shape 的 param 保留原值`() {
        // Utils.AddTagMixedNumArg（Utils.cs:364-371）：num == 0 的属性不写
        val a = hpRows("<img src='a' width='0px' height='10'>")[0].runs[0].text
        // width 是 0 → 丢弃；height 无 px → 10 * FontSize(18) / 100 = 1（Utils.cs:87-98）
        assertEquals("<img src='a' height='1'>", a)
        // shape：type 小写、param 用 ", " 分隔（ConsoleShapePart.cs:14-31）；
        // param 文案**直接拼原值**（:20-31，其中 :22 `param[i].num.ToString()`，isPx 才追 "px"），
        // 不做 `num * FontSize / 100` 归一 —— 归一只是 img 那支（Utils.MixedNum.BuilderString，
        // Utils.cs:87-98；CreateShape 压根没走它）。故 100/200/1 原样回显，不折成 18/36/0。
        // PC 基准同证：pc-golden-root.txt:24 的 100 条 `<shape type='rect' param='0, 30, 24, 64'`
        // 是原值；按归一写法应成 `0, 5, 4, 11`。
        val b = hpRows("<shape type='RECT' param='100,200,1'>")[0].runs[0].text
        assertEquals("<shape type='rect' param='100, 200, 1'>", b)
        // color 一旦设过就写（colorchanged：ConsoleShapePart.cs:15 折值 / :33-38 落笔）
        val c = hpRows("<shape type='space' param='10px' color='#123456'>")[0].runs[0].text
        assertEquals("<shape type='space' param='10px' color='#123456'>", c)
        // bcolor 等于 Config.FocusColor（缺省 0xFFFF00，ConfigData.cs:70）时省略（:39-44）
        val d = hpRows("<shape type='space' param='10px' bcolor='yellow'>")[0].runs[0].text
        assertEquals("<shape type='space' param='10px'>", d)
    }

    @Test
    fun `语料 BODYGRAPH 的 div 里嵌套 p align right`() {
        // ERB/BODY_INFO/PRINT_STATE_BODYGRAPH.ERB:113，{textboxWidth} 代入 320、%LOCALS% 代入 HP/MP
        val html = "<div width='320px' height='2000' xpos='0' ypos='0'>" +
            "<p align='right'>HP/MP</p></div>"
        val row = hpRows(html).single()
        val div = row.runs.single()
        assertEquals(HtmlRunKind.DIV, div.kind)
        // 子解析是新的 HtmlAnalzeState（HM :537-544）：LineHead 重置为 true 才能开 <p>
        assertEquals(Alignment.RIGHT, div.divRows.single().align)
        assertEquals("HP/MP", div.divRows.single().visibleText)
        // div 内容不进父行的对齐状态
        assertEquals(Alignment.LEFT, row.align)
    }

    // ===== 7. 实体与注释 =====

    @Test
    fun `实体先还原再成段 注释整段吃掉`() {
        // HM :572 文本段先 Unescape；:577-587 注释跳过（连 --> 一起）
        assertEquals("<b>X", hpVisible("&lt;b&gt;X"))
        assertEquals(1, hpRows("&lt;b&gt;X").size)
        assertEquals(1, hpRows("&lt;b&gt;X")[0].runs.size) // 还原出来的 < 不再当标签
        assertEquals("AB", hpVisible("A<!-- note -->B"))
        assertEquals("A B", hpVisible("A&nbsp;B")) // HM :783-785：nbsp 还原成半角空格
        assertEquals("&#99999999;超过Unicode的范围(不支持代理对)",
            assertFailsWith<CodeEE> { htmlPrintDocument("&#99999999;") }.message)
    }

    // ===== 8. 接口回归：HTML_GETPRINTEDSTR 的行体回退 =====

    /** 只有 `text` 的控制台行（宿主没给行体 HTML）。 */
    private class HpHost(private val lines: List<ConsolePrintedLine>) : BuiltinHost {
        override val consoleLines: List<ConsolePrintedLine>? get() = lines
    }

    private class HpBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("HpBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    @Test
    fun `宿主只给 text 时 HTML_GETPRINTEDSTR 回退成 Escape text`() {
        // 原版每段都从节点重新拼并 Escape（HM :446-464），所以行体缺省应当回退到
        // Escape(visible)，而不是吐出空的 <nobr></nobr>。
        val store = VariableStore()
        val base = HpBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, HpHost(listOf(ConsolePrintedLine(text = "A&B"))))
        val got = (ctx.callFunction("HTML_GETPRINTEDSTR", listOf(Value.IntV(0))) as Value.StrV).v
        assertEquals("<p align='left'><nobr>A&amp;B</nobr></p>", got)
    }
}
