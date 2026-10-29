package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.FakeVariables
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **通用指令行**（marker 入池的 `InstructionStmt` 家族）参数位的表达式位调用降级
 * （h-htmlp；契约 M4-H §2；A15 遗留的"降级覆盖面"缺口）。
 *
 * ## 现场
 *
 * bootSmoke 首差（main `e64ec0f`）：`HTMLPRINTL` 行 118 / pc 171663 的
 * `HTML_PRINT HTMLP(HTMLFONT(HTMLSTYLE(ARGS:0), …), ARGS:1)` 在运行期撞
 * `ProcessRuntime.kt:297` 的"字符串方法 HTMLP 的表达式位调用未经编译期降级"。
 * 根因不在 `_RETS` 通道（A7 已接），而在 [ErbProgramCompiler.marker] 的入池前下沉
 * [lowerStatementForPool] 只覆盖 AssignStmt / PrintStmt / ReturnStmt / ReturnFStmt 四类，
 * `HTML_PRINT`（以及全部 `SpHtmlPrint`/`ExpressionArgument`/`SpBarArgument` 形态的
 * 普通指令行）走 `else -> s` 原样入池。
 *
 * ## 语义依据（reference/emuera-cs）
 *
 * - 表达式位的用户方法 = `UserDefinedMethodTerm`，装载期即完成调用绑定与实参转换，
 *   spec: IdentifierDictionary.cs:548-586（REFFUNC 表 :550-553、`#FUNCTION` 方法 :561-565、
 *   非方法名 throw UsedNonMethodFunc :566-569）+ UserDefinedMethodTerm.cs:17-26；
 *   运行期 `Process.GetValue` 进被调帧取返回值，spec: Process.cs:514-540。
 *   ⇒ **原版没有"语句位降级、表达式位不降级"的分野**：任何位置的 `#FUNCTION(S)` 调用
 *   都在解析期变成求值节点。本测试钉住"参数位与右值位同一口径"。
 * - 实参**升序**求值 spec: UserDefinedFunctionArgument.cs:35-69（null 位不求值 :39-42）；
 *   被调帧 ARG 升序写入、null 位跳过 spec: ProcessState.cs:435-452。
 * - `#FUNCTIONS`（str）返回值经 `_RETS` 字符串通道 + `#EXPR_RETS#<k>` 哨兵
 *   （合同 M3-A §3 A7；`_CALL` 的 b bit28 = STR_RET_FLAG）。
 * - CALLSHARP 的**变量实参回写**位（specs/isa.yaml 的 CALLSHARP 段，
 *   spec: FunctionIdentifier.cs:1218-1233）必须是原 `VarRef`：降级只替换 `FuncCall`
 *   节点，故实参位里的变量引用不受影响（本类第 9 锁）。
 *
 * ## 三条红线（本类逐条上锁）
 *
 * 1. 表不在位 ⇒ 旧行为（语料零回退底线）；
 * 2. 数据节家族（[PrintDataStmt] / [DataStmt]）语句实例**身份不变**（契约 C2 身份链，
 *    与 [ExprCanonicalizer.canonicalizeStatement] 同一豁免口径）；
 * 3. 子类身份保真：重建只回到同一个具体类，不把子类压成父类 [InstructionStmt] 空壳。
 */
class RefExprCallLoweringTest {

    private companion object {
        /** `_CALL` 的 b bit28 = 字符串返回标志（合同 M3-A §3 A7；同 Vm.kt 的 STR_RET_FLAG）。 */
        const val STR_RET_BIT = 0x10000000
    }

    // ------------------------------------------------------------------
    // 基础设施（夹具与 ExprCallLoweringTest 同源；HTMLP/HTMLSTYLE 取自真实语料名）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("S", OperandType.STR, 1)
            .declareFunction("HTMLP", OperandType.STR)       // @HTMLP = #FUNCTIONS（首差现场）
            .declareFunction("HTMLSTYLE", OperandType.STR)   // @HTMLSTYLE = #FUNCTIONS
            .declareFunction("MYFUNC", OperandType.INT)      // #FUNCTION（int 方法）
            .declareFunction("TARGET", OperandType.INT)      // 普通 @label（CALL 目标）
            .declareFunction("PLAIN", OperandType.INT)       // 普通 @label（表达式位必须抛）
            .declareFunction("PLAINSTR", OperandType.STR),   // 普通 @label（str 版，供 HTML_PRINT 位抛）
    )
    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun table() = ExprCallTable(
        intMethods = setOf("MYFUNC"),
        strMethods = setOf("HTMLP", "HTMLSTYLE"),
        nonMethodLabels = setOf("PLAIN", "PLAINSTR"),
    )

    /** 一个待编译函数：名字 + 形参数（arity，决定 VM `_CALL` 槽复制数）+ 体。 */
    private data class Fn(val name: String, val arity: Int, val lines: Array<String>, val returnsStr: Boolean = false)

    /** 首函数 = 调用方（挂降级表），其余 = 被调方法体。 */
    private fun compileCallers(vararg funcs: Fn): Program {
        val c = ErbProgramCompiler()
        val names = funcs.map { it.name }.toSet()
        val tbl = table()
        // CMP-31 起 RETURNF 有装载期形态校验（只认 #FUNCTION/#FUNCTIONS）。本夹具不区分
        // 方法/普通函数而被调体带 RETURNF（原版即 #FUNCTION(#FUNCTIONS) 方法体）；
        // `isMethod` 不参与发射（字符串返回由 returnsStr 决定），故夹具统一按方法编译。
        for (f in funcs) c.compileFunction(FuncSig(f.name, f.arity, isMethod = true, returnsStr = f.returnsStr), parse(*f.lines), names, tbl)
        return c.build(funcs.first().name)
    }

    private fun find(p: Program, pred: (com.eratw.port.core.code.Instruction) -> Boolean): Int {
        for (pc in 0 until p.instructionCount) if (pred(p.instructionAt(pc))) return pc
        return -1
    }

    private fun calls(p: Program): List<com.eratw.port.core.code.Instruction> =
        (0 until p.instructionCount).map { p.instructionAt(it) }.filter { it.op == Opcode._CALL }

    /** `_CALL` 的目标名（build() 期把函数下标 patch 进 a 操作数）。 */
    private fun callTargets(p: Program): List<String> = calls(p).map { p.functions[it.a].name }

    /** marker 指令（语句池下标在 a）对应的语句实例；非 marker 返回 null。 */
    private fun markerStmt(p: Program, pc: Int): Statement? {
        val ins = p.instructionAt(pc)
        return if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a) else null
    }

    /** 池内第一条 [code] 语句（HTML_PRINT / BAR / CALLSHARP 等通用指令行）。 */
    private fun poolStatement(p: Program, code: FunctionCode): Statement? {
        for (pc in 0 until p.instructionCount) {
            val s = markerStmt(p, pc)
            if (s != null && s.code == code) return s
        }
        return null
    }

    private fun htmlPrintStr(p: Program): Expr? =
        when (val s = poolStatement(p, FunctionCode.HTML_PRINT)) {
            is InstructionStmt -> when (val a = s.args) {
                is SpHtmlPrint -> a.str
                is HtmlPrintArgument -> a.term
                else -> null
            }
            else -> null
        }

    private fun collectCalls(e: Expr?): List<FuncCall> = when (e) {
        null -> emptyList()
        is FuncCall -> listOf(e) + e.args.filterNotNull().flatMap { collectCalls(it) }
        is com.eratw.port.core.expr.Binary -> collectCalls(e.left) + collectCalls(e.right)
        is com.eratw.port.core.expr.Ternary -> collectCalls(e.cond) + collectCalls(e.ifTrue) + collectCalls(e.ifFalse)
        is com.eratw.port.core.expr.Unary -> collectCalls(e.operand)
        is FormStringTerm -> e.parts.filterIsInstance<FormPart.Embedded>().flatMap { collectCalls(it.ast) }
        is VarRef -> e.indices.flatMap { collectCalls(it) }
        else -> emptyList()
    }

    // ---- 执行替身：VM + 哨兵桥 + HTML_PRINT 出口（复刻 A4 接线，本类同时是行为锁） ----

    private class Host : VmHost {
        val out = StringBuilder()
        val html = StringBuilder()
        val vars = FakeVariables()
        var vm: VirtualMachine? = null

        /** ARG/ARGS 的读 = 当前 VM 帧槽（`_CALL` 已按位置复制 R0..，spec: Vm.kt callInto）。 */
        val reader = object : com.eratw.port.core.expr.VariableReader by vars {
            override fun readInt(name: String, indices: LongArray): Long =
                if (name.equals("ARG", ignoreCase = true) && indices.isNotEmpty())
                    requireNotNull(vm).getInt(SlotAddr.local(indices[0].toInt()))
                else vars.readInt(name, indices)

            override fun readStr(name: String, indices: LongArray): String =
                if (name.equals("ARGS", ignoreCase = true) && indices.isNotEmpty())
                    requireNotNull(vm).getStr(SlotAddr.local(indices[0].toInt()))
                else vars.readStr(name, indices)
        }

        val ctx = object : EvalContext {
            override val variables: com.eratw.port.core.expr.VariableReader get() = reader
            override fun callFunction(name: String, args: List<Value?>): Value {
                exprStrTempSlotOf(name)?.let { k ->
                    return Value.StrV(requireNotNull(vm).getStr(SlotAddr.local(k)))
                }
                val k = exprTempSlotOf(name)
                    ?: throw ExprEvalException("表达式位调用未降级: $name")
                return Value.IntV(requireNotNull(vm).getInt(SlotAddr.local(k)))
            }
        }
        override val evalContext: EvalContext? get() = ctx
        override fun print(text: String) { out.append(text) }

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            when (stmt) {
                is PrintStmt -> {
                    val t = (stmt.args as? ExpressionArgument)?.term
                    if (t is StrLiteral) out.append(t.value)
                    if (stmt.isLineEnd) out.append('\n')
                }
                is AssignStmt -> {
                    val rhs = (stmt.args as? SpSetArgument)?.term ?: return StmtOutcome.Normal
                    val key = if (stmt.dest.indices.isEmpty()) stmt.dest.name else
                        stmt.dest.name + stmt.dest.indices.joinToString(":", prefix = ":")
                    if (stmt.dest.operandType == OperandType.STR) vars.strs[key] = rhs.getStrValue(ctx)
                    else vars.ints[key] = rhs.getIntValue(ctx)
                }
                // 首差现场：HTML_PRINT 的 str 位经 evalContext 求值 → 哨兵读调用方帧槽
                is InstructionStmt -> if (stmt.code == FunctionCode.HTML_PRINT) {
                    val a = stmt.args as? SpHtmlPrint
                    if (a != null) html.append(a.str?.getStrValue(ctx) ?: "")
                }
                else -> {}
            }
            return StmtOutcome.Normal
        }
    }

    private fun runProg(p: Program): Host {
        val h = Host()
        val vm = VirtualMachine(p, h)
        h.vm = vm
        vm.run()
        return h
    }

    // ------------------------------------------------------------------
    // 锁 1：HTML_PRINT 参数位的字符串方法 —— 编译形状（首差现场）
    // ------------------------------------------------------------------

    @Test
    fun `HTML_PRINT 参数位的字符串方法降级为 CALL 加字符串哨兵`() {
        val p = compileCallers(
            Fn("HTMLPRINTL", 0, arrayOf("HTML_PRINT HTMLP(\"x\")")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + \"</p>\""), returnsStr = true),
        )
        val call = assertNotNull(
            calls(p).firstOrNull { it.b and STR_RET_BIT != 0 },
            "参数位降级必须发射 `_CALL`（旧形态：原样入池 → ProcessRuntime.kt:297 红）",
        )
        assertEquals(1, call.b and STR_RET_BIT.inv(), "b 的低 28 位 = 实参数（升序全部实参）")
        assertTrue(!SlotAddr.isNone(call.c), "c = 调用方帧的匿名字符串返回槽（契约 C1）")
        val ret = SlotAddr.index(call.c)
        val str = assertNotNull(htmlPrintStr(p), "HTML_PRINT 语句必须入池")
        val sentinel = assertNotNull(
            collectCalls(str).singleOrNull { exprStrTempSlotOf(it.name) != null },
            "参数位应降级为字符串哨兵：$str",
        )
        assertEquals(ret, exprStrTempSlotOf(sentinel.name), "哨兵读的就是 _CALL 的返回槽")
        assertTrue(sentinel.args.isEmpty(), "哨兵零参")
        assertEquals(OperandType.STR, sentinel.operandType)
        assertTrue(
            collectCalls(str).none { it.name == "HTMLP" },
            "池内不得残留用户方法调用节点（残留 = 运行期红）：$str",
        )
        // 语句身份：marker 入池的仍是普通 InstructionStmt(HTML_PRINT)，未被压成别的类
        val s = assertIs<InstructionStmt>(assertNotNull(poolStatement(p, FunctionCode.HTML_PRINT)))
        assertEquals(FunctionCode.HTML_PRINT, s.code)
    }

    // ------------------------------------------------------------------
    // 锁 2：执行行为 —— 值真的落到 HTML_PRINT 出口
    // ------------------------------------------------------------------

    @Test
    fun `HTML_PRINT 参数位降级后运行期取到被调方法返回值`() {
        val p = compileCallers(
            Fn("HTMLPRINTL", 0, arrayOf("HTML_PRINT HTMLP(\"x\")")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + \"</p>\""), returnsStr = true),
        )
        val h = runProg(p)
        assertEquals("<p>x</p>", h.html.toString(), "实参升序进 R0 → 被调帧 ARGS:0 → _RETS → 哨兵读槽")
    }

    // ------------------------------------------------------------------
    // 锁 3：嵌套字符串方法（内层先调用）—— 前导指令顺序 = 原版实参升序求值
    // ------------------------------------------------------------------

    @Test
    fun `嵌套字符串方法按内层先外层次序发射 CALL`() {
        val p = compileCallers(
            Fn("HTMLPRINTL", 0, arrayOf("HTML_PRINT HTMLP(HTMLSTYLE(\"y\"), \"c\")")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + ARGS:1 + \"</p>\""), returnsStr = true),
            Fn("HTMLSTYLE", 2, arrayOf("RETURNF \"<b>\" + ARGS:0 + \"</b>\""), returnsStr = true),
        )
        val targets = callTargets(p)
        assertEquals(listOf("HTMLSTYLE", "HTMLP"), targets, "内层实参调用必须先于外层方法调用")
        val h = runProg(p)
        assertEquals("<p><b>y</b>c</p>", h.html.toString())
    }

    // ------------------------------------------------------------------
    // 锁 4：通用指令行的 int 方法参数位（SpBarArgument 形态）
    // ------------------------------------------------------------------

    @Test
    fun `通用指令行的 int 方法参数位降级为整型哨兵`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("BAR MYFUNC(1), 2, 3")),
            Fn("MYFUNC", 1, arrayOf("RETURN 3")),
        )
        val call = assertNotNull(calls(p).singleOrNull(), "int 方法参数位必须发射恰好一条 `_CALL`")
        assertEquals(0, call.b and STR_RET_BIT, "int 通道不置字符串返回位")
        assertEquals(1, call.b and STR_RET_BIT.inv(), "argc = 1")
        assertEquals("MYFUNC", p.functions[call.a].name)
        val s = assertIs<InstructionStmt>(assertNotNull(poolStatement(p, FunctionCode.BAR)))
        val arg = assertIs<com.eratw.port.core.stmt.SpBarArgument>(s.args)
        val sentinel = assertNotNull(collectCalls(arg.value).singleOrNull { exprTempSlotOf(it.name) != null })
        assertEquals(SlotAddr.index(call.c), exprTempSlotOf(sentinel.name))
        assertEquals(OperandType.INT, sentinel.operandType)
    }

    // ------------------------------------------------------------------
    // 锁 5：非调用表达式位原样保留（实参里的变量引用不得被替换）
    // ------------------------------------------------------------------

    @Test
    fun `同一条语句的变量实参位保持 VarRef 不被替换`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("HTML_PRINT HTMLP(\"x\"), A")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + \"</p>\""), returnsStr = true),
        )
        val s = assertIs<InstructionStmt>(assertNotNull(poolStatement(p, FunctionCode.HTML_PRINT)))
        val arg = assertIs<SpHtmlPrint>(s.args)
        val opt = assertIs<VarRef>(arg.opt, "opt 位不是方法调用 ⇒ 必须原样是 VarRef")
        assertEquals("A", opt.name)
        assertTrue(collectCalls(arg.str).any { exprStrTempSlotOf(it.name) != null })
    }

    // ------------------------------------------------------------------
    // 锁 6：表不在位 = 旧行为（语料 100% 编译的回归底线）
    // ------------------------------------------------------------------

    @Test
    fun `未挂表时通用指令行的调用原样入池不降级不报错`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST", 0), parse("HTML_PRINT HTMLP(\"x\")"), setOf("HTMLP"))
        val p = c.build("TEST")
        assertTrue(calls(p).isEmpty(), "exprCalls = null ⇒ 一条 `_CALL` 都不许多发")
        val str = assertNotNull(htmlPrintStr(p))
        assertEquals("HTMLP", assertIs<FuncCall>(str).name, "旧行为：节点原样留给运行期")
    }

    @Test
    fun `无方法调用的通用指令行不产生任何额外指令`() {
        val p = compileCallers(Fn("TEST", 0, arrayOf("HTML_PRINT \"plain\"")))
        assertTrue(calls(p).isEmpty(), "无调用 ⇒ 零前导 `_CALL`（marker 只发它自己那条）")
        assertNotNull(poolStatement(p, FunctionCode.HTML_PRINT))
    }

    // ------------------------------------------------------------------
    // 锁 7：数据节豁免（契约 C2 身份链；与 ExprCanonicalizer 同一豁免口径）
    // ------------------------------------------------------------------

    @Test
    fun `数据节语句实例身份保持不变且不前移方法调用`() {
        // PRINTDATA 的目的变量是 int（VAR_INT 随机索引槽，spec: PrintDataArgument.cs:6-13）
        val stmts = parse("PRINTDATA A", "DATA hello", "ENDDATA")
        val dataHead = stmts.filterIsInstance<PrintDataStmt>().single()
        val dataLine = stmts.filterIsInstance<DataStmt>().single()
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST", 0), stmts, setOf("HTMLP"), table())
        val p = c.build("TEST")
        assertSame(dataHead, poolStatement(p, FunctionCode.PRINTDATA), "PRINTDATA 头语句必须同一实例")
        assertSame(dataLine, poolStatement(p, FunctionCode.DATA), "DATA 行必须同一实例")
        assertTrue(calls(p).isEmpty(), "数据节豁免 ⇒ 不得为其参数发前导 `_CALL`")
    }

    @Test
    fun `数据节里的 FORM 表达式位也保持语句实例身份不被重建`() {
        // 与 E2E `SDV=` 首差同一身份链契约：数据行含 FORM 串（最容易被"顺手降级"的形态）。
        // 单行 `STRDATA <dest>` 本身解析为 DataStmt（LineParser.kt:423），与 PRINTDATA 头
        // 分属两类，故逐 code 取实例。
        val stmts = parse("STRDATA S", "DATA %HTMLP(\"x\")%", "ENDDATA")
        val head = stmts.filterIsInstance<DataStmt>().single { it.code == FunctionCode.STRDATA }
        val dataLine = stmts.filterIsInstance<DataStmt>().single { it.code == FunctionCode.DATA }
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST", 0), stmts, setOf("HTMLP"), table())
        val p = c.build("TEST")
        assertSame(head, poolStatement(p, FunctionCode.STRDATA), "STRDATA 语句必须同一实例")
        assertSame(dataLine, poolStatement(p, FunctionCode.DATA), "数据行必须同一实例")
        assertTrue(calls(p).isEmpty(), "数据行不降级 ⇒ 一条 `_CALL` 都不许多发")
    }

    // ------------------------------------------------------------------
    // 锁 8：CALLSHARP 的变量实参回写位（specs/isa.yaml CALLSHARP，spec: FunctionIdentifier.cs:1218-1233）
    // ------------------------------------------------------------------

    @Test
    fun `CALLSHARP 只降实参位且函数名与变量实参位不动`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("CALLSHARP FOO(HTMLP(\"x\"), A)")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + \"</p>\""), returnsStr = true),
        )
        val s = assertIs<CallSharpStmt>(assertNotNull(poolStatement(p, FunctionCode.CALLSHARP)))
        val arg = assertIs<SpCallSharpArgument>(s.args)
        val name = assertIs<StrLiteral>(arg.funcNameTerm, "函数名表达式不是值实参位 ⇒ 不降级")
        assertEquals("FOO", name.value)
        assertEquals(listOf("HTMLP"), callTargets(p), "只有实参位的方法调用被降级")
        assertTrue(
            arg.rowArgs.any { it is VarRef && it.name == "A" },
            "变量实参位必须仍是 VarRef，运行期回写才有着落（spec: FunctionIdentifier.cs:1218-1233）",
        )
    }

    // ------------------------------------------------------------------
    // 锁 9：CALL 家族不双发（实参由 stageArgs 求值，参数层不得再降一遍）
    // ------------------------------------------------------------------

    @Test
    fun `CALL 语句的实参降级只发生一次`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("CALL TARGET(MYFUNC(1))")),
            Fn("TARGET", 1, arrayOf("RETURN 7")),
            Fn("MYFUNC", 1, arrayOf("RETURN 3")),
        )
        val targets = callTargets(p)
        assertEquals(listOf("MYFUNC", "TARGET"), targets, "内层方法调用先于目标调用，且各一条")
    }

    // ------------------------------------------------------------------
    // 锁 10：非方法 @label 在通用指令参数位 = 编译期抛（ADR-005 复刻，不许悄悄支持）
    // ------------------------------------------------------------------

    @Test
    fun `普通 label 出现在通用指令参数位时编译期抛 UsedNonMethodFunc 复刻`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("TEST", 0), parse("HTML_PRINT PLAINSTR(1)"), setOf("PLAINSTR"), table())
        }
        assertTrue(e.message!!.contains("UsedNonMethodFunc"), "报错口径与表达式位一致：${e.message}")
    }

    // ------------------------------------------------------------------
    // 锁 11：拼接位（STR 二元）里的方法调用同样降级（严格求值位）
    // ------------------------------------------------------------------

    @Test
    fun `字符串拼接位里的方法调用降级`() {
        val p = compileCallers(
            Fn("HTMLPRINTL", 0, arrayOf("HTML_PRINT \"a\" + HTMLP(\"x\")")),
            Fn("HTMLP", 2, arrayOf("RETURNF \"<p>\" + ARGS:0 + \"</p>\""), returnsStr = true),
        )
        assertEquals(1, calls(p).size, "拼接位是严格求值位 ⇒ 前导 `_CALL` 恰一条")
        assertEquals("a<p>x</p>", runProg(p).html.toString())
    }
}
