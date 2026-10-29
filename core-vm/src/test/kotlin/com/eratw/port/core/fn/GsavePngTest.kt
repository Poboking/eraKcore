package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.BinaryFileOps
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.image.PngDecoder
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GSAVE 的落盘载体（GFX-13）。
 *
 * 判据与出处：
 * - 载体 = 真 PNG：原版 `graphicsImage.Bitmap.Save(path)` 未指定 `ImageFormat` ⇒ PNG
 *   （FunctionMethodCreator.cs:8125），路径 `{Config.SavDir}img{index:0000}.png`（:9471-9474）；
 * - 索引越界（`< 0` 或 `> int.MaxValue`）⇒ 0（:8116-8120）；
 * - 缓冲未创建 ⇒ 0（:8111-8115）；
 * - 任何落盘/编码失败 ⇒ 0，**不抛 CodeEE**（:8122-8130 的 `catch`）；
 * - 原版对像素数**没有上限**：8192×8192 也只是 `Bitmap.Save` 里 OOM 被同一个 catch 吞掉。
 *
 * 装置说明：本文件自带一套最小 [BuiltinHost] / [FileOps]，不复用
 * `GraphicsMethodsTest` 的私有夹具（那套只有文本通道，走的是回落分支）。
 */
private class PngBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("PngBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 内存文件系统：文本走 [texts]，二进制走 [images]（= 宿主实现了 [BinaryFileOps]）。 */
private class PngFileOps : FileOps, BinaryFileOps {
    val texts = LinkedHashMap<String, String>()
    val images = LinkedHashMap<String, ByteArray>()

    /** 置 true 模拟落盘失败（原版 Save 抛异常）。 */
    var writeFails = false

    override fun exists(path: String): Boolean = texts.containsKey(path) || images.containsKey(path)
    override fun readText(path: String): String? = texts[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        if (writeFails) return false
        texts[path] = if (append) (texts[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        if (writeFails) return false
        images[path] = bytes
        return true
    }
}

/** 只有文本通道的宿主（未接 [BinaryFileOps]，走回落分支）。 */
private class PngTextOnlyOps : FileOps {
    val texts = LinkedHashMap<String, String>()
    override fun exists(path: String): Boolean = texts.containsKey(path)
    override fun readText(path: String): String? = texts[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        texts[path] = if (append) (texts[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class PngHost(
    override val fileOps: FileOps?,
    private val reader: ((String) -> ByteArray?)? = null,
) : BuiltinHost {
    override fun readFileBytes(path: String): ByteArray? = reader?.invoke(path)
}

private fun pngCtx(ops: FileOps?, reader: ((String) -> ByteArray?)? = null): BuiltinEvalContext {
    val store = VariableStore()
    val base = PngBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, PngHost(ops, reader))
}

private fun pngCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun pngI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (pngCall(ctx, name, *args) as Value.IntV).v

private fun pngPool(ctx: BuiltinEvalContext): GraphicsPool = ctx.store.aux.slot("gfx") { GraphicsPool() }

/** PNG 块类型序列（`IHDR` / `IDAT` / `IEND` …），顺带校验块长不越界。 */
private fun chunkTypes(png: ByteArray): List<String> {
    val types = ArrayList<String>()
    var at = 8
    while (at + 8 <= png.size) {
        val len = intAt(png, at)
        val type = String(png, at + 4, 4, Charsets.US_ASCII)
        types.add(type)
        if (type == "IEND") break
        at += 12 + len
    }
    return types
}

private fun intAt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
        ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or
        (bytes[at + 3].toInt() and 0xFF)

class GsavePngTest {

    @Test
    fun `GSAVE 写出真 PNG 的签名与 IHDR`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 3, 2))
        assertEquals(1L, pngI(ctx, "GSAVE", 10, 7))

        val png = ops.images["sav/img0007.png"]
        assertTrue(png != null, "应经二进制通道落盘，实际 images=${ops.images.keys} texts=${ops.texts.keys}")
        // PNG 签名（PNG 规范 5.2）：89 50 4E 47 0D 0A 1A 0A
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(sig.contentEquals(png.copyOfRange(0, 8)), "前 8 字节必须是 PNG 签名")
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(png))
        // IHDR：宽 3、高 2、8 位/通道、色型 6（RGBA）、压缩/滤波/隔行均为 0
        assertEquals(3, intAt(png, 16))
        assertEquals(2, intAt(png, 20))
        assertEquals(8, png[24].toInt() and 0xFF)
        assertEquals(6, png[25].toInt() and 0xFF)
        assertEquals(0, png[26].toInt() and 0xFF)
        assertEquals(0, png[27].toInt() and 0xFF)
        assertEquals(0, png[28].toInt() and 0xFF)
    }

    @Test
    fun `GSAVE 的 PNG 经 PngDecoder 解回同一份像素`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 4, 3))
        assertEquals(1L, pngI(ctx, "GCLEAR", 10, 0xFF112233))
        assertEquals(1L, pngI(ctx, "GSETCOLOR", 10, 0xFF00FF00, 1, 2))
        assertEquals(1L, pngI(ctx, "GSETCOLOR", 10, 0x80ABCDEF, 3, 0))
        val before = pngPool(ctx).get(10).pixels.copyOf()

        assertEquals(1L, pngI(ctx, "GSAVE", 10, 3))
        val decoded = PngDecoder.decode(ops.images.getValue("sav/img0003.png"))
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        // RGBA 逐通道往返：A/R/G/B 顺序写错会在这里显形
        assertTrue(before.contentEquals(decoded.pixels), "PNG 解码后像素应与缓冲逐字节一致")
    }

    @Test
    fun `GSAVE 写 PNG 后 GLOAD 可往返`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops) { path -> ops.images[path] }
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 8, 4))
        assertEquals(1L, pngI(ctx, "GCLEAR", 10, 0xFF112233))
        assertEquals(1L, pngI(ctx, "GSETCOLOR", 10, 0xFF00FF00, 1, 2))
        val before = pngPool(ctx).get(10).pixels.copyOf()

        assertEquals(1L, pngI(ctx, "GSAVE", 10, 3))
        assertEquals(1L, pngI(ctx, "GLOAD", 17, 3))
        assertTrue(before.contentEquals(pngPool(ctx).get(17).pixels), "GSAVE(PNG)→GLOAD 像素应完全一致")
    }

    @Test
    fun `GSAVE 落盘失败按原版折成 0 不抛 CodeEE`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 2, 2))
        ops.writeFails = true
        // spec: FunctionMethodCreator.cs:8122-8130 —— Save 抛异常 → return 0L
        assertEquals(0L, pngI(ctx, "GSAVE", 10, 0))
        assertTrue(ops.images.isEmpty())
    }

    @Test
    fun `GSAVE 未接二进制通道时回落旧文本载体`() {
        val ops = PngTextOnlyOps()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 2, 2))
        assertEquals(1L, pngI(ctx, "GSAVE", 10, 9))
        val text = ops.texts["sav/img0009.png"]
        assertTrue(text != null && text.startsWith("ERATW-GFX 1"), "回落载体应带旧魔数，实际 $text")
        // 旧载体仍可被 GLOAD 读回（readImageFile 的第三跳）
        assertEquals(1L, pngI(ctx, "GLOAD", 17, 9))
        assertEquals(2L, pngI(ctx, "GWIDTH", 17))
    }

    /**
     * 原版没有像素上限：`Bitmap.Save` 的失败只来自异常（含 OOM），被 catch 折成 0
     * （FunctionMethodCreator.cs:8122-8130）。移植侧此前的 400 万像素 CodeEE 属自造，
     * 2048×2048 = 4,194,304 像素必须照常写出，而不是抛错。
     */
    @Test
    fun `GSAVE 超过 400 万像素不再抛 CodeEE`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops)
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 2048, 2048))
        assertEquals(1L, pngI(ctx, "GSAVE", 10, 1))
        val png = ops.images.getValue("sav/img0001.png")
        assertEquals(2048, intAt(png, 16))
        assertEquals(2048, intAt(png, 20))
    }

    @Test
    fun `GSAVE 的索引边界与路径格式`() {
        val ops = PngFileOps()
        val ctx = pngCtx(ops)
        // 未创建 → 0（:8111-8115）
        assertEquals(0L, pngI(ctx, "GSAVE", 10, 0))
        assertEquals(1L, pngI(ctx, "GCREATE", 10, 2, 2))
        // 负值 / 超过 int.MaxValue → 0，不落盘（:8116-8120）
        assertEquals(0L, pngI(ctx, "GSAVE", 10, -1))
        assertEquals(0L, pngI(ctx, "GSAVE", 10, Int.MAX_VALUE.toLong() + 1L))
        assertTrue(ops.images.isEmpty(), "越界索引不应落盘，实际 ${ops.images.keys}")
        // {index:0000} 至少 4 位，超过 4 位按实宽（:9473）
        assertEquals(1L, pngI(ctx, "GSAVE", 10, 0))
        assertEquals(1L, pngI(ctx, "GSAVE", 10, 12345))
        assertEquals(setOf("sav/img0000.png", "sav/img12345.png"), ops.images.keys.toSet())
    }
}
