package com.eratw.port.render.displaylist

import com.eratw.port.render.GlyphKey
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun

/**
 * 显示列表里的**非文本内联图形** —— M10-GFX 给 [DrawCmd] 加的位图/色块变体负载。
 *
 * ## 为什么它是「一段」而不是「一层」
 *
 * 原版 `PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE` 与 HTML 的 `<img>` / `<shape>` 都
 * **append 进打印缓冲**，跟文本节点排在同一条显示行里：
 * `EmueraConsole.cs:3192-3195` 的 `PrintImg` 就是 `printBuffer.Append(new ConsoleImagePart(...))`，
 * `:3196-3200` 的 `PrintShape` 同理 append `ConsoleShapePart`；随后
 * `ConsoleDisplayLine.DrawTo`（spec: `ConsoleDisplayLine.cs:118-125`）逐节点画，
 * 图形节点的横向起点与文本节点用的是**同一个** `PointX` 游标。
 * 所以移植侧不能把它做成「覆盖在文字上的一层」，只能做成显示列表里的一**段**：
 * 有列区间、参与折行与脏矩形，跟文本抢同一行空间。
 *
 * ## 为什么不带像素
 *
 * [Image.resource] 只是名字（sprite / 资源名），像素由绘制侧的解析器按名取
 * （`:platform` 的 `SpriteBitmapSource`）。显示列表是**保留缓存**（干净行的 [DrawCmd]
 * 实例跨帧复用），把 `IntArray` 挂在这里会让一份位图在缓存里常驻且无法随
 * `SPRITECREATE` 的覆盖/释放换代；名字则天然与 `AppContents.GetSprite(name)` 的
 * 每次查表同构（spec: `ConsoleImagePart.cs:60` 构造期查一次，画的时候读 `cImage`）。
 *
 * ## 尺寸口径（像素）
 *
 * 原版图形段的几何全是**像素**，只有列起点是字符推进的：
 * - 高：`height` 缺省 = `Config.FontSize`（一行高），非 px 时按 `FontSize * num / 100`
 *   （spec: `ConsoleImagePart.cs:66`）；
 * - 宽：`width` 缺省时按基图纵横比 `DestBaseSize.Width * num / DestBaseSize.Height`
 *   （spec: `ConsoleImagePart.cs:67-71`），px 直接用，百分比同样按 `FontSize` 折算（`:78`）；
 * - 竖向偏移 `ypos`：px 直接用，百分比 `num * FontSize / 100`（spec: `ConsoleImagePart.cs:81`），
 *   段的上沿 = `ypos`、下沿 = `ypos + 高`（`:93` 的 `bottom = top + num`），
 *   比行高大时原版会**把下一行的文本挤开**（`ConsoleDisplayLine` 按 `Top`/`Bottom` 排行）。
 *   本层不做那种行高重排：`widthCells` 由生产侧按半角步进换算，超高部分画到行外，
 *   TODO(verify) 真机截图复核「立绘压住下一行」是否需要行高让位，卡点：本层行高恒等于
 *   `CellMetrics.lineHeight`，没有 per-row 高度的容器。
 * - `PRINT_RECT` / `PRINT_SPACE` 的 param 数量分支与百分比换算见
 *   spec: `ConsoleShapePart.cs:50-69`（`space` 只有 1 参；`rect` 1 参时高 = FontSize、
 *   4 参时 `x, y, w, h` 逐参折算）。
 *
 * @property widthCells 本段占掉的半角格数（>= 0），由生产侧用像素→格换算好交进来：
 *   [DisplayList] 只有格口径、没有像素口径，绘制侧再按 [com.eratw.port.render.glyph.CellMetrics]
 *   量化回像素。零宽段（越界被夹成 0 时）不入列表。
 */
sealed class InlineGraphic {

    /** 本段在网格上占的半角格数（列游标因此前进这么多）。 */
    abstract val widthCells: Int

    /**
     * 位图片段（原版 `ConsoleImagePart`）。
     *
     * @property resource 资源 / sprite 名，绘制侧按它取像素；空串 = 原版 `resName ?? ""`
     *   查不到 sprite 的形态（spec: `ConsoleImagePart.cs:42,60-64`），那种情况下原版
     *   把 [altText]（重建出来的 img 标签）当文本画（spec: `ConsoleImagePart.cs:175-182`）；
     *   绘制侧的回落就是逐条照这条：帧解析不到（sprite 缺失 / 未创建）时画 [altText]
     * @property widthPx 目标宽（像素，已含百分比折算）
     * @property heightPx 目标高（像素）
     * @property yOffsetPx 相对行顶的竖向偏移（像素，可为负 = 画到上一行）
     * @property buttonResource 按下/聚焦时换用的资源名（按钮图 srcb → 原版 `cImageB`，
     *   spec: `ConsoleImagePart.cs:94-97`）；null = 不换。原版换图判据是
     *   `(isSelecting || isFocus) && cImageB != null`（spec: `ConsoleImagePart.cs:163-167`），
     *   移植侧的按下通道 = 命中层命中区间与本段列区间的几何相交（见 [graphicPressed]，
     *   按钮内图片列与文本列共用同一命中区间）；聚焦态（`ButtonIsSelected`，
     *   spec: `EmueraConsole.cs:830-833` 的 `selectingButton == button`）暂无独立通道，
     *   只按按下判 —— 已知差异。取图入口见 [Image.drawResource]；按下图查不到时
     *   绘制侧退主图（原版 GetSprite 对未注册名返回 null ⇒ `cImageB == null`，
     *   spec: `AppContents.cs:46-48`）。flatten 侧由 `HtmlRunFlatten.emitImage` 从
     *   `srcb` 属性喂（空串置 null），消费侧是 render-canvas 的 `drawImage` 按下分支
     * @property mappingResource 命中取色用的遮罩图名（srcm → 原版 `cImageM`，
     *   spec: `ConsoleImagePart.cs:44,98-101`；点进去按坐标取遮罩色是
     *   `ConsoleButtonString.cs:138-152` 的 `GetMappedColor` + `ConsoleImagePart.cs:135-157`
     *   的 `GetMappingColor`，取色算式见 [maskSamplePoint]）。**遮罩不是遮罩画法**：
     *   它不参与绘制，只被命中路径读像素 —— 与 srcb 别弄反（spec: `FunctionIdentifier.cs:414-426`：
     *   第 2 实参是按钮图、第 3 实参是取色图）
     * @property altText 原版构造期重建的标签文案（spec: `ConsoleImagePart.cs:45-59`）；
     *   取不到帧时按文本画上屏（`:60-64,175-182`）。生产侧用 [imgAltText] 逐字符生成；
     *   null = 生产侧没给（此时缺帧回落退化为不画，保持接入前的静默行为）
     * @property rowSpan 合并后的**跨行数**（`ImageBandMerge` 产物，>0）：这一行只是整块的首行，
     *   [heightPx] 故意大于单行行高、靠后面的承载空行让出空间。绘制侧据此**不得**按单行高
     *   压缩它（那会把整张标题图压成一条）；0 = 普通行内图
     * @property bandResources 竖带块的**全部切片资源名**（自上而下，`ImageBandMerge` 产物）：
     *   标题图是 35 张 1041×16 的切片 sprite，单画 [resource] 只会出最上面 16px 一条。
     *   非 null 时绘制侧逐片堆叠到 [widthPx]×[heightPx] 的目标块里；null = 普通单张图
     */
    data class Image(
        val resource: String,
        val widthPx: Int,
        val heightPx: Int,
        val yOffsetPx: Int,
        override val widthCells: Int,
        val buttonResource: String? = null,
        val mappingResource: String? = null,
        val altText: String? = null,
        val rowSpan: Int = 0,
        val bandResources: List<String>? = null,
    ) : InlineGraphic() {

        /**
         * GFX-09：本次绘制实际取图的资源名（spec: `ConsoleImagePart.cs:163-167`）：
         * 按下且带按下图时取 [buttonResource]，否则取 [resource]。原版在
         * `cImageB == null`（GetSprite 对未注册名返回 null，spec: `AppContents.cs:46-48`）
         * 时保持画主图；按下图查不到时的帧级兜底（退主图、再退 altText）在
         * render-canvas 的 `drawImage`，这里只定名。
         */
        fun drawResource(pressed: Boolean): String =
            if (pressed) buttonResource ?: resource else resource
    }

    /**
     * 实心矩形色块（原版 `ConsoleRectangleShapePart`）。
     *
     * @property xOffsetPx 相对段起点的横向偏移（4 参形态才有，spec: `ConsoleShapePart.cs:63`）；
     *   绘制侧还要在它之上再加一个 `ShapePositionShift`（原版是测量期加：
     *   `rect.X += Config.DrawingParam_ShapePositionShift`，spec: `ConsoleRectangleShapePart.cs:57`
     *   与 `Config.cs:461-466`）
     * @property yOffsetPx 相对行顶的竖向偏移
     * @property colorArgb 填充色。原版拿的是 `userStyle.Color`（spec: `ConsoleShapePart.cs:12`
     *   的 `color` 入参 = `Exm.Console.userStyle.Color`，`:76` 落到段上），GDI 的 SolidBrush
     *   用**不透明**色（`Config.ForeColor` 本身就是 255 alpha 的 RGB）；所以本字段允许
     *   生产侧只给 `0xRRGGBB`（缺 alpha 高位），**绘制侧必须补 alpha=0xFF 再画** ——
     *   直接把 0x00RRGGBB 当 ARGB 用会让色块整体透明、屏上一个像素都不出
     */
    data class Rect(
        val xOffsetPx: Int,
        val yOffsetPx: Int,
        val widthPx: Int,
        val heightPx: Int,
        val colorArgb: Int,
        override val widthCells: Int,
        /**
         * 横向渐变停点（ARGB，等间距从段左沿铺到右沿）；null = 用 [colorArgb] 纯色填充。
         *
         * 语料的渐变进度条（`@HTML_RECT_BAR`）把一条切成上百个 1 单位宽的 `<shape>`，逐块画
         * 会沿半角格出现台阶接缝。展平器把整条折成**一个**矩形 + 这组停点，绘制侧用
         * `LinearGradient` 画 —— 像素级平滑，同时不改变格布局（占宽仍由 [widthCells] 决定）。
         * 停点由各块的填充色按位置降采样而来，语义仍是原版「逐块 FillRectangle」的近似。
         */
        val gradientArgb: IntArray? = null,
    ) : InlineGraphic()

    /** 空白推进段（原版 `ConsoleSpacePart`：不画任何东西，只把列游标往前推；负宽 = 回退）。 */
    data class Space(override val widthCells: Int) : InlineGraphic()

    companion object {
        /**
         * 像素宽 → 半角格数的唯一换算入口（向上取整：一格都画不下的段留 0 格，
         * 只要有一点宽度就至少占 1 格，否则相邻段会叠在同一列上）。
         *
         * **负宽取正宽的镜像**：原版 `ConsoleSpacePart` 的 `WidthF` 就是负值，
         * 排版按 `num += part.Width` 把后续部件左移（`ConsoleButtonString.cs` 的 `CalcPointX`），
         * XRAY 图层正是靠 `<shape type='space' param='-{W}px'>` 把后续 `<img>` 拉回立绘起点
         * 叠上去（`QOL_IMAGE.ERB` 的 `XRAY_IMAGE_BLOCK`）。折成 0 格会让叠图落到立绘右侧。
         *
         * 原版在**像素域**上加减、`SetWidth` 还会把小数部分亚像素进位到下一段
         * （`ConsoleSpacePart.cs:18-23`），所以 `+Wpx` 与紧随其后的 `-Wpx` 相加恒为 0。
         * 本层只有整格网格：正向已按上取整占 `ceil(W)` 格，负向若按 C# 的向零截断就是
         * `floor(W)` 格，每叠一层漏 1 格 —— XRAY 十层会把图层一路右移，正是「叠不上」的
         * 另一种形态。故负向取 `-ceil(|W|)`，与正向严格互为镜像，配对写法净位移恰为 0。
         * 语料里的负 space 全部是这种「紧跟同宽段」的配对写法（`QOL_IMAGE.ERB:829` / `:878`、
         * `QOL_USERCOM.ERB:233`）。TODO(verify)：整格网格无法复刻亚像素进位，
         * 真机像素度量落地后改回像素域累加。
         *
         * [halfCellPx] 是实测半角步进（`CellMetrics.cellPixelWidth`），
         * 与「生产装配必须传 DrawableWidth ÷ 实测半角步进」同源。
         */
        fun cellsFor(widthPx: Int, halfCellPx: Float): Int {
            if (widthPx == 0 || halfCellPx <= 0f) return 0
            if (widthPx < 0) {
                // 用 Long 取绝对值：-Int.MIN_VALUE 会 Int 回绕成负数。
                val cells = kotlin.math.ceil(-widthPx.toLong() / halfCellPx).toInt()
                return if (cells < 1) -1 else -cells
            }
            val cells = kotlin.math.ceil(widthPx / halfCellPx).toInt()
            return if (cells < 1) 1 else cells
        }

        /**
         * GFX-09：图形段的**按下态判定** —— 本段列区间与当前按下按钮命中区间的几何相交
         * （半开区间，口径同 ButtonLayer 的命中区间，也同 drawCmd 文本段的按下判式）。
         *
         * 原版按钮里的 img 节点与文本片同住一条 `ConsoleButtonString`，绘制时共享同一份
         * `isSelecting` / `isFocus`（spec: `ConsoleButtonString.cs:319-320` 的
         * `ButtonIsSelected` / `ButtonIsPointing` 与 `:363` 的逐节点透传），所以按下判定
         * 只看「按下的命中区间盖没盖住本段」，不问段的 kind（图形段 kind 是 IMAGE/SHAPE，
         * 不是 BUTTON）。同一行的段列区间互不重叠，命中区间外的图形段不可能误中。
         *
         * [pressedRow] 为 -1（无按下）时任何段都不命中（cmd.row >= 0 恒假）。
         */
        fun graphicPressed(cmd: DrawCmd, pressedRow: Int, pressedLo: Int, pressedHi: Int): Boolean =
            cmd.row == pressedRow && cmd.colStart < pressedHi && pressedLo < cmd.colEnd

        /**
         * 原版 `ConsoleImagePart` 构造期重建的 AltText 标签（spec: `ConsoleImagePart.cs:45-59`，
         * 属性拼装格式 `Utils.AddTagArg` `:457-462` 与 `AddTagMixedNumArg` `:364-371`，
         * 数值形态 `MixedNum.BuilderString` `:87-95`）。
         *
         * 逐条判据：
         * - ` src='R'` 无条件（`ResourceName = resName ?? ""`，`:42,46`，空名也照写）；
         * - ` srcb=` 只在按钮图非 null 时写（`:47-50`；语句层已把空串置 null，
         *   spec: `FunctionIdentifier.cs:423-426`）；
         * - ` srcm=` 只在取色图**非空且非 null**时写（`:51-54`）；
         * - ` height= / width= / ypos=` 只在给了且原始 num 非 0 时写（`AddTagMixedNumArg`
         *   的 `num != null && num.num != 0` 守卫，`:366`）；写法是**折算形态**：
         *   px 原样 + `px` 后缀，百分比落 `num * FontSize / 100` 的**整除**值（BuilderString
         *   `:87-95`），所以 3% 在 FontSize 18 下写成 `0` 而不是省略（raw num 非 0 就写）。
         *
         * [height]/[width]/[ypos] 是语句层的原始 `MixedNum`（`Pair(num, isPx)`，
         * null = 脚本没给这个槽位，spec: `FunctionIdentifier.cs:427-439` 的 Length 判）；
         * [fontSizePx] = 移植侧 `Config.FontSize`（`CellMetrics.fontPixelSize`）。
         */
        fun imgAltText(
            resource: String,
            buttonResource: String?,
            mappingResource: String?,
            height: Pair<Long, Boolean>?,
            width: Pair<Long, Boolean>?,
            ypos: Pair<Long, Boolean>?,
            fontSizePx: Int,
        ): String {
            val sb = StringBuilder("<img src='")
            sb.append(resource).append('\'')
            if (buttonResource != null) sb.append(" srcb='").append(buttonResource).append('\'')
            if (!mappingResource.isNullOrEmpty()) sb.append(" srcm='").append(mappingResource).append('\'')
            mixedNumString(sb, "height", height, fontSizePx)
            mixedNumString(sb, "width", width, fontSizePx)
            mixedNumString(sb, "ypos", ypos, fontSizePx)
            sb.append('>')
            return sb.toString()
        }

        private fun mixedNumString(
            sb: StringBuilder,
            name: String,
            value: Pair<Long, Boolean>?,
            fontSizePx: Int,
        ) {
            // AddTagMixedNumArg（Utils.cs:364-371）：null 或 num==0 整个属性不写。
            val v = value ?: return
            if (v.first == 0L) return
            sb.append(' ').append(name).append("='")
            if (v.second) {
                sb.append(v.first).append("px")
            } else {
                sb.append(v.first * fontSizePx / 100)
            }
            sb.append('\'')
        }

        /**
         * 负宽 / 负高的原版翻正（spec: `ConsoleImagePart.cs:83-92`）：
         * `destRect.Width < 0` 时 `X = -Width; Width = -Width`，
         * `destRect.Height < 0` 时 `Y -= Height; num = -Height`。
         * 两条分支同式：尺寸取绝对值，起点沿该轴**前移 |尺寸|**（起点为 0 的 X 轴就是
         * `X = |Width|`，起点为 `top` 的 Y 轴就是 `Y = top + |Height|`）。
         *
         * 返回 `[起点', 尺寸']`；尺寸非负时原样返回（原版判据是严格小于 0，
         * 0 走的是「缺省尺寸」的另一条分支，不在这里管）。
         */
        fun fixNegativeExtent(originPx: Int, sizePx: Int): IntArray =
            if (sizePx < 0) intArrayOf(originPx - sizePx, -sizePx) else intArrayOf(originPx, sizePx)

        /**
         * 遮罩取色的**坐标换算**部分：把点击点从「图内本地像素」映射到遮罩基图上的取色点
         * （spec: `ConsoleButtonString.cs:142-146` 的范围判 + `ConsoleImagePart.cs:152-153`
         * 的缩放，两处都是整除截断）。
         *
         * 范围判与原版逐符号一致：左/上沿是**严格大于 0**（落在第 0 列不算命中）、
         * 右/下沿**严格小于宽/高**（`:143-144` 的 `num > 0 && num < mask.Width`）。
         * 缩放分母是绘制框（`destRect` 的宽高，即 [destWidth]/[destHeight]），
         * 分子是遮罩自己的 `DestBaseSize`（`ASprite.cs` 构造期 = 子矩形尺寸，
         * spec: `ASpriteSingle.cs:29-43`）。越界返回 null = 原版 `GetMappedColor` 的
         * `return 0L`（遮罩缺失 / 未创建同样返回 0，`ConsoleImagePart.cs:137,156`）。
         *
         * 返回的取色点还要加遮罩子矩形的左上角才是基图坐标 —— 那一步在绘制侧做
         * （`ASpriteSingle.cs:50-64` 的 `SpriteGetColor`，基图外返回 Transparent ⇒ 0）。
         */
        fun maskSamplePoint(
            localX: Int,
            localY: Int,
            destWidth: Int,
            destHeight: Int,
            maskBaseWidth: Int,
            maskBaseHeight: Int,
        ): Pair<Int, Int>? {
            if (localX <= 0 || localX >= destWidth) return null
            if (localY <= 0 || localY >= destHeight) return null
            if (destWidth <= 0 || destHeight <= 0) return null
            return Pair(localX * maskBaseWidth / destWidth, localY * maskBaseHeight / destHeight)
        }
    }
}

/**
 * 保留显示列表的绘制命令 —— ADR-001「重设计区」中「自绘 View + 字形缓存 + 脏矩形 + 保留显示列表」
 * 那条替代 GDI+ 全量重绘的路线，落到数据层的最小单元。
 *
 * ## 为什么这个类型里没有文本
 *
 * 原版把度量结果**缓存在显示节点上**，绘制阶段一律不重测：
 * `ConsoleStyledString` 构造时就固定 Font/Color 并把 `PointX`、`Width` 置 -1 作为
 * 「尚未测量」哨兵（spec: `ConsoleStyledString.cs:21-40`）；`CalcWidth` 只对 `Width <= 0`
 * 的节点调用测量（spec: `ConsoleButtonString.cs:257-270`）；对齐也只做一次
 * （spec: `ConsoleDisplayLine.cs:50-56` 的 `aligned` 守卫）；最终
 * `ConsoleDisplayLine.DrawTo` 只遍历既有节点数组并逐个画（spec: `ConsoleDisplayLine.cs:118-125`）。
 * 本类型把同一条约束**写进字段表**：只有列区间、颜色、样式位与字形引用
 * （[GlyphKey] 口径 = 字体名 + 样式位 + 码位，同 `ConsoleStyledString.cs:21-40` 的分桶依据），
 * 没有 `String`、`Char`、`TextRun`，也没有任何测量入口 ——
 * 「渲染线程再把文本量一遍」在这套类型里表达不出来。ADR-002 认定性能是架构副产品，
 * 这条类型约束就是它的落点：`DisplayList.render()` 只做只读拼接。
 *
 * ## 与命中区的分工
 *
 * [RunKind.BUTTON] 只告诉绘制侧「这一段要上按钮底色/描边」；按钮的**值**与命中区间
 * 归 m-button 的 `HitTarget`（它直接吃 [com.eratw.port.render.DisplayRow]），
 * 所以这里刻意不带 `buttonValue`：绘制命令里出现 `Any?` 会让上面那条「字段类型集合」
 * 不变量失效。
 * 两侧的列号同一来源：绘制侧（`DisplayList.prepare`）与命中侧（`ButtonLayer.attach`）
 * 都把 `DisplayRow.indentCells` 加成列游标的起点，同原版一个 `ShiftPositionX` 之后
 * 既用于 `DrawTo` 也用于命中判定（spec: `ConsoleDisplayLine.cs:118-125` 画既有节点、
 * `EmueraConsole.cs:2358-2361` 用同一批 `PointX` 判命中），不存在「画在偏移后、命中在偏移前」的分裂。
 *
 * @property row 显示列表内的行下标（0 起，保留列表坐标系，非逻辑行号）
 * @property colStart 段起始半角格（左闭）。**含整行的对齐位移**：提交时把
 *   `DisplayRow.indentCells` 加成列游标起点（spec: `ConsoleDisplayLine.cs:86-90` +
 *   `ConsoleButtonString.cs:307-315` 的 `PointX += shiftX`），再夹进可绘制窗口（负位移时取 0），
 *   整段出左界的段不入列表 —— 原版由 GDI 裁剪画布外像素（spec: `ConsoleStyledString.cs:119,129-138`）。
 *   配套的 [glyphs] 已把「整码点落在左界之外」的前导字形裁掉，所以绘制侧从 `colStart` 起
 *   逐字形铺就是这段在屏上的真实跨度（不裁会把整段文字右移 `|位移|` 格）。
 * @property colEnd 段结束半角格（右开，已裁剪到 `clientWidth`）= 从 [colStart] 起铺完
 *   [glyphs] 的格数（再夹到边界），也就是这段**实际会画到的列**。段宽 `colEnd - colStart`
 *   与位移无关（原版 `ShiftPositionX` 只动 `PointX` 不动 `Width`，spec: `ConsoleButtonString.cs:307-315`），
 *   除非段被左右边界裁短；负位移下跨界的那个码点整格保留时，右沿最多比几何右沿多出它的零头。
 * @property fore 前景色 `0xRRGGBB`
 * @property back 背景色 `0xRRGGBB`
 * @property focusFore 焦点态字色 `0xRRGGBB`（原版 `StringStyle.ButtonColor`，默认
 *   `Config.FocusColor` = `0xFFFF00`，`ConfigData.cs:70`；HTML `bcolor` 可逐段覆盖，
 *   `HtmlManager.cs:131-149`）。焦点绘制只读本字段，**不读** [back]：原版
 *   `ConsoleStyledString.cs:105-111` 在 `isFocus` 时取 `base.ButtonColor`，
 *   而 [back] 是控制台背景色（SETBGCOLOR 的行快照），两者不是一回事。
 * @property styleMask 字体样式位 1=粗体 2=斜体 4=删除线 8=下划线（`Grid.STYLE_*`）
 * @property kind 段种类（决定绘制路径，不影响命中）
 * @property glyphs 提交时已展开的字形引用序列（TAB 已按 8 空格展开、负位移下整码点出左界的
 *   前导码点已裁掉），渲染侧只需按 [GlyphKey] 查字形缓存并从 [colStart] 起逐格铺。
 *   [graphic] 非空的段这里是**空表** —— 图形段没有字形可铺
 * @property graphic 非文本内联图形负载（M10-GFX）；null = 普通文本段。
 *   非空时 [kind] 必为 [RunKind.IMAGE] 或 [RunKind.SHAPE]，绘制侧据此走位图/色块路径，
 *   不再逐字铺 [glyphs]
 */
data class DrawCmd(
    val row: Int,
    val colStart: Int,
    val colEnd: Int,
    val fore: Int,
    val back: Int,
    val styleMask: Int,
    val kind: RunKind,
    val glyphs: List<GlyphKey>,
    val graphic: InlineGraphic? = null,
    /** 焦点态字色；见类文档的 `focusFore` 条。默认 = `Config.FocusColor`。 */
    val focusFore: Int = TextRun.DEFAULT_FOCUS_COLOR,
) {
    init {
        require(row >= 0) { "DrawCmd.row 不得为负：row=$row" }
        require(colStart in 0..colEnd) { "列区间非法：[$colStart, $colEnd)" }
    }
}
