package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 控制台「弹出显示行」族（`EmueraConsole.PopDisplayingLines`）与 PRINTD/PRINTDATAD 的
 * 前景色抑制轴（`Console.UseSetColorStyle`）的语义测试（M4-N `n-hostconsole`）。
 *
 * 期望值全部由 C# 反编译源推导，不用本仓实现回读当期望。出处集中在三处：
 * - `MinorShift.Emuera.GameView/EmueraConsole.cs:3578-3588`（PopDisplayingLines 方法体四句）、
 *   `:469` + `:471-489`（UseSetColorStyle 与 Style 取值器）、`:491`（StringStyle 就是 userStyle）；
 * - `MinorShift.Emuera.UI.Game/PrintStringBuffer.cs:179-186`（Flush：排版 + 改末行 IsLineEnd +
 *   clearBuffer）、`:273-278`（clearBuffer 清哪三样）、`:196-266`（ButtonsToDisplayLines 的行序与
 *   IsLogicalLine）、`:66-101`（Append 写 isLastLineEnd）；
 * - `MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs:6112-6130`（唯一消费者
 *   HTML_POPPRINTINGSTR：null 与空数组都转成 ""）。
 *
 * 行模型的另一半（弹出侧交出哪些字段、丢在哪一层）见
 * [LineBufferConsoleSink.popDisplayingLines] 的 KDoc，本类只钉可观测的那几条。
 */
class ConsolePopDisplayTest {

    /**
     * 宽 40 半角格：够放下本类所有用例，折行判据不参与。
     *
     * 同时把 `UseUserStyle` 这一轴打开：原版该字段出厂 false（`EmueraConsole.cs:467` 的
     * `bool` 默认值），只有 PRINT 系语句在求值前写回真（`FunctionIdentifier.cs:179`/`:276`、
     * `Process.cs:1110`/`:1129`/`:1149`）。本类直接驱动 sink 的样式 API 再打印，等价于
     * 「站在 PRINT 语句里」，故先开轴 —— 否则 `Style` 取值器会整条走
     * `!UseUserStyle → defaultStyle`（`EmueraConsole.cs:471-472`），样式快照永远出厂态，
     * 下面的断言就退化成空断言。
     */
    private fun wide(clientWidth: Int = 40) =
        LineBufferConsoleSink(clientWidth = clientWidth).also { it.setUseUserStyle(true) }

    // ------------------------------------------------------------------
    // 1. 空缓冲：返回空表，且一个字节的状态都不动
    // ------------------------------------------------------------------

    /**
     * 原版 `if (printBuffer.IsEmpty) return null`（EmueraConsole.cs:3584-3587）⇒ 函数侧 ""
     * （FunctionMethodCreator.cs:6123-6127）。基准取证 `pc-golden-root.txt:302`
     * `HTML_POPPRINTINGSTR=[]`（调用点在 PRINTFORML 之后，缓冲已空）。
     */
    @Test
    fun popOnEmptyBufferReturnsNothingAndChangesNoState() {
        val s = wide()
        val rev = s.revision
        assertEquals(emptyList(), s.popDisplayingLines(), "缓冲空 ⇒ 无行可交（:3584）")
        assertEquals(0L, s.lineCount, "LINECOUNT 不因弹出而变（:3588 之后才有排版，这里根本没排版）")
        assertEquals(0, s.displayLineCount)
        assertEquals(rev, s.revision, "没有可见内容变化 ⇒ 快照版本不涨")
        assertTrue(s.printingBufferEmpty, "LINEISEMPTY 的数据源同口径（PrintStringBuffer.cs:39-48）")
    }

    // ------------------------------------------------------------------
    // 2. 主判据：弹出的行不进显示行列表
    // ------------------------------------------------------------------

    /**
     * 原版方法体只有 `return printBuffer.Flush(...)`（EmueraConsole.cs:3588），
     * 既没有 `addRangeDisplayLine` 也没有 `displayLineList.AddRange`
     * （换行路径 NewLine→PrintFlush：EmueraConsole.cs:3335 → :3375-3385，落列表的是 :3384；
     *  HTML 路径 :3221-3224 同样显式进列表）；
     * `Flush` 只 `clearBuffer()`（PrintStringBuffer.cs:184 → :273-278）。
     * ⇒ 弹出的行**不上屏、不进日志、不算 LINECOUNT、后续 GETDISPLAYLINE 看不到**。
     */
    @Test
    fun poppedLinesDoNotEnterDisplayListOrLineCount() {
        val s = wide()
        s.printLine("已提交的一行")
        assertEquals(1L, s.lineCount)
        val revAfterCommit = s.revision

        s.print("还没换行的半行", lineEnd = false)
        assertFalse(s.printingBufferEmpty, "PRINTN 之后缓冲非空（PrintStringBuffer.cs:66-101 只 Append 不落行）")

        val popped = s.popDisplayingLines()
        assertEquals(1, popped.size, "非空 ⇒ 交出这次 flush 排出来的行（:3588）")
        assertEquals("还没换行的半行", s.textOf(popped[0]))

        assertEquals(1, s.displayLineCount, "弹出的行不进列表 ⇒ 列表里只有先前那一行")
        assertEquals(1L, s.lineCount, "LINECOUNT 不算弹出的行（addDisplayLine 才是唯一计数点）")
        assertEquals(listOf("已提交的一行"), s.loggedLines(), "弹出的行不进日志")
        assertEquals(revAfterCommit, s.revision, "列表没变 ⇒ 渲染层快照版本不涨")
    }

    /**
     * `clearBuffer()` 清 builder/m_stringList/m_buttonList（PrintStringBuffer.cs:273-278），
     * 弹出是**读取即消费**：第二次弹为空，且 `isLastLineEnd` 不被清（:24 只在下次 Append 更新）。
     */
    @Test
    fun popClearsPrintBufferSoSecondPopIsEmpty() {
        val s = wide()
        s.print("一次性内容", lineEnd = false)
        assertEquals(1, s.popDisplayingLines().size)
        assertTrue(s.printingBufferEmpty, "缓冲已被 clearBuffer 清空")
        assertEquals(emptyList(), s.popDisplayingLines(), "第二次弹没有内容可交")
        assertEquals(0, s.displayLineCount, "两次弹都不产生显示行")
    }

    /**
     * 与「并入」这条路径的合谋：原版的「上一条显示行未收尾」状态只存在于
     * `displayLineList` 里（EmueraConsole.cs:2948-2977 的 addDisplayLine 合并），
     * 弹出的行既然不进列表，就**没有**可并入的对象 ⇒ 弹出之后的下一次打印另起一行。
     * （旧实现让弹出的行进列表，于是这里会把两段文本并成一行，比原版多一条被吞的行。）
     */
    @Test
    fun printAfterPopStartsANewDisplayLine() {
        val s = wide()
        s.print("AB", lineEnd = false)
        assertEquals(1, s.popDisplayingLines().size)

        s.print("CD", lineEnd = true)
        s.newLine()
        assertEquals(listOf("CD"), s.loggedLines(), "弹出之后没有可并入的未收尾行，CD 自己成一行")
        assertEquals(1L, s.lineCount)
    }

    // ------------------------------------------------------------------
    // 3. 行序与 IsLogicalLine
    // ------------------------------------------------------------------

    /**
     * 折行时的行序 = 显示顺序（自上而下）：`ButtonsToDisplayLines` 按按钮表顺序填当前行、
     * 放不下才 `list.Add` 收行（PrintStringBuffer.cs:207-251），最后收残行（:259-262）；
     * `IsLogicalLine` 只给第一行（:206 `firstLine = !subDiv`、:242、:261）。
     */
    @Test
    fun popReturnsRowsInDisplayOrderWithLogicalFlagOnlyOnFirst() {
        val s = wide(clientWidth = 2)
        s.print("ABCDEF", lineEnd = false)

        val rows = s.popDisplayingLines()
        assertEquals(3, rows.size, "6 个半角格按宽 2 折成三行")
        assertEquals(listOf("AB", "CD", "EF"), rows.map { s.textOf(it) }, "顺序是显示顺序，不是倒序")
        assertTrue(rows[0].isLogicalLine, "第一行是逻辑行首（:206）")
        assertFalse(rows[1].isLogicalLine, "续行不是逻辑行首（:242 之后 firstLine=false）")
        assertFalse(rows[2].isLogicalLine)
        assertEquals(0, s.displayLineCount, "三行都不进列表")
    }

    /**
     * `Flush` 把 `isLastLineEnd` 只写进**末行**（PrintStringBuffer.cs:183），
     * 而 `isLastLineEnd` 由最近一次 `Append` 的 lineEnd 决定（:68）。
     * 弹出路径不补空格（原版只在 `PrintFlush(force:true)` 的空缓冲补，:3379-3382）。
     */
    @Test
    fun poppedLastRowCarriesPendingLineEndFlag() {
        val s = wide(clientWidth = 2)
        s.print("ABCD", lineEnd = false)
        val rows = s.popDisplayingLines()
        assertEquals(2, rows.size)
        assertFalse(rows[1].isLineEnd, "PRINTN 之后末行 IsLineEnd=false（:183 + :68）")

        // clearBuffer 不清 isLastLineEnd（:273-278），下一次 Append 才更新
        s.print("EF", lineEnd = true)
        val more = s.popDisplayingLines()
        assertEquals(1, more.size)
        assertTrue(more[0].isLineEnd, "lineEnd=true 的 Append 之后末行 IsLineEnd=true")
    }

    // ------------------------------------------------------------------
    // 4. 交出的是完整 DisplayLine：文本 + 列区间 + 值
    // ------------------------------------------------------------------

    /**
     * 原版交出的数组元素是 `ConsoleDisplayLine`，其按钮数组元素 `ConsoleButtonString` 带
     * `PointX`/`Width`/`Input`（ConsoleButtonString.cs 的字段），HTML 侧消费者读的就是
     * `IsButton` + `Inputs` + 每个样式节点的 Text（HtmlManager.cs:410-464）。
     * ⇒ 移植侧弹出侧必须同时给出**可见文本、行内列区间、按钮值**，缺一项 HTML 侧就拼不出
     * `<button value='…'>`。
     *
     * 自动按钮一颗时是**整串成钮**：`syn` 的 `num <= 1` 短路把整个 printBuffer 做成一条
     * primitive（ButtonStringCreator.cs:63-73，`Str = printBuffer`、`CanSelect = num >= 1`、
     * `Input = input2`），不是「选/9/吧」三段。
     */
    @Test
    fun poppedLinesKeepButtonTextColumnRangeAndValue() {
        val s = wide()
        s.print("选[9]吧", lineEnd = false)
        val auto = s.popDisplayingLines()
        assertEquals(1, auto.size)
        assertEquals("选[9]吧", s.textOf(auto[0]), "按钮文本含括号一起保留（Str = 整串）")
        assertEquals(1, auto[0].segments.size, "num<=1 短路 ⇒ 整串一段（ButtonStringCreator.cs:63-73）")
        assertEquals(LineBufferConsoleSink.Kind.BUTTON, auto[0].segments[0].kind, "该段落成可选钮")
        assertEquals(1, auto[0].buttons.size, "一颗按钮对应一条区间（ConsoleDisplayLine.Buttons 长度口径）")
        val b = auto[0].buttons[0]
        assertEquals(0, b.startCol)
        assertEquals(7, b.endCol, "全角 2 格 + 半角 1 格：选2 [1 9 1 ]1 吧2 = 7")
        assertEquals(9L, b.value, "按钮值 = 括号里的数字（ConsoleButtonString.cs:81-95 的 long 构造）")

        s.printButton("确定", 42L)
        s.print("尾", lineEnd = true)
        val mixed = s.popDisplayingLines()
        assertEquals(1, mixed.size)
        assertEquals("确定尾", s.textOf(mixed[0]))
        assertEquals(listOf(LineBufferConsoleSink.Kind.BUTTON, LineBufferConsoleSink.Kind.TEXT), mixed[0].segments.map { it.kind }, "显式钮与后续文本各自成段")
        assertEquals(listOf(42L), mixed[0].buttons.map { it.value }, "显式按钮一条（PrintStringBuffer.cs:103-112）")
        assertEquals(4, mixed[0].buttons[0].endCol, "钮区间只覆盖钮文本（确定 = 4 格）")
    }

    /**
     * 样式快照随段一起交出：原版每个 `ConsoleStyledString` 带 `StringStyle`
     * （ConsoleStyledString.cs:21-40），HTML 侧据此产 style 标签（HtmlManager.cs:452-454）。
     * 本类在入缓冲时就冻好（见 [LineBufferConsoleSink.Segment] 的 KDoc），弹出侧不得丢。
     */
    @Test
    fun poppedSegmentsCarryColorStyleAndFontSnapshot() {
        val s = wide()
        s.setForeColor(255, 0, 0)
        s.setFontStyle(1)
        s.setFont("黑体")
        s.print("红粗黑体", lineEnd = false)
        val seg = s.popDisplayingLines()[0].segments[0]
        assertEquals(0xFF0000, seg.foreColor, "SETCOLOR 的当前值进段（EmueraConsole.cs:479-481）")
        assertEquals(1, seg.styleMask, "字体样式位进段（:487 保留 FontStyle）")
        assertEquals("黑体", seg.fontName, "字体名进段（:487 保留 Fontname）")
        assertEquals(LineBufferConsoleSink.Kind.TEXT, seg.kind)
    }

    // ------------------------------------------------------------------
    // 5. PRINTD / PRINTDATAD 的前景色抑制（宿主侧那一轴）
    // ------------------------------------------------------------------

    /**
     * 原版 `Style` 取值器的第三分支（EmueraConsole.cs:483-487）：`UseSetColorStyle=false` 时
     * **只把前景色**换成 `defaultStyle.Color`（= `Config.ForeColor`，:206 + ConfigData.cs:68），
     * `FontStyle` 与 `Fontname` 仍取 `userStyle`。写点 = PRINTD/PRINTDATAD 族
     * （FunctionIdentifier.cs:180 与 :277，`IsPrintDFunction()` = flag 的 0x800 位 :4240-4243，
     * 后缀 D 在构造里置位 :244-248），方法体结尾无条件写回 true（:227、:317）。
     */
    @Test
    fun useSetColorStyleFalseDropsOnlyForeColor() {
        val s = wide()
        s.setForeColor(255, 0, 0)
        s.setFontStyle(1)
        s.setUseSetColorStyle(false)
        s.print("D形输出", lineEnd = false)
        val d = s.popDisplayingLines()[0].segments[0]
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, d.foreColor, "D 后缀忽略 SETCOLOR 的前景（:487 defaultStyle.Color）")
        assertEquals(1, d.styleMask, "样式位**不**被抑制（:487 仍取 userStyle.FontStyle）")
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, d.fontName, "字体名照旧（这里没 SETFONT，仍是默认）")

        s.setUseSetColorStyle(true)
        s.print("普通输出", lineEnd = false)
        val n = s.popDisplayingLines()[0].segments[0]
        assertEquals(0xFF0000, n.foreColor, "写回 true 后 SETCOLOR 恢复生效（:227/:317）")
    }

    /**
     * 抑制轴**不改** GETCOLOR 的读数源：原版 `StringStyle => userStyle`（EmueraConsole.cs:491）
     * 与 `UseSetColorStyle` 无关，GETCOLOR 读的一直是用户当前色（FunctionMethodCreator.cs:3662）。
     * 所以这里 `foregroundColor` 必须还是 SETCOLOR 的值，而不是默认色。
     */
    @Test
    fun useSetColorStyleDoesNotClearUserColor() {
        val s = wide()
        s.setForeColor(1, 2, 3)
        s.setUseSetColorStyle(false)
        assertEquals(0x010203, s.foregroundColor, "开关只抑制打印，不清用户色")
        s.print("X", lineEnd = false)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, s.popDisplayingLines()[0].segments[0].foreColor)
        assertEquals(0x010203, s.foregroundColor, "弹出之后仍然没清")

        s.setUseSetColorStyle(true)
        s.print("Y", lineEnd = false)
        assertEquals(0x010203, s.popDisplayingLines()[0].segments[0].foreColor)
    }

    /**
     * 抑制轴的**作用边界 = 入缓冲的那一刻**，不回溯改写已在缓冲里的文字：
     * 原版 `Style` 在打印入口求值后当实参传下去（EmueraConsole.cs:3157、:3177、:3188、:3233），
     * `PrintStringBuffer.Append` 发现样式与上一段不同就先用**旧**样式把已缓冲的串结掉
     * （PrintStringBuffer.cs:92），再用新样式起一段（:94-95）；同样式才续在同一串里（:87-88）。
     * ⇒ 「写 `UseSetColorStyle`」恰好就是一条新段的边界，本类的 [Segment] 逐次入缓冲同形
     * （`bufferPrint` 每次 add 一条带当下样式的 TextSpan）。
     */
    @Test
    fun useSetColorStyleFlipStartsANewSegmentWithoutRewritingBufferedText() {
        val s = wide()
        s.setForeColor(0, 255, 0)
        s.print("甲", lineEnd = false)
        s.setUseSetColorStyle(false)
        s.print("乙", lineEnd = false)
        val rows = s.popDisplayingLines()
        assertEquals(1, rows.size)
        val segs = rows[0].segments
        assertEquals(2, segs.size, "开关翻转 ⇒ 两段（:77 的 `lastStringStyle != style` 分支）")
        assertEquals(0x00FF00, segs[0].foreColor, "甲 入缓冲时开关仍为 true ⇒ 保留 SETCOLOR 的色，不被回溯改写")
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, segs[1].foreColor, "乙 入缓冲时已被抑制")
        assertEquals("甲乙", s.textOf(rows[0]), "两段文本合起来仍是原串：抑制只改色，不改内容/长度")
    }

    /**
     * 弹出侧与提交侧共用**同一次排版**（[LineBufferConsoleSink] 的 `buildRows`），
     * 连 `Config.ButtonWrap` 这条折行分支也一并生效 —— `commitToDisplayList = false`
     * 去掉的只有「落显示列表」那一步。原版同一事实：两条路径调的都是 `printBuffer.Flush`
     * （EmueraConsole.cs:3588 与 :3375-3385），差别只在返回值有没有被 `addRangeDisplayLine` 收下。
     *
     * 折行判据取自 PrintStringBuffer.cs:217-241：`:222` 的条件成立
     * （`ButtonWrap` 为真 + 当前行非空 + 整颗放不下）⇒ 整颗按钮移到次行（:242 收行）；
     * `ButtonWrap` 为假则就地按字符拆（:244-249），同一颗按钮在两行各留一条区间。
     * 夹具宽 10 半角格：`abcd`(4 格) + `按钮按钮`(8 格) = 12 格，放不下。
     * 本作的语料口径是 `emuera.config:38` `BUTTON WRAPPING:YES` ⇒ 上面那条才是 PC 上的形状。
     */
    @Test
    fun popUsesTheSameButtonWrapFoldingAsTheCommitPath() {
        val wrap = LineBufferConsoleSink(clientWidth = 10, buttonWrap = true)
        wrap.print("abcd", lineEnd = false)
        wrap.printButton("按钮按钮", 1L)
        val moved = wrap.popDisplayingLines()
        assertEquals(listOf("abcd", "按钮按钮"), moved.map { wrap.textOf(it) }, "整颗按钮移到次行（:222/:242）")
        assertEquals(1, moved.sumOf { it.buttons.size }, "不拆 ⇒ 只在次行留一条区间")
        val whole = moved[1].buttons[0]
        assertEquals(0, whole.startCol)
        assertEquals(8, whole.endCol, "按钮独占次行：4 个全角字 = 8 格")
        assertEquals(1L, whole.value)
        assertEquals(0, wrap.displayLineCount, "排版完也不落显示列表")

        val split = LineBufferConsoleSink(clientWidth = 10, buttonWrap = false)
        split.print("abcd", lineEnd = false)
        split.printButton("按钮按钮", 1L)
        val broken = split.popDisplayingLines()
        assertEquals(2, broken.size, "拆的这颗按钮跨两行")
        assertEquals(2, broken.sumOf { it.buttons.size }, "ButtonWrap=false ⇒ 同一颗按钮在两行各留一条（:244-249）")
        assertEquals(0, split.displayLineCount)
    }
}
