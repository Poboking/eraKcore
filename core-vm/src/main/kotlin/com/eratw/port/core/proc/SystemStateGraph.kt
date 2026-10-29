package com.eratw.port.core.proc

/**
 * BEGIN 全状态机的**显式状态图**（C# `Process.cs:1995-2045` 的分派表 +
 * `Process.cs:2105-2645` 的各 handler）。
 *
 * 这张表回答四件事，逐条带 C# 行号：
 * - `handler`：进入该状态后 `runSystemProc()` 调用的方法（`Process.cs:2042-2045`）；
 * - `enter` / `exit`：进入与退出的前置/后置条件；
 * - `transitions`：handler 内可能写入的下一状态（含守卫条件）；
 * - `failure`：无合法出口时的原版行为（多数是 `CodeEE`）。
 *
 * 图**只描述** handler 驱动的关系。脚本执行 `BEGIN <type>` 引起的跳转不在此图里：
 * 那是 `ProcessState.Begin()` 的行为（`ProcessState.cs:219-278`、`:352-362`），
 * 对任何当前状态都成立，由 [BeginFlow.select] 处理。
 */
data class StateTransition(
    val to: SystemStateCode,
    /** 触发该迁移的条件（原版代码里的 if/分支）。 */
    val guard: String,
    val evidence: String,
)

data class StateNode(
    val state: SystemStateCode,
    /** 原版 handler 方法名（`systemProcessDictionary` 的值）。 */
    val handler: String,
    val enter: String,
    val exit: String,
    val transitions: List<StateTransition>,
    /** 无合法出口时的原版失败语义。 */
    val failure: String,
    val evidence: String,
)

/** 非法状态迁移（测试与 [SystemStateGraph.requireTransition] 用）。 */
class IllegalSystemTransition(
    val from: SystemStateCode,
    val to: SystemStateCode,
    val allowed: List<SystemStateCode>,
) : IllegalStateException(
    "非法系统状态迁移 ${from.name} -> ${to.name}；允许：${allowed.joinToString { it.name }}",
)

object SystemStateGraph {

    /** `ProcessState.Begin(BeginType)` 的初始状态（`ProcessState.cs:225-256`）。 */
    fun entryStateOf(type: BeginType): SystemStateCode = when (type) {
        BeginType.SHOP -> SystemStateCode.Shop_Begin
        BeginType.TRAIN -> SystemStateCode.Train_Begin
        BeginType.AFTERTRAIN -> SystemStateCode.AfterTrain_Begin
        BeginType.ABLUP -> SystemStateCode.Ablup_Begin
        BeginType.TURNEND -> SystemStateCode.Turnend_Begin
        BeginType.FIRST -> SystemStateCode.First_Begin
        BeginType.TITLE -> SystemStateCode.Title_Begin
    }

    /**
     * 节点构造器。用可变 builder 而不是 `vararg + 命名参数` 混排，避免调用点歧义。
     * 只在 [nodes] 初始化期使用，构造完即丢弃。
     */
    private class Builder(val state: SystemStateCode, val handler: String) {
        var enter: String = ""
        var exit: String = ""
        var failure: String = ""
        var evidence: String = ""
        val transitions: MutableList<StateTransition> = ArrayList()

        fun to(target: SystemStateCode, guard: String, evidence: String) {
            transitions.add(StateTransition(target, guard, evidence))
        }

        fun build(): StateNode =
            StateNode(state, handler, enter, exit, transitions.toList(), failure, evidence)
    }

    private fun node(
        state: SystemStateCode,
        handler: String,
        block: Builder.() -> Unit,
    ): StateNode = Builder(state, handler).apply(block).build()

    private val list: List<StateNode> = listOf(
        // ------------------------------------------------------------------
        // 标题 / 新游戏
        // ------------------------------------------------------------------
        node(SystemStateCode.Title_Begin, "beginTitle") {
            enter = "BEGIN TITLE 或启动完成（Process.cs:346 / :484）"
            exit = "SYSTEM_TITLE 被调用 → Normal；缺失 → 画默认标题后进 Openning"
            failure = "无（SYSTEM_TITLE 缺失不是错误，由宿主画默认标题）"
            evidence = "Process.cs:2105-2159"
            to(SystemStateCode.Normal, "callFunction(\"SYSTEM_TITLE\", force=false) == true", "Process.cs:2136-2140")
            to(SystemStateCode.Openning, "SYSTEM_TITLE 缺失 → openingInput()", "Process.cs:2141-2165")
            to(SystemStateCode.Title_Begin, "isCTrain && ClearCommands() → 直接返回", "Process.cs:2107-2110")
        },
        node(SystemStateCode.Openning, "endOpenning") {
            enter = "openingInput() 已设置 WaitInput（Process.cs:2161-2165）"
            exit = "systemResult 0 → First_Begin；1 → 读档；其他 → 重新等输入"
            failure = "无效值：deleteLine(1) + 临时行 + 重新等输入（不是异常）"
            evidence = "Process.cs:2167-2199"
            to(SystemStateCode.First_Begin, "systemResult == 0", "Process.cs:2169-2180")
            to(SystemStateCode.Openning_TitleLoadgame, "systemResult == 1 且 TITLE_LOADGAME 存在", "Process.cs:2181-2186")
            to(SystemStateCode.LoadGameOpenning_Begin, "systemResult == 1 且 TITLE_LOADGAME 缺失", "Process.cs:2187-2190")
            to(SystemStateCode.Openning, "systemResult 非 0/1 → 重新等输入", "Process.cs:2192-2198")
        },
        node(SystemStateCode.First_Begin, "beginFirst") {
            enter = "Openning(systemResult=0) 或脚本 BEGIN FIRST"
            exit = "先置 Normal，再派发 EVENTFIRST（force=true）"
            failure = "EVENTFIRST 缺失 → CodeEE FuncIsNotFound（force 语义，Process.cs:2207）"
            evidence = "Process.cs:2201-2209"
            to(SystemStateCode.Normal, "始终（置状态发生在调用之前）", "Process.cs:2203")
        },
        node(SystemStateCode.Openning_TitleLoadgame, "endTitleLoadgame") {
            enter = "TITLE_LOADGAME 函数返回"
            exit = "无条件回到标题"
            failure = "无"
            evidence = "Process.cs:2211-2214"
            to(SystemStateCode.Title_Begin, "无条件", "Process.cs:2213")
        },
        node(SystemStateCode.LoadGameOpenning_Begin, "beginLoadGameOpening") {
            enter = "TITLE_LOADGAME 缺失时的读档界面"
            exit = "进入读档输入等待"
            failure = "宿主无读档界面 → 阻塞（BOOT-2 不实现）"
            evidence = "Process.cs:2189 / Process.cs:2699-2704"
            to(SystemStateCode.LoadGameOpenning_WaitInput, "读档界面已显示", "Process.cs:2757-2814")
        },
        // 读档界面（LoadGameOpenning_WaitInput）的 handler 体属 M4 存档层；
        // 本节点只补图闭包（迁移目标必须已登记），BOOT-2 不在该状态执行任何脚本调用。
        node(SystemStateCode.LoadGameOpenning_WaitInput, "loadGameWaitInput") {
            enter = "printSaveDataText() 末尾 setWaitInput 后（Process.cs:2802-2814）"
            exit = "宿主选定存档 → 载入流程（BOOT-2 未接管）"
            failure = "宿主能力缺失 → 阻塞（不是原版错误路径）"
            evidence = "Process.cs:2817-2945 邻域"
        },

        // ------------------------------------------------------------------
        // 训练（回合主循环）
        // ------------------------------------------------------------------
        node(SystemStateCode.Train_Begin, "beginTrain") {
            enter = "BEGIN TRAIN 或读档后 BEGIN TRAIN"
            exit = "重置训练状态 → Train_CallEventTrain → 派发 EVENTTRAIN（force=false）"
            failure = "EVENTTRAIN 缺失不是错误：下一状态 handler 直接走 endCallEventTrain"
            evidence = "Process.cs:2216-2224"
            to(SystemStateCode.Train_CallEventTrain, "始终", "Process.cs:2219")
        },
        node(SystemStateCode.Train_CallEventTrain, "endCallEventTrain") {
            enter = "EVENTTRAIN 返回 / 缺失"
            exit = "NEXTCOM>=0 → Train_CallEventCom；否则 → SHOW_STATUS(force) → Train_CallShowStatus"
            failure = "SHOW_STATUS 缺失 → CodeEE（force=true，Process.cs:2240）"
            evidence = "Process.cs:2226-2242"
            to(SystemStateCode.Train_CallEventCom, "NEXTCOM >= 0", "Process.cs:2228-2234")
            to(SystemStateCode.Train_CallShowStatus, "NEXTCOM < 0", "Process.cs:2240-2241")
        },
        node(SystemStateCode.Train_CallShowStatus, "endCallShowStatus") {
            enter = "SHOW_STATUS 返回"
            exit = "清空 comAble / lastCalledComable / printComCount → Train_CallComAbleXX"
            failure = "无"
            evidence = "Process.cs:2244-2255"
            to(SystemStateCode.Train_CallComAbleXX, "无条件", "Process.cs:2246-2254")
        },
        node(SystemStateCode.Train_CallComAbleXX, "endCallComAbleXX") {
            enter = "comAble 清空后 / 每个 COM_ABLE<n> 返回后"
            exit = "逐个 COM_ABLE<n>；遍历完 → SHOW_USERCOM(force) → Train_CallShowUserCom"
            failure = "SHOW_USERCOM 缺失 → CodeEE（force=true，Process.cs:2317）"
            evidence = "Process.cs:2263-2319"
            to(SystemStateCode.Train_CallComAbleXX, "当前 COM_ABLE<n> 存在并已调用", "Process.cs:2309-2310")
            to(SystemStateCode.Train_CallShowUserCom, "TrainName 遍历完", "Process.cs:2312-2318")
        },
        node(SystemStateCode.Train_CallShowUserCom, "endCallShowUserCom") {
            enter = "SHOW_USERCOM 返回"
            exit = "非 CTrain → Train_WaitInput；CTrain 且还有指令 → trainWaitInput"
            failure = "无（等输入）"
            evidence = "Process.cs:2321-2339"
            to(SystemStateCode.Train_WaitInput, "!isCTrain", "Process.cs:2328-2332")
            to(SystemStateCode.Train_CallEventCom, "isCTrain 且 count < coms.Count", "Process.cs:2333-2338")
        },
        node(SystemStateCode.Train_WaitInput, "trainWaitInput") {
            enter = "用户指令选择完成"
            exit = "有效指令 → SELECTCOM + callEventCom；否则 USERCOM(force)"
            failure = "USERCOM 缺失 → CodeEE（force=true，Process.cs:2374）"
            evidence = "Process.cs:2341-2375"
            to(SystemStateCode.Train_CallEventCom, "选中了可执行指令", "Process.cs:2362-2366")
            to(SystemStateCode.Train_CallEventComEnd, "未选中有效指令", "Process.cs:2372-2374")
        },
        node(SystemStateCode.Train_CallEventCom, "endEventCom") {
            enter = "callEventCom()/endCallEventTrain 后派发 EVENTCOM（force=false）"
            exit = "EVENTCOM 返回或缺失 → COM{SELECTCOM}(force) → Train_CallComXX"
            failure = "COM{SELECTCOM} 缺失 → CodeEE（force=true，Process.cs:2399）"
            evidence = "Process.cs:2384-2400"
            to(SystemStateCode.Train_CallComXX, "始终", "Process.cs:2396-2399")
        },
        node(SystemStateCode.Train_CallComXX, "endCallComXX") {
            enter = "COM<n> 返回"
            exit = "RESULT==0 → Train_CallEventComEnd；否则 SOURCE_CHECK(force) → Train_CallSourceCheck"
            failure = "SOURCE_CHECK 缺失 → CodeEE（force=true，Process.cs:2410）"
            evidence = "Process.cs:2402-2411"
            to(SystemStateCode.Train_CallEventComEnd, "RESULT == 0", "Process.cs:2404-2407")
            to(SystemStateCode.Train_CallSourceCheck, "RESULT != 0", "Process.cs:2409-2410")
        },
        node(SystemStateCode.Train_CallSourceCheck, "endCallSourceCheck") {
            enter = "SOURCE_CHECK 返回"
            exit = "UpdateAfterSourceCheck → EVENTCOMEND(force=false) → Train_CallEventComEnd"
            failure = "EVENTCOMEND 缺失不是错误，直接 endCallEventComEnd"
            evidence = "Process.cs:2413-2422"
            to(SystemStateCode.Train_CallEventComEnd, "始终", "Process.cs:2416-2420")
        },
        node(SystemStateCode.Train_CallEventComEnd, "endCallEventComEnd") {
            enter = "EVENTCOMEND 返回 / COM 链结束"
            exit = "临时行校验失败 → Train_CallShowUserCom；否则回到 endCallEventTrain"
            failure = "CALLTRAINEND 存在时直接终止本轮回合（不回训练菜单，Process.cs:2443-2447）"
            evidence = "Process.cs:2424-2456"
            to(SystemStateCode.Train_CallShowUserCom, "LastLineIsTemporary && needCheck", "Process.cs:2426-2436")
            // 原版直接调用 endCallEventTrain()（:2455）；本移植拆成一步（见 BeginFlow KDoc）。
            to(SystemStateCode.Train_CallEventTrain, "否则 → endCallEventTrain（本移植的一步拆分）", "Process.cs:2455")
            to(SystemStateCode.Train_CallEventCom, "endCallEventTrain 且 NEXTCOM >= 0", "Process.cs:2455 → 2228-2234")
            to(SystemStateCode.Train_CallShowStatus, "endCallEventTrain 且 NEXTCOM < 0", "Process.cs:2455 → 2240-2241")
        },
        node(SystemStateCode.Train_DoTrain, "doTrain") {
            enter = "DOTRAIN 指令（仅 Train_CallEventTrain/ShowUserCom/EventComEnd 允许，Process.cs:1934）"
            exit = "SELECTCOM = doTrainSelectCom → callEventCom"
            failure = "非法状态 → CodeEE CanNotUseDotrainHere（Process.cs:1936-1937）"
            evidence = "Process.cs:2377-2382"
            to(SystemStateCode.Train_CallEventCom, "无条件", "Process.cs:2379-2381")
        },

        // ------------------------------------------------------------------
        // 训练后 / 能力提升 / 回合结束
        // ------------------------------------------------------------------
        node(SystemStateCode.AfterTrain_Begin, "beginAfterTrain") {
            enter = "BEGIN AFTERTRAIN"
            exit = "置 Normal → EVENTEND(force=true)"
            failure = "EVENTEND 缺失 → CodeEE（force=true，Process.cs:2464）"
            evidence = "Process.cs:2458-2466"
            to(SystemStateCode.Normal, "始终", "Process.cs:2463-2464")
        },
        node(SystemStateCode.Ablup_Begin, "beginAblup") {
            enter = "BEGIN ABLUP"
            exit = "Ablup_CallShowJuel → SHOW_JUEL(force)"
            failure = "SHOW_JUEL 缺失 → CodeEE（force=true，Process.cs:2474）"
            evidence = "Process.cs:2468-2476"
            to(SystemStateCode.Ablup_CallShowJuel, "始终", "Process.cs:2473-2474")
        },
        node(SystemStateCode.Ablup_CallShowJuel, "endCallShowJuel") {
            enter = "SHOW_JUEL 返回"
            exit = "Ablup_CallShowAblupSelect → SHOW_ABLUP_SELECT(force)"
            failure = "SHOW_ABLUP_SELECT 缺失 → CodeEE（force=true，Process.cs:2481）"
            evidence = "Process.cs:2478-2482"
            to(SystemStateCode.Ablup_CallShowAblupSelect, "始终", "Process.cs:2480-2481")
        },
        node(SystemStateCode.Ablup_CallShowAblupSelect, "endCallShowAblupSelect") {
            enter = "SHOW_ABLUP_SELECT 返回"
            exit = "setWaitInput → Ablup_WaitInput"
            failure = "无（等输入）"
            evidence = "Process.cs:2484-2488"
            to(SystemStateCode.Ablup_WaitInput, "始终", "Process.cs:2486-2487")
        },
        node(SystemStateCode.Ablup_WaitInput, "ablupWaitInput") {
            enter = "能力提升选择完成"
            exit = "0..99 → ABLUP{n}(force=false)；其他 → USERABLUP(force=true)"
            failure = "ABLUP{n} 缺失不是错误：清行 + 重新等输入（Process.cs:2496-2502）"
            evidence = "Process.cs:2490-2510"
            to(SystemStateCode.Ablup_CallAblupXX, "systemResult 0..99 或 USERABLUP 分支", "Process.cs:2494-2508")
            to(SystemStateCode.Ablup_WaitInput, "ABLUP{n} 缺失 → 清行后重新等输入", "Process.cs:2496-2502")
        },
        node(SystemStateCode.Ablup_CallAblupXX, "endCallAblupXX") {
            enter = "ABLUP{n} / USERABLUP 返回"
            exit = "临时行 → 重新显示选择；否则 beginAblup()"
            failure = "无"
            evidence = "Process.cs:2512-2528"
            to(SystemStateCode.Ablup_CallShowAblupSelect, "LastLineIsTemporary", "Process.cs:2514-2523")
            to(SystemStateCode.Ablup_Begin, "否则", "Process.cs:2526")
        },
        node(SystemStateCode.Turnend_Begin, "beginTurnend") {
            enter = "BEGIN TURNEND"
            exit = "EVENTTURNEND(force=true) → Normal（状态写在调用之后）"
            failure = "EVENTTURNEND 缺失 → CodeEE（force=true，Process.cs:2535）"
            evidence = "Process.cs:2530-2538"
            to(SystemStateCode.Normal, "始终", "Process.cs:2535-2536")
        },

        // ------------------------------------------------------------------
        // 商店 / 自动存档
        // ------------------------------------------------------------------
        node(SystemStateCode.Shop_Begin, "beginShop") {
            enter = "BEGIN SHOP（calledWhenNormal 记录来源状态，Process.cs:227-236）"
            exit = "Shop_CallEventShop → EVENTSHOP(force=false)"
            failure = "EVENTSHOP 缺失不是错误：下一状态 handler 走 endCallEventShop"
            evidence = "Process.cs:2540-2551"
            to(SystemStateCode.Shop_CallEventShop, "始终", "Process.cs:2545-2549")
        },
        node(SystemStateCode.Shop_CallEventShop, "endCallEventShop") {
            enter = "EVENTSHOP 返回 / 缺失"
            exit = "AutoSave 开启且 calledWhenNormal → 自动存档链；否则 AutoSave_Skipped"
            failure = "无（存档失败仅打印两行错误，Process.cs:2583-2588）"
            evidence = "Process.cs:2553-2563"
            to(SystemStateCode.AutoSave_CallUniqueAutosave, "SYSTEM_AUTOSAVE 存在", "Process.cs:2565-2571")
            to(SystemStateCode.AutoSave_CallSaveInfo, "SYSTEM_AUTOSAVE 缺失且 SAVEINFO 存在", "Process.cs:2572-2578")
            to(SystemStateCode.AutoSave_Skipped, "AutoSave 关闭 / calledWhenNormal=false", "Process.cs:2561")
        },
        node(SystemStateCode.AutoSave_CallSaveInfo, "endAutoSaveCallSaveInfo") {
            enter = "SAVEINFO 返回 / 缺失"
            exit = "endAutoSave()：isBegun → 执行挂起的 BEGIN；否则 SHOW_SHOP(force)"
            failure = "SHOW_SHOP 缺失 → CodeEE（force=true，Process.cs:2600）"
            evidence = "Process.cs:2581-2601"
            to(SystemStateCode.Shop_CallShowShop, "!state.isBegun", "Process.cs:2594-2600")
            to(SystemStateCode.Shop_Begin, "state.isBegun（挂起的 BEGIN）", "Process.cs:2594-2598")
        },
        node(SystemStateCode.AutoSave_CallUniqueAutosave, "endAutoSave") {
            enter = "SYSTEM_AUTOSAVE 返回"
            exit = "同 endAutoSaveCallSaveInfo"
            failure = "SHOW_SHOP 缺失 → CodeEE（force=true）"
            evidence = "Process.cs:2592-2601"
            to(SystemStateCode.Shop_CallShowShop, "!state.isBegun", "Process.cs:2594-2600")
            to(SystemStateCode.Shop_Begin, "state.isBegun", "Process.cs:2594-2598")
        },
        node(SystemStateCode.AutoSave_Skipped, "(无)") {
            enter = "endCallEventShop 直接赋值（不是 runSystemProc 的入口）"
            exit = "立即调用 endAutoSaveCallSaveInfo()（Process.cs:2561-2562）"
            failure = "同 AutoSave_CallSaveInfo"
            evidence = "Process.cs:2561-2562"
            to(SystemStateCode.Shop_CallShowShop, "!state.isBegun", "Process.cs:2594-2600")
            to(SystemStateCode.Shop_Begin, "state.isBegun", "Process.cs:2594-2598")
        },
        node(SystemStateCode.Shop_CallShowShop, "endCallShowShop") {
            enter = "SHOW_SHOP 返回"
            exit = "setWaitInput → Shop_WaitInput"
            failure = "无（等输入）"
            evidence = "Process.cs:2603-2607"
            to(SystemStateCode.Shop_WaitInput, "始终", "Process.cs:2605-2606")
        },
        node(SystemStateCode.Shop_WaitInput, "shopWaitInput") {
            enter = "商店选择完成"
            exit = "有效购买/用户商品 → Shop_CallEventBuy；无效 → endCallShowShop 回本状态重等输入（不重跑 SHOW_SHOP）"
            failure = "无（无效值只打印临时行，Process.cs:2624-2632）"
            evidence = "Process.cs:2609-2640"
            to(SystemStateCode.Shop_CallEventBuy, "ItemSales && BuyItem 或 USERSHOP 分支", "Process.cs:2611-2639")
            // 购买失败/缺货的落点是 endCallShowShop()（:2632），其方法体 :2603-2607 =
            // setWaitInput() + 状态写回 Shop_WaitInput —— 不经 Shop_CallShowShop，
            // 也不重跑 SHOW_SHOP（商店清单仍在屏上，删的只是输入行）。
            to(SystemStateCode.Shop_WaitInput, "购买失败/库存不足 → endCallShowShop()", "Process.cs:2632 / :2603-2607")
        },
        node(SystemStateCode.Shop_CallEventBuy, "endCallEventBuy") {
            enter = "EVENTBUY 返回 / 缺失"
            exit = "临时行 → 重显商店；否则 endAutoSave()"
            failure = "无"
            evidence = "Process.cs:2642-2658"
            to(SystemStateCode.Shop_CallShowShop, "LastLineIsTemporary", "Process.cs:2644-2653")
            to(SystemStateCode.AutoSave_Skipped, "否则 → endAutoSave()", "Process.cs:2656")
        },

        // ------------------------------------------------------------------
        // 常态
        // ------------------------------------------------------------------
        node(SystemStateCode.Normal, "endNormal") {
            enter = "SYSTEM_TITLE / EVENTFIRST / EVENTEND / EVENTTURNEND 后"
            exit = "无：脚本函数栈空即视为“预期之外的脚本结束”"
            failure = "CodeEE UnexpectedScriptEnd（Process.cs:2959-2962）"
            evidence = "Process.cs:2039 / Process.cs:2959-2962"
            // 脚本里的 BEGIN 会在函数栈清空时切状态（ProcessState.cs:352-362），
            // 那是 BeginFlow.select 的入口，不是 handler 迁移，故此处不列。
        },
    )

    val nodes: Map<SystemStateCode, StateNode> = list.associateBy { it.state }

    init {
        for (node in list) {
            for (tr in node.transitions) {
                require(nodes.containsKey(tr.to)) {
                    "状态图引用了未登记的状态 ${tr.to.name}（来自 ${node.state.name}）"
                }
            }
        }
    }

    fun nodeOf(state: SystemStateCode): StateNode =
        nodes[state] ?: error("状态 ${state.name} 不在 BEGIN 状态图内（BOOT-2 未接管）")

    fun allowedTargets(state: SystemStateCode): List<SystemStateCode> =
        nodes[state]?.transitions?.map { it.to } ?: emptyList()

    fun isLegal(from: SystemStateCode, to: SystemStateCode): Boolean =
        nodes[from]?.transitions?.any { it.to == to } ?: false

    /** 测试/断言用：非法迁移直接抛 [IllegalSystemTransition]。 */
    fun requireTransition(from: SystemStateCode, to: SystemStateCode) {
        if (!isLegal(from, to)) throw IllegalSystemTransition(from, to, allowedTargets(from))
    }
}
