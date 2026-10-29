package com.eratw.port.core.proc

import com.eratw.port.core.audio.ChannelAudioHost
import com.eratw.port.core.audio.soundChannelTable
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.fn.AudioHost
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConsolePrintedLine
import com.eratw.port.core.fn.ConsoleStyleSnapshot
import com.eratw.port.core.fn.DirectoryProbeFileOps
import com.eratw.port.core.fn.IdleInputState
import com.eratw.port.core.fn.InputState
import com.eratw.port.core.fn.TextBoxHost
import com.eratw.port.core.fn.TextMeasureRequest
import com.eratw.port.core.fn.consolePrintedLines
import com.eratw.port.core.fn.consoleStyleOf
import com.eratw.port.core.host.BinaryFileOps
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.LineBufferConsoleSink
import java.nio.file.Path

/**
 * Console 感知的 [BuiltinHost]：把 [RootedBuiltinHost] 的文件 / 时钟 / 存档探测 / 配置能力
 * 与「开局控制台」拼成一只宿主，供 [ProcessRuntime] 或 headless 开局装置使用。
 *
 * 原版只有一只 `EmueraConsole` 承担输出与样式状态：`bgColor`（EmueraConsole.cs:200）、
 * `userStyle`（:208）、`StringStyle`（:491）、`Alignment`（:493-503）、`Redraw`（:455）、
 * 打印缓冲空态 `EmptyLine`（:505）。GETCOLOR / GETSTYLE / GETFONT / CURRENTALIGN /
 * CURRENTREDRAW / LINEISEMPTY 全部直接读它（FunctionMethodCreator.cs:3648-3745、
 * :3793-3806），语句写、函数读同一份状态。所以本类不另存样式：[consoleStyle] 每次读都
 * 从 [console] 现取现算（唯一装配点 [consoleStyleOf]），[lineIsEmpty] 读同一份打印缓冲。
 *
 * **必须显式覆写七项**：本类用 `BuiltinHost by delegate` 做接口委托。Kotlin 为带默认实现
 * 的接口成员生成的转发实现会把调用交给委托对象，而不是读本类属性；委托的
 * [RootedBuiltinHost] 没有控制台，默认实现会在它身上得到 `consoleSink = null`、
 * `consoleStyle = null`、`lineIsEmpty = false`、`textBox = null`、`consoleLines = null`、
 * `popPrintingLines = null`（M4-E 首次接线与 HOST-02 的实际卡点）。
 * 因此 [consoleSink] / [consoleStyle] / [lineIsEmpty] / [textBox] / [configOverrides] /
 * [consoleLines] / [popPrintingLines] 都在类体里覆写，委托只负责其余成员
 * （clockMillis / readFileBytes / checkSaveData / soundDir 等）。
 * [inputState] / [fontSize] / [clientWidth] / [clientHeight] 同理不落在委托上，
 * 但它们用**构造属性**覆写（HOST-04 / GFX-06 同一模式）：注入值即读数，缺省时
 * 与 [BuiltinHost] 的出厂缺省逐项同值，headless 装配零行为差。
 *
 * **M11-E hostwire 的字形掩码透出走委托，不走显式覆写**（结论）：上述七项要覆写是因为
 * 值来自本类自持的控制台状态，落到委托对象上求值就丢；而 `glyphMaskHost` 的状态**本就住在
 * [delegate]**（[RootedBuiltinHost] 的构造参，M11-E 注入），「转给委托对象」正是想要的
 * 行为，与 [ConfigChainSource] 委托同理。故类声明追加 `GlyphMaskHostProvider by delegate`
 * 一条即可：消费方（`fn/GraphicsMethods.kt` 的 GDRAWTEXT 段，glyph-core 路）按
 * `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost` 取到**注入的同一实例**（判据④，
 * 用例 `GlyphHostWireTest`）。接口签名由 CONTRACT-M11-E §4 钉死，定义在
 * `core/host/GlyphMaskHost.kt`（glyph-core 路新建）。
 *
 * 写请求缺省一律拒绝：语料只读（AGENTS §1），而 [RootedFileOps.writeText] 会在根目录内真的
 * 落盘（core/proc/BootHosts.kt:50-60）。拒绝按原版**失败口径**返回 false，即 SAVETEXT
 * 返回 0（FunctionMethodCreator.cs:8010-8014 的 catch → 0L；语句层映射见
 * core/fn/HostMethods.kt:191-212）。[writes] 的计数供开局报告区分「装置挡掉的写」与
 * 「引擎真缺的写」，不把阻塞点伪装成推进。
 *
 * HOST-05（M11-B）开的显式可写注入口：[writeRoot] 非 null 时写请求放行到该根下的
 * [RootedFileOps]，SAVETEXT（FunctionMethodCreator.cs:7936-8017，:8010
 * `File.WriteAllText`）与 OUTPUTLOG（EmueraConsole.cs:3444-3484，:3449
 * `File.WriteAllText`）由此真写。注入口选型（契约「二选一」）：**生产装配面 =
 * [GameSession] 的装配参数**（真机从这里喂应用私有可写目录），本构造参数是同一机制的
 * 机械承接（headless 装置与测试直接建宿主时用），不是第二套语义。放行后的写路径安全
 * 边界与缺省的读路径同源：都在 [RootedFileOps] 的根内判定里（BootHosts.kt 的
 * resolvePath —— normalize 后必须仍在根内，绝对出根与「上跳出根」一律 null ⇒ 返回
 * false），不逃逸。
 *
 * @param root 游戏可执行目录（原版 `Program.ExeDir`，ERB 的上一级）。
 * @param console 控制台；null = 无控制台（样式与行缓冲函数按「宿主未接线」口径抛 CodeEE）。
 * @param delegate 其余能力的来源，默认按 [root] 新建 [RootedBuiltinHost]。
 * @param inputState 键盘/指针后端。缺省 [IdleInputState]（headless：窗口未激活，
 *   `GETKEY` / `GETKEYTRIGGERED` / `ISACTIVE` 一律按 0 回答，spec:
 *   FunctionMethodCreator.cs:7816-7819）；真机由应用层注入按键事件源，否则
 *   `TITLE.ERB:158` 的跳出条件 `GETKEYTRIGGERED(1|13|27)` 恒假（红 E）。
 * @param writeRoot 可写注入口（HOST-05）。null（缺省）= 拒写口径不变（golden/E2E 零差）；
 *   非 null = 写请求放行到该目录下的 [RootedFileOps]，SAVETEXT / OUTPUTLOG 真写
 *   （spec: FunctionMethodCreator.cs:8010、EmueraConsole.cs:3449），写路径限该根内。
 *   生产装配走 [GameSession] 的同名装配参数（选型说明见类 KDoc）。
 * @param fontSize 脚本像素尺（`Config.FontSize`，像素）。缺省 [BuiltinHost.DEFAULT_FONT_SIZE]
 *   = 18（ConfigData.cs:66，headless 与 PC 基准同值）；生产由 [GameSession] 喂**生效配置的字号**
 *   （= `scriptFontPixelSize`，条目表被 `emuera.config` 覆盖后的那一份）。
 *   2026-10-29 起不再接受应用层注入真机 page-fit 实测字号：这一路是 `HTML_STRINGLEN` 一族的
 *   量宽尺，语料的指令按钮网格拿它和 `GETCONFIG("フォントサイズ")` 算出的格宽比，
 *   两把尺一分叉每个按钮就只占应得宽度的一半上下（错位判据见 [BuiltinHost.fontSize] 的 KDoc）。
 *   屏幕像素（状态页 / 体型图的排版宽）走下面两条，那才是真机实测值。
 * @param clientWidth `CLIENTWIDTH()` 的像素值（原版 = MainPicBox.Width，EmueraConsole.cs:232）。
 *   缺省 [BuiltinHost.DEFAULT_CLIENT_WIDTH] = 760（ConfigData.cs:56）；真机注入内容区像素宽。
 *   注意与 [LineBufferConsoleSink.clientWidth]（**半角格**，折行单位）不是同一个量，
 *   见 GoldenPipeline.newConsole 的 KDoc。
 * @param clientHeight `CLIENTHEIGHT()` 的像素值（原版 = WindowY - LineHeight）。
 *   缺省 [BuiltinHost.DEFAULT_CLIENT_HEIGHT] = 461（ConfigData.cs:57,67）。
 * @param printCPerLine / @param printCLength `PRINTCPERLINE()` / `PRINTCLENGTH()` 的读数，
 *   原版语义就是 `Config.PrintCPerLine` / `Config.PrintCLength`（FunctionMethodCreator.cs:3922-3950）。
 *   生产由 [GameSession] 喂生效条目值：语料那层的网格宽度直接由 `PRINTCLENGTH()` 乘出来，
 *   读死缺省 25 而玩家把「PRINTCの文字数」改成别的值时，同一份网格里两个读数就会分叉。
 */
class ConsoleBuiltinHost(
    root: Path,
    private val console: ConsoleSink? = null,
    private val delegate: RootedBuiltinHost = RootedBuiltinHost(root),
    override val inputState: InputState = IdleInputState,
    /** 声道表所在变量池（HOST-03）；null = 未接音频宿主，四项走接口缺省（0 / false / null）。 */
    private val audioStore: com.eratw.port.core.vars.VariableStore? = null,
    writeRoot: Path? = null,
    override val fontSize: Long = BuiltinHost.DEFAULT_FONT_SIZE,
    override val clientWidth: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    override val clientHeight: Long = BuiltinHost.DEFAULT_CLIENT_HEIGHT,
    /**
     * 带样式的分段量宽（FN-04 core）：生产由 `GameSession` 从 Android 侧
     * `AndroidTextMeasure.measurePixels` 包一层传进来；null = 未接线，回落
     * [BuiltinHost.measureText] → 等宽近似（headless / 装置读数不变）。
     */
    private val textMeasureRun: ((TextMeasureRequest) -> Long?)? = null,
    /**
     * PRINTC 两格的**生效配置值**（`PRINTCPERLINE` / `PRINTCLENGTH` 的读数）。
     * 缺省 = 接口缺省（3 / 25，ConfigData.cs:63-64）；生产由 [GameSession] 喂条目表生效值。
     *
     * 为什么必须由会话喂：`PRINTCLENGTH()` 的原版语义就是 `Config.PrintCLength`
     * （spec: FunctionMethodCreator.cs:3937-3950），而条目表是被 `emuera.config` 覆盖后的那一份。
     * 不喂的话玩家把「PRINTCの文字数」改成 30，`GETCONFIG` 报 30、`PRINTCLENGTH()` 仍报 25，
     * 语料那层按 `PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2` 排的按钮网格就自相矛盾
     * —— 与 2026-10-29 指令列表错位同一族病（脚本可见的两个读数必须同源）。
     */
    override val printCPerLine: Long = BuiltinHost.DEFAULT_PRINT_C_PER_LINE,
    override val printCLength: Long = BuiltinHost.DEFAULT_PRINT_C_LENGTH,
) : BuiltinHost by delegate, ConfigChainSource by delegate, GlyphMaskHostProvider by delegate {

    /**
     * fileOps 的写闸：读路径恒走 [RootedFileOps]`(root)`（与 [delegate] 同根），
     * 写路径由 [writeRoot] 决定 —— null = 拒写并计数（[WriteBlockedFileOps.blockedWrites]
     * 供报告读数），非 null = 放行到 [RootedFileOps]`(writeRoot)`（边界同源，根内不逃逸）。
     */
    val writes: WriteBlockedFileOps = WriteBlockedFileOps(
        RootedFileOps(root),
        writeRoot?.let { RootedFileOps(it) },
    )

    override val fileOps: FileOps get() = writes

    /**
     * FN-04：显式覆写 —— `BuiltinHost by delegate` 会把它转给 [delegate]（缺省 null），
     * 注入的 [textMeasureRun] 必须压过那条转发（同 [consoleSink] 的委托陷阱）。
     */
    override fun measureTextRun(request: TextMeasureRequest): Long? =
        textMeasureRun?.invoke(request) ?: delegate.measureTextRun(request)

    /** 显式覆写：委托的 RootedBuiltinHost 看不到本类的控制台（见类 KDoc 的委托陷阱）。 */
    override val consoleSink: ConsoleSink? get() = console

    /**
     * 样式快照从 [console] 的活状态派生；null = 无控制台 → GETCOLOR 家族按「宿主未接线」
     * 抛 CodeEE，不编造状态。
     */
    override val consoleStyle: ConsoleStyleSnapshot? get() = console?.let { consoleStyleOf(it) }

    /**
     * HTML_GETPRINTEDSTR / GETDISPLAYLINE 的数据源（HOST-02）。
     *
     * 原版两者都直接读 `EmueraConsole.DisplayLineList`（EmueraConsole.cs:238）：
     * GetDisplayLines 从最新一行往回数逻辑行（:3546-3576），GETDISPLAYLINE 按显示行
     * 下标取 `ConsoleDisplayLine.ToString()`（FunctionMethodCreator.cs:8415-8440）。
     * 本属性把 [ConsoleSink.displayedLines] 的显示行快照映射为 [ConsolePrintedLine]
     * （文本 / 行体 HTML / 对齐 / 逻辑行标记，映射规则与出处见 fn/HtmlMethods.kt 的
     * `consolePrintedLine`），惰性视图：只在读取某一行时重建该行，不缓存。
     *
     * null = 无控制台、或该 sink 不追踪显示行（[ConsoleSink.displayedLines] 的缺省）
     * ⇒ 两个函数仍按「宿主未接线」抛 CodeEE。显示行含 REUSELASTLINE 的临时行：原版
     * 临时行同样在 displayLineList 里，直到下一次 addDisplayLine 才被删
     * （EmueraConsole.cs:2891-2893、:3053-3056）。
     */
    override val consoleLines: List<ConsolePrintedLine>?
        get() {
            val sink = console ?: return null
            val lines = sink.displayedLines ?: return null
            return printedLinesOf(lines)
        }

    /**
     * HTML_POPPRINTINGSTR 的数据源（HOST-02），**读取即消费**。
     *
     * 原版 `EmueraConsole.PopDisplayingLines`（EmueraConsole.cs:3578-3588）在打印缓冲
     * 非空时 `printBuffer.Flush(...)` 并返回排出的显示行，`Flush` 末尾 `clearBuffer()`
     * （PrintStringBuffer.cs:179-186、:273-278）⇒ 第一次读取后待决缓冲为空、第二次读到空表
     * （函数返回 ""，FunctionMethodCreator.cs:6123-6127）。弹出的行不进 displayLineList，
     * 所以不影响 [consoleLines]、LINECOUNT 与帧版本号。
     *
     * 副作用与既有口径的关系：POP 清空的是**待决打印缓冲**，因此之后
     * [lineIsEmpty]（LINEISEMPTY = `printBuffer.IsEmpty`，EmueraConsole.cs:505）变为 true ——
     * 这正是原版 Flush 的 clearBuffer 结果，不是新口径。
     *
     * null = 无控制台、或该 sink 不追踪打印缓冲（[ConsoleSink.popPrintingBuffer] 的缺省）
     * ⇒ 函数抛 CodeEE；空表 = 缓冲为空 ⇒ 函数返回 ""。
     */
    override val popPrintingLines: List<ConsolePrintedLine>?
        get() {
            val sink = console ?: return null
            val lines = sink.popPrintingBuffer ?: return null
            return printedLinesOf(lines)
        }

    /**
     * 显示行快照 → [ConsolePrintedLine] 的惰性视图（唯一映射在 fn/HtmlMethods.kt）。
     *
     * `defaultFontName` 取 sink 的注入项（`Config.FontName`）；sink 不追踪时依次回落到
     * 当前字体名与出厂缺省 —— 只影响行体 HTML 里 `<font face=...>` 的省略判断
     * （HtmlManager.cs:875），不改变文本与按钮结构。
     */
    private fun printedLinesOf(
        lines: List<LineBufferConsoleSink.DisplayLine>,
    ): List<ConsolePrintedLine> {
        val defFont = console?.defaultFontName
            ?: console?.fontName
            ?: LineBufferConsoleSink.DEFAULT_FONT_NAME
        return consolePrintedLines(lines, defFont)
    }

    /**
     * LINEISEMPTY 的数据源：原版 `EmptyLine => printBuffer.IsEmpty`
     * （EmueraConsole.cs:505；PrintStringBuffer.cs:39-48，flush 末尾 clearBuffer :273-278）。
     * 即「**待决打印缓冲**是否为空」，不是「上一显示行是否为空」；沿用开局装置既有口径，
     * 不按自然语义改写。无控制台 = 未接线 → false（[BuiltinHost.lineIsEmpty] 的缺省口径）。
     */
    override val lineIsEmpty: Boolean get() = console?.printingBufferEmpty ?: false

    /**
     * 文本框状态（GETTEXTBOX / SETTEXTBOX / MOVETEXTBOX / RESUMETEXTBOX）。
     * 原版挂在 `EmueraConsole.Window.TextBox` 上（FunctionMethodCreator.cs:8342-8371），
     * 是控制台侧的纯展示状态；headless 装置也应有同一份内存态，否则 `SETTEXTBOX`
     * 在开局路径（qol_MAP.ERB:626）直接抛「宿主未接线」。
     */
    override val textBox: TextBoxHost = ConsoleTextBox()

    /**
     * 配置覆盖表转发自 [delegate]（自 M4-K 起是**三层链 default→emuera→fixed 的合并快照**，
     * 键取 `EngText` 的大写英文形，AConfigItem.cs:22-23；合并口径见
     * `EmueraConfigParser.toOverrideMap`）。同 [consoleStyle]：带默认实现的接口成员必须显式覆写，
     * 否则会在委托对象上求值。
     *
     * 钉位（`fixed` 层）表达不了是这张表的固有损失，但本类同时把 [ConfigChainSource] 委托给
     * [delegate]，所以 `ConfigMethods.storeFromHost` 对本宿主走**优先通道**（三层重放），
     * 钉位不丢 —— 缺了那条委托才会退回这张值快照。
     */
    override val configOverrides: Map<String, String> get() = delegate.configOverrides

    // ---- M11-A L1 独有余项：HOST-03 音频四件套（跳过态与显示行两面已由上游 Q1 落地）----

    /** 原版 `GlobalStatic.Sound.Length`（GlobalStatic.cs:42）；未接池 = 0（基准 SOUND-MISSING 行口径）。 */
    override val soundChannels: Int
        get() = audioStore?.let { soundChannelTable(it.aux).channelCount } ?: 0

    /** spec: FunctionMethodCreator.cs:8866-8870 —— 「已建 且 在播」才真，不惰性建道。 */
    override fun isPlayingSound(channel: Int): Boolean =
        audioStore?.let { soundChannelTable(it.aux).isPlayingSound(channel) } ?: false

    /** spec: FunctionMethodCreator.cs:8960-8964 —— 同上；Bgm 侧只判 null 不建。 */
    override fun isPlayingBgm(): Boolean =
        audioStore?.let { soundChannelTable(it.aux).isPlayingBgm() } ?: false

    /**
     * GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL 的函数面（AudioMethods.kt:291-293
     * 读不到就抛「音频宿主尚未接线」）。视图包装与上面三项取**同一张表**（同一个 aux 槽），
     * 未接池 = null = 未装配形态，不伪造静音状态。
     */
    override val audio: AudioHost?
        get() = audioStore?.let { ChannelAudioHost(soundChannelTable(it.aux)) }
}

/**
 * 控制台文本框的内存态：文本 + 位置（x, y, width）。原版 `Window.TextBox` 的
 * `ChangeTextBox` / `SetTextBoxPos` / `ResetTextBoxPos`（FunctionMethodCreator.cs:3079-3108、
 * :8357-8371）只改这三类状态；绘制归宿主，脚本语义只需可读写。
 */
class ConsoleTextBox : TextBoxHost {

    /**
     * `@Volatile`：写方是 UI 线程（输入框文本镜像，`MainActivity.submitInput` / `InputBar`），
     * 读方是 VM 线程（`GETTEXTBOX` → `FunctionMethodCreator.cs:8351-8354`）。原版两边都在
     * UI 线程上摸同一个 `RichTextBox`，没有可见性问题；移植侧必须显式声明跨线程可见。
     */
    @Volatile
    override var text: String = ""
        private set

    var x: Int = 0
        private set
    var y: Int = 0
        private set
    var width: Int = 0
        private set

    override fun setText(text: String) {
        this.text = text
    }

    override fun moveTextBox(x: Int, y: Int, width: Int) {
        this.x = x
        this.y = y
        this.width = width
    }

    override fun resetTextBoxPos() {
        x = 0
        y = 0
        width = 0
    }
}

/**
 * [FileOps] 的写闸装饰：读 / 存在 / 枚举原样透传，写按 [writable] 二态。
 *
 * 缺省（[writable] = null）拒绝写并计数，与 [RootedFileOps.writeText] 的失败口径一致；
 * 语句层据此让 SAVETEXT 返回 0（FunctionMethodCreator.cs:8012-8014），不制造「写成功」
 * 的假推进。HOST-05 起可注入放行目标（[ConsoleBuiltinHost.writeRoot] 注入
 * [RootedFileOps]）：放行后写边界由目标自身保证 —— [RootedFileOps] 的根内判定
 * （BootHosts.kt 的 resolvePath）拒绝一切出根路径，与读路径同源，不逃逸。
 *
 * ## 二进制写走**同一道闸**（M11-E hostwire，判据②）
 *
 * [BinaryFileOps]（GSAVE 的真 PNG 通道，`core/host/GraphicsBuffer.kt`）同样按
 * [writable] 二态：null ⇒ `blockedWrites++` 且 false（与文本共用同一个计数器，
 * **不另开旁路、不另设计数**）；非 null ⇒ 转发给放行目标。放行目标是
 * [RootedFileOps]（[ConsoleBuiltinHost.writeRoot] 的唯一生产注入形态），它实现
 * [BinaryFileOps]，转发即达真落盘；GSAVE 在此之后拿到的字节与
 * `GraphicsBuffer.toSavePng()` 相等（判据③，用例 `BinaryFileOpsWiringTest`）。
 *
 * 失败口径同文本：false ⇒ GSAVE 折成返回 0（原版 `Bitmap.Save` 抛异常被 catch 折 0，
 * FunctionMethodCreator.cs:8122-8130）；[writable] 非 null 但**未实现**
 * [BinaryFileOps] 时按「宿主未接二进制写」返回 false（闸是开着的，不重复计
 * [blockedWrites]）—— 该情形下 GSAVE 也**不再回落**旧文本载体（函数层按
 * `ops as? BinaryFileOps` 已选中二进制分支），这是接线后的既定行为。
 *
 * @param delegate 读 / 存在 / 枚举的透传目标（开局面恒为 [RootedFileOps]`(gameRoot)`）。
 * @param writable 写请求（文本与二进制）的放行目标；null = 维持拒写口径（缺省行为不变）。
 *
 * ## 目录判定能力位必须一起转发（R5）
 *
 * [isDirectoryOf]（`fn/HostMethods.kt`）用 `ops as? DirectoryProbeFileOps` 选目录判定通道
 * （`Directory.Exists`，FunctionMethodCreator.cs:355），命中才拿 `isDirectory`，否则落
 * 「`list` 非空」兜底。本装饰器是生产 `fileOps` 的**唯一**出口（[ConsoleBuiltinHost.fileOps]
 * 恒返回本实例），而 delegate [RootedFileOps] 实现了该能力位 —— 装饰器若不实现接口，
 * `as?` 在装饰后恒落空 ⇒ **空目录**被兜底误判为非目录：
 * `EXISTFILE("空目录")` 返回 1（应 0，:1918 `File.Exists(目录)=False`）、
 * `ENUMFILES("空目录")` 返回 -1（应 0，:354-358 `Directory.Exists(目录)=True`）。
 * 故这里显式实现并优先转发 delegate 的能力位（未实现时回落 [FileOps.list] 兜底，不制造新口径）。
 */
class WriteBlockedFileOps(
    private val delegate: FileOps,
    private val writable: FileOps? = null,
) : DirectoryProbeFileOps, BinaryFileOps {

    /** 被拒绝的写请求次数（开局报告读数；放行态下写不经过这里，恒为注入前的拒写数）。 */
    var blockedWrites: Int = 0
        private set

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun readText(path: String): String? = delegate.readText(path)

    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        val target = writable
        if (target == null) {
            blockedWrites++
            return false
        }
        return target.writeText(path, text, append)
    }

    /**
     * 二进制覆盖写（GSAVE 真 PNG 通道）：闸与 [writeText] 同一道 ——
     * `writable == null` ⇒ 计数 + false（FunctionMethodCreator.cs:8122-8130 的失败
     * 由函数层折成 0）；非 null ⇒ 转发（放行目标需实现 [BinaryFileOps]，生产形态
     * [RootedFileOps] 满足；不满足时按「未接二进制写」false，不计数）。
     */
    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val target = writable
        if (target == null) {
            blockedWrites++
            return false
        }
        return (target as? BinaryFileOps)?.writeBytes(path, bytes) ?: false
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> =
        delegate.list(dir, pattern, recursive)

    /**
     * 目录判定能力位转发（`Directory.Exists`，FunctionMethodCreator.cs:355）。
     *
     * delegate 实现了 [DirectoryProbeFileOps] 就用它的 `isDirectory`（生产 delegate
     * [RootedFileOps] 走这条）；未实现时按 [isDirectoryOf] 的同一兜底口径
     * `list` 非空回落 —— 与装饰前的宿主行为逐位相同，不新造判据。
     */
    override fun isDirectory(path: String): Boolean =
        (delegate as? DirectoryProbeFileOps)?.isDirectory(path)
            ?: runCatching { delegate.list(path, "*", false).isNotEmpty() }.getOrDefault(false)
}
