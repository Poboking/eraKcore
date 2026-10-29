package com.eratw.port.uipreview

import com.eratw.port.render.displaylist.InlineGraphic
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图片入帧（CONTRACT-UP2 验收②③）：资源名 → 文件解析（GameImageFiles 口径）→
 * javax.imageio 解码 → [InkPainter] 按段盒缩放入帧；缺资源走 AltText 降级不崩；
 * `SETBGIMAGE` 背景层准入与垫图。全合成装置：测试现造 PNG（零外部依赖）。
 *
 * 内联图形的锚点 = 发布时刻的末行实例（[ConsoleFeed.attachInline] 的「路线 2」语义），
 * 同步一口气跑完会把整批图形堆到最后一行 —— 装置因此逐页推进：VM 线程每阻塞在 INPUT，
 * 测试线程当一次泵消费者（VM 阻塞期不碰渲染态，ADR-003 单消费仍成立），页与页的锚点
 * 就落在各自那一行上。
 */
class ImageFrameTest {

    /** 图形页剧本：背景图 + 真图 / 缺失图（AltText 降级）/ 色块，三页各以 INPUT 收口。 */
    private val erb = erb(
        "@SYSTEM_TITLE",
        "SETBGIMAGE bg1, 0, 255",
        "SETBGIMAGE nosuchbg, 0, 128",
        "PRINTL IMAGE-PAGE",
        "PRINT_IMG \"img1\", 200px, 60px",
        "PRINTL",
        "INPUT",
        "PRINT_IMG \"nosuchimg\", 40px, 20px",
        "PRINTL",
        "INPUT",
        "PRINT_RECT 400px, 20px, 500px, 16px",
        "PRINTL",
        "PRINTBUTTON \"GO\", 1",
        "PRINTL",
        "INPUT",
        "QUIT",
    )

    private fun erb(vararg lines: String): String =
        "; uipreview UP2 图片入帧装置\n" + lines.joinToString("\n") { "\t$it" }

    @Test
    fun `图片按资源名入帧 段盒几何与像素落位`(@TempDir tmp: Path) {
        val rig = Rig.start(tmp, erb)
        try {
            // 资源名 → 文件解析（GameImageFiles 口径）与解码缓存。
            val frame = assertNotNull(rig.renderer.imageFrames.frame("img1"), "resources/img1.png 应命中")
            assertTrue(frame.usable)
            assertEquals(200, frame.srcW)
            assertEquals(60, frame.srcH)
            assertEquals((200 shl 16) or 60, rig.renderer.imageFrames.destSize("img1"), "ImageSizeSource 打包口径")
            assertNull(rig.renderer.imageFrames.frame("nosuchimg"), "查不到的资源返回 null（绘制侧降级）")

            // 页 1：img1 的行已提交、VM 阻塞在 INPUT —— 此刻的快照把图锚在本行。
            rig.nextPage()
            val images = rig.renderer.screen.list.render().filter { it.graphic is InlineGraphic.Image }
            assertEquals(1, images.size, "页 1 只有 img1 一段")
            val imgCmd = images[0]
            val graphic = imgCmd.graphic as InlineGraphic.Image
            assertEquals("img1", graphic.resource)
            assertEquals(200, graphic.widthPx)
            assertEquals(60, graphic.heightPx)
            assertEquals(
                InlineGraphic.cellsFor(200, rig.renderer.metrics.cellPixelWidth),
                imgCmd.colEnd - imgCmd.colStart,
                "占宽 = 像素宽折半角格（向上取整）",
            )

            // 页 2：缺资源图 + 色块。
            rig.nextPage()
            rig.nextPage()

            // 背景 opacity 摆正（core-vm 缺口的装置侧补偿）：SETBGIMAGE 的 depth/opacity 走
            // FORM 串实参，FormStringTerm.getIntValue 恒 0（FormStringTerm.kt:70）—— 语句侧
            // 永远拿到 opacity=0（原版是 long.Parse(GetStrValue)，FunctionIdentifier.cs:1539）。
            // 真语料 0 处使用 ⇒ 零读数影响，报「需组长接线」；本测试把 VM 已准入的层换成
            // 正确 opacity 验证 [InkPainter] 的垫图像素。
            rig.renderer.graphicsLayer.removeBackground("bg1")
            rig.renderer.graphicsLayer.addBackground(
                com.eratw.port.render.view.GraphicsLayer.BgPlacement("bg1", 0, 255),
            )
            rig.renderer.pumpOnce()
            val canvas = pngOf(rig.renderer)

            // 帧内像素落位：图盒内 = 解码出的红（PNG 不透明 → blend 后逐字节红）。
            val rowTop = rig.renderer.composer.rowTopPx(imgCmd.row, rig.renderer.screen.visibleRows().size)
            val boxX = rig.renderer.metrics.cellsToPixels(imgCmd.colStart)
            assertEquals(RED, canvas.getRGB(boxX + 5, rowTop + 5), "图盒内 = 解码像素")

            // 背景层：contain 等比适配 + 水平居中 + 顶对齐 —— 盒内绿、画布角落在盒外保持底色。
            assertEquals(GREEN, canvas.getRGB(700, 5), "背景图盒内 = 解码像素")
            assertEquals(
                com.eratw.port.render.view.ScreenScene.DEFAULT_BACKGROUND,
                canvas.getRGB(3, 3),
                "背景图盒外 = 底色（300x300 图 contain 进 2402x1080 左右留边）",
            )
            assertEquals(
                1,
                rig.renderer.graphicsLayer.background.size,
                "nosuchbg 未过准入（原版 is SpriteF 口径）不进背景表",
            )

            // 色块段：SETCOLOR 未设 → 出厂填充色。
            val shape = rig.renderer.screen.list.render().first { it.graphic is InlineGraphic.Rect }
            val rect = shape.graphic as InlineGraphic.Rect
            assertEquals(500, rect.widthPx)
            assertEquals(16, rect.heightPx)
            val rectTop = rig.renderer.composer.rowTopPx(shape.row, rig.renderer.screen.visibleRows().size)
            assertEquals(
                (0xFF shl 24) or 0xC0C0C0,
                canvas.getRGB(rig.renderer.metrics.cellsToPixels(shape.colStart) + 450, rectTop + 25),
                "PRINT_RECT 填充色 = 出厂 ForeColor（JvmGraphicsSink.DEFAULT_RECT_COLOR）",
            )

            // 缺资源段的 AltText 墨迹（ConsoleImagePart.cs:175-182 的降级口径）：前景 = 图形段 0xC0C0C0。
            val nosuch = rig.renderer.screen.list.render()
                .filter { it.graphic is InlineGraphic.Image }[1]
            val altTop = rig.renderer.composer.rowTopPx(nosuch.row, rig.renderer.screen.visibleRows().size)
            val grayInk = (0 until canvas.width).any { x ->
                (0 until rig.renderer.metrics.lineHeight).any { dy ->
                    canvas.getRGB(x, altTop + dy) == ((0xFF shl 24) or 0xC0C0C0)
                }
            }
            assertTrue(grayInk, "缺资源行应出现 AltText 墨迹")
        } finally {
            rig.close()
        }
    }

    /** 装配台架：VM 线程 + 手动泵（每次 INPUT 阻塞处泵一次，锚点语义见类文档）。 */
    private class Rig private constructor(
        val renderer: PreviewRenderer,
        private val vm: Thread,
    ) : AutoCloseable {

        /** 是否已有未消费的提交（决定下一页要不要等 revision 前进，见 [nextPage]）。 */
        private var pendingSubmit = false

        /**
         * 等下一页就绪 → 泵一帧 → 放行。
         *
         * 「阻塞」单独判会撞上竞态：上一页的 `awaiting` 在 VM 消费提交前一直非空，
         * 轮询会立即误判成新页、提前泵帧 + 重复提交，页序错位（本类曾因此间歇丢 Rect 段）。
         * 所以用双条件：**阻塞中** 且（无在途提交 或 sink revision 已前进 = VM 消费了提交
         * 并打出了下一页）。revision 是 @Volatile（LineBufferConsoleSink A2 收口），跨线程可读；
         * 消费（awaiting 清空）先于打印（revision 前进）发生，volatile 序保证不会读到
         * 「revision 已进、awaiting 还是旧的」的组合。
         */
        fun nextPage() {
            val before = renderer.session.console.revision
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val blocked = renderer.session.inputSink.awaiting != null || renderer.session.inputSink.awaitingAnyKey
                val progressed = !pendingSubmit || renderer.session.console.revision > before
                if (blocked && progressed) break
                Thread.sleep(5)
            }
            renderer.pumpOnce()
            renderer.session.inputSink.submit("1", false)
            pendingSubmit = true
        }

        override fun close() {
            runCatching { renderer.session.close() }
            vm.join(5_000)
        }

        companion object {
            fun start(tmp: Path, erb: String): Rig {
                val built = MiniTitle.build(tmp, erb)
                val resources = Files.createDirectories(built.gameRoot.resolve("resources"))
                writeSolidPng(resources.resolve("img1.png"), 200, 60, RED)
                writeSolidPng(resources.resolve("bg1.png"), 300, 300, GREEN)
                val session = MiniTitle.assemble(built, columns = 110)
                val renderer = PreviewRenderer(session, columns = 110, screenW = 2400, screenH = 1080)
                val vm = Thread({ runSessionLoop(session, renderer) }, "up2-image-vm")
                vm.start()
                return Rig(renderer, vm)
            }
        }
    }

    /** 发布帧的 PNG 解回 BufferedImage（画布像素的只读视图）。 */
    private fun pngOf(renderer: PreviewRenderer): BufferedImage =
        ImageIO.read(ByteArrayInputStream(assertNotNull(renderer.currentFrame).png))

    private companion object {
        const val RED = (0xFF shl 24) or 0xFF0000
        const val GREEN = (0xFF shl 24) or 0x00FF00

        /** 纯色不透明 PNG（测试装置，零外部依赖）。 */
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
