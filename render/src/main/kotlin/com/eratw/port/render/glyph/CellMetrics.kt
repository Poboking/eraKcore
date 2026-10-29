package com.eratw.port.render.glyph

import com.eratw.port.render.Grid

/**
 * 像素 ↔ 半角格 换算 —— **真机折行的唯一宽度来源**。
 *
 * ## 为什么需要这一层
 *
 * [com.eratw.port.render.Grid] 的 KDoc（`render/src/main/kotlin/com/eratw/port/render/Kernel.kt:63-66`）
 * 已声明 M4 阶段折行先用「半角 1 / 全角 2」的等宽近似；真机上比例字体与字距必须回到
 * 像素测量，那时**唯一**能进入折行的宽度就是本类换算出来的格数：
 * 渲染侧一律先取 [GlyphEntry.advanceCells]，禁止把像素宽直接喂给折行或脏矩形。
 *
 * ## 换算公式取自原版 GRAPHICS 模式
 *
 * 原版有两种测量模式（`Config.TextDrawingMode`，**默认 TEXTRENDERER**，spec: `ConfigData.cs:55`；
 * WINAPI 会被强制改成 TEXTRENDERER，spec: `Config.cs:456-459`）：
 *  - TEXTRENDERER：`TextRenderer.MeasureText` 直接返回原始像素宽，**不做整格量化**
 *    （spec: `StringMeasure.cs:44-46,67-70`）。PC 默认走这条，所以原版的折行是纯像素折行，
 *    「半角 1 / 全角 2」只出现在 PRINTC 的补齐宽度上（Shift-JIS 字节数，spec: `EmueraConsole.cs:3255`）。
 *  - GRAPHICS：`MeasureCharacterRanges` 拿到像素宽后**重新量化到整格**（spec: `StringMeasure.cs:57-66`），
 *    且只有这条会先把 TAB 展成 8 个空格（spec: `StringMeasure.cs:59-61`）。
 *
 * 量化式：
 *  - 半角格像素宽 `fontDisplaySize = DefaultFont.Size / 2f * 1.04f`（spec: `StringMeasure.cs:37`）
 *  - 返回值 `(int)((float)(int)((像素宽 - 1f) / fontDisplaySize + 0.95f) * fontDisplaySize)`
 *    （spec: `StringMeasure.cs:65`）
 * 即内层 `(int)` 取整得到格数（本类的 [pixelsToCells]），外层 `(int)` 取整再落回像素
 * （本类的 [cellsToPixels]）。内层的 **减 1 像素偏移 + 0.95 偏置**是原版的实测调参，
 * 不是四舍五入：它让格数边界整体往左挪约 0.05 格。本类原样保留该偏置、两次截断与
 * 浮点中间量（一律 `Float`，与 C# 的 `float` 同精度同语义），空串在截断前就返回 0
 * （spec: `StringMeasure.cs:53-56`），故 `<= 0` 的入参一律给 0。
 *
 * ## 已知偏差（ADR-001 重设计区，不是 bug）
 *
 * 整数网格折行是**本端口的重设计选择**：PC 默认 TEXTRENDERER 下根本没有"格"这个概念，
 * 所以这里把 GRAPHICS 模式的量化式借来做像素 → 半角格的唯一换算入口。
 * TODO(verify)：真机（平台字体度量）接进来后，折行位置与 PC 的纯像素折行可能差最多 1 格，
 * 与 `Kernel.kt:66` 记的等宽近似偏差同性质，M4 截图验收时逐屏复核；
 * 若验收要求贴 PC 默认，则 [pixelsToCells] 改成恒等映射（像素直接当宽度单位）并同步改 [quantizePixels]。
 *
 * ## 配置口径
 *
 * 字号默认 18、行高默认 19（spec: `ConfigData.cs:66-67`）；字号小于 8 抬到 8、
 * 行高小于字号抬到字号（spec: `Config.cs:431-434`、`Config.cs:436-439`）。
 * 黄金基准的 PC 跑用的是 `黑体`（spec: `fixtures/golden/baseline/pc-golden-root.txt:279-280`）。
 *
 * ## 缺字形时的确定性宽度（GFX-12）
 *
 * 随包子集只覆盖 5,268/8,076 个 required 码点（缺 2,808，34.77%，
 * `tools/fontsubset/probe-report.txt` 实测），缺的字在真机上回落到系统字体 ⇒ 宽度随设备变。
 * [fallbackClassOf] 把「没有字形」时的 advance 钉成**只看码位**的档位，与设备装了什么字体无关：
 * 同一字符串在 A 设备（有系统回退字）与 B 设备（无）得到同一 advance 序列。
 * 分档（逐条证据见 [fallbackClassOf]）：
 *  - 零宽：组合附加符号与变体选择符（Unicode Mn/Me）；
 *  - 半角 1 格：C0/DEL/`U+0080`、ASCII 可打印、`U+00A5`、半角假名；
 *  - 全角 2 格：其余（东亚宽字符区、假名、CJK、私用区、增补平面 emoji）。
 * 与 [cellsOf] 的差只有两处，且都是有意的：组合符 2 → 0（组合符并进基字簇，不另占位），
 * 增补平面 4 → 2（按**码位**给宽度；[cellsOf] 的 4 格是布局侧按 UTF-16 码元累加的已登记口径）。
 *
 * @property fontPixelSize 生效字号（像素），入参小于 [MIN_FONT_PIXEL_SIZE] 时已被抬起
 * @property lineHeight 生效行高（像素），保证不小于 [fontPixelSize]
 */
class CellMetrics(
    requestedFontPixelSize: Int = DEFAULT_FONT_PIXEL_SIZE,
    requestedLineHeight: Int = DEFAULT_LINE_HEIGHT,
) {

    /** 生效字号：小于 8 抬到 8（spec: `Config.cs:431-434`，原版这里还会弹配置错误对话框）。 */
    val fontPixelSize: Int = if (requestedFontPixelSize < MIN_FONT_PIXEL_SIZE) MIN_FONT_PIXEL_SIZE else requestedFontPixelSize

    /** 生效行高：小于字号抬到字号（spec: `Config.cs:436-439`）。 */
    val lineHeight: Int = if (requestedLineHeight < fontPixelSize) fontPixelSize else requestedLineHeight

    /** 一个半角格的像素宽（spec: `StringMeasure.cs:37`）。 */
    val cellPixelWidth: Float = fontPixelSize / 2f * CELL_PIXEL_FACTOR

    /**
     * 像素 → 半角格（spec: `StringMeasure.cs:65` 的内层 `(int)` 取整）。
     * 非正像素（空串测量结果）返回 0（spec: `StringMeasure.cs:53-56`）。
     */
    fun pixelsToCells(pixels: Int): Int {
        if (pixels <= 0) {
            return 0
        }
        return ((pixels - 1f) / cellPixelWidth + CELL_ROUNDING_BIAS).toInt()
    }

    /**
     * 半角格 → 像素（spec: `StringMeasure.cs:65` 的外层 `(int)` 取整）。
     * 与 [pixelsToCells] 不对称是原版的固有行为：`cellsToPixels(pixelsToCells(p))`
     * 可能**大于** p（例如字号 18 时 p = 36 → 4 格 → 37 像素），这正是原版把宽度
     * 量化到整格的效果，不许"修正"。
     */
    fun cellsToPixels(cells: Int): Int {
        if (cells <= 0) {
            return 0
        }
        return (cells.toFloat() * cellPixelWidth).toInt()
    }

    /** 整条量化：原 `GetDisplayLength` 的返回值口径（spec: `StringMeasure.cs:65`）。 */
    fun quantizePixels(pixels: Int): Int = cellsToPixels(pixelsToCells(pixels))

    /**
     * **脚本给的像素值 → 本端口的设备像素**（立绘空档修复的换算入口，2026-10-27）。
     *
     * ## 为什么 px 需要一个尺、而百分比不需要
     *
     * 原版的 `MixedNum.ToPixel`（spec: `Utils.cs:60-71`，`ConsoleImagePart.cs:66-70` 的用法）两支是
     * - 百分比：`Config.FontSize * num / 100` —— 基准是**字号**；
     * - `px`：原值 —— 基准是**屏幕像素**。
     * 端口把百分比那支的基准换成了真机字号（`HtmlRunFlatten.emitImage` 读 `metrics.fontPixelSize`），
     * 于是「与字号同比例缩放」的图形自动跟着版面长大；`px` 那支却没换，仍是出厂屏幕像素。
     *
     * 关键在于**这两支在语料里是同一个数**：语料的 `emuera.config:22-23` 把 `FONT SIZE` 与
     * `LINE HEIGHT` 都写成 18，所以立绘库算出的 `height='{180*100/18}'`（= 1000%，
     * `ERB/グラフィック表示ライブラリ/グラフィック表示.ERB:212-213`）与 QOL 模块写的
     * `height='180px'`（`ERB/魔改内容/QOL_IMAGE.ERB:826`）在 PC 上同样是 180 像素。
     * 端口里前者画成 `真机字号 * 1000 / 100`（110 列档 ≈ 410px）、后者只画 180px，
     * 而剧本的空行预留两边都按 `ceil(180 / 一行の高さ)` = 10 行算 —— 于是 180px 那张图
     * 只吃掉 10 行里的 4 行，立绘下面挂出 6~7 行的空白（2026-10-27 主人报的「间距很大」）。
     *
     * ## 口径
     *
     * `设备px = 脚本px * 真机字号 / 基准字号`，**先乘后除、向零截断**（与 C# 的 `int` 除法同形，
     * 也与本类 [pixelsToCells] 一样不升浮点）。取 `基准字号 == 真机字号` 时恒等 ——
     * headless、黄金装置与全部既有用例因此逐值不变（缺省形参就是这个恒等档）。
     * 与百分比支的一致性判据：`px(v)` 与 `pct(v * 100 / 基准字号)` 同值，
     * 上面那两条语料写法在本端口也画成同一个 410px。
     *
     * 竖向为什么用**字号**而不是行高做比例：版面侧的行高恒为 `字号 * 19/18`
     * （`view/PageFitter.LINE_HEIGHT_RATIO`），两支同尺才不会横纵各缩一档；而预留空行那一侧
     * 的 10 行 = `10 * 真机行高` ≥ 折算后的图高，图只会略短于预留块（空档回到 1 行上下，
     * 与 PC 同比例），不会压到下一行文字上。
     *
     * TODO(verify)：**本函数把「脚本 px」钉成与「百分比」同一把尺（基准 = 配置的 FontSize）**，
     * 在语料这份配置（`FONT SIZE` 与 `LINE HEIGHT` 同为 18）下两者严格等价；若有人把两键配成
     * 不等的值（如 24 / 30），预留块高与折算图高的比就不再是 1，可能差半行。卡点：PC 侧无法
     * 本机取证（无 WinForms），且语料 0 处这样配。真机若出现「图压字/差半行」先查这一条。
     *
     * @param pixels 脚本写的像素值（可为负 —— `param='-180px'` 那种把图层压回同一行的负 space，
     *   符号原样保留；**注意下游 `InlineGraphic.cellsFor` 对非正宽返回 0 格**，负 space 目前
     *   不产生回退推进，见 BOARD 2026-10-27 的登记）
     * @param scriptFontPixelSize 脚本像素所依据的基准字号（= 引擎生效 `Config.FontSize`）；
     *   0/负数或与 [fontPixelSize] 相等 = 不折算（恒等）
     */
    fun scriptPixels(pixels: Int, scriptFontPixelSize: Int): Int {
        if (scriptFontPixelSize <= 0 || scriptFontPixelSize == fontPixelSize) {
            return pixels
        }
        // Long 中间量：脚本能写出接近 Int 上限的 px（`SPRITEWIDTH` 乘放大率那条），
        // Int 乘法会回绕成**符号相反**的值 ⇒ 整张图静默消失（cellsFor 对非正宽给 0，
        // drawImage 对 ≤0 直接 return），屏上没有任何报错可查。
        val scaled = (pixels.toLong() * fontPixelSize / scriptFontPixelSize)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
        // 缩小档（基准字号 > 真机字号）里 1px 会被整数除截成 0 —— 那在绘制侧等于「这张图
        // 不存在」。原版只会画成亚像素而不会消失，故保号抬到 1，思路同
        // `GraphicGeometry.intPixel` 的「≥0.001 抬到 1」。
        if (scaled == 0 && pixels != 0) {
            return if (pixels > 0) 1 else -1
        }
        return scaled
    }

    /**
     * 单个**码位**的半角格宽（宽度模型的唯一入口，委托 [Grid.cellWidth]）。
     *
     * 半角 1 / 全角 2 的判据来自 Shift-JIS 字节数（spec: `EmueraConsole.cs:3255`；
     * 基准实测 `WIDTH_FULL=6 WIDTH_HALF=3`，`fixtures/golden/baseline/pc-golden-root.txt:135`
     * 与 `fixtures/golden/GOLDEN_01_BASIC.ERB:122`）。
     * BMP 码位按 UTF-16 **码元**判（0..0xFFFF）：原版按 `Text.Length`/`Substring` 逐 UTF-16
     * 索引处理，代理对会被拆成两半各算一格（spec: `ConsoleStyledString.cs:60-62`）。
     *
     * **增补平面（U+10000 以上）按两个码元各 2 格 = 4 格**：布局侧 [Grid.cells] /
     * `grid/LineFolder` 逐 UTF-16 码元累加（本仓已登记的宽度模型，理由见 `Kernel.kt` 的
     * `Grid` KDoc「增补平面（U+10000 以上）的口径边界」节），绘制与命中必须用同一个推进量，
     * 否则 emoji 之后的文本会被画在布局预留位置的左边 2 格（`view/ScreenComposer.drawRun`
     * 逐字形推进、`displaylist/DisplayList.prepare` 的 `colEnd` 与 `button/ButtonLayer`
     * 的命中列都按布局的 4 格走）。
     * 与核心层 [com.eratw.port.core.host.CellWidth.of] 的「按码位 2 格」之差是**已登记的驻留
     * 差异**（`grid/AstralWidthTest`），不在本函数里冒充成一致。
     * 非法码位（负数 / 大于 U+10FFFF）按 [FULL_WIDTH_CELLS] 兜底。
     */
    fun cellsOf(codePoint: Int): Int {
        if (codePoint in 0..0xFFFF) {
            return Grid.cellWidth(codePoint.toChar())
        }
        if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT || codePoint > Character.MAX_CODE_POINT) {
            return FULL_WIDTH_CELLS
        }
        // 一个增补码位 = 高位 + 低位代理项各一个 UTF-16 码元，各按全角 2 格累加（= 4）。
        return Grid.cellWidth(Character.highSurrogate(codePoint)) +
            Grid.cellWidth(Character.lowSurrogate(codePoint))
    }

    /**
     * 缺字形时的宽度档位 —— **只看码位**，不看字体、不看设备上有没有系统回退字。
     *
     * 逐档证据（GFX-12）：
     * 1. **零宽**：`U+0300..U+036F`（组合附加符号，语料实测 89 个）、`U+20D0..U+20FF`、
     *    `U+FE00..U+FE0F`（变体选择符）、`U+FE20..U+FE2F`。判据是原版**整串**测量：
     *    `graph.MeasureCharacterRanges(...)` 一次量整串（spec: `StringMeasure.cs:63-65`），
     *    组合符并进基字簇、不另占位；GDI 的 `TextRenderer.MeasureText` 同理（`:44-49`）。
     *    `CellWidth` 对这类码点按「表外字 ⇒ 2 格」兜底，是顺带的兜底结果而不是口径结论
     *    （该文件已登记 TODO(verify)），本表按真机语义给 0。
     * 2. **半角 1 格**：`U+0000..U+001F`、`U+007F`、`U+0080`、`U+0020..U+007E`、`U+00A5`、
     *    `U+FF61..U+FF9F` —— 与 [Grid.cellWidth] 的四档逐值相同（CP932 单字节，
     *    spec: `EmueraConsole.cs:3255` 的 `GetByteCount`；逐点实测见 `CellWidth` 的 KDoc）。
     * 3. **全角 2 格**：其余一律，含东亚宽字符区 `U+1100` 以上的 W/F 段（谚文 Jamo、假名、
     *    CJK 基本区与扩展 A、全角 ASCII `U+FF01..U+FF5E`）、框线块元素、私用区、增补平面 emoji。
     *    判据是原版像素宽度：基准 `fixtures/golden/baseline/pc-golden-root.txt:61` 的 99 个
     *    `U+2501` 填满 1797px 可绘制宽度（`:253` 的 199 个 `=` 把半角步进钉成 9px）⇒ 单字 18px
     *    = 2 个半角格；`U+1100` 以上是 Unicode 的 East Asian Width 的 W/F 区（UAX #11）。
     *    增补平面按**一个码位 2 格**：原版一对代理在 GDI 眼里是一个字形
     *    （spec: `ConsoleStyledString.cs:60-62` 的 `Substring` 只做段切分，测量整串交出去），
     *    全角 emoji 的字形宽与 CJK 同档。
     * 4. **非法码位**（负数 / 大于 `U+10FFFF`）按全角兜底，与 [cellsOf] 一致。
     *
     * TODO(verify)：`U+00A0..U+024F`（拉丁扩展）与希腊/西里尔在本表按 2 格（沿用 CP932
     * 双字节口径与 [Grid.cellWidth]），但它们在比例字体里通常只有半角宽 —— 基准里没有这些字符
     * 的像素读数可对拍，等真机截图复核。
     */
    fun fallbackClassOf(codePoint: Int): FallbackWidthClass = when {
        codePoint < 0 || codePoint > Character.MAX_CODE_POINT -> FallbackWidthClass.FULL
        isZeroWidth(codePoint) -> FallbackWidthClass.ZERO
        codePoint <= 0x001F -> FallbackWidthClass.HALF
        codePoint == 0x007F || codePoint == 0x0080 -> FallbackWidthClass.HALF
        codePoint in 0x0020..0x007E -> FallbackWidthClass.HALF
        codePoint == 0x00A5 -> FallbackWidthClass.HALF
        codePoint in 0xFF61..0xFF9F -> FallbackWidthClass.HALF
        else -> FallbackWidthClass.FULL
    }

    /** 缺字形时的半角格数（= [fallbackClassOf] 的档位值）。 */
    fun fallbackCellsOf(codePoint: Int): Int = fallbackClassOf(codePoint).cells

    /** 缺字形时的 advance（像素）—— 确定性，且与 [cellsToPixels] 同一条取整口径。 */
    fun fallbackAdvancePixels(codePoint: Int): Int = cellsToPixels(fallbackCellsOf(codePoint))

    /** 零宽码位：组合附加符号（Mn/Me）与变体选择符，判据见 [fallbackClassOf]。 */
    private fun isZeroWidth(codePoint: Int): Boolean =
        codePoint in 0x0300..0x036F ||
            codePoint in 0x20D0..0x20FF ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xFE20..0xFE2F

    companion object {
        /** `FontSize` 默认 18（spec: `ConfigData.cs:66`）。 */
        const val DEFAULT_FONT_PIXEL_SIZE: Int = 18

        /** `LineHeight` 默认 19（spec: `ConfigData.cs:67`）。 */
        const val DEFAULT_LINE_HEIGHT: Int = 19

        /** 字号下限 8，低于它被抬到 8（spec: `Config.cs:431-434`）。 */
        const val MIN_FONT_PIXEL_SIZE: Int = 8

        /** `fontDisplaySize` 的 1.04 调参（spec: `StringMeasure.cs:37`）。 */
        const val CELL_PIXEL_FACTOR: Float = 1.04f

        /** 取整前的 0.95 偏置（spec: `StringMeasure.cs:65`）。 */
        const val CELL_ROUNDING_BIAS: Float = 0.95f

        /** 非法/表外码位的兜底格宽（全角）；增补平面码位按两个码元累加成 4，见 [cellsOf]。 */
        const val FULL_WIDTH_CELLS: Int = 2

        /** 基准 PC 跑的默认字体名（spec: `fixtures/golden/baseline/pc-golden-root.txt:279-280`）。 */
        const val DEFAULT_FONT_NAME: String = "黑体"

        /** 默认字号/行高下的换算器（`ConfigData.cs:66-67`）。 */
        val DEFAULT: CellMetrics = CellMetrics()
    }
}

/**
 * 缺字形时的宽度档位（GFX-12）—— 半角格数就是全部语义，判定式只有 [CellMetrics.fallbackClassOf] 一处。
 *
 * `tools/fontsubset/probe_fallback.py` 里有一份逐值同表的 Python 转写（用来出可复跑的缺字读数），
 * 两处改一处就会漂：改档位必须同批改脚本，并让 `FallbackWidthTest` 的逐档断言跟着走。
 */
enum class FallbackWidthClass(val cells: Int) {
    /** 组合附加符号 / 变体选择符：并进基字簇，不另占位。 */
    ZERO(0),

    /** CP932 单字节档：C0/DEL/`U+0080`、ASCII 可打印、`U+00A5`、半角假名。 */
    HALF(1),

    /** 其余一律全角：东亚宽字符区、假名、CJK、私用区、增补平面 emoji、非法码位。 */
    FULL(2),
}
