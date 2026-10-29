package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.CellWidth

/**
 * 调试 / 文本框 / 日志 / 线宽（[registerDebugMiscMethods]）。
 *
 * 落地名单（7 条）：OUTPUTLOG / GETLINESTR / GETTEXTBOX / SETTEXTBOX / MOVETEXTBOX /
 * RESUMETEXTBOX / BITMAP_CACHE_ENABLE。
 *
 * **M4-J j-dispatch 的分派切换**：本文件曾有 13 条，其中六条已经有「按 C# 逐条对拍的
 * 第二实现」但没注册（`BuiltinRegistry.register` 撞真实现即 error，Builtins.kt:594-601），
 * 现已摘掉注册、把名字交给各自的家族文件；本文件保留宿主面接口
 * （[TextBoxHost] / [AudioHost] / [SoundInfo] / [AudioOp]）与 `intArg`：
 * - GETMEMORYUSAGE → `TimeMethods.kt` 的 registerTimeMethods（同一口径，同一文案）；
 * - GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL → `AudioMethods.kt`（旧实现缺
 *   `(int)` 截断与「先物化再分派」，逐条行号见该文件类头第四节）；
 * - GETCONFIG / GETCONFIGS → `ConfigMethods.kt`（旧实现自带一份与 core/config 重复的
 *   125 行手抄条目表，且把 `Lang.Error` 原文改写成了中文）。
 * 各自的迁移说明写在注册函数体内。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - MoveTextBoxMethod        :3079-3110（MOVETEXTBOX / RESUMETEXTBOX）
 * - GetLineStrMethod         :5894-5910
 * - GetTextBoxMethod         :8342-8355
 * - ChangeTextBoxMethod      :8357-8374（SETTEXTBOX）
 * - BitmapCacheEnableMethod  :8655-8676
 * - OutputlogMethod          :8732-8767
 * 配套：`MinorShift.Emuera.GameView/EmueraConsole.cs` 的 getStBar :3420-3435、
 * OutputLog :3459-3484、GetLog :3507-3545；`Runtime.Config/ConfigData.cs`
 * GetItem :236-249、GetConfigValueInERB :350-428；`Runtime.Config/ConfigItem.cs`
 * ValueToString :53-87、TryParse :89-213（后两组现在由 `core/config` +
 * `ConfigMethods.kt` 消费，本文件只为 GETLINESTR 读 WindowX / FontSize 两项）。
 *
 * 宿主缺口的硬判据（合同 §5「无宿主能力不许静默返回 0」）：
 * - 文本框：宿主未注入时抛 CodeEE，并在消息里点名需要注入的
 *   [BuiltinHost] 属性，绝不伪造 0、空串或静音（音频、内存两条随各自家族一起迁出，
 *   判据不变）。
 * - OUTPUTLOG 需要 [BuiltinHost.fileOps] 与 [BuiltinHost.consoleLines]；缺任一项抛 CodeEE。
 * - GETLINESTR 只用 [BuiltinHost.measureText]（可选）；缺省回落到与 HTML 家族一致的
 *   等宽近似（半角步进 = FontSize/2、全角 = FontSize），全角/半角名单唯一取自
 *   [CellWidth]（口径 = CP932 字节数），证据与分歧登记见本文件的 measureWidth。
 * - BITMAP_CACHE_ENABLE 在原版**恒返回 0**（:8672），只改下一行的位图缓存开关；
 *   本移植没有渲染器消费该开关，但返回值与开关状态都按原版落地，不属于「假实现」。
 *
 * 刻意照抄的原版行为（配置名查找的大小写缺陷、GETCONFIG/GETCONFIGS 互指的类型错误
 * 文案，现在由 `ConfigMethods.kt` 与 `core/config/ConfigStore` 复刻，证据行号见那里）：
 * - `RESUMETEXTBOX` 逻辑上不需要参数，但原版复用了 MoveTextBoxMethod 的三参签名，
 *   所以仍必须写满 3 个整数（:3079-3090）。
 */
internal fun registerDebugMiscMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // ==================================================================
    // 配置查询：已迁出
    // ==================================================================
    //
    // GETCONFIG / GETCONFIGS 自 M4-J j-dispatch 起由本文件末尾的 registerConfigMethods
    // 名单持有真相（实现体 ConfigMethods.kt，注册收口见 Builtins.buildRegistry）。
    // 摘掉旧注册的理由有两条，都是「两份表」带来的：
    // - 条目表：旧实现自带一份 125 行手抄表（本文件曾有的 CONFIG_ROWS），
    //   与 core/config/ConfigSpecTable（唯一真相，逐条带 ConfigData.cs 行号）重复；
    //   现在本文件的 GETLINESTR 也改吃 core/config 那一份（见文件末 ConfigState）。
    // - 报错文案：旧实现把 Lang.Error 原文改写成了中文，见 ConfigMethods 的 LangError
    //   与交付报告的取舍说明。

    // ==================================================================
    // 文本框
    // ==================================================================

    // spec: :8342-8355 —— Console.Window.TextBox.Text
    r.register(BuiltinMethod("GETTEXTBOX", S, 0, 0) { c, _ ->
        Value.StrV(textBoxHost(c, "GETTEXTBOX").text)
    })
    // spec: :8357-8374 —— Console.Window.ChangeTextBox(arg)
    r.register(BuiltinMethod("SETTEXTBOX", I, 1, 1) { c, a ->
        val box = textBoxHost(c, "SETTEXTBOX")
        val text = (a.getOrNull(0) as? Value.StrV)?.v
            ?: throw CodeEE("函数 SETTEXTBOX 的第 1 个参数必须是字符串")
        box.setText(text)
        Value.IntV(1L)
    })
    // spec: :3079-3110 —— resume=false 分支：SetTextBoxPos(x, y, width)
    r.register(BuiltinMethod("MOVETEXTBOX", I, 3, 3) { c, a ->
        val box = textBoxHost(c, "MOVETEXTBOX")
        box.moveTextBox(
            intArg(a, 0, "MOVETEXTBOX").toInt(),
            intArg(a, 1, "MOVETEXTBOX").toInt(),
            intArg(a, 2, "MOVETEXTBOX").toInt(),
        )
        Value.IntV(1L)
    })
    // spec: :3079-3110 —— resume=true 分支：ResetTextBoxPos()，三个参数**不读也不求值**
    //   （C# 函数体整段 `if (resume) { ResetTextBoxPos(); }`，实参是 AExpression，只有
    //    else 分支才调 GetIntValue）。故 evalRef 路径把三个实参原样丢弃；eval 路径
    //    （测试/直接值调用）沿用已求值的读数，返回值相同。
    r.register(
        BuiltinMethod("RESUMETEXTBOX", I, 3, 3, evalRef = { c, _ ->
            textBoxHost(c, "RESUMETEXTBOX").resetTextBoxPos()
            Value.IntV(1L)
        }) { c, _ ->
            textBoxHost(c, "RESUMETEXTBOX").resetTextBoxPos()
            Value.IntV(1L)
        },
    )

    // ==================================================================
    // 内存 / 日志 / 线宽
    // ==================================================================

    // GETMEMORYUSAGE 已迁到 TimeMethods.kt 的 registerTimeMethods（同一条 WorkingSet64 口径，
    // spec: FunctionMethodCreator.cs:8307-8321；JVM 侧取哪个计数器的对照表见 TimeMethods.kt
    // 类头第四节）。宿主未接线时抛的 CodeEE 文案与旧注册逐字相同，两条路同判。

    // spec: :8732-8767 —— 参数 0 = 文件名（省略/空 → emuera.log），参数 1 == 1 时隐藏环境头
    r.register(BuiltinMethod("OUTPUTLOG", I, 0, 2) { c, a ->
        val name = when (val v = a.getOrNull(0)) {
            null -> ""
            is Value.StrV -> v.v
            else -> throw CodeEE("函数 OUTPUTLOG 的第 1 个参数必须是字符串")
        }
        val hideInfo = when (val v = a.getOrNull(1)) {
            null -> false
            is Value.IntV -> v.v == 1L
            else -> throw CodeEE("函数 OUTPUTLOG 的第 2 个参数必须是整数")
        }
        outputLog(c, name, hideInfo)
        // spec: :8765 —— 不论 Console.OutputLog 成功与否，函数本身恒返回 1
        Value.IntV(1L)
    })

    // spec: :5894-5910 —— Console.getStBar(arg)
    r.register(BuiltinMethod("GETLINESTR", S, 1, 1) { c, a ->
        val bar = (a.getOrNull(0) as? Value.StrV)?.v
            ?: throw CodeEE("函数 GETLINESTR 的第 1 个参数必须是字符串")
        if (bar.isEmpty()) throw CodeEE("函数 GETLINESTR：第 1 个参数为空字符串")
        Value.StrV(buildBar(c, bar))
    })

    // spec: :8655-8676 —— 只设置下一行的缓存开关，返回值恒为 0
    r.register(BuiltinMethod("BITMAP_CACHE_ENABLE", I, 1, 1) { c, a ->
        val on = intArg(a, 0, "BITMAP_CACHE_ENABLE") != 0L
        c.aux.slot(BITMAP_CACHE_SLOT) { BitmapCacheState() }.enabled = on
        Value.IntV(0L)
    })

    // ==================================================================
    // 音频：已迁出
    // ==================================================================
    //
    // GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL 自 M4-J j-dispatch 起由
    // AudioMethods.kt 的 registerAudioMethods 持有真相（注册收口见 Builtins.buildRegistry）。
    // 旧实现有两处与原版不符，正是切换的理由：
    // - 缺 C# 的 `(int)` 低 32 位截断（spec: FunctionMethodCreator.cs:8791、:8906-8907、:8995），
    //   于是 `GETSOUNDORBGMINFO 4294967295`（截断为 -1，原版读 BGM）在旧实现回 0；
    // - 缺「先物化再分派」：原版建道早于操作码分派（:8912-8915、:8996-8999），
    //   旧实现在返回 -2 的路径上一个宿主动作都不调 ⇒ `BGMCONTROL 9` 之后
    //   `GETSOUNDORBGMINFO -1, 4` 读到 0 而原版读到 100（Sound.cs:13 volume = 1f）。
    // 本文件仍然**拥有**宿主面 [AudioHost] / [SoundInfo] / [AudioOp]（新实现照样吃它们），
    // 只有分派与函数体搬走了。
}

// ---------------------------------------------------------------------------
// 宿主接口
// ---------------------------------------------------------------------------

/**
 * 文本框后端（[BuiltinHost.textBox]）。
 *
 * 对应原版 `EmueraConsole.Window` 的 TextBox 相关成员：Text 属性、ChangeTextBox、
 * SetTextBoxPos / ResetTextBoxPos（FunctionMethodCreator.cs:3079-3110、:8342-8374）。
 * null = 宿主未接线 → 四个文本框函数抛 CodeEE。
 */
interface TextBoxHost {
    val text: String

    fun setText(text: String)

    /** 原版参数是 (x, y, width)；高度保持不变。 */
    fun moveTextBox(x: Int, y: Int, width: Int)

    fun resetTextBoxPos()
}

/**
 * 声音状态快照（[AudioHost.soundInfo] / [AudioHost.bgmInfo] 的返回）。
 *
 * 字段口径对齐原版 `Sound` 的 getter：`GetTotalTime()` / `GetCurrentTime()` 为**秒**
 * （FunctionMethodCreator.cs:8810-8815 乘以 1000 后截断），`getSpeed()` 为倍率
 * （乘以 100 后截断），`getVolume()` 原样返回。
 *
 * @property totalSeconds   总时长（秒）
 * @property currentSeconds 当前播放位置（秒）
 * @property playing        是否正在播放
 * @property volume         音量（原版 getVolume 的整数口径）
 * @property speed          播放速度倍率（1.0 = 原速）
 */
data class SoundInfo(
    val totalSeconds: Double,
    val currentSeconds: Double,
    val playing: Boolean,
    val volume: Long,
    val speed: Double,
)

/** [AudioHost] 的操作种类，对应原版操作码 0/1/2/3。 */
enum class AudioOp { PAUSE, RESUME, STOP, SET_SPEED }

/**
 * 音频后端（[BuiltinHost.audio]）。
 *
 * 与原版 `GlobalStatic.Sound[]` / `GlobalStatic.Bgm` 的对应关系：
 * - 声道数量仍由 [BuiltinHost.soundChannels] 给出（即 `GlobalStatic.Sound.Length`）；
 *   本接口只负责对**已确定合法**的声道执行操作，越界判定留在函数层（原版返回 -1）。
 * - [soundInfo] 对 0..soundChannels-1 恒返回快照（原版在首次访问时惰性 `new Sound()`，
 *   新建对象处于停止/0 时长状态）。
 * - [bgmInfo] 返回 null 表示 BGM 从未创建（原版 `GlobalStatic.Bgm == null`，函数返回 0）。
 * - [controlSound] / [controlBgm] 只在操作码合法时被调用；实现方负责把
 *   [AudioOp.SET_SPEED] 的 speed 应用为倍率，并按 preservePitch 选择是否保留音高。
 */
interface AudioHost {
    fun soundInfo(channel: Int): SoundInfo

    fun bgmInfo(): SoundInfo?

    fun controlSound(channel: Int, op: AudioOp, speed: Double = 1.0, preservePitch: Boolean = true)

    fun controlBgm(op: AudioOp, speed: Double = 1.0, preservePitch: Boolean = true)
}

// ---------------------------------------------------------------------------
// 文本框 / 音频 / 日志
// ---------------------------------------------------------------------------

private fun textBoxHost(c: BuiltinContext, fn: String): TextBoxHost =
    c.host.textBox
        ?: throw CodeEE("函数 $fn：文本框宿主尚未接线（需要宿主注入 BuiltinHost.textBox）")

// 注：与 textBoxHost 同形的 `audioHost` 已随三条音频函数一起迁到 AudioMethods.kt
// （该文件末尾的私有 audioHost 抛的是同一条文案），本文件不再持有它。

private const val BITMAP_CACHE_SLOT = "gfx.bitmapCacheEnabledForNextLine"

/** BITMAP_CACHE_ENABLE 的跨调用状态：原版是 Console 上的一个 bool 字段（:8672）。 */
private class BitmapCacheState(var enabled: Boolean = false)

private const val CRLF = "\r\n"

/**
 * OUTPUTLOG 的落盘路径与内容（EmueraConsole.cs:3459-3484、3507-3545）。
 *
 * 与原版的差异：
 * - 原版把 ExeDir 前缀拼在文件名上；本移植的 [com.eratw.port.core.host.FileOps]
 *   已经以工作目录为根，故直接传相对路径。[BuiltinHost.logHeaderLines] 提供
 *   「環境情報 / 本体 / ログ」头（空表 = 只写显示行）。
 * - 路径检查保留 `../`（原版只查正斜杠形式，本实现同时拒绝反斜杠形式——安全加固），
 *   绝对路径按原版「写入失败」处理：不落盘、不打印成功行。
 * - 原版在写盘成功后用 PrintSystemLine 打一行；本移植照做，基准确认该行为
 *   （pc-golden-root.txt:534 `※※※已创建Log文件GOLDEN_LOG.txt※※※`）。
 */
private fun outputLog(c: BuiltinContext, filename: String, hideInfo: Boolean) {
    val requested = if (filename.isEmpty()) "emuera.log" else filename
    // spec: :3462-3467 —— 含 "../" 视为父目录输出，静默失败（函数仍返回 1）
    if (requested.contains("../") || requested.contains("..\\")) return
    if (isAbsolutePath(requested)) return
    val ops = c.host.fileOps
        ?: throw CodeEE("函数 OUTPUTLOG：文件宿主尚未接线（需要宿主注入 BuiltinHost.fileOps）")
    val lines = c.host.consoleLines
        ?: throw CodeEE("函数 OUTPUTLOG：控制台行缓冲尚未接线（需要宿主注入 BuiltinHost.consoleLines）")
    val sb = StringBuilder()
    if (!hideInfo) {
        for (header in c.host.logHeaderLines) sb.append(header).append(CRLF)
    }
    // spec: :3537-3541 —— 每条显示行取 ToString 后 AppendLine。
    // ConsolePrintedLine.text 已是按钮文本顺序拼接的纯文本（见其 KDoc），无需再剥 HTML。
    for (line in lines) sb.append(line.text).append(CRLF)
    // spec: :3444-3446 —— EncodingHandler.UTF8BOMEncoding，覆盖写
    if (!ops.writeText(requested, "\uFEFF$sb", append = false)) return
    c.printSystemLine("※※※已创建Log文件${requested}※※※")
}

private fun isAbsolutePath(p: String): Boolean =
    p.startsWith("/") || p.startsWith("\\") ||
        (p.length >= 2 && p[1] == ':' && p[0].isLetter())

// ---------------------------------------------------------------------------
// GETLINESTR
// ---------------------------------------------------------------------------

/**
 * EmueraConsole.getStBar（:3420-3435）。
 *
 * 先追加一份 barStr，然后**每次整串**测量宽度直到 >= DrawableWidth，再逐字符删到
 * <= DrawableWidth。DrawableWidth = WindowX - max(2, FontSize/6)（Config.cs:461-466）。
 *
 * 与原版的唯一差异是「零进度保护」：原版对零宽字符串会死循环，本实现对测量值不再
 * 增长的输入抛 CodeEE。TODO(verify)：真实字体下未发现零宽 barStr 用例。
 */
private fun buildBar(c: BuiltinContext, barStr: String): String {
    val state = configState(c)
    val drawable = drawableWidth(state)
    val fontSize = state.intOf(ConfigKey.FontSize)
    val sb = StringBuilder(barStr)
    var width = 0L
    // 原版 num 从 0 开始，故至少会追加一次
    var previous = -1L
    while (width < drawable) {
        sb.append(barStr)
        width = measureWidth(c, sb.toString(), fontSize)
        if (width <= previous) {
            throw CodeEE("函数 GETLINESTR：字符串\"${barStr}\"的显示宽度不随追加增长，无法确定长度")
        }
        previous = width
    }
    while (width > drawable) {
        if (sb.isEmpty()) return ""
        sb.setLength(sb.length - 1)
        width = measureWidth(c, sb.toString(), fontSize)
    }
    return sb.toString()
}

private fun drawableWidth(state: ConfigState): Int {
    val windowX = state.intOf(ConfigKey.WindowX)
    val fontSize = state.intOf(ConfigKey.FontSize)
    return windowX - maxOf(2, fontSize / 6)
}

/**
 * 原版逐次调用 StringMeasure.GetDisplayLength（StringMeasure.cs:42-72，GDI 像素）。
 * 宿主提供 [BuiltinHost.measureText] 时用宿主值；否则退到与 HtmlMethods.kt 同式的
 * 等宽近似：半角步进 = FontSize/2、全角 = FontSize。
 *
 * **宽度表不在本文件**：全角/半角唯一取自 [CellWidth.isFullWidth]
 * （`core/host/CellWidth.kt`，口径 = CP932(Shift-JIS) 字节数）。本文件原先那份
 * East Asian Wide/Fullwidth 副本（旧 :351-364，与 HtmlMethods 同表）已随 WIDTH-SINGLE
 * 收口删除；为什么像素可以用字节数口径、以及收口后变大的那一侧，逐条证据在
 * `core/fn/HtmlMethods.kt` 的 approxTextWidth 的 KDoc。
 *
 * 本侧的落点值得单独登记，因为 GETLINESTR 的**返回长度**是会进基准/语料的读数：
 * 语料 `ERB/カラム機能/カラム機能(div).ERB:21` 的 `#DIM DYNAMIC 行文字数 = STRLENSU(GETLINESTR("─"))`
 * （STRLENSU 是 UTF-16 长度，spec: FunctionMethodCreator.cs:9124 把键映射到 StrlenuMethod，
 * 其 :5284-5287 返回 `.Length`；字节数那一族是 STRLEN → StrlenMethod :5269-5272 →
 * LangManager.cs:15-22）。在基准环境（WindowX 1800、FontSize 18 ⇒ DrawableWidth 1797，
 * `Config.cs:461-466`）下：旧表 `─` 记 9px ⇒ 199 字，现口径记 18px ⇒ **99 字**，
 * 与基准 `pc-golden-root.txt:61` 那条 99 个 `━` 的横线同数（同一 `getStBar` 算法，
 * `EmueraConsole.cs:3420-3435`）；旧表的 199 个双字节字在屏上占约两行，明显不对。
 * 单位测试里的 WindowX 是夹具值 760（DrawableWidth 757），同一换算给 42 字。
 *
 * TODO(verify)：本函数与 HtmlMethods.kt 的 approxTextWidth 都未做 TAB 制表位展开
 * （原版 GRAPHICS 模式先替换成 8 个空格，StringMeasure.cs:57-62）；
 * FontSize 为奇数时全角不等于两个半角步进。
 */
private fun measureWidth(c: BuiltinContext, s: String, fontSize: Int): Long {
    if (s.isEmpty()) return 0L
    c.host.measureText?.let { return it(s) }
    var width = 0L
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        width += if (CellWidth.isFullWidth(cp)) fontSize.toLong() else (fontSize / 2).toLong()
        i += Character.charCount(cp)
    }
    return width
}

// ---------------------------------------------------------------------------
// GETSOUNDORBGMINFO / SOUNDCONTROL / BGMCONTROL 的实现体：已迁出
// ---------------------------------------------------------------------------
//
// 三条函数体（曾有 soundOrBgmInfo / soundControl / bgmControl）与只被它们使用的
// writeResult 都在 AudioMethods.kt（口径差异见 registerDebugMiscMethods 末尾的迁移说明）。
// intArg 仍在用（MOVETEXTBOX / BITMAP_CACHE_ENABLE / 文本框族），保留在本文件。

// ---------------------------------------------------------------------------
// 取参助手
// ---------------------------------------------------------------------------

private fun intArg(args: List<Value?>, i: Int, fn: String): Long {
    val v = args.getOrNull(i) ?: throw CodeEE("函数 $fn：缺少第 ${i + 1} 个参数")
    return (v as? Value.IntV)?.v ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数必须是整数")
}

// ---------------------------------------------------------------------------
// GETLINESTR 的两个配置读数：条目表唯一真相在 core/config
// ---------------------------------------------------------------------------

private const val CONFIG_SLOT = "config.state"

private fun configState(c: BuiltinContext): ConfigState =
    c.aux.slot(CONFIG_SLOT) { ConfigState(c.host) }

/**
 * 每 VM 一份的配置快照 —— 本文件只剩 GETLINESTR 用得到它（`DrawableWidth` 用的 WindowX
 * 与半角/全角步进的 FontSize，spec: `Config.cs:461-466`）。
 *
 * **表只有一份**：条目表来自 `core/config` 的 [ConfigSpecTable]（123 条逐条带
 * `ConfigData.cs` 行号）套出来的 [ConfigStore]；`GETCONFIG` / `GETCONFIGS` 经
 * [ConfigMethods] 吃的是同一份。本文件曾有那份 125 行手抄 `CONFIG_ROWS` 与其配套
 * 的值模型 / 解析器 / 枚举表已随分派切换一起删除，避免两张表各自漂移
 * （文案取舍见 ConfigMethods.kt 的 LangError 与交付报告）。
 *
 * 播种口径逐条沿用旧实现的 `seedFromHost`：只有「运行期真实可观测」的项覆盖出厂默认
 * —— 窗口宽高、字体与字号、PRINTC 的两项、存档页数、货币标签与位置、consoleStyle 的
 * 三色与字体名；其余保持 `ConfigData.setDefault` 的默认值。
 * WindowY = ClientHeight + LineHeight（spec: `MainWindow.cs:726` 的逆运算）。
 * 覆盖表（[BuiltinHost.configOverrides]）与宿主运行态走同一条解析路径
 * （[ConfigMethods.storeFromHost] ⇒ `EmueraConfigParser.loadOverrideMap`），
 * 解析失败的项按 ConfigWarn 口径保留旧值、不影响整局启动
 * （spec: `ConfigData.cs:563-586`）。
 */
private class ConfigState(host: BuiltinHost) {
    private val store: ConfigStore = ConfigMethods.storeFromHost(host)

    /** 按标识取 int 型条目的现值；类型写错立即失败（对齐原版 `Config.GetValue<int>` 的强转）。 */
    fun intOf(key: ConfigKey): Int = store.itemOf(key).int()
}
