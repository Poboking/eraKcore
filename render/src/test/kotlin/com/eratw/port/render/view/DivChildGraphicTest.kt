package com.eratw.port.render.view

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * div 子行的**图形落像素**与**按钮焦点/按下**（CONTRACT-DIV-HIT2 B 路）。
 *
 * 断言的原版语义（相对 reference/emuera-cs/）：
 * - `<img>` 落点 `x = 列游标 + ShapePositionShift`、`y = 行顶 + ypos`、
 *   尺寸 `ConsoleImagePart.cs:66-93`、框内落位 `ASpriteSingle.cs:79-85`：
 *   `ConsoleImagePart.DrawTo` :159-174（`:170` `rectangle = destRect`、`:171` 加 `PointX + shift`、
 *   `:172` 加 `pointY`）；
 * - 缺图不画：`:168` 的 `aSprite != null && aSprite.IsCreated` 守卫；
 * - 内容盒裁剪：`ConsoleDivPart.cs:195` 的 `SetClip`；
 * - 位图焦点/按下换 `srcb`：`ConsoleImagePart.cs:163-167`；
 * - `<shape type='rect'>` 填充与位移：`ConsoleRectangleShapePart.cs:44`（`X += PointX`）、
 *   `:45`（`color = isSelecting ? ButtonColor : Color`）、`:57`（`X += DrawingParam_ShapePositionShift`）；
 * - 文字焦点色：`ConsoleStyledString.cs:105-111`（`color = base.ButtonColor`），
 *   `ButtonColor` 默认 = `Config.FocusColor` `0xFFFF00`（`StringStyle.cs:11,22`；
 *   基准 `pc-golden-root.txt:269` 的 `FOCUS=16776960`）；
 * - 按下底色：`RunChrome.PRESSED_BACK`（原版按钮按下高亮，与正文行同一条通道）。
 *
 * 全部用例只吃 headless 栅格（`:render` 纯 JVM），像素读数走 `Raster.pixelAt`。
 */
class DivChildGraphicTest {

    private val metrics = CellMetrics(18, 18)

    private val composer = ScreenComposer(
        clientWidthCells = 100,
        heightLines = 20,
        metrics = metrics,
        canvasHeightPx = 20 * 18,
    )

    private val background = ScreenScene.DEFAULT_BACKGROUND
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    /** 焦点字色 / 色块焦点填充：`Config.FocusColor` `0xFFFF00` 补不透明位（StringStyle.cs:11,22）。 */
    private val focus = 0xFFFFFF00.toInt()

    /** 块锚定的行条顶（`ConsoleDivPart.DrawTo` 的 pointY；EmueraConsole.cs:1870）。 */
    private val anchorTopPx = 10

    /**
     * 一颗覆盖块（relative、已逃逸、无盒模型内缩）→ 规划产物。
     *
     * `widthPx`/`heightPx` 是脚本尺寸；规划后 `borderRectPx.width = widthPx + 2`
     * （CDP :178 的官方 fudge），内容盒无内缩时与边框矩形同形。
     */
    private fun block(
        children: List<String>,
        widthPx: Int = 100,
        heightPx: Int = 60,
    ): DivOverlayPlanner.Block {
        val raw = DisplayDivBlock(
            altHeadTag = "<div>",
            anchorCol = 0,
            anchorPointXPx = 0,
            xOffsetPx = 0,
            divXOffsetPx = 0,
            pointYPx = 0,
            yOffsetPx = 0,
            widthPx = widthPx,
            heightPx = heightPx,
            subDivisionWidthPx = widthPx,
            depth = 0,
            backgroundColor = null,
            margin = emptyList(),
            padding = emptyList(),
            border = emptyList(),
            radius = emptyList(),
            borderColors = emptyList(),
            isRelative = true,
            isEscaped = true,
            children = children.map { DisplayDivChildLine(it) },
        )
        val plan = composer.divPlanner.plan(
            blocks = listOf(raw),
            anchorTopPx = anchorTopPx,
            anchorPointXPx = 0,
            canvasHeightPx = composer.canvasHeightPx,
            visibleTopLine = 0,
            visibleBottomLine = 100,
            anchorLineNo = 0,
        )
        return plan.all.single()
    }

    /** 覆盖帧：depth 0 的块落在 front 层，behind 空 ⇒ `Plan.all` 下标 = 0。 */
    private fun overlay(block: DivOverlayPlanner.Block): DivOverlayFrame =
        DivOverlayFrame(emptyList(), listOf(block))

    private fun compose(
        block: DivOverlayPlanner.Block,
        sprites: SpriteTable = SpriteTable(),
        focused: ButtonLayer.DivFocus? = null,
        pressed: ButtonLayer.DivFocus? = null,
    ): Raster = composer.compose(
        commands = emptyList(),
        rowCount = 20,
        scene = ScreenScene(),
        sprites = sprites,
        overlay = overlay(block),
        focusedDiv = focused,
        pressedDiv = pressed,
    )

    private fun solid(width: Int, height: Int, argb: Int): Raster =
        Raster(width, height).also { it.fillRect(0, 0, width, height, argb) }

    private fun table(vararg entries: Pair<String, Raster>): SpriteTable {
        val sprites = SpriteTable()
        for ((name, raster) in entries) sprites.add(name, raster)
        return sprites
    }

    @Test
    fun `子行 img 按一像素贴到列游标加形状位移处`() {
        // 列游标 0 → x = 0 + shift 3（max(2, 18/6)，Config.cs:461-465）；y = 行顶 10 + ypos 0。
        val sprites = table("RED" to solid(20, 20, red))
        val canvas = compose(block(listOf("<img src='RED' width='20px' height='20px'>")), sprites)
        assertEquals(red, canvas.pixelAt(3, 10), "图左上角 = (列游标 0 + shift 3, 行顶 10)")
        assertEquals(red, canvas.pixelAt(22, 29), "20×20 的 1:1 贴图铺满 (3,10)-(23,30)")
        assertEquals(background, canvas.pixelAt(2, 10), "shift 之前的那 3 像素不落图")
        assertEquals(background, canvas.pixelAt(23, 10), "图右沿之外（20px 宽）")
    }

    @Test
    fun `子行 img 资源缺失静默跳过`() {
        // ConsoleImagePart.cs:168 的 IsCreated 守卫：查不到 sprite 就什么都不画、也不抛。
        val canvas = compose(
            block(listOf("<img src='NO_SUCH_SPRITE' width='20px' height='20px'>")),
            SpriteTable(),
        )
        assertEquals(background, canvas.pixelAt(3, 10))
        assertEquals(background, canvas.pixelAt(22, 29))
    }

    @Test
    fun `子行 img 超出内容盒右沿与下沿被裁`() {
        // 内容盒 = (0, 10, 22, 20)（width 20 + fudge 2）；CDP :195 的 SetClip 把溢出部分裁掉。
        val sprites = table("BIG" to solid(40, 40, red))
        val canvas = compose(
            block(listOf("<img src='BIG' width='40px' height='40px'>"), widthPx = 20, heightPx = 20),
            sprites,
        )
        assertEquals(red, canvas.pixelAt(21, 20), "盒内最后一列仍落图")
        assertEquals(background, canvas.pixelAt(22, 20), "内容盒右沿外不落图")
        assertEquals(background, canvas.pixelAt(3, 30), "内容盒下沿外不落图")
    }

    @Test
    fun `子行 shape rect 填充不透明色`() {
        // 单参 rect：宽取 param、高 = Config.FontSize（ConsoleShapePart.cs:50-69）。
        val canvas = compose(block(listOf("<shape type='rect' param='20px' color='#00FF00'>")))
        assertEquals(green, canvas.pixelAt(3, 10), "x = 列游标 0 + shift 3（ShapeType cs:57）")
        assertEquals(green, canvas.pixelAt(22, 27), "20×18 铺满 (3,10)-(23,28)")
        assertEquals(background, canvas.pixelAt(23, 10), "rect 右沿之外")
    }

    @Test
    fun `子行按钮焦点时文字换成 focusFore`() {
        val button = block(listOf("<button value='7'>AB</button>"))
        val plain = compose(button)
        assertEquals(0xFFC0C0C0.toInt(), plain.pixelAt(1, 13), "常态用段前景色（ConsoleStyledString.cs:103）")
        val focused = compose(button, focused = ButtonLayer.DivFocus(0, 0, 0, 2))
        assertEquals(focus, focused.pixelAt(1, 13), "焦点换 ButtonColor（:105-111）")
    }

    @Test
    fun `子行按钮按下时叠 PRESSED_BACK 底色`() {
        val canvas = compose(
            block(listOf("<button value='7'>AB</button>")),
            pressed = ButtonLayer.DivFocus(0, 0, 0, 2),
        )
        // 两字之间 1 像素缝（ScreenComposer.GLYPH_GAP_PX）只铺底色：0x66FFFFFF 合成到黑底
        // = 0xFF666666（Raster.blend 的 source-over，A=102）。
        assertEquals(0xFF666666.toInt(), canvas.pixelAt(8, 13))
    }

    @Test
    fun `子行按钮位图焦点时换成 srcb 资源`() {
        val sprites = table("RED" to solid(20, 20, red), "BLUE" to solid(20, 20, blue))
        val button = block(listOf("<img src='RED' srcb='BLUE' width='20px' height='20px'>"))
        val plain = compose(button, sprites)
        assertEquals(red, plain.pixelAt(3, 10), "常态画主图")
        val focused = compose(button, sprites, focused = ButtonLayer.DivFocus(0, 0, 0, 3))
        assertEquals(blue, focused.pixelAt(3, 10), "焦点/按下换 srcb（ConsoleImagePart.cs:163-167）")
    }

    @Test
    fun `子行 shape rect 焦点时换成 ButtonColor`() {
        // ConsoleRectangleShapePart.cs:45 的 `color = isSelecting ? base.ButtonColor : base.Color`。
        val canvas = compose(
            block(listOf("<shape type='rect' param='20px' color='#00FF00'>")),
            focused = ButtonLayer.DivFocus(0, 0, 0, 3),
        )
        assertEquals(focus, canvas.pixelAt(3, 10))
    }
}
