package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE

/**
 * BEGIN 状态机的**可执行单步**实现。
 *
 * 对应 C# 主循环的两段（`Process.cs:444-470`）：
 * 1. 函数栈空时反复 `runSystemProc()`（`Process.cs:2042-2045`）——本类的 [step]；
 * 2. `ProcessState.Begin()`（`ProcessState.cs:219-278`）选择入口状态——本类的 [select]。
 *
 * **一步 = 一个 handler**。注意这与 C# 的**调用形态不同**：原版多个 handler 会在一次
 * `runSystemProc()` 里嵌套直调（`beginTrain` 缺失时直接 `endCallEventTrain()`
 * `Process.cs:2220-2223`；`endCallShowStatus` 结尾直接 `endCallComAbleXX()`
 * `Process.cs:2254`），本类把它拆成多次 [step]。两者的**状态序列与副作用顺序一致**，
 * 区别只是宿主要多喂几步；这样脚本执行与状态推进不会耦合成不可打断的长事务。
 * 行为差异已在交付说明的「与基准差异」里列明。
 *
 * 失败语义分两类：
 * - 脚本函数缺失且 `force=true` → 由 [BeginCallPort.callFunction] 抛 `CodeEE`
 *   （原版 `Process.cs:2093-2100`）；
 * - 原版在该状态必抛的路径（如 `Normal` 的脚本结束）→ 返回
 *   [StepOutcome.FAILED] + [FlowBlocker]，不吞、也不在状态机里抛。FAILED 是**终局**：
 *   第一次之后本状态机粘滞在该步上（证据链见 [terminalEndNormalStep]），宿主读到即收口、
 *   不得挂起等输入后再泵。
 */
interface BeginCallPort {
    /** 调脚本函数；`false` = 未定义且 `force=false`。`force=true` 缺失须抛 CodeEE。 */
    fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean

    /** 脚本变量 `RESULT:0`（COM/ABLUP 分支用）。 */
    fun result(): Long

    /**
     * 脚本变量 `RESULT:0` 写入（原版 `vEvaluator.RESULT` 属性的 setter，
     * `VariableEvaluator.cs:46-48`，落 `RESULT_ARRAY[0]`）。
     *
     * 两个调用点都是同一形状「把系统输入原样交给脚本入口」：
     * - `trainWaitInput`（`Process.cs:2372`）——`RESULT = systemResult` 之后调 `USERCOM`。
     *   `USERCOM` 全程靠 `RESULT` 分派：语料 `USERCOM.ERB:15` 的 `LOCAL = RESULT`、
     *   `:85` 的 `SELECTCOM = RESULT`、`:109` 的 `TRYCALLFORM COM_ABLE{SELECTCOM}`。
     *   这条不写则 `SELECTCOM` 取陈旧值、`COM_ABLE<n>` 永不命中，真机表现为
     *   「点指令零输出静默退回菜单」；
     * - `ablupWaitInput`（`Process.cs:2506`）——`RESULT = systemResult` 之后调 `USERABLUP`。
     *
     * `shopWaitInput`（`Process.cs:2636`）那处已由 `ShopHostModel.writeResult` 落同一槽，不走这里。
     */
    fun setResult(value: Long)

    /** 原版 `Process.systemResult`（系统输入结果，见 `Process.cs:2052-2088`）。 */
    fun systemResult(): Long
    fun setSystemResult(value: Long)

    /** `NEXTCOM` / `SELECTCOM`（训练指令链）。 */
    fun nextCom(): Long
    fun setNextCom(value: Long)
    fun selectCom(): Long
    fun setSelectCom(value: Long)

    /** `CALLTRAIN` 指令队列（`Process.SetCommnds`，Process.cs:369-390）。 */
    fun isCTrain(): Boolean
    fun clearCommands(): Boolean

    /** `VariableEvaluator.UpdateInBeginTrain`（VariableEvaluator.cs:1668-1718）。 */
    fun beginTrainReset()

    /**
     * `VariableEvaluator.UpdateAfterShowUsercom`（`VariableEvaluator.cs:1719-1754`）：
     * 全局 `UP` / `DOWN` / `LOSEBASE`（`dataIntegerArray[5]/[9]/[16]`，槽位见
     * `VariableData.cs:3019/3023/3030`）与每个角色的 `DOWNBASE` / `CUP` / `CDOWN`
     * （角色 `[17]/[18]/[19]`，`VariableData.cs:3093-3095`）清零。
     * 调用点：`endCallShowUserCom`（`Process.cs:2327`）与 `doTrain`（`:2379`）。
     */
    fun afterShowUserComReset()

    /**
     * `VariableEvaluator.UpdateAfterInputCom`（`VariableEvaluator.cs:1756-1766`）：
     * 每个角色的 `NOWEX`（角色 `[16]`，`VariableData.cs:3092`）清零。
     * 调用点：`callEventCom`（`Process.cs:2386`），即每次派发 `EVENTCOM` 之前。
     */
    fun afterInputComReset()

    /**
     * `VariableEvaluator.UpdateAfterSourceCheck`（`VariableEvaluator.cs:1768-1778`）：
     * 每个角色的 `SOURCE`（角色 `[7]`，`VariableData.cs:3083`）清零。
     * 调用点：`endCallSourceCheck`（`Process.cs:2415`）。
     */
    fun afterSourceCheckReset()

    /** `TrainName[i]`；null = 该格为空或宿主未提供训练名表。 */
    fun trainName(code: Int): String?

    /** `TrainName.Length`；null = 宿主未提供训练名表。 */
    fun trainNameCount(): Int?

    /** 脚本 `BEGIN` 写下的挂起入口（`ProcessState.begintype`）。 */
    fun pendingBegin(): BeginType?
    fun takePendingBegin(): BeginType?
    fun clearPendingBegin()

    /** `ProcessState.isBegun`（ProcessState.cs:38）。 */
    fun isBegun(): Boolean = pendingBegin() != null
}

/**
 * 状态机需要、但 core-vm 内部拿不到的宿主能力。全部有默认实现：
 * 默认 = 「没有该能力」，对应步骤返回 [StepOutcome.BLOCKED] 或 [StepOutcome.AWAIT_INPUT]，
 * **不伪造结果**。
 */
interface BeginHost {
    /** SYSTEM_TITLE 缺失时画默认标题（`Process.cs:2141-2157`）。默认不画。 */
    fun printDefaultTitle() {}

    /**
     * `beginTitle` 开头的样式复位 —— 原版 `Process.cs:2112 console.ResetStyle()`
     * （方法体 `:2105-2113`：`:2111` `skipPrint = false` → `:2112` 复位 → `:2113`
     * `deleteAllPrevState()`）。复位集恰四项（前景/样式位/字体名/对齐，
     * `EmueraConsole.cs:2838-2842` + `StringStyle.cs:20-27`），**不含**背景色（`:200`）
     * 与重绘态（`:455`）。默认无动作 = 宿主没有 console。
     */
    fun resetStyle() {}

    /** 标题菜单输入（`openingInput` → `setWaitInput`，Process.cs:2158-2165）。null = 无输入后端。 */
    fun awaitOpeningInput(): Long? = null

    /** `systemResult == 0` 的新游戏初始化：ResetData + AddCharacterFromCsvNo(0) + PrintBar（Process.cs:2169-2179）。 */
    fun onNewGameSelected() {}

    /** `TITLE_LOADGAME` 缺失时的读档界面（Process.cs:2189）。true = 宿主已接管。 */
    fun beginLoadGameOpening(): Boolean = false

    /**
     * `printSaveDataText()`（`Process.cs:2757-2815`）的**槽位列表半边**（`:2758-2801`）：
     * 原版逐页枚举 20 个槽（`:2769-2783`，每槽 `vEvaluator.CheckData` 读存档文件出摘要行）、
     * 99 槽仅**非** `SaveGame_Begin` 上下文显示（`:2790-2799`）、末行 `[100] 戻る`（`:2801`）。
     * 这些全是宿主设备侧的存档数据读取 ⇒ [BeginFlow.stepSaveOrLoadGameBegin] 把这一段整体交给本钩子。
     *
     * 默认 no-op = 宿主没有存档数据界面；状态机**照常**走完 printSaveDataText 的后半
     * （`setWaitInput()` `:2802` + 换态 `:2803-2810`），本步返回即交还控制权，不阻塞、不伪造槽位内容。
     *
     * @param isSave true = SAVEGAME 来的（`SaveGame_Begin` 上下文，`:2790` 的分支因此跳过 99 槽）；
     *               false = LOADGAME 来的（`LoadGame_Begin` 上下文）。
     */
    fun printSaveDataSlots(isSave: Boolean) {}

    /** `Config.AutoSave`（Process.cs:2556）。 */
    fun autoSave(): Boolean = false

    /** `endCallShowStatus` 的 comAble 清零（Process.cs:2246-2254）。 */
    fun resetComAble() {}

    /** `console.PrintC(getTrainComString(...))`（Process.cs:2273）。默认丢弃显示。 */
    fun onComAble(code: Int, index: Int) {}

    /** `Config.ComAbleDefault`（Process.cs:2293）。 */
    val comAbleDefault: Int get() = 1

    /** 指令选择输入（`setWaitInput`，Process.cs:2330）。null = 无输入后端。 */
    fun awaitUserComInput(): Long? = null

    /** 能力提升选择输入（Process.cs:2486）。null = 无输入后端。 */
    fun awaitAblupInput(): Long? = null

    /** 商店选择输入（Process.cs:2605）。null = 无输入后端。 */
    fun awaitShopInput(): Long? = null

    /**
     * 商店输入的宿主侧模型：原版 `vEvaluator.ItemSales` / `BuyItem`
     * （VariableEvaluator.cs:1952-1966 / :1968-1988）与 `Config.MaxShopItem`
     * 上界（Process.cs:2611）都在变量池那半边，BEGIN 状态机层拿不到变量池，
     * 故整体经本属性注入（契约见 [ShopHostModel]）。null = 未接，
     * 商店输入步落 BLOCKED，不伪造成交。
     */
    val shopHostModel: ShopHostModel? get() = null

    /** `console.LastLineIsTemporary`（Process.cs:2426 / :2514 / :2644）。默认 false。 */
    fun lastLineIsTemporary(): Boolean = false

    /** 系统行输出（无效值提示等）。默认丢弃。 */
    fun writeLine(text: String) {}

    /**
     * `AppContents.UnloadTempLoadedConstImageNames()`（`Process.cs:2671`；
     * 实现在 AppContents.cs:205-215）：`LOADDATA` 之后卸掉 CONST 图临时载入表。
     * 默认无动作 = 宿主没有临时载入表（移植侧 `core/image` 尚未提供该表）。
     *
     * **同源缺口（登记）**：原版 `BEGIN SHOP` / `BEGIN FIRST` 在语句执行期也会调本方法 +
     * [unloadTempLoadedGraphicsImageNames]（`ProcessState.cs:140-141` / `:157-158` 的
     * `SetBegin(string)`），移植侧的落点 `ProcessRuntime` 的 `ExecResult.Begin` 分支
     * **未调**（只调了 `ResetStyle`），属组长接线项。
     */
    fun unloadTempLoadedConstImageNames() {}

    /**
     * `AppContents.UnloadTempLoadedGraphicsImageNames()`（`Process.cs:2672`；
     * 实现在 AppContents.cs:217-230）：同上，图形（GCREATE 系）临时载入表。
     */
    fun unloadTempLoadedGraphicsImageNames() {}

    companion object {
        /** 无宿主：所有能力缺失，状态机在第一个需要宿主处停住并给出 blocker。 */
        val NONE: BeginHost = object : BeginHost {}
    }
}

enum class StepOutcome {
    /** handler 调用了脚本函数且目标存在。 */
    CALLED,

    /** handler 调用了脚本函数，但目标不存在且 force=false。 */
    NOT_CALLED,

    /** handler 只做了状态迁移，没有调用脚本。 */
    CONTINUE,

    /** 已消费脚本层 `BEGIN`，切到新入口状态（**新状态**的 handler 未执行）。 */
    REENTERED,

    /** 等待宿主输入；下一步由宿主喂入结果。 */
    AWAIT_INPUT,

    /** 宿主能力缺失或 BOOT-2 未接管；见 [StateStep.blocker]。 */
    BLOCKED,

    /**
     * 原版在该状态必然失败（如 Normal 的脚本结束）。**终局**：宿主读到即收口，
     * 不得挂起等输入后再泵 —— 原版错误结局是 ConsoleState.Error，下一次回车
     * **不会**重跑 DoScript（EmueraConsole.cs:1094 的门控；证据链见
     * [BeginFlow.terminalEndNormalStep] 的 KDoc）。
     */
    FAILED,
}

enum class BlockerKind {
    /** 宿主面缺失（输入/文件/存档/训练名表……）。 */
    HOST_MISSING,

    /** 宿主有输入面但本步需要等输入。 */
    HOST_INPUT,

    /** BOOT-2 未接管的状态。 */
    NOT_IMPLEMENTED,

    /** 原版失败语义。 */
    OFFICIAL_FAILURE,
}

/**
 * 阻塞点五元组 + 证据。
 *
 * - [function] / [file] / [line] / [pc] 是 ERB 侧定位；状态级阻塞没有 ERB 位置时为 null/-1。
 * - [evidence] 一律是 C# `文件:行号`，说明这条失败语义从哪来。
 */
data class FlowBlocker(
    val kind: BlockerKind,
    val function: String?,
    val file: String?,
    val line: Int,
    val pc: Int,
    val message: String,
    val evidence: String,
) {
    fun tuple(): String =
        "function=${function ?: "?"} file=${file ?: "?"} line=$line pc=$pc error=$message"
}

data class StateStep(
    val from: SystemStateCode,
    val handler: String,
    val to: SystemStateCode,
    val outcome: StepOutcome,
    val calledFunction: String?,
    val blocker: FlowBlocker?,
    val evidence: String,
)

/**
 * @param calls 脚本调用/变量端口（[ProcessRuntime] 或单测假体）
 * @param host  状态机宿主能力（默认 [BeginHost.NONE]）
 */
class BeginFlow(
    private val calls: BeginCallPort,
    private val host: BeginHost = BeginHost.NONE,
) {
    var state: SystemStateCode = SystemStateCode.Normal
        private set

    /** `ProcessState.calledWhenNormal`（ProcessState.cs:28 / :227-236）。 */
    var calledWhenNormal: Boolean = true
        private set

    var selectedType: BeginType? = null
        private set

    private var lastCalledComable: Int = -1
    private var lastAddCom: Int = -1

    /**
     * `Process.comAble`（`Process.cs:102`，装载 `:1997` 按 `TrainName.Length` 建数组）：
     * 按钮号 → 训练码的登记表。原版 `endCallShowStatus` 整表清 -1（`:2250-2252`），
     * `endCallComAbleXX` 在两处登记（`:2270` RESULT!=0、`:2297` ComAbleDefault!=0），
     * `trainWaitInput` 按 systemResult 反查（`:2346-2348`）。移植侧用稀疏表：
     * 未登记 = 原版的 -1（查不到即无效值分支）。`endCallShowStatus` 那步整体清空。
     */
    private val comAble = HashMap<Int, Int>()

    /**
     * `Process.InputSystemInteger`（`Process.cs:426-430`：只做 `systemResult = i`）的交付闩锁。
     *
     * 原版一次回车只交付**一个** `systemResult`：`endCallShowUserCom` 里的 `setWaitInput()`
     * （`Process.cs:2330`）把请求交给控制台，程序线程停在等待上；`PressEnterKey` 回调调
     * `InputSystemInteger` 写值后线程才继续，下一个 handler（`trainWaitInput`，`:2341`）
     * **直接读 `systemResult`**（`:2346-2348`），不再等第二次输入。本移植的等待是宿主阻塞式的，
     * 若 [stepTrainShowUserCom] 与 [stepTrainWaitInput] 都调 `awaitUserComInput`，一条指令会吃掉
     * 两次提交且第一次被覆盖。故：[stepTrainShowUserCom] 取到值即置位并写 `systemResult`，
     * [stepTrainWaitInput] 只在已置位时读 `systemResult`（读后即清）；没置位（例如状态由存档恢复
     * 直接进 `Train_WaitInput`）才自己等一次。
     */
    private var userComDelivered = false

    /**
     * `Process.doTrainSelectCom`（`Process.cs:126`，构造期 `:187` 置 -1）：
     * 最近一次 `DOTRAIN <n>` 的参数。`doTrain()` 把它写进 `SELECTCOM`（`:2380`）。
     *
     * 语料用法（`コマンド関連/USERCOM.ERB:137`）：普通命令在 USERCOM 里先
     * `SELECTCOM = RESULT` + `TFLAG:3 = SELECTCOM`，再 `DOTRAIN 999` 走 dummy 训练；
     * 这里按原版把 SELECTCOM 改成 999（`COM999` 是空壳），真正的命令号随后由
     * `@SOURCE_CHECK` 从 `TFLAG:3` 还原（`SOURCE.ERB:26`）再经 `COM{SELECTCOM}` 执行。
     */
    private var doTrainSelectCom: Long = -1L

    /**
     * endNormal 终局粘滞位。第一次 `Normal` 分支报 FAILED 后置位；此后 [step] 幂等返回
     * 终局步，不再消费挂起 BEGIN、不再进入任何输入等待。证据链见 [terminalEndNormalStep]。
     */
    private var endNormalTerminal = false

    private fun registerComAble(comNo: Int, trainCode: Int) {
        comAble[comNo] = trainCode
        // 原版只在 !isCTrain 时 PrintC 按钮文本（Process.cs:2271-2277 / :2298-2304）。
        if (!calls.isCTrain()) host.onComAble(trainCode, comNo)
    }

    /**
     * `ProcessState.Begin(BeginType)`（ProcessState.cs:273-278 → :219-271）：
     * 记录 `begintype`、按类型设置 `sysStateCode`，并清空挂起入口（原版清函数栈）。
     *
     * `SHOP` 额外记录 `calledWhenNormal`（`:227-236`）——只有从 `Normal` 进商店才算
     * 「正常回合结束」，自动存档用它判定（`:2556`）。
     *
     * **官方怪行为（ADR-005 照抄）**：`Begin(type)` 在调 `Begin()` **之前**把
     * `sysStateCode` 置成 `Title_Begin`（`:276`），于是 `:228` 的 `== Normal` 判定
     * 恒为 false —— 宿主侧 `begin(SHOP)` 的 `calledWhenNormal` **永远是 false**；
     * 只有脚本 `BEGIN SHOP`（走无参 `Begin()`，当前状态原样保留）才可能为 true。
     * [viaBeginType] 就是这两条入口的区别：true = `Begin(type)`，false = 脚本 `Begin()`。
     */
    fun select(type: BeginType, viaBeginType: Boolean = true) {
        // :276 先把状态改成 Title_Begin，:228 的判定因此看不到真实来源状态。
        val wasNormal = !viaBeginType && state == SystemStateCode.Normal
        selectedType = type
        calls.clearPendingBegin()
        // 换入口即作废未消费的交付闩锁：原版 `setWaitInput` 的等待属于**那一次**指令选择，
        // 新的 BEGIN 流程必须重新等输入（口径见 [userComDelivered]）。
        userComDelivered = false
        if (type == BeginType.SHOP) calledWhenNormal = wasNormal
        state = SystemStateGraph.entryStateOf(type)
    }

    /**
     * `DOTRAIN <n>` 语句（`Process.cs:1931-1954`，`doNormalFunction` 的 DOTRAIN 分支）。
     *
     * 原版语义顺序（逐条照抄）：
     * 1. 状态门：只允许 `Train_CallEventTrain` / `Train_CallShowStatus` /
     *    `Train_CallShowUserCom` / `Train_CallEventComEnd`（`:1934-1941`；后两者正是
     *    `@SHOW_USERCOM` 与 `@USERCOM` 的执行期）；
     * 2. `coms.Clear(); isCTrain = false; count = 0`（`:1942-1944`，对应 [BeginCallPort.clearCommands]）；
     * 3. 参数边界：`< 0` 或 `>= TrainName.Length` 各抛一条 CodeEE（`:1946-1953`）；
     * 4. `doTrainSelectCom = n; state = Train_DoTrain`（`:1951-1952`）。
     *
     * 第 3 步的文案取自 zhs 语言表（`lang.emuera-zhs.xml:731-732`）。原版在第 1 步失败前
     * 还会 `PrintSystemLine(state.SystemState.ToString())`（`Process.cs:1939`）—— 移植侧
     * 控制台没有 system 行通道，且该分支只在非法调用位置才走到，故只抛错不打印
     * TODO(verify)：若要做逐字对照，需要控制台增 system 行样式。
     */
    fun doTrain(no: Long) {
        val st = state
        val allowed = st == SystemStateCode.Train_CallEventTrain ||
            st == SystemStateCode.Train_CallShowStatus ||
            st == SystemStateCode.Train_CallShowUserCom ||
            st == SystemStateCode.Train_CallEventComEnd
        if (!allowed) throw CodeEE("DOTRAIN命令不能在此位置执行")
        calls.clearCommands()
        if (no < 0L) throw CodeEE("对DOTRAIN命令传入了小于0的值")
        val count = calls.trainNameCount()
        if (count != null && no >= count) {
            throw CodeEE("对DOTRAIN命令传入了大于TRAINNAME数组元素数的值")
        }
        doTrainSelectCom = no
        state = SystemStateCode.Train_DoTrain
    }

    /**
     * 直接把状态置成 [code] 且**不跑 handler**，返回置之前的状态。
     *
     * 唯一调用方是 `ProcessRuntime.beginStep` / `resumeSystem` 的读档交接分支：
     * `LOADDATA` 成功后原版当场把 `state.SystemState` 置成 `LoadData_DataLoaded`
     * （`Process.cs:1861`），脚本随即结束（`:1860` 的 `ClearFunctionList`），下一次
     * `runSystemProc()` 直接从该状态进 handler（`Process.cs:445-452` + `:2042-2045`）。
     * 移植侧把「置状态」与「跑 handler」拆成两步，故这里只做前者。
     */
    fun enterState(code: SystemStateCode): SystemStateCode {
        val from = state
        state = code
        return from
    }

    /**
     * 「回标题」通道需要的状态机复位 —— 原版 `Process.BeginTitle()`（`Process.cs:480-485`）
     * 里属于状态机的那半边；唯一调用点是 `ProcessRuntime.gotoTitle()`。
     *
     * 原版 `BeginTitle()` 只有三句：`vEvaluator.ResetData()`（变量池半边，落在
     * `ProcessRuntime.gotoTitle` 调 `store.resetData()`）、`state = originalState`、
     * `state.Begin(BeginType.TITLE)`。**第二条是同对象自赋值**：`state` 与 `originalState`
     * 都指向 `Process.cs:200-201` 里 new 出来的那一个 `ProcessState`，全 reference 再无第三处
     * 写 `state =`，所以原版并没有把状态机「换回出厂实例」。真正生效的只有
     * `Begin(BeginType.TITLE)`（`ProcessState.cs:273-278`：先 `sysStateCode = Title_Begin`
     * （`:276`）→ `Begin()` 里 `functionList.Clear()` + `begintype = BeginType.NULL`
     * （`:269-270`），同一对语句在 `ClearFunctionList()` 的尾部 `:215-216`），而它在移植侧
     * 就是紧随本函数之后的那一次 [select]。
     *
     * 所以本函数只清**移植侧特有**的两枚闩锁，其余字段一律不动（铁律「不顺手扩大复位面」）：
     * - [endNormalTerminal]：粘滞位建模的是「控制台停在 `ConsoleState.Error`，下一次回车不再
     *   重跑 DoScript」（`EmueraConsole.cs:1092-1110` 的 `:1094` 门控；证据链见
     *   [terminalEndNormalStep]）。`GotoTitle` 正是解除该门控的**唯一**通道 ——
     *   `ReadAnyKey()` 把 `state` 无条件写成 `ConsoleState.WaitInput`
     *   （`EmueraConsole.cs:876-895`，写点在 `:893`），门控随之打开。不清它，[step] 会在开头
     *   继续早退返回终局步（`:347`），标题永远重跑不起来。
     * - [userComDelivered]：未消费的一次性输入交付属于**那一次**指令选择，换入口即作废
     *   （与 [select] `:316` 同口径；[select] 也会再清一次，这里清是让本函数单独调用时
     *   也是完整复位）。
     *
     * [state] 写回构造默认 [SystemStateCode.Normal]（`:248`）只是把 boot 态显式化：调用方
     * 紧接着的 [select] 会把它写成 `Title_Begin`，中间没有任何读数窗口。
     * **不复位**的东西同样有出处：[calledWhenNormal] 只由 `Begin(SHOP)` 写
     * （`ProcessState.cs:227-236`），[comAble] / `lastCalledComable` / `lastAddCom` 只由
     * `endCallShowStatus` 清（`Process.cs:2244-2255`）—— 新的标题流程要走到训练菜单必过
     * 那一状态，原版 `BeginTitle` 没碰这三样，这里也不碰。
     */
    fun resetToBootState() {
        endNormalTerminal = false
        userComDelivered = false
        state = SystemStateCode.Normal
    }

    /**
     * 执行**一个**系统状态 handler。
     *
     * 开头的挂起 `BEGIN` 分支对应 `ProcessState.cs:352-362`：脚本函数栈清空时若
     * `begintype != NULL`，先 `Begin()` 切状态，不跑当前状态 handler。
     */
    fun step(): StateStep {
        // 终局粘滞（证据链见 [terminalEndNormalStep]）：原版错误后 RunEmueraProgram 的门控
        // （EmueraConsole.cs:1094 的 state != ConsoleState.Error）不再重跑 DoScript，
        // 系统状态机收不到任何一步 ⇒ 挂起 BEGIN 不会被消费（runSystemProc 不再被调）、
        // 输入只被控制台回显、不喂给程序（EmueraConsole.cs:1280-1287）。
        if (endNormalTerminal) return terminalEndNormalStep()
        // ProcessState.cs:221-224 —— 处于 Shop_CallEventShop 时无参 Begin() 立即 return，
        // 挂起的 BEGIN 不会被消费（官方行为，照抄）。此状态下 EVENTSHOP 尚未返回，
        // 入口切换要等到 endCallEventShop → endAutoSave（:2594-2596）之后。
        if (state != SystemStateCode.Shop_CallEventShop) {
            beginAtScriptReturn(
                state, "(script BEGIN)", null,
                "ProcessState.cs:352-362 / ProcessState.cs:219-278",
            )?.let { return it }
        }
        return when (state) {
            SystemStateCode.Title_Begin -> stepTitle()
            SystemStateCode.Openning -> stepOpenning()
            SystemStateCode.First_Begin -> stepFirst()
            SystemStateCode.Openning_TitleLoadgame -> {
                state = SystemStateCode.Title_Begin
                done(
                    SystemStateCode.Openning_TitleLoadgame,
                    "endTitleLoadgame", StepOutcome.CONTINUE, null, "Process.cs:2211-2214",
                )
            }
            SystemStateCode.Train_Begin -> stepTrainBegin()
            SystemStateCode.Train_CallEventTrain -> stepTrainCallEventTrain()
            SystemStateCode.Train_CallShowStatus -> stepTrainShowStatus()
            SystemStateCode.Train_CallComAbleXX -> stepTrainComAble()
            SystemStateCode.Train_CallShowUserCom -> stepTrainShowUserCom()
            SystemStateCode.Train_WaitInput -> stepTrainWaitInput()
            SystemStateCode.Train_CallEventCom -> stepEndEventCom()
            SystemStateCode.Train_CallComXX -> stepTrainCallComXX()
            SystemStateCode.Train_CallSourceCheck -> stepTrainCallSourceCheck()
            SystemStateCode.Train_CallEventComEnd -> {
                // 原版 `endCallEventComEnd`（Process.cs:2424-2456）有三条早退/分支，本移植只走
                // 「回到训练菜单」主路径，另三条未接（登记，见交付）：
                // 1. 临时行校验（`:2426-2436`：LastLineIsTemporary && !isCTrain && needCheck
                //    ⇒ endCallShowUserCom 重显指令菜单）——`needCheck` 字段（`:2449` 置 true、
                //    `:2437-2448` CALLTRAINEND 置 false）未建模，无法只补半条；
                // 2. CALLTRAINEND 早退（`:2437-2448`）；
                // 3. NeedWaitToEventComEnd 的 setWait（`:2450-2453`，`:2417` 置位）——移植侧
                //    「等按键」是宿主驱动式，无 awaitAnyKey 通道。
                state = SystemStateCode.Train_CallEventTrain
                done(
                    SystemStateCode.Train_CallEventComEnd,
                    "endCallEventComEnd", StepOutcome.CONTINUE, null, "Process.cs:2455 → 2226-2242",
                )
            }
            SystemStateCode.Train_DoTrain -> stepTrainDoTrain()
            SystemStateCode.AfterTrain_Begin -> stepAfterTrain()
            SystemStateCode.Ablup_Begin -> stepAblupBegin()
            SystemStateCode.Ablup_CallShowJuel -> stepAblupShowJuel()
            SystemStateCode.Ablup_CallShowAblupSelect -> stepAblupShowSelect()
            SystemStateCode.Ablup_WaitInput -> stepAblupWait()
            SystemStateCode.Ablup_CallAblupXX -> stepAblupCall()
            SystemStateCode.Turnend_Begin -> stepTurnend()
            SystemStateCode.Shop_Begin -> stepShopBegin()
            SystemStateCode.Shop_CallEventShop -> stepShopEventShop()
            SystemStateCode.Shop_CallShowShop -> stepShopShow()
            SystemStateCode.Shop_WaitInput -> stepShopWait()
            SystemStateCode.Shop_CallEventBuy -> stepShopEventBuy()
            SystemStateCode.AutoSave_CallSaveInfo,
            SystemStateCode.AutoSave_CallUniqueAutosave,
            SystemStateCode.AutoSave_Skipped -> endAutoSave(state, "endAutoSave")
            SystemStateCode.LoadData_DataLoaded -> stepDataLoaded()
            SystemStateCode.LoadData_CallSystemLoad -> stepSystemLoad()
            SystemStateCode.LoadData_CallEventLoad -> stepEventLoad()
            SystemStateCode.SaveGame_Begin -> stepSaveOrLoadGameBegin(save = true)
            SystemStateCode.LoadGame_Begin -> stepSaveOrLoadGameBegin(save = false)
            SystemStateCode.Normal -> {
                // Process.cs:2039（systemProcessDictionary 注册）+ :2959-2962（endNormal
                // 方法体 = throw CodeEE(予期しないスクリプトの終了)）：Normal+栈空在原版
                // 同样是错误终局，不是「等下一次输入重进」的等待形态。
                endNormalTerminal = true
                terminalEndNormalStep()
            }
            else -> StateStep(
                from = state,
                handler = "(未接管)",
                to = state,
                outcome = StepOutcome.BLOCKED,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.NOT_IMPLEMENTED, null, null, -1, -1,
                    "系统状态 ${state.name} 不在 BOOT-2 的接管范围（BEGIN TITLE→SHOP 链）",
                    "SystemStateCode.cs:3-50 / Process.cs:1998-2039",
                ),
                evidence = "Process.cs:1998-2039",
            )
        }
    }

    /**
     * `Normal` + 脚本函数栈空（且无挂起 BEGIN）的终局失败步 —— `endNormal`
     * （`Process.cs:2039` 注册进 systemProcessDictionary、`:2959-2962` 方法体 =
     * `throw new CodeEE(予期しないスクリプトの終了)`）。幂等：每次调用返回逐字相同的
     * [StateStep]，第一次之后不再产生「新的」失败读数（红 B 的「FAILED 只报一次」）。
     *
     * **终局语义的证据链（红 B 裁定：原版崩溃后下一次回车不复活）**：
     * 1. endNormal 的 CodeEE 在 `DoScript` 的 catch 里按 `flag == true`（系统 proc 期）
     *    分派给 `handleExceptionInSystemProc`（`Process.cs:462-477`，`:471`）；该方法打印
     *    错误后**正常返回**、不重抛（`Process.cs:571-593`），`DoScript` 随之返回。
     * 2. 两条异常处理路径都先调 `console.ThrowError(playSound)`（`Process.cs:573` / `:597`），
     *    它把控制台状态置成 `ConsoleState.Error`（`EmueraConsole.cs:757-768`，`:767`）。
     * 3. 下一次回车走 `RunEmueraProgram`，门控是
     *    `input == null || (doInputToEmueraProgram(input) && state != ConsoleState.Error)`
     *    （`EmueraConsole.cs:1092-1110`，`:1094`）：非 WaitInput 态下
     *    `doInputToEmueraProgram` 只做 `Print(str); inputed = true; return true`
     *    （`:1280-1287`；`:1114` 的喂值分支不执行），但 `state != ConsoleState.Error`
     *    判假 ⇒ **DoScript 不再重跑**。控制台停在错误屏，后续回车只有回显。
     * 4. 「DoScript 返回 → 下一次回车重进 → 系统 proc 吃输入继续玩」的形状只属于
     *    **非 Error 收尾**：系统 proc 调 `setWaitInput` 把控制台置 `WaitInput`
     *    （`EmueraConsole.cs:858-874`），`IsRunning` 随之转假（`:270-284`）让 `DoScript`
     *    返回（`Process.cs:449-460`），下一次回车门控通过、重进 `runSystemProc` 消费输入。
     *    Normal+栈空不是这个形状。
     * 5. 原版从错误屏复活的通道只有菜单「タイトルへ戻る」与 Ctrl+Z 的
     *    `GotoTitle`（`MainWindow.cs:1079-1104` / `:532-536` → `EmueraConsole.cs:2569-2581`）：
     *    `process.BeginTitle()`（ResetData + Begin(TITLE)，`Process.cs:480-485`）+
     *    `ReadAnyKey()`（把控制台从 Error 置回 WaitInput，`EmueraConsole.cs:876-895`）+
     *    `RunEmueraProgram("")` 重跑 DoScript。该通道的 core-vm 半边已落地 =
     *    `ProcessRuntime.gotoTitle()`（变量池整体复位 + 本类的 [resetToBootState] +
     *    一步系统 handler），宿主按钮与会话重泵由组长在这条 API 上接线。
     *
     * 因此移植侧的终局形状 =「FAILED 只报一次 + 输入不再反复触发 FAILED 挂起」：
     * 宿主读到 [StepOutcome.FAILED] 即收口；粘滞位（[endNormalTerminal]）保证此后任何
     * [step] 调用都在本函数早退 —— 不会消费挂起 BEGIN（对应原版错误后 `runSystemProc`
     * 不再被调），也不会走到任何 `await*`（对应原版错误后输入只被回显、不喂程序）。
     */
    private fun terminalEndNormalStep(): StateStep = StateStep(
        from = SystemStateCode.Normal,
        handler = "endNormal",
        to = SystemStateCode.Normal,
        outcome = StepOutcome.FAILED,
        calledFunction = null,
        blocker = FlowBlocker(
            BlockerKind.OFFICIAL_FAILURE, null, null, -1, -1,
            "予期しないスクリプトの終了（脚本函数栈已空且没有挂起的 BEGIN）",
            "Process.cs:2959-2962",
        ),
        evidence = "Process.cs:2039 / Process.cs:2959-2962",
    )

    // ------------------------------------------------------------------
    // 脚本栈清空当刻的 BEGIN 消费（ProcessState.cs:352-362）
    // ------------------------------------------------------------------

    /**
     * `ProcessState.Return` 的「函数栈空即 `Begin()`」（`ProcessState.cs:352-362`）。
     *
     * 原版 `BEGIN` 语句在**语句执行期**就调 `state.Return(0L)`（`FunctionIdentifier.cs:2930-2936`），
     * 而 `Return` 只在被调函数弹栈后 `currentLine == null`（函数栈清空）那一次判
     * `begintype != 0` 并当场 `Begin()`（`ProcessState.cs:356-361`）；嵌套调用里的 `BEGIN`
     * 只结束当前帧，调用方继续执行，挂起入口留到最外层返回（`:348-361` 的 `else` 分支）。
     *
     * 本移植的根级脚本调用是同步的：`BeginCallPort.callFunction` 返回时函数栈必然已空，
     * 所以此刻消费 pending 与 `Return` 的时机逐字等价。**只有以 `Normal` 收尾的 handler**
     * （[stepTitle] / [stepFirst] / [stepAfterTrain] / [stepTurnend]）必须在这里消费：
     * 其它 handler 返回的状态不是 `Normal`，宿主会继续 [step]，开头那段同形分支会在
     * 下一步开头消费掉 pending。这四个若不在这里消费，宿主泵（`GameHost.drive`
     * 的「只要活着就反复 [BeginFlow.step]，见 GameHost.kt 状态机驱动小节」）会在
     * 这些 handler 收尾后看到「`Normal` 但 pending 还在」的错误中间态，把本该转态
     * 的回合当成 Normal+栈空报 FAILED —— 真机选「开始游戏」后卡住
     * （`SYSTEM_TITLE` 里 `BEGIN FIRST`，语料 TITLE.ERB:117）就是这个形态。
     *
     * @param called 本步实际派发/尝试派发的脚本函数名（[SystemStateCode] 的 handler 已知）。
     * @return null = 没有挂起 BEGIN，handler 按原样收尾。
     */
    private fun beginAtScriptReturn(
        from: SystemStateCode,
        handler: String,
        called: String?,
        evidence: String,
    ): StateStep? {
        val pending = calls.takePendingBegin() ?: return null
        select(pending, viaBeginType = false)
        return StateStep(
            from = from,
            handler = handler,
            to = state,
            outcome = StepOutcome.REENTERED,
            calledFunction = called,
            blocker = null,
            evidence = evidence,
        )
    }

    // ------------------------------------------------------------------
    // 标题
    // ------------------------------------------------------------------

    private fun stepTitle(): StateStep {
        val from = SystemStateCode.Title_Begin
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(from, "beginTitle", StepOutcome.CONTINUE, null, "Process.cs:2107-2110")
        }
        // Process.cs:2112 console.ResetStyle() —— 位置照原版：CTrain 早退（:2107-2110）之后、
        // SYSTEM_TITLE 派发（:2136）之前。原版 :2111 的 `skipPrint = false` 在移植侧无通道
        // （BeginCallPort 不带 skipPrint），属另行缺口，登记不在此补。
        // 另缺 `deleteAllPrevState()`（:2113）＝清存档快照栈 `prevStateList`
        // （:1986-1993）——移植侧**不复刻**快照栈（脚本帧是同步原生调用栈，无数据帧可恢复，
        // 见 SaveLoadGameBegin 的类内说明）⇒ 本处刻意 no-op，不是遗漏。
        host.resetStyle()
        // Process.cs:2138 —— 原版 `callFunction` 只推帧，`:2138` 的
        // `state.SystemState = Normal` 在**脚本体执行之前**落地；脚本里 `BEGIN SHOP` 到
        // `ProcessState.Return` 那一刻看到的正是 Normal（`calledWhenNormal` 的判定点，
        // ProcessState.cs:227-236）。本移植的脚本调用是同步的（callFunction 内部跑完），
        // 故这条赋值必须提前到调用之前，否则「TITLE 里 BEGIN SHOP」会误记成非正常回合。
        state = SystemStateCode.Normal
        if (calls.callFunction("SYSTEM_TITLE", false, false)) {
            // ProcessState.cs:352-362 —— 函数栈已空：脚本以 BEGIN 收尾时当场转态，
            // 宿主不得看到「Normal + pending」的中间态（见 [beginAtScriptReturn]）。
            beginAtScriptReturn(
                from, "beginTitle", "SYSTEM_TITLE",
                "Process.cs:2136-2140 / ProcessState.cs:352-362",
            )?.let { return it }
            return done(from, "beginTitle", StepOutcome.CALLED, "SYSTEM_TITLE", "Process.cs:2136-2140")
        }
        // SYSTEM_TITLE 缺失：原版 `callFunction` 返回 false 时不执行 :2138 的赋值，
        // 状态留在 Title_Begin；这里还原，保证默认标题/等菜单输入期间宿主读到的状态与原版一致。
        state = from
        // SYSTEM_TITLE 缺失：宿主画默认标题 + 等菜单输入（Process.cs:2141-2158）。
        host.printDefaultTitle()
        val input = host.awaitOpeningInput()
        // `openingInput()`（Process.cs:2161-2165）= setWaitInput() + 置 Openning，
        // 原版不检查「有没有输入后端」⇒ 状态无条件切 Openning。
        state = SystemStateCode.Openning
        if (input == null) {
            return StateStep(
                from = from,
                handler = "beginTitle",
                to = state,
                outcome = StepOutcome.BLOCKED,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.HOST_MISSING, "SYSTEM_TITLE", null, -1, -1,
                    "SYSTEM_TITLE 缺失且宿主无标题菜单输入（BeginHost.awaitOpeningInput == null）",
                    "Process.cs:2141-2165",
                ),
                evidence = "Process.cs:2141-2165",
            )
        }
        calls.setSystemResult(input)
        return StateStep(
            from = from,
            handler = "beginTitle",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, "SYSTEM_TITLE", null, -1, -1,
                "标题菜单已就绪，下一步 endOpenning 消费 systemResult=$input",
                "Process.cs:2156-2165",
            ),
            evidence = "Process.cs:2156-2165",
        )
    }

    private fun stepOpenning(): StateStep {
        val from = SystemStateCode.Openning
        return when (val result = calls.systemResult()) {
            0L -> {
                host.onNewGameSelected()
                state = SystemStateCode.First_Begin
                done(from, "endOpenning", StepOutcome.CONTINUE, null, "Process.cs:2169-2180")
            }
            1L -> {
                if (calls.callFunction("TITLE_LOADGAME", false, false)) {
                    state = SystemStateCode.Openning_TitleLoadgame
                    done(from, "endOpenning", StepOutcome.CALLED, "TITLE_LOADGAME", "Process.cs:2181-2186")
                } else if (host.beginLoadGameOpening()) {
                    state = SystemStateCode.LoadGameOpenning_Begin
                    done(from, "endOpenning", StepOutcome.CONTINUE, null, "Process.cs:2187-2190")
                } else {
                    StateStep(
                        from = from,
                        handler = "endOpenning",
                        to = state,
                        outcome = StepOutcome.BLOCKED,
                        calledFunction = null,
                        blocker = FlowBlocker(
                            BlockerKind.HOST_MISSING, "TITLE_LOADGAME", null, -1, -1,
                            "TITLE_LOADGAME 缺失且宿主无读档界面（BeginHost.beginLoadGameOpening == false）",
                            "Process.cs:2187-2190",
                        ),
                        evidence = "Process.cs:2187-2190",
                    )
                }
            }
            else -> {
                // Process.cs:2192-2198 —— deleteLine(1) / PrintTemporaryLine(无效的值) /
                // updatedGeneration = true 之后调 `openingInput()`（:2161-2165），它 = setWaitInput()
                // + `state.SystemState = Openning`：原版会**重新等一次菜单输入**。
                // 旧实现只把状态写回 Openning 而不取输入，宿主 drive 的
                // `while (systemState != Normal)` 会拿同一份陈旧 systemResult 反复进本分支刷屏。
                // 这里按原版真正再等一次，并把新值写进 systemResult；状态留在 Openning
                // （openingInput 的第二个动作），下一步 endOpenning 消费新值。
                host.writeLine("无效的值")
                val again = host.awaitOpeningInput()
                if (again == null) {
                    return StateStep(
                        from = from,
                        handler = "endOpenning",
                        to = state,
                        outcome = StepOutcome.BLOCKED,
                        calledFunction = null,
                        blocker = FlowBlocker(
                            BlockerKind.HOST_MISSING, null, null, -1, -1,
                            "systemResult=$result 不是 0/1，且宿主无标题菜单输入可重等" +
                                "（BeginHost.awaitOpeningInput == null）",
                            "Process.cs:2192-2198 / :2161-2165",
                        ),
                        evidence = "Process.cs:2192-2198 / :2161-2165",
                    )
                }
                calls.setSystemResult(again)
                StateStep(
                    from = from,
                    handler = "endOpenning",
                    to = state,
                    outcome = StepOutcome.AWAIT_INPUT,
                    calledFunction = null,
                    blocker = FlowBlocker(
                        BlockerKind.HOST_INPUT, null, null, -1, -1,
                        "systemResult=$result 不是 0/1；已重显提示并重新取到菜单输入 $again",
                        "Process.cs:2192-2198 / :2161-2165",
                    ),
                    evidence = "Process.cs:2192-2198 / :2161-2165",
                )
            }
        }
    }

    private fun stepFirst(): StateStep {
        // 原版先写 Normal 再调用（Process.cs:2203-2207），顺序影响异常后的状态，照抄。
        state = SystemStateCode.Normal
        // Process.cs:2204 —— CTrain 且队列已空时不派发 EVENTFIRST。
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(
                SystemStateCode.First_Begin, "beginFirst",
                StepOutcome.CONTINUE, null, "Process.cs:2203-2208",
            )
        }
        val called = calls.callFunction("EVENTFIRST", true, true)
        if (called) {
            // ProcessState.cs:352-362 —— EVENTFIRST 以 `BEGIN SHOP` 收尾（语料 SYSTEM.ERB:30）
            // 时事件栈在这一刻清空，原版当场 Begin()；不补这一步宿主会停在 Normal 直接退出。
            beginAtScriptReturn(
                SystemStateCode.First_Begin, "beginFirst", "EVENTFIRST",
                "Process.cs:2201-2209 / ProcessState.cs:352-362",
            )?.let { return it }
        }
        return done(
            SystemStateCode.First_Begin, "beginFirst",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTFIRST", "Process.cs:2201-2209",
        )
    }

    // ------------------------------------------------------------------
    // 训练
    // ------------------------------------------------------------------

    private fun stepTrainBegin(): StateStep {
        calls.beginTrainReset()
        state = SystemStateCode.Train_CallEventTrain
        val called = calls.callFunction("EVENTTRAIN", false, true)
        return done(
            SystemStateCode.Train_Begin, "beginTrain",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTTRAIN", "Process.cs:2216-2224",
        )
    }

    private fun stepTrainCallEventTrain(): StateStep {
        val from = SystemStateCode.Train_CallEventTrain
        val next = calls.nextCom()
        return if (next >= 0L) {
            state = SystemStateCode.Train_CallEventCom
            calls.setSelectCom(next)
            calls.setNextCom(0L)
            // Process.cs:2233 → :2386 —— callEventCom 先 `UpdateAfterInputCom()`
            // 清每角色 NOWEX（VariableEvaluator.cs:1756-1766），再派发 EVENTCOM。
            calls.afterInputComReset()
            val called = calls.callFunction("EVENTCOM", false, true)
            done(
                from, "endCallEventTrain",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "EVENTCOM", "Process.cs:2228-2234",
            )
        } else {
            state = SystemStateCode.Train_CallShowStatus
            val called = calls.callFunction("SHOW_STATUS", true, false)
            done(
                from, "endCallEventTrain",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "SHOW_STATUS", "Process.cs:2236-2242",
            )
        }
    }

    private fun stepTrainShowStatus(): StateStep {
        host.resetComAble()
        // Process.cs:2250-2252 —— 整表清 -1（移植侧稀疏表等价：清空）。
        comAble.clear()
        lastCalledComable = -1
        lastAddCom = -1
        state = SystemStateCode.Train_CallComAbleXX
        return done(
            SystemStateCode.Train_CallShowStatus, "endCallShowStatus",
            StepOutcome.CONTINUE, null, "Process.cs:2244-2255",
        )
    }

    private fun stepTrainComAble(): StateStep {
        val from = SystemStateCode.Train_CallComAbleXX
        val count = calls.trainNameCount()
            ?: return StateStep(
                from = from,
                handler = "endCallComAbleXX",
                to = state,
                outcome = StepOutcome.BLOCKED,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.HOST_MISSING, "SHOW_USERCOM", null, -1, -1,
                    "宿主未提供 TrainName 表（CSV Str.csv 的 TRAINNAME 段），无法枚举 COM_ABLE<n>",
                    "Process.cs:2263-2319",
                ),
                evidence = "Process.cs:2263-2319",
            )

        // 上一个 COM_ABLE<n> 已返回：RESULT != 0 才登记（Process.cs:2265-2282）。
        if (lastCalledComable in 0 until count && calls.trainName(lastCalledComable) != null) {
            lastAddCom++
            if (calls.result() != 0L) registerComAble(lastAddCom, lastCalledComable)
        }
        while (true) {
            lastCalledComable++
            if (lastCalledComable >= count) {
                state = SystemStateCode.Train_CallShowUserCom
                val called = calls.callFunction("SHOW_USERCOM", true, false)
                return done(
                    from, "endCallComAbleXX",
                    if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                    "SHOW_USERCOM", "Process.cs:2312-2318",
                )
            }
            if (calls.trainName(lastCalledComable) == null) continue
            val name = "COM_ABLE$lastCalledComable"
            if (!calls.callFunction(name, false, false)) {
                lastAddCom++
                if (host.comAbleDefault != 0) registerComAble(lastAddCom, lastCalledComable)
                continue
            }
            return done(from, "endCallComAbleXX", StepOutcome.CALLED, name, "Process.cs:2283-2311")
        }
    }

    private fun stepTrainShowUserCom(): StateStep {
        // Process.cs:2327 —— `vEvaluator.UpdateAfterShowUsercom()` 在 setWaitInput 之前，
        // 清 UP/DOWN/LOSEBASE 与每角色的 DOWNBASE/CUP/CDOWN（VariableEvaluator.cs:1719-1754）。
        calls.afterShowUserComReset()
        // 非 CTrain：setWaitInput + Train_WaitInput（Process.cs:2328-2332）。这里的
        // `awaitUserComInput` 就是原版的 setWaitInput：取到的整数交 `InputSystemInteger`
        // 写进 systemResult（Process.cs:426-430），下一个 handler 直接读它（见 [userComDelivered]）。
        // CTrain：原版从 coms 队列直接取 systemResult 调 trainWaitInput（:2333-2338），
        // 队列消费属 CALLTRAIN 簇、未落地；本移植照常落到 Train_WaitInput，
        // 由 [stepTrainWaitInput] 的 CTrain 支报 BLOCKED，不伪造队列读数。
        val input = host.awaitUserComInput()
        state = SystemStateCode.Train_WaitInput
        if (input != null) {
            calls.setSystemResult(input)
            userComDelivered = true
        }
        return StateStep(
            from = SystemStateCode.Train_CallShowUserCom,
            handler = "endCallShowUserCom",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, null, null, -1, -1,
                "等待指令选择输入（setWaitInput，Process.cs:2328-2332）",
                "Process.cs:2321-2339",
            ),
            evidence = "Process.cs:2321-2339",
        )
    }

    /**
     * `trainWaitInput`（`Process.cs:2341-2375`）—— M10-PLAY 收口：这里原样落地
     * 「指令号 → comAble 反查 → EVENTCOM / USERCOM」的分流。此前 BOOT-2 把它标成
     * 「宿主 UI 状态、未接」直接 BLOCKED，是真机「提交之后脚本不推进」的堵点：
     * `comAble` 在原版是 `Process` 的字段（`Process.cs:102`、装载 `:1997`），不是宿主状态。
     *
     * 分支（非 CTrain，`:2344-2349` + `:2361-2373`）：
     * - `0 <= systemResult < comAble.Length` 且登记值 >= 0 ⇒ `SELECTCOM = comAble[systemResult]`
     *   → `callEventCom()`（`:2361-2364`；`Train_CallEventCom` + `EVENTCOM`，缺失时原版当场
     *   `endEventCom()`，由 [SystemStateCode.Train_CallEventCom] 那步的 NOT_CALLED 继续口径承接）；
     * - 否则 ⇒ `RESULT = sysResult` → `Train_CallEventComEnd` + `USERCOM`（force，
     *   `:2366-2373`）。这里写的是**脚本变量** `RESULT`（`vEvaluator.RESULT`，`:2372`），
     *   经 [BeginCallPort.setResult] 落 `RESULT:0`；系统输入本身另经 `setSystemResult`
     *   落 `Process.systemResult` 槽（两槽语义不同，原版也是两个字段，不得合并）。
     *   漏写 `RESULT` 时的真机症状见 [BeginCallPort.setResult] 的 KDoc（红 O）。
     *
     * CTrain 那支（`:2351-2359` 扫 `comAble[i] == systemResult`）维持未接：`coms` 队列消费属
     * CALLTRAIN 簇（见 [stepTrainShowUserCom] 的类内说明），照常报 BLOCKED 不伪造。
     *
     * 交付：输入只在 [stepTrainShowUserCom] 取一次，本函数读闩锁后的 `systemResult`
     * （口径与证据见 [userComDelivered]）。单测 `trainWaitInput 命中登记派发 EVENTCOM 未登记走 USERCOM`
     * 用宿主调用计数把「一条指令一次提交」钉死。
     */
    private fun stepTrainWaitInput(): StateStep {
        val from = SystemStateCode.Train_WaitInput
        if (calls.isCTrain()) {
            return StateStep(
                from = from,
                handler = "trainWaitInput",
                to = state,
                outcome = StepOutcome.BLOCKED,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.NOT_IMPLEMENTED, "USERCOM", null, -1, -1,
                    "CTrain 的 coms 队列反查（Process.cs:2351-2359）未接；CALLTRAIN 簇未落地",
                    "Process.cs:2351-2359",
                ),
                evidence = "Process.cs:2341-2375",
            )
        }
        // 交付口径见 [userComDelivered]：endCallShowUserCom 已把回车值写进 systemResult，
        // 这里读的就是那条提交（`Process.cs:426-430` → `:2346-2348`），不再等第二次。
        val input = if (userComDelivered) {
            userComDelivered = false
            calls.systemResult()
        } else host.awaitUserComInput() ?: return StateStep(
            from = from,
            handler = "trainWaitInput",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, null, null, -1, -1,
                "等待指令选择输入（BeginHost.awaitUserComInput == null）",
                "Process.cs:2341-2375",
            ),
            evidence = "Process.cs:2341-2375",
        )
        val num = if (input >= 0L && input < Int.MAX_VALUE.toLong()) comAble[input.toInt()] else null
        return if (num != null && num >= 0) {
            calls.setSelectCom(num.toLong())
            state = SystemStateCode.Train_CallEventCom
            // Process.cs:2361-2364 → :2386 —— callEventCom 的 `UpdateAfterInputCom()`
            // （VariableEvaluator.cs:1756-1766）：每次派发 EVENTCOM 前清 NOWEX。
            calls.afterInputComReset()
            val called = calls.callFunction("EVENTCOM", false, true)
            done(
                from, "trainWaitInput",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "EVENTCOM", "Process.cs:2344-2349 / :2361-2364",
            )
        } else {
            // Process.cs:2372-2374 —— 原版先 `vEvaluator.RESULT = systemResult` 再调 USERCOM。
            // USERCOM 的分派全靠这个脚本变量（USERCOM.ERB:15/85/109），不写就是红 O。
            calls.setResult(input)
            calls.setSystemResult(input)
            state = SystemStateCode.Train_CallEventComEnd
            val called = calls.callFunction("USERCOM", true, false)
            done(
                from, "trainWaitInput",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "USERCOM", "Process.cs:2366-2373",
            )
        }
    }

    private fun stepEndEventCom(): StateStep {
        state = SystemStateCode.Train_CallComXX
        val name = "COM${calls.selectCom()}"
        val called = calls.callFunction(name, true, false)
        return done(
            SystemStateCode.Train_CallEventCom, "endEventCom",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            name, "Process.cs:2394-2400",
        )
    }

    private fun stepTrainCallComXX(): StateStep {
        val from = SystemStateCode.Train_CallComXX
        if (calls.result() == 0L) {
            state = SystemStateCode.Train_CallEventComEnd
            return done(from, "endCallComXX", StepOutcome.CONTINUE, null, "Process.cs:2404-2407")
        }
        state = SystemStateCode.Train_CallSourceCheck
        val called = calls.callFunction("SOURCE_CHECK", true, false)
        return done(
            from, "endCallComXX",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SOURCE_CHECK", "Process.cs:2409-2410",
        )
    }

    private fun stepTrainCallSourceCheck(): StateStep {
        // Process.cs:2415 —— `vEvaluator.UpdateAfterSourceCheck()`：在置 Train_CallEventComEnd
        // 与派发 EVENTCOMEND 之前清每角色 SOURCE（VariableEvaluator.cs:1768-1778）。
        calls.afterSourceCheckReset()
        state = SystemStateCode.Train_CallEventComEnd
        val called = calls.callFunction("EVENTCOMEND", false, true)
        return done(
            SystemStateCode.Train_CallSourceCheck, "endCallSourceCheck",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTCOMEND", "Process.cs:2413-2422",
        )
    }

    private fun stepTrainDoTrain(): StateStep {
        // Process.cs:2379 → :2380 —— doTrain 先 `UpdateAfterShowUsercom()`，再把
        // `DOTRAIN <n>` 的参数写进 SELECTCOM（`vEvaluator.SELECTCOM = doTrainSelectCom`）。
        // 此后 callEventCom（:2382 → :2386）才做 `UpdateAfterInputCom()`；本移植把两个
        // 复位与 SELECTCOM 赋值合在这一步（状态已指向 Train_CallEventCom，与 :2387 一致）。
        calls.afterShowUserComReset()
        calls.setSelectCom(doTrainSelectCom)
        calls.afterInputComReset()
        state = SystemStateCode.Train_CallEventCom
        val called = calls.callFunction("EVENTCOM", false, true)
        return done(
            SystemStateCode.Train_DoTrain, "doTrain",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTCOM", "Process.cs:2377-2382",
        )
    }

    // ------------------------------------------------------------------
    // 训练后 / 能力提升 / 回合结束
    // ------------------------------------------------------------------

    private fun stepAfterTrain(): StateStep {
        // Process.cs:2460 —— CTrain 且队列非空时什么都不做（状态停在 AfterTrain_Begin）。
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(
                SystemStateCode.AfterTrain_Begin, "beginAfterTrain",
                StepOutcome.CONTINUE, null, "Process.cs:2458-2465",
            )
        }
        state = SystemStateCode.Normal
        val called = calls.callFunction("EVENTEND", true, true)
        if (called) {
            // ProcessState.cs:352-362 —— EVENTEND 里的 BEGIN 在事件返回时当场转态。
            beginAtScriptReturn(
                SystemStateCode.AfterTrain_Begin, "beginAfterTrain", "EVENTEND",
                "Process.cs:2458-2466 / ProcessState.cs:352-362",
            )?.let { return it }
        }
        return done(
            SystemStateCode.AfterTrain_Begin, "beginAfterTrain",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTEND", "Process.cs:2458-2466",
        )
    }

    private fun stepTurnend(): StateStep {
        // Process.cs:2532 —— CTrain 且队列非空时什么都不做。
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(
                SystemStateCode.Turnend_Begin, "beginTurnend",
                StepOutcome.CONTINUE, null, "Process.cs:2530-2537",
            )
        }
        // Process.cs:2535-2536 —— 原版 `callFunction` 只推帧，`:2536` 的
        // `state.SystemState = Normal` 在 EVENTTURNEND 脚本体执行**之前**落地
        // （同 [stepTitle] 的同步化口径）：EVENTTURNEND 里的 `BEGIN SHOP`
        // （语料 EVENTTURNEND.ERB:300，逐回合都会走）判定 `calledWhenNormal` 时状态必须是 Normal。
        state = SystemStateCode.Normal
        val called = calls.callFunction("EVENTTURNEND", true, true)
        if (called) {
            // ProcessState.cs:352-362 —— 事件栈清空当刻当场转态。
            beginAtScriptReturn(
                SystemStateCode.Turnend_Begin, "beginTurnend", "EVENTTURNEND",
                "Process.cs:2530-2538 / ProcessState.cs:352-362",
            )?.let { return it }
        }
        return done(
            SystemStateCode.Turnend_Begin, "beginTurnend",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTTURNEND", "Process.cs:2530-2538",
        )
    }

    private fun stepAblupBegin(): StateStep {
        // Process.cs:2470 —— CTrain 且队列非空时什么都不做。
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(
                SystemStateCode.Ablup_Begin, "beginAblup",
                StepOutcome.CONTINUE, null, "Process.cs:2468-2475",
            )
        }
        state = SystemStateCode.Ablup_CallShowJuel
        val called = calls.callFunction("SHOW_JUEL", true, false)
        return done(
            SystemStateCode.Ablup_Begin, "beginAblup",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SHOW_JUEL", "Process.cs:2468-2476",
        )
    }

    private fun stepAblupShowJuel(): StateStep {
        state = SystemStateCode.Ablup_CallShowAblupSelect
        val called = calls.callFunction("SHOW_ABLUP_SELECT", true, false)
        return done(
            SystemStateCode.Ablup_CallShowJuel, "endCallShowJuel",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SHOW_ABLUP_SELECT", "Process.cs:2478-2482",
        )
    }

    private fun stepAblupShowSelect(): StateStep {
        state = SystemStateCode.Ablup_WaitInput
        return StateStep(
            from = SystemStateCode.Ablup_CallShowAblupSelect,
            handler = "endCallShowAblupSelect",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, null, null, -1, -1,
                "等待能力提升选择输入（setWaitInput，Process.cs:2486）",
                "Process.cs:2484-2488",
            ),
            evidence = "Process.cs:2484-2488",
        )
    }

    private fun stepAblupWait(): StateStep {
        val from = SystemStateCode.Ablup_WaitInput
        val input = host.awaitAblupInput()
            ?: return StateStep(
                from = from,
                handler = "ablupWaitInput",
                to = state,
                outcome = StepOutcome.AWAIT_INPUT,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.HOST_INPUT, null, null, -1, -1,
                    "等待能力提升选择输入（BeginHost.awaitAblupInput == null）",
                    "Process.cs:2490-2510",
                ),
                evidence = "Process.cs:2490-2510",
            )
        return if (input in 0L..99L) {
            state = SystemStateCode.Ablup_CallAblupXX
            val name = "ABLUP$input"
            val called = calls.callFunction(name, false, false)
            if (called) {
                done(from, "ablupWaitInput", StepOutcome.CALLED, name, "Process.cs:2492-2496")
            } else {
                // Process.cs:2496-2502：无效值 → 清行 + 重新显示选择并等输入。
                host.writeLine("无效的值")
                state = SystemStateCode.Ablup_WaitInput
                done(from, "ablupWaitInput", StepOutcome.AWAIT_INPUT, name, "Process.cs:2496-2502")
            }
        } else {
            // Process.cs:2506-2508 —— `vEvaluator.RESULT = systemResult` 之后调 USERABLUP，
            // 与 trainWaitInput 的 :2372 同一条通道（USERABLUP 也按 RESULT 分派）。
            calls.setResult(input)
            calls.setSystemResult(input)
            state = SystemStateCode.Ablup_CallAblupXX
            val called = calls.callFunction("USERABLUP", true, false)
            done(
                from, "ablupWaitInput",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "USERABLUP", "Process.cs:2504-2509",
            )
        }
    }

    private fun stepAblupCall(): StateStep {
        state = if (host.lastLineIsTemporary()) {
            SystemStateCode.Ablup_CallShowAblupSelect
        } else {
            SystemStateCode.Ablup_Begin
        }
        return done(
            SystemStateCode.Ablup_CallAblupXX, "endCallAblupXX",
            StepOutcome.CONTINUE, null, "Process.cs:2512-2528",
        )
    }

    // ------------------------------------------------------------------
    // 商店 / 自动存档
    // ------------------------------------------------------------------

    private fun stepShopBegin(): StateStep {
        // Process.cs:2542 —— CTrain 且队列非空时什么都不做。
        if (calls.isCTrain() && calls.clearCommands()) {
            return done(
                SystemStateCode.Shop_Begin, "beginShop",
                StepOutcome.CONTINUE, null, "Process.cs:2540-2550",
            )
        }
        state = SystemStateCode.Shop_CallEventShop
        val called = calls.callFunction("EVENTSHOP", false, true)
        return done(
            SystemStateCode.Shop_Begin, "beginShop",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTSHOP", "Process.cs:2540-2551",
        )
    }

    private fun stepShopEventShop(): StateStep {
        val from = SystemStateCode.Shop_CallEventShop
        if (host.autoSave() && calledWhenNormal) {
            if (calls.callFunction("SYSTEM_AUTOSAVE", false, false)) {
                state = SystemStateCode.AutoSave_CallUniqueAutosave
                return done(from, "endCallEventShop", StepOutcome.CALLED, "SYSTEM_AUTOSAVE", "Process.cs:2565-2571")
            }
            if (calls.callFunction("SAVEINFO", false, false)) {
                state = SystemStateCode.AutoSave_CallSaveInfo
                return done(from, "endCallEventShop", StepOutcome.CALLED, "SAVEINFO", "Process.cs:2572-2578")
            }
        }
        state = SystemStateCode.AutoSave_Skipped
        return endAutoSave(from, "endCallEventShop", "Process.cs:2561-2562 / :2581-2601")
    }

    private fun stepShopShow(): StateStep {
        state = SystemStateCode.Shop_WaitInput
        return StateStep(
            from = SystemStateCode.Shop_CallShowShop,
            handler = "endCallShowShop",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, null, null, -1, -1,
                "等待商店选择输入（setWaitInput，Process.cs:2605）",
                "Process.cs:2603-2607",
            ),
            evidence = "Process.cs:2603-2607",
        )
    }

    /**
     * `shopWaitInput`（`Process.cs:2609-2640`）—— 商店命令的分流。
     *
     * 输入来自 [BeginHost.awaitShopInput]（交付即写 [BeginCallPort.setSystemResult]，
     * 对应原版 `InputSystemInteger`，Process.cs:426-430）。分支：
     *
     * - `0 <= systemResult < Config.MaxShopItem`（`:2611`，上界由
     *   [ShopHostModel.maxShopItem] 提供）：
     *   - [ShopHostModel.itemSales] 为 true（VariableEvaluator.cs:1952-1966）：
     *     - [ShopHostModel.buyItem] 为 true（VariableEvaluator.cs:1968-1988；扣
     *       MONEY、ITEM 自增、BOUGHT 记账都在模型内完成）→ 切 `Shop_CallEventBuy`
     *       并调 `EVENTBUY`（force=false 的 event，`:2617-2618`）。调不动时原版当场
     *       `endCallEventBuy()`（`:2620`），移植侧按「一步一 handler」拆分：状态已切，
     *       本步返回 NOT_CALLED，下一 [step] 由 `Shop_CallEventBuy` 的 handler
     *       （endCallEventBuy 语义）承接。
     *     - false → `console.deleteLine(1)` + `PrintTemporaryLine(Lang.Error.NotEnoughMoney)`
     *       （`:2624-2625`）→ `endCallShowShop()`（`:2632` = setWaitInput +
     *       `Shop_WaitInput`，`:2603-2607`）。文案取 zhs 资源「MONEY不足」
     *       （日原文「お金が足りません。」，Lang.cs:3150；译文 emuera-zhs.xml:742）。
     *   - false → 同上，文案 `Lang.Error.OutOfStock`「售罄」（日原文
     *     「売っていません。」，Lang.cs:3154；译文 emuera-zhs.xml:743）。
     * - 越界（含负数）→ [ShopHostModel.writeResult]（原版 `vEvaluator.RESULT =
     *   systemResult`，Process.cs:2636）→ `USERSHOP`（**force=true**，`:2637`；
     *   缺失即 CodeEE，由 [BeginCallPort.callFunction] 抛）→ 切 `Shop_CallEventBuy`
     *   （`:2638`；原版先调函数后置状态，移植侧顺序一致）。
     *
     * deleteLine(1) 在移植侧暂无独立通道：临时行文本经 [BeginHost.writeLine] 交出，
     * 保真度欠账与「无效的值」三处同款（见 ConsoleBeginHost.writeLine 的 KDoc）。
     */
    private fun stepShopWait(): StateStep {
        val from = SystemStateCode.Shop_WaitInput
        val input = host.awaitShopInput()
            ?: return StateStep(
                from = from,
                handler = "shopWaitInput",
                to = state,
                outcome = StepOutcome.AWAIT_INPUT,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.HOST_INPUT, null, null, -1, -1,
                    "等待商店选择输入（BeginHost.awaitShopInput == null）",
                    "Process.cs:2609-2640",
                ),
                evidence = "Process.cs:2609-2640",
            )
        calls.setSystemResult(input)
        val model = host.shopHostModel
            ?: return StateStep(
                from = from,
                handler = "shopWaitInput",
                to = state,
                outcome = StepOutcome.BLOCKED,
                calledFunction = null,
                blocker = FlowBlocker(
                    BlockerKind.HOST_MISSING, null, null, -1, -1,
                    "ItemSales/BuyItem 属宿主商品模型（Process.cs:2611-2639）；" +
                        "BeginHost.shopHostModel 未接",
                    "Process.cs:2609-2640",
                ),
                evidence = "Process.cs:2609-2640",
            )
        if (input >= 0 && input < model.maxShopItem()) {
            val no = input.toInt()
            if (model.itemSales(no)) {
                if (model.buyItem(no)) {
                    state = SystemStateCode.Shop_CallEventBuy
                    val called = calls.callFunction("EVENTBUY", false, true)
                    return done(
                        from, "shopWaitInput",
                        if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                        "EVENTBUY", "Process.cs:2613-2623",
                    )
                }
                host.writeLine(NOT_ENOUGH_MONEY_TEXT)
                return backToShopWait(from, "Process.cs:2624-2632")
            }
            host.writeLine(OUT_OF_STOCK_TEXT)
            return backToShopWait(from, "Process.cs:2629-2632")
        }
        model.writeResult(input)
        calls.callFunction("USERSHOP", true, false)
        state = SystemStateCode.Shop_CallEventBuy
        return done(from, "shopWaitInput", StepOutcome.CALLED, "USERSHOP", "Process.cs:2634-2639")
    }

    /**
     * 商店购买失败分支的收口：原版 `shopWaitInput` 尾部直调 `endCallShowShop()`
     * （Process.cs:2632；方法体 `:2603-2607` = `setWaitInput()` + 状态写回
     * `Shop_WaitInput`）。移植侧这里只切状态，重等的输入由下一次 [step] 的
     * [stepShopWait] 再取（嵌套直调拆步口径见类头 KDoc）。
     */
    private fun backToShopWait(from: SystemStateCode, evidence: String): StateStep {
        state = SystemStateCode.Shop_WaitInput
        return done(from, "shopWaitInput", StepOutcome.CONTINUE, null, evidence)
    }

    private fun stepShopEventBuy(): StateStep {
        return if (host.lastLineIsTemporary()) {
            state = SystemStateCode.Shop_CallShowShop
            done(
                SystemStateCode.Shop_CallEventBuy, "endCallEventBuy",
                StepOutcome.CONTINUE, null, "Process.cs:2644-2653",
            )
        } else {
            state = SystemStateCode.AutoSave_Skipped
            endAutoSave(
                SystemStateCode.Shop_CallEventBuy, "endCallEventBuy",
                "Process.cs:2654-2657 / :2592-2601",
            )
        }
    }

    /**
     * `endAutoSave`（Process.cs:2592-2601）：`isBegun` 由下一次 [step] 的挂起 BEGIN 分支消费，
     * 所以这里只「不切状态」而不是直接跳转。
     */
    private fun endAutoSave(
        from: SystemStateCode,
        handler: String,
        evidence: String = "Process.cs:2592-2601",
    ): StateStep {
        if (calls.isBegun()) {
            return done(from, handler, StepOutcome.CONTINUE, null, evidence)
        }
        state = SystemStateCode.Shop_CallShowShop
        val called = calls.callFunction("SHOW_SHOP", true, false)
        return done(
            from, handler,
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SHOW_SHOP", evidence,
        )
    }

    // ------------------------------------------------------------------
    // 读档（LOADDATA）三态：Process.cs:2660-2683，注册表 :2033-2035
    // ------------------------------------------------------------------

    /**
     * `beginDataLoaded`（Process.cs:2660-2667）：
     * `state.SystemState = LoadData_CallSystemLoad` 后 `callFunction("SYSTEM_LOADEND",
     * force: false, isEvent: false)`；目标缺失时原版**当场**调 `endSystemLoad()`。
     *
     * 本状态机一步一个 handler（见类 KDoc 的拆分说明）：缺失时状态已在
     * `LoadData_CallSystemLoad`，下一步自然进 [stepSystemLoad]，序列与副作用顺序一致。
     */
    private fun stepDataLoaded(): StateStep {
        val from = SystemStateCode.LoadData_DataLoaded
        state = SystemStateCode.LoadData_CallSystemLoad
        val called = calls.callFunction("SYSTEM_LOADEND", false, false)
        return done(
            from, "beginDataLoaded",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SYSTEM_LOADEND", "Process.cs:2660-2667",
        )
    }

    /**
     * `endSystemLoad`（Process.cs:2669-2678）：先卸掉两张**临时载入**图片名表
     * （`AppContents.UnloadTempLoadedConstImageNames()` / `...GraphicsImageNames()`，
     * `:2671-2672`；实现在 AppContents.cs:205-230），再 `LoadData_CallEventLoad` +
     * `EVENTLOAD`（事件，force=false）；缺失时原版当场调 `endAutoSave()`（同 [stepDataLoaded]
     * 的拆分口径，状态已指向 `LoadData_CallEventLoad`）。
     */
    private fun stepSystemLoad(): StateStep {
        val from = SystemStateCode.LoadData_CallSystemLoad
        host.unloadTempLoadedConstImageNames()
        host.unloadTempLoadedGraphicsImageNames()
        state = SystemStateCode.LoadData_CallEventLoad
        val called = calls.callFunction("EVENTLOAD", false, true)
        return done(
            from, "endSystemLoad",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTLOAD", "Process.cs:2669-2678",
        )
    }

    /** `endEventLoad`（Process.cs:2680-2683）：只有一句 `endAutoSave()`。 */
    private fun stepEventLoad(): StateStep =
        endAutoSave(
            SystemStateCode.LoadData_CallEventLoad, "endEventLoad",
            "Process.cs:2680-2683 / :2592-2601",
        )

    // ------------------------------------------------------------------
    // 存档/读档界面入口（SAVEGAME / LOADGAME 语句交接之后）：
    // beginSaveGame / beginLoadGame `Process.cs:2685-2697`，注册表 `:2023` / `:2027`（M11-E saveflow 接管）
    // ------------------------------------------------------------------

    /**
     * `beginSaveGame`（`Process.cs:2685-2690`）/ `beginLoadGame`（`:2692-2697`）—— 两个方法体
     * 恰三句、逐行同形，对拍如下：
     * 1. `console.PrintSingleLine(Lang.SystemLine.SaveQuestion / LoadQuestion.Text)`
     *    （`:2687` / `:2694`）→ [BeginHost.writeLine]（zhs 常量见 [SAVE_QUESTION_TEXT] /
     *    [LOAD_QUESTION_TEXT]，临时行保真度欠账与「无效的值」同款口径）；
     * 2. `state.SystemState = SaveGame_Begin / LoadGame_Begin`（`:2688` / `:2695`）——
     *    进入本 handler 的前提就是处于该状态（注册表 `:2023` / `:2027` 按状态分派），
     *    原版是**同值写**，移植侧同样无事发生；
     * 3. `printSaveDataText()`（`:2689` / `:2696`；方法体 `:2757-2815`）拆两半：
     *    槽位列表与 `[100] 戻る`（`:2758-2801`，读存档文件，属宿主设备侧）交
     *    [BeginHost.printSaveDataSlots]（默认 no-op）；后半 `setWaitInput()`（`:2802`）+
     *    按当前状态换态（`:2803-2810`：SaveGame_Begin→SaveGame_WaitInput、
     *    LoadGame_Begin→LoadGame_WaitInput，LoadGameOpenning 分支 `:2811-2814` 不经本对
     *    handler，不在这里承接）落成本步的换态与 [StepOutcome.AWAIT_INPUT]。
     *
     * **原版的「回来」语义（快照栈核对结论，完整证据见 proc/SaveLoadStateTakeoverTest 的 KDoc）**：
     * SAVEGAME/LOADGAME 语句只是把脚本**挂起**——`saveCurrentState` 把旧 `ProcessState` 压进
     * `prevStateList`（`Process.cs:1963`），换上的 `Clone()` 不复制 functionList
     * （`ProcessState.cs:475-484`）⇒ 本次 run 停摆；存档界面收口时 `loadPrevState()`
     * （`Process.cs:1968-1976`）把旧帧接回，脚本自 SAVEGAME 行的**下一条**继续
     * （`runScriptProc` 先 `ShiftNextLine()` `:1011` 再 `DoInstruction` `:1050`）。移植侧把
     * 语句半边的**同帧可观测效果**建模为整栈丢弃 = 本次 run 结束（M11-D save-11 的
     * `SaveLoadHandoff.functionListCleared` → `StmtOutcome.Unwind` 口径），**不复刻**快照栈：
     * 栈内容的唯一读者是那组尚未接线的存档 UI handler，且移植侧脚本帧是同步原生调用栈
     * （类 KDoc「本移植的脚本调用是同步的」），VM 里没有任何数据帧可供恢复。
     *
     * 本对的**下一步往哪去由宿主决定**：`SaveGame_WaitInput` / `LoadGame_WaitInput` 起的四段
     * 存档 UI handler（saveGameWaitInput `:2817-2855`、saveGameWaitInputOverwrite `:2857-2878`、
     * endCallSaveInfo `:2880-2889`、loadGameWaitInput `:2891-2957`，注册 `:2024-2026` /
     * `:2028-2030`）需要 `dataIsAvailable` / `page` / `saveTarget` / `vEvaluator.SaveTo` 等
     * 宿主面 ⇒ **本路不接管**，落到 [step] 的「未接管」阻塞步（BLOCKED，不是挂起）。
     *
     * TODO(verify)：挂起 BEGIN 与本交接的**消费顺序**不完全同形——原版 pending `begintype` 只在
     * 最外层函数 `Return` 时消费（`ProcessState.cs:352-362`），`runSystemProc` 不碰它；移植侧
     * [step] 的开头分支（本类 `:389-394` 一带）任何一步都先消费。脚本同帧既挂 `BEGIN` 又走到
     * SAVEGAME 时（`ProcessState.cs:482` 的 Clone 原样复制 begintype ⇒ 交接不清挂起），原版
     * 先跑 beginSaveGame、移植下一步即 REENTERED 跳过接管步。语料未见该组合；改动消费顺序 =
     * 动 BEGIN 全链语义，超出本路文件面，交组长裁决。
     */
    private fun stepSaveOrLoadGameBegin(save: Boolean): StateStep {
        val from = if (save) SystemStateCode.SaveGame_Begin else SystemStateCode.LoadGame_Begin
        // Process.cs:2687 / :2694 —— PrintSingleLine(SaveQuestion / LoadQuestion)。
        host.writeLine(if (save) SAVE_QUESTION_TEXT else LOAD_QUESTION_TEXT)
        // Process.cs:2688 / :2695 —— 同值状态写，无事发生（见方法 KDoc 第 2 句）。
        // Process.cs:2689 / :2696 —— printSaveDataText() 的槽位列表半边（:2758-2801）交宿主。
        host.printSaveDataSlots(save)
        // Process.cs:2802-2810 —— setWaitInput() + 换态。移植侧的「等输入」是宿主驱动式的，
        // 本步返回即交还控制权（同 stepTrainShowUserCom / stepAblupShowSelect 的口径）。
        state = if (save) SystemStateCode.SaveGame_WaitInput else SystemStateCode.LoadGame_WaitInput
        return StateStep(
            from = from,
            handler = if (save) "beginSaveGame" else "beginLoadGame",
            to = state,
            outcome = StepOutcome.AWAIT_INPUT,
            calledFunction = null,
            blocker = FlowBlocker(
                BlockerKind.HOST_INPUT, null, null, -1, -1,
                "存档/读档界面已显示，等待槽位选择输入（setWaitInput，Process.cs:2802）",
                "Process.cs:2685-2697 / :2757-2815",
            ),
            evidence = if (save) {
                "Process.cs:2685-2690 / :2757-2815"
            } else {
                "Process.cs:2692-2697 / :2757-2815"
            },
        )
    }

    // ------------------------------------------------------------------

    private fun done(
        from: SystemStateCode,
        handler: String,
        outcome: StepOutcome,
        called: String?,
        evidence: String,
    ): StateStep = StateStep(from, handler, state, outcome, called, null, evidence)

    companion object {
        /**
         * `Lang.Error.NotEnoughMoney.Text`（Process.cs:2625）。日原文
         * 「お金が足りません。」（Lang.cs:3150），zhs 译文「MONEY不足」
         * （lang.emuera-zhs.xml:742）。移植侧错误串与「无效的值」同口径取 zhs 资源。
         */
        internal const val NOT_ENOUGH_MONEY_TEXT = "MONEY不足"

        /**
         * `Lang.Error.OutOfStock.Text`（Process.cs:2630）。日原文
         * 「売っていません。」（Lang.cs:3154），zhs 译文「售罄」
         * （lang.emuera-zhs.xml:743）。
         */
        internal const val OUT_OF_STOCK_TEXT = "售罄"

        /**
         * `Lang.SystemLine.SaveQuestion.Text`（Process.cs:2687 的 PrintSingleLine）。日原文
         * 「何番にセーブしますか？」（Lang.cs:4334），zhs 译文「保存至几号存档？」
         * （lang.emuera-zhs.xml:1024）。移植侧错误/系统行文案取 zhs 资源，与「无效的值」同口径。
         */
        internal const val SAVE_QUESTION_TEXT = "保存至几号存档？"

        /**
         * `Lang.SystemLine.LoadQuestion.Text`（Process.cs:2694 的 PrintSingleLine）。日原文
         * 「何番をロードしますか？」（Lang.cs:4338），zhs 译文「读取几号存档？」
         * （lang.emuera-zhs.xml:1025）。
         */
        internal const val LOAD_QUESTION_TEXT = "读取几号存档？"
    }
}
