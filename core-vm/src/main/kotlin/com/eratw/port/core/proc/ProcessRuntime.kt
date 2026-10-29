package com.eratw.port.core.proc

import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.compile.EXPR_STR_TEMP_PREFIX
import com.eratw.port.core.compile.EXPR_TEMP_PREFIX
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.UserFunctionKind
import com.eratw.port.core.fn.EmDataPools
import com.eratw.port.core.fn.formatDotNet
import com.eratw.port.core.save.SaveWiring
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.ConsoleBridge
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphMaskHostProvider
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopGraphicsSink
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.host.SemConfigHost
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.sem.DataSections
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.ExecResult
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.config.ConfigChainSource
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.toExprTokens
import com.eratw.port.core.vars.RefUserVar
import com.eratw.port.core.vars.Storage
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.sem.FlatStmtSource
import java.util.IdentityHashMap

/**
 * 函数调用运行时 —— 原版 `GameProc.Function`（CalledFunction + ProcessState + CALL 家族语句）
 * 的移植核心。
 *
 * ## 三层职责
 *
 * **1. VmHost 桥（VM 驱动的调用全生命周期）**：[VirtualMachine] 的 `_CALL`/`_RET` 触发
 * [onFunctionEnter]/[onFunctionExit]，本类在此完成：
 * - 作用域进入/离开：[VariableStore.enterFunction]/[leaveFunction]（私有 #DIM 栈式嵌套）。
 *   spec: ProcessState.cs:438-450（IntoFunction → ScopeIn + 实参 SetValue）、
 *   Process.cs:531-536（ScopeOut）。
 * - 实参绑定：按位置把实参写入 ARG / ARGS 两套数组（见下方"调用约定"）。
 * - 局部长度注册：#LOCALSIZE / #LOCALSSIZE / 形参数 → [VariableStore.defineLocalLengths]。
 *
 * **2. 根级调用 API（宿主 / 未来语句执行层驱动）**：[execute] / [callFunction] / [tryCall] /
 * [callForm] / [callF] / [tryCallF] / [executeEvent] / [begin]。原版这些语义分散在
 * Process.cs 的状态机里；本类把它们收拢为显式 API。
 *
 * **3. 语句桥（契约 C4/C5/C6，M2 执行层接线）**：VM 主循环把 marker 指令
 * （op 29..197，a=语句池下标）交给 [executeStatement]，sem 的 [StatementExecutor] 在
 * [execContext] 上执行语句语义；`ExecResult.Normal/Quit/Begin` 映射为
 * `StmtOutcome.Normal/Stop`。动态名 CALL 走 `_CALL_BY_NAME` → [resolveFunctionIndex]
 * （命中与 `_CALL` 同一条建帧路径，VM 内部共用 callInto，天然无重入）。
 * 文字流出口唯一：VM 的 `print` 与 sem 语句的 `ExecContext.console` 是**同一个**
 * [consoleBridge]（C6）；[output] 只是它的累计镜像。
 *
 * ## 调用约定（与 compile agent 的字节码契约）
 *
 * - 调用点把整型实参按序放入自己帧的 R0..R(ni-1) **整数槽**，字符串实参按序放入
 *   R0..R(ns-1) **字符串槽**（两套平行数组同槽号）；`_CALL` 的 b 操作数 = max(ni, ns)。
 * - [onFunctionEnter] 触发时 VM 尚未切换 frameBase，运行时经 [VirtualMachine.getInt] /
 *   [VirtualMachine.getStr] 读到的正是调用方帧的实参槽，随后按被调函数的
 *   argIntCount / argStrCount 绑定 ARG:0.. / ARGS:0..。
 * - 分桶按位置 = 原版按形参位置绑定：ConvertArg 强制第 i 个实参类型与第 i 个形参一致
 *   （str→int 直接报错，int→str 按 CompatiFuncArgAutoConvert 包 TOSTR），故两口径等价。
 *   spec: CalledFunction.cs:143-200。
 * - 缺省实参在**调用点**替换为默认值表达式后传满全部形参（spec: CalledFunction.cs:165-173，
 *   Def[i] 替换）；`ARG`/`ARGS`/私有形参的隐式默认 0/"" 由 [bindImplicitArgDefault]
 *   在绑定点补写（spec: ErbLoader.cs:714-722）。实参类型失配与省略位的报错/写值同属
 *   原版 ConvertArg 的分派，逐条见 [bindCallArgs] 的 KDoc。
 *   实参过多（TooManyFuncArgs，CalledFunction.cs:136-140）由 [checkTooManyArgs] /
 *   [requireArgCount] 在建帧绑定点补齐（`{0}函数: 参数过多`，zhs:901）；原版静态名在
 *   装载期就 Warn 成 error，本侧相位晚于装载期（与省略位/类型失配同一条时点差）。
 * - 根级调用（无调用方帧）时实参由 API 参数直接给出：`List<Value?>` 通道（[invokeUserMethod]
 *   / [callF] / [tryCallF]）位置完整 ⇒ [bindPositionalValues] 按形参位绑定；旧
 *   `callFunction(key, intArgs, strArgs)` 通道的 int/str 两数组本身已丢位置 ⇒
 *   [bindRootByName] 按形参类型序从桶里取（等价于调用方已按类型排过序）。
 *
 * ## 根级执行机制（任意入口）
 *
 * begin()/事件派发/根级 CALLF 需要从**任意函数**启动。核心 VM 直接支持
 * [VirtualMachine.run]`(entryFunctionIndex)`：目标函数就是第 0 帧，顶层 `_RET`/`_HALT`
 * 结束本次 run（见该重载 KDoc 的等价性说明）。
 *
 * 因此本类**只持有一份** [Program]：不复制 code/lines、不克隆常量池/语句池、不追加合成
 * 根指令。所有执行（含 [execute]）都走同一个 [vm]，全局槽天然同源；内存里不存在
 * 第二份指令流（ADR-002 的 400MB 预算下，全语料 4.6M 指令的拷贝约 74MB + 18.5MB lines）。
 *
 * ## 表达式位置的函数调用（A16 接线，[evalContext]）
 *
 * [EvalContext.callFunction]/[EvalContext.callFunctionRef] 有四条通道（前两条不需要建帧）：
 * 1. **编译期降级的用户 `#FUNCTION`/`#FUNCTIONS` 方法调用**：读回 `#EXPR_RET#<k>` /
 *    `#EXPR_RETS#<k>` 哨兵对应的调用方帧槽
 *    （降级形态与哨兵约定见 `ErbProgramCompiler.kt` 的 [ExprCallTable] / [EXPR_TEMP_PREFIX] KDoc，
 *    喂表方 = erbcc 桥 `com.erbcc.bridge.buildExprCallTable`）；
 * 2. **编译期未降级的用户方法**（惰性求值位：`&&`/`||` 右侧、三目分支、`\@?#\@` 条件位，
 *    以及返回类型未知的方法）→ [invokeUserMethod] 的**运行期重入**通道，与原版同形：
 *    原版就是"解析期绑成 `UserDefinedMethodTerm`、求值期在表达式内部进被调帧"
 *    （spec: Process.cs:514-540），这类调用点在编译期提为前导 `_CALL` 会把
 *    "可能根本不执行"的副作用变成必然执行（伪造副作用），故不降级、走重入；
 * 3. **内建函数** → 直接查 `core/fn` 的 271 注册表执行（[BuiltinEvalContext]）；
 * 4. 用户 `#FUNCTION` **遮蔽**同名内建，故 2 的判定必须先于 3
 *    （spec: IdentifierDictionary.cs:548-586）。
 *
 * 语句位置的 CALLF/CALLFORMF 用 [callF]（根级）；CALLFORM/TRYCALLFORM 动态名调用编译为
 * `_CALL_BY_NAME`，经 [resolveFunctionIndex] 在 VM 帧模型内完成（不重入 run()）。
 */
class ProcessRuntime(
    val program: Program,
    val store: VariableStore,
    functionTable: FunctionTable,
    config: VmConfig = VmConfig(),
    /** 原始 print 透传（可选；文字流同时进 [consoleBridge] 与 [output]）。 */
    private val printSink: ((String) -> Unit)? = null,
    /** 文字流宿主出口（契约 C6：与 [execContext] 的 console 同一个桥接 sink；null = 只镜像进 [output]）。 */
    private val consoleSink: ConsoleSink? = null,
    /** 语句层配置注入点（sem 契约：[ExecContext.config]）。 */
    semConfig: SemConfig = SemConfig(),
    /** 存档/系统语句宿主钩子注入点（sem 契约：[ExecContext.systemOps]，构造后经 [execContext] 亦可换）。 */
    systemOps: SystemOps? = null,
    /**
     * 内建函数宿主注入点（`core/fn` 契约：[BuiltinHost] 的 clockMillis / moneyLabel /
     * moneyLabelFirst / saveDataNos / lineIsEmpty / 音频四项）。null = 由 [semConfig] 派生
     * （见 [SemConfigHost]，配置项不另立第二套真相）；lineIsEmpty 需 M3 渲染层的
     * 控制台行状态查询面，本遍恒 false；音频四项（soundChannels / isPlayingSound /
     * isPlayingBgm / audio）绑 [VariableStore.aux] 的声道表——同源单表，见 [SemConfigHost]。
     */
    builtinHost: BuiltinHost? = null,
    /**
     * 函数键（大写）→ 源文件显示名（FN-05），UNICODE 告警的 WarnPrefix 用。
     *
     * 缺省 null = 从 [FunctionTable.sourceFileOf]（`FuncMeta.position` 的 `文件:行号`）
     * 反推，不额外持有侧车的 `fileByKey` 表；显式注入优先，供装置钉文件表。
     * 内存编译/测试无 position ⇒ 当前行恒 null，走原版无前缀回落（:5713-5716）。
     */
    private val sourceFileOf: ((String) -> String?)? = null,
    /**
     * RNG 种子（[store] 未注入 randProvider 时接 SFMT19937 用）。null = 时钟——
     * 原版进程级 `new MTRandom()` = Environment.TickCount（MTRandom.cs:25-27），
     * 移植口径 = 当前毫秒低 32 位有符号。宿主自行注入 `store.randProvider` 时本参数无效。
     */
    randSeed: Long? = null,
    /**
     * BEGIN 状态机宿主面（BOOT-2）：默认 [BeginHost.NONE] = 所有能力缺失，
     * 状态机在第一个需要输入/读档的步骤返回 BLOCKED，不伪造结果。
     */
    private val beginHost: BeginHost = BeginHost.NONE,
    /**
     * 语句层音频 sink 注入点（sem 契约：直接成为 [ExecContext.sound]）。缺省
     * [NoopSoundSink] = 宿主没有音频后端：PLAYSOUND / STOPSOUND / PLAYBGM / STOPBGM /
     * SETSOUNDVOLUME / SETBGMVOLUME 六条全为空实现，基准 SOUND-MISSING 读数
     * （fixtures/golden/baseline/pc-golden-root.txt:519-522）依赖该缺省。
     * 接生产后端时传 [com.eratw.port.core.audio.TableSoundSink] 的 aux 构造
     * （`TableSoundSink(store.aux, resolver)`）：它与 [SemConfigHost] 的四项查询共用
     * store.aux 的同一张声道表，语句层播的东西函数层（SOUNDCONTROL 等）立刻读得到。
     */
    soundSink: SoundSink = NoopSoundSink,
    /**
     * 语句层输入 sink 注入点（sem 契约：直接成为 [ExecContext.input]）。缺省
     * [NoopInputSink] = 等待立即返回请求默认值（`InputSink.kt:55-58`），既有装置
     * （`GameBootSmoke` / E2E 装置）的输入读数因此不受影响。
     *
     * 生产装配传 [QueueInputSink]（与 `ConsoleBeginHost` 共用同一条队列）：
     * `ExecContext.input` 是单例注入点，构造后换 sink 需要重跑 `execContext` 的装配，
     * 故只在这里给。
     */
    inputSink: InputSink = NoopInputSink,
    /**
     * 语句层图形 sink 注入点（sem 契约：直接成为 [ExecContext.graphics]）。缺省
     * [NoopGraphicsSink] = `PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE` / `SETBGIMAGE` /
     * `REMOVEBGIMAGE` / `CLEARBGIMAGE` 六条静默空操作（M10-GFX 之前真机的「屏上没有画面」
     * 就是这里没接线）。
     *
     * 生产装配走 `:platform` 的 `AndroidGraphicsSink`；它建不出来时（会话已构造、屏幕才布局
     * 完）可以直接改 `execContext.graphics`（同一注入点，语句层现读），两条路等价。
     */
    graphicsSink: GraphicsSink = NoopGraphicsSink,
    /**
     * output 镜像的封顶字符数（超限即停写，一次性封顶，不做环形）。为什么要有上限：
     * [output] 镜像在生产路径**零读者**（只有测试读它），而文字游戏一局长跑会产出
     * 海量 print 文本——无界累积的 StringBuilder 在 512MiB 堆上必然 OOM，且翻倍扩容
     * 的拷贝峰值更狠。控制台本体（LineBufferConsoleSink）自带 900 行滚动，权威输出
     * 永远在控制台侧；镜像只为测试对账存在，测试文本远小于 256K 默认值，封顶不影响
     * 任何既有断言，也不改变任何脚本可见行为（镜像只是内部副本）。
     */
    maxMirrorChars: Long = 262_144L,
    /**
     * **引擎级函数屏蔽表**（[EngineFunctionPolicy]）：命中名单的函数按「空体 `RETURN 0`」
     * 处理，语料里怎么调都拦得住（静态 `CALL` / 动态 `CALLFORM` / `CALLF` / 尾跳 / 根级 API
     * 全部走同一张表，见 [isStubbedFunction] 与 [runEntry]）。
     *
     * 缺省 [EngineFunctionPolicy.DEFAULT_DISABLED]（含 `BADAPPLE`）= 引擎出厂口径：
     * 任何人的语料都不必手工注释那一行 `CALL BADAPPLE`。传 `emptySet()` = 全部放行
     * （设置页把开关关掉时宿主这样传）。
     *
     * 名字匹配大小写不敏感（口径同 `FuncMeta.key` / `Config.StrComper`，spec: `Config.cs:33`）。
     */
    disabledFunctions: Set<String> = EngineFunctionPolicy.DEFAULT_DISABLED,
) : VmHost {

    private val table: FunctionTable = functionTable

    /**
     * 屏蔽表的下标位图（[Program.functions] 下标 → 是否屏蔽）+ 实际命中的函数名。
     *
     * 为什么要位图而不是每次按名查：VM 每执行一条 `_CALL` 都要问一次
     * （[isStubbedFunction]），按名查会变成每条调用一次 `String.uppercase` + 哈希 ——
     * 位图是一次 `BooleanArray` 读 + 一次比较，且只在构造期算一次
     * （110,072 个函数 × 1~2 个名字的 `equals(ignoreCase)`，毫秒级）。
     */
    private val stubTable: StubTable = StubTable.of(program, disabledFunctions)

    /** 实际被屏蔽的函数名（大小写按 `Program.functions` 里的原名；空 = 一个都没命中）。 */
    val stubbedFunctionNames: List<String> get() = stubTable.names

    /**
     * 文字流累计镜像（测试便捷）。**契约 C6：单一出口是 [consoleBridge]**——
     * VM `_PRINT_*`（[print]）与 sem 语句（[ExecContext.console]）都写经它，
     * 本缓冲只是桥接 sink 的同源镜像，不是第二套输出语义。
     *
     * 镜像只为测试对账存在（生产路径零读者），超过 [maxMirrorChars] 即停写；
     * 控制台的滚动缓冲才是权威输出来源，封顶不改变任何控制台输出。
     */
    val output = StringBuilder()

    /**
     * 语句级观测口注入点（CONTRACT-DBG1 路1，形状见 [StatementProbe]）：非 null 时
     * [executeStatement] 在每条真实 ERB 语句执行完毕后同步回调一次。
     * **null = 生产缺省 = 零行为**——[executeStatement] 的守卫结构保证探针缺席时不取时钟、
     * 不进 try/catch、零分配、零虚调用。@Volatile：宿主线程赋值、VM 线程消费
     * （ADR-003 单线程执行），只需这一处跨线程可见性。
     */
    @Volatile var statementProbe: StatementProbe? = null

    /**
     * 函数级观测口注入点（DBG4 P3，形状见 [FunctionProbe]）：非 null 时
     * [onFunctionEnter] / [onFunctionExit] 在每次帧进出时同步回调一次。
     * **null = 生产缺省 = 零行为**——两个 override 的守卫结构保证探针缺席时零分配、
     * 零虚调用（与 [statementProbe] 同款判据）；@Volatile 单写多读（ADR-003）。
     */
    @Volatile var functionProbe: FunctionProbe? = null

    // ------------------------------------------------------------------
    // VM（唯一一份 Program，任意入口执行）
    // ------------------------------------------------------------------

    /**
     * 主 VM：直接持有 [program] 本体（不复制 code/lines/池）。根级调用经
     * [VirtualMachine.run]`(entryFunctionIndex)` 从目标函数起跑。
     */
    private val vm: VirtualMachine

    /** VM 运行配置（构造参数留存：嵌套 VM 派生需同款步数/深度闸）。 */
    private val vmConfig: VmConfig = config

    /**
     * 当前指令流的 VM：主 VM，或 [invokeUserMethod] 重入期间借出的嵌套 VM。
     * 帧槽读取点（[bindArgs] 的实参槽、[evalContext] 哨兵 `#EXPR_RET#k` 读槽）必须跟随
     * 执行流切换——重入执行期间读主 VM 会命中其暂挂帧的槽值（垃圾）。init 块赋值。
     */
    private var activeVm: VirtualMachine

    init {
        // T2-64：扁平语句池在桥构建期一次 encode（幂等）。**受装载期开关控制**：
        // `ProgramLoader.loadMapped(flatStatements = …)` 写入 `Program.flatStatementsEnabled`，
        // Android 传 false（整池压扁 +437.5 MiB，512 MiB 堆挡位装不下，见 CONTRACT-T2-64 §10.6）；
        // 桌面/测试默认 true。关掉时 `flatStmtSource` 恒 null，VM marker 走对象树老路。
        if (program.flatStatementsEnabled) program.ensureFlatStmtSource()
        // ADR-001：RNG 必须走注入的 provider；缺省接 SFMT19937（禁止 kotlin.random 兜底，
        // 非确定性 = 验收不可复现）。spec: VariableEvaluator.cs:28（进程级 rand）+ rng.md §4。
        if (store.randProvider == null) {
            store.randProvider = Sfmt19937(randSeed ?: System.currentTimeMillis().toInt().toLong())
        }
        vm = VirtualMachine(program, this, config)
        activeVm = vm

        // 局部长度注册（spec: VariableLocal.cs:40-90 —— LOCAL/LOCALS 声明优先，ARG/ARGS 取 max）
        // PERF: the full corpus has 110,072 metadata rows; the old loop allocated
        // 110,072 LocalLengths objects plus 110,072 uppercase() calls and HashMap
        // puts. Two equivalent merges:
        // 1) skip rows whose four lengths are all zero: resolveLocalSize reads
        //    `len?.x ?: 0`, so "key absent" and "present with all-zero
        //    LocalLengths" produce the same 0 for LOCAL/LOCALS/ARG/ARGS, and this
        //    map has no iteration reader => final observable semantics are equal;
        // 2) count non-zero rows first and pre-size the bucket table once
        //    (reserveLocalLengths), avoiding repeated HashMap rehash/copies.
        val functions = table.all()
        var lengthEntries = 0
        for (m in functions) {
            if (m.localSize != 0 || m.localsSize != 0 || m.argIntCount != 0 || m.argStrCount != 0) {
                lengthEntries++
            }
        }
        store.reserveLocalLengths(lengthEntries)
        for (m in functions) {
            if (m.localSize == 0 && m.localsSize == 0 && m.argIntCount == 0 && m.argStrCount == 0) {
                continue
            }
            store.defineLocalLengths(
                m.key,
                VariableStore.LocalLengths(
                    local = m.localSize,
                    locals = m.localsSize,
                    arg = m.argIntCount,
                    args = m.argStrCount,
                ),
            )
        }
    }

    /**
     * `CompatiCALLNAME`（`ConfigCode.cs:56` = 52；条目出厂默认 false `ConfigData.cs:96`；
     * 本作 `emuera.config:50` = `USE NAME IF CALLNAME IS EMPTY:YES`）的现读 ——
     * `Config.cs:366` 由 `SetConfig` 从条目表回填静态字段。
     */
    private fun compatiCallname(): Boolean = configBool(ConfigKey.CompatiCALLNAME)

    /**
     * CALLNAME 为空时以 NAME 填充（`CompatiCALLNAME` 的唯一运行期可见效果）。
     *
     * spec：`ConstantData.cs:168`（构造期把开关捕进 `useCompatiName`）→ `:1263-1272`
     * （全部 `CHARA` CSV 装载完、`SetSpFlag`/重号检查之前，逐模板
     * `if (string.IsNullOrEmpty(tmpl.Callname)) tmpl.Callname = tmpl.Name`；
     * 任务锚点 `:1268` = 内层 if 的左花括号，赋值在 `:1269`）。
     * 下游读点：`CharacterData.cs:133`（`dataString[1] = tmpl.Callname`，即 `CALLNAME`
     * 变量列，`VariableEvaluator.cs:1594-1599` 同源）、`VariableEvaluator.cs:1594-1599`
     * （`CSVCALLNAME` 直读模板）、`ConstantData.cs:747-749`（模板名反查表 `relationDic`）。
     *
     * **相位差异（登记，未复刻）**：原版改的是**模板**，本方法改的是**已入列角色的列**。
     * 因此在装载面完成之前不可达的三处仍取模板原值：
     * 1. 会话开始后 `ADDCHARA` 新建的角色（`VariableStore.addCharacter` 从模板拷贝）；
     * 2. `CSVCALLNAME`（读模板，不是角色列）；
     * 3. 模板名反查（`VariableStore.templateByName`，RELATION 关键字）。
     * 要补齐需在 `GameSession` 注册模板之前按同一判据重写模板（装载面 / 他人文件，
     * 已写进交付报告「需组长接线」）。**本语料 168 个 `CHARA` CSV 全部写了非空
     * `呼び名`**（2026-10-24 独立复核：`CSV/Chara` 下 168 个文件，`grep -L "^呼び名,."`
     * 与逐行取第二列均为 0 命中），故三处差异在本语料不可观测，基准读数不动。
     *
     * @return 实际回填的角色数（0 = 开关关闭或无空 CALLNAME）
     */
    fun applyCompatiCallname(): Int {
        if (!compatiCallname()) return 0
        var filled = 0
        for (i in 0 until store.characters.count) {
            val at = longArrayOf(i.toLong())
            if (store.readStr(CALLNAME_VAR, at).isNotEmpty()) continue
            val name = store.readStr(NAME_VAR, at)
            if (name.isEmpty()) continue
            store.writeStr(CALLNAME_VAR, at, name)
            filled++
        }
        return filled
    }

    // ------------------------------------------------------------------
    // VmHost 桥
    // ------------------------------------------------------------------

    /** 运行时自己的帧深度（含无元数据函数帧），用于识别"根调用的直接被调帧"。 */
    private var scopeDepth = 0

    /**
     * 根级调用待绑定的实参；首个带元数据的被调帧消费一次（见 [pendingRootBind]）。
     *
     * ## 类型桶改成**按需构建**（PERF 2026-10-27 第四批）
     *
     * 旧实现 `RootBind(ints, strs, values)` 在 [rootBindOf] 里**立刻**跑两趟 `count{}`
     * 加一趟填充，无条件建出 `LongArray` + `String[]`。但两个消费点都是二选一：
     * `onFunctionEnter` 里 `root.values != null && bindPositionalValues(...)` 成立时
     * （重入方法通道的**常态**）压根不看类型桶；`bindArgs` 那条更冷。
     * JFR 分配采样实测这三样合计 **60.02 MiB**（`RootBind` 25.97 + `long[]` 21.97 +
     * `String[]` 13.00，占执行循环内分配的 15.0%），其中绝大部分桶是**白造的**。
     *
     * 于是把桶做成 [intArgs]/[strArgs] 的惰性属性：真正走到类型桶绑定时才建一次，
     * 建完缓存。语义零变化（同样的数组、同样的内容、同样的顺序）。
     *
     * ## 再进一步：整张表**只有一个实例**（同一批）
     *
     * 桶惰性化之后 `RootBind` **对象本身**升到榜首（36.73 MiB / 21.9%）。而它根本不需要
     * 每调一次造一个：[pendingRoot] 是**单字段**，任何时刻只有一个待消费的根绑定 ——
     * 两个入口（[runEntry] 与 [invokeUserMethod]）都是 `pendingRoot = bind` 直接覆盖，
     * 从来没有队列。覆盖旧值的行为在旧实现里同样是「旧对象从此无人引用」，与新实现
     * 「把同一实例重绑到新实参」逐字等价（旧对象的内容不会被任何读者看到）。
     *
     * 消费点全部在**同一调用栈内同步完成**（`onFunctionEnter` 里绑形参、`bindArgs` 里
     * 绑桶），期间不跑用户代码、不会重绑 ⇒ 重绑不会踩到正在读的桶。
     */
    private class RootBind {
        private var args: List<Value?> = emptyList()
        private var built = false
        private var ints: LongArray = EMPTY_LONGS
        private var strs: Array<out String?> = EMPTY_STRS

        /**
         * 逐位实参（重入方法通道：[invokeUserMethod] 的 `args`）。非 null 且被调
         * [Program.paramNames] 可用时，[onFunctionEnter] 按**头部形参名**逐位写回
         * （`ProcessState.IntoFunction` 的 `Arg[i]` 口径，spec: ProcessState.cs:435-452）；
         * null（根级 `execute()` / 语句 CALL 通道）保持类型桶绑定。
         */
        var values: List<Value?>? = null
            private set

        /** 逐位形态：桶按 [args] 惰性构建（见 [build]）。 */
        fun bind(args: List<Value?>, values: List<Value?>?): RootBind {
            this.args = args
            this.values = values
            this.ints = EMPTY_LONGS
            this.strs = EMPTY_STRS
            this.built = false
            return this
        }

        /** 类型桶形态（[doCall] 的 CALL 通道）：桶由调用方直接给出，无需构建。 */
        fun bindBuckets(intArgs: LongArray, strArgs: Array<out String?>): RootBind {
            this.args = emptyList()
            this.values = null
            this.ints = intArgs
            this.strs = strArgs
            this.built = true
            return this
        }

        /** 整型桶（首次访问时构建，见类 KDoc）。 */
        val intArgs: LongArray
            get() {
                build()
                return ints
            }

        /** 字符串桶（首次访问时构建，见类 KDoc）。 */
        val strArgs: Array<out String?>
            get() {
                build()
                return strs
            }

        /**
         * 一趟数个数、一趟填充（与旧实现同口径：`null` 位两个桶都不收 —— REF 形参由
         * [pendingRefBindings] 单独绑，其余 `null` 位保持原版 `Arguments[i] == null` 的跳过语义）。
         */
        private fun build() {
            if (built) return
            var ni = 0
            var ns = 0
            for (i in args.indices) {
                when (args[i]) {
                    is Value.IntV -> ni++
                    is Value.StrV -> ns++
                    null -> {}
                }
            }
            val iv = LongArray(ni)
            val sv = arrayOfNulls<String>(ns)
            var ii = 0
            var ss = 0
            for (i in args.indices) {
                when (val a = args[i]) {
                    is Value.IntV -> iv[ii++] = a.v
                    is Value.StrV -> sv[ss++] = a.v
                    null -> {}
                }
            }
            ints = iv
            strs = sv
            built = true
        }
    }

    /** 根绑定的唯一实例（见 [RootBind] 的「单槽复用」）：稳态零分配。 */
    private val rootBindSlot = RootBind()

    private var pendingRoot: RootBind? = null

    /**
     * [pendingRoot] 待消费标志。根级调用的入口函数本身就是第 0 帧（不再是合成根），
     * 它的 [onFunctionEnter] 直接消费实参；若入口无元数据，则顺延给首个带元数据的帧。
     * 不能以 `scopeDepth==2` 判定：该计数是 ProcessRuntime 全局的，
     * [invokeUserMethod] 重入时外层帧仍在栈上，根绑定的"深度 2"永远等不到。
     */
    private var pendingRootBind = false

    /**
     * 重入调用（[invokeUserMethod]）的 REF 形参绑定：**被调帧进入后**才落到被调私有
     * [RefUserVar] 上。不能在建帧前绑 —— `store.enterFunction` 会对该函数全部私有变量
     * `scopeIn()`，把提前写入的目标清空并压进作用域栈。
     *
     * 实参侧的名字在**调用方作用域**解析（原版 `variableTerm.Identifier.GetArray()` 发生在
     * 调用者侧，spec: UserDefinedFunctionArgument.cs:35-69），故 [PendingRefBinding] 持有
     * 已解析好的数组本体（用户变量路径给 [com.eratw.port.core.vars.UserVar]，
     * `LOCAL`/`LOCALS`/`ARG`/`ARGS` 路径给底层数组）。
     */
    private class PendingRefBinding(
        val target: RefUserVar,
        /** 调用方作用域内解析好的绑定源（并流面 [resolveRefSource] 统一口径）。 */
        val src: RefSource,
        val isStr: Boolean,
        val callee: String,
        val argNo: Int,
    )

    private var pendingRefBindings: List<PendingRefBinding> = emptyList()

    /** 是否正处于根级 [VirtualMachine.run] 中（重入保护）。 */
    private var inRun = false

    override fun print(text: String) {
        // 契约 C6：VM `_PRINT_*` 内部指令与 sem 语句共用同一桥接出口（[consoleBridge]），
        // 不存在第二套文字流语义；output 只是镜像。
        consoleBridge.print(text)
    }

    /**
     * 表达式求值的裸语境（变量池 = [store]）：只作为 [evalContext] 的 base 提供
     * [VariableReader]，自身不参与函数分发（[BuiltinEvalContext] 覆写了
     * callFunction/callFunctionRef）。
     */
    private val rawEval: EvalContext = object : EvalContext {
        override val variables: VariableReader get() = store

        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("表达式调用未经内建桥分发（$name）：请经 [ProcessRuntime.evalContext]")

        /**
         * 系统行上屏（OUTPUTLOG 的 ※※※已创建Log文件…※※※、求值警告、Unicode 警告等）。
         *
         * 原版 `PrintSystemLine`（EmueraConsole.cs:3092-3097）三步：
         * `PrintFlush(force: false)` → **`UseUserStyle = false`** → `PrintSingleLine(str)`。
         * 中间那句是「系统行整条回 `defaultStyle`」的唯一来源（`Style` 取值器第一条分支，
         * `:471-475`），而且**粘滞**：原版不配对写回，下一条 PRINT 系语句才写真。
         * 移植侧 `print` + `newLine` 的缓冲形态与 `PrintSingleLine` 的行形态不同
         * （见 `ConsoleSink.printSingleLine` 的 KDoc），此处不改行模型、只补样式轴：
         * 写假之后 [print] 入缓冲的段取 `defaultStyle` 的三件（前景 / 字型 / 字体名）。
         */
        override fun printSystemLine(message: String) {
            consoleSink?.setUseUserStyle(false)
            consoleSink?.print(message)
            consoleSink?.newLine()
        }
    }

    /**
     * 用户函数目录（`BuiltinHost.userFunctions` 的生产来源）。
     *
     * EXISTFUNCTION / EXISTMETH / GETMETH / GETMETHS / ENUMFUNC* 在原版只认
     * `Process.LabelDictionary` 的**非事件**标签表（FunctionMethodCreator.cs:8222-8304、
     * 8533-8653；EnumNameMethod 的 FUNCTION 源 = NoneventKeys，:280）。本类持有唯一一份
     * [FunctionTable]，直接从它投影：
     * - 事件成员剔除（事件标签在原版进 eventLabelDic，不在非事件域）；
     * - 非事件重名 = 后装载者生效（[FunctionTable.all] 按 defIndex 升序，后写覆盖）；
     * - 编译器合成的「事件入口」不是脚本符号（两条编译路径的 position 标记分别是
     *   `synthetic-event-entry`（core-vm 装置）与 `·event-entry·`（erbcc CorpusCompile），
     *   且其 key 必与某个事件同名），连同含 `#` 的根合成节一起剔除。
     *
     * 种类位 = `FunctionLabelLine.IsMethod` + `MethodType`（:8269-8280）：
     * #FUNCTIONS → 3、#FUNCTION → 2、普通 @label → 1。
     *
     * **惰性**：全量语料 ~11 万条 FuncMeta 投影出的表只在第一次符号查询
     * （EXIST 家族 / GETMETH 家族 / ENUMFUNC 家族）时建，标题画面等不查符号的路径零开销；
     * 建好后随 VM 常驻（原版 LabelDictionary 也是常驻表）。
     *
     * `CompatiCallEvent` 为真时事件名也投影进来：原版此时事件标签在非事件表里
     * （`LabelDictionary.cs:181-184`），故符号查询族对事件名的答案 = `value2[0]`
     * （= [firstEventMember]）的种类位 —— EXISTFUNCTION 返回 1/2/3
     * （FunctionMethodCreator.cs:8248-8280 与 :8282-8302 两形态同结论）、EXISTMETH
     * 返回 0（CallfNonMethodFunc 被 ExistMethMethod 捕获，:8630-8637）、GETMETH/GETMETHS
     * 抛非方法错（:557-560 口径，SymbolQueryMethods.getMethRef 的 PLAIN 分支）、
     * ENUMFUNC* 枚举到事件名（NoneventKeys，LabelDictionary.cs:30）。
     * 事件名标签恒为事件（`IsEventLabelName` 按名判定，IdentifierDictionary.cs:72-89
     * ↔ `Symbols.kt:81`），与非事件键不可能碰撞，直接 put 即可。
     */
    private val userFunctionKinds: Map<String, UserFunctionKind> by lazy {
        buildMap {
            for (m in table.all()) {
                if (m.isEvent) continue
                if (m.position == "synthetic-event-entry" || m.position == "·event-entry·") continue
                if ('#' in m.key) continue
                if (table.eventCallOrder(m.key) != null) continue
                put(
                    m.key.uppercase(),
                    when {
                        m.isMethod && m.returnsStr -> UserFunctionKind.STRING_METHOD
                        m.isMethod -> UserFunctionKind.INT_METHOD
                        else -> UserFunctionKind.PLAIN
                    },
                )
            }
            if (compatiCallEvent()) {
                for (m in table.all()) {
                    if (!m.isEvent) continue
                    val first = firstEventMember(m.key) ?: continue
                    put(
                        m.key.uppercase(),
                        when {
                            first.isMethod && first.returnsStr -> UserFunctionKind.STRING_METHOD
                            first.isMethod -> UserFunctionKind.INT_METHOD
                            else -> UserFunctionKind.PLAIN
                        },
                    )
                }
            }
        }
    }

    /**
     * 表达式求值实际使用的宿主：在注入宿主之上补齐用户函数目录与 GETMETH/GETMETHS 的调用通道。
     *
     * 生产装配（[GameSession] → [ProcessRuntime]）原先只传 [BuiltinHost] 的缺省实现，
     * `userFunctions` / `invokeUserFunction` 恒为 null ⇒ EXISTMETH/EXISTFUNCTION 对真实
     * 用户函数也答 0、GETMETH 退化成默认值（语料 `COMMON.ERB:4326`、`QOL_USERCOM.ERB:168`
     * 都踩这条）。这两个数据只有本类拿得到（函数表 + 重入执行器），所以在装配处一次补齐；
     * 宿主显式提供的表/调用器优先（E2E 装置按符号表自建的表不被覆盖）。
     */
    private val effectiveBuiltinHost: BuiltinHost = userFunctionWiring(
        builtinHost ?: SemConfigHost(semConfig, store),
        { userFunctionKinds },
    ) { name, args -> invokeUserMethod(name, args) }

    /**
     * 会话装配收尾：`CompatiCALLNAME` 的角色列回填（`ConstantData.cs:1263-1272`）。
     *
     * **为什么单独一个 init 块**：Kotlin 的 init 块与属性初始化按**声明序**执行，上面的
     * 主 init 块跑时 [effectiveBuiltinHost] 还没初始化，而回填要经它读条目表 —— 放在这里
     * 才读得到。相位（原版在 CSV 装载完、首个 ADDCHARA 之前）与残余差异见
     * [applyCompatiCallname] 的 KDoc。
     */
    init {
        applyCompatiCallname()
    }

    /**
     * 运行期动态解析（PRINTFORMS 二段展开 / RETURNFORM 整数列表）的词法配置 ——
     * System 组两个开关从 VM 条目表读（[com.eratw.port.core.fn.ConfigMethods.lexerConfig]），
     * 与 C# `LexicalAnalyzer` 直读静态字段同源（全角空格 spec: `LexicalAnalyzer.cs:748-754`、
     * 读取点 `Config.cs:370`；三连记号 spec: `LexicalAnalyzer.cs:1138`、读取点 `Config.cs:372`）。
     * 条目表播种是纯内存操作（rooted 宿主重放三层链 / 覆盖表降级），lazy 到首次
     * FORM 展开才付；同 VM 内与 GETCONFIG 共用 `store.aux` 的同一条目表。
     */
    private val runtimeLexerConfig: LexerConfig by lazy {
        ConfigMethods.lexerConfig(store.aux, effectiveBuiltinHost)
    }

    /**
     * 表达式求值语境（A16 任务 1/2）—— [BuiltinEvalContext] 的 proc 侧子类，三条路按
     * 原版优先级（spec: IdentifierDictionary.cs:538-547, 548-586, 559, 562-580, 607-628）：
     *
     * ① **返回值哨兵** `#EXPR_RET#<k>`（int，[exprTempSlotOf]）/ `#EXPR_RETS#<k>`
     *   （str，[exprStrTempSlotOf]，合同 M3-A §3 A7）= 编译期已把用户 `#FUNCTION`/
     *   `#FUNCTIONS` 方法调用降级为"实参升序求值 → `_MOV` R0.. → `_CALL`(c=返回槽；
     *   str 方法另置 b bit28 = STR_RET_FLAG，被调帧经 `_RETS` 返回) → 哨兵读槽"，
     *   求值发生在调用方帧内（前导 `_CALL` 已返回），故读 [SlotAddr] 局部槽 k 即调用方帧
     *   （spec: Vm.kt:438-446/570-580；约定见 ErbProgramCompiler.kt 哨兵前缀定义）。
     * ② **用户方法但编译期未降级**（调用点位于惰性求值位、REF 方法、类型未知、调用方没喂
     *   `ExprCallTable`）：走 [invokeUserMethod] 的**重入被调帧**通道 —— 原版就是运行期在表达式
     *   内部建帧（spec: Process.cs:514-540 `GetValue(SuperUserDefinedMethodTerm)`：
     *   `methodStack++`（:516-520 上限 100 → OverflowFuncStack）→ `state.currentMin` 暂存/还原
     *   （:522-523 / :537）→ `IntoFunction(call, udmt.Argument, exm)`（:527）→ `runScriptProc()`
     *   → `return state.MethodReturnValue`（:529）→ `finally` 私有动态变量 `ScopeOut()`（:533-536）），
     *   实参在**重入点**按升序求值（spec: UserDefinedFunctionArgument.cs:35-69）。
     *   本侧同一套：[nestedVmPool] 借派生 VM、[methodDepth] 计数、[rootBindOf] 分桶绑参。
     *   先于内建查（原版：用户 `#FUNCTION` **遮蔽**同名内建，spec: IdentifierDictionary.cs:548-586）。
     *
     *   REF 形参（原版 `TransporterRef` + `ReferenceToken.SetRef`，
     *   UserDefinedFunctionArgument.cs:43-58 / ProcessState.cs:439-441）走
     *   [EvalContext.callFunctionRef] 的 `exprCallUserMethodRef`：REF 位**不求值**、只取
     *   `VarRef` 本体交 [invokeUserMethod] 的 `refArgs`，由 [buildPendingRefBindings] +
     *   [applyPendingRefBindings] 在进入被调帧时绑成数组别名（见两者的 KDoc）。
     */
    override val evalContext: EvalContext = object : BuiltinEvalContext(
        rawEval,
        store,
        effectiveBuiltinHost,
        // FN-05：只注入「当前行」这一条现读通道，其余参数维持缺省。
        currentScriptLineProvider = { currentScriptLineNow() },
    ) {
        override fun callFunction(name: String, args: List<Value?>): Value {
            exprSentinelValue(name)?.let { return it }
            if (isUnloweredUserMethod(name)) return exprCallUserMethod(name, args)
            return super.callFunction(name, args)
        }

        override fun callFunctionRef(name: String, args: List<Expr?>): Value {
            // 判定顺序 = 哨兵 → 用户方法 → 内建：实参求值本身可能有副作用（内嵌的惰性位调用会
            // 重入建帧），而引用形参内建（MATCH / GETVAR …）要的是**未求值**的 Expr，
            // 故未确认目标前先不得求值实参（否则双重求值 = 双重副作用）。
            exprSentinelValue(name)?.let { return it }
            if (isUnloweredUserMethod(name)) {
                return exprCallUserMethodRef(name, args)
            }
            return super.callFunctionRef(name, args)
        }

        /** 返回值哨兵（① 路）：读调用方帧槽；不是哨兵返回 null。 */
        private fun exprSentinelValue(name: String): Value? {
            exprSentinelSlot(name, EXPR_STR_TEMP_PREFIX)?.let { slot ->
                // 字符串哨兵 `#EXPR_RETS#<k>`（合同 M3-A §3 A7）：#FUNCTIONS 方法降级产物，
                // 读调用方帧第 k 号局部槽的字符串（_RETS/leaveFrameStr 已写回）。
                return Value.StrV(activeVm.getStr(SlotAddr.local(slot)))
            }
            exprSentinelSlot(name, EXPR_TEMP_PREFIX)?.let { slot ->
                // 小整数走缓存（见 [Value.intV]）：本行是 `Value$IntV` 的**最大**分配点
                // （JFR 实测 69.91 MiB / 54.2% of IntV），返回值多为 0/1 这类标志。
                return Value.intV(activeVm.getInt(SlotAddr.local(slot)))
            }
            return null
        }

        /**
         * 哨兵名 → 槽号的**零分配**解析（PERF 2026-10-27 第二批）。
         *
         * 旧实现直接调 `compile/ErbProgramCompiler.kt` 的 `exprTempSlotOf` / `exprStrTempSlotOf`，
         * 两个都是 `name.substring(prefix.length).toIntOrNull()` —— 每次命中都新建一个 `String`
         * （`byte[]` + 头 ≈ 48 B），而 `callFunction` 一次调用要跑 **4 遍**
         * （[exprSentinelValue] 两遍 + [isUnloweredUserMethod] 两遍）。
         * JFR 分配采样（`jdk.ObjectAllocationSample`，执行循环内 1032.9 MiB 为分母）：
         * `exprTempSlotOf` 独占 **11.1%（114.9 MiB）**，`exprStrTempSlotOf` 另占 0.7%。
         *
         * 改用 `Integer.parseInt(CharSequence, from, to, radix)`（JDK 9+）：按**字符区间**解析，
         * 不切串、不建对象。失败抛 `NumberFormatException` ⇒ 返回 null，与 `toIntOrNull()`
         * 的失败语义逐字相同（空尾、非数字、溢出、正负号、前导零全部同一条 JDK 实现）。
         *
         * 前导 `startsWith` 对普通函数名第一字符就失配（两个前缀都以 `#` 开头，
         * 而 `#` 不是 ERB 标识符字符）⇒ 非哨兵名的成本就是一次字符比较。
         */
        private fun exprSentinelSlot(name: String, prefix: String): Int? {
            if (!name.startsWith(prefix)) return null
            return try {
                Integer.parseInt(name, prefix.length, name.length, 10)
            } catch (_: NumberFormatException) {
                null
            }
        }

        /**
         * 名字是否为**编译期未降级**的用户方法（② 路）；哨兵名排除（`#` 不是 ERB 标识符字符）。
         *
         * `CompatiCallEvent` 为真时事件方法标签也在非事件表里（LabelDictionary.cs:181-184）
         * ⇒ 表达式位按 UserDefinedMethodTerm 调用（IdentifierDictionary.cs:561-564）。
         */
        private fun isUnloweredUserMethod(name: String): Boolean =
            exprSentinelSlot(name, EXPR_TEMP_PREFIX) == null &&
                exprSentinelSlot(name, EXPR_STR_TEMP_PREFIX) == null &&
                (table.nonEvent(name)?.isMethod == true ||
                    (firstEventMember(name)?.isMethod == true && compatiCallEvent()))

        /**
         * ② 路落地：在求值点重入被调帧（见上方 KDoc 的 Process.cs:514-540 行号），
         * 通道按被调方法的声明类型选（[invokeUserMethod]：int 取帧返回值、str 取 `_RETS`
         * 字符串通道），与原版 `MethodReturnValue` 同源（spec: ProcessState.cs:30、
         * Process.cs:529）。消费侧的类型容错（原版 `SuperUserDefinedMethodTerm.cs:26-38`
         * 不匹配时 int 得 0 / str 得 ""）在本侧由两条既有通道等价覆盖：
         * 编译期降级走 VM 的 `_RET`/`_RETS`（Vm.kt:555-573），本通道按声明类型返回、
         * 与解析期 `funcTypes` 同源 ⇒ 正常语料不会出现消费/声明错配。
         * TODO(verify): 错配时的 0/"" 静默容错未逐字复刻（现走 [Expr] 的"返回了非整数结果"）。
         */
        private fun exprCallUserMethod(name: String, args: List<Value?>): Value =
            invokeUserMethod(name, args)
                ?: throw CodeEE("表达式位置调用用户方法 $name 失败（符号表无该方法的非事件定义）")

        /**
         * ② 路的 **REF 形参**形态：同一条重入通道，但 REF 位**不求值**，只把裸变量交给
         * [invokeUserMethod] 绑定 —— 原版对 REF 位取 `variableTerm.Identifier.GetArray()`
         * （spec: UserDefinedFunctionArgument.cs:35-69），不读值；求值反而会为 2 维数组的
         * 无下标实参（VariableNoArgTerm）制造「需要 N 个下标」的假错。
         *
         * 位序判定用**头部形参名**（[Program.paramNames]），与语句位 [bindCallArgs] 同源；
         * REF 位拿到非 `VarRef` 实参 / 省略位时按原版 ConvertArg 在**调用点**报错
         * （`RequireArrayBecauseRefArg` / `CanNotOmitRefArg`，CalledFunction.cs:147-158）。
         * 尾部被裁掉的 REF 位也在这里补查（:150-153，CompatiFuncArgOptional 管不到 REF）。
         */
        private fun exprCallUserMethodRef(name: String, args: List<Expr?>): Value {
            val meta = methodMetaOf(name)
                ?: throw CodeEE("表达式位置调用用户方法 $name 失败（符号表无该方法的非事件定义）")
            val paramNames = program.paramNames(meta.index)
            // PERF（2026-10-27 第四批）：两张逐位实参表从**借还池**取（见 [refCallScratchPool]）。
            // 旧实现每次调用现开 `ArrayList<Value?>(args.size)` + `ArrayList<VarRef?>(args.size)`，
            // JFR 分配采样实测占执行循环内分配的 **18.6%（82.89 MiB）**，其中 `Object[]` 45.95 +
            // `ArrayList` 壳 39.95 —— 全是「每次 `_CALL` 造一个能复用的容器」。
            // 为什么必须**借还**而不是按深度复用：求值实参本身可能递归进本函数
            // （`@F(@G(1))` 里 G 在外层 F 还没进 `invokeUserMethod` 时就跑了），
            // 按深度索引会撞上同一格；借还池天然隔离。
            // 归还时机 = `invokeUserMethod` 返回之后：它内部 `pendingRoot`/`pendingRefBindings`
            // 只在这期间持有这两张表，返回前已在 finally 里置空，故复用安全。
            val scratch = borrowRefCallScratch()
            val values = scratch.values
            val refs = scratch.refs
            try {
                for (i in args.indices) {
                    val pn = paramNames.getOrNull(i)
                    if (pn != null && refUserParamAt(pn, meta) != null) {
                        val e = args[i]
                            ?: throw CodeEE("@${meta.name}的第${i + 1}个参数是引用形式因此无法省略")
                        val vr = e as? VarRef
                            ?: throw CodeEE("@${meta.name}的第${i + 1}个参数必须是是引用形式的数组变量")
                        values.add(null) // REF 位不求值（原版只取数组本体）
                        refs.add(vr)
                    } else {
                        values.add(args[i]?.toValue(this))
                        refs.add(null)
                    }
                }
                for (p in args.size until paramNames.size) {
                    if (refUserParamAt(paramNames[p], meta) != null) {
                        throw CodeEE("@${meta.name}的第${p + 1}个参数是引用形式因此无法省略")
                    }
                }
                return invokeUserMethod(name, values, refs)
                    ?: throw CodeEE("表达式位置调用用户方法 $name 失败（符号表无该方法的非事件定义）")
            } finally {
                returnRefCallScratch(scratch)
            }
        }
    }

    /** 语句层执行器（无状态，全程单例）。 */
    private val statementExecutor = StatementExecutor()

    /**
     * 运行期 FORM 解析的标识符解析器（PRINTFORMS 二次展开用）：用户 `#FUNCTION` 先于内建
     * （spec: IdentifierDictionary.cs:548-586），变量按帧内作用域解析。
     * TODO(verify): 运行期才出现的零参用户函数引用仍不可达（VM 单线程不可重入），归 M3 表达式重入。
     */
    private val runtimeFormResolver: IdentifierResolver = object : IdentifierResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            store.resolveVarInfo(name, store.currentFunction)

        override fun resolveFunction(name: String): OperandType? {
            table.nonEvent(name)?.let { return if (it.returnsStr) OperandType.STR else OperandType.INT }
            return Builtins.registry.returnType(name)
        }
    }

    /**
     * PRINTFORMS 二段展开：CheckEscape → 按 FORM 重新词法/解析 → 求值。
     * spec: FunctionIdentifier.cs:203-209（CheckEscape + AnalyseFormattedString + StrForm.GetString）、
     * ExpressionMediator.cs:92-128（CheckEscape）。
     */
    private fun expandRuntimeForm(text: String): String {
        val form = ErbLexer(runtimeLexerConfig).analyseFormattedString(
            CharStream(checkEscape(text)), FormStrEndWith.EoL, trim = false, lineNo = -1,
        )
        return FormStringTerm.fromLexerStrForm(form, runtimeFormResolver, ParserConfig(), -1, -1)
            .getStrValue(evalContext)
    }

    /**
     * RETURNFORM 的运行期逗号整数列表解析（[ExecContext.runtimeIntList] 的注入实现）：
     * 逐段按 `LexEndWith.Comma` 词法、`reduceIntegerTerm`（默认 endWith=EoL）求值，
     * 段间越过逗号并跳过半角空格，spec: FunctionIdentifier.cs:3223-3231。
     * 空串返回空列表（RESULT 补 0 由 sem 负责）；解析器用 [runtimeFormResolver] + 求值用 [evalContext]。
     */
    private fun parseRuntimeIntList(text: String): List<Long> {
        val st = CharStream(text)
        val lexer = ErbLexer(runtimeLexerConfig)
        val out = ArrayList<Long>()
        while (!st.eos) {
            val tokens = lexer.analyse(st, LexEndWith.Comma, LexAnalyzeFlag.NONE, -1)
            val expr = ExprParser(toExprTokens(tokens), runtimeFormResolver, ParserConfig()).reduceIntegerTerm()
            out.add(expr.getIntValue(evalContext))
            st.shiftNext()
            lexer.skipHalfSpace(st)
        }
        return out
    }

    /**
     * 文字流唯一出口的桥接适配器（契约 C6）：镜像进 [output] + 透传 [consoleSink]
     * 宿主 + [printSink] 原始 print 回调。声明顺序在 [execContext] 之前（属性初始化依赖）。
     */
    private val consoleBridge: ConsoleSink = ConsoleBridge(consoleSink, printSink, output, maxMirrorChars)

    // LINECOUNT 原版即时读 Console.LineCount（spec: VariableData.cs:930-942）：直接查询宿主 sink，
    // 不在语句边界做快照（控制流表达式的求值点在 marker 之间）。
    init {
        // MEM-OPT：compile → runtime 交接处释放语句原文驻留（PERF-1 S2 账单
        // 「statements.raw」≈ 131.0 MiB）。此点之后池内语句 raw 一律为 ""——
        // 运行期生产路径零读者（报错定位走 program.lines + StmtEnv）；ErbcWriter
        // 落盘与 SystemVariableBootstrap 的装载链自采原文都在本构造之前完成。
        // perf 池普查（PoolCensus/SizeModel）须在建 ProcessRuntime 之前做。
        program.releaseStatementRaws()
        store.lineCountSource = { consoleBridge.lineCount }
    }

    /**
     * 语句层运行期语境（契约 C6）。**C1 单一真相**：[ExecContext.variables] 就是本类
     * [store]，[ExecContext.eval] 就是 VM `_EVAL_*` 在用的同一个 [evalContext] 实例——
     * 语句写过的变量，表达式立刻可见，反之亦然（[StatementBridgeTest] 锁死）。
     * sink/[SemConfig]/[SystemOps]/[DataSections] 注入点保持 sem 既有契约：
     * 后两者可直接改 `execContext.systemOps` / [installDataSections]。
     */
    val execContext: ExecContext = ExecContext(
        variables = store,
        eval = evalContext,
        console = consoleBridge,
        input = inputSink,
        config = semConfig,
        systemOps = systemOps,
        sound = soundSink,
        graphics = graphicsSink,
    ).apply {
        // 两条通道同一份数据：扁平通道按池下标直查；对象树通道（Android 生产）按语句实例
        // 经反查表换下标（见 [installDataSectionStatementIndex]）。
        dataSections = object : DataSections {
            override fun groupsOf(stmtIndex: Int): List<List<Expr>>? = dataGroupsOf(stmtIndex)
            override fun groupsOf(stmt: Statement): List<List<Expr>>? = dataGroupsOf(stmt)
        }
        runtimeForm = ::expandRuntimeForm
        runtimeIntList = ::parseRuntimeIntList
        // 输入等待的脚本行身份（原版 `process.getCurrentLine`）：按钮世代推进用它判「换行」。
        scriptLineToken = ::scriptLineTokenNow
        // 原版 `ProcessState.IsFunctionMethod`（RETURN 在方法帧内要先转 ReturnF(null)）。
        isFunctionMethodFrame = ::isMethodFrame
    }

    /**
     * 当前帧是否是 `#FUNCTION`/`#FUNCTIONS` 方法（[ExecContext.isFunctionMethodFrame] 的
     * 注入实现，spec: `ProcessState.cs:289-295`）。
     *
     * 判据取**变量层当前帧的函数键**（[VariableStore.currentFunction]，`onFunctionEnter`
     * 之后就是被调方）再查元数据表：非事件表优先（`#FUNCTION` 一般不在事件表里），
     * 事件表兜底（事件标签也可以带 `#FUNCTION`）。两处都没有 ⇒ 不是方法。
     */
    private fun isMethodFrame(): Boolean {
        val key = store.currentFunction ?: return false
        return table.nonEvent(key)?.isMethod == true || firstEventMember(key)?.isMethod == true
    }

    /**
     * VM 语句分派钩子（契约 C4/C6）。marker 指令（op 29..197）由 VM 取出
     * [Statement] 连同 [StmtEnv] 交来，语义在 sem [StatementExecutor]；结果映射：
     * - `ExecResult.Normal` → [StmtOutcome.Normal]
     * - `ExecResult.Quit(*)` → [StmtOutcome.Unwind] (0)：QUIT / FORCE_QUIT /
     *   QUIT_AND_RESTART 的状态机切换（spec: Process.cs:1307-1320）属根级宿主/M4；
     *   restart/force 位本轮没有第二消费方，随 Unwind(0) 归并丢弃（M4 接入时在根级读取）。
     *   C6 复核：QUIT 全程**不产出文字、不触发 flush、不经宿主直写** —— 原版语句体只有
     *   `exm.Console.Quit()`（spec: Process.cs:1307-1309 → EmueraConsole.cs:731-733
     *   仅置 `ConsoleState.Quit`），文字流唯一出口仍是 [consoleBridge]，无绕行/漏 flush/
     *   重复 flush 路径（本映射只改控制流结局，不碰任何 sink）。
     *   例外：`LOADDATA` 成功同样返回 Quit，但它对应 `state.ClearFunctionList()`
     *   （Process.cs:1860），故在下面的标志分支里改出 [StmtOutcome.Unwind]（整栈丢弃）。
     * - `ExecResult.Begin(t)` → [StmtOutcome.Stop] (0)：**真实入口切换属 M4 状态机**
     *   （spec: Process.cs:2136-2546 的 BeginType 系统链，见 [BeginType] KDoc），
     *   本轮按契约 C6 只结束当前帧。
     * - `ExecResult.Return(v)` → [StmtOutcome.Stop] (v)：RETURN/RETURNFORM 的 RESULT 已由 sem
     *   写入，Stop 只负责拆帧/结束本次帧执行（返回直达调用者的调用者，spec: ProcessState.cs:289-311）。
     *
     * sem 抛出的 CodeEE 不带位置——在这里补上 env 的 functionKey/line/pc
     * （原版 CodeEE 由 Process 显示时携带当前行，spec: Process.cs 错误路径），
     * 已带位置的错误原样上抛。VM 不 catch（控制转移/错误一律穿到调用方）。
     */
    override fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome {
        // 零开销守卫（CONTRACT-DBG1 §3 的判据本身）：probe == null（生产缺省）时整块跳过
        // ——不取 nanoTime、不进 try/catch、零分配、零虚调用；唯一新增成本是一次私有方法
        // 调用（invokespecial 非虚分派，JIT 单态内联）。probe 非 null 才计时、才回调。
        val probe = statementProbe ?: return executeStatementRaw(stmt, env)
        val startNs = System.nanoTime()
        // 旧树通道没有池下标可给（这条通道只在「没有扁平池」的装置里走到）：
        // op 用 `FunctionCode.ordinal` 与扁平池同口径，index 用哨兵。
        try {
            val outcome = executeStatementRaw(stmt, env)
            // 正常出口（Normal/Transfer/Stop/Unwind 都是裸路径的返回值，共用这一次回调）。
            // 吞口（StatementProbe KDoc 规定）：探针异常直接吞弃，不影响语句语义。
            runCatching {
                probe.onStatement(
                    stmt.code.ordinal, StatementProbe.NO_STMT_INDEX, env, outcome, null, System.nanoTime() - startNs,
                )
            }
            return outcome
        } catch (e: Throwable) {
            // 异常重抛前补一次回调（error 非空、outcome 为 null；e 已在裸路径盖好位置章，
            // 与上抛实例同一）。探针异常折进 e 的 suppressed 留痕——类型与 message 不动，
            // 原异常原样上抛（盖章/重抛顺序一字未动）。
            runCatching {
                probe.onStatement(
                    stmt.code.ordinal, StatementProbe.NO_STMT_INDEX, env, null, e, System.nanoTime() - startNs,
                )
            }
                .onFailure { probeError -> e.addSuppressed(probeError) }
            throw e
        }
    }

    /**
     * 扁平池通道（T2-64 波次 B）：VM marker 分支在 Program 扁平池就绪时走这里。
     * 与旧 [executeStatement] 同一条观测内衬（探针守卫 + 计时 + 吞口），裸路径换成
     * [executeStatementFlatRaw]；探针只需要 `op`（语句的 `FunctionCode.ordinal`）与
     * `index`（语句池下标）两个 int —— 波次 C 起**不再为回调物化语句树**
     * （数据节改下标键、装载期压扁即弃后，物化一次就是整棵树；见 [StatementProbe] KDoc）。
     * 要语句树的探针（vmdbg trace）自己按 index 取。
     */
    override fun executeStatement(source: FlatStmtSource, index: Int, env: StmtEnv): StmtOutcome {
        val probe = statementProbe ?: return executeStatementFlatRaw(source, index, env)
        val startNs = System.nanoTime()
        try {
            val outcome = executeStatementFlatRaw(source, index, env)
            // op 在回调内现取：裸路径的异常（越界下标等）永远是先抛的那一个，不被取 op 掩盖。
            runCatching {
                probe.onStatement(source.opAt(index), index, env, outcome, null, System.nanoTime() - startNs)
            }
            return outcome
        } catch (e: Throwable) {
            runCatching {
                probe.onStatement(source.opAt(index), index, env, null, e, System.nanoTime() - startNs)
            }
                .onFailure { probeError -> e.addSuppressed(probeError) }
            throw e
        }
    }

    /**
     * 当前脚本行（FN-05）：`(源文件显示名, 0 基行号)`，**按需现算**。
     *
     * 不能只在 [executeStatementRaw] 里刷新：`_EVAL_*`、`_CALL` 这类降级指令不是语句 marker
     * （`_EVAL_STR` 里的 `UNICODE(...)` 前不会有 [StmtEnv]），所以取
     * `activeVm.currentFunctionKey` + `activeVm.lastPc` + `Program.lineOf` 三件套。
     * 调用频率 = 诊断路径（UNICODE 控制码告警）本身，热循环零成本。
     * 未注入 [sourceFileOf] / 无帧 / 行号未知 → null（原版 `getCurrentLine == null` 口径）。
     */
    private fun currentScriptLineNow(): Pair<String, Int>? {
        val key = activeVm.currentFunctionKey ?: return null
        // 缺省从 FuncMeta.position 反推（不持有侧车 fileByKey 表，见 BootMetadataLazyTest）；
        // 显式注入的 sourceFileOf 优先（装置可直接钉文件表）。
        val file = sourceFileOf?.invoke(key) ?: table.sourceFileOf(key) ?: return null
        if (file.isEmpty() || file.startsWith("·")) return null
        val line = program.lineOf(activeVm.lastPc)
        if (line < 0) return null
        return file to line
    }

    /**
     * 当前脚本行的**身份位**（原版 `Process.getCurrentLine` 的对象身份）。
     *
     * 输入等待把它带进 [com.eratw.port.core.host.InputRequest.scriptLine]，控制台的
     * `newGeneration()`（EmueraConsole.cs:789-797）据此判断「脚本走到了另一行」。
     * 约定只用于**相等比较**：同一函数内的同一 pc 恒同值；换语句即变。
     * 无帧 / pc 未知 → -1（控制台退回行水位近似）。
     */
    private fun scriptLineTokenNow(): Long {
        val key = activeVm.currentFunctionKey ?: return -1L
        val pc = activeVm.lastPc
        if (pc < 0) return -1L
        return (key.hashCode().toLong() shl 32) xor (pc.toLong() and 0xffffffffL)
    }

    /** 语句执行裸路径（[executeStatement] 观测内衬所包的原有语义体）。 */
    private fun executeStatementRaw(stmt: Statement, env: StmtEnv): StmtOutcome =
        executeCore({ statementExecutor.execute(stmt, execContext) }, stmt.code, env)

    /**
     * 扁平通道裸路径（T2-64）：直读执行器。`op` 还原成 [FunctionCode] 供共用收尾
     * （流内 op 不可变，执行前后同值）。
     */
    private fun executeStatementFlatRaw(source: FlatStmtSource, index: Int, env: StmtEnv): StmtOutcome =
        executeCore(
            { statementExecutor.execute(source, index, execContext) },
            FunctionCode.entries[source.opAt(index)],
            env,
        )

    /**
     * 语句执行共用体（对象树 / 扁平两条裸路径共用；T2-64 波次 C 从 [executeStatementRaw] 抽出）：
     * `__CAN_SAVE__` 现读 → 执行（CodeEE/ExprEvalException 补位置章）→ LOADDATA/SAVEGAME 交接的
     * Unwind 分支 → [ExecResult] 到 [StmtOutcome] 映射。inline：lambda 装箱会落回每语句一次的
     * 热路径开销。
     */
    private inline fun executeCore(exec: () -> ExecResult, code: FunctionCode, env: StmtEnv): StmtOutcome {
        // `__CAN_SAVE__` 门的真值供给（FunctionIdentifier.cs:2969-2977 在 DoInstruction 时点
        // 读 `state.SystemState`）：逐语句现读系统状态的标志位，供 sem 的
        // SAVEGAME / LOADGAME 门（ExecContext.canSave 的 KDoc）。
        execContext.canSave = systemState.canSave
        val result = try {
            exec()
        } catch (e: CodeEE) {
            throw stampPosition(e, env)
        } catch (e: ExprEvalException) {
            // 原版表达式错误本身就是 CodeEE（如 MultStrInt 的「负数(N)不能与字符串相乘」，
            // OperatorMethodManager.cs:99-101），由 Process 显示时携带当前行；移植侧
            // ExprEvalException 若裸穿，失败屏就没有「（函数 行N）」现场 —— 2026-10-25
            // 真机「负数(-16)不能与字符串相乘」启动失败无定位，就是这一处漏盖章。
            throw stampPosition(CodeEE(e.message ?: "表达式求值失败"), env)
        }
        // `LOADDATA` 成功 → `state.ClearFunctionList()`（Process.cs:1860）：
        // 整个函数栈立刻作废（含正在执行本语句的帧及其全部调用者），`begintype` 一并清空
        // （ProcessState.cs:215-216），`runScriptProc` 不再往下跑。等价出口是
        // [StmtOutcome.Unwind]（整栈丢弃）而不是 [StmtOutcome.Stop]（后者只弹一帧，
        // 调用方会继续执行 —— 那正是读档后"脚本还在跑"的错语义）。
        // 信号由 `FileSystemOps.loadData` 落袋（顺序照 `:1860` → `:1861`），一次性取走；
        // 失败路径在 [statementExecutor] 里已抛错，走不到这里。
        //
        // `SAVEGAME` / `LOADGAME` 同形：原版 `saveCurrentState(single: true)`
        // （FunctionIdentifier.cs:2978）把 state 换成 `Clone()` 出的新实例，而 Clone 不复制
        // functionList（ProcessState.cs:475-484 + :20）⇒ `ScriptEnd`（:34）立即为真，
        // 本次脚本运行同样到此结束。区别只有一条：Clone **原样复制** `begintype`
        // （ProcessState.cs:482），故这两条语句不清挂起 BEGIN（清空只属 ClearFunctionList，
        // ProcessState.cs:216）。
        if (isSaveLoadHandoffCode(code) &&
            SaveLoadHandoff.of(store)?.takeFunctionListCleared() == true
        ) {
            // ProcessState.cs:216 —— 只有 ClearFunctionList 清挂起 BEGIN
            // （原版 `begintype = BeginType.NULL`）。
            if (code == FunctionCode.LOADDATA) pendingBeginType = null
            return StmtOutcome.Unwind(0L)
        }
        return when (result) {
            ExecResult.Normal -> StmtOutcome.Normal
            is ExecResult.Quit -> {
                // QUIT 家族（QUIT / QUIT_AND_RESTART / FORCE_QUIT / FORCE_QUIT_AND_RESTART）：
                // 原版只置 ConsoleState.Quit、**不做 state.Return**（Process.cs:1307-1309 →
                // EmueraConsole.cs:733-735），行泵 `while (console.IsRunning && !state.ScriptEnd)`
                // （Process.cs:1081）当行执行完即退出、DoScript 收尾（Process.cs:438-460）⇒
                // 调用方与事件后续成员**整链停止**。故这里必须整栈丢弃（Unwind ≈ IsRunning=false），
                // 不能用 Stop：Stop 在 depth>0 时 leaveFrame 回调用方继续跑，那是 BEGIN/RETURN
                // 专用出口（对照 FunctionIdentifier.cs:2934 的 BEGIN 显式 state.Return(0L)）。
                // 2026-10-26 fixcore 审查 A2：嵌套 QUIT 原先只拆一帧、调用方继续执行。
                // LOADDATA 成功若走到这里，说明读档交接没有记账（生产
                // FileSystemOps.loadData 必记账，走的是上方 Unwind 分支）：
                // 原版语义是清函数栈而非退出，不计退出位（见 [quitRequested]）。
                if (code != FunctionCode.LOADDATA) quitRequested = true
                StmtOutcome.Unwind(0L)
            }
            is ExecResult.Begin -> {
                // ProcessState.SetBegin(keyword, force)（ProcessState.cs:135-167）：
                // 未知关键字在这里就报 CodeEE（原版在语句执行期抛，不等到函数结束）。
                // 挂起入口在**函数栈清空**当刻消费（ProcessState.cs:352-362）：嵌套调用里
                // 的 BEGIN 只结束当前帧、调用方继续跑，留到最外层脚本返回时由 BeginFlow 的
                // handler 立即转态（见 BeginFlow.beginAtScriptReturn 的 KDoc）。
                pendingBeginType = try {
                    BeginType.fromKeyword(result.target)
                } catch (e: CodeEE) {
                    throw stampPosition(e, env)
                }
                // 原版 `EmueraConsole.ResetStyle()`（EmueraConsole.cs:2838-2842）是 BEGIN 方法体的**第 3 句**
                // （FunctionIdentifier.cs:2930-2936，:2935；FORCE_BEGIN 同形 :2947-2953/:2952），
                // 且必须在 SetBegin 之后 —— 未知关键字抛错时原版不会走到复位（:2933-2935 的顺序）。
                // 复位集恰四项：前景/样式位/字体名/对齐；背景色与重绘态不在其中（:200/:455）。
                consoleBridge.resetStyle()
                StmtOutcome.Stop(0)
            }
            is ExecResult.Return -> StmtOutcome.Stop(result.value)
        }
    }

    /**
     * 让出边界读数转发（M10-RUNB · M10-AWAIT）：VM 主循环在每条语句派发后比较本值，变化即把
     * 死循环保护的段落计数归零（阈值与无让出行为不变）。计数源见
     * [com.eratw.port.core.sem.StatementExecutor.yieldBoundaryCount] 与
     * [com.eratw.port.core.code.VmHost.yieldBoundaryCount] 的 KDoc（spec: Process.cs:438-458、
     * `EmueraConsole.cs:849` → `Process.cs:487-491`）。
     */
    override fun yieldBoundaryCount(): Long = statementExecutor.yieldBoundaryCount

    /**
     * `_CALL_BY_NAME` 的运行期名字解析（契约 C5）。三条路与原版
     * `CalledFunction.CallFunction` 一一对应：
     * - 命中非事件非方法 → 返回 `Program.functions` 下标（spec: CalledFunction.cs:110-114）。
     * - 未命中 → **null**（spec: CalledFunction.cs:104 —— 返回 null，由 VM 的 try 位
     *   决定静默跳过还是抛 [notDefinedFuncMes] 那条原文，spec: FunctionIdentifier.cs:3577-3588）。
     * - 非法目标 → **在本函数里抛 CodeEE，VM 不吞**（try 位同样不豁免，因为原版
     *   抛发生在 isTry 判断之前）：事件名 spec: CalledFunction.cs:100-103
     *   （`CallToEventFunc`，CompatiCallEvent:NO）；`#FUNCTION` 方法名
     *   spec: CalledFunction.cs:106-109（`CallToUserFunc`）。
     *
     * 错误文案与 [callFunction]/[tryCall] 根级路径同口径（复用 [eventCallError] /
     * [methodCallError]）。
     *
     * 事件名先走 [firstEventMember]：`CompatiCallEvent` 为真时它已登记进非事件表
     * （`LabelDictionary.cs:181-184`）⇒ 当普通函数调用；为假 ⇒ CodeEE（同 NO 的既有行为，
     * 只是判定点从 `classify` 换成"名字是不是事件名"，因为生产侧车给事件名编了合成入口，
     * `classify` 会把事件名误判成 NON_EVENT，见 [firstEventMember] 的 KDoc）。
     */
    override fun resolveFunctionIndex(name: String): Int? {
        eventAsNonEvent(name)?.let { return it.index }
        return when (table.classify(name)) {
            FunctionTable.TargetKind.NON_EVENT -> {
                val meta = table.nonEvent(name)!!
                if (meta.isMethod) throw methodCallError(meta)
                meta.index
            }
            FunctionTable.TargetKind.EVENT -> throw eventCallError(name)
            FunctionTable.TargetKind.NOT_FOUND -> null
        }
    }

    /**
     * CALLF 家族的运行期名字解析（契约 R11/API 6）：只认用户方法。
     * spec: IdentifierDictionary.cs:548-575 —— 事件名不进 non-event 表、未知名返回 null；
     * :557-560 —— userDefinedOnly 下非方法 label 抛 CallfNonMethodFunc。
     * 返回值交给 VM 的 try 位决定跳过（TRYCALLFORMF）还是报错（CALLFORMF）。
     *
     * `CompatiCallEvent` 为真时事件标签进了非事件表（`LabelDictionary.cs:181-184`）⇒
     * `GetNonEventLabel` 命中（`IdentifierDictionary.cs:554`）、`IsMethod` 为假 ⇒
     * `:557-560` 的 CallfNonMethodFunc（而不是 NO 时的"查不到 → NotDefinedUserFunc"）。
     */
    override fun resolveMethodIndex(name: String): Int? {
        firstEventMember(name)?.let { member ->
            if (!compatiCallEvent()) return null
            if (member.isMethod) return member.index
            throw callfNonMethodError(name)
        }
        return when (table.classify(name)) {
            FunctionTable.TargetKind.NON_EVENT -> {
                val meta = table.nonEvent(name)!!
                if (!meta.isMethod) throw callfNonMethodError(name)
                meta.index
            }
            FunctionTable.TargetKind.EVENT, FunctionTable.TargetKind.NOT_FOUND -> null
        }
    }

    /**
     * 引擎级函数屏蔽的 VM 侧查询面（[EngineFunctionPolicy] 的落点）。
     *
     * 每次 `_CALL` / 尾跳都会问一次，故只做位图查表（见 [stubTable] 的 KDoc）；
     * 命中后 VM 不建帧、不复制实参、不动变量层作用域，调用点直接得到 `0` / `""`
     * （与「函数体只有一句 `RETURN 0`」逐位等价，见 `Vm.callInto`）。
     */
    override fun isStubbedFunction(funcIndex: Int): Boolean = stubTable.hit(funcIndex)

    override fun onFunctionEnter(funcIndex: Int) {
        val meta = table.byFunctionIndex(funcIndex)
        // 原版 `ConvertArg` 的**第一条**硬判：实参数 > 形参数 → TooManyFuncArgs
        // （spec: CalledFunction.cs:136-140，静态名在装载期 `SetJumpTo` 就报、动态名在
        // `DoInstruction` 抛）。本侧统一在建帧绑定点报，与省略位/类型失配同一相位。
        // 必须早于 scopeDepth++ 与 enterFunction：本函数抛错时 VM 不会为本帧发
        // onFunctionExit（callInto 的 depth 尚未 ++，见 Vm.kt:888-900），
        // 任何已改状态都会泄漏成「作用域栈多一层」。
        if (meta != null) checkTooManyArgs(meta)
        scopeDepth++
        // DBG4 P3 观测口：无元数据帧（meta == null）照样发事件（FunctionProbe KDoc）
        functionProbe?.let { fp -> runCatching { fp.onFunctionEnter(meta) } }
        if (meta == null) return // 无元数据函数：不进变量层
        // spec: ProcessState.cs:438-446 —— ScopeIn（私有动态变量开新作用域）。
        // resetLocals 保持 false：原版 1.824 无"调用即清零 LOCAL"路径
        // （spec: VariableEvaluator.cs:1339,2441,2626 —— 仅 RESETDATA/LOAD 调用）。
        // 调用方函数键必须在 enterFunction 之前取：enter 之后 store.currentFunction 已是被调方，
        // 而 REF 实参的名字要在**调用方作用域**里解析（spec: UserDefinedFunctionArgument.cs:57
        // 的 `variableTerm.Identifier.GetArray()` 发生在调用者侧）。
        val callerKey = store.currentFunction
        // REF 实参的源同样在**进帧之前**解析：解析点即原版 `GetArray()` 的调用点
        // （spec: UserDefinedFunctionArgument.cs:57）。同一函数递归（callerKey == 被调键）时
        // 两边共用同一个 RefUserVar 实例，`enterFunction` 的 `scopeIn()` 会先把 target 清空
        // —— 进帧后再解析只能得到空引用（真机红：五子棋 `GOMOKU_ALPHABETA` 自递归。
        // 无 REF 形参位的函数在这里零成本返回 null。根级/重入帧的 REF 位走
        // `pendingRefBindings`（[buildPendingRefBindings] 已在进帧前解析），故跳过本遍）。
        val refArgSources = if (pendingRootBind) null else resolveRefArgsBeforeEnter(meta, callerKey)
        // PERF（2026-10-26）：`FuncMeta.key` 本就是大写归一键（FuncMeta.kt:25），
        // 旧的 `.uppercase()` 是每次 `_CALL` 一次的多余分配；enterFunction 内部另有归一。
        store.enterFunction(meta.key)
        val root = if (pendingRootBind) pendingRoot else null
        if (root != null) {
            // 根级/重入调用的直接被调帧：实参来自 API 数组（见 pendingRootBind KDoc）。
            pendingRootBind = false
            pendingRoot = null
            // 重入方法通道带逐位实参且有头部形参名 ⇒ 原版逐位写回（含 O_DATA 这类
            // 具名私有形参）；否则退回类型桶（根级 execute / 无形参名的旧产物）。
            // 取一次局部量再判空：`values` 现在是可写属性（[RootBind] 单槽复用要重绑）。
            val rootValues = root.values
            if (rootValues == null || !bindPositionalValues(meta, rootValues)) {
                bindBuckets(meta, root.intArgs, root.strArgs)
            }
            applyPendingRefBindings()
            return
        }
        // 有头部形参名 + 调用点实参元数据 ⇒ 原版逐位写回（ProcessState.cs:435-452）；
        // 缺任一 ⇒ 退回旧的类型桶绑定（`.erbc` 装载路径/重入 API 的既有口径）。
        // 「有没有形参名」这条判据已并进 [bindCallArgs]（首次调用该函数时算一次并缓存），
        // 这里不再每调用一次 `program.paramNames(meta.index).isNotEmpty()`。
        if (bindCallArgs(meta, callerKey, refArgSources)) return
        // 零实参调用点没有 callArgs 表：`Program.declareCallArgs` 只收非空表（Program.kt:1045），
        // 而 v2 段 7 也明确只登记非空站点。此时不能落回 bindArgs 按形参个数回读调用方槽——
        // 原版对 `Arguments[i] == null` 的位直接跳过（spec: ProcessState.cs:437），被调
        // ARG/ARGS 保留上次值；照形参个数读还会在调用方帧槽不足时报「槽越界」
        // （STONELEVEL_8 / M_KOJO_MESSAGE_SCOM_K70_9 两处实测）。
        if (callSiteArgc() == 0) return
        bindArgs(meta)
    }

    override fun onFunctionExit(funcIndex: Int) {
        scopeDepth--
        val meta = table.byFunctionIndex(funcIndex)
        // DBG4 P3 观测口：帧已弹出（VmHost 契约：exit 抛错也不会对同一帧重复调用）
        functionProbe?.let { fp -> runCatching { fp.onFunctionExit(meta, FunctionExitKind.EXIT) } }
        if (meta == null) return
        // spec: Process.cs:531-536 —— ScopeOut（finally 配对，由 VM 的 finally 兜底异常退出）
        store.leaveFunction()
    }

    /**
     * 撞闸抛点取证：把**当前**帧栈逐帧导出（LOCAL/LOCALS/ARG/ARGS + 函数私有用户变量）。
     *
     * 时机由 VM 卡在 `StepLimitExceeded` 抛出之前（`Vm.kt` 步数闸分支）—— 此刻
     * `store.frames` 还在；宿主拿到异常时已被 `run()` 的 finally 回卷，所以常规
     * `var LOCAL:1` 读不到（2026-10-25 红 J 取证缺口）。
     */
    override fun frameSnapshotLines(): List<String> = store.frameSnapshotLines()

    /**
     * 当前建帧调用点的实参数；-1 = 非 `_CALL`/`_CALL_BY_NAME` 建帧（根级入口 / 重入 API）。
     *
     * [VirtualMachine.lastCallPc] 在 `callInto`/`jumpInto` 调 [onFunctionEnter] 期间有效，
     * b 操作数的 bit28..31 是返回/尾跳/TRY/CALLF 标志，实参数取 [Opcode.ARG_COUNT_MASK]。
     */
    private fun callSiteArgc(): Int {
        val pc = activeVm.lastCallPc
        return if (pc < 0) -1 else program.b(pc) and Opcode.ARG_COUNT_MASK
    }

    /**
     * **实参过多**（`Lang.Error.TooManyFuncArgs`，spec: CalledFunction.cs:136-140）：
     * `ConvertArg` 先按被调形参数建 `AExpression[arity]`，`array.Length < srcArgs.Count`
     * 即 `errMes = TooManyFuncArgs` —— 静态名在装载期 `SetJumpTo`（FunctionIdentifier.cs:3550）
     * 就 Warn 成 error、动态名在 `DoInstruction`（:3592-3596）抛 CodeEE，两条路都到不了
     * `IntoFunction`。旧实现两个相位都没有这一判：`callInto` 把实参按 `min(argc, arity)`
     * **静默裁剪**、`bindCallArgs` 对越界位 `getOrNull ?: continue` 直接丢弃 —— 于是
     * `CALL F(1,2)`（F 只收 1 个）在端口上照常执行，在原版是硬错。
     *
     * 文案取本作 zhs 语言表 `lang.emuera-zhs.xml:901` = `{0}函数: 参数过多`，
     * `{0}` = 被调标签名（**不带 `@`**，spec: `topLabel.LabelName`）。
     *
     * 实参数口径 = `_CALL`/`_CALL_BY_NAME` 的 b 操作数（`ARG_COUNT_MASK`）：
     * 编译器 `stageArgs` 写的是**实参位置数**（`StagedArgs.argc = row.size`，
     * ErbProgramCompiler.kt:1655），不是 int/str 两个桶的 max —— 混合类型调用
     * （`CALL F(1,"a",2)`）下两者不同，用 max 会漏判。形参数取 `Program.functions` 的
     * `arity`（= 头部形参数，`FuncSig.paramNames` 同源）。
     *
     * 静默跳过的两种情形：`argc < 0`（根级/重入 API 建帧，没有调用点）与 `argc == 0`
     * （零实参调用点，原版 `srcArgs.Count == 0` 恒不超限）。
     */
    private fun checkTooManyArgs(meta: FuncMeta) = requireArgCount(meta, callSiteArgc())

    /**
     * [checkTooManyArgs] 的共同实现：`argc` 是**实参位置数**（不是类型桶数）。
     *
     * 两条来源共用（原版都过同一个 `ConvertArg`）：
     * - VM 建帧（`_CALL`/`_CALL_BY_NAME`/尾跳）⇒ `argc` = b 操作数的实参数段；
     * - 宿主/重入 API（[callF] / [tryCallF] / [invokeUserMethod]）⇒ `argc` = `args.size`，
     *   位置信息完整（`CALLF F("a", 1)` 的 2 个位置），故必须在这里判，
     *   不能靠 [rootBindOf] 的类型桶（桶丢了位置，也丢了个数）。
     *
     * `argc < 0` = 没有调用点（根级 [runEntry]，实参来自 API 的 `intArgs/strArgs` 数组，
     * 由调用方保证）⇒ 不判。
     */
    private fun requireArgCount(meta: FuncMeta, argc: Int) {
        if (argc <= 0) return
        val arity = program.functions.getOrNull(meta.index)?.arity ?: return
        if (argc > arity) throw CodeEE("${meta.name}函数: 参数过多")
    }

    /**
     * 实参绑定。三种来源：
     * - [pendingRootBind] 为真：根级/重入 API 调用（[runEntry]/[invokeUserMethod]），
     *   实参取 API 数组（[onFunctionEnter] 已先行消费，不进本函数）；
     * - 根级 API 无实参（bind=null）：根帧槽为零 ⇒ 绑零等价；尾跳替换根帧时实参同样必须绑定
     *   （jumpInto 不加深度、根绑定已消费，故不能按深度早退）；
     * - 其余（普通 `_CALL`）：实参在调用方帧槽（VM 尚未切 frameBase，见类 KDoc）。
     *
     * 零实参调用点在 [onFunctionEnter] 已提前返回，不会进入本函数。
     */
    private fun bindArgs(meta: FuncMeta) {
        val root = pendingRoot
        if (root != null && scopeDepth == 2) {
            pendingRoot = null
            bindBuckets(meta, root.intArgs, root.strArgs)
            return
        }
        for (i in 0 until meta.argIntCount) {
            store.writeInt(ARG_VAR, longArrayOf(i.toLong()), activeVm.getInt(SlotAddr.local(i)))
        }
        for (j in 0 until meta.argStrCount) {
            store.writeStr(ARGS_VAR, longArrayOf(j.toLong()), activeVm.getStr(SlotAddr.local(j)))
        }
    }

    /**
     * 根级实参绑定（API 通道：`runEntry` / [executeMethod] / [invokeUserMethod]）。
     *
     * 有头部形参名（`.erbc` v2 `SECTION_PARAM_NAMES` / 源码编译）⇒ 走 [bindRootByName]
     * 逐位写**形参变量**；没有 ⇒ 退回旧的类型桶口径。
     *
     * ## 为什么必须按形参名绑（红 N，2026-10-25 实机）
     *
     * 原版 `ProcessState.IntoFunction` 逐位写的是 `call.TopLabel.Arg[i]` 这个**变量**
     * （spec: ProcessState.cs:435-452），不是写 `ARG:i`。内建函数调用户方法（GETMETHS /
     * GETMETH 族 → [invokeUserMethod]）走的是根级绑定，旧实现只把类型桶灌进 `ARG:0..` /
     * `ARGS:0..`，于是 `@F角色列表33(ARG, O_DATA, V_NAME)`（`#DIMS O_DATA`）里的
     * `O_DATA` 恒空 ⇒ `SELECTCASE O_DATA` 落空 ⇒ 恒返回 ""；连带
     * `GETMETH_OBJNAME_TO_ID` 拿空名去比对 ⇒ 返回 0 而不是 -1、`CHECK_CHARA` 报
     * 「不明なTYPETARGET列表」。同一根因也命中 GETMETH/GETMETH_STR 族共用通道。
     *
     * 只有 `ARG`/`ARGS` 形参的方法（如 `DIVIDE(ARG, ARG:1)`）在旧口径下恰好正确，
     * 这正是「对照组正常」的原因。
     */
    private fun bindBuckets(meta: FuncMeta, intArgs: LongArray, strArgs: Array<out String?>) {
        if (bindRootByName(meta, intArgs, strArgs)) return
        val ni = minOf(intArgs.size, meta.argIntCount)
        for (i in 0 until ni) {
            store.writeInt(ARG_VAR, longArrayOf(i.toLong()), intArgs[i])
        }
        val ns = minOf(strArgs.size, meta.argStrCount)
        for (j in 0 until ns) {
            store.writeStr(ARGS_VAR, longArrayOf(j.toLong()), strArgs[j] ?: "")
        }
    }

    /**
     * 按**头部形参序**把 API 实参逐位写回形参变量（与 [bindCallArgs] 同名同域写法，
     * 区别只在实参来源：这里是已经丢掉了调用点点位信息的值桶）。
     *
     * 逐位规则（spec: ProcessState.cs:435-452 / CalledFunction.cs:143-200）：
     * - `ARG`/`ARGS` 系形参 → 写系统数组对应元素（整型通道 → `ARG`，字符串通道 → `ARGS`）；
     * - 具名用户变量形参（私有优先）→ 按声明类型写；`#DIM`/`#DIMS` 的下标段只允许常量
     *   （[foldParamSubscripts]），维度不足则跳过该位（与 [bindCallArgs] 同口径）；
     * - REF 形参 → **留空**：API 通道只带值、不带终结子，无法提供引用；
     * - 实参用尽的位 → 不写（原版 `Arguments[i] == null` 跳过，:437）。
     *
     * @return true = 走了逐位路径；false = 被调没有形参名（调用方退回类型桶口径）
     */
    private fun bindRootByName(meta: FuncMeta, intArgs: LongArray, strArgs: Array<out String?>): Boolean {
        val params = program.paramNames(meta.index)
        if (params.isEmpty()) return false
        var ii = 0
        var ss = 0
        for (p in params.indices) {
            val parts = params[p].removeSuffix("$").split(':')
            val baseName = parts[0].trim()
            val idx = foldParamSubscripts(parts.drop(1), meta.name)
            val sysName = baseName.uppercase()
            if (sysName == ARG_VAR || sysName == ARGS_VAR) {
                val index = idx.firstOrNull() ?: 0L
                if (sysName == ARG_VAR) {
                    if (ii < intArgs.size) store.writeInt(ARG_VAR, longArrayOf(index), intArgs[ii++])
                } else {
                    if (ss < strArgs.size) store.writeStr(ARGS_VAR, longArrayOf(index), strArgs[ss++] ?: "")
                }
                continue
            }
            val param = store.userVar(baseName, meta.key)
            if (param == null) {
                // 系统/局部变量形参（`LOCAL`/`LOCALS`/`PLAYER` 一族）—— 见 [writeSystemParam]。
                val def = store.registry.def(baseName)
                if (def != null) {
                    val sIndices = shapeSystemParamIndices(def, idx)
                    if (sIndices != null) {
                        if (def.type == VarType.STR) {
                            if (ss < strArgs.size) store.writeStr(baseName, sIndices, strArgs[ss++] ?: "")
                        } else {
                            if (ii < intArgs.size) store.writeInt(baseName, sIndices, intArgs[ii++])
                        }
                    }
                }
                continue
            }
            if (param.spec.isConst || param.spec.isRef) continue
            val wantsStr = param.spec.isStr || params[p].endsWith("$")
            if (wantsStr) {
                if (ss >= strArgs.size) continue
            } else {
                if (ii >= intArgs.size) continue
            }
            val dims = param.def.dims.size
            if (idx.size > dims) continue
            val indices = LongArray(dims)
            for (k in idx.indices) indices[k] = idx[k]
            if (wantsStr) {
                store.writeStr(baseName, indices, strArgs[ss++] ?: "")
            } else {
                store.writeInt(baseName, indices, intArgs[ii++])
            }
        }
        return true
    }

    /**
     * **逐位实参写回**（重入方法通道的形态）—— 与 [bindCallArgs] 同一条原版语义
     * （`ProcessState.IntoFunction` 按头部形参位写 `Arg[i]`，spec: ProcessState.cs:435-452），
     * 但实参值来自已求值的 [Value] 列表（[invokeUserMethod] 的 `args`），不是调用方帧槽。
     *
     * 为什么必须有这一条：`GETMETH/GETMETHS` 与表达式位未降级方法走的都是
     * [invokeUserMethod]；旧的类型桶绑定只写 `ARG`/`ARGS`，**具名私有形参**
     * （qol_OBJ 的 `@GETMETH_STR(ARG, CLASS_NAME, O_ID, O_DATA)`、`@F角色列表33(ARG, O_DATA, V_NAME)`
     * 的 `O_DATA`）永远拿不到实参 ⇒ GETMETHS 恒返回 ""、OOP 名图全空
     * （`OBJNAME_TO_ID` → -1 → `CHECK_CHARA` 不明なTYPE，2026-10-26 新游戏链实测）。
     *
     * 逐位规则与 [bindCallArgs] 的写回分支一致（省略位由调用方在构造实参表时处理）：
     * - `ARG`/`ARGS` 形参 → 写系统数组对应元素（str → int 硬错误；int → str 依
     *   `CompatiFuncArgAutoConvert` 包 TOSTR，CalledFunction.cs:174-198）；
     * - 具名用户变量形参 → 按声明类型写（REF 位由 [applyPendingRefBindings] 单独绑定）；
     * - 名字查不到 / 常量 / 下标超维 → 跳过该位（与原版 ConvertArg 的保守方向一致）。
     *
     * @return true = 走了逐位路径（被调有头部形参名）；false = 调用方应退回 [bindBuckets]。
     */
    private fun bindPositionalValues(meta: FuncMeta, values: List<Value?>): Boolean {
        // PERF（2026-10-27 第三批）：形参项 `NAME[:n[:m]]` 的拆解改走 [paramPlansOf] 的缓存，
        // 与 [bindCallArgs] 同一个 [ParamPlan]（那里的 KDoc 已说明为什么缓存声明侧是安全的）。
        // 旧实现在这里对**每个实参每次调用**现做 `removeSuffix` + `split(':')` + `drop(1)`
        // + `trim` + `uppercase` —— JFR 分配采样实测这一串占执行循环内分配的 **7.9%（50.9 MiB）**
        // （`String` 21.0 + `byte[]` 16.0 + `SingletonList` 14.0）。
        // `paramPlansOf` 在形参表为空时返回 null，与旧实现「`paramNames.isEmpty() → false`」同口径。
        val plans = paramPlansOf(meta) ?: return false
        for (i in values.indices) {
            val v = values[i] ?: continue
            val plan = plans.getOrNull(i) ?: continue
            val baseName = plan.baseName
            val idx = plan.constIdx
            val argNo = i + 1
            // 单下标一律走复用缓冲：`longArrayOf(...)` 每实参一次分配，而 `idx.firstOrNull()`
            // 还会把 `Long` 装箱（返回 `Long?`）。JFR 里 `long[]` 在这一族占 16 MiB 量级。
            // 安全性与 [bindCallArgs] 的 `bindIdx1` 同一条：`writeInt`/`writeStr` 只读不持有。
            val i0 = if (idx.isEmpty()) 0L else idx[0]
            if (plan.isArg) {
                val iv = v as? Value.IntV ?: throw strToIntArgError(meta.name, argNo)
                bindIdx1[0] = i0
                store.writeInt(ARG_VAR, bindIdx1, iv.v)
                continue
            }
            if (plan.isArgs) {
                val sv = when (v) {
                    is Value.StrV -> v.v
                    is Value.IntV -> intToStrArgValue(meta.name, argNo, v.v)
                }
                bindIdx1[0] = i0
                store.writeStr(ARGS_VAR, bindIdx1, sv)
                continue
            }
            val param = store.userVar(baseName, meta.key)
            if (param == null) {
                // 系统/局部变量形参（`LOCAL`/`LOCALS` 一族）：同 [bindCallArgs] 的 2) 前移档。
                writeSystemParamValue(meta, argNo, baseName, idx, v)
                continue
            }
            if (param.spec.isConst || param.spec.isRef) continue
            val dims = param.def.dims.size
            if (idx.size > dims) continue
            val indices = if (dims == 1) {
                bindIdx1[0] = i0
                bindIdx1
            } else {
                LongArray(dims).also { arr -> for (k in idx.indices) arr[k] = idx[k] }
            }
            if (param.spec.isStr) {
                val sv = when (v) {
                    is Value.StrV -> v.v
                    is Value.IntV -> intToStrArgValue(meta.name, argNo, v.v)
                }
                store.writeStr(baseName, indices, sv)
            } else {
                val iv = v as? Value.IntV ?: throw strToIntArgError(meta.name, argNo)
                store.writeInt(baseName, indices, iv.v)
            }
        }
        return true
    }

    /**
     * **逐位实参写回**（原版 `ProcessState.IntoFunction`，spec: ProcessState.cs:435-452；
     * 实参侧 `UserDefinedFunctionArgument.cs:35-69`）—— 具名形参 + `ARG`/`ARGS` 形参 +
     * REF 形参三类统一一条路径。
     *
     * 原版按**头部形参位** i 逐位写 `call.TopLabel.Arg[i]` 这个**变量**（不是写 `ARG:i`）：
     * `@ADDCHARA_INIT(C_ID, ARG:0)` 的 0 号位是私有变量 `C_ID`、1 号位是 `ARG[0]`；
     * 只传 1 个实参时 `Arguments[1] == null` 跳过（:437）⇒ `ARG[0]` **保留旧值**。
     * 这正是旧"类型桶 → ARG:i"绑定做错的形态（桶会把 0 号实参写给 `ARG[0]`，
     * 于是 `SIF !ARG` 恒假、`ADDCHARA` 永不执行）。
     *
     * 数据面：调用点 pc → [com.eratw.port.core.code.Program.callArgsAt]（位置 / 类型桶号 /
     * 裸变量名 / 是否省略位）；形参名 → [com.eratw.port.core.code.Program.paramNames]
     * （**头部**形参序，不是 `#DIM` 声明序）。实参值从**调用方**帧槽读：`onFunctionEnter`
     * 在 VM 切帧之前调用（见 [bindArgs]），槽号 = 编译器登记的类型桶号（`Vm.callInto` 同一约定）。
     *
     * 逐位规则（REF 位校验失败 = 原版同款调用点 CodeEE；其余不满足 → 跳过该位，绝不猜）：
     * 1. 形参名是 `ARG`/`ARG:n`/`ARGS`/`ARGS:n` → 写系统数组对应元素：整型通道写
     *    `ARG[n]`，字符串通道写 `ARGS[n]`（原版 `Arg[i]` 就是这些变量本身，:441-450）；
     * 2. 形参名解析到被调**用户变量**且 `DimSpec.isRef` → REF 绑定（`SetRef`，
     *    :439-441；实参必须是变量、伪变量/常量/角色变量/类型/维度按 `MatchType` 拒绝
     *    （CalledFunction.cs:154-162 / ReferenceToken.cs:130-169），省略位按
     *    CanNotOmitRefArg 拒绝（:150-153）—— 分支细节见 [bindRef] KDoc；
     *    实参可以是用户变量、`LOCAL`/`LOCALS`/`ARG`/`ARGS`（[bindLocalRef]）或
     *    系统数组（[bindSystemRef]）；并流面另有 [resolveRefSource] 的统一查表口径
     *    （IdentifierDictionary.cs:429-495）保留备用，两档解析同一批底层数组；
     * 3. 其余具名形参 → 类型一致照写（`SetValue`，:443-450）；不一致走 ConvertArg 的
     *    类型分支（CalledFunction.cs:174-198）：
     *    * str 实参 → int 形参：调用点即抛 CanNotConvertStrToInt（:176-179，无配置开关）；
     *    * int 实参 → str 形参：`CompatiFuncArgAutoConvert` 为真时按内建 TOSTR 的
     *      格式化口径转成字符串再写（:181-198，:186-189 取 TOSTR、:190-197 包一层
     *      `FunctionMethodTerm`），为假时抛 CanNotConvertIntToStr（:181-185，文案带
     *      该配置的双语名 :183）。两个开关都**现读**会话条目表
     *      （[ConfigMethods.store] 的 aux 槽，Config.cs:374-375 的原版回填点），
     *      不在注册期捕获；
     * 4. 省略位（`present == false`，含尾部超出实参表的位）按原版 ConvertArg 的三段分流
     *    （CalledFunction.cs:165-173）：
     *    * REF 形参 → CanNotOmitRefArg（:150-153）；该判在 :147，先于省略分支 :165，
     *      故不论 CompatiFuncArgOptional 取值都是硬错误；
     *    * `ARG`/`ARGS` 或**私有**用户变量形参 → 隐式默认 0/""（原版装载期
     *      `Def[i]`：无显式默认的 NullTerm 也按形参类型折成 SingleLongTerm(0)/
     *      SingleStrTerm("")，ErbLoader.cs:714-722），此处按位写回（:443-450）；
     *    * 其余（全局/系统变量形参）→ 装载期 `Def[i]` 必为 null（默认值只允许出现在
     *      ARG/ARGS/私有形参上，ErbLoader.cs:729-732 → ArgCanOnlyPrivVar），
     *      CompatiFuncArgOptional 为假 ⇒ CanNotOmitArgWithMessage（:168-172，文案带
     *      配置双语名 :170）；为真 ⇒ 不写（`Arguments[i] == null` 跳过，:437）。
     *    显式**字面量**默认值已由 padOmittedArgs 在调用点补成 `present=true`，不走这里。
     *
     * 校验相位差：原版对**静态名** CALL 在装载期 `SetJumpTo` 就跑 ConvertArg
     * （FunctionIdentifier.cs:3550 + ParserMediator.Warn），动态名在 DoInstruction
     * （:3592-3596 抛 CodeEE）；本侧编译期不校验，统一在进帧绑定点（VM 执行到 `_CALL`
     * 即抛）报出，文案一致、时点晚于装载期（TODO(verify)：装载期相位需编译器面配合，
     * 不在本文件面内）。
     *
     * TODO(verify): 显式**非字面量**默认值（`= C_YELLOW` 一类常量名）本侧编译期不注入
     * （`CallDefaultsCollector` 只注入字面量，语料实测 18/1,936 位不可注入），绑定点会把
     * 这类**私有**形参当「无显式默认」写 0/"" —— 与 PC 的常量值不同；语料实测这类省略位
     * 102 处（全在私有形参上，fromEN/QoL/UPDATE 附件）。同理，`CALLFORM` 族在编译期
     * 拿不到被调定义（[padOmittedArgs] 不补位），其被省略的显式默认值位也会落到这里的
     * 隐式 0/""。卡点：默认值表未进 FuncMeta/Program 侧车；两条都要等容器面补上
     * 「逐位是否有默认值/默认值表达式」才能收敛。
     *
     * REF 绑定的是**数组本身**（别名）：被调写入立即对调用方可见（ReferenceToken.cs:125-128
     * `array = refArray`），与"按值拷贝 + 回写"的近似语义不同。
     *
     * @param callerKey 调用方函数键（进入被调帧**之前**取的 `store.currentFunction`）；
     *   null = 根级帧，只查全局用户变量。
     * @param refSources 进帧**之前**解析好的 REF 位源（[resolveRefArgsBeforeEnter]，按实参表
     *   下标对齐；null = 该调用点没有 REF 位/无调用点元数据）。非 null 时该表对 REF 位
     *   是权威结果（元素 null = 解析不到，与旧口径一样静默跳过该位）。
     * @return true = 走了逐位路径（调用点数据齐备）；false = 调用方应退回旧的类型桶绑定。
     */
    private fun bindCallArgs(
        meta: FuncMeta,
        callerKey: String?,
        refSources: Array<RefSource?>? = null,
    ): Boolean {
        val pc = activeVm.lastCallPc
        if (pc < 0) return false
        val args = program.callArgsAt(pc) ?: return false
        // 形参表**只在首次调用该函数时取一次**（[paramPlansOf] 缓存），热路径上不再
        // 每调用一次 `program.paramNames(meta.index)`（一次虚调用 + 一次惰性函数表查）。
        // null = 该函数没有头部形参名 ⇒ 与旧实现的 `paramNames.isEmpty() → false` 同口径。
        val plans = paramPlansOf(meta) ?: return false
        // 原版 ConvertArg 对被调**全部**头部形参位校验（CalledFunction.cs:143-200）：
        // 实参数不足的位置（i >= srcArgs.Count）落到 REF 形参上是硬错误 CanNotOmitRefArg
        // （:150-153，CompatiFuncArgOptional 分支管不到 REF）。本侧调用点元数据只覆盖到
        // 「最后一个显式字面量默认值的形参位」（padOmittedArgs 只补这类位），REF 形参不可带
        // 默认值（原版装载期 RefArgCanNotInitialize 拒绝，ErbLoader.cs:756-758），故
        // 尾部被裁掉的 REF 位必须在这里补查 —— 否则省略的 REF 会静默留空、推迟到
        // 被调首次读时才报 EmptyRefVar，与原版「调用点即报」的时点与文案都不符。
        // 非 REF 的尾部位由 [bindImplicitArgDefault] 补装载期隐式默认（ARG/ARGS/私有形参），
        // 其余才落到省略配置分支。
        if (args.size < plans.size) {
            val paramNames = program.paramNames(meta.index)
            for (p in args.size until plans.size) {
                if (refUserParamAt(paramNames[p], meta) != null) {
                    throw CodeEE("@${meta.name}的第${p + 1}个参数是引用形式因此无法省略")
                }
                // 非 REF 的尾部省略：ARG/ARGS/私有形参有装载期隐式默认 0/""（ErbLoader.cs:714-722）；
                // 其余形参 Def 必为 null，CompatiFuncArgOptional 关 ⇒ CanNotOmitArgWithMessage
                // （CalledFunction.cs:168-172），开 ⇒ 保持 `Arguments[i] == null` 的跳过语义（:437）。
                if (bindImplicitArgDefault(paramNames[p], meta)) continue
                if (!funcArgCompat(ConfigKey.CompatiFuncArgOptional)) {
                    throw cannotOmitArgError(meta.name, p + 1)
                }
            }
        }
        // PERF（2026-10-27 第三批）：这里原本是 `for (a in args)` —— `args` 是 `List<CallArg>`，
        // 增强 for 每次都建一个 `ArrayList$Itr`。JFR 分配采样实测 `bindCallArgs` 的
        // `ArrayList$Itr` 占执行循环内分配的 **13.8%（88.9 MiB）**：一次 `_CALL` 一个迭代器，
        // 一轮 69.8 万次调用。按下标取不建任何对象（`args` 恒是 ArrayList 或 SingletonList）。
        for (ai in 0 until args.size) {
            val a = args[ai]
            if (!a.present) {
                val paramName = program.paramNames(meta.index).getOrNull(a.position) ?: continue
                // 省略的 REF 位：原版 ConvertArg 在调用点报 CanNotOmitRefArg
                // （CalledFunction.cs:150-153）—— 该判在 CompatiFuncArgOptional
                // 分支之前（:147 vs :165），任何配置下都是硬错误。
                if (refUserParamAt(paramName, meta) != null) {
                    throw CodeEE("@${meta.name}的第${a.position + 1}个参数是引用形式因此无法省略")
                }
                // ARG/ARGS/私有形参的省略 = 隐式默认 0/""（ErbLoader.cs:714-722），写回后
                // 与原版 `Def[i]` 顶替等价；其余形参才落到 CompatiFuncArgOptional 分支。
                if (bindImplicitArgDefault(paramName, meta)) continue
                if (!funcArgCompat(ConfigKey.CompatiFuncArgOptional)) {
                    throw cannotOmitArgError(meta.name, a.position + 1)
                }
                continue
            }
            // 形参项原文 `NAME[:n[:m]]` 的拆解结果（名字 + 常量下标）来自 [ParamPlan]：
            // 旧实现在这里现做 `removeSuffix`/`split`/`drop`/`uppercase` 四步，
            // 每个实参每次调用各一份（JFR 归因 `bindCallArgs` 自耗时 5.91%）。
            val plan = plans.getOrNull(a.position) ?: continue
            val slot = SlotAddr.local(a.bucket)
            val baseName = plan.baseName
            val idx = plan.constIdx
            // ---- 1) ARG / ARGS 系形参：写系统数组对应元素 ----
            if (plan.isArg || plan.isArgs) {
                val index = if (idx.isEmpty()) 0L else idx[0]
                val argNo = a.position + 1
                if (plan.isArg) {
                    // 形参类型 = ARG（long）：str 实参在原版 ConvertArg 直接拒绝（:176-179）。
                    if (a.isStr) throw strToIntArgError(meta.name, argNo)
                    bindIdx1[0] = index
                    store.writeInt(ARG_VAR, bindIdx1, activeVm.getInt(slot))
                } else {
                    // 形参类型 = ARGS（string）：int 实参按 CompatiFuncArgAutoConvert 包 TOSTR（:181-198）。
                    bindIdx1[0] = index
                    store.writeStr(ARGS_VAR, bindIdx1, strArgValue(meta.name, argNo, a, slot))
                }
                continue
            }
            // ---- 2) 系统/局部变量形参（LOCAL / LOCALS / PLAYER / TARGET 一族） ----
            // 原版 `Arg[i]` 就是 `ErbLoader.parseLabel` 解析出的 VariableTerm，
            // `IdentifierDictionary.GetVariableToken` 的查表顺序是「私有用户变量 → 局部变量
            // （LOCAL/LOCALS/ARG/ARGS）→ 全局系统变量 → 全局用户变量」（:429-495），
            // 故非用户变量的名字必须落到系统/局部那一档上，不能像旧实现那样 `continue` 丢掉
            // —— `@Pt01(LOCAL, …)`（DLC/OPTIONPRINT.ERB:94）的 LOCAL 恒 0 就是这么来的。
            val param = store.userVar(baseName, meta.key)
            if (param == null) {
                writeSystemParam(meta, a, baseName, idx, slot)
                continue
            }
            if (param.spec.isConst) continue
            if (param.spec.isRef) {
                val callee = meta.name
                val argNo = a.position + 1
                // REF 位实参必须是变量（原版 ConvertArg：非 VariableTerm 报
                // RequireArrayBecauseRefArg，CalledFunction.cs:154-158）。非变量实参
                // （表达式字面量等）CallArg.name 为 null —— 原样报错，不再静默留空。
                val name = a.name
                    ?: throw CodeEE("@${callee}的第${argNo}个参数必须是是引用形式的数组变量")
                // 实参解析链同 `IdentifierDictionary.GetVariableToken`（spec: :429-495）：
                // 调用方私有用户变量 → 局部变量（LOCAL/LOCALS/ARG/ARGS）→ 系统数组，
                // 三层由 [resolveRefSource] 统一查表后交 [bindRef] 判型绑定。
                // 进帧前已解析过（[refSources]，按实参表下标）就只认那份快照 ——
                // 同一函数递归时现解析读到的是被调帧 scopeIn 之后的空引用。
                val src = if (refSources != null) refSources.getOrNull(ai) else resolveRefSource(name, callerKey)
                if (src == null) continue
                bindRef(param as RefUserVar, src, a.isStr, callee, argNo)
                continue
            }
            if (param.spec.isStr != a.isStr) {
                // 原版 ConvertArg 的类型失配分派（CalledFunction.cs:174-198）：
                // str 实参 → int 形参一律拒绝（:176-179）；int 实参 → str 形参按
                // CompatiFuncArgAutoConvert 决定 TOSTR 包裹（:181-198）还是报错（:181-185）。
                if (!param.spec.isStr) throw strToIntArgError(meta.name, a.position + 1)
            }
            val dims = param.def.dims.size
            if (idx.size > dims) continue
            // 下标数组的长度必须**精确等于**维数（`checkElement` 按 `indices.size` 判维，
            // 多一格会报「需要 1 个下标（实际 4）」）。dims == 1 走复用缓冲，其余仍现分配。
            val indices = if (dims == 1) {
                bindIdx1[0] = if (idx.isEmpty()) 0L else idx[0]
                bindIdx1
            } else {
                LongArray(dims).also { arr -> for (k in idx.indices) arr[k] = idx[k] }
            }
            if (param.spec.isStr) {
                store.writeStr(baseName, indices, strArgValue(meta.name, a.position + 1, a, slot))
            } else {
                store.writeInt(baseName, indices, activeVm.getInt(slot))
            }
        }
        return true
    }

    /**
     * **系统/局部变量形参位**的写回（`@Pt01(LOCAL, …)`、`@CAN_COM702(PLAYER)` 一族）。
     *
     * ## 为什么需要这一档
     *
     * 原版 `ProcessState.cs:435-452` 逐位写的是 `call.TopLabel.Arg[i]` 这个**变量**，而
     * `Arg[i]` 是 `ErbLoader.parseLabel` 按形参项原文解析出的 `VariableTerm`（`:646-760`）
     * —— 名字是什么就写什么。`IdentifierDictionary.GetVariableToken`（`:429-495`）的查表顺序是
     * 「私有用户变量 → 局部变量（LOCAL/LOCALS/ARG/ARGS）→ 全局系统变量」，所以形参名可以
     * 是 `LOCAL`、`PLAYER`、`TARGET`、`RESULT` 这些**内建**变量。
     *
     * 旧实现只把 `ARG`/`ARGS` 单独接出来，其余一律 `store.userVar(...) ?: continue`：查不到
     * 用户变量就**静默丢掉该位**。语料实测 27 个形参位落在这条缝里
     * （`PLAYER` 11 / `TARGET` 7 / `LOCALS` 5 / `LOCAL` 3 / `RESULT` 1），
     * 其中 `@Pt01(LOCAL,ARG,ARGS,…)`（`DLC/OPTIONPRINT.ERB:94`）的 `LOCAL` 恒 0
     * ⇒ 补丁清单**全部**印「未启动」（2026-10-29 玩家反馈）。
     *
     * ## 下标形状
     *
     * 原版 1 维变量不带下标时补 `ZeroTerm`（`VariableParser.cs:156-168`，`LOCAL` → `LOCAL:0`），
     * 0 维变量（`RESULT`）不带下标。故按 [VarDef.argCount] 定形状：缺的位补 0，多的位
     * （原版装载期 `TooMany…VarArg`）保守跳过。
     *
     * ## 类型
     *
     * 与用户变量形参同一条 `ConvertArg` 口径（`CalledFunction.cs:174-198`）：str 实参 → int
     * 形参直接报错；int 实参 → str 形参按 `CompatiFuncArgAutoConvert` 包 TOSTR
     * （[strArgValue] 已含该配置分支）。
     */
    private fun writeSystemParam(meta: FuncMeta, a: CallArg, baseName: String, idx: LongArray, slot: Int) {
        val def = store.registry.def(baseName) ?: return
        val indices = shapeSystemParamIndices(def, idx) ?: return
        val argNo = a.position + 1
        if (def.type == VarType.STR) {
            store.writeStr(baseName, indices, strArgValue(meta.name, argNo, a, slot))
        } else {
            if (a.isStr) throw strToIntArgError(meta.name, argNo)
            store.writeInt(baseName, indices, activeVm.getInt(slot))
        }
    }

    /**
     * [writeSystemParam] 的「已求值实参」版本（[bindPositionalValues] 走的重入方法通道）。
     *
     * 值来源不同（[Value] 列表 vs 调用方帧槽），逐位规则与 [writeSystemParam] 一致。
     */
    private fun writeSystemParamValue(
        meta: FuncMeta,
        argNo: Int,
        baseName: String,
        idx: LongArray,
        v: Value,
    ) {
        val def = store.registry.def(baseName) ?: return
        val indices = shapeSystemParamIndices(def, idx) ?: return
        if (def.type == VarType.STR) {
            val sv = when (v) {
                is Value.StrV -> v.v
                is Value.IntV -> intToStrArgValue(meta.name, argNo, v.v)
            }
            store.writeStr(baseName, indices, sv)
        } else {
            val iv = v as? Value.IntV ?: throw strToIntArgError(meta.name, argNo)
            store.writeInt(baseName, indices, iv.v)
        }
    }

    /**
     * 系统/局部变量形参位的**下标整形**：长度恒等于 [VarDef.argCount]（`checkElement` 按
     * `indices.size` 判维，多一格会报「需要 N 个下标（实际 M）」）。
     *
     * @return null = 形参给的下标个数超过变量维数（原版装载期 `TooMany…VarArg` 硬错误；
     *   本侧保守跳过，不猜）
     */
    private fun shapeSystemParamIndices(def: VarDef, idx: LongArray): LongArray? {
        val n = def.argCount
        if (idx.size > n) return null
        return when (n) {
            0 -> EMPTY_INDICES
            1 -> {
                // 单下标走复用缓冲（同 [bindIdx1] 的安全前提：store 只读不持有下标数组）。
                bindIdx1[0] = if (idx.isEmpty()) 0L else idx[0]
                bindIdx1
            }
            else -> LongArray(n).also { arr -> for (k in idx.indices) arr[k] = idx[k] }
        }
    }

    /** 0 维系统变量（`RESULT` 一类）的下标：长度 0，恒空。 */
    private val EMPTY_INDICES = LongArray(0)

    /**
     * 形参项的**解析结果**（PERF 2026-10-27）：把 `NAME[:n[:m]]` 的字符串拆解从
     * 「每次调用 × 每个实参」搬到「每个被调函数一次」。
     *
     * ## 为什么值得单独缓存
     *
     * 旧实现每个实参每次调用都要现做：`removeSuffix("$")`（1 个 String）+ `split(':')`
     * （1 个 ArrayList + 1 个 String[]）+ `parts.drop(1)`（再 1 个 ArrayList）+
     * `baseName.uppercase()`（1 个 String）+ `LongArray(dims)`。`CHARA_MOVEMENT` 一回合
     * 百万级调用 ⇒ 千万级短命对象，JFR 归因 `bindCallArgs` 自耗时 **5.91%**、
     * `onFunctionEnter` 整链 **11.65%**，几乎全是这些分配与随之而来的 GC。
     *
     * ## 为什么不需要失效
     *
     * 缓存的是**声明侧**的解析结果（`Program.paramNames(meta.index)` 的原文拆解），
     * 形参声明表在装载完成后不可变。变量的**解析**（`store.userVar(baseName, meta.key)`）
     * 仍逐次进行，因此迟到声明、私有/全局分流、REF 绑定这些与作用域相关的判定
     * 一条都没被绕过 —— 与 T2-61 的 `VarRef` 站点缓存同一条原则：
     * 只固化「声明」，不固化「作用域」。
     */
    private class ParamPlan(
        /** 形参变量名：去掉尾部 `$` 与下标段，已 trim。 */
        val baseName: String,
        upperName: String,
        /** 冒号后的常量下标（`NAME:1:2` → `[1, 2]`）；无下标 = 空数组。 */
        val constIdx: LongArray,
    ) {
        /** 形参是 `ARG`（整型实参桶）。 */
        val isArg: Boolean = upperName == ARG_VAR

        /** 形参是 `ARGS`（字符串实参桶）。 */
        val isArgs: Boolean = upperName == ARGS_VAR
    }

    /** 形参位 → [ParamPlan]；按 `meta.index` 索引，首次调用该函数时建表（见 [ParamPlan] 的失效说明）。 */
    private val paramPlans = arrayOfNulls<Array<ParamPlan?>?>(program.functions.size)

    /**
     * ARG/ARGS 与 `dims == 1` 用户变量共用的**单下标**复用缓冲。
     *
     * 安全性：`VariableStore.writeInt/writeStr` 只读下标数组，不持有它（REF 绑定持有的是
     * **变量名**，不是下标）；单线程语义（ADR-003）下同一时刻只有一次绑定在写它。
     */
    private val bindIdx1 = LongArray(1)

    /**
     * 形参位 → [ParamPlan]；按 `meta.index` 索引，首次调用该函数时建表。
     *
     * @return `null` = 该函数**没有头部形参名**（`Program.paramNames` 为空）。这条判据
     *   原来由调用方每次 `paramNames(meta.index).isEmpty()` 现查，现在并进本函数的
     *   「未命中即建表」一次里 —— 无形参的函数（语料里占多数）因此每次调用省掉一次
     *   惰性函数表查询 + 一次 `List` 判空。
     */
    private fun paramPlansOf(meta: FuncMeta): Array<ParamPlan?>? {
        val idx = meta.index
        if (idx < 0 || idx >= paramPlans.size) return buildParamPlans(meta)
        if (!useParamPlans) return buildParamPlans(meta)
        val cached = paramPlans[idx]
        if (cached != null) return cached.takeIf { it.isNotEmpty() }
        val built = buildParamPlans(meta)
        paramPlans[idx] = built
        return built.takeIf { it.isNotEmpty() }
    }

    /**
     * [ParamPlan] 缓存的开关（缺省开）。`-Deratw.perf.noParamPlans=true` 关掉。
     *
     * 与 `Vm.useStmtMemo` 同一理由：只服务**同窗口 A/B**（跨窗口读数在本项目已被证伪，
     * 见 `CONTRACT-T2` T2-62 回执第 4 条）。关掉时走 [buildParamPlans] 的即时版本，
     * 语义与优化前逐字相同，只是每实参每次调用重新拆一次字符串。构造期读一次 ⇒ 热路径零成本。
     */
    private val useParamPlans: Boolean = !java.lang.Boolean.getBoolean("eratw.perf.noParamPlans")

    private fun buildParamPlans(meta: FuncMeta): Array<ParamPlan?> {
        val names = program.paramNames(meta.index)
        return Array(names.size) { p ->
            val parts = names[p].removeSuffix("$").split(':')
            val baseName = parts[0].trim()
            ParamPlan(
                baseName = baseName,
                upperName = baseName.uppercase(),
                constIdx = foldParamSubscripts(parts.drop(1), meta.name),
            )
        }
    }

    /**
     * 形参项的下标段（`NAME:n[:m]` 里冒号后的原文）→ 常量下标数组（CMP-85A）。
     *
     * ## 原版口径：这是**装载期**判定
     *
     * `ErbLoader.parseLabel` 逐位取形参项的 `VariableTerm`：不是引用型且
     * `!variableTerm.isAllConst` ⇒ `ArgSubscriptOnlyConst`
     * （`ErbLoader.cs:690-693` → `:773-775`），外层模板 `FuncArgError`
     * （`ErbLoader.cs:768`；`Lang.cs:2446`）。文案取本作 zhs 语言表
     * （`lang.emuera-zhs.xml`）：`:566` = `函数"@{0}"的参数错误:{1}`、
     * `:576` = `函数定义的参数下标必须指定为一个常量`。
     *
     * ## 生产者侧已于解析期拒绝；这里只留防御
     *
     * `LineParser.extractLabelParamRefs`（2026-10-24，CMP-85A）现在按顶层 `:` 分段、
     * 用 `ExprParser.reduceIntegerTerm` 归约每段，结果必须是 `IntLiteral`，否则解析期直接抛
     * `StmtParseException`（官方 zhs「函数定义的参数下标必须指定为一个常量」+ `ArgSubscriptOnlyConst`）；
     * `tokenText` 也已改为收整个 token 列表，`OUT:1+1` 的原文会完整保留、不再被截成 `OUT:1`。
     * 所以运行期正常不会再走到这里；本函数保留作**手构 `FuncSig` / 旧产物**的防御：
     * 十进制整数之外的形态一律按非 const 报错（旧形态是**静默跳过整位写回**，
     * 形参保留旧值、调用方无从察觉）。
     *
     * @param rawSubs 冒号后的各段原文（`@FA(OUT:1:2)` → ["1", "2"]）
     * @param callee 被调函数名（报错文案用）
     */
    private fun foldParamSubscripts(rawSubs: List<String>, callee: String): LongArray {
        if (rawSubs.isEmpty()) return LongArray(0)
        return LongArray(rawSubs.size) { k ->
            rawSubs[k].trim().toLongOrNull() ?: throw CodeEE(
                "函数\"@$callee\"的参数错误:函数定义的参数下标必须指定为一个常量",
            )
        }
    }

    /**
     * 省略形参位的**装载期隐式默认值**（原版 `FunctionLabelLine.Def[i]` 的 0/"" 形态）。
     *
     * `ErbLoader.parseLabel` 对每个头部形参先取 `[变量, 默认表达式]` 对（:661-676）；
     * 默认表达式是 NullTerm（没有 `= `，或 `= ` 后为空）时：`flag` = 形参是 `ARG`/`ARGS`
     * 或私有变量（:714）→ `Def[i]` 仍写成 `SingleLongTerm(0)` / `SingleStrTerm("")`
     * （:716-722），否则保持 null。于是 ConvertArg 的省略分支（CalledFunction.cs:165-173）
     * 只对「非 ARG/ARGS/私有的形参」才会落到 CanNotOmitArg；其余省略位拿到的是
     * 非 null 的 Def，照常按位写回（ProcessState.cs:443-450）。
     *
     * 本函数在绑定点复刻这个写回：
     * - `ARG`/`ARGS` 形参 → 写 0 / ""；
     * - 私有用户变量形参 → 按声明类型写 0 / ""；
     * - 其余（全局/系统变量形参、查不到的形参名）→ 返回 false，由调用方按
     *   CompatiFuncArgOptional 报错或跳过。默认值只允许出现在 ARG/ARGS/私有形参上
     *   （ErbLoader.cs:729-732 → `ArgCanOnlyPrivVar` :771-772），所以这些形参的
     *   `Def[i]` 必为 null。
     *
     * @return true = 已按隐式默认写回（省略合法，调用方继续下一位）；
     *   false = 该位没有默认值可用，省略合法性由配置决定。
     */
    private fun bindImplicitArgDefault(paramName: String, meta: FuncMeta): Boolean {
        // 形参项原文 `NAME[:n[:m]]`：与 [bindCallArgs] 主循环同一解析口径。
        val parts = paramName.removeSuffix("$").split(':')
        val baseName = parts[0].trim()
        val idx = foldParamSubscripts(parts.drop(1), meta.name)
        val sysName = baseName.uppercase()
        if (sysName == ARG_VAR) {
            store.writeInt(ARG_VAR, longArrayOf(idx.firstOrNull() ?: 0L), 0L)
            return true
        }
        if (sysName == ARGS_VAR) {
            store.writeStr(ARGS_VAR, longArrayOf(idx.firstOrNull() ?: 0L), "")
            return true
        }
        val param = store.userVar(baseName, meta.key) ?: return false
        if (param.spec.isConst) return false
        // REF 位在调用方先判（CanNotOmitRefArg），且装载期禁止给 REF 形参默认值
        // （ErbLoader.cs:756-758 → RefArgCanNotInitialize）；这里不再写值。
        if (param.spec.isRef) return false
        if (!param.spec.isPrivate) return false
        val dims = param.def.dims.size
        if (idx.size > dims) return true
        val indices = LongArray(dims)
        for (k in idx.indices) indices[k] = idx[k]
        if (param.spec.isStr) {
            store.writeStr(baseName, indices, "")
        } else {
            store.writeInt(baseName, indices, 0L)
        }
        return true
    }

    /**
     * 用户函数实参兼容开关的**现读**（每次绑定现取；不在注册期捕获、不建第二份配置真相）。
     *
     * 条目表走 [ConfigMethods.store] 的 `store.aux` 槽 —— 与 GETCONFIG / 运行期词法配置
     * 同一份 `ConfigData` 镜像（[runtimeLexerConfig] 同源）：三层链（default → emuera →
     * fixed）已由宿主装配重放进该槽，本函数只读条目的 `bool()` 现值。
     *
     * spec：`Config.CompatiFuncArgAutoConvert` / `Config.CompatiFuncArgOptional` 是进程级
     * 静态字段（Config.cs:157、:159），由 `Config.SetConfig` 从条目表回填（:374-375）；
     * 两条目出厂默认均 false（ConfigData.cs:108-109），本作 `emuera.config` 为
     * `AUTO TOSTR CONVERSION FOR USER FUNCTION ARGUMENTS:YES` /
     * `ALLOW ARGUMENTS OMISSION FOR USER FUNCTIONS:NO`（第 62/61 行）。
     */
    private fun funcArgCompat(key: ConfigKey): Boolean =
        ConfigMethods.store(store.aux, effectiveBuiltinHost).itemOf(key).bool()

    /**
     * 原版 `Config.GetConfigName` 的字符串形态（Config.cs:291-294 →
     * ConfigData.cs:204-214：`item.Text + "/" + item.EngText`，两段在注册期被
     * `ToUpperInvariant`，本侧 [com.eratw.port.core.config.ConfigSpec.upperJpLabel] /
     * [com.eratw.port.core.config.ConfigSpec.upperEnLabel] 同形）。
     *
     * 用途 = CanNotOmitArgWithMessage / CanNotConvertIntToStr 文案里的
     * 「兼容性配置「{2}」」占位（CalledFunction.cs:170、:183）。
     */
    private fun funcArgConfigName(key: ConfigKey): String {
        val spec = ConfigMethods.store(store.aux, effectiveBuiltinHost).itemOf(key).spec
        return spec.upperJpLabel + "/" + spec.upperEnLabel
    }

    /** 条目表布尔项的现读（与 [funcArgCompat] 同一条 `store.aux` 链，不建第二份配置真相）。 */
    private fun configBool(key: ConfigKey): Boolean =
        ConfigMethods.store(store.aux, effectiveBuiltinHost).itemOf(key).bool()

    /**
     * `Config.CompatiCallEvent`（`ConfigCode.cs:75` = 71；条目出厂默认 false
     * `ConfigData.cs:105`；本作 `emuera.config:58` = `ALLOW CALL ON EVENT FUNCTIONS:NO`）
     * 的现读。`Config.cs:376` 由 `SetConfig` 从条目表回填静态字段，故运行期取值 = 条目现值。
     *
     * 全仓消费点（2026-10-24 按键名 grep 穷举，reference/emuera-cs 全仓）：
     * - 条目/UI 面（非行为）：`ConfigCode.cs:75`、`ConfigData.cs:105`、
     *   `Config.cs:161`（属性声明）与 `:376`（回填）、`ConfigDialog.cs:745/953/957/1256/1264`；
     * - **运行期行为开关只有两处**（都在 LabelDictionary，均为"把事件标签同时登记进
     *   `noneventLabelDic`"）：`:70-73`（`SortLabel`，逐文件增量登记，ErbLoader.cs:517 调用）
     *   与 `:181-184`（`SortLabels`，全量重排，ErbLoader.cs:574 调用——**最终态由它决定**，
     *   增量登记被 `:165-167` 的 clear 后重建覆盖）；
     * - `CalledFunction.cs:102`：`CALL` 落到事件名那条 CodeEE 的 `{1}` 占位（**文案**用，
     *   不是行为开关 —— 行为分叉只由上面两处登记决定）。
     *
     * 行为分叉的唯一形态 = 「按名查非事件表（`GetNonEventLabel`，LabelDictionary.cs:359-366）
     * 时，事件名从查不到变成查到第一条成员（`value2[0]`，`FunctionLabelLine.CompareTo`
     * 排序首条，FunctionLabelLine.cs:85-96）」。该表的全部运行期读者：
     * - `CalledFunction.cs:93-114`（`CallFunction`）：命中即当**普通函数**建帧
     *   （`IsEvent=false`，只执行那一条成员）—— CALL / CALLFORM / TRYCALL / JUMP
     *   与 BEGIN 的非事件分支（`Process.cs:2090-2099`）同路；
     * - `CalledFunction.cs:71-75`（`CallEventFunction`）：事件名在 eventLabelDic 仍命中
     *   ⇒ 事件派发不受影响；仅非事件名走这条（CalleventToNonEventFunc）；
     * - `IdentifierDictionary.cs:554-575`（`GetFunctionMethod(userDefinedOnly: true)`，
     *   CALLF / CALLFORMF / TRYCALLF 与 GETMETH / GETMETHS 共用）：非方法事件名
     *   ⇒ CallfNonMethodFunc（:557-560），事件方法 ⇒ 可调用（:561-564）；
     * - `FunctionMethodCreator.cs:8248-8280` / `:8282-8302`（EXISTFUNCTION 两形态）、
     *   `:8555-8569` / `:8597-8611`（GETMETH / GETMETHS）、EnumNameMethod 的 FUNCTION 源
     *   `NoneventKeys`（`LabelDictionary.cs:30`，:280）——符号查询族；
     * - `IdentifierDictionary.cs:520`（`GetOverloadedList`，仅诊断面）。
     *
     * 移植侧的等价判定见 [firstEventMember]；各调用点的分叉落地见 [eventAsNonEvent]、
     * [resolveMethodIndex]、[resolveForMethod]、[tryCallF] 与 userFunctionKinds 的投影。
     */
    private fun compatiCallEvent(): Boolean = configBool(ConfigKey.CompatiCallEvent)

    /**
     * `CompatiCallEvent` 为真时事件名在非事件表里的那一条 ——
     * `LabelDictionary.cs:181-184` 的 `noneventLabelDic.Add(key, value2[0])`：
     * `value2` 按 `FunctionLabelLine.CompareTo`（`:85-96`：FileIndex → LineNo → Index）排序，
     * 故取**装载最早**的成员；`CalledFunction.cs:110-114` 以它为 TopLabel/CurrentLabel 建帧。
     *
     * 移植侧等价取值 = `eventCallOrder` 中 `defIndex` 最小者（defIndex = 全语料装载序号）。
     * 注意：不能直接用 `table.nonEvent(key)` —— 生产侧车为事件名额外编了一个
     * 「事件入口」合成函数（`CorpusCompile.kt:500-513` 的 `compileEventEntry`，
     * `FuncMeta.isEvent=false`），它会把事件名顶成 NON_EVENT 并**执行全部成员**，
     * 与 `value2[0]`（单成员）和 NO 分支（CodeEE）都不符。
     *
     * TODO(verify)：`defIndex` 序与 `(FileIndex, LineNo, Index)` 序不一致时（多文件装载序
     * ≠ 文件号序）会取到不同成员。**本语料事件名恰为多定义**（2026-10-24 grep 实测：
     * `IdentifierDictionary.cs:76-84` 的 9 个事件名里 EVENTTRAIN 有 10+ 条定义、
     * EVENTLOAD 7+ 条、EVENTFIRST 2 条，跨 fromEN/QOL/DLC 多文件）⇒ 该排序差在 YES 态
     * 结构上可达；本作 `emuera.config:58` = NO，分叉整体关闭，不可观测。
     *
     * 另登记一条官方 bug（ADR-005，YES 态才触发）：增量登记路径 `SortLabel`
     * （LabelDictionary.cs:70-73）对**同名的第二条事件标签**用 `Dictionary.Add`
     * （`:72`）会抛 ArgumentException —— ErbLoader.cs:517 逐文件调用它，本语料多文件
     * 重复定义事件名 ⇒ YES 态下原版在装载期即崩。移植侧没有逐文件增量登记的对应面
     * （FunctionTable 一次性合并），不复刻该崩溃；NO 态不可达。
     */
    private fun firstEventMember(key: String): FuncMeta? =
        table.eventCallOrder(key)?.minByOrNull { it.defIndex }

    /**
     * `CompatiCallEvent` 分叉的**唯一判定点**（`CALL` / `CALLFORM` / `TRYCALL` /
     * BEGIN 状态机四条按名解析路径共用，避免四处各写一份）：
     * - 名字不是事件名 → null，调用方走原来的 `classify` 三态；
     * - 是事件名且开关为真 → 返回"已登记进非事件表"的那条成员（[firstEventMember]）；
     * - 是事件名且开关为假 → 抛 CallToEventFunc（`CalledFunction.cs:100-103`）。
     *
     * 方法名判（`CalledFunction.cs:106-109`）放在这里：事件标签若同时带 `#FUNCTION`，
     * 原版两条分支都会走到 CallToUserFunc。
     */
    private fun eventAsNonEvent(key: String): FuncMeta? {
        val member = firstEventMember(key) ?: return null
        if (!compatiCallEvent()) throw eventCallError(key)
        if (member.isMethod) throw methodCallError(member)
        return member
    }

    /**
     * int 实参自动转字符串的取值 —— 与内建 TOSTR 的一参形态同一条格式化实现：
     * `core/fn/PureMethods.kt` 的 [formatDotNet]（空格式 = `Long.toString()`），
     * 与 spec: FunctionMethodCreator.cs:5484-5486 的 `intValue.ToString()` 同口径。
     * 不另写数字格式（MONEYSTR/TOSTR 的格式串实现也只有那一份）。
     */
    private fun intArgToStr(value: Long): String = formatDotNet(value, "", "TOSTR")

    /**
     * 字符串形参位的实参值：实参本是 str 直接取；int 实参按 `CompatiFuncArgAutoConvert`
     * 自动包 TOSTR（spec: CalledFunction.cs:181-198；:186-189 取内建 TOSTR、:190-197 包
     * `FunctionMethodTerm`），开关关闭时抛 CanNotConvertIntToStr（:181-185，文案 :183 带
     * 配置双语名）。
     */
    private fun strArgValue(callee: String, argNo: Int, a: CallArg, slot: Int): String {
        if (a.isStr) return activeVm.getStr(slot)
        return intToStrArgValue(callee, argNo, activeVm.getInt(slot))
    }

    /**
     * int 实参 → str 形参的转换本体 + 报错文案（`Lang.Error.CanNotConvertIntToStr`，
     * CalledFunction.cs:181-198；文案 zhs:686 —— 「从整转换为字符串型型」的重复「型」
     * 是官方原文，按 ADR-005 照抄）。
     *
     * 三条实参通道共用这一份：调用点帧槽（[strArgValue]）、逐位值列表
     * （[bindPositionalValues]）、旧类型桶（本函数无消费方时由前者兜住）——
     * 抄成多份就等于给这条官方错字留了「被顺手改对」的口子。
     */
    private fun intToStrArgValue(callee: String, argNo: Int, value: Long): String {
        if (!funcArgCompat(ConfigKey.CompatiFuncArgAutoConvert)) {
            throw CodeEE(
                "不能将\"@$callee\"的第${argNo}个参数从整转换为字符串型型" +
                    "(此警告可以用兼容性配置「${funcArgConfigName(ConfigKey.CompatiFuncArgAutoConvert)}」来无视)",
            )
        }
        return intArgToStr(value)
    }

    /**
     * str 实参 → int 形参的调用点错误：`Lang.Error.CanNotConvertStrToInt`
     * （spec: CalledFunction.cs:176-179，实参 = 被调标签名 / 头部形参位 1 基）。
     * 文案取本作 zhs 语言表（`lang.emuera-zhs.xml:685` =
     * `不能将"@{0}"的第{1}个参数从字符串型转换为整型`，与既有 CanNotOmitRefArg 同源）。
     */
    private fun strToIntArgError(callee: String, argNo: Int): CodeEE =
        CodeEE("不能将\"@$callee\"的第${argNo}个参数从字符串型转换为整型")

    /**
     * 省略非 REF 实参且 `CompatiFuncArgOptional` 关时的调用点错误：
     * `Lang.Error.CanNotOmitArgWithMessage`（spec: CalledFunction.cs:168-172，实参 =
     * 被调标签名 / 头部形参位 1 基 / `Config.GetConfigName(CompatiFuncArgOptional)` :170）。
     * 文案取本作 zhs 语言表（`lang.emuera-zhs.xml:684`）。
     */
    private fun cannotOmitArgError(callee: String, argNo: Int): CodeEE =
        CodeEE(
            "@${callee}的第${argNo}个参数不能省略" +
                "(此警告可以用兼容性配置「${funcArgConfigName(ConfigKey.CompatiFuncArgOptional)}」来无视)",
        )

    /**
     * REF 目标绑定（`ReferenceToken.SetRef`，spec: ReferenceToken.cs:125-128）。
     *
     * 原版绑的是 CLR 数组对象本身：多维 `long[,]`/`long[,,]` 自带形状，被调的
     * `GetIntValue`/`SetValue` 用 `array[i, j]` 直接寻址（spec: VariableData.cs:2389-2417 /
     * :2458-2488），`CheckElement`/`GetLength` 也问数组本体（spec: ReferenceToken.cs:32-76）。
     * 本移植的存储是行主序压平的一维数组，形状在数组本体上不可见，故必须把
     * [RefSource.dims]（**元素维**，见 [com.eratw.port.core.vars.VarDef.dims]）一起交给
     * [RefUserVar.bindInt]/[RefUserVar.bindStr]，否则被调只能用「全 0 下标」。
     *
     * 目标可以是用户变量、**系统数组**（`TSTR`/`SAVESTR`/`GLOBALS`/`RESULTS` 等全局 1D/2D/3D）与
     * **局部数组**（`LOCAL`/`LOCALS`/`ARG`/`ARGS`）：原版实参按 `IdentifierDictionary.GetVariableToken`
     * 解析（spec: :429-495），`GetArray()` 对局部变量还会惰性分配（spec: VariableData.cs:1202-1209 /
     * :1279-1285）—— 见 [resolveRefSource] / [com.eratw.port.core.vars.VariableStore.refBindTarget]。
     * 真机红 L①（`SPLIT_F(..., LOCALS)`，qol_skipmove.ERB）正是这一档：旧实现只查用户变量，
     * 解析不到就 `continue`，被调首读才抛 EmptyRefVar。
     *
     * **调用点校验**（原版在 `CalledFunction.ConvertArg` → `ReferenceToken.MatchType`，
     * spec: CalledFunction.cs:159-162 / ReferenceToken.cs:130-169，失败即 CodeEE、文案
     * 用 NumberOfArg 包装成「@函数名 的第N个参数:…」，lang.emuera-zhs.xml）：
     * 旧实现把常量/角色变量/类型不符/维度不符一律**静默跳过绑定**，REF 留空推迟到
     * 被调首次读才报 EmptyRefVar —— 与原版「调用点即报、报因明确」可证偏离，现按原版
     * 分支逐条复刻（顺序即 MatchType 的分支顺序）：
     * * 0 维实参（`Dimension == 0`，CalledFunction.cs:154-158）→
     *   RequireArrayBecauseRefArg（此判在 MatchType 之前，不带 NumberOfArg 包装）；
     * * 伪变量（`rother.IsCalc`，ReferenceToken.cs:138-142）→「无法引用伪变量」；
     * * 常量（`rother.IsConst`，:143-147）→「无法引用常量」；
     * * 角色变量（`allowChara: false`，:153-157）→「无法引用角色变量」；
     * * 整型/字符串不符（`IsInteger` 不同，:158-162）→「无法引用类型不同的变量」；
     * * 维数不符（`Dimension` 不同，:163-167）→「无法引用维度不同的数组变量」；
     * * REF 转发时源 REF 未绑定（`GetArray()` 抛 EmptyRefVar，ReferenceToken.cs:116-123）
     *   →「引用类型变量”…“缺少引用对象」。
     * 全局 REF 引用调用方局部变量（`!base.IsPrivate && rother.IsPrivate/IsLocal`，
     * ReferenceToken.cs:148-152）不在本面：ERB 函数头形参一律私有（GLOBAL 关键字在
     * ERB 内被 CanNotUseKeywordLocalVar 拒绝，UserDefinedVariableData.cs:171-175）。
     */
    private fun bindRef(
        target: RefUserVar,
        src: RefSource,
        isStr: Boolean,
        callee: String,
        argNo: Int,
    ) {
        fun fail(msg: String): Nothing = throw CodeEE("@${callee}的第${argNo}个参数:$msg")
        // 0 维实参先于 MatchType（spec: CalledFunction.cs:154-158，RequireArrayBecauseRefArg，
        // 文案不带 NumberOfArg 包装）。
        if (src.dims.isEmpty()) {
            throw CodeEE("@${callee}的第${argNo}个参数必须是是引用形式的数组变量")
        }
        // 以下分支与顺序照 `ReferenceToken.MatchType`（spec: ReferenceToken.cs:130-169）。
        if (src.def.storage == Storage.PSEUDO) fail("无法引用伪变量") // :138-142
        if (src.isConst) fail("无法引用常量") // :143-147
        // ConvertArg 恒以 allowChara: false 调用（spec: CalledFunction.cs:159），故角色变量一律拒绝
        // （spec: ReferenceToken.cs:153-157）；SetTransporter 里的 chara 分支（UserDefinedFunctionArgument.cs:46-54）
        // 因此对用户函数实参不可达。
        if (src.def.isCharacterData) fail("无法引用角色变量")
        // MatchType 的比较对象是 REF 形参 token vs 实参 token（ReferenceToken.cs:158-162），
        // 不是实参自己跟自己 —— isStr 来自实参，必须对 target 比。
        if (target.spec.isStr != isStr) fail("无法引用类型不同的变量")
        if (src.dims.size != target.spec.dims.size) fail("无法引用维度不同的数组变量")
        if (src.isRefSource) {
            // REF 转发：把源 REF 的目标（连同形状）再绑一层，源 REF 未绑即抛 EmptyRefVar
            // （原版 GetArray 在**调用点**取数组，spec: ReferenceToken.cs:116-123；
            // SetTransporter 路径 UserDefinedFunctionArgument.cs:45-58）。
            // 目标数组取 [RefSource] 在解析时留的**快照**，不现读持有者：同一函数递归
            // （callerKey == calleeKey）时两边共用同一个 RefUserVar 实例，被调帧的
            // scopeIn 已把 target 清空 —— 现读只能读到空引用（真机红：五子棋
            // `GOMOKU_ALPHABETA` 自递归 → 「引用类型变量"BOARD"缺少引用对象」）。
            // 形状必须一并转发，否则二维 REF 链会在下游退化成「只取第一个下标」
            // （spec: ReferenceToken.cs:58-76 按 GetLength(d) 逐维判界）。
            if (isStr) {
                val arr = src.strs
                    ?: throw CodeEE("引用类型变量\"${src.def.name}\"缺少引用对象")
                target.bindStr(arr, src.boundDims)
            } else {
                val arr = src.ints
                    ?: throw CodeEE("引用类型变量\"${src.def.name}\"缺少引用对象")
                target.bindInt(arr, src.boundDims)
            }
            return
        }
        // 目标形状 = 被引用变量的元素维（spec: ReferenceToken.cs:58-76 的逐维 GetLength）。
        // 源数组可能是系统全局（[VarDef.dims]）或局部实例（实际长度），都已由 [resolveRefSource] 带出；
        // 形状只在与被调 REF 的声明维数一致时生效，不一致时 RefUserVar.shapeOf 保留 null，
        // 走「1D 原样 / 多维仅全 0」的防静默路径（原版在装载期按 CanNotRefDifferentDim 拒绝，
        // 见 ReferenceToken.cs:163-167；本侧无装载期校验，不在此猜形状）。
        // 这里不预先 copyOf：shapeOf 只在维数一致时接收该数组且自带拷贝，
        // VarDef.dims 全仓只读，绑定点无需双重复制。
        // 绑定通道按实参类型（= 原版 `rother.IsInteger` 侧）走，与上面类型校验同口径。
        if (isStr) src.strs?.let { target.bindStr(it, src.dims) }
        else src.ints?.let { target.bindInt(it, src.dims) }
    }

    /**
     * REF 绑定源（[bindCallArgs] → [bindRef] 的中间形态）：调用方某个实参变量解析出的
     * 「元信息 + 底层数组（别名）」。用户变量 / 系统数组 / 局部数组统一成这一形态，
     * [bindRef] 只按 `ReferenceToken.MatchType` 的分支判型，不关心它来自哪一层。
     */
    private class RefSource(
        val def: VarDef,
        val isConst: Boolean,
        /** 元素维长度；空数组 = 0 维（不可作 REF 目标）。 */
        val dims: IntArray,
        val ints: LongArray?,
        val strs: IntArray?,
        /**
         * true = 实参本身是 REF 变量（转发链，spec: ReferenceToken.cs:91-114）：
         * [ints]/[strs] 是**解析那一刻**持有者目标的快照（未绑定 = 双 null），
         * [boundDims] 是当时的形状。
         */
        val isRefSource: Boolean = false,
        /** REF 持有者目标形状快照（仅 [isRefSource] 时有意义；null = 未知/一维以外的未给形状）。 */
        val boundDims: IntArray? = null,
    )

    /**
     * 解析 REF 实参名 → [RefSource]。
     *
     * 查表顺序同原版 `IdentifierDictionary.GetVariableToken`（spec: :429-495）：调用方私有
     * 用户变量 → 局部变量 → 系统变量（全局用户变量与系统变量同在该字典里，排在局部之后，
     * 但用户变量名不可能与 `LOCAL` 等关键字重名 —— 声明期就被 CanNotUseKeywordLocalVar 拒绝，
     * spec: UserDefinedVariableData.cs:171-175）。
     *
     * 实参带下标（`NAME:k`）时**忽略下标**：原版只取 `VariableTerm.Identifier.GetArray()`
     * （spec: UserDefinedFunctionArgument.cs:45-58），不看 term 上的 arguments，故
     * `FLAG:0` 与 `FLAG` 作 REF 实参等价（RefArgBindingTest 已锁）。
     *
     * 源是 REF 变量时，目标数组与形状在**本函数**取快照：解析点即原版的 `GetArray()`
     * 调用点，而 [applyPendingRefBindings] 落到被调 REF 上时被调帧的 `scopeIn()` 可能
     * 已经把同一个实例（同一函数递归）清空。
     */
    private fun resolveRefSource(name: String, callerKey: String?): RefSource? {
        val user = store.userVar(name, callerKey)
        if (user != null) {
            if (user is RefUserVar) {
                return RefSource(
                    def = user.def,
                    isConst = user.spec.isConst,
                    dims = user.def.dims,
                    ints = user.targetInt,
                    strs = user.targetStr,
                    isRefSource = true,
                    boundDims = user.targetDims,
                )
            }
            val raw = user.rawArray()
            return RefSource(
                def = user.def,
                isConst = user.spec.isConst,
                dims = user.def.dims,
                ints = raw as? LongArray,
                strs = raw as? IntArray,
            )
        }
        // 系统数组 / 局部数组（局部按**调用方**函数取实例，见 VariableStore.refBindTarget）
        val sys = store.refBindTarget(name, callerKey) ?: return null
        return RefSource(
            def = sys.def,
            isConst = sys.def.isConst,
            dims = sys.dims,
            ints = sys.ints,
            strs = sys.strs,
        )
    }

    /**
     * 每函数「头部形参位是否 REF 形参」的懒建缓存（按 `meta.index`），null = 还没算过。
     * 与 [paramPlans] 同一相位：只在首次调用该函数时问一次 `refUserParamAt`，
     * 之后热路径只剩一次数组读（无 REF 位的函数同样缓存空表 ⇒ 逐个 `_CALL` 零成本）。
     */
    private val refParamFlags = arrayOfNulls<BooleanArray>(program.functions.size)

    private val noRefParams = BooleanArray(0)

    private fun refParamFlagsOf(meta: FuncMeta): BooleanArray {
        val idx = meta.index
        if (idx < 0 || idx >= refParamFlags.size) return buildRefParamFlags(meta)
        refParamFlags[idx]?.let { return it }
        return buildRefParamFlags(meta).also { refParamFlags[idx] = it }
    }

    private fun buildRefParamFlags(meta: FuncMeta): BooleanArray {
        val names = program.paramNames(meta.index)
        if (names.isEmpty()) return noRefParams
        var any = false
        val flags = BooleanArray(names.size)
        for (i in names.indices) {
            if (refUserParamAt(names[i], meta) != null) {
                flags[i] = true
                any = true
            }
        }
        return if (any) flags else noRefParams
    }

    /**
     * 调用点的 REF 形参位实参 → 进帧**之前**的 [RefSource] 快照（按实参表下标对齐）。
     *
     * 原版的实参转换发生在**调用点**（`UserDefinedFunctionArgument.cs:45-58` 的
     * `variableTerm.Identifier.GetArray()`，装载期/`DoInstruction` 相位），此时调用方帧
     * 是栈顶、被调私有变量还没 `scopeIn()`。本侧逐位绑定的解析原本放在进帧之后
     * （[bindCallArgs]），对「同一函数递归 + REF 形参」这一形态是错的：caller 与 callee
     * 共用同一个 [RefUserVar] 实例，`enterFunction` 的 `scopeIn()` 先把 target 清空，
     * 进帧后解析只能得到空引用 ⇒ 误报「引用类型变量…缺少引用对象」
     * （真机红：`PLAY_GOMOKU.ERB` 的 `GOMOKU_ALPHABETA` 自递归，2026-10-29 玩家上报）。
     *
     * @return null = 该调用点没有 REF 位 / 拿不到调用点元数据（热路径零分配退出）。
     */
    private fun resolveRefArgsBeforeEnter(meta: FuncMeta, callerKey: String?): Array<RefSource?>? {
        val flags = refParamFlagsOf(meta)
        if (flags.isEmpty()) return null
        val pc = activeVm.lastCallPc
        if (pc < 0) return null
        val args = program.callArgsAt(pc) ?: return null
        var out: Array<RefSource?>? = null
        for (i in 0 until args.size) {
            val a = args[i]
            if (!a.present) continue
            val pos = a.position
            if (pos < 0 || pos >= flags.size || !flags[pos]) continue
            val name = a.name ?: continue
            (out ?: arrayOfNulls<RefSource?>(args.size).also { out = it })[i] =
                resolveRefSource(name, callerKey)
        }
        return out
    }

    /**
     * 消费 [pendingRefBindings]（重入调用建好的 REF 形参绑定），在**被调帧已进入**
     * （私有变量 `scopeIn()` 之后）落到被调 [RefUserVar] 上。绑定顺序与形参位序一致；
     * 异常时不回滚已绑位（原版 `IntoFunction` 同样逐位写、遇错即抛）。
     */
    private fun applyPendingRefBindings() {
        val refs = pendingRefBindings
        if (refs.isEmpty()) return
        pendingRefBindings = emptyList()
        for (r in refs) {
            bindRef(r.target, r.src, r.isStr, r.callee, r.argNo)
        }
    }

    /**
     * 头部形参位原文 → 被调的 `#DIM REF` 私有变量；非 REF 形参 / 名字查不到声明返回 null。
     * 与 [bindCallArgs] 主循环的形参名归一口径一致（剥 `$` 后缀、剥 `:n` 下标段）。
     */
    private fun refUserParamAt(paramName: String, meta: FuncMeta): com.eratw.port.core.vars.UserVar? {
        // PERF（2026-10-27 第二批）：`split(':').first()` 每次都建一个 `ArrayList` + 一个 `String`
        // （JFR 分配采样里 `ProcessRuntime.refUserParamAt` 的 split 占执行循环内分配的 1.9%，
        // 其中 `Collections$SingletonList` + `String` + `byte[]` 三样全是这一句产出的）。
        // `substringBefore` 在**没有分隔符时返回 `this`**（Kotlin 的 `missingDelimiterValue` 缺省），
        // 而形参名绝大多数不带 `:n` 下标段 ⇒ 常规路径零分配；带下标段时与原实现同样切一次。
        // `removeSuffix` / `trim` 在无需改动时同样返回 `this`，整条链只剩必要的切片。
        val baseName = paramName.removeSuffix("$").substringBefore(':').trim()
        val param = store.userVar(baseName, meta.key) ?: return null
        return if (param.spec.isRef) param else null
    }

    // ------------------------------------------------------------------
    // 根级执行
    // ------------------------------------------------------------------

    /** 从程序入口执行到底（等价 `vm.run()`）。 */
    fun execute(): Long = runEntry(program.entryFunction, null)

    /**
     * 从任意函数启动一次完整执行：目标函数直接作为 VM 的第 0 帧
     * （[VirtualMachine.run]`(targetIndex)`），不复制指令流、不追加合成根指令。
     */
    private fun runEntry(targetIndex: Int, bind: RootBind?): Long {
        if (inRun) {
            // VM 不可重入：运行中再入会摧毁外层帧状态。见类 KDoc"表达式位置的函数调用"。
            throw CodeEE("运行期重入调用不受支持（函数下标 $targetIndex）；应由编译期展开为 _CALL")
        }
        if (targetIndex !in program.functions.indices) {
            throw CodeEE("函数下标越界($targetIndex)")
        }
        // 引擎级函数屏蔽（[EngineFunctionPolicy]）：根级入口把目标函数当第 0 帧直接跑，
        // 不经过 VM 的 callInto —— 被屏蔽函数在这里短路，语义与 VM 内一致（空体 `RETURN 0`）：
        // 不建帧、不进 VM、不触发 onFunctionEnter/Exit，直接返回 0。
        if (stubTable.hit(targetIndex)) return 0L
        pendingRoot = bind
        pendingRootBind = bind != null
        inRun = true
        try {
            return vm.run(targetIndex)
        } finally {
            inRun = false
            pendingRoot = null
            pendingRootBind = false
        }
    }

    /**
     * 当前 VM 指令流所属的 [Program]（测试可见）：用于锁死"运行时不再复制第二份
     * code/lines/池"。生产代码不应依赖此访问器。
     */
    internal fun vmProgramForTest(): Program = activeVm.program

    // ------------------------------------------------------------------
    // CALL 家族（CALL / CALLFORM / JUMP / TRYCALL / TRYCALLFORM）
    // ------------------------------------------------------------------

    /**
     * CALL / JUMP 语义的根级入口（JUMP = 调用点不保留返回，根级无差别）。
     *
     * - 未定义 → CodeEE。spec: CALL_Instruction.DoInstruction
     *   FunctionIdentifier.cs:3577-3581（NotDefinedFunc）。
     * - 事件名 → CodeEE。spec: CalledFunction.cs:100-103（CallToEventFunc，
     *   CompatiCallEvent:NO 时事件函数不可 CALL；本游戏 emuera.config
     *   ALLOW CALL ON EVENT FUNCTIONS:NO）。**该键为真时改走普通调用**
     *   （`LabelDictionary.cs:181-184` + `CalledFunction.cs:93-114`，只执行装载最早的成员）。
     * - #FUNCTION 方法名 → CodeEE。spec: CalledFunction.cs:106-109（CallToUserFunc）。
     */
    fun callFunction(
        key: String,
        intArgs: LongArray = LongArray(0),
        strArgs: Array<String> = emptyArray(),
    ): Long {
        val meta = resolveForCall(key)
        return doCall(meta, intArgs, strArgs)
    }

    /**
     * TRYCALL / TRYCALLFORM / TRYJUMP：目标不存在 → 返回 null 静默跳过，不抛错。
     * spec: CALL_Instruction isTry 分支 FunctionIdentifier.cs:3577-3587。
     * TODO(verify)：team-lead 简报称"结果写 RESULT:0"，但 1.824 反编译源码的 isTry
     * 分支无任何 RESULT 写入；按源码实现（不写）。
     * 注意：事件名 / 方法名不豁免 —— CallFunction 在返回 null 之前就无条件抛错
     * （spec: CalledFunction.cs:100-109）；`CompatiCallEvent` 为真时事件名已进非事件表，
     * 于是与普通函数同路调用（`CalledFunction.cs:93-114`）。
     */
    fun tryCall(
        key: String,
        intArgs: LongArray = LongArray(0),
        strArgs: Array<String> = emptyArray(),
    ): Long? {
        eventAsNonEvent(key)?.let { return doCall(it, intArgs, strArgs) }
        return when (table.classify(key)) {
            FunctionTable.TargetKind.NOT_FOUND -> null
            FunctionTable.TargetKind.EVENT -> throw eventCallError(key)
            FunctionTable.TargetKind.NON_EVENT -> {
                val meta = table.nonEvent(key)!!
                if (meta.isMethod) throw methodCallError(meta)
                doCall(meta, intArgs, strArgs)
            }
        }
    }

    /**
     * CALLFORM：FORM 求名后按名查表调用（运行期解析）。语义同 [callFunction]。
     * spec: CALL_Instruction（form 变体）FunctionIdentifier.cs:3572-3575 ——
     * 名字经 FuncnameTerm.GetStrValue 求出后走同一条 CallFunction 路径。
     */
    fun callForm(
        name: String,
        intArgs: LongArray = LongArray(0),
        strArgs: Array<String> = emptyArray(),
    ): Long = callFunction(name, intArgs, strArgs)

    private fun doCall(meta: FuncMeta, intArgs: LongArray, strArgs: Array<String>): Long =
        runEntry(meta.index, rootBindSlot.bindBuckets(intArgs, strArgs))

    /**
     * `CalledFunction.CallFunction` 的名字解析（`:87-115`）。顺序照原版：
     * 先查非事件表（`:93`）→ 查不到再看是不是事件名（`:100`）→ 都不是则 null（`:104`）。
     *
     * `CompatiCallEvent` 为真时事件标签也在非事件表里（`LabelDictionary.cs:70-73` /
     * `:181-184`），所以事件名在第一步就命中 ⇒ 当普通函数调用（`:106-114` 的
     * `IsMethod` 判与建帧）。为假时第二步抛 CallToEventFunc（`:102`）。
     */
    private fun resolveForCall(key: String): FuncMeta {
        eventAsNonEvent(key)?.let { return it }
        return when (table.classify(key)) {
            FunctionTable.TargetKind.NON_EVENT -> table.nonEvent(key)!!
            FunctionTable.TargetKind.EVENT -> throw eventCallError(key)
            FunctionTable.TargetKind.NOT_FOUND -> throw CodeEE(notDefinedFuncMes(key))
        }.also { meta ->
            if (meta.isMethod) throw methodCallError(meta)
        }
    }

    /**
     * CALL / CALLFORM / JUMP 目标名解析不到时的上屏原文（原版 zhs 表形，逐字照抄）。
     *
     * 原版 key = `Lang.Error.NotDefinedFunc`：
     * jp 字面量 `Lang.cs:2262` = 指定された関数名"@{0}"は存在しません；
     * zhs 表形 `lang.emuera-zhs.xml:520` = **没有找到指定的函数"@{0}"**。
     * 引号是**成对直引号**、引号内带 `@` 前缀 —— `@` 是原版 format 串里的字面量
     * （调用方传的是不带 `@` 的函数名，见 `FunctionIdentifier.cs:3569` / `:3574`），
     * 不是装饰、也不能由调用方预先拼上。
     *
     * 抛出相位：`FunctionIdentifier.cs:3577-3581`（`CALL_Instruction.DoInstruction`：
     * 空判定 `:3574`/`:3575` → `!isTry` 才抛 `:3577-3579`）；静态名与 FORM 动态名**共用**
     * 这一条串。装载期同一条文案在 `ErbLoader.cs:1619`。
     *
     * 与 `core/code/Vm.kt` 的 `_CALL_BY_NAME` 分支必须同口径（两处各写一份字面量，
     * 由 `CallMesTextTest` 的交叉用例钉住相等）。
     */
    internal fun notDefinedFuncMes(name: String): String = "没有找到指定的函数\"@$name\""

    /**
     * CALLF / CALLFORMF 运行期解析不到用户 `#FUNCTION` 方法时的上屏原文。
     *
     * 原版 key = `Lang.Error.NotDefinedUserFunc`：jp `Lang.cs:2210` =
     * 式中関数"{0}"が見つかりません；zhs `lang.emuera-zhs.xml:507` =
     * **没有找到行内函数"{0}"**。这条**不带 `@`**（与 [notDefinedFuncMes] 是两条不同 key，
     * 别互相"统一"）。相位：`FunctionIdentifier.cs:1161-1163`
     * （`CALLF_Instruction.DoInstruction` 动态名解析 null 后抛）。
     *
     * 注意装载期是**另一条**：const 名的 CALLF 在 `FunctionIdentifier.cs:1133-1137` 报
     * `NotDefinedFunc`（带 `@` 那条）；移植侧语句层的 [callF] 是运行期入口，取 `:1163`。
     */
    internal fun notDefinedUserFuncMes(name: String): String = "没有找到行内函数\"$name\""

    /**
     * BEGIN / FORCE_BEGIN 的目标函数缺失且 `force=true` 时的上屏原文。
     *
     * 原版相位 = `Process.cs:2090-2099`（`Process.callFunction(name, force, isEvent)`：
     * `CalledFunction` 为 null 且 force → 抛），key = `Lang.Error.FuncIsNotFound`：
     * jp `Lang.cs:3126` = 関数"@{0}"が見つかりません；zhs `lang.emuera-zhs.xml:736` =
     * **找不到函数"@{0}"**。这条**带 `@` 但措辞与 [notDefinedFuncMes] 不同**（第三条 key），
     * 事件与非事件两条分支共用（`Process.cs:2092` 的三元只决定查哪张表）。
     */
    internal fun funcIsNotFoundMes(name: String): String = "找不到函数\"@$name\""

    /**
     * `CALL` 落到**事件函数名**上的上屏原文 —— `Lang.Error.CallToEventFunc`
     * （spec: CalledFunction.cs:100-103；`CompatiCallEvent` 为假才可达）。
     *
     * zhs 表形 `lang.emuera-zhs.xml:679` =
     * `对事件函数"@{0}"进行CALL调用(此警告可以用兼容性配置「{1}」来无视)`：
     * `{0}` = 调用点写的名字原文（`@` 由模板补，调用方不预拼），
     * `{1}` = `Config.GetConfigName(CompatiCallEvent)`（Config.cs:291-294 →
     * ConfigData.cs:204-214 的 `Text + "/" + EngText` 大写形）。
     *
     * 旧实现自造串「不能调用事件函数 X（ALLOW CALL ON EVENT FUNCTIONS:NO）」——
     * 与官方表形不符（缺 `对…进行CALL调用` 骨架、缺「兼容性配置」指引），2026-10-26 改钉。
     */
    private fun eventCallError(name: String) = CodeEE(
        "对事件函数\"@$name\"进行CALL调用" +
            "(此警告可以用兼容性配置「${funcArgConfigName(ConfigKey.CompatiCallEvent)}」来无视)",
    )

    /**
     * 普通 `CALL` 落到 **`#FUNCTION`/`#FUNCTIONS` 方法**上的上屏原文 ——
     * `Lang.Error.CallToUserFunc`（spec: CalledFunction.cs:106-109；方法只能由
     * `CALLF` 家族或表达式位置调用）。
     *
     * zhs 表形 `lang.emuera-zhs.xml:680` =
     * `对标记为#FUCNTION(S)的函数"@{0}"({1}:第{2}行)进行CALL调用`：
     * `{0}` = 标签名原文，`{1}` = 定义所在文件，`{2}` = 定义行号。
     * **`FUCNTION` 是官方拼写错误（原文如此）**，按 ADR-005 原样复刻，不许"顺手改正"。
     *
     * `{1}:{2}` 由 [FuncMeta.position]（erbcc 写 `"<相对路径>:<行号>"`，
     * CorpusCompile.kt:682）拆出；手构 `FuncMeta` 不带 position 时退化成 `(:第0行)`，
     * 与"无位置信息"同义（不猜）。
     */
    private fun methodCallError(meta: FuncMeta): CodeEE {
        val (file, line) = splitPosition(meta.position)
        return CodeEE(
            "对标记为#FUCNTION(S)的函数\"@${meta.name}\"($file:第${line}行)进行CALL调用",
        )
    }

    /** [FuncMeta.position] 的 `文件:行号` 拆分；解析不出行号时行号记 0（不猜）。 */
    private fun splitPosition(position: String): Pair<String, String> {
        val i = position.lastIndexOf(':')
        if (i <= 0 || i == position.length - 1) return position to "0"
        val line = position.substring(i + 1)
        if (line.toIntOrNull() == null) return position to "0"
        return position.substring(0, i) to line
    }

    /**
     * `CALLEVENT`（事件调用）落到**非事件同名函数**上的上屏原文 ——
     * `Lang.Error.CalleventToNonEventFunc`（spec: CalledFunction.cs:71-76；CompatiCallEvent
     * 与事件存在性无关，只由「事件表无成员、非事件表命中」触发）。
     *
     * zhs 表形 `lang.emuera-zhs.xml:678` = `对非事件函数"@{0}"({1}:第{2}行)进行事件调用`
     * （`@` 由模板补）：`{0}` = 查询名原文（`label`），`{1}/{2}` = 命中的非事件 label
     * 定义的文件/行。旧实现自造串「事件调用指向了非事件函数 X」，2026-10-26 改钉。
     */
    private fun nonEventAsEventError(name: String, meta: FuncMeta): CodeEE {
        val (file, line) = splitPosition(meta.position)
        return CodeEE("对非事件函数\"@$name\"($file:第${line}行)进行事件调用")
    }

    /**
     * `CALLF` 家族落到**非方法 label** 上的上屏原文 —— `Lang.Error.CallfNonMethodFunc`
     * （spec: IdentifierDictionary.cs:557-560）。
     *
     * zhs 表形 `lang.emuera-zhs.xml:214` =
     * `对为被标记为#FUNCTION的函数"@{0}"使用了CALLF系列的命令进行调用`
     * （「对为被标记为」是官方原句，照抄；`@` 由模板补）。
     * 旧实现自造串「非方法函数 @X 不能由 CALLF 系命令调用」，2026-10-26 改钉。
     */
    private fun callfNonMethodError(name: String) = CodeEE(
        "对为被标记为#FUNCTION的函数\"@$name\"使用了CALLF系列的命令进行调用",
    )

    /** 语句层抛出的 CodeEE 若不带位置，用 [StmtEnv] 补齐（契约 C4：报错必须能定位函数/行/pc）。 */
    private fun stampPosition(e: CodeEE, env: StmtEnv): CodeEE {
        if (e.funcName != null || e.line >= 0 || e.pc >= 0) return e
        return CodeEE(e.message ?: "", env.functionName ?: env.functionKey, env.line, env.pc)
    }

    // ------------------------------------------------------------------
    // CALLF / CALLFORMF / TRYCALLF（表达式内调用家族的语句形式）
    // ------------------------------------------------------------------

    /**
     * CALLF：调用用户 #FUNCTION 方法，结果丢弃（不写 RESULT、不跳转）。
     * spec: CALLF_Instruction.DoInstruction FunctionIdentifier.cs:1137-1140（GetValue 后弃值）；
     * 未定义 → NotDefinedUserFunc（FunctionIdentifier.cs:1145-1148）。
     */
    fun callF(name: String, args: List<Value?> = emptyList()) {
        executeMethod(resolveForMethod(name), args)
    }

    /**
     * TRYCALLF / TRYCALLFORMF：目标不存在 → 静默跳过返回 false。
     * spec: TRYCALLF_Instruction.DoInstruction FunctionIdentifier.cs:1271-1285
     * （aExpression == null → 不调用、不报错）。
     *
     * `CompatiCallEvent` 分叉口径同 [resolveForMethod]（CALLF 家族共用
     * `GetFunctionMethod(userDefinedOnly: true)`，IdentifierDictionary.cs:554-575）；
     * 注意 TRYCALLF 的 DoInstruction 对 GetFunctionMethod 的抛出**不捕获**
     * （只有装载期 SetJumpTo 试解 catch，:1264-1267）⇒ YES + 非方法事件名照抛。
     */
    fun tryCallF(name: String, args: List<Value?> = emptyList()): Boolean {
        firstEventMember(name)?.let { member ->
            if (!compatiCallEvent()) return false
            if (!member.isMethod) throw callfNonMethodError(name)
            executeMethod(member, args)
            return true
        }
        val meta = table.nonEvent(name)
        if (meta == null || !meta.isMethod) return false
        executeMethod(meta, args)
        return true
    }

    /**
     * 方法解析：`userDefinedOnly` 的 `GetFunctionMethod`（`IdentifierDictionary.cs:551-575`）。
     *
     * 三条判据的顺序照 C# 逐行：
     * 1. 名字在非事件表里存在且**不是方法** → `CallfNonMethodFunc`
     *    （`IdentifierDictionary.cs:557-560`，zhs:214，[callfNonMethodError]）——
     *    这是「存在但不是方法」，不是「不存在」；
     * 2. 存在且是方法 → 建 `UserDefinedMethodTerm` 返回（:561-564）；
     * 3. 不存在 → 返回 null，由调用方抛 `NotDefinedUserFunc`
     *    （`FunctionIdentifier.cs:1161-1163`，zhs:507，[notDefinedUserFuncMes]）。
     *
     * 旧实现把 1 与 3 合并成一条 NotDefinedUserFunc —— `CALLF <非方法>` 会报
     * 「没有找到行内函数」而不是官方的「对为被标记为#FUNCTION的函数…」，2026-10-26 收口拆回。
     *
     * `CompatiCallEvent` 分叉（IdentifierDictionary.cs:554-575）：为真时事件标签在
     * 非事件表里（LabelDictionary.cs:181-184）⇒ 非方法事件名抛 CallfNonMethodFunc
     * （:557-560）、事件**方法**可建帧（:561-564）；为假时事件名查不到 ⇒ null ⇒
     * NotDefinedUserFunc（FunctionIdentifier.cs:1161-1163）。
     */
    private fun resolveForMethod(name: String): FuncMeta {
        firstEventMember(name)?.let { member ->
            if (!compatiCallEvent()) throw CodeEE(notDefinedUserFuncMes(name))
            if (!member.isMethod) throw callfNonMethodError(name)
            return member
        }
        val meta = table.nonEvent(name) ?: throw CodeEE(notDefinedUserFuncMes(name))
        if (!meta.isMethod) throw callfNonMethodError(name)
        return meta
    }

    /**
     * 方法执行：独立帧（形参绑定与 CALL 一致——按**头部形参位**逐位写，见
     * [bindPositionalValues]、私有 #DIM 作用域），返回值整型取 `_RET` 槽值、字符串按约定
     * 取 RESULTS:0（RETURNF 写入，见类 KDoc 调用约定）。方法栈深度上限 100。
     * spec: Process.cs:502-506（methodStack > 100 → OverflowFuncStack）。
     */
    private fun executeMethod(meta: FuncMeta, args: List<Value?>): Value {
        if (methodDepth >= METHOD_STACK_LIMIT) {
            throw CodeEE(OVERFLOW_FUNC_STACK_MES)
        }
        // 实参过多：宿主 API 通道没有调用点 pc，按 `args` 的位置数判（同 [requireArgCount]）。
        requireArgCount(meta, args.size)
        methodDepth++
        try {
            // 逐位实参必须一起交出（`rootBindOf(args, args)`）：只给类型桶会让
            // `CALLF F("s", 7)` 打到 `@F(ARG, ARGS)` 时整段错位且静默 —— 原版
            // `ConvertArg` 只有「形参位 i ↔ srcArgs[i]」一种对位方式
            // （CalledFunction.cs:136-200），没有按类型分桶这一步。
            val ret = runEntry(meta.index, rootBindOf(args, args))
            return if (meta.returnsStr) {
                // `#FUNCTIONS` 的返回通道是 `_RETS`（合同 M3-A §3 A7）：depth 0 处写进
                // [VirtualMachine.stringResult]（Vm.kt:737），**不碰 RESULTS 数组**。
                // 旧实现读 `RESULTS:0` 拿到的是上一次 METHOD 语句留下的陈旧串
                // （与 [invokeUserMethod] 的 `nested.stringResult` 口径不一致）。
                Value.StrV(activeVm.stringResult ?: "")
            } else {
                Value.IntV(ret)
            }
        } finally {
            methodDepth--
        }
    }

    /**
     * 实参分桶（按值类型各自的位置序）。spec: CalledFunction.cs:143-200 位置绑定口径。
     *
     * [values] 非 null = 同时交出逐位实参，供 [bindPositionalValues] 按头部形参名写回
     * （重入方法通道；类型桶只是退路）。
     */
    private fun rootBindOf(args: List<Value?>, values: List<Value?>? = null): RootBind {
        // PERF（2026-10-27 第四批）：这里原本立刻跑两趟 `count{}` 再填充，无条件建出
        // `LongArray` + `String[]`。改成惰性（见 [RootBind] 的类 KDoc）：类型桶只在
        // `bindBuckets` 真正被调用时才建，重入方法通道的常态路径一次都不建。
        // 连对象本身也不新建 —— 重绑 [rootBindSlot]（单槽复用的论证见同一个 KDoc）。
        return rootBindSlot.bind(args, values)
    }

    private var methodDepth = 0

    // ------------------------------------------------------------------
    // 宿主驱动的方法重入执行（BuiltinHost.invokeUserFunction 通道）
    // ------------------------------------------------------------------

    /**
     * 按名调用用户 `#FUNCTION`/`#FUNCTIONS` 方法并取回返回值（GETMETH/GETMETHS 的执行通道；
     * 对应原版 `IdentifierDictionary.GetFunctionMethod` 绑定实参后
     * `functionMethod.GetIntValue/GetStrValue(exm)`，FunctionMethodCreator.cs:8571/8613）。
     *
     * 与 [callF]（语句位，外层不在 run 中）不同，本方法在**表达式求值回调里**被调，
     * 外层 [vm.run] 活跃中——[runEntry] 的 inRun 闸不可用，改从 [nestedVmPool] 借一台
     * 派生 VM（[VirtualMachine.deriveNestedVm]：共享全局槽、独立帧栈），以
     * [VirtualMachine.run]`(meta.index)` 从方法函数直接起跑；[pendingRoot] 由根级/重入
     * 路径串行复用（单线程，ADR-003）。
     * ARG/ARGS 绑定、私有 #DIM 作用域与 [callF] 同一条 [onFunctionEnter] 路径；
     * 方法栈深度与 [callF] 共用 [methodDepth] 上限（spec: Process.cs:502-506）。
     *
     * @return 方法返回值（int 走 `_RET` 顶层返回值；str 走 `_RETS` 顶层字符串通道
     *   [VirtualMachine.stringResult]，run() 每次重置故必为本次的返回）。
     *   null = 该名不是用户方法（未定义/普通 @label/事件名）——语义差异（类型不符、
     *   默认值回落）由调用方按原版口径处理（FunctionMethodCreator.cs:8561-8565 等）。
     * @param refArgs 逐位实参的**未求值**形态（表达式位调用保留的 [VarRef]）；REF 形参位
     *   用它解析调用方数组，其余位可为 null。空表 = 纯按值通道（GETMETH 等宿主调用）。
     */
    fun invokeUserMethod(
        name: String,
        args: List<Value?>,
        refArgs: List<VarRef?> = emptyList(),
    ): Value? {
        // `CompatiCallEvent` 为真时事件标签也在非事件表里（LabelDictionary.cs:181-184）
        // ⇒ 事件**方法**（`#FUNCTION` 事件标签）经 GetFunctionMethod 可建
        // UserDefinedMethodTerm（IdentifierDictionary.cs:561-564）并由 GETMETH 执行
        // （FunctionMethodCreator.cs:8571）。非方法事件名到不了这里：GETMETH 在种类位
        // （PLAIN）处先抛（SymbolQueryMethods.getMethRef，IdentifierDictionary.cs:557-560 口径）。
        val meta = methodMetaOf(name) ?: return null
        // 引擎级函数屏蔽（[EngineFunctionPolicy]）：本通道用派生 VM 的 `run(index)` 直接起跑，
        // 同样绕开 `callInto` —— 被屏蔽方法按空体 `RETURN 0`/`RETURN ""` 就地短路，
        // 不借嵌套 VM、不动方法栈深度（与 [runEntry] 的根级短路同口径）。
        if (stubTable.hit(meta.index)) {
            return if (meta.returnsStr) Value.StrV("") else Value.IntV(0L)
        }
        if (methodDepth >= METHOD_STACK_LIMIT) {
            throw CodeEE(OVERFLOW_FUNC_STACK_MES)
        }
        // 实参过多：同 [executeMethod]（GETMETH 族经本通道调用户方法，原版同样过 ConvertArg）。
        requireArgCount(meta, args.size)
        methodDepth++
        // REF 形参的名字要在**调用方**作用域解析（enterFunction 之后 currentFunction 已换人）。
        val callerKey = store.currentFunction
        val refs = buildPendingRefBindings(meta, args, refArgs, callerKey)
        val nested = nestedVmPool.removeFirstOrNull() ?: vm.deriveNestedVm(this, vmConfig)
        val prevActive = activeVm
        activeVm = nested
        try {
            pendingRoot = rootBindOf(args, args)
            pendingRootBind = true
            pendingRefBindings = refs
            try {
                val ret = nested.run(meta.index)
                return if (meta.returnsStr) {
                    Value.StrV(nested.stringResult ?: "")
                } else {
                    // 小整数走缓存（见 [Value.intV]）：方法返回值同样多为 0/1 标志，
                    // JFR 实测这一行分配 26.97 MiB 的 `Value$IntV`。
                    Value.intV(ret)
                }
            } finally {
                pendingRoot = null
                pendingRootBind = false
                pendingRefBindings = emptyList()
            }
        } finally {
            activeVm = prevActive
            methodDepth--
            nestedVmPool.addFirst(nested)
        }
    }

    /** 重入执行借出的嵌套 VM 池（严格栈式借还，单线程下 LIFO 复用）。 */
    private val nestedVmPool = ArrayDeque<VirtualMachine>()

    /**
     * 表达式位方法调用的**逐位实参缓冲**（PERF 2026-10-27 第四批）。
     *
     * 两张表一一对应被调方法的形参位：`values[i]` = 该位的值（REF 位为 `null`，不求值），
     * `refs[i]` = 该位的裸 [VarRef]（非 REF 位为 `null`）。生命周期严格等于
     * 「[invokeUserMethod] 一次调用」—— 见 [borrowRefCallScratch] 的 KDoc。
     */
    private class RefCallScratch {
        val values = ArrayList<Value?>(6)
        val refs = ArrayList<VarRef?>(6)
    }

    /**
     * [RefCallScratch] 的借还池（单线程，ADR-003）。
     *
     * 为什么是**借还**而不是「按 `methodDepth` 索引的固定格」：求值实参时会递归进
     * `exprCallUserMethodRef`（`@F(@G(1))` 里 G 在外层 F 还没进 `invokeUserMethod`
     * 之前就跑了，此时 `methodDepth` 还没涨），按深度索引会撞同一格、把外层的
     * 半成品实参表覆盖掉。借还池没有这个问题：借出去的不在池里。
     *
     * 归还时机 = `invokeUserMethod` 返回（含抛错）之后 —— 它内部的 `pendingRoot` /
     * `pendingRefBindings` 只在这期间持有这两张表，返回前已在 `finally` 里置空。
     */
    private val refCallScratchPool = ArrayDeque<RefCallScratch>()

    /** 借一格实参缓冲（池空则新建）。 */
    private fun borrowRefCallScratch(): RefCallScratch =
        refCallScratchPool.removeFirstOrNull() ?: RefCallScratch()

    /** 还一格实参缓冲：先断引用（`ArrayList.clear` 会把元素槽置空、保留容量）再入池。 */
    private fun returnRefCallScratch(scratch: RefCallScratch) {
        scratch.values.clear()
        scratch.refs.clear()
        // 上限只是防御「异常路径上池无限长」；正常语料深度不会超过它。
        if (refCallScratchPool.size < REF_CALL_SCRATCH_POOL_MAX) refCallScratchPool.addFirst(scratch)
    }

    /**
     * 名字 → 用户方法元数据（非事件表优先；`CompatiCallEvent` 为真时事件方法也入选）。
     * 与表达式位 [isUnloweredUserMethod] 的判定同源（spec: IdentifierDictionary.cs:561-564）。
     */
    private fun methodMetaOf(name: String): FuncMeta? =
        table.nonEvent(name)?.takeIf { it.isMethod }
            ?: firstEventMember(name)?.takeIf { it.isMethod && compatiCallEvent() }

    /**
     * 重入调用实参表 → REF 形参绑定清单（[PendingRefBinding]）。
     *
     * 只处理「头部形参位是 `#DIM REF` 私有变量 + 实参是裸 [VarRef]」的位（原版
     * `UserDefinedFunctionArgument.cs:35-69` 对 REF 位取 `Identifier.GetArray()`）：
     * - 名字解析在**调用方**作用域（[callerKey]），三层（用户变量 / `LOCAL`/`LOCALS`/
     *   `ARG`/`ARGS` / 系统数组 TARGET/ASSI/…）统一走 [resolveRefSource] →
     *   [com.eratw.port.core.vars.VariableStore.refBindTarget] 的查表链
     *   （原版 `LocalVariableToken.GetArray()` spec: LocalVariableToken.cs:17-37、
     *   `VariableToken.GetArray()` spec: VariableData.cs:2389-2488）；
     * - 全部查不到的实参（角色变量等 `allowChara:false` 面）保持旧口径静默跳过
     *   （TODO(verify)），被调首次读时才报 EmptyRefVar。
     */
    private fun buildPendingRefBindings(
        meta: FuncMeta,
        args: List<Value?>,
        refArgs: List<VarRef?>,
        callerKey: String?,
    ): List<PendingRefBinding> {
        if (refArgs.isEmpty()) return emptyList()
        val paramNames = program.paramNames(meta.index)
        if (paramNames.isEmpty()) return emptyList()
        var out: ArrayList<PendingRefBinding>? = null
        for (i in args.indices) {
            val vr = refArgs.getOrNull(i) ?: continue
            val pn = paramNames.getOrNull(i) ?: continue
            val target = refUserParamAt(pn, meta) as? RefUserVar ?: continue
            val isStr = vr.operandType == OperandType.STR
            // 实参侧名字在**调用方**作用域解析（原版 variableTerm.Identifier.GetArray()
            // 发生在调用者侧）；用户变量 / 局部 / 系统三层由 resolveRefSource 统一查表。
            val src = resolveRefSource(vr.name, callerKey) ?: continue
            (out ?: ArrayList<PendingRefBinding>(4).also { out = it })
                .add(PendingRefBinding(target, src, isStr, meta.name, i + 1))
        }
        return out ?: emptyList()
    }

    // ------------------------------------------------------------------
    // 事件派发（CALLEVENT / EVENTFIRST / EVENTTRAIN / ...）
    // ------------------------------------------------------------------

    private var eventActive = false

    /**
     * 事件派发：按 eventCallOrder（ONLY → PRI → NORMAL → LATER，同桶装载序）逐成员
     * 执行。
     * - #ONLY 成员执行完即终止整个派发。spec: ProcessState.cs:326-333（IsOnly →
     *   FinishEvent）+ CalledFunction.cs:259-264。
     * - #SINGLE 成员返回 1 → 跳过同桶剩余成员。spec: ProcessState.cs:306-310 +
     *   CalledFunction.cs:245-257（ShiftNextGroup）。
     * - 非事件同名函数存在 → CodeEE。spec: CalledFunction.cs:69-77
     *   （CalleventToNonEventFunc）。
     * - 事件不存在 → 返回 false 静默跳过。spec: CALLEVENT_Instruction
     *   FunctionIdentifier.cs:3602-3610（calledFunction == null → 无动作）。
     * - 事件进行中再入 → CodeEE。spec: ProcessState.cs:386-393
     *   （CalleventBeforeFinishEvent）。
     *
     * @return 是否执行了至少一个成员（false = 事件不存在）
     */
    fun executeEvent(name: String): Boolean {
        val order = table.eventCallOrder(name)
        if (order == null) {
            // spec: CalledFunction.cs:69-76 —— GetEventLabels 为空但 GetNonEventLabel 命中
            // ⇒ CalleventToNonEventFunc（官方 zhs 文案，含被命中非事件 label 的文件:行号）。
            table.nonEvent(name)?.let { throw nonEventAsEventError(name, it) }
            return false
        }
        if (eventActive) {
            // 官方 zhs CalleventBeforeFinishEvent（lang.emuera-zhs.xml:735 / Lang.cs:3122）。
            throw CodeEE("EVENT函数结束前执行了CALLEVENT命令")
        }
        eventActive = true
        // 整链终止（2026-10-26 深审补）：原版事件成员全在同一次 runScriptProc 行泵里派发
        // （ProcessState.cs:326-337 的 ShiftNext 逐成员推进，泵条件 Process.cs:1081
        // `while (console.IsRunning && !state.ScriptEnd)`）。任一成员把整条脚本链停掉后后续成员
        // 一律不再派发：QUIT 家族只置 ConsoleState.Quit（Process.cs:1307-1319 → EmueraConsole.cs:731-735，
        // IsRunning 转假 :270-284）；SAVEGAME/LOADGAME 把 state 换成 Clone() 出的空 functionList
        // （ProcessState.cs:475-484）、LOADDATA 清空函数栈（:202-217）⇒ ScriptEnd（:34）即为真。
        // 移植侧每个成员是独立一次 runEntry，其 StmtOutcome.Unwind 只结束该次 run、退回本循环继续
        // i++ —— 故在每次成员 run 之后按「整链终止读数」收尾：quitRequested（QUIT 粘滞位）或
        // SaveLoadHandoff 新落下的待消费系统状态位（SAVEGAME/LOADGAME/LOADDATA 的脚本链作废信号）。
        // 入口快照用于区分「本就挂着待消费状态」（不该由本循环认领）与「本循环内新落下」。
        val handoff = SaveLoadHandoff.of(store)
        val handoffAtEntry = handoff?.systemState
        try {
            var i = 0
            while (i < order.size) {
                val member = order[i]
                val ret = runEntry(member.index, null)
                if (quitRequested || handoff?.systemState?.let { it != handoffAtEntry } == true) {
                    return true
                }
                if (member.isOnly) return true
                if (member.isSingle && ret == 1L) {
                    val bucket = member.eventBucket
                    i++
                    while (i < order.size && order[i].eventBucket == bucket) i++
                    continue
                }
                i++
            }
            return true
        } finally {
            eventActive = false
        }
    }

    // ------------------------------------------------------------------
    // GOTOFORM / TRYGOTOFORM（$ 标签运行期解析）
    // ------------------------------------------------------------------

    /**
     * $ 标签表注入点 —— m3-compile（erbcc）落地后，把编译元数据里的
     * `$标签 → pc` 映射按函数键灌进来（[installLabelTable]）。
     *
     * 控制转移本身属语句层：GOTOFORM 编译为"`$名`求值 + 动态跳转"，语句执行器
     * 运行期经 [resolveLabel]/[tryResolveLabel] 把标签解析为目标 pc，再驱动 VM
     * 转移（core/code 目前无按 pc 的动态跳转指令，需与 compile agent 协商，
     * 见 TODO(verify)）。
     */
    interface LabelTable {
        /** 返回目标 pc；未定义返回 null。functionKey 与 label 均已大写归一。 */
        fun labelPc(functionKey: String, label: String): Int?
    }

    private var labelTable: LabelTable? = null

    /** 注入标签表（编译元数据落地后调用；幂等，后装覆盖）。 */
    fun installLabelTable(t: LabelTable) {
        labelTable = t
    }

    /**
     * GOTOFORM 的标签解析（不含转移）。未定义 → CodeEE。
     * spec: GOTO_Instruction.DoInstruction（form 变体）
     * FunctionIdentifier.cs:3694-3704 —— 名字运行期求值后
     * CurrentCalled.CallLabel → LabelDictionary.GetLabelDollar
     * （按**当前函数**查，LabelDictionary.cs:382-389）；未定义 → CodeEE
     * NotDefinedLabelName。
     */
    fun resolveLabel(functionKey: String, label: String): Int =
        tryResolveLabel(functionKey, label)
            ?: throw CodeEE("当前函数中不存在指定的标签名\"$label\"") // 官方 zhs 文案（emuera-zhs.xml:540；抛点 FunctionIdentifier.cs:3703）

    /**
     * TRYGOTOFORM 的标签解析：未定义 → null（语句层跳 $CATCH 或静默跳过）。
     * spec: FunctionIdentifier.cs:3699-3708（isTry：JumpToEndCatch 有则跳，无则不动作）。
     */
    fun tryResolveLabel(functionKey: String, label: String): Int? =
        labelTable?.labelPc(functionKey.uppercase(), label.uppercase())

    // ------------------------------------------------------------------
    // PRINTDATA / STRDATA 数据节（注入点，键为语句池下标）
    // ------------------------------------------------------------------

    /**
     * 数据节供给注入点 —— 契约与 sem 的 ExecContext.dataSections 对齐（`sem/ExecContext.kt`
     * 的 [DataSections] KDoc）：键为该 PRINTDATA/STRDATA 语句的**语句池下标**
     * （`Program.statement(i)` 的 i；扁平通道里就是 marker 指令的 `a` 操作数），值为
     * "DATA → StrLiteral / DATAFORM → FormStringTerm" 的表达式组列表。
     *
     * 数据源头是编译产物（erbcc 导出），由本运行时持有；语句分派在 sem 侧。
     * **适配必须两条通道都接**（本类 [execContext] 已按此接线）：扁平通道读
     * `groupsOf(index)`，对象树通道读 `groupsOf(stmt)` —— 只写
     * `DataSections { rt.dataGroupsOf(it) }` 这种单方法 lambda 会在 Android
     * （扁平关）上落到默认实现、直接抛异常（2026-10-29 真机启动失败）。未注入时语句层按
     * 契约走 no-op（`StatementExecutor.execPrintData/execStrData` 的
     * `groupsOf(...) ?: return`）。
     *
     * 侧车 `BootMetadata.dataSections` 的键本来就是**语句池下标**
     * （`core/loader/BootMetadata.kt` 的该字段 KDoc），装配点是 `GameSession`
     * （`core/proc/GameSession.kt` 的 `SidecarTables`）：波次 C 起**直查**，不再建
     * 「池下标 → 语句实例」身份表（那层要 3,874 次 `Program.statement(i)`，且把语句树
     * 钉在常驻里，与「压扁即弃」相反）。容器**不**承载这段映射（规约 §9 第 4 项已结）。
     *
     * **对象树通道例外**：扁平通道关掉时（Android 生产，`GameHost` 传
     * `flatStatements = false`）VM 走语句树老路，数据节读点是
     * `DataSections.groupsOf(stmt)`（语句实例），由 [installDataSectionStatementIndex]
     * 补一张「实例 → 池下标」反查表（全量 3,874 条）。
     */
    fun interface DataSectionSource {
        /** 语句池下标 → 数据节组；未登记（含越界下标）= null。 */
        fun groupsOf(stmtIndex: Int): List<List<Expr>>?
    }

    private var dataSectionSource: DataSectionSource? = null

    /** 注入数据节表（编译产物落地后调用；幂等，后装覆盖）。 */
    fun installDataSections(src: DataSectionSource) {
        dataSectionSource = src
    }

    /**
     * 对象树通道（扁平通道关闭）用的「数据节语句实例 → 语句池下标」反查表。
     *
     * 键只收侧车登记过的下标（全量 3,874 条），不扫整个语句池（1.7M 条）；
     * 由 [com.eratw.port.core.proc.GameSession] 在 `flatStatementsEnabled == false` 时装入。
     * 扁平通道全开时没人读它，`null` 即可。
     *
     * 为什么必须有：树通道的 `PRINTDATA`/`STRDATA` 只能按实例查（`execPrintData`/`execStrData`），
     * 而波次 C 把侧车的键换成了池下标 —— 没有反查表就只能抛异常。桌面/测试默认开扁平通道，
     * 这条缝直到真机 0.2.1 才暴露（2026-10-29 启动失败「数据节键已改语句池下标」）。
     */
    private var dataSectionStmtIndex: IdentityHashMap<Statement, Int>? = null

    /** 装反查表（仅扁平通道关闭时需要；null = 撤掉）。 */
    fun installDataSectionStatementIndex(index: IdentityHashMap<Statement, Int>?) {
        dataSectionStmtIndex = index
    }

    /** 语句层的数据节查询（未注入返回 null）。[stmtIndex] = 语句池下标。 */
    fun dataGroupsOf(stmtIndex: Int): List<List<Expr>>? = dataSectionSource?.groupsOf(stmtIndex)

    /**
     * 对象树通道的数据节查询：语句实例经 [dataSectionStmtIndex] 换成池下标再查。
     *
     * 未装反查表时**显式失败**（不静默查空：静态空会把「有数据节」与「本来没有」混成
     * 同一个 no-op，PRINTDATA 少印一段却看不出来）。未登记的语句 = 本来没有数据节 → null。
     */
    fun dataGroupsOf(stmt: Statement): List<List<Expr>>? {
        val index = dataSectionStmtIndex ?: throw UnsupportedOperationException(
            "对象树通道的数据节查询需要反查表：扁平通道关闭时须先 installDataSectionStatementIndex",
        )
        return dataGroupsOf(index[stmt] ?: return null)
    }

    // ------------------------------------------------------------------
    // BEGIN / START（游戏入口选择）
    // ------------------------------------------------------------------

    /** 原版 `Process.systemResult`（系统输入结果；`Process.cs:2052-2088`）。 */
    var systemResult: Long = 0L

    /**
     * 脚本 `QUIT` 家族（QUIT / QUIT_AND_… 等）在语句层留下的退出请求。
     * 原版脚本 QUIT 是 `ConsoleState.Quit`（`EmueraConsole.cs:731-734`），下一次
     * `IsRunning` 读数即 false（`EmueraConsole.cs:270-284`），DoScript 的内层泵
     * （`Process.cs:445-460`）随之停转 —— 转态由宿主判断，状态机不区分。
     * 本移植 QUIT 在 VM 内同步收栈、对状态机不可见，宿主泵读不到「退出」，
     * 故在语句执行处记一个粘滞位，供宿主 `GameHost.drive` 的泵停转。
     * **不含** LOADDATA 成功：那只是恰好以 [ExecResult.Quit] 表达的读档交接
     * （`StatementExecutor.kt:995`），原版语义是清函数栈继续跑，不是退出
     * （交接在 [executeStatement] 上方的 Unwind 分支截走，正常到不了 Quit 分支）。
     */
    var quitRequested: Boolean = false
        private set

    /**
     * 脚本 `BEGIN <keyword>` 写下的挂起入口（原版 `ProcessState.begintype`，
     * `ProcessState.cs:26/38/169-188`）。下一次 [begin]/[resumeSystem] 开头消费。
     */
    private var pendingBeginType: BeginType? = null

    /** 只读视图：脚本是否请求了尚未执行的入口切换（`ProcessState.isBegun`）。 */
    val pendingBegin: BeginType? get() = pendingBeginType

    /**
     * `CALLTRAIN` 指令队列（原版 `Process.isCTrain` + `coms`，`Process.cs:369-390`）。
     * BOOT-2 只提供状态位与清空语义；队列填充属 M4 训练层，故没有默认消费方。
     */
    private var callTrainActive = false
    private val callTrainCommands = ArrayDeque<Long>()

    /** 宿主/CALLTRAIN 接线：进入批量训练。 */
    fun startCallTrain(commands: LongArray) {
        callTrainActive = true
        callTrainCommands.clear()
        for (v in commands) callTrainCommands.addLast(v)
    }

    /** 宿主/CALLTRAIN 接线：退出批量训练。 */
    fun stopCallTrain() {
        callTrainActive = false
        callTrainCommands.clear()
    }

    /**
     * BEGIN 状态机（BOOT-2）。`select` = `ProcessState.Begin(BeginType)`，
     * `step` = `runSystemProc()` 的单次调用（`Process.cs:2042-2045`）。
     */
    private val beginFlow: BeginFlow = BeginFlow(
        object : BeginCallPort {
            override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean =
                callScriptFunction(name, force, isEvent)

            override fun result(): Long = store.readInt("RESULT", longArrayOf(0L))
            // Process.cs:2372 / :2506 —— `vEvaluator.RESULT = systemResult`：写脚本变量
            // RESULT:0（VariableEvaluator.cs:46-48 的 setter）。与下面 systemResult 的
            // 宿主字段是**两个槽**，原版同样分开（`Process.systemResult` vs RESULT_ARRAY[0]）。
            override fun setResult(value: Long) {
                store.writeInt("RESULT", longArrayOf(0L), value)
            }
            override fun systemResult(): Long = this@ProcessRuntime.systemResult
            override fun setSystemResult(value: Long) {
                this@ProcessRuntime.systemResult = value
            }

            override fun nextCom(): Long = store.readInt("NEXTCOM", longArrayOf(0L))
            override fun setNextCom(value: Long) {
                store.writeInt("NEXTCOM", longArrayOf(0L), value)
            }

            override fun selectCom(): Long = store.readInt("SELECTCOM", longArrayOf(0L))
            override fun setSelectCom(value: Long) {
                store.writeInt("SELECTCOM", longArrayOf(0L), value)
            }

            override fun isCTrain(): Boolean = callTrainActive

            override fun clearCommands(): Boolean {
                val was = callTrainActive
                callTrainActive = false
                callTrainCommands.clear()
                return was
            }

            /** `VariableEvaluator.UpdateInBeginTrain`（`VariableEvaluator.cs:1668-1718`）。 */
            override fun beginTrainReset() {
                store.writeInt("ASSIPLAY", longArrayOf(0L), 0L)
                store.writeInt("PREVCOM", longArrayOf(0L), -1L)
                store.writeInt("NEXTCOM", longArrayOf(0L), -1L)
                // 全局 TFLAG（`DataIntegerArray[4]`）整表清 0（:1673-1676）。
                clearGlobalInt("TFLAG")
                // 全局 TSTR（`DataStringArray[6]`）整表清空串（:1677-1680）。
                clearGlobalStr("TSTR")
                // 逐角色整数段（C# 每角色 foreach 内，列下标见 SystemLayout.charaInt1D）：
                // GOTJUEL[15]/TEQUIP[13]/EX[8]/PALAM[6]/SOURCE[7]/TCVAR[20] 清零
                // （:1681-1699、:1704-1717）。
                clearCharacterInt("GOTJUEL")
                clearCharacterInt("TEQUIP")
                clearCharacterInt("EX")
                clearCharacterInt("PALAM")
                clearCharacterInt("SOURCE")
                clearCharacterInt("TCVAR")
                // STAIN[14] ← Config.StainDefault（:1700 → setDefaultStain :1903-1918 / :1921）。
                resetCharacterStainToDefault("STAIN", configLongList(ConfigKey.StainDefault))
            }

            /**
             * `VariableEvaluator.UpdateAfterShowUsercom`（`VariableEvaluator.cs:1719-1754`）：
             * 全局 `UP`/`DOWN`/`LOSEBASE` + 每角色 `DOWNBASE`/`CUP`/`CDOWN` 清零。
             * 时机：`endCallShowUserCom`（`Process.cs:2327`）与 `doTrain`（`:2379`）。
             */
            override fun afterShowUserComReset() {
                clearGlobalInt("UP")
                clearGlobalInt("DOWN")
                clearGlobalInt("LOSEBASE")
                clearCharacterInt("DOWNBASE")
                clearCharacterInt("CUP")
                clearCharacterInt("CDOWN")
            }

            /** `VariableEvaluator.UpdateAfterInputCom`（`:1756-1766`）：每角色 `NOWEX` 清零。 */
            override fun afterInputComReset() {
                clearCharacterInt("NOWEX")
            }

            /** `VariableEvaluator.UpdateAfterSourceCheck`（`:1768-1778`）：每角色 `SOURCE` 清零。 */
            override fun afterSourceCheckReset() {
                clearCharacterInt("SOURCE")
            }

            override fun trainName(code: Int): String? {
                val len = trainNameCount() ?: return null
                if (code < 0 || code >= len) return null
                val s = runCatching { store.readStr("TRAINNAME", longArrayOf(code.toLong())) }.getOrNull()
                    ?: return null
                return s.ifEmpty { null }
            }

            /** `TrainName.Length`；CSV 未装载（变量不存在）→ null = 宿主未提供。 */
            override fun trainNameCount(): Int? = runCatching {
                val len = store.getLength("TRAINNAME", -1)
                if (len <= 0) null else len
            }.getOrNull()

            override fun pendingBegin(): BeginType? = pendingBeginType
            override fun takePendingBegin(): BeginType? =
                pendingBeginType.also { pendingBeginType = null }
            override fun clearPendingBegin() {
                pendingBeginType = null
            }
        },
        beginHost,
    )

    init {
        // 《DOTRAIN 是进程内语句，不是宿主能力》：状态门、doTrainSelectCom 与
        // Train_DoTrain 都在 [BeginFlow] 里（spec: Process.cs:1931-1954）。
        // 见 [ExecContext.doTrainHook] 的 KDoc（语料 USERCOM → DOTRAIN 999 主链）。
        execContext.doTrainHook = { no -> beginFlow.doTrain(no) }
    }

    /**
     * 系统 1 维整数数组整体清零（C# `VariableEvaluator.UpdateAfterShowUsercom` 对
     * `varData.DataIntegerArray[5]/[9]/[16]` 的 `for` 循环，`VariableEvaluator.cs:1721-1735`）。
     *
     * 长度取注册表的声明尺寸 `dims[0]`（禁用数组为 0 ⇒ 循环自然 no-op，与 C# 长度 0 的
     * 数组同形）；不复用 [VariableStore.getLength]，后者对 IsForbid 变量会抛 CodeEE。
     */
    private fun clearGlobalInt(name: String) {
        val n = store.registry.def(name)?.dims?.firstOrNull() ?: return
        val idx = longArrayOf(0)
        for (i in 0 until n) {
            idx[0] = i.toLong()
            store.writeInt(name, idx, 0L)
        }
    }

    /**
     * 角色 1 维整数**整列**清零（C# 逐角色逐元素写 0，`VariableEvaluator.cs:1736-1753`；
     * 移植侧角色表是列存，ADR-004 —— 直接按列 `fill`，语义等价且不做 N×M 次下标校验）。
     */
    private fun clearCharacterInt(name: String) {
        val def = store.registry.def(name) ?: return
        val col = store.characters.int1.getOrNull(def.index) ?: return
        for (i in 0 until store.characters.count) col.fill(i, 0L)
    }

    /**
     * 全局 1 维字符串数组整体清空串（C# `VariableEvaluator.UpdateInBeginTrain` 对
     * `varData.DataStringArray[6]` 的 `for` 循环，`VariableEvaluator.cs:1677-1680`）。
     * 长度同 [clearGlobalInt] 取注册表声明尺寸 `dims[0]`（禁用数组为 0 ⇒ 自然 no-op）。
     */
    private fun clearGlobalStr(name: String) {
        val n = store.registry.def(name)?.dims?.firstOrNull() ?: return
        val idx = longArrayOf(0)
        for (i in 0 until n) {
            idx[0] = i.toLong()
            store.writeStr(name, idx, "")
        }
    }

    /**
     * 角色 `STAIN` 整列复位为 Configuration `StainDefault`（C# `setDefaultStain`，
     * `VariableEvaluator.cs:1903-1918`；BEGIN 调用点 `:1700`）。
     *
     * C# 两条分支（`array.Length >= StainDefault.Count` 的 `CopyTo` + 补 0 / else 逐项取）
     * 语义等价于：`array[i] = i < StainDefault.Count ? StainDefault[i] : 0`——超出列表
     * 长度的高位一律 0。初始 _Replace.csv 无该行时列表为空 ⇒ 整列清 0。
     */
    private fun resetCharacterStainToDefault(name: String, defaults: List<Long>) {
        val def = store.registry.def(name) ?: return
        val col = store.characters.int1.getOrNull(def.index) ?: return
        val len = col.blockSize
        for (p in 0 until store.characters.count) {
            for (e in 0 until len) {
                col.set(p, e, defaults.getOrElse(e) { 0L })
            }
        }
    }

    /** 条目表 `List<long>` 项的现读（与 [configBool] 同一条 `store.aux` 链，不建第二份配置真相）。 */
    private fun configLongList(key: ConfigKey): List<Long> =
        ConfigMethods.store(store.aux, effectiveBuiltinHost).itemOf(key).longList()

    /** 当前系统状态（原版 `ProcessState.SystemState`）。 */
    val systemState: SystemStateCode get() = beginFlow.state

    /**
     * BEGIN 入口：按 [BeginType] 选择系统状态并执行**一步** handler
     * （等价 `Process.Begin(type)` + 一次 `runSystemProc()`，见 `Process.cs:444-470`）。
     *
     * 与原 M2 API 的语义保持一致：
     * - `true` = 入口目标函数已执行（[StepOutcome.CALLED]），**或**目标函数执行完被脚本
     *   `BEGIN` 当即带到新入口（[StepOutcome.REENTERED]，见下条）；
     * - `false` = 目标不存在且 `force=false`（宿主走默认流程，如 TITLE 缺失时画默认标题，
     *   `Process.cs:2136-2158`）；**也**包括 BOOT-2 新增的非调用结果：
     *   `BLOCKED`（宿主能力缺失）/ `CONTINUE` / `AWAIT_INPUT`——需要区分时改用 [beginStep]；
     * - `force=true` 且目标缺失 → 抛 `CodeEE`（`Process.cs:2093-2100`）。
     *
     * 后续状态（例如 SYSTEM_TITLE 里执行 `BEGIN FIRST`，TITLE.ERB:117）**不会在这里执行
     * handler**，但入口切换本身在脚本返回、函数栈空当刻就已完成（`ProcessState.cs:352-362`）：
     * 本方法返回时 [systemState] 已指向新入口（如 `First_Begin`），新状态的第一步由宿主调
     * [resumeSystem]（或再调 [begin]）。REENTERED 只可能来自本次调用内的脚本返回
     * （[beginStep] 的 `select` 先清掉了旧 pending），故也算「目标已执行」。
     */
    fun begin(type: BeginType): Boolean {
        val outcome = beginStep(type).outcome
        return outcome == StepOutcome.CALLED || outcome == StepOutcome.REENTERED
    }

    /** [begin] 的诊断版本：返回完整 [StateStep]（含阻塞点五元组）。 */
    fun beginStep(type: BeginType): StateStep {
        // 读档待办优先于宿主给的入口选择：原版 `LOADDATA` 之后不会走 `Begin(type)`
        // —— 函数栈已空（Process.cs:1860），DoScript 的外层循环直接 `runSystemProc()`
        // （Process.cs:445-452），而 `state.SystemState` 已是 LoadData_DataLoaded（`:1861`）。
        adoptHandoffState()?.let { return it }
        beginFlow.select(type)
        return beginFlow.step()
    }

    /**
     * 继续执行当前系统状态的下一 handler（`runSystemProc()`）。
     *
     * 宿主驱动约定：脚本函数返回后调用一次；本方法**一步一停**，不会循环。
     */
    fun resumeSystem(): StateStep {
        adoptHandoffState()?.let { return it }
        return beginFlow.step()
    }

    /**
     * 消费 [SaveLoadHandoff] 的系统状态位（`Process.cs:1861` / `FunctionIdentifier.cs:2979`）
     * 并**跳过本步 handler**。
     *
     * 原版把「置状态」写在语句里、把「跑 handler」留给下一次 `runSystemProc()`
     * （`Process.cs:445-452` + `:2042-2045`）；移植侧语句层够不到 `BeginFlow.state`，
     * 两半分别在 [executeStatement]（整栈丢弃）与这里（置状态）落。本步不调任何脚本，
     * 只在下一步由 `BeginFlow.step()` 进对应 handler（`beginDataLoaded` `:2660-2667`；
     * `beginSaveGame` / `beginLoadGame` `:2685-2697` —— M11-E saveflow 已接管，
     * 见 `BeginFlow.stepSaveOrLoadGameBegin`；其后的 `SaveGame_WaitInput` /
     * `LoadGame_WaitInput` 存档 UI handler 未接管 ⇒「未接管」阻塞步，交组长接线）。
     *
     * @return null = 没有待办（读档没发生过，或状态位已被消费）
     */
    private fun adoptHandoffState(): StateStep? {
        val code = SaveLoadHandoff.of(store)?.takeSystemState() ?: return null
        val from = beginFlow.enterState(code)
        return StateStep(
            from = from,
            handler = HANDOFF_HANDLER_NAMES[code] ?: LOAD_DATA_HANDOFF_HANDLER,
            to = code,
            outcome = StepOutcome.CONTINUE,
            calledFunction = null,
            blocker = null,
            evidence = HANDOFF_EVIDENCE[code] ?: "Process.cs:445-452 / Process.cs:1860-1861",
        )
    }

    // ------------------------------------------------------------------
    // 回标题（GOTOTITLE-CHANNEL：原版错误屏后的进程内恢复通道）
    // ------------------------------------------------------------------

    /**
     * 回到标题（进程内恢复通道，非重启进程）—— 逐句复刻原版
     * `EmueraConsole.GotoTitle()`（`MinorShift.Emuera.GameView/EmueraConsole.cs:2569-2581`），
     * 语句顺序与该方法的九条一一对应：
     *
     * | 原版（`EmueraConsole.cs`） | 本方法 |
     * |---|---|
     * | `:2572 ClearDisplay()` | [clearDisplayForGotoTitle]（显示行 + 逻辑行计数 + HTML 浮岛） |
     * | `:2573 AppContents.UnloadGraphicList()` | [unloadGraphicListForGotoTitle]（`AppContents.cs:196-203`） |
     * | `:2574 redraw = ConsoleRedraw.Normal` | `setRedraw(1)`（`ConsoleRedraw.cs:6-10` 的枚举序 None=0/Normal=1；`EmueraConsole.cs:1693-1707` 的折减只由 bit0 决定，bit1 的「立即重绘」是副作用，原版这里直接赋状态 ⇒ 传 1） |
     * | `:2575-2576 UseUserStyle = false` + `userStyle = new StringStyle(Config.ForeColor, FontStyle.Regular, null)` | `setUseUserStyle(false)` + `resetStyle()`（逐句对应：前者是「此后打印一律走 `defaultStyle`」的粘滞开关，见 `ConsoleSink.setUseUserStyle` 的 KDoc；后者是 `EmueraConsole.cs:2838-2842` 的四件样式复位，见 `ConsoleSink.resetStyle` 的 KDoc） |
     * | `:2577 process.BeginTitle()` | `store.resetData()` + [BeginFlow.resetToBootState] + `beginFlow.select(BeginType.TITLE)`（`Process.cs:480-485` = `vEvaluator.ResetData()` + `state = originalState` + `state.Begin(TITLE)`；`VariableStore.resetData` 对应 `VariableEvaluator.cs:1336-1346`） |
     * | `:2578 ReadAnyKey()` | 无对应动作：它只是把控制台置 `ConsoleState.WaitInput`（`EmueraConsole.cs:876-895`，写点 `:893`）——移植侧的「等输入」是宿主阻塞式的，这条门控解除落在 [BeginFlow.resetToBootState] 清掉的终局粘滞位上（证据链见 `BeginFlow.terminalEndNormalStep` 的 KDoc） |
     * | `:2579 RunEmueraProgram("")` | 本方法末尾的一次 `beginFlow.step()`（`RunEmueraProgram` → `process.DoScript()`（`EmueraConsole.cs:1092-1110`）→ 外层 `while (ScriptEnd) runSystemProc()`（`Process.cs:445-452` + `:2042-2045`）⇒ 恰好等价于**一步**系统 handler，返回那一步的 [StateStep]） |
     * | `:2580 RefreshStrings(force_Paint: true)` | `publishFrame()`（`ConsoleSink.publishFrame` 的 KDoc：原版 `force_Paint` 的强制上屏就是这条帧闸语义） |
     *
     * 返回的一步就是重跑的**第一个**标题 handler（正常语料下 `handler = beginTitle` +
     * `calledFunction = SYSTEM_TITLE`，[StepOutcome.CALLED]），宿主照常继续泵 [resumeSystem]
     * （原版 `DoScript` 之后每次回车都只泵一步，`Process.cs:445-452`）。
     *
     * ## 入口闸（原版「タイトルへ戻る」的两道拒绝）
     *
     * `MainWindow.cs:1079-1104` 的菜单分支在 `console.IsInProcess`（脚本正在跑）与
     * `console.notToTitle` 时拒绝，后者还按 `byError` 分两种文案；`byError` 只由
     * `ThrowTitleError` 置位（`EmueraConsole.cs:750-755`）= **装载/标题级**硬失败，
     * 普通游戏中错误（`ThrowError`，`:757-768`）**不**置它，因而照常允许回标题
     * （`MainWindow.cs:1028-1034` 的 `GotoTitle()` → `console.GotoTitle()` 这一跳没有任何判定）。
     * 「脚本正在跑」与「notToTitle」在移植侧各对应 [inRun] 与「装载期硬失败」两个宿主事实，
     * 都不属于 core-vm 自己判得出的量 ⇒ **本方法不做拒绝判定**，由宿主（应用层按钮 / Ctrl+Z
     * 入口）照 `MainWindow.cs:1079-1104` 的口径把关；这里只保证「被调用即按原版顺序复位」。
     *
     * ## 移植侧的两条额外动作（都有出处，非顺手扩面）
     *
     * 1. [quitRequested] 归 false：该位是移植侧对「脚本 `QUIT` → `ConsoleState.Quit`
     *    （`EmueraConsole.cs:731-734`）⇒ `IsRunning` 转假（`:270-284`）⇒ DoScript 外层泵停转
     *    （`Process.cs:445-460`）」的建模（本字段 KDoc `:1367-1379`）。原版 `ReadAnyKey()`
     *    把 `state` **无条件**写成 `ConsoleState.WaitInput`（`:893`），Quit 态随之被覆盖 ⇒
     *    泵停条件消失。不清它，宿主泵（`app/.../GameHost.kt:784` 的
     *    `while (!closed && !runtime.quitRequested)`）会在回标题后立刻停转，通道失效。
     * 2. `SaveLoadHandoff.reset()`：原版 `LOADDATA` 把待进入状态写在 `ProcessState.sysStateCode`
     *    上（`Process.cs:1861`），`Begin(TITLE)` 的 `sysStateCode = Title_Begin`
     *    （`ProcessState.cs:276`）当场把它**覆盖掉**；移植侧那份挂在 aux 槽上，不在
     *    [BeginFlow] 里（见 `host/SaveLoadHandoff.kt` 的「存什么、谁消费」），不显式清就会
     *    被宿主下一次 [resumeSystem] 的 [adoptHandoffState] 截走、把标题流程拐进读档链。
     *
     * ## Ctrl+Z **不是**本方法
     *
     * 菜单「タイトルへ戻る」走 `GotoTitle()` = 本方法；Ctrl+Z 走
     * `GotoTitleAndLoadAndRepeatInput()`（`MainWindow.cs:532-536` →
     * `EmueraConsole.cs:2583-...`），它的 `:2603-2612` 十条与本方法逐字同形，但之后还要
     * `ctrlZ.mRewindInProgress = true`（`:2613`）+ `Rand.SetRand(mRandomSeed)`（`:2614`）+
     * `Process.LoadSilent()`（`:2615`）+ 逐条重放 `mInputs`（`:2616` 起）—— 那是「回滚到最近
     * 一次自动存档再重放输入」的 rewinder 簇，不在恢复通道契约内。宿主若要给 Ctrl+Z 复用
     * 本方法，得另建 rewinder API（`TODO(verify)`：rewinder 需要 RNG 种子快照与静默读档，
     * 卡点是 `core/rng` 的 SetRand 面与 `LoadSilent` 都还没接）。
     *
     * ## 刻意**不做**的（逐条有出处，铁律「不顺手修好」）
     *
     * - **不停音频**：`GotoTitle` 的第一句 `forceStopTimer()`（`EmueraConsole.cs:2571` →
     *   方法体 `:1084-1091`）只把 `genericTimer.Enabled` 置 false —— 那是**定时输入**的倒计时
     *   定时器（`System.Timers.Timer`，声明 `:130`、构造 `:550-553`、`presetTimer` 里 `:998`
     *   开启），与音频无关（音频只在错误提示里被**播**一次：`ThrowError(playSound)` 的
     *   `SystemSounds.Hand.Play()`，`:757-768`）。移植侧没有 `genericTimer`（输入等待是宿主
     *   阻塞式的，`InputSink` 无倒计时线程），这一步在 core-vm **没有落点**；
     *   所以 `audio.channels`（[SoundChannelTable]）的 `closeAll()` 不调。
     * - **不清 `keytoggle`**：原版它是进程级 `private static readonly short[]`
     *   （声明 `FunctionMethodCreator.cs:9035`、唯一分配点在静态构造 `:9039` 的 `new short[256]`；
     *   此后只有 GETKEY/GETKEYTRIGGERED 的取值处按项改写），`GotoTitle` 的九条语句没有任何一条
     *   碰它 ⇒ 陈旧按键沿与原版同形（官方行为，ADR-005 口径）。
     *   移植侧它挂在 aux 槽 `keytoggle`（`fn/InputMethods.kt:169`），**同 VM 实例**内跨本方法
     *   存活，正好等于原版的进程级存活；真要「新开局清空」得由换 VM 的通道做
     *   （`RuntimeAux.clear()` 会把 `imageDecoder` / `save.wiring` / `config.state` 一起抹掉，
     *   本方法绝不调它）。
     * - **不清 CbgPool**：`GotoTitle` 与它调的 `ClearDisplay` 都不碰 `cbgList` /
     *   `cbgButtonMap`（`EmueraConsole.cs:2569-2581` + `:2801-2813`），原版回到标题后背景层
     *   仍在表里，只是其引用的 `GraphicsImage` 已被 `UnloadGraphicList` 释放而画不出东西。
     * - **不整表卸 sprite**：`UnloadGraphicList` 只遍历 `gList`（`AppContents.cs:196-203`），
     *   `imageDictionary` 不动 ⇒ sprite **登记项保留**，只是 base 图变未创建
     *   （`SpriteG.IsCreated => BaseImage.IsCreated`，`ASpriteSingle.cs:24-34`）；
     *   动画 sprite 的 `IsCreated` 原版恒 true（`SpriteAnime.cs:54`），卸掉它会读成 0，属偏离。
     */
    fun gotoTitle(): StateStep {
        // EmueraConsole.cs:2572 ClearDisplay()
        clearDisplayForGotoTitle()
        // EmueraConsole.cs:2573 AppContents.UnloadGraphicList()
        unloadGraphicListForGotoTitle()
        // EmueraConsole.cs:2574 redraw = ConsoleRedraw.Normal
        consoleBridge.setRedraw(REDRAW_NORMAL)
        // EmueraConsole.cs:2575-2576 UseUserStyle = false + userStyle = 出厂 StringStyle
        // 两条语句分开写，与原文逐句对应：:2575 是「此后打印一律走 defaultStyle」的粘滞开关
        // （ConsoleSink.setUseUserStyle 的 KDoc），:2576 才是四件样式字段的复位。
        // 顺序不能反 —— 先复位 userStyle 再关开关在终态上同值，但关开关本身要独立成句，
        // 否则「GotoTitle 之后系统行不吃用户样式」这条语义会被当成 resetStyle 的副作用。
        consoleBridge.setUseUserStyle(false)
        consoleBridge.resetStyle()
        // Process.cs:480-485 BeginTitle() 的变量池半边（VariableEvaluator.cs:1336-1346）。
        // ResetData 第一步是 RemoveEMSaveData（:1338 → VariableData.cs:4006-4029）：回标题同样
        // 要清上一局 SAVE 命名 MAP/XML/DT，否则旧局扩展池残留（2026-10-26 fixcore 审查 B1，
        // 与 RESETDATA 同修；此前只有 LOADDATA 接了这一步）。
        SaveWiring.of(store)?.emSets?.let { em -> EmDataPools.clear(store, em.save) }
        store.resetData()
        // 移植侧闩锁，逐条理由见方法 KDoc 的「额外动作」
        quitRequested = false
        SaveLoadHandoff.of(store)?.reset()
        // EmueraConsole.cs:2577 process.BeginTitle() 的状态机半边：清终局粘滞位 + 输入闩锁，
        // 然后 Begin(TITLE)（BeginFlow.resetToBootState 的 KDoc 说明为何原版
        // `state = originalState` 那一句不必移植）
        beginFlow.resetToBootState()
        beginFlow.select(BeginType.TITLE)
        // EmueraConsole.cs:2578-2579 ReadAnyKey() + RunEmueraProgram("") —— 一步系统 handler
        val step = beginFlow.step()
        // EmueraConsole.cs:2580 RefreshStrings(force_Paint: true)
        consoleBridge.publishFrame()
        return step
    }

    /**
     * `ClearDisplay()`（`EmueraConsole.cs:2801-2813`）在移植侧的等价终态。
     *
     * 原版四件事：`CBProc.ClearScreen()`（`:2803`）、`displayLineList.Clear()`（`:2804`）、
     * `_htmlElementList.Clear()`（`:2805`）、`ConsoleEscapedParts.Clear()` + 三个计数器复位
     * （`:2806-2810`）+ `window.Refresh()`（`:2812`）。[ConsoleSink] 目前**没有** ClearDisplay
     * 这一面（`clearText()` 对应的是 `CLEARTEXTBOX` = `EmueraConsole.ClearText`，
     * `:3355-3358`，清输入框而不是显示区，且 `LineBufferConsoleSink` 是空实现），所以这里用
     * 既有语句面拼出**同一终态**而不是新造 API：
     * - 显示行 + 逻辑行计数：`deleteLine(lineCount)`。`LineBufferConsoleSink.deleteLine`
     *   从尾部按逻辑行摘除、摘不满时把差额直接扣在 `logicalLineCount` 上
     *   （`host/LineBufferConsoleSink.kt:688-711`，对应 `EmueraConsole.cs:2999-3051` 的
     *   `:3033-3037`），因此传「当前 LINECOUNT」必然把显示行清空并把计数打到 **0** ——
     *   正是 `displayLineList.Clear()`（`:2804`）+ `logicalLineCount = 0L`（`:2807`）两条不变量。
     *   计数不会小于现存逻辑行：超出 `maxLines` 的头部淘汰（`:1616-1617`）不回退计数
     *   （原版 `displayLineList` 淘汰同样走 `deletedLines`，`EmueraConsole.cs:2808`）。
     * - HTML 浮岛：`clearHtmlIslands()` ↔ `_htmlElementList.Clear()`（`:2805`）。
     * - `window.Refresh()`（`:2812`）：由 [gotoTitle] 末尾的 `publishFrame()` 承担
     *   （原版这条与 `RefreshStrings(force_Paint: true)`（`:2580`）是同一个动作的两次触发）。
     * - TODO(verify)：`CBProc.ClearScreen()`（剪贴板处理器）与 `ConsoleEscapedParts.Clear()`
     *   （转义片段表）在移植侧无对应面（两块能力都未落地）⇒ 卡点是这两张表本身还没有宿主，
     *   已作为「建议 ConsoleSink 新增 `clearDisplay()`」写进交付报告的组长接线项。
     */
    private fun clearDisplayForGotoTitle() {
        val logical = consoleBridge.lineCount
        if (logical > 0) consoleBridge.deleteLine(logical.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        consoleBridge.clearHtmlIslands()
    }

    /**
     * `AppContents.UnloadGraphicList()`（`AppContents.cs:196-203`：逐个 `GDispose` 再
     * `gList.Clear()`）在移植侧的等价实现。
     *
     * `GraphicsPool` 只开了 `get`/`peek`/`clear` 三个口（`host/GraphicsBuffer.kt:495-505`），
     * **没有**遍历面，所以「逐个 GDispose」这一半改从**可达引用**那侧做：原版里还握着
     * `GraphicsImage` 实例引用的运行时对象就是 sprite（`SpriteG` 的 `BaseImage`、
     * `SpriteAnime.AnimeFrame.BaseImage`，`SpriteG.cs:8-27` / `SpriteAnime.cs:65-77`），
     * 移植侧同构（`SpritePool.Sprite.buffer` 与 `SpritePool.Frame.buffer` 就是池里同一个
     * GraphicsBuffer 实例，见 `fn/GraphicsMethods.kt` 的 SPRITECREATE / SPRITEANIMEADDFRAME
     * 都取 `gfx().get(gid)`）。于是：
     * 1. 遍历 sprite 池，把每个 sprite 的基图与每个动画帧的基图就地调用
     *    `GraphicsBuffer.dispose()`
     *    —— 效果与原版一致：静态 sprite 的 `isCreated` 转 false（`ASpriteSingle.cs:24-34`），
     *    动画 sprite 的 `isCreated` 保持 true（`SpriteAnime.cs:54`），而**登记项不删**
     *    （原版不动 `imageDictionary`，`AppContents.cs:196-203`）。
     * 2. `GraphicsPool.clear()` ↔ `gList.Clear()`：此后按编号取到的都是未创建的空条目
     *    （`AppContents.GetGraphics` 的惰性建条目语义，`AppContents.cs:28-37` ↔
     *    `GraphicsPool.get`，`GraphicsBuffer.kt:499`）。
     *
     * 与原版的一处**已知残差**：只被 gList 持有、不被任何 sprite 引用的缓冲，移植侧只丢引用
     * 不显式 dispose。纯 JVM 像素数组无 GDI+ 位图句柄要释放，GC 回收即等价；若将来渲染层
     * 另立了缓冲引用表，这条要改成在 `GraphicsPool` 上加遍历/disposeAll 的口子
     * （他人文件，已写进交付报告「需组长接线」）。
     */
    private fun unloadGraphicListForGotoTitle() {
        val aux = store.aux
        aux.peek<SpritePool>("sprite")?.let { pool ->
            for (name in pool.names()) {
                val sprite = pool.find(name) ?: continue
                sprite.buffer?.dispose()
                sprite.frames?.forEach { frame -> frame.buffer.dispose() }
            }
        }
        aux.peek<GraphicsPool>("gfx")?.clear()
    }

    /**
     * BEGIN 的目标函数调用（原 `begin` 的调用体，逐字保留）：
     * 事件派发 / 非事件 `doCall` / 事件名与方法名的拒绝语义。
     *
     * 非事件分支与原版 `Process.callFunction(name, force, isEvent=false)`
     * （Process.cs:2090-2099 → CalledFunction.cs:87-115）同路：`CompatiCallEvent` 为真时
     * 事件名在非事件表里（LabelDictionary.cs:181-184）⇒ 直接当普通函数调用，
     * 与 `force` 无关（目标确实存在）；为假时抛 CallToEventFunc。
     */
    private fun callScriptFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
        if (isEvent) {
            if (table.eventCallOrder(name) == null) {
                if (force) throw CodeEE(funcIsNotFoundMes(name))
                return false
            }
            return executeEvent(name)
        }
        firstEventMember(name)?.let { member ->
            if (!compatiCallEvent()) throw eventCallError(name)
            if (member.isMethod) throw methodCallError(member)
            doCall(member, LongArray(0), emptyArray())
            return true
        }
        return when (table.classify(name)) {
            FunctionTable.TargetKind.NOT_FOUND -> {
                if (force) throw CodeEE(funcIsNotFoundMes(name))
                false
            }
            FunctionTable.TargetKind.EVENT -> throw eventCallError(name)
            FunctionTable.TargetKind.NON_EVENT -> {
                val meta = table.nonEvent(name)!!
                if (meta.isMethod) throw methodCallError(meta)
                doCall(meta, LongArray(0), emptyArray())
                true
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private companion object {
        const val METHOD_STACK_LIMIT = 100

        /**
         * [refCallScratchPool] 的容量上限（防御性，正常语料远达不到）。
         *
         * 取 64：一个格只占两张 `ArrayList`（空表 + 数组），64 格 ≈ 几十 KB 常驻；
         * 而表达式位方法调用的实际嵌套深度在语料里是个位数。
         */
        const val REF_CALL_SCRATCH_POOL_MAX = 64

        /** [RootBind] 惰性桶的空占位（共享实例，永不被写：只在 `build()` 之前读到）。 */
        val EMPTY_LONGS = LongArray(0)

        /** [RootBind] 惰性字符串桶的空占位。 */
        val EMPTY_STRS = arrayOfNulls<String>(0)

        /**
         * 用户方法调用栈溢出原文 —— `Lang.Error.OverflowFuncStack`
         * （spec: Process.cs:517-520；zhs 表 `lang.emuera-zhs.xml:689`）。
         * 原版**只有这一处**用它（`Process.GetValue(SuperUserDefinedMethodTerm)`），
         * 旧实现自造串「函数方法调用栈溢出(100)」缺了「是否发生了无限递归调用？」这段
         * 排障指引，2026-10-26 改钉。
         */
        const val OVERFLOW_FUNC_STACK_MES = "函数调用栈溢出(是否发生了无限递归调用？)"

        const val ARG_VAR = "ARG"
        const val ARGS_VAR = "ARGS"

        /** 角色 0 维字符串列名（`SystemLayout.charaStr0D` 的 0/1 位，spec: CharacterData.cs:131-134）。 */
        const val NAME_VAR = "NAME"
        const val CALLNAME_VAR = "CALLNAME"

        /** [adoptHandoffState] 的 [StateStep.handler] 标记（不是 C# 方法名，是移植侧步骤名）。 */
        const val LOAD_DATA_HANDOFF_HANDLER = "(loaddata handoff)"

        /**
         * [adoptHandoffState] 的步骤名覆盖：值是原版 `systemProcessDictionary` 里的 handler
         * 方法名（`Process.cs:2023` / `:2027` 注册，方法体 `:2685-2697`）。
         */
        val HANDOFF_HANDLER_NAMES: Map<SystemStateCode, String> = mapOf(
            SystemStateCode.SaveGame_Begin to "beginSaveGame",
            SystemStateCode.LoadGame_Begin to "beginLoadGame",
        )

        /** [adoptHandoffState] 的证据行覆盖（语句里置状态的原版出处 + 该状态的 handler）。 */
        val HANDOFF_EVIDENCE: Map<SystemStateCode, String> = mapOf(
            SystemStateCode.SaveGame_Begin to
                "FunctionIdentifier.cs:2978-2979 / ProcessState.cs:190-195 / Process.cs:2685-2690",
            SystemStateCode.LoadGame_Begin to
                "FunctionIdentifier.cs:2978-2979 / ProcessState.cs:196-199 / Process.cs:2692-2697",
        )

        /**
         * 三条「语句内改 state 并把脚本栈作废」的语句：`LOADDATA`（`Process.cs:1860`）与
         * `SAVEGAME` / `LOADGAME`（`FunctionIdentifier.cs:2978`，换 `Clone()` 出的空 functionList
         * 实例，`ProcessState.cs:475-484`）。只有前者清挂起 BEGIN（`:216`）。
         */
        fun isSaveLoadHandoffCode(code: FunctionCode): Boolean =
            code == FunctionCode.LOADDATA ||
                code == FunctionCode.SAVEGAME ||
                code == FunctionCode.LOADGAME

        /**
         * `ConsoleRedraw.Normal`（`ConsoleRedraw.cs:6-10` 的枚举序 None=0 / Normal=1），
         * 即 `setRedraw` 折减后 bit0=1 的那个值（`EmueraConsole.cs:1693-1707`）。
         * [gotoTitle] 用它复刻 `EmueraConsole.cs:2574` 的 `redraw = ConsoleRedraw.Normal`：
         * 原版那里是**直接赋状态**，不走 `SetRedraw` ⇒ bit1（立即重绘）不该带上，传 1 而不是 3。
         */
        const val REDRAW_NORMAL = 1
    }
}

/**
 * 用户函数宿主面补丁：只补 [BuiltinHost.userFunctions] / [BuiltinHost.invokeUserFunction]
 * 两项，其余成员原样委托（[BuiltinHost] 的默认实现会转发给 delegate，见 ConsoleBuiltinHost
 * 类 KDoc 的委托陷阱）。
 *
 * 宿主**显式**给出的两项优先：E2E 装置（GoldenPipeline）按符号表自建表并延迟绑定
 * [ProcessRuntime.invokeUserMethod]，不能被本包装层的投影顶掉；生产宿主两项为 null
 * （接口缺省）时回落到 [functions] 与 [invoke]。
 *
 * @param functions 名字（大写）→ 种类的**惰性**来源，见 [ProcessRuntime.userFunctionKinds]
 * @param invoke GETMETH/GETMETHS 的执行通道（[ProcessRuntime.invokeUserMethod]）
 */
private open class UserFunctionWiring(
    private val delegate: BuiltinHost,
    private val functions: () -> Map<String, UserFunctionKind>,
    private val invoke: (String, List<Value?>) -> Value?,
) : BuiltinHost by delegate, GlyphMaskHostProvider {
    override val userFunctions: Map<String, UserFunctionKind> get() = delegate.userFunctions ?: functions()

    override val invokeUserFunction: ((String, List<Value?>) -> Value?)? get() =
        delegate.invokeUserFunction ?: invoke

    /**
     * 保留**可选能力面** [GlyphMaskHostProvider]：`GraphicsMethods` 用
     * `c.host as? GlyphMaskHostProvider` 取真字形通道（GraphicsMethods.kt:510）。本包装层是
     * [ProcessRuntime.effectiveBuiltinHost] 的最外层，`BuiltinHost by delegate` 只委托
     * BuiltinHost 自身成员，别的接口面会被丢掉 —— 不显式转发则生产运行时该探测恒落空、
     * `GDRAWTEXT` 永久退化推进盒（与 AuxMacroHost 同类，见 Builtins.kt:870-876）。
     */
    override val glyphMaskHost: GlyphMaskHost?
        get() = (delegate as? GlyphMaskHostProvider)?.glyphMaskHost
}

/**
 * 同 [UserFunctionWiring]，但**保留 [ConfigChainSource] 这一面**：
 * `ConfigMethods.storeFromHost` 用 `host as? ConfigChainSource` 选优先通道
 * （三层链 + 固定层钉位，ConfigMethods.kt:182-191）。生产宿主
 * [com.eratw.port.core.proc.ConsoleBuiltinHost] 实现了该接口，包装后不能把类型信息丢掉。
 */
private class UserFunctionWiringOnChain(
    private val chainSource: ConfigChainSource,
    functions: () -> Map<String, UserFunctionKind>,
    invoke: (String, List<Value?>) -> Value?,
) : UserFunctionWiring(chainSource as BuiltinHost, functions, invoke),
    ConfigChainSource by chainSource

/** 装配点：按宿主是否带配置链面选包装类型。 */
private fun userFunctionWiring(
    delegate: BuiltinHost,
    functions: () -> Map<String, UserFunctionKind>,
    invoke: (String, List<Value?>) -> Value?,
): BuiltinHost = if (delegate is ConfigChainSource) {
    UserFunctionWiringOnChain(delegate, functions, invoke)
} else {
    UserFunctionWiring(delegate, functions, invoke)
}

/**
 * 转义复原（PRINTFORMS 二段展开的前置步骤）：`\\`→`\\`、`\%`/`\@`/`\{`/`\}` 保留反斜杠，
 * 其余 `\x` 变为 `\\x`，保证再解析时不被当作 FORM 记号。spec: ExpressionMediator.cs:92-128
 */
private fun checkEscape(str: String): String = buildString {
    var i = 0
    while (i < str.length) {
        val c = str[i]
        if (c != '\\') { append(c); i++; continue }
        if (i + 1 >= str.length) { append(c); break }
        when (val n = str[i + 1]) {
            '\\' -> append("\\\\")
            '%', '@', '{', '}' -> append('\\').append(n)
            else -> append("\\\\").append(n)
        }
        i += 2
    }
}
