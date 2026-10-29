package com.eratw.port.core.sem

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.DebugSink
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopConsoleSink
import com.eratw.port.core.host.NoopDebugSink
import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.skipStateSlot
import com.eratw.port.core.vars.VariableStore

/**
 * 语句层配置 —— 原版 Config 中被执行路径读取的项。
 * spec: Runtime.Config/ConfigData.cs（默认值）。
 */
data class SemConfig(
    /** 每行 PrintC 列数（PRINT_PALAM/PRINT_SHOPITEM 分列用；0 = 不分列）。spec: ConfigData.cs PrintCPerLine */
    val printCPerLine: Int = 3,
    /** 存档槽数（SAVENOS）。spec: ConfigData.cs SaveDataNos */
    val saveDataNos: Int = 20,
    /** 限时输入超时提示语（TINPUT 系第 5 参缺省）。出厂串 = `ConfigData.cs:170` 的 `"時間切れ"`（繁体 `時`/`間`，非简体 `时间`）；回落 `FunctionIdentifier.cs:1033`/`:1082` ← `Config.cs:736`（配置项 111 = `ConfigCode.cs:93`），语料无该配置行、证据链与尺子见 `core/host/HostAudit2Test`。 */
    val timeupLabel: String = "時間切れ",
    /** 金钱符号（PRINT_SHOPITEM）。spec: ConfigData.cs MoneyLabel */
    val moneyLabel: String = "$",
    /** 金额前缀格式（PRINT_SHOPITEM）。spec: ConfigData.cs:160 MoneyFirst 缺省 true */
    val moneyFirst: Boolean = true,
    /** 污渍初值（RESET_STAIN）。spec: ConfigData.cs:169 */
    val stainDefault: LongArray = longArrayOf(0L, 0L, 2L, 1L, 8L),
    /** UseNewRandom（RANDOMIZE/INITRAND/DUMPRAND 的告警开关）。spec: ConfigData.cs UseNewRandom */
    val useNewRandom: Boolean = false,
    /**
     * TIMES 的计算路径开关（「TIMESの計算をeramakerにあわせる」）。spec: ConfigData.cs:111
     * `t: false` 出厂即 **false**（本游戏 emuera.config 亦为 NO）⇒ 活跃路径是小数精确乘：
     * `(decimal)int × (decimal)double` 后向零截断（spec: FunctionIdentifier.cs:1326-1338）；
     * true 时才走 `(double)int × double` 的 eramaker 模拟路径（:1322-1325）。
     * 消费点 [StatementExecutor.execTimes]；接线点 proc/GameSession.kt（生效配置注入）。
     */
    val timesNotRigorousCalculation: Boolean = false,
    /** TODO(verify) 预留：限时超时后是否强制跳过。**无读写方、原版无对应物**（全仓 grep `TimeoutForce` 零命中，配置项侧只有 `ConfigCode.cs:93` 的 TimeupLabel）；旧注释所述「MesSkip 初始状态 / SKIPLOG」是 mesSkip 一族（`Process.cs:1830-1833`），与字段名不符 —— 保留声明只为不破坏既有构造调用，接线前先回 C# 找语义。 */
    val timeupTimeoutForce: Boolean = false,
    /**
     * VARI/VARS 作用域变量语句开关。本游戏 setting.json `UseScopedVariableInstruction:false`，
     * 原版在标识符解析期即抛 CodeEE（spec: IdentifierDictionary.cs:624-627 CanNotUseVAR），
     * 语料零使用 ⇒ 语义按**禁用**处理（门控消费点见 StatementExecutor.execVarDecl）。
     * 解析期同口径门控已落地：`core.stmt.StmtParseConfig.useScopedVariableInstruction`
     * （LineParser 构造参数；M3-A A3）。默认 true 保持既有解析行为，本作口径显式传 false；
     * 若启用，原版在 ParseLine 手工解析参数（LogicalLineParser.cs:412-511），
     * execVarDecl 的初值语义随之生效。
     */
    val useScopedVariableInstruction: Boolean = false,
)

/**
 * 存档/系统级语句的宿主钩子（SAVELOADGAME/SAVEDATA/LOADDATA/DELDATA/SAVEGLOBAL/LOADGLOBAL/
 * SAVECHARA/LOADCHARA/UPCHECK/CALLTRAIN/UPDATECHECK 等）。
 * 这些语句依赖进程级状态（存档文件、UI 流程），语句层只转交。
 * 除 SAVEGAME/LOADGAME 外，其余方法默认抛 [CodeEE]：宿主未提供时按「未实现」报错
 * （原版对应功能不可用的场景）。SAVEGAME/LOADGAME 在原版里**没有宿主调用**，见各自的 KDoc。
 */
interface SystemOps {
    /** SAVEDATA。返回 false = 保存失败（打印错误行，不抛异常）。spec: Process.cs:1327-1349 */
    fun saveData(no: Int, text: String): Boolean =
        throw CodeEE("SAVEDATA 需要宿主提供存档能力（SystemOps）")

    /** LOADDATA。spec: Process.cs:1841-1863 */
    fun loadData(no: Int): Unit = throw CodeEE("LOADDATA 需要宿主提供读档能力（SystemOps）")

    /** DELDATA。spec: FunctionIdentifier.cs:1944-1957 */
    fun delData(no: Int): Unit = throw CodeEE("DELDATA 需要宿主提供存档删除能力（SystemOps）")

    /**
     * SAVEGAME 的**宿主前置闸**（缺省放行；移植侧扩展，原版无对应调用）。
     *
     * 原版 `SAVELOADGAME_Instruction.DoInstruction`（`FunctionIdentifier.cs:2967-2980`）在
     * `__CAN_SAVE__` 门之后只有两句进程内动作，没有任何宿主调用：
     * `GlobalStatic.Process.saveCurrentState(single: true)`（`:2978` → `Process.cs:1959-1966`）
     * 与 `GlobalStatic.Process.getCurrentState.SaveLoadData(isSave)`（`:2979` →
     * `ProcessState.cs:190-200`）。移植侧的对位实现落在
     * `sem/StatementExecutor.execSaveLoadGame`（状态迁移，经 `host/SaveLoadHandoff`）与
     * `proc/ProcessRuntime.executeStatement`（整栈丢弃），**不经过本钩子**。
     *
     * 本钩子只回答「宿主愿不愿意进入存档界面」：抛错 = 拒绝，此时状态不迁移、脚本栈不动
     * （与「门挡下」同形，只是文案由宿主给）。缺省 no-op = 放行。待存档界面落地后本方法
     * 可整对删除（届时宿主改由系统状态机 `SaveGame_Begin` 驱动）。
     */
    fun saveGame() {}

    /** LOADGAME 的宿主前置闸，语义与 [saveGame] 同（原版 `:2979` 只差 `SaveLoadData(false)`）。 */
    fun loadGame() {}

    /** SAVEGLOBAL。spec: FunctionIdentifier.cs:1801-1813 */
    fun saveGlobal(): Unit = throw CodeEE("SAVEGLOBAL 需要宿主提供全局存档能力（SystemOps）")

    /** LOADGLOBAL：返回 true=成功。spec: FunctionIdentifier.cs:1815-1834 */
    fun loadGlobal(): Boolean = throw CodeEE("LOADGLOBAL 需要宿主提供全局存档能力（SystemOps）")

    /** SAVECHARA（角色号列表 + 文件名 + 描述）。spec: FunctionIdentifier.cs:1865-1898 */
    fun saveChara(nos: List<Long>, filename: String, text: String): Unit =
        throw CodeEE("SAVECHARA 需要宿主提供角色存档能力（SystemOps）")

    /** LOADCHARA。spec: FunctionIdentifier.cs:1900-1914 */
    fun loadChara(filename: String): Unit =
        throw CodeEE("LOADCHARA 需要宿主提供角色读档能力（SystemOps）")

    /** UPDATECHECK：返回 RESULT 值（0-5）。spec: FunctionIdentifier.cs:2757-2839 */
    fun updateCheck(): Long = throw CodeEE("UPDATECHECK 需要宿主提供网络能力（SystemOps）")

    /** CALLTRAIN / STOPCALLTRAIN / DOTRAIN。spec: Process.cs:1918-1954 */
    fun callTrain(no: Long): Unit = throw CodeEE("CALLTRAIN 需要宿主提供训练流程能力（SystemOps）")
    fun stopCallTrain(): Unit = throw CodeEE("STOPCALLTRAIN 需要宿主提供训练流程能力（SystemOps）")
    fun doTrain(no: Long): Unit = throw CodeEE("DOTRAIN 需要宿主提供训练流程能力（SystemOps）")

    /** UPCHECK / CUPCHECK（UP/PALAM 结算，可能伴随输出）。spec: Process.cs:1255-1263 */
    fun upcheck(): Unit = throw CodeEE("UPCHECK 需要宿主提供参数结算能力（SystemOps）")
    fun cupcheck(target: Long): Unit = throw CodeEE("CUPCHECK 需要宿主提供参数结算能力（SystemOps）")
}

/**
 * PRINTDATA/STRDATA 数据节供给接口。
 *
 * 数据节消费需要块结构信息（PRINTDATA..ENDDATA 之间的 DATA 行），这属 proc 运行时；
 * 语句层只要求「可求值的字符串表达式列表」（DATA → StrLiteral，DATAFORM → FormStringTerm）。
 * proc 运行时在分派 PRINTDATA 时注入。
 *
 * ## 键 = 语句池下标（T2-64 波次 C）
 *
 * 侧车 `BootMetadata.dataSections` 本来就按**语句池下标**存（全量 3,874 节，
 * `core/loader/BootMetadata.kt:102-109`），语句层消费时拿到的也正是池下标
 * （扁平通道 = marker 指令的 `a` 操作数）。旧口径「键为该 PRINTDATA 语句实例」要一层
 * `IdentityHashMap` 反查（3,874 次 `Program.statement(i)`），波次 C 连同那层一起删除。
 *
 * 口径后果：**同一语句池里同一语句恒为同一下标 ⇒ 同下标恒同组**（波次 C 的验收判据）；
 * 越界下标返回 null（语句层按 no-op 处理，与「本来没有数据节」同形）。
 */
fun interface DataSections {
    /** 语句池下标 → 数据节组（每组 = 一行 DATA 的可求值表达式列表）；未登记 = null。 */
    fun groupsOf(stmtIndex: Int): List<List<com.eratw.port.core.expr.Expr>>?

    /**
     * 键是**语句实例**的读面 —— 对象树通道专用（`Program.flatStatementsEnabled == false`；
     * Android 生产就是这条路，见 `GameHost` 的 `loadMapped(flatStatements = false)`）。
     *
     * 树通道的 `PRINTDATA`/`STRDATA` 手里只有语句实例
     * （[com.eratw.port.core.sem.StatementExecutor] 的 `execPrintData`/`execStrData`），
     * 必须由实现方用「实例 → 池下标」反查表换到 [groupsOf] 上
     * （生产实现见 `ProcessRuntime.installDataSectionStatementIndex`）。
     *
     * 默认实现**显式失败**：没有反查能力的替身静默查空会把「有数据节」与「本来没有」混成
     * 同一个 no-op（真机 0.2.1 之前的缝就是这么来的：树通道一直没人覆写，扁平通道全开时
     * 桌面闸看不见）。只有不需要服务树通道的实现才可以不管它。
     */
    fun groupsOf(stmt: Statement): List<List<com.eratw.port.core.expr.Expr>>? =
        throw UnsupportedOperationException(
            "对象树通道的数据节查询未实现：实现方须把语句实例反查成语句池下标",
        )
}

/**
 * 语句执行语境 —— 聚合变量池、各宿主 sink、表达式求值语境与进程级状态
 * （对应原版 ExpressionMediator + ProcessState + Process 的执行路径可见状态）。
 */
class ExecContext(
    val variables: VariableStore,
    /** 表达式求值语境（变量读取 + 函数调用桥）。 */
    val eval: EvalContext,
    val console: ConsoleSink = NoopConsoleSink,
    val input: InputSink = NoopInputSink,
    /**
     * 计时宿主（`AWAIT` + 时间函数族的时钟入口，见 [TimerSink]）。缺省 [NoopTimerSink] =
     * `AWAIT` 不睡、装置读数与既有纯 JVM 闸逐条一致（语料闸 / E2E / bootSmoke 不受影响）。
     *
     * 声明成 `var` 的理由与 [graphics] 同：真机上这条缝要等 `Context` 齐了才接得起来，
     * 而 sem 每次执行 `AWAIT` 都是现读 `ctx.timer`。Android 侧必须换上真实现 ——
     * 否则 `@BADAPPLE` 那种每帧 `AWAIT 16` 的动画会以全速跑完，画面与配乐脱节。
     * 写入方必须是 VM 线程（或在 `drive()` 之前完成），本字段不带锁。
     */
    var timer: TimerSink = NoopTimerSink,
    val sound: SoundSink = NoopSoundSink,
    /**
     * 图形宿主 sink 注入点（M10-GFX）。缺省 [NoopGraphicsSink] = 图形语句静默空操作，
     * 既有纯 JVM 装置（语料闸 / E2E / bootSmoke）的读数因此不受影响。
     *
     * 声明成 `var` 而不是构造参数唯一通道，是因为真机装配链上
     * `GameHost → GameSession → ProcessRuntime` 的构造点在别的 lane 手里，
     * 而 Android 侧的 sink 要等 `Context` / 解包根 / 屏幕尺寸都齐了才建得出来；
     * 语句层每次都是现读 `ctx.graphics`（`StatementExecutor.kt:385-422` 六条图形语句），
     * 所以「装载完成后换上真 sink」与「构造时就传进来」逐次执行等价。
     * 写入方必须是 VM 线程（或在 `drive()` 之前完成），本字段不带锁。
     */
    var graphics: GraphicsSink = NoopGraphicsSink,
    /**
     * 当前脚本行标识（原版 `Process.getCurrentLine` 的身份位）。
     *
     * 语句层在构造 [com.eratw.port.core.host.InputRequest] 时把它带进 `scriptLine`，
     * 控制台的按钮世代推进（`EmueraConsole.newGeneration`，EmueraConsole.cs:783-823）
     * 靠它判断「脚本是否换了一行」。null = 未接（纯 sem 单测），控制台退回行水位近似。
     * 写入方必须是 VM 线程（`ProcessRuntime` 构造期注入）。
     */
    var scriptLineToken: (() -> Long)? = null,
    val debug: DebugSink = NoopDebugSink,
    val config: SemConfig = SemConfig(),
    /** 存档/系统级钩子；未注入时对应语句报「未实现」。 */
    var systemOps: SystemOps? = null,
    /**
     * `DOTRAIN` 的**进程内**实现（原版 `Process.cs:1931-1954`，`doNormalFunction` 的
     * DOTRAIN 分支）。它不是宿主能力：状态校验、`doTrainSelectCom` 赋值与
     * `state.SystemState = Train_DoTrain` 全都写在同一份 [com.eratw.port.core.proc.BeginFlow]
     * 状态机里，故由 proc 层注入；null = 纯 sem 装置（单测），照旧报「未实现」。
     *
     * 为什么不能留在 [SystemOps]：`SystemOps` 是存档/文件/网络等**宿主**能力，
     * 生产实现 `FileSystemOps` 与它无关；把 DOTRAIN 放那里会让语料的
     * `USERCOM → DOTRAIN 999 → SOURCE_CHECK → COM{SELECTCOM}` 主指令链在宿主未覆写时
     * 整条断掉（2026-10-26 实测：日 1 指令菜单点击后只重绘、不执行）。
     */
    var doTrainHook: ((Long) -> Unit)? = null,
    /** PRINTDATA/STRDATA 数据节供给；未注入时这些语句为无输出 no-op（跳转仍由 VM 完成）。 */
    var dataSections: DataSections? = null,
    /**
     * PRINTFORMS 家族的运行期二次 FORM 展开（原版 DoInstruction 对求值后的字符串再跑
     * CheckEscape + AnalyseFormattedString，spec: FunctionIdentifier.cs:205-209）。
     * 解析期无法预知求值结果，故由宿主/运行时注入；null = 原样输出（未接线，仅单元测试）。
     */
    var runtimeForm: ((String) -> String)? = null,
    /**
     * RETURNFORM 的运行期逗号整数列表解析（ErbLexer 按 `,` 切段 + ExprParser 求值，
     * spec: FunctionIdentifier.cs:3223-3231）。空串返回空列表（RESULT 补 0 归 sem）。
     * null = 宿主未接线，sem 显式抛 CodeEE（不是静默 no-op）。
     */
    var runtimeIntList: ((String) -> List<Long>)? = null,
    /**
     * 当前帧是否是 `#FUNCTION`/`#FUNCTIONS` 方法（原版 `ProcessState.IsFunctionMethod`，
     * 语义见 `ProcessState.cs:289-295`）。只有 `RETURN` 需要它：
     *
     * 原版 `ProcessState.Return(ret)` 的**第一条**就是 `if (IsFunctionMethod) { ReturnF(null); return; }`
     * —— 方法帧内的 `RETURN` 不走「弹帧 + 交返回值」，而是转成 `ReturnF(null)`，
     * 把 `MethodReturnValue` 清成 **null**；消费侧 `SuperUserDefinedMethodTerm.GetIntValue`
     * 于是拿到 0（字符串方法同理得 ""）。也就是说 `RETURN 5` 写在 `#FUNCTION` 里，
     * 方法返回值仍是 0，而 RESULT 数组照旧被 SetResultX 写成 5
     * （`FunctionIdentifier.cs:3265-3271` 先写 RESULT、再 `state.Return(RESULT:0)`）。
     *
     * 缺省 `{ false }` = 宿主未接线（纯语句层单测）⇒ 按普通函数帧处理，与旧行为一致。
     */
    var isFunctionMethodFrame: () -> Boolean = { false },
) {
    // ---- 进程级显示状态（对应 Process.skipPrint / Console.MesSkip）----
    //
    // 两个标志的真身都在**每 VM 的 aux 跳过状态槽**（core/fn/InputMethods.kt 的
    // SKIP_STATE_AUX_SLOT 槽，值类型 SkipStateSlot）：语句层在这两个属性上写，fn 层
    // ISSKIP / MESSKIP / MOUSESKIP 直接读同一口槽。属性只是槽的视图，本类**不再另存字段**
    // ——另存一份就是第二真相，SKIPDISP 进行中 fn 会查到旧值（HOST-01 的成因）。
    // 槽按名字现取（不缓存引用）：aux 被 clear() 重建运行时后，这里与 fn 同步看到新槽。

    init {
        // 构造即落槽并写初值 false/false（= 原版新进程的 Process.skipPrint / Console.MesSkip，
        // Process.cs:160、EmueraConsole.cs:148）：同一个 store 上另起语句层语境时也回到出厂态，
        // 与旧实现「两个字段各自初始化为 false」等价。fn 层据此把「本 VM 已接线」与「没有语句层」
        // 区分开，不让纯 fn 装置误报未接线。
        val slot = skipStateSlot(variables.aux)
        slot.skipPrint = false
        slot.mesSkip = false
    }

    /** SKIPDISP 设置的显示跳过标志：true 时 PRINT 系整条抑制。spec: Process.cs:1587-1592（直写 :1589） */
    var skipPrint: Boolean
        get() = skipStateSlot(variables.aux).skipPrint
        set(value) {
            skipStateSlot(variables.aux).skipPrint = value
        }

    /** 消息跳过标志（MesSkip；SKIPLOG 直写 Process.cs:1832，输入消费路径另写 EmueraConsole.cs:1373、:1456）。 */
    var mesSkip: Boolean
        get() = skipStateSlot(variables.aux).mesSkip
        set(value) {
            skipStateSlot(variables.aux).mesSkip = value
        }

    /**
     * true = 当前系统状态带 `__CAN_SAVE__` 位，`SAVEGAME` / `LOADGAME` 可以执行。
     * spec: `FunctionIdentifier.cs:2956-2981` —— `SAVELOADGAME_Instruction.DoInstruction`
     * 先判 `(state.SystemState & SystemStateCode.__CAN_SAVE__) != __CAN_SAVE__`，不成立即
     * `CodeEE("不能使用{当前函数名}命令")`（`Process.cs:2971-2976`：`state.Scope` 取函数栈顶
     * 的函数名，空栈为空串；`Lang.Error.CanNotUseInstruction`，lang/emuera.zhs.xml:785，
     * 模板只有一个 `{0}`，第二实参 `"SAVEGAME/LOADGAME"` 被 string.Format 丢弃 —— 官方怪癖，
     * ADR-005 原样复刻）。真值由 proc 运行时逐语句写入（`ProcessRuntime.executeStatement`
     * 现读 `systemState.canSave`，对应原版在 DoInstruction 时点读 `state.SystemState`）。
     *
     * 缺省 true：不经 proc 运行时的纯 sem 装置（单测等）没有系统状态可读，保持中性；
     * 有 proc 接线时该缺省值会在第一条语句派发前被覆盖。
     */
    var canSave: Boolean = true

    /** NOSKIP 进入前的 skipPrint 快照。spec: Process.cs:1593-1613 */
    var saveSkip: Boolean = false

    // ---- TRYC/PARTIAL(16) 语境门控（契约 §5 R3 附带项；sem 只提供标志位，判定归 proc 语境）----
    // 原版 TRYC 家族 flag = 32784 = IS_TRYC(32768) | PARTIAL(16)（spec: FunctionIdentifier.cs:3516、:3651；
    // 常量 PARTIAL=16 见 :3729）。PARTIAL 位确有读取者（IsPartial() 定义 :4265-4268），且两处禁用判定
    // 都发生在**装载/语境**层而非语句执行层，故由 A4 的 ProcessRuntime 写入、A3/编译器消费：

    /**
     * true = 当前处于 `#FUNCTION` 方法体内。TRYC 家族（flag 含 PARTIAL(16)）在该语境**无条件禁用**，
     * 原版直接 CodeEE「指令不能使用」（spec: EmueraConsole.cs:2216-2219，IsPartial() 分支）。
     * 由 A4 ProcessRuntime 进入/离开方法体时写入。
     * TODO(verify): 我们的 TRYC 已由编译器降级（R3 块配对），sem 分派路径看不到 PARTIAL 位；
     * 本标志保留给 proc 层门控与 golden 对拍复现装载报错场景。
     */
    var inFunctionMethod: Boolean = false

    /**
     * true = 紧邻的上一逻辑行是 SIF。紧随 SIF 之后的 PARTIAL 语句原版给装载告警
     * （ParserMediator.Warn level 2、isError=true，**不是异常、不回跳**；
     * spec: FunctionIdentifier.cs:3049-3052；契约 §5 R3 引的 "EmueraConsole.cs:3049-3052" 系笔误，
     * 真实读取点在 FunctionIdentifier.cs，已回报组长）。
     * 行窗口语义（"下一行"）属 proc 状态机，由 A4 ProcessRuntime 逐行写入。
     * TODO(verify): 同上 —— 消费点在装载期；运行期本标志仅供 proc 门控与对拍。
     */
    var afterSifLine: Boolean = false
}
