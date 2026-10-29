package com.eratw.port.core.stmt

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `Console.ResetStyle()` 调用点测试（m-resetstyle 路）。
 *
 * 原版复位集恰四项（前景 / 样式位 / 字体名 / 对齐，EmueraConsole.cs:2838-2842 +
 * StringStyle.cs:20-27），**不含** bgColor（EmueraConsole.cs:200）、redraw（:455）、
 * 打印缓冲 —— 逐字段口径与对照夹具借 `core/host/ConsoleResetStyleTest.kt`。
 *
 * 本文件钉的是**语句调用点**：
 * 1. `RESETDATA` 补接样式复位 —— 原版 `RESETDATA_Instruction.DoInstruction` 方法体恰两句
 *    （FunctionIdentifier.cs:1846 `exm.VEvaluator.ResetData();` → :1847 `exm.Console.ResetStyle();`），
 *    复位紧随变量复位之后；
 * 2. `RESETGLOBAL` **不**复位样式 —— 原版方法体只 `ResetGlobalData()`
 *    （FunctionIdentifier.cs:1851-1863），反向防误接钉桩；
 * 3. `BEGIN` 未知关键字文案 = 原版 zhs 表形（Lang.cs:3118 / lang.emuera-zhs.xml:734，
 *    成对直引号；ProcessState.cs:164-166 抛出）；
 * 4. `BEGIN` 关键字大小写敏感 —— 原版是裸 string switch（ProcessState.cs:139-161），
 *    不经 `Config.StrComper`；语料实测 `^\s*begin ` 23 行全大写、无小写写法，收紧零代价。
 *
 * beginTitle（Process.cs:2112）一侧由组长接线落定：`BeginHost.resetStyle()` 默认空实现 +
 * `BeginFlow.stepTitle` 调用 + `ConsoleBeginHost` 委托自身持有的 console。断言点在
 * `core/proc/BeginFlowTest.kt`（假体 `FakeCalls`/`FakeHost` 已在那儿，不重复搭一套）：
 * 派发前复位一次、CTrain 早退（Process.cs:2107-2110 在复位 :2112 之前）不复位。
 */
class ResetStyleCallSitesTest {

    private class Fixture {
        val store = VariableStore()
        val sink = LineBufferConsoleSink(clientWidth = 80, defaultFontName = "黑体")
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = sink,
        )
        val executor = StatementExecutor()

        /** 让四项样式全部偏离出厂态，同时把复位面外的背景/重绘/缓冲也弄脏。 */
        fun dirtyEverything() {
            sink.setForeColor(255, 0, 0)
            sink.setFontStyle(9) // 粗体|下划线
            sink.setFont("楷体")
            sink.setAlignment(Alignment.CENTER)
            sink.setBackColor(1, 2, 3)
            sink.setRedraw(3)
            sink.print("pending", lineEnd = false)
        }

        fun run(code: FunctionCode) {
            executor.execute(InstructionStmt(code, VoidArgument(), 1, code.name), ctx)
        }
    }

    // ------------------------------------------------------------------
    // 1. RESETDATA 的样式复位（本路产品改动）
    // ------------------------------------------------------------------

    /**
     * `RESETDATA` 后四项样式逐字段回出厂态（FunctionIdentifier.cs:1847 →
     * EmueraConsole.cs:2838-2842）；背景色、重绘、待决打印缓冲、行计数**不动**。
     * 回退本路改动（删 StatementExecutor 的 `ctx.console.resetStyle()`）此用例必红。
     */
    @Test
    fun `RESETDATA 复位四项样式且不碰背景重绘与缓冲`() {
        val f = Fixture()
        f.dirtyEverything()
        // 前置读数：四项确已非默认。
        assertEquals(0xFF0000, f.sink.foregroundColor)
        assertEquals(9, f.sink.fontStyle)
        assertEquals("楷体", f.sink.fontName)
        assertEquals(Alignment.CENTER, f.sink.alignment)

        val revBefore = f.sink.revision
        f.run(FunctionCode.RESETDATA)

        // 复位四项 → 出厂态（EmueraConsole.cs:206 defaultStyle + :2841 LEFT）。
        assertNull(f.sink.foregroundColor, "前景回 Config.ForeColor 落点（同 RESETCOLOR）")
        assertEquals(0, f.sink.fontStyle, "defaultStyle.FontStyle = Regular（StringStyle.cs:20-27）")
        assertEquals("黑体", f.sink.fontName, "空字体名归一成 Config.FontName（StringStyle.cs:20-27）")
        assertEquals(Alignment.LEFT, f.sink.alignment, "ResetStyle 第 2 句（EmueraConsole.cs:2841）")

        // 复位面之外逐项确认没被顺手清（bgColor :200、redraw :455 不在 ResetStyle 方法体里）。
        assertEquals(0x010203, f.sink.backgroundColor, "bgColor 不属于 RESETDATA 的样式复位")
        assertEquals(1, f.sink.redraw, "redraw 3 折减成 1，且不被复位")
        assertFalse(f.sink.printingBufferEmpty, "待决打印缓冲不属于 ResetStyle")
        assertEquals(revBefore, f.sink.revision, "样式复位不产生行、不自增 revision")
        assertEquals(0L, f.sink.lineCount)
    }

    /**
     * RESETDATA 的既有两句语义不被本路插入破坏：变量复位（:1846，SAVEDATA_TEXT 是
     * 系统字符串变量本体，VariableEvaluator.cs:119-129）与三个 skip 位仍复位，
     * 样式复位紧随 resetData 之后（原版 :1846→:1847 顺序，副作用互不可见处只钉「都发生」）。
     */
    @Test
    fun `RESETDATA 变量复位与跳过位复位保持`() {
        val f = Fixture()
        f.dirtyEverything()
        f.ctx.skipPrint = true
        f.ctx.mesSkip = true
        f.ctx.saveSkip = true
        f.store.writeStr("SAVEDATA_TEXT", longArrayOf(), "t")

        f.run(FunctionCode.RESETDATA)

        assertEquals("", f.store.readStr("SAVEDATA_TEXT", longArrayOf()), "resetData（FunctionIdentifier.cs:1846）")
        assertFalse(f.ctx.skipPrint)
        assertFalse(f.ctx.mesSkip)
        assertFalse(f.ctx.saveSkip)
        // 样式同时已复位（与上一用例共用口径）。
        assertNull(f.sink.foregroundColor)
    }

    // ------------------------------------------------------------------
    // 2. RESETGLOBAL 反向防误接
    // ------------------------------------------------------------------

    /**
     * `RESETGLOBAL` **不**复位样式 —— 原版 `RESETGLOBAL_Instruction.DoInstruction`
     * 方法体只 `exm.VEvaluator.ResetGlobalData()`（FunctionIdentifier.cs:1851-1863），
     * 无 `Console.ResetStyle()`。若有人把复位「顺手补」到 RESETGLOBAL，此用例红。
     */
    @Test
    fun `RESETGLOBAL 不复位样式`() {
        val f = Fixture()
        f.dirtyEverything()

        f.run(FunctionCode.RESETGLOBAL)

        assertEquals(0xFF0000, f.sink.foregroundColor, "RESETGLOBAL 原版不触碰样式")
        assertEquals(9, f.sink.fontStyle)
        assertEquals("楷体", f.sink.fontName)
        assertEquals(Alignment.CENTER, f.sink.alignment)
    }

    // ------------------------------------------------------------------
    // 3. BEGIN 未知关键字文案（zhs 表形）
    // ------------------------------------------------------------------

    /**
     * 未知关键字上屏整串 = `BEGIN的关键字"NOPE"没有定义` —— 原版 zhs 表形
     * （lang.emuera-zhs.xml:734 / Lang.cs:3118 InvalidBeginArg，**成对直引号**），
     * 由 `ProcessState.cs:164-166` 的 default 分支抛出；关键字按脚本原样插入
     * （原版 `string.Format(..., keyword)` 用的是 `func.Argument.ConstStr` 原文，
     * `FunctionIdentifier.cs:2932`，不 trim 不换大小写）。
     */
    @Test
    fun `BEGIN 未知关键字报 zhs 原文整串`() {
        val e = assertFailsWith<CodeEE> { BeginType.fromKeyword("NOPE") }
        assertEquals("""BEGIN的关键字"NOPE"没有定义""", e.message)

        // 原样插入读数：带空格与混大小写的关键字，文案保留原样。
        val e2 = assertFailsWith<CodeEE> { BeginType.fromKeyword(" nope ") }
        assertEquals("""BEGIN的关键字" nope "没有定义""", e2.message)
    }

    // ------------------------------------------------------------------
    // 4. BEGIN 关键字大小写敏感（收紧后的口径）
    // ------------------------------------------------------------------

    /**
     * 原版是裸 C# string switch（ProcessState.cs:139-161 七个 case，:164-166 default），
     * **大小写敏感**；移植侧已同步收紧。语料实测：画蛇添足版 4.980 的 ERB 目录里
     * `^\s*begin ` 命中 23 行、关键字全大写、无小写写法 ⇒ 收紧不打掉任何语料合法写法。
     * 保留的两处超集：`trim()`（原版不 trim，FunctionIdentifier.cs:2932 直取 ConstStr）
     * 与目标函数名别名（原版 `BEGIN EVENTSHOP` 落 default 抛错）。
     */
    @Test
    fun `BEGIN 关键字大小写敏感且保留函数名别名超集`() {
        for ((kw, want) in listOf(
            "SHOP" to BeginType.SHOP,
            "TRAIN" to BeginType.TRAIN,
            "AFTERTRAIN" to BeginType.AFTERTRAIN,
            "ABLUP" to BeginType.ABLUP,
            "TURNEND" to BeginType.TURNEND,
            "FIRST" to BeginType.FIRST,
            "TITLE" to BeginType.TITLE,
        )) assertSame(want, BeginType.fromKeyword(kw), "case 表 ProcessState.cs:139-161")

        // 收紧读数：小写与原版同形 → default 抛错（回退 uppercase 删除前的旧行为，此行必红）。
        val e = assertFailsWith<CodeEE> { BeginType.fromKeyword("shop") }
        assertEquals("""BEGIN的关键字"shop"没有定义""", e.message)

        // 仍保留的超集：trim 与函数名别名。
        assertSame(BeginType.SHOP, BeginType.fromKeyword("  SHOP  "))
        assertSame(BeginType.SHOP, BeginType.fromKeyword("EVENTSHOP"))
        assertSame(BeginType.TITLE, BeginType.fromKeyword("SYSTEM_TITLE"))
    }
}
