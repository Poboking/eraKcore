package com.eratw.port.core.host

/**
 * 宿主侧**字形掩码通道**（M11-D 遗留接线项②的核心侧；波次契约 CONTRACT-M11-E §4/§5「glyph-core」）。
 *
 * 背景：`:core-vm` 没有字体栅格器，`GDRAWTEXT` 的 M11-D 形态是「推进盒」替代字形
 * （口径与登记见 `fn/GraphicsMethods.kt` 的 GDRAWTEXT KDoc「替代字形」一节）。本文件补的是
 * 真字形的**数据通道 + 合成运算**：宿主（`m11e-glyph-platform` 路，Android 侧 Paint/Bitmap
 * 提 alpha）按码位交出 8 位覆盖度掩码，核心侧把「画刷填充 + 画笔描边」两遍合成进
 * [GraphicsBuffer.pixels]，对应原版 `GraphicsImage.GDrawString`（GraphicsImage.cs:154-188）
 * 的 `FillPath`（:172-179）→ `DrawPath`（:180-187）两遍。
 *
 * 能力判定与 [BinaryFileOps] 同款（可选、`as?` 探测，不做强制、不改 `Builtins.kt`）：
 * 消费点写 `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost`；没有 provider、
 * host 为 null、或某码位返回 null 掩码，都回落推进盒 —— 行为与 M11-D 逐位一致。
 */

/**
 * 取掩码的请求三键（CONTRACT-M11-E §4 钉死，签名不改）。
 *
 * 三键由 `GDRAWTEXT` 的**布局同源**给出（见其 KDoc「真字形掩码合成」一节）：
 * - [fontName] = 本行测量用的字体名：缓冲 `GSETFONT` 的请求名（[GraphicsBuffer.fontName]），
 *   缓冲未设字体时是回退分支的 `Config.FontName`（spec: FunctionMethodCreator.cs:6522 →
 *   GraphicsImage.cs:166 的 `new Font(Config.FontName, 100f, …)`）；**不是**解析名
 *   [GraphicsBuffer.fontResolvedName]——原版喂给 `AddString` 的也是 `font.FontFamily`
 *   这一份对象，请求与解析在 GDI+ 里不发生二次分叉（GraphicsImage.cs:170）。
 *   宿主据此映射到具体字面（Typeface），映射与回落策略属宿主侧（platform 路）。
 * - [sizePx] = em 字号（像素）：缓冲自带字体时是 `GSETFONT` 设过的字号，回退分支是
 *   测量常量 `FALLBACK_MEASURE_FONT_SIZE`（100）——与逐行推进行盒顶用的同一个数。
 * - [styleMask] = FontStyle 位掩码（Bold=1 / Italic=2 / Strikeout=4 / Underline=8，
 *   GraphicsImage.cs:64-87 的口径），回退分支取控制台现用样式位（:6522）。
 *   宿主可以忽略这些位（无栅格器时可用的替代字体），但不得把它当已生效宣称。
 */
data class GlyphRequest(val fontName: String?, val sizePx: Float, val styleMask: Int)

/**
 * 单个码位的**覆盖度掩码**（CONTRACT-M11-E §4 钉死，签名不改）。
 *
 * ## 原点系约定（宿主侧实现者必须逐字遵守 —— CONTRACT-M11-E §5「glyph-platform」判据①）
 *
 * 掩码贴在画布上的位置：
 *
 * ```text
 * 掩码左上角 (mask 的 (0,0) 像素) = ( ⌊penX⌋ + bearingX , lineTop + bearingY )
 * ```
 *
 * 其中 `penX` 是该码位起笔处的浮点笔位（§3 advance 逐步累加，与推进盒分支的 `penX`
 * **同一个量**；`⌊·⌋` 向下取整），`lineTop` 是**行盒顶**——首行 = GDRAWTEXT 的 y 原点，
 * 逐行 +sizePx（M11-D 实测口径：`AddString` 的 origin 是 em 盒左上角而非基线，
 * 见 `fn/GraphicsMethods.kt` GDRAWTEXT KDoc 的「坐标」「行距」两条）。
 *
 * - [bearingX]：掩码左缘相对笔位的水平偏移，正数向右。承担原来推进盒里 `lsb` 的角色，
 *   但**不参与推进**：advance 恒由 §3 sidecar 决定，掩码通道只决定落墨。
 * - [bearingY]：掩码顶缘相对**行盒顶**的垂直偏移，**正数向下**。
 *   注意这**不是** FreeType 的 top bearing（那是「自基线向上」的正向）；宿主若从
 *   基线系换算，设 `bearingY = ascentFromBoxTop - topBearing`（行盒顶到基线的距离由
 *   宿主自己按字体竖度量给出，核心侧没有基线概念）。
 * - 两个 bearing 都可以为负（掩码越出行盒：上伸部/降部/侧过hang），越界部分由
 *   [compositeGlyphMask] 按 Long 裁到画布内、不报错（同推进盒的裁剪口径）。
 *
 * ## alpha 数组
 *
 * [alpha] 行主序，`alpha[y * width + x]` = 掩码内像素 (x,y) 的覆盖度，0..255
 * （Java ByteArray 有符号，消费侧统一 `and 0xFF` 读）。覆盖度用于填充的预乘
 * （见 [compositeGlyphMask]），0 = 该像素不属于字形。
 * 自洽性（宽/高为正、`alpha.size == width * height`）由 [isSane] 判定；不自洽的掩码
 * 视为宿主实现缺陷，调用方回落推进盒，不抛错。
 */
data class GlyphMask(
    val width: Int, val height: Int,
    val bearingX: Int, val bearingY: Int,
    val alpha: ByteArray,          // 行主序，size = width*height，0..255 覆盖度
)

/** 取字形掩码的宿主能力（CONTRACT-M11-E §4 钉死，签名不改）。 */
interface GlyphMaskHost {
    /** 取字形掩码；null = 无此字形（调用方回落推进盒）。 */
    fun glyphMask(cp: Int, req: GlyphRequest): GlyphMask?
}

/**
 * 宿主透出点（CONTRACT-M11-E §4 钉死，签名不改）。
 *
 * `BuiltinHost` 的实现者（`RootedBuiltinHost`，m11e-hostwire 路接线）顺带实现本接口；
 * 消费方一律 `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost` 探测，
 * 因此 [com.eratw.port.core.fn.BuiltinHost] 本身**不需要**任何改动。
 */
interface GlyphMaskHostProvider { val glyphMaskHost: GlyphMaskHost? }

/**
 * [GlyphMask] 的自洽检查：宽高为正、`width * height` 落在 Int 内且与 [GlyphMask.alpha]
 * 长度一致。返回 false 的掩码**必须**被调用方当作「无此字形」处理（回落推进盒），
 * [compositeGlyphMask] 也对它直接无操作 —— 这是对宿主实现缺陷的兜底，不是语义分支。
 */
fun GlyphMask.isSane(): Boolean {
    if (width <= 0 || height <= 0) return false
    val n = width.toLong() * height.toLong()
    return n <= Int.MAX_VALUE.toLong() && alpha.size == n.toInt()
}

/**
 * 把一块字形掩码合成进 [buffer]（CONTRACT-M11-E §4「合成运算写成 GlyphMaskHost.kt 的
 * 纯函数」；只读写 [GraphicsBuffer] 的公共像素数组与 [GraphicsBuffer.blendPixel]，
 * 不改 `GraphicsBuffer.kt` 本身）。
 *
 * 对应原版 `GraphicsImage.cs:154-188` 的两遍：`FillPath`（画刷，:172-179）与
 * `DrawPath`（画笔，:180-187）。落到掩码上的口径：
 *
 * - **fill = brush 色按 alpha 预乘混合**：掩码像素覆盖度 cov（0..255）时，源色取
 *   [fillColor] 的 RGB、alpha 取 `(fillColor 的 A * cov) / 255`（整除截尾；与
 *   [GraphicsBuffer.blendPixel] 的整式除同族），再按 source-over 合成。cov == 0 的
 *   像素不着色；源 alpha 合成后为 0 的像素跳过（[GraphicsBuffer.blendPixel] 对 A=0
 *   本就无操作）。
 * - **outline = 掩码边界按 pen 宽描 pen 色**：令 mask 集 = 覆盖度 > 0 的掩码像素。
 *   描边集 = 到 mask 集的**切比雪夫距离**在 `1..penWidth` 内、且自身不属于 mask 集的
 *   像素（含掩码矩形外扩 penWidth 一圈）；`penWidth == 1` 时恰为契约原文的
 *   「膨胀 1 减自身」（3x3 方核、8 邻域）。这些像素以 [outlineColor] **原样**（全不透明
 *   按其自带 alpha）着色 —— 边界像素覆盖度恒 0，没有可预乘的量。
 *   `penWidth <= 0` 时完全不描（与推进盒回落 `strokeRect` 的无操作口径一致）；
 *   `penWidth` 超过掩码自身尺寸时**饱和**到 `max(width, height)`（更宽的笔在推进盒
 *   时代也只是填满盒子，不新增语义）。
 * - **先 fill 后 outline**（FillPath → DrawPath，:172-187）。两集合按构造不相交
 *   （描边只落在覆盖度 0 的像素），顺序在结果上不可见，但仍按原版顺序执行。
 * - **裁剪**：贴放坐标 [destX]/[destY] 一律 Long（`GDRAWTEXT` 的原点可含 Int 极值），
 *   画布外部分丢弃、不报错 —— 与推进盒的 `fillRect`/`strokeRect` 同一口径
 *   （GDI+ 只裁剪，实测见 GDRAWTEXT KDoc「裁剪」条）。
 *
 * [mask] 未过 [isSane] 时直接无操作（调用方应先判、走推进盒回落）。
 *
 * @param destX 掩码左上角落在画布上的 x（= ⌊penX⌋ + bearingX，由调用方算好）
 * @param destY 掩码左上角落在画布上的 y（= lineTop + bearingY，由调用方算好）
 * @param fillColor 填充色（ARGB；`GDRAWTEXT` 侧取 `g.brushColor ?: DEFAULT_FORE_COLOR`）
 * @param outlineColor 描边色（ARGB；取 `g.penColor ?: DEFAULT_FORE_COLOR`）
 * @param outlineWidth 笔宽像素（`new Pen(color)` 默认 1；GSETPEN 后用其宽度）
 */
fun compositeGlyphMask(
    buffer: GraphicsBuffer,
    destX: Long,
    destY: Long,
    mask: GlyphMask,
    fillColor: Int,
    outlineColor: Int,
    outlineWidth: Int,
) {
    if (!mask.isSane() || !buffer.isCreated) return
    val w = mask.width
    val h = mask.height
    val alpha = mask.alpha

    // ---- fill：brush 色按覆盖度预乘（FillPath，GraphicsImage.cs:172-179）----
    val brushA = fillColor ushr 24
    if (brushA != 0) {
        val rgb = fillColor and 0xFFFFFF
        for (my in 0 until h) {
            val ty = destY + my.toLong()
            if (ty < 0 || ty >= buffer.height) continue
            val rowBase = my * w
            for (mx in 0 until w) {
                val cov = alpha[rowBase + mx].toInt() and 0xFF
                if (cov == 0) continue
                val tx = destX + mx.toLong()
                if (tx < 0 || tx >= buffer.width) continue
                val a = (brushA * cov) / 255
                if (a == 0) continue
                buffer.blendPixel(tx.toInt(), ty.toInt(), (a shl 24) or rgb)
            }
        }
    }

    // ---- outline：掩码边界（膨胀 penWidth 减自身）描 pen 色（DrawPath，:180-187）----
    if (outlineWidth > 0 && (outlineColor ushr 24) != 0) {
        val radius = minOf(outlineWidth.toLong(), maxOf(w, h).toLong()).toInt()
        for (my in -radius until h + radius) {
            val ty = destY + my.toLong()
            if (ty < 0 || ty >= buffer.height) continue
            for (mx in -radius until w + radius) {
                val inside = mx in 0 until w && my in 0 until h
                if (inside && alpha[my * w + mx].toInt() and 0xFF != 0) continue
                if (!hasCoverageNear(alpha, w, h, mx, my, radius)) continue
                val tx = destX + mx.toLong()
                if (tx < 0 || tx >= buffer.width) continue
                buffer.blendPixel(tx.toInt(), ty.toInt(), outlineColor)
            }
        }
    }
}

/**
 * 像素 (x,y)（可越出掩码矩形）的切比雪夫距离 [radius] 内是否存在覆盖度 > 0 的掩码像素。
 * 窗口按掩码矩形夹取：矩形外的像素到 mask 集的距离即「到矩形内最近覆盖像素」的
 * 切比雪夫距离，越界方向没有掩码像素、无需扫。
 */
private fun hasCoverageNear(alpha: ByteArray, w: Int, h: Int, x: Int, y: Int, radius: Int): Boolean {
    val y0 = maxOf(0, y - radius)
    val y1 = minOf(h - 1, y + radius)
    val x0 = maxOf(0, x - radius)
    val x1 = minOf(w - 1, x + radius)
    for (yy in y0..y1) {
        val base = yy * w
        for (xx in x0..x1) {
            if (alpha[base + xx].toInt() and 0xFF != 0) return true
        }
    }
    return false
}
