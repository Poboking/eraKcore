package com.eratw.port.core.code

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableHandles
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.expr.VariableWriter
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.sem.FlatStmtSource

/**
 * VM 运行期配置。
 *
 * [maxStepsPerRun] 是**死循环保护**：一次 [VirtualMachine.run] 中「相邻两次让出之间」
 * 最多执行多少条指令（段落计数；跨过 [VmHost.yieldBoundaryCount] 的一次边界后从 0 重算）。
 * 单线程 VM 一旦死循环就是整机无响应，必须有硬闸；但原版没有「每 run 指令上限」这种东西
 * ——DoScript 外层 while 的边界是 `console.IsRunning`（spec: Process.cs:438-458，:449/:453），
 * 要输入就把控制权还给控制台；原版的死循环提示是**按墙钟**弹框、用户选继续即清零重跑
 * （spec: Process.cs:493-512 checkInfiniteLoop，:496 时限 / :509 清零）。本移植的输入等待是
 * 阻塞式（一次 run 跨几十上百次输入），指令在整局上累加必撞闸 ⇒ 计数边界挪到让出点上。
 * 让出点两类（M10-RUNB 定输入、M10-AWAIT 补 `AWAIT`）：**输入等待返回**，以及
 * `AWAIT` 的逐帧让出 —— 后者对应原版把 `UpdateCheckInfiniteLoopState()`
 * （`Process.cs:487-491`）唯一地放在 `EmueraConsole.Await` 里调用（`EmueraConsole.cs:849`）；
 * 不补这一条，`@BADAPPLE` 那种「6573 帧全靠 `AWAIT` 让出」的动画会在一次 run 内攒满段落计数。
 * 数值与阈值不变；**不跨越任何让出点**的死循环仍在同一阈值抛 [StepLimitExceeded]。
 *
 * 默认 100 万步是人为约定的保守值；真实游戏"一帧"可能远大于此（ADR-002 提到
 * 2.6M 行的量级）。生产路径由宿主按"帧预算"设置（例如 5M），见 TODO(verify)。
 */
data class VmConfig(
    val maxStepsPerRun: Long = 1_000_000L,
    val maxCallDepth: Int = 10_000,
    /**
     * **单段落墙钟上限**（毫秒，段落口径同 [maxStepsPerRun]）；0（缺省）= 不限。
     *
     * 存在的理由只有一个：原版的死循环保护是**时间**，不是步数。
     * `Process.cs:493-512` 的 `checkInfiniteLoop` 每 10000 行读一次 `startTime.ElapsedMilliseconds`，
     * 超过 `Config.InfiniteLoopAlertTime`（`ConfigData.cs:75` 注册缺省 5000 ms，语料
     * `emuera.config` 未覆盖）就弹框问「是否中止」；玩家点「否」⇒ `state.lineCount = 0` +
     * `startTime.Restart()`（:509-510）继续。**原版从不因「跑了多少条指令」中止脚本。**
     *
     * 移植层原先只有步数闸，等于把「卡很久」判成「死循环」：真机读 9/21 第 47 日旧档的
     * 恢复演出（`EVENTTRAIN → CHARA_MOVEMENT → GMF_METHOD_UPDATE`，合法 O(CHARANUM) 扫描）
     * 顶到 20,000,000 步就弹「启动失败」（BOARD 2026-10-27）。步数闸换不出「原版允许继续」，
     * 所以生产改由本闸当主判据（[com.eratw.port.core.proc.GameSession.DEFAULT_MAX_RUN_MILLIS]），
     * 步数闸留给装置/测试做确定性复现（缺省 0 = 不启用，装置行为零变化）。
     *
     * 检查点每 65536 步做一次（`System.nanoTime` 是系统调用，不能每条指令都读）：
     * 在 500 ns/指令 的机器上约 33 ms 一次，对秒级阈值足够细。
     */
    val maxRunMillis: Long = 0L,
)

/**
 * 语句层执行结果（契约 C4）。
 *
 * VM 主循环对 `op in 29..197` 的真实 ERB 语句只认这四种结局：
 * * [Normal] —— 语句执行完，顺序走下一条指令（绝大多数语句、结构语句的"本行无动作"）。
 * * [Transfer] —— **同函数内**的绝对跳转。原版对应 `ProcessState.JumpTo(同函数内的行)`，
 *   本移植里由 GOTO/TRYGOTO 的语句实现、以及 TRYC 系"失败跳 ENDCATCH"
 *   （spec: FunctionIdentifier.cs:3583-3586 `state.JumpTo(func.JumpToEndCatch)`）产生；
 *   VM 必须校验目标落在当前函数的 pc 区间内（跨函数跳 = 语句层 bug，直接 CodeEE），
 *   否则会破坏帧模型（返回点指向别的函数体）。
 * * [Stop] —— 本帧到此结束（QUIT / BEGIN / FORCE_QUIT / 根级停止），原版的
 *   `ProcessState` 状态切换（spec: FunctionIdentifier.cs 的 QUIT_/BEGIN_ 分支）
 *   本轮不实现状态机（契约 C6：`ExecResult.Quit/Begin → Stop(0)`），
 *   VM 只按 `_RET` 同款路径拆帧并把 [value] 交回调用方。
 * * [Unwind] —— **整栈丢弃**（`ProcessState.ClearFunctionList()`，ProcessState.cs:202-217），
 *   产生方只有 `LOADDATA` 成功（Process.cs:1860-1862）：不是拆一帧，而是结束整次 run。
 */
sealed class StmtOutcome {
    /** 顺序执行下一条。 */
    object Normal : StmtOutcome()

    /** 同函数内绝对跳转（GOTOFORM/JumpToEndCatch）。 */
    data class Transfer(val pc: Int) : StmtOutcome()

    /** 结束本次帧执行（QUIT / BEGIN / 根级停止）。 */
    data class Stop(val value: Long) : StmtOutcome()

    /**
     * **整栈丢弃**（`ProcessState.ClearFunctionList()`，ProcessState.cs:202-217）：当前帧连同
     * 全部调用者一起作废，[VirtualMachine.run] 立即结束，不逐帧 `_RET`、不写任何返回槽。
     *
     * 与 [Stop] 的差别：`Stop` 在 `depth > 0` 时按 `_RET` 同一条路径拆**一帧**，把值写回
     * 调用方并继续执行调用方的下一条指令 —— 那正是 `LOADDATA` 的错语义（原版清空函数栈后
     * `runScriptProc` 直接返回，Process.cs:445-452 的外层循环转去 `runSystemProc`）。
     * 帧的作用域仍由 [VirtualMachine.run] 的 finally 成对退出（enter/exit 不泄漏）；
     * 帧槽数组本身不擦除，下一次 `run` 重置 depth/入口帧。
     */
    data class Unwind(val value: Long) : StmtOutcome()
}

/**
 * 传给语句层的运行期环境（core/code 定义，proc 层实现/消费 —— 契约 C4）。
 *
 * 实现是 VM 内部的一个**可复用可变对象**：每执行一条语句刷新一次字段。
 * 宿主（sem/proc 层）**不得跨语句持有本实例**（会读到被后续语句改写过的值），
 * 需要留存就自己复制四个字段。这样 2.6M 行/帧的规模下每条语句零分配（ADR-002 性能副产品）。
 */
interface StmtEnv {
    /** 当前函数大写键（= `FuncMeta.key` 口径，VariableStore 作用域键）。 */
    val functionKey: String

    /** 当前语句指令的 pc。 */
    val pc: Int

    /** 当前语句的源码行号（-1 = 未知）。 */
    val line: Int

    /** 当前函数书写名（报错用）。 */
    val functionName: String?
}

/**
 * VM 与宿主的边界：文字流输出 + 表达式求值语境 + 函数作用域生命周期 + 语句分派钩子。
 *
 * 表达式求值语境（[EvalContext]）由变量层（M2 的列式变量池）实现；
 * [_EVAL_INT]/[_EVAL_STR] 直接复用 `core.expr` 的 `Expr.getIntValue/getStrValue`，
 * 不在 VM 里重写一遍算术语义（ADR-001：语义契约以 core.expr 为准）。
 *
 * 函数进入/离开是**帧生命周期事件**，不是语句语义（team-lead 裁决 2026-10-17）：
 * CALL 家族有 CALL/CALLF/TRYCALLF/CALLFORM/TRYCALLFORM/JUMP/CALLSHARP/事件调用等
 * 8+ 条路径，让语句层逐条插指令必然漏；VM 的 `_CALL`/`_RET` 是所有路径的必经之处。
 * 一切以 `_CALL` 为入口的调用（含函数指针/事件派发，它们最终也编成 `_CALL`）自动覆盖；
 * 不走 `_CALL` 的路径当前不存在——若将来新增（如原生回调），必须同样挂钩。
 *
 * 语句分派（契约 C4）与按名调用（契约 C5）也在这条边界上：VM 负责**取语句 + 分派 +
 * 收拢控制流**（Normal/Transfer/Stop），语句语义本身归 `sem.StatementExecutor`。
 */
interface VmHost {
    /** 文字流输出（对应 EmueraConsole.Print）。换行/着色等属 M4 文字流层。 */
    fun print(text: String)

    /** EVAL 指令用的求值语境；null 时 EVAL 抛 CodeEE。 */
    val evalContext: EvalContext?

    /**
     * 语句执行钩子（契约 C4）：VM 主循环遇到 `op in 29..197` 时按语句池下标取出
     * [Statement] 交给宿主。**默认实现抛 CodeEE**——接线未完成时必须显式失败，
     * 不许静默当 NOP（否则 golden 语料会"跑通"却不输出）。
     */
    fun executeStatement(stmt: Statement, env: StmtEnv): StmtOutcome =
        throw CodeEE("宿主未提供语句执行器（VmHost.executeStatement）")

    /**
     * 语句执行钩子的**扁平池通道**（T2-64 波次 B）：Program 已构建扁平语句池
     * （[Program.ensureFlatStmtSource]，proc 桥构建期接线）时，VM marker 分支改走本方法，
     * [index] = 语句池下标（marker 指令的 `a` 操作数）。
     *
     * 缺省实现 = 还原兜底：`executeStatement(source.statementAt(index), env)` —— 只服务
     * 「持有扁平池但未覆写本方法」的装置宿主。产线 [com.eratw.port.core.proc.ProcessRuntime]
     * 覆写为 `StatementExecutor.execute(source, index, ctx)` 直读通道（热语句直读参数流，
     * 其余语句兜底）。注意缺省还原路径**丢掉语句池下标**（改走以语句实例为键的旧通道，
     * `StatementProbe` 拿到 `NO_STMT_INDEX`）：需要数据节（PRINTDATA/STRDATA）或按 index
     * 观测的宿主必须覆写本方法。
     */
    fun executeStatement(source: FlatStmtSource, index: Int, env: StmtEnv): StmtOutcome =
        executeStatement(source.statementAt(index), env)

    /**
     * 语句层累计跨越的**让出边界**次数（M10-RUNB · M10-AWAIT）：sem 每从一次真实输入等待
     * （`InputSink.waitInput` / `readAnyKey`）返回、或每执行一次 `AWAIT` 就 +1；
     * [com.eratw.port.core.proc.ProcessRuntime] 把
     * [com.eratw.port.core.sem.StatementExecutor.yieldBoundaryCount] 原样转发到这里。
     *
     * VM 主循环每派发一条语句后比较本值与上次读数：变了 ⇒ 该语句跨过了一次让出，
     * [VmConfig.maxStepsPerRun] 的段落计数从 0 重新起算。原版没有每-run 指令上限，
     * DoScript 外层 while 的边界是 `console.IsRunning`（spec: Process.cs:438-458）——
     * 输入即交还控制台；而 `AWAIT` 在原版里是**死循环保护唯一的复位点**
     * （`Process.cs:487-491` 的 `UpdateCheckInfiniteLoopState()` 全工程只被
     * `EmueraConsole.cs:849` 调用一次）。本移植等待是阻塞式，故把死循环闸的**计数边界**
     * 挪到这两类落点，阈值数值不变。
     *
     * 用累计计数而不是一次性布尔：主 VM 与嵌套 VM（[deriveNestedVm]）共用同一宿主时，
     * 一次性标志会被先轮询到的一方吃掉、另一方漏掉边界；读数单调，双方各自都能看到每一次变化。
     * 缺省 0 = 该宿主不存在让出等待（测试装置 / 纯求值宿主），行为与改造前完全一致。
     */
    fun yieldBoundaryCount(): Long = 0

    /**
     * 运行期按名解析函数（契约 C5，`_CALL_BY_NAME` 用）。三条路（与原版一一对应）：
     * * 命中非事件函数 → 返回 `Program.functions` 下标；
     * * 未命中 → 返回 `null`（spec: CalledFunction.cs:98-104 —— `CallFunction` 找不到标签时
     *   返回 null，由 CALL 语句按 isTry 决定跳过还是报错）；
     * * **非法目标 → 抛 CodeEE，VM 不吞**（事件名 spec: CalledFunction.cs:100-103
     *   `CallToEventFunc`；`#FUNCTION` 方法名 spec: CalledFunction.cs:106-109 `CallToUserFunc`）。
     *   即"查不到"与"不许查"是两条路，后者即使在 try 位下也必须抛
     *   （spec: FunctionIdentifier.cs:3575 `CalledFunction.CallFunction` 在 isTry 判断之前就已抛出）。
     */
    fun resolveFunctionIndex(name: String): Int? = null

    /**
     * CALLF 家族（CALLFORMF/TRYCALLFORMF）的运行期名字解析：只认用户
     * `#FUNCTION`/`#FUNCTIONS` 方法（spec: IdentifierDictionary.cs:538-586 的
     * `GetFunctionMethod(userDefinedOnly: true)`）。未命中返回 null，由 VM 按 try 位决定
     * 跳过或报错；非方法 label 由实现方抛 CodeEE（IdentifierDictionary.cs:557-560）。
     */
    fun resolveMethodIndex(name: String): Int? = null

    /**
     * **引擎级函数屏蔽**（[com.eratw.port.core.proc.EngineFunctionPolicy]）：本函数返回 true
     * 时，VM 把对该函数的调用当成「空体 `RETURN 0`」处理 —— 不建帧、不复制实参、不动变量层
     * 作用域、不占调用深度，调用点直接拿到 `0`（字符串返回位拿到 `""`）。
     *
     * 屏蔽在**调用边界**判定（[callInto] / [jumpInto]），因此与调用形态无关：静态 `CALL`、
     * 动态 `CALLFORM`/`TRYCALLFORM`、`CALLF` 家族、以及尾跳全走同一条判据，语料怎么调都拦得住。
     *
     * 为什么要这么一层：像 eraTW 的 `@BADAPPLE`（6573 帧 `AWAIT 16` ≈ 105 秒的标题彩蛋）
     * 这类函数只烧时间不产内容，屏蔽动作必须能在**引擎**里做 —— 语料不进仓，要求每个
     * 玩家手工注释语料既不可持续、也会被语料升级覆盖（详见该策略对象的 KDoc）。
     *
     * 缺省 false = 不屏蔽任何函数：既有测试宿主、golden 装置、纯求值宿主行为零变化。
     * 实现方必须**幂等且无副作用**（每次 `_CALL` 都会问一次，命中判据只能查表，不得抛错）。
     *
     * @param funcIndex `Program.functions` 下标（VM 已做越界校验）
     */
    fun isStubbedFunction(funcIndex: Int): Boolean = false

    /**
     * 进入函数帧 —— 在**实参复制完成之后、被调函数第一条指令之前**调用，
     * 保证变量层的 `ARG` 已就位（对应 VariableStore.enterFunction）。
     */
    fun onFunctionEnter(funcIndex: Int) {}

    /**
     * 离开函数帧 —— `_RET`/`_HALT`/异常退出都会调用，保证作用域栈不泄漏
     * （对应 VariableStore.leaveFunction）。
     *
     * 调用时该帧**已从 VM 调用栈弹出**：即使本回调抛错，`run()` 的 finally 也不会
     * 对同一帧重复调用（避免变量层栈不配对时出现二次 leave）。
     */
    fun onFunctionExit(funcIndex: Int) {}

    /**
     * 帧局部变量快照（只读取证口）：撞闸抛 [StepLimitExceeded] **之前**就地调用一次，
     * 结果随异常带走（[StepLimitExceeded.frameSnapshot]）。
     *
     * 之所以要 VM 主动要：`run()` 的 finally 在异常离栈时回卷调用栈，宿主拿到异常时
     * `frames` 已空，再读 LOCAL/ARG 只会报「变量 LOCAL 只能在函数内使用」。
     *
     * 默认空表（测试宿主 / 无变量池场景）；实现方不得抛错，抛错会在闸门处被兜住。
     */
    fun frameSnapshotLines(): List<String> = emptyList()
}

/** 最简宿主：把输出收进 StringBuilder。测试与 erbcc 自检用。 */
class StringHost(
    override val evalContext: EvalContext? = null,
) : VmHost {
    val output = StringBuilder()

    override fun print(text: String) {
        output.append(text)
    }
}

/**
 * [StmtEnv] 的 VM 内部实现：四个字段随语句刷新，整个 VM 复用同一实例（每语句零分配）。
 * 宿主若要留存，必须自行复制字段（见 [StmtEnv] 文档）。
 */
private class MutableStmtEnv : StmtEnv {
    override var functionKey: String = ""
    override var pc: Int = -1
    override var line: Int = -1
    override var functionName: String? = null
}


/**
 * 虚拟机：fetch-decode-execute 单线程解释器（ADR-003：VM 单线程是语义要求）。
 *
 * ## 分派方式：`when` 密集跳转表，不用"lambda 数组"
 *
 * 操作码编号 0..197 **连续无空洞**（见 [Opcode]），Kotlin 的 `when (op)` 会被编译成
 * `tableswitch`：一次边界检查 + 一次表内跳转，热分支可被 JIT/ART 内联。
 *
 * 备选方案"`Array<() -> Unit>` 动态分派"被否：每个 handler 是一个闭包对象，调用点是
 * megamorphic 的虚调用（无法内联），还要额外捕获状态；在 2.6M 行/帧的规模下这是纯亏。
 * 备选方案"每指令一个函数 + 手动线程化"在 JVM 上拿不到 computed goto 的收益，反而失去
 * `when` 的跳转表。结论：`when` 是 Kotlin 下唯一同时拿到表分派与内联的方案。
 *
 * ## 帧模型
 *
 * - 每个函数一个**帧**：`R0..R(slotCount-1)` 为帧内槽，物理上从 `frameBase` 起连续存放。
 * - 调用帧分配在"调用者基址 + 调用者帧大小"处（经典栈帧），返回即释放 → 支持递归。
 * - 参数按位置复制：调用者 `R0..R(argc-1)` → 被调帧 `R0..R(argc-1)`。
 * - 返回值经 [_RET] 的 a 槽取出（long 通道），写入调用方 [_CALL] 的 c 槽（`NONE` = 丢弃）；
 *   `#FUNCTIONS`（str 方法）走 [_RETS] 字符串通道（合同 M3-A §3 A7）：调用位 b bit28
 *   （STR_RET_FLAG）声明要字符串，leaveFrame 改走 `setStr`。
 * - [_CALL_BY_NAME] 与 [_CALL] 共用 [callInto]（建帧 + 实参复制 + `onFunctionEnter`），
 *   [_RET]/[_RETS] 与语句层的 `Stop` 共用 [leaveFrame]（拆帧 + `retDst` 写回 + `onFunctionExit`）：
 *   两条路径各只有一份实现，帧生命周期不可能在一边漏配对。
 * - JUMP 家族是**尾跳**：[_CALL]/[_CALL_BY_NAME] 的 b bit30=1 时走 [jumpInto] —— 目标函数
 *   替换当前帧（实参照用本帧 R0..），**不增加深度、不改返回点**，目标 `_RET` 直接回到
 *   当前帧的调用者（spec: ProcessState.cs:289-311：IsJump 帧 Return 时移除自身并递归 Return）。
 * - 真实 ERB 语句（操作码 29..197）不在 VM 里实现语义：按 marker 的 `a` 从语句池取出
 *   [Statement] 交给 [VmHost.executeStatement]，VM 只消费 [StmtOutcome]（契约 C4）。
 * - 全局槽 `G<n>` 走独立数组，与帧槽不共享地址空间（见 [SlotAddr]）。
 *
 * TODO(verify): 原版 LOCAL/ARG 是**按函数名**实例化的字典变量（specs/variables.yaml
 * local_vars），并非严格栈帧；递归时的可见行为需用原版实测对齐。本骨架先按栈帧实现，
 * 语义对齐在 M2 变量层接 SET/CALL 时做。
 *
 * ## 槽的类型
 *
 * 槽是**无类型**的（`Long` 数组 + 字符串数组各一份，同号槽可同时存在两种值）。
 * 静态类型在编译期已由 core.expr 的类型系统保证，运行期不再打标签。
 * TODO(verify): 字符串槽与整数槽混用是否需要运行期断言，接变量层时按原版 CodeEE 行为定。
 */
class VirtualMachine(
    val program: Program,
    private val host: VmHost = StringHost(),
    private val config: VmConfig = VmConfig(),
    /**
     * 嵌套 VM 注入点（[deriveNestedVm] 专用）：与本 VM **共享同一份全局槽**（GLOBAL 变量
     * 语义在进程内只有一份），帧栈独立。普通构造传 null = 自建全局槽。
     */
    sharedGlobals: Pair<LongArray, Array<String?>>? = null,
) {
    // ---- 帧槽存储（可增长） ----
    private var frameInts = LongArray(INITIAL_SLOTS)
    private var frameStrs = arrayOfNulls<String>(INITIAL_SLOTS)

    // ---- 全局槽存储（与帧槽隔离） ----
    private val globalInts: LongArray = sharedGlobals?.first ?: LongArray(maxOf(program.globalSlots, 1))
    private val globalStrs: Array<String?> = sharedGlobals?.second ?: arrayOfNulls(maxOf(program.globalSlots, 1))

    /**
     * 取指页缓冲（PERF 2026-10-27 第五批）：**本 VM 独占**，跨 `run` 复用。
     *
     * 只有来源支持 [CodeSource.supportsFillPage] 时才建（[MappedCodeSource]）；一旦建好
     * 就一直留着 —— 一个 VM 实例只付一次 16 KiB，而嵌套 VM 池又复用实例，于是整局
     * 「每换一页一个 `IntArray(16 KiB)`」的分配被彻底消掉。
     *
     * 为什么可以跨 `run` 复用：同一个 VM 实例**不会被重入** —— `run` 一进来就把
     * `depth`/`frameBase`/操作数栈全部重置，重入会直接毁掉外层状态，故重入一律走
     * [deriveNestedVm] 借另一台实例。缓冲因此永远只有一个读者。
     */
    private var pageBuf: IntArray? = null

    /** 取（必要时建）本 VM 的页缓冲；来源不支持原地填充时返回 null。 */
    private fun pageBufferOf(source: CodeSource): IntArray? {
        if (!source.supportsFillPage) return null
        val need = source.pageWords
        val cur = pageBuf
        if (cur != null && cur.size >= need) return cur
        return IntArray(need).also { pageBuf = it }
    }

    // ---- 调用栈（并行数组，避免每帧一个对象） ----
    private val retPc = IntArray(config.maxCallDepth + 1)
    private val frameBaseStack = IntArray(config.maxCallDepth + 1)
    private val frameSlotStack = IntArray(config.maxCallDepth + 1)
    private val retDstStack = IntArray(config.maxCallDepth + 1)
    private val funcStack = IntArray(config.maxCallDepth + 1)

    /**
     * 每帧的"调用位要字符串返回"标志（`_CALL`/`_CALL_BY_NAME` 的 b bit28，合同 M3-A §3 A7）：
     * `_RET`/`_RETS`/[StmtOutcome.Stop] 离开帧时按它决定写 `setInt` 还是 `setStr`。
     * 与 [retDstStack] 同下标同时刻写入（callInto/jumpInto 各一份）。
     */
    private val retStrStack = BooleanArray(config.maxCallDepth + 1)

    // ------------------------------------------------------------------
    // 操作数栈（T2-22：栈码组 202..243 的求值栈）
    //
    // 两个**类型化**栈：long 栈与 String 栈，栈指针独立（spI/spS）。栈码指令只碰自己那一侧，
    // 运行期没有类型标签、没有装箱 —— 这是相对 `_EVAL_*`（表达式池 + 递归虚分派）的全部收益来源。
    //
    // 容量固定 [OP_STACK_DEPTH]：下标个数与表达式嵌套深度都是编译期常量（CONTRACT-T2 §1.4
    // 「操作数栈用固定上限，编译期挡深度」），运行期扩容只会把「编译期就该发现的深度失控」
    // 推迟成内存问题。溢出/下溢一律抛 CodeEE（文案见 [pushInt]/[popInt]），**绝不回绕** ——
    // 回绕会静默改写调用方的求值中间值，是典型的「只在深递归里偶发」的脏 bug。
    //
    // 帧语义：每帧一个**水位线** [opBaseI]/[opBaseS] = 进入该帧那一刻的栈顶。被调帧以
    // `sp == base` 起步 ⇒ 看不到调用方的中间值；[leaveFrameCore] 把 sp 回卷到水位线 ⇒
    // 无论被调帧压了多少、有没有配平，调用方的栈面逐位复原。尾跳 [jumpInto] 原地换帧，
    // 水位线不变但 sp 就地回卷（旧帧的中间值随旧函数体一起作废）。
    // ------------------------------------------------------------------

    private val opStackI = LongArray(OP_STACK_DEPTH)
    private val opStackS = arrayOfNulls<String>(OP_STACK_DEPTH)
    private var spI = 0
    private var spS = 0
    private val opBaseI = IntArray(config.maxCallDepth + 1)
    private val opBaseS = IntArray(config.maxCallDepth + 1)

    // ------------------------------------------------------------------
    // 变量句柄内联缓存（T2-23「装期绑定」的落地形态）
    //
    // 栈码 `_STK_*_VAR_*` 的 `a` 是**字符串池下标**，不是变量名本身。按名存取一次要
    // `upperOf` + `privateVars[funcKey]` + `userVars[key]` + `registry.def(name)`（后者内部
    // 又一次大写查表）≈ 4～6 次哈希查找；而变量名的解析结果是**稳定的**（私有变量只按
    // 函数隔离，不随调用实例变化），所以每条指令只需要解析一次。
    //
    // 缓存形状：`[funcNo] → [key, handle, key, handle, …]`，`key = strIdx * 2 + isStr`。
    // 线性扫描而不是哈希：一个函数里引用的不同变量名通常只有十几个到几十个，
    // 十几次 int 比较比一次装箱哈希便宜，而且零分配。
    //
    // **为什么缓存在 VM 而不是 Program**（契约 T2-23 原文写的是 `Program.handleOf`）：
    // 句柄是**变量池私有**的下标（指向 `VariableStore` 的句柄表），同一个 `Program` 可以和
    // 不同的变量池搭配（测试夹具、`deriveNestedVm`、以及宿主换 store 重跑），把池私有下标
    // 挂到与池无关的 `Program` 上，换池那天就会静默读错变量。VM 与变量池同生命周期
    // （`ProcessRuntime.vm` 与 `store` 同属一个实例），所以缓存在 VM 天然安全；
    // 「同一 (函数, 变量) 只解析一次」这条语义与契约完全一致。
    // ------------------------------------------------------------------

    /** 每函数的 `[key, handle, …]` 句柄表；懒分配（未执行栈码的程序一个字节都不占）。 */
    private var varHandleTables: Array<IntArray?>? = null

    /**
     * 句柄表的所属变量池。宿主换了 `EvalContext.variables` 实例就整表作废 ——
     * 句柄跨池无意义（见 [entryOf] 的越界守卫）。
     */
    private var varHandleOwner: Any? = null

    /**
     * 每帧的"作用域已退出、新作用域尚未进入"标记（[unwindScopes] 读）。
     *
     * 只服务尾跳 [jumpInto]：它先把旧函数 exit 掉、再 enter 目标函数，两步之间本帧在
     * 变量层是空的。任一步抛错时 run() 的 finally 都不能再对这一帧补一次 exit ——
     * 宿主契约是"enter 抛错的帧不出现于 exit 序列"（见 [callInto] 的同款说明与
     * `VariableStore.enterFunction` 的回滚），旧帧则已经 exit 过。
     *
     * [jumpInto] 在同一条摘牌窗口里还会把 `funcStack[depth]` 写成 [FRAME_NONE]；
     * [unwindScopes] 两道守卫都查（`fi >= 0` 且 `!frameExited[d]`）。两者覆盖的是同一个
     * 窗口（成对置位、成对复位），行为与任一单道守卫逐条一致；保留两道是让"未登记"与
     * "已退出"两个不变量各自可独立核对。
     *
     * 复位点与 `funcStack[depth]` 的写入点一一对应：[run] 的入口帧、[callInto]、[jumpInto]。
     * 已弹出的帧槽（depth 之上的下标）不读，故 [leaveFrameCore] 不需要清它。
     */
    private val frameExited = BooleanArray(config.maxCallDepth + 1)

    private var depth = 0
    private var frameBase = 0
    private var frameSlotCount = 0

    /**
     * 最近一次**建立被调帧的调用点** pc（[_CALL]/[_CALL_BY_NAME] 与尾跳共用）；-1 = 非调用建帧。
     *
     * 宿主（[VmHost.onFunctionEnter]）在进入被调帧的那一刻读它，用于把调用点登记的
     * 逐位实参元数据（[Program.callArgsAt]）落到被调形参上（spec: ProcessState.cs:435-452）。
     * 生命周期只有一条指令：callInto/jumpInto 在 [VmHost.onFunctionEnter] 之前置位、之后清回 -1，
     * 绝不跨调用残留（根帧 [run] 不置位 ⇒ 恒 -1）。
     */
    var lastCallPc: Int = -1
        private set

    /**
     * 最近一次 [_RETS] 的字符串值（合同 M3-A §3 A7：便于测试与 erbcc 自检）。
     * 正常控制流下最后执行的 `_RETS` 是最外层方法的返回 = "顶层字符串值"；
     * 每次 [run] 开始重置为 null（一次没有 `_RETS` 的执行不得读到上一次的残留）。
     */
    var stringResult: String? = null
        private set

    /** 最近一次 [run] 实际执行的指令数。 */
    var executedSteps: Long = 0L
        private set

    /** 最近一条已执行指令的 pc（调试/测试用）。 */
    var lastPc: Int = -1
        private set

    /**
     * int 操作数栈的当前栈顶（**绝对**下标，跨帧累加；诊断/测试读数）。
     *
     * 正常结束时应为 0：每个 `return` 的帧都在 [leaveFrameCore] 里把 sp 回卷到自己的水位线，
     * 入口帧的水位线是 0。非 0 = 有帧把值压进栈却没消费（字节码不配平），属编译期缺陷。
     */
    val intStackDepth: Int get() = spI

    /** String 操作数栈的当前栈顶（口径同 [intStackDepth]）。 */
    val strStackDepth: Int get() = spS

    /**
     * 复用给 [VmHost.executeStatement] 的语句环境（契约 C4）。
     * 每执行一条语句刷新一次字段，见 [StmtEnv] 的"不得跨语句持有"约束。
     */
    private val stmtEnv = MutableStmtEnv()

    /**
     * 语句池取指的 L1 直映备忘（PERF 2026-10-27）。
     *
     * ## 为什么需要第二层
     *
     * 生产装载走**惰性语句池**：`Program.statement(a)` → `StatementPool.statementAt` 是
     * **接口调用**（JIT 无法内联）+ `AtomicReferenceArray.get`（**volatile 读**）+ 两次判界。
     * 一次 run 里 5,140,444 条语句各走一遍（`CHARA_MOVEMENT` 实测），JFR 归因
     * `Program.statement` 3.30% + `LazyStatementRecords.statementAt` 2.26% = **5.56%**，
     * 而其中真正的「首次物化」只占极小一部分 —— 绝大多数是同一批语句在 `FOR` 环体里
     * 被反复取。
     *
     * ## 为什么是直映 64 槽
     *
     * 环体是**连续若干条**语句（语料实测典型 5~30 条），`a` 在环内呈小范围聚集；
     * 64 槽直映（`a and 63`）足以覆盖一个环体的工作集，且槽位是普通 `IntArray` / 对象数组，
     * 命中路径是「一次数组读 + 一次整数比较」，无虚调用、无 volatile 栅栏
     * （ART 上 `ldar` 是 acquire 屏障，5M 次/run 的代价不可忽略）。
     *
     * ## 正确性
     *
     * 语句池**不可变**：`Statement` 物化后恒返回同一实例，`Program` 生命周期内不重写
     * （唯一例外 `releaseRaw` 释放的是原文而不是条目本身）。因此备忘不需要任何失效逻辑，
     * 也不受 `run`/`deriveNestedVm` 影响 —— 两个 VM 实例各持一份，共享同一批不可变条目。
     *
     * 哨兵 `-1` 合法：`a` 在进入备忘之前已按 `[0, statementCount)` 判过界。
     */
    private val stmtMemoKey = IntArray(STMT_MEMO_SLOTS) { -1 }
    private val stmtMemoVal = arrayOfNulls<Statement>(STMT_MEMO_SLOTS)

    /**
     * [stmtMemoKey] 的开关（缺省开）。`-Deratw.perf.noStmtMemo=true` 关掉。
     *
     * 存在理由只有一个：**同窗口 A/B**。跨窗口比较在本项目已被证伪（`CONTRACT-T2` 的
     * T2-62 回执第 4 条：单轮内 7 个稳态样本自身跨 17%，跨窗口会得出相反符号），
     * 而「改代码 → 重编 → 再测」天然是跨窗口。留一个构造期读一次的系统属性，
     * 就能用同一份构建交替跑两条腿。构造期读一次 ⇒ 热路径零成本。
     */
    private val useStmtMemo: Boolean = !java.lang.Boolean.getBoolean("eratw.perf.noStmtMemo")

    /**
     * [evalIntFast]/[evalStrFast] 的单态快路径开关（缺省开）。
     * `-Deratw.perf.noEvalFast=true` 关掉 —— 与 [useStmtMemo] 同一理由（同窗口 A/B）。
     */
    private val useEvalFast: Boolean = !java.lang.Boolean.getBoolean("eratw.perf.noEvalFast")

    /**
     * TEMP(perf-probe)：非 null 时逐指令累加操作码直方图。
     *
     * 只为回答「1208 万条指令里各操作码各占多少」这一个问题而临时存在，测完回滚。
     * 生产恒为 null ⇒ 热路径多一次字段读 + 一次可预测的空判，仅供取证。
     */
    @JvmField
    var opHistogram: IntArray? = null

    /**
     * 步数闸的抛点（**冷方法**，从主循环体里提出来；PERF 2026-10-27）。
     *
     * `StepLimitExceeded` 要构造 6 个参数，其中一个还是 `runCatching { host.frameSnapshotLines() }`
     * —— 这段代码内联在主循环体里，JIT 之后会让「全程序最热的几十个字节」明显变长。
     * 提到独立方法后，热路径只剩一次 `steps > maxSteps` 比较 + 一次（几乎不走的）调用，
     * 而抛错时的参数构造、行号解析、帧快照全部挪到冷路径。
     *
     * 语义零变化：`steps`/`pc`/`lineOf(pc)`/函数名/调用链/帧快照的来源与顺序逐字不变。
     */
    private fun stepLimitAt(pc: Int, steps: Long): Nothing = throw StepLimitExceeded(
        steps, program.lineOf(pc), pc, currentFunctionName(), currentCallChain(),
        runCatching { host.frameSnapshotLines() }.getOrDefault(emptyList()),
    )

    /**
     * 段落墙钟闸的抛点（冷方法，理由同 [stepLimitAt]）。
     *
     * 与步数闸共用 [StepLimitExceeded]，但带上 `elapsedMs`：失败屏/台账据此区分
     * 「跑了太多指令」（旧口径）与「这一段跑太久」（原版口径，`Process.cs:493-512`）。
     */
    private fun timeLimitAt(pc: Int, steps: Long, elapsedMs: Long): Nothing = throw StepLimitExceeded(
        steps, program.lineOf(pc), pc, currentFunctionName(), currentCallChain(),
        runCatching { host.frameSnapshotLines() }.getOrDefault(emptyList()),
        elapsedMs,
    )

    /** 取第 [index] 条语句：先查 L1 备忘，未命中再走 [Program.statement] 并回填。 */
    private fun statementOf(index: Int): Statement {
        if (!useStmtMemo) return program.statement(index)
        val slot = index and STMT_MEMO_MASK
        if (stmtMemoKey[slot] == index) {
            // 非空：写回填与键同槽同锁（单线程，ADR-003），键命中即值必在。
            return stmtMemoVal[slot]!!
        }
        val s = program.statement(index)
        stmtMemoKey[slot] = index
        stmtMemoVal[slot] = s
        return s
    }

    // ------------------------------------------------------------------
    // 表达式求值的**单态快路径**（PERF 2026-10-27）
    //
    // 病根：`Expr.getIntValue/getStrValue` 各有 **10 个实现**（`VarRef`/`Binary`/`Unary`/
    // `UnaryAfter`/`Ternary`/`FuncCall`/`KeywordIndexArg`/`IntLiteral`/`StrLiteral`/`NullTerm`），
    // 于是 `e.getIntValue(ctx)` 这个调用点是 **megamorphic** 的 —— JIT 一条实现都内联不了，
    // 每次求值都要走完整虚表查找，且被调方的函数体无法与调用方一起优化
    // （`VarRef.getIntValue` 的站点缓存查找、`borrowIndices` 借还、对 `VariableReader` 的
    // 二次虚调用因此全部无法内联展开）。
    //
    // 做法：按**节点具体类型**分流成几个各自单态（或双态）的调用点。类型判定本身是一次
    // 类型比较（Kotlin `is` → `instanceof`，命中即常数级），换来的是每条分支都能被 JIT
    // 内联 —— 这是 JVM 上处理「多实现虚调用」的标准手段，不需要改 `Expr` 的任何一行。
    //
    // 语义：**每一支都是同一次虚调用**，只是调用点被拆开；求值顺序、异常类型与文案、
    // 副作用次数逐字不变（`Unary(MINUS)` 的双求值、`DIV` 先右后左这些红线全在各
    // `Expr` 实现体里，本函数只负责「把调用发到正确的实现上」）。
    //
    // 为什么按这个顺序：`VarRef` 是表达式树里绝对最常见的叶子（语料热循环里大量
    // `CFLAG:LOCAL:X`、`TALENT:TARGET:X`），`Binary` 次之，`Unary`/`FuncCall` 再次之；
    // 其余落到 `else` 保持原来的 megamorphic 调用，行为与改造前**完全一致**。
    // ------------------------------------------------------------------

    /** `_EVAL_INT` 的求值（见上方「单态快路径」的取舍说明）。 */
    private fun evalIntFast(e: Expr, ctx: EvalContext): Long {
        if (!useEvalFast) return e.getIntValue(ctx)
        return when {
            e is VarRef -> e.getIntValue(ctx)
            e is Binary -> e.getIntValue(ctx)
            e is Unary -> e.getIntValue(ctx)
            e is FuncCall -> e.getIntValue(ctx)
            else -> e.getIntValue(ctx)
        }
    }

    /** `_EVAL_STR` 的求值（口径同 [evalIntFast]）。 */
    private fun evalStrFast(e: Expr, ctx: EvalContext): String {
        if (!useEvalFast) return e.getStrValue(ctx)
        return when {
            e is VarRef -> e.getStrValue(ctx)
            e is Binary -> e.getStrValue(ctx)
            e is FuncCall -> e.getStrValue(ctx)
            else -> e.getStrValue(ctx)
        }
    }

    /**
     * 从程序入口函数 [Program.entryFunction] 开始执行（等价于 `run(program.entryFunction)`）。
     *
     * @return 见 [run] 的「返回值口径」。
     */
    fun run(): Long = run(program.entryFunction)

    /**
     * 从**任意函数**开始执行，直到 [_HALT] / 顶层 [_RET] / 顶层 [StmtOutcome.Stop]。
     *
     * 每次调用都是一次全新的执行：调用栈重置、入口帧槽清零，但**全局槽保留**
     * （全局变量跨 tick 存活，对应原版 GLOBAL 语义）。
     *
     * ## 无合成根帧的语义等价性
     *
     * [entryFunctionIndex] 直接占据第 0 帧，不存在额外的"launcher/根帧"：`funcStack[0]`
     * 就是目标函数，`retPc[0] = -1`（无返回点）。因此目标函数里的 `_RET`/`_RETS`/`_HALT`/
     * [StmtOutcome.Stop] 在 `depth == 0` 处直接结束本次 run，返回值/字符串值经
     * [stringResult] 交回调用方 —— 与"合成根函数只发一条 `_CALL 目标,0,NONE` 再接 `_HALT`"
     * 的外部结果一致（`_CALL` 的 `retDst = NONE` 不写回，`_HALT` 交回的正是目标帧最后
     * 一次返回的 `result`）。
     *
     * 帧生命周期仍严格配对：入口函数经 [VmHost.onFunctionEnter] 进入一次，正常返回、
     * `_HALT` 或异常退出都由 [unwindScopes] 的 finally 兜底调用一次 [VmHost.onFunctionExit]
     * （[funcStack] 只含真实帧，不再有点数不一致的合成帧）。
     *
     * ## 返回值口径（`result` 是**整次 run 共享**的一条局部通道）
     *
     * `result` 由**每一条** [_RET] 赋值（不分深度：`_RET` 先写 `result`，再按 `depth` 决定
     * 是结束 run 还是 [leaveFrame]），[_HALT] / 顶层 [_RETS] 交回的都是它 —— 所以这三条
     * 收尾路径返回的都是「本次 run 里**最后执行到的一条 `_RET`** 的值」，不是恒 0：
     * 入口帧先 `_CALL f, n, R…` 而 `f` 用 `_RET` 返回 5，随后入口帧自己走到 `_HALT` ⇒
     * 本次 run 返回 **5**（合成根帧类比里 launcher 的 `_HALT` 同样返回 5，见上一段）。
     * 只有本次 run 一条 `_RET` 都没执行过时才是 0。[_RETS] 的字符串通道另经
     * [stringResult]，不受这条局部变量影响。
     *
     * @param entryFunctionIndex `program.functions` 下标；越界抛 [CodeEE]。
     * @return 本次 run 最后一条 [_RET] 的值（没有 `_RET` 时为 0）；顶层 [StmtOutcome.Stop] /
     *   [StmtOutcome.Unwind] 返回其 `value`。
     */
    fun run(entryFunctionIndex: Int): Long {
        if (entryFunctionIndex !in program.functions.indices) {
            throw CodeEE("函数下标越界($entryFunctionIndex)")
        }
        val entry = program.functions[entryFunctionIndex]
        depth = 0
        frameBase = 0
        frameSlotCount = entry.slotCount
        funcStack[0] = entryFunctionIndex
        retPc[0] = -1
        frameBaseStack[0] = 0
        frameSlotStack[0] = frameSlotCount
        retDstStack[0] = SlotAddr.NONE
        retStrStack[0] = false
        frameExited[0] = false
        // 操作数栈：一次 run 是一段全新的求值，栈面从 0 起；入口帧水位线 = 0。
        spI = 0
        spS = 0
        opBaseI[0] = 0
        opBaseS[0] = 0
        ensureFrameSlots(entry.slotCount.toLong())
        java.util.Arrays.fill(frameInts, 0, entry.slotCount, 0L)
        java.util.Arrays.fill(frameStrs, 0, entry.slotCount, null)
        executedSteps = 0L
        stringResult = null

        // 入口函数也是一帧：作用域必须成对进入/离开（finally 统一兜底）。
        host.onFunctionEnter(entryFunctionIndex)
        try {
            return execute(entry)
        } finally {
            unwindScopes()
        }
    }

    /**
     * 拆掉所有尚未正常返回的帧 —— `_HALT`、顶层 `_RET`、以及**任何异常退出**
     * （CodeEE/步数上限/调用栈溢出/宿主异常）都走这里，保证 `onFunctionExit` 与
     * `onFunctionEnter` 严格配对，变量层的作用域栈不会泄漏。
     *
     * 槽里是 [FRAME_NONE]（= [jumpInto] 摘牌后 enter 抛错）时跳过：那一帧的新作用域
     * 从未进入成功，补一次 exit 只会把调用者的作用域多弹一层。原版的 ScopeOut 只对
     * "已登记在 functionList 里"的 CalledFunction 执行（ProcessState.cs:296-317、:469-472），
     * 未入栈的调用没有可退的作用域 —— 与这里的跳过同口径。
     *
     * 同一次摘牌窗口的第二道标记：尾跳失败时该帧已被 [jumpInto] 置 [frameExited]
     * （旧帧已 exit、新帧未 enter 成），跳过它 —— 否则同一函数 exit 两次、enter 一次。
     * 两道守卫覆盖的是同一个窗口（[jumpInto] 里一起置位、[callInto]/[run] 里一起复位），
     * 任一道成立即跳过；保留两道是让"摘牌"与"已退出"两个不变量各自可独立核对。
     */
    private fun unwindScopes() {
        var d = depth
        while (d >= 0) {
            val fi = funcStack[d]
            if (fi >= 0 && !frameExited[d]) host.onFunctionExit(fi)
            d--
        }
        depth = -1
    }

    /**
     * 派生嵌套 VM（ProcessRuntime 的重入方法执行用，如 GETMETH/GETMETHS 宿主通道）：
     * **共享全局槽**（GLOBAL 变量与编译期全局临时在进程内同源）、独立帧栈与调用栈。
     * 单线程语义（ADR-003）下，嵌套 run 与外层 run 严格串行（外层循环暂停在语句求值内），
     * 共享数组无并发写；代码段本身只读（[run] 不再改写入口指令），天然不互踩。
     */
    internal fun deriveNestedVm(host: VmHost, config: VmConfig): VirtualMachine =
        VirtualMachine(program, host, config, globalInts to globalStrs)

    private fun execute(entry: FunctionInfo): Long {
        var pc = entry.entryPc
        // 段落计数：距上一次让出边界以来已执行的指令数 —— 死循环闸（maxStepsPerRun）用它。
        // 不跨越任何让出点的脚本里两个计数严格同步，闸行为与改造前逐条一致（M10-RUNB）。
        var steps = 0L
        // 段落墙钟起点（[VmConfig.maxRunMillis] 用，段落口径与 [steps] 完全一致）：
        // 原版的死循环保护看的是时间（`Process.cs:496` 的 `startTime.ElapsedMilliseconds`），
        // 而 `startTime` 只在 `UpdateCheckInfiniteLoopState()`（:487-491，被 `EmueraConsole.Await`
        // 调用，EmueraConsole.cs:849）与「玩家选择继续」时重启 ⇒ 度量边界同样是让出点。
        var segStartNanos = System.nanoTime()
        // 整段累计：本次 run 从头到这里实际执行的指令数 —— executedSteps 用它，口径不变
        // （= 最近一次 run 执行的总指令数；正常返回与异常退出都在 execute 的 finally 落数）。
        var runSteps = 0L
        // 宿主让出边界读数的上次快照（见 VmHost.yieldBoundaryCount；run 起点取当前值，
        // 上一次 run / 嵌套调用之前的边界不算数）。
        var seenBoundaries = host.yieldBoundaryCount()
        var result = 0L

        // 取指（M6-CODE-VIEW）：指令流经 CodeSource 的**页视图**读，页缓存在这两个局部变量里。
        // 页内命中只多一次边界比较（不走虚调用）；跨页才调一次 page()。
        // 语义：页只是 code[pc shl 2 .. +3] 的另一种读法，逐下标同一 u32；`IntArray` 来源
        // 的整段就是唯一一页（返回原数组本体），因此与改造前 `val code = program.code`
        // 取到的是同一块内存、同样的值。
        val source = program.codeSource
        val pageWords = source.pageWords
        val instructionCount = program.instructionCount
        // PERF（2026-10-27 第五批）：来源支持原地填充时，页用**本 VM 独占的常驻缓冲**，
        // 换页只重填不重建数组 —— JFR 实测 `MappedCodeSource.page` 的 `IntArray(16 KiB)`
        // 占执行循环内分配的 29.2%（45.39 MiB），且 16 KiB 超过 ART 大对象阈值（12 KiB），
        // 每页都落 LOS。缓冲归本 VM 所有（嵌套 VM 各一份）⇒ 不存在「旧页还被别人持有」
        // 的别名问题。不支持原地填充的来源（IntArrayCodeSource 零拷贝整段一页）走原路。
        //
        // **必须是实例字段而不是这里的局部量**：`execute` 每次 `run` 进一次，而
        // 表达式位用户方法调用每次都借一台嵌套 VM 跑 `run` —— 一轮 69.8 万次。
        // 第一版写成局部 `IntArray(pageWords)` 时，JFR 当场读到 **15.3 GiB 的 `int[]`**
        // （每次 `run` 一个 16 KiB），分配量反而爆了 600 倍。字段化之后只在每个 VM
        // 实例上分配一次（嵌套 VM 池复用同一批实例）。
        val pageBuf = pageBufferOf(source)
        // 当前页的实际字数（尾页可短）。`pageBuf` 恒为满长，因此页内上界不能读 `page.size`。
        var page: IntArray
        var pageLen: Int
        if (pageBuf != null) {
            pageLen = source.fillPage(0, pageBuf)
            page = pageBuf
        } else {
            page = source.page(0)
            pageLen = page.size
        }
        var pageBase = 0
        // 页内合法的**最大字偏移**：`pageLen - WORDS` 提到循环外，跨页时随 page 一起刷新。
        // 原写法每取一条指令算一次 `off + WORDS > page.size`（一次加法 + 一次比较），
        // 改成 `off > pageLimit` 省掉加法；语义等价（页字数恒为 4 的倍数 ⇒ 指令不跨页）。
        var pageLimit = pageLen - Instruction.WORDS
        // 死循环闸的阈值：`config.maxStepsPerRun` 是 `config` 的只读 val，但每条指令都要穿过
        // 一层引用读一次字段；提到循环外对语义零影响（VmConfig 在 run 期间不可变）。
        val maxSteps = config.maxStepsPerRun
        // 段落墙钟阈值（0 = 不限）。同 [maxSteps] 提到循环外：`VmConfig` 在 run 期间不可变。
        val maxRunMillis = config.maxRunMillis
        // 池上界也提到循环外（PERF 2026-10-27）。四个 `*Count` 都是 **getter**：
        // `exprCount`/`statementCount` 走 `lazyExprs?.nodeCount ?: exprs.size`，
        // `nodeCount` 还是**接口调用**；而 `_EVAL_INT` 每条都过 `checkPoolIndex(…, program.exprCount, …)`
        // （一轮 292 万次）、每条语句都过 `a >= program.statementCount`（一轮 295 万次）。
        // JFR 运行期归因里 `getExprCount` 单独占 1.07% 自耗时，就是这条链。
        // 不变量：`Program` 在 `run` 期间不可变（惰性池明确拒绝追加，`seal()` 之后
        // `addExpr`/`addStatement` 均抛）⇒ 提到循环外与逐次读字段语义等价。
        val exprCount = program.exprCount
        val statementCount = program.statementCount
        val constantCount = program.constantCount
        val stringCount = program.stringCount
        // 异常退出（CodeEE / StepLimitExceeded / 宿主异常）也要把已执行步数交回：
        // 原先只在 HALT/_RET/Stop/Unwind 的正常出口写 [executedSteps]，失败记录恒为 0，
        // sweep 台账无法判断错误前走了多远（2026-10-25 全量清扫实测 27,778 条失败全是 0）。
        try { while (true) {
            if (pc < 0 || pc >= instructionCount) {
                throw errorAt("指令指针越界($pc)", pc)
            }
            steps++
            runSteps++
            if (steps > maxSteps) {
                // 抛错走冷方法：`StepLimitExceeded` 的参数构造 + 帧快照 lambda 内联在
                // 循环体里会让最热的那几十个字节变长（见 [stepLimitAt]）。
                stepLimitAt(pc, steps)
            }
            // 段落墙钟闸（[VmConfig.maxRunMillis]）：每 65536 步读一次 `System.nanoTime()`。
            // 放在步数闸**之后**、取指之前，热路径只多一次 `maxRunMillis > 0` 的常量为假的比较
            // （缺省 0 ⇒ 装置与纯测试完全不走这段），不碰指令译码。
            if (maxRunMillis > 0L && (steps and RUN_MILLIS_CHECK_MASK) == 0L) {
                val elapsedMs = (System.nanoTime() - segStartNanos) / 1_000_000L
                if (elapsedMs > maxRunMillis) timeLimitAt(pc, steps, elapsedMs)
            }
            lastPc = pc
            val w = pc shl 2
            var off = w - pageBase
            if (off < 0 || off > pageLimit) {
                // 跨页（页号 = 字下标 / 页字数；页字数恒为 4 的倍数 ⇒ 一条指令不跨页）。
                val p = w / pageWords
                if (pageBuf != null) {
                    pageLen = source.fillPage(p, pageBuf)
                } else {
                    page = source.page(p)
                    pageLen = page.size
                }
                pageBase = p * pageWords
                pageLimit = pageLen - Instruction.WORDS
                off = w - pageBase
            }
            val op = page[off]
            val a = page[off + 1]
            val b = page[off + 2]
            val c = page[off + 3]

            val hist = opHistogram
            if (hist != null) hist[op]++

            when (op) {
                Opcode._NOP -> pc++

                Opcode._HALT -> {
                    executedSteps = runSteps
                    return result
                }

                Opcode._SET_INT -> {
                    checkPoolIndex(b, constantCount, "常量池", pc)
                    setInt(a, program.constant(b))
                    pc++
                }

                Opcode._SET_STR -> {
                    checkPoolIndex(b, stringCount, "字符串池", pc)
                    setStr(a, program.string(b))
                    pc++
                }

                Opcode._MOV_INT -> {
                    setInt(a, getInt(b))
                    pc++
                }

                Opcode._MOV_STR -> {
                    setStr(a, getStr(b))
                    pc++
                }

                Opcode._ADD -> {
                    setInt(a, getInt(b) + getInt(c)); pc++
                }

                Opcode._SUB -> {
                    setInt(a, getInt(b) - getInt(c)); pc++
                }

                Opcode._MUL -> {
                    setInt(a, getInt(b) * getInt(c)); pc++
                }

                Opcode._DIV -> {
                    val r = getInt(c)
                    if (r == 0L) throw errorAt("除数为 0", pc)
                    val l = getInt(b)
                    // `long.MinValue / -1` 是 .NET 唯一会溢出的整除：C# 抛 OverflowException
                    // （spec: OperatorMethodManager.cs:136），JVM 的 ldiv 静默回绕成 MIN_VALUE。
                    // 按原版抛 CodeEE，不回绕。取余（_MOD）不在此列：两边都得 0。
                    if (l == Long.MIN_VALUE && r == -1L) {
                        throw errorAt("除算溢出：-9223372036854775808 / -1", pc)
                    }
                    setInt(a, l / r); pc++
                }

                Opcode._MOD -> {
                    val r = getInt(c)
                    if (r == 0L) throw errorAt("除数为 0", pc)
                    setInt(a, getInt(b) % r); pc++
                }

                Opcode._NEG -> {
                    setInt(a, -getInt(b)); pc++
                }

                Opcode._EQ -> {
                    setInt(a, if (getInt(b) == getInt(c)) 1L else 0L); pc++
                }

                Opcode._NE -> {
                    setInt(a, if (getInt(b) != getInt(c)) 1L else 0L); pc++
                }

                Opcode._LT -> {
                    setInt(a, if (getInt(b) < getInt(c)) 1L else 0L); pc++
                }

                Opcode._LE -> {
                    setInt(a, if (getInt(b) <= getInt(c)) 1L else 0L); pc++
                }

                Opcode._GT -> {
                    setInt(a, if (getInt(b) > getInt(c)) 1L else 0L); pc++
                }

                Opcode._GE -> {
                    setInt(a, if (getInt(b) >= getInt(c)) 1L else 0L); pc++
                }

                Opcode._NOT -> {
                    setInt(a, if (getInt(b) == 0L) 1L else 0L); pc++
                }

                Opcode._JUMP -> pc = pc + 1 + a

                Opcode._JUMP_DYN -> {
                    // a=函数内标签下标 → 查当前函数标签表得 pc，绝对跳转（编译器/语句层负责名字解析）。
                    // 目标 pc 与 StmtOutcome.Transfer 同一条守卫（checkTransferTarget）：标签 pc 由
                    // 编译器/装载侧登记，畸形表里可能落在**别的函数**区间 —— 放行会让当前帧的返回点
                    // 指向另一个函数体，帧模型直接坏掉；标签下标本身越界另按「标签下标越界」报错。
                    val f = program.functions[funcStack[depth]]
                    if (a < 0 || a >= f.labelPcs.size) throw errorAt("标签下标越界($a)", pc)
                    pc = checkTransferTarget(f.labelPcs[a], pc)
                }

                Opcode._JUMP_BY_NAME -> {
                    // a=名字槽 —— GOTOFORM：运行期查当前函数的 $标签。
                    // b bit31 = try 标志（与 _CALL_BY_NAME 的 TRY_FLAG 同口径，操作数 b 别无他用；
                    //   GOTO 没有 bit30/bit29 语义，不参与 ARG_COUNT_MASK）。
                    // 未命中：非 TRY 抛 CodeEE；TRY 跳过本行。
                    // spec: FunctionIdentifier.cs:3694-3709 —— 动态标签缺失时 !isTry 抛，isTry 时
                    //   有 catch 区则 JumpToEndCatch（编译期已追加 `_JUMP → catch 体首` 两步式）、
                    //   无 catch 区直接 return。常量标签缺失走 :3685-3693 的运行期早退，编译期不发指令。
                    val f = program.functions[funcStack[depth]]
                    val raw = getStr(a)
                    val name = raw.uppercase()
                    val isTry = (b and Opcode.TRY_FLAG) != 0
                    val idx = f.labelNames.indexOf(name)
                    if (idx < 0) {
                        // 官方 zhs 文案（emuera-zhs.xml:540 NotDefinedLabelName），format 实参 =
                        // 名字原文未大写化（FunctionIdentifier.cs:3703 传 text 原文）。
                        if (!isTry) throw errorAt("当前函数中不存在指定的标签名\"$raw\"", pc)
                        pc++
                    } else {
                        pc = f.labelPcs[idx]
                    }
                }

                Opcode._JUMP_IF_FALSE -> {
                    pc = if (getInt(a) == 0L) pc + 1 + b else pc + 1
                }

                Opcode._JUMP_IF_TRUE -> {
                    pc = if (getInt(a) != 0L) pc + 1 + b else pc + 1
                }

                Opcode._CALL -> {
                    // b = 实参数 | bit30 尾跳标志（契约 R10 API 3）| bit28 字符串返回标志
                    // （合同 M3-A §3 A7）；历史字节码两位为 0，行为不变。
                    val isJump = (b and Opcode.JUMP_FLAG) != 0
                    val isStrRet = (b and Opcode.STR_RET_FLAG) != 0
                    val argc = b and Opcode.ARG_COUNT_MASK
                    pc = if (isJump) {
                        val next = jumpInto(a, argc, isStrRet, pc)
                        // 尾调到被屏蔽函数（见 [VmHost.isStubbedFunction]）：本帧已经"返回"。
                        // depth==0 时"本帧返回"就是整个 run 结束，jumpInto 表达不了 ⇒ 哨兵值
                        // 交回本层（与 `_RET` 的 depth==0 分支同口径）。
                        if (next == STUB_TAIL_RETURN) {
                            if (isStrRet) stringResult = null
                            executedSteps = runSteps
                            return 0L
                        }
                        next
                    } else {
                        callInto(a, argc, c, isStrRet, pc)
                    }
                }

                Opcode._CALL_BY_NAME -> {
                    // 契约 C5：a=名字槽（帧内字符串临时，已由 _EVAL_STR 写好），
                    // b=实参数 | bit31=try 标志 | bit30=尾跳标志（契约 R10 API 3）
                    //   | bit29=CALLF 家族标志（契约 R11/API 5）| bit28=字符串返回标志
                    //   （合同 M3-A §3 A7），c=返回槽。
                    // 原版语义（CALL_Instruction.DoInstruction）：
                    //   spec: FunctionIdentifier.cs:3572-3576 —— FORM 变体先 `FuncnameTerm.GetStrValue`
                    //     取名，再走与静态 CALL 同一条 CalledFunction.CallFunction 路径；
                    //   spec: FunctionIdentifier.cs:3577-3588 —— 解析结果为 null 时：
                    //     !isTry → `throw new CodeEE(Lang.Error.NotDefinedFunc)`（3581）；
                    //     isTry  → 无 CATCH 时直接 return（3587）= 跳过本行继续执行下一条指令
                    //                （有 JumpToEndCatch 时 state.JumpTo(...)，3583-3586，
                    //                 属 TRYC 变体的同函数内转移，本轮由语句层经 StmtOutcome.Transfer 走，
                    //                 VM 的 try 位只覆盖"查不到就跳过"）；
                    //   spec: FunctionIdentifier.cs:3589-3598 —— 命中则 state.IntoFunction(...)，
                    //     与 _CALL 完全同一条建帧路径（本文件 callInto；尾跳走 jumpInto）。
                    // CALLF 家族（bit29）：改走 `resolveMethodIndex`，只认用户 #FUNCTION/#FUNCTIONS
                    //   方法（spec: FunctionIdentifier.cs:1146-1166、1271-1286 →
                    //   IdentifierDictionary.cs:538-586 `GetFunctionMethod(userDefinedOnly: true)`）；
                    //   命中后仍是普通调用/尾跳路径（CALLF 不置 JUMP 位，c 通常 NONE），
                    //   未命中非 try 时抛 NotDefinedUserFunc（IdentifierDictionary.cs:572-575）。
                    // "非法目标"（事件名 / #FUNCTION 方法名）在 resolveFunctionIndex 里就已抛出
                    //   （spec: CalledFunction.cs:100-103, 106-109），VM 不 catch、不降级为跳过。
                    val name = getStr(a)
                    val isTry = (b and Opcode.TRY_FLAG) != 0
                    val isJump = (b and Opcode.JUMP_FLAG) != 0
                    val isCallF = (b and Opcode.CALLF_FLAG) != 0
                    val isStrRet = (b and Opcode.STR_RET_FLAG) != 0
                    val argc = b and Opcode.ARG_COUNT_MASK
                    val target = if (isCallF) {
                        host.resolveMethodIndex(name)
                    } else {
                        host.resolveFunctionIndex(name)
                    }
                    if (target == null) {
                        if (!isTry) {
                            // 上屏原文两族（与 core/proc/ProcessRuntime.kt 的 notDefinedFuncMes /
                            // notDefinedUserFuncMes 同口径，由 CallMesTextTest 交叉用例钉住相等）：
                            //  CALL/CALLFORM 非 try = Lang.Error.NotDefinedFunc
                            //    （jp Lang.cs:2262 / zhs lang.emuera-zhs.xml:520 =
                            //     没有找到指定的函数"@{0}"；抛点 FunctionIdentifier.cs:3581，
                            //     静态名与 FORM 动态名共用这一条。`@` 是 format 串里的字面量）；
                            //  CALLF 家族非 try = Lang.Error.NotDefinedUserFunc
                            //    （jp Lang.cs:2210 / zhs :507 = 没有找到行内函数"{0}"；
                            //     抛点 FunctionIdentifier.cs:1161-1163，**不带 `@`**）。
                            throw errorAt(
                                if (isCallF) "没有找到行内函数\"$name\"" else "没有找到指定的函数\"@$name\"",
                                pc,
                            )
                        }
                        // try 未命中的"未调用"标志位：与下方命中分支的 1 同槽同守卫。
                        if (!SlotAddr.isNone(c)) {
                            checkRetDst(c, pc)
                            setInt(c, 0L)
                        }
                        pc++
                    } else if (isJump) {
                        // 尾跳命中就直接换帧走人：调用方不会回到本行之后的 `_JUMP_IF_TRUE`，
                        // c 的命中标志无需写。
                        val next = jumpInto(target, argc, isStrRet, pc)
                        // 同 [_CALL] 的尾调屏蔽分支：被屏蔽函数的尾跳 = 本帧立刻返回 0。
                        if (next == STUB_TAIL_RETURN) {
                            if (isStrRet) stringResult = null
                            executedSteps = runSteps
                            return 0L
                        }
                        pc = next
                    } else {
                        // try 且给了标志槽 ⇒ c 作"是否真调用"标志（TRYC 降级用，契约 R3）：
                        // 必须在切帧**之前**写调用方帧，且返回值改 NONE —— 否则被调帧的 _RET
                        // 会经 retDst 覆盖这个 1。
                        if (isTry && !SlotAddr.isNone(c)) {
                            // 标志槽与返回槽同为调用方帧槽：先做同款越界守卫再落 1
                            // （越界槽直接 setInt 会写进相邻帧 —— 运行期守卫，见 checkRetDst）。
                            checkRetDst(c, pc)
                            setInt(c, 1L)
                            pc = callInto(target, argc, SlotAddr.NONE, false, pc)
                        } else {
                            pc = callInto(target, argc, c, isStrRet, pc)
                        }
                    }
                }

                Opcode._RET -> {
                    result = if (SlotAddr.isNone(a)) 0L else getInt(a)
                    if (depth == 0) {
                        executedSteps = runSteps
                        return result
                    }
                    // 先取返回值再拆帧：返回槽属于被调帧。
                    // str 调用位收到 long 返回 = 方法帧里走了 `Return(long)`：原版
                    // ProcessState.cs:289-295 `IsFunctionMethod → ReturnF(null)`，消费侧
                    // SuperUserDefinedMethodTerm.cs:33-38 得 "" —— 同口径写空串，不抛。
                    pc = if (retStrStack[depth]) leaveFrameStr(null) else leaveFrame(result)
                }

                Opcode._RETS -> {
                    // `#FUNCTIONS`（str 方法）返回通道（合同 M3-A §3 A7）：a=被调帧内字符串槽，
                    // NONE = 无返回值（RETURNF 无参 / 掉尾，原版 ReturnF(null)，消费侧得 ""）。
                    // 返回槽（retDst）行为：NONE = 丢弃（合法，[leaveFrameCore] 不写）；
                    // 越界槽已在调用点被 [checkRetDst] 拒绝 —— 原版 ReturnF 无"槽"概念
                    // （spec: ProcessState.cs:463-472 只弹帧 + 置 MethodReturnValue；
                    // RETURNF 语句 spec: FunctionIdentifier.cs:3444-3485），槽是本移植
                    // 字节码契约，越界属字节码不完整，按运行期守卫抛 CodeEE。
                    val str = if (SlotAddr.isNone(a)) null else getStr(a)
                    stringResult = str
                    if (depth == 0) {
                        // 顶层 `_RETS` 直接结束 run：long 结果保持 0，字符串经 [stringResult] 取。
                        executedSteps = runSteps
                        return result
                    }
                    // int 调用位收到字符串返回 = 原版消费侧 GetIntValue 对 SingleStrTerm 得 0L
                    // （SuperUserDefinedMethodTerm.cs:26-31）—— 同口径写 0，不抛。
                    pc = if (retStrStack[depth]) leaveFrameStr(str) else leaveFrame(0L)
                }

                Opcode._PRINT_INT -> {
                    val text = getInt(a).toString()
                    host.print(if ((b and FLAG_NEWLINE) != 0) text + "\n" else text)
                    pc++
                }

                Opcode._PRINT_STR -> {
                    val text = getStr(a)
                    host.print(if ((b and FLAG_NEWLINE) != 0) text + "\n" else text)
                    pc++
                }

                Opcode._PRINT_LITERAL -> {
                    checkPoolIndex(a, stringCount, "字符串池", pc)
                    val text = program.string(a)
                    host.print(if ((b and FLAG_NEWLINE) != 0) text + "\n" else text)
                    pc++
                }

                Opcode._EVAL_INT -> {
                    val ctx = host.evalContext
                        ?: throw errorAt("EVAL 需要 EvalContext，宿主未提供", pc)
                    checkPoolIndex(a, exprCount, "表达式池", pc)
                    // 表达式错在此处转 CodeEE：原版表达式错本身就是 CodeEE（显示带当前行）；
                    // 裸穿的 ExprEvalException 没有函数/行现场（2026-10-25 真机
                    // 「负数(-16)不能与字符串相乘」启动失败无定位即此因），语句层
                    // ProcessRuntime.executeStatementRaw 有同款包裹。
                    val v = try {
                        evalIntFast(program.expr(a), ctx)
                    } catch (e: ExprEvalException) {
                        throw errorAt(e.message ?: "表达式求值失败", pc)
                    }
                    setInt(b, v)
                    pc++
                }

                Opcode._EVAL_STR -> {
                    val ctx = host.evalContext
                        ?: throw errorAt("EVAL 需要 EvalContext，宿主未提供", pc)
                    checkPoolIndex(a, exprCount, "表达式池", pc)
                    val s = try {
                        evalStrFast(program.expr(a), ctx)
                    } catch (e: ExprEvalException) {
                        throw errorAt(e.message ?: "表达式求值失败", pc)
                    }
                    setStr(b, s)
                    pc++
                }

                else -> {
                    // 契约 C4：29..197 是 169 条真实 ERB 语句（marker 指令：a=语句池下标、
                    // b=0、c=SlotAddr.NONE），语义归语句层（`sem.StatementExecutor`，经
                    // `ProcessRuntime.executeStatement` 桥）。区间外**不是**语句：
                    // 202..243 是栈码组（T2-21，走 [execStackOp]），其余（负数 / > 243 的
                    // 脏数据）仍然显式失败。
                    //
                    // 判据顺序刻意写成「先语句区间、后栈码区间」：语句 marker 是当前最热的
                    // 分支，`op !in 29..197` 为假时整块跳过，热路径**一条指令都没多**；
                    // 反过来写会让每条语句多两次区间比较。
                    if (op !in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE) {
                        if (op in Opcode.STACK_OPS) {
                            pc = execStackOp(op, a, b, c, pc)
                            continue
                        }
                        throw errorAt("语句 ${Opcode.nameOf(op)} 尚未实现", pc)
                    }
                    if (a < 0 || a >= statementCount) {
                        throw errorAt("语句池下标越界($a)（共 ${statementCount} 条）", pc)
                    }
                    // T2-64：扁平池在（proc 桥构建期 build、且装载期开关允许）时 marker 直通扁平通道；
                    // null（Android 默认关闭 / 纯装置 Program / 裸宿主）保持对象树老路（statementOf + L1 备忘），
                    // 与波次 A 前逐字一致。
                    val flatSource = program.flatStmtSource
                    val outcome = if (flatSource != null) {
                        host.executeStatement(flatSource, a, envAt(pc))
                    } else {
                        host.executeStatement(statementOf(a), envAt(pc))
                    }
                    // 让出边界（M10-RUNB 输入等待 / M10-AWAIT 帧让出）：这条语句跨过了它 ⇒ 段落交界，
                    // 死循环闸的段落计数从 0 重算；runSteps（executedSteps 口径）不动。
                    // 墙钟起点同步重启 —— 原版 `startTime.Restart()` 与 `state.lineCount = 0`
                    // 是同一处（`Process.cs:487-491`、`:509-510`），两个闸的段落边界必须同源。
                    val boundaries = host.yieldBoundaryCount()
                    if (boundaries != seenBoundaries) {
                        seenBoundaries = boundaries
                        steps = 0L
                        if (maxRunMillis > 0L) segStartNanos = System.nanoTime()
                    }
                    when (outcome) {
                        StmtOutcome.Normal -> pc++

                        is StmtOutcome.Transfer -> pc = checkTransferTarget(outcome.pc, pc)

                        is StmtOutcome.Stop -> {
                            result = outcome.value
                            if (depth == 0) {
                                executedSteps = runSteps
                                return result
                            }
                            // 同 `_RET` 的 str 容错口径（QUIT/BEGIN/RETURN 走 Stop）。
                            pc = if (retStrStack[depth]) leaveFrameStr(null) else leaveFrame(result)
                        }

                        is StmtOutcome.Unwind -> {
                            // 整栈丢弃（ClearFunctionList）：不走 leaveFrame —— 逐帧弹会把控制权
                            // 交回调用方，与原版清空函数栈后脚本立即结束相反。作用域由
                            // run() 的 finally（unwindScopes）成对退出。
                            result = outcome.value
                            executedSteps = runSteps
                            return result
                        }
                    }
                }
            }
        } } finally {
            executedSteps = runSteps
        }
    }

    // ------------------------------------------------------------------
    // 帧建立 / 拆除（_CALL 与 _CALL_BY_NAME、_RET 与 StmtOutcome.Stop 各共用一条路径）
    // ------------------------------------------------------------------

    /**
     * 建立被调帧并把 pc 切到其入口 —— [_CALL] 与 [_CALL_BY_NAME] 的**普通调用**共用（契约 C5：
     * "命中后必须与 _CALL 同一条帧建立路径"，两份拷贝必然漏掉 enter/exit 配对与实参裁剪）。
     * JUMP 家族（b bit30=1）不走这里，见 [jumpInto]。
     *
     * @param funcIndex 目标函数下标（调用方已完成名字解析与越界语义）。
     * @param argcRaw 调用点实参个数（int/str 两套共用槽号、取 max；超出被调 `arity` 的部分裁剪）。
     * @param retDst 返回值写回调用方的槽（[SlotAddr.NONE] = 丢弃）；越界槽在调用点就地
     *   抛 [CodeEE]（[checkRetDst]）—— 放行会在被调帧返回时写坏相邻帧内存。
     * @param isStrRet 调用位要字符串返回（b bit28，合同 M3-A §3 A7）：记入 [retStrStack]，
     *   决定被调帧 `_RET`/`_RETS`/Stop 离开时写 `setStr` 还是 `setInt`。
     * @param pc 调用点指令下标（报错定位 + 返回点 = pc+1）。
     * @return 被调函数入口 pc。
     */
    private fun callInto(funcIndex: Int, argcRaw: Int, retDst: Int, isStrRet: Boolean, pc: Int): Int {
        if (funcIndex < 0 || funcIndex >= program.functions.size) {
            throw errorAt("函数下标越界($funcIndex)", pc)
        }
        checkRetDst(retDst, pc)
        // 引擎级函数屏蔽（[VmHost.isStubbedFunction]）：被屏蔽函数 = 空体 `RETURN 0`。
        // 不建帧、不复制实参、不进变量层作用域，直接把 0/"" 写回**调用方**帧的返回槽
        // （frameBase 尚未切换，[setInt]/[setStr] 落的就是调用方槽），然后跳过本指令。
        // 放在 maxCallDepth 之前：屏蔽调用不占深度，也不会因为深栈被误判成爆栈。
        if (host.isStubbedFunction(funcIndex)) {
            if (!SlotAddr.isNone(retDst)) {
                if (isStrRet) setStr(retDst, "") else setInt(retDst, 0L)
            }
            return pc + 1
        }
        if (depth + 1 > config.maxCallDepth) {
            throw CallStackOverflow(config.maxCallDepth, program.lineOf(pc), pc, currentFunctionName(), currentCallChain())
        }
        val callee = program.functions[funcIndex]
        // 实参复制上界三重夹取（契约 + 健壮性，见 [jumpInto] 同款说明）：
        //  - [argcRaw]：调用点声明的实参数（b 的低位）；
        //  - `callee.arity`：原版只按被调**头部形参位**逐位绑定（spec: ProcessState.cs:435
        //    循环上界 `call.TopLabel.Arg.Length`），多出的实参原版直接忽略；
        //  - `callee.slotCount`：实参只能落在**本帧槽区间**内。畸形的 FunctionInfo
        //    （arity > slotCount，装载期 validateFunctionRecord 不拦这一条）下，按 arity 复制
        //    会把实参写进**相邻帧**区域，甚至越过 `ensureFrameSlots` 只保证到
        //    `frameBase + slotCount` 的容量 ⇒ AIOOBE 冒到宿主。
        // 下限 0：负数 arity（内存构造的畸形函数表）不得让下面的清零区间变成负数起点。
        val argc = minOf(argcRaw, callee.arity, callee.slotCount).coerceAtLeast(0)
        // 帧基址 + 帧大小先在 Long 上算：两个 Int 相加可能回绕成负下标，回绕后的
        // `need` 还会骗过容量比较（负数 ≤ 当前容量），随后写 frameInts 直接越界。
        val newBaseL = frameBase.toLong() + frameSlotCount
        ensureFrameSlots(newBaseL + callee.slotCount)
        val newBase = newBaseL.toInt()
        // 形参按位置复制（int 与 str 两套数组各拷一次）
        for (i in 0 until argc) {
            frameInts[newBase + i] = frameInts[frameBase + i]
            frameStrs[newBase + i] = frameStrs[frameBase + i]
        }
        for (i in argc until callee.slotCount) {
            frameInts[newBase + i] = 0L
            frameStrs[newBase + i] = null
        }
        // 作用域进入必须在实参复制之后（变量层 enterFunction 后 ARG 才就位）；
        // 放在改 depth/帧指针之前：若宿主抛错，本帧还没算"已进入"，finally 不会误出栈。
        lastCallPc = pc
        try {
            host.onFunctionEnter(funcIndex)
        } finally {
            lastCallPc = -1
        }
        depth++
        retPc[depth] = pc + 1
        frameBaseStack[depth] = frameBase
        frameSlotStack[depth] = frameSlotCount
        retDstStack[depth] = retDst
        retStrStack[depth] = isStrRet
        frameExited[depth] = false
        funcStack[depth] = funcIndex
        // 操作数栈水位线 = 调用方此刻的栈顶 ⇒ 被调帧以空栈起步，调用方的中间值对它不可见。
        opBaseI[depth] = spI
        opBaseS[depth] = spS
        frameBase = newBase
        frameSlotCount = callee.slotCount
        return callee.entryPc
    }

    /**
     * 池下标守卫（与槽守卫 [checkRetDst]、[frameSlotIndex] 同口径，CMP-55 的"字节码不完整"
     * 一律收敛成 [CodeEE]）：
     *
     * 常量池 / 字符串池 / 表达式池的下标是**字节码操作数**（[_SET_INT]/[_SET_STR]/
     * [_PRINT_LITERAL]/[_EVAL_INT]/[_EVAL_STR]），越界属编译器 bug 或裸拼装错误。
     * `.erbc` 装载链有引用范围校验（`ErbcStream.validate` 的 requirePool），但**内存直编**
     * （`CodeBuilder`）与 `Assembler` 产物不走那趟，放行只会让 ArrayList 的
     * `IndexOutOfBoundsException`（非 CodeEE）冒到宿主/主线程。就地抛 [CodeEE]，
     * 与槽越界同一层。
     */
    private fun checkPoolIndex(index: Int, size: Int, pool: String, pc: Int) {
        if (index < 0 || index >= size) {
            throw errorAt("${pool}下标越界($index)（共 $size 项）", pc)
        }
    }

    /**
     * 返回槽守卫（运行期，[_CALL]/[_CALL_BY_NAME] 的 c 操作数在调用点校验）：
     * [SlotAddr.NONE] = 丢弃返回值，合法放行；其余槽必须落在**调用方当前帧**的槽区间内
     * （帧内槽），或全局槽数组界内。越界 = 字节码不完整（编译器 bug / 裸拼装错误）：
     * 放行的后果是被调帧 `_RET`/`_RETS` 返回时经 [leaveFrameCore] 把值写进相邻帧
     * （静默写坏内存）或直接数组越界崩溃 —— 在调用点就地抛 [CodeEE]，不让损坏下沉。
     *
     * 校验时机充分性：调用方帧的 [frameSlotCount] 在被调帧存活期间不变，调用点合法的
     * [retDstStack] 记录到返回时刻仍合法，[leaveFrameCore] 无需二次校验。
     *
     * 原版没有这一层（字节码是本移植的产物，原版语句树不存在"槽号"）；
     * 错误级别对齐原版运行期守卫的 [CodeEE]（spec: Process.cs:516-519 方法栈超限
     * 同样抛 CodeEE），文案带槽形式（[SlotAddr.format]）与可用槽数。
     */
    private fun checkRetDst(dst: Int, pc: Int) {
        if (SlotAddr.isNone(dst)) return
        if (SlotAddr.isGlobal(dst)) {
            val idx = SlotAddr.index(dst)
            if (idx >= globalInts.size || idx >= globalStrs.size) {
                throw errorAt("返回槽越界（全局槽 G$idx，全局槽共 ${globalInts.size} 个）", pc)
            }
        } else if (dst >= frameSlotCount) {
            throw errorAt("返回槽越界（R$dst，当前帧只有 $frameSlotCount 槽）", pc)
        }
    }

    /**
     * 尾跳（JUMP 家族）：用目标函数**替换当前帧**，其返回直达当前帧的调用者。
     *
     * 与 [callInto] 的差别：**不加 depth、不改 retPc/retDstStack/frameBaseStack/frameSlotStack**——
     * 目标函数执行 `_RET` 时 [leaveFrame] 弹出的是本帧（funcStack[depth] 已换成目标），
     * 回到的仍是本帧的返回点，即"跳转函数的返回直达调用者的调用者"
     * （spec: ProcessState.cs:289-311 —— IsJump 帧 Return 时移除自身并递归 Return）。
     *
     * 实参已由编译器 stageArgs 写在本帧 R0..，尾跳复用同一 frameBase，不复制实参；
     * 只清 `[argc, slotCount)` 覆盖旧槽，顺带丢弃 `argcRaw > arity` 的多余实参。
     * 不做 maxCallDepth 检查：尾跳不增加深度。
     *
     * @param argcRaw 调用点实参个数；超出被调 `arity` 的部分忽略。
     * @param isStrRet 调用位要字符串返回（b bit28）：本帧的返回标志换成调用位的声明
     *   （目标 `_RETS` 的字符串经本帧的 retDst 直达调用者的调用者）。
     * @param pc 跳转点指令下标（报错定位）。
     * @return 被调函数入口 pc。
     */
    private fun jumpInto(funcIndex: Int, argcRaw: Int, isStrRet: Boolean, pc: Int): Int {
        if (funcIndex < 0 || funcIndex >= program.functions.size) {
            throw errorAt("函数下标越界($funcIndex)", pc)
        }
        // 引擎级函数屏蔽（[VmHost.isStubbedFunction]）：尾调一个"空体 RETURN 0"的函数
        // ≡ 本帧立刻返回 0/""。depth==0 时退回哨兵值，由调用点转成 run 的正常返回。
        if (host.isStubbedFunction(funcIndex)) return stubTailReturn(isStrRet)
        val callee = program.functions[funcIndex]
        // 实参上界三重夹取（与 [callInto] 同一条判据）：`arity > slotCount` 或负 arity 的
        // 畸形函数表下，清零区间 `[argc, slotCount)` 不得越出本帧槽区间（负起点会写成
        // `frameBase - 1` 的跨帧读写）。尾跳不复制实参（实参已由编译器写在本帧 R0..），
        // 这里只用于**截断清零区间**的起点。
        val argc = minOf(argcRaw, callee.arity, callee.slotCount).coerceAtLeast(0)
        ensureFrameSlots(frameBase.toLong() + callee.slotCount)
        for (i in argc until callee.slotCount) {
            frameInts[frameBase + i] = 0L
            frameStrs[frameBase + i] = null
        }
        // 旧作用域先退出、新作用域再进入；frameBase 未变，enter 的 bindArgs 仍从当前帧读实参。
        // 变量层的作用域是 LIFO 栈（`VariableStore.leaveFunction` 弹栈顶），所以不能改成
        // "先 enter 新的再 exit 旧的"：那样弹出的是新帧，旧帧的作用域永远留在栈上。
        //
        // 槽位先摘成哨兵再调 exit/enter：本函数是**原地换帧**，depth 不变，若 exit 或 enter
        // 抛错，run() 的 finally（unwindScopes）会从同一 depth 兜底；槽里若仍是旧函数（或已
        // 提前写成新函数），就会对同一帧多补一次 exit —— 变量层作用域栈被多弹一层，
        // 还会把原始异常顶掉。enter 成功后才真正登记新函数下标。
        // 同一次摘牌窗口一并置 `frameExited[depth]`：一旦 exit 自身抛错、或 enter 抛错
        // （宿主契约 = 该帧不出现于 exit 序列，见 [callInto]），[unwindScopes] 都不会再按
        // funcStack[depth] 把旧帧 exit 第二次；两道守卫的配对口径见 [unwindScopes]。
        // spec: ProcessState.cs:428-458 —— 原版也是先 ScopeIn、再 functionList.Add(call)，
        // 登记（并入栈）发生在进入成功之后；ProcessState.cs:298-311 的 IsJump 帧 Return
        // 时移除自身并递归 Return。
        val old = funcStack[depth]
        funcStack[depth] = FRAME_NONE
        frameExited[depth] = true
        if (old >= 0) host.onFunctionExit(old)
        lastCallPc = pc
        try {
            host.onFunctionEnter(funcIndex)
        } finally {
            lastCallPc = -1
        }
        frameExited[depth] = false
        funcStack[depth] = funcIndex
        retStrStack[depth] = isStrRet
        frameSlotCount = callee.slotCount
        // 尾跳原地换帧：水位**不动**（还是本帧的水位），但旧函数体压下的中间值必须作废 ——
        // 目标函数看到的必须是空栈，否则旧帧的残留值会被当成自己的操作数。
        spI = opBaseI[depth]
        spS = opBaseS[depth]
        return callee.entryPc
    }

    /**
     * 尾调目标被引擎屏蔽（[VmHost.isStubbedFunction]）时的收尾。
     *
     * 被屏蔽函数语义 = 空体 `RETURN 0` ⇒ 「尾调它」等价于「本帧立刻返回 0 / `""`」：
     * 走与 [_RET] 完全相同的退栈路径（[leaveFrame] / [leaveFrameStr]），变量层作用域、
     * 调用方返回槽、返回点 pc 都按原样处理。
     *
     * `depth == 0` 时「本帧返回」等于整个 run 结束 —— 顶层帧不在此处 leave（见 [leaveFrame]
     * 的"只在 depth>0 调用"约束），故返回哨兵 [STUB_TAIL_RETURN]，由 `_CALL` /
     * `_CALL_BY_NAME` 的调用点转成 run 的正常返回。
     */
    private fun stubTailReturn(isStrRet: Boolean): Int =
        if (depth == 0) STUB_TAIL_RETURN else if (isStrRet) leaveFrameStr(null) else leaveFrame(0L)

    /**
     * 拆当前帧并把返回值写回调用方 —— [_RET]（long 通道）与 [StmtOutcome.Stop] **共用**
     * （契约 C4）。**只在 `depth > 0` 时调用**：顶层帧不在此处 leave，由 [run] 的 finally
     * 统一兜底（[ScopeLifecycleTest] 锁的 7 例 enter/exit 配对以此为前提，改动前先跑它）。
     *
     * 顺序约束（不可调换）：
     * 1. 先恢复调用方的 frameBase/frameSlotCount 再写 `retDst`（返回槽是**调用方**的槽）；
     * 2. 先回卷操作数栈（读的是**本帧**水位，必须在 `depth--` 之前）再拆帧；
     * 3. 先 `depth--` 再 [VmHost.onFunctionExit]：exit 自身抛错（变量层栈不配对）时，
     *    finally 从 `depth` 起兜底，不会对这一帧二次 leave。
     *
     * @return 返回点 pc（= 调用指令的下一条）。
     */
    private fun leaveFrame(value: Long): Int = leaveFrameCore { dst ->
        if (!SlotAddr.isNone(dst)) setInt(dst, value)
    }

    /**
     * [leaveFrame] 的字符串通道版（合同 M3-A §3 A7）：`_RETS` 离开帧时写 `setStr`。
     * [value] = null（`_RETS` a=NONE，原版 `ReturnF(null)`）写**空串** —— 消费侧
     * `SuperUserDefinedMethodTerm.GetStrValue` 对非 `SingleStrTerm` 统一得 `""`
     * （SuperUserDefinedMethodTerm.cs:33-38）。
     */
    private fun leaveFrameStr(value: String?): Int = leaveFrameCore { dst ->
        if (!SlotAddr.isNone(dst)) setStr(dst, value ?: "")
    }

    /** 拆帧骨架（两条返回通道共用：顺序约束只有一份实现，见 [leaveFrame] KDoc）。 */
    private inline fun leaveFrameCore(writeDst: (Int) -> Unit): Int {
        val dst = retDstStack[depth]
        val leaving = funcStack[depth]
        val returnPc = retPc[depth]
        frameBase = frameBaseStack[depth]
        frameSlotCount = frameSlotStack[depth]
        // 操作数栈回卷到本帧水位线：被调帧压了多少都随帧销毁，调用方的栈面逐位复原
        // （包括它自己在调用点之前压下的中间值）。必须在 depth-- 之前读本帧的水位。
        spI = opBaseI[depth]
        spS = opBaseS[depth]
        depth--
        writeDst(dst)
        host.onFunctionExit(leaving)
        return returnPc
    }

    // ------------------------------------------------------------------
    // 栈码组（202..243，T2-21 / T2-22）
    //
    // 为什么单独一个方法而不是在主循环里内联 42 个分支：[execute] 已经是几百行的巨型方法
    // （JIT/ART 对超大方法有编译上限，越过就整段回退解释执行 —— 那是把所有指令一起拖慢）。
    // 每次栈码指令一次方法调用，换来的是主循环体积不变；而栈码本身已经把
    // 「池查 + 递归虚分派」换成了「弹栈 → 算 → 压栈」，这点调用开销是它省下来的零头。
    // ------------------------------------------------------------------

    /**
     * 执行一条栈码指令，返回**下一条** pc（跳转类返回目标 pc）。
     *
     * 语义逐条照抄 `core.expr` 的对应分支（CONTRACT-T2 §4：不许重写语义）：
     * 整型算术/比较对 [execute] 的 `_ADD`…`_GE`，字符串比较对 `strCompare`
     * （含 `>=`/`<=` 两处官方 bug），`_STK_SREPEAT` 对 `Binary.getStrValue` 的三条检查，
     * 变量读与自增自减对 `VarRef` / `Unary` / `UnaryAfter`（下标只求值一次 ⇒ 走 `plusInt`）。
     *
     * 错误一律经 [errorAt] 抛 CodeEE，文案与 `ExprEvalException` 逐字相同 —— `_EVAL_*`
     * 路径把 ExprEvalException 包成 CodeEE 时用的也是同一个 message，两条路径的报错
     * 因此逐字一致（T2-53 文案对拍的前提）。
     */
    private fun execStackOp(op: Int, a: Int, b: Int, c: Int, pc: Int): Int {
        when (op) {
            // ---- 压栈 / 栈操作 ----
            Opcode._STK_PUSH_IMM -> {
                pushInt(program.constant(a)); return pc + 1
            }

            Opcode._STK_PUSH_STR -> {
                pushStr(program.string(a)); return pc + 1
            }

            Opcode._STK_DUP_I -> {
                pushInt(peekInt()); return pc + 1
            }

            Opcode._STK_DROP -> {
                popInt(); return pc + 1
            }

            Opcode._STK_DROP_S -> {
                popStr(); return pc + 1
            }

            // ---- 变量 ----
            Opcode._STK_PUSH_VAR_I -> {
                pushInt(readVarInt(a, b, pc)); return pc + 1
            }

            Opcode._STK_PUSH_VAR_S -> {
                pushStr(readVarStr(a, b, pc)); return pc + 1
            }

            Opcode._STK_PLUS_VAR_I -> {
                pushInt(plusVarInt(a, b, c, pc)); return pc + 1
            }

            Opcode._STK_STORE_VAR_I -> {
                storeVarInt(op, a, b, pc); return pc + 1
            }

            Opcode._STK_STORE_VAR_S -> {
                storeVarStr(op, a, b, pc); return pc + 1
            }

            // ---- 整型算术（口径 = _ADD…_NEG，i64 回绕） ----
            Opcode._STK_ADD -> {
                val r = popInt(); val l = popInt(); pushInt(l + r); return pc + 1
            }

            Opcode._STK_SUB -> {
                val r = popInt(); val l = popInt(); pushInt(l - r); return pc + 1
            }

            Opcode._STK_MUL -> {
                val r = popInt(); val l = popInt(); pushInt(l * r); return pc + 1
            }

            Opcode._STK_DIV -> {
                // 先判除数（= Expr 的求值序：右操作数先取），再判 .NET 唯一会溢出的整除。
                val r = popInt()
                if (r == 0L) throw errorAt("除数为 0", pc)
                val l = popInt()
                if (l == Long.MIN_VALUE && r == -1L) {
                    throw errorAt("除算溢出：-9223372036854775808 / -1", pc)
                }
                pushInt(l / r); return pc + 1
            }

            Opcode._STK_MOD -> {
                val r = popInt()
                if (r == 0L) throw errorAt("除数为 0", pc)
                // `long.MinValue % -1` 在 C# 与 JVM 都得 0，不复刻溢出（同 _MOD）。
                pushInt(popInt() % r); return pc + 1
            }

            Opcode._STK_NEG -> {
                pushInt(-popInt()); return pc + 1
            }

            Opcode._STK_BITAND -> {
                val r = popInt(); val l = popInt(); pushInt(l and r); return pc + 1
            }

            Opcode._STK_BITOR -> {
                val r = popInt(); val l = popInt(); pushInt(l or r); return pc + 1
            }

            Opcode._STK_BITXOR -> {
                val r = popInt(); val l = popInt(); pushInt(l xor r); return pc + 1
            }

            Opcode._STK_SHL -> {
                // C# 与 JVM 都按低 6 位取移位计数（`shl`/`shr` 的 Int 重载即此口径）。
                val r = popInt(); val l = popInt(); pushInt(l shl r.toInt()); return pc + 1
            }

            Opcode._STK_SHR -> {
                val r = popInt(); val l = popInt(); pushInt(l shr r.toInt()); return pc + 1
            }

            Opcode._STK_BITNOT -> {
                pushInt(popInt().inv()); return pc + 1
            }

            // ---- 整型比较（结果 0/1） ----
            Opcode._STK_EQ -> {
                val r = popInt(); val l = popInt(); pushInt(if (l == r) 1L else 0L); return pc + 1
            }

            Opcode._STK_NE -> {
                val r = popInt(); val l = popInt(); pushInt(if (l != r) 1L else 0L); return pc + 1
            }

            Opcode._STK_LT -> {
                val r = popInt(); val l = popInt(); pushInt(if (l < r) 1L else 0L); return pc + 1
            }

            Opcode._STK_LE -> {
                val r = popInt(); val l = popInt(); pushInt(if (l <= r) 1L else 0L); return pc + 1
            }

            Opcode._STK_GT -> {
                val r = popInt(); val l = popInt(); pushInt(if (l > r) 1L else 0L); return pc + 1
            }

            Opcode._STK_GE -> {
                val r = popInt(); val l = popInt(); pushInt(if (l >= r) 1L else 0L); return pc + 1
            }

            // ---- 字符串比较（Ordinal；`>=`/`<=` 是官方 bug，见 specs/verify-notes.md §1） ----
            Opcode._STK_SEQ -> {
                val r = popStr(); pushInt(if (popStr().compareTo(r) == 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SNE -> {
                val r = popStr(); pushInt(if (popStr().compareTo(r) != 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SLT -> {
                val r = popStr(); pushInt(if (popStr().compareTo(r) < 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SLE -> {
                // spec: OperatorMethodManager.cs `LessEqualStrStr` 判的是 c < 0（相等泄漏）。
                val r = popStr(); pushInt(if (popStr().compareTo(r) < 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SGT -> {
                val r = popStr(); pushInt(if (popStr().compareTo(r) > 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SGE -> {
                // spec: OperatorMethodManager.cs `GreaterEqualStrStr` 判的是 c < 0（方向整个反了）。
                val r = popStr(); pushInt(if (popStr().compareTo(r) < 0) 1L else 0L); return pc + 1
            }

            // ---- 字符串 ----
            Opcode._STK_CONCAT -> {
                val r = popStr(); pushStr(popStr() + r); return pc + 1
            }

            Opcode._STK_SREPEAT -> {
                // 栈面：s（下）n（上）；`n * s` 与 `s * n` 由发码方归一到同一形态。
                // 三条检查的次序与文案 = Expr.Binary.getStrValue 的 MULT 分支。
                val n = popInt()
                val s = popStr()
                if (n < 0L) throw errorAt("负数($n)不能与字符串相乘", pc)
                if (n >= 10000L) throw errorAt("重复次数($n)不能大于等于 10000", pc)
                pushStr(if (s.isEmpty() || n == 0L) "" else s.repeat(n.toInt()))
                return pc + 1
            }

            Opcode._STK_I2S -> {
                pushStr(popInt().toString()); return pc + 1
            }

            // ---- 控制（短路 / 三目） ----
            Opcode._STK_JZ_POP -> {
                // `&&` 右位：左操作数为 0 ⇒ 结果 0，右侧一次都不执行。
                if (popInt() == 0L) {
                    pushInt(0L)
                    return pc + 1 + a
                }
                return pc + 1
            }

            Opcode._STK_JNZ_POP -> {
                // `||` 右位：左操作数非 0 ⇒ 结果 1，右侧一次都不执行。
                if (popInt() != 0L) {
                    pushInt(1L)
                    return pc + 1 + a
                }
                return pc + 1
            }

            Opcode._STK_JZ_KEEP -> {
                // 三目：条件只求值一次，出栈后按真假分叉（不压任何值）。
                return if (popInt() == 0L) pc + 1 + a else pc + 1
            }

            // ---- 落槽 ----
            Opcode._STK_POP_SLOT_I -> {
                val v = popInt()
                setInt(a, v)
                return pc + 1
            }

            Opcode._STK_POP_SLOT_S -> {
                val v = popStr()
                setStr(a, v)
                return pc + 1
            }

            else -> throw errorAt("未实现的栈码指令 ${Opcode.nameOf(op)}", pc)
        }
    }

    /**
     * 弹出 b 个下标，装配成 [VariableReader] 要求的 `LongArray`。
     *
     * 维数是**裸操作数**，合法域 `0..[MAX_VAR_DIM]`：本移植的变量只有 1/2/3 维
     * （`VariableSizes` 的 IsArray1D/2D/3D 并集、`VariableStore` 的 `名字@1/2/3` 键空间），
     * ERB 语法到 3 维为止。不挡的话畸形值（如 0x7FFFFFFF）会在 `LongArray(b)` 上直接
     * OOM —— 与槽越界同一档「字节码不完整」，显式 [CodeEE] 优于裸崩。
     *
     * 栈面次序：`idx0` 先压、`idx(b-1)` 在栈顶（= 源码里的书写序），故倒序回填。
     * b == 0 复用共享空数组 —— 与 [com.eratw.port.core.expr.VarRef] 的 `NO_INDICES` 同口径，
     * 绝大多数全局/角色变量读因此零分配。
     */
    private fun popIndices(dim: Int, pc: Int): LongArray {
        checkVarDim(dim, pc)
        if (dim == 0) return NO_INDICES
        val idx = LongArray(dim)
        for (i in dim - 1 downTo 0) idx[i] = popInt()
        return idx
    }

    /**
     * 维数守卫（**不弹任何东西**）。
     *
     * 单独拆出来是给 `_STK_STORE_VAR_*` 用的：那里的校验必须发生在**第一次弹出之前**，
     * 否则畸形维数会让栈面先少掉一个值再抛错，调试时会被误判成字节码配平问题。
     * （至于先弹值还是先弹下标，由各指令自己的栈效应决定 —— 见 [storeVarInt]。）
     */
    private fun checkVarDim(dim: Int, pc: Int) {
        if (dim < 0 || dim > MAX_VAR_DIM) {
            throw errorAt("栈码变量指令的维数非法($dim)：合法范围 0..$MAX_VAR_DIM", pc)
        }
    }

    /** `_STK_PUSH_VAR_I`：读整型变量（下标已由 [popIndices] 弹出）。 */
    private fun readVarInt(strIdx: Int, dim: Int, pc: Int): Long {
        val indices = popIndices(dim, pc)
        val vars = evalContextOrThrow(pc).variables
        val name = program.string(strIdx)
        return try {
            if (vars is VariableHandles) {
                val h = varHandle(vars, strIdx, name, wantStr = false)
                if (h != VariableHandles.NONE) vars.readIntH(h, indices) else vars.readInt(name, indices)
            } else {
                vars.readInt(name, indices)
            }
        } catch (e: ExprEvalException) {
            throw errorAt(e.message ?: "表达式求值失败", pc)
        }
    }

    /** `_STK_PUSH_VAR_S`：读字符串变量。 */
    private fun readVarStr(strIdx: Int, dim: Int, pc: Int): String {
        val indices = popIndices(dim, pc)
        val vars = evalContextOrThrow(pc).variables
        val name = program.string(strIdx)
        return try {
            if (vars is VariableHandles) {
                val h = varHandle(vars, strIdx, name, wantStr = true)
                if (h != VariableHandles.NONE) vars.readStrH(h, indices) else vars.readStr(name, indices)
            } else {
                vars.readStr(name, indices)
            }
        } catch (e: ExprEvalException) {
            throw errorAt(e.message ?: "表达式求值失败", pc)
        }
    }

    /** `_STK_PLUS_VAR_I`：读改写一体（= `plusInt`，下标只求值一次）。 */
    private fun plusVarInt(strIdx: Int, dim: Int, delta: Int, pc: Int): Long {
        val indices = popIndices(dim, pc)
        val vars = evalContextOrThrow(pc).variables
        val name = program.string(strIdx)
        return try {
            if (vars is VariableHandles) {
                val h = varHandle(vars, strIdx, name, wantStr = false)
                if (h != VariableHandles.NONE) vars.plusIntH(h, indices, delta.toLong())
                else vars.plusInt(name, indices, delta.toLong())
            } else {
                vars.plusInt(name, indices, delta.toLong())
            }
        } catch (e: ExprEvalException) {
            throw errorAt(e.message ?: "表达式求值失败", pc)
        }
    }

    /**
     * `_STK_STORE_VAR_I`：`idx0..idx(b-1) value → ∅`（值是**栈顶**）。
     *
     * 弹出次序 = 「先查维数 → 再弹值 → 最后弹下标」，三者都不可换位：
     * * **查维数必须在第一次弹出之前**（见 [checkVarDim]），畸形维数不留半截栈面；
     * * **值必须先弹**：值与下标同在 int 栈上，值在栈顶。反过来（先 `peek` 值再弹下标）
     *   会把值当成 `idx(b-1)`、把真正的 `idx0` 当成要写的值——`A[2] = 7` 会静默写成
     *   `A[7] = 2`，而 `dim == 0` 时又恰好正确，是最难在真机上定位的一类错。
     */
    private fun storeVarInt(op: Int, strIdx: Int, dim: Int, pc: Int) {
        val vars = evalContextOrThrow(pc).variables
        val writer = vars as? VariableWriter ?: throw noWriteChannel(op, pc)
        checkVarDim(dim, pc)
        val value = popInt()
        val indices = popIndices(dim, pc)
        val name = program.string(strIdx)
        try {
            if (vars is VariableHandles) {
                val h = varHandle(vars, strIdx, name, wantStr = false)
                if (h != VariableHandles.NONE) {
                    vars.writeIntH(h, name, indices, value)
                    return
                }
            }
            writer.writeInt(name, indices, value)
        } catch (e: ExprEvalException) {
            throw errorAt(e.message ?: "表达式求值失败", pc)
        }
    }

    /**
     * `_STK_STORE_VAR_S`：`idx0..idx(b-1) s → ∅`（字符串在 String 栈顶，下标在 int 栈）。
     *
     * 值在下标之外的另一组栈上（[opStackS] vs [opStackI]），两者的弹出次序因此无关语义；
     * 仍按 `I` 形态统一成「查维数 → 弹值 → 弹下标」，让两条指令的失败时点（先报维数、
     * 再报下溢）保持一致。
     */
    private fun storeVarStr(op: Int, strIdx: Int, dim: Int, pc: Int) {
        val vars = evalContextOrThrow(pc).variables
        val writer = vars as? VariableWriter ?: throw noWriteChannel(op, pc)
        checkVarDim(dim, pc)
        val value = popStr()
        val indices = popIndices(dim, pc)
        val name = program.string(strIdx)
        try {
            if (vars is VariableHandles) {
                val h = varHandle(vars, strIdx, name, wantStr = true)
                if (h != VariableHandles.NONE) {
                    vars.writeStrH(h, name, indices, value)
                    return
                }
            }
            writer.writeStr(name, indices, value)
        } catch (e: ExprEvalException) {
            throw errorAt(e.message ?: "表达式求值失败", pc)
        }
    }

    /**
     * 变量写通道缺失时的固定失败。
     *
     * [VariableReader] 只读；写入是独立能力（[VariableWriter]）。宿主若只提供了读口
     * （测试假实现、只读快照），栈码写入必须**显式失败**而不是绕过抽象去 cast 具体实现——
     * 后者会把「宿主没接这条能力」变成一次 ClassCastException 或一次静默的空写。
     */
    private fun noWriteChannel(op: Int, pc: Int): CodeEE =
        errorAt(
            "栈码指令 ${Opcode.nameOf(op)} 需要可写的变量存储（宿主的变量层未实现 VariableWriter）",
            pc,
        )

    /**
     * 取 `(当前函数, 变量名)` 的句柄；首次解析后写进 [varHandleTables]。
     *
     * 缓存键 `strIdx * 2 + wantStr`：字符串池下标加类型位。`resolveInt` 与 `resolveStr`
     * 现在解析到同一个条目，但把类型位放进键里，将来两者若分叉（比如按句柄做类型检查）
     * 不会把 int 句柄误用到字符串指令上。
     *
     * 解析不出（[VariableHandles.NONE]）时**不入缓存**：未定义变量在后续执行里可能因为
     * 迟到的 `#DIM` 装载而变成可解析，缓存住「解析不出」会把它永久钉死。解析不出的路径
     * 本来就必然抛错，多解析一次的代价没有意义。
     */
    private fun varHandle(vars: VariableHandles, strIdx: Int, name: String, wantStr: Boolean): Int {
        if (vars !== varHandleOwner) {
            varHandleOwner = vars
            varHandleTables = null
        }
        val fi = funcStack[depth]
        if (fi < 0) return if (wantStr) vars.resolveStr(name) else vars.resolveInt(name)
        val tables = varHandleTables ?: arrayOfNulls<IntArray>(program.functions.size).also { varHandleTables = it }
        val key = strIdx * 2 + if (wantStr) 1 else 0
        val cached = tables[fi]
        if (cached != null) {
            var i = 0
            val n = cached.size
            while (i < n) {
                if (cached[i] == key) return cached[i + 1]
                i += 2
            }
        }
        val h = if (wantStr) vars.resolveStr(name) else vars.resolveInt(name)
        if (h == VariableHandles.NONE) return h
        val next = if (cached == null) IntArray(2) else cached.copyOf(cached.size + 2)
        next[next.size - 2] = key
        next[next.size - 1] = h
        tables[fi] = next
        return h
    }

    /**
     * 求值语境（与 `_EVAL_*` 的守卫同文案）。
     *
     * 只给栈码的变量指令用：`_EVAL_*` 是热路径，那里保留内联的同一句检查，避免多一次调用。
     */
    private fun evalContextOrThrow(pc: Int): EvalContext =
        host.evalContext ?: throw errorAt("EVAL 需要 EvalContext，宿主未提供", pc)

    /**
     * 压入 int 操作数栈。
     *
     * 溢出抛 CodeEE、**不扩容**（理由见 [opStackI] 的字段注释）。文案是 T2-22 的验收口径，
     * 改动前先看 CONTRACT-T2 §2.3：`操作数栈溢出（上限 1024 槽）`。
     */
    private fun pushInt(v: Long) {
        if (spI >= OP_STACK_DEPTH) {
            throw errorAt("操作数栈溢出（上限 $OP_STACK_DEPTH 槽）", lastPc)
        }
        opStackI[spI++] = v
    }

    /**
     * 弹出 int 操作数栈顶。
     *
     * 下界是**本帧水位线**而不是 0：越过水位线读到的会是调用方的中间值（静默算错），
     * 必须显式失败。字节码不配平属编译期缺陷，与槽越界同一档（见 [frameSlotIndex]）。
     */
    private fun popInt(): Long {
        if (spI <= opBaseI[depth]) throw errorAt("操作数栈下溢（本帧没有可弹出的整数）", lastPc)
        return opStackI[--spI]
    }

    /** 读 int 栈顶（不弹）。 */
    private fun peekInt(): Long {
        if (spI <= opBaseI[depth]) throw errorAt("操作数栈下溢（本帧没有可复制的整数）", lastPc)
        return opStackI[spI - 1]
    }

    /** 压入 String 操作数栈（溢出文案与 [pushInt] 同一条）。 */
    private fun pushStr(v: String) {
        if (spS >= OP_STACK_DEPTH) {
            throw errorAt("操作数栈溢出（上限 $OP_STACK_DEPTH 槽）", lastPc)
        }
        opStackS[spS++] = v
    }

    /** 弹出 String 栈顶（null 归一为 ""，与 [getStr] 同口径）。 */
    private fun popStr(): String {
        if (spS <= opBaseS[depth]) throw errorAt("操作数栈下溢（本帧没有可弹出的字符串）", lastPc)
        return opStackS[--spS] ?: ""
    }

    /**
     * 刷新并返回复用给语句层的 [StmtEnv]（契约 C4：functionKey/pc/line/functionName）。
     * `functionKey` 用大写归一，与 proc 层 `FuncMeta.key` / `VariableStore` 作用域键同口径
     * （spec: Config.cs:33 `StrComper = OrdinalIgnoreCase`）。
     */
    private fun envAt(pc: Int): StmtEnv {
        val f = program.functions[funcStack[depth]]
        // PERF（2026-10-26）：走 FunctionInfo.upperKey 的惰性缓存，替代每条语句一次的
        // `name.uppercase()`（ART 上逐字符 ICU 分配）；值与原实现相同（同一大写结果）。
        stmtEnv.functionKey = f.upperKey
        stmtEnv.pc = pc
        stmtEnv.line = program.lineOf(pc)
        stmtEnv.functionName = f.name
        return stmtEnv
    }

    /**
     * 校验语句层给出的同函数内跳转目标（契约 C4）：必须落在
     * `[当前函数 entryPc, 下一函数 entryPc)`（最后一个函数以指令流长度为上界）。
     * 越界 = 语句层算错 pc 或想跨函数跳，一律 CodeEE——跨函数跳会把调用栈的返回点
     * 留在别的函数体里，帧模型直接坏掉，绝不能静默接受。
     */
    private fun checkTransferTarget(target: Int, pc: Int): Int {
        val fi = funcStack[depth]
        val f = program.functions[fi]
        val end = transferEnds[fi]
        if (target < f.entryPc || target >= end) {
            throw errorAt("语句层跳转目标 pc 越界($target)：不在函数 ${f.name} 的 ${f.entryPc}..${end - 1} 区间内", pc)
        }
        return target
    }

    /**
     * 函数下标 → 其 pc 区间上界（下一个函数的 entryPc；最后一个函数 = 指令流长度）。
     * 依赖"函数按 entryPc 连续排布"这一编译器不变量；对乱序的函数表也成立（按 entryPc 排序后取后继）。
     * 空函数（与后继共享 entryPc）区间为空，其中的 Transfer 必然越界——正确的严格行为。
     */
    private val transferEnds: IntArray by lazy {
        val funcs = program.functions
        val order = funcs.indices.sortedBy { funcs[it].entryPc }
        val ends = IntArray(funcs.size)
        for (k in order.indices) {
            ends[order[k]] = if (k + 1 < order.size) funcs[order[k + 1]].entryPc else program.instructionCount
        }
        ends
    }

    // ------------------------------------------------------------------
    // 槽访问：bit31 = 全局槽，否则帧内槽
    //
    // 四入口都带**下标守卫**（CMP-55 夹具②）：槽号是字节码操作数，越界属
    // 「字节码不完整」（编译器 bug / 裸拼装错误），不能把引擎的数组越界
    // （AIOOBE）冒到宿主，也不能让它静默读写相邻帧。判据与 [checkRetDst]
    // 同口径：全局槽看 `globalInts/globalStrs` 长度，帧内槽看**当前帧**的
    // [frameSlotCount]（不是物理数组容量 —— `frameInts` 是跨帧共享的 2 倍增长
    // 数组，`frameBase + raw` 落在数组内不代表落在本帧内）。
    // ------------------------------------------------------------------

    fun getInt(raw: Int): Long = if (SlotAddr.isGlobal(raw)) {
        globalInts[globalSlotIndex(raw)]
    } else {
        frameInts[frameBase + frameSlotIndex(raw)]
    }

    fun setInt(raw: Int, value: Long) {
        if (SlotAddr.isGlobal(raw)) {
            globalInts[globalSlotIndex(raw)] = value
        } else {
            frameInts[frameBase + frameSlotIndex(raw)] = value
        }
    }

    fun getStr(raw: Int): String = if (SlotAddr.isGlobal(raw)) {
        globalStrs[globalSlotIndex(raw)] ?: ""
    } else {
        frameStrs[frameBase + frameSlotIndex(raw)] ?: ""
    }

    fun setStr(raw: Int, value: String) {
        if (SlotAddr.isGlobal(raw)) {
            globalStrs[globalSlotIndex(raw)] = value
        } else {
            frameStrs[frameBase + frameSlotIndex(raw)] = value
        }
    }

    /**
     * 全局槽操作数 → 数组下标，越界抛 [CodeEE]；文案沿用 [checkRetDst] 的槽形式。
     * 只在 `SlotAddr.isGlobal(raw)` 为真时调用（[SlotAddr.index] 已去掉 bit31）。
     */
    private fun globalSlotIndex(raw: Int): Int {
        val idx = SlotAddr.index(raw)
        if (idx >= globalInts.size) {
            throw errorAt("槽越界（全局槽 G$idx，全局槽共 ${globalInts.size} 个）", lastPc)
        }
        return idx
    }

    /**
     * 帧内槽操作数 → 帧内偏移，越界抛 [CodeEE]。
     *
     * 下界同时拦 [SlotAddr.NONE]（-1，`SlotAddr.isGlobal` 判否）：它只该出现在
     * 「可缺省」操作数上，调用方未判 `isNone` 就往下传属字节码/接线错误，
     * 不能让它变成 `frameBase - 1` 的跨帧读写。
     */
    private fun frameSlotIndex(raw: Int): Int {
        if (raw < 0 || raw >= frameSlotCount) {
            throw errorAt("槽越界（${SlotAddr.format(raw)}，当前帧只有 $frameSlotCount 槽）", lastPc)
        }
        return raw
    }

    /** 构造一条带当前位置（函数/行/pc）的脚本错误。 */
    private fun errorAt(message: String, pc: Int): CodeEE =
        CodeEE(message, currentFunctionName(), program.lineOf(pc), pc)

    /**
     * 帧槽扩容：把两套槽数组都扩到至少 [need] 槽。
     *
     * [need] 与倍增容量一律走 Long：帧基址 + 帧大小是两个 Int 相加（深递归的帧基址 +
     * 巨帧槽数），在 Int 上会回绕成负数或 0；`cap shl 1` 在接近 2^30 时同样会溢出成
     * 负数/0，`while (cap < need)` 就永远不成立 —— 不报错地 100% CPU 挂死。
     *
     * 上限 [MAX_FRAME_SLOTS] 是本移植防溢出的硬闸，不是原版语义：原版帧栈是动态
     * `List`（ProcessState.cs:458 `functionList.Add(call)`），没有容量上限；这里显式
     * 失败（CodeEE）优于静默回绕/挂死。
     */
    private fun ensureFrameSlots(need: Long) {
        if (need < 0) {
            throw CodeEE("帧槽需求为负($need)：槽数回绕", currentFunctionName(), -1, lastPc)
        }
        if (need <= frameInts.size.toLong()) return
        if (need > MAX_FRAME_SLOTS) {
            throw CodeEE(
                "帧槽需求超出上限($need > $MAX_FRAME_SLOTS)：疑似损坏的帧槽数或过深递归",
                currentFunctionName(),
                -1,
                lastPc,
            )
        }
        var cap = frameInts.size.toLong()
        while (cap < need) cap = cap shl 1
        frameInts = frameInts.copyOf(cap.toInt())
        frameStrs = frameStrs.copyOf(cap.toInt())
    }

    private fun currentFunctionName(): String? {
        // [unwindScopes] 把 depth 收成 -1 之后本帧表已无内容：funcStack[-1] 是数组越界
        // （`IntArray` 的 AIOOBE，非 CodeEE），不能让它从 `errorAt` / 诊断口冒出去。
        if (depth < 0) return null
        return program.functions.getOrNull(funcStack[depth])?.name
    }

    /**
     * 当前帧函数的大写键（= [StmtEnv.functionKey] 口径；无帧 = null）。
     *
     * 供宿主在**任意指令**上定位源码位置（当前行通道 FN-05：`_EVAL_*` 等非语句指令
     * 没有 [StmtEnv]，只能由宿主按 [lastPc] + 本属性 + `Program.lineOf` 现算）。
     * 返回值每次调用分配一个大写串：只给低频诊断路径用，不要进热循环。
     */
    val currentFunctionKey: String?
        get() {
            // KDoc 承诺「无帧 = null」：`run()`（含异常退出）的 finally 里 [unwindScopes] 会把
            // depth 置回 -1，此时 `funcStack[-1]` 会抛 `ArrayIndexOutOfBoundsException`
            // （非 [CodeEE]，且与承诺相反）。宿主在 run 返回/抛出后读取本属性是合法用法。
            if (depth < 0) return null
            return program.functions.getOrNull(funcStack[depth])?.name?.uppercase()
        }

    /**
     * 抛点就地还原的脚本调用链（入口帧 → 当前帧，见 [StepLimitExceeded.callChain]）。
     *
     * 只在异常构造**之前**有效：[unwindScopes] 会把 depth 置回 -1、funcStack 残留已弹出帧，
     * 事后从外部反射拿不到链（2026-10-21 真机撞闸排查的教训）。
     */
    private fun currentCallChain(): String? {
        if (depth < 0) return null
        val names = (0..depth).mapNotNull { program.functions.getOrNull(funcStack[it])?.name }
        return if (names.isEmpty()) null else names.joinToString(" → ")
    }

    private companion object {
        const val INITIAL_SLOTS = 64

        /**
         * 段落墙钟闸的检查间隔掩码（[VmConfig.maxRunMillis]）：每 65536 步读一次时钟。
         *
         * 为什么不是每步：`System.nanoTime()` 是系统调用（本机约 20-25 ns），
         * 按 500 ns/指令 的稳态成本算会直接吃掉 4% 的吞吐。65536 步 ≈ 33 ms，
         * 相对秒级阈值足够细，且与 [steps] 的段落计数天然对齐（让出时归零，检查点从 0 起）。
         */
        const val RUN_MILLIS_CHECK_MASK = 0xFFFFL

        /**
         * 语句取指 L1 备忘的槽数（2 的幂，见 [stmtMemoKey] 的取舍依据）。
         *
         * 64 覆盖一个典型 `FOR` 环体的工作集；再大就要多付 L1 缓存压力而命中率不涨
         * （`bindOf` 的 512 → 4096 扩容实测**反而慢 12%**，教训同源）。
         */
        const val STMT_MEMO_SLOTS = 64
        const val STMT_MEMO_MASK = STMT_MEMO_SLOTS - 1

        /**
         * 操作数栈容量（槽）：long 栈与 String 栈**各自**这么多。
         *
         * 1024 的依据：单条表达式的嵌套深度由编译器挡在 64（CONTRACT-T2 §2.4 T2-31），
         * 一条表达式最多压 ~64 槽；1024 是它的 16 倍余量，同时低于「深递归 + 深表达式」
         * 叠加后仍能容忍的量级（500 层递归每层留 40 槽 = 20000 会溢出，那是设计上要
         * **显式失败**的场景，不是要扩容的场景 —— 见 [opStackI] 的字段注释）。
         */
        const val OP_STACK_DEPTH = 1024

        /**
         * 栈码变量指令的维数上界 = 3（依据见 [popIndices]）。
         * 放 companion 而不是写死在判据里：文案与 T2-31 降级器的发码约束要引同一个数。
         */
        private const val MAX_VAR_DIM = 3

        /** 0 下标变量的共享空数组（只读；[popIndices] 复用，避免每次读变量都分配）。 */
        private val NO_INDICES = LongArray(0)

        /**
         * [funcStack] 的"未登记"哨兵（值 -1）：不在 `program.functions` 下标范围内，
         * [unwindScopes] 见它即跳过 [VmHost.onFunctionExit]（见 [jumpInto]）。
         */
        const val FRAME_NONE = -1

        /**
         * [jumpInto] 的"run 到此结束"哨兵（[Int.MIN_VALUE]）：尾调目标被引擎屏蔽
         * （[VmHost.isStubbedFunction]）且当前是顶层帧时，被调函数等价于空体 `RETURN 0`，
         * 「本帧返回」= 整个 run 结束 —— 这在 [jumpInto] 里表达不了，故回传本值，
         * 由 `_CALL` / `_CALL_BY_NAME` 的调用点转成 run 的正常返回（见 [stubTailReturn]）。
         *
         * 合法返回点 pc 恒 ≥ 0，且不与 [FRAME_NONE]（-1）/ [SlotAddr.NONE]（-1）混淆。
         */
        const val STUB_TAIL_RETURN = Int.MIN_VALUE

        /**
         * 帧槽容量上限（槽数）：只在 [ensureFrameSlots] 的容量倍增里当防溢出的硬闸，
         * 不参与任何脚本语义。64M 槽 = 512 MiB long 槽 + 256 MiB 引用槽，已是单进程
         * 不该达到的量级；超出说明装载数据损坏或递归失控，抛 CodeEE 比挂死好。
         */
        const val MAX_FRAME_SLOTS = 1 shl 26

        /** [_PRINT_INT] 等的标志位：bit0 = 末尾换行。 */
        const val FLAG_NEWLINE = 1

        /*
         * 调用位 b 的位常量与语句操作码上界**不在这里声明**：单一真源在 [Opcode]
         * （`Opcode.TRY_FLAG`/`JUMP_FLAG`/`CALLF_FLAG`/`STR_RET_FLAG`/`ARG_COUNT_MASK`
         * 与 `Opcode.MAX_STATEMENT_OPCODE`）。CMP-57 之前本文件、`OpcodeTable.kt` 与
         * `ErbProgramCompiler.kt` 各存一套字面量，改一处漏一处即产生「同一条指令两种解释」。
         */
    }
}
