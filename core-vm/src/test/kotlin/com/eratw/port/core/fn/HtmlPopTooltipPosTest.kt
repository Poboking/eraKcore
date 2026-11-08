package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTML_POPPRINTINGSTR 的 **tooltip（`title`）与 `pos` 字段往返**钉板（FONT-MEASURE F3）。
 *
 * ## 原版语义（逐句对拍）
 *
 * - `HTML_POPPRINTINGSTR` = `PopDisplayingLines()` → `HtmlManager.DisplayLine2Html(array,
 *   needPandN: false)`（FunctionMethodCreator.cs:6112-6130，注册 :9151）；null/空数组转 ""。
 * - `DisplayLine2Html`（HtmlManager.cs:381-484）对每颗按钮：`text = Escape(buttons[j].Title)`
 *   （:416-418）、`flag = IsButton || text != null || PointXisLocked`（:418）、
 *   `title='…'`（:427-434）与 `pos='RelativePointX'`（:437-442）**只在设过时写出**。
 * - 缓冲路径携带这两个字段的唯一入口是 `HTML_PRINT str, 1`（toPrintBuffer=true）：
 *   `Html2ButtonList` 解析 `title=`（HM :1512-1520）与 `pos=`（:1521-1537，int.TryParse，
 *   失败抛 AttributeCanNotInterpretNum），`cssToButton` 落到 `ConsoleButtonString.Title`
 *   （:847）与 `LockPointX`（:850 → ConsoleButtonString.cs:157-162，`PointX = rel_px *
 *   FontSize / 100`）。`pos` 还要求行处 nobr 且左对齐（HM :684-693），解析期校验在本仓
 *   `HtmlStrictParser.finish`（HtmlStrict.kt）。
 * - PRINTBUTTON（非 HTML 段）产出的按钮 Title=null、PointXisLocked=false ⇒ 原版回读串
 *   不带这两个属性；语料 4 个调用点（`PRINT_STATE_BODYGRAPH.ERB:89`、
 *   `Add_Custom_Commands.ERB:69`、`MISC.ERB:168`、`MISC.ERB:1323`）弹出的都是
 *   PRINTBUTTON 内容，PC 上同样不带 title/pos —— 本仓与之同判。
 *
 * ## 本仓的承载路径
 *
 * `LineBufferConsoleSink.printHtml(text, option=true)` 把整段 HTML 原文存进 `Kind.HTML`
 * 待决段；`popDisplayingLines()` 弹出后由 `consolePrintedLine`（fn/HtmlMethods.kt）保留段
 * **原文**（只剥行级 p/nobr 外壳）⇒ `title` / `pos` 逐字节随原文往返，与原版
 * `DisplayLine2Html` 的重建产物在「属性取值」层一致（原版重建会顺带规范化样式标签形状，
 * 这一层已按「HTML 段按原文输出」的既有登记保留分歧，见 sink KDoc）。
 *
 * 消费侧现状（2026-11-06）：`title` 已接 Android 长按浮窗（render `HtmlRunFlatten` 的
 * `TextRun.tooltip` → `ButtonLayer.tooltip` → app `CanvasConsoleView.tooltipAt` →
 * ShellUi `ButtonTooltipOverlay`，平台对应物偏离 = 长按代替 hover，ADR-005 登记）；
 * `pos` 的显示期绝对定位（原版 `LockPointX` 的 `PointX = rel_px * FontSize / 100`，
 * PrintStringBuffer.cs:393-411 的 setWidthToButtonList 游标接力）**渲染层未消费**
 * ⇒「需组长接线」（render 归 DIV-WIRE 路所有权，见契约 §1 禁区）。
 */
class HtmlPopTooltipPosTest {

    private class PopBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("PopBaseCtx 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    /** 生产装配同形：popPrintingLines 读取即消费（ConsoleBuiltinHost 的同一委托形状）。 */
    private class PopHost(private val sink: LineBufferConsoleSink) : BuiltinHost {
        override val popPrintingLines: List<ConsolePrintedLine>?
            get() = consolePrintedLines(sink.popDisplayingLines(), sink.defaultFontName)

        override val consoleLines: List<ConsolePrintedLine>?
            get() = consolePrintedLines(sink.lines(), sink.defaultFontName)
    }

    private class PopEnv {
        val sink: LineBufferConsoleSink = LineBufferConsoleSink(clientWidth = 40)

        private fun ctx(): BuiltinEvalContext {
            val store = VariableStore()
            val base = PopBaseCtx()
            base.variables = store
            return BuiltinEvalContext(base, store, PopHost(sink))
        }

        /** `HTML_PRINT text, 1`（缓冲路径）+ `HTML_POPPRINTINGSTR()` 的生产链。 */
        fun printBufferedAndPop(html: String): String {
            sink.printHtml(html, option = true)
            return pop()
        }

        /** 只读 `HTML_POPPRINTINGSTR()`（PRINTBUTTON 等非 HTML 待决内容的弹出）。 */
        fun pop(): String =
            (ctx().callFunction("HTML_POPPRINTINGSTR", emptyList()) as Value.StrV).v

        /** `HTML_GETPRINTEDSTR()`（needPandN=true 的同源重建面）。 */
        fun readPrintedStr(): String =
            (ctx().callFunction("HTML_GETPRINTEDSTR", emptyList()) as Value.StrV).v

        /** 解析代理（与 HtmlPrintedStrRoundTripTest 同一口径）：抛错文案或 null。 */
        fun parseErrorOrNull(html: String): String? = try {
            val store = VariableStore()
            val base = PopBaseCtx()
            base.variables = store
            (BuiltinEvalContext(base, store, PopHost(sink))
                .callFunction("HTML_STRINGLEN", listOf(Value.StrV(html))) as Value.IntV).v
            null
        } catch (e: CodeEE) {
            e.message
        }
    }

    // ------------------------------------------------------------------
    // 1. 语料形态锚：title 往返
    // ------------------------------------------------------------------

    /**
     * 语料 `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:382` 的行体形态：
     * `<nonbutton title='已禁用Graphics'>[更改列表背景色]</nonbutton>`（font 包裹是语料原文）。
     * 原版 ：418 `flag = text != null` ⇒ nonbutton 也带 title 属性重建；本仓按原文保留。
     */
    @Test
    fun `弹出行保留 nonbutton 的 title`() {
        val env = PopEnv()
        val html = "<nonbutton title='已禁用Graphics'>[更改列表背景色]</nonbutton>"
        assertEquals(html, env.printBufferedAndPop(html))
        assertTrue(env.parseErrorOrNull(html) == null, "回读串必须可再解析")
    }

    /**
     * 语料 `ERB/fromEN/NEW_UPDATE/UPDATE_MAIN.ERB:1561` 的形态：
     * `<button value='8' title='说明文字'>[8] - 标题</button>`（title 与 value 并存）。
     */
    @Test
    fun `弹出行保留 button 的 value 与 title`() {
        val env = PopEnv()
        val html = "<button value='8' title='更新说明'>[8] - 更新</button>"
        assertEquals(html, env.printBufferedAndPop(html))
    }

    /**
     * `pos` 属性：原版重建 `pos='RelativePointX'`（HM :437-442，解析值原样回写）。
     * 本仓原文往返同值；`pos` 要求 nobr（HM :684-693）——nobr 外壳在弹出串里被剥
     * （needPandN=false 本就无外壳），属性本身保留。
     */
    @Test
    fun `弹出行保留 button 的 pos`() {
        val env = PopEnv()
        val html = "<nobr><button value='1' pos='100'>AB</button>CD</nobr>"
        assertEquals("<button value='1' pos='100'>AB</button>CD", env.printBufferedAndPop(html))
    }

    // ------------------------------------------------------------------
    // 2. GETPRINTEDSTR 同源：needPandN 只换外壳，字段面相同
    // ------------------------------------------------------------------

    @Test
    fun `GETPRINTEDSTR 的行体同样带 title 与 pos`() {
        // 直印路径（option=false）：原版 PrintHtml else 分支 addRangeDisplayLine(Html2DisplayLine)
        // （EmueraConsole.cs:3218-3224），行进显示行列表 ⇒ GETPRINTEDSTR 读得到。
        // 缓冲路径（option=1）的内容在 printBuffer 里、不进 displayLineList ⇒ GETPRINTEDSTR
        // 返回 ""（原版同判），所以这条必须走直印。
        val env = PopEnv()
        env.sink.printHtml("<nobr><button value='1' pos='100' title='T'>AB</button></nobr>", option = false)
        assertEquals(
            "<p align='left'><nobr><button value='1' pos='100' title='T'>AB</button></nobr></p>",
            env.readPrintedStr(),
        )
    }

    // ------------------------------------------------------------------
    // 3. PRINTBUTTON（非 HTML 段）与空缓冲的原版同判
    // ------------------------------------------------------------------

    /**
     * PRINTBUTTON 的按钮 Title=null / PointXisLocked=false（ConsoleButtonString 三个构造
     * 都不设这两项）⇒ 原版 DisplayLine2Html 只写 `<button value='N'>`，无 title/pos。
     * 语料 4 个调用点弹出的正是这类内容（`PRINT_STATE_BODYGRAPH.ERB:89` 的
     * `PRINTBUTTON "□ 身体情报 □",89999` 等）。
     */
    @Test
    fun `PRINTBUTTON 弹出不带 title 与 pos（与原版同判）`() {
        val env = PopEnv()
        env.sink.printButton("□ 身体情报 □", "89999")
        assertEquals(
            "<button value='89999'>□ 身体情报 □</button>",
            env.pop(),
        )
    }

    /** 缓冲空 → ""（FunctionMethodCreator.cs:6123-6127；基准 pc-golden-root.txt:302）。 */
    @Test
    fun `空缓冲弹出的仍是空串`() {
        val env = PopEnv()
        assertEquals("", env.pop())
    }
}
