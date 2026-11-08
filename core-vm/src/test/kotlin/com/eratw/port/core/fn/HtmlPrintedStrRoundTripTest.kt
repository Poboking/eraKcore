package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 「回读再打印」真往返钉板：`HTML_GETPRINTEDSTR` 交回的串再喂给 `HTML_PRINT`，
 * **原版会不会抛、我们会不会抛**（CONTRACT-EP2-3LANE §2 L8）。
 *
 * ## 判性结论：**(b) 原版不抛而我们抛 ⇒ 缺陷在生成侧**（已修，改动在
 * [consolePrintedLine] / [stripRowShell]，判式一条没动）
 *
 * 原版交回的是**带外壳的行体 HTML**，外壳只有唯一一层：
 * - `HTML_GETPRINTEDSTR` = `GetDisplayLines(lineNo)` → `DisplayLine2Html(lines, needPandN: true)`
 *   （FunctionMethodCreator.cs:6103-6108），取行是 `EmueraConsole.cs:3546-3576`；
 * - `needPandN` 那层 `<p align='…'>` + `<nobr>` 只在**首尾各加一次**（HtmlManager.cs:388-403、
 *   :478-482），行间用 `<br>` 连接（:404-409）；
 * - **行体是逐节点重建**（:446-464）：`ConsoleStyledString` 走 `getStringStyleStartingTag`
 *   （:864-915，只产 `font/s/u/i/b`）+ `Escape(Text)`，`<img>`/`<shape>` 给 `AltText`，
 *   按钮走 :415-470 ⇒ 行体里**永远不会有** `<p>`/`<nobr>`：那两枚在打印期就被
 *   `html2DisplayLine` 折成**行级标志**（`HtmlAnalzeState.Alignment`/`FlagNobr` →
 *   每条显示行 `SetAlignment`，:700-710）。
 * ⇒ 原版回读串的形状恒为 `<p align='…'><nobr>行体</nobr></p>`，再喂 `HTML_PRINT` 就是它自己
 * 打印期的合法输入（`<p>` 先于 `<nobr>`、只一枚；判式见 :1109-1116），**不抛**。
 *
 * `GETDISPLAYLINE` 交回的是**去壳纯文本**（`ConsoleDisplayLine.ToString()`，
 * ConsoleDisplayLine.cs:127-140，FunctionMethodCreator.cs:8437），本仓同一口径
 * （[ConsolePrintedLine.text]）⇒ 两侧同判，不在本类射程内，只在下面顺带钉一条「行体不含标签」。
 *
 * ## 我们曾经为什么更严
 *
 * sink 的 HTML 段存的是**行原文**（含原文自带的 `<p>`/`<nobr>` 外壳与 splitHtmlLines 的
 * 行首重放），[consolePrintedLine] 过去把它**原样**当行体交回，[displayLine2Html] 再套一层
 * 外壳 ⇒ 双外壳。第二枚 `<p>` 命中原版「开 `<p>` 查 `FlagNobr`」的复制粘贴判式
 * （:1113-1116，ADR-005 必须复刻）抛「<p>标签重复使用」；即便只多一枚闭标签也会被
 * `</p>` 的「未开过即抛」判掉（:985-990）。这正是端点那条上报的成因：
 * `ERB/魔改内容/qol/qol_EVENTCOMEND.ERB:22` 的 `L_STR:LOCAL = <clearbutton>%HTML_GETPRINTEDSTR(LOCAL)%</clearbutton>`
 * 把回读串存下来，:26 再 `HTML_PRINT` 回去；**下一次**进这个函数时，第 22 行读到的行体里
 * 已是双外壳，而该行的 `<img>` 命中读取侧严格解析触发条件（[HTML_NODE_TRIGGER]），
 * 就在第 22 行抛（0 基行号上报为 21，`lexer/LogicalLineReader.kt:181-182`）。
 * 判式的逐形对账在 `HtmlStrictDuplicateTagTest`（那边钉的是「解析器照原版拒绝双外壳」，
 * 本类钉的是「生成侧不再产双外壳」，两侧不重叠）。
 *
 * ## 解析代理口径
 *
 * 本仓 `HTML_PRINT` 的语法错误**推迟**到读取侧（见 [consolePrintedLine] 的 TODO(verify)），
 * 而原版在打印期就解析（:517-527）。所以「再喂 `HTML_PRINT` 会不会抛」在本类用
 * `HTML_STRINGLEN` 当解析代理：二者共用同一份 `HtmlStrictParser`（[htmlLength] :168-182
 * 与 `html2DisplayLine` 同一套 `tagAnalyze` 判式），抛点与文案逐形相同。
 */
private class RtBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("RtBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 只把行缓冲接到 [BuiltinHost.consoleLines] 上，其余成员走 `BuiltinHost` 出厂缺省。 */
private class RtHost(
    override val consoleLines: List<ConsolePrintedLine>?,
) : BuiltinHost

private class RtEnv {
    val sink: LineBufferConsoleSink = LineBufferConsoleSink(clientWidth = 40)

    /** `HTML_GETPRINTEDSTR` 的一次读取（走生产装配同一份映射 [consolePrintedLines]）。 */
    fun readPrintedStr(lineNo: Long? = null): String {
        val lines = consolePrintedLines(sink.lines(), sink.defaultFontName)
        val ctx = rtCtx(RtHost(lines))
        return rtStr(ctx, "HTML_GETPRINTEDSTR", *if (lineNo == null) arrayOf() else arrayOf(num(lineNo)))
    }

    /** `GETDISPLAYLINE(lineNo)` 的读数。 */
    fun readDisplayLine(lineNo: Long): String {
        val lines = consolePrintedLines(sink.lines(), sink.defaultFontName)
        return rtStr(rtCtx(RtHost(lines)), "GETDISPLAYLINE", num(lineNo))
    }

    /** 回读模型（行体 / 对齐）—— [stripRowShell] 的直接观察面。 */
    fun printedLines(): List<ConsolePrintedLine> =
        consolePrintedLines(sink.lines(), sink.defaultFontName).toList()
}

private fun rtCtx(host: BuiltinHost): BuiltinEvalContext {
    val store = VariableStore()
    val base = RtBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun rtStr(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun num(n: Long): Value = Value.IntV(n)

private fun str(s: String): Value = Value.StrV(s)

/**
 * 解析代理：`HTML_STRINGLEN(html)` 走 [htmlLength] → [HtmlStrictParser]，与原版打印期
 * `html2DisplayLine` 同一套判式（HtmlManager.cs:168-182 vs :517-527 + :951-1678）。
 * 返回 null = 不抛；非 null = 抛出的 CodeEE 文案。
 */
private fun parseErrorOrNull(html: String): String? = try {
    (rtCtx(RtHost(null)).callFunction("HTML_STRINGLEN", listOf(str(html))) as Value.IntV).v
    null
} catch (e: CodeEE) {
    e.message
}

private fun assertParses(html: String) {
    assertEquals(null, parseErrorOrNull(html), "回读串必须可再解析：$html")
}

class HtmlPrintedStrRoundTripTest {

    /**
     * 单行带外壳的回读：原版交回的是**带一层外壳的行体 HTML**（HtmlManager.cs:388-403 +
     * :446-464 的逐节点行体），我们从此同形；再喂 `HTML_PRINT`（解析代理）不抛。
     */
    @Test
    fun `单行带外壳回读_交回一层外壳_再喂不抛`() {
        val env = RtEnv()
        env.sink.printHtml("<p align='center'><nobr>AB</nobr></p>", option = false)

        val got = env.readPrintedStr()
        assertEquals("<p align='center'><nobr>AB</nobr></p>", got)
        assertParses(got)
        // 行体侧观察：剥壳后只剩节点级内容，对齐由行体的 `<p align>` 折回行级标志
        // （原版 SetAlignment，HtmlManager.cs:700-710）。
        val printed = env.printedLines().single()
        assertEquals("AB", printed.html)
        assertEquals(com.eratw.port.core.host.Alignment.CENTER, printed.align)
    }

    /**
     * **往返收敛**（原版 `DisplayLine2Html` 的形状是自身不动点）：回读 → 再打印 → 再回读，
     * 第二次与第一次逐字相同。旧实现在这里每轮都多套一层外壳，串越长越发散，
     * 且第二次读取（含 `img` 触发行）必抛。
     */
    @Test
    fun `回读再打印再回读_逐字收敛不再逐轮套壳`() {
        val first = RtEnv().also { it.sink.printHtml("<p align='left'><nobr>AB</nobr></p>", false) }
            .readPrintedStr()
        val second = RtEnv().also { it.sink.printHtml(first, false) }.readPrintedStr()
        val third = RtEnv().also { it.sink.printHtml(second, false) }.readPrintedStr()

        assertEquals(first, second, "一次往返必须是不动点")
        assertEquals(first, third, "逐轮不得再加外壳")
        assertParses(third)
    }

    /**
     * **跨行重放单独钉**（契约 §2 L8 第 4 步）：多行页回读第二行 —— 外壳重放正落在这一行，
     * 也是 `main@d0c17818` 刚改过外壳序的那一处。旧实现把带重放外壳的第二行原文再套一次
     * 外壳 ⇒ 回读串自产脏串；现在两条显示行的行体都剥壳，交回的原版形状是
     * 一层外壳 + 行间 `<br>`（HtmlManager.cs:404-409）。
     */
    @Test
    fun `跨行重放的第二行剥壳后交回一层外壳`() {
        val env = RtEnv()
        env.sink.printHtml("<p align='left'><nobr>甲行<br>乙行</nobr></p>", option = false)
        val printed = env.printedLines()
        assertTrue(printed.size >= 2, "br 必须切成两条显示行，实得：$printed")
        // 行首重放的 p/nobr 只服务渲染层（sink 原文），回读行体一律剥净。
        assertEquals("甲行", printed[0].html)
        assertEquals("乙行", printed[1].html, "第二行正是重放外壳的落点")

        val got = env.readPrintedStr()
        assertEquals("<p align='left'><nobr>甲行<br>乙行</nobr></p>", got)
        assertParses(got)
        // 回读的是**带外壳的行体 HTML**，不是去壳文本：去壳文本归 GETDISPLAYLINE。
        assertEquals("甲行", env.readDisplayLine(0))
        assertEquals("乙行", env.readDisplayLine(1))
    }

    /**
     * **端点语料形状**（`qol_EVENTCOMEND.ERB:21-27`，`@删除立绘履历`）：
     * 第 22 行把 `HTML_GETPRINTEDSTR` 的回读串包进 `<clearbutton>` 存进 `L_STR`，
     * 第 26 行再 `HTML_PRINT` 回去；**下一次进这个函数**时第 22 行读到的就是上一轮打印的
     * 那一行——上报那条 `<p>标签重复使用` 正是在这一次读取里抛的（`<img>` 命中
     * [HTML_NODE_TRIGGER] 才触发读取侧严格解析）。本例把这条链跑到第二次调用。
     */
    @Test
    fun `端点语料形状_立绘履历行的两次调用不再自产脏串`() {
        val endpointMsg = "<p>标签重复使用"
        // 第 1 次调用：读普通立绘行（带 img）→ 包 clearbutton → 回打。
        val first = RtEnv()
        first.sink.printHtml("<p align='left'><nobr>立绘<img src='a.png'></nobr></p>", option = false)
        val read1 = first.readPrintedStr()
        assertParses(read1)
        val wrapped = "<clearbutton>$read1</clearbutton>"
        assertParses(wrapped)
        val second = RtEnv()
        second.sink.printHtml(wrapped, option = false)

        // 第 2 次调用的第 22 行：读上一轮回打的那一行（img ⇒ 读取侧严格解析）。
        // 旧实现在这里抛「<p>标签重复使用」（行体已带两层外壳）。
        val read2 = second.readPrintedStr()
        val err = parseErrorOrNull(read2)
        assertEquals(null, err, "第 2 次回读串不许抛 $endpointMsg：$read2")
        assertParses(read2)
    }

    /**
     * 回归钉：**没有外壳的行体逐字不变**（本路只动 p/nobr 的表示，其余一律不许变形），
     * 以及属性值引号内的 `<p>` 不被误剥（原版属性是 LiteralStringWord，`title='a<p>b'`
     * 里的尖括号不是标签边界）。
     */
    @Test
    fun `无外壳行体逐字不变_属性内的标签不误剥`() {
        val plain = RtEnv()
        plain.sink.printHtml("<b>HTML</b>PLAIN", option = false)
        assertEquals("<b>HTML</b>PLAIN", plain.printedLines().single().html)
        assertEquals(
            "<p align='left'><nobr><b>HTML</b>PLAIN</nobr></p>",
            plain.readPrintedStr(),
        )

        val attr = RtEnv()
        attr.sink.printHtml("<button value='1' title='x<p>y'>ok</button>", option = false)
        val got = attr.readPrintedStr()
        assertTrue("'x<p>y'" in got, "属性内的 <p> 属文本，不许当外壳剥掉：$got")
        // 原版对 `<button value='…' title='…'>` 的按钮标签判式与 p/nobr 无关（HM :1476-1563）：
        // 剥壳口径不能把它判坏。
        assertParses(got)
    }

    /**
     * 反向钉（不许「顺手宽松」）：手工造的**双外壳**串仍然被严格解析拒绝，且文案与原版
     * 同形——本路只把**生成侧**改成原版口径，判式一条都没动（`HtmlStrict.kt` 禁碰）。
     */
    @Test
    fun `手工双外壳仍照原版拒绝`() {
        val e = assertFailsWith<CodeEE> {
            rtCtx(RtHost(null)).callFunction(
                "HTML_STRINGLEN",
                listOf(str("<p align='left'><nobr><p align='left'><nobr>AB</nobr></p></nobr></p>")),
            )
        }
        assertEquals("<p>标签重复使用", e.message)
    }
}
