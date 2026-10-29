package com.eratw.port.core.image

import com.eratw.port.core.host.GraphicsBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * PNG 解码失败的统一异常（签名/结构/不支持的子集/解压失败）。
 *
 * 函数层（`GCREATEFROMFILE` / `GLOAD`）捕获本异常并按原版 `Utils.LoadImage` 的
 * catch-all 口径返回 0（`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs:502-525`：
 * 任何异常 → null → 函数返回 0），不把解码错误升级成脚本可见的 CodeEE。
 */
class PngDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一次 PNG 解码的产物。
 *
 * @property width  像素宽（> 0 且 <= [GraphicsBuffer.MAX_IMAGE_SIZE]）
 * @property height 像素高（同上）
 * @property pixels 行主序 `0xAARRGGBB` 直通像素，长度 == width * height。
 *   这是 PNG 源像素的**直通** ARGB（直通 alpha，非预乘）；原版 `GraphicsImage.GCreateFromF`
 *   还会把源位图 `DrawImage` 到透明目标（GraphicsImage.cs:119-130），该步的 alpha=0 归零由
 *   函数层按 GDI+ 实测口径补做，不在解码器里。
 */
class PngImage(val width: Int, val height: Int, val pixels: IntArray) {
    override fun toString(): String = "PngImage(${width}x$height, ${pixels.size} px)"
}

/**
 * 最小 PNG 解码器（纯 JVM，仅依赖 `java.util.zip`）。**不用** `javax.imageio`：Android 上没有它。
 *
 * ## 支持的子集
 *
 * | 维度 | 支持 | 不支持（抛 [PngDecodeException]） |
 * |---|---|---|
 * | 块 | IHDR / PLTE / tRNS / IDAT（可多块拼接）/ IEND；其余块跳过 | 缺 IHDR / PLTE（色型 3）/ IDAT / IEND |
 * | 位深 | 8 | 1 / 2 / 4 / 16（KDoc 末尾 TODO(verify)） |
 * | 色型 | 0 灰度 / 2 RGB / 3 调色板 / 4 灰度+α / 6 RGBA | 其余数值 |
 * | 过滤器 | 0 None / 1 Sub / 2 Up / 3 Average / 4 Paeth | 5..255 |
 * | 隔行 | 非隔行（interlace = 0） | Adam7（interlace = 1） |
 *
 * 语料实测（`resources` 目录下 592 个 PNG，2026-10-21）：全部是 8 位非隔行；色型分布
 * 590 个 6、1 个 2、1 个 3（后者带 PLTE + tRNS）。也就是说本子集覆盖语料全部 PNG；
 * 1/2/4/16 位与 Adam7 只是规范上的缺口。
 *
 * ## 与 C# / GDI+ 的关系
 *
 * - 原版 `GCREATEFROMFILE` 调 `Utils.LoadImage`（FunctionMethodCreator.cs:6850-6927），
 *   `GLOAD` 同（:8135-8200，取图与尺寸判定在 :8172-8181）；`Utils.LoadImage` 先用扩展名分派
 *   .webp（Utils.cs:511-515），其余交 `new Bitmap(path)`（Utils.cs:518）——即 GDI+ 能读
 *   PNG/BMP/JPEG/GIF 等。
 *   本解码器只覆盖 PNG；webp 与其余格式在函数层回落为「返回 0」，见 `GraphicsMethods.kt`
 *   的 KDoc（语料另有 2547 个 webp，另行立卡）。
 * - **CRC 不复验**：GDI+ 实测对本机语料里 CRC 错误的 iCCP（`Xray_source/im_av_chara_yellow.png`）
 *   以及人为破坏的 IDAT CRC 仍然成功解码；本解码器照此容忍，只校验块长度与结构。
 * - 输出直通 ARGB；GDI+ `DrawImage` 到透明目标会把 alpha=0 的像素洗成 0，那一步在函数层做。
 * - 原版单边上限 8192（AbstractImage.cs:8、FunctionMethodCreator.cs:6905-6908）之外，本解码器
 *   另有**像素总数**上限（GraphicsBuffer.MAX_IMAGE_PIXELS）：原版对巨图是 GDI+ 自己 OOM 后被
 *   `Utils.LoadImage` 的空 catch（Utils.cs:521-523）兜成 null → 0，本解码器在分配前显式给
 *   同一结局，避免 `OutOfMemoryError` 炸穿只捕 `Exception` 的函数层（GraphicsMethods.kt:971-975）。
 *
 * ## TODO(verify)
 *
 * - 1/2/4/16 位深与 Adam7：语料零实例，未实现。若未来语料/资源出现，这里必须补：
 *   16 位要按 GDI+ 的 8 位截断口径（高字节）落 ARGB；Adam7 要按七趟 pass 展开。
 * - GDI+ 对坏 CRC 的容忍度是在 Windows System.Drawing 上实测的；Android 侧若换成别的
 *   解码实现（本类不依赖 Android），口径需重新确认。
 */
object PngDecoder {

    /** PNG 签名：89 50 4E 47 0D 0A 1A 0A。 */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 文件头是否匹配 PNG 签名（只验前 8 字节，不保证可解码）。 */
    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE.size) return false
        for (i in SIGNATURE.indices) {
            if (bytes[i] != SIGNATURE[i]) return false
        }
        return true
    }

    /**
     * 解码一张 PNG。
     *
     * @throws PngDecodeException 签名不匹配、结构截断、位深/色型/隔行不在子集、IDAT 解压失败、
     *   调色板索引越界等。函数层捕获后按原版返回 0。
     */
    fun decode(bytes: ByteArray): PngImage {
        if (!isPng(bytes)) throw PngDecodeException("PNG 签名不匹配：文件头不是 PNG")

        var pos = SIGNATURE.size
        var header: Header? = null
        var palette: ByteArray? = null
        var transparency: ByteArray? = null
        val idat = ByteArrayOutputStream()
        var sawIdat = false
        var sawIend = false

        while (pos < bytes.size) {
            if (bytes.size - pos < 12) {
                throw PngDecodeException("PNG 截断：偏移 $pos 处不足一个块（至少 12 字节）")
            }
            val length = readInt(bytes, pos)
            if (length < 0) throw PngDecodeException("PNG 块长度非法：$length（偏移 $pos）")
            val typeStart = pos + 4
            val type = String(bytes, typeStart, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val dataEndLong = dataStart.toLong() + length.toLong()
            if (dataEndLong + 4L > bytes.size.toLong()) {
                throw PngDecodeException("PNG 截断：块 $type 声明 $length 字节，文件剩余不足（偏移 $pos）")
            }
            val dataEnd = dataEndLong.toInt()

            if (header == null && type != "IHDR") {
                throw PngDecodeException("PNG 结构非法：首块必须是 IHDR，实际是 $type")
            }
            when (type) {
                "IHDR" -> {
                    if (header != null) throw PngDecodeException("PNG 结构非法：出现多个 IHDR")
                    if (length != 13) throw PngDecodeException("PNG 结构非法：IHDR 长度应为 13，实际 $length")
                    header = parseHeader(bytes, dataStart)
                }
                "PLTE" -> {
                    if (length == 0 || length % 3 != 0 || length > 768) {
                        throw PngDecodeException("PNG 结构非法：PLTE 长度 $length 不是 3..768 的 3 的倍数")
                    }
                    palette = bytes.copyOfRange(dataStart, dataEnd)
                }
                "tRNS" -> transparency = bytes.copyOfRange(dataStart, dataEnd)
                "IDAT" -> {
                    if (header == null) throw PngDecodeException("PNG 结构非法：IDAT 出现在 IHDR 之前")
                    idat.write(bytes, dataStart, length)
                    sawIdat = true
                }
                "IEND" -> {
                    if (length != 0) throw PngDecodeException("PNG 结构非法：IEND 长度应为 0，实际 $length")
                    sawIend = true
                }
                // 其余块（pHYs / tEXt / iTXt / zTXt / sRGB / gAMA / iCCP / bKGD / tIME / eXIf 等）跳过。
                else -> Unit
            }
            pos = dataEnd + 4
            if (sawIend) break
        }

        if (!sawIend) throw PngDecodeException("PNG 截断：缺少 IEND 块")
        val h = header ?: throw PngDecodeException("PNG 结构非法：缺少 IHDR 块")
        if (!sawIdat || idat.size() == 0) throw PngDecodeException("PNG 结构非法：缺少 IDAT 块")
        return decodePixels(h, palette, transparency, idat.toByteArray())
    }

    // ------------------------------------------------------------------
    // IHDR
    // ------------------------------------------------------------------

    /** 校验后的 IHDR（只保留解码要用的字段）。 */
    private class Header(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        /** 扫描线每像素字节数（8 位深时的过滤器 bpp；色型 3 是索引 1 字节）。 */
        val bytesPerPixel: Int,
    )

    private fun parseHeader(bytes: ByteArray, at: Int): Header {
        val width = readInt(bytes, at)
        val height = readInt(bytes, at + 4)
        val bitDepth = bytes[at + 8].toInt() and 0xFF
        val colorType = bytes[at + 9].toInt() and 0xFF
        val compression = bytes[at + 10].toInt() and 0xFF
        val filter = bytes[at + 11].toInt() and 0xFF
        val interlace = bytes[at + 12].toInt() and 0xFF

        if (width <= 0 || width > GraphicsBuffer.MAX_IMAGE_SIZE) {
            throw PngDecodeException(
                "PNG 尺寸不受支持：宽 $width（允许 1..${GraphicsBuffer.MAX_IMAGE_SIZE}）",
            )
        }
        if (height <= 0 || height > GraphicsBuffer.MAX_IMAGE_SIZE) {
            throw PngDecodeException(
                "PNG 尺寸不受支持：高 $height（允许 1..${GraphicsBuffer.MAX_IMAGE_SIZE}）",
            )
        }
        // 像素总数守卫：原版只限单边 8192（FunctionMethodCreator.cs:6905-6908），不限像素数；
        // 但 8192×8192 级别的巨图会让本解码器分配数亿字节的解压缓冲 + 像素数组，在真机堆上
        // 抛 OutOfMemoryError——而函数层只捕 Exception（GraphicsMethods.kt:971-975），Error
        // 会炸穿 VM 线程。GDI+ 在内存受限宿主上对这种图抛 OutOfMemoryException，被
        // Utils.LoadImage 的空 catch（Utils.cs:521-523）折成 null → 函数返回 0；这里在
        // 分配之前显式给同一结局（上限口径见 GraphicsBuffer.MAX_IMAGE_PIXELS）。
        if (width.toLong() * height.toLong() > GraphicsBuffer.MAX_IMAGE_PIXELS) {
            throw PngDecodeException(
                "PNG 像素总数超限：${width}x$height = ${width.toLong() * height.toLong()}" +
                    " px（上限 ${GraphicsBuffer.MAX_IMAGE_PIXELS}）",
            )
        }
        if (bitDepth != 8) {
            throw PngDecodeException("PNG 位深 $bitDepth 不受支持（仅支持 8 位；见 PngDecoder 的 KDoc）")
        }
        if (compression != 0) {
            throw PngDecodeException("PNG 压缩方法 $compression 不受支持（规范仅定义 0）")
        }
        if (filter != 0) {
            throw PngDecodeException("PNG 过滤方法 $filter 不受支持（规范仅定义 0）")
        }
        if (interlace != 0) {
            throw PngDecodeException("PNG 隔行扫描（Adam7）不受支持（interlace=$interlace）")
        }
        val bytesPerPixel = when (colorType) {
            0 -> 1  // 灰度
            2 -> 3  // RGB
            3 -> 1  // 调色板索引
            4 -> 2  // 灰度 + alpha
            6 -> 4  // RGBA
            else -> throw PngDecodeException("PNG 色型 $colorType 不受支持（支持 0/2/3/4/6）")
        }
        return Header(width, height, bitDepth, colorType, bytesPerPixel)
    }

    // ------------------------------------------------------------------
    // IDAT + 过滤器 + 像素转换
    // ------------------------------------------------------------------

    private fun decodePixels(
        h: Header,
        paletteBytes: ByteArray?,
        transparency: ByteArray?,
        zlib: ByteArray,
    ): PngImage {
        val paletteCount = paletteBytes?.size?.div(3) ?: 0
        if (h.colorType == 3 && paletteCount == 0) {
            throw PngDecodeException("PNG 调色板色型（3）缺少 PLTE 块")
        }

        val stride = h.width * h.bytesPerPixel
        val rawSizeLong = (stride.toLong() + 1L) * h.height.toLong()
        if (rawSizeLong > Int.MAX_VALUE.toLong()) {
            throw PngDecodeException("PNG 解压后过大：$rawSizeLong 字节")
        }
        val rawSize = rawSizeLong.toInt()
        val raw = inflate(zlib, rawSize)

        // 调色板：ARGB，alpha 暂按 255（tRNS 在取像素时覆盖）。
        val palette = IntArray(paletteCount)
        if (paletteBytes != null) {
            for (i in 0 until paletteCount) {
                val o = i * 3
                val r = paletteBytes[o].toInt() and 0xFF
                val g = paletteBytes[o + 1].toInt() and 0xFF
                val b = paletteBytes[o + 2].toInt() and 0xFF
                palette[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        // tRNS：色型 3 = 每索引 1 字节 alpha；色型 0 = 2 字节灰度键；色型 2 = 6 字节 RGB 键。
        val paletteAlpha = IntArray(paletteCount) { 255 }
        var grayKey = -1
        var redKey = -1
        var greenKey = -1
        var blueKey = -1
        if (transparency != null) {
            when (h.colorType) {
                3 -> {
                    val n = minOf(paletteCount, transparency.size)
                    for (i in 0 until n) paletteAlpha[i] = transparency[i].toInt() and 0xFF
                }
                0 -> {
                    if (transparency.size >= 2) {
                        // 16 位大端样本；8 位深时有效值只有低字节。
                        val key = ((transparency[0].toInt() and 0xFF) shl 8) or
                            (transparency[1].toInt() and 0xFF)
                        if (key in 0..0xFF) grayKey = key
                    }
                }
                2 -> {
                    if (transparency.size >= 6) {
                        redKey = ((transparency[0].toInt() and 0xFF) shl 8) or
                            (transparency[1].toInt() and 0xFF)
                        greenKey = ((transparency[2].toInt() and 0xFF) shl 8) or
                            (transparency[3].toInt() and 0xFF)
                        blueKey = ((transparency[4].toInt() and 0xFF) shl 8) or
                            (transparency[5].toInt() and 0xFF)
                    }
                }
                // 色型 4/6 的 alpha 在扫描线里，tRNS 无意义，忽略。
                else -> Unit
            }
        }

        val pixels = IntArray(h.width * h.height)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var p = 0
        var out = 0
        for (y in 0 until h.height) {
            val filterType = raw[p].toInt() and 0xFF
            p++
            System.arraycopy(raw, p, cur, 0, stride)
            p += stride

            for (x in 0 until stride) {
                val a = if (x >= h.bytesPerPixel) cur[x - h.bytesPerPixel].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= h.bytesPerPixel) prev[x - h.bytesPerPixel].toInt() and 0xFF else 0
                val encoded = cur[x].toInt() and 0xFF
                val value = when (filterType) {
                    0 -> encoded
                    1 -> encoded + a
                    2 -> encoded + b
                    3 -> encoded + ((a + b) ushr 1)
                    4 -> encoded + paeth(a, b, c)
                    else -> throw PngDecodeException(
                        "PNG 第 $y 行过滤器类型 $filterType 非法（仅支持 0..4）",
                    )
                }
                cur[x] = (value and 0xFF).toByte()
            }

            when (h.colorType) {
                0 -> for (x in 0 until h.width) {
                    val g = cur[x].toInt() and 0xFF
                    val argb = (g shl 16) or (g shl 8) or g
                    pixels[out++] = if (g == grayKey) argb else 0xFF000000.toInt() or argb
                }
                2 -> for (x in 0 until h.width) {
                    val o = x * 3
                    val r = cur[o].toInt() and 0xFF
                    val g = cur[o + 1].toInt() and 0xFF
                    val b = cur[o + 2].toInt() and 0xFF
                    val rgb = (r shl 16) or (g shl 8) or b
                    val keyed = r == redKey && g == greenKey && b == blueKey
                    pixels[out++] = if (keyed) rgb else 0xFF000000.toInt() or rgb
                }
                3 -> for (x in 0 until h.width) {
                    val idx = cur[x].toInt() and 0xFF
                    if (idx >= paletteCount) {
                        throw PngDecodeException(
                            "PNG 调色板索引 $idx 越界（PLTE 只有 $paletteCount 项，行 $y 列 $x）",
                        )
                    }
                    val alpha = paletteAlpha[idx]
                    pixels[out++] = (alpha shl 24) or (palette[idx] and 0x00FFFFFF)
                }
                4 -> for (x in 0 until h.width) {
                    val o = x * 2
                    val g = cur[o].toInt() and 0xFF
                    val a = cur[o + 1].toInt() and 0xFF
                    pixels[out++] = (a shl 24) or (g shl 16) or (g shl 8) or g
                }
                else -> for (x in 0 until h.width) {  // 6
                    val o = x * 4
                    val r = cur[o].toInt() and 0xFF
                    val g = cur[o + 1].toInt() and 0xFF
                    val b = cur[o + 2].toInt() and 0xFF
                    val a = cur[o + 3].toInt() and 0xFF
                    pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return PngImage(h.width, h.height, pixels)
    }

    /**
     * zlib 解压 IDAT 载荷到**恰好** [expected] 字节。
     *
     * 多块 IDAT 在调用前已按规范首尾拼接（PNG 规定 zlib 流跨块连续）。
     * 解压不足（流截断 / 需要字典）→ [PngDecodeException]；多余字节忽略（GDI+ 同样容忍）。
     */
    private fun inflate(zlib: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(zlib)
            var off = 0
            while (off < expected) {
                val n = try {
                    inflater.inflate(out, off, expected - off)
                } catch (e: DataFormatException) {
                    throw PngDecodeException("PNG IDAT zlib 解压失败：${e.message}", e)
                }
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                    throw PngDecodeException("PNG IDAT zlib 解压停滞（无进展且未结束）")
                }
                off += n
            }
            if (off != expected) {
                throw PngDecodeException("PNG IDAT 解压数据不足：期望 $expected 字节，实际 $off")
            }
            return out
        } finally {
            inflater.end()
        }
    }

    /** PNG 规范的 Paeth 预测器（过滤器 4）。 */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** 大端 32 位；长度字段为负即非法（调用方判）。 */
    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
