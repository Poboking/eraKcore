package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.FunctionBytecode
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * LIVE-REFLOW 会话面（L2）：[GameSession.resizeConsole] 与会话期屏幕像素读数。
 *
 * 钉住四条不变量：
 * 1. 关闭开关（`reflowConsole = false`）时 `resizeConsole` 恒 false 且**不碰任何状态**
 *    （控制台列宽 / `DRAWLINESTR` 背衬 / 像素读数三段逐一比对）；
 * 2. 像素读数走 [ScriptScreenState] 活状态：`CLIENTWIDTH()` / `CLIENTHEIGHT()` 的下一次
 *    求值即新值（原版两条是控件活尺寸，EmueraConsole.cs:232/234；
 *    函数层 FunctionMethodCreator.cs:6777-6799）；
 * 3. 像素高度单独变化只更新读数、不重排、不动 `DRAWLINESTR`
 *    （宽度判据在 `LineBufferConsoleSink.resize` 手里，本类不另判一次）；
 * 4. 重排发生时 `DRAWLINESTR` 必须换成重排后的 `stBar`
 *    （原版 PrintBar 与 DRAWLINESTR 读同一个 stBar 字段：EmueraConsole.cs:3392/3417/3441 +
 *    VariableData.cs:976-986）；
 * 5. 读数单位 = **脚本 px**：宿主报的设备内容区像素按「设备字号 / 配置字号」折回脚本坐标系
 *    （渲染层按同一比例再折设备像素；不折就是整幅放大 `设备字号 / 配置字号` 倍）。
 *    未注入字号或两档相等时折算恒等 —— 既有装置读数逐字不变。
 *
 * 第 3、4 条经 [GameSession.applyResizeOutcome] 断言：L1 路的
 * `LineBufferConsoleSink.resize` 在本次工作树里仍是骨架（`reflowEnabled = true` 时显式抛错），
 * `reflowed = true` 这一支无法在本树端到端产出。断言用的是**真实** store / console，
 * 只把 sink 的布尔结论按两种取值分别代入 —— 不是假宿主，也不是假断言。
 * 端到端（真 sink 换宽）的复跑卡点记在交付报告。
 */
class GameSessionResizeTest {

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 最小产物：只有 `@SYSTEM_TITLE` + 一句 `PRINTL`（同 GameSessionDrawLineWireTest 口径）。 */
    private fun session(
        root: Path,
        reflow: Boolean,
        widthPx: Long = 2400L,
        heightPx: Long = 1100L,
        deviceFont: Int = 0,
    ): GameSession {
        val compiler = ErbProgramCompiler()
        val stmts = listOf("@SYSTEM_TITLE", "PRINTL PLAIN").mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            LineParser(SymbolTable()).parseLogicalLine(LogicalLine(kind, "TEST.ERB", i + 1, text))
        }
        val fb: FunctionBytecode = compiler.compileFunction(
            FuncSig("SYSTEM_TITLE"),
            stmts,
            setOf("SYSTEM_TITLE"),
        )
        val program: Program = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "TEST.ERB:1",
                ),
            ),
        )
        return GameSession(
            gameRoot = root,
            program = program,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 1L,
            clientWidthPx = widthPx,
            clientHeightPx = heightPx,
            deviceFontPixelSize = deviceFont,
            reflowConsole = reflow,
        )
    }

    /** 宿主 → 内建函数求值上下文（同 ConsoleBuiltinHostTest 的假宿主形状）。 */
    private fun hostCtx(host: BuiltinHost): BuiltinEvalContext {
        val store = VariableStore()
        val base = object : EvalContext {
            override lateinit var variables: VariableReader
            override fun callFunction(name: String, args: List<Value?>): Value =
                throw CodeEE("hostCtx 不应被直接调用：$name")
            override fun printSystemLine(message: String) {}
        }
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun iv(ctx: BuiltinEvalContext, name: String): Long =
        (ctx.callFunction(name, emptyList()) as Value.IntV).v

    // ------------------------------------------------------------------
    // 1) ScriptScreenState 本体
    // ------------------------------------------------------------------

    @Test
    fun `像素状态读写与出厂缺省`() {
        val fresh = ScriptScreenState()
        assertEquals(BuiltinHost.DEFAULT_CLIENT_WIDTH, fresh.widthPx, "缺省 = 原版 WindowX 760（ConfigData.cs:56）")
        assertEquals(BuiltinHost.DEFAULT_CLIENT_HEIGHT, fresh.heightPx, "缺省 = WindowY-LineHeight 461（ConfigData.cs:57,67）")
        assertEquals("760x461", fresh.toString())

        fresh.update(1234L, 567L)
        assertEquals(1234L, fresh.widthPx)
        assertEquals(567L, fresh.heightPx)

        // 单格直写也生效（getter 读的是同一份字段，不是快照）
        fresh.widthPx = 99L
        assertEquals(99L, fresh.widthPx)
        assertEquals(567L, fresh.heightPx)
    }

    // ------------------------------------------------------------------
    // 2) ConsoleBuiltinHost 的 getter 跟随
    // ------------------------------------------------------------------

    @Test
    fun `未注入状态时 CLIENTWIDTH 与 CLIENTHEIGHT 仍读注入的构造参数`() {
        // 回落口径回归：screenState = null（既有装配与既有用例的形状）时，
        // 两个读数必须逐字等于构造参数 —— 这同时钉住「同名构造参数 → 私有回落字段」那一跳
        // 取的是参数而不是属性自身（自递归会读成 0）。
        val host = ConsoleBuiltinHost(dir, clientWidth = 2400L, clientHeight = 1100L)
        assertEquals(2400L, host.clientWidth)
        assertEquals(1100L, host.clientHeight)
        val ctx = hostCtx(host)
        assertEquals(2400L, iv(ctx, "CLIENTWIDTH"))
        assertEquals(1100L, iv(ctx, "CLIENTHEIGHT"))
    }

    @Test
    fun `注入状态后两条读数跟随活状态而不是构造参数`() {
        val state = ScriptScreenState()
        val host = ConsoleBuiltinHost(
            dir,
            clientWidth = 2400L,
            clientHeight = 1100L,
            screenState = state,
        )
        // 优先读 state：构造参数降为回落值（state 缺省 760x461 压过注入的 2400x1100）
        assertEquals(760L, host.clientWidth)
        assertEquals(461L, host.clientHeight)
        val ctx = hostCtx(host)
        assertEquals(760L, iv(ctx, "CLIENTWIDTH"))
        assertEquals(461L, iv(ctx, "CLIENTHEIGHT"))

        // 写一次即读新值（原版 ClientWidth/ClientHeight 每次现读控件，EmueraConsole.cs:232/234）
        state.update(1080L, 2000L)
        assertEquals(1080L, host.clientWidth)
        assertEquals(2000L, host.clientHeight)
        assertEquals(1080L, iv(ctx, "CLIENTWIDTH"))
        assertEquals(2000L, iv(ctx, "CLIENTHEIGHT"))

        // fontSize / printC 两格与本状态无关（本路不动它们）
        assertEquals(BuiltinHost.DEFAULT_FONT_SIZE, host.fontSize)
        assertEquals(BuiltinHost.DEFAULT_PRINT_C_LENGTH, host.printCLength)
    }

    @Test
    fun `会话装配把同一份状态交给宿主`() {
        val s = session(dir, reflow = false, widthPx = 2400L, heightPx = 1100L)
        assertEquals(2400L, s.screenState.widthPx, "初值 = 构造参数（真机内容区实测值）")
        assertEquals(1100L, s.screenState.heightPx)
        assertEquals(2400L, s.builtinHost.clientWidth, "宿主读到会话自持的同一份活状态")
        assertEquals(1100L, s.builtinHost.clientHeight)
        val ctx = hostCtx(s.builtinHost)
        assertEquals(2400L, iv(ctx, "CLIENTWIDTH"))
        assertEquals(1100L, iv(ctx, "CLIENTHEIGHT"))
    }

    // ------------------------------------------------------------------
    // 2b) 设备像素 → 脚本 px 折算（真机背景图那条）
    // ------------------------------------------------------------------

    @Test
    fun `设备字号与配置字号不等时 CLIENTWIDTH 折回脚本 px`() {
        // 真机横屏形状：内容区 2400x1100 设备像素、版面解算字号 33、配置字号 18
        // （ScreenLayout 的列数守护把 34 压到 33）。语料拿 CLIENTWIDTH() 开 CG 画布
        // （QOL_IMAGE.ERB 的 GCREATE），渲染层再按 33/18 折回设备像素 —— 引擎不先除回去，
        // 整幅背景就放大 1.83 倍（玩家看到「只剩左上角一块」）。
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L, deviceFont = 33)
        assertEquals(18L, s.builtinHost.fontSize, "配置字号仍是生效 Config.FontSize（设备字号不进来）")
        assertEquals(1310L, s.builtinHost.clientWidth, "ceil(2400 x 18 / 33) = 1310")
        assertEquals(600L, s.builtinHost.clientHeight, "1100 x 18 / 33 = 600")

        // 设备像素原样留在活状态里（诊断/宿主口径）；折算只发生在交给脚本的读数上
        assertEquals(2400L, s.screenState.widthPx)
        assertEquals(1100L, s.screenState.heightPx)
        assertEquals("2400x1100", s.screenState.toString())

        val ctx = hostCtx(s.builtinHost)
        assertEquals(1310L, iv(ctx, "CLIENTWIDTH"), "函数层读到的就是脚本 px")
        assertEquals(600L, iv(ctx, "CLIENTHEIGHT"))
    }

    @Test
    fun `视口回调换字号档后折算比例跟着换`() {
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L, deviceFont = 33)
        assertEquals(1310L, s.builtinHost.clientWidth)

        // 转竖屏：内容区 1080x1920，版面字号落到 15 档（同一份 18 的配置字号）。
        // 只换像素不换分母的话，CLIENTWIDTH 会按 18/33 去折 1080 的新宽度。
        assertFalse(
            s.applyResizeOutcome(
                reflowed = false,
                widthPx = 1080L,
                heightPx = 1920L,
                deviceFontPixelSize = 15,
            ),
        )
        assertEquals(15, s.screenState.deviceFontPixelSize, "设备字号随视口回调落账")
        assertEquals(1296L, s.builtinHost.clientWidth, "1080 x 18 / 15 = 1296")
        assertEquals(2304L, s.builtinHost.clientHeight, "1920 x 18 / 15 = 2304")
    }

    @Test
    fun `字号未注入或同档时折算恒等`() {
        // 设备字号 0 = 未知 ⇒ 恒等：headless、桌面 vmdbg 与既有装置（含本文件其余用例）读数不变
        val unknown = session(dir, reflow = false, widthPx = 2400L, heightPx = 1100L)
        assertEquals(2400L, unknown.builtinHost.clientWidth)
        assertEquals(1100L, unknown.builtinHost.clientHeight)

        // 1:1 版面（设备字号 == 配置字号）同样是恒等
        val oneToOne = ScriptScreenState(
            widthPx = 2400L,
            heightPx = 1100L,
            deviceFontPixelSize = 18,
        )
        assertEquals(2400L, oneToOne.scriptWidthPx(18))
        assertEquals(1100L, oneToOne.scriptHeightPx(18))

        // 向上取整：0 宽保持 0，非整除时补满（避免整幅画布比内容区窄一条缝）
        assertEquals(0L, ScriptPixels.toScriptPx(0L, 33, 18))
        assertEquals(1310L, ScriptPixels.toScriptPx(2400L, 33, 18))
        assertEquals(655L, ScriptPixels.toScriptPx(1200L, 33, 18), "ceil(654.5) = 655")

        // 有符号坐标（MOUSEY 在客户区内恒为负）同样向上取整：截断除法在负数上会 floor，
        // 边界两侧各偏 1 px（-549 x 18 / 33 = -299.45 → -299，不是 -300）
        assertEquals(-299L, ScriptPixels.toScriptPx(-549L, 33, 18))
        assertEquals(-300L, ScriptPixels.toScriptPx(-550L, 33, 18), "-300 整除，不再补满")
    }

    @Test
    fun `未注入活状态时回落读数也走同一把折尺`() {
        // screenState = null 的装配（既有测试形状）也要能折算：分母取 fontSize（配置字号），
        // 分子取注入的设备字号。字号未给时仍是恒等（与改造前逐字相同）。
        val host = ConsoleBuiltinHost(
            dir,
            clientWidth = 2400L,
            clientHeight = 1100L,
            fontSize = 18L,
            deviceFontPixelSize = 33,
        )
        assertEquals(1310L, host.clientWidth)
        assertEquals(600L, host.clientHeight)
    }

    // ------------------------------------------------------------------
    // 2c) MOUSEX / MOUSEY 与 CLIENTWIDTH 同尺（脚本 px）
    // ------------------------------------------------------------------

    @Test
    fun `会话装配把 MOUSEX 与 MOUSEY 也折回脚本 px`() {
        // 语料拿 MOUSEX() 排 UI 位置（UI_Container_Framework.ERB:823）并与
        // GETCONFIG("ウィンドウ幅") 比（开锁系统.ERB:230）：它必须与 CLIENTWIDTH() 同一把尺，
        // 否则弹窗按设备像素摆进脚本 px 的几何里，出屏 / 角度过 1。
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L, deviceFont = 33)
        val mouse = mouseStateOf(s.store.aux)
        mouse.windowCreated = true
        mouse.setClientSize(2400, 1100)
        mouse.setPointerPosition(1200, 551)

        assertEquals(655L, mouse.mouseX(), "ceil(1200 x 18 / 33) = 655")
        assertEquals(-299L, mouse.mouseY(), "ceil((551 - 1100) x 18 / 33) = ceil(-299.45) = -299")

        // 内部现场仍是设备像素：越界判定按宿主报的尺做（半开区间 [0, W) x [0, H)）
        assertTrue(mouse.isPointerInClientArea())
        mouse.setPointerPosition(2400, 1100)
        assertFalse(mouse.isPointerInClientArea(), "右/下边界是半开区间 [0, 2400) x [0, 1100)")
        assertEquals(1310L, mouse.mouseX(), "MOUSEX 不做越界闸（越界由 MOUSEB 的 Contains 判）")
        assertEquals(0L, mouse.mouseY(), "y == Height ⇒ 位移量 0")
    }

    @Test
    fun `转屏换字号档后 MOUSE 族读数跟着换分母`() {
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L, deviceFont = 33)
        val mouse = mouseStateOf(s.store.aux)
        mouse.windowCreated = true
        mouse.setClientSize(2400, 1100)
        mouse.setPointerPosition(1200, 600)
        assertEquals(655L, mouse.mouseX())

        s.applyResizeOutcome(reflowed = false, widthPx = 1080L, heightPx = 1920L, deviceFontPixelSize = 15)
        mouse.setClientSize(1080, 1920)
        mouse.setPointerPosition(540, 960)
        assertEquals(648L, mouse.mouseX(), "540 x 18 / 15 = 648")
        assertEquals(-1152L, mouse.mouseY(), "(960 - 1920) x 18 / 15 = -1152")
    }

    @Test
    fun `字号未注入时 MOUSE 族读数恒等`() {
        // headless / 既有装置（设备字号 0）：折算恒等，读数与改造前逐字相同
        val s = session(dir, reflow = false, widthPx = 2400L, heightPx = 1100L)
        val mouse = mouseStateOf(s.store.aux)
        mouse.windowCreated = true
        mouse.setClientSize(2400, 1100)
        mouse.setPointerPosition(1200, 551)
        assertEquals(1200L, mouse.mouseX())
        assertEquals(-549L, mouse.mouseY())
    }

    // ------------------------------------------------------------------
    // 3) 关闭态：必须彻底 no-op
    // ------------------------------------------------------------------

    @Test
    fun `关闭开关时 resizeConsole 恒 false 且三段状态逐字不动`() {
        val s = session(dir, reflow = false, widthPx = 2400L, heightPx = 1100L)
        val columnsBefore = s.console.clientWidth
        val drawLineBefore = s.store.drawLineStr
        val pixelsBefore = s.screenState.toString()
        assertEquals("-".repeat(199), drawLineBefore, "装配期 stBar 已进背衬（Process.cs:258-259）")

        assertFalse(s.resizeConsole(64, 900L, 700L), "关闭态必须返回 false")
        assertEquals(columnsBefore, s.console.clientWidth, "折行列宽不动")
        assertEquals(drawLineBefore, s.store.drawLineStr, "DRAWLINESTR 背衬不换串")
        assertEquals(pixelsBefore, s.screenState.toString(), "像素读数不更新")
        assertEquals(2400L, s.builtinHost.clientWidth, "脚本读数不变")
        assertEquals(1100L, s.builtinHost.clientHeight)

        // 关闭态连参数校验都不做：非法实参同样按 no-op 返回 false（关态调用方本就不该存在，
        // 但一条静默 no-op 比一条只在关态才炸的异常更安全 —— 契约 §4 第 1 条先于第 2 条）
        assertFalse(s.resizeConsole(0, -1L, -1L))
        assertEquals(columnsBefore, s.console.clientWidth)
        assertEquals(pixelsBefore, s.screenState.toString())
    }

    // ------------------------------------------------------------------
    // 4) 打开开关：参数闸 + 只在宽度真变时重排
    // ------------------------------------------------------------------

    @Test
    fun `打开开关时非法实参直接拒绝且不落账`() {
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L)
        assertFailsWith<IllegalArgumentException> { s.resizeConsole(0, 900L, 700L) }
        assertFailsWith<IllegalArgumentException> { s.resizeConsole(-3, 900L, 700L) }
        assertFailsWith<IllegalArgumentException> { s.resizeConsole(64, -1L, 700L) }
        assertFailsWith<IllegalArgumentException> { s.resizeConsole(64, 900L, -1L) }
        // 拒绝发生在落账之前
        assertEquals(2400L, s.screenState.widthPx)
        assertEquals(1100L, s.screenState.heightPx)
        assertEquals("-".repeat(199), s.store.drawLineStr)
    }

    @Test
    fun `仅高度变化不触发重排只更新读数`() {
        // 宽度未变 ⇒ sink 的 resize 返回 false（LineBufferConsoleSink.resize 的宽度判据）
        // ⇒ 本类只落像素读数，历史行与 DRAWLINESTR 一个字都不动。
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L)
        // 换一个哨兵串，证明「没重排 ⇒ 不回写背衬」
        s.store.drawLineStr = "=SENTINEL="
        val columnsBefore = s.console.clientWidth

        assertFalse(s.applyResizeOutcome(reflowed = false, widthPx = 2400L, heightPx = 700L))

        assertEquals(2400L, s.screenState.widthPx, "宽度读数按宿主报的值落账（同宽不变）")
        assertEquals(700L, s.screenState.heightPx, "高度读数更新")
        assertEquals("=SENTINEL=", s.store.drawLineStr, "未重排不动 DRAWLINESTR")
        assertEquals(columnsBefore, s.console.clientWidth, "折行列宽不动")
        assertEquals(2400L, s.builtinHost.clientWidth)
        assertEquals(700L, s.builtinHost.clientHeight, "CLIENTHEIGHT() 的下一次求值即新高度")
    }

    @Test
    fun `重排后 DRAWLINESTR 换成重排后的 stBar`() {
        // reflowed = true 支路：宽度真变、sink 已按新宽度重建 stBar（EmueraConsole.cs:3441
        // setStBar 重算），背衬必须跟着换串，否则 PrintBar 与 DRAWLINESTR 分叉
        // （两者读同一个 stBar 字段：:3392 / :3417 + VariableData.cs:976-986）。
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L)
        s.store.drawLineStr = "=STALE="
        val stBar = s.console.stBar

        assertTrue(s.applyResizeOutcome(reflowed = true, widthPx = 1920L, heightPx = 1080L))

        assertEquals(stBar, s.store.drawLineStr, "背衬 = 控制台当前 stBar")
        assertEquals("-".repeat(199), s.store.drawLineStr, "本树里 stBar 仍是 199 格（L1 落地后随新宽度）")
        assertEquals(1920L, s.screenState.widthPx)
        assertEquals(1080L, s.screenState.heightPx)
        assertEquals(1920L, s.builtinHost.clientWidth)
        assertEquals(1080L, s.builtinHost.clientHeight)
    }

    @Test
    fun `sink 仍是骨架时 resizeConsole 不会假成功`() {
        // 防假绿的负面判据：打开开关后真调 resizeConsole，若 L1 的 sink 还没落地/静默空转，
        // 这里绝不能得到 true。骨架形态是显式抛错；L1 落地后换宽返回 true、同宽返回 false，
        // 两种都在允许集内 —— 本条只钉「没有第三种（假 true）形态」。
        val s = session(dir, reflow = true, widthPx = 2400L, heightPx = 1100L)
        val outcome = runCatching { s.resizeConsole(60, 900L, 700L) }
        outcome.exceptionOrNull()?.let { err ->
            assertTrue(
                err is NotImplementedError,
                "打开开关后 sink.resize 只允许「未实现」抛错或返回布尔，实为 $err",
            )
            return
        }
        assertEquals(60, s.console.clientWidth, "返回时列宽必须已真的改成 60")
        assertEquals(900L, s.screenState.widthPx)
        assertEquals(700L, s.screenState.heightPx)
        assertEquals(s.console.stBar, s.store.drawLineStr, "重排成功则背衬必须换串")
    }
}
