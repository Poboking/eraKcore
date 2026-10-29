package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.font.FALLBACK_MEASURE_FONT_SIZE
import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.FontSidecarReader
import com.eratw.port.core.font.PortFonts
import com.eratw.port.core.font.SLOT_FONT_SIDECAR
import com.eratw.port.core.host.BinaryFileOps
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.GlyphRequest
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.host.compositeGlyphMask
import com.eratw.port.core.host.isSane
import com.eratw.port.core.host.unsignedArgb
import com.eratw.port.core.image.PngDecoder
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.image.WebpDecoder

/**
 * 图形缓冲 / Sprite / CBG 家族（[registerGraphicsMethods]）。**文件所有者：A4**（M3-B 第二波）。
 *
 * 落地名单（51 条）：
 * GCREATE / GCREATEFROMFILE / GCREATED / GDISPOSE / GWIDTH / GHEIGHT / GCLEAR /
 * GFILLRECTANGLE / GSETCOLOR / GGETCOLOR / GSETBRUSH / GGETBRUSH / GSETPEN / GGETPEN /
 * GGETPENWIDTH / GDRAWLINE / GDRAWTEXT / GSETFONT / GGETFONT / GGETFONTSIZE /
 * GGETFONTSTYLE / GGETTEXTSIZE / GDRAWG / GDRAWGWITHMASK / GDRAWGWITHROTATE / GDASHSTYLE /
 * GSAVE / GLOAD / GDRAWSPRITE / SPRITECREATE / SPRITECREATED / SPRITEDISPOSE /
 * SPRITEDISPOSEALL / SPRITEWIDTH / SPRITEHEIGHT / SPRITEPOSX / SPRITEPOSY / SPRITEMOVE /
 * SPRITESETPOS / SPRITEGETCOLOR / SPRITEANIMECREATE / SPRITEANIMEADDFRAME / SETANIMETIMER /
 * CBGSETG / CBGSETSPRITE / CBGSETBUTTONSPRITE / CBGSETBMAPG / CBGCLEAR / CBGCLEARBUTTON /
 * CBGREMOVERANGE / CBGREMOVEBMAP。
 *
 * 状态一律挂 `c.store.aux` 的 `gfx` / `sprite` / `cbg` 槽（[GraphicsPool] / [SpritePool] /
 * [CbgPool]），不用 object 单例。
 *
 * ## M4-C（GLYPH-KEY-SIZE）新增的文本度量面
 *
 * `GGETTEXTSIZE` 与 `GDRAWTEXT` 在 `:core-vm` 里就要按真实 advance 量宽，而依赖方向是
 * `:render -> :core-vm`（单向，core 不许反向 import 渲染层）。**CONTRACT-M4-C §3 sidecar 的唯一
 * 解析实现**（`FontAdvanceTable` / `FontSidecarCatalog` / `FontSidecarReader`）已独立到
 * `com.eratw.port.core.font` 包（见 [FontAdvanceTable] 等 import），本文件只是它的消费方之一；
 * `:render` 的 `com.eratw.port.render.glyph.TextMetrics` 复用同一套表，只在它外面加
 * 「像素 ↔ 半角格」的换算与折行口径 —— 两模块共用一份解析器才不会各自漂移（WIDTH-SINGLE 同源问题）。
 *
 * 原版每个方法开头都有 `Config.TextDrawingMode == WINAPI → CodeEE` 的守卫
 * （如 :6173-6176）。移植版只有软件缓冲一条路径（等价于 TEXTRENDERER），故不再复刻该分支。
 *
 * ## M4-L（K-MEASURE）补上的三条 GDI+ 布局口径
 *
 * `GGETTEXTSIZE` / `GDRAWTEXT` 的测量除「按码位累加 advance」外，还有三条**与字体无关**的
 * 布局规则（2026-10-21 本机 System.Drawing 对拍，逐条读数与调用式见
 * `core-vm/src/test/kotlin/com/eratw/port/core/fn/TextMeasureAuditTest.kt`）：
 * 串按 U+000A 分行取最宽行、每行行尾空白裁掉、`Mn`/`Me` 组合符零步进。
 * 出处仍是那一句 `MeasureString(strValue, font, int.MaxValue, StringFormat.GenericTypographic)`
 * （spec: FunctionMethodCreator.cs:6524 / :6585）—— 规则本身在 GDI+ 里，反编译读不到，只能实测。
 * 竖度量（行高）不在本波能对上，卡点见 [measureHeightPx]。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * （Graphics*Method / Sprite*Method / CBG*Method，行号见各函数 KDoc）+ `UI.Game.Image/GraphicsImage.cs`、
 * `UI.Game.Image/AppContents.cs`、`GameView/EmueraConsole.cs` 的 CBG 段。
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:634-692`。
 *
 * 三个"怪值"的来源（照抄，不修）：
 * - `GSETCOLOR=0`：基准 641 行把参数写成 `GSETCOLOR(10, 5, 5, 0xFF0000)`，而原版签名是
 *   `(id, color, x, y)`（:6285-6286）——颜色被当成 5，x=5、y=0xFF0000 越界 → 返回 0。
 * - `GGETCOLOR=0/0`：`GCLEAR(10, 0x0000FF)` 的 alpha=0，GDI+ 清屏后像素全 0（见 [GraphicsBuffer.clear]）。
 * - `GDISPOSE14=0`：基准 651 行的 14 号缓冲此时**尚未创建**（到 654 行才 GCREATE），
 *   `GetGraphics(14)` 只是登记空条目（AppContents.cs:28-37），IsCreated=false → 0。
 */
internal fun registerGraphicsMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    fun int(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }
    fun str(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }

    // ==================================================================
    // G 家族：缓冲生命周期与状态查询
    // ==================================================================

    // GCREATE：创建缓冲。已创建 → 0；宽高必须在 1..8192（:6821-6846）。
    int("GCREATE", 3, 3) { c, a ->
        val g = readGraphics(c, "GCREATE", a[0])
        if (g.isCreated) {
            0L
        } else {
            val (w, h) = readPoint("GCREATE", a, 1)
            if (w <= 0) throw CodeEE("函数 GCREATE：宽度必须为正数（$w）")
            if (w > GraphicsBuffer.MAX_IMAGE_SIZE) {
                throw CodeEE("函数 GCREATE：宽度超过上限 ${GraphicsBuffer.MAX_IMAGE_SIZE}（$w）")
            }
            if (h <= 0) throw CodeEE("函数 GCREATE：高度必须为正数（$h）")
            if (h > GraphicsBuffer.MAX_IMAGE_SIZE) {
                throw CodeEE("函数 GCREATE：高度超过上限 ${GraphicsBuffer.MAX_IMAGE_SIZE}（$h）")
            }
            g.create(w, h)
            1L
        }
    }

    // GCREATEFROMFILE：从图片文件建缓冲（:6850-6927）。
    // 文件按签名判定为 PNG → PngDecoder 真解码；否则回落 GSAVE 的文本载体（见 readImageFile）。
    // 原版 Utils.LoadImage 任何异常都吞成 null → 0（Utils.cs:502-525；:6896-6925），
    // 所以「不存在 / 坏 PNG / 非法文本载体」一律 0，不升级成 CodeEE。
    // 第 3 参是原版的「不加 ContentDir 前缀」开关（:6883-6895）：**不是**绝对路径开关 ——
    // 相对路径在 flag==0 时必须先接上 `Program.ContentDir`（= `<ExeDir>\resources\`，
    // Program.cs:219-221），见 [contentDirPath]。少了那一步本函数在真机上永远返回 0。
    // webp 已接（[WebpDecoder]：动画与无损按原版口径，有损 1893 个登记为不支持 → 0）；
    // BMP / JPEG / GIF 仍不解码（语料里各有少量，见 BOARD 的图片余量口径）。
    int("GCREATEFROMFILE", 2, 3) { c, a ->
        val g = readGraphics(c, "GCREATEFROMFILE", a[0])
        val trace = c.imageTrace()
        if (g.isCreated) {
            trace.reason = "already-created"
            0L
        } else {
            if (a.size != 2 && a.size != 3) {
                throw CodeEE("函数 GCREATEFROMFILE 的参数个数不匹配（需要 2 或 3 个）：实际 ${a.size} 个")
            }
            val ops = c.host.fileOps ?: throw CodeEE("函数 GCREATEFROMFILE 需要宿主文件能力（fileOps == null）")
            val rawPath = a.size > 2 && iv(a[2]) != 0L
            val loaded = readImageFile(c, ops, contentDirPath(sv(a[1]), rawPath))
            val ok = loaded != null && g.createFromArgb(loaded.width, loaded.height, loaded.pixels)
            if (!ok && loaded != null) trace.reason = "create-rejected=${loaded.width}x${loaded.height}"
            if (ok) 1L else 0L
        }
    }

    // GCREATED / GWIDTH / GHEIGHT / GGETFONTSIZE / GGETFONTSTYLE / GGETPEN / GGETPENWIDTH /
    // GGETBRUSH：共用 GraphicsStateMethod（:6162-6195）。未创建一律 0。
    int("GCREATED", 1, 1) { c, a -> if (readGraphics(c, "GCREATED", a[0]).isCreated) 1L else 0L }
    int("GWIDTH", 1, 1) { c, a -> readGraphics(c, "GWIDTH", a[0]).width.toLong() }
    int("GHEIGHT", 1, 1) { c, a -> readGraphics(c, "GHEIGHT", a[0]).height.toLong() }
    int("GGETFONTSIZE", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETFONTSIZE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            if (g.fontName == null) throw CodeEE("函数 GGETFONTSIZE：缓冲区 ${idOf(g)} 未设置字体（原版此处抛 NullReferenceException）")
            g.fontSize.toLong()
        }
    }
    int("GGETFONTSTYLE", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETFONTSTYLE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            if (g.fontName == null) throw CodeEE("函数 GGETFONTSTYLE：缓冲区 ${idOf(g)} 未设置字体（原版此处抛 NullReferenceException）")
            g.fontStyle.toLong()
        }
    }
    int("GGETPEN", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETPEN", a[0])
        if (!g.isCreated) {
            0L
        } else {
            // 原版 graphicsImage.Pen.Color —— Pen 未设置时是 NullReferenceException（:6189）。
            unsignedArgb(g.penColor ?: throw CodeEE("函数 GGETPEN：缓冲区 ${idOf(g)} 未设置画笔（原版此处抛 NullReferenceException）"))
        }
    }
    int("GGETPENWIDTH", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETPENWIDTH", a[0])
        if (!g.isCreated) {
            0L
        } else {
            if (g.penColor == null) throw CodeEE("函数 GGETPENWIDTH：缓冲区 ${idOf(g)} 未设置画笔（原版此处抛 NullReferenceException）")
            g.penWidth.toLong()
        }
    }
    int("GGETBRUSH", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETBRUSH", a[0])
        if (!g.isCreated) {
            0L
        } else {
            // 原版 ((SolidBrush)graphicsImage.Brush).Color.ToArgb()（:6191）。
            unsignedArgb(g.brushColor ?: throw CodeEE("函数 GGETBRUSH：缓冲区 ${idOf(g)} 未设置画刷（原版此处抛 NullReferenceException）"))
        }
    }

    // GGETFONT：GraphicsStateStrMethod（:6197-6222）。未创建返回 ""。
    // :6219 回读的是 `graphicsImage.Fontname`，而 `Fontname => font.Name`（GraphicsImage.cs:60）
    // = **GDI+ 解析后的家族名**，不是脚本传进来的请求名；两者只在请求名解析不到时分叉
    // （原版那条分支 GDI+ 静默换族，实测回 `Microsoft Sans Serif`）。
    // 移植侧的解析名在 `GSETFONT` 里算好存进 [GraphicsBuffer.fontResolvedName]，
    // 口径与两处与 PC 不同值的登记见 PortFonts.resolveFontName 的 KDoc。
    str("GGETFONT", 1, 1) { c, a ->
        val g = readGraphics(c, "GGETFONT", a[0])
        if (!g.isCreated) {
            ""
        } else {
            if (g.fontName == null) throw CodeEE("函数 GGETFONT：缓冲区 ${idOf(g)} 未设置字体（原版此处抛 NullReferenceException）")
            g.fontResolvedName ?: throw CodeEE("函数 GGETFONT：缓冲区 ${idOf(g)} 未设置字体（原版此处抛 NullReferenceException）")
        }
    }

    // GDISPOSE：已创建 → 释放并返回 1；否则 0（:6930-6952）。
    int("GDISPOSE", 1, 1) { c, a ->
        val g = readGraphics(c, "GDISPOSE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            g.dispose()
            1L
        }
    }

    // GCLEAR：2 参全清 / 6 参清矩形（:7143-7164）。注意 ReadColor 在 IsCreated 判定之前。
    int("GCLEAR", 2, 6) { c, a ->
        val g = readGraphics(c, "GCLEAR", a[0])
        val color = readColor("GCLEAR", a[1])
        when {
            !g.isCreated -> 0L
            a.size == 2 -> {
                g.clear(color)
                1L
            }
            a.size == 6 -> {
                val x = intArg("GCLEAR", a, 2)
                val y = intArg("GCLEAR", a, 3)
                val w = intArg("GCLEAR", a, 4)
                val h = intArg("GCLEAR", a, 5)
                g.clearRect(x, y, w, h, color)
                1L
            }
            else -> throw CodeEE("函数 GCLEAR 的参数个数不匹配（需要 2 或 6 个）：实际 ${a.size} 个")
        }
    }

    // GFILLRECTANGLE：用当前画刷填矩形（:7167-7197）。无画刷时原版用 Config.BackColor（:242）。
    int("GFILLRECTANGLE", 5, 5) { c, a ->
        val g = readGraphics(c, "GFILLRECTANGLE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val rect = readRectangle("GFILLRECTANGLE", a, 1)
            g.fillRect(rect[0], rect[1], rect[2], rect[3], g.brushColor ?: DEFAULT_BACK_COLOR)
            1L
        }
    }

    // GSETCOLOR：签名是 (id, color, x, y)（:6274-6293）。越界 → 0。
    // 原版边界判断把 X 写了两遍、Y 只判上界（:6287），照抄；y<0 在原版会落到
    // Bitmap.SetPixel 的 ArgumentException，这里显式抛 CodeEE 而不是返回 0。
    int("GSETCOLOR", 4, 4) { c, a ->
        val g = readGraphics(c, "GSETCOLOR", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val color = readColor("GSETCOLOR", a[1])
            val (x, y) = readPoint("GSETCOLOR", a, 2)
            if (x < 0 || x >= g.width || x < 0 || y >= g.height) {
                0L
            } else {
                if (y < 0) throw CodeEE("函数 GSETCOLOR：y 坐标越界（$y）")
                g.setPixel(x, y, color)
                1L
            }
        }
    }

    // GGETCOLOR：未创建 / 越界 → -1；否则像素 ARGB（:6225-6256）。
    // 原版边界判断把 X 写了两遍、Y 只判上界（:6251），照抄。
    int("GGETCOLOR", 3, 3) { c, a ->
        val g = readGraphics(c, "GGETCOLOR", a[0])
        if (!g.isCreated) {
            -1L
        } else {
            val (x, y) = readPoint("GGETCOLOR", a, 1)
            if (x < 0 || x >= g.width || x < 0 || y >= g.height) {
                -1L
            } else {
                if (y < 0) throw CodeEE("函数 GGETCOLOR：y 坐标越界（$y）")
                unsignedArgb(g.pixelAt(x, y))
            }
        }
    }

    // GSETBRUSH：总是 1（画刷颜色允许任意 ARGB，:6309-6323）。
    int("GSETBRUSH", 2, 2) { c, a ->
        val g = readGraphics(c, "GSETBRUSH", a[0])
        if (!g.isCreated) {
            0L
        } else {
            g.brushColor = readColor("GSETBRUSH", a[1])
            1L
        }
    }

    // GSETPEN：保留既有 DashStyle/DashCap（GraphicsImage.cs:469-482），只换颜色与宽度。
    int("GSETPEN", 3, 3) { c, a ->
        val g = readGraphics(c, "GSETPEN", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val color = readColor("GSETPEN", a[1])
            g.penColor = color
            g.penWidth = intArg("GSETPEN", a, 2)
            1L
        }
    }

    // GDRAWLINE：5 参 = id, x1, y1, x2, y2（:6644-6675）。
    int("GDRAWLINE", 5, 5) { c, a ->
        val g = readGraphics(c, "GDRAWLINE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val p1 = readPoint("GDRAWLINE", a, 1)
            val p2 = readPoint("GDRAWLINE", a, 3)
            g.drawLine(p1.first, p1.second, p2.first, p2.second, g.penColor ?: DEFAULT_FORE_COLOR)
            1L
        }
    }

    /**
     * GSETFONT：2..4 参（id, name[, size[, style]]）。
     *
     * 参数形态 = Int, String, Int, Int 且 `OmitStart = 2`（:6331-6344）⇒ **2..4**
     * （个数判据是 `arguments.Count >= OmitStart`，FunctionMethod.cs:183 —— 不是「槽数减 OmitStart」）。
     * 第 2 参（字体名）在 :6359 取、第 3 参（字号）在 :6360 无条件取、第 4 参（样式位）只在
     * `arguments.Count > 3` 时于 :6362-6381 取（且只读 1/2/4/8 四个位，其余位丢弃）。
     * 所以 2 参在原版**解析期合法**、运行期撑到 :6360 的 `arguments[2]` 才抛
     * `ArgumentOutOfRangeException`；移植侧没有崩溃通道，由 [intArg] 抛 CodeEE。
     * CMP-70 裁决（C# 实参表为权威）把注册下界从 3 降回 2。
     *
     * **字号的合法区间 = 严格正数，上界是 `Single.MaxValue`，不是任何 Int 边界**
     * （对拍 j-gfxaudit 2026-10-20，GDI+ 实测见下）。原版的链路是：
     * - :6360 `long intValue = arguments[2].GetIntValue(exm);` —— **没有任何范围检查**（既不是
     *   `ReadPoint` 也不是 `ArgIsOutOfRange`，直接取 long）；
     * - :6385-6402 先在 `GlobalStatic.Pfc.Families` 里逐名精确比对，命中就用
     *   `new Font(fontFamily, intValue, fontStyle, GraphicsUnit.Pixel)`（:6394），
     *   一个都不命中就落到 `new Font(strValue, intValue, fontStyle, GraphicsUnit.Pixel)`（:6400）；
     *   后者对**未知字体名不抛异常**，GDI+ 静默回退（实测 `new Font("NoSuchFamilyZZZ", 16f, …)`
     *   得到 Name = `Microsoft Sans Serif`）⇒ 名字再离谱也返回 1；
     * - `Font` 构造对 `emSize` 的判据实测（System.Drawing / .NET，Windows，2026-10-21）：
     *   `emSize = 0` 与 `-1` 抛 `ArgumentOutOfRangeException`（原文提示 emSize 应大于 0 且
     *   小于或等于 `Single.MaxValue`），而 `0.001 / 1 / 16 / 32767 / 32768 / 40000` **全部构造成功**
     *   （`Font.Size` 依次回读 0.001 / 1 / 16 / 32767 / 32768 / 40000）⇒ **32767 不是上界**；
     * - :6404-6406 的空 `catch { return 0L; }` 把上面所有构造异常折成 0。
     *
     * 所以原版只有 `size <= 0 → 0` 这一条静默失败口径。移植版此前多加了一条
     * `size > 32767 → 0`，属**自行加严**：`GSETFONT(10, "黑体", 32768)` 原版 1、移植版 0，
     * 且原版字号一旦设上就能被 `GGETFONTSIZE` 读回（:6187 → `GraphicsImage.cs:62`），
     * 移植版把它挡在门外后读回的是旧值。本波删掉这条自造上界（审计口径：graphics 族大量
     * 形态是「静默返回 0」，但 0 的触发条件必须逐条对上，不许多不许少）。
     *
     * 已知残留分歧（登记不修正，见交付报告 TODO(verify)）：原版 :6360 不做 Int 范围检查，
     * 而这里经 [intArg] 收口 —— 字号绝对值超过 `Int.MAX_VALUE` 时原版仍能构造成功
     * （long → float 无损降级），移植版抛 CodeEE；且原版 `GraphicsImage.cs:62` 的
     * `(int)font.Size` 在 .NET 是**溢出回绕**（emSize 2147483647 → float 2147483648 → 读回
     * -2147483648），JVM 的 `Float.toInt()` 是**饱和**，两侧对「超大字号读回什么」根本不同构。
     * 要对齐得先决定 `GraphicsBuffer.fontSize` 是否改成存 Float 口径，属组长接线。
     */
    int("GSETFONT", 2, 4) { c, a ->
        val g = readGraphics(c, "GSETFONT", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val name = sv(a[1])
            val size = intArg("GSETFONT", a, 2)
            val mask = if (a.size > 3) intArg("GSETFONT", a, 3) else 0
            if (size <= 0) {
                0L
            } else {
                // FontStyle 位掩码（:6361-6381）：Bold=1 Italic=2 Strikeout=4 Underline=8。
                val catalog = fontCatalogOf(c)
                // 「可解析」= 仓内字体表 / 宿主点查命中，或这个名字自己有一张 §3 度量表
                // （原版 :6385-6402 的两分支口径，见 PortFonts.resolveFontName 的 KDoc）。
                val resolvable = PortFonts.isAvailable(name, c.host.installedFonts) || catalog.hasOwnTable(name)
                g.fontName = name
                g.fontResolvedName = PortFonts.resolveFontName(name, resolvable, catalog.fallbackFamily)
                g.fontSize = size
                g.fontStyle = mask and 0xF
                1L
            }
        }
    }

    /**
     * GDRAWTEXT：2 参（id, text）画在 (0,0)，4 参（id, text, x, y）画在指定点。
     *
     * 参数形态 = Int, String, Int, Int 且 `OmitStart = 2`（:6480-6494）→ 允许 2/3/4 参；
     * 原版 3 参时两条绘制分支都不进、但仍然测量并回写 RESULT（:6509-6517），故这里不抛个数错。
     * RESULT 口径：`RESULT_ARRAY[1]` = 宽、`[2]` = 高（:6526-6527），返回值恒 1（:6528）。
     *
     * **测量用的字体来源**（M4-L 路次查实的同分支口径，spec: :6518-6523）：
     * - 缓冲自带 `Fnt` 非空 ⇒ 就用它（:6519 → GraphicsImage.cs:89）；
     * - 为空 ⇒ `new Font(Config.FontName, 100f, GlobalStatic.Console.StringStyle.FontStyle,
     *   GraphicsUnit.Pixel)`（:6522）—— 三个来源分别是**配置项的字体名**、**字面量 100 的字号**、
     *   **控制台现用样式位**。
     *
     * 移植侧此前把「字体名」也取自控制台现用样式（`consoleStyle.fontName`），与原版不同：
     * 脚本一旦执行过控制台 `SETFONT`，原版这条分支仍按 `Config.FontName` 量，移植侧却换成
     * 控制台那个族 ⇒ 度量分叉。现按原版改回**配置条目** `ConfigKey.FontName`
     * （spec: Config.cs:333 的写入、ConfigData.cs:65 的出厂默认「ＭＳ ゴシック」）；
     * 条目现值为空串时才退回控制台现用名，再没有宿主样式快照时退到 [FontSidecarCatalog.DEFAULT_FAMILY]。
     *
     * 注意 `ConfigMethods.store(c)` 的播种口径：宿主没装配置链时 `seedHostState`
     * 会把条目 `FontName` 播成 `consoleStyle.fontName`（core/fn/ConfigMethods.kt 的 seedHostState），
     * 所以「控制台现用名」这条兜底在**没读配置的宿主**上与旧实现同值，不会回退读数；
     * 真语料开局装了 `emuera.config:21`（FONT NAME:黑体）后两者才各自归位。
     *
     * ## 绘制（总账 GFX-08）
     *
     * 原版链路：`graphicsImage.GDrawString(str, x, y)`（:6511 / :6516）→
     * `GraphicsImage.cs:154-188`：取字体（:162-167；缓冲自带 `Fnt` 为空时同一条
     * `new Font(Config.FontName, 100f, Console.StringStyle.FontStyle)`，与测量那份是**同一个**
     * 选择式）→ `StringFormat.GenericTypographic`（:163）→
     * `GraphicsPath.AddString(text, family, style, emSize, new Point(x, y), format)`（:168-170）→
     * `FillPath`（画刷，:172-179）→ `DrawPath`（画笔，:180-187）。
     * 移植侧逐条对齐的口径：
     * - **坐标**：`AddString` 的 origin 是「文本起点」＝首行 em 盒左上角（GDI+ API 契约），
     *   不是基线。本机 GDI+ 实测（2026-10-24，python ctypes 直调 gdiplus.dll 的
     *   `GdipAddPathString` + `GdipFillPath`，再扫 alpha 非 0 像素的包围盒；MS Gothic、
     *   emSize=100、origin=(50,50)）：`A` 的墨迹落在 x 69..115 / y 59..135，
     *   基线 135 ≈ 50 + 0.859em 的 ascent ⇒ origin.Y 是盒顶。移植侧把首行盒顶放在 `y`。
     * - **行距**：同一装置 `A` + U+000A + `B` 的墨迹 y 59..235，两行间隔 100 = emSize
     *   （**不是** `font.Height` 的 114）⇒ 逐行按 emSize 推进。Pixel 单位下
     *   emSize = `font.Height * emHeight / lineSpacing` 回到字号原值（100→100.0），故这里按
     *   `sizePx` 推进；`font.Height` 的取整与 `emHeight/lineSpacing` 那一档见 [measureHeightPx] 的
     *   TODO(verify)（同一条竖度量缺口）。
     * - **换行**：按 U+000A 分行，与测量共用 [splitMeasureLines]（GDI+ 同一套文本布局）。
     * - **裁剪**：GDI+ 只把画布外的部分裁掉、不抛错（同装置 origin=(-20,-20) 只留下画布内的
     *   1284 个像素；origin=(390,50) 完全在画布外时零像素、无异常）。移植侧
     *   [GraphicsBuffer.fillRect] / [GraphicsBuffer.strokeRect] 按 Long 求交后丢弃画布外部分。
     * - **颜色**：填充取 `brush`，未设时 `new SolidBrush(Config.ForeColor)`（:172-179）；
     *   描边取 `pen`，未设时 `new Pen(Config.ForeColor)`（宽 1，:180-187）。两者都吃 alpha
     *   （原版不设 `CompositingMode`，SourceOver 叠加）。
     *
     * ## 替代字形（ADR-005 登记：不复刻字形轮廓）
     *
     * `:core-vm` 没有字体栅格器：§3 sidecar 只握手 advance 与 lsb（[FontAdvanceTable]），
     * 本模块又不许依赖 `:render` 的 glyph 包（依赖方向 `:render -> :core-vm` 单向），
     * [GraphicsBuffer] 类文档也明确「不做字体栅格化」。所以**没接宿主字形通道时**（下一节）
     * 这里落的是**推进盒**：每个码位占
     * `[penX + lsb, penX + lsb + advance) × [行顶, 行顶 + sizePx)`，先按画刷填满、再按画笔描一圈
     * （对应 FillPath + DrawPath 两遍）。**这不是字形轮廓**：像素形状不得当渲染结果宣称，
     * 真机字形由宿主字形通道 [GlyphMaskHostProvider] 提供（M11-E 落地，见下一节）。随之而来的三条口径：
     * - 空白码位（`Character.isWhitespace` / `isSpaceChar`，含 U+0020 / U+3000 / U+0009）不落墨：
     *   真实字体的空格字形没有轮廓，FillPath 也不出像素；
     * - 零 advance（`Mn`/`Me` 组合符，见 [stepOfCodePoint]）不落墨；
     * - 样式位（粗体/斜体/删除线/下划线）对替代字形没有影响（没有轮廓可加粗/倾斜），
     *   原版这些位经 `Font`/`FontStyle` 进 GDI+（:169-170）⇒ 登记为 TODO(verify)。
     * 抗锯齿同样不做（原版 :171 `SmoothingMode.AntiAlias`）：软件缓冲是硬边覆盖。
     *
     * ## 真字形掩码合成（M11-E，[GlyphMaskHostProvider] / [GlyphMaskHost]）
     *
     * 宿主能力探测与 GSAVE 的 `ops as? BinaryFileOps` 同款：
     * `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost`（可选实现，`Builtins.kt` 不改）。
     * 每个**非空白、非零 advance** 的码位先按 [GlyphMaskHost.glyphMask] 取掩码：
     * - 命中（且过 [isSane] 自洽检查）⇒ 走 [compositeGlyphMask] 逐像素合成：
     *   fill = 画刷色按掩码覆盖度预乘混合（对应 FillPath，:172-179），
     *   outline = 掩码边界（按 pen 宽膨胀减自身，penWidth=1 即「膨胀 1 减自身」）
     *   以画笔色满覆盖描（对应 DrawPath，:180-187）；先 fill 后 outline，与原版两遍同序；
     *   合成越界部分按 Long 裁到画布内、不报错（判据④，与推进盒的裁剪同口径）。
     * - 无 provider / host 为 null / 该码位返回 null / 掩码不自洽 ⇒ 落上面的推进盒分支，
     *   与 M11-D 现状逐位一致（判据①，`GdrawTextDrawTest` 8 例零回退）。
     * - [GlyphRequest] 三键与本布局同源逐项一致：`fontName` = 测量用的名字（缓冲请求名，
     *   回退分支为配置字体名），`sizePx` = 逐行推进的 em 字号，`styleMask` = 布局用的样式位。
     * - **定位系**：掩码左上角 = `(⌊penX⌋ + bearingX, lineTop + bearingY)`；笔位推进仍由
     *   §3 advance 决定（advance 不进掩码通道，bearing 只定位移不改变步进）。
     *   bearing/alpha 原点系的完整约定（含「bearingY 正方向向下、参照行盒顶而非基线」）
     *   钉在 `core/host/GlyphMaskHost.kt` 的 [GlyphMask] KDoc，宿主侧（m11e-glyph-platform）
     *   按契约 §5 判据① 与之逐字对齐。
     */
    int("GDRAWTEXT", 2, 4) { c, a ->
        val g = readGraphics(c, "GDRAWTEXT", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val text = sv(a[1])
            // 参数形态（:6480-6494，OmitStart=2）：2 参画在 (0,0)（:6509-6512）、
            // 4 参按 ReadPoint 的坐标画（:6513-6517）；**3 参两条绘制分支都不进**，只测量 + 写 RESULT。
            val origin: Pair<Int, Int>? = when (a.size) {
                2 -> 0 to 0
                4 -> readPoint("GDRAWTEXT", a, 2)
                else -> null
            }
            val bufferFont = g.fontName
            val name = bufferFont ?: configFontName(c)
            val sizePx = if (bufferFont == null) FALLBACK_MEASURE_FONT_SIZE else g.fontSize.toLong()
            // 样式：自带字体用缓冲里那份；回退分支读控制台现用样式位（:6522 的 Console.StringStyle.FontStyle）
            val style = if (bufferFont == null) (c.host.consoleStyle?.fontStyleMask ?: 0).toLong() else g.fontStyle.toLong()
            if (origin != null) {
                // ---- 绘制（GraphicsImage.cs:154-188）：先 FillPath 再 DrawPath ----
                val chain = fontCatalogOf(c).chainFor(name)
                val fillColor = g.brushColor ?: DEFAULT_FORE_COLOR            // :172-179
                val outlineColor = g.penColor ?: DEFAULT_FORE_COLOR           // :180-187
                // `new Pen(color)` 的默认宽度是 1；GSETPEN 过的画笔用它的宽度。
                val outlineWidth = if (g.penColor == null) 1 else g.penWidth
                // 宿主字形通道（M11-E）：可选能力判定，与 GSAVE 的 `ops as? BinaryFileOps` 同款；
                // 没接宿主时 glyphHost = null，每个码位都走推进盒分支，行为与 M11-D 逐位一致。
                val glyphHost = (c.host as? GlyphMaskHostProvider)?.glyphMaskHost
                val glyphReq = GlyphRequest(name, sizePx.toFloat(), style.toInt())
                var lineTop = origin.second.toLong()
                for (line in splitMeasureLines(text)) {
                    var penX = origin.first.toFloat()
                    var i = 0
                    while (i < line.length) {
                        val cp = line.codePointAt(i)
                        i += Character.charCount(cp)
                        val step = stepOfCodePoint(chain, cp, sizePx)
                        // 空白与零 advance 不落墨（见 KDoc「替代字形」）——**接了宿主也不查掩码**，
                        // 空格在真字体里本就没有轮廓，零 advance 码位不给出墨权（判据③）。
                        if (step.pixels > 0f && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                            val mask = glyphHost?.glyphMask(cp, glyphReq)
                            if (mask != null && mask.isSane()) {
                                // ---- 真字形路径：bearing 原点系见 core/host/GlyphMaskHost.kt ----
                                // 掩码左上角 = (⌊penX⌋ + bearingX, lineTop + bearingY)；
                                // 推进量仍用 §3 步进（mask 不参与 advance）。
                                compositeGlyphMask(
                                    g,
                                    Math.floor(penX.toDouble()).toLong() + mask.bearingX.toLong(),
                                    lineTop + mask.bearingY.toLong(),
                                    mask, fillColor, outlineColor, outlineWidth,
                                )
                            } else {
                                // ---- 推进盒回落（无宿主 / 无此字形 / 掩码不自洽，见 KDoc）----
                                val lsb = chain.firstNotNullOfOrNull { it.lsbPixels(cp, sizePx) } ?: 0f
                                val left = Math.floor((penX + lsb).toDouble()).toLong()
                                val right = Math.floor((penX + lsb + step.pixels).toDouble()).toLong()
                                val bottom = lineTop + sizePx
                                // 填充：按 Long 裁到画布（GDI+ 只是裁剪，不报错），宽高必在 Int 内。
                                val fx0 = maxOf(0L, left)
                                val fy0 = maxOf(0L, lineTop)
                                val fx1 = minOf(g.width.toLong(), right)
                                val fy1 = minOf(g.height.toLong(), bottom)
                                if (fx0 < fx1 && fy0 < fy1) {
                                    g.fillRect(
                                        fx0.toInt(), fy0.toInt(),
                                        (fx1 - fx0).toInt(), (fy1 - fy0).toInt(), fillColor,
                                    )
                                }
                                // 描边传**未裁剪**的盒（笔宽贴真边，而不是贴画布边），由缓冲内部裁剪。
                                g.strokeRect(left, lineTop, right, bottom, outlineColor, outlineWidth)
                            }
                        }
                        penX += step.pixels
                    }
                    lineTop += sizePx
                }
            }
            val read = measureTextPixels(c, text, name, sizePx, style)
            c.store.writeInt("RESULT", longArrayOf(1), read.pixels)
            c.store.writeInt("RESULT", longArrayOf(2), read.height)
            1L
        }
    }

    // GGETTEXTSIZE：(text, fontName, size[, style]) —— **不含 gId**（:6537-6550、:6560-6562）。
    // 返回值 = 文本宽度（:6587），高度写 RESULT_ARRAY[1]（:6586）。
    // 度量口径：§3 的 advance 表（设计单元 → `design * size / unitsPerEm` → 每行求和后**一次**截断，
    // 对齐原版 `MeasureString(...)` 返回 float、`(long)` 只截一次，:6585-6587）；
    // 串按 U+000A 分行、行尾空白裁掉、`Mn`/`Me` 组合符零步进 ⇒ 宽度取最宽那一行
    // （三条都是 2026-10-21 本机 GDI+ 实测口径，逐条读数见 `TextMeasureAuditTest`）；
    // 裁尾只作用于整串布局那一档 —— **一行恰好一个码位**时按步进查询处理、不裁
    // （两条通道的分工与 PC `one-space` = 0 的那一档分叉写在 [stepOfCodePoint]）；
    // 缺表 / 缺码位时按 `CellWidth` 的等宽网格确定性退化，退化原因写进 FontSidecarCatalog.lastReason
    // （不许把退化读数当成真实度量宣称）。
    // 高度 = 字号 × 行数：PC 的行高是 `lineSpacing/emHeight`，移植侧没有这三个竖度量字段
    // （§3 只握手 advance 与 lsb）⇒ 见 [measureHeightPx] 的 TODO(verify)。
    int("GGETTEXTSIZE", 3, 4) { c, a ->
        if (a.size != 3 && a.size != 4) {
            throw CodeEE("函数 GGETTEXTSIZE 的参数个数不匹配（需要 3 或 4 个）：实际 ${a.size} 个")
        }
        val text = sv(a[0])                                  // :6560
        val fontName = sv(a[1])                              // :6561
        val sizePx = iv(a[2])                                // :6562（原版是 long，直接喂 float 形参 emSize，无范围检查）
        val style = if (a.size > 3) iv(a[3]) else 0L          // :6564-6583（只取 1/2/4/8 四个位，其余位原版同样忽略）
        if (sizePx <= 0L) {
            // :6584 的 `new Font(name, emSize, …)` 对 emSize <= 0 抛 ArgumentException，且原版这段没有 try/catch
            // （对比 GSETFONT 的 catch → 0，:6404-6406）；移植版没有崩溃通道，按本包既有惯例转成 CodeEE。
            throw CodeEE("函数 GGETTEXTSIZE：字号必须为正数（$sizePx，原版此处 new Font 抛 ArgumentException）")
        }
        val read = measureTextPixels(c, text, fontName, sizePx, style)
        c.store.writeInt("RESULT", longArrayOf(1), read.height)   // :6586
        read.pixels                                               // :6587
    }

    // GDRAWG：10 参（dst, src, dx,dy,dw,dh, sx,sy,sw,sh）或 11 参（+ 颜色矩阵）（:7248-7274）。
    // 第 11 位是 RefInt2D/RefInt3D（:7221 的 (_ArgType)2122 / :7239 的 (_ArgType)2186，
    // 位定义 FunctionMethod.cs:27,39,43）⇒ 与 GDRAWSPRITE 同型登记双路径：
    // 引用路径留 Expr 形态喂 [readColorMatrix]，值路径拿不到数组本体按同口径报 CodeEE。
    r.register(
        BuiltinMethod(
            "GDRAWG",
            I,
            10,
            11,
            evalRef = { c, e -> Value.IntV(gdrawG(c, GdrawGArgs.ofExprs(c, e))) },
            eval = { c, a -> Value.IntV(gdrawG(c, GdrawGArgs.ofValues(c, a))) },
        ),
    )

    // GDRAWGWITHMASK：5 参（dst, src, mask, x, y）。src/mask 尺寸必须相同，且目标区域不得越界（:7312-7344）。
    int("GDRAWGWITHMASK", 5, 5) { c, a ->
        val dst = readGraphics(c, "GDRAWGWITHMASK", a[0])
        if (!dst.isCreated) {
            0L
        } else {
            val src = readGraphics(c, "GDRAWGWITHMASK", a[1])
            if (!src.isCreated) {
                0L
            } else {
                val mask = readGraphics(c, "GDRAWGWITHMASK", a[2])
                if (!mask.isCreated) {
                    0L
                } else if (src.width != mask.width || src.height != mask.height) {
                    // 基准 655 行：src=10(100x60) 与 mask=14(20x20) 尺寸不符 → 0。
                    0L
                } else {
                    val (x, y) = readPoint("GDRAWGWITHMASK", a, 3)
                    if (x + src.width > dst.width || y + src.height > dst.height) {
                        0L
                    } else {
                        dst.blitMasked(src, mask, x, y)
                        1L
                    }
                }
            }
        }
    }

    // GDRAWGWITHROTATE：3 参（dst, src, angle，绕源中心）/ 5 参（+ x, y）（:6614-6641）。
    int("GDRAWGWITHROTATE", 3, 5) { c, a ->
        if (a.size != 3 && a.size != 5) {
            throw CodeEE("函数 GDRAWGWITHROTATE 的参数个数不匹配（需要 3 或 5 个）：实际 ${a.size} 个")
        }
        val dst = readGraphics(c, "GDRAWGWITHROTATE", a[0])
        if (!dst.isCreated) {
            0L
        } else {
            val src = readGraphics(c, "GDRAWGWITHROTATE", a[1])
            if (!src.isCreated) {
                0L
            } else {
                val angle = intArg("GDRAWGWITHROTATE", a, 2).toLong()
                val center = if (a.size == 3) {
                    src.width / 2 to src.height / 2
                } else {
                    readPoint("GDRAWGWITHROTATE", a, 3)
                }
                dst.rotateFrom(src, angle, center.first, center.second)
                1L
            }
        }
    }

    // GDASHSTYLE：3 参（id, dashStyle, dashCap）（:6459-6472）。画笔未设置时原版新建一支默认笔。
    int("GDASHSTYLE", 3, 3) { c, a ->
        val g = readGraphics(c, "GDASHSTYLE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            if (g.penColor == null) {
                g.penColor = DEFAULT_FORE_COLOR
                g.penWidth = 1
            }
            g.dashStyle = intArg("GDASHSTYLE", a, 1)
            g.dashCap = intArg("GDASHSTYLE", a, 2)
            1L
        }
    }

    // GSAVE：2 参（id, index），写 `{Config.SavDir}img{index:0000}.png`（:8105-8132 / :9471-9474）。
    // 载体 = 真 PNG（GraphicsBuffer.toSavePng），与原版 `Bitmap.Save` 按 `.png` 扩展名
    // 选 PNG 编码器一致（:8125 / :9473）。
    // 原版**没有**像素上限、也不抛 CodeEE：整个 Save 包在 try 里，任何异常（含 GDI+ 的
    // OutOfMemoryException）都被 catch 折成 0（:8122-8130）。此处照同一口径：编码/落盘
    // 失败（含 OOM）一律返回 0，不升级成 CodeEE。
    // 宿主未实现 BinaryFileOps（二进制写）时回落旧文本载体，保证 GLOAD 仍可往返；
    // 接线点见该接口的 KDoc。
    int("GSAVE", 2, 2) { c, a ->
        val g = readGraphics(c, "GSAVE", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val index = iv(a[1])
            if (index < 0 || index > Int.MAX_VALUE) {
                0L
            } else {
                val ops = c.host.fileOps ?: throw CodeEE("函数 GSAVE 需要宿主文件能力（fileOps == null）")
                val path = savePath(index.toInt())
                try {
                    val sink = ops as? BinaryFileOps
                    if (sink != null) {
                        if (sink.writeBytes(path, g.toSavePng())) 1L else 0L
                    } else if (ops.writeText(path, g.toSaveText(), append = false)) {
                        1L
                    } else {
                        0L
                    }
                } catch (_: Exception) {
                    0L
                } catch (_: OutOfMemoryError) {
                    0L
                }
            }
        }
    }

    // GLOAD：2 参（id, index）。目标已创建 → 0（基准 649 行的注释即指这条）。
    // 原版读 PNG（:8168-8172 Utils.LoadImage）；这里 PNG 真解码，GSAVE 文本载体继续可用
    // （读取顺序与失败口径见 readImageFile）。
    int("GLOAD", 2, 2) { c, a ->
        val g = readGraphics(c, "GLOAD", a[0])
        if (g.isCreated) {
            0L
        } else {
            val index = iv(a[1])
            if (index < 0 || index > Int.MAX_VALUE) {
                0L
            } else {
                val ops = c.host.fileOps ?: throw CodeEE("函数 GLOAD 需要宿主文件能力（fileOps == null）")
                val loaded = readImageFile(c, ops, savePath(index.toInt()))
                if (loaded != null && g.createFromArgb(loaded.width, loaded.height, loaded.pixels)) 1L else 0L
            }
        }
    }

    // ==================================================================
    // Sprite 家族
    // ==================================================================

    // SPRITECREATE：2 / 6 / 8 / 10 参（:7016-7071）。
    int("SPRITECREATE", 2, 10) { c, a ->
        when (a.size) {
            2, 6, 8, 10 -> Unit
            else -> throw CodeEE("函数 SPRITECREATE 的参数个数不匹配（需要 2/6/8/10 个）：实际 ${a.size} 个")
        }
        val name = sv(a[0])
        if (name.isEmpty()) {
            0L
        } else if (c.sprites().find(name)?.isCreated == true) {
            0L
        } else {
            val g = readGraphics(c, "SPRITECREATE", a[1])
            if (!g.isCreated) {
                0L
            } else {
                var rect = intArrayOf(0, 0, g.width, g.height)
                var destW = g.width
                var destH = g.height
                var px = 0
                var py = 0
                if (a.size >= 6) {
                    rect = readRectangle("SPRITECREATE", a, 2)
                    // 6 参形态 destSize = rect.Size（spec: :7043），函数层不取绝对值，
                    // 兜底在 ASprite 构造器（ASprite.cs:16-23 负 Width/Height 取反）——
                    // 这里就等于把该构造器的行为前移，SrcRectangle 仍保留 rect 原值。
                    destW = kotlin.math.abs(rect[2])
                    destH = kotlin.math.abs(rect[3])
                    if (!intersects(rect, g.width, g.height)) {
                        throw CodeEE("函数 SPRITECREATE：源矩形超出父图像范围")
                    }
                }
                if (a.size >= 8) {
                    px = intArg("SPRITECREATE", a, 6)
                    py = intArg("SPRITECREATE", a, 7)
                }
                if (a.size == 10) {
                    destW = kotlin.math.abs(intArg("SPRITECREATE", a, 8))
                    destH = kotlin.math.abs(intArg("SPRITECREATE", a, 9))
                }
                c.sprites().put(
                    SpritePool.Sprite(
                        name.uppercase(), destW, destH, g,
                        rect[0], rect[1], rect[2], rect[3], px, py,
                    ),
                )
                1L
            }
        }
    }

    // SPRITECREATED / SPRITEWIDTH / SPRITEHEIGHT / SPRITEPOSX / SPRITEPOSY：SpriteStateMethod（:6687-6703）。
    int("SPRITECREATED", 1, 1) { c, a ->
        if (c.sprites().find(sv(a[0]))?.isCreated == true) 1L else 0L
    }
    int("SPRITEWIDTH", 1, 1) { c, a -> c.sprites().find(sv(a[0]))?.let { if (it.isCreated) it.width.toLong() else 0L } ?: 0L }
    int("SPRITEHEIGHT", 1, 1) { c, a -> c.sprites().find(sv(a[0]))?.let { if (it.isCreated) it.height.toLong() else 0L } ?: 0L }
    int("SPRITEPOSX", 1, 1) { c, a -> c.sprites().find(sv(a[0]))?.let { if (it.isCreated) it.posX.toLong() else 0L } ?: 0L }
    int("SPRITEPOSY", 1, 1) { c, a -> c.sprites().find(sv(a[0]))?.let { if (it.isCreated) it.posY.toLong() else 0L } ?: 0L }

    // SPRITEMOVE / SPRITESETPOS：SpriteSetPosMethod（:6720-6740）。缺失或未创建 → 0。
    int("SPRITEMOVE", 3, 3) { c, a ->
        val s = c.sprites().find(sv(a[0]))
        if (s == null || !s.isCreated) {
            0L
        } else {
            val (dx, dy) = readPoint("SPRITEMOVE", a, 1)
            s.posX += dx
            s.posY += dy
            1L
        }
    }
    int("SPRITESETPOS", 3, 3) { c, a ->
        val s = c.sprites().find(sv(a[0]))
        if (s == null || !s.isCreated) {
            0L
        } else {
            val (x, y) = readPoint("SPRITESETPOS", a, 1)
            s.posX = x
            s.posY = y
            1L
        }
    }

    // SPRITEGETCOLOR：缺失/越界 → -1；基图取不到 → Color.Transparent（0x00FFFFFF = 16777215）。
    // ASpriteSingle.SpriteGetColor（ASpriteSingle.cs:50-64）。
    int("SPRITEGETCOLOR", 3, 3) { c, a ->
        val s = c.sprites().find(sv(a[0]))
        if (s == null || !s.isCreated) {
            -1L
        } else if (s.isAnime) {
            // SpriteAnime.SpriteGetColor 直接抛 NotSupportedException（SpriteAnime.cs:132-135）。
            throw CodeEE("函数 SPRITEGETCOLOR：动画 Sprite 不支持取色（原版抛 NotSupportedException）")
        } else {
            val (x, y) = readPoint("SPRITEGETCOLOR", a, 1)
            if (x < 0 || x >= s.width || y < 0 || y >= s.height) {
                -1L
            } else {
                val buf = s.buffer
                val sx = x + s.srcX
                val sy = y + s.srcY
                if (buf == null || !buf.isCreated || sx < 0 || sx >= buf.width || sy < 0 || sy >= buf.height) {
                    unsignedArgb(TRANSPARENT)
                } else {
                    unsignedArgb(buf.pixelAt(sx, sy))
                }
            }
        }
    }

    // SPRITEDISPOSE：缺失/未创建 → 0（:7083-7093）。
    int("SPRITEDISPOSE", 1, 1) { c, a ->
        val s = c.sprites().find(sv(a[0]))
        if (s == null || !s.isCreated) {
            0L
        } else {
            c.sprites().dispose(sv(a[0]))
            1L
        }
    }

    // SPRITEDISPOSEALL：返回释放个数（AppContents.cs:66-78）。
    int("SPRITEDISPOSEALL", 1, 1) { c, a -> c.sprites().disposeAll(iv(a[0]) != 0L) }

    // SPRITEANIMECREATE：3 参（name, w, h），尺寸 1..8192（:7481-7516）。
    int("SPRITEANIMECREATE", 3, 3) { c, a ->
        val name = sv(a[0])
        if (name.isEmpty()) {
            0L
        } else if (c.sprites().find(name)?.isCreated == true) {
            0L
        } else {
            val (w, h) = readPoint("SPRITEANIMECREATE", a, 1)
            if (w <= 0) throw CodeEE("函数 SPRITEANIMECREATE：宽度必须为正数（$w）")
            if (w > GraphicsBuffer.MAX_IMAGE_SIZE) {
                throw CodeEE("函数 SPRITEANIMECREATE：宽度超过上限 ${GraphicsBuffer.MAX_IMAGE_SIZE}（$w）")
            }
            if (h <= 0) throw CodeEE("函数 SPRITEANIMECREATE：高度必须为正数（$h）")
            if (h > GraphicsBuffer.MAX_IMAGE_SIZE) {
                throw CodeEE("函数 SPRITEANIMECREATE：高度超过上限 ${GraphicsBuffer.MAX_IMAGE_SIZE}（$h）")
            }
            c.sprites().put(SpritePool.Sprite(name.uppercase(), w, h, frames = ArrayList()))
            1L
        }
    }

    // SPRITEANIMEADDFRAME：9 参（name, gid, x, y, w, h, px, py, delay）（:7539-7577）。
    int("SPRITEANIMEADDFRAME", 9, 9) { c, a ->
        val name = sv(a[0])
        val s = if (name.isEmpty()) null else c.sprites().find(name)
        if (name.isEmpty()) {
            0L
        } else if (s == null) {
            0L
        } else {
            // 原版 `spriteAnime == null && !spriteAnime.IsCreated` 在"存在但不是动画"时抛
            // NullReferenceException（:7555）；这里改成 CodeEE，避免崩溃但不掩盖误用。
            if (!s.isAnime) throw CodeEE("函数 SPRITEANIMEADDFRAME：Sprite \"$name\" 不是动画 Sprite（原版此处抛 NullReferenceException）")
            val g = readGraphics(c, "SPRITEANIMEADDFRAME", a[1])
            if (!g.isCreated) {
                0L
            } else {
                val r = readRectangle("SPRITEANIMEADDFRAME", a, 2)
                if (r[2] <= 0 || r[3] <= 0 || r[0] < 0 || r[0] + r[2] > g.width ||
                    r[1] < 0 || r[1] + r[3] > g.height
                ) {
                    0L
                } else {
                    val (ox, oy) = readPoint("SPRITEANIMEADDFRAME", a, 6)
                    val delay = intArg("SPRITEANIMEADDFRAME", a, 8).toLong()
                    if (delay <= 0 || delay > Int.MAX_VALUE) {
                        0L
                    } else {
                        // AnimeFrame.Normalize(DestBaseSize)（SpriteAnime.cs:24-36）：
                        //   rectangle = Intersect(Rectangle(Offset, SrcRectangle.Size),
                        //                         Rectangle(Point.Empty, DestBaseSize));
                        //   Offset = rectangle.Location; SrcRectangle.Width/Height = rectangle.Size
                        // 注意 **SrcRectangle.X/Y 不动**，只有 Offset 与宽高被回写。
                        //
                        // 上面那条范围检查只保证「源矩形在**父图像**内」，与 `DestBaseSize`
                        // 是两个不同的框，所以 Normalize **不是**空操作。语料实证：
                        // `l13/留琴/留琴-兔女郎/anime.xml` 的
                        // `param="0,0,752,752,0,80,42"` —— 752×752 的帧放在 y=80，
                        // 底边到 832 已越过 752 高的 DestBaseSize，原版裁成 672 高；
                        // `颜.xml` 的 `param="210,0,360,360,0,40,42"` 同理裁成 320。
                        // 不裁就是每帧往下多画 80/40px，压到下一行。
                        val ix = maxOf(ox, 0)
                        val iy = maxOf(oy, 0)
                        val ir = minOf(ox + r[2], s.width)
                        val ib = minOf(oy + r[3], s.height)
                        val iw = ir - ix
                        val ih = ib - iy
                        // 交集为空 ⇒ 原版把 `BaseImage` 置 null（:29-30）：这一帧**不画**，
                        // 但 delay 照进 totaltime（:78），时间片还在。用 srcW/srcH = 0 表示
                        // 「这帧没有像素」：SpriteBitmapSource.spriteFrame 对非正子矩形返回
                        // null（不画），而 SpriteAnimeTimeline.delaysOf 只读 delayMs，
                        // 帧表与总时长都不受影响 —— 与原版「帧在表里、但不画」等价。
                        s.frames!!.add(
                            if (iw <= 0 || ih <= 0) {
                                SpritePool.Frame(g, r[0], r[1], 0, 0, 0, 0, delay.toInt())
                            } else {
                                SpritePool.Frame(g, r[0], r[1], iw, ih, ix, iy, delay.toInt())
                            },
                        )
                        1L
                    }
                }
            }
        }
    }

    // SETANIMETIMER：1 参，范围 Int.MIN..32767，越界抛 CodeEE（:7924-7933）。
    //
    // 它只是**重绘节奏**，不是帧间隔：原版 SetAnimeTimerMethod 把参数交给
    // Console.setRedrawTimer（FunctionMethodCreator.cs:7931），后者 <=0 关定时器、<10 抬到 10
    // （EmueraConsole.cs:967-980），定时器到点只做 window.Refresh（:959-965）。
    // 帧推进另有一路：GetCurrentFrame 按 (now - StartTime) % totaltime 现算
    // （SpriteAnime.cs:90-119），帧间隔来自 SPRITEANIMEADDFRAME 的第 9 参（:72-78）。
    // 读出侧（GFX-07）：platform 的 SpriteBitmapSource.animeRepaintIntervalMs() 读本值，
    // 帧号由 render/sprite 的 SpriteAnimeTimeline.frameAt 算（纯 JVM，时钟由宿主注入）。
    // 本语句只写池、返回值恒 1，语义未动。
    int("SETANIMETIMER", 1, 1) { c, a ->
        val ms = iv(a[0])
        if (ms < Int.MIN_VALUE || ms > 32767) {
            throw CodeEE("函数 SETANIMETIMER：第 1 个参数超出范围（$ms，允许 ${Int.MIN_VALUE}..32767）")
        }
        c.sprites().animeTimerMs = ms.toInt()
        1L
    }

    /**
     * GDRAWSPRITE：2 / 4 / 6 / 7 参（id, name[, x, y | x, y, w, h[, matrix]]）。
     *
     * 权威：`FunctionMethodCreator.cs:7347-7445`（GraphicsDrawSpriteMethod）+
     * `UI.Game.Image/GraphicsImage.cs:246-303`（GDrawCImg 两个重载）+
     * `ASpriteSingle.cs:77-99` / `SpriteAnime.cs:148-172`（真正决定像素落在哪的两个 GraphicsDraw）。
     * 总账 GFX-01：本函数此前只做参数校验、不做合成，语料里的换装图/身体图在屏上空白。
     *
     * ## 逐形态（实参个数只允许 2/4/6/7，spec: :7352-7400 四份 ArgTypeList、:7422-7444 按 Count 分支）
     *
     * 四种形态**共用同一句「缺省目标矩形」**：`(0, 0, sprite.DestBaseSize.W, sprite.DestBaseSize.H)`
     * （spec: :7421）—— 注意是 DestBaseSize（缩放后的目标尺寸），不是基图/源矩形尺寸。
     * - 2 参：矩形原样交给 GDrawCImg（:7422-7425）。点位来自 sprite 自己的 `DestBasePosition`
     *   （移植侧 = [SpritePool.Sprite.posX]/[SpritePool.Sprite.posY]），在
     *   `ASpriteSingle.cs:81-82` 那一步生效（此时 destRect.Width == DestBaseSize.Width ⇒ 偏移就是 posX/posY 原值）；
     * - 4 参：`ReadPoint(args, 2)` 只改 X/Y，**宽高仍是 DestBaseSize**（:7427-7433）；
     * - 6 参：`ReadRectangle(args, 2)` 整体替换矩形（:7435-7439）；
     * - 7 参：先 `ReadRectangle`、再 `ReadColormatrix(args, 6)`（:7441-7444），矩阵走
     *   [readColorMatrix] + [GraphicsBuffer.applyColorMatrix]。
     * 找不到 sprite / `IsCreated` 为假的返回码保持原样 0（:7412-7420）。
     *
     * ## 矩形到源区域的换算（GFX-01 的核心，逐行核对）
     *
     * 静态 sprite（SpriteG/SpriteF 同族，`ASpriteSingle.cs:77-99`）：
     * ```
     * if (!DestBasePosition.IsEmpty) {                  // :79 —— Point.IsEmpty 即 X==0 && Y==0
     *     destRect.X += DestBasePosition.X * destRect.Width  / DestBaseSize.Width   // :81
     *     destRect.Y += DestBasePosition.Y * destRect.Height / DestBaseSize.Height  // :82
     *     destRect.Width  = destRect.Width  * SrcRectangle.Width  / DestBaseSize.Width  // :83
     *     destRect.Height = destRect.Height * SrcRectangle.Height / DestBaseSize.Height // :84
     * }
     * g.DrawImage(Bitmap, destRect, SrcRectangle, GraphicsUnit.Pixel)               // :86 / :98
     * ```
     * 三条容易被「图形学常识」写歪的点：
     * 1. **pos 非 0 时矩形宽高会被重算**成 `w * srcW / destW`（源矩形比 DestBaseSize 小 ⇒ 画出来更小），
     *    pos 为 0 时**完全不重算**（同一张图两种走法，:79 的守卫是原版的，不是漏写）；
     * 2. X/Y 的偏移用的是**重算前**的 destRect.Width/Height（:81-82 在 :83-84 之前）；
     * 3. 四个式子在 C# 里全是 `int` 乘法 + 整除（`System.Drawing.Rectangle` 的四项都是 int，
     *    反编译里没有浮点参与）⇒ 取整口径 = **向零截断**，中间积溢出按 Int 回绕。
     *    JVM 的 Int 乘除与之一致，所以这几步**不许升 Long**（升了就在极大矩形上分叉）。
     *    契约 §4 L1.3 提的 `Utils.cs:60-71` 是 `MixedNum.ToPixelf`（字号/百分比 → 像素的**浮点**换算，
     *    服务 HTML/CSS 那一族尺寸），与本条矩形换算无关；这里唯一的取整出处就是 :81-84 的整除。
     *    矩形内部的**取样**取整沿用 [GraphicsBuffer.blitFrom] 既有口径（最近邻、`i * sw / dw` 整除，
     *    GDI+ 实为双线性；差异属契约 §4 A4 的像素级豁免，`dotnet` 对拍读数见 GraphicsMethodsTest 的
     *    「GDRAWG 源矩形越界」用例）。
     *
     * 动画 sprite（`SpriteAnime.cs:148-172`）换算式**没有** IsEmpty 守卫、且宽度用的是**帧**的源尺寸：
     * `X += (posX + frame.offsetX) * w / DestBaseSize.W`（:153）、`W = frame.srcW * w / DestBaseSize.W`（:155）。
     *
     * ## 取帧口径（本路只定「画哪一帧」，定时器归总账 GFX-07）
     *
     * 原版 `GetCurrentFrame()` 读挂钟（`DateTime.Now`，`SpriteAnime.cs:96-117`）：
     * `lastFrame == -1` 时取 `FrameList[0]` 并把 `StartTime` 置为当下，之后按
     * `(now - StartTime) 毫秒 % totaltime` 逐帧减 delay 选中那一帧；`totaltime <= 0`（没有任何帧）
     * 返回 null ⇒ 一个像素都不画（:92-95 与 :140/:151 的空守卫）。
     * 移植侧没有可复现的挂钟、且 `SETANIMETIMER` 写下的 `animeTimerMs` 全仓无读者（那是 **GFX-07**，
     * 本路不收），core 层也不许引入不确定量（AGENTS §2 铁律 4、契约 §0.6）⇒ **当前实现恒取第 0 帧**，
     * 与原版「首帧」（:100-101 的 `FrameList[0]`）一致；帧序号/挂钟推进等 GFX-07 一并接线。
     * 绘制侧早就是同一个口径（`:platform` 的 `SpriteBitmapSource.spriteFrame` 取
     * `frames.firstOrNull()`、空列表与基图未创建都当「不画」，platform/image/SpriteBitmapSource.kt:164-172，
     * 本路只读引用它、不改那个文件）⇒ 两侧不会因为取帧而分叉。
     * 空帧列表按原版返回码 1、不写像素（:92-95）。
     *
     * ## 合成（叠加还是覆盖、alpha=0 落什么）
     *
     * 原版两个 GDrawCImg 重载都落到 `g.DrawImage(位图, destRect, srcRect, Pixel[, attr])`
     * （`ASpriteSingle.cs:86/:98`），且全仓没有任何 `Graphics.CompositingMode` 赋值点
     * （`reference/emuera-cs` 检索无命中）⇒ 取 GDI+ 默认 **SourceOver = 叠加**，不是覆盖；
     * 换算后 alpha=0 的像素**一个不改目标**（2026-10-23 本机 System.Drawing 实测，读数与
     * 口径整段记在 [GraphicsBuffer.applyColorMatrix] 的 KDoc）。合成原语即 [GraphicsBuffer.blitFrom]
     * 的既有 source-over 通道（与 `GDRAWG` 同一条，目标 alpha 处于 0/255 时与 GDI+ 逐通道一致）。
     *
     * ## 不做的部分（有出处，不是漏项）
     *
     * `GraphicsImage.cs:253-287` 的 `drawImgList` 记账是 PC 侧「位图卸载后按列表重放」的优化
     * （`Load()`，GraphicsImage.cs:654-670），它只决定**能不能省掉这份位图**，
     * 第 288 行的 `img.GraphicsDraw(g, destRect)` 无条件执行 ⇒ 对本侧可见像素没有影响；
     * 软件缓冲常驻像素，故不复刻该机制。SpriteG 的基图是另一张 Graphics 缓冲时，
     * 取的就是它此刻的像素（基图后画的部分会跟着变），与原版 `ASpriteSingle.cs:12-22` 同构。
     */
    r.register(
        BuiltinMethod(
            "GDRAWSPRITE",
            I,
            2,
            7,
            // 表达式层（真实脚本调用的唯一入口）走这一支：第 7 位是 RefInt2D/RefInt3D
            // （spec: FunctionMethodCreator.cs:7382 / :7396 的 (_ArgType)2122 / (_ArgType)2186，
            // 位定义 FunctionMethod.cs:27,39,43），必须留 Expr 形态才能拿到数组本体。
            evalRef = { c, e -> Value.IntV(gdrawSprite(c, SpriteArgs.ofExprs(c, e))) },
            // 值路径（宿主/测试直接 callFunction 传 Value）：2/4/6 参照常合成；
            // 7 参的颜色矩阵拿不到引用 ⇒ 由 [readColorMatrix] 按原版 ArgType 报 CodeEE。
            eval = { c, a -> Value.IntV(gdrawSprite(c, SpriteArgs.ofValues(c, a))) },
        ),
    )

    // ==================================================================
    // CBG 家族
    // ==================================================================

    // CBGSETG：4 参（gid, x, y, depth），depth 不得为 0 且须在 Int 范围内（:7665-7684）。
    int("CBGSETG", 4, 4) { c, a ->
        val g = readGraphics(c, "CBGSETG", a[0])
        if (!g.isCreated) {
            0L
        } else {
            val (x, y) = readPoint("CBGSETG", a, 1)
            val depth = checkDepth("CBGSETG", a, 3, 4)
            c.cbg().add(CbgPool.Layer(depth, gid = g.id, x = x, y = y))
            1L
        }
    }

    // CBGSETSPRITE：4 参（name, x, y, depth）；Sprite 缺失/未创建 → 0（:7727-7745）。
    int("CBGSETSPRITE", 4, 4) { c, a ->
        val s = c.sprites().find(sv(a[0]))
        if (s == null || !s.isCreated) {
            0L
        } else {
            val (x, y) = readPoint("CBGSETSPRITE", a, 1)
            val depth = checkDepth("CBGSETSPRITE", a, 3, 4)
            c.cbg().add(CbgPool.Layer(depth, spriteName = s.name, x = x, y = y))
            1L
        }
    }

    // CBGSETBUTTONSPRITE：6 或 7 参（value, nameN, nameB, x, y, depth[, tooltip]）（:7773-7802）。
    // 原版不校验两个 Sprite 是否已创建（CBG_SetButtonImage 也不查，EmueraConsole.cs:666-682）。
    int("CBGSETBUTTONSPRITE", 6, 7) { c, a ->
        if (a.size != 6 && a.size != 7) {
            throw CodeEE("函数 CBGSETBUTTONSPRITE 的参数个数不匹配（需要 6 或 7 个）：实际 ${a.size} 个")
        }
        val value = iv(a[0])
        if (value < 0 || value > 16777215) {
            0L
        } else {
            val nameN = sv(a[1])
            val nameB = sv(a[2])
            val (x, y) = readPoint("CBGSETBUTTONSPRITE", a, 3)
            val depth = checkDepth("CBGSETBUTTONSPRITE", a, 5, 6)
            val tooltip = if (a.size > 6) sv(a[6]) else null
            c.cbg().add(
                CbgPool.Layer(
                    depth,
                    spriteName = c.sprites().find(nameN)?.name ?: nameN.uppercase(),
                    spriteNameB = c.sprites().find(nameB)?.name ?: nameB.uppercase(),
                    x = x, y = y, isButton = true, buttonValue = value.toInt(), tooltip = tooltip,
                ),
            )
            1L
        }
    }

    // CBGSETBMAPG：1 参（gid）。同一张图重复设置 → 0（EmueraConsole.cs:650-664）。
    int("CBGSETBMAPG", 1, 1) { c, a ->
        val g = readGraphics(c, "CBGSETBMAPG", a[0])
        if (!g.isCreated) {
            0L
        } else if (c.cbg().buttonMapGid == g.id) {
            0L
        } else {
            c.cbg().buttonMapGid = g.id
            1L
        }
    }

    // CBGCLEAR / CBGCLEARBUTTON / CBGREMOVEBMAP：恒 1（:7589-7593 / :7627-7631 / :7643-7647）。
    int("CBGCLEAR", 0, 0) { c, _ ->
        c.cbg().clear()
        1L
    }
    int("CBGCLEARBUTTON", 0, 0) { c, _ ->
        c.cbg().clearButtons()
        1L
    }
    int("CBGREMOVEBMAP", 0, 0) { c, _ ->
        c.cbg().clearButtonMap()
        1L
    }

    // CBGREMOVERANGE：2 参（zmin, zmax），恒 1（:7609-7615）。
    int("CBGREMOVERANGE", 2, 2) { c, a ->
        val zmin = intArg("CBGREMOVERANGE", a, 0)
        val zmax = intArg("CBGREMOVERANGE", a, 1)
        c.cbg().clearRange(zmin, zmax)
        1L
    }
}

// ======================================================================
// 参数读取与常量（对照 FunctionMethodCreator.cs:9325-9400）
// ======================================================================

/** 取整数参数。 */
private fun iv(v: Value?): Long = (v as? Value.IntV)?.v ?: throw CodeEE("需要整数参数")

/** 取字符串参数。 */
private fun sv(v: Value?): String = (v as? Value.StrV)?.v ?: throw CodeEE("需要字符串参数")

/**
 * `GCREATEFROMFILE` 的相对路径前缀（spec: `FunctionMethodCreator.cs:6890-6894`
 * `if (!Path.IsPathRooted(text)) text = flag ? strValue : Program.ContentDir + strValue;`）。
 *
 * `Program.ContentDir` = `<ExeDir>\resources\`（spec: `Program.cs:219-221` 的 `SetDirPaths`）；
 * 本移植宿主 `readFileBytes` 的 base 就是游戏根（= ExeDir），所以前缀写成相对段 [CONTENT_DIR]。
 * 缺这一步的后果在真机上量过：`GCREATEFROMFILE RM_TITLE, "title.webp"` 去游戏根找
 * `title.webp`（不存在）⇒ 返回 0、`GCREATED(RM_TITLE)` 恒 0，而
 * `ERB/リソース管理/ResourceManager.ERB:5-10` 的调用方是「没建好就每帧重试」的形状 ⇒
 * 开局无限循环、5,000,001 条指令撞死循环闸（2026-10-21 vivo V2199A 失败屏现场
 * `RM_タイトル画像用意 行7`）。
 *
 * 只作用于 `GCREATEFROMFILE`：`GLOAD` 走的是存档图路径
 * （spec: `FunctionMethodCreator.cs:8135-8172` 的 `saveDataPathGraphics`），全工程
 * `ContentDir` 在函数层只有那一处引用。
 */
private fun contentDirPath(path: String, rawPath: Boolean): String =
    if (rawPath || isRootedPath(path)) path else CONTENT_DIR + path

/** `Path.IsPathRooted` 的 Windows 口径：`/x`、`\x`、`\\server\share`、`C:x`、`C:\x` 都算带根。 */
private fun isRootedPath(path: String): Boolean =
    path.isNotEmpty() && (path[0] == '/' || path[0] == '\\' || (path.length > 1 && path[1] == ':'))

/** 原版的资源目录名（`Program.cs:219` 的 `Path.Combine(ExeDir, "resources")`，尾分隔符自带）。 */
private const val CONTENT_DIR = "resources/"

/** 第 [i] 个整数参数（原版 `arguments[i].GetIntValue`）。 */
private fun intArg(name: String, a: List<Value?>, i: Int): Int {
    if (i >= a.size) throw CodeEE("函数 $name：缺少第 ${i + 1} 个参数")
    val v = iv(a[i])
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) {
        throw CodeEE("函数 $name：第 ${i + 1} 个参数超出 Int 范围（$v）")
    }
    return v.toInt()
}

/** 缓冲编号显示（错误文案用）。 */
private fun idOf(g: GraphicsBuffer): Int = g.id

/**
 * 取图形缓冲（`ReadGraphics`，:9325-9337）。
 *
 * 编号必须 0..Int.MAX_VALUE；负数/过大抛 CodeEE。**不存在的编号也会登记一个空条目**
 * （`AppContents.GetGraphics`，AppContents.cs:28-37），这正是 `GDISPOSE(14)=0` 的来源。
 */
private fun readGraphics(c: BuiltinContext, name: String, v: Value?): GraphicsBuffer {
    val id = iv(v)
    if (id < 0) throw CodeEE("函数 $name：图形编号不能为负（$id）")
    if (id > Int.MAX_VALUE) throw CodeEE("函数 $name：图形编号过大（$id）")
    return c.gfx().get(id.toInt())
}

/** 取颜色（`ReadColor`，:9352-9360）。合法范围 0..0xFFFFFFFF；按 ARGB 原样返回。 */
private fun readColor(name: String, v: Value?): Int {
    val raw = iv(v)
    if (raw < 0 || raw > 0xFFFFFFFFL) {
        throw CodeEE("函数 $name：颜色值超出 ARGB 范围（$raw）")
    }
    return raw.toInt()
}

/** 取坐标对（`ReadPoint`，:9362-9375）。 */
private fun readPoint(name: String, a: List<Value?>, i: Int): Pair<Int, Int> =
    intArg(name, a, i) to intArg(name, a, i + 1)

/** 取矩形（`ReadRectangle`，:9377-9400）。宽高不得为 0。 */
private fun readRectangle(name: String, a: List<Value?>, i: Int): IntArray {
    val x = intArg(name, a, i)
    val y = intArg(name, a, i + 1)
    val w = intArg(name, a, i + 2)
    val h = intArg(name, a, i + 3)
    if (w == 0) throw CodeEE("函数 $name：第 ${i + 3} 个参数不能为 0")
    if (h == 0) throw CodeEE("函数 $name：第 ${i + 4} 个参数不能为 0")
    return intArrayOf(x, y, w, h)
}

/** CBG 深度校验：Int.MIN..Int.MAX 且 != 0（`ArgIsOutOfRangeExcept`，:7678-7681）。 */
private fun checkDepth(name: String, a: List<Value?>, i: Int, argNo: Int): Int {
    val v = iv(a[i])
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE || v == 0L) {
        throw CodeEE("函数 $name：第 $argNo 个参数超出范围（$v，允许 ${Int.MIN_VALUE}..${Int.MAX_VALUE} 且不为 0）")
    }
    return v.toInt()
}

/**
 * 矩形是否与 `(0,0,w,h)` 相交（`Rectangle.IntersectsWith`，:7044）。
 *
 * 逐字复刻 .NET 的四项严格比较：`rx < W && 0 < rx + rw && ry < H && 0 < ry + rh`。
 * 注意负宽高时 `Right = rx + rw < rx = Left`，该比较天然按 `[min, max]` 归一化后的
 * 区间判交（如 `(3,1,-2,2)` 与 4x4 父图：3<4 && 0<1 && 1<4 && 0<3 ⇒ true）；
 * 早先的「夹取成 `[max(rx,0), min(rx+rw,W)]` 再比非空」写法对负宽高恒 false，
 * 会把 .NET 放行（随后在 ASprite.cs:16-23 取绝对值）的负尺寸误判为越界。
 */
private fun intersects(r: IntArray, w: Int, h: Int): Boolean {
    return r[0] < w && 0 < r[0] + r[2] && r[1] < h && 0 < r[1] + r[3]
}

// ======================================================================
// GDRAWSPRITE 的私有实现（总账 GFX-01）
// ======================================================================

/**
 * GDRAWSPRITE 的实参读取器（值路径与引用路径共用一份分支代码）。
 *
 * 为什么不能直接用 [readPoint] / [readRectangle]：那两个的入参是**已求值的** `List<Value?>`，
 * 而引用路径手里拿的是 `List<Expr?>`（第 7 位是数组引用，折成值就丢了身份）。
 * 更要紧的是原版的求值顺序：图形编号与 Sprite 名先算，未创建 / 取不到就直接 `return 0`，
 * **后面的点位、矩形、颜色矩阵连求值都不发生**（spec: FunctionMethodCreator.cs:7411-7443）；
 * 若把整张实参表提前折成值，`GDRAWSPRITE 999, "没有这个Sprite", 超大数, 0` 就会从原版的
 * 返回 0 变成抛 CodeEE，函数型实参也会多跑一遍。于是这里按槽位惰性取：
 * - 值路径（[ofValues]）直接读现成的 [Value]；
 * - 引用路径（[ofExprs]）读到才算，且每槽缓存 ⇒ 同一槽被读多次只算一次。
 *
 * 三条整数报错与 [intArg] 同式（缺位 / 非整数 / 超出 Int，spec: FunctionMethodCreator.cs:9365-9373），
 * 矩形的宽高非 0 检查与 [readRectangle] 同式（spec: :9390-9398）；名字写死在本类里，
 * 因为 GDRAWSPRITE 是该读取器唯一的消费者（契约 §4 L1 的文件面）。
 */
private class SpriteArgs private constructor(
    private val ctx: EvalContext,
    private val exprs: List<Expr?>?,
    private val values: List<Value?>?,
) {
    /** 实参个数（值路径看值表，引用路径看 Expr 表）。 */
    val size: Int get() = values?.size ?: exprs!!.size

    private val cache: Array<Value?>? = exprs?.let { arrayOfNulls<Value>(it.size) }

    /** 第 [i] 位的值形态（引用路径下惰性求值 + 缓存）。 */
    fun value(i: Int): Value? {
        values?.let { return it[i] }
        val table = cache!!
        table[i]?.let { return it }
        val v = exprs!![i]?.toValue(ctx)
        table[i] = v
        return v
    }

    /** 第 [i] 位的 Expr 形态（只有颜色矩阵那一位用得上；值路径没有 Expr ⇒ null）。 */
    fun expr(i: Int): Expr? = exprs?.get(i)

    fun intAt(i: Int): Int {
        if (i >= size) throw CodeEE("函数 GDRAWSPRITE：缺少第 ${i + 1} 个参数")
        val v = iv(value(i))
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) {
            throw CodeEE("函数 GDRAWSPRITE：第 ${i + 1} 个参数超出 Int 范围（$v）")
        }
        return v.toInt()
    }

    /** `ReadRectangle` 的四个槽（宽高不得为 0）。 */
    fun intRectangle(i: Int): IntArray {
        val x = intAt(i)
        val y = intAt(i + 1)
        val w = intAt(i + 2)
        val h = intAt(i + 3)
        if (w == 0) throw CodeEE("函数 GDRAWSPRITE：第 ${i + 3} 个参数不能为 0")
        if (h == 0) throw CodeEE("函数 GDRAWSPRITE：第 ${i + 4} 个参数不能为 0")
        return intArrayOf(x, y, w, h)
    }

    companion object {
        fun ofExprs(c: EvalContext, e: List<Expr?>): SpriteArgs = SpriteArgs(c, e, null)
        fun ofValues(c: EvalContext, a: List<Value?>): SpriteArgs = SpriteArgs(c, null, a)
    }
}

/**
 * `GraphicsDrawSpriteMethod.GetIntValue` 的函数体（spec: FunctionMethodCreator.cs:7405-7445）。
 *
 * 求值顺序逐行照抄：图形编号（:7411）→ 未创建就 0（:7412-7415）→ Sprite 名（:7416）→
 * 取不到或未创建就 0（:7417-7420）→ 按实参个数读点位/矩形（:7422-7441）→
 * 最后才读颜色矩阵（:7442）→ 合成 → 1。
 */
private fun gdrawSprite(c: BuiltinContext, args: SpriteArgs): Long {
    val n = args.size
    when (n) {
        2, 4, 6, 7 -> Unit
        else -> throw CodeEE("函数 GDRAWSPRITE 的参数个数不匹配（需要 2/4/6/7 个）：实际 $n 个")
    }
    val g = readGraphics(c, "GDRAWSPRITE", args.value(0))
    if (!g.isCreated) {
        return 0L
    }
    val s = c.sprites().find(sv(args.value(1)))
    if (s == null || !s.isCreated) {
        return 0L
    }
    // spec: :7421 —— 缺省矩形 = (0, 0, DestBaseSize)；Sprite.width/height 就是 DestBaseSize
    var dx = 0
    var dy = 0
    var dw = s.width
    var dh = s.height
    var matrix: FloatArray? = null
    when (n) {
        2 -> Unit // :7422-7425
        4 -> {
            // :7429-7432 —— ReadPoint 只改 X/Y，宽高保持缺省矩形
            dx = args.intAt(2)
            dy = args.intAt(3)
        }
        6 -> {
            // :7437
            val r = args.intRectangle(2)
            dx = r[0]
            dy = r[1]
            dw = r[2]
            dh = r[3]
        }
        else -> {
            // :7441-7443（矩阵在矩形之后读，顺序照抄）
            val r = args.intRectangle(2)
            dx = r[0]
            dy = r[1]
            dw = r[2]
            dh = r[3]
            matrix = readColorMatrix("GDRAWSPRITE", c, args.expr(6))
        }
    }
    drawSpriteTo(g, s, dx, dy, dw, dh, matrix)
    return 1L
}

/**
 * 把矩形换算成「源缓冲 + 源矩形 + 目标矩形」后交给 [GraphicsBuffer.blitFrom]。
 *
 * 换算式与取整口径逐行见 `GDRAWSPRITE` 注册处的 KDoc（静态走 ASpriteSingle.cs:77-99，
 * 动画走 SpriteAnime.cs:148-172）；这里的每一步都**刻意保持 Int 运算**，
 * 与 C# `System.Drawing.Rectangle` 的 int 乘除同构（含向零截断与溢出回绕）。
 */
private fun drawSpriteTo(
    dst: GraphicsBuffer,
    s: SpritePool.Sprite,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    matrix: FloatArray?,
) {
    val frames = s.frames
    if (frames != null) {
        // 动画 sprite：totaltime <= 0（没有任何帧）时 GetCurrentFrame 返回 null ⇒ 什么都不画
        // （SpriteAnime.cs:92-95 的空守卫 + :151 的绘制守卫），返回码仍是 1。
        if (frames.isEmpty()) {
            return
        }
        // 取帧口径：恒第 0 帧 = 原版首帧（SpriteAnime.cs:96-101）；挂钟推进归总账 GFX-07。
        val f = frames[0]
        if (!f.buffer.isCreated) {
            return // :151 —— 基图未创建（GDISPOSE 过）的帧不画
        }
        if (s.width == 0 || s.height == 0) {
            // DestBaseSize 允许是 0：SPRITECREATE 的 10 参形态只取绝对值、不查 0
            // （FunctionMethodCreator.cs:7055-7067），而 SpriteAnime.cs:153-156 无 IsEmpty 守卫
            // ⇒ 原版此处除零抛 DivideByZeroException（整只程序崩）。移植侧按本包惯例折成 CodeEE。
            throw CodeEE("函数 GDRAWSPRITE：动画 Sprite \"${s.name}\" 的 DestBaseSize 含 0 维（$w,$h），原版此处抛 DivideByZeroException")
        }
        // SpriteAnime.cs:153-156：**无** IsEmpty 守卫，且宽高超比用的是「帧的源尺寸」
        val nx = x + (s.posX + f.offsetX) * w / s.width
        val ny = y + (s.posY + f.offsetY) * h / s.height
        val nw = f.srcW * w / s.width
        val nh = f.srcH * h / s.height
        dst.blitFrom(f.buffer, nx, ny, nw, nh, f.srcX, f.srcY, f.srcW, f.srcH, matrix)
        return
    }
    // 静态 sprite：isCreated 已在函数头判过（ASpriteSingle.cs:24-34 的基图守卫），
    // 这里只兜住「基图在 SPRITECREATE 之后被 GDISPOSE」的竞态：原版取到 null 位图后
    // 交给 DrawImage 抛 NullReferenceException（ASpriteSingle.cs:12-22），移植侧什么都不画。
    val src = s.buffer ?: return
    var nx = x
    var ny = y
    var nw = w
    var nh = h
    if (s.posX != 0 || s.posY != 0) {
        // ASpriteSingle.cs:79 的 Point.IsEmpty 守卫：pos 全 0 时**整段跳过**（宽高也不重算）
        if (s.width == 0 || s.height == 0) {
            throw CodeEE("函数 GDRAWSPRITE：Sprite \"${s.name}\" 的 DestBaseSize 含 0 维而偏移不为 0，原版此处抛 DivideByZeroException")
        }
        // :81-82 用的是重算**前**的 nw/nh
        nx += s.posX * nw / s.width
        ny += s.posY * nh / s.height
        // :83-84
        nw = nw * s.srcW / s.width
        nh = nh * s.srcH / s.height
    }
    dst.blitFrom(src, nx, ny, nw, nh, s.srcX, s.srcY, s.srcW, s.srcH, matrix)
}

/** `Lang.Error.InvalidColorMatrix` 的 zhs 表形（lang.emuera-zhs.xml:936，调用式 FunctionMethodCreator.cs:9425 / :9448 / :9452）。 */
private fun invalidColorMatrix(name: String, a: Long, b: Long): String =
    "${name}函数: ColorMatrix的要素($a, $b)不恰当或者大小不满足5x5"

/**
 * `ReadColormatrix`（spec: FunctionMethodCreator.cs:9402-9464）。
 *
 * 装载期形态门：第 7 位的 ArgType 是 `(_ArgType)2122` / `(_ArgType)2186`
 * （:7382 / :7396），按 `FunctionMethod.cs:27,39,43` 的位定义解出来就是
 * **RefInt2D | AllowConstRef** 与 **RefInt3D | AllowConstRef** ⇒ 只有二维/三维整数数组
 * 变量的引用能进这一位；运行期再按 `IsArray2D`（:9406-9435）/ `IsArray3D`（:9436-9462）分两支，
 * 两支都不成立时原版留一份「行全是 null」的 float[5][] 给 ColorMatrix（空引用），
 * 这里按本包惯例折成 CodeEE。
 *
 * 元素换算：**每个元素 `/ 256f`**（spec: :9432 与 :9459）⇒ 脚本侧的 256 就是 1.0，
 * 单位阵写成对角 256、其余 0（语料实况
 * `ERB/ステータス表示関連/グラフィック特殊効果.ERB:288-295` 的「夜」预设正是
 * 对角 90/90/200/256/256）。返回值是 25 个 Float 的**行主序**表，`m[源通道*5 + 目标通道]`，
 * 通道顺序与夹取/舍入口径见 [GraphicsBuffer.applyColorMatrix]（含 2026-10-23 的实测读数）。
 *
 * 下标求值顺序照抄原版：先 `ReadRectangle`（:7441）再读这里，本函数内部按
 * `Index1..Index3` 的顺序求值（:9413-9421 / :9443-9445）。
 *
 * @param argNo 矩阵位在调用侧的第几个参数（1 基，只用于报错文案）：
 *   `GDRAWSPRITE` 是第 7 个（:7382 / :7396），`GDRAWG` 是第 11 个（:7266-7272 的
 *   `ReadColormatrix(base.Name, exm, arguments, 10)`）。
 */
private fun readColorMatrix(name: String, c: BuiltinContext, e: Expr?, argNo: Int = 7): FloatArray {
    val ref = e as? VarRef ?: throw CodeEE(
        "函数 $name：第 $argNo 个参数必须是二维或三维整数数组变量的引用" +
            "（原版 ArgType RefInt2D/RefInt3D 加 AllowConstRef，FunctionMethod.cs:27,39,43；" +
            "运行期按 VariableTerm 强制转换取数组本体，FunctionMethodCreator.cs:9404）",
    )
    val info = c.store.resolveVarInfo(ref.name)
        ?: throw CodeEE("函数 $name：颜色矩阵变量 ${ref.name} 未定义")
    val rank = info.dimension
    if (rank != 2 && rank != 3) {
        throw CodeEE(
            "函数 $name：颜色矩阵变量 ${ref.name} 是 $rank 维，原版只接受二维与三维数组" +
                "（FunctionMethodCreator.cs:9406 / :9436）",
        )
    }
    val chara = info.isCharacterData
    if (chara && rank == 3) {
        // spec: :9436-9441 —— 角色数据的三维数组原版直接 NotImplCodeEE。
        throw CodeEE("函数 $name：角色数据的三维数组不能当颜色矩阵（原版此处 NotImplCodeEE，FunctionMethodCreator.cs:9440）")
    }
    // 角色变量的第 0 维是角色位置（GetArrayChara(Index1)，:9413），矩阵基址取 Index2/Index3（:9414-9415）；
    // 全局数组则 Index1/Index2 就是矩阵基址（:9419-9421）。
    val base = if (chara) 1 else 0
    if (ref.indices.size != rank + base) {
        throw CodeEE("函数 $name：颜色矩阵变量 ${ref.name} 需要 ${rank + base} 个下标（实际 ${ref.indices.size}）")
    }
    val idx = LongArray(ref.indices.size) { ref.indices[it].getIntValue(c) }
    val m = FloatArray(GraphicsBuffer.COLOR_MATRIX_SIZE)
    if (rank == 2) {
        val p = idx[base]
        val q = idx[base + 1]
        val len0 = c.store.getLength(ref.name, 0).toLong()
        val len1 = c.store.getLength(ref.name, 1).toLong()
        // spec: :9423 —— 5x5 整块必须在数组内（原版 GetLength 是 CLR 数组的元素维长度，
        // 角色变量同样只算元素维，移植侧 getLength(name, d) 与之一致）
        if (p < 0 || q < 0 || p + 5 > len0 || q + 5 > len1) {
            throw CodeEE(invalidColorMatrix(name, p, q))
        }
        for (i in 0..4) {
            for (j in 0..4) {
                val at = LongArray(idx.size) { k -> idx[k] }
                at[base] = p + i
                at[base + 1] = q + j
                m[i * 5 + j] = c.store.readInt(ref.name, at).toFloat() / 256f
            }
        }
        return m
    }
    val d0 = idx[0]
    val p = idx[1]
    val q = idx[2]
    val len0 = c.store.getLength(ref.name, 0).toLong()
    val len1 = c.store.getLength(ref.name, 1).toLong()
    val len2 = c.store.getLength(ref.name, 2).toLong()
    // spec: :9446-9449 —— 第 0 维单独判（只要求落在数组内，不做 +5 的整块检查），
    // 且这条报错文案填的是 (num2, index) 而不是越界的那一维（:9448 的实参顺序），照抄。
    if (d0 < 0 || d0 >= len0) {
        throw CodeEE(invalidColorMatrix(name, p, q))
    }
    // spec: :9450-9453
    if (p < 0 || q < 0 || p + 5 > len1 || q + 5 > len2) {
        throw CodeEE(invalidColorMatrix(name, p, q))
    }
    for (k in 0..4) {
        for (l in 0..4) {
            m[k * 5 + l] = c.store.readInt(ref.name, longArrayOf(d0, p + k, q + l)).toFloat() / 256f
        }
    }
    return m
}

// ======================================================================
// GDRAWG 的私有实现（与 GDRAWSPRITE 同型的惰性双路径读取器）
// ======================================================================

/**
 * GDRAWG 的实参读取器（值路径与引用路径共用一份分支代码）。
 *
 * 存在理由与 [SpriteArgs] 同构：原版的求值顺序是图形编号先算，未创建就直接
 * `return 0`，**两个矩形连求值都不发生**（spec: FunctionMethodCreator.cs:7252-7262）；
 * 若把整张实参表提前折成值，`GDRAWG 999, 998, X, Y, 0, 5, ...` 就会从原版的
 * 返回 0 变成先抛「宽为 0」的 CodeEE。于是按槽位惰性取：值路径（[ofValues]）读现成的
 * [Value]，引用路径（[ofExprs]）读到才算且每槽缓存。
 *
 * 与 [SpriteArgs] 的两处差别都有出处：1) 报错文案的名字是 GDRAWG（第 11 位的
 * ArgType 是 RefInt2D/RefInt3D，spec: :7221 / :7239）；2) 多一个 [isOmitted] ——
 * 原版 :7266 的 `arguments[10] == null`（`OmitStart = 10` 的省略形态）在两条路径下
 * 分别是「Expr 表第 11 位为 null」与「值表第 11 位为 null」，必须分开看，
 * 不能只查 Expr（否则值路径下被省略的第 11 位会被误判成矩阵位）。
 */
private class GdrawGArgs private constructor(
    private val ctx: EvalContext,
    private val exprs: List<Expr?>?,
    private val values: List<Value?>?,
) {
    /** 实参个数（值路径看值表，引用路径看 Expr 表）。 */
    val size: Int get() = values?.size ?: exprs!!.size

    private val cache: Array<Value?>? = exprs?.let { arrayOfNulls<Value>(it.size) }

    /** 第 [i] 位的值形态（引用路径下惰性求值 + 缓存）。 */
    fun value(i: Int): Value? {
        values?.let { return it[i] }
        val table = cache!!
        table[i]?.let { return it }
        val v = exprs!![i]?.toValue(ctx)
        table[i] = v
        return v
    }

    /** 第 [i] 位的 Expr 形态（只有颜色矩阵那一位用得上；值路径没有 Expr ⇒ null）。 */
    fun expr(i: Int): Expr? = exprs?.get(i)

    /**
     * 第 [i] 位调用侧**没给**（对应 C# `arguments[i] == null`）：
     * 引用路径看 Expr、值路径看 Value —— 两条路径的 nil 形态不同，见类 KDoc。
     */
    fun isOmitted(i: Int): Boolean =
        if (exprs != null) exprs[i] == null else values!![i] == null

    fun intAt(i: Int): Int {
        if (i >= size) throw CodeEE("函数 GDRAWG：缺少第 ${i + 1} 个参数")
        val v = iv(value(i))
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) {
            throw CodeEE("函数 GDRAWG：第 ${i + 1} 个参数超出 Int 范围（$v）")
        }
        return v.toInt()
    }

    /** `ReadRectangle` 的四个槽（spec: :9377-9400，宽高非 0）。 */
    fun rectangle(i: Int): IntArray {
        val x = intAt(i)
        val y = intAt(i + 1)
        val w = intAt(i + 2)
        val h = intAt(i + 3)
        if (w == 0) throw CodeEE("函数 GDRAWG：第 ${i + 3} 个参数不能为 0")
        if (h == 0) throw CodeEE("函数 GDRAWG：第 ${i + 4} 个参数不能为 0")
        return intArrayOf(x, y, w, h)
    }

    companion object {
        fun ofExprs(c: EvalContext, e: List<Expr?>): GdrawGArgs = GdrawGArgs(c, e, null)
        fun ofValues(c: EvalContext, a: List<Value?>): GdrawGArgs = GdrawGArgs(c, null, a)
    }
}

/**
 * `GraphicsDrawGMethod.GetIntValue` 的函数体（spec: FunctionMethodCreator.cs:7248-7274）。
 *
 * 求值顺序逐行照抄：dst 图形编号（:7252）→ 未创建就 0（:7253-7256）→
 * src 图形编号（:7257）→ 未创建就 0（:7258-7261）→ 目标矩形（:7261，槽 2）→
 * 源矩形（:7262，槽 6）→ 矩阵分支（:7266-7272）→ 合成 → 1。
 *
 * 矩阵分支的三个走向：10 参或第 11 位被省略（:7266 的两个析取项）⇒ 无矩阵直接贴；
 * 否则读矩阵（:7270）后带矩阵贴（:7271）。引用路径下第 11 位非 null 但不是数组
 * 引用、以及值路径下第 11 位有值（数组身份已折丢），都由 [readColorMatrix] 按
 * 原版 ArgType 报 CodeEE —— 与 GDRAWSPRITE 7 参值路径同一口径。
 */
private fun gdrawG(c: BuiltinContext, args: GdrawGArgs): Long {
    val n = args.size
    if (n != 10 && n != 11) {
        throw CodeEE("函数 GDRAWG 的参数个数不匹配（需要 10 或 11 个）：实际 $n 个")
    }
    val dst = readGraphics(c, "GDRAWG", args.value(0))
    if (!dst.isCreated) {
        return 0L
    }
    val src = readGraphics(c, "GDRAWG", args.value(1))
    if (!src.isCreated) {
        return 0L
    }
    val d = args.rectangle(2)
    val s = args.rectangle(6)
    if (n == 10 || args.isOmitted(10)) {
        dst.blitFrom(src, d[0], d[1], d[2], d[3], s[0], s[1], s[2], s[3])
        return 1L
    }
    val matrix = readColorMatrix("GDRAWG", c, args.expr(10), 11)
    dst.blitFrom(src, d[0], d[1], d[2], d[3], s[0], s[1], s[2], s[3], matrix)
    return 1L
}

/**
 * GCREATEFROMFILE / GLOAD 的统一图片读取。
 *
 * 1. 先取字节（宿主 `BuiltinHost.readFileBytes`，生产实现 `RootedBuiltinHost` 已提供，
 *    见 BootHosts.kt:101-105；不需要给 FileOps 扩二进制接口）；签名是 PNG →
 *    [PngDecoder.decode]；签名是 RIFF/WEBP → [WebpDecoder.decode]
 *    （原版 `Utils.LoadImage` 也是按扩展名分派 webp：Utils.cs:511-515 走 `WebP.Load`，
 *    其余走 `new Bitmap(path)`）。
 * 2. 图像字节不可得 / 签名不匹配 → 回落 GSAVE 文本载体 [GraphicsBuffer.fromSaveText]；
 *    **签名是 webp 但解码失败时不回落文本载体**，直接 null —— 原版对 `.webp` 只试 webp，
 *    失败就是 null（Utils.cs:521-523 的空 catch）。
 *
 * 失败口径照原版 `Utils.LoadImage`：文件不存在 / 解码抛异常 → null → 函数返回 0
 * （Utils.cs:502-525；FunctionMethodCreator.cs:6896-6925、:8168-8198）。因此
 * 「不是 PNG/webp 且文本载体不合法」也返回 0，不抛 CodeEE。
 *
 * webp 的未支持形态（`VP8 ` 有损位流，语料 1893 个：831 裸容器 + 1036 带 ALPH + 26 不带 ALPH）
 * 由 [WebpDecoder] 抛 [com.eratw.port.core.image.WebpDecodeException]，这里同样折成 null → 0；
 * 动画 webp 例外——原版返回的是画布尺寸的空白位图（[WebpDecoder] 已复刻，依据见其 KDoc），所以这里是 1。
 * 语料另有 646 个无损（`VP8L`）文件，容器层支持、位流层当前只实测解通 28 个（缺口登记在
 * [WebpDecoder] 的「已知缺口」一节），失败的同样按 null → 0 收口。
 *
 * GCreateFromF 的 GDI+ 口径（GraphicsImage.cs:119-130）：源位图 `DrawImage` 到**透明**目标，
 * alpha == 0 的源像素在缓冲里是 0x00000000（RGB 被 source-over 丢弃）。GDI+ 实测
 * （System.Drawing，2026-10-21）：RGBA(200,100,50,0) → 0；调色板 tRNS 透明项 0x00RRGGBB → 0。
 * 半透明像素按直通 ARGB 拷贝；GDI+ 预乘往返可能把个别通道减 1（RGBA(1,2,3,128) 的 G 变 1），
 * 像素级差异按契约 §4 A4 不复刻。
 * TODO(verify)：Android 渲染层自绘时的 source-over 口径要与这里的 alpha=0 归零一致。
 */
private fun readImageFile(c: BuiltinContext, ops: FileOps, path: String): PngImage? {
    val trace = c.imageTrace()
    trace.path = path
    val bytes = c.host.readFileBytes(path)
    if (bytes == null) trace.reason = "no-bytes"
    // M10-GFX 宿主解码通道：装了 `ImageDecoderHost`（`:platform` 用 BitmapFactory）时先问它。
    // 口径不动的两条理由：1) 没装时 aux.peek 为 null，本函数与今天逐项相同（语料闸/E2E 走的
    // 就是这条）；2) 装了之后，成功与否仍由下面的同一个 null/非 null 分派决定，GCREATEFROMFILE
    // 与 GLOAD 的返回码分支一个字没改。原版本来就能解 webp（Utils.cs:511-515 走 WebP.Load、
    // :516 走 new Bitmap(path)），所以这条是把「我们自己的解码器解不通」的缺口补上，
    // 不是为复刻失败而失败。alpha==0 归零与下面 png/webp 两支同式。
    val decoder = c.aux.peek<com.eratw.port.core.host.ImageDecoderHost>(com.eratw.port.core.host.IMAGE_DECODER_SLOT)
    if (decoder == null) {
        if (bytes != null) trace.reason = "no-decoder"
    } else if (bytes != null) {
        val decoded = try {
            decoder.decode(path, bytes)
        } catch (e: Exception) {
            trace.reason = "decoder-throw:${e.javaClass.simpleName}"
            null
        }
        if (decoded != null) {
            val px = decoded.pixels
            for (i in px.indices) {
                if ((px[i] ushr 24) == 0) px[i] = 0
            }
            trace.reason = "ok=${decoded.width}x${decoded.height}"
            return decoded
        }
        trace.reason = "decoder-null"
    }
    if (bytes != null && PngDecoder.isPng(bytes)) {
        val png = try {
            PngDecoder.decode(bytes)
        } catch (e: Exception) {
            return null
        }
        val px = png.pixels
        for (i in px.indices) {
            if ((px[i] ushr 24) == 0) px[i] = 0
        }
        return png
    }
    if (bytes != null && WebpDecoder.isWebp(bytes)) {
        // 原版对 webp 只走 libwebp 一条路（Utils.cs:511-515），失败就是 null，不再试别的载体。
        val webp = try {
            WebpDecoder.decode(bytes)
        } catch (e: Exception) {
            trace.reason = "webp-fail:${e.javaClass.simpleName}"
            return null
        }
        val px = webp.pixels
        for (i in px.indices) {
            if ((px[i] ushr 24) == 0) px[i] = 0
        }
        trace.reason = "ok=${webp.width}x${webp.height}"
        return PngImage(webp.width, webp.height, px)
    }
    val text = ops.readText(path)
    if (text == null) {
        if (bytes == null) trace.reason = "no-file" else trace.reason = "no-decoder-chain"
        return null
    }
    val loaded = GraphicsBuffer.fromSaveText(0, text)
    if (loaded == null) {
        trace.reason = "bad-carrier"
        return null
    }
    trace.reason = "ok=${loaded.width}x${loaded.height}"
    return PngImage(loaded.width, loaded.height, loaded.pixels)
}

/** GSAVE / GLOAD 的存档图片路径：`{Config.SavDir}img{index:0000}.png`（:9471-9474）。 */
private fun savePath(index: Int): String {
    val digits = index.toString().padStart(4, '0')
    return "sav/img$digits.png"
}

/** 池访问（每 VM 一份，挂 `c.store.aux`）。 */
private fun BuiltinContext.gfx(): GraphicsPool = aux.slot("gfx") { GraphicsPool() }
private fun BuiltinContext.sprites(): SpritePool = aux.slot("sprite") { SpritePool() }
private fun BuiltinContext.cbg(): CbgPool = aux.slot("cbg") { CbgPool() }

/**
 * `GCREATEFROMFILE` / `GLOAD` 最近一次「取不到像素」的原因，挂在同一个 aux 表上供**宿主回读**。
 *
 * 为什么要有它：函数层按原版口径把一切失败都折成返回 0（`Utils.cs:502-525`），既不抛也不打印，
 * 于是「脚本每帧重试直到撞死循环闸」这种形状在真机上完全没有现场（2026-10-21 vivo V2199A：
 * `RM_タイトル画像用意 行7` 循环 5,000,001 次，宿主侧三项探针却全绿）。本记录不参与任何脚本
 * 可见语义 —— 返回值、异常、控制台输出一个都不改，只多留一个可读的现场。
 *
 * @property reason 最后一次取图的落点（`no-bytes` / `no-decoder` / `decoder-null` /
 *   `png-fail` / `webp-fail` / `no-carrier` / `already-created` / `ok=宽x高`）
 * @property path 该次调用**加完 ContentDir 前缀之后**实际去取的路径
 */
class ImageLoadTrace {
    @Volatile
    var reason: String? = null

    @Volatile
    var path: String? = null
}

/** [ImageLoadTrace] 的槽名（宿主侧用 `store.aux.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)` 回读）。 */
const val IMAGE_LOAD_TRACE_SLOT = "gfx-load-trace"

private fun BuiltinContext.imageTrace(): ImageLoadTrace =
    aux.slot(IMAGE_LOAD_TRACE_SLOT) { ImageLoadTrace() }

/**
 * 一次串级测量的产物（口径见 [measureTextPixels]）。
 *
 * @property pixels 宽度像素（各行求和后**一次**向零截断，取最宽那一行，spec: FunctionMethodCreator.cs:6587）
 * @property height 高度像素 = 字号 × 行数（见 [measureHeightPx] 的竖度量卡点）
 * @property tableFamily 实际取到 advance 表的家族名；全退化时为 null
 * @property degradedCodePoints 有多少个码位没能在任何一张表里取到 advance（不许把这条当 0 忽略）
 * @property styleMask 请求侧样式位，仅用于溯源（当前表没有粗体度量，不影响数值）
 */
private class TextWidthRead(
    val pixels: Long,
    val height: Long,
    val tableFamily: String?,
    val degradedCodePoints: Int,
    val styleMask: Long,
)

/**
 * GDI+ `MeasureString(..., StringFormat.GenericTypographic)` 的行切分（spec 的调用式在
 * FunctionMethodCreator.cs:6524 与 :6585；切分口径 = 2026-10-21 本机 System.Drawing 实测，
 * 黑体 16 与 100 两档，逐条读数登记在 `TextMeasureAuditTest` 的 KDoc 表）。
 *
 * 只按 **U+000A** 分行；串尾那个换行**不**多算一行（`"AB\n"` 与 `"AB"` 同为 1 行：实测宽度都是
 * 16，而 `"AB\n\n"` 的宽度仍是 16 但高度翻一倍 = 2 行）。
 * 空串 ⇒ **0 行**（实测宽 0 / 高 0，任何非空串的高都非 0）。
 * 含不含换行会影响**高度档位**（`"AB"` 高 16.0，`"AB\n"` 高 18.25 —— 行数都是 1），
 * 那一档的口径与移植侧取不到它的原因见 [measureHeightPx]。
 *
 * 其余控制/分隔字符实测都**不**分行，只按各自在字体里的 advance 计宽：
 * U+000B / U+000C / U+000D / U+0085 / U+2028 / U+2029 / U+0009 全部实测 H = 18.25（单行），
 * 其中只有 U+000A 会跳成 36.5（`"AB" + X + "CD"` 的读数，16 字号）。
 * ⇒ 本函数不许把 `\r`、`\v`、`\f`、`U+2028` 当换行。
 *
 * 尾随空白要裁（实测 `"abc   "` = 24 = `"abc"`、`"   "` = 0、前导不裁 `" abc"` = 32），
 * 裁的是**每一行**的行尾（实测 `"AB \nCD"` = 16 = max(`"AB"`→裁后 16, `"CD"`→16)）。
 *
 * 裁剪只属于**整串布局**这条通道，不属于**单码位步进查询**那条通道
 * （见 [measureSingleCodePointStep]）：一行恰好只有一个码位时输入本身就是「这个码位推几像素」
 * 的步进查询，没有行尾可言 ⇒ [trimTrailingBlankEnd] 不参与。
 */
private fun splitMeasureLines(text: String): List<String> {
    if (text.isEmpty()) {
        return emptyList()
    }
    val parts = text.split('\n')
    val last = parts.size - 1
    return if (last > 0 && parts[last].isEmpty()) parts.subList(0, last) else parts
}

/**
 * 行尾裁掉的空白集 —— 实测只有这两个码位被 GenericTypographic 裁掉：
 * **U+0020**（`"abc "` = 24）与 **U+3000**（`"abc　"` = 24，`"  　"` = 0）。
 * 实测**不**裁的：U+00A0（`"abc\u00A0"` = 28.25，非 0 步进）、U+0009（= 24.0078，
 * 步进极小但不是被裁）、U+2000..U+200F 一类排字空格（步进是小数，逐点不同，见测试表）
 * ⇒ 不扩进本集合：把小数步进折成「裁掉」是拿三例凑式子，不是口径。
 */
private fun isTrimmableTrailingBlank(codePoint: Int): Boolean = codePoint == 0x20 || codePoint == 0x3000

/**
 * 行尾裁剪的落点：返回裁完之后该行的**结束下标**（逐码位从行尾往前退，代理对整体退）。
 *
 * 只裁 U+0020 与 U+3000（[isTrimmableTrailingBlank]），裁到空串就返回 0 ——
 * 那是 PC 的 `"   "` = 0 / `"  　"` = 0 两条读数（oracle label `3sp`）。
 * **调用方负责**先排除单码位形态（见 [measureTextPixels] 的通道分工），
 * 否则就把「单码位步进查询」也裁成 0，等于把邻路 `FnWidthSingleSourceTest` 钉的
 * [CellWidth] 半角档（`U+0020` 记 1 格 = `sizePx/2`）改口。
 */
private fun trimTrailingBlankEnd(line: String): Int {
    var end = line.length
    while (end > 0) {
        val tail = Character.codePointBefore(line, end)
        if (!isTrimmableTrailingBlank(tail)) {
            break
        }
        end -= Character.charCount(tail)
    }
    return end
}

/**
 * 一个码位推多少像素，以及它是否走了退化档（表里没有这一行）。
 *
 * 两条通道共用这一份步进算法，差别只在**行尾裁剪**：
 * 整串测量（[measureTextPixels] 的多码位行）先裁尾再逐位累加；
 * 单码位步进查询不裁 —— PC 侧 `" "` 整串实测 0（oracle label `one-space`），
 * 移植侧那一格必须仍等于 [CellWidth] 的档值（邻路 `FnWidthSingleSourceTest` 的
 * `ASCII 与半角假名按半角计` 钉 @18 得 9），两者不是一条通道 ⇒
 * 这一档**有意**比 PC 大 9，登记不修（要修得连邻路的格宽表一起定，属组长接线）。
 */
private class CodePointStep(val pixels: Float, val degraded: Boolean)

private fun stepOfCodePoint(
    chain: List<FontAdvanceTable>,
    codePoint: Int,
    sizePx: Long,
): CodePointStep {
    for (table in chain) {
        val a = table.advancePixels(codePoint, sizePx)
        if (a != null) {
            return CodePointStep(a, degraded = false)
        }
    }
    if (isZeroAdvanceMark(codePoint)) {
        // 组合符不推笔位；这条**不带**下面的 1px 下限，否则 `"e\u0301"` 会得 9 而不是 PC 的 8。
        return CodePointStep(0f, degraded = true)
    }
    // 零字号保护：半角步进是 sizePx/2，字号 0..1 时该乘积会落到 0f，
    // 整串宽度恒 0 ⇒ DebugMiscMethods 的 buildBar 会按「显示宽度不随追加增长」抛 CodeEE
    // （原版只在 GDI 真的量出 0 时才进那一路）。收口前 fn 侧的 measureText 有同一
    // maxOf(1, fontSize/2) 守卫，本函数接管后必须把这 1px 下限带上。
    return CodePointStep(maxOf(1f, CellWidth.ofCodePoint(codePoint) * sizePx.toFloat() / 2f), degraded = true)
}

/**
 * 零步进的组合符：Unicode 的 `Mn`（非间距）与 `Me`（围绕）两类。
 *
 * 实测支持：`"e\u0301"` = 8 = 单个 `e`（组合符并进基字簇；移植侧旧口径按两个码位给 24）、
 * `"e\u0300"` = 8、`"e\u064E"` = 8、`"e\u20D0"` = 8.898438（长截断后仍是 8）、
 * 单独一个组合符 `"X"` = 0、`"X+e"` = 8 ⇒ 「归零」不看它在簇前还是簇后。
 * 间距型 `Mc` **不**归零（U+0903 实测步进 13.65625、U+0BBE 实测 20.85938）。
 *
 * 与 PC 不同的那一类：黑体自己没有的符（U+093C / U+0951 / U+0E31 / U+1AB0 / U+20DD / U+FE00
 * 都是 `Mn`/`Me`，实测步进 9.5078 / 9.5078 / 9.5078 / 16 / 16 / 16）—— GDI+ 落到回退字体的
 * 方框上，于是「非间距」名不副实。移植侧本函数一律归零，读数会比 PC 小一截；
 * 这是**字体表差异**（同 [FontSidecarCatalog] 类文档里替代族那条 TODO 同源），
 * 语料里没有这类码位（登记不修正，逐点读数见 TextMeasureAuditTest 的组合符用例）。
 */
private fun isZeroAdvanceMark(codePoint: Int): Boolean {
    // Character.getType 返回 byte，Java 的 NON_SPACING_MARK/ENCLOSING_MARK 常量也是 byte
    // ⇒ 必须先升成 Int 才能与 Int 形参同型比较。
    val type = Character.getType(codePoint).toInt()
    return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
}

/**
 * 高度：`字号 × 行数`。
 *
 * 原版是 `(long)MeasureString().Height`（spec: FunctionMethodCreator.cs:6527 / :6586），
 * 实测的 PC 结构是「行数 × 该字体的行高」，而行高有两个候选值（黑体，spec 实测）：
 * - **单行且整行落在字体的西文档**（`"ABC"` / `"gpq"` / `"×—━€"`，甚至**全角数字** `"０１２"`）
 *   ⇒ `cellAscent + cellDescent = 220 + 36 = 256 = emHeight` ⇒ 高 = 字号原值（16 → 16.0、100 → 100.0）；
 * - **其余任何情况**（串里有 CJK/全角字母/假名/谚文/emoji/NBSP/组合符，或者串中含换行）
 *   ⇒ 高 = `lineSpacing / emHeight = 292 / 256 = 1.140625` × 字号 × 行数
 *   （16 → 18.25、两行 → 36.5；100 → 114.0625、两行 → 228.125、三行 → 342.1875）。
 *
 * 移植侧的 §3 握手数据只有 `advance` 与 `lsb`（[FontAdvanceTable] 的字段表），
 * 既没有 `lineSpacing`/`cellAscent`/`cellDescent`，也没有「哪个码位落进西文档」的判定表
 * ⇒ 只能取 1.0 这一档，即 `字号 × 行数`。
 *
 * TODO(verify)：**不许**为了对上 `"ABC"` / `"中文字"` / `"AB\nCD"` 三例把 `1.140625` 写死 ——
 * 它是黑体的 `lineSpacing/emHeight`，与随包的 Saitamaar 子集无关（Saitamaar 的 unitsPerEm 1280，
 * 行高比是另一个数），把它当通用系数就是硬凑。卡点与出口：
 * ① §3 sidecar 需要扩 `#lineSpacing` / `#cellAscent` / `#cellDescent` 三个握手字段（生成侧属 c-fontsub，
 *   本路禁改其产物与 render 模块的文件）；② 「落进西文档」需要 PC 的 GDI+ 字体链接表实测，
 *   或按 `CellWidth` 的 CP932 半/全角档近似 —— 近似会在 `１２３`（实测按西文档 16.0）
 *   与 `ＡＢＣ`（实测按 CJK 档 18.25）这类**同一张退化网格里同档**的码位上分叉；
 * ③ 基准 `pc-golden-root.txt` 从未断言过宽高（只有 `:648` 的 `GDRAWTEXT=1`）⇒ 无对拍尺子。
 * 三项都属组长接线。空串 ⇒ 0 行 ⇒ 高 0，这一条与 PC 同值（实测 `"W=0 / H=0"`）。
 */
private fun measureHeightPx(sizePx: Long, lineCount: Int): Long =
    if (lineCount <= 0) 0L else if (lineCount == 1) sizePx else sizePx * lineCount

/**
 * 文本宽度：按 GenericTypographic 的行切分逐行量，取**最宽那一行**；
 * 每行裁掉行尾空白后逐码位累加，优先走 §3 的 advance 表，缺表或缺码位时按等宽网格退化。
 *
 * ## 换算与截断
 *
 * 逐**码位**（代理对合成一个码位）取 `design * sizePx / unitsPerEm`（CONTRACT-M4-C §3 的换算式），
 * 累加用 `Float`（原版 `MeasureString` 返回 `SizeF`，只在最后做 `(long)` 截断，
 * spec: FunctionMethodCreator.cs:6585-6587），因此**整串一次截断**：
 * 两个 advance 5.625 的码位给 11（11.25 截断），不是逐字截断的 10。
 * 多行时先比 float、赢了才截断（实测 `"AB\nC D "` → W=24：第 2 行裁尾后是 `"C D"` = 8+8+8 = 24，
 * 与第 1 行 16 比之后取 24）。
 *
 * ## 为什么按码位而不是按 UTF-16 码元
 *
 * §3 的表按码点出行（一码点一行），而原版的宽度是**整串**交给 GDI+ 量的
 * （spec: FunctionMethodCreator.cs:6585），不存在「按码元切」的口径；
 * `ConsoleStyledString` 用 `Text.Length`/`Substring` 只是段切分，不是测量
 * （spec: ConsoleStyledString.cs:60-62）。
 * 增补平面字符实测按**一个**码位给一个全角步进（`"😀"` = 16 = 1 em，UTF-16 是 2 个码元），
 * 与 [CellWidth] 的 2 格口径同值；**代理项半**（单独一个 U+D800/U+DE00）实测也各算一个全角
 * （各 16），移植侧 [CellWidth.ofCodePoint] 对代理项半同样给 2 格 ⇒ 同值。
 *
 * ## 退化（不许静默冒充真实度量）
 *
 * 表缺失（c-fontsub 成品未接入 / 宿主没给文件口）或某个码位不在 cmap（§3：缺行即缺字）时，
 * 该码位按 [CellWidth]（core 侧唯一等宽网格权威）给 `格数 * sizePx / 2` 像素，
 * 并把可读原因写进 [FontSidecarCatalog.lastReason] / [FontSidecarCatalog.reasonFor]；
 * 计数在 [TextWidthRead.degradedCodePoints] 里如实带上。真实折行/位图那条口径在
 * `:render` 的 glyph 包（`TextMetrics.measureCells` / `measurePixels`），两边不许互相冒充
 * （契约 §0.5）。
 *
 * 退化档里唯一的例外是 [isZeroAdvanceMark]（`Mn`/`Me` 给 0 步进）：那是 Unicode 类别的
 * 定义性事实，不是「凑出来的数」；但它与 PC 只在**基字体自己有这个符**时同值
 * （黑体缺符时 GDI+ 落到回退字体的方框，实测 U+093C 给 9.5078、U+1AB0 给 16、U+20DD 给 16），
 * 这条差异属「字体表差异」，与 [FontSidecarCatalog] 类文档里那条替代族的 TODO 同源。
 */
private fun measureTextPixels(
    c: BuiltinContext,
    text: String,
    fontName: String,
    sizePx: Long,
    styleMask: Long,
): TextWidthRead {
    val catalog = fontCatalogOf(c)
    val chain = catalog.chainFor(fontName)
    val lines = splitMeasureLines(text)
    var widest = 0f
    var degraded = 0
    for (line in lines) {
        var total = 0f
        var i = 0
        // 两条通道分开：一行**恰好一个码位**时输入是步进查询，不裁行尾空白
        // （口径与两侧读数见 stepOfCodePoint 的 KDoc）；多码位行才走 GDI+ 的整串裁尾。
        val end = if (Character.codePointCount(line, 0, line.length) == 1) {
            line.length
        } else {
            trimTrailingBlankEnd(line)
        }
        while (i < end) {
            val codePoint = line.codePointAt(i)
            i += Character.charCount(codePoint)
            val step = stepOfCodePoint(chain, codePoint, sizePx)
            if (step.degraded) {
                degraded++
            }
            total += step.pixels
        }
        if (total > widest) {
            widest = total
        }
    }
    return TextWidthRead(
        pixels = widest.toLong(),
        height = measureHeightPx(sizePx, lines.size),
        tableFamily = chain.firstOrNull()?.family,
        degradedCodePoints = degraded,
        styleMask = styleMask,
    )
}

/** 每 VM 一份的 sidecar 目录（挂 `store.aux`，读取口取宿主 [com.eratw.port.core.host.FileOps]）。 */
private fun fontCatalogOf(c: BuiltinContext): FontSidecarCatalog {
    val catalog = c.aux.slot(SLOT_FONT_SIDECAR) { FontSidecarCatalog() }
    val ops = c.host.fileOps
    catalog.reader = ops?.let { nonNullOps -> FontSidecarReader { path -> nonNullOps.readText(path) } }
    return catalog
}

/**
 * `Config.FontName`（spec: Config.cs:333 的写入、ConfigData.cs:65 的出厂默认「ＭＳ ゴシック」）。
 *
 * `GDRAWTEXT` 未设字体那条分支要的是它（spec: FunctionMethodCreator.cs:6522），
 * 取值走 [ConfigMethods.store] —— 那张条目表是 `Config` 的**唯一**现值来源（M4-K k-configwire 定下），
 * 本处只读不写。取到空串时按原版 `FontFactory` 的归一口径退回控制台现用字体名
 * （spec: StringStyle.cs:26-31），再没有宿主样式快照时退到 [FontSidecarCatalog.DEFAULT_FAMILY]。
 */
private fun configFontName(c: BuiltinContext): String {
    val fromConfig = ConfigMethods.store(c).itemOf(ConfigKey.FontName).string().trim()
    if (fromConfig.isNotEmpty()) {
        return fromConfig
    }
    return c.host.consoleStyle?.fontName ?: FontSidecarCatalog.DEFAULT_FAMILY
}

/** `Color.Transparent.ToArgb()`（0x00FFFFFF）——原版取不到基图像素时的返回值。 */
private const val TRANSPARENT = 0x00FFFFFF

/** 未设置画刷时 `GFILLRECTANGLE` 的回落色（`Config.BackColor`，基准环境 0,0,0）。 */
private val DEFAULT_BACK_COLOR: Int = 0xFF000000.toInt()

/** 未设置画笔时描边/文字的回落色（`Config.ForeColor`，基准环境 192,192,192）。 */
private val DEFAULT_FORE_COLOR: Int = 0xFFC0C0C0.toInt()
