package com.eratw.port.render.sprite

import kotlin.math.abs

/**
 * ARGB 软件合成光栅面（契约 CONTRACT-M4-B §2 m-sprite）。
 *
 * 原版全部走 GDI+（`System.Drawing.Bitmap` + `Graphics`，
 * 参考 `MinorShift.Emuera.UI.Game.Image/GraphicsImage.cs:1-52`），ADR-001 已定调
 * 「GDI+ 全量重绘不照抄」：本类用 `IntArray`（`0xAARRGGBB`，与 `Color.ToArgb()` 同构）
 * 做纯 JVM 软件合成，像素级差异在重设计允许范围内（TODO(verify)：真机 Android 侧
 * 截图验收时记录偏差，见 golden-runbook §6 的截图级说明）。
 *
 * 绘制入口一律**静默裁剪**越界（原版 `Graphics` 自带裁剪区域，画在窗外不报错）；
 * 读像素的 [pixelAt] 不裁剪（对应原版 `Bitmap.GetPixel` 越界抛异常）。
 *
 * @property width 像素宽（>=0；0 宽高合法，所有绘制退化为无操作）
 * @property height 像素高（>=0）
 */
class Raster(val width: Int, val height: Int) {

    init {
        require(width >= 0 && height >= 0) { "Raster 尺寸不能为负：${width}x${height}" }
    }

    /** 像素，行主序 `0xAARRGGBB`，初始全 0（透明黑）。 */
    val pixels: IntArray = IntArray(width * height)

    /**
     * 读像素；越界抛 [IndexOutOfBoundsException]（`Bitmap.GetPixel` 同口径：原版 `GGETCOLOR`
     * 判完空位图就直通 `Bitmap.GetPixel`（`GraphicsImage.cs:502-509`，直通在 `:508`，
     * `Bitmap == null` 时先抛 `NullReferenceException` `:504-506`），判界在 GDI+ 侧、
     * 反编译里没有那一行，异常类型属框架行为、不逐字对齐）。
     *
     * 判界必须**显式**做、不能靠 `pixels[y * width + x]` 的数组检查：行主序下标会让
     * `x == width`（落进下一行行首）与 `x == -1`（落进上一行行尾）都算合法下标，
     * 于是越界读静默返回**另一个像素**（连 alpha 一起读错）而不是抛错 —— 这正是调用方
     * 最该收到异常的两侧。绘制侧不受影响（[blend]/[fillRect]/[blit] 各自静默裁剪，
     * 对应 GDI+ 的裁剪区域）。
     *
     * **别用它接精灵侧读色**：原版 `SPRITEGETCOLOR` 走 `ASpriteSingle.SpriteGetColor`
     * （`ASpriteSingle.cs:50-64`）—— 先加 `SrcRectangle` 偏移（`:57-58`）再显式判界，
     * 越界**返回 `Color.Transparent`、不抛**（`:59-62`），位图为 null 同样返回透明
     * （`:52-56`；null 位图的来源是 `ASpriteSingle.cs:12-22` 的 getter）。
     * 基准 `pc-golden-root.txt:671` 的 `SPRITEGETCOLOR=0` 就是那条透明读数。
     */
    fun pixelAt(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw IndexOutOfBoundsException("像素坐标 ($x, $y) 越界：画布 ${width}x$height")
        }
        return pixels[y * width + x]
    }

    /**
     * 单像素 source-over 合成：`out = src*a + dst*(1-a)`（整数近似，除以 255 舍去）。
     *
     * 与 core-vm `GraphicsBuffer.blendPixel`（`core/host/GraphicsBuffer.kt:272-287`）
     * 同一公式，保证函数层返回值与渲染层像素自洽；`A=0` 不改目标像素、`A=255` 直接覆盖。
     * 原版由 GDI+ 内部完成，无逐位公式可抄 —— 本式是重设计选择而非复刻对象。
     */
    fun blend(x: Int, y: Int, argb: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val a = argb ushr 24
        if (a == 0) return
        val idx = y * width + x
        if (a == 255) {
            pixels[idx] = argb
            return
        }
        val d = pixels[idx]
        val ia = 255 - a
        val r = ((((argb shr 16) and 0xFF) * a + ((d shr 16) and 0xFF) * ia) / 255) and 0xFF
        val g = ((((argb shr 8) and 0xFF) * a + ((d shr 8) and 0xFF) * ia) / 255) and 0xFF
        val b = (((argb and 0xFF) * a + (d and 0xFF) * ia) / 255) and 0xFF
        val oa = (a + ((d ushr 24) * ia / 255)) and 0xFF
        pixels[idx] = (oa shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 把 [src] 的 `(srcX,srcY,srcW,srcH)` 区域逐像素 source-over 贴到本面的
     * `(dstX,dstY)` 起处；源区与目标区**双向裁剪**（越界部分丢弃，完全不含则无操作）。
     *
     * 源区先快照，允许 `src === this` 的自贴图（对应原版 `GDrawG` 允许目标缓冲即源缓冲，
     * 基准 `pc-golden-root.txt:652` 的 `GDRAWG(10,...)` 自贴返回 1；快照口径与
     * core-vm `GraphicsBuffer.blitFrom`（`core/host/GraphicsBuffer.kt:186-198`）一致）。
     * 落位映射与原版 GDI+ 一致：`目标位置 = (dstX,dstY) + (源像素坐标 - (srcX,srcY))`
     * （spec: `GraphicsImage.cs:306-315`、`SpriteAnime.cs:144`、`ASpriteSingle.cs:74`）；
     * 裁剪只丢弃矩形与两侧光栅面求交后落在外面的像素，**不搬移源矩形原点**
     * （spec: `AppContents.cs:313-325` 仅做相交测试、不相交整块丢弃；
     * `SpriteAnime.cs:24-35` 的 `Normalize` 只裁目标矩形）。
     * 1:1 平移拷贝；缩放采样不在本波范围（需缩放时用 [blend] 自行铺点，或写进「需组长接线」）。
     */
    fun blit(
        dstX: Int,
        dstY: Int,
        src: Raster,
        srcX: Int = 0,
        srcY: Int = 0,
        srcW: Int = src.width,
        srcH: Int = src.height,
    ) {
        val sx0 = maxOf(0, srcX)
        val sy0 = maxOf(0, srcY)
        val sx1 = minOf(src.width, srcX + srcW)
        val sy1 = minOf(src.height, srcY + srcH)
        if (sx0 >= sx1 || sy0 >= sy1) return
        val dx0 = dstX + (sx0 - srcX)
        val dy0 = dstY + (sy0 - srcY)
        val cx0 = maxOf(0, dx0)
        val cy0 = maxOf(0, dy0)
        val cx1 = minOf(width, dx0 + (sx1 - sx0))
        val cy1 = minOf(height, dy0 + (sy1 - sy0))
        if (cx0 >= cx1 || cy0 >= cy1) return
        val w = cx1 - cx0
        val h = cy1 - cy0
        val tmp = IntArray(w * h)
        for (j in 0 until h) {
            val sy = sy0 + (cy0 + j - dy0)
            val sRow = sy * src.width
            for (i in 0 until w) tmp[j * w + i] = src.pixels[sRow + sx0 + (cx0 + i - dx0)]
        }
        for (j in 0 until h) {
            for (i in 0 until w) blend(cx0 + i, cy0 + j, tmp[j * w + i])
        }
    }

    /**
     * 实心矩形（GDI+ `Graphics.FillRectangle` + SolidBrush 的合成语义）：
     * 每像素走 [blend]，`A=255` 时等价覆盖、`A=0` 时无操作。
     * 宽高 <=0 或裁剪后为空区域时无操作。
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        val x1 = minOf(width, x + w)
        val y1 = minOf(height, y + h)
        if (x0 >= x1 || y0 >= y1) return
        val opaque = (argb ushr 24) == 255
        for (ty in y0 until y1) {
            val row = ty * width
            if (opaque) {
                pixels.fill(argb, row + x0, row + x1)
            } else {
                for (tx in x0 until x1) blend(tx, ty, argb)
            }
        }
    }

    /**
     * 1px 直线（Bresenham 整数画线，端点闭区间）。
     *
     * 原版按 `Pen.Width` 描边（GDI+ 抗锯齿，`Graphics.DrawLine`），本波按契约的
     * 重设计口径取 1px 整数线；逐点走 [blend]，线在面外部分静默裁剪。
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            blend(x, y, argb)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }
}
