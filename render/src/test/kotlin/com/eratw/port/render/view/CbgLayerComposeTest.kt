package com.eratw.port.render.view

import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CBG 图层在 headless 合成里的层位（`ScreenComposer` 的消费面）。
 *
 * ## 判据
 *
 * 原版 `EmueraConsole.cs:1834-1857` 的双路归并：`cbgList`（立绘与 CBG 同表）按 zdepth
 * **降序**与逃逸文本段合并，每轮取两侧头的最大值 `num7`，`num7 == 0` 那轮才画正文 ⇒
 * - depth > 0 的层在正文之前落笔（垫底）；
 * - depth < 0 的层在正文之后落笔（压住正文）；
 * - 落位 `new Point(cbg.x, cbg.y + MainPicBox.Height - DestBaseSize.Height)`（`:1846`）。
 *
 * 语料活体：`ERB/魔改内容/QOL_IMAGE.ERB:2562` 的 `CBGSETG BG_ID, 0, 0, 1000`
 * （全屏高亮图垫在 `BODY_INFO` 的正文之下）。
 *
 * ## 装置
 *
 * 与 `ScreenComposerTest` 同一把尺：字号 25 ⇒ 半角格 13px、行高 25px；
 * 20 半角格 × 命中 3 行 ⇒ 画布 260×75，两行正文的绘制窗口顶边分别在 25 与 50。
 */
class CbgLayerComposeTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 25, requestedLineHeight = 25)
    private val cellPx = 13
    private val green = (0xFF shl 24) or 0x00FF00
    private val red = (0xFF shl 24) or 0xFF0000
    private val grayBg = (0xFF shl 24) or 0x102030
    private val fore = (0xFF shl 24) or ScreenRows.DEFAULT_FORE

    private fun composer(): ScreenComposer =
        ScreenComposer(clientWidthCells = 20, heightLines = 3, metrics = metrics)

    private fun twoRowScreen(): ConsoleScreen {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = 3)
        screen.submit(listOf(ScreenRows.textLine("AB"), ScreenRows.textLine("CD")))
        return screen
    }

    private fun sprite(w: Int, h: Int, argb: Int): Raster {
        val r = Raster(w, h)
        r.fillRect(0, 0, w, h, argb)
        return r
    }

    private fun tableWith(name: String, raster: Raster): SpriteTable =
        SpriteTable().apply { add(name, raster) }

    /** 一条 CBG 快照（名字分支；gid 分支在 render 侧当前跳过，见 `drawCbgLayer`）。 */
    private fun cbg(depth: Int, x: Int, y: Int, name: String, w: Int, h: Int): CbgLayerSnapshot =
        CbgLayerSnapshot(
            depth = depth,
            x = x,
            y = y,
            spriteName = name,
            gid = -1,
            widthPx = w,
            heightPx = h,
        )

    @Test
    fun `depth 为正的 CBG 垫在正文之下`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("BG_DEEP", sprite(40, 20, green))
        // x=0、y=0 ⇒ 落位 y = 75 - 20 = 55（下沿锚定，EmueraConsole.cs:1846），x 0..39。
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(cbg(1000, 0, 0, "BG_DEEP", 40, 20)),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table)

        // 第 1 行正文块 y 52..72、x 0..25 与 CBG 重叠：depth 1000 先落笔 ⇒ 正文压在图上。
        assertEquals(fore, canvas.pixelAt(0, 66), "正文在 depth 1000 的 CBG 之上")
        assertEquals(fore, canvas.pixelAt(cellPx, 66))
        // 正文块之下（y 73、74）只剩 CBG 本体。
        assertEquals(green, canvas.pixelAt(0, 74))
        assertEquals(green, canvas.pixelAt(39, 74))
        // 横向出图区外仍是背景色：x=40 已出 40px 宽的图。
        assertEquals(grayBg, canvas.pixelAt(40, 74))
        // 图上沿之外（y 48..51 在两行正文块之间，CBG 顶在 55）仍是背景色。
        assertEquals(grayBg, canvas.pixelAt(0, 51))
    }

    @Test
    fun `depth 为负的 CBG 压在正文之上`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("BG_UNDER", sprite(40, 20, green))
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(cbg(-5, 0, 0, "BG_UNDER", 40, 20)),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table)

        // 归并：num7 == 0 那轮画完正文才轮到负 z（EmueraConsole.cs:1850-1857）⇒ 盖住正文。
        assertEquals(green, canvas.pixelAt(0, 66))
        assertEquals(green, canvas.pixelAt(cellPx, 70))
        assertEquals(green, canvas.pixelAt(39, 74))
        // 正文块上沿（y 52..54）在图的 y 55 之上，仍是正文。
        assertEquals(fore, canvas.pixelAt(0, 53))
    }

    @Test
    fun `两个 CBG 按 depth 降序落笔且负 depth 后画`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = SpriteTable().apply {
            add("BG_DEEP", sprite(40, 20, green))
            add("BG_FRONT", sprite(40, 20, red))
        }
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(
                // 故意乱序传入：合成器自己按 depth 降序（不依赖装配侧顺序）。
                cbg(-5, 0, 0, "BG_FRONT", 40, 20),
                cbg(1000, 0, 0, "BG_DEEP", 40, 20),
            ),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table)

        // 同区域两层：depth 1000 先画、depth -5 后画 ⇒ 屏上看到 -5 的颜色。
        assertEquals(red, canvas.pixelAt(0, 66))
        assertEquals(red, canvas.pixelAt(39, 74))
        assertEquals(red, canvas.pixelAt(0, 74))
        // 正文在两层之间（1000 之上、-5 之下）—— 已被 -5 盖住，但能证明 1000 没盖住正文：
        // 只放 1000 时正文可见（上一条用例），此处置负 depth 后同一像素换色。
        assertEquals(ScreenComposer.orderCbgByDepthDesc(scene.cbgLayers).map { it.depth }, listOf(1000, -5))
    }

    @Test
    fun `无 CBG 与有 CBG 在图层矩形外逐像素一致`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("BG_SIDE", sprite(40, 20, green))
        val commands = screen.list.render()

        val base = c.compose(commands, 2, ScreenScene(backgroundArgb = grayBg), table)
        val withCbg = c.compose(
            commands,
            2,
            // 放在 x=200（正文只占第 0、1 格 ⇒ x 0..25），矩形外必须与无 CBG 帧逐像素相同。
            ScreenScene(
                backgroundArgb = grayBg,
                cbgLayers = listOf(cbg(1000, 200, 0, "BG_SIDE", 40, 20)),
            ),
            table,
        )

        assertEquals(green, withCbg.pixelAt(200, c.canvasHeightPx - 1))
        assertEquals(green, withCbg.pixelAt(239, c.canvasHeightPx - 1))
        var y = 0
        while (y < c.canvasHeightPx) {
            var x = 0
            while (x < c.canvasWidthPx) {
                val insideCbg = x in 200..239 && y in (c.canvasHeightPx - 20) until c.canvasHeightPx
                if (!insideCbg) {
                    assertEquals(base.pixelAt(x, y), withCbg.pixelAt(x, y), "CBG 矩形外像素被改动：($x,$y)")
                }
                x++
            }
            y++
        }
    }

    /** 缺省场景（不传 CBG）与显式空表同结果 —— 接线前的调用点零改动。 */
    @Test
    fun `空 CBG 表与不传 CBG 逐像素一致`() {
        val screen = twoRowScreen()
        val c = composer()
        val commands = screen.list.render()
        val implicit = c.compose(commands, 2, ScreenScene(backgroundArgb = grayBg))
        val explicit = c.compose(
            commands,
            2,
            ScreenScene(backgroundArgb = grayBg, cbgLayers = emptyList()),
        )
        var y = 0
        while (y < c.canvasHeightPx) {
            var x = 0
            while (x < c.canvasWidthPx) {
                assertEquals(implicit.pixelAt(x, y), explicit.pixelAt(x, y))
                x++
            }
            y++
        }
    }

    /**
     * 归并用「存在的那一侧」判负，不能拿哨兵值代替空侧：`zdepth = Int.MIN_VALUE` 是
     * 语料合法的「最上层」摆位（见 [SpritePlacement] 的 KDoc），不许被当成「列表没了」吞掉。
     */
    @Test
    fun `zdepth 为 IntMIN 的立绘在空 CBG 下仍画在正文之上`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("TOP", sprite(20, 10, green))
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            sprites = listOf(SpritePlacement("TOP", 0, 0, Int.MIN_VALUE)),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table)
        // 20×10 下沿锚定 ⇒ y 65..74，盖住第 1 行正文块（y 52..72）。
        assertEquals(green, canvas.pixelAt(0, 66))
        assertEquals(green, canvas.pixelAt(13, 70))
    }

    // ---------------------------------------------------------------
    // GraphicsSink → GraphicsLayer 的落点（VM 线程写、渲染线程读）
    // ---------------------------------------------------------------

    @Test
    fun `setCbgLayers 写进 GraphicsLayer 并推进 revision`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        val rev0 = layer.revision.get()
        val pushed = listOf(
            cbg(1000, 0, 0, "BG_DEEP", 100, 60),
            cbg(-5, 3, -7, "BG_FRONT", 20, 10),
        )
        sink.setCbgLayers(pushed)

        val stored = layer.cbgLayers
        assertEquals(2, stored.size)
        assertEquals(listOf(1000, -5), stored.map { it.depth }, "顺序原样保留（core 已按 depth 降序）")
        assertEquals("BG_DEEP", stored[0].spriteName)
        assertEquals(100, stored[0].widthPx)
        assertEquals(60, stored[0].heightPx)
        assertEquals(3, stored[1].x)
        assertEquals(-7, stored[1].y)
        assertEquals(-1, stored[1].gid)
        assertTrue(layer.revision.get() > rev0, "写入推进 revision")

        // CBGCLEAR 推空表 ⇒ 渲染侧也清空。
        sink.setCbgLayers(emptyList())
        assertTrue(layer.cbgLayers.isEmpty())
    }

    @Test
    fun `reset 清空 CBG 图层`() {
        val layer = GraphicsLayer()
        val sink = GraphicsSinkBridge(layer, metrics)
        sink.setCbgLayers(listOf(cbg(1, 0, 0, "BG", 10, 10)))
        layer.reset()
        assertTrue(layer.cbgLayers.isEmpty())
    }
}
