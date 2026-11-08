package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GCREATEFROMFILE` 的**延迟装载**契约（M12-MEM-GFX：点「爱抚」闪退的正解）。
 *
 * ## 病
 *
 * 本作语料的立绘动图是整张工作表：`resources/As06MARK-IV/anime/服_笑顔动_25.webp`
 * 是 8000x4000 ⇒ ARGB **122 MiB**。原版 PC 上它是 GDI+ `Bitmap`（原生内存），
 * 移植侧 `GraphicsBuffer.pixels` 是 `IntArray`（Dalvik 堆，`largeHeap` 512 MiB 挡）——
 * `RM_G_ID` → `GCREATEFROMFILE` 一解就把堆吃掉近四分之一，再叠渲染侧同尺寸位图副本，
 * 训练场景里点一个会打印立绘的指令就 `OutOfMemoryError` 直接闪退，连报告都落不下来。
 *
 * ## 本文件钉死的行为
 *
 * | # | 行为 | 用例 |
 * |---|---|---|
 * | 1 | 宿主能只读文件头量尺寸时，`GCREATEFROMFILE` **一次都不解码**，返回 1、尺寸照旧可读 | 「装了宿主且能探尺寸」 |
 * | 2 | 像素仍按需落地：第一次真读像素才解码，且**只解一次** | 同上 |
 * | 3 | 量不出尺寸 ⇒ 回落到原来的立即解码分支（返回码、像素都不变） | 「探不出尺寸」 |
 * | 4 | 文件源登记在缓冲上，渲染侧据此绕开 `pixels` | 同上（`fileSource`） |
 * | 5 | 缓冲被画写之后文件源立刻作废（内容不再等于文件） | 「画写之后」 |
 * | 6 | 未装宿主 ⇒ 逐项与今天相同（纯 JVM 闸 / 语料对拍走的就是这条） | 「未装宿主」 |
 *
 * 装置与 `ImageDecoderHostTest` 同构（内存 `FileOps` + 可注入 `readFileBytes` + 独立
 * `VariableStore`），只是假 decoder 多实现一个 `probe`。
 */
private class DefBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DefBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

private class DefOps : FileOps {
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

/**
 * 假 decoder：`probe` 按 [probeSize] 答尺寸（null = 量不出来），`decode` 记账并回 [image]。
 *
 * 两条计数分开记，才能断言「创建时没解码、首像素访问才解码」这条核心判据。
 */
private class DefDecoder(
    private val probeSize: IntArray?,
    private val image: PngImage?,
) : ImageDecoderHost {
    var probeCalls = 0
        private set
    var decodeCalls = 0
        private set

    override fun decode(path: String, bytes: ByteArray): PngImage? {
        decodeCalls++
        return image
    }

    override fun probe(path: String, bytes: ByteArray): IntArray? {
        probeCalls++
        return probeSize?.copyOf()
    }
}

private class DefEnv(val ops: DefOps = DefOps()) {
    val store = VariableStore()
    private val base = DefBaseCtx().also { it.variables = store }
    val ctx: BuiltinEvalContext = BuiltinEvalContext(
        base,
        store,
        object : BuiltinHost {
            override val fileOps: FileOps? = ops
            override fun readFileBytes(path: String): ByteArray? = ops.bytes[path]
        },
    )

    fun install(decoder: ImageDecoderHost) {
        store.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { decoder }
    }

    fun buffer(id: Int): GraphicsBuffer? = store.aux.peek<GraphicsPool>("gfx")?.peek(id)

    fun call(name: String, vararg args: Any): Long {
        val value = ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        )
        return (value as Value.IntV).v
    }
}

class GraphicsDeferredFileTest {

    /** 4x3、逐像素可核对的假图：下标 i 的像素 = 0xFF000000 | (i * 0x11)。 */
    private val argb = IntArray(12) { 0xFF000000.toInt() or (it * 0x11) }
    private fun argbAt(index: Int): Long = argb[index].toLong() and 0xFFFFFFFFL

    private fun env(decoder: ImageDecoderHost): DefEnv =
        DefEnv().apply {
            // GCREATEFROMFILE 对相对路径先接 ContentDir（`resources/`），宿主收到的是带前缀的路径
            ops.bytes["resources/pic.webp"] = byteArrayOf(1, 2, 3, 4)
            install(decoder)
        }

    @Test
    fun `装了宿主且能探尺寸_创建时不解码_首像素访问才解一次`() {
        val dec = DefDecoder(intArrayOf(4, 3), PngImage(4, 3, argb.copyOf()))
        val e = env(dec)

        assertEquals(1L, e.call("GCREATEFROMFILE", 20, "pic.webp"), "量得出尺寸就该当场成功（原版返回码语义）")
        assertEquals(1, dec.probeCalls, "应当只问一次文件头")
        assertEquals(0, dec.decodeCalls, "创建阶段一个像素都不该解 —— 这正是 122 MiB 那一刀的落点")

        // 尺寸照旧可读（脚本可见读数不变）
        assertEquals(1L, e.call("GCREATED", 20))
        assertEquals(4L, e.call("GWIDTH", 20))
        assertEquals(3L, e.call("GHEIGHT", 20))

        val buffer = e.buffer(20)
        assertNotNull(buffer, "缓冲应当已经建好（延迟形态）")
        assertEquals("resources/pic.webp", buffer.fileSource, "文件源必须登记，渲染侧靠它绕开 pixels")

        // 首像素访问才解码，且只解一次
        assertEquals(argbAt(0), e.call("GGETCOLOR", 20, 0, 0))
        assertEquals(1, dec.decodeCalls, "首像素访问应当触发一次解码")
        assertEquals(argbAt(5), e.call("GGETCOLOR", 20, 1, 1))
        assertEquals(argbAt(11), e.call("GGETCOLOR", 20, 3, 2))
        assertEquals(1, dec.decodeCalls, "同一缓冲第二次读像素不该再解")
    }

    @Test
    fun `探不出尺寸_回落立即解码且不登记文件源`() {
        val dec = DefDecoder(null, PngImage(4, 3, argb.copyOf()))
        val e = env(dec)

        assertEquals(1L, e.call("GCREATEFROMFILE", 21, "pic.webp"))
        assertEquals(1, dec.probeCalls, "先问文件头")
        assertEquals(1, dec.decodeCalls, "量不出来 ⇒ 回落到立即解码（老路，返回码不变）")
        assertEquals(argbAt(0), e.call("GGETCOLOR", 21, 0, 0))
        assertNull(
            e.buffer(21)?.fileSource,
            "立即解码的缓冲没有「内容等于文件」的保证，不该登记文件源",
        )
    }

    @Test
    fun `画写之后文件源作废`() {
        val dec = DefDecoder(intArrayOf(4, 3), PngImage(4, 3, argb.copyOf()))
        val e = env(dec)
        assertEquals(1L, e.call("GCREATEFROMFILE", 22, "pic.webp"))
        assertEquals("resources/pic.webp", e.buffer(22)?.fileSource)

        // 往这张缓冲上画一笔：文件内容不再等于缓冲内容，渲染侧必须回落到 pixels
        e.call("GCLEAR", 22, 0xFF00FF00L)
        assertNull(e.buffer(22)?.fileSource, "GCLEAR 之后文件源必须作废")
        assertEquals(0xFF00FF00L, e.call("GGETCOLOR", 22, 0, 0))
    }

    @Test
    fun `未装宿主_逐项与今天相同`() {
        val e = DefEnv().apply { ops.bytes["resources/pic.webp"] = byteArrayOf(1, 2, 3, 4) }
        // 没有解码通道、也没有可解的内容（4 字节不是 png/webp）⇒ 与接入前一样返回 0
        assertEquals(0L, e.call("GCREATEFROMFILE", 23, "pic.webp"))
        assertEquals(0L, e.call("GCREATED", 23))
        assertNull(e.buffer(23)?.fileSource, "没有解码通道就没有延迟装载")
    }

    @Test
    fun `宿主抛异常_按量不出来处理不冒泡`() {
        val dec = object : ImageDecoderHost {
            override fun decode(path: String, bytes: ByteArray): PngImage? = null
            override fun probe(path: String, bytes: ByteArray): IntArray? =
                throw IllegalStateException("模拟 BitmapFactory 对畸形字节抛异常")
        }
        val e = env(dec)
        assertEquals(0L, e.call("GCREATEFROMFILE", 24, "pic.webp"), "探针抛异常 ⇒ 回落立即解码 ⇒ 解不出来 ⇒ 0")
        assertTrue(e.buffer(24)?.fileSource == null)
    }
}
