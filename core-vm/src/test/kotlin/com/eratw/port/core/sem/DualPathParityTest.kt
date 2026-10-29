package com.eratw.port.core.sem

import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.fn.NativeMethod
import com.eratw.port.core.fn.NativeParam
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.VarWriteProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T2-64 波次 C（lane G）：**双路等价对拍装置**（单测形态）。
 *
 * ## 这个装置回答什么
 *
 * 波次 B 起 `StatementExecutor` 有两条入口：
 * - **树路径** [StatementExecutor.execute] `(stmt, ctx)` —— 波次 A 之前的唯一语义实现；
 * - **扁平路径** [StatementExecutor.execute] `(source, index, ctx)` —— ASSIGN / PRINT 家族 / BAR 系
 *   直读参数流，其余经 `source.statementAt(index)` 回落树路径。
 *
 * 两条路径必须**逐字同义**。E2E 黄金管道（stdout）证明的是「进程级可观测面」，覆盖不到
 * 「某条语句在某个分支上的写面/异常文案」；本装置在同一初始状态下把**同一条语句**分别喂给
 * 两条路径，比对四个轴：
 *
 * | 轴 | 观测面 |
 * |---|---|
 * | ① 控制台 | [DualPathConsoleSink] 的全部动作（含 `print` 的 lineEnd 位、样式开关、帧边界） |
 * | ② 语句结果 | `ExecResult` 的类别 + 载荷（Normal / Quit(restart,force) / Begin(target) / Return(value)） |
 * | ③ 异常 | [CodeEE] 的文案 + 补齐后的 `funcName/line/pc`（见 [renderError] 的 stamp 口径） |
 * | ④ 变量写面 | [DualPathWriteProbe] 的写入事件流（名字 + 下标 + 值，按发生序）+ 抽样的终态读值 |
 *
 * 另有三个辅助轴：输入请求流（`InputSink` 收到的 `InputRequest` 全文）、
 * `yieldBoundaryCount`（让出边界计数，`AWAIT`/输入族语义的一部分）、宿主钩子调用
 * （[DualPathSystemOps] / 定时器参数）与抽样终态（[sampleState]）。
 *
 * ## 装置纪律：每个用例重建两份语境
 *
 * 两路各拿一份**干净**的 [DualPathEnv]（各自的 `VariableStore` / sink / `ExecContext`），
 * 初始状态由同一批 setup 语句在**两条路上都以树路径**执行得到（见 [assertParity]），
 * 然后才各跑一次目标语句。这样第一路的副作用不可能污染第二路 —— 否则「先 SET 再 PRINT」
 * 这类用例会把「第二路读到了第一路的写入」伪装成等价。
 *
 * ## 分支自检：等价必须发生在**同一个被比较的分支**上
 *
 * 波次 B 的直读分支只有 ASSIGN / PRINT 家族 / BAR 系。若某条「本该直读」的语句悄悄回落到
 * `statementAt`，两侧跑的都是同一份树实现，「等价」就没有信息量。因此 [DualPathFlatSource]
 * 记 `statementAt` 的调用次数，[ParityCase.directRead] 逐条断言分支归属：
 * `true` = 必须不回落，`false` = 必须回落一次。
 *
 * ## 与 `:core-vm:dualPathAudit` 的分工
 *
 * 本文件同时提供真语料装置（`boot/DualPathCorpusMain.kt`）复用的 [DualPathEnv] /
 * [DualPathObs] / [DualPathFlatSource] / [observe]；语句族的「可安全执行」判定表写在
 * 那个 main 的 KDoc 里（本文件只覆盖确定性语句）。
 *
 * ## 数据节（PRINTDATA / STRDATA）的过渡写法
 *
 * 当前 `DataSections` 的键是**语句实例**（`groupsOf(stmt)`），所以装置用 [MapDataSections]
 * 按实例取组。车道 C/D 把键换成语句池下标（`groupsOf(index)`）之后，这个替身必须同步改成
 * 下标键 —— 否则执行器的读点会打到「会抛 UnsupportedOperationException」的过渡重载上。
 * 这是本文件与跨路 API 的唯一耦合点，改签名时一起改（已写进交付消息的「需组长接线」）。
 *
 * ## 波次 C 车道 D 合入后的形态（写给收口人）
 *
 * 车道 D 删掉旧入口 `execute(stmt, ctx)` 之后，本文件的树路径一侧**编译期即红**（这是刻意的：
 * 删入口必须同步改闸）。届时把树路径一侧换成「`source.statementAt(index)` → 直读入口」的
 * 还原对照，或按 `CONTRACT-T2-64-C §3-D` 的口径把本装置收敛成 code 层的对拍器。
 * 在此之前它就是「波次 B 两条路等价」的唯一闸。
 */
class DualPathParityTest {

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 用例：setup 语句（两路都用树路径跑）+ 一条目标语句 + 装配/注入钩子。 */
    private class ParityCase(
        val name: String,
        val setup: List<Statement> = emptyList(),
        val target: Statement,
        /**
         * 扁平路径**应走哪条分支**：true = 直读（不得调 `statementAt`）；
         * false = 回落树路径（必须恰好调一次）；null = 不检查。
         */
        val directRead: Boolean? = null,
        /** 建 templates / 注册 native 方法这类**装配**动作，两路各调一次（不许共享可变对象）。 */
        val preInit: ((DualPathEnv) -> Unit)? = null,
        /** `ExecContext` 上的注入（数据节 / 输入作答 / 宿主钩子）。 */
        val configure: ((ExecContext) -> Unit)? = null,
        /** true = 断言目标语句确实产生了可观测变化（防「两路都没做事」的空跑绿灯）。 */
        val expectChange: Boolean = true,
    )

    private val parser = LineParser()

    /** 按真实 ERB 文本造语句（走装载期的同一套解析器，语料形态同源）。 */
    private fun stmt(text: String): Statement = parser.parseCodeLine(text, 1)

    // ---------------- SET ----------------

    @Test
    fun `SET 常量赋值两路一致`() = assertParity(
        ParityCase(
            "SET 常量",
            setup = listOf(stmt("DAY = 10")),
            target = stmt("DAY = 42"),
            directRead = true,
        ),
    )

    @Test
    fun `SET 常量增量加两路一致`() = assertParity(
        ParityCase(
            "SET += 常量",
            setup = listOf(stmt("DAY = 10")),
            target = stmt("DAY += 2"),
            directRead = true,
        ),
    )

    @Test
    fun `SET 非常量增量加两路一致`() = assertParity(
        ParityCase(
            "SET += 变量",
            setup = listOf(stmt("DAY = 10"), stmt("MONEY = 5")),
            target = stmt("DAY += MONEY"),
            directRead = true,
        ),
    )

    @Test
    fun `SET 字符串赋值两路一致`() = assertParity(
        ParityCase(
            "SET 字符串",
            setup = listOf(stmt("RESULTS:0 = \"旧值\"")),
            target = stmt("RESULTS:0 = \"新值\""),
            directRead = true,
        ),
    )

    @Test
    fun `SET 字符串增量加两路一致`() = assertParity(
        ParityCase(
            "SET 字符串 +=",
            setup = listOf(stmt("RESULTS:0 = \"旧\"")),
            target = stmt("RESULTS:0 += \"值\""),
            directRead = true,
        ),
    )

    // ---------------- PRINT 家族 ----------------

    @Test
    fun `PRINT 不换行两路一致`() = assertParity(
        ParityCase("PRINT", target = stmt("PRINT \"hello\""), directRead = true),
    )

    @Test
    fun `PRINTL 换行两路一致`() = assertParity(
        ParityCase("PRINTL", target = stmt("PRINTL \"line\""), directRead = true),
    )

    @Test
    fun `PRINTV 混合项两路一致`() = assertParity(
        ParityCase(
            "PRINTV",
            setup = listOf(stmt("DAY = 3")),
            target = stmt("PRINTV DAY:0, \" / \", 7"),
            directRead = true,
        ),
    )

    @Test
    fun `PRINTSINGLE 单行两路一致`() = assertParity(
        ParityCase("PRINTSINGLE", target = stmt("PRINTSINGLE \"single\""), directRead = true),
    )

    @Test
    fun `PRINTW 换行加等待两路一致`() = assertParity(
        ParityCase("PRINTW", target = stmt("PRINTW \"wait\""), directRead = true),
    )

    @Test
    fun `PRINTDL 的 D 位取色窗口两路一致`() = assertParity(
        ParityCase("PRINTDL(D 位)", target = stmt("PRINTDL \"dark\""), directRead = true),
    )

    @Test
    fun `PRINTFORML 的 FORM 位两路一致`() = assertParity(
        ParityCase(
            "PRINTFORML",
            setup = listOf(stmt("DAY = 9")),
            target = stmt("PRINTFORML \"n={DAY:0}\""),
            directRead = true,
        ),
    )

    @Test
    fun `PRINTC 定宽两路一致`() = assertParity(
        ParityCase("PRINTC", target = stmt("PRINTC \"centered\""), directRead = true),
    )

    // ---------------- BAR / BARL ----------------

    @Test
    fun `BAR 两路一致`() = assertParity(
        ParityCase("BAR", target = stmt("BAR 5, 10, 20"), directRead = true),
    )

    @Test
    fun `BARL 两路一致`() = assertParity(
        ParityCase("BARL", target = stmt("BARL 7, 10, 20"), directRead = true),
    )

    // ---------------- 数据节（回落分支） ----------------

    @Test
    fun `PRINTDATA 有数据节两路一致`() {
        // 数据节键是语句实例（当前口径），两路共享同一批语句实例 ⇒ 同一份 groups 生效。
        val target = instr(FunctionCode.PRINTDATAL, PrintDataArgument(iVar("RESULT", 0)))
        val groups = listOf(listOf(sLit("甲行"), sLit("乙行")), listOf(sLit("丙行")))
        assertParity(
            ParityCase(
                "PRINTDATAL 有数据节",
                target = target,
                directRead = false,
                configure = { ctx -> ctx.dataSections = MapDataSections(mapOf(target to groups)) },
            ),
        )
    }

    @Test
    fun `PRINTDATA 空数据节两路一致`() {
        val target = instr(FunctionCode.PRINTDATAW, PrintDataArgument(null))
        assertParity(
            ParityCase(
                "PRINTDATAW 空数据节",
                target = target,
                directRead = false,
                configure = { ctx -> ctx.dataSections = MapDataSections(mapOf(target to emptyList())) },
            ),
        )
    }

    @Test
    fun `PRINTDATA 无数据节供给两路一致`() {
        val target = instr(FunctionCode.PRINTDATADL, PrintDataArgument(null))
        assertParity(ParityCase("PRINTDATADL 无供给", target = target, directRead = false))
    }

    @Test
    fun `STRDATA 有数据节两路一致`() {
        val target = DataStmt(FunctionCode.STRDATA, "", StrDataArgument(sVar("RESULTS", 0)), 1, "STRDATA")
        val groups = listOf(listOf(sLit("上"), sLit("下")))
        assertParity(
            ParityCase(
                "STRDATA 有数据节",
                target = target,
                directRead = false,
                configure = { ctx -> ctx.dataSections = MapDataSections(mapOf(target to groups)) },
            ),
        )
    }

    // ---------------- 输入族（假 InputSink 固定作答） ----------------

    @Test
    fun `INPUT 整数交付写 RESULT 两路一致`() = assertParity(
        ParityCase(
            "INPUT",
            target = instr(FunctionCode.INPUT, SpInputsArgument(null, null, null)),
            directRead = false,
            configure = { ctx ->
                // 固定作答：两路各持一份 sink，但作答内容相同 ⇒ 初始状态相同。
                (ctx.input as DualPathInputSink).outcome = InputOutcome(intValue = 123L)
            },
        ),
    )

    @Test
    fun `INPUTS 字符串交付写 RESULTS 两路一致`() = assertParity(
        ParityCase(
            "INPUTS",
            target = instr(FunctionCode.INPUTS, SpInputsArgument(null, null, null)),
            directRead = false,
            configure = { ctx ->
                (ctx.input as DualPathInputSink).outcome = InputOutcome(strValue = "打字")
            },
        ),
    )

    @Test
    fun `BINPUT 先换行再取默认值两路一致`() = assertParity(
        ParityCase(
            "BINPUT",
            target = instr(FunctionCode.BINPUT, SpInputsArgument(iLit(2), null, null)),
            directRead = false,
        ),
    )

    @Test
    fun `TINPUT 限时与默认值两路一致`() = assertParity(
        ParityCase(
            "TINPUT",
            target = instr(
                FunctionCode.TINPUT,
                SpTInputsArgument(iLit(1000), iLit(11), null, null, null, null),
            ),
            directRead = false,
            configure = { ctx ->
                (ctx.input as DualPathInputSink).outcome = InputOutcome(intValue = 5L, isTimeout = true)
            },
        ),
    )

    // ---------------- 数组族 ----------------

    @Test
    fun `ARRAYSHIFT 两路一致`() {
        val setup = (0 until 5).map { stmt("FLAG:$it = ${it + 1}") }
        val target = instr(
            FunctionCode.ARRAYSHIFT,
            SpArrayShiftArgument(iVar("FLAG", 0), iLit(1), iLit(0), iLit(0), iLit(5)),
        )
        assertParity(ParityCase("ARRAYSHIFT", setup = setup, target = target, directRead = false))
    }

    @Test
    fun `ARRAYSORT 两路一致`() {
        val setup = listOf(stmt("FLAG:0 = 3"), stmt("FLAG:1 = 1"), stmt("FLAG:2 = 2"))
        val target = instr(
            FunctionCode.ARRAYSORT,
            SpArraySortArgument(iVar("FLAG", 0), SortOrder.ASCENDING, iLit(0), iLit(3)),
        )
        assertParity(ParityCase("ARRAYSORT", setup = setup, target = target, directRead = false))
    }

    // ---------------- 角色表族 ----------------

    @Test
    fun `ADDCHARA 两路一致`() = assertParity(
        ParityCase(
            "ADDCHARA",
            target = instr(
                FunctionCode.ADDCHARA,
                ExpressionArrayArgument(listOf(iLit(10), iLit(20))),
            ),
            directRead = false,
            preInit = { env -> registerTemplates(env.store) },
        ),
    )

    @Test
    fun `DELCHARA 两路一致`() {
        val add = instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(listOf(iLit(10), iLit(20))))
        val target = instr(FunctionCode.DELCHARA, ExpressionArrayArgument(listOf(iLit(0))))
        assertParity(
            ParityCase(
                "DELCHARA",
                setup = listOf(add),
                target = target,
                directRead = false,
                preInit = { env -> registerTemplates(env.store) },
            ),
        )
    }

    // ---------------- 宿主钩子族 ----------------

    @Test
    fun `SAVEDATA 两路一致`() = assertParity(
        ParityCase(
            "SAVEDATA",
            target = instr(FunctionCode.SAVEDATA, SpSaveDataArgument(iLit(1), sLit("存档说明"))),
            directRead = false,
        ),
    )

    @Test
    fun `CALLSHARP 回写实参两路一致`() {
        val call = SpCallSharpArgument(sLit("BUMP"), emptyList(), listOf(iVar("DAY", 3))).also {
            it.isConst = true
            it.constStr = "BUMP"
        }
        assertParity(
            ParityCase(
                "CALLSHARP",
                setup = listOf(stmt("DAY:3 = 10")),
                target = instr(FunctionCode.CALLSHARP, call),
                directRead = false,
                preInit = { env ->
                    nativeMethods(env.store.aux).register("BUMP", NativeMethod { p: Array<NativeParam> ->
                        p[0].intValue += 7
                    })
                },
            ),
        )
    }

    // ---------------- AWAIT ----------------

    @Test
    fun `AWAIT 负值报错两路一致`() = assertParity(
        ParityCase("AWAIT -1", target = instr(FunctionCode.AWAIT, intArg(-1)), directRead = false),
    )

    @Test
    fun `AWAIT 正值的定时与让出边界两路一致`() = assertParity(
        ParityCase("AWAIT 16", target = instr(FunctionCode.AWAIT, intArg(16)), directRead = false),
    )

    // ---------------- 错误面（越界 / 除零 / THROW） ----------------

    @Test
    fun `变量下标越界写两路一致`() = assertParity(
        ParityCase("DAY 越界写", target = stmt("DAY:999999 = 1"), directRead = true),
    )

    @Test
    fun `变量下标越界读两路一致`() = assertParity(
        ParityCase("PRINTV 越界读", target = stmt("PRINTV FLAG:999999"), directRead = true),
    )

    @Test
    fun `除零求值错误两路一致`() = assertParity(
        ParityCase("除零", target = stmt("DAY = 1 / 0"), directRead = true),
    )

    @Test
    fun `THROW 自定义文案两路一致`() = assertParity(
        ParityCase("THROW", target = stmt("THROW \"boom\""), directRead = false),
    )

    // ------------------------------------------------------------------
    // 装置自检：观测面对差异必须敏感（否则上面的全绿没有意义）
    // ------------------------------------------------------------------

    /** 写面敏感：两条不同赋值的写事件流必须不等。 */
    @Test
    fun `装置自检 写面差异必被抓到`() {
        val a = DualPathEnv()
        val b = DualPathEnv()
        val s1 = stmt("DAY = 1")
        val s2 = stmt("DAY = 2")
        val oa = observe(a, s1, 0) { executeTreePath(a.executor, s1, a.ctx) }
        val ob = observe(b, s2, 0) { executeTreePath(b.executor, s2, b.ctx) }
        assertEquals("writes", oa.firstDiff(ob), "两条不同赋值必须落在 writes 轴上")
        assertEquals(listOf("int DAY[0]=1"), oa.writes)
    }

    /** 终态敏感：绕过写观察口的族（角色表）也必须被抽样终态抓住。 */
    @Test
    fun `装置自检 角色表变化必被终态抽样抓到`() {
        val a = DualPathEnv()
        val b = DualPathEnv()
        registerTemplates(a.store)
        registerTemplates(b.store)
        val add = instr(FunctionCode.ADDCHARA, ExpressionArrayArgument(listOf(iLit(10))))
        val noop = instr(FunctionCode.NOSKIP) // 无行为语句：只用来让两侧都执行一次
        val oa = observe(a, add, 0) { executeTreePath(a.executor, add, a.ctx) }
        val ob = observe(b, noop, 0) { executeTreePath(b.executor, noop, b.ctx) }
        assertEquals("state", oa.firstDiff(ob), "角色表变化不在写观察口上，只能靠终态抽样")
        assertTrue(oa.state.first() == "charas=1" && ob.state.first() == "charas=0")
    }

    /** 异常面敏感：两条不同文案的 CodeEE 必须落在 error 轴上。 */
    @Test
    fun `装置自检 异常文案差异必被抓到`() {
        val a = DualPathEnv()
        val b = DualPathEnv()
        val s1 = instr(FunctionCode.AWAIT, intArg(-1))
        val s2 = stmt("THROW \"boom\"")
        val oa = observe(a, s1, 0) { executeTreePath(a.executor, s1, a.ctx) }
        val ob = observe(b, s2, 1) { executeTreePath(b.executor, s2, b.ctx) }
        assertEquals("error", oa.firstDiff(ob), "两条不同错误文案必须落在 error 轴上")
        assertEquals("CodeEE|AWAIT命令:指定了一个负值(-1)|DUAL|1|0", oa.error)
    }

    /**
     * 直读分支敏感（故障注入）：把扁平流里 PRINT 记录的 `isC` 位（0x020）打开，
     * 直读分支应当从 `print` 走到 `printC` —— 树路径不变。这一条证明
     * 「直读分支的语义差异会被 console 轴抓住」，而不是只有回落分支在跑。
     *
     * 槽序依据：`FlatStmtSource` 的接口 KDoc（槽 0 = tag、1 = op、2..4 = argType/bits/flag）。
     */
    @Test
    fun `装置自检 直读分支被注入偏差时必现差异`() {
        val target = stmt("PRINTL \"x\"")
        val source = DualPathFlatSource(listOf(target))
        val head = source.headAt(0)
        source.argStreamBase()[head + 3] = source.argStreamBase()[head + 3] or 0x020
        val flat = DualPathEnv()
        val tree = DualPathEnv()
        val a = observe(flat, target, 0) { flat.executor.execute(source, 0, flat.ctx) }
        val b = observe(tree, target, 0) { executeTreePath(tree.executor, target, tree.ctx) }
        assertEquals(0, source.statementAtCalls, "本用例必须走直读分支（否则注入不生效）")
        assertEquals("console", a.firstDiff(b), "直读分支的 isC 位被注入偏差后，console 轴必须不等")
    }

    // ------------------------------------------------------------------
    // 引擎
    // ------------------------------------------------------------------

    private fun registerTemplates(store: VariableStore) {
        store.registerTemplate(CharacterTemplate(no = 10, name = "甲", csvNo = 10))
        store.registerTemplate(CharacterTemplate(no = 20, name = "乙", csvNo = 20))
    }

    /**
     * 双跑一条用例并逐轴比对。
     *
     * 前提断言：两路 setup 之后的抽样状态必须**逐字相同**——否则后面的差异无法归因到
     * 被测语句（装置自检，不是被测语义）。
     */
    private fun assertParity(case: ParityCase) {
        val statements = ArrayList<Statement>(case.setup.size + 1)
        statements.addAll(case.setup)
        statements.add(case.target)
        val index = statements.lastIndex
        val source = DualPathFlatSource(statements)

        val tree = DualPathEnv()
        val flat = DualPathEnv()
        case.preInit?.invoke(tree)
        case.preInit?.invoke(flat)
        case.configure?.invoke(tree.ctx)
        case.configure?.invoke(flat.ctx)
        for (s in case.setup) {
            executeTreePath(tree.executor, s, tree.ctx)
            flat.executor.execute(s, flat.ctx)
        }

        val before = sampleState(tree.store)
        assertEquals(before, sampleState(flat.store), "${case.name}：两条路的初始状态必须一致（装置前提）")

        val a = observe(tree, case.target, index) { executeTreePath(tree.executor, case.target, tree.ctx) }
        val b = observe(flat, case.target, index) { flat.executor.execute(source, index, flat.ctx) }

        // 波次 C 起 37 个 StmtTag 全量直读：**任何**用例都不得回落 statementAt。
        // 波次 B 时代的 directRead 标记（true = 直读 / false = 兜底 / null = 不查）随兜底分支一起
        // 失去区分度，字段保留只为不改 40 条用例的构造调用；判据在这里统一钉成 0。
        assertEquals(
            0,
            source.statementAtCalls,
            "${case.name}：波次 C 全量直读，不得回落 statementAt（实调 ${source.statementAtCalls} 次）",
        )

        assertEquals(a.console, b.console, "${case.name}：控制台输出不一致")
        assertEquals(a.input, b.input, "${case.name}：输入请求不一致")
        assertEquals(a.host, b.host, "${case.name}：宿主钩子调用不一致")
        assertEquals(a.writes, b.writes, "${case.name}：变量写面不一致")
        assertEquals(a.state, b.state, "${case.name}：抽样终态不一致")
        assertEquals(a.result, b.result, "${case.name}：ExecResult 不一致")
        assertEquals(a.error, b.error, "${case.name}：异常面不一致")
        assertEquals(a.yieldBoundary, b.yieldBoundary, "${case.name}：让出边界计数不一致")
        assertEquals(a, b, "${case.name}：整体观测面不一致")

        if (case.expectChange) {
            assertTrue(
                a.console.isNotEmpty() || a.writes.isNotEmpty() || a.host.isNotEmpty() ||
                    a.input.isNotEmpty() || a.error != null || a.state != before ||
                    a.result != "Normal",
                "${case.name}：目标语句没有产生任何可观测变化（用例空跑，闸没有意义）",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 装置：环境 / sink / 观测
// ---------------------------------------------------------------------------

/** 记录全部控制台动作的 sink（含 `print` 的 lineEnd 位、样式开关与帧边界）。 */
internal class DualPathConsoleSink : FakeConsoleSink() {
    override fun print(text: String, lineEnd: Boolean) {
        events.add("print($text,$lineEnd)")
    }

    override fun setUseUserStyle(value: Boolean) {
        events.add("setUseUserStyle($value)")
    }

    override fun setUseSetColorStyle(value: Boolean) {
        events.add("setUseSetColorStyle($value)")
    }

    override fun publishFrame() {
        events.add("publishFrame")
    }

    override fun holdFrame() {
        events.add("holdFrame")
    }

    override fun noteInputWait(request: InputRequest) {
        waitNotes.add(request)
        events.add("noteInputWait(${request.form},${request.type},${request.oneInput})")
    }
}

/** 记录输入请求、按预设结果作答的 sink（两路各持一份，作答内容相同）。 */
internal class DualPathInputSink(
    var outcome: InputOutcome = InputOutcome(intValue = 7L, strValue = "typed"),
) : InputSink {
    val events = ArrayList<String>()
    var nextMesSkip: Boolean = false

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        events.add("readAnyKey($anyKey,$stopMesSkip)")
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        // InputRequest 是 data class：toString 覆盖了全部字段（含 scriptLine/timeLimit 等）。
        events.add("waitInput($request)")
        return outcome
    }

    override fun consumeMesSkip(): Boolean {
        val value = nextMesSkip
        nextMesSkip = false
        events.add("consumeMesSkip($value)")
        return value
    }
}

/** 记录 `AWAIT` 的延时参数（不真睡：装置只关心语义）。 */
internal class DualPathTimerSink(val log: MutableList<String>) : TimerSink {
    override fun delay(millis: Long) {
        log.add("delay($millis)")
    }
}

/**
 * 变量写观察口：`watches` 恒真 ⇒ 全量记录写入事件（名字 + 下标 + 值，按发生序）。
 *
 * 口径 = 写入**入口**（`VariableStore.writeIntEntry` 的挂点，校验前）：与原生调试观察口一致，
 * 校验失败的写入也有事件。因此「两路都尝试写同一个越界格」也会逐字对上。
 */
internal class DualPathWriteProbe : VarWriteProbe {
    val events = ArrayList<String>()

    override fun watches(name: String): Boolean = true

    override fun onWriteInt(name: String, indices: LongArray, value: Long) {
        events.add("int $name[${indices.joinToString(",")}]=$value")
    }

    override fun onWriteStr(name: String, indices: LongArray, value: String) {
        events.add("str $name[${indices.joinToString(",")}]=$value")
    }
}

/**
 * 记录型 [SystemOps]：两路都装同一套（接口缺省实现会抛「宿主未提供」）。
 *
 * 装上的理由：`SAVEDATA` 一族要**可比**地执行完并把调用参数纳入观测面；不装的话两路都抛
 * 同一条"未实现"，覆盖不到真实分支。
 */
internal class DualPathSystemOps(val log: MutableList<String>) : SystemOps {
    override fun saveData(no: Int, text: String): Boolean {
        log.add("saveData($no,$text)")
        return true
    }

    override fun loadData(no: Int) {
        log.add("loadData($no)")
    }

    override fun delData(no: Int) {
        log.add("delData($no)")
    }

    override fun saveGame() {
        log.add("saveGame")
    }

    override fun loadGame() {
        log.add("loadGame")
    }

    override fun saveGlobal() {
        log.add("saveGlobal")
    }

    override fun loadGlobal(): Boolean {
        log.add("loadGlobal")
        return false
    }

    override fun saveChara(nos: List<Long>, filename: String, text: String) {
        log.add("saveChara(${nos.joinToString(",")},$filename,$text)")
    }

    override fun loadChara(filename: String) {
        log.add("loadChara($filename)")
    }

    override fun updateCheck(): Long {
        log.add("updateCheck")
        return 0L
    }

    override fun callTrain(no: Long) {
        log.add("callTrain($no)")
    }

    override fun stopCallTrain() {
        log.add("stopCallTrain")
    }

    override fun doTrain(no: Long) {
        log.add("doTrain($no)")
    }

    override fun upcheck() {
        log.add("upcheck")
    }

    override fun cupcheck(target: Long) {
        log.add("cupcheck($target)")
    }
}

/**
 * 生产 `FlatStmtSource` 的装置替身：数据面走 [FlatStatements]，
 * [statementAt] 指回**原始语句实例**（与 `Program.PoolBackedFlatStmtSource` 同一契约）。
 */
internal class DualPathFlatSource(private val statements: List<Statement>) : FlatStmtSource {
    private val flat = FlatStatements.encode(statements, emptyList())

    /**
     * [statementAt] 的调用次数 = 本路**回落树路径**的语句数。
     *
     * 分支判定用：直读分支（ASSIGN / PRINT 家族 / BAR 系）不碰 [statementAt]，
     * 其余语句必然调一次。装置据此证明「这一次执行真的走了预期的分支」，
     * 而不是两条路都在跑同一份树实现（那样的「等价」没有信息量）。
     */
    var statementAtCalls: Int = 0
        private set

    /** 参数流槽数（诊断/读数用）。 */
    val streamSize: Int get() = flat.stream.size

    override fun opAt(index: Int): Int = flat.opAt(index)

    override fun headAt(index: Int): Int = flat.headAt(index)

    override fun argStreamBase(): IntArray = flat.stream

    override fun exprAt(poolIdx: Int): Expr? =
        if (poolIdx == ErbcFormat.NONE_REF) null else flat.exprAt(poolIdx)

    override fun stringAt(poolIdx: Int): String = flat.strAt(poolIdx)

    override fun statementAt(index: Int): Statement {
        statementAtCalls++
        return statements[index]
    }
}

/**
 * 一份干净的执行语境（变量池 + sink + [ExecContext] + 执行器）。
 *
 * [clearObservations] 只清**观测缓冲**，不动状态：用例在 setup 之后调一次，
 * 让比对只覆盖目标语句（真语料装置也是逐条调用）。
 */
internal class DualPathEnv(store: VariableStore = VariableStore()) {
    val store: VariableStore = store
    val console = DualPathConsoleSink()
    val input = DualPathInputSink()
    val writes = DualPathWriteProbe()
    val systemOpsLog = ArrayList<String>()
    val timerLog = ArrayList<String>()
    val executor = StatementExecutor()
    val ctx: ExecContext

    init {
        // ADR-001：RNG 必须经注入的 provider（PRINTDATA 家族按原版语义随机选组）。
        this.store.randProvider = Sfmt19937(0L)
        this.store.writeProbe = writes
        ctx = ExecContext(
            variables = this.store,
            eval = TestEvalContext(this.store),
            console = console,
            input = input,
            timer = DualPathTimerSink(timerLog),
        )
        ctx.systemOps = DualPathSystemOps(systemOpsLog)
    }

    fun clearObservations() {
        console.events.clear()
        console.waitNotes.clear()
        input.events.clear()
        writes.events.clear()
        systemOpsLog.clear()
        timerLog.clear()
    }
}

/** 一次执行的完整观测面（四个轴 + 三个辅助读数）。 */
internal data class DualPathObs(
    val console: List<String>,
    val input: List<String>,
    val host: List<String>,
    val writes: List<String>,
    val state: List<String>,
    val result: String,
    val error: String?,
    val yieldBoundary: Long,
)

/** 首处差异的字段名（null = 两侧完全相同），给真语料装置做首差归因。 */
internal fun DualPathObs.firstDiff(other: DualPathObs): String? = when {
    console != other.console -> "console"
    input != other.input -> "input"
    host != other.host -> "host"
    writes != other.writes -> "writes"
    state != other.state -> "state"
    result != other.result -> "result"
    error != other.error -> "error"
    yieldBoundary != other.yieldBoundary -> "yieldBoundary"
    else -> null
}

/** 装置假定的函数键（[renderError] 的 stamp 口径用；纯 sem 装置没有真帧）。 */
internal const val DUAL_PATH_FUNCTION_KEY = "DUAL"

/**
 * **树路径调用的唯一接缝** —— 车道 D 合入、旧入口 `execute(stmt, ctx)` 被删时只改这里。
 *
 * 现在直接调旧入口。D 交付之后，可选择：
 * 1. 换成 code 层的还原对照（`FlatStatements.statementAt(index)` 造树再走旧语义的副本）；
 * 2. 换成 D 交付的 code 层对拍器（`CONTRACT-T2-64-C §3-D` 的「对拍装置保留在 code 层」）。
 *
 * 两个装置（单测 + 真语料）都只经本函数触树路径，故适配点只有一处；不在这里改成反射
 * 调「可能存在的方法」——那会在删入口后静默退化成空闸。
 */
internal fun executeTreePath(
    executor: StatementExecutor,
    stmt: Statement,
    ctx: ExecContext,
): ExecResult = executor.execute(stmt, ctx)

/**
 * 执行一次并收集观测面。异常**不外抛**（它本身就是被比对的一轴）。
 *
 * @param stmt 目标语句（stamp 需要它的行号）
 * @param index 语句在池里的下标（stamp 的 pc 位）
 * @param probeSeed 终态抽样的旋转种子（真语料装置传语句下标，让抽样格随语句推进而
 *   在数组上滑动；单测固定 0）。两侧**必须用同一个种子**，否则抽样面不可比。
 */
internal fun observe(
    env: DualPathEnv,
    stmt: Statement,
    index: Int,
    probeSeed: Int = 0,
    block: () -> ExecResult,
): DualPathObs {
    env.clearObservations()
    var result: ExecResult? = null
    var error: String? = null
    try {
        result = block()
    } catch (t: Throwable) {
        error = renderError(t, stmt, index)
    }
    return DualPathObs(
        console = ArrayList(env.console.events),
        input = ArrayList(env.input.events),
        host = ArrayList(env.systemOpsLog) + env.timerLog,
        writes = ArrayList(env.writes.events),
        state = sampleState(env.store, probeSeed),
        result = renderResult(result),
        error = error,
        yieldBoundary = env.executor.yieldBoundaryCount,
    )
}

/** `ExecResult` 的类别 + 载荷文本。 */
internal fun renderResult(result: ExecResult?): String = when (result) {
    null -> "<threw>"
    ExecResult.Normal -> "Normal"
    is ExecResult.Quit -> "Quit(restart=${result.restart},force=${result.force})"
    is ExecResult.Begin -> "Begin(${result.target})"
    is ExecResult.Return -> "Return(${result.value})"
}

/**
 * 异常面文本。
 *
 * [CodeEE] 补位置的口径**逐条照抄** `ProcessRuntime.stampPosition`（`ProcessRuntime.kt:2499-2502`：
 * `funcName != null || line >= 0 || pc >= 0` 之一成立即原样返回，否则用当前语句的
 * 函数键/行号/`pc` 重建）。那里是 private，装置侧只能复刻它的三行判据 —— 这是本文件
 * 与产线口径之间唯一的"抄写"，改动 stampPosition 时必须同步这里。
 */
internal fun renderError(t: Throwable, stmt: Statement, index: Int): String {
    if (t is CodeEE) {
        val stamped = if (t.funcName != null || t.line >= 0 || t.pc >= 0) {
            t
        } else {
            CodeEE(t.message ?: "", DUAL_PATH_FUNCTION_KEY, stmt.line, index)
        }
        return "CodeEE|${stamped.message}|${stamped.funcName}|${stamped.line}|${stamped.pc}"
    }
    return "${t::class.java.name}|${t.message}"
}

// ---------------------------------------------------------------------------
// 变量终态抽样（公开读面：readInt / readStr）
// ---------------------------------------------------------------------------

/** 整数抽样名单（全部是系统变量；不存在或下标形态不符时记 `na:<文案>`，两侧同判）。 */
internal val DUAL_SAMPLE_INT_VARS: List<String> = listOf(
    "DAY", "MONEY", "RESULT", "FLAG", "NO", "TARGET", "ASSI", "MASTER", "PLACE", "GLOBAL", "ITEM",
)

/** 字符串抽样名单。 */
internal val DUAL_SAMPLE_STR_VARS: List<String> = listOf(
    "RESULTS", "SAVEDATA_TEXT", "NAME", "CALLNAME", "DRAWLINESTR",
)

/**
 * 按「变量名 + 下标 + 值」抽样终态（走 `VariableStore` 的公开读面，不碰内部布局）。
 *
 * 每个变量读三格：**下标 0**（小数组/标量用例恒覆盖）+ 由 [probeSeed] 推出的两格
 * `r`、`r+1`（`r = (probeSeed * 13 + 变量序 * 7) mod 长度`）。真语料装置逐条推进
 * [probeSeed]，抽样点因此在数组上滑动 —— 200k 条语句的并集能盖到大量格子，成本仍只有
 * 每次三读。单测传 0，行为固定可复现。
 *
 * 读不到的格记 `na:<CodeEE 文案>`：这本身也是可比状态（"该变量此刻不可读"）。
 * 刻意不读 `RAND`（读取即消耗，会破坏两路的 RNG 同步），也不读 `LINECOUNT` 之外的
 * 宿主派生面（`lineCount` 只是 `VariableStore` 的字段，不触发宿主）。
 *
 * TODO(verify)：`VARSET` / 批量赋值走的 `setAll` / `setValues` **不经过**
 * `VarWriteProbe`（挂点只在 `writeInt` / `writeStr` 入口）⇒ 这两族只有本抽样面兜底，
 * 不是逐格全量。要升级成逐格比较，得有「按声明尺寸遍历全部变量」的低成本读面
 * （`StateHash` 那套全量遍历在本装置的量级上是 O(语句 × 变量总数)，不可用）。
 */
internal fun sampleState(store: VariableStore, probeSeed: Int = 0): List<String> {
    val out = ArrayList<String>(48)
    out.add("charas=${store.characters.count}")
    out.add("lineCount=${store.lineCount}")
    out.add("isTimeout=${store.isTimeout}")
    out.add("windowTitle=${store.windowTitle}")
    out.add("drawLineStr=${store.drawLineStr}")
    out.add("lastLoadNo=${store.lastLoadNo}")
    for ((vi, name) in DUAL_SAMPLE_INT_VARS.withIndex()) {
        for (idx in probeIndices(store, name, vi, probeSeed)) {
            out.add("$name:$idx=" + readOrNa { store.readInt(name, longArrayOf(idx)) })
        }
    }
    for ((vi, name) in DUAL_SAMPLE_STR_VARS.withIndex()) {
        for (idx in probeIndices(store, name, vi, probeSeed)) {
            out.add("$name:$idx=" + readOrNa { store.readStr(name, longArrayOf(idx)) })
        }
    }
    return out
}

/** 抽样下标三点：0 + 两个随 [seed] 滑动的格（长度读不到时退化为 0，仍可比）。 */
private fun probeIndices(store: VariableStore, name: String, varOrdinal: Int, seed: Int): LongArray {
    val len = runCatching { store.getLength(name) }.getOrDefault(0)
    if (len <= 1) return longArrayOf(0L)
    val base = ((seed.toLong() * 13L + varOrdinal.toLong() * 7L) % len.toLong()).toInt()
    return longArrayOf(0L, base.toLong(), ((base + 1) % len).toLong())
}

private inline fun readOrNa(block: () -> Any): String = try {
    block().toString()
} catch (e: CodeEE) {
    "na:${e.message}"
} catch (t: Throwable) {
    "fail:${t::class.java.simpleName}:${t.message}"
}

/** 数据节替身：按**语句实例**取组（当前 `DataSections` 的口径）。 */
internal class MapDataSections(
    private val groups: Map<Statement, List<List<Expr>>>,
) : DataSections {

    /**
     * 扁平路径：本装置每条用例只挂**一条**语句，`DualPathFlatSource` 的池下标恒 0
     * （见 [DualPathFlatSource] 的单条形态），所以「唯一那一项」就是该下标的组。
     * 这不是「忽略下标」，而是装置的单语句形态：多语句用例需要按真下标建表（当前没有）。
     */
    override fun groupsOf(stmtIndex: Int): List<List<Expr>>? =
        if (groups.size == 1) groups.values.first() else null

    /** 树路径（旧口径）：对拍 oracle 要能跑，故同时实现实例键读面。 */
    @Suppress("DEPRECATION")
    override fun groupsOf(stmt: Statement): List<List<Expr>>? = groups[stmt]
}
