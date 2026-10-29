package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11-A L5（sem-casefor）：BOARD 引擎缺陷总账 **CORE-01** 与 **CORE-03** 的行为锁。
 *
 * ## CORE-01 字符串 `CASE lo TO hi` 区间判定整体取反
 *
 * 原版 SELECTCASE 的 TO 形态**不走运算符重载**：`CaseExpression.GetBool(string, exm)` 是一段
 * 独立的区间判定（spec: CaseExpression.cs:62-70 —— `string.Compare(LeftTerm, Is, Ordinal) <= 0`
 * 成立才判 `string.Compare(Is, RightTerm, Ordinal) <= 0`，下界不成立直接 `return false`），
 * 整数版同构（同文件 :45-60，编译器侧走整型 `_GE`/`_LE` 指令、本来就对，本路不动）。
 * 移植侧旧实现把它写成 `target GREATER_EQUAL lo && target LESS_EQUAL hi`
 * （ErbProgramCompiler.compileCaseTo 的字符串分支 + Expr.kt strCompare），而字符串 `>=`/`<=`
 * 是**两处官方 bug**（`>=` 完全反向、`<=` 相等泄漏；specs/verify-notes.md §1 与文末官方 bug
 * 清单第 1/2 条，ADR-005 要求原样复刻）。把 bug 接进区间判定 ⇒ 命中集合整体取反：
 * `CASE "a" TO "c"` 配 "b"/"a"/"c" **永不命中**，`CASE "m" TO "z"` 配 "a" **反而命中**。
 *
 * 修复方向：bug 本体保留（源码真写 `>=`/`<=` 的表达式位读数不许变，见
 * [运算符版字符串大于等于官方bug原样保留]），区间判定改走 `Expr.kt` 的 `strCaseRange`
 * ——用不带 bug 的 `>` 表达 `<=`（`compare(a,b) <= 0` ⇔ `!(a > b)`），并保住原版的
 * 求值顺序与短路（下界失配则上界表达式**连求值都不求**，见
 * [字符串CASE下界失配时不走上界表达式]）。
 *
 * **语料 0 处命中**（字符串 TO 形态在画蛇添足版里不存在）⇒ 本路的编译产物变化只可能
 * 出现在"含字符串 CASE TO"的函数上，既有基准读数（E2E 610 行 / corpusSmoke 指令数 /
 * L3 链哈希）预期**逐位不变**；由组长在收口时实测（本路按纪律不跑 gradle）。
 *
 * ## CORE-03 `FOR` 的非常量步长逐轮重求值
 *
 * 原版只在 FOR 行求一次：`func.LoopStep = spForNextArgment.Step.GetIntValue(exm)`
 * （spec: FunctionIdentifier.cs:3005），NEXT/REND 步进与判定、CONTINUE（:3340-3342）、
 * BREAK（:3316）读的都是这个 long 快照；`specs/isa.yaml` A9 段（:1670-1671）已把这条写成规约
 * （"**非常量步长不会逐迭代重算**；`FOR A,0,10,STEPVAR` 改 STEPVAR 不影响已开循环"）。
 * 移植侧旧实现把步长原表达式留在步进语句里，且入口判定的求值点在**回边目标之后**
 * （ErbProgramCompiler.compileFor），于是每轮 NEXT 与每轮判定各重求一次。
 *
 * 修复方向：与终值同口径 —— 非常量步长在 FOR 行（回边目标之前）求值进帧内持有槽，
 * 步进语句经返回值哨兵 [EXPR_TEMP_PREFIX] 读同一槽（与 [compileMethod] 写 RESULT 同形态）。
 * 步进语句的**形态**约束不变：常量步长仍折成「常量增量」（`SpSetArgument.isConst/addConst`，
 * spec: ArgumentParser.cs:1073-1090，执行层只读形态、不再按 op 重算），没有退回「term + op」。
 *
 * 语料有 5 处非常量步长（`ARG:` / `加减` / `L_CHUNK_SIZE` 一类），全部在动画与列表函数里；
 * 这些循环体内不改步长、步长表达式也无副作用 ⇒ 运行期读数预期不变，但**槽位编号会移动**
 * （步长槽改在零值基准槽之前分配、且循环期内持有）⇒ 那几个函数的 `.erbc`/`program.meta`
 * 字节必然变化，需组长统一重编语料并核对三处读数（见类 KDoc 末段与交付报告）。
 *
 * ## 装置
 *
 * 纯 JVM：真 [LineParser] + 真 [ErbProgramCompiler] + 真 [VariableStore] + 真 [ProcessRuntime]
 * （形态照 `ForBuiltinLetterCounterTest`，不吃语料环境变量、不落盘）。循环变量与标志位一律用
 * 内建裸字母 `A`..`Z`（spec: VariableData.cs:3040 起 varTokenDic 的 1 维整型系统变量；
 * 移植侧 stmt/BuiltinSymbols.kt + vars/SystemLayout.kt 同名册），避免为测试另造变量册。
 */
class CaseRangeAndStepTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(SymbolTable())
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "case-for.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Program {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parse(*lines), setOf("T"))
        return c.build("T")
    }

    private fun run(program: Program): VariableStore {
        val store = VariableStore()
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()
        return store
    }

    /** 内建字母变量读数（dim-1，解析期补下标 0）。 */
    private fun intOf(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // CORE-01：字符串 CASE TO 区间判定（行为）
    // ------------------------------------------------------------------

    /**
     * 原版：`a <= "b" <= c` ⇒ 命中（spec: CaseExpression.cs:66-68）。
     * **改前实际**：`"b" >= "a"` 走反向的字符串 `>=`（bug）得 0 ⇒ 整条区间失配，J 恒 0。
     */
    @Test
    fun `字符串CASE区间内的值命中`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"b\"",
                "CASE \"a\" TO \"c\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(1L, intOf(store, "J"), "\"b\" 落在 \"a\" TO \"c\" 内必须命中（CaseExpression.cs:66-68）")
    }

    /**
     * 下界相等必须命中：原版 `compare("a","a") = 0 <= 0`（spec: CaseExpression.cs:66）。
     * **改前实际**：`target >= lo` 的官方 bug 是 `c < 0` ⇒ 相等判 0 ⇒ 不命中。
     */
    @Test
    fun `字符串CASE下界本身命中`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"a\"",
                "CASE \"a\" TO \"c\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(1L, intOf(store, "J"), "区间含下界端点（原版用 `<= 0`，不是 `< 0`）")
    }

    /**
     * 上界相等必须命中：原版 `compare("c","c") = 0 <= 0`（spec: CaseExpression.cs:68）。
     * **改前实际**：`target <= hi` 的官方 bug 也是 `c < 0`（相等泄漏）⇒ 上界端点判不命中。
     */
    @Test
    fun `字符串CASE上界本身命中`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"c\"",
                "CASE \"a\" TO \"c\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(1L, intOf(store, "J"), "区间含上界端点：TO 判定不走 `<=` 运算符，相等也算命中")
    }

    /**
     * 区间左侧的值不得命中：原版 `compare("m","a") = 1 > 0` ⇒ 下界不成立（spec: CaseExpression.cs:66-70）。
     * **改前实际**：反向的 `>=` 让 "a" 落进 "m" TO "z"，J 被误置 1（整体取反的另一半）。
     */
    @Test
    fun `字符串CASE区间左侧的值不误命中`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"a\"",
                "CASE \"m\" TO \"z\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "\"a\" 在 \"m\" TO \"z\" 之外（改前正是这里错命中 1）")
    }

    /** 超出上界不命中（改前后一致，作为区间上界的回归锁）：spec: CaseExpression.cs:68。 */
    @Test
    fun `字符串CASE超上界的值不命中`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"d\"",
                "CASE \"a\" TO \"c\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "\"d\" 大于上界 \"c\" ⇒ 失配")
    }

    /**
     * 同一 CASE 行多段逐段试配（spec: FunctionIdentifier.cs:3197-3204 的 caseExps 循环 +
     * CaseExpression.cs:62-70 每段自己的区间判定）：第一段下界成立、上界失配 ⇒ 继续试第二段。
     * **改前实际**：两段都按取反口径判，"e" 谁也不命中。
     */
    @Test
    fun `多段字符串CASE逐段试配`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"e\"",
                "CASE \"a\" TO \"b\", \"d\" TO \"f\"",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(1L, intOf(store, "J"), "第一段失配后必须继续试第二段（ErbProgramCompiler.compileCase 的局部回填）")
    }

    /** 全段失配 → CASEELSE 体（spec: FunctionIdentifier.cs:3173-3176 CASEELSE 直取）。 */
    @Test
    fun `字符串CASE全段失配落CASEELSE`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"z\"",
                "CASE \"a\" TO \"c\"",
                "J = 1",
                "CASEELSE",
                "K = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "\"z\" 在区间外")
        assertEquals(1L, intOf(store, "K"), "失配必须落 CASEELSE 体")
    }

    /**
     * 下界失配时**不求上界表达式**（spec: CaseExpression.cs:66-70 —— 下界那条 `if` 不成立即
     * `return false`，RightTerm.GetStrValue 根本没被调用）。
     * 上界写成 `TOSTR(1 / K, "D1")`（K 缺省 0）：一旦求值就抛「除数为 0」（Expr.kt 的 DIV 分支，
     * spec: OperatorMethodManager.cs:121-157），VM 不吞表达式异常（Vm.kt `_EVAL_INT` 无 catch），
     * 所以本用例能跑完本身就是短路的证据。
     * **改前实际**：`target >= lo` 反向 bug 让下界"成立"，于是真的去求上界 ⇒ 运行期抛错。
     */
    @Test
    fun `字符串CASE下界失配时不走上界表达式`() {
        val store = run(
            compile(
                "@T",
                "SELECTCASE \"a\"",
                "CASE \"z\" TO TOSTR(1 / K, \"D1\")",
                "J = 1",
                "ENDSELECT",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "下界失配 ⇒ 整条 CASE 失配")
    }

    /**
     * 结构锁：字符串 TO 的编译产物里**不允许**出现字符串型的 GREATER_EQUAL / LESS_EQUAL
     * （那两个运算符带官方 bug，区间判定必须绕开）。同时必须存在 `AND(NOT(lo > target), NOT(target > hi))`
     * 形态（[com.eratw.port.core.expr.strCaseRange] 的装配）。
     * **改前实际**：这里能扫到 `Binary(GREATER_EQUAL, STR)` 与 `Binary(LESS_EQUAL, STR)` 各一条。
     */
    @Test
    fun `字符串CASE区间编译产物不用反向运算符`() {
        val p = compile(
            "@T",
            "SELECTCASE \"b\"",
            "CASE \"a\" TO \"c\"",
            "J = 1",
            "ENDSELECT",
        )
        val strCmpOps = HashSet<String>()
        var rangeShape = 0
        for (e in p.expressions()) {
            collectStrCompareOps(e, strCmpOps)
            if (isCaseRangeShape(e)) rangeShape++
        }
        assertTrue(
            "GREATER_EQUAL" !in strCmpOps && "LESS_EQUAL" !in strCmpOps,
            "字符串区间判定不得走 >=/<= 运算符（CaseExpression.cs:62-70 是独立判定），实扫到：$strCmpOps",
        )
        assertEquals(1, rangeShape, "TO 形态应装配出恰好一条 AND(NOT(>), NOT(>)) 区间判定")
    }

    /**
     * 运算符本体不动（ADR-005）：源码里真写 `>=` 的表达式位仍按官方 bug 读数 ——
     * `"b" >= "a"` 得 0、`"a" >= "b"` 得 1（spec: CaseExpression 之外的
     * OperatorMethodManager.cs:331 GreaterEqualStrStr 实为 `c < 0`；
     * specs/verify-notes.md §1 + 文末官方 bug 清单第 1 条）。
     * 这条是「没顺手把原版也修好」的证据。
     */
    @Test
    fun `运算符版字符串大于等于官方bug原样保留`() {
        val store = run(
            compile(
                "@T",
                "J = \"b\" >= \"a\"",
                "K = \"a\" >= \"b\"",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "字符串 `>=` 反向：\"b\" >= \"a\" 官方给 0（不许顺手修）")
        assertEquals(1L, intOf(store, "K"), "字符串 `>=` 反向：\"a\" >= \"b\" 官方给 1")
    }

    /**
     * 整数 TO 一支未动（CaseExpression.cs:45-60 的整型判定本来就是 `_GE`/`_LE` 直译）：
     * 区间内命中、区间外失配，读数与改前一致 ⇒ 基准不受本路影响的那一半证据。
     */
    @Test
    fun `整数CASE_TO判定口径不变`() {
        val hit = run(
            compile("@T", "SELECTCASE 2", "CASE 1 TO 3", "J = 1", "ENDSELECT"),
        )
        assertEquals(1L, intOf(hit, "J"), "整数区间内命中（与改前一致）")
        val miss = run(
            compile("@T", "SELECTCASE 1100", "CASE 1 TO 500", "J = 1", "ENDSELECT"),
        )
        assertEquals(0L, intOf(miss, "J"), "整数超上界失配（与改前一致）")
    }

    // ------------------------------------------------------------------
    // CORE-03：FOR 非常量步长只在 FOR 行求值一次
    // ------------------------------------------------------------------

    /**
     * 原版：步长在 FOR 行取一次快照（spec: FunctionIdentifier.cs:3005），体内 `K = 3` 不改已开循环
     * ⇒ 步长恒 2，I = 0,2,4,6,8,10 共 5 轮、终值 10（排他界 :3401-3403）。
     * **改前实际**：J = 4、I = 12（NEXT 的步进语句重读 K，入口判定也重读）。
     */
    @Test
    fun `非常量步长只在FOR行求值一次`() {
        val store = run(
            compile(
                "@T",
                "K = 2",
                "FOR I, 0, 10, K",
                "K = 3",
                "J = J + 1",
                "NEXT",
            ),
        )
        assertEquals(5L, intOf(store, "J"), "步长快照 = 2 ⇒ 体恰 5 轮（isa.yaml A9:1670-1671）")
        assertEquals(10L, intOf(store, "I"), "终值 = 10（排他上界，spec: FunctionIdentifier.cs:3403）")
        assertEquals(3L, intOf(store, "K"), "体内的改写照旧落库，只是不影响步长")
    }

    /**
     * 快照的**符号**也固定：正步长 1 开局，体内把步长变量改成 -1 ⇒ 原版仍按 +1 跑到 I = 6、共 6 轮。
     * **改前实际**：第二轮起入口判定按当轮 K = -1 走负步长分支，J = 1、I = -1 就出体。
     */
    @Test
    fun `非常量步长的方向按FOR行快照`() {
        val store = run(
            compile(
                "@T",
                "K = 1",
                "FOR I, 0, 6, K",
                "K = -1",
                "J = J + 1",
                "NEXT",
            ),
        )
        assertEquals(6L, intOf(store, "J"), "方向取 FOR 行的快照 ⇒ 6 轮")
        assertEquals(6L, intOf(store, "I"), "终值 = 6")
        assertEquals(-1L, intOf(store, "K"), "体内写的 -1 只落变量，不改 LoopStep 快照")
    }

    /**
     * 零步长（非常量形态）：原版入口判定两个析取同时为真 ⇒ 循环体 0 次，计数变量仍被
     * Start 初始化（spec: FunctionIdentifier.cs:3003 与 :3006）。改前后一致，作为
     * 非常量分支（快照槽 + 零值基准槽都在 holdSlots 里）的回归锁。
     */
    @Test
    fun `零步长循环体零次且计数器仍初始化`() {
        val store = run(
            compile(
                "@T",
                "K = 0",
                "FOR I, 0, 6, K",
                "J = J + 1",
                "NEXT",
            ),
        )
        assertEquals(0L, intOf(store, "J"), "step == 0 ⇒ 恒跳过循环体（FunctionIdentifier.cs:3006）")
        assertEquals(0L, intOf(store, "I"), "计数器 = Start（FunctionIdentifier.cs:3003）")
    }

    /**
     * 负步长的非常量形态（`FOR I, 0, -6, K`，K = -1）：I = 0,-1,…,-5 共 6 轮、终值 -6
     * （spec: FunctionIdentifier.cs:3403 的 `step < 0 && end < cnt` 支）。
     */
    @Test
    fun `非常量负步长按快照排他收口`() {
        val store = run(
            compile(
                "@T",
                "K = -1",
                "FOR I, 0, -6, K",
                "J = J + 1",
                "NEXT",
            ),
        )
        assertEquals(6L, intOf(store, "J"), "负步长 6 轮（I = 0..-5）")
        assertEquals(-6L, intOf(store, "I"), "终值 = end（排他）")
    }

    /**
     * BREAK 前的那一步也用 FOR 行快照（原版怪行为：spec: FunctionIdentifier.cs:3312-3317
     * `instructionLine.LoopCounter.ChangeValue(instructionLine.LoopStep, exm)`）：
     * 步长快照 = 2，第一轮就 BREAK ⇒ I = 0 + 2 = 2、J = 1；体内 `K = 5` 不参与。
     */
    @Test
    fun `BREAK的步进也用步长快照`() {
        val store = run(
            compile(
                "@T",
                "K = 2",
                "FOR I, 0, 10, K",
                "K = 5",
                "J = J + 1",
                "BREAK",
                "NEXT",
            ),
        )
        assertEquals(1L, intOf(store, "J"), "第一轮即 BREAK")
        assertEquals(2L, intOf(store, "I"), "BREAK 跳出前加的是快照步长 2（FunctionIdentifier.cs:3316）")
    }

    /**
     * 结构锁①：非常量步长的**求值点必须落在回边目标之前**（= 只在 FOR 行执行一次）。
     * 回边 = 目标 pc 不大于自身的唯一 `_JUMP`（compileNext 的 backJump）；旧实现把
     * `evalTemp(step)` 发在 headPc **之后**，于是每轮 NEXT 都重求一次。
     */
    @Test
    fun `步长求值点在回边目标之前`() {
        val p = compile(
            "@T",
            "K = 2",
            "FOR I, 0, 10, K",
            "J = J + 1",
            "NEXT",
        )
        val backPcs = (0 until p.instructionCount).filter { pc ->
            val ins = p.instructionAt(pc)
            ins.op == Opcode._JUMP && pc + 1 + ins.a <= pc   // 目标不晚于自身 = 回边
        }
        assertEquals(1, backPcs.size, "FOR 恰有一条回边，实扫：$backPcs")
        val headPc = backPcs.single() + 1 + p.instructionAt(backPcs.single()).a
        val stepEvals = stepEvalSites(p)
        assertEquals(1, stepEvals.size, "步长表达式只应有 FOR 行那一条 `_EVAL_INT`")
        assertTrue(
            stepEvals.single().first < headPc,
            "步长求值点 pc=${stepEvals.single().first} 必须严格早于回边目标 headPc=$headPc（否则逐轮重求）",
        )
    }

    /**
     * 结构锁②：步进语句（`[FOR STEP]`）的右值是**步长快照槽哨兵**，不是原步长表达式 ——
     * 形态与 [compileMethod] 写 RESULT 那条一致（[EXPR_TEMP_PREFIX]）。
     * 旧实现在这里直接放 `VarRef(K)`，于是 NEXT 每轮重读变量。
     * 同时步进语句仍是「整式 + 落库」形态（非常量无法折成常量增量），**没有**退回「term + op」。
     */
    @Test
    fun `步进语句读FOR行的步长快照槽`() {
        val p = compile(
            "@T",
            "K = 2",
            "FOR I, 0, 10, K",
            "J = J + 1",
            "NEXT",
        )
        val (evalPc, snapshotSlot) = stepEvalSites(p).single()
        assertTrue(
            !SlotAddr.isGlobal(snapshotSlot) && !SlotAddr.isNone(snapshotSlot),
            "快照槽必须是帧内局部槽，实得 raw=$snapshotSlot（求值点 pc=$evalPc）",
        )
        val stepStmt = p.statements().filterIsInstance<AssignStmt>()
            .firstOrNull { it.dest.name == "I" && it.op == OperatorCode.PLUS }
            ?: error("语句池里找不到 FOR 的步进赋值：${p.statements()}")
        val sp = stepStmt.args as? SpSetArgument ?: error("步进赋值形态应为 SpSetArgument：${stepStmt.args}")
        assertTrue(!sp.isConst, "非常量步长的快照不是编译期常量，不得折成 addConst")
        val term = sp.term as? Binary ?: error("步进项应是整式 dest + 快照，实得 ${sp.term}")
        assertEquals(OperatorCode.PLUS, term.op, "整式仍是 `I + step`（ArgumentParser.cs:1092-1096 同形）")
        assertEquals("I", (term.left as? VarRef)?.name, "整式左端是循环变量本体")
        val rhs = term.right as? FuncCall ?: error("整式右端应是快照槽哨兵 FuncCall，实得 ${term.right}")
        assertEquals(snapshotSlot, exprTempSlotOf(rhs.name), "右端哨兵必须指向 FOR 行那条求值的目的槽")
        assertTrue(rhs.args.isEmpty(), "哨兵是零参读槽，实得 ${rhs.args}")
        assertEquals(OperandType.INT, rhs.operandType)
        assertTrue(
            collectVarNames(term).none { it == "K" },
            "步进语句里不得再出现步长变量本体（否则又变回逐轮重读）：${collectVarNames(term)}",
        )
    }

    /**
     * 常量步长的既有形态不许动（契约 §4 L5 的「特别注意 2」）：`FOR I, 0, 10, 2` 的步进语句
     * 仍是 `SpSetArgument(isConst && addConst, constInt = 2)`，term 为空
     * （spec: ArgumentParser.cs:1073-1090；执行层只读形态、不再按 op 重算）。
     * 负常量 `-1` 折成 `constInt = -1`（解析形态是 `Unary(MINUS, 1)`）。
     */
    @Test
    fun `常量步长仍是常量增量形态`() {
        val pos = compile("@T", "FOR I, 0, 10, 2", "J = J + 1", "NEXT")
        val posStep = pos.statements().filterIsInstance<AssignStmt>()
            .first { it.dest.name == "I" && it.op == OperatorCode.PLUS }
        val posSp = posStep.args as SpSetArgument
        assertTrue(posSp.isConst && posSp.addConst, "正常量步长必须走 isConst/addConst 形态")
        assertEquals(2L, posSp.constInt)
        assertNull(posSp.term, "常量增量形态不带整式项")

        val neg = compile("@T", "FOR I, 10, 0, -1", "J = J + 1", "NEXT")
        val negSp = neg.statements().filterIsInstance<AssignStmt>()
            .first { it.dest.name == "I" && it.op == OperatorCode.PLUS }.args as SpSetArgument
        assertTrue(negSp.isConst && negSp.addConst, "负常量步长同样走常量增量形态")
        assertEquals(-1L, negSp.constInt, "`-1` 解析成 Unary(MINUS, 1)，快照取 -1")

        // 行为侧一并锁住：常量步长的读数与改前一致（负步长排他界：I = 10..1 共 10 轮、终值 0，
        // spec: FunctionIdentifier.cs:3403 与 specs/isa.yaml A9:1677）。
        val store = run(neg)
        assertEquals(10L, intOf(store, "J"), "FOR I,10,0,-1 跑 10 轮")
        assertEquals(0L, intOf(store, "I"), "终值 = end（排他）")
    }

    // ------------------------------------------------------------------
    // 遍历辅助
    // ------------------------------------------------------------------

    /**
     * 步长表达式的求值点：`_EVAL_INT`（a=表达式池下标、b=目的槽）且表达式正是 `VarRef("K")`。
     * 只认这一条形态 —— `K = 2` / `K = 3` 都是赋值语句（走语句池 marker，不落 `_EVAL_INT`），
     * 入口判定读的是快照槽本身（操作数就是那个槽，不再出现 `VarRef(K)`）⇒ 全程序恰一条。
     */
    private fun stepEvalSites(p: Program): List<Pair<Int, Int>> =
        (0 until p.instructionCount).mapNotNull { pc ->
            val ins = p.instructionAt(pc)
            if (ins.op != Opcode._EVAL_INT) return@mapNotNull null
            val e = p.expr(ins.a)
            val vr = e as? VarRef ?: return@mapNotNull null
            if (vr.name != "K") return@mapNotNull null
            pc to ins.b
        }

    /** 收集表达式里出现的全部变量名（含下标表达式）。 */
    private fun collectVarNames(e: Expr): List<String> {
        val out = ArrayList<String>()
        collectVarsInto(e, out)
        return out
    }

    private fun collectVarsInto(x: Expr, out: MutableList<String>) {
        when (x) {
            is VarRef -> { out.add(x.name); x.indices.forEach { collectVarsInto(it, out) } }
            is Binary -> { collectVarsInto(x.left, out); collectVarsInto(x.right, out) }
            is Unary -> collectVarsInto(x.operand, out)
            is UnaryAfter -> collectVarsInto(x.operand, out)
            is FuncCall -> x.args.filterNotNull().forEach { collectVarsInto(it, out) }
            is KeywordIndexArg -> collectVarsInto(x.expr, out)
            else -> Unit   // 字面量 / NullTerm / Ternary / FormStringTerm：本组用例不涉及
        }
    }

    /** 表达式里所有**字符串型操作数**的比较运算符名（CORE-01 的结构锁取数面）。 */
    private fun collectStrCompareOps(e: Expr, into: MutableSet<String>) {
        when (e) {
            is Binary -> {
                val cmp = e.op == OperatorCode.EQUAL || e.op == OperatorCode.NOT_EQUAL ||
                    e.op == OperatorCode.GREATER || e.op == OperatorCode.LESS ||
                    e.op == OperatorCode.GREATER_EQUAL || e.op == OperatorCode.LESS_EQUAL
                if (cmp && e.left.operandType == OperandType.STR && e.right.operandType == OperandType.STR) {
                    into.add(e.op.name)
                }
                collectStrCompareOps(e.left, into)
                collectStrCompareOps(e.right, into)
            }
            is Unary -> collectStrCompareOps(e.operand, into)
            is UnaryAfter -> collectStrCompareOps(e.operand, into)
            is FuncCall -> e.args.filterNotNull().forEach { collectStrCompareOps(it, into) }
            is KeywordIndexArg -> collectStrCompareOps(e.expr, into)
            else -> Unit
        }
    }

    /** 是否为 `AND(NOT(a > b), NOT(c > d))` 的区间判定形态（[com.eratw.port.core.expr.strCaseRange]）。 */
    private fun isCaseRangeShape(e: Expr): Boolean {
        if (e !is Binary || e.op != OperatorCode.AND) return false
        val l = (e.left as? Unary)?.operand as? Binary ?: return false
        val r = (e.right as? Unary)?.operand as? Binary ?: return false
        if (l.op != OperatorCode.GREATER || r.op != OperatorCode.GREATER) return false
        if ((e.left as Unary).op != OperatorCode.NOT) return false
        if ((e.right as Unary).op != OperatorCode.NOT) return false
        // 下界那条左边是 CASE 的下界端点、右边是目标哨兵读；上界那条反过来（CaseExpression.cs:66/:68 的实参序）
        val targetIsRight = l.right is FuncCall && exprStrTempSlotOf((l.right as FuncCall).name) != null
        val targetIsLeft = r.left is FuncCall && exprStrTempSlotOf((r.left as FuncCall).name) != null
        return targetIsRight && targetIsLeft
    }
}
