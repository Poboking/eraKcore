package com.eratw.port.core.fn

import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.ImageSizePx
import com.eratw.port.core.host.LineBufferConsoleSink
import java.util.Locale

/**
 * HTML 家族 + GETDISPLAYLINE（[registerHtmlMethods]）。**文件所有者：A1**（M3-B 第二波）。
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:293-303`（SECTION:HTML）与
 * `:304-307`（LINE-QUERY）；调用序列见 `fixtures/golden/GOLDEN_02_PRINT.ERB:249-266`。
 *
 * 权威实现分布（注意：契约里写的 `Runtime.Script.Statements/HtmlManager.cs` 与 `HtmlParser.cs`
 * 在本 reference 快照中不存在，HTML 解析器就是 `HtmlManager.html2DisplayLine`）：
 * - 函数壳：`MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - 解析/度量/转义：`MinorShift.Emuera.UI.Game/HtmlManager.cs`
 * - 控制台行缓冲：`MinorShift.Emuera.UI.Game/EmueraConsole.cs`
 *
 * 落地函数与证据行号：
 * - HTML_STRINGLEN      FunctionMethodCreator.cs:34-68（GetIntValue :54-66）
 *                       + HtmlManager.HtmlLength :168-182（严格解析见 HtmlStrict.kt）
 * - HTML_SUBSTRING      FunctionMethodCreator.cs:930-952（GetStrValue :944-951）
 *                       + HtmlManager.HtmlSubString :206-326、GetSubStr :184-204
 * - HTML_STRINGLINES    FunctionMethodCreator.cs:953-983（GetIntValue :970-982）
 * - HTML_TOPLAINTEXT    FunctionMethodCreator.cs:6132-6146 → HtmlManager.Html2PlainText :714-717
 * - HTML_ESCAPE         FunctionMethodCreator.cs:6147-6162 → HtmlManager.Escape :719-742
 * - HTML_GETPRINTEDSTR  FunctionMethodCreator.cs:6076-6110 → EmueraConsole.GetDisplayLines :3546-3576
 *                       → HtmlManager.DisplayLine2Html :381-484
 * - HTML_POPPRINTINGSTR FunctionMethodCreator.cs:6112-6130 → EmueraConsole.PopDisplayingLines :3578-3588
 * - GETDISPLAYLINE      FunctionMethodCreator.cs:8415-8440 → EmueraConsole.DisplayLineList :238
 *                       + ConsoleDisplayLine.ToString :127-140
 * 注册表行号：HTML_GETPRINTEDSTR/POPPRINTINGSTR/TOPLAINTEXT/ESCAPE :9150-9153、
 * HTML_STRINGLEN/SUBSTRING/STRINGLINES :9204-9206、GETDISPLAYLINE :9297。
 *
 * 与基准逐项核对（pc-golden-root.txt:297-303，输入均为 `<i>AB</i>CD`）：
 * - `HTML_STRINGLEN=4`：HtmlLength 返回 36（4 个半角字符 × FontSize/2 = 4 × 9），
 *   再按 `2*36/18 = 4` 折算成「全角字数」；`PLAIN=36` 是第二参非 0 时的**原始像素宽度**。
 *   注意 specs/builtin-functions.yaml:642-649 把第二参语义写反了（写成了「返回纯字符长度」），
 *   以 FunctionMethodCreator.cs:58-66 与基准 36 为准。
 * - `HTML_TOPLAINTEXT=ABCD`、`HTML_ESCAPE=&lt;a&gt;`、`HTML_STRINGLINES=2`（`("AAAA",2)`：
 *   预算 2*18/2 = 18 px，每行 2 个半角字符）、
 *   `HTML_SUBSTRING=[<i>AB</i>C]`（预算 3*18/2 = 27 px → A、B、C 三个字符，标签原样保留）。
 *
 * 已知近似（均在函数 KDoc 里单列 TODO(verify)）：
 * 1. 字宽模型。原版走 GDI+ `MeasureCharacterRanges` / `TextRenderer.MeasureText`
 *    （StringMeasure.cs:42-72），结果依赖宿主字体。core 层拿不到字体度量，故内置
 *    「半角 = fontSize/2、全角 = fontSize」的等宽近似；该模型在默认 FontSize=18
 *    （ConfigData.cs:66）下对 ASCII 给出 9 px，与基准 36 完全一致。
 *    **宿主接线后近似仅为纯 JVM 回落**（FONT-MEASURE A3 收口，2026-11-06）：生产宿主
 *    `ConsoleBuiltinHost` 经 `GameSession` 接 `AndroidTextMeasure.measurePixels`
 *    （android.graphics.Paint，GameHost.kt 的 `textMeasureRun` 闭包），量宽走
 *    [measureText] 的三段回落链：`measureTextRun`（带 fontName/styleMask/charSpace，
 *    粗斜体与 `<font face>` 各得独立读数）→ 旧 [BuiltinHost.measureText] →
 *    [approxTextWidth]。headless / 装置（无宿主度量）读数与收口前逐位一致。
 *    **全角/半角的名单不在本文件**，唯一取自 [CellWidth.isFullWidth]
 *    （WIDTH-SINGLE 收口，口径 = CP932 字节数；与像素口径为什么能混用、以及收口后
 *    变大的那一侧，逐条写在 [approxTextWidth]）。
 * 2. ~~`img` / `shape` 标签的宽度此处记 0~~ —— **已收口（M11-A L6 / FN-01）**：按
 *    `ConsoleImagePart.cs:60-87` / `ConsoleShapePart.cs:46-79` 折成像素宽。「无 `width`
 *    属性的 `<img>` 按图元宽高比」**也已收口（IMG-A4 I1，2026-11-06）**：宿主尺寸源
 *    [BuiltinHost.imageSizeOf]（生产经 `AuxMacroHost` 现读 aux 的 SpritePool，见其 KDoc）
 *    查到 `DestBaseSize` 就按 `宽 * 折算高 / 高` 折算（ConsoleImagePart.cs:67-71，
 *    消费点在 `HtmlStrict.kt` 的 `imgGroupNode` —— img/shape 计宽段的物理所在）；
 *    查不到（宿主缺位 / 图元缺失）仍回落量 AltText（:61-64、:110-114）—— 纯 JVM 读数零变化。
 * 3. ~~自动折行未模拟~~ —— **已收口（M11-A L6 / FN-03）**：按 `Config.DrawableWidth`
 *    折行（`PrintStringBuffer.cs:196-266`），可绘制宽由 [drawableWidthPx] 从条目表现取
 *    （`Config.cs:461-466`）。`<div>` 子层的 `SubDivisionWidth`（FN-02）**已派生**
 *    （CONTRACT-HTMLDIV-B1 D5：`DivSpec.geometry`，HM :618-635 口径 —— `ToPixel(width)`
 *    取原值不取 abs、减 margin/border/padding 左右各一份）；量宽面的读数**不因它变化**：
 *    `HtmlLength` 只累加 `array[0].Buttons[].Width`（HM :176-181），div 节点 0 宽
 *    （ConsoleDivPart.cs:217-219）、子行宽留在递归层（HM :636-642），SubDivisionWidth
 *    只作用于**子行**的折行与对齐（HM :698/:702-704），对父行首行宽不可观测 ——
 *    既有读数由 `HtmlDivWidthTest` 钉住。
 * 4. ~~`div` 的版面语义未模拟~~ —— **已复刻（CONTRACT-HTMLDIV-B1 D1/D2/D4，2026-11-06）**：
 *    `<div>` 属性全量解析（xpos/ypos/width/height/depth/color/bcolor/size/rect/display/
 *    margin/padding/border/radius）落 [com.eratw.port.core.host.DisplayDivBlock]（零宽
 *    内嵌覆盖块：几何 + depth + IsRelative/IsEscaped + 子行 + altHeadTag）；div `color`
 *    染**块矩形**（原版 `DrawBorder` 填充，ConsoleDivPart.cs:186），不再染整行；
 *    GETDISPLAYLINE 重现块的头尾标签（D6，`ConsoleDivPart.ToString`，ConsoleDivPart.cs:221-233），
 *    HTML_GETPRINTEDSTR 剔除块（`DisplayLine2Html` 对块无分支，HtmlManager.cs:446-464）。
 *    [htmlPrintDocument] 的 [HtmlRunKind.DIV] 段（量宽/文档面）行为不变，由
 *    `HtmlStrictTest` / `HtmlDivWidthTest` 钉住。
 */
internal fun registerHtmlMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // ---- HTML_STRINGLEN(htmlStr[, flag]) ----
    // flag 省略或为 0：返回「全角字数」= 2*width/FontSize（向上取整，:58-63）；
    // flag 非 0：返回原始像素宽度 width（:65-66）。负数分支（:63）照抄，实际不可达。
    r.register(BuiltinMethod("HTML_STRINGLEN", I, 1, 2) { c, a ->
        val num = htmlLength(c, argStr(a, 0, "HTML_STRINGLEN")).toLong()
        val plain = a.size == 1 || argInt(a, 1, "HTML_STRINGLEN") == 0L
        if (!plain) {
            Value.IntV(num)
        } else {
            val fontSize = c.host.fontSize
            val twice = 2 * num
            val roundUp = if (twice % fontSize != 0L) 1L else 0L
            Value.IntV(if (num >= 0) twice / fontSize + roundUp else twice / fontSize - roundUp)
        }
    })

    // ---- HTML_SUBSTRING(htmlStr, length) ----
    // 返回首段（:951），并把 [首段, 剩余] 写进 RESULTS:0 / RESULTS:1（:947-950）。
    r.register(BuiltinMethod("HTML_SUBSTRING", S, 2, 2) { c, a ->
        val (head, tail) = htmlSubString(
            c,
            argStr(a, 0, "HTML_SUBSTRING"),
            argInt(a, 1, "HTML_SUBSTRING"),
        )
        val cap = c.store.getLength("RESULTS", -1)
        if (cap > 0) c.store.writeStr("RESULTS", longArrayOf(0), head)
        if (cap > 1) c.store.writeStr("RESULTS", longArrayOf(1), tail)
        Value.StrV(head)
    })

    // ---- HTML_STRINGLINES(htmlStr, length) ----
    // 反复取剩余段直到空串（:974-979），空输入直接 0（:972-975）。
    r.register(BuiltinMethod("HTML_STRINGLINES", I, 2, 2) { c, a ->
        val src = argStr(a, 0, "HTML_STRINGLINES")
        val width = argInt(a, 1, "HTML_STRINGLINES")
        if (src.isEmpty()) {
            Value.IntV(0)
        } else {
            var text = src
            var lines = 0L
            do {
                val next = htmlSubString(c, text, width).second
                lines++
                // 原版在 length<=0 时剩余段恒等于原串 → 死循环（HtmlSubString :242-249 只前进 1 个字符
                // 又把整串还回来）。此处防挂起：剩余段未变即停，返回已计行数。属有意偏差。
                if (next == text) break
                text = next
            } while (text.isNotEmpty())
            Value.IntV(lines)
        }
    })

    // ---- HTML_TOPLAINTEXT(htmlStr) ---- :6132-6146 → Html2PlainText :714-717
    r.register(BuiltinMethod("HTML_TOPLAINTEXT", S, 1, 1) { c, a ->
        Value.StrV(htmlToPlainText(argStr(a, 0, "HTML_TOPLAINTEXT")))
    })

    // ---- HTML_ESCAPE(str) ---- :6147-6162 → Escape :719-742（替换表 :372-378）
    r.register(BuiltinMethod("HTML_ESCAPE", S, 1, 1) { c, a ->
        Value.StrV(htmlEscape(argStr(a, 0, "HTML_ESCAPE")))
    })

    // ---- HTML_GETPRINTEDSTR([lineNo]) ---- :6076-6110
    // lineNo 缺省 0（:6094-6097），负数抛 CodeEE（:6099-6102）。
    // GetDisplayLines 从**最新**行往回数「逻辑行」（EmueraConsole.cs:3546-3576），
    // 因此 0 = 最近一条逻辑行连同其后的折行；再套 <p align><nobr>（DisplayLine2Html :387-400, :473-477）。
    r.register(BuiltinMethod("HTML_GETPRINTEDSTR", S, 0, 1) { c, a ->
        val lineNo = if (a.isEmpty()) 0L else argInt(a, 0, "HTML_GETPRINTEDSTR")
        if (lineNo < 0) {
            throw CodeEE("函数 HTML_GETPRINTEDSTR 的第 1 个参数不能为负：$lineNo")
        }
        val lines = c.host.consoleLines
            ?: throw CodeEE("函数 HTML_GETPRINTEDSTR 需要宿主接线控制台行缓冲（consoleLines == null）")
        val picked = pickDisplayLines(lines, lineNo)
        Value.StrV(if (picked.isEmpty()) "" else displayLine2Html(picked, needPandN = true))
    })

    // ---- HTML_POPPRINTINGSTR() ---- :6112-6130
    // 原版取「打印中缓冲」并清空（PopDisplayingLines，EmueraConsole.cs:3578-3588）；
    // 缓冲为空或控制台未启用时返回 null → 函数返回 ""（:6123-6127）。
    // 宿主读取即消费（[BuiltinHost.popPrintingLines] 的 getter 有副作用）。
    r.register(BuiltinMethod("HTML_POPPRINTINGSTR", S, 0, 0) { c, _ ->
        val lines = c.host.popPrintingLines
            ?: throw CodeEE("函数 HTML_POPPRINTINGSTR 需要宿主接线打印中缓冲（popPrintingLines == null）")
        Value.StrV(if (lines.isEmpty()) "" else displayLine2Html(lines, needPandN = false))
    })

    // ---- GETDISPLAYLINE(lineNo) ---- :8415-8440
    // 直接按**显示行**下标取 DisplayLineList[lineNo]（EmueraConsole.cs:238），
    // 越界（含负数）返回 ""（:8431-8434）；文本 = ConsoleDisplayLine.ToString()（:8437）。
    // div 覆盖块按 ConsoleDivPart.ToString()（ConsoleDivPart.cs:221-233）重建：altHeadTag
    // + 子行（每条后跟 "\r\n"）+ "</div>"（CONTRACT-HTMLDIV-B1 D6；黄金锚
    // pc-golden-root.txt:71 的标题屏五子棋块）。
    r.register(BuiltinMethod("GETDISPLAYLINE", S, 1, 1) { c, a ->
        val lineNo = argInt(a, 0, "GETDISPLAYLINE")
        val lines = c.host.consoleLines
            ?: throw CodeEE("函数 GETDISPLAYLINE 需要宿主接线控制台行缓冲（consoleLines == null）")
        if (lineNo < 0 || lineNo >= lines.size.toLong()) {
            Value.StrV("")
        } else {
            Value.StrV(lines[lineNo.toInt()].text)
        }
    })
}

/**
 * HTML 片段 → 显示行（`HtmlManager.Html2DisplayLine`，HtmlManager.cs:517-527）。
 *
 * 这是 `HTML_PRINT` 族**输出侧**的唯一语义实现：`printHtml` 在宿主里拿到的是一段 HTML
 * 原文，玩家最终看到的行/格子由这里决定（标签剥离、`<br>` 与裸换行切行、`<p align>`
 * 对齐、`<nobr>`、`<font color/bcolor/face>` 样式帧、`<button>/<nonbutton>` 的按钮组、
 * `<img>/<shape>` 的 AltText、`<div>` 的浮动块子行、实体还原、注释跳过）。
 *
 * 与 [htmlLength] 共用 [HtmlStrictParser]：同一份标签表、同一份错误文案，畸形输入在这里
 * 抛与原版相同的 CodeEE（ADR-005）。区别只在于产物 —— 这里要行，那里要宽度。
 *
 * 接线状态（**需组长接线**，见交付报告）：
 * - `core/host/LineBufferConsoleSink.printHtml` 目前把整段 HTML 原文塞进一个
 *   `Kind.HTML` 段、固定一条显示行，`<br>` 不切行，`<p align>` 不改对齐；
 *   正确做法是改成调用本函数，把每个 [HtmlPrintRow] 落成一条 `DisplayLine`。
 * - `render` 的 `MiniHtml` 是另一份实现（自己一套解析 + `UnsupportedTag` 子集），
 *   本函数可作为它的超集；两边口径若合并，[HtmlStrictTest] 里的「MiniHtml 对照表」需同步。
 * 卡点：这两处都不在本路文件面（`core/fn`），故本波只交语义实现与测试。
 *
 * 两个配置值各管各的，别当成同一件事：
 * - [fontSize]（`ConfigData.cs:66` 缺省 18）只归一 **`<img>`** 的 height/width/ypos
 *   （`Utils.MixedNum.BuilderString`，Utils.cs:87-98 经 `AddTagMixedNumArg` :364-371，
 *   落在 `ConsoleImagePart.cs:45-59`）。**`<shape>` 的 param 不参与**：
 *   `ConsoleShapePart.CreateShape`（:20-31）直接拼原值，PC 基准可验
 *   （`pc-golden-root.txt:24` 的 `param='0, 30, 24, 64'`，归一后会变成 `0, 5, 4, 11`）。
 * - [focusColor]（`ConfigData.cs:70` 缺省 0xFFFF00）是 **`bcolor` 的省略判据**：
 *   `<font bcolor>`（HM :887-891）与 `<shape bcolor>`（ConsoleShapePart.cs:39-44）都比它。
 *   宽度与可见文本不受这两个值影响。
 *
 * 两个缺省值 = 出厂配置：字号 [BuiltinHost.DEFAULT_FONT_SIZE]（18）、
 * 焦点色 [LineBufferConsoleSink.DEFAULT_FOCUS_COLOR]（0xFFFF00）。**只服务未接线的调用点**
 * （headless / 纯 JVM 装置）；生产回读链（宿主 → [consolePrintedLine] →
 * [htmlSegmentReadback]）必须把会话生效值显式传进来，否则玩家改过 `Config.FontSize` / `HIGHLIGHT COLOR`
 * 之后回读串与屏上分叉（[consolePrintedLine] 的「配置注入面」节）。
 *
 * TODO(verify)：`<div>` 段在父行里只保留子行、不重现 `<div ...>` 头尾标签
 * （`ConsoleDivPart.ToString()`，ConsoleDivPart.cs:221-231 会重现）；等宿主真要对
 * `HTML_GETPRINTEDSTR` 逐字节对拍浮动块时再补 MixedNum 归一化文案。
 */
fun htmlPrintDocument(
    html: String,
    fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
): HtmlPrintDoc = HtmlStrictParser(html, { 0L }, { htmlUnescape(it) }, fontSize, focusColor).document()

/**
 * 控制台显示行（[BuiltinHost.consoleLines] / [BuiltinHost.popPrintingLines] 的元素）。
 *
 * 对应原版 `ConsoleDisplayLine`（ConsoleDisplayLine.cs:8-...）里被 HTML 家族用到的四个事实：
 * - [text]：`ConsoleDisplayLine.ToString()` 的结果 = 各按钮文本顺序拼接
 *   （ConsoleDisplayLine.cs:127-140 → ConsoleButtonString.cs 同名方法），GETDISPLAYLINE 返回它。
 *   逐节点口径：文本节点是其可见文本（`ConsoleStyledString.Text`），`<img>` / `<shape>`
 *   节点是**重建的 AltText**（ConsoleImagePart.cs:45-59、ConsoleShapePart.cs:16-45），
 *   见 [consolePrintedLine] 的 htmlSegmentReadback。
 * - [html]：该行的**行体 HTML**（按钮 `<button value='...'>` 与样式标签、文本已转义），
 *   但**不含** `<p align=...>` / `<nobr>` 外壳 —— 外壳由 [displayLine2Html] 按
 *   DisplayLine2Html :387-400 与 :473-477 生成。HTML_PRINT 的段保留原文（标签即样式），
 *   只剥掉其中行级的 p/nobr 外壳并把 `<p align>` 折回 [align]（[stripRowShell]），
 *   其余段按 DisplayLine2Html 的逐节点规则重建，见 [consolePrintedLine]。
 * - [htmlParts]：HTML 段的分部（节点）快照，含每个分部的 `StringStyle.ButtonColor`
 *   （= HTML `<font bcolor>` 写进的分部背景，HtmlManager.cs:887-891）；空表 = 该行没有
 *   需要按节点回读的 HTML 段，或解析未触发（见 [consolePrintedLine]）。
 * - [align]：`ConsoleDisplayLine.Align`（DisplayLineAlignment.cs:3-8）。注意 DisplayLine2Html
 *   只读 **lines[0]** 的对齐（:387），后续行沿用同一外壳。
 * - [isLogicalLine]：`ConsoleDisplayLine.IsLogicalLine`，GetDisplayLines 靠它数「逻辑行」
 *   （EmueraConsole.cs:3560-3572）。
 */
data class ConsolePrintedLine(
    val text: String,
    val html: String = "",
    val align: Alignment = Alignment.LEFT,
    val isLogicalLine: Boolean = true,
    val htmlParts: List<HtmlPrintRun> = emptyList(),
    /**
     * 行体 HTML 是否由 [consolePrintedLine] 按 `DisplayLine2Html` 的逐节点规则**算出**
     * （true = [html] 为空就是真空行）。[displayLine2Html] 只在本标志为假时才回落
     * `Escape(text)` —— div 覆盖块的行体**就该是空的**（原版 `DisplayLine2Html`
     * 对 `ConsoleDivPart` 无分支：HtmlManager.cs:446-464 只处理 StyledString /
     * ImagePart / ShapePart，块整段跳过、子行也不出现），回落会把 GETDISPLAYLINE 的
     * 块重建文本再逃逸一遍吐进 HTML_GETPRINTEDSTR，与原版分叉。
     */
    val htmlBuilt: Boolean = false,
)

// ---------------------------------------------------------------------------
// 控制台显示行 → ConsolePrintedLine（HOST-02 / CONTRACT-Q1 §2.2 的唯一映射）
// ---------------------------------------------------------------------------

/**
 * [ConsolePrintedLine] 的惰性视图：只在调用方取某一行时才重建该行的文本与行体 HTML。
 *
 * 为什么惰性：HTML_GETPRINTEDSTR 的 `pickDisplayLines` 从最新一行往回扫、命中即停
 * （EmueraConsole.cs:3546-3576），GETDISPLAYLINE 只取一个下标；若每次读取都把整份
 * displayLineList（上限 Config.MaxLog，缺省 5000）全量序列化，脚本里的逐行循环
 * （语料 BODY_INFO 的 `GET_STRING_STATE_GENERAL` 就是逐行取）会变成平方级开销。
 * 视图元素仍从 [consolePrintedLine] 现算，不缓存任何结果，不构成第二份状态。
 *
 * @param lines 显示行快照（[LineBufferConsoleSink.lines] 的返回值，元素不可变）。
 * @param defaultFontName 宿主环境的 `Config.FontName`，用于判断是否写 `<font face>`。
 * @param graphicFontPx 图形段的**脚本字号**（`<img>` / `<shape>` 折算像素宽的基尺）；
 *   必须与控制台排版用的那一份同源 —— [LineBufferConsoleSink.scriptFontSize]，
 *   生产装配点 = 生效 `Config.FontSize`（[com.eratw.port.core.proc.GameSession] 同一个值
 *   同时喂 sink 与 [BuiltinHost.fontSize]）。0（缺省）= 不接图形，读数与收口前逐位一致。
 * @param imageSizeOf `<img>` 缺 `width` 时按基图纵横比推宽的尺寸源（[ImageSizePx]）；
 *   null（缺省）= 宿主未接线，缺 width 的图元计 0 格（见 [consolePrintedLine] 的 KDoc）。
 * @param fontSize 回读链的**配置字号**（`Config.FontSize`，`ConfigData.cs:66` 缺省 18）：
 *   [htmlSegmentReadback] 重建 `<img>` 的 AltText 时，非 px 的 height/width/ypos 按
 *   `num * fontSize / 100` 归一。缺省 = 出厂 18（未接线调用点与改动前读数逐值相同）。
 * @param focusColor 回读链的**配置焦点色**（`Config.FocusColor`，`ConfigData.cs:70` 缺省
 *   0xFFFF00）：`<font bcolor>` / `<shape bcolor>` 的省略判据（值比较）。缺省 = 出厂
 *   0xFFFF00（同上）。
 *
 * 两个配置项与 [graphicFontPx] 的关系见 [consolePrintedLine] 的「配置注入面」节：
 * [graphicFontPx] 管**列坐标**（0 = 不接图形），这两个管**回读文案**（永远有值）。
 */
internal fun consolePrintedLines(
    lines: List<LineBufferConsoleSink.DisplayLine>,
    defaultFontName: String,
    graphicFontPx: Long = 0L,
    imageSizeOf: ((String) -> ImageSizePx?)? = null,
    fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
): List<ConsolePrintedLine> = object : AbstractList<ConsolePrintedLine>() {
    override val size: Int get() = lines.size
    override fun get(index: Int): ConsolePrintedLine = consolePrintedLine(
        lines[index],
        defaultFontName,
        graphicFontPx,
        imageSizeOf,
        fontSize,
        focusColor,
    )
}

/**
 * 一条控制台显示行 → [ConsolePrintedLine]（HOST-02 的唯一映射实现）。
 *
 * 三个字段逐条对拍原版：
 * - [ConsolePrintedLine.text] = `ConsoleDisplayLine.ToString()`（ConsoleDisplayLine.cs:127-140
 *   → ConsoleButtonString.cs:375-387）：各节点文本顺序拼接。HTML 段在打印期就被
 *   `HtmlManager.Html2DisplayLine` 解析成节点（HtmlManager.cs:517-561），文本节点过
 *   `Unescape`（:558），`<img>` / `<shape>` 节点是**重建的 AltText**
 *   （ConsoleImagePart.cs:45-59、ConsoleShapePart.cs:16-45），故回读文本 = 各节点 ToString()
 *   的顺序拼接（见 [htmlSegmentReadback]）；不含图元的 HTML 段仍走 `Html2PlainText`
 *   （去标签 + 还原实体，:714-717），逐字不变。
 * - [ConsolePrintedLine.html] = `DisplayLine2Html` 的**行体**（HtmlManager.cs:381-484）：
 *   按钮 `ConsoleButtonString` 包 `<button value='...'>`（:415-470，值取 `Inputs` 的
 *   Escape，ConsoleButtonString.cs:81-95），文本节点按样式包 `<font ...>` 与
 *   `<s>/<u>/<i>/<b>`（:452-454、getStringStyleStartingTag :864-915、
 *   getClosingStyleStartingTag :917-940），文本过 `Escape`（:719-742）。
 *   `<p align>`/`<nobr>` 外壳不在这里加（[displayLine2Html] 按 needPandN 生成）。
 *   HTML 段保留原文（其内部样式/按钮标签在原版由 DisplayLine2Html 逐节点重建，:446-464），
 *   只按 [stripRowShell] 剥掉行级的 p/nobr 外壳 —— 原版行体里永远没有这两枚标签，
 *   不剥则回读串带两层外壳、再喂 `HTML_PRINT` 必抛「<p>标签重复使用」（判式见
 *   HtmlManager.cs:1113-1116；外层 `<font>` 也**不**包 HTML 段：原版 HTML 文本的基准色是
 *   当前 SETCOLOR 的值，见 HtmlAnalzeState.GetSS，HtmlManager.cs:128-150）。
 * - [ConsolePrintedLine.align] / [ConsolePrintedLine.isLogicalLine] 直接取显示行快照
 *   （原版 `ConsoleDisplayLine.Align`/`IsLogicalLine`，ConsoleDisplayLine.cs:10-32）。
 *
 * `bcolor`（分部背景，FN-11）的记录口径：
 * - 原版 `StringStyle.ButtonColor` 在 `getStringStyleStartingTag`（HtmlManager.cs:887-891）
 *   里以**值比较**判据写进 `<font ... bcolor='#RRGGBB'>`：`ButtonColor != Config.FocusColor`
 *   才写（缺省 FocusColor = 0xFFFF00，ConfigData.cs:70）。ERB 侧只有 HTML 的
 *   `<font bcolor>` 能改它（`SETCOLOR` 只动前景），所以非 HTML 部的背景在原版恒为
 *   FocusColor，行体不输出 `bcolor` 属性 —— 这与原版一致，不是缺失。
 * - HTML 部的背景由 [ConsolePrintedLine.htmlParts] 记录：HTML 段按
 *   [HtmlStrictParser] 解析出的每个分部给一份 [HtmlPrintStyle]，其
 *   [HtmlPrintStyle.buttonColor] 已按上面的值比较归一（null = 等于 FocusColor，等价于没写，
 *   见 [HtmlStrictParser.openFont]）。行体 [ConsolePrintedLine.html] 保留 HTML 原文，
 *   所以 `bcolor` 在回读 html 里照旧带背景语义；两份表示同源（同一次解析）。
 *
 * 图元段的回读（FN-12）：`<img>` 的 AltText 固定次序 `src srcb srcm height width ypos`、
 * 每个 MixedNum 走 `Utils.AddTagMixedNumArg`（`num == 0` 整条不写、非 px 值按
 * `num * FontSize / 100` 归一，ConsoleImagePart.cs:45-59 + Utils.cs:364-371）；`<shape>`
 * 的 AltText 是 `type` 小写 + `param` 原值（`", "` 分隔、px 项补后缀，不归一）+ 只在
 * 设过时写 `color` / 只在 != FocusColor 时写 `bcolor`（ConsoleShapePart.cs:16-45）。
 * 两者都由 [HtmlStrictParser] 产出，本文件不复制第二份重建规则。
 *
 * TODO(verify)：HTML 段的解析只在段文本命中 `img`/`shape`/`<font bcolor` 时进行
 * （[HTML_NODE_TRIGGER]）。原版 HTML_PRINT 的语法错误（含畸形实体）在**打印期**抛 CodeEE
 * （HtmlManager.cs:773-780、:801-831），本实现推迟到读取 HTML_GETPRINTEDSTR /
 * GETDISPLAYLINE 时才抛；而 sink 切行后的片段可能带未闭合的 `<div>` 头
 * （`LineBufferConsoleSink.splitHtmlLines` 不补 `</div>`），对这类片段强行严格解析会抛
 * 错误 —— 故只对上面三类标签触发。卡点：语料 GETDISPLAYLINE 0 命中、
 * HTML_GETPRINTEDSTR 的黄金夹具没有图元行，没有对拍读数；语料静态 grep 未见 HTML_PRINT
 * 里的裸 `&`，动态拼接的串无法在装载期穷举。
 *
 * 配置注入面（`Config.FontSize` / `Config.FocusColor` 的回读侧）：
 * - 这两个值**只**影响 [htmlSegmentReadback] 里 `<img>` 重建文案的像素折算
 *   （`num * FontSize / 100`，ConsoleImagePart.cs:45-59 + Utils.cs:364-371）与
 *   `<font bcolor>` / `<shape bcolor>` 的省略判据（`!= Config.FocusColor` 才写，
 *   HtmlManager.cs:887-891、ConsoleShapePart.cs:39-44）。**不**影响可见文本、行体原文、
 *   宽度与列坐标。
 * - [fontSize] / [focusColor] = 会话生效配置值，由生产装配点
 *   ([com.eratw.port.core.proc.ConsoleBuiltinHost] 的 `printedLinesOf`）从 sink 自持的
 *   `scriptFontSize`（生效 `Config.FontSize`）与 `focusColor`（生效 `Config.FocusColor`）
 *   注入；缺省 = 出厂 18 / 0xFFFF00，未接线的调用点读数与注入前**逐字节相同**。
 * - 与 [graphicFontPx] 的分工：那个是**列坐标**的图形尺（0 合法 = 图形不计格），
 *   这两个是**回读文案**的配置值（永远有值，哪怕是出厂值）。别把两者合成一个参数：
 *   headless 装置要「图形不计格但仍按 FontSize=18 归一 AltText」，正是改动前的形态。
 * - focusColor 的来源下溯同 `consoleStyleOf`（`BuiltinHost` 没有该读数、`ConsoleSink`
 *   接口也没有）：`(console as? LineBufferConsoleSink)?.focusColor`，落空（Noop / 诊断
 *   假 sink）回落出厂常量。TODO(verify)：自定义 `ConsoleSink` 实现不携带焦点色时按出厂值
 *   回读，与屏上真值可能分叉；卡点＝接口文件不在本路文件面，且生产装配恒为
 *   `LineBufferConsoleSink`（`GameSession` 喂 `effective.color(ConfigKey.FocusColor)`）。
 *
 * TODO(verify)：`<div>` 段（[HtmlRunKind.DIV]）在父行文本里只拼子行文本，不重现
 * `<div ...>` 头尾标签（`ConsoleDivPart.ToString()`，ConsoleDivPart.cs:221-231 会重现）；
 * 本路不触达（`div` 不在 [HTML_NODE_TRIGGER] 内，文本仍按 Html2PlainText）。
 *
 * TODO(verify)：HTML 段的行体按**原文**输出，只按 [stripRowShell] 剥掉行级的 p/nobr 外壳
 * （原版会把这两枚折成行级标志并据此重建外壳，HtmlManager.cs:700-710、:387-402，剥壳后
 * 「回读 → 再打印」的往返不再自产脏串，判性见 `HtmlPrintedStrRoundTripTest`）；注释与其余
 * 布局标签仍**不**按 Html2DisplayLine 的解析结果规范化（见 HtmlManager.cs:517-710）。
 * 文本比较（HTML_TOPLAINTEXT）与按钮值不受影响；依赖全量规范化形态的调用点需要 PC 侧
 * 对拍后才能定案（卡点：golden 只覆盖纯文本行）。
 *
 * 段列坐标（[graphicFontPx] / [imageSizeOf]）：`starts`/`end` 是**格**坐标系，必须与
 * [LineBufferConsoleSink] 排版时的 `Button.startCol` 同一把尺（sink 的 `segmentWidth`：
 * HTML 段 = `visibleHtmlCells(seg.text, scriptFontSize, imageSizeOf)`，其余 = `CellWidth.of`）。
 * 收口前这里对 HTML 段量的是「剥标签后的可见文本」（`visible[i]`），`<img>` / `<shape>`
 * 因此记 0 格 ⇒ 段列偏小，紧随其后的按钮（`line.buttons` 的列号由 sink 带图形宽算出）
 * 落不进本段或落错段，HTML_GETPRINTEDSTR / GETDISPLAYLINE 回读的 `<button>` 包裹位置
 * 就与屏上不一致（GFX 段最典型：`<img width='40px'>` 后接 PRINTBUTTON）。
 * 现在 HTML 段直接走 [CellWidth.visibleHtmlCells]（图形感知、与 sink / 渲染侧同尺），
 * 非 HTML 段仍是 [CellWidth.of]。
 *
 * [graphicFontPx] = 0（缺省，纯 JVM 装置 / 未接线的构造点）时 [CellWidth.visibleHtmlCells]
 * 只算文本，读数与本收口前**逐值一致**（标签照旧 0 宽）。生产装配点
 * （[com.eratw.port.core.proc.ConsoleBuiltinHost]）从 sink 现取 `scriptFontSize` 与
 * `imageSizeOf` 传进来 —— 必须取 sink 那一份而不是 `BuiltinHost.fontSize`：
 * sink 的列坐标是构造期按 `scriptFontSize` frozen 的，两边字号分叉会重新错位。
 *
 * TODO(verify)：`<img>` **缺 `width`** 的占宽依赖 [imageSizeOf]；宿主未接（null）时
 * 仍计 0 格（与收口前同读数，原版按基图纵横比推宽，ConsoleImagePart.cs:66-70）。
 * 生产 sink 已接（`GameSession` 现读 aux 的 SpritePool），headless 装置与
 * `consolePrintedLines` 的缺省调用点没有尺寸源 ⇒ 这类行在纯 JVM 下的回读列坐标照旧偏小，
 * 单测只能靠注入尺寸源钉纵横比支（见 `HtmlPrintedWidthGraphicsTest`）。
 */
internal fun consolePrintedLine(
    line: LineBufferConsoleSink.DisplayLine,
    defaultFontName: String,
    graphicFontPx: Long = 0L,
    imageSizeOf: ((String) -> ImageSizePx?)? = null,
    fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    focusColor: Int = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR,
): ConsolePrintedLine {
    val segments = line.segments
    // 两套口径分开算，别混用（混用会把按钮列坐标算坏）：
    // - visible：玩家读到的**字符**，HTML 段去标签 + 还原实体（Html2PlainText :714-717）；
    //   sink 的按钮区间与折行也按这个口径计宽（visibleHtmlWidth），列坐标必须同源。
    //   div 区间的子行文本**留在** visible 里：块不占行宽、子行文本的列坐标只用于
    //   选项快照排序（过渡期内联渲染也按它计宽），与 sink 的 visibleHtmlWidth 同源。
    // - 回读文本：ConsoleDisplayLine.ToString()，HTML 段里的 img/shape 节点给 AltText；
    //   div 区间按 [DisplayDivBlock.sourceRange] **整段替换**成块的重建文案
    //   （[divBlockReadback]，ConsoleDivPart.ToString，ConsoleDivPart.cs:221-233 ——
    //   CONTRACT-HTMLDIV-B1 D6，黄金锚 pc-golden-root.txt:71）。
    val visible = ArrayList<String>(segments.size)
    val readback = ArrayList<String>(segments.size)
    val htmlParts = ArrayList<HtmlPrintRun>()
    // 每段的 div 块（按区间起点排序；GETDISPLAYLINE 拼接与 HTML_GETPRINTEDSTR 剔除共用）。
    val blocksBySegment = Array(segments.size) { ArrayList<DisplayDivBlock>() }
    for (b in line.divBlocks) {
        if (b.sourceSegment in blocksBySegment.indices && !b.sourceRange.isEmpty()) {
            blocksBySegment[b.sourceSegment].add(b)
        }
    }
    for (i in blocksBySegment.indices) blocksBySegment[i].sortBy { it.sourceRange.first }
    for ((i, seg) in segments.withIndex()) {
        if (seg.kind != LineBufferConsoleSink.Kind.HTML) {
            visible.add(seg.text)
            readback.add(seg.text)
            continue
        }
        val plain = htmlToPlainText(seg.text)
        visible.add(plain)
        readback.add(divSplicedReadback(seg.text, blocksBySegment[i], htmlParts, fontSize, focusColor))
    }
    // 列坐标必须与 sink 的 DisplayLine.buttons 同一坐标系（CellWidth 网格，见 segmentsOf）：
    // HTML 段走 [CellWidth.visibleHtmlCells]（图形感知；[graphicFontPx] 为 0 时只算文本），
    // 非 HTML 段仍是 [CellWidth.of]。旧写法对 HTML 段量 `visible[i]`（剥标签后的文本），
    // `<img>`/`<shape>` 记 0 格 ⇒ 段列偏小、按钮开在错段（见函数 KDoc 的「段列坐标」节）。
    val widths = IntArray(segments.size) { i ->
        val seg = segments[i]
        if (seg.kind == LineBufferConsoleSink.Kind.HTML) {
            CellWidth.visibleHtmlCells(seg.text, graphicFontPx, imageSizeOf)
        } else {
            CellWidth.displayCells(seg.text)
        }
    }
    val starts = IntArray(segments.size)
    var col = 0
    for (i in segments.indices) {
        starts[i] = col
        col += widths[i]
    }

    val text = StringBuilder()
    val html = StringBuilder()
    // 从 HTML 段行体里剥出的外壳对齐（原版行级标志 `ConsoleDisplayLine.Align` 的载体，
    // 判据与剥壳口径见 [stripRowShell]）；null = 该行体没写 `<p align>`，回落显示行自己的对齐。
    var shellAlign: Alignment? = null
    var buttonIndex = 0
    var openButtonEnd = -1
    for (i in segments.indices) {
        val seg = segments[i]
        val start = starts[i]
        val end = start + widths[i]
        // 上一条按钮在本段之前结束 → 先闭合。
        if (openButtonEnd >= 0 && start >= openButtonEnd) {
            html.append("</button>")
            openButtonEnd = -1
        }
        // 本段覆盖到一条尚未打开的按钮区间 → 打开（按钮边界与段边界同源，见 buildRows）。
        while (openButtonEnd < 0 && buttonIndex < line.buttons.size) {
            val button = line.buttons[buttonIndex]
            if (button.endCol <= start) {
                buttonIndex++
                continue
            }
            if (button.startCol >= end) break
            html.append("<button value='").append(htmlEscape(button.value.toString())).append("'>")
            openButtonEnd = button.endCol
            buttonIndex++
        }
        if (seg.kind == LineBufferConsoleSink.Kind.HTML) {
            // 原文已是 HTML；内部样式/按钮由原文自身表达（见上方 KDoc）。
            // 但**行级外壳**（p/nobr）必须剥掉 —— 原版行体是逐节点重建，里面永远没有
            // p/nobr（它们被 html2DisplayLine 折成行级标志 Align/FlagNobr，HtmlManager.cs:700-710），
            // 外壳由 [displayLine2Html] 按 needPandN 唯一添加（:387-402、:478-482）。
            // 不剥 → 回读串带两层外壳，再喂 HTML_PRINT 时第二枚 `<p>` 命中原版
            // 「开 `<p>` 查 `FlagNobr`」那条复制粘贴判式（:1113-1116，ADR-005 必须复刻）而抛
            // 「<p>标签重复使用」——端点 0.2.35 `qol_EVENTCOMEND.ERB` 那条自产脏串的成因。
            // div 块区间**整段剔除**（CONTRACT-HTMLDIV-B1 D6）：原版 `DisplayLine2Html`
            // 对 `ConsoleDivPart` 无分支（HtmlManager.cs:446-464），块的子行/头尾标签
            // 在 HTML_GETPRINTEDSTR 的产物里**不出现** —— 与 GETDISPLAYLINE（走
            // `ToString()`，块重建完整吐出）行为刻意不同，两边都按原版。
            val bodyRaw = cutDivRanges(seg.text, blocksBySegment[i])
            val (rowBody, rowAlign) = stripRowShell(bodyRaw)
            if (rowAlign != null) shellAlign = rowAlign
            text.append(readback[i])
            html.append(rowBody)
        } else {
            text.append(readback[i])
            html.append(styleStartTag(seg, defaultFontName))
            html.append(htmlEscape(visible[i]))
            html.append(styleEndTag(seg, defaultFontName))
        }
        if (openButtonEnd >= 0 && end >= openButtonEnd) {
            html.append("</button>")
            openButtonEnd = -1
        }
    }
    if (openButtonEnd >= 0) html.append("</button>")
    return ConsolePrintedLine(
        text.toString(),
        html.toString(),
        shellAlign ?: line.alignment,
        line.isLogicalLine,
        htmlParts,
        htmlBuilt = true,
    )
}

/**
 * 把 [blocks] 的区间从 [raw] 里剔除（半开化为端点裁剪，区间不重叠 —— sink 侧按开块序
 * 圈区间）。用于 HTML_GETPRINTEDSTR 的行体：原版对 div 节点无分支（HtmlManager.cs:446-464）。
 */
private fun cutDivRanges(raw: String, blocks: List<DisplayDivBlock>): String {
    if (blocks.isEmpty()) return raw
    val out = StringBuilder(raw.length)
    var pos = 0
    for (b in blocks) {
        val start = b.sourceRange.first.coerceIn(0, raw.length)
        val end = (b.sourceRange.last + 1).coerceIn(0, raw.length)
        if (start > pos) out.append(raw, pos, start)
        if (end > pos) pos = end
    }
    if (pos < raw.length) out.append(raw, pos, raw.length)
    return out.toString()
}

/**
 * GETDISPLAYLINE 的段回读：[blocks] 的区间替换成块重建（[divBlockReadback]），其余碎片
 * 走既有的逐节点回读（[htmlSegmentReadback]，img/shape 给 AltText）。
 *
 * 对应原版 `ConsoleDisplayLine.ToString()`（ConsoleDisplayLine.cs:127-140）的节点序：
 * 文本节点与 div 节点按父节点表的顺序拼接（div 在 HtmlManager.cs:646 进入父列表的位置
 * = `<div>` 开标签处），块前后文本与块**同行**。
 */
private fun divSplicedReadback(
    raw: String,
    blocks: List<DisplayDivBlock>,
    partsOut: MutableList<HtmlPrintRun>,
    fontSize: Long,
    focusColor: Int,
): String {
    if (blocks.isEmpty()) return htmlSegmentReadback(raw, htmlToPlainText(raw), partsOut, fontSize, focusColor)
    val sb = StringBuilder()
    var pos = 0
    for (b in blocks) {
        val start = b.sourceRange.first.coerceIn(0, raw.length)
        val end = (b.sourceRange.last + 1).coerceIn(0, raw.length)
        if (start > pos) {
            val frag = raw.substring(pos, start)
            sb.append(htmlSegmentReadback(frag, htmlToPlainText(frag), partsOut, fontSize, focusColor))
        }
        sb.append(divBlockReadback(b, partsOut, fontSize, focusColor))
        if (end > pos) pos = end
    }
    if (pos < raw.length) {
        val frag = raw.substring(pos)
        sb.append(htmlSegmentReadback(frag, htmlToPlainText(frag), partsOut, fontSize, focusColor))
    }
    return sb.toString()
}

/**
 * 一颗 div 块的 GETDISPLAYLINE 重建 = `ConsoleDivPart.ToString()`
 * （ConsoleDivPart.cs:221-233）：`altHeadTag` + 每条子行的 `BuildString` + `"\r\n"`
 * （**每条**子行后都带，含最后一条）+ `"</div>"`。
 *
 * 子行的回读走与行文本同一套逐节点口径（文本节点给文本、img/shape 给 AltText）；
 * 黄金锚：`pc-golden-root.txt:71`（标题屏五子棋入口，TITLE.ERB:81-85 经
 * `HTML_PRINT …, 1` 的缓冲路径）的头标签字节 `xpos='1600px' ypos='-937px'
 * width='180px' color='#363636' height='50px' padding='10px' border='2px'
 * radius='4px' bcolor='#A5A5A5'` —— 属性次序/省略规则/大小写全由
 * [com.eratw.port.core.host.DivSpec.altHeadTag] 逐字节复刻。
 */
private fun divBlockReadback(
    block: DisplayDivBlock,
    partsOut: MutableList<HtmlPrintRun>,
    fontSize: Long,
    focusColor: Int,
): String {
    val sb = StringBuilder(block.altHeadTag)
    for (child in block.children) {
        val plain = htmlToPlainText(child.html)
        sb.append(htmlSegmentReadback(child.html, plain, partsOut, fontSize, focusColor))
        sb.append("\r\n")
    }
    sb.append("</div>")
    return sb.toString()
}

/**
 * 一条 HTML 段行原文 → 去外壳行体（`DisplayLine2Html` 的逐节点重建口径，HtmlManager.cs:446-464）。
 *
 * 原版把 `<p align=…>` 与 `<nobr>` 折成**行级标志**（`HtmlAnalzeState.Alignment`/`FlagNobr` →
 * 每条显示行 `SetAlignment`，HtmlManager.cs:700-710；strict 判式只在行首放行这两枚标签，
 * :1093、:1109-1112），行体由 [displayLine2Html] 按 `needPandN` 唯一地补一层外壳
 * （:387-402、:478-482）。本仓 sink 的 HTML 段保留**原文**（含原文自带的那层外壳与
 * splitHtmlLines 的行首重放），原样交回就会让回读串带上**两层**外壳：
 * 再喂一次 `HTML_PRINT` 时，内层那枚 `<p>` 命中原版「开 `<p>` 查 `FlagNobr`」的复制粘贴判式
 * （:1113-1116，ADR-005 必须原样复刻）抛「<p>标签重复使用」，而外层那两枚闭标签被
 * [displayLine2Html] 补齐后还会再多出一对无主的 `</p>`/`</nobr>`（strict 判 `</p>` 未开过即抛
 * UnexpectedCloseTag，:985-997）。⇒ 交回前必须**剥掉 p/nobr 外壳**，行体只剩节点级标签
 * （font/样式/按钮/图元原样保留，与原版行体逐形一致）。
 *
 * 剥除按**顶层标签**判定：属性值引号内的尖括号不算标签边界（原版属性是 LiteralStringWord），
 * 所以 `<button title='a<p>b'>` 里的 `<p>` 随所属标签整段原样带走、不误删。标签名大小写不敏感
 * （`tagAnalyze` 先 ToLower，HtmlManager.cs:1163-1166）。无闭合 '>' 的孤立 '<' 当文本照抄
 * （与 sink 的 splitHtmlLines 同一宽容口径）。
 *
 * @return 去壳行体 + 从第一枚开 `<p align=…>` 读到的对齐；没有 `<p>`（或没写 align）时对齐为
 *   null，调用方回落到显示行自身的对齐快照。原版正是用 `lines[0].Align` 重建外壳（:387、:390-401）。
 */
private fun stripRowShell(raw: String): Pair<String, Alignment?> {
    val out = StringBuilder(raw.length)
    var align: Alignment? = null
    var i = 0
    val n = raw.length
    while (i < n) {
        if (raw[i] != '<') {
            out.append(raw[i])
            i++
            continue
        }
        var j = i + 1
        var inSingle = false
        var inDouble = false
        var closed = false
        while (j < n) {
            val c = raw[j]
            when {
                inSingle -> if (c == '\'') inSingle = false
                inDouble -> if (c == '"') inDouble = false
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == '>' -> closed = true
            }
            if (closed) break
            j++
        }
        if (!closed) {
            // 孤立 '<'：原版抛 CodeEE（HtmlManager.cs:597-599），此处与 sink 切行同口径当文本带走。
            out.append(raw, i, n)
            break
        }
        val tag = raw.substring(i, j + 1)
        when (htmlTagName(tag)) {
            "p" -> align = htmlAlignAttr(tag) ?: align // 外壳：剥除，对齐随第一枚开 p 折给行级标志
            "nobr" -> Unit // 外壳：剥除（原版 FlagNobr 不落行体）
            else -> out.append(tag)
        }
        i = j + 1
    }
    return out.toString() to align
}

/** 标签名：`<` 之后、跳过 `/`，读到 '>' 或空白为止，小写（原版 `tagAnalyze` 先 ToLower）。 */
private fun htmlTagName(tag: String): String {
    var s = 1
    if (s < tag.length && tag[s] == '/') s++
    var e = s
    while (e < tag.length && tag[e] != '>' && tag[e] != ' ' && tag[e] != '\t') e++
    return tag.substring(s, e).lowercase(Locale.ROOT)
}

/** `<p>` 的 `align='left|center|right'`（属性名与值都不区分大小写，值允许单/双引号或裸写）。 */
private val HTML_ALIGN_ATTR = Regex("(?i)\\balign\\s*=\\s*['\"]?\\s*(left|center|right)")

private fun htmlAlignAttr(tag: String): Alignment? =
    HTML_ALIGN_ATTR.find(tag)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let {
        when (it) {
            "center" -> Alignment.CENTER
            "right" -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
    }

/**
 * HTML 段的严格解析触发条件（正则口径见 [consolePrintedLine] 的两条 TODO(verify)）。
 *
 * 三个分支各自对应一处「按节点回读」的语义：
 * - `<img` / `<shape`：文本回读要给 AltText（FN-12）；
 * - `<font ... bcolor=`：分部背景要记账（FN-11）。
 *
 * 其余段（纯文本/样式/按钮/div）不解析，读数与 [htmlToPlainText] 逐字相同。
 * 标签名与属性名在原版都不区分大小写（`HtmlManager.tagAnalyze` 先 ToLower，
 * 属性名走 `OrdinalIgnoreCase`，HtmlManager.cs:1163-1166），故用 `(?i)`。
 */
private val HTML_NODE_TRIGGER = Regex("(?i)<(?:img|shape)\\b|<font\\b[^>]*\\bbcolor\\s*=")

/**
 * 单个 HTML 段的文本回读（`ConsoleDisplayLine.ToString()` 口径，FN-12）。
 *
 * 命中 [HTML_NODE_TRIGGER] 时按 [HtmlStrictParser] 的解析结果取文本：
 * - 有 `<img>`/`<shape>` 节点 → 用 [HtmlPrintRow.plainText]（各节点 ToString 顺序拼接，
 *   img/shape 给 AltText，ConsoleImagePart.cs:45-59、ConsoleShapePart.cs:16-45）；
 * - 只有 `<font bcolor>` → 文本仍用 [plain]（Html2PlainText），保证未涉及图元的行读数不变。
 *
 * 解析出的节点一律追加进 [partsOut]，供 [ConsolePrintedLine.htmlParts] 记录分部背景
 * （FN-11，`HtmlPrintStyle.buttonColor`）。`rows` 为空（空串）时什么也不加。
 *
 * [fontSize] / [focusColor] 原样透传给 [htmlPrintDocument]：重建 `<img>` AltText 的
 * 百分比折算与 `bcolor` 的省略判据都发生在那一次解析里（见 [consolePrintedLine] 的
 * 「配置注入面」节）。调用点必须传会话生效值，别让这里回落到出厂缺省。
 */
private fun htmlSegmentReadback(
    html: String,
    plain: String,
    partsOut: MutableList<HtmlPrintRun>,
    fontSize: Long,
    focusColor: Int,
): String {
    if (!HTML_NODE_TRIGGER.containsMatchIn(html)) return plain
    val rows = htmlPrintDocument(html, fontSize, focusColor).rows
    val parts = rows.flatMap { it.runs }
    partsOut.addAll(parts)
    val hasGraphic = parts.any { it.kind == HtmlRunKind.IMAGE || it.kind == HtmlRunKind.SHAPE }
    return if (hasGraphic) rows.joinToString("") { it.plainText } else plain
}

/**
 * 段样式开标签 = `HtmlManager.getStringStyleStartingTag`（HtmlManager.cs:864-915）。
 *
 * 判据与原文一致：字体名偏离 `Config.FontName`、前景偏离 `Config.ForeColor`
 * （原版 `ColorChanged`；EmueraConsole.cs:2823 写 SETCOLOR 时就是 `color != Config.ForeColor`）
 * 或字体样式位非零，三者全否时返回空串。位序照抄：先 `<s>`（删除线）、`<u>`（下划线）、
 * `<i>`（斜体）、`<b>`（粗体）；ERB 的 FONTSTYLE 实参位见 Process.cs:1510-1527。
 * `bcolor` 不输出：sink 的段没有该字段，且 ERB 侧只有 HTML 的 `<font bcolor>` 能改
 * `StringStyle.ButtonColor`（HtmlManager.cs:887-891）—— 非 HTML 部在原版恒等于
 * `Config.FocusColor`，本来就不写。HTML 部的分部背景记录在
 * [ConsolePrintedLine.htmlParts]（同一次解析给出的 [HtmlPrintStyle.buttonColor]），
 * 行体原文照旧带 `bcolor`。
 */
private fun styleStartTag(seg: LineBufferConsoleSink.Segment, defaultFontName: String): String {
    val faceChanged = seg.fontName != defaultFontName
    val colorChanged = seg.foreColor != LineBufferConsoleSink.DEFAULT_FORE_COLOR
    val bits = seg.styleMask
    if (!faceChanged && !colorChanged && bits == 0) return ""
    val sb = StringBuilder()
    if (faceChanged || colorChanged) {
        sb.append("<font")
        if (faceChanged) sb.append(" face='").append(htmlEscape(seg.fontName)).append('\'')
        if (colorChanged) sb.append(" color='#").append(hexRgb(seg.foreColor)).append('\'')
        sb.append('>')
    }
    if (bits and 4 != 0) sb.append("<s>")
    if (bits and 8 != 0) sb.append("<u>")
    if (bits and 2 != 0) sb.append("<i>")
    if (bits and 1 != 0) sb.append("<b>")
    return sb.toString()
}

/**
 * 段样式闭标签 = `HtmlManager.getClosingStyleStartingTag`（HtmlManager.cs:917-940）。
 *
 * 闭合序与开标签相反：`</b>`、`</i>`、`</u>`、`</s>`、`</font>`。开标签为空时这里也返回空串，
 * 保证成对。
 */
private fun styleEndTag(seg: LineBufferConsoleSink.Segment, defaultFontName: String): String {
    val faceChanged = seg.fontName != defaultFontName
    val colorChanged = seg.foreColor != LineBufferConsoleSink.DEFAULT_FORE_COLOR
    val bits = seg.styleMask
    if (!faceChanged && !colorChanged && bits == 0) return ""
    val sb = StringBuilder()
    if (bits and 1 != 0) sb.append("</b>")
    if (bits and 2 != 0) sb.append("</i>")
    if (bits and 8 != 0) sb.append("</u>")
    if (bits and 4 != 0) sb.append("</s>")
    if (faceChanged || colorChanged) sb.append("</font>")
    return sb.toString()
}

/**
 * `0xRRGGBB` → 六位大写十六进制（原版 `(R*65536+G*256+B).ToString("X6")`，
 * HtmlManager.cs:882-884）。用 `Locale.ROOT` 钉住十六进制字母的大小写与数字形。
 */
private fun hexRgb(rgb: Int): String = String.format(Locale.ROOT, "%06X", rgb and 0xFFFFFF)

// ---------------------------------------------------------------------------
// 取参助手（本文件私有；VarMethods/PureMethods 的同名助手可见性不动）
// ---------------------------------------------------------------------------

private fun argInt(args: List<Value?>, i: Int, fn: String): Long {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return (v as? Value.IntV)?.v ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是数值")
}

private fun argStr(args: List<Value?>, i: Int, fn: String): String {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn 缺少第 ${i + 1} 个参数")
    return (v as? Value.StrV)?.v ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是字符串")
}

// ---------------------------------------------------------------------------
// 字宽度量
// ---------------------------------------------------------------------------

/**
 * 单段纯文本的显示宽度（像素）。
 *
 * 原版：`ConsoleStyledString.SetWidth` → `StringMeasure.GetDisplayLength`
 * （ConsoleStyledString.cs:75-95、StringMeasure.cs:42-72），即 GDI+ 实测宽度。
 * **原版没有码位宽度表**——宽度直接问字体，所以本文件也不该有自己的表。
 *
 * 宿主未接线（[BuiltinHost.measureText] == null）时用等宽近似 [approxTextWidth]：
 * 全角 1 个 FontSize、半角 1 个 FontSize/2。默认 FontSize=18 时半角 = 9 px，
 * 与基准 `pc-golden-root.txt:297` 的 `HTML_STRINGLEN("<i>AB</i>CD") PLAIN=36` 吻合
 * （4 个 ASCII 半角）。
 *
 * 三段回落链（FN-04 core）：
 * 1. [BuiltinHost.measureTextRun]：带 `fontName` / `styleMask` / `charSpace` 的分段请求，
 *    生产由 Android 侧 `AndroidTextMeasure.measurePixels` 回答，粗体/斜体/`<font face>`
 *    各得独立读数（原版 `FontFactory.GetFont(style.Fontname, style.FontStyle)`，
 *    ConsoleStyledString.cs:25、StringMeasure.cs:42-72）；
 * 2. [BuiltinHost.measureText]：旧纯文本回调（保留给未升级的宿主，读数与改造前一致）；
 * 3. [approxTextWidth]：等宽近似（headless / 装置）。
 *
 * [style] 为 null（纯文本快速路径）时请求里的字体名为空串、样式位为 0，即原版缺省族 +
 * 常规体；这不改变第 2/3 段回落的读数。
 */
private fun measureText(c: BuiltinContext, s: String, style: HtmlPrintStyle? = null): Long {
    if (s.isEmpty()) return 0L
    // 请求里的 `fontSize` 必须是**配置字号**（`BuiltinHost.fontSize` 的 KDoc），不是真机
    // page-fit 实测字号：本函数的读数会被语料拿去和 `PRINTCLENGTH()*GETCONFIG("フォントサイズ")/2`
    // 相比、差值再原样写成 `<shape param='Npx'>` 补回同一行（HTML_PRINTC_MANAGER 排的指令
    // 按钮网格）。两把尺一分为二，每个按钮就只占应得宽度的一半上下，一行十几个整排错位
    // （2026-10-29 玩家反馈）。原版把量宽与排格绑成同一个 Config.FontSize
    // （`StringMeasure.cs:42-72` 用的就是 `Config.FontSize`），这里不许再解耦。
    val request = TextMeasureRequest(
        text = s,
        fontName = style?.fontName.orEmpty(),
        fontSize = c.host.fontSize,
        styleMask = style?.styleBits ?: 0,
    )
    c.host.measureTextRun(request)?.let { return it }
    c.host.measureText?.let { return it(s) }
    return approxTextWidth(s, c.host.fontSize)
}

/**
 * 等宽近似：半角步进 = [fontSize] 的一半，全角 = 一个 [fontSize]。
 * 本函数只做**格 → 像素**这一层换算，**宽度表唯一取自 [CellWidth.isFullWidth]**
 * （`core/host/CellWidth.kt`，口径 = CP932(Shift-JIS) 字节数）。
 * 本文件原先自带的那份 East Asian Wide/Fullwidth 近似表已随 WIDTH-SINGLE 收口删除。
 *
 * ## 为什么「字节数口径」能近似**像素**宽（这条口径与 [CellWidth] 的格数口径不是两回事）
 *
 * 原版自己就把两者绑在了一起：`calcPrintCWidth`（EmueraConsole.cs:3237-3243）把
 * 「PrintCLength 个 ASCII 空格」的 GDI 像素宽定义为 PRINTC 一列的上限，而同列的占用数
 * 按 Shift-JIS 字节数计（`CreateTypeCString` :3255），随后再用像素宽把多补的空格削回去
 * （右对齐 :3271-3275、左对齐 :3280-3288）。⇒ **「1 字节 ⇔ 1 个空格步进」是原版成立的前提**。
 * 而步进之所以等于 FontSize/2：同一函数 :3261 用 `new Font(name, Config.DefaultFont.Size,
 * style, GraphicsUnit.Pixel)` 造字体 —— 字号本来就是**像素**，日文字体里全角字形正好 1 em
 * （= FontSize px）、半角字形 0.5 em。于是 `像素 ≈ 格数 × FontSize/2`，基准把这条钉成了：
 * - `pc-golden-root.txt:253` 的 199 个 `=` ⇒ 半角步进 9px、可绘制宽度 1797px
 *   （`Config.cs:461-466` 的 `DrawableWidth = WindowX - max(2, FontSize/6)`，FontSize=18 见
 *   `ConfigData.cs:66`）；
 * - `:61` 的 99 个 `━`（U+2501，CP932 双字节 0x84AA）填满同一条宽度 ⇒ 单字 18px = 2 格。
 *
 * ## 口径分歧登记（收口后变大的一侧）
 *
 * Ambiguous 一类符号（`─ ━ │ ┃ ┼ ※ ① ± × ― “ ” … ■ □ ▲ ▼ ◆ ○ ● ★ ♪ ≦ ≧ ¢ § ¬ °`）
 * 旧表按 East Asian Width 的 Ambiguous 记 1 格（9px），现按 CP932 双字节记 2 格（18px）。
 * 逐点字节序列登记在 `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`
 * 的 A 类表（wave/d-width 路），本文件不复制第二份名单。
 * **判据只能回到 C# 原文**：基准里 FONT-STYLE 节（`pc-golden-root.txt:273-281`）只有
 * FONT/FONT_DEFAULT/CHKFONT，`:646-648` 的 GDRAWTEXT 只记返回值 1、GGETTEXTSIZE 零条，
 * HTML 节（`:293-303`）只有 ASCII 的 36px ⇒ **近似像素宽在基准没有直接对拍面**，
 * 只有 `:61` 那条横线能间接钉住它（`getStBar` 的填充走的是 `core/fn/DebugMiscMethods.kt`
 * 那侧同一个宽度模型，详见其 measureWidth 的登记）。间接面给出的结论是 2 格一侧与原版一致：
 * 可绘制宽度 1797px 下，旧表（单字 9px）会填满 **199 个** `━`（与 `:253` 的 199 个 `=` 同数，
 * 但 `━` 是双字节字、屏上单字 18px ⇒ 实占约两行），现口径（单字 18px）填 **99 个** ——
 * 与 `:61` 记录的 99 个 `━` 逐字对齐。故登记为**向原版靠拢**，不是新增偏差。
 *
 * TODO(verify) 两条：
 * 1. TAB（U+0009）不是字符而是制表位，原版 GRAPHICS 模式下先替换成 8 个空格再量
 *    （StringMeasure.cs:57-62），本函数按 [CellWidth] 记全角 2 格 = 18px；core/fn 侧
 *    没有 render 侧 `Grid.expandTabs` 那一步，卡点是基准里没有任何含 TAB 的像素读数。
 * 2. FontSize 为奇数时 `fontSize / 2` 向下取整，全角（= fontSize）不等于两个半角步进；
 *    默认 FontSize=18（ConfigData.cs:66）为偶数，故基准环境不受影响。
 */
private fun approxTextWidth(s: String, fontSize: Long): Long {
    var width = 0L
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        width += if (CellWidth.isFullWidth(cp)) fontSize else fontSize / 2
        i += Character.charCount(cp)
    }
    return width
}

// ---------------------------------------------------------------------------
// HtmlManager 移植（reference/emuera-cs/MinorShift.Emuera.UI.Game/HtmlManager.cs）
// ---------------------------------------------------------------------------

/**
 * `HtmlManager.HtmlLength`（HtmlManager.cs:168-182）：第一**显示行**的总像素宽。
 *
 * 原版把整串解析成 `ConsoleDisplayLine[]` 后只累加 `array[0].Buttons[].Width`（:174-181），
 * 也就是说遇到换行（`<br>` 或 `\n`）即止，后面的内容不计。**但解析本身覆盖整串**：
 * 换行之后仍有语法错误时，`html2DisplayLine` 照样抛 CodeEE（:545-666），错误在
 * `tagAnalyze`（:951-1678）与串尾校验（:667-674）里。故本实现：
 * - 纯文本（无 `<`、`&`、`\n`）走快速路径，直接度量；
 * - 其余交给 [HtmlStrictParser] 做一遍 O(n) 的「校验 + 量宽」，畸形输入抛与原版同文案的
 *   CodeEE（ADR-005：默认语义 = 原版语义）。
 *
 * 文本段先 Unescape 再度量（原版在 html2DisplayLine :558/:572 对文本段调用 Unescape）。
 *
 * TODO(verify)：`div` 的版面语义不模拟（只做语法校验 + 子层不按可绘制宽折，
 * `SubDivisionWidth` 那条是 FN-02），内容按普通文本累计。
 *
 * FN-01 / FN-03 已在 `HtmlStrictParser` 落地（图元按 `ConsoleImagePart` / `ConsoleShapePart`
 * 折成格宽；按 `Config.DrawableWidth` 折行取第一条显示行）。本函数只负责把**现值**接进去：
 * 字号取 `BuiltinHost.fontSize`，可绘制宽取 [drawableWidthPx]。快速路径因此只在「纯文本且
 * 量得不超可绘制宽」时短路 —— 原版读的是第一条显示行的宽（HtmlManager.cs:168-182），
 * 超长的无标签串照样会折，必须交给解析器。
 */
private fun htmlLength(c: BuiltinContext, s: String, drawablePx: Long = drawableWidthPx(c)): Int {
    if (s.indexOf('<') < 0 && s.indexOf('&') < 0 && s.indexOf('\n') < 0) {
        val plain = measureText(c, s)
        if (plain <= drawablePx) return plain.toInt()
    }
    return HtmlStrictParser(
        s,
        { measureText(c, it) },
        { htmlUnescape(it) },
        fontSize = c.host.fontSize,
        drawableWidthPx = drawablePx,
        collectRows = false,
        // 每个文本段按自己的样式快照独立量宽（FN-04 core）；未接线时回落到上面的
        // measureText（旧回调 → 等宽近似），读数不变。
        measureStyled = { text, style -> measureText(c, text, style) },
        // IMG-A4 I1：图元尺寸查表（无 width 的 <img> 按图元真实宽高比折算宽）。
        // 宿主缺省 null = 图元缺失路径，量 AltText 的读数不变（ConsoleImagePart.cs:61-64）。
        imageSizeOf = c.host.imageSizeOf,
    ).length()
}

/**
 * 语句层（HTML_PRINTC / HTML_PRINTLC）的像素量宽入口：与 [htmlLength] **同一把尺**。
 *
 * 暴露成 `internal` 而不是复制一份近似：语句层补齐用的宽度必须与建内建量宽时的读数同源，
 * 否则「量宽用 A、补齐用 B」就是两条尺（`proc/GameSession.kt:378-393` 登记过这类分叉的
 * 后果：每个按钮只占应得宽度的一半上下）。
 */
internal fun htmlPixelWidth(c: BuiltinContext, s: String): Long = htmlLength(c, s).toLong()

/**
 * `Config.DrawableWidth` 的现值（`Config.cs:461-466`：窗口宽减去 `shapePositionShift`）。
 *
 * 式子不在此重写 —— 复用 [EffectiveConfig.drawableWidth]，避免与 `core/config` 分叉成第二把尺。
 *
 * **不做跨调用缓存**：`store.aux` 的槽名进 L3 状态哈希（`l3/StateHash.kt:269`、`:358-361`），
 * 新开一个槽就等于改基准读数；热路径（`HTML_SUBSTRING` 逐字符调 [htmlLength]，
 * HtmlManager.cs:184-204）改由调用点**每次函数调用算一次**再传下来。
 * 条目表本身已有 `ConfigMethods.store(c)` 的每 VM 缓存，这里只是折算一遍减法与夹取。
 */
private fun drawableWidthPx(c: BuiltinContext): Long =
    EffectiveConfig.of(ConfigMethods.store(c)).drawableWidth.toLong()

/**
 * `HtmlManager.GetSubStr`（HtmlManager.cs:184-204）：在像素预算 [lmax] 内贪心吃字符。
 *
 * 每个字符都重新度量 `prefix + 该字符 + suffix`（:190）—— 前缀/后缀是当前未闭合的样式标签，
 * 保证「斜体字更宽」这类效果被计入。返回 (吃掉的字符数, 剩余预算)。
 * 一个字符都放不下时返回 0（`i` 回退到 -1 再 +1，:194-200）。
 */
private fun getSubStr(
    c: BuiltinContext,
    prefix: String,
    suffix: String,
    s: String,
    lmax0: Int,
    drawablePx: Long,
): Pair<Int, Int> {
    var consumed = 0
    var lmax = lmax0
    var i = 0
    while (i < s.length) {
        val w = htmlLength(c, prefix + s.substring(i, i + 1) + suffix, drawablePx)
        if (consumed + w > lmax) {
            i--
            break
        }
        consumed += w
        i++
    }
    if (i == s.length) i--
    lmax -= consumed
    return (i + 1) to lmax
}

/**
 * `HtmlManager.HtmlSubString`（HtmlManager.cs:206-326）：按显示宽度截断，返回 (首段, 剩余段)。
 *
 * 逐字照抄原版流程（含 goto 的等价改写）：
 * 1. 预算换算：`length * Config.FontSize / 2`（:250）—— 参数单位是「全角字数」。
 * 2. 先 Unescape（:251）：实体引用在截断前还原成字符，故 `&lt;` 不会被当成标签。
 * 3. 文本段：用当前未闭合的样式标签做前后缀度量（:219-243）。
 * 4. 标签段：`</x>` 弹栈（:260-265）；`<br>` 直接结束（:276-278, :306）；
 *    `<img>`/`<shape>` 从预算里扣掉图片宽度（:279-300）；其余标签压栈并原样保留（:301-304）。
 * 5. 组装：首段 = 原文前缀 + 补全的闭合标签（:315-319）；
 *    剩余段 = 未闭合标签的开启标签 + 原文剩余（:320-324）。
 *
 * 与原版一致的「怪」行为（照抄，不修）：
 * - 文本段截断时 `num2 += subStr + 1`（:244）会多前进一格，靠 `Substring(num2 - 1, ...)`
 *   （:310, :324）补回来；预算耗尽点因此落在「最后一个被吃掉的字符」之后。
 * - 闭合标签一律弹栈（:260-263），不校验标签名是否匹配。原版弹空栈会抛
 *   InvalidOperationException；此处弹空栈时静默跳过（避免整段脚本崩掉，属有意偏差）。
 *   TODO(verify)：基准里没有「以闭合标签开头」的用例，等出现再定是否改成抛 CodeEE。
 *
 * TODO(verify)：`length` 是 int 运算（原版 `(int)` 强转 + 32 位乘法），极大值下的溢出行为未对齐。
 */
private fun htmlSubString(c: BuiltinContext, raw: String, length: Long): Pair<String, String> {
    val open = ArrayDeque<HtmlTag>()
    val close = ArrayDeque<HtmlTag>()
    var budget = (length * c.host.fontSize / 2).toInt()
    // FN-03：可绘制宽每次**函数调用**折算一次，逐字符的 getSubStr 复用同一个读数
    // （HtmlManager.cs:184-204 是逐字符调 HtmlLength 的，见 [drawableWidthPx] 为什么不缓存进 aux）
    val drawablePx = drawableWidthPx(c)
    val str = htmlUnescape(raw)
    var num = -1
    var num2 = 0
    var brLen = 0
    var flag = false

    loop@ while (true) {
        num = str.indexOf('<', num2)
        if (num != num2) {
            var prefix = ""
            var suffix = ""
            // C# foreach over Stack 是「栈顶 → 栈底」
            for (k in open.indices.reversed()) {
                if (open[k].isStyleTag) prefix += open[k].tag
            }
            for (k in close.indices.reversed()) {
                if (close[k].isStyleTag) suffix += close[k].tag
            }
            val run = if (num >= 0) str.substring(num2, num) else str.substring(num2)
            val (taken, left) = getSubStr(c, prefix, suffix, run, budget, drawablePx)
            budget = left
            num2 += taken + 1
            flag = true
            if (num < 0 || taken < run.length) break@loop
        } else {
            num2++
        }

        num = str.indexOf('>', num2)
        if (num <= 0) break@loop
        if (str[num2] == '/') {
            open.removeLastOrNull()
            close.removeLastOrNull()
            num2 = num + 1
            continue@loop
        }
        // 原版 `str.IndexOf(' ', num2, num - num2)`（:266-270）：只在 [num2, num) 内找空格，找不到用 num。
        val space = str.indexOf(' ', num2)
        val nameEnd = if (space < 0 || space > num) num else space
        val name = str.substring(num2, nameEnd)
        when (name) {
            "br" -> {
                brLen = 1
                break@loop
            }
            "img", "shape" -> {
                val tagWidth = htmlLength(c, str.substring(num2 - 1, num + 1), drawablePx)
                budget -= tagWidth
                if (!(budget < 0 && flag)) {
                    num2 = num + 1
                    continue@loop
                }
                break@loop
            }
            else -> {
                val isStyle = name == "b" || name == "i" || name == "s"
                open.addLast(HtmlTag("<" + str.substring(num2, num) + ">", isStyle))
                close.addLast(HtmlTag("</$name>", isStyle))
                num2 = num + 1
                continue@loop
            }
        }
    }

    if (num2 == 0) return "" to str
    var head = str.substring(0, num2 - 1)
    while (close.isNotEmpty()) head += close.removeLast().tag
    var tail = ""
    while (open.isNotEmpty()) tail = open.removeLast().tag + tail
    tail += str.substring(num2 - 1 + brLen * 4)
    return head to tail
}

/** HtmlSubString 的栈元素（HtmlManager.cs:12-27 的 HTMLI）。 */
private class HtmlTag(val tag: String, val isStyleTag: Boolean)

/**
 * `HtmlManager.Html2PlainText`（HtmlManager.cs:714-717）：
 * 先按正则 `\<[^<]*\>` 删标签，再 Unescape。
 *
 * 正则口径照抄：`[^<]*` 不允许标签体内再出现 '<'，故 `"<a<b>"` 只会删掉 `"<a<b>"` 里的
 * `"<b>"`（与原版 .NET Regex 一致）；`<` 后紧跟 `>`（`"<>"`）也会被删。
 */
private fun htmlToPlainText(s: String): String = htmlUnescape(HTML_TAG_REGEX.replace(s, ""))

/** 原版正则字面量 `"\\<[^<]*\\>"`（HtmlManager.cs:716）。 */
private val HTML_TAG_REGEX = Regex("<[^<]*>")

/**
 * `HtmlManager.Escape`（HtmlManager.cs:719-742）：单趟扫描，替换 `& > < " '` 五个字符。
 *
 * 替换表见 HtmlManager.cs:372-378，顺序无关（逐字符查表，不是顺序 replace）。
 * 注意 `>` 与 `<` 都会转义，且 `'` 用 `&apos;`（不是 `&#39;`）。
 *
 * 可见性 internal：宿主构造 [ConsolePrintedLine.html] 时应当用同一口径转义行体文本，
 * 避免两处转义表漂移（E2E 装置 / Android 控制台都可直接复用）。
 */
internal fun htmlEscape(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val rep = HTML_ESCAPE_TABLE[ch]
        if (rep == null) sb.append(ch) else sb.append(rep)
    }
    return sb.toString()
}

/** HtmlManager.cs:372-378 的 rep/repDic。 */
private val HTML_ESCAPE_TABLE: Map<Char, String> = mapOf(
    '&' to "&amp;",
    '>' to "&gt;",
    '<' to "&lt;",
    '"' to "&quot;",
    '\'' to "&apos;",
)

/**
 * `HtmlManager.Unescape`（HtmlManager.cs:744-834）：还原实体引用。
 *
 * 支持 `&nbsp; &amp; &gt; &lt; &quot; &apos;`（先 `ToLower`（:780）再 `switch`（:781-801））与数字引用
 * `&#123;` / `&#x7B;`（:802-831，仅十进制/十六进制，范围 0..65535）。
 *
 * 进制由**实体的第二个字符**决定（:807-817）：只有 `&#x……` 才把 `fromBase` 置 16 并砍掉 `#x`，
 * 其余一律砍掉 `#` 后按**十进制**送 `Convert.ToInt32(text, fromBase)`（:821）。于是
 * `&#x0x41;` 的 digits 是 `0x41` 且 base 为 16 → 还原成 `A`（`netParseInt32` 认 `0x` 前缀），
 * 而 `&#0x41;` 的 digits 同样是 `0x41` 但 base 仍是 10 → 解不出 → 「不是有效的转义字符」
 * （原版就是这个不对称，ADR-005 不修，测试已钉住）。
 * `&#xFFFFFFFF;` 同理走 base 16、digits = `ffffffff` → `Convert` 的 32 位回绕给 `-1`（:827-830）
 * → 「超出范围」分支，不是「不是有效的转义字符」。
 *
 * 错误分支照抄原版（都抛 CodeEE，文案取 lang.emuera-zhs.xml:799-802 的模板）：
 * - 找不到 ';'（:770-775）→ `没有找到与'&'对应的';'`
 * - '&' 后紧接 ';'（:776）→ `连续出现'&'与';'`
 * - 名字既不是内置实体也不以 '#' 开头（:803-806）→ `&{0};不是有效的转义字符`
 * - 数字解析失败（:818-826）→ 同上
 * - 超出 0..65535（:827-830）→ `&{0};超过Unicode的范围(不支持代理对)`
 */
private fun htmlUnescape(str: String): String {
    var num = 0
    if (str.indexOf('&', num) < 0) return str
    val sb = StringBuilder(str.length)
    while (num < str.length) {
        val amp = str.indexOf('&', num)
        if (amp < 0) {
            sb.append(str, num, str.length)
            break
        }
        if (amp > num) sb.append(str, num, amp)
        num = amp
        val semi = str.indexOf(';', num)
        if (semi <= num + 1) {
            if (semi < 0) throw CodeEE("没有找到与'&'对应的';'")
            throw CodeEE("连续出现'&'与';'")
        }
        val body = str.substring(num + 1, semi)
        num = semi + 1
        val lower = body.lowercase(Locale.ROOT)
        when (lower) {
            "nbsp" -> { sb.append(' '); continue }
            "amp" -> { sb.append('&'); continue }
            "gt" -> { sb.append('>'); continue }
            "lt" -> { sb.append('<'); continue }
            "quot" -> { sb.append('"'); continue }
            "apos" -> { sb.append('\''); continue }
        }
        var radix = 10
        if (lower[0] != '#') throw CodeEE("&$body;不是有效的转义字符")
        val digits = if (lower.length > 1 && lower[1] == 'x') {
            radix = 16
            lower.substring(2)
        } else {
            lower.substring(1)
        }
        val code = netParseInt32(digits, radix) ?: throw CodeEE("&$body;不是有效的转义字符")
        if (code < 0 || code > 65535) throw CodeEE("&$body;超过Unicode的范围(不支持代理对)")
        sb.append(code.toChar())
    }
    return sb.toString()
}

/**
 * `Convert.ToInt32(str, fromBase)`（.NET Framework 4.8，`ParseNumbers.IsTight`）的移植。
 *
 * 调用点：数字实体（HtmlManager.cs:821）、`#RRGGBB` 的十六进制段（:1692）、
 * 「裸十六进制名」探测（:1714）。三处共用同一套 `.NET` 口径，不能各写一份 `toIntOrNull`。
 *
 * 实测口径（Windows PowerShell 5.1 / .NET Framework 4.8，`[Convert]::ToInt32(s, b)`）：
 * - **不接受任何空白**（`" 10"` / `"10 "` / `"0x10 "` 全部失败）——与 `int.TryParse` 不同；
 * - 可选前导 `+`（`"+10"` / `"+0x10"` → 16）；**十六进制不接受 `-`**（`"-10"` base 16 → 失败），
 *   十进制接受（`"-65"` → -65）；
 * - 十六进制可选 `0x`/`0X` 前缀（`"0x90EE90"` → 9498256，`"0X10"` → 16；`"&h10"` 在本框架上失败）；
 * - 十六进制按 **32 位无符号**回绕（`"FFFFFFFF"` → -1、`"80000000"` → Int32.MinValue），
 *   超过 8 位有效位（`"0xFFFFFFFFF"`、`"123456789"`）→ 失败；
 * - 十进制必须落在 `Int32` 内，超界（`"2147483648"`）→ 失败；前导零不限（`"065"` → 65）；
 * - **只认 ASCII 数字**：全角 `０`（U+FF10）、阿拉伯-印度 `٠`（U+0660）一律 FormatException，
 *   而 JDK 的 `Character.digit` 对它们返回 0（Temurin 17 实测），所以本函数**不能**用
 *   `Character.digit` / `Char.digitToIntOrNull` 顶替，见 [asciiDigit]；
 * - 本层三个调用点只送 10 或 16，其它 `fromBase` 原版直接 `ArgumentException`（:821 之外无调用），
 *   这里一并判失败。
 *
 * 返回 null = 原版抛 `FormatException`/`OverflowException`/`ArgumentException` 的三类失败。
 * **不能**用 `String.toIntOrNull(radix)` 顶替：Kotlin 版不认 `0x` 前缀、却认十六进制负号，
 * 两个方向都会与原版分叉（语料 `DLC/透视图优化.ERB:11-12` 的 `<font color='#0x696969'>`
 * 在 Kotlin 版下会被误判成「无法被解析为数值」）。
 */
internal fun netParseInt32(s: String, radix: Int): Int? {
    if (s.isEmpty()) return null
    if (radix != 10 && radix != 16) return null
    var i = 0
    var negative = false
    if (s[0] == '+' || s[0] == '-') {
        if (radix == 16) {
            if (s[0] == '-') return null // 实测：「-10」base 16 → ArgumentException
        } else {
            negative = s[0] == '-'
        }
        i = 1
    }
    if (radix == 16 && i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X')) i += 2
    if (i >= s.length) return null
    var mag = 0L
    while (i < s.length) {
        val d = asciiDigit(s[i], radix)
        if (d < 0) return null // 空白、符号、0x 之后的非数字都在这里失败
        mag = mag * radix + d
        if (mag > 0xFFFFFFFFL) return null // 16 进制按 uint32 上限；10 进制在下方按 Int32 判
        i++
    }
    if (radix == 16) return mag.toInt() // 32 位回绕：0xFFFFFFFF → -1
    val signed = if (negative) -mag else mag
    if (signed < Int.MIN_VALUE || signed > Int.MAX_VALUE) return null
    return signed.toInt()
}

/**
 * `Convert.ToInt32` 的数字表：**只有 ASCII `0-9` / `a-f` / `A-F`**（PowerShell 5.1 实测
 * 全角与阿拉伯-印度数字均 FormatException）。`Character.digit` 按 Unicode 十进制数值返 0，
 * 用它会把 `&#x１;` 这类串解成合法实体、与原版分叉，故这里自己判区间。
 */
private fun asciiDigit(c: Char, radix: Int): Int {
    val d = when {
        c in '0'..'9' -> c - '0'
        radix == 16 && c in 'a'..'f' -> c - 'a' + 10
        radix == 16 && c in 'A'..'F' -> c - 'A' + 10
        else -> return -1
    }
    return if (d < radix) d else -1
}

/**
 * `HtmlManager.DisplayLine2Html`（HtmlManager.cs:381-484），只保留「行文本」这一层。
 *
 * [needPandN] = true 时加外壳：`<p align='left|center|right'><nobr>` … `</nobr></p>`
 * （:387-400、:473-477）；对齐只取**第一行**的（:387）。多行之间用 `<br>` 连接（:409-412）。
 * [needPandN] = false 时（HTML_POPPRINTINGSTR）只有行体本身。
 *
 * 行体里的按钮/样式标签由宿主在 [ConsolePrintedLine.html] 里给出（对应 :415-464
 * 对每个 ConsoleButtonString / ConsoleStyledString 的输出，含 `Escape` 后的文本）。
 */
private fun displayLine2Html(lines: List<ConsolePrintedLine>, needPandN: Boolean): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    if (needPandN) {
        when (lines[0].align) {
            Alignment.LEFT -> sb.append("<p align='left'>")
            Alignment.CENTER -> sb.append("<p align='center'>")
            Alignment.RIGHT -> sb.append("<p align='right'>")
        }
        sb.append("<nobr>")
    }
    for ((i, line) in lines.withIndex()) {
        if (i != 0) sb.append("<br>")
        // 原版每段都从节点重新拼（:446-464 对 ConsoleStyledString 逐个 Escape），
        // 所以宿主只给了 text、没给行体 HTML 时，回退到 Escape(text) 而不是空串
        // —— 否则 HTML_GETPRINTEDSTR 会吐出没有内容的 <nobr></nobr>。
        // [ConsolePrintedLine.htmlBuilt] 的行不算这一档：div 块被剔除后的空行体就是
        // 原版语义（块节点在 DisplayLine2Html 里没有输出分支），回落 Escape(text) 会把
        // GETDISPLAYLINE 的块重建文本错吐进 HTML_GETPRINTEDSTR。
        sb.append(if (line.html.isEmpty() && !line.htmlBuilt) htmlEscape(line.text) else line.html)
    }
    if (needPandN) sb.append("</nobr></p>")
    return sb.toString()
}

/**
 * `EmueraConsole.GetDisplayLines`（EmueraConsole.cs:3546-3576）的下标选择部分。
 *
 * 从最新一行往回扫，`num` 数「逻辑行」；`num == lineNo` 的行全部收进结果（同一逻辑行的
 * 折行片段都会被收），`num > lineNo` 即停。因此 `lineNo = 0` = 最近一条逻辑行。
 * 越界（`lineNo > Count`，原版注意是 `>` 不是 `>=`，:3548）或没扫到任何行 → 空表
 * （原版返回 null，调用方转成 ""，:6104-6107）。
 */
private fun pickDisplayLines(lines: List<ConsolePrintedLine>, lineNo: Long): List<ConsolePrintedLine> {
    if (lineNo < 0 || lineNo > lines.size.toLong()) return emptyList()
    val out = ArrayList<ConsolePrintedLine>()
    var num = 0L
    for (i in lines.indices.reversed()) {
        // 取一次：consoleLines 是惰性视图（consolePrintedLines），逐项访问会重建该行。
        val line = lines[i]
        if (num == lineNo) out.add(0, line)
        if (line.isLogicalLine) num++
        if (num > lineNo) break
    }
    return out
}
