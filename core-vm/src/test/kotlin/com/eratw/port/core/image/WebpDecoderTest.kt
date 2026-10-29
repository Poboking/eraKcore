package com.eratw.port.core.image

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.unsignedArgb
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.api.Assumptions
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * webp 解码器与 `GCREATEFROMFILE` / `GLOAD` 接线的单测（路次 h-image-rest）。
 *
 * 期望值来源（**全部由本路 2026-10-21 用 Python 直接读语料文件头解出，未跑反编译器**）：
 * 语料根（画蛇添足版 4.980）下 `.webp` 共 **2548** 个（resources 目录下 2547 个，另有 1 个在
 * `resources` 之外的分片目录），顶层段分布：
 *
 * | 容器形态 | 语料条数 | 本移植侧结果 |
 * |---|---|---|
 * | `VP8X` + `VP8 ` + `ALPH`（有损 + 独立 alpha） | 1036 | 不支持 → [WebpDecodeException] → 函数 0 |
 * | `VP8 ` 裸容器（有损） | 831 | 同上 |
 * | `VP8L` 裸容器（无损） | 569 | 容器/头支持，**位流层实测 125 个解通**（见 [语料全量成败清单]） |
 * | `VP8X` + `VP8L`（无损） | 77 | 同上，实测 2 个解通 |
 * | `VP8X` + `VP8 `（有损、无 ALPH） | 26 | 不支持 → 0 |
 * | `VP8X` + `ANIM`/`ANMF`（动画） | 9 | 复刻原版：画布尺寸空白图 → 函数 1 |
 * | 合计 | 2548 | 成功 136（125 + 2 + 9），失败 2412 |
 *
 * 有损合计 1036 + 831 + 26 = **1893**；无损合计 569 + 77 = **646**；顶层 `ALPH` 段共 1036 个，
 * 全部与有损位流配对，语料里**没有**「无损 + ALPH」的实例。动画文件 9 个（`ANIM` 段 9、`ANMF` 段 9），
 * 全部 `VP8X` 且 ALPHA 标志置位，画布尺寸 450x600 x8、600x600 x1，都落在
 * `resources/剧本自定义附件/K97/` 下。
 *
 * 头解析口径：`VP8X` 的画布宽高 = 小端 24 位 + 1（`webp_dec.c:ParseHeadersInternal`）；
 * `VP8L` 的宽高 = 位流头两个 14 位字段 + 1（`vp8l_dec.c:117-126` `ReadImageInfo`）。
 */
class WebpDecoderTest {

    // ==================================================================
    // 签名判定
    // ==================================================================

    @Test
    fun `isWebp 只认 12 字节 RIFF 与 WEBP 双签名`() {
        assertFalse(WebpDecoder.isWebp(ByteArray(0)))
        assertFalse(WebpDecoder.isWebp(ByteArray(11)))
        // 刚好 12 字节的 RIFF/WEBP：签名对，但连一个段头都没有（decode 会判截断）。
        assertTrue(WebpDecoder.isWebp(riff(ByteArray(0))))
        assertFalse(WebpDecoder.isWebp(riff(ByteArray(0)).copyOf(11)))
        assertFalse(WebpDecoder.isWebp("not a webp".toByteArray()))
        // RIFF 对、WEBP 不对 → 不是 webp（原版走 `new Bitmap(path)`，这里不接管）
        val badTag = riff(ByteArray(0)).also { it[8] = 'X'.code.toByte() }
        assertFalse(WebpDecoder.isWebp(badTag))
        assertTrue(WebpDecoder.isWebp(riffWith("VP8L", ByteArray(5))))
    }

    // ==================================================================
    // 失败口径：未支持 / 坏结构一律 WebpDecodeException，绝不抛别的类型
    // ==================================================================

    @Test
    fun `坏输入只抛 WebpDecodeException`() {
        // 非 webp 字节
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode("hello world, definitely not webp".toByteArray()) }
        // 签名对但整文件不足 20 字节
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riffWith("VP8L", ByteArray(0))) }
        // RIFF 声明长度超过实际文件（截断）
        val truncated = riffWith("VP8L", byteArrayOf(0x2f, 0, 0, 0, 0))
        truncated[4] = (200).toByte()
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(truncated) }
        // 首个段既不是 VP8X 也不是 VP8/VP8L
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riffWith("ICCP", ByteArray(4))) }
        // VP8X 段长度不是 10
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riffWith("VP8X", ByteArray(9))) }
        // VP8X 合法但容器里没有位流段
        val noBitstream = vp8x(flags = 0, w = 8, h = 8) + chunk("ICCP", ByteArray(4))
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riff(noBitstream)) }
        // VP8X 不在第一个段
        val lateVp8x = chunk("ICCP", ByteArray(4)) + vp8x(0, 8, 8) + vp8lChunk(8, 8)
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riff(lateVp8x)) }
        // VP8L 魔术字节不是 0x2f
        val badMagic = vp8x(0, 8, 8) + chunk("VP8L", byteArrayOf(0x2e, 0, 0, 0, 0))
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riff(badMagic)) }
        // VP8L 版本号非 0
        val badVersion = vp8x(0, 8, 8) + vp8lChunk(8, 8, version = 1)
        assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riff(badVersion)) }
    }

    @Test
    fun `有损位流的失败消息带语料条数登记`() {
        // 裸 VP8 容器：明确不支持，消息必须能被函数层折成 0（不抛 CodeEE）。
        val lossy = riffWith("VP8 ", byteArrayOf(0, 0, 0, 0x9d.toByte(), 1, 0x2a, 3, 0, 3, 0))
        val ex = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(lossy) }
        assertTrue(ex.message!!.startsWith("WEBP-UNSUPPORTED-LOSSY"), ex.message)
        // 语料实测：有损 1893 = 831 裸容器 + 1036 带 ALPH + 26 不带 ALPH
        assertTrue(ex.message!!.contains("1893"), ex.message)
    }

    @Test
    fun `尺寸超过 8192 判失败`() {
        // VP8L 头里的 14 位字段最大正好是 16384；原版的函数层上限是 8192
        // （AbstractImage.cs:8 → FunctionMethodCreator.cs:6905 / :8177 返回 0）。
        val over = riffWith("VP8L", vp8lBody(8193, 1))
        val ex = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(over) }
        assertTrue(ex.message!!.contains("TOOBIG"), ex.message)

        val exactlyMax = riffWith("VP8L", vp8lBody(8192, 1))
        val ok = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(exactlyMax) }
        // 8192 本身不越界；这里只允许败在「位流是凑数的」，不许是 TOOBIG。
        assertFalse(ok.message!!.contains("TOOBIG"), ok.message)
    }

    /**
     * 像素总数守卫：单边都在原版 8192 上限内的巨图（8192x8192）也不许走到
     * `IntArray(w * h)` 的分配——原版这种图是 GDI+ / libwebp 在宿主上 OOM 后被
     * `Utils.LoadImage` 的空 catch（Utils.cs:521-523）折成 null → 函数返回 0；
     * 本解码器要在分配前显式判失败（GraphicsBuffer.MAX_IMAGE_PIXELS），否则
     * OutOfMemoryError 会漏过只捕 `Exception` 的函数层（GraphicsMethods.kt:984-988）。
     */
    @Test
    fun `像素总数超限在分配前判失败`() {
        // 动画画布路径：VP8X 画布 + ANIM 段 → animated 分支在分配前 checkSize。
        val anim = vp8x(flags = 0, w = 8192, h = 8192) + chunk("ANIM", ByteArray(6))
        val ex = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(riff(anim)) }
        assertTrue(ex.message!!.contains("像素总数"), ex.message)

        // 静态 VP8L 路径：位流头声明 8192x8192 → withHeader 分支的 checkSize。
        val lossless = riffWith("VP8L", vp8lBody(8192, 8192))
        val ex2 = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(lossless) }
        assertTrue(ex2.message!!.contains("像素总数"), ex2.message)
    }

    /** 语料普查最大 8192x4000 = 32,768,000 px < 上限，不许被像素总数守卫误杀。 */
    @Test
    fun `语料上限内的大图不触发像素总数守卫`() {
        val boundary = riffWith("VP8L", vp8lBody(8192, 4000))
        val ok = assertFailsWith<WebpDecodeException> { WebpDecoder.decode(boundary) }
        // 8192x4000 只允许败在位流层（凑数的位流），不许是尺寸/像素总数。
        assertFalse(ok.message!!.contains("TOOBIG"), ok.message)
        assertFalse(ok.message!!.contains("像素总数"), ok.message)
    }

    // ==================================================================
    // 动画 webp：复刻原版「画布尺寸空白位图」
    // ==================================================================

    /**
     * 依据（C# 侧，本路实测行号）：
     * - `WebP.Decode` 先 `GetInfo` 拿宽高再建位图（`reference/emuera-cs/MinorShift.Emuera.Runtime.Utils/WebP.cs:36-38`）；
     * - 两个 `Into` 接口的包装是 `internal static void`，libwebp 的状态码被丢掉、也不抛异常
     *   （`UnsafeNativeMethods.cs:201-214`（BGRInto）、`:222-235`（BGRAInto)），
     *   所以 `WebP.Load` 正常返回那张刚 `new Bitmap(...)` 出来的位图（`WebP.cs:37`、`:43`、`:47`）；
     * - libwebp 1.1.0 对动画：`WebPGetFeaturesInternal` 只读 VP8X 就 `goto ReturnWidthHeight`
     *   并返回 OK（`src/dec/webp_dec.c:319`、`:331-333`、`:398-408`），
     *   而 `WebPParseHeaders` 把动画判成 `VP8_STATUS_UNSUPPORTED_FEATURE`
     *   （`src/dec/webp_dec.c:427-428`），`DecodeInto` 在 `:456-459` 立刻返回、**一个字节都不写**
     *   （`src/dec/webp_dec.c:447-459`）。
     *
     * TODO(verify)：「新建 GDI+ 位图是全 0」是上一路在本机 Windows + System.Drawing 实测的，
     * 本路没有复测；若真机对拍发现 RGB/alpha 初值不同，需要按实测改这里的空白填充。
     */
    @Test
    fun `动画 webp 给画布尺寸的空白位图`() {
        val anim = vp8x(flags = FLAG_ALPHA, w = 20, h = 12) +
            chunk("ANIM", ByteArray(6)) +
            chunk("ANMF", ByteArray(16) + byteArrayOf(0x2f, 0, 0, 0, 0))
        val img = WebpDecoder.decode(riff(anim))
        assertEquals(20, img.width)
        assertEquals(12, img.height)
        assertTrue(img.hasAlpha, "VP8X 的 ALPHA 标志决定 Format32bppArgb")
        assertTrue(img.pixels.all { it == 0 }, "空白位图：全 0")

        // 无 ALPHA 标志 → Format24bppRgb → 不透明黑（alpha 恒 255）
        val opaque = WebpDecoder.decode(riff(vp8x(0, 4, 3) + chunk("ANIM", ByteArray(6))))
        assertEquals(4, opaque.width)
        assertEquals(3, opaque.height)
        assertFalse(opaque.hasAlpha)
        assertTrue(opaque.pixels.all { it == 0xFF000000.toInt() }, "空白位图：不透明黑")
    }

    // ==================================================================
    // 真实语料：3 个文件的 GWIDTH / GHEIGHT
    // ==================================================================

    /**
     * 期望宽高怎么来的：直接用 Python 打开这三个文件读头字段。
     * - `resources/CharaTH98/152.webp`：12 字节 RIFF/WEBP → 首个段 `VP8L`（size 0x3b50=15184），
     *   位流头 `0x2f` 后两个 14 位字段 = 539 / 359 → **540x360**，alpha 位 = 1；
     * - `resources/剧本自定义附件/K97/97SOX13.webp`：`VP8X`（size=10）flags=0x12（ALPHA + ANIMATION），
     *   画布 24 位字段 = 599 / 599 → **600x600**；
     * - `resources/剧本自定义附件/K97/97BA1-2.webp`：`VP8X` flags=0x12，画布 = 449 / 599 → **450x600**。
     */
    @Test
    fun `真实语料三张图的 GWIDTH 与 GHEIGHT`() {
        val resources = findCorpusResources()
        Assumptions.assumeTrue(resources != null, "未找到语料 resources 目录（设置 ERATW_GAMEDIR 或 ERATW_CORPUS）")
        val root = resources ?: return

        val cases = listOf(
            // 裸 VP8L 无损：当前实测能解通的形态。名字按**语料里脚本传给 GCREATEFROMFILE 的形态**
            // 写（不带 resources/ ），前缀由函数自己加 —— spec: FunctionMethodCreator.cs:6890-6894。
            Triple("CharaTH98/152.webp", 540, 360),
            // 动画（VP8X + ANIM/ANMF）：原版给画布尺寸空白图
            Triple("剧本自定义附件/K97/97SOX13.webp", 600, 600),
            Triple("剧本自定义附件/K97/97BA1-2.webp", 450, 600),
        )
        val ops = WebpTestFileOps()
        val gameRoot = root.parentFile  // resources 的上一级就是语料根
        for ((path, _, _) in cases) {
            val f = File(gameRoot, "resources/$path")
            assertTrue(f.isFile, "语料文件缺失：${f.path}")
            ops.binaries["resources/$path"] = f.readBytes()
        }
        val ctx = webpCtx(ops)
        cases.forEachIndexed { i, (path, width, height) ->
            val id = 10 + i
            assertEquals(1L, webpCall(ctx, "GCREATEFROMFILE", id, path), "$path 应创建成功")
            assertEquals(width.toLong(), webpCall(ctx, "GWIDTH", id), "$path 的 GWIDTH")
            assertEquals(height.toLong(), webpCall(ctx, "GHEIGHT", id), "$path 的 GHEIGHT")
            assertEquals(1L, webpCall(ctx, "GCREATED", id))
        }
    }

    // ==================================================================
    // 真实语料：全量成败清单（组长跑闸时核对这张表）
    // ==================================================================

    /**
     * 遍历语料全部 `.webp`，按容器形态统计成败并打印清单。
     *
     * 断言的是**本路 Python 普查 + 本路 JVM 全量自查**对上的读数：
     * 总数 2548、成功 136（裸 `VP8L` 125 + `VP8X`/`VP8L` 2 + 动画 9）、
     * 有损 1893 全部失败且失败码只能是 `WEBP-UNSUPPORTED-LOSSY`（或结构截断）。
     *
     * ⚠️ 「无损 127/646 成功」是**当前已知缺口**（`WebpDecoder` 的 VP8L 位流层与 libwebp 不同步，
     * 515 个报 LZ77/码表类 BADCODE、4 个报位流截断），不是设计目标；
     * 修好后这里的读数必须上调，见 KDoc 与本路交付报告。
     */
    @Test
    fun `语料全量成败清单`() {
        val resources = findCorpusResources()
        Assumptions.assumeTrue(resources != null, "未找到语料 resources 目录（设置 ERATW_GAMEDIR 或 ERATW_CORPUS）")
        val root = resources ?: return
        val gameRoot = root.parentFile

        val files = gameRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("webp", ignoreCase = true) }
            .toList()
        assertEquals(2548, files.size, "语料 .webp 总数（本路 Python 普查）")

        val total = LinkedHashMap<String, Int>()
        val ok = LinkedHashMap<String, Int>()
        val reasons = LinkedHashMap<String, Int>()
        val bad = ArrayList<String>()
        var success = 0
        for (f in files) {
            val bytes = f.readBytes()
            val cls = containerClass(bytes)
            total[cls] = (total[cls] ?: 0) + 1
            val rel = f.path.substring(gameRoot.path.length + 1).replace('\\', '/')
            try {
                val img = WebpDecoder.decode(bytes)
                assertTrue(img.pixels.size == img.width * img.height, "像素数不符：$rel")
                success++
                ok[cls] = (ok[cls] ?: 0) + 1
            } catch (e: WebpDecodeException) {
                val code = e.message!!.substringBefore('：')
                reasons[code] = (reasons[code] ?: 0) + 1
                // 有损形态只允许报「未支持有损」；动画必须成功；其余 BADCODE 都是位流层缺口。
                if (cls.contains("VP8L") && code == "WEBP-UNSUPPORTED-LOSSY") bad.add("形态归类不一致 $rel")
                if (cls.contains("ANIM") && code != "OK") bad.add("动画不该失败 $rel :: ${e.message}")
            } catch (e: Exception) {
                bad.add("$rel 抛了非 WebpDecodeException：${e.javaClass.name}: ${e.message}")
                reasons["NON-WEBP-EXCEPTION"] = (reasons["NON-WEBP-EXCEPTION"] ?: 0) + 1
            }
        }

        val sb = StringBuilder()
        sb.appendLine("[webp-census] 语料 $gameRoot")
        sb.appendLine("[webp-census] 成功 $success / ${files.size}")
        for ((k, v) in total.entries.sortedByDescending { it.value }) {
            sb.appendLine("[webp-census]   %-24s total=%-6d ok=%d".format(k, v, ok[k] ?: 0))
        }
        for ((k, v) in reasons.entries.sortedByDescending { it.value }) {
            sb.appendLine("[webp-census]   reason %-28s %d".format(k, v))
        }
        bad.take(40).forEach { sb.appendLine("[webp-census] !! $it") }
        println(sb)

        assertEquals(1148, total["VP8X-lossy"]!! + total["VP8X-lossless"]!! + total["VP8X-animated"]!!, "VP8X 容器总数（普查 1148）")
        assertEquals(831, total["bare/VP8 "], "裸有损容器（普查 831）")
        assertEquals(569, total["bare/VP8L"], "裸无损容器（普查 569）")
        assertEquals(1893, (total["bare/VP8 "] ?: 0) + (total["VP8X-lossy"] ?: 0), "有损位流合计（普查 1893）")
        assertEquals(646, (total["bare/VP8L"] ?: 0) + (total["VP8X-lossless"] ?: 0), "无损位流合计（普查 646）")
        assertEquals(9, total["VP8X-animated"], "动画（普查 9）")

        // 有损一律失败；动画一律成功。
        assertEquals(0, ok["bare/VP8 "] ?: 0, "有损不许解通")
        assertEquals(0, ok["VP8X-lossy"] ?: 0, "有损不许解通")
        assertEquals(9, ok["VP8X-animated"], "动画 9 个全部按原版给空白图")
        // 无损当前实测读数（缺口登记，修好后要上调；2026-10-22 复核：color indexing
        // 的索引通道改取绿字节后，裸 VP8L 从 26 涨到 125）
        assertEquals(125, ok["bare/VP8L"], "裸 VP8L 当前实测解通数")
        assertEquals(2, ok["VP8X-lossless"], "VP8X+VP8L 当前实测解通数")
        assertEquals(136, success, "成功合计")
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    // ==================================================================
    // 函数层接线：未支持形态必须返回 0，不许升级成 CodeEE
    // ==================================================================

    @Test
    fun `webp 解码失败时函数返回 0 且不回落文本载体`() {
        val ops = WebpTestFileOps()
        // 有损 webp（本路不支持）——原版 Utils.LoadImage 的空 catch → null → 0（Utils.cs:502-525）。
        ops.binaries["resources/lossy.webp"] = riffWith("VP8 ", byteArrayOf(0, 0, 0, 0x9d.toByte(), 1, 0x2a, 3, 0, 3, 0))
        // 签名对但结构非法
        ops.binaries["resources/broken.webp"] = riffWith("VP8X", ByteArray(9))
        // webp 签名 + 恰好也是 GSAVE 文本载体的字节：必须按「只试 webp」判 0，不许回落到文本。
        ops.binaries["resources/both.webp"] = riffWith("VP8X", ByteArray(9))
        ops.texts["resources/both.webp"] = "ERATW-GFX 1\n2 2\nffffffff\nffffffff\nffffffff\nffffffff\n"
        // 不是 webp 的文本载体：仍走 GSAVE 回落（GraphicsMethods.kt 的分派只接管 RIFF/WEBP）。
        ops.texts["resources/gsave.txt"] = "ERATW-GFX 1\n2 2\nffffffff\nffffffff\nffffffff\nffffffff\n"
        ops.binaries["resources/gsave.txt"] = "ERATW-GFX 1\n2 2\nffffffff\nffffffff\nffffffff\nffffffff\n".toByteArray()
        val ctx = webpCtx(ops)

        assertEquals(0L, webpCall(ctx, "GCREATEFROMFILE", 10, "lossy.webp"))
        assertEquals(0L, webpCall(ctx, "GCREATED", 10), "失败不得留下半创建的缓冲")
        assertEquals(0L, webpCall(ctx, "GCREATEFROMFILE", 11, "broken.webp"))
        assertEquals(0L, webpCall(ctx, "GCREATEFROMFILE", 12, "both.webp"), "webp 签名下不许回落文本载体")
        assertEquals(1L, webpCall(ctx, "GCREATEFROMFILE", 13, "gsave.txt"), "非 webp 仍走 GSAVE 文本载体")
        assertEquals(2L, webpCall(ctx, "GWIDTH", 13))

        // GLOAD 同一条路：坏 webp → 0（FunctionMethodCreator.cs:8168-8198）。
        val ops2 = WebpTestFileOps()
        ops2.binaries["sav/img0003.webp"] = ops.binaries.getValue("resources/broken.webp")
        val ctx2 = webpCtx(ops2)
        assertEquals(0L, webpCall(ctx2, "GLOAD", 20, 3))
        assertEquals(0L, webpCall(ctx2, "GCREATED", 20))
    }

    @Test
    fun `宿主没有二进制读能力时 webp 返回 0`() {
        val ops = WebpTestFileOps()
        ops.binaries["resources/a.webp"] = riffWith("VP8L", ByteArray(0))
        val ctx = webpCtx(ops, binaryReader = false)
        assertEquals(0L, webpCall(ctx, "GCREATEFROMFILE", 10, "a.webp"))
    }

    // ==================================================================
    // 合成 webp 构造
    // ==================================================================

    private fun le32(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte())

    private fun le24(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte())

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)  // RIFF 奇数长度补一字节
        return out.toByteArray()
    }

    private fun riff(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        // RIFF 的 size 字段 = 总长 - 8，也就是「WEBP 标签 + 所有段（含补齐字节）」的字节数。
        out.write(le32(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun riffWith(type: String, payload: ByteArray): ByteArray = riff(chunk(type, payload))

    /** `VP8X` 段：flags(LE32) + 画布宽-1(LE24) + 画布高-1(LE24)，共 10 字节。 */
    private fun vp8x(flags: Int, w: Int, h: Int): ByteArray =
        chunk("VP8X", le32(flags) + le24(w - 1) + le24(h - 1))

    /**
     * `VP8L` 位流体：`0x2f` + 14 位宽-1 + 14 位高-1 + 1 位 alpha + 3 位版本（LSB-first），
     * 后面填零 —— 只用来测头/尺寸口径，位流本身注定失败。
     */
    private fun vp8lBody(w: Int, h: Int, alpha: Int = 0, version: Int = 0): ByteArray {
        var bitPos = 0
        val bits = ArrayList<Pair<Int, Int>>()
        bits += 8 to 0x2f
        bits += 14 to (w - 1)
        bits += 14 to (h - 1)
        bits += 1 to alpha
        bits += 3 to version
        val total = bits.sumOf { it.first }
        val out = ByteArray((total + 7) / 8)
        for ((size, value) in bits) {
            for (i in 0 until size) {
                if ((value ushr i) and 1 == 1) out[bitPos / 8] = (out[bitPos / 8].toInt() or (1 shl (bitPos % 8))).toByte()
                bitPos++
            }
        }
        return out
    }

    private fun vp8lChunk(w: Int, h: Int, version: Int = 0): ByteArray =
        chunk("VP8L", vp8lBody(w, h, version = version) + ByteArray(32))

    // ==================================================================
    // 容器归类：与普查 KDoc 的表同一口径
    // ==================================================================

    private fun le32At(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun containerClass(b: ByteArray): String {
        if (!WebpDecoder.isWebp(b) || b.size < 20) return "unparsable"
        val first = String(b, 12, 4, Charsets.US_ASCII)
        if (first != "VP8X") return "bare/$first"
        val limit = (le32At(b, 4) + 8).coerceAtMost(b.size)
        var pos = 12
        var bs = ""
        var anim = (b[20].toInt() and 0x02) != 0
        while (pos + 8 <= limit) {
            val cc = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32At(b, pos + 4)
            if (size < 0 || pos + 8 + size > limit) break
            when (cc) {
                "VP8 ", "VP8L" -> if (bs.isEmpty()) bs = cc
                "ANIM", "ANMF" -> anim = true
            }
            pos = pos + 8 + size + (size and 1)
        }
        return when {
            anim -> "VP8X-animated"
            bs == "VP8L" -> "VP8X-lossless"
            bs == "VP8 " -> "VP8X-lossy"
            else -> "VP8X-other"
        }
    }

    // ==================================================================
    // 内存宿主（与 PngDecoderTest 同一套装配，名字独立避免冲突）
    // ==================================================================

    private class WebpTestFileOps : FileOps {
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

    private class WebpTestBase : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("WebpTestBase 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    private class WebpTestHost(
        private val ops: WebpTestFileOps,
        private val binaryReader: Boolean,
    ) : BuiltinHost {
        override val fileOps: FileOps = ops

        override fun readFileBytes(path: String): ByteArray? = if (binaryReader) ops.binaries[path] else null
    }

    private fun webpCtx(ops: WebpTestFileOps, binaryReader: Boolean = true): BuiltinEvalContext {
        val store = VariableStore()
        val base = WebpTestBase()
        base.variables = store
        return BuiltinEvalContext(base, store, WebpTestHost(ops, binaryReader))
    }

    private fun webpCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
        (ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        ) as Value.IntV).v

    /** `VP8X` flags 位（libwebp `src/webp/mux_types.h`：ICC 1 / ANIMATION 2 / XMP 4 / EXIF 8 / ALPHA 16）。 */
    private companion object {
        const val FLAG_ALPHA = 0x10
    }
}

/**
 * 语料 `resources` 目录发现：`ERATW_GAMEDIR`（游戏根，显式给出即权威）优先，
 * 回落 `ERATW_CORPUS`（闸门口径指向 `ERB` 子树）后向上最多 4 层找 `resources`。
 * **不写死任何盘符**；两个变量都没有 → 调用方 `Assumptions.assumeTrue` 跳过。
 */
private fun findCorpusResources(): File? {
    val gameDir = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
    if (gameDir != null) {
        val res = File(gameDir, "resources")
        return if (res.isDirectory) res else null
    }
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
