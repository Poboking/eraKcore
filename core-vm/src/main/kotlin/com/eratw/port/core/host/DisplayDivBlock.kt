package com.eratw.port.core.host

/**
 * 零宽内嵌覆盖块 —— 原版 `ConsoleDivPart`（`MinorShift.Emuera.Runtime.Utils.EvilMask/ConsoleDivPart.cs`，
 * 下称 CDP）在 [LineBufferConsoleSink.DisplayLine] 上的落点（CONTRACT-HTMLDIV-B1 D2）。
 *
 * ## 原版形状（逐条带 C# 行号，相对 reference/emuera-cs/）
 *
 * `<div>` 在 `html2DisplayLine` 里是**递归子解析 + 零宽节点**：`HtmlManager.cs:606-648`
 * 在 `<div>` 开标签处按 width 派生 `SubDivisionWidth`（:618-635）、递归解析子行（:636-642）、
 * 然后把 `new ConsoleDivPart(...)` **塞进父节点列表当前位置**（:646）—— 所以块与其前后文本
 * 在**同一条显示行**上，且 `SetWidth` 是空实现（CDP :217-219）、`Text = string.Empty`
 * （:102）、`CanDivide = false`（:66）：折行、量宽、列游标全都吃它 0 宽
 * （`ConsoleButtonString.cs:266-270/:294-298`）。
 *
 * 绘制不走路：`ConsoleButtonString.DrawTo` 对 div 节点**整体跳过**（:361-364）；只有
 * 「逃逸块」（`FilterEscaped`，ConsoleButtonString.cs:165-185：`Top < 0 || Bottom > LineHeight`
 * 才置 `IsEscaped` 并从行条里登记出去，EmueraConsole.cs:2916-2925）才进入覆盖绘制
 * （EmueraConsole.cs:1829-1873 按 **depth 降序**逐层画，depth 0 槽画正文行；
 * `ConsoleDivPart.DrawTo` CDP :174-206 画背景/边框/圆角并把子行按 margin/padding/border
 * 收缩后的内容盒逐行落点）。**未逃逸的块不画**：背景、边框、子行全部不可见
 * —— 这是原版形状（小到塞进单行条带的 div 没有任何绘制面），照抄不修（ADR-005）。
 *
 * ## 单位
 *
 * 全部像素字段是**脚本像素**（`Utils.MixedNum.ToPixel` 口径，Utils.cs:21-45：
 * `px` 后缀原样、无后缀按 `num * Config.FontSize / 100` 整除）。
 * `Config.FontSize` 的现值由 sink 构造参数喂入（PC 基准 18，`ConfigData.cs:66`）；
 * 真机换算到设备像素归渲染层（`render` 的 `CellMetrics.scriptPixels`，与图形段同一把尺）。
 *
 * ## 与行文本的关系（KDoc 口径，别处引用）
 *
 * - 锚行文本曾**保留** div 区间原始 HTML 作过渡期内联近似；DIV-WIRE 接线后
 *   `ConsoleAdapter.stripDivSegments` 已按 [sourceRange] 把块区间从正文 run 剔除，
 *   上屏由渲染层按 [isEscaped]/几何覆盖绘制接管；
 * - [sourceRange] 记录区间在锚行**单一 HTML 段原文**里的 `[start, end)`，供
 *   GETDISPLAYLINE 重建（`ConsoleDivPart.ToString`，CDP :221-233）与
 *   HTML_GETPRINTEDSTR 剔除（`DisplayLine2Html` 对 div 节点**无分支** ——
 *   HtmlManager.cs:446-464 只处理 StyledString/ImagePart/ShapePart，块整段跳过）；
 *   null = 区间不可考（解析层没给，见构造点 KDoc）。
 *
 * @property altHeadTag 重建的 `<div ...>` 头标签（CDP :71-101 逐字节口径，见 [DivSpec.altHeadTag]）。
 *   GETDISPLAYLINE 用它 + 子行 + `</div>` 重建整块（黄金锚 `pc-golden-root.txt:71`）。
 * @property anchorCol 块锚点在锚行的**半角格列**（`<div>` 开标签处该行已排内容的行内
 *   游标格数：文本按格、`<img>`/`<shape>` 按图形格、`<div>` 0 宽）。
 *   块不占宽，后续文本列不因块后移（与原版 0 宽一致）。
 * @property anchorPointXPx 同一趟游标给出的**脚本像素**占宽（文本 = 格 × FontSize/2，
 *   `<img>`/`<shape>` 按图形像素累加）—— 对应原版 `ConsoleDivPart.PointX`
 *   （CDP :42-54，`ConsoleButtonString.CalcPointX` 的节点宽串行累加）—— 相对定位矩形的
 *   X 基准。**不是**「锚列 × FontSize/2」的等宽近似：那种算法会把 `<img>` 剥成 0 宽
 *   （原版是像素宽，ConsoleImagePart.cs:66-82），图文并排的 div 会落回行首。
 * @property xOffsetPx `ToPixel(xpos, 0)`（CDP :103）。
 * @property divXOffsetPx `margin[3] + padding[3] + border[3]`（CDP :104-115，左内缩）。
 * @property pointYPx `ToPixel(ypos, 0)`（CDP :116）= 块 `Top`（:58）。
 * @property yOffsetPx `margin[0] + padding[0] + border[0]`（CDP :117-128，上内缩）。
 * @property widthPx `ToPixel(|width|)`（CDP :72 的 `Math.Abs` 先于 :129 的 ToPixel）。
 * @property heightPx `ToPixel(|height|)`（CDP :73 + :130）；`Bottom = PointY + Height`（:60）。
 * @property subDivisionWidthPx 子层折行宽（HM :618-635：`ToPixel(width)` **取原值不取 abs**，
 *   再减去 margin/border/padding 的左右各一份）。渲染层把子行按它折行、按它算对齐偏移
 *   （HM :698、:702-704 → `ConsoleDisplayLine.SetAlignment` 的 `customWidth`，ConsoleDisplayLine.cs:80-83）。
 * @property depth 层序（CDP :132）。覆盖绘制按 depth **降序**：越大越先画（越在文字底下），
 *   depth 0 槽画正文行，负 depth 画在正文之后（EmueraConsole.cs:1829-1857 的排序与合并序）。
 * @property backgroundColor `color >= 0` 时的 `0xRRGGBB`（CDP :70 的 `| 0xFF000000`）；
 *   null = Transparent（CDP :70 的 else 支），不画底。
 * @property margin/padding/border/radius 四值表（CSS 序 top/right/bottom/left，
 *   `Utils.ParseParam4MixedNum` 的展开序 Utils.cs:247-281），已折成脚本像素
 *   （`MixedNum4ToInt4`，Utils.cs:490-500）；**空表 = 属性没写**（CDP 的 null 支）。
 * @property borderColors 四边框色 `0xRRGGBB`（CDP :90-98 的 `| 0xFF000000`）；
 *   空表 = `bcolor` 没写（此时边框取 `Config.ForeColor`，CDP :94 经 DrawBorder :94/:111）。
 * @property isRelative `display` 属性（HM :1341-1353）：缺省 true；`absolute` 时矩形锚定
 *   **画布底部**（CDP :178 的 `MainPicBox.Height - PointY - Height`）且 X 只用 [xOffsetPx]
 *   （不加锚列）、`CalcPointX` 也不推进流程游标（ConsoleButtonString.cs:294-298）。
 * @property isEscaped 逃逸标记（`FilterEscaped`，ConsoleButtonString.cs:165-185）：
 *   `Top < 0 || Bottom > 行高`（行高 = `Config.LineHeight`，下限 FontSize，Config.cs:436-439；
 *   登记 — ConsoleEscapedParts.Add — 只发生在这一支，EmueraConsole.cs:2916-2925）。
 *   **false 的块没有覆盖绘制面**（见上「原版形状」）。
 * @property children 子行（`ConsoleDivPart.Children`，CDP :38-40 的 `ConsoleDisplayLine[]`）；
 *   每行是 `<div>` 与 `</div>` 之间按 `<br>`/裸换行切出的行原文（HTML，渲染层再展平）。
 * @property sourceRange 块区间（含 `<div ...>` 与 `</div>` 标签）在锚行 HTML 段原文里的
 *   半开区间；见类文档「与行文本的关系」。`isEmpty` = 不可考（不参与重建/剔除）。
 * @property sourceSegment 区间所属的锚行段下标（[LineBufferConsoleSink.DisplayLine.segments]）。
 */
data class DisplayDivBlock(
    val altHeadTag: String,
    val anchorCol: Int,
    val anchorPointXPx: Int,
    val xOffsetPx: Int,
    val divXOffsetPx: Int,
    val pointYPx: Int,
    val yOffsetPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val subDivisionWidthPx: Int,
    val depth: Int,
    val backgroundColor: Int?,
    val margin: List<Int>,
    val padding: List<Int>,
    val border: List<Int>,
    val radius: List<Int>,
    val borderColors: List<Int>,
    val isRelative: Boolean,
    val isEscaped: Boolean,
    val children: List<DisplayDivChildLine>,
    val sourceRange: IntRange = IntRange.EMPTY,
    val sourceSegment: Int = 0,
) {
    /** 块顶（CDP :58 `Top => PointY`），脚本像素。 */
    val topPx: Int get() = pointYPx

    /** 块底（CDP :60 `Bottom => PointY + Height`），脚本像素。 */
    val bottomPx: Int get() = pointYPx + heightPx
}

/**
 * div 的**一行子行**（原版 `ConsoleDisplayLine`）：`<div>` 与 `</div>` 之间按 `<br>` /
 * 裸换行切出的行原文。原文保留标签（子行里有按钮/样式），渲染层与回读层各自消费。
 */
data class DisplayDivChildLine(val html: String)

/**
 * `Utils.MixedNum`（Utils.cs:15-99）的取值形态：数值 + 是否带 `px` 后缀。
 * 无后缀 = 「FontSize 的百分比」（`ToPixel` 按 `num * FontSize / 100` 整除，Utils.cs:27-31）。
 */
data class DivMixedNum(val num: Int, val isPx: Boolean) {
    /** `ToPixel(num, def)`（Utils.cs:21-32）；null 宿主用 [def]。 */
    fun toPixel(fontSize: Int, def: Int = 0): Int =
        if (isPx) num else num * fontSize / 100

    /**
     * `MixedNum.BuilderString`（Utils.cs:87-98）：`px` 原值加后缀；百分比**归一成像素值**
     * 且**不带**后缀（`num * FontSize / 100`）。GETDISPLAYLINE 的重建文案以它为准
     * （黄金锚 `pc-golden-root.txt:71` 的 `xpos='1600px'`；百分比形态如 `width='100'`
     * 会重建为 `width='18'`）。
     */
    fun builderString(fontSize: Int): String =
        if (isPx) "$num" + "px" else (num * fontSize / 100).toString()
}

/**
 * `<div>` 属性解析结果（HM :1225-1373 的 `case "div"` + CDP ctor :68-135 的输入侧）。
 *
 * 解析是**宽容**的（显示路径不抛）：width/height 缺失或解析失败时 [valid] = false，
 * 上层退回「无块」的旧行为；畸形属性在严格解析路（`HtmlStrictParser`，FN-02 量宽面）
 * 才按原版抛 CodeEE（HM :1360-1366 的 NotSetAttribute 等）。
 *
 * @property sourceCount 各盒模型属性的**源段数**（1..4）。原版 `AddTagMixedParam`
 *   （Utils.cs:373-410）按**引用相等**折叠重建值：1 值展开的四个元素是同一引用 → 折 1 段、
 *   2 值 → 2 段、3 值 → 3 段（`:384-391` 的判据链）、4 值 → 4 段（四个独立解析对象，
 *   哪怕数值相同也不折 —— `margin='10,10,10,10'` 重建为 `'10,10,10,10'`，引用不等）。
 *   用源段数重建这一折叠序，避免再造一套引用语义。
 * @property colorCount `bcolor` 的源段数。原版 `AddColorParam4`（Utils.cs:422-455）按
 *   **值相等**折叠（Color 是值类型）：四值同值折 1 段、`[0]==[2] && [1]==[3]` 折 2 段、
 *   `[1]==[3]` 折 3 段。与盒模型的引用口径**不同**，必须分开记。
 */
data class DivSpec(
    val xPos: DivMixedNum?,
    val yPos: DivMixedNum?,
    val width: DivMixedNum?,
    val height: DivMixedNum?,
    val depth: Int,
    /** `color` 属性的 `0xRRGGBB`；null = 没写 / Transparent（CDP :70 的 else 支）。 */
    val color: Int?,
    val box: DivBoxSpec?,
    val isRelative: Boolean,
) {
    /** width/height 必填（HM :1360-1366）；缺一不可成块。 */
    val valid: Boolean get() = width != null && height != null

    /**
     * CDP ctor :71-101 的 `altHeadTag` 逐字节重建。
     *
     * 顺序与省略规则（全部照原版）：
     * 1. `"<div"`；
     * 2. `AddTagMixedNumArg(xpos/ypos/width/height)`（Utils.cs:364-371）：**null 或 num==0
     *    整条不写**（黄金锚里 ypos='-937px' 在场、xpos='0' 会缺席）；width/height 先取
     *    **绝对值**（CDP :72-73 的 `Math.Abs` 发生在重建**之前**）；
     * 3. `AddColorParam(color)`（Utils.cs:412-420）：Transparent（没写）不写；值格式
     *    `GetColorToString`（HM :856-862）：`#` + 六位**大写**十六进制；
     * 4. 盒模型 `AddTagMixedParam(margin/padding/border/radius)`（Utils.cs:373-410）：
     *    按 [DivBoxSpec] 各属性的源段数折叠；
     * 5. `AddColorParam4(bcolor)`（Utils.cs:422-455）：按值相等折叠（见 [DivSpec.colorCount]）；
     * 6. `">"`。
     *
     * 百分比值按 [DivMixedNum.builderString] 归一（Utils.cs:87-98 的 `BuilderString`）。
     */
    fun altHeadTag(fontSize: Int): String {
        val sb = StringBuilder()
        sb.append("<div")
        addTagMixedNumArg(sb, "xpos", xPos, fontSize)
        addTagMixedNumArg(sb, "ypos", yPos, fontSize)
        // CDP :72-73：width/height 的 Math.Abs 在重建之前发生（原地改写同一个对象）
        addTagMixedNumArg(sb, "width", width?.let { DivMixedNum(kotlin.math.abs(it.num), it.isPx) }, fontSize)
        addColorParam(sb, "color", color)
        addTagMixedNumArg(sb, "height", height?.let { DivMixedNum(kotlin.math.abs(it.num), it.isPx) }, fontSize)
        val box = box
        if (box != null) {
            addTagMixedParam(sb, "margin", box.margin, box.marginCount, fontSize)
            addTagMixedParam(sb, "padding", box.padding, box.paddingCount, fontSize)
            addTagMixedParam(sb, "border", box.border, box.borderCount, fontSize)
            addTagMixedParam(sb, "radius", box.radius, box.radiusCount, fontSize)
            val bc = box.color
            if (bc != null) addColorParam4(sb, "bcolor", bc)
        }
        sb.append(">")
        return sb.toString()
    }

    /** `Utils.AddTagMixedNumArg`（Utils.cs:364-371）。 */
    private fun addTagMixedNumArg(sb: StringBuilder, name: String, num: DivMixedNum?, fontSize: Int) {
        if (num == null || num.num == 0) return
        sb.append(' ').append(name).append("='").append(num.builderString(fontSize)).append('\'')
    }

    /** `Utils.AddColorParam`（Utils.cs:412-420）：没写（Transparent）整条不写。 */
    private fun addColorParam(sb: StringBuilder, name: String, rgb: Int?) {
        if (rgb == null) return
        sb.append(' ').append(name).append("='").append(colorString(rgb)).append('\'')
    }

    /**
     * `Utils.AddTagMixedParam`（Utils.cs:373-410）的折叠序，按**源段数**重建
     * （引用相等口径的等价物，见 [DivSpec.sourceCount]）：
     * 1 段 → 单值；2 段 → 两值；3 段 → 三值；4 段 → 四值。
     */
    private fun addTagMixedParam(
        sb: StringBuilder,
        name: String,
        nums: List<DivMixedNum>,
        sourceCount: Int,
        fontSize: Int,
    ) {
        // 该属性没写（盒模型里的其它属性写了但这条没写）→ 整条省略（原版 null 直返，Utils.cs:375-377）
        if (nums.isEmpty() || sourceCount <= 0) return
        sb.append(' ').append(name).append("='")
        val n = sourceCount.coerceIn(1, nums.size)
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append(nums[i].builderString(fontSize))
        }
        sb.append('\'')
    }

    /**
     * `Utils.AddColorParam4`（Utils.cs:422-455）的折叠序：**值相等**判据（与盒模型的
     * 引用口径不同）—— 全同 → 1 值；`[0]==[2] && [1]==[3]` → 2 值；`[1]==[3]` → 3 值；
     * 其余 → 4 值。
     */
    private fun addColorParam4(sb: StringBuilder, name: String, colors: List<Int>) {
        sb.append(' ').append(name).append("='")
        when {
            colors[0] == colors[1] && colors[0] == colors[2] && colors[0] == colors[3] ->
                sb.append(colorString(colors[0]))
            colors[0] == colors[2] && colors[1] == colors[3] ->
                sb.append(colorString(colors[0])).append(',').append(colorString(colors[1]))
            colors[1] == colors[3] ->
                sb.append(colorString(colors[0])).append(',').append(colorString(colors[1]))
                    .append(',').append(colorString(colors[2]))
            else -> colors.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append(colorString(c))
            }
        }
        sb.append('\'')
    }

    /** `HtmlManager.GetColorToString`（HM :856-862）：`#` + 六位大写十六进制。 */
    private fun colorString(rgb: Int): String {
        val hex = (rgb and 0xFFFFFF).toString(16).uppercase(java.util.Locale.ROOT)
        return "#" + "0".repeat(6 - hex.length) + hex
    }

    /**
     * CDP ctor :103-134 的几何折算（脚本像素）。
     *
     * @param fontSize `Config.FontSize`（折算基数）。
     */
    fun geometry(fontSize: Int): DivGeometry {
        val box = box
        val marginPx = box?.margin?.map { it.toPixel(fontSize) }
        val paddingPx = box?.padding?.map { it.toPixel(fontSize) }
        val borderPx = box?.border?.map { it.toPixel(fontSize) }
        var xOffset = xPos?.toPixel(fontSize) ?: 0
        var divXOffset = 0
        marginPx?.getOrNull(3)?.let { divXOffset += it }
        paddingPx?.getOrNull(3)?.let { divXOffset += it }
        borderPx?.getOrNull(3)?.let { divXOffset += it }
        val pointY = yPos?.toPixel(fontSize) ?: 0
        var yOffset = 0
        marginPx?.getOrNull(0)?.let { yOffset += it }
        paddingPx?.getOrNull(0)?.let { yOffset += it }
        borderPx?.getOrNull(0)?.let { yOffset += it }
        // CDP :72-73 的 Math.Abs 只改 num，isPx 不动；:129-130 再 ToPixel
        val w = width!!.let { DivMixedNum(kotlin.math.abs(it.num), it.isPx).toPixel(fontSize) }
        val h = height!!.let { DivMixedNum(kotlin.math.abs(it.num), it.isPx).toPixel(fontSize) }
        // SubDivisionWidth（HM :618-635）：ToPixel(width) 用**原值**（不 abs），再减
        // margin/border/padding 的左右（[3]+[1]）各一份
        var subDiv = width.toPixel(fontSize)
        marginPx?.let { subDiv -= it.getOrElse(3) { 0 } + it.getOrElse(1) { 0 } }
        borderPx?.let { subDiv -= it.getOrElse(3) { 0 } + it.getOrElse(1) { 0 } }
        paddingPx?.let { subDiv -= it.getOrElse(3) { 0 } + it.getOrElse(1) { 0 } }
        return DivGeometry(
            xOffsetPx = xOffset,
            divXOffsetPx = divXOffset,
            pointYPx = pointY,
            yOffsetPx = yOffset,
            widthPx = w,
            heightPx = h,
            subDivisionWidthPx = subDiv,
            marginPx = marginPx ?: emptyList(),
            paddingPx = paddingPx ?: emptyList(),
            borderPx = borderPx ?: emptyList(),
            radiusPx = box?.radius?.map { it.toPixel(fontSize) } ?: emptyList(),
        )
    }
}

/**
 * 盒模型四属性（`Utils.StyledBoxModel`，Utils.cs:101-112）+ 源段数。
 * 元素序 CSS：top/right/bottom/left（`ParseParam4MixedNum` 的展开序，Utils.cs:247-281）。
 */
data class DivBoxSpec(
    val margin: List<DivMixedNum>,
    val marginCount: Int,
    val padding: List<DivMixedNum>,
    val paddingCount: Int,
    val border: List<DivMixedNum>,
    val borderCount: Int,
    val radius: List<DivMixedNum>,
    val radiusCount: Int,
    /** `bcolor` 四值（`0xRRGGBB`）；null = 没写（HM :1337-1340 只在写了 `bcolor` 时建表）。 */
    val color: List<Int>?,
    /** `bcolor` 源段数（值相等折叠用，见 [DivSpec.colorCount]）。 */
    val colorCount: Int,
)

/** [DivSpec.geometry] 的产物：全脚本像素。 */
data class DivGeometry(
    val xOffsetPx: Int,
    val divXOffsetPx: Int,
    val pointYPx: Int,
    val yOffsetPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val subDivisionWidthPx: Int,
    val marginPx: List<Int>,
    val paddingPx: List<Int>,
    val borderPx: List<Int>,
    val radiusPx: List<Int>,
)

/**
 * `Utils.ParseMixedNum`（Utils.cs:342-362）的宽容版：`"N"`（百分比）/ `"Npx"`（像素，大小写
 * 不敏感）；解析失败返回 null（严格路的 CodeEE 归 `HtmlStrictParser`，显示路径不抛）。
 */
fun parseDivMixedNum(value: String): DivMixedNum? {
    var s = value.trim()
    if (s.isEmpty()) return null
    var px = false
    if (s.endsWith("px", ignoreCase = true)) {
        px = true
        s = s.substring(0, s.length - 2).trim()
    }
    val n = s.toIntOrNull() ?: return null
    return DivMixedNum(n, px)
}

/**
 * `Utils.ParseParam4MixedNum`（Utils.cs:247-281）的宽容版：1 值四份同引用（折叠成 1 段）、
 * 2 值 `[v0,v1,v0,v1]`、3 值 `[v0,v1,v2,v1]`、4 值原样；段数越界返回 null。
 * 返回 `(四值表, 源段数)`。
 */
fun parseDivBoxParam(value: String): Pair<List<DivMixedNum>, Int>? {
    val parts = value.split(',')
    val parsed = parts.map { parseDivMixedNum(it.trim()) }
    if (parsed.any { it == null }) return null
    return when (parts.size) {
        1 -> {
            val v = parsed[0]!!
            listOf(v, v, v, v) to 1
        }
        2 -> {
            val (v0, v1) = parsed[0]!! to parsed[1]!!
            listOf(v0, v1, v0, v1) to 2
        }
        3 -> {
            val (v0, v1, v2) = Triple(parsed[0]!!, parsed[1]!!, parsed[2]!!)
            listOf(v0, v1, v2, v1) to 3
        }
        4 -> parsed.map { it!! } to 4
        else -> null
    }
}
