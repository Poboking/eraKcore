package com.eratw.port.render.view

import com.eratw.port.render.DirtyRect
import com.eratw.port.render.RunKind
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 按行重绘的像素等价性（W8-RENDERPERF 的核心判据）。
 *
 * 每一条用例都做同一件事：先在 [ConsoleFrame] 上**逐帧增量**地画，
 * 再用「同一状态 + 整屏 `ScreenComposer.composeInto`」画一张全新的画布，逐像素对拍。
 * 两者相同即证明「按行脏重绘」没有改变任何一个像素 —— 这也是本波性能改动
 * （省掉每帧 6.9 MB 的画布与整屏重绘）**输出不变**的直接证据。
 *
 * 期望值不来自实现：整屏那侧走的是既有入口 `ScreenComposer.composeInto`（本波之前
 * `compose` 的同一段代码），像素读数口径见 `ScreenComposerTest`。
 */
class ConsoleFrameTest {

    /** 字号 25 → 半角格恰 13px（25/2*1.04），行高 25px，与 `ScreenComposerTest` 同一组整数。 */
    private val metrics = CellMetrics(requestedFontPixelSize = 25, requestedLineHeight = 25)

    private fun composer(heightLines: Int = 3, clientWidthCells: Int = 20) =
        ScreenComposer(clientWidthCells = clientWidthCells, heightLines = heightLines, metrics = metrics)

    /** 同一状态的整屏重绘（对照组）。 */
    private fun fullRepaint(
        screen: ConsoleScreen,
        composer: ScreenComposer,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        focusedButton: com.eratw.port.render.HitTarget? = null,
    ): Raster = composer.composeInto(
        target = Raster(composer.canvasWidthPx, composer.canvasHeightPx),
        commands = screen.list.render(),
        rowCount = screen.visibleRows().size,
        scene = scene,
        sprites = sprites,
        focusedButton = focusedButton,
    )

    /**
     * 灌满绘制窗口（`heightLines + 1` 行）的屏：后续追加行时**行数不变**，
     * 于是帧路径稳定在「按行重绘」上（行数变化会退回整屏，那是另一条用例）。
     */
    private fun filledScreen(heightLines: Int, clientWidth: Int = 20, prefix: String = "L"): ConsoleScreen {
        val screen = ConsoleScreen(clientWidth = clientWidth, maxLog = 500, heightLines = heightLines)
        screen.submit((0 until heightLines + 1).map { ScreenRows.textLine("$prefix$it") })
        return screen
    }

    private fun assertSamePixels(expected: Raster, actual: Raster, note: String) {
        assertEquals(expected.width, actual.width, "$note：画布宽")
        assertEquals(expected.height, actual.height, "$note：画布高")
        var i = 0
        while (i < expected.pixels.size) {
            if (expected.pixels[i] != actual.pixels[i]) {
                fail(
                    "$note：像素 (${i % expected.width}, ${i / expected.width}) 不同 " +
                        "期望 0x${expected.pixels[i].toUInt().toString(16)} 实到 0x${actual.pixels[i].toUInt().toString(16)}",
                )
            }
            i++
        }
    }

    /** 画布像素与快照逐项相同（用来断言「这一帧一个像素都没动」）。 */
    private fun assertPixelsUnchanged(before: IntArray, actual: Raster, note: String) {
        assertEquals(before.size, actual.pixels.size, "$note：画布尺寸变了")
        var i = 0
        while (i < before.size) {
            if (before[i] != actual.pixels[i]) {
                fail("$note：像素 (${i % actual.width}, ${i / actual.width}) 被改动了")
            }
            i++
        }
    }

    @Test
    fun `追加行时按行重绘与整屏重绘逐像素相同`() {
        // 窗口已灌满（4 行 = heightLines + 1）⇒ 后续追加行不会改变行数。
        val screen = filledScreen(heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)

        var rects = screen.refresh()
        var canvas = frame.draw(screen, rects)
        assertTrue(frame.lastWasFull, "首帧没有上一帧的像素，必须整屏")
        assertSamePixels(fullRepaint(screen, composer), canvas, "首帧")

        // 窗口满了再追加：行下标整体前移，每一行都脏（真机滚动的稳态形态）。
        rects = screen.submit(listOf(ScreenRows.textLine("EF")))
        canvas = frame.draw(screen, rects)
        assertFalse(frame.lastWasFull, "行数没变 ⇒ 走按行路径（不新建画布）")
        assertEquals(4, frame.lastRowsRepainted, "整屏位移时每一行都要重绘，但仍是逐行段作画")
        assertSamePixels(fullRepaint(screen, composer), canvas, "追加一行（整屏位移）")

        // PRINTN 并入：末行原地变长，只有那一行脏。
        rects = screen.submit(listOf(ScreenRows.textLine("GH", isLineEnd = false)))
        canvas = frame.draw(screen, rects)
        assertFalse(frame.lastWasFull)
        assertSamePixels(fullRepaint(screen, composer), canvas, "PRINTN 续行")

        rects = screen.submit(listOf(ScreenRows.textLine("IJ", isLineEnd = false)))
        canvas = frame.draw(screen, rects)
        assertEquals(1, frame.lastRowsRepainted, "并入只改末行")
        assertSamePixels(fullRepaint(screen, composer), canvas, "PRINTN 并入末行")
    }

    @Test
    fun `改中间行时按行重绘与整屏重绘逐像素相同`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 4)
        val composer = composer(heightLines = 4)
        val frame = ConsoleFrame(composer)

        var rects = screen.submitSnapshot(
            listOf(ScreenRows.textLine("AAA"), ScreenRows.textLine("BBB"), ScreenRows.textLine("CCC")),
        )
        frame.draw(screen, rects)
        assertSamePixels(fullRepaint(screen, composer), frame.canvas!!, "首帧")

        rects = screen.submitSnapshot(
            listOf(ScreenRows.textLine("AAA"), ScreenRows.textLine("中中中"), ScreenRows.textLine("CCC")),
        )
        val canvas = frame.draw(screen, rects)
        assertFalse(frame.lastWasFull)
        assertEquals(1, frame.lastRowsRepainted, "只有中间那一行变")
        assertSamePixels(fullRepaint(screen, composer), canvas, "改中间行")
    }

    /**
     * 真机滚动稳态：窗口整体前移一行（行数不变、每一行都脏）。
     * 按行重绘此时应重绘全部行，但**仍然**与整屏重绘逐像素相同。
     */
    @Test
    fun `窗口整体前移时按行重绘与整屏重绘逐像素相同`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 2)
        val composer = composer(heightLines = 2)
        val frame = ConsoleFrame(composer)

        var lines = (0 until 6).map { ScreenRows.textLine("L$it") }
        var rects = screen.submitSnapshot(lines)
        var canvas = frame.draw(screen, rects)
        assertSamePixels(fullRepaint(screen, composer), canvas, "六行一次灌入")

        // 每帧追加一行：绘制窗口（3 行）整体前移。
        lines = lines + ScreenRows.textLine("L6")
        rects = screen.submitSnapshot(lines)
        canvas = frame.draw(screen, rects)
        assertFalse(frame.lastWasFull, "行数没变 ⇒ 仍走按行路径")
        assertEquals(3, frame.lastRowsRepainted)
        assertSamePixels(fullRepaint(screen, composer), canvas, "窗口前移一帧")

        lines = lines + ScreenRows.textLine("L7")
        rects = screen.submitSnapshot(lines)
        canvas = frame.draw(screen, rects)
        assertSamePixels(fullRepaint(screen, composer), canvas, "窗口再前移一帧")
    }

    @Test
    fun `清屏后按行重绘与整屏重绘逐像素相同`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"), ScreenRows.textLine("中文"))))

        val rects = screen.clear()
        val canvas = frame.draw(screen, rects)
        assertSamePixels(fullRepaint(screen, composer), canvas, "清屏")
        assertTrue(canvas.pixels.all { it == ScreenScene.DEFAULT_BACKGROUND }, "清屏后除背景色外不该留像素")
    }

    /** 稳态帧：一个像素都不动、一条命令都不发，画布实例也不换。 */
    @Test
    fun `无变化的帧不重绘`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        val canvas = frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"))))
        val before = canvas.pixels.copyOf()

        val rects = screen.refresh()
        assertTrue(rects.isEmpty())
        val again = frame.draw(screen, rects)
        assertSame(canvas, again, "稳态帧必须复用同一个画布实例")
        assertEquals(0, frame.lastDrawCount, "没有脏行就不该发绘制命令")
        assertEquals(0, frame.lastRowsRepainted)
        assertPixelsUnchanged(before, again, "稳态帧")
    }

    @Test
    fun `背景色变化退回整屏重绘`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"))))

        val scene = ScreenScene(backgroundArgb = (0xFF shl 24) or 0x102030)
        val canvas = frame.draw(screen, screen.refresh(), scene)
        assertTrue(frame.lastWasFull)
        assertSamePixels(fullRepaint(screen, composer, scene), canvas, "换背景色")
    }

    /** 场景里有立绘时一律整屏（按行重绘只碰文字行段，不碰立绘像素）。 */
    @Test
    fun `有立绘时始终整屏重绘`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        val sprite = Raster(20, 10)
        sprite.fillRect(0, 0, 20, 10, (0xFF shl 24) or 0x00FF00)
        val sprites = SpriteTable().apply { add("PORTRAIT", sprite) }
        val scene = ScreenScene(sprites = listOf(SpritePlacement(name = "PORTRAIT", xPx = 100, yPx = 0)))

        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"))), scene, sprites)
        assertTrue(frame.lastWasFull)
        val canvas = frame.draw(screen, screen.refresh(), scene, sprites)
        assertTrue(frame.lastWasFull, "有立绘的帧不做按行重绘")
        assertSamePixels(fullRepaint(screen, composer, scene, sprites), canvas, "立绘帧")
    }

    /** 焦点按钮变了要重画整屏（换色会改一整段字色，可能跨行）。 */
    @Test
    fun `焦点变化退回整屏重绘且像素一致`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 5)
        val composer = composer(heightLines = 5)
        val frame = ConsoleFrame(composer)
        val button = ScreenRows.line(
            ScreenRows.run(text = "BTN", kind = RunKind.BUTTON, buttonValue = 7L, back = 0xFFFF00),
        )
        frame.draw(screen, screen.submit(listOf(button)))
        assertTrue(frame.lastWasFull)

        val focused = screen.focusedTarget(0 to 0)
        val canvas = frame.draw(screen, screen.refresh(), pointing = 0 to 0)
        assertTrue(frame.lastWasFull, "焦点态换了颜色，必须整屏")
        assertSamePixels(fullRepaint(screen, composer, focusedButton = focused), canvas, "焦点帧")

        // 焦点没变的下一帧回到按行路径（这里没有变化，因此一像素不动）。
        val again = frame.draw(screen, screen.refresh(), pointing = 0 to 0)
        assertFalse(frame.lastWasFull)
        assertEquals(0, frame.lastRowsRepainted)
        assertSame(canvas, again)
    }

    @Test
    fun `invalidate之后退回整屏重绘`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"))))
        frame.invalidate()
        val canvas = frame.draw(screen, screen.refresh())
        assertTrue(frame.lastWasFull)
        assertSamePixels(fullRepaint(screen, composer), canvas, "invalidate 之后")
    }

    /** 行窗口行数变了（首次填满一屏）⇒ 行顶 y 的基准变了，必须整屏。 */
    @Test
    fun `行数变化退回整屏重绘`() {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("A"))))
        assertTrue(frame.lastWasFull)
        val canvas = frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("B"))))
        assertTrue(frame.lastWasFull, "行数 1 -> 2，行顶 y 全部平移")
        assertSamePixels(fullRepaint(screen, composer), canvas, "行数变化")
    }

    /**
     * 帧统计：`render.frame ms=<毫秒> draw=<命令数> rows=<重绘行数>`。
     * 时钟注入，因此 `ms` 的取整不依赖真实耗时；`draw`/`rows` 直接对应当前帧的工作量。
     */
    @Test
    fun `帧统计按整屏与按行分别报读数`() {
        val lines = ArrayList<String>()
        var now = 1_000_000L
        val stats = FrameStats(enabled = true, sink = { lines.add(it) }, clock = { now })
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3, stats = stats)
        val frame = ConsoleFrame(composer(), stats)

        // 首帧：灌满绘制窗口（4 行 = heightLines + 1），四行四段命令。
        var rects = screen.submit((0 until 4).map { ScreenRows.textLine("L$it") })
        now = 4_500_000L // +3.5 ms ⇒ 向下取整 3
        frame.draw(screen, rects)
        assertEquals(listOf("render.frame ms=3 draw=4 rows=4"), lines)
        assertEquals(3L, stats.lastMs)
        assertEquals(4, stats.lastDraw)
        assertEquals(4, stats.lastRows)

        // 稳态帧：零工作量。
        now = 9_000_000L
        frame.draw(screen, screen.refresh())
        assertEquals("render.frame ms=0 draw=0 rows=0", stats.lastLine)

        // 追加一行（行数不变）：窗口整体前移，四行都要重绘。
        now = 10_000_000L
        rects = screen.submit(listOf(ScreenRows.textLine("A1", isLineEnd = false)))
        now = 12_000_000L
        frame.draw(screen, rects)
        assertEquals("render.frame ms=2 draw=4 rows=4", stats.lastLine)
        assertEquals(3L, stats.frames)

        // PRINTN 并入末行：只有一行脏、两条段命令。
        now = 14_000_000L
        rects = screen.submit(listOf(ScreenRows.textLine("A2", isLineEnd = false)))
        now = 15_000_000L
        frame.draw(screen, rects)
        assertEquals("render.frame ms=1 draw=2 rows=1", stats.lastLine)
        assertEquals(4L, stats.frames)
    }

    /** `ms` 只统计「提交 + 绘制」整帧：`beginFrame` 在 `ConsoleScreen.refresh` 里。 */
    @Test
    fun `ms覆盖提交与绘制两段`() {
        var now = 0L
        val stats = FrameStats(enabled = true, clock = { now })
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3, stats = stats)
        val frame = ConsoleFrame(composer(), stats)
        now = 20_000_000L   // 提交开始时 20ms，提交本身耗时 20ms（模拟）
        val rects = screen.submit(listOf(ScreenRows.textLine("AB")))
        now = 41_000_000L   // 画完 41ms
        frame.draw(screen, rects)
        assertEquals(21L, stats.lastMs, "ms 覆盖 submit + draw 整帧")
    }

    /** 只画不提交（没有配对的 beginFrame）时按 0 毫秒记，不制造假的长帧。 */
    @Test
    fun `没有提交的帧按零毫秒记`() {
        val stats = FrameStats(enabled = true, clock = { 999_000_000L })
        val frame = ConsoleFrame(composer(), stats)
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        frame.draw(screen, screen.submit(listOf(ScreenRows.textLine("AB"))))
        assertEquals(0L, stats.lastMs)
    }

    /** 脏区参数的形态没变：`ConsoleFrame.draw` 只消费 `submit` 的返回值，不重算脏区。 */
    @Test
    fun `draw只消费调用方给的脏区`() {
        val screen = filledScreen(heightLines = 3)
        val composer = composer()
        val frame = ConsoleFrame(composer)
        val canvas = frame.draw(screen, screen.refresh())
        val before = canvas.pixels.copyOf()
        // 内容真的变了，但人为交一个空脏区 ⇒ 一像素不动（脏区是唯一依据）。
        screen.submit(listOf(ScreenRows.textLine("ZZ")))
        val again = frame.draw(screen, emptyList())
        assertSame(canvas, again)
        assertPixelsUnchanged(before, again, "空脏区")
    }
}
