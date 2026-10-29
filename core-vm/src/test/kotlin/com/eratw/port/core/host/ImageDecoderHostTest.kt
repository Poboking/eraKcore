package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.vars.VariableStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ImageDecoderHost] 的**接线契约**回归（M10-GFX 第 2 步：将此前恒为 null 的宿主解码通道钉死）。
 *
 * ## 为什么这批用例必须存在
 *
 * `GCREATEFROMFILE` / `GLOAD` 的像素在 core 侧只有两个来源（`PngDecoder` 全绿、`WebpDecoder`
 * 的有损位流明确不做，语料 2548 个 webp 实测只解通 37 个），解不出来时按原版
 * `Utils.LoadImage` 的空 catch 折成 0（spec: `Utils.cs:502-525`，空 catch 在 `:521-523`）。
 * 真机上要的是「屏上有立绘」，所以 `:platform` 用 `BitmapFactory` 实现本接口，
 * 由 `GameHost.installGraphics` 装进 `store.aux` 的 [IMAGE_DECODER_SLOT]。
 * **两侧之间只有 `aux.peek` 这一根线**，装与不装必须由测试来判，不能靠口头。
 *
 * ## 判据（每条都有 C# 出处）
 *
 * | # | 行为 | C# 出处 | 本文件用例 |
 * |---|---|---|---|
 * | 1 | 未装宿主 ⇒ 逐项与今天相同，且宿主一次都不被问 | `Utils.cs:502-525` | 「未装宿主时_」两条 |
 * | 2 | 装了宿主 ⇒ webp 缺口出像素，返回码仍是 1 | `Utils.cs:511-515`（原版 webp 走 `WebP.Load`，本来就能出像素） | 「装了宿主时_」三条 |
 * | 3 | 宿主返回 null / 抛异常 ⇒ 回落 core 解码器，失败仍折成 0 | `Utils.cs:521-523` 的空 catch | 「宿主失败_」三条 |
 * | 4 | 宿主像素的 alpha==0 归零 | `GraphicsImage.cs:119-130`（GDI+ source-over 丢弃 RGB） | 「alpha 归零_」 |
 * | 5 | 宿主越界 / 像素长度不符 ⇒ 0 且不脏缓冲 | `FunctionMethodCreator.cs:6905`、`AbstractImage.cs:8` | 「宿主交坏数据_」 |
 * | 6 | `GLOAD` 与 `GCREATEFROMFILE` 同一通道 | `FunctionMethodCreator.cs:8168-8198` | 「GLOAD 」两条 |
 *
 * 反向防误接（第 1 条）与正向接线（第 2 条）用**同一个假 decoder**：第 1 条断言它一次都没被调用、
 * 第 2 条断言它的像素上了屏。语料闸 / E2E 跑的就是第 1 条这条路（从不装宿主），
 * 所以任何「无条件走宿主」的改法都会在这里炸。
 *
 * 装置与 `core/fn/GraphicsGfxAuditTest` 同构：内存 `FileOps` + 可注入的 `readFileBytes`，
 * 状态挂各自独立的 [VariableStore]（用例之间不串味）。
 *
 * 夹具一律登记在 `resources/` 下：`GCREATEFROMFILE` 对相对路径要先接 `Program.ContentDir`
 * （= `<ExeDir>\resources\`，spec: `FunctionMethodCreator.cs:6890-6894` + `Program.cs:219-221`），
 * 所以用例里传给函数的仍是语料原样的裸名（`"a.webp"`），宿主收到的才是 `resources/a.webp`。
 * `GLOAD` 不走这条（它的入参是存档图路径，spec: `FunctionMethodCreator.cs:8168-8198`），
 * 那两条用例的路径保持不带前缀。
 */
private class DecBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DecBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 内存文件系统：[bytes] 走宿主二进制口（图片），[files] 走 GSAVE 文本载体。 */
private class DecOps : FileOps {
    val files = LinkedHashMap<String, String>()
    val bytes = LinkedHashMap<String, ByteArray>()
    override fun exists(path: String): Boolean = files.containsKey(path) || bytes.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class DecEnv(val ops: DecOps = DecOps()) {
    val store = VariableStore()
    private val base = DecBaseCtx().also { it.variables = store }
    val ctx: BuiltinEvalContext = BuiltinEvalContext(
        base,
        store,
        object : BuiltinHost {
            override val fileOps: FileOps? = ops
            override fun readFileBytes(path: String): ByteArray? = ops.bytes[path]
        },
    )

    /** 把假 decoder 装进 aux —— 与 `GameHost.installGraphics` 同一行写法。 */
    fun install(decoder: ImageDecoderHost) {
        store.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { decoder }
    }

    fun call(name: String, vararg args: Any): Long {
        val value = ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        )
        return (value as Value.IntV).v
    }
}

/**
 * 可编程假 decoder：按路径返回预置像素，记录每次被问的 (path, 字节数)。
 *
 * [outcome] = `image`（返回图）/ `null`（解不出来）/ `throw`（抛异常，`BitmapFactory`
 * 对畸形字节确实会抛，见 `BitmapImageDecoder` 的 catch）。
 */
private class FakeDecoder(
    val images: MutableMap<String, PngImage?> = HashMap(),
    var outcome: String = "image",
) : ImageDecoderHost {
    val asked = ArrayList<String>()

    override fun decode(path: String, bytes: ByteArray): PngImage? {
        asked.add("$path/${bytes.size}")
        when (outcome) {
            "throw" -> throw IllegalStateException("模拟 BitmapFactory 抛异常：$path")
            "null" -> return null
        }
        return images[path]
    }

    fun count(path: String): Int = asked.count { it.startsWith("$path/") }
}

/** RIFF/WEBP + "VP8 "（有损位流）：`WebpDecoder` 登记为不支持，语料 1893 个就是这个形态。 */
private val LOSSY_WEBP = byteArrayOf(
    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
    24, 0, 0, 0,
    'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
    'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
    8, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8,
)

/** 真 PNG（ImageIO 编码，`PngDecoder` 能解）：像素由调用方给，alpha 通道原样保留。 */
private fun pngBytes(w: Int, h: Int, argb: IntArray): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb[y * w + x])
    val out = ByteArrayOutputStream()
    assertTrue(ImageIO.write(img, "png", out), "ImageIO 写不出 png，装置失效")
    return out.toByteArray()
}

class ImageDecoderHostTest {

    // 一张 4x3 的 PNG：像素按**行主序下标**递增，逐像素可核对。
    private val pngArgb = IntArray(12) { 0xFF000000.toInt() or (it * 0x11) }
    private fun argbAt(index: Int): Long = pngArgb[index].toLong() and 0xFFFFFFFFL

    private fun pngEnv() = DecEnv().apply { ops.bytes["resources/ok.png"] = pngBytes(4, 3, pngArgb) }
    private fun webpEnv() = DecEnv().apply { ops.bytes["resources/a.webp"] = LOSSY_WEBP }

    // ==================================================================
    // 1) 未装宿主：与今天逐项相同（反向防误接）
    // ==================================================================

    @Test
    fun `未装宿主时_解码通道恒空且 webp 失败仍返回 0`() {
        val env = webpEnv()
        val decoy = FakeDecoder(HashMap(mapOf("resources/a.webp" to PngImage(5, 2, IntArray(10) { 0xFF00FF00.toInt() }))))

        assertNull(
            env.store.aux.peek<ImageDecoderHost>(IMAGE_DECODER_SLOT),
            "aux 里不该凭空出现解码器（peek 只查不建，见 ImageDecoderHost 的槽约定）",
        )
        // 与接入前逐项相同：解不出来 → 0，缓冲不创建、尺寸 0
        assertEquals(0L, env.call("GCREATEFROMFILE", 10, "a.webp"))
        assertEquals(0L, env.call("GCREATED", 10))
        assertEquals(0L, env.call("GWIDTH", 10))
        assertEquals(0, decoy.count("resources/a.webp"), "未装的 decoder 一次都不该被问")
    }

    @Test
    fun `未装宿主时_PNG 像素仍来自 core 自带解码器`() {
        val env = pngEnv()
        assertEquals(1L, env.call("GCREATEFROMFILE", 11, "ok.png"))
        assertEquals(4L, env.call("GWIDTH", 11))
        assertEquals(3L, env.call("GHEIGHT", 11))
        // 逐像素读数（PngDecoder 那条路，与 E2E / 语料闸看到的完全一致）
        assertEquals(argbAt(0), env.call("GGETCOLOR", 11, 0, 0))
        assertEquals(argbAt(5), env.call("GGETCOLOR", 11, 1, 1))
        assertEquals(argbAt(11), env.call("GGETCOLOR", 11, 3, 2))
    }

    // ==================================================================
    // 2) 装了宿主：webp 缺口出像素，返回码分支未动
    // ==================================================================

    @Test
    fun `装了宿主时_webp 缺口出像素且返回码从 0 变 1`() {
        val env = webpEnv()
        // 5x2，像素 = 0xFF000000 + (y*5+x)*0x100，与 PNG 那条路明显不同源
        val host = FakeDecoder(
            HashMap(mapOf("resources/a.webp" to PngImage(5, 2, IntArray(10) { 0xFF000000.toInt() or (it * 0x100) }))),
        )
        env.install(host)

        // 未装时这里是 0（上一条用例钉的就是它）—— 这条用例是「缺口补齐」的正向证据
        assertEquals(1L, env.call("GCREATEFROMFILE", 20, "a.webp"))
        assertEquals(1L, env.call("GCREATED", 20))
        assertEquals(5L, env.call("GWIDTH", 20))
        assertEquals(2L, env.call("GHEIGHT", 20))
        assertEquals(0xFF000000L, env.call("GGETCOLOR", 20, 0, 0))
        assertEquals((0xFF000000L + 0x100L), env.call("GGETCOLOR", 20, 1, 0))
        assertEquals((0xFF000000L + 9L * 0x100L), env.call("GGETCOLOR", 20, 4, 1))
        assertEquals(1, host.count("resources/a.webp"))
    }

    @Test
    fun `装了宿主时_字节按原样交出去且只交一次`() {
        val env = webpEnv()
        val host = FakeDecoder(HashMap(mapOf("resources/a.webp" to PngImage(1, 1, intArrayOf(0xFF010203.toInt())))))
        env.install(host)
        assertEquals(1L, env.call("GCREATEFROMFILE", 21, "a.webp"))
        // 宿主拿到的是**文件原始字节**（24 字节 RIFF 容器），不是解码后的像素：
        // BitmapFactory 需要自己判格式，所以 core 不许在这里做签名过滤。
        assertEquals(listOf("resources/a.webp/${LOSSY_WEBP.size}"), host.asked)
    }

    @Test
    fun `装了宿主时_png 也走宿主且宿主结果优先`() {
        val env = pngEnv()
        val host = FakeDecoder(HashMap(mapOf("resources/ok.png" to PngImage(2, 2, IntArray(4) { 0xFF123456.toInt() }))))
        env.install(host)
        assertEquals(1L, env.call("GCREATEFROMFILE", 22, "ok.png"))
        assertEquals(2L, env.call("GWIDTH", 22))
        assertEquals(0xFF123456L, env.call("GGETCOLOR", 22, 1, 1))
    }

    // ==================================================================
    // 3) 宿主失败口径（Utils.cs:521-523 的空 catch）
    // ==================================================================

    @Test
    fun `宿主失败_返回 null 时回落 core 解码器`() {
        val env = pngEnv()
        val host = FakeDecoder(HashMap(), outcome = "null")
        env.install(host)
        // 宿主说解不出 ⇒ 继续按签名走 PngDecoder（ImageDecoderHost KDoc 承诺的回落）
        assertEquals(1L, env.call("GCREATEFROMFILE", 23, "ok.png"))
        assertEquals(4L, env.call("GWIDTH", 23))
        assertEquals(argbAt(0), env.call("GGETCOLOR", 23, 0, 0))
    }

    @Test
    fun `宿主失败_抛异常也折成 null 不外泄`() {
        val env = pngEnv()
        val host = FakeDecoder(HashMap(), outcome = "throw")
        env.install(host)
        assertEquals(1L, env.call("GCREATEFROMFILE", 24, "ok.png"))
        assertEquals(1, host.count("resources/ok.png"))
        assertEquals(4L, env.call("GWIDTH", 24))
    }

    @Test
    fun `宿主失败_不认识的格式仍返回 0 且不回落文本载体`() {
        val env = webpEnv()
        // 同一路径放一份**完全合法**的 GSAVE 文本载体：回落就会得到 1 与 6x5。
        // 原版对 webp 只试一条路（Utils.cs:511-515），失败即 null（:521-523）⇒ 0。
        val seed = DecEnv(env.ops)
        assertEquals(1L, seed.call("GCREATE", 60, 6, 5))
        assertEquals(1L, seed.call("GCLEAR", 60, 0xFF00FF00L))
        assertEquals(1L, seed.call("GSAVE", 60, 3))
        env.ops.files["resources/a.webp"] = requireNotNull(env.ops.files["sav/img0003.png"]) { "文本载体没写进去" }

        val host = FakeDecoder(HashMap(), outcome = "null")
        env.install(host)
        assertEquals(0L, env.call("GCREATEFROMFILE", 61, "a.webp"))
        assertEquals(0L, env.call("GCREATED", 61))
        assertEquals(1, host.count("resources/a.webp"))
    }

    @Test
    fun `文件不存在时_宿主一次都不被问`() {
        val env = DecEnv()
        val host = FakeDecoder(HashMap(mapOf("resources/nope.png" to PngImage(1, 1, intArrayOf(0xFF000000.toInt())))))
        env.install(host)
        // Utils.cs:505-508 先判 File.Exists 就 return null，压根不进 try ⇒ 没有字节就没有解码
        assertEquals(0L, env.call("GCREATEFROMFILE", 62, "nope.png"))
        assertEquals(0, host.count("resources/nope.png"))
    }

    // ==================================================================
    // 4) alpha==0 归零（GraphicsImage.cs:119-130）
    // ==================================================================

    @Test
    fun `alpha 归零_宿主像素与 png 支同式`() {
        val env = DecEnv()
        // (0,0) alpha=0 但 RGB 非零 —— GDI+ 把源位图画到透明目标时 RGB 被丢弃
        val pixels = intArrayOf(0x00FF0000.toInt(), 0x80010203.toInt(), 0xFF040506.toInt(), 0x000000FF.toInt())
        env.ops.bytes["resources/a.webp"] = LOSSY_WEBP
        env.install(FakeDecoder(HashMap(mapOf("resources/a.webp" to PngImage(2, 2, pixels.copyOf())))))
        assertEquals(1L, env.call("GCREATEFROMFILE", 30, "a.webp"))
        assertEquals(0L, env.call("GGETCOLOR", 30, 0, 0), "alpha=0 必须归零，不许留 0x00RRGGBB")
        assertEquals(0L, env.call("GGETCOLOR", 30, 1, 1), "同上（右下角那个）")
        // 半透明与非透明原样直通（移植侧不做预乘往返，见 readImageFile 的 KDoc）
        assertEquals((0x80010203L and 0xFFFFFFFFL), env.call("GGETCOLOR", 30, 1, 0))
        assertEquals(0xFF040506L, env.call("GGETCOLOR", 30, 0, 1))
    }

    // ==================================================================
    // 5) 宿主交坏数据（越界 / 长度不符）
    // ==================================================================

    @Test
    fun `宿主交坏数据_越界与长度不符都按解不出来收口`() {
        val env = webpEnv()
        env.install(
            FakeDecoder(
                HashMap(
                    mapOf(
                        // 8193 越过 MAX_IMAGE_SIZE（AbstractImage.cs:8；原版在 :6905 判并返回 0）
                        "big.webp" to PngImage(8193, 1, IntArray(8193) { 0xFF000000.toInt() }),
                        // 尺寸与像素数不匹配（解码器不许半张图落进缓冲）
                        "bad.webp" to PngImage(3, 3, IntArray(4) { 0xFF000000.toInt() }),
                    ),
                ),
            ),
        )
        env.ops.bytes["big.webp"] = LOSSY_WEBP
        env.ops.bytes["bad.webp"] = LOSSY_WEBP
        assertEquals(0L, env.call("GCREATEFROMFILE", 41, "big.webp"))
        assertEquals(0L, env.call("GCREATED", 41))
        assertEquals(0L, env.call("GCREATEFROMFILE", 42, "bad.webp"))
        assertEquals(0L, env.call("GCREATED", 42))
    }

    // ==================================================================
    // 6) GLOAD 与 GCREATEFROMFILE 共用同一通道
    // ==================================================================

    @Test
    fun `GLOAD 走同一解码通道且路径是 sav imgNNNN png`() {
        val env = DecEnv()
        val path = "sav/img0007.png"
        env.ops.bytes[path] = LOSSY_WEBP // 内容随便：宿主按**路径**返回图，core 不看签名
        env.install(FakeDecoder(HashMap(mapOf(path to PngImage(2, 2, IntArray(4) { 0xFF0A0B0C.toInt() })))))

        assertEquals(1L, env.call("GLOAD", 50, 7))
        assertEquals(2L, env.call("GWIDTH", 50))
        assertEquals(0xFF0A0B0CL, env.call("GGETCOLOR", 50, 1, 1))

        // 同一个槽再 GLOAD：已创建直接 0（GraphicsMethods.kt 的 GLOAD 首支，基准 650 行同式），
        // 且这条路**不该**再问宿主一次 —— 防的是把解码挪到返回码分支之后。
        assertEquals(0L, env.call("GLOAD", 50, 7))
    }

    @Test
    fun `GLOAD 未装宿主时仍走 GSAVE 文本载体`() {
        val env = DecEnv()
        val seed = DecEnv(env.ops)
        assertEquals(1L, seed.call("GCREATE", 70, 6, 5))
        assertEquals(1L, seed.call("GCLEAR", 70, 0xFF00FF00L))
        assertEquals(1L, seed.call("GSAVE", 70, 7))
        // 文本载体在 files 里、bytes 里没有 ⇒ readFileBytes 返回 null ⇒ 宿主分支整个跳过
        assertTrue(env.ops.files.containsKey("sav/img0007.png"))
        assertEquals(1L, env.call("GLOAD", 71, 7))
        assertEquals(6L, env.call("GWIDTH", 71))
        assertEquals(0xFF00FF00L, env.call("GGETCOLOR", 71, 0, 0))
    }

    /**
     * `GCREATEFROMFILE` 的相对路径要先接 `Program.ContentDir`（= `<ExeDir>\resources\`）。
     * spec: `FunctionMethodCreator.cs:6890-6894`（`flag ? strValue : Program.ContentDir + strValue`）
     * 与 `Program.cs:219-221`（`SetDirPaths` 里 ContentDir 的算法）。
     *
     * 这一条是真机开局的阻塞点：`ERB/リソース管理/ResourceManager.ERB:5-10` 用
     * `SIF !GCREATED(RM_TITLE)` + 每帧重试的形状等 `GCREATEFROMFILE RM_TITLE, "title.webp"` 建好缓冲，
     * 少了前缀就永远找不到文件 ⇒ 永远返回 0 ⇒ 开局 5,000,001 条指令撞死循环闸
     * （2026-10-21 vivo V2199A 失败屏现场 `RM_タイトル画像用意 行7`）。
     */
    @Test
    fun `GCREATEFROMFILE 相对路径接 ContentDir 前缀且 flag 与带根路径两种豁免`() {
        val env = webpEnv()
        env.install(FakeDecoder(HashMap(mapOf("resources/a.webp" to PngImage(1, 1, intArrayOf(0xFF010203.toInt()))))))
        assertEquals(1L, env.call("GCREATEFROMFILE", 80, "a.webp"), "裸名必须按 resources/ 解析")
        assertEquals(0L, env.call("GCREATEFROMFILE", 81, "a.webp", 1), "flag 非 0 ⇒ 不加前缀 ⇒ 游戏根没有这个文件")

        val absOps = DecOps().apply { bytes["/abs/x.webp"] = LOSSY_WEBP }
        val absHost = FakeDecoder(HashMap(mapOf("/abs/x.webp" to PngImage(1, 1, intArrayOf(0xFF010203.toInt())))))
        val absEnv = DecEnv(absOps).also { it.install(absHost) }
        assertEquals(1L, absEnv.call("GCREATEFROMFILE", 82, "/abs/x.webp"), "带根的路径原样交给宿主")
        assertEquals(listOf("/abs/x.webp/${LOSSY_WEBP.size}"), absHost.asked)
    }
}
