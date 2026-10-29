package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.GlyphMask
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.GlyphRequest
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GDRAWTEXT` 的**真字形掩码合成**（M11-E，CONTRACT-M11-E §5「glyph-core」判据①..⑤）。
 *
 * 原版锚点：`GraphicsImage.cs:154-188` —— 同一份 path 先 `FillPath`（画刷，:172-179）
 * 再 `DrawPath`（画笔，:180-187）。移植侧把这两遍落到宿主掩码通道
 * （`core/host/GlyphMaskHost.kt`）的合成函数 [com.eratw.port.core.host.compositeGlyphMask] 上：
 * fill = 画刷色按掩码覆盖度预乘混合；outline = 掩码边界（按 pen 宽膨胀减自身，penWidth=1
 * 即契约原文的「膨胀 1 减自身」）以画笔色满覆盖描。
 *
 * 装置掩码 = 合成宿主（实心块 / 空心框 / 非零 bearing，判据⑤），断言的是**合成后的像素**，
 * 每处注明对齐 C# 哪几行；推进盒回退面（判据①）与 `GdrawTextDrawTest` 同装置对照。
 *
 * 度量口径沿用 `GdrawTextDrawTest`：未接 §3 表时 ASCII 半角步进 = 字号 / 2 ⇒ 字号 10 的
 * `A` 推 5px、RESULT 宽 5 —— **掩码通道不改变推进**（bearing 只定位移）。
 */
private class GdtgBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("GdtgBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 按码位查表交掩码的合成宿主；记录被查询的码位与最后一次请求三键。 */
private class GdtgScriptHost(
    private val masks: Map<Int, GlyphMask>,
    private val default: GlyphMask? = null,
) : GlyphMaskHost {
    val queried = ArrayList<Int>()
    var lastRequest: GlyphRequest? = null

    override fun glyphMask(cp: Int, req: GlyphRequest): GlyphMask? {
        queried.add(cp)
        lastRequest = req
        return masks[cp] ?: default
    }
}

private class GdtgCtxHost(private val glyph: GlyphMaskHost?) : BuiltinHost, GlyphMaskHostProvider {
    override val glyphMaskHost: GlyphMaskHost? get() = glyph
}

private fun gdtgCtx(glyphHost: GlyphMaskHost? = null): BuiltinEvalContext {
    val store = VariableStore()
    val base = GdtgBaseCtx()
    base.variables = store
    val host = GdtgCtxHost(glyphHost)
    return BuiltinEvalContext(base, store, host)
}

private fun gdtgI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    ) as Value.IntV).v

/**
 * 造一个 40x40、字号 10、画刷红、画笔蓝（宽 [penWidth]）的缓冲。
 *
 * 颜色按官方 `ReadColor`（`FunctionMethodCreator.cs`）口径取 **ARGB 无符号 32 位**
 * （`long` 校验 `0..uint.MaxValue`）⇒ 传参必须用 `Long`；传 `Int`
 * （`0xFFFF0000.toInt()` 即 `-65536`）会被产品合法地拒成「超出 ARGB 范围」。
 */
private fun gdtgSetup(ctx: BuiltinEvalContext, id: Int = 10, penWidth: Int = 1, brush: Long = 0xFFFF0000L) {
    assertEquals(1L, gdtgI(ctx, "GCREATE", id, 40, 40))
    assertEquals(1L, gdtgI(ctx, "GSETFONT", id, "黑体", 10))
    assertEquals(1L, gdtgI(ctx, "GSETBRUSH", id, brush))
    assertEquals(1L, gdtgI(ctx, "GSETPEN", id, 0xFF0000FFL, penWidth))
}

private fun gdtgPixel(ctx: BuiltinEvalContext, id: Int, x: Int, y: Int): Long =
    gdtgI(ctx, "GGETCOLOR", id, x, y)

/** 实心块掩码（判据⑤的第一种）。 */
private fun gdtgSolid(w: Int, h: Int, cov: Int = 255, bx: Int = 0, by: Int = 0): GlyphMask =
    GlyphMask(w, h, bx, by, ByteArray(w * h) { cov.toByte() })

/** 空心框掩码：外圈一圈覆盖、内部全 0（判据⑤的第二种）。 */
private fun gdtgFrame(w: Int, h: Int): GlyphMask {
    val a = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) a[y * w + x] = 0xFF.toByte()
        }
    }
    return GlyphMask(w, h, 0, 0, a)
}

private val RED = 0xFFFF0000L
private val BLUE = 0xFF0000FFL
private val FORE = 0xFFC0C0C0L   // DEFAULT_FORE_COLOR（GSETFONT KDoc 引 Config.ForeColor 口径）

class GdrawTextGlyphTest {

    /**
     * 实心块掩码：先 FillPath（画刷红，GraphicsImage.cs:172-179）后 DrawPath
     * （画笔蓝边界，:180-187），并核对 [GlyphRequest] 三键与本布局同源、RESULT 测量不受掩码通道影响。
     */
    @Test
    fun `实心块掩码 填充加一圈描边`() {
        val host = GdtgScriptHost(mapOf('A'.code to gdtgSolid(3, 2)))
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx)
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "A", 5, 5))
        // 掩码左上角 = (⌊penX⌋ + bearingX, lineTop + bearingY) = (5,5)（GlyphMask KDoc 原点系）
        assertEquals(RED, gdtgPixel(ctx, 10, 5, 5), "覆盖 255 的像素按画刷原样着色（:172-179）")
        assertEquals(RED, gdtgPixel(ctx, 10, 7, 6), "3x2 实心块右下角")
        // penWidth=1 ⇒ 「膨胀 1 减自身」：块外一圈画笔色（:180-187），且这一圈在掩码矩形**外侧**
        assertEquals(BLUE, gdtgPixel(ctx, 10, 4, 4))
        assertEquals(BLUE, gdtgPixel(ctx, 10, 8, 7))
        assertEquals(BLUE, gdtgPixel(ctx, 10, 4, 6), "左缘整列都是 1px 边界")
        assertEquals(0L, gdtgPixel(ctx, 10, 3, 4), "距离 2 不着色")
        assertEquals(0L, gdtgPixel(ctx, 10, 4, 3), "上缘外一格不着色")
        // 请求三键 = 布局同源（GSETFONT 请求名 / 字号 / 样式位 0）
        assertEquals(GlyphRequest("黑体", 10f, 0), host.lastRequest)
        // 测量通道不变：RESULT 宽 = §3 退化的半角步进 5、高 = 字号 10（FMC.cs:6526-6527）
        assertEquals(5L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(10L, ctx.store.readInt("RESULT", longArrayOf(2)))
        assertEquals(listOf('A'.code), host.queried)
    }

    /**
     * 判据①：无 provider / host 为 null / 掩码为 null / 掩码不自洽 ⇒ 与 M11-D 推进盒
     * 逐位一致（对照同一画法下的边缘与内部像素；推进盒几何见 `GdrawTextDrawTest`）。
     */
    @Test
    fun `四档回落与推进盒现状逐位一致`() {
        // (a) **不实现** GlyphMaskHostProvider 的普通宿主 —— 消费点 `as?` 直接落空
        val storeA = VariableStore()
        val baseA = GdtgBaseCtx()
        baseA.variables = storeA
        val a = BuiltinEvalContext(baseA, storeA, object : BuiltinHost {})
        // (b) provider 存在但 glyphMaskHost = null
        val b = gdtgCtx(null)
        gdtgSetup(a)
        gdtgSetup(b)
        assertEquals(1L, gdtgI(a, "GDRAWTEXT", 10, "A", 0, 0))
        assertEquals(1L, gdtgI(b, "GDRAWTEXT", 10, "A", 0, 0))
        // (c) host 返回 null（无此字形）
        val nullHost = GdtgScriptHost(emptyMap())
        val c = gdtgCtx(nullHost)
        gdtgSetup(c)
        assertEquals(1L, gdtgI(c, "GDRAWTEXT", 10, "A", 0, 0))
        // (d) 掩码不自洽（alpha 长度 != w*h）⇒ 视为无此字形（GlyphMask.isSane 的兜底口径）
        val badHost = GdtgScriptHost(mapOf('A'.code to GlyphMask(2, 2, 0, 0, ByteArray(3))))
        val d = gdtgCtx(badHost)
        gdtgSetup(d)
        assertEquals(1L, gdtgI(d, "GDRAWTEXT", 10, "A", 0, 0))
        // 四档在同一组探针像素上逐位一致：盒 [0,5)x[0,10)，边框画笔、内部画刷
        for ((x, y) in listOf(0 to 0, 1 to 1, 4 to 0, 4 to 9, 0 to 9, 2 to 5, 5 to 5)) {
            val pa = gdtgPixel(a, 10, x, y)
            assertEquals(pa, gdtgPixel(b, 10, x, y), "($x,$y) b 档回退分叉")
            assertEquals(pa, gdtgPixel(c, 10, x, y), "($x,$y) c 档回退分叉")
            assertEquals(pa, gdtgPixel(d, 10, x, y), "($x,$y) d 档回退分叉")
        }
        assertEquals(BLUE, gdtgPixel(a, 10, 0, 0), "推进盒左上角是描边（M11-D 现状）")
        assertEquals(RED, gdtgPixel(a, 10, 1, 1), "推进盒内部是填充")
        assertEquals(0L, gdtgPixel(a, 10, 5, 5), "盒外无墨")
        assertTrue(nullHost.queried.isNotEmpty(), "c 档确实走到了宿主查询")
    }

    /**
     * 空心框掩码：框线本身被画刷填充（FillPath :172-179），框内外边界都被描画笔色
     * （DrawPath :180-187 —— 掩码集的边界含**内侧**一圈，这是「膨胀减自身」的推论）；
     * 离框线两格以上的空洞不落墨。
     */
    @Test
    fun `空心框掩码 内外两圈边界`() {
        val host = GdtgScriptHost(mapOf('A'.code to gdtgFrame(5, 5)))
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx)
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "A", 10, 10))
        assertEquals(RED, gdtgPixel(ctx, 10, 10, 10), "框左上角是覆盖像素 ⇒ 填充")
        assertEquals(RED, gdtgPixel(ctx, 10, 14, 12), "框右缘中点")
        assertEquals(RED, gdtgPixel(ctx, 10, 12, 14), "框下缘中点")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 11, 11), "内侧环：与框线距离 1 的空洞像素被描")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 12, 11), "内侧环：上框线下方一格")
        assertEquals(0L, gdtgPixel(ctx, 10, 12, 12), "框心距覆盖 2 格 ⇒ 不落墨")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 9, 9), "外侧环：角上斜邻一格（膨胀 1 = 8 邻域）")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 15, 15), "外侧环：右下角外一格")
        assertEquals(0L, gdtgPixel(ctx, 10, 8, 8), "距覆盖 2 格的外侧不落墨")
    }

    /**
     * 非零 bearing（判据⑤第三种）：正 bearing 把掩码推到盒外右侧/下方；
     * 负 bearing 让掩码越出画布左上角 ⇒ 按 Long 裁剪、不报错（判据④，同
     * `GDI+ 只裁剪` 的推进盒口径，见 GDRAWTEXT KDoc「裁剪」条与 GraphicsImage.cs:154-188 无范围检查）。
     */
    @Test
    fun `非零与负 bearing 定位与裁剪`() {
        val host = GdtgScriptHost(
            mapOf(
                'A'.code to gdtgSolid(2, 2, bx = 2, by = 3),
                'B'.code to gdtgSolid(2, 2, bx = -1, by = -1),
            ),
        )
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx)
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "A", 0, 0))
        // 左上角 = (⌊0⌋ + 2, 0 + 3)
        assertEquals(RED, gdtgPixel(ctx, 10, 2, 3))
        assertEquals(RED, gdtgPixel(ctx, 10, 3, 4), "2x2 实心块右下角")
        assertEquals(0L, gdtgPixel(ctx, 10, 0, 0), "bearing 偏移的空白不落墨")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 1, 2), "块外一圈仍是描边")

        assertEquals(1L, gdtgI(ctx, "GCLEAR", 10, 0))
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "B", 0, 0))
        // 左上角 = (-1,-1)：画布内只剩掩码右下角像素 (0,0) 一个填充点，越界部分被裁掉
        assertEquals(RED, gdtgPixel(ctx, 10, 0, 0))
        assertEquals(BLUE, gdtgPixel(ctx, 10, 1, 1), "负 bearing 的外扩边界照描（画布内部分）")
        assertEquals(0L, gdtgPixel(ctx, 10, 2, 2), "距离 2 不落墨")
        assertEquals(listOf('A'.code, 'B'.code), host.queried)
    }

    /**
     * 判据③：接了宿主，空白与零 advance 码位**仍不落墨、也不被查询**
     * （真字体的空格没有轮廓，FillPath 不出像素；零 advance 不给出墨权）。
     */
    @Test
    fun `空白与零advance不落墨也不查宿主`() {
        val host = GdtgScriptHost(mapOf('A'.code to gdtgSolid(4, 4)))
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx)
        // "A B"：空格不落墨 ⇒ 中段 (5..8, 0..8) 干净（两块的描边环只到 x=4 与 x=9）
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "A B", 0, 0))
        assertEquals(RED, gdtgPixel(ctx, 10, 0, 0), "第一个 A 的掩码 (0,0)")
        assertEquals(RED, gdtgPixel(ctx, 10, 12, 2), "第二个 A 的块覆盖 10..12（两次 5px 推进后 dest=10）")
        assertEquals(0L, gdtgPixel(ctx, 10, 7, 2), "空格段不落墨")
        assertEquals(0L, gdtgPixel(ctx, 10, 6, 0), "空格段不落墨（含两盒邻边）")
        // "e + U+0301"（Mn 组合符，零步进）：组合符不查宿主、不落墨；
        // 'e' 在宿主表里没有掩码（default = null）⇒ 走推进盒（边框蓝、内部红）
        assertEquals(1L, gdtgI(ctx, "GCLEAR", 10, 0))
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "e\u0301", 0, 0))
        assertEquals(BLUE, gdtgPixel(ctx, 10, 0, 0), "e 的推进盒边框")
        assertEquals(RED, gdtgPixel(ctx, 10, 1, 1), "e 的推进盒内部")
        assertEquals(0L, gdtgPixel(ctx, 10, 6, 1), "组合符零步进、零落墨")
        assertEquals(listOf('A'.code, 'B'.code, 'e'.code), host.queried, "空格与组合符不得进宿主查询")
    }

    /**
     * 判据②的颜色口径：填充色 = `g.brushColor` 按覆盖度预乘（A 通道相乘、整除截尾），
     * 描边色 = `g.penColor` 缺省 [FORE]（DEFAULT_FORE_COLOR，对应 `SolidBrush(Config.ForeColor)`
     * :178 / `new Pen(Config.ForeColor)` :186）；两者都吃各自 alpha（SourceOver，同 blendPixel）。
     */
    @Test
    fun `半透明画刷按覆盖度预乘 描边回落ForeColor`() {
        // 掩码用 3x3：中心是**内部**（只被 FillPath 覆盖）、外圈是边界（FillPath 之后被
        // DrawPath 描边覆盖 —— `GraphicsImage.cs` 先填后描）。1x1 掩码没有内部，
        // 描边必然盖掉填充，观测不到预乘口径。
        val host = GdtgScriptHost(mapOf('A'.code to gdtgSolid(3, 3, cov = 128, bx = 0, by = 0)))
        val ctx = gdtgCtx(host)
        // 画刷 A=0x80；**不设画笔** ⇒ 描边走 ForeColor、宽 1（:186 的 new Pen(Config.ForeColor)）
        assertEquals(1L, gdtgI(ctx, "GCREATE", 11, 40, 40))
        assertEquals(1L, gdtgI(ctx, "GSETFONT", 11, "黑体", 10))
        assertEquals(1L, gdtgI(ctx, "GSETBRUSH", 11, 0x80FF0000L))
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 11, "A", 20, 20))
        // 中心内部：源 alpha = 128*128/255 = 64；叠在透明目标上 ⇒ A=R=64
        assertEquals(0x40400000L, gdtgPixel(ctx, 11, 21, 21), "预乘口径 (brushA*cov)/255 整除截尾")
        // 本侧描边模型是「膨胀 penWidth 减自身」⇒ 掩码**覆盖像素**仍是填充（含四角），
        // 画笔色只落在掩码矩形**外侧**的一圈（同 `实心块掩码 填充加一圈描边`）。
        assertEquals(0x40400000L, gdtgPixel(ctx, 11, 20, 20), "覆盖角仍是填充（预乘口径）")
        assertEquals(FORE, gdtgPixel(ctx, 11, 19, 19), "掩码外侧一圈是描边（未设画笔 ⇒ ForeColor）")
        assertEquals(0L, gdtgPixel(ctx, 11, 18, 18))
    }

    /**
     * 笔宽口径同现状（判据②）：`GSETPEN` 的宽度进描边集的膨胀半径（penWidth=2 ⇒
     * 边界环厚 2px）；`new Pen(color)` 默认 1（GraphicsImage.cs:180-187 的回落分支）。
     */
    @Test
    fun `描边厚度跟随penWidth`() {
        val host = GdtgScriptHost(mapOf('A'.code to gdtgSolid(2, 2)))
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx, penWidth = 2)
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "A", 10, 10))
        assertEquals(RED, gdtgPixel(ctx, 10, 10, 10), "实心 2x2")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 9, 9), "距覆盖 1 格")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 8, 8), "距覆盖 2 格仍在厚 2 的环内")
        assertEquals(0L, gdtgPixel(ctx, 10, 7, 7), "距覆盖 3 格出环")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 13, 10), "右缘：距覆盖 2 格")
        assertEquals(0L, gdtgPixel(ctx, 10, 14, 10), "右缘：距覆盖 3 格")
    }

    /**
     * 推进仍由 §3 步进决定、掩码通道不参与（bearing 不改 advance）：
     * 两个 `A` 的第二块左上角落在 `⌊5⌋`（半角步进 5），RESULT 宽 = 10。
     */
    @Test
    fun `掩码不改变推进 逐码位取掩码`() {
        val host = GdtgScriptHost(mapOf('A'.code to gdtgSolid(3, 3)))
        val ctx = gdtgCtx(host)
        gdtgSetup(ctx)
        assertEquals(1L, gdtgI(ctx, "GDRAWTEXT", 10, "AA", 0, 0))
        assertEquals(RED, gdtgPixel(ctx, 10, 0, 0), "第一块左上角")
        assertEquals(RED, gdtgPixel(ctx, 10, 5, 1), "第二块左上角 = ⌊penX⌋ = 5（advance 来自 §3 退化网格）")
        assertEquals(0L, gdtgPixel(ctx, 10, 9, 1), "第二块右缘在 x=7")
        assertEquals(BLUE, gdtgPixel(ctx, 10, 4, 1), "两块之间的边界（各距 1）")
        assertEquals(10L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(listOf('A'.code, 'A'.code), host.queried, "每个码位各查一次")
    }
}
