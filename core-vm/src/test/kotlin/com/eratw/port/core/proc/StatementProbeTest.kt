package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 语句探针（CONTRACT-DBG1 路1）：[ProcessRuntime.statementProbe] 注入点的四条契约——
 * (a) probe == null（生产缺省）时既有语义零变化；(b) 回调字段口径；(c) CodeEE 路径
 * error/outcome 互斥 + 原异常照常上抛；(d) 被跳过的语句不回调；(e) 探针自身抛错不影响
 * 语句语义；(f) Stop/Unwind 出口也回调。
 *
 * 全部用合成小程序（Assembler 手工字节码 + 真 sem 语句实例），不依赖真语料与 ERATW_* 环境变量。
 */
class StatementProbeTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 环境快照：env 是 VM 复用的可变对象（StmtEnv KDoc），回调瞬间必须抄走四个字段——
     *  这正是「回调实现不得持有 env 引用」要求的落地形态。
     *
     *  T2-64 波次 C：回调签名带 `op`（FunctionCode.ordinal）与 `index`（语句池下标），
     *  不再带语句实例；要树的实现自己按 index 取。 */
    private class RecordingProbe : StatementProbe {
        class Record(
            val op: Int,
            val index: Int,
            val functionKey: String,
            val pc: Int,
            val line: Int,
            val functionName: String?,
            val outcome: StmtOutcome?,
            val error: Throwable?,
            val elapsedNs: Long,
        )

        val records = ArrayList<Record>()
        var failEveryCall = false

        override fun onStatement(
            op: Int,
            index: Int,
            env: StmtEnv,
            outcome: StmtOutcome?,
            error: Throwable?,
            elapsedNs: Long,
        ) {
            if (failEveryCall) throw IllegalStateException("探针自身故障")
            records.add(
                Record(op, index, env.functionKey, env.pc, env.line, env.functionName, outcome, error, elapsedNs),
            )
        }
    }

    /** 直调钩子用的只读 env（VM 真路径走 envAt 的可复用实现，此处口径同 StatementBridgeTest）。 */
    private class TestEnv(
        override val functionKey: String = "MAIN",
        override val pc: Int = -1,
        override val line: Int = -1,
        override val functionName: String? = "main",
    ) : StmtEnv

    private fun literalStr(s: String) = ExpressionArgument(StrLiteral(s))

    private fun printStmtOf(code: FunctionCode, args: Argument): PrintStmt =
        PrintStmt(PrintSpec.parse(code)!!, args, 1, code.name)

    private fun runtime(program: Program): ProcessRuntime =
        ProcessRuntime(program, VariableStore(), FunctionTable(emptyList()))

    /** `PRINTL hello; _HALT`（语句池下标 0 = PRINTL）。 */
    private fun helloProgram(stmt: Statement): Program = Assembler.assemble(
        """
        PRINTL stmt#0
        _HALT
        """.trimIndent(),
        statements = listOf(stmt),
    )

    /** `THROW boom`（语句池下标 1；文本第 2 行 → marker line=2、pc=1）。 */
    private fun throwProgram(throwStmt: Statement, printStmt: Statement): Program = Assembler.assemble(
        """
        PRINTL stmt#0
        THROW stmt#1
        _PRINT_LITERAL "never" 1
        _HALT
        """.trimIndent(),
        statements = listOf(printStmt, throwStmt),
    )

    /** SIF 降级形状（compileSif → emitJumpIfFalse，补跳目标 = 紧随一条语句之后）手工复刻。 */
    private fun sifProgram(cond: Long, printStmt: Statement): Program = Assembler.assemble(
        """
        _SET_INT R0, $cond
        _JUMP_IF_FALSE R0, skipped
        PRINTL stmt#0
        skipped:
        _PRINT_LITERAL "end" 1
        _HALT
        """.trimIndent(),
        statements = listOf(printStmt),
    )

    // ------------------------------------------------------------------
    // (a) probe == null（生产缺省）：既有语义零变化
    // ------------------------------------------------------------------

    @Test
    fun `探针缺席与在场 输出与返回值完全一致`() {
        // 基准：不加探针时代的输出（同一装配路径，先跑一遍钉住期望值）
        val bare = runtime(helloProgram(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))))
        assertEquals(0L, bare.execute())
        val expected = bare.output.toString()
        assertEquals("hello\n", expected)

        // 在场：换一条全新装配（避免跨会话共享任何状态），输出必须逐字节一致
        val probe = RecordingProbe()
        val withProbe = runtime(helloProgram(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))))
        withProbe.statementProbe = probe
        assertEquals(0L, withProbe.execute())
        assertEquals(expected, withProbe.output.toString())
        assertEquals(1, probe.records.size, "每条真实语句恰一次回调")
    }

    // ------------------------------------------------------------------
    // (b) 回调字段口径：op / index / line / functionKey / outcome
    // ------------------------------------------------------------------

    @Test
    fun `探针收到正确的 op index line functionKey 与 Normal outcome`() {
        val stmt = printStmtOf(FunctionCode.PRINTL, literalStr("hello"))
        val probe = RecordingProbe()
        val rt = runtime(helloProgram(stmt))
        rt.statementProbe = probe
        rt.execute()

        val r = probe.records.single()
        assertEquals(FunctionCode.PRINTL.ordinal, r.op, "op = 语句的 FunctionCode.ordinal（扁平池槽 1 同口径）")
        assertEquals(0, r.index, "index = 语句池下标（本程序只有 PRINTL 一条语句）")
        assertSame(stmt, rt.program.statement(r.index), "按下标取回的必须是同一条语句（池身份）")
        assertEquals("MAIN", r.functionKey) // envAt：函数名大写归一（Vm.kt:1001）
        assertEquals(1, r.line) // marker 在汇编文本第 1 行
        assertEquals(0, r.pc) // 隐式 main 首条指令
        assertEquals("main", r.functionName)
        assertSame(StmtOutcome.Normal, r.outcome)
        assertNull(r.error)
        assertTrue(r.elapsedNs >= 0, "耗时应为非负 nanoTime 差，实际 ${r.elapsedNs}")
    }

    // ------------------------------------------------------------------
    // (c) CodeEE 路径：error 非空 / outcome 为 null / 原异常照常上抛
    // ------------------------------------------------------------------

    @Test
    fun `CodeEE 路径 error 非空 outcome 为 null 且原异常照常上抛`() {
        val printStmt = printStmtOf(FunctionCode.PRINTL, literalStr("hello"))
        val throwStmt = InstructionStmt(FunctionCode.THROW, literalStr("boom"), 1, "THROW")
        val probe = RecordingProbe()
        val rt = runtime(throwProgram(throwStmt, printStmt))
        rt.statementProbe = probe

        val e = assertFailsWith<CodeEE> { rt.execute() }
        // 原异常类型与 message 不变；位置章照旧补齐（stampPosition：sem 抛的不带位置 → env 补）
        assertEquals("boom", e.message)
        assertEquals("main", e.funcName)
        assertEquals(2, e.line)
        assertEquals(1, e.pc)

        // 探针两条：hello(Normal) → THROW(error)。error 与上抛实例同一、outcome 为 null
        assertEquals(2, probe.records.size)
        assertNull(probe.records[0].error)
        val errRecord = probe.records[1]
        assertNull(errRecord.outcome, "error != null 时 outcome 必须为 null")
        assertSame(e, errRecord.error)
        assertEquals("boom", errRecord.error?.message)
        assertTrue(errRecord.elapsedNs >= 0)
        // 第一条已打印、后续 "never" 未执行
        assertEquals("hello\n", rt.output.toString())
    }

    // ------------------------------------------------------------------
    // (d) 被跳过的语句不回调
    // ------------------------------------------------------------------

    /**
     * 跳过的实现路径（核实记录）：SIF 在编译期降级为条件跳转——`compileSif`
     * （ErbProgramCompiler.kt:1035-1040）发 `_JUMP_IF_FALSE`，主循环把补跳目标 patch 到
     * 「紧随的一条语句之后」（ErbProgramCompiler.kt:838-893）；VM 执行 `_JUMP_IF_FALSE`
     * 直接改 pc（Vm.kt:600-602），被跳语句的 marker 指令根本不会到达唯一分派点
     * `host.executeStatement`（Vm.kt:769）。因此「跳过未执行」的语句天然不回调——
     * 探针挂在 executeStatement 上，不进 VM 控制流。用例按 compileSif 的发射形状手工复刻。
     */
    @Test
    fun `被 SIF 条件跳过的语句不回调`() {
        val stmt = printStmtOf(FunctionCode.PRINTL, literalStr("skipped"))
        val probe = RecordingProbe()
        val rt = runtime(sifProgram(cond = 0, printStmt = stmt))
        rt.statementProbe = probe

        assertEquals(0L, rt.execute())
        assertEquals("end\n", rt.output.toString(), "条件为假：紧随语句确实被跳过")
        assertTrue(probe.records.isEmpty(), "被跳过的语句不得回调，实际 ${probe.records}")
    }

    @Test
    fun `SIF 条件为真时语句照常执行并回调`() {
        // 对照组：同一跳转结构、条件为真 → marker 被分派 → 恰一次回调。
        // 证明 (d) 的空记录来自跳过语义，而不是装置漏接。
        val stmt = printStmtOf(FunctionCode.PRINTL, literalStr("skipped"))
        val probe = RecordingProbe()
        val rt = runtime(sifProgram(cond = 1, printStmt = stmt))
        rt.statementProbe = probe

        assertEquals(0L, rt.execute())
        assertEquals("skipped\nend\n", rt.output.toString())
        assertEquals(1, probe.records.size)
        assertSame(StmtOutcome.Normal, probe.records.single().outcome)
    }

    // ------------------------------------------------------------------
    // (e) 探针自身抛错：吞口，不影响语句语义
    // ------------------------------------------------------------------

    @Test
    fun `探针自身抛错 正常路径吞弃 语义不变`() {
        val probe = RecordingProbe().apply { failEveryCall = true }
        val rt = runtime(helloProgram(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))))
        rt.statementProbe = probe

        assertEquals(0L, rt.execute())
        assertEquals("hello\n", rt.output.toString())
    }

    @Test
    fun `探针自身抛错 失败路径原异常照常上抛 探针异常折进 suppressed 留痕`() {
        val printStmt = printStmtOf(FunctionCode.PRINTL, literalStr("hello"))
        val throwStmt = InstructionStmt(FunctionCode.THROW, literalStr("boom"), 1, "THROW")
        val probe = RecordingProbe().apply { failEveryCall = true }
        val rt = runtime(throwProgram(throwStmt, printStmt))
        rt.statementProbe = probe

        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("boom", e.message, "探针故障不得改写原异常的 message")
        assertEquals("main", e.funcName)
        assertEquals(1, e.suppressed.size, "探针异常应折进 suppressed 留痕")
        assertTrue(e.suppressed[0] is IllegalStateException)
    }

    // ------------------------------------------------------------------
    // (f) Stop / Unwind 出口也回调（Transfer 目前无生产者，见交付报告）
    // ------------------------------------------------------------------

    @Test
    fun `QUIT 的 Unwind 出口回调 Unwind0（fixcore A2 整链停止）`() {
        val quit = FlowStmt(FunctionCode.QUIT, VoidArgument(), 1, "QUIT")
        val probe = RecordingProbe()
        val rt = runtime(
            Assembler.assemble(
                """
                QUIT stmt#0
                _PRINT_LITERAL "never" 1
                _HALT
                """.trimIndent(),
                statements = listOf(quit),
            ),
        )
        rt.statementProbe = probe

        assertEquals(0L, rt.execute())
        assertEquals("", rt.output.toString())
        val outcomes = probe.records.map { it.outcome }
        // fixcore A2（2026-10-26）：QUIT 家族原版不做 state.Return（Process.cs:1307-1309），
        // 行泵当行退出 ⇒ 调用方与事件后续成员整链停止；出口从 Stop(0)（嵌套只拆一帧）改为
        // StmtOutcome.Unwind(0)（SAVEGAME/LOADGAME 同款整栈丢弃通道）。
        assertEquals(listOf<StmtOutcome?>(StmtOutcome.Unwind(0L)), outcomes, "QUIT → Unwind(0) 出口恰一次回调")
        assertNull(probe.records.single().error)
    }

    @Test
    fun `SAVEGAME 的 Unwind 出口回调 Unwind0`() {
        // 装置同 StatementBridgeTest 的 SAVEGAME 用例：缺省 systemState.canSave = true，
        // 门放行 → saveLoadHandoff 记账 → 整栈丢弃 Unwind(0L)。
        val probe = RecordingProbe()
        val rt = runtime(Assembler.assemble("_HALT"))
        rt.statementProbe = probe
        val saveGame = InstructionStmt(FunctionCode.SAVEGAME, VoidArgument(), 1, "SAVEGAME")

        rt.executeStatement(saveGame, TestEnv())

        assertEquals(StmtOutcome.Unwind(0L), probe.records.single().outcome)
        assertNull(probe.records.single().error)
        // 旧树通道（不经扁平池）拿不到池下标：op 照给，index 用哨兵（StatementProbe.NO_STMT_INDEX）。
        assertEquals(FunctionCode.SAVEGAME.ordinal, probe.records.single().op)
        assertEquals(StatementProbe.NO_STMT_INDEX, probe.records.single().index)
    }

    // ------------------------------------------------------------------
    // StatementExecutionCounter（覆盖率仪表的数据面）
    // ------------------------------------------------------------------

    @Test
    fun `计数器统计执行次数与 distinct 语句 重复执行按次数累计`() {
        // helloProgram = PRINTL(1 条语句) + _HALT(内部指令，不回调)。同一程序执行两轮：
        // executions 累计到 2、distinct 不变（pc 已在位集里）。
        val counter = StatementExecutionCounter()
        val rt = runtime(helloProgram(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))))
        rt.statementProbe = counter
        assertEquals(0L, rt.execute())
        assertEquals(1L, counter.executions)
        assertEquals(1, counter.distinctStatements())
        assertEquals(0L, rt.execute())
        assertEquals(2L, counter.executions)
        assertEquals(1, counter.distinctStatements())
    }

    @Test
    fun `计数器区间过滤 区间外的 pc 不计入`() {
        // 区间 [1, 2) 排除 pc=0（本程序唯一语句的 marker pc）。真实用法：区间 = 被测函数的
        // 指令区间，被 CALL 进来的其它函数（pc 在别的函数区间）不计入本函数覆盖率分子。
        val counter = StatementExecutionCounter(pcFromInclusive = 1, pcToExclusive = 2)
        val rt = runtime(helloProgram(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))))
        rt.statementProbe = counter
        assertEquals(0L, rt.execute())
        assertEquals(0L, counter.executions)
        assertEquals(0, counter.distinctStatements())
        assertTrue(counter.executedPcs.isEmpty)
    }
}
