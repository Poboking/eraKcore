package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigChainLoad
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.config.ConfigDebugLoad
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.lexer.lexerMacroNamesOf
import com.eratw.port.core.lexer.lexerMacrosOf
import com.eratw.port.core.vars.VariableStore
import java.time.ZoneId
import java.util.TreeMap

/**
 * 表达式内建方法层。对应原版 `GameData.Function`（FunctionMethodCreator.cs 注册表 +
 * 各 FunctionMethod 实现），权威清单为 `specs/builtin-functions.yaml`（271 个）。
 *
 * 三层结构：
 * - [Builtins.registry]：名字 → [BuiltinMethod]（OrdinalIgnoreCase，对应 `Config.StrComper`）。
 * - [BuiltinContext]：求值环境（EvalContext + 变量池 + 宿主服务）。
 * - [BuiltinEvalContext]：把 EvalContext 的 `callFunction` 回调接到注册表的桥。
 *
 * 用户 #FUNCTION 的遮蔽优先级已查证（IdentifierDictionary.cs:548-586：用户 #FUNCTION
 * 覆盖同名内建，普通 @label 不覆盖）——桥在查内建表之前先查用户函数表。
 */

/**
 * 一段文本的**带样式**量宽请求（FN-04 core）。
 *
 * 对应原版 `ConsoleStyledString` 量宽时握着的三件套：文本、`StringStyle`（字体名 + 样式位，
 * StringStyle.cs:20-40）与 `Config.FontSize`（ConfigData.cs:66）。原版按
 * `FontFactory.GetFont(fontName, fontSize, style)` 取字体后 GDI+ 实测（FontFactory.cs:9-50、
 * StringMeasure.cs:42-72）；移植侧把「名字/字号/样式位」原样交给宿主，
 * Android 实现见 `AndroidTextMeasure.measurePixels`。
 *
 * [styleMask] 位序 = 脚本 `FONTSTYLE` 口径：1 粗体、2 斜体、4 删除线、8 下划线
 * （Process.cs:1508-1527；与 HtmlPrintStyle.styleBits、LineBufferConsoleSink 同源）。
 * [charSpace] 是每**码位**附加的像素（0 = 整串一次度量、保留 kerning）。
 */
data class TextMeasureRequest(
    val text: String,
    /** 段字体名；空串 = 原版缺省族（`Config.FontName`，StringStyle.cs:26-31）。 */
    val fontName: String,
    /** 字号（像素，原版 `GraphicsUnit.Pixel`）。 */
    val fontSize: Long,
    /** 脚本样式位（1/2/4/8，见类 KDoc）。 */
    val styleMask: Int,
    /** 每码位附加像素；0 = 不附加。 */
    val charSpace: Long = 0L,
)

/** 宿主服务注入点：时间、配置、控制台状态等 M3+ 能力由宿主提供。 */
interface BuiltinHost {
    /** 当前时刻（epoch 毫秒），GETTIME 家族用。 */
    fun clockMillis(): Long = System.currentTimeMillis()

    /**
     * GETTIME 家族的**本地时区**注入点（与 [clockMillis] 同一条协议）。
     *
     * 原版 `DateTime.Now` 是 .NET 本地墙上时间（spec: `FunctionMethodCreator.cs:4008`），
     * 移植侧的「本地」从 JVM `ZoneId.systemDefault()` 读 —— 那是装置面输入，会从三处
     * 漏进可见状态：GETTIMES 文本、GETMILLISECOND/GETSECOND 数值、DT 行主键 ticks
     * （`MapDataTableMethods` 的 `nextRowId`）。E2E/L3 装置钉钟之后若不钉时区，
     * L3 状态哈希链从 #10 GOLDEN_XMLMAP（DT ticks 写进 RESULT:1 起的格）起仍跨环境漂移
     * （2026-10-22 实测：同一台机 Asia/Shanghai 与 America/New_York 两轮链尾不同）。
     * 缺省 = `ZoneId.systemDefault()`（产线语义不变）；装置侧覆写钉 UTC
     * （`GoldenPipeline` 的 `E2E_PINNED_ZONE_ID`）。
     */
    fun zoneId(): ZoneId = ZoneId.systemDefault()

    /** 货币标签（MONEYSTR）。spec: Config.MoneyLabel */
    val moneyLabel: String get() = "$"

    /** 货币标签是否在数值前（MONEYSTR）。spec: Config.MoneyFirst */
    val moneyLabelFirst: Boolean get() = true

    /** 存档槽数量（SAVENOS）。spec: Config.SaveDataNos */
    val saveDataNos: Int get() = 20

    /**
     * 当前**打印缓冲**（未 flush 的待决内容）是否为空（LINEISEMPTY）。
     * 语义不是"上一显示行是否为空"：任何 PRINTL / 换行 flush 之后即为空（→ 1）。
     * spec: EmueraConsole.cs:505 `EmptyLine => printBuffer.IsEmpty`；
     * PrintStringBuffer.cs:39-48 IsEmpty（buttonList/builder/stringList 三空），
     * clearBuffer()（:273-278）在 Flush 末尾调用 → flush 后必空。
     * 接了 [consoleSink] 时直接读 sink 的活状态（`printingBufferEmpty`，同一份缓冲，
     * 不再有第二处记账）；两者都没接 = 宿主未接线 → false。
     */
    val lineIsEmpty: Boolean get() = consoleSink?.printingBufferEmpty ?: false

    /**
     * 控制台宿主（生产实现 = `core/host/LineBufferConsoleSink`）。
     *
     * 这是**样式与缓冲状态的唯一来源**：原版的 `Config.ForeColor`/`bgColor`/`StringStyle`/
     * `alignment`/`redraw` 全部挂在 EmueraConsole 这一个对象上
     * （spec: EmueraConsole.cs:200、:207、:455、:491、:493-503），语句写它、函数读它，
     * 中间没有第二份拷贝。所以 [consoleStyle] 与 [lineIsEmpty] 都从这里的读出侧派生
     * （读出侧清单见 `core/host/ConsoleSink.kt` 的「样式读出侧」一节）。
     *
     * null = 宿主没有控制台（erbcc 自检、纯求值路径）→ 样式函数抛 CodeEE，不编造状态。
     */
    val consoleSink: ConsoleSink? get() = null

    /**
     * 控制台当前样式快照（GETCOLOR 家族）。null = 宿主未接线 → 函数抛 CodeEE。
     *
     * 缺省实现**从 [consoleSink] 的活状态派生**（[consoleStyleOf]），本属性不是状态存储：
     * 每次读都现取现算，SETCOLOR/SETFONT/ALIGNMENT/REDRAW 之后立刻能读到新值。
     * 自己另存一份样式字段 = 第二真相，与打印路径必然分叉（M4-D 的 PRINTN 并入规则事故）。
     * spec: FunctionMethodCreator.cs:3648-3745（GetColorMethod/GetBGColorMethod/
     * GetFocusColorMethod/GetStyleMethod/GetFontMethod），Console.StringStyle / Config。
     */
    val consoleStyle: ConsoleStyleSnapshot? get() = consoleSink?.let { consoleStyleOf(it) }

    /**
     * 控制台显示行缓冲（HTML_GETPRINTEDSTR / GETDISPLAYLINE 的数据源）。
     *
     * 顺序 = 打印顺序（最旧在前），与原版 `EmueraConsole.displayLineList` 一致
     * （EmueraConsole.cs:238）；元素模型见 [ConsolePrintedLine]。
     * 注意 GETDISPLAYLINE 按**显示行**下标取（含折行片段），而 HTML_GETPRINTEDSTR 按
     * **逻辑行**从最新往回数（EmueraConsole.cs:3546-3576）。
     *
     * null = 宿主未接线 → 这两个函数抛 CodeEE（不静默返回空串，避免基准里悄悄少一行）。
     *
     * **接线状态（M11-A L1 / HOST-02，上游 `1e5c0154`）**：生产宿主
     * [com.eratw.port.core.proc.ConsoleBuiltinHost] 直接投影
     * `LineBufferConsoleSink.lines()` —— 行表本来就住在那里（打印管线每次 flush 落行），
     * 本属性不是第二份记账。原版 `displayLineList` 里既含折行续行也含 REUSELASTLINE 的
     * 临时行（`addDisplayLine` 的 `displayLineList.Add`，EmueraConsole.cs:2969；临时行同一条
     * 路径 :3053-3056 → :3152-3165），故生产实现不过滤临时行。
     */
    val consoleLines: List<ConsolePrintedLine>? get() = null

    /**
     * 「正在打印中」的显示行（HTML_POPPRINTINGSTR 的数据源）。
     *
     * 对应原版 `EmueraConsole.PopDisplayingLines`（EmueraConsole.cs:3578-3588）：
     * 取当前打印缓冲并 **flush 清空**。故本属性的 getter **读取即消费**（有副作用），
     * 实现方在返回后必须清空自己的打印缓冲。
     *
     * null = 宿主未接线 → 函数抛 CodeEE；空表 = 缓冲为空 → 函数返回 ""
     * （原版 PopDisplayingLines 在缓冲空/控制台未启用时返回 null，函数转成 ""，
     * FunctionMethodCreator.cs:6123-6127）。
     *
     * **接线状态（M11-A L1 / HOST-02，上游 `1e5c0154`）**：生产宿主委托
     * `LineBufferConsoleSink.popDisplayingLines()`（排版与提交路径同源、只不落行列表），
     * 弹完 `LINEISEMPTY` 回到 1。
     */
    val popPrintingLines: List<ConsolePrintedLine>? get() = null

    /**
     * 文本显示宽度测量（像素），HTML_STRINGLEN / HTML_SUBSTRING / HTML_STRINGLINES 用。
     *
     * 原版 = `StringMeasure.GetDisplayLength`（StringMeasure.cs:39-66，GDI+
     * MeasureCharacterRanges 或 TextRenderer.MeasureText），结果依赖字体与字号。
     *
     * null = 宿主未接线 → 回落到内置等宽近似：全角 1 个 [fontSize]、其余半个
     * （见 HtmlMethods.kt 的 approxTextWidth）。默认 FontSize=18 时 ASCII = 9 px，
     * 与基准 `HTML_STRINGLEN("<i>AB</i>CD") = 4`（宽度 36）一致。
     */
    val measureText: ((String) -> Long)? get() = null

    /**
     * 带样式的**分段**文本量宽（像素，FN-04 core）。
     *
     * 原版 `ConsoleStyledString.SetWidth` 按段的「字体名 + 字号 + 样式位」从 `FontFactory`
     * 取字体再量（ConsoleStyledString.cs:75-95、StringMeasure.cs:42-72、FontFactory.cs:9-50），
     * 粗体/斜体与 `<font face>` 都会改变读数；[measureText] 只吃纯文本，故在它之外补这条
     * 通道。`HtmlStrictParser` 在每个文本段收口时按**当前样式快照**组装 [TextMeasureRequest]
     * （见 HtmlMethods.kt 的 `measureText`）。
     *
     * null = 宿主未接线 → 调用方回落到 [measureText]，再回落到内建等宽近似；因此旧宿主
     * 与既有基准读数零变化。生产实现是 Android 侧的 `AndroidTextMeasure.measurePixels`
     * （字体族 + styleMask + charSpace），经 `GameSession` → `ConsoleBuiltinHost` 注入。
     *
     * 注意：`BuiltinHost by delegate` 的包装类必须显式转发本方法（Kotlin 委托虽然会搬运
     * 带默认实现的接口成员，但显式覆写缺省形参会把委托结果遮成 null —— 见 ConsoleBuiltinHost）。
     */
    fun measureTextRun(request: TextMeasureRequest): Long? = null

    /**
     * 字体大小（`Config.FontSize`）。spec: ConfigData.cs:66（默认 18）。
     *
     * 只用于 HTML 家族的宽度↔字数换算：`HTML_STRINGLEN` 的 `2*width/FontSize`
     * （FunctionMethodCreator.cs:60-63）与 `HTML_SUBSTRING` 的预算换算
     * `length*FontSize/2`（HtmlManager.cs:250）。必须为正。
     *
     * **这条就是「脚本 px」那把尺本身**，不只是换算常数：[measureTextRun] 的请求字号
     * 也取它（`HtmlMethods.kt:556`）。语料那层的指令按钮网格按
     * `PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2` 算一格的像素宽、再拿
     * `HTML_STRINGLEN(…,1)` 的读数去比、把差值原样写成 `<shape param='Npx'>` 补回来
     * （`ERB/魔改内容/PRINT系/HTML_PRINT_Components.ERB` 的 `HTML_PRINTC_MANAGER`）——
     * 三者必须同尺，网格才闭得拢。
     *
     * 生产覆写：[com.eratw.port.core.proc.ConsoleBuiltinHost] 由会话按**生效配置字号**喂
     * （`GameSession.scriptFontPixelSize`）。**不许喂真机 page-fit 实测字号**：那等于
     * 量宽用 40、排格用 18，每个按钮只占应得宽度的一半上下，一行十几个就整排错位
     * （2026-10-29 玩家反馈的指令列表错位；账见 GameSession 侧同名 KDoc）。
     */
    val fontSize: Long get() = DEFAULT_FONT_SIZE

    /**
     * 输入后端快照（GETKEY / MOUSE 系 / ISACTIVE 家族的数据源）。null = 宿主未接线 →
     * 相关函数抛 CodeEE（不静默答 0）。类型见 core/fn/InputMethods.kt 的 [InputState]。
     */
    val inputState: InputState? get() = null

    /**
     * 跳过状态快照（ISSKIP/MESSKIP/FLOWINPUT* 家族的数据源）。
     * 原版无输入后端时同样无从判断，故 null → 抛 CodeEE。类型见 [SkipState]。
     *
     * **接线状态（HOST-01，上游 `d6beb31b`）**：函数侧不读本成员 ——
     * `ISSKIP`/`MESSKIP`/`MOUSESKIP` 现读每 VM aux 的 `skipState` 槽
     * （`InputMethods.kt:71` 的 `SKIP_STATE_AUX_SLOT` + `:230` 的 `skipStateSlotOf`），
     * sem 层 `ExecContext.skipPrint` / `mesSkip` 读写的是同一只槽
     * （`ExecContext.kt:186-201`）⇒ 不存在「宿主答 false、语句答 true」的两套读数。
     * M11-A 曾拟的 `ConsoleBuiltinHost.skipStateProvider` 闭包与之同责，合流时取上游形态。
     * 这里**不许有默认值**：`SKIPDISP` 进行中查 `ISSKIP` 必须得 1
     * （FunctionMethodCreator.cs:3611-3614 读 `Process.SkipPrint`），静默答 false 会歪曲
     * 所有走跳过分支的基准读数。
     */
    val skipState: SkipState? get() = null

    /**
     * 文件/文本读写宿主（SAVETEXT 家族，A9 消费）。null = 宿主未接线 → 函数抛 CodeEE。
     * 接口由 A9 新建（core/host/FileOps.kt），此处仅引用其 FQCN 与默认实现。
     */
    val fileOps: FileOps? get() = null

    /**
     * 角色/变量存档目录（原版 `Program.DatDir`）。
     *
     * `FIND_CHARADATA` / `CHKCHARADATA` 的路径口径（VariableEvaluator.cs:2044-2047、:2076-2102）：
     * `{DatDir}chara_{name}.dat`。原版 DatDir 以目录分隔符结尾；本属性按**不带尾分隔符**的
     * 目录名给出（默认 `dat`，对应原版默认的 ExeDir/dat），函数层用 `/` 拼接，
     * 由 [fileOps] / [readFileBytes] 的实现负责映射到平台路径。
     */
    val datDir: String get() = "dat"

    /**
     * 二进制读（存档内核：CHKDATA / CHKCHARADATA / 未来的 LOADDATA）。
     *
     * [FileOps] 只有文本读写，二进制存档必须走这里。**能力位看 [fileOps]，不看本方法**：
     * 实现了 [fileOps] 的宿主必须同时实现本方法（生产宿主 `RootedBuiltinHost` 按
     * `Files.isRegularFile` + `readAllBytes` 给字节，读不出即 null）。于是本方法的 null
     * 在存档探测链上按原版「打开/读取抛异常」处理 —— `CHKDATA` 落 `ETC_ERROR` + LoadError
     * （VariableEvaluator.cs:2219-2228），不再冒充宿主未接线；只有 [fileOps] 也缺失
     * （宿主完全没接文件能力）才算未接线，由调用方抛 CodeEE。
     * 现存不一致（只记不改，改法要动本接口）：`CharaFileMethods.kt:115-116` 仍把
     * `readFileBytes == null` 当「宿主未接线」抛 CodeEE。
     */
    fun readFileBytes(path: String): ByteArray? = null

    /**
     * 已安装字体名集合（CHKFONT）。
     *
     * 原版 = `new InstalledFontCollection().Families[].Name`（FunctionMethodCreator.cs:3488-3497），
     * 逐名**精确**比较（`families[i].Name == strValue`，区分大小写）。
     *
     * null = 宿主无点查能力（安卓没有「枚举系统字体」的 API，只能对单个族名点查）。
     * **null ≠ 无字体表**：判定权威是仓内字体表 [com.eratw.port.core.font.PortFonts]，
     * 本项只允许**追加**条目、不参与否决（`PortFonts.isAvailable` 的并集口径，
     * `ConsoleQueryMethods.kt` 的 CHKFONT）。旧的「[consoleStyle] 当前字体名自证为已安装」
     * 回落**已删**：原版答 1 是因为那个名字真在两张表里，不是因为「正在用它显示」。
     */
    val installedFonts: Set<String>? get() = null

    /**
     * 音频资源根目录（EXISTSOUND 的 `.\sound\`）。
     *
     * 原版 = `File.Exists(Path.GetFullPath(".\\sound\\" + strValue))`
     * （FunctionMethodCreator.cs:8214-8218）—— 以进程工作目录为基准的相对路径。
     *
     * null = 宿主未接线 → 回落到 `File("sound")`（等价于原版工作目录语义）。
     */
    val soundDir: java.io.File? get() = null

    /**
     * 存档槽头探测（CHKDATA 家族）。
     *
     * 返回 null = 宿主未接线 → 函数抛 CodeEE（不静默返回「不存在」）。
     * 原版：`VEvaluator.CheckData`（VariableEvaluator.cs:2138-2157）→ `CheckDataByFilename`
     * （:2159+）读存档头，未找到夹具 `State = FILENOTFOUND / DataMes = "----"`（:2162-2166），
     * 读成功则回描述文本（:2193）。
     *
     * @param no 存档槽号（已校验 0..Int.MAX_VALUE）。
     * @param kind 存档类别（NORMAL = `sav/save{no}.sav`）。
     */
    fun checkSaveData(no: Long, kind: SaveFileKind = SaveFileKind.NORMAL): SaveDataCheck? = null

    /**
     * 客户端窗口宽度（CLIENTWIDTH）。原版语义 = EmueraConsole.ClientWidth = MainPicBox.Width，
     * 未接线时回落到原版默认窗口宽度 Config.WindowX。
     * spec: EmueraConsole.cs:232 / MainWindow.cs:726 / ConfigData.cs:56（WindowX 默认 760）。
     *
     * 生产覆写：[com.eratw.port.core.proc.ConsoleBuiltinHost] 构造注入（GFX-06）——
     * 单位是**像素**，与 [com.eratw.port.core.host.LineBufferConsoleSink] 的
     * clientWidth（半角格）不是同一个量；真机喂内容区像素宽，headless 缺省 760。
     */
    val clientWidth: Long get() = DEFAULT_CLIENT_WIDTH

    /**
     * 客户端窗口高度（CLIENTHEIGHT）。原版语义 = EmueraConsole.ClientHeight = MainPicBox.Height
     * = Config.WindowY - Config.LineHeight（菜单条高度在公式中相互抵消）。
     * spec: EmueraConsole.cs:234 / MainWindow.cs:726 / ConfigData.cs:57（WindowY 默认 480）,
     * :67（LineHeight 默认 19）。
     */
    val clientHeight: Long get() = DEFAULT_CLIENT_HEIGHT

    /** 每行 PRINTC 数（PRINTCPERLINE）。原版语义 = Config.PrintCPerLine。
     * spec: FunctionMethodCreator.cs:3922-3934 / ConfigData.cs:63（默认 3）。 */
    val printCPerLine: Long get() = DEFAULT_PRINT_C_PER_LINE

    /** PRINTC 每项的字符宽度（PRINTCLENGTH）。原版语义 = Config.PrintCLength。
     * spec: FunctionMethodCreator.cs:3937-3950 / ConfigData.cs:64（默认 25）。 */
    val printCLength: Long get() = DEFAULT_PRINT_C_LENGTH

    /**
     * 声音通道数（`GlobalStatic.Sound.Length`）。0 = 宿主没有音频后端。
     *
     * 原版该长度恒为 10（GlobalStatic.cs:42 `new Sound[10]`），元素可空（首次访问才
     * `new Sound()`，惰性建的六个调用点见 core/audio/SoundChannelTable.kt 的类头）。
     * 越界判定用它做：ISPLAYINGSOUND 返回 -1（FunctionMethodCreator.cs:8849-8852）、
     * SOUNDCONTROL 返回 -1（:8908-8911）、GETSOUNDORBGMINFO 返回 0（:8797-8808）。
     *
     * 移植侧缺省 0 是**有意的**：基准里 SOUND 族是 MISSING 行
     * （fixtures/golden/baseline/pc-golden-root.txt:519-522 只有 `EXISTSOUND=0`、
     * `ISPLAYINGBGM=0 ISPLAYINGSOUND=-1`），把**接口缺省**改成 10 会让这三条函数凭空多出读数，
     * 等于伪造「已支持」（CONTRACT-M4-D §2 d-audio 红线）。
     *
     * **接线状态（M11-A L1 / HOST-03）**：接口缺省不变，**生产宿主已落** ——
     * `ConsoleBuiltinHost` 的四项（本成员 + [isPlayingSound] + [isPlayingBgm] + [audio]）
     * 同源取 `store.aux` 的 `audio.channels`（[com.eratw.port.core.audio.soundChannelTable]
     * 按 `AUDIO_CHANNEL_SLOT` 建的那张 `SoundChannelTable`，长度 = 表自身而不是常量 10），与语句层
     * `TableSoundSink` 同一张表。相容性：接上表后 `ISPLAYINGSOUND(0)` 仍读 -1 ——
     * 原版的 -1 来自「`Sound[0]` 还没被建出来」（FunctionMethodCreator.cs:8866-8870 的
     * null 分支），而不是「没有后端」（pc-golden-root.txt:521）。
     */
    val soundChannels: Int get() = 0

    /**
     * 声道 [channel] 是否正在播放。spec: FunctionMethodCreator.cs:8866-8870 ——
     * `GlobalStatic.Sound[num] != null && Sound[num].isPlaying()`；**不**惰性建道
     * （对照 GETSOUNDORBGMINFO 的 :8799-8802）。
     *
     * `isPlaying()` = `Playing && !paused`（Sound.cs:171-178），所以**暂停中的声道读不到**。
     * 接线时委托 [com.eratw.port.core.audio.SoundChannelTable.isPlayingSound]。
     */
    fun isPlayingSound(channel: Int): Boolean = false

    /**
     * BGM 是否正在播放。spec: FunctionMethodCreator.cs:8960-8964 ——
     * `GlobalStatic.Bgm != null && Bgm.isPlaying()`，同样**不**惰性建。
     * 接线时委托 [com.eratw.port.core.audio.SoundChannelTable.isPlayingBgm]。
     */
    fun isPlayingBgm(): Boolean = false

    /**
     * 用户函数目录：名字（**大写**）→ 种类（[UserFunctionKind]）。
     *
     * EXISTFUNCTION / EXISTMETH / GETMETH / GETMETHS / ENUMFUNC* 的数据源，
     * 对应原版 `Process.LabelDictionary` 的用户标签表
     * （`IdentifierDictionary.GetFunctionMethod`，IdentifierDictionary.cs:538-586；
     * `NoneventKeys` 见 EnumNameMethod，FunctionMethodCreator.cs:280）。
     *
     * null = 宿主自身未接线（无 ERB 装载）。**生产链路不会落到 null**：
     * [com.eratw.port.core.proc.ProcessRuntime] 在装配处用函数表投影出全量用户函数目录
     * （事件与编译器合成节剔除；见其 `userFunctionKinds`），仅纯求值语境（单测直接
     * 构造 [BuiltinEvalContext]）才会读到这里。旧口径的 null 回落语义逐函数：
     * - EXISTFUNCTION / EXISTMETH：一律按"用户函数不存在"返回 0（原版只认用户标签表，
     *   内建函数本来就不在 LabelDictionary 里）；
     * - GETMETH / GETMETHS：未接线但有默认值时返回默认值；否则抛 CodeEE；
     * - ENUMFUNC*：回落到**内建函数名**（`Builtins.registry.names()`，271 个），
     *   即"内建优先"近似——枚举的是内建方法表而非用户标签名。
     */
    val userFunctions: Map<String, UserFunctionKind>? get() = null

    /**
     * 用户函数调用器（GETMETH / GETMETHS 执行用，A5 消费）。
     *
     * 对应原版绑定实参后调用用户 #FUNCTION 函数体（FunctionMethodCreator.cs:8571/8613）。
     * 收到名字（大写）与已求值实参，返回函数结果（数值或字符串）；返回 null = 调用失败。
     *
     * null = 宿主未接线 → GETMETH/GETMETHS 抛 CodeEE（不静默返回默认值，
     * 避免把"没接解释器"伪装成"函数不存在"）。生产链路由
     * [com.eratw.port.core.proc.ProcessRuntime] 装配时接到 `invokeUserMethod`，不会为 null。
     */
    val invokeUserFunction: ((String, List<Value?>) -> Value?)? get() = null

    /**
     * `#DEFINE` 宏表（宏名 → 展开体原文），宿主可注入项。
     *
     * 数据源 = `IdentifierDictionary.macroDic`（`ErhLoader.cs:170/246` 的两个 AddMacro 调用点）。
     * 缺省空表 = 未接线；生产装配不靠宿主实现本项：[BuiltinEvalContext] 会把
     * `store.aux` 的 `lexer.macros` 槽（[com.eratw.port.core.lexer.installLexerMacros] 写入）
     * 合成进来，宿主显式覆写时以宿主为准。
     */
    val macros: Map<String, String> get() = emptyMap()

    /**
     * #DEFINE 宏名集合（ISDEFINED / ENUMMACRO* 的数据源，A5 消费）。
     *
     * 对应原版 `IdentifierDictionary.MacroKeys`（macroDic.Values.Keyword，
     * IdentifierDictionary.cs:66,70）。原版 GetMacro 按 Config.IgnoreCase
     * （默认 true，Config.cs:299-303）匹配，即大小写不敏感。
     *
     * 缺省从 [macros] 派生：非空宏表 ⇒ 键集；空表 ⇒ null（= 宿主未接线）。生产链路
     * 由 [BuiltinEvalContext] 用 `store.aux` 的宏表补齐（宏体/宏名同源，见 [macros]），
     * 因此装配期只需把会话宏表挂进 aux，不必再实现第二只宿主。
     */
    val macroNames: Set<String>? get() = macros.keys.takeIf { macros.isNotEmpty() }

    /**
     * 变量名全集（ENUMVAR* 的数据源，A5 消费）。
     *
     * 对应原版 `IdentifierDictionary.VarKeys`（varTokenDic.Keys，:68）——系统变量
     * 全集 + 文件级 #DIM 全局变量（AddUseDefinedVariable，:394-399）。
     *
     * null = 宿主未接线 → 回落变量池可见名（`VariableStore.registry.all()` +
     * `store.allUserVars()`）。回落表只有系统变量（约 160 个）与用户 #DIM 全局，
     * 与 PC 生产版的差别在"未装载的游戏 ERH #DIM"；装载后自动收敛。
     */
    val variableNames: Collection<String>? get() = null

    /**
     * 当前执行中的函数名（GETDOINGFUNCTION 的数据源，A5 消费）。
     *
     * 对应原版 `Process.GetScaningLine().ParentLabelLine.LabelName`
     * （FunctionMethodCreator.cs:8452-8457）；不在函数内（无扫描行）时原版返回 ""。
     *
     * null = 宿主未接线 → 函数返回 ""（与原版"无扫描行"分支一致，不是抛错）。
     */
    val doingFunction: String? get() = null

    /**
     * 配置覆盖值（GETCONFIG / GETCONFIGS 的 emuera.config / _Replace.csv / debug.config 层）。
     *
     * 键按原版规则匹配：先 `ToUpperInvariant`，再与 `Name`（enum 名）/ `Text` / `EngText`
     * 比较（config→replace→debug 顺序，spec: ConfigData.cs:236-340）。注意 Name 分支因
     * 大小写混合而**永远命中不了**——这是原版缺陷，函数层照抄。值按 `ConfigItem.TryParse`
     * 解析，非法值按原版 ConfigWarn 口径保留默认（ConfigData.cs:563-586）。
     *
     * 未映射到宿主的项保持 ConfigData.cs:52-220 的出厂默认；WindowX / WindowY / FontSize /
     * FontName / 颜色 / MoneyLabel 等可由宿主现有属性播种（见 DebugMiscMethods.kt 的 ConfigState）。
     */
    val configOverrides: Map<String, String> get() = emptyMap()

    /**
     * OUTPUTLOG 在 `hideInfo == false` 时写入的日志头（原版「環境情報 / 本体 / ログ」三段，
     * EmueraConsole.cs:3507-3538）。每项一行，按顺序写。
     *
     * 空表 = 宿主未提供环境头（移植版没有 Emuera 版本号与 GAMEBASE 标题上下文），
     * 此时只写显示行；不伪造版本信息。
     */
    val logHeaderLines: List<String> get() = emptyList()

    /**
     * 当前进程工作集字节数（GETMEMORYUSAGE）。
     * 原版 = `Process.GetCurrentProcess().WorkingSet64`（FunctionMethodCreator.cs:8307-8322）。
     *
     * null = 宿主未接线 → 函数抛 CodeEE（不静默返回 0）。
     */
    val memoryUsageBytes: Long? get() = null

    /**
     * 文本框后端（GETTEXTBOX / SETTEXTBOX / MOVETEXTBOX / RESUMETEXTBOX）。
     * 类型见 DebugMiscMethods.kt 的 [TextBoxHost]。
     *
     * null = 宿主未接线 → 四个函数抛 CodeEE。
     */
    val textBox: TextBoxHost? get() = null

    /**
     * 音频后端（GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL）。
     * 声道数仍由 [soundChannels] 给出；类型见 DebugMiscMethods.kt 的 [AudioHost]。
     *
     * null = 宿主未接线 → 三个函数抛 CodeEE（不伪造静音状态与 0 时长）。
     * 接口缺省仍然是 null —— M4-D 那条「不许把缺省塞成实现」的红线不变；
     * **生产装配已落**（M11-A L1 / HOST-03）：`GameSession` 把变量池递给
     * [com.eratw.port.core.proc.ConsoleBuiltinHost] 的 `audioStore`，四项现读该 aux 上的
     * 声道表。
     *
     * 接线口径（逐条都要同源，否则同一份声道状态会被读成两套）：
     * - 实现方用 [com.eratw.port.core.audio.ChannelAudioHost]（它把 core/audio 的声道状态机
     *   映射成 [AudioHost]），或让真实后端的 `AudioHost` 与 [com.eratw.port.core.host.SoundSink]
     *   共用同一张 [com.eratw.port.core.audio.SoundChannelTable]；
     * - [soundChannels] = 该表的 `channelCount`（原版 10，GlobalStatic.cs:42）；
     * - [isPlayingSound] / [isPlayingBgm] = 该表的同名查询（不惰性建道，:8866、:8960）；
     * - 语句层六条走 `ExecContext.sound`（原版和这三个函数动的是同一组 GlobalStatic 对象，
     *   FunctionIdentifier.cs:2608-2755 与 FunctionMethodCreator.cs:8769-9030）。
     */
    val audio: AudioHost? get() = null

    companion object {
        /** 无宿主时的最小实现（测试与纯函数场景）。 */
        val DEFAULT = object : BuiltinHost {}

        /** Config.WindowX 默认值（ConfigData.cs:56）。 */
        const val DEFAULT_CLIENT_WIDTH = 760L

        /** Config.WindowY(480) - Config.LineHeight(19)（ConfigData.cs:57,67 / MainWindow.cs:726）。 */
        const val DEFAULT_CLIENT_HEIGHT = 461L

        /** Config.PrintCPerLine 默认值（ConfigData.cs:63）。 */
        const val DEFAULT_PRINT_C_PER_LINE = 3L

        /** Config.PrintCLength 默认值（ConfigData.cs:64）。 */
        const val DEFAULT_PRINT_C_LENGTH = 25L

        /** Config.FontSize 默认值（ConfigData.cs:66）。HTML 家族的宽度↔字数换算用。 */
        const val DEFAULT_FONT_SIZE = 18L
    }
}

/**
 * 控制台当前样式快照（GETCOLOR 家族的宿主数据源）。
 *
 * 颜色分量均为 `[r, g, b]`（各 0-255）。返回编码 = `r shl 16 or g shl 8 or b`
 * （对应原版 `Color.ToArgb() & 0xFFFFFF`，spec: FunctionMethodCreator.cs:3662,3695,3677）。
 *
 * 「未设置时回落默认色」语义：原版 GETCOLOR/GETBGCOLOR 读 `Console.StringStyle.Color`/`bgColor`，
 * 而 `RESETCOLOR`/`RESETBGCOLOR` 会把这些值重置为 `Config.ForeColor`/`Config.BackColor`
 * （spec: FunctionMethodCreator.cs:3662 `defaultColor ? Config.ForeColor : StringStyle.Color`）。
 * 快照里用 `fore == null` / `back == null` 表示「当前未显式设置颜色」，函数层此时回落到
 * [defFore] / [defBack]（等价于 RESETCOLOR 之后的状态）。
 *
 * @property fore        当前前景色；null = 未显式设置 → GETCOLOR 回落 [defFore]。
 * @property back        当前背景色；null = 未显式设置 → GETBGCOLOR 回落 [defBack]。
 * @property defFore     默认前景色（Config.ForeColor）→ GETDEFCOLOR。
 * @property defBack     默认背景色（Config.BackColor）→ GETDEFBGCOLOR。
 * @property focus       焦点/选择色（Config.FocusColor）→ GETFOCUSCOLOR。
 * @property fontStyleMask 字体样式位掩码：Bold=1 | Italic=2 | Strikeout=4 | Underline=8
 *   （spec: FunctionMethodCreator.cs:3708-3729）→ GETSTYLE 原样返回。
 * @property fontName    当前字体名（Console.StringStyle.Fontname）→ GETFONT。
 * @property alignment   对齐方式：0=LEFT、1=CENTER、2=RIGHT
 *   （spec: DisplayLineAlignment.cs:5-7）→ CURRENTALIGN 映射成字符串。
 * @property redraw      重绘状态：**已折减的两态**（0=None / 1=Normal）。
 *   原版 `SetRedraw` 只把 bit0 存进 `ConsoleRedraw redraw`
 *   （spec: EmueraConsole.cs:1693-1707；`ConsoleRedraw` 枚举 None=0 / Normal=1，
 *   ConsoleRedraw.cs:6-10），bit1 只是「立即重绘」的副作用。
 *   CURRENTREDRAW = (redraw != None) ? 1 : 0
 *   （spec: FunctionMethodCreator.cs:3793-3806，取值在 :3804）。
 */
data class ConsoleStyleSnapshot(
    val fore: IntArray?, val back: IntArray?,
    val defFore: IntArray, val defBack: IntArray, val focus: IntArray,
    val fontStyleMask: Int, val fontName: String, val alignment: Int, val redraw: Int,
)

/**
 * 样式快照的**唯一装配处**：把控制台宿主 [sink] 的活状态翻译成 [ConsoleStyleSnapshot]。
 *
 * 原版没有"快照"这个类型，样式类函数直接读 EmueraConsole 对象上的字段
 * （`Console.StringStyle.Color` / `Console.bgColor` / `Console.StringStyle.FontStyle` /
 * `.Fontname` / `Console.Alignment` / `Console.Redraw`，spec 逐条见
 * `core/host/ConsoleSink.kt` 的「样式读出侧」注释块）。移植版把这些读数收在
 * [ConsoleSink] 的读出侧，本函数只做**一次**表示转换（`0xRRGGBB` → `[r,g,b]`、
 * `Alignment` → 0/1/2），不持任何状态：同一份状态在改前改后各取一次快照会得到不同结果，
 * 得到相同结果就说明有人又存了一份。
 *
 * 三个 `Config` 项（[defaultFore]/[defaultBack]/[focusColor]）走参数而不是走 sink：
 * 它们是**配置**（`RESETCOLOR`/`RESETBGCOLOR` 的落点，FunctionIdentifier.cs:1501、:1515；
 * GETCOLOR 家族读取分支 FunctionMethodCreator.cs:3662、:3677、:3695），原版由 `Config` 回答；
 * 默认值直接引用 `LineBufferConsoleSink` 里那份带 `ConfigData.cs:68-70` 出处的常量，
 * 不再抄第二张表。真机装载 emuera.config 后由调用方把这几项换成配置值。
 *
 * @param sink 控制台宿主（样式活状态的唯一来源）。
 * @param defaultFore `Config.ForeColor`（ConfigData.cs:68 = 192,192,192）。
 * @param defaultBack `Config.BackColor`（ConfigData.cs:69 = 0,0,0）。
 * @param focusColor `Config.FocusColor`（ConfigData.cs:70 = 255,255,0；
 *   基准取证 pc-golden-root.txt:266 `FOCUS=16776960`）。
 * @param defaultFontName `Config.FontName`（ConfigData.cs:65）——只在宿主不追踪字体名时兜底；
 *   原版 never 读到空值：`StringStyle` 构造里空字体名就归一成 `Config.FontName`
 *   （StringStyle.cs:26-31，EmueraConsole.cs:2826-2835 的 SETFONT 同一条规则）。
 */
fun consoleStyleOf(
    sink: ConsoleSink,
    defaultFore: IntArray = LineBufferConsoleSink.DEFAULT_FORE_COLOR.toRgbComponents(),
    defaultBack: IntArray = LineBufferConsoleSink.DEFAULT_BACK_COLOR.toRgbComponents(),
    focusColor: IntArray = LineBufferConsoleSink.DEFAULT_FOCUS_COLOR.toRgbComponents(),
    defaultFontName: String = LineBufferConsoleSink.DEFAULT_FONT_NAME,
): ConsoleStyleSnapshot = ConsoleStyleSnapshot(
    fore = sink.foregroundColor?.toRgbComponents(),
    back = sink.backgroundColor?.toRgbComponents(),
    defFore = defaultFore,
    defBack = defaultBack,
    focus = focusColor,
    fontStyleMask = sink.fontStyle,
    fontName = sink.fontName ?: defaultFontName,
    // DisplayLineAlignment.cs:3-8 的声明序（LEFT/CENTER/RIGHT = 0/1/2）；
    // ConsoleQueryMethods.kt:99-107 按同一编号映回字符串。
    alignment = when (sink.alignment) {
        Alignment.LEFT -> 0
        Alignment.CENTER -> 1
        Alignment.RIGHT -> 2
    },
    redraw = sink.redraw,
)

/**
 * `0xRRGGBB` → `[r, g, b]`（与 ConsoleQueryMethods.kt:35-38 的 `encodeRgb` 互逆；
 * 原版这一侧是 `Color.R/G/B`，读用同一份编码即不会与写侧分叉）。
 * `internal`：装配点只有一份，测试与 E2E 门面都该复用而不是再抄一次解码。
 */
internal fun Int.toRgbComponents(): IntArray =
    intArrayOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

/**
 * 用户函数种类（[BuiltinHost.userFunctions] 的值）。对应原版
 * `FunctionLabelLine.IsMethod` + `MethodType`（FunctionMethodCreator.cs:8269-8280）：
 * - [PLAIN]：普通 `@label`（非方法）→ EXISTFUNCTION 返回 1；
 *   GETMETH/GETMETHS 遇到它抛 CodeEE（CallfNonMethodFunc，IdentifierDictionary.cs:557-559），
 *   EXISTMETH 返回 0（该 CodeEE 被 ExistMethMethod 捕获，:8630-8637）。
 * - [INT_METHOD]：`#FUNCTION`（long）→ EXISTFUNCTION 返回 2、EXISTMETH 返回 1。
 * - [STRING_METHOD]：`#FUNCTIONS`（string）→ EXISTFUNCTION 返回 3、EXISTMETH 返回 2。
 */
enum class UserFunctionKind { PLAIN, INT_METHOD, STRING_METHOD }

/**
 * 内建函数求值环境。桥（[BuiltinEvalContext]）负责实现；纯函数只依赖
 * [variables]（经 EvalContext），变量池函数依赖 [store]，宿主函数依赖 [host]。
 */
interface BuiltinContext : EvalContext {
    val store: VariableStore
    val host: BuiltinHost

    /** 每 VM 一份的附属状态（XML/Map/DT/图形池）。见 [com.eratw.port.core.host.RuntimeAux]。 */
    val aux: com.eratw.port.core.host.RuntimeAux get() = store.aux

    /** 形式串/动态表达式解析用的解析期配置。 */
    val parserConfig: ParserConfig get() = ParserConfig()

    /**
     * 当前正在求值的脚本行（FN-05）：`Filename to LineNo`，行号 **0 基**
     * （`LogicalLine.lineNo` / `Program.lineOf(pc)` 同基）。
     *
     * 对应原版 `GlobalStatic.Process.getCurrentLine.Position`（FunctionMethodCreator.cs:5709-5712
     * 的 UNICODE 警告前缀）；文件名取装载期显示名（侧车 `fileByKey`）。
     * null = 通道未接线（内存编译/测试）⇒ 走 C# `getCurrentLine == null` 的无前缀回落
     * （:5713-5716）。
     */
    val currentScriptLine: Pair<String, Int>? get() = null
}

/**
 * 存档类别。spec: Runtime.Utils/EraSaveFileType.cs:3-9 ——
 * `Normal, Global, Var, CharVar`（序号即本枚举顺序）。
 */
enum class SaveFileKind { NORMAL, GLOBAL, VAR, CHARVAR }

/**
 * 存档探测状态。spec: Runtime.Utils/EraDataState.cs:3-10 ——
 * `OK, FILENOTFOUND, GAME_ERROR, VIRSION_ERROR, ETC_ERROR`（序号 0..4）。
 *
 * 原版 `CHKDATA` 直接把 `(long)State` 当返回值，故 [code] 就是函数结果。
 * `VIRSION_ERROR` 的拼写照抄原版，便于检索对照。
 */
enum class EraDataState(val code: Long) {
    OK(0), FILENOTFOUND(1), GAME_ERROR(2), VIRSION_ERROR(3), ETC_ERROR(4),
}

/**
 * 存档槽头探测结果（[BuiltinHost.checkSaveData] 的返回）。
 *
 * @property state 探测状态，见 [EraDataState]。
 * @property mes   描述文本；文件不存在时原版给 `"----"`（VariableEvaluator.cs:2165），
 *                 读出存档头后是存档描述（:2193）。
 */
data class SaveDataCheck(val state: EraDataState, val mes: String)

/**
 * 单个内建方法的描述。
 *
 * [minArgs]/[maxArgs] 为参数个数边界（maxArgs = -1 表示可变参数，对应原版
 * ArgType.Variadic）；被调用时由桥统一检查（对应原版 CheckArgumentType 的个数分支）。
 *
 * [omitStart] 对应原版 `ArgTypeList.OmitStart`（spec: FunctionMethod.cs:129-132）：
 * -1 = 该形态没有省略位（走 [minArgs]/[maxArgs] 的旧文案）；>= 0 时 **[omitStart] 就是
 * 最少实参个数**（原版 `arguments.Count >= OmitStart` 才过闸，spec: FunctionMethod.cs:183）。
 * 声明了它的函数还按原版两条**独立**分支报错（spec: FunctionMethod.cs:182-284）：
 * 多于槽位 → `参数过多`（TooManyFuncArgs，:279）；少于 omitStart → `至少需要N个参数`
 *（NotEnoughArgs，:283）。旧文案是 METHOD_ARG_TYPE 缺口（PcOracleGapTest 已登记），
 * 本字段只给对过原文的函数用。
 *
 * [eval] 是值求值入口（参数已求值为 [Value]）；[evalRef] 是引用求值入口（参数保持
 * [Expr] 形态，供 SUMARRAY/FINDCHARA 等需要"变量身份"的函数使用）。
 * 二者互斥：声明了 [evalRef] 的函数只能经引用路径调用（见接口变更提案/汇报）。
 */
class BuiltinMethod(
    val name: String,
    val retType: OperandType,
    val minArgs: Int = 0,
    val maxArgs: Int = 0,
    val omitStart: Int = -1,
    // 注意：eval 必须是最后一个参数——注册处大量使用尾随 lambda（如 BUILTIN(...) { ctx, args ->
    // ... }），Kotlin 尾随 lambda 绑定到最后一个参数；evalRef 由 VarMethods 用具名参数传入。
    val evalRef: ((BuiltinContext, List<Expr?>) -> Value)? = null,
    val eval: ((BuiltinContext, List<Value?>) -> Value)? = null,
) {
    /** 调用路径上的个数检查。spec: FunctionMethod.cs:174-293（CheckArgumentTypeEx 的个数/省略位分支） */
    fun checkArity(args: Int) {
        if (omitStart >= 0) {
            // 有省略位：个数下界 = OmitStart，上界 = 槽位数（spec: FunctionMethod.cs:182-283）
            if (maxArgs >= 0 && args > maxArgs) throw CodeEE("${name}函数: 参数过多")
            if (args < omitStart) throw CodeEE("${name}函数: 至少需要${omitStart}个参数")
            return
        }
        if (maxArgs >= 0) {
            if (args !in minArgs..maxArgs) {
                throw CodeEE("函数 $name 的参数个数不匹配（需要 $minArgs 个）：实际 $args 个")
            }
        } else if (args < minArgs) {
            throw CodeEE("函数 $name 的参数个数不足（至少 $minArgs 个）：实际 $args 个")
        }
    }
}

/**
 * 内建函数注册表。名字匹配 OrdinalIgnoreCase（对应 `Dictionary<string, FunctionMethod>(Config.StrComper)`，
 * spec: FunctionMethodCreator.cs:9042）。
 */
class BuiltinRegistry {
    // String.CASE_INSENSITIVE_ORDER 是逐字符大小写折叠，与 C# OrdinalIgnoreCase 等价（ASCII 域）
    private val map = TreeMap<String, BuiltinMethod>(String.CASE_INSENSITIVE_ORDER)

    /**
     * 精确名快查表（2026-10-23 真机性能点）。
     *
     * 编译产物里的方法名大小写已经固定，运行期绝大多数查询是**同一个字面串**反复查：
     * 走 [TreeMap] 的比较器是逐字符折叠（真机采样里 `String$CaseInsensitiveComparator`
     * 占世界初始化那页 2%）。这里按原样名字做一次 O(1) 缓存；未命中才回到大小写不敏感表。
     * 值只增不减（名字集合有限），与注册表同寿命。
     */
    private val exactCache = java.util.concurrent.ConcurrentHashMap<String, BuiltinMethod>()

    /**
     * 占位名单（[registerUnimplemented] 注册、尚未落地的能力）。
     *
     * 规则：占位可被真实现**覆盖**（[register] 命中占位时静默替换并移出名单）；
     * 真实现之间仍然禁止重名（`check(old == null)`）。这样各能力家族可以在自己的文件里
     * 注册，无需回头删 [registerUnimplementedAll] 的名单——名单只增不减也不会劣化行为，
     * 因为真实现总是后注册、总是胜出。
     */
    private val placeholders = HashSet<String>()

    fun register(method: BuiltinMethod) {
        exactCache.clear()
        val old = map.put(method.name, method)
        if (old == null) return
        if (placeholders.remove(method.name)) return
        // 真实现撞真实现：放回原值并报错（保持既有契约）
        map[method.name] = old
        error("内建函数重名注册：${method.name}")
    }

    fun registerUnimplemented(name: String, retType: OperandType, minArgs: Int, maxArgs: Int) {
        exactCache.clear()
        // 已被真实现占用的名字：占位让路（注册顺序无关，重复调用安全）
        if (map[name] != null && !placeholders.contains(name)) return
        map[name] = BuiltinMethod(name, retType, minArgs, maxArgs) { _, _ ->
            throw CodeEE("函数 $name 尚未实现（M3+：图像/音频/存档/网络类能力）")
        }
        placeholders.add(name)
    }

    /** 该名字是否仍是占位（未落地能力）。 */
    fun isPlaceholder(name: String): Boolean = placeholders.contains(name)

    /** 仍未落地的能力名单（快照，供台账/测试用）。 */
    fun placeholderNames(): Set<String> = placeholders.toSet()


    operator fun get(name: String): BuiltinMethod? {
        exactCache[name]?.let { return it }
        val m = map[name] ?: return null
        exactCache[name] = m
        return m
    }

    /** 解析期用：函数返回类型；null = 不是内建函数（供 IdentifierResolver 委托）。 */
    fun returnType(name: String): OperandType? = map[name]?.retType

    fun size(): Int = map.size

    fun names(): Set<String> = map.keys
}

/** 全局唯一注册表实例。 */
object Builtins {
    val registry: BuiltinRegistry = buildRegistry()

    private fun buildRegistry(): BuiltinRegistry {
        val r = BuiltinRegistry()
        // 顺序契约：占位先铺满（271 条一个不缺），真实现后注册并自动覆盖占位。
        // 这样任何能力家族在自己的文件里注册即可生效，不必回头改 HostMethods 的名单
        // （历史事故：register 排在占位后 + check(old==null) 导致运行期崩溃）。
        registerUnimplementedAll(r)
        registerPure(r)
        registerVarMethods(r)
        registerHostMethods(r)
        registerConsoleQuery(r)
        registerFileMethods(r)
        // M3-B 第二波能力家族（每个家族一个独占文件，见各文件 KDoc 的所有者标注）
        registerHtmlMethods(r)
        registerXmlMethods(r)
        registerMapMethods(r)
        registerDataTableMethods(r)
        registerGraphicsMethods(r)
        registerInputMethods(r)
        // MOUSE 族（MOUSEX/MOUSEY/MOUSEB/MOUSESKIP/FLOWINPUT/FLOWINPUTS）自 M4-I i-mouse 起
        // 由 MouseMethods 持有真相：MOUSEY 减 ClientHeight（EmueraConsole.cs:2264）、
        // 无窗口回 (0,0)（:2259-2262）、MOUSEB 越界不刷新因而返回过期指向串
        // （FunctionMethodCreator.cs:7876-7896）。InputMethods 里这六条同名注册已删。
        registerMouseMethods(r)
        registerSymbolQueryMethods(r)
        registerVarWriteMethods(r)
        registerDebugMiscMethods(r)
        registerCharaFileMethods(r)
        // ---- M4-J j-dispatch：三族「第二实现」接管分派（摘掉的旧注册见各文件迁移说明）----
        // 时间族：GETTIMES / GETMILLISECOND / GETSECOND（原由 registerHostMethods 注册）+
        //   GETMEMORYUSAGE（原由 registerDebugMiscMethods 注册）。切换的实际修正是
        //   GETMILLISECOND / GETSECOND 补上本地时区的 UTC 偏移（原版 DateTime.Now.Ticks/10000，
        //   spec: FunctionMethodCreator.cs:4008 —— 是本地墙上时间，旧接线漏了偏移，
        //   Asia/Shanghai 下比原版小 28 800 000）。GETTIME 仍在 HostMethods（逐字段取本地日历，
        //   与 DateTime.Now 同值）。registerTimeMethods 自带 claimable() 让路闸，重复接线安全。
        // 音频族：GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL（补 (int) 截断与「先物化再分派」）。
        // 配置族：GETCONFIG / GETCONFIGS（条目表唯一真相改为 core/config，报错文案回到 Lang.cs 原文）。
        registerTimeMethods(r)
        registerAudioMethods(r)
        registerConfigMethods(r)
        // ---- M6-SAVE-WIRE：存档槽家族（CHKDATA / SAVENOS）----
        // 两条原由 registerHostMethods 注册，M6-SAVE-WIRE 起由 SaveMethods.kt 持有真相
        // （函数口径回 Lang.cs 原文、CHKDATA 吃 SaveWiring 的 UseSaveFolder）。放在末尾：
        // 先让占位/旧注册铺好，真实现后注册覆盖；claimable 让路闸保证重复接线安全。
        registerSaveMethods(r)
        return r
    }
}

/**
 * 把 [BuiltinHost] 与 `store.aux` 的宏表现读合并的装饰器（CMP-34）。
 *
 * 为什么要在 [BuiltinEvalContext] 里做而不是改生产宿主：宏表是**每 VM** 的状态
 * （铁律 4 不许静态可变全局），生产唯一装配点 `GameSession` 按本波契约只出接线回执；
 * 把「aux 槽 → BuiltinHost.macroNames」这一步收在求值语境里，任何宿主（含
 * `BuiltinHost.DEFAULT`、`SemConfigHost`、E2E 装置）都自动吃到同一张表，不会再出现
 * 「lexerConfig 有宏、ISDEFINED 没宏」的两套读数。
 *
 * `delegate` 显式给的 [BuiltinHost.macros] / [BuiltinHost.macroNames] 优先。
 */
private open class AuxMacroHost(
    protected val delegate: BuiltinHost,
    private val aux: RuntimeAux,
) : BuiltinHost by delegate, GlyphMaskHostProvider {

    override val macros: Map<String, String>
        get() = delegate.macros.ifEmpty { lexerMacrosOf(aux) }

    override val macroNames: Set<String>?
        get() = delegate.macroNames ?: lexerMacroNamesOf(aux)

    /**
     * 可选宿主能力的**显式转发**（2026-10-24 修）。
     *
     * Kotlin 的接口委托（`BuiltinHost by delegate`）只转发 `BuiltinHost` 声明的方法，
     * delegate 额外实现的接口**不会**被透传 ⇒ `(c.host as? GlyphMaskHostProvider)` 在装饰后
     * 恒落空，GDRAWTEXT 静默退化成「推进盒」分支（表现：小掩码被描边整块盖成画笔色，
     * `GdrawTextGlyphTest` 8/8 红）。凡消费点用 `as?` 探测的宿主能力，都必须在装饰器里
     * 显式转发一次（GSAVE 的 `BinaryFileOps` 走 `c.host` 同一风险，见 [BuiltinHost] 使用点）。
     */
    override val glyphMaskHost: GlyphMaskHost?
        get() = (delegate as? GlyphMaskHostProvider)?.glyphMaskHost
}

/**
 * [AuxMacroHost] 的链宿主变体：`ConfigMethods.storeFromHost` 用
 * `host as? ConfigChainSource` 选「三层链重放 / 覆盖表降级」两条通道（`ConfigMethods.kt:235`），
 * 装饰器必须把这条身份一起透传，否则生产宿主会从钉位通道悄悄退回值快照通道。
 */
private class AuxMacroChainHost(
    delegate: BuiltinHost,
    private val chain: ConfigChainSource,
    aux: RuntimeAux,
) : AuxMacroHost(delegate, aux), ConfigChainSource {

    override val configChain: ConfigChainLoad get() = chain.configChain

    override val configDebug: ConfigDebugLoad? get() = chain.configDebug

    override fun applyChainTo(store: ConfigStore): ConfigChainLoad = chain.applyChainTo(store)
}

/** 按 delegate 是否实现 [ConfigChainSource] 选装饰器，保持原对象的接口身份。 */
private fun withAuxMacros(host: BuiltinHost, aux: RuntimeAux): BuiltinHost =
    if (host is ConfigChainSource) AuxMacroChainHost(host, host, aux) else AuxMacroHost(host, aux)

/**
 * EvalContext → 内建函数的桥。宿主把基础 EvalContext（变量回调 + 系统行输出）
 * 包进本类后交给表达式层。
 *
 * @param userFunctions 用户 #FUNCTION 表：名字（大写）→ 调用器。**优先于内建**
 *   （spec: IdentifierDictionary.cs:548-586，team-lead 已查证）。
 * @param userFunctionTypes 用户函数的返回类型（解析期 `resolveFunction` 委托用）。
 */
open class BuiltinEvalContext(
    private val base: EvalContext,
    override val store: VariableStore,
    host: BuiltinHost = BuiltinHost.DEFAULT,
    override val parserConfig: ParserConfig = ParserConfig(),
    private val userFunctions: Map<String, (List<Value?>) -> Value> = emptyMap(),
    private val userFunctionTypes: Map<String, OperandType> = emptyMap(),
    /**
     * 当前脚本行供给（FN-05）。用函数而不用值：本类构造一次、跨整局复用，
     * 行号随 VM 每条语句刷新（`ProcessRuntime.executeStatement` 写入），
     * 值形参会把第一行永久钉死。
     */
    private val currentScriptLineProvider: (() -> Pair<String, Int>?)? = null,
) : EvalContext by base, BuiltinContext {

    /**
     * 求值语境对外暴露的宿主 = 注入宿主 + `store.aux` 宏表现读（见 [AuxMacroHost]）。
     * 命名实参 `host =` 的调用点无需改动。
     */
    override val host: BuiltinHost = withAuxMacros(host, store.aux)

    override val variables: VariableReader get() = base.variables

    /** FN-05：现读 VM 的当前语句快照；未注入 = 保持 [BuiltinContext] 的 null 回落。 */
    override val currentScriptLine: Pair<String, Int>?
        get() = currentScriptLineProvider?.invoke()

    override fun printSystemLine(message: String) = base.printSystemLine(message)

    /**
     * 用户函数名大写记忆（PERF 2026-10-26）。
     *
     * 表达式里每次调用都要把用户 `#FUNCTION`/`#FUNCTIONS` 方法名归一到大写键
     * （[userFunctions]/[userFunctionTypes] 的键口径）。`String.uppercase` 在 ART 上走
     * ICU CaseMapper 逐字符分配；名字集合有限，用小表记忆归一结果。
     * 更关键的是**空表短路**：绝大多数语料没有用户方法，旧实现每次函数调用都先算一次
     * `name.uppercase()` 再去查空 map，纯属白分配。
     */
    private val userFuncKeys = HashMap<String, String>(16)

    private fun userFuncKey(name: String): String {
        val cached = userFuncKeys[name]
        if (cached != null) return cached
        val u = name.uppercase()
        userFuncKeys[name] = u
        return u
    }

    /** 解析期 resolveFunction 的内建 + 用户函数合并视图。 */
    fun resolveFunctionType(name: String): OperandType? =
        if (userFunctionTypes.isEmpty()) Builtins.registry.returnType(name)
        else userFunctionTypes[userFuncKey(name)] ?: Builtins.registry.returnType(name)

    override fun callFunction(name: String, args: List<Value?>): Value {
        // 用户 #FUNCTION 覆盖同名内建。spec: IdentifierDictionary.cs:548-586
        if (userFunctions.isNotEmpty()) userFunctions[userFuncKey(name)]?.let { return it(args) }
        val m = Builtins.registry[name]
            ?: throw CodeEE("未定义的函数 $name")
        m.checkArity(args.size)
        m.eval?.let { return it(this, args) }
        // 走到这说明该函数需要引用参数（evalRef），但调用方没有走引用路径
        throw CodeEE("函数 $name 的第 1 个参数必须是变量引用")
    }

    /**
     * 引用参数调用入口（对应原版 Ref 类型参数把 VariableTerm 原样传入的形态）。
     * 表达式层 `FuncCall` 经 [EvalContext.callFunctionRef] 走这里：evalRef 优先，
     * 其余回落值求值。
     */
    open override fun callFunctionRef(name: String, argExprs: List<Expr?>): Value {
        if (userFunctions.isNotEmpty()) userFunctions[userFuncKey(name)]?.let {
            return it(argExprs.map { e -> e?.toValue(this) })
        }
        val m = Builtins.registry[name] ?: throw CodeEE("未定义的函数 $name")
        m.checkArity(argExprs.size)
        m.evalRef?.let { return it(this, argExprs) }
        return m.eval.let { fn ->
            fn ?: throw CodeEE("未定义的函数 $name")
            fn(this, argExprs.map { e -> e?.toValue(this) })
        }
    }
}
