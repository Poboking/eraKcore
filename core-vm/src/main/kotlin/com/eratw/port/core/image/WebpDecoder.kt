package com.eratw.port.core.image

import com.eratw.port.core.host.GraphicsBuffer

/**
 * webp 解码失败的统一异常（签名/容器/位流不在支持子集、结构截断、尺寸超限）。
 *
 * 函数层（`GCREATEFROMFILE` / `GLOAD`）按原版口径把它折成 0：
 * `Utils.LoadImage`（`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs:502-525`）
 * 对扩展名 `.webp` 走 `new WebP().Load(path)`（:511-515），`WebP.Load` 抛出的任何异常
 * （`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/WebP.cs:16-27`）都被
 * `Utils.cs:521-523` 的空 catch 吞掉 → 返回 null → 函数返回 0。
 * 所以本异常只在 core 内部出现，不外溢成 CodeEE。
 */
class WebpDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一次 webp 解码的产物（形状与 [PngImage] 一致：行主序直通 ARGB）。
 *
 * @property hasAlpha 原版 `WebP.Decode` 用 `WebPGetFeatures` 的 has_alpha 决定位图像素格式
 *   （`WebP.cs:37`）：真 → `Format32bppArgb`（alpha 有效），假 → `Format24bppRgb`（alpha 恒 255）。
 *   本类在 [hasAlpha] 为假时已把 alpha 写成 255（等价于 libwebp 的 MODE_BGR 丢 alpha）。
 */
class WebpImage(val width: Int, val height: Int, val pixels: IntArray, val hasAlpha: Boolean) {
    override fun toString(): String = "WebpImage(${width}x$height, alpha=$hasAlpha)"
}

/**
 * 最小 webp 解码器（纯 JVM：不用 Android API、不用 `javax.imageio`、不用 libwebp）。
 *
 * ## 支持子集（2026-10-21 语料全量普查：`.webp` 共 2548 个，其中 resources 目录下 2547 个）
 *
 * | 形态 | 语料条数 | 容器层 | 位流层实测 |
 * |---|---|---|---|
 * | `VP8L` 裸容器（无损） | 569 | 支持 | **125 个解通**（见下「已知缺口」） |
 * | `VP8X` + `VP8L` | 77 | 支持（画布与位流尺寸必须一致） | **2 个解通** |
 * | `VP8X` + `ALPH` + `VP8L` | 0 | 代码就位，语料无实例，仅合成用例自证（TODO(verify)） | — |
 * | `VP8X` + `ANIM`/`ANMF`（动画） | 9 | 复刻原版：给画布尺寸的**空白图**（见下） | 9/9 |
 * | `VP8 ` 裸容器（有损） | 831 | 明确不支持 → [WebpDecodeException] → 函数层 0 | — |
 * | `VP8X` + `VP8 ` + `ALPH`（有损 + 独立 alpha） | 1036 | 同上 | — |
 * | `VP8X` + `VP8 `（有损、无 `ALPH`） | 26 | 同上 | — |
 *
 * 合计：有损 831 + 1036 + 26 = **1893**；无损 569 + 77 = **646**；`VP8X` 容器 1036 + 26 + 77 + 9 = **1148**。
 * 顶层 `ALPH` 段共 1036 个，实测**全部**与有损位流配对（无损 + alpha 组合零实例）。
 * 其它顶层段计数（同一文件可多段）：`ICCP` 201、`EXIF` 75、`XMP ` 94、`PSAI` 321、`ANIM` 9、`ANMF` 9；
 * `RIFF` 声明长度与实际文件大小不符的文件 0 个。尺寸分布 315 种取值，最大 8192 x 4000
 * （上限正好卡在函数层的 8192，没有超限文件），最常见的五档：704x744（484）、1080x540（266）、
 * 752x752（168）、704x734（160）、540x360（146）。
 *
 * ## 已知缺口（本路实测，不是设计目标）
 *
 * 无损分支目前 **646 个里解通 127 个**（2026-10-22 复核读数）：515 个报 `WEBP-BADCODE`（几乎都是
 * LZ77 距离越界，首差常出现在第 1~2 个符号，说明**位流在读完变换/meta 子层之后与 libwebp 不同步**），
 * 4 个报 `WEBP-TRUNCATED`。上一版登记的是 28/646；差出来的 99 个全部是
 * `color indexing` 变换把**整个 ARGB 字**当调色板索引（`bits == 0` 分支）造成的假失败 ——
 * 那些文件的像素层其实已经解通，只是在最后的逆变换里被判成越界；索引取绿字节后一并转绿。
 * 逐段核对过 libwebp v1.1.0 的
 * `VP8LBuildHuffmanTable`（`src/utils/huffman_utils.c:84-206`）、`ReadHuffmanCode`
 * （`vp8l_dec.c:309-356`）、`ReadHuffmanCodeLengths`（:248-305）、`ReadTransform`（:1292-1343）、
 * `ExpandColorMap`（:1267-1290）、`DecodeImageData`（:1110-1255）、`PlaneCodeToDistance`（:167-177）、
 * `VP8LColorCacheInsert`（`src/utils/color_cache_utils.h:50-54`：`(argb * 0x1e35a7bd) >> (32 - bits)`），
 * 语义都对得上，卡点是**没有 libwebp 的逐层比特位置参照**，需要下一步给每层打印 `bit_pos`
 * 并与真库（游戏带的 `libwebp_x64.dll`，或本机 ctypes 调用）对拍才能定位。
 * 这里登记的读数与 [WebpDecoderTest] 的「语料全量成败清单」断言一致，修好后两侧都要上调。
 *
 * 有损不做的理由不是「没时间」：原版的像素值是 libwebp `WebPDecodeBGR(A)Into` 那套整数
 * IDCT / 环内滤波 / fancy 上采样的**实现定义**结果（`WebP.cs:41-48`），不是 VP8 规范的唯一解；
 * 逐字节复刻要把 `src/dec/{webp,frame,layer,tree,vp8}_dec.c` 与 `src/dsp/{yuv,upsampling}.c`
 * 整套搬进 core。语料 1893 个有损文件因此统一登记为「不支持 → 0」。
 *
 * ## 动画 webp 的原版口径（本类复刻，ADR-005）
 *
 * 1. `WebP.Decode` 先 `GetInfo`（`WebP.cs:36`）拿宽高：`WebPGetFeaturesInternal`
 *    对动画只读 `VP8X` 的画布尺寸与 alpha 标志就**返回 OK**
 *    （libwebp v1.1.0 `src/dec/webp_dec.c:319` 取 `ANIMATION_FLAG`、`:331-333` 直接
 *    `goto ReturnWidthHeight`、`:398-408` 用 VP8X 的宽高与 `*has_alpha |= (hdrs.alpha_data != NULL)` 收口）；
 * 2. 于是按 has_alpha 建 `Format32bppArgb` / `Format24bppRgb` 位图（`WebP.cs:37`）并 `LockBits(WriteOnly)`（:38）；
 * 3. `WebPDecodeBGRAInto` / `WebPDecodeBGRInto` 对动画返回 `VP8_STATUS_UNSUPPORTED_FEATURE`
 *    （`WebPParseHeaders`：`webp_dec.c:427-428`；`DecodeInto`：`:447-459` 在写任何输出之前就 return），
 *    **一个字节都不写**；
 * 4. 而这两个 P/Invoke 的包装是 `internal static void`，状态码被丢掉也不抛异常
 *    （`UnsafeNativeMethods.cs:201-214`、`:222-235`）→ `WebP.Load` 正常返回那张**刚创建、未写入**的位图；
 * 5. GDI+ 新建位图是清零的（上一路在本机 Windows PowerShell + System.Drawing 实测：
 *    `Format32bppArgb` 像素 = A0 R0 G0 B0；`Format24bppRgb` = A255 R0 G0 B0），
 *    所以动画 webp 在原版得到「画布尺寸的全透明图（有 alpha 标志）/ 全黑不透明图（无 alpha 标志）」，
 *    `GCREATEFROMFILE` 返回 **1**。
 *    TODO(verify)：第 3 步的 libwebp 行为本路已按 v1.1.0 源码复核（行号见上），
 *    第 5 步的「GDI+ 新建位图全 0」本路未复测（无 Windows System.Drawing 环境时无法验证）。
 *
 * ## 无损位流依据（逐函数照 libwebp **v1.1.0**，与游戏带的
 * `パッチ/Emuera1824+v11+webp+test+fix/libwebp_x64.dll` 同版本，`WebPGetDecoderVersion` = 65792）
 *
 * `src/dec/vp8l_dec.c`：头 :117-126、前缀码 :150-177、`ReadHuffmanCode` :309-356、
 * `ReadHuffmanCodeLengths` :248-305、`ReadHuffmanCodes` :358-529、`ReadTransform` :1292-1343、
 * `ExpandColorMap` :1267-1290、`DecodeImageStream` :1414-1501、`DecodeImageData` :1110-1255；
 * `src/utils/huffman_utils.c:84-206`（`BuildHuffmanTable`：单符号特例 :132-141、满树校验 :200-202）、
 * `src/utils/color_cache_utils.h:38-66`；`src/dsp/lossless.c`（预测 / 加回 green / 色彩反变换 / 调色板展开）、
 * `src/utils/utils.c` 的 4 个 unfilter（`WebPUnfilters`）；`src/dec/alpha_dec.c`（ALPH 头字节）。
 *
 * ## TODO(verify)
 *
 * - `ALPH` 段：语料里 1036 个 ALPH 全部配 `VP8 ` 有损位流，无损 + alpha 的组合零实例，
 *   只有合成用例自证；接上有损解码后需真机对拍。
 * - 变换条数 > 4：libwebp v1.1.0 只有 `assert`（`vp8l_dec.c:1311`），Release 下是越界写；
 *   本实现直接判失败。语料无损文件的变换条数**未逐条统计**（要完整解析位流才能数，
 *   而位流层本身还没解通），所以「全部 ≤ 4 条」这句没有普查支撑，别当结论用。
 * - `ReadHuffmanCodes` 的组号重映射阈值本实现照 `vp8l_dec.c:400`（`> 1000 || > xsize * ysize`，
 *   注意右值是**整层**尺寸不是子采样后的尺寸），并且照 :447-460 把未用到的组也读完码表
 *   （只是不存），否则位流会少读 5 棵 Huffman 码而整体错位。
 */
object WebpDecoder {

    // ------------------------------------------------------------------
    // 容器层
    // ------------------------------------------------------------------

    /** 文件头是否匹配 RIFF + WEBP（只验 12 字节，不保证可解码）。 */
    fun isWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    }

    // VP8X flags：libwebp `src/webp/mux_types.h:34-38`，按 `src/dec/webp_dec.c:135` 以 LE32 读取。
    private const val FLAG_ICC = 0x01
    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_XMP = 0x04
    private const val FLAG_EXIF = 0x08
    private const val FLAG_ALPHA = 0x10

    private fun mkFourcc(a: Char, b: Char, c: Char, d: Char): Int =
        a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)

    private val FCC_VP8X = mkFourcc('V', 'P', '8', 'X')
    private val FCC_VP8L = mkFourcc('V', 'P', '8', 'L')
    private val FCC_VP8_ = mkFourcc('V', 'P', '8', ' ')
    private val FCC_ALPH = mkFourcc('A', 'L', 'P', 'H')
    private val FCC_ANIM = mkFourcc('A', 'N', 'I', 'M')
    private val FCC_ANMF = mkFourcc('A', 'N', 'M', 'F')

    private fun fourcc(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun fourccName(cc: Int): String {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = ((cc ushr (8 * i)) and 0xFF).toByte()
        return String(b, Charsets.US_ASCII)
    }

    /** 小端 32 位（无符号域，调用方保证不越界）。 */
    private fun le32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    /** 小端 24 位。 */
    private fun le24(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)

    /**
     * 解码一张 webp。
     *
     * @throws WebpDecodeException 非 webp、有损位流、结构截断、位流非法、
     *   尺寸超过 [GraphicsBuffer.MAX_IMAGE_SIZE]（原版在 `FunctionMethodCreator.cs:6905` / `:8177`
     *   对宽或高 > 8192 的位图同样返回 0）。
     */
    fun decode(bytes: ByteArray): WebpImage {
        if (!isWebp(bytes)) throw WebpDecodeException("WEBP-BADSIGN：文件头不是 RIFF/WEBP")
        if (bytes.size < 20) throw WebpDecodeException("WEBP-TRUNCATED：不足 20 字节（连第一个段头都不全）")

        val declared = le32(bytes, 4) + 8L
        if (declared > bytes.size.toLong()) {
            throw WebpDecodeException("WEBP-TRUNCATED：RIFF 声明 $declared 字节，实际只有 ${bytes.size}")
        }
        val limit = declared.toInt()

        var pos = 12
        var flags = -1
        var canvasW = 0
        var canvasH = 0
        var haveVp8x = false
        var codicStart = -1
        var codicLen = 0
        var codicIsLossless = false
        var alphStart = -1
        var alphLen = 0
        var animated = false

        while (pos + 8 <= limit) {
            val cc = fourcc(bytes, pos)
            val size = le32(bytes, pos + 4)
            val payload = pos + 8
            if (payload + size > limit.toLong()) {
                throw WebpDecodeException(
                    "WEBP-TRUNCATED：段 ${fourccName(cc)} 声明 $size 字节，偏移 $pos 处文件不够",
                )
            }
            when (cc) {
                FCC_VP8X -> {
                    if (pos != 12) throw WebpDecodeException("WEBP-BADSTRUCT：VP8X 必须是第一个段")
                    // webp_dec.c:127-129：VP8X 的 chunk size 必须是 10，否则 BITSTREAM_ERROR。
                    if (size != 10L) throw WebpDecodeException("WEBP-BADSTRUCT：VP8X 段长度 $size 不是 10")
                    flags = le32(bytes, payload).toInt()
                    canvasW = 1 + le24(bytes, payload + 4)
                    canvasH = 1 + le24(bytes, payload + 7)
                    haveVp8x = true
                    if (flags and FLAG_ANIMATION != 0) animated = true
                }
                // libwebp 只认第一个位流段（`webp_dec.c` 的 ParseVP8Header），后面的段不参与解码。
                FCC_VP8L -> if (!haveVp8x || codicStart < 0) {
                    if (!animated) {
                        codicStart = payload
                        codicLen = size.toInt()
                        codicIsLossless = true
                    }
                }
                FCC_VP8_ -> if (codicStart < 0) {
                    if (!animated) {
                        codicStart = payload
                        codicLen = size.toInt()
                        codicIsLossless = false
                    }
                }
                FCC_ALPH -> if (alphStart < 0) {
                    alphStart = payload
                    alphLen = size.toInt()
                }
                FCC_ANIM, FCC_ANMF -> animated = true
                else -> Unit  // ICCP / EXIF / XMP 等：libwebp 也只是跳过
            }
            if (!haveVp8x && cc != FCC_VP8X) break  // 非扩展容器：第一个位流段之后不再看
            pos = payload + size.toInt() + (size.toInt() and 1)
        }

        if (!haveVp8x && codicStart < 0) {
            throw WebpDecodeException("WEBP-BADSTRUCT：首个段既不是 VP8X 也不是 VP8/VP8L")
        }

        // ---- 动画：原版拿到的是「画布尺寸、未写入（GDI+ 清零）」的位图，见类 KDoc ----
        if (animated) {
            if (!haveVp8x || canvasW <= 0 || canvasH <= 0) {
                throw WebpDecodeException("WEBP-BADSTRUCT：动画缺少合法的 VP8X 画布尺寸")
            }
            checkSize(canvasW, canvasH)
            val hasAlpha = flags and FLAG_ALPHA != 0
            val fill = if (hasAlpha) 0 else 0xFF000000.toInt()
            return WebpImage(canvasW, canvasH, IntArray(canvasW * canvasH) { fill }, hasAlpha)
        }

        if (codicStart < 0) {
            throw WebpDecodeException("WEBP-BADSTRUCT：VP8X 容器里没有 VP8/VP8L 位流段")
        }
        if (!codicIsLossless) {
            throw WebpDecodeException(
                "WEBP-UNSUPPORTED-LOSSY：VP8 有损位流（$codicLen 字节）未实现；语料 1893 个文件属此类" +
                    "（831 裸容器 + 1036 带 ALPH + 26 不带 ALPH）",
            )
        }

        val img = decodeVp8l(bytes, codicStart, codicLen, withHeader = true, canvasW = 0, canvasH = 0)

        // VP8X 画布必须与位流尺寸一致：libwebp `webp_dec.c:381-385` 直接判 BITSTREAM_ERROR。
        if (haveVp8x && (canvasW != img.width || canvasH != img.height)) {
            throw WebpDecodeException(
                "WEBP-BADSTRUCT：VP8X 画布 ${canvasW}x$canvasH 与 VP8L ${img.width}x${img.height} 不符",
            )
        }

        // has_alpha 的判定照 libwebp `webp_dec.c:336-347`+`:418-423`+`ReturnWidthHeight` 段：
        // 无损位流的 alpha 位**覆盖** VP8X 的 ALPHA 标志，ALPH 段再或上去；动画只认 VP8X 标志。
        val alphPresent = alphStart >= 0
        var hasAlpha = if (animated || !codicIsLossless) {
            (haveVp8x && (flags and FLAG_ALPHA) != 0) || alphPresent
        } else {
            img.alphaUsed != 0 || alphPresent
        }
        val argb = img.pixels

        if (alphStart >= 0) {
            // ALPH 段的 alpha 平面覆盖位流自带的 alpha（libwebp v1.1.0 `vp8l_dec.c:1540-1568`
            // 的 `WebPExtractGreen` + `AlphaApplyFilter`）。
            val plane = decodeAlphaPlane(bytes, alphStart, alphLen, img.width, img.height)
            for (i in argb.indices) argb[i] = (argb[i] and 0x00FFFFFF) or (plane[i] shl 24)
            hasAlpha = true
        }

        if (!hasAlpha) {
            // 原版走 Format24bppRgb + WebPDecodeBGRInto：位流自带的 alpha 被丢掉。
            for (i in argb.indices) argb[i] = argb[i] or 0xFF000000.toInt()
        }
        return WebpImage(img.width, img.height, argb, hasAlpha)
    }

    /**
     * 尺寸守卫：单边 1..[GraphicsBuffer.MAX_IMAGE_SIZE]（原版 FunctionMethodCreator.cs:6905 /
     * :8177 的 8192 上限，越界同样返回 0）+ **像素总数**上限（GraphicsBuffer.MAX_IMAGE_PIXELS）。
     *
     * 像素总数这条是移植侧显式化：原版对 8192×8192 级别的巨图是 GDI+ / libwebp 在宿主上
     * OOM 后被 `Utils.LoadImage` 的空 catch（Utils.cs:521-523）兜成 null → 0；本解码器若不做
     * 总数守卫，动画画布或 VP8L 位流会在分配 `IntArray(w * h)` 时抛 `OutOfMemoryError`，
     * 而函数层只捕 `Exception`（GraphicsMethods.kt:984-988），Error 会炸穿 VM 线程。
     * 所有分配点（动画画布、VP8L 头、ALPH 平面）都经本函数校验后才分配。
     */
    private fun checkSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > GraphicsBuffer.MAX_IMAGE_SIZE || h > GraphicsBuffer.MAX_IMAGE_SIZE) {
            throw WebpDecodeException(
                "WEBP-TOOBIG：尺寸 ${w}x$h（函数层上限 ${GraphicsBuffer.MAX_IMAGE_SIZE}，原版同样返回 0）",
            )
        }
        if (w.toLong() * h.toLong() > GraphicsBuffer.MAX_IMAGE_PIXELS) {
            throw WebpDecodeException(
                "WEBP-TOOBIG：像素总数 ${w}x$h = ${w.toLong() * h.toLong()} px 超限" +
                    "（上限 ${GraphicsBuffer.MAX_IMAGE_PIXELS}）",
            )
        }
    }

    // ------------------------------------------------------------------
    // VP8L 常量与表
    // ------------------------------------------------------------------

    private const val VP8L_MAGIC = 0x2f
    private const val NUM_LITERAL_CODES = 256
    private const val NUM_LENGTH_CODES = 24
    private const val NUM_DISTANCE_CODES = 40
    private const val LEN_CODE_LIMIT = NUM_LITERAL_CODES + NUM_LENGTH_CODES  // 280
    private const val CODE_TO_PLANE_CODES = 120
    private const val MAX_ALLOWED_CODE_LENGTH = 15
    private const val NUM_CODE_LENGTH_CODES = 19
    private const val CODE_LENGTH_LITERALS = 16
    private const val MAX_CACHE_BITS = 11
    private const val QUICK_TABLE_BITS = 12                     // Huffman 快表深度上限
    private const val ARGB_BLACK = 0xFF000000.toInt()
    private const val ARGB_AG_MASK = 0xFF00FF00.toInt()   // alpha|green 通道掩码
    private const val AVG_MASK = 0xFEFEFEFE.toInt()          // average2 的逐位半加掩码
    private const val HASH_MUL = 0x1E35A7BDL

    private val CODE_LENGTH_CODE_ORDER = intArrayOf(
        17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )
    private val CODE_LENGTH_EXTRA_BITS = intArrayOf(2, 3, 7)
    private val CODE_LENGTH_REPEAT_OFFSETS = intArrayOf(3, 3, 11)

    /** libwebp `kCodeToPlane`（`vp8l_dec.c:65-84`）：距离前缀 1..120 → (yoffset<<4)|(8-xoffset)。 */
    private val CODE_TO_PLANE = intArrayOf(
        0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a, 0x26, 0x2a,
        0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a, 0x25, 0x2b, 0x48, 0x04,
        0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b, 0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45,
        0x4b, 0x34, 0x3c, 0x03, 0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d,
        0x44, 0x4c, 0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
        0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b, 0x32, 0x3e,
        0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f, 0x64, 0x6c, 0x42, 0x4e,
        0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b, 0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e,
        0x00, 0x74, 0x7c, 0x41, 0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d,
        0x51, 0x5f, 0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70,
    )

    /** 变换类型（libwebp v1.1.0 `src/webp/format_constants.h:55-60`）。 */
    private const val T_PREDICTOR = 0
    private const val T_CROSS_COLOR = 1
    private const val T_SUBTRACT_GREEN = 2
    private const val T_COLOR_INDEXING = 3

    private class Vp8lResult(val width: Int, val height: Int, val pixels: IntArray, val alphaUsed: Int)

    private class Transform(val type: Int, var bits: Int, val xsize: Int, val ysize: Int, var data: IntArray?)

    // ------------------------------------------------------------------
    // 位读（LSB-first，对齐 libwebp `VP8LReadBits`）
    // ------------------------------------------------------------------

    private class BitReader(private val data: ByteArray, from: Int, private val until: Int) {
        private var bytePos = from
        private var value = 0L
        private var bitCount = 0
        private var consumed = 0
        private val totalBits = (until - from) * 8

        fun readBits(n: Int): Int {
            if (n <= 0) return 0
            if (n > 32) throw WebpDecodeException("WEBP-INTERNAL：一次读 $n 位超限")
            while (bitCount < n) {
                val b = if (bytePos < until) data[bytePos].toInt() and 0xFF else 0
                value = value or ((b.toLong() and 0xFFL) shl bitCount)
                bitCount += 8
                bytePos++
            }
            val mask = if (n == 32) -1L else (1L shl n) - 1L
            val r = (value and mask).toInt()
            value = value ushr n
            bitCount -= n
            consumed += n
            if (consumed > totalBits) {
                throw WebpDecodeException(
                    "WEBP-TRUNCATED：位流需要第 $consumed 位，只有 $totalBits 位",
                )
            }
            return r
        }

        /** 查表用的向前窥视（不推进、不因补零而判截断）。 */
        fun peekBits(n: Int): Int {
            while (bitCount < n) {
                val b = if (bytePos < until) data[bytePos].toInt() and 0xFF else 0
                value = value or ((b.toLong() and 0xFFL) shl bitCount)
                bitCount += 8
                bytePos++
            }
            val mask = if (n == 32) -1L else (1L shl n) - 1L
            return (value and mask).toInt()
        }

        /** 消费 [n] 位（配合 [peekBits]）。 */
        fun skip(n: Int) {
            consumed += n
            if (consumed > totalBits) {
                throw WebpDecodeException("WEBP-TRUNCATED：位流需要第 $consumed 位，只有 $totalBits 位")
            }
            var toSkip = n
            while (toSkip >= 8 && bitCount >= 8) {
                value = value ushr 8
                bitCount -= 8
                toSkip -= 8
            }
            if (toSkip > 0) {
                value = value ushr toSkip
                bitCount -= toSkip
            }
        }

        fun bitsLeft(): Int = totalBits - consumed
    }

    // ------------------------------------------------------------------
    // Huffman
    // ------------------------------------------------------------------

    /**
     * 规范 Huffman 表（libwebp `BuildHuffmanTable` 的 LSB-first 约定）：
     * 码长按 (长度, 符号) 递增分配规范码，再取**位反转**作为查表索引（码字高位在前）。
     *
     * 快表深度上限 12 位（4096 项），更长的码（13..15）用 `longCodes` 兜底；
     * 语料 646 个无损文件实测最长码字都在 12 位内，长码路径只为规范完整性而留。
     */
    private class Huffman(codeLens: IntArray, alphabetSize: Int) {
        private var singleSymbol = -1
        private val depth: Int
        private val mask: Int
        private val syms: IntArray
        private val bits: IntArray
        private val longCodes: HashMap<Int, Int>

        init {
            val count = IntArray(MAX_ALLOWED_CODE_LENGTH + 1)
            var used = 0
            for (s in 0 until alphabetSize) {
                val l = codeLens[s]
                if (l > MAX_ALLOWED_CODE_LENGTH) {
                    throw WebpDecodeException("WEBP-BADCODE：码长 $l 超过上限 $MAX_ALLOWED_CODE_LENGTH")
                }
                if (l != 0) {
                    count[l]++
                    used++
                }
            }
            if (used == 0) throw WebpDecodeException("WEBP-BADCODE：Huffman 表全零码长（字母表 $alphabetSize）")
            if (used == 1) {
                // 单符号表：不消耗任何位（libwebp 的 bits=0 特例）。
                var sym = -1
                for (i in 0 until alphabetSize) if (codeLens[i] != 0) sym = i
                singleSymbol = sym
                depth = 0
                mask = 0
                syms = IntArray(0)
                bits = IntArray(0)
                longCodes = HashMap()
            } else {
                var maxLen = 0
                for (len in 1..MAX_ALLOWED_CODE_LENGTH) {
                    if (count[len] > (1 shl len)) {
                        throw WebpDecodeException("WEBP-BADCODE：长度 $len 有 ${count[len]} 个码字，超过 ${1 shl len}")
                    }
                    if (count[len] > 0) maxLen = len
                }
                // Kraft 必须正好闭合（等价于 libwebp 的 `num_nodes == 2 * offset[15] - 1`）。
                var left = 1
                for (len in 1..MAX_ALLOWED_CODE_LENGTH) {
                    left = left shl 1
                    left -= count[len]
                    if (left < 0) throw WebpDecodeException("WEBP-BADCODE：Huffman 表在第 $len 层溢出")
                }
                if (left != 0) throw WebpDecodeException("WEBP-BADCODE：Huffman 表不是满树（余 $left）")

                depth = minOf(maxLen, QUICK_TABLE_BITS)
                mask = (1 shl depth) - 1
                syms = IntArray(1 shl depth)
                bits = IntArray(1 shl depth)   // 0 = 空槽；-1 = 需要走长码
                longCodes = HashMap()
                var code = 0
                for (len in 1..maxLen) {
                    if (count[len] == 0) {
                        code = code shl 1
                        continue
                    }
                    for (sym in 0 until alphabetSize) {
                        if (codeLens[sym] != len) continue
                        val reversed = reverseBits(code, len)
                        if (len <= depth) {
                            var idx = reversed
                            val step = 1 shl len
                            while (idx < syms.size) {
                                syms[idx] = sym
                                bits[idx] = len
                                idx += step
                            }
                        } else {
                            longCodes[(len shl 16) or reversed] = sym
                            var idx = reversed and mask
                            while (idx < syms.size) {
                                bits[idx] = -1
                                idx += 1 shl depth
                            }
                        }
                        code++
                    }
                    code = code shl 1
                }
            }
        }

        fun read(br: BitReader): Int {
            if (singleSymbol >= 0) return singleSymbol
            val v = br.peekBits(depth) and mask
            val len = bits[v]
            if (len > 0) {
                br.skip(len)
                return syms[v]
            }
            if (len == 0) throw WebpDecodeException("WEBP-BADCODE：Huffman 查表命中空洞（索引 $v）")
            // 长码：从 depth 位继续逐位补全。
            var acc = v
            var curLen = depth
            while (curLen < MAX_ALLOWED_CODE_LENGTH) {
                acc = acc or (br.readBits(1) shl curLen)
                curLen++
                val s = longCodes[(curLen shl 16) or acc]
                if (s != null) return s
            }
            throw WebpDecodeException("WEBP-BADCODE：长度 $MAX_ALLOWED_CODE_LENGTH 内找不到码字")
        }

        companion object {
            fun reverseBits(value: Int, len: Int): Int {
                var v = 0
                for (i in 0 until len) {
                    if ((value and (1 shl i)) != 0) v = v or (1 shl (len - 1 - i))
                }
                return v
            }
        }
    }

    /** 一组 5 棵 Huffman 树：GREEN(0) / RED(1) / BLUE(2) / ALPHA(3) / DIST(4)。 */
    private class TreeGroup(val green: Huffman, val red: Huffman, val blue: Huffman, val alpha: Huffman, val dist: Huffman)

    /** 一层图像的元数据。 */
    private class Meta(
        val groups: Array<TreeGroup?>,
        val huffmanImage: IntArray?,
        val huffmanBits: Int,
        val huffmanXsize: Int,
        val colorCacheBits: Int,
    ) {
        val colorCacheSize: Int = if (colorCacheBits > 0) 1 shl colorCacheBits else 0

        fun groupFor(x: Int, y: Int): TreeGroup {
            val img = huffmanImage ?: return groups[0] ?: throw WebpDecodeException("WEBP-INTERNAL：0 号组未填充")
            if (huffmanBits == 0) return groups[0] ?: throw WebpDecodeException("WEBP-INTERNAL：0 号组未填充")
            val idx = img[huffmanXsize * (y shr huffmanBits) + (x shr huffmanBits)]
            val g = if (idx in groups.indices) groups[idx] else null
            return g ?: throw WebpDecodeException(
                "WEBP-BADCODE：meta 组号 $idx 越界或未填充（组 ${groups.size}，bits=$huffmanBits，" +
                    "hx=$huffmanXsize，x=$x y=$y，图长 ${img.size}）",
            )
        }
    }

    private fun subsample(length: Int, bits: Int): Int =
        if (bits == 0) length else (length + (1 shl bits) - 1) shr bits

    // ------------------------------------------------------------------
    // VP8L 解码入口
    // ------------------------------------------------------------------

    /**
     * 解一段 VP8L 位流。
     *
     * @param withHeader 色/主位流为真（前 5 字节是 0x2f + 宽高 + alpha + 版本）；
     *   `ALPH` 段里的无损平面**没有**这 5 字节头，尺寸由 `VP8X` 画布给出
     *   （libwebp v1.1.0 `vp8l_dec.c:1568-1590` 的 `VP8LDecodeAlphaHeader` 直接调
     *   `DecodeImageStream(width, height, 1, ...)`，不读 `ReadImageInfo`）。
     */
    private fun decodeVp8l(
        data: ByteArray,
        from: Int,
        len: Int,
        withHeader: Boolean,
        canvasW: Int,
        canvasH: Int,
    ): Vp8lResult {
        val br = BitReader(data, from, from + len)
        var width = canvasW
        var height = canvasH
        var alphaUsed = 0
        if (withHeader) {
            if (br.readBits(8) != VP8L_MAGIC) throw WebpDecodeException("WEBP-BADCODE：VP8L 魔术字节不是 0x2f")
            width = br.readBits(14) + 1
            height = br.readBits(14) + 1
            alphaUsed = br.readBits(1)
            val version = br.readBits(3)
            if (version != 0) throw WebpDecodeException("WEBP-UNSUPPORTED：VP8L 版本号 $version（仅支持 0）")
            checkSize(width, height)
        }
        if (width <= 0 || height <= 0) throw WebpDecodeException("WEBP-BADSTRUCT：尺寸非法 ${width}x$height")

        // ---- 变换链（只在 level 0 读，vp8l_dec.c:1426-1431）----
        val transforms = ArrayList<Transform>(4)
        var seen = 0
        var xs = width
        var ys = height
        while (br.readBits(1) != 0) {
            if (transforms.size >= 4) throw WebpDecodeException("WEBP-UNSUPPORTED：变换条数超过 4")
            val type = br.readBits(2)
            if (seen and (1 shl type) != 0) {
                throw WebpDecodeException("WEBP-BADCODE：变换类型 $type 重复出现")
            }
            seen = seen or (1 shl type)
            val t = Transform(type, 0, xs, ys, null)
            transforms.add(t)
            when (type) {
                T_PREDICTOR, T_CROSS_COLOR -> {
                    t.bits = br.readBits(3) + 2
                    t.data = decodePlainLayer(br, subsample(t.xsize, t.bits), subsample(t.ysize, t.bits))
                }
                T_COLOR_INDEXING -> {
                    val numColors = br.readBits(8) + 1
                    val bits = when {
                        numColors > 16 -> 0
                        numColors > 4 -> 1
                        numColors > 2 -> 2
                        else -> 3
                    }
                    t.bits = bits
                    xs = subsample(t.xsize, bits)
                    t.data = expandColorMap(numColors, bits, decodePlainLayer(br, numColors, 1))
                }
                else -> t.bits = 0  // T_SUBTRACT_GREEN：无元数据
            }
        }

        val meta = readMeta(br, xs, ys, allowRecursion = true)
        val packed = decodePixels(br, meta, xs, ys)
        val argb = applyInverseTransforms(packed, xs, ys, width, transforms)
        return Vp8lResult(width, height, argb, alphaUsed)
    }

    /** 非 level 0 的一层（变换数据 / meta 图 / 调色板）：无变换、无 meta-prefix。 */
    private fun decodePlainLayer(br: BitReader, xsize: Int, ysize: Int): IntArray {
        val meta = readMeta(br, xsize, ysize, allowRecursion = false)
        return decodePixels(br, meta, xsize, ysize)
    }

    /** `ExpandColorMap`（vp8l_dec.c:1267-1290）：调色板逐字节前缀累加并补黑尾。 */
    private fun expandColorMap(numColors: Int, bits: Int, palette: IntArray): IntArray {
        val finalCount = 1 shl (8 shr bits)
        val out = IntArray(finalCount)
        if (palette.isEmpty()) return out
        val bytes = ByteArray(finalCount * 4)
        for (i in 0 until minOf(palette.size, finalCount)) {
            val v = palette[i]
            bytes[i * 4] = (v and 0xFF).toByte()
            bytes[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        val n = minOf(numColors, finalCount) * 4
        for (i in 4 until n) {
            bytes[i] = (((bytes[i].toInt() and 0xFF) + (bytes[i - 4].toInt() and 0xFF)) and 0xFF).toByte()
        }
        for (i in 0 until finalCount) {
            out[i] = (bytes[i * 4].toInt() and 0xFF) or
                ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        return out
    }

    /** 颜色缓存位 + Huffman 码（可带 meta-prefix 递归）。 */
    private fun readMeta(br: BitReader, xsize: Int, ysize: Int, allowRecursion: Boolean): Meta {
        var colorCacheBits = 0
        if (br.readBits(1) != 0) {
            colorCacheBits = br.readBits(4)
            if (colorCacheBits < 1 || colorCacheBits > MAX_CACHE_BITS) {
                throw WebpDecodeException("WEBP-BADCODE：颜色缓存位数 $colorCacheBits 非法（1..$MAX_CACHE_BITS）")
            }
        }

        var huffmanBits = 0
        var huffmanImage: IntArray? = null
        var huffmanXsize = 1
        var numGroups = 1
        var numGroupsToRead = 1
        var mapping: IntArray? = null
        if (allowRecursion && br.readBits(1) != 0) {
            // meta Huffman 码（vp8l_dec.c:375-420）：每 2^bits 的块一个组号，组号在 R|G 两字节里。
            huffmanBits = br.readBits(3) + 2
            val hx = subsample(xsize, huffmanBits)
            val hy = subsample(ysize, huffmanBits)
            val raw = decodePlainLayer(br, hx, hy)
            huffmanXsize = hx
            // 组号存在 R|G 两个字节里，先原地换成紧凑组号（libwebp：`huffman_image[i] = group`）。
            var maxGroup = 0
            for (i in raw.indices) {
                val g = (raw[i] ushr 8) and 0xFFFF
                raw[i] = g
                if (g > maxGroup) maxGroup = g
            }
            val groupsMax = maxGroup + 1
            numGroups = groupsMax
            // 照 vp8l_dec.c:400-419 的阈值（右值是**整层**尺寸，不是子采样后的尺寸）：看着过大就把组号
            // 重映射成 [0, num) 的紧凑编号。注意重映射只影响**存储**：:447-460 仍会为每个未用到的组
            // 读一遍 5 棵 Huffman 码（只是传 NULL 不填表），少读就会让后面的位流整体错位。
            if (groupsMax > 1000 || groupsMax > xsize * ysize) {
                val map = IntArray(groupsMax) { -1 }
                var next = 0
                for (i in raw.indices) {
                    val g = raw[i]
                    if (map[g] < 0) map[g] = next++
                    raw[i] = map[g]
                }
                mapping = map
                numGroups = next
            }
            numGroupsToRead = groupsMax
            if (numGroups == 1) {
                huffmanBits = 0
                huffmanImage = null
            } else {
                huffmanImage = raw
            }
        }

        val groups = readTreeGroups(br, numGroups, numGroupsToRead, mapping, colorCacheBits)
        return Meta(groups, huffmanImage, huffmanBits, huffmanXsize, colorCacheBits)
    }

    private fun readTreeGroups(
        br: BitReader,
        numGroups: Int,
        numGroupsToRead: Int,
        mapping: IntArray?,
        colorCacheBits: Int,
    ): Array<TreeGroup?> {
        val out = arrayOfNulls<TreeGroup>(numGroups)
        for (g in 0 until numGroupsToRead) {
            val tree = TreeGroup(
                readHuffmanCode(br, LEN_CODE_LIMIT + (if (colorCacheBits > 0) 1 shl colorCacheBits else 0)),
                readHuffmanCode(br, NUM_LITERAL_CODES),
                readHuffmanCode(br, NUM_LITERAL_CODES),
                readHuffmanCode(br, NUM_LITERAL_CODES),
                readHuffmanCode(br, NUM_DISTANCE_CODES),
            )
            val target = mapping?.get(g) ?: g
            if (target in 0 until numGroups) out[target] = tree
        }
        return out
    }

    /** `ReadHuffmanCode`（vp8l_dec.c:309-357）：simple 码或规范码。 */
    private fun readHuffmanCode(br: BitReader, alphabetSize: Int): Huffman {
        val codeLens = IntArray(LEN_CODE_LIMIT + (1 shl MAX_CACHE_BITS))
        if (br.readBits(1) != 0) {
            val numSymbols = br.readBits(1) + 1
            val firstLenCode = br.readBits(1)
            val symbol0 = br.readBits(if (firstLenCode == 0) 1 else 8)
            if (symbol0 < codeLens.size) codeLens[symbol0] = 1
            if (numSymbols == 2) {
                val symbol1 = br.readBits(8)
                if (symbol1 < codeLens.size) codeLens[symbol1] = 1
            }
            return Huffman(codeLens, alphabetSize)
        }
        val codeLenLens = IntArray(NUM_CODE_LENGTH_CODES)
        val numCodes = br.readBits(4) + 4
        for (i in 0 until numCodes) {
            codeLenLens[CODE_LENGTH_CODE_ORDER[i]] = br.readBits(3)
        }
        val codeLenTree = Huffman(codeLenLens, NUM_CODE_LENGTH_CODES)
        readCodeLengths(br, codeLenTree, alphabetSize, codeLens)
        return Huffman(codeLens, alphabetSize)
    }

    /** 码长表（19 个符号）自建一棵 Huffman；与主表同一套 LSB-first 约定。 */

    /** `ReadHuffmanCodeLengths`（vp8l_dec.c:248-307）。 */
    private fun readCodeLengths(br: BitReader, code: Huffman, numSymbols: Int, out: IntArray) {
        var prevCodeLen = 8
        var maxSymbol: Int
        if (br.readBits(1) != 0) {
            val lengthNbits = 2 + 2 * br.readBits(3)
            maxSymbol = 2 + br.readBits(lengthNbits)
            if (maxSymbol > numSymbols) throw WebpDecodeException("WEBP-BADCODE：max_symbol $maxSymbol > $numSymbols")
        } else {
            maxSymbol = numSymbols
        }
        var symbol = 0
        while (symbol < numSymbols) {
            if (maxSymbol == 0) break
            maxSymbol--
            val codeLen = code.read(br)
            if (codeLen < CODE_LENGTH_LITERALS) {
                out[symbol++] = codeLen
                if (codeLen != 0) prevCodeLen = codeLen
            } else {
                val slot = codeLen - CODE_LENGTH_LITERALS
                if (slot < 0 || slot > 2) throw WebpDecodeException("WEBP-BADCODE：码长重复码 $codeLen")
                val repeat = br.readBits(CODE_LENGTH_EXTRA_BITS[slot]) + CODE_LENGTH_REPEAT_OFFSETS[slot]
                if (symbol + repeat > numSymbols) {
                    throw WebpDecodeException("WEBP-BADCODE：码长重复越界（$symbol + $repeat > $numSymbols）")
                }
                val value = if (codeLen == CODE_LENGTH_LITERALS) prevCodeLen else 0
                for (i in 0 until repeat) out[symbol++] = value
            }
        }
    }

    // ------------------------------------------------------------------
    // 像素层（LZ77 + 颜色缓存）
    // ------------------------------------------------------------------

    private fun decodePixels(br: BitReader, meta: Meta, width: Int, height: Int): IntArray {
        val data = IntArray(width * height)
        val cacheSize = meta.colorCacheSize
        val cacheBits = meta.colorCacheBits
        val cache = if (cacheSize > 0) IntArray(cacheSize) else null
        val cacheLimit = LEN_CODE_LIMIT + cacheSize
        var pos = 0
        while (pos < data.size) {
            val group = meta.groupFor(pos % width, pos / width)
            val code = group.green.read(br)   // GREEN 通道 + 长度码 + 颜色缓存码
            if (code < NUM_LITERAL_CODES) {
                val red = group.red.read(br)
                val blue = group.blue.read(br)
                val alpha = group.alpha.read(br)
                data[pos] = (alpha shl 24) or (red shl 16) or (code shl 8) or blue
                pos++
                if (cache != null) insertCache(cache, data[pos - 1], cacheBits)
            } else if (code < LEN_CODE_LIMIT) {
                val length = prefixValue(code - NUM_LITERAL_CODES, br)
                val distSymbol = group.dist.read(br)
                val distCode = prefixValue(distSymbol, br)
                val dist = planeCodeToDistance(width, distCode)
                if (pos < dist || data.size - pos < length) {
                    throw WebpDecodeException(
                        "WEBP-BADCODE：LZ77 越界（pos=$pos dist=$dist length=$length / ${data.size}）",
                    )
                }
                for (i in 0 until length) data[pos + i] = data[pos + i - dist]
                pos += length
                if (cache != null) {
                    for (p in (pos - length) until pos) insertCache(cache, data[p], cacheBits)
                }
            } else if (cache != null && code < cacheLimit) {
                data[pos] = cache[code - LEN_CODE_LIMIT]
                pos++
                insertCache(cache, data[pos - 1], cacheBits)
            } else {
                throw WebpDecodeException("WEBP-BADCODE：符号 $code 超出字母表（颜色缓存位 $cacheBits）")
            }
        }
        return data
    }

    /** `VP8LHashPix`（color_cache_utils.h:38-43）：64 位乘法的**高**位取 key。 */
    private fun insertCache(cache: IntArray, argb: Int, bits: Int) {
        // 乘积是 **32 位** 回绕（C 的 uint32 算术），再取高 `bits` 位。
        val prod = ((argb.toLong() and 0xFFFFFFFFL) * HASH_MUL) and 0xFFFFFFFFL
        val key = (prod ushr (32 - bits)).toInt()
        cache[key] = argb
    }

    /** `GetCopyLength` / `GetCopyDistance`（vp8l_dec.c:150-165）：长度与距离共用一套映射。 */
    private fun prefixValue(symbol: Int, br: BitReader): Int {
        if (symbol < 4) return symbol + 1
        val extraBits = (symbol - 2) ushr 1
        val offset = (2 + (symbol and 1)) shl extraBits
        return offset + br.readBits(extraBits) + 1
    }

    /** `PlaneCodeToDistance`（vp8l_dec.c:167-177）。 */
    private fun planeCodeToDistance(xsize: Int, planeCode: Int): Int {
        if (planeCode > CODE_TO_PLANE_CODES) return planeCode - CODE_TO_PLANE_CODES
        if (planeCode <= 0) throw WebpDecodeException("WEBP-BADCODE：距离前缀码 $planeCode 非法")
        val distCode = CODE_TO_PLANE[planeCode - 1]
        val yoffset = distCode ushr 4
        val xoffset = 8 - (distCode and 0xF)
        val dist = yoffset * xsize + xoffset
        return if (dist >= 1) dist else 1
    }

    // ------------------------------------------------------------------
    // 逆变换
    // ------------------------------------------------------------------

    private fun applyInverseTransforms(
        packed: IntArray,
        packedWidth: Int,
        rows: Int,
        finalWidth: Int,
        transforms: List<Transform>,
    ): IntArray {
        if (transforms.isEmpty()) return packed
        val out = IntArray(finalWidth * rows)
        var current = packed
        var curWidth = packedWidth
        for (i in transforms.indices.reversed()) {
            val t = transforms[i]
            when (t.type) {
                T_COLOR_INDEXING -> {
                    if (current === out) colorIndexInverseInPlace(t, out, rows) else {
                        colorIndexInverseSeparate(t, current, curWidth, out, rows)
                        current = out
                    }
                }
                T_SUBTRACT_GREEN, T_PREDICTOR, T_CROSS_COLOR -> {
                    if (curWidth != t.xsize) {
                        throw WebpDecodeException("WEBP-BADCODE：逆变换行宽不符（$curWidth vs ${t.xsize}）")
                    }
                    if (current !== out) {
                        System.arraycopy(current, 0, out, 0, curWidth * rows)
                        current = out
                    }
                    when (t.type) {
                        T_SUBTRACT_GREEN -> addGreenToBlueAndRed(out, curWidth * rows)
                        T_PREDICTOR -> predictorInverse(t, out, rows)
                        else -> colorSpaceInverse(t, out, rows)
                    }
                }
            }
            curWidth = t.xsize
        }
        return current
    }

    private fun colorIndexInverseSeparate(t: Transform, src: IntArray, srcWidth: Int, dst: IntArray, rows: Int) {
        val width = t.xsize
        val colors = t.data ?: throw WebpDecodeException("WEBP-INTERNAL：调色板缺失")
        if (t.bits == 0) {
            for (i in 0 until srcWidth * rows) {
                // 索引在**绿色通道**里，不是整个 ARGB 字：RFC 9649 §3.5.4 的逆变换是
                // argb = color_table[GREEN(argb)]；libwebp v1.1.0 src/dsp/lossless_common.h:31-33
                // 的 VP8GetARGBIndex 就是 (idx >> 8) & 0xff。
                val idx = (src[i] ushr 8) and 0xFF
                if (idx >= colors.size) {
                    throw WebpDecodeException("WEBP-BADCODE：调色板索引 $idx 越界（表 ${colors.size} 项）")
                }
                dst[i] = colors[idx]
            }
            return
        }
        val bitsPerPixel = 8 shr t.bits
        val perWord = 1 shl t.bits
        val bitMask = (1 shl bitsPerPixel) - 1
        var sp = 0
        var dp = 0
        for (y in 0 until rows) {
            var x = 0
            var packed = 0
            while (x < width) {
                if ((x and (perWord - 1)) == 0) {
                    if (sp >= src.size) throw WebpDecodeException("WEBP-TRUNCATED：调色板像素不足")
                    // 每个打包字节都在源像素的绿色通道（同上：lossless_common.h:31-33 的
                    // VP8GetARGBIndex；libwebp src/dsp/lossless.c:370-371 把它作 GET_INDEX 形参传给
                    // COLOR_INDEX_INVERSE 宏，打包循环在 :360-362：每 perWord 个像素重新取一次
                    // GET_INDEX、按 LSB 先出 & bit_mask 后 >>= bitsPerPixel）。
                    packed = (src[sp++] ushr 8) and 0xFF
                }
                dst[dp++] = colors[packed and bitMask]
                packed = packed ushr bitsPerPixel
                x++
            }
        }
    }

    /** `ColorIndexInverseTransform_C` 的 in == out 形态（libwebp 用 memmove 技巧）。 */
    private fun colorIndexInverseInPlace(t: Transform, img: IntArray, rows: Int) {
        val width = t.xsize
        val colors = t.data ?: throw WebpDecodeException("WEBP-INTERNAL：调色板缺失")
        if (t.bits == 0) {
            for (i in 0 until width * rows) {
                // 同 colorIndexInverseSeparate：索引在绿色通道（lossless_common.h:31-33）。
                val idx = (img[i] ushr 8) and 0xFF
                if (idx >= colors.size) {
                    throw WebpDecodeException("WEBP-BADCODE：调色板索引 $idx 越界（表 ${colors.size} 项）")
                }
                img[i] = colors[idx]
            }
            return
        }
        val outStride = rows * width
        val inStride = rows * subsample(width, t.bits)
        if (outStride > img.size || inStride > outStride) {
            throw WebpDecodeException("WEBP-BADCODE：调色板展开的缓冲尺寸不符")
        }
        System.arraycopy(img, 0, img, outStride - inStride, inStride)
        val bitsPerPixel = 8 shr t.bits
        val perWord = 1 shl t.bits
        val bitMask = (1 shl bitsPerPixel) - 1
        var sp = outStride - inStride
        var dp = 0
        while (dp < outStride) {
            // 打包字也取绿色通道（lossless_common.h:31-33 的 VP8GetARGBIndex）。
            var packed = (img[sp++] ushr 8) and 0xFF
            var k = 0
            while (k < perWord && dp < outStride) {
                img[dp++] = colors[packed and bitMask]
                packed = packed ushr bitsPerPixel
                k++
            }
        }
    }

    /** `VP8LAddGreenToBlueAndRed`（dsp/lossless.c:265-276）。 */
    private fun addGreenToBlueAndRed(img: IntArray, count: Int) {
        for (i in 0 until count) {
            val argb = img[i]
            val green = (argb ushr 8) and 0xFF
            var redBlue = argb and 0x00FF00FF
            redBlue += (green shl 16) or green
            redBlue = redBlue and 0x00FF00FF
            img[i] = (argb and ARGB_AG_MASK) or redBlue
        }
    }

    /** `PredictorInverseTransform_C`（dsp/lossless.c:201-250）。 */
    private fun predictorInverse(t: Transform, img: IntArray, rows: Int) {
        val width = t.xsize
        val modes = t.data ?: throw WebpDecodeException("WEBP-INTERNAL：预测模式图缺失")
        if (width <= 0 || rows <= 0) return
        // 第 0 行：首像素模式 0，其余模式 1（左）。
        img[0] = addPixels(img[0], ARGB_BLACK)
        for (x in 1 until width) img[x] = addPixels(img[x], img[x - 1])
        val tileWidth = 1 shl t.bits
        val mask = tileWidth - 1
        val tilesPerRow = subsample(width, t.bits)
        var y = 1
        while (y < rows) {
            val rowBase = y * width
            // 行首像素模式 2（上）。
            img[rowBase] = addPixels(img[rowBase], img[rowBase - width])
            var x = 1
            var tileIdx = (y shr t.bits) * tilesPerRow
            while (x < width) {
                if (tileIdx >= modes.size) throw WebpDecodeException("WEBP-TRUNCATED：预测模式图不够（行 $y）")
                val mode = (modes[tileIdx++] ushr 8) and 0xF
                var xEnd = (x and mask.inv()) + tileWidth
                if (xEnd > width) xEnd = width
                for (px in x until xEnd) {
                    val i = rowBase + px
                    img[i] = addPixels(img[i], predictor(mode, img, i, px, width))
                }
                x = xEnd
            }
            y++
        }
    }

    /** `VP8LPredictor0_C`..`13_C`（dsp/lossless.c:110-196）；left = 已还原的左邻、top = 上一行。 */
    private fun predictor(mode: Int, img: IntArray, i: Int, x: Int, width: Int): Int {
        val left = img[i - 1]
        val upper = i - width
        return when (mode) {
            0 -> ARGB_BLACK
            1 -> left
            2 -> img[upper]
            3 -> safeAt(img, upper + 1)
            4 -> safeAt(img, upper - 1)
            5 -> average3(left, img[upper], safeAt(img, upper + 1))
            6 -> average2(left, safeAt(img, upper - 1))
            7 -> average2(left, img[upper])
            8 -> average2(safeAt(img, upper - 1), img[upper])
            9 -> average2(img[upper], safeAt(img, upper + 1))
            10 -> average4(left, safeAt(img, upper - 1), img[upper], safeAt(img, upper + 1))
            11 -> select(img[upper], left, safeAt(img, upper - 1))
            12 -> clampedAddSubtractFull(left, img[upper], safeAt(img, upper - 1))
            13 -> clampedAddSubtractHalf(left, img[upper], safeAt(img, upper - 1))
            else -> ARGB_BLACK   // 14/15 是 libwebp 的 padding sentinel
        }
    }

    private fun safeAt(img: IntArray, idx: Int): Int = if (idx in img.indices) img[idx] else 0

    /** `VP8LAddPixels`：逐通道相加取低 8 位。 */
    private fun addPixels(a: Int, b: Int): Int {
        val alphaAndGreen = (a and ARGB_AG_MASK) + (b and ARGB_AG_MASK)
        val redAndBlue = (a and 0x00FF00FF) + (b and 0x00FF00FF)
        return (alphaAndGreen and ARGB_AG_MASK) or (redAndBlue and 0x00FF00FF)
    }

    private fun average2(a: Int, b: Int): Int =
        (((a xor b) and AVG_MASK) ushr 1) + (a and b)

    private fun average3(a: Int, b: Int, c: Int): Int = average2(average2(a, c), b)

    private fun average4(a: Int, b: Int, c: Int, d: Int): Int = average2(average2(a, b), average2(c, d))

    private fun sub3(a: Int, b: Int, c: Int): Int =
        kotlin.math.abs(b - c) - kotlin.math.abs(a - c)

    private fun select(a: Int, b: Int, c: Int): Int {
        val d = sub3(a ushr 24, b ushr 24, c ushr 24) +
            sub3((a ushr 16) and 0xFF, (b ushr 16) and 0xFF, (c ushr 16) and 0xFF) +
            sub3((a ushr 8) and 0xFF, (b ushr 8) and 0xFF, (c ushr 8) and 0xFF) +
            sub3(a and 0xFF, b and 0xFF, c and 0xFF)
        return if (d <= 0) a else b
    }

    private fun clip255(v: Int): Int = if (v in 0..255) v else if (v < 0) 0 else 255

    private fun componentFull(a: Int, b: Int, c: Int): Int = clip255(a + b - c)

    private fun clampedAddSubtractFull(c0: Int, c1: Int, c2: Int): Int {
        val a = componentFull(c0 ushr 24, c1 ushr 24, c2 ushr 24)
        val r = componentFull((c0 ushr 16) and 0xFF, (c1 ushr 16) and 0xFF, (c2 ushr 16) and 0xFF)
        val g = componentFull((c0 ushr 8) and 0xFF, (c1 ushr 8) and 0xFF, (c2 ushr 8) and 0xFF)
        val b = componentFull(c0 and 0xFF, c1 and 0xFF, c2 and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun componentHalf(a: Int, b: Int): Int = clip255(a + (a - b) / 2)

    private fun clampedAddSubtractHalf(c0: Int, c1: Int, c2: Int): Int {
        val ave = average2(c0, c1)
        val a = componentHalf(ave ushr 24, c2 ushr 24)
        val r = componentHalf((ave ushr 16) and 0xFF, (c2 ushr 16) and 0xFF)
        val g = componentHalf((ave ushr 8) and 0xFF, (c2 ushr 8) and 0xFF)
        val b = componentHalf(ave and 0xFF, c2 and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `ColorSpaceInverseTransform_C` + `VP8LTransformColorInverse_C`（dsp/lossless.c:278-345）。 */
    private fun colorSpaceInverse(t: Transform, img: IntArray, rows: Int) {
        val width = t.xsize
        val mult = t.data ?: throw WebpDecodeException("WEBP-INTERNAL：色彩变换图缺失")
        val tileWidth = 1 shl t.bits
        val tilesPerRow = subsample(width, t.bits)
        for (y in 0 until rows) {
            var tileIdx = (y shr t.bits) * tilesPerRow
            var x = 0
            while (x < width) {
                if (tileIdx >= mult.size) throw WebpDecodeException("WEBP-TRUNCATED：色彩变换图不够（行 $y）")
                val code = mult[tileIdx++]
                val greenToRed = (code and 0xFF).toByte().toInt()
                val greenToBlue = ((code ushr 8) and 0xFF).toByte().toInt()
                val redToBlue = ((code ushr 16) and 0xFF).toByte().toInt()
                val xEnd = minOf(x + tileWidth, width)
                while (x < xEnd) {
                    val argb = img[y * width + x]
                    val green = ((argb ushr 8) and 0xFF).toByte().toInt()
                    var newRed = (argb ushr 16) and 0xFF
                    var newBlue = argb and 0xFF
                    newRed += colorTransformDelta(greenToRed, green)
                    newRed = newRed and 0xFF
                    newBlue += colorTransformDelta(greenToBlue, green)
                    newBlue += colorTransformDelta(redToBlue, newRed.toByte().toInt())
                    newBlue = newBlue and 0xFF
                    img[y * width + x] = (argb and ARGB_AG_MASK) or (newRed shl 16) or newBlue
                    x++
                }
            }
        }
    }

    private fun colorTransformDelta(colorPred: Int, color: Int): Int = (colorPred * color) shr 5

    // ------------------------------------------------------------------
    // ALPH 段
    // ------------------------------------------------------------------

    /**
     * 解 `ALPH` 段。头字节：method 位 0-1、filter 位 2-3、pre-processing 位 4-5、保留位 6-7
     * （libwebp v1.1.0 `src/dec/alpha_dec.c` 的 `ALPHInit`；编码侧同布局：`src/enc/alpha_enc.c:171-172`
     * 的 `header = method | (filter << 2)`，`reduce_levels` 再 `|= 1 << 4`）。
     */
    private fun decodeAlphaPlane(data: ByteArray, from: Int, len: Int, width: Int, height: Int): IntArray {
        if (len <= 1) throw WebpDecodeException("WEBP-BADSTRUCT：ALPH 段载荷不足 1 字节")
        val hdr = data[from].toInt() and 0xFF
        val method = hdr and 0x03
        val filter = (hdr ushr 2) and 0x03
        val preProcessing = (hdr ushr 4) and 0x03
        val reserved = (hdr ushr 6) and 0x03
        if (method > 1) throw WebpDecodeException("WEBP-UNSUPPORTED：ALPH 压缩方法 $method（只支持 0 未压缩 / 1 无损）")
        if (filter > 3) throw WebpDecodeException("WEBP-BADSTRUCT：ALPH 滤波器 $filter")
        if (preProcessing > 1) throw WebpDecodeException("WEBP-UNSUPPORTED：ALPH 预处理等级 $preProcessing")
        if (reserved != 0) throw WebpDecodeException("WEBP-BADSTRUCT：ALPH 保留位非 0")

        val n = width * height
        val alpha = IntArray(n)
        val payload = from + 1
        val payloadLen = len - 1
        if (method == 0) {
            if (payloadLen < n) throw WebpDecodeException("WEBP-TRUNCATED：未压缩 ALPH 只有 $payloadLen 字节，需要 $n")
            for (i in 0 until n) alpha[i] = data[payload + i].toInt() and 0xFF
        } else {
            // 无损 alpha 平面：不带 5 字节头，尺寸取画布；alpha 取绿色通道。
            val img = decodeVp8l(data, payload, payloadLen, withHeader = false, canvasW = width, canvasH = height)
            if (img.width != width || img.height != height) {
                throw WebpDecodeException(
                    "WEBP-BADSTRUCT：ALPH 无损图 ${img.width}x${img.height} 与画布 ${width}x$height 不符",
                )
            }
            for (i in 0 until n) alpha[i] = (img.pixels[i] ushr 8) and 0xFF
        }
        if (filter != 0) applyAlphaUnfilter(filter, alpha, width, height)
        return alpha
    }

    /** `WebPUnfilters`（libwebp `src/utils/utils.c`：none / horizontal / vertical / gradient）。 */
    private fun applyAlphaUnfilter(filter: Int, alpha: IntArray, width: Int, height: Int) {
        var prevBase = -1
        var y = 0
        while (y < height) {
            val base = y * width
            when (filter) {
                1 -> {
                    var pred = if (prevBase < 0) 0 else alpha[prevBase]
                    for (x in 0 until width) {
                        pred = (pred + alpha[base + x]) and 0xFF
                        alpha[base + x] = pred
                    }
                }
                2 -> {
                    if (prevBase < 0) {
                        var pred = 0
                        for (x in 0 until width) {
                            pred = (pred + alpha[base + x]) and 0xFF
                            alpha[base + x] = pred
                        }
                    } else {
                        for (x in 0 until width) alpha[base + x] = (alpha[prevBase + x] + alpha[base + x]) and 0xFF
                    }
                }
                else -> {
                    if (prevBase < 0) {
                        var pred = 0
                        for (x in 0 until width) {
                            pred = (pred + alpha[base + x]) and 0xFF
                            alpha[base + x] = pred
                        }
                    } else {
                        var top = alpha[prevBase]
                        var topLeft = top
                        var left = top
                        for (x in 0 until width) {
                            top = alpha[prevBase + x]
                            val g = left + top - topLeft
                            val p = if (g < 0) 0 else if (g > 255) 255 else g
                            left = (alpha[base + x] + p) and 0xFF
                            topLeft = top
                            alpha[base + x] = left
                        }
                    }
                }
            }
            prevBase = base
            y++
        }
    }
}
