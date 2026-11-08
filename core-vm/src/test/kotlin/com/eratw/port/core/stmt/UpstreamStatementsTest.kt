package com.eratw.port.core.stmt

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeConsoleSink
import com.eratw.port.core.sem.FakeInputSink
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.981 上游新增语句的定向用例（W3 波次）。
 *
 * 覆盖：TINPUTNF/TINPUTSNF（参数槽位与 TINPUT/TINPUTS 同表，4 参语料形态：
 * `qol_MAP.ERB` 的 `TINPUTNF 20000,2,0,"开始播放DEMO"`、
 * `TINPUTSNF 2000, "UPDATE", 0, ""`）、TEXT_BGC_ON/OFF（转发到 GraphicsSink）、
 * DISABLE/ENABLE_INPUT_MACRO（状态挂 `ExecContext.inputMacroEnabled` 并随
 * `InputRequest.macroEnabled` 下发）、HTML_PRINTC/HTML_PRINTLC（像素补齐三态）、
 * TRYCALLSTR（运行期拼串命中 / 缺失静默跳过）、TRYCCALLSTR（缺失跳配对 CATCH）。
 */
class UpstreamStatementsTest {
    /** 记录 TEXT_BGC 转发的假图形宿主（其余图形入口空转）。 */
    private class BgGraphics : GraphicsSink {
        val bgCalls = mutableListOf<Pair<Long, Long>>()
        override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {}
        override fun printRect(params: List<Pair<Long, Boolean>>) {}
        override fun printSpace(params: List<Pair<Long, Boolean>>) {}
        override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
        override fun removeBgImage(name: String) {}
        override fun clearBgImages() {}
        override fun setTextBgColor(rgb: Long, opacity: Long) {
            bgCalls += rgb to opacity
        }
    }

    /** 语句层装置：真 store + 内建求值语境（HTML_PRINTC 的像素量宽要求 BuiltinContext）。 */
    private class Env(outcome: InputOutcome = InputOutcome()) {
        val store = VariableStore()
        val console = FakeConsoleSink()
        val input = FakeInputSink(outcome)
        val graphics = BgGraphics()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = console,
            input = input,
            graphics = graphics,
        )
        private val executor = StatementExecutor()

        fun run(stmt: Statement) = executor.execute(stmt, ctx)
    }

    private fun tInputArg(
        time: Long,
        def: Expr?,
        disp: Long? = null,
        timeout: Expr? = null,
    ) = SpTInputsArgument(IntLiteral(time), def, disp?.let { IntLiteral(it) }, timeout, null, null)

    // ------------------------------------------------------------------
    // TINPUTNF / TINPUTSNF
    // ------------------------------------------------------------------

    @Test
    fun `TINPUTNF 四参形态 默认值写 RESULT 且请求带时限`() {
        val env = Env(InputOutcome(intValue = 7))
        env.run(instr(FunctionCode.TINPUTNF, tInputArg(20000, IntLiteral(2), 0, StrLiteral("开始播放DEMO"))))
        assertEquals(7L, env.store.readInt("RESULT", longArrayOf(0)), "等待返回后写 RESULT")
        val req = env.input.requests.single()
        assertEquals(InputRequest.InputType.INT, req.type)
        assertEquals(20000L, req.timeLimit)
        assertEquals(false, req.displayTime, "第 3 参 0 = 不显示计时")
        assertEquals("开始播放DEMO", req.timeUpMessage)
        assertEquals(2L, req.defInt, "第 2 参是数值默认值")
        assertEquals(true, req.macroEnabled, "出厂输入宏开关 = true")
    }

    @Test
    fun `TINPUTSNF 四参形态 字符串默认值写 RESULTS`() {
        val env = Env(InputOutcome(strValue = "UPDATE"))
        env.run(instr(FunctionCode.TINPUTSNF, tInputArg(2000, StrLiteral("UPDATE"), 0, StrLiteral(""))))
        assertEquals("UPDATE", env.store.readStr("RESULTS", longArrayOf(0)))
        val req = env.input.requests.single()
        assertEquals(InputRequest.InputType.STR, req.type)
        assertEquals(2000L, req.timeLimit)
        assertEquals("UPDATE", req.defStr, "第 2 参是字符串默认值")
    }

    // ------------------------------------------------------------------
    // TEXT_BGC_ON / TEXT_BGC_OFF
    // ------------------------------------------------------------------

    @Test
    fun `TEXT_BGC_ON 把颜色与不透明度转发给图形宿主`() {
        val env = Env()
        env.run(instr(FunctionCode.TEXT_BGC_ON, SpSwapCharaArgument(IntLiteral(0x112233), IntLiteral(200))))
        assertEquals(listOf(0x112233L to 200L), env.graphics.bgCalls)
    }

    @Test
    fun `TEXT_BGC_OFF 用负色哨兵关闭背景色`() {
        val env = Env()
        env.run(instr(FunctionCode.TEXT_BGC_OFF))
        assertEquals(listOf(-1L to 0L), env.graphics.bgCalls, "rgb < 0 = 关闭（见 GraphicsSink KDoc）")
    }

    // ------------------------------------------------------------------
    // DISABLE / ENABLE_INPUT_MACRO
    // ------------------------------------------------------------------

    @Test
    fun `DISABLE 后输入请求 macroEnabled 为假 再次 ENABLE 恢复`() {
        val env = Env(InputOutcome(intValue = 1))
        env.run(instr(FunctionCode.DISABLE_INPUT_MACRO))
        assertEquals(false, env.ctx.inputMacroEnabled)
        env.run(instr(FunctionCode.TINPUT, tInputArg(100, IntLiteral(0))))
        assertEquals(false, env.input.requests.last().macroEnabled, "随 InputRequest 下发")
        env.run(instr(FunctionCode.ENABLE_INPUT_MACRO))
        assertEquals(true, env.ctx.inputMacroEnabled)
    }

    // ------------------------------------------------------------------
    // HTML_PRINTC / HTML_PRINTLC
    // ------------------------------------------------------------------

    @Test
    fun `HTML_PRINTC 右对齐前置格数补齐`() {
        // 列宽 = PRINTCLENGTH = 25 半角格；全角「あ」= 2 格 ⇒ 前补 23 个半角空格。
        // 补齐段必须是折行可见的空格（<shape> 在折行侧计 0 格，2026-11-04 玩家反馈的网格
        // 坍缩病根）；padHtmlColumn 的口径与出处见其 KDoc。
        val env = Env()
        env.run(instr(FunctionCode.HTML_PRINTC, SpHtmlPrint(StrLiteral("あ"), null)))
        assertEquals("printHtml(${" ".repeat(23)}あ,true)", env.console.events.single())
    }

    @Test
    fun `HTML_PRINTLC 左对齐后置格数补齐`() {
        val env = Env()
        env.run(instr(FunctionCode.HTML_PRINTLC, SpHtmlPrint(StrLiteral("あ"), null)))
        assertEquals("printHtml(あ${" ".repeat(23)},true)", env.console.events.single())
    }

    @Test
    fun `HTML_PRINTC 空串与超宽文本不补齐`() {
        val env = Env()
        env.run(instr(FunctionCode.HTML_PRINTC, SpHtmlPrint(StrLiteral(""), null)))
        val wide = "あ".repeat(13) // 13 全角 = 26 格 > 25 格
        env.run(instr(FunctionCode.HTML_PRINTC, SpHtmlPrint(StrLiteral(wide), null)))
        assertEquals(listOf("printHtml(,true)", "printHtml($wide,true)"), env.console.events)
    }

    // ------------------------------------------------------------------
    // TRYCALLSTR / TRYCCALLSTR（编译 + 运行，走 VM 的 _CALL_BY_NAME）
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALLSTR 运行期拼串命中后调用并取值`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "X = 400", "TRYCALLSTR @\"COM_ABLE{X}\"", "A = RESULT"),
                "COM_ABLE400" to listOf("@COM_ABLE400", "RETURN 42"),
            ),
            store,
        )
        assertEquals(42L, store.readInt("A", longArrayOf(0)), "动态名 COM_ABLE400 被调用且返回值进 RESULT")
    }

    @Test
    fun `TRYCALLSTR 目标不存在静默跳过并继续执行`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf("@TEST", "TRYCALLSTR @\"GHOST_{X}\"", "A = 1"),
            ),
            store,
        )
        assertEquals(1L, store.readInt("A", longArrayOf(0)), "TRY 缺失 = 跳过本行（原版 :3587）")
    }

    @Test
    fun `TRYCCALLSTR 目标不存在跳配对 CATCH`() {
        val store = VariableStore()
        runProgram(
            compileProgram(
                "TEST" to listOf(
                    "@TEST",
                    "TRYCCALLSTR @\"GHOST_{X}\"",
                    "A = 1",
                    "CATCH",
                    "A = 2",
                    "ENDCATCH",
                    "B = 1",
                ),
            ),
            store,
        )
        assertEquals(2L, store.readInt("A", longArrayOf(0)), "缺失时进 catch 体")
        assertEquals(1L, store.readInt("B", longArrayOf(0)), "ENDCATCH 之后继续执行")
    }

    // ------------------------------------------------------------------
    // 语料原样解析（LineParser → FunctionCode.BY_NAME → ArgBuilders）
    // ------------------------------------------------------------------

    @Test
    fun `上游新语句按语料原样可解析且取到对应参数形态`() {
        val stmts = parseErbLines(
            "TINPUTNF 20000,2,0,\"开始播放DEMO\"",
            "TINPUTSNF 2000, \"UPDATE\", 0, \"\"",
            "TEXT_BGC_ON 0x1F1F1F, 200",
            "TEXT_BGC_OFF",
            "DISABLE_INPUT_MACRO",
            "ENABLE_INPUT_MACRO",
            "HTML_PRINTC \"<b>x</b>\"",
            "TRYCALLSTR STR:0",
            "TRYCCALLSTR RESULTS",
        )
        assertEquals(
            listOf(
                FunctionCode.TINPUTNF,
                FunctionCode.TINPUTSNF,
                FunctionCode.TEXT_BGC_ON,
                FunctionCode.TEXT_BGC_OFF,
                FunctionCode.DISABLE_INPUT_MACRO,
                FunctionCode.ENABLE_INPUT_MACRO,
                FunctionCode.HTML_PRINTC,
                FunctionCode.TRYCALLSTR,
                FunctionCode.TRYCCALLSTR,
            ),
            stmts.map { it.code },
        )
        assertTrue(stmts[0].args is SpTInputsArgument, "TINPUTNF 复用 SP_TINPUT 参数表")
        assertTrue(stmts[1].args is SpTInputsArgument, "TINPUTSNF 复用 SP_TINPUTS 参数表")
        assertTrue(stmts[2].args is SpSwapCharaArgument, "TEXT_BGC_ON 两参 INT")
        assertTrue(stmts[6].args is SpHtmlPrint, "HTML_PRINTC 复用 SP_HTML_PRINT")
        assertTrue(stmts[7].args is SpCallArgument && (stmts[7].args as SpCallArgument).label == null,
            "TRYCALLSTR 的非常量目标走运行期名")
    }

    // ------------------------------------------------------------------
    // 扁平通道与 fork 实参表等价（T5 · SP_SWAP / SP_HTML_PRINT 生产直读面）
    // ------------------------------------------------------------------

    @Test
    fun `TEXT_BGC_ON 与 HTML_PRINTC 的扁平通道与 fork 实参表等价`() {
        // 生产路径（扁平直读）与对象树通道读数一致：
        // TEXT_BGC_ON 的 SP_SWAP 实参表 = 2 个 INT（fork SP_COLOR_ALPHA；
        // `ArgumentBuilder.cs:2074-2099` = fork-src 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）），
        // 经 `SpSwapCharaArgument` → `ArgTag.SP_SWAP_CHARA` 记录承载（本机可达替代依据）；
        // HTML_PRINTC 的 SP_HTML_PRINT 实参表 = STR(+可选 INT)（fork SP_HTML_PRINTC；
        // `ArgumentBuilder.cs:408-442` = fork-src 行号不可本机取证（TODO(verify)：待 fork-src 就位后复核）），
        // 经 `SpHtmlPrint` → `ArgTag.SP_HTML_PRINT` 记录承载（本机可达替代依据）。
        val env = SemTestEnv()
        val bg = BgGraphics()
        env.ctx.graphics = bg
        env.runFlat(
            instr(FunctionCode.TEXT_BGC_ON, SpSwapCharaArgument(IntLiteral(0x0A0B0C), IntLiteral(50))),
        )
        assertEquals(
            listOf(0x0A0B0CL to 50L), bg.bgCalls,
            "SP_SWAP_CHARA 记录经扁平通道还原为两参 INT",
        )
        env.runFlat(instr(FunctionCode.HTML_PRINTC, SpHtmlPrint(StrLiteral("<b>x</b>"), null)))
        assertEquals(
            listOf("printHtml(<b>x</b>,true)"), env.console.events,
            "SP_HTML_PRINT 记录经扁平通道还原为 STR 实参（纯 sem 假体无 BuiltinContext ⇒ 不补齐）",
        )
    }

    // ------------------------------------------------------------------
    // 编译/运行装置（与 StmtFamilyAuditTest 同形，私有类型不可跨文件复用）
    // ------------------------------------------------------------------

    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "upstream-statements.erb", i + 1, text))
        }
    }

    private fun compileProgram(vararg funcs: Pair<String, List<String>>): Program {
        val c = ErbProgramCompiler()
        val known = funcs.map { it.first.uppercase() }.toSet()
        for ((name, lines) in funcs) {
            val params = headerParams(lines.first())
            c.compileFunction(
                FuncSig(name, arity = params.size, paramNames = params),
                parseErbLines(*lines.toTypedArray()),
                known,
            )
        }
        return c.build(funcs.first().first)
    }

    private fun headerParams(labelLine: String): List<String> {
        val open = labelLine.indexOf('(')
        val close = labelLine.lastIndexOf(')')
        if (open < 0 || close <= open) return emptyList()
        return labelLine.substring(open + 1, close)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun runProgram(program: Program, store: VariableStore) {
        val table = FunctionTable(
            program.functions.mapIndexed { i, f -> FuncMeta(index = i, key = f.name.uppercase()) },
        )
        ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 50_000L)).execute()
    }
}
