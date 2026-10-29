package com.eratw.port.core.sem

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.stmt.WhileStmt
import com.eratw.port.core.vars.CharacterTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T2-64 波次 C：`StatementExecutor.execute(source, index, ctx)` 全量直读的**双路等价**闸。
 *
 * 判据不是「输出长什么样」，而是**同一初始状态**下两条路径完全一致：
 *
 * - `ExecResult`（含异常类型与文案 —— 错误保真）；
 * - 控制台事件序列、输入请求序列、任意键等待调用；
 * - `skipPrint` / `mesSkip` / `saveSkip`、让出边界计数、`SystemOps` 调用序列；
 * - 抽样变量写面（数值 / 字符串）。
 *
 * 旧树路径（`execute(stmt, ctx)`）是参照系：它在本波次按契约原样保留（已标 `@Deprecated`），
 * 收尾提交才删除。数据节的两条语句（PRINTDATA / STRDATA）例外 —— 旧重载在波次 C 过渡期
 * 已改为抛 `UnsupportedOperationException`（契约 T2-64-C §2.2），故这两条只断言新路径行为。
 */
class StatementExecutorFlatTest {

    // ------------------------------------------------------------------
    // 扁平读面（测试用实现：直读 FlatStatements，不经过 Program）
    // ------------------------------------------------------------------

    private class FlatSource(val flat: FlatStatements) : FlatStmtSource {
        /** 新路径不得触达的读点（旧树兜底）：命中即计数，末端断言为 0。 */
        var statementAtCalls = 0

        override fun opAt(index: Int): Int = flat.opAt(index)

        override fun headAt(index: Int): Int = flat.headAt(index)

        override fun argStreamBase(): IntArray = flat.stream

        override fun exprAt(poolIdx: Int): Expr? = if (poolIdx == ErbcFormat.NONE_REF) null else flat.exprAt(poolIdx)

        override fun stringAt(poolIdx: Int): String = flat.strAt(poolIdx)

        override fun statementAt(index: Int): Statement {
            statementAtCalls++
            return flat.statementAt(index)
        }
    }

    /**
     * 固定数据节（键 = 语句池下标）。
     *
     * 过渡期说明：本地 worktree 的 `DataSections` 仍以语句实例为唯一抽象成员（正式版由 lane C
     * 换成语句池下标键，见契约 T2-64-C §2.2），故这里同时覆写两个重载；收尾删除旧重载时，
     * 删掉 `groupsOf(stmt)` 这一行与 `@Suppress` 即可（其余不动）。
     */
    private fun sections(groups: List<List<Expr>>): DataSections = object : DataSections {
        override fun groupsOf(stmtIndex: Int): List<List<Expr>>? = groups

        @Suppress("DEPRECATION")
        override fun groupsOf(stmt: Statement): List<List<Expr>>? = groups
    }

    /** 记录调用序列的 `SystemOps` 假实现（两路各一份，序列必须逐字一致）。 */
    private class RecordingOps : SystemOps {
        val calls = mutableListOf<String>()

        override fun saveData(no: Int, text: String): Boolean {
            calls.add("saveData($no,$text)")
            return true
        }

        override fun loadData(no: Int) {
            calls.add("loadData($no)")
        }

        override fun delData(no: Int) {
            calls.add("delData($no)")
        }

        override fun saveGame() {
            calls.add("saveGame")
        }

        override fun loadGame() {
            calls.add("loadGame")
        }

        override fun saveGlobal() {
            calls.add("saveGlobal")
        }

        override fun loadGlobal(): Boolean {
            calls.add("loadGlobal")
            return true
        }

        override fun saveChara(nos: List<Long>, filename: String, text: String) {
            calls.add("saveChara($nos,$filename,$text)")
        }

        override fun loadChara(filename: String) {
            calls.add("loadChara($filename)")
        }

        override fun updateCheck(): Long {
            calls.add("updateCheck")
            return 3
        }

        override fun callTrain(no: Long) {
            calls.add("callTrain($no)")
        }

        override fun stopCallTrain() {
            calls.add("stopCallTrain")
        }

        override fun doTrain(no: Long) {
            calls.add("doTrain($no)")
        }

        override fun upcheck() {
            calls.add("upcheck")
        }

        override fun cupcheck(target: Long) {
            calls.add("cupcheck($target)")
        }
    }

    // ------------------------------------------------------------------
    // 双路对拍装置
    // ------------------------------------------------------------------

    /** 执行结果或异常的稳定描述（异常类型 + 文案逐字比较 = 错误保真闸）。 */
    private fun capture(run: () -> ExecResult): String = try {
        "ok:${run()}"
    } catch (e: Throwable) {
        "${e::class.simpleName}:${e.message}"
    }

    private fun compare(oldEnv: SemTestEnv, newEnv: SemTestEnv, stmt: Statement): Pair<SemTestEnv, SemTestEnv> {
        val source = FlatSource(FlatStatements.encode(listOf(stmt), emptyList()))
        val oldOut = capture { oldEnv.executor.execute(stmt, oldEnv.ctx) }
        val newOut = capture { newEnv.executor.execute(source, 0, newEnv.ctx) }
        assertEquals(oldOut, newOut, "ExecResult / 异常不一致：${stmt.code}")
        assertEquals(oldEnv.console.events, newEnv.console.events, "控制台事件不一致：${stmt.code}")
        assertEquals(oldEnv.input.requests, newEnv.input.requests, "输入请求不一致：${stmt.code}")
        assertEquals(oldEnv.input.anyKeyCalls, newEnv.input.anyKeyCalls, "任意键等待不一致：${stmt.code}")
        assertEquals(oldEnv.ctx.skipPrint, newEnv.ctx.skipPrint, "skipPrint 不一致：${stmt.code}")
        assertEquals(oldEnv.ctx.mesSkip, newEnv.ctx.mesSkip, "mesSkip 不一致：${stmt.code}")
        assertEquals(oldEnv.ctx.saveSkip, newEnv.ctx.saveSkip, "saveSkip 不一致：${stmt.code}")
        assertEquals(oldEnv.executor.yieldBoundaryCount, newEnv.executor.yieldBoundaryCount, "让出边界不一致：${stmt.code}")
        assertEquals(0, source.statementAtCalls, "新路径触达了旧树兜底：${stmt.code}")
        return oldEnv to newEnv
    }

    private fun dual(stmt: Statement, setup: (SemTestEnv) -> Unit = {}): Pair<SemTestEnv, SemTestEnv> {
        val oldEnv = SemTestEnv().also(setup)
        val newEnv = SemTestEnv().also(setup)
        return compare(oldEnv, newEnv, stmt)
    }

    private fun dualSys(
        stmt: Statement,
        setup: (SemTestEnv, RecordingOps) -> Unit = { _, _ -> },
    ): Pair<SemTestEnv, SemTestEnv> {
        val oldOps = RecordingOps()
        val newOps = RecordingOps()
        val oldEnv = SemTestEnv().also { it.ctx.systemOps = oldOps; setup(it, oldOps) }
        val newEnv = SemTestEnv().also { it.ctx.systemOps = newOps; setup(it, newOps) }
        val envs = compare(oldEnv, newEnv, stmt)
        assertEquals(oldOps.calls, newOps.calls, "SystemOps 调用不一致：${stmt.code}")
        return envs
    }

    private fun assertIntVar(envs: Pair<SemTestEnv, SemTestEnv>, name: String, vararg idx: Long) {
        assertEquals(
            envs.first.store.readInt(name, idx),
            envs.second.store.readInt(name, idx),
            "变量 $name[${idx.joinToString()}] 两路不一致",
        )
    }

    private fun assertStrVar(envs: Pair<SemTestEnv, SemTestEnv>, name: String, vararg idx: Long) {
        assertEquals(
            envs.first.store.readStr(name, idx),
            envs.second.store.readStr(name, idx),
            "字符串变量 $name[${idx.joinToString()}] 两路不一致",
        )
    }

    private fun seedInt(env: SemTestEnv, name: String, value: Long, vararg idx: Long) =
        env.store.writeInt(name, longArrayOf(*idx), value)

    private fun templates(env: SemTestEnv, vararg nos: Long) {
        for (no in nos.toSet()) env.store.registerTemplate(CharacterTemplate(no = no, name = "T$no", csvNo = no))
    }

    /** 直接经变量池追加角色（替身造数据，不走语句层，避免对拍两侧的初始状态依赖旧路径）。 */
    private fun seedChara(env: SemTestEnv, vararg nos: Long) {
        templates(env, *nos)
        for (no in nos) env.store.addCharacter(no)
    }

    // ------------------------------------------------------------------
    // 37 个 StmtTag：全部分派且不触达旧树兜底
    // ------------------------------------------------------------------

    private fun oneStatementPerTag(): List<Statement> {
        val v = VoidArgument()
        return listOf(
            PrintStmt(PrintSpec.parse(FunctionCode.PRINTL)!!, ExpressionArgument(StrLiteral("x")), 1, ""),
            PrintDataStmt(FunctionCode.PRINTDATA, PrintDataArgument(null), 1, ""),
            AssignStmt(iVar("FLAG", 0), OperatorCode.ASSIGNMENT, SpSetArgument(iVar("FLAG", 0), IntLiteral(1)), 1, ""),
            JumpStmt(FunctionCode.JUMP, null, v, 1, ""),
            CallStmt(FunctionCode.CALL, null, v, 1, ""),
            CallFStmt(null, v, 1, ""),
            CallSharpStmt(SpCallSharpArgument(null, emptyList(), emptyList()), 1, ""),
            MethodStmt("FOO", MethodArgument(null), 1, ""),
            VarDeclStmt(FunctionCode.VARI, IntAsignArgument("XV", intArrayOf(1), null), 1, ""),
            IfStmt(IntLiteral(1), v, 1, ""),
            ElseIfStmt(IntLiteral(1), v, 1, ""),
            ElseStmt(v, 1, ""),
            EndIfStmt(v, 1, ""),
            SelectCaseStmt(IntLiteral(1), v, 1, ""),
            CaseStmt(listOf(CaseExpression()), v, 1, ""),
            CaseElseStmt(v, 1, ""),
            EndSelectStmt(v, 1, ""),
            ForStmt(FunctionCode.FOR, v, 1, ""),
            NextStmt(v, 1, ""),
            RepeatStmt(v, 1, ""),
            RendStmt(v, 1, ""),
            WhileStmt(IntLiteral(1), v, 1, ""),
            WendStmt(v, 1, ""),
            DoStmt(v, 1, ""),
            LoopStmt(null, v, 1, ""),
            CatchStmt(v, 1, ""),
            EndCatchStmt(v, 1, ""),
            DataListStmt(FunctionCode.DATALIST, v, 1, ""),
            EndBlockStmt(FunctionCode.ENDLIST, v, 1, ""),
            FlowStmt(FunctionCode.QUIT, v, 1, ""),
            ReturnStmt(listOf(IntLiteral(1)), v, 1, ""),
            ReturnFStmt(null, v, 1, "", FunctionCode.RETURNFORM),
            FuncLabelStmt("FOO", listOf("A"), 1, ""),
            GotoLabelStmt("\$L", 1, ""),
            SharpStmt(keyword = "#DIM", line = 1, raw = ""),
            InstructionStmt(FunctionCode.RESETCOLOR, v, 1, ""),
            DataStmt(FunctionCode.DATA, "", v, 1, ""),
        )
    }

    @Test
    fun `37 个 StmtTag 全部分派且不触达 statementAt`() {
        val stmts = oneStatementPerTag()
        val flat = FlatStatements.encode(stmts, emptyList())
        val source = FlatSource(flat)
        val tags = (0 until flat.count).map { flat.stream[flat.headAt(it)] }.toSet()
        assertEquals(37, tags.size, "覆盖的 StmtTag 数量不是 37：$tags")
        val env = SemTestEnv()
        env.ctx.dataSections = sections(emptyList())
        for (i in 0 until flat.count) {
            // 语句能否成功执行不属本条判据（如 VAR_DECL 在本作配置下禁用）；只要求不走旧树。
            try {
                env.executor.execute(source, i, env.ctx)
            } catch (e: Throwable) {
                assertTrue(
                    e !is UnsupportedOperationException && e !is NullPointerException,
                    "第 $i 条（tag=${flat.stream[flat.headAt(i)]}）抛出非预期异常：$e",
                )
            }
        }
        assertEquals(0, source.statementAtCalls, "新入口触达了 statementAt 兜底")
    }

    // ------------------------------------------------------------------
    // 变量族
    // ------------------------------------------------------------------

    @Test
    fun `变量族 直读与旧树逐字一致`() {
        // SET：非常量项
        assertIntVar(
            dual(
                AssignStmt(iVar("FLAG", 0), OperatorCode.ASSIGNMENT, SpSetArgument(iVar("FLAG", 0), IntLiteral(7)), 1, ""),
            ),
            "FLAG", 0,
        )
        // SET：常量增量（Term=null, IsConst, AddConst, ConstInt）
        val plus = SpSetArgument(iVar("FLAG", 0), null).apply {
            isConst = true
            constInt = 3
            addConst = true
        }
        assertIntVar(
            dual(AssignStmt(iVar("FLAG", 0), OperatorCode.ASSIGNMENT, plus, 1, ""), { seedInt(it, "FLAG", 40, 0) }),
            "FLAG", 0,
        )
        // SET：字符串
        assertStrVar(
            dual(
                AssignStmt(sVar("RESULTS", 0), OperatorCode.ASSIGNMENT, SpSetArgument(sVar("RESULTS", 0), StrLiteral("hi")), 1, ""),
            ),
            "RESULTS", 0,
        )
        // SET：数组批量赋值（SpSetArrayArgument，术语列表形态）
        assertIntVar(
            dual(
                AssignStmt(
                    iVar("FLAG", 0),
                    OperatorCode.ASSIGNMENT,
                    SpSetArrayArgument(iVar("FLAG", 0), listOf(IntLiteral(4), IntLiteral(5))),
                    1, "",
                ),
            ),
            "FLAG", 0,
        )
        // SET：数组批量赋值（常量列表形态 = 可空 longArray 槽）
        assertIntVar(
            dual(
                AssignStmt(
                    iVar("FLAG", 0),
                    OperatorCode.ASSIGNMENT,
                    SpSetArrayArgument(iVar("FLAG", 0), emptyList(), constIntList = longArrayOf(8, 9)),
                    1, "",
                ),
            ),
            "FLAG", 1,
        )
        // SWAP / SETBIT / CLEARBIT / INVERTBIT
        assertIntVar(
            dual(
                InstructionStmt(FunctionCode.SWAP, SpSwapVarArgument(iVar("DAY", 0), iVar("FLAG", 0)), 1, ""),
                { seedInt(it, "DAY", 1, 0); seedInt(it, "FLAG", 2, 0) },
            ),
            "DAY", 0,
        )
        assertIntVar(
            dual(InstructionStmt(FunctionCode.SETBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(3))), 1, "")),
            "FLAG", 0,
        )
        assertIntVar(
            dual(
                InstructionStmt(FunctionCode.CLEARBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(0))), 1, ""),
                { seedInt(it, "FLAG", 3, 0) },
            ),
            "FLAG", 0,
        )
        assertIntVar(
            dual(
                InstructionStmt(FunctionCode.INVERTBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(1))), 1, ""),
                { seedInt(it, "FLAG", 1, 0) },
            ),
            "FLAG", 0,
        )
        // SETBIT 越界（错误文案逐字一致）
        dual(InstructionStmt(FunctionCode.SETBIT, BitArgument(iVar("FLAG", 0), listOf(IntLiteral(64))), 1, ""))
        // VARSET / VARSIZE / POWER / TIMES
        assertIntVar(
            dual(InstructionStmt(FunctionCode.VARSET, SpVarSetArgument(iVar("FLAG", 0), IntLiteral(7), null, null), 1, "")),
            "FLAG", 9999,
        )
        assertIntVar(
            dual(InstructionStmt(FunctionCode.VARSIZE, SpVarsizeArgument("FLAG"), 1, "")),
            "RESULT", 0,
        )
        assertIntVar(
            dual(InstructionStmt(FunctionCode.POWER, SpPowerArgument(iVar("FLAG", 0), IntLiteral(2), IntLiteral(10)), 1, "")),
            "FLAG", 0,
        )
        assertIntVar(
            dual(
                InstructionStmt(FunctionCode.TIMES, SpTimesArgument(iVar("FLAG", 0), 1.5), 1, ""),
                { seedInt(it, "FLAG", 8, 0) },
            ),
            "FLAG", 0,
        )
        // CVARSET：写 [角色, 元素]，区间右开
        val cvar = dual(
            InstructionStmt(
                FunctionCode.CVARSET,
                SpCVarSetArgument(iVar("CFLAG"), IntLiteral(5), IntLiteral(7), IntLiteral(0), IntLiteral(2)),
                1, "",
            ),
            { env -> seedChara(env, 10, 20, 10) },
        )
        assertIntVar(cvar, "CFLAG", 0, 5)
        assertIntVar(cvar, "CFLAG", 1, 5)
        assertIntVar(cvar, "CFLAG", 2, 5)
        // SORTCHARA
        dual(
            InstructionStmt(FunctionCode.SORTCHARA, SpSortcharaArgument(iVar("NO"), SortOrder.ASCENDING), 1, ""),
            { env -> seedChara(env, 20, 10) },
        )
    }

    @Test
    fun `数组族 直读与旧树逐字一致`() {
        // ENCODETOUNI
        dual(InstructionStmt(FunctionCode.ENCODETOUNI, ExpressionArgument(StrLiteral("AB")), 1, ""))
        // SPLIT
        dual(
            InstructionStmt(
                FunctionCode.SPLIT,
                SpSplitArgument(StrLiteral("a,b,c"), StrLiteral(","), "SAVEDATA_TEXT", null),
                1, "",
            ),
        )
        // ARRAYSHIFT
        assertIntVar(
            dual(
                InstructionStmt(
                    FunctionCode.ARRAYSHIFT,
                    SpArrayShiftArgument(iVar("FLAG", 0), IntLiteral(1), IntLiteral(0), null, null),
                    1, "",
                ),
                { env -> for (i in 0 until 5) seedInt(env, "FLAG", i.toLong(), i.toLong()) },
            ),
            "FLAG", 2,
        )
        // ARRAYREMOVE
        assertIntVar(
            dual(
                InstructionStmt(
                    FunctionCode.ARRAYREMOVE,
                    SpArrayControlArgument(iVar("FLAG", 0), IntLiteral(0), IntLiteral(1)),
                    1, "",
                ),
                { env -> for (i in 0 until 4) seedInt(env, "FLAG", i.toLong(), i.toLong()) },
            ),
            "FLAG", 2,
        )
        // ARRAYSORT
        assertIntVar(
            dual(
                InstructionStmt(
                    FunctionCode.ARRAYSORT,
                    SpArraySortArgument(iVar("FLAG", 0), SortOrder.ASCENDING, null, null),
                    1, "",
                ),
                { env -> seedInt(env, "FLAG", 9, 0); seedInt(env, "FLAG", 1, 1) },
            ),
            "FLAG", 0,
        )
        // ARRAYCOPY（1 维）
        val copy = dual(
            InstructionStmt(FunctionCode.ARRAYCOPY, SpCopyArrayArgument(StrLiteral("FLAG"), StrLiteral("DAY")), 1, ""),
            { env -> for (i in 0 until 3) seedInt(env, "FLAG", i.toLong(), i.toLong()) },
        )
        assertIntVar(copy, "DAY", 2)
        // ARRAYSHIFT 错误路径（第 2 参溢出）
        dual(
            InstructionStmt(
                FunctionCode.ARRAYSHIFT,
                SpArrayShiftArgument(iVar("FLAG", 0), IntLiteral(Int.MIN_VALUE.toLong()), null, null, null),
                1, "",
            ),
        )
        // ARRAYSORT 显式负值（原文把 start 印成负值）与越界
        dual(
            InstructionStmt(
                FunctionCode.ARRAYSORT,
                SpArraySortArgument(iVar("FLAG", 0), SortOrder.ASCENDING, null, IntLiteral(-1)),
                1, "",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 显示族
    // ------------------------------------------------------------------

    @Test
    fun `显示族 直读与旧树逐字一致`() {
        dual(printStmt(FunctionCode.PRINTL, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTW, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTN, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTC, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTLC, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTSINGLE, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTFORMD, ExpressionArgument(StrLiteral("x"))))
        dual(printStmt(FunctionCode.PRINTV, SpPrintVArgument(listOf(IntLiteral(1), StrLiteral("a")))))
        dual(InstructionStmt(FunctionCode.PRINTBUTTON, SpButtonArgument(StrLiteral("b"), IntLiteral(5)), 1, ""))
        dual(InstructionStmt(FunctionCode.PRINTBUTTONC, SpButtonArgument(StrLiteral("b"), StrLiteral("k")), 1, ""))
        dual(InstructionStmt(FunctionCode.PRINTPLAIN, ExpressionArgument(StrLiteral("p")), 1, ""))
        dual(InstructionStmt(FunctionCode.DRAWLINE, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.DRAWLINEFORM, ExpressionArgument(StrLiteral("d")), 1, ""))
        dual(InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(StrLiteral("r")), 1, ""))
        dual(InstructionStmt(FunctionCode.CLEARLINE, ExpressionArgument(IntLiteral(1)), 1, ""))
        dual(InstructionStmt(FunctionCode.BAR, SpBarArgument(IntLiteral(5), IntLiteral(10), IntLiteral(4)), 1, ""))
        dual(InstructionStmt(FunctionCode.BARL, SpBarArgument(IntLiteral(5), IntLiteral(10), IntLiteral(4)), 1, ""))
        dual(InstructionStmt(FunctionCode.SETCOLOR, SpColorArgument(IntLiteral(0x102030)), 1, ""))
        dual(InstructionStmt(FunctionCode.SETBGCOLOR, SpColorArgument(IntLiteral(1), IntLiteral(2), IntLiteral(3)), 1, ""))
        dual(InstructionStmt(FunctionCode.SETCOLORBYNAME, ExpressionArgument(StrLiteral("Red")), 1, ""))
        dual(InstructionStmt(FunctionCode.SETCOLORBYNAME, ExpressionArgument(StrLiteral("NoSuchColor")), 1, ""))
        dual(InstructionStmt(FunctionCode.RESETCOLOR, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.FONTBOLD, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.FONTSTYLE, ExpressionArgument(IntLiteral(3)), 1, ""))
        dual(InstructionStmt(FunctionCode.SETFONT, ExpressionArgument(StrLiteral("f")), 1, ""))
        dual(InstructionStmt(FunctionCode.ALIGNMENT, ExpressionArgument(StrLiteral("center")), 1, ""))
        dual(InstructionStmt(FunctionCode.ALIGNMENT, ExpressionArgument(StrLiteral("nope")), 1, ""))
        dual(InstructionStmt(FunctionCode.REDRAW, ExpressionArgument(IntLiteral(1)), 1, ""))
        dual(InstructionStmt(FunctionCode.HTML_PRINT, SpHtmlPrint(StrLiteral("<b>x</b>"), IntLiteral(1)), 1, ""))
        dual(InstructionStmt(FunctionCode.HTML_PRINT_ISLAND, SpHtmlPrint(StrLiteral("i"), null), 1, ""))
        dual(InstructionStmt(FunctionCode.HTML_PRINT_ISLAND_CLEAR, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.HTML_TAGSPLIT, SpHtmlSplitArgument(StrLiteral("a<b>x"), "SAVEDATA_TEXT", null), 1, ""))
        // PRINT_ABL / PRINT_PALAM 需要角色位
        dual(InstructionStmt(FunctionCode.PRINT_ABL, ExpressionArgument(IntLiteral(0)), 1, ""), { env -> seedChara(env, 10) })
        dual(InstructionStmt(FunctionCode.PRINT_PALAM, ExpressionArgument(IntLiteral(0)), 1, ""), { env -> seedChara(env, 10) })
        // 无 CSV 表时两路同样报「未定义的变量」——同样比较异常文案
        dual(InstructionStmt(FunctionCode.PRINT_ITEM, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.PRINT_SHOPITEM, VoidArgument(), 1, ""))
    }

    // ------------------------------------------------------------------
    // 输入族
    // ------------------------------------------------------------------

    @Test
    fun `输入族 直读与旧树逐字一致`() {
        dual(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.FORCEWAIT, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.WAITANYKEY, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(10), IntLiteral(0)), 1, ""))
        dual(InstructionStmt(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(0), IntLiteral(1)), 1, ""))
        dual(InstructionStmt(FunctionCode.INPUT, SpInputsArgument(IntLiteral(5), null, null), 1, ""))
        dual(InstructionStmt(FunctionCode.INPUTS, SpInputsArgument(StrLiteral("d"), IntLiteral(0), null), 1, ""))
        dual(InstructionStmt(FunctionCode.ONEINPUT, SpInputsArgument(null, null, null), 1, ""))
        dual(InstructionStmt(FunctionCode.ONEINPUTS, SpInputsArgument(StrLiteral("d"), null, null), 1, ""))
        // CanSkip + MesSkip 快路径
        dual(
            InstructionStmt(FunctionCode.INPUT, SpInputsArgument(IntLiteral(5), IntLiteral(1), IntLiteral(1)), 1, ""),
            { it.ctx.mesSkip = true },
        )
        dual(
            InstructionStmt(
                FunctionCode.TINPUT,
                SpTInputsArgument(IntLiteral(100), IntLiteral(1), IntLiteral(1), StrLiteral("超时"), IntLiteral(0), null),
                1, "",
            ),
        )
        dual(
            InstructionStmt(
                FunctionCode.TINPUTS,
                SpTInputsArgument(IntLiteral(100), StrLiteral("d"), null, null, IntLiteral(1), IntLiteral(1)),
                1, "",
            ),
            { it.ctx.mesSkip = true },
        )
        dual(InstructionStmt(FunctionCode.BINPUT, SpInputsArgument(IntLiteral(5), null, null), 1, ""))
        dual(InstructionStmt(FunctionCode.BINPUTS, SpInputsArgument(StrLiteral("d"), IntLiteral(0), null), 1, ""))
        dual(InstructionStmt(FunctionCode.ONEBINPUT, SpInputsArgument(null, null, null), 1, ""))
        dual(InstructionStmt(FunctionCode.INPUTANY, VoidArgument(), 1, ""))
        dual(
            InstructionStmt(
                FunctionCode.INPUTMOUSEKEY,
                ExpressionsArgument(listOf(null), listOf(IntLiteral(100))),
                1, "",
            ),
        )
        dual(InstructionStmt(FunctionCode.INPUTMOUSEKEY, ExpressionsArgument(emptyList(), emptyList()), 1, ""))
    }

    // ------------------------------------------------------------------
    // 角色库 / 进程状态
    // ------------------------------------------------------------------

    @Test
    fun `角色库与进程状态 直读与旧树逐字一致`() {
        assertEquals(
            2,
            dual(
                InstructionStmt(FunctionCode.ADDCHARA, ExpressionArrayArgument(listOf(IntLiteral(10), IntLiteral(20))), 1, ""),
                { env -> templates(env, 10, 20) },
            ).second.store.characters.count,
        )
        dual(InstructionStmt(FunctionCode.ADDVOIDCHARA, VoidArgument(), 1, ""))
        dual(
            InstructionStmt(FunctionCode.ADDCOPYCHARA, ExpressionArrayArgument(listOf(IntLiteral(0))), 1, ""),
            { env -> seedChara(env, 10) },
        )
        dual(
            InstructionStmt(FunctionCode.DELCHARA, ExpressionArrayArgument(listOf(IntLiteral(0))), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        dual(
            InstructionStmt(FunctionCode.DELCHARA, ExpressionArrayArgument(listOf(IntLiteral(0), IntLiteral(1))), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        dual(InstructionStmt(FunctionCode.DELALLCHARA, VoidArgument(), 1, ""), { env -> seedChara(env, 10) })
        dual(
            InstructionStmt(FunctionCode.SWAPCHARA, SpSwapCharaArgument(IntLiteral(0), IntLiteral(1)), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        dual(
            InstructionStmt(FunctionCode.COPYCHARA, SpSwapCharaArgument(IntLiteral(0), IntLiteral(1)), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        dual(
            InstructionStmt(FunctionCode.PICKUPCHARA, ExpressionArrayArgument(listOf(IntLiteral(1), IntLiteral(0))), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        // 越界报错（文案逐字）
        dual(
            InstructionStmt(FunctionCode.PICKUPCHARA, ExpressionArrayArgument(listOf(IntLiteral(9))), 1, ""),
            { env -> seedChara(env, 10, 20) },
        )
        dual(
            InstructionStmt(FunctionCode.RESET_STAIN, ExpressionArgument(IntLiteral(0)), 1, ""),
            { env -> seedChara(env, 10) },
        )
        dual(
            InstructionStmt(FunctionCode.RESET_STAIN, ExpressionArgument(IntLiteral(5)), 1, ""),
            { env -> seedChara(env, 10) },
        )
        // 进程状态
        val skip = dual(InstructionStmt(FunctionCode.SKIPDISP, ExpressionArgument(IntLiteral(1)), 1, ""))
        assertIntVar(skip, "RESULT", 0)
        dual(InstructionStmt(FunctionCode.NOSKIP, VoidArgument(), 1, ""), { it.ctx.skipPrint = true })
        dual(InstructionStmt(FunctionCode.ENDNOSKIP, VoidArgument(), 1, ""), { it.ctx.saveSkip = true })
        dual(InstructionStmt(FunctionCode.SKIPLOG, ExpressionArgument(IntLiteral(1)), 1, ""))
        dual(InstructionStmt(FunctionCode.FORCEKANA, ExpressionArgument(IntLiteral(9)), 1, ""))
        dual(InstructionStmt(FunctionCode.RESETDATA, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.RESETGLOBAL, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.PUTFORM, ExpressionArgument(StrLiteral("t")), 1, ""))
        dual(InstructionStmt(FunctionCode.ASSERT, ExpressionArgument(IntLiteral(0)), 1, ""))
        dual(InstructionStmt(FunctionCode.THROW, ExpressionArgument(StrLiteral("boom")), 1, ""))
        dual(InstructionStmt(FunctionCode.QUIT, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.FORCE_QUIT_AND_RESTART, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.BEGIN, ExpressionArgument(StrLiteral("TITLE")), 1, ""))
        dual(InstructionStmt(FunctionCode.FORCE_BEGIN, ExpressionArgument(StrLiteral("TITLE")), 1, ""))
        // 未实现占位语句
        dual(InstructionStmt(FunctionCode.REF, RefArgument("X"), 1, ""))
        dual(InstructionStmt(FunctionCode.LOADVAR, ExpressionArgument(StrLiteral("X")), 1, ""))
    }

    // ------------------------------------------------------------------
    // 系统 / 存档
    // ------------------------------------------------------------------

    @Test
    fun `系统与存档 直读与旧树逐字一致`() {
        assertIntVar(
            dualSys(InstructionStmt(FunctionCode.SAVEDATA, SpSaveDataArgument(IntLiteral(1), StrLiteral("t")), 1, "")),
            "RESULT", 0,
        )
        dualSys(InstructionStmt(FunctionCode.SAVEDATA, SpSaveDataArgument(IntLiteral(-1), null), 1, ""))
        dualSys(InstructionStmt(FunctionCode.SAVEDATA, SpSaveDataArgument(IntLiteral(1), StrLiteral("a\nb")), 1, ""))
        dualSys(InstructionStmt(FunctionCode.LOADDATA, ExpressionArgument(IntLiteral(1)), 1, ""))
        dualSys(InstructionStmt(FunctionCode.LOADDATA, ExpressionArgument(IntLiteral(-1)), 1, ""))
        dualSys(InstructionStmt(FunctionCode.DELDATA, ExpressionArgument(IntLiteral(1)), 1, ""))
        dualSys(InstructionStmt(FunctionCode.DELDATA, ExpressionArgument(IntLiteral(-1)), 1, ""))
        dualSys(InstructionStmt(FunctionCode.SAVEGAME, VoidArgument(), 1, ""))
        dualSys(InstructionStmt(FunctionCode.LOADGAME, VoidArgument(), 1, ""))
        dualSys(InstructionStmt(FunctionCode.SAVEGLOBAL, VoidArgument(), 1, ""))
        assertIntVar(dualSys(InstructionStmt(FunctionCode.LOADGLOBAL, VoidArgument(), 1, "")), "RESULT", 0)
        dualSys(
            InstructionStmt(
                FunctionCode.SAVECHARA,
                ExpressionArrayArgument(listOf(StrLiteral("f"), StrLiteral("t"), IntLiteral(0))),
                1, "",
            ),
            { env, _ -> seedChara(env, 10) },
        )
        dualSys(InstructionStmt(FunctionCode.LOADCHARA, ExpressionArgument(StrLiteral("f")), 1, ""))
        assertIntVar(dualSys(InstructionStmt(FunctionCode.UPDATECHECK, VoidArgument(), 1, "")), "RESULT", 0)
        dualSys(InstructionStmt(FunctionCode.CALLTRAIN, ExpressionArgument(IntLiteral(1)), 1, ""))
        dualSys(InstructionStmt(FunctionCode.STOPCALLTRAIN, VoidArgument(), 1, ""))
        dualSys(
            InstructionStmt(FunctionCode.DOTRAIN, ExpressionArgument(IntLiteral(1)), 1, ""),
            { _, _ -> },
        )
        dualSys(InstructionStmt(FunctionCode.UPCHECK, VoidArgument(), 1, ""))
        dualSys(InstructionStmt(FunctionCode.CUPCHECK, ExpressionArgument(IntLiteral(1)), 1, ""))
        // 未注入 SystemOps：两路同一句 CodeEE
        dual(InstructionStmt(FunctionCode.SAVEGLOBAL, VoidArgument(), 1, ""))
        // SAVEGAME 的 __CAN_SAVE__ 门
        dualSys(InstructionStmt(FunctionCode.SAVEGAME, VoidArgument(), 1, ""), { env, _ -> env.ctx.canSave = false })
        // DOTRAIN 的 proc 钩子优先
        dual(
            InstructionStmt(FunctionCode.DOTRAIN, ExpressionArgument(IntLiteral(2)), 1, ""),
            { it.ctx.doTrainHook = { _ -> } },
        )
    }

    // ------------------------------------------------------------------
    // 杂项 / 调试 / 声音 / 随机数
    // ------------------------------------------------------------------

    @Test
    fun `杂项与随机数 直读与旧树逐字一致`() {
        dual(InstructionStmt(FunctionCode.STRLEN, ExpressionArgument(StrLiteral("abc")), 1, ""))
        dual(InstructionStmt(FunctionCode.STRLENU, ExpressionArgument(StrLiteral("abc")), 1, ""))
        dual(InstructionStmt(FunctionCode.PRINTCPERLINE, SpGetIntArgument(iVar("FLAG", 0)), 1, ""))
        dual(InstructionStmt(FunctionCode.SAVENOS, SpGetIntArgument(iVar("FLAG", 0)), 1, ""))
        dual(InstructionStmt(FunctionCode.METHOD, MethodArgument(IntLiteral(5)), 1, ""))
        dual(InstructionStmt(FunctionCode.METHOD, MethodArgument(StrLiteral("s")), 1, ""))
        dual(InstructionStmt(FunctionCode.DEBUGPRINT, ExpressionArgument(StrLiteral("d")), 1, ""))
        dual(InstructionStmt(FunctionCode.DEBUGPRINTL, ExpressionArgument(StrLiteral("d")), 1, ""))
        dual(InstructionStmt(FunctionCode.DEBUGCLEAR, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.PLAYBGM, ExpressionArgument(StrLiteral("b")), 1, ""))
        dual(InstructionStmt(FunctionCode.STOPBGM, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.PLAYSOUND, SpHtmlPrint(StrLiteral("s"), IntLiteral(0)), 1, ""))
        dual(InstructionStmt(FunctionCode.STOPSOUND, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.SETSOUNDVOLUME, ExpressionArgument(IntLiteral(50)), 1, ""))
        dual(InstructionStmt(FunctionCode.SETBGMVOLUME, ExpressionArgument(IntLiteral(50)), 1, ""))
        dual(InstructionStmt(FunctionCode.AWAIT, ExpressionArgument(IntLiteral(0)), 1, ""))
        dual(InstructionStmt(FunctionCode.AWAIT, ExpressionArgument(IntLiteral(-1)), 1, ""))
        dual(InstructionStmt(FunctionCode.AWAIT, ExpressionArgument(IntLiteral(10001)), 1, ""))
        dual(InstructionStmt(FunctionCode.RANDOMIZE, ExpressionArgument(IntLiteral(42)), 1, ""))
        dual(InstructionStmt(FunctionCode.DUMPRAND, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.INITRAND, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.CLEARBGIMAGE, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.CLEARTEXTBOX, VoidArgument(), 1, ""))
        dual(InstructionStmt(FunctionCode.SETBGIMAGE, ExpressionArrayArgument(listOf(StrLiteral("bg"), IntLiteral(3))), 1, ""))
        dual(InstructionStmt(FunctionCode.REMOVEBGIMAGE, ExpressionArrayArgument(listOf(StrLiteral("bg"))), 1, ""))
        dual(InstructionStmt(FunctionCode.TOOLTIP_SETDELAY, ExpressionArgument(IntLiteral(1)), 1, ""))
        // PRINT_IMG / PRINT_RECT（graphics 走 NoopGraphicsSink：两路同样无输出）
        dual(
            InstructionStmt(
                FunctionCode.PRINT_IMG,
                SpPrintImgArgument(StrLiteral("i.png"), null, null, null),
                1, "",
            ),
        )
        dual(
            InstructionStmt(
                FunctionCode.PRINT_IMG,
                SpPrintImgArgument(StrLiteral("i.png"), null, null, listOf(MixedIntegerExprTerm(IntLiteral(10), false))),
                1, "",
            ),
        )
        dual(
            InstructionStmt(
                FunctionCode.PRINT_RECT,
                SpPrintShapeArgument(
                    listOf(
                        MixedIntegerExprTerm(IntLiteral(1), true),
                        MixedIntegerExprTerm(IntLiteral(2), false),
                    ),
                ),
                1, "",
            ),
        )
        // RETURN / RETURNFORM
        dual(ReturnStmt(listOf<Expr?>(IntLiteral(3), null), VoidArgument(), 1, ""))
        dual(ReturnStmt(emptyList(), VoidArgument(), 1, ""))
        dual(ReturnFStmt(StrLiteral("5, 6"), VoidArgument(), 1, "", FunctionCode.RETURNFORM), { env ->
            env.ctx.runtimeIntList = { text -> text.split(",").map { it.trim().toLong() } }
        })
    }

    // ------------------------------------------------------------------
    // 数据节（新路径专用：旧重载在过渡期抛 UnsupportedOperationException）
    // ------------------------------------------------------------------

    @Test
    fun `数据节按语句池下标直读`() {
        val env = SemTestEnv()
        env.ctx.dataSections = sections(listOf(listOf(sLit("A")), listOf(sLit("B"))))
        val stmt = PrintDataStmt(FunctionCode.PRINTDATAL, PrintDataArgument(iVar("FLAG", 0)), 1, "")
        val flat = FlatStatements.encode(listOf(stmt), emptyList())
        assertEquals(ExecResult.Normal, env.executor.execute(FlatSource(flat), 0, env.ctx))
        val chosen = env.store.readInt("FLAG", longArrayOf(0))
        assertTrue(chosen == 0L || chosen == 1L, "随机组下标未写入目标变量：$chosen")
        val expected = if (chosen == 0L) "A" else "B"
        assertEquals(listOf("print($expected)", "newLine"), env.console.events)
    }

    @Test
    fun `STRDATA 按语句池下标直读`() {
        val env = SemTestEnv()
        env.ctx.dataSections = sections(listOf(listOf(sLit("a"), sLit("b"))))
        val stmt = DataStmt(FunctionCode.STRDATA, "", StrDataArgument(sVar("RESULTS", 0)), 1, "")
        val flat = FlatStatements.encode(listOf(stmt), emptyList())
        assertEquals(ExecResult.Normal, env.executor.execute(FlatSource(flat), 0, env.ctx))
        assertEquals("a\nb", env.store.readStr("RESULTS", longArrayOf(0)))
    }

    @Test
    fun `数据节未注入时为无输出noop`() {
        val env = SemTestEnv()
        val stmt = PrintDataStmt(FunctionCode.PRINTDATAL, PrintDataArgument(null), 1, "")
        val flat = FlatStatements.encode(listOf(stmt), emptyList())
        assertEquals(ExecResult.Normal, env.executor.execute(FlatSource(flat), 0, env.ctx))
        assertTrue(env.console.events.none { it.startsWith("print(") }, env.console.events.toString())
    }

    // ------------------------------------------------------------------
    // 形态校验
    // ------------------------------------------------------------------

    @Test
    fun `形态校验 记录与op不符抛ErbcDecodeException`() {
        val stmt = printStmt(FunctionCode.PRINTL, ExpressionArgument(StrLiteral("x")))
        val flat = FlatStatements.encode(listOf(stmt), emptyList())
        val head = flat.headAt(0)
        // [PRINT][op][argType][bits][flag] 之后即参数记录的 ArgTag 槽；把它改成别家形态。
        flat.stream[head + 5] = ErbcFormat.ArgTag.SP_BAR
        val env = SemTestEnv()
        assertFailsWith<ErbcDecodeException> { env.executor.execute(FlatSource(flat), 0, env.ctx) }
    }
}
