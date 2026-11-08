package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.ArgInterner
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R7-L1「`codeBase` 偏移基座」的出口判据（CONTRACT-R7-B §2/L1 ①②③④）。
 *
 * ## 这个测试守的是什么
 *
 * R7 的目标是「玩家把口上 .ERB 丢进 `mods/` 就能进游戏」：设备端把补丁单独编译，装载期
 * 拼在 base 指令流尾部。波 A 的钉子 A1（`IncrementalCompileParityTest`）已经用代码证伪了
 * 「复用 base 的 builder 续编」这条路：`build()` 之后池被 `seal()` 封死（再 `addString` 抛
 * `IllegalStateException`），而语句池按引用共享给 `Program` ⇒ 续编会**反向污染**已建
 * `Program.statementCount`。于是补丁必须用**独立** builder，而独立 builder 的 pc / 池下标
 * 天生从 0 起 ⇒ 必须由 [CodeBuilder] 的基线参数在**编译期**把绝对下标算好。
 *
 * 四条判据（逐条对应本文件的用例组）：
 *
 * ① 零基线逐位等价：同一份「源码」（本文件用确定性发射序列当替身）编两次，code 段
 *    `contentEquals`、四个池计数与两个条数相等 —— 既有路径一个字节都不许动。
 * ② 带基线：`instructionCount == base + 新增`、`statementCount == M + 新增`，且指令里所有
 *    绝对下标（marker 的 `a`、`_SET_INT`/`_SET_STR` 的 `b`、`_EVAL_INT` 的 `a`、`_CALL` 的 `a`）
 *    都是 `base + 新增`；跳转操作数（相对偏移）**不受**基线影响。
 * ③ 不许反向污染：先用 builder A 建出 `p1`，再用**独立** builder B（`statementBase =
 *    p1.statementCount` 等）续编，断言 `p1` 的读数一个数都没变。
 * ④ 负向：基线为负、`codeBase` 不是 [Instruction.WORDS] 的整数倍 ⇒ 构造期**明确抛错**
 *    （不静默纠正），消息点名参数。
 *
 * 另加两组惰性池用例：[ConstantPool] 的 `lazyStatements` / `lazyExprs` 两条分支同样按
 * 绝对下标报数与取用（偏移不能只改 eager 那条路）。
 */
class CompileBaseTest {

    // ------------------------------------------------------------------
    // 夹具：一段确定性「源码」
    // ------------------------------------------------------------------

    /** [sample] 发射出来的各点绝对 pc 与实例（断言要用）。 */
    private class Emitted(
        val entryFunction: Int,
        val entryPc: Int,
        val calleeFunction: Int,
        val markerStmt: Statement,
        val markerPc: Int,
        val jumpPc: Int,
        val labelPc: Int,
        val callPc: Int,
    )

    /**
     * 同一份「源码」的确定性替身：常量 / 字符串 / 表达式 / 语句 marker / 前向跳转 / `_CALL`
     * / 标签 / 调用点元数据各一条，覆盖 [CodeBuilder] 里全部需要算绝对下标的出口。
     *
     * 两次调用逐字相同 ⇒ 可用它验「零基线逐位等价」。
     */
    private fun sample(b: CodeBuilder): Emitted {
        val entryPc = b.here()
        val entry = b.declareFunction("ENTRY", entryPc, arity = 0, slotCount = 4)
        b.emit(Opcode._SET_INT, 0, b.constant(7L), 0, 1)
        b.emit(Opcode._SET_STR, 1, b.string("口上"), 0, 2)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(42L, 2, 0)), 2, 0, 2)
        val jumpPc = b.emit(Opcode._JUMP, 0, 0, 0, 3)
        b.emit(Opcode._NOP, 0, 0, 0, 4)
        val labelPc = b.here()
        // 跳转偏移 = 目标绝对 pc - (本条绝对 pc + 1)：两端同处绝对空间，差值不受基线影响。
        b.patch(jumpPc, 1, labelPc - (jumpPc + 1))
        val markerStmt = RendStmt(VoidArgument(), 3, "REND")
        val markerPc = b.emit(Opcode.REND, b.statement(markerStmt), 0, SlotAddr.NONE, 3)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 4)

        val callee = b.declareFunction("CALLEE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 5)
        val callPc = b.emit(Opcode._CALL, callee, 0, SlotAddr.NONE, 6)
        b.declareLabel(entry, "LOOP", labelPc)
        b.declareCallArgs(callPc, listOf(CallArg(0, 0, false, null, true)))
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 7)
        return Emitted(entry, entryPc, callee, markerStmt, markerPc, jumpPc, labelPc, callPc)
    }

    /** [sample] 发射的指令条数（10 条：SET_INT/SET_STR/EVAL_INT/JUMP/NOP/REND/RET/RET/CALL/RET）。 */
    private val sampleInstructions = 10

    // ------------------------------------------------------------------
    // ① 零基线：逐位等价
    // ------------------------------------------------------------------

    @Test
    fun `零基线：同一份源码编两次 code 段与四个池逐位等价`() {
        val a = CodeBuilder()
        val ea = sample(a)
        val p1 = a.build(ea.entryFunction)

        val b = CodeBuilder()
        val eb = sample(b)
        val p2 = b.build(eb.entryFunction)

        assertContentEquals(p1.code, p2.code, "零基线两次编译的 code 段必须逐字相同")
        assertContentEquals(p1.lines, p2.lines, "行号表同样逐字相同")
        assertEquals(p1.instructionCount, p2.instructionCount)
        assertEquals(sampleInstructions, p1.instructionCount)
        assertEquals(p1.constantCount, p2.constantCount)
        assertEquals(p1.stringCount, p2.stringCount)
        assertEquals(p1.exprCount, p2.exprCount)
        assertEquals(p1.statementCount, p2.statementCount)
        // 四个池各 1 条（语句池只有 marker 那一条）。
        assertEquals(1, p1.constantCount)
        assertEquals(1, p1.stringCount)
        assertEquals(1, p1.exprCount)
        assertEquals(1, p1.statementCount)
    }

    @Test
    fun `零基线：六个基线访问器全为 0`() {
        val b = CodeBuilder()
        val e = sample(b)
        val p = b.build(e.entryFunction)
        assertEquals(0, p.codeBase)
        assertEquals(0, p.instructionBase)
        assertEquals(0, p.statementBase)
        assertEquals(0, p.stringBase)
        assertEquals(0, p.longBase)
        assertEquals(0, p.exprBase)
        assertEquals(0, p.functionBase)
        // 零基线时 pc 读面与改造前同口径（pc 就是数组下标）。
        assertEquals(Opcode._SET_INT, p.op(0))
        assertEquals(0, p.entryFunction)
    }

    // ------------------------------------------------------------------
    // ② 带基线：绝对下标 = base + 新增
    // ------------------------------------------------------------------

    @Test
    fun `带基线：指令与四个池的计数都是 base 加新增`() {
        val baseInstr = 12
        val b = CodeBuilder(
            codeBase = baseInstr * Instruction.WORDS,
            statementBase = 5,
            stringBase = 3,
            longBase = 9,
            exprBase = 2,
            functionBase = 100,
        )
        val e = sample(b)
        val p = b.build(e.entryFunction)

        assertEquals(baseInstr, p.instructionBase)
        assertEquals(baseInstr + sampleInstructions, p.instructionCount, "instructionCount 必须是绝对口径")
        assertEquals(5 + 1, p.statementCount)
        assertEquals(3 + 1, p.stringCount)
        assertEquals(9 + 1, p.constantCount)
        assertEquals(2 + 1, p.exprCount)

        // 基线本身如实暴露（装载期算补丁区间的依据）。
        assertEquals(baseInstr * Instruction.WORDS, p.codeBase)
        assertEquals(5, p.statementBase)
        assertEquals(3, p.stringBase)
        assertEquals(9, p.longBase)
        assertEquals(2, p.exprBase)
        assertEquals(100, p.functionBase)

        // 段内本体只含新增条目：不给 base 段补零（否则补丁产物凭空多出整段 base 的字节）。
        assertEquals(sampleInstructions * Instruction.WORDS, p.code.size)
        assertEquals(sampleInstructions, p.lines.size)
        assertEquals(2, p.functions.size)
    }

    @Test
    fun `带基线：指令里的绝对下标都是 base 加新增`() {
        val baseInstr = 12
        val b = CodeBuilder(
            codeBase = baseInstr * Instruction.WORDS,
            statementBase = 5,
            stringBase = 3,
            longBase = 9,
            exprBase = 2,
            functionBase = 100,
        )
        val e = sample(b)
        val p = b.build(e.entryFunction)

        // emit / here 返回的都是绝对 pc。
        assertEquals(baseInstr, e.entryPc)
        assertEquals(baseInstr + 5, e.labelPc)
        assertEquals(baseInstr + 5, e.markerPc)

        // marker 的 a = 语句池绝对下标；取回的是同一实例。
        assertEquals(5, p.a(e.markerPc), "marker 的 a 必须是 statementBase + 新增")
        assertSame(e.markerStmt, p.statement(p.a(e.markerPc)))

        // _SET_INT 的 b / _SET_STR 的 b / _EVAL_INT 的 a 各自带自己那条池的基线。
        assertEquals(9, p.b(baseInstr + 0), "常量下标必须是 longBase + 新增")
        assertEquals(7L, p.constant(p.b(baseInstr + 0)))
        assertEquals(3, p.b(baseInstr + 1), "字符串下标必须是 stringBase + 新增")
        assertEquals("口上", p.string(p.b(baseInstr + 1)))
        assertEquals(2, p.a(baseInstr + 2), "表达式下标必须是 exprBase + 新增")
        assertEquals(IntLiteral(42L, 2, 0), p.expr(p.a(baseInstr + 2)))

        // _CALL 的 a = 函数表绝对下标；函数表本体只含本段条目（列表无法开窗口）。
        // CALLEE 是本段第 2 个函数 ⇒ 100 + 1。
        assertEquals(101, p.a(e.callPc), "_CALL 的 a 必须是 functionBase + 新增")
        assertEquals(101, e.calleeFunction)

        // 跳转是相对偏移 ⇒ 基线不改变它（否则跨段跳转会整体错位）。
        assertEquals(1, p.a(e.jumpPc), "跳转偏移是相对量，基线不该影响它")
        assertEquals(e.labelPc, e.jumpPc + 1 + p.a(e.jumpPc))
    }

    @Test
    fun `带基线：函数入口 pc 与标签 pc 落在绝对 pc 空间`() {
        val baseInstr = 12
        val b = CodeBuilder(codeBase = baseInstr * Instruction.WORDS, functionBase = 100)
        val e = sample(b)
        val p = b.build(e.entryFunction)

        assertEquals(baseInstr, p.functions[0].entryPc, "entryPc 直接来自 emit，已是绝对 pc")
        assertEquals(e.labelPc, p.functions[0].labelPcs[0], "标签 pc 同样是绝对 pc")
        // 入口函数下标仍按既有口径（functions 的下标），绝对下标由 functionBase 提供。
        assertEquals(0, p.entryFunction)
        assertEquals(100, p.functionBase)
        // 绝对 pc 落在 [instructionBase, instructionCount) 内 —— ErbcWriter 的判界据此成立。
        assertTrue(p.functions[0].entryPc in p.instructionBase until p.instructionCount)
        assertEquals(1, p.callArgsAt(e.callPc)?.size ?: -1, "调用点表按绝对 pc 登记")
    }

    @Test
    fun `带基线：pc 读面按绝对下标取值`() {
        val baseInstr = 12
        val b = CodeBuilder(codeBase = baseInstr * Instruction.WORDS, statementBase = 5)
        val e = sample(b)
        val p = b.build(e.entryFunction)

        assertEquals(Opcode._SET_INT, p.op(baseInstr + 0))
        assertEquals(Opcode._SET_STR, p.op(baseInstr + 1))
        assertEquals(Instruction(Opcode.REND, 5, 0, SlotAddr.NONE), p.instructionAt(e.markerPc))
        assertEquals(3, p.lineOf(e.markerPc), "行号表按绝对 pc 折回本段序号")
        assertEquals(-1, p.lineOf(baseInstr - 1), "base 段不归本 Program，按越界口径回落 -1")
    }

    // ------------------------------------------------------------------
    // ③ 不许反向污染（A1 抓到的坑）
    // ------------------------------------------------------------------

    @Test
    fun `不许反向污染：独立 builder 带 statementBase 续编，已建 Program 读数一个不变`() {
        // 先建 p1（builder A 自己的池，build 后已被 seal 封死）。
        val a = CodeBuilder()
        val ea = sample(a)
        val p1 = a.build(ea.entryFunction)
        val stmtBefore = p1.statementCount
        val instrBefore = p1.instructionCount
        val stringBefore = p1.stringCount
        val constantBefore = p1.constantCount
        val exprBefore = p1.exprCount
        val codeBefore = p1.code.copyOf()

        // 补丁必须用**独立** builder B：基线取 p1 的绝对计数，池是 B 自己的（不与 p1 共享）。
        val b = CodeBuilder(
            codeBase = p1.instructionCount * Instruction.WORDS,
            statementBase = p1.statementCount,
            stringBase = p1.stringCount,
            longBase = p1.constantCount,
            exprBase = p1.exprCount,
            functionBase = p1.functions.size,
        )
        val eb = sample(b)
        val p2 = b.build(eb.entryFunction)

        assertEquals(stmtBefore, p1.statementCount, "续编不得反向改变已建 Program 的 statementCount")
        assertEquals(instrBefore, p1.instructionCount, "续编不得反向改变已建 Program 的 instructionCount")
        assertEquals(stringBefore, p1.stringCount)
        assertEquals(constantBefore, p1.constantCount)
        assertEquals(exprBefore, p1.exprCount)
        assertContentEquals(codeBefore, p1.code, "已建 Program 的指令流一个字节都不许变")

        // 续编侧读数是 base + 新增（两次 sample 各 1 条语句 / 10 条指令）。
        assertEquals(stmtBefore + 1, p2.statementCount)
        assertEquals(instrBefore + sampleInstructions, p2.instructionCount)
        assertEquals(stringBefore + 1, p2.stringCount)
        // 续编段自己仍可正常取值（绝对下标）。
        assertSame(eb.markerStmt, p2.statement(p2.a(eb.markerPc)))
    }

    // ------------------------------------------------------------------
    // ④ 负向：明确抛错，不静默纠正
    // ------------------------------------------------------------------

    @Test
    fun `负向：基线为负构造期抛错并点名参数`() {
        val cases: List<Pair<String, () -> CodeBuilder>> = listOf(
            "codeBase" to { CodeBuilder(codeBase = -Instruction.WORDS) },
            "statementBase" to { CodeBuilder(statementBase = -1) },
            "stringBase" to { CodeBuilder(stringBase = -1) },
            "longBase" to { CodeBuilder(longBase = -1) },
            "exprBase" to { CodeBuilder(exprBase = -1) },
            "functionBase" to { CodeBuilder(functionBase = -1) },
        )
        for ((name, make) in cases) {
            val ex = assertFailsWith<IllegalArgumentException>("$name 为负必须抛错") { make() }
            val msg = ex.message ?: ""
            assertTrue(msg.contains(name), "$name 的错误消息必须点名参数，实际: $msg")
        }
    }

    @Test
    fun `负向：codeBase 不是 Instruction_WORDS 的整数倍构造期抛错`() {
        val ex = assertFailsWith<IllegalArgumentException>("codeBase 的单位是字，必须是 4 的整数倍") {
            CodeBuilder(codeBase = 6)
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("codeBase"), "错误消息必须点名 codeBase，实际: $msg")
        assertTrue(msg.contains("整数倍"), "错误消息必须说明是整数倍约束，实际: $msg")
        // 合法值不抛（防止把检查写成恒抛）。
        CodeBuilder(codeBase = 8)
        CodeBuilder(codeBase = 0)
    }

    // ------------------------------------------------------------------
    // 惰性池两条分支（lazyStatements / lazyExprs）也要按绝对下标
    // ------------------------------------------------------------------

    /** 惰性语句池的最小桩：按下标直接取，用于验 `statementBase` 的折回。 */
    private class StubStatementPool(private val items: List<Statement>) : LazyStatementPool {
        override val totalCount: Int get() = items.size
        override val materializedCount: Int get() = items.size
        override fun statementAt(index: Int): Statement = items[index]
        override fun releaseRaws() = Unit
        override fun seedInterning(roots: List<Expr?>): ArgInterner.Stats = ArgInterner.Stats(0, 0, 0, 0, 0L)
        override fun buildFlatStatementsRange(from: Int, until: Int): FlatStatements =
            FlatStatements.encode(items.subList(from, until), emptyList())
    }

    /** 惰性表达式池的最小桩。 */
    private class StubExprSource(private val nodes: List<Expr>) : LazyExprSource {
        override val nodeCount: Int get() = nodes.size
        override fun node(index: Int): Expr = nodes[index]
    }

    @Test
    fun `惰性语句池：statementBase 参与计数与取用`() {
        val s0 = RendStmt(VoidArgument(), 1, "REND")
        val s1 = RendStmt(VoidArgument(), 2, "REND")
        val pool = ConstantPool(statementBase = 7)
        pool.attachLazyStatementPool(StubStatementPool(listOf(s0, s1)))

        assertEquals(9, pool.statementCount, "惰性档的条数同样是 statementBase + 段声明条数")
        assertSame(s0, pool.statementAt(7), "绝对下标 7 折回本池序号 0")
        assertSame(s1, pool.statementAt(8))
        assertEquals(2, pool.statementList.size, "整池快照只含本池条目，不从绝对下标 0 起扫")
        assertFailsWith<IndexOutOfBoundsException>("base 以下的下标归 base 池，不许静默回落") {
            pool.statementAt(6)
        }
        // 惰性池不接受追加（既有契约不变）。
        assertFailsWith<IllegalStateException> { pool.addStatement(s0) }
    }

    @Test
    fun `惰性表达式池：exprBase 参与计数与取用`() {
        val e0 = IntLiteral(1L)
        val e1 = IntLiteral(2L)
        val pool = ConstantPool(exprBase = 4)
        pool.attachLazyExpressions(StubExprSource(listOf(e0, e1)))

        assertEquals(6, pool.exprCount, "惰性档的条数同样是 exprBase + 段声明条数")
        assertSame(e0, pool.exprAt(4), "绝对下标 4 折回本池序号 0")
        assertSame(e1, pool.exprAt(5))
        assertEquals(listOf<Expr>(e0, e1), pool.exprList, "整池快照只含本池条目，不从绝对下标 0 起扫")
        assertFailsWith<IndexOutOfBoundsException>("base 以下的下标归 base 池，不许静默回落") {
            pool.exprAt(3)
        }
    }

    @Test
    fun `eager 池：add 返回绝对下标且读面按绝对下标折回`() {
        val pool = ConstantPool(longBase = 10, stringBase = 20, exprBase = 30, statementBase = 40)
        val l = pool.addLong(5L)
        val s = pool.addString("x")
        val x = pool.addExpr(IntLiteral(9L))
        val st = pool.addStatement(RendStmt(VoidArgument(), 0, "REND"))

        assertEquals(10, l)
        assertEquals(20, s)
        assertEquals(30, x)
        assertEquals(40, st)
        assertEquals(11, pool.longCount)
        assertEquals(21, pool.stringCount)
        assertEquals(31, pool.exprCount)
        assertEquals(41, pool.statementCount)
        assertEquals(5L, pool.longAt(l))
        assertEquals("x", pool.stringAt(s))
        assertSame(pool.exprAt(x), pool.exprAt(30))
        assertFailsWith<IndexOutOfBoundsException> { pool.longAt(9) }
    }
}
