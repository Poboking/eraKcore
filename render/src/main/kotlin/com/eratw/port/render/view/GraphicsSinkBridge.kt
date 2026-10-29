package com.eratw.port.render.view

import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics

/**
 * 六条图形语句 → [GraphicsLayer] 的**共享桥**（UP1-WIRE-1）。
 *
 * 原实现有两份逐条重复的拷贝：Android 生产装配用的
 * `platform/.../image/AndroidGraphicsSink` 与桌面预览用的
 * `tools/uipreview/.../JvmGraphicsSink`。两份的算式、守卫与 C# 出处完全一致，
 * 唯一差别是「要不要通知宿主重绘」——本类把它收成一个可选回调 [onInvalidate]，
 * 两个模块各自只留一层薄壳。
 *
 * 放在 `render` 的依据（模块图）：`render` 已依赖 `core-vm`（拿 [GraphicsSink]），
 * 而 `platform` 与 `tools/uipreview` 都依赖 `render`；把实现放到 `core-vm` 会反向
 * 依赖 [GraphicsLayer]/[InlineGraphic] 形成环。
 *
 * ## 参数换算的出处
 *
 * - `PRINT_IMG name, nameb, namem, param`：**`param[0]` 是宽、`param[1]` 是高、
 *   `param[2]` 是 `ypos`**，原版按这个顺序喂 `PrintImg(name, nameb, namem, height, width, ypos)`
 *   （spec: `FunctionIdentifier.cs:427-439` —— 注意 C# 那边第 4 个实参取的是 `Param[1]`
 *   且形参名是 `height`，第 5 个取 `Param[0]` 形参名 `width`，与本类的读法逐值一致）；
 *   `nameb == ""` 时原版置 null（spec: `FunctionIdentifier.cs:423-426`）；
 *   尺寸与偏移的 px / 百分比口径见 spec: `ConsoleImagePart.cs:66-82`
 *   （百分比基数是 `Config.FontSize`，移植侧 = [CellMetrics.fontPixelSize]）；
 * - `PRINT_RECT`：1 参 ⇒ 高 = FontSize 的横条；4 参 ⇒ `x, y, w, h`；
 *   `x >= 0 && w > 0 && h > 0` 才成段，否则原版落 `ConsoleErrorShapePart`
 *   （spec: `ConsoleShapePart.cs:56-69`）；1 参形态要求 `num > 0`（`:57`）；
 *   填充色取语句层传来的控制台当前前景色（原版 `PrintShape(..., userStyle.Color, ...)`，
 *   spec: `EmueraConsole.cs:3197-3199`），alpha 恒不透明（见 [printRect] 覆写的 KDoc）；
 * - `PRINT_SPACE`：只有 1 参形态有效（spec: `ConsoleShapePart.cs:50-55`）；
 * - `SETBGIMAGE name[, depth[, opacity]]`：`opacity` 是 `0..255` 的整数、
 *   原版除 255 变浮点（spec: `FunctionIdentifier.cs:1529-1541`），图层按 depth **降序**
 *   烘进一张背景（spec: `EmueraConsole.cs:897-906` + `:936-957`）；
 * - `REMOVEBGIMAGE` / `CLEARBGIMAGE`：spec: `FunctionIdentifier.cs:1545-1570`。
 *
 * ## 线程
 *
 * 本类在 **VM 线程**被语句层调用（`StatementExecutor` 的执行线程）。
 * [GraphicsLayer] 的写入是无锁安全的；[onInvalidate] 会把「该重绘了」投回 UI 线程，
 * 宿主传进来的实现必须是 `View.postInvalidateOnAnimation()` 一类跨线程入口。
 *
 * @param layer 图形层状态（与 `ConsoleFeed` / `CanvasConsoleView` 必须同一个实例）
 * @param metrics 像素 ↔ 半角格换算器（内联段占宽与百分比折算的基数都取它，
 *                与绘制侧不同源就会错位）
 * @param scriptFontPixelSize **脚本 `px` 的基准字号**（= 引擎生效 `Config.FontSize`）；
 *                `px` 形态经 [CellMetrics.scriptPixels] 折成设备像素，与 HTML 侧同尺。
 *                缺省 0 = 不折算（恒等），headless 与既有用例逐值不变
 * @param onInvalidate 屏上要变了的通知（null = 不通知，桌面预览靠 `ConsoleFeed.pump`
 *                     检查 `GraphicsLayer.hasPendingInline()` 自己发现）
 */
class GraphicsSinkBridge(
    private val layer: GraphicsLayer,
    private val metrics: CellMetrics,
    private val scriptFontPixelSize: Int = 0,
    private val onInvalidate: (() -> Unit)? = null,
) : GraphicsSink {

    /** 半角步进（像素）：内联段占宽换算的唯一分母。 */
    private val halfCellPx: Float = metrics.cellPixelWidth

    override fun printImage(
        name: String,
        nameb: String?,
        namem: String?,
        params: List<Pair<Long, Boolean>>,
    ) {
        val fontSize = metrics.fontPixelSize
        val height = params.getOrNull(1)
        val width = params.firstOrNull()
        val ypos = params.getOrNull(2)
        // FunctionIdentifier.cs:423-426：nameb 空串按 null 处理；namem 保留原样
        //（空串在 ConsoleImagePart.cs:51,98 的判据里同样落不进 srcm/cImageM）。
        val button = nameb?.takeIf { it.isNotEmpty() }
        val mapping = namem?.takeIf { it.isNotEmpty() }
        // ConsoleImagePart.cs:66：height 缺省或 0 ⇒ 一行高（FontSize）
        val heightPx = if (height == null || height.first == 0L) fontSize else pixel(height, fontSize)
        val widthPx = if (width == null || width.first == 0L) {
            // ConsoleImagePart.cs:67-71：宽按基图纵横比 = DestBaseSize.Width * h / DestBaseSize.Height。
            // 这里拿不到基图（sink 侧不查表，查表在绘制侧），先按「见方」占位，
            // 真图由绘制侧按纵横比覆盖：TODO(verify) 缺宽时的占格数与实画宽可能差 1~2 格，
            // 卡点：`PRINT_IMG` 不带 `width` 的形态在语料里 0 处，没有可取证的对象。
            heightPx
        } else {
            pixel(width, fontSize)
        }
        val yOffsetPx = if (ypos == null) 0 else pixel(ypos, fontSize)
        layer.enqueue(
            InlineGraphic.Image(
                resource = name,
                widthPx = widthPx,
                heightPx = heightPx,
                yOffsetPx = yOffsetPx,
                widthCells = InlineGraphic.cellsFor(widthPx, halfCellPx),
                buttonResource = button,
                // 遮罩取色图（srcm）：不进绘制，供命中路径按坐标取色（spec:
                // ConsoleButtonString.cs:138-152 + ConsoleImagePart.cs:135-157，
                // 换算入口 InlineGraphic.maskSamplePoint）。本端消费点在
                // CanvasConsoleView.mappingColor，INPUT(6/3, color) 入队归装配侧接线。
                mappingResource = mapping,
                // sprite 查不到时原版把重建的 img 标签当文本画（spec: ConsoleImagePart.cs:45-59、
                // :60-64、:175-182）；文案在这里按原始 MixedNum 生成，绘制侧缺帧时回落。
                altText = InlineGraphic.imgAltText(name, button, mapping, height, width, ypos, fontSize),
            ),
        )
        invalidate()
    }

    /**
     * 不带颜色的旧入口：按出厂前景 `Config.ForeColor` 填充
     * （spec: `ConfigData.cs:68` 的 `Color.FromArgb(192,192,192)`）。
     * 语句层已改调带填充色的双参重载；保留本覆写让直接调旧入口的宿主行为不变。
     */
    override fun printRect(params: List<Pair<Long, Boolean>>) {
        printRect(params, DEFAULT_RECT_COLOR)
    }

    /**
     * PRINT_RECT 带填充色。[rgb] = `0xRRGGBB`（`ConsoleSink.foregroundColor` 的表示；
     * 未设色时语句层已回落 [DEFAULT_RECT_COLOR]）。
     *
     * 原版把执行时点的 `userStyle.Color` 直接冻进形状段
     * （spec: `EmueraConsole.cs:3197-3199` → `ConsoleShapePart.cs:76`），
     * `ConsoleRectangleShapePart` 用 `SolidBrush(color)` 实心填充
     * （spec: `ConsoleRectangleShapePart.cs:46`）。
     *
     * **alpha 恒不透明**：`SETCOLOR` 只接受 1 个 RGB 或 3 个分量
     * （spec: `ArgumentParser.cs:1911-1955`），落点是三实参 `Color.FromArgb(r, g, b)`
     * （spec: `Process.cs:1436`，无 alpha 实参 ⇒ A=255）；`SETCOLORBYNAME` 还拒绝
     * A==0 的名字（spec: `Process.cs:1444`）；`Config.ForeColor` 同为三实参
     * （spec: `ConfigData.cs:68`）。故这里按 [opaque] 补 0xFF，不把 [rgb] 高位当 alpha。
     */
    override fun printRect(params: List<Pair<Long, Boolean>>, rgb: Int) {
        val fontSize = metrics.fontPixelSize
        val graphic = when {
            params.size == 1 -> {
                val w = pixel(params[0], fontSize)
                if (params[0].first <= 0L) return // ConsoleShapePart.cs:57 要求 num > 0
                InlineGraphic.Rect(
                    xOffsetPx = 0, yOffsetPx = 0, widthPx = w, heightPx = fontSize,
                    colorArgb = opaque(rgb), widthCells = InlineGraphic.cellsFor(w, halfCellPx),
                )
            }
            params.size == 4 -> {
                // 原版的 4 参分支全程走**浮点** MixedNum.ToPixelf（spec: ConsoleShapePart.cs:63，
                // 实现 Utils.cs:60-71：百分比 = (float)(num*FontSize)/100f，除法不再是整除），
                // 守卫 `x >= 0f && w > 0f && h > 0f`（:64）也按浮点判 —— 0<w<1 的百分比
                // 原版会建成段，只是取整后不可见；本端口整即丢，仅在高度上复刻
                // ConsoleRectangleShapePart.cs:30-33 的「(int)高 == 0 且原值 ≥ 0.001 ⇒ 补 1」。
                val x = toPixelf(params[0], fontSize)
                val y = toPixelf(params[1], fontSize)
                val w = toPixelf(params[2], fontSize)
                val h = toPixelf(params[3], fontSize)
                if (x < 0f || w <= 0f || h <= 0f) return // ConsoleShapePart.cs:64 的守卫
                val xi = x.toInt()
                val heightPx = h.toInt().let { if (it == 0 && h >= 0.001f) 1 else it }
                // ConsoleRectangleShapePart.cs:27,50-56：WidthF = X+W，整段宽 = (int)(X+W) - (int)X
                val widthPx = (x + w).toInt() - xi
                InlineGraphic.Rect(
                    xOffsetPx = xi, yOffsetPx = y.toInt(), widthPx = widthPx, heightPx = heightPx,
                    colorArgb = opaque(rgb), widthCells = InlineGraphic.cellsFor(widthPx, halfCellPx),
                )
            }
            else -> return // 原版这里落 ConsoleErrorShapePart（画 AltText），本波不复刻错误形态
        }
        layer.enqueue(graphic)
        invalidate()
    }

    override fun printSpace(params: List<Pair<Long, Boolean>>) {
        if (params.size != 1) return // ConsoleShapePart.cs:51 的形态守卫
        val p = params[0]
        val px = pixel(p, metrics.fontPixelSize)
        layer.enqueue(InlineGraphic.Space(InlineGraphic.cellsFor(px, halfCellPx)))
        invalidate()
    }

    override fun setBgImage(name: String, depth: Long?, opacity: Long?) {
        // FunctionIdentifier.cs:1530 与 :1532：depth 缺省 0L，opacity 缺省 1f（= 255/255 全不透明）
        layer.addBackground(
            GraphicsLayer.BgPlacement(
                resource = name,
                depth = (depth ?: 0L).coerceToInt(),
                opacity = (opacity ?: 255L).coerceInt(0, 255),
            ),
        )
        invalidate()
    }

    override fun removeBgImage(name: String) {
        layer.removeBackground(name)
        invalidate()
    }

    override fun clearBgImages() {
        layer.clearBackground()
        invalidate()
    }

    /** `Utils.MixedNum` → 像素：带 `px` 折成设备像素，否则按 `FontSize * num / 100` 折算。 */
    private fun pixel(value: Pair<Long, Boolean>, fontSize: Int): Int {
        val raw = if (value.second) scriptPx(value.first.coerceToInt()).toLong()
        else value.first * fontSize / 100
        return raw.coerceInt(Int.MIN_VALUE, Int.MAX_VALUE)
    }

    /** `Utils.MixedNum.ToPixelf`（Utils.cs:60-71）：百分比是**浮点**折算，不整除；`px` 先折设备像素。 */
    private fun toPixelf(value: Pair<Long, Boolean>, fontSize: Int): Float =
        if (value.second) scriptPx(value.first.coerceToInt()).toFloat() else value.first * fontSize / 100f

    /**
     * 脚本 `px` → 设备像素（与 `adapter/HtmlRunFlatten` 同一把尺，口径见
     * [CellMetrics.scriptPixels]）：`PRINTIMG` 的宽高、`PRECT`/`PSPACE` 的 `px` 形态都要走它，
     * 否则同一张图「用语句打」和「用 HTML 打」差一个基准字号倍，HTML 侧的图层还会与
     * 语句侧的色块错位。
     */
    private fun scriptPx(pixels: Int): Int = metrics.scriptPixels(pixels, scriptFontPixelSize)

    private fun invalidate() {
        onInvalidate?.invoke()
    }

    private fun Long.coerceToInt(): Int = if (this > Int.MAX_VALUE) Int.MAX_VALUE else if (this < Int.MIN_VALUE) Int.MIN_VALUE else toInt()

    private fun Long.coerceInt(min: Int, max: Int): Int =
        coerceAtLeast(min.toLong()).coerceAtMost(max.toLong()).toInt()

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    companion object {
        /**
         * `PRINT_RECT` 的出厂填充色 = `Config.ForeColor`（192,192,192，
         * spec: `ConfigData.cs:68`；`userStyle.Color` 的初值即它，spec: `EmueraConsole.cs:208`）。
         *
         * 当前前景色由语句层随 [printRect] 的带色重载传下来（原版 `PrintShape` 取
         * `userStyle.Color`，spec: `EmueraConsole.cs:3197-3199`）；本常量只剩两个用途：
         * 单参旧入口的填充色，以及语句层在 `ConsoleSink.foregroundColor == null`
         * （未 SETCOLOR）时的同值回落字面量（sem 在 core 层，取不到本类常量）。
         */
        const val DEFAULT_RECT_COLOR = 0xC0C0C0
    }
}
