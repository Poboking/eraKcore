package com.eratw.port.core.host

/**
 * 一条按钮的创建来源。右侧选项栏只消费引擎快照，不再从可见文本里猜。
 *
 * - [EXPLICIT]：`PRINTBUTTON` / `PRINTBUTTONC` / `PRINTBUTTONLC` 显式创建。
 * - [HTML]：`HTML_PRINT` / `HTML_PRINT_ISLAND` 里的 `<button value=…>`。
 * - [AUTO_TEXT]：普通文本里的 `[N]` 被原版 `ButtonStringCreator` 自动变成可点段
 *   （`PrintStringBuffer.fromCssToButton`，`PrintStringBuffer.cs:280-292`）。
 *
 * 三者都是原版真的可点对象；来源只用于诊断与显示徽标，**不参与可点性判定**
 * （可点性只由世代 + 等待类型决定，见 [LineBufferConsoleSink.currentOptions]）。
 */
enum class OptionOrigin { EXPLICIT, HTML, AUTO_TEXT }

/**
 * 选项在右栏里的**显式视觉类型**，由引擎在冻结快照时一次判定，UI 不再二次猜标签。
 *
 * - [TEXT]：普通文字按钮（即使脚本给它设过颜色，也只按默认按钮外观显示）；
 * - [COLOR_SWATCH]：整颗按钮的标签就是一个纯色块字形，颜色是按钮的数据本身。
 *
 * 这个字段是识别口径的单一真源：`ShellUi` 的色格、`LeanOptionFilter` 的保留判据、
 * `MainActivity` 的选项指纹都只读它，避免三处各自扫标签后分叉。
 */
enum class OptionVisual { TEXT, COLOR_SWATCH }

/**
 * 引擎在**进入一次输入等待**时冻结下来的一个选项。
 *
 * 与 `OptionButtonScanner` 的旧产物不同：这里没有正则、没有“扫日志尾部 N 行”，
 * 只有 [LineBufferConsoleSink] 按原版世代（`Generation == lastButtonGeneration`）
 * 筛出来的真实按钮对象。`value` 与原版 `ConsoleButtonString.Input/Inputs` 同型
 * （`Long` 或 `String`），点击提交时直接 `toString()`，与 `ButtonLayer.selectedString`
 * 同一口径。
 *
 * @property value 提交值：数值按钮是 `Long`，字符串按钮是 `String`。**永远以按钮对象自己的
 *   value 为准**，不从标签里的 `[N]` 前缀反推 —— 原版允许 `[27] 示例` 的按钮提交 `227`
 *   （地图 `MAIN_MAP*100+LOCAL` 形态，`DRAW_MAP.ERB:467`），前缀只是屏上印的字。
 * @property label 按钮上印的字（已去 HTML 标签、已折空白；可能为空）。标签前缀是否剥掉
 *   取决于它与 [value] 是否一致，见 [LineBufferConsoleSink] 的 `describeOption`。
 * @property origin 按钮来源（见 [OptionOrigin]；仅诊断/徽标，不参与可点性）
 * @property lineSerial 按钮所在显示行的单调序号（UI 按它做行优先排序）
 * @property startCol 行内起始半角格（行优先排序的第二关键字）
 * @property endCol 行内结束半角格（半开区间）
 * @property generation 按钮对象的世代（原版 `ConsoleButtonString.Generation`，诊断用）
 * @property ordinal 数值按钮的序号徽标；**只有标签前缀与 value 一致、且 value 在 0..999 时**
 *   才非 null。前缀与 value 不一致（如标签 `[27]`、value `227`）时为 null：既不用前缀
 *   当提交值，也不把 `227` 冒充成屏上的 `27`，徽标交给标签原文。
 * @property groupId **源按钮身份**（原版是一个 `ConsoleButtonString` 实例；本仓用单调编号表达，
 *   见 `LineBufferConsoleSink.nextButtonGroupId`）。同一颗按钮折行/跨 `<br>` 重放时各处都是
 *   同一个编号 ⇒ 右侧栏并成一枚；屏上两颗不同的按钮即使 [value] 相同也是两个编号 ⇒ 两枚都留。
 *   右侧栏的去重键是它、**不是 [value]**（[ConsoleOptionFilter.prefer] 的口径说明）。
 *   `-1` = 身份未知（手工构造的本类型），永不与别的候选合并。
 * @property printsOwnNumber **这颗按钮在屏上自己印了编号**：标签开头的 `[N]` 与 [value] 一致
 *   （`[100] - 睁开眼睛`、`[400] - OPTION` 一族），出快照时那个编号被剥下来交给 [ordinal] 显示，
 *   所以 [label] 里已经看不到它。判的是**数值按钮**（[value] 是 `Long`）：字符串值按钮不印自己的
 *   编号（`qol_MAP.ERB:178` 的 `PRINTBUTTON "地图切换", TOSTR(BUTTON切替中)` 标签里就没有数字）。
 *   编号印在名字**末尾**（`移动[400]`）时这里是 false —— 编号还留在 [label] 原文里，
 *   不必另说一句。屏上印的数与提交值对不上时同样是 false：地图格子印的是本地图内的号，
 *   提交值却是 `MAIN_MAP*100+LOCAL`（`MOVEMENTS/DRAW_MAP.ERB:467`），那不是按钮自己的编号。
 *   消费点是显示层的「更精简的游戏选项」（`LeanOptionFilter`），可点性判定不看它。
 * @property colorRgb 按钮打印时的显式前景色（`0xRRGGBB`）；普通文字按钮和色块按钮都保留。
 *   null = 原按钮没有 `SETCOLOR` / HTML 显式色，右栏保持默认外观。是否色块只看 [visual]。
 *
 *   为什么必须由引擎快照带出：颜色是 `StringStyle` 在按钮对象创建时的快照，等脚本
 *   `RESETCOLOR` 之后 UI 才来读，当前色已经回落默认值。布娃娃系统的 128 色调色板正是
 *   `SETCOLOR COLOR_PALETTE:i` → `PRINTBUTTON "■", 80000+i` → `RESETCOLOR`
 *   （`MOB_SYSTEM.ERB:274-285`），右栏若只拿 label 会得到 128 个同形 `■`。
 *   普通按钮（选中的页签、当前部件名等）保留颜色只用于底色；不会混进色块通道。
 * @property visual 引擎在快照时判定的显式视觉类型（[OptionVisual]）。UI、精简过滤和指纹
 *   都只读这一项，不再从标签/颜色二次推断。
 */
data class ConsoleOption(
    val value: Any,
    val label: String,
    val origin: OptionOrigin,
    val lineSerial: Long = 0L,
    val startCol: Int = 0,
    val endCol: Int = 0,
    val generation: Long = 0L,
    val ordinal: Int? = null,
    val groupId: Int = -1,
    val printsOwnNumber: Boolean = false,
    val colorRgb: Int? = null,
    val visual: OptionVisual = OptionVisual.TEXT,
) {
    /** 点这枚候选要提交给输入队列的文本：数值 = 十进制串，字符串 = 原串。 */
    val submitValue: String get() = value.toString()

    /** 这枚候选是不是色块：引擎在快照时已按 [OptionVisual] 判定，UI 不再扫标签。 */
    val isColorSwatch: Boolean get() = visual == OptionVisual.COLOR_SWATCH
}

/**
 * 原版用来画色块的字符（布娃娃调色板用 `■`；其余是同类 UI 的常见实心块）。
 *
 * 这是**共享判据**：它只回答「这个标签是不是纯色块字形」；单个色块是否真的属于
 * 调色板，还要由 [paletteSwatchMask] 再看同一行有没有连续 8 枚以上。
 * 只认纯字形（允许中间夹空白/零宽排版字符），`■ 说明` 这类正常标题不算。
 */
fun isColorBlockLabel(label: String): Boolean {
    var sawGlyph = false
    for (ch in label) {
        if (ch.isWhitespace() || ch in COLOR_BLOCK_IGNORABLE) continue
        if (ch !in COLOR_BLOCK_GLYPHS) return false
        sawGlyph = true
    }
    return sawGlyph
}

private val COLOR_BLOCK_GLYPHS = setOf(
    '■', '●', '◆', '▲', '▼', '★', '♥', '♦', '█', '▇', '◼', '⬛',
)

/** 字体/排版层可能插在色块旁的零宽字符，不改变「这是一个纯色块字形」的事实。 */
private val COLOR_BLOCK_IGNORABLE = setOf(
    '\uFE0E', '\uFE0F', '\u200B', '\u200C', '\u200D', '\u2060',
)

/** 真正的调色板至少要有这么多枚连续色块；单个/少量彩色字形只是普通按钮。 */
const val PALETTE_MIN_SWATCH_RUN = 8

/**
 * 标出真正属于调色板的色块：同一 [ConsoleOption.lineSerial] 上**连续至少 [minRun] 枚**
 * [ConsoleOption.isColorSwatch] 才算；短游程、跨行拼凑和单个色块都不算。
 *
 * 返回与 [options] 等长的布尔数组。UI 的密集色格入口与精简选项过滤共用这一条判据，
 * 避免两边各自猜「多少枚算调色板」而在边界上分叉。
 */
fun paletteSwatchMask(
    options: List<ConsoleOption>,
    minRun: Int = PALETTE_MIN_SWATCH_RUN,
): BooleanArray {
    val mask = BooleanArray(options.size)
    if (options.isEmpty() || minRun <= 0) return mask
    var lineStart = 0
    while (lineStart < options.size) {
        val serial = options[lineStart].lineSerial
        var lineEnd = lineStart
        while (lineEnd < options.size && options[lineEnd].lineSerial == serial) lineEnd++
        markPaletteRuns(options, lineStart, lineEnd, minRun, mask)
        lineStart = lineEnd
    }
    return mask
}

private fun markPaletteRuns(
    options: List<ConsoleOption>,
    start: Int,
    end: Int,
    minRun: Int,
    mask: BooleanArray,
) {
    var i = start
    while (i < end) {
        if (!options[i].isColorSwatch) {
            i++
            continue
        }
        var runEnd = i + 1
        while (runEnd < end && options[runEnd].isColorSwatch) runEnd++
        if (runEnd - i >= minRun) {
            for (k in i until runEnd) mask[k] = true
        }
        i = runEnd
    }
}

/**
 * 选项快照的查询口径（引擎侧唯一实现，见 [LineBufferConsoleSink.currentOptions]）。
 *
 * 入参是**当前等待请求**：只有取值等待（`INPUT`/`INPUTS`/`INPUTANY`/`BINPUT` 系）
 * 才有“选项”；`WAIT`/`ENTER`/`VOID` 这类只等回车的页面一律返回空表。数值等待只留
 * `Long`，字符串等待两种值都收（原版 `SelectedString` 的 StrValue 支取 `Inputs`，
 * `ConsoleButtonString.cs:97-110`）。
 *
 * 这是一条纯函数，便于单测直接钉口径；[LineBufferConsoleSink] 只负责在等待入口
 * 把当前页按钮喂进来。
 */
internal object ConsoleOptionFilter {

    fun accepts(request: InputRequest, option: ConsoleOption): Boolean =
        when (request.form) {
            InputRequest.RequestForm.VALUE ->
                request.type == InputRequest.InputType.STR || option.value is Long
            InputRequest.RequestForm.ANY_VALUE -> true
            InputRequest.RequestForm.ENTER_KEY,
            InputRequest.RequestForm.ANY_KEY,
            InputRequest.RequestForm.VOID,
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY,
            -> false
        }

    /**
     * 同**源**候选（同一个 [ConsoleOption.groupId] 折行/跨 `<br>` 重放出来的几条）留哪一条。
     *
     * 同源恒同值、恒同来源，所以 [OptionOrigin] 的优先级实际只在「身份未知的候选被并到一起」
     * 这种非生产形态下起作用；真正会发生的差别是**标签**：一条按钮的正文可能只印在首行
     * （图形按钮、`<button>` 里带 `<br>` 的那颗），后出的分片标签为空 ⇒ 让位于有标签的那条。
     *
     * 不同源的候选**不进这里**：屏上有几颗按钮，右侧栏就出几枚，即使它们的 [ConsoleOption.value]
     * 相同（地图格子「2」与同屏菜单「2」是两颗按钮，原版点哪个就提交哪个的值）。
     */
    fun prefer(existing: ConsoleOption, candidate: ConsoleOption): Boolean {
        val existingRank = rank(existing.origin)
        val candidateRank = rank(candidate.origin)
        if (candidateRank != existingRank) return candidateRank > existingRank
        return existing.label.isEmpty() && candidate.label.isNotEmpty()
    }

    private fun rank(origin: OptionOrigin): Int = when (origin) {
        OptionOrigin.EXPLICIT, OptionOrigin.HTML -> 2
        OptionOrigin.AUTO_TEXT -> 1
    }
}
