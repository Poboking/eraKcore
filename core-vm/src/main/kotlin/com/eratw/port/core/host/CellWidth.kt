package com.eratw.port.core.host

/**
 * core 侧**唯一**的字符宽度模型：半角 1 格 / 全角 2 格。
 *
 * ## 为什么要有这个对象
 *
 * `LineBufferConsoleSink` 的折行、PRINTC 补齐、DRAWLINE 填充、按钮跨行拆分、
 * 以及 M4 渲染层需要的「段起始列」，都必须用同一套换算。四处各写一份的后果是
 * 「同一行在不同使用者眼里宽度不同」——按钮命中区与字形位置会错位。
 *
 * ## 单位与真机口径
 *
 * 单位是**半角格**（cell），不是像素，也不是 UTF-16 长度：
 * - 原版按像素测量（`StringMeasure.GetDisplayLength`，StringMeasure.cs:42-72，
 *   调用点 EmueraConsole.cs:3429/3434 的 DRAWLINE 填充、:3270-3280 的 PRINTC 补齐）；
 * - 真机上唯一权威是 `:render` 的 `GlyphCache.measureCells`（`render/.../glyph` 包）：
 *   宿主先量像素再换算成半角格，把结果作为 `clientWidth` 传进来；
 * - 本对象是**没有字体度量时**的确定性兜底（headless 测试、等宽字体），
 *   不作为真机语义：比例字体（如 `SETFONT "Saitamaar"`，
 *   空格 400 / 数字 640 / 拉丁 800 / CJK 1280，见 BOARD「M4-REALCHECK」）下
 *   两者不等价，真机折行以 GlyphCache 为准。
 *
 * 表来源 = **CP932(Shift-JIS) 字节数**（半角 1 字节 / 全角 2 字节），判定式与逐点出处写在
 * [isFullWidth]。原版屏上宽度问 GDI 像素、PRINTC 补齐宽度问字节数（`EmueraConsole.cs:3255`），
 * 等宽日文字体下两者同结论；基准 `pc-golden-root.txt:61`（99 个 `━` 填满 1797px 可绘制宽度）
 * 把 East Asian Width 里的 Ambiguous 一类符号钉成全角，故不按 EAW 建表。
 * `core/fn` 侧曾另有两份 EAW 副本（`fn/HtmlMethods.kt` 的 approxTextWidth、
 * `fn/DebugMiscMethods.kt` 的 measureWidth）加第三份「逐码元 code 小于 0x80」的判据
 * （`fn/GraphicsMethods.kt`），已由 wave/h-fncopy 全部收进本表——像素换算层各留各的，
 * **名单只有一份**（回归尺子：`core/fn/FnWidthSingleSourceTest`，三处读数必须等于
 * `CellWidth.of(s) x 9`）。
 *
 * 与「字节数」有关但**不归本表**的一条：`STRLEN`/`GETSUBSTRING` 一族走的是
 * `LangManager.setEncode(Config.Encode)` 的**配置码页**，本语料 `emuera.config:76` 是
 * 「EMUERA INTERFACE LANGUAGE:简体中文」⇒ 运行期码页是 936 而不是 932；半角假名在 932 是
 * 1 字节、在 936 是 2 字节。BOARD 的 STRLEN-CODEPAGE 行在管这件事，别把它和显示宽度混成一个数。
 *
 * TODO(verify)：emoji（U+1F300..U+1FAFF、U+2600..U+27BF）在 PC 基准里未出现，
 * 本表按「CP932 表外字 ⇒ 全角 2 格」处理（与 [isFullWidth] 的表外字同一条卡点）；
 * 接真机字体度量后以 GlyphCache 的 advanceCells 为准。
 */
/**
 * 宿主提供的**图元像素尺寸**（脚本像素，宽 × 高）。
 *
 * 只给 `CellWidth` 的「`<img>` 缺 width 时按基图纵横比推宽」这一支用
 * （原版 `ConsoleImagePart.cs:66-70`：`Width = DestBaseSize.Width * 高 / DestBaseSize.Height`）。
 * 生产装配点把 `RuntimeAux` 里 `SpritePool` 的登记尺寸包成这个形态喂进来
 * （与 `BuiltinHost.imageSizeOf` 同一份表）；headless / 纯 JVM 装置不喂 ⇒ 缺 width 的
 * `<img>` 仍计 0 格，读数与接线前逐位一致。喂了而查不到（或 [heightPx] ≤ 0）⇒ 原版
 * 单出口：AltText 当文本计（`ConsoleImagePart.cs:60-65` + `:104-115`）。
 */
data class ImageSizePx(val widthPx: Int, val heightPx: Int)

object CellWidth {

    /** 半角字符占格数。 */
    const val HALF = 1

    /** 全角字符占格数。 */
    const val FULL = 2

    /**
     * TAB（`U+0009`）的占格数：**8 个半角格**。
     *
     * 原版在量宽前先 `Replace("\t", "        ")`（`StringMeasure.cs:59-62`），即 TAB 的有效
     * 宽度 = 8 个空格；本仓渲染侧同样把 TAB 展开成 8 空格（`render` 的 `LineFolder` /
     * `Grid` 口径）。core 若把 TAB 记 1 格（CP932 字节数口径的顺带结果），折行预算、
     * 行宽、div 锚点、`GETLINESTR` 的重复列都会与屏上实占差 7 格 —— 两侧必须同一把尺。
     *
     * 注意这与「CP932 里 TAB 是 1 字节」并不矛盾：[`isFullWidth`] 记的是**字节数**，
     * 而这里是**显示宽度**（原版显示宽度走像素度量，度量前已展开 TAB）。
     */
    const val TAB_CELLS = 8

    /**
     * 单个码位的 **CP932 字节数**占格（[of] 的口径）。
     *
     * 只接受**完整码位**（`String.codePointAt` 的返回值），不接受 UTF-16 代理项：
     * 单独传一个代理项半会落进「其余一律全角」按 2 格计（与 [isFullWidth] 的判据一致）；
     * 把一个增补平面字符按两个代理项分别传入会得到 4 格，按码位传入才是 2 格。
     * 遍历一律用 `i += Character.charCount(cp)`，见 [of]。
     *
     * **TAB 在这里是 1 格**（CP932 单字节），这是字节数口径的正确读数；显示宽度请用
     * [displayCodePoint]（TAB = [TAB_CELLS] 格）。
     */
    fun ofCodePoint(cp: Int): Int = if (isFullWidth(cp)) FULL else HALF

    /**
     * 单个码位的**显示宽度**（半角格）：与 [ofCodePoint]（字节数口径）的差别只有两处，
     * 两处都必须与渲染/原版同值：
     *
     * 1. **TAB**：展开成 [TAB_CELLS] 个空格（`StringMeasure.cs:59-62`，渲染侧
     *    `Grid.expandTabs` 同口径）；
     * 2. **增补平面**（`cp > 0xFFFF`）：渲染侧按**两个 UTF-16 码元各 2 格 = 4 格**建模 ——
     *    `glyph/CellMetrics.cellsOf` 显式如此（一个增补码位 = 高/低代理各一个码元、各按
     *    全角 2 格），`GlyphCache` / `DisplayList` / `ButtonLayer` 的推进与命中列都读它；
     *    布局若按「一个码位 2 格」（[ofCodePoint] 的字节数口径）就会比实际落笔窄一半，
     *    emoji 之后的文字被画到布局预留位置的右边。⇒ 显示宽度必须给 4。
     *
     * 折行预算、行宽、div 锚点、PRINT 补位、`GETLINESTR` 的重复填充都是**显示宽度**，
     * 必须用本函数；[ofCodePoint] 只用于「字节数/字符数」语义的场合
     * （例：`EmueraConsole.cs:3255` 的字节数规则、`GDRAWTEXT` 的单码位步进）。
     */
    fun displayCodePoint(cp: Int): Int = when {
        cp == '\t'.code -> TAB_CELLS
        cp > 0xFFFF -> 2 * FULL
        else -> ofCodePoint(cp)
    }

    /**
     * 整串的**显示宽度**（半角格）：逐码位走 [displayCodePoint]。
     *
     * 这是 core 侧所有「这一串在屏上占多宽」的唯一入口（与渲染侧 `Grid.cells` 同口径：
     * TAB 展开 8 空格、增补平面 4 格/Lone 代理 2 格）；[of] 保持字节数口径不动
     * （既有调用点与测试钉的就是它）。
     */
    fun displayCells(text: String): Int {
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += displayCodePoint(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * 整串的占格数。按码位遍历，代理对（增补平面）不会被拆成两个半角。
     *
     * 这是折行/补齐/按钮列区间的统一入口；调用方不得再自行累加。
     */
    fun of(text: String): Int {
        var w = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += ofCodePoint(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * HTML 片段的**可见占格数**：跳过普通标签、解析实体后按 [ofCodePoint] 累加；
     * [graphicFontPx] > 0 时图形标签（`<img>` / `<shape>`）按渲染侧同式计入格宽。
     *
     * 这是 HTML_PRINTC/HTML_PRINTLC 的补齐计量与 [LineBufferConsoleSink] 折行共用的
     * 同一把尺（两者各自调它，不许再写第三份）：补齐额若与折行的可见宽口径不同源，
     * 补出来的列在折行眼里就不是定宽列，逐项制表的网格会散（2026-11-04 玩家反馈的
     * 「选项左右斜斜 + 中途换行」病根之一）。
     *
     * 文本口径与原版量宽的可见口径对齐：
     * - 普通标签体不算宽。标签结束的判法与 HTML 解析侧同源（引号态）：`value="a>b"` 这类
     *   属性值里的裸 `>` 不是标签结尾 —— 逢 `>` 即出标签会把「`>` 之后的属性文本」
     *   当可见字符计宽，折行用的宽度与渲染宽度错位（sink 侧 H5 修正的同一条）；
     * - 未闭合的 `<` 走到串尾都算标签内（宽容路，与解析侧 findTagEnd 的 -1 分支一致）；
     * - 实体（`&amp;`/`&#x41;`/`&#65;` 与未知名）解出码位后按 [ofCodePoint] 计，
     *   找不到 `;` 的裸 `&` 按普通字符 1 格计。
     *
     * 图形口径（[graphicFontPx] > 0 才启用；传 0 = 只算文本，headless 缺省）逐形对齐
     * 渲染侧 `HtmlRunFlatten` 的落宽模型（2026-11-05 遗留①收口）：
     * - `<img width=…>`：px 形态原值、百分比形态按脚本字号折算（[graphicScriptPx]），
     *   经 [graphicCells] 计格；`width` 缺失/为 0 时按宿主 [ImageSizePx] 的基图纵横比推宽
     *   （原版 `ConsoleImagePart.cs:66-71`）；
     * - `<img>` **图元取不到**（宿主查表未命中、或登记的基图高 ≤ 0）⇒ 原版单出口
     *   （`ConsoleImagePart.cs:60-65`）：`Text = AltText`、宽度按文本度量（`:104-115`）——
     *   **显式 `width` 也不生效**（原版 `return` 在整段尺寸计算之前），这里计
     *   [displayCells] 的格数。宿主**未接线**（[imageSizeOf] 为 null）时无法判 miss，
     *   保持旧读数（显式 `width` 计图形格、缺 `width` 计 0），与 sink 行内游标同一口径；
     * - `<shape type='space'>` 单参、`type='rect'>` 单参且 num>0：同 img 计格；
     * - `rect` 四参（x≥0 && w>0 && h>0）：占宽 = **w**（渲染按 w 落格，横向偏移不推进）；
     * - 其余（未知 type、参数不合、rect 校验不过）：渲染落重建文案按文本画
     *   （`ConsoleErrorShapePart`，ConsoleShapePart.cs:71-74），这里按标签原文近似计文本格
     *   （重建文案与原文长度同阶）；这一支**不受** `graphicFontPx > 0` 门控 —— 错误形态
     *   在 headless 下同样按文本计，与行内游标同尺（见 [isInvalidShapeTag]）；
     * - `<div>`：原版本就不占行宽（`ConsoleDivPart.cs:217-219` SetWidth 为空），计 0。
     */
    fun visibleHtmlCells(
        s: String,
        graphicFontPx: Long = 0L,
        imageSizeOf: ((String) -> ImageSizePx?)? = null,
    ): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                val end = htmlTagEnd(s, i)
                val closed = end > i && s[end - 1] == '>'
                if (!closed) {
                    // 未闭合的 `<`：原版 `\<[^<]*\>` 正则**不吞**没有 `>` 的尾巴
                    // （`HtmlManager.cs:714-717`）⇒ 余下按文本计。旧实现把它当标签一路吃到
                    // 串尾（记 0 格），而 div 锚点游标按文本计 —— 同一个 sink 两把尺。
                    var j = i
                    while (j < s.length) {
                        val cp = s.codePointAt(j)
                        w += displayCodePoint(cp)
                        j += Character.charCount(cp)
                    }
                    break
                }
                val tag = s.substring(i, end)
                if (graphicFontPx > 0L) {
                    w += htmlTagWidth(tag, graphicFontPx, imageSizeOf)
                } else if (isInvalidShapeTag(tag)) {
                    // headless 不折算图形，但错误形态 <shape> 原版落 ConsoleErrorShapePart
                    // （文本回落，ConsoleShapePart.cs:71-74 + ConsoleErrorShapePart.cs:28-37）：
                    // 渲染画文本、按文本度量，折行也必须计文本格，与行内游标同一把尺。
                    // 合法图形在 headless 下仍记 0 格（既有读数，由 graphicFontPx > 0 门控）。
                    w += invalidShapeCells(tag)
                }
                // `<div>` 是**零宽节点**：`ConsoleDivPart.SetWidth` 空实现（CDP:217-219），
                // 子行是独立 display line ⇒ 子行文本也不进父行宽。旧实现只把 `<div>` 标签
                // 记 0，却把块内文本照常累加（一行 N 张卡的父行宽 = 全部卡片的文字总宽），
                // 折行预算/PRINTN 合并位移随之偏大。这里整块跳过（原版嵌套 div 抛 NestedTag，
                // 宽容为「跳过到第一个 `</div>`」）。
                i = if (isDivOpenTag(tag)) divBlockEnd(s, end) else end
                continue
            }
            if (c == '&') {
                val end = s.indexOf(';', i + 1)
                if (end > i) {
                    val entity = s.substring(i + 1, end)
                    val cp = entityCodePoint(entity)
                    if (cp != null) {
                        w += ofCodePoint(cp)
                    } else {
                        // 未知命名实体 / 越界数字实体：原版 `numericEntity` 只认 0..65535
                        // （`HtmlManager.cs:817-831`），认不出就把 `&` 原样留在文本里 ⇒ 字面量
                        // 按半角字符计（`&…;` 共 end-i+1 个码位）。否则 `&#x1F600;` 会被算成
                        // 1 个全角格，而锚点侧（Unescape 保留字面量）算 9 格 —— 又一处两把尺。
                        w += (end - i + 1) * HALF
                    }
                    i = end + 1
                    continue
                }
            }
            val cp = s.codePointAt(i)
            w += displayCodePoint(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /**
     * `&名称;` / `&#123;` / `&#x7B;` 的码位；**认不出或越界返回 null**（调用方按字面量计宽）。
     *
     * 口径与原版一致：命名实体只有那 6 个（`HtmlManager.cs:769-778`），数字实体范围
     * 0..65535（`:817-831`，增补平面 `&#x1F600;` 属于**原版也解不出**的形态）。
     */
    internal fun entityCodePoint(entity: String): Int? = when (entity) {
        "lt" -> '<'.code
        "gt" -> '>'.code
        "amp" -> '&'.code
        "quot" -> '"'.code
        "apos" -> '\''.code
        "nbsp" -> ' '.code
        else -> when {
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull(10)
            else -> null
        }
    }?.takeIf { it in 0..0xFFFF }

    /** 是否是 `<div …>` 开标签（含 `</div>` 之外的 `<divx>` 边界判断，同 [tagStartsWith]）。 */
    internal fun isDivOpenTag(tag: String): Boolean =
        tag.length > 1 && tag[1] != '/' && tagStartsWith(tag, "div")

    /**
     * `<div …>` 块的结束下标（exclusive，指向 `</div>` 之后）：原版块**零宽**，
     * 父行量宽要整块跳过。找不到闭合标签（宽容路径）时返回串尾。
     */
    internal fun divBlockEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            if (lt < 0) return s.length
            if (lt + 5 <= s.length && s.regionMatches(lt, "</div", 0, 5, ignoreCase = true)) {
                val end = htmlTagEnd(s, lt)
                return if (end > lt && s[end - 1] == '>') end else s.length
            }
            i = lt + 1
        }
        return s.length
    }

    /**
     * 标签体的结束下标（**不含** `>` 本身，返回exclusive端点）：引号态扫描，
     * `value="a>b"` 的裸 `>` 不出标签；未闭合的 `<` 走到串尾。
     */
    internal fun htmlTagEnd(s: String, start: Int): Int {
        var i = start + 1
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c == '>' -> return i + 1
            }
            i++
        }
        return s.length
    }

    /**
     * 单个标签对宽度的贡献（[visibleHtmlCells] 的图形扫描支）。
     * 普通标签 0；`<img>` / `<shape>` 按类文档的逐形模型。
     */
    private fun htmlTagWidth(
        tag: String,
        graphicFontPx: Long,
        imageSizeOf: ((String) -> ImageSizePx?)?,
    ): Int {
        val px = graphicWidthPxOrNull(tag, graphicFontPx, imageSizeOf)
        if (px != null) return graphicCells(px, graphicFontPx)
        // 原版单出口（`ConsoleImagePart.cs:60-65`）：图元取不到 ⇒ `Text = AltText`，宽度按
        // 文本度量（`:104-115`）——**连显式 `width` 也不生效**（那个 `return` 在整段尺寸计算
        // 之前）。触发面 = 宿主已接线且查表未命中 / 基图高 ≤ 0；未接线时
        // [imgWidthPxOrNull] 仍按旧口径给数，不会落到这里。
        if (isImgTag(tag)) return imgAltText(tag, graphicFontPx)?.let { displayCells(it) } ?: 0
        // 落不到图形形态的 `<shape>`：渲染画重建文案（按文本），格走 invalidShapeCells
        // （与行内游标同一份 displayCells）。判据与折行 headless 支同取 isInvalidShapeTag。
        return if (isInvalidShapeTag(tag)) invalidShapeCells(tag) else 0
    }

    /**
     * 图形标签（`<img>` / 合法 `<shape space|rect>`）的**脚本像素宽**；不是图形标签、
     * 或形态落空（参数不合 / rect 校验不过）时返回 null（调用方按普通标签或错误形态处理）。
     *
     * 这是 core 侧**唯一**的「图元占宽」实现：`visibleHtmlCells`（折行/制表预算）与
     * `LineBufferConsoleSink` 的行内游标（div 锚点像素）都从这里取同一个数，逐形口径见
     * [visibleHtmlCells] 的类文档。`<img>` 缺 width 时按 [ImageSizePx] 的基图纵横比推宽
     * （原版 `ConsoleImagePart.cs:66-70`）。
     *
     * **接线后查表未命中**（或基图高 ≤ 0）返回 null：原版这条走 `Text = AltText` 的
     * 单出口（`ConsoleImagePart.cs:60-65`），不再是「图形宽 0」。宿主未接线时仍返回旧读数
     * （显式 `width` 的图形宽 / 缺 `width` 的 0），与 sink 行内游标同口径。
     */
    internal fun graphicWidthPxOrNull(
        tag: String,
        graphicFontPx: Long,
        imageSizeOf: ((String) -> ImageSizePx?)?,
    ): Long? {
        if (graphicFontPx <= 0L) return null
        val isImg = tagStartsWith(tag, "img")
        val isShape = isShapeTag(tag)
        if (!isImg && !isShape) return null
        if (isImg) return imgWidthPxOrNull(tag, graphicFontPx, imageSizeOf)
        // <shape>：type + param 的形态判据唯一取自 shapeGraphicNum（与折行、行内游标同一份）。
        val num = shapeGraphicNum(tag) ?: return null
        return graphicScriptPx(num.first, num.second, graphicFontPx)
    }

    /**
     * `<img>` 的脚本像素宽：原版先查图元（`ConsoleImagePart.cs:60-65`），查不到 ⇒ 整个
     * 尺寸段被 `return` 跳过（**显式 `width` 也不生效**），节点宽改按 AltText 文本度量；
     * 查得到时再按显式 `width`（[graphicScriptPx]）或「高 × 基图纵横比」推宽
     * （`:66-82`：`height` 缺省/0 → `FontSize`，px 原值、百分比 `FontSize*num/100` 整除）。
     *
     * 返回值约定：数字 = 图形宽；**null = 图元 miss**（宿主已接线但查表未命中，或登记的
     * 基图高 ≤ 0）—— 调用方落原版单出口。宿主**未接线**（[imageSizeOf] 为 null）时无法
     * 判 miss，保持旧读数：显式 `width` 给图形宽、缺 `width` 给 0。
     */
    private fun imgWidthPxOrNull(
        tag: String,
        graphicFontPx: Long,
        imageSizeOf: ((String) -> ImageSizePx?)?,
    ): Long? {
        val width = tagAttr(tag, "width")?.let { parseMixedNum(it) }
        val src = tagAttr(tag, "src")
        if (imageSizeOf != null && src != null) {
            val size = imageSizeOf.invoke(src)
            // 原版单出口：查表在尺寸计算**之前**，miss 时 width/height 一概不看。
            if (size == null || size.heightPx <= 0) return null
            if (width != null && width.first != 0L) {
                // 负宽**取绝对值**：原版 `ConsoleImagePart.cs:83-87` 先把 destRect 归一到
                // `(X=|W|, Width=-|W|)`，节点的 `Width` 因此是正数 —— 负号是「镜像绘制」信息，
                // 不是「往回退这么多格」。端口把镜像落成 `InlineGraphic.Image.flipX`，占宽
                // 与行宽都必须是正的（否则 `<img width='-180px'>` 后面的一切都会往左跑）。
                return kotlin.math.abs(graphicScriptPx(width.first, width.second, graphicFontPx))
            }
            val height = tagAttr(tag, "height")?.let { parseMixedNum(it) }
            val num = when {
                height == null || height.first == 0L -> graphicFontPx
                height.second -> height.first
                else -> graphicFontPx * height.first / 100
            }
            // 派生宽同样取绝对值（负高按纵横比派生出的负宽 = 原版两轴齐翻的形态，见 `:88-92`）。
            return kotlin.math.abs(size.widthPx.toLong() * num / size.heightPx)
        }
        if (width != null && width.first != 0L) {
            return kotlin.math.abs(graphicScriptPx(width.first, width.second, graphicFontPx))
        }
        // 宿主未接线（判不了 miss）或标签没有 src：保持旧读数 0，与 sink 行内游标同一把尺。
        return 0L
    }

    /** AltText 里按 `height` → `width` → `ypos` 次序写出的数值属性（`ConsoleImagePart.cs:52-57`）。 */
    private val IMG_ALT_TEXT_NUM_ATTRS = arrayOf("height", "width", "ypos")

    /**
     * `<img>` 的 **AltText 重建文案**（`ConsoleImagePart.cs:44-59` 的逐字段复刻）：
     * `<img src='…'>` 打头，`srcb` 非 null 就写、`srcm` 非空才写，`height` / `width` /
     * `ypos` 只在原始 num != 0 时写（`Utils.cs:364-371`，次序同 `:52-57`）；px 形态带
     * `px` 后缀、百分比形态落 `num × 字号 / 100` 的整除值（`Utils.cs:87-95` 的
     * `BuilderString`）。
     *
     * 属性值按标签原文取用（展平侧 `HtmlRunFlatten.parseAttrs` 同样不解实体，三条链拿到
     * 同一串）。`src` 缺失返回 null：原版解析期就抛 CodeEE（`HtmlManager.cs:1222-1225`），
     * 到不了图元节点，调用方按 0 格处理即可。
     */
    internal fun imgAltText(tag: String, fontSize: Long): String? {
        val src = tagAttr(tag, "src") ?: return null
        val sb = StringBuilder("<img src='")
        sb.append(src).append('\'')
        tagAttr(tag, "srcb")?.let { sb.append(" srcb='").append(it).append('\'') }
        tagAttr(tag, "srcm")?.takeIf { it.isNotEmpty() }?.let {
            sb.append(" srcm='").append(it).append('\'')
        }
        for (name in IMG_ALT_TEXT_NUM_ATTRS) {
            val num = tagAttr(tag, name)?.let { parseMixedNum(it) } ?: continue
            if (num.first == 0L) continue
            sb.append(' ').append(name).append("='")
            if (num.second) {
                sb.append(num.first).append("px")
            } else {
                sb.append(num.first * fontSize / 100)
            }
            sb.append('\'')
        }
        sb.append('>')
        return sb.toString()
    }

    /** `tagname` 边界匹配（`<img` 后必须是空白/`>`/`/`，`<imgx>` 不算）。 */
    internal fun tagStartsWith(tag: String, name: String): Boolean {
        if (tag.length < name.length + 1) return false
        if (!tag.regionMatches(1, name, 0, name.length, ignoreCase = true)) return false
        return tag.length == name.length + 1 || !tag[name.length + 1].isLetterOrDigit()
    }

    /** 是否是 `<img …>` 标签（边界判同 [tagStartsWith]）。 */
    internal fun isImgTag(tag: String): Boolean = tagStartsWith(tag, "img")

    /** 是否是 `<shape …>` 标签（含形态落空的那些）。 */
    internal fun isShapeTag(tag: String): Boolean = tagStartsWith(tag, "shape")

    /**
     * `<shape>` 的合法图形形态 → `(num, isPx)`；落不到合法形态（未知 type、param 个数不合、
     * rect 四参校验不过、单参 rect 的 num <= 0）返回 null。
     *
     * 判据逐条照 `ConsoleShapePart.CreateShape`（ConsoleShapePart.cs:48-74）：switch 只认
     * space 单参、rect 单参且 num > 0、rect 四参且 x >= 0 且 w > 0 且 h > 0，其余落到
     * `ConsoleErrorShapePart`（:71-74）。这是 core 侧**唯一**一份 shape 形态判据：
     * [graphicWidthPxOrNull] 取它的 num 折脚本 px，[isInvalidShapeTag] 取它的 null 判定
     * 错误形态，折行 [visibleHtmlCells] 与 sink 行内游标都消费后者 —— 不再各解析一遍。
     *
     * 与 [graphicWidthPxOrNull] 的 `graphicFontPx <= 0` 早退无关：本函数只判形态、不折像素，
     * headless 下同样能区分「合法图形」与「错误形态文本回落」。
     */
    internal fun shapeGraphicNum(tag: String): Pair<Long, Boolean>? {
        if (!isShapeTag(tag)) return null
        val type = tagAttr(tag, "type")?.trim()?.lowercase() ?: ""
        val params = (tagAttr(tag, "param") ?: "")
            .split(',')
            .map { parseMixedNum(it.trim()) }
        return when (type) {
            "space" -> if (params.size == 1) params[0] else null
            "rect" -> when {
                params.size == 1 -> params[0]?.takeIf { it.first > 0 }
                params.size == 4 && params.all { it != null } &&
                    params[0]!!.first >= 0 && params[2]!!.first > 0 && params[3]!!.first > 0 ->
                    params[2]
                else -> null
            }
            else -> null
        }
    }

    /**
     * `<shape>` 是否落不到合法图形形态：原版落 `ConsoleErrorShapePart`
     * （ConsoleShapePart.cs:71-74），它把重建文案当文本画、按文本度量宽
     * （ConsoleErrorShapePart.cs:28-37、StringMeasure.cs:42-72）。
     *
     * 与 [graphicWidthPxOrNull] 的 null 语义不同：那个在 headless（`graphicFontPx <= 0`）
     * 恒返回 null（不折算图形），本函数只看形态，headless 下照样判得出错误形态 ⇒
     * 折行与行内游标据此无条件计文本格，不再出现「折行说 0 格、游标说 N 格」的分歧。
     */
    internal fun isInvalidShapeTag(tag: String): Boolean =
        isShapeTag(tag) && shapeGraphicNum(tag) == null

    /** 错误形态 `<shape>` 的文本宽：重建文案按文本画，格数走 [displayCells]（与行内游标同尺）。 */
    private fun invalidShapeCells(tag: String): Int = displayCells(tag)

    /** 标签属性取值（引号内的原样文本）；找不到返回 null。属性名大小写不敏感。 */
    internal fun tagAttr(tag: String, name: String): String? {
        var i = 0
        val key = name.lowercase()
        while (i < tag.length) {
            // 属性名必须顶着一个非字母起点（`<` 或空白）出现，避免 `srcwidth` 误命中 `width`。
            if ((tag[i] == ' ' || tag[i] == '<') &&
                i + 1 + key.length < tag.length &&
                tag.regionMatches(i + 1, key, 0, key.length, ignoreCase = true)
            ) {
                var j = i + 1 + key.length
                while (j < tag.length && tag[j] == ' ') j++
                if (j < tag.length && tag[j] == '=') {
                    j++
                    while (j < tag.length && tag[j] == ' ') j++
                    if (j < tag.length && (tag[j] == '\'' || tag[j] == '"')) {
                        val q = tag[j]
                        val vStart = j + 1
                        var vEnd = vStart
                        while (vEnd < tag.length && tag[vEnd] != q) vEnd++
                        return tag.substring(vStart, vEnd)
                    }
                }
            }
            i++
        }
        return null
    }

    /** `12` / `12px` / `12 Px`（带符号，px 后缀大小写不敏感）；解析不出返回 null。 */
    internal fun parseMixedNum(raw: String): Pair<Long, Boolean>? {
        if (raw.isBlank()) return null
        var s = raw.trim()
        var px = false
        if (s.endsWith("px", ignoreCase = true)) {
            px = true
            s = s.substring(0, s.length - 2).trim()
        }
        val n = s.toLongOrNull() ?: return null
        return n to px
    }

    /**
     * HTML 图形段（`<img>` / `<shape>`）的**脚本像素宽**：px 形态原值，百分比形态按
     * `scriptFontPx * num / 100`（Long 整除，与 C# 的 int 运算同形）。
     *
     * 这是折行侧与渲染侧共用的**同一把尺**：渲染（`HtmlRunFlatten.emitImage`/`emitShape`）
     * 的图形格宽与折行（[visibleHtmlCells] 的图形扫描）都必须由本函数的返回值经
     * [graphicCells] 量化得到 —— 两边各算各的，img 行的折行位置就会与屏上实占错位
     * （2026-11-05 遗留①的病根：折行把图形计 0 格）。
     *
     * 百分比基尺 = **脚本字号**（`Utils.MixedNum.ToPixelf` 的 `num * FontSize / 100`，
     * Utils.cs:60-71；渲染侧旧实现用设备字号 `metrics.fontPixelSize`，换到脚本尺后
     * 两侧逐值同源，物理尺寸经 `scriptPixels` 折算不变量 ≤1 设备像素）。
     */
    fun graphicScriptPx(num: Long, isPx: Boolean, scriptFontPx: Long): Long =
        if (isPx) num else scriptFontPx * num / 100

    /**
     * 像素宽 → **半角格数**（带号）的**唯一取整实现**：`ceil(|px| / halfCellPx)`，正负镜像。
     *
     * 三条链都收在这一个函数里：本对象的 [graphicCells]（脚本像素 + 脚本字号）、
     * 渲染侧 `InlineGraphic.cellsFor`（`render/displaylist/DrawCmd.kt`，设备像素 + 实测半角步进，
     * 直接委托本函数）、sink 的行内游标（经 [graphicCells]）。取整方向 / 下限 / 镜像不许任何
     * 一方再各写一份：折行预算与屏上实占各用一把尺，图形行就会差格（2026-11-05 遗留①的病根）。
     *
     * 判据逐条（与 `cellsFor` 的既有语义逐位相同）：
     * - [px] == 0 或 [halfCellPx] <= 0（无基尺 / headless 不折算）→ 0 格；
     * - 正宽向上取整，且不足 1 格抬 1（否则相邻段会叠在同一列上）；
     * - 负宽取正宽的**严格镜像** `-ceil(|px| / halfCellPx)`，不足 1 格回退 `-1`：原版在像素域
     *   带符号累加（`ConsoleButtonString.cs:281`/`:337` 的 `num += Width`），`+Wpx` 与紧跟的
     *   `-Wpx` 必须净零，否则 XRAY 叠图每层右移一格（见 `cellsFor` 的 KDoc）；
     * - `Int` 域的 `MIN_VALUE` 经 [Long] 取绝对值不回绕（`-Int.MIN_VALUE` 在 Int 上回绕成
     *   自身）。`Long.MIN_VALUE` 不在入参域（脚本像素是 `Int` 量级），落到 `-1`，不求镜像。
     *
     * 取整只看 `ceil`，**不是** `CellMetrics.pixelsToCells` 的 `(像素 - 1) / 半角宽 + 0.95`
     * 截断式：后者是原版 GRAPHICS 模式对**文本整串**的量化（spec: `StringMeasure.cs:65`），
     * 图形段的整格宽是本端口的重设计口径（像素域几何 → 整格网格），两者不互换。
     */
    fun cellsAtHalfCell(px: Long, halfCellPx: Float): Int {
        if (px == 0L || halfCellPx <= 0f) return 0
        if (px < 0L) {
            val cells = kotlin.math.ceil(-px / halfCellPx).toInt()
            return if (cells < 1) -1 else -cells
        }
        val cells = kotlin.math.ceil(px / halfCellPx).toInt()
        return if (cells < 1) 1 else cells
    }

    /**
     * 图形段的脚本像素 → **占格数**（带号）：先按脚本字号折出半角格宽，再走 [cellsAtHalfCell]。
     *
     * 半角格宽 = `scriptFontPx / 2f * 1.04f` —— 表达式与求值顺序**逐位照抄**原版
     * `fontDisplaySize = Config.DefaultFont.Size / 2f * 1.04f`（spec: `StringMeasure.cs:37`；
     * 1.04 是原版实测调参，见 `CellMetrics.CELL_PIXEL_FACTOR`；0.52 = 1.04 / 2）。
     * 分子分母的设备字号相消，**只需脚本字号**即可与渲染逐值同源（px 形态的
     * `scriptPixels` 截断带 ≤1 设备像素，两侧同式后不再引入分歧）。
     *
     * 取整 / 下限 / 正负镜像全在 [cellsAtHalfCell]；本函数只负责「脚本字号 → 半角格宽」这一层。
     */
    fun graphicCells(scriptPx: Long, scriptFontPx: Long): Int =
        cellsAtHalfCell(scriptPx, scriptFontPx / 2f * 1.04f)

    /**
     * 半角（1 格）的**四档**，其余一律全角 —— **判定式的唯一出处见本函数**。
     * 口径 = 「CP932(Shift-JIS) 里占几个字节」，不是 East Asian Width，也不是
     * 只看 ASCII 可打印段：
     *
     * 1. `U+0020..U+007E`：ASCII 可打印（Shift-JIS 单字节 0x20..0x7E，其中 0x5C 屏上是 `¥`、
     *    0x7E 是 `‾`，但都仍是半角）；
     * 2. `U+FF61..U+FF9F`：半角假名与半角符号（Shift-JIS 单字节 0xA1..0xDF）；
     * 3. `U+0000..U+001F`、`U+007F`、`U+0080`：C0 控制符、DEL 与 C1 首码点 —— `.NET` 的
     *    932 码页把它们**原样单字节透传**（0x00..0x1F、0x7F、0x80），`GetByteCount` 各得 1；
     * 4. `U+00A5`（`¥` YEN SIGN）与 `U+203E`（`‾` OVERLINE）：932 码页的 `0x5C`/`0x7E`
     *    是**真实的单字节映射**（不是替换回退）—— 前者 best-fit 编回 `5c`，后者在
     *    JIS X 0201 罗马字表里本就占 `7e`（本机 Temurin 17 `windows-31J` 逐字节实测
     *    `U+203E` → `7e`；`U+301C`/`U+2016`/`U+2212` 才是替换回退 `3f`，仍走表外字全角档）。
     *    `U+203E` 于 W8 刀五补进本档（`EmueraConsole.cs:3255` 字节数口径应为 1 格；
     *    语料 3,971 个 ERB 与两份基准文本逐字节扫描 `E2 80 BE` 零命中 ⇒ 基准读数不受影响）。
     *
     * 第 3、4 档是本表在 2026-10-21 由 `EmueraConsole.cs:3255` 的字节数口径直接实测补上的
     * （本机 `dotnet 9.0.200` + `CodePagesEncodingProvider` 的
     * `Encoding.GetEncoding(932).GetByteCount`，逐点读数写在
     * `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`
     * 的 D 类表与 `LineFolderAuditTest` 的同名用例；本机 Temurin 17 的 `windows-31J` 对
     * 这 35 个码点逐点交叉验证同样得 1 字节，`U+0080` 走替换回退 `0x3F` 但仍是 1 字节）。
     * 补之前它们落在「其余一律 2 格」里，与 `:3255` 的读数差 1 格 ——
     * 即 M6-RENDER-AUDIT 登记的那条缺口；`U+00A5` 与 ASCII 的 `0x5C` 现在**同值**，
     * 旧 `Kernel.kt:109-110` 那条「同一个字形两个读数」的 TODO 随本档一起消掉。
     *
     * 口径的第二条证据是屏上宽度：原版问 GDI 像素（`StringMeasure.cs:42-72`），而等宽日文字体下
     * 「双字节 ⇒ 两倍半角宽」，两者同结论。基准直接钉住了这一点：
     * `fixtures/golden/baseline/pc-golden-root.txt:61`（对 `ERB/TITLE.ERB:62` 的
     * `CUSTOMDRAWLINE ━` 的采集）是 **99 个 U+2501**，而 `:253` 的 199 个 `=` 把半角步进
     * 钉成 9px、可绘制宽度 1797px（`Config.cs:461-466`）—— 99 字填满 1797px 只能是
     * 单字 18px = **2 个半角格**。
     *
     * 换表换掉的是 East Asian Width 的 Ambiguous 一类：`─ ━ │ ┃ ┼ ■ □ ▲ ▼ ◆ ○ ● ★ ♪ ※ ① ±
     * × ― ‐ “ ” … ℃ № → √ ≠ ≡ ≦ ≧ ¢ § ¬ ° ´` 在 CP932 里都是双字节（逐点字节序列实测登记在
     * `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt` 的 A 类表），
     * EAW 只算 Ambiguous → 旧表给 1 格，与上面两条口径同时冲突。
     *
     * 控制符按 1 格**不影响整串路径**：TAB 在测量前就被展开成 8 个空格
     * （`StringMeasure.cs:57-61`，本仓 `render` 的 `Grid.expandTabs`），所以
     * `CellWidth.of("\t")` = 1（字节数口径）而显示宽度走 [displayCells] 得 8；
     * 折行/行宽/锚点等显示路径**一律使用** [displayCells]/[displayCodePoint]。
     * 基准 `:18 :20 :22` 的三条含 TAB 行格数由此与屏上实占一致。
     *
     * TODO(verify)：CP932 **编不出来**的表外字（`— ≤ ≥ ♥ ❤ · ½ ∶ ≈` 等，逐点登记在同测试的
     * B 类表）这里按全角 2 格处理，与旧表同值、沿用旧表的 TODO：
     * 原版像素宽度取决于字体回退，基准文本里没有可对拍的读数；而 `:3255` 的字节数口径对
     * 这类字符实得 1（.NET 的替换回退按单个 `?` 计）。两种口径对表外字必然分叉，
     * 真机以 `:render` 的 `glyph/GlyphCache.measureCells` 像素度量为准（契约 M4-C 0.5）。
     * 代理项半（`U+D800..U+DFFF`）不是字符，单独传入按全角 2 计；整串一律走 [of] 按码位累加。
     *
     * TODO(verify)：**组合符（Unicode `Mn`，`U+0300..U+036F`）按「表外字 ⇒ 2 格」是兜底的顺带结果，
     * 不是口径结论**。本机 Temurin 17 `windows-31J` 实测 `U+0300`/`U+0301` 各编成**单个替换字节
     * `3f`**（`U+1F600` 同）⇒ `EmueraConsole.cs:3255` 的字节数口径给 1 格；原版屏上宽度又是 GDI
     * **整串**测量（`StringMeasure.cs:51-72`），组合符并进基字簇、不另占一位 —— 两条口径都不是 2。
     * **可达性实测**：全语料 3,818 个 ERB 逐字节扫描命中 **338 个组合附加符**，分布在 6 个口上文件
     * （最多一处 = `ERB/口上・メッセージ関連/個人口上/120 Aunn [あうん]/阿吽海外版/
     * M_KOJO_K120_日常系コマンド.ERB` 319 个），两份基准文本 0 命中 ⇒ 黄金基准读数不受影响，
     * 但这几条口上行在折行上会逐符偏 1 格。与 `core/fn/GraphicsGfxAuditTest.kt:294` 登记的那条
     * `e + U+0301` GFX 宽度分歧（PC 8 像素 vs 移植侧 24）是同一笔结构性债；正解是**字簇
     * （grapheme cluster）步进**，归 M4 渲染层与折行侧（本路禁改 `LineBufferConsoleSink`），
     * 且改判会同时动 `FontSidecarMetricsTest` 钉住的用例 ⇒ 登记不修正。
     */
    fun isFullWidth(cp: Int): Boolean = !isHalfWidth(cp)

    /** [isFullWidth] 的补集：CP932 里占**一个字节**的四档（逐档出处见 [isFullWidth]）。 */
    private fun isHalfWidth(cp: Int): Boolean = when {
        cp in 0x0020..0x007E -> true
        cp in 0xFF61..0xFF9F -> true
        cp in 0x0000..0x001F -> true
        cp == 0x007F || cp == 0x0080 -> true
        cp == 0x00A5 || cp == 0x203E -> true
        else -> false
    }
}
