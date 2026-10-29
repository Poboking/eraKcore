package com.eratw.port.render

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.displaylist.InlineGraphic

/**
 * M4 渲染层的输入模型 —— 文本段（一段连续、样式相同的字符）。
 *
 * 渲染层单向依赖 `:core-vm`（本模块消费引擎产物，`:core-vm` 永不反向依赖本模块），
 * 所以控制台产物由 render 的 adapter 包映射到本类型
 * （源类型：`core/host/LineBufferConsoleSink.kt` 的 `DisplayLine`/`Segment`/`Button`）。
 *
 * 样式在**入缓冲时**快照（原版 `ConsoleStyledString` 构造时就固定 Color/FontStyle/Fontname，
 * spec: `MinorShift.Emuera.UI.Game/ConsoleStyledString.cs:26-43`），
 * 因此 SETCOLOR/SETFONT 只影响后续段，不回改已入队段。
 *
 * @property fore 前景色 `0xRRGGBB`
 * @property back 背景色 `0xRRGGBB`
 * @property styleMask 字体样式位：1=粗体 2=斜体 4=删除线 8=下划线
 *   （spec: `Process.cs:1508-1530` 的 FontStyle 组装）
 * @property buttonValue 仅 [RunKind.BUTTON] 有意义：Long（数值按钮）或 String（字符串按钮）
 * @property buttonId 本 run 所属**源按钮**的编号；-1 = 不属于任何按钮 / 身份未知。
 *
 * 原版一条按钮是 `ConsoleButtonString` 实例，它的 `StrArray` 可以装多个样式节点
 * （`ConsoleButtonString.cs:19`、`:296-303`），而两次 `AppendButton` 恒是两条
 * （`PrintStringBuffer.cs:103-123`）。渲染层按样式把一条按钮切成多条 run 之后，
 * 只有编号能把「同一按钮的不同样式片」与「两个值相同、列号相接的独立按钮」分开
 * （条数口径 = `ConsoleDisplayLine.buttons` 的数组长度，`ConsoleDisplayLine.cs:18,28`）：
 * 同一编号的相邻同值 run 并成一条命中区，不同编号的各是一条。
 * 源侧取值：`adapter/ConsoleAdapter.runsOf` 填该 run 覆盖的
 * `LineBufferConsoleSink.Button` 在**本行 `buttons` 列表里的下标**；行内按钮区间两两不相交
 * ⇒ 下标就是身份。手工构造的 run 不填（-1）时退化成「列号相接 + 值相同就并」的老口径。
 * @property htmlOption 仅 [RunKind.HTML] 有意义：HTML_PRINT 的 toPrintBuffer 标志快照
 *   （源侧 `core/host/LineBufferConsoleSink.kt` 的 `Segment.htmlOption`，
 *   spec: `EmueraConsole.cs:3203-3227`）。适配层原样搬运，不做任何再解析。
 * @property focusFore 焦点态字色（原版 `StringStyle.ButtonColor`，`StringStyle.cs:9,11,22`）：
 *   默认 `Config.FocusColor`（`ConfigData.cs:70` 的 `Color.FromArgb(255, 255, 0)`，
 *   基准实测 `FOCUS=16776960`，`fixtures/golden/baseline/pc-golden-root.txt:269`），
 *   HTML 的 `bcolor` 可逐段改它（`HtmlManager.cs:131-149` 的 `BColor` → `buttonColor`）。
 *   焦点绘制读它而**不读** [back] —— 原版 `ConsoleStyledString.cs:105-111` 用的是
 *   `base.ButtonColor`；[back] 是控制台背景色（SETBGCOLOR 的行快照），两者不是一回事。
 *   TODO(verify)：`Config.FocusColor` 可被配置界面改写（`ConfigDialog.cs:994`），render 侧
 *   目前没有配置入口，恒用默认值；配置层接线时应在装配处逐段写入本字段。
 */
data class TextRun(
    val text: String,
    val fore: Int,
    val back: Int,
    val styleMask: Int,
    val fontName: String,
    val kind: RunKind = RunKind.TEXT,
    val buttonValue: Any? = null,
    val htmlOption: Boolean = false,
    val buttonId: Int = -1,
    /**
     * 非文本内联图形负载（M10-GFX）。null = 文本段。
     *
     * 非空时本段的 [text] 恒为空串、[kind] 为 [RunKind.IMAGE] 或 [RunKind.SHAPE]，
     * 占宽由 [InlineGraphic.widthCells] 给出而不是由文本量出来 —— 原版图形段的宽度
     * 同样是「节点自带的像素宽」而非测量值（spec: `ConsoleImagePart.cs:66-80` 直接写
     * `base.Width`，`SetWidth` 只在 sprite 查不到时才去量 `AltText`：`:104-115`）。
     */
    val graphic: InlineGraphic? = null,
    /** 焦点态字色；见类文档的 `focusFore` 条。默认 = `Config.FocusColor`。 */
    val focusFore: Int = DEFAULT_FOCUS_COLOR,
    /**
     * 按钮的 `title` 属性原文（HTML `<button title='…'>`），null = 该按钮没有提示。
     *
     * 原版把 `title` 存进 `HtmlAnalzeStateButtonTag.ButtonTitle`
     * （`HtmlManager.cs:1514-1520`），`</button>` 时随整条按钮打包成
     * `ConsoleButtonString.Title`（`HtmlManager.cs:847`、`ConsoleButtonString.cs:53,253`），
     * 鼠标指着按钮时由控制台取出来做 ToolTip（`EmueraConsole.cs:1891-1917`，其中
     * `:1909` 把 `<br>` 换成换行符）。PC 靠 hover，安卓没有 hover，改由长按取这份原文显示。
     * 与 [buttonId] 同源：同一条源按钮拆出的文本段/图形段都带同一份提示。
     */
    val tooltip: String? = null,
) {
    companion object {
        /**
         * `Config.FocusColor` 的默认值 `Color.FromArgb(255, 255, 0)` = `0xFFFF00`
         * （`ConfigData.cs:70`；基准 `pc-golden-root.txt:269` 的 `FOCUS=16776960`）。
         */
        const val DEFAULT_FOCUS_COLOR: Int = 0xFFFF00
    }
}

/**
 * 文本段种类（渲染层据此区分绘制路径；按钮另带命中区）。
 *
 * [IMAGE] / [SHAPE] 是 M10-GFX 加的位图与色块变体（原版对应 `ConsoleImagePart` 与
 * `ConsoleShapePart`，spec: `EmueraConsole.cs:3192-3200` 把两者都 append 进同一条
 * 打印缓冲，所以它们与文本段共用列游标与脏矩形口径）。
 */
enum class RunKind { TEXT, BUTTON, PLAIN, HTML, ERROR, IMAGE, SHAPE }

/**
 * 一条显示行。
 *
 * @property isLogicalLine 本行是否是一个逻辑行的**首行**（折行时只有首段为 true，
 *   spec: `PrintStringBuffer.cs:193,206`）
 * @property isLineEnd 本行是否结束一个逻辑行：PRINTN（`lineEnd=false`）的末行为 false，
 *   下一批内容并入本行（spec: `EmueraConsole.cs:2948-2977`）
 * @property align 本行的对齐方式快照（ALIGNMENT，spec: `Process.cs:1536-1552` 与
 *   `DisplayLineAlignment.cs:3-8`；列偏移的换算见 `adapter/ConsoleAdapter` 的 alignmentShift）。
 *   原版把它存在 `ConsoleDisplayLine.align`（spec: `ConsoleDisplayLine.cs:20,30`），
 *   入列时用 `SetAlignment` 换成按钮的 `PointX` 偏移（spec: `ConsoleDisplayLine.cs:50-91`）。
 * @property temporary REUSELASTLINE 产生的临时行标记（spec: `EmueraConsole.cs:2891-2893`：
 *   下一条正常输出入列前先把末行的临时行删掉）。源侧是 `DisplayLine.isTemporary`。
 * @property noBreak 本行**不参与折行**（原版 `nobr`）。源侧是 `DisplayLine.noBreak`。
 *
 * 原版 `nobr` 是 `Flush` 的调用参数而不是行字段（`FlushSingleLine` 传 true，
 * spec: `PrintStringBuffer.cs:173`；普通 `Flush` 传 false，`:182`；折行判定读它，`:217`），
 * 因为那边布局一次做完。本仓渲染层的 `LineFolder` 会**再折一次**，所以这个事实必须随行带过来，
 * 否则 PRINTSINGLE / REUSELASTLINE / 错误行会被按宽度二次拆开（原版一行不折：
 * `PrintStringBuffer.cs:217`；调用点 `EmueraConsole.cs:3147-3165`、`:3053-3056`、`:3098-3115`）。
 */
data class RenderLine(
    val runs: List<TextRun>,
    val isLogicalLine: Boolean,
    val isLineEnd: Boolean,
    val align: Alignment = Alignment.LEFT,
    val temporary: Boolean = false,
    val noBreak: Boolean = false,
)

/** 渲染层的数据来源（CONSOLE-2 落地前由测试假实现驱动）。 */
interface RenderSource {
    val lines: List<RenderLine>

    /** 逻辑行计数（LINECOUNT 口径，与是否折行无关）。 */
    val lineCount: Long
}

/**
 * 网格常量与宽度模型。
 *
 * ## 单位：半角格，不是像素
 *
 * 原版折行按**像素**测量（`StringMeasure.GetDisplayLength`，
 * spec: `StringMeasure.cs:42-80`，GDI `TextRenderer` / `MeasureCharacterRanges`），
 * 只有 PRINTC 的补齐宽度是 **Shift-JIS 字节数**（半角 1 / 全角 2，
 * spec: `EmueraConsole.cs:3255`，`Encoding.GetEncoding("Shift-JIS").GetByteCount`）。
 * 本模块在 M4 阶段统一用「半角 1 / 全角 2」的等宽字符网格近似像素测量
 * （ADR-001 的重设计区：整数网格强制对齐，不信字体度量）；
 * 真机上必须由字形缓存提供「像素到半角格」的换算后再传入宽度。
 * TODO(verify)：比例字体与字距下的折行位置与原版不可能逐像素一致，属已知偏差（M4 验收需记录）。
 *
 * ## 宽度表只有一份（M4-D 的 WIDTH-SINGLE，口径由组长裁定）
 *
 * 收口前是**两张表**：本类自带一张 Shift-JIS 字节数口径的判定式（`U+0020..U+007E` 与半角假名
 * `U+FF61..U+FF9F` 记 1 格，其余一律 2 格），核心层 `core/host/CellWidth.kt` 那张当时是
 * East Asian Width 的 Wide/Fullwidth 近似（宿主侧文本折行 `LineBufferConsoleSink.kt:664`、
 * 按钮切点 `:677-689`、段宽 `:770`、PRINTC 补齐 `:781-795`、分隔线填充 `:809-818`、
 * HTML 可见宽度 `:866,872` 都走它）——同一行在两层眼里格数不同，按钮切点与列号偏 1 格。 *
 * 裁定（main@`166dbd7`）：**留字节数口径那张、把核心层的表换掉**，而不是让渲染层去迁就近似表。
 * 两条独立证据：PRINTC 的补齐宽度原样问 `Encoding.GetEncoding("Shift-JIS").GetByteCount`
 * （spec: `EmueraConsole.cs:3255`；`STRLEN` 一族同理 `LangManager.cs:15-22,39,74,90`；码页
 * `Config.cs:412-416` 定成 932）；屏上宽度由基准钉死——`pc-golden-root.txt:61` 是 99 个 `━`，
 * 而 `:253` 的 199 个 `=` 把半角步进钉成 9px、可绘制宽度 1797px（`Config.cs:461-466`），
 * 99 字填满只能是单字 18px = 2 个半角格。EAW 把 `─ ━ ※ ① ± … ― “ ” ■ ▼ ♪ ≦ ≧` 这类判成
 * Ambiguous（1 格），与这两条同时冲突。
 *
 * 于是 [isFullWidth] 与 [cellWidth] 一律委托 [CellWidth]（`:render -> :core-vm` 单向依赖，
 * 契约 M4-D 0.2），本类只剩**换算**（[cells] 的逐码元累加）与 **TAB 展开**
 * （[expandTabs]，spec: `StringMeasure.cs:57-61` 的 `s.Replace("\t", "        ")`）。
 * 委托**只改了 35 个码点**：C0 控制符 / DEL / `U+0080` / `U+00A5` 按 `.NET` 932 的
 * `GetByteCount` 实测从 2 格改成 1 格（2026-10-21，M6-RENDER-AUDIT 的缺口①，
 * 逐点账在 `grid/GridWidthSingleSourceTest` 的 D 类表）；其余码点逐值不变 ——
 * `sprite/BarLineTest` 的 `build("─")` 仍是 49 字、`view/ConsoleScreenTest` 的脏区右沿仍是 198。
 * 等宽网格与真实度量两条**口径**仍并存
 * （契约 M4-C 0.5：真机以 `glyph/GlyphCache.measureCells` 的像素度量为准），但宽度数值只有一个来源。
 *
 * ## 增补平面（U+10000 以上）的口径边界（WIDTH-ASTRAL 钉死，2026-10-20）
 *
 * [cells] 逐 **UTF-16 码元**累加，一对合法代理（一个增补字符）得 **4 格**；
 * 核心层 [CellWidth.of] 逐 **码位**累加，同一个字符得 **2 格** —— 两层差一倍。
 * 本轮**维持现状、不统一**（本路的生产行为逐字节不变：只改本 KDoc、`grid/LineFolder.kt`
 * 的 KDoc 与新增的 `grid/AstralWidthTest`），三条理由按权重排：
 *
 * 1. **原版折行路径的索引空间就是 UTF-16 `Char`，宽度与索引必须同域**：
 *    `PrintStringBuffer.cs:453-496` 的 `getDivideIndex` 在 `text.Length` 上二分、按
 *    `Slice(0, num4)` 取 Char 前缀量宽度（`:471,473,485-486`）；段宽是整段文本的度量
 *    （`ConsoleStyledString.cs:82` 的 `SetWidth`），拆分按 Char 下标（`DivideAt(index)` 里的
 *    `Substring`，`:54-73`）；`PrintStringBuffer.cs:438` 累计的也是 `Text.Length`。
 *    `grid/LineFolder.kt` 是这条的直译，「段宽」与「前缀宽度」两套计数因此只能同式
 *    （`pieceCellsAt` 逐 Char 取 [cellWidth]、`FoldPiece.cells` 整串走 [cells]）；
 *    改成按码位要**同时**改两处（只改一半更糟：折行判定与收行格数会互相矛盾），
 *    且改完索引空间（Char）与宽度空间（码位）不再同域。
 * 2. **原版的宽度读数本身是像素**：`StringMeasure.cs:42-49` 的 `TextRenderer.MeasureText`
 *    与 `:51-72` 的 `MeasureCharacterRanges`（TAB 展开见 `:59-62`）。GDI 把一对代理项当
 *    **一个字形**量（靠字体回退），既不是 4 也不是 2；CP932 口径同样给不出 4 ——
 *    U+10000 以上在 CP932 里没有码位（`EmueraConsole.cs:3255` 的 `GetByteCount` 对表外字
 *    走替换回退、实得 1 字节，逐点登记在 `GridWidthSingleSourceTest` 的 B 类表）。
 *    两个整数读数都是**没有度量时的兜底近似**，不是原版读数；真机一律以
 *    `glyph/GlyphCache.measureCells` 的像素度量为准（契约 M4-C 0.5）。
 * 3. **改动面越界、且闸看不见**：基准整份没有码位大于 U+FFFF 的字符
 *    （`fixtures/golden/baseline/pc-golden-root.txt` 实测 700 行 / 22,694 字节 / 0 个），
 *    换累加口径要同时重钉 `GridWidthSingleSourceTest` 的「增补平面字符是已登记的驻留差异」
 *    与 `LineFolderTest` 的「代理对不被劈成半个字符」两个**已登记**用例，二者都不在本路契约的
 *    独占文件里（跨界重钉归组长接线，见 M4-F 交付消息）；基准又没有这类字符 ⇒
 *    改与不改，四道闸的读数都不动，拿不到任何验证收益。
 *
 * 分叉的实际代价（下游要按这条处理）：核心层的生产折行**按码位**
 * （`core/host/LineBufferConsoleSink.kt:770-778` 的 `codePointAt` + `charCount` 步进，
 * 每字 2 格），而 `adapter/ConsoleAdapter.fold` 又用 [LineFolder] 对同一条行**再折一次**
 * ⇒ 含增补字符的行在本层按 4 格算，可能把核心层没断的行再断开、按钮列区间随之后移
 * 2 格/字（按钮切点见 `adapter/ConsoleAdapter` 与 `button/ButtonLayer`）。
 * 语料里这类字符**确实存在**（`ERB` 目录实测 13 个文件共 74 个，含
 * `PRINTBUTTON @"%UNICODE(0x3000) * 2%[U+1F6AA 检查房间]", 100` 这类按钮标签、
 * `MOVEMENTS` 下 `MAP_06.ERB` 的 24 个 U+1F480 地图行），只是基准的采集里一个也没有。
 * 逐值账、三层对照表与折行落点全在 `grid/AstralWidthTest`。
 * TODO(verify)：4 格与 2 格谁更接近原版，本装置判定不了（卡点：本机无 Windows GUI/GDI
 *   读数，PC 侧 `GetByteCount` / `MeasureText` 都跑不了；Temurin 17 的 `windows-31J` 对
 *   U+1F6AA 一类只编出单个 `0x3F`，与 .NET 替换回退是否同值未实测）。
 *
 * 逐码位登记与两条驻留差异（增补字符的「按码元 4 格 vs 按码位 2 格」、CP932 表外字的全角兜底）
 * 都在 `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`：
 * A/B/C/D 四类符号表带逐点 CP932 字节序列（D 类 = C0/DEL/`U+0080`/`U+00A5` 的单字节档），
 * 两张表将来谁再分叉，那条差集用例先炸；
 * 增补平面的三层对照表与折行落点在新文件
 * `render/src/test/kotlin/com/eratw/port/render/grid/AstralWidthTest.kt`。
 */
object Grid {
    /** 黄金基准的窗口宽度 `CLIENTWIDTH=1800`（`fixtures/golden/baseline/pc-golden-root.txt:689`）。 */
    const val DEFAULT_CLIENT_WIDTH = 1800

    /** `MAXLOG` 默认 5000（spec: `ConfigData.cs:62`）。 */
    const val MAX_LOG_DEFAULT = 5000

    /** `MAXLOG` 下限 500，配置小于它则被抬到 500（spec: `Config.cs:451-454`）。 */
    const val MAX_LOG_FLOOR = 500

    /** `PRINTC` 补齐宽度默认 25（spec: `ConfigData.cs:64`）。 */
    const val PRINT_C_LENGTH_DEFAULT = 25

    /** TAB 在测量前展开成 8 个空格（spec: `StringMeasure.cs:57-60`）。 */
    const val TAB_SPACES = 8

    /**
     * [expandTabs] 的替换串（正好 [TAB_SPACES] 个空格）。写成常量而不是每次
     * `" ".repeat(TAB_SPACES)` 现算：后者每遇到一个含 TAB 的段就多一个 8 字符 `String`。
     */
    private const val TAB_EXPANSION = "        "

    /** 样式位（与 [TextRun.styleMask] 同口径，spec: `Process.cs:1508-1530`）。 */
    const val STYLE_BOLD = 1
    const val STYLE_ITALIC = 2
    const val STYLE_STRIKE = 4
    const val STYLE_UNDERLINE = 8

    /**
     * 该码元在等宽网格里是否占两格 —— **判定式不在此处**，整体委托
     * [CellWidth.isFullWidth]（核心层唯一表，判据与逐点依据写在 `core/host/CellWidth.kt`
     * 的 [CellWidth.isFullWidth] KDoc：CP932 单字节是 ASCII、半角假名、C0/DEL/`U+0080`/`U+00A5` 四档）。
     *
     * 委托前本类自带的那张表只在 **D 类 35 个码点**上与唯一表不同（控制符与 `U+00A5`：
     * 旧表 2 格、唯一表按 `.NET` 932 的单字节读数给 1 格），旧式逐字抄在
     * `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`
     * 当参照物，两张表将来谁再分叉，那里先报红。
     *
     * 入参是 UTF-16 **码元**：代理项半（`U+D800..U+DFFF`）不在半角两段里，单独传入按 2 格计，
     * 所以一个增补平面字符经 [cells] 逐码元累加是 **4 格**，而 [CellWidth.of] 按码位给 **2 格**
     * —— 这条粒度差是**已登记的驻留差异**（委托前后都不变），逐值钉在单源测试的
     * 「增补平面字符是已登记的驻留差异」用例里。
     * 分叉**只发生在合法代理对**上：单独一个代理项半（未成对）三层同值都是 2 格 ——
     * [CellWidth.of] 的 `codePointAt` 对孤立半原样返回该半，落进「其余一律全角」。
     * 维持现状的理由、代价与重钉清单见本对象 KDoc 的「增补平面（U+10000 以上）的口径边界」节。
     * TODO(verify)：基准全份没有码位大于 U+FFFF 的字符 ⇒ 闸看不见这条；真机以
     * `glyph/GlyphCache.measureCells` 的像素度量为准（契约 M4-C 0.5）。
     */
    fun isFullWidth(ch: Char): Boolean = CellWidth.isFullWidth(ch.code)

    /**
     * 单字符占用的半角格数（委托 [CellWidth.ofCodePoint]，本类不再自带 1/2 的字面量）。
     *
     * 入参是**码元**：合法代理对的两个半都落进「其余一律全角」，各给 2 格 ⇒ [cells] 对一个
     * 增补字符给 4 格，与 [CellWidth.of] 的 2 格差在**累加粒度**（不在本函数）。
     */
    fun cellWidth(ch: Char): Int = CellWidth.ofCodePoint(ch.code)

    /**
     * 字符串占用的半角格数：先把 TAB 展开成 8 空格（spec: `StringMeasure.cs:57-61`），
     * 再逐码元累加 [cellWidth]。
     *
     * 这里刻意保持**逐码元累加**而不是直接调 [CellWidth.of] 的按码位循环：
     * `grid/LineFolder.kt` 的 `pieceCellsAt` 与 [expandTabs] 之后按 `Char`
     * 索引的拆段/前缀逻辑共用同一个入口，改成按码位会让「段宽」与「前缀宽度」两套计数
     * 在代理对上分叉（本类的 `DisplayRow.cells` 与折行判定必须同式）。
     * BMP 串（无代理对）下两者逐字相同，已由单源测试逐码点扫过一遍。
     *
     * ⇒ 增补平面字符这里 4 格、[CellWidth.of] 2 格，是**有意保留**的已登记驻留差异
     * （理由、代价与重钉清单见本对象 KDoc 的「增补平面（U+10000 以上）的口径边界」节，
     * 逐值账在 `grid/AstralWidthTest`）。谁要统一，必须 [cells] 与 `grid/LineFolder.kt`
     * 的两套计数（段宽 / 前缀宽度）**同时**改，并同步重钉 `GridWidthSingleSourceTest` 与
     * `LineFolderTest` 里已登记的期望值。
     */
    fun cells(s: String): Int = cellsExpanded(expandTabs(s))

    /**
     * **已经** [expandTabs] 过的文本的格数 —— [cells] 的快速通道。
     *
     * 只省掉「再查一遍有没有 TAB」这一趟扫描，累加口径与 [cells] 的逐码元 [cellWidth]
     * 逐值相同，不引入第二套宽度表：`cells(s)` 的定义就是 `cellsExpanded(expandTabs(s))`。
     * 调用点（`displaylist/DisplayList.prepare`、`grid/LineFolder` 的段宽）本来就先展开过 TAB，
     * 直接用这条可以少一次 O(n) 的 `indexOf` 扫描与一次重复展开的可能分配。
     * 下标循环而不是 `for (ch in s)`：后者在 String 上会产生一个 `CharIterator`（每段一次）。
     */
    fun cellsExpanded(s: String): Int {
        var total = 0
        var i = 0
        while (i < s.length) {
            total += cellWidth(s[i])
            i++
        }
        return total
    }

    /**
     * TAB 展开（只按 8 空格替换，不做制表位对齐 —— 与原版一致）。
     *
     * 替换串是常量：旧写法在这里现算 `" ".repeat(TAB_SPACES)`，于是每遇到一个含 TAB 的段
     * 都多分配一个 8 字符的 `String`。常量与 [TAB_SPACES] 同值（8），由单测钉住。
     */
    fun expandTabs(s: String): String = if (s.indexOf('\t') < 0) s else s.replace("\t", TAB_EXPANSION)
}

/**
 * 折行后的一行显示单元 —— `grid` 路的产物，`displaylist`/`scroll`/`button` 路的输入。
 *
 * @property runs 本显示行的段序（跨行按钮在每一行各成一条段，`ConsoleButtonString.DivideAt`
 *   语义，spec: `ConsoleButtonString.cs:187-215`、`PrintStringBuffer.cs:224-236`）
 * @property cells 本行占用的半角格总数（小于等于 `clientWidth`）
 * @property align 本行的对齐方式（从 [RenderLine.align] 透传；折行产生的每一行**各自**对齐，
 *   因为原版 `SetAlignment` 是在 `addDisplayLine` 里对每条显示行分别调用的，
 *   spec: `EmueraConsole.cs:2937-2944` + `ConsoleDisplayLine.cs:50-91`）
 * @property indentCells 本行的**列偏移**（半角格，可为负）：绘制与命中都要在段列号上加上它。
 *   对应原版 `SetAlignment` 算出的 `num3 = num2 - pointX` 并经 `ShiftPositionX` 加到
 *   每个 `ConsoleButtonString.PointX` 上的那一步（spec: `ConsoleDisplayLine.cs:70-90`、
 *   `ConsoleButtonString.cs:307-315`）。PRINTC 的定宽补齐不走这里（它在核心层就被补成空格，
 *   spec: `EmueraConsole.cs:3247-3291`），本字段只承载 ALIGNMENT CENTER/RIGHT 的整行平移。
 *   默认 0 表示未对齐（LEFT 且无偏移），既有生产者不传即保持原行为。
 * @property temporary 临时行标记（从 [RenderLine.temporary] 透传，spec: `EmueraConsole.cs:2891-2893`）
 */
data class DisplayRow(
    val runs: List<TextRun>,
    val cells: Int,
    val isLogicalLine: Boolean,
    val isLineEnd: Boolean,
    val align: Alignment = Alignment.LEFT,
    val indentCells: Int = 0,
    val temporary: Boolean = false,
)

/**
 * 行级脏区（半开区间，`[rowStart, rowEnd)` × `[colStart, colEnd)`）。
 * 粒度取「行」是 ADR-003 的规定：渲染线程在 Choreographer 回调里按行消费。
 */
data class DirtyRect(val rowStart: Int, val rowEnd: Int, val colStart: Int, val colEnd: Int)

/**
 * 按钮命中目标：显示行 [row] 的列区间 `[colStart, colEnd)` 上的可点击段。
 * [value] 是 Long（数值按钮）或 String（字符串按钮），与 [TextRun.buttonValue] 同源。
 */
class HitTarget(
    val row: Int,
    val colStart: Int,
    val colEnd: Int,
    val value: Any,
    /**
     * 按钮上印的字（`ButtonLayer.attach` 时把覆盖区间的 run 文本攒出来）。
     *
     * 2026-10-24 加：右侧「大按钮」栏以前拿**文本快照正则**猜 `[数字]`，导致 `exit` 这类
     * 字符串按钮完全看不到。引擎本来就有真实命中区（本类），把文本一起带上就能在引擎层
     * 直接枚举出「这一页有哪些按钮、叫什么、值是什么」。图形按钮没有文字，留空串。
     *
     * **不参与相等性**：命中目标的身份 = 几何 + 值（与加本字段之前的既有用例口径一致）；
     * 文字只是展示元数据，改文案不该让两个命中目标变得不相等。
     */
    val label: String = "",
) {
    override fun equals(other: Any?): Boolean =
        other is HitTarget &&
            other.row == row &&
            other.colStart == colStart &&
            other.colEnd == colEnd &&
            other.value == value

    override fun hashCode(): Int {
        var result = row
        result = 31 * result + colStart
        result = 31 * result + colEnd
        result = 31 * result + value.hashCode()
        return result
    }

    override fun toString(): String =
        "HitTarget(row=$row, colStart=$colStart, colEnd=$colEnd, value=$value, label=$label)"
}


/**
 * 字形键：字体名 + 样式位 + 字号 + 码位。样式与字号分桶是必须的 ——
 * 原版 `ConsoleStyledString` 的 FontStyle/Fontname 随段固定（spec: `ConsoleStyledString.cs:26-43`），
 * 原版字形字典的键含 `Config.FontSize`（spec: `FontFactory.cs:9,18`）。
 *
 * [size] 默认 0 = 「本缓存只服务一个字号」的兼容取值；引入真实度量后必须由调用方传实际字号，
 * 字号变更不再需要重建整张缓存。
 */
data class GlyphKey(
    val fontName: String,
    val styleMask: Int,
    val codePoint: Int,
    val size: Int = 0,
)

