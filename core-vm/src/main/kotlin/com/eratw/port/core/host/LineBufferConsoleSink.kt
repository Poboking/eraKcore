package com.eratw.port.core.host

import com.eratw.port.core.err.CodeEE

/**
 * 生产用行缓冲控制台（M4 渲染层的数据源）。
 *
 * 复刻 `EmueraConsole` + `PrintStringBuffer` 的文字流语义：
 * - 打印缓冲（未 flush 的待决内容）与显示行列表分离；
 * - flush 时才按宽度折行、生成 [DisplayLine]，并维护 LINECOUNT；
 * - PRINTN 的 `lineEnd=false` 使末显示行可被下一次 flush 合并（日志不断行）。
 *
 * 折行宽度模型：原版按像素测量（`StringMeasure.GetDisplayLength`，ConsoleStyledString.cs:75-81、
 * PrintStringBuffer.cs:205-224），本类按**等宽字符网格**近似：半角 1、全角 2。
 *
 * 因此 [clientWidth] 的单位是「半角格」，不是像素；而且它对应的是**可绘制宽度**
 * `Config.DrawableWidth`，不是窗口客户区 `ClientWidth`：
 * - 折行判据原版用 `Config.DrawableWidth`（PrintStringBuffer.cs:205、:217），
 *   同一项也用于 DRAWLINE 填充（EmueraConsole.cs:3426）与对齐偏移（ConsoleDisplayLine.cs:80、:83）；
 * - `DrawableWidth = WindowX - max(2, FontSize/6)`（Config.cs:461-466），
 *   PC 基准 `WINDOW WIDTH:1800` / `FONT SIZE:18`（语料 emuera.config:12、:22）⇒ 1797px，
 *   半角步进 9px（由 `pc-golden-root.txt:253` 的 199 个 `=` 唯一确定）⇒ **199 格**；
 * - `ClientWidth` 是 `window.MainPicBox.Width`（EmueraConsole.cs:232，基准 1800px），
 *   只用于鼠标坐标换算（:1307 减 ClientHeight 等），**不参与折行**。
 *
 * 所以构造缺省的 1800 只是「与基准 CLIENTWIDTH 同值」的占位，不是正确的格数：
 * 生产装配（GameSession / GoldenPipeline）必须传入「DrawableWidth ÷ 实测半角步进」
 * 的换算结果（PC 基准 = 199，见 `GoldenPipeline.PC_DRAWABLE_WIDTH`），
 * 否则 1800 会被当成 1800 格。TODO(verify)：比例字体下该换算依赖 `:render` 的
 * `GlyphCache.measureCells`（本类的字符网格只在等宽字体下等价）。
 *
 * **宽度换算只有一个实现**：[CellWidth]（折行、PRINTC 补齐、DRAWLINE 填充、按钮列区间、
 * 段起始列全部走它）。真机的像素→半角格换算以 `:render` 的 `GlyphCache.measureCells`
 * 为准（`render/.../glyph` 包），本类拿到的 [clientWidth] 就是换算后的格数；
 * 字符表只在无字体度量的 headless 路径下作为确定性兜底。
 * TODO(verify)：原版是像素级测量（含比例字体/字距），本类的字符网格只在等宽字体下等价。
 *
 * 线程模型：写侧单 VM 线程（ADR-003）；读侧 UI 线程每帧经 render 的 ConsoleFeed 并发调
 * [lines] 等快照方法。因此 [displayLines] 的**每一个**原地增删点与**每一个**快照读取点
 * 都用 `synchronized(displayLines)` 保护（锁粒度 = 单次列表操作或一整段「检查+修改」
 * 复合逻辑；VM 热路径上是无竞争锁，开销可接受）——不加锁时 UI 线程的 `toList` 迭代器
 * 会做 modCount 校验，撞上写侧并发修改直接崩 UI 线程（A2：ConcurrentModificationException）。
 * 锁内只做纯列表/排版操作，不允许调用任何外部回调。
 *
 * [islands]（HTML 浮岛：写侧 [addHtmlIsland]/[clearHtmlIslands]、读侧 [htmlIslands]）归入**同一把**
 * `displayLines` 监视器，而不是第三把锁：它是同一个「VM 单线程写、UI 侧多读」的共享 ArrayList，
 * 缺陷形状与 displayLines 完全同类（原版无锁只是因为 PC 侧 VM 与绘制同线程消息泵，见
 * EmueraConsole.cs:166/:1880/:2869/:2874 —— 本类拆了线程就必须补锁）；而浮岛的三个读写点与显示行的
 * 临界区互不嵌套（谁都不在对方持锁时调用），单监视器因此没有锁序环，也不引入重入。
 * 浮岛变化**不**自增 [revision]：[revision] 的口径只有 [lines] 的可见变化，渲染层目前也不消费浮岛。
 *
 * ## 自动按钮（`[123]`）
 *
 * 普通文本里的 `[123]` 在原版 flush 时被 `ButtonStringCreator.SplitButton`
 * （PrintStringBuffer.cs:280-292 的 `fromCssToButton` → `:326-345` 的 `createButtons`）
 * 切成可选段，命中即喂数字 123。这条在本类落地：[splitAutoButtons] 在折行前把**一组连续的
 * cssList 节点**（纯文本段 + HTML 段）整串交给 [ButtonStringCreator]，可选片落成
 * `Kind.BUTTON` 段 + [DisplayLine.buttons] 区间。
 * 因此 [DisplayLine.buttons] 里会有**没有显式 PRINTBUTTON** 的区间 —— 这是原版行为
 * （`ConsoleDisplayLine.Buttons` 同样混着两类），不是重复记账。
 * PRINTPLAIN 不参与（`createPlainButton`，PrintStringBuffer.cs:125-134、:318-324）；
 * PRINTSINGLE / REUSELASTLINE 参与（`FlushSingleLine` 也先 `fromCssToButton`，`:170-177`）。
 *
 * **切分单位是「cssList 节点组」而不是「单条样式段」**：`fromCssToButton` 把待 flush 的
 * 全部节点交给 `createButtons`，后者**先把所有节点的 `Text` 拼成一整串**再交给
 * `ButtonStringCreator.SplitButton`（PrintStringBuffer.cs:280-292、:326-345），最后按字符区间
 * 把节点切回去（:347-377）——所以一个按钮可以横跨多个样式段（`ConsoleButtonString.StrArray`
 * 是节点数组，ConsoleButtonString.cs:19、:296-303）。本类同样跨段拼串（见 [splitAutoButtons]）。
 *
 * **非文本节点不可拆**：`createButtons` 在切点落在不可拆节点（`CanDivide == false`，
 * 如 `ConsoleImagePart` / `ConsoleDivPart`）内部时，把这**整个节点**塞进当前按钮再断
 * （PrintStringBuffer.cs:358-374）。本仓没有立绘/形状节点，唯一的非文本待决段是 HTML 段，
 * 按同一口径处理（边界落在它内部就吞到段末，见 [splitCrossNode]）。
 * TODO(verify)：原版 `HTML_PRINT` 的 `option=true` 走 `Html2ButtonList` →
 * `AppendButton(ConsoleButtonString)`（EmueraConsole.cs:3203-3216；PrintStringBuffer.cs:136-140
 * 每次都先 `fromCssToButton`），HTML 内容**不进** `m_stringList`，所以原版里 `[n]` 不会跨
 * HTML 段落成按钮；本类把 HTML 段当 m_stringList 节点是为了让「拼同一 cssList 再切」这条
 * 只有一份实现。为不改动既有语料行为，跨节点拼串**只在方括号真的跨节点时**生效
 * （纯文本子组各自已经能切出按钮时仍按子组切，见 [splitGroup]）。语料里没有跨 HTML 的
 * `[...]` 实例，卡点：无对拍读数，单测用合成段钉规则（`LineBufferConsoleSinkTest`）。
 *
 * @param clientWidth 显示宽度（半角格数）。<= 0 表示不折行。
 * @param printCWidth PRINTC 的补齐宽度。原版 = `Config.PrintCLength`，默认 25
 *   （ConfigData.cs:64）。注意单位是 **Shift-JIS 字节数**（EmueraConsole.cs:3257-3261），
 *   本类用「半角 1 / 全角 2」近似该字节数。
 * @param maxLines 显示行上限（原版 Config.MaxLog 默认 5000，ConfigData.cs:62；
 *   超出时丢最旧行，EmueraConsole.cs:2980-2990）。<= 0 表示不限制。
 * @param defaultFontName 宿主环境的 `Config.FontName`（SETFONT 传空串时恢复的就是它，
 *   RESETFONT 语义同）。ConfigData.cs:65 的**出厂缺省**是「ＭＳ ゴシック」，但真机上这一项
 *   由 emuera.config 决定，不是常量 —— PC 基准环境取游戏 emuera.config:21
 *   「FONT NAME:黑体」（基准取证 pc-golden-root.txt:280 `FONT_DEFAULT=黑体`）。
 *   因此它必须是注入项而非写死的默认值。
 * @param buttonWrap `Config.ButtonWrap`（「BUTTON WRAPPING」，ConfigData.cs:83 出厂 false）。
 *   见 [DEFAULT_BUTTON_WRAP]：true 时**可选按钮**跨折行边界不拆分、整颗移到下一行
 *   （PrintStringBuffer.cs:217-241 的条件在 :222，`Config.ButtonWrap` 为真且当前行非空时进入
 *   「不拆分」分支；放不下一整行时才在下一行按字符拆）。非按钮文本两种取值都按字符折行。
 *   **必须是注入项**：语料 `emuera.config:38` 实测 `BUTTON WRAPPING:YES` ⇒ 真机口径是 **true**，
 *   与本参数的出厂缺省相反，取缺省会把可选按钮在折行边界拆开（PC 不拆）。
 *   TODO(verify) M4-N n-hostconsole 核查结论：`proc/GameSession.kt:191-196` 的**生产构造点**
 *   目前只喂了 `clientWidth` / `printCWidth` / `maxLines` / `defaultFontName` 四项，
 *   **buttonWrap 仍未从三层配置链取**（同处 :187 的 `rooted.configOverrides` 就是
 *   `ConfigChainSource` 的合并表，`BootHosts.kt:228-230`，字体名与行数都从它读）⇒
 *   需组长按本波交付「需组长接线」补一行（该文件不在本路文件面）。
 *   折行口径本身已由 `core/host/HostChainAuditTest` 三条用例钉住（含「按钮比整行宽时仍拆」）。
 */
class LineBufferConsoleSink(
    val clientWidth: Int = 1800,
    val printCWidth: Int = DEFAULT_PRINT_C_WIDTH,
    val maxLines: Int = DEFAULT_MAX_LINES,
    override val defaultFontName: String = DEFAULT_FONT_NAME,
    val buttonWrap: Boolean = DEFAULT_BUTTON_WRAP,
    /**
     * 「一屏一帧」（CONSOLE-FRAMEGATE）开关。
     *
     * true（缺省）= 既有行为：让出边界内冻结发布，`AWAIT` / 进入输入等待 / `REDRAW 1` 时
     * 一次性上屏；false = **永不冻结**，任何可见变更立即 bump [revision]。2026-10-23 真机
     * 「画面卡在上一帧」的逃生阀 —— 设置页可切换，宿主经 `GameHost(frameGateEnabled = …)`
     * 喂进来。
     */
    val frameGateEnabled: Boolean = true,
    /**
     * DRAWLINE 分隔线字符：`DRAWLINE文字` 条目的生效值（spec: `ConfigData.cs:163` 出厂 `"-"`）。
     * 装配层（`GameSession`）从三层链 + `_Replace.csv` 读入并合「空串回落 `"-"`」
     * （spec: `Config.cs:725-729`）。本类只在装配期消费一次 —— [stBar] 按 [clientWidth]
     * 填满成型，与原版 `Process.cs:259` 的 `console.setStBar(...)` 同为 init 期一次，
     * 运行中改配置不重算。
     */
    val barUnit: String = "-",
) : ConsoleSink {

    /** 文本段种类：渲染层据此区分普通文本、按钮、PRINTPLAIN、HTML、错误行。 */
    enum class Kind { TEXT, BUTTON, PLAIN, HTML, ERROR }

    /**
     * 文本段（一段连续、样式相同的字符）。
     *
     * 样式在**入缓冲时**快照，因此 SETCOLOR/FONTSTYLE/SETFONT 只影响后续段
     * （原版 ConsoleStyledString 在构造时固定 Color/FontStyle/Fontname，ConsoleStyledString.cs:21-40）。
     *
     * @property text 段文本。HTML 段保留原文（含标签）。
     * @property foreColor 前景色 `0xRRGGBB`，已按「当前值 ?: 默认值」解析。
     * @property styleMask 字体样式位：1=粗体 2=斜体 4=删除线 8=下划线（Process.cs:1508-1530）。
     * @property fontName 字体名（SETFONT；空串已在语句层恢复默认）。
     * @property kind 段种类。
     * @property htmlOption HTML_PRINT 的 toPrintBuffer 标志（仅 [Kind.HTML] 有意义）。
     */
    data class Segment(
        val text: String,
        val foreColor: Int,
        val styleMask: Int,
        val fontName: String,
        val kind: Kind = Kind.TEXT,
        val htmlOption: Boolean = false,
    )

    /**
     * 行内可点击区间，半开区间 `[startCol, endCol)`，单位与 [clientWidth] 相同（半角格）。
     *
     * 原版按钮是 `ConsoleButtonString`，折行时可用 `DivideAt` 拆到两行
     * （PrintStringBuffer.cs:222-249、ConsoleButtonString.cs）。本类同样拆分：
     * 跨行按钮在每一行各产生一条 [Button]，[value] 相同，列号是**该行内**的列。
     *
     * @property value Long（数值按钮）或 String（字符串按钮）。
     *
     * **条数口径 = 原版 `ConsoleDisplayLine.buttons` 数组长度**（ConsoleDisplayLine.cs:18,28），
     * 一条 `ConsoleButtonString` 对应本表一条：
     * - 同一按钮横跨多个样式段（`ConsoleButtonString.StrArray` 是节点数组，
     *   ConsoleButtonString.cs:19、:296-303；自动按钮的按字符切分把多个样式节点装进同一条按钮，
     *   PrintStringBuffer.cs:347-387）⇒ **一条**；
     * - 两次显式 PRINTBUTTON 即使值相同、列号相接也是**两条**（PrintStringBuffer.cs:103-123
     *   每次都 `fromCssToButton` 后再 `createButton`）。
     * 本类靠 [PendingSpan] 的源按钮编号分辨这两者（见 `RowBuilder.addButton` 的 KDoc）。
     */
    data class Button(
        val startCol: Int,
        val endCol: Int,
        val value: Any,
        /** 按钮来源（显式 PRINTBUTTON / HTML / 文本自动按钮），右侧栏按它标来源。 */
        val origin: OptionOrigin = OptionOrigin.EXPLICIT,
        /** 按钮上印的字（已按源按钮的整段文本记录，UI 可再截断/清序号）。 */
        val label: String = "",
        /**
         * 按钮对象的世代（原版 `ConsoleButtonString.Generation`，ConsoleButtonString.cs:49）。
         * 打印时取 [newButtonGeneration]；等待入口按 [lastButtonGeneration] 判可点。
         */
        val generation: Long = 0L,
        /**
         * **源按钮**编号（[PendingSpan] 侧的 `ButtonSpan.groupId`，发号器 [nextButtonGroupId]）。
         *
         * 一条 `ConsoleButtonString` 折行后在每一行各留一条本类型（见本类 KDoc 的条数口径），
         * 它们靠这个编号认出自己同属一条按钮；两次显式 `PRINTBUTTON` 即使值相同也是两个编号。
         * 右侧栏据此合并呈现（[dedupeOptions]），不再拿 `value` 当身份。
         * `-1` = 身份未知（手工构造的本类型），只会被逐条保留、不参与合并。
         */
        val groupId: Int = ID_UNKNOWN,
    ) {
        /**
         * 按钮的**语义身份 = 行内区间 + 值**，不含来源/标签/编号元数据。
         *
         * 来源/标签是 2026-10-25 给选项快照新增的旁路信息；命中几何、折行合并、条数口径
         * 仍由原版 `ConsoleButtonString` 的三元组决定。测试与诊断按原口径比较 `buttons`
         * 时不应因为多了元数据而失真。
         */
        override fun equals(other: Any?): Boolean =
            other is Button && startCol == other.startCol && endCol == other.endCol && value == other.value

        override fun hashCode(): Int {
            var result = startCol
            result = 31 * result + endCol
            result = 31 * result + value.hashCode()
            return result
        }
    }

    /**
     * 一行 HTML 里的 `<button>`（只给选项快照用，不参与命中几何）。
     *
     * 几何仍由渲染层 `HtmlRunFlatten` 从同一段 HTML 里算；核心层只抽“这行确实有一个
     * 带值的 HTML 按钮”这件事，供 [currentOptions] 使用。两边解析的是同一份原文，
     * 不读快照文本、不猜。
     *
     * @property generation 该 HTML 按钮对象打印时的世代（原版 `Html2ButtonList` 产出的
     *   `ConsoleButtonString` 同样带 `Generation`）；等待入口按它判可点。
     * @property startCol 按钮可见文本在**本行**的起始半角格；`option=true` 的缓冲 HTML 在
     *   排版时再加上所在段的列偏移。只用于行优先排序，不参与点击几何（几何仍归 render）。
     * @property endCol 行内结束半角格（半开区间）。
     * @property groupId **源按钮**编号（[extractHtmlOptions] 为每一次 `<button>` 开标签发出）。
     *   一颗按钮跨 `<br>` 时 [splitHtmlLines] 行尾补闭合、行首重放同一段开标签原文，
     *   重放行抽到的仍是同一个编号 ⇒ 右侧栏并成一枚（[dedupeOptions]）；
     *   同一屏上两颗不同的按钮即使 `value` 相同也是两个编号 ⇒ 各留一枚。
     *   `-1` = 身份未知，只会被逐条保留。
     */
    data class HtmlOption(
        val value: Any,
        val label: String,
        val generation: Long = 0L,
        val startCol: Int = 0,
        val endCol: Int = 0,
        val groupId: Int = ID_UNKNOWN,
    )

    /**
     * 一条显示行。
     *
     * @property isLineEnd 本行是否结束一个逻辑行。PRINTN（lineEnd=false）的末行为 false，
     *   下一次 flush 会与该行合并（EmueraConsole.cs:2948-2977）；折行的中间行恒为 false。
     * @property segments 文本段（按列顺序）。
     * @property buttons 按钮区间（按列顺序）。
     * @property isLogicalLine 本行是否为一个逻辑行的**首行**（与原版
     *   ConsoleDisplayLine.IsLogicalLine 口径一致：折行时只有首段为 true，PrintStringBuffer.cs:193,206）。
     *   逻辑行的判定不能只看本标志：PRINTN 的续行会把下一批内容并入本组。
     * @property isTemporary REUSELASTLINE 产生的临时行；下一次正常输出会先删掉它
     *   （EmueraConsole.cs:2891-2893）。
     * @property alignment 行对齐快照（ALIGNMENT，Process.cs:1536-1552）。
     * @property backColor 背景色 `0xRRGGBB`（SETBGCOLOR 是控制台全局色，本类按行快照供渲染层消费）。
     * @property noBreak 本行**不参与折行**（原版 `nobr`）。
     *
     * 这个事实在原版是 `Flush` 的**调用参数**、不落在 `ConsoleDisplayLine` 上：
     * `FlushSingleLine` 传 `nobr: true`（PrintStringBuffer.cs:173），普通 `Flush` 传 `nobr: false`
     * （:182），折行判定读的正是它（:217 `if (nobr || PointX + Width <= num)`）——
     * 也就是说「这条显示行不会再被拆」在原版是**布局已经做完**的结果，`ConsoleDisplayLine`
     * （ConsoleDisplayLine.cs:8-48）里根本没有这个字段。
     *
     * 本仓必须显式带出：核心层排完的行还要过渲染层的 `LineFolder` **再折一次**
     * （`render/.../grid/LineFolder.kt` 的 `fold`，它同样是 `ButtonsToDisplayLines` 的直译），
     * 不把 `nobr` 带过去，PRINTSINGLE / REUSELASTLINE / 错误行这几类「一行不折」的行会被
     * 渲染层按宽度二次拆开 —— 原版从不拆（:217），超宽就画到右沿外。
     *
     * 取值为 true 的产生点（全部在 `FlushSingleLine` 路径上）：
     * PRINTSINGLE（EmueraConsole.cs:3147-3165 → `BufferToSingleLine` :3339-3353 → :173）、
     * REUSELASTLINE 临时行（:3053-3056 同一个 `PrintSingleLine(temporary:true)`）、
     * 错误行（:3098-3115 → `PrintPlainwithSingleLine` :3361-3373 → :173）。
     * 独立 HTML 行（`printHtml(option=false)`）**不**置位：原版走 `Html2DisplayLine`
     * （HtmlManager.cs:517-520）→ `ButtonsToDisplayLines(..., htmlAnalzeState.FlagNobr, ...)`
     * （:698），nobr 由 HTML 自己的 `<nobr>` 标签决定（:687-694 的 `FlagNobr`）。core 侧
     * 不把 nobr 落到本标志（GFX-03 的切行只做结构，跨行重放让 render 侧 HtmlRunFlatten
     * 对每一行各自解析出 noBreak），本类恒 false。
     */
    data class DisplayLine(
        val isLineEnd: Boolean,
        val segments: List<Segment>,
        val buttons: List<Button> = emptyList(),
        val isLogicalLine: Boolean = false,
        val isTemporary: Boolean = false,
        val alignment: Alignment = Alignment.LEFT,
        val backColor: Int = LineBufferConsoleSink.DEFAULT_BACK_COLOR,
        val noBreak: Boolean = false,
        /**
         * 行的单调序号（只在 [appendGroup] 落列表时分配，不随行删除/淘汰复用）。
         *
         * 右侧选项栏用它切“当前页”：进入输入等待时只收 `serial > 上次等待水位` 的行。
         * 这是原版 `Generation`（`ConsoleButtonString.cs:49`、`EmueraConsole.cs:783-825`）
         * 在本仓的等价锚点 —— 比“扫日志尾部 N 行”精确，也不依赖 CLEARLINE 一定出现。
         */
        val serial: Long = 0L,
        /** 本行 HTML 里解析出的 `<button>`（只给 [currentOptions]；命中几何另走渲染层）。 */
        val htmlOptions: List<HtmlOption> = emptyList(),
    )

    /**
     * 行内某一段的定位视图（M4 渲染与命中检测用）。
     *
     * [startCol] 是段首字符在**本行**的起始半角格，[widthCells] 是段占格数；
     * 二者都由 [CellWidth] 算出，与 [Button] 的列号同一坐标系，因此
     * `startCol + widthCells` 与本行 `buttons` 的列可直接比较。
     * 段宽不等于 `segment.text.length`（全角 2 格、代理对占两格但只有一个码位）。
     */
    data class PlacedSegment(val startCol: Int, val widthCells: Int, val segment: Segment)

    /**
     * 按钮表的一行：按钮所在显示行下标 + 行内半开区间 `[startCol, endCol)` + 按钮值。
     *
     * 跨行按钮在每行各有一条（列号是**该行内**的列，value 相同），与原版
     * [DisplayLine.buttons] 的拆分口径一致（PrintStringBuffer.cs:222-249）。
     */
    data class PlacedButton(val lineIndex: Int, val startCol: Int, val endCol: Int, val value: Any)

    // ------------------------------------------------------------------
    // ConsoleSink：查询侧
    // ------------------------------------------------------------------

    private var logicalLineCount = 0L

    /**
     * LINECOUNT：只统计已完成（末行 isLineEnd=true）的逻辑行。
     * spec: EmueraConsole.cs:509 `LineCount → logicalLineCount`；计数点只有一处 ——
     * addDisplayLine 里 `if (line.IsLogicalLine) { if (末行.IsLineEnd) logicalLineCount++ }`
     * （:2971-2977），删除侧回退见 :3014-3021、:3032-3036。
     *
     * **与原版的一处已知偏差（本类自洽，不改物理行的 isLineEnd 形状）**：
     * 原版 flush 只改写**末段**的 IsLineEnd（PrintStringBuffer.cs:174、:183 `array[^1].IsLineEnd =
     * isLastLineEnd`），其余段的 IsLineEnd 由构造函数缺省 true（ConsoleDisplayLine.cs:16、:32-48）。
     * 于是「PRINTN + 折行」的一次 flush 产出 `[首段(IsLogicalLine, IsLineEnd=true),
     * 末段(IsLineEnd=isLastLineEnd=false)]`，addDisplayLine 在首段就计 1；等下一批并入末段后
     * 并入行又是 `IsLogicalLine && IsLineEnd` ⇒ 再计 1，同一个逻辑行被计两次。
     * 本类把「行结束」定义为**逻辑行结束**（只有末段可能 isLineEnd=true，续行恒 false），
     * 所以 PRINTN 折行在完成前为 0、完成后为 1。
     * TODO(verify)：复刻原版需要把续行的 isLineEnd 置 true，那会改变渲染层快照语义
     * （需要与 render 的 ConsoleFeed 一起改），golden 610 行没有该场景，暂不动。
     */
    override val lineCount: Long get() = logicalLineCount

    /**
     * 打印缓冲空态（原版 PrintBuffer.IsEmpty，PrintStringBuffer.cs:39-48；flush 后 clearBuffer
     * → EmueraConsole.cs:273-278）。BINPUT 系据此决定是否先补换行（FunctionIdentifier.cs:2123-2126）。
     */
    override val printingBufferEmpty: Boolean get() = pending.isEmpty()

    /** 当前字体样式位（FONTBOLD/FONTITALIC 的 OR 语义读出侧，FunctionIdentifier.cs:1574-1606）。 */
    override val fontStyle: Int get() = styleMask

    /**
     * 当前前景色 `0xRRGGBB`；**null = 未显式设置**（SETCOLOR 之后才有值，RESETCOLOR 清回 null）。
     *
     * 读出侧存在理由（CONSOLE-2 → HOST-STYLE）：GETCOLOR 家族（ConsoleQueryMethods.kt:52-94）要的是
     * 「当前值」与「未设置时回落 Config.ForeColor」这两种状态的**区分**，
     * 而 ConsoleSink 早期只有写入侧。原版数据源 = `userStyle.Color`（EmueraConsole.cs:2820-2824，
     * 初值 = Config.ForeColor：:207），回落项 = `Config.ForeColor`
     * （[DEFAULT_FORE_COLOR]，ConfigData.cs:68）。
     * RESETCOLOR 把当前值清回默认（FunctionIdentifier.cs:1501 `SetStringStyle(Config.ForeColor)`），
     * 本类用 null 表示同一件事 —— **本属性就是这份状态的唯一存放处**，宿主快照从这里派生。
     */
    override val foregroundColor: Int? get() = foreColor

    /** 当前背景色 `0xRRGGBB`；null = 未显式设置（回落 [DEFAULT_BACK_COLOR]，ConfigData.cs:69；原版初值同，EmueraConsole.cs:200）。 */
    override val backgroundColor: Int? get() = backColor

    /** 当前字体名（GETFONT 的数据源；初始值 = 构造参数 [defaultFontName]，即 `Config.FontName`）。 */
    override val fontName: String get() = fontNameCur

    /** 当前对齐方式（CURRENTALIGN 的数据源；出厂 LEFT，Process.cs:1535-1552）。 */
    override val alignment: Alignment get() = align

    /**
     * 当前重绘状态：0=None / 1=Normal（**已折减**，只留 bit0；EmueraConsole.cs:1693-1707，
     * `ConsoleRedraw` 枚举序 None=0 / Normal=1，ConsoleRedraw.cs:6-10）。
     * bit1 的「立即重绘」是副作用，不进状态，故 [setRedraw] 折减后才写进来。
     */
    override var redraw: Int = 0
        private set

    /**
     * HTML 浮岛原文列表（HTML_PRINT_ISLAND / HTML_PRINT_ISLAND_CLEAR）。
     *
     * 原版数据源是 `EmueraConsole._htmlElementList`（EmueraConsole.cs:166），
     * 写点 `PrintHTMLIsland`/`ClearHTMLIsland`（:2869、:2874）在进程侧、读点在绘制侧的
     * `OnPaint` 遍历（:1880）—— 与 [lines] 是同一组「进程写 / 绘制读」关系，只是原版
     * 两边跑在同一个消息泵线程上（无锁；本类拆线程后必须补，见类文档的线程模型）。
     *
     * 快照同样必须在 `synchronized(displayLines)` 内完成（与 [lines] 同一口径）：
     * `islands` 是普通 `ArrayList`，`toList` 对它是 `toArray` → `Arrays.copyOf(elementData, size)`，
     * 而 `elementData` 与 `size` 互不同步 ⇒ 撞上 [clearHtmlIslands] 的摘除循环、或撞上
     * [addHtmlIsland] 触发的扩容（拿到旧数组配新 size），都会拷回 null 槽。
     * 2026-10-22 在未加锁版本上以 1 写 3 读压测 2 秒实测到三个读线程全部读到过 null 槽
     * （`LineBufferSinkConcurrentTest.concurrentIslandWritesAndReadsNeverExposePartialSnapshot`）。
     * 元素是 `String`（不可变），锁内拷完即可安全跨线程读。
     */
    fun htmlIslands(): List<String> = synchronized(displayLines) { islands.toList() }

    /**
     * 浮岛内容的水位（每次 [addHtmlIsland] / [clearHtmlIslands] 真的改了内容就 +1）。
     *
     * 为什么需要（2026-10-29）：[htmlIslands] 是**加锁 + 整表拷贝**，而渲染侧的
     * `ConsoleFeed.pump()` 原本**每帧**无条件调它（浮岛不参与 [revision]，只能每帧搬）。
     * 外出/地图屏用 `HTML_PRINT_ISLAND` 画整块面板（`qol_MAP.ERB` 的地图格），浮岛里
     * 可能有几十条字符串 —— 每帧一次锁 + 一次 `toList` 纯属浪费，还会给 UI 线程制造
     * 持续分配。有了水位，消费侧只在**内容真的变了**时才拷。
     *
     * 读侧无锁：`@Volatile` 单字段发布，与 [revision] 同一口径（读到旧值最多多拷一次，
     * 不会漏更新 —— 写侧先改内容再抬水位，抬之前的读会看到旧水位但内容已新，
     * 下一次抬水位后仍会再拷一次）。
     */
    fun htmlIslandsVersion(): Long = islandsVersion

    /**
     * 显示行只读视图（最旧在前），对应原版 `displayLineList`（EmueraConsole.cs:238）。
     *
     * 这是 M4 渲染的**唯一行数据源**：返回 `ArrayList` 的浅拷贝，调用方拿到的列表
     * 不会随 sink 后续输出变化；元素 [DisplayLine]/[Segment]/[Button] 均为不可变
     * `data class`，可安全跨线程读（ADR-003 单生产单消费）。
     *
     * 含 REUSELASTLINE 的临时行（`isTemporary=true`）——它是要**画**的，
     * 只是不进日志回放；要日志文本用 [loggedLines]。
     */
    // 快照必须在锁内完成：toList 走迭代器（有 modCount 校验），锁外迭代会撞写侧并发修改；
    // `ArrayList` 本身没有安全发布，锁外拷贝还可能拷到扩容/摘除半途的 elementData（尾部 null 元素）。
    // 元素是不可变 data class，锁内拷贝完成后返回值即可安全跨线程读。
    fun lines(): List<DisplayLine> = synchronized(displayLines) { displayLines.toList() }

    /** [lines] 的旧名（E2E 装置既有调用点），语义完全相同。 */
    fun snapshot(): List<DisplayLine> = lines()

    /**
     * [ConsoleSink.displayedLines] 的生产实现：直接给 [lines] 的只读快照。
     *
     * 原版 `EmueraConsole.DisplayLineList`（EmueraConsole.cs:238）就是这一份列表，
     * HTML_GETPRINTEDSTR（:3546-3576）与 GETDISPLAYLINE（FunctionMethodCreator.cs:8415-8440）
     * 都直接读它；这里不复制第二份状态，快照的锁口径与线程安全见 [lines]。
     * 含 REUSELASTLINE 的临时行（原版同样在 displayLineList 里，直到下一次 addDisplayLine
     * 才被删，EmueraConsole.cs:2891-2893、:3053-3056）。
     */
    override val displayedLines: List<DisplayLine> get() = lines()

    /**
     * [ConsoleSink.popPrintingBuffer] 的生产实现：直接走 [popDisplayingLines]。
     *
     * 读取即消费：原版 `PopDisplayingLines`（EmueraConsole.cs:3578-3588）在缓冲非空时
     * `printBuffer.Flush(...)`（PrintStringBuffer.cs:179-186），`Flush` 末尾 `clearBuffer()`
     * （:273-278）⇒ 返回后 [printingBufferEmpty] 必为 true，第二次读到空表。
     * 弹出的行只排版、不落 displayLineList：不调 appendGroup、不动 LINECOUNT 与 [revision]
     * （方法体没有 addDisplayLine，见 [popDisplayingLines] 的 KDoc）。
     */
    override val popPrintingBuffer: List<DisplayLine> get() = popDisplayingLines()

    /**
     * [lines] 可见内容的版本号：初值 0，只增不减，每次可见变化 +1（CONSOLE-FEED）。
     *
     * 用途是给渲染层的快照桥（render 模块的 ConsoleFeed）一个 O(1) 的「要不要重交快照」判据：
     * 原版同一条判断是 `RefreshStrings` 在 `lastDrawnLineNo == lineNo` 时早退
     * （EmueraConsole.cs:1758），水位要等画完才写（:1942-1949）；本类没有「画」这个动作，
     * 所以把被观察的状态收敛成一个单调计数。
     *
     * 自增点就是显示行的两个中央变更点：
     * - [appendGroup] 末尾 —— flush 产行（含 PRINTN 的 `isLineEnd=false` 行）、PRINTN 并入改写末行、
     *   REUSELASTLINE 临时行先删、超出 [maxLines] 淘汰头部行（对应 `displayLineList.Add` 与
     *   `deleteLine(1)` 的并入路径，EmueraConsole.cs:2947-2970）；
     * - [deleteLine] 实际摘掉显示行时（CLEARLINE，EmueraConsole.cs:2999-3051）。
     *
     * 只追加打印缓冲而未 flush、只改样式/对齐/重绘状态时**不**自增 —— 那些都不改变 [lines]。
     * 计数允许比真实变化多（只影响桥接效率，不影响正确性），但不许漏增。
     *
     * 发布协议：两个自增点都在 `synchronized(displayLines)` 临界区内，所以读侧看到新的
     * [revision] 再去取 [lines]，拿到的快照至少同样新（不会「revision 已涨、行还是旧的」）。
     */
    // @Volatile：UI 线程不经锁直接读本字段（O(1) 的「要不要重交快照」判据），
    // 必须保证写侧自增对读侧立即可见，否则 UI 线程可能长期看到过期水位。
    @Volatile
    var revision: Long = 0L
        private set

    /** 显示行条数（含临时行与折行续行）。逻辑行数见 [lineCount]（LINECOUNT 口径）。 */
    // size 读虽然不会撕裂，但为了与写侧的增删互斥（避免读到同一次复合操作的中途态）、
    // 与其他 displayLines 读点保持同一把锁，统一走 synchronized。
    val displayLineCount: Int get() = synchronized(displayLines) { displayLines.size }

    /**
     * 按钮表：把 [lines] 里所有行的 [DisplayLine.buttons] 按（行号升序，列号升序）摊平。
     *
     * 供 M4 命中检测直接建索引；空表表示当前没有任何可点区域。
     */
    fun buttons(): List<PlacedButton> {
        val out = ArrayList<PlacedButton>()
        // 遍历期间 displayLines 可能被 VM 线程增删，整趟遍历必须持锁。
        synchronized(displayLines) {
            displayLines.forEachIndexed { index, line ->
                for (b in line.buttons) out.add(PlacedButton(index, b.startCol, b.endCol, b.value))
            }
        }
        return out
    }

    /**
     * 当前输入等待页的选项快照（右侧栏唯一数据源）。
     *
     * 返回的是 [noteInputWait] 在等待入口按**原版世代**（`Generation ==
     * lastButtonGeneration`）冻结的那一份，并按等待请求做了取值类型过滤。没有等待 /
     * 非取值等待 / 当前页没有按钮时返回空表。读侧是 UI 线程，不需要拿 [displayLines] 锁。
     */
    fun currentOptions(): List<ConsoleOption> = optionSnapshot

    /**
     * 输入等待入口：按原版 `EmueraConsole.newGeneration()` 推进按钮世代，并冻结可点集合。
     *
     * 口径逐条对：
     * - 按钮对象在**打印时**盖 `generation = newButtonGeneration` 并 `updateGeneration()`
     *   （`ConsoleButtonString.cs:91-138` 的构造 + `EmueraConsole.cs:770-774`）。
     * - 等待入口先跑 `PrintFlush(force:false)`（`EmueraConsole.cs:1094→:1107`），再跑
     *   `newGeneration()`（`:783-823`）：脚本行身份变了且本页没印按钮 ⇒ `lastButtonGeneration`
     *   前移，旧页按钮作废；同一条脚本行重试 ⇒ 世代不动，旧按钮仍是本页。
     * - 非取值等待（WAIT/ENTER/ANY/VOID/鼠标原始键）在原版 `NeedValue == false` 时
     *   `newGeneration()` **直接返回**（`:785-788`），世代完全不动；UI 快照清空。
     *
     * 可点集合 = 全部现存显示行 + 浮岛的按钮里 `generation == lastButtonGeneration` 的那些，
     * 与 `SelectedString` 的命中判据一致（`EmueraConsole.cs:2439`、`:2456`）；不再按
     * 「上一次等待之后的新行」切页，也不再按 `[N]` 的前缀形态猜。
     */
    override fun noteInputWait(request: InputRequest) {
        // 原版进入等待前 RunEmueraProgram 会 PrintFlush(force: false)（EmueraConsole.cs:1094→:1107）：
        // 没有以换行收尾的 PRINTN 段、以及 `HTML_PRINT …, 1`（追加到打印缓冲的按钮段）
        // 都在这一刻上屏。不补这一下，标题页那个 `<div>…<button value='4'>五子棋</button></div>`
        // 会永远留在缓冲里 —— 屏上没有、右侧栏也没有，而原版是可见可点的。
        flushPending(force = false)
        val valueWait = request.form == InputRequest.RequestForm.VALUE ||
            request.form == InputRequest.RequestForm.ANY_VALUE
        if (!valueWait) {
            // NeedValue == false：原版 newGeneration 直接返回，世代原样保留。
            optionSnapshot = emptyList()
            publishOptionsIfDirty()
            return
        }
        newGeneration(request)
        optionSnapshot = collectClickableOptions(request)
        // 上面这次 flush 可能刚落下一批按钮，而它发生在 publishFrame 之后（见
        // [publishOptionsIfDirty] 的 KDoc）：帧口径快照在这里补一次，且必须放在
        // [newGeneration] 之后 —— 世代已定，收集到的才是玩家眼前这一屏的可点集合。
        publishOptionsIfDirty()
    }

    /**
     * 原版 `newGeneration()`（EmueraConsole.cs:783-823）的逐句移植。
     *
     * [request] 的 `scriptLine` 是 `process.getCurrentLine` 的身份位；`-1`（宿主未接线）
     * 时退回行水位 [lineSerial] —— 只影响「脚本没打印却换了等待语句」这一种边角。
     */
    private fun newGeneration(request: InputRequest) {
        val currentLine = if (request.scriptLine >= 0L) request.scriptLine else lineSerial
        if (!updatedGeneration && currentLine != lastInputLine) {
            lastButtonGeneration = newButtonGeneration
        } else {
            updatedGeneration = false
        }
        lastInputLine = currentLine
        when (request.type) {
            InputRequest.InputType.INT -> {
                if (lastButtonGeneration == newButtonGeneration) {
                    newButtonGeneration++
                } else if (!lastButtonIsInput) {
                    lastButtonGeneration = newButtonGeneration
                }
                lastButtonIsInput = true
            }
            InputRequest.InputType.STR -> {
                if (lastButtonGeneration == newButtonGeneration) {
                    newButtonGeneration++
                } else if (lastButtonIsInput) {
                    lastButtonGeneration = newButtonGeneration
                }
                lastButtonIsInput = false
            }
        }
    }

    /**
     * 按钮对象的世代登记（原版 `ConsoleButtonString` 构造 + `UpdateGeneration()`）：
     * 返回本次创建应盖的世代，并把 `lastButtonGeneration` 对齐到它、置脏。
     */
    private fun stampButtonGeneration(): Long {
        val generation = newButtonGeneration
        lastButtonGeneration = generation
        updatedGeneration = true
        return generation
    }

    /**
     * 按世代 + 等待类型收集可点选项（原版 `SelectedString` 的判据）。
     *
     * 扫描**全部现存显示行**（含滚出可视区但仍在列表里的行）与浮岛按钮，只保留
     * `generation == lastButtonGeneration` 的按钮；[dedupeOptions] 只把**同一颗源按钮**的
     * 多条重放并成一枚，不同源的按钮即使同值也各占一枚（右侧栏照屏上逐颗列）。
     */
    private fun collectClickableOptions(request: InputRequest): List<ConsoleOption> {
        val out = ArrayList<ConsoleOption>()
        synchronized(displayLines) {
            out.addAll(collectGenerationOptionsLocked())
        }
        return orderOptions(dedupeOptions(out)).filter { ConsoleOptionFilter.accepts(request, it) }
    }

    /**
     * 右侧栏的**显示兜底**：当前世代里所有按钮/HTML 按钮，**不看等待类型**。
     *
     * 为什么需要它：QoL 的地图/外出屏（`qol_MAP.ERB:1033` 的 `AWAIT_UI_INPUT`）用
     * `AWAIT` + `GETKEYTRIGGERED` + `MOUSEB` 轮询，**根本不走 `INPUT`**，`noteInputWait`
     * 的那份快照会停在上一个真正 INPUT 的页面 —— 玩家看到的就是「旧场景按钮还在、
     * 本场景按钮反而没有」。宿主在没有等待方（`QueueInputSink.isAwaiting == false`）时
     * 用本方法兜底；有等待方时仍以 [currentOptions] 的等待过滤为准（WAIT/ENTER 页不放行）。
     *
     * ## 这是**活表**读数，生产消费点请用 [publishedOptions]
     *
     * 本方法直接扫 `displayLines` 的当前内容，锁只保证「单次读是原子的」，**不保证读到的是
     * 完整一帧**：脚本 `CLEARLINE` 之后、重印到一半时来读，拿到的就是残缺集合
     * （动态地图调试屏每 200 ms 整屏重印一次，2026-10-27 真机「按钮数在 9/11/29/32 之间跳」
     * 就是这个读数撞上了重印窗口）。留活表口径是因为 DevTools / `uipreview` 的
     * `/state/chips` 要的正是「引擎此刻认为有什么」，它们不在绘制路径上。
     *
     * 排序是行优先：`(lineSerial, startCol, endCol)` —— 原版按钮的阅读顺序就是显示行顺序，
     * 同一行内按列从左到右。旧实现按收集顺序（每行先 buttons 再 htmlOptions）排，跨行/
     * 混合来源的页面会串序。
     */
    fun displayOptions(): List<ConsoleOption> {
        val out = ArrayList<ConsoleOption>()
        synchronized(displayLines) {
            out.addAll(collectGenerationOptionsLocked())
        }
        return orderOptions(dedupeOptions(out))
    }

    /**
     * **帧一致的**显示兜底：与 [revision] 同一处发布，读到的一定是**当前这一帧**的完整按钮集。
     *
     * 语义是「玩家眼前那一屏有哪些可点」，与 `ConsoleFeed` 交给画布的那份快照同源同刻：
     * 发布点只有让出边界（`publishFrame` / 输入等待 / `REDRAW 1`），而 `CLEARLINE` 与逐行
     * 重印之间的中间态**不会被发布**（`gateDirty` 只记脏）。因此右侧栏的按钮集不会再跟着
     * 重印进度变化 —— 画布显示第 N 帧，栏里就是第 N 帧的按钮。
     *
     * 与 [displayOptions] 的分工：那个是活表（DevTools / 预览要「此刻」），本方法是帧口径
     * （壳层要「屏上」）。**生产壳层（`GameHost.currentOptions` / `isCurrentGeneration`）
     * 一律用本方法**，用活表会在重印窗口里读到残缺集合。
     *
     * `frameGateEnabled == false`（「一屏一帧」关掉）时退回活表：那时连画布都是逐条发布的，
     * 再给栏加一层帧口径只会让两者对不上。
     *
     * 读侧是 UI 线程，不经锁（[frameOptions] 是 `@Volatile` 的单次引用发布，发布后不再改动）。
     */
    fun publishedOptions(): List<ConsoleOption> =
        if (frameGateEnabled) frameOptions else displayOptions()

    /** 锁内枚举当前世代（`lastButtonGeneration`）的全部按钮与浮岛按钮。 */
    private fun collectGenerationOptionsLocked(): List<ConsoleOption> {
        val out = ArrayList<ConsoleOption>()
        val gen = lastButtonGeneration
        for (line in displayLines) {
            for (b in line.buttons) {
                if (b.generation != gen) continue
                out.add(
                    ConsoleOption(
                        value = b.value,
                        label = cleanOptionLabel(b.label, b.value),
                        origin = b.origin,
                        lineSerial = line.serial,
                        startCol = b.startCol,
                        endCol = b.endCol,
                        generation = b.generation,
                        ordinal = ordinalOf(b.value, b.label),
                        groupId = b.groupId,
                    ),
                )
            }
            for (h in line.htmlOptions) {
                if (h.generation != gen) continue
                out.add(
                    ConsoleOption(
                        value = h.value,
                        label = cleanOptionLabel(h.label, h.value),
                        origin = OptionOrigin.HTML,
                        lineSerial = line.serial,
                        startCol = h.startCol,
                        endCol = h.endCol,
                        generation = h.generation,
                        ordinal = ordinalOf(h.value, h.label),
                        groupId = h.groupId,
                    ),
                )
            }
        }
        // 浮岛是覆盖层，不在正文行序里；放在最后（同一 position 内按值稳定）。
        for (h in islandOptions) {
            if (h.generation != gen) continue
            out.add(
                ConsoleOption(
                    value = h.value,
                    label = cleanOptionLabel(h.label, h.value),
                    origin = OptionOrigin.HTML,
                    lineSerial = Long.MAX_VALUE,
                    generation = h.generation,
                    ordinal = ordinalOf(h.value, h.label),
                    groupId = h.groupId,
                ),
            )
        }
        return out
    }

    /** 行优先排序：显示行 → 行内起始列 → 结束列 → 值（仅作稳定兜底）。 */
    private fun orderOptions(options: List<ConsoleOption>): List<ConsoleOption> = options.sortedWith(
        compareBy<ConsoleOption>({ it.lineSerial }, { it.startCol }, { it.endCol }, { it.value.toString() }),
    )

    /**
     * 右侧栏的**呈现合并**：同一颗源按钮（[ConsoleOption.groupId] 相同）折行或跨 `<br>` 重放
     * 出来的几条留一条，其余原样保留。
     *
     * 键**不是 `value`**。旧口径按 value 先到先得，把「同屏两颗编号不同、值却相同」的按钮
     * 吞掉一枚 —— 地图格子的「2」与同屏菜单的「2」就是这样丢的（2026-10-29 玩家反馈：
     * 排在最底下那枚识别不到）。原版这两条是两份 `ConsoleButtonString`
     * （`PrintStringBuffer.cs:103-123` 每次 `AppendButton` 都新建一条；`ConsoleDisplayLine.cs:18,28`
     * 的 `buttons` 数组各占一格），点哪颗提交哪颗的值，屏上也各画一处 ⇒ 栏里也该各列一枚；
     * 渲染层的命中表本来就是按源身份合并的（`ButtonLayer.attach` 的 `sameSource`），
     * 本方法只是把右侧栏对齐到同一口径。
     *
     * [ID_UNKNOWN]（手工构造、身份未知）不参与合并：宁可多列一枚，不能把两颗按钮并成一颗。
     *
     * 顺序保留打印顺序（与屏幕阅读顺序一致），不做数值排序；保留的那一条由
     * [ConsoleOptionFilter.prefer] 决定（同源的分片里挑有标签的那条）。
     */
    private fun dedupeOptions(options: List<ConsoleOption>): List<ConsoleOption> {
        val out = ArrayList<ConsoleOption>(options.size)
        val index = HashMap<Int, Int>(options.size * 2)
        for (option in options) {
            if (option.groupId == ID_UNKNOWN) {
                out.add(option)
                continue
            }
            val at = index[option.groupId]
            if (at == null) {
                index[option.groupId] = out.size
                out.add(option)
            } else if (ConsoleOptionFilter.prefer(out[at], option)) {
                out[at] = option
            }
        }
        return out
    }

    /**
     * 单行的段定位视图：按列顺序给出每段的起始列与占格数。
     *
     * 宽度一律经 [CellWidth]（与折行、按钮列同一坐标系）；HTML 段按剥标签后的可见文本计宽，
     * 与折行时的估算同源（见 [visibleHtmlWidth] 的 TODO(verify)）。
     */
    fun segmentsOf(line: DisplayLine): List<PlacedSegment> {
        val out = ArrayList<PlacedSegment>(line.segments.size)
        var col = 0
        for (seg in line.segments) {
            val w = segmentWidth(seg)
            out.add(PlacedSegment(col, w, seg))
            col += w
        }
        return out
    }

    /**
     * 日志回放文本行：跳过临时行，其余按段顺序拼接。
     *
     * 口径 = 原版 `GetLog` 的文本流（临时行不进日志，EmueraConsole.cs:3053-3056 的
     * temporary 行只用于屏幕显示）；未 flush 的打印缓冲不在结果里（它还没有显示行形态）。
     * 逐字节对拍 golden 时用本方法，不要用 [lines]（后者含续行标记与样式）。
     *
     * 注意 [Kind.HTML] 段在这里保留**原文**（含标签）——本方法是渲染层的行文本口径。
     * 唯一例外是被自动按钮吞掉的 HTML 段（`[...]` 跨过它时落成 [Kind.BUTTON] 的可见文本，
     * 见 [splitCrossNode] 的 TODO(verify)），那种行的本方法给出的是可见文本。
     * 需要「PC 日志里 HTML 行的可见文本」时调用方自己走 [htmlVisibleText]（CONSOLE-2
     * 的 E2E 对拍口径即如此，标签剥离规则只有一份，见该函数 KDoc）。
     */
    // filter/map 同样走迭代器遍历 displayLines，与 toList 一样要在锁内完成快照。
    fun loggedLines(): List<String> =
        synchronized(displayLines) { displayLines.filterNot { it.isTemporary }.map { textOf(it) } }

    /** 显示行的纯文本：各段文本顺序拼接（对应 ConsoleDisplayLine.ToString()，:127-140）。 */
    fun textOf(line: DisplayLine): String = line.segments.joinToString("") { it.text }

    /**
     * 打印中缓冲的弹出侧（HTML_POPPRINTINGSTR 的数据源）。
     *
     * 原版 `EmueraConsole.PopDisplayingLines`（EmueraConsole.cs:3578-3588），方法体只有四句：
     * `!Enabled` → null（:3580-3583）、`printBuffer.IsEmpty` → null（:3584-3587）、
     * `return printBuffer.Flush(stringMeasure, force_temporary)`（:3588）。逐条对拍：
     * 1. **返回哪些行** = 这一次 `Flush` 排出来的全部显示行（PrintStringBuffer.cs:179-186 →
     *    `ButtonsToDisplayLines` :196-266），即「弹出之前还没落行的全部内容」；
     *    缓冲空 → 原版返回 null、函数侧转成 ""（FunctionMethodCreator.cs:6123-6127），
     *    本方法返回空表 —— 同一件事的两种表示。
     * 2. **顺序** = 自上而下、自左而右的显示顺序（`ButtonsToDisplayLines` 按按钮表顺序填
     *    `list2`、满行才 `list.Add`（:207-251），最后追加残行 :259-262），
     *    与 [flushPending] 的 `buildRows` 同序；首行 `isLogicalLine=true`、其余折行 false
     *    （:206 `firstLine = !subDiv`、:242、:261）。
     * 3. **弹出后缓冲里剩什么** = 什么都不剩：`Flush` 末尾 `clearBuffer()`（:184 → :273-278
     *    清 builder/m_stringList/m_buttonList）⇒ [printingBufferEmpty] 变 true，
     *    且 `isLastLineEnd` **不**被清（:24 只在下次 Append 时更新，:68）。
     * 4. 返回的行**不进** displayLineList：方法体没有 `addRangeDisplayLine`
     *    （换行路径 `NewLine`→`PrintFlush` 的 :3335 → :3375-3385，**落列表那一句是 :3384**；
     *    HTML 路径 :3221-3224 同样显式进列表）⇒ 这几行不上屏、不进日志、
     *    不算 LINECOUNT，后续 GETDISPLAYLINE / CONSOLELINE 也看不到它们。
     *    M4-N n-hostconsole 落地这一条：本方法走 [flushPending] 的
     *    `commitToDisplayList = false` 分支（只排版、不落列表、不动 [revision]）。
     *    **排版口径与提交路径完全同源**（同一个 [buildRows]，折行、`isLogicalLine`、
     *    按钮拆分与 [buttonWrap] 一并生效），去掉的只有落列表那一步 ——
     *    见 `ConsolePopDisplayTest` 里钉折行的那条用例。
     *    与之配对的旧断言在 `e2e/ProductionConsoleSinkTest`（非本路文件面），见回执「需组长接线」。
     *
     * 与 [flushPending] 的 force 分支不同，这里**不补空格**：原版只在
     * `PrintFlush(force:true)`（NewLine 路径，:3375-3385）时空缓冲补一个空格，
     * PopDisplayingLines 走的是 `IsEmpty` 早退。
     *
     * 三条**账面差异**（本层不可观测，登记而不硬造实现）：
     * - 原版方法体第一句是 `if (!Enabled) return null`（:3580-3583）。移植侧没有「控制台未启用」
     *   这一态（宿主不接控制台时 `BuiltinHost.popPrintingLines` 直接 null → 函数抛 CodeEE），
     *   故不建模。TODO(verify)：`Enabled` 的写点属 UI 侧，不在 core 层。
     * - 原版把 `force_temporary` 当 Flush 的 temporary 实参传下去（:3588），弹出的行因此可能
     *   带 `IsTemporary=true`。`force_temporary` 只在 ERB 重载路径被写 true
     *   （EmueraConsole.cs:2672-2674、:2723-2725、:2775-2777），ERB 语句无法触达，
     *   故本方法恒按 temporary=false。
     * - 原版折行产出的**非末行** `IsLineEnd` 是构造函数缺省 true（ConsoleDisplayLine.cs:16、:32-48，
     *   `Flush` 只改写末行 :183），本类非末行恒 false（见 [lineCount] KDoc 的同一条已登记偏差）。
     *   弹出的行不进 `displayLineList`，而唯一消费者 `HtmlManager.DisplayLine2Html`
     *   （HtmlManager.cs:381-484）不读 IsLineEnd ⇒ 该差值在原版不可观测，这里也不改形状。
     *
     * 字段侧：本方法交出的是完整 [DisplayLine]（段文本 + 前景/样式位/字体名 + [Button] 列区间 +
     * 对齐 + 逻辑行标记），原版 `ConsoleDisplayLine` 的 `LineNo`（:10，进列表时才填）与
     * `bitmapCacheEnabled`（:24）在原版这条路径上本就是 -1/false，移植侧不存在；
     * `ConsoleButtonString.Title`（tooltip）与 `PointXisLocked`/`RelativePointX`
     * （HtmlManager.cs:414-443 会写成 `title='…'` / `pos='…'`）本类没有对应字段 ⇒
     * TODO(verify)：语料里 HTML_POPPRINTINGSTR 的四个调用点（`ERB/BODY_INFO/PRINT_STATE_BODYGRAPH.ERB:89`、
     * `ERB/fromEN/Custom_Commands/Add_Custom_Commands.ERB:69`、`ERB/fromEN/MISC.ERB:168`、`:1323`）
     * 是否需要 tooltip/绝对定位，卡点 = 移植侧尚无 TOOLTIP 与 PRINTPOS 的按钮侧写点。
     */
    fun popDisplayingLines(): List<DisplayLine> =
        if (pending.isEmpty()) emptyList() else flushPending(force = false, commitToDisplayList = false)

    /**
     * HTML 原文的**可见文本**：去标签 + 还原实体引用。
     *
     * 为什么本类要带这一份：[Kind.HTML] 段在本类里存的是**原文**（含标签，供 M4 渲染层
     * 消费），而原版的显示行文本里根本没有标签——`HtmlManager.Html2DisplayLine`
     * （HtmlManager.cs:517-561）在打印期就把标签解析成样式片段，文本片段逐个过
     * `Unescape`（:558 与 :744-834），所以 `ConsoleDisplayLine.ToString()`
     * （ConsoleDisplayLine.cs:127-140）得到的是纯可见文本。要复刻「日志里的 HTML 行」，
     * 规则必须与 `Html2PlainText`（:714-717 = 去标签 + Unescape）同源。
     *
     * 去标签用**同款正则** `\<[^<]*\>`（:716）；实体表取原版 Unescape 的六项
     * （nbsp/amp/gt/lt/quot/apos，名字大小写不敏感，:776-799）加数字引用
     * `&#123;` / `&#x7B;`（:801-832，原版范围 0..65535）。
     *
     * TODO(verify)：原版对畸形实体（`&` 找不到 `;`、`&;`、非内置名且非 `#` 开头、
     * 超范围）在**打印期**抛 CodeEE（HtmlManager.cs:773-780、:801-831）；本方法发生在
     * **读取期**，按保守口径原样保留那段文本而不是抛错——抛错的位置与原版不一致
     * （原版是 HTML_PRINT 语句失败，这里是日志读取失败）。语料/golden 里没有畸形实体，
     * 需要时再把解析前移进 [printHtml] 并按原版抛。
     *
     * 原版 `<br>` 会断行（一次 Html2DisplayLine 产多条显示行）：直印路径的切行在
     * [splitHtmlLines] 做（GFX-03），到本函数的串都是**切后的单行原文**（br 已剥除），
     * 不需要插换行符；option=true 的缓冲段仍含 br 原文，跨节点拼串时按本口径吃掉。
     */
    fun htmlVisibleText(html: String): String = htmlUnescapeEntities(HTML_TAG_REGEX.replace(html, ""))

    /**
     * 从一段**已确认是 HTML** 的原文里抽 `<button value=…>标签</button>`。
     *
     * 这是引擎侧选项接口的 HTML 来源，不是对普通正文跑正则：
     * - 调用点只有 [printHtml]（`HTML_PRINT` / `HTML_PRINT_ISLAND`，节点种类在
     *   [DisplayLine.segments] 里已经标成 [Kind.HTML]）；
     * - 值保留原版类型分派：纯十进制落 `Long`，其余落 `String`（与
     *   `HtmlRunFlatten` 的 `toLongOrNull() ?: string` 同口径，见 HtmlRunFlatten.kt:427-437）；
     * - 标签只取按钮正文（去 HTML 标签、折空白），不把 HTML 源码当按钮名；**实体不在这里还原**
     *   （还原由 [cleanOptionLabel] / [ordinalOf] 在出快照时做一次，避免读取期二次还原）。
     *
     * 几何不在这里算：命中区仍由渲染层 `HtmlRunFlatten` + `ButtonLayer` 负责。本方法只回答
     * 「这一页真正存在哪些 HTML 按钮」，供 [currentOptions] 使用。
     *
     * @param sources 本行原文里**已经认识的** `<button>` 开标签（`开标签原文 → 源按钮编号`），
     *   由 [splitHtmlLines] 发出：一颗按钮跨 `<br>` 时，行尾补的闭合与下一行行首重放的**是同一段
     *   开标签原文**，靠原文认回来 ⇒ 两行抽到的 [HtmlOption.groupId] 相同，右侧栏并成一枚
     *   （口径同 `HtmlManager.cs:841-852` 的 `HtmlAnalzeState.CurrentButtonTag` 串级延续）。
     *   按原文匹配、匹配一次即摘走，所以同一段原文在一行里出现两次（两颗同值按钮）也会
     *   各拿一个编号；对不上号的（原文之外的畸形 HTML）现发一个新号 = 当作另一颗按钮，
     *   绝不误并。不传（`option=true` 缓冲段、浮岛）即每次出现都是新源。
     */
    private fun extractHtmlOptions(
        html: String,
        clear: ClearButtonScan = ClearButtonScan(false),
        sources: List<Pair<String, Int>> = emptyList(),
    ): List<HtmlOption> {
        if (html.isEmpty()) return emptyList()
        // 注释里的 `<button>` 不是按钮；先剥注释再找开闭标签，避免把注释掉的旧面板翻出来。
        val src = HTML_COMMENT_REGEX.replace(html, "")
        // `<clearbutton>` 区间要先扫（即便本行没有 button 也要把串级状态交下去）。
        val cleared = clearedRanges(src, clear)
        if (!src.contains("<button", ignoreCase = true)) return emptyList()
        val out = ArrayList<HtmlOption>()
        val pending = sources.toMutableList()
        var pos = 0
        // 列号累加：`measuredEnd` 之前的可见文本已经折进 `col`，下一个按钮只需补上
        // 「上一颗按钮闭标签之后到本颗开标签之前」这段间隔的宽度。旧实现对每颗按钮
        // 从下标 0 重算整段前缀（一屏几十颗按钮时是 O(颗数 × 串长²)），既慢又没必要。
        var measuredEnd = 0
        var col = 0
        while (pos < src.length) {
            val open = src.indexOf("<button", pos, ignoreCase = true)
            if (open < 0) break
            // `<buttonX>` 不是按钮标签；标签名后必须是空白、`>` 或 `/`。
            val nameEnd = open + "<button".length
            if (nameEnd < src.length && !isTagBoundary(src[nameEnd])) {
                pos = nameEnd
                continue
            }
            val openEnd = findTagEnd(src, nameEnd)
            if (openEnd < 0) break
            val close = src.indexOf("</button", openEnd + 1, ignoreCase = true)
            if (close < 0) break
            val closeEnd = findTagEnd(src, close + "</button".length)
            if (closeEnd < 0) break

            // 源编号先认、后筛：`<clearbutton>` 区间内的按钮与缺 `value` 的那几颗同样占掉
            // 「本行文本顺序里的一次出现」，剩下的候选才不会串到隔壁的编号上。
            val groupId = takeHtmlButtonSource(pending, src.substring(open, openEnd + 1))

            // 行内位置：按钮**可见文本**在本行已印内容之后。标签/属性不计宽；
            // `<shape space>` 的像素宽这里不算进前缀（只影响同段里像素偏移后的
            // 排序精度，不影响“同一行内按列升序”这一主判据）。同一 HTML 串里
            // 多个按钮按源序自然递增。
            col += CellWidth.of(htmlVisibleText(src.substring(measuredEnd, open)))
            val start = col
            // 标签存**未还原实体**的原文：还原交给 [cleanOptionLabel] / [ordinalOf] 在
            // 出快照时做一次。这里若先还原一次，读取期就还原两次（`&amp;lt;` 会被折成 `<`），
            // 与原版 `Html2DisplayLine` 的**单次** Unescape（HtmlManager.cs:558、:744-834）不符。
            val label = src.substring(openEnd + 1, close)
                .replace(HTML_TAG_REGEX, " ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
            val end = start + CellWidth.of(htmlUnescapeEntities(label))
            col = end
            measuredEnd = closeEnd + 1
            pos = closeEnd + 1

            // `<clearbutton>` 打开期间的按钮在原版被降级成非按钮（HtmlManager.cs:1549-1551）。
            // eraTW 的 CLEARLINE 重绘正是靠它作废上一屏格子按钮：地图页的 `<button value='34'>34`
            // 在移动后重印为 `<clearbutton>…<button value='34'>34…</clearbutton>`，正文还在屏上、
            // 但已经不可点。列宽照算（这些字仍占格子），只不登记成选项。
            if (cleared.any { open >= it[0] && open < it[1] }) continue

            val raw = htmlAttribute(src.substring(nameEnd, openEnd), "value") ?: continue
            val valueText = htmlUnescapeEntities(raw).trim()
            if (valueText.isEmpty()) continue
            val value: Any = valueText.toLongOrNull() ?: valueText
            out.add(HtmlOption(value, label, startCol = start, endCol = end, groupId = groupId))
        }
        return out
    }

    /**
     * 从本行已认识的开标签集合 [pending] 里按**原文**领一个源按钮编号；领不到就现发一个新编号。
     *
     * 领到一个摘走一个：同一段开标签原文在一行里出现两次，就是屏上两颗不同的按钮
     * （例如 `<button value='2'>` 画地图格子、又画菜单项），必须各拿各的编号，
     * [dedupeOptions] 才不会把它们并掉。匹配失败（属性写法不同、注释/引号造成的错位）
     * 一律当新源：宁可多列一枚，不可误并一颗。
     */
    private fun takeHtmlButtonSource(pending: MutableList<Pair<String, Int>>, openRaw: String): Int {
        val at = pending.indexOfFirst { it.first == openRaw }
        if (at < 0) return nextButtonGroupId++
        return pending.removeAt(at).second
    }

    /**
     * `<clearbutton>` 的区间扫描状态：**跨行串级**。
     *
     * [printHtml] 会把同一段 HTML 按显示行切开，开标签可能落在上一行、闭标签落在下一行
     * （真机 2026-10-27 的地图页重绘就是这样）。原版解析状态同样随行推进
     * （`HtmlManager.cs:1549-1551` 的 `state.FlagClearButton`），所以这里由调用方按行序
     * 把 [inside] 传下去，而不是每行重新开一次。
     */
    private class ClearButtonScan(var inside: Boolean)

    /**
     * 本行原文里 `<clearbutton>` 覆盖的区间（源码下标，左闭右开），并把行尾状态写回 [clear]。
     *
     * 只在选项抽取这一侧用：正文照旧绘制（eraTW 重绘的正是同一份字），但区间内的 `<button>`
     * 不算可点 —— 原版在这段里把按钮降级成非按钮（`HtmlManager.cs:1549-1551`），
     * `ButtonStringCreator` 也就不会给它生成可点对象。
     */
    private fun clearedRanges(src: String, clear: ClearButtonScan): List<IntArray> {
        val spans = ArrayList<IntArray>()
        if (clear.inside) spans.add(intArrayOf(0, src.length))
        var pos = 0
        while (true) {
            if (!clear.inside) {
                val open = src.indexOf("<clearbutton", pos, ignoreCase = true)
                if (open < 0) break
                clear.inside = true
                spans.add(intArrayOf(open, src.length))
                pos = open + 1
            } else {
                val close = src.indexOf("</clearbutton", pos, ignoreCase = true)
                if (close < 0) break
                spans[spans.size - 1][1] = close
                clear.inside = false
                pos = close + 1
            }
        }
        return spans
    }

    /** 标签名后的分隔符：`button` / `button value=…` / `button>` / `button/>`。 */
    private fun isTagBoundary(c: Char): Boolean =
        c == '>' || c == '/' || c.isWhitespace() || c == '\u3000'

    /**
     * 从 [from] 起找标签的闭合 `>`，**跳过引号内的 `>`**。
     *
     * 语料里的按钮会把 `<br>` 塞进 `title='…'`（如 `title='初始剧本<br>内容量适中…'`），
     * 早先的 `<button([^>]*)>` 正则会在 title 里的第一个 `>` 处截断，把标题文本当成
     * 按钮正文（真机剧本选择页实测 label 串位）。
     */
    private fun findTagEnd(src: String, from: Int): Int {
        var i = from
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 从开标签属性串里取属性值（如 [name] = `value`），支持：
     * `value='…'` / `value="…"` / `value=…`、`=` 两侧空白、属性顺序任意。
     *
     * 值内允许出现 `>`（引号包裹），因为 [findTagEnd] 已保证属性串本身在正确的 `>` 处截断。
     */
    private fun htmlAttribute(attrs: String, name: String): String? {
        var i = 0
        while (i < attrs.length) {
            while (i < attrs.length && attrs[i].isWhitespace()) i++
            val keyStart = i
            while (i < attrs.length && !attrs[i].isWhitespace() && attrs[i] != '=') i++
            val key = attrs.substring(keyStart, i)
            while (i < attrs.length && attrs[i].isWhitespace()) i++
            if (i >= attrs.length || attrs[i] != '=') continue
            i++
            while (i < attrs.length && attrs[i].isWhitespace()) i++
            val value: String = if (i < attrs.length && (attrs[i] == '\'' || attrs[i] == '"')) {
                val quote = attrs[i++]
                val start = i
                while (i < attrs.length && attrs[i] != quote) i++
                val v = attrs.substring(start, i)
                if (i < attrs.length) i++
                v
            } else {
                val start = i
                while (i < attrs.length && !attrs[i].isWhitespace()) i++
                attrs.substring(start, i)
            }
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    /**
     * 选项栏显示名：去 HTML 标签/实体、折空白。
     *
     * **行首 `[N]` 前缀只在它等于按钮自己的 [value] 时剥掉**（序号由 UI 的徽标单独显示）。
     * 不等时必须原样保留：地图/据点列表的按钮常印 `[27]` 而提交值是 `227`
     * （`MAIN_MAP*100+LOCAL`，`DRAW_MAP.ERB:467`），剥掉前缀再拿 value 当徽标，
     * 玩家会看到屏上是 `27`、栏里却写 `227`，误以为值被改写。前缀只用于显示，
     * 提交值始终是 [value]（[ConsoleOption.submitValue]）。
     *
     * 前缀与值一致但 value 不在 0..999（`[2000] 能力编辑`）也保留：徽标不显示大数值，
     * 前缀就是屏上唯一的编号，剥掉等于把信息丢了。
     */
    private fun cleanOptionLabel(raw: String, value: Any? = null): String {
        if (raw.isEmpty()) return ""
        val text = htmlUnescapeEntities(raw)
            .replace(HTML_TAG_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val prefix = ordinalPrefixOf(text)
        val v = (value as? Long)?.takeIf { it in 0..999 }?.toInt()
        return if (prefix != null && prefix == v) {
            text.replace(ORDINAL_PREFIX_REGEX, "").trim()
        } else {
            text
        }
    }

    /**
     * 数值按钮的序号徽标：仅当行首前缀与 value 一致且 value 在 0..999 时返回 value。
     *
     * 标签 `[27]` + value `227` ⇒ null（不把 227 冒充成 27，也让标签原文的 `[27]` 留着）；
     * 标签 `[27]` + value `27` ⇒ 27；无前缀 + value `27` ⇒ 27；`[2000]` + value `2000` ⇒ null。
     */
    private fun ordinalOf(value: Any, rawLabel: String): Int? {
        val v = (value as? Long)?.takeIf { it in 0..999 }?.toInt() ?: return null
        val label = htmlUnescapeEntities(rawLabel)
            .replace(HTML_TAG_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val prefix = ordinalPrefixOf(label) ?: return v
        return if (prefix == v) v else null
    }

    /** 行首 `[N]` 的数字（`[ 12]` 也认）；没有返回 null。 */
    private fun ordinalPrefixOf(text: String): Int? =
        ORDINAL_PREFIX_NUMBER_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 实体还原（`HtmlManager.Unescape`，HtmlManager.cs:744-834 的保守版）：
     * 六项内置名（nbsp/amp/gt/lt/quot/apos，大小写不敏感 :776-799）+ 数字引用
     * `&#123;` / `&#x7B;`（:801-832，原版范围 0..65535）。
     * 认不出的 `&...;` 原样保留（畸形实体在原版是打印期 CodeEE，见 [htmlVisibleText] 的 TODO）。
     */
    private fun htmlUnescapeEntities(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val amp = s.indexOf('&', i)
            if (amp < 0) { out.append(s, i, s.length); break }
            out.append(s, i, amp)
            val semi = s.indexOf(';', amp)
            val body = if (semi > amp + 1) s.substring(amp + 1, semi).lowercase(java.util.Locale.ROOT) else null
            val rep = body?.let { NAMED_ENTITIES[it] ?: numericEntity(it) }
            if (rep == null) { out.append('&'); i = amp + 1 } else { out.append(rep); i = semi + 1 }
        }
        return out.toString()
    }

    /** `&#123;` / `&#x7B;`（原版范围 0..65535，HtmlManager.cs:817-831）。 */
    private fun numericEntity(ent: String): String? {
        if (!ent.startsWith("#")) return null
        val hex = ent.startsWith("#x")
        val body = if (hex) ent.substring(2) else ent.substring(1)
        val code = body.toIntOrNull(if (hex) 16 else 10) ?: return null
        return if (code in 0..65535) code.toChar().toString() else null
    }

    /**
     * 行的显示宽度（半角格）。对齐（CENTER/RIGHT）与命中检测都要用它，实现走 [CellWidth]。
     */
    fun widthOf(line: DisplayLine): Int = lineWidth(line)

    // ------------------------------------------------------------------
    // ConsoleSink：打印
    // ------------------------------------------------------------------

    /** spec: EmueraConsole.cs:3167-3189 —— 空串直接忽略。 */
    override fun print(text: String) = print(text, true)

    /**
     * 追加文本。
     *
     * 与原文一致：文本里出现 `\n` 时先 flush 前缀，再**以 lineEnd=true** 递归打印剩余部分
     * （EmueraConsole.cs:3172-3187 的 `Print(str3)` 忽略了外层 lineEnd）。
     */
    override fun print(text: String, lineEnd: Boolean) {
        if (text.isEmpty()) return
        val nl = text.indexOf('\n')
        if (nl < 0) {
            bufferPrint(text, lineEnd)
            return
        }
        val head = text.substring(0, nl)
        // 原版对空前缀同样 Append（只改 isLastLineEnd），随后 NewLine 补一个空格行。
        if (head.isEmpty()) {
            pendingLineEnd = true
        } else {
            bufferPrint(head, true)
        }
        flushPending(force = true)
        if (nl < text.length - 1) {
            print(text.substring(nl + 1)) // 默认 lineEnd=true：原版递归调用不带实参
        }
    }

    /** spec: EmueraConsole.cs:3167 —— Print(str) 后由语句层的 NewLine 收尾。 */
    override fun printLine(text: String) {
        print(text)
        newLine()
    }

    /**
     * PRINTC / PRINTLC 的定宽补齐（EmueraConsole.cs:3229-3235、3247-3291）。
     *
     * 原版宽度按 **Shift-JIS 字节数**（`Encoding.GetEncoding("Shift-JIS").GetByteCount`，:3257-3261），
     * 补齐/裁剪只作用于空格：右对齐补前导空格，若像素宽超过 `PrintCLength` 个空格宽则从行首删空格；
     * 左对齐补到 `PrintCLength+1` 再从行尾删空格到 `PrintCLength`。**超宽文本不截断**。
     * 本类用半角 1 / 全角 2 近似 Shift-JIS 字节数。TODO(verify)：非 Shift-JIS 可表示字符
     * （emoji 等）原版编码器会按替换字符计 1 字节，本类按 1 或 2 计。
     */
    override fun printC(text: String, alignRight: Boolean) {
        if (text.isEmpty()) return
        // 原版 Append(..., lineEnd 缺省 true) 且 force_button=true（EmueraConsole.cs:3229-3235）：
        // 本段自成一组自动按钮，见 TextSpan.forceButtonGroup。
        bufferPrint(padC(text, alignRight), true, forceButtonGroup = true)
    }

    /** spec: EmueraConsole.cs:3293-3307 —— 空串不产生按钮。 */
    override fun printButton(text: String, value: Long) = bufferButton(text, value)

    override fun printButton(text: String, value: String) = bufferButton(text, value)

    /** spec: EmueraConsole.cs:3309-3323 —— 先 CreateTypeCString 再作为按钮文本。 */
    override fun printButtonC(text: String, value: Long, alignRight: Boolean) {
        if (text.isEmpty()) return
        bufferButton(padC(text, alignRight), value)
    }

    override fun printButtonC(text: String, value: String, alignRight: Boolean) {
        if (text.isEmpty()) return
        bufferButton(padC(text, alignRight), value)
    }

    /**
     * PRINTPLAIN：按原版仍使用当前 StringStyle（Process.cs:1147-1154 `UseSetColorStyle = true`，
     * EmueraConsole.cs:3325-3331 `AppendPlainText(str, Style)`），区别在于不参与
     * `[...]` 自动按钮/HTML 解析（PrintStringBuffer.cs:125-134 的 createPlainButton）。
     * 因此本类不降级颜色，只用 [Kind.PLAIN] 让渲染层区分。
     *
     * 注意：AppendPlainText 不修改 isLastLineEnd（PrintStringBuffer.cs:125-134），故本调用
     * 不改变待决行的 lineEnd 标记。
     */
    override fun printPlain(text: String) {
        if (text.isEmpty()) return
        pending.add(TextSpan(text, Kind.PLAIN, currentFore(), currentStyle(), currentFont()))
    }

    /**
     * PRINTSINGLE：先 flush 待决缓冲，再把文本作为一条不折行的独立显示行输出
     * （EmueraConsole.cs:3147-3165 → BufferToSingleLine → FlushSingleLine(nobr)）。
     */
    override fun printSingleLine(text: String) {
        if (text.isEmpty()) return
        flushPending(force = false)
        pendingLineEnd = true // PrintSingleLine 内部 Append(str, Style) 置 true（PrintStringBuffer.cs:66-68）
        appendGroup(listOf(singleLine(text, Kind.TEXT, temporary = false)))
    }

    /**
     * REUSELASTLINE 的临时行（EmueraConsole.cs:3053-3056 → PrintSingleLine(temporary:true)）。
     *
     * 原版语义：若最后一行已是临时行，先删除再追加（EmueraConsole.cs:2891-2893），
     * 所以连续 REUSELASTLINE 表现为「替换」；若最后一行不是临时行，则**追加**一条临时行，
     * 该行会被下一次正常输出删除。单行不折行（FlushSingleLine → nobr，PrintStringBuffer.cs:170-177）。
     */
    override fun printTemporaryLine(text: String) {
        if (text.isEmpty()) return
        flushPending(force = false)
        // PrintSingleLine 内部 Append(str, Style) 会把 isLastLineEnd 置 true（PrintStringBuffer.cs:66-68）。
        pendingLineEnd = true
        appendGroup(listOf(singleLine(text, Kind.TEXT, temporary = true)))
    }

    /**
     * 开机分隔线（`Process.cs:259` `setStBar` 一次成型）：[barUnit] 按 [clientWidth] 填满。
     * [printBar]（DRAWLINE 语句）与 `DRAWLINESTR` 伪变量（原版 `VariableData.cs:976-986`
     * 经 `getDefStBar`，EmueraConsole.cs:3437-3441）读的就是这同一份。
     */
    val stBar: String = barString(barUnit, clientWidth)

    /**
     * DRAWLINE：打印装配期成型的 [stBar]（原版 PrintBar 打同一字段，
     * EmueraConsole.cs:3388-3393；分隔线字符来自 `DRAWLINE文字` 条目，见 [barUnit]）。
     * 原版临时把字体样式改为 Regular（:3390-3392），本类同样只影响本条分隔线。
     * 本类令 DrawableWidth = [clientWidth]；原版还要减去字体/形状偏移
     * （Config.cs:466 `WindowX - DrawingParam_ShapePositionShift`）。TODO(verify)。
     */
    override fun printBar() {
        if (clientWidth <= 0) return
        withRegularStyle { bufferPrint(stBar, true) }
    }

    /**
     * CUSTOMDRAWLINE / DRAWLINEFORM：把参数字符串重复到 DrawableWidth（EmueraConsole.cs:3395-3413、
     * 3417-3437），同样临时使用 Regular。空串原版抛 CodeEE（:3396-3399）。
     */
    override fun printCustomBar(text: String) {
        if (text.isEmpty()) {
            throw CodeEE("DRAWLINE 的参数字符串不能为空")
        }
        withRegularStyle {
            val bar = barString(text, clientWidth)
            if (bar.isNotEmpty()) bufferPrint(bar, true)
        }
    }


    /**
     * 换行：flush 打印缓冲。
     *
     * 原版 force flush 在缓冲为空时会先 Append(" ", Style)（EmueraConsole.cs:3375-3385），
     * 因此空 NewLine 产生一个「空格行」，LINECOUNT +1。
     */
    override fun newLine() {
        flushPending(force = true)
    }

    /**
     * CLEARLINE：从尾部删除 [count] 个**逻辑行**（不是显示行），折行产生的续行随之一起删除。
     *
     * 整段照抄 `EmueraConsole.deleteLine`（EmueraConsole.cs:2999-3051），逐句对应见
     * [deleteRowsLocked]。原版**没有** `argNum <= 0` 的早退：`CLEARLINE 0` 在列表恰满
     * `MaxLog` 时仍会走收尾的 `if (Count == MaxLog) RemoveAt(0)`（:3046-3049）摘掉最旧一行，
     * 负数的 `CLEARLINE -n` 则会把 `deletedLines` 加回去（:3050 的 `-= argNum`）。
     * 这两条都是原版的实际行为（`CLEARLINE` 的实参不经任何夹取，FunctionIdentifier.cs:693），
     * 按 ADR-005 原样复刻，不做「顺手补个下界」。
     */
    override fun deleteLine(count: Int) {
        // 空表检查 + 循环摘除 + 逻辑行计数回退 + 行号回绕 + MaxLog 补行/淘汰 + revision
        // 是一条复合逻辑，必须整体持锁：循环中途若被读侧快照或写侧追加撞上，
        // 要么并发修改、要么删到不一致的中间态。
        synchronized(displayLines) {
            // 真有显示行被摘掉或补进才算可见变化（只扣 logicalLineCount 的空表删除不改变
            // lines()）。帧闸关闭（REDRAW 0 / 让出边界冻结）时只记脏：CLEARLINE 的逐行摘除
            // 中间态不发布，等解冻一次性上屏（原版 None 态 paint 早退，EmueraConsole.cs:1738-1741）。
            if (deleteRowsLocked(count)) {
                // 行集变了 ⇒ 帧口径的选项快照也要重算（与 revision 同一发布点，见 [publishedOptions]）。
                optionsDirty = true
                if (publishGateOpen) revision++ else gateDirty = true
            }
        }
    }

    /**
     * [deleteLine] 的锁内实现 —— `EmueraConsole.deleteLine`（:2999-3051）逐句直译。
     *
     * @return 是否动了 `displayLines`（摘行或补行）
     */
    private fun deleteRowsLocked(count: Int): Boolean {
        var changed = false
        // :3005-3006 两个局部量：num 数到几个**逻辑行**，flag 只服务于 :3037 的转义片缓存清理。
        var num = 0
        // :3007 `while (num < argNum && displayLineList.Count != 0)`
        while (num < count && displayLines.isNotEmpty()) {
            val row = displayLines.removeAt(displayLines.lastIndex)
            changed = true
            // :3013 每摘一排 lineNo--（折行的续排也各占一号，所以按排回退而不是按逻辑行）
            lineNo--
            // :3014-3021 `if (IsLogicalLine) { num++; if (IsLineEnd) logicalLineCount--; }`
            // 本仓的计数侧是「入列时 IsLineEnd ⇒ +1」（[appendGroup]），故回退侧对称地看
            // IsLineEnd，`num` 仍只在逻辑行首排 +1 —— 折成多排的一个逻辑行整条出列。
            if (row.isLineEnd) logicalLineCount--
            if (row.isLogicalLine) num++
            // :3022-3025 列表降到 MaxLog - 1 时记一次淘汰（补行分支随后可能再抬回 MaxLog - 1）
            if (maxLines > 0 && displayLines.size == maxLines - 1) deletedLines++
            // :3026-3030 「补行」：已经淘汰过头部（lineNo > Count 即证）且列表掉到
            // MaxLog - 2 时，把打印缓冲排成的**单行**插回**头部**，让可见行数不再继续缩水。
            // 这一行不经 addDisplayLine ⇒ 不涨 lineNo、不涨 logicalLineCount（原版同样如此）。
            if (maxLines > 0 && displayLines.size == maxLines - 2 && lineNo > displayLines.size) {
                displayLines.add(0, bufferToSingleRow())
                changed = true
            }
        }
        if (num < count) {
            // :3032-3036 删不够：行号直接归零，差额按逻辑行从计数里硬扣（原版不管被删的是
            // 不是逻辑行，会把 LINECOUNT 扣成负数 —— 复刻）。
            lineNo = 0
            logicalLineCount -= (count - num)
        }
        // :3037-3040 `if (flag && TextDrawingMode != WINAPI) ConsoleEscapedParts.Remove(lineNo)`
        // 无对应物：移植侧没有 EvilMask 的转义片缓存（Windows GDI 字形替换专属），行号只做记账。
        // :3041-3044 行号回绕（int 下溢 → 加回 int.MaxValue）
        if (lineNo < 0) lineNo += Int.MAX_VALUE
        // :3046-3049 收尾再夹一次容量：argNum <= 0 时循环没跑，这一步是唯一可能摘行的地方
        if (maxLines > 0 && displayLines.size == maxLines) {
            displayLines.removeAt(0)
            changed = true
        }
        // :3050 `deletedLines -= argNum`
        deletedLines -= count
        return changed
    }

    /**
     * 把打印缓冲排成**一条**不折行的显示行并清空缓冲
     * （原版 `BufferToSingleLine(force: true, temporary: false)` → `FlushSingleLine`，
     * EmueraConsole.cs:3339-3354、PrintStringBuffer.cs:170-177）。
     *
     * 只给 [deleteRowsLocked] 的 MaxLog 补行用（:3028）。缓冲为空时先补一个空格
     * （:3349-3352 的 `force && printBuffer.IsEmpty` 分支），与 force flush 同款。
     * 行标志按 `new ConsoleDisplayLine(buttons, isLogical: true, temporary)` 的构造缺省：
     * `IsLogicalLine = true`、`IsLineEnd = true`（ConsoleDisplayLine.cs:16）。
     */
    private fun bufferToSingleRow(): DisplayLine {
        if (pending.isEmpty()) bufferPrint(" ", true)
        val row = buildRows(pending, temporary = false, nobr = true).first()
            .copy(isLineEnd = true)
        pending.clear()
        return row
    }

    /** CLEARTEXTBOX 只清输入框，不影响文字流。spec: EmueraConsole.cs:3355-3358。 */
    override fun clearText() {}

    // ------------------------------------------------------------------
    // ConsoleSink：样式
    // ------------------------------------------------------------------

    /** spec: EmueraConsole.cs:2820-2824 SetStringStyle(Color)。 */
    override fun setForeColor(r: Int, g: Int, b: Int) {
        foreColor = rgb(r, g, b)
    }

    /**
     * 写「打印期用不用当前前景色」开关（原版 `Console.UseSetColorStyle = …`，
     * EmueraConsole.cs:469；写点全表见 [ConsoleSink.setUseSetColorStyle] 的 KDoc
     * —— 写假只有 `FunctionIdentifier.cs:180`/`:277` 两处，其余五处一律写真）。
     *
     * 只影响**此后**入缓冲的段（样式在入缓冲时快照，见 [Segment] 的 KDoc），已经排好的
     * 显示行与仍在缓冲里的段都不回溯改写。原版的机制是：`Style` 取值器在 **EmueraConsole 的
     * 打印入口处**求值并把结果当实参传下去（`printBuffer.Append(str, Style)`，
     * EmueraConsole.cs:3157、:3177、:3188、:3233），`PrintStringBuffer.Append` 再把它冻进
     * 当前样式游标：样式与上一次不同就在 :92 用**旧**样式把已缓冲的串结掉、:94-95 用**新**样式
     * 起一段（同样式则 :87 直接续在 builder 里）⇒ 「写开关的时机」恰好就是「分段边界」。
     * 本类在同一位置（[bufferPrint] 入缓冲）取 [currentFore]，故边界天然一致
     * （`ConsoleStyledString` 构造即定色，ConsoleStyledString.cs:21-40）。
     *
     * 注意它**不清空** [foreColor]：SETCOLOR 的当前值仍然是 GETCOLOR 的读数源
     * （原版 `StringStyle => userStyle`，EmueraConsole.cs:491，与 UseSetColorStyle 无关）。
     *
     * **接线状态**：语句层已接线（`core/sem/StatementExecutor.kt` 的 `execPrint`/`execPrintData`
     * 按 FunctionIdentifier.cs:180/:227 与 :277/:317 置位/复位；合流后 gfx 路的 D 系打印取色窗口全链同源）。
     */
    override fun setUseSetColorStyle(use: Boolean) {
        useSetColorStyle = use
    }

    /**
     * 写「打印期用不用用户样式」开关（原版 `Console.UseUserStyle = …`，
     * EmueraConsole.cs:467；写点全表见 [ConsoleSink.setUseUserStyle] 的 KDoc
     * —— 写真五处全在语句层，写假六处全在控制台内部流程）。
     *
     * 与 [setUseSetColorStyle] 同为**粘滞**开关：只影响此后入缓冲的段（样式在入缓冲时快照，
     * 见 [Segment] 的 KDoc），已排好的显示行不回溯改写。原版机制同样是「`Style` 取值器在
     * 打印入口处求值并冻进 `ConsoleStyledString`」（EmueraConsole.cs:3157/:3177/:3188/:3233
     * → PrintStringBuffer.cs:87-95 → ConsoleStyledString.cs:21-40），本类在同一位置取
     * [currentFore]/[currentStyle]/[currentFont]，分段边界天然一致。
     *
     * 它**不清空**三件样式字段：SETCOLOR/FONTSTYLE/SETFONT 的当前值仍是
     * GETCOLOR/GETSTYLE/GETFONT 的读数源（原版 `StringStyle => userStyle`，
     * EmueraConsole.cs:491，与本开关无关）。
     */
    override fun setUseUserStyle(use: Boolean) {
        useUserStyle = use
    }

    /** spec: EmueraConsole.cs:2844-2858 SetBgColor。 */
    override fun setBackColor(r: Int, g: Int, b: Int) {
        backColor = rgb(r, g, b)
    }

    /** RESETCOLOR → Config.ForeColor。spec: FunctionMethodCreator.cs:3662。 */
    override fun resetForeColor() {
        foreColor = null
    }

    /** RESETBGCOLOR → Config.BackColor。spec: FunctionMethodCreator.cs:3695。 */
    override fun resetBackColor() {
        backColor = null
    }

    /** FONTSTYLE 位掩码替换。spec: Process.cs:1508-1530。 */
    override fun setFontStyle(mask: Int) {
        styleMask = mask
    }

    /** SETFONT：空串恢复默认字体（EmueraConsole.cs:2826-2835）。 */
    override fun setFont(name: String) {
        fontNameCur = if (name.isEmpty()) defaultFontName else name
    }

    /** ALIGNMENT。spec: Process.cs:1535-1552。 */
    override fun setAlignment(kind: Alignment) {
        align = kind
    }

    /**
     * REDRAW：只把 bit0 存进状态（`mode and 1`）；bit1 的「立即重绘」按原版是
     * `RefreshStrings(force_Paint: true)` 的绘制副作用（EmueraConsole.cs:1693-1707），
     * 本类把它落成 [publishFrame] 口径：bit0=0（None）时冻结发布闸，bit1 在冻结态下
     * 强制发布一次（REDRAW 2 的「None + 立即重绘」）。
     * spec: EmueraConsole.cs:1693-1707；读出侧见 [redraw]。
     */
    override fun setRedraw(mode: Int) {
        redraw = mode and 1
        if (redraw != 0) {
            // Normal：解冻 + 冻结期间的内容一次性上屏（原版回 Normal 后下一次
            // RefreshStrings 会画出新状态，SetRedraw 本身只置状态）。
            publishFrame()
        } else {
            holdFrame()
            if (mode and 2 != 0) flushGateOnce() // bit1：None 态下的强制重绘（REDRAW 2）
        }
    }

    // ------------------------------------------------------------------
    // 帧发布闸（CONSOLE-FRAMEGATE · 2026-10-22 真机抖动收口）
    // ------------------------------------------------------------------

    /**
     * 发布闸开闭态：true（缺省）= 可见变更立即 bump [revision]（既有直通行为）；
     * false = 变更只记 [gateDirty]，等解冻一次性发布。
     *
     * 只被 VM 线程写（[setRedraw] / [publishFrame] / [holdFrame]，ADR-003 单生产），
     * 读点也全在 VM 线程的 appendGroup / deleteLine 里，故不需要 @Volatile。
     */
    private var publishGateOpen = true

    /** 闸关期间积攒过可见变更（解冻时据此决定是否 bump [revision]）。同 [publishGateOpen] 的线程口径。 */
    private var gateDirty = false

    /**
     * 帧口径的选项快照（[publishedOptions] 的载体）：**与 [revision] 同一处发布**，
     * 发布后整份替换、永不原地改，UI 线程可以无锁读。
     */
    @Volatile
    private var frameOptions: List<ConsoleOption> = emptyList()

    /**
     * 选项集待重算（行集或浮岛按钮变过）。初值 true：第一帧之前 `frameOptions` 是空表，
     * 首个发布点必须无条件建一次。
     *
     * 与 [gateDirty] **不是**同一个标志：`gateDirty` 只在闸关时置位（管 [revision] 的
     * 合并发布），而本标志在闸开时也要置位 —— 否则「闸开期间打印的标题页」永远不会有
     * 快照（[flushGateOnce] 会因 `gateDirty == false` 提前返回）。同 [publishGateOpen] 的
     * 线程口径：只被 VM 线程写。
     */
    private var optionsDirty = true

    /**
     * 帧发布（让出边界）：解冻 + 一次性上屏冻结期间的内容。
     * 语句层在 `AWAIT` 与进入输入等待前调用（原版 Await 第一步的强制重绘 +
     * WaitInput 后消息循环的 paint 机会，见 [ConsoleSink.publishFrame] 的 spec 引证）。
     */
    override fun publishFrame() {
        publishGateOpen = true
        flushGateOnce()
    }

    /** 帧冻结（让出边界返回之后）：后续变更积累到下一次 [publishFrame]。 */
    override fun holdFrame() {
        // 一屏一帧关闭 ⇒ 永不冻结，变更立即发布（见构造参数 [frameGateEnabled] 的 KDoc）。
        if (!frameGateEnabled) return
        publishGateOpen = false
    }

    /**
     * 冻结态下的一次性发布：有脏才 bump；闸的开闭态不动。
     *
     * 选项快照与 [revision] 在这里**同一刻**发布（见 [publishedOptions]）：两者要么都还是
     * 上一帧的，要么都是这一帧的，不会出现「画布已经换了、栏里还是半屏按钮」。
     */
    private fun flushGateOnce() {
        if (gateDirty) {
            gateDirty = false
            // revision 是 @Volatile 读侧判据，锁外写与 appendGroup 锁内的条件写之间
            // 只可能多发布一次空 revision（UI 多画一帧幂等快照），不会漏。
            revision++
        }
        publishOptionsIfDirty()
    }

    /**
     * 帧口径选项快照的**唯一**发布动作：有脏才重算，整份替换 [frameOptions]。
     *
     * 两个调用点都在让出边界上：[flushGateOnce]（AWAIT / `REDRAW 1`）与 [noteInputWait]
     * 的收尾 —— 后者是补一次「刚刚 flush 上屏、但发生在 `publishFrame` 之后」的按钮
     * （标题页 `HTML_PRINT …, 1` 的 `<div><button>` 就是这种形态），不补的话
     * [publishedOptions] 会漏掉玩家眼前这一屏，`isCurrentGeneration` 会把它们判成旧世代。
     */
    private fun publishOptionsIfDirty() {
        if (!optionsDirty) return
        optionsDirty = false
        frameOptions = displayOptions()
    }

    /**
     * `EmueraConsole.ResetStyle()` 的一次性复位（spec: EmueraConsole.cs:2838-2842；
     * 复位集与「不碰背景色/重绘/缓冲」的出处见 [ConsoleSink.resetStyle] KDoc）。
     * 本类直接写字段：终态与接口缺省的逐项 setter 组合逐字段一致
     * （foregroundColor→null 即 RESETCOLOR 的「回 Config.ForeColor」形、styleMask→0 即
     * Regular、fontNameCur→[defaultFontName] 即 StringStyle.cs:20-27 对 defaultStyle
     * 空字体名的归一、align→LEFT），且与 [setForeColor]/[setFontStyle]/[setFont]/
     * [setAlignment] 写的是同一份状态，不存在第二快照。
     * 不动 [backColor]、[redraw]、待决缓冲与显示行；样式复位不改变 [lines]，
     * 故 [revision] 不自增（其 KDoc 已声明）。
     */
    override fun resetStyle() {
        foreColor = null
        styleMask = 0
        fontNameCur = defaultFontName
        align = Alignment.LEFT
    }

    // ------------------------------------------------------------------
    // ConsoleSink：HTML / 错误
    // ------------------------------------------------------------------

    /**
     * HTML_PRINT。
     *
     * - `option=true`（toPrintBuffer）：HTML 作为一段进入当前打印缓冲，原文与标志都保留
     *   （EmueraConsole.cs:3209-3216 走 `Html2ButtonList`：在 `buttonsOutput != null` 处
     *   提前返回、不切行，HtmlManager.cs:679-682）。该路径上 div 是打印缓冲里的覆盖绘制
     *   节点（TEMP_CHARA_LIST_BY_DT.ERB:641 用它画行高亮条），线性行模型落不了，丢弃。
     * - `option=false`：先 flush 当前缓冲（非空时），再把 HTML **按结构切行**后逐条追加
     *   （EmueraConsole.cs:3217-3225：`addRangeDisplayLine(Html2DisplayLine(...))` 可产
     *   多条行，EmueraConsole.cs:2877-2883 逐条 addDisplayLine）。
     *
     * 切行（GFX-03）：[splitHtmlLines] 按 C# `html2DisplayLine` 的结构语义把原文切成若干
     * 「行原文」——`<br>` 与串内换行符断行（HtmlManager.cs:588-592 置 FlagBr、:650-658 追加
     * null 分段、PrintStringBuffer.cs:209-215 遇 null 落行），`<div>` 开/闭是断行边界、
     * 其内容行带 div 的 `color` 底色（HtmlManager.cs:1225-1371、ConsoleDivPart.cs:68-70）。
     * 跨行的样式状态（font/b/i/u/s/button 开标签栈与 p/nobr——`HtmlAnalzeState` 是串级
     * 状态机，br 不重置它，HtmlManager.cs:92-151）按「行尾补闭合、行首重放」延续：渲染
     * 结果与原版的状态延续等价（`GetSS` 只读栈顶一帧，HtmlManager.cs:128-150）。真正的
     * 标签解析（样式/图形/按钮命中）仍归 :render 的 `HtmlRunFlatten`，本类只做**结构**
     * 切分，每行仍是 [Kind.HTML] 原文段。
     *
     * 与原版的**登记分歧**（浮动块模型缺失，不硬造）：
     * - `<div>` 的 xpos/ypos/width/height/depth/border/margin/padding/radius/bcolor/
     *   display 全部丢弃：原版 div 是行内嵌**覆盖绘制块**——`SetWidth` 为空不占行宽
     *   （ConsoleDivPart.cs:217-219），子行画在 xpos/ypos 矩形内（:174-206）；
     *   [DisplayLine] 的线性字符行模型没有定位/覆盖域，硬造等于发明新语义。
     *   移植侧近似：div 内容独立成行（带底色），两栏并排布局退化为上下堆叠——
     *   行序与内容保持、栏位丢失（语料 29 处 div 全是定位块用法）。
     * - div 前后的文本与块在原版**同行**（块零宽内嵌，HtmlManager.cs:646 把 DivPart 塞进
     *   当前节点列表），移植侧断成独立行。
     * - div 的 `color` 染矩形区域，移植侧染整行 [DisplayLine.backColor]（渲染层对非按钮
     *   run 逐条消费，ConsoleAdapter 的 back 槽）。
     * - `<p align>`/`<nobr>` 的行级语义由 render 侧 `HtmlRunFlatten` 落到行 align/noBreak
     *   （跨行重放保证切出来的每一行都解析得到）；core 侧 [DisplayLine.noBreak] 恒 false。
     */
    override fun printHtml(text: String, option: Boolean) {
        if (text.isEmpty()) return
        if (option) {
            // 选项快照与绘制几何分开：这里只登记“这段 HTML 里有哪些按钮”，
            // 命中区仍由渲染层 HtmlRunFlatten 从同一份原文算。按钮对象此刻创建 ⇒ 盖当前世代。
            // 位置随段走：到 [buildRows] 排版时再加该段的行内列偏移，落到**所在显示行**
            // （旧实现把整段按钮挂在 flush 首行，跨行 HTML 的选项顺序会全部串到一起）。
            val opts = extractHtmlOptions(text)
            val htmlOptions = if (opts.isEmpty()) {
                emptyList()
            } else {
                val generation = stampButtonGeneration()
                opts.map { it.copy(generation = generation) }
            }
            pending.add(
                HtmlSpan(text, true, currentFore(), currentStyle(), currentFont(), htmlOptions),
            )
            return
        }
        flushPending(force = false)
        val chunks = splitHtmlLines(text)
        if (chunks.isEmpty()) return
        // 逐行抽取：每个 chunk 是**一条显示行**的 HTML 原文（跨行按钮由 splitHtmlLines
        // 在行尾补闭合、行首重放，value 在每行都可见）。旧实现从整段原文抽一次、全挂首行，
        // 屏上第 2 行以后的按钮就会和首行按钮挤在同一 lineSerial 上，右侧栏无法行优先排序。
        // `<clearbutton>` 的开关状态按行序串下去：开在上一行、闭在下一行的区间整段不可点。
        // chunk 自带的开标签身份（`buttonIds`）跟着一起交下去：跨行重放的那几行抽到同一个
        // 源按钮编号，右侧栏才认得出「这是一颗按钮的两行」而不是两颗。
        val clear = ClearButtonScan(false)
        val perLine = chunks.map { extractHtmlOptions(it.text, clear, it.buttonIds) }
        val generation = if (perLine.any { it.isNotEmpty() }) stampButtonGeneration() else 0L
        appendGroup(
            chunks.mapIndexed { index, chunk ->
                DisplayLine(
                    isLineEnd = true,
                    segments = listOf(
                        Segment(chunk.text, currentFore(), currentStyle(), currentFont(), Kind.HTML, false),
                    ),
                    // 组内首行 logical、其余 false（PrintStringBuffer.cs:206 firstLine=!subDiv、
                    // :212/:242 后续置 false；行 IsLineEnd 构造缺省 true，ConsoleDisplayLine.cs:32/:47）。
                    isLogicalLine = index == 0,
                    alignment = align,
                    backColor = chunk.divBackColor ?: currentBack(),
                    htmlOptions = perLine[index].map { it.copy(generation = generation) },
                )
            },
        )
    }

    /**
     * [splitHtmlLines] 切出的一行：行原文 + 该行所处 div 的底色（不在 div 内为 null）
     * + 该行文本里出现的 `<button>` 开标签身份（`开标签原文 → 源按钮编号`，按文本顺序）。
     *
     * [buttonIds] 是跨行重放的**唯一凭据**：行首重放的开标签与它在源串里的那一段逐字相同，
     * 所以同一个编号会连着出现在相邻几行的表里（见 [extractHtmlOptions] 的 `sources`）。
     */
    private class HtmlChunk(
        val text: String,
        val divBackColor: Int?,
        val buttonIds: List<Pair<String, Int>> = emptyList(),
    )

    /**
     * [splitHtmlLines] 的开放标签栈帧：开标签原文 + 标签名 + （仅 `button`）源按钮编号。
     *
     * 编号在**开标签首次出现**时发出（[nextButtonGroupId]），闭标签出栈即失效；下一行的
     * 行首重放用的还是同一帧，因此同一颗按钮在几行里都是同一个编号。
     */
    private class OpenHtmlTag(val raw: String, val name: String, val buttonId: Int)

    private class HtmlTagInfo(val name: String, val isClose: Boolean, val raw: String, val next: Int)

    /**
     * 直印 HTML 的结构切行（GFX-03，语义出处见 [printHtml] 的 KDoc）。
     *
     * 只认**结构标签**（br/div 的开闭、样式标签的开放栈、p/nobr 的串级记忆），
     * 其余标签原样留在行文本里交给 render 展平；属性值（引号内）不参与标签识别
     * （原版属性是 LiteralStringWord，`title='a<br>b'` 里的 br 不切行）。
     *
     * 行的形状 = 「行首重放（上一行延续的开放状态）+ 行体 + 行尾补闭合（开放栈逆序）」，
     * 行首重放在**进入该行时**定格（[rowPrefix]），行内新出现的标签改的是下一行的重放集，
     * 不会重复进本行。
     */
    private fun splitHtmlLines(src: String): List<HtmlChunk> {
        val rows = ArrayList<HtmlChunk>()
        val cur = StringBuilder()
        // 未闭合样式标签栈（开标签原文, 标签名, 按钮编号），按开序——HtmlAnalzeState.FonttagList +
        // FontStyle 的跨行延续（HtmlManager.cs:92-151），br/div 都不重置。
        val openTags = ArrayList<OpenHtmlTag>()
        // 本行**行首重放**的开标签身份（= 上一行落行时还开着的那几颗按钮），按 [rowPrefix] 的拼接序。
        var replayIds = emptyList<Pair<String, Int>>()
        // 本行行体里新开的按钮身份（按遇到序）；落行后清空，与 replayIds 拼成本行的 [HtmlChunk.buttonIds]。
        val bodyIds = ArrayList<Pair<String, Int>>()
        // p/nobr 的串级重放源：C# 的 Alignment/FlagNobr 一旦设置跨整串有效（每行都
        // SetAlignment，HtmlManager.cs:702-710），`</p>`/`</nobr>` 只置闭合标志、不清值。
        var replayP: String? = null
        var replayNobr: String? = null
        var divDepth = 0
        var divBackColor: Int? = null
        // 本行行首的重放前缀（进入该行时的开放状态快照）：只在落行后为**下一行**重算，
        // 行内新开的标签改的是下一行的重放集，不会重复进本行。
        var rowPrefix = ""

        fun closeRow() {
            val body = cur.toString()
            cur.setLength(0)
            val closeAll = openTags.asReversed().joinToString("") { "</${it.name}>" }
            rows.add(HtmlChunk(rowPrefix + body + closeAll, divBackColor, replayIds + bodyIds))
            rowPrefix = buildString {
                replayNobr?.let { append(it) }
                replayP?.let { append(it) }
                for (tag in openTags) append(tag.raw)
            }
            // 下一行的行首重放 = 此刻还开着的按钮（行体内新开又闭的不算，它已经在本文里结掉了）。
            replayIds = openTags.filter { it.buttonId >= 0 }.map { it.raw to it.buttonId }
            bodyIds.clear()
        }

        var pos = 0
        val n = src.length
        while (pos < n) {
            val ch = src[pos]
            if (ch == '\n') {
                // 串内换行符同 br（HtmlManager.cs:535/:588-592 的 HasReturn→FlagBr）；
                // ERB 多行字符串字面量里的 CRLF 把 \r 留在上一行尾会画出脏字符，剥掉。
                if (cur.isNotEmpty() && cur[cur.length - 1] == '\r') cur.setLength(cur.length - 1)
                closeRow()
                pos++
                continue
            }
            if (ch != '<') {
                cur.append(ch)
                pos++
                continue
            }
            val tag = parseHtmlTag(src, pos)
            if (tag == null) {
                // 无 '>' 的孤立 '<'：原版抛 CodeEE（HtmlManager.cs:597-599），宽容当文本。
                cur.append('<')
                pos++
                continue
            }
            when (tag.name) {
                // br：null 分段语义——总是落行（空也落，PrintStringBuffer.cs:209-215）。
                // 标签本身剥除：null 不该出现在行文本里。
                "br" -> {
                    closeRow()
                    pos = tag.next
                }
                "div" -> if (tag.isClose) {
                    if (divDepth > 0) {
                        // div 内最后一行先落（还在 div 底色里），再退出
                        if (cur.isNotEmpty()) closeRow()
                        divDepth--
                        if (divDepth == 0) divBackColor = null
                    }
                    // 多余的 </div>（C# 抛 UnexpectedCloseTag，HtmlManager.cs:1029-1035）：丢弃标记。
                    pos = tag.next
                } else {
                    // div 前的文本先落行（外层底色）；嵌套 div（C# 抛 NestedTag，:1227-1229）
                    // 宽容为只认最外层的 color。
                    if (cur.isNotEmpty()) closeRow()
                    if (divDepth == 0) divBackColor = tagAttrValue(tag.raw, "color")?.let { htmlColorValue(it) }
                    divDepth++
                    pos = tag.next
                }
                "p" -> {
                    if (!tag.isClose) replayP = tag.raw
                    cur.append(tag.raw)
                    pos = tag.next
                }
                "nobr" -> {
                    if (!tag.isClose) replayNobr = tag.raw
                    cur.append(tag.raw)
                    pos = tag.next
                }
                // 样式/按钮/作废区间标签：原文进行体，开放状态进栈供跨行重放（按钮跨 br 时值延续，
                // HtmlAnalzeState.CurrentButtonTag 同为串级状态，HtmlManager.cs:841-852）。
                // `clearbutton` 进重放集：它打开期间的按钮在原版全部降级成非按钮
                // （HtmlManager.cs:1549-1551），而 eraTW 的 CLEARLINE 重绘会让这个区间
                // **横跨两条显示行**（地图页 `<clearbutton>…<br>…</clearbutton>`）。
                // 补上闭合/重放后每一行都自含区间，抽取与渲染两侧都不用再记跨行状态。
                // `button` 的开标签额外领一个源按钮编号：重放行抽到的是同一个编号，
                // 右侧栏据此知道「这两行是同一颗按钮」，而不是两颗同值的按钮。
                "font", "b", "i", "u", "s", "button", "nonbutton", "clearbutton" -> {
                    if (tag.isClose) {
                        val idx = openTags.indexOfLast { it.name == tag.name }
                        if (idx >= 0) openTags.removeAt(idx)
                    } else {
                        val buttonId = if (tag.name == "button") nextButtonGroupId++ else ID_UNKNOWN
                        openTags.add(OpenHtmlTag(tag.raw, tag.name, buttonId))
                        if (buttonId >= 0) bodyIds.add(tag.raw to buttonId)
                    }
                    cur.append(tag.raw)
                    pos = tag.next
                }
                else -> {
                    // 其余标签（img/shape/未知名）：结构无关，原文保留。
                    cur.append(tag.raw)
                    pos = tag.next
                }
            }
        }
        // 尾行：C# 只在还有节点时收尾（HtmlManager.cs:675-678 的 list.Count > 0）。
        // 「节点」= 可见文本非空（空白字符也是节点，ConsoleStyledString 照建）**或含图形标签**
        // （img/shape 在 HtmlManager.cs:279-280 与 br 同列特殊处理，产 ConsoleImagePart /
        // ConsoleShapePart 节点——纯图行没有可见文本也必须留行，否则屏上图整行消失）；
        // 闭标签尾巴（如 </nobr>）剥完为空 → 丢弃，避免多出一条原版没有的空行。
        val tail = cur.toString()
        if (cur.isNotEmpty() && (divBackColor != null || htmlVisibleText(tail).isNotEmpty() || GRAPHIC_TAG_REGEX.containsMatchIn(tail))) {
            closeRow()
        }
        return rows
    }

    /**
     * 从 `pos` 处的 `<` 起解析一个标签：名字（字母数字）、引号感知地跳到标签收尾 `>`。
     * 属性值里的 `<`/`>` 不参与（原版属性是字符串字面量）；找不到 `>` 返回 null。
     */
    private fun parseHtmlTag(src: String, start: Int): HtmlTagInfo? {
        var i = start + 1
        val isClose = i < src.length && src[i] == '/'
        if (isClose) i++
        val nameStart = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        if (i == nameStart) return null
        val name = src.substring(nameStart, i).lowercase(java.util.Locale.ROOT)
        var quote = '\u0000'
        while (i < src.length) {
            val c = src[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '>') {
                break
            }
            i++
        }
        if (i >= src.length) return null
        return HtmlTagInfo(name, isClose, src.substring(start, i + 1), i + 1)
    }

    /** 开标签原文里的单个属性值（引号/裸值）；属性无值返回空串，找不到该属性返回 null。 */
    private fun tagAttrValue(raw: String, want: String): String? {
        var i = 1 // 跳 '<'（调用方保证是开标签）
        while (i < raw.length && !raw[i].isWhitespace() && raw[i] != '>') i++ // 跳标签名
        while (i < raw.length) {
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] == '>') return null
            if (raw[i] == '/') {
                i++
                continue
            }
            val nameStart = i
            while (i < raw.length && raw[i] != '=' && !raw[i].isWhitespace() && raw[i] != '>') i++
            val name = raw.substring(nameStart, i).lowercase(java.util.Locale.ROOT)
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length || raw[i] != '=') {
                if (name == want) return ""
                continue
            }
            i++ // '='
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return if (name == want) "" else null
            val quote = raw[i]
            if (quote == '\'' || quote == '"') {
                val end = raw.indexOf(quote, i + 1).let { if (it < 0) raw.length else it }
                if (name == want) return raw.substring(i + 1, end)
                i = if (end < raw.length) end + 1 else raw.length
            } else {
                val valueStart = i
                while (i < raw.length && !raw[i].isWhitespace() && raw[i] != '>') i++
                if (name == want) return raw.substring(valueStart, i)
            }
        }
        return null
    }

    /**
     * div 的 `color` 属性值 → RGB（`stringToColorInt32`，HtmlManager.cs:1680-1725 的保守版）：
     * `#RRGGBB` 十六进制与十进制整数两种；颜色名分支（`Color.FromName`）不复刻，
     * 解析不出返回 null（该 div 不染底色）。
     */
    private fun htmlColorValue(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return if (s[0] == '#') {
            s.substring(1).toLongOrNull(16)?.takeIf { it in 0..0xFFFFFF }?.toInt()
        } else {
            s.toLongOrNull(10)?.takeIf { it in 0..0xFFFFFF }?.toInt()
        }
    }

    /**
     * spec: EmueraConsole.cs:2867-2869 PrintHTMLIsland → _htmlElementList.AddRange。
     *
     * 与 [htmlIslands] 同锁（`displayLines` 监视器，见类文档线程模型）。空串早退留在锁外：
     * 它不碰任何共享状态，只是少取一次无竞争的锁，可观察结果与改锁前的 `if (text.isNotEmpty())`
     * 逐字相同（原版空串走 `Html2DisplayLine` 得到零行、`AddRange` 空数组，等价于不追加：
     * HtmlManager.cs:675-677 + PrintStringBuffer.cs:198-201 + EmueraConsole.cs:2869）。
     * 临界区里只有 `ArrayList.add`，没有任何外部回调 ⇒ 不会与别的路径组成锁序环。
     */
    override fun addHtmlIsland(text: String) {
        if (text.isEmpty()) return
        synchronized(displayLines) {
            islands.add(text)
            islandsVersion++
            val opts = extractHtmlOptions(text)
            if (opts.isNotEmpty()) {
                val generation = stampButtonGeneration()
                islandOptions.addAll(opts.map { it.copy(generation = generation) })
                // 浮岛不自增 revision（见 [revision] 的 KDoc），但它的按钮要进帧口径快照：
                // 下一个让出边界重算一次即可（不清 `islandOptions` 的世代判定，收集时按世代过滤）。
                optionsDirty = true
            }
        }
    }

    /** spec: EmueraConsole.cs:2871-2874 ClearHTMLIsland。锁口径同 [addHtmlIsland]。 */
    override fun clearHtmlIslands() {
        synchronized(displayLines) {
            islands.clear()
            islandsVersion++
            islandOptions.clear()
            optionsDirty = true
            // 浮岛被清 = 那一屏按钮已经不存在；别让上一次等待的快照在 UI 上残留。
            optionSnapshot = emptyList()
        }
    }

    /**
     * PrintError：先 flush 待决缓冲，再用**默认样式**（不使用用户 SETCOLOR/FONTSTYLE/SETFONT）
     * 输出一条独立、强制左对齐、不折行的行（EmueraConsole.cs:3098-3115：
     * UseUserStyle=false + PrintPlainwithSingleLine + addDisplayLine(force_LEFT:true)）。
     */
    override fun printError(text: String) {
        if (text.isEmpty()) return
        // 错误画面必须可见：即便帧闸冻结（错误常发生在 REDRAW 0 段内 / 让出边界之间），
        // 也先解冻发布一次——原版错误行 PrintError 后 state=Error，主循环会强制画出来。
        publishFrame()
        flushPending(force = false)
        // 原版顺序（EmueraConsole.cs:3107-3110）：先 PrintFlush，**再**写 UseUserStyle=false，
        // 然后打印错误行。写在 flush 之后是必须的——已经排进缓冲的段按旧样式结掉，
        // 只有此后入缓冲的内容才回默认样式。这条写假是**粘滞**的：原版没有配对写回，
        // 下一条 PRINT 系语句才会写真（ConsoleSink.setUseUserStyle 的 KDoc）。
        useUserStyle = false
        val seg = Segment(text, DEFAULT_FORE_COLOR, 0, defaultFontName, Kind.ERROR)
        appendGroup(
            listOf(
                DisplayLine(
                    isLineEnd = true,
                    segments = listOf(seg),
                    isLogicalLine = true,
                    alignment = Alignment.LEFT,
                    backColor = currentBack(),
                    // PrintPlainwithSingleLine → FlushSingleLine(nobr: true)（EmueraConsole.cs:3361-3373、
                    // PrintStringBuffer.cs:170-177）。
                    noBreak = true,
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // 内部：待决缓冲与布局
    // ------------------------------------------------------------------

    private sealed interface PendingSpan

    private class TextSpan(
        val text: String,
        val kind: Kind,
        val fore: Int,
        val style: Int,
        val font: String,
        /**
         * 本段是否自成一组自动按钮（PRINTC/PRINTLC 的定宽段）。
         *
         * 原版 `PrintC` 走 `Append(str, Style, force_button: true)`（EmueraConsole.cs:3229-3235），
         * `Append` 在追加**前后**各调一次 `fromCssToButton`（PrintStringBuffer.cs:73-76、:97-100）：
         * 前一次把已缓冲文本转成按钮，后一次把 PRINTC 文本单独转成按钮 ⇒ `[n]` 只在本段内
         * 找配对，不会把前面的普通文本一起吞进按钮。本类用这个标记在 [splitAutoButtons]
         * 的节点组上切一刀，行为等价且不必提前 flush。
         */
        val forceButtonGroup: Boolean = false,
    ) : PendingSpan

    private class ButtonSpan(
        val text: String,
        val value: Any,
        val fore: Int,
        val style: Int,
        val font: String,
        /**
         * 本片所属的**源按钮**编号（同一 `ConsoleButtonString` 的多个样式片共用一个编号）。
         *
         * 原版一条按钮是 `ConsoleButtonString` 实例，它的 `StrArray` 可以装多个样式节点
         * （ConsoleButtonString.cs:19、:296-303），而**两次 `AppendButton` 恒是两条**
         * （PrintStringBuffer.cs:103-123 每次都 `fromCssToButton` 后再 `createButton`）。
         * 落到整数网格上：同一源按钮的相邻片列号相接且值相同，两次显式 PRINTBUTTON 即使
         * 值相同列号也相接 —— 只看「列号 + 值」分不开这两种，必须带编号。
         */
        val groupId: Int,
        /** 按钮来源：`PRINTBUTTON` 显式 vs `[N]` 自动。HTML 由 [HtmlSpan] 单独解析。 */
        val origin: OptionOrigin,
        /** 这条源按钮的完整可见文本（跨样式片/折行时保持同一份，UI 不再拼）。 */
        val label: String,
    ) : PendingSpan

    private class HtmlSpan(
        val text: String,
        val option: Boolean,
        val fore: Int,
        val style: Int,
        val font: String,
        /** `option=true` 时这段 HTML 里的按钮（列号相对段首；排版时再加行内偏移）。 */
        val htmlOptions: List<HtmlOption> = emptyList(),
    ) : PendingSpan

    private class SegBuilder(
        val kind: Kind,
        val fore: Int,
        val style: Int,
        val font: String,
        val htmlOption: Boolean = false,
    ) {
        val sb = StringBuilder()

        fun build(): Segment = Segment(sb.toString(), fore, style, font, kind, htmlOption)
    }

    private class RowBuilder(
        val alignment: Alignment,
        val backColor: Int,
    ) {
        var col = 0
        val segs = ArrayList<SegBuilder>()
        val buttons = ArrayList<Button>()
        /** 本行 HTML 按钮（列号已加上本行内的段偏移）。 */
        val htmlOptions = ArrayList<HtmlOption>()

        /**
         * 记一条按钮区间。**只有同一个源按钮**（[groupId] 相同）的相邻、同值片才并成一条：
         * 原版一条 `ConsoleButtonString` 可以横跨多个样式段（自动按钮的整串分支
         * `ButtonStringCreator.cs:63-73`，以及 `createButtons` 按字符切分后把多个样式段装进
         * 同一个按钮，PrintStringBuffer.cs:347-387），在整数网格里就是「列号相接、值相同、
         * 同一源按钮」；命中表只该有**一条**（`ConsoleDisplayLine.Buttons` 的条数口径）。
         *
         * 两次显式 `PRINTBUTTON` 即使值相同也**不并**：原版是两条 `ConsoleButtonString`
         * （PrintStringBuffer.cs:103-123），`Buttons` 数组长度 2（ConsoleDisplayLine.cs:18,28 的
         * `buttons` 字段 + ConsoleButtonString.cs:296-303 的节点列推进）。
         * 列号 + 值相同的事实只说明两条命中区间相接、回环串一样，不说明它们是同一条。
         *
         * 源身份落在 [Button.groupId] 上（旧实现另开一条 `buttonGroups` 平行表记它），
         * 折行/PRINTN 续行合并时随 `copy` 一起走，右侧栏据此认「同一条按钮的重放」。
         */
        fun addButton(
            start: Int,
            end: Int,
            value: Any,
            groupId: Int,
            origin: OptionOrigin = OptionOrigin.EXPLICIT,
            label: String = "",
            generation: Long,
        ) {
            val last = buttons.lastOrNull()
            if (last != null && groupId != ID_UNKNOWN &&
                last.endCol == start && last.value == value && last.groupId == groupId
            ) {
                // 同一源按钮跨段/折行：标签用源按钮的完整文本，不把分片重复拼接。
                buttons[buttons.lastIndex] = Button(
                    startCol = last.startCol,
                    endCol = end,
                    value = value,
                    origin = last.origin,
                    label = last.label.ifEmpty { label },
                    generation = last.generation,
                    groupId = groupId,
                )
            } else {
                buttons.add(Button(start, end, value, origin, label, generation, groupId))
            }
        }

        fun put(text: String, width: Int, kind: Kind, fore: Int, style: Int, font: String) {
            val last = segs.lastOrNull()
            if (last != null && last.kind == kind && last.fore == fore && last.style == style && last.font == font) {
                last.sb.append(text)
            } else {
                val b = SegBuilder(kind, fore, style, font)
                b.sb.append(text)
                segs.add(b)
            }
            col += width
        }

        fun putRaw(
            text: String,
            width: Int,
            kind: Kind,
            fore: Int,
            style: Int,
            font: String,
            htmlOption: Boolean = false,
        ) {
            val b = SegBuilder(kind, fore, style, font, htmlOption)
            b.sb.append(text)
            segs.add(b)
            col += width
        }

        fun build(isLogical: Boolean, temporary: Boolean): DisplayLine = DisplayLine(
            isLineEnd = false,
            segments = segs.map { it.build() },
            buttons = buttons.toList(),
            htmlOptions = htmlOptions.toList(),
            isLogicalLine = isLogical,
            isTemporary = temporary,
            alignment = alignment,
            backColor = backColor,
        )
    }

    private val pending = ArrayList<PendingSpan>()

    /** 显示行 + HTML 浮岛共用的**唯一监视器**（本类只有这一把锁；口径见类文档线程模型）。 */
    private val displayLines = ArrayList<DisplayLine>()

    /** 受 [displayLines] 监视器保护：写点 [addHtmlIsland]/[clearHtmlIslands]，读点 [htmlIslands]。 */
    private val islands = ArrayList<String>()

    /** 浮岛内容水位（见 [htmlIslandsVersion]）；只在 `synchronized(displayLines)` 内递增。 */
    @Volatile
    private var islandsVersion: Long = 0L

    /** 当前浮岛里的 `<button>`（与 [islands] 同锁；浮岛清空时一起清）。 */
    private val islandOptions = ArrayList<HtmlOption>()

    /** 原版 PrintStringBuffer.isLastLineEnd 初值 true（PrintStringBuffer.cs:24）。 */
    private var pendingLineEnd = true

    /**
     * 下一条**源按钮**的编号（[ButtonSpan.groupId]、[HtmlOption.groupId] 与 HTML 开标签的
     * 跨行重放都从这里取号，见 [takeHtmlButtonSource]）。
     *
     * 只为「同一源按钮的多个样式片/重放行」与「两条相邻同值显式按钮」不给并成一条（见
     * [RowBuilder.addButton] 与 [dedupeOptions] 的 KDoc），不是原版的字段：原版是对象身份
     * （`ConsoleButtonString` 实例），本仓在整数网格上用单调编号表达同一件事。
     * 单线程单 VM（ADR-003），不自增线程安全，也不需要跨 flush 复位 —— 只比相等。
     */
    private var nextButtonGroupId = 0

    /** 显示行的单调序号发号器（写侧 VM 线程；读侧只读已落行的 [DisplayLine.serial]）。 */
    private var lineSerial = 0L

    /**
     * 已入列的显示行号计数 —— 原版 `lineNo`（EmueraConsole.cs:220，读出口 :507
     * `GetLineNo => lineNo`）。折行的每一排各占一号，与逻辑行无关。
     *
     * 递增在 [appendGroup]（:2970 的 `lineNo++`，同一处还有 `== int.MaxValue` 归零回绕
     * :2979-2983），递减在 [deleteRowsLocked]（:3013 的 `lineNo--`）。
     * 两个**控制流**用途（原版都是这样用的，不是日志）：
     * 1. [deleteRowsLocked] 的 MaxLog 补行判据 `lineNo > displayLineList.Count`（:3026）——
     *    它等价于「头部已经淘汰过行」，用 `size + deletedLines` 推不出来（CLEARLINE 的
     *    `lineNo = 0` 分支会把这层关系打断），所以必须真维护一个计数器；
     * 2. 绘制偏移 `shift = (GetLineNo > MaxLog) ? GetLineNo - MaxLog : 0`（:1865-1868、:2402）。
     *    移植侧的滚动偏移由 render 侧 `LogBuffer.totalLines` 独立建模（`render/scroll/LogBuffer.kt`），
     *    本字段不喂它 —— 两处口径同源（都是「已入列排数」），但各自持有。
     *
     * 写入全在 `synchronized(displayLines)` 内；读侧拿到的值只作诊断，不做跨线程判据。
     */
    var lineNo: Int = 0
        private set

    /**
     * 被淘汰/删除的显示行数 —— 原版 `deletedLines`（EmueraConsole.cs:224，读出口 :511
     * `DeletedLines => deletedLines`）。
     *
     * 原版三个写点：`addDisplayLine` 头部淘汰 +1（:2995）、`deleteLine` 列表降到
     * `MaxLog - 1` 时 +1（:3024）、`deleteLine` 收尾 `-= argNum`（:3050）。
     * **原版内部无消费者**（`DeletedLines` 这个 public 属性在 emuera 里一次都没被读），
     * 纯记账；本仓照抄是为了让 [deleteRowsLocked] 的 :3022-3025 那一句有落点、不出现
     * 「读到一条没有实现的语句就跳过」的静默偏差。ADR-005：原版怎么记就怎么记，
     * 包括 `CLEARLINE` 负参数会把它加上去、删不足时会把它扣成负数。
     */
    var deletedLines: Long = 0L
        private set

    /**
     * 按钮世代状态（原版 `EmueraConsole.cs:104-112` 的四个字段 + `:770-823` 两条方法）。
     *
     * 按钮对象打印时盖 [newButtonGeneration] 并调 [stampButtonGeneration]；等待入口调
     * [newGeneration] 决定「哪些世代还算当前页」。语义与原版逐句一致，只有
     * `lastInputLine` 的身份位由宿主经 [InputRequest.scriptLine] 提供（未接线时退回行水位）。
     */
    private var lastButtonIsInput = true
    private var updatedGeneration = false

    /**
     * 当前可点世代。写点在 VM 线程（打印/等待），**读点还有 UI 线程**
     * （[displayOptions] 的兜底路径）⇒ 必须 volatile，否则轮询屏的右侧栏可能长期
     * 看到旧世代（表现为“上一个场景的按钮还在”）。
     */
    @Volatile
    private var lastButtonGeneration = 0L
    private var newButtonGeneration = 0L

    /** 上一次等待的脚本行身份（原版 `LogicalLine lastInputLine`；`Long.MIN_VALUE` = 还没等待过）。 */
    private var lastInputLine = Long.MIN_VALUE

    /**
     * 当前等待页的选项快照（世代 + 等待类型过滤后）。UI 线程只读，必须可见。
     *
     * 写点只有 [noteInputWait]（VM 线程）；读点 [currentOptions]（UI 线程）。列表本身
     * 不可变，读侧拿到的是整份快照，不存在半更新态。
     */
    @Volatile
    private var optionSnapshot: List<ConsoleOption> = emptyList()

    private var foreColor: Int? = null
    private var backColor: Int? = null
    private var styleMask = 0
    private var fontNameCur = defaultFontName
    private var align = Alignment.LEFT

    /**
     * 原版 `EmueraConsole.UseSetColorStyle`（EmueraConsole.cs:469，取值器 :471-489）。
     * 出厂 true；PRINTD / PRINTDATAD 族在方法体开头写成 `!IsPrintDFunction()`
     * （FunctionIdentifier.cs:180、:277；D 后缀置位 :141-145 与 :244-248，判定 :4240-4243），
     * 其后共五处无条件写回 true（:227、:317、ExpressionMediator.cs:68、
     * Process.cs:1111 / :1130 / :1150）。完整口径与「为什么这里只有一轴」见
     * 接口侧 [ConsoleSink.setUseSetColorStyle] 的 KDoc。
     * 只影响 [currentFore] 的解析，[foreColor] 本体与 GETCOLOR 读数都不动
     * （原版 GETCOLOR 读的也是 userStyle.Color，FunctionMethodCreator.cs:3662）。
     */
    private var useSetColorStyle = true

    /**
     * 原版 `EmueraConsole.UseUserStyle`（EmueraConsole.cs:467，取值器 :471-489 的第一条分支）。
     *
     * 出厂 **false**：C# `bool` 字段默认值，原版进程启动即如此；第一条 PRINT 语句
     * （FunctionIdentifier.cs:179 / :276、Process.cs:1110 / :1129 / :1149）才把它写成 true。
     * 写假的六处（:764 `ThrowError`、:2575 `GotoTitle`、:2607 Ctrl+Z、:3094 `PrintSystemLine`、
     * :3108 `PrintError`、:3127 `PrintErrorButton`）全在控制台内部流程，完整表见接口侧
     * [ConsoleSink.setUseUserStyle] 的 KDoc。
     *
     * **粘滞**：写假之后只有下一条 PRINT 系语句会写回真。因此 `PrintError` 之后的
     * `PRINTSINGLE`（`ExpressionMediator.cs:68` 只复位 UseSetColorStyle）在原版也是默认样式 ——
     * 本类不擅自「打完就还原」。
     *
     * 只影响**此后入缓冲的段**：三件样式（前景 / 字型位 / 字体名）在入缓冲时快照，
     * 已排好的显示行不回溯改写；[foreColor]/[styleMask]/[fontNameCur] 三个字段本体与
     * GETCOLOR / GETSTYLE / GETFONT 的读数都不动（原版 `StringStyle => userStyle`，
     * EmueraConsole.cs:491，与 UseUserStyle 无关）。
     */
    private var useUserStyle = false

    /**
     * 打印段前景色解析 = 原版 `Style` getter 的取色支（EmueraConsole.cs:471-489）：
     *
     * - `!useUserStyle`（系统行 / 错误行）⇒ `defaultStyle.Color`，**不看** [useSetColorStyle]；
     * - 非窗口（useSetColorStyle=true）用 userStyle（[foreColor] 未设即回落默认色）；
     * - D 系窗口（false）恒为默认色 —— 原版两分支「userStyle.Color == defaultStyle.Color →
     *   userStyle」与「否则 defaultStyle.Color」的实际取值相同。
     *
     * `defaultStyle.Color` 在原版是装载期取的 `Config.ForeColor`（EmueraConsole.cs:206），
     * 本仓取工厂常量 [DEFAULT_FORE_COLOR]（`ConfigData.cs:68` = `Color.FromArgb(192,192,192)`）。
     * 二者对本作**同值**：语料 `emuera.config:24` 就是 `TEXT COLOR:192,192,192`，
     * 基准侧 `defFore` 也按该常量交出去（`e2e/GoldenConsoleSink.kt:103`）⇒ 不需要注入项。
     * TODO(verify)：若日后支持用户改 `TEXT COLOR`，这条与 [foreColor] 为 null 时的缺省色
     * 要一起换成注入项（同 [defaultFontName] 的口径，`CONTRACT` 里那条「FONT NAME」的理由一致）；
     * 该缺口在 main 上本就存在（RESETCOLOR 的缺省也是同一常量），不是本次引入。
     */
    private fun currentFore(): Int =
        if (!useUserStyle) DEFAULT_FORE_COLOR
        else if (useSetColorStyle) foreColor ?: DEFAULT_FORE_COLOR
        else DEFAULT_FORE_COLOR

    private fun currentBack(): Int = backColor ?: DEFAULT_BACK_COLOR

    /**
     * 打印段字体名 = `Style` getter 的 Fontname 支：`!useUserStyle` 时回 `defaultStyle`
     * 的 null（`StringStyle.cs:20-27` 把空字体名归一成 `Config.FontName` ⇒ 本类的
     * [defaultFontName]），即「系统行不吃 SETFONT」。
     */
    private fun currentFont(): String = if (useUserStyle) fontNameCur else defaultFontName

    /**
     * 打印段字型位 = `Style` getter 的 FontStyle 支：`!useUserStyle` 时回
     * `FontStyle.Regular`（0），即「系统行不吃 FONTSTYLE/FONTBOLD/...」。
     * 读出侧 [fontStyle]（GETSTYLE）仍读字段本体，不经过这里。
     */
    private fun currentStyle(): Int = if (useUserStyle) styleMask else 0

    private fun rgb(r: Int, g: Int, b: Int): Int = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun bufferPrint(text: String, lineEnd: Boolean, forceButtonGroup: Boolean = false) {
        pending.add(TextSpan(text, Kind.TEXT, currentFore(), currentStyle(), currentFont(), forceButtonGroup))
        pendingLineEnd = lineEnd
    }

    /** 按钮/Plain/HTML 走 AppendButton/AppendPlainText，均**不修改** isLastLineEnd。 */
    private fun bufferButton(text: String, value: Any) {
        // 原版 PRINTBUTTON 在 Process.cs:1112 / :1136 先执行 text.Replace("\n", "")。
        val clean = text.replace("\n", "")
        if (clean.isEmpty()) return
        // 每次 AppendButton 都是一条新的 ConsoleButtonString（PrintStringBuffer.cs:103-123）。
        pending.add(
            ButtonSpan(
                clean,
                value,
                currentFore(),
                currentStyle(),
                currentFont(),
                nextButtonGroupId++,
                OptionOrigin.EXPLICIT,
                clean,
            ),
        )
    }

    /**
     * 不折行的独立显示行（PRINTSINGLE / REUSELASTLINE），对应原版
     * `PrintStringBuffer.FlushSingleLine`（PrintStringBuffer.cs:170-177）。
     *
     * 原版 `FlushSingleLine` 也先过 `fromCssToButton`（`:172`），所以普通文本一行
     * 同样要吃自动按钮（[ButtonStringCreator]）；错误行（[Kind.ERROR]）按
     * `AppendAndFlushErrButton` 的口径是**显式**一条按钮（PrintStringBuffer.cs:157-168），
     * 不在本条切分范围内。
     */
    private fun singleLine(text: String, kind: Kind, temporary: Boolean): DisplayLine {
        if (kind != Kind.TEXT) {
            return DisplayLine(
                isLineEnd = true,
                segments = listOf(Segment(text, currentFore(), currentStyle(), currentFont(), kind)),
                isLogicalLine = true,
                isTemporary = temporary,
                alignment = align,
                backColor = currentBack(),
                noBreak = true,
            )
        }
        val row = RowBuilder(align, currentBack())
        for (piece in ButtonStringCreator.splitButton(text)) {
            val start = row.col
            // 每个 primitive 落成一条 ConsoleButtonString（PrintStringBuffer.cs:378-386）
            val groupId = if (piece.canSelect) nextButtonGroupId++ else -1
            row.put(
                piece.text,
                CellWidth.of(piece.text),
                if (piece.canSelect) Kind.BUTTON else kind,
                currentFore(),
                currentStyle(),
                currentFont(),
            )
            if (piece.canSelect) {
                row.addButton(
                    start,
                    row.col,
                    piece.input,
                    groupId,
                    OptionOrigin.AUTO_TEXT,
                    piece.text.trim(),
                    stampButtonGeneration(),
                )
            }
        }
        return row.build(isLogical = true, temporary = temporary).copy(isLineEnd = true, noBreak = true)
    }

    private inline fun withRegularStyle(block: () -> Unit) {
        val saved = styleMask
        styleMask = 0
        try {
            block()
        } finally {
            styleMask = saved
        }
    }

    /**
     * 折行前的**自动按钮**切分：把待决缓冲按「cssList 节点组」整串拼起来再切
     * （`PrintStringBuffer.fromCssToButton` → `createButtons`，PrintStringBuffer.cs:280-292、:326-345）。
     *
     * 对应关系：
     * - 「节点组」= 连续的、会进 `m_stringList` 的非按钮段：[Kind.TEXT] 的 [TextSpan] 与 [HtmlSpan]。
     *   原版这两个位置上是 `ConsoleStyledString` 与 `Append(AConsoleDisplayNode)` 塞进来的非文本节点
     *   （PrintStringBuffer.cs:56-64、EmueraConsole.cs:3192-3201）；按钮（[ButtonSpan]）与
     *   PRINTPLAIN（`createPlainButton`，:125-134）在原文里都是「先 fromCssToButton 再单独成条」，
     *   天然断组。PRINTC/PRINTLC 同理：`force_button: true` 使它在追加前后各切一次
     *   （:73-76、:97-100），本类用 [TextSpan.forceButtonGroup] 让这一段落单成组。
     * - 「整串再切」= 拼起来的字符串交给 [ButtonStringCreator]，可选片落成 [ButtonSpan]
     *   （值 = 原版 `ConsoleButtonString` 的 long 构造 `Input`，ConsoleButtonString.cs:81-95）。
     *   于是折行/整颗按钮不拆/跨行每行一条按钮的既有逻辑（见 [buildRows] 的按钮分支）**一行都不用改**。
     *
     * 切完再按字符区间把节点切回去（[splitTextRun] / [splitCrossNode]），与 `createButtons`
     * 的 `num/num2` 游标（PrintStringBuffer.cs:347-377）同序。
     */
    private fun splitAutoButtons(spans: List<PendingSpan>): List<PendingSpan> {
        var candidate = false
        for (span in spans) {
            if (span is TextSpan && span.kind == Kind.TEXT && (span.text.contains('[') || span.text.contains(']'))) {
                candidate = true
                break
            }
        }
        if (!candidate) return spans

        val out = ArrayList<PendingSpan>(spans.size)
        var i = 0
        while (i < spans.size) {
            val span = spans[i]
            if (!isCssNode(span)) {
                out.add(span)
                i++
                continue
            }
            if (isForcedButtonGroup(span)) {
                // PRINTC 段自成一组：只在本段文本里找 `[n]`，不吞前后的普通文本。
                out.addAll(splitGroup(listOf(span)))
                i++
                continue
            }
            val group = ArrayList<PendingSpan>()
            while (i < spans.size && isCssNode(spans[i]) && !isForcedButtonGroup(spans[i])) {
                group.add(spans[i])
                i++
            }
            out.addAll(splitGroup(group))
        }
        return out
    }

    /** 会进 m_stringList 的段（原版 `ConsoleStyledString` + 非文本节点，PrintStringBuffer.cs:56-64）。 */
    private fun isCssNode(span: PendingSpan): Boolean =
        (span is TextSpan && span.kind == Kind.TEXT) || span is HtmlSpan

    /** PRINTC 定宽段：原版 force_button 的两次 fromCssToButton 使它既断前组、也自成一组。 */
    private fun isForcedButtonGroup(span: PendingSpan): Boolean =
        span is TextSpan && span.forceButtonGroup

    /**
     * 一组 cssList 节点的切分入口（两段式，见类 KDoc 的「自动按钮」节）：
     * 1. 先按**纯文本子组**各自切（HTML 段断组，与原版 `HTML_PRINT` 不进 `m_stringList`
     *    的事实一致：EmueraConsole.cs:3211-3215 → PrintStringBuffer.cs:136-140）；
     * 2. 若子组切出了可选按钮（说明方括号没有跨节点）⇒ 就用这份结果，**既有行为一字不变**；
     * 3. 否则（括号真的跨过 HTML 段）⇒ 整组拼串再切（[splitCrossNode]）。
     */
    private fun splitGroup(group: List<PendingSpan>): List<PendingSpan> {
        if (group.none { it is HtmlSpan }) {
            return splitTextRun(group.map { it as TextSpan })
        }
        val perText = ArrayList<PendingSpan>(group.size)
        var i = 0
        while (i < group.size) {
            val span = group[i]
            if (span is TextSpan) {
                val run = ArrayList<TextSpan>()
                while (i < group.size && group[i] is TextSpan) {
                    run.add(group[i] as TextSpan)
                    i++
                }
                perText.addAll(splitTextRun(run))
            } else {
                perText.add(span)
                i++
            }
        }
        if (perText.any { it is ButtonSpan }) return perText
        return splitCrossNode(group) ?: perText
    }

    /**
     * 一组相邻 [TextSpan] 的整串切分（原版 `createButtons` 的 `cssList` 全是样式段的常见情形）：
     * 拼串（PrintStringBuffer.cs:328-333）→ [ButtonStringCreator.splitButton]（:333）→
     * 按字符区间把样式段切回（:347-377）。切点落在字符边界上，样式按段回填。
     */
    private fun splitTextRun(group: List<TextSpan>): List<PendingSpan> {
        val joined = group.joinToString("") { it.text }
        if (!joined.contains('[') || !joined.contains(']')) return group
        val pieces = ButtonStringCreator.splitButton(joined)
        if (pieces.size == 1 && !pieces[0].canSelect) return group
        if (pieces.sumOf { it.text.length } != joined.length) {
            // 覆盖性兜底：切分结果必须逐字覆盖原串（原版 reduce 的写入序保证这一点），
            // 万一不成立就退回未切分的段，宁可没有自动按钮也不丢字。
            return group
        }
        val out = ArrayList<PendingSpan>(pieces.size)
        var offset = 0
        for (piece in pieces) {
            var start = offset
            val end = offset + piece.text.length
            offset = end
            val gid = if (piece.canSelect) nextButtonGroupId++ else -1
            var index = 0
            var base = 0
            while (index < group.size && base + group[index].text.length <= start) {
                base += group[index].text.length
                index++
            }
            while (start < end && index < group.size) {
                val span = group[index]
                val from = start - base
                val to = minOf(end - base, span.text.length)
                val chunk = span.text.substring(from, to)
                out.add(
                    if (gid >= 0) {
                        ButtonSpan(
                            chunk,
                            piece.input,
                            span.fore,
                            span.style,
                            span.font,
                            gid,
                            OptionOrigin.AUTO_TEXT,
                            piece.text.trim(),
                        )
                    } else {
                        TextSpan(chunk, Kind.TEXT, span.fore, span.style, span.font)
                    }
                )
                start = base + to
                index++
                base += span.text.length
            }
        }
        return out
    }

    /**
     * 跨节点的整串切分：`createButtons` 先把**所有**节点的 `Text` 拼成一串再切
     * （PrintStringBuffer.cs:326-345），切完按字符游标逐个节点消费（:347-377）。
     *
     * 本类节点的「Text」口径：文本段就是原文；HTML 段取**可见文本**
     * （原版节点里的文本本来就是 `Html2DisplayLine` 解析后的可见串，HtmlManager.cs:556-561
     * 的 `Unescape` 之后才构造 `ConsoleStyledString`），所以 `[`/`]` 能否配上也按可见文本判。
     *
     * 不可拆节点（本仓 = HTML 段）按 `CanDivide == false` 处理：切点落在它内部时，
     * **整个节点**并进当前按钮（PrintStringBuffer.cs:358-374 的
     * `if (num3 > 0 && aConsoleDisplayNode.CanDivide) {...}` 无 else 分支 ⇒ 直接 `list2.Add(node)`），
     * 下一个按钮从节点之后开始。
     *
     * 可选片吞掉 HTML 段时，节点被落成 [ButtonSpan] 的**可见文本**。
     * TODO(verify)：原版那个节点仍是原来的节点类型（HTML 样式/图片照旧绘制，只是整条
     * `ConsoleButtonString` 带 Input，ConsoleButtonString.cs:19、:296-303）；本仓把 HTML 存成
     * 原文、渲染层按 [Grid] 逐字计宽，若保留 `Kind.HTML` 会让命中区按标签宽度算（画出来是
     * 可见宽度），所以这里降级成可见文本段。语料里 `[...]` 不跨 HTML 段，卡点：无对拍读数。
     *
     * @return 切好的段；整串切不出可选片时返回 null（调用方回退到纯文本子组的结果）
     */
    private fun splitCrossNode(group: List<PendingSpan>): List<PendingSpan>? {
        val visible = group.map { if (it is HtmlSpan) htmlVisibleText(it.text) else (it as TextSpan).text }
        val joined = visible.joinToString("")
        if (!joined.contains('[') || !joined.contains(']')) return null
        val pieces = ButtonStringCreator.splitButton(joined)
        if (pieces.none { it.canSelect }) return null
        if (pieces.sumOf { it.text.length } != joined.length) return null

        val starts = IntArray(group.size)
        var acc = 0
        for (k in group.indices) {
            starts[k] = acc
            acc += visible[k].length
        }
        val ends = IntArray(group.size) { starts[it] + visible[it].length }

        val out = ArrayList<PendingSpan>(pieces.size + group.size)
        var cursor = 0
        var pieceIndex = 0
        while (cursor < joined.length && pieceIndex < pieces.size) {
            val piece = pieces[pieceIndex++]
            val start = cursor
            var end = minOf(joined.length, cursor + piece.text.length)
            // 边界落在不可拆节点内部 ⇒ 吞到节点末尾（可能连锁，故循环到稳定）。
            var moved = true
            while (moved) {
                moved = false
                for (k in group.indices) {
                    if (group[k] !is HtmlSpan) continue
                    if (end > starts[k] && end < ends[k]) {
                        end = ends[k]
                        moved = true
                    }
                }
            }
            if (end <= start) {
                cursor = maxOf(cursor, end)
                continue
            }
            val gid = if (piece.canSelect) nextButtonGroupId++ else -1
            for (k in group.indices) {
                val from = maxOf(start, starts[k])
                val to = minOf(end, ends[k])
                if (from >= to) continue
                val span = group[k]
                when (span) {
                    is TextSpan -> {
                        val chunk = span.text.substring(from - starts[k], to - starts[k])
                        out.add(
                            if (gid >= 0) {
                                ButtonSpan(
                                    chunk,
                                    piece.input,
                                    span.fore,
                                    span.style,
                                    span.font,
                                    gid,
                                    OptionOrigin.AUTO_TEXT,
                                    piece.text.trim(),
                                )
                            } else {
                                TextSpan(chunk, Kind.TEXT, span.fore, span.style, span.font)
                            }
                        )
                    }
                    is HtmlSpan -> out.add(
                        if (gid >= 0) {
                            ButtonSpan(
                                visible[k],
                                piece.input,
                                span.fore,
                                span.style,
                                span.font,
                                gid,
                                OptionOrigin.AUTO_TEXT,
                                piece.text.trim(),
                            )
                        } else {
                            span
                        }
                    )
                    else -> out.add(span)
                }
            }
            cursor = end
        }
        return out
    }

    /**
     * 把待决内容排版为显示行。
     *
     * 折行规则：当前列 > 0 且放下该字符会超过 [clientWidth] 时换行；单字符本身超宽时
     * 独占一行（否则会死循环）。原版按像素做同样的「尽量放入」判定
     * （PrintStringBuffer.cs:217-241、getDivideIndex :414-451）。
     *
     * 入口先过 [splitAutoButtons]：普通文本里的 `[123]` 在这里变成可选段
     * （原版 `fromCssToButton`/`createButtons`，PrintStringBuffer.cs:280-292、:326-345），
     * 之后按钮片走下面那条 `is ButtonSpan` 分支，与 PRINTBUTTON 同一个折行口径。
     *
     * @param nobr true = 整段排成**一条**显示行、不做任何折行判定。对应原版
     *   `PrintStringBuffer.FlushSingleLine` 的 `setWidthToButtonList(..., nobr: true)`
     *   （PrintStringBuffer.cs:170-177），只被 [bufferToSingleRow] 的 MaxLog 补行路径使用
     *   （EmueraConsole.cs:3028）；常规 flush 一律 false。
     */
    private fun buildRows(
        spans: List<PendingSpan>,
        temporary: Boolean,
        nobr: Boolean = false,
    ): List<DisplayLine> {
        val builders = ArrayList<RowBuilder>()
        var cur = RowBuilder(align, currentBack())
        builders.add(cur)

        fun newRow() {
            cur = RowBuilder(align, currentBack())
            builders.add(cur)
        }

        for (span in splitAutoButtons(spans)) {
            when (span) {
                is TextSpan -> {
                    var i = 0
                    while (i < span.text.length) {
                        val cp = span.text.codePointAt(i)
                        val n = Character.charCount(cp)
                        val w = CellWidth.ofCodePoint(cp)
                        if (!nobr && clientWidth > 0 && cur.col > 0 && cur.col + w > clientWidth) newRow()
                        cur.put(span.text.substring(i, i + n), w, span.kind, span.fore, span.style, span.font)
                        i += n
                    }
                }

                is ButtonSpan -> {
                    /*
                     * Config.ButtonWrap=true（「BUTTON WRAPPING」）时的「整颗按钮不拆」：
                     * PrintStringBuffer.cs:217 先判 `PointX + Width <= DrawableWidth`，放不下才进 :222 的
                     * 分支；:222 的条件是 `!Config.ButtonWrap || list2.Count == 0 ||
                     * (!buttonList[i].IsButton && !Config.CompatiLinefeedAs1739)`。语料
                     * emuera.config:38 `BUTTON WRAPPING:YES`、:55 前 1739 兼容为 NO ⇒ 可选按钮
                     * 在当前行非空时**不拆分**，而是 :242 直接结束本行、:244-249 把剩余按钮重排到新行；
                     * 新行仍放不下（按钮比整行还宽）才回到拆分路径（此时 list2 已空）。
                     * 非按钮文本（[TextSpan]）不受此开关影响，永远按字符折行。
                     */
                    if (!nobr && buttonWrap && clientWidth > 0 && cur.col > 0 &&
                        cur.col + CellWidth.of(span.text) > clientWidth
                    ) {
                        newRow()
                    }
                    // 一条源按钮（可横跨多行/多段）共用一个世代：原版 ConsoleButtonString
                    // 在创建时取 NewButtonGeneration 并 UpdateGeneration（:91-138）。
                    val generation = stampButtonGeneration()
                    var start = -1
                    var i = 0
                    while (i < span.text.length) {
                        val cp = span.text.codePointAt(i)
                        val n = Character.charCount(cp)
                        val w = CellWidth.ofCodePoint(cp)
                        if (!nobr && clientWidth > 0 && cur.col > 0 && cur.col + w > clientWidth) {
                            if (start >= 0) {
                                cur.addButton(
                                    start,
                                    cur.col,
                                    span.value,
                                    span.groupId,
                                    span.origin,
                                    span.label,
                                    generation,
                                )
                                start = -1
                            }
                            newRow()
                        }
                        if (start < 0) start = cur.col
                        cur.put(span.text.substring(i, i + n), w, Kind.BUTTON, span.fore, span.style, span.font)
                        i += n
                    }
                    if (start >= 0) {
                        cur.addButton(
                            start,
                            cur.col,
                            span.value,
                            span.groupId,
                            span.origin,
                            span.label,
                            generation,
                        )
                    }
                }

                is HtmlSpan -> {
                    val w = visibleHtmlWidth(span.text)
                    if (!nobr && clientWidth > 0 && cur.col > 0 && cur.col + w > clientWidth) newRow()
                    // 本段 HTML 里的按钮落到**当前行**：列号 = 段首在本行的列 + 段内可见前缀宽。
                    // 同一段里多个按钮按源序递增；跨段/跨行的按钮由各段各自登记。
                    if (span.htmlOptions.isNotEmpty()) {
                        val base = cur.col
                        for (o in span.htmlOptions) {
                            cur.htmlOptions.add(
                                o.copy(startCol = o.startCol + base, endCol = o.endCol + base),
                            )
                        }
                    }
                    cur.putRaw(span.text, w, Kind.HTML, span.fore, span.style, span.font, span.option)
                }
            }
        }

        return builders.mapIndexed { index, b -> b.build(isLogical = index == 0, temporary = temporary) }
    }

    /**
     * 把待决缓冲排版成显示行并落进列表，**返回这次排版出来的行**（原版
     * `PrintStringBuffer.Flush` 的返回值就是新建的 ConsoleDisplayLine 数组，
     * PopDisplayingLines 直接把该数组交给调用方，EmueraConsole.cs:3586）。
     *
     * 返回值是 [appendGroup] **之前**的排版结果：PRINTN 未收尾时新组的第一行会被并进
     * 上一条显示行（:2948-2977），那种情况下被并入的那一行不在返回值里、返回值里的
     * 第一行也不等于列表里的那一条。调用方（[popDisplayingLines]）只需要文本与列区间，
     * 不需要「列表里的同一实例」，与原版同判（原版返回的也是 flush 的临时数组）。
     *
     * @param commitToDisplayList false ⇒ **只排版不落列表**（不调 [appendGroup]、不动
     *   [logicalLineCount] 与 [revision]）。这是原版 `PopDisplayingLines` 的形状：
     *   方法体只有 `return printBuffer.Flush(...)`（EmueraConsole.cs:3588），
     *   `Flush` 自己只 `clearBuffer()`（PrintStringBuffer.cs:184 → :273-278），
     *   把数组交给调用方而不进 `displayLineList`。
     *   调用点只有 [popDisplayingLines]；其余 flush 路径（换行 / PRINTSINGLE / HTML /
     *   错误行）都要落列表，保持缺省 true。
     */
    private fun flushPending(
        force: Boolean,
        temporary: Boolean = false,
        commitToDisplayList: Boolean = true,
    ): List<DisplayLine> {
        if (pending.isEmpty()) {
            if (!force) return emptyList()
            bufferPrint(" ", true) // 原版 force flush 空缓冲补一个空格（EmueraConsole.cs:3379-3382）
        }
        val rows = buildRows(pending, temporary)
        pending.clear()
        if (rows.isEmpty()) return emptyList()
        val fixed = rows.toMutableList()
        fixed[fixed.lastIndex] = fixed.last().copy(isLineEnd = pendingLineEnd)
        if (commitToDisplayList) appendGroup(fixed)
        // 注意：原版 clearBuffer 不重置 isLastLineEnd（PrintStringBuffer.cs:273-278），
        // 只在下次 Append 时更新。因此这里必须保留 pendingLineEnd：PRINTN 之后的
        // PRINTBUTTON/PRINTPLAIN/HTML_PRINT（AppendButton/AppendPlainText）会继续未断行。
        return fixed
    }

    /**
     * 把一次 flush 的显示行并入列表。
     *
     * 合并规则对应 EmueraConsole.addDisplayLine（:2891-2990）：
     * 1. 末行是临时行 → 先删掉；
     * 2. 末行 `IsLineEnd=false`（PRINTN 未收尾）→ 把新组的第一行并进末行，其余行追加；
     * 3. 只有末行有 lineEnd；每出现一条 `isLineEnd=true` 的显示行，LINECOUNT +1。
     */
    private fun appendGroup(newRows: List<DisplayLine>) {
        if (newRows.isEmpty()) return

        // 临时行先删、未结行并入、逐行追加、超限淘汰是一条复合序列，必须整体持锁：
        // 中途任何一步被读侧快照撞上都会并发修改。锁内只做纯排版/列表操作
        // （mergeRows / trimToMaxLines 均为纯函数，不调用任何外部回调）。
        synchronized(displayLines) {
            if (displayLines.isNotEmpty() && displayLines.last().isTemporary) {
                val temp = displayLines.removeAt(displayLines.lastIndex)
                if (temp.isLineEnd) logicalLineCount--
                // 原版这一步是 `deleteLine(1)`（:2891-2893），其中 :3013 的 lineNo-- 必须留着：
                // 紧随其后的 addDisplayLine 会把 lineNo 加回去，净额为零。漏掉这一减，
                // lineNo 会随每次临时行替换虚涨 1，把 deleteLine 的 :3026 判据（lineNo > Count）带偏。
                lineNo--
            }

            val rows = if (displayLines.isNotEmpty() && !displayLines.last().isLineEnd) {
                val prev = displayLines.removeAt(displayLines.lastIndex)
                // 同上：原版并入前也走 `deleteLine(1)`（:2954），行号一减一加抵平。
                lineNo--
                listOf(mergeRows(prev, newRows.first())) + newRows.drop(1)
            } else {
                newRows
            }

            for (row in rows) {
                // 行序号在这里统一发号：只有真正进列表的行才消耗序号，折行/并入/淘汰都不会
                // 把序号复用给后面的页，右侧栏的“当前页”水位因此不会被旧行串进来。
                val stamped = row.copy(serial = ++lineSerial)
                displayLines.add(stamped)
                if (stamped.isLineEnd) logicalLineCount++
                // :2970 `lineNo++` 与 :2979-2987 两条回绕（int 行号归零、long 逻辑行计数归零）。
                lineNo++
                if (lineNo == Int.MAX_VALUE) lineNo = 0
                if (logicalLineCount == Long.MAX_VALUE) logicalLineCount = 0L
            }
            trimToMaxLines()
            // 本函数是 flush / 并入 / 临时行先删 / MaxLog 淘汰的唯一汇合点（见 [revision] 的 KDoc）。
            // 帧闸关闭时只记脏不发布：REDRAW 0 段内 / 让出边界之间的逐行打印中间态不逐条上屏，
            // 由下一次 [publishFrame]（AWAIT / 输入等待 / REDRAW 1）一次性发布完整帧。
            // 选项快照与 revision 同一发布点，这里只置脏（见 [publishedOptions]）。
            optionsDirty = true
            if (publishGateOpen) revision++ else gateDirty = true
        }
    }

    /**
     * 把同一逻辑行的两段拼接起来（原版 addDisplayLine 的并入分支，EmueraConsole.cs:2947-2967）。
     *
     * 原版并入时**保留的是新行**：`deleteLine(1)` 删掉旧的未结行，再
     * `line.ShiftPositionX(旧行末按钮的 PointX + Width)`（:2955）并把旧行按钮接到新行按钮**前面**
     * （:2956-2966）。所以并完之后这一行的身份（[DisplayLine.isLogicalLine]、[DisplayLine.isTemporary]、
     * 对齐、背景色、行结束标记）全部来自 [next]（新行），旧行只贡献内容与它自己的宽度位移。
     *
     * 这一条**不是**实现细节：旧行可能是同一次 flush 的续行（`isLogicalLine=false`），
     * 若把旧行的标志留下来，随后的 CLEARLINE 会把「本行未结束」误判成「整个逻辑行已删完」，
     * 连上一行一起删掉（对照 deleteLine 的 :3014-3021 只认 `IsLogicalLine && IsLineEnd`）。
     */
    private fun mergeRows(prev: DisplayLine, next: DisplayLine): DisplayLine {
        val shift = lineWidth(prev)
        return next.copy(
            segments = prev.segments + next.segments,
            buttons = prev.buttons + next.buttons.map { it.copy(startCol = it.startCol + shift, endCol = it.endCol + shift) },
            // 旧行在前、新行接在它右沿之后：新行的 HTML 按钮列号同样平移。
            htmlOptions = prev.htmlOptions + next.htmlOptions.map {
                it.copy(startCol = it.startCol + shift, endCol = it.endCol + shift)
            },
        )
    }

    /**
     * MaxLog 淘汰：每次入列后若超过 [maxLines] 就丢最旧一行（EmueraConsole.cs:2988-2996）。
     * 原版每调一次 `addDisplayLine` 最多摘一行并 `deletedLines++`（:2995），本仓一次 flush
     * 可能入列多排，故循环 —— 与「逐排调用 addDisplayLine」等价，淘汰计数按行累加。
     */
    private fun trimToMaxLines() {
        if (maxLines <= 0) return
        while (displayLines.size > maxLines) {
            displayLines.removeAt(0)
            deletedLines++
        }
    }

    private fun lineWidth(line: DisplayLine): Int = line.segments.sumOf { segmentWidth(it) }

    private fun segmentWidth(seg: Segment): Int = when (seg.kind) {
        Kind.HTML -> visibleHtmlWidth(seg.text)
        else -> CellWidth.of(seg.text)
    }

    /**
     * PRINTC 的补齐（EmueraConsole.cs:3247-3291 的等宽模型版）。
     * 注意原版**不截断超宽文本**：只在补了空格后、像素宽仍超 `PrintCLength` 个空格宽时
     * 从行首/行尾删空格。
     */
    private fun padC(text: String, alignRight: Boolean): String {
        if (printCWidth <= 0) return text
        var s = text
        var w = CellWidth.of(s)
        if (alignRight) {
            if (w < printCWidth) {
                s = " ".repeat(printCWidth - w) + s
                w = CellWidth.of(s)
                // 原版的裁剪只在补齐分支内执行（EmueraConsole.cs:3263-3271）。
                while (w > printCWidth && s.isNotEmpty() && s[0] == ' ') {
                    s = s.substring(1)
                    w--
                }
            }
        } else {
            if (w < printCWidth + 1) {
                s += " ".repeat(printCWidth + 1 - w)
                w = CellWidth.of(s)
                while (w > printCWidth && s.isNotEmpty() && s[s.length - 1] == ' ') {
                    s = s.substring(0, s.length - 1)
                    w--
                }
            }
        }
        return s
    }

    /** getStBar：把 [unit] 重复到至少 [widthCells]，再逐字符裁到不超过。spec: EmueraConsole.cs:3417-3437。 */
    private fun barString(unit: String, widthCells: Int): String {
        if (unit.isEmpty() || widthCells <= 0) return ""
        val sb = StringBuilder(unit)
        var w = CellWidth.of(unit)
        while (w < widthCells) {
            sb.append(unit)
            w += CellWidth.of(unit)
        }
        while (w > widthCells && sb.isNotEmpty()) {
            val end = sb.length
            val cp = sb.codePointBefore(end)
            sb.setLength(end - Character.charCount(cp))
            w -= CellWidth.ofCodePoint(cp)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 字符宽度：唯一实现在 CellWidth（本类不再自行累加）
    // ------------------------------------------------------------------

    /** 剥掉 HTML 标签后的可见宽度（粗略；HTML 排版归 M4，TODO(verify)）。 */
    private fun visibleHtmlWidth(s: String): Int {
        var w = 0
        var i = 0
        var inTag = false
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                inTag = true
                i++
                continue
            }
            if (c == '>' && inTag) {
                inTag = false
                i++
                continue
            }
            if (inTag) {
                i++
                continue
            }
            if (c == '&') {
                val end = s.indexOf(';', i + 1)
                if (end > i) {
                    val entity = s.substring(i + 1, end)
                    val cp = when (entity) {
                        "lt" -> '<'.code
                        "gt" -> '>'.code
                        "amp" -> '&'.code
                        "quot" -> '"'.code
                        "apos" -> '\''.code
                        "nbsp" -> ' '.code
                        else -> when {
                            entity.startsWith("#x") || entity.startsWith("#X") ->
                                entity.substring(2).toIntOrNull(16) ?: '?'.code
                            entity.startsWith("#") -> entity.substring(1).toIntOrNull(10) ?: '?'.code
                            else -> '?'.code
                        }
                    }
                    w += CellWidth.ofCodePoint(cp)
                    i = end + 1
                    continue
                }
            }
            val cp = s.codePointAt(i)
            w += CellWidth.ofCodePoint(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    companion object {
        /**
         * 源按钮编号的「身份未知」取值（[Button.groupId] / [HtmlOption.groupId] /
         * [ConsoleOption.groupId]）：与渲染层 `ButtonLayer.ID_UNKNOWN` 同一口径。
         * 真实编号由 [nextButtonGroupId] 单调发出，恒 `>= 0`。
         */
        const val ID_UNKNOWN = -1

        /** Config.PrintCLength 默认值（ConfigData.cs:64）——《ConsoleSink》KDoc 里写的「宿主按 PrintCWidth」即此项。 */
        const val DEFAULT_PRINT_C_WIDTH = 25

        /** Config.MaxLog 默认值（ConfigData.cs:62）。 */
        const val DEFAULT_MAX_LINES = 5000

        /**
         * `Config.ButtonWrap` 的**出厂缺省**（ConfigData.cs:83 `new ConfigItem<bool>(ConfigCode.ButtonWrap,
         * …, t: false)`），即「按钮可以拆到两行」。语料把这一项打开了：
         * `emuera.config:38 BUTTON WRAPPING:YES`（同文件 :55
         * `REPRODUCE WRAPPING BEHAVIOR LIKE IN PRE VER1739:NO` ⇒ `Config.CompatiLinefeedAs1739=false`），
         * 所以真机/PC 基准的语义是 `true`。
         *
         * 这一项**必须由调用方按 config 注入**（与 [defaultFontName]、[printCWidth] 同一条规矩）：
         * GameSession / GoldenPipeline 目前没接线，走的是本缺省 —— 需组长接线（见 M6-B HOST-AUDIT 回执）。
         */
        const val DEFAULT_BUTTON_WRAP = false

        /** Config.ForeColor 默认值 192,192,192（ConfigData.cs:68）。 */
        const val DEFAULT_FORE_COLOR = 0xC0C0C0

        /** Config.BackColor 默认值 0,0,0（ConfigData.cs:69）。 */
        const val DEFAULT_BACK_COLOR = 0x000000

        /**
         * Config.FocusColor 出厂缺省 = `Color.FromArgb(255, 255, 0)` = 亮黄 0xFFFF00
         * （ConfigData.cs:70，条目中文名「選択中文字色」、英文名「Highlight color」）。
         * GETFOCUSCOLOR 读的就是它（FunctionMethodCreator.cs:3666-3679），
         * 基准取证 pc-golden-root.txt:266 `FOCUS=16776960` = 0xFFFF00 —— 与缺省一致，
         * 说明 PC 会话没有改过这一项。
         */
        const val DEFAULT_FOCUS_COLOR = 0xFFFF00

        /** Config.FontName 出厂缺省（ConfigData.cs:65）；真机值由 emuera.config 覆盖，见构造参数 [defaultFontName]。 */
        const val DEFAULT_FONT_NAME = "ＭＳ ゴシック"

        /**
         * 标签匹配 = 原版 `Html2PlainText` 的 `"\\<[^<]*\\>"`（HtmlManager.cs:716）。
         * 转义 `<>` 在正则里没有意义，这里写成同样的字面量（同一张规则、同一个来源）。
         */
        private val HTML_TAG_REGEX = Regex("<[^<]*>")

        /**
         * 图形标签（GFX-03 尾行判据用）：img/shape 在 C# HtmlManager.cs:279-280 与 br
         * 同列特殊处理，产 ConsoleImagePart / ConsoleShapePart 节点——无可见文本也是节点。
         */
        private val GRAPHIC_TAG_REGEX = Regex("<(img|shape)\\b", RegexOption.IGNORE_CASE)

        /** HTML 注释：按钮抽取前先剥掉（注释里的标签不参与渲染，也不该进选项栏）。 */
        private val HTML_COMMENT_REGEX = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

        /** 行首序号前缀（`[0] `、`[ 12] `），只清行首，不动 `返回 [9]` 这种行中序号。 */
        private val ORDINAL_PREFIX_REGEX = Regex("""^\[\s*\d{1,4}\s*\]\s*""")

        /** 同上，但捕获数字本身（`[ 27]` → 27），用于「前缀是否等于 value」的判定。 */
        private val ORDINAL_PREFIX_NUMBER_REGEX = Regex("""^\[\s*(\d{1,4})\s*\]""")

        private val WHITESPACE_REGEX = Regex("""\s+""")

        /** 原版 Unescape 的内置实体名分支（HtmlManager.cs:782-799），键已小写。 */
        private val NAMED_ENTITIES = mapOf(
            "nbsp" to " ",
            "amp" to "&",
            "gt" to ">",
            "lt" to "<",
            "quot" to "\"",
            "apos" to "'",
        )
    }
}