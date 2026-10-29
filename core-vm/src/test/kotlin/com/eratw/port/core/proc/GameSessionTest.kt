package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.MOUSESKIP_WARNING_TEXT
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * [GameSession] 装配单测：**Program + 侧车 → BEGIN TITLE**。
 *
 * 这里只测装配与接线的**事实**（哪些声明真的进了变量池、数据节真的被查到、`BeginHost`
 * 真的从输入队列取值）；「源码直建 vs `.erbc` + 侧车装载」的等价性对拍在 erbcc 的
 * `BootMetaRoundTripTest`（那条路径要 `ErbcArtifacts`/`ProgramLoader`）。
 *
 * 小语料刻意用**真的解析器 + 真的编译器**（不是手搓字节码），因为要钉的正是
 * 「侧车里的元数据能不能喂活一个真 `Program`」：
 *
 * ```
 * @SYSTEM_TITLE
 * #DIM LOC_OK
 * #DIM CONST LOC_C = 3
 * LOC_OK = G_CONST + LOC_C
 * PRINTFORML INIT={LOC_OK}
 * PRINTL HELLO
 * PRINTDATA
 * DATA AAA
 * DATA BBB
 * ENDDATA
 * PRINTL TAILED
 * ```
 */
class GameSessionTest {

    // ------------------------------------------------------------------
    // 小语料
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            // #DIM 不带尺寸时 DimParser 归一成 1 维、长度 1（UserVariables.kt:304-313），
            // 裸引用由表达式解析器补下标 0（Parser.kt:421-428）；符号表必须同口径声明 1 维。
            .declareVariable("G_CONST", OperandType.INT, 1)
            .declareVariable("LOC_OK", OperandType.INT, 1)
            .declareVariable("LOC_C", OperandType.INT, 1)
            .declareVariable("RESULT", OperandType.INT, 0),
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
            p.parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }.filterNotNull()
    }

    private class Fixture(
        val program: Program,
        val metadata: BootMetadata,
        val dataIndex: Int,
    )

    /** 全局常量原文（`#DIM CONST`）——与侧车 `globalDims` 同形。 */
    private val globalConstRaw = "#DIM CONST G_CONST = 7"

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
        assertNotNull(split, "测试数据必须是合法 #DIM 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    private fun fixture(
        withGlobalConst: Boolean = true,
        withPrivateDims: Boolean = true,
        withDataSections: Boolean = true,
        printDataFirst: String? = null,
    ): Fixture {
        val compiler = ErbProgramCompiler()
        val body = if (printDataFirst == null) {
            arrayOf(
                "#DIM LOC_OK",
                "#DIM CONST LOC_C = 3",
                "LOC_OK = G_CONST + LOC_C",
                "PRINTFORML INIT={LOC_OK}",
                "PRINTL HELLO",
                "PRINTDATA",
                "DATA AAA",
                "DATA BBB",
                "ENDDATA",
                "PRINTL TAILED",
            )
        } else {
            // 不做数据节、也不引用全局常量的最小变体（用于对比读数）
            arrayOf(printDataFirst)
        }
        val stmts = parse("@SYSTEM_TITLE", *body)
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program = compiler.build("SYSTEM_TITLE")

        val dataIndex = program.statements().indexOfFirst { it is PrintDataStmt }
        val dataSections = if (withDataSections && dataIndex >= 0) {
            mapOf(
                dataIndex to listOf(
                    listOf<Expr>(StrLiteral("AAA")),
                    listOf<Expr>(StrLiteral("BBB")),
                ),
            )
        } else {
            emptyMap()
        }
        val privateDims = if (withPrivateDims) {
            listOf(
                BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM LOC_OK")),
                BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM CONST LOC_C = 3")),
            )
        } else {
            emptyList()
        }
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            dataSections = dataSections,
            globalDims = if (withGlobalConst) listOf(BootMetadata.RawDim(globalConstRaw, "TEST.ERB", 1)) else emptyList(),
            privateDims = privateDims,
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "TEST.ERB"),
        )
        return Fixture(program, metadata, dataIndex)
    }

    private fun session(f: Fixture, root: Path, seed: Long = 20260226L): GameSession = GameSession(
        gameRoot = root,
        program = f.program,
        metadata = f.metadata,
        consoleWidth = 199,
        randSeed = seed,
    )

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    @Test
    fun `装配后 BEGIN TITLE 输出含常量 私有变量 数据节与尾部行`(@TempDir dir: Path) {
        val f = fixture()
        val s = session(f, dir)
        try {
            assertNull(s.gameData, "临时目录没有 CSV：系统变量按缺省注册表运行")
            assertEquals(1, s.globalDimInstall.declared, "全局 #DIM CONST 应注册成功: ${s.globalDimInstall.failures}")
            assertTrue(s.globalDimInstall.failures.isEmpty(), "全局装载不应有失败")
            assertEquals(7L, s.globalDimInstall.constants["G_CONST"], "标量 CONST 折叠进常量表（尺寸求值用）")
            assertEquals(2, s.privateDimDeclared, "两条私有 #DIM 都应注册: ${s.privateDimFailures}")
            assertTrue(s.privateDimFailures.isEmpty())

            assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")
            val lines = s.console.loggedLines()
            assertEquals("INIT=10", lines[0], "全局 7 + 私有 3（两侧元数据都真的进了变量池）")
            assertEquals("HELLO", lines[1])
            // PRINTDATA 的 DATA 不带换行（原版 DAT1DL1 同形，基准 pc-golden-root.txt:238），
            // 所以被选中的 AAA/BBB 与下一条 PRINTL TAILED 拼在同一显示行。
            assertTrue(lines[2] == "AAATAILED" || lines[2] == "BBBTAILED", "PRINTDATA 必须命中数据节，实为 ${lines[2]}")
            assertEquals(3, lines.size, "不应有多余行: $lines")
        } finally {
            s.close()
        }
    }

    @Test
    fun `会话配置与函数层条目表吃到同一份 Replace`(@TempDir dir: Path) {
        val csv = Files.createDirectories(dir.resolve(GameSession.CSV_DIR_NAME))
        Files.writeString(
            csv.resolve("_Replace.csv"),
            "お金の単位 , ￥\n汚れの初期値 , 0/0/2/1/8/0/1/8\nBAR文字1 , =\n",
        )
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        val s = session(f, dir)
        try {
            // 会话配置：SaveConfig / BeginHost / SAVENOS 的取值来源（GameSession.configStore）
            assertEquals("￥", s.configStore.itemOf(ConfigKey.MoneyLabel).string(), "缺省会话表经 applyChainTo 重放，Replace 在位")
            assertEquals(
                listOf(0L, 0L, 2L, 1L, 8L, 0L, 1L, 8L),
                s.configStore.itemOf(ConfigKey.StainDefault).longList(),
            )
            // MONEYLABEL 伪变量与 MONEYSTR 同源（原版 Config.MoneyLabel，Config.cs:199/:721）
            assertEquals("￥", s.store.moneyLabel)
            assertEquals("￥", s.builtinHost.moneyLabel)
            // 宿主覆盖表：barChars / 码页 / LOADTEXT 白名单消费面
            assertEquals("￥", s.builtinHost.configOverrides["CURRENCY SYMBOL"])
            assertEquals("=", s.builtinHost.configOverrides["BAR CHARACTER 1"])
            // 函数层 GETCONFIG 条目表：与上面两条通道同源
            val fnStore = ConfigMethods.storeFromHost(s.builtinHost)
            assertEquals("￥", fnStore.itemOf(ConfigKey.MoneyLabel).string())
            assertEquals('=', fnStore.itemOf(ConfigKey.BarChar1).char())
            assertEquals(
                s.configStore.itemOf(ConfigKey.StainDefault).value,
                fnStore.itemOf(ConfigKey.StainDefault).value,
                "会话表与函数层条目表的 REPLACE 组逐项相同",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `侧车缺数据节时 PRINTDATA 无输出`(@TempDir dir: Path) {
        val f = fixture(withDataSections = false)
        val s = session(f, dir)
        try {
            assertTrue(s.begin(BeginType.TITLE))
            val lines = s.console.loggedLines()
            assertEquals(listOf("INIT=10", "HELLO", "TAILED"), lines, "未注入数据节 → PRINTDATA 按空表 no-op")
        } finally {
            s.close()
        }
    }

    @Test
    fun `侧车缺全局常量时运行到赋值处报未定义变量`(@TempDir dir: Path) {
        val f = fixture(withGlobalConst = false)
        val s = session(f, dir)
        try {
            val e = assertFailsWith<CodeEE> { s.begin(BeginType.TITLE) }
            assertTrue(e.message!!.contains("G_CONST"), "错误必须点名缺失的变量: ${e.message}")
        } finally {
            s.close()
        }
    }

    @Test
    fun `侧车缺私有声明时运行到赋值处报未定义变量`(@TempDir dir: Path) {
        val f = fixture(withPrivateDims = false)
        val s = session(f, dir)
        try {
            val e = assertFailsWith<CodeEE> { s.begin(BeginType.TITLE) }
            // 表达式按 `G_CONST + LOC_C` 先取右操作数，缺失的是 LOC_C；两者都属未注册的私有声明。
            assertTrue(
                e.message!!.contains("LOC_OK") || e.message!!.contains("LOC_C"),
                "错误必须点名缺失的私有变量: ${e.message}",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `标签表按函数键与标签名大写归一`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        // 手挂一个标签（小语料里没有 $ 行，这里只验注入点接线，不验编译器发射）
        val meta = f.metadata.let { m ->
            BootMetadata(
                labels = mapOf("SYSTEM_TITLE" to mapOf("LOOP" to 0)),
                functions = m.functions,
                fileByKey = m.fileByKey,
            )
        }
        val s = GameSession(dir, f.program, meta, consoleWidth = 199, randSeed = 1L)
        try {
            assertEquals(0, s.runtime.tryResolveLabel("system_title", "loop"), "两侧键都大写归一")
            assertNull(s.runtime.tryResolveLabel("SYSTEM_TITLE", "nope"))
        } finally {
            s.close()
        }
    }

    @Test
    fun `屏幕像素可注入而字号只认生效配置`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        // 缺省（headless / PC 基准）：GFX-06 改造前后零行为差 ——
        // FontSize=18（ConfigData.cs:66）、CLIENTWIDTH=WindowX 760（:56）、
        // CLIENTHEIGHT=WindowY-LineHeight 461（:57,67）。
        val def = GameSession(dir, f.program, f.metadata, consoleWidth = 199, randSeed = 1L)
        try {
            assertEquals(18L, def.builtinHost.fontSize)
            assertEquals(760L, def.builtinHost.clientWidth)
            assertEquals(461L, def.builtinHost.clientHeight)
        } finally {
            def.close()
        }
        // 真机形状（GameHost.assembleSession 的注入链）：两条屏幕像素随注入值走
        // （消费点 ConsoleQueryMethods.kt:131/136），**字号不吃注入值**。
        val s = GameSession(
            dir, f.program, f.metadata,
            consoleWidth = 110, randSeed = 1L,
            clientWidthPx = 2400, clientHeightPx = 1100,
        )
        try {
            assertEquals(2400L, s.builtinHost.clientWidth)
            assertEquals(1100L, s.builtinHost.clientHeight)
            // 字号这条是 2026-10-29 指令按钮错位的收口判据：语料那层排格用
            // `PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2`（=225），量宽用
            // `HTML_STRINGLEN(…,1)`（吃 builtinHost.fontSize）—— 两条必须是同一个 18，
            // 一旦宿主把真机 page-fit 字号（30~40）喂进来，每个按钮就只占应得宽度的一半，
            // 一行十几个整排平移。这里把它钉成「宿主喂不进来」。
            assertEquals(18L, s.builtinHost.fontSize)
            assertEquals(s.scriptFontPixelSize.toLong(), s.builtinHost.fontSize)
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 装配期硬错误
    // ------------------------------------------------------------------

    @Test
    fun `侧车无函数元数据时构造期报错`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        val meta = BootMetadata(functions = emptyList())
        val e = assertFailsWith<IllegalArgumentException> { GameSession(dir, f.program, meta, consoleWidth = 199) }
        assertTrue(e.message!!.contains("函数元数据"), "错误应说明缺函数元数据: ${e.message}")
    }

    @Test
    fun `侧车与产物不配对时构造期报错`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        val meta = BootMetadata(
            functions = listOf(
                FuncMeta(index = f.program.functions.size + 5, key = "SYSTEM_TITLE", defIndex = 0),
            ),
        )
        val e = assertFailsWith<IllegalArgumentException> { GameSession(dir, f.program, meta, consoleWidth = 199) }
        assertTrue(e.message!!.contains("不配对"), "错误应说明配对失败: ${e.message}")
    }

    // ------------------------------------------------------------------
    // BeginHost：四个 await* 接同一输入队列
    // ------------------------------------------------------------------

    @Test
    fun `SYSTEM_TITLE 缺失时 awaitOpeningInput 从输入队列取值`(@TempDir dir: Path) {
        val compiler = ErbProgramCompiler()
        val fb = compiler.compileFunction(FuncSig("OTHER"), parse("@OTHER", "PRINTL NOPE"), setOf("OTHER"))
        val program = compiler.build("OTHER")
        val meta = BootMetadata(
            functions = listOf(FuncMeta(index = fb.index, key = "OTHER", defIndex = 0, position = "TEST.ERB:1")),
        )
        val s = GameSession(dir, program, meta, consoleWidth = 199)
        try {
            // 先提交、后 begin：队列无请求方时提交不丢（QueueInputSink 的口径）
            assertTrue(s.inputSink.submitInt(0))
            assertFalse(s.begin(BeginType.TITLE), "SYSTEM_TITLE 不存在 → begin 返回 false（force=false）")
            assertEquals(SystemStateCode.Openning, s.runtime.systemState, "标题菜单状态")
            assertEquals(0L, s.runtime.systemResult, "输入值进了 systemResult")

            val step = s.resumeSystem()
            assertEquals(StepOutcome.CONTINUE, step.outcome, "systemResult=0 → 新游戏分支")
            assertEquals(SystemStateCode.First_Begin, s.runtime.systemState)
            assertTrue(s.console.loggedLines().isEmpty(), "默认标题不画（BeginHost.printDefaultTitle 缺省），内容交给 SYSTEM_TITLE 脚本")
        } finally {
            s.close()
        }
    }

    @Test
    fun `console 与 metadata 暴露的是同一份控制台与运行期`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        val s = session(f, dir)
        try {
            assertSame(s.runtime.program, s.program)
            assertTrue(s.begin(BeginType.TITLE))
            assertEquals(listOf("PLAIN"), s.console.loggedLines())
            // 控制台是 ConsoleFeed 的数据源：revision 随输出自增
            assertTrue(s.console.revision > 0, "输出后 revision 必须前进")
            assertEquals(s.console.clientWidth, 199)
        } finally {
            s.close()
        }
    }

    @Test
    fun `close 关闭输入队列`(@TempDir dir: Path) {
        val f = fixture(printDataFirst = "PRINTL PLAIN")
        val s = session(f, dir)
        assertFalse(s.inputSink.isClosed)
        s.close()
        assertTrue(s.inputSink.isClosed, "close 应传达给输入队列")
    }

    // ------------------------------------------------------------------
    // MOUSESKIP 弃用警告的生产装配（SAVE-15b）
    // ------------------------------------------------------------------

    @Test
    fun `MOUSESKIP 警告生产装配 缺省收进会话清单`(@TempDir dir: Path) {
        // 判据一：GameSession 装配链上 warnRecorder 可达且非 null；
        // 且与 fn 层 mouseState(c) 是同一份 aux 槽实例（MouseState.kt 的取用约定）。
        val f = fixture()
        val s = session(f, dir)
        try {
            val mouse = mouseStateOf(s.store.aux)
            assertNotNull(mouse.warnRecorder, "生产装配必须给出 warnRecorder")
            assertSame(mouse, mouseStateOf(s.store.aux), "fn 层与装配层必须命中同一份 MouseState")
            mouse.warnRecorder!!.invoke(MOUSESKIP_WARNING_TEXT)
            assertEquals(listOf(MOUSESKIP_WARNING_TEXT), s.mouseWarnings)
        } finally {
            s.close()
        }
    }

    @Test
    fun `MOUSESKIP 警告宿主覆盖面 转发 mouseWarnSink 且清单不再收`(@TempDir dir: Path) {
        val f = fixture()
        val got = ArrayList<String>()
        val s = GameSession(
            gameRoot = dir,
            program = f.program,
            metadata = f.metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            mouseWarnSink = { got.add(it) },
        )
        try {
            mouseStateOf(s.store.aux).warnRecorder!!.invoke(MOUSESKIP_WARNING_TEXT)
            assertEquals(listOf(MOUSESKIP_WARNING_TEXT), got)
            assertEquals(emptyList<String>(), s.mouseWarnings, "显式 sink 生效时清单恒空")
        } finally {
            s.close()
        }
    }

    @Test
    fun `脚本求值 MOUSESKIP 时警告逐次落进会话清单`(@TempDir dir: Path) {
        // 端到端：源码 → 编译 → 会话 → 求值期投递（MouseMethods.kt 的 MOUSESKIP 注册体）。
        // 语料 ERB 子树 MOUSESKIP 零命中（grep 0 行），本用例不影响 golden 基线。
        // 频率口径与 C# 的差异（原版解析期逐出现次数、这里求值期逐执行次数）见
        // GameSession.mouseWarnSink 参数 KDoc 与 MouseMethods.kt 的 TODO(verify)。
        val f = fixture(printDataFirst = "PRINTFORML V={MOUSESKIP()}")
        val s = session(f, dir)
        try {
            assertTrue(s.begin(BeginType.TITLE))
            assertEquals(listOf("V=0"), s.console.loggedLines(), "MOUSESKIP 返回 MesSkip 出厂值 0（FMCreator:3644）")
            assertEquals(listOf(MOUSESKIP_WARNING_TEXT), s.mouseWarnings, "求值一次 → 警告一条（文案逐字 = Lang.cs:3850 + FMCreator:3637）")
        } finally {
            s.close()
        }
    }
}
