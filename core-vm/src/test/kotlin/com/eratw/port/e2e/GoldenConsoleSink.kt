package com.eratw.port.e2e

import com.eratw.port.core.fn.ConsolePrintedLine
import com.eratw.port.core.fn.ConsoleStyleSnapshot
import com.eratw.port.core.fn.htmlEscape
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.LineBufferConsoleSink

// ===========================================================================
// CONSOLE-2 残项：E2E 整体换成生产 sink
//
// [ConsoleSink] 是「ERB 语句可触达的输出动作」最小集（写入侧），而 E2E 装置还要从
// 控制台**读回**五样东西：GETCOLOR 家族的样式快照、GETDISPLAYLINE / HTML_GETPRINTEDSTR
// 的已提交显示行、HTML_POPPRINTINGSTR 的打印中缓冲、以及喂给归一化的日志文本行。
// 这五个读出侧不在 ConsoleSink 里，于是 GoldenPipeline.newRuntime 的参数只能写在
// 自建假 sink 的具体类型上（`LineConsoleSink`），生产 sink
// （`LineBufferConsoleSink` + `CellWidth`）接不进来 —— 这就是 CONTRACT-M4-D §2
// d-console2 说的「`GoldenPipeline.kt:625` 一带类型不匹配」。
//
// 本文件把这个读出侧立成接口 [GoldenConsoleSink]，两个实现各自回答：
//  - [LineConsoleSink]（既有假 sink，行缓冲自建模）——`GoldenTextParityTest` 与
//    boot/l3 装置仍在用它，公开 API 一字未动，只是追加接口成员；
//  - [ProductionConsoleSink]（本波新接线）——**纯映射门面**：打印与状态全部转发
//    [LineBufferConsoleSink]，本类不持任何影子状态（样式、行数、缓冲都从生产 sink
//    读），只做「生产显示行 → 装置 DTO/文本行」这一层翻译。
// ===========================================================================

/**
 * E2E 黄金对拍所需的控制台查询面 = 写入侧 [ConsoleSink] + 五个读出侧。
 *
 * [snapshot] / [capturedText] 是装置既有调用名（分节增量与全文捕获），默认实现走
 * [loggedLines]；[LineConsoleSink] 保留自己的口径（含未 flush 的当前行），
 * [ProductionConsoleSink] 用默认实现（未 flush 的内容还没有显示行形态，
 * 与原版 `GetLog` 同源，EmueraConsole.cs:3536-3543）。
 */
interface GoldenConsoleSink : ConsoleSink {
    /** GETCOLOR 家族的数据源（[ConsoleStyleSnapshot]，ConsoleQueryMethods.kt:40-112 逐条读它）。 */
    fun styleSnapshot(): ConsoleStyleSnapshot

    /** 已提交显示行（GETDISPLAYLINE 按下标取 `text`，HTML_GETPRINTEDSTR 取 `html`；不含临时行）。 */
    fun displayLines(): List<ConsolePrintedLine>

    /** 打印中缓冲（HTML_POPPRINTINGSTR；读取即消费 —— 原版是读取即 flush）。 */
    fun poppingLines(): List<ConsolePrintedLine>

    /** 日志回放文本行（逐字节对拍的输入；不含临时行）。 */
    fun loggedLines(): List<String>

    /** 装置快照 = 日志文本行（分节增量按它的长度切）。 */
    fun snapshot(): List<String> = loggedLines()

    /** 快照拼回全文（行间换行；PC 日志文本流口径，末尾无换行）。 */
    fun capturedText(): String = loggedLines().joinToString("\n")
}

/**
 * 生产 sink 的 E2E 门面：把 [LineBufferConsoleSink] 的显示行数据模型翻译成
 * [GoldenConsoleSink] 的查询面，让黄金对拍跑在生产实现上。
 *
 * 打印侧（print/printC/printBar/样式/行数/折行/PRINTN 并入/临时行）全部原样转发，
 * 本类不重复实现任何一条 —— 唯一职责是映射：
 *
 * 1. **HTML 段的行文本**：生产 sink 的 Kind.HTML 段保留原文（含标签）供 `:render` 的
 *    mini-HTML 排版消费（`LineBufferConsoleSink` 类 KDoc），而 PC 日志文本里没有标签——
 *    原版在打印期就解析掉了（HtmlManager.cs:517-561 的 Html2DisplayLine，文本片段逐个
 *    过 Unescape :558）。所以日志文本与 GETDISPLAYLINE/HTML_GETPRINTEDSTR 的 `text`
 *    都走 [LineBufferConsoleSink.htmlVisibleText]（同一份去标签+实体规则）。
 *    基准取证：pc-golden-root.txt:294 `HTMLPLAIN`（语句是 GOLDEN_02_PRINT.ERB:250 的
 *    `HTML_PRINT "<b>HTML</b>PLAIN"`）。
 * 2. **样式快照**：`ConsoleStyleSnapshot` 要的是 `[r,g,b]` 三元组与「未设置=null」，
 *    生产 sink 存的是 `0xRRGGBB` 与 null，本类只做拆解（[toIntArray]）与
 *    `Alignment → 0/1/2` 的编号映射（DisplayLineAlignment.cs:5-7，
 *    ConsoleQueryMethods.kt:99-107 再映回 LEFT/CENTER/RIGHT）。三项环境缺省色
 *    （defFore/defBack/focus）直接取生产 sink 的 ConfigData 常量，本类不自存一份。
 * 3. **打印中缓冲**：[poppingLines] 走 [LineBufferConsoleSink.popDisplayingLines]
 *    （原版 PopDisplayingLines 的「空则不返回、非空则 flush 并交出行」，
 *    EmueraConsole.cs:3578-3588）。假 sink 当年是「取出即丢弃」，那是假 sink 的偏差，
 *    本门面按原版口径接。基准取证：pc-golden-root.txt:302 `HTML_POPPRINTINGSTR=[]`
 *    （调用点在 PRINTFORML 之后，缓冲本就是空 → 两种口径同结果）。
 *
 * 显示行的 `isLogicalLine` 用生产的「折行首行」语义（PrintStringBuffer.cs:193,206），
 * 这正是 `GetDisplayLines` 从新往回数逻辑行所依赖的那一项
 * （EmueraConsole.cs:3546-3576）；假 sink 的「折行末行」语义在 golden 里无感知
 * （目标行未折行，pc-golden-root.txt:301 只包一条显示行）。
 */
class ProductionConsoleSink(val sink: LineBufferConsoleSink) : GoldenConsoleSink {

    // ---- 查询侧 ----

    /** LINECOUNT（生产 sink 自己按 addDisplayLine 计，EmueraConsole.cs:2971-2977）。 */
    override val lineCount: Long get() = sink.lineCount

    /** LINEISEMPTY 的数据源：未 flush 的打印缓冲空态。 */
    override val printingBufferEmpty: Boolean get() = sink.printingBufferEmpty

    /** FONTBOLD/FONTITALIC 的 OR 基值（FunctionIdentifier.cs:1574-1606）。 */
    override val fontStyle: Int get() = sink.fontStyle

    override fun styleSnapshot(): ConsoleStyleSnapshot = ConsoleStyleSnapshot(
        fore = sink.foregroundColor?.toIntArray(),
        back = sink.backgroundColor?.toIntArray(),
        defFore = LineBufferConsoleSink.DEFAULT_FORE_COLOR.toIntArray(),
        defBack = LineBufferConsoleSink.DEFAULT_BACK_COLOR.toIntArray(),
        focus = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR.toIntArray(),
        fontStyleMask = sink.fontStyle,
        fontName = sink.fontName,
        alignment = when (sink.alignment) {
            Alignment.LEFT -> 0
            Alignment.CENTER -> 1
            Alignment.RIGHT -> 2
        },
        redraw = sink.redraw,
    )

    override fun displayLines(): List<ConsolePrintedLine> =
        sink.lines().filterNot { it.isTemporary }.map { it.toPrintedLine() }

    override fun poppingLines(): List<ConsolePrintedLine> =
        sink.popDisplayingLines().map { it.toPrintedLine() }

    /**
     * 日志文本行 = 生产 [LineBufferConsoleSink.loggedLines] 的**同一条筛选**
     * （跳过临时行，EmueraConsole.cs:3053-3056），但文本走 [logText] 的可见文本口径。
     *
     * `flatMap { split('\n') }` 只为「一行文本里出现换行」这一种残余兜底：
     * 生产 sink 的 print 在内嵌换行处就已经 flush 断行（EmueraConsole.cs:3172-3187），
     * 所以显示行本身不含换行，除 HTML 段的 `<br>`（见 [LineBufferConsoleSink.htmlVisibleText]
     * 的 TODO(verify)）。
     */
    override fun loggedLines(): List<String> =
        sink.lines().filterNot { it.isTemporary }.flatMap { logText(it).split('\n') }

    // ---- 写入侧：全部转发生产 sink（本类零状态）----

    override fun print(text: String) { sink.print(text) }

    override fun print(text: String, lineEnd: Boolean) { sink.print(text, lineEnd) }

    override fun printLine(text: String) { sink.printLine(text) }

    override fun printC(text: String, alignRight: Boolean) { sink.printC(text, alignRight) }

    override fun printSingleLine(text: String) { sink.printSingleLine(text) }

    override fun printButton(text: String, value: Long) { sink.printButton(text, value) }

    override fun printButton(text: String, value: String) { sink.printButton(text, value) }

    override fun printButtonC(text: String, value: Long, alignRight: Boolean) {
        sink.printButtonC(text, value, alignRight)
    }

    override fun printButtonC(text: String, value: String, alignRight: Boolean) {
        sink.printButtonC(text, value, alignRight)
    }

    override fun printPlain(text: String) { sink.printPlain(text) }

    override fun printTemporaryLine(text: String) { sink.printTemporaryLine(text) }

    override fun printBar() { sink.printBar() }

    override fun printCustomBar(text: String) { sink.printCustomBar(text) }

    override fun newLine() { sink.newLine() }

    override fun deleteLine(count: Int) { sink.deleteLine(count) }

    override fun clearText() { sink.clearText() }

    override fun setForeColor(r: Int, g: Int, b: Int) { sink.setForeColor(r, g, b) }

    override fun setBackColor(r: Int, g: Int, b: Int) { sink.setBackColor(r, g, b) }

    override fun resetForeColor() { sink.resetForeColor() }

    override fun resetBackColor() { sink.resetBackColor() }

    override fun setFontStyle(mask: Int) { sink.setFontStyle(mask) }

    override fun setFont(name: String) { sink.setFont(name) }

    override fun setAlignment(kind: Alignment) { sink.setAlignment(kind) }

    override fun setRedraw(mode: Int) { sink.setRedraw(mode) }

    override fun printHtml(text: String, option: Boolean) { sink.printHtml(text, option) }

    override fun addHtmlIsland(text: String) { sink.addHtmlIsland(text) }

    override fun clearHtmlIslands() { sink.clearHtmlIslands() }

    override fun printError(text: String) { sink.printError(text) }

    // ---- 映射 ----

    /**
     * 显示行的可见文本：非 HTML 段原样，HTML 段过生产的
     * [LineBufferConsoleSink.htmlVisibleText]（去标签 + 还原实体，Html2PlainText 口径，
     * HtmlManager.cs:714-717）。
     */
    private fun logText(line: LineBufferConsoleSink.DisplayLine): String =
        line.segments.joinToString("") { seg ->
            if (seg.kind == LineBufferConsoleSink.Kind.HTML) sink.htmlVisibleText(seg.text) else seg.text
        }

    /**
     * 行 → 装置 DTO。`html` = 对可见文本再转义一次（[htmlEscape] 与原版
     * `HtmlManager.Escape` 同一张表，HtmlManager.cs:372-378）——
     * 基准取证 pc-golden-root.txt:301 的
     * `HTML_GETPRINTEDSTR=[<p align='left'><nobr>HTML_STRINGLINES=2</nobr></p>]`
     * （外壳由 fn 侧 displayLine2Html 加，本层只交行体）。
     */
    private fun LineBufferConsoleSink.DisplayLine.toPrintedLine(): ConsolePrintedLine {
        val text = logText(this)
        return ConsolePrintedLine(
            text = text,
            html = htmlEscape(text),
            align = alignment,
            isLogicalLine = isLogicalLine,
        )
    }

    /** `0xRRGGBB` → `[r, g, b]`（与 ConsoleQueryMethods.kt:34-37 的 encodeRgb 互逆）。 */
    private fun Int.toIntArray(): IntArray = intArrayOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)
}
