package com.eratw.port.core.code

import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.err.DIVIDE_BY_ZERO_TEXT
import com.eratw.port.core.err.MULTIPLY_10K_TO_STR_TEMPLATE
import com.eratw.port.core.err.MULTIPLY_NEGATIVE_TO_STR_TEMPLATE
import com.eratw.port.core.err.erbDisplayLine
import com.eratw.port.core.err.notDefinedLabelNameText
import com.eratw.port.core.err.renderMultiplyToStrLimit
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
import com.eratw.port.core.lexer.LangVariant
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.sem.FlatStmtSource

/**
 * VM 运行期配置。
 *
 * ## 死循环保护 = 墙钟 + 问玩家（ENGFIX-3 · 2026-11-05，本类的判据主次从这里开始定死）
 * 原版 Emuera 与 skia 魔改版的死循环保护**只有一条中止路径，而且由玩家按下**：
 * 每 10000 行看一次墙钟（skia `Process.cs:989-992`），越过 `Config.InfiniteLoopAlertTime`
 * （`ConfigData.cs:75` 注册缺省 5000 ms，`Config.cs:101`/`:337` 读取，置 0 = 完全关闭）
 * 就弹框问「中断处理并强制结束吗？」（`Process.cs:470-489`）；玩家选「否」⇒ 行数与计时
 * 双双归零接着跑（:486-487）；只有选「是」才抛 `CodeEE`（:484）；没有可用 UI
 * （`view.Enabled == false`，:478）时连告警都不发、更不中止。
 * ⇒ **原版从不因「跑了多少条指令」中止脚本。**
 *
 * 本移植的这条判据就是 [infiniteLoopAlertTimeMs] + [VmHost.askInfiniteLoop]，
 * 计数单位回到**行**、判据是**墙钟**、中止权在玩家。
 *
 * ## 下面两个预算不是官方判据，是移植层兜底
 * 移植侧此前自造了一条按步数的硬闸（`02d7faaa` + `1c93a898`，四刀调参 5M→20M→60s→10 亿，
 * 后一刀推翻前一刀），把合法重负载判成死循环（真机读旧档弹「启动失败」那类报告）。
 * 本波把它们**降级**为 [LoopGuardCause.DeviceBudget]（触因位见 [StepLimitExceeded.guardCause]）：
 * * [maxStepsPerRun] —— 段落**指令数**预算。单线程 VM 一旦真死循环就是整机无响应，
 *   兜底必须留在某个量级上；生产值 = 正常游戏永不触及的极端量级
 *   （见 `GameSession.PRODUCTION_MAX_STEPS_PER_RUN`），装置/清扫工具传的是自己的预算。
 *   段落边界 = [VmHost.yieldBoundaryCount] 跨过一次让出（输入等待返回、`AWAIT` 逐帧让出）；
 *   原版对应点是 `DoScript` 每次重进（`Process.cs:415-418` 的 `startTime.Restart()` +
 *   `state.lineCount = 0`，被 `EmueraConsole.cs:1094-1097` 的「收到输入才重跑」驱动）与
 *   `EmueraConsole.Await` 里的 `UpdateCheckInfiniteLoopState()`（`EmueraConsole.cs:840-849`）。
 *   本移植的输入等待是阻塞式（一次 run 跨几十上百次输入），故段落口径与原版等价。
 * * [maxRunMillis] —— 段落**墙钟**预算（撞线即终止，不问玩家）。同样是装置/诊断旋钮。
 *
 * 两个预算都**不参与**死循环判定，只在显式传值时兜底；默认值见各自字段。
 */
data class VmConfig(
    /**
     * **单段落指令数预算**（段落口径：相邻两次让出之间执行的指令数；跨过
     * [VmHost.yieldBoundaryCount] 的一次边界后从 0 重算）。
     *
     * 这一维**不是原版判据**：原版没有「每 run 指令上限」这种东西
     * （`Process.cs:438-458` 的边界是 `console.IsRunning`，要输入就交还控制台），
     * 死循环判定归 [infiniteLoopAlertTimeMs]。本字段只是移植层的装置预算 / 极端兜底
     * （撞线抛 [StepLimitExceeded]，guardCause = [LoopGuardCause.DeviceBudget]），
     * 生产取值必须是正常游戏永不触及的量级，且需 ADR-005 例外登记（本波交付报告已提）。
     *
     * 段落边界与原版同源：原版的 `startTime.Restart()` + `state.lineCount = 0` 落在
     * `DoScript` 每次重进（`Process.cs:415-418`，由 `EmueraConsole.cs:1094-1097` 的
     * 「收到输入才重跑一次」驱动）与 `EmueraConsole.Await` 里的
     * `UpdateCheckInfiniteLoopState()`（`EmueraConsole.cs:840-849`，全工程唯一调用点）。
     * 本移植的输入等待是阻塞式（一次 run 跨几十上百次输入），故让出点即段落点。
     *
     * 默认 100 万步是裸 VM 装置的人为保守值；真实游戏"一帧"可能远大于此（ADR-002 提到
     * 2.6M 行的量级）。生产与装置的**实际取值**分别见
     * [com.eratw.port.core.proc.GameSession.PRODUCTION_MAX_STEPS_PER_RUN]（真机 `GameHost`，10 亿）
     * 与 [com.eratw.port.core.proc.GameSession.DEFAULT_MAX_STEPS_PER_RUN]（会话缺省，2000 万）。
     */
    val maxStepsPerRun: Long = 1_000_000L,
    val maxCallDepth: Int = 10_000,
    /**
     * **单段落墙钟预算**（毫秒，段落口径同 [maxStepsPerRun]）；0（缺省）= 不限。
     *
     * 存在理由与原版无关，只剩一条：装置/诊断要一个「这一段跑太久就掐掉」的确定性旋钮
     * （`SweepCore` 的台账、桌面 vmdbg 的 sweep）。**生产必须传 0**：墙钟是**设备速度**的
     * 函数，同一条合法链在慢机上必撞 —— 2026-10-29 取的 60 秒在玩家机上只够 1.33 亿条指令，
     * 而第 47 日起床链实测 2.90 亿条（BOARD 2026-10-30）⇒ 误杀。这正是「把合法重负载
     * 判成死循环」那起报告的发生方式，别再把它抬回生产。
     *
     * 原版那一维是 [infiniteLoopAlertTimeMs]（**问玩家**，不自动中止），两者不是同一件事。
     *
     * 检查点每 65536 步做一次（`System.nanoTime` 是系统调用，不能每条指令都读）：
     * 在 500 ns/指令 的机器上约 33 ms 一次，对秒级预算足够细。
     */
    val maxRunMillis: Long = 0L,
    /**
     * **死循环告警的墙钟阈值**（毫秒）= 原版 `Config.InfiniteLoopAlertTime`。
     *
     * 判据与出处（skia 与官方逐字同文，只差变量名）：
     * * `Process.cs:989-992` —— `if (Config.InfiniteLoopAlertTime > 0 && state.lineCount % 10000 == 0)`
     *   才检查 ⇒ **大于 0 是开关**，每 10000 行采样一次；
     * * `Process.cs:472-476` —— `startTime.ElapsedMilliseconds < Config.InfiniteLoopAlertTime` 直接返回；
     * * `ConfigData.cs:75` 注册缺省 **5000**（取值域 0..100000，本仓
     *   `core/config/ConfigSpec.kt:132` 已同值登记）；`Config.cs:101` 静态字段、`:337` 读取。
     *
     * 撞线之后**不是异常**：VM 经 [VmHost.askInfiniteLoop] 问宿主（宿主要弹框就弹框），
     * 答「继续」⇒ 行数与计时归零重跑，答「退出」才抛 [StepLimitExceeded]
     * （guardCause = [LoopGuardCause.PlayerChoseExit]，文案 = 官方
     * `Lang.Error.SelectExitInfiniteLoopMB`），宿主没有通道 ⇒ 按 skia 的
     * `view.Enabled == false` 分支什么都不做、绝不中止。
     *
     * **阈值来源必须单一**：本字段只是**载体**，唯一真源 = 会话生效配置里的
     * `ConfigKey.InfiniteLoopAlertTime` 条目（`GameSession` 装配期读一次填进来，
     * 口径与 `PRINTCの文字数` 那批条目一致 —— 设置页改这项由下一局会话吃到）。
     * 本类**不抄第二份缺省**：这里唯一的缺省是 0 = 关闭，原版那个 5000 只记在
     * `ConfigSpec.kt:132` 的条目注册处，取值链路上不允许出现第二把尺。
     */
    val infiniteLoopAlertTimeMs: Long = 0L,
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
     * **后台暂停闸**（跨线程）：返回 true 时，VM 在**每条语句执行之前**调用 [awaitResume]
     * 阻塞，直到宿主恢复或 VM 线程被中断。
     *
     * 语义：暂停不消耗步数、不改变执行顺序、不推进任何计时（步数闸/墙钟闸的段落计数与
     * 计时起点都不动）；恢复后从**原语句**继续执行。暂停位由宿主线程写、VM 线程读。
     *
     * 缺省 false = 不暂停：既有测试宿主 / 纯求值宿主 / golden 装置行为零变化。
     */
    fun backgroundPaused(): Boolean = false

    /**
     * 等待后台恢复：仅在 [backgroundPaused] 为 true 时由 VM 在主语句循环里调用。
     *
     * 实现方应以有限超时（如 250 ms）轮询暂停位，**线程被 interrupt 或会话关闭时立即返回**
     * ——GameHost.close 会 interrupt VM 线程，返回后 VM 侧看到中断位即退出本次 run。
     * 缺省空实现 = 不阻塞。
     */
    fun awaitResume() {}

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
     * [VmConfig.maxStepsPerRun] 的段落计数与 [VmConfig.infiniteLoopAlertTimeMs] 的
     * 行数/墙钟段落**一起**从 0 重算。原版的段落复位点有两处且成对
     * （`startTime.Restart()` + `state.lineCount = 0`）：`DoScript` 每次重进
     * （`Process.cs:415-418`）与 `UpdateCheckInfiniteLoopState()`
     * （`Process.cs:464-468`，全工程只被 `EmueraConsole.cs:849` 的 `Await` 调用一次）。
     * 本移植等待是阻塞式，故把这两类落点合成同一个读数；判定权在墙钟 + 玩家，
     * 不再有「攒满段落计数就判死」那条（ENGFIX-3）。
     *
     * 用累计计数而不是一次性布尔：主 VM 与嵌套 VM（[deriveNestedVm]）共用同一宿主时，
     * 一次性标志会被先轮询到的一方吃掉、另一方漏掉边界；读数单调，双方各自都能看到每一次变化。
     * 缺省 0 = 该宿主不存在让出等待（测试装置 / 纯求值宿主），行为与改造前完全一致。
     */
    fun yieldBoundaryCount(): Long = 0

    /**
     * 死循环告警的**宿主回调切口**（ENGFIX-3 · 2026-11-05）：墙钟越过
     * [VmConfig.infiniteLoopAlertTimeMs] 时，VM 在下一个 10000 行采样点上问宿主一次。
     *
     * 对应原版 `Process.cs:470-489`（skia；官方 `:493-512` 同文）：那里是
     * `Dialog.ShowPrompt(标题, 正文)` 的 YesNo 弹框。core 层纯 JVM、禁 Android API
     * （AGENTS.md 铁律 4），所以弹框只能长成这个样子 —— **本方法就是那个框的替身**，
     * 画不画框、用什么线程画、能不能画，全归宿主。
     *
     * ## 契约
     * * **调用线程** = VM 线程（[VirtualMachine.run] 所在线程）。实现方要上 UI 就自己
     *   `runBlocking`/`await` 到玩家答完为止；**必须阻塞返回**，VM 不等异步答复。
     *   口径与 [com.eratw.port.core.host.InputSink.waitInput] 的阻塞式等待一致 ——
     *   玩家盯着卡住的界面按「继续」本来就是原版的观感（`Thread.Sleep` + `DoEvents`，
     *   `EmueraConsole.cs:840-856`）。
     * * **可重入**：实现方在本回调里**不得**再触发同一 VM 实例的 [VirtualMachine.run]
     *   （重入 run 会抛「同一实例重入」那条 CodeEE），也不得推进存档/读档这类会拆帧的动作；
     *   要做的只是「问一句、拿答案」。
     * * **抛出**：宿主抛出会一路穿出 `run` —— 与原版一致（原版的框抛错也被 `DoScript`
     *   顶层 catch 兜住打到屏上，`Process.cs:454-455`）。所以实现方要么给出答案，
     *   要么就别画框（返回 [InfiniteLoopAnswer.NoUi]），不要把异常当退出码用。
     * * **频率**：每 10000 行、且只在墙钟已越过阈值时最多问一次；玩家答「继续」后
     *   行数与计时双双归零，下一个 10000 行边界才会再问。宿主内部再自行节流即可。
     * * **没有通道**（缺省实现 [InfiniteLoopAnswer.NoUi]）≙ skia 的 `view.Enabled == false`
     *   分支：什么都不做、绝不中止。既有装置 / 测试宿主 / 未接线的生产路径因此零变化。
     */
    fun askInfiniteLoop(alert: InfiniteLoopAlert): InfiniteLoopAnswer = InfiniteLoopAnswer.NoUi

    /**
     * 当前函数键对应的**源文件显示名**（死循环告警正文的第 0 位，原版
     * `currentLine.Position.Value.Filename`，skia `Process.cs:481`）。
     *
     * VM 只有 `pc` 与行号表，函数键 → 源文件名的映射在装载侧车
     * （`FuncMeta.position`）里，因此这一路只能问宿主。
     *
     * 缺省 null = 宿主没有这份信息（裸 Program / 测试宿主），VM 把正文的文件位回落成
     * 函数书写名（见 [InfiniteLoopAlert.displaySource]），不编造文件名。
     * 只在告警真正触发时调用一次（每 10000 行至多一次），不是热路径。
     */
    fun infiniteLoopSourceName(functionKey: String?): String? = null

    /**
     * 指令循环的**周期检查点**（长页自救口）：每 [SHUTDOWN_CHECK_MASK] + 1 条指令一次，
     * 与宿主关闭闸同一处、同频次（真机 ≈30 ms 一发），热路径不额外加比较。
     *
     * 为什么需要它（2026-11-01 收件箱）：OPPO PLD110 / 0.2.34 那一轮轨迹是
     * `turn 2ms`、`turn 2342ms`、`turn 15192ms`、`turn 58515ms` 交替 —— 同一类页面平时
     * 2 ms、堆贴顶时 58 秒，玩家感受正是「玩着玩着卡死、点屏幕没反应」（长页期间没有
     * 等待方，提交按原版 Running 早退丢弃 = `EmueraConsole.cs:1374-1377`）。而宿主所有
     * 自救动作（回收可重解的图、钉轨迹）此前**只挂在等待点**（`onTurnReady`）：一页算
     * 一分钟，那一分钟里一次都跑不到，越慢越回收不到、越回收不到越慢。本钩子把这个
     * 落点从「页尾」挪到「每 ≈30 ms 的指令流里」，且**只在两条语句之间**触发 ——
     * 检查点在取指之前，一条语句（含 `GDRAWG` 这类整段写缓冲的语句）不会被从中截断。
     *
     * 契约：实现方必须**极便宜**（自带节流）且**不得抛出**；抛出会直接变成脚本错误终局。
     * 缺省空实现 = 装置 / 测试宿主不受影响，与加钩子前逐条一致。
     * 嵌套 VM（[deriveNestedVm]）共用宿主 ⇒ 也会触发，频率只会更高，节流归实现方。
     */
    fun pressureCheckpoint() {}

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
     * 运行期字符串调用（`TRYCALLSTR`/`TRYCCALLSTR`，操作码 [_CALL_STR]，契约 C-CALL-STR-HOST@1）：
     * 把 `a` 槽的整串原文解析成「目标函数下标 + 逐位求值后的实参」。
     *
     * 实现方**不得抛异常**：一切失败经 [RuntimeCallResolution.Failed]/[RuntimeCallResolution.NotFound]/
     * [RuntimeCallResolution.Blank] 折回，由 VM 按 `b` 的 TRY 位决定上抛 `CodeEE` 还是降级
     * （`specs/isa.yaml:2542-2543`）。缺省返回 [RuntimeCallResolution.NotFound]（`name = raw`：
     * 缺省宿主没有解析能力），使既有测试宿主 / 纯装置行为零变化（等同「目标未命中」，
     * 非 TRY 时文案随 `raw`）。
     */
    fun resolveRuntimeCall(raw: String): RuntimeCallResolution = RuntimeCallResolution.NotFound(raw)

    /**
     * 运行期调用点的逐位实参元数据（契约 C-CALL-STR-HOST@1）：VM 在进入被调帧
     *（[VirtualMachine.callInto] / [VirtualMachine.jumpInto]）**之前**调一次，把
     * 「实参位 → 类型桶槽号」的映射交给宿主，从而让 `bindCallArgs` 获得按位置绑定。
     *
     * [args] 的每个元素 `bucket` 与 VM 写入**调用方当前帧**实参槽 R0.. 的槽号同源；
     * 缺省空实现 = 既有测试宿主不受影响。
     */
    fun onRuntimeCallArgs(pc: Int, args: List<CallArg>) {}

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

/**
 * 运行期字符串调用的一个实参（求值结果，契约 C-CALL-STR-HOST@1）。
 *
 * 只区分 int 桶 / str 桶两支 —— 与帧槽的两套数组（`frameInts`/`frameStrs`）同构；
 * 调用方按 [RuntimeCallResolution.Ready] 的顺序把 int 值写进 int 桶、str 值写进 str 桶。
 */
class RuntimeCallArg(val isStr: Boolean, val intValue: Long, val strValue: String?)

/**
 * 运行期字符串调用的解析结果（契约 C-CALL-STR-HOST@1）。
 *
 * VM 依此按 [Opcode._CALL_STR] 的 `b` 标志做 TRY/TRYC 降级（`specs/isa.yaml:2542-2543`）。
 */
sealed interface RuntimeCallResolution {
    /** 命中：目标在 `Program.functions` 的下标 + 求值后的实参（按书写位置序）。 */
    class Ready(val targetIndex: Int, val args: List<RuntimeCallArg>) : RuntimeCallResolution

    /** 空串/全空白：不调用，顺序执行下一条（`specs/isa.yaml:2542`）。 */
    object Blank : RuntimeCallResolution

    /**
     * 目标函数未命中（宿主 `resolveFunctionIndex` 返回 null）。
     *
     * [name] = **解析后的函数名**（不是整串原文），供 VM 生成官方 NotDefinedFunc 文案
     * `没有找到指定的函数"@{0}"`（`{0}` = 函数名；与 [_CALL_BY_NAME] 分支的 `"@$name"` 同口径，
     * 见 `Vm.kt` 的 `_CALL_BY_NAME` 非 try 分支）。缺省宿主无解析能力，交 `raw`。
     */
    class NotFound(val name: String) : RuntimeCallResolution

    /** 实参解析或求值失败；[message] = 上报文案（非 TRY 时作为 `CodeEE` 的 message）。 */
    class Failed(val message: String) : RuntimeCallResolution
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
 *   （STR_RET_FLAG）声明要字符串，leaveFrame 改走 `setStr`；4.981 的浮点方法走 [_RETF]
 *   （a = 表达式池下标），值不进槽而进 [floatResult] 寄存器，调用位按原版消费侧容错得 0/""。
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
    /**
     * functionDispatch 表（M2 dispatch ISA，ABI-3 候选；`specs/erbc-format.md` §5.6a）：
     * 物理函数下标 → 执行入口物理下标（切面链头 ?: 覆盖胜者 ?: 自身），来源 =
     * [SymbolCatalog.dispatch]（链接产物 [com.eratw.port.core.patch.LinkedProgram]）。
     *
     * - **null = 无 Mod 产物快路径**：`_CALL` 直调物理下标，不查表（性能闸：无覆盖/
     *   无切面的产物装配点不传表——恒等表只是语义基准，不是运行成本）；
     * - 表长度必须等于函数表长度（构造期 require，防喂错表）；
     * - `b` bit27 DIRECT 置位时绕过表直调（CALL_SUPER 回填专用，查表会把被覆盖的
     *   前实现弹回链尾胜者成环）；越界下标原样透传给 [callInto]/[jumpInto]，
     *   报既有的「函数下标越界」——行为与无表逐位一致；
     * - 名字通道（`_CALL_BY_NAME` / `_CALL_STR` / 表达式 FuncCall）不经本表：宿主
     *   `resolveFunctionIndex`/`resolveMethodIndex` 返回的已是 dispatch 后的执行入口
     *   （分派幂等不变量），装配点接 [SymbolCatalog.bindings] 胜者，不在此重查。
     */
    val functionDispatch: IntArray? = null,
) {
    // ---- 帧槽存储（可增长） ----
    private var frameInts = LongArray(INITIAL_SLOTS)
    private var frameStrs = arrayOfNulls<String>(INITIAL_SLOTS)

    // ---- 全局槽存储（与帧槽隔离） ----
    private val globalInts: LongArray = sharedGlobals?.first ?: LongArray(maxOf(program.globalSlots, 1))
    private val globalStrs: Array<String?> = sharedGlobals?.second ?: arrayOfNulls(maxOf(program.globalSlots, 1))

    init {
        // dispatch 表必须覆盖整张函数表（喂错表 = 取错执行入口，构造期拦）。
        // 幂等不变量（dispatch[dispatch[i]] == dispatch[i]）由 SymbolCatalog.build
        // 内建自检保证，这里不重复校验（单一真源）。
        val dispatch = functionDispatch
        if (dispatch != null) {
            require(dispatch.size == program.functions.size) {
                "functionDispatch 长度（${dispatch.size}）必须等于函数表长度（${program.functions.size}）"
            }
        }
    }

    /**
     * 取指页缓冲（PERF-PAGEFETCH 2026-10-30 起**缺省不用**）。
     *
     * 缺省走 [CodeSource.page]：映射来源的页缓存命中时把**常驻数组本体**直接交给 VM，
     * 一次跨页零拷贝、零分配。只有 `-Deratw.perf.noCachedFetch=true` 才退回 2026-10-27
     * 第五批的形态（VM 独占一块常驻缓冲，**每跨一页**重搬 16 KiB 再逐字小端解释），
     * 留这条退路只为同窗口交替 A/B 能复现当时的读数。
     *
     * 退回形态为什么慢：`_CALL`/`_RET` 本来就在「调用者页 ↔ 被调页」之间来回切
     * （[MappedCodeSource] 的 cache 注释记的就是这条病，页缓存当年为它而建），
     * 单块缓冲等于每次调用都重填两遍。体感上就是「有的页秒开、有的页等很久」——
     * 换页越密的选项越慢，纯顺序执行的页几乎不受影响。
     */
    private var pageBuf: IntArray? = null

    /** 退回「每跨一页重填 VM 缓冲」形态的开关（缺省走页缓存）；`-Deratw.perf.noCachedFetch=true` 打开。 */
    private val useFillPageFetch: Boolean = java.lang.Boolean.getBoolean("eratw.perf.noCachedFetch")

    /** 取（必要时建）本 VM 的页缓冲；走页缓存、或来源不支持原地填充时返回 null。 */
    private fun pageBufferOf(source: CodeSource): IntArray? {
        if (!useFillPageFetch || !source.supportsFillPage) return null
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
     * 最近一次**异常离栈**时的帧表截断拷贝（下标序与 [funcStack] 一致：最外层在前），
     * 由 [run] 的 catch 在 [unwindScopes] 之前取一次；读口是 [funcStackAtThrow]。
     *
     * 为什么要有它：`run()` 的 finally 在异常离栈时把 `depth` 收成 -1，宿主拿到异常写
     * 错误报告时实时帧表已经没有内容 —— 2026-11-01 端点现存 100 份报告里 93 份的这一节
     * 只剩采集器自己的 Java 栈，CodeEE 只留下最内层那一个函数名，「哪个口上链走到这里」
     * 永远读不出。抛点当场镜像一次是唯一的取用处（同 [VmHost.frameSnapshotLines] 的理由）。
     *
     * 每次 [run] 起跑清零 ⇒ 这份快照只属于本次 run，不会把上一局的链挂到下一条报告。
     */
    private var funcStackAtThrowSnapshot: IntArray? = null

    /**
     * 本次 `run` 是否正在执行（不可重入闸，见 [run] 的入口判据）。
     *
     * 只在 `run` 的进/出各写一次 ⇒ 主循环零成本；它不是跨线程状态（ADR-003 单线程 VM），
     * 故不加 `@Volatile`：宿主的跨线程入口（`GameHost.close`）只碰中断位与暂停位。
     */
    private var running = false

    /**
     * 「本页函数流水」记录面挂载点（观察面，缺省 null = 零行为）：由宿主在装配期挂
     * [PageFunctionTrail] 实例，VM 只在**建立帧的三处** `funcStack[...]` 写入点顺带
     * [PageFunctionTrail.record]（[run] 的入口帧、[callInto]、[jumpInto]，与
     * [frameExited] KDoc 的复位点一一对应）；退帧不写、页边界清零由
     * [com.eratw.port.core.proc.QueueInputSink] 的等待点结算处执行。
     *
     * null 判断每进帧一次（进帧频率 ≪ 指令频率，实测均摊见交付报告）；不新增分支到
     * 指令主循环。@Volatile 与 [com.eratw.port.core.proc.FunctionProbe] 同口径：
     * 宿主线程赋值、VM 线程消费。
     */
    @Volatile
    var pageTrail: PageFunctionTrail? = null

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
     * 本次 [run] 的**顶层帧**（`depth == 0`）最后一次 [_RETS] 的字符串值
     * （合同 M3-A §3 A7；`ProcessRuntime.invokeUserMethod` / `executeMethod` 从这里取
     * `#FUNCTIONS` 的返回值）。每次 [run] 开始重置为 null，且只有顶层 `_RETS` 写它。
     *
     * `depth > 0` 的内层字符串方法返回**不经这里**：它只经 [leaveFrameStr] 写调用方
     * 返回槽（表达式位降级产物 `#EXPR_RETS#<k>` 读的就是那个槽，
     * `ErbProgramCompiler.kt:3254-3259`）。若内层帧也写本寄存器，掉尾方法
     * （编译器尾声 `_RET NONE`，`ErbProgramCompiler.kt:350`）没有自己的 `_RETS` 去覆盖，
     * 宿主就会把**内层方法的返回值**当成掉尾方法的返回值；而原版掉尾是
     * `ReturnF(null)`、消费侧得 `""`（`ProcessState.cs:289-295` +
     * `SuperUserDefinedMethodTerm.cs:33-38`）。真机表现即
     * `GETMETHS(@"COM448_DISPLAY", "Null")` 让 448 按钮显示成上一次调用过的地名串。
     */
    var stringResult: String? = null
        private set

    /**
     * 最近一次 [_RETF]（浮点返回通道，4.981 路次 [g2-returnf-float]）的浮点值；
     * null = 本次 run 一条 `_RETF` 都没执行过。
     *
     * 存在理由：本移植的帧槽只有整型列与字符串列（[frameInts]/[frameStrs]），**没有浮点格**
     * —— 浮点值在移植侧只活在表达式求值器与 VariableStore 的浮点列里。所以浮点方法的返回值
     * 不经返回槽交回，而是走这条与 [stringResult] 并列的寄存器：
     * `_RETF` 在**被调帧内**求值 → 记进本 VM 实例 → 拆帧。与 [stringResult] 只认顶层帧
     * 不同，本寄存器由**任意帧**的 `_RETF` 写入；但浮点方法的每个出帧点（含掉尾兜底
     * `_RETF NONE`，`ErbProgramCompiler.kt:350`）都由编译器发 `_RETF`，所以掉尾必然覆盖，
     * 不会把内层或上一次的值留成交回宿主的结果。
     *
     * 每次 [run] 开始重置为 null（一次没有 `_RETF` 的执行不得读到上一次的残留）；
     * 重入方法通道（`ProcessRuntime.invokeUserMethod`）读的是**派生 VM** 上的这一个，
     * 与外层 VM 互不污染。
     *
     * 语义依据（fork-src qirchen421/emuera.sk@48494165）：`RETURNF` 把值塞进
     * `ProcessState.MethodReturnValue`（`Process.State.cs:760/:762-789`，**不写 RESULTF**），
     * 消费侧 `UserDefinedMethodTerm.cs:19-38` 按运行时项类型取值、不符即静默零值。
     */
    var floatResult: Double? = null
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
     * TEMP(perf-probe)：非 null 时逐指令按**当前帧函数下标**累加，回答「12.08M 条指令里
     * 各 ERB 函数各占多少」。数组下标 = `program.functions` 下标；名字由调用方按同下标解析。
     *
     * 生产恒为 null ⇒ 热路径多一次字段读 + 一次可预测的空判；与 [opHistogram] 同一取证口径，
     * 不改执行语义。TODO(verify)：是否长期保留作诊断口由组长裁决。
     */
    @JvmField
    var fnHistogram: IntArray? = null

    /**
     * TEMP(perf-probe)：非 null 且 `printOpMask[op]` 为 true 时，把该指令的**执行序号**写进
     * [printStepLog]（容量由调用方分配），用来量「最长无打印窗口」这条保活判据。
     *
     * 打印事件稀疏，故日志只在空判命中后写；生产恒 null。
     */
    @JvmField
    var printOpMask: BooleanArray? = null

    /** TEMP(perf-probe)：打印事件步号日志（见 [printOpMask]）；生产恒 null，只写不读。 */
    @JvmField
    var printStepLog: LongArray? = null

    /** TEMP(perf-probe)：[printStepLog] 已写条数（超容量后停止记录，不覆盖旧事件）。 */
    @JvmField
    var printStepCount: Int = 0

    /**
     * TEMP(perf-probe)：跨主 VM 与派生 VM（[deriveNestedVm]）共享的**全局指令序号**计数器
     * （单元素 `LongArray`，只由 VM 线程写）。非 null 时每条指令先自增，打印事件日志用它
     * 记序号 ⇒ 「最长无打印窗口」的步号与总指令数在嵌套表达式方法调用下仍同一坐标系。
     *
     * 生产恒 null；与其它 TEMP 归因字段同一条回滚口径。
     */
    @JvmField
    var globalStepCounter: LongArray? = null

    /**
     * **装置预算**（指令数）的抛点（**冷方法**，从主循环体里提出来；PERF 2026-10-27）。
     *
     * `StepLimitExceeded` 要构造 6 个参数，其中一个还是 `runCatching { host.frameSnapshotLines() }`
     * —— 这段代码内联在主循环体里，JIT 之后会让「全程序最热的几十个字节」明显变长。
     * 提到独立方法后，热路径只剩一次 `steps > maxSteps` 比较 + 一次（几乎不走的）调用，
     * 而抛错时的参数构造、行号解析、帧快照全部挪到冷路径。
     *
     * 语义零变化：`steps`/`pc`/`lineOf(pc)`/函数名/调用链/帧快照的来源与顺序逐字不变。
     *
     * **ENGFIX-3 的定位**：这里抛的是 [LoopGuardCause.DeviceBudget]（移植层预算 / 极端兜底），
     * 不是原版的死循环判定 —— 原版那条走 [checkInfiniteLoopAt]，判据是墙钟、中止权在玩家。
     */
    private fun stepLimitAt(pc: Int, steps: Long): Nothing = throw StepLimitExceeded(
        steps, program.lineOf(pc), pc, currentFunctionName(), currentCallChain(),
        runCatching { host.frameSnapshotLines() }.getOrDefault(emptyList()),
    )

    /**
     * **装置预算**（段落墙钟）的抛点（冷方法，理由同 [stepLimitAt]）。
     *
     * 与步数预算共用 [StepLimitExceeded]，但带上 `elapsedMs`：失败屏/台账据此区分
     * 「跑了太多指令」与「这一段跑太久」—— 两者都是**移植层预算**（成因
     * [LoopGuardCause.DeviceBudget]），原版两条都没有：原版只按墙钟**问玩家**，
     * 从不自己中止（`Process.cs:470-489`，见 [checkInfiniteLoopAt]）。
     */
    private fun timeLimitAt(pc: Int, steps: Long, elapsedMs: Long): Nothing = throw StepLimitExceeded(
        steps, program.lineOf(pc), pc, currentFunctionName(), currentCallChain(),
        runCatching { host.frameSnapshotLines() }.getOrDefault(emptyList()),
        elapsedMs,
    )

    /**
     * 死循环告警的**采样点**（冷方法，理由同 [stepLimitAt]）：组装现场 + 询问宿主整块挪出
     * 主循环体，热路径只留一次 `loopAlertMs > 0` 常量比较与一次行界比较。
     *
     * 对应 skia `Process.cs:470-489`（官方 `:493-512` 同文），三段守卫逐条对形：
     * * `:472-476` 墙钟不足阈值直接返回 —— 由调用方判掉，本方法只在已达阈值时进来；
     * * `:478` 「当前行存在且不是 `NullLine`」—— 移植侧只在语句 marker 上采样（marker 必有
     *   pc，行号由 `Program.lineOf` 给；非语句指令不是一次「行执行」，与原版 `NullLine`
     *   不采同义）；
     * * `:478` 的 `view.Enabled` —— 由 [VmHost.askInfiniteLoop] 的缺省答案
     *   [InfiniteLoopAnswer.NoUi] 承担：没接 UI 通道的宿主（装置、测试、未接线的生产路径）
     *   因此**什么都不做**，与原版窗口未创建时逐条一致。
     *
     * 文件名那一路（`:481` 的 `currentLine.Position.Value.Filename`）VM 自己没有 ——
     * 函数键到源文件名的映射在装载侧车里，经 [VmHost.infiniteLoopSourceName] 问宿主；
     * 宿主抛错或没有这份信息时回落成函数书写名（[InfiniteLoopAlert.displaySource]），
     * 不编造文件名、也不因为取不到名字而取消告警。
     */
    private fun checkInfiniteLoopAt(
        pc: Int,
        elapsedMs: Long,
        lines: Long,
        alertTimeMs: Long,
    ): InfiniteLoopAnswer = host.askInfiniteLoop(
        infiniteLoopAlertAt(pc, elapsedMs, lines, alertTimeMs, LangVariant.ZHS),
    )

    /**
     * 玩家选「退出」的抛点（冷方法）：这就是原版 `Process.cs:484` 的
     * `throw new CodeEE(Lang.Error.SelectExitInfiniteLoopMB.Text)` —— 文案走
     * [InfiniteLoopTexts]（逐字照抄原文/译文，不自造串），语言档用宿主画框时那一档，
     * 保证「玩家看到的框」与「屏上留下的那句」同语言。
     *
     * 类型仍是 [StepLimitExceeded]（它的语义自本波起 = 「玩家选择退出的墙钟告警」），
     * 成因位 [LoopGuardCause.PlayerChoseExit] 让宿主能把它按**可恢复终局**收口
     * （原版由 `DoScript` 顶层 catch 打到屏上、会话留着，`Process.cs:454-455`）。
     */
    private fun infiniteLoopExitAt(
        pc: Int,
        runSteps: Long,
        elapsedMs: Long,
        lines: Long,
        alertTimeMs: Long,
        textVariant: LangVariant,
    ): Nothing = throw StepLimitExceeded(
        steps = runSteps,
        line = program.lineOf(pc),
        pc = pc,
        funcName = currentFunctionName(),
        callChain = currentCallChain(),
        frameSnapshot = runCatching { host.frameSnapshotLines() }.getOrDefault(emptyList()),
        elapsedMs = elapsedMs,
        guardCause = LoopGuardCause.PlayerChoseExit,
        alert = infiniteLoopAlertAt(pc, elapsedMs, lines, alertTimeMs, textVariant),
    )

    /** 死循环告警的现场组装（[checkInfiniteLoopAt] 与 [infiniteLoopExitAt] 共用同一口径）。 */
    private fun infiniteLoopAlertAt(
        pc: Int,
        elapsedMs: Long,
        lines: Long,
        alertTimeMs: Long,
        textVariant: LangVariant,
    ): InfiniteLoopAlert = InfiniteLoopAlert(
        sourceName = runCatching { host.infiniteLoopSourceName(currentFunctionKey) }.getOrNull(),
        functionName = currentFunctionName(),
        // 正文第 1 位是**给人看的行号**：原版吃的是归一后的 1 基值（skia `Process.cs:481` /
        // 官方 `:504` 的 `currentLine.Position.Value.LineNo`，`ScriptPosition.cs:15-17` 在构造时
        // 就 `+1`），因此这里走全工程唯一的 0 基→1 基出口 [erbDisplayLine]，
        // 而 [StepLimitExceeded.line] 字段仍保持 0 基键面口径（与所有 CodeEE 一致）。
        lineNo = erbDisplayLine(program.lineOf(pc)).toLong(),
        linesExecuted = lines,
        elapsedMs = elapsedMs,
        alertTimeMs = alertTimeMs,
        textVariant = textVariant,
    )

    /**
     * 宿主关闭（`Thread.interrupt()`）的抛点（冷方法，理由同 [stepLimitAt]）。
     *
     * 与步数闸/墙钟闸**分开一句话**是刻意的：这两闸是「脚本自己跑太久」，会进失败屏与错误
     * 报告；本闸是「玩家/系统正在拆这一局」，`GameHost` 的 catch 按 `closed` 把它只当日志
     * （同 `InputSinkClosedException` 的既有口径），玩家不该看到一张「启动失败」。
     *
     * 异常类型仍用 [CodeEE]：它已经是宿主收口路径上唯一的「VM 主动退出」载体，
     * [com.eratw.port.core.proc.ProcessRuntime] 与既有暂停分支用的也是它（
     * `Vm.kt` 的 `执行被中断（宿主正在关闭）`）。消息逐字保留，测试与日志据此识别。
     *
     * 中断位**不清**：GameHost.close() 之后这条线程的职责就是尽快退出，任何后续阻塞点
     * （输入等待 / AWAIT）都应立刻看到它。
     */
    private fun shutdownInterruptedAt(pc: Int): Nothing =
        throw CodeEE("执行被中断（宿主正在关闭）", currentFunctionName(), program.lineOf(pc), pc)

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
     * [stringResult]，不受这条局部变量影响：它只由 `depth == 0` 的 `_RETS` 写入，
     * 故掉尾方法（尾声 `_RET NONE`）读回 null，宿主按原版 `ReturnF(null)` 得 `""`。
     *
     * @param entryFunctionIndex `program.functions` 下标；越界抛 [CodeEE]。
     * @return 本次 run 最后一条 [_RET] 的值（没有 `_RET` 时为 0）；顶层 [StmtOutcome.Stop] /
     *   [StmtOutcome.Unwind] 返回其 `value`。
     */
    fun run(entryFunctionIndex: Int): Long {
        if (entryFunctionIndex !in program.functions.indices) {
            throw CodeEE("函数下标越界($entryFunctionIndex)")
        }
        // **不可重入**（2026-10-31 稳定性波 L1）：本 VM 的全部帧状态（depth / frameBase /
        // 返回点栈 / 操作数栈水位 / funcStack）都是实例字段，一次 run 独占。宿主若在回调里
        // （print / executeStatement / 变量层）对**同一实例**再 run 一次，内层会把 depth
        // 复位成 0、在内层 finally 里把 depth 收成 -1，外层随后读 `funcStack[-1]`
        // （ArrayIndexOutOfBoundsException，非 CodeEE），或干脆带着被清空的帧表静默返回错值；
        // 外层入口帧的 onFunctionExit 也再不会配对调用（变量层作用域栈泄漏）。
        // 嵌套执行有专用通道：[deriveNestedVm] 造**另一个实例**，与这里不冲突。
        if (running) {
            throw CodeEE(
                "VM 不可重入：同一实例的 run 已在执行（嵌套执行须经 deriveNestedVm 派生的实例）",
            )
        }
        val entry = program.functions[entryFunctionIndex]
        depth = 0
        frameBase = 0
        frameSlotCount = entry.slotCount
        funcStack[0] = entryFunctionIndex
        // 进帧流水（观察面）：与 funcStack 建立写同点，null = 零行为；口径见 [pageTrail]。
        pageTrail?.record(entryFunctionIndex)
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
        floatResult = null
        funcStackAtThrowSnapshot = null

        // 入口函数也是一帧：作用域必须成对进入/离开（finally 统一兜底）。
        // [running] 的复位放在最外层 finally：unwindScopes 自己也可能抛（宿主 onFunctionExit
        // 异常），那一次抛出同样不得把实例永久钉在「运行中」。
        running = true
        try {
            host.onFunctionEnter(entryFunctionIndex)
            try {
                return execute(entry)
            } catch (t: Throwable) {
                // 抛点镜像必须在 finally 回卷之前（口径见 [funcStackAtThrowSnapshot]）。
                captureFuncStackAtThrow()
                throw t
            } finally {
                unwindScopes()
            }
        } finally {
            running = false
        }
    }

    /**
     * 把当前帧表的前 `depth + 1` 项镜像进 [funcStackAtThrowSnapshot]（诊断口，语义零改动）。
     *
     * 只在异常离栈那一次执行，且整份拷贝包在 [runCatching] 里：报告常在堆将尽时生成，
     * 镜像本身撞 OOM 也只能丢这一节读数，不许把原始异常换成一个别的。
     */
    private fun captureFuncStackAtThrow() {
        val d = depth
        if (d < 0) return
        val top = minOf(d + 1, funcStack.size)
        if (top <= 0) return
        runCatching { funcStackAtThrowSnapshot = funcStack.copyOf(top) }
    }

    /**
     * 最近一次异常离栈时的帧表镜像（下标序与实时 [funcStack] 一致，无快照 = null）。
     *
     * 公开只读口是给崩溃/失败屏采集侧用的（`ErrorReportCollector` 反射取它，改名要在
     * `ErrorReportVmStackTest` 的字段名哨兵同步）：本次 run 正常返回时它是 null。
     */
    fun funcStackAtThrow(): IntArray? = funcStackAtThrowSnapshot

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
    internal fun deriveNestedVm(host: VmHost, config: VmConfig): VirtualMachine {
        val nested = VirtualMachine(program, host, config, globalInts to globalStrs)
        // TEMP(perf-probe)：表达式方法调用走派生 VM（见 invokeUserMethod），动态归因若只挂
        // 主 VM 就会把 `GETMETH`/`CALLF` 目标整段漏掉。数组按引用共享；打印日志的写索引
        // 不跨 VM 合并（只覆盖主 VM 窗口，口径偏差见交付）。
        nested.opHistogram = opHistogram
        nested.fnHistogram = fnHistogram
        nested.printOpMask = printOpMask
        nested.printStepLog = printStepLog
        nested.globalStepCounter = globalStepCounter
        return nested
    }

    private fun execute(entry: FunctionInfo): Long {
        var pc = entry.entryPc
        // 段落计数：距上一次让出边界以来已执行的指令数 —— **装置预算**（maxStepsPerRun）用它。
        // 不跨越任何让出点的脚本里两个计数严格同步，闸行为与改造前逐条一致（M10-RUNB）。
        var steps = 0L
        // 段落墙钟起点（[VmConfig.maxRunMillis] 与 [VmConfig.infiniteLoopAlertTimeMs] 共用，
        // 段落口径与 [steps] 完全一致）：原版的死循环保护看的是时间（skia `Process.cs:472`
        // 的 `startTime.ElapsedMilliseconds`），而 `startTime` 只在 `UpdateCheckInfiniteLoopState()`
        // （:464-468，被 `EmueraConsole.Await` 调用，EmueraConsole.cs:849）、「玩家选择继续」
        // （:487）与每次 `DoScript` 重进（:417）时重启 ⇒ 度量边界同样是让出点。
        var segStartNanos = System.nanoTime()
        // 段落**行数**（ENGFIX-3 · 官方死循环闸的计数单位）：原版 `state.lineCount` 由
        // `ProcessState.ShiftNextLine` 等六处 `lineCount++` 逐**逻辑行**累加
        // （`ProcessState.cs:126/:132/:341/:365/:379/:460`），移植侧的同行单位 = 语句 marker
        // 派发次数（一条 ERB 语句行发一个 marker，`ErbProgramCompiler.kt:1034/:1105` 那一族；
        // FOR/REPEAT 的锚点另补一条步进 marker，`:1566/:1626` ⇒ 行数只可能略高于原版口径，
        // 采样因此更密，判据与阈值不变）。表达式求值走非语句指令，不计入行数。
        var loopLines = 0L
        // 下一次告警采样的行界（原版 `Process.cs:989` 的 `lineCount % 10000 == 0` 同义写法：
        // 从 0 起每满 10000 行一发；归零后重新从 10000 起算）。
        var nextLoopCheckLine = INFINITE_LOOP_CHECK_LINES
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
        // 取指（PERF-PAGEFETCH 2026-10-30）：缺省 `pageBuf == null` ⇒ 跨页时取 [CodeSource.page]，
        // 映射来源的页缓存命中就给常驻数组本体（零拷贝、零分配）。
        // pageBuf 只在 `-Deratw.perf.noCachedFetch=true` 的退路段里用（VM 独占常驻缓冲，换页只重填
        // 不重建数组 —— 2026-10-27 第五批为消掉「每换一页一个 IntArray(16 KiB)」而加，但它同时
        // 绕开了页缓存，等于把 `_CALL`/`_RET` 的换页成本全部付回来；读数见 [pageBuf] 的 KDoc）。
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
        // 装置预算的阈值：`config.maxStepsPerRun` 是 `config` 的只读 val，但每条指令都要穿过
        // 一层引用读一次字段；提到循环外对语义零影响（VmConfig 在 run 期间不可变）。
        val maxSteps = config.maxStepsPerRun
        // 段落墙钟阈值（0 = 不限）。同 [maxSteps] 提到循环外：`VmConfig` 在 run 期间不可变。
        val maxRunMillis = config.maxRunMillis
        // 死循环告警的阈值（= 原版 `Config.InfiniteLoopAlertTime`；0 = 整条判据关闭，
        // skia `Process.cs:989` 的开关位）。唯一真源是会话生效配置里的同名条目，
        // 本处只是把它读成一个循环外常量，不另立第二把尺（口径见 [VmConfig.infiniteLoopAlertTimeMs]）。
        val loopAlertMs = config.infiniteLoopAlertTimeMs
        // 段落墙钟要不要维护（暂停时长平移、让出边界重启起点）：两个用它的闸
        // （[maxRunMillis] 预算 / [loopAlertMs] 告警）任一启用就得维护。两处比较都在冷路径。
        val wallClockTracked = maxRunMillis > 0L || loopAlertMs > 0L
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
            // 宿主关闭闸（2026-10-31 玩家「切到 QQ 再回来启动失败」）：GameHost.close() 只能
            // `Thread.interrupt()`，而中断位此前**只在暂停分支里**被看（见下面 statements 段的
            // `backgroundPaused` 分支）—— 一段纯计算长链（地图重修的 CHARA_MOVEMENT 是
            // 1.2 亿~2.9 亿条指令量级，语句之间没有让出边界）根本不进那个分支，旧 VM 线程会
            // 带着整份 Program（真机 422 MiB）一直跑到链尾；Activity 被系统回收后重建，新一局
            // 的装载起点就撞上「上一局的内存还没释放」硬闸。
            //
            // 检查点与墙钟闸同频（每 65536 条指令一次，玩家机的 2.2M 指令/秒 ⇒ ≈30 ms）：
            // 热路径只多一次 `(steps and mask) == 0` 的常量比较；`isInterrupted` 是 native
            // 调用，不能每条指令都读。GameHost.close() 是**唯一**的 interrupt 来源，所以
            // 命中即「宿主正在关闭」，与暂停分支的退出语义同源（见 [shutdownInterruptedAt]）。
            if ((steps and SHUTDOWN_CHECK_MASK) == 0L) {
                if (Thread.currentThread().isInterrupted) shutdownInterruptedAt(pc)
                // 长页自救口（见 [VmHost.pressureCheckpoint]）：与关闭闸同点同频，缺省空实现。
                host.pressureCheckpoint()
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

            // TEMP(perf-probe)：函数级与打印事件归因（缺省 null，语义零改动）。
            val gsc = globalStepCounter
            val globalStep = if (gsc != null) {
                val v = gsc[0] + 1
                gsc[0] = v
                v
            } else {
                0L
            }
            val fnh = fnHistogram
            if (fnh != null && depth >= 0) {
                val frameIndex = funcStack[depth]
                if (frameIndex >= 0) fnh[frameIndex]++
            }
            val pmask = printOpMask
            if (pmask != null && pmask[op]) {
                val plog = printStepLog
                val pcount = printStepCount
                if (plog != null && pcount < plog.size) {
                    plog[pcount] = if (gsc != null) globalStep else runSteps
                    printStepCount = pcount + 1
                }
            }

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
                    if (r == 0L) throw errorAt(DIVIDE_BY_ZERO_TEXT, pc)
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
                    if (r == 0L) throw errorAt(DIVIDE_BY_ZERO_TEXT, pc)
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
                    // 判据与 [_JUMP_DYN] 逐条相同（同一张 `$标签` 表的两条入口）：
                    // 1. 下标必须落在 labelPcs 里 —— 两张平行数组由装载侧校验相等
                    //    （`ErbcWriter.kt:418-424`），但**内存直编**（`CodeBuilder.declareLabel`）
                    //    不校验，放行会让下面的取 pc 变成 `IndexOutOfBoundsException`（非 CodeEE）。
                    // 2. 取到的 pc 必须属于**本函数**（[checkTransferTarget]）—— 标签表里一条
                    //    越出本函数的 pc 会让当前帧跑进别的函数体，而返回点仍指向原调用者，
                    //    帧模型静默损坏（[_JUMP_DYN] 早已有这道守卫，这里原先漏了）。
                    // 两条都按「这个标签没有可用的跳转目标」处理：走官方 NotDefinedLabelName
                    // 文案（非 TRY 抛 / TRY 跳过本行），不比缺失标签多出第二种语义。
                    if (idx < 0 || idx >= f.labelPcs.size) {
                        // 官方 zhs 文案（`Lang.cs:2342` NotDefinedLabelName；reference 内嵌表 :540 /
                        // 语料根表 :513 同文）的模板**自带字面 `$`**：`当前函数中不存在指定的标签名"${0}"`。
                        // format 实参 = `text` 原文（`FunctionIdentifier.cs:3703`）——不大写化、不剥 `$`：
                        // 官方全链不剥**引用侧**的 `$`（`LexicalAnalyzer.cs:330-399`/`:1133-1147` +
                        // `ArgumentParser.cs:725-730`），而查表键恒不带 `$`（剥除只发生在定义行词法，
                        // `LogicalLineParser.cs:316-328` ⇒ `LabelDictionary.cs:339-348` 存的是净名）。
                        // 于是屏上原文 = `名"$TAIL"`；求值名自己带 `$` 时官方给 `名"$$TAIL"`，双 `$` 照抄不修。
                        // 旧形状是一条手抄字面量 `当前函数中不存在指定的标签名\"$raw\"` ——
                        // Kotlin 把 `${...}` 之外的 `$raw` 当插值、文案本体的 `$`（ERB 标签 sigil）
                        // 就此丢失；改走 err/LabelErrorText.kt 的单一真源即补回（ADR-005 连 `$` 复刻）。
                        if (!isTry) throw errorAt(notDefinedLabelNameText(raw), pc)
                        pc++
                    } else {
                        pc = checkTransferTarget(f.labelPcs[idx], pc)
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
                    // （合同 M3-A §3 A7）| bit27 DIRECT 直调标志（M2 dispatch ISA）；
                    // 历史字节码 bit27/bit28 为 0，行为不变。
                    val isJump = (b and Opcode.JUMP_FLAG) != 0
                    val isStrRet = (b and Opcode.STR_RET_FLAG) != 0
                    val argc = b and Opcode.ARG_COUNT_MASK
                    val target = resolveDispatch(a, b)
                    pc = if (isJump) {
                        val next = jumpInto(target, argc, isStrRet, pc)
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
                        callInto(target, argc, c, isStrRet, pc)
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

                Opcode._CALL_STR -> {
                    // 运行期字符串调用（245，契约 C-CALL-STR-OPCODE@1 / C-CALL-STR-HOST@1）：
                    // a=帧内 str 槽（整串 `NAME(实参…)` / `NAME, 实参…` / `NAME`）、
                    // b=bit31 TRY | bit30 JUMP | bit28 STR_RET（**低位不解释 argc**）、
                    // c=返回槽（TRYC 变体作命中标志槽）。
                    // 目标函数名与实参都在串里、运行期才定，故交宿主解析
                    //（host.resolveRuntimeCall 不得抛，一切失败折成 Blank/NotFound(name)/Failed）。
                    val raw = getStr(a)
                    val isTry = (b and Opcode.TRY_FLAG) != 0
                    val isJump = (b and Opcode.JUMP_FLAG) != 0
                    val isStrRet = (b and Opcode.STR_RET_FLAG) != 0
                    when (val resolution = host.resolveRuntimeCall(raw)) {
                        is RuntimeCallResolution.Ready -> {
                            val callArgs = resolution.args
                            val argc = callArgs.size
                            // 实参暂存区在调用方帧 R0..R(argc-1)：编译器为含 _CALL_STR 的函数
                            // 预留了运行期实参区（见 ErbProgramCompiler 的 RUNTIME_CALL_STAGE_SLOTS）。
                            // 越界即字节码/运行期估算缺口，就地显式失败，不写坏相邻帧。
                            if (argc > frameSlotCount) {
                                throw errorAt("运行期调用实参数($argc)超出当前帧槽上限($frameSlotCount)", pc)
                            }
                            // 分桶序 = 编译期 stageArgs 同源：int 桶 / str 桶各自 R0.. 递增，
                            // argc = **实参位置数**（不是桶数）——requireArgCount/checkTooManyArgs
                            // 依赖该口径（ProcessRuntime.kt:1582-1609）。
                            val meta = ArrayList<CallArg>(argc)
                            var intBucket = 0
                            var strBucket = 0
                            for (i in 0 until argc) {
                                val arg = callArgs[i]
                                val bucket = if (arg.isStr) strBucket++ else intBucket++
                                if (arg.isStr) {
                                    setStr(SlotAddr.local(bucket), arg.strValue ?: "")
                                } else {
                                    setInt(SlotAddr.local(bucket), arg.intValue)
                                }
                                meta.add(
                                    CallArg(
                                        position = i,
                                        bucket = bucket,
                                        isStr = arg.isStr,
                                        name = null,
                                        present = true,
                                    ),
                                )
                            }
                            // 逐位元数据交宿主（在 callInto/jumpInto 之前，供 bindCallArgs 优先读）。
                            host.onRuntimeCallArgs(pc, meta)
                            if (isJump) {
                                val next = jumpInto(resolution.targetIndex, argc, isStrRet, pc)
                                // 尾调到被屏蔽函数：本帧已经"返回"（同 [_CALL] 的哨兵口径）。
                                if (next == STUB_TAIL_RETURN) {
                                    if (isStrRet) stringResult = null
                                    executedSteps = runSteps
                                    return 0L
                                }
                                pc = next
                            } else if (isTry && !SlotAddr.isNone(c)) {
                                // TRYCCALLSTR 两步式（契约 R3）：c 作"是否真调用"命中标志，
                                // 必须在切帧前写调用方帧、且返回值改 NONE（同 [_CALL_BY_NAME]）。
                                checkRetDst(c, pc)
                                setInt(c, 1L)
                                pc = callInto(resolution.targetIndex, argc, SlotAddr.NONE, false, pc)
                            } else {
                                pc = callInto(resolution.targetIndex, argc, c, isStrRet, pc)
                            }
                        }

                        RuntimeCallResolution.Blank -> {
                            // 空串/全空白：不调用，顺序执行下一条（specs/isa.yaml:2542）；
                            // TRYC 命中标志写 0 ⇒ 编译器两步式顺落配对 CATCH。
                            if (isTry && !SlotAddr.isNone(c)) {
                                checkRetDst(c, pc)
                                setInt(c, 0L)
                            }
                            pc++
                        }

                        is RuntimeCallResolution.NotFound -> {
                            if (!isTry) {
                                // 官方 NotDefinedFunc 文案（zhs lang.emuera-zhs.xml:520 =
                                // 没有找到指定的函数"@{0}"；抛点 FunctionIdentifier.cs:3581，
                                // 与 [_CALL_BY_NAME] 的静态名/动态名共用同一条）。
                                // {0} = **解析后函数名**（resolution.name）——不得把实参并入，
                                // 对齐既有 `_CALL_BY_NAME` 的 "@$name"（Vm.kt:1302）。
                                throw errorAt("没有找到指定的函数\"@${resolution.name}\"", pc)
                            }
                            // TRY 系：不调用、跳过本行；TRYC 命中标志写 0 顺落 CATCH。
                            if (!SlotAddr.isNone(c)) {
                                checkRetDst(c, pc)
                                setInt(c, 0L)
                            }
                            pc++
                        }

                        is RuntimeCallResolution.Failed -> {
                            if (!isTry) throw errorAt(resolution.message, pc)
                            if (!SlotAddr.isNone(c)) {
                                checkRetDst(c, pc)
                                setInt(c, 0L)
                            }
                            pc++
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
                    if (depth == 0) {
                        // 交回宿主的字符串返回值只认**顶层帧**的 `_RETS`（见 [stringResult] KDoc）：
                        // 内层帧的串由下面的 [leaveFrameStr] 走调用方返回槽，不得污染本寄存器。
                        // 否则掉尾方法（尾声 `_RET NONE`）会把内层方法的返回值留在这里，
                        // 被 `ProcessRuntime.invokeUserMethod` 读成掉尾方法自己的返回值。
                        stringResult = str
                        // 顶层 `_RETS` 直接结束 run：long 结果保持 0，字符串经 [stringResult] 取。
                        executedSteps = runSteps
                        return result
                    }
                    // int 调用位收到字符串返回 = 原版消费侧 GetIntValue 对 SingleStrTerm 得 0L
                    // （SuperUserDefinedMethodTerm.cs:26-31）—— 同口径写 0，不抛。
                    pc = if (retStrStack[depth]) leaveFrameStr(str) else leaveFrame(0L)
                }

                Opcode._RETF -> {
                    // 浮点返回通道（4.981 路次 [g2-returnf-float]）：a = **表达式池下标**
                    // （不是槽 —— 帧槽没有浮点列，见 [floatResult] 的 KDoc），
                    // NONE = 无返回值 = 0.0。
                    // 求值点在**被调帧内、拆帧之前**，与原版同序：
                    // `RETURNF` 的 DoInstruction 先 `term.GetValue(exm)` 再 `state.ReturnF(ret)`
                    // （fork-src Instraction.Child.cs:3737-3746）；`ReturnF` 只做「弹帧 + 置
                    // MethodReturnValue」，不碰 RESULTF（Process.State.cs:762-789）。
                    // 无参 RETURNF → 原版 `ReturnF(null)` → 消费侧按声明类型给默认项
                    // （UserDefinedMethodTerm.cs:39-49）⇒ 浮点位得 0.0，与这里 `NONE → 0.0` 一致。
                    val value = if (SlotAddr.isNone(a)) {
                        0.0
                    } else {
                        val ctx = host.evalContext
                            ?: throw errorAt("RETURNF 浮点返回需要 EvalContext，宿主未提供", pc)
                        checkPoolIndex(a, exprCount, "表达式池", pc)
                        // 表达式错转 CodeEE：口径与 `_EVAL_INT` 完全一致（现场带函数/行号）。
                        try {
                            program.expr(a).getFloatValue(ctx)
                        } catch (e: ExprEvalException) {
                            throw errorAt(e.message ?: "表达式求值失败", pc)
                        }
                    }
                    floatResult = value
                    if (depth == 0) {
                        // 顶层 `_RETF` 直接结束 run：long 结果保持 0，浮点值经 [floatResult] 取。
                        executedSteps = runSteps
                        return result
                    }
                    // 帧返回口径 = 原版消费侧的类型容错（UserDefinedMethodTerm.cs:19-38：
                    // 非对应项类型静默得 0 / ""，不抛）：调用位要 int 写 0、要 str 写 ""。
                    // 浮点调用位不存在（帧槽无浮点列），浮点消费方走 [floatResult] 寄存器。
                    pc = if (retStrStack[depth]) leaveFrameStr("") else leaveFrame(0L)
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

                Opcode._EVAL_INT_JZ -> {
                    // 融合超指令（MODERN-T1）：求值 + 落槽 + 条件跳假，语义逐字等于
                    // `_EVAL_INT a,b` 后接 `_JUMP_IF_FALSE b,c`；c 是相对下一条指令的偏移。
                    val ctx = host.evalContext
                        ?: throw errorAt("EVAL 需要 EvalContext，宿主未提供", pc)
                    checkPoolIndex(a, exprCount, "表达式池", pc)
                    // 异常包裹与 [_EVAL_INT] 同源（表达式错本身就是 CodeEE，带当前行现场）。
                    val v = try {
                        evalIntFast(program.expr(a), ctx)
                    } catch (e: ExprEvalException) {
                        throw errorAt(e.message ?: "表达式求值失败", pc)
                    }
                    setInt(b, v)
                    pc = if (v == 0L) pc + 1 + c else pc + 1
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
                    // 202..243 是栈码组（T2-21，走 [execStackOp]），其余（负数 / 超出
                    // [Opcode.MAX_OPCODE] 的脏数据）仍然显式失败（`_RETF` 在 [when] 分支里已认领）。
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
                    // 后台暂停闸（跨线程）：语句边界处阻塞。暂停不消耗步数（steps 已在本轮
                    // 循环头计入，暂停期间不再前进）、不改变 pc/执行顺序、不推进段落计时；
                    // 恢复后从本条语句继续。缺省实现（backgroundPaused=false）只多一次布尔读。
                    if (host.backgroundPaused()) {
                        // 墙钟的段落起点平移掉暂停时长：后台停留几十秒不该被当成
                        // 「本段落算了太久」而撞 maxRunMillis 预算，更不该凭空攒出一次
                        // 死循环告警（原版没有「暂停」这一维，`startTime` 只在让出点重启）。
                        // 两者都不启用时（wallClockTracked 为假）不读时钟。
                        val pauseStartNs = if (wallClockTracked) System.nanoTime() else 0L
                        host.awaitResume()
                        if (wallClockTracked) segStartNanos += System.nanoTime() - pauseStartNs
                        // 暂停期间宿主关闭（GameHost.close → interrupt）：awaitResume 立即返回，
                        // 这里再看到中断位就退出本次 run，让 close 能把 VM 线程收回去。
                        // 只有「暂停 → 中断」这条路径读中断位，正常执行的结束语义不变。
                        // 抛点走 [shutdownInterruptedAt]：两条中断出口（主循环 65536 步检查点 /
                        // 本暂停分支）**同一句文案 + 同一份位置章**（原先这里少带函数/行/pc，
                        // 日志里只能看到一句没有现场的「执行被中断」）。
                        if (Thread.currentThread().isInterrupted) {
                            shutdownInterruptedAt(pc)
                        }
                    }
                    // ---------------- 死循环告警（ENGFIX-3：官方唯一的那条判据） ----------------
                    // skia `Process.cs:989-992`：`Config.InfiniteLoopAlertTime > 0` 且
                    // `state.lineCount % 10000 == 0` ⇒ `checkInfiniteLoop()`（:470-489）。
                    // 移植侧逐条照形：阈值 0 = 整条关闭；计数单位是**行**（marker 派发次数）；
                    // 判据是**墙钟**；撞线**不是异常**，而是问一次宿主
                    // （[VmHost.askInfiniteLoop]）—— 答「继续」⇒ 行数与计时双双归零重跑，
                    // 答「退出」才抛，没有通道 ⇒ 什么都不做。**跑多久都不会自己中止**。
                    // 阈值未启用时本块只多一次常量比较（装置/测试路径零成本）。
                    if (loopAlertMs > 0L) {
                        loopLines++
                        if (loopLines >= nextLoopCheckLine) {
                            // 下一采样界 = 当前行 + 10000（与原版 `lineCount % 10000 == 0` 同义，
                            // 省掉热路径上的一次取模）。
                            nextLoopCheckLine = loopLines + INFINITE_LOOP_CHECK_LINES
                            val elapsedMs = (System.nanoTime() - segStartNanos) / 1_000_000L
                            // skia :473-476 —— 墙钟不足阈值直接返回，连当前行都不看。
                            if (elapsedMs >= loopAlertMs) {
                                val answer = checkInfiniteLoopAt(pc, elapsedMs, loopLines, loopAlertMs)
                                when (answer) {
                                    // 玩家选「否」⇒ `state.lineCount = 0` + `startTime.Restart()`
                                    //（skia :486-487），两者必须同一处、成对归零。
                                    InfiniteLoopAnswer.Continue -> {
                                        loopLines = 0L
                                        nextLoopCheckLine = INFINITE_LOOP_CHECK_LINES
                                        segStartNanos = System.nanoTime()
                                    }
                                    // 没有可用 UI ⇒ skia :478 的 `view.Enabled` 为假时整个 if 块
                                    // 跳过：不弹框、不归零、更不中止，下一个 10000 行边界照旧再看。
                                    InfiniteLoopAnswer.NoUi -> Unit
                                    // 玩家选「是」⇒ `throw new CodeEE(...)`（skia :484）。
                                    is InfiniteLoopAnswer.Exit -> infiniteLoopExitAt(
                                        pc, runSteps, elapsedMs, loopLines, loopAlertMs,
                                        answer.textVariant,
                                    )
                                }
                            }
                        }
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
                    // 装置预算的段落计数与**死循环告警的行数/墙钟**一起从 0 重算；
                    // runSteps（executedSteps 口径）不动。
                    // 原版这两件本来就是同一处的成对动作：`startTime.Restart()` 与
                    // `state.lineCount = 0` 一起写（skia `Process.cs:415-418` 的 DoScript 入口、
                    // `:464-468` 的 `UpdateCheckInfiniteLoopState()`、`:486-487` 的「玩家选继续」），
                    // 两个闸的段落边界因此必须同源。
                    val boundaries = host.yieldBoundaryCount()
                    if (boundaries != seenBoundaries) {
                        seenBoundaries = boundaries
                        steps = 0L
                        if (wallClockTracked) segStartNanos = System.nanoTime()
                        if (loopAlertMs > 0L) {
                            loopLines = 0L
                            nextLoopCheckLine = INFINITE_LOOP_CHECK_LINES
                        }
                        // 让出边界的补充检查（宿主关闭闸）：短段永远攒不到 65536 步 ——
                        // `AWAIT` 逐帧让出（@BADAPPLE 6573 帧）每段只有几十条指令，主循环头
                        // 的周期检查够不着；`SleepingTimerSink` 被 interrupt 叫醒后会复原中断位
                        // 而不是抛错，所以必须在这里再看一眼。频率 = 让出次数（AWAIT 每帧一次），
                        // 不是语句数，成本可忽略。输入等待路径本来就会在 `close()` 时抛
                        // `InputSinkClosedException`，这里命中只会更早退出。
                        if (Thread.currentThread().isInterrupted) shutdownInterruptedAt(pc)
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
     * 静态 `_CALL` 的分派点（M2 dispatch ISA，ABI-3 候选）：
     * `b` bit27 DIRECT 置位 → 直调物理下标（CALL_SUPER 回填专用，绕过表）；
     * 无表（null）→ 直调；有表且下标在界内 → 查 [functionDispatch]；
     * 越界下标原样透传（[callInto]/[jumpInto] 报既有「函数下标越界」，行为与无表一致）。
     *
     * 名字通道不经这里：`_CALL_BY_NAME` 等宿主解析返回的已是 dispatch 后的执行入口
     * （分派幂等不变量，SymbolCatalog.build 内建自检），重查就是二次分派。
     */
    private fun resolveDispatch(funcIndex: Int, b: Int): Int {
        val table = functionDispatch ?: return funcIndex
        if (b and Opcode.DIRECT_CALL_FLAG != 0) return funcIndex
        return if (funcIndex >= 0 && funcIndex < table.size) table[funcIndex] else funcIndex
    }

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
        // 进帧流水（观察面）：与 funcStack 建立写同点（enter 成功后）；见 [pageTrail]。
        pageTrail?.record(funcIndex)
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
        // 进帧流水（观察面）：尾跳「enter 成功后才登记」口径下与 funcStack 建立写同点；见 [pageTrail]。
        pageTrail?.record(funcIndex)
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
     * （`>=`/`<=` 判据 = 4.981 `OperatorMethod.cs:547/:578`），`_STK_SREPEAT` 对 `Binary.getStrValue` 的三条检查，
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
                // 池下标是**裸操作数**（T2-21），与 `_SET_INT`/`_SET_STR`/`_PRINT_LITERAL`/
                // `_EVAL_*` 同一档：装载链（`ErbcStream.validate` → `ErbcReader.requireStackOpPool`）
                // 校验过 `.erbc`，但内存直编（`CodeBuilder`/`Assembler`）不走那趟 —— 放行只会让
                // `ConstantPool` 的 `IndexOutOfBoundsException`（非 CodeEE）冒到宿主，
                // 失败屏因此丢掉「函数 行N」现场（见 [checkPoolIndex] 的 KDoc）。
                checkPoolIndex(a, program.constantCount, "常量池", pc)
                pushInt(program.constant(a)); return pc + 1
            }

            Opcode._STK_PUSH_STR -> {
                checkPoolIndex(a, program.stringCount, "字符串池", pc)
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
                if (r == 0L) throw errorAt(DIVIDE_BY_ZERO_TEXT, pc)
                val l = popInt()
                if (l == Long.MIN_VALUE && r == -1L) {
                    throw errorAt("除算溢出：-9223372036854775808 / -1", pc)
                }
                pushInt(l / r); return pc + 1
            }

            Opcode._STK_MOD -> {
                val r = popInt()
                if (r == 0L) throw errorAt(DIVIDE_BY_ZERO_TEXT, pc)
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

            // ---- 字符串比较（Ordinal；`>=`/`<=` 判据 = 4.981 OperatorMethod.cs:547/:578） ----
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
                // spec: OperatorMethod.cs:578 `LessEqualStrStr` 判的是 c <= 0（4.981）。
                val r = popStr(); pushInt(if (popStr().compareTo(r) <= 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SGT -> {
                val r = popStr(); pushInt(if (popStr().compareTo(r) > 0) 1L else 0L); return pc + 1
            }

            Opcode._STK_SGE -> {
                // spec: OperatorMethod.cs:547 `GreaterEqualStrStr` 判的是 c >= 0（4.981）。
                val r = popStr(); pushInt(if (popStr().compareTo(r) >= 0) 1L else 0L); return pc + 1
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
                if (n < 0L) throw errorAt(renderMultiplyToStrLimit(MULTIPLY_NEGATIVE_TO_STR_TEMPLATE, n), pc)
                if (n >= 10000L) throw errorAt(renderMultiplyToStrLimit(MULTIPLY_10K_TO_STR_TEMPLATE, n), pc)
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
        // 变量名是字符串池下标（裸操作数）：先判池再动栈面，失败时不留半截栈
        // （与 [checkVarDim] 的「校验收在第一次弹出之前」同一条纪律，见 [storeVarInt]）。
        checkPoolIndex(strIdx, program.stringCount, "字符串池", pc)
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
        checkPoolIndex(strIdx, program.stringCount, "字符串池", pc)
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
        checkPoolIndex(strIdx, program.stringCount, "字符串池", pc)
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
        checkPoolIndex(strIdx, program.stringCount, "字符串池", pc)
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
        checkPoolIndex(strIdx, program.stringCount, "字符串池", pc)
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
     * 当前帧函数的 Program 下标（`funcStack[depth]` 原值；无帧 / 帧未登记 → -1）。
     *
     * FN-05 当前行通道的**正解入口**：事件成员与同文件重复定义的 Program 函数名是
     * `KEY#defIndex` 形（erbcc 切片签名，CorpusCompile.kt:469-477），与 `FunctionTable`
     * 按键查表的**裸大写名**不同形——按 [currentFunctionKey] 反查会对一切事件成员帧落空。
     * 下标与 `FunctionTable.byFunctionIndex` 同一坐标域，直取 meta 即绕开键表。
     * 守卫与 [currentFunctionKey] 同理：`unwindScopes` 之后 `depth == -1`，不许越界读。
     */
    val currentFunctionIndex: Int
        get() = if (depth < 0) -1 else funcStack[depth]

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
         * 死循环告警的采样频率：**每 10000 行**看一次墙钟（skia `Process.cs:989-992`）。
         *
         * 原版口径是 `state.lineCount % 10000 == 0`（`Process.cs:990`）—— 计数单位是**行**
         * （`ProcessState.cs:126/:132/:341/:365/:379/:460` 的 `lineCount++`），不是指令。
         * 移植侧用「语句标记取指」当行（opcode 29..197，一条 ERB 语句行恰好一枚标记），
         * `FOR`/`REPEAT` 锚点会多挂一枚步进标记，所以移植侧的采样只会**比原版更密**、
         * 不会更疏；判定权仍在墙钟与玩家手上（见 [VmConfig.infiniteLoopAlertTimeMs]）。
         *
         * 用「下次越线」而不是「取模」：移植侧的行计数在段落边界归零（≙ skia 的
         * `UpdateCheckInfiniteLoopState()` 与 `DoScript()` 重入都会 `lineCount = 0`），
         * 取模会在归零后错过对齐点，越线判定与原版逐次等距的观感一致。
         */
        const val INFINITE_LOOP_CHECK_LINES = 10_000L

        /**
         * 宿主关闭闸的检查间隔掩码（[VirtualMachine.execute] 主循环）：每 65536 条指令读一次
         * `Thread.currentThread().isInterrupted`。
         *
         * 为什么与 [RUN_MILLIS_CHECK_MASK] 同频而不是更密：`isInterrupted` 是 native 调用，
         * 逐条读会污染最热的取指循环；65536 在玩家机（≈2.2M 指令/秒）上 ≈30 ms 一次，
         * 「切后台被回收 → 回前台重开」的窗口里足够让旧会话在堆闸之前让出整份 Program。
         */
        const val SHUTDOWN_CHECK_MASK = 0xFFFFL

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
