package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自动按钮切分器（`[123]`）的逐值尺子 —— M6-RENDER-AUDIT 登记的第三条缺口。
 *
 * 期望值全部由 C# 参考实现手算：
 * - `ButtonStringCreator.cs:11` 的数字正则（`[` + 可选 0x/0b 前缀 + 可选符号 + 十进制 +
 *   可选 e/E/p/P 指数 + `]`）；
 * - `:29-161` 的 `syn`：`:36-37` 前置剪枝、`:38-39` 词法失败即整串、
 *   `:45-62` 计数（`num` / 前导正文 `flag` / 尾随正文 `flag2`）、
 *   `:63-73` 的 `num <= 1` 整串分支、`:74-135` + `:145-160` 的多按钮分派；
 * - `:194-248` 的 `lex`（`[` `]` 与空白切词，嵌套/落单括号返回 null）；
 * - `LexicalAnalyzer.cs:30-97` 的 `ReadInt64`（`0x`/`0b`、正负号、`e`/`p` 指数）。
 *
 * 每条用例除了切分结果，还钉住**覆盖性**（各片拼回去逐字等于入参），因为核心层要靠
 * 这个不变量把切片按字符区间映射回样式段（`LineBufferConsoleSink` 的 `splitTextGroup`）。
 */
class ButtonStringCreatorTest {

    private fun piece(text: String, input: Long?) = ButtonStringCreator.ButtonPrimitive(text, input != null, input ?: 0L)

    /** 无数字方括号 / 落单括号 / 非数字内容 ⇒ 一条不可选 primitive，文本一字不改。 */
    @Test
    fun `非数字方括号不产生按钮`() {
        for (text in listOf("abc", "[abc]", "[1.5]", "[]", "[ ]", "半截[12", "落单]12[", "a[1[b]")) {
            assertEquals(listOf(piece(text, null)), ButtonStringCreator.splitButton(text), text)
        }
    }

    /**
     * `num <= 1`（`ButtonStringCreator.cs:63-73`）：一行里只有一个数字方括号时，
     * **整串**是一条可选段，`Input` 就是括号里的数 —— 前导/尾随正文一起可点。
     */
    @Test
    fun `单个数字方括号把整串变成一条可选段`() {
        assertEquals(listOf(piece("按[123]确认", 123L)), ButtonStringCreator.splitButton("按[123]确认"))
        assertEquals(listOf(piece("abc[7]", 7L)), ButtonStringCreator.splitButton("abc[7]"))
        assertEquals(listOf(piece("[7]", 7L)), ButtonStringCreator.splitButton("[7]"))
        assertEquals(0L, ButtonStringCreator.splitButton("[0]").single().input)
    }

    /** `num >= 2`：每段正文粘在**它前面**那个按钮上（flag3/flag4 的 reduce 顺序，`:104-135`）。 */
    @Test
    fun `多个数字方括号按驻留正文切成多条可选段`() {
        assertEquals(
            listOf(piece("红[1]", 1L), piece("蓝[2]", 2L)),
            ButtonStringCreator.splitButton("红[1]蓝[2]"),
        )
        assertEquals(
            listOf(piece("红[1]确认", 1L), piece("[2]完毕", 2L)),
            ButtonStringCreator.splitButton("红[1]确认[2]完毕"),
        )
        assertEquals(
            listOf(piece("[1]", 1L), piece("[2]", 2L)),
            ButtonStringCreator.splitButton("[1][2]"),
        )
        // 纯空白 token 不改变按钮计数；只在「缓冲区以可选段+正文结尾」时才触发 reduce（`:90-99`）
        assertEquals(
            listOf(piece("[1] x ", 1L), piece("[2]", 2L)),
            ButtonStringCreator.splitButton("[1] x [2]"),
        )
    }

    /** 数值口径：`0x` / `0b` / 正负号 / 空格 / `e` 指数都按 `ReadInt64` 解析（`LexicalAnalyzer.cs:30-97`）。 */
    @Test
    fun `方括号里的数值按 ReadInt64 解析`() {
        assertEquals(18L, ButtonStringCreator.splitButton("[0x12]").single().input, "0x 前缀 + 纯十进制数字")
        // 正则的数字段是 `[0-9]+`（ButtonStringCreator.cs:11）⇒ 含十六进制字母的 token 不是按钮
        assertEquals(listOf(piece("[0x1A]", null)), ButtonStringCreator.splitButton("[0x1A]"))
        assertEquals(5L, ButtonStringCreator.splitButton("[0b101]").single().input)
        assertEquals(-5L, ButtonStringCreator.splitButton("[-5]").single().input)
        assertEquals(5L, ButtonStringCreator.splitButton("[+5]").single().input)
        assertEquals(12L, ButtonStringCreator.splitButton("[ 12 ]").single().input)
        assertEquals(12L, ButtonStringCreator.splitButton("[\u300012\u3000]").single().input, "全角空格也算空白（IsWhiteSpace，:638-644）")
        assertEquals(1000L, ButtonStringCreator.splitButton("[1e3]").single().input, "e 指数按 10 的幂")
        assertEquals(64L, ButtonStringCreator.splitButton("[0x10p2]").single().input, "p 指数按 2 的幂")
    }

    /** 覆盖性不变量：各片按序拼回去逐字等于入参（核心层按字符区间回填样式靠它）。 */
    @Test
    fun `切分结果逐字覆盖入参`() {
        val samples = listOf(
            "按[123]确认", "红[1]蓝[2]", "红[1]确认[2]完毕", "[1][2]", "[1] x [2]",
            "a[0x1A]b[0b11]c", "x[1] y[2] z", "[1] [2] [3] [4]", "abc", "[abc]",
            "※※※已创建Log文件GOLDEN_LOG.txt※※※", " 快Ｃ[..........]     0",
        )
        for (text in samples) {
            val pieces = ButtonStringCreator.splitButton(text)
            assertEquals(text, pieces.joinToString("") { it.text }, text)
            assertTrue(pieces.isNotEmpty(), text)
        }
        // 「快Ｃ[..........]     0」里的方括号内容不是数字 ⇒ 不可选
        assertTrue(ButtonStringCreator.splitButton(" 快Ｃ[..........]     0").none { it.canSelect })
    }

    // ------------------------------------------------------------------
    // 接线：核心层把可选片落成 Kind.BUTTON 段 + buttons 区间
    // ------------------------------------------------------------------

    /** 普通打印：`DisplayLine.buttons` 里出现没有显式 PRINTBUTTON 的区间，列号按半角格。 */
    @Test
    fun `普通打印的自动按钮落进 buttons 表`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("按[123]确认")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("按[123]确认"), line.segments.map { it.text })
        assertEquals(LineBufferConsoleSink.Kind.BUTTON, line.segments.single().kind)
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 11, 123L)), line.buttons)
    }

    /** 多条：并成一条 BUTTON 段，但 `buttons` 仍是两条区间（列号 2+3 / 2+3）。 */
    @Test
    fun `多条自动按钮的列区间各自独立`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("红[1]蓝[2]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("红[1]蓝[2]"), line.segments.map { it.text })
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 5, 1L), LineBufferConsoleSink.Button(5, 10, 2L)),
            line.buttons,
        )
    }

    /** PRINTSINGLE 也吃自动按钮（`FlushSingleLine` 先 `fromCssToButton`，`PrintStringBuffer.cs:170-177`）。 */
    @Test
    fun `PRINTSINGLE 的文本同样切出自动按钮`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printSingleLine("继续[0]")
        val line = s.snapshot().single()
        assertEquals(listOf("继续[0]"), line.segments.map { it.text })
        assertEquals(LineBufferConsoleSink.Kind.BUTTON, line.segments.single().kind)
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 7, 0L)), line.buttons)
    }

    /** PRINTPLAIN 不切（`createPlainButton`，`PrintStringBuffer.cs:125-134`、`:318-324`）。 */
    @Test
    fun `PRINTPLAIN 不产生自动按钮`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printPlain("按[9]继续")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("按[9]继续"), line.segments.map { it.text })
        assertEquals(LineBufferConsoleSink.Kind.PLAIN, line.segments.single().kind)
        assertTrue(line.buttons.isEmpty())
    }

    /** 自动按钮跨行：每行一条区间、值相同（`ConsoleButtonString.DivideAt` 抄 `IsButton`/`Input`，:240-251）。 */
    @Test
    fun `跨行的自动按钮每行一条区间`() {
        val s = LineBufferConsoleSink(clientWidth = 4)
        s.print("ab[12345]")
        s.newLine()
        assertEquals(listOf("ab[1", "2345", "]"), s.loggedLines())
        assertEquals(
            listOf(
                LineBufferConsoleSink.PlacedButton(0, 0, 4, 12345L),
                LineBufferConsoleSink.PlacedButton(1, 0, 4, 12345L),
                LineBufferConsoleSink.PlacedButton(2, 0, 1, 12345L),
            ),
            s.buttons(),
        )
    }

    /**
     * 跨样式段：`[123]` 被两次 PRINT 切成两段样式（原版在同一份 cssList 上拼串后再切，
     * `PrintStringBuffer.cs:326-333`），本仓按字符区间把样式回填，颜色不动、值只有一个。
     */
    @Test
    fun `数字方括号跨样式段时仍算一个按钮`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 开 `UseUserStyle` 轴：出厂 false（EmueraConsole.cs:467），只有 PRINT 系语句写真
        // （FunctionIdentifier.cs:179/:276、Process.cs:1110/:1129/:1149）。本用例手动改前景色
        // 再打印，等价于「站在 PRINT 语句里」；不开轴两段会同色、被并成一段。
        s.setUseUserStyle(true)
        s.setForeColor(255, 0, 0)
        s.print("按[12")
        s.setForeColor(0, 255, 0)
        s.print("3]确认")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("按[12", "3]确认"), line.segments.map { it.text }, "样式不同 ⇒ 两段")
        assertEquals(listOf(0xFF0000, 0x00FF00), line.segments.map { it.foreColor })
        assertEquals(
            listOf(LineBufferConsoleSink.Kind.BUTTON, LineBufferConsoleSink.Kind.BUTTON),
            line.segments.map { it.kind },
            "整串只有一个数字 ⇒ 两段都是可选段",
        )
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 11, 123L)), line.buttons, "5 + 6 = 11 格")
    }

    /** 显式 PRINTBUTTON 与自动按钮混排时按列序各就各位。 */    @Test
    fun `显式按钮与自动按钮混排按列序记账`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printButton("A", 7L)
        s.print("中[3]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("A中[3]"), line.segments.map { it.text }, "同样式相邻 BUTTON 段并成一条（RowBuilder.put）")
        assertEquals(LineBufferConsoleSink.Kind.BUTTON, line.segments.single().kind)
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 1, 7L), LineBufferConsoleSink.Button(1, 6, 3L)),
            line.buttons,
            "A=1 格；中[3]=2+3=5 格",
        )
    }

    // ------------------------------------------------------------------
    // 条数口径：一条 ConsoleButtonString 就是一条（ConsoleDisplayLine.cs:18,28 的 buttons 数组）
    //   同一按钮的多个样式段装在 StrArray 里（ConsoleButtonString.cs:19、:296-303 的节点列推进），
    //   而两次 AppendButton 恒是两条（PrintStringBuffer.cs:103-123）。
    // ------------------------------------------------------------------

    /**
     * 「跨样式段同一按钮合并为一条」与「两个相邻同值显式按钮」的分界：
     * - 前者是**一个** `ConsoleButtonString`、`StrArray` 里两个样式节点（ConsoleButtonString.cs:19、
     *   :296-303）⇒ `buttons` 长度 1（ConsoleDisplayLine.cs:18,28）；
     * - 后者是两条 `ConsoleButtonString`（PrintStringBuffer.cs:103-123：每次 `AppendButton` 都
     *   `fromCssToButton()` 后再 `createButton`）⇒ 长度 2，即便值与列号都相接。
     */
    @Test
    fun `同值相邻的两次 PRINTBUTTON 是两条而跨样式自动按钮是一条`() {
        val explicit = LineBufferConsoleSink(clientWidth = 80)
        explicit.printButton("A", 7L)
        explicit.printButton("B", 7L)
        explicit.newLine()
        val two = explicit.snapshot().single()
        assertEquals(listOf("AB"), two.segments.map { it.text }, "同样式 ⇒ 仍并成一条 Segment")
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 1, 7L), LineBufferConsoleSink.Button(1, 2, 7L)),
            two.buttons,
            "两次 AppendButton 是两条 ConsoleButtonString，值相同也不并",
        )

        val auto = LineBufferConsoleSink(clientWidth = 80)
        // 开 `UseUserStyle` 轴（理由同上）：不开轴时两次 print 取出厂样式，
        // 段落不会被样式切开，「跨样式自动按钮是一条」这条断言就失去对照。
        auto.setUseUserStyle(true)
        auto.setForeColor(255, 0, 0)
        auto.print("按[12")
        auto.setForeColor(0, 255, 0)
        auto.print("3]确认")
        auto.newLine()
        val one = auto.snapshot().single()
        assertEquals(listOf("按[12", "3]确认"), one.segments.map { it.text }, "样式不同 ⇒ 两段")
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 11, 123L)),
            one.buttons,
            "同一 ConsoleButtonString 的两个样式节点 ⇒ 一条区间（5 + 6 = 11 格）",
        )
    }

    /** 显式按钮 + 紧随其后的**同值**自动按钮：列号相接、值相同，仍是两条。 */
    @Test
    fun `显式按钮与紧随的同值自动按钮仍是两条`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printButton("A", 7L)
        s.print("中[7]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("A中[7]"), line.segments.map { it.text })
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 1, 7L), LineBufferConsoleSink.Button(1, 6, 7L)),
            line.buttons,
            "PRINTBUTTON 一条、自动按钮一条：值与列号都相接也不许并",
        )
    }

    // ------------------------------------------------------------------
    // 跨 CSS 节点：createButtons 把 cssList 里所有节点的 Text 拼成一串再切
    //   （PrintStringBuffer.cs:326-345 的拼串 + :347-377 的按字符游标消费），
    //   不可拆节点（本仓 = HTML 段）在切点落进它内部时整节点吞进当前按钮（:358-374）。
    //   TODO(verify)：原版 HTML_PRINT 走 Html2ButtonList 不进 cssList（EmueraConsole.cs:3203-3216、
    //   PrintStringBuffer.cs:136-140），所以这条在基准里不可达；语料无 `[...]` 跨 HTML 实例。
    // ------------------------------------------------------------------

    /** 方括号整个落在纯文本里 ⇒ HTML 段不参与，一段文本自己切（既有行为不变）。 */
    @Test
    fun `自动按钮不跨 HTML 段当括号未跨越时`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("A[1]")
        s.printHtml("<b>x</b>", true)
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("A[1]", "<b>x</b>"), line.segments.map { it.text })
        assertEquals(
            listOf(LineBufferConsoleSink.Kind.BUTTON, LineBufferConsoleSink.Kind.HTML),
            line.segments.map { it.kind },
            "HTML 段保留原文与 Kind.HTML",
        )
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 4, 1L)), line.buttons, "A[1] = 4 格，HTML 不计入")
    }

    /**
     * 方括号**跨过** HTML 段：整组拼串（可见文本）再切，HTML 段被可选片吞掉后落成
     * 可见文本的 [LineBufferConsoleSink.Kind.BUTTON] 片，三片同属一个源按钮 ⇒ 一条区间。
     */
    @Test
    fun `自动按钮跨 HTML 段时整组拼串再切`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("按[1")
        s.printHtml("<b>2</b>", true)
        s.print("]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals("按[12]", s.textOf(line), "HTML 段按可见文本参与拼串")
        assertEquals(
            listOf(LineBufferConsoleSink.Kind.BUTTON),
            line.segments.map { it.kind }.distinct(),
            "整串可选的 num<=1 分支 ⇒ 全部落成按钮片（ButtonStringCreator.cs:63-73）",
        )
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 6, 12L)),
            line.buttons,
            "按=2、[=1、1=1、2=1、]=1 ⇒ 6 格",
        )
    }

    /**
     * 不可拆节点的「吞到段末」（PrintStringBuffer.cs:358-374）：切点落在 HTML 段内部时，
     * 当前按钮把**整个** HTML 段吃掉，下一个按钮从段后开始。
     *
     * 拼串 = `x[1` + `]y[` + `2]` = `x[1]y[2]`（`x[1` 与 `2]` 各自不配对 ⇒ 纯文本子组切不出按钮，
     * 整组切出 `[1]` 与 `[2]` 两个）；piece1 的名义切点是第 4 个字符，落在 HTML 段
     * （可见文本 `]y[`，列 3..6）内部 ⇒ 扩到 6，把 `]y[` 整段吞进第一条按钮。
     */
    @Test
    fun `跨 HTML 段的切点落在节点内部时吞到段末`() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("x[1")
        s.printHtml("<b>]y[</b>", true)
        s.print("2]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals("x[1]y[2]", s.textOf(line), "HTML 标签不参与可见文本")
        assertEquals(
            listOf(
                LineBufferConsoleSink.Button(0, 6, 1L),
                LineBufferConsoleSink.Button(6, 8, 2L),
            ),
            line.buttons,
            "第一条吞掉整段 `]y[`（3+3=6 格）；第二条是 `2]`（2 格）",
        )
    }
}
