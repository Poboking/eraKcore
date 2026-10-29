package com.eratw.port.uipreview

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * 代表帧画廊生成器（CONTRACT-UP2 §4.6）：`-Deratw.up2.gallery=1` 才跑（缺省 skip，
 * 闸门零成本），产三张代表帧 PNG 到 `.tmp/up2-gallery/`（**不入库**，交付消息给路径）：
 *
 * 1. `up2-title.png` —— 标题页：真字形（中/日/拉丁混排、粗体）+ 按钮；
 * 2. `up2-images.png` —— 图片页：`PRINT_IMG` 内联图（渐变 PNG）+ `PRINT_RECT` 色块条；
 * 3. `up2-background.png` —— 背景页：`SETBGIMAGE` 半透明背景垫图 + 真字形。
 *
 * 图片全由测试程序化现造（零外部依赖、确定性渐变）；真语料 serve 出帧由人手动验收。
 * 输出目录：`-Deratw.up2.gallery.out=` 覆盖，缺省 `<工程根>/../../.tmp/up2-gallery`
 * （从 Gradle 测试工作目录 = 模块目录推算）。
 */
class GalleryPngTest {

    @Test
    fun `产三张代表帧`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("eratw.up2.gallery") == "1",
            "画廊生成器默认跳过；手动跑：./gradlew :tools:uipreview:test --tests '*GalleryPngTest*' -Deratw.up2.gallery=1",
        )
        val outDir = Path.of(
            System.getProperty("eratw.up2.gallery.out")
                ?: Path.of(System.getProperty("user.dir"), "..", "..", ".tmp", "up2-gallery").normalize().toString(),
        )
        Files.createDirectories(outDir)
        val erb = galleryErb()
        val tmp = Files.createTempDirectory("up2-gallery")
        try {
            val built = MiniTitle.build(tmp, erb)
            val resources = Files.createDirectories(built.gameRoot.resolve("resources"))
            writeGradientPng(resources.resolve("gallery1.png"), 480, 120)
            writeRadialPng(resources.resolve("gallerybg.png"), 640, 400)
            val session = MiniTitle.assemble(built, columns = 110)
            val renderer = PreviewRenderer(session, columns = 110, screenW = 2400, screenH = 1080)
            val vm = Thread({ runSessionLoop(session, renderer) }, "up2-gallery-vm")
            vm.start()
            try {
                shot(renderer, outDir.resolve("up2-title.png"))
                advance(renderer)
                shot(renderer, outDir.resolve("up2-images.png"))
                advance(renderer)
                // SETBGIMAGE 的 opacity 经 core 侧 FORM 实参恒读到 0（FormStringTerm.getIntValue
                // 恒 0，FormStringTerm.kt:70；原版是 long.Parse(GetStrValue)，FunctionIdentifier.cs:1539
                // —— 需组长接线）。画廊把已准入的层换成目标 opacity 让垫图可见。
                awaitBlocked(renderer)
                renderer.graphicsLayer.removeBackground("gallerybg")
                renderer.graphicsLayer.addBackground(
                    com.eratw.port.render.view.GraphicsLayer.BgPlacement("gallerybg", 0, 160),
                )
                shot(renderer, outDir.resolve("up2-background.png"))
            } finally {
                runCatching { session.close() }
                vm.join(5_000)
            }
            println("up2-gallery 产物：$outDir")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** 画廊剧本：标题（真字形+按钮）/ 图片（内联图+色块）/ 背景（半透明垫图）三页。 */
    private fun galleryErb(): String {
        val lines = listOf(
            "@SYSTEM_TITLE",
            "PRINTL UIPREVIEW UP2 —— 真字形与图片入帧",
            "PRINTL 0123456789 ABCDEF abcdef",
            "PRINTL かなカナ漢字、混合排版。",
            """PRINTBUTTON "[ 查看图片页 ]", 1""",
            "PRINTL",
            "INPUT",
            "PRINT_IMG \"gallery1\", 480px, 120px",
            "PRINTL",
            "PRINT_RECT 100px, 24px, 900px, 20px",
            "PRINTL",
            """PRINTBUTTON "[ 查看背景页 ]", 2""",
            "PRINTL",
            "INPUT",
            "SETBGIMAGE gallerybg, 0, 160",
            "PRINTL 背景页：SETBGIMAGE 半透明垫图 + 真字形墨迹。",
            """PRINTBUTTON "[ 结束 ]", 3""",
            "PRINTL",
            "INPUT",
            "QUIT",
        )
        return "; uipreview UP2 画廊装置\n" + lines.joinToString("\n") { "\t$it" }
    }

    /** 在途提交标志（首调无提交可消费，免等 revision；双条件防旧 awaiting 误判，同 ImageFrameTest.Rig）。 */
    private var pendingSubmit = false

    /** 等阻塞 → 泵帧 → 放行（双条件防旧 awaiting 误判，同 ImageFrameTest.Rig.nextPage）。 */
    private fun advance(renderer: PreviewRenderer) {
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

    /** 轮询等 VM 阻塞到下一次输入等待（SETBGIMAGE 已执行完的时点）。 */
    private fun awaitBlocked(renderer: PreviewRenderer) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (renderer.session.inputSink.awaiting != null || renderer.session.inputSink.awaitingAnyKey) return
            Thread.sleep(5)
        }
        throw IllegalStateException("VM 未在 10s 内阻塞到输入等待")
    }

    /** 等最终帧并写 PNG。 */
    private fun shot(renderer: PreviewRenderer, out: Path) {
        val deadline = System.currentTimeMillis() + 10_000
        var last = renderer.currentFrame
        val startRev = last?.frameRev ?: 0L
        while (System.currentTimeMillis() < deadline) {
            last = renderer.currentFrame
            if (last != null && last.frameRev > startRev) break
            renderer.pumpOnce()
            Thread.sleep(20)
        }
        val shot = assertNotNull(last, "画廊页必须出帧")
        // 泵一次保底刷新（阻塞期内容已就绪），再写盘。
        renderer.pumpOnce()
        val final = assertNotNull(renderer.currentFrame)
        ImageIO.write(
            BufferedImage(shot.width, shot.height, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, final.width, final.height, decodePixels(final), 0, final.width)
            },
            "png",
            out.toFile(),
        )
        println("up2-gallery: ${out.fileName} ${final.width}x${final.height}")
    }

    private fun decodePixels(shot: PreviewRenderer.FrameShot): IntArray {
        val image = ImageIO.read(shot.png.inputStream())
        val pixels = IntArray(shot.width * shot.height)
        image.getRGB(0, 0, shot.width, shot.height, pixels, 0, shot.width)
        return pixels
    }

    /** 确定性水平渐变 PNG（紫→青），装置用。 */
    private fun writeGradientPng(path: Path, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = x.toFloat() / (width - 1).coerceAtLeast(1)
                val r = (0x70 + (0x30 - 0x70) * t).toInt().coerceIn(0, 255)
                val g = (0x30 + (0xC0 - 0x30) * t).toInt().coerceIn(0, 255)
                val b = (0xC0 + (0xF0 - 0xC0) * t).toInt().coerceIn(0, 255)
                image.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(image, "png", path.toFile())
    }

    /** 确定性同心环 PNG（背景垫图装置用）。 */
    private fun writeRadialPng(path: Path, width: Int, height: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val cx = width / 2f
        val cy = height / 2f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = Math.sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
                val band = (d / 24).toInt() % 2 == 0
                val r = if (band) 0x40 else 0x18
                val g = if (band) 0x60 else 0x28
                val b = if (band) 0xA0 else 0x48
                image.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(image, "png", path.toFile())
    }
}
