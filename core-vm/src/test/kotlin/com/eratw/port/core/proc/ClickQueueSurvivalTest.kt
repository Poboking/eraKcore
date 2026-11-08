package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.InputMirrorPort
import com.eratw.port.core.fn.InputState
import com.eratw.port.core.fn.KeyEventInputState
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.MouseState
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 执行期点击排队（CHARA_MOVEMENT ≈5.4s 丢点击）的取证与验收装置。
 *
 * 症状：真机玩家在 VM 长链（无输入语句的纯计算段）执行期间点地图按钮，「点好几次才切换地图」。
 * 执行期点击的保留通道 = 文本框镜像 + VK_RETURN 沿变（[KeyEventInputState] 的短按存记，
 * GameHost.kt:543-546 `fireVirtualKeyEdge` + MainActivity.kt:2492-2494 `mirrorTextBox`）；
 * 通道的消费点 = QoL 地图循环 `AWAIT_UI_INPUT` 的回车支（4.980 语料 qol_MAP.ERB:1131-1139：
 * `GETKEYTRIGGERED(13)` + `GETTEXTBOX()` 轮询；AWAIT 是纯 `timer.delay`，StatementExecutor
 * FunctionCode.AWAIT → `ctx.timer.delay`，**不进 sink**，故整个轮询期 `isAwaiting` 恒假，
 * 每一笔点击都走 GameHost.deliver 的执行期闸被丢文本）。
 *
 * 本文件用真解析 + 真编译 + 真 [GameSession]（装置与 GotoTitleRecoveryTest 同形）复现
 * 「长链 → 地图循环顶镜像覆写 → AWAIT 轮询回车支」全链路，锁定意图死在哪一环。
 */
class ClickQueueSurvivalTest {

    // ------------------------------------------------------------------
    // 装置（照 GotoTitleRecoveryTest 先例：真解析 + 真编译 + 真 GameSession）
    // ------------------------------------------------------------------

    private fun parser(intVars: List<String>, strVars: List<String>): LineParser {
        // RESULT 1 维整数（ErbProgramCompiler.kt:510-522 的运行期口径）；裸引用按
        // 「一般 1D：省略→0」补下标（expr/Parser.kt:507-511）。
        var table = SymbolTable().declareVariable("RESULT", OperandType.INT, 1)
        for (name in intVars) table = table.declareVariable(name, OperandType.INT, 1)
        for (name in strVars) table = table.declareVariable(name, OperandType.STR, 1)
        return LineParser(table)
    }

    private fun parse(
        intVars: List<String>,
        strVars: List<String>,
        vararg lines: String,
    ): List<Statement> {
        val p = parser(intVars, strVars)
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.trimStart().startsWith("@") -> LineKind.FUNCTION_LABEL
                text.trimStart().startsWith("$") -> LineKind.GOTO_LABEL
                text.trimStart().startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text.trimStart()))
        }.filterNotNull()
    }

    /** `#DIM`/`#DIMS` 行里剔掉关键字段后的变量名（DimParser 关键字口径，UserVariables.kt:391）。 */
    private fun dimName(raw: String): String {
        val tokens = raw.removePrefix("#DIM").trim().split(Regex("\\s+"))
        val keywords = setOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")
        return tokens.first { it.uppercase() !in keywords }
    }

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
        assertTrue(split != null, "测试数据必须是合法 #DIM/#DIMS 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    private class Fixture(val program: Program, val metadata: BootMetadata)

    private fun fixture(
        body: List<String>,
        globalDims: List<String> = emptyList(),
        privateDims: List<String> = emptyList(),
        intVars: List<String> = emptyList(),
        strVars: List<String> = emptyList(),
    ): Fixture {
        val compiler = ErbProgramCompiler()
        val names = LinkedHashSet<String>()
        for (raw in globalDims) names.add(dimName(raw))
        for (raw in privateDims) names.add(dimName(raw))
        val stmts = parse(
            names.toList() + intVars, strVars,
            "@SYSTEM_TITLE", *body.toTypedArray(),
        )
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

    /**
     * 会话装配：`maxStepsPerRun` 抬到生产口径（GameSession.PRODUCTION_MAX_STEPS_PER_RUN 的
     * 防死循环闸语义；缺省 20M 会被 6M 迭代的长链截断），`inputState` 换成测试持有的
     * [KeyEventInputState]（与 GameHost.kt:511 `val keyInput = KeyEventInputState(...)` 的生产装配同型）。
     */
    private fun session(f: Fixture, dir: Path, keyInput: KeyEventInputState): GameSession =
        GameSession(
            gameRoot = dir,
            program = f.program,
            metadata = f.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            maxStepsPerRun = 500_000_000L,
            inputState = keyInput as InputState,
            // 关帧冻结：PRINTL 的握手行要立刻进 lines() 快照（跨线程观测用）。
            frameGateEnabled = false,
        )

    // ------------------------------------------------------------------
    // 长链 + 地图轮询脚本（4.980 语料 qol_MAP.ERB 地图循环的同构）
    // ------------------------------------------------------------------

    /**
     * - `FOR I_ACC` 长链 = CHARA_MOVEMENT 的无输入语句纯计算段（MOVEMENTS/MOVEMENT.ERB:7 起，
     *   全函数无 AWAIT/GETKEY/SETTEXTBOX）；链中点用 `PRINTL CHAINMID` 打出可跨线程观测的
     *   握手行（控制台行快照带锁，见 [LineBufferConsoleSink.loggedLines]）——不能改读
     *   `store`：那是 VM 线程独占的普通数组，外部线程读不到及时值，点击会落到轮询之后。
     * - `SETTEXTBOX MOUSEB()` = 地图循环顶的镜像覆写（qol_MAP.ERB:43；headless 下 MOUSEB()="",
     *   MouseMethods.kt:102-105 → MouseState.refreshPointingForButton 无指向 → buttonValue=""）。
     * - `AWAIT 16` + `GETKEYTRIGGERED(1)`/`MOUSEB()` + `GETKEYTRIGGERED(13)`/`GETTEXTBOX()` =
     *   AWAIT_UI_INPUT 的**原始分支顺序**（左键支在前、回车支在后；qol_MAP.ERB:1103-1150）。
     *   headless 下 `MOUSEB()` 为空 ⇒ 左键支读不到值、不 RETURN，兜底必须落在回车支。
     */
    private val chainPollBody = listOf(
        "FOR I_ACC, 1, 6000000",
        "    IF I_ACC == 3000000",
        "        PRINTL CHAINMID",
        "    ENDIF",
        "NEXT",
        "SETTEXTBOX MOUSEB()",
        "FOR J_ACC, 1, 100",
        "    AWAIT 16",
        "    IF GETKEYTRIGGERED(1)",
        "        TEMP_STR '= MOUSEB()",
        "        IF TEMP_STR != \"\"",
        "            BREAK",
        "        ENDIF",
        "    ENDIF",
        "    IF GETKEYTRIGGERED(13)",
        "        TEMP_STR = %GETTEXTBOX()%",
        "        IF TEMP_STR != \"\"",
        "            BREAK",
        "        ENDIF",
        "    ENDIF",
        "NEXT",
        "PRINTFORML MAPMOVE=%TEMP_STR%",
    )

    /**
     * 执行期点击通道：与 `handleConsoleTap` → `submitButtonValue` 的执行期形态逐语句同构——
     * 1. `fireVirtualKeyEdge(VK_LBUTTON)`（MainActivity.kt:2286，点在任何位置都算左键按下）；
     * 2. `mirrorTextBox`（MainActivity.kt:2492-2494）= 文本框镜像写按钮值；
     * 3. `fireVirtualKeyEdge(VK_RETURN)`（GameHost.kt:543-546，提交动作补的沿变）；
     * 4. `deliver` 的执行期闸（GameHost.kt:936-947）= `isAwaiting` 为假 ⇒ 文本丢弃（计数），
     *    同时把落点记进单槽点击意图（`noteClickIntent`）。
     */
    private fun fireExecutionPeriodClick(s: GameSession, keyInput: KeyEventInputState, value: String) {
        keyInput.onKeyDown(AndroidKeyCodes.VK_LBUTTON)
        keyInput.onKeyUp(AndroidKeyCodes.VK_LBUTTON)
        s.builtinHost.textBox.setText(value)
        keyInput.onKeyDown(AndroidKeyCodes.VK_RETURN)
        keyInput.onKeyUp(AndroidKeyCodes.VK_RETURN)
        assertFalse(s.inputSink.isAwaiting, "长链执行期没有 sink 等待方，点击文本必走执行期闸")
        keyInput.noteClickIntent(value)
    }

    // ------------------------------------------------------------------
    // T1 取证：长链执行期点击，在镜像覆写之后必须仍被轮询回车支消费
    // ------------------------------------------------------------------
    /**
     * 点击落点由 `CHAINMID` 握手行钉死在链中点（I_ACC=3,000,000），保证 SETTEXTBOX 覆写发生在
     * 点击之后 —— 复现真机「链中点击 → 链末循环顶覆写镜像 → 首轮轮询读镜像」的时序。
     */
    @Test
    fun `长链执行期点击在镜像覆写之后仍被轮询回车支消费`(@TempDir dir: Path) {
        val keyInput = KeyEventInputState()
        val s = session(
            fixture(
                chainPollBody,
                privateDims = listOf("#DIM I_ACC", "#DIM J_ACC", "#DIMS TEMP_STR"),
                intVars = listOf("I_ACC", "J_ACC"),
                strVars = listOf("TEMP_STR"),
            ),
            dir,
            keyInput,
        )
        // 生产装配：GameHost.init 把意图槽的镜像端口绑到会话的 ConsoleTextBox。
        keyInput.mirror = object : InputMirrorPort {
            override fun read(): String = s.builtinHost.textBox.text
            override fun write(text: String) = s.builtinHost.textBox.setText(text)
        }
        try {
            val clickerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val clicker = thread(start = true, isDaemon = true) {
                try {
                    // 链中点握手：控制台行快照带锁（LineBufferConsoleSink.loggedLines），
                    // 且会话关了帧冻结，PRINTL 立即可见。
                    while (!s.console.loggedLines().contains("CHAINMID")) Thread.sleep(2)
                    fireExecutionPeriodClick(s, keyInput, "500")
                } catch (t: Throwable) {
                    clickerFailure.set(t)
                }
            }
            // begin/一步一停（ProcessRuntime.beginStep）：SYSTEM_TITLE 在同一 step 内跑到
            // 终局，所以必须把执行放线程里、点击线程先就位（放在 begin 之后会整条脚本跑完
            // 才轮到点击，等于什么都没测到）。
            val began = java.util.concurrent.atomic.AtomicBoolean(false)
            val runner = thread(start = true, isDaemon = true) {
                try {
                    began.set(s.begin(BeginType.TITLE))
                    s.resumeSystem()
                } catch (t: Throwable) {
                    clickerFailure.compareAndSet(null, t)
                }
            }
            clicker.join(60_000)
            assertFalse(clicker.isAlive, "点击线程应在长链中点落到点击通道")
            runner.join(120_000)
            assertFalse(runner.isAlive, "VM 未在时限内跑到终局")
            clickerFailure.get()?.let { throw AssertionError("执行线程异常", it) }
            assertTrue(began.get(), "SYSTEM_TITLE 已登记，begin 应执行它")
            assertEquals(
                listOf("CHAINMID", "MAPMOVE=500"),
                s.console.loggedLines(),
                "执行期点击应在 SETTEXTBOX MOUSEB() 覆写镜像之后仍被轮询回车支消费" +
                    "（修复前：GETTEXTBOX 读到被覆写的空串，MAPMOVE= 为红）",
            )
        } finally {
            s.close()
        }
    }

    /**
     * 10-25 跨屏串值回归钉：执行期点击意图**不得**进取值等待的 RESULT/RESULTS 槽。
     *
     * 意图只在轮询屏的按键读点补写镜像（见 [KeyEventInputState] 类文档「执行期点击意图」）；
     * sink 的取值等待连它的影子都看不到，而且进入等待时宿主会经
     * [QueueInputSink.onWaitEntered] 把待交付意图作废（下一屏轮询不得再补写旧值）。
     */
    @Test
    fun `执行期点击意图不进取值等待的值槽`() {
        val keyInput = KeyEventInputState()
        val sink = QueueInputSink()
        var mirror = ""
        keyInput.mirror = object : InputMirrorPort {
            override fun read(): String = mirror
            override fun write(text: String) {
                mirror = text
            }
        }
        sink.onWaitEntered = { keyInput.clearClickIntent() }
        keyInput.onKeyDown(AndroidKeyCodes.VK_RETURN)
        keyInput.onKeyUp(AndroidKeyCodes.VK_RETURN)
        keyInput.noteClickIntent("500")

        val outcome = sink.waitInput(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                hasDef = true,
                defInt = 7L,
                timeLimit = 30L,
            ),
        )
        assertEquals(7L, outcome.intValue, "取值等待只应拿到自己的默认值")
        assertEquals(0L, sink.lastSubmittedSeq, "点击意图不进队列：sink 一条提交都没收到")
        assertEquals(0, sink.pendingCount)
        assertEquals("", mirror, "取值等待不读也不补写文本框镜像")

        // 等待点已把意图作废：之后轮询屏的回车读点不应再把旧点击补写回镜像。
        keyInput.onKeyDown(AndroidKeyCodes.VK_RETURN)
        keyInput.onKeyUp(AndroidKeyCodes.VK_RETURN)
        keyInput.keyState(AndroidKeyCodes.VK_RETURN)
        assertEquals("", mirror, "进入 sink 等待即作废待交付点击，不得跨屏补写")
    }

    // ------------------------------------------------------------------
    // 左键支：headless 下 MOUSEB() 恒空的可达化注入（[chainPollBody] 注释自述的缺口）
    //
    // 上面两条只走回车兜底：headless 下 `MouseState` 无窗口、无客户区、无命中测试，
    // `MOUSEB()` 恒返回空串（MouseMethods.kt:102-105 → MouseState.refreshPointingForButton
    // 在 MouseState.kt:170 的 windowCreated 闸处早退），于是 qol_MAP.ERB:1105-1113 的
    // `IF GETKEYTRIGGERED(1)` + `MOUSEB()` 左键支在生产语料里存在、在本测试装置里却从未执行。
    // 下面两条用 MouseState 的既有注入面（hitTest :105、windowCreated :61、
    // setClientSize/setPointerPosition :123/:127）把这条支打通，生产代码零改动。
    // ------------------------------------------------------------------

    /**
     * 客户区尺寸：真机由 UI 在首帧 / resize 时经 `MouseState.setClientSize` 写入
     * （MouseState.kt:127）；这里只要非零，越界判据的半开区间 `[0,w)×[0,h)`（:173）才有意义。
     * 宽取会话的 consoleWidth（199），高只需 > 指针 Y。
     */
    private val clientW = 199
    private val clientH = 400

    /**
     * 装配 MOUSEB 的鼠标现场 —— headless 缺的正是这三样：窗口已创建、客户区非零、命中测试有指向。
     * 指针落在客户区内 `(10,20)`，越界判据（MouseState.kt:173）为真 ⇒ 每次 MOUSEB 都会调
     * `hitTest` 并把返回值写进 `pointing`（:175）。
     */
    private fun installPointing(s: GameSession, point: ConsoleButtonPointing): MouseState {
        val mouse = mouseStateOf(s.store.aux)
        mouse.windowCreated = true
        mouse.setClientSize(clientW, clientH)
        mouse.setPointerPosition(10, 20)
        mouse.hitTest = { _, _ -> point }
        return mouse
    }

    /**
     * 只 fire `VK_LBUTTON` 的 down/up 沿 —— 左键支的唯一输入：不写文本框、不补 `VK_RETURN`、
     * 不 `noteClickIntent`（后三者是回车兜底通道，见 [fireExecutionPeriodClick]）。
     */
    private fun fireLeftButtonEdge(keyInput: KeyEventInputState) {
        keyInput.onKeyDown(AndroidKeyCodes.VK_LBUTTON)
        keyInput.onKeyUp(AndroidKeyCodes.VK_LBUTTON)
    }

    /**
     * 左键支主用例：长链中点只 fire 左键沿，脚本必须从 `IF GETKEYTRIGGERED(1)` + `MOUSEB()`
     * 非空 → `BREAK` 出去（qol_MAP.ERB:1105-1113）。
     *
     * 与 T1 的差别只有输入面：T1 是「镜像 + 回车沿」走 `GETKEYTRIGGERED(13)`/`GETTEXTBOX()`
     * 兜底；本用例不碰文本框、不补回车，唯一能让 `MAPMOVE` 拿到 `MAPBTN` 的路径就是左键支
     * （`MOUSEB()` 读的是 `pointing`，不是镜像）。终局若没有 `MAPMOVE=MAPBTN`，说明左键支没被
     * 走到 —— 这正是撤掉 `hitTest` 注入后应出现的红。
     */
    @Test
    fun `长链执行期左键沿在轮询左键支被 MOUSEB 消费`(@TempDir dir: Path) {
        val keyInput = KeyEventInputState()
        val s = session(
            fixture(
                chainPollBody,
                privateDims = listOf("#DIM I_ACC", "#DIM J_ACC", "#DIMS TEMP_STR"),
                intVars = listOf("I_ACC", "J_ACC"),
                strVars = listOf("TEMP_STR"),
            ),
            dir,
            keyInput,
        )
        // 生产装配：GameHost.init 把意图槽的镜像端口绑到会话的 ConsoleTextBox（本用例不用它，
        // 但与 T1 同装置；左键支不经过镜像，绑着也不会串味）。
        keyInput.mirror = object : InputMirrorPort {
            override fun read(): String = s.builtinHost.textBox.text
            override fun write(text: String) = s.builtinHost.textBox.setText(text)
        }
        // 注入面全部是既有字段：窗口已创建 + 客户区非零 + 指针在区内 + 命中测试给出按钮串。
        installPointing(s, ConsoleButtonPointing(isButton = true, isInteger = false, inputs = "MAPBTN"))
        try {
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val clicker = thread(start = true, isDaemon = true) {
                try {
                    while (!s.console.loggedLines().contains("CHAINMID")) Thread.sleep(2)
                    // 点在任何位置都算左键按下（MainActivity.kt:2544 的 fireVirtualKeyEdge
                    // VK_LBUTTON 同一事件），但**没有**文本框镜像与回车沿。
                    fireLeftButtonEdge(keyInput)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            val began = java.util.concurrent.atomic.AtomicBoolean(false)
            val runner = thread(start = true, isDaemon = true) {
                try {
                    began.set(s.begin(BeginType.TITLE))
                    s.resumeSystem()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
            clicker.join(60_000)
            assertFalse(clicker.isAlive, "点击线程应在长链中点落下左键沿")
            runner.join(120_000)
            assertFalse(runner.isAlive, "VM 未在时限内跑到终局")
            failure.get()?.let { throw AssertionError("执行线程异常", it) }
            assertTrue(began.get(), "SYSTEM_TITLE 已登记，begin 应执行它")
            assertEquals(
                listOf("CHAINMID", "MAPMOVE=MAPBTN"),
                s.console.loggedLines(),
                "执行期左键沿应在轮询左键支被 MOUSEB() 消费（qol_MAP.ERB:1105-1113）；" +
                    "撤掉 hitTest 注入后 MOUSEB() 恒空，终局退化成 MAPMOVE=",
            )
        } finally {
            s.close()
        }
    }

    /**
     * 越界点击读过期指向的**会话级**组合（单测口径已由 MouseMethodsTest:202 覆盖，这里不复制
     * 单测，只把同一判据放进真 [GameSession] 的轮询链）：
     *
     * 1. 指针在区内 → `MOUSEB()` 命中 `MAPBTN`，`pointing` 记为 `MAPBTN`；
     * 2. 长链中点把指针移出客户区（`x = -1`，半开区间外，MouseState.kt:173）并 fire 左键沿，
     *    命中测试同时换成 `OTHER`；
     * 3. 轮询左键支读 `MOUSEB()`：`refreshPointingForButton` 因越界**不调 hitTest**
     *    （FunctionMethodCreator.cs:7879-7883）⇒ 返回**上一次**的 `MAPBTN`，不是 `OTHER`；
     * 4. 指针回到区内后同一次会话再读：`OTHER` —— 证明同一命中测试确实会被调用，
     *    第 3 步的 `MAPBTN` 只能来自「没刷新」。
     *
     * 长链 = 可跨线程观测的时间窗（与 T1 同装置），避免用墙钟 sleep 赌时序。
     */
    private val stalePointingBody = listOf(
        "TEMP_STR '= MOUSEB()",
        "PRINTFORML HIT1=%TEMP_STR%",
        "FOR I_ACC, 1, 6000000",
        "    IF I_ACC == 3000000",
        "        PRINTL CHAINMID",
        "    ENDIF",
        "NEXT",
        "FOR J_ACC, 1, 100",
        "    AWAIT 16",
        "    IF GETKEYTRIGGERED(1)",
        "        TEMP_STR '= MOUSEB()",
        "        IF TEMP_STR != \"\"",
        "            PRINTFORML HIT2=%TEMP_STR%",
        "            BREAK",
        "        ENDIF",
        "    ENDIF",
        "NEXT",
        "FOR I_ACC, 1, 6000000",
        "    IF I_ACC == 3000000",
        "        PRINTL CHAINMID3",
        "    ENDIF",
        "NEXT",
        "TEMP_STR '= MOUSEB()",
        "PRINTFORML HIT3=%TEMP_STR%",
    )

    @Test
    fun `越界点击在轮询左键支读到过期指向串`(@TempDir dir: Path) {
        val keyInput = KeyEventInputState()
        val s = session(
            fixture(
                stalePointingBody,
                privateDims = listOf("#DIM I_ACC", "#DIM J_ACC", "#DIMS TEMP_STR"),
                intVars = listOf("I_ACC", "J_ACC"),
                strVars = listOf("TEMP_STR"),
            ),
            dir,
            keyInput,
        )
        val mouse = installPointing(
            s,
            ConsoleButtonPointing(isButton = true, isInteger = false, inputs = "MAPBTN"),
        )
        try {
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val clicker = thread(start = true, isDaemon = true) {
                try {
                    // 第 1 步已落屏：此刻 pointing == "MAPBTN"。
                    while (!s.console.loggedLines().contains("HIT1=MAPBTN")) Thread.sleep(2)
                    // 长链中点：把指针挪出客户区、换掉命中测试的返回值，再 fire 左键沿。
                    // 移出后 refreshPointingForButton 不刷新（:7879 的 ClientRectangle 判据），
                    // 所以后面的读点若给出 OTHER 就说明越界也在刷新（错）。
                    while (!s.console.loggedLines().contains("CHAINMID")) Thread.sleep(2)
                    mouse.setPointerPosition(-1, 0)
                    mouse.hitTest = { _, _ ->
                        ConsoleButtonPointing(isButton = true, isInteger = false, inputs = "OTHER")
                    }
                    fireLeftButtonEdge(keyInput)
                    // 第 3 步的越界读已落屏：指针回到区内，让第 4 步的读点刷新。
                    while (!s.console.loggedLines().contains("HIT2=MAPBTN")) Thread.sleep(2)
                    mouse.setPointerPosition(10, 20)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            val began = java.util.concurrent.atomic.AtomicBoolean(false)
            val runner = thread(start = true, isDaemon = true) {
                try {
                    began.set(s.begin(BeginType.TITLE))
                    s.resumeSystem()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
            clicker.join(60_000)
            assertFalse(clicker.isAlive, "点击线程应在两次长链窗口内完成动手")
            runner.join(120_000)
            assertFalse(runner.isAlive, "VM 未在时限内跑到终局")
            failure.get()?.let { throw AssertionError("执行线程异常", it) }
            assertTrue(began.get(), "SYSTEM_TITLE 已登记，begin 应执行它")
            assertEquals(
                listOf("HIT1=MAPBTN", "CHAINMID", "HIT2=MAPBTN", "CHAINMID3", "HIT3=OTHER"),
                s.console.loggedLines(),
                "越界点击读到的必须是上一次的指向串 MAPBTN（:7879-7883/移植 MouseState.kt:173-176）；" +
                    "HIT3 的 OTHER 证明同一 hitTest 在区内确实会被调用",
            )
        } finally {
            s.close()
        }
    }
}
