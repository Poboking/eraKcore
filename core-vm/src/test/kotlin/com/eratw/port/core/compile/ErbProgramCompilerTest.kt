package com.eratw.port.core.compile

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Disassembler
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.StatementAliases
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.FakeVariables
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.TestContext
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ErbProgramCompiler 单测 —— 块结构 → _JUMP/_JUMP_IF_*、调用约定（实参暂存/传参）、
 * TRY 系条件发射、TRYC/CATCH 块配对（契约 R3）、FOR 排他界（契约 R4）、
 * 语句池 marker（契约 C2/C3）、标签表导出、事件入口序、编译-反汇编往返。
 *
 * **A3b 按新降级规则如实重写**（旧断言的归宿逐条注明）：
 * - 旧 `_PRINT_LITERAL`/`p.string(a)` 断言：PRINT 家族现**全量入语句池**（契约 C3，
 *   字面量也入池，保证文字流唯一出口），改为 marker 语句池断言（[markerPrintText]）。
 * - 旧 `_SET_INT 全局槽` 断言（具名变量落槽）：契约 C1 废除 —— 具名变量的写只经
 *   语句池 marker（[markerAssignDest]）。
 * - 旧 FOR `_LE/_GE`（含端）断言：契约 R4 废除 —— 改为 `_LT/_GT` 排他 + 行为锁。
 * - 旧 TRYC"不发射=落下一行"：契约 R3 废除 —— CALL/JUMP 系与动态 FORM 缺失发射
 *   `_JUMP → catch 体首 pc`；常量标签缺失的 TRYCGOTO 是例外，原版运行期直接跳过本行
 *   （`JumpTo == null → return`，FunctionIdentifier.cs:3685-3693；PC 基准
 *   pc-golden-root.txt:362 的 NEVER-AFTER-TRYCGOTO）。
 *
 * 断言风格：以"指令模式扫描"为主（找特征指令序列），不锁死具体 pc —— 只锁**结构性**
 * 事实（回边方向、出口位置、比较符选择、传参序列、marker 池条目）。
 */
class ErbProgramCompilerTest {

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("B", OperandType.INT, 1)
            .declareVariable("S", OperandType.STR, 1)
            .declareVariable("RESULT", OperandType.INT, 0)
            .declareFunction("MYFUNC", OperandType.INT)
            // 行首 `MYFUNC(...)` = 用户方法调用（METHOD 语句）；isUserMethod 为真才走 MethodStmt
            // （spec: FunctionIdentifier.cs:4135-4148 + ArgBuilders.kt:326-330）。
            .declareMethod("MYFUNC")
            .declareFunction("STRFUNC", OperandType.STR)
            .declareMethod("STRFUNC")
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

    private fun compileOne(
        vararg lines: String,
        known: Set<String> = emptySet(),
        isMethod: Boolean = false,
    ): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST", isMethod = isMethod), parse(*lines), known)
        return c.build()
    }

    private fun compileFuncs(vararg funcs: Pair<String, Array<String>>): Program {
        val c = ErbProgramCompiler()
        val names = funcs.map { it.first }.toSet()
        for ((name, lines) in funcs) c.compileFunction(FuncSig(name), parse(*lines), names)
        return c.build(funcs.first().first)
    }

    private fun indexOfFunc(p: Program, name: String) = p.functions.indexOfFirst { it.name == name }

    /** 找第一条满足条件的指令 pc；-1 = 不存在。 */
    private fun find(p: Program, from: Int = 0, pred: (Instruction) -> Boolean): Int {
        for (pc in from until p.instructionCount) {
            if (pred(p.instructionAt(pc))) return pc
        }
        return -1
    }

    private fun allPcs(p: Program, pred: (Instruction) -> Boolean): List<Int> =
        (0 until p.instructionCount).filter { pred(p.instructionAt(it)) }

    private fun jumpTarget(p: Program, pc: Int, operand: Int): Int {
        val ins = p.instructionAt(pc)
        return if (operand == 1) pc + 1 + ins.a else pc + 1 + ins.b
    }

    // ---- 语句池 marker 断言辅助（契约 C2/C3；替代旧 _PRINT_LITERAL 形态断言） ----

    private val printlOp: Int = StatementAliases.resolve("PRINTL")
        ?: error("StatementAliases 缺 PRINTL 别名（OpcodeTable.kt:937）")

    private fun markerAt(p: Program, pc: Int): Statement? {
        val ins = p.instructionAt(pc)
        return if (CorpusCompileValidator.isStatementMarker(ins.op)) p.statement(ins.a) else null
    }

    /** 该 pc 若是 PRINTL marker → 返回其字面文本（StrLiteral/IntLiteral；VarRef 退化为名字）。 */
    private fun markerPrintText(p: Program, pc: Int): String? {
        val ins = p.instructionAt(pc)
        if (ins.op != printlOp) return null
        val st = p.statement(ins.a) as? PrintStmt ?: return null
        return when (val t = (st.args as? ExpressionArgument)?.term) {
            is StrLiteral -> t.value
            is IntLiteral -> t.value.toString()
            is VarRef -> t.name
            else -> null
        }
    }

    private fun findPrint(p: Program, from: Int = 0, text: String? = null): Int {
        for (pc in from until p.instructionCount) {
            val t = markerPrintText(p, pc) ?: continue
            if (text == null || t == text) return pc
        }
        return -1
    }

    private fun markerAssignDest(p: Program, pc: Int): String? =
        (markerAt(p, pc) as? AssignStmt)?.dest?.name

    // ------------------------------------------------------------------
    // IF 家族
    // ------------------------------------------------------------------

    @Test
    fun `if else 条件跳转与出口回填`() {
        val p = compileOne("IF 0", "PRINTL T", "ELSE", "PRINTL F", "ENDIF", "PRINTL END")
        val jif = find(p) { it.op == Opcode._JUMP_IF_FALSE }
        val elseBody = jumpTarget(p, jif, 2)
        assertEquals("F", markerPrintText(p, elseBody), "条件假 → ELSE 体（PRINTL 现为语句池 marker）")
        // IF 体尾必须有无条件跳转越过 ELSE 体
        val ifBodyExit = jif + 1                          // 条件、跳转之后 = IF 体首
        assertEquals("T", markerPrintText(p, ifBodyExit))
        val skip = find(p, from = ifBodyExit + 1) { it.op == Opcode._JUMP && it.a != 0 }
        val endifAfter = jumpTarget(p, skip, 1)
        assertEquals("END", markerPrintText(p, endifAfter), "分支体尾 → ENDIF 之后")
    }

    @Test
    fun `elseif 链条件串联`() {
        val p = compileOne("IF 0", "PRINTL A", "ELSEIF 0", "PRINTL B", "ELSE", "PRINTL C", "ENDIF", "PRINTL E")
        val jif1 = find(p) { it.op == Opcode._JUMP_IF_FALSE }
        val jif2 = find(p, from = jif1 + 1) { it.op == Opcode._JUMP_IF_FALSE }
        assertEquals(2, allPcs(p) { it.op == Opcode._JUMP_IF_FALSE }.size, "两个分支条件各一个假跳")
        // ELSEIF 条件失败 → ELSE 体
        val elseBody = jumpTarget(p, jif2, 2)
        assertEquals("C", markerPrintText(p, elseBody))
        // 分支体尾跳转全部指向 ENDIF 之后
        val exits = allPcs(p) { it.op == Opcode._JUMP && it.a > 0 }
        assertTrue(exits.size >= 2, "IF 体尾与 ELSEIF 体尾各需一个出口跳转")
        val endifAfter = jumpTarget(p, exits.first(), 1)
        assertEquals("E", markerPrintText(p, endifAfter))
        for (pc in exits) assertEquals(endifAfter, jumpTarget(p, pc, 1), "所有出口汇聚到同一点")
    }

    @Test
    fun `sif 跳过紧随单行`() {
        val p = compileOne("SIF 0", "PRINTL X", "PRINTL Y")
        val jif = find(p) { it.op == Opcode._JUMP_IF_FALSE }
        val skipTo = jumpTarget(p, jif, 2)
        assertEquals("Y", markerPrintText(p, skipTo), "SIF 假 → 跳过紧随一条")
    }

    @Test
    fun `SIF 后随块分支与结束语句装载期报错`() {
        // spec: isa.yaml:1637（后随行不可作 SIF 目标时解析报错）+
        // FunctionIdentifier.cs:3044-3056（IsPartial → FuncCanNotAfterSif）。
        // 旧实现只挡 BlockRole.OPEN，ELSE/CASE/ENDIF 会落进分支体（`SIF 0 / ELSE / …` 执行 ELSE 体）。
        val bad = listOf(
            // 任务书原始形状：SIF 假 → 旧实现落进 ELSE 体（ELSE 绑到外层 IF 上）
            arrayOf("SIF 0", "ELSE", "PRINTL BAD", "ENDIF"),
            arrayOf("IF 1", "SIF 0", "ELSE", "PRINTL BAD", "ENDIF"),
            arrayOf("SELECTCASE 1", "SIF 0", "CASE 1", "PRINTL BAD", "ENDSELECT"),
            arrayOf("IF 1", "SIF 0", "ELSEIF 1", "PRINTL BAD", "ENDIF"),
            arrayOf("IF 1", "SIF 0", "ENDIF", "PRINTL BAD"),
            arrayOf("SIF 0", "SIF 1", "PRINTL BAD"),
        )
        for (lines in bad) {
            val e = assertFailsWith<CompileException>("${lines.toList()} 必须装载期报错") {
                compileOne(*lines)
            }
            assertTrue(
                "SIF 之后不能是" in (e.message ?: ""),
                "报错文案应指明 SIF 目标行非法：${e.message}",
            )
        }
    }

    // ------------------------------------------------------------------
    // SELECTCASE
    // ------------------------------------------------------------------

    @Test
    fun `selectCase 普通匹配用 EQ`() {
        val p = compileOne("SELECTCASE 1", "CASE 1", "PRINTL ONE", "ENDSELECT", "PRINTL OUT")
        val eq = find(p) { it.op == Opcode._EQ }
        assertTrue(eq >= 0, "整数 CASE 需要 _EQ 比较")
        val hit = find(p, from = eq) { it.op == Opcode._JUMP_IF_TRUE }
        val body = jumpTarget(p, hit, 2)
        assertEquals("ONE", markerPrintText(p, body), "命中 → CASE 体首")
    }

    @Test
    fun `selectCase TO 范围用 GE 与 LE`() {
        val p = compileOne("SELECTCASE 2", "CASE 1 TO 3", "PRINTL IN", "ENDSELECT", "PRINTL OUT")
        val ge = find(p) { it.op == Opcode._GE }
        val le = find(p, from = ge) { it.op == Opcode._LE }
        assertTrue(ge >= 0 && le > ge, "CASE TO 需要 _GE（下界）在前、_LE（上界）在后（CASE 匹配含端，与 FOR 的 R4 排他界无关）")
        val fail = find(p, from = ge) { it.op == Opcode._JUMP_IF_FALSE }
        val hit = find(p, from = le) { it.op == Opcode._JUMP_IF_TRUE }
        val body = jumpTarget(p, hit, 2)
        assertEquals("IN", markerPrintText(p, body))
        assertTrue(jumpTarget(p, fail, 2) > body, "下界失配 → 跳过命中链")
    }

    @Test
    fun `selectCase TO 越上界的值不命中`() {
        // 回归（bootSmoke qol_update.ERB:305 实测）：上界比较只发射 true 分支时，
        // `CASE 1 TO 500` 退化成「只判下界」，1100 会落进 CASE 体。
        val p = compileOne(
            "SELECTCASE 1100", "CASE 1 TO 500", "PRINTL IN", "ENDSELECT", "PRINTL OUT",
        )
        val s = runProgram(p, "TEST").out.toString()
        assertTrue("IN" !in s && "OUT" in s, "上界失配必须跳过 CASE 体（实际输出：$s）")
    }

    @Test
    fun `selectCase IS 用指定比较符`() {
        val p = compileOne("SELECTCASE 7", "CASE IS > 5", "PRINTL BIG", "ENDSELECT")
        assertTrue(find(p) { it.op == Opcode._GT } >= 0, "CASE IS > 必须用 _GT")
    }

    @Test
    fun `selectCase 无 CaseElse 未命中直落出口`() {
        val p = compileOne("SELECTCASE 9", "CASE 1", "PRINTL ONE", "ENDSELECT", "PRINTL OUT")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("ONE" !in s && "OUT" in s, "未命中必须直落 ENDSELECT 之后（实际：$s）")
    }

    @Test
    fun `多值 CASE 逐值试配 命中第二个值`() {
        // spec: FunctionIdentifier.cs:3185-3193 —— 行内 caseExps 逐个 GetBool，任一命中即进体。
        // 旧实现把每个 caseExp 的失配都塞进跨 CASE 链 ⇒ 首值失配即跳到下一 CASE，
        // `CASE 1, 2` 的第二值起是死代码（等价 CASE 1）。
        val p = compileOne("SELECTCASE 2", "CASE 1, 2", "PRINTL HIT", "ENDSELECT", "PRINTL OUT")
        assertTrue(allPcs(p) { it.op == Opcode._EQ }.size >= 2, "两个值各需一次 _EQ（第二个值不得被跳过）")
        val s = runProgram(p, "TEST").out.toString()
        assertTrue("HIT" in s && "OUT" in s, "CASE 1, 2 在 SELECTCASE 2 下必须命中第二值（实际：$s）")
    }

    @Test
    fun `多值 CASE 全不命中落 CASEELSE`() {
        val p = compileOne(
            "SELECTCASE 3", "CASE 1, 2", "PRINTL HIT", "CASEELSE", "PRINTL MISS", "ENDSELECT",
        )
        val s = runProgram(p, "TEST").out.toString()
        assertTrue("MISS" in s && "HIT" !in s, "两个值都失配 → CASEELSE 体（实际：$s）")
    }

    @Test
    fun `多值 CASE 的 TO 形态逐值试配`() {
        // 旧实现：第一个 TO 的下界失配即跳出整条 CASE 链，`4 TO 5` 永远读不到。
        val p = compileOne("SELECTCASE 4", "CASE 1 TO 2, 4 TO 5", "PRINTL RANGE", "ENDSELECT", "PRINTL OUT")
        assertTrue(allPcs(p) { it.op == Opcode._GE }.size >= 2, "两个 TO 值各需一次下界 _GE")
        val s = runProgram(p, "TEST").out.toString()
        assertTrue("RANGE" in s && "OUT" in s, "CASE 1 TO 2, 4 TO 5 在 4 下必须命中第二段（实际：$s）")
    }

    @Test
    fun `多值 CASE 的 IS 形态逐值试配`() {
        val p = compileOne("SELECTCASE 7", "CASE IS > 100, IS >= 7", "PRINTL ISHIT", "ENDSELECT")
        val s = runProgram(p, "TEST").out.toString()
        assertTrue("ISHIT" in s, "CASE IS > 100, IS >= 7 在 7 下必须命中第二个 IS（实际：$s）")
    }

    // ------------------------------------------------------------------
    // 循环（契约 R4：FOR 界排他，spec: FunctionIdentifier.cs:3401-3403/3006/3003-3005）
    // ------------------------------------------------------------------

    @Test
    fun `for 正向回边与出口判定`() {
        val p = compileOne("FOR A, 0, 2", "PRINTL L", "NEXT", "PRINTL D")
        val cmp = find(p) { it.op == Opcode._LT }
        assertTrue(cmp >= 0, "R4：正步长用 _LT（cnt < end，排他）；旧含端 _LE 锁定的行为已被契约废除")
        val exit = find(p, from = cmp) { it.op == Opcode._JUMP_IF_FALSE }
        val back = find(p, from = exit) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(back > exit, "NEXT 处存在回边")
        // 回边 → 判定区首（_EVAL_INT 重读循环变量），即 _LT 前一条；不是 _LT 本身
        assertEquals(cmp - 1, jumpTarget(p, back, 1), "回边 → 循环头判定区（_EVAL_INT→_LT→_JUMP_IF_FALSE）")
        val afterExit = jumpTarget(p, exit, 2)
        assertTrue(afterExit > back, "出口在循环尾之后")
        assertEquals("D", markerPrintText(p, afterExit))
    }

    @Test
    fun `for 负步长用 GT 判定`() {
        val p = compileOne("FOR A, 2, 0, -1", "PRINTL L", "NEXT", "PRINTL D")
        val ge = find(p) { it.op == Opcode._GT }
        assertTrue(ge >= 0, "R4：负步长必须用 _GT（cnt > end，排他；-1 折叠为常量步长）")
        assertTrue(find(p) { it.op == Opcode._GE } < 0, "旧含端 _GE 形态不得复活")
    }

    @Test
    fun `for 终值只求值一次且留在匿名临时槽`() {
        // 回归（旧 bug：步长复用终值槽破坏比较基准）+ R4/C1：初始化/步进走语句池，
        // **终值留在 VM 匿名临时槽**逐轮读——旧断言"步长 _SET_INT 与终值 _SET_INT 分槽"
        // 的形态已废（步长不再发射 _SET_INT），改为锁"循环区内无人重写终值槽"。
        val p = compileOne("FOR A, 0, 2", "PRINTL L", "NEXT", "PRINTL D")
        val set2 = find(p) { it.op == Opcode._SET_INT && p.constant(it.b) == 2L }
        assertTrue(set2 >= 0, "终值 2 求值一次到帧内临时槽（spec: FunctionIdentifier.cs:3004）")
        val endSlot = p.instructionAt(set2).a
        val cmp = find(p) { it.op == Opcode._LT }
        assertTrue(cmp > set2)
        assertEquals(endSlot, p.instructionAt(cmp).c, "排他比较每轮读同一终值槽")
        val back = find(p, from = cmp) { it.op == Opcode._JUMP && it.a != 0 }
        for (pc in set2 + 1..back) {
            val ins = p.instructionAt(pc)
            when (ins.op) {
                Opcode._SET_INT, Opcode._SET_STR, Opcode._MOV_INT, Opcode._MOV_STR ->
                    assertTrue(ins.a != endSlot, "pc$pc 不得重写终值槽")
                Opcode._EVAL_INT, Opcode._EVAL_STR ->
                    assertTrue(ins.b != endSlot, "pc$pc 不得复用终值槽")
                else -> {}
            }
        }
    }

    @Test
    fun `for 初始化与步进走语句池而非 VM 槽`() {
        // 契约 C1/C3：`cnt = start` / `cnt += step` 是合成 AssignStmt marker；
        // 旧形态（varSlot → _SET_INT/_ADD 到具名槽）已被废除。
        val p = compileOne("FOR A, 0, 2", "PRINTL L", "NEXT")
        val inits = (0 until p.instructionCount).filter { markerAssignDest(p, it) == "A" }
        assertTrue(inits.size >= 2, "init（A=0）与步进（A+=1）各至少一条 pool marker，实际 $inits")
        val initStmt = p.statement(p.instructionAt(inits.first()).a) as AssignStmt
        assertEquals(OperatorCode.ASSIGNMENT, initStmt.op, "首条即初始化 `A = start`")
    }

    @Test
    fun `repeat 计数回边且上界不减一`() {
        val p = compileOne("REPEAT 3", "PRINTL R", "REND", "PRINTL E")
        // R4：REPEAT n → FOR COUNT, 0, n, 1（**不减一**，spec: ArgumentParser.cs:1223-1228）；
        // 旧 -1 补偿（上界 2）随含端判定一并撤销 —— 这里锁终值就是 3。
        val cmp = find(p) { it.op == Opcode._LT }
        assertTrue(cmp >= 0, "REPEAT 改写后按 FOR 排他界编译")
        assertTrue(find(p) { it.op == Opcode._SET_INT && p.constant(it.b) == 3L } >= 0, "终值 = n（3）")
        assertTrue(find(p) { it.op == Opcode._SET_INT && p.constant(it.b) == 2L } < 0, "不得出现 n-1 补偿")
        val back = find(p, from = cmp) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(back > cmp)
        assertEquals(cmp - 1, jumpTarget(p, back, 1), "回边 → 判定区首（_EVAL_INT）")
    }

    @Test
    fun `while 回边与出口`() {
        val p = compileOne("WHILE 0", "PRINTL X", "WEND", "PRINTL Y")
        val exit = find(p) { it.op == Opcode._JUMP_IF_FALSE }
        val after = jumpTarget(p, exit, 2)
        assertEquals("Y", markerPrintText(p, after), "条件假 → WEND 之后")
        // WEND 处回边到 WHILE 条件判定
        val back = find(p, from = exit + 1) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(back > exit)
        assertTrue(jumpTarget(p, back, 1) < exit, "回边方向朝前")
    }

    @Test
    fun `do loop 条件回边`() {
        val p = compileOne("DO", "PRINTL X", "LOOP 0")
        val jit = find(p) { it.op == Opcode._JUMP_IF_TRUE }
        assertTrue(jit >= 0, "LOOP 条件用 _JUMP_IF_TRUE 回边")
        val target = jumpTarget(p, jit, 2)
        assertTrue(target < jit, "回边目标在前面（DO 头）")
    }

    @Test
    fun `break 跳出最内层循环`() {
        val p = compileOne("FOR A, 0, 1", "FOR B, 0, 1", "BREAK", "NEXT", "PRINTL I", "NEXT", "PRINTL O")
        val brk = find(p) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(brk >= 0, "BREAK 产生跳转")
        val target = jumpTarget(p, brk, 1)
        assertEquals("I", markerPrintText(p, target), "内层 BREAK → 内层循环出口（外层体尾）")
    }

    @Test
    fun `循环外 CONTINUE 是行级错误_编译为 THROW marker`() {
        // 原版装载期只把该行标为 IsError，装载照常完成，执行到才 throw CodeEE
        // （spec: ErbLoader.cs:1198-1205；ParserMediator.cs:127-131；Process.cs:1017-1019）。
        val p = compileOne("PRINTL A", "CONTINUE", "PRINTL B")
        val pc = find(p) { it.op == Opcode.THROW }
        assertTrue(pc >= 0, "编译不失败，且原位发出 THROW marker")
        val stmt = p.statement(p.instructionAt(pc).a) as InstructionStmt
        assertEquals(FunctionCode.THROW, stmt.code)
        assertEquals(
            "在REPEAT, FOR, WHILE, DO语法以外使用了\"CONTINUE\"",
            (stmt.args as ExpressionArgument).term?.let { (it as StrLiteral).value },
        )
    }

    @Test
    fun `循环外 BREAK 是行级错误_编译为 THROW marker`() {
        val p = compileOne("PRINTL A", "BREAK", "PRINTL B")
        val pc = find(p) { it.op == Opcode.THROW }
        assertTrue(pc >= 0, "编译不失败，且原位发出 THROW marker")
        val stmt = p.statement(p.instructionAt(pc).a) as InstructionStmt
        assertEquals(FunctionCode.THROW, stmt.code)
        assertTrue(
            (stmt.args as ExpressionArgument).term.let { (it as StrLiteral).value.contains("BREAK") },
        )
    }

    @Test
    fun `continue 回到步进区`() {
        val p = compileOne("FOR A, 0, 2", "CONTINUE", "NEXT", "PRINTL D")
        val jif = find(p) { it.op == Opcode._JUMP_IF_FALSE }
        val cont = find(p, from = jif + 1) { it.op == Opcode._JUMP }
        assertTrue(cont >= 0, "CONTINUE 产生跳转")
        val target = jumpTarget(p, cont, 1)
        // 契约 C1/C3：步进是语句池 marker（合成 `A += step` AssignStmt），旧 `_ADD` VM 指令形态已废
        val step = markerAt(p, target) as? AssignStmt
        assertTrue(
            step != null && step.op == OperatorCode.PLUS && step.dest.name == "A",
            "CONTINUE → FOR 步进区（语句池 marker `A += step`）",
        )
        val back = target + 1
        assertEquals(Opcode._JUMP, p.instructionAt(back).op)
        assertTrue(jumpTarget(p, back, 1) <= jif, "NEXT 回边到循环头（判定之前）")
    }

    // ------------------------------------------------------------------
    // 调用约定
    // ------------------------------------------------------------------

    @Test
    fun `call 实参暂存与 mov 传参`() {
        val p = compileFuncs("TEST" to arrayOf("CALL F, 1, 2", "PRINTL E"), "F" to arrayOf("PRINTL F"))
        val fIdx = indexOfFunc(p, "F")
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0)
        assertEquals(fIdx, p.instructionAt(call).a, "_CALL 已回填函数表下标")
        assertEquals(2, p.instructionAt(call).b, "b = 实参数")
        // 实参先暂存（≥ maxArgc 槽），再 _MOV 进 R0/R1
        val mov0 = find(p, from = 0) { it.op == Opcode._MOV_INT && SlotAddr.index(it.a) == 0 }
        val mov1 = find(p, from = mov0) { it.op == Opcode._MOV_INT && SlotAddr.index(it.a) == 1 }
        assertTrue(mov0 in 0 until call && mov1 in 0 until call, "传参序列在 _CALL 之前")
        val set1 = find(p) { it.op == Opcode._SET_INT && p.constant(it.b) == 1L }
        val set2 = find(p, from = set1) { it.op == Opcode._SET_INT && p.constant(it.b) == 2L }
        assertTrue(SlotAddr.index(p.instructionAt(set1).a) >= 2, "暂存槽不得占用 R0..R(maxArgc-1) 实参区")
        assertTrue(SlotAddr.index(p.instructionAt(set2).a) >= 2)
        assertTrue(set1 < mov0 && set2 < mov1, "先求值后搬运")
    }

    @Test
    fun `行首内建方法是 METHOD marker 而非用户函数 CALL`() {
        // spec: FunctionIdentifier.cs:4135-4142 + METHOD_Instruction:584-604 ——
        // 内建方法被注册为语句，执行后写 RESULT；编成 _CALL 会在链接期报"未定义的函数"
        // （语料 BATTLE_ERB 的裸 GETMILLISECOND 行即此）。
        val p = compileOne("GETMILLISECOND")
        assertEquals(-1, find(p) { it.op == Opcode._CALL }, "内建方法不得发 _CALL")
        val pc = find(p) { it.op == Opcode.METHOD }
        assertTrue(pc >= 0, "应发出 METHOD marker")
        val stmt = markerAt(p, pc) as MethodStmt
        assertEquals("GETMILLISECOND", stmt.methodName)
        assertEquals(OperandType.INT, (stmt.args as MethodArgument).methodTerm?.operandType)
    }

    @Test
    fun `行首用户方法调用按实参发射并写 RESULT`() {
        // spec: FunctionIdentifier.cs:592-603 —— METHOD_Instruction 求值整个 methodTerm
        // （实参升序、建帧、取返回值）后按返回类型写 RESULT / RESULTS。
        // 旧实现对行首用户方法只发 `_CALL 名, 0`：丢实参、不登记 callArgs、结果丢弃。
        val p = compileFuncs(
            "TEST" to arrayOf("MYFUNC 1, 2", "PRINTL E"),
            "MYFUNC" to arrayOf("RETURN 7"),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0, "行首用户方法必须发 _CALL（只有内建方法才走 METHOD marker）")
        assertEquals(2, p.instructionAt(call).b, "b = 实参数（旧实现恒 0）")
        val meta = p.callArgsAt(call)
        assertEquals(listOf(0, 1), meta?.map { it.position } ?: emptyList(), "实参逐位登记进 callArgs")
        assertEquals(listOf(false, false), meta?.map { it.isStr } ?: emptyList(), "两个实参都是整型位")
        // 实参先求值到暂存槽，再按桶序 _MOV 进 R0/R1
        val movs = (0 until call)
            .filter { p.instructionAt(it).op == Opcode._MOV_INT }
            .map { SlotAddr.index(p.instructionAt(it).a) }
        assertEquals(listOf(0, 1), movs, "传参序列在 _CALL 之前且按桶序")
        // 返回后写 RESULT：合成赋值 `RESULT = #EXPR_RET#<c>`（c = _CALL 的返回槽）
        val mark = (call until p.instructionCount).firstOrNull { markerAssignDest(p, it) == "RESULT" } ?: -1
        assertTrue(mark > call, "调用返回后必须写 RESULT（spec: FunctionIdentifier.cs:593-596）")
        val stmt = p.statement(p.instructionAt(mark).a) as AssignStmt
        val rhs = (stmt.args as SpSetArgument).term as FuncCall
        assertEquals(
            SlotAddr.index(p.instructionAt(call).c), exprTempSlotOf(rhs.name),
            "RESULT 赋值经哨兵读的就是 _CALL 的返回槽",
        )
    }

    @Test
    fun `行首字符串方法调用写 RESULTS`() {
        // spec: FunctionIdentifier.cs:597-601 —— 非整型 methodTerm 写 RESULTS；
        // 调用位另置 b bit28（STR_RET_FLAG，合同 M3-A §3 A7）走 `_RETS` 字符串通道。
        val c = ErbProgramCompiler()
        val names = setOf("TEST", "STRFUNC")
        c.compileFunction(FuncSig("TEST"), parse("STRFUNC 1, 2", "PRINTL E"), names)
        c.compileFunction(FuncSig("STRFUNC", isMethod = true, returnsStr = true), parse("RETURNF \"X\""), names)
        val p = c.build("TEST")
        val call = find(p) { it.op == Opcode._CALL }
        assertTrue(call >= 0, "行首字符串方法同样走 _CALL")
        assertEquals(2, p.instructionAt(call).b and 0x0FFFFFFF, "b 低 28 位 = 实参数")
        assertTrue((p.instructionAt(call).b and 0x10000000) != 0, "str 返回方法必须置 STR_RET_FLAG（bit28）")
        val mark = (call until p.instructionCount).firstOrNull { markerAssignDest(p, it) == "RESULTS" } ?: -1
        assertTrue(mark > call, "字符串返回必须写 RESULTS（spec: FunctionIdentifier.cs:597-601）")
        val stmt = p.statement(p.instructionAt(mark).a) as AssignStmt
        val rhs = (stmt.args as SpSetArgument).term as FuncCall
        assertTrue(rhs.name.startsWith(EXPR_STR_TEMP_PREFIX), "RESULTS 赋值走字符串哨兵，实际 ${rhs.name}")
        assertEquals(SlotAddr.index(p.instructionAt(call).c), exprStrTempSlotOf(rhs.name), "哨兵槽 = 返回槽")
    }

    @Test
    fun `嵌套调用实参暂存区隔离`() {
        // CALL F, MYFUNC(1), 2 —— 实参表 [MYFUNC(1), 2]，函数名不算实参
        val p = compileFuncs(
            "TEST" to arrayOf("CALL F, MYFUNC(1), 2", "PRINTL E"),
            "F" to arrayOf("PRINTL F"),
        )
        val call = find(p) { it.op == Opcode._CALL }
        assertEquals(2, p.instructionAt(call).b, "2 个实参 → b=2")
        // _EVAL_INT 求值 MYFUNC(1)；结果槽 ≥ maxArgc=2
        val eval = find(p) { it.op == Opcode._EVAL_INT }
        assertTrue(eval >= 0, "函数调用实参走 _EVAL_INT")
        assertTrue(SlotAddr.index(p.instructionAt(eval).b) >= 2, "暂存槽 ≥ maxArgc")
        // 全部 _MOV 顺序覆盖 R0..R1
        val movs = allPcs(p) { it.op == Opcode._MOV_INT }
        assertEquals(listOf(0, 1), movs.map { SlotAddr.index(p.instructionAt(it).a) }, "按序写入 R0..R1")
    }

    @Test
    fun `slotCount 取临时槽高水位`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("T", 2), parse("CALL F, 1, 2, 3, 4", "PRINTL X"), knownFunctions = setOf("F"))
        c.compileFunction(FuncSig("F"), parse("PRINTL F"))
        c.build("T")
        // maxArgc=4 → 临时槽从 4 起 + 若干临时 → slotCount > 4
        assertTrue(fb.slotCount >= 5, "slotCount=${fb.slotCount}")
    }

    @Test
    fun `trycall 目标未知不发射`() {
        val p = compileOne("TRYCALL F", "PRINTL X", known = emptySet())
        assertTrue(find(p) { it.op == Opcode._CALL } < 0, "未知目标不得发射 _CALL")
    }

    @Test
    fun `trycall 目标已知正常发射`() {
        val p = compileFuncs("TEST" to arrayOf("TRYCALL F", "PRINTL X"), "F" to arrayOf("PRINTL F"))
        assertTrue(find(p) { it.op == Opcode._CALL } >= 0, "已知目标必须发射 _CALL")
    }

    @Test
    fun `trygoto 标签缺失不发射`() {
        val p = compileOne("TRYGOTO NOPE", "PRINTL X")
        val jif = find(p) { it.op == Opcode._JUMP }
        assertTrue(jif < 0, "标签缺失的 TRYGOTO 不得发射 _JUMP")
    }

    // ------------------------------------------------------------------
    // TRYC/CATCH 块配对（契约 R3：TRYC 语句自身是 try 块头；缺失 → catch 体首 pc）
    // ------------------------------------------------------------------

    @Test
    fun `tryc 目标缺失跳 catch 体首行而非下一条`() {
        val p = compileOne(
            "TRYCCALL GHOST", "PRINTL TRYBODY", "CATCH", "PRINTL INCATCH", "ENDCATCH", "PRINTL AFTER",
        )
        assertTrue(find(p) { it.op == Opcode._CALL } < 0, "缺失目标不发射调用")
        val miss = find(p) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(miss >= 0, "缺失 → 一条 _JUMP（旧'什么都不发射=落下一行'的形态已被 R3 废除）")
        assertEquals("INCATCH", markerPrintText(p, jumpTarget(p, miss, 1)), "跳到 catch 体首 pc（R1 换算口径）")
        val end = find(p, from = miss + 1) { it.op == Opcode._JUMP && it.a != 0 }
        assertEquals("AFTER", markerPrintText(p, jumpTarget(p, end, 1)), "try 体尾跳过 catch 体 → ENDCATCH 之后")
        // CATCH/ENDCATCH 是块结构：不得再有它们的语句池 marker
        assertTrue(
            (0 until p.instructionCount).none { markerAt(p, it)?.code?.name in setOf("CATCH", "ENDCATCH") },
            "CATCH/ENDCATCH 不入语句池",
        )
    }

    @Test
    fun `tryc 空 try 体 缺失直落 catch 体`() {
        val p = compileOne("TRYCCALL GHOST", "CATCH", "PRINTL IN", "ENDCATCH", "PRINTL OUT")
        val miss = find(p) { it.op == Opcode._JUMP && it.a != 0 }
        assertTrue(miss >= 0)
        assertEquals("IN", markerPrintText(p, jumpTarget(p, miss, 1)), "miss → CATCH 行编译出的第一条指令之后 = catch 体首")
        val end = find(p, from = miss + 1) { it.op == Opcode._JUMP && it.a != 0 }
        assertEquals("OUT", markerPrintText(p, jumpTarget(p, end, 1)))
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("IN" in s && "OUT" in s, "空 try 体运行期净效果（实际：$s）")
    }

    @Test
    fun `tryc 目标已知走 try 体并跳过 catch 体`() {
        val p = compileFuncs(
            "TEST" to arrayOf("TRYCCALL F", "PRINTL OK", "CATCH", "PRINTL BAD", "ENDCATCH", "PRINTL TAIL"),
            "F" to arrayOf("PRINTL CALLED"),
        )
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("CALLED" in s && "OK" in s && "BAD" !in s && "TAIL" in s, "命中 → try 体 → 跳过 catch（实际：$s）")
    }

    @Test
    fun `tryccallform 两步式发射形态`() {
        // 契约 R3：_EVAL_STR/_SET_STR → nameSlot；_CALL_BY_NAME (argc|bit31, c=hitFlag)；
        // _JUMP_IF_TRUE hitFlag → try 体首（out.here() 换算，R1 禁硬编码 +1）；_JUMP → catch 体首。
        val p = compileOne(
            "TRYCCALLFORM \"GHOST\"", "PRINTL BODY", "CATCH", "PRINTL HURT", "ENDCATCH", "PRINTL OUT",
        )
        val cbn = find(p) { it.op == Opcode._CALL_BY_NAME }
        assertTrue(cbn >= 0)
        val ins = p.instructionAt(cbn)
        assertTrue(ins.b and Int.MIN_VALUE != 0, "b bit31 = try 位（契约 C5/R3）")
        assertTrue(!SlotAddr.isNone(ins.c), "c = 命中标志槽（NONE = 丢弃，那是纯 TRY 的编码）")
        assertEquals(Opcode._JUMP_IF_TRUE, p.instructionAt(cbn + 1).op, "紧随命中判定")
        assertEquals(ins.c, p.instructionAt(cbn + 1).a, "判定读同一标志槽")
        assertEquals(cbn + 3, jumpTarget(p, cbn + 1, 2), "命中 → _JUMP 之后那条 = try 体首（R1 换算，非硬编码）")
        assertEquals(Opcode._JUMP, p.instructionAt(cbn + 2).op)
        assertEquals("HURT", markerPrintText(p, jumpTarget(p, cbn + 2, 1)), "缺失 → catch 体首 pc")
    }

    @Test
    fun `tryccallform 运行期缺失落入 catch 体`() {
        val p = compileOne(
            "TRYCCALLFORM \"GHOST\"", "PRINTL BODY", "CATCH", "PRINTL HURT", "ENDCATCH", "PRINTL OUT",
        )
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("HURT" in s && "OUT" in s && "BODY" !in s, "实际：$s")
        // TODO(verify) A2：**成功路径**的运行期锁用例要等 Vm.kt `_CALL_BY_NAME` 补命中标志
        //（现 :418-426：命中时 c 被被调 _RET 结果覆盖、未命中+try 不写 0）——补丁前命中也会
        // 落入 catch 体，锁"命中成功"会红。三行补丁建议见交回。静态 TRYCCALL 的命中路径
        //（tryc 目标已知走 try 体）不受此影响，已单独锁死。
    }

    @Test
    fun `tryc 块缺 ENDCATCH 报未闭合`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("T"), parse("TRYCCALL G", "CATCH", "PRINTL X"))
        }
        assertTrue(e.message!!.contains("未闭合"), e.message ?: "")
    }

    @Test
    fun `catch 无配对 TRYC 报错`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("T"), parse("PRINTL A", "CATCH", "PRINTL B", "ENDCATCH"))
        }
        assertTrue(e.message!!.contains("不匹配"), e.message ?: "")
    }

    @Test
    fun `常量标签缺失的 trycgoto 跳过本行且仍与自己的 CATCH 配对`() {
        // 原版：常量标签缺失 → 运行期 `JumpTo == null → return`（FunctionIdentifier.cs:3685-3693），
        // 不跳 catch；PC 基准 fixtures/golden/baseline/pc-golden-root.txt:362 的
        // NEVER-AFTER-TRYCGOTO 即此行为。六种 TRYC 各自开块（ErbLoader.cs:1169-1178/1339-1348）。
        val p = compileFuncs(
            "TEST" to arrayOf(
                "TRYCCALL HARNESS", "TRYCGOTO NOLAB", "PRINTL NEVER",
                "CATCH", "PRINTL CAUGHT-INNER", "ENDCATCH",
                "CATCH", "PRINTL CAUGHT-OUTER", "ENDCATCH",
                "PRINTL AFTER",
            ),
            "HARNESS" to arrayOf("RETURN"),
        )
        val h = runProgram(p, "TEST")
        assertEquals("NEVER\nAFTER\n", h.out.toString(), "TRYCGOTO 缺失 = 跳过；两个 CATCH 体都不执行")
    }

    @Test
    fun `动态标签缺失的 trycgotoform 落入自己的 catch 体`() {
        // 原版：动态目标缺失 + isTry + JumpToEndCatch → 跳 catch 体首（FunctionIdentifier.cs:3699-3709）。
        val p = compileFuncs(
            "TEST" to arrayOf(
                "TRYCCALL HARNESS", "TRYCGOTOFORM \"NOLAB\"", "PRINTL NEVER",
                "CATCH", "PRINTL CAUGHT-INNER", "ENDCATCH",
                "CATCH", "PRINTL CAUGHT-OUTER", "ENDCATCH",
                "PRINTL AFTER",
            ),
            "HARNESS" to arrayOf("RETURN"),
        )
        val h = runProgram(p, "TEST")
        assertEquals("CAUGHT-INNER\nAFTER\n", h.out.toString(), "miss → 内层 catch 体；外层 try 体尾的 CATCH 跳越外层体")
    }

    @Test
    fun `孤立 trycgoto 缺 CATCH 报块未闭合`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("T"), parse("TRYCGOTO NOPE", "PRINTL X"), knownFunctions = setOf("T"))
        }
        assertTrue(e.message!!.contains("未闭合"), e.message ?: "")
    }

    // ------------------------------------------------------------------
    // GOTO / 标签表 / FORM 跳转
    // ------------------------------------------------------------------

    @Test
    fun `goto 前向引用回填`() {
        val p = compileOne("GOTO TAIL", "PRINTL SKIPPED", "\$TAIL", "PRINTL DONE")
        val j = find(p) { it.op == Opcode._JUMP && it.a != 0 }
        val target = jumpTarget(p, j, 1)
        assertEquals("DONE", markerPrintText(p, target), "GOTO → \$TAIL 处")
        val skipped = findPrint(p, from = j + 1, text = "SKIPPED")
        assertTrue(target > skipped, "SKIPPED 被跳过")
    }

    @Test
    fun `goto 目标缺失编 THROW 哨兵不中断编译`() {
        // 原版是**行级**错误：装载照常完成，执行到该行才抛 CodeEE
        // （spec: FunctionIdentifier.cs:3664-3669 + ErbLoader.cs:937-955 + Process.cs:1017-1019）。
        // 旧实现让 run() 抛「GOTO 目标不存在」⇒ 整函数被外层兜底成空体桩；现编 THROW 哨兵。
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("T"), parse("GOTO NOWHERE"))
        val p = c.build()
        val throwIns = (fb.entryPc until fb.endPc)
            .map { p.instructionAt(it) }
            .single { it.op == Opcode.THROW }
        val msg = ((p.statement(throwIns.a).args as ExpressionArgument).term as StrLiteral).value
        assertTrue(msg.contains("NOWHERE"), msg)
    }

    @Test
    fun `函数标签表导出`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("T"), parse("\$TOP", "PRINTL A", "\$MID", "PRINTL B"))
        assertTrue(fb.labels.containsKey("TOP"))
        assertTrue(fb.labels.containsKey("MID"))
        assertTrue(fb.labels["TOP"]!! < fb.labels["MID"]!!)
        for (pc in fb.labels.values) {
            assertTrue(pc >= fb.entryPc && pc < fb.endPc, "标签 pc 落在函数体范围内")
        }
    }

    @Test
    fun `标签表升格导出 name 到 idx 与 pc`() {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("T"), parse("\$TOP", "PRINTL A", "\$MID", "PRINTL B"))
        val p = c.build("T")
        val fi = p.functions[fb.index]
        // name→idx 与 name→pc 平行对齐
        for ((name, idx) in fb.labelIndexes) {
            assertEquals(name, fi.labelNames[idx])
            assertEquals(fb.labels[name], fi.labelPcs[idx], "标签 $name 的 idx 与 pc 对齐")
        }
        assertEquals(fb.labels.size, fb.labelIndexes.size)
    }

    @Test
    fun `jump_dyn 按标签下标绝对跳转`() {
        val b = com.eratw.port.core.code.CodeBuilder()
        val fi = b.declareFunction("T", 0, 0, 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("A"), 1, 0, 1)      // pc0
        b.emit(Opcode._JUMP_DYN, 0, 0, 0, 2)                       // pc1 → 标签 0
        b.emit(Opcode._PRINT_LITERAL, b.string("SKIP"), 1, 0, 3)   // pc2
        b.emit(Opcode._PRINT_LITERAL, b.string("TAIL"), 1, 0, 4)   // pc3
        b.emit(Opcode._HALT, 0, 0, 0, 5)                           // pc4
        b.declareLabel(fi, "TAIL", 3)                              // 标签 0 → pc3（TAIL）
        val p = b.build(0)
        val h = runProgram(p, "T")
        val s = h.out.toString()
        assertTrue("A" in s && "SKIP" !in s && "TAIL" in s, "_JUMP_DYN 语义（实际：$s）")
    }

    @Test
    fun `执行 gotoform 按名动态跳转`() {
        // GOTOFORM "TAIL" 语句全链路：FORM 名字项求值（外层引号由解析层剥离，
        // spec: ArgumentParser.cs:802-875）→ _SET_STR 求值到槽 →
        // _JUMP_BY_NAME 按名查当前函数标签表（FunctionInfo.labelNames/labelPcs）跳转
        val p = compileOne("GOTOFORM \"TAIL\"", "PRINTL SKIPPED", "\$TAIL", "PRINTL DONE")
        val h = runProgram(p, "TEST")
        val out = h.out.toString()
        assertTrue("SKIPPED" !in out && "DONE" in out, "GOTOFORM 语句级动态跳转（实际：$out）")
    }

    @Test
    fun `gotoform 与 callform 用 BY_NAME 指令`() {
        val p1 = compileOne("GOTOFORM TAIL")
        assertTrue(find(p1) { it.op == Opcode._JUMP_BY_NAME } >= 0)
        val p2 = compileOne("CALLFORM TAIL")
        assertTrue(find(p2) { it.op == Opcode._CALL_BY_NAME } >= 0)
        // 非 TRYC 的 CALLFORM：c 仍为 NONE（命中标志丢弃 = 纯 TRY/TRY 行为，契约 R3 括注）
        val cbn = p2.instructionAt(find(p2) { it.op == Opcode._CALL_BY_NAME })
        assertTrue(SlotAddr.isNone(cbn.c), "CALLFORM（非 TRYC）不得占用标志槽")
    }

    // ------------------------------------------------------------------
    // RETURNF / 隐式结尾
    // ------------------------------------------------------------------

    @Test
    fun `returnf 带返回槽`() {
        // CMP-31：RETURNF 只在 #FUNCTION/#FUNCTIONS 内合法 ⇒ 本用例按方法编译
        // （isMethod 不参与发射，只做装载期形态校验）。
        val p = compileOne("RETURNF 5", isMethod = true)
        val ret = find(p) { it.op == Opcode._RET && !SlotAddr.isNone(it.a) }
        assertTrue(ret >= 0, "RETURNF 的 _RET 必须带返回槽")
        val set5 = find(p) { it.op == Opcode._SET_INT && p.constant(it.b) == 5L }
        assertTrue(set5 >= 0 && set5 < ret, "返回值先求值到**匿名临时槽**再 _RET（契约 C1：_RET 取值槽合法）")
        assertEquals(SlotAddr.index(p.instructionAt(ret).a), SlotAddr.index(p.instructionAt(set5).a), "_RET 取返回值槽")
    }

    @Test
    fun `掉尾函数先投 RESULT 清零语句再 RET`() {
        // spec: Process.cs:1060-1063 的掉尾 RESULT 清零。旧断言（_SET_INT → **全局槽** RESULT）
        // 已被契约 C1 废除；新形态（C3）：合成 AssignStmt(RESULT = 0) 进语句池，紧邻 _RET 之前。
        val p = compileOne("PRINTL X")
        val ret = find(p) { it.op == Opcode._RET }
        assertTrue(ret >= 0)
        val zero = markerAt(p, ret - 1) as? AssignStmt
        assertTrue(zero != null && zero.dest.name == "RESULT" && zero.op == OperatorCode.ASSIGNMENT,
            "_RET 之前必须是投池的 RESULT = 0 赋值语句")
        assertEquals(0L, (zero!!.args as? SpSetArgument)?.term?.let { (it as? IntLiteral)?.value })
        assertTrue(SlotAddr.isNone(p.instructionAt(ret).a), "掉尾 _RET 无返回槽")
    }

    @Test
    fun `落尾 RESULT 清零对真实 VariableStore 不抛`() {
        // A15-E2E 任务 1 的行为锁（spec: core-vm/build/e2e-report/diagnosis.md §2）：
        // RESULT 在真实 registry 是 **1 维**（SystemLayout.kt:25 int1D → G_INT1；
        // VarDef.kt:115 argCount = dims.size = 1）。旧 resultRef 的零下标 VarRef 经
        // Expr.kt:90 造出 0 长下标，writeInt→checkElement（VariableStore.kt:916-918）
        // 必抛"需要 1 个下标（实际 0）"——任何未显式 RETURN 的落尾函数全崩。
        // 本用例还原 sem execAssign 的写路径（StatementExecutor.kt:1224）：还原任务 1
        // 的一行修复后本用例转红（反向证明），旧形态的解析层假表（上面 parser() 把
        // RESULT 声明成 0 维）正是编译层测试此前抓不到它的原因。
        val p = compileOne("PRINTL X")
        val ret = find(p) { it.op == Opcode._RET }
        val zero = markerAt(p, ret - 1) as AssignStmt
        assertEquals(1, zero.dest.indices.size, "落尾 RESULT 引用必须携带 1 个下标（1D 省略→0 的解析期补全口径）")
        val store = com.eratw.port.core.vars.VariableStore()
        val ctx = object : EvalContext {
            override val variables = store
            override fun callFunction(name: String, args: List<com.eratw.port.core.expr.Value?>): com.eratw.port.core.expr.Value =
                throw AssertionError("不该走到运行期调用")
        }
        val idx = LongArray(zero.dest.indices.size) { zero.dest.indices[it].getIntValue(ctx) }
        store.writeInt("RESULT", idx, 7L)
        // sem 落库路径（execAssign → writeInt）：还原零下标形态时这里抛 CodeEE
        store.writeInt(zero.dest.name, idx, (zero.args as SpSetArgument).term!!.getIntValue(ctx))
        assertEquals(0L, store.readInt("RESULT", longArrayOf(0)))
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    @Test
    fun `事件入口依序 call`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("EVENTTURNEND#65"), parse("PRINTL A"))
        c.compileFunction(FuncSig("EVENTTURNEND#61"), parse("PRINTL B"))
        c.compileFunction(FuncSig("EVENTTURNEND#70"), parse("PRINTL C"))
        val fb = c.compileEventEntry("EVENTTURNEND", listOf("EVENTTURNEND#65", "EVENTTURNEND#61", "EVENTTURNEND#70"))
        val p = c.build("EVENTTURNEND")
        val expected = listOf("EVENTTURNEND#65", "EVENTTURNEND#61", "EVENTTURNEND#70").map { indexOfFunc(p, it) }
        val calls = (fb.entryPc until fb.endPc - 1).map { p.instructionAt(it) }
        assertEquals(3, calls.size, "入口 3 条 _CALL")
        assertEquals(expected, calls.map { it.a }, "按 eventCallOrder 依序")
        assertEquals(listOf(0, 0, 0), calls.map { it.b }, "事件无实参")
        assertEquals(Opcode._RET, p.instructionAt(fb.endPc - 1).op, "入口尾部 _RET")
    }

    // ------------------------------------------------------------------
    // 错误处理
    // ------------------------------------------------------------------

    @Test
    fun `重复函数名报错`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parse("PRINTL A"))
        val e = assertFailsWith<CompileException> { c.compileFunction(FuncSig("t"), parse("PRINTL B")) }
        assertTrue(e.message!!.contains("重复"))
    }

    @Test
    fun `未闭合块报错`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(FuncSig("T"), parse("IF 1", "PRINTL A"))
        }
        assertTrue(e.message!!.contains("未闭合"))
    }

    @Test
    fun `事件入口引用未编译函数报错`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileEventEntry("EV", listOf("GHOST"))
        }
        assertTrue(e.message!!.contains("未编译"))
    }

    // ------------------------------------------------------------------
    // VM 执行（含最小语句池执行器：契约 C4 钩子的测试替身，真实现归 sem/ProcessRuntime）
    // ------------------------------------------------------------------

    private class ExecHost(program: Program) : VmHost {
        val out = StringBuilder()
        val entered = mutableListOf<String>()
        val vars = FakeVariables()
        /**
         * 裸 VM 装置也要接得住**返回值哨兵** `#EXPR_RET#<k>` / `#EXPR_RETS#<k>`。
         *
         * 那是编译期「读第 k 号帧槽」的约定形态，生产侧由
         * `ProcessRuntime.evalContext.exprSentinelValue` 解析（ProcessRuntime.kt:424-435）；
         * M11-A L5 之后**非常量步长的 `[FOR STEP]` 语句**也读它（CORE-03：步长只在 FOR 行求值
         * 一次，快照留在帧槽里逐轮同读，spec: FunctionIdentifier.cs:3005 + NEXT :3401）。
         * 本装置不认哨兵的话，`callFunction` 抛「未绑定函数」被 [applyAssign] 的 `runCatching`
         * 吞掉 ⇒ 计数器永不前进 ⇒ `StepLimitExceeded` 死循环。
         * 解析复用生产同一个 [exprTempSlotOf]（ErbProgramCompiler.kt:2602），不另起一把尺。
         */
        private val ctx = object : TestContext(vars) {
            override fun callFunction(name: String, args: List<Value?>): Value {
                exprTempSlotOf(name)?.let { return Value.IntV(checkNotNull(vm).getInt(SlotAddr.local(it))) }
                exprStrTempSlotOf(name)?.let { return Value.StrV(checkNotNull(vm).getStr(SlotAddr.local(it))) }
                return super.callFunction(name, args)
            }
        }
        private val names = program.functions.map { it.name }
        // GOTOFORM 等名字参数是 FORM 语态，_EVAL_STR 需要 EvalContext；
        // FOR 终值/循环变量读取（_EVAL_INT VarRef A[0]）同样经它 —— vars 是单一测试真相源
        override val evalContext: EvalContext? get() = ctx
        override fun print(text: String) { out.append(text) }
        override fun onFunctionEnter(funcIndex: Int) { entered += names[funcIndex] }

        /** 由 [runProgram] 在建好 VM 之后注入；哨兵读的就是这台 VM 的**当前帧槽**。 */
        var vm: VirtualMachine? = null

        /** 契约 C4：29..197 marker → 本替身最小执行（PRINT 家族字面量 + AssignStmt 标量）。 */
        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            when (stmt) {
                is PrintStmt -> {
                    when (val t = (stmt.args as? ExpressionArgument)?.term) {
                        is StrLiteral -> out.append(t.value)
                        is IntLiteral -> out.append(t.value)
                        is VarRef -> out.append(runCatching { t.getStrValue(ctx) }.getOrNull() ?: t.name)
                        else -> {}
                    }
                    if (stmt.isLineEnd) out.append('\n')
                }
                is AssignStmt -> applyAssign(stmt)
                else -> {}
            }
            return StmtOutcome.Normal
        }

        /** 与 FakeVariables.key / VarRef.getIntValue 同款的"名+下标"寻址（dim-1 变量解析期补 [0]）。 */
        private fun idxOf(v: VarRef): LongArray = LongArray(v.indices.size) { v.indices[it].getIntValue(ctx) }

        private fun keyOf(name: String, idx: LongArray): String =
            if (idx.isEmpty()) name else name + idx.joinToString(":", prefix = ":")

        private fun applyAssign(a: AssignStmt) {
            val sp = a.args as? SpSetArgument ?: return
            val key = keyOf(a.dest.name, idxOf(a.dest))
            if (a.dest.operandType == OperandType.STR) {
                val s = if (sp.isConst) sp.constStr
                else runCatching { sp.term?.getStrValue(ctx) }.getOrNull() ?: return
                vars.strs[key] = s
                return
            }
            // 取值口径同生产 `StatementExecutor`：`IsConst ? ConstInt : Term.GetIntValue`；
            // `AddConst`（常量 `+= / -=`，ArgumentParser.cs:1073-1090）走增量，
            // 非常量复合赋值的整式已在 Term 里（同文件 :1092-1096）⇒ 覆盖写。
            val v = if (sp.isConst) sp.constInt
            else runCatching { sp.term?.getIntValue(ctx) }.getOrNull() ?: return
            if (sp.addConst) vars.ints[key] = (vars.ints[key] ?: 0L) + v
            else vars.ints[key] = v
        }
    }

    /** 种子同时写"裸名"与"名:0"两种键位（解析期给 dim-1 变量补下标，具体形态不锁死在本测试）。 */
    private fun runProgram(p: Program, entry: String, ints: Map<String, Long> = emptyMap()): ExecHost {
        val host = ExecHost(p)
        ints.forEach { (k, v) ->
            host.vars.ints[k] = v
            host.vars.ints["$k:0"] = v
        }
        VirtualMachine(p, host).also { host.vm = it }.run()
        return host
    }

    /** 读回变量终值：dim-1 补下标形态（名:0）优先——种子同时铺两种键位，写入只会命中真形态那一路。 */
    private fun intVar(h: ExecHost, name: String): Long? = h.vars.ints["$name:0"] ?: h.vars.ints[name]

    @Test
    fun `执行 if else 走真分支`() {
        val p = compileOne("IF 1", "PRINTL T", "ELSE", "PRINTL F", "ENDIF", "PRINTL END")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("T" in s && "F" !in s, "真分支执行、假分支被跳过（实际输出：$s）")
        assertTrue("END" in s)
    }

    @Test
    fun `执行 if else 走假分支`() {
        val p = compileOne("IF 0", "PRINTL T", "ELSE", "PRINTL F", "ENDIF", "PRINTL END")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("F" in s && "T" !in s, "假分支执行、真分支被跳过（实际输出：$s）")
    }

    @Test
    fun `ELSE 之后的 ELSEIF 不崩且为死分支`() {
        // 审计 finding 1：旧实现 compileElse 把 pending 置 null，compileElseIf 的
        // `sc.pending ?: error("unreachable")` 抛 IllegalStateException，整段函数编译失败。
        // 原版对 ELSE 之后的 ELSE/ELSEIF 只 Warn(InvalidElseAfterElse) 并照常加入 IfCaseList
        // （spec: ErbLoader.cs:1219-1223）；运行期 IF 遇到 ELSE 即 break
        // （spec: FunctionIdentifier.cs:3123-3127）⇒ 新分支是死代码，但装载/编译照常完成。
        val p = compileOne(
            "IF 0", "PRINTL A", "ELSE", "PRINTL B", "ELSEIF 1", "PRINTL C",
            "ENDIF", "PRINTL D",
        )
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertTrue("B" in s, "ELSE 体必须执行（实际：$s）")
        assertTrue("A" !in s && "C" !in s, "IF 假分支与 ELSE 之后的 ELSEIF 都不得执行（实际：$s）")
        assertTrue("D" in s, "ENDIF 之后继续（实际：$s）")
    }

    // ---- R4 行为锁：排他界（旧"FOR A,0,2 跑 3 轮"的断言锁的是被契约 R4 废除的含端口径，
    //      已改写为 2 轮；5 轮/负步长/零步长/上界只求值一次为新增锁）----

    @Test
    fun `执行 FOR 上界排他 跑五轮`() {
        val p = compileOne("FOR A, 0, 5", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "FOR A,0,5 原版 5 轮（旧含端口径 6 轮已废）；实际：$s")
        assertEquals(5L, intVar(h, "A"), "出循环时计数器已越过上界一步（NEXT 先步进再判定）")
        assertTrue("D" in s)
    }

    @Test
    fun `执行 FOR 负步长 跑五轮`() {
        val p = compileOne("FOR A, 5, 0, -1", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "FOR A,5,0,-1 原版 5 轮（cnt=5..1）；实际：$s")
        assertEquals(0L, intVar(h, "A"))
    }

    @Test
    fun `执行 FOR 零步长 零轮`() {
        val p = compileOne("FOR A, 0, 5, 0", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST")
        val s = h.out.toString()
        assertEquals(0, s.count { it == 'L' }, "step==0 ⇒ 入口无条件出体（spec: FunctionIdentifier.cs:3006）")
        assertEquals(0L, intVar(h, "A"), "初始化（SetValue(Start) :3003）仍先于跳过执行")
        assertTrue("D" in s)
    }

    @Test
    fun `执行 FOR 运行期零步长 零轮不死循环`() {
        // 审计 finding 2：非常量步长旧实现一律 `_LT`，运行期 step==0 时 cnt 永不前进 ⇒
        // 死循环。原版入口判定 `(step<=0 || end<=cnt) && (step>=0 || end>=cnt)`
        // （spec: FunctionIdentifier.cs:3006）在 step==0 时两个析取同时为真 ⇒ 直接出体。
        val p = compileOne("FOR A, 0, 5, B", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST", ints = mapOf("B" to 0L))
        val s = h.out.toString()
        assertEquals(0, s.count { it == 'L' }, "运行期 step==0 必须零轮（实际：$s）")
        assertEquals(0L, intVar(h, "A"), "初始化仍执行")
        assertTrue("D" in s)
    }

    @Test
    fun `执行 FOR 运行期负步长 五轮`() {
        // 审计 finding 2：动态负步长旧实现选 `_LT`（5 < 0 假 ⇒ 入口直接出体，零轮）。
        // 原版按步长符号选方向（spec: FunctionIdentifier.cs:3006/3403）⇒ cnt > end 续循环。
        val p = compileOne("FOR A, 5, 0, B", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST", ints = mapOf("B" to -1L))
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "FOR A,5,0,B 且 B=-1 原版 5 轮（cnt=5..1）；实际：$s")
        assertEquals(0L, intVar(h, "A"))
        assertTrue("D" in s)
    }

    @Test
    fun `执行 FOR 运行期正步长 与常量同口径`() {
        val p = compileOne("FOR A, 0, 5, B", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST", ints = mapOf("B" to 1L))
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "运行期 step=1 与 FOR A,0,5 同口径；实际：$s")
        assertEquals(5L, intVar(h, "A"), "NEXT 先步进后判定")
        assertTrue("D" in s)
    }

    @Test
    fun `执行 FOR 运行期负步长到上界后继续判定`() {
        // 回边（NEXT）必须按当轮步长符号重新判定：B=-1 从 3 递减到 -2 共 5 轮，
        // 越过 0 后 cnt > end 仍成立（原版 :3403 `step<0 && end<cnt`）。
        val p = compileOne("FOR A, 3, -2, B", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST", ints = mapOf("B" to -1L))
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "cnt=3,2,1,0,-1 五轮（实际：$s）")
        assertEquals(-2L, intVar(h, "A"))
    }

    @Test
    fun `执行 FOR 上界只求值一次`() {
        // spec: FunctionIdentifier.cs:3003-3005：Start/End/Step 只在头行求值一次。
        // body 改写 B 不得影响迭代次数（终值留在 VM 匿名临时槽，契约 C1/R4）。
        val p = compileOne("FOR A, 0, B, 1", "B = 99", "PRINTL L", "NEXT", "PRINTL D")
        val h = runProgram(p, "TEST", ints = mapOf("B" to 5L))
        val s = h.out.toString()
        assertEquals(5, s.count { it == 'L' }, "上界表达式只求值一次：B 被改成 99 也不加轮；实际：$s")
        assertEquals(99L, intVar(h, "B"), "body 里的改写本身要生效（只是不参与循环判定）")
    }

    @Test
    fun `执行 while 加 break`() {
        val p = compileOne("WHILE 1", "BREAK", "WEND", "PRINTL OK")
        val h = runProgram(p, "TEST")
        assertTrue("OK" in h.out.toString(), "BREAK 必须真正跳出（回填）")
    }

    @Test
    fun `执行 do loop 至少一次`() {
        val p = compileOne("DO", "PRINTL X", "LOOP 0")
        val h = runProgram(p, "TEST")
        assertTrue("X" in h.out.toString())
    }

    @Test
    fun `执行 repeat 两次`() {
        val p = compileOne("REPEAT 2", "PRINTL R", "REND", "PRINTL E")
        val h = runProgram(p, "TEST")
        assertEquals(2, h.out.toString().count { it == 'R' }, "R4 后 REPEAT 仍是 n 轮（上界 n 排他、无 -1 补偿）；实际：${h.out}")
        assertTrue("E" in h.out.toString())
    }

    @Test
    fun `执行 事件入口序 EVENTTURNEND 65 61 70`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("EVENTTURNEND#65"), parse("PRINTL 65"))
        c.compileFunction(FuncSig("EVENTTURNEND#61"), parse("PRINTL 61"))
        c.compileFunction(FuncSig("EVENTTURNEND#70"), parse("PRINTL 70"))
        c.compileEventEntry("EVENTTURNEND", listOf("EVENTTURNEND#65", "EVENTTURNEND#61", "EVENTTURNEND#70"))
        val p = c.build("EVENTTURNEND")
        val h = runProgram(p, "EVENTTURNEND")
        // entered[0] 是入口自身，其后为按序调用的成员
        assertEquals(
            listOf("EVENTTURNEND", "EVENTTURNEND#65", "EVENTTURNEND#61", "EVENTTURNEND#70"),
            h.entered,
        )
        val s = h.out.toString()
        assertTrue("65" in s && "61" in s && "70" in s)
    }

    @Test
    fun `执行 call 顺序与返回`() {
        val p = compileFuncs("TEST" to arrayOf("PRINTL M", "CALL SUB", "PRINTL E"), "SUB" to arrayOf("PRINTL S"))
        val h = runProgram(p, "TEST")
        assertEquals("MSE", h.out.toString().replace("\n", ""))
    }

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    @Test
    fun `编译 反汇编 往返一致`() {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse(
            "IF 1", "PRINTL A", "ELSEIF 0", "PRINTL B", "ELSE", "PRINTL C", "ENDIF",
            "FOR A, 0, 2", "PRINTL L", "NEXT",
            "SELECTCASE 1", "CASE 1 TO 3", "PRINTL IN", "CASE IS > 5", "PRINTL BIG", "ENDSELECT",
            "GOTO TAIL", "PRINTL S", "\$TAIL", "PRINTL D",
        ))
        c.compileFunction(FuncSig("SUB"), parse("PRINTL S2", "CALL TEST"))
        val p = c.build("TEST")
        val d1 = Disassembler.disassemble(p)
        // marker 往返要带语句池快照（Assembler 的 stmt#k 表，契约 C2 按身份追加）
        val d2 = Disassembler.disassemble(Assembler.assemble(d1, p.expressions(), p.statements()))
        assertEquals(d1, d2, "asm(disasm(p)) 必须还原出相同程序")
    }
}
