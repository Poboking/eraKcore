package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.parseBoolLiteral
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.IdleInputState
import com.eratw.port.core.fn.InputState
import com.eratw.port.core.fn.TextMeasureRequest
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.installLexerMacros
import com.eratw.port.core.loader.BootMetadata
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
 * @param maxStepsPerRun 单次 `begin`/`resumeSystem` 的步数闸（防死循环）。缺省
 *   [DEFAULT_MAX_STEPS_PER_RUN] = 20,000,000 —— 5,000,000 会误伤重载模组的 `INIT_STARTINGLV`
 *   → `SHOW_ABLUP_SELECT`（红 J），详见该常量 KDoc。
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
     * 不稳定；生产由 [GameHost] 显式传 [DEFAULT_MAX_RUN_MILLIS] 当主判据，步数闸在那条路上
     * 抬到 [PRODUCTION_MAX_STEPS_PER_RUN] 只作兜底。
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
     * 脚本可见的屏幕像素两条：`CLIENTWIDTH` / `CLIENTHEIGHT` 的**设备像素**
     * （消费点 ConsoleQueryMethods.kt:131/136）。缺省 = 原版出厂窗口值
     * （WindowX 760 / WindowY-LineHeight 461，spec: ConfigData.cs:56,57,67），
     * headless 与 PC 基准零行为差；真机由 GameHost 按控制台实测值注入
     * （缺省 760 会把状态页/体型图的像素换算缩到 1/3）。
     *
     * **字号不在这份注入里**（2026-10-29 修正）：`builtinHost.fontSize` 一律取会话自己算出的
     * 生效配置字号（[scriptFontPixelSize]），宿主喂不进来。为什么非不可喂，写在
     * [scriptFontPixelSize] 的 KDoc（一句话：语料的按钮网格用 `GETCONFIG("フォントサイズ")`
     * 当尺，量宽若换尺，一格就同时对不齐一次，攒到一行十几个就整排错位）。
     * 这两条仍是设备像素、也仍由宿主注入 —— 它们没有「同源第二把尺」可对照，
     * 原版读的就是真窗口像素。
     */
    clientWidthPx: Long = BuiltinHost.DEFAULT_CLIENT_WIDTH,
    clientHeightPx: Long = BuiltinHost.DEFAULT_CLIENT_HEIGHT,
    /**
     * 带样式的分段量宽（FN-04 core）：`(请求) -> 像素宽`，生产由应用层把
     * `AndroidTextMeasure.measurePixels` 包一层传进来（字体族 / styleMask / charSpace）。
     * null = 未接线 → `HTML_STRINGLEN` 家族回落旧纯文本回调 → 等宽近似，
     * headless 与既有基准读数零变化。
     */
    textMeasureRun: ((TextMeasureRequest) -> Long?)? = null,
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
) {

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

    /** BEGIN 状态机的宿主面：四个 `await*` 全部接到 [inputSink] 的同一队列，`writeLine` 走 [console]。 */
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
     * 分母同时出现、整个约掉**，所以换任何 page-fit 档（`PageFitter` 从 30px 到平板那档 60+）
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
                    varSizes = gameData?.let { DimVarSizes.ofRegistry(it.registry) }
                        ?: DimVarSizes.systemSnapshot(null),
                    // GETNUM 的装载期折叠面（内置 29 张名字表 + CMP-59 的 ERD 表）。
                    keywordTables = foldKeywordTables,
                    // GETLINESTR：装载期控制台宽度（设备侧按字体度量传入，此处是同一把尺）。
                    consoleWidth = console.clientWidth,
                ),
        )
        stageMark?.invoke("session.dims")

        // 5) 私有 #DIM：逐条注册，失败不打断（与装置同口径：失败进清单，运行到使用点才暴露）
        var ok = 0
        val failures = ArrayList<String>()
        for (p in metadata.privateDims) {
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
        )
        stageMark?.invoke("session.hosts")

        // 7b) 语句参数子树规范化去重（M6-PERFINT）：装载期一次摊销，必须在运行期开始前。
        //     幂等 —— `.erbc` 装载路径（ProgramLoader）可能已经跑过，此时直接复用读数。
        program.internStatementArgs()
        stageMark?.invoke("session.intern")

        // 8) 运行期。存档/系统语句宿主缺省 = 真存档层：落盘走 `<gameRoot>`（原版 Program.ExeDir，
        //    Program.cs:207 的同一根），路径由 SaveConfig 产出（Config.cs:467-481）。
        runtime = ProcessRuntime(
            program,
            store,
            FunctionTable(metadata.functions),
            VmConfig(maxStepsPerRun = maxStepsPerRun, maxRunMillis = maxRunMillis),
            consoleSink = console,
            // 语句层缺省吃生效配置的 `PRINTCを並べる数`（spec: `Config.cs:326` 静态字段、
            // `ConfigData.cs:63` 注册缺省 3）；显式传入的 semConfig 优先（装置对拍固定传语料值）。
            // `saveDataNos` 无论哪条路都取生效值（Config.cs:338 + :441-450）。
            // `timesNotRigorousCalculation` 同理取生效值（ConfigData.cs:111，消费点
            // StatementExecutor.execTimes；本游戏 emuera.config 为 NO ⇒ false，与出厂缺省一致）。
            semConfig = (semConfig ?: SemConfig(printCPerLine = effective.int(ConfigKey.PrintCPerLine)))
                .copy(saveDataNos = effective.saveDataNos)
                .copy(timesNotRigorousCalculation = effective.bool(ConfigKey.TimesNotRigorousCalculation)),
            systemOps = systemOps
                ?: FileSystemOps(
                    store,
                    RootedSaveFileIo(gameRoot, rooted.fileOps),
                    saveConfig,
                    saveMigrationPrompt = saveMigrationPrompt ?: SaveFolderMigration.AUTO_ACCEPT,
                ),
            builtinHost = builtinHost,
            randSeed = randSeed,
            beginHost = beginHost,
            soundSink = soundSink,
            inputSink = inputSinkOverride ?: inputSink,
            disabledFunctions = disabledFunctions,
        )
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
        sidecar = SidecarTables(metadata.labels, metadata.dataSections)
        stageMark?.invoke("session.sidecar")
        runtime.installLabelTable(object : ProcessRuntime.LabelTable {
            override fun labelPc(functionKey: String, label: String): Int? =
                sidecar.labelPc(functionKey, label)
        })
        runtime.installDataSections(ProcessRuntime.DataSectionSource { index -> sidecar.groupsOf(index) })
        // 扁平通道关闭（Android 生产：`GameHost` 的 `loadMapped(flatStatements = false)`）时
        // VM 走对象树通道，PRINTDATA/STRDATA 按**语句实例**取数据节 ⇒ 补一张反查表。
        // 扁平通道全开（桌面/测试）不需要，也不建（免得无故物化语句树）。
        if (!program.flatStatementsEnabled) {
            runtime.installDataSectionStatementIndex(sidecar.dataSectionStatementIndex(program))
        }
        stageMark?.invoke("session.tail")
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
        inputSink.close()
        return program.releaseMmap()
    }

    companion object {
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
         * 生产（真机 `GameHost`）用的步数兜底：抬到 5 亿步，让**时间闸当主判据**。
         *
         * 为什么生产不再用 [DEFAULT_MAX_STEPS_PER_RUN]（2026-10-29 玩家反馈「读档有时报错」）：
         * 2000 万步是「指令数」口径，原版根本没有这一维 —— 它的死循环保护看墙钟
         * （`Process.cs:493-512`，默认 5000 ms 弹框且**玩家可选继续**）。移植层拿 2000 万步
         * 硬中止，等于把「读长档恢复演出卡十几秒」判成死循环（BOARD 2026-10-27 真机复现：
         * `EVENTTRAIN → CHARA_MOVEMENT → GMF_METHOD_UPDATE` 在 20,000,001 步被拦）。
         *
         * 5 亿步在 500 ns/指令 的机器上 ≈ 250 秒，远大于 [DEFAULT_MAX_RUN_MILLIS] 的 60 秒
         * ⇒ 正常情况下永远是时间闸先触发；步数闸只兜「时钟被系统冻住」这类极端情形
         * （Android 休眠/调试暂停时 `nanoTime` 不走，步数仍在涨）。
         */
        const val PRODUCTION_MAX_STEPS_PER_RUN = 500_000_000L

        /**
         * 生产（真机 `GameHost`）的单段落墙钟上限：60 秒。
         *
         * 取值依据：原版 `Config.InfiniteLoopAlertTime` 注册缺省 5000 ms（`ConfigData.cs:75`），
         * 但它只是「弹框问一下」，玩家点「否」即继续（`Process.cs:507-510`）—— 原版的实际
         * 行为是**从不中止**。移植层没有这个交互，只能选一个「远超任何合法负载」的值：
         * 真机读 9/21 第 47 日旧档的整条恢复演出 3-4 分钟，但其中**单段**（两次让出之间）
         * 远小于此（让出点是每帧 `AWAIT` 与每次输入等待）；60 秒留了一个数量级余量。
         * 真死循环（`LOOP 1` 无让出）在 60 秒内被抓，玩家看到的是可重试的失败屏，
         * 不会像现在这样「读个旧档就启动失败」。
         */
        const val DEFAULT_MAX_RUN_MILLIS = 60_000L

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
