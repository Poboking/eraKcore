package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.scroll.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ConsoleAdapter] 的映射判据（M4-ADAPT）。
 *
 * 期望值全部由 C# 参考实现手算（`EmueraConsole.cs` / `PrintStringBuffer.cs` /
 * `ConsoleDisplayLine.cs` / `ConsoleButtonString.cs`），核心层夹具用真实的
 * `LineBufferConsoleSink` 跑出来，不回读本类实现。
 * 宽度一律 [Grid.cells]（半角 1 / 全角 2，spec: `EmueraConsole.cs:3255`）。
 */
class ConsoleAdapterTest {

    /**
     * 生产口径 sink，并开 `UseUserStyle` 轴。
     *
     * 原版该字段出厂 false（EmueraConsole.cs:467 的 `bool` 默认值），只有 PRINT 系语句
     * 在求值前写真（FunctionIdentifier.cs:179/:276、Process.cs:1110/:1129/:1149）。
     * 本类用例直接改样式再打印，等价于「站在 PRINT 语句里」；不开轴则 Style 取值器
     * 恒走 `!UseUserStyle → defaultStyle`（:471-472），「样式变化处切成两段」这类
     * 夹具自检会直接不成立。
     */
    private fun sink(width: Int = 80) =
        LineBufferConsoleSink(clientWidth = width).also { it.setUseUserStyle(true) }

    private fun textOf(line: LineBufferConsoleSink.DisplayLine): String = line.segments.joinToString("") { it.text }

    private fun cellsOf(line: LineBufferConsoleSink.DisplayLine): Int = line.segments.sumOf { Grid.cells(it.text) }

    /** 手工构造的按钮 run（源按钮编号显式给出，用于钉条数口径与并入时的编号抬升）。 */
    private fun buttonRun(text: String, value: Any?, id: Int = -1) =
        TextRun(text, 0xC0C0C0, 0x000000, 0, "黑体", RunKind.BUTTON, value, buttonId = id)

    // ------------------------------------------------------------------
    // 接线①：Segment → TextRun 全字段搬运，样式不重解析
    // ------------------------------------------------------------------

    @Test
    fun `段的全字段逐项照抄且不在适配层重新解析颜色与字体`() {
        val s = sink()
        s.setForeColor(255, 128, 0)
        s.setFontStyle(3)
        s.setFont("MyFont")
        s.print("A")
        s.setForeColor(0, 0, 255)
        s.setFontStyle(8)
        s.print("B")
        s.newLine()

        val line = s.snapshot().single()
        assertEquals(2, line.segments.size, "夹具自检：样式变化处必须切成两段")
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(2, rl.runs.size)
        for (i in line.segments.indices) {
            val seg = line.segments[i]
            val r = rl.runs[i]
            // 颜色/样式/字体是入缓冲时的快照（ConsoleStyledString.cs:26-43），适配层只搬运。
            assertEquals(seg.text, r.text, "第 $i 段文本")
            assertEquals(seg.foreColor, r.fore, "第 $i 段前景色不得再解析")
            assertEquals(seg.styleMask, r.styleMask, "第 $i 段样式位")
            assertEquals(seg.fontName, r.fontName, "第 $i 段字体名")
            assertEquals(RunKind.valueOf(seg.kind.name), r.kind, "第 $i 段种类同名对应")
            assertEquals(seg.htmlOption, r.htmlOption, "第 $i 段 HTML 选项")
            // 背景色是控制台全局色，核心层按行快照；非按钮 run 的 back 直接取它。
            assertEquals(line.backColor, r.back, "第 $i 段是非按钮段，背景色取行快照")
        }
        assertEquals(0xFF8000, rl.runs[0].fore)
        assertEquals(3, rl.runs[0].styleMask)
        assertEquals("MyFont", rl.runs[0].fontName)
        assertEquals(0x0000FF, rl.runs[1].fore)
        assertEquals(8, rl.runs[1].styleMask)
    }

    /**
     * 按钮 run 的 `back` 槽承载原版 `StringStyle.ButtonColor`，缺省 = `Config.FocusColor`
     * `0xFFFF00`（`StringStyle.cs:11,22`、`ConfigData.cs:70`）—— 绘制侧焦点命中时正是从
     * 这一槽取字色（`view/ScreenComposer.glyphFore` 对 `ConsoleStyledString.cs:105-111`）。
     * 非按钮 run 的 `back` 仍是 SETBGCOLOR 的行快照，两种语义不能混成一个。
     *
     * 未注入时用 [LineBufferConsoleSink.DEFAULT_FOCUS_COLOR]；真机可经 `focusColor` 注入
     * `emuera.config` 的 FOCUSCOLOR（同一段里顺带钉住注入口）。
     */
    @Test
    fun `按钮 run 的 back 槽承载焦点色而非行背景色`() {
        val s = sink()
        s.setBackColor(16, 32, 48)
        s.print("ab")
        s.printButton("BTN", 7L)
        s.newLine()

        val line = s.snapshot().single()
        assertEquals(0x102030, line.backColor, "夹具自检：SETBGCOLOR 落进行快照")
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf(RunKind.TEXT, RunKind.BUTTON), rl.runs.map { it.kind })
        assertEquals(line.backColor, rl.runs[0].back, "非按钮 run 的 back 仍是行背景色")
        assertEquals(
            LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
            rl.runs[1].back,
            "按钮 run 的 back 槽承载 Config.FocusColor 缺省（StringStyle.cs:22、ConfigData.cs:70）",
        )
        assertEquals(0xFFFF00, rl.runs[1].back)

        // 焦点色是配置项（原版 emuera.config 的 FOCUSCOLOR），适配层不把缺省写死在函数体。
        val injected = ConsoleAdapter.adapt(line, focusColor = 0x00FF00)
        assertEquals(0x00FF00, injected.runs[1].back, "注入的焦点色必须覆盖缺省")
        assertEquals(line.backColor, injected.runs[0].back, "注入焦点色不影响非按钮段")
    }

    @Test
    fun `HTML 段保留原文不剥标签`() {
        val s = sink()
        s.printHtml("<b>x</b>", true)
        s.newLine()

        val line = s.snapshot().single()
        assertEquals("<b>x</b>", textOf(line), "夹具自检：核心层不剥标签")
        val r = ConsoleAdapter.adapt(line).runs.single()
        assertEquals("<b>x</b>", r.text, "适配层同样不解析 HTML")
        assertEquals(RunKind.HTML, r.kind)
        assertTrue(r.htmlOption, "toPrintBuffer 标志随段快照（EmueraConsole.cs:3203-3227）")
    }

    @Test
    fun `isLogicalLine 与 isLineEnd 与临时行标记原样透传`() {
        val s = sink(width = 10)
        s.print("ああああああ", false) // 6 个全角 = 12 格 > 10 → 折成 5 + 1 行，且整体未断行
        s.newLine()

        val lines = s.snapshot()
        assertEquals(2, lines.size, "夹具自检：折行出两条显示行")
        val adapted = ConsoleAdapter.adapt(lines)
        // 折行首段带 IsLogicalLine（PrintStringBuffer.cs:193,206），续行不带。
        assertEquals(listOf(true, false), adapted.map { it.isLogicalLine })
        // PRINTN 的 lineEnd=false 只改写末段（PrintStringBuffer.cs:183），两段都未断行。
        assertEquals(listOf(false, false), adapted.map { it.isLineEnd })
        assertEquals(listOf(false, false), adapted.map { it.temporary })

        val s2 = sink()
        s2.print("base")
        s2.newLine()
        s2.printTemporaryLine("t1") // EmueraConsole.cs:3053-3056 → PrintSingleLine(temporary:true)
        assertEquals(listOf(false, true), ConsoleAdapter.adapt(s2.snapshot()).map { it.temporary })
    }

    @Test
    fun `PRINTC 的定宽补齐就是文本的一部分不被适配层改动`() {
        // PRINTC 的补齐在核心层按 Shift-JIS 字节数完成（EmueraConsole.cs:3247-3291），
        // 适配层只搬运；ALIGNMENT CENTER/RIGHT 的整行平移另见 alignmentShift。
        val s = LineBufferConsoleSink(clientWidth = 100, printCWidth = 10)
        s.printC("ab", true)
        s.newLine()
        val r = ConsoleAdapter.adapt(s.snapshot().single()).runs.single()
        assertEquals("        ab", r.text)
        assertEquals(10, Grid.cells(r.text), "8 个半角空格 + ab = 10 格")
    }

    // ------------------------------------------------------------------
    // 接线②：空行先塞一个空格再折行（EmueraConsole.cs:3379-3382）
    // ------------------------------------------------------------------

    @Test
    fun `零内容行由适配层补一个空格而不是交给折行器`() {
        // LineFolder 对零内容返回空表（PrintStringBuffer.cs:198-201），所以这条必须适配层做。
        val empty = RenderLine(runs = emptyList(), isLogicalLine = true, isLineEnd = true)
        assertEquals(0, LineFolder(80).fold(empty).size, "夹具自检：折行器不造空行")

        val line = LineBufferConsoleSink.DisplayLine(isLineEnd = true, segments = emptyList(), isLogicalLine = true)
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(1, rl.runs.size)
        assertEquals(" ", rl.runs.single().text)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, rl.runs.single().fore, "无段可取时用 Config.ForeColor 默认（ConfigData.cs:68）")
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, rl.runs.single().fontName, "Config.FontName 默认（ConfigData.cs:65）")
        val rows = ConsoleAdapter.fold(LineFolder(80), listOf(rl))
        assertEquals(1, rows.size, "补出的空格占一行")
        assertEquals(1, rows.single().cells)
    }

    @Test
    fun `全空文本段同样补成单空格且样式取第一段`() {
        val seg = LineBufferConsoleSink.Segment("", 0xFF0000, 5, "First")
        val line = LineBufferConsoleSink.DisplayLine(isLineEnd = true, segments = listOf(seg), isLogicalLine = true)
        val r = ConsoleAdapter.adapt(line).runs.single()
        assertEquals(" ", r.text)
        assertEquals(0xFF0000, r.fore, "第一段样式快照沿用")
        assertEquals(5, r.styleMask)
        assertEquals("First", r.fontName)
        assertEquals(1, Grid.cells(r.text))
    }

    @Test
    fun `核心层已补空格的空行不被二次补成两个空格`() {
        val s = sink()
        s.newLine() // 空缓冲 force flush → Append(" ", Style)（EmueraConsole.cs:3379-3382）
        assertEquals(" ", textOf(s.snapshot().single()), "夹具自检")
        val rows = ConsoleAdapter.fold(LineFolder(80), ConsoleAdapter.adapt(s.snapshot()))
        assertEquals(1, rows.size)
        assertEquals(" ", rows.single().runs.single().text)
        assertEquals(1, rows.single().cells)
    }

    // ------------------------------------------------------------------
    // 接线③：AppendButton 并段口径（PrintStringBuffer.cs:103-123）
    // ------------------------------------------------------------------

    @Test
    fun `被并成一个段的相邻按钮切回一按钮一条 run 且命中区各自等于文本宽`() {
        val s = sink()
        s.printButton("A", 1L)
        s.printButton("BC", "two")
        s.newLine()

        val line = s.snapshot().single()
        assertEquals(1, line.segments.size, "夹具自检：核心层把样式相同的相邻按钮并成一段")
        assertEquals("ABC", textOf(line))
        assertEquals(
            listOf(
                LineBufferConsoleSink.Button(0, 1, 1L),
                LineBufferConsoleSink.Button(1, 3, "two"),
            ),
            line.buttons,
        )

        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("A", "BC"), rl.runs.map { it.text }, "按钮段必须切回一按钮一条 run")
        assertEquals(listOf(RunKind.BUTTON, RunKind.BUTTON), rl.runs.map { it.kind })
        assertEquals(listOf<Any?>(1L, "two"), rl.runs.map { it.buttonValue })

        // 命中区由 ButtonLayer 从 run 文本重算：区间宽度 = 各自文本的格数，不是整段宽度。
        val layer = ButtonLayer()
        val aligned = ConsoleAdapter.fold(LineFolder(80), listOf(rl))
        layer.attach(aligned)
        assertEquals(listOf(HitTarget(0, 0, 1, 1L), HitTarget(0, 1, 3, "two")), layer.all())
        assertEquals(layer.all(), ConsoleAdapter.hitTargets(aligned), "门面只委托 ButtonLayer，不另算列")
        assertEquals(1L, layer.hit(0, 0)?.value)
        assertEquals("two", layer.hit(0, 1)?.value, "第二按钮的首格不得算进第一个按钮")
    }

    @Test
    fun `按钮夹在文本之间时文本段与按钮段各自独立`() {
        val s = sink()
        s.print("ab")
        s.printButton("C", 7L)
        s.print("de")
        s.newLine()

        val rl = ConsoleAdapter.adapt(s.snapshot().single())
        assertEquals(listOf("ab", "C", "de"), rl.runs.map { it.text })
        assertEquals(listOf(RunKind.TEXT, RunKind.BUTTON, RunKind.TEXT), rl.runs.map { it.kind })
        assertEquals(listOf<Any?>(null, 7L, null), rl.runs.map { it.buttonValue })

        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 2, 3, 7L)), layer.all())
    }

    @Test
    fun `跨行按钮在每一行各一条 run 且值相同`() {
        val s = sink(width = 10)
        s.print("aaaaaaaaa") // 9 格
        s.printButton("abc", 7L)
        s.newLine()

        val lines = s.snapshot()
        assertEquals(2, lines.size, "夹具自检：按钮跨行被核心层拆开")
        assertEquals(listOf("aaaaaaaaaa", "bc"), lines.map { textOf(it) })
        assertEquals(listOf(10, 2), lines.map { cellsOf(it) })

        val rows = ConsoleAdapter.fold(LineFolder(10), ConsoleAdapter.adapt(lines))
        assertEquals(2, rows.size, "核心层已按 10 格折行，按 Grid 复核同样不折")
        assertEquals(listOf("aaaaaaaaa", "a"), rows[0].runs.map { it.text })
        assertEquals(listOf(RunKind.TEXT, RunKind.BUTTON), rows[0].runs.map { it.kind })
        assertEquals(listOf("bc"), rows[1].runs.map { it.text })
        assertEquals(listOf<Any?>(7L), rows[1].runs.map { it.buttonValue })

        val layer = ButtonLayer()
        layer.attach(rows)
        assertEquals(
            listOf(HitTarget(0, 9, 10, 7L), HitTarget(1, 0, 2, 7L)),
            layer.all(),
            "跨行按钮每行各一条命中区（ConsoleButtonString.cs:247-251）",
        )
    }

    @Test
    fun `按钮区间用尽后的孤儿文本不带值也不进命中表`() {
        // 段文本 3 格却只给了 1 格宽的列区间（两套等宽表分歧的极端形）：剩下的文本仍按
        // BUTTON 搬运但不带值 —— ButtonLayer 对 buttonValue == null 不入表
        // （spec: ButtonLayer.kt:74-88），宁可不命中也不错命中。
        val seg = LineBufferConsoleSink.Segment("ABC", 0xC0C0C0, 0, "f", LineBufferConsoleSink.Kind.BUTTON)
        val line = LineBufferConsoleSink.DisplayLine(
            isLineEnd = true,
            segments = listOf(seg),
            buttons = listOf(LineBufferConsoleSink.Button(0, 1, 1L)),
            isLogicalLine = true,
        )
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("A", "BC"), rl.runs.map { it.text })
        assertEquals(listOf<Any?>(1L, null), rl.runs.map { it.buttonValue })

        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 0, 1, 1L)), layer.all())
    }

    // ------------------------------------------------------------------
    // 接线③b：自动按钮（普通文本里的数字方括号）
    // ------------------------------------------------------------------

    /**
     * 单个数字：`ButtonStringCreator.cs:63-73` 的 `num <= 1` 分支把**整串**做成一条可选段
     * （`CanSelect = num >= 1`），所以 `按[123]确认` 从第 0 格到末格都可点，命中值是 123。
     * 出处链：`PrintStringBuffer.cs:280-292` 的 `fromCssToButton` → `:326-345` 的 `createButtons`
     * → `ButtonStringCreator.SplitButton`（`:24-27`）；本仓在 `core/host/ButtonStringCreator`
     * 落地，核心层给出 `Kind.BUTTON` 段 + `buttons` 区间。
     */
    @Test
    fun `单个数字方括号把整行变成自动按钮`() {
        val s = sink(width = 80)
        s.print("按[123]确认")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(1, line.segments.size, "样式相同 ⇒ 一条 BUTTON 段")
        assertEquals(listOf(LineBufferConsoleSink.Button(0, 11, 123L)), line.buttons, "2 + 5 + 4 = 11 格")
        assertEquals(RunKind.BUTTON, ConsoleAdapter.adapt(line).runs.single().kind)
        assertEquals(123L, ConsoleAdapter.adapt(line).runs.single().buttonValue)

        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), ConsoleAdapter.adapt(s.snapshot())))
        assertEquals(listOf(HitTarget(0, 0, 11, 123L)), layer.all(), "整行可点")
        assertEquals(123L, layer.hit(0, 0)?.value)
        assertEquals(123L, layer.hit(0, 10)?.value)
        assertEquals("123", layer.click(0, 5), "数值按钮回环给十进制串（ConsoleButtonString.cs:85）")
    }

    /**
     * 两个数字：前后正文按 `flag3/flag4/flag5` 粘到相邻按钮（`ButtonStringCreator.cs:74-135`）。
     * `红[1]蓝[2]` 是「有前导正文、无尾部正文」（flag4）⇒ 每段正文粘在**它前面那个**按钮上。
     */
    @Test
    fun `两个数字方括号按正文位置切成两条自动按钮`() {
        val s = sink(width = 80)
        s.print("红[1]蓝[2]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(listOf("红[1]蓝[2]"), line.segments.map { it.text }, "同样式相邻按钮并成一条 Segment")
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 5, 1L), LineBufferConsoleSink.Button(5, 10, 2L)),
            line.buttons,
            "2+3 与 2+3",
        )
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("红[1]", "蓝[2]"), rl.runs.map { it.text }, "适配层按 buttons 区间切回一按钮一 run")
        assertEquals(listOf<Any?>(1L, 2L), rl.runs.map { it.buttonValue })
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 0, 5, 1L), HitTarget(0, 5, 10, 2L)), layer.all())
        assertEquals(1L, layer.hit(0, 0)?.value)
        assertEquals(2L, layer.hit(0, 5)?.value)
        assertEquals("2", layer.click(0, 9))
    }

    /** 尾部正文（flag3）：粘在**最后一个**按钮上（`ButtonStringCreator.cs:104-111` 的 reduce 顺序）。 */
    @Test
    fun `最后一个数字之后的正文粘在最后一条自动按钮上`() {
        val s = sink(width = 80)
        s.print("红[1]确认[2]完毕")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 9, 1L), LineBufferConsoleSink.Button(9, 16, 2L)),
            line.buttons,
            "2+3+4 与 3+4",
        )
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("红[1]确认", "[2]完毕"), rl.runs.map { it.text })
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 0, 9, 1L), HitTarget(0, 9, 16, 2L)), layer.all())
        assertEquals("1", layer.click(0, 8))
        assertEquals("2", layer.click(0, 9))
    }

    /** 非数字方括号与落单括号都不是按钮；PRINTPLAIN 一律不切（`createPlainButton`，`:125-134`）。 */
    @Test
    fun `自动按钮只认数字方括号且不碰 PRINTPLAIN`() {
        val s = sink(width = 80)
        s.print("[abc]")
        s.newLine()
        s.print("半截[12")
        s.newLine()
        s.printPlain("按[9]继续")
        s.newLine()
        val lines = s.snapshot()
        assertEquals(listOf("[abc]", "半截[12", "按[9]继续"), lines.map { textOf(it) }, "文本一字不改")
        assertTrue(lines.all { it.buttons.isEmpty() }, "非法方括号与 PLAIN 都不产按钮")
        assertEquals(listOf(RunKind.TEXT, RunKind.TEXT, RunKind.PLAIN), lines.flatMap { ConsoleAdapter.adapt(it).runs }.map { it.kind })
    }

    /**
     * 自动按钮比一行还宽时按字符折行，**每行各一条**命中区且值相同 ——
     * 原版 `ConsoleButtonString.DivideAt` 把 `IsButton`/`Input` 一起抄给拆分出来的那半
     * （`ConsoleButtonString.cs:240-251`）。夹具 `buttonWrap` 取缺省 false（拆）。
     */
    @Test
    fun `跨行的自动按钮每行各一条命中区`() {
        val s = sink(width = 4)
        s.print("ab[12345]")
        s.newLine()
        val lines = s.snapshot()
        assertEquals(listOf("ab[1", "2345", "]"), lines.map { textOf(it) })
        val rows = ConsoleAdapter.fold(LineFolder(4), ConsoleAdapter.adapt(lines))
        val layer = ButtonLayer()
        layer.attach(rows)
        assertEquals(
            listOf(HitTarget(0, 0, 4, 12345L), HitTarget(1, 0, 4, 12345L), HitTarget(2, 0, 1, 12345L)),
            layer.all(),
        )
    }

    /**
     * 跨样式段：原版一条 `ConsoleButtonString` 的 `StrArray` 可以装多个样式节点
     * （`ConsoleButtonString.cs:19,296-299`），本仓在渲染层按样式切成两条 run，
     * 但**命中表仍只有一条**（列号相接、值相同、**源按钮编号相同** ⇒ `ButtonLayer.attach` 合并）。
     */
    @Test
    fun `跨样式段的自动按钮仍是一条命中区`() {
        val s = sink(width = 80)
        s.setForeColor(255, 0, 0)
        s.print("按[12")
        s.setForeColor(0, 255, 0)
        s.print("3]确认")
        s.newLine()
        val rl = ConsoleAdapter.adapt(s.snapshot().single())
        assertEquals(listOf("按[12", "3]确认"), rl.runs.map { it.text })
        assertEquals(listOf(0xFF0000, 0x00FF00), rl.runs.map { it.fore })
        assertEquals(listOf<Any?>(123L, 123L), rl.runs.map { it.buttonValue })
        assertEquals(listOf(0, 0), rl.runs.map { it.buttonId }, "同一条源按钮：编号 = buttons 下标 0")
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 0, 11, 123L)), layer.all(), "5 + 6 = 11 格")
        assertEquals("123", layer.click(0, 10))
        assertEquals(123L, layer.hit(0, 0)?.value, "首格也算同一条按钮")
    }

    /**
     * 两个相邻**同值**显式 PRINTBUTTON：原版是两条 `ConsoleButtonString`
     * （`PrintStringBuffer.cs:103-123` 每次 `AppendButton` 都 `fromCssToButton` 后 `createButton`），
     * `ConsoleDisplayLine.Buttons` 长度 2（`ConsoleDisplayLine.cs:18,28`）⇒ 命中表两条，
     * 即使值和列号都相接。
     */
    @Test
    fun `两个相邻同值显式按钮在适配层保持两条命中区`() {
        val s = sink(width = 80)
        s.printButton("A", 7L)
        s.printButton("B", 7L)
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 1, 7L), LineBufferConsoleSink.Button(1, 2, 7L)),
            line.buttons,
            "夹具自检：核心层给两条区间",
        )
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("A", "B"), rl.runs.map { it.text })
        assertEquals(listOf(0, 1), rl.runs.map { it.buttonId }, "源按钮编号 = buttons 下标，必须不同")
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(
            listOf(HitTarget(0, 0, 1, 7L), HitTarget(0, 1, 2, 7L)),
            layer.all(),
            "值相同、列号相接也不是同一条",
        )
        assertEquals("7", layer.click(0, 0))
        assertEquals("7", layer.click(0, 1))
    }

    // ------------------------------------------------------------------
    // nobr 透传：DisplayLine.noBreak → RenderLine.noBreak → LineFolder.fold(noBr = true)
    //   原版 nobr 是 Flush 的实参（PrintStringBuffer.cs:173 vs :182），折行判定读它（:217）；
    //   本仓折行在渲染层再做一次，所以这个事实必须随行带过来。
    // ------------------------------------------------------------------

    /**
     * PRINTSINGLE / REUSELASTLINE / 错误行：`noBreak = true` 必须一路活到折行器，
     * 超宽也**只有一行**（原版 `nobr` 分支一行不折，`PrintStringBuffer.cs:217`；
     * 调用点 `EmueraConsole.cs:3147-3165`、`:3053-3056`、`:3098-3115`）。
     */
    @Test
    fun `PRINTSINGLE 与临时行与错误行的 noBreak 活到折行器`() {
        val single = sink(width = 5)
        single.printSingleLine("abcdefgh")
        val rl = ConsoleAdapter.adapt(single.snapshot().single())
        assertTrue(rl.noBreak, "DisplayLine.noBreak → RenderLine.noBreak")
        val rows = ConsoleAdapter.fold(LineFolder(5), listOf(rl))
        assertEquals(1, rows.size, "nobr ⇒ 渲染层不得二次折行")
        assertEquals(8, rows.single().cells, "超宽行按原版画到右沿外")

        val temporary = sink(width = 5)
        temporary.printTemporaryLine("abcdefgh")
        val tempRows = ConsoleAdapter.fold(LineFolder(5), ConsoleAdapter.adapt(temporary.snapshot()))
        assertEquals(1, tempRows.size)
        assertTrue(tempRows.single().temporary)
        assertEquals(8, tempRows.single().cells)

        val error = sink(width = 5)
        error.printError("abcdefgh")
        val errRows = ConsoleAdapter.fold(LineFolder(5), ConsoleAdapter.adapt(error.snapshot()))
        assertEquals(1, errRows.size, "PrintPlainwithSingleLine → FlushSingleLine(nobr: true)")
        assertEquals(RunKind.ERROR, errRows.single().runs.single().kind)

        // 对照：普通 PRINT 折行两行（nobr=false 的 Flush，PrintStringBuffer.cs:182）。
        val normal = sink(width = 5)
        normal.print("abcdefgh")
        normal.newLine()
        assertEquals(2, ConsoleAdapter.fold(LineFolder(5), ConsoleAdapter.adapt(normal.snapshot())).size)
    }

    /** 独立 HTML 行不带 noBreak（TODO(verify)：HTML 的 `<nobr>` 标签未解析）。 */
    @Test
    fun `独立 HTML 行不继承 noBreak`() {
        val s = sink(width = 5)
        s.printHtml("<b>x</b>", false)
        assertTrue(!ConsoleAdapter.adapt(s.snapshot().single()).noBreak)
    }

    /**
     * 跨 HTML 段的自动按钮：核心层把整组拼串后切片（`PrintStringBuffer.cs:326-345`），
     * HTML 段被可选片吞掉后是 [RunKind.BUTTON] 的可见文本片，三片同属一个源按钮
     * ⇒ 适配层切回三条 run（样式/文本各自独立），命中表仍只有**一条**。
     */
    @Test
    fun `跨 HTML 段的自动按钮在适配层是一条命中区`() {
        val s = sink(width = 80)
        s.print("按[1")
        s.printHtml("<b>2</b>", true)
        s.print("]")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals("按[12]", textOf(line), "夹具自检：HTML 按可见文本参与拼串")
        val rl = ConsoleAdapter.adapt(line)
        assertEquals(listOf("按[12]"), rl.runs.map { it.text }, "同样式并成一条 Segment")
        assertEquals(listOf(0), rl.runs.map { it.buttonId })
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(listOf(HitTarget(0, 0, 6, 12L)), layer.all(), "2+1+1+1+1 = 6 格")
        assertEquals("12", layer.click(0, 5))
    }

    /**
     * PRINTN 续行并入（`EmueraConsole.cs:2947-2970`）会把两条行的 run 拼成一条：
     * 源按钮编号只保证**行内**唯一，拼接时必须整体抬起，否则「前一行末按钮」与
     * 「后一行首按钮」撞号、值相同且列号相接时会被 `ButtonLayer` 并成一条 ——
     * 而原版是两条独立 `ConsoleButtonString`（`PrintStringBuffer.cs:103-123`）。
     *
     * 两半各用编号 0 构造（模拟两条各自 adapt 出来的行），拼完必须变成 0 / 1。
     */
    @Test
    fun `PRINTN 并入时源按钮编号整体抬起不撞号`() {
        val prev = RenderLine(
            runs = listOf(buttonRun("A", 7L, id = 0)),
            isLogicalLine = true,
            isLineEnd = false,
        )
        val next = RenderLine(
            runs = listOf(buttonRun("B", 7L, id = 0)),
            isLogicalLine = false,
            isLineEnd = true,
        )
        val landed = ConsoleAdapter.appendMerged(
            LogBuffer(10),
            ConsoleAdapter.fold(LineFolder(80), listOf(prev, next)),
        )
        assertEquals(1, landed.size, "夹具自检：末行未断行 ⇒ 并入")
        val ids = landed.single().runs.map { it.buttonId }
        assertEquals(listOf(0, 1), ids, "后一段的编号必须抬到前一段之上：$ids")

        val layer = ButtonLayer()
        layer.attach(landed)
        assertEquals(
            listOf(HitTarget(0, 0, 1, 7L), HitTarget(0, 1, 2, 7L)),
            layer.all(),
            "值相同、列号相接但是两条独立按钮",
        )
    }

    /** 核心层自己并掉 PRINTN 续行后，`adapt` 给出的源按钮编号仍按并后的 `buttons` 下标各就各位。 */
    @Test
    fun `核心层并掉 PRINTN 续行后两条同值按钮编号仍不同`() {
        val s = sink(width = 80)
        s.printButton("A", 7L)
        s.print("x", false)
        s.newLine()
        s.printButton("B", 7L)
        s.print("y")
        s.newLine()

        val lines = s.snapshot()
        assertEquals(1, lines.size, "夹具自检：PRINTN 续行并进同一显示行")
        assertEquals(
            listOf(LineBufferConsoleSink.Button(0, 1, 7L), LineBufferConsoleSink.Button(2, 3, 7L)),
            lines.single().buttons,
            "两条按钮之间隔着 1 格文本",
        )
        val rl = ConsoleAdapter.adapt(lines.single())
        assertEquals(listOf(0, 1), rl.runs.filter { it.buttonValue != null }.map { it.buttonId })
        val layer = ButtonLayer()
        layer.attach(ConsoleAdapter.fold(LineFolder(80), listOf(rl)))
        assertEquals(
            listOf(HitTarget(0, 0, 1, 7L), HitTarget(0, 2, 3, 7L)),
            layer.all(),
        )
    }

    // ------------------------------------------------------------------
    // 接线④：绘制 / 命中两条取行入口 —— C# 起行公式恒等，本仓只多要一排被裁掉的行
    // ------------------------------------------------------------------

    private fun row(i: Int) = DisplayRow(
        runs = listOf(TextRun("L$i", fore = 0xC0C0C0, back = 0x000000, styleMask = 0, fontName = "黑体")),
        cells = ("L$i").length,
        isLogicalLine = true,
        isLineEnd = true,
    )

    /**
     * C# 两处的起行公式**恒等**：令 `H = MainPicBox.Height`、`L = Config.LineHeight`，则
     * `(H - L) / L + 1 == H / L`（整数除法：`floor((H-L)/L) = floor(H/L) - 1`），
     * 所以 `EmueraConsole.cs:1805-1807`（绘制）与 `:2328-2331`（命中）算出的 `num3` 逐值相同。
     * `H < L`（窗口比一行还矮）时 .NET 的截断除法让两式差 1，这是退化区间，逐值登记在下面。
     */
    @Test
    fun `绘制与命中的起行公式恒等`() {
        // 基准窗口：CLIENTHEIGHT=955（pc-golden-root.txt:688）/ LINE HEIGHT 18（emuera.config:23）
        assertEquals(53, 955 / 18)
        assertEquals(53, (955 - 18) / 18 + 1)
        for (l in 1..40) {
            for (h in l..2000) {
                assertEquals(h / l, (h - l) / l + 1, "H=$h L=$l")
            }
        }
        // 退化区间（H < L）：截断除法让两式差 1 —— 绘制多算一行，命中不读
        assertEquals(1, (10 - 18) / 18 + 1)
        assertEquals(0, 10 / 18)
    }

    /**
     * 两个常量的读数（1 与 0）保留，但**它们不是两套口径**：支取的行逐行等价。
     * 窗口足够长时 `rowsForDraw.drop(1) == rowsForHit`（绘制多要的那一排被画布裁掉，
     * `scroll/ViewportAuditTest` 的 `rowTopPx` 读数是 `top + lineHeight <= 0`）；
     * 行数不足一屏、顶行被夹到 0 时（`:1808-1811` 与 `:2329-2332`）两支**整体相同**。
     */
    @Test
    fun `绘制与命中的取行窗口逐行等价`() {
        assertEquals(1, ConsoleAdapter.DRAW_EXTRA_ABOVE, "绘制入口多要一排（EmueraConsole.cs:1805-1807 的 + 1 已折进常量）")
        assertEquals(0, ConsoleAdapter.HIT_EXTRA_ABOVE, "命中入口不多要（EmueraConsole.cs:2328-2331）")

        val buffer = LogBuffer(500)
        repeat(30) { buffer.append(row(it)) }
        val viewport = Viewport(heightLines = 4)
        viewport.sync(buffer)

        val draw = ConsoleAdapter.rowsForDraw(viewport, buffer)
        val hit = ConsoleAdapter.rowsForHit(viewport, buffer)
        assertEquals(5, draw.size, "4 + 1 排")
        assertEquals(4, hit.size)
        assertEquals(hit, draw.drop(1), "多要的那一排在最上面，其余逐行等值")
        assertEquals("L26", hit.first().runs.first().text)
        assertEquals("L25", draw.first().runs.first().text)
        assertEquals(25, ConsoleAdapter.topRowForDraw(viewport))
        assertEquals(26, ConsoleAdapter.topRowForHit(viewport))
        assertEquals(1, ConsoleAdapter.topRowForHit(viewport) - ConsoleAdapter.topRowForDraw(viewport))

        // 不足一屏：顶行夹到 0，两支连条数都一样（没有可多要的行）
        val shortBuf = LogBuffer(500)
        repeat(2) { shortBuf.append(row(100 + it)) }
        val shortVp = Viewport(heightLines = 4)
        shortVp.sync(shortBuf)
        assertEquals(ConsoleAdapter.rowsForHit(shortVp, shortBuf), ConsoleAdapter.rowsForDraw(shortVp, shortBuf))
        assertEquals(0, ConsoleAdapter.topRowForDraw(shortVp))
        assertEquals(0, ConsoleAdapter.topRowForHit(shortVp))
    }

    // ------------------------------------------------------------------
    // 接线⑤：DisplayRow 的列偏移与对齐承载（ConsoleDisplayLine.cs:50-91）
    // ------------------------------------------------------------------

    @Test
    fun `对齐偏移按 SetAlignment 的三条分支手算`() {
        // LEFT：IsLogicalLine 分支直接 return，另一支 num2 = 0 且 pointX = 0 ⇒ 恒 0
        // （ConsoleDisplayLine.cs:72-78、:86-90）。
        assertEquals(0, ConsoleAdapter.alignmentShift(Alignment.LEFT, cells = 10, drawableWidthCells = 80))
        // CENTER：DrawableWidth/2 - num/2 = 40 - 5（ConsoleDisplayLine.cs:80）
        assertEquals(35, ConsoleAdapter.alignmentShift(Alignment.CENTER, cells = 10, drawableWidthCells = 80))
        // 整数除法向零截断：7/2 = 3 ⇒ 40 - 3 = 37
        assertEquals(37, ConsoleAdapter.alignmentShift(Alignment.CENTER, cells = 7, drawableWidthCells = 80))
        // RIGHT：DrawableWidth - num（:83）
        assertEquals(70, ConsoleAdapter.alignmentShift(Alignment.RIGHT, cells = 10, drawableWidthCells = 80))
        // 超宽行为负：原版 ShiftPositionX 允许把 PointX 移成负数（ConsoleButtonString.cs:307-315）
        assertEquals(-20, ConsoleAdapter.alignmentShift(Alignment.RIGHT, cells = 100, drawableWidthCells = 80))
    }

    @Test
    fun `折行产物带上对齐与列偏移且折出的每一行各自平移`() {
        val s = sink(width = 10)
        s.setAlignment(Alignment.RIGHT)
        s.print("abcdefgh") // 8 格，一行放得下
        s.newLine()
        val rows = ConsoleAdapter.fold(LineFolder(10), ConsoleAdapter.adapt(s.snapshot()), drawableWidthCells = 10)
        assertEquals(1, rows.size)
        assertEquals(Alignment.RIGHT, rows.single().align, "ALIGNMENT 快照随行进渲染层")
        assertEquals(2, rows.single().indentCells, "10 - 8 = 2（ConsoleDisplayLine.cs:83）")
        assertEquals(8, rows.single().cells, "偏移不计入内容宽度")

        // 折成两行时每行按自己的宽度分别平移：SetAlignment 在 addDisplayLine 里逐行调用
        // （EmueraConsole.cs:2937-2944）。
        val s2 = sink(width = 10)
        s2.setAlignment(Alignment.CENTER)
        s2.print("abcdefghijk") // 11 个半角 → 10 + 1
        s2.newLine()
        val two = ConsoleAdapter.fold(LineFolder(10), ConsoleAdapter.adapt(s2.snapshot()), drawableWidthCells = 10)
        assertEquals(2, two.size)
        assertEquals(0, two[0].indentCells, "第一行 10 格：10/2 - 10/2 = 5 - 5 = 0")
        assertEquals(5, two[1].indentCells, "第二行 1 格：10/2 - 1/2 = 5 - 0 = 5")
        assertEquals(listOf(true, false), two.map { it.isLogicalLine })
        assertEquals(listOf(Alignment.CENTER, Alignment.CENTER), two.map { it.align })
    }

    @Test
    fun `临时行标记跟着折行产物落到 DisplayRow`() {
        val s = sink()
        s.printTemporaryLine("tmp")
        val rows = ConsoleAdapter.fold(LineFolder(80), ConsoleAdapter.adapt(s.snapshot()))
        assertEquals(1, rows.size)
        assertTrue(rows.single().temporary, "REUSELASTLINE 的临时行标记必须活到日志侧（EmueraConsole.cs:2891-2893）")
    }

    // ------------------------------------------------------------------
    // 接线⑥：宽度模型只有 Grid.cells 一处
    // ------------------------------------------------------------------

    @Test
    fun `列数一律按 Grid 的等宽网格算且 TAB 先展开成 8 空格`() {
        val s = sink(width = 80)
        s.print("a\tb")
        s.newLine()
        val line = s.snapshot().single()
        assertEquals(3, textOf(line).length, "夹具自检：一个 TAB 在核心层按 1 格算")
        val rows = ConsoleAdapter.fold(LineFolder(80), listOf(ConsoleAdapter.adapt(line)))
        assertEquals(10, rows.single().cells, "交给下游的格数以 Grid.cells 为权威：1 + 8 + 1（StringMeasure.cs:57-60）")
        assertNotEquals(3, rows.single().cells, "不得沿用核心层的格数")
    }
}
