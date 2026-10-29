package com.eratw.port.core.image

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.unsignedArgb
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.Assumptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PNG 解码器与 `GCREATEFROMFILE` / `GLOAD` 接线的单测。
 *
 * - 合成用例**手写 PNG 字节**（签名 + IHDR + PLTE + tRNS + IDAT(zlib) + IEND，块 CRC 用
 *   `java.util.zip.CRC32` 生成），不依赖 Android / ImageIO / 任何外部库；
 * - 真实语料用例走 `ERATW_CORPUS`（从环境变量路径向上找 `resources`），目录不在时
 *   `Assumptions.assumeTrue` 跳过；期望像素由 Windows System.Drawing 读同一文件取得
 *   （GDI+ 是原版 `Utils.LoadImage` 的 oracle）。
 *
 * 覆盖契约 PNG-DECODE：色型 0/2/3/4/6、过滤 0..4、位深 8、调色板 tRNS、多 IDAT、
 * 坏签名 / 截断 / 坏 zlib、非 PNG 回落为 0、GSAVE 文本载体不回退。
 */
class PngDecoderTest {

    // ==================================================================
    // 合成 PNG
    // ==================================================================

    @Test
    fun `签名判定只认前 8 字节`() {
        val png = pngBytes(1, 1, 0, listOf(byteArrayOf(7)))
        assertTrue(PngDecoder.isPng(png))
        assertFalse(PngDecoder.isPng(ByteArray(0)))
        assertFalse(PngDecoder.isPng("not a png".toByteArray()))
        assertFalse(PngDecoder.isPng(PNG_SIGNATURE.copyOf(7)))
        assertTrue(PngDecoder.isPng(PNG_SIGNATURE.copyOf(8)))
    }

    @Test
    fun `色型 0 灰度 8 位 2x2`() {
        val bytes = pngBytes(
            2, 2, 0,
            listOf(byteArrayOf(0, 127), byteArrayOf(128.toByte(), 255.toByte())),
        )
        val img = PngDecoder.decode(bytes)
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertPixels(
            img,
            argb(255, 0, 0, 0),
            argb(255, 127, 127, 127),
            argb(255, 128, 128, 128),
            argb(255, 255, 255, 255),
        )
    }

    @Test
    fun `色型 0 tRNS 颜色键的像素 alpha 为 0 且保留灰度`() {
        val bytes = pngBytes(
            2, 2, 0,
            listOf(byteArrayOf(10, 20), byteArrayOf(30, 20)),
            trns = byteArrayOf(0, 20),
        )
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(255, 10, 10, 10),
            argb(0, 20, 20, 20),
            argb(255, 30, 30, 30),
            argb(0, 20, 20, 20),
        )
    }

    @Test
    fun `色型 2 RGB 8 位 2x2 含 tRNS 颜色键`() {
        val bytes = pngBytes(
            2, 2, 2,
            listOf(
                byteArrayOf(1, 2, 3, 0, 255.toByte(), 0),
                byteArrayOf(0, 255.toByte(), 0, 9, 8, 7),
            ),
            trns = byteArrayOf(0, 0, 0, 255.toByte(), 0, 0),
        )
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(255, 1, 2, 3),
            argb(0, 0, 255, 0),
            argb(0, 0, 255, 0),
            argb(255, 9, 8, 7),
        )
    }

    @Test
    fun `色型 3 调色板加 tRNS 2x2`() {
        val bytes = pngBytes(
            2, 2, 3,
            listOf(byteArrayOf(0, 1), byteArrayOf(1, 0)),
            palette = byteArrayOf(
                255.toByte(), 0, 0,
                0, 255.toByte(), 0,
            ),
            trns = byteArrayOf(255.toByte(), 0),
        )
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(255, 255, 0, 0),
            argb(0, 0, 255, 0),
            argb(0, 0, 255, 0),
            argb(255, 255, 0, 0),
        )
    }

    @Test
    fun `色型 4 灰度加 alpha 2x2`() {
        val bytes = pngBytes(
            2, 2, 4,
            listOf(
                byteArrayOf(100, 0, 200.toByte(), 128.toByte()),
                byteArrayOf(50, 255.toByte(), 0, 0),
            ),
        )
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(0, 100, 100, 100),
            argb(128, 200, 200, 200),
            argb(255, 50, 50, 50),
            0,
        )
    }

    @Test
    fun `色型 6 RGBA 2x2`() {
        val bytes = pngBytes(
            2, 2, 6,
            listOf(
                byteArrayOf(200.toByte(), 100, 50, 0, 1, 2, 3, 128.toByte()),
                byteArrayOf(9, 8, 7, 255.toByte(), 0, 0, 0, 0),
            ),
        )
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(0, 200, 100, 50),
            argb(128, 1, 2, 3),
            argb(255, 9, 8, 7),
            0,
        )
    }

    @Test
    fun `过滤器 0 到 4 各一行逐字节还原`() {
        val bpp = 3
        val rows = listOf(
            byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte(), 90),
            byteArrayOf(1, 2, 3, 200.toByte(), 150.toByte(), 100, 0, 255.toByte(), 128.toByte()),
            byteArrayOf(255.toByte(), 0, 64, 32, 16, 8, 9, 10, 11),
            byteArrayOf(17, 34, 51, 68, 85, 102, 119, 136.toByte(), 153.toByte()),
            byteArrayOf(5, 5, 5, 250.toByte(), 240.toByte(), 230.toByte(), 12, 13, 14),
        )
        val bytes = pngBytes(3, 5, 2, rows, filters = listOf(0, 1, 2, 3, 4))
        val img = PngDecoder.decode(bytes)
        assertEquals(3, img.width)
        assertEquals(5, img.height)
        var i = 0
        for (row in rows) {
            for (x in 0 until 3) {
                val o = x * bpp
                assertEquals(
                    argb(255, row[o].toInt() and 0xFF, row[o + 1].toInt() and 0xFF, row[o + 2].toInt() and 0xFF),
                    img.pixels[i++],
                    "row=${i / 3} x=$x",
                )
            }
        }
    }

    @Test
    fun `多个 IDAT 块按 zlib 流拼接`() {
        val rows = listOf(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            byteArrayOf(7, 8, 9, 10, 11, 12),
        )
        val bytes = pngBytes(2, 2, 2, rows, idatChunks = 3)
        val img = PngDecoder.decode(bytes)
        assertPixels(
            img,
            argb(255, 1, 2, 3),
            argb(255, 4, 5, 6),
            argb(255, 7, 8, 9),
            argb(255, 10, 11, 12),
        )
    }

    @Test
    fun `CRC 不参与判定（GDI+ 对坏 CRC 仍解码）`() {
        // 语料 resources 里的 im_av_chara_yellow.png 就有一个 CRC 错误的 iCCP 块，
        // System.Drawing 仍能读出 180x180；这里把 IDAT 的 CRC 也打坏，验证同样容忍。
        val bytes = pngBytes(1, 1, 2, listOf(byteArrayOf(9, 8, 7)))
        val corrupted = flipCrcOf(bytes, "IDAT")
        val img = PngDecoder.decode(corrupted)
        assertPixels(img, argb(255, 9, 8, 7))
    }

    // ==================================================================
    // 坏输入 / 不支持子集
    // ==================================================================

    @Test
    fun `坏签名直接拒绝`() {
        val broken = pngBytes(1, 1, 0, listOf(byteArrayOf(1))).copyOf()
        broken[1] = 0x00
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(broken) }
        assertTrue(ex.message!!.contains("签名"), ex.message)
    }

    @Test
    fun `截断的 PNG 拒绝`() {
        val full = pngBytes(2, 2, 2, listOf(byteArrayOf(1, 2, 3, 4, 5, 6), byteArrayOf(7, 8, 9, 10, 11, 12)))
        val noIend = assertFailsWith<PngDecodeException> { PngDecoder.decode(full.copyOf(full.size - 12)) }
        assertTrue(noIend.message!!.contains("IEND"), noIend.message)

        val half = assertFailsWith<PngDecodeException> { PngDecoder.decode(full.copyOf(full.size / 2)) }
        assertTrue(half.message!!.contains("截断"), half.message)

        val headerOnly = assertFailsWith<PngDecodeException> { PngDecoder.decode(full.copyOf(20)) }
        assertTrue(headerOnly.message!!.contains("截断"), headerOnly.message)

        val signatureOnly = assertFailsWith<PngDecodeException> { PngDecoder.decode(PNG_SIGNATURE.copyOf()) }
        assertTrue(signatureOnly.message!!.contains("IEND"), signatureOnly.message)
    }

    @Test
    fun `坏 zlib 流拒绝`() {
        val bytes = pngBytes(2, 1, 2, listOf(byteArrayOf(1, 2, 3, 4, 5, 6)), idat = byteArrayOf(1, 2, 3, 4, 5))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("IDAT"), ex.message)
    }

    @Test
    fun `非 8 位深明确拒绝`() {
        val bytes = pngBytes(1, 1, 0, listOf(byteArrayOf(1, 2)), bitDepth = 16)
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("位深 16"), ex.message)
    }

    @Test
    fun `隔行扫描明确拒绝`() {
        val bytes = pngBytes(1, 1, 2, listOf(byteArrayOf(1, 2, 3)), interlace = 1)
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("隔行"), ex.message)
    }

    @Test
    fun `未知色型明确拒绝`() {
        val bytes = pngBytes(1, 1, 5, listOf(byteArrayOf(1)))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("色型 5"), ex.message)
    }

    @Test
    fun `尺寸超过 8192 明确拒绝`() {
        val bytes = pngBytes(8193, 1, 0, listOf(ByteArray(8193)))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("8192"), ex.message)
    }

    /**
     * 像素总数守卫：单边都在原版 8192 上限内（FunctionMethodCreator.cs:6905-6908 只限单边）、
     * 原版对这种巨图是 GDI+ 分配失败抛 OutOfMemoryException 后被 `Utils.LoadImage` 的空
     * catch（Utils.cs:521-523）折成 null → 函数返回 0；本解码器在分配解压缓冲/像素数组
     * **之前**给同一结局（GraphicsBuffer.MAX_IMAGE_PIXELS），不许把 OutOfMemoryError
     * 漏给只捕 `Exception` 的函数层（GraphicsMethods.kt:971-975）。
     */
    @Test
    fun `像素总数超限在分配前明确拒绝`() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ihdr(8192, 8192, 6, 8, 0) + chunk("IEND", ByteArray(0))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("像素总数超限"), ex.message)
    }

    /** 语料普查最大 8192x4000 = 32,768,000 px < 上限，不许被像素总数守卫误杀。 */
    @Test
    fun `语料上限内的大图不触发像素总数守卫`() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ihdr(8192, 4000, 6, 8, 0) + chunk("IEND", ByteArray(0))
        // IHDR 之后没有 IDAT/IEND 之外的载荷：只会败在「缺少 IEND」之前的结构检查上，
        // 无论败在哪条，都不许是像素总数超限。
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertFalse(ex.message!!.contains("超限"), ex.message)
    }

    @Test
    fun `调色板缺失与索引越界`() {
        val noPlte = pngBytes(1, 1, 3, listOf(byteArrayOf(0)))
        val ex1 = assertFailsWith<PngDecodeException> { PngDecoder.decode(noPlte) }
        assertTrue(ex1.message!!.contains("PLTE"), ex1.message)

        val oneEntry = pngBytes(
            1, 1, 3,
            listOf(byteArrayOf(1)),
            palette = byteArrayOf(1, 2, 3),
        )
        val ex2 = assertFailsWith<PngDecodeException> { PngDecoder.decode(oneEntry) }
        assertTrue(ex2.message!!.contains("索引 1"), ex2.message)
    }

    @Test
    fun `过滤器 5 明确拒绝`() {
        val raw = byteArrayOf(5) + ByteArray(3)  // 1x1 RGB，过滤器字节 = 5
        val bytes = pngBytes(1, 1, 2, listOf(byteArrayOf(0, 0, 0)), idat = zlib(raw))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("过滤器类型 5"), ex.message)
    }

    @Test
    fun `IDAT 解压数据不足明确拒绝`() {
        // 2x1 RGB 需要 1 + 6 = 7 字节，这里只压 1 + 3 = 4 字节。
        val raw = byteArrayOf(0) + ByteArray(3)
        val bytes = pngBytes(2, 1, 2, listOf(byteArrayOf(0, 0, 0, 0, 0, 0)), idat = zlib(raw))
        val ex = assertFailsWith<PngDecodeException> { PngDecoder.decode(bytes) }
        assertTrue(ex.message!!.contains("数据不足"), ex.message)
    }

    // ==================================================================
    // GraphicsBuffer 落点
    // ==================================================================

    @Test
    fun `createFromArgb 校验尺寸与像素数`() {
        val buf = GraphicsBuffer(1)
        assertFalse(buf.createFromArgb(2, 2, IntArray(3)))
        assertFalse(buf.isCreated, "校验失败不得修改缓冲")
        assertTrue(buf.createFromArgb(2, 1, intArrayOf(1, 2)))
        assertEquals(2, buf.width)
        assertEquals(1, buf.height)
        assertEquals(1, buf.pixelAt(0, 0))
    }

    // ==================================================================
    // GCREATEFROMFILE / GLOAD 接线
    // ==================================================================

    @Test
    fun `GCREATEFROMFILE 解码真 PNG 并给出 GWIDTH GHEIGHT 与像素`() {
        val ops = PngTestFileOps()
        ops.binaries["resources/face.png"] = pngBytes(
            2, 2, 6,
            listOf(
                byteArrayOf(200.toByte(), 100, 50, 0, 1, 2, 3, 128.toByte()),
                byteArrayOf(9, 8, 7, 255.toByte(), 0, 0, 0, 0),
            ),
        )
        val ctx = pngCtx(ops)

        // 2 参形态：语料原样的裸名，宿主收到的是 `resources/face.png`（ContentDir 前缀，
        // spec: FunctionMethodCreator.cs:6890-6894 + Program.cs:219-221）
        assertEquals(1L, pngCall(ctx, "GCREATEFROMFILE", 10, "face.png"))
        assertEquals(2L, pngCall(ctx, "GWIDTH", 10))
        assertEquals(2L, pngCall(ctx, "GHEIGHT", 10))
        // GCreateFromF 的 DrawImage 口径：源 alpha=0 的像素在缓冲里是 0（RGB 被丢弃）
        assertEquals(0L, pngCall(ctx, "GGETCOLOR", 10, 0, 0))
        assertEquals(unsignedArgb(argb(128, 1, 2, 3)), pngCall(ctx, "GGETCOLOR", 10, 1, 0))
        assertEquals(unsignedArgb(argb(255, 9, 8, 7)), pngCall(ctx, "GGETCOLOR", 10, 0, 1))

        // 3 参形态：flag 非 0 = 「不要 ContentDir 前缀」（spec: FunctionMethodCreator.cs:6891-6894），
        // 所以这里必须传**已经带前缀**的全路径才解得开 —— 与上一条用例走的是同一个文件。
        assertEquals(1L, pngCall(ctx, "GCREATEFROMFILE", 11, "resources/face.png", 1))
        assertEquals(2L, pngCall(ctx, "GWIDTH", 11))
        // 已创建的缓冲 → 0（C# :6858-6862）
        assertEquals(0L, pngCall(ctx, "GCREATEFROMFILE", 10, "face.png"))
    }

    @Test
    fun `GCREATEFROMFILE 对坏 PNG 与文本垃圾返回 0`() {
        val ops = PngTestFileOps()
        val good = pngBytes(2, 2, 2, listOf(byteArrayOf(1, 2, 3, 4, 5, 6), byteArrayOf(7, 8, 9, 10, 11, 12)))
        ops.binaries["resources/bad.png"] = good.copyOf(good.size / 2)
        ops.binaries["resources/text.png"] = "hello".toByteArray(Charsets.UTF_8)
        ops.texts["resources/text.png"] = "hello"
        val ctx = pngCtx(ops)

        assertEquals(0L, pngCall(ctx, "GCREATEFROMFILE", 10, "bad.png"))
        assertEquals(0L, pngCall(ctx, "GCREATED", 10), "坏 PNG 不得留下半创建的缓冲")
        assertEquals(0L, pngCall(ctx, "GCREATEFROMFILE", 11, "text.png"))
        assertEquals(0L, pngCall(ctx, "GCREATEFROMFILE", 12, "missing.png"))
    }

    @Test
    fun `GLOAD 读 sav 目录下的真 PNG`() {
        val ops = PngTestFileOps()
        ops.binaries["sav/img0007.png"] = pngBytes(1, 1, 2, listOf(byteArrayOf(1, 2, 3)))
        val ctx = pngCtx(ops)

        assertEquals(1L, pngCall(ctx, "GLOAD", 17, 7))
        assertEquals(1L, pngCall(ctx, "GWIDTH", 17))
        assertEquals(1L, pngCall(ctx, "GHEIGHT", 17))
        assertEquals(unsignedArgb(argb(255, 1, 2, 3)), pngCall(ctx, "GGETCOLOR", 17, 0, 0))
    }

    @Test
    fun `GSAVE 文本载体在宿主有二进制读时仍可 GLOAD`() {
        val ops = PngTestFileOps()
        val ctx = pngCtx(ops)

        assertEquals(1L, pngCall(ctx, "GCREATE", 10, 2, 2))
        // GSETCOLOR 的颜色参数按 emuera 无符号 ARGB（0..0xFFFFFFFF）传入（ReadColor :9352-9360）
        assertEquals(1L, pngCall(ctx, "GSETCOLOR", 10, unsignedArgb(argb(255, 10, 20, 30)), 0, 0))
        assertEquals(1L, pngCall(ctx, "GSETCOLOR", 10, unsignedArgb(argb(128, 40, 50, 60)), 1, 1))
        assertEquals(1L, pngCall(ctx, "GSAVE", 10, 0))
        // 生产宿主（RootedBuiltinHost）同一路径也能读到 UTF-8 字节；文本载体不是 PNG 签名，
        // 必须回落 readText，而不是把文本当坏 PNG 直接判 0。
        ops.binaries["sav/img0000.png"] = ops.texts.getValue("sav/img0000.png").toByteArray(Charsets.UTF_8)

        val before = pngPool(ctx).get(10).pixels.copyOf()
        assertEquals(1L, pngCall(ctx, "GLOAD", 17, 0))
        assertEquals(2L, pngCall(ctx, "GWIDTH", 17))
        assertEquals(2L, pngCall(ctx, "GHEIGHT", 17))
        assertEquals(unsignedArgb(argb(255, 10, 20, 30)), pngCall(ctx, "GGETCOLOR", 17, 0, 0))
        assertEquals(unsignedArgb(argb(128, 40, 50, 60)), pngCall(ctx, "GGETCOLOR", 17, 1, 1))
        assertTrue(before.contentEquals(pngPool(ctx).get(17).pixels), "GSAVE 文本载体往返像素应一致")
        // 基准 649 行分支：目标缓冲已创建时 GLOAD 直接 0（不再读文件）
        assertEquals(0L, pngCall(ctx, "GLOAD", 17, 0))
    }

    @Test
    fun `宿主未接二进制读时 PNG 返回 0 但文本载体仍可用`() {
        val ops = PngTestFileOps()
        ops.binaries["resources/a.png"] = pngBytes(1, 1, 2, listOf(byteArrayOf(1, 2, 3)))
        ops.texts["resources/b.png"] = "ERATW-GFX 1\n1 1\nff112233\n"
        val ctx = pngCtx(ops, binaryReader = false)

        assertEquals(0L, pngCall(ctx, "GCREATEFROMFILE", 10, "a.png"), "没有二进制读能力时真 PNG 无法解码")
        assertEquals(1L, pngCall(ctx, "GCREATEFROMFILE", 11, "b.png"))
        assertEquals(unsignedArgb(argb(255, 0x11, 0x22, 0x33)), pngCall(ctx, "GGETCOLOR", 11, 0, 0))
    }

    // ==================================================================
    // 真实语料（ERATW_CORPUS 缺失时跳过）
    // ==================================================================

    @Test
    fun `真实语料 592 个 PNG 全量解码`() {
        val resources = findCorpusResources()
        Assumptions.assumeTrue(resources != null, "未找到语料 resources 目录（设置 ERATW_CORPUS）")
        val root = resources ?: return

        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .toList()
        assertTrue(files.size > 500, "只发现 ${files.size} 个 PNG（语料实测约 592 个）")

        var decoded = 0
        for (f in files) {
            val img = PngDecoder.decode(f.readBytes())
            assertTrue(img.width > 0 && img.height > 0, "尺寸非法：${f.path}")
            assertEquals(img.width * img.height, img.pixels.size, "像素数不符：${f.path}")
            decoded++
        }
        assertEquals(files.size, decoded)

        // 三种色型各抽一个文件，像素值由 System.Drawing（GDI+，原版 LoadImage 的 oracle）读出。
        val rgb = files.firstOrNull { it.name == "im_av_green.png" }
        val palette = files.firstOrNull { it.name == "pngベース.png" }
        val rgba = files.firstOrNull { it.name == "13_extra.png" }
        assertTrue(rgb != null && palette != null && rgba != null, "语料抽样文件缺失")

        val rgbImg = PngDecoder.decode(rgb!!.readBytes())
        assertEquals(128, rgbImg.width)
        assertEquals(128, rgbImg.height)
        assertEquals(argb(255, 34, 177, 76), rgbImg.pixels[0])

        val paletteImg = PngDecoder.decode(palette!!.readBytes())
        assertEquals(270, paletteImg.width)
        assertEquals(270, paletteImg.height)
        // 该图 PLTE 两项均为黑色，tRNS = FF 00：全图索引 1 → alpha 0、RGB 0（GDI+ 读也是全透明）。
        assertEquals(0, paletteImg.pixels[0])

        val rgbaImg = PngDecoder.decode(rgba!!.readBytes())
        assertEquals(270, rgbaImg.width)
        assertEquals(270, rgbaImg.height)
        assertEquals(argb(134, 160, 153, 147), rgbaImg.pixels[135 * 270 + 135])

        // 真实文件走 builtin 全链路（契约要求的 GWIDTH/GHEIGHT 实测）：
        // GCREATEFROMFILE → 解码 → createFromArgb → GWIDTH/GHEIGHT。
        val ops = PngTestFileOps()
        ops.binaries["resources/corpus/im_av_green.png"] = rgb.readBytes()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngCall(ctx, "GCREATEFROMFILE", 10, "corpus/im_av_green.png"))
        assertEquals(128L, pngCall(ctx, "GWIDTH", 10))
        assertEquals(128L, pngCall(ctx, "GHEIGHT", 10))
    }
}

// ======================================================================
// 合成 PNG 构造器
// ======================================================================

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

/** `0xAARRGGBB`。 */
private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

private fun be32(v: Int): ByteArray = byteArrayOf(
    ((v ushr 24) and 0xFF).toByte(),
    ((v ushr 16) and 0xFF).toByte(),
    ((v ushr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte(),
)

private fun chunk(type: String, data: ByteArray): ByteArray {
    val t = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32()
    crc.update(t)
    crc.update(data)
    val out = ByteArrayOutputStream()
    out.write(be32(data.size))
    out.write(t)
    out.write(data)
    out.write(be32(crc.value.toInt()))
    return out.toByteArray()
}

private fun ihdr(width: Int, height: Int, colorType: Int, bitDepth: Int, interlace: Int): ByteArray =
    chunk(
        "IHDR",
        be32(width) + be32(height) +
            byteArrayOf(bitDepth.toByte(), colorType.toByte(), 0, 0, interlace.toByte()),
    )

private fun zlib(raw: ByteArray): ByteArray {
    val deflater = Deflater()
    try {
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}

/** 按 PNG 过滤器规范把原始扫描线编码成过滤后的行（解码的逆运算）。 */
private fun filterRow(raw: ByteArray, prev: ByteArray?, bpp: Int, filter: Int): ByteArray {
    val out = ByteArray(raw.size)
    for (x in raw.indices) {
        val a = if (x >= bpp) raw[x - bpp].toInt() and 0xFF else 0
        val b = if (prev != null) prev[x].toInt() and 0xFF else 0
        val c = if (prev != null && x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
        val v = raw[x].toInt() and 0xFF
        out[x] = when (filter) {
            0 -> v
            1 -> v - a
            2 -> v - b
            3 -> v - ((a + b) ushr 1)
            4 -> v - paeth(a, b, c)
            else -> error("测试构造器只支持过滤器 0..4，实际 $filter")
        }.toByte()
    }
    return out
}

private fun pngBytes(
    width: Int,
    height: Int,
    colorType: Int,
    rows: List<ByteArray>,
    filters: List<Int> = List(height) { 0 },
    palette: ByteArray? = null,
    trns: ByteArray? = null,
    bitDepth: Int = 8,
    interlace: Int = 0,
    idat: ByteArray? = null,
    idatChunks: Int = 1,
    includeIend: Boolean = true,
): ByteArray {
    require(rows.size == height) { "rows 行数 ${rows.size} != height $height" }
    val bpp = when (colorType) {
        0, 3 -> 1
        2 -> 3
        4 -> 2
        6 -> 4
        else -> 1
    }
    val raw = ByteArrayOutputStream()
    var prev: ByteArray? = null
    for (y in 0 until height) {
        raw.write(filters[y])
        raw.write(filterRow(rows[y], prev, bpp, filters[y]))
        prev = rows[y]
    }
    val compressed = idat ?: zlib(raw.toByteArray())

    val out = ByteArrayOutputStream()
    out.write(PNG_SIGNATURE)
    out.write(ihdr(width, height, colorType, bitDepth, interlace))
    if (palette != null) out.write(chunk("PLTE", palette))
    if (trns != null) out.write(chunk("tRNS", trns))
    if (idatChunks <= 1 || compressed.isEmpty()) {
        out.write(chunk("IDAT", compressed))
    } else {
        val n = minOf(idatChunks, compressed.size)
        for (i in 0 until n) {
            val from = compressed.size * i / n
            val to = compressed.size * (i + 1) / n
            out.write(chunk("IDAT", compressed.copyOfRange(from, to)))
        }
    }
    if (includeIend) out.write(chunk("IEND", ByteArray(0)))
    return out.toByteArray()
}

/** 把指定类型第一块的 CRC 末字节取反（验证解码器不复验 CRC）。 */
private fun flipCrcOf(bytes: ByteArray, type: String): ByteArray {
    val out = bytes.copyOf()
    var pos = PNG_SIGNATURE.size
    while (pos + 12 <= out.size) {
        val length = ((out[pos].toInt() and 0xFF) shl 24) or
            ((out[pos + 1].toInt() and 0xFF) shl 16) or
            ((out[pos + 2].toInt() and 0xFF) shl 8) or
            (out[pos + 3].toInt() and 0xFF)
        val t = String(out, pos + 4, 4, Charsets.US_ASCII)
        val crcAt = pos + 8 + length
        if (t == type) {
            out[crcAt + 3] = (out[crcAt + 3].toInt() xor 0xFF).toByte()
            return out
        }
        pos = crcAt + 4
    }
    error("没有找到块 $type")
}

private fun assertPixels(img: PngImage, vararg expected: Int) {
    assertEquals(expected.size, img.pixels.size, "像素个数")
    for (i in expected.indices) {
        assertEquals(expected[i], img.pixels[i], "pixel[$i]（0x${Integer.toHexString(expected[i])}）")
    }
}

// ======================================================================
// GCREATEFROMFILE / GLOAD 的内存宿主
// ======================================================================

private class PngTestFileOps : FileOps {
    val texts = LinkedHashMap<String, String>()
    val binaries = LinkedHashMap<String, ByteArray>()

    override fun exists(path: String): Boolean = texts.containsKey(path) || binaries.containsKey(path)

    override fun readText(path: String): String? = texts[path]

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        texts[path] = if (append) (texts[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class PngTestBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("PngTestBase 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** [binaryReader] = false 模拟「只接了 fileOps、没接 readFileBytes」的宿主。 */
private class PngTestHost(
    private val ops: PngTestFileOps,
    private val binaryReader: Boolean,
) : BuiltinHost {
    override val fileOps: FileOps = ops

    override fun readFileBytes(path: String): ByteArray? = if (binaryReader) ops.binaries[path] else null
}

private fun pngCtx(ops: PngTestFileOps, binaryReader: Boolean = true): BuiltinEvalContext {
    val store = VariableStore()
    val base = PngTestBase()
    base.variables = store
    return BuiltinEvalContext(base, store, PngTestHost(ops, binaryReader))
}

private fun pngCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    ) as Value.IntV).v

private fun pngPool(ctx: BuiltinEvalContext): GraphicsPool =
    ctx.store.aux.slot("gfx") { GraphicsPool() }

/**
 * 从 `ERATW_CORPUS` 向上最多 4 层找 `resources` 目录。
 *
 * 语料根有两种形态：`ERATW_CORPUS=<游戏根>`（本波闸门口径）或 `<游戏根>/ERB`
 * （bootSmoke 口径），都在这里兼容；不写死任何盘符。
 */
private fun findCorpusResources(): File? {
    val env = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var dir: File? = File(env)
    repeat(4) {
        val d = dir ?: return null
        if (d.isDirectory) {
            val res = File(d, "resources")
            if (res.isDirectory) return res
        }
        dir = d.parentFile
    }
    return null
}
