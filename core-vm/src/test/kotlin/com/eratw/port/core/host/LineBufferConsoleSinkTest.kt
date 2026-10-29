package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LineBufferConsoleSink] 的语义测试。
 *
 * 期望值全部由 C# 参考实现推导（EmueraConsole.cs / PrintStringBuffer.cs），
 * 不用本类实现回读当期望。
 */
class LineBufferConsoleSinkTest {

    // ------------------------------------------------------------------
    // PRINTN / 行计数
    // ------------------------------------------------------------------

    /** PRINTN 的 isLineEnd=false 行与下一次 flush 合并，LINECOUNT 不增（EmueraConsole.cs:2948-2977）。 */
    @Test
    fun printnMergesWithNextLineAndDoesNotCount() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("abc", false)
        s.newLine()

        assertEquals(0L, s.lineCount, "PRINTN 的逻辑行未结束，不应计数")
        assertEquals(1, s.snapshot().size)
        assertFalse(s.snapshot()[0].isLineEnd)
        assertEquals("abc", s.textOf(s.snapshot()[0]))

        s.print("def", true)
        s.newLine()

        assertEquals(1L, s.lineCount)
        assertEquals(1, s.snapshot().size, "第二次 flush 应并入上一行而不是新增显示行")
        val merged = s.snapshot()[0]
        assertTrue(merged.isLineEnd)
        assertEquals("abcdef", s.textOf(merged))
    }

    /** 普通 PRINT 每次换行只产生一个逻辑行（EmueraConsole.cs:2971-2977）。 */
    @Test
    fun normalPrintCountsOnePerLine() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("x")
        s.newLine()
        assertEquals(1L, s.lineCount)

        s.print("y")
        s.print("z")
        s.newLine()

        assertEquals(2L, s.lineCount)
        assertEquals(2, s.snapshot().size)
        assertEquals("yz", s.textOf(s.snapshot()[1]))
    }

    /** force flush 空缓冲会补一个空格行（EmueraConsole.cs:3375-3385）。 */
    @Test
    fun newLineOnEmptyBufferProducesSpaceLine() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.newLine()

        assertEquals(1L, s.lineCount)
        assertEquals(1, s.snapshot().size)
        assertEquals(" ", s.textOf(s.snapshot()[0]))
    }

    /** 文本内嵌 `\n`：先 flush 前缀，剩余部分继续留在缓冲（EmueraConsole.cs:3172-3187）。 */
    @Test
    fun embeddedNewlineFlushesPrefix() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a\nb")

        assertEquals(1, s.snapshot().size)
        assertEquals("a", s.textOf(s.snapshot()[0]))
        assertFalse(s.printingBufferEmpty)

        s.newLine()

        assertEquals(2, s.snapshot().size)
        assertEquals("b", s.textOf(s.snapshot()[1]))
        assertEquals(2L, s.lineCount)
    }

    /** 打印缓冲空态：flush 后必为空（PrintStringBuffer.cs:39-48、EmueraConsole.cs:273-278）。 */
    @Test
    fun printingBufferEmptyTracksPendingText() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        assertTrue(s.printingBufferEmpty)
        s.print("abc", false)
        assertFalse(s.printingBufferEmpty)
        s.newLine()
        assertTrue(s.printingBufferEmpty)
    }

    /**
     * clearBuffer 不重置 isLastLineEnd（PrintStringBuffer.cs:273-278），而 AppendButton/AppendPlainText
     * 也不改它（:103-134）。所以 PRINTN 之后直接 PRINTBUTTON，按钮行仍为未断行并并入上一逻辑行。
     */
    @Test
    fun printnContinuationSurvivesButtonWithoutTextAppend() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a", false)
        s.newLine()
        s.printButton("B", 1L)
        s.newLine()

        assertEquals(0L, s.lineCount)
        assertEquals(1, s.snapshot().size)
        assertEquals("aB", s.textOf(s.snapshot()[0]))
        assertEquals(listOf(LineBufferConsoleSink.Button(1, 2, 1L)), s.snapshot()[0].buttons)

        s.print("c", true) // 文本 Append 把 isLastLineEnd 置回 true
        s.newLine()

        assertEquals(1L, s.lineCount)
        assertEquals("aBc", s.textOf(s.snapshot()[0]))
    }

    // ------------------------------------------------------------------
    // 折行
    // ------------------------------------------------------------------

    /** 宽 10：5 个全角刚好一行，6 个折成 5 + 1。半角宽 1、全角宽 2。 */
    @Test
    fun wrapsFullWidthCharactersAtClientWidth() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("あああああ")
        s.newLine()
        assertEquals(1L, s.lineCount)
        assertEquals(1, s.snapshot().size)
        assertEquals("あああああ", s.textOf(s.snapshot()[0]))

        s.print("ああああああ")
        s.newLine()

        assertEquals(2L, s.lineCount, "折行只算一个逻辑行")
        assertEquals(3, s.snapshot().size)
        val first = s.snapshot()[1]
        val second = s.snapshot()[2]
        assertEquals("あああああ", s.textOf(first))
        assertEquals("あ", s.textOf(second))
        assertTrue(first.isLogicalLine, "段首行标记逻辑行起点")
        assertFalse(second.isLogicalLine)
        assertFalse(first.isLineEnd, "折行续行的 IsLineEnd=false")
        assertTrue(second.isLineEnd)
    }

    /** 宽 10：11 个半角字符折成 10 + 1。 */
    @Test
    fun wrapsHalfWidthCharactersAtClientWidth() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("abcdefghijk")
        s.newLine()

        assertEquals(2, s.snapshot().size)
        assertEquals("abcdefghij", s.textOf(s.snapshot()[0]))
        assertEquals("k", s.textOf(s.snapshot()[1]))
        assertEquals(1L, s.lineCount)
    }

    /** PRINTN 折行后仍不计数；下一次输入并入最后一段（不能把整段折行压成一行）。 */
    @Test
    fun wrappedPrintnStillDoesNotCountAndMergesOnlyLastSegment() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("ああああああ", false)
        s.newLine()

        assertEquals(0L, s.lineCount)
        assertEquals(2, s.snapshot().size)
        assertFalse(s.snapshot()[0].isLineEnd)
        assertFalse(s.snapshot()[1].isLineEnd)

        s.print("x", true)
        s.newLine()

        assertEquals(1L, s.lineCount)
        assertEquals(2, s.snapshot().size, "只合并最后一段，前一段保持独立显示行")
        assertEquals("あああああ", s.textOf(s.snapshot()[0]))
        assertEquals("あx", s.textOf(s.snapshot()[1]))
        assertTrue(s.snapshot()[1].isLineEnd)
    }

    // ------------------------------------------------------------------
    // 按钮区间
    // ------------------------------------------------------------------

    /** 同一行两个按钮按列序保存。 */
    @Test
    fun buttonsKeepColumnOrder() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.printButton("A", 1L)
        s.printButton("B", "two")
        s.newLine()

        val line = s.snapshot().single()
        assertEquals(
            listOf(
                LineBufferConsoleSink.Button(0, 1, 1L),
                LineBufferConsoleSink.Button(1, 2, "two"),
            ),
            line.buttons,
        )
        assertEquals("AB", s.textOf(line))
    }

    /**
     * 按钮跨折行时按原版 `DivideAt` 拆到两行（PrintStringBuffer.cs:222-249）：
     * 每行各一条 [LineBufferConsoleSink.Button]，value 相同，列号是行内列。
     */
    @Test
    fun buttonIsSplitAcrossWrappedLines() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("aaaaaaaaa") // 9 列
        s.printButton("abc", 7L)
        s.newLine()

        assertEquals(2, s.snapshot().size)
        val first = s.snapshot()[0]
        val second = s.snapshot()[1]
        assertEquals("aaaaaaaaaa", s.textOf(first), "按钮首字符 'a' 留在第一行第 10 列")
        assertEquals("bc", s.textOf(second))
        assertEquals(listOf(LineBufferConsoleSink.Button(9, 10, 7L)), first.buttons)
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 2, 7L)), second.buttons)
        assertFalse(first.isLineEnd)
        assertTrue(second.isLineEnd)
    }

    // ------------------------------------------------------------------
    // 删除 / 临时行 / 单行
    // ------------------------------------------------------------------

    /** CLEARLINE 删除逻辑行，折行续行一并删除（EmueraConsole.cs:3010-3051）。 */
    @Test
    fun deleteLineRemovesWholeLogicalLine() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("あああああ") // 1 行
        s.newLine()
        s.print("ああああああ") // 折成 2 行
        s.newLine()

        assertEquals(2L, s.lineCount)
        assertEquals(3, s.snapshot().size)

        s.deleteLine(1)

        assertEquals(1L, s.lineCount)
        assertEquals(1, s.snapshot().size, "删除 1 个逻辑行应连同其续行一起删掉")
        assertEquals("あああああ", s.textOf(s.snapshot()[0]))

        s.deleteLine(1)
        assertEquals(0L, s.lineCount)
        assertTrue(s.snapshot().isEmpty())
    }

    /** PRINTN 的未完成行删除后不扣 LINECOUNT。 */
    @Test
    fun deleteLineOnPartialLineDoesNotChangeCount() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("partial", false)
        s.newLine()
        assertEquals(0L, s.lineCount)

        s.deleteLine(1)

        assertEquals(0L, s.lineCount)
        assertTrue(s.snapshot().isEmpty())
    }

    /**
     * REUSELASTLINE：连续临时行互相替换（EmueraConsole.cs:2891-2893 删除上一条临时行，
     * :3053-3056 PrintSingleLine(temporary:true)）；正常输出会删掉临时行。
     */
    @Test
    fun temporaryLineReplacesPreviousTemporaryLine() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("base")
        s.newLine()
        s.printTemporaryLine("t1")

        assertEquals(2L, s.lineCount)
        assertEquals(2, s.snapshot().size)
        assertTrue(s.snapshot()[1].isTemporary)

        s.printTemporaryLine("t2")

        assertEquals(2L, s.lineCount, "替换临时行不应改变逻辑行数")
        assertEquals(2, s.snapshot().size)
        assertEquals("t2", s.textOf(s.snapshot()[1]))
        assertTrue(s.snapshot()[1].isTemporary)

        s.print("real")
        s.newLine()

        assertEquals(2L, s.lineCount)
        assertEquals(2, s.snapshot().size)
        assertEquals("real", s.textOf(s.snapshot()[1]))
        assertFalse(s.snapshot()[1].isTemporary)
    }

    /** PRINTSINGLE：先 flush 待决缓冲，再输出一条不折行的独立行（EmueraConsole.cs:3147-3165）。 */
    @Test
    fun singleLineFlushesPendingAndDoesNotWrap() {
        val s = LineBufferConsoleSink(clientWidth = 5)

        s.print("a")
        s.printSingleLine("abcdefg")

        assertEquals(2, s.snapshot().size)
        assertEquals("a", s.textOf(s.snapshot()[0]))
        assertEquals("abcdefg", s.textOf(s.snapshot()[1]), "PrintSingleLine 走 nobr，不折行")
        assertEquals(2L, s.lineCount)
    }

    // ------------------------------------------------------------------
    // nobr：FlushSingleLine 的三条路径（PRINTSINGLE / REUSELASTLINE / 错误行）
    //   原版 nobr 是 Flush 的实参（PrintStringBuffer.cs:173 vs :182），折行判定读它（:217）；
    //   ConsoleDisplayLine 里没有这个字段（ConsoleDisplayLine.cs:8-48），所以本仓必须
    //   自己带出来（DisplayLine.noBreak），否则渲染层 LineFolder 会把超宽行二次拆开。
    // ------------------------------------------------------------------

    /** 普通 PRINT 折出来的行不带 noBreak（PrintStringBuffer.cs:182 的 `nobr: false`）。 */
    @Test
    fun normalPrintLineDoesNotCarryNoBreak() {
        val s = LineBufferConsoleSink(clientWidth = 5)
        s.print("abcdefghi")
        s.newLine()

        assertEquals(2, s.snapshot().size, "夹具自检：折成 5 + 4")
        assertTrue(s.snapshot().none { it.noBreak }, "普通 flush 的 nobr=false")
    }

    /**
     * PRINTSINGLE / REUSELASTLINE / 错误行都带 `noBreak`：三者都走
     * `FlushSingleLine(nobr: true)`（PrintStringBuffer.cs:170-177 的 `:173`）——
     * PRINTSINGLE 见 EmueraConsole.cs:3147-3165 → `BufferToSingleLine` :3339-3353、
     * REUSELASTLINE 见 :3053-3056、错误行见 :3098-3115 → `PrintPlainwithSingleLine` :3361-3373。
     */
    @Test
    fun singleLineTemporaryLineAndErrorCarryNoBreak() {
        val single = LineBufferConsoleSink(clientWidth = 5)
        single.printSingleLine("abcdefghi")
        assertEquals(1, single.snapshot().size)
        assertTrue(single.snapshot()[0].noBreak, "PRINTSINGLE 走 FlushSingleLine(nobr: true)")

        val temporary = LineBufferConsoleSink(clientWidth = 5)
        temporary.printTemporaryLine("abcdefghi")
        assertEquals(1, temporary.snapshot().size)
        assertTrue(temporary.snapshot()[0].isTemporary)
        assertTrue(temporary.snapshot()[0].noBreak, "REUSELASTLINE 是 PrintSingleLine(temporary:true)")

        val error = LineBufferConsoleSink(clientWidth = 5)
        error.printError("abcdefghi")
        assertEquals(1, error.snapshot().size)
        assertEquals(LineBufferConsoleSink.Kind.ERROR, error.snapshot()[0].segments.single().kind)
        assertTrue(error.snapshot()[0].noBreak, "PrintPlainwithSingleLine → FlushSingleLine(nobr: true)")

        // 标记是逐行的，不是全局态：同一条 sink 之后的普通行照常折行。
        single.print("abcdefghi")
        single.newLine()
        assertEquals(3, single.snapshot().size, "6 个以上字符按 5 格折成 2 行")
        assertFalse(single.snapshot().last().noBreak)
    }

    /** 独立 HTML 行不带 noBreak：原版的 nobr 来自 HTML 的 `<nobr>` 标签（HtmlManager.cs:687-698），本类不解析。 */
    @Test
    fun standaloneHtmlLineDoesNotCarryNoBreak() {
        val s = LineBufferConsoleSink(clientWidth = 5)
        s.printHtml("<b>abcdefghi</b>", false)

        assertEquals(1, s.snapshot().size)
        assertFalse(s.snapshot()[0].noBreak, "TODO(verify)：HTML 的 FlagNobr 未解析，按 false")
    }

    // ------------------------------------------------------------------
    // 样式快照
    // ------------------------------------------------------------------

    /** SETCOLOR/FONTSTYLE/SETFONT 只影响后续文本段（ConsoleStyledString.cs:26-43）。 */
    @Test
    fun styleChangesOnlyAffectFollowingSegments() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 开 `UseUserStyle` 轴：出厂 false（EmueraConsole.cs:467 的 bool 默认值），
        // 由 PRINT 系语句写真（FunctionIdentifier.cs:179/:276、Process.cs:1110/:1129/:1149）。
        // 本用例直接驱动 sink 的样式 API 再打印，等价于「站在 PRINT 语句里」。
        s.setUseUserStyle(true)

        s.setForeColor(255, 0, 0)
        s.print("A")
        s.setForeColor(0, 0, 255)
        s.setFontStyle(1)
        s.setFont("TestFont")
        s.print("B")
        s.newLine()

        val line = s.snapshot().single()
        assertEquals(2, line.segments.size)
        assertEquals("A", line.segments[0].text)
        assertEquals(0xFF0000, line.segments[0].foreColor)
        assertEquals(0, line.segments[0].styleMask)
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, line.segments[0].fontName)
        assertEquals("B", line.segments[1].text)
        assertEquals(0x0000FF, line.segments[1].foreColor)
        assertEquals(1, line.segments[1].styleMask)
        assertEquals("TestFont", line.segments[1].fontName)
        assertEquals(1, s.fontStyle)
    }

    /** 背景色与对齐方式按行快照（Process.cs:1535-1552、EmueraConsole.cs:2844-2858）。 */
    @Test
    fun lineSnapshotsBackgroundAndAlignment() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.setBackColor(0x11, 0x22, 0x33)
        s.setAlignment(Alignment.RIGHT)
        s.print("right")
        s.newLine()

        val right = s.snapshot().single()
        assertEquals(0x112233, right.backColor)
        assertEquals(Alignment.RIGHT, right.alignment)

        s.resetBackColor()
        s.setAlignment(Alignment.LEFT)
        s.print("left")
        s.newLine()

        val left = s.snapshot()[1]
        assertEquals(LineBufferConsoleSink.DEFAULT_BACK_COLOR, left.backColor)
        assertEquals(Alignment.LEFT, left.alignment)
    }

    // ------------------------------------------------------------------
    // PRINTC
    // ------------------------------------------------------------------

    /** 默认宽度 = Config.PrintCLength 25（ConfigData.cs:64）。 */
    @Test
    fun printCDefaultWidthIsConfigPrintCLength() {
        assertEquals(25, LineBufferConsoleSink().printCWidth)
    }

    /** 右对齐补前导空格、左对齐补尾部空格；都不截断超宽文本（EmueraConsole.cs:3247-3291）。 */
    @Test
    fun printCPadsButDoesNotTruncate() {
        val s = LineBufferConsoleSink(clientWidth = 100, printCWidth = 10)

        s.printC("ab", true)
        s.newLine()
        assertEquals("        ab", s.textOf(s.snapshot()[0]))
        assertEquals(10, s.textOf(s.snapshot()[0]).length)

        s.printC("ab", false)
        s.newLine()
        assertEquals("ab        ", s.textOf(s.snapshot()[1]))
        assertEquals(10, s.textOf(s.snapshot()[1]).length)

        s.printC("あ", true)
        s.newLine()
        assertEquals("        あ", s.textOf(s.snapshot()[2]))

        s.printC("abcdefghijkl", true)
        s.newLine()
        assertEquals("abcdefghijkl", s.textOf(s.snapshot()[3]), "超宽文本按原版不截断")

        s.printC("abcdefghijkl", false)
        s.newLine()
        assertEquals("abcdefghijkl", s.textOf(s.snapshot()[4]))
    }

    /** PRINTBUTTONC 的按钮文本同样定宽，按钮列区间覆盖补齐后的宽度。 */
    @Test
    fun printButtonCUsesPrintCWidth() {
        val s = LineBufferConsoleSink(clientWidth = 100, printCWidth = 10)

        s.printButtonC("ab", 5L, true)
        s.newLine()

        val line = s.snapshot().single()
        assertEquals("        ab", s.textOf(line))
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 10, 5L)), line.buttons)
    }

    /**
     * PRINTC 走 `Append(..., force_button: true)`（EmueraConsole.cs:3229-3235）：
     * 追加前先把已缓冲文本转成按钮、追加后再转一次（PrintStringBuffer.cs:73-76、:97-100），
     * 所以它的 `[n]` 只在本段内配对，前面的普通文本不可点。
     *
     * 旧实现把两段并成同一 cssList，`ButtonStringCreator` 的「整串只有一个按钮」分支
     * （ButtonStringCreator.cs:63-73）会让可点区从列 0 开始，把 "abc" 也吞进去。
     */
    @Test
    fun printCStartsItsOwnAutomaticButtonGroup() {
        val s = LineBufferConsoleSink(clientWidth = 80, printCWidth = 10)

        s.print("abc")
        s.printC("[1]", alignRight = false)
        s.newLine()

        val line = s.snapshot().single()
        assertEquals("abc[1]       ", s.textOf(line))
        assertEquals(listOf(LineBufferConsoleSink.Button(3, 13, 1L)), line.buttons)
        assertEquals(3, line.buttons.single().startCol, "按钮必须从 PRINTC 起始列开始，而非列 0")
    }

    // ------------------------------------------------------------------
    // PLAIN / HTML / ERROR
    // ------------------------------------------------------------------

    /** PRINTPLAIN 保留当前 StringStyle（Process.cs:1147-1154），但以 Kind.PLAIN 区分。 */
    @Test
    fun plainUsesCurrentStyleAndIsDistinguishable() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 开 `UseUserStyle` 轴（PRINTPLAIN 的语句层同样写真，Process.cs:1149）；
        // 不开轴则 Style 取值器走 defaultStyle（EmueraConsole.cs:471-472），
        // 「保留当前 StringStyle」这条断言无法被证伪。
        s.setUseUserStyle(true)

        s.setForeColor(1, 2, 3)
        s.printPlain("plain")
        s.newLine()

        val seg = s.snapshot().single().segments.single()
        assertEquals(LineBufferConsoleSink.Kind.PLAIN, seg.kind)
        assertEquals(0x010203, seg.foreColor)
    }

    /** HTML_PRINT 保留原文与 option 标志；option=false 先 flush 再独立成行。 */
    @Test
    fun htmlKeepsRawTextAndOptionFlag() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.printHtml("<b>x</b>", true)
        assertFalse(s.printingBufferEmpty)
        s.newLine()

        val buffered = s.snapshot().single().segments.single()
        assertEquals(LineBufferConsoleSink.Kind.HTML, buffered.kind)
        assertEquals("<b>x</b>", buffered.text)
        assertTrue(buffered.htmlOption)

        s.print("A")
        s.printHtml("<i>y</i>", false)

        assertEquals(3, s.snapshot().size, "独立 HTML 行前应先 flush 普通缓冲")
        assertEquals("A", s.textOf(s.snapshot()[1]))
        val direct = s.snapshot()[2].segments.single()
        assertEquals(LineBufferConsoleSink.Kind.HTML, direct.kind)
        assertEquals("<i>y</i>", direct.text)
        assertFalse(direct.htmlOption)
    }

    /** HTML 浮岛原样保存/清空。 */
    @Test
    fun htmlIslandsCanBeStoredAndCleared() {
        val s = LineBufferConsoleSink()

        s.addHtmlIsland("<div>1</div>")
        s.addHtmlIsland("<div>2</div>")
        assertEquals(listOf("<div>1</div>", "<div>2</div>"), s.htmlIslands())

        s.clearHtmlIslands()
        assertTrue(s.htmlIslands().isEmpty())
    }

    // ------------------------------------------------------------------
    // HTML 直印的结构切行（GFX-03：br/div 多行语义）
    // ------------------------------------------------------------------

    /**
     * `<br>` 断行：`A<br><br>B` → A / 空 / B 三行。
     *
     * C# 的 null 分段语义：每个 br 都把当前累积内容落成一条行（空也落，空行是**有意的**
     * 显示行），HtmlManager.cs:650-658 追加 null、PrintStringBuffer.cs:209-215 遇 null 落行；
     * 组内首行 IsLogicalLine=true、其余 false（PrintStringBuffer.cs:206/:212/:242），
     * 行 IsLineEnd 全 true（ConsoleDisplayLine.cs:32/:47 构造缺省）。
     */
    @Test
    fun htmlBrSplitsIntoMultipleDisplayLines() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("A<br><br>B", false)

        val rows = s.snapshot()
        assertEquals(3, rows.size)
        assertEquals("A", s.textOf(rows[0]))
        assertEquals("", s.textOf(rows[1]), "连续 br 中间是一条空显示行")
        assertEquals("B", s.textOf(rows[2]))
        assertTrue(rows[0].isLogicalLine)
        assertFalse(rows[1].isLogicalLine, "只有组内首行是逻辑行首（PrintStringBuffer.cs:206）")
        assertFalse(rows[2].isLogicalLine)
        assertTrue(rows.all { it.isLineEnd }, "直印行 IsLineEnd 构造缺省 true（ConsoleDisplayLine.cs:47）")
        assertEquals(3L, s.lineCount, "三条已完成的逻辑行各计 1")
        rows.forEach { line ->
            assertTrue(line.segments.all { it.kind == LineBufferConsoleSink.Kind.HTML && !it.htmlOption })
        }
    }

    /**
     * br 的边缘：行尾 br 不产**尾随**空行（尾行判据是 list.Count > 0，HtmlManager.cs:675-678）；
     * 行首 br 产一条空行；大小写不敏感（原版 tagAnalyze 里 text.ToLower 比较）。
     */
    @Test
    fun htmlTrailingLeadingAndCaseInsensitiveBr() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("A<br>", false)
        assertEquals(1, s.snapshot().size, "尾部 br 落完 A 行后没有剩余节点，不再产空行")
        assertEquals("A", s.textOf(s.snapshot()[0]))

        val s2 = LineBufferConsoleSink(clientWidth = 80)
        s2.printHtml("<br>A", false)
        assertEquals(2, s2.snapshot().size, "行首 br 先落一条空行")
        assertEquals("", s2.textOf(s2.snapshot()[0]))
        assertEquals("A", s2.textOf(s2.snapshot()[1]))

        val s3 = LineBufferConsoleSink(clientWidth = 80)
        s3.printHtml("X<BR>Y", false)
        assertEquals(2, s3.snapshot().size)
        assertEquals("X", s3.textOf(s3.snapshot()[0]))
        assertEquals("Y", s3.textOf(s3.snapshot()[1]))
    }

    /** 串内换行符同 br（HtmlManager.cs:535 HasReturn、:588-592 置 FlagBr）；CRLF 的 \r 剥掉。 */
    @Test
    fun htmlNewlineInStringSplitsLikeBr() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("A\n\nB", false)
        assertEquals(3, s.snapshot().size)
        assertEquals("A", s.textOf(s.snapshot()[0]))
        assertEquals("", s.textOf(s.snapshot()[1]))
        assertEquals("B", s.textOf(s.snapshot()[2]))

        val crlf = LineBufferConsoleSink(clientWidth = 80)
        crlf.printHtml("A\r\nB", false)
        assertEquals(2, crlf.snapshot().size, "\\r 留在行尾会画出脏字符，随 \\n 一起剥")
        assertEquals("A", crlf.textOf(crlf.snapshot()[0]))
        assertEquals("B", crlf.textOf(crlf.snapshot()[1]))
    }

    /**
     * 属性值里的 br 不切行：原版属性是字符串字面量（tagAnalyze 的 LiteralStringWord），
     * `title='a<br>b'` 不参与主循环的标签扫描；引号内的 `>` 同理不提前收尾标签。
     */
    @Test
    fun htmlBrInsideAttributeDoesNotSplit() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("<button title='a<br>b'>X</button>", false)
        assertEquals(1, s.snapshot().size)
        assertEquals("<button title='a<br>b'>X</button>", s.snapshot()[0].segments.single().text)

        val s2 = LineBufferConsoleSink(clientWidth = 80)
        s2.printHtml("<button title='a>b'>X<br>Y</button>", false)
        assertEquals(2, s2.snapshot().size, "引号内的 > 不收尾标签，切行只认真正的 br")
        assertEquals("<button title='a>b'>X</button>", s2.snapshot()[0].segments.single().text)
        assertEquals("<button title='a>b'>Y</button>", s2.snapshot()[1].segments.single().text)
    }

    /**
     * 跨 br 的样式状态延续：HtmlAnalzeState 是串级状态机、br 不重置（HtmlManager.cs:92-151），
     * `GetSS` 每段读 FonttagList 栈顶（:128-150）。移植侧「行尾补闭合、行首重放」——
     * 渲染结果与状态延续等价（栈顶一帧决定样式）。
     */
    @Test
    fun htmlCrossBrStyleStateReplaysTags() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("<font color='#ff0000'><b>A<br>B</b></font>", false)

        val rows = s.snapshot()
        assertEquals(2, rows.size)
        assertEquals(
            "<font color='#ff0000'><b>A</b></font>",
            rows[0].segments.single().text,
            "行尾按开放栈逆序补闭合",
        )
        assertEquals(
            "<font color='#ff0000'><b>B</b></font>",
            rows[1].segments.single().text,
            "行首重放仍开放的标签（原文）",
        )
    }

    /**
     * div：开/闭是断行边界，内容行带 div 的 `color` 底色
     * （HtmlManager.cs:1225-1371、ConsoleDivPart.cs:68-70 的 backgroundColor）；
     * div 外的行保持控制台背景快照。
     */
    @Test
    fun htmlDivSplitsIntoColoredLines() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("<div color='#363636'>A<br>B</div>C", false)

        val rows = s.snapshot()
        assertEquals(3, rows.size)
        assertEquals("A", s.textOf(rows[0]))
        assertEquals(0x363636, rows[0].backColor, "div 内容行染 div 的 color")
        assertEquals("B", s.textOf(rows[1]))
        assertEquals(0x363636, rows[1].backColor)
        assertEquals("C", s.textOf(rows[2]), "div 之后的文本是普通行")
        assertEquals(LineBufferConsoleSink.DEFAULT_BACK_COLOR, rows[2].backColor)
    }

    /**
     * 语料的两栏 div 形态（TEMP_CHARA_LIST_BY_DT 系）：内容与顺序保持、各自底色。
     * xpos/width 丢弃（登记分歧：浮动覆盖块在 [LineBufferConsoleSink.DisplayLine]
     * 线性行模型上没有落点，两栏并排退化为上下堆叠）。
     */
    @Test
    fun htmlDivTwoColumnsKeepsContentAndColors() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml(
            "<div xpos='0' width='10px' color='#111111'>L1<br>L2</div>" +
                "<div xpos='20px' color='#222222'>R1<br>R2</div>",
            false,
        )

        val rows = s.snapshot()
        assertEquals(4, rows.size, "两栏各 2 行：内容不塌行、顺序保持")
        assertEquals("L1", s.textOf(rows[0]))
        assertEquals(0x111111, rows[0].backColor)
        assertEquals("L2", s.textOf(rows[1]))
        assertEquals(0x111111, rows[1].backColor)
        assertEquals("R1", s.textOf(rows[2]))
        assertEquals(0x222222, rows[2].backColor, "第二个 div 换自己的底色")
        assertEquals("R2", s.textOf(rows[3]))
        assertEquals(0x222222, rows[3].backColor)
        // div 标签本身（含 xpos/width）从行文本剥除
        rows.forEach { line -> assertFalse(line.segments.single().text.contains("<div")) }
    }

    /** `HTML_PRINT ..., 1`（toPrintBuffer）不切行：原版走 Html2ButtonList 提前返回（HtmlManager.cs:679-682）。 */
    @Test
    fun htmlPrintToBufferDoesNotSplit() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("A<br>B", true)
        assertFalse(s.printingBufferEmpty)
        s.newLine()

        val rows = s.snapshot()
        assertEquals(1, rows.size, "缓冲路径整段原文一行")
        val seg = rows[0].segments.single()
        assertEquals("A<br>B", seg.text)
        assertTrue(seg.htmlOption)
    }

    /** PRINTN 未结行与 HTML 组的衔接：组首行并入未结行、其余行独立追加（EmueraConsole.cs:2877-2883 逐条 addDisplayLine）。 */
    @Test
    fun htmlDirectLinesJoinUnfinishedPrintLine() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.print("HEAD", false)
        s.printHtml("A<br>B", false)

        val rows = s.snapshot()
        assertEquals(2, rows.size)
        assertEquals("HEADA", s.textOf(rows[0]), "组首行并入 PRINTN 未结行")
        assertTrue(
            rows[0].isLineEnd,
            "并入行继承 HTML 行的 IsLineEnd=true：C# 把末行按钮拼进新行、新行的 IsLineEnd 不变" +
                "（EmueraConsole.cs:2950-2967，HTML 行构造缺省 true，ConsoleDisplayLine.cs:47）",
        )
        assertEquals("B", s.textOf(rows[1]))
        assertTrue(rows[1].isLineEnd)
        assertEquals(2L, s.lineCount, "并入行与 B 行各计 1（addDisplayLine 的 IsLogicalLine && IsLineEnd）")
    }

    /** PrintError 用默认样式、强制左对齐、单行不折行（EmueraConsole.cs:3098-3115）。 */
    @Test
    fun errorUsesDefaultStyleAndForcedLeftAlignment() {
        val s = LineBufferConsoleSink(clientWidth = 5)

        s.setForeColor(9, 9, 9)
        s.setFontStyle(3)
        s.setFont("Custom")
        s.setAlignment(Alignment.RIGHT)
        s.printError("abcdefg")

        val line = s.snapshot().single()
        val seg = line.segments.single()
        assertEquals(LineBufferConsoleSink.Kind.ERROR, seg.kind)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor)
        assertEquals(0, seg.styleMask)
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, seg.fontName)
        assertEquals(Alignment.LEFT, line.alignment)
        assertEquals("abcdefg", s.textOf(line), "错误行不折行")
        assertEquals(1L, s.lineCount)
    }

    // ------------------------------------------------------------------
    // 分隔线
    // ------------------------------------------------------------------

    /** DRAWLINE 用 '-' 填满宽度（ConfigData.cs:163、EmueraConsole.cs:3388-3393）。 */
    @Test
    fun printBarFillsClientWidth() {
        val s = LineBufferConsoleSink(clientWidth = 5)

        s.printBar()
        s.newLine()

        assertEquals("-----", s.textOf(s.snapshot().single()))
    }

    /** 自定义分隔线重复参数字符串并裁到宽度（EmueraConsole.cs:3417-3437）。 */
    @Test
    fun printCustomBarRepeatsAndTrims() {
        val s = LineBufferConsoleSink(clientWidth = 5)

        s.printCustomBar("ab")
        s.newLine()

        assertEquals("ababa", s.textOf(s.snapshot().single()))
        assertFailsWith<CodeEE> { s.printCustomBar("") }
    }

    /**
     * 分隔线在**黄金基准宽度**下的逐字读数（把 `getStBar` 与宽度表一起钉住）。
     *
     * 为什么必须单独立一条：`GoldenNormalizer.normalize` 从 START 标记起截取，还把纯 `=`、
     * 纯 `-` 的整行折成 `LINE(=)` / `LINE(-)` 哨兵 —— 基准 `pc-golden-root.txt` 里
     * `:61`（99 个 `━`）与 `:253` `:254`（各 199 个 `=` / `-`）这三类**全角/半角宽度敏感的
     * 行根本不进 E2E 的 610 行对拍**。也就是说宽度表换成什么口径，E2E 都看不出来，
     * 只能靠这里手算钉死。
     *
     * 算式（单位换成半角格）：可绘制宽度 1797px（`Config.cs:461-466`：`WINDOW WIDTH 1800`
     * 减 `max(2, FontSize/6)`，语料 `emuera.config:12` 与 `:22`），半角步进 9px
     * （由基准 `:253` 的 199 个 `=` 唯一确定：`199*9 = 1791 <= 1797 < 1800 = 200*9`）
     * ⇒ 可绘制宽度 = **199 个半角格**。
     * - `=` / `-` 半角 1 格 ⇒ 199 字（基准 `:253` `:254` 实测 199 字）。
     * - `━`(U+2501) 是 CP932 双字节 ⇒ 2 格 ⇒ 追加到 `2*100 = 200 > 199` 后逐字删回
     *   `2*99 = 198 <= 199` ⇒ **99 字**，与基准 `:61` 的 99 个 `━` 一致
     *   （`ERB/TITLE.ERB:62` 的 `CUSTOMDRAWLINE ━`）。这就是宽度表必须按
     *   Shift-JIS 字节数、不能按 East Asian Width 的实测依据（另见
     *   `ConsoleDataSourceTest.cellWidthFollowsSjisByteCountNotEastAsianWidth`）。
     */
    @Test
    fun drawLineBarMatchesGoldenWidths() {
        val s = LineBufferConsoleSink(clientWidth = 199)
        s.printCustomBar("━")
        s.newLine()
        val heavy = s.textOf(s.snapshot().single())
        assertEquals(99, heavy.length, "基准:61 实测 99 个 ━")
        assertTrue(heavy.all { it == '━' })

        val s2 = LineBufferConsoleSink(clientWidth = 199)
        s2.printCustomBar("=")
        s2.newLine()
        assertEquals(199, s2.textOf(s2.snapshot().single()).length, "基准:253 实测 199 个 =")

        val s3 = LineBufferConsoleSink(clientWidth = 199)
        s3.printBar()
        s3.newLine()
        assertEquals(199, s3.textOf(s3.snapshot().single()).length, "基准:254 实测 199 个 -（DRAW_LINE 未配置，回退 - 见 Config.cs:725-729）")
    }

    /**
     * 纯图行（只有 img/shape、无可见文本）必须留行：C# HtmlManager.cs:279-280 把
     * img/shape 与 br 同列特殊处理，产 ConsoleImagePart / ConsoleShapePart **节点**，
     * HtmlManager.cs:675-678 的 `list.Count > 0` 因此成立。移植侧尾行判据若只查
     * 「可见文本非空」会把整行丢掉（屏上图整行消失的回归），图形标签必须计为节点。
     */
    @Test
    fun htmlPureGraphicLineKeepsRowWithoutVisibleText() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        s.printHtml("<img src='a' width='60px' height='30px'>", false)
        val lines = s.snapshot()
        assertEquals(1, lines.size, "纯 img 行无可见文本也必须留行（img/shape 是节点）")
        assertTrue(s.textOf(lines.single()).contains("<img"), "行原文保留（展平归 render）")

        val s2 = LineBufferConsoleSink(clientWidth = 80)
        s2.printHtml("<shape type='rect' color='#363636' width='60px' height='10px'>", false)
        assertEquals(1, s2.snapshot().size, "纯 shape 行同样留行")
    }
}
