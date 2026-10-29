package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [ScreenComposer] 的分层与行列位置测试（M4-SCREEN）。
 *
 * ## 期望值的来源
 * - 画布尺寸与行距：`EmueraConsole.cs:1805`（`num = MainPicBox.Height - Config.LineHeight`）、
 *   `:1812`（顶行 y 回推）、`:1854-1855`（逐行 `num += LineHeight`）；
 *   像素 ↔ 半角格换算：`StringMeasure.cs:37`（`FontSize / 2f * 1.04f`）与 `:65`（外层取整），
 *   落地件是 `glyph/CellMetrics.kt`。本测试取字号 25 ⇒ 一个半角格**恰为 13 像素**、行高 25，
 *   让所有期望值都是整数（13 的整数倍在 float 下不越界）。
 * - 立绘落位：`EmueraConsole.cs:1846` 的 `cbg.y + MainPicBox.Height - DestBaseSize.Height`
 *   （下沿锚定）；层序：`:1834-1857` 的双路归并 —— 每轮取两侧头的最大 z，
 *   `num7 == 0` 时才画文本，故 **zdepth 大的先画（压在文本之下）、zdepth 为负的最后一轮才画（压在文本之上）**；
 *   排序键见 `ClientBackGroundImage.CompareTo` 的 `-zdepth.CompareTo(...)`（`:58-65`）；
 *   立绘未创建即跳过（`:1844`）。
 * - 文本取色：`ConsoleStyledString.cs:103,119,133,138`（`Color`）；焦点按钮换 `ButtonColor`
 *   （`:105-111`），`ButtonColor` 默认 = `Config.FocusColor`（`StringStyle.cs:11,22`，
 *   基准 `pc-golden-root.txt:269` 实测 `FOCUS=16776960`）。
 * - 默认**没有**逐段背景填充：`ConsoleStyledString.cs:121-130` 那条分支要求
 *   `JSONConfig.Data.UseButtonFocusBackgroundColor` 为真，而该开关默认 false
 *   （`JSONConfigData.cs:7-8` 没有初始化器 ⇒ C# 布尔零值），所以本类不画 `back` 底。
 * - 立绘尺寸 40×20：基准 `pc-golden-root.txt:640` 的 `SPRITEWIDTH=40 HEIGHT=20`。
 *
 * 字形块本身是占位画法（M4 判据允许字形差异、只核行列位置），所以断言只打在
 * **「第几行第几列的那个像素是什么颜色」**上。
 */
class ScreenComposerTest {

    /** 字号 25 → 半角格恰 13px（25/2*1.04 = 13），行高 25px。 */
    private val metrics = CellMetrics(requestedFontPixelSize = 25, requestedLineHeight = 25)

    private val cellPx = 13
    private val linePx = 25
    private val green = (0xFF shl 24) or 0x00FF00
    private val grayBg = (0xFF shl 24) or 0x102030
    private val fore = (0xFF shl 24) or ScreenRows.DEFAULT_FORE
    private val focusFore = (0xFF shl 24) or 0xFFFF00
    private val scene = ScreenScene(backgroundArgb = grayBg)

    private fun sprite(w: Int, h: Int, argb: Int): Raster {
        val r = Raster(w, h)
        r.fillRect(0, 0, w, h, argb)
        return r
    }

    private fun tableWith(name: String, raster: Raster): SpriteTable = SpriteTable().apply { add(name, raster) }

    /** 两行短文本的一屏：20 半角格宽、命中口径 3 行 ⇒ 画布 260×75。 */
    private fun twoRowScreen(): ConsoleScreen {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        screen.submit(listOf(ScreenRows.textLine("AB"), ScreenRows.textLine("CD")))
        return screen
    }

    private fun composer(heightLines: Int = 3, clientWidthCells: Int = 20): ScreenComposer =
        ScreenComposer(clientWidthCells = clientWidthCells, heightLines = heightLines, metrics = metrics)

    // ---- 几何 ------------------------------------------------------------

    @Test
    fun `画布尺寸由 clientWidth 与 CellMetrics 决定`() {
        val c = composer()
        assertEquals(20 * cellPx, c.canvasWidthPx)
        assertEquals(3 * linePx, c.canvasHeightPx)
        // 最后一行的顶边落在 canvasHeight - lineHeight（EmueraConsole.cs:1805 + :1854-1855）
        assertEquals(50, c.rowTopPx(1, 2))
        assertEquals(25, c.rowTopPx(0, 2))
        assertEquals(50, c.rowTopPx(2, 3))
        assertEquals(0, c.rowTopPx(0, 3))
        // 绘制口径多读的那一行落在窗外（负 y），由 Raster 静默裁剪 —— 原版同样只露几个像素
        assertEquals(-linePx, c.rowTopPx(0, 4))
    }

    @Test
    fun `文本层的行列位置逐格可核`() {
        val screen = twoRowScreen()
        val c = composer()
        val rows = screen.visibleRows()
        assertEquals(2, rows.size)
        val canvas = c.compose(screen.list.render(), rows.size, scene)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX      // 25 + 2 = 27
        assertEquals(25, c.rowTopPx(0, rows.size))
        // 'A' 占第 0 格：块宽 = cellsToPixels(1) - cellsToPixels(0) - 1 = 12
        assertEquals(fore, canvas.pixelAt(0, y))
        assertEquals(fore, canvas.pixelAt(cellPx - 2, y))
        assertEquals(grayBg, canvas.pixelAt(cellPx - 1, y))                   // 1px 缝：同色相邻字形仍可数个格
        assertEquals(fore, canvas.pixelAt(cellPx, y))                         // 'B' 起于第 1 格
        assertEquals(fore, canvas.pixelAt(2 * cellPx - 2, y))
        assertEquals(grayBg, canvas.pixelAt(2 * cellPx, y))                   // 两格之后没有内容
        // 竖向：块只占行高中间一段，上下各留 GLYPH_INSET_PX
        assertEquals(grayBg, canvas.pixelAt(0, c.rowTopPx(0, rows.size)))
        assertEquals(grayBg, canvas.pixelAt(0, c.rowTopPx(1, rows.size) - 1))
        // 第 1 行整体下移一个行高
        assertEquals(y + linePx, c.rowTopPx(1, rows.size) + ScreenComposer.GLYPH_INSET_PX)
        assertEquals(fore, canvas.pixelAt(0, c.rowTopPx(1, rows.size) + ScreenComposer.GLYPH_INSET_PX))
    }

    @Test
    fun `全角字形占两格`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        screen.submit(listOf(ScreenRows.textLine("快Ｃ"), ScreenRows.textLine("AB")))
        val c = composer()
        val rows = screen.visibleRows()
        assertEquals(2, rows.size)
        assertEquals(4, Grid.cells("快Ｃ"))
        val canvas = c.compose(screen.list.render(), rows.size, scene)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX
        assertEquals(fore, canvas.pixelAt(0, y))
        assertEquals(fore, canvas.pixelAt(2 * cellPx - 2, y))                  // 第一个全角字的块内
        assertEquals(grayBg, canvas.pixelAt(2 * cellPx - 1, y))                // 缝
        assertEquals(fore, canvas.pixelAt(2 * cellPx, y))                      // 第二个全角字从第 2 格起
        assertEquals(fore, canvas.pixelAt(4 * cellPx - 2, y))
        assertEquals(grayBg, canvas.pixelAt(4 * cellPx - 1, y))
        assertEquals(grayBg, canvas.pixelAt(4 * cellPx, y))                    // 4 格之后是空
        val y1 = c.rowTopPx(1, rows.size) + ScreenComposer.GLYPH_INSET_PX
        assertEquals(fore, canvas.pixelAt(0, y1))                              // 第二行两个半角字回到第 0、1 格
        assertEquals(fore, canvas.pixelAt(cellPx, y1))
        assertEquals(grayBg, canvas.pixelAt(2 * cellPx, y1))
    }

    /**
     * 增补平面字符（一个码位、两个 UTF-16 码元）的**绘制推进量必须等于布局的 4 格**。
     *
     * 布局侧（[Grid.cells] 逐码元、`grid/LineFolder` 的段宽、`DisplayList.prepare` 的
     * `colEnd`、`ButtonLayer` 的命中列）早就是 4 格；旧绘制走
     * `CellMetrics.cellsOf(codePoint > 0xFFFF) = FULL_WIDTH_CELLS = 2`，于是同一条 run 里
     * emoji 之后的文本被画在预留位置的左边 2 格（命中区/脏区与字形错位）。
     * 判据落在**同一条 run** 上（跨 run 时第二段的 `colStart` 由布局给出，看不出发散）：
     * `😀AB` 共 6 格，旧实现只画到第 3 格（像素 39..50），第 4 格起是背景。
     */
    @Test
    fun `增补平面字符按布局的四格推进绘制`() {
        // U+1F600 写成代理对转义，避免测试源文件编码依赖
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        screen.submit(listOf(ScreenRows.textLine("\uD83D\uDE00AB")))
        val c = composer()
        val rows = screen.visibleRows()
        // 布局口径：4 + 1 + 1 = 6 格（Grid.cells 逐码元，与 LineFolder 同式）
        val cmd = screen.list.render().single()
        assertEquals(0, cmd.colStart)
        assertEquals(6, cmd.colEnd)
        val canvas = c.compose(screen.list.render(), rows.size, scene)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX
        assertEquals(fore, canvas.pixelAt(0, y))                               // emoji 块起于第 0 格
        assertEquals(fore, canvas.pixelAt(2 * cellPx, y))                      // 块横跨 4 格：第 2 格仍是它
        assertEquals(grayBg, canvas.pixelAt(4 * cellPx - 1, y))                // 1px 缝在 4 格末尾
        assertEquals(fore, canvas.pixelAt(4 * cellPx, y))                      // 'A' 起于第 4 格（旧实现这里是背景）
        assertEquals(fore, canvas.pixelAt(5 * cellPx, y))                      // 'B' 起于第 5 格
        assertEquals(grayBg, canvas.pixelAt(6 * cellPx, y))                    // 6 格之后是空
    }

    // ---- 分层 ------------------------------------------------------------

    @Test
    fun `背景色在最下`() {
        val screen = twoRowScreen()
        val c = composer()
        val canvas = c.compose(screen.list.render(), 2, ScreenScene(backgroundArgb = green))
        assertEquals(green, canvas.pixelAt(100, 5))                            // 无文本无立绘处即背景色
        assertEquals(green, canvas.pixelAt(250, 74))
        assertEquals(fore, canvas.pixelAt(0, c.rowTopPx(0, 2) + ScreenComposer.GLYPH_INSET_PX))
    }

    @Test
    fun `立绘按语料摆位下沿锚定`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("PORTRAIT", sprite(40, 20, green))               // 基准:640 的 40×20
        val placed = listOf(SpritePlacement(name = "PORTRAIT", xPx = 200, yPx = 0))
        val canvas = c.compose(screen.list.render(), 2, ScreenScene(backgroundArgb = grayBg, sprites = placed), table)
        // y = yPx + 画布高 - 立绘高 ⇒ 贴在底边（EmueraConsole.cs:1846）
        assertEquals(green, canvas.pixelAt(200, 75 - 20))
        assertEquals(green, canvas.pixelAt(239, 74))
        assertEquals(grayBg, canvas.pixelAt(200, 75 - 20 - 1))                 // 立绘上沿之外
        assertEquals(grayBg, canvas.pixelAt(199, 74))                          // 立绘左沿之外
        assertEquals(grayBg, canvas.pixelAt(240, 74))
    }

    @Test
    fun `zdepth 非负的立绘压在文本之下`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("OVER", sprite(20, 10, green))
        val scene = ScreenScene(backgroundArgb = grayBg, sprites = listOf(SpritePlacement("OVER", 0, 0, 5)))
        val canvas = c.compose(screen.list.render(), 2, scene, table)
        // 立绘占 y 65..74、x 0..19；第 1 行的文本块占 y 52..72 —— 文本后画，故盖住重叠区
        assertEquals(fore, canvas.pixelAt(0, 66))
        assertEquals(fore, canvas.pixelAt(13, 66))
        assertEquals(green, canvas.pixelAt(10, 74))                            // 文本块之下的立绘本体
        assertEquals(green, canvas.pixelAt(19, 73))                            // 同一列但已出文本块高度
        assertEquals(fore, canvas.pixelAt(19, 66))                             // 重叠区里文本在后、盖住立绘
        assertEquals(grayBg, canvas.pixelAt(20, 74))                           // 立绘右沿之外
    }

    @Test
    fun `zdepth 为负的立绘压在文本之上`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("UNDER", sprite(20, 10, green))
        val scene = ScreenScene(backgroundArgb = grayBg, sprites = listOf(SpritePlacement("UNDER", 0, 0, -1)))
        val canvas = c.compose(screen.list.render(), 2, scene, table)
        // 归并循环里负 z 排在 num7 == 0（文本）之后才画 ⇒ 盖住文本（EmueraConsole.cs:1834-1857）
        assertEquals(green, canvas.pixelAt(0, 66))
        assertEquals(green, canvas.pixelAt(13, 70))
        assertEquals(fore, canvas.pixelAt(0, 60))                              // 立绘上沿之外仍是文本
        assertEquals(green, canvas.pixelAt(19, 65))
    }

    @Test
    fun `zdepth 排序取降序且同值保持入参序`() {
        val ordered = ScreenComposer.orderByZdesc(
            listOf(
                SpritePlacement("A", 0, 0, 0),
                SpritePlacement("B", 0, 0, 7),
                SpritePlacement("C", 0, 0, -3),
                SpritePlacement("D", 0, 0, 7),
                SpritePlacement("E", 0, 0, 0),
            ),
        )
        assertEquals(listOf("B", "D", "A", "E", "C"), ordered.map { it.name })
    }

    @Test
    fun `未登记的立绘名静默跳过`() {
        val screen = twoRowScreen()
        val c = composer()
        val scene = ScreenScene(backgroundArgb = grayBg, sprites = listOf(SpritePlacement("MISSING", 0, 0)))
        val canvas = c.compose(screen.list.render(), 2, scene, SpriteTable())
        // 原版守卫 aSprite != null && aSprite.IsCreated（EmueraConsole.cs:1844）：整块不画、不抛
        assertEquals(grayBg, canvas.pixelAt(0, c.canvasHeightPx - 1))
        assertEquals(grayBg, canvas.pixelAt(19, c.canvasHeightPx - 1))
    }

    // ---- 焦点与裁剪 ------------------------------------------------------

    @Test
    fun `指着的按钮换成焦点色`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 5)
        // back 故意留默认黑：焦点色若误取背景色，指针指着时就是「黑底黑字」——
        // 这条用例在旧实现（焦点读 DrawCmd.back）下拿到的是 0xFF000000，不是 FOCUS。
        screen.submit(listOf(ScreenRows.line(ScreenRows.run(text = "BTN", kind = RunKind.BUTTON, buttonValue = 7L))))
        val c = composer(heightLines = 5)
        val rows = screen.visibleRows()
        assertEquals(1, rows.size)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX
        val plain = c.compose(screen.list.render(), rows.size, scene)
        assertEquals(fore, plain.pixelAt(0, y))                                // 没有焦点时仍是 Color
        assertEquals(fore, plain.pixelAt(cellPx, y))
        val focused = c.compose(
            commands = screen.list.render(),
            rowCount = rows.size,
            scene = scene,
            focusedButton = HitTarget(0, 0, 3, 7L),
        )
        assertEquals(focusFore, focused.pixelAt(0, y))                         // ConsoleStyledString.cs:105-111
        assertEquals(focusFore, focused.pixelAt(cellPx, y))
        assertEquals(focusFore, focused.pixelAt(2 * cellPx, y))
        assertEquals(grayBg, focused.pixelAt(3 * cellPx, y))                   // 半开区间：第 3 格已出按钮段
    }

    /**
     * 端到端：核心层 `PRINTBUTTON` 的 sink 快照经 `ConsoleAdapter` → [ConsoleFeed] →
     * [ConsoleScreen] 落到绘制命令后，焦点命中按钮必须画 `Config.FocusColor`（`0xFFFF00`），
     * 而不是行背景色。
     *
     * 缺陷形态：适配层曾把按钮 run 的 `back` 也填成 `line.backColor`（默认 0x000000），
     * 而 [ScreenComposer] 在焦点命中时返回 `opaque(cmd.back)`，于是黑底黑字
     * （原版 `ConsoleStyledString.cs:105-111` 在 isFocus 时把**字色**换成 `base.ButtonColor`，
     * 画的是文字前景，与背景无关）。合并后的绘制侧已改读每段 `DrawCmd.focusFore`
     * （见下一条用例），本条仍然成立：适配层给按钮段填的 `back` 与 focusFore 缺省值同为
     * `0xFFFF00`（`StringStyle.cs:11,22`、`ConfigData.cs:70`）。
     */
    @Test
    fun `sink 快照里的按钮经适配与装配后焦点命中取焦点色`() {
        val sink = LineBufferConsoleSink(clientWidth = 20)
        sink.setBackColor(0x10, 0x20, 0x30)
        sink.print("ab")
        sink.printButton("BTN", 7L)
        sink.newLine()
        sink.print("XY")
        sink.newLine()

        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 5)
        val feed = ConsoleFeed(sink, screen)
        feed.pump()

        val rows = screen.visibleRows()
        assertEquals(2, rows.size)
        assertEquals(7L, screen.hit(0, 2)?.value, "夹具自检：第 0 行第 2 格是 PRINTBUTTON 的命中区")
        // 适配层：按钮 run 的 back 槽承载焦点色，非按钮 run 的 back 仍是行背景色。
        assertEquals(listOf(RunKind.TEXT, RunKind.BUTTON), rows[0].runs.map { it.kind })
        assertEquals(0x102030, rows[0].runs[0].back, "非按钮 run 的 back = SETBGCOLOR 行快照")
        assertEquals(
            LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
            rows[0].runs[1].back,
            "按钮 run 的 back = Config.FocusColor（StringStyle.cs:22、ConfigData.cs:70）",
        )
        assertEquals(0x102030, rows[1].runs[0].back)

        // TODO(verify)：上面这条钉的是**适配层把焦点色塞进按钮段 back 槽**的搬运口径
        // （origin/main 侧 ConsoleAdapter）。审计侧改为每段 `TextRun.focusFore` 承载（下一条
        // 用例；C# 口径：ConsoleStyledString.cs:105-111 的 ButtonColor 是字色，back 是控制台
        // 背景色，两个字段）。合并后绘制侧只读 focusFore，适配层的 `focusColor` 注入因此成了
        // 死通道；若接线时把适配层改写入 focusFore，本条断言需同步改钉。

        val c = composer(heightLines = 5)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX
        val plain = c.compose(screen.list.render(), rows.size, scene)
        assertEquals(fore, plain.pixelAt(2 * cellPx, y), "无焦点时按钮仍用前景色")
        val focused = c.compose(screen, scene = scene, pointing = 0 to 2)
        assertEquals(focusFore, focused.pixelAt(2 * cellPx, y), "焦点命中按钮 ⇒ 用 0xFFFF00 画字")
        assertNotEquals(grayBg, focused.pixelAt(2 * cellPx, y), "修复前该像素等于行背景色（黑底黑字）")
        assertEquals(fore, focused.pixelAt(0, y), "焦点只换按钮段字色，非按钮 run 不受影响")
    }

    /**
     * 焦点色是**每段可覆盖**的独立字段（[com.eratw.port.render.TextRun.focusFore]）：
     * 原版 `StringStyle.ButtonColor` 由 HTML `bcolor` 逐段改写（`HtmlManager.cs:131-149`），
     * 默认值才是 `Config.FocusColor`（`ConfigData.cs:70`）。本条钉住 DrawCmd 的透传。
     */
    @Test
    fun `焦点色逐段可覆盖且默认取FocusColor`() {
        val custom = 0x123456
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        screen.submit(
            listOf(
                ScreenRows.line(
                    ScreenRows.run(text = "AB", kind = RunKind.BUTTON, buttonValue = 1L, focusFore = custom),
                ),
            ),
        )
        val c = composer()
        val rows = screen.visibleRows()
        val cmd = screen.list.render().single()
        assertEquals(custom, cmd.focusFore)
        val y = c.rowTopPx(0, rows.size) + ScreenComposer.GLYPH_INSET_PX
        val focused = c.compose(screen.list.render(), rows.size, scene, focusedButton = HitTarget(0, 0, 2, 1L))
        assertEquals((0xFF shl 24) or custom, focused.pixelAt(0, y))
        assertEquals((0xFF shl 24) or custom, focused.pixelAt(cellPx, y))
        // 默认值仍是 Config.FocusColor（ConfigData.cs:70）
        assertEquals(0xFFFF00, TextRun(text = "x", fore = 0, back = 0, styleMask = 0, fontName = "f").focusFore)
    }

    @Test
    fun `溢出行落在负 y 被裁掉`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 2)
        screen.submit(List(5) { ScreenRows.textLine("L$it") })
        val c = composer(heightLines = 2)
        val rows = screen.visibleRows()
        assertEquals(3, rows.size)                                             // 绘制口径 = 命中口径 + 1 溢出行
        assertEquals(2, screen.hitRows().size)
        assertEquals(-linePx, c.rowTopPx(0, rows.size))
        assertEquals(c.canvasHeightPx - linePx, c.rowTopPx(2, rows.size))      // 底行仍是最新行
        assertEquals("L4", rows[2].runs[0].text)
        val canvas = c.compose(screen.list.render(), rows.size, scene)
        assertEquals(grayBg, canvas.pixelAt(0, 0))                             // 溢出行整行在窗外
        assertEquals(fore, canvas.pixelAt(0, c.rowTopPx(1, rows.size) + ScreenComposer.GLYPH_INSET_PX))
    }

    @Test
    fun `便捷入口按绘制口径取命令与焦点`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 2)
        screen.submit(List(5) { i -> ScreenRows.line(ScreenRows.run(text = "B$i", kind = RunKind.BUTTON, buttonValue = i.toLong())) })
        val c = composer(heightLines = 2)
        val canvas = c.compose(screen, scene = scene)
        assertEquals(20 * cellPx, canvas.width)
        assertEquals(2 * linePx, canvas.height)
        // 命中窗口是第 3、4 行（B3、B4），绘制窗口多带第 2 行（B2）
        assertEquals("3", screen.hit(0, 0)?.value.toString())
        assertEquals(1, screen.hitRowToSnapshotRow(0))
        assertEquals(1, screen.focusedTarget(0 to 0)?.row)
        val y = c.rowTopPx(1, 3) + ScreenComposer.GLYPH_INSET_PX               // 绘制口径第 1 行 = B3
        assertEquals(fore, canvas.pixelAt(0, y))                               // 不带 pointing ⇒ 无焦点
        val focused = c.compose(screen, scene = scene, pointing = 0 to 0)
        assertEquals(focusFore, focused.pixelAt(0, y))
        assertEquals(focusFore, focused.pixelAt(cellPx, y))                    // 第 1 格仍在 2 格宽的按钮段内
        assertEquals(grayBg, focused.pixelAt(2 * cellPx, y))                   // 第 2 格已出按钮段
    }
}
