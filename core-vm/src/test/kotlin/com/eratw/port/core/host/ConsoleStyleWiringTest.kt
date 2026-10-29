package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConsoleStyleSnapshot
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ===========================================================================
// HOST-STYLE：控制台样式读线（sink 的活状态 → ConsoleStyleSnapshot → GETCOLOR 家族）
//
// 原版没有「样式快照」这个类型：GETCOLOR / GETBGCOLOR / GETSTYLE / GETFONT /
// CURRENTALIGN / CURRENTREDRAW 全部直接读 EmueraConsole 对象上的字段——
//   StringStyle(= userStyle) . Color / FontStyle / Fontname（EmueraConsole.cs:491、:207、
//     :2815-2818、:2820-2824、:2826-2835；StringStyle.cs:26-31 把空字体名归一成 Config.FontName）、
//   bgColor（:200 声明即 = Config.BackColor、:2844-2858）、
//   Alignment（:493-503）、Redraw（:455，写入折减见 :1693-1707）。
// 语句写这份字段、函数读同一份字段，中间没有第二处记账。本包测的就是移植版没有把这条
// 读线接成第二份状态：BuiltinHost.consoleStyle 的缺省实现从 ConsoleSink 的读出侧现取现算
// （core/fn/Builtins.kt 的 consoleStyleOf），断言逐条回指上面的行号与黄金基准行。
// ===========================================================================

/** 只用控制台读出侧的最小宿主：故意**不**覆写 consoleStyle / lineIsEmpty，让缺省派生路径成为被测对象。 */
private class SinkOnlyHost(private val console: ConsoleSink?) : BuiltinHost {
    override val consoleSink: ConsoleSink? get() = console
}

private class StyleBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("StyleBaseCtx 不该被直接调用：$name")
}

private class Harness(sink: ConsoleSink?) {
    val host: BuiltinHost = SinkOnlyHost(sink)
    private val store = VariableStore()
    private val ctx: BuiltinEvalContext = run {
        val base = StyleBaseCtx()
        base.variables = store
        BuiltinEvalContext(base, store, host)
    }

    fun i(name: String): Long = (ctx.callFunction(name, emptyList()) as Value.IntV).v
    fun s(name: String): String = (ctx.callFunction(name, emptyList()) as Value.StrV).v
}

/** 基准侧的颜色读数（pc-golden-root.txt:263-290）。 */
private const val PC_FORE = 12632256L      // 266 DEF / 267 RESET / 12632256 = 0xC0C0C0（ConfigData.cs:68）
private const val PC_RED = 16711680L       // 264 RED（SETCOLOR 0xFF0000）
private const val PC_RGB = 33023L          // 265 RGB（SETCOLOR 0,128,255）
private const val PC_FOCU = 16776960L      // 266 FOCUS（= 0xFFFF00，ConfigData.cs:70）
private const val PC_BG_GREEN = 65280L     // 268 BG（SETBGCOLOR 0x00FF00）
private const val PC_BACK = 0L             // 269 DEFBG / 270 BGRESET（ConfigData.cs:69）

class ConsoleStyleWiringTest {

    /** 生产 sink：字体名按 PC 环境注入黑体（游戏 emuera.config 的 FONT NAME，基准 279-280 行）。 */
    /**
     * 生产口径的 sink，并把 `UseUserStyle` 这一轴打开。
     *
     * 原版该字段出厂 false（`EmueraConsole.cs:467` 的 `bool` 默认值），由 PRINT 系语句在
     * 求值前写真（`FunctionIdentifier.cs:179`/`:276`、`Process.cs:1110`/`:1129`/`:1149`）；
     * 写假只发生在系统行/错误行/回标题那几处（`EmueraConsole.cs:764`、`:2575`、`:3094`、
     * `:3108` 等）。本类用例是「站在 PRINT 语句里看打印路径与查询路径是否同源」，
     * 不先开轴则 `Style` 取值器恒走 `!UseUserStyle → defaultStyle`（`:471-472`），
     * 打印侧的样式快照永远是出厂态，与查询侧（直接读 `userStyle` 字段）必然分叉。
     */
    private fun prodSink() =
        LineBufferConsoleSink(clientWidth = 1800, defaultFontName = "黑体").also { it.setUseUserStyle(true) }

    // ---- 1. REDRAW 的折减：写侧口径不变，读侧只露两态 ----

    @Test
    fun `REDRAW 原始整数折减成两态后 CURRENTREDRAW 才读数`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 折减规则 spec: EmueraConsole.cs:1693-1707（(i & 1)==0 → None，否则 Normal；
        // (i & 2) 只是 RefreshStrings 的立即重绘副作用，不进状态）。
        // 读数规则 spec: FunctionMethodCreator.cs:3793-3806，取值在 :3804
        //（exm.Console.Redraw != ConsoleRedraw.None ? 1 : 0），枚举序 None=0 / Normal=1
        //（ConsoleRedraw.cs:6-10）。
        sink.setRedraw(0)
        assertEquals(0L, h.i("CURRENTREDRAW"), "REDRAW 0 → None → 0")
        assertEquals(0, sink.redraw, "快照字段必须是折减后的状态")

        sink.setRedraw(1)
        assertEquals(1L, h.i("CURRENTREDRAW"), "REDRAW 1 → Normal → 1")
        assertEquals(1, sink.redraw)

        // 3 = bit0|bit1：状态仍是 Normal（1），不是原样 3。
        sink.setRedraw(3)
        assertEquals(1L, h.i("CURRENTREDRAW"), "REDRAW 3 的 bit1 不改状态")
        assertEquals(1, sink.redraw, "读出侧必须已折减，不许把 3 透出去")

        // 2 = 只有 bit1：bit0=0 → None（原版这一条只立即重绘，状态清零）。
        sink.setRedraw(2)
        assertEquals(0L, h.i("CURRENTREDRAW"), "REDRAW 2 → None → 0")
        assertEquals(0, sink.redraw)
    }

    // ---- 2. SETCOLOR / RESETCOLOR 与默认色回落 ----

    @Test
    fun `SETCOLOR 之后 GETCOLOR 读到新值且 RESETCOLOR 回落 Config ForeColor`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 出厂态：原版 userStyle 的 Color 初值就是 Config.ForeColor（EmueraConsole.cs:207），
        // 所以「未设置」读到 0xC0C0C0，与基准 266 行 DEF 同值。
        assertEquals(PC_FORE, h.i("GETCOLOR"))
        // SETCOLOR 0xFF0000 → 基准 264 行 RED；写侧 spec: EmueraConsole.cs:2820-2824
        sink.setForeColor(255, 0, 0)
        assertEquals(PC_RED, h.i("GETCOLOR"))
        // GETDEFCOLOR 读的是 Config 而不是当前值（FunctionMethodCreator.cs:3662 的 defaultColor 分支）
        assertEquals(PC_FORE, h.i("GETDEFCOLOR"), "默认色不许被 SETCOLOR 带动")
        // RESETCOLOR = SetStringStyle(Config.ForeColor)（FunctionIdentifier.cs:1501）→ 基准 267 行 RESET
        sink.resetForeColor()
        assertEquals(PC_FORE, h.i("GETCOLOR"))
        // 同一 sink 再走一次 RGB 分支（基准 265 行 RGB=33023 = SETCOLOR 0,128,255）
        sink.setForeColor(0, 128, 255)
        assertEquals(PC_RGB, h.i("GETCOLOR"))
    }

    @Test
    fun `SETBGCOLOR 与 RESETBGCOLOR 走同一条读线`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 出厂态：bgColor 声明即 = Config.BackColor（EmueraConsole.cs:200）→ 基准 269 行 DEFBG
        assertEquals(PC_BACK, h.i("GETBGCOLOR"))
        // SETBGCOLOR（spec: EmueraConsole.cs:2844-2858）→ 基准 268 行 BG=65280
        sink.setBackColor(0, 255, 0)
        assertEquals(PC_BG_GREEN, h.i("GETBGCOLOR"))
        assertEquals(PC_BACK, h.i("GETDEFBGCOLOR"), "GETDEFBGCOLOR 读 Config.BackColor（FunctionMethodCreator.cs:3695）")
        // RESETBGCOLOR = SetBgColor(Config.BackColor)（FunctionIdentifier.cs:1515）→ 基准 270 行 BGRESET
        sink.resetBackColor()
        assertEquals(PC_BACK, h.i("GETBGCOLOR"))
    }

    @Test
    fun `GETFOCUSCOLOR 读 Config FocusColor`() {
        val h = Harness(prodSink())
        // 基准取证 pc-golden-root.txt:266 `FOCUS=16776960`（= 0xFFFF00）；
        // 出厂值 ConfigData.cs:70 `Color.FromArgb(255, 255, 0)`；
        // 读取分支 FunctionMethodCreator.cs:3666-3679（取值 :3677）。
        assertEquals(PC_FOCU, h.i("GETFOCUSCOLOR"))
    }

    // ---- 3. 字体 / 样式 / 对齐 ----

    @Test
    fun `SETFONT 与空串恢复默认都落在快照里`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 基准 280 行 FONT_DEFAULT=黑体：注入的 Config.FontName 即出厂读数（EmueraConsole.cs:207 的
        // userStyle 由 StringStyle.cs:26-31 把空字体名归一成 Config.FontName）。
        assertEquals("黑体", h.s("GETFONT"))
        // SETFONT 自定义名（spec: EmueraConsole.cs:2826-2835；GETFONT 读 StringStyle.Fontname，
        // FunctionMethodCreator.cs:3732-3745 取值 :3743）
        sink.setFont("Saitamaar")
        assertEquals("Saitamaar", h.s("GETFONT"))
        // SETFONT 空串 = 恢复 Config.FontName（EmueraConsole.cs:2828-2834 的 IsNullOrEmpty 分支）
        sink.setFont("")
        assertEquals("黑体", h.s("GETFONT"))
    }

    @Test
    fun `FONTSTYLE 位掩码落在快照里`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 位序 Bold=1 / Italic=2 / Strikeout=4 / Underline=8
        // （spec: Process.cs:1508-1530 写、FunctionMethodCreator.cs:3699-3730 读，逐位 :3708-3729）。
        // 基准 274-278 行的 STYLE=1 / 3 / 4 / 8 / 0。
        sink.setFontStyle(1)
        assertEquals(1L, h.i("GETSTYLE"))
        sink.setFontStyle(3)
        assertEquals(3L, h.i("GETSTYLE"))
        sink.setFontStyle(8)
        assertEquals(8L, h.i("GETSTYLE"))
        sink.setFontStyle(0)
        assertEquals(0L, h.i("GETSTYLE"))
        // FONTBOLD/FONTITALIC 的 OR 语义要读当前值再置位（FunctionIdentifier.cs:1574-1606）：
        // 读出侧就在同一份状态上，语句层读到的与函数层读到的是同一个数。
        sink.setFontStyle(1)
        assertEquals(sink.fontStyle, h.i("GETSTYLE").toInt())
    }

    @Test
    fun `ALIGNMENT 的三个枚举序落在快照里`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 编号 = DisplayLineAlignment.cs:3-8 的声明序（LEFT/CENTER/RIGHT = 0/1/2），
        // 函数层再映回字符串（FunctionMethodCreator.cs:3770-3791；基准 283-286 行）。
        assertEquals("LEFT", h.s("CURRENTALIGN"))
        sink.setAlignment(Alignment.CENTER)
        assertEquals("CENTER", h.s("CURRENTALIGN"))
        sink.setAlignment(Alignment.RIGHT)
        assertEquals("RIGHT", h.s("CURRENTALIGN"))
        sink.setAlignment(Alignment.LEFT)
        assertEquals("LEFT", h.s("CURRENTALIGN"))
    }

    // ---- 4. 单一来源：读线与写线必须是同一份状态 ----

    @Test
    fun `函数层读数与 sink 读出侧逐项同值`() {
        val sink = prodSink()
        val h = Harness(sink)
        sink.setForeColor(12, 34, 56)
        sink.setBackColor(200, 100, 50)
        sink.setFontStyle(5)
        sink.setFont("Saitamaar")
        sink.setAlignment(Alignment.CENTER)
        sink.setRedraw(1)

        // 每一项都同时从两个入口读：sink 的活状态 与 内建函数。任何一处走了第二份状态就会在这里红。
        assertEquals(sink.foregroundColor, 0x0C2238)
        assertEquals((h.i("GETCOLOR")).toInt(), sink.foregroundColor!!)
        assertEquals((h.i("GETBGCOLOR")).toInt(), sink.backgroundColor!!)
        assertEquals(h.i("GETSTYLE").toInt(), sink.fontStyle)
        assertEquals(h.s("GETFONT"), sink.fontName)
        assertEquals(sink.alignment, Alignment.CENTER)
        assertEquals(h.i("CURRENTREDRAW").toInt(), if (sink.redraw != 0) 1 else 0)
    }

    @Test
    fun `快照是现取现算而不是装配一次`() {
        val sink = prodSink()
        val host: BuiltinHost = SinkOnlyHost(sink)
        val before: ConsoleStyleSnapshot = assertNotNull(host.consoleStyle, "接了 sink 就不该是 null")
        assertNull(before.fore)
        assertNull(before.back)

        // 只经 ConsoleSink 接口写入（写线），再从同一个宿主读样式（读线）——同一对象、同一份字段。
        val asInterface: ConsoleSink = sink
        asInterface.setForeColor(255, 0, 0)
        asInterface.setAlignment(Alignment.RIGHT)
        asInterface.setRedraw(1)

        // 同一个 host 实例第二次读到的必须是新状态：装配一次缓存起来就在这里红。
        val after: ConsoleStyleSnapshot = assertNotNull(host.consoleStyle)
        assertNull(before.fore, "先前那份读数被回写过：快照不是纯派生视图")
        assertEquals(listOf(255, 0, 0), after.fore?.toList())
        assertEquals(2, after.alignment)
        assertEquals(1, after.redraw)
        // 默认色三项来自配置侧，不随当前值移动（FunctionMethodCreator.cs:3662、:3695 的 defaultColor 分支）
        assertEquals(listOf(192, 192, 192), after.defFore.toList())
        assertEquals(listOf(0, 0, 0), after.defBack.toList())
        assertEquals(listOf(255, 255, 0), after.focus.toList())
        // RESETCOLOR 之后回到 null（= 未显式设置），函数层再回落默认色：
        // 说明「当前值」这一份状态仍然只住在 sink 里，快照只是它的视图。
        asInterface.resetForeColor()
        assertNull(assertNotNull(host.consoleStyle).fore)
    }

    @Test
    fun `打印路径与查询路径读的是同一份样式`() {
        val sink = prodSink()
        val h = Harness(sink)
        sink.setForeColor(1, 2, 3)
        sink.setBackColor(7, 7, 7)
        sink.setFontStyle(1)
        sink.setFont("Saitamaar")
        sink.printLine("甲")
        val seg = sink.lines().last().segments.last()
        // 段样式在入缓冲时快照（ConsoleStyledString.cs:26-43），取的必须是 sink 那份活状态：
        // 打印用的当前色与 GETCOLOR 的当前色同源，否则「画出来的」和「问出来的」会分叉。
        assertEquals(h.i("GETCOLOR").toInt(), seg.foreColor)
        assertEquals(h.i("GETBGCOLOR").toInt(), sink.lines().last().backColor)
        assertEquals(h.i("GETSTYLE").toInt(), seg.styleMask)
        assertEquals(h.s("GETFONT"), seg.fontName)

        // 回落方向也要同源：RESETCOLOR 之后，画出来的是 Config.ForeColor，
        // 问出来的也必须是同一个数（sink 的 currentFore 缺省与快照的 defFore 同一份常量）。
        sink.resetForeColor()
        sink.resetBackColor()
        sink.printLine("乙")
        val next = sink.lines().last().segments.last()
        assertEquals(PC_FORE.toInt(), next.foreColor)
        assertEquals(h.i("GETCOLOR").toInt(), next.foreColor, "默认色有两份常量")
        assertEquals(PC_BACK.toInt(), sink.lines().last().backColor)
        assertEquals(h.i("GETBGCOLOR").toInt(), sink.lines().last().backColor)
    }

    @Test
    fun `LINEISEMPTY 也走 sink 的打印缓冲`() {
        val sink = prodSink()
        val h = Harness(sink)
        // 原版 = printBuffer.IsEmpty（EmueraConsole.cs:505；PrintStringBuffer.cs:39-48，
        // clearBuffer :273-278 在 Flush 末尾调用）。
        assertEquals(1L, h.i("LINEISEMPTY"), "初始无待决内容")
        sink.print("待决")
        assertEquals(0L, h.i("LINEISEMPTY"), "未 flush 的内容就是待决缓冲")
        sink.newLine()
        assertEquals(1L, h.i("LINEISEMPTY"), "flush 后必空")
    }

    // ---- 4b. D 系打印取色回落（PRINTD 家族） ----

    @Test
    fun `D 系窗口内打印回落默认前景色且字体属性保留`() {
        val sink = prodSink()
        sink.setForeColor(255, 0, 0)
        sink.setFontStyle(1)
        sink.setFont("Saitamaar")
        // 非 D 打印：SETCOLOR 生效（Style getter 的 UseSetColorStyle=true 分支）。
        sink.printLine("红")
        assertEquals(0xFF0000, sink.lines().last().segments.last().foreColor)
        // D 窗口：前景回落 Config.ForeColor（0xC0C0C0，ConfigData.cs:68）。
        // spec: EmueraConsole.cs:471-489 —— userStyle.Color != defaultStyle.Color 时
        // new StringStyle(defaultStyle.Color, userStyle.FontStyle, userStyle.Fontname)：
        // 只落前景色，字体样式/字体名不回落（StringStyle.cs:15-17 不参与该分支）。
        sink.setUseSetColorStyle(false)
        sink.printLine("默认")
        sink.setUseSetColorStyle(true)
        val dSeg = sink.lines().last().segments.last()
        assertEquals(0xC0C0C0, dSeg.foreColor)
        assertEquals(1, dSeg.styleMask, "D 回落只动前景色")
        assertEquals("Saitamaar", dSeg.fontName, "D 回落只动前景色")
        // 读出侧不受窗口影响：GETCOLOR 读 userStyle.Color 本体（FunctionMethodCreator.cs:3662）。
        assertEquals(0xFF0000, sink.foregroundColor)
        // 背景色不回落：原版 bgColor 是独立字段（EmueraConsole.cs:200），不在 StringStyle 里。
        sink.setBackColor(0, 255, 0)
        sink.setUseSetColorStyle(false)
        sink.printLine("背景")
        sink.setUseSetColorStyle(true)
        assertEquals(0x00FF00, sink.lines().last().backColor)
        // 未 SETCOLOR 时 D 窗口与普通打印同值（userStyle.Color == defaultStyle.Color 分支，
        // EmueraConsole.cs:483-486），色相同无差异。
        sink.resetForeColor()
        sink.setUseSetColorStyle(false)
        sink.printLine("同色")
        sink.setUseSetColorStyle(true)
        assertEquals(0xC0C0C0, sink.lines().last().segments.last().foreColor)
    }

    // ---- 5. 未接线与不追踪样式的宿主 ----

    @Test
    fun `没有控制台宿主的样式函数仍抛 CodeEE`() {
        val h = Harness(null)
        assertNull(h.host.consoleSink)
        assertNull(h.host.consoleStyle, "consoleSink 为 null 时不许编造快照")
        for (name in listOf(
            "GETCOLOR", "GETDEFCOLOR", "GETBGCOLOR", "GETDEFBGCOLOR", "GETFOCUSCOLOR",
            "GETSTYLE", "GETFONT", "CURRENTALIGN", "CURRENTREDRAW",
        )) {
            assertFailsWith<CodeEE> { h.i(name) }
        }
        // 窗口/配置度量不经样式快照（ConsoleQueryMethods.kt:115-137 已登记）
        assertEquals(760L, h.i("CLIENTWIDTH"))
    }

    @Test
    fun `不追踪样式的宿主答出厂态而不是抛错`() {
        // NoopConsoleSink 的读出侧走 ConsoleSink 的接口缺省 = 原版出厂态：
        // fore/back 未设置（回落 Config 两项）、样式 Regular、对齐 LEFT、REDRAW None。
        val h = Harness(NoopConsoleSink)
        assertEquals(PC_FORE, h.i("GETCOLOR"))
        assertEquals(PC_BACK, h.i("GETBGCOLOR"))
        assertEquals(PC_FOCU, h.i("GETFOCUSCOLOR"))
        assertEquals(0L, h.i("GETSTYLE"))
        assertEquals(0L, h.i("CURRENTREDRAW"))
        // 字体名出厂值 = ConfigData.cs:65 的「ＭＳ ゴシック」；真机由 emuera.config 覆盖，
        // 走 LineBufferConsoleSink 的 defaultFontName 注入（本类黑体那条用例即该路径）。
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, h.s("GETFONT"))
        assertEquals("LEFT", h.s("CURRENTALIGN"))
        // 写入侧对 Noop 无效果（它不存状态），读出侧因此恒为出厂态——这正是「一份状态」的反证：
        // 不存状态的实现不会凭空给出历史值。
        NoopConsoleSink.setForeColor(1, 2, 3)
        assertEquals(PC_FORE, h.i("GETCOLOR"))
    }
}
