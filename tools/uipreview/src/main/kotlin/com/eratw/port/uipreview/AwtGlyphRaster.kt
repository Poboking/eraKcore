package com.eratw.port.uipreview

import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.FallbackWidthClass
import com.eratw.port.render.glyph.GlyphRaster
import com.eratw.port.render.glyph.RasterizedGlyph
import com.eratw.port.render.glyph.TextMetrics
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.InputStream

/**
 * 桌面侧的真字形光栅器（CONTRACT-UP2 §4.1）：[GlyphRaster] 的 java.awt 实现，
 * 用 `Font.createFont` 从 classpath 的 TTF 子集（`:render` 的 resources `fonts/` 目录，
 * c-fontsub 交付面）载入字形，替换 [com.eratw.port.render.glyph.StaticGlyphRaster] 的
 * 占位墨迹。
 *
 * ## 铁不变量：advance 与布局完全不归 AWT 管
 *
 * 本类是「换光栅器前后布局逐位不变」承诺的落点一侧：
 * - **advance 一律走 [metrics]**（[TextMetrics]），路径与 StaticGlyphRaster 逐式相同
 *   （`StaticGlyphRaster.rasterize` :94-119：`advancePixels(...) ?: 缺字分档`）。
 *   禁止用 `FontMetrics.stringWidth` / `canDisplayUpTo` 之类 AWT 读数当 advance ——
 *   那会把 AWT 的字体度量灌进布局，破坏不变量；
 * - 位图几何照 Static 契约：`bitmapWidth = advance`（非正给 0）、
 *   `bitmapHeight = converter.lineHeight`。于是同一 (字体, 样式, 码位, 字号) 在两个
 *   光栅器下 advance / lsb / 位图宽高**全部相等**，唯一差别是位图内容：
 *   Static 是编码占位色块，本类是 alpha = 抗锯齿覆盖率的真字形墨。
 *
 * ## 墨迹像素语义
 *
 * 产出的位图是**覆盖率掩码**：alpha = 该像素的墨量（0 = 透明，255 = 全墨），
 * RGB 恒 `0xFFFFFF` 白。着色发生在绘制侧（[InkPainter]）：按 alpha 当覆盖率、
 * 用 `cmd.fore` 补 alpha 后 source-over 合成。Static 的实心像素（alpha 恒 FF）
 * 走同一通道得到纯色块 —— 两个光栅器共用一条着色路径，换芯不改绘制侧。
 *
 * ## 字体家族 = advance 来源家族（硬约束）
 *
 * 度量链装配为 `substituteFamily = "Saitamaar"`（`FontSidecarCatalog`，FontSidecar.kt:343-352）：
 * 脚本请求的族名链上没有表时，第二跳 Saitamaar 补位。墨迹必须与 advance 同源，否则
 * 「表给的 advance × 字形真实宽度」错位漏墨。因此本类对**任何**请求名都落同一份
 * saitamaar-subset.ttf —— 对齐 `FontSidecarCatalog.fallbackFamily` 的口径
 * （移植版不分发微软版权字体，兜底自己的子集，FontSidecar.kt:408-418）；
 * 显式登记的其余家族（Unifont 等）按名直取。字形在 TTF 里没有（[Font.canDisplay] 假）
 * 返回 null → [com.eratw.port.render.glyph.GlyphCache] 走回退链，两跳都空出方框
 * —— 原版 GDI+ 对「Font 可用但码位缺字」画的就是 .notdef 方框
 * （spec: `GlyphCache` 类文档缺字回退链一节）。
 *
 * ## 确定性
 *
 * 同入参必同输出：TTF 只载一次（路径键缓存）、`deriveFont` 是纯函数、渲染 hints 固定
 * （TEXT_ANTIALIAS_ON + FRACTIONALMETRICS_OFF）、基线/删除线/下划线用确定性公式。
 * 抗锯齿的**具体覆盖率**允许随 JVM/字体栈实现浮动（跨机不逐字节比），单测只断言结构
 * （advance、位图尺寸、非零墨）与两光栅器之间的不变量，不钉具体像素值。
 *
 * @param defaultFontName 回退链第二跳的默认字体名（`CellMetrics.DEFAULT_FONT_NAME` = "黑体"）
 * @param cellMetrics 默认字号的换算器（其余字号经 [metrics.cellMetricsFor] 取）
 * @param metrics 度量入口（§3 旁表 + 缺字分档都在里面）；**调用方必须与 Static 侧共享同一实例**
 * @param fontResources 家族名 → classpath TTF 资源路径
 * @param fallbackResource 请求名没有专属 TTF 时兜底用的资源（Saitamaar 子集）
 */
class AwtGlyphRaster(
    override val defaultFontName: String = CellMetrics.DEFAULT_FONT_NAME,
    val cellMetrics: CellMetrics = CellMetrics.DEFAULT,
    val metrics: TextMetrics = TextMetrics.gridOnly(cellMetrics),
    fontResources: Map<String, String> = DEFAULT_FONT_RESOURCES,
    val fallbackResource: String = FONT_SAITAMAAR,
) : GlyphRaster {

    private val fontByResource = fontResources.values.associateWith { resource -> loadOrNull(resource) }
        .let { base ->
            // fallback 也可能没被任何显式家族引用到，单独补载（同资源时 loadOrNull 幂等便宜）
            if (base.containsKey(fallbackResource)) base
            else base + (fallbackResource to loadOrNull(fallbackResource))
        }

    /** FontMetrics 用的光栅面（getFontMetrics 需要一个 Graphics，共享一份零分配）。 */
    private val metricsImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    /**
     * 查表光栅化。缺字（表缺行 / TTF 无字形 / TTF 载入失败）→ null，
     * 由 GlyphCache 走回退链（契约第 1 条：不许抛）。
     */
    override fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? {
        // 缺字判据第一道（与 Static 同判）：§3 表缺行 = 缺字。链上没表时 covers 恒真
        // （退化不等于否决，TextMetrics.covers 的口径），此时也不否决。
        if (!metrics.covers(codePoint, fontName)) {
            return null
        }
        val converter = metrics.cellMetricsFor(sizePx)
        // advance 逐式照抄 StaticGlyphRaster.rasterize:106-107 —— 布局不归 AWT 管的铁不变量。
        val advance = metrics.advancePixels(codePoint, fontName, sizePx)
            ?: missingGlyphAdvancePixels(codePoint, converter)
        val bitmapHeight = converter.lineHeight
        val bitmapWidth = if (advance > 0) advance else 0
        val font = fontByResource[resourceFor(fontName)] ?: return null
        if (!font.canDisplay(codePoint)) {
            return null
        }
        val style = fontStyleBits(styleMask)
        val sized = if (style == Font.PLAIN) font.deriveFont(sizePx.toFloat()) else font.deriveFont(style, sizePx.toFloat())
        val fm = metricsImage.graphics.run {
            val m = getFontMetrics(sized)
            dispose()
            m
        }
        // 基线夹进行高盒：像素字体的 ascent 理论上可能超出行高，夹住保证位图内不越界。
        val baseline = fm.ascent.coerceIn(0, bitmapHeight)
        val bitmap = if (bitmapWidth > 0) drawGlyph(sized, codePoint, styleMask, bitmapWidth, bitmapHeight, baseline) else IntArray(0)
        return RasterizedGlyph(
            advancePixels = advance,
            ascentPixels = baseline,
            descentPixels = bitmapHeight - baseline,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            bitmap = bitmap,
            lsbPixels = metrics.lsbPixels(codePoint, fontName, sizePx),
        )
    }

    /** 请求名 → TTF 资源：显式登记的家族直取；其余（含默认名、空名归一后的名字）走 Saitamaar 兜底。 */
    private fun resourceFor(fontName: String): String = DEFAULT_FONT_RESOURCES[fontName] ?: fallbackResource

    /**
     * 缺度量行时的 advance（像素）——逐式照抄 StaticGlyphRaster.missingGlyphAdvancePixels
     * （:148-153）：零宽档 0 步进、其余按 [CellMetrics.cellsOf] 的网格折算。
     * 两条光栅器必须同式，否则同一码位在换芯前后 advance 不同、布局漂移。
     */
    private fun missingGlyphAdvancePixels(codePoint: Int, converter: CellMetrics): Int {
        if (converter.fallbackClassOf(codePoint) == FallbackWidthClass.ZERO) {
            return 0
        }
        return converter.cellsToPixels(converter.cellsOf(codePoint))
    }

    /**
     * 把字形画进 advance × 行高的覆盖率掩码。
     *
     * 样式位映射（[GlyphRaster.rasterize] 契约：脚本位 ≠ GDI FontStyle 位）：
     * 粗/斜进 deriveFont；删除线（脚本位 4）与下划线（脚本位 8）手画线 —— GDI 那边
     * 4=Underline、8=Strikeout（GlyphRaster.kt:59-63 钉过的坑），位值直传必反。
     * 线位公式是确定性选择（`TODO(verify)`：无 PC 截图可比，真机验收时复核）。
     */
    private fun drawGlyph(
        font: Font,
        codePoint: Int,
        styleMask: Int,
        width: Int,
        height: Int,
        baseline: Int,
    ): IntArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            g2.font = font
            g2.color = INK_COLOR
            val text = String(Character.toChars(codePoint))
            g2.drawString(text, 0f, baseline.toFloat())
            val thickness = lineThickness(height)
            if (styleMask and STYLE_STRIKE != 0) {
                val y = (baseline - height / 4).coerceIn(0, height - thickness)
                g2.fillRect(0, y, width, thickness)
            }
            if (styleMask and STYLE_UNDERLINE != 0) {
                val y = (baseline + thickness).coerceIn(0, height - thickness)
                g2.fillRect(0, y, width, thickness)
            }
        } finally {
            g2.dispose()
        }
        val out = IntArray(width * height)
        image.getRGB(0, 0, width, height, out, 0, width)
        return out
    }

    /** 样式位 → AWT FontStyle（只吃粗/斜两位；删除线/下划线手画，见 [drawGlyph]）。 */
    private fun fontStyleBits(styleMask: Int): Int {
        var style = Font.PLAIN
        if (styleMask and STYLE_BOLD != 0) style = style or Font.BOLD
        if (styleMask and STYLE_ITALIC != 0) style = style or Font.ITALIC
        return style
    }

    private fun lineThickness(lineHeightPx: Int): Int = maxOf(1, lineHeightPx / 16)

    private fun loadOrNull(resource: String): Font? = try {
        val stream: InputStream? = javaClass.classLoader.getResourceAsStream(resource)
        stream?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    } catch (ignored: Exception) {
        // 字体不可用按「这一跳没有字形」处理（GlyphRaster 契约第 1 条，FontFactory.cs:52-54 口径）
        null
    }

    companion object {
        /** 脚本样式位（Grid.STYLE_* 同口径）：1=粗 2=斜 4=删除线 8=下划线。 */
        private const val STYLE_BOLD = 1
        private const val STYLE_ITALIC = 2
        private const val STYLE_STRIKE = 4
        private const val STYLE_UNDERLINE = 8

        /** 掩码墨色：RGB 白、alpha 由字形覆盖率填（着色在绘制侧，见类文档）。 */
        private val INK_COLOR = java.awt.Color.WHITE

        /** classpath 上 `:render` resources 的字体子集（c-fontsub 交付面，font-pack-manifest.txt）。 */
        const val FONT_SAITAMAAR = "fonts/saitamaar-subset.ttf"
        const val FONT_UNIFONT = "fonts/unifont-subset.ttf"
        const val FONT_FUSION = "fonts/fusion-pixel-12px-subset.ttf"
        const val FONT_ARK = "fonts/ark-pixel-12px-subset.ttf"

        /**
         * 家族名 → TTF。Saitamaar 是语料自带字体的子集
         * （subset-build-manifest.txt：source = 语料 font/Saitamaar.ttf），与度量链的
         * substituteFamily 同名；Unifont 是兜底覆盖面（99.48%，manifest [unifont]）。
         * misaki-gothic.ttf 没有随包 sidecar（不进度量链），不在此登记 ——
         * 登记了会让墨迹家族 ≠ advance 家族。
         */
        val DEFAULT_FONT_RESOURCES: Map<String, String> = mapOf(
            "Saitamaar" to FONT_SAITAMAAR,
            "Unifont" to FONT_UNIFONT,
            "Fusion Pixel 12px" to FONT_FUSION,
            "Ark Pixel 12px" to FONT_ARK,
        )
    }
}
