package com.eratw.port.core.fn

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.CellWidth

/**
 * `HTML_PRINT` 族要输出的**显示行**数据模型（`HtmlManager.html2DisplayLine` 的产物）。
 *
 * 语义权威：`reference/emuera-cs/MinorShift.Emuera.UI.Game/HtmlManager.cs`（下称 HM）
 * 与 `.../EmueraConsole.cs` 的 `PrintHtml`（:3203-3227）。原版的产物是
 * `ConsoleDisplayLine[]`，每行由若干 `ConsoleButtonString` 组成，每个按钮串里又是
 * `AConsoleDisplayNode[]`（`ConsoleStyledString` / `ConsoleImagePart` / `ConsoleShapePart` /
 * `ConsoleDivPart`）。本文件把「一行里玩家能看到什么」压成三层，**不复制**渲染层的
 * `TextRun`/`DisplayLine`（那是 `render` 模块的模型，本模块不依赖它）：
 *
 * - [HtmlPrintDoc]：一次 `HTML_PRINT` 产出的全部显示行（原版 `Html2DisplayLine` 的返回值，
 *   HM :517-527）。**行数 = `<br>`/裸换行切出的段数**，见 [HtmlPrintDoc] 的说明。
 * - [HtmlPrintRow]：一条显示行，带对齐（原版 `SetAlignment`，HM :700-710）与
 *   `nobr` 标记（原版 `FlagNobr`，传给 `PrintStringBuffer.ButtonsToDisplayLines`，HM :698）。
 * - [HtmlPrintRun]：行内一段同一样式的文本 / 图片 / 图形 / 浮动块。
 *
 * 与数据模型的关系（契约「不改数据模型」）：本文件只新增类型，**不动**
 * `core/host/LineBufferConsoleSink` 的 `DisplayLine`/`Segment`，也不动 `render` 的
 * `TextRun`。宿主接线时把 [HtmlPrintRow] 翻成既有 `DisplayLine` 即可（一行一段或多段），
 * 不需要新字段；`kind`/`button`/`style` 都已有对应的既有字段。
 *
 * C# 出处汇总：
 * - 行切分：`HtmlManager.cs:650-666`（`FlagBr` → `list2.Add(null)`）+
 *   `PrintStringBuffer.cs:207-262`（null 标记处结行，末尾残留非空才再结一行）。
 * - 对齐/nobr 落盘：`HtmlManager.cs:698-710`。
 * - 文本段样式：`HtmlAnalzeState.GetSS`（HM :128-150）。
 * - 按钮组：`HtmlManager.cssToButton`（:836-854）与 `tagAnalyze` 的 `button`/`nonbutton`
 *   分支（:1476-1563）。
 *
 * 本对象只放**样式位常量**（原版 `FontStyle` 的位值）；模型入口见 [HtmlPrintDoc]，
 * 产出入口是 `HtmlMethods.htmlPrintDocument`。
 *
 * 注意编码与 `LineBufferConsoleSink.Segment.styleMask` **不同**：后者存的是 ERB `FONTSTYLE`
 * 语句的实参位（`Process.cs:1512-1527`：1=Bold、2=Italic、**4=Strikeout、8=Underline**），
 * 而本对象是 `System.Drawing.FontStyle` 的枚举值（**4=Underline、8=Strikeout**）。
 * 接线换算：sink 掩码 = (b?1:0) | (i?2:0) | (s?4:0) | (u?8:0)。
 */
object HtmlStyleBits {
    /** `FontStyle.Bold`（HM :1062）。 */
    const val BOLD: Int = 1

    /** `FontStyle.Italic`（HM :1065）。 */
    const val ITALIC: Int = 2

    /** `FontStyle.Underline`（HM :1068）。 */
    const val UNDERLINE: Int = 4

    /** `FontStyle.Strikeout`（HM :1058 的初值即 Strikeout）。 */
    const val STRIKE: Int = 8
}

/** [HtmlPrintRun] 的种类，对应原版四种 `AConsoleDisplayNode` 子类。 */
enum class HtmlRunKind {
    /** `ConsoleStyledString`（ConsoleStyledString.cs:21-40）：真正上屏的文本。 */
    TEXT,

    /** `ConsoleImagePart`（ConsoleImagePart.cs:117-124）：`ToString()` 回 `AltText`。 */
    IMAGE,

    /** `ConsoleShapePart`（ConsoleShapePart.cs:82-89）：`ToString()` 回 `AltText`。 */
    SHAPE,

    /** `ConsoleDivPart`（ConsoleDivPart.cs:221-231）：自带一组子行。 */
    DIV,
}

/**
 * 一段文本的样式快照（原版 `StringStyle`，StringStyle.cs:7-56）。
 *
 * 三个可空字段**只有 [fore] 的口径是「HTML 有没有显式指定」**：原版 `GetSS`（HM :128-150）用
 * `HtmlAnalzeStateFontTag.Color >= 0` 判断是否设过色，一旦设过就置 `ColorChanged = true`
 * （即使指定的颜色恰好等于 `Config.ForeColor`），于是 `DisplayLine2Html` 会为它补一个
 * `<font color='#……'>`（HM :881-886）。[buttonColor] 与 [fontName] 不同 —— 原版的重建文案
 * 还要再比一次值（见各自条目），本模型把那次比较提前折进来。
 * - [fore] null = 未指定（回 `Config.ForeColor`），否则 `0xRRGGBB`。
 *   「指定过就算」——对应 `ColorChanged`，即使指定值恰好等于缺省也非 null。
 * - [buttonColor] null = **值等于 `Config.FocusColor`**（原版重建时整条省略，HM :887-891），
 *   否则 `0xRRGGBB`。口径是**值比较**而不是「有没有写」：`bcolor='#FFFF00'` 在缺省
 *   FocusColor 下与没写等价（`StringStyle` 只存值，`getStringStyleStartingTag` 再比一次）。
 * - [fontName] null = 未指定或显式空串（都回 `Config.FontName`）；空串按原版归一成默认字体
 *   （StringStyle.cs:41-44，`string.IsNullOrEmpty` 分支），本模型不再保留 `""`。
 *   与原版已知差：显式写成 `Config.FontName` 本身（HM :875 `Fontname != Config.FontName` 才写 face）
 *   原版会省略 `face`，本层拿不到 `Config.FontName` 故照写 —— 语料无此形态，接线见交付报告。
 * - [styleBits] 取 [HtmlStyleBits] 的位或。
 */
data class HtmlPrintStyle(
    val fore: Int? = null,
    val buttonColor: Int? = null,
    val fontName: String? = null,
    val styleBits: Int = 0,
)

/**
 * 行内一段所属的按钮组（原版 `HtmlAnalzeStateButtonTag`，HM :73-90 + `cssToButton` :836-854）。
 *
 * 原版把「样式节点」整段包进一个 `ConsoleButtonString`，按钮的输入值/标题只挂在它上面；
 * 本模型把按钮信息挂在每个 [HtmlPrintRun] 上，同一组内的 run 共享同一份描述。
 *
 * 官方怪癖（照抄）：
 * - `<button>` **没有** `value` 属性时 `IsButton = text6 != null` 为假（HM :1547），
 *   即它在视觉上是普通文本，但仍是一个按钮组（`IsButtonTag = true`，:1557）。
 * - `<clearbutton>` 打开之后出现的 `<button>` 一律降级成非按钮（HM :1549-1551），
 *   并在 `notooltip='true'` 时把标题清空（:1552-1555）。
 * - [valueIsInt] 是 `long.TryParse` 的结果（HM :1543）；解析失败时 [valueInt] 为 0
 *   （`result3` 的缺省），字符串形态仍留在 [value]。
 */
data class HtmlPrintButton(
    val isButton: Boolean,
    val value: String? = null,
    val valueIsInt: Boolean = false,
    val valueInt: Long = 0L,
    val title: String? = null,
    val pos: Int? = null,
)

/**
 * 行内一段（原版 `AConsoleDisplayNode`）。
 *
 * [text] 的口径按原版 `ToString()`：
 * - [HtmlRunKind.TEXT]：`ConsoleStyledString.Text`，即 **Unescape 之后**的可见文本
 *   （HM :558/:572 对每个文本段先 Unescape）。
 * - [HtmlRunKind.IMAGE]/[HtmlRunKind.SHAPE]：原版 `AltText`，即**重建**出来的标签文案
 *   （`ConsoleImagePart.cs:45-59`、`ConsoleShapePart.cs:16-45`；不是输入原文）。两处对 MixedNum
 *   的口径**不同**：img 走 `Utils.AddTagMixedNumArg`（Utils.cs:364-371）→ `BuilderString`
 *   按 FontSize 归一；shape 直接拼 `param[i].num` 原值（:20-31，不归一）。属性次序见那两处。
 *   玩家看到的是图/图形，但 `GETDISPLAYLINE` 与日志走
 *   `ConsoleDisplayLine.ToString()`（ConsoleDisplayLine.cs:120-133），拿到的正是这条 AltText。
 * - [HtmlRunKind.DIV]：空串；浮动块自己的行在 [divRows]（ConsoleDivPart.Children）。
 */
data class HtmlPrintRun(
    val kind: HtmlRunKind,
    val text: String,
    val style: HtmlPrintStyle = HtmlPrintStyle(),
    val button: HtmlPrintButton? = null,
    val divRows: List<HtmlPrintRow> = emptyList(),
)

/**
 * 一条显示行（原版 `ConsoleDisplayLine`，ConsoleDisplayLine.cs:10-...）。
 *
 * [align] 是**整次 HTML 解析的终态对齐**：原版在 :700-710 把 `state.Alignment` 写给
 * 本次产出的每一条显示行，而不是逐行记录，故同一次 `HTML_PRINT` 的多行共享一个对齐值。
 * [noBreak] 同理取自终态 `FlagNobr`（:698 传入 `ButtonsToDisplayLines`）。
 *
 * 三个取文本的访问器对应三个不同口径，测试与消费方不要混用：
 * - [plainText]：原版 `ConsoleDisplayLine.ToString()` 的近似 —— 所有 run 的 [HtmlPrintRun.text]
 *   顺序拼接（含图片/图形的重建 AltText），DIV 段递归到子行。
 * - [visibleText]：**玩家读到的字符** —— 只取 TEXT 段（图片/图形不产生字符），DIV 段
 *   递归到子行的可见文本（浮动块里的字玩家照样看得见）。
 * - [hasVisibleText]：该行是否有任何可见字符（空行判定用）。
 * - [cells]：**格数**（半角 = 1、全角 = 2），口径 [CellWidth]。
 */
data class HtmlPrintRow(
    val runs: List<HtmlPrintRun>,
    val align: Alignment = Alignment.LEFT,
    val noBreak: Boolean = false,
) {
    /** 原版 `ConsoleDisplayLine.ToString()` 口径（含 AltText 与 DIV 子行）。 */
    val plainText: String
        get() {
            val sb = StringBuilder()
            for (r in runs) {
                when (r.kind) {
                    HtmlRunKind.DIV -> for (sub in r.divRows) sb.append(sub.plainText)
                    else -> sb.append(r.text)
                }
            }
            return sb.toString()
        }

    /** 玩家实际读到的字符：只算 TEXT 段，DIV 段递归。 */
    val visibleText: String
        get() {
            val sb = StringBuilder()
            for (r in runs) {
                when (r.kind) {
                    HtmlRunKind.TEXT -> sb.append(r.text)
                    HtmlRunKind.DIV -> for (sub in r.divRows) sb.append(sub.visibleText)
                    else -> {}
                }
            }
            return sb.toString()
        }

    /** 是否至少有一个可见字符（[visibleText] 非空）。 */
    val hasVisibleText: Boolean get() = visibleText.isNotEmpty()

    /**
     * 该行可见文本占的**半角格数**（全角 = 2、半角 = 1，唯一口径 [CellWidth]）。
     *
     * 这是「玩家屏幕上一行占多少格」的读数：`core/host/CellWidth` 的 CP932 字节口径
     * 已由 WIDTH-SINGLE 收口钉在基准上（`pc-golden-root.txt:253` 的 199 个 `=`、
     * `:61` 的 99 个 `━`），本属性只是把它接到 [visibleText] 上，不新增第二份宽度表。
     * 不含折行语义：原版按 `Config.DrawableWidth` 折行（PrintStringBuffer.cs:242-251），
     * 那一步归宿主（它才知道窗口宽）。
     */
    val cells: Int get() = CellWidth.of(visibleText)
}

/**
 * 一次 `HTML_PRINT` 的全部显示行（原版 `ConsoleDisplayLine[]`）。
 *
 * 行数口径逐条照抄 `PrintStringBuffer.ButtonsToDisplayLines`（:207-262）对
 * `list2` 里 null 标记的处理 —— 这是「玩家看到的行数」，不是 `<br>` 的个数：
 * - 空输入：0 行（`buttonList.Count == 0` 早退，:198-201）。
 * - `"AB"`：1 行。
 * - `"AB<br>"`：**1 行**（尾部 null 只是给最后一行收尾，:209-216 结出 AB 行后 `list2` 已空，
 *   :259-262 不再补行）。
 * - `"<br>AB"`：2 行（首个 null 在空表上结出一条空行）。
 * - `"AB<br>CD"`：2 行；`"AB<br><br>CD"`：3 行（中间那行是空行）。
 *
 * 早退性质：`HtmlLength`（HM :168-182）只取 `rows[0]` 求和，故 `"<br>AB"` 的宽度是 0。
 */
data class HtmlPrintDoc(val rows: List<HtmlPrintRow>)
