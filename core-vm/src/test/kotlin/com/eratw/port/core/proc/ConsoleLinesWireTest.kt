package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.NoopConsoleSink
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HOST-02（CONTRACT-Q1 §2.2）生产接线回归：真实 [LineBufferConsoleSink] +
 * [ConsoleBuiltinHost] 装配下验证 HTML_GETPRINTEDSTR / HTML_POPPRINTINGSTR /
 * GETDISPLAYLINE 的取值与 POP 的读取即消费副作用。
 *
 * 判据出处（全部来自反编译 C#，不是由实现反推）：
 * - GETDISPLAYLINE 按**显示行**下标取 `ConsoleDisplayLine.ToString()`
 *   （FunctionMethodCreator.cs:8415-8440；ConsoleDisplayLine.cs:127-140；
 *   ConsoleButtonString.cs:375-387），越界（含负数）返回 ""（:8432-8436）；
 * - HTML_GETPRINTEDSTR 从最新一行往回数**逻辑行**，命中即停，再套
 *   `<p align='...'><nobr>` 外壳（FunctionMethodCreator.cs:6076-6110；
 *   EmueraConsole.cs:3546-3576；HtmlManager.cs:381-400、:473-477）；
 * - HTML_POPPRINTINGSTR 读取即 `printBuffer.Flush` 并清空缓冲（FunctionMethodCreator.cs:6112-6130；
 *   EmueraConsole.cs:3578-3588；PrintStringBuffer.cs:179-186、:273-278），
 *   弹出的行不进 displayLineList（PopDisplayingLines 方法体没有 addDisplayLine）。
 *
 * 断言里的 HTML 形状取自 DisplayLine2Html 的逐节点规则：按钮段包
 * `<button value='...'>`（HtmlManager.cs:415-470），SETCOLOR 后的文本包
 * `<font color='#RRGGBB'>`（:864-915、:917-940），HTML_PRINT 的段按原文保留
 * （原版解析后重建的样式标签与之同形，HtmlManager.cs:446-464）。
 */
class ConsoleLinesWireTest {

    // ===== 取值：GETDISPLAYLINE =====

    @Test
    fun `GETDISPLAYLINE 按显示行下标取文本 越界与负数回空串`() =
        withTempRoot("eratw-console-lines") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            console.printLine("LINE-0")
            console.printLine("LINE-1")
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            assertEquals("LINE-0", wireStr(host, "GETDISPLAYLINE", num(0)))
            assertEquals("LINE-1", wireStr(host, "GETDISPLAYLINE", num(1)))
            // 原版 intValue >= DisplayLineList.Count → ""（FunctionMethodCreator.cs:8433-8436）
            assertEquals("", wireStr(host, "GETDISPLAYLINE", num(2)))
            assertEquals("", wireStr(host, "GETDISPLAYLINE", num(-1)))
        }

    @Test
    fun `GETDISPLAYLINE 的单位是显示行 折行续行各占一个下标`() =
        withTempRoot("eratw-console-lines-wrap") { root ->
            val console = LineBufferConsoleSink(clientWidth = 5)
            console.printLine("ABCDEFGH")
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // 折行：ABCDE + FGH 两条显示行、一个逻辑行（PrintStringBuffer.cs:207-262）。
            assertEquals(2, console.displayLineCount)
            assertEquals("ABCDE", wireStr(host, "GETDISPLAYLINE", num(0)))
            assertEquals("FGH", wireStr(host, "GETDISPLAYLINE", num(1)))
            // HTML_GETPRINTEDSTR 收同一逻辑行的全部显示行，行间以 <br> 连接（:409-412）。
            assertEquals(
                "<p align='left'><nobr>ABCDE<br>FGH</nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )
        }

    // ===== 取值：HTML_GETPRINTEDSTR =====

    @Test
    fun `HTML_GETPRINTEDSTR 取最近逻辑行并加 p align 与 nobr 外壳`() =
        withTempRoot("eratw-console-printed") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            console.printLine("FIRST")
            console.setAlignment(Alignment.CENTER)
            console.printLine("SECOND")
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // 外壳只取 lines[0] 的对齐（HtmlManager.cs:387-400、:473-477）。
            assertEquals(
                "<p align='center'><nobr>SECOND</nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )
            assertEquals(
                "<p align='left'><nobr>FIRST</nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR", num(1)),
            )
            // lineNo > Count → GetDisplayLines 返回 null → ""（:3548，注意是 > 不是 >=；
            // lineNo == Count 扫完也凑不出行 → 同样 ""）。
            assertEquals("", wireStr(host, "HTML_GETPRINTEDSTR", num(2)))
            assertEquals("", wireStr(host, "HTML_GETPRINTEDSTR", num(3)))
        }

    @Test
    fun `HTML_GETPRINTEDSTR 行体重建 SETCOLOR 的 font color 标签`() =
        withTempRoot("eratw-console-printed-color") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            // 开 `UseUserStyle` 轴：出厂 false（EmueraConsole.cs:467），只有 PRINT 系语句写真
            // （FunctionIdentifier.cs:179/:276、Process.cs:1110/:1129/:1149）。本用例绕过语句层
            // 直接调 sink 的 printLine，等价于「站在 PRINT 语句里」；不开轴则 Style 取值器
            // 恒走 defaultStyle（:471-472），ColorChanged 恒为 false、不会补 font 标签。
            console.setUseUserStyle(true)
            console.setForeColor(255, 0, 0)
            console.printLine("RED")
            console.resetForeColor()
            console.printLine("PLAIN")
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // 原版 ColorChanged = SETCOLOR 的颜色 != Config.ForeColor（EmueraConsole.cs:2823），
            // 行体在 DisplayLine2Html 里补 <font color='#RRGGBB'>（HtmlManager.cs:864-915）。
            assertEquals(
                "<p align='left'><nobr><font color='#FF0000'>RED</font></nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR", num(1)),
            )
            assertEquals(
                "<p align='left'><nobr>PLAIN</nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )
        }

    @Test
    fun `HTML_GETPRINTEDSTR 空白行复现 COMMON 的逐字比较`() =
        withTempRoot("eratw-console-printed-blank") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            // 空缓冲的 NewLine 按原版补一个空格（EmueraConsole.cs:3377-3382），
            // 语料 COMMON.ERB:4267/4270 就是拿这条串做逐字比较。
            console.newLine()
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            assertEquals(
                "<p align='left'><nobr> </nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )
            assertEquals(" ", wireStr(host, "GETDISPLAYLINE", num(0)))
        }

    @Test
    fun `HTML_PRINT 行在读取侧表现为可见文本与行体原文`() =
        withTempRoot("eratw-console-printed-html") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            console.printHtml("<b>HTML</b>PLAIN", option = false)
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // 文本 = Html2PlainText 口径（去标签 + Unescape，HtmlManager.cs:714-717）。
            assertEquals("HTMLPLAIN", wireStr(host, "GETDISPLAYLINE", num(0)))
            // 行体保留 HTML 原文；原版解析后重建的样式标签与之同形（HtmlManager.cs:446-464）。
            assertEquals(
                "<p align='left'><nobr><b>HTML</b>PLAIN</nobr></p>",
                wireStr(host, "HTML_GETPRINTEDSTR"),
            )
        }

    // ===== 取值 + 消费：HTML_POPPRINTINGSTR =====

    @Test
    fun `HTML_POPPRINTINGSTR 读取即消费 第二次为空且不进显示行列表`() =
        withTempRoot("eratw-console-pop") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            console.printButton("GO", 42)
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            assertFalse(console.printingBufferEmpty, "待决按钮应让 LINEISEMPTY=0")
            // 按钮段包 <button value='42'>（ConsoleButtonString.cs:81-95、
            // DisplayLine2Html 的按钮分支 HtmlManager.cs:415-470）。
            assertEquals("<button value='42'>GO</button>", wireStr(host, "HTML_POPPRINTINGSTR"))

            // Flush 末尾 clearBuffer（PrintStringBuffer.cs:179-186、:273-278）：
            // 第一次取走即清空，第二次读到空表 → 函数返回 ""。
            assertTrue(console.printingBufferEmpty)
            assertTrue(host.lineIsEmpty, "POP 后 LINEISEMPTY 口径跟随待决缓冲 → 1")
            assertEquals("", wireStr(host, "HTML_POPPRINTINGSTR"))

            // 弹出的行只排版不落列表（PopDisplayingLines 没有 addDisplayLine）：
            // 显示行仍为空，GETDISPLAYLINE 看不到它们。
            assertTrue(console.lines().isEmpty())
            assertEquals("", wireStr(host, "GETDISPLAYLINE", num(0)))
        }

    @Test
    fun `HTML_POPPRINTINGSTR 对纯文本行体做转义`() =
        withTempRoot("eratw-console-pop-escape") { root ->
            val console = LineBufferConsoleSink(clientWidth = 199)
            console.print("A&B ", lineEnd = false)
            console.printPlain("C<D")
            val host: BuiltinHost = ConsoleBuiltinHost(root, console)

            // Escape 的五个字符表（HtmlManager.cs:372-378、:719-742）。
            assertEquals("A&amp;B C&lt;D", wireStr(host, "HTML_POPPRINTINGSTR"))
        }

    // ===== 未接线口径 =====

    @Test
    fun `无控制台时三函数仍按宿主未接线抛 CodeEE`() =
        withTempRoot("eratw-console-none") { root ->
            val host: BuiltinHost = ConsoleBuiltinHost(root, null)

            val get = assertFailsWith<CodeEE> { wireStr(host, "HTML_GETPRINTEDSTR") }
            assertTrue(get.message!!.contains("consoleLines == null"), "实际：${get.message}")
            val pop = assertFailsWith<CodeEE> { wireStr(host, "HTML_POPPRINTINGSTR") }
            assertTrue(pop.message!!.contains("popPrintingLines == null"), "实际：${pop.message}")
            val line = assertFailsWith<CodeEE> { wireStr(host, "GETDISPLAYLINE", num(0)) }
            assertTrue(line.message!!.contains("consoleLines == null"), "实际：${line.message}")
        }

    @Test
    fun `不追踪行缓冲的 sink 仍按宿主未接线抛 CodeEE`() =
        withTempRoot("eratw-console-noop") { root ->
            // ConsoleSink 的两个新访问器缺省 null（其他 sink 与测试假 sink 零改动）：
            // 宿主不得把「不追踪」静默当成空表。
            val host: BuiltinHost = ConsoleBuiltinHost(root, NoopConsoleSink)

            assertNull(host.consoleLines)
            assertNull(host.popPrintingLines)
            val get = assertFailsWith<CodeEE> { wireStr(host, "HTML_GETPRINTEDSTR") }
            assertTrue(get.message!!.contains("consoleLines == null"), "实际：${get.message}")
            val pop = assertFailsWith<CodeEE> { wireStr(host, "HTML_POPPRINTINGSTR") }
            assertTrue(pop.message!!.contains("popPrintingLines == null"), "实际：${pop.message}")
        }

    // ===== 装置 =====

    private fun <T> withTempRoot(prefix: String, body: (Path) -> T): T {
        val root = Files.createTempDirectory(prefix)
        try {
            return body(root)
        } finally {
            runCatching {
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder<Path>()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 只承接 [BuiltinEvalContext] 的变量回调；内建函数求值不经过它。 */
    private class WireBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("WireBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    private fun wireEval(host: BuiltinHost, name: String, vararg args: Value): Value {
        val store = VariableStore()
        val base = WireBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host).callFunction(name, args.toList())
    }

    private fun wireStr(host: BuiltinHost, name: String, vararg args: Value): String =
        (wireEval(host, name, *args) as Value.StrV).v

    private fun num(n: Long): Value = Value.IntV(n)
}
