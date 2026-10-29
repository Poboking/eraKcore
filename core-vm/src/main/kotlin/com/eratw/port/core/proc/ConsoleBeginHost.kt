package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink

/**
 * 生产 [BeginHost]：把 BEGIN 状态机的四类系统输入接到同一条输入队列，系统行输出接到控制台。
 *
 * ## 四个 `await*` 对应原版的哪一个动作
 *
 * 原版这四个位置全部是 `setWaitInput()` + 状态迁移，等待本身发生在控制台的
 * `PressEnterKey` 回调里（`Process.cs:2052-2088` 的 `setWaitInput` 构造
 * `InputRequest{ InputType = IntValue, IsSystemInput = true }` 后 `console.WaitInput(req)`）：
 *
 * | 本文方法 | 原版位置 |
 * |---|---|
 * | [awaitOpeningInput] | `beginTitle` → `openingInput()`（`Process.cs:2161-2165`） |
 * | [awaitUserComInput] | `endCallShowUserCom`（`Process.cs:2323-2331`） |
 * | [awaitAblupInput] | `endCallShowAblupSelect`（`Process.cs:2484-2488`） |
 * | [awaitShopInput] | `endCallShowShop`（`Process.cs:2603-2607`） |
 *
 * 四者都是**整数**系统输入（`InputType.IntValue`，`:2065-2068`），且成功值写进
 * `Process.systemResult`（`[BeginFlow]` 的 `calls.setSystemResult(input)` 已经做了这件事），
 * 所以这里只负责「等到一个整数」——不解析按钮语义、不预填默认值。
 *
 * ## 与装置的差异（如实登记）
 *
 * - [printDefaultTitle] 保持接口缺省（不画）：原版在 `SYSTEM_TITLE` 缺失时用
 *   `gamebase.ScriptTitle/ScriptVersionText/...` 逐行 `PrintSingleLine` 再打菜单
 *   （`Process.cs:2141-2158`）。那需要 gamebase 读数与 6 行版面常量，本类只拿得到控制台；
 *   语料的 `SYSTEM_TITLE` 存在（`TITLE.ERB:3`），这条分支仅在脚本缺函数时才走到。
 * - [onNewGameSelected] 保持接口缺省：原版此处是 `ResetData + AddCharacterFromCsvNo(0) + PrintBar`
 *   （`Process.cs:2169-2179`）。它属于「新游戏初始化」语义，`GameSession` 已按装置口径在装配期
 *   种好角色；这里再 ResetData 会把宿主注入的角色/变量清掉，故不在这一层重做。
 * - [onComAble]、[resetComAble] 保持缺省：`Process.cs:2273/2300` 打的是
 *   `getTrainComString(trainCode, comNo)`，需要 `TrainName[trainCode]` 的文本（CSV 名字表），
 *   `BeginHost` 的形参只给了 `(code, index)` 两个整数 —— 文本得由宿主自己拼。
 *
 * @param input 输入队列（与语句层 `INPUT` 家族共用；见 [QueueInputSink]）
 * @param console 开局控制台（`writeLine` 与 `lastLineIsTemporary` 的数据源）
 * @param comAbleDefaultValue `Config.ComAbleDefault`（`Process.cs:2293`；缺省 1）
 * @param autoSaveEnabled `Config.AutoSave`（`Process.cs:2556`；缺省 false = 现语料 `emuera.config`
 *   的 `MAKE AUTOSAVES:NO`）
 * @param shopHostModel 商店输入的宿主侧模型（`Process.cs:2611-2639` 的 ItemSales / BuyItem /
 *   MaxShopItem / RESULT 写通道，语义见 [ShopHostModel]）。缺省 null = 未接，商店输入步
 *   会落 BLOCKED；装配层应传 `StoreShopHostModel(store, config)`（见 ShopHostModel.kt）。
 */
class ConsoleBeginHost(
    private val input: QueueInputSink,
    private val console: LineBufferConsoleSink,
    private val comAbleDefaultValue: Int = 1,
    private val autoSaveEnabled: Boolean = false,
    override val shopHostModel: ShopHostModel? = null,
    /**
     * 当前脚本行身份（原版 `process.getCurrentLine`）；系统等待同样要参与按钮世代推进
     * （`EmueraConsole.newGeneration`，EmueraConsole.cs:783-823）。缺省 null = 控制台
     * 用行水位近似（系统输入菜单每页都会重印按钮，实际无差）。
     */
    private val scriptLineToken: (() -> Long)? = null,
) : BeginHost {

    /**
     * `beginTitle` 的样式复位（`Process.cs:2112 console.ResetStyle()`，调用点
     * `BeginFlow.stepTitle()`）。委托本宿主已持有的 [console]，复位集四项口径见
     * [BeginHost.resetStyle]。
     */
    override fun resetStyle() {
        console.resetStyle()
    }

    override fun awaitOpeningInput(): Long = waitSystemInteger()

    override fun awaitUserComInput(): Long = waitSystemInteger()

    override fun awaitAblupInput(): Long = waitSystemInteger()

    override fun awaitShopInput(): Long = waitSystemInteger()

    // shopHostModel 以构造参数直挂 override（见类头 @param），未传即缺省 null = 未接。

    /**
     * 系统行输出（无效值提示等）。原版这几处是
     * `console.deleteLine(1); console.PrintTemporaryLine(Lang.Error.InvalidValue.Text)`
     * （`Process.cs:2194-2196`、`:2430-2432`、`:2498-2500`、`:2648-2650`）：
     * **先删上一行再画临时行**。本移植的 [BeginFlow] 只把文本交出来（`writeLine("无效的值")`），
     * 删行信息在状态机里没有单列，故这里按「追加一行」打印。
     * TODO(verify)：要与原版一致，需要 `BeginHost.writeLine` 带「是否临时行/是否替换末行」标志；
     * 属 `BeginHost` 契约变更，留给组长统一裁决。
     */
    override fun writeLine(text: String) {
        console.printLine(text)
    }

    /** `console.LastLineIsTemporary`（`Process.cs:2426`、`:2514`、`:2644`）——控制台的活状态。 */
    override fun lastLineIsTemporary(): Boolean = console.lines().lastOrNull()?.isTemporary ?: false

    override val comAbleDefault: Int get() = comAbleDefaultValue

    override fun autoSave(): Boolean = autoSaveEnabled

    /**
     * 等一次整数系统输入。
     *
     * 队列关闭时抛 [InputSinkClosedException]（宿主正在销毁），不返回伪造值。
     * 超时在该入口不适用：系统输入没有 `timeLimit`（原版 `setWaitInput` 不设 `Timelimit`，
     * `Process.cs:2052-2088`）。
     *
     * 帧发布：本入口绕过 sem 层直接等 [input]，必须自己做
     * `publishFrame`（等前上屏）→ `holdFrame`（返回后重新冻结），口径与
     * [com.eratw.port.core.sem.StatementExecutor] 的取值等待一致
     * （原版 `WaitInput` 置 state=WaitInput 后主循环退到消息循环，paint 得以上屏，
     * `EmueraConsole.cs:840-895`）。缺了这两句，剧本 `REDRAW 0` 关闸后的
     * 开局/读档等待永远不冲刷，UI 停在旧帧。
     */
    private fun waitSystemInteger(): Long {
        console.publishFrame()
        val request = InputRequest(
            type = InputRequest.InputType.INT,
            scriptLine = scriptLineToken?.invoke() ?: -1L,
        )
        // 系统整数等待同样是一次“当前页”的取值等待：走与 sem 层相同的选项冻结入口，
        // 避免 BEGIN/读档这类等待期间右侧栏还挂着上一页的按钮快照。
        console.noteInputWait(request)
        val value = input.waitInput(request).intValue
        console.holdFrame()
        return value
    }
}
