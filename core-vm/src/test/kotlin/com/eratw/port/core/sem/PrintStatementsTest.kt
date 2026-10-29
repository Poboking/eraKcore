package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PRINT 家族 / 显示系语句执行语义。
 * spec: FunctionIdentifier.cs:73-229 + ExpressionMediator.cs（输出路径）。
 */
class PrintStatementsTest {

    // ---------------- PRINT 基础后缀 ----------------

    @Test
    fun `PRINT 只输出不换行`() {
        val env = SemTestEnv()
        env.run(printStmt(FunctionCode.PRINT, strArg("你好")))
        assertEquals(listOf("print(你好)"), env.console.events)
    }

    @Test
    fun `PRINTL 输出后换行`() {
        val env = SemTestEnv()
        env.run(printStmt(FunctionCode.PRINTL, strArg("abc")))
        assertEquals(listOf("print(abc)", "newLine"), env.console.events)
    }

    @Test
    fun `PRINTW 输出换行并等待按键`() {
        val env = SemTestEnv()
        env.run(printStmt(FunctionCode.PRINTW, strArg("x")))
        assertEquals(listOf("print(x)", "newLine"), env.console.events)
        assertEquals(listOf(false to false), env.input.anyKeyCalls)
    }

    @Test
    fun `PRINTL 在 SKIPDISP 中整条抑制`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SKIPDISP, intArg(1)))
        assertTrue(env.ctx.skipPrint)
        env.run(printStmt(FunctionCode.PRINTL, strArg("hidden")))
        assertEquals(emptyList(), env.console.events)
    }

    @Test
    fun `PRINTV 逐项拼接整数`() {
        val env = SemTestEnv()
        env.run(
            printStmt(
                FunctionCode.PRINTV,
                SpPrintVArgument(
                    listOf(
                        iLit(12),
                        iLit(34),
                    ),
                ),
            ),
        )
        assertEquals("1234", env.console.printedText)
    }

    @Test
    fun `PRINTC 右对齐 PRINTLC 左对齐`() {
        val env = SemTestEnv()
        env.run(printStmt(FunctionCode.PRINTC, strArg("A")))
        env.run(printStmt(FunctionCode.PRINTLC, strArg("B")))
        assertEquals(
            listOf("printC(A,true)", "printC(B,false)"),
            env.console.events,
        )
    }

    @Test
    fun `PRINTSINGLE 走独立行输出`() {
        val env = SemTestEnv()
        env.run(printStmt(FunctionCode.PRINTSINGLE, strArg("S")))
        assertEquals(listOf("printSingleLine(S)"), env.console.events)
    }

    @Test
    fun `PRINTFORM 展开 FORM 串`() {
        val env = SemTestEnv()
        val form = FormStringTerm(listOf<FormPart>(FormPart.Text("半径{0}")))
        env.run(printStmt(FunctionCode.PRINTFORM, com.eratw.port.core.stmt.ExpressionArgument(form)))
        assertEquals(listOf("print(半径{0})"), env.console.events)
    }

    // ---------------- D 系取色窗口 ----------------
    //
    // 原版 PRINT 家族每次执行都开「打印窗口」：UseUserStyle=true、
    // UseSetColorStyle=!IsPrintDFunction()（PRINT 系 spec: FunctionIdentifier.cs:179-180，
    // PRINTDATA 系 :276-277），打印完复位 true（:227 / :317）。窗口内 Style getter
    // （EmueraConsole.cs:471-489）把 D 系的前景回落 Config.ForeColor——这里只钉
    // **窗口开合次序**（宿主侧的取色回落见 LineBufferConsoleSink 的单测）。

    @Test
    fun `PRINTD 窗口闭合次序 置false 打印 置true`() {
        val (executor, sink, ctx) = dWindowEnv()
        executor.execute(printStmt(FunctionCode.PRINTD, strArg("dbg")), ctx)
        assertEquals(listOf(false, true), sink.colorSwitches)
        assertEquals(listOf("print(dbg)"), sink.events)
    }

    @Test
    fun `PRINTL 窗口恒开 置true 打印 置true`() {
        val (executor, sink, ctx) = dWindowEnv()
        executor.execute(printStmt(FunctionCode.PRINTL, strArg("x")), ctx)
        assertEquals(listOf(true, true), sink.colorSwitches)
        assertEquals(listOf("print(x)", "newLine"), sink.events)
    }

    @Test
    fun `PRINTSINGLED 也在 D 窗口内`() {
        val (executor, sink, ctx) = dWindowEnv()
        executor.execute(printStmt(FunctionCode.PRINTSINGLED, strArg("S")), ctx)
        assertEquals(listOf(false, true), sink.colorSwitches)
        assertEquals(listOf("printSingleLine(S)"), sink.events)
    }

    @Test
    fun `PRINTDATAD 窗口闭合次序`() {
        val (executor, sink, ctx) = dWindowEnv()
        ctx.dataSections = dualKeySections { _ -> listOf(listOf(sLit("D1"))) }
        executor.execute(instr(FunctionCode.PRINTDATAD), ctx)
        assertEquals(listOf(false, true), sink.colorSwitches)
        assertEquals(listOf("print(D1)"), sink.events)
    }

    @Test
    fun `PRINTDATA 数据节为空时取色开关不复位 原版泄漏`() {
        // 原版 dataList.Count == 0 直接 JumpTo 返回、跳过了 :317 的复位
        // （spec: FunctionIdentifier.cs:278-282），false 泄漏到下一条打印语句——ADR-005 原样复刻。
        val (executor, sink, ctx) = dWindowEnv()
        ctx.dataSections = dualKeySections { _ -> emptyList() }
        executor.execute(instr(FunctionCode.PRINTDATAD), ctx)
        assertEquals(listOf(false), sink.colorSwitches)
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun `PRINTBUTTON 求值前显式复位取色开关`() {
        // spec: Process.cs:1110-1111 / :1129-1130 —— 按钮不吃 D 配色回落，
        // 且会把上一条泄漏出来的窗口就地关掉。
        val (executor, sink, ctx) = dWindowEnv()
        ctx.dataSections = dualKeySections { _ -> emptyList() }
        executor.execute(instr(FunctionCode.PRINTDATAD), ctx) // 泄漏 false
        executor.execute(
            instr(FunctionCode.PRINTBUTTON, com.eratw.port.core.stmt.SpButtonArgument(sLit("选项"), iLit(1))),
            ctx,
        )
        assertEquals(listOf(false, true), sink.colorSwitches)
        assertEquals(listOf("printButton(选项,1)"), sink.events)
    }

    @Test
    fun `PRINTPLAIN 求值前显式复位取色开关`() {
        // spec: Process.cs:1149-1150 —— 同 PRINTBUTTON 的就地复位。
        val (executor, sink, ctx) = dWindowEnv()
        ctx.dataSections = dualKeySections { _ -> emptyList() }
        executor.execute(instr(FunctionCode.PRINTDATAD), ctx) // 泄漏 false
        executor.execute(instr(FunctionCode.PRINTPLAIN, strArg("p")), ctx)
        assertEquals(listOf(false, true), sink.colorSwitches)
        assertEquals(listOf("printPlain(p)"), sink.events)
    }

    // ---------------- 按钮 ----------------

    @Test
    fun `PRINTBUTTON 整数值`() {
        val env = SemTestEnv()
        env.run(
            instr(
                FunctionCode.PRINTBUTTON,
                com.eratw.port.core.stmt.SpButtonArgument(sLit("选项"), iLit(3)),
            ),
        )
        assertEquals(listOf("printButton(选项,3)"), env.console.events)
    }

    @Test
    fun `PRINTBUTTON 字符串值`() {
        val env = SemTestEnv()
        env.run(
            instr(
                FunctionCode.PRINTBUTTON,
                com.eratw.port.core.stmt.SpButtonArgument(sLit("选项"), sLit("YES")),
            ),
        )
        assertEquals(listOf("printButton(选项,YES)"), env.console.events)
    }

    @Test
    fun `PRINTBUTTONC 右对齐 PRINTBUTTONLC 左对齐`() {
        val env = SemTestEnv()
        val mk = { code: FunctionCode ->
            com.eratw.port.core.stmt.SpButtonArgument(sLit("T"), iLit(1)).let {
                com.eratw.port.core.stmt.InstructionStmt(code, it, 1, code.name)
            }
        }
        env.run(mk(FunctionCode.PRINTBUTTONC))
        env.run(mk(FunctionCode.PRINTBUTTONLC))
        assertEquals(
            listOf("printButtonC(T,1,true)", "printButtonC(T,1,false)"),
            env.console.events,
        )
    }

    // ---------------- 线与行控制 ----------------

    @Test
    fun `DRAWLINE 打印分隔线并换行`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.DRAWLINE))
        assertEquals(listOf("printBar", "newLine"), env.console.events)
    }

    @Test
    fun `CUSTOMDRAWLINE 用参数串填充`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.CUSTOMDRAWLINE, strArg("-")))
        assertEquals(listOf("printCustomBar(-)", "newLine"), env.console.events)
    }

    @Test
    fun `CLEARLINE 删除指定行数`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.CLEARLINE, intArg(2)))
        assertEquals(listOf("deleteLine(2)"), env.console.events)
    }

    @Test
    fun `REUSELASTLINE 替换最后一行`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.REUSELASTLINE, strArg("R")))
        assertEquals(listOf("printTemporaryLine(R)"), env.console.events)
    }

    // ---------------- 颜色 / 字体 ----------------

    @Test
    fun `SETCOLOR 按 0xRRGGBB 拆分`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETCOLOR, com.eratw.port.core.stmt.SpColorArgument(iLit(0xFF8001))))
        assertEquals(listOf("setForeColor(255,128,1)"), env.console.events)
    }

    @Test
    fun `SETCOLORBYNAME 命名色`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETCOLORBYNAME, strArg("red")))
        assertEquals(listOf("setForeColor(255,0,0)"), env.console.events)
    }

    @Test
    fun `SETCOLORBYNAME 未知名报错`() {
        val env = SemTestEnv()
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.SETCOLORBYNAME, strArg("notacolor"))) }
        assertTrue(e.message!!.contains("无效"), e.message)
    }

    @Test
    fun `RESETCOLOR 与 SETBGCOLOR`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.SETBGCOLOR, com.eratw.port.core.stmt.SpColorArgument(iLit(0x102030))))
        env.run(instr(FunctionCode.RESETCOLOR))
        env.run(instr(FunctionCode.RESETBGCOLOR))
        assertEquals(
            listOf("setBackColor(16,32,48)", "resetForeColor", "resetBackColor"),
            env.console.events,
        )
    }

    @Test
    fun `FONTBOLD FONTITALIC FONTREGULAR FONTSTYLE 样式位`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.FONTBOLD))
        env.run(instr(FunctionCode.FONTITALIC))
        env.run(instr(FunctionCode.FONTREGULAR))
        env.run(instr(FunctionCode.FONTSTYLE, intArg(5)))
        assertEquals(
            listOf("setFontStyle(1)", "setFontStyle(2)", "setFontStyle(0)", "setFontStyle(5)"),
            env.console.events,
        )
    }

    // ---------------- 数据节 ----------------

    @Test
    fun `PRINTDATA 取注入的数据组输出`() {
        val env = SemTestEnv()
        env.ctx.dataSections = dualKeySections { _ ->
            listOf(
                listOf(sLit("A1")),
                listOf(sLit("B1"), sLit("B2")),
            )
        }
        env.store.randProvider = com.eratw.port.core.vars.RandProvider { 1L } // 固定取第 2 组
        env.run(instr(FunctionCode.PRINTDATA))
        assertEquals(listOf("print(B1)", "newLine", "print(B2)"), env.console.events)
    }

    @Test
    fun `PRINTDATAL 末尾换行 PRINTDATAW 末尾等待`() {
        val env = SemTestEnv()
        env.ctx.dataSections = dualKeySections { _ -> listOf(listOf(sLit("X"))) }
        env.store.randProvider = com.eratw.port.core.vars.RandProvider { 0L }
        env.run(instr(FunctionCode.PRINTDATAL))
        assertEquals(listOf("print(X)", "newLine"), env.console.events)
        env.run(instr(FunctionCode.PRINTDATAW))
        assertEquals(listOf(false to false), env.input.anyKeyCalls)
    }

    @Test
    fun `PRINTDATA 无数据节供给时为 no-op`() {
        val env = SemTestEnv()
        val r = env.run(instr(FunctionCode.PRINTDATA))
        assertEquals(ExecResult.Normal, r)
        assertEquals(emptyList(), env.console.events)
    }

    @Test
    fun `STRDATA 经 DataStmt 分派写入目标变量`() {
        val env = SemTestEnv()
        env.store.declareUserVar(DimSpec("SDV", true, intArrayOf(1)))
        env.store.randProvider = com.eratw.port.core.vars.RandProvider { 0L }
        env.ctx.dataSections = dualKeySections { _ ->
            listOf(listOf(sLit("SD-A")), listOf(sLit("SD-B")))
        }
        // 解析器把 STRDATA 归为 DataStmt（LineParser.kt:324），必须走 DataStmt 分派（Process.cs:1801-1829）
        // 一般 1D 变量省略下标时解析器补 IntLiteral(0)（Parser.kt:395-402）
        val stmt = DataStmt(FunctionCode.STRDATA, "SDV", StrDataArgument(sVar("SDV", 0L)), 1, "STRDATA SDV")
        env.run(stmt)
        assertEquals("SD-A", env.store.readStr("SDV", longArrayOf(0)))
    }

    @Test
    fun `PRINTFORMS 求值后经 runtimeForm 二次展开`() {
        val env = SemTestEnv()
        env.ctx.runtimeForm = { "EXP[$it]" }
        env.run(printStmt(FunctionCode.PRINTFORMSL, strArg("XY{3+3}")))
        assertEquals(listOf("print(EXP[XY{3+3}])", "newLine"), env.console.events)
    }

    // ---------------- 杂项显示 ----------------

    @Test
    fun `PRINTPLAIN 不套样式输出`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.PRINTPLAIN, strArg("p")))
        assertEquals(listOf("printPlain(p)"), env.console.events)
    }

    @Test
    fun `ALIGNMENT 合法值与报错`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.ALIGNMENT, strArg("LEFT")))
        assertEquals(listOf("setAlignment(LEFT)"), env.console.events)
        val e = assertFailsWith<CodeEE> { env.run(instr(FunctionCode.ALIGNMENT, strArg("MIDDLE"))) }
        assertTrue(e.message!!.contains("ALIGNMENT"), e.message)
    }

    @Test
    fun `BAR 输出进度条`() {
        val env = SemTestEnv()
        env.run(
            instr(
                FunctionCode.BAR,
                com.eratw.port.core.stmt.SpBarArgument(
                    value = iLit(5),
                    max = iLit(10),
                    length = iLit(10),
                ),
            ),
        )
        assertEquals(listOf("print([*****.....])"), env.console.events)
    }

    @Test
    fun `CLEARTEXTBOX 清输入框`() {
        val env = SemTestEnv()
        env.run(instr(FunctionCode.CLEARTEXTBOX, VoidArgument()))
        assertEquals(listOf("clearText"), env.console.events)
    }

    /** D 窗口测试环境：console 换成能录取色开关闭合的 [DWindowSink]（SemTestEnv 的 console 固定）。 */
    private fun dWindowEnv(): Triple<StatementExecutor, DWindowSink, ExecContext> {
        val store = com.eratw.port.core.vars.VariableStore()
        store.randProvider = com.eratw.port.core.rand.Sfmt19937(0L)
        val sink = DWindowSink()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = sink,
            input = FakeInputSink(),
        )
        return Triple(StatementExecutor(), sink, ctx)
    }
}

/** [FakeConsoleSink] 基础上追加取色开关闭合记录（其余事件沿用父类记账）。 */
private class DWindowSink : FakeConsoleSink() {
    val colorSwitches = mutableListOf<Boolean>()

    override fun setUseSetColorStyle(value: Boolean) {
        colorSwitches += value
    }
}
