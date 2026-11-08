package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.TextMeasureRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FN-04 分段量宽在**生产宿主链**上的接线钉板（FONT-MEASURE F1）。
 *
 * 链路：app `GameHost`（`AndroidTextMeasure.measurePixels` 闭包）→ `GameSession` 构造参
 * `textMeasureRun`（GameSession.kt）→ `ConsoleBuiltinHost.textMeasureRun` →
 * `measureTextRun` 覆写。本类钉的是链上**只能在 core 层验证**的两环：
 *
 * 1. **委托陷阱**：`ConsoleBuiltinHost` 是 `BuiltinHost by delegate`，带默认实现的接口成员
 *    会被 Kotlin 转给委托对象（缺省 null）——`measureTextRun` 因此必须**显式覆写**
 *    （ConsoleBuiltinHost.kt 的覆写清单；同 `consoleSink` / `popPrintingLines` 一族）。
 *    覆写一旦被删，注入的闭包就会被委托的缺省实现遮成 null：真机静默退回等宽近似、
 *    桌面/装置测试全绿（它们本就不接）——只能在这里用注入实例钉死。
 * 2. 消费串级：`HTML_STRINGLEN`（HtmlMethods.measureText 的三段回落链）经本宿主
 *    优先吃注入的 measureTextRun，字体名 / 样式位 / 字号逐字段透传。
 */
class ConsoleBuiltinHostMeasureWireTest {

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

    private class WireBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("WireBaseCtx 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    private fun eval(host: BuiltinHost, name: String, vararg args: Value): Value {
        val store = VariableStore()
        val base = WireBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host).callFunction(name, args.toList())
    }

    private fun num(n: Long): Value = Value.IntV(n)

    private fun str(s: String): Value = Value.StrV(s)

    @Test
    fun `注入的 textMeasureRun 压过委托的缺省 null`() = withTempRoot("eratw-measure-wire") { root ->
        val console = LineBufferConsoleSink(clientWidth = 199)
        var called = 0
        val host: BuiltinHost = ConsoleBuiltinHost(
            root,
            console,
            textMeasureRun = { req ->
                called++
                req.text.length * 7L
            },
        )
        assertEquals(21L, host.measureTextRun(TextMeasureRequest("ABC", "", 18L, 0)))
        assertEquals(1, called, "注入闭包必须被直接调用（Kotlin 委托会把本成员转给 delegate）")
    }

    @Test
    fun `未注入时 measureTextRun 回落委托缺省 null`() = withTempRoot("eratw-measure-wire-null") { root ->
        val host: BuiltinHost = ConsoleBuiltinHost(root, LineBufferConsoleSink(clientWidth = 199))
        assertNull(host.measureTextRun(TextMeasureRequest("ABC", "", 18L, 0)))
    }

    @Test
    fun `HTML_STRINGLEN 经生产宿主吃注入度量并透传字号`() = withTempRoot("eratw-measure-wire-fn") { root ->
        val console = LineBufferConsoleSink(clientWidth = 199)
        val seen = ArrayList<TextMeasureRequest>()
        // 生产装配形状：字号由 GameSession 喂生效配置字号（scriptFontPixelSize）。
        val host: BuiltinHost = ConsoleBuiltinHost(
            root,
            console,
            fontSize = 20L,
            textMeasureRun = { req ->
                seen += req
                req.text.length * req.fontSize
            },
        )
        // 4 个 ASCII 半角 × 20px = 80：字号透传（不是缺省 18 ⇒ 72）。
        assertEquals(80L, (eval(host, "HTML_STRINGLEN", str("ABCD"), num(1)) as Value.IntV).v)
        assertEquals(listOf(TextMeasureRequest("ABCD", "", 20L, 0)), seen)
    }
}
