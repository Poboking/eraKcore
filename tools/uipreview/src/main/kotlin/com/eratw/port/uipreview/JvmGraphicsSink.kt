package com.eratw.port.uipreview

import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.GraphicsSinkBridge

/**
 * 桌面预览用的 [GraphicsSink] 壳（UP1-WIRE-1 之后与 Android 共用一份实现）。
 *
 * 语义、算式与 C# 出处全部在 [GraphicsSinkBridge]；本类不传 `onInvalidate`
 * —— 预览侧由 `ConsoleFeed.pump` 检查 `GraphicsLayer.hasPendingInline()` 自己发现
 * 图形变化，不需要额外重绘通知。
 *
 * 保留本类名是因为 `PreviewRenderer` 与既有 KDoc 都按这个名字引用；
 * 与 `platform/.../image/AndroidGraphicsSink` 不再有第二份算式。
 *
 * **本波图片/色块不出墨**（UP2）：composer 对图形段（`cmd.glyphs` 为空）本就不画
 * 任何像素，图形只占布局格（`widthCells`），画「结构先对、墨迹后补」。
 *
 * @param scriptFontPixelSize 脚本 `px` 的基准字号（= 会话生效 `Config.FontSize`）：预览要复现
 *   真机版面就必须与 `ConsoleFeed` 喂同一个值，否则 `<img>` 用语句打与用 HTML 打的尺寸分叉。
 *   0（缺省）= 不折算。
 * @param sizeSource 图元尺寸源（[HtmlRunFlatten.ImageSizeSource]，生产形状 = [JvmImageFrames]）：
 *   `PRINT_IMG` 缺宽按纵横比推宽、图元 miss 按 AltText 量宽（`ConsoleImagePart.cs:60-71`）。
 *   预览喂的是与 [JvmImageFrames] 同一份表；null（缺省）= 判不了图元在不在，保持见方占位。
 */
class JvmGraphicsSink(
    layer: GraphicsLayer,
    metrics: CellMetrics,
    scriptFontPixelSize: Int = 0,
    sizeSource: HtmlRunFlatten.ImageSizeSource? = null,
) : GraphicsSink by GraphicsSinkBridge(
    layer,
    metrics,
    scriptFontPixelSize = scriptFontPixelSize,
    sizeSource = sizeSource,
)
