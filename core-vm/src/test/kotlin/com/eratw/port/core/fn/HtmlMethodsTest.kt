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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTML 家族 + GETDISPLAYLINE 测试。
 *
 * 判据：`fixtures/golden/baseline/pc-golden-root.txt:293-307`（SECTION:HTML 与 LINE-QUERY），
 * 调用序列 `fixtures/golden/GOLDEN_02_PRINT.ERB:249-266`。
 * 断言值一律取自基准文本，不由实现反推：
 * - 297 `HTML_STRINGLEN=4 PLAIN=36`
 * - 298 `HTML_TOPLAINTEXT=ABCD`
 * - 299 `HTML_ESCAPE=&lt;a&gt;`
 * - 300 `HTML_STRINGLINES=2`
 * - 301 `HTML_GETPRINTEDSTR=[<p align='left'><nobr>HTML_STRINGLINES=2</nobr></p>]`
 * - 302 `HTML_POPPRINTINGSTR=[]`
 * - 303 `HTML_SUBSTRING=[<i>AB</i>C] R=<i>AB</i>C`
 * - 306 `GETDISPLAYLINE=如果出现了错误，请根据目录下的报错指导文件进行报错`
 */
private class HmBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("HmBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 假宿主：控制台行缓冲与打印中缓冲由测试直接给。 */
private class HmHost(
    override val consoleLines: List<ConsolePrintedLine>? = null,
    override val popPrintingLines: List<ConsolePrintedLine>? = null,
    override val measureText: ((String) -> Long)? = null,
) : BuiltinHost

private fun hmCtx(host: BuiltinHost = HmHost()): BuiltinEvalContext {
    val store = VariableStore()
    val base = HmBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun hmInt(ctx: BuiltinEvalContext, name: String, vararg args: Value): Long =
    (ctx.callFunction(name, args.toList()) as Value.IntV).v

private fun hmStr(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun str(s: String): Value = Value.StrV(s)
private fun num(n: Long): Value = Value.IntV(n)

/**
 * 基准第 301 行要复现的「上一行」：GOLDEN_02_PRINT.ERB:258 打印的 `HTML_STRINGLINES=2`。
 * 行体 HTML 无样式标签（默认配色），故 html 就是文本本身。
 */
private fun hmLastPrintedLine(): ConsolePrintedLine =
    ConsolePrintedLine(text = "HTML_STRINGLINES=2", html = "HTML_STRINGLINES=2")

/** 基准第 306 行要复现的第 0 条显示行：游戏 TITLE 的首行提示。 */
private fun hmConsoleBuffer(): List<ConsolePrintedLine> = listOf(
    ConsolePrintedLine(text = "如果出现了错误，请根据目录下的报错指导文件进行报错"),
    ConsolePrintedLine(text = "=== SECTION:HTML ==="),
    ConsolePrintedLine(text = "HTMLPLAIN"),
    ConsolePrintedLine(text = " "),
    ConsolePrintedLine(text = "TAGSPLIT_N=4 R0=<i> R1=AB R2=</i>"),
    ConsolePrintedLine(text = "HTML_STRINGLEN=4 PLAIN=36"),
    ConsolePrintedLine(text = "HTML_TOPLAINTEXT=ABCD"),
    ConsolePrintedLine(text = "HTML_ESCAPE=&lt;a&gt;"),
    hmLastPrintedLine(),
)

class HtmlMethodsTest {

    // ===== 基准 297-300：纯文本/宽度/转义 =====

    @Test
    fun `HTML_STRINGLEN 复现基准 297 行 4 与 36`() {
        val ctx = hmCtx()
        // HTML_STRINGLEN("<i>AB</i>CD")：宽度 4 个半角字符 × 9px = 36，2*36/18 = 4
        assertEquals(4L, hmInt(ctx, "HTML_STRINGLEN", str("<i>AB</i>CD")))
        // HTML_STRINGLEN("<i>AB</i>CD",1)：第二参非 0 → 原始像素宽度
        assertEquals(36L, hmInt(ctx, "HTML_STRINGLEN", str("<i>AB</i>CD"), num(1)))
        // 第二参显式 0 与省略等价
        assertEquals(4L, hmInt(ctx, "HTML_STRINGLEN", str("<i>AB</i>CD"), num(0)))
    }

    @Test
    fun `HTML_TOPLAINTEXT 复现基准 298 行`() {
        assertEquals("ABCD", hmStr(hmCtx(), "HTML_TOPLAINTEXT", str("<i>AB</i>CD")))
    }

    @Test
    fun `HTML_ESCAPE 复现基准 299 行`() {
        assertEquals("&lt;a&gt;", hmStr(hmCtx(), "HTML_ESCAPE", str("<a>")))
        // 五个字符的表（HtmlManager.cs:372-378），单趟扫描不重复转义
        assertEquals("&amp;&quot;&apos;", hmStr(hmCtx(), "HTML_ESCAPE", str("&\"'")))
        assertEquals("&amp;lt;", hmStr(hmCtx(), "HTML_ESCAPE", str("&lt;")))
    }

    @Test
    fun `HTML_STRINGLINES 复现基准 300 行`() {
        assertEquals(2L, hmInt(hmCtx(), "HTML_STRINGLINES", str("AAAA"), num(2)))
        // 空串直接 0（FunctionMethodCreator.cs:972-975）
        assertEquals(0L, hmInt(hmCtx(), "HTML_STRINGLINES", str(""), num(2)))
    }

    // ===== 基准 303：按显示宽度截断 =====

    @Test
    fun `HTML_SUBSTRING 复现基准 303 行`() {
        val ctx = hmCtx()
        // 预算 3*18/2 = 27px → A、B、C 三个半角字符；标签原样补全
        assertEquals("<i>AB</i>C", hmStr(ctx, "HTML_SUBSTRING", str("<i>AB</i>CD"), num(3)))
        // RESULTS:0 同返回值（基准 303 行 R=<i>AB</i>C）；RESULTS:1 是剩余段。
        // 剩余段基准未打印，此处按 HtmlSubString 的返回契约（HtmlManager.cs:305-325）推导为 "D"。
        assertEquals("<i>AB</i>C", ctx.store.readStr("RESULTS", longArrayOf(0)))
        assertEquals("D", ctx.store.readStr("RESULTS", longArrayOf(1)))
    }

    // ===== 基准 301-302：控制台行缓冲 =====

    @Test
    fun `HTML_GETPRINTEDSTR 复现基准 301 行`() {
        val ctx = hmCtx(HmHost(consoleLines = hmConsoleBuffer()))
        assertEquals(
            "<p align='left'><nobr>HTML_STRINGLINES=2</nobr></p>",
            hmStr(ctx, "HTML_GETPRINTEDSTR", num(0)),
        )
        // 参数可省略，缺省 0（FunctionMethodCreator.cs:6094-6097）
        assertEquals("<p align='left'><nobr>HTML_STRINGLINES=2</nobr></p>", hmStr(ctx, "HTML_GETPRINTEDSTR"))
    }

    @Test
    fun `HTML_POPPRINTINGSTR 复现基准 302 行`() {
        // 打印缓冲为空 → 空串（PopDisplayingLines 返回 null 的分支）
        assertEquals("", hmStr(hmCtx(HmHost(popPrintingLines = emptyList())), "HTML_POPPRINTINGSTR"))
    }

    @Test
    fun `GETDISPLAYLINE 复现基准 306 行`() {
        val ctx = hmCtx(HmHost(consoleLines = hmConsoleBuffer()))
        assertEquals(
            "如果出现了错误，请根据目录下的报错指导文件进行报错",
            hmStr(ctx, "GETDISPLAYLINE", num(0)),
        )
        // 越界返回空串（FunctionMethodCreator.cs:8431-8434）
        assertEquals("", hmStr(ctx, "GETDISPLAYLINE", num(999)))
        assertEquals("", hmStr(ctx, "GETDISPLAYLINE", num(-1)))
    }

    // ===== 宿主未接线：三个函数抛 CodeEE =====

    @Test
    fun `控制台缓冲未接线时抛 CodeEE`() {
        val ctx = hmCtx()
        assertFailsWith<CodeEE> { ctx.callFunction("HTML_GETPRINTEDSTR", listOf(num(0))) }
        assertFailsWith<CodeEE> { ctx.callFunction("HTML_POPPRINTINGSTR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETDISPLAYLINE", listOf(num(0))) }
    }

    // ===== 宿主度量注入（measureText）与行缓冲的分组语义 =====

    @Test
    fun `宿主注入 measureText 后按宿主宽度换算`() {
        // 每个字符固定 10px：4 个字符 = 40 → 2*40/18 = 4（余 8 非 0 → +1 → 5）
        val ctx = hmCtx(HmHost(measureText = { s -> s.length * 10L }))
        assertEquals(40L, hmInt(ctx, "HTML_STRINGLEN", str("<i>AB</i>CD"), num(1)))
        assertEquals(5L, hmInt(ctx, "HTML_STRINGLEN", str("<i>AB</i>CD")))
    }

    @Test
    fun `GETPRINTEDSTR 取最近一条逻辑行并合并其折行片段`() {
        val ctx = hmCtx(
            HmHost(
                consoleLines = listOf(
                    ConsolePrintedLine(text = "OLD", html = "OLD"),
                    ConsolePrintedLine(text = "NEW", html = "NEW"),
                    ConsolePrintedLine(text = "NEW2", html = "NEW2", isLogicalLine = false),
                ),
            ),
        )
        // 从最新往回数：num==0 的片段全收（含 isLogicalLine=false 的折行），遇到逻辑行后 num=1 即停
        assertEquals("<p align='left'><nobr>NEW<br>NEW2</nobr></p>", hmStr(ctx, "HTML_GETPRINTEDSTR", num(0)))
        // 再往前一条逻辑行
        assertEquals("<p align='left'><nobr>OLD</nobr></p>", hmStr(ctx, "HTML_GETPRINTEDSTR", num(1)))
    }

    @Test
    fun `HTML_POPPRINTINGSTR 无 p-nobr 外壳且用 br 连接`() {
        val ctx = hmCtx(
            HmHost(
                popPrintingLines = listOf(
                    ConsolePrintedLine(text = "A", html = "A", align = Alignment.CENTER),
                    ConsolePrintedLine(text = "B", html = "B", isLogicalLine = false),
                ),
            ),
        )
        assertEquals("A<br>B", hmStr(ctx, "HTML_POPPRINTINGSTR"))
    }

    // ===== 注册表：8 个名字都必须从占位变成真实现 =====

    @Test
    fun `八个函数均已落地且不再是占位`() {
        val names = listOf(
            "HTML_STRINGLEN", "HTML_SUBSTRING", "HTML_STRINGLINES", "HTML_TOPLAINTEXT",
            "HTML_ESCAPE", "HTML_GETPRINTEDSTR", "HTML_POPPRINTINGSTR", "GETDISPLAYLINE",
        )
        for (n in names) {
            assertNotNull(Builtins.registry[n], "函数 $n 未注册")
            assertFalse(Builtins.registry.isPlaceholder(n), "函数 $n 仍是占位实现")
        }
        assertTrue(Builtins.registry.names().contains("HTML_STRINGLEN"))
    }
}
