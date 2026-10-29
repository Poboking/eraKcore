package com.eratw.port.core.proc

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.op
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.VariableStore
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M2 执行层接线（A4 proc-bridge）：`ProcessRuntime` = VM 字节码 ↔ sem 语句层的唯一桥。
 *
 * 全部用**手工字节码**（Assembler marker `stmt#k` 或 CodeBuilder 直发 `_CALL_BY_NAME`，
 * A1/A2 已落地的编码），不依赖 A3 的新降级实现。覆盖：
 * - 契约 C4：marker → executeStatement → StmtOutcome 映射（Normal / Quit→Stop(0) / Begin→Stop(0)）
 * - 契约 C5：`_CALL_BY_NAME` 三条路（命中建帧 / 未命中 try 跳过·非 try 抛 / 非法目标必抛）
 *   + [ProcessRuntime.resolveFunctionIndex] 直查
 * - 契约 C6：`VmHost.print` 与 `ExecContext.console` 同一个 sink、[ProcessRuntime.output] 只是镜像
 * - 契约 C1：语句层写的变量 VM 表达式读得到（双真相杀手测试），反方向亦然
 * - CodeEE 位置补齐、SemConfig/SystemOps/installDataSections 注入点、launcher 语句池身份克隆
 */
class StatementBridgeTest {

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private class StoreResolver(private val store: VariableStore) : IdentifierResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            store.resolveVarInfo(name, null)

        override fun resolveFunction(name: String): OperandType? = null
    }

    private fun expr(store: VariableStore, vararg tokens: ExprToken): Expr =
        parseOrThrow(tokens.toList(), StoreResolver(store))

    /** 记录所有 ConsoleSink 动作 + 纯文本镜像（对拍口径由宿主 sink 自己决定）。 */
    private class RecordingConsole : ConsoleSink {
        val calls = ArrayList<String>()
        val text = StringBuilder()

        // 装置不追踪打印缓冲，恒报"非空"→ BINPUT 系保持补换行（保守口径）。
        override val printingBufferEmpty: Boolean get() = false

        private fun rec(name: String, vararg args: String) {
            calls.add(if (args.isEmpty()) name else "$name(${args.joinToString(",")})")
        }

        override fun print(text: String) { rec("print", text); this.text.append(text) }
        override fun printLine(text: String) { rec("printLine", text); this.text.append(text).append('\n') }
        override fun printC(text: String, alignRight: Boolean) { rec("printC", text); this.text.append(text) }
        override fun printSingleLine(text: String) { rec("printSingleLine", text); this.text.append(text).append('\n') }
        override fun printButton(text: String, value: Long) { rec("printButton", text, "$value"); this.text.append(text) }
        override fun printButton(text: String, value: String) { rec("printButton", text, value); this.text.append(text) }
        override fun printButtonC(text: String, value: Long, alignRight: Boolean) { rec("printButtonC", text, "$value") }
        override fun printButtonC(text: String, value: String, alignRight: Boolean) { rec("printButtonC", text, value) }
        override fun printPlain(text: String) { rec("printPlain", text); this.text.append(text) }
        override fun printTemporaryLine(text: String) { rec("printTemporaryLine", text) }
        override fun printBar() { rec("printBar") }
        override fun printCustomBar(text: String) { rec("printCustomBar", text) }
        override fun newLine() { rec("newLine"); text.append('\n') }
        override fun deleteLine(count: Int) { rec("deleteLine", "$count") }
        override fun clearText() { rec("clearText") }
        override fun setForeColor(r: Int, g: Int, b: Int) { rec("setForeColor", "$r,$g,$b") }
        override fun setBackColor(r: Int, g: Int, b: Int) { rec("setBackColor", "$r,$g,$b") }
        override fun resetForeColor() { rec("resetForeColor") }
        override fun resetBackColor() { rec("resetBackColor") }
        override fun setFontStyle(mask: Int) { rec("setFontStyle", "$mask") }
        override fun setFont(name: String) { rec("setFont", name) }
        override fun setAlignment(kind: Alignment) { rec("setAlignment", kind.name) }
        override fun setRedraw(mode: Int) { rec("setRedraw", "$mode") }
        override fun printHtml(text: String, option: Boolean) { rec("printHtml", text) }
        override fun addHtmlIsland(text: String) { rec("addHtmlIsland", text) }
        override fun clearHtmlIslands() { rec("clearHtmlIslands") }
        override fun printError(text: String) { rec("printError", text); this.text.append(text).append('\n') }
    }

    /** VM 复用同一个可变 StmtEnv；测试直接调用钩子时自建只读实现。 */
    private class TestEnv(
        override val functionKey: String = "MAIN",
        override val pc: Int = -1,
        override val line: Int = -1,
        override val functionName: String? = "main",
    ) : StmtEnv

    private fun printStmtOf(code: FunctionCode, args: com.eratw.port.core.stmt.Argument): PrintStmt =
        PrintStmt(PrintSpec.parse(code)!!, args, 1, code.name)

    private fun literalStr(s: String) = ExpressionArgument(StrLiteral(s))

    private fun iVar(name: String, vararg idx: Long) =
        VarRef(name, idx.map { IntLiteral(it) }, operandType = OperandType.INT)

    private fun assignGlobal(index: Long, value: Long) = AssignStmt(
        iVar("GLOBAL", index), OperatorCode.ASSIGNMENT,
        SpSetArgument(iVar("GLOBAL", index), IntLiteral(value)), 1, "GLOBAL:$index = $value",
    )

    private fun runtimeWith(
        program: Program,
        console: RecordingConsole,
        metas: List<FuncMeta> = emptyList(),
        store: VariableStore = VariableStore(),
        semConfig: SemConfig = SemConfig(),
        systemOps: SystemOps? = null,
    ): ProcessRuntime = ProcessRuntime(
        program, store, FunctionTable(metas), VmConfig(),
        consoleSink = console, semConfig = semConfig, systemOps = systemOps,
    )

    /** `main: _SET_STR R0, <名字>; _CALL_BY_NAME R0, b, [R1]; _PRINT_LITERAL "after"; _HALT`。 */
    private fun callByNameProgram(name: String, tryFlag: Boolean): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string(name), 0, 1)
        b.emit(
            Opcode._CALL_BY_NAME, SlotAddr.local(0),
            if (tryFlag) Int.MIN_VALUE else 0,
            SlotAddr.NONE,
            1,
        )
        b.emit(Opcode._PRINT_LITERAL, b.string("after"), 1, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    // ------------------------------------------------------------------
    // C4 + C6：marker 指令端到端（launcher → 语句分派 → sem → 桥接 sink）
    // ------------------------------------------------------------------

    @Test
    fun `PRINTL marker 经 VM 执行到宿主 sink 且 output 同源`() {
        val console = RecordingConsole()
        val program = Assembler.assemble(
            """
            PRINTL stmt#0
            _PRINT_LITERAL "vm" 1
            _HALT
            """.trimIndent(),
            statements = listOf(printStmtOf(FunctionCode.PRINTL, literalStr("hello"))),
        )
        val rt = runtimeWith(program, console)
        rt.execute()
        // 语句路径（PRINTL → print+newLine）与 VM 内部指令路径（_PRINT_LITERAL）落同一个宿主 sink
        assertEquals("hello\nvm\n", console.text.toString())
        assertEquals(rt.output.toString(), console.text.toString()) // C6：镜像 == 宿主文本，同源
        assertEquals(listOf("print(hello)", "newLine", "print(vm\n)"), console.calls)
    }

    @Test
    fun `executeStatement 把 Normal 映射回去且 PRINT 不带换行`() {
        val console = RecordingConsole()
        val rt = runtimeWith(Assembler.assemble("_HALT"), console)
        val outcome = rt.executeStatement(printStmtOf(FunctionCode.PRINT, literalStr("x")), TestEnv())
        assertSame(StmtOutcome.Normal, outcome)
        assertEquals(listOf("print(x)"), console.calls) // PRINT（无 L）：不 newLine
        assertEquals("x", rt.output.toString())
    }

    @Test
    fun `QUIT 语句映射 Stop0 且帧内不再执行后续输出`() {
        val console = RecordingConsole()
        val program = Assembler.assemble(
            """
            QUIT stmt#0
            _PRINT_LITERAL "never" 1
            _HALT
            """.trimIndent(),
            statements = listOf(FlowStmt(FunctionCode.QUIT, VoidArgument(), 1, "QUIT")),
        )
        val rt = runtimeWith(program, console)
        assertEquals(0L, rt.execute())
        // Quit(*) → Stop(0)（契约 C6）：根帧到此结束，"never" 不得输出
        assertEquals("", rt.output.toString())
        assertTrue(console.calls.isEmpty())
    }

    @Test
    fun `BEGIN 语句映射 Stop0 真实入口切换属 M4 状态机`() {
        val rt = runtimeWith(Assembler.assemble("_HALT"), RecordingConsole())
        val begin = InstructionStmt(FunctionCode.BEGIN, literalStr("EVENTSHOP"), 1, "BEGIN")
        val outcome = rt.executeStatement(begin, TestEnv())
        assertEquals(StmtOutcome.Stop(0), outcome) // 本轮不实现 SystemStateCode 切换（BeginType KDoc）
    }

    @Test
    fun `sem 抛出的 CodeEE 携带 env 的 functionKey line pc`() {
        val rt = runtimeWith(Assembler.assemble("_HALT"), RecordingConsole())
        val throwStmt = InstructionStmt(FunctionCode.THROW, literalStr("boom"), 1, "THROW")
        val e = assertFailsWith<CodeEE> {
            rt.executeStatement(throwStmt, TestEnv(functionKey = "FOO", pc = 5, line = 77, functionName = "Foo"))
        }
        assertEquals("boom", e.message)
        assertEquals("Foo", e.funcName)
        assertEquals(77, e.line)
        assertEquals(5, e.pc)
    }

    // ------------------------------------------------------------------
    // C1 单一真相：语句层写 ↔ VM 表达式读
    // ------------------------------------------------------------------

    @Test
    fun `SET 语句写入 GLOBAL 后 CALL_BY_NAME 的函数内表达式读得到同一池`() {
        // 双真相杀手：若 ExecContext 与 VM 各持一份变量池，READER 里的 GLOBAL:0+1 读不到
        // SET 语句写的 41（会得到 1 或直接 CodeEE），输出必然不是 42。
        val store = VariableStore()
        val console = RecordingConsole()
        val readExpr = expr(store, id("GLOBAL"), colon(), i(0), op("+"), i(1))
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val stmtIdx = b.statement(assignGlobal(0, 41))
        b.emit(Opcode.SET, stmtIdx, 0, SlotAddr.NONE, 1) // marker：op=SET, a=语句池下标, c=NONE（契约 C3）
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("READER"), 0, 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), 0, SlotAddr.local(1), 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(1), 1, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        val readerEntry = b.here()
        b.declareFunction("READER", readerEntry, 0, 1)
        b.emit(Opcode._EVAL_INT, b.expr(readExpr), SlotAddr.local(0), 0, 2)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 2)
        val program = b.build(0)

        val readerMeta = FuncMeta(index = 1, key = "READER", name = "READER")
        val rt = runtimeWith(program, console, metas = listOf(readerMeta), store = store)
        val ret = rt.execute()
        assertEquals(42L, ret) // GLOBAL:0+1，经 _CALL_BY_NAME 命中 READER 后由返回槽带回
        assertEquals("42\n", console.text.toString())
        assertEquals(41L, store.readInt("GLOBAL", longArrayOf(0)))
        assertSame(store, rt.execContext.variables) // 结构锁定：ExecContext 与 VM 同一个 VariableStore
    }

    @Test
    fun `PRINTV 表达式读 VariableStore 里宿主预写的值 反方向同源`() {
        val store = VariableStore()
        store.writeInt("GLOBAL", longArrayOf(0), 7)
        val rt = runtimeWith(Assembler.assemble("_HALT"), RecordingConsole(), store = store)
        val printv = printStmtOf(
            FunctionCode.PRINTV,
            SpPrintVArgument(listOf(expr(store, id("GLOBAL"), colon(), i(0)))),
        )
        assertSame(StmtOutcome.Normal, rt.executeStatement(printv, TestEnv()))
        assertEquals("7", rt.output.toString())
    }

    // ------------------------------------------------------------------
    // C5：_CALL_BY_NAME 与 resolveFunctionIndex
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALLFORM 未命中静默跳过继续执行`() {
        val console = RecordingConsole()
        val rt = runtimeWith(callByNameProgram("GHOST", tryFlag = true), console)
        rt.execute() // 未命中 + try=1 → pc++（spec: FunctionIdentifier.cs:3577-3588）
        assertEquals("after\n", console.text.toString())
    }

    @Test
    fun `CALL_BY_NAME 非 try 未命中抛没有找到指定的函数原文`() {
        val rt = runtimeWith(callByNameProgram("GHOST", tryFlag = false), RecordingConsole())
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // zhs 表形逐字（lang.emuera-zhs.xml:520 / Lang.cs:2262，抛点 FunctionIdentifier.cs:3581）：
        // 函数名两侧成对直引号、引号内含 `@`。
        assertEquals("没有找到指定的函数\"@GHOST\"", e.message ?: "")
    }

    @Test
    fun `CALL_BY_NAME 事件名即使 try 位也必抛 VM 不吞`() {
        val meta = FuncMeta(index = 1, key = "EVENTFIRST", isEvent = true)
        val rt = runtimeWith(
            callByNameProgram("EVENTFIRST", tryFlag = true),
            RecordingConsole(),
            metas = listOf(meta),
        )
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // spec: CalledFunction.cs:100-103 —— 抛发生在 isTry 判断之前（FunctionIdentifier.cs:3575）；
        // 文案 = zhs:679 `对事件函数"@{0}"进行CALL调用(此警告可以用兼容性配置「{1}」来无视)`，
        // {1} = Config.GetConfigName(CompatiCallEvent) 的 `Text + "/" + EngText` 大写形。
        assertEquals(
            "对事件函数\"@EVENTFIRST\"进行CALL调用" +
                "(此警告可以用兼容性配置「イベント関数のCALLを許可する/ALLOW CALL ON EVENT FUNCTIONS」来无视)",
            e.message ?: "",
        )
    }

    @Test
    fun `CALL_BY_NAME FUNCTION 方法名必抛`() {
        val meta = FuncMeta(index = 1, key = "SUBM", isMethod = true)
        val rt = runtimeWith(
            callByNameProgram("SUBM", tryFlag = true),
            RecordingConsole(),
            metas = listOf(meta),
        )
        val e = assertFailsWith<CodeEE> { rt.execute() }
        // spec: CalledFunction.cs:106-109（CallToUserFunc）；文案 = zhs:680
        // `对标记为#FUCNTION(S)的函数"@{0}"({1}:第{2}行)进行CALL调用`
        // （`FUCNTION` 是官方拼写错误，照抄）。本用例的 FuncMeta 不带 position
        // ⇒ {1} 为空、{2}=0（不猜）。
        assertEquals(
            "对标记为#FUCNTION(S)的函数\"@SUBM\"(:第0行)进行CALL调用",
            e.message ?: "",
        )
    }

    @Test
    fun `resolveFunctionIndex 命中返回下标忽略大小写 未命中返回null`() {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        b.declareFunction("TARGET", 1, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        val rt = runtimeWith(
            b.build(0), RecordingConsole(),
            metas = listOf(FuncMeta(index = 1, key = "TARGET", name = "TARGET")),
        )
        assertEquals(1, rt.resolveFunctionIndex("target")) // IGNORE CASE（spec: Config.cs:33）
        assertEquals(1, rt.resolveFunctionIndex("TARGET"))
        assertEquals(null, rt.resolveFunctionIndex("NOPE")) // "查不到"(null) ≠ "不许查"(抛)
    }

    // ------------------------------------------------------------------
    // C6 注入点：SemConfig / SystemOps / installDataSections
    // ------------------------------------------------------------------

    @Test
    fun `semConfig 与 systemOps 注入点经 execContext 生效`() {
        val ops = object : SystemOps {
            val saved = ArrayList<Pair<Int, String>>()
            override fun saveData(no: Int, text: String): Boolean {
                saved.add(no to text)
                return true
            }
        }
        val rt = runtimeWith(
            Assembler.assemble("_HALT"), RecordingConsole(),
            semConfig = SemConfig(printCPerLine = 5), systemOps = ops,
        )
        assertSame(ops, rt.execContext.systemOps)
        // PRINTCPERLINE：SemConfig 注入值经语句写 GLOBAL:0
        rt.executeStatement(
            InstructionStmt(FunctionCode.PRINTCPERLINE, SpGetIntArgument(iVar("GLOBAL", 0)), 1, "PRINTCPERLINE"),
            TestEnv(),
        )
        assertEquals(5L, rt.store.readInt("GLOBAL", longArrayOf(0)))
        rt.executeStatement(
            InstructionStmt(FunctionCode.SAVEDATA, SpSaveDataArgument(IntLiteral(1), StrLiteral("t")), 1, "SAVEDATA"),
            TestEnv(),
        )
        assertEquals(listOf(1 to "t"), ops.saved)
    }

    /**
     * `SAVEGAME` 的 `__CAN_SAVE__` 门吃**系统状态标志位**（FunctionIdentifier.cs:2969-2977）：
     * `executeStatement` 逐语句把 `systemState.canSave` 写进 [com.eratw.port.core.sem.ExecContext]。
     * `Normal`(262143) 带位 → 门放行，语句照原版走
     * `saveCurrentState(single: true)` + `SaveLoadData(true)`（`:2978-2979`）⇒ 整栈丢弃
     * （`Process.cs:1964` 换 clone，`ProcessState.cs:475-484`）；
     * `Title_Begin`(0) 不带位 → `CodeEE("不能使用命令")`（Scope 空栈为空串，ProcessState.cs:88-97）。
     */
    @Test
    fun `SAVEGAME 的 CAN_SAVE 门吃系统状态标志位`() {
        val console = RecordingConsole()
        val store = VariableStore()
        val rt = runtimeWith(Assembler.assemble("_HALT"), console, store = store)
        val saveGame = InstructionStmt(FunctionCode.SAVEGAME, VoidArgument(), 1, "SAVEGAME")

        // Normal 带位：门放行，整栈丢弃 + 留下 SaveGame_Begin 交接
        assertEquals(true, rt.systemState.canSave)
        assertEquals(StmtOutcome.Unwind(0L), rt.executeStatement(saveGame, TestEnv()))
        assertEquals(
            SystemStateCode.SaveGame_Begin,
            com.eratw.port.core.host.SaveLoadHandoff.of(store)!!.systemState,
        )

        // 切到 Title_Begin（无 CAN_SAVE 位）再派发：门挡下
        rt.beginStep(BeginType.TITLE)
        assertEquals(false, rt.systemState.canSave)
        val blocked = assertFailsWith<CodeEE> { rt.executeStatement(saveGame, TestEnv()) }
        assertEquals("不能使用命令", blocked.message)
    }

    @Test
    fun `installDataSections 契约保持 键为语句池下标 未注入时noop`() {
        val console = RecordingConsole()
        val rt = runtimeWith(Assembler.assemble("_HALT"), console)

        // 未注入：null（语句层按 no-op 处理，spec: StatementExecutor.execPrintData 的 groupsOf ?: return）
        assertNull(rt.dataGroupsOf(0))

        // 注入：键 = **语句池下标**（T2-64 波次 C；旧口径「语句实例」在侧车侧就没有这层映射）。
        val groups = listOf(listOf<Expr>(StrLiteral("aa"), StrLiteral("bb")))
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> if (index == 7) groups else null })
        assertSame(groups, rt.dataGroupsOf(7), "注入点按池下标直查（同一组实例原样返回）")
        assertSame(groups, rt.dataGroupsOf(7), "同下标恒同组（重复查询同一组、不重建）")
        assertNull(rt.dataGroupsOf(6), "未登记的下标没有任何数据节")
        assertNull(rt.dataGroupsOf(-1), "越界下标查不到，不得挂到别的语句上")

        // 幂等：后装覆盖。
        rt.installDataSections(ProcessRuntime.DataSectionSource { _ -> null })
        assertNull(rt.dataGroupsOf(7))
    }

    /**
     * **对象树通道**（`flatStatementsEnabled = false`，Android 生产）的 PRINTDATA 必须真出字。
     *
     * 真机红（0.2.1 · 2026-10-29 启动失败）：波次 C 把侧车键换成语句池下标后，树通道的
     * `ctx.dataSections.groupsOf(stmt)` 一直没人实现（默认实现显式抛
     * `UnsupportedOperationException`「数据节键已改语句池下标」）。桌面/测试默认开扁平通道，
     * 这条缝没有任何闸；Android `GameHost` 传 `flatStatements = false` ⇒ 一走到 PRINTDATA 就崩。
     *
     * 修法：`ProcessRuntime` 补「语句实例 → 池下标」反查表（装配见 `GameSession`）。本用例锁：
     * 1. 未装反查表 → 显式失败（不静默吞掉数据节）；
     * 2. 装上后按实例换成下标取组，未登记实例 = 没有数据节；
     * 3. 走完 `executeStatement`（树通道）真打到控制台。
     */
    @Test
    fun `对象树通道 PRINTDATA 经反查表取到数据节`() {
        val console = RecordingConsole()
        val dataStmt = InstructionStmt(FunctionCode.PRINTDATAL, PrintDataArgument(null), 1, "PRINTDATAL")
        val program = Assembler.assemble("PRINTDATAL stmt#0\n_HALT", statements = listOf(dataStmt))
        val rt = runtimeWith(program, console)

        val groups = listOf(listOf<Expr>(StrLiteral("甲"), StrLiteral("乙")))
        rt.installDataSections(ProcessRuntime.DataSectionSource { index -> if (index == 0) groups else null })

        assertFailsWith<UnsupportedOperationException> { rt.dataGroupsOf(dataStmt) }

        rt.installDataSectionStatementIndex(IdentityHashMap<Statement, Int>().apply { put(dataStmt, 0) })
        assertSame(groups, rt.dataGroupsOf(dataStmt), "语句实例经反查表换成语句池下标")
        assertNull(
            rt.dataGroupsOf(InstructionStmt(FunctionCode.PRINTDATAL, PrintDataArgument(null), 1, "X")),
            "未登记实例 = 本来没有数据节（no-op，不是报错）",
        )

        rt.executeStatement(dataStmt, TestEnv())
        assertEquals("甲\n乙\n", console.text.toString(), "树通道 execPrintData：行间换行 + L 后缀再换行")
        assertEquals(0, rt.store.callDepth())
    }

    @Test
    fun `launcher 语句池按身份克隆 marker 下标不漂移`() {
        // execute() 全程走 launcher Program：clonePool 漏拷语句池 → "语句池下标越界"；
        // 结构合并/换实例 → 身份键数据节静默失效。
        val console = RecordingConsole()
        val program = Assembler.assemble(
            """
            PRINTL stmt#0
            PRINTL stmt#1
            _HALT
            """.trimIndent(),
            statements = listOf(
                printStmtOf(FunctionCode.PRINTL, literalStr("A")),
                printStmtOf(FunctionCode.PRINTL, literalStr("B")),
            ),
        )
        val rt = runtimeWith(program, console)
        rt.execute()
        assertEquals("A\nB\n", console.text.toString())
        assertEquals(2, program.statementCount) // 两条同款语句 = 两个条目（C2 严禁去重）
    }
}
