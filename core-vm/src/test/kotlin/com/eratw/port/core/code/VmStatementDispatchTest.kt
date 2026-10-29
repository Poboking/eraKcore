package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 契约 C4（VM 语句分派钩子）+ 契约 C5（`_CALL_BY_NAME`）的行为锁。
 *
 * 全部用**手工构造的程序**（[Assembler] 的 `stmt#<k>` marker 与 [CodeBuilder] 直发指令），
 * 不依赖编译器（A3）/ proc 桥（A4）/ sem（A5）的代码：宿主是一条记录型假 `VmHost`。
 *
 * ## 反向可证（改坏实现必红）的几处
 * * 钩子没调 / 当成 NOP：`分派` 与 `Transfer` 例断言"被跳过的那条指令绝没有执行"
 *   （输出里不许出现 `MUST-NOT-RUN`）、`seen.size` 精确等于语句条数。
 * * 未接线却静默：[StringHost] 不覆盖 `executeStatement`，marker 必须炸出
 *   "宿主未提供语句执行器"。
 * * try 位没生效：未命中 + try 的例断言"跳过一条、继续执行下一条、且不建帧"；
 *   未命中 + 非 try 的例断言 CodeEE 文案；非法目标（事件名/方法名）即使 try=1 也必须抛。
 * * 拆帧不配对：`Stop` 与按名调用/递归的例逐个比对 `enter/exit` 事件序列
 *   （与 [ScopeLifecycleTest] 同一口径，不许出现二次 leave）。
 * * bit31 漏进实参数：`try=1 且命中` 的例里 argc=2，被调帧把 R0+R1 相加返回，
 *   argc 变负则实参不复制 → 结果 0 ≠ 21。
 */
class VmStatementDispatchTest {

    /** 记录型假宿主：语句调用 / 按名解析 / 帧生命周期全部留痕。 */
    private class Probe(
        override val evalContext: EvalContext? = null,
    ) : VmHost {
        val output = StringBuilder()
        val events = ArrayList<String>()

        /** 每次 executeStatement 的语句身份 + env 四字段快照（env 实例是复用的，必须当场复制）。 */
        val seen = ArrayList<Seen>()

        /** resolveFunctionIndex 收到的名字（原样，大小写不加工）。 */
        val resolved = ArrayList<String>()

        /** 语句 → 结果；未登记 = [StmtOutcome.Normal]。 */
        val outcomes = HashMap<Statement, StmtOutcome>()

        /** 语句层抛错（验证 VM 不吞宿主异常）。 */
        var throwOn: Statement? = null
        var throwMessage: String = "语句层抛出的错误"

        /** 大写键 → 函数下标。 */
        var table: Map<String, Int> = emptyMap()

        /** 大写键 → 错误消息：模拟"非法目标"（事件名 / #FUNCTION 方法名）。 */
        var resolveErrors: Map<String, String> = emptyMap()

        /** 当前深度与峰值（拆帧配对的第二道闸）。 */
        var depth = 0
            private set
        var maxDepth = 0
            private set

        data class Seen(
            val stmt: Statement,
            val functionKey: String,
            val pc: Int,
            val line: Int,
            val functionName: String?,
        )

        override fun print(text: String) {
            output.append(text)
        }

        override fun onFunctionEnter(funcIndex: Int) {
            events += "enter:$funcIndex"
            depth++
            if (depth > maxDepth) maxDepth = depth
        }

        override fun onFunctionExit(funcIndex: Int) {
            depth--
            events += "exit:$funcIndex"
        }

        override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
            seen += Seen(stmt, env.functionKey, env.pc, env.line, env.functionName)
            if (stmt === throwOn) throw CodeEE(throwMessage)
            return outcomes[stmt] ?: StmtOutcome.Normal
        }

        override fun resolveFunctionIndex(name: String): Int? {
            resolved += name
            resolveErrors[name.uppercase()]?.let { throw CodeEE(it) }
            return table[name.uppercase()]
        }
    }

    // ------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------

    private fun stmt(
        code: FunctionCode = FunctionCode.PRINT,
        line: Int = 1,
        raw: String = "PRINTL x",
    ): Statement = InstructionStmt(code, VoidArgument(), line, raw)

    private fun pcOf(program: Program, op: Int, from: Int = 0): Int =
        (from until program.instructionCount).first { program.op(it) == op }

    private fun indexTable(program: Program): Map<String, Int> =
        program.functions.withIndex().associate { (i, f) -> f.name.uppercase() to i }

    /** `_CALL_BY_NAME` 的 b：实参数 | bit31(try)。 */
    private fun nameArgc(argc: Int, try_: Boolean): Int =
        if (try_) argc or Int.MIN_VALUE else argc

    // ------------------------------------------------------------------
    // C4：分派
    // ------------------------------------------------------------------

    @Test
    fun `语句操作码走宿主钩子而不是抛未实现`() {
        val s0 = stmt(raw = "PRINTL 第一条")
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe()
        val vm = VirtualMachine(program, probe)

        assertEquals(0L, vm.run())
        assertEquals(1, probe.seen.size, "钩子必须被调到，且每条语句一次")
        assertSame(s0, probe.seen[0].stmt, "marker 的 a 必须按语句池下标取回同一实例")
        assertEquals(2L, vm.executedSteps, "marker 自身计入步数")
    }

    @Test
    fun `未接线的宿主显式失败而不是静默 NOP`() {
        // StringHost 不覆盖 executeStatement → 默认实现抛 CodeEE（契约 C4）。
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(stmt()),
        )
        val e = assertFailsWith<CodeEE> { VirtualMachine(program, StringHost()).run() }
        assertTrue("宿主未提供语句执行器" in e.message!!, "实际文案：${e.message}")
    }

    @Test
    fun `语句池按身份取回两条结构相同的语句`() {
        // 契约 C2：语句池不去重（sem 的数据节以 Statement 实例为键）。
        val a = stmt(raw = "PRINTL 同名同形")
        val b = stmt(raw = "PRINTL 同名同形")
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              PRINTL stmt#1
              _HALT
            """.trimIndent(),
            statements = listOf(a, b),
        )
        val probe = Probe()
        vmRun(program, probe)

        assertEquals(2, probe.seen.size)
        assertSame(a, probe.seen[0].stmt)
        assertSame(b, probe.seen[1].stmt)
        assertTrue(probe.seen[0].stmt !== probe.seen[1].stmt, "结构相等的两条语句必须是两个池条目")
        assertEquals(2, program.statementCount)
    }

    @Test
    fun `操作码区间两端都进钩子`() {
        // 29 = BEGIN（第一条 isa 语句）、197 = UPDATECHECK（最后一条）。
        val begin = stmt(FunctionCode.BEGIN, raw = "BEGIN TITLE")
        val update = stmt(FunctionCode.UPDATECHECK, raw = "UPDATECHECK")
        val program = Assembler.assemble(
            """
            .func main slots=1
              BEGIN stmt#0
              _NOP
              UPDATECHECK stmt#1
              _HALT
            """.trimIndent(),
            statements = listOf(begin, update),
        )
        val probe = Probe()
        vmRun(program, probe)

        assertEquals(listOf(begin, update), probe.seen.map { it.stmt })
    }

    @Test
    fun `区间外的脏操作码仍然按未实现失败`() {
        // 198/199/200/201 是编译器扩展内部指令（有各自分支；201 = _RETS，合同 M3-A §3 A7），
        // 202..243 是栈码组（T2-21，走 execStackOp，不是语句），244 起无人认领 → 不得当语句派发。
        // 这里刻意用 **MAX_OPCODE + 1** 而不是写死数字：栈码组将来还会长（T2-23 起），
        // 写死的「第一个空号」会在扩容当天变成「已认领的指令」，让这条用例从守卫变成误报。
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), 0, 1)
        b.emit(Opcode.MAX_OPCODE + 1, 0, 0, 0, line = 5)
        b.emit(Opcode._HALT, line = 6)
        val program = b.build(main)

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, Probe()).run() }
        assertTrue("尚未实现" in e.message!!, "实际文案：${e.message}")
        assertEquals("main", e.funcName)
        assertEquals(5, e.line)
        assertEquals(0, e.pc)
    }

    @Test
    fun `语句池下标越界带函数行号与 pc 定位`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), 0, 1)
        b.statement(stmt()) // 池里只有 1 条
        val markerPc = b.emit(Opcode.PRINT, 99, 0, SlotAddr.NONE, line = 7)
        b.emit(Opcode._HALT, line = 8)
        val program = b.build(main)

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, Probe()).run() }
        assertTrue("语句池下标越界(99)" in e.message!!, "实际文案：${e.message}")
        assertEquals(7, e.line)
        assertEquals(markerPc, e.pc)
        assertEquals("main", e.funcName)
    }

    @Test
    fun `StmtEnv 报告函数键 pc 行号与书写名`() {
        val s0 = stmt(raw = "PRINTL 在被调函数里")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _CALL sub, 0, none
              _HALT

            .func sub slots=1
              PRINTL stmt#0
              _RET
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe()
        vmRun(program, probe)

        val env = probe.seen.single()
        val markerPc = pcOf(program, Opcode.PRINT)
        assertEquals("SUB", env.functionKey, "functionKey 大写归一（FuncMeta.key 口径）")
        assertEquals("sub", env.functionName)
        assertEquals(markerPc, env.pc)
        assertEquals(6, env.line, "行号取 marker 指令的源码行（1 起）")
    }

    @Test
    fun `宿主在语句里抛的 CodeEE 原样冒泡且作用域栈回滚`() {
        val s0 = stmt(raw = "PRINTL 会抛错")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _CALL sub, 0, none
              _HALT

            .func sub slots=1
              PRINTL stmt#0
              _RET
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply {
            throwOn = s0
            throwMessage = "语句层抛出的错误"
        }
        val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertEquals("语句层抛出的错误", e.message)
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
        assertEquals(0, probe.depth)
    }

    // ------------------------------------------------------------------
    // C4：Transfer
    // ------------------------------------------------------------------

    @Test
    fun `Transfer 跳到同函数内的目标 pc`() {
        val s0 = stmt(raw = "GOTO TAIL")
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              _PRINT_LITERAL "MUST-NOT-RUN"
              _HALT
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Transfer(pcOf(program, Opcode._HALT)) }

        vmRun(program, probe)
        assertEquals("", probe.output.toString(), "Transfer 之后的那条指令不得执行")
        assertEquals(listOf("enter:0", "exit:0"), probe.events)
    }

    @Test
    fun `Transfer 跳到别的函数区间必须 CodeEE`() {
        val s0 = stmt(raw = "GOTO 跨函数")
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              _HALT

            .func other slots=1
              _NOP
            """.trimIndent(),
            statements = listOf(s0),
        )
        val other = program.functions[1]
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Transfer(other.entryPc) }

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertTrue("跳转目标 pc 越界" in e.message!!, "实际文案：${e.message}")
        assertEquals("main", e.funcName)
        assertEquals(2, e.line)
        assertEquals(0, e.pc)
        assertEquals(listOf("enter:0", "exit:0"), probe.events, "异常路径同样要 leave 入口帧")
    }

    @Test
    fun `Transfer 落到本函数 entryPc 之前或负 pc 都必须 CodeEE`() {
        val s0 = stmt(raw = "GOTO 越下界")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _CALL other, 0, none
              _HALT

            .func other slots=1
              _NOP
              _NOP
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(s0),
        )
        val markerPc = pcOf(program, Opcode.PRINT)
        assertEquals(2, program.functions[1].entryPc, "other 的 entryPc 必须 >0，本例才测得到下界")
        for (bad in listOf(-1, 0, 1)) {
            val probe = Probe().apply { outcomes[s0] = StmtOutcome.Transfer(bad) }
            val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
            assertTrue("跳转目标 pc 越界" in e.message!!, "目标 $bad 应被拒绝：${e.message}")
            assertEquals("other", e.funcName, "定位取**当前帧**的函数名")
            assertEquals(markerPc, e.pc)
            assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
        }
    }

    @Test
    fun `Transfer 允许落回本函数 entryPc 且步数闸仍然生效`() {
        val s0 = stmt(raw = "GOTO HEAD")
        val program = Assembler.assemble(
            """
            .func main slots=1
              PRINTL stmt#0
              _HALT
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Transfer(0) }
        val vm = VirtualMachine(program, probe, VmConfig(maxStepsPerRun = 50))

        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(51L, e.steps, "回边合法 → 只有步数闸能拦住它")
        assertEquals("main", e.funcName)
        assertEquals(2, e.line)
        assertEquals(50, probe.seen.size, "每条语句都计入步数")
    }

    // ------------------------------------------------------------------
    // C4：Stop（复用 _RET 的拆帧路径）
    // ------------------------------------------------------------------

    @Test
    fun `顶层 Stop 的值就是 run 的返回值并 leave 入口帧`() {
        val s0 = stmt(FunctionCode.QUIT, raw = "QUIT")
        val program = Assembler.assemble(
            """
            .func main slots=1
              QUIT stmt#0
              _PRINT_LITERAL "MUST-NOT-RUN"
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Stop(7L) }
        val vm = VirtualMachine(program, probe)

        assertEquals(7L, vm.run())
        assertEquals("", probe.output.toString())
        assertEquals(1L, vm.executedSteps)
        assertEquals(listOf("enter:0", "exit:0"), probe.events)
    }

    @Test
    fun `被调帧的 Stop 按 RET 同款路径写回返回槽`() {
        val s0 = stmt(FunctionCode.QUIT, raw = "QUIT")
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL sub, 0, R0
              _PRINT_INT R0, 1
              _HALT

            .func sub slots=1
              QUIT stmt#0
              _PRINT_LITERAL "MUST-NOT-RUN"
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Stop(42L) }

        vmRun(program, probe)
        assertEquals("42\n", probe.output.toString(), "Stop 的值必须写回 _CALL 的 c 槽")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events, "不得出现二次 leave")
        assertEquals(0, probe.depth)
    }

    @Test
    fun `Stop 在返回槽为 none 时丢弃返回值`() {
        val s0 = stmt(FunctionCode.QUIT, raw = "QUIT")
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 9
              _CALL sub, 0, none
              _PRINT_INT R0, 1
              _HALT

            .func sub slots=1
              QUIT stmt#0
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe().apply { outcomes[s0] = StmtOutcome.Stop(42L) }

        vmRun(program, probe)
        assertEquals("9\n", probe.output.toString(), "c=none 时任何槽都不许被写")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
    }

    @Test
    fun `Stop 之后调用方再抛错也只 leave 一次`() {
        val quit = stmt(FunctionCode.QUIT, raw = "QUIT")
        val boom = stmt(raw = "PRINTL 会抛错")
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL sub, 0, R0
              PRINTL stmt#1
              _HALT

            .func sub slots=1
              QUIT stmt#0
            """.trimIndent(),
            statements = listOf(quit, boom),
        )
        val probe = Probe().apply {
            outcomes[quit] = StmtOutcome.Stop(5L)
            throwOn = boom
            throwMessage = "调用方语句炸了"
        }

        assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertEquals(
            listOf("enter:0", "enter:1", "exit:1", "exit:0"),
            probe.events,
            "被调帧由 leaveFrame 出栈、main 由 finally 兜底：各一次",
        )
        assertEquals(0, probe.depth)
    }

    // ------------------------------------------------------------------
    // C5：_CALL_BY_NAME
    // ------------------------------------------------------------------

    @Test
    fun `按名调用命中走与 _CALL 相同的建帧路径`() {
        val program = byNameCall(argc = 1, tryName = false)
        val probe = Probe().apply { table = indexTable(program) }

        vmRun(program, probe)
        assertEquals(listOf("SUB"), probe.resolved)
        assertEquals("21\ntail\n", probe.output.toString(), "实参按位置复制进被调帧、返回值写回 c 槽")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
    }

    @Test
    fun `按名调用的名字原样交给宿主解析`() {
        val program = byNameCall(argc = 1, tryName = false, writtenName = "sUb")
        val probe = Probe().apply { table = indexTable(program) }

        vmRun(program, probe)
        assertEquals(listOf("sUb"), probe.resolved, "VM 不做大小写归一，归 proc 层 classify")
        assertEquals("21\ntail\n", probe.output.toString())
    }

    @Test
    fun `try 位未命中时静默跳过调用本身且不建帧不写返回槽`() {
        val program = byNameCall(argc = 1, tryName = true, writtenName = "NOPE")
        val probe = Probe() // table 为空 → resolve 返回 null
        val vm = VirtualMachine(program, probe)

        assertEquals(0L, vm.run())
        assertEquals(listOf("NOPE"), probe.resolved)
        assertEquals(listOf("enter:0", "exit:0"), probe.events, "跳过 = 不进入任何帧")
        assertEquals("0\ntail\n", probe.output.toString(), "R3 保持 0（返回槽未被写），后续指令照常执行")
        assertEquals(6L, vm.executedSteps, "被跳过的只有调用，本条指令仍计一步")
    }

    @Test
    fun `try 命中仍正常调用`() {
        val program = byNameCall(argc = 1, tryName = true)
        val probe = Probe().apply { table = indexTable(program) }

        vmRun(program, probe)
        // 契约 R3：try 且 c≠NONE 时 c 是"是否真调用"标志槽（命中写 1），
        // 返回值归 RESULT、不占槽（Vm.kt _CALL_BY_NAME）。
        assertEquals("1\ntail\n", probe.output.toString(), "TRYCALLFORM：存在即调，命中标志=1")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
        assertEquals(listOf("SUB"), probe.resolved)
    }

    @Test
    fun `非 try 未命中必须抛函数未定义并带定位`() {
        val program = byNameCall(argc = 1, tryName = false, writtenName = "NOPE")
        val probe = Probe()

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertEquals("没有找到指定的函数\"@NOPE\"", e.message)
        assertEquals("main", e.funcName)
        assertEquals(3, e.line, "错误定位取 _CALL_BY_NAME 的行号")
        assertEquals(2, e.pc)
        assertEquals(listOf("enter:0", "exit:0"), probe.events)
    }

    @Test
    fun `非法目标即使带 try 位也必须抛且 VM 不吞`() {
        // 事件名 / #FUNCTION 方法名：resolveFunctionIndex 抛错（spec: CalledFunction.cs:100-103, 106-109）
        val program = byNameCall(argc = 1, tryName = true, writtenName = "EVENTFIRST")
        val probe = Probe().apply {
            table = indexTable(program)
            resolveErrors = mapOf(
                "EVENTFIRST" to
                    "对事件函数\"@EVENTFIRST\"进行CALL调用" +
                    "(此警告可以用兼容性配置「イベント関数のCALLを許可する/ALLOW CALL ON EVENT FUNCTIONS」来无视)",
            )
        }

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertTrue("对事件函数\"@EVENTFIRST\"进行CALL调用" in e.message!!, "实际文案：${e.message}")
        assertEquals(listOf("enter:0", "exit:0"), probe.events, "抛错不得退化成跳过、更不得建帧")
    }

    @Test
    fun `try 位不污染实参数`() {
        // argc=2 + try：bit31 若漏进 argc，argc 变负 → 一个实参也不复制 → 和被印成 1 而非 21。
        // try 命中时返回槽是"是否真调用"标志（契约 R3），故被调函数自己打印和，不靠返回值回传。
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), 0, 4)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(20L), line = 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(1L), line = 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(2), b.string("add"), line = 3)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(2), nameArgc(2, true), SlotAddr.local(3), line = 4)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(3), 1, line = 5)
        b.emit(Opcode._HALT, line = 6)
        b.declareFunction("add", b.here(), 2, 4)
        b.emit(Opcode._ADD, SlotAddr.local(2), SlotAddr.local(0), SlotAddr.local(1), line = 10)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(2), 1, line = 11)
        b.emit(Opcode._RET, SlotAddr.NONE, line = 12)
        val program = b.build(main)

        val probe = Probe().apply { table = indexTable(program) }
        vmRun(program, probe)
        assertEquals(listOf("add"), probe.resolved)
        assertEquals("21\n1\n", probe.output.toString(), "实参完整复制（20+1=21）+ 命中标志 1")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
    }

    @Test
    fun `按名递归共用调用栈深度闸`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("rec"), line = 1)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), nameArgc(0, false), SlotAddr.local(0), line = 2)
        b.emit(Opcode._HALT, line = 3)
        b.declareFunction("rec", b.here(), 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("rec"), line = 10)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), nameArgc(0, false), SlotAddr.local(0), line = 11)
        b.emit(Opcode._RET, SlotAddr.NONE, line = 12)
        val program = b.build(main)

        val probe = Probe().apply { table = indexTable(program) }
        val vm = VirtualMachine(program, probe, VmConfig(maxCallDepth = 2))
        assertFailsWith<CallStackOverflow> { vm.run() }
        // 深度 2 用尽后抛出：main 1 帧 + rec 2 帧，finally 逐个 leave，不许多也不许多 leave 一次。
        assertEquals(
            listOf("enter:0", "enter:1", "enter:1", "exit:1", "exit:1", "exit:0"),
            probe.events,
        )
        assertEquals(0, probe.depth)
    }

    @Test
    fun `宿主给出越界函数下标时按名调用同样拒绝`() {
        val program = byNameCall(argc = 0, tryName = false, writtenName = "GHOST")
        val probe = Probe().apply { table = mapOf("GHOST" to 999) }

        val e = assertFailsWith<CodeEE> { VirtualMachine(program, probe).run() }
        assertTrue("函数下标越界(999)" in e.message!!, "实际文案：${e.message}")
        assertEquals(listOf("enter:0", "exit:0"), probe.events, "下标校验在 onFunctionEnter 之前")
    }

    @Test
    fun `静态 _CALL 的分派不受语句钩子影响`() {
        // 反向可证：若把 29..197 的判定放宽到"非内部指令"，_CALL(22) 会被当成语句送进钩子。
        val s0 = stmt(raw = "PRINTL 只此一条")
        val program = Assembler.assemble(
            """
            .func main slots=1
              _CALL sub, 0, none
              _HALT

            .func sub slots=1
              PRINTL stmt#0
              _RET
            """.trimIndent(),
            statements = listOf(s0),
        )
        val probe = Probe()
        vmRun(program, probe)
        assertEquals(1, probe.seen.size, "只有 marker 进钩子")
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), probe.events)
    }

    // ------------------------------------------------------------------
    // 手工程序：按名调用（main 把名字写进 R2，调用 sub；sub 返回 R0*1）
    // ------------------------------------------------------------------

    /**
     * ```
     * main:  _SET_INT R0, 21              ; 行 1，实参
     *        _SET_STR R2, <writtenName>   ; 行 2，名字槽
     *        _CALL_BY_NAME R2, (argc|try), R3   ; 行 3
     *        _PRINT_INT R3, 1             ; 行 4（返回值写回证明；未调用时打印 0）
     *        _PRINT_LITERAL "tail"        ; 行 5（try 跳过后续行仍执行的证明）
     *        _HALT                        ; 行 6
     * sub:   _RET R0                      ;  arity=1
     * ```
     */
    private fun byNameCall(argc: Int, tryName: Boolean, writtenName: String = "SUB"): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", b.here(), 0, 4)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(21L), line = 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(2), b.string(writtenName), line = 2)
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(2), nameArgc(argc, tryName), SlotAddr.local(3), line = 3)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(3), 1, line = 4)
        b.emit(Opcode._PRINT_LITERAL, b.string("tail"), 1, line = 5)
        b.emit(Opcode._HALT, line = 6)
        b.declareFunction("sub", b.here(), 1, 2)
        b.emit(Opcode._RET, SlotAddr.local(0), line = 10)
        return b.build(main)
    }

    private fun vmRun(program: Program, host: VmHost, config: VmConfig = VmConfig()): Long =
        VirtualMachine(program, host, config).run()
}
