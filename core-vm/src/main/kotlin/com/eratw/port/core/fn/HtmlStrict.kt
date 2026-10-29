package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import java.util.Locale

/**
 * `HtmlManager.HtmlLength`（HtmlManager.cs:168-182）的**严格**口径解析器。
 *
 * 原版没有「宽容扫描」这条路：`HtmlLength` 直接调 `Html2DisplayLine`（:170），
 * 由 `html2DisplayLine`（:529-712）与 `tagAnalyze`（:951-1678）把整串解析成
 * `ConsoleDisplayLine[]`，任何语法问题都在那里抛 CodeEE；只到最后才取
 * `array[0].Buttons[].Width`（:176-181）。因此**畸形输入必须抛错，而不是尽量量宽**。
 *
 * 本类把「校验」「量宽」「产出显示行」合并成一遍 O(n) 扫描：
 * - 校验：逐条复刻 tagAnalyze 的开/闭标签状态机（样式位、字体栈、p/nobr、按钮、div）。
 * - 量宽：只累加**第一条显示行**的宽度（FN-01 / FN-03 后与原版同口径）：
 *   `img` / `shape` 按**图元宽高折算**计入（FN-01，见 [imgGroupNode] 与 [shapeGroupNode]），
 *   累计宽度越过 `Config.DrawableWidth` 时按原版 `PrintStringBuffer.ButtonsToDisplayLines`
 *   的折行判据收尾（FN-03，见 [commitGroup]），`<br>` / 裸换行同样结束第一条显示行。
 *   `<div>` 走递归子解析（HtmlManager.cs:606-648），div 内的 `<br>` 只结束子行，
 *   不结束父行的第一条显示行 —— 故 [closeDiv] 会恢复 [measuring]；div 子行的内容
 *   也**不**计入父行宽：`ConsoleDivPart.SetWidth` 是空实现（ConsoleDivPart.cs:217-219），
 *   `Width` 停在字段缺省 0（AConsoleDisplayNode.cs:27），父组累加时 div 贡献 0
 *   （ConsoleButtonString.cs:266-270），见 [commitGroup] 与 [closeDiv]。
 *   换行之后仍继续校验 —— 原版也是解析完整串才取第一行。
 * - 显示行：[document] 按原版 `list2` 的 null 标记语义切行（HtmlManager.cs:650-666 +
 *   PrintStringBuffer.cs:207-262），产出 [HtmlPrintDoc]；[length] 与 [document] 共用同一遍
 *   扫描，**只能调用其中一个**。
 *
 * 错误文案一律取 `MinorShift.Emuera.Properties.lang.emuera-zhs.xml` 的
 * `Error.*` 模板（行号见各 msg* 函数），不自拟。
 *
 * 与 HtmlMethods.kt 的分工：本文件只做 HTML 语法；字宽度量与实体还原由调用方
 * 以 lambda 注入（[measure] / [unescape]），避免复制第二份实体表。
 */
internal class HtmlStrictParser(
    private val src: String,
    private val measure: (String) -> Long,
    private val unescape: (String) -> String,
    /** `Config.FontSize`（ConfigData.cs:66 缺省 18）：`<img>` 重建文案里非 px 值的换算基数。 */
    private val fontSize: Long = 18L,
    /** `Config.FocusColor`（ConfigData.cs:70 缺省 0xFFFF00）：`<shape bcolor>` 的省略判据。 */
    private val focusColor: Int = 0xFFFF00,
    /**
     * 自动折行的**可绘制宽度**（像素）—— FN-03。
     *
     * 原版 `HtmlLength`（HtmlManager.cs:168-182）取的是「第一条**显示行**的宽」，而显示行
     * 由 `PrintStringBuffer.ButtonsToDisplayLines` 按 `Config.DrawableWidth` 折出来
     * （PrintStringBuffer.cs:205 的 `num`、:217 的 `PointX + Width <= num`），
     * 不是「第一个 `<br>` 之前的宽」。所以本类必须在**越过这条宽度时收口**。
     *
     * `DrawableWidth = WindowX - max(2, FontSize / 6)`（Config.cs:461-466）：
     * 基准语料 `emuera.config:12 WINDOW WIDTH:1800` + `:22 FONT SIZE:18` ⇒ **1797px**
     * （= 199 个半角格，半角步进 9px 由 `fixtures/golden/baseline/pc-golden-root.txt:253`
     * 的 199 个 `=` 唯一确定）。移植侧同一式子在 `core/config/EffectiveConfig.kt` 的
     * `drawableWidth`（:115-116、:275）。
     *
     * **取舍（读数以宿主接线值为准）**：本参数是装置面输入。缺省钉的是**PC 基准环境**的
     * 1797px，而 Android 上窗口可绘制宽随设备屏宽与实测半角步进变化（`GameSession`
     * 装配时 `LineBufferConsoleSink.clientWidth` 就是「DrawableWidth ÷ 半角步进」的换算值）
     * ⇒ **同一句 `HTML_STRINGLEN` 在不同屏宽上读数不同**，这与原版一致（原版也按窗口宽折行）。
     * 生产路径要精确对齐真机必须由宿主将 `EffectiveConfig.drawableWidth` 传进来
     * （调用点 `HtmlMethods.kt` 的 `htmlLength` 不在本路文件面 ⇒ 见交付报告「需组长接线」）。
     * <= 0 = 不折行（等价于原版 `nobr` 分支，只用于装置对照）。
     */
    private val drawableWidthPx: Long = DEFAULT_DRAWABLE_WIDTH_PX,
    /**
     * 是否顺手产出显示行（[document] 需要）。量宽路径（[length]）传 false：
     * `HtmlSubString` 的 `getSubStr` 是**逐字符**调 `HtmlLength`（HM :184-204），
     * 每次调用都堆 run/row 只是白烧内存。
     */
    private val collectRows: Boolean = true,
    /**
     * 带样式的分段量宽（FN-04 core）：`(文本, 当前样式快照) -> 像素宽`。
     *
     * 原版 `ConsoleStyledString.SetWidth` 按段的 `StringStyle` 选字体再量
     * （ConsoleStyledString.cs:75-95、StringMeasure.cs:42-72），粗体/斜体/`<font face>`
     * 都会改变读数；[measure] 只吃纯文本。null（缺省）= 只用 [measure]，既有装置与
     * 测试的读数不变；生产由 `HtmlMethods.measureText` 接
     * `BuiltinHost.measureTextRun`。样式快照在**文本段收口时**取
     * （[flush] 发生在标签生效之前），与 `HtmlPrintRun` 的样式来源同一处。
     */
    private val measureStyled: ((String, HtmlPrintStyle) -> Long)? = null,
) {
    private val text = StringBuilder()
    private var pos = 0
    private var width = 0L

    /**
     * 当前**按钮组**（原版 `ConsoleButtonString`）内的段宽度表；组边界 = `<button>` /
     * `</button>` / `<nonbutton>` / `</nonbutton>` / 换行 / 串尾（`HtmlManager.cs:1011`、`:1019`、
     * `:1559` 置 `FlagButton`，`:659-661` 据此调 `cssToButton`）。
     * 折行判据是按**组**比宽的（`PrintStringBuffer.cs:217`），所以必须先成组再判，
     * 不能像旧实现那样逐段累加。
     */
    private var group = ArrayList<HtmlGroupNode>()

    /**
     * 亚像素余数（原版在段之间传递 `XsubPixel`，`PrintStringBuffer.cs` 的
     * `setWidthToButtonList` :403-410 + `ConsoleButtonString.CalcWidth` :271）。
     * `ConsoleSpacePart.SetWidth`（ConsoleSpacePart.cs:18-23）与
     * `ConsoleRectangleShapePart.SetWidth`（:50-58）都把它**加进自己的宽**再取整，
     * 所以 shape 段的宽依赖前一段的余数；文本段只是原样带过（`ConsoleStyledString.SetWidth` :83）。
     */
    private var subPixel = 0.0

    // ---- 显示行缓冲（HtmlPrintDoc 的数据源；与量宽互不影响） ----

    /** 当前行已收的 run；遇 `<br>`/裸换行由 [endRow] 收进 [rows]。 */
    private var runs = ArrayList<HtmlPrintRun>()

    /** 已收口的显示行（不含当前行）。 */
    private val rows = ArrayList<HtmlPrintRow>()

    /** `<font>` 帧栈（原版 `FonttagList`，HtmlManager.cs:98/1601-1669）。 */
    private var fontStack = ArrayList<HtmlPrintStyle>()

    /** 当前按钮组（原版 `CurrentButtonTag`，:112/1476-1563）；null = 不在按钮组内。 */
    private var curButton: HtmlPrintButton? = null

    /** `<div>` 帧栈（原版递归子解析，:606-648）；嵌套 `<div>` 由 [hasDiv] 挡下。 */
    private val divFrames = ArrayDeque<DivFrame>()

    /** 驱动一次就锁死：量宽与产出文档共用同一遍扫描（原版也只解析一遍）。 */
    private var driven = false

    /**
     * 是否还在第一条显示行内。置 false 的两个点：首个 `<br>` / 裸换行（[newLine]），
     * 以及折行收口（[commitGroup]，FN-03）；之后只校验不量宽。
     */
    private var measuring = true

    /** `HtmlAnalzeState.LineHead`（HtmlManager.cs:94）：只被文本段置 false（:574）。 */
    private var lineHead = true

    /** `HtmlAnalzeState.FontStyle`（:96）：Bold=1 / Italic=2 / Underline=4 / Strikeout=8。 */
    private var styleMask = 0

    private var flagP = false
    private var flagPClosed = false
    private var flagNobr = false
    private var flagNobrClosed = false

    /** `HtmlAnalzeState.Alignment`（:108），只用于 pos 校验（:691-694）。 */
    private var align = ALIGN_LEFT

    /** `CurrentButtonTag.IsButtonTag`（:77）：开的是 `<button>` 还是 `<nonbutton>`。 */
    private var buttonIsTag = false

    /** 任一 button 带过 `pos=`（`PointXisLocked`，:89）。 */
    private var posLocked = false

    /** `CurrentDivTag != null`（:120）。 */
    private var hasDiv = false

    private var flagClearButton = false
    private var flagClearButtonTooltip = false

    /**
     * `HtmlManager.HtmlLength`（:168-182）：第一条显示行的像素宽 =
     * `array[0].Buttons[].Width` 之和（:176-181）。
     *
     * 「第一条显示行」有两个终止条件（FN-03）：
     * 1. `<br>` / 裸换行（`FlagBr` → `list2.Add(null)`，:650-657 与 :663）；
     * 2. 折行 —— 组宽使 `PointX + Width` 越过 `Config.DrawableWidth`（:698 →
     *    `PrintStringBuffer.ButtonsToDisplayLines` :205/:217）。
     *
     * spec 交叉引用：`specs/builtin-functions.yaml:634-641`（那条把第二参语义写反了，
     * 权威口径 = `FunctionMethodCreator.cs:54-66` + 基准
     * `fixtures/golden/baseline/pc-golden-root.txt:297`，见 `HtmlMethods.kt` 类注释）。
     */
    fun length(): Int {
        drive()
        return width.toInt()
    }

    /**
     * `HtmlManager.Html2DisplayLine`（:517-527）：整串解析成显示行。
     *
     * 与 [length] 共用同一遍扫描（[driven] 锁），**同一个实例只能调一个**。
     * 串尾校验（:667-674）与 pos 校验（:683-697）照 [length] 一样执行，故畸形输入
     * 在这里抛的 CodeEE 与量宽路径逐字相同。
     */
    fun document(): HtmlPrintDoc {
        check(collectRows) { "HtmlStrictParser.collectRows = false 时没有行可交；量宽请用 length()" }
        drive()
        val all = ArrayList<HtmlPrintRow>(rows.size + 1)
        all.addAll(rows)
        // 尾行：最后一个 `<br>`/换行之后的残留；空则不成行（PrintStringBuffer.cs:259-262）
        if (runs.isNotEmpty()) all.add(HtmlPrintRow(runs.toList()))
        val a = when (align) {
            ALIGN_CENTER -> com.eratw.port.core.host.Alignment.CENTER
            ALIGN_RIGHT -> com.eratw.port.core.host.Alignment.RIGHT
            else -> com.eratw.port.core.host.Alignment.LEFT
        }
        return HtmlPrintDoc(all.map { it.copy(align = a, noBreak = flagNobr) })
    }

    /** 单遍扫描本体（原版 `html2DisplayLine` 的 while 循环，:545-666）。 */
    private fun drive() {
        if (driven) return
        driven = true
        val hasComment = src.contains("<!--") // :534
        val hasReturn = src.indexOf('\n') >= 0 // :535
        while (pos < src.length) {
            // :547-555：下一个 '<' 或（有裸换行时）更靠前的 '\n'
            var cut = src.indexOf('<', pos)
            if (hasReturn) {
                val nl = src.indexOf('\n', pos)
                if (nl >= 0 && (cut < 0 || cut > nl)) cut = nl
            }
            if (cut < 0) {
                // :556-568：尾文本先入段，再按 FlagPClosed / FlagNobrClosed 抛
                addText(src.substring(pos))
                if (flagPClosed) throw CodeEE(ERR_TEXT_AFTER_P)
                if (!flagNobrClosed) break
                throw CodeEE(ERR_TEXT_AFTER_NOBR)
            }
            if (cut > pos) {
                // :570-576
                addText(src.substring(pos, cut))
                lineHead = false
                pos = cut
            }
            if (hasComment && src.startsWith("<!--", pos)) {
                // :577-587
                val end = src.indexOf("-->", pos + 4)
                if (end < 0) throw CodeEE(ERR_COMMENT_NOT_CLOSED)
                pos = end + 3
                continue
            }
            if (src[pos] == '\n') {
                // :588-592
                newLine()
                pos++
                continue
            }
            // :595-605
            pos++
            val gt = analyzeTag(pos)
            if (gt >= src.length || src[gt] != '>') throw CodeEE(ERR_NO_TAG_END)
            pos = gt + 1
        }
        flush()
        commitGroup()
        finish()
    }

    // -----------------------------------------------------------------------
    // 量宽 + 显示行
    // -----------------------------------------------------------------------

    /** 文本段：先 Unescape（:558/:572），仍在第一条显示行内才攒进缓冲；同时收进显示行。 */
    private fun addText(chunk: String) {
        if (chunk.isEmpty()) return
        val t = unescape(chunk)
        if (measuring) text.append(t)
        if (collectRows) runs.add(HtmlPrintRun(HtmlRunKind.TEXT, t, currentStyle(), curButton))
    }

    /**
     * 文本段收进当前组（FN-03：宽度**不再**逐段直接加到 [width]，而是先攒成组）。
     *
     * `ConsoleStyledString.SetWidth`（ConsoleStyledString.cs:75-83）量出的是**整段**文本的宽，
     * `XsubPixel` 只是把上一段的余数原样带过去（:83），所以文本段不吸收余数。
     */
    private fun flush() {
        if (text.isEmpty()) return
        val t = text.toString()
        text.setLength(0)
        if (!measuring) return
        val style = currentStyle()
        group.add(HtmlGroupNode.text(t, measureWithStyle(t, style), style))
    }

    /** 分段量宽：优先带样式回调，缺省回落到纯文本 [measure]（旧读数不变）。 */
    private fun measureWithStyle(s: String, style: HtmlPrintStyle): Long =
        measureStyled?.invoke(s, style) ?: measure(s)

    /**
     * 组收口 = 原版 `cssToButton`（:836-854）+ `ButtonsToDisplayLines` 的折行判定
     * （PrintStringBuffer.cs:207-251），只管**第一条显示行**。
     *
     * div 子解析期间（[divFrames] 非空）组一律**丢弃**、不进 [width]：子行的宽属于
     * 浮动块自己的 `ConsoleDisplayLine[]`（HtmlManager.cs:636-642 递归产物），父行里的
     * `ConsoleDivPart` 节点宽恒 0（ConsoleDivPart.cs:217-219 `SetWidth` 空实现 +
     * AConsoleDisplayNode.cs:27 缺省 0 ⇒ ConsoleButtonString.cs:266-270 累加得 0）。
     *
     * 组宽 = 各段宽之和（`ConsoleButtonString.CalcPointX` :300-303 的
     * `last.PointX + last.Width - first.PointX`）；**整组宽 <= 0 时原版把组宽写成 -1**
     * （`CalcWidth` :273-276），`<shape type='space' param='0px'>` 这类零宽组因此
     * 在 `HTML_STRINGLEN` 里记 **-1px**（官方口径，ADR-005 复刻，不修）。
     *
     * 折行（[foldActive] 为真时）逐字对应 :217-241 的三个分支：
     * - `PointX + Width <= DrawableWidth` → 整组留在本行；
     * - 放不下 → 试**字符级拆分**（`getDivideIndex` :414-451 + :453-496）：能拆出非零前缀时，
     *   前缀留在第一条显示行、其余进第二条（:227-235 先 `list2.Add` 再 :242 结行），
     *   第一条显示行到此结束；
     * - 拆不出（组宽全部来自不可拆的 img/shape）：本行为空则整组占一条溢出行
     *   （:236-239 `divideIndex == 0 && list2.Count == 0`），本行非空则整组挪到下一行（:242）。
     * 两种「结束」都置 [measuring] = false，与原版「只取 array[0]」等价。
     */
    private fun commitGroup() {
        if (divFrames.isNotEmpty()) {
            group.clear() // div 子行的组不进父行宽（见 KDoc；组内内容在 closeDiv 整体丢弃）
            return
        }
        if (!measuring) {
            group.clear()
            return
        }
        if (group.isEmpty()) return // :653/:659/:675 的 `list.Count > 0`
        var groupPx = 0L
        for (n in group) groupPx += n.widthPx
        if (groupPx <= 0L) groupPx = -1L
        if (!foldActive()) {
            width += groupPx
            group.clear()
            return
        }
        if (width + groupPx <= drawableWidthPx) {
            width += groupPx
            group.clear()
            return
        }
        val fit = fittingPortionPx()
        when {
            fit > 0L -> width += fit
            width == 0L -> width += groupPx
        }
        group.clear()
        measuring = false
    }

    /**
     * `</button>` / `</nonbutton>` 的结组点：原版 `FlagButton`（HM :1011、:1019）
     * 在 :659-661 调 `cssToButton`。抽成方法让 [closeTag] 的两个分支共用同一条时序。
     */
    private fun closeButtonGroup() {
        flush()
        commitGroup()
    }

    /**
     * 折行是否生效。两种情形不折（原版同判据）：
     * - `nobr`：`HtmlLength` 把 `htmlAnalzeState.FlagNobr`（HtmlManager.cs:698）当参数交给
     *   `ButtonsToDisplayLines`，:217 的 `if (nobr || …)` 让整串留在同一条显示行；
     * - [drawableWidthPx] <= 0：装置对照用（显式关掉折行）。
     *
     * div 子解析不在此判：子解析期间 [commitGroup] 直接丢弃组（子层折行宽
     * `SubDivisionWidth` 是 FN-02 的版面语义，本路不折），到不了这里。
     */
    private fun foldActive(): Boolean =
        drawableWidthPx > 0L && !flagNobr

    /**
     * `PrintStringBuffer.getDivideIndex(button, sm, divWidth)`（:414-451）的宽度侧读数：
     * 第一条显示行还能吃下本组的多少像素。
     *
     * 与原版一样**以字符数为闸门**：`num2` 只累加放得下段的 `Text.Length`（img/shape 的
     * `Text` 是空串 ⇒ 贡献 0），第一个放不下的段若是**文本段**
     * （`ConsoleStyledString.CanDivide = true`，ConsoleStyledString.cs:15）再按 :453-496 的
     * 二分求可放前缀（判定式同为 `GetDisplayLength(前缀) <= 剩余额度`）并加进 `num2`。
     * `DivideAt` 只在 `divideIndex > 0` 时才真拆（:189-192 与 :227 的分支），
     * 所以 **`num2 == 0` ⇒ 拆不出来 ⇒ 返回 0**，哪怕前面有放得下的 img/shape 段
     * （`[img(50px) 放得下, img2 放不下]` 就是这一档：原版 `divideIndex = 0` → 整组挪到下一行）。
     * 拆得开时，第一条显示行的宽 = 放得下的各段宽 + 文本段前缀的实测宽
     * （`ConsoleButtonString.DivideAt` :198-229 的归属：按字符走，段前缀之后的段全部进后半）。
     *
     * 已知偏差（登记）：原版 :427-434 在「不可拆段放不下且前面还没有放得下的段」时**不 break**，
     * 继续扫描后面的段（`num` 不累计该段宽）。本实现在第一个放不下的段就收，
     * 两条口径只在「组内首段是超宽 img/shape、后面还跟着长文本」时分叉
     * （原版此时会把 img + 文本前缀 一起留在本行，本法按「整组占一条溢出行」处理）；
     * 复刻它要重建 `DivideAt` 的按字符分段归属（:187-213），收益只是极端超宽图元的读数，
     * 不值得第二份组模型。
     */
    private fun fittingPortionPx(): Long {
        var x = width
        var fitPx = 0L
        var fitChars = 0
        var i = 0
        while (i < group.size) {
            val n = group[i]
            if (x + n.widthPx > drawableWidthPx) break
            fitPx += n.widthPx
            fitChars += n.textLength
            x += n.widthPx
            i++
        }
        if (i < group.size) {
            val s = group[i].text // img / shape 不可拆 ⇒ 没有前缀可言
            if (s != null) {
                val style = group[i].style
                val inner = bisectFitChars(s, drawableWidthPx - x, style)
                if (inner > 0) {
                    fitPx += if (style != null) measureWithStyle(s.substring(0, inner), style) else measure(s.substring(0, inner))
                    fitChars += inner
                }
            }
        }
        return if (fitChars == 0) 0L else fitPx
    }

    /**
     * `PrintStringBuffer.getDivideIndex(part, sm, divWidth)`（:453-496）的二分：返回最长
     * 度量值 <= [budget] 的**UTF-16 码元**前缀长度。
     *
     * 步长与原版逐句一致：`mid = (lo + hi) / 2`；`mid == lo` 时 `mid++`（:477-480）、
     * `mid == hi` 时收束（:481-484）。空串与 `budget <= 0` 都返回 0（原版初值 `num2 = 0`，
     * 任何一次判定不过都只收缩右界）。切点按码元而非码位，和原版 `Substring(index)` 同形
     * （代理对可能被切成两半 —— 官方口径，ADR-005）。
     */
    private fun bisectFitChars(s: String, budget: Long, style: HtmlPrintStyle? = null): Int {
        if (s.isEmpty() || budget <= 0L) return 0
        var lo = 0
        var hi = s.length
        while (true) {
            var mid = (lo + hi) / 2
            if (mid == lo) mid++
            if (mid == hi) break
            val w = if (style != null) measureWithStyle(s.substring(0, mid), style) else measure(s.substring(0, mid))
            if (w <= budget) lo = mid else hi = mid
        }
        return lo
    }

    /** 当前样式快照（原版 `GetSS`，:128-150）：字体帧栈顶 + 样式位。 */
    private fun currentStyle(): HtmlPrintStyle {
        val top = fontStack.lastOrNull()
        return HtmlPrintStyle(
            fore = top?.fore,
            buttonColor = top?.buttonColor,
            fontName = top?.fontName,
            styleBits = styleMask,
        )
    }

    /**
     * `<br>` 与裸换行（:588-592、:1081-1087）：结束第一条显示行，之后只校验。
     *
     * 顺序照原版：`FlagBr` 先经 `cssToButton` 把当前组建结（:653-657），
     * `list2.Add(null)` 才结出显示行，然后才轮到「只取 array[0]」。
     *
     * div 内的 `<br>` 属于递归子解析自己的 `FlagBr`（HtmlManager.cs:606-648），
     * 关闭 div 时由 [closeDiv] 把 [measuring] 恢复成进入 div 之前的值，故子行的换行
     * 不会让父行在 `</div>` 之后漏量。
     */
    private fun newLine() {
        flush()
        commitGroup()
        endRow()
        measuring = false
    }

    /**
     * 行收口：`HtmlManager.cs:650-666` 的 `FlagBr` 分支 + `PrintStringBuffer.cs:209-216`。
     *
     * 空行也要收（`<br>` 落在空表上照样结出一条空显示行）；
     * 尾部 `<br>` 之后不再补行，见 [HtmlPrintDoc] 的行数表。
     */
    private fun endRow() {
        if (!collectRows) return
        rows.add(HtmlPrintRow(runs.toList(), alignOf(align), flagNobr))
        runs = ArrayList()
    }

    private fun alignOf(v: Int): com.eratw.port.core.host.Alignment = when (v) {
        ALIGN_CENTER -> com.eratw.port.core.host.Alignment.CENTER
        ALIGN_RIGHT -> com.eratw.port.core.host.Alignment.RIGHT
        else -> com.eratw.port.core.host.Alignment.LEFT
    }

    /** 串尾校验（:667-674、:683-697）。 */
    private fun finish() {
        if (hasDiv) throw CodeEE(ERR_TAG_NOT_CLOSED)
        if (curButton != null || styleMask != 0 || fontStack.isNotEmpty()) throw CodeEE(ERR_TAG_NOT_CLOSED)
        if (posLocked) {
            if (!flagNobr) throw CodeEE(ERR_POS_WITHOUT_NOBR)
            if (align != ALIGN_LEFT) throw CodeEE(ERR_POS_NOT_LEFT)
        }
    }

    // -----------------------------------------------------------------------
    // 标签切分（tagAnalyze 入口，:951-1056）
    // -----------------------------------------------------------------------

    /** 返回 `>` 的下标；找不到返回 [src] 长度，由调用方抛 NotFoundTerminateTag（:597-600）。 */
    private fun analyzeTag(from: Int): Int {
        if (from >= src.length) return src.length
        if (src[from] == '/') {
            // :954-964：闭标签整段 Trim 后匹配
            val gt = src.indexOf('>', from + 1)
            if (gt < 0) return src.length
            closeTag(src.substring(from + 1, gt).trim())
            return gt
        }
        if (src.indexOf('>', from) < 0) return src.length
        var i = from
        val nameStart = i
        while (i < src.length && isNameChar(src[i])) i++
        val rawName = src.substring(nameStart, i)
        i = skipWs(i)
        var attrs: List<Pair<String, String>>? = null
        if (i < src.length && src[i] != '>') {
            // :1045-1050：名字之后仍有内容 → 进入属性词法
            if (isAttrlessName(rawName)) throw CodeEE(msgAttrOnTag(rawName))
            val parsed = readAttrs(i) ?: throw CodeEE(msgTagError(src))
            attrs = parsed.first
            i = parsed.second
        }
        if (rawName.isEmpty()) throw CodeEE(msgTagError(src)) // :1056 + :1677
        openTag(rawName, attrs)
        return i
    }

    /** 标签名/属性名的字符集：到空白、`>`、`=`、`/`、引号、`<` 为止。 */
    private fun isNameChar(c: Char): Boolean =
        !isSpace(c) && c != '>' && c != '=' && c != '/' && c != '\'' && c != '"' && c != '<'

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun skipWs(from: Int): Int {
        var i = from
        while (i < src.length && isSpace(src[i])) i++
        return i
    }

    /** `b/i/u/s/br/nobr` 一旦带任何尾部内容就是「设置了属性」（:1071-1074、:1082-1085、:1089-1092）。 */
    private fun isAttrlessName(raw: String): Boolean {
        val n = raw.lowercase(Locale.ROOT)
        return n == "b" || n == "i" || n == "u" || n == "s" || n == "br" || n == "nobr"
    }

    /**
     * 属性三元组 `name = 'literal'`（LexicalAnalyzer.Analyse，:1049）；结构不合语法返回 null。
     * 原版的 `WordCollection` 只认这种三元组，其它形状在各 case 里 break 到 :1677 HtmlTagError。
     */
    private fun readAttrs(from: Int): Pair<List<Pair<String, String>>, Int>? {
        val out = ArrayList<Pair<String, String>>()
        var i = from
        // 注意：调用方 [analyzeTag] 在调本函数前已 `skipWs`，所以这里**不能**再要求
        // 首字符是空白（否则 `<p align='x'>` 的 align 会被当成语法错误，具体文案也被
        // 通用 TagError 吞掉）。每轮开头重新 skipWs 即可覆盖 `a='1' b='2'` 的间隔。
        while (i < src.length && src[i] != '>') {
            i = skipWs(i)
            if (i >= src.length) return null
            if (src[i] == '>') break
            val nameStart = i
            while (i < src.length && !isSpace(src[i]) && src[i] != '=' && src[i] != '>') i++
            if (i == nameStart) return null
            val name = src.substring(nameStart, i)
            i = skipWs(i)
            if (i >= src.length || src[i] != '=') return null
            i++
            i = skipWs(i)
            if (i >= src.length) return null
            val quote = src[i]
            if (quote != '\'' && quote != '"') return null
            i++
            val valueStart = i
            while (i < src.length && src[i] != quote) i++
            if (i >= src.length) return null
            out.add(name to src.substring(valueStart, i))
            i++
        }
        if (i >= src.length) return null
        return out to i
    }

    // -----------------------------------------------------------------------
    // 开标签（:1056-1676）
    // -----------------------------------------------------------------------

    private fun openTag(raw: String, attrs: List<Pair<String, String>>?) {
        when (raw.lowercase(Locale.ROOT)) {
            "b" -> styleOpen(raw, STYLE_BOLD, attrs)
            "i" -> styleOpen(raw, STYLE_ITALIC, attrs)
            "u" -> styleOpen(raw, STYLE_UNDERLINE, attrs)
            "s" -> styleOpen(raw, STYLE_STRIKE, attrs)
            "br" -> {
                if (attrs != null) throw CodeEE(msgAttrOnTag(raw))
                newLine()
            }
            "nobr" -> {
                if (attrs != null) throw CodeEE(msgAttrOnTag(raw))
                if (!lineHead) throw CodeEE(msgTagNotBegin("nobr"))
                if (flagNobr) throw CodeEE(msgDuplicateTag("nobr"))
                flagNobr = true
            }
            "p" -> openP(raw, attrs)
            "font" -> openFont(raw, attrs)
            "img" -> openImg(raw, attrs)
            "div" -> openDiv(raw, attrs)
            "shape" -> openShape(raw, attrs)
            "button" -> openButtonTag(raw, attrs, true)
            "nonbutton" -> openButtonTag(raw, attrs, false)
            "clearbutton" -> openClearButton(raw, attrs)
            // :1677
            else -> throw CodeEE(msgTagError(src))
        }
    }

    /** `b/i/u/s` 共用体（:1061-1080）：属性 → AttributeSetToTag；同位重复 → DuplicateTag。 */
    private fun styleOpen(raw: String, bit: Int, attrs: List<Pair<String, String>>?) {
        if (attrs != null) throw CodeEE(msgAttrOnTag(raw))
        if (styleMask and bit != 0) throw CodeEE(msgDuplicateTag(raw))
        // 量宽分段：标签前后的文本属于不同 ConsoleStyledString（原版按 style 取字体度量），
        // 必须在改 styleMask 之前把前一段收口，否则前文会被后一段的样式量掉。
        flush()
        styleMask = styleMask or bit
    }

    /** `case "p"`（:1103-1147）：恰好一个 `align='left|center|right'`。 */
    private fun openP(raw: String, attrs: List<Pair<String, String>>?) {
        if (attrs == null) throw CodeEE(msgTagHasNoAttr(raw))
        if (!lineHead) throw CodeEE(msgTagNotBegin("p"))
        if (flagNobr) throw CodeEE(msgDuplicateTag("p"))
        if (attrs.size != 1) throw CodeEE(msgTagError(src))
        val key = attrs[0].first
        val value = attrs[0].second
        if (!key.equals("align", ignoreCase = true)) throw CodeEE(msgCanNotInterpretPAttr(key))
        when (unescape(value).lowercase(Locale.ROOT)) {
            "left" -> align = ALIGN_LEFT
            "center" -> align = ALIGN_CENTER
            "right" -> align = ALIGN_RIGHT
            else -> throw CodeEE(msgCanNotInterpretAttr(value))
        }
        flagP = true
    }

    /**
     * `case "font"`（:1601-1672）：color / bcolor / face。
     *
     * 缺省属性**显式继承外层帧**（:1651-1667 把父帧的值抄进新帧），所以栈顶帧永远是
     * 完整快照 —— [currentStyle] 只看栈顶这一条就够。缺属性且没有外层帧时留 null
     * （= 用 `Config` 的缺省，HM :130-131 与 StringStyle.cs:41-44）；继承来的值同样要过
     * 下面那两处归一，因为原版是在**重建文案时**才比的（HM :875/:887-891），不看「有没有写」。
     */
    private fun openFont(raw: String, attrs: List<Pair<String, String>>?) {
        if (attrs == null) throw CodeEE(msgTagHasNoAttr(raw))
        var fore: Int? = null
        var bcolor: Int? = null
        var face: String? = null
        val seen = HashSet<String>()
        for ((k, rawValue) in attrs) {
            val key = k.lowercase(Locale.ROOT)
            if (!seen.add(key)) throw CodeEE(msgDuplicateAttr(raw, k))
            val value = unescape(rawValue)
            when (key) {
                // :1626/:1633 原版用 `Color >= 0` 当「已设过」的哨兵，重复即 DuplicateAttribute
                "color" -> fore = colorToInt(value)
                "bcolor" -> bcolor = colorToInt(value)
                "face" -> face = value
                else -> throw CodeEE(msgCanNotInterpretAttrName(raw, k))
            }
        }
        val parent = fontStack.lastOrNull()
        // 两点归一（都发生在 StringStyle 构造 / 重建文案那一侧，本层提前折成模型口径）：
        // - face：StringStyle.cs:41-44 `string.IsNullOrEmpty(fontname)` → Config.FontName，
        //   故显式 `face=''` 是「回缺省」而不是「继承外层」（空串非 null，不触发 :1651-1667 的继承）；
        // - bcolor：getStringStyleStartingTag（HtmlManager.cs:887-891）只在
        //   `ButtonColor != Config.FocusColor` 时写 bcolor，故「指定成 FocusColor」等价于「没写」。
        val faceValue = face ?: parent?.fontName
        val bcolorValue = bcolor ?: parent?.buttonColor
        // 量宽分段：<font> 前的文本用外层字体量（同 [styleOpen]）。
        flush()
        fontStack.add(
            HtmlPrintStyle(
                fore = fore ?: parent?.fore,
                buttonColor = bcolorValue?.takeUnless { it == focusColor },
                fontName = faceValue?.takeUnless { it.isEmpty() },
                styleBits = styleMask,
            )
        )
    }

    /**
     * `case "img"`（:1148-1224）：src 必填；width/height/ypos 走 MixedNum。
     *
     * 段文本是原版 `ConsoleImagePart` 构造里**重建**出来的标签文案
     * （ConsoleImagePart.cs:41-59）：固定次序 `src srcb srcm height width ypos`，
     * 每个 MixedNum 走 `Utils.AddTagMixedNumArg`（Utils.cs:364-371，`num == 0` 时整个属性不写，
     * 非 px 值按 `num * FontSize / 100` 归一成像素，Utils.cs:87-98）。
     * 图元宽度按 [imgGroupNode] 计入本组（FN-01），不再是 0。
     */
    private fun openImg(raw: String, attrs: List<Pair<String, String>>?) {
        if (attrs == null) throw CodeEE(msgTagHasNoAttr(raw))
        var src: String? = null
        var srcb: String? = null
        var srcm: String? = null
        var height: MixedNum? = null
        var width: MixedNum? = null
        var ypos: MixedNum? = null
        val seen = HashSet<String>()
        for ((k, rawValue) in attrs) {
            val key = k.lowercase(Locale.ROOT)
            if (!seen.add(key)) throw CodeEE(msgDuplicateAttr(raw, k))
            val value = unescape(rawValue)
            when (key) {
                "src" -> src = value
                "srcb" -> srcb = value
                "srcm" -> srcm = value
                "height" -> height = parseMixedNum(raw, k, value)
                "width" -> width = parseMixedNum(raw, k, value)
                "ypos" -> ypos = parseMixedNum(raw, k, value)
                else -> throw CodeEE(msgCanNotInterpretAttrName(raw, k))
            }
        }
        if (src == null) throw CodeEE(msgNotSetAttr(raw, "src"))
        flush()
        val sb = StringBuilder("<img src='").append(src).append('\'')
        if (srcb != null) addTagArg(sb, "srcb", srcb!!)
        if (!srcm.isNullOrEmpty()) addTagArg(sb, "srcm", srcm!!)
        addTagMixedNumArg(sb, "height", height)
        addTagMixedNumArg(sb, "width", width)
        addTagMixedNumArg(sb, "ypos", ypos)
        sb.append('>')
        if (measuring) group.add(imgGroupNode(sb.toString(), width))
        if (collectRows) runs.add(HtmlPrintRun(HtmlRunKind.IMAGE, sb.toString(), currentStyle(), curButton))
    }

    /** `case "div"`（:1225-1373）：width / height 必填；其余属性按原版名单校验。 */
    private fun openDiv(raw: String, attrs: List<Pair<String, String>>?) {
        if (hasDiv) throw CodeEE(msgNestedTag("div"))
        var hasWidth = false
        var hasHeight = false
        val seen = HashSet<String>()
        if (attrs != null) {
            for ((k, rawValue) in attrs) {
                val key = k.lowercase(Locale.ROOT)
                if (!seen.add(key)) throw CodeEE(msgDuplicateAttr(raw, k))
                val value = unescape(rawValue)
                when (key) {
                    "width" -> {
                        if (hasWidth) throw CodeEE(msgDuplicateAttr(raw, "width"))
                        checkMixedNum(raw, key, value)
                        hasWidth = true
                    }
                    "height" -> {
                        if (hasHeight) throw CodeEE(msgDuplicateAttr(raw, "height"))
                        checkMixedNum(raw, key, value)
                        hasHeight = true
                    }
                    "xpos", "ypos" -> checkMixedNum(raw, key, value)
                    "depth" -> {
                        if (value.trim().toIntOrNull() == null) throw CodeEE(msgAttrNotNum(raw, k))
                    }
                    "color" -> checkColor(value)
                    "bcolor" -> checkColorList(value)
                    "size" -> {
                        if (hasWidth) throw CodeEE(msgDuplicateAttr(raw, "width"))
                        if (hasHeight) throw CodeEE(msgDuplicateAttr(raw, "height"))
                        val parts = value.split(',')
                        if (parts.size != 2) throw CodeEE(msgClearButtonValue(raw, k, rawValue))
                        checkMixedNum(raw, "width", parts[0].trim())
                        checkMixedNum(raw, "height", parts[1].trim())
                        hasWidth = true
                        hasHeight = true
                    }
                    "rect" -> {
                        if (hasWidth) throw CodeEE(msgDuplicateAttr(raw, "width"))
                        if (hasHeight) throw CodeEE(msgDuplicateAttr(raw, "height"))
                        val parts = value.split(',')
                        if (parts.size != 4) throw CodeEE(msgClearButtonValue(raw, k, rawValue))
                        checkMixedNum(raw, "xpos", parts[0].trim())
                        checkMixedNum(raw, "ypos", parts[1].trim())
                        checkMixedNum(raw, "width", parts[2].trim())
                        checkMixedNum(raw, "height", parts[3].trim())
                        hasWidth = true
                        hasHeight = true
                    }
                    "display" -> {
                        if (!value.equals("absolute", ignoreCase = true) &&
                            !value.equals("relative", ignoreCase = true)
                        ) {
                            throw CodeEE(msgCanNotInterpretAttr(value))
                        }
                    }
                    "border", "radius", "margin", "padding" -> checkBoxModel(raw, k, value)
                    else -> throw CodeEE(msgCanNotInterpretAttrName(raw, k))
                }
            }
        }
        if (!hasWidth) throw CodeEE(msgNotSetAttr(raw, "width"))
        if (!hasHeight) throw CodeEE(msgNotSetAttr(raw, "height"))
        hasDiv = true
        // :606-648：父状态就地保存，子解析从**空节点表 + 新样式状态**开始
        // （HtmlParentInfo 只带过去 SubDivisionWidth / CurrentDivTag / StartingSubDivision /
        // FlagClearButton(+Tooltip)，见 :537-544；样式位、字体栈、LineHead、对齐都重置）。
        // 组/亚像素同样换一层：子层有自己的 setWidthToButtonList（余数从 0 起），
        // 而折行宽是 `SubDivisionWidth` 而非 DrawableWidth（FN-02，本路不折）。
        flush()
        divFrames.addLast(
            DivFrame(
                savedRuns = runs,
                rowMark = rows.size,
                savedMeasuring = measuring,
                savedGroup = group,
                savedSubPixel = subPixel,
                savedStyleMask = styleMask,
                savedFontStack = fontStack,
                savedLineHead = lineHead,
                savedButton = curButton,
                savedButtonIsTag = buttonIsTag,
                savedAlign = align,
                savedFlagP = flagP,
                savedFlagPClosed = flagPClosed,
                savedFlagNobr = flagNobr,
                savedFlagNobrClosed = flagNobrClosed,
                savedPosLocked = posLocked,
                savedFlagClearButton = flagClearButton,
                savedFlagClearButtonTooltip = flagClearButtonTooltip,
            )
        )
        runs = ArrayList()
        group = ArrayList()
        subPixel = 0.0
        fontStack = ArrayList()
        styleMask = 0
        lineHead = true
        curButton = null
        buttonIsTag = false
        align = ALIGN_LEFT
        flagP = false
        flagPClosed = false
        flagNobr = false
        flagNobrClosed = false
        posLocked = false
    }

    /**
     * `case "shape"`（:1374-1475）：param / type 必填。
     *
     * 段文本是 `ConsoleShapePart.CreateShape`（ConsoleShapePart.cs:12-45）重建的标签文案：
     * type **小写**（:14/:18）、param 用 `", "` 分隔且逐项输出**原始 num**（px 项补 `px` 后缀，
     * ConsoleShapePart.cs:20-31 —— shape 不走 `MixedNum.BuilderString` 的百分比归一，
     * 那是 `<img>` 经 `Utils.AddTagMixedNumArg`（Utils.cs:364-371，归一在 BuilderString，
     * :87-98）才有的口径）、`color` 只在实际设过时写（`colorchanged` 口径，:15 折进
     * `color != Config.ForeColor`、:33-38 才落笔）、`bcolor` 只在 != `Config.FocusColor` 时写
     * （:39-44）。缺省取 `Config.ForeColor` / `Config.FocusColor`，`colorchanged` 传的是
     * `num2 >= 0`（HM :1462-1472）。
     *
     * 宽度按 [shapeGroupNode] 计入本组（FN-01）：`space` / `rect` 由 param 直接折算成像素，
     * 其余形态落到 `ConsoleErrorShapePart`，宽度 = 重建文案的文本度量。
     */
    private fun openShape(raw: String, attrs: List<Pair<String, String>>?) {
        if (attrs == null) throw CodeEE(msgTagHasNoAttr(raw))
        var params: List<MixedNum>? = null
        var type: String? = null
        var color: Int? = null
        var bcolor: Int? = null
        val seen = HashSet<String>()
        for ((k, rawValue) in attrs) {
            val key = k.lowercase(Locale.ROOT)
            if (!seen.add(key)) throw CodeEE(msgDuplicateAttr(raw, k))
            val value = unescape(rawValue)
            when (key) {
                "type" -> type = value
                "param" -> params = value.split(',').map { parseMixedNum(raw, k, it.trim()) }
                "color" -> color = colorToInt(value)
                "bcolor" -> bcolor = colorToInt(value)
                else -> throw CodeEE(msgCanNotInterpretAttrName(raw, k))
            }
        }
        if (params == null) throw CodeEE(msgNotSetAttr(raw, "param"))
        if (type == null) throw CodeEE(msgNotSetAttr(raw, "type"))
        flush()
        val sb = StringBuilder("<shape type='").append(type!!.lowercase(Locale.ROOT))
        sb.append("' param='")
        params!!.forEachIndexed { i, p ->
            // ConsoleShapePart.cs:20-31：原始 num 原样输出、px 项补 "px" 后缀，
            // **不**做 `num * FontSize / 100` 的百分比归一（那是 MixedNum.builderString /
            // `<img>` 那条路径，Utils.cs:87-98）。`<shape param='50'>` 的 AltText 回显
            // `param='50'`，不是 `param='9'`。
            sb.append(p.num)
            if (p.isPx) sb.append("px")
            if (i < params!!.size - 1) sb.append(", ")
        }
        sb.append('\'')
        // :15 `colorchanged = colorchanged || color != Config.ForeColor`；未设时 color 就是
        // Config.ForeColor（HM :1462-1464），故「设过」与「不等于缺省」在这里等价。
        if (color != null) sb.append(" color='").append(colorString(color!!)).append('\'')
        if (bcolor != null && bcolor != focusColor) {
            sb.append(" bcolor='").append(colorString(bcolor!!)).append('\'')
        }
        sb.append('>')
        if (measuring) {
            group.add(shapeGroupNode(type!!.lowercase(Locale.ROOT), params!!, sb.toString()))
        }
        if (collectRows) runs.add(HtmlPrintRun(HtmlRunKind.SHAPE, sb.toString(), currentStyle(), curButton))
    }

    /**
     * `<img>` 段的**宽度**（FN-01）—— `ConsoleImagePart` 的构造 + `SetWidth` 两条路径。
     *
     * 图元取到时宽度只由属性决定（ConsoleImagePart.cs:66-80）：
     * - `width='Npx'` → `Width = N`（:72-75），`XsubPixel` 不写、留在字段初值 0
     *   （⇒ 亚像素余数在这里被**清零**）；
     * - `width='N'`（不带 px = FontSize 的百分比）→ `Width = FontSize * N / 100` 整除，
     *   余数进 `XsubPixel = FontSize * N / 100f - Width`（:77-79）；
     * - 上面两条算出的宽为负时取绝对值（:83-87 的 `destRect.Width < 0` 分支）。
     *
     * `width` 缺省或为 0 时（:67-71）原版要用**图元自身宽高比**
     * `DestBaseSize.Width * num / DestBaseSize.Height`（:69，num = height 折算值，:66）。
     * core 层是纯 JVM，没有 sprite 度量表（`:render` 的图元尺寸不在本路文件面），
     * 于是落到原版的另一条分支 —— **图元取不到**：`cImage == null` 时
     * `Text = AltText`（:61-64，宽度的计算整段被 return 跳过），随后
     * `SetWidth`（:110-114）用**文本度量**量出整串重建文案的像素宽。
     *
     * 本层据此取「有 `width` 属性 ⇒ 属性路径；无 ⇒ 图元缺失回落（量 AltText）」，
     * 因为它是 core 唯一能确定性算出的读数。
     * TODO(verify)：不带 `width` 的 `<img>` 在 PC 上（图元存在）读数是
     * `图元宽 * 折算高 / 图元高`，需要宿主接 sprite 尺寸后经 `BuiltinHost` 的新通道
     * 才能精确；语料 110 处 img/shape 里带 `width` 的占多数
     * （如 `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:438` 的 `width='{SPRITEWIDTH(L_SPR)}px'`）。
     */
    private fun imgGroupNode(altText: String, rawWidth: MixedNum?): HtmlGroupNode {
        if (rawWidth == null || rawWidth.num == 0) {
            // 缺图回落量 AltText：原版 SetWidth 用的仍是当前样式（ConsoleImagePart.cs:110-114）。
            return HtmlGroupNode.renderedAsText(altText, measureWithStyle(altText, currentStyle()))
        }
        val w: Int = if (rawWidth.isPx) {
            subPixel = 0.0 // :74 分支不给 XsubPixel 赋值 ⇒ 余数清零
            rawWidth.num
        } else {
            val numerator = fontSize * rawWidth.num // :78 的分子（原版整段是 int 运算）
            val truncated = (numerator / 100).toInt()
            subPixel = numerator.toDouble() / 100.0 - truncated // :79（原版 float，这里同值到 1e-6 量级）
            truncated
        }
        return HtmlGroupNode.element((if (w < 0) -w else w).toLong()) // :83-87 取绝对值
    }

    /**
     * `<shape>` 段的**宽度**（FN-01）—— `ConsoleShapePart.CreateShape`（ConsoleShapePart.cs:46-79）
     * 的形态判定 + 三个子类的 `SetWidth`。
     *
     * - `type='space'` 且恰好 1 个 param：`WidthF = param[0].isPx ? num : num * FontSize / 100f`
     *   （:53，`Utils.MixedNum.ToPixelf`，Utils.cs:60-71；**非 px 是浮点**，不是整除）。
     * - `type='rect'`：
     *   - 1 个 param 且 `num > 0` → 同上（:57-59）；`num <= 0`（含 `0px`）判据不成立 ⇒ 落错误形态；
     *   - 4 个 param → `RectangleF(ToPixelf(x), ToPixelf(y), ToPixelf(w), ToPixelf(h))`（:63），
     *     仅当 `x >= 0 && w > 0 && h > 0` 才成立（:64-67），占宽 = **`X + Width`**
     *     （ConsoleRectangleShapePart.cs:27 —— 四参 rect 把自己的横向偏移也吃进宽度）。
     * - 其余（未知 type、param 个数不合、rect 校验不过）→ `ConsoleErrorShapePart`
     *   （ConsoleShapePart.cs:71-74），它的 `Text` 就是重建文案，
     *   宽度 = 文本度量（ConsoleErrorShapePart.cs:28-37）。
     *
     * `space` / `rect` 的落地宽 = `(int)(上一段余数 + WidthF)`，并把余数**吸收掉**
     * （ConsoleSpacePart.cs:18-23、ConsoleRectangleShapePart.cs:50-58）；
     * 错误形态只把余数原样带过（`XsubPixel = subPixel`，:36）。
     */
    private fun shapeGroupNode(type: String, params: List<MixedNum>, altText: String): HtmlGroupNode {
        val widthF: Double? = when (type) {
            "space" -> if (params.size == 1) toPixelF(params[0]) else null
            "rect" -> when {
                params.size == 1 -> if (params[0].num > 0) toPixelF(params[0]) else null
                params.size == 4 -> {
                    val x = toPixelF(params[0])
                    val w = toPixelF(params[2])
                    val h = toPixelF(params[3])
                    if (x >= 0.0 && w > 0.0 && h > 0.0) x + w else null
                }
                else -> null
            }
            else -> null
        }
        if (widthF == null) {
            return HtmlGroupNode.renderedAsText(altText, measureWithStyle(altText, currentStyle()))
        }
        val total = subPixel + widthF
        val w = total.toInt() // C# 的 (int) 向零截断，Kotlin Double.toInt() 同形
        subPixel = total - w
        return HtmlGroupNode.element(w.toLong())
    }

    /** `Utils.MixedNum.ToPixelf`（Utils.cs:60-71）：px 原值，否则 `num * FontSize / 100f`。 */
    private fun toPixelF(num: MixedNum): Double =
        if (num.isPx) num.num.toDouble() else num.num * fontSize / 100.0

    /**
     * `case "button" / "nonbutton"`（:1476-1563）。
     *
     * 按钮描述落在这一组后续的每个 [HtmlPrintRun] 上（原版是把整组节点包进一个
     * `ConsoleButtonString`，见 `cssToButton` :836-854）。
     * 官方怪癖照抄：没有 `value` 的 `<button>` 不是按钮（:1547 `IsButton = text7 != null`）；
     * `<clearbutton>` 打开期间的按钮一律降级非按钮（:1549-1551），`notooltip='true'`
     * 时连标题都清空（:1552-1555）。
     */
    private fun openButtonTag(raw: String, attrs: List<Pair<String, String>>?, isButtonTag: Boolean) {
        if (curButton != null) throw CodeEE(ERR_NESTED_BUTTON)
        var value: String? = null
        var title: String? = null
        var pos: Int? = null
        val seen = HashSet<String>()
        if (attrs != null) {
            for ((k, rawValue) in attrs) {
                val key = k.lowercase(Locale.ROOT)
                if (!seen.add(key)) throw CodeEE(msgDuplicateAttr(raw, k))
                val attrValue = unescape(rawValue)
                when (key) {
                    // :1503-1506
                    "value" -> {
                        if (!isButtonTag) throw CodeEE(msgNotSetAttr(raw, "value"))
                        value = attrValue
                    }
                    "title" -> title = attrValue
                    "pos" -> {
                        val p = attrValue.trim().toIntOrNull() ?: throw CodeEE(msgAttrNotNum(raw, k))
                        pos = p
                        posLocked = true
                    }
                    else -> throw CodeEE(msgCanNotInterpretAttrName(raw, k))
                }
            }
        }
        val asButton = if (flagClearButton) false else value != null
        val titleOut = if (flagClearButton && flagClearButtonTooltip) null else title
        val parsed = parseNetLong(value)
        curButton = HtmlPrintButton(
            isButton = asButton,
            value = value,
            valueIsInt = asButton && parsed != null,
            valueInt = parsed ?: 0L,
            title = titleOut,
            pos = pos,
        )
        buttonIsTag = isButtonTag
        // `FlagButton`（HM :1559）：开标签**之前**攒下的段在此结成一个组（:659-661）。
        flush()
        commitGroup()
    }

    /** `case "clearbutton"`（:1564-1600）。 */
    private fun openClearButton(raw: String, attrs: List<Pair<String, String>>?) {
        if (flagClearButton) throw CodeEE(msgNestedTag("clearbutton"))
        if (attrs != null) {
            for ((k, rawValue) in attrs) {
                val key = k.lowercase(Locale.ROOT)
                if (key != "notooltip") throw CodeEE(msgCanNotInterpretAttrName(raw, k))
                val value = unescape(rawValue).lowercase(Locale.ROOT)
                if (value == "true") {
                    flagClearButtonTooltip = true
                } else if (value != "false") {
                    throw CodeEE(msgClearButtonValue(raw, k, rawValue))
                }
            }
        }
        flagClearButton = true
    }

    // -----------------------------------------------------------------------
    // 闭标签（:954-1038）
    // -----------------------------------------------------------------------

    private fun closeTag(raw: String) {
        val name = raw.lowercase(Locale.ROOT)
        val styleBit = when (name) {
            "b" -> STYLE_BOLD
            "i" -> STYLE_ITALIC
            "u" -> STYLE_UNDERLINE
            "s" -> STYLE_STRIKE
            else -> 0
        }
        if (styleBit != 0) {
            // :966-983：不比对开标签名，只校验样式位存在后异或清除
            if (styleMask and styleBit == 0) throw CodeEE(msgUnexpectedClose(raw))
            // 量宽分段：标签内的文本用清除**前**的样式量（同 [styleOpen]）。
            flush()
            styleMask = styleMask xor styleBit
            return
        }
        when (name) {
            "p" -> {
                if (!flagP || flagPClosed) throw CodeEE(msgUnexpectedClose("p"))
                flagPClosed = true
            }
            "nobr" -> {
                if (!flagNobr || flagNobrClosed) throw CodeEE(msgUnexpectedClose("nobr"))
                flagNobrClosed = true
            }
            "font" -> {
                if (fontStack.isEmpty()) throw CodeEE(msgUnexpectedClose("font"))
                // 量宽分段：</font> 内的文本用弹出**前**的字体量（同 [styleOpen]）。
                flush()
                fontStack.removeAt(fontStack.size - 1)
            }
            "button" -> {
                if (curButton == null || !buttonIsTag) throw CodeEE(msgUnexpectedClose("button"))
                curButton = null
                closeButtonGroup()
            }
            "nonbutton" -> {
                if (curButton == null || buttonIsTag) throw CodeEE(msgUnexpectedClose("nonbutton"))
                curButton = null
                closeButtonGroup()
            }
            "clearbutton" -> {
                if (!flagClearButton) throw CodeEE(msgUnexpectedClose("clearbutton"))
                flagClearButton = false
                flagClearButtonTooltip = false
            }
            "div" -> closeDiv()
            else -> throw CodeEE(msgCanNotInterpretClose(raw))
        }
    }

    /**
     * `</div>`：结束子解析并回填父状态（`HtmlManager.cs:606-648` + `:1029-1035`）。
     *
     * 递归层退回前先跑一次「样式/字体/按钮必须空」校验（:671-674）：原版在 `</div>`
     * 之后的 `StartingSubDivision` 分支里 break 出子解析，紧接着就是串尾校验。
     * 子行收进一个 [HtmlRunKind.DIV] 段（`ConsoleDivPart.Children`，ConsoleDivPart.cs:38），
     * 父行照常继续 —— 浮动块在父行里的位置就是 `<div>` 标签的位置。
     *
     * 量宽状态同样回填：[measuring] 是**父解析**的第一显示行状态，子解析里出现的 `<br>`
     * （:590 的 `FlagBr`）只作用于子 state（:537-544 新 `HtmlAnalzeState`，FlagBr 不继承），
     * 不能让父行在 `</div>` 之后停止量宽。
     *
     * 宽度侧（FN-02 的 `HTML_STRINGLEN` 半边）：子组的宽在 [commitGroup] 已整体丢弃；
     * 这里把 div 节点本身以 **0 宽**记回父组 —— `ConsoleDivPart` 进入父节点表
     * （HtmlManager.cs:646）但 `SetWidth` 是空实现（ConsoleDivPart.cs:217-219），
     * `Width` 停在缺省 0（AConsoleDisplayNode.cs:27）⇒ `CalcWidth` 累加得 0
     * （ConsoleButtonString.cs:266-270），`Text = string.Empty`（:102）⇒ 折行字符数为 0，
     * `CanDivide = false`（:66）⇒ 永不可拆。`CalcPointX` 对非 relative 的 div 也**不推进
     * 光标**（ConsoleButtonString.cs:294-298），0 宽节点同样不占折行预算
     * （`getDivideIndex` :427 的 `num + 0 > divWidth` 恒假）。
     */
    private fun closeDiv() {
        val frame = divFrames.removeLastOrNull() ?: throw CodeEE(msgUnexpectedClose("div"))
        // 子层自己的最后一个组：宽度不进父行（见 KDoc），直接丢弃
        flush()
        group.clear()
        if (curButton != null || styleMask != 0 || fontStack.isNotEmpty()) throw CodeEE(ERR_TAG_NOT_CLOSED)
        // 递归层自己的 pos 校验（:683-697 在每个 html2DisplayLine 调用里都跑一遍）
        if (posLocked) {
            if (!flagNobr) throw CodeEE(ERR_POS_WITHOUT_NOBR)
            if (align != ALIGN_LEFT) throw CodeEE(ERR_POS_NOT_LEFT)
        }
        // 子解析尾行（:675-678 的 list.Count > 0 分支）
        val childRows = ArrayList<HtmlPrintRow>()
        if (collectRows) {
            if (runs.isNotEmpty()) {
                rows.add(HtmlPrintRow(runs.toList(), alignOf(align), flagNobr))
                runs = ArrayList()
            }
            for (i in frame.rowMark until rows.size) childRows.add(rows[i])
            while (rows.size > frame.rowMark) rows.removeAt(rows.size - 1)
        }
        // 回填父状态
        runs = frame.savedRuns
        group = frame.savedGroup
        subPixel = frame.savedSubPixel
        measuring = frame.savedMeasuring
        styleMask = frame.savedStyleMask
        fontStack = frame.savedFontStack
        lineHead = frame.savedLineHead
        curButton = frame.savedButton
        buttonIsTag = frame.savedButtonIsTag
        align = frame.savedAlign
        flagP = frame.savedFlagP
        flagPClosed = frame.savedFlagPClosed
        flagNobr = frame.savedFlagNobr
        flagNobrClosed = frame.savedFlagNobrClosed
        posLocked = frame.savedPosLocked
        flagClearButton = frame.savedFlagClearButton
        flagClearButtonTooltip = frame.savedFlagClearButtonTooltip
        hasDiv = false
        // div 节点本身以 0 宽回到父组（CanDivide=false、Text.Length=0 ⇒ 折行零贡献），
        // 父行里 `<div>` 之后的文本与它同组相加 —— 对应 HtmlManager.cs:646 把
        // ConsoleDivPart 加进父 list 的位置语义
        if (measuring) group.add(HtmlGroupNode.element(0L))
        // TODO(verify)：ConsoleDivPart.ToString()（ConsoleDivPart.cs:221-231）会把
        // 重新拼出的 <div ...> 头尾标签也串进去；本模型只保留子行，父行文本里不出现标签。
        // 卡点：`ConsoleDivPart` 的 width/height/xpos/ypos/depth/color/box 归一化文案
        // （Utils.AddTagMixedNumArg 一族）不在本路文件面，等宿主真要 HTML_GETPRINTEDSTR
        // 逐字节对拍浮动块时一并做。
        if (collectRows) runs.add(HtmlPrintRun(HtmlRunKind.DIV, "", currentStyle(), curButton, childRows))
    }

    // -----------------------------------------------------------------------
    // 属性值
    // -----------------------------------------------------------------------

    /** `Utils.ParseMixedNum`（Utils.cs:342-362）：整数或「整数+px」。 */
    private fun checkMixedNum(rawTag: String, attrName: String, value: String) {
        parseMixedNum(rawTag, attrName, value)
    }

    /**
     * `Utils.ParseMixedNum`（Utils.cs:342-362）的取值形态。
     *
     * `"15px"` → (15, isPx=true)；`"15"` → (15, isPx=false，语义是「FontSize 的百分比」，
     * 换算见 `MixedNum.BuilderString`，Utils.cs:87-98）。不合语法抛
     * `AttributeCanNotInterpretNum`，与 [checkMixedNum] 同一文案。
     */
    private fun parseMixedNum(rawTag: String, attrName: String, value: String): MixedNum {
        if (value.endsWith("px", ignoreCase = true)) {
            val num = value.substring(0, value.length - 2).trim().toIntOrNull()
                ?: throw CodeEE(msgAttrNotNum(rawTag, attrName))
            return MixedNum(num, true)
        }
        val num = value.trim().toIntOrNull() ?: throw CodeEE(msgAttrNotNum(rawTag, attrName))
        return MixedNum(num, false)
    }

    /** `Utils.AddTagArg`（Utils.cs:457-462）：` name='value'`。 */
    private fun addTagArg(sb: StringBuilder, name: String, value: String) {
        sb.append(' ').append(name).append("='").append(value).append('\'')
    }

    /**
     * `Utils.AddTagMixedNumArg`（Utils.cs:364-371）：只有 `num != 0` 才写，且走
     * [MixedNum.builderString] 的像素归一。
     */
    private fun addTagMixedNumArg(sb: StringBuilder, name: String, num: MixedNum?) {
        if (num == null || num.num == 0) return
        sb.append(' ').append(name).append("='").append(num.builderString(fontSize)).append('\'')
    }

    /** `HtmlManager.GetColorToString`（:856-862）：`#RRGGBB` 六位大写。 */
    private fun colorString(rgb: Int): String {
        val hex = (rgb and 0xFFFFFF).toString(16).uppercase(Locale.ROOT)
        return "#" + "0".repeat(6 - hex.length) + hex
    }

    /** `Utils.ParseParam4MixedNum`（Utils.cs:247-281）：1..4 段，每段 MixedNum。 */
    private fun checkBoxModel(rawTag: String, key: String, value: String) {
        val parts = value.split(',')
        if (parts.size < 1 || parts.size > 4) throw CodeEE(msgCanNotInterpretAttr(value))
        for (part in parts) checkMixedNum(rawTag, key, part.trim())
    }

    /**
     * `HtmlManager.stringToColorInt32`（:1680-1725）。
     *
     * 命名色名单读唯一权威 [ColorNames]（140 名，框架 KnownColor 实测取证，见其类级 KDoc）。
     * 原版把名字交给 `Color.FromName`（:1705）并只在 `A == 0` 时进入抛错分支（:1706）——
     * 即**任何未知名必抛**，本层收口前「本层没有该表、未知名字不在此处判错」的宽容分支
     * 已按契约 CONTRACT-M4-D §2 d-htmlcolor 删除（COLOR-HTMLSTRICT）。
     *
     * 分支顺序与原版一致：
     * 1. 查表命中（等价 `A > 0`，:1706 取反）返回；`grey` 系拼写不在表内（框架实测 `A == 0`，
     *    ADR-005 按未知名处理）。
     * 2. `transparent` 不在表内（框架返回 `A == 0`）→ [ERR_TRANSPARENT]（:1708-1710）。
     * 3. 官方怪癖：无 `#` 前缀但能按十六进制解析 → 仍抛 InvalidColorName2（:1712-1720）；
     *    连十六进制都解不出 → InvalidColorName（:1718）。
     *
     * 系统色名（[ColorNames.SYSTEM_NAMES]，33 项）在 PC 侧 `FromName` 返回 `A == 255` 被接受，
     * 取值随 Windows 主题变化；按 ColorNames 的入表口径在本层判「未知名」——与原版有行为差，
     * 见交付报告「与基准差异」。
     * `Convert.ToInt32(str, 16)`（:1692 / :1714）的实测口径已在 [netParseInt32] 的 KDoc 里登记
     * （PowerShell 5.1 / .NET Framework 4.8）：`+1f` 能解析（→ InvalidColorName2）、` 1f` 不能
     * （→ InvalidColorName）、`0x90EE90` 能解析（→ 合法颜色）。原先按 `toIntOrNull(16)` 实现的
     * 版本在 `#0xRRGGBB` 上会**误抛**（语料 DLC/透视图优化.ERB:11-12），已修。
     */
    private fun checkColor(value: String) {
        colorToInt(value)
    }

    /**
     * [checkColor] 的取值形态：返回 `0xRRGGBB`（`<font color>` / `<div color>` 等要用它建样式帧）。
     * 校验分支与文案口径见 [checkColor] 的 KDoc，两者是同一段代码。
     */
    private fun colorToInt(value: String): Int {
        if (value.isEmpty()) throw CodeEE(ERR_COLOR_REQUIRED)
        if (value[0] == '#') {
            val digits = value.substring(1)
            // :1692 `Convert.ToInt32(text, 16)`：认 `0x` 前缀（`#0x90EE90` 是合法颜色，
            // 语料 DLC/透视图优化.ERB:11-12 就在用），不认空白与十六进制负号。见 htmlUnescape 旁的
            // netParseInt32 实测表。
            val num = netParseInt32(digits, 16) ?: throw CodeEE(msgBadNumValue(digits))
            // 原版把 OoRColorValue 抛在 try 内，被同一个裸 catch 吞掉 → 实际报 CanNotInterpretNumValue
            if (num < 0 || num > 0xFFFFFF) throw CodeEE(msgBadNumValue(digits))
            return num
        }
        ColorNames.rgb(value)?.let { return it } // :1705-1706：FromName 认得（A > 0）即通过
        if (ColorNames.isTransparent(value)) throw CodeEE(ERR_TRANSPARENT) // :1708-1710
        // 官方怪癖：能按十六进制解析的裸串仍被拒绝（:1712-1720）。
        // 探测同样走 :1714 的 `Convert.ToInt32(str, 16)`：`0x10` / `+1f` 认（→ InvalidColorName2），
        // ` 1f` 不认（→ InvalidColorName）。
        if (netParseInt32(value, 16) != null) throw CodeEE(msgInvalidColorName2(value))
        // 原版 :1718 直接抛 Lang.Error.InvalidColorName.Text，没有 string.Format——
        // 文案里的 {0} 原样上屏（官方怪癖，ADR-005 复刻）。
        throw CodeEE(ERR_INVALID_COLOR_NAME_RAW)
    }

    /**
     * `long.TryParse`（`<button value>`，HtmlManager.cs:1543）的移植。
     *
     * .NET 的默认 `NumberStyles.Integer` 允许**首尾空白与一个正负号**，不接受千分位；
     * Kotlin 的 `toLongOrNull` 只接受符号、**不接受空白**（`" 12 "` → null，实测
     * `java.lang.Long.parseLong`），故必须先 `trim()` 再解析，否则
     * `<button value=' 12 '>` 会漏掉 `ButtonIsInteger`（MOUSEB 的整型按钮口径随之分叉）。
     * 解析失败返回 null（原版 `out result3` 留在 0，`ButtonIsInteger = false`）。
     */
    private fun parseNetLong(s: String?): Long? {
        if (s == null) return null
        return s.trim().toLongOrNull()
    }

    /** `HtmlManager.ParseParam4IntNum`（HtmlManager.cs:328-368）：1..4 段颜色，逐段 `Trim()` 后送 `stringToColorInt32`。 */
    private fun checkColorList(value: String) {
        val parts = value.split(',')
        if (parts.size < 1 || parts.size > 4) throw CodeEE(msgCanNotInterpretAttr(value))
        for (part in parts) checkColor(part.trim())
    }
}

// ---------------------------------------------------------------------------
// MixedNum（Utils.cs:15-99 的移植子集）
// ---------------------------------------------------------------------------

/**
 * `Utils.MixedNum`（Utils.cs:15-99）的取值子集：只要「原值 + 是否 px」两件事。
 *
 * 两个文案出口不能混用（原版就是两条不同的代码路径）：
 * - [builderString]：`Utils.MixedNum.BuilderString`（Utils.cs:87-98），非 px 值按
 *   `num * FontSize / 100` 归一成像素。`Utils.AddTagMixedNumArg`（Utils.cs:364-371）与
 *   `<img>` 的重建文案（ConsoleImagePart.cs:55-57）走这条。
 * - `<shape>` 的 param 文案**不走** [builderString]：`ConsoleShapePart.CreateShape`
 *   （ConsoleShapePart.cs:20-31）就地拼 `num`（isPx 追 "px"）、不归一，故
 *   `<shape param='50'>` 的 AltText 是 `param='50'`；该拼接写在 [openShape] 里，
 *   本类不再包第二个 helper（同一处只能留一套口径）。
 */
private class MixedNum(val num: Int, val isPx: Boolean) {
    fun builderString(fontSize: Long): String =
        if (isPx) "${num}px" else (num * fontSize / 100).toString()

    /** `ConsoleShapePart.cs:20-31`：`param[i].num.ToString()` + isPx 时追加 `px`。 */
    fun shapeParamString(): String = if (isPx) "${num}px" else num.toString()
}

// ---------------------------------------------------------------------------
// 折行用的组内段
// ---------------------------------------------------------------------------

/**
 * 一条按钮组（原版 `ConsoleButtonString`）内的一个**段**（`AConsoleDisplayNode`）。
 *
 * 只有两种，判据就是原版的 `CanDivide`：
 * - 文本段（`ConsoleStyledString`，CanDivide = true，ConsoleStyledString.cs:15）带 [text]，
 *   折行时可以在**码元**中间切开（PrintStringBuffer.cs:453-496 的二分）；
 * - 图元段（`ConsoleImagePart` :35 / `ConsoleShapePart` :10 / `ConsoleDivPart`，
 *   CanDivide = false）只有宽度、不可拆，[text] = null。
 *
 * [widthPx] 的单位与注入的 `measure` 一致（**像素**），也就是 `HTML_STRINGLEN(html, 1)` 的单位
 * （`HtmlLength` 累加的就是 `ConsoleButtonString.Width`，HtmlManager.cs:176-181）。
 * [textLength] 取 **UTF-16 码元数**（原版 `AConsoleDisplayNode.Text.Length`，
 * 它是 `getDivideIndex` 的 `num2` 与 `DivideAt` 的切点单位）。图元段里只有
 * **按文案上屏**的那两种才非 0：缺图的 `ConsoleImagePart`（Text 被置成 AltText，
 * ConsoleImagePart.cs:41 与 :63）与 `ConsoleErrorShapePart`（:12）；
 * `space` / `rect` 的 `Text` 是空串（ConsoleSpacePart.cs:10、ConsoleRectangleShapePart.cs:25）⇒ 0。
 * 像素换算不在本类另建一套：生产侧的 `measure` = `HtmlMethods.approxTextWidth`，
 * 它的全角/半角名单唯一取自 [com.eratw.port.core.host.CellWidth]
 * （装载期 `GETLINESTR` 也用同一把尺）；本类只是把它的读数按组攒起来。
 */
private class HtmlGroupNode private constructor(
    val text: String?,
    val widthPx: Long,
    val textLength: Int,
    /** 文本段量宽时的样式快照（FN-04）；折行拆分前缀要用同一份样式重量。 */
    val style: HtmlPrintStyle? = null,
) {
    companion object {
        fun text(s: String, widthPx: Long, style: HtmlPrintStyle? = null): HtmlGroupNode =
            HtmlGroupNode(s, widthPx, s.length, style)

        /**
         * 不可拆、且屏上不占文案的图元段（`space` / `rect` / 有图有宽度的 `img`）；
         * `<div>` 节点也走这里（`element(0)`，ConsoleDivPart.cs:217-219 空宽口径）。
         */
        fun element(widthPx: Long): HtmlGroupNode = HtmlGroupNode(null, widthPx, 0)

        /** 不可拆、但**按整串文案上屏**的图元段（缺图 `img`、错误 `shape`）：宽来自文本度量。 */
        fun renderedAsText(altText: String, widthPx: Long): HtmlGroupNode =
            HtmlGroupNode(null, widthPx, altText.length)
    }
}

// ---------------------------------------------------------------------------
// div 子解析帧
// ---------------------------------------------------------------------------

/**
 * `<div>` 打开时的父状态快照（`HtmlManager.cs:606-648` 的递归 + `HtmlParentInfo` :153-162）。
 *
 * 原版递归调用带过去的是**一份新的** `HtmlAnalzeState`，只复制 SubDivisionWidth /
 * CurrentDivTag / StartingSubDivision / FlagClearButton / FlagClearButtonTooltip（:537-544），
 * 其余（LineHead、FontStyle、FonttagList、Alignment、FlagP、FlagNobr、按钮）全部重置。
 * 本类用「保存父值 → 清空 → `</div>` 时回填」等价实现，故这里存的是要**恢复**的那份。
 * [savedMeasuring] 也在这份快照里：量宽走的是父解析的第一显示行，而 `FlagBr`
 * （:590）属于子解析自己的 state，不能泄漏到父行。
 */
private class DivFrame(
    val savedRuns: ArrayList<HtmlPrintRun>,
    val rowMark: Int,
    val savedMeasuring: Boolean,
    val savedGroup: ArrayList<HtmlGroupNode>,
    val savedSubPixel: Double,
    val savedStyleMask: Int,
    val savedFontStack: ArrayList<HtmlPrintStyle>,
    val savedLineHead: Boolean,
    val savedButton: HtmlPrintButton?,
    val savedButtonIsTag: Boolean,
    val savedAlign: Int,
    val savedFlagP: Boolean,
    val savedFlagPClosed: Boolean,
    val savedFlagNobr: Boolean,
    val savedFlagNobrClosed: Boolean,
    val savedPosLocked: Boolean,
    val savedFlagClearButton: Boolean,
    val savedFlagClearButtonTooltip: Boolean,
)

// ---------------------------------------------------------------------------
// 错误文案（lang.emuera-zhs.xml:792-822；{0}/{1} 已按调用点填好）
// ---------------------------------------------------------------------------

private const val STYLE_BOLD = 1
private const val STYLE_ITALIC = 2
private const val STYLE_UNDERLINE = 4
private const val STYLE_STRIKE = 8

private const val ALIGN_LEFT = 0
private const val ALIGN_CENTER = 1
private const val ALIGN_RIGHT = 2

/**
 * [HtmlStrictParser.drawableWidthPx] 的缺省值 = **PC 基准环境**的可绘制宽度（像素）。
 *
 * `DrawableWidth = WindowX - max(2, FontSize / 6)`（Config.cs:461-466，
 * `DrawingParam_ShapePositionShift` = `max(2, FontSize/6)`），基准语料
 * `emuera.config:12 WINDOW WIDTH:1800` + `:22 FONT SIZE:18` ⇒ 1800 - 3 = 1797。
 * 同一读数在 `fixtures/golden/baseline/pc-golden-root.txt:253`（199 个 `=` 填满一行）
 * 与 `core/config/EffectiveConfig.kt:115-116` 都对得上（199 格 × 9px = 1791 <= 1797 < 1800）。
 * 真机由宿主接 `EffectiveConfig.drawableWidth`，见该参数的 KDoc。
 */
private const val DEFAULT_DRAWABLE_WIDTH_PX = 1797L

/** `Error.TextAfterP`（:792） */
private const val ERR_TEXT_AFTER_P = "</p>之后没有文字"

/** `Error.TextAfterNobr`（:793） */
private const val ERR_TEXT_AFTER_NOBR = "</nobr>后没有文字"

/** `Error.NotFoundCloseTag`（:794） */
private const val ERR_COMMENT_NOT_CLOSED = "找不到注释的结束标签\"-->\""

/** `Error.NotFoundTerminateTag`（:795） */
private const val ERR_NO_TAG_END = "找不到标签的结束符'>'"

/** `Error.TagIsNotClosed`（:796） */
private const val ERR_TAG_NOT_CLOSED = "存在没有结尾的标签"

/** `Error.CanNotUsePosWithoutNobr`（:797） */
private const val ERR_POS_WITHOUT_NOBR = "设定了<nobr>的行不能使用pos属性"

/** `Error.CanOnlyUsePosAssignmentLR`（:798） */
private const val ERR_POS_NOT_LEFT = "align不能在非left的行使用pos属性"

/** `Error.NestedButtonTag`（:815） */
private const val ERR_NESTED_BUTTON = "<button>或者<nonbutton>嵌套了自身"

/** `Error.RequireColorCode`（:819） */
private const val ERR_COLOR_REQUIRED = "需要表示颜色的单词或者#RRGGBB值"

/** `Error.TransparentUnsupported`（:450） */
private const val ERR_TRANSPARENT = "不能将透明(Transparent)指定为颜色"

/** `Error.HtmlTagError`（:818）；`{0}` 是整条待解析串（CharStream.RowString） */
private fun msgTagError(html: String): String = "在解析html字符串\"$html\"的标签时发生错误"

/** `Error.UnexpectedCloseTag`（:803） */
private fun msgUnexpectedClose(name: String): String = "</$name>之前没有<$name>"

/** `Error.CanNotInterpretCloseTag`（:804） */
private fun msgCanNotInterpretClose(name: String): String = "无法解析的结束标签</$name>"

/** `Error.AttributeSetToTag`（:805） */
private fun msgAttrOnTag(name: String): String = "<$name>标签设置了属性"

/** `Error.DuplicateTag`（:806） */
private fun msgDuplicateTag(name: String): String = "<$name>标签重复使用"

/** `Error.TagIsNotBegin`（:807） */
private fun msgTagNotBegin(name: String): String = "<$name>被使用在行首以外的位置"

/** `Error.TagHasNotAttribute`（:808） */
private fun msgTagHasNoAttr(name: String): String = "<$name>标签没有设置属性"

/** `Error.CanNotInterpretPAttribute`（:809） */
private fun msgCanNotInterpretPAttr(name: String): String = "<p>标签的属性名${name}无法解析"

/** `Error.CanNotInterpretAttribute`（:810） */
private fun msgCanNotInterpretAttr(value: String): String = "无法解析属性值$value"

/** `Error.DuplicateAttribute`（:811） */
private fun msgDuplicateAttr(tag: String, name: String): String = "<$tag>标签的${name}属性重复指定"

/** `Error.AttributeCanNotInterpretNum`（:812） */
private fun msgAttrNotNum(tag: String, name: String): String = "<$tag>标签的${name}属性的属性值无法被解析为数值"

/** `Error.CanNotInterpretAttributeName`（:813） */
private fun msgCanNotInterpretAttrName(tag: String, name: String): String = "<$tag>标签的属性名${name}无法解析"

/** `Error.NotSetAttribute`（:814） */
private fun msgNotSetAttr(tag: String, name: String): String = "<$tag>标签没有设定${name}属性"

/** `Error.NestedTag`（:816） */
private fun msgNestedTag(name: String): String = "<$name>嵌套了自身"

/** `Error.ClearbuttonAttributeCanNotInterpretNum`（:817） */
private fun msgClearButtonValue(tag: String, name: String, value: String): String =
    "无法解析<$tag>标签的${name}属性的值\u201C$value\u201D"

/** `Error.InvalidColorName`（:451）；原版 :1718 抛的是**未 Format** 的模板，`{0}` 原样保留 */
private const val ERR_INVALID_COLOR_NAME_RAW = "指定的颜色名\"{0}\"无效"

/** `Error.InvalidColorName2`（:822） */
private fun msgInvalidColorName2(value: String): String =
    "所指定的颜色名\"$value\"无效(用16进制数表示颜色时须在数值前添加#符号)"

/** `Error.CanNotInterpretNumValue`（:821） */
private fun msgBadNumValue(value: String): String = "${value}无法被解析为数值"
