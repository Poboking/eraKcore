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

    /** `TrainName[i]`；null = 该格为空（原版空槽是 `null` 元素，`Process.cs:2265`/`:2281` 都按空跳过）。 */
    fun trainName(code: Int): String?

    /**
     * `TrainName.Length` —— 原版是 `ConstantData.TrainName` 的数组字段直读，**恒有值**：
     * `CSV/Train.csv` 没装载时它是一张 0 长度数组（`ConstantData.cs` 按
     * `MaxDataList[TRAIN 槽]` 建表），不是「没有这张表」。所以本函数返回 0 而不是 null。
     */
    fun trainNameCount(): Int

    /**
     * 系统字符串变量 `SAVEDATA_TEXT`（原版 `vEvaluator.SAVEDATA_TEXT`，
     * `VariableEvaluator.cs:119-129`：属性即 `varData.DataString[0]`）。
     *
     * 自动存档链的两端都经它：`beginAutoSave` 写缺省文案（`Process.cs:2573`），
     * `endAutoSaveCallSaveInfo` 把**当前值**交给 `SaveTo`（`:2583`）——中间的
     * `SAVEINFO` 脚本可以改写它（`CALLSAVEINFO` 语义，`PUTFORM` 追加路在
     * `StatementExecutor` 的 `Process.cs:1295-1306` 分支同格读写），所以
     * **写侧与读侧必须是同一格变量**，不能另存旁路状态。
     * 缺省 "" + 写 no-op = 假体/无变量池宿主；链在缺省下只会拿到空文案，不伪造内容。
     */
    fun savedataText(): String = ""
    fun setSavedataText(value: String) {}

    /**
     * 原版 `count == coms.Count`（`Process.cs:2437`）：`CALLTRAIN` 队列是否已**全部执行完**。
     * `endCallEventComEnd` 的 CALLTRAINEND 早退闸（`:2437-2448`）只在队列耗尽时触发。
     * 缺省 true = 无队列语义的假体按「已耗尽」处理（配合 [isCTrain] 缺省 false，
     * 该分支整体不触发，既有读数不变）。
     */
    fun commandsConsumed(): Boolean = true

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

    /**
     * `vEvaluator.SaveTo(saveTarget, vEvaluator.SAVEDATA_TEXT)`（`Process.cs:2583`；
     * 方法体 `VariableEvaluator.cs:2644-2685`：吞掉一切异常返回 false）。
     *
     * 自动存档链的唯一实档动作 —— 由 [com.eratw.port.core.proc.GameSession] 接到
     * `core/save` 既有实档路（`SystemOps.saveData` / `FileSystemOps`，与语句 `SAVEDATA`
     * 同一条 `SaveCapture → SaveCodec → io.write` 通道，`Process.cs:1327-1349` 同源）。
     *
     * 三态（接口缺省纪律「没有该能力不伪造结果」）：
     * - true  = 存档成功；
     * - false = 原版失败 ⇒ 两行报错（[AUTO_SAVE_ERROR_TEXT1] / [AUTO_SAVE_ERROR_TEXT2]，
     *   `Process.cs:2585-2586`）+ `ReadAnyKey`（`:2587`）；
     * - null（缺省）= 宿主没有存档能力 ⇒ 状态机返回 BLOCKED，既不报错也不继续收口。
     */
    fun saveTo(slot: Int, text: String): Boolean? = null

    /**
     * `console.ReadAnyKey()`（方法体 `EmueraConsole.cs:876-895`；无参 ⇒ `anykey = false`
     * ⇒ `InputType.EnterKey`，`:883-886`）—— **等玩家点一下**。两个调用点：
     * - `Process.cs:2587`：自动存档失败两行报错后的等待；
     * - `Process.cs:2450-2453` 的 `setWait()`（`:2047-2050`，方法体就是这一句）：
     *   `endCallEventComEnd` 尾部、回训练链之前。发起条件由
     *   [needWaitToEventComEnd]（`:2417` 置位）给出 —— 语料里「玩家选了、但自己判定不该做」
     *   的指令只打一行提示就 `RETURN` 负数、零状态变化（例：清掃 = `COM410`，
     *   `COMF410 掃除.ERB:18-26`），PC 上紧接着就是这次等待、提示被钉在屏上；移植侧没接时
     *   手机上一次回合整帧一次刷屏 ⇒ 提示行一冒出来就滚进历史 ⇒ 玩家只看到「按钮点了没反应」。
     *
     * 缺省 no-op = 无控制台的假体/宿主不阻塞（与旧链「失败也直进 [endAutoSave]」的读数一致）；
     * 生产宿主 [ConsoleBeginHost] 转交 [com.eratw.port.core.host.QueueInputSink.readAnyKey]
     * 并负责等前把整帧上屏（见该覆写）。
     */
    fun readAnyKey() {}

    /**
     * 指令链收尾那次按键等待（[readAnyKey]）的**适用范围**。
     *
     * 原版口径 = 「凡是走 `SOURCE_CHECK` 的指令都等一次」（`Process.cs:2417` 置位 →
     * `:2450-2453` 的 `setWait()`），缺省 `true` 即原样，没表态的宿主（含 [BeginFlow.NONE]
     * 与各测试假体）行为零变化。
     *
     * 生产宿主收窄的理由与取值见
     * [com.eratw.port.core.proc.ConsoleBeginHost.commandEndWaitComCodes]：手机上一次等待
     * = 一次额外点击，而语料里 移動 是「每走一步就 `RETURN 1` 回训练链」的高频指令
     * （`魔改内容/qol/qol_MAP.ERB:59-66`），那一步等待会吃掉玩家下一次点目的地。
     *
     * @param comCode 本次指令的训练码（`SELECTCOM`，原版 `Process.cs:2380` 写入；
     *   注意取的是 **`SOURCE_CHECK` 之前**的值 —— 那之后 `RESULT` 已被脚本函数的返回值覆盖）
     * @param result 本次指令自己的返回值（`COM<n>` 的 `RETURN`）：负数 = 语料自己判定
     *   「不该做」，只打一行提示就返回、零状态变化（例 `COMF410 掃除.ERB:18-26`）
     */
    fun shouldWaitAfterCommand(comCode: Int, result: Long): Boolean = true

    /**
     * eraTW 扩展配置 `AUTO_SAVE` 的**「前」档**（裁定依据：报告引新版 `Process.cs:1849-1891`
     * 的 `endCallEventComEnd` 分支②；缺省 `CompatiCalleventToEnd`＝「後」，报告引
     * `ConfigData.cs:66`）。
     *
     * **TODO(verify)**：本仓 reference 的 `ConfigData.cs:66` 是 `FontSize`、全仓无
     * `CalleventToEnd` 命中，`endCallEventComEnd` 在本版位于 `Process.cs:2424-2456` 且
     * 无此分支 —— 该配置项属于 4.981 实跑的 SkiaX 分支，需反编译 `Emuera_skiaV16_x64.exe`
     * 才能给行号级证据（BOARD 2026-10-30 条已登记「反编译参考已非权威」）。
     * 缺省 false =「後」= 不在 [stepCallEventComEnd] 内联走自动存档链。
     */
    fun autoSaveAtComEnd(): Boolean = false

    /** `endCallShowStatus` 的 comAble 清零（Process.cs:2246-2254）。 */
    fun resetComAble() {}

    /** `console.PrintC(getTrainComString(...))`（Process.cs:2273）。默认丢弃显示。 */
    fun onComAble(code: Int, index: Int) {}

    /** `Config.ComAbleDefault`（Process.cs:2293）。 */
    val comAbleDefault: Int get() = 1

    /**
     * 系统输入是否可能是 `comAble` **下标** —— 即宿主有没有渲染
     * `Process.endCallComAbleXX` 那组 `PrintC` 按钮。
     *
     * 原版 `trainWaitInput`（`Process.cs:2344-2349`）拿 `systemResult` 当 `comAble` 下标反查，
     * 前提是控制台上真有 `getTrainComString` 打出来的 `乳交[ 51]` 这类按钮
     * （`Process.cs:2257-2261`：文本 `名称[下标]`，值 = 下标）。
     *
     * 移植侧生产宿主 [ConsoleBeginHost] **不渲染**这组按钮（`onComAble` 保持缺省 no-op，
     * 见其 KDoc「onComAble、resetComAble 保持缺省」），指令菜单完全由语料自己打印。
     * 而画蛇添足版 `@BUILD_COM_BUTTON_HTML`（`魔改内容/PRINT系/HTML_PRINT_Components.ERB:200`）
     * 的按钮值是**训练码本身**：
     * `<button value='{COM_VAL}'>%COM_TEXT%[{COM_VAL,3}]</button>`，
     * 菜单上写的就是 `乳交[ 82]`、`正常位[ 60]`。
     *
     * 于是 `systemResult` 永远是训练码；再拿它查 `comAble` 会**错派发**（实测读数见下），
     * 正确的落点是 `USERCOM` 支 —— 语料 `USERCOM.ERB:84-86` 那两行注释与代码
     * （`;SELECTCOMとTFLAG:3(SELECTCOM退避)に入力値セット` / `SELECTCOM = RESULT` /
     * `TFLAG:3 = SELECTCOM`）就是专门为「输入值 = 训练码」准备的；配套的还原在
     * `@SOURCE_CHECK`（`SOURCE.ERB:25` 的 `SELECTCOM = TFLAG:3`，用来抵消 `DOTRAIN 999`
     * 对 `SELECTCOM` 的 dummy 覆盖）。若走 `comAble` 反查，这两处都成了死代码/自毁代码。
     *
     * 实测（桌面复现装置，探针用完已删；时停 + 咲夜 + 扒光但内裤未脱，真产物 + `save03.sav`）：
     * - `comAble` 表 33 项、最大下标 193，其中 `comAble[82] = 180`、`comAble[60]` 无此项；
     * - 旧口径点 `乳交[ 82]` ⇒ `SELECTCOM = comAble[82] = 180` ⇒ 跑的是 `COM180`（潤滑液）；
     *   点 `正常位[ 60]` ⇒ 查不到 ⇒ 退回 `USERCOM`（这条反而是对的）。
     *   同一状态下 `CAN_COM82(0)` 的语料自报明细是
     *   `受虐属性LV1(2) - 坚强(5) - 自尊心高(15) + 侍奉精神LV2(8) + 献身的(6) = -4 < 实行值30`
     *   —— 也就是「走对支时会被 `CAN_COM` 按実行値拒掉」，而错派发把这条判定整个绕过去了。
     *
     * 缺省 **true** = 按原版口径当**下标**解释（`Process.cs:2344-2349`），这是保守缺省：
     * 没表态的宿主（含 `BeginHost.NONE` 与各测试假体）行为一律不变。
     * 只有 [ConsoleBeginHost] 这类**明确从不渲染下标按钮**的宿主才置 false，
     * 让 `trainWaitInput` 把输入原样交 `USERCOM` 支。
     */
    val systemResultIsComAbleIndex: Boolean get() = true

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

    /**
     * `console.LastLineIsEmpty`（调用点 `Process.cs:2428`；属性体 `EmueraConsole.cs:526-537`：
     * 显示行表为空 ⇒ false，否则末显示行整行内容 `ToString().Trim()` 为空 ⇒ true ——
     * `ConsoleDisplayLine.ToString()` 是整行内容（文本与按钮同池）的拼接
     * （`ConsoleDisplayLine.cs:127-140`），不是「无按钮」）。与 [lastLineIsTemporary]
     * 同刻同源，调用点全在临时行门的**内层**（`Process.cs:2426`/`:2516`/`:2646`），
     * 只在该门成立时才有意义。
     *
     * 缺省 false = 宿主没有控制台可读（接口缺省纪律，与 [lastLineIsTemporary] 同口径）。
     */
    fun lastLineIsEmpty(): Boolean = false

    /**
     * `console.deleteLine(n)`（调用点 `Process.cs:2430`/`:2194`/`:2518`/`:2648`；方法体
     * `EmueraConsole.cs:2999-3051`）：
     * 从显示行表尾删除至多 n 条**逻辑行**（折行续行随主行一起走，被删行上的按钮随之消亡）。
     * 状态机调用点：EVENTCOMEND 空行半句（`:2428-2432`，删 2）+ W10 接线的三处 ——
     * 标题菜单 else 支（`:2194`，删 1）、ABLUP 收尾空行半句（`:2518`，删 2）、
     * 商店收尾空行半句（`:2648`，删 2）+ OBS2-L1 换道的两处商店失败支
     * （`:2624`/`:2629`，各删 1）与 ABLUP 缺函数支（`:2498`，删 1，W10 后随 ECHO-INPUT
     * 波次换道）—— 删 1 的各支删除对象 = 玩家键入的回显行
     * （`EmueraConsole.cs:1280-1282`），全部与 [printTemporaryLine] 的提示成对出现。
     * 缺省 no-op = 宿主没有可删行的控制台。
     */
    fun deleteLine(count: Int) {}

    /**
     * `console.PrintTemporaryLine(str)`（调用点 `Process.cs:2431`/`:2195`/`:2499`/`:2519`/
     * `:2625`/`:2630`/`:2649`；
     * 方法体
     * `EmueraConsole.cs:3053-3056` → `PrintSingleLine(temporary: true)`）：追加一条临时行。
     * 与 [writeLine] 的差别：writeLine 落**普通行**（追加即存续），本方法落**临时行**
     * （REUSELASTLINE 同族）—— 追加新显示行时若末行是临时行会先被删掉
     * （`EmueraConsole.cs:2891-2893` 的 `addDisplayLine` 门），所以提示打在菜单重等的
     * 窗口里可见、下一次正式输出到达时收走，与原版同寿。
     * 缺省 no-op = 宿主没有临时行通道。
     */
    fun printTemporaryLine(text: String) {}

    /**
     * `console.updatedGeneration = true`（调用点 `Process.cs:2433`；字段
     * `EmueraConsole.cs:106`。**字段直写**，不是 `UpdateGeneration()`（`:770-774`）——
     * 那是按钮构造专用、连 lastButtonGeneration 一起前移，本点位用它旧按钮照样死，
     * 通道语义见 [com.eratw.port.core.host.LineBufferConsoleSink.markGenerationUpdated]）。
     *
     * 状态机调用点（W8-gen 起）：`endCallEventComEnd` 分支①（[stepCallEventComEnd]，即
     * `Process.cs:2433`）+ W9-gen 补的同款四处 —— 标题菜单（[stepOpenning]，`:2196`）、
     * ABLUP 缺函数（[stepAblupWait]，`:2500`）、ABLUP 收尾临时行（[stepAblupCall]，`:2521`）、
     * 商店收尾临时行（[stepShopEventBuy]，`:2651`）—— 全部是「非法输入/临时行收尾 →
     * 重等输入」形态：重等前置脏，下一次等待入口的 `newGeneration`
     * （`EmueraConsole.cs:789-796`）才不会因脚本行推进而把当前页世代前移、
     * 把屏上旧菜单按钮整批作废（「点了没反应」）。
     * `Process.cs:2852`/`:2868`/`:2930`（存读档 UI handler 内）暂无移植落点——
     * 那四段 handler 未接管（`step` 落「未接管」BLOCKED），接管时须随行补置位。
     * 缺省 no-op = 宿主没有世代机制可置（`NONE` 与假体行为不变）。
     */
    fun markGenerationUpdated() {}

    /** 系统行输出（无效值提示等）。默认丢弃。 */
    fun writeLine(text: String) {}

    /**
     * `console.PrintError(str)`（调用点 `Process.cs:2585-2586` 自动存档失败两行；
     * 方法体 `EmueraConsole.cs:3098-3116`）。与 [writeLine] 的语义差集（逐句对照）：
     * - 空串早退（`EmueraConsole.cs:3100-3101`）—— writeLine 无此门；
     * - `PrintFlush(force:false)` 先结掉打印缓冲（`:3107`）；
     * - `UseUserStyle = false` 粘滞复位（`:3108`；此后入缓冲的内容都回默认样式，
     *   直到下一条 PRINT 系语句写真，见 `ConsoleSink.setUseUserStyle` 的 KDoc）——
     *   writeLine 走当前用户样式（SETCOLOR/FONTSTYLE/SETFONT 全部生效）；
     * - `PrintPlainwithSingleLine` 出**纯文本、不折行、不参与 `[...]` 自动按钮/HTML
     *   解析**的行（`:3109`；PrintStringBuffer.cs:125-134 的 createPlainButton 口径）——
     *   writeLine 的 `Print` 会解析自动按钮；
     * - `addDisplayLine(force_LEFT: true)` 强制左对齐（`:3112`）—— writeLine 吃当前
     *   ALIGNMENT；
     * - `RefreshStrings(force_Paint:false)`（`:3113`）的对位是
     *   `LineBufferConsoleSink.printError` 的 `publishFrame`（错误画面必上屏，
     *   `FrameGateUnfreezeTest` 已钉）；
     * - `Program.DebugMode` 下先 `DebugPrint`+`DebugNewLine` 双打（`:3102-3106`）——
     *   本移植恒非调试态（`ErbPreprocessor.kt:63`「本移植恒 false」、
     *   `Symbols.kt:342` 的 `[IF_DEBUG]` 开关），该半句**不适用**，不补。
     *
     * 移植侧真实现对位在 sink：`LineBufferConsoleSink.printError`
     * （`ConsoleSink.kt:376` 声明、`LineBufferConsoleSink.kt:2272-2298` 实现，
     * 前六条差集全部已落地）。本方法缺省**降级转 [writeLine]** —— 未覆写的宿主
     * 保持既有普通行行为。生产宿主 `ConsoleBeginHost` 已由 OBS2-L1 组长接线覆写
     * （转 `LineBufferConsoleSink.printError`，`ConsoleBeginHost.kt` 的 `printError`），
     * 自动存档失败两行的样式复位/强制左对齐/不折行/不吃按钮解析/帧必上屏五格自此到位。
     */
    fun printError(text: String) { writeLine(text) }

    /**
     * `console.PrintSingleLine(str)`（调用点 `Process.cs:2687`/`:2694` 的存读档问句；
     * 方法体 `EmueraConsole.cs:3147-3165`）—— **非临时行**的独立单行输出。与 [writeLine]
     * （→ `console.Print` + `NewLine`，`EmueraConsole.cs:3167-3172`/`:3334-3336`）的差：
     * - 先 `PrintFlush(force:false)` 把**未结的打印缓冲**单独结掉（`:3155` → `:3375-3383`
     *   的 `printBuffer.Flush` 走折行路），再 `BufferToSingleLine(force:true)`
     *   （`:3158` → `:3339-3353` → `printBuffer.FlushSingleLine`，`PrintStringBuffer.cs:170-177`）
     *   把本句排成**一条不折行**的显示行；
     * - [writeLine] 的 `Print` 是往**同一个** printBuffer 里追加（`EmueraConsole.cs:3167-3172`），
     *   上一句 PRINTN/PRINTC 留下的未结残段与本句**并进同一段**再一起折行 ⇒ 问句与残段
     *   粘成同一逻辑行（原版这里不会：残段先被 `PrintFlush` 单独结掉）。
     *
     *   注：两条路在「末显示行 `IsLineEnd == false`」时都会被 `addDisplayLine`
     *   （`EmueraConsole.cs:2948-2965`）并进去，**看得见差别的是折行**：本方法那条
     *   走 `FlushSingleLine` 不折行（`PrintStringBuffer.cs:170-177`），`NewLine` 那条
     *   走 `printBuffer.Flush` 按宽度折（`EmueraConsole.cs:3375-3383`）。
     *   钉测 `WaitInputGenerationTest` 即钉这一格。
     *
     * 移植侧对位在 sink：`ConsoleSink.printSingleLine`（`ConsoleSink.kt:83` 声明、
     * `LineBufferConsoleSink.kt:1486-1491` 实现，`noBreak` 单行见 `:2736-2747`）——
     * 通道**现成**，本方法只是 BeginHost 侧的口。缺省**降级转 [writeLine]**（纪律照
     * [printError]）：未覆写的宿主（含 `NONE` 与各测试假体）行为与本刀之前逐字一致，
     * 不伪造单行语义。生产宿主 [ConsoleBeginHost] 覆写转 `console.printSingleLine`。
     */
    fun printSingleLine(text: String) { writeLine(text) }

    /**
     * 「把当前这一屏叫出来的脚本函数」的登记（OBS2-L5）—— 对位原版
     * `ProcessState.currentLine` 这个**字段**（`ProcessState.cs:40-50`）：C# 只在 VM 真的执行一行时
     * 写它（`FunctionIdentifier.cs:292` 的主循环 `state.CurrentLine = enumerator.Current`，另有
     * `:3128`/`:3179` 的 `#IF` 分支与 `Process.cs:1814` 的 STRDATA），**函数返回、栈清空都不清**
     * （全仓无置 null 的写点）⇒ 系统菜单（商店/ABLUP/指令/标题）在 `setWaitInput`
     * （`Process.cs:2052-2088`）挂起时，`process.getCurrentLine`（`Process.cs:142`）读到的就是
     * 「印完这一屏之后停住的那条脚本行」，同一屏重问多少次都是同一个身份位
     * ⇒ `EmueraConsole.cs:789-796` 走 else 支、`lastButtonGeneration` 原地不动、屏上旧按钮
     * 保持可点。移植侧的等待点没有那个字段可退：BEGIN 状态机的每次等待都发生在 `vm.run`
     * **返回之后**（`Vm.unwindScopes` 把帧深收回 -1，`Vm.kt:947-955`；引擎侧函数走
     * `runEntry` → `vm.run`，`ProcessRuntime.kt:2901-2923`），现算的
     * `ProcessRuntime.scriptLineTokenNow`（`:1242`）在无帧时恒 -1（桌面实测：真
     * [GameSession] 装配下 `begin(SHOP)` 泵到 `Shop_WaitInput` 前后读数都是 -1；
     * 同一结论的独立记载见 `core/diag/PageFunctionTrail.kt:9-14`「等待点全部发生在
     * BeginFlow 的 Kotlin 步、相邻两次 ERB 调用之间 ⇒ 无帧」）⇒ 直接把现算通道接进
     * [ConsoleBeginHost] 等于没接。本方法就是缺的那一格：**由最清楚「这一屏是谁印的」的
     * [BeginFlow] 在每次真的跑过脚本函数之后把函数名登记给宿主**。
     *
     * 登记点 = [BeginFlow] 的脚本调用统一入口（`callScript`），且只在被调方**真的存在**
     * （`callFunction` 返回 true）时登记：缺函数支一支脚本都没跑（`Process.cs:2495` 的
     * `ABLUP{N}` 缺失、`:2618` 的 `EVENTBUY` 缺失），身份位必须留在印上一屏的那个函数上，
     * 与原版「没执行行 ⇒ `currentLine` 不变」同形。
     *
     * 粒度差（如实登记）：原版是**行**级、这里是**函数**级（宿主拿不到无帧时刻的 pc）。
     * 唯一能观察到的差别是「同一函数两次叫屏之间没盖过任何按钮」—— 而换屏必然重印按钮、
     * 按钮盖章就把 `lastButtonGeneration` 推到新世代并置脏（`LineBufferConsoleSink.stampButtonGeneration`
     * `:753-758`，写点 `:1930`/`:1949`/`:2246`/`:2769`/`:3150`），`EmueraConsole.cs:789` 的
     * `!updatedGeneration` 门随即短路，身份位在这一格**根本不参与判定** ⇒ 函数级与行级
     * 在可观测面等价。真换了另一支函数叫屏（`SHOW_SHOP` → `EVENTBUY` → …）身份位照旧变，
     * 原版「脚本走远 ⇒ 旧页作废」的判据不被本刀冻住（钉在 WaitInputGenerationTest）。
     *
     * 缺省 no-op = 宿主没有世代概念（[BeginHost.NONE] 与各测试假体），行为与本刀之前逐字一致。
     */
    fun noteWaitPageProducer(functionKey: String) {}

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
 * @param autoSaveSavedataText `beginAutoSave` 写 `SAVEDATA_TEXT` 的缺省文案提供者
 *   （原版 `Process.cs:2573` `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "`；
 *   移植侧时钟可注入以稳定单测读数，缺省用本地时钟的同一格式）。
 */
class BeginFlow(
    private val calls: BeginCallPort,
    private val host: BeginHost = BeginHost.NONE,
    private val autoSaveSavedataText: () -> String = { defaultAutoSaveSavedataText() },
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
     * `Process.NeedWaitToEventComEnd`（`Process.cs:128`）：`endCallSourceCheck` 在把状态交给
     * `Train_CallEventComEnd` 之前置真（`:2417`），`endCallEventComEnd` 据此在回菜单前
     * `setWait()`（`:2450-2453`）。原版清位有两处（`EmueraConsole.cs:894` 进入等待即清、
     * `Process.cs:2454` 返回后再清），本移植取前者那一处，时机与理由见消费点。
     *
     * 置真条件是 `RESULT != 0`（`endCallComXX`，`:2404-2407` 的 0 直接跳 `endCallEventComEnd`，
     * 不经 `endCallSourceCheck` ⇒ 不等待）—— 语料正是用负数 RESULT 表达「选了但不该做」。
     *
     * **值从「恒真」改成策略判定**（2026-11-02）：置位时机与消费点都不动，但置进去的值来自
     * [pendingComEndWait]（在 `endCallComXX` 那一刻算好的 [BeginHost.shouldWaitAfterCommand]），
     * 好让生产宿主把这条等待收窄到「只打一行提示就返回」的指令上。
     */
    private var needWaitToEventComEnd = false

    /**
     * `endCallComXX` 那一刻算出的「这次指令收尾要不要等一次按键」，等 `endCallSourceCheck`
     * 把它搬进 [needWaitToEventComEnd]。
     *
     * 为什么要提前算：`SOURCE_CHECK` 是脚本函数，它一返回就把 `RESULT` 覆盖成自己的返回值
     * （`ステータス計算関連/SOURCE/SOURCE.ERB:1`），到收尾点已经读不到「这条指令自己被拒了没有」；
     * `SELECTCOM` 同理要靠 `SOURCE_CHECK` 里的 `SELECTCOM = TFLAG:3` 还原，也不能等到收尾点再读。
     * 故判定输入（训练码 + 指令自己的 `RESULT`）只在 `endCallComXX` 这一步是准的。
     */
    private var pendingComEndWait = false

    /**
     * endNormal 终局粘滞位。第一次 `Normal` 分支报 FAILED 后置位；此后 [step] 幂等返回
     * 终局步，不再消费挂起 BEGIN、不再进入任何输入等待。证据链见 [terminalEndNormalStep]。
     */
    private var endNormalTerminal = false

    /**
     * `Process.saveTarget`（`Process.cs:140`；构造期 `:191` 复位 -1）。自动存档固定槽 99
     * （`Process.cs:136` `const AutoSaveIndex = 99`，`:2572` 置位、`:2583` 判 `== 99` 才实存、
     * `endCallEventShop` `:2555` 每次商店返回复位）。
     *
     * 帧状态挂本实例（禁静态可变全局，AGENTS 铁律 4）。
     *
     * **TODO(verify)**：挖虫报告还称「`Initialize` 复位 saveTarget=-1（`Process.cs:983-984`）」
     * 与「saveTarget>=0 时不重复进 AUTO_SAVE（`:1656-1659`）」——这两处在本地 1.824 反编译的
     * 对应行号是 `SerializeData`/`ARRAYSHIFT` 方法体，`saveTarget` 全量引用仅
     * `:140/:191/:2555/:2572/:2583/:2832/:2882`。前者按报告裁定落进
     * [resetToBootState]（回标题=回出厂态的移植对位）；后者落进 [stepShopEventShop] 的
     * 入口闸。两处均缺新版行号级证据，待反编译 `Emuera_skiaV16_x64.exe` 复核。
     */
    var saveTarget: Int = -1
        private set

    /**
     * `Process.needCheck`（`Process.cs:188` 构造期置 true；`endCallEventComEnd`
     * 尾部 `:2449` 置 true、CALLTRAINEND 早退 `:2445` 置 false）。
     * 它是 `:2426-2436` 临时行早退闸的三个条件之一。
     */
    private var needCheck = true

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
        if (no >= calls.trainNameCount()) {
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
        // 报告裁定的 `Initialize` 复位项（挖虫报告引 `Process.cs:983-984`；本地 1.824 只在
        // 构造器 `:191` 复位，`Initialize` 本体 `:195+` 不碰 —— 版本差，见 [saveTarget] KDoc
        // 的 TODO(verify)）。回标题=新会话出厂态，移植侧对位本函数。
        saveTarget = -1
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
            SystemStateCode.Train_CallEventComEnd -> stepCallEventComEnd()
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
            // 注册表 `Process.cs:2031-2032`：CallSaveInfo 的 handler 是
            // endAutoSaveCallSaveInfo（内含 SaveTo(99) 真档）；CallUniqueAutosave 才是
            // 裸 endAutoSave（`SYSTEM_AUTOSAVE` 自管存档）。Skipped 在原版不是 runSystemProc
            // 入口（endCallEventShop :2561-2562 赋态后直调），移植侧拆步后由本分支兜住，
            // 判据同 CallSaveInfo（saveTarget 已清 ⇒ 不重存）。
            SystemStateCode.AutoSave_CallSaveInfo,
            SystemStateCode.AutoSave_Skipped ->
                endAutoSaveCallSaveInfoStep(state, "endAutoSaveCallSaveInfo")
            SystemStateCode.AutoSave_CallUniqueAutosave -> endAutoSave(state, "endAutoSave")
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
        if (callScript("SYSTEM_TITLE", false, false)) {
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
                if (callScript("TITLE_LOADGAME", false, false)) {
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
                // :2194-2195 —— 先删 1 条逻辑行再画临时行（W10 保真度收口：原先经
                // [BeginHost.writeLine] 落普通行，提示会残留屏上；换道后与原版同寿，
                // 被下一次正式输出收走，文案见 [INVALID_VALUE_TEXT]）。
                host.deleteLine(1)
                host.printTemporaryLine(INVALID_VALUE_TEXT)
                // Process.cs:2196 console.updatedGeneration = true —— 标题菜单非法输入的
                // 重等（:2197 openingInput() → setWaitInput）之前置脏：本次等待入口的
                // newGeneration 才不会因提示行推进过脚本行而把屏上 [0]/[1] 菜单按钮的
                // 世代前移（EmueraConsole.cs:789-796），「点了没反应」。只置脏不前移的
                // 通道语义见 [BeginHost.markGenerationUpdated]。
                host.markGenerationUpdated()
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
        val called = callScript("EVENTFIRST", true, true)
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
        val called = callScript("EVENTTRAIN", false, true)
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
            val called = callScript("EVENTCOM", false, true)
            done(
                from, "endCallEventTrain",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "EVENTCOM", "Process.cs:2228-2234",
            )
        } else {
            state = SystemStateCode.Train_CallShowStatus
            val called = callScript("SHOW_STATUS", true, false)
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

        // 上一个 COM_ABLE<n> 已返回：RESULT != 0 才登记（Process.cs:2265-2282）。
        if (lastCalledComable in 0 until count && calls.trainName(lastCalledComable) != null) {
            lastAddCom++
            if (calls.result() != 0L) registerComAble(lastAddCom, lastCalledComable)
        }
        while (true) {
            lastCalledComable++
            if (lastCalledComable >= count) {
                // count == 0（Train.csv 没装载）时这里第一次就命中 —— 与原版同形：
                // `while (++lastCalledComable < TrainName.Length)` 一次不进，
                // 落到 `:2312` 的 `lastCalledComable >= TrainName.Length` 分支照常
                // 调 SHOW_USERCOM。原版没有「表空 = 宿主未提供」这一终局。
                state = SystemStateCode.Train_CallShowUserCom
                val called = callScript("SHOW_USERCOM", true, false)
                return done(
                    from, "endCallComAbleXX",
                    if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                    "SHOW_USERCOM", "Process.cs:2312-2318",
                )
            }
            if (calls.trainName(lastCalledComable) == null) continue
            val name = "COM_ABLE$lastCalledComable"
            if (!callScript(name, false, false)) {
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
     * 分支（非 CTrain，`:2344-2349` + `:2361-2373`），由 [BeginHost.systemResultIsComAbleIndex]
     * 二选一 —— 判据是**宿主的按钮值到底是下标还是训练码**，不是「有没有查到」：
     * - **宿主渲染了下标按钮**且 `0 <= systemResult < comAble.Length` 且登记值 >= 0 ⇒
     *   `SELECTCOM = comAble[systemResult]` → `callEventCom()`（`:2361-2364`；
     *   `Train_CallEventCom` + `EVENTCOM`，缺失时原版当场 `endEventCom()`，由
     *   [SystemStateCode.Train_CallEventCom] 那步的 NOT_CALLED 继续口径承接）；
     * - **宿主没渲染下标按钮**（生产 [ConsoleBeginHost] 口径）⇒ `systemResult` 是画蛇添足版
     *   `@BUILD_COM_BUTTON_HTML` 的**训练码本身**，一律 `RESULT = sysResult` →
     *   `Train_CallEventComEnd` + `USERCOM`（force，`:2366-2373`），由语料自己分派
     *   （`USERCOM.ERB:85-86` 设 `SELECTCOM`/`TFLAG:3`，`:137` `DOTRAIN 999`，
     *   `@SOURCE_CHECK` 还原后经 `@SOURCE_CALLCOM` 派 `COM{SELECTCOM}`）。
     *   这一支同时承载语料的 `COM_ABLE` / `CAN_COM`（実行値）/ 妊娠 / 时停瞪人等判定，
     *   **不能拿 `systemResult` 去查 `comAble`**（那是显示位：`comAble[82]=180` ⇒ 点乳交跑潤滑液，
     *   实测见 [BeginHost.systemResultIsComAbleIndex]）。
     *   这里写的是**脚本变量** `RESULT`（`vEvaluator.RESULT`，`:2372`），
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
        // 两种按钮编号约定，二选一（见 [BeginHost.systemResultIsComAbleIndex]）：
        // - 宿主渲染了 `乳交[ 51]` 这类**下标**按钮 ⇒ 按原版反查 `comAble`（`:2344-2349`）；
        // - 宿主没渲染（生产 [ConsoleBeginHost]）⇒ 输入是画蛇添足版
        //   `@BUILD_COM_BUTTON_HTML` 的 `<button value='{COM_VAL}'>乳交[{COM_VAL,3}]`，
        //   也就是**训练码本身**。它绝不能拿去查 `comAble`（那是显示位，不是指令号），
        //   而应原样交 `USERCOM` 支自己分派 —— 语料 `USERCOM.ERB:84-86` 的注释与代码
        //   （`SELECTCOM = RESULT` / `TFLAG:3 = SELECTCOM`）就是为这条支写的。
        val num: Int? = if (host.systemResultIsComAbleIndex && input >= 0L && input < Int.MAX_VALUE.toLong()) {
            comAble[input.toInt()]?.takeIf { it >= 0 }
        } else {
            null
        }
        return if (num != null) {
            calls.setSelectCom(num.toLong())
            state = SystemStateCode.Train_CallEventCom
            // Process.cs:2361-2364 → :2386 —— callEventCom 的 `UpdateAfterInputCom()`
            // （VariableEvaluator.cs:1756-1766）：每次派发 EVENTCOM 前清 NOWEX。
            calls.afterInputComReset()
            val called = callScript("EVENTCOM", false, true)
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
            val called = callScript("USERCOM", true, false)
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
        val called = callScript(name, true, false)
        return done(
            SystemStateCode.Train_CallEventCom, "endEventCom",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            name, "Process.cs:2394-2400",
        )
    }

    private fun stepTrainCallComXX(): StateStep {
        val from = SystemStateCode.Train_CallComXX
        val comResult = calls.result()
        if (comResult == 0L) {
            // Process.cs:2404-2407 直接跳 endCallEventComEnd，不经 SOURCE_CHECK ⇒ 这条支没有
            // setWait()。顺带把上一条指令可能留下的「要不要等」清干净，免得串到这次收尾。
            pendingComEndWait = false
            state = SystemStateCode.Train_CallEventComEnd
            return done(from, "endCallComXX", StepOutcome.CONTINUE, null, "Process.cs:2404-2407")
        }
        // 判定输入只在这一刻是准的（SELECTCOM 尚未被 SOURCE_CHECK 还原/改写、RESULT 还是本指令的
        // 返回值），见 [pendingComEndWait]。
        pendingComEndWait = host.shouldWaitAfterCommand(calls.selectCom().toInt(), comResult)
        state = SystemStateCode.Train_CallSourceCheck
        val called = callScript("SOURCE_CHECK", true, false)
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
        // Process.cs:2417 —— 原版在派发 EVENTCOMEND 之前置 NeedWaitToEventComEnd；消费点
        // 在 endCallEventComEnd（:2450-2453），语义见 [needWaitToEventComEnd]。
        // 置进去的是 endCallComXX 那一刻算好的策略结果（缺省恒真 = 原版口径）。
        needWaitToEventComEnd = pendingComEndWait
        val called = callScript("EVENTCOMEND", false, true)
        return done(
            SystemStateCode.Train_CallSourceCheck, "endCallSourceCheck",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTCOMEND", "Process.cs:2413-2422",
        )
    }

    /**
     * `endCallEventComEnd`（`Process.cs:2424-2456`）—— 旧移植只接了尾部「回训练链」一条，
     * 本路补齐三条（挖虫波 B2，P1）：
     *
     * 1. **临时行早退**（`:2426-2436`）：`LastLineIsTemporary && !isCTrain && needCheck` ⇒
     *    `endCallShowUserCom()`（`:2434`；复用屏上菜单重等输入，移植侧拆步进
     *    [stepTrainShowUserCom]）。内层空行半句已接：`LastLineIsEmpty`
     *    （`EmueraConsole.cs:526-537`）⇒ `deleteLine(2)` + `PrintTemporaryLine(無効な値です)`
     *    （`:2428-2432`，经 [BeginHost.deleteLine] / [BeginHost.printTemporaryLine]，
     *    文案 [INVALID_VALUE_TEXT]）。`:2433 console.updatedGeneration = true`
     *    已接（[BeginHost.markGenerationUpdated]，W8-gen）—— 保住复用菜单按钮的
     *    可点世代，机制依据见方法体内注。
     * 2. **eraTW `AUTO_SAVE=="前"`**（裁定报告引新版 `Process.cs:1849-1891`；本地 1.824 无此
     *    分支，`ConfigData.cs` 全仓无 `CalleventToEnd` 命中 ⇒ 版本差，TODO(verify) 见
     *    [BeginHost.autoSaveAtComEnd]）：在指令链收尾点**内联**走一遍 B1 存档链
     *    （置 99、写 `SAVEDATA_TEXT`、`SaveTo(99)`、失败双行+ReadAnyKey），队列清法照
     *    `:2437-2442`（`isCTrain=false; coms.Clear(); count=0` ⇒ [BeginCallPort.clearCommands]）。
     *    缺省 `CompatiCalleventToEnd`＝「後」⇒ 本分支不触发，既有读数不变。
     *    报告还写本分支「`CALLFUNCTION endCallEventComEnd`（有错误双行报+ReadAnyKey）」——
     *    该短语在本地 reference 无对应物，移植侧按「内联存档后即落 ③/尾部」建模，
     *    不是二次重入自身（形态待新版反编译坐实，TODO(verify)）。
     * 3. **CALLTRAINEND 早退**（`:2437-2448`）：`isCTrain && count==coms.Count` ⇒ 清队列 +
     *    `CALLTRAINEND`（force=false）；调得动则 `needCheck=false`（`:2445`）并**原地返回**
     *    （状态不切 —— 原版靠 runSystemProc 的循环再进本 handler，这次 `isCTrain` 已 false，
     *    落到尾部）。
     *
     * 尾部 `:2449-2455`：`needCheck=true`；`NeedWaitToEventComEnd` 的 `setWait()`
     * （`:2450-2453`，`:2417` 置位）= [BeginHost.readAnyKey] —— 指令链收尾回训练链之前
     * 等玩家点一下（发起条件与玩家侧症状见该钩子；**等不等**由 [BeginHost.shouldWaitAfterCommand]
     * 在 `endCallComXX` 那一刻判好，见 [pendingComEndWait]）；随后 [SystemStateCode.Train_CallEventTrain]
     * （`endCallEventTrain` `:2226-2242`，旧移植口径不变）。
     */
    private fun stepCallEventComEnd(): StateStep {
        val from = SystemStateCode.Train_CallEventComEnd
        // ① Process.cs:2426-2436 —— 临时行 + 非批量训练 + needCheck ⇒ 复用屏上菜单重等输入。
        if (host.lastLineIsTemporary() && !calls.isCTrain() && needCheck) {
            // :2428-2432 —— 末行还是一条**空**临时行（指令链零输出的形态）⇒
            // 删 2 逻辑行 + 画「无效的值」临时行。删除与提示成对，文案见 [INVALID_VALUE_TEXT]。
            if (host.lastLineIsEmpty()) {
                host.deleteLine(2)
                host.printTemporaryLine(INVALID_VALUE_TEXT)
            }
            // :2433 console.updatedGeneration = true —— 不推进就回不去：重等输入的 newGeneration
            // 在「标志未置且脚本行变了」时会推进按钮世代（EmueraConsole.cs:789-796），而指令链
            // 必然改过脚本行 ⇒ 旧菜单按钮世代失配、整屏按钮点不动。置位后走 else 支
            // （:793-796）保住 lastButtonGeneration，屏上菜单保持可点。
            // 通道只置脏、不带 UpdateGeneration（:770-774）的前移半句 —— 那是按钮构造专用，
            // 在本点位会把 lastButtonGeneration 抬到 newButtonGeneration，旧按钮照样死。
            host.markGenerationUpdated()
            state = SystemStateCode.Train_CallShowUserCom
            return done(
                from, "endCallEventComEnd", StepOutcome.CONTINUE, null,
                "Process.cs:2426-2436（endCallShowUserCom；:2433 updatedGeneration 已接，W8-gen）",
            )
        }
        // ② eraTW AUTO_SAVE=="前"：并入 B1 存档链（落点与 saveTarget 交集的说明见交付）。
        if (host.autoSaveAtComEnd() && saveTarget < 0) {
            saveTarget = AUTO_SAVE_SLOT
            calls.setSavedataText(autoSaveSavedataText())
            val ok = host.saveTo(AUTO_SAVE_SLOT, calls.savedataText())
            if (ok == null) {
                return StateStep(
                    from = from,
                    handler = "endCallEventComEnd",
                    to = state,
                    outcome = StepOutcome.BLOCKED,
                    calledFunction = null,
                    blocker = FlowBlocker(
                        BlockerKind.HOST_MISSING, null, null, -1, -1,
                        "AUTO_SAVE「前」档需要宿主 saveTo（SystemOps.saveData 同源路）——未接线",
                        "Process.cs:2583 / VariableEvaluator.cs:2644-2685（分支属 SkiaX 扩展，TODO(verify)）",
                    ),
                    evidence = "裁定报告 Process.cs:1849-1891（本地版无此分支）",
                )
            }
            if (!ok) {
                // 与 endAutoSaveCallSaveInfo 支同款两行报错：原版通道是 console.PrintError
                // （形制 `Process.cs:2585-2586`；差集与对位见 [BeginHost.printError] 的 KDoc）。
                host.printError(AUTO_SAVE_ERROR_TEXT1)
                host.printError(AUTO_SAVE_ERROR_TEXT2)
                host.readAnyKey()
            }
            saveTarget = -1
            // 队列清法照 :2437-2442 / 报告 :1869-1874。
            calls.clearCommands()
        }
        // ③ Process.cs:2437-2448 —— CALLTRAIN 队列耗尽的收尾派发。
        if (calls.isCTrain() && calls.commandsConsumed()) {
            calls.clearCommands()
            if (callScript("CALLTRAINEND", false, false)) {
                needCheck = false
                // 状态留在本状态：原版 runSystemProc 循环再进一次 handler，这次
                // isCTrain 已 false（clearCommands），落到尾部。移植侧同样由下一次 step 承接。
                return done(
                    from, "endCallEventComEnd", StepOutcome.CALLED, "CALLTRAINEND",
                    "Process.cs:2437-2448",
                )
            }
            // 目标缺失（force=false，:2443）：原版不 return，继续落尾部。
        }
        // 尾部 Process.cs:2449-2455。
        needCheck = true
        // Process.cs:2450-2453 —— `setWait()` = `console.ReadAnyKey()`：回训练链之前等一次按键。
        // 清位时机取原版「进入等待那一刻」那一处（`EmueraConsole.cs:894`），不是返回后的
        // `Process.cs:2454` —— 等待被宿主销毁打断（`InputSinkClosedException`）时不留粘着的真位，
        // 下一次到点这里不会白等一遍。
        if (needWaitToEventComEnd) {
            needWaitToEventComEnd = false
            host.readAnyKey()
        }
        state = SystemStateCode.Train_CallEventTrain
        return done(
            from, "endCallEventComEnd", StepOutcome.CONTINUE, null,
            "Process.cs:2449-2455 → 2226-2242",
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
        val called = callScript("EVENTCOM", false, true)
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
        val called = callScript("EVENTEND", true, true)
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
        val called = callScript("EVENTTURNEND", true, true)
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
        val called = callScript("SHOW_JUEL", true, false)
        return done(
            SystemStateCode.Ablup_Begin, "beginAblup",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "SHOW_JUEL", "Process.cs:2468-2476",
        )
    }

    private fun stepAblupShowJuel(): StateStep {
        state = SystemStateCode.Ablup_CallShowAblupSelect
        val called = callScript("SHOW_ABLUP_SELECT", true, false)
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
            val called = callScript(name, false, false)
            if (called) {
                done(from, "ablupWaitInput", StepOutcome.CALLED, name, "Process.cs:2492-2496")
            } else {
                // Process.cs:2496-2502：无效值 → 删行 + 画提示 + 置脏 + 重新显示选择并等输入。
                // 删除对象 = 键入回显行（`doInputToEmueraProgram` 末尾 Print(str) 落的那条
                // 普通行，EmueraConsole.cs:1280-1282）—— 回显机制（ECHO-INPUT 波次）补上后，
                // :2498 的 deleteLine(1) 删的就是它（2026-11-04 曾因无回显误删 ABLUP 菜单
                // 按钮行而回滚本刀，见 BOARD「键入回显行缺失」）。
                host.deleteLine(1)
                // :2499 PrintTemporaryLine(Lang.Error.InvalidValue.Text) —— zhs 文案
                // [INVALID_VALUE_TEXT]，与 Openning 标题菜单支（:994，Process.cs:2194-2195）同一条。
                host.printTemporaryLine(INVALID_VALUE_TEXT)
                // Process.cs:2500 console.updatedGeneration = true —— ABLUP 重等（:2501
                // endCallShowAblupSelect → setWaitInput :2486）前置脏，保住屏上旧按钮的
                // 重等可点世代（通道语义见 [BeginHost.markGenerationUpdated]）。
                host.markGenerationUpdated()
                state = SystemStateCode.Ablup_WaitInput
                done(from, "ablupWaitInput", StepOutcome.AWAIT_INPUT, name, "Process.cs:2496-2502")
            }
        } else {
            // Process.cs:2506-2508 —— `vEvaluator.RESULT = systemResult` 之后调 USERABLUP，
            // 与 trainWaitInput 的 :2372 同一条通道（USERABLUP 也按 RESULT 分派）。
            calls.setResult(input)
            calls.setSystemResult(input)
            state = SystemStateCode.Ablup_CallAblupXX
            val called = callScript("USERABLUP", true, false)
            done(
                from, "ablupWaitInput",
                if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                "USERABLUP", "Process.cs:2504-2509",
            )
        }
    }

    private fun stepAblupCall(): StateStep {
        // Process.cs:2512-2528 endCallAblupXX —— ABLUP{N}/USERABLUP 收尾分流。
        if (host.lastLineIsTemporary()) {
            // :2516-2520 空行半句（W10 保真度收口）—— 末行还是一条**空**临时行 ⇒
            // 删 2 条逻辑行 + 画提示临时行（与 EVENTCOMEND 分支① `:2428-2432` 同形；
            // 删行数 :2518 实测为 2，非标题 else 支的 1）。文案 [ABLUP_INVALID_VALUE_RAW]
            // 是**硬编码日文**：官方行为 —— 该半句不经 Lang，zhs 化环境也打日文
            // （同文件 :2431/:2499/:2649 的兄弟无效值点位都走 Lang.Error.InvalidValue.Text，
            // 全仓唯 :2519 一处字面量，照抄不本地化，ADR-005）。
            if (host.lastLineIsEmpty()) {
                host.deleteLine(2)
                host.printTemporaryLine(ABLUP_INVALID_VALUE_RAW)
            }
            // Process.cs:2521 console.updatedGeneration = true —— 临时行收尾 ⇒ 回菜单重等
            // （:2522 endCallShowAblupSelect → setWaitInput :2486），置脏保住屏上旧按钮世代。
            // 置位在空行判定**之外**、临时行门**之内**（:2521 的原文位置，照抄）。
            host.markGenerationUpdated()
            state = SystemStateCode.Ablup_CallShowAblupSelect
        } else {
            state = SystemStateCode.Ablup_Begin
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
        val called = callScript("EVENTSHOP", false, true)
        return done(
            SystemStateCode.Shop_Begin, "beginShop",
            if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
            "EVENTSHOP", "Process.cs:2540-2551",
        )
    }

    /**
     * `endCallEventShop`（`Process.cs:2553-2563`）+ `beginAutoSave`（`:2565-2579`）的复合步
     * （原版在同一次调用里嵌套直调，移植侧把「SAVEINFO 返回后」拆给下一次 [step]）。
     *
     * 逐句对位：
     * 1. `:2555 saveTarget = -1` —— 每次商店返回都是**新的一档**；入口闸先判
     *    `saveTarget >= 0`（裁定项「不重复进 AUTO_SAVE」，证据与 TODO(verify) 见
     *    [saveTarget] KDoc）：本轮已带存档目标时**既不覆盖也不重存**，直进 [endAutoSave] 收口。
     * 2. `:2556-2560` `Config.AutoSave && calledWhenNormal` ⇒ [beginAutoSaveStep]；
     * 3. `:2561-2562` 否则 `AutoSave_Skipped` + 直调 `endAutoSaveCallSaveInfo()` —— 移植侧
     *    同一步内接 [endAutoSaveCallSaveInfoStep]；`saveTarget` 已是 -1 ⇒ 判据
     *    `saveTarget == 99`（`:2583`）不成立，不触实存，读数与旧「直进 endAutoSave」一致。
     */
    private fun stepShopEventShop(): StateStep {
        val from = SystemStateCode.Shop_CallEventShop
        if (saveTarget >= 0) {
            // 不重复进 AUTO_SAVE（见 [saveTarget] KDoc 的 TODO(verify)）；saveTarget 原样保留，
            // 等本轮存档链自己的收口点复位 —— 这里抢跑只会把「已存过」状态洗掉。
            return endAutoSave(from, "endCallEventShop", "Process.cs:2592-2601（入口闸，见交付 TODO(verify)）")
        }
        saveTarget = -1
        if (host.autoSave() && calledWhenNormal) {
            return beginAutoSaveStep(from)
        }
        state = SystemStateCode.AutoSave_Skipped
        return endAutoSaveCallSaveInfoStep(from, "endCallEventShop", "Process.cs:2561-2562 / :2581-2601")
    }

    /**
     * `beginAutoSave`（`Process.cs:2565-2579`）逐句：
     * 1. `:2567-2571` `SYSTEM_AUTOSAVE` 存在 ⇒ `AutoSave_CallUniqueAutosave`（它的 handler
     *    是**裸 endAutoSave**，注册表 `:2032` —— 该函数自管实存）；
     * 2. `:2572-2574` `saveTarget = 99`（`const AutoSaveIndex`，`:136`）+
     *    `SAVEDATA_TEXT = DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "`（`:2573`）+
     *    置 `AutoSave_CallSaveInfo`；
     * 3. `:2575-2578` `SAVEINFO` 存在 → 返回等脚本跑完（下一次 [step] 进
     *    [endAutoSaveCallSaveInfoStep]）；缺失 → 原版**当场**直调 `endAutoSaveCallSaveInfo()`，
     *    移植侧同步接上。
     *
     * **TODO(verify)**：裁定报告还称 beginAutoSave 里 `ShopItem==-1` 先调 `EVENTSHOP(0)`、
     * `SAVEDATA_TEXT` 取 eraTW 文案键 `AUTO_SAVE_2/3`（缺省 `AUTO_SAVE`）——本地 1.824 的
     * `:2565-2579` 无这两处（`:2573` 就是时间戳行），lang 表也无 `AUTO_SAVE_*` 键；
     * 属 SkiaX 分支扩展，未落地（缺行号级证据，禁脑补）。缺省文案先按 `:2573` 时间戳，
     * 文案键由 [autoSaveSavedataText] 注入口透出，组长拿到新版反编译后一处即可接上。
     */
    private fun beginAutoSaveStep(from: SystemStateCode): StateStep {
        if (callScript("SYSTEM_AUTOSAVE", false, false)) {
            state = SystemStateCode.AutoSave_CallUniqueAutosave
            return done(from, "beginAutoSave", StepOutcome.CALLED, "SYSTEM_AUTOSAVE", "Process.cs:2565-2571")
        }
        saveTarget = AUTO_SAVE_SLOT
        calls.setSavedataText(autoSaveSavedataText())
        state = SystemStateCode.AutoSave_CallSaveInfo
        if (callScript("SAVEINFO", false, false)) {
            return done(from, "beginAutoSave", StepOutcome.CALLED, "SAVEINFO", "Process.cs:2572-2578")
        }
        // :2576-2577 —— SAVEINFO 缺失时原版当场 endAutoSaveCallSaveInfo()（嵌套直调拆步口径：
        // 本步直接续上，状态已是 AutoSave_CallSaveInfo）。
        return endAutoSaveCallSaveInfoStep(from, "beginAutoSave", "Process.cs:2565-2578 / :2581-2601")
    }

    /**
     * `endAutoSaveCallSaveInfo`（`Process.cs:2581-2590`）：
     * `saveTarget == 99` 才实存（`:2583` —— Skipped 分支的 -1 就是靠这条判据跳过，
     * 玩家配置 `MAKE AUTOSAVES:NO` 走的正是它）；`SaveTo` 失败 ⇒ 两行报错 +
     * `ReadAnyKey`（`:2584-2588`，**不抛、不中断**，随后照常 [endAutoSave]）；
     * 无论成败收口都进 [endAutoSave]（`:2589`），并把本轮的 `saveTarget` 复位
     * （`:2555` 每次商店返回复位，移植侧入口闸在复位**之前**判 `>= 0`，故收口点在此，
     * 差异见交付「与基准差异」①）。
     *
     * **TODO(verify)**：裁定报告称本函数「无论成败 `CallErbHid(RESET_EVENT)`→`Initialize()`」
     * （引 `Process.cs:2530-2545`，该区间本地是 `beginTurnend`/`beginShop`，
     * 且全 reference 无 `RESET_EVENT`/`CallErbHid` 命中）—— 属 SkiaX 分支扩展，未落地；
     * 若新版坐实，落点在下面 `saveTarget = -1` 之后追加复位链，宿主接线归组长。
     */
    private fun endAutoSaveCallSaveInfoStep(
        from: SystemStateCode,
        handler: String,
        evidence: String = "Process.cs:2581-2601",
    ): StateStep {
        if (saveTarget == AUTO_SAVE_SLOT) {
            val ok = host.saveTo(AUTO_SAVE_SLOT, calls.savedataText())
            if (ok == null) {
                // 宿主没有存档能力：BLOCKED，不伪造成败、不转态（接口缺省纪律）。
                return StateStep(
                    from = from,
                    handler = handler,
                    to = state,
                    outcome = StepOutcome.BLOCKED,
                    calledFunction = null,
                    blocker = FlowBlocker(
                        BlockerKind.HOST_MISSING, null, null, -1, -1,
                        "AUTO_SAVE 链需要宿主 saveTo（SystemOps.saveData 同源路）——未接线",
                        "Process.cs:2583 / VariableEvaluator.cs:2644-2685",
                    ),
                    evidence = "Process.cs:2581-2590",
                )
            }
            if (!ok) {
                // :2584-2588 —— 两行报错 + ReadAnyKey。文案 = Lang.Error.AutoSaveError1/2
                // （日原文 Lang.cs:3142/:3146；zhs 译文 emuera-zhs.xml:740-741，
                // 「预期以外的的错误」双「的」为官方原文，ADR-005 照抄）。
                // 报告称该键在 eraTW 资源里叫 AUTO_SAVE_4/5 —— 键名差、内容同，TODO(verify) 并注。
                // OBS2-L1 裁定：原版是 console.PrintError（`Process.cs:2585-2586`；
                // `EmueraConsole.cs:3098-3116`），改走 [BeginHost.printError] 通道 ——
                // 样式复位 / 强制左对齐 / 不折行 / 不吃按钮解析 / 帧必上屏的对位在
                // `LineBufferConsoleSink.printError`（现 ConsoleBeginHost 未覆写该方法，
                // 缺省降级 = writeLine，接线前行为与换道前逐字一致；接线登记「需组长接线」）。
                host.printError(AUTO_SAVE_ERROR_TEXT1)
                host.printError(AUTO_SAVE_ERROR_TEXT2)
                host.readAnyKey()
            }
        }
        saveTarget = -1
        return endAutoSave(from, handler, evidence)
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
     * 两支失败提示自 OBS2-L1 起走与原版同形的 [BeginHost.deleteLine]`(1)` +
     * [BeginHost.printTemporaryLine] 通道（删除对象 = 键入回显行，见 `:2624` 支的注释）。
     * 原版该支**不置** `updatedGeneration`（`Process.cs:2609-2640` 无此句，对照 `:2500`）：
     * PC 上商店重等的等待行身份恒为同一条 ERB `INPUT` 语句（`process.getCurrentLine`），
     * `EmueraConsole.cs:789-796` 走 else 支、旧按钮保持可点。移植生产链自 OBS2-L5 起把这一身份
     * 接上（本类的脚本调用统一入口 [callScript] → [BeginHost.noteWaitPageProducer] →
     * `ConsoleBeginHost.waitScriptLine`；装配点 `GameSession` 的 `pageScriptLineIdentity`），
     * 重等前后同一组按钮的可点集合不变；未接时的读数（身份退化行水位 ⇒ 第二次重等把旧按钮整批
     * 作废）与接线后的对照钉在 ShopTemporaryLineTest 与 WaitInputGenerationTest。
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
                    val called = callScript("EVENTBUY", false, true)
                    return done(
                        from, "shopWaitInput",
                        if (called) StepOutcome.CALLED else StepOutcome.NOT_CALLED,
                        "EVENTBUY", "Process.cs:2613-2623",
                    )
                }
                // Process.cs:2624-2625（OBS2-L1 换道，形制同 ablupWaitInput :1599-1606 支）：
                // deleteLine(1) 删的是玩家那次键入的回显行（`EmueraConsole.cs:1280-1282`，
                // 回显随 f85b3c2d 落 main）；提示走临时行通道，与回显同寿、被下一次正式
                // 输出收走。**原版 :2624-2630 两支都没有 updatedGeneration 置位半句**
                // （逐字核对 `Process.cs:2609-2640`，置位只在 `endCallEventBuy` `:2651`；
                // 对照 `ablupWaitInput` `:2500` 带置位）⇒ 照抄不补 [markGenerationUpdated]
                // （ADR-005；重等后旧按钮的世代口径见 [stepShopWait] 的 KDoc）。
                host.deleteLine(1)
                host.printTemporaryLine(NOT_ENOUGH_MONEY_TEXT)
                return backToShopWait(from, "Process.cs:2624-2632")
            }
            // Process.cs:2629-2630 —— 与上支同形，文案 `Lang.Error.OutOfStock`。
            host.deleteLine(1)
            host.printTemporaryLine(OUT_OF_STOCK_TEXT)
            return backToShopWait(from, "Process.cs:2629-2632")
        }
        model.writeResult(input)
        callScript("USERSHOP", true, false)
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
            // :2646-2650 空行半句（W10 保真度收口）—— 末行还是一条**空**临时行 ⇒
            // 删 2 条逻辑行 + 画「无效的值」临时行（与 EVENTCOMEND 分支① `:2428-2432`
            // 同形；删行数 :2648 实测为 2）。文案 [INVALID_VALUE_TEXT]：原版此处 :2649
            // 走 Lang.Error.InvalidValue.Text（**不经**硬编码，与 ABLUP `:2519` 的日文
            // 字面量不同，见 [ABLUP_INVALID_VALUE_RAW]）。
            if (host.lastLineIsEmpty()) {
                host.deleteLine(2)
                host.printTemporaryLine(INVALID_VALUE_TEXT)
            }
            // Process.cs:2651 console.updatedGeneration = true —— 临时行收尾 ⇒ 回商店重等
            // （:2652 endCallShowShop → setWaitInput :2605）前置脏，保住屏上旧按钮世代。
            // 置位在空行判定**之外**、临时行门**之内**（:2651 的原文位置，照抄）。
            host.markGenerationUpdated()
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
        val called = callScript("SHOW_SHOP", true, false)
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
        val called = callScript("SYSTEM_LOADEND", false, false)
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
        val called = callScript("EVENTLOAD", false, true)
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
     *    （`:2687` / `:2694`）→ [BeginHost.printSingleLine]（zhs 常量见 [SAVE_QUESTION_TEXT] /
     *    [LOAD_QUESTION_TEXT]）。OBS2-L5 换道：此前走 [BeginHost.writeLine] 会把问句并进上一句
     *    未结的 PRINTN/PRINTC 残段（原版 `PrintSingleLine` 先 `PrintFlush` 结掉缓冲再单行落，
     *    `EmueraConsole.cs:3155-3158`），差集逐句见 [BeginHost.printSingleLine] 的 KDoc；
     *    未覆写该通道的宿主缺省降级回 writeLine，读数与换道前逐字一致；
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
        // Process.cs:2687 / :2694 —— PrintSingleLine(SaveQuestion / LoadQuestion)，**非临时行**、
        // 独立单行（差集见 [BeginHost.printSingleLine] 的 KDoc；OBS2-L5 换道，之前误走 writeLine
        // 会把问句并进上一句未结的 PRINTN/PRINTC 残段）。
        host.printSingleLine(if (save) SAVE_QUESTION_TEXT else LOAD_QUESTION_TEXT)
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

    /**
     * 状态机跑脚本函数的**唯一入口**（OBS2-L5）：委托 [BeginCallPort.callFunction]，
     * 并在被调方真的存在时把函数名登记给宿主当「本页来源」——理由与粒度差见
     * [BeginHost.noteWaitPageProducer] 的 KDoc（原版 `ProcessState.currentLine` 是「跑过才算数」
     * 的字段，缺函数支一支脚本都没跑，故 `called == false` 时不登记）。
     *
     * 登记发生在 `callFunction` **返回之后**：那一刻 VM 已无帧，宿主拿不到 pc，
     * 只能按函数名取身份位（同一时刻读 `scriptLineToken` 恒 -1，证据链见上）。
     */
    private fun callScript(name: String, force: Boolean, isEvent: Boolean): Boolean {
        val called = calls.callFunction(name, force, isEvent)
        if (called) {
            host.noteWaitPageProducer(name)
        }
        return called
    }

    private fun done(
        from: SystemStateCode,
        handler: String,
        outcome: StepOutcome,
        called: String?,
        evidence: String,
    ): StateStep = StateStep(from, handler, state, outcome, called, null, evidence)

    companion object {
        /**
         * 「本页来源函数」身份位的 pc 位固定值（OBS2-L5）：BEGIN 状态机的等待点没有帧，
         * 拿不到真 pc，故身份位取**函数级**粒度 —— 生产侧 `GameSession.beginWaitPageIdentity`
         * 与钉测装置（`WaitInputGenerationTest`）共用本常量，避免两侧各写一份 0 而悄悄分叉。
         *
         * 粒度差的边界与「为什么可观测面等价」见 [BeginHost.noteWaitPageProducer] 的 KDoc；
         * `pc = 0` 与真语句 token 只可能在**同一个函数**上重合（跨函数不会折叠，
         * 因为 id 按函数键分配，见 `proc/LineIdentityIds.kt`）。
         */
        internal const val PAGE_IDENTITY_PC = 0

        /**
         * 清掃的训练码（`CSV/Train.csv:199` 的 `410,掃除`）。
         *
         * 它是「玩家选了、但语料自己判定不该做」这一类指令的代表：`COMF410 掃除.ERB:18-26`
         * 在 `YOGORE < OSOUJI_LIMIT` 时只 `DRAWLINE` + 打一行「也没有那么乱七八糟、就算不扫除可以吧」
         * 就 `RETURN -1`，零状态变化 —— 没有收尾那一次等待，那行提示在手机上会被下个回合的
         * 输出整帧刷掉（BOARD 2026-11-01「点清扫看着没反应」）。
         */
        internal const val COM_CLEANING = 410

        /**
         * `endCallAblupXX` 空行半句的提示串（`Process.cs:2519`）。**官方行为：该半句不经
         * Lang，zhs 化环境也打日文** —— 同文件其余无效值点位（`:2431`/`:2499`/`:2649`）
         * 全走 `Lang.Error.InvalidValue.Text`（日原文「無効な値です」，`Lang.cs:3130`，
         * zhs 译文「无效的值」），唯 `:2519` 是字面量硬编码；照抄不本地化（ADR-005）。
         */
        private const val ABLUP_INVALID_VALUE_RAW = "無効な値です"

        /**
         * `Lang.Error.InvalidValue.Text`。日原文「無効な値です」
         * （`Lang.cs:3130`），zhs 译文「无效的值」（lang.emuera-zhs.xml:737；游戏语料
         * `lang/emuera.zhs.xml:405` 同文）。移植侧错误/系统行文案取 zhs 资源。
         * W10 起临时行通道的消费方：EVENTCOMEND 分支①（`Process.cs:2431`）、标题菜单
         * else 支（`:2195`，W10 换道）、ABLUP 缺函数支（`:2499`，OBS2-L1 已换道，
         * 落点 [stepAblupWait] 的 deleteLine(1) + [printTemporaryLine] 两语句）、
         * 商店收尾空行半句（`:2649`，W10 接线）；商店钱不够/售罄（`:2625`/`:2630`，
         * OBS2-L1 换道，落点 [stepShopWait]）。通道语义见 [BeginHost.printTemporaryLine]
         * （`ConsoleBeginHost.writeLine` 的 KDoc 已随 OBS2-L1/L5 的换道同步更正——
         * 该方法如今的普通行欠账位为零）；
         * ABLUP 收尾空行半句 `:2519` 是日文硬编码不经 Lang，见 [ABLUP_INVALID_VALUE_RAW]。
         */
        internal const val INVALID_VALUE_TEXT = "无效的值"

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

        /**
         * 自动存档固定槽 `const int AutoSaveIndex = 99`（`Process.cs:136`；
         * 置位 `:2572`、判据 `:2583`）。
         */
        internal const val AUTO_SAVE_SLOT = 99

        /**
         * `Lang.Error.AutoSaveError1.Text`（`Process.cs:2585`）。日原文
         * 「オートセーブ中に予期しないエラーが発生しました」（Lang.cs:3142），
         * zhs 译文「自动存档期间发生预期以外的的错误」（lang.emuera-zhs.xml:740 ——
         * 双「的」为官方资源原文，ADR-005 照抄）。挖虫报告称 eraTW 资源里该键叫
         * AUTO_SAVE_4 —— 键名差、本仓取官方 zhs 译文，TODO(verify) 见
         * [endAutoSaveCallSaveInfoStep] KDoc。
         */
        internal const val AUTO_SAVE_ERROR_TEXT1 = "自动存档期间发生预期以外的的错误"

        /**
         * `Lang.Error.AutoSaveError2.Text`（`Process.cs:2586`）。日原文
         * 「オートセーブをスキップします」（Lang.cs:3146），zhs 译文
         * 「自动存档将被跳过」（lang.emuera-zhs.xml:741）。报告键名 AUTO_SAVE_5，同上注。
         */
        internal const val AUTO_SAVE_ERROR_TEXT2 = "自动存档将被跳过"

        /**
         * `beginAutoSave` 缺省档名（`Process.cs:2573`）：
         * `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "`（本地时区、尾随一个空格）。
         * 测试经 [BeginFlow.autoSaveSavedataText] 注入固定串收稳读数。
         */
        internal fun defaultAutoSaveSavedataText(): String =
            java.time.LocalDateTime.now().format(AUTO_SAVE_TEXT_FORMAT) + " "

        /** C# `yyyy/MM/dd HH:mm:ss` 的等价格式（`Process.cs:2573`）。 */
        private val AUTO_SAVE_TEXT_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    }
}
