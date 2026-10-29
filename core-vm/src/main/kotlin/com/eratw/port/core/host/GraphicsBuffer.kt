package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 图形缓冲 / Sprite / CBG 的软件实现（纯 JVM，无 Android / AWT 依赖）。
 *
 * 对应原版 `MinorShift.Emuera.UI.Game.Image` 包：
 * - [GraphicsBuffer]  ≈ `GraphicsImage`（`Bitmap` + `Graphics` + 画笔/画刷/字体状态），
 *   像素是 ARGB IntArray，替代 `System.Drawing.Bitmap` 的 `Format32bppArgb`。
 * - [GraphicsPool]    ≈ `AppContents.gList`（`ConcurrentDictionary<int, GraphicsImage>`）。
 * - [SpritePool]      ≈ `AppContents.imageDictionary` + `resourceImageDictionary`。
 * - [CbgPool]         ≈ `EmueraConsole.cbgList` / `cbgButtonMap`（背景图与按钮图层）。
 *
 * 像素布局：`0xAARRGGBB`（与 `Color.ToArgb()` 同构）。原版查询颜色时做
 * `ToArgb() & 0xFFFFFFFF`（FunctionMethodCreator.cs:6189,6191,6255），即把 Int 当无符号 32 位；
 * 函数层用 [unsignedArgb] 完成该转换。
 *
 * 绘制保证「返回码 + 状态自洽」，并对真正落进缓冲的绘制（`GDRAWG` / `GDRAWSPRITE` 的
 * 贴图合成，总账 GFX-01；`GDRAWTEXT` 的替代字形，总账 GFX-08）给出可断言的像素；
 * 不做抗锯齿 / 字体栅格化（契约 §4 A4）：`GDRAWTEXT` 落的是「推进盒」而不是字形轮廓，
 * 口径与接线项见 `core/fn/GraphicsMethods.kt` 里 GDRAWTEXT 的 KDoc。
 */

/** ARGB 颜色转无符号 32 位（原版 `ToArgb() & 0xFFFFFFFF`）。 */
fun unsignedArgb(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

/**
 * 宿主侧**二进制写**能力（GSAVE 落真 PNG 的通道）。
 *
 * 为什么不是 [FileOps] 的成员：[FileOps] 只声明文本读写（`readText` / `writeText`），
 * 写出来的字节恒是 UTF-8 正文（`RootedFileOps.writeText`，`core/proc/BootHosts.kt`），
 * PNG 是二进制容器，经文本通道必然损坏。二进制**读**侧早有独立通道
 * （`BuiltinHost.readFileBytes`），本接口补的是对称的写侧。
 *
 * ## 接线口径
 *
 * 由宿主的 [FileOps] 实现**顺带实现**本接口（`as?` 判定，不做强制）：
 * - `RootedFileOps`：按它自己的 `resolvePath` 落在注入根内、建父目录、`Files.write`；
 * - `WriteBlockedFileOps`（`core/proc/ConsoleBuiltinHost.kt`）：写闸关着时返回 false，
 *   开着时转发给可写根的 `RootedFileOps` —— 与 `writeText` 同一条闸，不另开旁路。
 *
 * 未实现本接口时 GSAVE 回落旧文本载体（[GraphicsBuffer.toSaveText]），
 * 见 `fn/GraphicsMethods.kt` 的 GSAVE 段。
 */
interface BinaryFileOps {
    /**
     * 覆盖写 [bytes]；返回 false = 失败（原版 `Bitmap.Save` 抛异常 ⇒ GSAVE 返回 0，
     * FunctionMethodCreator.cs:8127-8130）。
     *
     * 父目录由实现创建（原版 GSAVE 先 `Config.CreateSavDir()` 再 Save，:8124-8125）。
     */
    fun writeBytes(path: String, bytes: ByteArray): Boolean
}

/**
 * 单个图形缓冲。对应 `GraphicsImage`（GraphicsImage.cs:11-671）。
 *
 * 生命周期（GraphicsImage.cs:102-117 / :528-560）：
 * - [create] 先释放旧内容再建新位图（`GCreate` 调 `GDispose`），画笔/画刷/字体一并清空；
 * - [dispose] 尺寸归 0、像素清空、三个绘图状态置 null，[isCreated] 转 false；
 * - 未创建时宽高读 0（`size = new Size(0,0)`）。
 *
 * 状态字段对应 `GraphicsImage` 的 `brush` / `pen` / `font` / `style`：
 * [brushColor] 是 `SolidBrush.Color`，[penColor]+[penWidth] 是 `Pen.Color`/`Pen.Width`，
 * [fontName]+[fontSize]+[fontStyle] 是**请求名**、`Font.Size` 与 `Fontstyle` 位掩码，
 * [fontResolvedName] 才是 `Font.Name`（GraphicsImage.cs:60；解析口径见其 KDoc）。
 */
class GraphicsBuffer(val id: Int) {

    /** 位图宽度；未创建 = 0（`GraphicsImage.Width => size.Width`）。 */
    var width: Int = 0
        private set

    /** 位图高度；未创建 = 0。 */
    var height: Int = 0
        private set

    /** 已落地的像素（[pendingPixels] 为 null 时即全部真值）。 */
    private var pixelData: IntArray = IntArray(0)

    /**
     * 延迟解码源（CSV 资源表专用）。非 null 时首像素访问才真正解码 ——
     * 原版 `AppContents.LoadContents` 是逐文件立即 `Bitmap`（AppContents.cs:273-293），
     * 本语料四个 CSV 引用的 134 张图全解码约 105 MiB，真机 512 MiB 堆上不可常驻；
     * 尺寸/`IsCreated` 已在登记时按文件头给出，故语义等价（`ASpriteSingle.IsCreated`
     * 只看 `BaseImage.IsCreated`，ASpriteSingle.cs:24-34），只是像素按需落地。
     */
    private var pendingPixels: (() -> IntArray?)? = null

    /**
     * 像素，行主序，`0xAARRGGBB`；未创建 = 空数组。
     *
     * 读侧可能来自 UI 线程（`SpriteBitmapSource` 建位图）而写侧是 VM 线程
     * （`GCREATEFROMFILE` / `GDISPOSE`），延迟解码与清空都收进本实例监视器。
     */
    var pixels: IntArray
        get() {
            val pending = pendingPixels ?: return pixelData
            synchronized(this) {
                val load = pendingPixels ?: return pixelData
                // 先记下这一代的尺寸：解码期间尺寸不会变（写侧都走同一把锁），
                // 但失败分支可能要清尺寸，后面的长度判定必须用解码前的值。
                val loadW = width
                val loadH = height
                val decoded = try {
                    load()
                } catch (t: Throwable) {
                    // 宿主解码器是实现方（Android 侧 BitmapFactory / 桌面侧纯 JVM PNG），
                    // 抛异常一律按「读不出这张图」处理，不让它穿到 VM 线程或 UI 线程。
                    null
                }
                pixelData = if (decoded != null && decoded.size == loadW * loadH) {
                    decoded
                } else {
                    // 解码失败：原版这一行在装载期就被丢弃（AppContents.cs:274-278，`LoadImage`
                    // 抛异常折成 null 后 `continue`）。延迟源拿不到像素，这里退化成同尺寸全透明图，
                    // 保持 `pixels.length == width * height` 这条不变量 —— 下游按尺寸索引
                    // （`GDRAWSPRITE` 的 arraycopy、`SpriteBitmapSource` 建 Bitmap）才不会越界；
                    // 全透明在屏上等价于「没画」。失败结果同样清掉源，免得每次访问都重试解码
                    // （`GCREATEFROMFILE` 读不出图时折成 0 同口径，FunctionMethodCreator.cs:6905）。
                    try {
                        IntArray(loadW * loadH)
                    } catch (t: Throwable) {
                        // 连透明图都分配不出来（真机堆已满）：退回「未创建」，让 isCreated 关掉
                        // 所有下游入口。尺寸与像素在同一把锁里成对清空，读者不会拿到半套状态。
                        width = 0
                        height = 0
                        IntArray(0)
                    }
                }
                pendingPixels = null
                return pixelData
            }
        }
        private set(value) {
            pixelData = value
            pendingPixels = null
        }

    /** `SolidBrush.Color`；null = 未设置（`brush == null`）。 */
    var brushColor: Int? = null

    /** `Pen.Color`；null = 未设置。 */
    var penColor: Int? = null

    /** `Pen.Width`；默认 1（`new Pen(color)` 的默认宽度）。 */
    var penWidth: Int = 1

    /** `Pen.DashStyle`（`GDashStyle` 只写不改，GraphicsImage.cs:436-448）。 */
    var dashStyle: Int = 0

    /** `Pen.DashCap`。 */
    var dashCap: Int = 0

    /**
     * `Font.Name` —— 移植侧记的是**请求名**（`GSETFONT` 第 2 参原样）。
     * null = 未设置（`font == null`）。
     *
     * 测量走这个名字（`FontSidecarCatalog.chainFor` 的第一跳），与原版一致：原版把
     * 解析后的 `Font` 对象存进 `GraphicsImage.font`，绘制与测量都读同一个对象
     * （spec: FunctionMethodCreator.cs:6394 / :6400 → GraphicsImage.cs:89 `Fnt`）。
     */
    var fontName: String? = null

    /**
     * 解析名 —— 原版 `GraphicsImage.cs:60` 的 `Fontname => font.Name`，即 **GDI+ 解析后**的家族名，
     * 不是请求名；`GGETFONT` 回读的就是它（spec: FunctionMethodCreator.cs:6219）。
     *
     * 请求名解析不到时 GDI+ 静默换一个族继续画（实测 `new Font("NoSuchFamilyZZZ", 16f, Regular, Pixel)`
     * 的 `Name` = `Microsoft Sans Serif`，2026-10-21 本机 System.Drawing），所以两者可以不同。
     * 移植侧的对应值由 `PortFonts.resolveFontName` 给出，见 `GSETFONT` 分支的 KDoc。
     * null = 未设置字体。
     */
    var fontResolvedName: String? = null

    /** `Font.Size`（构造时用 `GraphicsUnit.Pixel`，故 `Size` 就是像素值）。 */
    var fontSize: Int = 0

    /** `Fontstyle` 位掩码：Bold=1 / Italic=2 / Strikeout=4 / Underline=8（GraphicsImage.cs:64-87）。 */
    var fontStyle: Int = 0

    /** `GraphicsImage.IsCreated`：`g != null`（GraphicsImage.cs:44-54）。 */
    val isCreated: Boolean get() = width > 0 && height > 0

    /** `GCreate`：先 `GDispose` 再建位图（GraphicsImage.cs:102-117）。 */
    fun create(w: Int, h: Int) {
        dispose()
        width = w
        height = h
        pixels = IntArray(w * h)
    }

    /** `GCreateFromF`：按源位图尺寸重建并拷贝像素（GraphicsImage.cs:119-130）。 */
    fun createFrom(w: Int, h: Int, src: IntArray) {
        dispose()
        width = w
        height = h
        pixels = IntArray(w * h)
        System.arraycopy(src, 0, pixels, 0, minOf(src.size, pixels.size))
    }

    /**
     * `GCREATEFROMFILE` / `GLOAD` 的解码像素落点：先校验再按 [createFrom] 重建。
     *
     * 校验口径：
     * - 宽/高必须 1..[MAX_IMAGE_SIZE]：原版 `bitmap.Width > 8192 || bitmap.Height > 8192`
     *   时函数直接返回 0（FunctionMethodCreator.cs:6905 / :8177）；
     * - [src] 长度必须恰好 `w * h`：解码器已保证，这里兜底，避免半张图落进缓冲。
     *
     * 返回 false 时**不修改**当前缓冲（原版这条路径也不会走到 `GCreateFromF`）。
     */
    fun createFromArgb(w: Int, h: Int, src: IntArray): Boolean {
        if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return false
        if (src.size != w * h) return false
        createFrom(w, h, src)
        return true
    }

    /**
     * CSV 资源表的延迟图片：尺寸取自文件头（原版 `LoadImage` 后 `bitmap.Width/Height`，
     * AppContents.cs:273-280），像素在首访问时由 [load] 给出。
     *
     * @return false = 尺寸非法（原版 >8192 只告警但仍建图，AppContents.cs:280-283；
     *   这里对齐 `GCREATEFROMFILE` 的硬闸，因为延迟源拿不到半张图）。
     */
    fun createDeferred(w: Int, h: Int, load: () -> IntArray?): Boolean {
        if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return false
        dispose()
        width = w
        height = h
        pendingPixels = load
        return true
    }

    /** `GDispose`：尺寸 0、位图/画笔/画刷/字体全部释放（GraphicsImage.cs:528-560）。 */
    fun dispose() {
        width = 0
        height = 0
        pixels = IntArray(0)
        brushColor = null
        penColor = null
        penWidth = 1
        dashStyle = 0
        dashCap = 0
        fontName = null
        fontResolvedName = null
        fontSize = 0
        fontStyle = 0
    }

    /**
     * 读像素（原版 `Bitmap.GetPixel`；越界给透明色而不是抛异常）。
     *
     * 越界不炸是延迟缓冲的要求：CSV 资源表的图在首像素访问时才解码，调用方手里那份
     * `width/height` 可能来自解码前的登记尺寸（解码失败时尺寸会被清零，见 [pixels]），
     * 按它算出来的坐标就可能落到实际像素数组之外。原版这条路走的是完整加载的 `Bitmap`，
     * 没有这个窗口。
     */
    fun pixelAt(x: Int, y: Int): Int {
        val px = pixels
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val idx = y * width + x
        return if (idx < px.size) px[idx] else 0
    }

    /** 写像素（直接覆盖，用于 `GSetColor` 的 `Bitmap.SetPixel`）。 */
    fun setPixel(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    /**
     * `Graphics.Clear(Color)`。
     *
     * GDI+ 实测口径：**alpha == 0 的 Clear 结果是全 0**（不是 0x00RRGGBB）。证据 =
     * 基准 `pc-golden-root.txt:642`：`GCLEAR(10, 0x0000FF)` 之后 `GGETCOLOR(10,0,0)=0`；
     * 若按 ARGB 原样填充，该像素应是 0x000000FF=255。GDI+ 内部按预乘处理，A=0 时 RGB 信息丢失。
     * TODO(verify)：A 在 1..254 时的舍入行为未取到样本（基准只覆盖 A=0），此处按原样填充。
     */
    fun clear(argb: Int) {
        val v = if ((argb ushr 24) == 0) 0 else argb
        pixels.fill(v)
    }

    /** `GClear(color, x, y, w, h)`：只清矩形区域（GraphicsImage.cs:141-152，SetClip+Clear）。 */
    fun clearRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        fillRect(x, y, w, h, argb, replace = true)
    }

    /**
     * `Graphics.FillRectangle`。
     *
     * [replace] = true 时按 [argb] 原样覆盖（用于 `Graphics.Clear` 的裁剪分支，它走 Clear 而非
     * FillRectangle）；false 时按 source-over 合成（GDI+ 画刷语义，A=0 不改目标像素）。
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int, replace: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        // GDI+ Rectangle 按 float 处理，x+w 不会 Int 回绕（GraphicsImage.cs:305-315 的
        // DrawImage 同族）；这里升 Long 求交避免 x+w / y+h 溢出成负数把整段裁没。
        val x1 = minOf(width.toLong(), x.toLong() + w.toLong()).toInt()
        val y1 = minOf(height.toLong(), y.toLong() + h.toLong()).toInt()
        if (x0 >= x1 || y0 >= y1) return
        val v = if (replace && (argb ushr 24) == 0) 0 else argb
        for (ty in y0 until y1) {
            val row = ty * width
            for (tx in x0 until x1) {
                if (replace) pixels[row + tx] = v else blendPixel(tx, ty, argb)
            }
        }
    }

    /**
     * 描边矩形：只画边框，[thickness] 像素全部落在框**内**。
     *
     * 对应 `Graphics.DrawPath(pen, path)`（GraphicsImage.cs:180-187）在软件缓冲上的近似 ——
     * GDI+ 沿路径中线描边（一半在路径外）、并且做斜接与端点样式；这里没有路径几何，
     * 只把边框画在框内，故 `GDRAWTEXT` 的替代字形轮廓比原版窄约半个 pen 宽（见该函数的 KDoc）。
     *
     * 坐标一律 Long：调用方（`GDRAWTEXT`）的 `x` 来自 `ReadPoint`（FMC.cs:9362-9375，只做
     * Int 范围检查），而 `x + advance` 在 Int 里会回绕；GDI+ 对越界路径只是裁剪
     * （同函数实测：origin=(-20,-20) 只留下画布内的像素），所以这里按 Long 求交后丢弃画布外部分。
     * 合成走 [blendPixel]（SourceOver，alpha=0 不改目标）。
     */
    fun strokeRect(x0: Long, y0: Long, x1: Long, y1: Long, argb: Int, thickness: Int = 1) {
        if (thickness <= 0) return
        val left = minOf(x0, x1)
        val top = minOf(y0, y1)
        val right = maxOf(x0, x1)
        val bottom = maxOf(y0, y1)
        val t = thickness.toLong()
        val cx0 = maxOf(0L, left)
        val cy0 = maxOf(0L, top)
        val cx1 = minOf(width.toLong(), right)
        val cy1 = minOf(height.toLong(), bottom)
        if (cx0 >= cx1 || cy0 >= cy1) return
        val innerLeft = left + t
        val innerRight = right - t
        val innerTop = top + t
        val innerBottom = bottom - t
        for (ty in cy0 until cy1) {
            val onHorizontalEdge = ty < innerTop || ty >= innerBottom
            for (tx in cx0 until cx1) {
                if (onHorizontalEdge || tx < innerLeft || tx >= innerRight) {
                    blendPixel(tx.toInt(), ty.toInt(), argb)
                }
            }
        }
    }

    /**
     * `GDrawLine`：Bresenham，1px（原版按 `Pen.Width` 描边；宽度近似见函数层 KDoc）。
     *
     * 对应 `GraphicsImage.GDrawLine`（GraphicsImage.cs:421-434）。
     *
     * 端点先按 Liang-Barsky 裁剪进画布再迭代：原实现的 `|x1-x0|` / `|y1-y0|` 用 Int，
     * 坐标差 ≥ 2^30 时 `err` 溢出，`e2 >= dy` 与 `e2 <= dx` 可能同时不成立 ⇒ 坐标永不推进的死循环。
     * 裁剪后坐标差不超过画布宽高，仍全部用 Long 运算，并以「包围盒宽+高」作迭代数兜底。
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        if (width <= 0 || height <= 0) return
        val ax = x0.toDouble()
        val ay = y0.toDouble()
        val ddx = x1.toDouble() - ax
        val ddy = y1.toDouble() - ay
        var t0 = 0.0
        var t1 = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val r = q / p
            if (p < 0.0) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
        val maxX = (width - 1).toLong()
        val maxY = (height - 1).toLong()
        if (!clip(-ddx, ax)) return
        if (!clip(ddx, maxX.toDouble() - ax)) return
        if (!clip(-ddy, ay)) return
        if (!clip(ddy, maxY.toDouble() - ay)) return
        var x = (ax + t0 * ddx).toLong().coerceIn(0L, maxX)
        var y = (ay + t0 * ddy).toLong().coerceIn(0L, maxY)
        val ex = (ax + t1 * ddx).toLong().coerceIn(0L, maxX)
        val ey = (ay + t1 * ddy).toLong().coerceIn(0L, maxY)
        val dx = kotlin.math.abs(ex - x)
        val dy = -kotlin.math.abs(ey - y)
        val sx = if (x < ex) 1L else -1L
        val sy = if (y < ey) 1L else -1L
        var err = dx + dy
        val maxSteps = dx - dy + 2
        var steps = 0L
        while (true) {
            blendPixel(x.toInt(), y.toInt(), argb)
            if ((x == ex && y == ey) || steps++ >= maxSteps) break
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

    /**
     * `GDrawG`：把 [src] 的 `(sx,sy,sw,sh)` 缩放贴到 `(dx,dy,dw,dh)`。
     *
     * 对应 `GraphicsImage.GDrawG`（GraphicsImage.cs:305-315，GDI+ `DrawImage`）。
     * 最近邻取样（GDI+ 是双线性；像素级差异不属于验收口径，契约 §4 A4）。
     *
     * **所有中间量升 Long**：原版 `g.DrawImage(bitmap, destRect, srcRect, GraphicsUnit.Pixel)`
     * （GraphicsImage.cs:314）在 GDI+ 里按 float 处理矩形，坐标/宽高的加减乘**从不回绕**，
     * 超界部分一律被裁剪（dest 裁到目标表面、src 越界采透明）。移植侧若用 Int 做这些
     * 中间量，极大值会回绕成负矩形（`sw*sh` 溢出、`x+w` 溢出），行为就与原版分叉了。
     * 快照只物化「实际会被采样且落在源缓冲内」的区域，既保住 src === this 的读冻结
     * （基准 652 行 `GDRAWG(10,10,...)` 自贴图），也不给极大矩形任何 OOM 通道。
     *
     * [matrix]（GFX-01 新增，只有 `GDRAWSPRITE` 的 7 参形态传非 null）是 GDI+ `ColorMatrix`
     * 的 25 个 Float、**行主序、行 = 源通道、列 = 目标通道**（口径与出处见 [applyColorMatrix]）。
     * 原版把矩阵交给 `ImageAttributes.SetColorMatrix(...)` 后由 `DrawImage` 消费
     * （`GDRAWSPRITE` 链路：GraphicsImage.cs:291-303 → ASpriteSingle.cs:89-99 /
     * SpriteAnime.cs:161-172），GDI+ 的顺序是**取样 → 颜色换算 → 合成**，故这里对
     * 「实际被采到的那个源像素」逐像素换算后再 [blendPixel]；1:1 贴图上与原版同序，
     * 缩放贴图时差异只可能来自取样模式（最近邻 vs 双线性），已按契约 §4 A4 登记。
     */
    fun blitFrom(
        src: GraphicsBuffer,
        dx: Int,
        dy: Int,
        dw: Int,
        dh: Int,
        sx: Int,
        sy: Int,
        sw: Int,
        sh: Int,
        matrix: FloatArray? = null,
    ) {
        if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return
        val dxL = dx.toLong(); val dyL = dy.toLong()
        val dwL = dw.toLong(); val dhL = dh.toLong()
        val sxL = sx.toLong(); val syL = sy.toLong()
        val swL = sw.toLong(); val shL = sh.toLong()
        val wL = width.toLong(); val hL = height.toLong()
        // 缓冲内实际会写的 dest 像素范围（Long，不回绕）
        val iLo = maxOf(0L, -dxL)
        val iHi = minOf(dwL, wL - dxL)
        val jLo = maxOf(0L, -dyL)
        val jHi = minOf(dhL, hL - dyL)
        if (iLo >= iHi || jLo >= jHi) return
        // 源图可能是 CSV 资源表的延迟缓冲（首像素访问才解码）。先取像素、再读尺寸，并要求
        // 两者自洽：解码失败退化成同尺寸透明图、极端情况下尺寸被清零，都不能让下面的
        // arraycopy 拿旧尺寸去索引空数组。
        val srcPixels = src.pixels
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0 || srcPixels.size != srcW * srcH) return
        // dest (i,j) → 源采样坐标；与旧口径同式（i*sw/dw 整除 + coerce 到源矩形内）
        fun srcX(i: Long): Long = sxL + (i * swL / dwL).coerceIn(0L, swL - 1L)
        fun srcY(j: Long): Long = syL + (j * shL / dhL).coerceIn(0L, shL - 1L)
        // 实际会被采样且落在源缓冲内的区域（srcX/srcY 对 i/j 单调不减，端点即范围）
        val xMin = maxOf(0L, srcX(iLo))
        val xMax = minOf(srcW.toLong() - 1L, srcX(iHi - 1L))
        val yMin = maxOf(0L, srcY(jLo))
        val yMax = minOf(srcH.toLong() - 1L, srcY(jHi - 1L))
        if (xMin > xMax || yMin > yMax) return // 采样全落源外：全是透明色，blendPixel 无操作
        val snapW = (xMax - xMin + 1L).toInt()
        val snapH = (yMax - yMin + 1L).toInt()
        val snap = IntArray(snapW * snapH)
        for (yy in 0 until snapH) {
            System.arraycopy(srcPixels, ((yMin + yy) * srcW + xMin).toInt(), snap, yy * snapW, snapW)
        }
        for (j in jLo until jHi) {
            val ty = (dyL + j).toInt() // jHi ≤ hL - dyL ⇒ ty ∈ [0, height)，不越界不回绕
            val py = srcY(j)
            if (py < yMin || py > yMax) continue
            val row = ((py - yMin) * snapW).toInt()
            for (i in iLo until iHi) {
                val px = srcX(i)
                if (px < xMin || px > xMax) continue // 源外采样 = 透明（GDI+ 越界口径）
                val raw = snap[row + (px - xMin).toInt()]
                // 颜色换算发生在合成之前（GDI+ 的 DrawImage 顺序），逐像素做一次 5x5 乘加。
                blendPixel((dxL + i).toInt(), ty, if (matrix === null) raw else applyColorMatrix(raw, matrix))
            }
        }
    }

    /**
     * `GDrawGWithMask`：逐像素遮罩合成（GraphicsImage.cs:332-392 原样复刻）。
     *
     * 注意原版把 **BGRA 首字节（蓝通道）** 当遮罩 alpha 用（`array2[num2]`，GraphicsImage.cs:356/364/370），
     * 因为 `LockBits` 的 32bppArgb 内存序是 B,G,R,A。这里同样取低 8 位（蓝），属官方 bug 原样复刻。
     * 混合公式也照抄：`(src*(m+1) + dst*(256-(m+1))) >> 8`。
     */
    fun blitMasked(src: GraphicsBuffer, mask: GraphicsBuffer, dx: Int, dy: Int) {
        if (src.width != mask.width || src.height != mask.height) return
        for (j in 0 until src.height) {
            val ty = dy + j
            if (ty < 0 || ty >= height) continue
            for (i in 0 until src.width) {
                val tx = dx + i
                if (tx < 0 || tx >= width) continue
                val sIdx = j * src.width + i
                val m = mask.pixels[sIdx] and 0xFF
                val dIdx = ty * width + tx
                when (m) {
                    0 -> {}
                    255 -> pixels[dIdx] = src.pixels[sIdx]
                    else -> {
                        val k = m + 1
                        val s = src.pixels[sIdx]
                        val d = pixels[dIdx]
                        val b = (((s and 0xFF) * k + (d and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val g = ((((s shr 8) and 0xFF) * k + ((d shr 8) and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val r = ((((s shr 16) and 0xFF) * k + ((d shr 16) and 0xFF) * (256 - k)) shr 8) and 0xFF
                        val a = ((((s ushr 24)) * k + ((d ushr 24)) * (256 - k)) shr 8) and 0xFF
                        pixels[dIdx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    /**
     * `GDrawGWithRotate`：绕 `(cx,cy)` 旋转后把 [src] 贴到 (0,0)（GraphicsImage.cs:407-419）。
     *
     * 原版用 GDI+ 变换矩阵；这里用逆变换 + 最近邻取样（像素级近似，见契约 §4 A4）。
     */
    fun rotateFrom(src: GraphicsBuffer, angleDegrees: Long, cx: Int, cy: Int) {
        if (src.width <= 0 || src.height <= 0) return
        val rad = Math.toRadians(angleDegrees.toDouble())
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)
        for (ty in 0 until height) {
            for (tx in 0 until width) {
                val dx = tx - cx
                val dy = ty - cy
                val fx = dx * cos + dy * sin + cx
                val fy = -dx * sin + dy * cos + cy
                val sx = kotlin.math.floor(fx).toInt()
                val sy = kotlin.math.floor(fy).toInt()
                if (sx in 0 until src.width && sy in 0 until src.height) {
                    blendPixel(tx, ty, src.pixels[sy * src.width + sx])
                }
            }
        }
    }

    /** source-over 合成（非预乘）。alpha=0 不改目标，alpha=255 直接覆盖。 */
    fun blendPixel(x: Int, y: Int, argb: Int) {
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
        val oa = (a + (d ushr 24) * ia / 255) and 0xFF
        pixels[idx] = (oa shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------
    // GSAVE / GLOAD 的字节载体
    // ------------------------------------------------------------------

    /**
     * 编码为**真 PNG**（GSAVE 的落盘载体）。
     *
     * 原版 `graphicsImage.Bitmap.Save(saveDataPathGraphics)`（FunctionMethodCreator.cs:8125）
     * 不指定 `ImageFormat`，落盘格式由路径扩展名 `.png` 决定（:9471-9474 的
     * `$"{Config.SavDir}img{index:0000}.png"`）。GDI+ 侧实测（2026-10-24，本机 .NET 8
     * System.Drawing，`new Bitmap(4,3).Save("x.png")`）：
     * 签名 `89 50 4E 47 0D 0A 1A 0A`、IHDR `depth=8 colortype=6 comp=0 filter=0 interlace=0`
     * —— 与本函数的编码参数逐项一致。本函数把移植侧此前「写文本载体代偿」的那一步补成真 PNG。
     *
     * ## 编码参数（逐项有据）
     *
     * - **8 位/通道、色型 6（真彩 + alpha，非预乘）**：缓冲像素就是 `0xAARRGGBB`
     *   （见类 KDoc 的像素布局段），与 RGBA8 逐通道一一对应，无量化损失；
     * - **每扫描线 filter 类型 0（None）**：GDI+ 的 filter 选择是编码器内部启发式
     *   （反编译无行号、也无基准读数），复刻它既不可验证也无收益；像素级等价即够
     *   （契约 §4 A4 的像素近似口径）；
     * - **zlib 用 [Deflater] 默认级别**，逐行喂入：不物化整幅 RGBA 原始流
     *   （8192×8192 的扫描线是 256 MB，一次分配就会在真机堆上炸）。
     *
     * ## 失败口径
     *
     * 原版把 `Bitmap.Save` 整个包在 `try` 里，任何异常（含 GDI+ 的 `OutOfMemoryException`）
     * 都被 `catch` 折成 GSAVE 的返回 0（:8122-8130）。本函数**不吞异常**：编码失败照常抛
     * （`OutOfMemoryError` 也是），由函数层按同一口径折成 0（见 `fn/GraphicsMethods.kt`
     * 的 GSAVE 段），这样「缓冲未创建」这类编程错误不会被静默吃掉。
     *
     * @throws IllegalStateException 缓冲未创建（宽或高为 0）
     */
    fun toSavePng(): ByteArray {
        check(width > 0 && height > 0) { "未创建的缓冲不能编码 PNG（${width}x$height）" }
        val out = ByteArrayOutputStream(1 shl 16)
        // PNG 文件签名（PNG 规范 5.2）：89 50 4E 47 0D 0A 1A 0A
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR：宽、高、位深、色型、压缩方法、滤波方法、隔行扫描
        val ihdr = ByteArray(13)
        putIntBe(ihdr, 0, width)
        putIntBe(ihdr, 4, height)
        ihdr[8] = 8 // 位深 8 位/通道（与 0xAARRGGBB 逐通道对齐）
        ihdr[9] = 6 // 色型 6 = 真彩 + alpha（RGBA，非预乘）
        writeChunk(out, "IHDR", ihdr)
        val idat = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater()
        try {
            val row = ByteArray(1 + width * 4)
            val buf = ByteArray(1 shl 16)
            for (y in 0 until height) {
                row[0] = 0 // filter: None
                var p = 1
                val base = y * width
                for (x in 0 until width) {
                    val v = pixels[base + x]
                    row[p++] = ((v shr 16) and 0xFF).toByte()
                    row[p++] = ((v shr 8) and 0xFF).toByte()
                    row[p++] = (v and 0xFF).toByte()
                    row[p++] = (v ushr 24).toByte()
                }
                deflater.setInput(row)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(buf)
                    if (n <= 0) break
                    idat.write(buf, 0, n)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                idat.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        writeChunk(out, "IDAT", idat.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * 序列化为自描述文本（**旧** GSAVE 载体，现仅作回落与读侧兼容）。
     *
     * 原版 `Bitmap.Save(path)` 写 PNG（FunctionMethodCreator.cs:8125）。移植侧早先没有
     * 可移植的 PNG 编码器，用文本格式承载同一份 ARGB 像素：首行魔数+版本，第二行
     * `宽 高`，第三行起每行一个像素的 8 位十六进制。
     *
     * 现状（GFX-13）：GSAVE 在宿主实现 [BinaryFileOps] 时改写 [toSavePng] 的真 PNG；
     * 未接线时才回落本载体（`fn/GraphicsMethods.kt` 的 GSAVE 段）。读取侧
     * `GCREATEFROMFILE` / `GLOAD` 先按 PNG 解码真实图片
     * （[com.eratw.port.core.image.PngDecoder]），签名不是 PNG 时才回落本载体，
     * 故旧档与回落产物都仍可读，GSAVE → GLOAD 往返像素逐字节一致（契约 §4 A4）。
     */
    fun toSaveText(): String {
        // 初始容量按像素数估算（每像素 9 字符），但只按上限预分配；实际增长交给 StringBuilder，
        // 避免 `width * height * 9` 在超大缓冲上一次性申请数百 MB。
        val estimate = 64L + pixels.size.toLong() * 9L
        val cap = minOf(estimate, 1L shl 22).toInt()
        val sb = StringBuilder(cap)
        sb.append(SAVE_MAGIC).append('\n')
        sb.append(width).append(' ').append(height).append('\n')
        for (v in pixels) {
            val h = Integer.toHexString(v)
            for (k in h.length until 8) sb.append('0')
            sb.append(h).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** GSAVE 文本载体魔数（见 [toSaveText]）。 */
        const val SAVE_MAGIC = "ERATW-GFX 1"

        /** 大端写 32 位整数（PNG 的块长 / CRC 与 IHDR 的宽高都是大端）。 */
        private fun putIntBe(dst: ByteArray, at: Int, v: Int) {
            dst[at] = (v ushr 24).toByte()
            dst[at + 1] = (v ushr 16).toByte()
            dst[at + 2] = (v ushr 8).toByte()
            dst[at + 3] = v.toByte()
        }

        /**
         * 写一个 PNG 块：`长度(4) 类型(4) 数据 CRC(4)`（PNG 规范 5.3）。
         *
         * CRC32 覆盖「类型 + 数据」，不含长度字段。
         */
        private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val len = ByteArray(4)
            putIntBe(len, 0, data.size)
            out.write(len)
            val tag = type.toByteArray(Charsets.US_ASCII)
            out.write(tag)
            out.write(data)
            val crc = CRC32()
            crc.update(tag)
            crc.update(data)
            val sum = ByteArray(4)
            putIntBe(sum, 0, crc.value.toInt())
            out.write(sum)
        }

        /** 尺寸上限（`AbstractImage.MAX_IMAGESIZE`，AbstractImage.cs:8）。 */
        const val MAX_IMAGE_SIZE = 8192

        /**
         * 文本载体允许的最大像素数（只约束 [fromSaveText] 的**读**侧）。
         *
         * 文本载体每像素约 9 字符（8 位十六进制 + 换行），4,000,000 像素 ≈ 36 MB 字符串，
         * 再大就接近 JVM 堆上限。GSAVE 自 GFX-13 起写真 PNG（[toSavePng]），**不再有**这条
         * 限制：原版 GSAVE 对像素数无上限，失败只来自 `Bitmap.Save` 抛异常被 catch 折成 0
         * （FunctionMethodCreator.cs:8122-8130）。
         */
        const val MAX_SAVE_TEXT_PIXELS = 4_000_000L

        /**
         * 单图**像素数**上限（宽 × 高），解码侧（`PngDecoder` / `WebpDecoder`）在分配任何
         * 大缓冲之前用它拒绝巨图。
         *
         * 原版对宽或高 > 8192 的图返回 0（FunctionMethodCreator.cs:6905-6908），但**不限制
         * 像素总数**：8192×8192 在 GDI+ 里由 `new Bitmap(path)` 直接解出来，OOM 了也是
         * `Utils.LoadImage` 的空 catch 兜底（Utils.cs:521-523）→ null → 函数返回 0。移植侧
         * 解码器是自己分配 `IntArray(w * h)`，8192×8192 一张就是 256 MB 像素 + 同量级的
         * 解压缓冲，在真机堆上直接抛 `OutOfMemoryError`——而 `readImageFile` 只捕
         * `Exception`（GraphicsMethods.kt:971-975），Error 会一路炸穿 VM 线程。所以这里
         * 按「GDI+ 在内存受限宿主上 OOM ⇒ 加载失败」的同一结局，把上限**显式化**：
         * 超限在分配前抛解码失败（Exception），上层按原版口径折成 0。
         *
         * 取值：语料普查的最大图是 8192×4000 = 32,768,000 px（WebpDecoder KDoc 的语料
         * 读数），取 2 的幂 33,554,432（2^25，约 128 MB 像素）刚好覆盖语料、排除
         * 8192×8192 级别的巨图。
         */
        const val MAX_IMAGE_PIXELS = 33_554_432

        /** 颜色矩阵的元素个数（GDI+ `ColorMatrix` 是 5x5）。 */
        const val COLOR_MATRIX_SIZE = 25

        /**
         * GDI+ `ColorMatrix` 的单像素换算（总账 GFX-01，`GDRAWSPRITE` 的 7 参形态）。
         *
         * ## 索引口径（**不是**图形学课本的转置）
         *
         * [m] 是 25 个 Float 的**行主序**数组，**行 = 源通道、列 = 目标通道**，第 4 行是常量项
         * （偏移），即
         * `out[dst] = in[R]*m[0][dst] + in[G]*m[1][dst] + in[B]*m[2][dst] + in[A]*m[3][dst] + m[4][dst]`。
         * 依据：
         * - 脚本侧矩阵元素按 `array[i][j] = 变量值 / 256f` 装进 `float[5][5]`
         *   （spec: FunctionMethodCreator.cs:9427-9434 二维分支、:9454-9461 三维分支 ⇒ 第一个下标
         *   是脚本里 `ARG_CM:i:j` 的 `i`，它直接进 `ColorMatrix` 的 Matrix i）；
         * - `Matrix33` 被用作**不透明度**（spec: ConsoleBackground.cs:19-22 `colorMatrix.Matrix33 = opacity`）
         *   ⇒ 通道下标 0..3 = R,G,B,A、4 = 常量行；
         * - 通道换位的实测读数（2026-10-23 本机 System.Drawing / GDI+，`DrawImage(1x1, 1x1, Pixel, attr)`，
         *   源像素 A255 R200 G100 B50、目标全透明，单位阵打底后只改指名的那一个元素）：
         *   `m[0][0] = 0` 且 `m[0][1] = 1`（第 1 行仍是单位行）⇒ `A255 R0 G255 B50`
         *   （源 R 落到目标 G：G' = 200 + 100 = 300 → 夹到 1.0 → 255；若按「行 = 目标通道」
         *   应当得 `R100`，实测否掉了那一种）；
         *   `m[1][1] = 0` 且 `m[1][2] = 1` ⇒ `A255 R200 G0 B150`（源 G 落到目标 B：100 + 50）；
         *   偏移项取 `m[4][0] = 0.5` ⇒ `R200 → 255`（0.784+0.5 超过 1.0 后夹住），
         *   而把同样的 0.5 写在 `m[0][4]` 不产生任何偏移 ⇒ 偏移确实住在**第 4 行**。
         *
         * ## 数值口径（实测 2026-10-23，同一装置）
         *
         * 通道值按 `通道 / 255f` 归一后参与乘加，结果**先夹到 [0,1] 再 `round(v * 255f)`**：
         * - `m[0][0] = 0.5`（脚本值 128）对源 R=1/3/5/129/255 → 1/2/3/65/128，
         *   即 0.5/1.5/2.5/64.5/127.5 全部**四舍五入向上**（向下取整会是 0/1/2/64/127）；
         * - 脚本值 90/256 对源 R=2/4/5/7/9/255 → 1/1/2/2/3/90（0.703/1.406/1.758/2.461/3.164/89.65 取最近）；
         * - 负值夹到 0（`m[0][0] = -1` 配源 R=200 → 0）、超过 1 夹到 1（`m[0][0] = 2` 配源 R=200 → 255）；
         * - 对角全 256（=1.0）逐通道原样透传。
         * 换算在**直通（非预乘）ARGB** 上做：源 A=0、RGB=(200,100,50) 配「A 行偏移 +0.5」时
         * 实测结果 = 把 (A128,R200,G100,B50) 合成到目标上的值（若矩阵作用在预乘值上，RGB 会是 0）。
         *
         * ## 与 [blendPixel] 的分工
         *
         * 本函数只负责颜色换算，之后由 [blendPixel] 做 source-over 合成（原版从不设
         * `Graphics.CompositingMode`，取 GDI+ 默认 SourceOver ⇒ **叠加**而非覆盖，全仓检索无
         * `CompositingMode` 赋值点）；换算后 A=0 的像素一个都不改目标（实测：A=0 源配单位阵
         * 叠在不透明目标上目标保持 `A255 R10 G20 B30`；5x5 全 0 同样保持原值）。
         * 残差登记：目标 alpha 在 1..254 之间时 GDI+ 是在预乘空间合成再反预乘
         * （实测 源(A128,R200,G100,B50) 叠 目标(A128,R10,G20,B30) → `A192 R135 G73 B42`），
         * 而 [blendPixel] 的直通整型式给 `A191 R105 G60 B40`；本波不改 [blendPixel]
         * （它同时服务 `GDRAWG`/`GFILLRECTANGLE`，改了要重对基准），软件缓冲的目标 alpha
         * 实际只有 0 与 255 两档（`GCREATE` + `GCLEAR`），两档下两侧逐通道一致（实测
         * 源(A128,R200,G100,B50) 叠 目标(A255,R10,G20,B30) → 两侧同得 `A255 R105 G60 B40`）。
         *
         * @param m 25 个 Float，行主序，行 = 源通道、列 = 目标通道（见上）
         */
        fun applyColorMatrix(argb: Int, m: FloatArray): Int {
            require(m.size == COLOR_MATRIX_SIZE) { "ColorMatrix 必须是 ${COLOR_MATRIX_SIZE} 个 Float" }
            val a = argb ushr 24
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val nr = mixChannel(r, g, b, a, m, 0)
            val ng = mixChannel(r, g, b, a, m, 1)
            val nb = mixChannel(r, g, b, a, m, 2)
            val na = mixChannel(r, g, b, a, m, 3)
            return (na shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        /** [applyColorMatrix] 的单通道式：`Σ 源通道 × m[源][dst] + m[4][dst]`，夹到 [0,1] 后四舍五入。 */
        private fun mixChannel(r: Int, g: Int, b: Int, a: Int, m: FloatArray, dst: Int): Int {
            val f = (r / 255f) * m[dst] + (g / 255f) * m[5 + dst] + (b / 255f) * m[10 + dst] +
                (a / 255f) * m[15 + dst] + m[20 + dst]
            // Float 溢出可能是 NaN（脚本侧矩阵元素没有范围检查，spec: FunctionMethodCreator.cs:9432
            // 只做 `(float)long / 256f`）；NaN 走夹取会原样漏到 round 里，显式折 0。
            val v = if (f.isNaN()) 0f else f.coerceIn(0f, 1f)
            return Math.round(v * 255f)
        }

        /**
         * 解析 [toSaveText] 的产物；格式/尺寸不合法返回 null（GLOAD 返回 0）。
         * 不修改 this，返回新缓冲（GLOAD 走 `GCreateFromF`，会先释放旧内容）。
         *
         * 逐行用 [lineSequence] 迭代而不是 `split`：后者会把整份文本切成行数组，
         * 峰值内存翻倍（数十 MB 载体会多占一份等量对象），与 GSAVE 的 OOM 风险同源。
         * 像素数超过 [MAX_SAVE_TEXT_PIXELS] 按「载体不合法」返回 null，不尝试分配。
         */
        fun fromSaveText(id: Int, text: String): GraphicsBuffer? {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != SAVE_MAGIC) return null
            if (!lines.hasNext()) return null
            val dims = lines.next().trim().split(' ')
            if (dims.size != 2) return null
            val w = dims[0].toIntOrNull() ?: return null
            val h = dims[1].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE) return null
            val n = w.toLong() * h.toLong()
            if (n > MAX_SAVE_TEXT_PIXELS) return null
            val count = n.toInt()
            val px = IntArray(count)
            for (i in 0 until count) {
                if (!lines.hasNext()) return null
                val s = lines.next().trim()
                if (s.isEmpty() || s.length > 8) return null
                px[i] = (s.toLongOrNull(16) ?: return null).toInt()
            }
            val buf = GraphicsBuffer(id)
            return if (buf.createFromArgb(w, h, px)) buf else null
        }
    }
}

/**
 * 图形缓冲池。对应 `AppContents.gList`（AppContents.cs:20,28-37）。
 *
 * 按编号惰性建条目：`GetGraphics(i)` 对不存在的编号也会登记一个**未创建**的
 * `GraphicsImage`（这就是 `GDISPOSE(14)=0` 而非报错的原因——基准 651 行）。
 */
class GraphicsPool {
    private val buffers = HashMap<Int, GraphicsBuffer>()

    /** `AppContents.GetGraphics`：不存在则建一个空条目（IsCreated=false）。 */
    fun get(id: Int): GraphicsBuffer = buffers.getOrPut(id) { GraphicsBuffer(id) }

    /** 只查不建。 */
    fun peek(id: Int): GraphicsBuffer? = buffers[id]

    fun clear() = buffers.clear()
}

/**
 * Sprite 池。对应 `AppContents.imageDictionary`（运行时表）与 `resourceImageDictionary`
 * （CSV 资源表，AppContents.cs:16-22）。
 *
 * 名字一律按 `ToUpper(InvariantCulture)` 归一（AppContents.cs:45/57/86/102）。
 * [Sprite.isCreated] 的语义分两种（ASpriteSingle.cs:24-34 / SpriteAnime.cs:54）：
 * - 静态 Sprite（SpriteG/SpriteF）：基图存在且基图已创建；
 * - 动画 Sprite（SpriteAnime）：恒 true（帧列表可以为空）。
 *
 * ## 线程口径：UI 线程只读安全
 *
 * 内部容器是普通 `LinkedHashMap`，而读侧（platform 的 `SpriteBitmapSource` 经
 * `aux.peek` 拿到本实例后，在 UI 线程 `CanvasConsoleView.onDraw` 每帧 `find`）
 * 与写侧（VM 线程的 SPRITECREATE / SPRITEDELETE 语句）跨线程并发。因此**全部
 * 公开方法（含 [animeTimerMs] 的读写）都收进 `this` 监视器**：保证容器结构一致
 * ——不会读到 resize 半途的表、不会迭代失败——调用方（读侧在 platform 模块、
 * 写侧在 core-vm 语句层）无需自己加锁。锁内不做任何外部回调或绘制。
 *
 * 注意 [Sprite] 本身仍含可变状态（[Sprite.posX]/[Sprite.posY]、[Sprite.frames]
 * 列表、buffer 像素数组）：`find` 返回引用后对这些字段的读取不在本锁范围内，
 * UI 侧可能读到 VM 线程刚写入的新值（弱一致，只会「下一帧生效」，对象结构不
 * 会被破坏）；[Sprite.name]/[Sprite.width]/[Sprite.height]/srcX/srcY/srcW/srcH
 * 是登记后不再变化的 val，读侧可放心使用。
 */
class SpritePool {

    /** 动画帧。对应 `SpriteAnime.AnimeFrame`（SpriteAnime.cs:12-42）。 */
    class Frame(
        val buffer: GraphicsBuffer,
        val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
        val offsetX: Int, val offsetY: Int,
        val delayMs: Int,
    )

    /**
     * 单个 Sprite。
     *
     * @param frames null = 静态 Sprite（引用 [buffer] 的 [srcX]/[srcY]/[srcW]/[srcH] 区域）；
     *   非 null = 动画 Sprite（帧列表，[buffer] 无意义）。
     * @param width  `DestBaseSize.Width`（缩放后的目标尺寸）。
     * @param height `DestBaseSize.Height`。
     */
    class Sprite(
        val name: String,
        val width: Int,
        val height: Int,
        val buffer: GraphicsBuffer? = null,
        val srcX: Int = 0,
        val srcY: Int = 0,
        val srcW: Int = width,
        val srcH: Int = height,
        var posX: Int = 0,
        var posY: Int = 0,
        val frames: MutableList<Frame>? = null,
    ) {
        val isAnime: Boolean get() = frames != null

        /** `ASpriteSingle.IsCreated` / `SpriteAnime.IsCreated`。 */
        val isCreated: Boolean get() = if (frames != null) true else buffer?.isCreated == true

        /** 动画总时长（`SpriteAnime.totaltime`，SpriteAnime.cs:46,78）。 */
        val totalTimeMs: Int get() = frames?.sumOf { it.delayMs } ?: 0
    }

    private val sprites = LinkedHashMap<String, Sprite>()
    private val resourceSprites = LinkedHashMap<String, Sprite>()

    /**
     * 池级互斥锁：为什么收在类内？因为读侧（platform 的 UI 线程）与写侧（VM 线程）
     * 分属不同模块，调用方各自加锁既容易漏、也无法覆盖两模块间的组合操作；
     * 锁用 `this` 监视器（不新增字段：aux 槽对象会被 L3 状态哈希按字段名反射混入，新增字段会改变登记常量）并对所有公开方法生效后，`aux.peek` 拿到引用即安全。
     */
    /**
     * 动画重绘定时器（`SETANIMETIMER` → `Console.setRedrawTimer`）。
     *
     * 写侧是 VM 线程（GraphicsMethods 的 SETANIMETIMER），读侧可能来自 UI 线程，
     * 故读写同样收进 `this` 监视器保证可见性（裸 Int 属性跨线程无 happens-before）。
     * 自定义访问器不改变 `pool.animeTimerMs = x` 的调用方语法。
     */
    var animeTimerMs: Int = 0
        get() = synchronized(this) { field }
        set(value) {
            synchronized(this) { field = value }
        }

    /** `AppContents.GetSprite`：未命中返回 null。 */
    fun find(name: String): Sprite? = synchronized(this) { sprites[name.uppercase()] }

    /** 登记/覆盖一个 Sprite（`AppContents.CreateSpriteG` / `CreateSpriteAnime`）。 */
    fun put(sprite: Sprite) {
        synchronized(this) { sprites[sprite.name] = sprite }
    }

    /** `AppContents.SpriteDispose`：命中则移除并返回 true。 */
    fun dispose(name: String): Boolean = synchronized(this) { sprites.remove(name.uppercase()) != null }

    /**
     * `AppContents.SpriteDisposeAll`（AppContents.cs:66-78）。
     *
     * @param delResourceSprites true = 连 CSV 资源一起清（返回清掉的**全部**个数）；
     *   false = 只清运行时创建的，表回滚成资源表（返回 `count - resourceCount`）。
     */
    fun disposeAll(delResourceSprites: Boolean): Long = synchronized(this) {
        val count = sprites.size
        val resourceCount = resourceSprites.size
        if (delResourceSprites) {
            sprites.clear()
            resourceSprites.clear()
            return@synchronized count.toLong()
        }
        sprites.clear()
        sprites.putAll(resourceSprites)
        (count - resourceCount).toLong()
    }

    /**
     * 登记一个 CSV 资源 Sprite（`resourceImageDictionary` + 运行表副本，
     * 对应 `LoadContents` 末尾 `imageDictionary = new ConcurrentDictionary(resourceImageDictionary)`，
     * AppContents.cs:161,176）。
     */
    fun putResource(sprite: Sprite) {
        synchronized(this) {
            resourceSprites[sprite.name] = sprite
            sprites[sprite.name] = sprite
        }
    }

    /**
     * 播种 count 个**非资源**的运行时 Sprite（空动画，`IsCreated=true`）。
     *
     * 用途：复现 PC 会话在标题/开局阶段由游戏自身创建的 Sprite。基准 673 行
     * `SPRITEDISPOSEALL=35` 就是这 35 个的个数（`count - resourceCount`，当时资源表为空）。
     * 生产路径不会调用它——只给 GoldenPipeline 还原前置状态。
     */
    fun seedRuntimeSprites(count: Int, prefix: String = "GOLDEN_PRE_") {
        synchronized(this) {
            for (i in 0 until count) {
                val name = (prefix + i).uppercase()
                sprites[name] = Sprite(name, 1, 1, frames = ArrayList())
            }
        }
    }

    fun size(): Int = synchronized(this) { sprites.size }
    fun resourceSize(): Int = synchronized(this) { resourceSprites.size }

    /** 返回锁内快照（`keys.toSet()`），调用方拿到的是不受锁保护的副本。 */
    fun names(): Set<String> = synchronized(this) { sprites.keys.toSet() }
}

/**
 * CBG（控制台背景图 / 按钮）池。对应 `EmueraConsole.cbgList` 与 `cbgButtonMap`
 * （EmueraConsole.cs:563-682）。
 *
 * 只维护图层顺序与清除语义；真正绘制归 M4 的控制台。`zdepth == 0` 的条目是常驻底图
 * （`CBG_Clear` 会重新塞一个，EmueraConsole.cs:574），`CBG_ClearRange` 永不清 depth 0。
 */
class CbgPool {

    /** 一个背景图层。`Img`/`ImgB` 用缓冲编号或 Sprite 名表示。 */
    data class Layer(
        val depth: Int,
        val gid: Int? = null,
        val spriteName: String? = null,
        val spriteNameB: String? = null,
        val x: Int = 0,
        val y: Int = 0,
        val isButton: Boolean = false,
        val buttonValue: Int = 0,
        val tooltip: String? = null,
    )

    val layers = ArrayList<Layer>()

    /** `cbgButtonMap`（CBGSETBMAPG 的位图）。 */
    var buttonMapGid: Int? = null

    /**
     * 追加一层并保持图层序。spec: EmueraConsole.cs:645-646（CBG_SetImage）与 :679-680
     * （CBG_SetButtonImage）——`cbgList.Add(item); cbgList.Sort();`，而
     * `ClientBackGroundImage.CompareTo`（:58-65）是 `-zdepth.CompareTo(other.zdepth)`
     * ⇒ 排序结果为 **zdepth 降序**。消费方有两处都依赖这个序：
     * 绘制循环按列表序从深到浅合并（EmueraConsole.cs:1834-1868，取
     * `Math.Max` 的 zdepth 并优先消费列表头部），tooltip 命中取列表序第一个
     * （:1898-1906 的 foreach 首匹配）。升序会让深层先画/浅层先命中 tooltip，
     * 与原版首差落在 zdepth!=0 的多图层场景。
     *
     * 同 depth 的并列序：原版 [List.Sort] 是不稳定排序，C# 侧并列项相对顺序未定义；
     * 本类用稳定排序（插入序保留）作为并列项的确定化，不构成可证偏离。
     */
    fun add(layer: Layer) {
        layers.add(layer)
        layers.sortByDescending { it.depth }
    }

    /** `CBG_Clear`：清全部并复位按钮图（EmueraConsole.cs:563-575）。 */
    fun clear() {
        layers.clear()
        clearButtonMap()
    }

    /** `CBG_ClearRange`：清 `[zmin,zmax]` 且 depth != 0；`zmin > zmax` 时不动（:577-596）。 */
    fun clearRange(zmin: Int, zmax: Int) {
        if (zmin > zmax) return
        layers.removeAll { it.depth in zmin..zmax && it.depth != 0 }
    }

    /** `CBG_ClearButton`：清按钮图层并复位按钮图（:598-614）。 */
    fun clearButtons() {
        layers.removeAll { it.isButton }
        clearButtonMap()
    }

    /** `CBG_ClearBMap`（:616-621）。 */
    fun clearButtonMap() {
        buttonMapGid = null
    }
}

/**
 * GoldenPipeline 接线助手：给一个 VM 的附属状态播种 [count] 个运行时 Sprite。
 *
 * 基准 673 行 `SPRITEDISPOSEALL=35` 的前置条件（PC 会话在进入 GOLDEN_GVIEW 之前已由游戏
 * 自身创建 35 个非资源 Sprite，资源表为空）。装置侧一行：
 * `seedGoldenRuntimeSprites(store.aux, 35)`。
 */
fun seedGoldenRuntimeSprites(aux: RuntimeAux, count: Int): SpritePool =
    aux.slot("sprite") { SpritePool() }.also { it.seedRuntimeSprites(count) }
