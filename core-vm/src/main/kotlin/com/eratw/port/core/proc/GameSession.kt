package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.parseBoolLiteral
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.frontend.Symbols
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.IdleInputState
import com.eratw.port.core.fn.InputState
import com.eratw.port.core.fn.TextMeasureRequest
import com.eratw.port.core.host.FontDisplayWidth
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.StaticFontDisplayWidth
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.installLexerMacros
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.save.EmSaveSets
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveFolderMigration
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * 生产启动器：`Program` + 侧车元数据 + 游戏根 → 可立即 `begin(TITLE)` 的运行期。
 *
 * ## 输入与来源无关
 *
 * 只吃 [program] + [metadata] + [gameRoot]，**不关心** `Program` 是源码直编（`CorpusCompile`）
 * 还是 `.erbc` 装载（`ProgramLoader` + `BootMetadata.read`）。这样桌面测试可以用内存产物，
 * 真机走 `.erbc` + 侧车，两条路的装配完全同一份代码（判据见 `BootMetaRoundTripTest`：
 * 同一份合成语料两条路 `BEGIN TITLE` 的控制台行逐行相同）。
 *
 * ## 装配顺序（口径 = 开局冒烟装置 `GameBootSmoke` 的生产面）
 *
 * 1. [GameDataLoader.load]`(gameRoot/CSV)` —— 缺失即 null，不阻断（无 CSV 时系统变量按缺省注册表）；
 * 2. [VariableStore]：角色模板 → ITEM 价格 → STR 名表 → 角色种子
 *    （CSV 有模板 0 时种真实角色，否则 4 个裸角色，同装置口径）；
 * 3. 全局 `#DIM`：[SystemVariableBootstrap.install]（剥行内注释 + 不动点重试环，
 *    spec: `LexicalAnalyzer.cs:919-929` / `ErhLoader.cs:315-337`）；
 * 4. 私有 `#DIM`：[VariableStore.declareUserVar]`(spec, functionKey)`（被遮蔽定义不注册）；
 * 5. 内建函数宿主：[ConsoleBuiltinHost]（委托 [RootedBuiltinHost]：文件 IO / 时钟 / 存档槽探测 /
 *    三层配置链与 `_Replace.csv` 覆盖；写请求一律拒绝并计数，语料只读铁律）；
 * 6. [ProcessRuntime]；7. `installLabelTable` / `installDataSections`。
 *
 * 存档接线在 5 与 6 之间：[saveConfig] 从 [configStore] 按 [ConfigKey] 读出
 * （`SystemSaveInBinary` / `ZipSaveData` / `UseSaveFolder`），再把 `Config.SaveDataNos`
 * 的生效值与之一并装进 [VariableStore.aux] 的 [SaveWiring] 槽；同一槽还带
 * `<gameRoot>/CSV/VarExt*.csv` 的 Map/Xml/DT 扩展段名字表（[EmSaveSets]，
 * `ConstantData.cs:1317`）。[ProcessRuntime] 的 `systemOps` 缺省
 * = `FileSystemOps(store, RootedSaveFileIo(gameRoot, rooted.fileOps), saveConfig)`
 * （`SAVEDATA` / `LOADDATA` / `DELDATA` / `SAVEGLOBAL` / `LOADGLOBAL` / 角色档全部落到
 * `<gameRoot>` 的真实存档文件）。
 *
 * ## 侧车生命周期（M8-BOOTMETA）
 *
 * `metadata` 是**装配期输入**，装配完成后本类不再持有它：真机冷启动时它来自
 * `<gameRoot>/erbc.v1/program.meta`（全量语料产物 49,224,605 B），而解析后的对象图按常驻
 * 模型实测 **96.8 MiB**（是文件的两倍），整份常驻会直接吃掉 512 MB 堆的预算。
 * 逐段处置如下（判据 = 运行期还有没有读者）：
 *
 * | 段 | 处置 | 判据 |
 * |---|---|---|
 * | `functions` 110,072 条 | **保留** | `FunctionTable` 是 CALL / 事件派发 / `#FUNCTION` 的唯一索引，`BEGIN TITLE` 第一句就要查表；换 `program.functions` 会丢事件位与方法位（`.erbc` v1 的函数标志恒 0） |
 * | 全局 7,201 / 私有 19,744 条 `#DIM` | 装配期**全量**消费后释放 | `SystemVariableBootstrap.install` 要跑不动点重试环、私有声明要逐条 `declareUserVar`；脚本执行前变量必须全部就位，没有「用到再声明」的口子（原版 `ErhLoader.cs:249-338` 同样是装载期一次建池） |
 * | `dimParseConfig`（22 条宏 + 5,770 条整型常量） | 装配期消费后释放 | 只喂 `DimParser`；常量表的副本已随 [globalDimInstall] 的 `constants` 留在结果里 |
 * | `fileByKey` 110,072 条 | **释放** | 本类与运行期零读者（它只服务开局装置的首错定位，那条路用源码直编的 metadata） |
 * | `labels` 外层表（110,072 个键，其中 109,172 个空表） | 装配期**剪枝**后保留 | 真延迟会把这 10.9 万只空表整份钉住；先剪成 900 个有标签函数的 1,623 条，才谈得上「只留用得着的」 |
 * | `dataSections` 3,874 节 | 保留，键 = **语句池下标**直查 | 原版数据节挂在**语句行对象**上（`FunctionIdentifier.cs:4285` 的 `IsPrintData`），移植侧只有侧车带着这棵树；侧车本身按语句池下标导出，而语句层消费时拿到的也是池下标（`DataSections.groupsOf(stmtIndex)`）⇒ 两张表键天然对齐，不再需要「下标 → 实例」反查层（见 [SidecarTables]） |
 *
 * 构造完因此只留三样：`FunctionTable`（48.4 MiB，其中函数元数据本身 38.0 MiB）、剪枝后的
 * 标签表（0.3 MiB）、数据节表（5.9 MiB），合计 54.6 MiB；**回收 53.1 MiB**——即
 * `fileByKey`（28.6 MiB）+ 外层标签表（18.4 MiB）+ `#DIM` 原文与宏表（5.6 MiB）与侧车外壳，
 * 随 `metadata` 一起在 GC 后归还。读法口径见 [SidecarTables]。
 *
 * ## 边界（明确不做）
 *
 * - **不读时钟、不起线程、不 print**：日志/时序一律由宿主读 [console]、[runtime] 的状态；
 * - **不写语料**：存档写只发生在 [saveConfig] 指定的存档路径（`SAVEDATA` 走 `FileSystemOps`
 *   的 NIO 写，不经 [ConsoleBuiltinHost] 的只读 [WriteBlockedFileOps]）；
 * - **不等输入**：输入只从 [inputSink] 的队列里取；本类自己从不阻塞。
 *
 * ## 失败口径
 *
 * 装配期能判死的错（侧车与产物不配对、函数元数据缺失）在构造期抛 [IllegalArgumentException]；
 * 脚本语义错（未定义变量、宿主能力缺失）全部留给 `begin` 抛 `CodeEE`，不在这里吞。
 *
 * @param gameRoot 游戏可执行目录（原版 `Program.ExeDir`）—— `ERB` 的**上一级**：
 *   `CSV/`、`resources/`、`sav/`、`sound/`、`emuera.config` 都在这层（`RootedFileOps` 同根）。
 * @param program 编译产物（源码直编或 `.erbc` 装载，二者等价）。
 * @param metadata 与 [program] 配对的侧车（函数元数据 / `#DIM` / 标签 / 数据节 / 宏表）。
 *   只在装配期消费，**不保留**（见上文「侧车生命周期」）；装配后本类只持有 [sidecar]
 *   投影出来的两张表与 `FunctionTable` 的函数表。参数名不变，命名实参的调用点无需改动。
 * @param consoleWidth 控制台显示宽度（**半角格**，`LineBufferConsoleSink` 的折行单位）。
 *   真机要按字体度量把像素宽换算成格数后传入（`render` 的 `GlyphCache.measureCells` 口径）。
 * @param printCWidth PRINTC 的补齐宽度（原版 `Config.PrintCLength`，语料 `emuera.config` 为 25）。
 *   0（缺省）= 读 [configStore] 的 `PRINTCの文字数` 条目（注册缺省 25，spec: `ConfigData.cs:64`；
 *   静态字段读取点 spec: `Config.cs:327`）—— 设置页改这项后由下一局会话吃到。显式传 >0 时以宿主为准。
 * @param maxLines 显示行上限（原版 `Config.MaxLog`；语料 `emuera.config` 为 900）。
 * @param maxStepsPerRun 单次 `begin`/`resumeSystem` 的步数闸。ENGFIX-3（2026-11-05）起
 *   **不再是死循环判据**——官方/skia 的死循环保护是墙钟 + 弹框问玩家（VmConfig.infiniteLoopAlertTimeMs
 *   + VmHost.askInfiniteLoop），本字段降级为 [LoopGuardCause.DeviceBudget] 的装置预算/极端兜底，
 *   语义见 [com.eratw.port.core.code.VmConfig.maxStepsPerRun] 的 KDoc。
 * @param randSeed RNG 种子。null = 交给 [ProcessRuntime] 的缺省（时钟，原版 `MTRandom` =
 *   `Environment.TickCount`，`MTRandom.cs:25-27`）；确定性地对拍时传常量。
 * @param soundSink 语句层音频 sink（缺省 [NoopSoundSink]；真机接 `TableSoundSink`）。
 * @param systemOps 存档/系统语句宿主。null（缺省）= 按 [saveConfig] 建 [FileSystemOps]
 *   （真存档层）；装置要拦写时显式传自己的实现（如 `GameBootSmoke` 的 `bootOps`）。
 * @param semConfig 语句层配置。null（缺省）= 按生效配置取 `PRINTCを並べる数`
 *   （注册缺省 3，spec: `ConfigData.cs:63`；静态字段读取点 spec: `Config.cs:326`；语料
 *   `emuera.config:19` 为 5）—— 设置页改这项后由下一局会话吃到。显式传入时以宿主为准
 *   （装置与黄金对拍按语料固定传 5）。`saveDataNos` 会被 [configStore] 的生效值覆盖
 *   （`Config.cs:338` + `:441-450`）—— 语句 `SAVENOS`（Process.cs:1580-1582）与函数
 *   `SAVENOS()` 必须同一份读数。
 * @param configStore 会话配置。null（缺省）= 经 [RootedBuiltinHost.applyChainTo] 重放
 *   `<gameRoot>` 的完整配置链（`CsvDir/_default.config` → `emuera.config` → `CsvDir/_fixed.config`，
 *   spec: `ConfigData.cs:479-494`）并叠上 `_Replace.csv`（spec: `Process.cs:240-270`）——
 *   与函数层 `ConfigMethods.storeFromHost` 走同一个重放入口，两条通道读数一致。
 *   设置页落盘的 `emuera.config` 由这条链在**下一次**会话装配时吃到（原版
 *   `Program.cs:95` 只在进程启动装载一次，运行中不重读；设置页的「重启」入口即为此）。
 *   显式传入时**不**再叠 Replace（调用方给的条目表就是权威，TODO(verify)：那条接线不在本路文件面内）。
 */
class GameSession(
    val gameRoot: Path,
    val program: Program,
    metadata: BootMetadata,
    consoleWidth: Int = DEFAULT_CONSOLE_WIDTH,
    printCWidth: Int = 0,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxStepsPerRun: Long = DEFAULT_MAX_STEPS_PER_RUN,
    /**
     * 单段落墙钟上限（毫秒）；0（缺省）= 不限。语义与取值依据见
     * [com.eratw.port.core.code.VmConfig.maxRunMillis]。
     *
     * **缺省 0 是刻意的**：装置（sweep / play / E2E）要的是「确定性复现」，墙钟在 CI 上
     * 不稳定；生产由 [GameHost] 显式传 [PRODUCTION_MAX_RUN_MILLIS]（同样 = 0，即不启用）。
     * ENGFIX-3 起死循环判据归 [com.eratw.port.core.code.VmConfig.infiniteLoopAlertTimeMs]
     * + [com.eratw.port.core.code.VmHost.askInfiniteLoop]（官方「弹框问玩家，不自动中止」
     * 语义）；ENGFIX-4 起生产侧由 app 的 [infiniteLoopPrompt] 接 AlertDialog，
     * 阈值取会话生效配置的 `InfiniteLoopAlertTime`（注册缺省 5000ms，置 0 关）。
     */
    maxRunMillis: Long = 0L,
    randSeed: Long? = null,
    soundSink: SoundSink = NoopSoundSink,
    systemOps: SystemOps? = null,
    semConfig: SemConfig? = null,
    configStore: ConfigStore? = null,
    /** 「一屏一帧」开关：false = 关掉帧冻结，控制台变更立即发布（见 `LineBufferConsoleSink`）。 */
    frameGateEnabled: Boolean = true,
    /**
     * 键盘/指针后端（GETKEY / GETKEYTRIGGERED / ISACTIVE 的数据源）。缺省
     * [IdleInputState] = headless「窗口未激活」，全部读数按 0（原版口径
     * `FunctionMethodCreator.cs:7816-7819`）；真机由应用层注入按键事件源，否则剧本里
     * `GETKEYTRIGGERED(...)` 形态的跳出条件恒假（红 E：标题演示不可跳）。
     */
    inputState: InputState = IdleInputState,
    /**
     * 装置/调试器专用注入（CONTRACT-DBG1 收口接线）：非 null 时**脚本侧**语句输入全部走它
     * （立即应答面），[inputSink] 投递队列只留给 [ConsoleBeginHost] 的 BEGIN 面
     * （sweep/call 不驱动 BEGIN，二者互不影响）。null（缺省）= 生产装配原样吃
     * [inputSink]（QueueInputSink），行为零变化。
     *
     * 为什么必须在构造期给：[ProcessRuntime] 的 `ExecContext.input` 是构造期单例注入点
     * （`ProcessRuntime.kt` 的 `inputSink` 参数 KDoc），会话建成后换 sink 要重跑整个
     * execContext 装配，做不到事后换。sweep 的「立即应答 sink」规格此前因此落不进会话——
     * 真语料函数一撞 TINPUT 就永久 park 在 QueueInputSink 上（2026-10-25 全量 sweep 首跑实测）。
     */
    val inputSinkOverride: InputSink? = null,
    /**
     * 可写注入口（HOST-05，契约「二选一」的**选定生产装配面**）。null（缺省）=
     * [ConsoleBuiltinHost] 维持拒写口径（写请求计数、SAVETEXT 返回 0、OUTPUTLOG
     * 不落盘），golden/E2E 零差；非 null = SAVETEXT / OUTPUTLOG 真写到该目录
     * （spec: FunctionMethodCreator.cs:8010 `File.WriteAllText`、EmueraConsole.cs:3449
     * 同款），写路径限 [writeRoot] 根内（边界与读路径同源：normalize 后必须仍在根内，
     * 出根一律拒写返回 false）。读路径不受影响，恒以 [gameRoot] 为根。
     */
    writeRoot: Path? = null,
    /**
     * MOUSESKIP 弃用警告的投递面（SAVE-15b）。null（缺省）= 逐条收进 [mouseWarnings]；
     * 非 null = 逐条转发给宿主（真机 UI 要即时上屏时用），此时 [mouseWarnings] 恒空。
     *
     * 语义出处：MOUSESKIP 的弃用警告是 level 1、非错误、非向后兼容的**解析期**警告
     * （`MesSkipMethod.CheckArgumentType` → ParserMediator.Warn，FunctionMethodCreator.cs:3635-3638；
     * 收录条件 ParserMediator.cs:132，出厂阈值 ConfigData.cs:76 = 1、语料 `emuera.config:32`
     * 为 0 ⇒ 默认配置下可显示）。移植侧解析/装载通道面归邻路（save15a）所有，投递点
     * 仍在**求值期**（MouseMethods.kt 的 MOUSESKIP 注册体）——频率口径因此与原版不同：
     * 原版按**出现次数**（装载时逐处一次、与是否执行无关），这里按**执行次数**，
     * 差异登记见 MouseMethods.kt 的 TODO(verify)。
     */
    mouseWarnSink: ((String) -> Unit)? = null,
    /**
     * 字形掩码宿主透传（M11-E 遗留项②；接口签名由 CONTRACT-M11-E §4 钉死，定义在
     * `core/host/GlyphMaskHost.kt`）。null（缺省）= 未接线，GDRAWTEXT 维持 M11-D 的
     * 「推进盒」形态（消费点 `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost` 判 null）；
     * 非 null = 装进第 2 步的 [RootedBuiltinHost]，经 [ConsoleBuiltinHost] 的
     * `GlyphMaskHostProvider by delegate` 转发后透出给函数层，**全程同一实例**
     * （本类不复制、不改写；判据④，用例 `GlyphHostWireTest`）。真栅格化实现是
     * platform 路的设备侧宿主（`AndroidGlyphMaskHost`），由应用层在构造会话时喂进来。
     */
    glyphMaskHost: GlyphMaskHost? = null,
    /**
     * 装配期分段读数回调（性能诊断用；null = 不采样，既有调用点零改动）。
     *
     * 回调只在**段结束后**调用一次，段名以 `session.` 开头。调用方（`GameHost`）接到
     * `LoadTrace.mark`：它按「距上一段的耗时」记账，所以这里不能改打在段开始前。
     * 回调在本构造线程同步执行；实现方不要在里面做 GC / 大分配。
     */
    private val stageMark: ((String) -> Unit)? = null,
    /**
     * 屏幕**设备像素**两条（`CLIENTWIDTH` / `CLIENTHEIGHT` 的注入端，消费点
     * ConsoleQueryMethods.kt:131/136）。缺省 = 原版出厂窗口值
     * （WindowX 760 / WindowY-LineHeight 461，spec: ConfigData.cs:56,57,67），
     * headless 与 PC 基准零行为差；真机由 GameHost 按控制台实测值注入
     * （缺省 760 会把状态页/体型图的像素换算缩到 1/3）。
     *
     * 这两条是**设备像素**，但交给脚本的读数不是：语料把 `CLIENTWIDTH()` 当脚本 px 用
     * （拿它开 CG 画布），渲染层再按 `设备字号 / 配置字号` 折设备像素，所以会话把这两条
     * 经 [ScriptPixels] 折回脚本坐标系后才给函数层（口径见 [ScriptScreenState] 的类文档）。
     * 折算比例的另一半来自 [deviceFontPixelSize]。
     *
     * **字号不在这份注入里**（2026-10-29 修正）：`builtinHost.fontSize` 一律取会话自己算出的
     * 生效配置字号（[scriptFontPixelSize]），宿主喂不进来。为什么非不可喂，写在
     * [scriptFontPixelSize] 的 KDoc（一句话：语料的按钮网格用 `GETCONFIG("フォントサイズ")`
     * 当尺，量宽若换尺，一格就同时对不齐一次，攒到一行十几个就整排错位）。
     * [deviceFontPixelSize] 是另一回事：它是**渲染层解算出的设备字号**（像素），只当折算比例
     * 用，从不进 `GETCONFIG("フォントサイズ")` / `builtinHost.fontSize` 的读数。
     *
     * **只作为初值**（LIVE-REFLOW）：两条像素装进 [screenState] 交给 [builtinHost]，
     * 之后窗口尺寸变化由 [resizeConsole] 就地更新，注入参数不再参与读数。
     */
    clientWidthPx: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    clientHeightPx: Long = BuiltinHost.DEFAULT_CLIENT_HEIGHT,
    /**
     * 渲染层解算出的**设备像素字号**（`ScreenLayout.resolve` 的 `fontPixelSize`）：
     * 把 [clientWidthPx] / [clientHeightPx] 折回脚本 px 的比例分母，见 [ScriptPixels]。
     *
     * 0（缺省）= 未知 —— 折算恒等，headless / PC 基准 / 既有装置读数逐字不变。真机由
     * GameHost 在会话装配前按 `view.cellMetrics().fontPixelSize` 取走；视口尺寸变化时由
     * [resizeConsole] 跟随新版面更新（转屏会换字号档）。
     *
     * **不是** [scriptFontPixelSize]：后者是脚本坐标系的基准（生效 `Config.FontSize`），
     * 两者只在 1:1 版面上同值。混用会把 2026-10-29 的指令按钮错位病灶重新引回来。
     */
    deviceFontPixelSize: Int = 0,
    /**
     * 带样式的分段量宽（FN-04 core）：`(请求) -> 像素宽`，生产由应用层把
     * `AndroidTextMeasure.measurePixels` 包一层传进来（字体族 / styleMask / charSpace）。
     * null = 未接线 → `HTML_STRINGLEN` 家族回落旧纯文本回调 → 等宽近似，
     * headless 与既有基准读数零变化。
     */
    textMeasureRun: ((TextMeasureRequest) -> Long?)? = null,
    /**
     * **字体感知排版尺**（AA 画/老虎机字符画错位修复，2026-11）：控制台折行与按钮列位的
     * 半角格宽度来源。`SETFONT` 在原版是「换字形**并**换尺」（每段宽度 =
     * `StringMeasure.GetDisplayLength(Text, Font)` 的 GDI+ 实测，
     * spec: `StringMeasure.cs:42-72` + `ConsoleStyledString.cs:25,82`）；移植版此前
     * 折行恒走静态 CP932 表，块元素 `▂`/`▉` 被算成 2 格、比例字体的微调空格被放大，
     * 红魔馆老虎机与觉的 AA 画因此在屏上错位/误折行。
     *
     * 真机由 `GameHost` 把 `CanvasConsoleView.fontDisplayWidth`（assets sidecar 的真实
     * advance 尺）递进来 —— 它与绘制侧推进是同一只实例；缺省
     * [StaticFontDisplayWidth] = 接线前的静态表，headless 与既有装置读数**逐值不变**。
     * 契约与退化纪律见 [FontDisplayWidth]。
     */
    fontWidth: FontDisplayWidth = StaticFontDisplayWidth,
    /**
     * 存档目录迁移询问通道（M11-A-WIRE-2）。`USE SAV FOLDER` 由 NO→YES 且根目录还有旧档时，
     * [FileSystemOps] 会选「要不要搬」；null（缺省）= [SaveFolderMigration.AUTO_ACCEPT]，
     * 与旧版**逐字同解**（静默搬）。真机由应用层接一个阻塞式弹框（答 No 时文件原地不动，
     * 且原版因目录已先建好而永久不再问，spec: `Config.cs:482-485` + `:564-567`）。
     *
     * 时机：本值在会话构造期（[FileSystemOps.init]）同步调用一次，调用线程 = 装配线程
     * （真机是 VM 线程）。实现方可阻塞到用户作答，但不得在 UI 线程上调用。
     */
    saveMigrationPrompt: ((title: String, message: String) -> Boolean)? = null,
    /**
     * 死循环告警的宿主通道（ENGFIX-4），契约见 [com.eratw.port.core.code.VmHost.askInfiniteLoop]。
     *
     * null（缺省）= [InfiniteLoopAnswer.NoUi]：headless / 装置与改造前逐条一致。
     * 真机由 `GameHost` 接一个 AlertDialog + `CountDownLatch` 的阻塞式实现：
     * 本回调只在 **VM 线程**、两条语句之间同步调用，实现方必须阻塞到玩家作答为止，
     * **不得在 UI 线程上调用本会话**；不得抛出（抛异常会被收口成脚本终局，
     * [ProcessRuntime] 另有 runCatching 兜底）。
     */
    infiniteLoopPrompt: ((InfiniteLoopAlert) -> InfiniteLoopAnswer)? = null,
    /**
     * **引擎级函数屏蔽表**（[EngineFunctionPolicy]）：命中名单的函数按「空体 `RETURN 0`」
     * 处理，与调用形态无关（静态 `CALL` / 动态 `CALLFORM` / `CALLF` / 尾跳 / 根级 API）。
     *
     * 缺省 [EngineFunctionPolicy.DEFAULT_DISABLED]（含 `BADAPPLE`）= 出厂口径：任何人的语料
     * 都不必再手工注释 `CALL BADAPPLE`（语料不进仓，上游那份那一行可能是活的）。
     * 设置页把「标题彩蛋」开关关掉时宿主传 `emptySet()`。
     *
     * 命中结果见 [stubbedFunctions]（宿主用来上屏/记日志，屏蔽本身是静默的）。
     */
    disabledFunctions: Set<String> = EngineFunctionPolicy.DEFAULT_DISABLED,
    /**
     * mods 补丁目录（R7-C4）。null（缺省）= 不装载补丁，装配行为与今天逐位一致（I1）；
     * 非 null = 构造期走 [ModPatchLoader.load]（I5 全或无：任一补丁失败整体回退到无补丁
     * 模式，结果见 [patchRolledBack] / [patchLoadFailures]），overlay 后的 Program 替换
     * [program] 进运行期，补丁函数尾段拼进 FunctionTable、标签/数据节拼进 SidecarTables。
     * 目录不存在或无补丁候选 = 行为与今天逐位一致。
     */
    val modsDirectory: Path? = null,
    /**
     * base `program.erbc` 的 SHA-256（64 位小写十六进制），补丁身份校验（I3）的比对对象。
     * [modsDirectory] 非 null 时**必须**显式传入：Program 本身不记哈希（见
     * `patchBaseIdentity` 的 KDoc），宿主装载 .erbc 时手里有字节，由它算出传入。
     * null 且 [modsDirectory] 为 null = 与今天一致（该参数无消费点）。
     */
    val baseProgramSha256: String? = null,
    /**
     * LIVE-REFLOW 开关：false（缺省）= 现有「会话期宽度冻结」语义逐字不变，
     * 横屏/竖屏的既有画面路径都走原分支；true = [LineBufferConsoleSink] 保留 journal，
     * 允许 [resizeConsole] 在窗口尺寸变化时重排历史并更新脚本可见读数。
     */
    val reflowConsole: Boolean = false,
    /**
     * 键入回显（ECHO-INPUT）的引擎档位，正式定义见 [EchoInputMode]。
     *
     * - [EchoInputMode.REPLICA]（**core 层缺省**）= 原版 `EmueraConsole.cs:1280-1282`
     *   那行回显照打且上屏；桌面装置、E2E golden 与全部既有定向用例都走这一档，逐字不变。
     * - [EchoInputMode.SUPPRESSED] = **玩家侧缺省**：引擎在输入交付点就不产生那行显示行，
     *   只由控制台行账本记一笔、并折算随后的 `deleteLine`。屏上没有数字，也没有帧差。
     *
     * 档位在会话装配期定死：输入 sink 按档位决定回显回调收到文本还是 null，控制台按同一
     * 档位决定打印还是记账。壳层设置页改完重开一局生效（与 `reflowConsole` 同款口径）。
     */
    val echoInputMode: EchoInputMode = EchoInputMode.REPLICA,
    /**
     * 交给配置回填用的**脚本 px 版面宽**（R5.1；读数定义见 [BuiltinHost.windowWidthScriptPx]）。
     *
     * 0（缺省）= 未接线/未布局 ⇒ 回填层不写，`WindowX` 保持链读数（headless / 既有装置
     * 逐值不变，R5.3）。
     *
     * 真机由 `GameHost` 喂 `CanvasConsoleView.windowWidthScriptPx()`：View 把**实际**排版用的
     * 版面宽按 `CellMetrics.scriptPixels` 同一折算比反折成脚本 px，装配在三层链**之后**把它
     * 回填成 `WindowX`（原版 MainWindow.cs:726 的「开窗后回填实际客户区宽」语义；链前播种
     * 会被文件值/裸缺省 760 盖住，0.2.43 的「同源」就断在这一步）。核心
     * `drawableWidth = WindowX - max(2, FontSize/6)` 折算到设备像素后与屏上可绘制宽同源
     * （差不到一格；判据见计划 C-WINDOW-SEED 的同源验收算式）。
     */
    windowWidthScriptPx: Long = 0L,
) {

    /**
     * 会话期控制台尺寸变更（LIVE-REFLOW）：窗口尺寸变化时由宿主（UI 线程）调用。
     *
     * ## 语义（逐条对应冻结契约 CONTRACT-LIVE-REFLOW §4）
     *
     * 1. [reflowConsole] = false（缺省）⇒ 返回 false 且**不碰任何状态**（连参数校验都不做：
     *    关闭态必须是彻底 no-op，含 `columns <= 0` 这类非法实参 —— 关态的调用方本就不该存在）。
     * 2. `require(columns > 0)`、像素宽高非负（负像素是宿主算错，直接炸而不是静默记 0）。
     * 3. 宽度是否真的变了由 [LineBufferConsoleSink.resize] 判定并执行重排，本类**不另判一次**
     *    （同宽/仅高度变化 ⇒ sink 返回 false，历史行一个字都不动）。
     * 4. 无论是否重排，像素宽高（以及本次解算出的设备字号）都写进 [screenState] ⇒
     *    `CLIENTWIDTH()` / `CLIENTHEIGHT()` 的下一次求值即新值（原版这两条是控件活尺寸，
     *    EmueraConsole.cs:232/234）。读数经 [ScriptPixels] 折成脚本 px 后交给脚本，
     *    转屏换了字号档时那一格也必须一起更新，否则比例分母停在旧档。
     * 5. 重排发生时同步 `DRAWLINESTR` 伪变量背衬：`store.drawLineStr = console.stBar`
     *    （原版 PrintBar 与 DRAWLINESTR 读同一个 stBar 字段，EmueraConsole.cs:3392/3417 +
     *    VariableData.cs:976-986；stBar 按宽度成型于 :3441 `setStBar`）。`optionsDirty` /
     *    revision / serial / lineNo 的记账全部由 sink 负责，本类不插手。
     * 6. 返回 sink 的重排结论（`false` = 开关关闭 / 宽度未变 / 无需重建，且前后无可见差异）。
     *
     * ## 明确不做
     *
     * - **不重算 `#DIM` 的 `GETLINESTR` 折叠**：装载期折叠（[SystemVariableBootstrap.install]
     *   的 `consoleWidth`，实现 `vars/UserVariables.kt` 的 `getLineStr`）用的是**半角格**宽度，
     *   而折叠结果是变量池里的**声明期常量** —— 原版同样只在装载期算一次，运行中改窗口不会
     *   回写已声明的布局变量。这里重算等于改语义（见 `GameSession.kt` 装配段 4/4b 的两处注释）。
     * - **不动 Config 层**：`PRINTCLENGTH` / `PRINTCPERLINE` / 字号 / MaxLog 一个都不改
     *   （它们来自 `emuera.config` 条目表，属于下一次会话装配才重读的那一份）。
     *
     * ## 线程
     *
     * 调用方是 UI 线程（窗口尺寸变化回调）；与 VM 线程的并发由 sink 自己在
     * `synchronized(displayLines)` 内处置（见 `LineBufferConsoleSink.resize`）。
     * **不要**从 VM 步进线程内调用（会与正在执行的语句抢同一份显示行）。
     *
     * @return true = 历史行已按新宽度重排；false = 未重排（见上面第 6 条）。
     */
    fun resizeConsole(
        columns: Int,
        widthPx: Long,
        heightPx: Long,
        deviceFontPixelSize: Int? = null,
    ): Boolean {
        if (!reflowConsole) return false
        require(columns > 0) { "控制台列数必须为正：$columns" }
        require(widthPx >= 0 && heightPx >= 0) { "屏幕像素不能为负：$widthPx x $heightPx" }
        require(deviceFontPixelSize == null || deviceFontPixelSize >= 0) {
            "设备字号不能为负：$deviceFontPixelSize"
        }
        return applyResizeOutcome(console.resize(columns), widthPx, heightPx, deviceFontPixelSize)
    }

    /**
     * [resizeConsole] 的**后置段**：像素读数落账 + 重排后同步 `DRAWLINESTR` 背衬。
     *
     * internal 只为定向单测：L1 路的 `LineBufferConsoleSink.resize` 在本次工作树里仍是
     * 骨架（`reflowEnabled = true` 时显式抛错），`reflowed = true` 这一支在本树内无法
     * 端到端产出；拆出这一段后，单测可以用真实 [store] / [console] 断言「重排后必须换串」
     * 这条生产语句，而不是把分支留成未验证的死角。生产装配只从 [resizeConsole] 进来，
     * 语义上不存在第二条入口。
     *
     * @param reflowed [LineBufferConsoleSink.resize] 的结论（宽度是否真的变了）
     * @param deviceFontPixelSize 本次版面解算出的设备字号；null = 沿用现值（既有装置只报像素）
     */
    internal fun applyResizeOutcome(
        reflowed: Boolean,
        widthPx: Long,
        heightPx: Long,
        deviceFontPixelSize: Int? = null,
    ): Boolean {
        screenState.update(widthPx, heightPx, deviceFontPixelSize)
        if (reflowed) store.drawLineStr = console.stBar
        return reflowed
    }

    /**
     * 脚本可见的屏幕像素（`CLIENTWIDTH()` / `CLIENTHEIGHT()` 的活数据源）。
     *
     * 初值 = 构造参数 [clientWidthPx] / [clientHeightPx]（真机 = 内容区实测值，headless =
     * 出厂 760 x 461 ⇒ 读数与未接本类时逐字相同）；[resizeConsole] 之后跟着窗口尺寸走。
     * 折算比例的两端：设备字号 = [deviceFontPixelSize]，脚本字号由**取数侧**每次现传
     * （[scriptFontPixelSize]，生效配置字号），本属性只存设备侧的原始读数 —— 设备字号与
     * 脚本字号都不在 `init` 之后才补，故**构造即可读**。
     * 装配期即交给 [builtinHost] 的 `screenState`（同一实例，不复制），函数层的自定义
     * getter 每次求值现读（见 [ConsoleBuiltinHost.clientWidth]）。
     */
    val screenState: ScriptScreenState =
        ScriptScreenState(clientWidthPx, clientHeightPx, deviceFontPixelSize)

    init {
        require(metadata.functions.isNotEmpty()) {
            "侧车缺少函数元数据（functions 段为空）：没有它 FunctionTable 认不出任何函数，" +
                "begin(TITLE) 必然走「目标不存在」分支。请用 erbcc 的 compile 子命令重新落盘旁路文件。"
        }
        val maxIndex = metadata.functions.maxOf { it.index }
        require(maxIndex < program.functions.size) {
            "侧车与 Program 不配对：函数下标 $maxIndex 越过 program.functions（${program.functions.size}）。" +
                "两种可能：侧车不是这份 program.erbc 的（同名不同后缀取错），或产物被单独替换过。"
        }
    }

    // ------------------------------------------------------------------
    // 控制台 / 输入（宿主读这两个）
    // ------------------------------------------------------------------

    /**
     * 开局控制台（M4 渲染层的数据源）：`snapshot()` / `lines()` / `revision` / `buttons()` 都在这。
     * 文字流唯一出口 = [ProcessRuntime] 的 console 桥（契约 C6），本类不另开输出路径。
     *
     * 字体名与会话级配置取自 `<gameRoot>/emuera.config`（原版 `Program.ExeDir + "emuera.config"`，
     * `ConfigData.cs:33`）：`FONT NAME`（语料 `:21` 为「黑体」）与 `MAX HISTORY LOG LINES`
     * （语料 `:18` 为 900）。配置读不到时用 [LineBufferConsoleSink] 的缺省。
     */
    val console: LineBufferConsoleSink

    /** 输入队列：宿主（UI/触屏/宏）把「回车提交的原始文本」投进来，脚本侧阻塞等它。 */
    val inputSink: QueueInputSink = QueueInputSink()

    /**
     * BEGIN 状态机的宿主面：四个 `await*` 全部接到 [inputSink] 的同一队列，`writeLine` 走 [console]，
     * 等待入口的脚本行身份走 [beginWaitScriptLineToken] + [beginWaitPageIdentity]
     * （OBS2-L5：重等后屏上旧按钮保持可点）。
     */
    val beginHost: ConsoleBeginHost

    /** 内建函数宿主（文件 IO / 时钟 / 存档槽探测 / 配置覆盖）；写请求在这里被拒并计数。 */
    val builtinHost: ConsoleBuiltinHost

    /** CSV 总装结果；null = `<gameRoot>/CSV` 缺失（系统变量按缺省注册表运行）。 */
    val gameData: GameData?

    /** 变量池（宿主可直接读，例如设置页的数据层）。 */
    val store: VariableStore

    /** 运行期（语句/表达式/CALL 家族/BEGIN 状态机）。 */
    val runtime: ProcessRuntime

    /**
     * [runtime] 的**就绪标记**（跨线程读，`@Volatile`）：装配完成前为 null，
     * [setBackgroundPaused] 据此决定「直接转发」还是「先记住意图」（消除启动期竞态）。
     * 构造完成后与 [runtime] 是同一实例。
     */
    @Volatile
    private var runtimeRef: ProcessRuntime? = null

    /** [setBackgroundPaused] 的握手机（与 [runtimeRef] 的发布/消费配对，见该方法）。 */
    private val backgroundPauseLock = Any()

    /** runtime 尚未就绪时收到的暂停意图（true/false）；装配完成后立即应用并清空。 */
    private var pendingBackgroundPause: Boolean? = null

    /**
     * 实际被**引擎屏蔽**的函数名（[EngineFunctionPolicy] 命中 `program.functions` 的结果；
     * 空 = 一个都没命中，例如语料里根本没有 `@BADAPPLE`，或宿主关掉了开关）。
     *
     * 屏蔽是静默的（调用点直接得 0/`""`，语料侧没有任何可观察痕迹），宿主用它上屏提示或
     * 记日志，别让「标题没有彩蛋」变成没人说得清的悬案。
     */
    val stubbedFunctions: List<String> get() = runtime.stubbedFunctionNames

    /** 全局 `#DIM` 装载结果（`declared` / `failures` / `constants`）。 */
    val globalDimInstall: SystemVariableBootstrap.Result

    /**
     * 侧车在运行期的投影：**只剩**标签表与数据节表（`#DIM` / 宏表 / `fileByKey` 已在装配期
     * 消费完并被释放，见类 KDoc 的「侧车生命周期」）。数据节的语句实例索引在这里**惰性**建立。
     */
    internal val sidecar: SidecarTables

    /** 私有 `#DIM` 注册失败清单（`函数键/变量名` → 消息）；成功条数见 [privateDimDeclared]。 */
    val privateDimFailures: List<String>

    /** 私有 `#DIM` 注册成功条数。 */
    val privateDimDeclared: Int

    /**
     * R7-C4：mods 补丁装载的原始结果（装配期一次；公开读数经 [loadedPatches] 等投影）。
     * null = [modsDirectory] 未启用（与今天一致）。
     */
    private val patchLoadResult: ModPatchLoader.Result?

    /**
     * R7-C4：补丁函数的运行期元数据尾段（FunctionTable 追加的输入，见
     * [buildPatchFunctionMetas]）。空 = 无补丁或整体回退。internal 只为定向单测能直接
     * 断言 argInt/argStr 分桶与装载序（I7 的函数级读数面）。
     */
    internal val patchFunctionMetas: List<FuncMeta>

    /**
     * R7-C4（I7）：已装载补丁清单（补丁名 + 函数数 + 补丁段指令数 + 函数键）。
     * DevTools 的「已装载补丁」页（UI 面归 C5）吃这一份；整体回退时为空。
     */
    val loadedPatches: List<ModPatchLoader.LoadedPatch>
        get() = patchLoadResult?.loadedPatches ?: emptyList()

    /**
     * R7-C4（I7）：装载失败清单。条目 = `补丁名: 原因`，原因自带补丁头内行号/pc/字段名
     * （补丁头解析错带「第 N 行」、身份不符带「字段 期望/实为」、补丁体错带 pc）。
     * 非空 ⇔ [patchRolledBack]（I5：任一失败整体回退）。
     */
    val patchLoadFailures: List<String>
        get() = patchLoadResult?.failures ?: emptyList()

    /** R7-C4（I7）：装载期非致命告警（如补丁容器缺形参名 ⇒ ARG/ARGS 桶不绑定）。 */
    val patchLoadWarnings: List<String>
        get() = patchLoadResult?.warnings ?: emptyList()

    /** R7-C4（I5）：补丁装载是否整体回退（true = 补丁全部未生效，会话按无补丁模式运行）。 */
    val patchRolledBack: Boolean
        get() = patchLoadResult?.rolledBack ?: false

    /**
     * ERD 装载期的告警清单（`erd名#下标位` 前缀的挂表失败文案 + 裸文案的 level 1 重名告警）。
     *
     * spec: ErhLoader.cs:328-332 —— 原版逐条 `#DIM` 套 try/catch，重键/重复登记降级成
     * level 2 告警并继续装载其它行；移植侧同形（见 `UserKeywordTables.attach`），
     * 所以这里**非空不代表装载失败**，只代表那个变量的字符串下标表没挂上。
     * 另一类条目来自 `Config.CheckDuplicateIdentifier`（UserDefinedVariableData.cs:269-272）：
     * `#DIM` 声明名撞已登记 ERD 表的关键字 ⇒ level 1 告警（ConstantData.cs:795-806），
     * 官方文案自带变量名与表名、**没有** `erd名#下标位` 前缀，表照挂。
     */
    val erdWarnings: List<String>

    /**
     * MOUSESKIP 弃用警告清单（SAVE-15b 的缺省投递面；求值期逐条追加，投递点 =
     * MouseMethods.kt 的 MOUSESKIP 求值体）。宿主显式传 mouseWarnSink 时本清单恒空。
     * 语义与频率口径（解析期 vs 求值期）见 [mouseWarnSink] 参数 KDoc。
     */
    val mouseWarnings: List<String> get() = mouseWarnBuffer

    /** [mouseWarnings] 的后备缓冲（装配期经 [mouseStateOf] 接到 MouseState.warnRecorder）。 */
    private val mouseWarnBuffer = ArrayList<String>()

    /**
     * CALLSHARP 的**装载趟警告**（CSHARP-WIRE C3，2026-11-06）：宿主装配段跑完
     * core/fn 的 `prefillSharpLinks` 后回填的「No native method X found」清单
     * （官方英文原样，`FunctionIdentifier.cs:1204`，ADR-005；顺序 = 语句池序 = 原版装载序）。
     *
     * 形态对齐原版 `ParserMediator.cs:143-158` 的 warningList：装载期记、装载完一起交出、
     * **之后不重发**（预填对已裁决行幂等跳过）。呈现面 = 宿主的既有日志通道
     * （GameHost 装配段 Log.w）；本清单是会话读数面（同 [erdWarnings] / [mouseWarnings]
     * 的形状），给取证/诊断页直接读，不必再扫一遍语句池。
     */
    val sharpLoadWarnings: List<String> get() = sharpWarnBuffer

    /** [sharpLoadWarnings] 的后备缓冲（宿主装配段经 [recordSharpLoadWarnings] 回填）。 */
    private val sharpWarnBuffer = ArrayList<String>()

    /** 宿主装配段把 `prefillSharpLinks`（core/fn）的返回值交进来（C3 的会话读数面）。 */
    fun recordSharpLoadWarnings(warnings: List<String>) {
        sharpWarnBuffer.addAll(warnings)
    }

    /**
     * 会话配置（原版 `ConfigData.Instance` 的只读快照来源）。
     * 缺省构造 = 从 [gameRoot] 重放完整配置链并叠 `_Replace.csv`
     * （`ConfigData.cs:479-494` + `Process.cs:240-270`）。
     */
    val configStore: ConfigStore

    /**
     * 脚本 `px` 的**基准字号** = 生效 `Config.FontSize`（`Config.cs:431-435` 夹取后的值）。
     *
     * 渲染侧拿它把 `<img>` / `<shape>` 与 `PRINTIMG` / `PRECT` / `PSPACE` 的 `px` 形态折成设备
     * 像素，与百分比形态共用一把尺（换算口径与其踩坑记录见 render 模块
     * `glyph/CellMetrics.kt` 的 `scriptPixels`；本模块看不见那个类，只把值报出去）：
     * 语料 `emuera.config:22-23` 把
     * `FONT SIZE` 与 `LINE HEIGHT` 都写成 18，所以 `height='180px'` 与 `height='1000%'` 在 PC 上是
     * 同一个 180 像素，而剧本两侧都按 `ceil(图高 / 一行の高さ)` 预留空行 —— 基准字号必须由**会话
     * 自己**报出，不能让渲染侧猜常量（用户把字号改成 24 时尺也要跟着变）。
     *
     * ## 第二条职责：内建函数家族的字号尺（2026-10-29 加）
     *
     * `builtinHost.fontSize` 也取这一个值，**不接受宿主注入的真机 page-fit 字号**。因为
     * `HTML_STRINGLEN(…, 1)` / `HTML_SUBSTRING` / `HtmlStrictParser` 的 `px` 全在这一把尺上走，
     * 而语料那层按钮网格（`ERB/魔改内容/PRINT系/HTML_PRINT_Components.ERB` 的
     * `HTML_PRINTC_MANAGER`）算的正是 `CELL_WIDTH_PX = PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2`
     * —— 它拿 `GETCONFIG` 的**配置字号**当尺，再拿 `HTML_STRINGLEN` 的读数去比、把差值写成
     * `<shape type='space' param='Npx'>` 补回来。量宽与补齐必须是同一把尺，否则：
     * 喂真机 page-fit 41px 时「移动[400]」量到 184（9 个半角 × 41/2），对着 225 只差 41 ——
     * 于是只补 41 脚本 px，而这 41 再被折算一次（× 41/18 = 93 设备像素 = 5 格），整枚只占
     * **14 格**；正确的尺上同一枚补齐到 **25 格**（`render/.../QolButtonGridClosureTest`
     * 两条读数都在，反证那条钉住 14）。每个按钮少占约一半宽度、补齐还带非整数的取整误差，
     * 一行里按钮越多误差攒得越多，端口折行的落点又和脚本自己算的落点不一致 ⇒
     * 指令列表（会话 / 扫地 …）整排平移错位（2026-10-29 玩家反馈）。
     * PC 上「配置字号」与「屏幕像素字号」本来就是同一个数（`StringMeasure.cs:42-72` 用的
     * 就是 `Config.FontSize`），所以这一条不是新增口径，是把注入错的那半改回原版那把尺。
     *
     * 顺带一条同源推论（同一处修正顺手治好，无需另改代码）：脚本把 `HTML_STRINGLEN(…,1)`
     * 的读数当长度写回 `<shape type='rect' param='0,Npx'>` 那一类画法（下划线 / 进度条盖住
     * 某段文字）时，端口会把这个 px 折算成设备像素 —— 读数若已是设备像素就等于**放大两次**，
     * 条子比它要对齐的文字长 2.3 倍；读数交回脚本 px 这一把尺，折算一次正好等于屏上宽度。
     *
     * ## 机型自适应（2026-10-29 复查补的读数）
     *
     * 补齐段落的落地格数 = `ceil(补齐px × 机型字号 / 18 ÷ (0.52 × 机型字号))` —— **机型字号在分子
     * 分母同时出现、整个约掉**，所以换任何字号档（30px 到平板那档 60+）
     * 落点都不变；会被机型影响的是**随包字体的半角前进量**（这条尺上 9 上下），语料这批指令
     * 标签（8–22 半角）的闭合区间约 8.94–9.30，黄金基线量到的 9.03 落在正中。区间外的最坏情况
     * 是少占**一格**，不再是从前那种「一枚差半格、一行攒成整排平移」。两条读数都钉在
     * `render/src/test/.../QolButtonGridClosureTest`（扫字号那一条与扫步进那两条）。
     *
     * TODO(verify)：本值是**夹取后**的字号（<8 抬到 8），而 `GETCONFIG("フォントサイズ")` 读的是
     * 条目**原值**（`ConfigMethods.kt:331-333` 不夹取）。语料 `FONT SIZE:18` 两者同值不受影响；
     * 只有把字号配成 <8 才会重新分叉（原版在这种非法配置下自己也分叉，ADR-005 不追）。
     */
    val scriptFontPixelSize: Int

    /**
     * 存档落盘策略（`SystemSaveInBinary` / `ZipSaveData` / `UseSaveFolder` 三项 + 文本格式口径）。
     * 见 [SaveConfig]；eratw 实机那三项与 [SaveConfig.ERATW] 同值（语料 `emuera.config:51/60/74`）。
     */
    val saveConfig: SaveConfig

    /** 装配进 [VariableStore.aux] 的存档接线参数（`CHKDATA` / `SAVENOS` 现读这一份）。 */
    val saveWiring: SaveWiring

    init {
        // 0) mods 补丁装载（R7-C4，CONTRACT-R7-C §2/C4）。必须在 #DIM 注册（第 5 步）、
        //    语句规范化（7b）与 ProcessRuntime（第 8 步）之前：overlay Program 要替换
        //    [program] 进运行期，补丁的私有 #DIM / 标签 / 数据节要拼进各装配段。
        //    I1：modsDirectory = null（或目录无候选）时 patchLoad 各段全空，
        //    effectiveProgram === program，以下每一处拼接都退化为「原样传入」。
        val patchLoad: ModPatchLoader.Result? = if (modsDirectory != null) {
            val sha = requireNotNull(baseProgramSha256) {
                "modsDirectory 非空时 baseProgramSha256 必须显式传入：补丁身份校验（I3）要比对 " +
                    "base program.erbc 的 SHA-256，而 Program 本身不记哈希" +
                    "（宿主装载 .erbc 时手里有字节，由它算出传入）。"
            }
            ModPatchLoader.load(modsDirectory, program, sha).also {
                stageMark?.invoke("session.patches")
            }
        } else {
            null
        }
        val effectiveProgram: Program = patchLoad?.program ?: program
        if (effectiveProgram !== program) {
            // overlay 产物的扁平通道开关是构造缺省（true），不会自动继承 base 装载路径的
            // 开关；不同步的话 Android 生产（flatStatements = false，第 8b 步按 false 建
            // 数据节反查表）会按 true 走错通道。overlay 是本模块构造的新实例，同步是
            // 装配期一次性写，不影响 base（I4）。
            effectiveProgram.flatStatementsEnabled = program.flatStatementsEnabled
        }
        val patchMetas: List<FuncMeta> =
            buildPatchFunctionMetas(effectiveProgram, program, patchLoad, metadata.functions)
        val patchLabels: Map<String, Map<String, Int>> = patchLoad?.labels ?: emptyMap()
        val patchDataSections: Map<Int, List<List<Expr>>> = patchLoad?.dataSections ?: emptyMap()
        patchLoadResult = patchLoad
        patchFunctionMetas = patchMetas
        // I1：段名轨迹只在启用 mods 时多两段（session.patches / session.patchwire）；
        // modsDirectory = null 时轨迹必须与今天逐字一致（GameSessionStageMarkTest 钉死）。
        if (patchLoad != null) stageMark?.invoke("session.patchwire")

        // 1) 游戏数据（缺失 = null，不阻断）
        gameData = GameDataLoader.load(gameRoot.resolve(CSV_DIR_NAME))
        stageMark?.invoke("session.csv")

        // 2) 配置先读一次：字体名、日志行数与 PRINTC 两项都影响控制台构造，故在控制台之前取。
        //    与 ConsoleBuiltinHost 同源（同一个 RootedBuiltinHost 实例随后交给它做委托）。
        //    M11-E hostwire：glyphMaskHost 在同一构造点喂入（判据④的装配行，其余结构不动）。
        val rooted = RootedBuiltinHost(gameRoot, glyphMaskHost = glyphMaskHost)
        val config = rooted.configOverrides
        // 会话条目表在这里一次定死（原版 ConfigData.Instance 只在进程启动时装载一次，
        // spec: `Program.cs:95` 的 LoadConfig + `ConfigData.cs:494` 的 SetConfig；设置页改动
        // 落盘后由**下一次**会话构造吃到，见 SettingsActivity 的口径）。三条通道共用它：
        // 控制台（PRINTC 两项 / 字体 / 日志行数）、存档（SaveConfig / SAVENOS）、语句层。
        this.configStore = configStore ?: ConfigStore().also { rooted.applyChainTo(it) }
        // R5.1 收口（0.2.43 同源补完）：`WindowX` 的**链后回填** —— 原版按配置开窗后把
        // **实际**客户区宽回填 `Config.WindowX`（MainWindow.cs:726），移植侧的对应读数是
        // [windowWidthScriptPx]（View 实际版面宽的同尺逆折算）。它必须落在三层链之后：
        // 链前播种会被文件值/裸缺省 760 盖住（原版方向），core 的 `drawableWidth` 就停在
        // 760 脚本 px ≈ 42 格而屏上是 139 列 —— `MAXWIDTH()` 掉进除零区（DEBUG.ERB
        // `CFLAG_DEBUG` 行561/575 的 `/45`、`/90`），HTML_PRINT 折行尺也与屏上分叉。
        // 外部传入 [configStore] 的装配者自己管配置，不回填（行为与接线前一致）。
        if (configStore == null) {
            ConfigMethods.backfillWindowWidth(this.configStore, windowWidthScriptPx)
        }
        val effective = EffectiveConfig.of(this.configStore)
        // 脚本 px 的基准字号（渲染侧折设备像素用；口径见 [scriptFontPixelSize] 的 KDoc）。
        // 取**生效值**而不是条目原值：`Config.cs:431-435` 把 <8 抬到 8，条目原值是个会被
        // 原版忽略的数，喂给尺子就会把图形整体缩到 1/3 以下。
        this.scriptFontPixelSize = effective.fontSize
        val fontName = config[CONFIG_FONT_NAME]?.trim()?.takeIf { it.isNotEmpty() }
            ?: LineBufferConsoleSink.DEFAULT_FONT_NAME
        val logLines = config[CONFIG_MAX_LOG]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: maxLines
        // 组长接线（M4-N）：`ButtonWrap` 是 `LineBufferConsoleSink` 的构造参数，此前生产点从不喂，
        // 于是语料 `emuera.config:38 BUTTON WRAPPING:YES`（出厂 false，spec: ConfigData.cs:83）
        // 永远不生效 ⇒ 按钮串在窄行上的折行口径与 PC 相反。取数与 `RootedBuiltinHost` 同源
        // （三层链快照的 `configOverrides`），解析走原版 bool 字面量口径（ConfigItem.cs:227-250）。
        val buttonWrap = config[CONFIG_BUTTON_WRAPPING]?.trim()?.let { parseBoolLiteral(it) }
            ?: LineBufferConsoleSink.DEFAULT_BUTTON_WRAP
        // 组长接线（NEG16）：DRAWLINE 文字与分隔线 stBar。原版在 Process init 里
        // `Config.SetReplace(ConfigData.Instance)` 之后立刻 `console.setStBar(Config.DrawLineString)`
        // （Process.cs:258-259），「空串回落 "-"」正是 SetReplace 的唯一改写（Config.cs:725-729）；
        // EffectiveConfig 不做 SetReplace 视图（其 KDoc 明言留给装配层）⇒ 在这里读生效条目并合回落。
        // 成型一次喂两处：控制台（DRAWLINE 语句 PrintBar，spec: EmueraConsole.cs:3388-3393）
        // 与变量池的 DRAWLINESTR 伪变量背衬（getDefStBar，spec: EmueraConsole.cs:3437-3441）——
        // 原版两处读同一个 stBar 字段，本层也必须同串。
        val drawLineUnit = effective.source.itemOf(ConfigKey.DrawLineString).string().ifEmpty { "-" }
        // W8 刀四：`Config.ForeColor`（「文字色 / TEXT COLOR」，ConfigData.cs:68 出厂 192,192,192）。
        // 原版在控制台构造期把它写进两个 StringStyle（EmueraConsole.cs:206-208），RESETCOLOR
        // 的落点也是它（FunctionIdentifier.cs:1501）⇒ 与字体名/行数是同一条装配规矩。
        // 解析走条目本体（effective.color ⇒ ConfigItem.cs:252-274 的 tryStringsToColor 口径），
        // 语料 `emuera.config:24` = 192,192,192 与工厂常量同值 ⇒ 基准读数不变。
        val foreColor = effective.color(ConfigKey.ForeColor)
        console = LineBufferConsoleSink(
            clientWidth = consoleWidth,
            // `Config.PrintCLength`（spec: `Config.cs:327` 读静态字段）：显式参数优先，
            // 缺省吃条目值（注册缺省 25，spec: `ConfigData.cs:64`；语料 `emuera.config` 同值）。
            printCWidth = if (printCWidth > 0) printCWidth else effective.int(ConfigKey.PrintCLength),
            maxLines = logLines,
            defaultFontName = fontName,
            buttonWrap = buttonWrap,
            frameGateEnabled = frameGateEnabled,
            barUnit = drawLineUnit,
            defaultForeColor = (foreColor.r shl 16) or (foreColor.g shl 8) or foreColor.b,
            // CONFIG-WIRE U5 追加三色（与 ForeColor 同一条装配规矩，都是 EmueraConsole 构造期
            // 从 Config 静态字段取的初值）：解析同走 effective.color（ConfigItem.cs:252-274）。
            //   * defaultBackColor —— EmueraConsole.cs:200 bgColor 初值（ConfigData.cs:69 出厂
            //     0,0,0；语料 `emuera.config:25` 同值 ⇒ 基准读数不变）。
            //   * focusColor —— 按钮焦点绘制 + GETFOCUSCOLOR 的源（ConfigData.cs:70 出厂
            //     255,255,0；语料 `emuera.config:26` 同值）。
            //   * logColor —— 履历绘制前景（ConsoleStyledString.cs:115/:153；ConfigData.cs:71
            //     出厂 192,192,192，语料 `emuera.config:27` = 128,128,128 **不同值** ⇒ 必须吃
            //     生效条目，渲染壳层经 feed 链自取）。
            defaultBackColor = effective.color(ConfigKey.BackColor).let { (it.r shl 16) or (it.g shl 8) or it.b },
            focusColor = effective.color(ConfigKey.FocusColor).let { (it.r shl 16) or (it.g shl 8) or it.b },
            logColor = effective.color(ConfigKey.LogColor).let { (it.r shl 16) or (it.g shl 8) or it.b },
            reflowEnabled = reflowConsole,
            // HTML 图形段折行计宽的基尺（2026-11-05 遗留①）：与 [scriptFontPixelSize] 同源
            // （= 生效配置字号），渲染侧 `HtmlRunFlatten` 的图形格宽由同一把尺量化 ——
            // 折行与屏上实占同格数，img/shape 行不再「攒到超宽才折、右沿裁切」。
            scriptFontSize = effective.fontSize,
            // 图元尺寸源（`<img>` 缺 width 时按基图纵横比推宽，原版 ConsoleImagePart.cs:66-70）：
            // 现读 aux 的 SpritePool（与 BuiltinHost.imageSizeOf 同一份表，见 AuxMacroHost 的
            // KDoc）。lambda 在**打印期**才求值 —— 池在会话装配后段才装好，构造期取到 null
            // 会让缺 width 的图元一直计 0 格（神签/画像表示那类「只给 height」的写法）。
            imageSizeOf = { name ->
                store.aux.peek<com.eratw.port.core.host.SpritePool>(com.eratw.port.core.host.SPRITE_SLOT)
                    ?.find(name)
                    ?.let { com.eratw.port.core.host.ImageSizePx(it.width, it.height) }
            },
            // 文本像素真值（FONT-MEASURE，2026-11-06）：div 锚点/按钮列位的行内游标里，
            // **文本段**的像素不再一律按「格 × FontSize/2」的等宽近似推进，改问宿主的真字体
            // 度量 —— 与原版同一把尺（`StringMeasure.GetDisplayLength` 的 GDI+ 实测，
            // StringMeasure.cs:42-72）：比例字体（语料 `SETFONT "Saitamaar"`）下等宽近似与
            // 实测的差，就是锚点/按钮列位偏的像素量。
            //
            // 复用**已有的** `textMeasureRun` 注入口（app 侧 `GameHost` 包的是
            // `AndroidTextMeasure.measurePixels`，`GameHost.kt:3485`；FN-04 也是这条链），
            // 不新造表、不改 app。请求按「会话缺省三件套」组装：字体 = 生效 `FONT NAME`
            // （语料 emuera.config:21 「黑体」），字号 = 生效字号（与 [scriptFontPixelSize] /
            // 宿主 `fontSize` 同源），样式位 0 —— 本通道的签名只吃纯文本，SETFONT/FONTBOLD
            // 的逐段样式不随字符区间携带（见 `LineBufferConsoleSink.textPxOf` 的 KDoc）。
            //
            // 求值时机 = **打印期**：lambda 在每次 div 锚点/列位量宽时才调宿主，装配期不量。
            // null（缺省，golden / headless / 装置）= 未接线 ⇒ sink 回落旧算式，读数逐位不变。
            textPxOf = textMeasureRun?.let { measure ->
                { text: String ->
                    measure(TextMeasureRequest(text, fontName, effective.fontSize.toLong(), 0))?.toInt()
                }
            },
            // div 覆盖块逃逸判定的行高（CONTRACT-DIV-WIRE W3，替代出厂缺省 19）：
            // `Config.LineHeight` 的生效值（Config.cs:334 读取 + :436-440 不足字号抬到字号，
            // EffectiveConfig.lineHeight 同一条夹取），与 app 侧 `applyBootFontMetrics` 灌给
            // View 的是**同一个 effective**（GameHost.kt）—— sink 的逃逸判定与真机版面同尺。
            // 缺省 19 保持给不喂这参的构造点（headless 纯 JVM 测试零行为）。
            lineHeightPx = effective.lineHeight,
            // 排版尺（AA 画修复）：缺省静态表，真机注入 `CanvasConsoleView.fontDisplayWidth`。
            fontWidth = fontWidth,
        )
        stageMark?.invoke("session.config")

        // 3) 变量池
        //    `USE THE BINARY FORMAT FOR SAVING DATA` 必须在建池与装 #DIM 之前就取到生效值：
        //    它是 `#DIM ... SAVEDATA` 的登记闸（spec: UserDefinedVariableData.cs:465-475 ——
        //    非二进制存档时，SAVEDATA 的多维字符串变量与 CHARADATA 变量直接 CodeEE 拒登记）。
        //    侧车里烤的是**编译机**那一份（`program.meta` 的 dim-config），设备上的是游戏目录里
        //    `emuera.config` 那一份（语料 `:51` = YES）；照侧车用会少登记 69 个全局变量，
        //    现场是运行到使用点才报「未定义的变量」。
        //    与装置同源：`boot/BootPlayChain.kt:125/158/170` 走的也是这三个入口。
        val saveInBinary = SystemVariableBootstrap.systemSaveInBinaryFrom(config)
        store = VariableStore(
            gameData?.registry ?: VariableRegistry(),
            // `Config.CompatiRAND`（ConfigData.cs:98 出厂 false；语料 `emuera.config:52` = NO）：
            // 决定 RAND 伪变量取哪个 token —— `RandToken` 还是 `CompatiRandToken`
            // （VariableData.cs:3154）。此前本构造点不喂，VarConfig 恒取缺省 false，
            // 于是把配置改成 YES 也不分叉（SAVE-09）。取值面在 `VariableStore.random`。
            config = VarConfig(
                systemSaveInBinary = saveInBinary,
                compatiRand = effective.bool(ConfigKey.CompatiRAND),
                // `Config.VarsizeDimConfig`（ConfigData.cs:117 出厂 false）：VARSIZE 的维度实参
                // 是否按 ERD 口径减一（FunctionMethodCreator.cs:3449-3452）。此前本构造点不喂，
                // 该配置恒取缺省 false ⇒ 改成 YES 也不分叉。取值面在 `VarMethods.VARSIZE`。
                varsizeDimConfig = effective.bool(ConfigKey.VarsizeDimConfig),
                // CONFIG-WIRE U1/U2：初值四件改吃生效条目。原版链：emuera.config 条目 →
                // `Config.SetReplace`（Config.cs:719-741，REPLACE 组连 _Replace.csv 链）→
                // `VariableData.SetDefaultValue` 用静态字段灌池（EXPLV/PALAMLV：
                // VariableData.cs:3485-3490 的 CopyTo+Min 截断；PBAND：:3501-3504；
                // RELATION：CharacterData.cs:166-169 逐角色整列）。此前本构造点不喂这四件，
                // 恒吃 VarConfig 缺省 ⇒ 玩家改「EXPLVの初期値」等条目永不生效。
                // 条目侧解析（longList/long）与出厂缺省同值（ConfigData.cs:171-174），
                // 语料 `emuera.config` 无 REPLACE 段 ⇒ 条目值 = 缺省值，基准零漂移。
                // 取数走 [effective]（会话条目表，applyChainTo 已把 _Replace.csv 链放进去），
                // 与喂给控制台/语句层的是同一份真相。
                palamLvDef = effective.source.itemOf(ConfigKey.PalamLvDef).longList().toLongArray(),
                expLvDef = effective.source.itemOf(ConfigKey.ExpLvDef).longList().toLongArray(),
                pbandDef = effective.long(ConfigKey.pbandDef),
                relationDef = effective.long(ConfigKey.RelationDef),
            ),
        )
        // `MONEYLABEL` 伪变量（spec: `VariableData.cs:962-971` 的 MONEYLABEL_Token 读
        // `Config.MoneyLabel`）与 `MONEYSTR` 同源：宿主属性 [RootedBuiltinHost.moneyLabel]
        // 已接到链 + Replace，这里再把同一个值灌进变量池的宿主状态槽（原版是同一个静态字段，
        // spec: `Config.cs:199`、`:721`）。不灌的话 `%MONEYLABEL%` 会停在 `$`。
        store.moneyLabel = rooted.moneyLabel
        // `DRAWLINESTR` 伪变量（spec: `VariableData.cs:976-986` 的 DRAWLINESTR_Token 读
        // `Console.getDefStBar()`）与 DRAWLINE 语句同源同串：装配期一次成型的 `console.stBar`
        // （见上面 drawLineUnit 的接线注释）。不灌的话语料 COMMON.ERB 等 5 文件的
        // `%DRAWLINESTR%` 读数停在空串（NEG16）。
        store.drawLineStr = console.stBar
        gameData?.let { data ->
            for (t in data.templates) store.registerTemplate(t)
            store.loadItemPrice(data.itemPrices)
            store.reloadStrNames()
        }
        // 引擎基础状态：CSV 有角色模板 0 时种真实角色，否则 4 个裸角色（同 GoldenPipeline）
        if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
        else repeat(BARE_CHARACTERS) { store.characters.addBare() }

        // 3b) MOUSESKIP 弃用警告的生产装配（SAVE-15b）：原版在解析期投递（FunctionMethodCreator.cs:3635-3638
        //     → ParserMediator.Warn，ParserMediator.cs:125-136），解析/装载通道面归邻路（save15a），
        //     本路保持求值期投递，只把「warnRecorder 生产恒 null」补成「装配即给出投递面」。
        //     aux 槽实例即 fn 层 mouseState(c) 取到的同一份（MouseState.kt 的取用约定），
        //     一处装配全 VM 生效；aux.clear() 生产路径无人调用（grep 仅测试命中），不存在装配丢失面。
        mouseStateOf(store.aux).warnRecorder = mouseWarnSink ?: { mouseWarnBuffer.add(it) }

        // 3b-2) MOUSEX() / MOUSEY() 的单位（2026-11-07，与 CLIENTWIDTH 同一把尺）：宿主报的是
        //       **设备像素**坐标与客户区尺寸（命中测试/越界判定也在这个坐标系里做），读数出口
        //       折回**脚本 px**。语料拿 MOUSEX() 排 UI 位置（`UI_Container_Framework.ERB:823`
        //       的 `POS_X = MOUSEX() + MARGIN_PX`，该 px 直接写进脚本几何），又与
        //       `GETCONFIG("ウィンドウ幅")` 比（`开锁系统.ERB:230`）；不折就是在放大
        //       `设备字号 / 配置字号` 倍的坐标系里摆脚本 px 的 UI —— 弹窗出屏、角度过 1。
        //       折算比例取 [screenState]（转屏换字号档自动跟随）；字号未注入（0）或 1:1
        //       版面时恒等，既有装置读数逐字不变。
        mouseStateOf(store.aux).pointerToScriptPx = { v -> screenState.scriptPx(v, scriptFontPixelSize) }

        // 3c) 运行期词法宏表（CMP-34 接线）：侧车 `dimParseConfig` 的 22 条 `#DEFINE` 投影到
        //     `VariableStore.aux` 的 `lexer.macros` 槽 —— 运行期 `STRFORM`/`GETVAR` 等词法入口
        //     据此展开宏名（`ConfigMethods.lexerConfig` 现读该槽）。`metadata` 对象本身仍按类
        //     KDoc 的生命周期表在装配期后释放，本槽只留宏表投影（≈22 条字符串）。
        installLexerMacros(store.aux, metadata.dimParseConfig.macros)
        stageMark?.invoke("session.store")

        // 4) 全局 #DIM（原版 ERH 先于 ERB；install 自己就是不动点重试环）
        //    VARSIZE 折叠表用 DimVarSizes.systemSnapshot（= 原版 IdentifierDictionary 的装载期
        //    尺寸，只吃 VariableSize.csv：Process.cs:292-293 → :300-301），不取 store.registry——
        //    后者被 GameDataLoader.autoGrowByCsvExtent 抬过 29 张 CSV 名字表，比原版口径大。
        //    侧车字段保持无回调（CorpusCompile.kt:481），折叠器只在运行期这一处挂上。
        //    折叠回调必须在运行期重新挂：它不可序列化，侧车 dim-config 只落数据字段
        //    （BootMetaCodec），而原版 Process.cs:304-305 先于 ErhLoader 装配 EMediator ⇒
        //    源码路径能折的纯静态声明在 .erbc 装载路径也要能折。
        //    「空表零行为差」的旧论断已被 m-dimfold-wire 实测证伪：语料全局 #DIM 缺口
        //    5→0、私有 7→3 全靠这张 VARSIZE 快照。远程侧担心的 REF 被折成 0 维不成立 ——
        //    本快照只吃 VariableSize.csv 的出厂尺寸，不经 store.registry，也不含用户 REF。
        // CMP-59 数据面：`GETNUM` 第二跳的 ERD 表快照（键 = `NAME` / `NAME@n`，与
        // `ConstantData.cs:783-792` 的 `erdNameToIntDics` 同形）。装载期折叠发生在全局
        // `#DIM` 安装之前（`#DIM` 尺寸要先折），此时 5b 的 [UserKeywordTables.install]
        // 还没把表挂到变量池上，故这里按表文件独立取一份；两处同源（同一份
        // [UserKeywordTables.discover] / [UserKeywordTables.load]），只差长度上界口径
        // （见 [UserKeywordTables.foldSnapshot] 的 KDoc）。
        // 门 = `Config.UseERD`（与 5b 的 install 同一个开关；关掉时原版连表都不扫）。
        // 系统变量名不是 ERD 用户变量（原版 `#DIM` 重名会被拒）⇒ 排除，避免给
        // `GETNUM(TIME, …)` 一类折出原版没有的表（见 [UserKeywordTables.foldSnapshot] KDoc）。
        val erdFoldTables: Map<String, Map<String, Int>> =
            if (effective.bool(ConfigKey.UseERD)) {
                val systemNames = gameData?.registry?.all()
                    ?.mapTo(HashSet()) { it.name.uppercase() } ?: emptySet()
                UserKeywordTables.foldSnapshot(gameRoot, systemNames)
            } else {
                emptyMap()
            }
        // 键冲突时内置表优先（原版第一跳 `GetKeywordDictionary` 的口径，ConstantData.cs:857-861）。
        val foldKeywordTables: Map<String, Map<String, Int>> =
            erdFoldTables + (gameData?.registry?.systemKeywordTables() ?: emptyMap())
        stageMark?.invoke("session.tables")
        // 4b) VARSIZE 折叠快照**并入用户全局**（a59cd7ff 的运行期半边）：原版 ERH 边装边
        //     注册（IdentifierDictionary.cs:429），先声明的用户全局对后声明的
        //     `#DIM X, VARSIZE("Y")`（Y 亦是用户全局）在装载期可见；erbcc 编译侧已按同
        //     语义并快照（CorpusCompile.mergeGlobalUserDimSizes），而运行期这条重解析链
        //     此前只喂系统表 ⇒ 折不出 → 声明被丢（level 2 告警）→ 运行到用点「未定义的
        //     变量」。主线语料当前零命中，但 kojo-pack 烧包流程里第三方口上的 ERH 全局
        //     声明走同一条链，可达面真实。base 口径不变（只吃 VariableSize.csv，见下方
        //     注释），用户全局经 [SystemVariableBootstrap.mergedVarSizes] 不动点并入
        //     （与编译侧同一份实现，不许各抄一份）。
        val foldVarSizes = SystemVariableBootstrap.mergedVarSizes(
            base = gameData?.let { DimVarSizes.ofRegistry(it.registry) }
                ?: DimVarSizes.systemSnapshot(null),
            declarations = metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            config = metadata.dimParseConfig.copy(systemSaveInBinary = saveInBinary),
            keywordTables = foldKeywordTables,
            consoleWidth = console.clientWidth,
        )
        globalDimInstall = SystemVariableBootstrap.install(
            store,
            metadata.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) },
            metadata.dimParseConfig
                .copy(systemSaveInBinary = saveInBinary)
                .withPureStaticDimFolders(
                    // 复用 GameDataLoader 已建好的同一份系统尺寸口径：loader 与 systemSnapshot
                    // 都只跑一次 VariableSizeLoader.load；GameBase 不参与 VarDef.dims，CsvLoader
                    // 只写 CsvNameTable、不回写 sizes ⇒ ofRegistry(gameData.registry) 与重新
                    // systemSnapshot(csvDir) 逐字段相同（省掉第二次 CSV 解析 + 第二只 VariableRegistry）。
                    // 系统口径之上再并用户全局（见 4b 注释）——`withUserDecls` 只吃成功注册
                    // 的用户 spec，不会触碰系统表条目。
                    varSizes = foldVarSizes,
                    // GETNUM 的装载期折叠面（内置 29 张名字表 + CMP-59 的 ERD 表）。
                    keywordTables = foldKeywordTables,
                    // GETLINESTR：装载期控制台宽度（设备侧按字体度量传入，此处是同一把尺）。
                    consoleWidth = console.clientWidth,
                ),
        )
        stageMark?.invoke("session.dims")

        // 5) 私有 #DIM：逐条注册，失败不打断（与装置同口径：失败进清单，运行到使用点才暴露）
        //    R7-C4：补丁侧声明追加在同一条注册链（ModPatchLoader 结果的 privateDims；
        //    补丁头 v1 无 #DIM 载体 ⇒ 补丁段恒空表，追加零行为差 —— 载体扩展后由装载器
        //    抽取，见其类 KDoc「私有 #DIM」节）。
        val patchPrivateDims = patchLoad?.privateDims ?: emptyList()
        val allPrivateDims =
            if (patchPrivateDims.isEmpty()) metadata.privateDims else metadata.privateDims + patchPrivateDims
        var ok = 0
        val failures = ArrayList<String>()
        for (p in allPrivateDims) {
            runCatching { store.declareUserVar(p.spec, p.functionKey) }
                .onSuccess { ok++ }
                .onFailure { failures.add("${p.functionKey}/${p.spec.name}: ${it.message}") }
        }
        privateDimDeclared = ok
        privateDimFailures = failures
        stageMark?.invoke("session.privatedims")

        // 5b) ERD 用户变量关键字表（spec: ErhLoader.cs:344-392 + ConstantData.cs:758-803）：
        //     `<CSV>/<变量名>.csv` / `<ERB>/**/<变量名>@<维>.erd` 给用户 #DIM 提供字符串下标。
        //     必须在全局/私有 #DIM 都注册完之后挂（只对已声明变量生效）。
        //     开关 = `Config.UseERD`（出厂 true，ConfigData.cs:116）：关掉时原版既不扫表也不查表
        //     （ErhLoader.cs:269-272、ConstantData.cs:1113），这里同样不建表。
        //     单条表判定失败（重键/重复登记）不炸会话：降级成清单里的一条告警、继续挂其它变量
        //     （spec: ErhLoader.cs:328-332 的逐条 #DIM try/catch），清单交给 [erdWarnings]。
        //     第二条开关 = `Config.CheckDuplicateIdentifier`（ConfigData.cs:118 出厂 false；
        //     语料 `emuera.config:71` = YES）：打开时每条 `#DIM` 的声明名撞已登记 ERD 表的关键字
        //     发一条 level 1 告警（UserDefinedVariableData.cs:269-272 + ConstantData.cs:795-806）。
        //     同一份 [erdWarnings] 收这两类文案（level 1 告警不改表、不改登记）。
        val erdWarn = ArrayList<String>()
        if (effective.bool(ConfigKey.UseERD)) {
            UserKeywordTables.install(
                store, gameRoot, erdWarn,
                checkDuplicateIdentifier = effective.bool(ConfigKey.CheckDuplicateIdentifier),
            )
        }
        erdWarnings = erdWarn
        stageMark?.invoke("session.keywords")

        // 6) 存档接线（插在宿主与运行期之间：两边都要读同一份 SaveWiring）
        //   - 配置值走 ConfigStore + ConfigSpec 的键（不另抄标签；`UseSaveFolder` 的读取口径
        //     与 K-CONFIGWIRE 一致，本类只吃 ConfigStore，不改解析器）；
        //   - `SaveDataNos` 取**生效值**（Config.cs:338 读 + :441-450 夹到 20..80）——
        //     语句 SAVENOS（Process.cs:1580-1582）与函数 SAVENOS()（FunctionMethodCreator.cs:3963）
        //     在原版读的是同一个 `Config.SaveDataNos`，这里同步覆盖 SemConfig 的那一项。
        //   - 缺省条目表走 [RootedBuiltinHost.applyChainTo]（不是裸 loadChainFromDisk）：
        //     这条通道与函数层 `ConfigMethods.storeFromHost` 是同一个重放入口，
        //     三层链 + `_Replace.csv` 都随之就位（spec: `Process.cs:240-270`）。
        //     宿主显式传入 [configStore] 时以它为准（K-CONFIGWIRE 的接线面）。
        //   （条目表与生效视图已在第 2 步建好：控制台的 PRINTC 两项与这里共用同一份。）
        saveConfig = SaveConfig(
            systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
            zipSaveData = effective.bool(ConfigKey.ZipSaveData),
            useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
        )
        saveWiring = SaveWiring.install(
            store,
            saveConfig,
            effective.saveDataNos,
            // Map/Xml/DT 扩展段名字表：`<gameRoot>/CSV/VarExt*.csv`（ConstantData.cs:1317）
            EmSaveSets.parse(gameRoot.resolve(CSV_DIR_NAME)),
        )
        // 读档交接槽先装出来（`LOADDATA` → `SYSTEM_LOADEND`→`EVENTLOAD` 链的会话级状态）：
        // 写端在 FileSystemOps.loadData（Process.cs:1860-1861），读端在 ProcessRuntime。
        // aux 槽跨 resetValues 存活（见 RuntimeAux），故一次装配即可。
        SaveLoadHandoff.install(store)
        // B1 自动存档链：实档通道**提前构造**（原第 8 步的内联实参上提，构造参数一个都不变，
        // 只是把 `SaveFolderMigration` 的发生点从 runtime 装配挪到这里 —— 同一会话装配、
        // 中间无失败面）。`BeginFlow` 的 `host.saveTo(99, SAVEDATA_TEXT)`（Process.cs:2583）
        // 与语句 `SAVEDATA`（:1327-1349 → SystemOps.saveData）走**同一个** FileSystemOps，
        // 不重造落盘路（VariableEvaluator.cs:2644-2685：SaveTo 吞异常返回 false 的口径
        // 已由 FileSystemOps.saveData 复刻）。
        val sessionSystemOps = systemOps
            ?: FileSystemOps(
                store,
                RootedSaveFileIo(gameRoot, rooted.fileOps),
                saveConfig,
                saveMigrationPrompt = saveMigrationPrompt ?: SaveFolderMigration.AUTO_ACCEPT,
            )
        stageMark?.invoke("session.save")

        // 7) 内建函数宿主 + BEGIN 宿主。ConsoleBeginHost 的两个配置项走**生效配置**（与
        //    原版同源）：`Config.ComAbleDefault`（`Process.cs:2293`；ConfigData.cs 出厂 1）、
        //    `Config.AutoSave`（`Process.cs:2556`；语料 `emuera.config` 为 NO ⇒ false，
        //    与旧缺省一致，读数不变）。M10-PLAY 接线：此前两处恒用形参缺省，配置链改了
        //    也不会进 BEGIN 状态机。
        // HOST-05：writeRoot 非 null 时宿主放行 SAVETEXT / OUTPUTLOG 的写（见参数 KDoc）。
        // GFX-06：屏幕像素两条随装配透传（真机 = 实测值，headless = 出厂缺省）。
        // 2026-10-29：fontSize 改吃**生效配置字号**（scriptFontPixelSize），不再吃宿主的
        // page-fit 实测字号 —— 语料的指令按钮网格按 GETCONFIG("フォントサイズ") 排，
        // 量宽与补齐必须同尺（判据与后果见 [scriptFontPixelSize] 的 KDoc）。
        builtinHost = ConsoleBuiltinHost(
            gameRoot,
            console,
            rooted,
            inputState,
            audioStore = store,
            writeRoot = writeRoot,
            fontSize = scriptFontPixelSize.toLong(),
            clientWidth = clientWidthPx,
            clientHeight = clientHeightPx,
            // PRINTCLENGTH / PRINTCPERLINE 吃生效条目（与喂给控制台的 `printCWidth` 同一个来源）：
            // 语料那层的网格宽度由 `PRINTCLENGTH()` 乘出来，读死出厂 25 而玩家改了
            // 「PRINTCの文字数」时，同一份网格里 PRINTCLENGTH() 与 GETCONFIG 会分叉成两把尺。
            printCPerLine = effective.int(ConfigKey.PrintCPerLine).toLong(),
            printCLength = effective.int(ConfigKey.PrintCLength).toLong(),
            textMeasureRun = textMeasureRun,
            // LIVE-REFLOW：屏幕像素改读会话自持的活状态（同一实例）—— CLIENTWIDTH() /
            // CLIENTHEIGHT() 从此跟着 resizeConsole 走（原版是控件活尺寸，EmueraConsole.cs:232/234）。
            // 构造参数仍按 fixture 注入，作为「活状态缺席」时的回落值（同样经 ScriptPixels 折算，
            // 折算比例的另一端就是下面这个设备字号）。
            screenState = screenState,
            deviceFontPixelSize = deviceFontPixelSize,
            // R5.1 同源回填：脚本 px 版面宽（0 = 未接线/未布局 ⇒ 回填层不写，WindowX 保持链读数）。
            windowWidthScriptPx = windowWidthScriptPx,
        )
        beginHost = ConsoleBeginHost(
            inputSink,
            console,
            comAbleDefaultValue = effective.int(ConfigKey.ComAbleDefault),
            autoSaveEnabled = effective.bool(ConfigKey.AutoSave),
            // 商店输入的宿主商品模型（Process.cs:2611-2639）：ItemSales/BuyItem 全在
            // 变量池半边，经 BeginHost.shopHostModel 注入；`MaxShopItem` 不在
            // EffectiveConfig 的夹取集，条目原值即生效值。
            shopHostModel = StoreShopHostModel(store, this.configStore),
            // OBS2-L5：系统菜单等待入口的脚本行身份 —— 此前**没传**，`scriptLine` 退化 -1，
            // 等待身份回落到行水位（`LineBufferConsoleSink.kt:713-714`），报错/回显一推进行表，
            // 第二次重等的入口世代就前移 ⇒ 屏上旧按钮失配 = 玩家「点了没反应」。
            // 两条通道一起接：取用次序与各自的适用范围见
            // [beginWaitScriptLineToken]（运行期现算通道，有帧才准）与
            // [beginWaitPageIdentity]（本页来源函数，BEGIN 等待的实际所在时点）。
            scriptLineToken = ::beginWaitScriptLineToken,
            pageScriptLineIdentity = ::beginWaitPageIdentity,
            // B1：AUTO_SAVE 链实档 = 与语句 SAVEDATA 同一个 FileSystemOps（见上一步注释）。
            saveToHook = { slot, text -> sessionSystemOps.saveData(slot, text) },
            // CONFIG-WIRE U4：默认标题版面（SYSTEM_TITLE 缺失分支，Process.cs:2138-2158）——
            // gamebase 读数来自第 1) 步的 GameDataLoader（GameBase.cs:106 装载），菜单两行吃
            // REPLACE 组键（Config.cs:732-733；只经 _Replace.csv 链生效，主配置写不动）。
            gamebase = gameData?.gameBase ?: GameBaseData(),
            titleMenuString0 = effective.string(ConfigKey.TitleMenuString0),
            titleMenuString1 = effective.string(ConfigKey.TitleMenuString1),
        )
        // 键入回显（ECHO-INPUT，2026-11-04）：交付消费点的生效文本 → console 的正常打印路径。
        // 原版 = doInputToEmueraProgram 末尾 Print(str) + PrintFlush（EmueraConsole.cs:1280-1282）：
        // 非临时、无按钮、参与 PRINTC 残留行合并与 LINECOUNT —— 该行是引擎级 deleteLine(1)/(2)
        // 错误清理的删除对象（Process.cs:2194/:2498/:2648 等），缺了它同形 deleteLine 会误删真行。
        // 回调由 QueueInputSink 在输入锁外、VM 线程上触发（时机与形态矩阵见其 onEchoInput KDoc）。
        //
        // 发布相位契约（回显不产生独立发布帧，详见 LineBufferConsoleSink.printEchoLine 的 KDoc）：
        // 让出边界的顺序是 publishFrame（等前整帧上屏、闸开）→ waitInput → holdFrame
        // （返回后关闸），而 onEchoInput 恰在 waitInput 返回之前触发。回显若走普通打印会落在
        // 闸开窗口被直通发布，脚本随后的 CLEARLINE 只能删活表、删不掉 UI 已抓到的那一帧。
        // 装配给 REPLICA 档的 printEchoLine 先关闸再打印，使回显与后续 CLEARLINE／重印在
        // 同一发布边界成帧 —— 对齐原版 Print(str) 进缓冲、PrintFlush(force: false) 后脚本
        // 继续同步执行（EmueraConsole.cs:1280-1282），真上屏等 RunEmueraProgram("") 跑完的
        // forced paint（同文件 :1068/:1080），Running/Initializing 期间另有 16ms 节流（:1758）。
        // 可观测形态：限时等待 `TINPUTS ANIMATERECOLOREDMAPS,"UPDATE",0,""`
        // （COMF400 移動.ERB:162）每 tick 交付 "UPDATE"、剧本随后
        // `CLEARLINE LINECOUNT - LINESTART`（同文件 :172）重印地图；不符契约时屏上在
        // 「地图页」与「地图页 + UPDATE」两帧间交替。
        //
        // ECHO-INPUT 两档：REPLICA 按上述契约先关闸再打印；SUPPRESSED 在交付点就不产生
        // 显示行，帧闸 / 行表 / revision 一概不动 ⇒ 冻结段前后两帧逐字相同。少掉的那行由
        // 控制台行账本记进 LINECOUNT 并在 deleteLine 折算，脚本 CLEARLINE 不会误删菜单行。
        inputSink.echoInputMode = echoInputMode
        inputSink.onEchoInput = console::printEchoLine
        inputSink.onEchoSuppressed = console::noteSuppressedEchoLine
        stageMark?.invoke("session.hosts")

        // 7b) 语句参数子树规范化去重（M6-PERFINT）：装载期一次摊销，必须在运行期开始前。
        //     幂等 —— `.erbc` 装载路径（ProgramLoader）可能已经跑过，此时直接复用读数。
        //     R7-C4：跑在 overlay Program 上 —— 补丁段的语句池条目是组合池新追加的，
        //     未经规范化；base 段重跑一遍幂等（同一实例回写）。
        effectiveProgram.internStatementArgs()
        stageMark?.invoke("session.intern")

        // 8) 运行期。存档/系统语句宿主缺省 = 真存档层：落盘走 `<gameRoot>`（原版 Program.ExeDir，
        //    Program.cs:207 的同一根），路径由 SaveConfig 产出（Config.cs:467-481）。
        //    R7-C4：VM 吃 overlay Program（无补丁时 === program）；FunctionTable 尾段追加
        //    补丁函数的 FuncMeta（[patchMetas]，装载序恒在全部 base 之后）。
        runtime = ProcessRuntime(
            effectiveProgram,
            store,
            FunctionTable(
                if (patchMetas.isEmpty()) {
                    metadata.functions
                } else {
                    metadata.functions + patchMetas
                },
            ),
            VmConfig(
                maxStepsPerRun = maxStepsPerRun,
                maxRunMillis = maxRunMillis,
                // ENGFIX-4：唯一真源 = 会话生效配置（RootedBuiltinHost 重放包含游戏的
                // emuera.config；注册缺省 5000ms，置 0 关，spec: ConfigData.cs:75）。
                infiniteLoopAlertTimeMs = effective.int(ConfigKey.InfiniteLoopAlertTime).toLong(),
            ),
            consoleSink = console,
            // 语句层缺省吃生效配置的 `PRINTCを並べる数`（spec: `Config.cs:326` 静态字段、
            // `ConfigData.cs:63` 注册缺省 3）；显式传入的 semConfig 优先（装置对拍固定传语料值）。
            // `saveDataNos` 无论哪条路都取生效值（Config.cs:338 + :441-450）。
            // `timesNotRigorousCalculation` 同理取生效值（ConfigData.cs:111，消费点
            // StatementExecutor.execTimes；本游戏 emuera.config 为 NO ⇒ false，与出厂缺省一致）。
            // CONFIG-WIRE U2/U3/U4 追加五件（原版同为 `Config` 静态字段的直读直用）：
            //   * moneyLabel/moneyFirst —— `PRINT_SHOPITEM` 的金额格式（Config.cs:199/:201 静态
            //     字段；消费点 StatementExecutor 的商店打印面）。取数与上面 `store.moneyLabel`
            //     同走 `rooted.moneyLabel`/`moneyLabelFirst`（configChain.store 条目，链+Replace
            //     同源），保证 `%MONEYLABEL%` 伪变量与商店打印两把尺永不分叉。
            //   * timeupLabel —— TINPUT 系第 5 参缺省（Config.cs:736 读条目；出厂
            //     ConfigData.cs:170 = "時間切れ"）。语料无该配置行 ⇒ 条目值 = 缺省，零漂移。
            //   * stainDefault —— RESET_STAIN 语句面（StatementExecutor 读 `ctx.config.stainDefault`；
            //     C# 侧 = Config.StainDefault，spec VariableEvaluator.cs:1921 的 SetDefaultStain 口）。
            //     BEGIN TRAIN 面的整列复位另有活读（ProcessRuntime.configLongList），两处同一条目。
            //   * barChar1/barChar2 —— BAR 语句实部/空部字符（CONFIG-WIRE U3；出厂 '*'/'.'，
            //     ConfigData.cs:164-165；消费点 ExpressionMediator.cs:155-156 的 CreateBar）。
            //     BARSTR 函数侧已有独立活读（fn/HostMethods barChars），两处同一条目。
            //     语料无 BAR文字 条目（全语料 grep 零命中）⇒ 与出厂同值，基准零漂移。
            semConfig = (semConfig ?: SemConfig(printCPerLine = effective.int(ConfigKey.PrintCPerLine)))
                .copy(saveDataNos = effective.saveDataNos)
                .copy(timesNotRigorousCalculation = effective.bool(ConfigKey.TimesNotRigorousCalculation))
                .copy(
                    moneyLabel = rooted.moneyLabel,
                    moneyFirst = rooted.moneyLabelFirst,
                    timeupLabel = effective.string(ConfigKey.TimeupLabel),
                    stainDefault = effective.source.itemOf(ConfigKey.StainDefault).longList().toLongArray(),
                    barChar1 = effective.char(ConfigKey.BarChar1),
                    barChar2 = effective.char(ConfigKey.BarChar2),
                ),
            systemOps = sessionSystemOps,
            builtinHost = builtinHost,
            randSeed = randSeed,
            beginHost = beginHost,
            soundSink = soundSink,
            inputSink = inputSinkOverride ?: inputSink,
            disabledFunctions = disabledFunctions,
            infiniteLoopPrompt = infiniteLoopPrompt,
        )
        // 暂停开关的就绪发布（见 [setBackgroundPaused]）：先发布 runtime，再在**同一把锁**
        // 下消费装配期间收到的意图 —— 两个方向都走 [backgroundPauseLock]，意图不会丢。
        runtimeRef = runtime
        synchronized(backgroundPauseLock) {
            pendingBackgroundPause?.let { runtime.setBackgroundPaused(it) }
            pendingBackgroundPause = null
        }
        stageMark?.invoke("session.runtime")
        // 屏蔽是静默的（调用点直接得 0，语料侧看不到任何痕迹）⇒ 必须留一条可查的痕迹：
        // 真机上别人看到「标题没有彩蛋」时，这一行是唯一能对上号的读数。
        if (runtime.stubbedFunctionNames.isNotEmpty()) {
            stageMark?.invoke("session.stub=" + runtime.stubbedFunctionNames.joinToString(","))
        }

        // 8b) HOST-01 已由上游 Q1（`d6beb31b`）落每 VM aux 的 `skipState` 槽，
        //     `InputMethods.skipFlags` 直接读它（`core/fn/InputMethods.kt:228-232`）——
        //     本波原拟的 `ConsoleBuiltinHost.skipStateProvider` 闭包与之同责，合流时取上游形态，
        //     不留第二份跳过态通道。

        // 8) 编译期元数据 → 运行期索引。**这里之后不再引用 [metadata]**：标签表剪枝成
        //    有标签函数的紧凑表、数据节按语句池下标直查（见 [SidecarTables]），
        //    其余段（#DIM 原文 / 宏表 / fileByKey）随 metadata 一起可回收。
        //    标签查找的键由 ProcessRuntime.tryResolveLabel 前置大写（ProcessRuntime.kt 的
        //    `labelPc(functionKey.uppercase(), label.uppercase())`），投影层不再重复归一。
        //    R7-C4：补丁侧标签/数据节在此合并 —— 键域与 base 不相交（标签键 = 函数键，
        //    重名闸在装载期已拦；数据节键 = 绝对语句池下标，补丁键必然 ≥ base 计数），
        //    Map 相加不会遮蔽任何 base 条目。
        sidecar = SidecarTables(
            if (patchLabels.isEmpty()) metadata.labels else metadata.labels + patchLabels,
            if (patchDataSections.isEmpty()) metadata.dataSections else metadata.dataSections + patchDataSections,
        )
        stageMark?.invoke("session.sidecar")
        runtime.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                sidecar.labelPc(functionKey, label)
        })
        runtime.installDataSections(ProcessRuntime.DataSectionSource { index -> sidecar.groupsOf(index) })
        // 扁平通道关闭（Android 生产：`GameHost` 的 `loadMapped(flatStatements = false)`）时
        // VM 走对象树通道，PRINTDATA/STRDATA 按**语句实例**取数据节 ⇒ 补一张反查表。
        // 扁平通道全开（桌面/测试）不需要，也不建（免得无故物化语句树）。
        // R7-C4：判据对象与反查表都走 effectiveProgram —— 开关已在编排点同步成 base 的
        // 值，补丁语句的数据节下标也要进反查表（对象树通道对补丁 PRINTDATA 的消费面）。
        if (!effectiveProgram.flatStatementsEnabled) {
            runtime.installDataSectionStatementIndex(sidecar.dataSectionStatementIndex(effectiveProgram))
        }
        stageMark?.invoke("session.tail")
    }

    /**
     * R7-C4：把 overlay 后函数表尾段（补丁函数）转成 [FuncMeta] —— `FunctionTable` 尾段
     * 追加的输入（CONTRACT-R7.md §1 会话侧第 1 处）。
     *
     * 逐字段口径（补丁容器是 `.erbc` 窗口产物，函数元数据只能从
     * [com.eratw.port.core.code.FunctionInfo] 与装载读数推得）：
     * - `index` = 绝对函数下标（base 条数 + 尾段序；`_CALL` 的 a 与函数表下标同域）；
     * - `key` / `name` 取 [FunctionInfo.upperKey] / [FunctionInfo.name]（大写归一）；
     * - **事件面**（wave/mod-import-event-members 的装载侧对应）：PatchCompiler 给事件成员
     *   编出的 Program 名是 `<事件名>#<绝对下标>`（CorpusCompile.kt:474 同款形状），裸事件名
     *   留给合成入口。重建时按名还原：带 `#` 且主段是保留事件名 ⇒ `key` = 裸事件名、
     *   `isEvent = true`（进 eventMembersByKey，NORMAL 桶装载序追加在 base 成员之后）；
     *   裸事件名 ⇒ `isEvent = false` 的入口位（nonEventByKey 后装载者覆盖 base 入口）。
     * - `isMethod` / `returnsStr` / `returnsFloat` / `floatLocalSize` / `localSize` /
     *   `localsSize` 六项 = 头可选段 `function-metas`（4.981 浮点族 per-function 元数据，
     *   装载侧 harvestFunctionMetas 已按 offset+name 与补丁体逐条互证）按补丁窗口内
     *   offset 消费；缺记录（六项全缺省的函数不进段 / 旧头无段）走缺省（false/0）。
     *   接线前 `#FUNCTIONF` 返回恒 0、`#FUNCTIONS` 恒空串、`#LOCALFSIZE` 退默认 10 ——
     *   三个静默错值形态随本消费收口（PatchHeader 类 KDoc「可选段 function-metas」；
     *   事件成员/入口不可能带 `#FUNCTION` 声明 ⇒ 与上面的 isEvent 还原互不相扰）。
     *   其余 `isEvent` 恒 false：容器不带事件位（`.erbc` v1 函数标志恒 0）。
     *   补丁定义的非事件同名 base 函数由装载器重名闸拒绝（R7 §0.1 第 4 条）。
     * - `argIntCount` / `argStrCount` 按形参名的 `$` 后缀分桶（形参名是头部形参序，
     *   `B$` = 字符串形参）。形参名缺失而 arity 大于 0 时桶为 0 ⇒ 函数体内 ARG:i 不绑定，
     *   该状态已在装载告警里点名（I7），这里不再静默猜测。
     * - `defIndex` = base 最大 defIndex + 1 + 尾段序：**装载序恒为最后**（R7 §0.1 第 1 条），
     *   补丁函数在事件同桶排序与「后装载者生效」语义里都排在全部 base 之后。
     * - `position` = `mods/<补丁名>`（[ModPatchLoader.PATCH_POSITION_PREFIX]）：FN-05
     *   报错定位能反查补丁来源（I7）。
     *
     * 无补丁 / 整体回退（`effectiveProgram === baseProgram`）时尾段为空 ⇒ 返回空表。
     */
    private fun buildPatchFunctionMetas(
        effectiveProgram: Program,
        baseProgram: Program,
        patchLoad: ModPatchLoader.Result?,
        baseMetas: List<FuncMeta>,
    ): List<FuncMeta> {
        if (patchLoad == null || patchLoad.rolledBack) return emptyList()
        val baseFnCount = baseProgram.functions.size
        val tail = effectiveProgram.functions.drop(baseFnCount)
        if (tail.isEmpty()) return emptyList()
        val defIndexBase = baseMetas.maxOf { it.defIndex }
        val out = ArrayList<FuncMeta>(tail.size)
        var patchIdx = 0
        var patchFnBase = 0
        for ((offset, f) in tail.withIndex()) {
            // 定位该函数所属的补丁（loadedPatches 按装载序累计函数数）。
            while (patchIdx < patchLoad.loadedPatches.size &&
                offset >= patchFnBase + patchLoad.loadedPatches[patchIdx].functionCount
            ) {
                patchFnBase += patchLoad.loadedPatches[patchIdx].functionCount
                patchIdx++
            }
            val patchName = patchLoad.loadedPatches[patchIdx].name
            // 事件面还原（PatchCompiler「事件函数成员」节的装载侧对应）：成员 Program 名
            // = `<事件名>#<绝对下标>`，裸事件名 = 合成入口位。
            val rootKey = f.upperKey.substringBefore('#')
            val isMember = '#' in f.upperKey && Symbols.isEventLabelName(rootKey)
            val isEntry = '#' !in f.upperKey && Symbols.isEventLabelName(f.upperKey)
            // 4.981 浮点族 per-function 元数据（头可选段 `function-metas`，装载侧
            // harvestFunctionMetas 已与补丁体逐条互证）：六项按补丁窗口内 offset 取记录；
            // 缺记录（六项全缺省的函数不进段 / 旧头无段）走原缺省。接线前 `#FUNCTIONF`
            // 返回恒 0、`#FUNCTIONS` 恒空串、`#LOCALFSIZE` 退默认 10 三个静默错值形态
            // 在此收口（PatchHeader 类 KDoc「可选段 function-metas」）。
            val rec = patchLoad.functionMetas[offset]
            out += FuncMeta(
                index = baseFnCount + offset,
                key = if (isMember) rootKey else f.upperKey,
                name = if (isMember) rootKey else f.name,
                isEvent = isMember,
                isMethod = rec?.isMethod ?: false,
                argIntCount = f.paramNames.count { !it.endsWith("$") },
                argStrCount = f.paramNames.count { it.endsWith("$") },
                localSize = rec?.localSize ?: 0,
                localsSize = rec?.localsSize ?: 0,
                floatLocalSize = rec?.floatLocalSize ?: 0,
                returnsStr = rec?.returnsStr ?: false,
                returnsFloat = rec?.returnsFloat ?: false,
                defIndex = defIndexBase + 1 + offset,
                // 入口位带合成标记：userFunctionKinds 投影据此剔除（与全量路
                // CorpusCompile 的 `·event-entry·` 同值），不当作脚本符号暴露。
                position = if (isEntry) "·event-entry·" else ModPatchLoader.PATCH_POSITION_PREFIX + patchName,
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // 驱动
    // ------------------------------------------------------------------

    /**
     * 进入一个系统入口并执行**一步** handler（`Process.Begin(type)` + 一次 `runSystemProc()`，
     * `Process.cs:444-470`；状态机见 [BeginFlow]）。
     *
     * @return true = 目标函数已执行；false = 目标不存在且 force=false（也含 BLOCKED /
     *   AWAIT_INPUT 等非调用结果——需要区分时用 [runtime]`.beginStep(type)`）。
     */
    fun begin(type: BeginType): Boolean = runtime.begin(type)

    /** 继续当前系统状态的下一 handler（`runSystemProc()`，`Process.cs:2042-2045`）。 */
    fun resumeSystem(): StateStep = runtime.resumeSystem()

    /**
     * 交给 [ConsoleBeginHost] 的**现算脚本行身份通道**（OBS2-L5；构造点见装配第 7 步）。
     *
     * 值不新造：直接取运行期现成的 `ExecContext.scriptLineToken`
     * （`ProcessRuntime.kt:1093` 挂的 `::scriptLineTokenNow`，实现 `:1242-1247`，
     * 打包口径 = 函数单调 id 高 32 位 + pc 低 32 位，见 [LineIdentityIds]，恒非负），
     * 与语句层 `INPUT` 家族用的是**同一个**身份位（`StatementExecutor.kt:3912`）。
     *
     * **本通道在生产 BEGIN 等待点上恒 -1**：`scriptLineTokenNow` 无帧即 -1
     * （`:1243` 的 `activeVm.currentFunctionKey ?: return -1L`），而 BEGIN 状态机的每一次等待
     * 都发生在 `vm.run` **返回之后**（`Vm.unwindScopes` 把帧深收回 -1，`Vm.kt:947-955`；
     * 引擎侧 `SHOW_SHOP` 这类调用走 `runEntry` → `vm.run`，`ProcessRuntime.kt:2901-2923`）。
     * 桌面实测（真 [GameSession] + `begin(SHOP)` 泵到 `Shop_WaitInput`）：跑完 `SYSTEM_TITLE`
     * 后、盖完按钮后、进等待前后读到的都是 -1 —— 只接这一条等于没接，故配
     * [beginWaitPageIdentity] 一起接（取用次序与理由见 `ConsoleBeginHost.waitScriptLine`）。
     * 留着它的理由：一旦等待点落到**有帧**时刻（语料在系统流程里 `INPUT`、或后续波次把等待
     * 挪进语句帧），现算读数就是真行身份，比函数级更准，优先级也更高。
     */
    private fun beginWaitScriptLineToken(): Long =
        runtimeRef?.execContext?.scriptLineToken?.invoke() ?: -1L

    /**
     * 交给 [ConsoleBeginHost] 的**「本页来源函数」→ 脚本行身份位**通道（OBS2-L5 的主通道；
     * 登记时机、函数级粒度与「为什么必须粘这一层」见 [BeginHost.noteWaitPageProducer]，
     * 现算通道为什么恒 -1 见 [beginWaitScriptLineToken]）。
     *
     * 身份位不新造：取的是运行期同一张 `LineIdentityIds` 表 —— aux 槽名与
     * `ProcessRuntime.kt:4393` 的 `LINE_IDENTITY_SLOT`（`"proc.lineid"`，文件私有常量）
     * **必须是同一个串**，两条通道才共享一个身份域、id 才全局唯一（否则「本页来源」的身份位
     * 可能与某条真语句的身份位数值相撞，把该前移的世代冻住）。该串在本仓只有两处：
     * 那个私有常量与本方法的 [BEGIN_LINE_IDENTITY_SLOT] —— 改一处必须改另一处
     * （钉在 WaitInputGenerationTest 的用例面上；统一成公共常量需动 `ProcessRuntime.kt`，
     * 非本路所有权 ⇒ 交组长）。
     *
     * `pc` 恒 [BeginFlow.PAGE_IDENTITY_PC]（=0）：无帧时刻拿不到 pc（`lastPc` 虽在，函数键已随
     * `depth = -1` 不可读，`Vm.kt:2583-2590`），故取「该函数」这一级粒度；pc=0 与真语句
     * token 只可能在**同一个函数**上重合（同函数不同行的两次等待被当同一身份 —— 那正是
     * 原版对同一句 INPUT 重等的读法），跨函数不会折叠（id 按串内容分配，见 [LineIdentityIds]）。
     */
    private fun beginWaitPageIdentity(functionKey: String): Long =
        store.aux.slot(BEGIN_LINE_IDENTITY_SLOT) { LineIdentityIds() }
            .tokenOf(functionKey, BeginFlow.PAGE_IDENTITY_PC)

    /**
     * 回到标题（GOTOTITLE-CHANNEL 的宿主面）：逐句对应原版
     * `EmueraConsole.GotoTitle()`（`EmueraConsole.cs:2569-2581`）+
     * `Process.BeginTitle()`（`Process.cs:480-485`），顺序与复位面见
     * [ProcessRuntime.gotoTitle] 的对照表。
     *
     * 原版入口的两道拒绝（`MainWindow.cs:1079-1104`：脚本正在跑 `IsInProcess`、
     * 装载/标题级硬失败 `notToTitle`/`byError`）在 core-vm 判不出来，由宿主把关。
     */
    fun gotoTitle(): StateStep = runtime.gotoTitle()

    /**
     * 后台暂停开关（跨线程，宿主 UI 调）：true 后 VM 在**下一条语句执行前**阻塞，
     * false 立即恢复；暂停不消耗步数、不改变执行顺序、不推进任何计时。
     *
     * 装配期竞态：`runtime` 还在构造中（同一时刻宿主在 UI 线程按了 Home）时经
     * [backgroundPauseLock] 记住意图，构造完成后在装配线程上立即应用 —— 不丢、不早、不晚。
     */
    fun setBackgroundPaused(paused: Boolean) {
        synchronized(backgroundPauseLock) {
            val ready = runtimeRef
            if (ready != null) ready.setBackgroundPaused(paused) else pendingBackgroundPause = paused
        }
    }

    /**
     * 关闭输入队列并唤醒等待方（Activity 销毁/退出游戏时调用；之后任何等待抛 `InputSinkClosedException`）。
     *
     * CMP-19：本方法是宿主对整局的**唯一收口点**，所以产物文件的确定性释放也挂在这里
     * （[Program.releaseMmap]）。惰性档的 `Program` 生产上持有 1 个 `FileChannel` 与
     * CODE/段 3/语句局部串表三块映射，宿主原来没有任何释放点 ⇒ 每次 `Activity.recreate()`
     * 漏一套。释放的前提（段窗口整段 pin + 串表数据块整块 pin）由装载侧保证，装载侧判定
     * 不成立时 `releaseMmap()` 返回 false、通道保持打开（不拿惰性解码换 fd）。
     *
     * 幂等：`QueueInputSink.close` 与 [Program.releaseMmap] 各自幂等，重复调用无害
     * （`onDestroy` 与失败路径都会走到）。
     *
     * @return [Program.releaseMmap] 的读数：true = 产物通道已释放（或本来就没有通道）；
     *   false = 前提不成立（语句段局部串表 > 128 MiB），通道保持打开 —— 宿主据此落一条
     *   诊断日志，真机上的 fd 读数见 TODO(verify)。
     *
     * TODO(verify)：真机 `/proc/<pid>/fd` 的计数在 recreate 前后是否回落（本机无装置，
     * 只能由 JVM 单测证明 `FileChannel` 的关闭动作发生）。
     */
    fun close(): Boolean {
        // 暂停中的 VM 线程必须先放行：awaitResume 的循环条件是暂停位，不清位就只能靠
        // interrupt 叫醒等待（GameHost.close 会 interrupt，但会话也可能被单独关闭）。
        // 先清位再关输入队列：VM 恢复后若撞上输入等待，会按关闭口径抛 InputSinkClosedException。
        setBackgroundPaused(false)
        inputSink.close()
        return program.releaseMmap()
    }

    companion object {
        /**
         * 「本页来源函数」身份位用的 aux 槽名 —— **必须与 `ProcessRuntime.kt:4393` 的
         * `LINE_IDENTITY_SLOT`（文件私有常量，拿不到引用）同串**，两条通道才共用同一张
         * `LineIdentityIds`、只有一个身份域。理由与钉测面见 [beginWaitPageIdentity] 的 KDoc
         * 与 `WaitInputGenerationTest` 的「装配钉」用例（两处各写一份字面量，改一处必须改另一处）。
         */
        const val BEGIN_LINE_IDENTITY_SLOT = "proc.lineid"

        const val CSV_DIR_NAME = "CSV"

        /** `Config.MaxLog` 缺省（ConfigData.cs:62）；语料 emuera.config 覆盖为 900。 */
        const val DEFAULT_MAX_LINES = LineBufferConsoleSink.DEFAULT_MAX_LINES

        /** 控制台缺省宽度（半角格）：PC 基准 `CLIENTWIDTH=1800`（pc-golden-root.txt:689）。 */
        const val DEFAULT_CONSOLE_WIDTH = 1800

        /**
         * 单次运行的步数闸（死循环保护；与开局装置 `GameBootSmoke` 的缺省一致）。
         *
         * **红 J（NEWGAME-STEP-SPIN）**：原值 5,000,000 对**重载模组**的初始化
         * 流程不够 —— `NEWGAME` 的 `INIT_STARTINGLV` → `SHOW_ABLUP_SELECT`（`ABL.ERB:3-136`）
         * 对 `FOR 角色,0,CHARANUM` × `FOR LOCAL:11,0,30` ×（`FOR LOCAL,0,4` 内 `$AGAIN` 回边
         * ＋ `FOR LOCAL:1,0,100` ×2）逐角色扫描。真语料实测：单角色该流程 ≤ 30 万步
         * （`NewGameAbLupRealCorpusProbeTest` 的 `STEP_CAP=300000` 口径），乘以
         * `CHARANUM`（本模组开局即注册数十名角色）后**合法步耗可达 5,000,000–10,000,000**；
         * 实测把闸抬到 10,000,000 即**正常越过**该流程（死循环保护不误报）。
         * 因此缺省抬到 20,000,000（留 2× 余量）；仍保留为死循环保护上限。
         *
         * **红 K（GET_MAPID-SPIN）**：设备上 `CHARA_MOVEMENT` 被 5,000,000 闸误判为死循环。
         * 桌面用真产物 + `fixtures/saves/save03.sav`（`CHARANUM=168`）实测该函数
         * **执行 12,080,416 条指令**（5,140,444 条语句；`GMF_METHOD_UPDATE` 2.71M +
         * `GMF_METHOD_RESTORE` 2.32M 占 98% —— 两者各自按 `FOR 1..CHARANUM` 扫三遍，
         * 语料 `DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:50-92`，属**合法 O(N) 扫描**而非环不前进）。
         * 12.08M < 20M ⇒ 本缺省覆盖且留 1.65× 余量；同口径回归用例
         * `RedKCharaMovementReproTest`。
         */
        const val DEFAULT_MAX_STEPS_PER_RUN = 20_000_000L

        /**
         * 生产（真机 `GameHost`）用的步数闸：10 亿步，是**生产唯一的死循环判据**。
         *
         * 口径与 [DEFAULT_MAX_STEPS_PER_RUN] 完全相同（**段落**计数：每次让出边界从 0 重算，
         * `Vm.kt` 的 `steps`），只是取值按生产标定。
         *
         * 为什么是 10 亿（2026-10-30 玩家报「开了地图重修插件后启动失败」）：
         * - 桌面用真产物 + `fixtures/saves/save03.sav`（第 47 日 `TIME=1345`、`CHARANUM=168`、
         *   地图重修插件开启）实测一次 `EVENTTRAIN` 派发：连调 **24 次** `CHARA_MOVEMENT`，
         *   共 123,496,792 条语句 ≈ **2.90 亿条 VM 指令**（单次 `CHARA_MOVEMENT` 12,080,416 条
         *   指令、其中 97.8% 是 `GMF_METHOD_UPDATE`/`GMF_METHOD_RESTORE` —— 每次移动 2,303 次
         *   地图切换、每次都整表重建 `MAPNAME`/`ROOMDATA`/`PRIVATEROOM`/`CFLAG:神社在住`，
         *   语料 `DLC/GENSOKYO_MAP_FIX/幻想乡地图重修.ERB:40-92` 的合法 O(CHARANUM) 扫描）。
         * - 玩家报告里那一段在 60,013 ms 时已跑 **133,496,832 步仍未到让出点**（≈2.22M 指令/秒），
         *   即该段至少 1.33 亿条指令 —— 与上面 2.90 亿的整条链同量级。
         * - 10 亿 = 实测最坏合法链的 **3.4 倍**，给 `CHARANUM` 继续增长与更慢机型留量。
         *
         * **代价（写清楚，别当免费）**：真死循环要到 10 亿步才报，在玩家机的速率下 ≈7.5 分钟。
         * 原版在这一情形只是 5 秒弹一次框、玩家点「否」后**继续跑**（`Process.cs:507-510`），
         * 从不自动中止 ⇒ 本仓的排序是「不误杀合法重负载」优先于「快判真死循环」。
         * 要快判就把墙钟闸当**诊断旋钮**显式启用（[com.eratw.port.core.code.VmConfig.maxRunMillis]），
         * 而不是把生产阈值压回合法链以下 —— 那正是本报告的发生方式。
         *
         * ENGFIX-3（2026-11-05）重定位：官方死循环保护从不按指令数中止（墙钟 + 弹框问玩家，
         * `Process.cs:470-489`），core 侧已复刻该语义（VmConfig.infiniteLoopAlertTimeMs +
         * VmHost.askInfiniteLoop）。本常量随之**降级为移植层极端兜底**（LoopGuardCause.DeviceBudget）：
         * 量级取「正常游戏永不触及」（`EVENTTRAIN` 起床链实测 2.90 亿条仍有两个数量级余量），
         * 不是官方语义 —— ADR-005 例外登记项（随 app 弹框接线波次一并落账）。
         */
        const val PRODUCTION_MAX_STEPS_PER_RUN = 1_000_000_000L

        /**
         * 生产（真机 `GameHost`）的单段落墙钟上限：**0 = 关**（生产不用墙钟当判据）。
         *
         * 原版 `Config.InfiniteLoopAlertTime` 注册缺省 5000 ms（`ConfigData.cs:75`），
         * 但它只是「弹框问一下」，玩家点「否」即继续（`Process.cs:507-510`）—— 原版的实际
         * 行为是**从不中止**。core 侧该交互已由 ENGFIX-3 落地（VmHost.askInfiniteLoop），
         * ENGFIX-4 起生产 app 已接 AlertDialog（阈值取会话生效配置 `InfiniteLoopAlertTime`，
         * 见构造参数 `infiniteLoopPrompt`）。2026-10-29 曾取 60 秒当「远超任何合法负载」
         * 的值；2026-10-30 的玩家报告证伪了这个前提：`EVENTTRAIN` 起床链实测 2.90 亿条指令，
         * 在 2.22M 指令/秒 的机器上需要 ≈130 秒（详见 [PRODUCTION_MAX_STEPS_PER_RUN]），
         * 60 秒只够跑到一半 ⇒ 必然误杀。
         *
         * 墙钟闸本身保留（[com.eratw.port.core.code.VmConfig.maxRunMillis] 缺省 0，
         * 装置/诊断可显式启用），只是生产不再启用：它的误杀面（设备速度、后台抢占、降频）
         * 无法用常量消除。
         */
        const val PRODUCTION_MAX_RUN_MILLIS = 0L

        /** 无 CSV 时种下的裸角色数（同 `GoldenPipeline`）。 */
        const val BARE_CHARACTERS = 4

        /** `ConfigData.cs:65` 的条目标签（大写英文形，`AConfigItem.cs:22-23` 的注册口径）。 */
        private const val CONFIG_FONT_NAME = "FONT NAME"

        /** `ConfigData.cs:62` 的条目标签。 */
        private const val CONFIG_MAX_LOG = "MAX HISTORY LOG LINES"

        /** `ConfigData.cs:83` 的条目标签（出厂 false，语料 `emuera.config:38` = YES）。 */
        private const val CONFIG_BUTTON_WRAPPING = "BUTTON WRAPPING"
    }
}

/**
 * 侧车在运行期的**投影**：只留 [GameSession] 真正还会读的两张表，其余段在装配完成后随
 * [BootMetadata] 一起可回收（逐段判据见 [GameSession] 类 KDoc 的「侧车生命周期」）。
 *
 * ## 为什么标签表是「剪枝」而不是「延迟」
 *
 * 侧车对每个函数都写一行 `labelset`（全量产物 110,072 行），其中只有 900 个函数真的带
 * `$标签`：解析后 `labels` 是 110,072 个外层键、109,172 个**空表**。若把这张表整份留到
 * 「首次解析标签」再处理，常驻就先把 10.9 万只空表钉住了（常驻模型实测 18.7 MiB，
 * 剪枝后只剩 0.3 MiB），与削减常驻的目标相反。因此装配期一次遍历把它压成 900 个非空函数的
 * 紧凑表（共享内层表实例，不复制标签），此后不再增长。
 *
 * 查找键的归一在上游：`ProcessRuntime.tryResolveLabel` 已经把函数键与标签名都 `.uppercase()`
 * 之后再调 [labelPc]（`ProcessRuntime.kt` 的 `labelTable?.labelPc(functionKey.uppercase(),
 * label.uppercase())`），所以这里直接查表、不再分配字符串；侧车里的键本身就是大写形
 * （写出侧按 `FuncSig.name` 归一大写，`BootMetadata.labelPc` 也只归一查找键）。
 *
 * ## 数据节为什么直查下标
 *
 * 侧车按**语句池下标**存数据节（3,874 节），语句层消费时拿到的也正是池下标
 * （`sem/ExecContext.kt` 的 `DataSections.groupsOf(stmtIndex)`；波次 C 起
 * `StatementExecutor` 读扁平池 marker 的 `a` 操作数就是它）。两张表的键天然对齐，
 * 因此**不需要**「池下标 → 语句实例」的反查层：波次 B 为兼容「兜底回原树」的旧通道
 * 临时留过一层 `IdentityHashMap`（3,874 次 `Program.statement(i)` + 表分配，还把语句树
 * 钉在常驻里），波次 C 随数据节键改下标一并删除，[groupsOf] 直查。
 *
 * ## 线程口径
 *
 * [groupsOf] 只在 VM 线程被语句执行器调用（`ProcessRuntime.dataGroupsOf` ←
 * `StatementExecutor`），与 [GameSession.begin] 同线程；[sections] 是只读表，无需同步。
 *
 * @param labels 侧车的函数键 → 标签名 → pc（装配期剪枝的输入，之后只留非空表）
 * @param sections 侧车的语句池下标 → 数据节组（原表保留，值就是运行期要用的表达式树）
 */
internal class SidecarTables(
    labels: Map<String, Map<String, Int>>,
    private val sections: Map<Int, List<List<Expr>>>,
) {

    /** 剪枝后的标签表：只含有标签的函数（内层表与侧车共享实例，不复制条目）。 */
    private val labels: Map<String, Map<String, Int>> = pruneLabels(labels)

    /** 保留的标签函数数（单测口径：全量产物应为 900，而不是 110,072）。 */
    internal val labelFunctionCount: Int get() = labels.size

    /** 保留的标签条数（全量产物应为 1,623）。 */
    internal val labelCount: Int get() = labels.values.sumOf { it.size }

    /**
     * `$标签` 解析（语义 = `BootMetadata.labelPc`）：函数键与标签名由调用方保证已大写，
     * 未登记的函数或标签一律 null。
     */
    fun labelPc(functionKey: String, label: String): Int? = labels[functionKey]?.get(label)

    /**
     * 语句池下标的数据节：未登记（含越界下标）→ null，语句层据此走 PRINTDATA / STRDATA
     * 的 no-op 分支。
     *
     * 键与 `BootMetadata.dataSections` 同源、**不换实例**：同一语句池里同一语句恒为同一下标，
     * 故「同下标恒同组」（波次 C 验收判据）；越界下标只是查不到，不会挂到别的语句上。
     * 语句池在装载后仍可追加（`Program.addStatement`），那些语句没有侧车数据节，查表即 null。
     */
    fun groupsOf(stmtIndex: Int): List<List<Expr>>? = sections[stmtIndex]

    /**
     * 对象树通道（扁平通道关闭，Android 生产）的反查表：数据节语句实例 → 语句池下标。
     *
     * 只收 [sections] 登记过的下标（全量 3,874 条），**不**扫整个语句池（1.7M 条，
     * 且会把懒装载的语句树整棵物化）；越界键跳过。见
     * `ProcessRuntime.installDataSectionStatementIndex` 的 KDoc。
     */
    fun dataSectionStatementIndex(program: Program): IdentityHashMap<Statement, Int> {
        val out = IdentityHashMap<Statement, Int>(sections.size * 2)
        for (idx in sections.keys) {
            if (idx < 0 || idx >= program.statementCount) continue
            out[program.statement(idx)] = idx
        }
        return out
    }

    private companion object {
        /** 剪掉空表：全量侧车里 110,072 个外层键只有 900 个带标签。 */
        fun pruneLabels(src: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> {
            var out: HashMap<String, Map<String, Int>>? = null
            for ((key, table) in src) {
                if (table.isEmpty()) continue
                val dst = out ?: HashMap<String, Map<String, Int>>(1024).also { out = it }
                dst[key] = table
            }
            return out ?: emptyMap()
        }
    }
}

/**
 * 只回答「没有全局存档」的最小 [SystemOps]：**装置专用**（`GameBootSmoke` / `PerfBaseline` /
 * `E2ESystemOps` 一族的对照基线），不再是 [GameSession] 的缺省。
 *
 * ## 为什么保留
 *
 * 语料 `SYSTEM_TITLE` 的第一段就是 `LOADGLOBAL` → `RESULT = GLOBAL` → `SAVEGLOBAL` → `LOADGLOBAL`
 * （`TITLE.ERB:20-23`）：需要一只「不碰盘」的宿主来做开局冒烟与基准读数（写盘会把语料目录
 * 弄脏，也会让两次运行的读数不一致）。M6-SAVE-WIRE 起生产路径改为 [FileSystemOps]，
 * 本对象只在显式传入时生效。
 *
 * 语义口径：`loadGlobal` 答 false（**本机没有全局存档**，对全新安装是事实），
 * `saveGlobal` 不落盘。
 *
 * 警告：`saveGlobal` 是 no-op ⇒ 用它跑存档流程等于静默丢弃存档。真实存档口径见
 * `core/save/FileSystemOps.kt`（`SAVEGLOBAL` → `global.sav`，`VariableEvaluator.cs:2474-2511`）。
 */
object NoSaveSystemOps : SystemOps {
    override fun saveGlobal() {}

    override fun loadGlobal(): Boolean = false
}
