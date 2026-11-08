package com.eratw.port.core.fn

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.NoopConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U5 收口（COLORDEF-WIRE 波）：GETDEF 色族缺省口从**工厂常量**改吃
 * **sink 注入值**。
 *
 * 接线前的缺口：[consoleStyleOf] 的 defFore/defBack/focus 三项缺省参数直接吃
 * `LineBufferConsoleSink.DEFAULT_*_COLOR` 工厂常量，而 CONFIG-WIRE U5 只把
 * emuera.config 条目喂进了 sink 的注入构造参数（`defaultForeColor`/`defaultBackColor`/
 * `focusColor`）——宿主没显式传缺省时，GETDEFCOLOR / GETDEFBGCOLOR / GETFOCUSCOLOR
 * （以及 GETCOLOR / GETBGCOLOR 未显式设置的回落）报工厂值，真机改条目后函数面脱钩。
 *
 * 原版语义：这些函数读 `Config` 静态属性**现值**——
 * - GETDEFCOLOR / GETCOLOR：`Config.ForeColor` / `Console.StringStyle.Color`
 *   （FunctionMethodCreator.cs:3662，StringStyle 初值 = Config.ForeColor，EmueraConsole.cs:206-208）；
 * - GETFOCUSCOLOR：`Config.FocusColor`（:3677）；
 * - GETDEFBGCOLOR / GETBGCOLOR：`Config.BackColor` / `Console.bgColor`
 *   （:3695，bgColor 声明即 = Config.BackColor，EmueraConsole.cs:200）。
 * 移植侧的「配置现值」就是 U5 注入值（GameSession 按 emuera.config 条目喂 sink，
 * 会话期定死、不做运行中热更——CONTRACT-CONFIG-WIRE §2 口径）。
 *
 * 解析次序（[consoleStyleOf] KDoc）：显式参数 > sink 注入值 > 工厂常量。
 * 本类用真 [LineBufferConsoleSink] 走**缺省派生路径**（宿主只接 [ConsoleSink]，
 * 不覆写 [BuiltinHost.consoleStyle]，与生产 [com.eratw.port.core.proc.ConsoleBuiltinHost]
 * 的装配同形状），断言经真函数面（[BuiltinEvalContext.callFunction]）读数。
 *
 * 基准锚：pc-golden-root.txt:266 `DEF=12632256 FOCUS=16776960`、:269 `DEFBG=0`。
 */
class ConfigWireColorDefTest {

    /** 最小宿主：只接控制台，让 `BuiltinHost.consoleStyle` 的缺省派生成为被测路径。 */
    private fun ctxOf(sink: ConsoleSink?): BuiltinEvalContext {
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                error("色族查询不应触发函数调用：$name")
        }
        base.variables = store
        val host = object : BuiltinHost {
            override val consoleSink: ConsoleSink? get() = sink
        }
        return BuiltinEvalContext(base, store, host)
    }

    private fun i(ctx: BuiltinEvalContext, name: String): Long =
        (ctx.callFunction(name, emptyList()) as Value.IntV).v

    /** 非出厂注入三色（GameSession 装配同款：emuera.config 条目解析成的 0xRRGGBB）。 */
    private fun wiredSink() = LineBufferConsoleSink(
        defaultForeColor = 0x0A141E,
        defaultBackColor = 0x28323C,
        focusColor = 0x46505A,
    )

    @Test
    fun `注入非出厂值时 GETDEF 三族报注入值`() {
        val ctx = ctxOf(wiredSink())
        assertEquals(
            0x0A141EL, i(ctx, "GETDEFCOLOR"),
            "Config.ForeColor = 注入值（FunctionMethodCreator.cs:3662 的 defaultColor 分支）",
        )
        assertEquals(
            0x28323CL, i(ctx, "GETDEFBGCOLOR"),
            "Config.BackColor = 注入值（FunctionMethodCreator.cs:3695）",
        )
        assertEquals(
            0x46505AL, i(ctx, "GETFOCUSCOLOR"),
            "Config.FocusColor = 注入值（FunctionMethodCreator.cs:3677）",
        )
    }

    @Test
    fun `GETCOLOR 与 GETBGCOLOR 未设置回落注入值`() {
        val sink = wiredSink()
        val ctx = ctxOf(sink)
        // 原版未 SETCOLOR 时 StringStyle.Color 初值就是 Config.ForeColor
        //（EmueraConsole.cs:207）→ 未设置回落必须与 GETDEFCOLOR 同一口。
        assertEquals(0x0A141EL, i(ctx, "GETCOLOR"))
        // bgColor 声明即 = Config.BackColor（EmueraConsole.cs:200）。
        assertEquals(0x28323CL, i(ctx, "GETBGCOLOR"))
        // 当前值通道不受缺省口影响：SETCOLOR 后 GETCOLOR 报设置值（:3662 非 defaultColor 分支）。
        sink.setForeColor(255, 0, 0)
        sink.setBackColor(0, 255, 0)
        assertEquals(0xFF0000L, i(ctx, "GETCOLOR"))
        assertEquals(0x00FF00L, i(ctx, "GETBGCOLOR"))
        assertEquals(0x0A141EL, i(ctx, "GETDEFCOLOR"), "SETCOLOR 不带动默认色")
        // RESETCOLOR / RESETBGCOLOR → 未设置 → 回落注入值
        //（FunctionIdentifier.cs:1501/:1515 落 Config.ForeColor / Config.BackColor）。
        sink.resetForeColor()
        sink.resetBackColor()
        assertEquals(0x0A141EL, i(ctx, "GETCOLOR"))
        assertEquals(0x28323CL, i(ctx, "GETBGCOLOR"))
    }

    @Test
    fun `出厂注入下与黄金基准读数逐字不变`() {
        // 缺省构造 = 注入值即工厂常量（语料 emuera.config:24-26 与出厂同值的形态）。
        val ctx = ctxOf(LineBufferConsoleSink())
        assertEquals(12632256L, i(ctx, "GETDEFCOLOR"), "pc-golden-root.txt:266 DEF（ConfigData.cs:68）")
        assertEquals(16776960L, i(ctx, "GETFOCUSCOLOR"), "pc-golden-root.txt:266 FOCUS（ConfigData.cs:70）")
        assertEquals(0L, i(ctx, "GETDEFBGCOLOR"), "pc-golden-root.txt:269 DEFBG（ConfigData.cs:69）")
        assertEquals(12632256L, i(ctx, "GETCOLOR"), "未设置回落 = 基准 267 行 RESET 同口")
        assertEquals(0L, i(ctx, "GETBGCOLOR"), "未设置回落 = 基准 270 行 BGRESET 同口")
    }

    @Test
    fun `显式参数压过 sink 注入`() {
        val snapshot = consoleStyleOf(
            wiredSink(),
            defaultFore = intArrayOf(1, 2, 3),
            defaultBack = intArrayOf(4, 5, 6),
            focusColor = intArrayOf(7, 8, 9),
        )
        assertEquals(listOf(1, 2, 3), snapshot.defFore.toList())
        assertEquals(listOf(4, 5, 6), snapshot.defBack.toList())
        assertEquals(listOf(7, 8, 9), snapshot.focus.toList())
    }

    @Test
    fun `不携带注入的宿主回落工厂常量`() {
        // NoopConsoleSink 下溯落空 → 工厂常量（改前行为逐字相同，Noop 的既有断言零漂移）。
        val snapshot = consoleStyleOf(NoopConsoleSink)
        assertEquals(
            LineBufferConsoleSink.DEFAULT_FORE_COLOR.toRgbComponents().toList(),
            snapshot.defFore.toList(),
        )
        assertEquals(
            LineBufferConsoleSink.DEFAULT_BACK_COLOR.toRgbComponents().toList(),
            snapshot.defBack.toList(),
        )
        assertEquals(
            LineBufferConsoleSink.DEFAULT_FOCUS_COLOR.toRgbComponents().toList(),
            snapshot.focus.toList(),
        )
    }
}
