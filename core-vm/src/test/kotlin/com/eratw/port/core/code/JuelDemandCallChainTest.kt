package com.eratw.port.core.code

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.exprTempSlotOf
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.FakeVariables
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 红 J（NEWGAME-STEP-SPIN）的**最小复现**：`JUEL_DEMAND` 返回值链与 `GOTO AGAIN` 回边。
 *
 * 语料里打转的那段（ERB 目录下 `ステータス計算関連/ABL/ABL.ERB`）:
 * - `ABL.ERB:79-131` 是 `FOR LOCAL,0,4` + `$AGAIN`(:80) … `GOTO AGAIN`(:130) 的定长回边；
 *   出体的唯一途径是 `能力値不足`(:91) / `経験不足`(:102) 被置位 → `CONTINUE`(:97/:108)。
 * - 置位条件要求 `JUEL_DEMAND(...) > 0`（`ABL.ERB:89`），即"需求为 0 的位"根本不计入不足；
 *   而 `ABL_UP_DATA.ERB:2775-2776` 的 `SIF DEMAND == 0 → RETURNF 0` 让 0 成为**合法返回**。
 *   于是需求链上任何一环退化成 0，`GOTO AGAIN` 就永远退不出去 —— 5,000,000 步闸唯一的出口。
 *
 * 本测试只锁 **code/ 层的调用/返回链**（契约允许改动面）：
 * 1. 方法调用作赋值右值 → `_CALL(c=返回槽)` 的返回值由紧后的赋值 marker 哨兵取回
 *    （`ErbProgramCompiler.kt` 的 `lowerFuncCall`；Vm.kt:608-615 `_CALL`）。
 * 2. `RETURNF 嵌套调用` → `_CALL` 之后 `_EVAL_INT` 读哨兵槽、`_RET` 从**同一槽**取返回值
 *    （Vm.kt:693-724 `_RET`；Reference: `FunctionIdentifier.cs:3444-3485` RETURNF）。
 * 3. `SIF call(...) == -2` 与 `ARG > 0 → RETURNF -1` 早退（Reference: `ABL_UP_DATA.ERB:114-117`）。
 * 4. `RETURNF TBL:(ARG:1)`：形参 `ARG:1` 作下标的读与"缺位得 0"的口径
 *    （Reference: `ProcessState.cs:435-452` 头部形参按位写 ARG:i）。
 * 5. `$AGAIN` + `GOTO AGAIN` 回边的终止性：同一 ABL 等级反复回边时，
 *    `LV += 1` 的写入必须被下一轮的调用读到（否则等级不涨、需求不涨 → 永不不足）。
 *
 * 帧模型口径（`Vm.kt:824-867` callInto / `:913-953` jumpInto / `:967-1080` leaveFrame）：
 * 被调帧存 retPc/retDst/retStr/frameBase/frameSlot 五元组，`_RET` **先恢复调用方帧再写回**
 * （Vm.kt `leaveFrameCore`），与 Reference `ProcessState.cs:272-340`（IntoFunction/Return）
 * 的「弹栈后才把返回值交给调用者」同序。
 *
 * 刻意**不**用真 `VariableStore`/`StatementExecutor`：本测试要证明的是"编译产物 + VM 帧栈本身
 * 不丢返回值、不回错帧"，变量层（LOCAL 私有域、`+=` 落盘）属于别的目录，见交付报告。
 */
class JuelDemandCallChainTest {

    // ------------------------------------------------------------------
    // 夹具：解析器 + 编译器 + VM（与 compile 包既有用例同源）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("Y", OperandType.INT, 1)
            .declareVariable("Z", OperandType.INT, 1)
            .declareVariable("W", OperandType.INT, 1)
            .declareVariable("LV", OperandType.INT, 1)
            .declareVariable("STEP", OperandType.INT, 1)
            .declareVariable("D", OperandType.INT, 1)
            .declareVariable("TBL", OperandType.INT, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareFunction("DOUBLE", OperandType.INT)
            .declareFunction("PLUS100", OperandType.INT)
            .declareFunction("CHAIN", OperandType.INT)
            .declareFunction("DEM", OperandType.INT)
            .declareFunction("NEG", OperandType.INT)
            .declareFunction("DEMAND", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "juel.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun table() = ExprCallTable(
        setOf("DOUBLE", "PLUS100", "CHAIN", "DEM", "NEG", "DEMAND"),
        emptySet(),
        emptySet(),
    )

    /** 一个待编译函数：名字 + 形参数（`_CALL` 槽复制数，Reference `ProcessState.cs:435-452`）+ 体。 */
    private data class Fn(val name: String, val arity: Int, val lines: Array<String>)

    private fun compile(vararg funcs: Fn): Program {
        val c = ErbProgramCompiler()
        val names = funcs.map { it.name }.toSet()
        val tbl = table()
        for (f in funcs) {
            // CMP-31：RETURNF 只在 #FUNCTION/#FUNCTIONS 内合法
            // （spec: FunctionIdentifier.cs:3455-3458 CanNotUseReturnf），夹具一律按方法编译。
            c.compileFunction(
                FuncSig(f.name, f.arity, isMethod = true), parse(*f.lines), names, tbl,
            )
        }
        return c.build(funcs.first().name)
    }

    private fun find(p: Program, pred: (Instruction) -> Boolean): Int {
        for (pc in 0 until p.instructionCount) if (pred(p.instructionAt(pc))) return pc
        return -1
    }

    // ---- 执行替身：VM + 哨兵桥（复刻 proc 层 `#EXPR_RET#<k>` 读当前帧的接线） ----

    private class Host : VmHost {
        val vars = FakeVariables()
        var vm: VirtualMachine? = null

        /** `ARG:1` 等形参位 = 当前帧槽（`_CALL` 已按位复制到 R0..，Vm.kt:824-867）。 */
        val reader = object : VariableReader by vars {
            override fun readInt(name: String, indices: LongArray): Long =
                if (name.equals("ARG", ignoreCase = true) && indices.isNotEmpty()) {
                    requireNotNull(vm).getInt(SlotAddr.local(indices[0].toInt()))
                } else {
                    vars.readInt(name, indices)
                }
        }

        val ctx = object : EvalContext {
            override val variables: VariableReader get() = reader
            override fun callFunction(name: String, args: List<Value?>): Value {
                val k = exprTempSlotOf(name)
                    ?: throw ExprEvalException("表达式位调用未降级: $name")
                return Value.IntV(requireNotNull(vm).getInt(SlotAddr.local(k)))
            }
        }

        override val evalContext: EvalContext? get() = ctx

        override fun print(text: String) {}

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            if (stmt is AssignStmt) {
                val arg = stmt.args as? SpSetArgument ?: return StmtOutcome.Normal
                val idx = LongArray(stmt.dest.indices.size) { stmt.dest.indices[it].getIntValue(ctx) }
                val key = if (idx.isEmpty()) stmt.dest.name else stmt.dest.name + idx.joinToString(":", prefix = ":")
                // 与产品执行层同形（StatementExecutor.execAssign，spec: FunctionIdentifier.cs:640-657）：
                // 取值 `IsConst ? ConstInt : Term`；`AddConst`（`+=`/`-=`/`++`/`--` 折叠出来的
                // 常量增量形态，ArgumentParser.cs:1073-1090）走 ChangeValue，绝不能按 `stmt.op` 再套一次。
                if (stmt.dest.operandType == OperandType.STR) {
                    vars.strs[key] = if (arg.isConst) arg.constStr else arg.term?.getStrValue(ctx) ?: ""
                } else {
                    val v = if (arg.isConst) arg.constInt else arg.term?.getIntValue(ctx) ?: 0L
                    vars.ints[key] = if (arg.addConst) (vars.ints[key] ?: 0L) + v else v
                }
            }
            return StmtOutcome.Normal
        }
    }

    private fun run(p: Program): Pair<Host, Long> {
        val h = Host()
        val vm = VirtualMachine(p, h)
        h.vm = vm
        return h to vm.run()
    }

    private fun intVar(h: Host, name: String): Long = h.vars.ints["$name:0"] ?: h.vars.ints[name] ?: 0L

    // ------------------------------------------------------------------
    // 1) 方法调用作赋值右值 —— `JUEL_DEMAND_RESULT = JUEL_DEMAND_4(...)` 形态
    // ------------------------------------------------------------------

    @Test
    fun `赋值右值调用返回原值并经哨兵槽落盘`() {
        val p = compile(
            Fn("TEST1", 0, arrayOf("Y = DOUBLE(3)", "RETURNF Y")),
            Fn("DOUBLE", 1, arrayOf("RETURNF ARG:0 * 2")),
        )
        // 形状锁：`_CALL` 的 c = 帧内匿名返回槽；紧随其后一条是赋值 marker，
        // 其右值是读同一槽的零参哨兵（返回值就地消费，不落具名槽）。
        val callPc = find(p) { it.op == Opcode._CALL }
        assertTrue(callPc >= 0, "降级必须发射 _CALL")
        val call = p.instructionAt(callPc)
        assertEquals(1, call.b, "b = 实参数")
        assertTrue(!SlotAddr.isNone(call.c), "c = 调用方帧的返回槽")
        val retSlot = SlotAddr.index(call.c)
        val afterPc = ((callPc + 1) until p.instructionCount)
            .firstOrNull { p.instructionAt(it).op in 29..197 } ?: -1
        assertTrue(afterPc in (callPc + 1)..(callPc + 2), "赋值 marker 紧跟 _CALL（返回值就地消费）")
        val after = p.instructionAt(afterPc)
        assertIs<AssignStmt>(p.statement(after.a), "紧后的 marker 必须是赋值语句")
        val marker = p.statement(after.a) as AssignStmt
        val sentinel = assertIs<FuncCall>(
            (marker.args as? SpSetArgument)?.term,
            "marker 右值必须是被调返回哨兵",
        )
        assertEquals(retSlot, exprTempSlotOf(sentinel.name), "marker 右值哨兵槽 = _CALL.c")
        // 行为锁：A(3) = 3 * 2
        val (h, result) = run(p)
        assertEquals(6L, result, "RETURNF Y 必须带回 _CALL 的返回值")
        assertEquals(6L, intVar(h, "Y"))
    }

    // ------------------------------------------------------------------
    // 2) `RETURNF CALC_ABL_DEMAND(ABL_ID, ...)` 形态：嵌套调用作 RETURNF 表达式
    // ------------------------------------------------------------------

    @Test
    fun `RETURNF 表达式内的嵌套调用返回值同槽带回`() {
        val p = compile(
            Fn("TEST2", 0, arrayOf("RETURNF CHAIN(3)")),
            Fn("CHAIN", 1, arrayOf("RETURNF PLUS100(DOUBLE(ARG:0))")),
            Fn("DOUBLE", 1, arrayOf("RETURNF ARG:0 * 2")),
            Fn("PLUS100", 1, arrayOf("RETURNF ARG:0 + 100")),
        )
        // 形状锁：`_CALL` → `_EVAL_INT`(哨兵) → `_RET`，且 _RET 与哨兵槽同号
        val callPc = find(p) { it.op == Opcode._CALL }
        assertTrue(callPc >= 0, "RETURNF 的嵌套调用必须降级为 _CALL")
        val eval = p.instructionAt(callPc + 1)
        assertEquals(Opcode._EVAL_INT, eval.op, "_CALL 紧后一条读返回哨兵")
        val ret = p.instructionAt(callPc + 2)
        assertEquals(Opcode._RET, ret.op, "哨兵读完后立刻 _RET")
        val sentinel = assertIs<FuncCall>(p.expr(eval.a), "哨兵是合法表达式节点")
        assertEquals(
            SlotAddr.index(p.instructionAt(callPc).c),
            exprTempSlotOf(sentinel.name),
            "哨兵槽 = _CALL.c",
        )
        assertEquals(SlotAddr.index(ret.a), SlotAddr.index(eval.b), "_RET 读的就是哨兵落到的槽")
        // 行为锁：PLUS100(DOUBLE(3)) = 3 * 2 + 100
        val (_, result) = run(p)
        assertEquals(106L, result, "RET 链必须原样回写（ABL_UP_DATA.ERB:2777 的 RETURNF MAX(...) 同形）")
    }

    // ------------------------------------------------------------------
    // 3) `SIF JUEL_DEMAND(...) == -2` 与 `SIF ARG > 0 → RETURNF -1`
    // ------------------------------------------------------------------

    @Test
    fun `SIF 条件内的调用按返回值决定跳过且负值早退原样`() {
        val p = compile(
            Fn(
                "TEST3", 0,
                arrayOf(
                    "Y = 99",
                    "SIF NEG(1) == -2",
                    "Y = 1",
                    "SIF NEG(2) == -2",
                    "Y = Y + 10",
                    "RETURNF Y",
                ),
            ),
            Fn("NEG", 1, arrayOf("SIF ARG:0 == 1", "RETURNF -2", "RETURNF 0")),
        )
        val (h, result) = run(p)
        // SIF 只接管紧随其后的一行：NEG(1) == -2 成立 → 执行 `Y = 1`；
        // NEG(2) = 0 ≠ -2 → 跳过 `Y = Y + 10`，Y 保持 1。
        assertEquals(1L, result, "SIF 载条件里的调用不得被吞成 0（ABL.ERB:83-84）")
        assertEquals(1L, intVar(h, "Y"))
    }

    // ------------------------------------------------------------------
    // 4) `RETURNF LOCAL:(ARG:1)` 形态：形参作下标 + 缺位得 0
    // ------------------------------------------------------------------

    @Test
    fun `形参 ARG 1 作下标的表读与缺位零值`() {
        val p = compile(
            Fn(
                "TEST4", 0,
                arrayOf(
                    "TBL:11 = 200",
                    "Y = DEM(0, 11)",
                    "Z = DEM(1, 11)",
                    "W = DEM(0, 12)",
                    "RETURNF Y",
                ),
            ),
            // ABL_UP_DATA.ERB:114-117 同形：模式 > 0 直接 -1；命中位返回预置需求
            Fn("DEM", 2, arrayOf("SIF ARG:0 > 0", "RETURNF -1", "RETURNF TBL:(ARG:1)")),
        )
        val (h, result) = run(p)
        assertEquals(200L, result, "ARG:1 = 11 必须读到 TBL:11")
        assertEquals(200L, intVar(h, "Y"))
        assertEquals(-1L, intVar(h, "Z"), "模式 1 → -1 早退（ABL.ERB:85-86 据此 CONTINUE）")
        // 缺位 = 0：CALC_ABL_DEMAND 的 `SIF DEMAND == 0 → RETURNF 0`（ABL_UP_DATA.ERB:2775-2776）
        assertEquals(0L, intVar(h, "W"), "未预置下标必须是 0（需求 0 的位不计入不足）")
    }

    // ------------------------------------------------------------------
    // 5) `$AGAIN` + `GOTO AGAIN` 回边的终止性（ABL.ERB:80-130 的骨架）
    // ------------------------------------------------------------------

    @Test
    fun `ABL 等级回边循环在需求随等级增长时必须终止`() {
        val p = compile(
            Fn(
                "TEST5", 0,
                arrayOf(
                    "LV = 0",
                    "STEP = 0",
                    // 与 ABL.ERB:80 同形：回边落在这条标签上
                    "\$AGAIN",
                    "STEP += 1",
                    "SIF STEP > 10000",
                    "RETURNF -99",
                    "D = DEMAND(0, LV)",
                    "IF D > 40",
                    // 与 ABL.ERB:97-108 的 CONTINUE 同效：资源不足 → 出体
                    "GOTO DONE",
                    "ENDIF",
                    "LV += 1",
                    "GOTO AGAIN",
                    "\$DONE",
                    "RETURNF LV",
                ),
            ),
            // 需求随等级线性增长：等级 0..4 → 10,20,30,40,50（>40 即"资源不足"）
            Fn("DEMAND", 2, arrayOf("SIF ARG:0 > 0", "RETURNF -1", "RETURNF (ARG:1 + 1) * 10")),
        )
        val (h, result) = run(p)
        assertEquals(5L, intVar(h, "STEP"), "回边轮数必须是 5（多/漏记步 = 闸口径问题）")
        assertEquals(4L, intVar(h, "LV"), "LV += 1 必须被下一轮调用读到，否则永不终止")
        assertEquals(4L, result, "循环正常出体后 RETURNF LV")
        val steps = requireNotNull(h.vm).executedSteps
        assertTrue(steps < 1000, "5 轮循环不得吃掉上千步（GOTO 只改 pc、每条指令记一次，实际 $steps）")
    }
}
