package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FN-04 core：`HTML_STRINGLEN` 的量宽必须按**段**的字体名 / 粗斜体位 / 字距请求宿主，
 * 而不是把整串 HTML 当纯文本量一次。
 *
 * 原版 `ConsoleStyledString.SetWidth` → `StringMeasure.GetDisplayLength`
 * （ConsoleStyledString.cs:75-95、StringMeasure.cs:42-72）按段的 `StringStyle`
 * （字体名 + 样式位，StringStyle.cs:20-40）从 `FontFactory` 取字体后实测；
 * `HtmlMethods.measureText` 的三段回落链见其 KDoc。
 *
 * 本文件钉三件事：
 * 1. 粗体 / 斜体 / `<font face>` 各得**独立** request（样式位与字体名逐字段对）；
 * 2. `measureTextRun` 返回 null 时回落到旧 [BuiltinHost.measureText]；
 * 3. 两者都未接线时读数与改造前逐位一致（`<i>AB</i>CD` = 36px）。
 */
class TextMeasureStyleTest {

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /**
     * 记录型宿主：每次带样式量宽都记 request；[run] 为 null 表示该宿主未接这条通道，
     * 由 [BuiltinHost] 缺省实现返回 null。
     */
    private class RecordingHost(
        val run: ((TextMeasureRequest) -> Long?)? = null,
        override val measureText: ((String) -> Long)? = null,
        override val fontSize: Long = 18L,
    ) : BuiltinHost {
        val requests = ArrayList<TextMeasureRequest>()

        override fun measureTextRun(request: TextMeasureRequest): Long? {
            requests += request
            return run?.invoke(request)
        }
    }

    private fun ctx(host: BuiltinHost): BuiltinEvalContext {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun plainWidth(host: BuiltinHost, html: String): Long =
        (ctx(host).callFunction("HTML_STRINGLEN", listOf(Value.StrV(html), Value.IntV(1))) as Value.IntV).v

    /** 可控宽度：半角 = 字号一半，全角 = 字号（与内建近似同式，但走的是新通道）。 */
    private fun emWidth(r: TextMeasureRequest): Long {
        var w = 0L
        var i = 0
        while (i < r.text.length) {
            val cp = r.text.codePointAt(i)
            w += if (com.eratw.port.core.host.CellWidth.isFullWidth(cp)) r.fontSize else r.fontSize / 2
            i += Character.charCount(cp)
        }
        return w
    }

    // ------------------------------------------------------------------
    // 1) 分段：粗体 / 斜体 / face 各得独立 request
    // ------------------------------------------------------------------

    @Test
    fun `粗体与斜体文本各得独立 request`() {
        val host = RecordingHost(run = ::emWidth)
        // 两个 ASCII 半角：粗体段 9px、斜体段 9px，合计 18px。
        assertEquals(18L, plainWidth(host, "<b>A</b><i>B</i>"))
        assertEquals(
            listOf(
                TextMeasureRequest("A", "", 18L, styleMask = 1),
                TextMeasureRequest("B", "", 18L, styleMask = 2),
            ),
            host.requests,
            "样式位必须逐段独立（1 = 粗体、2 = 斜体，Process.cs:1508-1527）",
        )
    }

    @Test
    fun `嵌套样式位按栈叠加`() {
        val host = RecordingHost(run = ::emWidth)
        plainWidth(host, "<b>A<i>B</i>C</b>")
        assertEquals(
            listOf(
                TextMeasureRequest("A", "", 18L, styleMask = 1),
                TextMeasureRequest("B", "", 18L, styleMask = 3),
                TextMeasureRequest("C", "", 18L, styleMask = 1),
            ),
            host.requests,
        )
    }

    @Test
    fun `font face 进入 request 的字体名`() {
        val host = RecordingHost(run = ::emWidth)
        // 「あ」全角 18px；「AB」半角各 9px，共 36px。
        assertEquals(36L, plainWidth(host, "<font face='黑体'>あ</font>AB"))
        assertEquals(
            listOf(
                TextMeasureRequest("あ", "黑体", 18L, styleMask = 0),
                TextMeasureRequest("AB", "", 18L, styleMask = 0),
            ),
            host.requests,
            "空字体名 = 原版缺省族（StringStyle.cs:26-31）",
        )
    }

    // ------------------------------------------------------------------
    // 2) 回落链
    // ------------------------------------------------------------------

    @Test
    fun `带样式回调返回 null 时回落旧纯文本回调`() {
        val host = RecordingHost(
            run = { null },
            measureText = { s -> s.length * 5L },
        )
        // 未接新通道的宿主仍按旧回调量：4 个字符 × 5 = 20px。
        assertEquals(20L, plainWidth(host, "<i>AB</i>CD"))
        assertEquals(2, host.requests.size, "请求仍会发出，只是宿主选择不回答")
    }

    @Test
    fun `两条回调都未接线时读数与改造前一致`() {
        val host = RecordingHost()
        // 内建等宽近似：4 个 ASCII 半角 × 18/2 = 36px（基准 pc-golden-root.txt:297）。
        assertEquals(36L, plainWidth(host, "<i>AB</i>CD"))
        assertEquals(2, host.requests.size)
        // 纯文本快速路径同样走新通道（样式位 0、字体名空）。
        host.requests.clear()
        assertEquals(36L, plainWidth(host, "ABCD"))
        assertEquals(listOf(TextMeasureRequest("ABCD", "", 18L, 0)), host.requests)
    }

    @Test
    fun `HTML_STRINGLEN 第一参仍按像素折格数`() {
        val host = RecordingHost(run = ::emWidth)
        // 第二参省略/为 0：2*36/18 = 4（FunctionMethodCreator.cs:58-63）。
        assertEquals(
            4L,
            (ctx(host).callFunction("HTML_STRINGLEN", listOf(Value.StrV("<b>AB</b>CD"))) as Value.IntV).v,
        )
    }
}
