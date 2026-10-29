package com.eratw.port.uipreview

import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 几何断言（CONTRACT-UP1 §3 单测①）：mini 剧本 boot → 出帧，断言画布尺寸与行列几何。
 * 反推式在 CONTRACT-UP1 §4.3 写死：540x960 + 40 列 ⇒ 字号 26、行高 27、画布 540x960、35 行。
 */
class GeometryTest {

    @Test
    fun `boot 后出帧且画布几何与反推式一致`(@TempDir tmp: Path) {
        val built = MiniTitle.build(tmp, MiniTitle.ERB_AUTOQUIT)
        val session = MiniTitle.assemble(built, columns = 40)
        val renderer = PreviewRenderer(session, columns = 40, screenW = 540, screenH = 960)

        // 反推式：font = round(540/(40*0.52)) = 25.96，floor 25 ⇒ 宽 520、ceil 26 ⇒ 宽 540，取 26。
        assertEquals(26, renderer.fontPixelSize)
        // 行高 = max(26, round(26*19/18)=27) = 27；heightLines = 960/27 = 35。
        assertEquals(27, renderer.metrics.lineHeight)
        assertEquals(35, renderer.heightLines)
        // 画布：宽 = cellsToPixels(40)（540 精确命中），高显式传 --screen 高。
        assertEquals(540, renderer.composer.canvasWidthPx)
        assertEquals(960, renderer.composer.canvasHeightPx)
        // core 侧与 render 侧的窗口宽必须同值（ConsoleFeed 构造期要求）。
        assertEquals(40, session.console.clientWidth)
        assertEquals(40, renderer.screen.clientWidth)
        assertEquals(40, renderer.feed.sink.clientWidth)

        // boot：脚本无输入等待，BEGIN TITLE 一次推进到 QUIT。
        val terminal = runSessionLoop(session, renderer)
        assertEquals(TerminalKind.QUIT, terminal.kind)
        assertTrue(renderer.vmEnded)

        // 出帧：首帧强制发布，PNG 尺寸 = 画布尺寸。
        assertTrue(renderer.pumpOnce(), "首帧必须发布")
        val shot = assertNotNull(renderer.currentFrame)
        assertEquals(540, shot.width)
        assertEquals(960, shot.height)
        val img = ImageIO.read(ByteArrayInputStream(shot.png))
        assertNotNull(img, "PNG 必须能被 ImageIO 解回")
        assertEquals(540, img.width)
        assertEquals(960, img.height)

        // 行列几何：按钮表来自 ButtonLayer（真机同一命中层），命中窗口行数在屏容量内。
        val ui = assertNotNull(renderer.currentUi)
        assertEquals(1, ui.buttons.size)
        assertEquals("START", ui.buttons[0].label)
        assertEquals(1L, ui.buttons[0].value)
        assertTrue(renderer.screen.hitRows().isNotEmpty())
        assertTrue(renderer.screen.hitRows().size <= renderer.heightLines)
        // 按钮中心像素在画布内。
        assertTrue(ui.buttons[0].xPx in 0 until 540)
        assertTrue(ui.buttons[0].yPx in 0 until 960)
        session.close()
    }

    @Test
    fun `字号取整后画布宽与目标宽的差不超过列数`(@TempDir tmp: Path) {
        // 抽两组非整除尺寸，钉「偏差 ≤ columns px」的登记口径（ScreenLayout.fixedColumns）。
        for (pair in listOf(1080 to 2400, 800 to 600)) {
            val (w, h) = pair
            val columns = 110
            val built = MiniTitle.build(tmp.resolve("g$w"), MiniTitle.ERB_AUTOQUIT)
            val session = MiniTitle.assemble(built, columns)
            val renderer = PreviewRenderer(session, columns, w, h)
            val diff = kotlin.math.abs(renderer.composer.canvasWidthPx - w)
            assertTrue(diff <= columns, "W=$w：画布宽 ${renderer.composer.canvasWidthPx} 与 $w 差 $diff 应 ≤ columns=$columns")
            assertEquals(h, renderer.composer.canvasHeightPx, "画布高必须精确 = --screen 高")
            session.close()
        }
    }

    @Test
    fun `极端宽低于字号下限走 18dp 密度兜底`(@TempDir tmp: Path) {
        // 390px/110 列：反推 ≈6.8px < CellMetrics.MIN_FONT_PIXEL_SIZE ⇒ 真机权威
        // （ScreenLayout.fixedColumns）= 18dp × densityDpi/160 兜底；裸 --screen 无档案
        // ⇒ densityDpi=160 ⇒ 18px、行高 19。画布宽 110*18/2*1.04 = 1029.6 → 1029，
        // 与 390 的差远超 columns —— 这是真机行为（View 以兜底字号绘制、列段被裁），
        // UP1 旧反推式在此档贴 8px 下限 ≠ 真机，组长接线后改钉权威口径。
        val built = MiniTitle.build(tmp.resolve("g390"), MiniTitle.ERB_AUTOQUIT)
        val session = MiniTitle.assemble(built, 110)
        val renderer = PreviewRenderer(session, columns = 110, screenW = 390, screenH = 844)
        assertEquals(18, renderer.fontPixelSize)
        assertEquals(19, renderer.metrics.lineHeight)
        assertEquals(1029, renderer.composer.canvasWidthPx)
        session.close()
    }
}
