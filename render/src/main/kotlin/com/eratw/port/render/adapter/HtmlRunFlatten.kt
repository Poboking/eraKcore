package com.eratw.port.render.adapter

import com.eratw.port.core.fn.ColorNames
import com.eratw.port.core.host.Alignment
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics

/**
 * HTML 段的**宽容**展平（M10-GFX）：把 `HTML_PRINT` 原文里内联的 `<img>` / `<shape>`
 * 变成显示列表的图形段，同时把标签标记从可见文本里剥掉。
 *
 * ## 为什么这一层必须存在
 *
 * 本作的立绘与标题图**不是** `PRINT_IMG` 画的，是 `HTML_PRINT` 画的：
 * `ERB/TITLE.ERB:59` 的标题图 `HTML_PRINT "<p align='center'><img src='TW_title001'>"`、
 * `ERB/BODY_INFO/GET_BODY_SETS.ERB:117` 的角色图 `<img src='bodyGraph_X' width=... height=...>`
 * （语料里 `<img` 共 51 处）。core 的 `LineBufferConsoleSink.printHtml` 明确不解析标签、
 * 只把原文存成一个 [com.eratw.port.core.host.LineBufferConsoleSink.Kind.HTML] 段
 * （其 KDoc：「行体 HTML 的排版归 M4」），适配层又约定「原样搬运、不做任何再解析」
 * （见 `ConsoleAdapter` 类文档）。于是真机上屏的是**整串 HTML 源码**，图一张都没有。
 * 本类就是「M4 那一步」里**只关乎图形**的最小落点。
 *
 * ## 与 `html/MiniHtml` 的分工
 *
 * `MiniHtml` 是原版 `HtmlManager.tagAnalyze`（spec: `HtmlManager.cs:951-1678`）的严格复刻，
 * 契约明确不覆盖 `button` / `nonbutton` / `div` / `shape`（命中即抛 UNSUPPORTED_TAG，
 * 见其类文档），而本作语料里 `<button` 196 处、`<div>` / `<shape>` 44 处 ——
 * 直接拿它做生产排版会在绝大多数含按钮的行上抛异常。所以这里用**宽容**口径：
 * 认识的标签照语义处理，不认识的一律「丢掉标记本身、保留其间文本」，绝不抛。
 * 脚本可见行为不受影响（`HTML_STRINGLEN` / `HTML_ESCAPE` 等仍走 core 与 `MiniHtml` 那两条），
 * 变的只有用户可见的屏上内容。
 *
 * ## 语义出处（逐条）
 *
 * - 图形段是**与文本同游标的一段**，不是覆盖层（spec: `EmueraConsole.cs:3192-3200`）；
 * - `<img>` 尺寸：`height` 缺省 = `Config.FontSize`；带 `px` 直接用像素，否则是
 *   `FontSize * num / 100`；`width` 缺省时按基图纵横比 `DestBaseSize.Width * height /
 *   DestBaseSize.Height`（spec: `ConsoleImagePart.cs:66-80`）；`ypos` 同口径（`:81`）；
 * - sprite 查不到时原版把重建出来的 `<img ...>` 标签当文本画（spec: `ConsoleImagePart.cs:60-64`
 *   的 `base.Text = base.AltText`）—— 本类的「解析不出尺寸」同样退化成原文文本；
 * - `<shape type='space' param='w'>` 与 `<shape type='rect' param='...'>` 的
 *   1 参 / 4 参分支与百分比换算（spec: `ConsoleShapePart.cs:50-69`），
 *   色 = `color` 属性、缺省 `userStyle.Color`（spec: `ConsoleShapePart.cs:12` 的入参）；
 *   落不成图形段的 shape 接回重建标签文案当文本（`ConsoleErrorShapePart`，
 *   spec: `ConsoleShapePart.cs:71-74` + `ConsoleErrorShapePart.cs:12-26`）；
 * - 样式位是异或/状态机语义，本类只做宽容近似：`<b>` 置位、`</b>` 清位；
 * - `<font bcolor=...>` 改的是**焦点态字色**（[TextRun.focusFore]，原文 `ButtonColor`）：
 *   `HtmlManager.cs:131-149` 的 `GetSS` 把帧里的 `BColor` 交给 `StringStyle.ButtonColor`，
 *   `ConsoleStyledString.cs:105-111` 在 `isFocus` 时用它画字；它**不是**背景色，
 *   所以不写进 [TextRun.back]（`back` 是控制台背景色的行快照）；
 * - `</font>` 整帧出栈还原 fore/focusFore/fontName（原版帧是
 *   `HtmlAnalzeStateFontTag { Color, BColor, FontName }` 的整体出栈
 *   （`HtmlManager.cs:1655-1670` 缺属性继承外层、`:998-1004` 的 `RemoveAt(Count - 1)`）；
 *   **styleMask 不归 font 管**：原版 FontStyle 是独立异或字段，font 标签从不动它
 *   （HtmlManager.cs:964-980）——`</font>` 不得把 `<font>` 里开的 `<b>` 一起清掉。
 * - `&nbsp;` 还原成半角空格 U+0020（spec: `HtmlManager.cs:783-785`，`MiniHtml.unescape` 同口径）；
 * - 颜色名/十六进制：命名色（core 的 [ColorNames]）、`color='#RRGGBB'`（含 `#0x` 前缀）、
 *   `color='N'`（数值 = RGB 整数）都吃，解析不出就保持外层色
 *   （spec: `HtmlManager.cs:1680-1725` 的三条分支，本类不抛；逐条口径见 [colorOf]）。
 *   **注意**：原版对不带 `#` 的值走 `Color.FromName`，「能按十六进制解析」的一律抛
 *   InvalidColorName2（`HtmlManager.cs:1701-1721`）—— 纯数字（如 16711680）原版**必报**
 *   CodeEE，宽容层留十进制支只为不中断显示；语料 0 处（2026-10-26 fixcore 审查 render-C2
 *   更正本注释此前「数值都吃 = 官方口径」的误述；严格路 MiniHtml.stringToColor 与原版一致）。
 * - `<p align>` / `<nobr>`：解析进行级状态（HtmlManager.cs:1096-1141）。
 *   **只对直印 HTML 行生效**（段快照 `htmlOption == false`，即 `HTML_PRINT` 未带 toPrintBuffer
 *   参数的形态）：原版 `html2DisplayLine` 每串 HTML 新建一份状态、默认 LEFT
 *   （HtmlManager.cs:536 的 `new HtmlAnalzeState()`），产出线逐条 `SetAlignment(state.Alignment)`
 *   （HtmlManager.cs:702-710），而 `SetAlignment` 的一次性守卫（ConsoleDisplayLine.cs:52-56）
 *   让 `addDisplayLine` 里的控制台 ALIGNMENT（EmueraConsole.cs:2937-2944）对这类行**失效** ——
 *   直印 HTML 行永远按自己的 `<p align>`（缺省 LEFT）对齐，不吃 ALIGNMENT 语句的活状态。
 *   toPrintBuffer 形态走 `Html2ButtonList`，在 `buttonsOutput != null` 处提前返回
 *   （HtmlManager.cs:679-682），对齐与 nobr 都**不生效**（按钮并入打印缓冲、随普通 Flush 折行），
 *   本类对 `htmlOption == true` 的段同样两者都不应用。
 *
 * ## 占宽
 *
 * 图形段的文本负载填**等量半角空格**（不是空串）：`grid/LineFolder` 与 `ConsoleAdapter`
 * 的宽度权威是 `Grid.cells(run.text)`，填空格才能让折行、`<p align>` 居中、脏矩形
 * 都按图的实际占宽走；绘制侧走 [InlineGraphic] 分支，不会把这些空格铺出来。
 * 原版对应的口径是「节点自带 `Width`、`SetWidth` 不再测量」（spec: `ConsoleImagePart.cs:104-115`）。
 *
 * @property metrics 像素 ↔ 半角格换算器（必须与 `CanvasConsoleView` 同一份，
 *   否则占宽与实际绘制的列位置会分叉）
 * @property sizeSource 基图尺寸来源（`<img>` 没写 `width` 时要按纵横比推宽，
 *   spec: `ConsoleImagePart.cs:67-71`）；null 时缺宽的图片按 `height` 见方占位
 * @property scriptFontPixelSize **脚本 `px` 的基准字号**（= 引擎生效 `Config.FontSize`）。
 *   `px` 形态一律经 [CellMetrics.scriptPixels] 折算成设备像素，与百分比形态共用一把尺；
 *   缺省 0 = 不折算（恒等），headless 与既有用例逐值不变。口径与踩坑见该函数的 KDoc
 */
class HtmlRunFlatten(
    private val metrics: CellMetrics,
    private val sizeSource: ImageSizeSource? = null,
    private val scriptFontPixelSize: Int = 0,
) {

    /** 资源基图的目标尺寸（`ASprite.DestBaseSize`，spec: `ASpriteSingle.cs` / `AppContents.cs:80-88`）。 */
    fun interface ImageSizeSource {
        /** 返回 `width * 1 shl 16 or height` 的打包整数；查不到返回 null。 */
        fun destSize(resource: String): Int?
    }

    private val out = ArrayList<TextRun>()
    private val text = StringBuilder()

    /** 本行是否**切出过图形段**：决定收尾那条文字段的 kind（见 [flush]）。 */
    private var graphicEmitted = false

    private var fore = DEFAULT_FORE
    private var back = DEFAULT_BACK
    private var focusFore = TextRun.DEFAULT_FOCUS_COLOR
    private var styleMask = 0
    private var fontName = DEFAULT_FONT

    /**
     * 当前处在哪个 HTML `<button value=N>` 之内：null = 普通文本。
     *
     * 原版 HTML 按钮是可点节点（`HtmlManager.cs` 的 `<button>` 分支把文本装进
     * `ConsoleButtonString`，命中判定走 `EmueraConsole.cs:2358-2361`）；本轮补的是
     * **生产链里缺失的那一半** —— 展平器过去把标签丢掉、文本降级成 TEXT，于是
     * `DisplayRow.runs` 里没有 `kind=BUTTON` 的段，`ButtonLayer.attach` 自然建不出
     * 命中区，真机上 `<button>` 选项只能在右侧栏点。
     *
     * 这里只记「按钮内文本」这一件事：标签本身的样式（按下态描边）仍由绘制侧按
     * [RunKind.BUTTON] 决定；值原样保留为字符串（`value` 属性是字符串域，
     * 回车提交时再按等待类型解析，见 `ButtonLayer.selectedString`）。
     */
    private var buttonValue: Any? = null

    /** 当前按钮的世代号（[TextRun.buttonId]）：同一个 `<button>` 拆出的多段会并成一条命中区。 */
    private var buttonId = -1

    /**
     * 当前是否处在 `<clearbutton>` 区间（原版 `HtmlAnalzeState.FlagClearButton`，
     * `HtmlManager.cs:1549-1551`）：区间内的 `<button>` 一律降级成**非按钮** ——
     * 正文照画、`IsButton = false`，`ConsoleButtonString` 不进可点命中。
     *
     * eraTW 的 CLEARLINE 重绘靠它作废上一屏的按钮：地图页的 `<button value='34'>34`
     * 在移动后重印为 `<clearbutton>…<button value='34'>34…</clearbutton>`。
     * 本仓 `LineBufferConsoleSink.splitHtmlLines` 已让这个区间逐行自含（闭合/重放），
     * 所以这里按串复位即可。
     */
    private var clearButton = false

    /**
     * 当前标签的 `title` 原文（[TextRun.tooltip]）：`<button>` / `<nonbutton>` 开标签时取值，
     * 对应闭标签与每串 HTML 收尾时清空。
     *
     * 原版对两种标签建的是**同一份**状态 `HtmlAnalzeStateButtonTag.ButtonTitle`
     * （`HtmlManager.cs:1514-1520` 取值，`:147` 的 `IsButton` 只管可点性），
     * 收尾时把 `Title` 一并赋给 `ConsoleButtonString`（`HtmlManager.cs:841-847`）——
     * 所以不可点的 `<nonbutton title='…'>`（本作 `qol_update.ERB:145` 的过滤提示）
     * 在 PC 上悬停同样弹提示。本类只把原文挂到 run 上，可点身份仍由 [buttonValue] 决定。
     * PC 的 ToolTip 取值见 `EmueraConsole.cs:1891-1917`（`:1909` 把 `<br>` 换成换行）。
     */
    private var tagTitle: String? = null

    /** 4 参 `<shape type='rect'>` 的像素级条带缓冲（见 [appendRectBand] / [flushRectBands]）。 */
    private val rectBands = ArrayList<RectBand>()

    /** 按钮世代号发号器（每遇到一个开标签 +1）。 */
    private var buttonSeq = 0

    /**
     * `<font>` 帧：开标签时压栈、闭标签整帧还原 fore/focusFore/fontName。
     *
     * 对应原版 `HtmlAnalzeStateFontTag`（`Color` / `BColor` / `FontName` 三字段，
     * `HtmlManager.cs:1655-1670` 的缺属性继承外层）+ `FonttagList.RemoveAt` 整体出栈
     * （`:998-1004`）。**styleMask 不入栈**：原版 FontStyle 是独立异或字段、font 标签
     * 从不动它（HtmlManager.cs:964-980），`<font>` 里开的 `<b>` 要活到自己的 `</b>`。
     */
    private class FontFrame(val fore: Int, val back: Int, val focusFore: Int, val fontName: String)

    private val fontStack = ArrayList<FontFrame>()

    /**
     * 最近一次 [flatten] 解析出的 `<p align>`（无 `<p>` 或属性不认时 null）。
     * 仅当被展平的段是直印 HTML（`htmlOption == false`）时才被 [flattenLine] 采纳
     * —— 语义出处见类文档「`<p align>` / `<nobr>`」条。
     */
    private var parsedAlign: Alignment? = null

    /** 最近一次 [flatten] 是否见到 `<nobr>`（HtmlManager.cs:1100-1104 置位；闭合不解）。 */
    private var parsedNobr = false

    /**
     * [flattenAll] 对**直印 HTML 段**（`htmlOption == false`）收集到的行级状态：
     * [directAlign] 取自该段解析出的 `<p align>`（没写就是 null，消费侧落成 LEFT）；
     * [directNobr] 是该段是否见到 `<nobr>`。toPrintBuffer 段不参与收集
     * （HtmlManager.cs:679-682 的提前返回，见类文档）。
     */
    private var directAlign: Alignment? = null
    private var directNobr = false

    /** 展平一条 run；非 HTML 段原样返回（一条都不动）。 */
    fun flatten(run: TextRun): List<TextRun> {
        if (run.kind != RunKind.HTML || run.text.indexOf('<') < 0 && run.text.indexOf('&') < 0) {
            return listOf(run)
        }
        out.clear()
        text.setLength(0)
        graphicEmitted = false
        fore = run.fore
        back = run.back
        focusFore = run.focusFore
        styleMask = run.styleMask
        fontName = run.fontName
        fontStack.clear()
        parsedAlign = null
        parsedNobr = false
        // 按钮态逐串 HTML 复位：原版每串 HTML 都新建一份 `HtmlAnalzeState`
        // （spec: `HtmlManager.cs:536`），串尾未闭合的 `<button>` 不影响下一串。
        // 不复位则上一行漏下来的 `buttonValue` 会把下一行**按钮区间外**的
        // 文本/图形段折成可点（违背「只有源按钮区间才带可点身份」，
        // 该身份是 `ButtonLayer` 入表的唯一凭据）。[buttonSeq] 不重置：
        // 世代号只需逐开标签唯一，跨串单调递增不影响同源合并判定。
        buttonValue = null
        buttonId = -1
        tagTitle = null
        clearButton = false
        Scanner(run.text).scan()
        flush(run)
        if (out.isEmpty()) {
            // 全串都是标签、剥完一个字符都不剩：交一条同列宽的空段，
            // 免得整条显示行变成「没有任何命令」而躲过脏矩形（残影）。
            return listOf(run.copy(text = ""))
        }
        return ArrayList(out)
    }

    /**
     * 展平一整条显示行：逐 run 展平之外，还把**直印 HTML 段**（`htmlOption == false`）
     * 解析出的 `<p align>` / `<nobr>` 落到行的 `align` / `noBreak` 上。
     *
     * 直印行的对齐**无条件**按解析结果取（没写 `<p align>` 就是 LEFT）：
     * 原版对这类行逐条 `SetAlignment(state.Alignment)`（HtmlManager.cs:702-710，
     * state 缺省 LEFT），一次守卫（ConsoleDisplayLine.cs:52-56）让它吃掉
     * `addDisplayLine` 的控制台 ALIGNMENT（EmueraConsole.cs:2937-2944）。
     * 行没有任何直印 HTML 段、或解析没带来任何变化时原行实例原样返回
     * （调用方靠实例同一性省掉重挂）。
     */
    fun flattenLine(line: RenderLine): RenderLine {
        val runs = flattenAll(line.runs)
        val direct = line.runs.any { it.kind == RunKind.HTML && !it.htmlOption }
        val align = if (direct) (directAlign ?: Alignment.LEFT) else line.align
        val noBreak = if (direct && directNobr) true else line.noBreak
        if (runs === line.runs && align == line.align && noBreak == line.noBreak) {
            return line
        }
        return line.copy(
            runs = if (runs === line.runs) line.runs else runs,
            align = align,
            noBreak = noBreak,
        )
    }

    /** 展平一批 runs（顺序保持；非 HTML 段零拷贝原样进表）。 */
    fun flattenAll(runs: List<TextRun>): List<TextRun> {
        directAlign = null
        directNobr = false
        var touched = false
        for (r in runs) {
            if (r.kind == RunKind.HTML && (r.text.indexOf('<') >= 0 || r.text.indexOf('&') >= 0)) {
                touched = true
                break
            }
        }
        if (!touched) return runs
        val result = ArrayList<TextRun>(runs.size)
        for (r in runs) {
            if (r.kind == RunKind.HTML && (r.text.indexOf('<') >= 0 || r.text.indexOf('&') >= 0)) {
                result.addAll(flatten(r))
                if (!r.htmlOption) {
                    // 直印段：把本轮解析出的行级状态收集上去（多段时后段覆盖前段，
                    // 与原版「一串 HTML 一份状态」的逐串产出同序）。
                    directAlign = parsedAlign
                    directNobr = parsedNobr
                }
            } else {
                result.add(r)
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private inner class Scanner(val src: String) {
        var pos = 0

        fun scan() {
            while (pos < src.length) {
                val ch = src[pos]
                when {
                    ch == '<' -> tag()
                    ch == '&' -> entity()
                    else -> {
                        text.append(ch)
                        pos++
                    }
                }
            }
        }

        /** `<` 开头的一切：注释、开标签、闭标签；结构不合法时按原文吞掉这个 `<`（宽容）。 */
        private fun tag() {
            val start = pos
            if (src.startsWith("<!--", start)) {
                val end = src.indexOf("-->", start + 4)
                pos = if (end < 0) src.length else end + 3
                return
            }
            val close = start + 1 < src.length && src[start + 1] == '/'
            var i = if (close) start + 2 else start + 1
            while (i < src.length && !src[i].isWhitespace() && src[i] != '>' && src[i] != '/') i++
            if (i == start + (if (close) 2 else 1)) {
                text.append('<')
                pos = start + 1
                return
            }
            val name = src.substring(if (close) start + 2 else start + 1, i)
            val end = findTagEnd(src, i)
            if (end < 0) {
                text.append('<')
                pos = start + 1
                return
            }
            val attrs = parseAttrs(src.substring(i, end))
            pos = end + 1
            if (close) {
                closeTag(name)
            } else {
                openTag(name, attrs)
            }
        }

        /**
         * 从 [from] 起找标签的结束 `>`：**引号内的 `>` 不算**。
         *
         * 语料剧本书选择页的真形态是 `title='初始剧本<br>内容量适中…'` —— 属性值里带
         * `<br>`。按裸 `indexOf('>')` 会在 `title` 里那个 `>` 截断，剩余属性文本
         * （`内容量适中…'>`）会被当成标签间正文画到屏上，同时污染按钮标签。
         * 原版 `HtmlManager` 的属性扫描按引号成对推进（`HtmlManager.cs:1010-1050`），
         * 这里对齐同一口径。
         */
        private fun findTagEnd(src: String, from: Int): Int {
            var i = from
            var quote = '\u0000'
            while (i < src.length) {
                val c = src[i]
                if (quote != '\u0000') {
                    if (c == quote) quote = '\u0000'
                } else if (c == '\'' || c == '"') {
                    quote = c
                } else if (c == '>') {
                    return i
                }
                i++
            }
            return -1
        }

        private fun entity() {
            val semi = src.indexOf(';', pos + 1)
            if (semi < 0 || semi <= pos + 1) {
                text.append('&')
                pos++
                return
            }
            val body = src.substring(pos + 1, semi)
            val resolved = resolveEntity(body)
            if (resolved == null) {
                // 认不出的实体按原文留着（原版是抛 CodeEE，这里宽容：屏上多几个字符
                // 比整行消失好，脚本可见的 HTML_UNESCAPE 仍归 core 那条实现）。
                text.append('&').append(body).append(';')
                pos = semi + 1
                return
            }
            text.append(resolved)
            pos = semi + 1
        }
    }

    private fun resolveEntity(body: String): String? {
        when (body.lowercase()) {
            "lt" -> return "<"
            "gt" -> return ">"
            "amp" -> return "&"
            "quot" -> return "\""
            "apos" -> return "'"
            "nbsp" -> return " " // spec: HtmlManager.cs:783-785 落 U+0020 而不是 U+00A0
        }
        if (body.isEmpty() || body[0] != '#') return null
        val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
        val digits = if (hex) body.substring(2) else body.substring(1)
        val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
        if (code < 0 || code > 0xFFFF) return null
        return code.toChar().toString()
    }

    private fun parseAttrs(body: String): Map<String, String> {
        val map = HashMap<String, String>(4)
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == '/')) i++
            var nameStart = i
            while (i < body.length && body[i] != '=' && !body[i].isWhitespace()) i++
            if (i == nameStart) {
                i++
                continue
            }
            val name = body.substring(nameStart, i).lowercase()
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != '=') {
                map[name] = ""
                continue
            }
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) {
                map[name] = ""
                break
            }
            val quote = body[i]
            if (quote == '\'' || quote == '"') {
                i++
                nameStart = i
                while (i < body.length && body[i] != quote) i++
                map[name] = body.substring(nameStart, i)
                if (i < body.length) i++
            } else {
                nameStart = i
                while (i < body.length && !body[i].isWhitespace()) i++
                map[name] = body.substring(nameStart, i)
            }
        }
        return map
    }

    private fun openTag(name: String, attrs: Map<String, String>) {
        // 改样式之前先把攒着的文字落成一段：原版扫到 `<` 就先用**当前**样式快照建一个
        // `ConsoleStyledString` 节点，之后才轮到 `tagAnalyze` 去改状态
        // （spec: `HtmlManager.cs:569-575` 的 `new ConsoleStyledString(str3, htmlAnalzeState.GetSS())`
        // 对 `:596` 的 `tagAnalyze(...)`；样式快照取自 `:128` 的 `GetSS()`）。
        // 于是样式节点各自成段：`<b>粗</b>平` 在原版是两个节点，移植侧也必须交两条 run，
        // 否则加粗与后面的平字会被并进同一段、屏上两种样式都丢（`flushCurrentText()` 在
        // 缓冲区为空时是 no-op，所以这里不需要额外的守卫）。
        // `br` / `p` / `div` / `button` 一类不改样式的标签走 `else`，不 flush（不改状态就不该断段）。
        when (name) {
            "b" -> { flushCurrentText(); styleMask = styleMask or Grid.STYLE_BOLD }
            "i" -> { flushCurrentText(); styleMask = styleMask or Grid.STYLE_ITALIC }
            "s" -> { flushCurrentText(); styleMask = styleMask or Grid.STYLE_STRIKE }
            "u" -> { flushCurrentText(); styleMask = styleMask or Grid.STYLE_UNDERLINE }
            "font" -> {
                // 开标签的样式位叠加与 `font` 压栈（spec: `HtmlManager.cs:1058-1090` 的
                // `state.FontStyle |= fontStyle`、`:1601-1640` 的 `FonttagList.Add(...)`）。
                // 帧里存 fore/focusFore/fontName 三件：闭标签整帧还原（原版 `:998-1004`
                // 是 `RemoveAt(Count - 1)` 整体出栈）。styleMask 不进帧 —— font 标签在
                // 原版从不动 FontStyle。
                flushCurrentText()
                fontStack.add(FontFrame(fore, back, focusFore, fontName))
                attrs["color"]?.let { colorOf(it)?.let { c -> fore = c } }
                // `bcolor` 是焦点态字色（`StringStyle.ButtonColor`），不是背景色：
                // `HtmlManager.cs:131-149` 的 `GetSS` 直接把帧里的 `BColor` 交给 buttonColor。
                attrs["bcolor"]?.let { colorOf(it)?.let { c -> focusFore = c } }
                // `bgcolor` 是**背景色**（Emuera 的 `<font bgcolor=...>`）：原实现完全没解析，
                // 于是该段的 `back` 落在出厂 0x000000 或上一段的残留值上 —— 玩家侧看到的就是
                // 「某些字带了不该有的底色」（2026-10-24 负责人反馈的黄色底）。
                attrs["bgcolor"]?.let { colorOf(it)?.let { c -> back = c } }
                attrs["face"]?.takeIf { it.isNotEmpty() }?.let { fontName = it }
            }
            "img" -> emitImage(attrs)
            "shape" -> emitShape(attrs)
            // HTML 按钮：开标签后其间的文本产物落成 `kind=BUTTON` 的段（带值），
            // 由 `ButtonLayer.attach` 生成真实命中区（见 [buttonValue] 的类注释）。
            "button" -> {
                flushCurrentText()
                // `title` 是按钮的悬停提示（`HtmlManager.cs:1514-1520` 取值、`:847`
                // 随整条按钮打包；PC 由 `EmueraConsole.cs:1891-1917` 当 ToolTip 用）。
                // 安卓没有 hover，这份原文随 run 带下去，由长按取出显示。
                // 与 `value` 分开取：原版 `title` 的解析不受 `IsButton` 影响
                // （`HtmlManager.cs:1547` 只把 `value` 的有无写进 `IsButton`），
                // 所以没 `value` 的 `<button title>` 在 PC 上照样弹提示。
                tagTitle = attrs["title"]?.trim()?.takeIf { it.isNotEmpty() }
                val v = attrs["value"]
                if (v != null && !clearButton) {
                    // 纯数字值落成长整型：与 `PRINTBUTTON` 数值按钮同型（`ButtonLayer`
                    // 的 INT 闸只放行 Long，见 `selectedString`；原版 HTML 按钮在整数等待
                    // 里点得动，靠的就是这一点）。非数字值保持字符串，交给等待侧解析。
                    buttonValue = v.toLongOrNull() ?: v
                    buttonId = ++buttonSeq
                }
            }
            // `<clearbutton>` 打开：区间内的 `<button>` 降级成不可点文本
            // （HtmlManager.cs:1549-1551 的 `IsButton = false`），标题照旧保留。
            "clearbutton" -> clearButton = true
            // 不可点的高亮块：只吃 `title`（原版 `IsButton = false` 的同一份状态，
            // 提示照样落到 `ConsoleButtonString.Title`，HtmlManager.cs:841-847）。
            "nonbutton" -> {
                flushCurrentText()
                tagTitle = attrs["title"]?.trim()?.takeIf { it.isNotEmpty() }
            }
            // p / br / div / button / nonbutton / clearbutton 与任何未知标签：
            // 标记本身丢掉，其间文本保留（见类文档「与 MiniHtml 的分工」）。
            // 唯二的例外是 `<p align>` 与 `<nobr>` 的**行级状态**（不改样式、不断段，
            // 只把值记进 [parsedAlign] / [parsedNobr]，由 [flattenLine] 落到直印行的
            // align / noBreak 上；出处见类文档「`<p align>` / `<nobr>`」条）。
            "p" -> {
                val align = attrs["align"]?.lowercase()
                if (align != null) {
                    // 不认的取值原版抛 CodeEE（HtmlManager.cs:1138-1141），这里宽容为不记。
                    parsedAlign = when (align) {
                        "left" -> Alignment.LEFT
                        "center" -> Alignment.CENTER
                        "right" -> Alignment.RIGHT
                        else -> null
                    }
                }
            }
            "nobr" -> parsedNobr = true
            else -> {}
        }
    }

    private fun closeTag(name: String) {
        // 与 [openTag] 同一条理由：闭标签同样先断段，才改样式
        // （spec: `HtmlManager.cs:964-980` 的 `state.FontStyle ^= fontStyle`、
        //  `:998-1004` 的 `FonttagList.RemoveAt(末尾)`）。
        when (name) {
            "b" -> { flushCurrentText(); styleMask = styleMask and Grid.STYLE_BOLD.inv() }
            "i" -> { flushCurrentText(); styleMask = styleMask and Grid.STYLE_ITALIC.inv() }
            "s" -> { flushCurrentText(); styleMask = styleMask and Grid.STYLE_STRIKE.inv() }
            "u" -> { flushCurrentText(); styleMask = styleMask and Grid.STYLE_UNDERLINE.inv() }
            "font" -> {
                flushCurrentText()
                // 弹栈还原 fore/back/fontName 三件（HtmlManager.cs:998-1004 的 RemoveAt；
                // `GetSS` 只读栈顶一条的 FontName/Color/BColor，HtmlManager.cs:128-155）。
                // styleMask **不还原**：原版 font 标签从不动 FontStyle，`<font>` 里开的
                // `<b>` 要活到它自己的 `</b>` 才闭。
                val frame = fontStack.removeLastOrNull()
                if (frame != null) {
                    fore = frame.fore
                    back = frame.back
                    focusFore = frame.focusFore
                    fontName = frame.fontName
                }
            }
            // 按钮结束：先把按钮内最后一截文本按 BUTTON 落段，再退回普通文本态。
            "button" -> {
                flushCurrentText()
                buttonValue = null
                buttonId = -1
                tagTitle = null
            }
            // 不可点块结束：先落最后一截文本，再退回普通文本态。
            "nonbutton" -> {
                flushCurrentText()
                tagTitle = null
            }
            // 作废区间结束：后续 `<button>` 恢复可点（HtmlManager.cs:1021-1027 只清标志）。
            "clearbutton" -> clearButton = false
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // 图形段
    // ------------------------------------------------------------------

    /**
     * `<img>`：尺寸换算逐条照 `ConsoleImagePart.cs:66-82`，缺宽时按纵横比推。
     *
     * **不做任何横向夹取**：原版 `destRect` 就是 `(0, top, Width, num)`（`ConsoleImagePart.cs:82`），
     * 宽度只由 `height` / `width` 两个属性与基图纵横比决定，与窗口宽无关。图比窗口宽时原版
     * 的处置是「整块排到下一行 + 右侧被绘制区裁掉」：`ConsoleImagePart.CanDivide => false`
     * （`:35`）让折行器不能切它，`PrintStringBuffer.ButtonsToDisplayLines` 见
     * `PointX + Width > DrawableWidth` 就换行（`PrintStringBuffer.cs:217-249`）。
     *
     * 移植侧曾经在这里按窗口宽夹过一次（只压宽不压高），那是为绕开「行模型等高、图片行
     * 无法自定义行高」的临时手段，代价是**画面上的横纵比与原图不一致**、且脚本给的
     * `width` 被静默改写。2026-10-26 按 ADR-005 去掉：超宽就按原版溢出裁切，需要「整块
     * 缩进屏」的场景由 `ImageBandMerge`（竖带合并，另有 `bandAvailWidthPx` 参数）负责，
     * 那是显式的合成动作，不再改写单张图的尺寸。
     */
    private fun emitImage(attrs: Map<String, String>) {
        val src = attrs["src"] ?: ""
        val height = mixedNum(attrs["height"])
        val width = mixedNum(attrs["width"])
        val ypos = mixedNum(attrs["ypos"])
        val lineHeight = metrics.fontPixelSize
        val heightPx = when {
            height == null || height.num == 0 -> lineHeight
            height.isPx -> scriptPx(height.num)
            else -> lineHeight * height.num / 100
        }
        val base = if (src.isEmpty()) null else sizeSource?.destSize(src)
        val widthPx = when {
            width != null && width.num != 0 ->
                if (width.isPx) scriptPx(width.num) else lineHeight * width.num / 100
            base != null -> {
                val bw = base ushr 16
                val bh = base and 0xFFFF
                if (bh > 0) bw * heightPx / bh else heightPx
            }
            else -> heightPx
        }
        val yOffsetPx = when {
            ypos == null -> 0
            ypos.isPx -> scriptPx(ypos.num)
            else -> ypos.num * lineHeight / 100
        }
        emitGraphic(
            InlineGraphic.Image(
                resource = src,
                widthPx = widthPx,
                heightPx = heightPx,
                yOffsetPx = yOffsetPx,
                widthCells = InlineGraphic.cellsFor(widthPx, metrics.cellPixelWidth),
                buttonResource = attrs["srcb"]?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /**
     * `<shape type='rect|space' param='...'>`：分支照 `ConsoleShapePart.cs:50-69`。
     *
     * 落不成图形段的 shape **不丢弃**：原版落到 `ConsoleErrorShapePart`（ConsoleShapePart.cs:71-74），
     * 它的 `Text`/`AltText` 是重建的标签文案、`DrawTo` 按普通文本画（ConsoleErrorShapePart.cs:12-26）、
     * `SetWidth` 按文本量宽（:28-37）——即玩家屏上会看到重建出来的 `<shape ...>` 原文。
     * 本层同样把重建文案当文本接回行内（样式取当前快照，宽容近似）。
     */
    private fun emitShape(attrs: Map<String, String>) {
        val type = (attrs["type"] ?: "").lowercase()
        val params = (attrs["param"] ?: "").split(',').map { mixedNum(it.trim()) }
        // <shape> 的 color 是 RGB 域（原版 GetColor 不带 alpha）：这里补成不透明 ARGB。
        // 少了这一句，drawRect 拿到 0x00RRGGBB 会画成**全透明** —— 真机「少女祈祷中」
        // 进度条只剩 [100%] 文本、彩色条消失就是这个 bug（2026-10-23 云真机实拍）。
        val color = 0xFF000000.toInt() or ((attrs["color"]?.let { colorOf(it) } ?: fore) and 0xFFFFFF)
        val lineHeight = metrics.fontPixelSize
        when (type) {
            "space" -> if (params.size == 1 && params[0] != null) {
                val p = params[0]!!
                val px = pixel(p, lineHeight)
                emitGraphic(InlineGraphic.Space(InlineGraphic.cellsFor(px, metrics.cellPixelWidth)))
                return
            }
            "rect" -> when {
                params.size == 1 && params[0] != null && params[0]!!.num > 0 -> {
                    val p = params[0]!!
                    val px = pixel(p, lineHeight)
                    emitGraphic(
                        InlineGraphic.Rect(
                            xOffsetPx = 0, yOffsetPx = 0, widthPx = px, heightPx = lineHeight,
                            colorArgb = color, widthCells = InlineGraphic.cellsFor(px, metrics.cellPixelWidth),
                        ),
                    )
                    return
                }
                params.size == 4 && params.all { it != null } -> {
                    // 4 参形态照 `Utils.MixedNum.ToPixelf`（spec: `Utils.cs:60-71`）+ `ConsoleShapePart.cs:61-68`：
                    // 判定是**浮点**矩形，`X >= 0 && Width > 0 && Height > 0` 就成立。
                    // 老实现走整数 `pixel()`，`1 * FontSize / 100` 被截成 0 ⇒ 1% 宽的分块全被判
                    // 「不可渲染」⇒ 落到 AltText、把 `<shape ...>` 原文画到屏上（2026-10-23 真机
                    // 「少女祈祷中」实拍：整屏裸标签）。
                    val x = pixelF(params[0]!!, lineHeight)
                    val y = pixelF(params[1]!!, lineHeight)
                    val w = pixelF(params[2]!!, lineHeight)
                    val h = pixelF(params[3]!!, lineHeight)
                    if (x >= 0f && w > 0f && h > 0f) {
                        appendRectBand(x, y, w, h, color)
                        return
                    }
                }
            }
        }
        emitShapeAltText(attrs)
    }

    /**
     * 不可渲染 shape 的 AltText 回落：接回重建的标签文案（`ConsoleErrorShapePart` 的
     * `Text = AltText = 重建文案`，ConsoleShapePart.cs:71-74 + ConsoleErrorShapePart.cs:12-26）。
     * 重建口径与 core 侧 [com.eratw.port.core.fn.HtmlStrictParser] 一致：type 小写、
     * param 逐项原始 num（px 项补后缀）、`", "` 分隔（ConsoleShapePart.cs:20-31）；
     * `color` 设过就写、`bcolor` 只在 != 缺省 `Config.FocusColor`（0xFFFF00）时写
     * （ConsoleShapePart.cs:33-44）。
     */
    private fun emitShapeAltText(attrs: Map<String, String>) {
        flushCurrentText()
        val sb = StringBuilder("<shape type='").append((attrs["type"] ?: "").lowercase())
        sb.append("' param='")
        val params = (attrs["param"] ?: "").split(',')
        params.forEachIndexed { i, raw ->
            val m = mixedNum(raw.trim())
            if (m == null) {
                sb.append(raw.trim())
            } else {
                sb.append(m.num)
                if (m.isPx) sb.append("px")
            }
            if (i < params.size - 1) sb.append(", ")
        }
        sb.append('\'')
        attrs["color"]?.let { colorOf(it)?.let { c -> sb.append(" color='").append(colorString(c)).append('\'') } }
        attrs["bcolor"]?.let { colorOf(it)?.takeIf { c -> c != 0xFFFF00 }?.let { c ->
            sb.append(" bcolor='").append(colorString(c)).append('\'')
        } }
        sb.append('>')
        out.add(TextRun(sb.toString(), fore, back, styleMask, fontName, RunKind.TEXT))
    }

    /** `HtmlManager.GetColorToString`（HtmlManager.cs:856-862）：`#RRGGBB` 六位大写。 */
    private fun colorString(rgb: Int): String {
        val hex = (rgb and 0xFFFFFF).toString(16).uppercase()
        return "#" + "0".repeat(6 - hex.length) + hex
    }

    private fun pixel(m: Mixed, base: Int): Int =
        if (m.isPx) scriptPx(m.num) else base * m.num / 100

    /** `Utils.MixedNum.ToPixelf`（spec: `Utils.cs:60-71`）：`px` 折成设备像素，否则按字号百分比算**浮点**。 */
    private fun pixelF(m: Mixed, base: Int): Float =
        if (m.isPx) scriptPx(m.num).toFloat() else base.toFloat() * m.num / 100f

    /**
     * 脚本 `px` → 设备像素（[CellMetrics.scriptPixels] 的本类入口）。
     *
     * 与百分比支必须同尺：语料里 `height='180px'`（`ERB/魔改内容/QOL_IMAGE.ERB:826`）与
     * `height='1000%'`（`ERB/グラフィック表示ライブラリ/グラフィック表示.ERB:213` 算出的
     * `180*100/18`）在 PC 上是同一个 180 像素，剧本两边的空行预留也都按 `ceil(180/一行の高さ)`
     * 算；端口只换了百分比支的基准，px 支不换就会让前者画成 1/2.3、后者画成满尺，
     * 立绘下面挂出 6~7 行空白。折算是**先乘后除的整数除**，与 C# 同形。
     */
    private fun scriptPx(pixels: Int): Int = metrics.scriptPixels(pixels, scriptFontPixelSize)

    /**
     * 浮点像素 → 整型绘制尺寸，照 `ConsoleRectangleShapePart.cs:28-33`：
     * 截断，截成 0 但原值 ≥ 0.001 时抬到 1（否则细条整条不可见）。
     */
    private fun intPixel(v: Float): Int = v.toInt().let { if (it == 0 && v >= 0.001f) 1 else it }

    /**
     * 追加一段图形：先把手上攒的文本落成一条 run，再落图形段。
     * 图形段的文本负载是等量空格（占宽用，见类文档「占宽」）。
     *
     * ## 按钮区间内的图形段带源按钮身份（总账 GFX-02 供给侧，B5 L2）
     *
     * 原版 `<button>` 区间里切出的节点 —— 文本片与图片片 —— 进的是**同一条**节点列表：
     * 文本在 `HtmlManager.cs:559,573` `list.Add(new ConsoleStyledString(...))`，标签节点
     * （img 分支 `:1221` 产出 `ConsoleImagePart`）在 `:596-604` 追加进同一个 `list`；
     * `</button>` 置 `FlagButton`（`:1005-1011`）后，整张列表由 `cssToButton` 打包成
     * **一条** `ConsoleButtonString`（`:659-661`、`:836-854`：按钮值取自
     * `LastButtonTag.ButtonValueInt/ButtonValueStr`，`:1543-1545`，整条按钮共享一份）。
     * `ConsoleButtonString.cs:19` 的 `AConsoleDisplayNode[] strArray` 因此**同时**装着
     * 文本片与图片片；`CalcPointX`（`:292-303`）逐节点按 `Width` 推进像素游标 ——
     * 图片片 `Text` 为空串、字符跨度记 0（`ConsoleImagePart.cs:41`），`Width` 却是节点
     * 自带的像素宽（`ConsoleImagePart.cs:66-82`），在按钮里照样占一段命中区。
     *
     * 所以移植侧从按钮区间切出的图形段必须落 [TextRun.buttonValue] 与 [TextRun.buttonId]：
     * 编号只在开标签处递增（[buttonSeq]），同一源按钮拆出的文本段与图形段**共用同一编号**
     * —— 命中层（`ButtonLayer.attach`）按源按钮身份合并，不按「值相同」并；
     * 语料形态 `ERB/BODY_INFO/GET_BODY_SETS.ERB:115` 的部位按钮
     * （`<button value=…><img …></button>`）全靠这一份身份才有命中区。
     * 折 Long 口径沿用 [openTag] 落进 [buttonValue] 的结果（纯数字 → Long），本函数只透传，
     * 不破 `ButtonLayer.selectedString` 的 INT 闸（该闸只放行 Long）。
     * 按钮区间外（含无 `value` 属性的 `<button>` —— 原版 `IsButton = text7 != null`，
     * `HtmlManager.cs:1547`，非按钮区间不可点）保持 null/-1；kind 也**不**改成 BUTTON：
     * 接缝形态是 `TextRun(kind=IMAGE/SHAPE, graphic, buttonValue, buttonId)`，
     * 入表条件归命中层判。
     */
    private fun emitGraphic(graphic: InlineGraphic) {
        flushRectBands()
        flushCurrentText()
        emitGraphicRun(graphic)
    }

    /** 直接落一段图形（不碰条带缓冲，避免 [flushRectBands] 递归回 [emitGraphic]）。 */
    private fun emitGraphicRun(graphic: InlineGraphic) {
        // 负 space：原版 ConsoleSpacePart 的 Width 为负，是真实回退（ConsoleSpacePart.cs:23-33），
        // XRAY 叠图靠它把后续 <img> 拉回立绘起点。文本负载为空串，只占布局游标。
        if (graphic is InlineGraphic.Space && graphic.widthCells < 0) {
            out.add(
                TextRun(
                    text = "",
                    fore = fore,
                    back = back,
                    styleMask = styleMask,
                    fontName = fontName,
                    kind = RunKind.SHAPE,
                    graphic = graphic,
                    focusFore = focusFore,
                    buttonValue = buttonValue,
                    buttonId = if (buttonValue != null) buttonId else -1,
                    tooltip = tagTitle,
                ),
            )
            return
        }
        if (graphic.widthCells <= 0) return
        graphicEmitted = true
        val bv = buttonValue
        out.add(
            TextRun(
                text = " ".repeat(graphic.widthCells),
                fore = fore,
                back = back,
                styleMask = styleMask,
                fontName = fontName,
                kind = if (graphic is InlineGraphic.Image) RunKind.IMAGE else RunKind.SHAPE,
                graphic = graphic,
                focusFore = focusFore,
                buttonValue = bv,
                buttonId = if (bv != null) buttonId else -1,
                tooltip = tagTitle,
            ),
        )
    }

    /**
     * 4 参 `<shape type='rect'>` 的像素级条带缓冲。
     *
     * 语料侧的渐变进度条把整条切成上百个 1 单位宽的小矩形（`UI_Components_&_Tables.ERB`
     * 的 `@HTML_RECT_BAR` 渐变分支；1 单位 = `FontSize / 100` 像素，spec:
     * `ConsoleShapePart.cs:61-68`）。原版按 `RectangleF` 逐块画、`SetWidth` 带**子像素进位**
     * （spec: `ConsoleRectangleShapePart.cs:50-59`），落地是连续的 1px 竖列；本层布局的宽度
     * 单位却是**半角格**，每块各占一格会把 40px 的条拉成 100 格（≈2000px）——比裸标签好不了
     * 多少。所以这里把连续同高同纵偏移的块攒成一条带，收尾时按「总宽 ÷ 半角格宽」折成整格
     * 数、逐格取区间中点颜色（保住渐变），宽度与后续文字的落位都与原版同量级。
     *
     * 单块（纯色条、`PRINT_RECT` 那种大块）不参与合并，口径与改前逐像素一致。
     */
    private fun appendRectBand(x: Float, y: Float, w: Float, h: Float, colorArgb: Int) {
        if (x != 0f) {
            flushRectBands()
            rectBands.add(RectBand(x, y, w, h, colorArgb))
            return
        }
        if (rectBands.isNotEmpty()) {
            val last = rectBands[rectBands.size - 1]
            if (last.x != 0f || last.y != y || last.h != h || rectBands.size >= MAX_RECT_BANDS) flushRectBands()
        }
        rectBands.add(RectBand(x, y, w, h, colorArgb))
    }

    /** 落掉攒着的条带：单块/带横向偏移的逐块落，纯连续条带按格合并（见 [appendRectBand]）。 */
    private fun flushRectBands() {
        if (rectBands.isEmpty()) return
        val first = rectBands[0]
        val mergeable = rectBands.size > 1 && first.x == 0f
        if (!mergeable) {
            for (b in rectBands) {
                emitGraphicRun(
                    InlineGraphic.Rect(
                        xOffsetPx = b.x.toInt(), yOffsetPx = b.y.toInt(),
                        widthPx = maxOf(1, b.w.toInt()), heightPx = intPixel(b.h),
                        colorArgb = b.colorArgb,
                        widthCells = InlineGraphic.cellsFor(maxOf(1, b.w.toInt()), metrics.cellPixelWidth),
                    ),
                )
            }
            rectBands.clear()
            return
        }
        var total = 0f
        for (b in rectBands) total += b.w
        val cellPx = metrics.cellPixelWidth
        val cells = if (cellPx <= 0f) 1 else maxOf(1, kotlin.math.ceil(total / cellPx).toInt())
        // 渐变停点：按位置降采样（最多 16 个 —— 够平滑，又不至于每帧重建大数组）。
        val stops = minOf(cells, GRADIENT_STOPS).coerceAtLeast(2)
        val gradient = IntArray(stops) { i -> colorAt(total * (i + 0.5f) / stops) }
        emitGraphicRun(
            InlineGraphic.Rect(
                xOffsetPx = 0, yOffsetPx = first.y.toInt(),
                widthPx = maxOf(1, total.toInt()), heightPx = intPixel(first.h),
                colorArgb = gradient.first(),
                widthCells = cells,
                gradientArgb = gradient,
            ),
        )
        rectBands.clear()
    }

    /** 条带里覆盖 [centerPx] 那一块的颜色（区间中点采样，渐变按格降采样）。 */
    private fun colorAt(centerPx: Float): Int {
        var acc = 0f
        for (b in rectBands) {
            acc += b.w
            if (centerPx <= acc) return b.colorArgb
        }
        return rectBands[rectBands.size - 1].colorArgb
    }

    private fun flushCurrentText() {
        flushRectBands()
        if (text.isEmpty()) return
        val bv = buttonValue
        out.add(
            TextRun(
                text = text.toString(),
                fore = fore,
                back = back,
                styleMask = styleMask,
                fontName = fontName,
                kind = if (bv != null) RunKind.BUTTON else RunKind.TEXT,
                buttonValue = bv,
                buttonId = if (bv != null) buttonId else -1,
                focusFore = focusFore,
                tooltip = tagTitle,
            ),
        )
        text.setLength(0)
    }

    /**
     * 收尾：余下的文本落段。
     *
     * kind 的取法只有一条规则：**本行切出过图形段**（[graphicEmitted]）时，余下的文字与
     * 段首那条一样是普通样式文字（[RunKind.TEXT]）—— 一行的文字段不许一会儿 TEXT 一会儿
     * HTML，绘制侧按 kind 选绘制路径（spec: `ConsoleDisplayLine.cs:118-125` 逐节点画时
     * 文本节点走的都是 `ConsoleStyledString.DrawToString` 那一条，与节点在行内第几个无关）。
     * 一行**没**切出任何图形段时才保留外层 kind：`ERROR` / `PLAIN` 形态的 HTML 段不该被
     * 降级成 TEXT；全标签行还要靠这条保留下来的 kind 走 [flatten] 末尾的空段分支。
     */
    private fun flush(source: TextRun) {
        // 行尾：先把攒着的矩形条带落成图形段（它们在本行文字之前），再落余下的文字
        flushRectBands()
        if (text.isEmpty()) return
        // 按钮内的文字：kind 一律 BUTTON（与图形段规则互斥 —— 按钮文本不参与「本行切出
        // 过图形段就降级 TEXT」那条，否则按钮会丢命中区）。
        val bv = buttonValue
        val kind = when {
            bv != null -> RunKind.BUTTON
            graphicEmitted -> RunKind.TEXT
            else -> source.kind
        }
        out.add(
            TextRun(
                text = text.toString(),
                fore = fore,
                back = back,
                styleMask = styleMask,
                fontName = fontName,
                kind = kind,
                buttonValue = bv,
                buttonId = if (bv != null) buttonId else -1,
                focusFore = focusFore,
                tooltip = tagTitle,
            ),
        )
        text.setLength(0)
    }

    private companion object {
        const val DEFAULT_FORE = 0xC0C0C0
        const val DEFAULT_BACK = 0x000000

        const val DEFAULT_FONT = "ＭＳ ゴシック"

        /** 一条条带最多攒多少块（语料侧自带 100 上限，这里是脏输入的防线）。 */
        const val MAX_RECT_BANDS = 4096

        /** 合并后的条带最多给多少个渐变停点（像素级平滑与数组开销之间的折中）。 */
        const val GRADIENT_STOPS = 16
    }
}

/** 4 参 `<shape type='rect'>` 的一块（像素域的 x/y/宽/高 + 颜色，见 [HtmlRunFlatten.appendRectBand]）。 */
private class RectBand(val x: Float, val y: Float, val w: Float, val h: Float, val colorArgb: Int)

/** `Utils.MixedNum`（spec: `Utils.cs:342-362`）：整数 + 是否带 `px` 后缀。 */
private class Mixed(val num: Int, val isPx: Boolean)

/** 认 `12` / `12px` / `12 Px`；带符号；解析不出返回 null（原版这里是 CodeEE）。 */
private fun mixedNum(raw: String?): Mixed? {
    if (raw.isNullOrBlank()) return null
    var s = raw.trim()
    var px = false
    if (s.endsWith("px", ignoreCase = true)) {
        px = true
        s = s.substring(0, s.length - 2).trim()
    }
    val n = s.toIntOrNull() ?: return null
    return Mixed(n, px)
}

/**
 * `<font color>` / `<shape color>` 的取值解析，三条分支逐条对齐
 * `HtmlManager.stringToColorInt32`（spec: `HtmlManager.cs:1680-1725`）：
 *
 * - `#` 开头：去掉 `#` 后按十六进制解析。原版走 `Convert.ToInt32(text, 16)`，.NET 的
 *   `ParseNumbers.StringToInt` **接受 `0x` / `0X` 前缀**，所以 `color='#0x90EE90'`
 *   是浅绿（本作 `qol_update.ERB:145` 的过滤提示、`:142` 的页码都用这个写法）。
 * - 不带 `#`：先查命名色 `Color.FromName`（`:1705`）。唯一权威是 core 的
 *   [ColorNames]（140 名，逐项对齐 .NET KnownColor，见其 KDoc），`lime` / `orange`
 *   这类树行配色就靠这一支。
 * - 都认不出：原版再试十六进制、命中即报 `InvalidColorName2`（`:1712-1721`）。
 *   宽容层不抛，返回 null 让调用侧保持外层色 —— 屏上少一块颜色好过整行消失。
 *   `transparent` 原版必报 CodeEE（`:1708-1711`），这里同样按「不认」处理。
 *
 * 十进制支是宽容层自造（原版必报 CodeEE，`HtmlManager.cs:1701-1721`），语料 0 处。
 */
private fun colorOf(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("#")) {
        // 原版这条只走十六进制，命名色在这里不生效。
        return hexColor(stripHexPrefix(trimmed.substring(1)))
    }
    ColorNames.rgb(trimmed)?.let { return it }
    if (ColorNames.isTransparent(trimmed)) return null
    val body = stripHexPrefix(trimmed)
    if (body.isEmpty()) return null
    if (body.all { it.isDigit() } && body.length <= 7) {
        return body.toLongOrNull()?.let { if (it in 0L..0xFFFFFFL) it.toInt() else null }
    }
    return hexColor(body)
}

/** `0xRRGGBB` 里的 `0x` / `0X` 前缀（`Convert.ToInt32(str, 16)` 接受它，见 [colorOf]）。 */
private fun stripHexPrefix(s: String): String =
    if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s.substring(2) else s

private fun hexColor(body: String): Int? =
    body.toLongOrNull(16)?.let { if (it in 0L..0xFFFFFFL) it.toInt() else null }
