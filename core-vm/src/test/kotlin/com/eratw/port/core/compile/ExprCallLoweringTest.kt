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
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 表达式位用户方法调用的编译期降级（A15 任务 3；契约 C1/C3/C5/R6）。
 *
 * 语义定稿（reference/emuera-cs，逐行见 ErbProgramCompiler.kt"表达式位用户方法调用的
 * 编译期降级"注释块）：
 * - 求值路径 spec: IdentifierDictionary.cs:548-586 / UserDefinedMethodTerm.cs:17-26 /
 *   Process.cs:514-540；
 * - 实参**升序**求值 spec: UserDefinedFunctionArgument.cs:35-69（null 位不求值 :39-42），
 *   ARG **升序**写入、null 位跳过不写 spec: ProcessState.cs:435-452（守卫 :437）；
 * - **非方法 @label 在表达式位抛 UsedNonMethodFunc** spec: IdentifierDictionary.cs:566-569
 *   （同名内建存在时静默走内建 :566/:576-585）—— 本层原样复刻、不许悄悄支持（ADR-005）。
 *
 * 降级形态：实参升序求值到调用方帧的匿名临时槽（≥ tempBase，两遍 maxArgc 策略沿用）→
 * 依序 `_MOV_*` 进 R0..R(argc-1) → `_CALL`（c=返回槽）→ 返回值经 `_RET`/leaveFrame 写回
 * （spec: Vm.kt:438-446/570-580）→ 外层表达式用零参哨兵 `#EXPR_RET#<k>` 读该槽。
 * `#FUNCTIONS`（str）方法同形降级，但 `_CALL` 置 bit28 = STR_RET_FLAG、被调帧经 `_RETS`
 * 返回、哨兵换 `#EXPR_RETS#<k>` 前缀（合同 M3-A §3 A7）。
 * 运行期哨兵桥（ProcessRuntime 侧接线归 A4，diff 见 A15 报告）在本测试由 [Host] 复刻，
 * 因此这些用例同时是**编译形状锁**与**执行行为锁**。
 */
class ExprCallLoweringTest {

    private companion object {
        /** `_CALL` 的 b bit28 = 字符串返回标志（合同 M3-A §3 A7；同 Vm.kt 的 STR_RET_FLAG）。 */
        const val STR_RET_BIT = 0x10000000
    }

    // ------------------------------------------------------------------
    // 基础设施（解析夹具与编译器测试同源；methods/labels 分类由 ExprCallTable 注入）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("B", OperandType.INT, 1)
            .declareVariable("S", OperandType.STR, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareFunction("MYFUNC", OperandType.INT)      // #FUNCTION（int 方法）
            .declareFunction("DOUBLE", OperandType.INT)
            .declareFunction("ADD2", OperandType.INT)
            .declareFunction("STRM", OperandType.STR)        // #FUNCTIONS（str 方法，A7 起降级走 _RETS）
            .declareFunction("PLAIN", OperandType.INT),      // 普通 @label
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

    private fun table(
        int: Set<String> = setOf("MYFUNC", "DOUBLE", "ADD2"),
        str: Set<String> = setOf("STRM"),
        labels: Set<String> = setOf("PLAIN"),
    ) = ExprCallTable(int.map { it.uppercase() }.toSet(), str.map { it.uppercase() }.toSet(), labels.map { it.uppercase() }.toSet())

    /** 一个待编译函数：名字 + 形参数（arity，决定 VM `_CALL` 槽复制数）+ 体。 */
    private data class Fn(val name: String, val arity: Int, val lines: Array<String>, val returnsStr: Boolean = false)

    /** 首函数=调用方（挂降级表），其余=被调方法体。 */
    private fun compileCallers(vararg funcs: Fn): Program {
        val c = ErbProgramCompiler()
        val names = funcs.map { it.name }.toSet()
        val tbl = table()
        // CMP-31 起 RETURNF 有装载期形态校验（只认 #FUNCTION/#FUNCTIONS）。本夹具不区分
        // 方法/普通函数，被调体又带 RETURNF（原版即 #FUNCTION 方法体）；`isMethod` 不参与
        // 发射（只决定 _RETS 的是 returnsStr），故夹具统一按方法编译。
        for (f in funcs) c.compileFunction(FuncSig(f.name, f.arity, isMethod = true, returnsStr = f.returnsStr), parse(*f.lines), names, tbl)
        return c.build(funcs.first().name)
    }

    private fun find(p: Program, from: Int = 0, pred: (com.eratw.port.core.code.Instruction) -> Boolean): Int {
        for (pc in from until p.instructionCount) if (pred(p.instructionAt(pc))) return pc
        return -1
    }

    private fun allPcs(p: Program, from: Int = 0, pred: (com.eratw.port.core.code.Instruction) -> Boolean): List<Int> =
        (from until p.instructionCount).filter { pred(p.instructionAt(it)) }

    private fun markerStmt(p: Program, pc: Int): Statement? {
        val ins = p.instructionAt(pc)
        return if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a) else null
    }

    private fun rhsOfFirstAssign(p: Program): Expr? {
        val pc = (0 until p.instructionCount).firstOrNull { (markerStmt(p, it) as? AssignStmt) != null } ?: return null
        return ((p.instructionAt(pc).let { markerStmt(p, pc) } as AssignStmt).args as SpSetArgument).term
    }

    // ---- 执行替身：VM + 哨兵桥（复刻 A4 需要的 ProcessRuntime 六行接线） ----

    private class Host : VmHost {
        val out = StringBuilder()
        val vars = FakeVariables()
        var vm: VirtualMachine? = null

        /**
         * ARG/ARGS 的读 = **当前 VM 帧槽**（`_CALL` 已按位置复制 R0..R(argc-1)，
         * spec: Vm.kt callInto :536-539）——复刻 proc 层 bindArgs 的分桶绑定；其余走 [vars]。
         */
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
                // 字符串哨兵（`#EXPR_RETS#<k>`，合同 M3-A §3 A7）：读调用方帧的 str 临时槽。
                exprStrTempSlotOf(name)?.let { k ->
                    return Value.StrV(requireNotNull(vm).getStr(SlotAddr.local(k)))
                }
                val k = exprTempSlotOf(name)
                    ?: throw ExprEvalException("表达式位调用未降级: $name（哨兵桥只认 $EXPR_TEMP_PREFIX/$EXPR_STR_TEMP_PREFIX）")
                return Value.IntV(requireNotNull(vm).getInt(SlotAddr.local(k)))
            }
        }
        override val evalContext: EvalContext? get() = ctx
        override fun print(text: String) { out.append(text) }

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            when (stmt) {
                is PrintStmt -> {
                    when (val t = (stmt.args as? ExpressionArgument)?.term) {
                        is StrLiteral -> out.append(t.value)
                        is IntLiteral -> out.append(t.value)
                        is VarRef -> out.append(
                            if (t.operandType == OperandType.STR) t.getStrValue(ctx) else t.getIntValue(ctx).toString(),
                        )
                        else -> {}
                    }
                    if (stmt.isLineEnd) out.append('\n')
                }
                is AssignStmt -> {
                    val rhs = (stmt.args as? SpSetArgument)?.term ?: return StmtOutcome.Normal
                    val idx = LongArray(stmt.dest.indices.size) { stmt.dest.indices[it].getIntValue(ctx) }
                    val key = if (idx.isEmpty()) stmt.dest.name else stmt.dest.name + idx.joinToString(":", prefix = ":")
                    if (stmt.dest.operandType == OperandType.STR) {
                        vars.strs[key] = rhs.getStrValue(ctx)
                    } else {
                        val v = rhs.getIntValue(ctx)
                        when (stmt.op) {
                            OperatorCode.ASSIGNMENT -> vars.ints[key] = v
                            OperatorCode.PLUS -> vars.ints[key] = (vars.ints[key] ?: 0L) + v
                            else -> {}
                        }
                    }
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

    private fun intVar(h: Host, name: String): Long = h.vars.ints["$name:0"] ?: h.vars.ints[name] ?: 0L

    /** 字符串变量读：一维声明未写下标时解析期补默认 0 ⇒ 键既可能是 `NAME` 也可能是 `NAME:0`。 */
    private fun strVar(h: Host, name: String): String? = h.vars.strs["$name:0"] ?: h.vars.strs[name]

    // ------------------------------------------------------------------
    // 锁 1：表达式位 int 方法调用 —— 结构（实参升序暂存 → _MOV → _CALL(c=返回槽) → 哨兵）
    // ------------------------------------------------------------------

    @Test
    fun `表达式位 int 方法降级为实参求值加CALL加返回哨兵`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("A = MYFUNC(1, 2)", "PRINTL E")),
            Fn("MYFUNC", 2, arrayOf("RETURNF 42")),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0, "降级必须发射 _CALL（旧形态：整树入池、运行期撞 ProcessRuntime.kt:177-180 桩抛）")
        val ins = p.instructionAt(call)
        assertEquals(2, ins.b, "b = 实参数（升序全部实参）")
        assertTrue(!SlotAddr.isNone(ins.c), "c = 调用方帧的匿名返回槽（契约 C1：非具名、非 VariableStore）")
        val retSlot = SlotAddr.index(ins.c)
        // 暂存区/临时槽分界（**只看 TEST 函数内、_CALL 之前**的求值槽，避开被调方法自身的临时槽）：
        // maxArgc=2 → TEST 里实参/返回临时槽都 ≥2，不得踩 R0..R1
        // （_SET_* 的目标在 a，_EVAL_* 的目标在 b，见 OpcodeTable.kt:119-123）
        val staging = (0 until call).map { p.instructionAt(it) }.filter {
            it.op == Opcode._SET_INT || it.op == Opcode._EVAL_INT ||
                it.op == Opcode._SET_STR || it.op == Opcode._EVAL_STR
        }.map { i0 ->
            if (i0.op == Opcode._EVAL_INT || i0.op == Opcode._EVAL_STR) i0.b else i0.a
        }
        assertTrue(staging.isNotEmpty() && staging.all { SlotAddr.index(it) >= 2 }, "TEST 内实参/返回临时槽 ≥ maxArgc")
        assertTrue(retSlot >= 2, "返回槽同为帧内临时槽（≥ maxArgc，契约 C1）")
        val movs = allPcs(p, 0) { it.op == Opcode._MOV_INT && SlotAddr.index(it.a) < 2 }
        assertEquals(listOf(0, 1), movs.map { SlotAddr.index(p.instructionAt(it).a) }, "依序 _MOV 进 R0..R(argc-1)")
        // 语句池 marker 的右值 = 零参哨兵，槽号与 _CALL.c 一致；原 MYFUNC 节点不再出现在任何池树
        val rhs = assertIs<FuncCall>(rhsOfFirstAssign(p), "赋值右值应为哨兵 FuncCall")
        assertEquals(retSlot, exprTempSlotOf(rhs.name), "哨兵读的就是 _CALL 的返回槽")
        assertTrue(rhs.args.isEmpty(), "哨兵零参")
        assertEquals(OperandType.INT, rhs.operandType)
        assertTrue(
            p.expressions().none { it is FuncCall && it.name == "MYFUNC" },
            "降级后表达式池不得残留用户方法调用节点",
        )
        // 执行行为锁：返回 42 落进 A
        val h = runProg(p)
        assertEquals(42L, intVar(h, "A"))
    }

    // ------------------------------------------------------------------
    // 锁 2：嵌套调用（f(g(x), y)）不得破坏外层已求值实参（tempBase 抬升策略保持）
    // ------------------------------------------------------------------

    @Test
    fun `嵌套表达式调用不破坏外层已求值实参`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("B = ADD2(DOUBLE(3), 9)", "A = ADD2(7, DOUBLE(3))")),
            Fn("ADD2", 1, arrayOf("RETURNF ARG")),
            Fn("DOUBLE", 1, arrayOf("RETURNF ARG + ARG")),
        )
        val h = runProg(p)
        assertEquals(6L, intVar(h, "B"), "内层 DOUBLE(3)=6 必须作为 ADD2 的 R0 送达（ARG=6）")
        assertEquals(7L, intVar(h, "A"), "内层调用的暂存/返回槽不得踩外层已求值实参 R0=7")
        // 两条降级调用互不干扰：_CALL 恰 2 次（内层各一 + 外层各一 = 4 次？逐条数：每行 2 次）
        assertEquals(4, allPcs(p) { it.op == Opcode._CALL }.size, "每行 内层+外层 两次 _CALL")
    }

    // ------------------------------------------------------------------
    // 锁 3：普通 @label 在表达式位 —— 编译期抛（UsedNonMethodFunc 复刻，不许悄悄支持）
    // ------------------------------------------------------------------

    @Test
    fun `普通 label 表达式位调用编译期抛 UsedNonMethodFunc`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("TEST"), parse("A = PLAIN(1)"), setOf("TEST", "PLAIN"), table())
        }
        assertTrue(e.message!!.contains("行内函数形式的调用"), e.message ?: "")
        assertTrue(e.message!!.contains("UsedNonMethodFunc"), e.message ?: "")
    }

    @Test
    fun `内建同名 label 在表达式位静默走内建`() {
        // spec: IdentifierDictionary.cs:566（`!methodDic.ContainsKey` 才抛）+ :576-585 回落内建。
        val b = Builtins.registry.names().first().uppercase()
        val t = ExprCallTable(nonMethodLabels = setOf(b))
        assertEquals(ExprCallKind.BUILTIN, t.classify(b))
    }

    // ------------------------------------------------------------------
    // 锁 4：实参含 FORM 串（str 实参走 _MOV_STR 传参，运行期照常降级调用）
    // ------------------------------------------------------------------

    @Test
    fun `实参含 FORM 串走 MOV_STR 暂存传参`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("A = MYFUNC(\"x{1+2}\", 5)", "PRINTL E")),
            Fn("MYFUNC", 2, arrayOf("RETURNF 42")),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0)
        val movStr = allPcs(p, 0) { it.op == Opcode._MOV_STR && SlotAddr.index(it.a) < 2 }
        assertTrue(movStr.isNotEmpty(), "FORM 串实参必须经 _EVAL_STR + _MOV_STR 进 R0")
        assertNotNull(rhsOfFirstAssign(p))
        val h = runProg(p)
        assertEquals(42L, intVar(h, "A"))
    }

    // ------------------------------------------------------------------
    // 锁 5：#FUNCTIONS（str）方法降级为 _CALL(STR_RET bit) + 字符串哨兵（合同 M3-A §3 A7）
    // ------------------------------------------------------------------

    @Test
    fun `STR 方法降级为 CALL 加字符串返回位加字符串哨兵`() {
        // A7 之前"本遍不降级"（VM `_RET` 只携 long）的旧锁已反转：#FUNCTIONS 现在与
        // #FUNCTION 同形降级，只差 ① `_CALL` 的 b 置 bit28（STR_RET_FLAG）② 哨兵前缀
        // `#EXPR_RETS#` ③ 被调帧 RETURNF 发 `_RETS`（FuncSig.returnsStr 驱动）。
        //
        // 写法注意（原版语义，不是测试凑数）：STR 目的地的 `=` 赋值右值走
        // `AnalyseFormattedString`（spec: ArgumentParser.cs:1025-1037），所以 `S = STRM()`
        // 是**字面 FORM 串**；字符串方法必须写成 FORM 内嵌 `%STRM()%` 才是表达式调用
        // （同一条原版路径，我们的 ArgBuilders.kt:1344-1357 已逐行复刻）。
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("S = %STRM()%")),
            Fn("STRM", 0, arrayOf("RETURNF \"hello\""), returnsStr = true),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0, "str 方法必须降级为 _CALL（合同 M3-A §3 A7，半成品禁令已解除）")
        val ins = p.instructionAt(call)
        assertEquals(STR_RET_BIT, ins.b, "argc=0 时 b 恰为 STR_RET 位（bit28）")
        assertTrue(!SlotAddr.isNone(ins.c), "c = 调用方帧的匿名字符串返回槽")
        val retSlot = SlotAddr.index(ins.c)
        // 语句池 marker 的右值 = FORM 串（内嵌字符串哨兵），哨兵槽号与 _CALL.c 一致
        val rhs = assertNotNull(rhsOfFirstAssign(p), "赋值右值应入池")
        val sentinel = assertNotNull(
            collectCalls(rhs).singleOrNull { exprStrTempSlotOf(it.name) != null },
            "FORM 内嵌调用应降级为字符串哨兵：$rhs",
        )
        assertEquals(retSlot, exprStrTempSlotOf(sentinel.name), "哨兵读的就是 _CALL 的返回槽")
        assertTrue(sentinel.args.isEmpty(), "哨兵零参")
        assertEquals(OperandType.STR, sentinel.operandType)
        assertTrue(
            p.expressions().none { it is FuncCall && it.name == "STRM" },
            "降级后表达式池不得残留用户方法调用节点",
        )
        // 执行行为锁（值级，与本类 int 锁同口径）：字符串经 _RETS → leaveFrameStr(setStr)
        // → 哨兵读槽 → 赋值进 S。输出通路由 E2E（GOLDEN_PRINT/IO）另行覆盖。
        val h = runProg(p)
        assertEquals("hello", strVar(h, "S"))
    }

    @Test
    fun `STR 方法带实参时字符串实参走 MOV_STR 且返回位照常置`() {
        val p = compileCallers(
            Fn("TEST", 0, arrayOf("S = %STRM(\"x\")%")),
            Fn("STRM", 1, arrayOf("RETURNF ARGS"), returnsStr = true),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0)
        val ins = p.instructionAt(call)
        assertEquals(1 or STR_RET_BIT, ins.b, "argc=1 | STR_RET 位")
        val movStr = allPcs(p, 0) { it.op == Opcode._MOV_STR && SlotAddr.index(it.a) < 1 }
        assertTrue(movStr.isNotEmpty(), "字符串实参必须经 _MOV_STR 进 R0")
        val h = runProg(p)
        assertEquals("x", strVar(h, "S"), "被调帧 ARGS:0 读到 R0 的字符串实参，经 _RETS 原样返回")
    }

    // ------------------------------------------------------------------
    // 锁 6：表不在位 = 旧行为（语料 100% 编译的回归底线）
    // ------------------------------------------------------------------

    @Test
    fun `未挂表时表达式调用原样入池不降级不报错`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse("A = MYFUNC(1)", "A = PLAIN(2)"))
        val p = c.build("TEST")
        assertTrue(find(p) { it.op == Opcode._CALL } < 0)
        val rhs = assertIs<FuncCall>(rhsOfFirstAssign(p))
        assertEquals("MYFUNC", rhs.name, "旧行为：节点原样留给运行期（A4 桥/后续遍）")
    }

    // ------------------------------------------------------------------
    // 锁 7：短路位不前移（AND/OR 右侧、三目分支的调用保持原节点）
    // ------------------------------------------------------------------

    @Test
    fun `短路位调用不被提升到无条件前导`() {
        // 把可能不执行的调用提到条件之前 = 伪造副作用（原版表达式 VM 用 SKIP 保短路）。
        val p = compileCallers(Fn("TEST", 0, arrayOf("B = A && MYFUNC(1)")))
        assertTrue(find(p) { it.op == Opcode._CALL } < 0, "AND 右侧的调用不得发射前导 _CALL")
        val rhs = rhsOfFirstAssign(p)
        assertNotNull(rhs)
        assertTrue(
            collectCalls(rhs).none { it.name.startsWith(EXPR_TEMP_PREFIX) },
            "短路位保持原节点（哨兵只允许出现在严格求值位）",
        )
        assertEquals(1, collectCalls(rhs).count { it.name == "MYFUNC" }, "AND 右侧调用原样保留")
    }

    /** 测试辅助：收集表达式树里的全部 FuncCall。 */
    private fun collectCalls(e: Expr): List<FuncCall> = when (e) {
        is FuncCall -> listOf(e) + e.args.filterNotNull().flatMap { collectCalls(it) }
        is com.eratw.port.core.expr.Binary -> collectCalls(e.left) + collectCalls(e.right)
        is com.eratw.port.core.expr.Ternary -> collectCalls(e.cond) + collectCalls(e.ifTrue) + collectCalls(e.ifFalse)
        is com.eratw.port.core.expr.Unary -> collectCalls(e.operand)
        is FormStringTerm -> e.parts.filterIsInstance<com.eratw.port.core.expr.FormPart.Embedded>()
            .flatMap { collectCalls(it.ast) }
        is com.eratw.port.core.expr.VarRef -> e.indices.flatMap { collectCalls(it) }
        else -> emptyList()
    }
}
