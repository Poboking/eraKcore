package com.eratw.port.core.proc

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.GameBaseData

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
 * - [printDefaultTitle]：原版在 `SYSTEM_TITLE` 缺失时用 `gamebase.ScriptTitle/`
 *   `ScriptVersionText/...` 逐行 `PrintSingleLine` 再打菜单（`Process.cs:2138-2158`）。
 *   CONFIG-WIRE U4：本类照原版版面实现——gamebase 读数经 [gamebase]（GameBase.csv
 *   装载，GameBaseLoader → GameBaseData；原版 GameBase.cs:106 装载、:150-160 键位），
 *   菜单两行吃 [titleMenuString0]/[titleMenuString1]（REPLACE 组键，`Config.cs:732-733`
 *   读、`ConfigData.cs:166-167` 缺省；`_Replace.csv` 链生效）。语料的 `SYSTEM_TITLE`
 *   存在（`TITLE.ERB:3`），这条分支仅在脚本缺函数时才走到。
 * - [onNewGameSelected] 保持接口缺省：原版此处是 `ResetData + AddCharacterFromCsvNo(0) + PrintBar`
 *   （`Process.cs:2169-2179`）。它属于「新游戏初始化」语义，`GameSession` 已按装置口径在装配期
 *   种好角色；这里再 ResetData 会把宿主注入的角色/变量清掉，故不在这一层重做。
 * - [onComAble]、[resetComAble] 保持缺省：`Process.cs:2273/2300` 打的是
 *   `getTrainComString(trainCode, comNo)`，需要 `TrainName[trainCode]` 的文本（CSV 名字表），
 *   `BeginHost` 的形参只给了 `(code, index)` 两个整数 —— 文本得由宿主自己拼。
 *   **连带契约**：既然这组 `名称[下标]` 按钮从不渲染，`systemResult` 就不可能来自它们 ——
 *   故本类把 [BeginHost.systemResultIsComAbleIndex] 覆写成 **false**（接口缺省是 true，
 *   即原版的下标口径）。`trainWaitInput` 于是不拿 `systemResult` 去查 `comAble`，
 *   而是原样交 `RESULT` 给语料 `USERCOM` 分派（`USERCOM.ERB:85-86` → `DOTRAIN 999` →
 *   `@SOURCE_CHECK` 还原 → `@CALL_COM` 的 `TRYCALLFORM COM{SELECTCOM}`）。
 *   这条不能省：画蛇添足版 `@BUILD_COM_BUTTON_HTML` 的按钮值是训练码，若仍走 `comAble`
 *   反查，实测 `comAble[82]=180` 会把「乳交[82]」错派发成 `COM180`（潤滑液）。
 *
 * @param input 输入队列（与语句层 `INPUT` 家族共用；见 [QueueInputSink]）
 * @param console 开局控制台（`writeLine` / `lastLineIsTemporary` / `lastLineIsEmpty` /
 *   `deleteLine` / `printTemporaryLine` 的数据源）
 * @param comAbleDefaultValue `Config.ComAbleDefault`（`Process.cs:2293`；缺省 1）
 * @param autoSaveEnabled `Config.AutoSave`（`Process.cs:2556`；缺省 false = 现语料 `emuera.config`
 *   的 `MAKE AUTOSAVES:NO`）
 * @param shopHostModel 商店输入的宿主侧模型（`Process.cs:2611-2639` 的 ItemSales / BuyItem /
 *   MaxShopItem / RESULT 写通道，语义见 [ShopHostModel]）。缺省 null = 未接，商店输入步
 *   会落 BLOCKED；装配层应传 `StoreShopHostModel(store, config)`（见 ShopHostModel.kt）。
 * @param saveToHook AUTO_SAVE 链的实档通道（`vEvaluator.SaveTo`，
 *   `VariableEvaluator.cs:2644-2685`：吞掉一切异常返回 false）。装配层（GameSession）
 *   传 `SystemOps.saveData` 的同一实例（`core/save/FileSystemOps`，与语句 `SAVEDATA`
 *   `Process.cs:1327-1349` 同一条落盘路）。缺省 null = 未接线：`autoSave()` 为 true 时
 *   存档步落 BLOCKED（接口缺省纪律「没有该能力不伪造结果」），为 false 时行为不变。
 */
class ConsoleBeginHost(
    private val input: QueueInputSink,
    private val console: LineBufferConsoleSink,
    private val comAbleDefaultValue: Int = 1,
    private val autoSaveEnabled: Boolean = false,
    override val shopHostModel: ShopHostModel? = null,
    /**
     * 当前脚本行身份（原版 `process.getCurrentLine` = `ProcessState.currentLine` 字段，
     * `Process.cs:142` → `ProcessState.cs:40-50`）；系统等待同样要参与按钮世代推进
     * （`EmueraConsole.newGeneration`，EmueraConsole.cs:783-823，判据 `:789-796`）。
     *
     * **本形参在生产链上是「有帧才准」的那半条通道**：BEGIN 状态机的每次等待都发生在
     * `vm.run` 返回之后、VM 无帧（`Vm.unwindScopes` 把帧深收回 -1，`Vm.kt:947-955`），
     * 而 [com.eratw.port.core.proc.ProcessRuntime] 的实现 `scriptLineTokenNow`（`:1242-1247`）
     * 无帧即 -1 ⇒ 单靠它等于没接（桌面实测读数见 [noteWaitPageProducer] 的 KDoc）。
     * 生产装配因此**两条一起接**：本形参给现算通道（[com.eratw.port.core.sem.ExecContext.scriptLineToken]，
     * `ProcessRuntime.kt:1093`），[pageScriptLineIdentity] 给「本页来源函数」的身份位；
     * 取用次序与理由见 [waitScriptLine]。
     * 缺省 null ⇒ 两个来源都是 -1 = 「宿主未接线」哨兵 ⇒ 控制台退回**行水位**近似
     * （`LineBufferConsoleSink.kt:713-714`）：报错提示/键入回显一推进行表，第二次重等的
     * 入口世代就前移 ⇒ 屏上旧按钮失配 = 玩家「点了没反应」（本刀治的病）。
     * 旧注释写的「系统输入菜单每页都会重印按钮，实际无差」只在**重印**那一支成立，
     * 原版商店/ABLUP 的失败重问支（`Process.cs:2624-2630`，删回显+临时行、**不**重印按钮）
     * 恰恰不重印，故有差。
     */
    private val scriptLineToken: (() -> Long)? = null,
    /**
     * 「把当前这一屏叫出来的脚本函数」→ 脚本行身份位（OBS2-L5 的主通道；登记时机与
     * 函数级粒度的理由见 [BeginHost.noteWaitPageProducer]）。由 [GameSession] 接运行期
     * 现成的同一张身份表（`LineIdentityIds`，同 `ProcessRuntime.kt:4393` 的 aux 槽
     * `proc.lineid` ⇒ 与语句层 `INPUT` 家族共用一个身份域，不新造第二套 token）。
     * 缺省 null = 假体/未接（各测试装置照旧），世代判据退回 [scriptLineToken] 与行水位。
     */
    private val pageScriptLineIdentity: ((String) -> Long)? = null,
    private val saveToHook: ((slot: Int, text: String) -> Boolean)? = null,
    /**
     * GAMEBASE.CSV 装载读数（[com.eratw.port.core.data.csv.GameBaseLoader.load]），
     * 供 [printDefaultTitle] 画默认标题版面（`Process.cs:2141-2148` 的 ScriptTitle/
     * ScriptVersionText/ScriptAutherName/ScriptYear/ScriptDetail）。缺省全空 = 夹具
     * 未注入；版面照打（原版对空键也是打空串）。
     */
    private val gamebase: GameBaseData = GameBaseData(),
    /**
     * 标题菜单两行（`Process.cs:2156-2157` `"[0] " + Config.TitleMenuString0`）。
     * REPLACE 组键：`ConfigData.cs:166-167` 缺省「最初からはじめる」/「ロードしてはじめる」，
     * `Config.cs:732-733` 从条目读——只吃 `_Replace.csv` 链（主配置写不动，原版口径）。
     * 缺省 = ConfigData 缺省值，夹具未注入时版面不缺字。
     */
    private val titleMenuString0: String = "最初からはじめる",
    private val titleMenuString1: String = "ロードしてはじめる",
    /**
     * 指令链收尾那次按键等待（[BeginHost.readAnyKey]）的适用指令集，见
     * [BeginHost.shouldWaitAfterCommand]。
     *
     * **缺省只清掃（[BeginFlow.COM_CLEANING]）且只在被拒时**（`RESULT < 0`）—— 这是产品取舍，
     * 不是原版口径（原版是「每条指令都等一次」）：
     * - 保留的理由：清掃被拒时语料只打一行提示就返回，不收尾等待的话那行在手机上会被下个回合
     *   整帧刷掉（BOARD 2026-11-01「点清扫看着没反应」）。
     * - 收窄的理由（2026-11-02 玩家反馈「点击移动卡顿」）：移动 = 训练码 400，
     *   `QOL_COM400` 每走一步就 `RETURN 1` 回训练链（`魔改内容/qol/qol_MAP.ERB:59-66`），
     *   于是收尾等待落在**每一次移动之后**；地图还在屏上，玩家下一次点目的地会被这次等待吃掉
     *   ⇒ 观感就是「点了没反应」。其余指令同理，一律不给额外点击。
     *
     * 传 null = 恢复原版「每条指令都等一次」；传空集 = 永不等。
     */
    private val commandEndWaitComCodes: Set<Int>? = setOf(BeginFlow.COM_CLEANING),
) : BeginHost {

    /**
     * 本宿主的按钮值是**训练码**，不是 `comAble` 下标 —— 见类头 KDoc「连带契约」。
     * 覆写理由：指令菜单由语料 `QOL_SHOW_USERCOM` 自印，按钮来自
     * `@BUILD_COM_BUTTON_HTML`（`<button value='{COM_VAL}'>乳交[{COM_VAL,3}]`），
     * 而引擎那组 `名称[下标]` 的 `PrintC` 按钮（`Process.cs:2273`）本类从不渲染。
     */
    override val systemResultIsComAbleIndex: Boolean get() = false

    /**
     * 收尾等待只给「被语料拒掉」的那几条指令（[commandEndWaitComCodes] 且 `RESULT < 0`）：
     * 成功的指令（含 移動 每走一步的 `RETURN 1`）不给额外点击。取值理由见构造参数。
     */
    override fun shouldWaitAfterCommand(comCode: Int, result: Long): Boolean {
        val codes = commandEndWaitComCodes ?: return true
        return result < 0L && comCode in codes
    }

    /**
     * `beginTitle` 的样式复位（`Process.cs:2112 console.ResetStyle()`，调用点
     * `BeginFlow.stepTitle()`）。委托本宿主已持有的 [console]，复位集四项口径见
     * [BeginHost.resetStyle]。
     */
    override fun resetStyle() {
        console.resetStyle()
    }

    /**
     * 默认标题版面（`Process.cs:2138-2158`，SYSTEM_TITLE 缺失分支）：
     * 分隔线 → 居中的 标题/[バージョン]/作者/(製作年) → 空行 → 追加情報 →
     * 左对齐 → 分隔线 → `[0] <TitleMenuString0>` / `[1] <TitleMenuString1>`。
     * バージョン行按 `ScriptVersionText` 派生（GameBase.cs:40-60：`v/1000 + "."`，
     * `v%10 != 0` 补 3 位、否则补 2 位），且仅 `version != 0` 才打（`Process.cs:2142`）。
     */
    override fun printDefaultTitle() {
        console.printBar()
        console.newLine()
        console.setAlignment(Alignment.CENTER)
        console.printSingleLine(gamebase.title)
        if (gamebase.version != 0L) {
            console.printSingleLine(scriptVersionText(gamebase.version))
        }
        console.printSingleLine(gamebase.author)
        console.printSingleLine("(" + gamebase.year + ")")
        console.newLine()
        console.printSingleLine(gamebase.info)
        console.setAlignment(Alignment.LEFT)
        console.printBar()
        console.newLine()
        console.printSingleLine("[0] " + titleMenuString0)
        console.printSingleLine("[1] " + titleMenuString1)
    }

    /** `GameBase.ScriptVersionText`（GameBase.cs:40-60）的逐字派生。 */
    private fun scriptVersionText(version: Long): String {
        val head = (version / 1000).toString() + "."
        val tail = if (version % 10 != 0L) {
            (version % 1000).toString().padStart(3, '0')
        } else {
            (version % 1000 / 10).toString().padStart(2, '0')
        }
        return head + tail
    }

    override fun awaitOpeningInput(): Long = waitSystemInteger()

    override fun awaitUserComInput(): Long = waitSystemInteger()

    override fun awaitAblupInput(): Long = waitSystemInteger()

    override fun awaitShopInput(): Long = waitSystemInteger()

    // shopHostModel 以构造参数直挂 override（见类头 @param），未传即缺省 null = 未接。

    /**
     * 系统行输出（无效值提示等）。原版的无效值提示形如
     * `console.deleteLine(1); console.PrintTemporaryLine(Lang.Error.InvalidValue.Text)`
     * —— **先删上一行再画临时行**（`Process.cs:2194-2195`/`:2498-2499`/`:2648-2649`；
     * ABLUP 收尾空行半句 `:2519` 是日文硬编码，不经 Lang）。
     * W10 保真度收口后 [BeginFlow] 的无效值提示改走 [deleteLine] / [printTemporaryLine]
     * 通道：标题 else 支（`:2194-2195`，删 1）、ABLUP/SHOP 收尾空行半句（`:2518-2519` /
     * `:2648-2649`，删 2）；EVENTCOMEND 分支①（`:2428-2432`）更早已按原样走临时行。
     * 仍经本方法落普通行的欠账位：**无**——ABLUP 缺函数支（`:2498-2500`）与钱不够/售罄
     * （`:2624-2630`）自 OBS2-L1 换道（键入回显 `f85b3c2d` 落 main 后前置消除），自动存档失败
     * 两行走 [printError]，存读档问句（`:2687`/`:2694`）自 OBS2-L5 走 [printSingleLine]。
     */
    override fun writeLine(text: String) {
        console.printLine(text)
    }

    /**
     * `console.PrintSingleLine(str)`（调用点 `Process.cs:2687`/`:2694` 存读档问句；方法体
     * `EmueraConsole.cs:3147-3165`）—— OBS2-L5 接线：转交 [LineBufferConsoleSink.printSingleLine]
     * （`ConsoleSink.kt:83` 声明、`LineBufferConsoleSink.kt:1486-1491` 实现）。与 [writeLine]
     * 的差 = 先结掉未结的打印缓冲、本句再排成**一条不折行**的显示行，差集逐句与降级纪律见
     * [BeginHost.printSingleLine] 的 KDoc。
     */
    override fun printSingleLine(text: String) {
        console.printSingleLine(text)
    }

    /**
     * `console.PrintError(str)`（`Process.cs:2585-2586` 自动存档失败两行；方法体
     * `EmueraConsole.cs:3098-3116`）—— OBS2-L1 组长接线：转交
     * [LineBufferConsoleSink.printError]（`ConsoleSink.kt:376` 声明、
     * `LineBufferConsoleSink.kt:2272-2298` 实现），与 [writeLine] 的六格语义差集
     * （空串早退 / `PrintFlush` / 样式粘滞复位 / 纯文本不折行不吃按钮解析 / 强制左对齐 /
     * 错误画面必上屏）逐句对照见 [BeginFlow] 里 `printError` 的 KDoc。
     */
    override fun printError(text: String) {
        console.printError(text)
    }

    /** `console.LastLineIsTemporary`（`Process.cs:2426`、`:2514`、`:2644`）——控制台的活状态。 */
    override fun lastLineIsTemporary(): Boolean = console.lines().lastOrNull()?.isTemporary ?: false

    /**
     * `console.LastLineIsEmpty`（`Process.cs:2428`）—— 与 [lastLineIsTemporary] 同源：
     * [LineBufferConsoleSink.lines] 的末行经 [LineBufferConsoleSink.textOf]
     * （= `ConsoleDisplayLine.ToString()` 的移植对位，见其 KDoc）整行判空；
     * 行表为空 = false（`EmueraConsole.cs:530-533`）。空判用 [CharSequence.isBlank]
     * （Unicode 空白族，含全角空格 U+3000）；与 C# `Trim()` 的已知差异只有 U+00A0
     * 一族不换行空格（.NET 计入空白、Kotlin isWhitespace 不计），语料临时行未见该字符，
     * 不在本通道扩判。
     */
    override fun lastLineIsEmpty(): Boolean =
        console.lines().lastOrNull()?.let { console.textOf(it).isBlank() } ?: false

    /**
     * `console.deleteLine(n)`（`Process.cs:2430`）—— 转交 [LineBufferConsoleSink.deleteLine]
     * （按逻辑行删，整段照抄 `EmueraConsole.cs:2999-3051`，口径见其 KDoc）。
     */
    override fun deleteLine(count: Int) {
        console.deleteLine(count)
    }

    /**
     * `console.PrintTemporaryLine(str)`（`Process.cs:2431`）—— 转交
     * [LineBufferConsoleSink.printTemporaryLine]（REUSELASTLINE 同族：下一次正常输出
     * 到达时先被删，`EmueraConsole.cs:2891-2893` 的 `addDisplayLine` 门）。
     */
    override fun printTemporaryLine(text: String) {
        console.printTemporaryLine(text)
    }

    /**
     * `console.updatedGeneration = true`（`Process.cs:2433` 及 W9-gen 补接的同款四处
     * `:2196`/`:2500`/`:2521`/`:2651`，全部是「非法输入/临时行收尾 → 重等」形态）—— 转交
     * [LineBufferConsoleSink.markGenerationUpdated]（只置脏不动世代，语义见其 KDoc）。
     */
    override fun markGenerationUpdated() {
        console.markGenerationUpdated()
    }

    /**
     * 「本页来源函数」登记（OBS2-L5 主通道）：把函数名换成身份位存进 [waitPageIdentity]，
     * 供下一次等待入口 [waitScriptLine] 取用。粒度、时机与原版字段对位关系见
     * [BeginHost.noteWaitPageProducer]；缺 resolver（假体/未接）时整条不记账，
     * 世代判据退回 [scriptLineToken] 与行水位，与本刀之前逐字一致。
     *
     * 单写单读：登记与取用都在 VM 线程（`BeginFlow.step` 与宿主等待同线程，ADR-003），
     * 不加锁、不 `@Volatile`。
     */
    override fun noteWaitPageProducer(functionKey: String) {
        val identity = pageScriptLineIdentity?.invoke(functionKey) ?: return
        if (identity >= 0L) {
            waitPageIdentity = identity
        }
    }

    /**
     * 等待入口的脚本行身份位 = 原版 `process.getCurrentLine` 的移植读数。
     *
     * 两个来源的取用次序：
     * 1. [scriptLineToken]（运行期现算通道 `ProcessRuntime.scriptLineTokenNow`，`:1242`）——
     *    **有帧时最准**（真行身份），但 BEGIN 状态机的等待全在无帧时刻，实测恒 -1；
     * 2. [waitPageIdentity]（[noteWaitPageProducer] 记的「本页来源函数」身份位）——
     *    无帧时的对位读数，正对原版「字段不清空」的形状。
     * 两者都没有 ⇒ -1 = 未接线哨兵 ⇒ 控制台退回行水位（`LineBufferConsoleSink.kt:713-714`）。
     *
     * 两个取用点的生效范围不同（如实登记）：[waitSystemInteger] 的 `INT` 等待走
     * `RequestForm.VALUE` ⇒ [LineBufferConsoleSink.noteInputWait] 会调 `newGeneration`
     * （`LineBufferConsoleSink.kt:690-696`），本方法的身份位**参与判定**；[readAnyKey] 的
     * `ENTER_KEY` 等待 `NeedValue == false` ⇒ `noteInputWait` 在 `:690-694` 提前返回、
     * 只清选项快照，身份位在那一点**不参与世代判定**（与原版同形 —— `newGeneration()` 的
     * `!inputReq.NeedValue` 早退门，`EmueraConsole.cs:785-788`），接与不接读数一致。
     */
    private fun waitScriptLine(): Long {
        val live = scriptLineToken?.invoke() ?: -1L
        return if (live >= 0L) live else waitPageIdentity
    }

    /** 最近一次登记的「本页来源函数」身份位；-1 = 从没跑过脚本函数（未接线）。 */
    private var waitPageIdentity: Long = -1L

    override val comAbleDefault: Int get() = comAbleDefaultValue

    override fun autoSave(): Boolean = autoSaveEnabled

    /**
     * AUTO_SAVE 链的实档（`Process.cs:2583` 的 `vEvaluator.SaveTo(99, SAVEDATA_TEXT)`）。
     * 转交构造参数 [saveToHook]（GameSession 接 `SystemOps.saveData`，与语句 `SAVEDATA`
     * 同一条落盘路）；未接线返回 null = 「宿主没有该能力」，由 [BeginFlow] 落 BLOCKED，
     * 不伪造成功。
     */
    override fun saveTo(slot: Int, text: String): Boolean? = saveToHook?.invoke(slot, text)

    /**
     * `console.ReadAnyKey()`（调用点 `Process.cs:2587` 自动存档失败 / `Process.cs:2450-2453`
     * 的 `setWait()`；方法体 `EmueraConsole.cs:876-895`）—— 等玩家点一下。转交
     * [QueueInputSink.readAnyKey]（anykey=false，与原版无参调用同形 —— `:876` 的默认实参），
     * 阻塞语义与原版一致。
     *
     * 帧发布口径与 [waitSystemInteger] 一致：等前把这一帧整个上屏、返回后重新冻结。
     * 这两个调用点都是「先打几行、再等按键」——不发帧的话 `REDRAW 0` 关闸时那几行根本不上屏，
     * 等待就变成了「屏上什么都没有、还要点一下」，比不等待更难懂。
     *
     * 消息跳过不参与：原版这两处等待都由 `Process` 无条件发起，是否被跳过属控制台侧的
     * `MesSkip` 循环（`EmueraConsole.cs:1451-1456`）；本移植的跳过意图由语句层 `WAIT` 系经
     * `ExecContext.mesSkip` 消费，从本入口另取一份会把那一侧的意图偷走。
     */
    override fun readAnyKey() {
        console.publishFrame()
        try {
            console.noteInputWait(
                InputRequest(
                    type = InputRequest.InputType.STR,
                    form = InputRequest.RequestForm.ENTER_KEY,
                    scriptLine = waitScriptLine(),
                ),
            )
            input.readAnyKey(anyKey = false, stopMesSkip = false)
        } finally {
            console.holdFrame()
        }
    }

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
        // 解冻与重新冻结必须成对：等待抛错（输入队列关闭/线程中断，见 [InputSinkClosedException]）
        // 时旧写法跳过 holdFrame，控制台停在"半发布"态 —— 之后任何一行可见变更都逐条 bump
        // revision，一屏一帧的批次发布从此失效（关闭/重开路径上的状态残留）。正常返回路径
        // 的执行序与异常前逐字相同：publishFrame → 取值 → holdFrame。
        try {
            val request = InputRequest(
                type = InputRequest.InputType.INT,
                scriptLine = waitScriptLine(),
            )
            // 系统整数等待同样是一次“当前页”的取值等待：走与 sem 层相同的选项冻结入口，
            // 避免 BEGIN/读档这类等待期间右侧栏还挂着上一页的按钮快照。
            console.noteInputWait(request)
            return input.waitInput(request).intValue
        } finally {
            console.holdFrame()
        }
    }
}
