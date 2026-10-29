package com.eratw.port.core.host

import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.sem.DataSections
import com.eratw.port.core.sem.dualKeySections
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `EmueraConsole.UseUserStyle` 这条轴的语义测试。
 *
 * ## 为什么它是一条独立的轴
 *
 * 原版 `Style` 取值器有**四条**分支（`EmueraConsole.cs:471-489`），`UseUserStyle` 是**最先**
 * 判的一条、也是**唯一**能把整条样式（前景 + 字型位 + 字体名）整体打回 `defaultStyle` 的开关：
 *
 * ```
 * if (!UseUserStyle) return defaultStyle;                      // :471-472  ← 本文件的主判据
 * if (UseSetColorStyle) return userStyle;                      // :473-474
 * if (userStyle.Color == defaultStyle.Color) return userStyle; // :483-486
 * return new StringStyle(defaultStyle.Color, userStyle.FontStyle, userStyle.Fontname); // :487-488
 * ```
 *
 * 另一条轴 `UseSetColorStyle` 只压**前景色**、不压字型位与字体名（:487-488 明写保留
 * `userStyle.FontStyle` / `Fontname`），见 `ConsolePopDisplayTest` 与
 * `ConsoleStyleWiringTest` 的 D 系用例。
 *
 * ## 出厂值与粘滞
 *
 * - 出厂 **false**：C# `bool` 字段默认值（`EmueraConsole.cs:467`），进程启动即如此。
 *   因此「还没执行过任何 PRINT 语句」时打印是默认样式 —— 这不是移植侧的疏漏。
 * - 写真五处：`FunctionIdentifier.cs:179`（PRINT 族）、`:276`（PRINTDATA 族）、
 *   `Process.cs:1110`（PRINTBUTTON）、`:1129`（PRINTBUTTONC/LC）、`:1149`（PRINTPLAIN/FORM），
 *   全部在 `if (!SkipPrint)` 内、**求值之前**。
 * - 写假六处：`EmueraConsole.cs:764`（ThrowError）、`:2575`（GotoTitle）、`:2607`（Ctrl+Z）、
 *   `:3094`（PrintSystemLine）、`:3108`（PrintError）、`:3127`（PrintErrorButton）。
 *   这六处**没有配对写回** ⇒ 开关是粘滞的：写假之后只有下一条 PRINT 系语句能恢复用户样式。
 *
 * 第 5、6 组用例钉的正是这条粘滞语义（「不擅自打完就还原」）。
 */
class ConsoleUseUserStyleTest {

    // ==================================================================
    // 1. sink 级：Style getter 的第一条分支
    // ==================================================================

    private fun sink() = LineBufferConsoleSink(clientWidth = 80, defaultFontName = "黑体")

    /** 让三件样式全部偏离出厂态（前景 / 字型位 / 字体名）。 */
    private fun dirty(s: LineBufferConsoleSink) {
        s.setForeColor(255, 0, 0)
        s.setFontStyle(1) // Bold
        s.setFont("Saitamaar")
    }

    /**
     * 出厂 false ⇒ 打印段整条取 `defaultStyle`，即便用户已经 SETCOLOR/FONTSTYLE/SETFONT。
     *
     * 出处：`EmueraConsole.cs:467`（`bool` 默认 false）+ `:471-472`（第一条分支直接
     * `return defaultStyle`，连 `UseSetColorStyle` 都不看）。三件样式**一起**回落才是这条轴
     * 的特征 —— 若只回落前景色，那是 `UseSetColorStyle` 那条轴（:487-488 保留字型/字体名）。
     */
    @Test
    fun `未开轴时打印段整条取 defaultStyle`() {
        val s = sink()
        dirty(s)
        s.printLine("x")

        val seg = s.snapshot().single().segments.single()
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor, "defaultStyle.Color（:471-472）")
        assertEquals(0, seg.styleMask, "defaultStyle.FontStyle = Regular（StringStyle.cs:15-17）")
        assertEquals("黑体", seg.fontName, "defaultStyle.Fontname 归一成 Config.FontName（StringStyle.cs:20-27）")
        // 读出侧不受这条轴影响：原版 StringStyle => userStyle（:491），与 UseUserStyle 无关。
        assertEquals(0xFF0000, s.foregroundColor, "GETCOLOR 读 userStyle.Color 本体")
        assertEquals(1, s.fontStyle, "GETSTYLE 读 userStyle.FontStyle 本体")
        assertEquals("Saitamaar", s.fontName, "GETFONT 读 userStyle.Fontname 本体")
    }

    /** 开轴后三件样式逐项生效（`Style` getter 的第二条分支，:473-474）。 */
    @Test
    fun `开轴后三件样式逐项生效`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.printLine("x")

        val seg = s.snapshot().single().segments.single()
        assertEquals(0xFF0000, seg.foreColor)
        assertEquals(1, seg.styleMask)
        assertEquals("Saitamaar", seg.fontName)
    }

    /**
     * 写假之后整条回默认样式，且**优先于** `UseSetColorStyle`。
     *
     * 最后一段特意在 `useSetColorStyle = false` 的 D 系窗口里打印：两条轴同时为假时，
     * 原版在 :471-472 就返回了，根本走不到 :487-488 —— 结果与「只有 `useSetColorStyle` 为假」
     * 相同（都是默认前景），但字型位与字体名**同样**回默认，这是两轴的差别所在。
     */
    @Test
    fun `写假之后整条回默认样式且优先于取色轴`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.print("A")

        s.setUseUserStyle(false)
        s.print("B")

        s.setUseSetColorStyle(false) // D 系窗口：两条轴同时为假
        s.print("C")
        s.newLine()

        val segs = s.snapshot().single().segments
        assertEquals(listOf("A", "BC"), segs.map { it.text }, "B 与 C 同样式（都是 defaultStyle）⇒ 并成一段")
        assertEquals(0xFF0000, segs[0].foreColor)
        assertEquals(1, segs[0].styleMask)
        assertEquals("Saitamaar", segs[0].fontName)
        for (i in 1..segs.lastIndex) {
            assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, segs[i].foreColor, "第 $i 段：默认前景")
            assertEquals(0, segs[i].styleMask, "第 $i 段：字型位回 Regular（只有 UseUserStyle 能压这一项）")
            assertEquals("黑体", segs[i].fontName, "第 $i 段：字体名回默认（只有 UseUserStyle 能压这一项）")
        }
    }

    /**
     * 写假只作用于**此后入缓冲的段**，已排好的显示行不回溯改写。
     *
     * 样式快照在入缓冲时定死（`ConsoleStyledString.cs:26-43`），`Style` 只在 Append 那一刻被读。
     */
    @Test
    fun `写假不回溯改写已提交的显示行`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.printLine("已提交")

        s.setUseUserStyle(false)
        s.printLine("之后")

        val lines = s.snapshot()
        assertEquals(0xFF0000, lines[0].segments.single().foreColor, "先提交的行保留当时的快照")
        assertEquals(
            LineBufferConsoleSink.DEFAULT_FORE_COLOR,
            lines[1].segments.single().foreColor,
            "写假之后的行取 defaultStyle",
        )
    }

    // ==================================================================
    // 2. printError：先 flush 再写假（EmueraConsole.cs:3107-3110）
    // ==================================================================

    /**
     * 顺序是**先 `PrintFlush`、再 `UseUserStyle = false`**（`EmueraConsole.cs:3107-3110`）：
     * 已排进缓冲的段按旧样式结掉，只有此后入缓冲的内容才回默认样式。
     *
     * 反证：若先写假再 flush，`pending` 那一段会在 flush 时被重新取样式而掉成默认色 ——
     * 本用例的 `0xFF0000` 断言就会红。
     */
    @Test
    fun `错误行先 flush 待决缓冲再写假`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.print("pending", lineEnd = false)
        s.printError("E")

        val lines = s.snapshot()
        // 待决行没有 lineEnd ⇒ addDisplayLine 把错误行并进同一显示行
        // （EmueraConsole.cs:2891-2990 的「末行未结则并」分支），故这里是一条显示行两段。
        assertEquals(1, lines.size, "待决行未断行 ⇒ 错误行并进同一条显示行")
        val segs = lines.single().segments
        assertEquals(listOf("pending", "E"), segs.map { it.text })
        assertEquals(0xFF0000, segs[0].foreColor, "flush 早于写假 ⇒ 保留用户色")
        assertEquals(LineBufferConsoleSink.Kind.ERROR, segs[1].kind)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, segs[1].foreColor)
        assertEquals(0, segs[1].styleMask)
        assertEquals("黑体", segs[1].fontName)
    }

    /**
     * 粘滞（一）：错误行之后直接调 sink 打印（不经 PRINT 语句）仍是默认样式。
     *
     * 原版 `PrintError` 只写假、不写回（:3108），下一条 PRINT 系语句才会写真 —— 所以
     * 「错误行后面的输出突然恢复用户色」是移植侧**擅自还原**的特征。
     */
    @Test
    fun `错误行之后不经 PRINT 的打印仍是默认样式`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.printError("E")

        // 模拟 PrintSystemLine 那一路：写假 → print → newLine（:3092-3097），
        // 中间不经过任何 PRINT 系语句。
        s.setUseUserStyle(false)
        s.print("sys")
        s.newLine()

        val lines = s.snapshot()
        // 错误行本体：强制左对齐 + 不折行（EmueraConsole.cs:3113 force_LEFT、
        // PrintPlainwithSingleLine → FlushSingleLine(nobr)，PrintStringBuffer.cs:170-177）。
        assertEquals(Alignment.LEFT, lines[0].alignment, "PrintError 强制左对齐")
        assertTrue(lines[0].noBreak, "错误行不折行")
        assertEquals(LineBufferConsoleSink.Kind.ERROR, lines[0].segments.single().kind)

        val seg = lines.last().segments.single()
        assertEquals("sys", seg.text)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor, "粘滞：没有配对写回（:3108 之后无 true）")
        assertEquals(0, seg.styleMask)
        assertEquals("黑体", seg.fontName)
    }

    /**
     * 粘滞（二）：错误行之后的 `PRINTSINGLE` 仍是默认样式。
     *
     * `PRINTSINGLE` 走 `ExpressionMediator.cs:68`，那里**只**复位 `UseSetColorStyle`，
     * 不碰 `UseUserStyle`（它不在写真五处里）⇒ 原版这条语句打印出来也是默认样式。
     * 本仓若在 `printSingleLine` 里顺手写真，此用例必红。
     */
    @Test
    fun `错误行之后的 PRINTSINGLE 仍是默认样式`() {
        val s = sink()
        s.setUseUserStyle(true)
        dirty(s)
        s.printError("E")
        s.setUseSetColorStyle(true) // ExpressionMediator.cs:68 只做这一句
        s.printSingleLine("single")

        val seg = s.snapshot().last().segments.single()
        assertEquals("single", seg.text)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor)
        assertEquals(0, seg.styleMask)
        assertEquals("黑体", seg.fontName)
    }

    // ==================================================================
    // 3. 语句级：写真五处（求值之前）
    // ==================================================================

    private val parser = LineParser(SymbolTable())

    private fun stmt(text: String): Statement =
        requireNotNull(parser.parseLogicalLine(LogicalLine(LineKind.CODE, "uus.erb", 1, text))) {
            "行解析失败：$text"
        }

    private class Env {
        val store = VariableStore(randProvider = RandProvider { 0L })
        val console = LineBufferConsoleSink(clientWidth = 80, defaultFontName = "黑体")
        val executor = StatementExecutor()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
        )
    }

    private fun run(env: Env, text: String) {
        env.executor.execute(stmt(text), env.ctx)
    }

    /** 让 sink 处于「上一条错误行/系统行把开关写假了」的状态。 */
    private fun preError(env: Env) {
        env.console.setForeColor(255, 0, 0)
        env.console.setFontStyle(1)
        env.console.setFont("Saitamaar")
        env.console.setUseUserStyle(false)
    }

    /**
     * PRINT 族：`UseUserStyle = true` 在求值之前（`FunctionIdentifier.cs:179`）。
     *
     * 这是本路修复的主判据 —— 语句层若漏掉这一句，错误行之后的第一条 PRINT 仍会是默认样式
     * （`ConsoleSink.setUseUserStyle` 的 KDoc 里「写真五处」第一条）。
     */
    @Test
    fun `PRINT 在求值前把 UseUserStyle 写回真`() {
        val env = Env()
        preError(env)
        run(env, "PRINT 甲")
        env.console.newLine() // PRINT 不加换行，显示行要等 flush 才成形

        val seg = env.console.snapshot().last().segments.single()
        assertEquals("甲", seg.text)
        assertEquals(0xFF0000, seg.foreColor, "错误行之后的第一条 PRINT 恢复用户色")
        assertEquals(1, seg.styleMask)
        assertEquals("Saitamaar", seg.fontName)
    }

    /**
     * PRINTPLAIN/FORM：两条开关都在求值前写回真（`Process.cs:1149-1150`），
     * 顺带清掉可能泄漏的 D 系窗口。
     */
    @Test
    fun `PRINTPLAIN 在求值前把两条开关都写回真`() {
        val env = Env()
        preError(env)
        env.console.setUseSetColorStyle(false) // 上一条 PRINTD 泄漏下来的 D 窗口
        run(env, "PRINTPLAIN \"丙\"")
        env.console.newLine()

        val seg = env.console.snapshot().last().segments.single()
        assertEquals(LineBufferConsoleSink.Kind.PLAIN, seg.kind)
        assertEquals(0xFF0000, seg.foreColor, "PRINTPLAIN 不吃 D 配色回落")
        assertEquals(1, seg.styleMask)
        assertEquals("Saitamaar", seg.fontName)
    }

    /**
     * PRINTBUTTON 族：`Process.cs:1110-1111` / `:1129-1130`，两条开关都写回真，
     * 按钮因此是「系统行之后第一条恢复用户样式的语句」之一。
     */
    @Test
    fun `PRINTBUTTON 在求值前把两条开关都写回真`() {
        val env = Env()
        preError(env)
        env.console.setUseSetColorStyle(false)
        run(env, "PRINTBUTTON \"丁\", 7")
        env.console.newLine()

        val line = env.console.snapshot().last()
        val seg = line.segments.single()
        assertEquals(LineBufferConsoleSink.Kind.BUTTON, seg.kind)
        assertEquals(0xFF0000, seg.foreColor)
        assertEquals(1, seg.styleMask)
        assertEquals("Saitamaar", seg.fontName)
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 2, 7L)), line.buttons)
    }

    /**
     * PRINTD：窗口内前景回落默认，但字型位与字体名**保留**（`EmueraConsole.cs:487-488`）。
     *
     * 与第 1 组「写假」用例对照：两条轴都压前景，只有 `UseUserStyle` 能压字型位与字体名。
     */
    @Test
    fun `PRINTD 的 D 窗口只压前景不压字型与字体名`() {
        val env = Env()
        preError(env)
        run(env, "PRINTD 乙")
        env.console.newLine()

        val seg = env.console.snapshot().last().segments.single()
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, seg.foreColor, "D 窗口回落默认前景")
        assertEquals(1, seg.styleMask, "只动前景色（:487-488 保留 userStyle.FontStyle）")
        assertEquals("Saitamaar", seg.fontName, "只动前景色（:487-488 保留 userStyle.Fontname）")
    }

    /**
     * PRINTDATA 的空数据节：`return` 只跳过 `:317` 的取色复位，**不回滚** `:276` 写下的
     * `UseUserStyle = true`（`FunctionIdentifier.cs:278-282` 早退在 :276 之后）。
     *
     * 观察方式：空组早退后不经任何 PRINT 语句直接打印，若开关确实已被写回真，
     * 这一段就取用户样式；若实现把写真放到「拿到数据组之后」，此用例必红。
     */
    @Test
    fun `PRINTDATA 空数据节早退仍已把 UseUserStyle 写回真`() {
        val env = Env()
        preError(env)
        val noGroups = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            dataSections = dualKeySections { emptyList() },
        )
        env.executor.execute(stmt("PRINTDATA"), noGroups)

        // 直接驱动 sink（不经 PRINT 语句），只看开关状态。
        env.console.print("after")
        env.console.newLine()

        val seg = env.console.snapshot().last().segments.single()
        assertEquals("after", seg.text)
        assertEquals(0xFF0000, seg.foreColor, ":276 写真发生在数据组查找之前")
        assertEquals(1, seg.styleMask)
        assertEquals("Saitamaar", seg.fontName)
    }

    /** PRINTDATA 有数据组：正文取用户样式（`FunctionIdentifier.cs:276` 写真 → 正文打印）。 */
    @Test
    fun `PRINTDATA 正文取用户样式`() {
        val env = Env()
        preError(env)
        val withGroups = ExecContext(
            variables = env.store,
            eval = TestEvalContext(env.store),
            console = env.console,
            dataSections = dualKeySections { listOf(listOf(StrLiteral("数据"))) },
        )
        env.executor.execute(stmt("PRINTDATA"), withGroups)
        env.console.newLine()

        val seg = env.console.snapshot().last().segments.single()
        assertEquals("数据", seg.text)
        assertEquals(0xFF0000, seg.foreColor)
        assertEquals(1, seg.styleMask)
    }
}
