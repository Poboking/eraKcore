package com.eratw.port.render.view

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.sprite.DrawLineBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ConsoleScreen] 的三屏装配与确定性快照测试（M4-SCREEN）。
 *
 * ## 期望值的来源（一律不是本实现回读）
 * - 行文本：`fixtures/golden/baseline/pc-golden-root.txt` 的**真实行**（逐条在下面标了行号）；
 * - 快照夹具：`render/src/test/resources/view/` 下三份 txt，由基准真实行按下面写明的
 *   「标记行 → 屏上文本」换算规则生成（HTML 标记折成前导空格列、div 行合并），
 *   夹具里**尾随空格与行尾都不许动**（列位置就是判据的一部分）；
 * - 布局语义：`ERB/TITLE.ERB:58-82`（标题画面）、`ERB/COMMON.ERB:1435-1446`（ASK_YN 按钮）、
 *   `Process.cs:1182-1206`（PRINT_PALAM 的 5 格一行右对齐）、`EmueraConsole.cs:3246-3291`
 *   （PRINTC 补位宽度）、`EmueraConsole.cs:2947-2968`（PRINTN 并入）、
 *   `EmueraConsole.cs:3379-3382`（空行先塞一个空格）、`EmueraConsole.cs:1805-1812` 对
 *   `:2328-2331`（绘制与命中两套取行口径）；
 * - 列位置常量：半角步进 9px（`sprite/DrawLineBar.kt` 由基准 `:253` 反推）→
 *   `<shape type='space' param='841px'>` = 841/9 = 93 格（基准 `:69`），
 *   `<div xpos='1600px'>` = 1600/9 = 177 格（基准 `:71`）。
 *   TODO(verify)：px → 半角格的取整方向（这里取 floor）没有 PC 截图可比，
 *   真实字体度量口径接进来后需复核这两列。
 * - **取行溢出与并入规则不在本测试的字面量里**（ADAPT-CONSUME）：绘制／命中各读几行由
 *   `ConsoleAdapter.DRAW_EXTRA_ABOVE`（EmueraConsole.cs:1805-1807 的 `+ 1`）与
 *   `ConsoleAdapter.HIT_EXTRA_ABOVE`（:2328-2331 的无 `+ 1`）之差决定；PRINTN 并入与
 *   REUSELASTLINE 临时行先删由 `ConsoleAdapter.appendMerged`（:2947-2968、:2891-2893）决定，
 *   装配层只经 `ConsoleLogSync` 调它。所以「`submit` 的结果 == 直接调适配层的结果」这类
 *   反重复断言写在下面（`装配层的入列结果与适配层 appendMerged 逐字段相同`），
 *   夹具本身不参与这类断言 —— 三份夹具的文本仍然只以基准真实行为准。
 */
class ConsoleScreenTest {

    // ---- 夹具读取 -------------------------------------------------------

    /**
     * 读 `view/` 下的快照夹具：UTF-8；剥掉文件末尾**唯一一个**换行，其余原样返回。
     *
     * 行终止符按 `\r\n` 或 `\n` 都接（`lineSequence` 口径，与 `GoldenPipeline` 读基准同一做法）——
     * 本仓 `core.autocrlf=true`，LF 提交的夹具在 Windows 检出后是 CRLF，按单 `\n` 拆会把 `\r`
     * 留在每行尾部，与代码生成的行对不上。
     */
    private fun loadSnapshot(name: String): List<String> {
        val stream = javaClass.getResourceAsStream("/view/$name")
        assertNotNull(stream, "缺少快照夹具 /view/$name")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        // 只剥末尾**一个**行终止符：CRLF 文件必须先去掉 \r 再去掉 \n，
        // 否则残留的孤立 \r 会被 lineSequence 当成第三个终止符，凭空多出一行空行。
        val stripped = text.removeSuffix("\n").removeSuffix("\r")
        return if (stripped.isEmpty()) emptyList() else stripped.lineSequence().toList()
    }

    /**
     * 基准窗口口径的一屏：窗口 1800px／9px = 200 半角格、折行宽 DrawableWidth 1797px = 199 格
     * （Config.cs:461-466）、955px／18px 的 54 命中行、MAXLOG 5000。
     */
    private fun goldenScreen(): ConsoleScreen = ConsoleScreen(
        clientWidth = ConsoleScreen.GOLDEN_CLIENT_WIDTH,
        maxLog = LogBuffer.MAX_LOG_DEFAULT,
        heightLines = ConsoleScreen.DEFAULT_HEIGHT_LINES,
        drawableWidthCells = ConsoleScreen.GOLDEN_DRAWABLE_WIDTH,
    )

    // ---- 三屏的输入行（真实基准行文本） ----------------------------------

    private fun titleLines(): List<RenderLine> = listOf(
        ScreenRows.textLine(BAR_FULL),                                    // 基准:61  TITLE.ERB:62
        ScreenRows.textLine("eraThe World【画蛇添足版】"),                    // :62     TITLE.ERB:66
        ScreenRows.textLine("4.980 - 日语交流用画蛇添足版CH20260326"),         // :63     TITLE.ERB:67
        ScreenRows.textLine("(2013～2026)"),                               // :64     TITLE.ERB:68
        ScreenRows.textLine(" "),                                         // :65     TITLE.ERB:69 的空 PRINTL
        ScreenRows.textLine("汉化版仅为学习日语的交流之用，禁止对该版本的一切盗用、商用行为"), // :66 TITLE.ERB:70
        ScreenRows.textLine("※游玩中遇到的问题可以去问一下单独指导里的哆来咪（游玩时注意温馨提示哦）"), // :67 TITLE.ERB:71
        ScreenRows.textLine(BAR_FULL),                                    // :68     TITLE.ERB:72
        ScreenRows.line(
            ScreenRows.pad(MENU_COL_CELLS),
            ScreenRows.button("[0] 开始游戏", 0L),
        ),                                                                // :69     TITLE.ERB:74-75
        ScreenRows.line(
            ScreenRows.pad(MENU_COL_CELLS),
            ScreenRows.button("[1] 继续游戏", 1L),
            ScreenRows.run("    "),
            ScreenRows.button("[2] DEMO", 2L),
        ),                                                                // :70     TITLE.ERB:76
        ScreenRows.line(
            ScreenRows.pad(GOMOKU_COL_CELLS),
            ScreenRows.button("【欢落五子棋】", 4L),
        ),                                                                // :71-:72 TITLE.ERB:79-82
        ScreenRows.textLine("0"),                                         // :73
        ScreenRows.textLine(BAR_FULL),                                    // :74     TITLE.ERB:85
        ScreenRows.textLine("检测到已存在的存档，确定要开始新游戏吗？"),         // :75     TITLE.ERB:98
        ScreenRows.line(ScreenRows.button("[0] 那当然", 0L)),                // :76     COMMON.ERB:1443
        ScreenRows.line(ScreenRows.button("[1] 再想想", 1L)),                // :77     COMMON.ERB:1445
        ScreenRows.textLine("0"),                                         // :78
        ScreenRows.textLine(BAR_FULL),                                    // :79
    )

    /**
     * 状态栏屏：三条 DRAWLINE 分隔条由 [DrawLineBar] 现场生成（本路对它的消费点），
     * 其余是基准 `:427-434` 的角色状态行。`:431-434` 的每一格都是
     * `PRINTC(..., alignmentRight = true)` 的产物（`Process.cs:1195`），
     * 按 25 半角格切成独立 run（补前导空格见 `EmueraConsole.cs:3267-3275`，
     * 每 `PrintCPerLine` 格换行见 `Process.cs:1197-1200`，实测 `pc-golden-root.txt:290` 是 5）。
     */
    private fun statusBarLines(): List<RenderLine> {
        val bar = DrawLineBar()
        return listOf(
            ScreenRows.textLine(bar.build("=")),                          // 基准:253 CUSTOMDRAWLINE =
            ScreenRows.textLine(bar.defaultBar),                          // :254 DRAWLINE（stBar，Config.DrawLineString 空回退 "-"）
            ScreenRows.textLine(bar.build("-2")),                         // :255 DRAWLINEFORM -{1+1}
            ScreenRows.textLine("Ｃ感覚LV5 技巧LV2 "),                       // :427 PRINT_ABL
            ScreenRows.textLine("[性別]"),                                 // :428 PRINT_TALENT
            ScreenRows.textLine(" "),                                     // :429 空行 = 一个空格
            ScreenRows.textLine(" "),                                     // :430
            ScreenRows.line(*printCCells("   快Ｃ[..........]     0   快Ｖ[..........]     0   快Ａ[..........]     0   快Ｂ[..........]     0   快Ｍ[..........]     0")), // :431
            ScreenRows.line(*printCCells("   潤滑[..........]     0   恭順[..........]     0   欲情[..........]     0   屈服[..........]     0   習得[..........]     0")), // :432
            ScreenRows.line(*printCCells("   恥情[..........]     0   苦痛[..........]     0   恐怖[..........]     0   好意[..........]     0   優越[..........]     0")), // :433
            ScreenRows.line(*printCCells("   反感[..........]     0   不快[..........]     0   抑鬱[..........]     0   眠姦[..........]     0")), // :434 只有 4 格
            ScreenRows.textLine(bar.build("=")),                          // 收尾分隔条（BODY_INFO/PRINT_STATE_BODYGRAPH.ERB:77,89）
        )
    }

    /**
     * 口上对话框屏的文本区与按钮区。第 1 行是五段 `PRINTN` 续接 + `PRINTL` 收尾并入成的一行
     * （语句序列见 `GOLDEN_02_PRINT.ERB:109-114`；并入判据 `EmueraConsole.cs:2947-2968`；
     * 行尾那个空格是 `PrintFlush(force)` 先塞的空格 `:3379-3382`）。
     * 按钮区逐段对应 `GOLDEN_02_PRINT.ERB:186-197` 的语句类型与值类型（`Process.cs:1107-1124`）。
     */
    private fun dialogLines(): List<RenderLine> = listOf(
        ScreenRows.line(ScreenRows.run("NN", fore = RED), isLineEnd = false),
        ScreenRows.line(ScreenRows.run("45", fore = RED), isLineEnd = false),
        ScreenRows.line(ScreenRows.run("NS", fore = RED), isLineEnd = false),
        ScreenRows.line(ScreenRows.run("FN46", fore = RED), isLineEnd = false),
        ScreenRows.line(ScreenRows.run("FSN47", fore = RED), isLineEnd = false),
        ScreenRows.line(ScreenRows.run(" ", fore = RED)),                 // PRINTL 收尾，结束续接
        ScreenRows.textLine("AFTER-PRINTN"),
        ScreenRows.line(ScreenRows.button("BTN", 1L)),                    // 基准:257
        ScreenRows.line(ScreenRows.button("[X]", "SVAL")),                // :258
        ScreenRows.line(ScreenRows.button("                       BC", 2L)), // :259 PRINTBUTTONC：补位空格在按钮段内部
        ScreenRows.line(ScreenRows.button("BLC                      ", 3L)), // :260 PRINTBUTTONLC
        ScreenRows.line(ScreenRows.run("PLAIN", kind = RunKind.PLAIN)),   // :261 PRINTPLAIN
        ScreenRows.line(ScreenRows.run("PF7", kind = RunKind.PLAIN)),     // :262 PRINTPLAINFORM
    )

    // ---- 三屏快照 --------------------------------------------------------

    @Test
    fun `标题屏快照与夹具逐行一致`() {
        val screen = goldenScreen()
        val rects = screen.submit(titleLines())
        assertEquals(loadSnapshot("title-screen-snapshot.txt"), screen.snapshotText())
        // 首帧：18 行全脏并合成一条脏区，列跨度取最宽行的 198 半角格（基准:61 的 ━ 串 = 99 全角）
        assertEquals(listOf(DirtyRect(0, 18, 0, 198)), rects)
        assertEquals(18, screen.visibleRows().size)
        assertEquals(0L, screen.log.firstIndex)
        assertEquals(18L, screen.log.totalLines)
        // 行数少于窗口行数时，绘制与命中两个口径此刻重合（顶行下标夹到 0）
        assertEquals(18, screen.hitRows().size)
        assertEquals(0, screen.visibleRowBase)
        assertEquals(0, screen.hitRowBase)
        // 菜单按钮的列位置：841px → 第 93 格、1600px → 第 177 格
        assertEquals(93, screen.buttons.all()[0].colStart)
        assertEquals(105, screen.buttons.all()[0].colEnd)
        assertEquals(109, screen.buttons.all()[2].colStart)
        assertEquals(177, screen.buttons.all()[3].colStart)
        // 三个菜单按钮的值（TITLE.ERB:75-80 的 button value='0'/'1'/'2'/'4'）
        assertEquals(listOf(0L, 1L, 2L, 4L, 0L, 1L), screen.buttons.all().map { it.value })
    }

    @Test
    fun `状态屏快照与夹具逐行一致且分隔条等于基准`() {
        val screen = goldenScreen()
        screen.submit(statusBarLines())
        val snap = screen.snapshotText()
        assertEquals(loadSnapshot("status-bar-snapshot.txt"), snap)
        // 分隔条本身就是基准判据：199 个 `=`、199 个 `-`、199 字符的 `-2` 串且以单横杠收尾
        assertEquals("=".repeat(199), snap[0])
        assertEquals("-".repeat(199), snap[1])
        assertEquals("-2".repeat(99) + "-", snap[2])
        // gauge 行：每格 25 半角格、一行 5 格（Process.cs:1195-1200 + 基准:290 的 PCL=5）
        assertEquals(125, Grid.cells(snap[7]))
        assertEquals(125, Grid.cells(snap[8]))
        assertEquals(125, Grid.cells(snap[9]))
        assertEquals(100, Grid.cells(snap[10]))
        assertEquals(5, snap[7].count { it == '[' })
        assertEquals(4, snap[10].count { it == '[' })
        // 一格一段右对齐：段首三格是补位空格（基准:431 每格实测 3 个前导空格）
        val row = screen.visibleRows()[7]
        assertEquals(5, row.runs.size)
        assertEquals("   快Ｃ[..........]     0", row.runs[0].text)
        assertEquals(25, Grid.cells(row.runs[0].text))
        // 12 行都是**一条**显示行（199 与 125 格都没超过 199 格的折行宽 DrawableWidth，Config.cs:461-466）
        assertEquals(12, screen.visibleRows().size)
        assertEquals(12L, screen.log.totalLines)
    }

    /**
     * 黄金口径下折行宽是 **199 格**而不是窗口的 200 格：折行判据
     * `PointX + Width <= num` 的 `num` 是 `Config.DrawableWidth` = 1797px = 199 格
     * （spec: `PrintStringBuffer.cs:205` + `Config.cs:461-466`），第 200 格被 3px 形状位移
     * 挤出可绘制区、折到下一排。
     */
    @Test
    fun `黄金口径的折行宽是 199 格`() {
        val fits = goldenScreen()
        fits.submit(listOf(ScreenRows.textLine("=".repeat(199))))
        assertEquals(1, fits.visibleRows().size, "199 格 <= DrawableWidth：不折行")
        val spills = goldenScreen()
        spills.submit(listOf(ScreenRows.textLine("=".repeat(200))))
        assertEquals(2, spills.visibleRows().size, "第 200 格超出 DrawableWidth 199 格，折到下一排")
    }

    @Test
    fun `口上屏快照与夹具逐行一致且按钮区列位置可读`() {
        val screen = goldenScreen()
        screen.submit(dialogLines())
        assertEquals(loadSnapshot("kougou-dialog-snapshot.txt"), screen.snapshotText())
        // 按钮占格标尺（基准:257-260 的段宽：BTN=3、[X]=3、PRINTBUTTONC／LC 整串 25）。
        // 标尺下标是**屏上绝对列**，长度恒为 clientWidth（200）：这些行都左对齐、按钮从 0 起，
        // 故除按钮区间外的格子一律是 '.'。
        val width = ConsoleScreen.GOLDEN_CLIENT_WIDTH
        val blank = ".".repeat(width)
        val three = "BBB" + ".".repeat(width - 3)
        val twentyFive = "B".repeat(25) + ".".repeat(width - 25)
        assertEquals(
            listOf(blank, blank, three, three, twentyFive, twentyFive, blank, blank),
            screen.snapshotButtonCells(),
        )
        // 数值按钮回环给十进制串、字符串按钮给原串（EmueraConsole.cs:395-428）
        assertEquals("1", screen.click(2, 0))
        assertEquals("SVAL", screen.click(3, 1))
        assertEquals(25, screen.hit(4, 24)?.colEnd)
        assertEquals(2L, screen.hit(4, 24)?.value)
        // 数值等待期字符串按钮不算命中（EmueraConsole.cs:2439、:2456）
        assertNotNull(screen.hit(3, 1))
        assertEquals(null, screen.hit(3, 1, ButtonAcceptance.INT))
        assertEquals("SVAL", screen.hit(3, 1, ButtonAcceptance.STR)?.value)
    }

    /**
     * 按钮占格标尺的列是**屏上绝对列**，长度恒为 [ConsoleScreen.clientWidth]：
     * CENTER 行的按钮落在 `indentCells` 之后，按内容宽度截断会把 `B` 全部截掉。
     *
     * 手算（宽 20、内容 "BTN" 3 格）：CENTER 位移 = `20/2 - 3/2 = 9`
     * （spec: `ConsoleDisplayLine.cs:80`），按钮区间 `[9, 12)`；标尺应是 9 个 `.` +
     * `BBB` + 8 个 `.`。
     */
    @Test
    fun `按钮占格标尺按屏上绝对列给CENTER行`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 5)
        screen.submit(
            listOf(
                RenderLine(
                    runs = listOf(ScreenRows.button("BTN", 1L)),
                    isLogicalLine = true,
                    isLineEnd = true,
                    align = Alignment.CENTER,
                ),
            ),
        )
        assertEquals(listOf(".".repeat(9) + "BBB" + ".".repeat(8)), screen.snapshotButtonCells())
        val target = screen.buttons.all().single()
        assertEquals(9, target.colStart, "命中区间已含 indentCells（ButtonLayer.attach）")
        assertEquals(12, target.colEnd)
        assertEquals(listOf("BTN"), screen.snapshotText())
    }

    @Test
    fun `PRINTN 续接并入成一行且逻辑行只计一`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submit(dialogLines().subList(0, 6))
        assertEquals(1, screen.log.size)
        assertEquals(listOf("NN45NSFN46FSN47 "), screen.snapshotText())
        assertEquals(1L, screen.log.logicalLines)                          // EmueraConsole.cs:2971-2977
        assertEquals(1, screen.visibleRows().size)
        val row = screen.visibleRows()[0]
        assertEquals(6, row.runs.size)                                     // 五段文本 + 收尾空格
        assertEquals(16, row.cells)
        assertTrue(row.isLogicalLine)
        assertTrue(row.isLineEnd)
    }

    @Test
    fun `空内容行不代做补空格`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submit(listOf(RenderLine(runs = emptyList(), isLogicalLine = true, isLineEnd = true)))
        assertTrue(screen.snapshotText().isEmpty())
        assertEquals(0, screen.log.size)
        // 屏上的空行由调用侧交下来：一个空格、1 半角格（EmueraConsole.cs:3379-3382 + 基准:65）
        screen.submit(listOf(ScreenRows.textLine(" ")))
        assertEquals(listOf(" "), screen.snapshotText())
        assertEquals(1, screen.visibleRows()[0].cells)
    }

    // ---- 装配层改接适配层（ADAPT-CONSUME） --------------------------------

    /**
     * 整屏快照喂法（`ConsoleLogSync.submit`，即生产路径：核心层 `snapshot()` 的形态）与
     * 增量批喂法必须得到**同一份夹具** —— 并入与入列只有一份实现时这才成立。
     */
    @Test
    fun `整屏快照喂法得到的标题屏夹具与增量批一致`() {
        val batched = goldenScreen()
        batched.submit(titleLines())
        val snapshot = goldenScreen()
        val rects = snapshot.submitSnapshot(titleLines())
        // 夹具本身：与基准 :61-79 逐字节相同（三份夹具都不许为通过而重标）
        assertEquals(loadSnapshot("title-screen-snapshot.txt"), snapshot.snapshotText())
        assertEquals(batched.snapshotText(), snapshot.snapshotText())
        assertEquals(batched.log.all(), snapshot.log.all(), "两条入口落到缓冲的行必须逐字段相同")
        // 首帧整屏脏：与增量批同一条脏区（EmueraConsole.cs:1805-1812 的绘制窗口）
        assertEquals(listOf(DirtyRect(0, 18, 0, 198)), rects)
        // 同一快照重放：差分查不出变化 ⇒ 零脏区、行数不增（EmueraConsole.cs:1758 的早退分支）
        assertEquals(emptyList(), snapshot.submitSnapshot(titleLines()))
        assertEquals(18, snapshot.log.size)
        assertEquals(18L, snapshot.log.totalLines)
        // 快照变长只追加尾部那一行（:2969-2970）
        val rects2 = snapshot.submitSnapshot(titleLines() + ScreenRows.textLine("AFTER-SNAPSHOT"))
        assertEquals(19, snapshot.log.size)
        assertEquals(19L, snapshot.log.totalLines)
        assertEquals(1, rects2.size)
        assertEquals(18, rects2[0].rowStart, "只有新增的第 19 行脏（绘制口径下标 18）")
        assertEquals(19, snapshot.visibleRows().size)
    }

    /** 快照差分之后，新行仍按适配层的入列规则并入未收尾的末行。 */
    @Test
    fun `快照差分后新行仍按适配层规则并入末行`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submitSnapshot(listOf(ScreenRows.textLine("NN", isLineEnd = false)))
        assertEquals(1, screen.log.size)
        assertEquals(1L, screen.log.totalLines)
        assertEquals(0L, screen.log.logicalLines, "PRINTN 未收尾不计 LINECOUNT（EmueraConsole.cs:2971-2977）")
        screen.submitSnapshot(listOf(ScreenRows.textLine("NN", isLineEnd = false), ScreenRows.textLine("45")))
        assertEquals(1, screen.log.size, "续接行并入末行，日志不断行（EmueraConsole.cs:2947-2970）")
        assertEquals(listOf("NN45"), screen.snapshotText())
        assertEquals(1L, screen.log.totalLines, "删号再取号 ⇒ 绝对行号不变（:2945 + :3013 + :2970）")
        assertEquals(1L, screen.log.logicalLines)
    }

    /**
     * PRINTN 折成多排后下一行并入：并入前的 `deleteLine(1)` 按**逻辑行**循环
     * （spec: `EmueraConsole.cs:3007-3021`），折出的两排一起出列、`logicalLineCount`
     * 只回退首排那一次；并入库的是被删逻辑行的**末排**（`:2952-2954` 先取
     * `displayLineList[^1]` 再 deleteLine，`:2955-2966` 把它与新行拼成一条）。
     * 缺陷形态：只删末排 ⇒ 首排以「logical + lineEnd」留在缓冲里，
     * 并入行再计一次 ⇒ `logicalLines == 2` 且屏上多一行。
     */
    @Test
    fun `PRINTN 折行后的并入按逻辑行回撤`() {
        val screen = ConsoleScreen(clientWidth = 10, maxLog = 500, heightLines = 5)
        screen.submit(listOf(ScreenRows.textLine("ああああああ", isLineEnd = false)))
        assertEquals(2, screen.log.size, "12 格在宽 10 的屏上折成两排")
        assertEquals(1L, screen.log.logicalLines, "首排 logical+lineEnd 先计过一次（PrintStringBuffer.cs:193,183）")

        screen.submit(listOf(ScreenRows.textLine("xx")))
        assertEquals(1, screen.log.size, "折行首排不得残留在缓冲里")
        assertEquals(1L, screen.log.totalLines, "两排一起删、并入一行再入列（EmueraConsole.cs:3013 + :2970）")
        assertEquals(1L, screen.log.logicalLines, "旧首排已出列，并入行只计一次")
        assertEquals(
            listOf("あxx"),
            screen.snapshotText(),
            "并入的是被删逻辑行的末排（:2952-2966），旧首排随 deleteLine(1) 整条出列",
        )
    }

    /** 快照变短（CLEARLINE / DELETELINE 的形态）⇒ 尾部按行回撤，行号同步回退。 */
    @Test
    fun `快照变短即回撤尾部`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submitSnapshot(listOf(ScreenRows.textLine("A"), ScreenRows.textLine("B"), ScreenRows.textLine("C")))
        assertEquals(3, screen.log.size)
        assertEquals(3L, screen.log.totalLines)
        screen.submitSnapshot(listOf(ScreenRows.textLine("A"), ScreenRows.textLine("B")))
        assertEquals(listOf("A", "B"), screen.snapshotText())
        assertEquals(2L, screen.log.totalLines, "lineNo-- 随被回撤的行回退（EmueraConsole.cs:3013）")
        assertEquals(2L, screen.log.logicalLines)
        assertEquals(0L, screen.log.firstIndex)
    }

    /**
     * 反重复断言：装配层的入列结果 == 直接调适配层的结果。
     *
     * 本类不再有第二份 PRINTN 并入，所以这两条路径必须**逐字段**相同（`DisplayRow` 是 data class，
     * 连 `isLogicalLine`/`isLineEnd`/`align`/`indentCells`/`temporary` 都要相等）。文本判据仍是夹具
     * `kougou-dialog-snapshot.txt` 的头两行（基准 `:218-219`）。
     */
    @Test
    fun `装配层的入列结果与适配层 appendMerged 逐字段相同`() {
        val lines = dialogLines().subList(0, 7)
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submit(lines)
        val reference = LogBuffer(LogBuffer.MAX_LOG_DEFAULT)
        ConsoleAdapter.appendMerged(reference, ConsoleAdapter.fold(LineFolder(40), lines))
        assertEquals(reference.all(), screen.log.all(), "入列规则只有一份：结果必须逐字段相同")
        assertEquals(listOf("NN45NSFN46FSN47 ", "AFTER-PRINTN"), screen.snapshotText())
        assertEquals(2, screen.log.size)
        assertEquals(2L, screen.log.logicalLines)
    }

    /** REUSELASTLINE 的临时行在装配层也要被下一条正常行顶掉（EmueraConsole.cs:2891-2893）。 */
    @Test
    fun `临时行经装配层入列前被下一条正常行顶掉`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submit(
            listOf(
                RenderLine(
                    runs = listOf(ScreenRows.run("TMP")),
                    isLogicalLine = true,
                    isLineEnd = true,
                    temporary = true,
                ),
            ),
        )
        assertEquals(1, screen.log.size)
        assertEquals(1L, screen.log.totalLines)
        assertTrue(screen.log.last()!!.temporary, "临时行标记必须活着到日志（Kernel.kt 的 DisplayRow.temporary）")
        screen.submit(listOf(ScreenRows.textLine("NEXT")))
        assertEquals(listOf("NEXT"), screen.snapshotText())
        assertEquals(1, screen.log.size, "临时行被正常行顶掉（:2891-2893）")
        assertEquals(1L, screen.log.totalLines, "删号再取号 ⇒ 绝对行号不变")
        assertEquals(1L, screen.log.logicalLines)
    }

    /** ALIGNMENT 的整行平移经适配层算好后，装配层不许把它丢掉（ConsoleDisplayLine.cs:70-90）。 */
    @Test
    fun `ALIGNMENT 的列偏移经装配层活着到日志行`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        screen.submit(
            listOf(
                RenderLine(runs = listOf(ScreenRows.run("AB")), isLogicalLine = true, isLineEnd = true, align = Alignment.CENTER),
                RenderLine(runs = listOf(ScreenRows.run("AB")), isLogicalLine = true, isLineEnd = true, align = Alignment.RIGHT),
                ScreenRows.textLine("AB"),
            ),
        )
        val rows = screen.visibleRows()
        assertEquals(3, rows.size)
        assertEquals(Alignment.CENTER, rows[0].align)
        assertEquals(40 / 2 - 2 / 2, rows[0].indentCells, "CENTER：DrawableWidth/2 - num/2（ConsoleDisplayLine.cs:80）")
        assertEquals(Alignment.RIGHT, rows[1].align)
        assertEquals(40 - 2, rows[1].indentCells, "RIGHT：DrawableWidth - num（ConsoleDisplayLine.cs:83）")
        assertEquals(Alignment.LEFT, rows[2].align)
        assertEquals(0, rows[2].indentCells, "LEFT 恒不平移（ConsoleDisplayLine.cs:72-78）")
        assertEquals(2, rows[0].cells, "偏移不计入内容宽度")
    }

    /** 绝对行号与快照行下标的互换都走适配层的 `positionOf` / `lineNoAt`。 */
    @Test
    fun `绝对行号与快照行下标互换走适配层的换算`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = 500, heightLines = 3)
        screen.submit(List(530) { ScreenRows.textLine("A$it") })
        // 保留区 500 行、firstIndex = 30（EmueraConsole.cs:1865-1868 的 shift）；
        // 绘制窗口是保留区下标 496..499 ⇒ 绝对行号 526..529。
        assertEquals(500, screen.log.size)
        assertEquals(30L, screen.log.firstIndex)
        assertEquals(4, screen.visibleRows().size)
        assertEquals(526L, screen.snapshotRowToLineNumber(0))
        assertEquals(529L, screen.snapshotRowToLineNumber(3))
        assertEquals(null, screen.snapshotRowToLineNumber(4))
        assertEquals(null, screen.snapshotRowToLineNumber(-1))
        assertEquals(0, screen.lineNumberToSnapshotRow(526L))
        assertEquals(3, screen.lineNumberToSnapshotRow(529L))
        assertEquals(null, screen.lineNumberToSnapshotRow(29L), "已淘汰的号求不出下标（positionOf 的 firstIndex 守卫）")
        assertEquals(null, screen.lineNumberToSnapshotRow(530L), "还没生成的号")
        for (row in screen.visibleRows().indices) {
            val lineNo = screen.snapshotRowToLineNumber(row)
            assertNotNull(lineNo)
            assertEquals(row, screen.lineNumberToSnapshotRow(lineNo), "往返必须回到同一个下标")
        }
    }

    // ---- 确定性与幂等 ----------------------------------------------------

    @Test
    fun `重复提交幂等`() {
        val screen = goldenScreen()
        screen.submit(titleLines())
        // 没有任何新内容：VM 空转一帧不产生重绘工作量（EmueraConsole.cs:1758 的早退分支）
        assertEquals(emptyList(), screen.refresh())
        assertEquals(emptyList(), screen.submit(emptyList()))
        val first = screen.snapshotText()
        assertEquals(first, screen.snapshotText())
        assertEquals(18, screen.log.size)
    }

    @Test
    fun `快照字节级可复现`() {
        val a = goldenScreen()
        a.submit(titleLines() + statusBarLines() + dialogLines())
        val b = goldenScreen()
        b.submit(titleLines() + statusBarLines() + dialogLines())
        val bytesA = a.snapshotTextJoined().toByteArray(Charsets.UTF_8)
        val bytesB = b.snapshotTextJoined().toByteArray(Charsets.UTF_8)
        assertTrue(bytesA.contentEquals(bytesB), "同输入两次快照必须逐字节相同")
        assertEquals(bytesA.size, bytesB.size)
        // 同一实例连读两次也必须逐字节相同（快照不消耗状态、不含迭代序）
        assertTrue(a.snapshotText().joinToString("\n").toByteArray().contentEquals(
            a.snapshotText().joinToString("\n").toByteArray(),
        ))
        assertEquals(a.snapshotButtonCells(), b.snapshotButtonCells())
        assertEquals(a.log.firstIndex, b.log.firstIndex)
        assertEquals(a.log.logicalLines, b.log.logicalLines)
        assertEquals(a.list.render(), b.list.render())
    }

    @Test
    fun `脏区只覆盖变化行且列跨度取新旧较大`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 5)
        val first = screen.submit(listOf(ScreenRows.textLine("AB"), ScreenRows.textLine("C")))
        assertEquals(listOf(DirtyRect(0, 2, 0, 2)), first)
        // 日志只追加：新行才脏，脏区从第 2 行起，列跨度取该行宽 4
        val rects = screen.submit(listOf(ScreenRows.textLine("DEFG"), ScreenRows.textLine("H")))
        assertEquals(listOf(DirtyRect(2, 4, 0, 4)), rects)
        assertEquals(listOf("AB", "C", "DEFG", "H"), screen.snapshotText())
    }

    // ---- 滚动口径 --------------------------------------------------------

    @Test
    fun `绘制口径比命中口径多读一行`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 3)
        screen.submit(List(10) { ScreenRows.textLine("A$it") })
        // 行数由适配层的一对常量给出，不在这里写 1 与 0
        assertEquals(screen.heightLines + ConsoleAdapter.DRAW_EXTRA_ABOVE, screen.visibleRows().size)   // EmueraConsole.cs:1805-1807
        assertEquals(screen.heightLines + ConsoleAdapter.HIT_EXTRA_ABOVE, screen.hitRows().size)        // :2328-2331
        val extra = ConsoleAdapter.DRAW_EXTRA_ABOVE - ConsoleAdapter.HIT_EXTRA_ABOVE
        assertEquals(1, extra, "「绘制比命中多一行」= 两个常量之差（:1805-1807 的 +1 对 :2328-2331 的无 +1）")
        assertEquals(ConsoleScreen.EXTRA_ABOVE_ROWS, extra, "装配层只留这个差值，且它就等于适配层之差")
        assertEquals(-extra, screen.visibleRowBase - screen.hitRowBase)                                  // 绘制窗口比命中窗口早一行开始
        assertEquals(listOf("A6", "A7", "A8", "A9"), screen.snapshotText())
        assertEquals(listOf("A7", "A8", "A9"), screen.hitRows().map { it.runs[0].text })
        assertEquals(1, screen.hitRowToSnapshotRow(0))
        // 绝对行号 → 快照行下标：A6 是绝对行 6，落在绘制窗口（基准下标 6）的第一条
        assertEquals(0, screen.lineNumberToSnapshotRow(6L))
        assertEquals(3, screen.lineNumberToSnapshotRow(9L))
        assertEquals(null, screen.lineNumberToSnapshotRow(5L))
    }

    /**
     * 绘制侧的行定位口径（钉 `CanvasConsoleView.drawCmd` 的 `rowTop = (row - extraRows) * 行高`）：
     * 屏上第 0 排必须是**命中窗口的首行**，那条多读出来的溢出行必须落在折线上方。
     *
     * 真机缺陷的形态正是这个差值的符号写反（`visibleRowBase - hitRowBase`）：装满时得 -1、
     * 被 `coerceIn(0, 1)` 夹成 0 ⇒ 溢出行占住屏顶、整屏内容低一排、最新打印的一行永远不可见，
     * 而 `tap` 用同一个反符号换算命中行 ⇒ 按钮跟着偏一排但照样点得动（症状隐蔽）。
     */
    @Test
    fun `屏上第零排是命中窗口首行且溢出行落在折线上方`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 3)
        screen.submit(List(10) { ScreenRows.textLine("A$it") })
        // 绘制侧要减掉的「屏外溢出排数」= 两条窗口基准之差（与 hitRowToSnapshotRow 的反向同一口径）
        val clipped = screen.hitRowBase - screen.visibleRowBase
        assertEquals(ConsoleScreen.EXTRA_ABOVE_ROWS, clipped, "装满时被裁掉的溢出行恰好一排")
        assertEquals("A6", screen.visibleRows()[0].runs[0].text, "第 0 条绘制命令就是那条溢出行")
        assertEquals("A7", screen.visibleRows()[clipped].runs[0].text, "屏上第 0 排 = 命中窗口首行")
        assertEquals("A9", screen.visibleRows()[clipped + screen.heightLines - 1].runs[0].text,
            "屏上最后一排 = 最新打印的一行（否则满页时最后一行永不可见）")
        // 屏上排号 → 命中行：两者同起点（窗口装满时恒等），点得中屏上看到的那一行
        for (r in 0 until screen.heightLines) {
            assertEquals(r, screen.hitRowToSnapshotRow(r)!! - clipped, "屏上第 $r 排应命中第 $r 行")
        }
    }

    @Test
    fun `上翻锁定是偏移守恒且绝不跳回底部`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 3)
        screen.submit(List(10) { ScreenRows.textLine("A$it") })
        screen.viewport.scrollUp(2)
        screen.refresh()
        assertEquals(2, screen.viewport.offsetFromEnd)
        assertFalse(screen.viewport.atBottom, "上翻后不能被判成贴底（原版 Value != Maximum，EmueraConsole.cs:1804）")
        val scrolled = screen.snapshotText()
        assertEquals(listOf("A4", "A5", "A6", "A7"), scrolled)
        // 无新内容时反复刷新，快照与滚动状态一个数都不动
        assertEquals(emptyList(), screen.refresh())
        assertEquals(scrolled, screen.snapshotText())
        assertEquals(2, screen.viewport.offsetFromEnd)
        // 原版 verticalScrollBarUpdate 的 num > 0 分支是 `Maximum = count; Value += num`
        // （EmueraConsole.cs:2552-2555）⇒ 追加 K 行时窗口整体前移 K 行，但距尾部距离恒为 offset，
        // 也绝不跳回底部。契约里「上翻时快照不跟随」按这条官方口径落成下面两句。
        screen.submit(listOf(ScreenRows.textLine("A10"), ScreenRows.textLine("A11")))
        assertEquals(2, screen.viewport.offsetFromEnd)
        assertEquals(listOf("A6", "A7", "A8", "A9"), screen.snapshotText())
        assertFalse(screen.viewport.atBottom)
        // 回贴底才看到最新行（鼠标点击与任意按键处无条件 Value = Maximum，MainWindow.cs:803、:1404）
        screen.viewport.snapToBottom()
        screen.refresh()
        assertEquals(listOf("A8", "A9", "A10", "A11"), screen.snapshotText())
        assertEquals(listOf("A9", "A10", "A11"), screen.hitRows().map { it.runs[0].text })
    }

    @Test
    fun `上翻过界停在最早一屏`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = LogBuffer.MAX_LOG_DEFAULT, heightLines = 3)
        screen.submit(List(6) { ScreenRows.line(ScreenRows.button("B$it", it.toLong())) })
        screen.viewport.scrollUp(99)
        screen.refresh()
        // M11-B：clamp 上界 = max(0, retainedLines - heightLines) = 6 - 3 ⇒ 滚到顶显示保留区
        // 最早三行，不再复刻 Value == 0 ⇒ num2 == -1 的整屏空（取舍见 Viewport 类文档「能上翻到哪里」）
        assertEquals(listOf("B0", "B1", "B2"), screen.snapshotText())
        assertEquals(listOf("B0", "B1", "B2"), screen.hitRows().map { it.runs[0].text })
        assertEquals(2, screen.viewport.bottomRingIndex(), "num2 = Value - 1 = 3 - 1")
        assertEquals(0, screen.hitRowToSnapshotRow(0), "绘制与命中窗口同域 ⇒ 行换算恒等")
        assertNotNull(screen.hit(0, 0), "首行按钮仍在命中表里")
    }

    @Test
    fun `清屏回到初始态`() {
        val screen = goldenScreen()
        screen.submit(titleLines())
        screen.viewport.scrollUp(3)
        val rects = screen.clear()
        assertTrue(screen.snapshotText().isEmpty())
        assertEquals(0, screen.log.size)
        assertEquals(0L, screen.log.totalLines)                            // EmueraConsole.cs:2801-2813
        assertEquals(0L, screen.log.firstIndex)
        assertTrue(screen.viewport.atBottom)
        assertEquals(0, screen.viewport.retainedLines)
        // 清屏要把整屏 18 行擦掉：一条覆盖全部行的脏区，列跨度仍是旧内容的最大宽度 198 格
        assertEquals(listOf(DirtyRect(0, 18, 0, 198)), rects)
        // 清空后重新提交，行号从 0 起算（原版 lineNo = 0 同口径）
        screen.submit(listOf(ScreenRows.textLine("AFTER-CLEAR")))
        assertEquals(0L, screen.log.firstIndex)
        assertEquals(1L, screen.log.totalLines)
        assertEquals(listOf("AFTER-CLEAR"), screen.snapshotText())
    }

    // ---- 常量判据 --------------------------------------------------------

    @Test
    fun `黄金窗口口径常量`() {
        assertEquals(200, ConsoleScreen.GOLDEN_CLIENT_WIDTH)               // 1800px／9px（基准:689 + DrawLineBar 反推）
        assertEquals(199, ConsoleScreen.GOLDEN_DRAWABLE_WIDTH)             // DrawableWidth 1797px／9px（Config.cs:461-466）
        assertEquals(54, ConsoleScreen.DEFAULT_HEIGHT_LINES)               // 955／18 + 1（基准:688 + emuera.config:23）
        // 同一组基准尺寸在 view 包里只许出现一次：合成器引用装配层的常量，不留第二份字面量
        assertEquals(ConsoleScreen.GOLDEN_CLIENT_WIDTH, ScreenComposer.GOLDEN_CLIENT_WIDTH_CELLS)
        assertEquals(ConsoleScreen.DEFAULT_HEIGHT_LINES, ScreenComposer.GOLDEN_HEIGHT_LINES)
        val bar = DrawLineBar()
        assertEquals(199, bar.drawableWidth / bar.halfCellPx)              // DrawableWidth 1797px → 199 格（基准:253）
        assertEquals(1797, bar.drawableWidth)                               // Config.cs:461-466
        // 折行口径与核心层黄金管线同一读数：第 200 格被 3px 形状位移吃掉，装不进 DrawableWidth
        assertTrue(
            ConsoleScreen.GOLDEN_DRAWABLE_WIDTH * 9 <= 1797 &&
                (ConsoleScreen.GOLDEN_DRAWABLE_WIDTH + 1) * 9 > 1797,
            "199 格 = DrawableWidth 1797px 在 9px 步进下的最大整格数",
        )
    }

    companion object {
        /** 基准 `:61` 的 `CUSTOMDRAWLINE ━`：99 个 U+2501 = 198 半角格。 */
        private val BAR_FULL: String = "━".repeat(99)

        /** `<shape type='space' param='841px'>` → 841／9 = 93 半角格（基准 `:69`、TITLE.ERB:74-75）。 */
        private const val MENU_COL_CELLS: Int = 841 / 9

        /** `<div xpos='1600px'>` → 1600／9 = 177 半角格（基准 `:71`、TITLE.ERB:79）。 */
        private const val GOMOKU_COL_CELLS: Int = 1600 / 9

        /** 基准 `:264` 的 `RED=` 实测值 16711680 = `0xFF0000`，用作口上文本色。 */
        private const val RED: Int = 0xFF0000

        /** PRINTC 的一格宽：`PRINTCLENGTH=25`（基准 `:291`，ConfigData.cs:64 的默认值）。 */
        private const val PRINT_C_CELLS: Int = 25

        /**
         * 把一行 `PRINTC` 文本按 25 半角格切成若干段（每段一个 run，对应原版
         * 每格一次 `CreateTypeCString` + `Append`，`EmueraConsole.cs:3222-3235`）。
         */
        private fun printCCells(row: String): Array<TextRun> {
            val out = ArrayList<TextRun>()
            val sb = StringBuilder()
            var cells = 0
            for (ch in row) {
                val w = Grid.cellWidth(ch)
                if (cells + w > PRINT_C_CELLS && sb.isNotEmpty()) {
                    out.add(ScreenRows.run(sb.toString()))
                    sb.setLength(0)
                    cells = 0
                }
                sb.append(ch)
                cells += w
            }
            if (sb.isNotEmpty()) {
                out.add(ScreenRows.run(sb.toString()))
            }
            return out.toTypedArray()
        }
    }
}
