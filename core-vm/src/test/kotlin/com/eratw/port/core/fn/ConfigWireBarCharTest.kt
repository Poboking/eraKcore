package com.eratw.port.core.fn

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U3 复核钉：`BAR文字1` / `BAR文字2` 是**已接线**的键，别当缺口重接。
 *
 * C# 侧这两项的唯一运行期消费是 `ExpressionMediator.cs:155-156` 的 BARSTR
 * （`Config.cs:730-731` 把 `ConfigData.cs:164-165` 的 REPLACE 组条目读成 char；
 * `PrintBar()`（`EmueraConsole.cs:3388-3393`）打的是 `stBar`，属 `DrawLineString`，
 * 不是这一族）。移植侧 `HostMethods.barChars` 读 `BuiltinHost.configOverrides`，
 * `BootHosts.kt` 的链重放已把 `CSV/_Replace.csv` 的 REPLACE 键写进覆盖表
 * （`ReplaceWireTest` 钉的是条目表这一半）。本类补另一半：**BARSTR 的真输出**跟着改。
 *
 * 出厂缺省 `'*'` / `'.'` 与 `ConfigItem.cs:119-126` 的单码元守卫也一并钉住。
 */
class ConfigWireBarCharTest {

    private fun ctxWith(overrides: Map<String, String>): BuiltinEvalContext {
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                error("BARSTR 不应触发函数调用：$name")
            override fun printSystemLine(message: String) = Unit
        }
        base.variables = store
        val host = object : BuiltinHost {
            override val configOverrides: Map<String, String> = overrides
        }
        return BuiltinEvalContext(base, store, host)
    }

    private fun barstr(ctx: BuiltinEvalContext, value: Long, max: Long, length: Long): String =
        (ctx.callFunction(
            "BARSTR",
            listOf(Value.IntV(value), Value.IntV(max), Value.IntV(length)),
        ) as Value.StrV).v

    @Test
    fun `Replace 的 BAR 两个字符进 BARSTR 输出`() {
        val ctx = ctxWith(mapOf("BAR CHARACTER 1" to "=", "BAR CHARACTER 2" to "-"))
        assertEquals("[===-------]", barstr(ctx, 30, 100, 10), "ExpressionMediator.cs:155-156")
    }

    @Test
    fun `日文标签同样命中且缺省值不受影响`() {
        val jp = ctxWith(mapOf("BAR文字1" to "#", "BAR文字2" to "_"))
        assertEquals("[###_______]", barstr(jp, 30, 100, 10))
        val def = ctxWith(emptyMap())
        assertEquals("[***.......]", barstr(def, 30, 100, 10), "ConfigData.cs:164-165 出厂 '*' / '.'")
    }

    @Test
    fun `非单码元的 Replace 值按原版保留出厂字符`() {
        val bad = ctxWith(mapOf("BAR CHARACTER 1" to "AB", "BAR CHARACTER 2" to ""))
        assertEquals("[***.......]", barstr(bad, 30, 100, 10), "ConfigItem.cs:119-126 char.TryParse 失败不改写")
    }
}
