package com.eratw.port.render.view

import com.eratw.port.render.displaylist.InlineGraphic

/**
 * 上屏前的**纯几何**换算（M10-GFX）：`CanvasConsoleView` 里那三段只算数、不碰 `Canvas` 的逻辑。
 *
 * ## 为什么单独一层
 *
 * 绘制侧在 Android 模块（`:render-canvas`），单测跑不了 `View`；而这三段算式正是「立绘/背景
 * 画在对不对」的全部判据。把它们放成纯函数后，`:render` 的 JVM 用例可以直接钉数值，
 * `CanvasConsoleView` 只负责把结果塞进 `Rect` / `RectF` 再 `drawBitmap`。
 * 返回的数组都是**新分配**的小数组：调用方（每帧的 `onDraw`）只在有图形段的行才走到这里，
 * 一行最多几个段，量级远小于同一帧要铺的字形。
 *
 * ## 出处
 *
 * - [coverBox]：`EmueraConsole.cs:947-952` 的 `BakeBackground` ——
 *   `num = W / 基图宽`、`num2 = H / 基图高`、`flag = 基图高 * num < H`、取 `flag ? num2 : num`；
 *   `flag` 与 `num < num2` 逐值等价（两边同除 `基图高`），即**取较大的那个缩放比把短边填满**
 *   （cover，不是 contain），随后 `x = (W - 缩放宽) / 2`、`y = 0`（顶对齐 + 水平居中）。
 * - [sourceRect]：`AppContents.cs:80-105` 的 `CreateSpriteG(name, parent, rect, ...)` ——
 *   sprite 是基图上的一个子矩形；子矩形没给（`srcW`/`srcH` 非正）时按整幅基图画。
 *   这一支**原样返回**子矩形，越界不管 —— 平台无关的纯算式；真正上屏时要用
 *   [sourceRectClipped]（2026-10-21 审计补，理由见该函数）。
 * - [inlineSize] / [inlineBox]：`ConsoleImagePart.cs:66-93` —— 脚本给了正数就用脚本的
 *   （`AndroidGraphicsSink` / `HtmlRunFlatten` 已经把 px 与百分比折算成像素交进来），
 *   没给（非正）就退回基准尺寸。[inlineSize] 是「两个轴各管各的」那个简化形态，
 *   [inlineBox] 才是原版：**高缺省取 `Config.FontSize`、宽缺省按基准宽高比反推**，
 *   两式只在「脚本两个尺寸都给了」时才同值（逐条行号见 [inlineBox]）。
 * - 裁剪这件事在原版是 GDI+ 的画布裁剪：`ConsoleImagePart.cs:159-184` 的 `DrawTo`
 *   把 `destRect` 直接交给 `ASpriteSingle.GraphicsDraw`（`ASpriteSingle.cs:74,86,98`），
 *   源矩形或目标矩形落在基图/窗外时不报错、只是看不见；
 *   `ASpriteSingle.cs:57-63` 的取色那一路是**显式夹**的（越界返回透明）。
 *   文字侧同理：`ConsoleStyledString.cs:119,129,133,138` 是四条 `DrawString`/`DrawText`
 *   分支（`:138` 还专门带 `TextFormatFlags.PreserveGraphicsClipping`），
 *   全部靠 GDI+ 的裁剪区域，没有一条自己做区间夹取。
 */
object GraphicGeometry {

    /**
     * 背景层的摆位框：`(x, y, w, h)`，坐标**相对绘制区域左沿与上沿**
     * （调用方再加自己的 content 原点）。
     *
     * @param areaW 绘制区域宽（像素浮点）
     * @param areaH 绘制区域高
     * @param srcW 基图（子矩形）宽，必须 > 0
     * @param srcH 基图高，必须 > 0
     */
    fun coverBox(areaW: Float, areaH: Float, srcW: Float, srcH: Float): FloatArray {
        val scaleW = areaW / srcW
        val scaleH = areaH / srcH
        // EmueraConsole.cs:949 的 flag 等价于 scaleW < scaleH ⇒ 取 scaleH（cover）
        val scale = if (scaleW < scaleH) scaleH else scaleW
        val w = srcW * scale
        val h = srcH * scale
        return floatArrayOf((areaW - w) / 2f, 0f, w, h)
    }

    /**
     * `drawBitmap` 的源矩形 `(left, top, right, bottom)`。
     *
     * 子矩形没给（`srcW <= 0 || srcH <= 0`）时返回整幅基图；给了就原样平移，
     * **不夹到基图边界**（纯算式形态，给单测钉住「脚本写了什么就是什么」；
     * 真上屏必须改用 [sourceRectClipped]，否则安卓会抛异常，见该函数）。
     */
    fun sourceRect(srcX: Int, srcY: Int, srcW: Int, srcH: Int, bitmapW: Int, bitmapH: Int): IntArray =
        if (srcW > 0 && srcH > 0) {
            intArrayOf(srcX, srcY, srcX + srcW, srcY + srcH)
        } else {
            intArrayOf(0, 0, bitmapW, bitmapH)
        }

    /**
     * 上屏用的源矩形：把 [sourceRect] 的结果**夹进基图**，夹完为空则返回 null（什么都不画）。
     *
     * 为什么要多这一步而 [sourceRect] 不直接夹：
     * 原版 `ASpriteSingle.GraphicsDraw` 走 GDI+（`ASpriteSingle.cs:74,86,98`），
     * 源矩形越界只是看不见、不报错；而 `android.graphics.Canvas.drawBitmap(bitmap, src, dst, paint)`
     * 在 `src` 为空矩形或**超出 bitmap 边界**时抛 `IllegalArgumentException`（契约见
     * `android.graphics.Canvas` 的 drawBitmap 重载）。`SPRITECREATE` 的子矩形是脚本给的，
     * 语料里越界/负值的写法一律由 GDI+ 静默吞掉，安卓侧不夹就是**真机崩溃**，
     * 一条坏行毁掉整个 app。取色侧原版本来就是显式夹的（`ASpriteSingle.cs:57-63`），
     * 所以「夹」这个动作有出处，不是本层新造的宽容。
     *
     * TODO(verify)：夹完之后 GDI+ 具体画多大一块（是按原 src 比例裁 dst，还是把夹后的
     * src 铺满整个 dst）没有 PC 可比读数；卡点＝本机无 WinForms，也无截图工具。
     * 本函数只保证「不崩 + 不画到基图外」，竖向错位要等真机截图复核。
     *
     * @return `(left, top, right, bottom)`，或 null 表示这一帧没有可画的像素
     */
    fun sourceRectClipped(
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
        bitmapW: Int,
        bitmapH: Int,
    ): IntArray? {
        val rect = sourceRect(srcX, srcY, srcW, srcH, bitmapW, bitmapH)
        val left = rect[0].coerceIn(0, bitmapW)
        val top = rect[1].coerceIn(0, bitmapH)
        val right = rect[2].coerceIn(0, bitmapW)
        val bottom = rect[3].coerceIn(0, bitmapH)
        if (right <= left || bottom <= top) {
            return null
        }
        return intArrayOf(left, top, right, bottom)
    }

    /** 脚本给的像素尺寸优先，非正（= 没给）时退回帧自带尺寸。 */
    fun inlineSize(givenPx: Int, fallbackPx: Int): Int = if (givenPx > 0) givenPx else fallbackPx

    /**
     * 「纯图片行」的图要不要按行高等比缩小的**缩放系数**（1 = 原尺寸）。
     *
     * 原版**从不按行高缩图**：每行的行距恒为 `Config.LineHeight`
     * （`EmueraConsole.cs:1855` 的 `num += Config.LineHeight`），而
     * `ConsoleImagePart.DrawTo` 把 `destRect` 原样交给 `GraphicsDraw`
     * （`ConsoleImagePart.cs:159-174`），高由 `raw_height` 决定（`:66`、`:82`、`:93`），
     * 与行高无关 —— 高于一行的图**向下溢出**，压在后续空行上。eraTW 的立绘正是靠这一条：
     * `HTML_PRINTL_PX` 打完图行后按 `ceil(图高 / 一行の高さ)` 补空行（QOL_IMAGE.ERB:356-373），
     * 180px 的图溢出进这些空行。
     *
     * 本端口为什么要缩：[RowLayout] 会为标题图（35 条 1041×16 横带铺满
     * 整屏）把纯图片行等分压缩到低于执行行高。那种情况行间距小于带宽，不缩就会让相邻横带
     * 互相覆盖。**只有这一种情况**才缩，四条守卫缺一不可：
     *
     * - [rowSpan] > 0：`ImageBandMerge` 合成的竖带块刻意跨行，绘制侧另行铺满，不缩；
     * - `drawH > lineHeight`：图本身就高于一行 ⇒ 原版就是让它溢出（立绘 180px、大地图都在
     *   这一支）。**这条保证立绘在任何屏高下都不会再被压成行高**——上一版只判
     *   `rowHeight < lineHeight`，一旦整屏文字多到 `remain / artRows` 只差分毫就低于行高，
     *   180px 又会被压回 21px（浮点边界复燃路径，2026-10-26 复查补）；
     * - `rowHeight` 未低于行高：[RowLayout] 的
     *   `artAdvance = fit.coerceIn(MIN_ART_ROW_HEIGHT, lineHeightPx)` 只有 `fit < lineHeight`
     *   才会真被压（否则夹回 `lineHeight`）。留 0.5px 容差吸收浮点残差：`fit` 恰好等于
     *   行高时 `coerceIn` 返回的就是行高本身，不该因 1e-6 的差被当成压缩；
     * - `drawH <= lineHeight`：只有「行内高度级」的图（标题横带 16px）才按行高等比缩；
     *   更高的一律按原尺寸溢出，与原版一致。
     *
     * @param drawH 图按脚本尺寸算出的目标高（像素）
     * @param rowHeight 该行在当前布局里的高度（像素）
     * @param lineHeight 执行行高（`Config.LineHeight` 的落地值，像素）
     * @param rowSpan 合并竖带块的跨行数（0 = 普通图片行）
     */
    fun artRowScale(drawH: Float, rowHeight: Float, lineHeight: Float, rowSpan: Int): Float {
        if (rowSpan > 0 || rowHeight <= 0f || lineHeight <= 0f) return 1f
        if (drawH <= rowHeight) return 1f
        if (drawH > lineHeight) return 1f
        if (rowHeight >= lineHeight - ART_ROW_EPSILON_PX) return 1f
        return rowHeight / drawH
    }

    /** [artRowScale] 判「行高被压过」时允许的浮点容差（像素）。 */
    private const val ART_ROW_EPSILON_PX = 0.5f

    /**
     * 行内图形段的纵盒 `(top, bottom)`，**相对行顶**（像素，与段自带的 `yOffsetPx` 同尺）。
     *
     * 三支算式逐条照原版（缺陷 B 的几何出处；契约 CONTRACT-ANIME §2 给的是 `:26-33`，
     * 本仓 reference 实测赋值在 `:34`/`:35`，两处指的是同一段构造器）：
     * - [InlineGraphic.Image] —— `ConsoleImagePart.cs:81` `top = ypos`（px 原值、
     *   百分比按 `num * Config.FontSize / 100`）、`:82` `destRect = new Rectangle(0, top, W, num)`、
     *   `:93` `bottom = top + num`。`num` 即本端口的 `heightPx`（已按 `:66` 折算过）。
     * - [InlineGraphic.Rect] —— `ConsoleRectangleShapePart.cs:34` `top = Math.Min(0, rect.Y)`、
     *   `:35` `bottom = Math.Max(Config.FontSize, rect.Y + rect.Height)`：矩形件**无论摆在
     *   行内哪里**，纵盒都至少盖住一行字（`Config.FontSize`），所以一条 2px 的细横带
     *   不算溢出、而 200px 高的色块算。
     * - 文本件与 [InlineGraphic.Space] —— `AConsoleDisplayNode.cs:29,31` 的默认
     *   `Top => 0`、`Bottom => Config.FontSize`：正常配置（`FontSize <= LineHeight`）下恒不溢出，
     *   `Space` 本身不画任何东西（`ConsoleSpacePart` 只推列游标），同样按不溢出交回 `(0, 0)`。
     *
     * @return `[top, bottom]`；[InlineGraphic.Space] 返回 `[0, 0]`
     */
    fun escapedLineBox(graphic: InlineGraphic, fontSizePx: Int): IntArray =
        intArrayOf(lineBoxTop(graphic), lineBoxBottom(graphic, fontSizePx))

    /**
     * 该图形段是否**越出自己那一行** —— 原版 escape 名单的判式。
     *
     * 出处（缺陷 B 的因果链，每条都在本仓 reference 实读）：
     * - `ConsoleButtonString.cs:165-185` 的 `FilterEscaped()`：`:171`
     *   `strArray.Where(p => p.Top < 0 || p.Bottom > Config.LineHeight)` 收进 `escaped`；
     * - `EmueraConsole.cs:2916-2923` 行入列时把溢出的部件登记进 `ConsoleEscapedParts`
     *   （行区间 = `ceil(Top/LineHeight)+lineNo` .. `floor(max(0,Bottom-1)/LineHeight)+lineNo`）；
     * - `OnPaint` 的绘制主循环 `:1850-1857` **先**按行自上而下画完，**随后** `:1858-1872`
     *   才把溢出的部件按 `num6 + (ParentLine.LineNo - num3) * LineHeight`（`:1870`）单独铺一遍；
     * - `ConsoleButtonString.DrawTo`（`:317-363`）对 `strArray` 全量画、**不**排除 escaped 部件
     *   ⇒ 原版是「原位画两遍 + 溢出的再铺一遍」，净效果 = **溢出部件盖在文字之上**。
     *
     * 端口此前没有这一趟（整屏路按行升序一遍铺到底 ⇒ 图行先画、它下面那些行的字后画 =
     * 「历史里的动图被文本覆盖」）。现在两个消费点接同一个判式、**一份算式不重复**：
     * `CanvasConsoleView.drawScreen` 的整屏路（先非 escape、后 escape）与
     * uipreview 的 `InkPainter.paint` 第 3 趟。
     * 判式只认段自带的 `yOffsetPx` / `heightPx`，与原版 `FilterEscaped` 数
     * `ConsoleImagePart.destRect` 完全同口径（`SpriteAnime.cs:148-159` 的落笔高放大那一条
     * 在 `_DW/_DH` 缺省时比值恒 1，语料常见写法不成立，故不进判式；见契约 §2 缺陷 B）。
     *
     * `heightPx <= 0` 的段按原版当「不溢出」处理 —— 原版对负高是 `ConsoleImagePart.cs:88-92`
     * 的取负翻正（`Y -= Height; num = -Height`），**不在本刀范围**（尺寸归 `inlineBox` 管）。
     * TODO(verify)：负高段翻正后的 `top` 会下移到 `top + |height|`，理论上能同时越出下沿；
     * 卡点＝展平侧（`HtmlRunFlatten.emitImage`）把负 `height` 原样交进来、本端没有负高语料实测。
     *
     * @param fontSizePx 绘制侧的生效字号（`Config.FontSize`）：矩形件纵盒的下沿默认值
     * @param lineHeightPx 执行行高（`Config.LineHeight`）
     */
    fun escapesLine(graphic: InlineGraphic, fontSizePx: Int, lineHeightPx: Float): Boolean =
        lineBoxTop(graphic) < 0 || lineBoxBottom(graphic, fontSizePx) > lineHeightPx

    /**
     * 纵盒上沿（相对行顶）。[escapedLineBox] 与 [escapesLine] 共用这一份算式 ——
     * 判式在每帧的整屏路里**每条命令要跑两遍**（两趟铺墨各一遍），返回数组会让这条 40 ms
     * 一拍的动图路径每帧多出成百上千个 `IntArray`；拆成两个标量函数后热路径零分配，
     * 而算式仍然只有这一处（[escapedLineBox] 只是把它们打包给单测/排障看）。
     */
    private fun lineBoxTop(graphic: InlineGraphic): Int = when (graphic) {
        is InlineGraphic.Image -> graphic.yOffsetPx
        is InlineGraphic.Rect -> if (graphic.yOffsetPx < 0) graphic.yOffsetPx else 0
        is InlineGraphic.Space -> 0
    }

    /** 纵盒下沿（相对行顶）：三支出处见 [escapedLineBox] 的 KDoc。 */
    private fun lineBoxBottom(graphic: InlineGraphic, fontSizePx: Int): Int = when (graphic) {
        is InlineGraphic.Image -> graphic.yOffsetPx + graphic.heightPx
        is InlineGraphic.Rect -> maxOf(fontSizePx, graphic.yOffsetPx + graphic.heightPx)
        is InlineGraphic.Space -> 0
    }

    /**
     * `ConsoleImagePart` 构造段（`ConsoleImagePart.cs:66-93`）的**完整**尺寸算式：
     * 返回内联图的 `(width, height)`（已取绝对值，负数按 :83-92 翻正）。
     *
     * 逐条出处（`num` = 高、`baseW/baseH` = `cImage.DestBaseSize`，即 [ImageFrame.width] /
     * [ImageFrame.height] 那份有效基准尺寸）：
     * - **高**：`:66` —— `raw_height` 没给或给的是 0 ⇒ `Config.FontSize`（:40 的同一个默认）；
     *   给了但不是 px ⇒ `FontSize * num / 100`（整数除法）；是 px ⇒ 原值。
     * - **宽**：`:67-70` —— `raw_width` 没给或给的是 0 ⇒ **不是**基准宽，而是按高反推的
     *   `DestBaseSize.Width * num / DestBaseSize.Height`（整数除法，:69）——
     *   这就是原版的等比形态。给了但不是 px ⇒ `FontSize * num / 100`（:78）。
     * - **负数**：`:83-92` —— `destRect.Width < 0` 时 `X = -Width; Width = -Width`、
     *   `Height < 0` 时 `Y -= Height; Height = -Height`，即**取绝对值并顺带搬原点**，
     *   不是丢弃。本函数只返回尺寸，位移侧原版本来就是 `X = -Width`（起点 0 时等价于平移），
     *   需要精确到 `top` 的调用方另按 :81 的 `raw_ypos` 自己加。
     *
     * 与 [inlineSize] 的差别只在「宽没给」这一支：[inlineSize] 退基准宽（= 不等比），
     * 本函数退**按高反推的等比宽**。`PRINTIMG 高,`（只给一个尺寸）在语料里是常见写法。
     *
     * **一处主动偏离 ADR-005（要组长拍板）**：`baseH == 0` 时本函数给宽 0 而不做除法，
     * 原版 :69 的整数除法在这一步会抛 `DivideByZeroException`（崩溃路径，不画这张图）。
     * 零高基准尺寸在原版是能造出来的：`ASprite` 构造只对**负**宽高取绝对值，0 原样存进
     * `DestBaseSize`（spec: `ASprite.cs:13-25`）。这里选择不抛的理由是「一行坏图不该带走整个
     * app」，安卓侧没有 PC 那种可「继续」的未处理异常对话框。
     * TODO(verify)：卡点＝语料里查不到零高 sprite 的写法，量不到 PC 上这条到底崩不崩；
     * 组长若判定要照抛，去掉这个守卫即可（Kotlin 整数除以 0 同样抛 `ArithmeticException`）。
     *
     * @param givenWidthPx 脚本的宽（已折成像素；0 = 没给，走等比反推；负 = 原版的翻转形态）
     * @param givenHeightPx 脚本的高（已折成像素；0 = 没给，退 `FontSize`；负 = 翻转形态）
     * @param baseW 基准尺寸宽（`DestBaseSize.Width`）
     * @param baseH 基准尺寸高（`DestBaseSize.Height`）
     * @param fontSizePx `Config.FontSize` 的像素值：既是高缺省，也是百分比折像素的尺
     */
    fun inlineBox(
        givenWidthPx: Int,
        givenHeightPx: Int,
        baseW: Int,
        baseH: Int,
        fontSizePx: Int,
    ): IntArray {
        val height = if (givenHeightPx == 0) fontSizePx else givenHeightPx
        val width = if (givenWidthPx == 0) {
            if (baseH != 0) baseW * height / baseH else 0
        } else {
            givenWidthPx
        }
        return intArrayOf(if (width < 0) -width else width, if (height < 0) -height else height)
    }

    /**
     * 图在**内联框里**的落位：返回 `(x, y, w, h)`，相对框左上角（调用方再加自己的原点）。
     *
     * 原版把内联框 `destRect` 整个交给 `ASprite.GraphicsDraw(g, Rectangle)`，
     * 落位换算发生在 sprite 自己身上，**不在** `ConsoleImagePart` 里
     * （`ConsoleImagePart.cs:159-174` 只做 `rectangle = destRect; X += PointX + ShapePositionShift`）。
     * 两支算式：
     *
     * - 静态 sprite（`SpriteG`/`SpriteF`）—— `ASpriteSingle.cs:79-85`：
     *   ```
     *   if (!DestBasePosition.IsEmpty) {
     *       destRect.X += DestBasePosition.X * destRect.Width  / DestBaseSize.Width;
     *       destRect.Y += DestBasePosition.Y * destRect.Height / DestBaseSize.Height;
     *       destRect.Width  = destRect.Width  * SrcRectangle.Width  / DestBaseSize.Width;
     *       destRect.Height = destRect.Height * SrcRectangle.Height / DestBaseSize.Height;
     *   }
     *   ```
     *   **整段被 `DestBasePosition.IsEmpty` 门住** —— `pos` 为 `(0,0)` 时连「按子矩形占比缩」
     *   都不做，源矩形被拉满整个框。这是原版的耦合怪癖（`IsEmpty` 顺带当了「有没有设过
     *   `xpos/ypos`」的哨兵），不是移植侧可以顺手拆开的：语料 93 个资源 XML 里 23,604 个
     *   静态条目有 6,851 个 `pos != (0,0)`（如 `As06MARK-IV/拼接.xml` 的
     *   `param="2462,271,95,104,193,49,,500,500"`，95×104 的小件要塞进 500×500 画布的
     *   (193,49)），剩下 16,753 个 `pos == (0,0)`。若把门拆掉改成「恒缩」，后者里凡是
     *   `srcW != destW` 的写法都会跟着变尺寸 —— 那是改语义，ADR-005 不许。照抄。
     * - 动画 sprite（`SpriteAnime`）—— `SpriteAnime.cs:148-159`：同样四式，但**没有那道门**，
     *   且位移是 `DestBasePosition + currentFrame.Offset`（`:153-154`）。`SPRITEANIMECREATE`
     *   不收 `pos`（`GraphicsMethods.kt` 的 `SPRITEANIMECREATE` 只有 name/w/h 三参），
     *   所以动画侧实际只有 `Offset` 在动 —— 但它**必须**动：语料
     *   `l13/留琴/留琴-服/anime.xml` 的 `別立ち2_服_笑顔动_2` 每帧 `param="0,0,704,734,15,0,42"`
     *   就是 `xpos=15`（`RM_PARSE_PARAMS` 的 `PARAMS:4` → `SPRITEANIMEADDFRAME` 第 7 参），
     *   丢掉它整段动画就横向偏 15px。
     *
     * 除法全是 **C# 的 `int` 整数除法**（向零截断）：先乘后除、不升 Long、不转浮点。
     * 溢出行为也与 C# 一致（Int 回绕），不做饱和 —— 原版 `Rectangle` 就是这么算的。
     *
     * ## 与原版的三处主动偏离（均为「不崩」而非「改语义」）
     *
     * - `DestBaseSize` 某轴 `<= 0` 时原版 :81-84 会抛 `DivideByZeroException`（崩掉整帧）。
     *   这里退回「框即落位」（`(0,0,boxW,boxH)`），理由同 [inlineBox] 的零高守卫：
     *   安卓侧没有 PC 那种可继续的未处理异常对话框。
     * - `srcW/srcH <= 0` 时原版 `SrcRectangle.Width` 可为 0 或负（`SPRITECREATE` 6 参形态
     *   `destSize = rect.Size` 允许负宽），乘出来是 0/负宽矩形，GDI+ 静默不画。
     *   这里该轴退回框宽/框高 —— 与 `ImageFrame.width/height` 的「非正 = 没给」口径一致，
     *   且 `drawBitmap` 拿到 0 宽目标矩形同样什么都不画，观感等价。
     * - `anime == false && pos == (0,0)` 时不进算式（见上，这是**复刻**而非偏离）。
     *
     * @param boxW 内联框宽（`ConsoleImagePart.destRect.Width`，已折像素、已翻正）
     * @param boxH 内联框高
     * @param baseW `DestBaseSize.Width`
     * @param baseH `DestBaseSize.Height`
     * @param basePosX `DestBasePosition.X`（`SPRITECREATE` 的 `xpos`）
     * @param basePosY `DestBasePosition.Y`（`ypos`）
     * @param offsetX 动画当前帧 `Offset.X`；静态传 0
     * @param offsetY 动画当前帧 `Offset.Y`
     * @param srcW 源子矩形宽（`SrcRectangle.Width`）
     * @param srcH 源子矩形高
     * @param anime 是否 `SpriteAnime`（选不带 `IsEmpty` 门的那一支）
     */
    fun spriteDestRect(
        boxW: Int,
        boxH: Int,
        baseW: Int,
        baseH: Int,
        basePosX: Int,
        basePosY: Int,
        offsetX: Int,
        offsetY: Int,
        srcW: Int,
        srcH: Int,
        anime: Boolean,
    ): IntArray {
        // ASpriteSingle.cs:79 的 `!DestBasePosition.IsEmpty` / SpriteAnime.cs:148-159 无门
        val place = anime || basePosX != 0 || basePosY != 0
        if (!place || baseW <= 0 || baseH <= 0) {
            return intArrayOf(0, 0, boxW, boxH)
        }
        // :153-154 与 :81-82 —— 位移用的是**改之前的**框宽高（原版先算 X/Y 再改 Width/Height）
        val x = (basePosX + offsetX) * boxW / baseW
        val y = (basePosY + offsetY) * boxH / baseH
        // :155-156 与 :83-84
        val w = if (srcW > 0) boxW * srcW / baseW else boxW
        val h = if (srcH > 0) boxH * srcH / baseH else boxH
        return intArrayOf(x, y, w, h)
    }
}
