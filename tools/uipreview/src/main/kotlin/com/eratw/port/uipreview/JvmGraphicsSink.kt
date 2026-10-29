package com.eratw.port.uipreview

import com.eratw.port.core.host.GraphicsSink
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
 */
class JvmGraphicsSink(
    layer: GraphicsLayer,
    metrics: CellMetrics,
    scriptFontPixelSize: Int = 0,
) : GraphicsSink by GraphicsSinkBridge(layer, metrics, scriptFontPixelSize = scriptFontPixelSize)
