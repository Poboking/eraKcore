package com.eratw.port.core.image

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L02 图像解码审计（续任）：webp 无损 **color indexing 的索引通道**，以及无损像素层的
 * 通道布局 / alpha 口径。
 *
 * ## 语义依据
 *
 * - WebP 无损的 color indexing 正变换把调色板索引写进**绿色通道**（alpha=255、红蓝=0），
 *   逆变换是 `argb = color_table[GREEN(argb)]` —— RFC 9649 §3.5.4 的正文式子（该节明确
 *   "The indexing is done based on the green component of the ARGB color"），也是 libwebp
 *   v1.1.0 `src/dsp/lossless_common.h:31-33` 的 `VP8GetARGBIndex`（体是 `(idx >> 8) & 0xff`）；
 *   `src/dsp/lossless.c:370-371` 把它作 `GET_INDEX` 形参传给 `COLOR_INDEX_INVERSE` 宏
 *   （宏体在 :329-368），逐像素支路取的就是 `color_map[GET_INDEX(*src)]`（:337）。
 *   子字节打包（`bits > 0`）时，一个源像素的**绿字节**里按 LSB 先出塞 `1 << bits` 个索引
 *   （同一宏的 :360-362：`(x & count_mask) == 0` 时重新取绿字节，`& bit_mask` 后 `>>=`）。
 * - 原版 `.webp` 走 libwebp（`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs:511-514`
 *   的 `WebP.Load`，`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/WebP.cs:16-27`），
 *   所以绿字节取索引是原版可见像素语义，不是我们的选择。
 * - 无损像素的 ARGB 布局（alpha 位 24-31、红 16-23、绿 8-15、蓝 0-7）与
 *   `WebPDecodeBGRAInto` 写进 GDI+ `Format32bppArgb` 的字节序同构
 *   （`WebP.cs:37` 按 `has_alpha` 选 `Format32bppArgb` / `Format24bppRgb`，`:41-48` 二选一）。
 * - `alpha_is_used = 0` ⇒ `has_alpha` 为假 ⇒ 原版建 `Format24bppRgb` 并用
 *   `WebPDecodeBGRInto`（`WebP.cs:43`，MODE_BGR 丢 alpha）——位流自带的 alpha 被丢弃、恒为 255。
 *
 * ## 为什么这里的用例必须区分「整字」与「绿字节」
 *
 * 修正前的实现把**整个 ARGB 字**当索引：`bits == 0` 时索引 ≥ 2^24 直接抛越界（本路语料普查
 * 登记的 26→125 差值 99 个文件即此分支；该读数本轮未复核 ⇒ TODO(verify)，卡点是 corpusSmoke
 * 不在本次闸内），`bits > 0` 时命中字的低位**蓝字节** —— 恰好与绿字节相同的情况
 * 会让用例假绿。所以带 subtract green 的原地展开用例把蓝通道置 1，让蓝字节与绿字节分开。
 *
 * ## 合成文件
 *
 * 全部手写 VP8L 位流（LSB-first，`VP8LReadBits` 口径），只覆盖需要的子集：无颜色缓存、
 * 无 meta Huffman、Huffman 只用 simple code（1~2 个符号）。调色板条目全部是
 * `A=1,R=0,G=0,B=0`，逐字节前缀累加（`ExpandColorMap`）后第 i 项的 alpha 正好是 `i+1`，
 * 于是「取到第几个索引」在输出 alpha 上一眼可辨。期望值全部手算，不依赖 Android / ImageIO。
 */
class ImageAudit2Test {

    // ==================================================================
    // color indexing：索引取绿色通道
    // ==================================================================

    @Test
    fun `color indexing 的索引取绿色通道（bits 3，8 个索引打进一个像素）`() {
        // 4x1 索引 [1,0,1,1]：t.bits=3 ⇒ 每 8 个索引打进一个绿字节，
        // 变换后宽度 = ceil(4/8) = 1，绿色字节 = 1 | 0<<1 | 1<<2 | 1<<3 = 13。
        val payload = colorIndexedVp8l(
            width = 4, height = 1, numColors = 2,
            transformedGreen = intArrayOf(13),
        )
        val img = WebpDecoder.decode(auditWebp(payload))
        assertEquals(4, img.width)
        assertEquals(1, img.height)
        // 调色板两项 (A=1,R=0,G=0,B=0) 前缀累加 ⇒ alpha 1、2。
        assertContentEquals(
            intArrayOf(0x02000000, 0x01000000, 0x02000000, 0x02000000),
            img.pixels,
            "索引 [1,0,1,1] 应查 color_table 的第 1/0/1/1 项；整字当索引会全取第 0 项",
        )
    }

    @Test
    fun `color indexing 的子字节索引按 LSB 先出（bits 1，两个 4 位索引）`() {
        // numColors=5 ⇒ t.bits=1、bitsPerPixel=4、perWord=2。变换后 1 个像素，
        // 绿字节 = idx0 | idx1<<4 = 4 | 3<<4 = 0x34。
        // 调色板 16 项里只有前 5 项被前缀累加填过 ⇒ alpha 1..5，其余为 0。
        val payload = colorIndexedVp8l(
            width = 2, height = 1, numColors = 5,
            transformedGreen = intArrayOf(0x34),
        )
        val img = WebpDecoder.decode(auditWebp(payload))
        assertContentEquals(
            intArrayOf(0x05000000, 0x04000000),
            img.pixels,
            "绿字节 0x34 的低 4 位是第 0 个像素、高 4 位是第 1 个像素；整字当打包源会取到蓝字节",
        )
    }

    @Test
    fun `color indexing 的 bits 0 逐像素索引（整字当索引会抛越界）`() {
        // 17 色 ⇒ t.bits=0（每像素一个索引、不做行内打包），变换后宽度 = 原宽 = 2。
        // 两个源像素的绿色 = 0 / 16；调色板 17 项全 (A=1,R=0,G=0,B=0) ⇒ alpha 1..17。
        val payload = colorIndexedVp8l(
            width = 2, height = 1, numColors = 17,
            transformedGreen = intArrayOf(0, 16),
        )
        val img = WebpDecoder.decode(auditWebp(payload))
        assertEquals(2, img.width)
        assertEquals(1, img.height)
        assertContentEquals(
            intArrayOf(0x01000000, 0x11000000),
            img.pixels,
            "整字 0xFF000000 / 0xFF001000 当索引会越界；绿色通道应得到第 0 / 第 16 项",
        )
    }

    @Test
    fun `color indexing 在 in-place 展开时同样取绿色通道（bits 3）`() {
        // 变换链 = color indexing 后接 subtract green ⇒ 逆变换先 subtract green（拷进 out），
        // 再在 out 上原地展开 color indexing，走的正是 colorIndexInverseInPlace。
        // 蓝通道取 1：subtract green 后蓝字节 = 1+13 = 0x0E、绿字节 = 13 = 0x0D，两者分开，
        // 「整字当打包源」会读到蓝字节 0x0E 的位序列 0,1,1,1 而不是绿字节 0x0D 的 1,0,1,1。
        val payload = colorIndexedVp8l(
            width = 4, height = 1, numColors = 2,
            transformedGreen = intArrayOf(13),
            subtractGreen = true, transformedBlue = 1,
        )
        val img = WebpDecoder.decode(auditWebp(payload))
        assertEquals(4, img.width)
        assertEquals(1, img.height)
        assertContentEquals(
            intArrayOf(0x02000000, 0x01000000, 0x02000000, 0x02000000),
            img.pixels,
            "in-place 展开的打包字同样要取绿色通道（蓝字节与绿字节已被刻意分开）",
        )
    }

    @Test
    fun `color indexing 的 in-place 展开 bits 0 也取绿色通道`() {
        // 同上但 t.bits=0：原地逐像素替换。绿 = 0 / 16，蓝 = 1 让整字不再等于绿字节。
        // subtract green 后两个字 = 0xFF000001 与 0xFF101011，整字当索引必然越界。
        val payload = colorIndexedVp8l(
            width = 2, height = 1, numColors = 17,
            transformedGreen = intArrayOf(0, 16),
            subtractGreen = true, transformedBlue = 1,
        )
        val img = WebpDecoder.decode(auditWebp(payload))
        assertContentEquals(
            intArrayOf(0x01000000, 0x11000000),
            img.pixels,
            "in-place 的 bits 0 分支同样只认绿字节",
        )
    }

    @Test
    fun `同一变换类型重复出现按坏码拒绝`() {
        // libwebp v1.1.0 `src/dec/vp8l_dec.c:1300-1304` 的 `ReadTransform` 按 `transforms_seen_`
        // 位图拒绝已出现过的类型（"Each transform type can only be present once in the stream."）；
        // 本实现同样按 seen 位图拒绝。这里连读两个 COLOR_INDEXING。
        val payload = colorIndexedVp8l(
            width = 2, height = 1, numColors = 2,
            transformedGreen = intArrayOf(0),
            duplicateTransform = true,
        )
        val ex = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(auditWebp(payload)) }
        assertTrue(
            ex.message!!.contains("重复"),
            "期望「变换类型重复」，实际：${ex.message}",
        )
    }

    // ==================================================================
    // 无损像素层：ARGB 布局与 alpha 口径
    // ==================================================================

    @Test
    fun `无损像素按 A24 R16 G8 B0 布局还原`() {
        // 2x1 两张字面量像素，无任何变换 / 颜色缓存 / meta。
        val argb = intArrayOf(0xFF010203.toInt(), 0xC8040506.toInt())
        val img = WebpDecoder.decode(auditWebp(literalVp8l(2, 1, argb, alphaUsed = 1)))
        assertEquals(2, img.width)
        assertEquals(1, img.height)
        assertTrue(img.hasAlpha, "alpha_is_used=1 ⇒ 原版建 Format32bppArgb（WebP.cs:37）")
        assertContentEquals(argb, img.pixels, "逐通道位置：绿 8-15、红 16-23、alpha 24-31、蓝 0-7")
    }

    @Test
    fun `alpha_is_used 为 0 时位流自带 alpha 被丢弃`() {
        // 同一份位流只改头里的 alpha_is_used 位：has_alpha 假 ⇒ 原版走
        // Format24bppRgb + WebPDecodeBGRInto（WebP.cs:37,:43），自带 alpha 一律丢成 255。
        val argb = intArrayOf(0xFF010203.toInt(), 0xC8040506.toInt())
        val img = WebpDecoder.decode(auditWebp(literalVp8l(2, 1, argb, alphaUsed = 0)))
        assertFalse(img.hasAlpha, "alpha_is_used=0 ⇒ Format24bppRgb")
        assertContentEquals(
            intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt()),
            img.pixels,
            "第二个像素自带的 alpha=200 必须被改写成 255",
        )
    }

    @Test
    fun `LZ77 长度码与平面距离码复制前序像素`() {
        // 4x1。GREEN 树是**定长规范码**（4 个符号各 2 位，走非 simple 支路）：
        // 字面量 7 / 8，长度前缀符号 0（码 256，长度 1）与 1（码 257，长度 2）；
        // RED / BLUE / ALPHA 各单符号，DIST 单符号 1 ⇒ 距离前缀 1 ⇒ 平面码 2。
        // 符号序列：7、8、257(长度 2) ⇒ planeCodeToDistance(4, 2)：CODE_TO_PLANE[1]=0x07
        // ⇒ yoffset=0、xoffset=8-7=1 ⇒ dist=1；从 pos=2 起按 dist=1 复制 2 个像素。
        val bw = Vp8lBits()
        bw.header(4, 1)
        bw.write(0, 1)      // 无变换
        bw.write(0, 1)      // 无颜色缓存
        bw.write(0, 1)      // 无 meta Huffman
        bw.fixedLengthCode(listOf(7, 8, 256, 257), alphabetSize = 280, codeLen = 2)
        bw.singleSymbol(3)      // RED
        bw.singleSymbol(4)      // BLUE
        bw.singleSymbol(255)    // ALPHA
        bw.singleSymbol(1)      // DIST
        bw.green(7); bw.green(8); bw.green(257)
        val img = WebpDecoder.decode(auditWebp(bw.toByteArray()))
        assertEquals(4, img.width)
        assertContentEquals(
            intArrayOf(0xFF030704.toInt(), 0xFF030804.toInt(), 0xFF030804.toInt(), 0xFF030804.toInt()),
            img.pixels,
            "复制长度 2、距离 1 ⇒ 第 3、4 个像素都等于第 2 个（逐次自复制，不是只拷原始两像素）",
        )
    }

    @Test
    fun `LZ77 距离越过已解像素时按坏码拒绝`() {
        // 同上但首符号就是长度码：pos=0 < dist=1 ⇒ 无源可拷，
        // 必须报错而不是把未初始化（0）像素当成拷贝源。
        val bw = Vp8lBits()
        bw.header(4, 1)
        bw.write(0, 1); bw.write(0, 1); bw.write(0, 1)
        bw.fixedLengthCode(listOf(7, 8, 256, 257), alphabetSize = 280, codeLen = 2)
        bw.singleSymbol(3); bw.singleSymbol(4); bw.singleSymbol(255); bw.singleSymbol(1)
        bw.green(257)
        val ex = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(auditWebp(bw.toByteArray())) }
        assertTrue(ex.message!!.contains("LZ77 越界"), ex.message)
    }

    // ==================================================================
    // 合成 VP8L（只生成用到的位流子集）
    // ==================================================================

    private class Vp8lBits {
        private val bytes = ArrayList<Byte>()
        private var bit = 0

        /** 最近一次 [fixedLengthCode] 的符号表与定长，供 [green] 反查规范码。 */
        private var fixedSymbols: List<Int> = emptyList()
        private var fixedLen = 0

        /** LSB-first 写 [n] 位（n <= 32）。 */
        fun write(value: Int, n: Int) {
            for (i in 0 until n) {
                if (bit == 0) bytes.add(0)
                if ((value ushr i) and 1 == 1) {
                    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or (1 shl bit)).toByte()
                }
                bit = (bit + 1) and 7
            }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }

        /** `ReadImageInfo`（vp8l_dec.c:117-126）：0x2f + 14 位宽-1 + 14 位高-1 + alpha + 版本。 */
        fun header(width: Int, height: Int, alphaUsed: Int = 1) {
            write(0x2f, 8)
            write(width - 1, 14)
            write(height - 1, 14)
            write(alphaUsed, 1)
            write(0, 3)
        }

        /** `ReadHuffmanCode` 的 simple code：单符号表（解码时 0 位）。 */
        fun singleSymbol(symbol: Int) {
            write(1, 1)   // simple_code
            write(0, 1)   // num_symbols - 1 = 0
            if (symbol < 2) {
                write(0, 1)          // first_symbol_len_code = 0 → 1 位符号
                write(symbol, 1)
            } else {
                write(1, 1)          // first_symbol_len_code = 1 → 8 位符号
                write(symbol, 8)
            }
        }

        /** simple code 的两符号表（都按 8 位写）；规范码：小符号 0、大符号 1。 */
        fun twoSymbols(low: Int, high: Int) {
            require(low in 0..255 && high in 0..255 && low != high) { "两符号表需 0..255 且不相等" }
            write(1, 1)
            write(1, 1)      // num_symbols - 1 = 1
            write(1, 1)      // first_symbol_len_code = 1
            write(low, 8)
            write(high, 8)
        }

        /**
         * 非 simple 支路的**定长规范码**：[symbols] 个符号全部取码长 [codeLen]
         * （Kraft 恰好闭合），其余符号码长 0。走 `ReadHuffmanCodeLengths` 的 19 符号
         * 码长字母表，并顺带覆盖 17/18 两个零游程码。
         */
        fun fixedLengthCode(symbols: List<Int>, alphabetSize: Int, codeLen: Int) {
            require(symbols.size == (1 shl codeLen)) { "定长码要求 ${1 shl codeLen} 个符号，实给 ${symbols.size}" }
            require(symbols == symbols.sorted()) { "规范码按 (码长, 符号) 递增分配，符号必须升序" }
            require(codeLen in 1..15) { "码长 $codeLen 越界" }
            require(symbols.all { it in 0 until alphabetSize }) { "符号不在字母表 0..$alphabetSize 内" }
            fixedSymbols = symbols
            fixedLen = codeLen
            write(0, 1)                      // is_simple = 0
            // 码长字母表（19 个符号）自建一棵树：[codeLen] 用 1 位，17 与 18 各用 2 位。
            val codeLenLens = IntArray(CODE_LENGTH_ALPHABET)
            codeLenLens[codeLen] = 1
            codeLenLens[17] = 2
            codeLenLens[18] = 2
            val numCodes = codeLenLens.indices.last { codeLenLens[CODE_LENGTH_CODE_ORDER[it]] != 0 } + 1
            require(numCodes in 4..CODE_LENGTH_ALPHABET) { "numCodes $numCodes 不在 4..$CODE_LENGTH_ALPHABET" }
            write(numCodes - 4, 4)
            for (i in 0 until numCodes) write(codeLenLens[CODE_LENGTH_CODE_ORDER[i]], 3)
            write(0, 1)                      // use_length = 0 ⇒ max_symbol = alphabetSize
            val literal = symbols.toSet()
            var sym = 0
            while (sym < alphabetSize) {
                if (sym in literal) {
                    // 码长字母表里 [codeLen] 是本树唯一的 1 位码 ⇒ 规范码 0。
                    write(0, 1)
                    sym++
                } else {
                    var zeros = 0
                    while (sym + zeros < alphabetSize && (sym + zeros) !in literal) zeros++
                    writeZeroRun(zeros)
                    sym += zeros
                }
            }
        }

        /** 码长字母表符号 17（3..10 个 0）与 18（11..138 个 0）的规范码：索引 1 与 3。 */
        private fun writeZeroRun(n: Int) {
            var left = n
            while (left >= 11) {
                val take = minOf(left, 138)
                write(3, 2)                  // 符号 18
                write(take - 11, 7)
                left -= take
            }
            if (left > 0) {
                require(left in 3..10) { "零游程余 $left 个无法用 17/18 游程码表达" }
                write(1, 2)                  // 符号 17
                write(left - 3, 3)
            }
        }

        /** 按最近一次 [fixedLengthCode] 的定长规范码写一个 GREEN 符号。 */
        fun green(symbol: Int) {
            val idx = fixedSymbols.indexOf(symbol)
            require(idx >= 0) { "$symbol 不在定长码符号表 $fixedSymbols 里" }
            write(reverseBitsLocal(idx, fixedLen), fixedLen)
        }

        /**
         * 一张 `argb.size` 像素、四通道各 1~2 个符号的**纯字面量**层。
         *
         * 先写 GREEN/RED/BLUE/ALPHA 四棵通道树 + 1 棵 DIST 树（单符号 0），再按同一读序
         * 逐像素写 1 位（大符号=1、小符号=0，见 [twoSymbols] 的规范码分配）。
         */
        fun literalTrees(argb: IntArray, distSingleSymbol: Int = 0) {
            val channels = arrayOf(
                argb.map { (it ushr 8) and 0xFF },   // GREEN
                argb.map { (it ushr 16) and 0xFF },  // RED
                argb.map { it and 0xFF },            // BLUE
                argb.map { (it ushr 24) and 0xFF },  // ALPHA
            )
            val distinct = channels.map { it.toSortedSet().toList() }
            for (d in distinct) {
                require(d.size in 1..2) { "本用例只覆盖每通道 1~2 个符号：$d" }
            }
            for (d in distinct) {
                if (d.size == 1) singleSymbol(d[0]) else twoSymbols(d[0], d[1])
            }
            singleSymbol(distSingleSymbol)   // DIST：字面量序列里不会被读到
            for (p in argb.indices) {
                for (c in channels.indices) {
                    if (distinct[c].size == 2) {
                        write(if (channels[c][p] == distinct[c][1]) 1 else 0, 1)
                    }
                }
            }
        }
    }

    /** 调色板层：numColors x 1，所有通道取常量（绿 0 / 红 0 / 蓝 0 / alpha 1）。 */
    private fun Vp8lBits.paletteLayer() {
        write(0, 1)          // 无颜色缓存（非 level0 层不读 meta-prefix 位）
        singleSymbol(0)      // GREEN
        singleSymbol(0)      // RED
        singleSymbol(0)      // BLUE
        singleSymbol(1)      // ALPHA ⇒ 前缀累加后第 i 项 alpha = i+1
        singleSymbol(0)      // DIST
        // 单符号树读 0 位：numColors 个像素不占位流。
    }

    private fun paletteBits(numColors: Int): Int = when {
        numColors > 16 -> 0
        numColors > 4 -> 1
        numColors > 2 -> 2
        else -> 3
    }

    private fun subsampleLocal(length: Int, bits: Int): Int =
        if (bits == 0) length else (length + (1 shl bits) - 1) shr bits

    /**
     * 组装一份带 color indexing（可选再串 subtract green）的 VP8L 位流。
     *
     * @param transformedGreen 变换后主图每个像素的绿色通道值（8 位索引或打包后的索引字节）
     * @param transformedBlue 主图蓝通道常量；置非 0 才能把「整字」与「绿字节」分开
     * @param duplicateTransform 变换链末尾再写一次 COLOR_INDEXING（坏码用例）
     */
    private fun colorIndexedVp8l(
        width: Int,
        height: Int,
        numColors: Int,
        transformedGreen: IntArray,
        subtractGreen: Boolean = false,
        transformedBlue: Int = 0,
        duplicateTransform: Boolean = false,
    ): ByteArray {
        val bw = Vp8lBits()
        bw.header(width, height, alphaUsed = 1)   // alpha_is_used=1：否则 decode 会把 alpha 强写成 255
        bw.write(1, 1)                    // 有变换
        bw.write(3, 2)                    // COLOR_INDEXING
        bw.write(numColors - 1, 8)
        bw.paletteLayer()
        if (subtractGreen) {
            bw.write(1, 1)                // 有变换
            bw.write(2, 2)                // SUBTRACT_GREEN（无附加数据）
        }
        if (duplicateTransform) {
            bw.write(1, 1)
            bw.write(3, 2)                // 又一次 COLOR_INDEXING ⇒ 判坏码
        }
        bw.write(0, 1)                    // 变换链结束
        val bits = paletteBits(numColors)
        require(transformedGreen.size == subsampleLocal(width, bits) * height) {
            "主图像素数 ${transformedGreen.size} != 变换后 ${subsampleLocal(width, bits)}x$height"
        }
        // 主图：颜色缓存位 + meta 位，然后四通道树 + DIST 树。红恒 0、蓝取常量。
        bw.write(0, 1)
        bw.write(0, 1)
        val argb = IntArray(transformedGreen.size) {
            (0xFF shl 24) or (transformedGreen[it] shl 8) or (transformedBlue and 0xFF)
        }
        bw.literalTrees(argb)
        return bw.toByteArray()
    }

    /** 无变换的纯字面量 VP8L 位流。 */
    private fun literalVp8l(width: Int, height: Int, argb: IntArray, alphaUsed: Int): ByteArray {
        require(argb.size == width * height)
        val bw = Vp8lBits()
        bw.header(width, height, alphaUsed)
        bw.write(0, 1)      // 无变换
        bw.write(0, 1)      // 无颜色缓存
        bw.write(0, 1)      // 无 meta Huffman
        bw.literalTrees(argb)
        return bw.toByteArray()
    }

    private fun auditLe32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /** 裸 `VP8L` 容器的 RIFF/WEBP 包装（与 WebpDecoder 的容器口径一致）。 */
    private fun auditWebp(payload: ByteArray): ByteArray {
        val chunk = ByteArrayOutputStream()
        chunk.write("VP8L".toByteArray(Charsets.US_ASCII))
        chunk.write(auditLe32(payload.size))
        chunk.write(payload)
        if (payload.size % 2 == 1) chunk.write(0)   // RIFF 奇数长度补一字节
        val body = chunk.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(auditLe32(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }
}

/** 码长字母表大小（libwebp v1.1.0 `src/dec/vp8l_dec.c:59` 的 `NUM_CODE_LENGTH_CODES`）。 */
private const val CODE_LENGTH_ALPHABET = 19

/** 码长表本身的写出顺序（`kCodeLengthCodeOrder`，`vp8l_dec.c:60-62`；与 `WebpDecoder.CODE_LENGTH_CODE_ORDER` 同源）。 */
private val CODE_LENGTH_CODE_ORDER = intArrayOf(
    17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
)

/** 位反转（规范码转成 LSB-first 查表索引），与 `WebpDecoder.Huffman.reverseBits` 同口径。 */
private fun reverseBitsLocal(value: Int, len: Int): Int {
    var v = 0
    for (i in 0 until len) if ((value and (1 shl i)) != 0) v = v or (1 shl (len - 1 - i))
    return v
}
