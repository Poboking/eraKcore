package com.eratw.port.core.host

import com.eratw.port.core.proc.SystemStateCode
import com.eratw.port.core.vars.VariableStore

/**
 * 语句层 → 进程状态机的**进程状态交接**。三个写入口，对应原版三处「语句里直接改 state」：
 *
 * - `LOADDATA` 成功：`state.ClearFunctionList(); state.SystemState = LoadData_DataLoaded;`
 *   （`Process.cs:1860-1861`）⇒ [requestDataLoaded]。
 * - `SAVEGAME`：`saveCurrentState(single: true); state.SaveLoadData(true);`
 *   （`FunctionIdentifier.cs:2978-2979`）⇒ [requestSaveGame]。
 * - `LOADGAME`：同两句、`SaveLoadData(false)`（`FunctionIdentifier.cs:2979`）⇒ [requestLoadGame]。
 *
 * ## 为什么需要这一层
 *
 * 原版这几句都写在 Process / Instruction 里，`state` 是 `ProcessState` 的实例，语句层随手
 * 就能改。移植侧的语句层（[com.eratw.port.core.sem.StatementExecutor]）**看不到**
 * `BeginFlow.state`（`proc/ProcessRuntime.kt` 的 `systemState` 是只读视图，写入点只有
 * `BeginFlow.select` / `enterState`），而 `LOADDATA` 的落盘实现在 `core/save/FileSystemOps.kt`。
 * 两处之间的唯一共享面就是本仓既有的 `store.aux` 槽纪律（同 `SaveWiring` / xml / map / dt 池）。
 *
 * ## 存什么、谁消费
 *
 * - [functionListCleared]：「脚本函数栈应当被丢弃」的等价信号。两条来源同效：
 *   `ClearFunctionList()`（`ProcessState.cs:202-217`，`LOADDATA`）与
 *   `saveCurrentState` 把 `state` 换成 `Clone()` 出的新实例（`Process.cs:1964`）——
 *   `Clone()` 只复制 `currentLine` / `sysStateCode` / `begintype` 与 `isClone`，
 *   **不复制 functionList**（`ProcessState.cs:475-484`，字段初始化见 `:20`）⇒
 *   `ScriptEnd`（`:34` `functionList.Count == currentMin`）立即为真，本次脚本运行结束。
 *   VM 侧的等价动作是 `StmtOutcome.Unwind`（整栈丢弃，结束本次 run），由
 *   `ProcessRuntime.executeStatement` 产生；不是「逐帧弹到调用方」。
 * - [systemState]：待进入的状态，值域是既有枚举 [SystemStateCode]（**不新造状态**）。
 *   目前可能是 `LoadData_DataLoaded(528)` / `SaveGame_Begin(256)` / `LoadGame_Begin(272)`
 *   （`ProcessState.cs:194` / `:198`）。
 *
 * 两项按原版顺序落：先 [functionListCleared]，后 [systemState]
 * （`Process.cs:1860` → `:1861`；`Process.cs:1963-1964` → `ProcessState.cs:194/198`）。
 *
 * ## 消费点
 *
 * 三处，都在 proc 侧，按原版顺序：
 * 1. `ProcessRuntime.executeStatement`：`LOADDATA` / `SAVEGAME` / `LOADGAME` 成功后取
 *    [takeFunctionListCleared]，返回 `StmtOutcome.Unwind` —— VM 整栈丢弃（不是逐帧弹）。
 *    只有 `LOADDATA` 同时清掉挂起的 `BEGIN`（`:216` 的 `begintype = BeginType.NULL`）：
 *    `Clone()` 是原样复制 `begintype`（`ProcessState.cs:482`），故 SAVEGAME/LOADGAME 不清。
 * 2. `ProcessRuntime.beginStep` / `resumeSystem` 开头取 [takeSystemState]：非 null 时把
 *    `BeginFlow.state` 置成该状态并**跳过本步 handler**（等价 `DoScript` 外层
 *    `while (state.ScriptEnd) runSystemProc()`，`Process.cs:445-452`），下一步才跑该状态
 *    的 handler。
 * 3. `BeginFlow` 的 `LoadData_DataLoaded → LoadData_CallSystemLoad → LoadData_CallEventLoad`
 *    三态（`Process.cs:2660-2683`，注册表见 `:2033-2035`）。SAVEGAME/LOADGAME 的
 *    `SaveGame_Begin` / `LoadGame_Begin` handler（`Process.cs:2685-2697` 的
 *    `beginSaveGame` / `beginLoadGame`）已由 M11-E saveflow 接管：问题行 + 槽位交宿主钩子 +
 *    换态到 `SaveGame_WaitInput` / `LoadGame_WaitInput`（`BeginFlow.stepSaveOrLoadGameBegin`）。
 *    「未接管」阻塞步因此后移到那两态的存档 UI handler（`Process.cs:2817-2855` / `:2891-2957`）。
 *
 * 失败路径（`loadData` 抛 [com.eratw.port.core.err.CodeEE]）不写任何一项：状态位保持 null，
 * 控制流仍走原来的 `ExecResult.Quit`（只结束当前帧）。
 *
 * TODO(verify)：原版 `AppContents.UnloadTempLoadedConstImageNames()` /
 * `UnloadTempLoadedGraphicsImageNames()`（`Process.cs:2671-2672`）在移植侧没有临时载入表
 * （`core/image` 尚未提供），现经 `BeginHost` 的两个新钩子默认 no-op 转发，等该表落地。
 */
class SaveLoadHandoff {

    /**
     * 「脚本函数栈应当被丢弃」的等价信号。两个来源：`state.ClearFunctionList()`
     * （`Process.cs:1860`，`LOADDATA`）与 `saveCurrentState` 换 `Clone()` 出的空 functionList
     * 实例（`Process.cs:1964`，`SAVEGAME` / `LOADGAME`）。
     */
    var functionListCleared: Boolean = false
        private set

    /** 待进入的系统状态（`Process.cs:1861`）；null = 没有待办。 */
    var systemState: SystemStateCode? = null
        private set

    /**
     * 记录一次「读档成功」的交接，顺序照 `Process.cs:1860-1861`：
     * 先函数栈清空信号，后状态位。重复调用幂等（值不变）。
     */
    fun requestDataLoaded() {
        functionListCleared = true
        systemState = SystemStateCode.LoadData_DataLoaded
    }

    /**
     * 记录一次 `SAVEGAME` 的交接（`FunctionIdentifier.cs:2978-2979` 两句的合成）：
     * `saveCurrentState(single: true)`（`Process.cs:1959-1966`：换 clone ⇒ 脚本栈作废）
     * + `state.SaveLoadData(true)`（`ProcessState.cs:190-195`：`sysStateCode = SaveGame_Begin`）。
     *
     * 顺序照原版：先脚本栈信号（`Process.cs:1964`），后状态位（`ProcessState.cs:194`）。
     */
    fun requestSaveGame() {
        functionListCleared = true
        systemState = SystemStateCode.SaveGame_Begin
    }

    /**
     * 记录一次 `LOADGAME` 的交接：与 [requestSaveGame] 同形，只有 `SaveLoadData(false)`
     * 的分支不同（`ProcessState.cs:196-199`：`sysStateCode = LoadGame_Begin`）。
     */
    fun requestLoadGame() {
        functionListCleared = true
        systemState = SystemStateCode.LoadGame_Begin
    }

    /** 取走待进入状态（一次性；消费后 [systemState] 归 null，[functionListCleared] 保持）。 */
    fun takeSystemState(): SystemStateCode? =
        systemState.also { systemState = null }

    /**
     * 取走「函数栈该整体丢弃」信号（一次性）：`ProcessRuntime.executeStatement` 在
     * `LOADDATA` / `SAVEGAME` / `LOADGAME` 成功后读一次，据此返回 `StmtOutcome.Unwind`
     * （`Process.cs:1860` 的 `ClearFunctionList` 或 `Process.cs:1964` 的换 clone）。
     * 一次性是必须的 —— 残留的标志会把后一条无关语句变成整栈丢弃。
     */
    fun takeFunctionListCleared(): Boolean =
        functionListCleared.also { functionListCleared = false }

    /** 全部归零（重开一局 / 卸载运行时用）。 */
    fun reset() {
        functionListCleared = false
        systemState = null
    }

    companion object {

        /** aux 槽名（约定见 [RuntimeAux] 类 KDoc：能力家族前缀 + 小写）。 */
        const val AUX_SLOT: String = "save.loadHandoff"

        /** 只查不建（未装配 = 宿主没走 [SaveLoadHandoff.of] 的安装路径）。 */
        fun of(store: VariableStore): SaveLoadHandoff? = store.aux.peek(AUX_SLOT)

        /** 装配（只建一次；保留先装的那份，同 `SaveWiring.install` 的理由）。 */
        fun install(store: VariableStore): SaveLoadHandoff = store.aux.slot(AUX_SLOT) { SaveLoadHandoff() }

        /** 现装现写：`LOADDATA` 成功路径的唯一调用点（`FileSystemOps.loadData`）。 */
        fun requestDataLoaded(store: VariableStore) {
            install(store).requestDataLoaded()
        }

        /** 现装现写：`SAVEGAME` 的唯一调用点（`StatementExecutor.execSaveLoadGame`）。 */
        fun requestSaveGame(store: VariableStore) {
            install(store).requestSaveGame()
        }

        /** 现装现写：`LOADGAME` 的唯一调用点（`StatementExecutor.execSaveLoadGame`）。 */
        fun requestLoadGame(store: VariableStore) {
            install(store).requestLoadGame()
        }
    }
}
