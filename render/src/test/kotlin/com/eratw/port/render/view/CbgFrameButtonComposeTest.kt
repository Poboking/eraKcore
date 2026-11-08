package com.eratw.port.render.view

import com.eratw.port.core.host.CbgFrameGeometry
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CBG 的**动画帧落位**与**按钮链**在 headless 合成里的读数（A / B 刀）。
 *
 * ## 判据（原版）
 *
 * - 动画层：`SpriteAnime.GraphicsDraw(g, Point)` 先叠 `currentFrame.Offset`
 *   （`SpriteAnime.cs:142-143`）、落位尺寸取 `currentFrame.SrcRectangle.Size`（`:144`）；
 *   而调用方的 y 已经减过 `DestBaseSize.Height`（`EmueraConsole.cs:1846`）
 *   ⇒ 落位 = `(x + frameOffsetX, 画布高 - baseHeightPx + y + frameOffsetY)`、尺寸 = 帧尺寸。
 * - 帧推进：`(now - StartTime) % totaltime` 逐帧累减、`<= 0` 命中（`SpriteAnime.cs:108-117`，
 *   闭区间边界见 `SpriteAnimeTimelineTest`）。
 * - 按下态：`isButton && buttonValue == selectingCBGButtonInt` 时改画 `ImgB`
 *   （`EmueraConsole.cs:1840-1843`）。
 * - 取色：取色图按 `point2.Y -= ClientHeight; point3.Y += map.Height`
 *   （`:1306-1313`）后在取样点取色，`A == 255` 才算命中、值 `& 0xFFFFFF`（`:1316-1318`）。
 *
 * 装置与 `CbgLayerComposeTest` 同一把尺：字号 25 ⇒ 半角格 13px、行高 25px；
 * 20 半角格 × 命中 3 行 ⇒ 画布 260×75。CBG 放在 x=100（正文只占第 0..1 格 = x 0..25，
 * 互不重叠，像素断言才不会撞上正文块）。
 */
class CbgFrameButtonComposeTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 25, requestedLineHeight = 25)
    private val green = (0xFF shl 24) or 0x00FF00
    private val red = (0xFF shl 24) or 0xFF0000
    private val grayBg = (0xFF shl 24) or 0x102030

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

    /** 两层动画帧：30×20@(2,3) 100ms → 10×10@(0,0) 100ms。 */
    private val twoFrames = listOf(
        CbgFrameGeometry(widthPx = 30, heightPx = 20, offsetX = 2, offsetY = 3, delayMs = 100),
        CbgFrameGeometry(widthPx = 10, heightPx = 10, offsetX = 0, offsetY = 0, delayMs = 100),
    )

    private fun anime(depth: Int, x: Int, y: Int, name: String, baseH: Int): CbgLayerSnapshot =
        CbgLayerSnapshot(
            depth = depth,
            x = x,
            y = y,
            spriteName = name,
            gid = -1,
            widthPx = twoFrames[0].widthPx,
            heightPx = twoFrames[0].heightPx,
            baseHeightPx = baseH,
            frames = twoFrames,
            frameIndex = 0,
        )

    // ------------------------------------------------------------------
    // A 刀：动画帧几何
    // ------------------------------------------------------------------

    /**
     * 首帧落位：尺寸用**帧尺寸**（30×20 而不是位图/ DestBaseSize）、位置叠**帧偏移**、
     * 下沿锚点用 `DestBaseSize.Height`（50）。
     *
     * 期望：x = 100+2 = 102、y = 75 + 0 - 50 + 3 = 28，覆盖 x 102..131 / y 28..47。
     */
    @Test
    fun `动画图层按首帧尺寸与偏移落位`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("ANI", sprite(30, 20, green))
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(anime(1000, 100, 0, "ANI", baseH = 50)),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table, cbgAnimeNowMs = { 0L })

        assertEquals(green, canvas.pixelAt(102, 28), "帧左上角 = x+offsetX, 画布高-baseH+y+offsetY")
        assertEquals(green, canvas.pixelAt(131, 47), "帧右下角（尺寸 = 帧尺寸）")
        assertEquals(grayBg, canvas.pixelAt(101, 28), "帧偏移左侧是背景")
        assertEquals(grayBg, canvas.pixelAt(132, 47), "帧尺寸右侧是背景（不是位图宽 30 之外）")
        assertEquals(grayBg, canvas.pixelAt(102, 27), "帧偏移上方是背景（下沿锚点用 baseHeightPx）")
    }

    /**
     * 逐帧推进：`elapsedMs = 150` 落在第 1 帧（首帧 100ms、`<= 0` 闭区间）⇒
     * 尺寸/偏移整套换成第 1 帧（10×10@(0,0)、锚点不变）。
     */
    @Test
    fun `动画图层随时间推进到第二帧`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("ANI", sprite(30, 20, green))
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(anime(1000, 100, 0, "ANI", baseH = 50)),
        )
        val commands = screen.list.render()
        val frame0 = c.compose(commands, 2, scene, table, cbgAnimeNowMs = { 0L })
        val frame1 = c.compose(commands, 2, scene, table, cbgAnimeNowMs = { 150L })

        // 第 0 帧：102..131 / 28..47；第 1 帧：100..109 / 25..34。
        assertEquals(green, frame0.pixelAt(102, 28))
        assertEquals(green, frame1.pixelAt(100, 25), "第 1 帧左上角")
        assertEquals(green, frame1.pixelAt(109, 34))
        // (102,28) 仍落在第 1 帧的 10×10 内（帧左上 100,25）⇒ 那里是新帧的绿；
        // 「原来 30×20 的位置」只有新帧之外的像素才回背景，取 (125,45)。
        assertEquals(grayBg, frame1.pixelAt(125, 45), "第 1 帧只有 10×10 ⇒ 原来 30×20 的位置已回背景")
        assertEquals(grayBg, frame1.pixelAt(110, 30), "第 1 帧右沿之外")
    }

    /** `frameIndex = NO_FRAME`（空帧表动画）整层不画 —— 不是「按位图高画一张静态图」。 */
    @Test
    fun `空帧动画图层整层跳过`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = tableWith("EMPTY", sprite(40, 20, green))
        val scene = ScreenScene(
            backgroundArgb = grayBg,
            cbgLayers = listOf(
                CbgLayerSnapshot(
                    depth = 1000, x = 100, y = 0, spriteName = "EMPTY", gid = -1,
                    widthPx = 0, heightPx = 0,
                    baseHeightPx = 20,
                    frameIndex = CbgLayerSnapshot.NO_FRAME,
                ),
            ),
        )
        val canvas = c.compose(screen.list.render(), 2, scene, table, cbgAnimeNowMs = { 0L })
        assertEquals(grayBg, canvas.pixelAt(105, 65), "整层不画（SpriteAnime.cs:92-95/139-145）")
    }

    // ------------------------------------------------------------------
    // B 刀：按下态换图
    // ------------------------------------------------------------------

    /** 按下值命中 `buttonValue` ⇒ 改画 `spriteNameB`；不命中 ⇒ 主图。 */
    @Test
    fun `按下态换画 spriteNameB`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = SpriteTable().apply {
            add("BTN_N", sprite(10, 10, green))
            add("BTN_B", sprite(10, 10, red))
        }
        val layer = CbgLayerSnapshot(
            depth = 1000, x = 100, y = 0, spriteName = "BTN_N", gid = -1,
            widthPx = 10, heightPx = 10,
            spriteNameB = "BTN_B",
            isButton = true,
            buttonValue = 0x112233,
            baseHeightPx = 10,
        )
        val commands = screen.list.render()
        val idle = c.compose(commands, 2, ScreenScene(grayBg, cbgLayers = listOf(layer)), table)
        assertEquals(green, idle.pixelAt(105, 70), "未按下画主图")

        val pressed = c.compose(
            commands, 2,
            ScreenScene(grayBg, cbgLayers = listOf(layer), pressedCbgButtons = setOf(0x112233)),
            table,
        )
        assertEquals(red, pressed.pixelAt(105, 70), "按下态改画 ImgB（EmueraConsole.cs:1840-1843）")

        val other = c.compose(
            commands, 2,
            ScreenScene(grayBg, cbgLayers = listOf(layer), pressedCbgButtons = setOf(0x445566)),
            table,
        )
        assertEquals(green, other.pixelAt(105, 70), "按下的不是这一层 ⇒ 仍画主图")
    }

    /** 非按钮层即使 `buttonValue` 撞上按下值也不换图（原版判式先查 `isButton`）。 */
    @Test
    fun `非按钮层不参与按下态换图`() {
        val screen = twoRowScreen()
        val c = composer()
        val table = SpriteTable().apply {
            add("N", sprite(10, 10, green))
            add("B", sprite(10, 10, red))
        }
        val layer = CbgLayerSnapshot(
            depth = 1000, x = 100, y = 0, spriteName = "N", gid = -1,
            widthPx = 10, heightPx = 10,
            spriteNameB = "B",
            isButton = false,
            buttonValue = 0x112233,
            baseHeightPx = 10,
        )
        val canvas = c.compose(
            screen.list.render(), 2,
            ScreenScene(grayBg, cbgLayers = listOf(layer), pressedCbgButtons = setOf(0x112233)),
            table,
        )
        assertEquals(green, canvas.pixelAt(105, 70))
    }

    // ------------------------------------------------------------------
    // B 刀：取色图命中原语（纯函数）
    // ------------------------------------------------------------------

    /**
     * 取样点换算 = 底对齐：`sampleY = py - canvasHeightPx + mapHeightPx`。
     *
     * 原版 `point2.Y -= ClientHeight` 再 `point3.Y += cbgButtonMap.Height`
     * （`EmueraConsole.cs:1307-1312`），越界三条界判见 `:1313`。
     */
    @Test
    fun `取色图取样点按底对齐换算`() {
        // 画布高 100、取色图 40×20 ⇒ 取样图与原图底对齐。
        val inside = assertNotNull(
            CbgDrawOrder.buttonMapSamplePoint(px = 5, py = 95, canvasHeightPx = 100, mapWidthPx = 40, mapHeightPx = 20),
        )
        assertEquals(5, inside[0])
        assertEquals(15, inside[1], "95 - 100 + 20")
        assertNull(
            CbgDrawOrder.buttonMapSamplePoint(5, 70, 100, 40, 20),
            "取样 y = -10 ⇒ 越界（原版 :1313 的三条界判）",
        )
        assertNull(CbgDrawOrder.buttonMapSamplePoint(40, 95, 100, 40, 20), "x 右沿外")
        assertNull(CbgDrawOrder.buttonMapSamplePoint(-1, 95, 100, 40, 20), "x 左沿外")
        assertNull(CbgDrawOrder.buttonMapSamplePoint(5, 95, 100, 0, 20), "取色图宽 0 ⇒ 永不命中")
    }

    /** 命中：值 = 像素 `& 0xFFFFFF`、层按 `buttonValue` 匹配、gid 原样回给宿主。 */
    @Test
    fun `取色图命中给出值 层与 gid`() {
        val layers = listOf(
            CbgLayerSnapshot(
                depth = 5, x = 0, y = 0, spriteName = "BTN", gid = -1,
                widthPx = 10, heightPx = 10,
                spriteNameB = "BTN_B",
                isButton = true,
                buttonValue = 0xFF0000,
                buttonMapGid = 9,
            ),
        )
        // 取色图与画布等高 ⇒ 取样点 = 画布点。
        val hit = assertNotNull(
            CbgDrawOrder.hitCbgButton(
                px = 5, py = 5, canvasHeightPx = 20, mapWidthPx = 40, mapHeightPx = 20,
                colorAt = { _, _ -> (0xFF shl 24) or 0xFF0000 },
                layers = layers,
            ),
        )
        assertEquals(0xFF0000, hit.value, "值 = 像素 & 0xFFFFFF（EmueraConsole.cs:1318）")
        assertEquals(5, hit.sampleX)
        assertEquals(5, hit.sampleY)
        assertEquals(9, hit.buttonMapGid, "取色图 gid 原样回给宿主")
        assertEquals(listOf(0), hit.layerIndices, "buttonValue 匹配的层下标")

        // A != 255（半透明/透明）不算命中（:1316）。
        assertNull(
            CbgDrawOrder.hitCbgButton(
                px = 5, py = 5, canvasHeightPx = 20, mapWidthPx = 40, mapHeightPx = 20,
                colorAt = { _, _ -> 0x00FF0000 },
                layers = layers,
            ),
            "alpha 不是 255 ⇒ 不命中",
        )

        // 没有取色图（gid < 0）⇒ 不做命中判定。
        assertNull(
            CbgDrawOrder.hitCbgButton(
                px = 5, py = 5, canvasHeightPx = 20, mapWidthPx = 40, mapHeightPx = 20,
                colorAt = { _, _ -> -1 },
                layers = listOf(layers[0].copy(buttonMapGid = -1)),
            ),
        )
    }
}
