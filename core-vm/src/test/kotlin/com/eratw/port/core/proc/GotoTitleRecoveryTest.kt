package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 红 B「崩后无法恢复」的**恢复链回归**（GotoTitleRecovery）—— 症状是
 * 「Normal + 栈空时下一次回车仍判 FAILED、排队输入不吃」（脚本出错后整个会话粘住），
 * 原版的唯一复活通道是菜单「タイトルへ戻る」/Ctrl+Z 的 `GotoTitle`
 * （`MainWindow.cs:1079-1104` 的菜单回调 → `EmueraConsole.cs:2569-2581` =
 * `process.BeginTitle()`（`Process.cs:480-485` = `vEvaluator.ResetData()` +
 * `state = originalState` + `state.Begin(BeginType.TITLE)`）+ `ReadAnyKey()`
 * （`EmueraConsole.cs:876-895`，把控制台从 `ConsoleState.Error` 无条件写回
 * `ConsoleState.WaitInput`，写点 `:893`）+ `RunEmueraProgram("")` 重跑 `DoScript`）。
 *
 * 本文件钉的是**链上每一态**的可观察终态（装置与 [GotoTitleTest] 同形：真解析器 +
 * 真编译器 + 真 [GameSession]，临时目录、不吃语料环境变量）：
 * 1. 终局 FAILED 的**粘滞**形状（红 B 修复 f8a8d019：`endNormal` 抛 CodeEE 之后
 *    `EmueraConsole.cs:1094` 的 `state != ConsoleState.Error` 门控挡住下一次回车，
 *    排队输入不被消费 —— 症状本体）；
 * 2. [com.eratw.port.core.proc.ProcessRuntime.gotoTitle]（GOTOTITLE-CHANNEL，main 6b16277f）
 *    按 `ResetData`（`VariableEvaluator.cs:1336-1346`）**逐条**复位变量池：哪些清、
 *    哪些留，全部钉 C# 行号；
 * 3. 状态回到标题 `BEGIN(TITLE)`（`ProcessState.cs:273-278` 的 `sysStateCode = Title_Begin`）；
 * 4. 排队输入**恢复消费**（`RunEmueraProgram("")` 重跑的标题链重新吃输入）；
 * 5. `DoScript` 可**重复**重跑（每轮恢复都是完整的复位 + 重跑，不是一次性开关）。
 *
 * 与 [GotoTitleTest] 的分工：那边逐条对拍 `GotoTitle` 九条语句的**显示/图形/样式**
 * 侧面（ClearDisplay / UnloadGraphicList / redraw / userStyle / quit 闩锁 / keytoggle），
 * 本文件只盯**变量池复位面 + 输入恢复链**，两文件互不改写。
 */
class GotoTitleRecoveryTest {

    // ------------------------------------------------------------------
    // 小语料（装配照 GotoTitleTest 先例：真解析 + 真编译 + 真 GameSession）
    // ------------------------------------------------------------------

    private fun parser(vararg ints: String): LineParser {
        // RESULT 在运行期 registry 是 1 维整数数组（SystemLayout.kt:25 的 int1D →
        // Storage.G_INT1，见 ErbProgramCompiler.kt:510-522 的 KDoc），符号表必须同口径
        // 声明成 1 维：裸引用才会按「一般 1D：省略→0」补成 RESULT:0
        // （expr/Parser.kt:507-511 的省略补全规则，spec: VariableParser.cs:65-190）。
        var table = SymbolTable().declareVariable("RESULT", OperandType.INT, 1)
        for (name in ints) table = table.declareVariable(name, OperandType.INT, 1)
        return LineParser(table)
    }

    private fun parse(names: List<String>, vararg lines: String): List<Statement> {
        val p = parser(*names.toTypedArray())
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
    }

    /** `#DIM` 行里剔掉关键字段后的变量名（DimParser 的关键字口径，UserVariables.kt:391）。 */
    private fun dimName(raw: String): String {
        val tokens = raw.removePrefix("#DIM").removePrefix("#DIMS").trim().split(Regex("\\s+"))
        val keywords = setOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")
        return tokens.first { it.uppercase() !in keywords }
    }

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
        assertTrue(split != null, "测试数据必须是合法 #DIM 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    private class Fixture(
        val program: Program,
        val metadata: BootMetadata,
    )

    private fun fixture(
        body: List<String>,
        globalDims: List<String> = listOf("#DIM G_ACC"),
        privateDims: List<String> = listOf("#DIM LOC_ACC"),
    ): Fixture {
        val compiler = ErbProgramCompiler()
        val names = LinkedHashSet<String>()
        for (raw in globalDims) names.add(dimName(raw))
        for (raw in privateDims) names.add(dimName(raw))
        val stmts = parse(names.toList(), "@SYSTEM_TITLE", *body.toTypedArray())
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            globalDims = globalDims.mapIndexed { i, raw -> BootMetadata.RawDim(raw, "TEST.ERB", i + 1) },
            privateDims = privateDims.map { BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim(it)) },
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB"),
        )
        return Fixture(program, metadata)
    }

    /** 每跑一轮把全局 `G_ACC` 与私有 `LOC_ACC` 各加一并打印 —— 「第二轮的数字」同时钉住
     *  **跑过第二遍**（标题重跑）与**数字回到 1**（ResetData 生效）。 */
    private val countingBody = listOf(
        "#DIM LOC_ACC",
        "G_ACC = G_ACC + 1",
        "LOC_ACC = LOC_ACC + 1",
        "PRINTFORML PASS G={G_ACC} L={LOC_ACC}",
        "PRINTL TITLE-END",
    )

    /** 每轮先 `INPUT`（阻塞吃输入队列）再回显 —— 排队输入恢复消费的观测点。 */
    private val inputBody = listOf(
        "INPUT",
        "PRINTFORML GOT={RESULT}",
        "PRINTL TITLE-END",
    )

    private fun session(f: Fixture, dir: Path): GameSession =
        GameSession(gameRoot = dir, program = f.program, metadata = f.metadata, consoleWidth = 199, randSeed = 20260226L)

    private fun read(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 1) 终局 FAILED 的粘滞形状（红 B 症状本体：排队输入不吃）
    // ------------------------------------------------------------------

    /**
     * 红 B 症状钉死（修复 f8a8d019 后的期望形状）：`Normal` + 脚本函数栈空 ⇒
     * `endNormal` 抛 CodeEE（`Process.cs:2039` 注册 / `:2959-2962` 方法体）⇒
     * 两条异常路径都先 `console.ThrowError`（`Process.cs:573` / `:597`）把控制台置
     * `ConsoleState.Error`（`EmueraConsole.cs:757-768`，写点 `:767`）⇒ 下一次回车被
     * `RunEmueraProgram` 的 `state != ConsoleState.Error` 门控挡住
     * （`EmueraConsole.cs:1092-1110`，`:1094`），只回显不喂程序（`:1280-1287`）。
     *
     * 移植侧形状 = FAILED **只报一次** + 终局步幂等 + 排队输入不被消费：
     * 宿主读到 [StepOutcome.FAILED] 即收口，粘滞位保证此后任何 [resumeSystem] 都在
     * `BeginFlow.terminalEndNormalStep` 早退（`BeginFlow.kt` 的 step 开头段），
     * 不消费挂起 BEGIN、不进任何输入等待 —— 对应原版错误后 `runSystemProc` 不再被调。
     */
    @Test
    fun `终局 FAILED 粘滞不复活且排队输入不被消费`(@TempDir dir: Path) {
        val s = session(fixture(inputBody, globalDims = emptyList(), privateDims = emptyList()), dir)
        try {
            // 先提交后 begin：队列无请求方时提交不丢（QueueInputSink 的口径，
            // 与 GameSessionTest 的同一装配先例一致）
            assertTrue(s.inputSink.submitInt(42))
            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")
            val failed = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, failed.outcome, "Normal+栈空 = endNormal 终局（Process.cs:2959-2962）")
            assertEquals("endNormal", failed.handler)
            assertEquals(0, s.inputSink.pendingCount, "第一轮的输入已被脚本里的 INPUT 消费")
            assertEquals(listOf("GOT=42", "TITLE-END"), s.console.loggedLines())

            // 红 B 症状：终局态之后排队/再提交的输入完全不被消费
            assertTrue(s.inputSink.submitInt(7))
            assertEquals(1, s.inputSink.pendingCount)
            val sticky = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, sticky.outcome, "终局态下再泵还是 FAILED，不是新的 handler")
            assertEquals(failed, sticky, "终局步幂等（BeginFlow.terminalEndNormalStep，红 B 修复 f8a8d019）")
            assertEquals(
                1, s.inputSink.pendingCount,
                "排队输入不被消费：原版 ConsoleState.Error 门控（EmueraConsole.cs:1094）挡住下一次回车",
            )
            assertEquals(
                listOf("GOT=42", "TITLE-END"), s.console.loggedLines(),
                "终局态不重跑 DoScript（EmueraConsole.cs:1094），无新输出",
            )
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 2) gotoTitle 的变量池复位面（ResetData 逐条对 C#：哪些清哪些留）
    // ------------------------------------------------------------------

    /**
     * `process.BeginTitle()` 的 `vEvaluator.ResetData()`（`Process.cs:480-485` →
     * `VariableEvaluator.cs:1336-1346`）在移植侧 = [com.eratw.port.core.proc.ProcessRuntime.gotoTitle]
     * 里的 `store.resetData()`，四条语句逐条对拍：
     * 1. `RemoveEMSaveData()`（`VariableData.cs:4006-4026`）：SAVEDATA 字符串映射 / XML /
     *    DataTable 清空（本装置无语料 CSV，无对应观测点，不在此列）；
     * 2. `SetDefaultLocalValue()`（`VariableData.cs:3365-3375`）：`localvarTokenDic`
     *    （函数私有实例，如 `LOC_ACC`）+ `userDefinedStaticVarList`（函数内 STATIC 与
     *    **非 GLOBAL** 的全局静态 #DIM，如 `G_ACC`）逐项 `SetDefault()`；
     * 3. `SetDefaultValue(constant)`（`VariableData.cs:3385-3467`）：`dataInteger` 标量
     *    归零（`:3387-3390`）、`dataIntegerArray` 归零**但跳过** case 60（ITEMPRICE 回灌
     *    常量表，`:3395-3397`）与 case 63（GLOBAL 保留，`:3398-3399`）、
     *    `dataStringArray` 置空**但跳过** case 1（`__DUMMY_STR__` 回灌 CSV 名表，`:3414-3416`）
     *    与 case 5（GLOBALS 保留，`:3417-3418`）、2D/3D 数组无条件归零（`:3425-3467`）；
     * 4. `CharacterList.Clear()`（`VariableEvaluator.cs:1341-1345`，逐项 `Dispose()` 后清表）。
     *
     * 「留」的三轴同样有出处：`userDefinedGlobalVarList`（`#DIM GLOBAL` 用户变量，
     * 进表见 `CreateUserDefVariable` 的 `VariableData.cs:3265-3268`）只由
     * `ResetGlobalData()`（`VariableEvaluator.cs:1348-1353`）清，而 `ResetData`
     * **不调**它 ⇒ GLOBAL 用户变量跨回标题存活。
     */
    @Test
    fun `gotoTitle 按 ResetData 语义复位变量池并保留 GLOBAL 轴`(@TempDir dir: Path) {
        val s = session(fixture(countingBody, globalDims = listOf("#DIM G_ACC", "#DIM GLOBAL G_KEEP")), dir)
        try {
            assertTrue(s.begin(BeginType.TITLE))
            val failed = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, failed.outcome)
            // 第一轮留下的现场：用户 #DIM 已 +1、打印行里 L=1
            assertEquals(1L, read(s.store, "G_ACC"))
            assertEquals(listOf("PASS G=1 L=1", "TITLE-END"), s.console.loggedLines())
            assertEquals(4, s.store.characters.count, "无 CSV 时 GameSession 种 4 个裸角色（与 GotoTitleTest 同口径）")
            // 写坏四个「该清」的轴 + 三个「该留」的轴
            s.store.writeInt("MONEY", longArrayOf(0), 12_345L)      // dataInteger 标量
            s.store.writeInt("FLAG", longArrayOf(0), 99L)          // dataIntegerArray 一维
            s.store.writeInt("G_KEEP", longArrayOf(0), 55L)        // userDefinedGlobalVarList
            s.store.writeInt("GLOBAL", longArrayOf(0), 77L)        // dataIntegerArray[63]
            s.store.writeStr("GLOBALS", longArrayOf(0), "keepme")  // dataStringArray[5]

            val step = s.runtime.gotoTitle()

            // —— 标题态：Begin(TITLE)（Process.cs:480-485 的状态机半边 / ProcessState.cs:273-278）——
            assertEquals("beginTitle", step.handler, "重跑的第一个标题 handler")
            assertEquals(SystemStateCode.Title_Begin, step.from, "select(TITLE) 的入口态 = Begin(TITLE) 的 sysStateCode")
            assertEquals(StepOutcome.CALLED, step.outcome)
            assertEquals("SYSTEM_TITLE", step.calledFunction)
            assertEquals(SystemStateCode.Normal, s.runtime.systemState, "stepTitle 派发前先写 Normal（Process.cs:2138）")

            // —— 清：SetDefaultLocalValue（VariableData.cs:3365-3375）——
            assertEquals(1L, read(s.store, "G_ACC"), "非 GLOBAL 静态 #DIM 复位到 0 后被第二轮 +1；读到 2 = 没复位")
            assertEquals(
                listOf("PASS G=1 L=1", "TITLE-END"), s.console.loggedLines(),
                "函数私有 LOC_ACC 同样回到 1（localvarTokenDic，VariableData.cs:3367-3370）",
            )
            // —— 清：SetDefaultValue（VariableData.cs:3385-3390 / :3391-3405）——
            assertEquals(0L, read(s.store, "MONEY"), "dataInteger 标量归零（VariableData.cs:3387-3390）")
            assertEquals(0L, read(s.store, "FLAG"), "dataIntegerArray 归零（VariableData.cs:3391-3405，跳过 60/63）")
            // —— 清：CharacterList.Clear（VariableEvaluator.cs:1341-1345）——
            assertEquals(0, s.store.characters.count, "角色表整表清空（VariableEvaluator.cs:1341-1345）")

            // —— 留：SetDefaultValue 的两个跳过位（VariableData.cs:3398-3399 / :3417-3418）——
            assertEquals(77L, read(s.store, "GLOBAL"), "GLOBAL 系统数组跳过复位（case 63，VariableData.cs:3398-3399）")
            assertEquals("keepme", s.store.readStr("GLOBALS", longArrayOf(0)), "GLOBALS 系统串数组跳过复位（case 5，VariableData.cs:3417-3418）")
            // —— 留：userDefinedGlobalVarList 不在 ResetData 面上（VariableEvaluator.cs:1348-1353 才清）——
            assertEquals(55L, read(s.store, "G_KEEP"), "GLOBAL 用户 #DIM 跨回标题存活（VariableData.cs:3265-3268 进表，ResetData 不碰）")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 3) 输入恢复消费 + 重跑 DoScript（排队输入从「不吃」到「被吃掉」）
    // ------------------------------------------------------------------

    /**
     * 恢复链的主通道：终局粘滞期间排队的输入（[com.eratw.port.core.proc.QueueInputSink.pendingCount]
     * 不动）在 [com.eratw.port.core.proc.ProcessRuntime.gotoTitle] 之后被重跑的标题链吃掉 ——
     * 对应原版 `ReadAnyKey()`（`EmueraConsole.cs:876-895`，写点 `:893`）把控制台从 Error
     * 无条件写回 WaitInput、`RunEmueraProgram("")`（`:2579`）重进 `runSystemProc`
     * （`Process.cs:445-452` + `:2042-2045`）的那一跳。移植侧该门控解除落在
     * `BeginFlow.resetToBootState` 清掉的终局粘滞位上（证据链见
     * `BeginFlow.terminalEndNormalStep` 的 KDoc）。
     *
     * 「重跑 DoScript」的读数 = 第二轮脚本**完整**跑了一遍（INPUT 重新吃到排队值、
     * 打印出新行），且其后的终局是**新鲜**的 endNormal（粘滞位已被清，本轮重新置位），
     * 不是旧粘滞位的余震。
     */
    @Test
    fun `gotoTitle 后标题链重跑并恢复消费排队输入`(@TempDir dir: Path) {
        val s = session(fixture(inputBody, globalDims = emptyList(), privateDims = emptyList()), dir)
        try {
            assertTrue(s.inputSink.submitInt(42))
            assertTrue(s.begin(BeginType.TITLE))
            assertEquals(StepOutcome.FAILED, s.resumeSystem().outcome, "第一轮终局")

            // 终局态排队不吃（用例 1 已证），这里直接验恢复
            assertTrue(s.inputSink.submitInt(7))
            assertEquals(1, s.inputSink.pendingCount)
            val step = s.runtime.gotoTitle()

            // 标题态（BEGIN(TITLE)：ProcessState.cs:273-278 + Process.cs:2138）
            assertEquals("beginTitle", step.handler)
            assertEquals(SystemStateCode.Title_Begin, step.from)
            assertEquals(StepOutcome.CALLED, step.outcome)
            assertEquals("SYSTEM_TITLE", step.calledFunction)
            // 输入恢复消费：重跑的标题链把排队的那条吃掉了（RunEmueraProgram("")，EmueraConsole.cs:2579）
            assertEquals(0, s.inputSink.pendingCount, "排队输入恢复消费（终局态下它原样待在队列里）")
            // 交付值从打印观测（GOT=7 的 {RESULT} 在函数内求值）；SYSTEM_TITLE 是普通函数
            // （非 #FUNCTION 方法），返回时原版掉尾清零把 RESULT 打回 0
            // （Process.cs:1059-1064 `if (!state.IsFunctionMethod) vEvaluator.RESULT = 0L`；
            // 移植侧 = ErbProgramCompiler.resultRef 的掉尾清零），不得停在交付值。
            assertEquals(0L, read(s.store, "RESULT"), "普通函数返回掉尾清零（Process.cs:1059-1064）")
            assertEquals(listOf("GOT=7", "TITLE-END"), s.console.loggedLines(), "第二轮完整重跑（ClearDisplay 后只剩本轮输出）")

            // 重跑 DoScript 之后的终局是新鲜的（粘滞位被 resetToBootState 清过，本轮重新置位）
            val second = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, second.outcome)
            assertEquals("endNormal", second.handler)
            assertEquals(listOf("GOT=7", "TITLE-END"), s.console.loggedLines(), "终局步本身不产生输出")
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 4) 通道可重复（每轮恢复都是完整复位 + 重跑，不是一次性开关）
    // ------------------------------------------------------------------

    /**
     * `GotoTitle` 不是一次性开关：每一轮都是「提交 → 跑完 → 终局 → 回标题」的完整循环，
     * 变量池每轮都被 `ResetData`（`VariableEvaluator.cs:1336-1346`）打回出厂、
     * 终局粘滞位每轮都被 `BeginFlow.resetToBootState` 清掉。三轮连跑钉住通道的可重复性
     * （真机场景：玩家在错误屏回标题 → 再崩 → 再回标题，不能第二次就失效）。
     */
    @Test
    fun `gotoTitle 恢复通道可重复触发`(@TempDir dir: Path) {
        val s = session(fixture(inputBody, globalDims = emptyList(), privateDims = emptyList()), dir)
        try {
            var first = true
            for (v in listOf(42L, 7L, 9L)) {
                assertTrue(s.inputSink.submitInt(v), "第 $v 轮：先提交后跑")
                if (first) {
                    assertTrue(s.begin(BeginType.TITLE), "第 42 轮：首次进标题")
                    first = false
                } else {
                    val step = s.runtime.gotoTitle()
                    assertEquals("beginTitle", step.handler, "第 $v 轮：回标题后重跑 beginTitle")
                    assertEquals(SystemStateCode.Title_Begin, step.from)
                    assertEquals(StepOutcome.CALLED, step.outcome)
                    assertEquals("SYSTEM_TITLE", step.calledFunction)
                }
                assertEquals(0, s.inputSink.pendingCount, "第 $v 轮：输入被消费")
                assertEquals(listOf("GOT=$v", "TITLE-END"), s.console.loggedLines(), "第 $v 轮：完整重跑")
                val terminal = s.resumeSystem()
                assertEquals(StepOutcome.FAILED, terminal.outcome, "第 $v 轮：终局 FAILED")
                assertEquals("endNormal", terminal.handler)
                // 交付值只在打印（GOT=$v）里；普通函数返回掉尾清零
                // （Process.cs:1059-1064 `if (!state.IsFunctionMethod) vEvaluator.RESULT = 0L`）。
                assertEquals(0L, read(s.store, "RESULT"), "第 $v 轮：返回时掉尾清零（Process.cs:1059-1064）")
            }
        } finally {
            s.close()
        }
    }
}
