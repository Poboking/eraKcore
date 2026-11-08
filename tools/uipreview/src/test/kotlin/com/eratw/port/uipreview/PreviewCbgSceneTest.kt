package com.eratw.port.uipreview

import com.eratw.port.core.host.CbgLayerSnapshot
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D 刀：`PreviewRenderer` 组 [com.eratw.port.render.view.ScreenScene] 时把
 * `GraphicsLayer.cbgLayers` 带上（预览显示 CBG）。
 *
 * ## 判据
 *
 * 预览侧唯一的 CBG 显示通道就是 `frame.draw(screen, rects, scene, sprites)` 的
 * scene / sprite 表参数：`ScreenComposer` 只从 scene 读 CBG 图层
 * （`ScreenScene.cbgLayers`），名字再从 [com.eratw.port.render.sprite.SpriteTable] 解析。
 * 本用例因此**不看中间对象**，直接断像素：往 `graphicsLayer` 压一条 CBG 快照（名字指向
 * 游戏根里的真 PNG），泵一帧，PNG 上该出现那块颜色。
 *
 * 装置只走「泵线程 + 文件兜底图」这一条，不起 VM 线程：`JvmImageFrames.frame(name)` 在
 * sprite 表查不到时按名扫 `resources/`（其类文档的「查表顺序」第 2 跳），正好覆盖
 * 「CBGSETSPRITE 指向一张资源图」的形态。
 */
class PreviewCbgSceneTest {

    private fun erb(vararg lines: String): String =
        "; uipreview CBG scene 装置\n" + lines.joinToString("\n") { "\t$it" }

    @Test
    fun `CBG 图层进 scene 并按帧几何上屏`(@TempDir tmp: Path) {
        // 画布 2400x1080；100 列 ⇒ 字号/行高由 ScreenLayout 反推（几何断言用 composer 的读数）。
        val built = MiniTitle.build(tmp, erb("@SYSTEM_TITLE", "PRINTL CBG-SCENE", "QUIT"))
        val resources = Files.createDirectories(built.gameRoot.resolve("resources"))
        writeSolidPng(resources.resolve("cbg1.png"), 40, 20, GREEN)

        val session = MiniTitle.assemble(built, columns = 110)
        val renderer = PreviewRenderer(session, columns = 110, screenW = 2400, screenH = 1080)
        try {
            // CBGSETSPRITE 的落点形态：名字 + 下沿锚定 y=0 + depth 1000（垫底）。
            val layer = CbgLayerSnapshot(
                depth = 1000,
                x = 0,
                y = 0,
                spriteName = "cbg1",
                gid = -1,
                widthPx = 40,
                heightPx = 20,
                baseHeightPx = 20,
            )
            renderer.graphicsLayer.setCbgLayers(listOf(layer))

            assertTrue(renderer.pumpOnce(), "有 CBG 图层 ⇒ 必须出一帧")
            val png = pngOf(renderer)
            val top = renderer.composer.canvasHeightPx - 20
            assertEquals(GREEN, png.getRGB(5, top + 5), "CBG 下沿锚定：y = 画布高 - baseHeightPx")
            assertEquals(GREEN, png.getRGB(39, top + 19), "右下角在图层内")
            assertEquals(
                com.eratw.port.render.view.ScreenScene.DEFAULT_BACKGROUND,
                png.getRGB(60, top + 5),
                "图层宽 40 之外仍是底色",
            )

            // CBGCLEAR 的形态：清空后同一块位置回底色（scene 跟着 layer 换代）。
            renderer.graphicsLayer.setCbgLayers(emptyList())
            assertTrue(renderer.pumpOnce(), "层换代 ⇒ 重画")
            val cleared = pngOf(renderer)
            assertEquals(
                com.eratw.port.render.view.ScreenScene.DEFAULT_BACKGROUND,
                cleared.getRGB(5, top + 5),
                "CBG 清空后回底色",
            )
        } finally {
            renderer.close()
        }
    }

    private fun pngOf(renderer: PreviewRenderer): BufferedImage =
        ImageIO.read(ByteArrayInputStream(assertNotNull(renderer.currentFrame).png))

    private companion object {
        const val GREEN = (0xFF shl 24) or 0x00FF00

        /** 纯色不透明 PNG（测试装置，零外部依赖；与 ImageFrameTest 同款）。 */
        fun writeSolidPng(path: Path, width: Int, height: Int, argb: Int) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val rgb = argb and 0xFFFFFF
            for (y in 0 until height) {
                for (x in 0 until width) {
                    image.setRGB(x, y, (0xFF shl 24) or rgb)
                }
            }
            ImageIO.write(image, "png", path.toFile())
        }
    }
}
