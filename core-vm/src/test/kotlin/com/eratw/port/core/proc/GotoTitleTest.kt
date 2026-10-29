package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.host.SpritePool
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `ProcessRuntime.gotoTitle()`（GOTOTITLE-CHANNEL）—— 原版错误屏后的进程内恢复通道
 * `EmueraConsole.GotoTitle()`（`MinorShift.Emuera.GameView/EmueraConsole.cs:2569-2581`）
 * 的九条语句逐条对拍：
 *
 * ```
 * forceStopTimer(); ClearDisplay(); AppContents.UnloadGraphicList();
 * redraw = ConsoleRedraw.Normal; UseUserStyle = false;
 * userStyle = new StringStyle(Config.ForeColor, FontStyle.Regular, null);
 * process.BeginTitle(); ReadAnyKey(); RunEmueraProgram(""); RefreshStrings(force_Paint: true);
 * ```
 *
 * 装置与 [GameSessionTest] 同形（真解析器 + 真编译器 + 真 [GameSession]，临时目录、
 * 不吃语料环境变量），钉的是**回标题之后的可观察终态**：
 * 1. 终局 FAILED 之后 [com.eratw.port.core.proc.ProcessRuntime.gotoTitle] 重跑标题链
 *    （而不是返回粘滞的终局步）—— `EmueraConsole.cs:1092-1110` 的 `:1094` 门控被
 *    `ReadAnyKey()`（`:876-895`，写点 `:893`）解除，对应移植侧的 `endNormalTerminal`；
 * 2. `process.BeginTitle()` 的 `vEvaluator.ResetData()`（`Process.cs:480-485` →
 *    `VariableEvaluator.cs:1336-1346`）真的清掉用户/全局/角色状态；
 * 3. `ClearDisplay()`（`EmueraConsole.cs:2801-2813`）的显示行与逻辑行计数归零，
 *    `redraw = ConsoleRedraw.Normal`（`:2574`）与 `userStyle = 出厂 StringStyle`（`:2576`）
 *    两道样式门复位；
 * 4. `AppContents.UnloadGraphicList()`（`AppContents.cs:196-203`）之后 gList 与 sprite
 *    的基图都报未创建，而**登记项与 cbgList 不动**（原版没清它们）。
 */
class GotoTitleTest {

    // ------------------------------------------------------------------
    // 小语料
    // ------------------------------------------------------------------

    private fun parser(vararg ints: String): LineParser {
        var table = SymbolTable().declareVariable("RESULT", OperandType.INT, 0)
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

    private fun privateDim(raw: String): DimSpec {
        val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(raw))
        assertNotNull(split, "测试数据必须是合法 #DIM 行: $raw")
        return DimParser.parse(split.first, split.second, isPrivate = true)
    }

    /**
     * 一份「SYSTEM_TITLE 会自我计数」的产物 + 侧车：脚本体每跑一轮就把全局 `G_ACC` 与
     * 私有 `LOC_ACC` 各加一，并打印两者的当前值 —— 于是「第二轮打印出来的数字」同时钉住
     * 两件事：**跑过第二遍**（标题重跑）与**数字回到 1**（ResetData 生效）。
     */
    private class Fixture(
        val program: Program,
        val metadata: BootMetadata,
    )

    private fun fixture(
        body: List<String>,
        globalDims: List<String> = listOf("#DIM G_ACC"),
    ): Fixture {
        val compiler = ErbProgramCompiler()
        // 符号表要认得脚本里出现的每个名字（G_ACC / LOC_ACC / 额外全局），
        // `#DIM` 不带尺寸时 DimParser 归一成 1 维长度 1，与 declareVariable(_, INT, 1) 同口径
        // （口径与 GameSessionTest.parser() 的注释一致）。
        val names = LinkedHashSet<String>().apply {
            add("G_ACC")
            add("LOC_ACC")
            for (raw in globalDims) add(raw.removePrefix("#DIM").trim())
        }
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
            privateDims = listOf(BootMetadata.PrivateDim("SYSTEM_TITLE", privateDim("#DIM LOC_ACC"))),
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

    private val countingBody = listOf(
        "#DIM LOC_ACC",
        "G_ACC = G_ACC + 1",
        "LOC_ACC = LOC_ACC + 1",
        "PRINTFORML PASS G={G_ACC} L={LOC_ACC}",
        "PRINTL TITLE-END",
    )

    private fun session(f: Fixture, dir: Path): GameSession =
        GameSession(gameRoot = dir, program = f.program, metadata = f.metadata, consoleWidth = 199, randSeed = 20260226L)

    /** 跑一轮标题并泵到终局（`endNormal` = `Process.cs:2959-2962`），回标题的前置状态。 */
    private fun drivenToTerminal(s: GameSession): StateStep {
        assertTrue(s.begin(BeginType.TITLE), "SYSTEM_TITLE 已登记，begin 应执行它")
        return s.resumeSystem()
    }

    private fun read(store: VariableStore, name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 1) 终局 FAILED 之后重跑标题（EmueraConsole.cs:2577-2580 的复活通道）
    // ------------------------------------------------------------------

    @Test
    fun `终局 FAILED 后 gotoTitle 重跑标题链而不是返回粘滞终局步`(@TempDir dir: Path) {
        val s = session(fixture(countingBody), dir)
        try {
            val failed = drivenToTerminal(s)
            assertEquals(StepOutcome.FAILED, failed.outcome, "Normal+栈空 = endNormal 终局（Process.cs:2959-2962）")
            assertEquals("endNormal", failed.handler)

            // 粘滞：不清终局位的话，任何后续 step 都在 BeginFlow.kt:347 早退返回同一条终局步。
            val sticky = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, sticky.outcome)
            assertEquals(failed, sticky, "终局步幂等（BeginFlow.terminalEndNormalStep）")

            // GotoTitle 正是解除这条粘滞的通道：ReadAnyKey 把控制台写成 WaitInput
            // （EmueraConsole.cs:876-895，写点 :893）⇒ RunEmueraProgram("") 重进 runSystemProc。
            val step = s.runtime.gotoTitle()
            assertEquals("beginTitle", step.handler, "不得再返回 endNormal 终局步")
            assertEquals(SystemStateCode.Title_Begin, step.from)
            assertEquals(StepOutcome.CALLED, step.outcome)
            assertEquals("SYSTEM_TITLE", step.calledFunction)
            assertEquals(SystemStateCode.Normal, s.runtime.systemState, "stepTitle 派发前先写 Normal（Process.cs:2138）")

            // 第二轮真的跑了脚本：G_ACC 被 ResetData 打回 0 后又被这一轮 +1（期望 1，实际 2 = 没复位）。
            assertEquals(1L, read(s.store, "G_ACC"))
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 2) BeginTitle 的变量池半边（Process.cs:480-485 → VariableEvaluator.cs:1336-1346）
    // ------------------------------------------------------------------

    @Test
    fun `gotoTitle 走 ResetData 清掉用户全局与角色状态`(@TempDir dir: Path) {
        val s = session(fixture(countingBody), dir)
        try {
            drivenToTerminal(s)
            // 第一轮留下的：全局 #DIM G_ACC=1、私有 LOC_ACC=1（都在 PRINTFORML 的读数里）
            assertEquals(1L, read(s.store, "G_ACC"))
            assertEquals(listOf("PASS G=1 L=1", "TITLE-END"), s.console.loggedLines())
            // 系统数组（resetValues 那半边）+ 角色表（deleteAllCharacters 那半边）
            s.store.writeInt("MONEY", longArrayOf(0), 12_345L)
            assertEquals(4, s.store.characters.count, "无 CSV 时 GameSession 种 4 个裸角色")

            s.runtime.gotoTitle()

            assertEquals(1L, read(s.store, "G_ACC"), "复位到 0 后由第二轮 +1；读到 2 = ResetData 没跑")
            assertEquals(0L, read(s.store, "MONEY"), "SetDefaultValue（VariableData.cs:3385-3400）")
            assertEquals(0, s.store.characters.count, "CharacterList.Clear（VariableEvaluator.cs:1336-1346）")
            // 打印出来的 L 同样回到 1：函数内 #DIM 是**静态**声明（DimParser 缺省 static，
            // UserVariables.kt:417），复位走 `VariableStore.resetLocalValues` 的 privateVars 分支
            // （`vars/VariableStore.kt:1725-1741`），对应原版 `SetDefaultLocalValue` 遍历
            // `localvarTokenDic`（`VariableData.cs:3365-3375`）；全局 #DIM（非 GLOBAL）走同一复位
            // （C# 侧进 `userDefinedStaticVarList`，`VariableData.cs:3265-3271`）。
            assertEquals(listOf("PASS G=1 L=1", "TITLE-END"), s.console.loggedLines())
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 3) ClearDisplay + redraw + userStyle（EmueraConsole.cs:2572-2576）
    // ------------------------------------------------------------------

    @Test
    fun `gotoTitle 清空显示并把重绘与用户样式门复位`(@TempDir dir: Path) {
        val s = session(fixture(countingBody), dir)
        try {
            drivenToTerminal(s)
            // 错误屏留下的文本行（原版错误文案的位置）——回标题必须把它连同第一轮内容一起清掉
            s.console.printLine("BEFORE-GOTO")
            // 剧本留在的非出厂样式与非 Normal 重绘态
            s.console.setRedraw(0)
            s.console.setForeColor(255, 0, 0)
            s.console.setFontStyle(1)
            s.console.setFont("NoSuchFamilyZZZ")
            s.console.setAlignment(Alignment.RIGHT)
            assertEquals(3L, s.console.lineCount, "三轮之前的显示行：2 条脚本 + 1 条错误屏")
            assertEquals(0, s.console.redraw, "REDRAW 0 = ConsoleRedraw.None")

            s.runtime.gotoTitle()

            // ClearDisplay：displayLineList.Clear（:2804）+ logicalLineCount = 0（:2807），
            // 然后 RunEmueraProgram("") 重跑的标题只留下自己那两行。
            assertEquals(listOf("PASS G=1 L=1", "TITLE-END"), s.console.loggedLines(), "旧屏文本必须整屏消失")
            assertEquals(2, s.console.displayLineCount)
            assertEquals(2L, s.console.lineCount, "LINECOUNT 从 0 重新计数")
            // redraw = ConsoleRedraw.Normal（:2574；ConsoleRedraw.cs:6-10 的枚举序）
            assertEquals(1, s.console.redraw)
            // userStyle = new StringStyle(Config.ForeColor, Regular, null)（:2576）= ResetStyle 四项
            // （EmueraConsole.cs:2838-2842 + StringStyle.cs:20-27；背景色与缓冲不在复位集内）
            assertNull(s.console.foregroundColor, "前景回落 Config.ForeColor")
            assertEquals(0, s.console.fontStyle, "FontStyle.Regular")
            assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, s.console.fontName, "空字体名归一成 Config.FontName")
            assertEquals(Alignment.LEFT, s.console.alignment)
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 4) UnloadGraphicList（AppContents.cs:196-203）——只卸 gList，不动登记项与 cbgList
    // ------------------------------------------------------------------

    @Test
    fun `gotoTitle 卸掉图形缓冲但保留 sprite 登记与 cbg 图层`(@TempDir dir: Path) {
        val s = session(fixture(countingBody), dir)
        try {
            drivenToTerminal(s)
            val aux = s.store.aux
            val gfx = aux.slot("gfx") { GraphicsPool() }
            val sprites = aux.slot("sprite") { SpritePool() }
            val cbg = aux.slot("cbg") { CbgPool() }
            val base = gfx.get(7)
            base.create(4, 4)
            val frameBase = gfx.get(9)
            frameBase.create(2, 2)
            // 静态 sprite（原版 SpriteG，基图 = gList 里的 GraphicsImage）
            sprites.put(SpritePool.Sprite("TEST_SPR", 4, 4, buffer = base))
            // 动画 sprite（原版 SpriteAnime：IsCreated 恒 true，但帧基图同样在 gList 里）
            sprites.put(
                SpritePool.Sprite(
                    "TEST_ANI", 2, 2,
                    frames = mutableListOf(SpritePool.Frame(frameBase, 0, 0, 2, 2, 0, 0, 10)),
                ),
            )
            cbg.add(CbgPool.Layer(depth = 3, gid = 7))
            assertTrue(base.isCreated && frameBase.isCreated)
            assertTrue(sprites.find("TEST_SPR")!!.isCreated)

            s.runtime.gotoTitle()

            // gList.Clear：按编号再取得到的是未创建的空条目（AppContents.cs:28-37 ↔ GraphicsPool.get）
            assertNull(gfx.peek(7))
            assertFalse(gfx.get(7).isCreated)
            assertFalse(gfx.get(9).isCreated)
            // 逐条 GDispose 的可观察效果落在 sprite 上：静态 sprite 报未创建
            // （SpriteG.IsCreated => BaseImage.IsCreated，ASpriteSingle.cs:24-34）
            val spr = sprites.find("TEST_SPR")
            assertNotNull(spr, "GotoTitle 不动 imageDictionary（AppContents.cs:196-203 只清 gList）")
            assertFalse(spr.isCreated)
            assertFalse(base.isCreated)
            assertFalse(frameBase.isCreated, "动画帧的基图也在 gList 里，同批 GDispose")
            // 动画 sprite 的 IsCreated 原版恒 true（SpriteAnime.cs:54）⇒ 卸掉登记项才是偏离
            val ani = sprites.find("TEST_ANI")
            assertNotNull(ani)
            assertTrue(ani.isCreated)
            assertEquals(2, sprites.size())
            // cbgList / cbgButtonMap：GotoTitle 与 ClearDisplay 都没碰（:2569-2581 + :2801-2813）
            assertEquals(1, cbg.layers.size, "回标题不清背景层")

            // 二次回标题：池已空也不能炸（peek 返回 null 的分支）
            val again = s.runtime.gotoTitle()
            assertEquals("beginTitle", again.handler)
        } finally {
            s.close()
        }
    }

    // ------------------------------------------------------------------
    // 5) 移植侧两条额外复位的读者（quit 闩锁 / 读档交接待办）
    // ------------------------------------------------------------------

    /**
     * `IF G_STOP` 包住 `QUIT`：第一次进标题时 `G_STOP=1` ⇒ 落 `quitRequested`
     * （移植侧对 `ConsoleState.Quit` 的建模，`EmueraConsole.cs:731-734` + `:270-284`）；
     * `gotoTitle` 先清闩锁、`ResetData` 又把 `G_STOP` 打回 0，所以第二轮不再 QUIT。
     * 原版侧这条门控解除就是 `ReadAnyKey()` 把 `state` 无条件写成 `WaitInput`
     * （`EmueraConsole.cs:893`），Quit 态随之消失。
     */
    @Test
    fun `gotoTitle 清 quit 闩锁与读档交接待办`(@TempDir dir: Path) {
        val body = listOf(
            "#DIM LOC_ACC",
            "G_ACC = G_ACC + 1",
            "PRINTL QUIT-MARK",
            "IF G_STOP",
            "QUIT",
            "ENDIF",
        )
        val s = session(fixture(body, globalDims = listOf("#DIM G_ACC", "#DIM G_STOP")), dir)
        try {
            s.store.writeInt("G_STOP", longArrayOf(0), 1L)
            assertTrue(s.begin(BeginType.TITLE))
            assertTrue(s.runtime.quitRequested, "QUIT 之后宿主泵会停转（GameHost.drive 的停转条件）")

            SaveLoadHandoff.of(s.store)!!.requestDataLoaded()

            val step = s.runtime.gotoTitle()
            assertEquals(StepOutcome.CALLED, step.outcome, "标题 handler 照常跑完（本轮 G_STOP=0，只是不再 QUIT）")
            assertEquals("SYSTEM_TITLE", step.calledFunction)
            assertFalse(s.runtime.quitRequested, "回标题后宿主泵必须能继续跑")
            assertEquals(0L, read(s.store, "G_STOP"))
            // Begin(TITLE) 覆盖 sysStateCode（ProcessState.cs:276）⇒ 读档交接的待办不得把
            // 下一步拐进 LoadData 链；下一步照常是 Normal 的终局步。
            assertNull(SaveLoadHandoff.of(s.store)!!.systemState)
            val next = s.resumeSystem()
            assertEquals(StepOutcome.FAILED, next.outcome, "不是 (loaddata handoff) 的 CONTINUE")
            assertEquals(listOf("QUIT-MARK"), s.console.loggedLines())
        } finally {
            s.close()
        }
    }

    /**
     * 回标题**不**做的事（铁律「不顺手修好」）：原版 `forceStopTimer()` 只关 `genericTimer`
     * （`EmueraConsole.cs:1084-1091`，那是定时输入的倒计时器，见 `:130`/`:550-553`/`:998`），
     * 与音频无关 ⇒ 声道表不因回标题而关闭（移植侧没有 genericTimer，那一步在 core-vm 无落点）；
     * `keytoggle` 是进程级 static（`FunctionMethodCreator.cs:9035`，静态构造 `:9039`），
     * `GotoTitle` 的九条语句里没有它 ⇒ 移植侧那份 aux 槽同样跨本方法存活。
     * 本用例把「回标题不清按键沿表、更不整表 `RuntimeAux.clear()`」钉住，防止后来者顺手加复位。
     */
    @Test
    fun `gotoTitle 不清按键沿表也不整表清空 aux`(@TempDir dir: Path) {
        val s = session(fixture(countingBody), dir)
        try {
            drivenToTerminal(s)
            val aux = s.store.aux
            val keytoggle = aux.slot("keytoggle") { IntArray(256) }
            keytoggle[65] = 2 // GETKEYTRIGGERED 的沿记忆（FunctionMethodCreator.cs:7827 的 (keyState&1)+1）
            val gfx = aux.slot("gfx") { GraphicsPool() }
            gfx.get(3).create(2, 2)

            s.runtime.gotoTitle()

            // aux 槽跨 ResetData 存活（RuntimeAux 与变量池同生命周期，且刻意不被 resetValues 触及）
            val after = aux.peek<IntArray>("keytoggle")
            assertNotNull(after, "按键沿表不该被回标题抹掉（原版它是进程级 static）")
            assertEquals(2, after[65])
            // 槽对象本身还在（只卸内容，绝不整表 RuntimeAux.clear() —— 那会连 imageDecoder /
            // save.wiring / config.state 一起抹掉）
            assertNotNull(aux.peek<GraphicsPool>("gfx"), "gfx 槽保留，内容按 UnloadGraphicList 卸掉")
            assertNull(aux.peek<GraphicsPool>("gfx")!!.peek(3), "已登记的缓冲随 gList.Clear 一起消失")
            assertFalse(aux.peek<GraphicsPool>("gfx")!!.get(3).isCreated)
        } finally {
            s.close()
        }
    }
}
