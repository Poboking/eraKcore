package com.eratw.port.uipreview

import com.eratw.port.render.GlyphKey
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.GlyphCache
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicGeometry
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.ScreenComposer
import com.eratw.port.render.view.ScreenScene

/**
 * 帧后处理真墨迹绘制器（CONTRACT-UP2 §4.3）：把 [com.eratw.port.render.view.ScreenComposer]
 * 的占位墨换成真字形，把图形段（IMAGE/SHAPE，compose 侧零墨）铺上真像素，把
 * [GraphicsLayer] 的背景层垫到文字下。
 *
 * ## 为什么要「工具侧补偿」（需组长接线的根）
 *
 * render 的两条既有画法都到不了「真墨」：
 * 1. `ScreenComposer.drawRun`（ScreenComposer.kt:333-366）把每个字形铺成单元格内缩的
 *    **实心色块**（占位画法），从不消费 [GlyphEntry] 位图；类文档把「字形缓存位图逐块
 *    blit」列为真机路线 —— render 侧目前没有这条绘制口；
 * 2. 图形段（`cmd.glyphs` 为空）compose 时一个像素都不画（UP1 的「占宽空块」口径）。
 * 本波不许改 render 文件 ⇒ 墨迹在 `ConsoleFrame.draw` 产出画布之后由本类补：
 * 每个发布帧**全量重绘**（`ConsoleFrame.invalidate()` 由装配侧调；桌面工具性能非目标，
 * 且背景/立绘路径 render 本就强制全量），然后三趟后处理。
 *
 * ## 三趟的次序是硬约束
 *
 * 1. **擦占位**：文本段的占位块条带填回背景色 —— 条带必须与 drawRun 的铺块区域逐像素
 *    覆盖（`[cellsToPixels(colStart), cellsToPixels(colEnd)) × [top+INSET, top+lineHeight-INSET)`，
 *    块间缝隙与右沿 gap 都在条带内）；
 * 2. **垫背景**：背景层等比适配 + 水平居中 + 顶对齐（原版 `EmueraConsole.cs:947-955`
 *    的烘焙画法）整幅 blend —— 必须在擦之后，否则擦动作会把背景图在文本条带内擦掉；
 *    不走 `ScreenScene.sprites` 的 compose 侧垫图位，原因：(a) `drawSprite`
 *    （ScreenComposer.kt:319-323）只会 1:1 平移、没有等比缩放；(b) compose 侧垫好的
 *    背景会被第 1 趟的擦动作在文本条带内破坏。`TODO(verify)`：contain 适配的取舍
 *    （整图缩进画布 vs 原版烘焙是否裁切）需 PC 实机截图对拍；
 * 3. **铺墨**：逐 [DrawCmd] —— 文本段走字形位图、Rect 段填色/渐变、Image 段缩放贴图
 *    或 AltText 降级；这一趟内部再分两小趟（先「不出本行」的、后越出本行的图形段），
 *    与真机 `CanvasConsoleView.drawScreen` 整屏路同序，判式共用
 *    [GraphicGeometry.escapesLine]（理由与原版行号见 [paint] 的第 3 步注释）。
 *
 * ## 着色通道
 *
 * 字形位图按 **alpha = 覆盖率** 解释：`blend(fore | alpha)`，AwtGlyphRaster 的 AA 掩码与
 * StaticGlyphRaster 的实心像素（alpha 恒 FF）走同一条路 —— 换光栅器不改绘制侧，
 * 缺字方框（`GlyphCache.notdef`）同通道落在前景色（原版 .notdef 用当前字色画）。
 *
 * ## 不做的
 *
 * - 焦点/按下态换色：工具没有指针事件注入绘制（`frame.draw` 的 `pointing` 恒 null），
 *   文本墨一律用 `cmd.fore`（`ScreenComposer.glyphFore` 的非焦点分支）；
 * - 行背景（`cmd.back`）：原版默认配置不画逐段背景（`ScreenComposer` 类文档三层顺序 3），
 *   擦占位统一用 [ScreenScene.DEFAULT_BACKGROUND]（与 compose 的整屏底色同值）。
 *
 * 线程：只在泵线程调（GlyphCache 非线程安全，归泵线程独占，ADR-003 消费侧独占同款）。
 */
class InkPainter(
    private val composer: ScreenComposer,
    private val metrics: CellMetrics,
    private val glyphCache: GlyphCache,
    private val images: JvmImageFrames,
) {

    /**
     * 后处理一帧画布。`bgLayers` = [GraphicsLayer.background] 的当前快照（depth 降序，
     * 绘制按列表序 = 烘焙序，遮挡次序的 TODO(verify) 见 GraphicsLayer.addBackground）。
     */
    fun paint(canvas: Raster, screen: ConsoleScreen, bgLayers: List<GraphicsLayer.BgPlacement>) {
        val commands = screen.list.render()
        val rowCount = screen.visibleRows().size
        // 1) 擦占位（只擦有字形的段；图形段 compose 侧本就没铺墨）
        for (cmd in commands) {
            if (cmd.graphic == null && cmd.glyphs.isNotEmpty()) {
                erasePlaceholder(canvas, cmd, rowCount)
            }
        }
        // 2) 垫背景层
        for (layer in bgLayers) {
            paintBackgroundLayer(canvas, layer)
        }
        // 3) 铺墨（**两趟**，与真机 `CanvasConsoleView.drawScreen` 的整屏路同一个次序）：
        // 第一趟铺「不出自己那一行」的命令，第二趟单独铺越出本行的图形段 —— 判式只有
        // 一份，在 [GraphicGeometry.escapesLine]（原版 `ConsoleButtonString.cs:165-185`
        // 的 `FilterEscaped`，判式 `:171`；绘制次序出处 `EmueraConsole.cs:1850-1857` 画完行、
        // `:1858-1872` 再铺溢出部件）。单趟铺到底会让立绘/动图后面的字盖在图上（缺陷 B）。
        // 趟内保持 commands 原有的行升序 ⇒ 同行内列序不变；不新建列表。
        for (cmd in commands) {
            if (escapes(cmd)) continue
            drawInk(canvas, cmd, rowCount)
        }
        for (cmd in commands) {
            if (!escapes(cmd)) continue
            drawInk(canvas, cmd, rowCount)
        }
    }

    /**
     * 本条命令的图形段是否越出自己那一行（判式本体见 [GraphicGeometry.escapesLine]，
     * 与真机铺墨次序同源，本工具**不另算一份**）。文本段恒 false —— 原版文本件
     * `Top = 0`、`Bottom = Config.FontSize`（`AConsoleDisplayNode.cs:29,31`）。
     */
    private fun escapes(cmd: DrawCmd): Boolean {
        val g = cmd.graphic ?: return false
        return GraphicGeometry.escapesLine(g, metrics.fontPixelSize, metrics.lineHeight.toFloat())
    }

    /** 占位块条带 → 背景色（区域口径见类文档「三趟的次序」第 1 趟）。 */
    private fun erasePlaceholder(canvas: Raster, cmd: DrawCmd, rowCount: Int) {
        val boxHeight = metrics.lineHeight - 2 * ScreenComposer.GLYPH_INSET_PX
        if (boxHeight <= 0) {
            return
        }
        val top = composer.rowTopPx(cmd.row, rowCount)
        val x0 = metrics.cellsToPixels(cmd.colStart)
        val x1 = metrics.cellsToPixels(cmd.colEnd)
        if (x1 <= x0) {
            return
        }
        canvas.fillRect(x0, top + ScreenComposer.GLYPH_INSET_PX, x1 - x0, boxHeight, ScreenScene.DEFAULT_BACKGROUND)
    }

    /**
     * 一条背景层：等比适配（contain）+ 水平居中 + 顶对齐，opacity 预乘 alpha
     * （原版 `FunctionIdentifier.cs:1539` 的 num/255f；GraphicsLayer.BgPlacement.opacity 0..255）。
     */
    private fun paintBackgroundLayer(canvas: Raster, layer: GraphicsLayer.BgPlacement) {
        val frame = images.frame(layer.resource) ?: return
        val decoded = frame.handle as? DecodedPixels ?: return
        if (frame.srcW <= 0 || frame.srcH <= 0 || canvas.width <= 0 || canvas.height <= 0) {
            return
        }
        val scale = minOf(
            canvas.width.toFloat() / frame.srcW,
            canvas.height.toFloat() / frame.srcH,
        )
        val destW = maxOf(1, (frame.srcW * scale).toInt())
        val destH = maxOf(1, (frame.srcH * scale).toInt())
        val originX = (canvas.width - destW) / 2
        val originY = 0
        val opacity = layer.opacity.coerceIn(0, 255)
        var dy = 0
        while (dy < destH) {
            val sy = frame.srcY + dy * frame.srcH / destH
            var dx = 0
            while (dx < destW) {
                val sx = frame.srcX + dx * frame.srcW / destW
                // anime 帧的 src 矩形在帧登记侧未经夹取（spriteFrame 只夹静态分支），这里守边界。
                if (sx in 0 until decoded.width && sy in 0 until decoded.height) {
                    val pixel = decoded.pixels[sy * decoded.width + sx]
                    val a = ((pixel ushr 24) * opacity / 255)
                    if (a > 0) {
                        canvas.blend(originX + dx, originY + dy, (a shl 24) or (pixel and 0xFFFFFF))
                    }
                }
                dx++
            }
            dy++
        }
    }

    /** 逐段铺墨（文本 / Rect / Image 三路，见类文档第 3 趟）。 */
    private fun drawInk(canvas: Raster, cmd: DrawCmd, rowCount: Int) {
        val graphic = cmd.graphic
        if (graphic == null) {
            drawGlyphRun(canvas, cmd, rowCount, cmd.glyphs, originX = metrics.cellsToPixels(cmd.colStart))
            return
        }
        when (graphic) {
            is InlineGraphic.Rect -> drawRectInk(canvas, cmd, graphic, rowCount)
            is InlineGraphic.Image -> drawImageInk(canvas, cmd, graphic, rowCount)
            is InlineGraphic.Space -> return
        }
    }

    /**
     * 文本墨：从 [originX] 起、基线 = 行顶 + `entry.ascentPixels`，逐字形 blend；
     * 推进用 [GlyphEntry.advancePixels] —— 位图拼接的逐字口径（`GlyphCache.measurePixels`
     * 的 KDoc：位图拼接只能按它来），与 DisplayList 的格口径是两条并存线。
     */
    private fun drawGlyphRun(canvas: Raster, cmd: DrawCmd, rowCount: Int, glyphs: List<GlyphKey>, originX: Int) {
        val top = composer.rowTopPx(cmd.row, rowCount)
        val fore = cmd.fore and 0xFFFFFF
        var pen = originX
        for (key in glyphs) {
            val entry = glyphCache.get(key)
            val inkX = pen + entry.lsbPixels
            val bitmap = entry.bitmap
            var i = 0
            while (i < bitmap.size) {
                val a = bitmap[i] ushr 24
                if (a > 0) {
                    canvas.blend(
                        inkX + i % entry.bitmapWidth,
                        top + i / entry.bitmapWidth,
                        (a shl 24) or fore,
                    )
                }
                i++
            }
            pen += entry.advancePixels
        }
    }

    /** 色块：纯色或停点渐变（列向线性采样，`DrawCmd` 文档的格布局语义不动）。 */
    private fun drawRectInk(canvas: Raster, cmd: DrawCmd, rect: InlineGraphic.Rect, rowCount: Int) {
        val top = composer.rowTopPx(cmd.row, rowCount)
        val x = metrics.cellsToPixels(cmd.colStart) + rect.xOffsetPx
        val y = top + rect.yOffsetPx
        val stops = rect.gradientArgb
        if (stops != null && stops.isNotEmpty()) {
            var col = 0
            while (col < rect.widthPx) {
                val pos = if (rect.widthPx <= 1 || stops.size == 1) 0 else col * (stops.size - 1) / (rect.widthPx - 1)
                canvas.fillRect(x + col, y, 1, rect.heightPx, opaque(stops[pos]))
                col++
            }
            return
        }
        canvas.fillRect(x, y, rect.widthPx, rect.heightPx, opaque(rect.colorArgb))
    }

    /**
     * 图片：按源子矩形最近邻缩放到段盒 `(colStart, top+yOffset, widthPx, heightPx)`；
     * 帧取不到按原版口径画 [InlineGraphic.Image.altText]（ConsoleImagePart.cs:175-182），
     * altText 也没有保持空块。AltText 的字形键用空字体名（归一到默认族）+ 样式 0 ——
     * [DrawCmd] 不携带字体名，原版画 alt 串用的是该节点的 Font，此处不可考
     * （`TODO(verify)`：真机对拍 alt 串的字体）。
     */
    private fun drawImageInk(canvas: Raster, cmd: DrawCmd, image: InlineGraphic.Image, rowCount: Int) {
        val top = composer.rowTopPx(cmd.row, rowCount)
        // GFX-09 口径：工具无指针事件，恒非按下态 → 取主图。
        val frame = images.frame(image.drawResource(pressed = false))
        if (frame != null && frame.usable && image.widthPx > 0 && image.heightPx > 0) {
            val decoded = frame.handle as? DecodedPixels ?: return
            var dy = 0
            while (dy < image.heightPx) {
                val sy = frame.srcY + dy * frame.srcH / image.heightPx
                var dx = 0
                while (dx < image.widthPx) {
                    val sx = frame.srcX + dx * frame.srcW / image.widthPx
                    // 同 paintBackgroundLayer：anime 帧的 src 矩形未在登记侧夹取，守边界。
                    if (sx in 0 until decoded.width && sy in 0 until decoded.height) {
                        val pixel = decoded.pixels[sy * decoded.width + sx]
                        canvas.blend(
                            metrics.cellsToPixels(cmd.colStart) + dx,
                            top + image.yOffsetPx + dy,
                            pixel,
                        )
                    }
                    dx++
                }
                dy++
            }
            return
        }
        val alt = image.altText ?: return
        val glyphs = ArrayList<GlyphKey>(alt.length)
        var i = 0
        while (i < alt.length) {
            val codePoint = alt.codePointAt(i)
            i += Character.charCount(codePoint)
            glyphs.add(GlyphKey(ALT_FONT_NAME, 0, codePoint, metrics.fontPixelSize))
        }
        drawGlyphRun(canvas, cmd, rowCount, glyphs, originX = metrics.cellsToPixels(cmd.colStart))
    }

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    private companion object {
        /** AltText 降级串的字体名：空 = 归一到默认族（CellMetrics.DEFAULT_FONT_NAME），见 [drawImageInk]。 */
        const val ALT_FONT_NAME = ""
    }
}
