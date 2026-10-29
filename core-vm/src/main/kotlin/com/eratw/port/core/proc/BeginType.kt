package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE

/**
 * BEGIN 入口类型 → 目标函数名的映射表。
 *
 * 依据 Process.cs 的系统处理链（每个 BeginType 在 Begin() 后由对应的 system proc
 * 发起第一次函数调用）：
 *
 * | BeginType  | 目标函数        | 事件? | force | spec                        |
 * |------------|-----------------|-------|-------|------------------------------|
 * | TITLE      | SYSTEM_TITLE    | 否    | false | Process.cs:2136（缺失→宿主画默认标题，不报错） |
 * | FIRST      | EVENTFIRST      | 是    | true  | Process.cs:2207（缺失→CodeEE FuncIsNotFound） |
 * | TRAIN      | EVENTTRAIN      | 是    | false | Process.cs:2220              |
 * | AFTERTRAIN | EVENTEND        | 是    | true  | Process.cs:2464              |
 * | ABLUP      | SHOW_ABLUP_SELECT | 否  | true  | Process.cs:2481              |
 * | TURNEND    | EVENTTURNEND    | 是    | true  | Process.cs:2535              |
 * | SHOP       | EVENTSHOP       | 是    | false | Process.cs:2546              |
 *
 * TODO(verify)：TRAIN/SHOP 的 force=false 分支在原版里是"回退到别的系统状态"
 * （EVENTTRAIN 缺失 → endCallEventTrain 直接走 beginTrain 的后续，Process.cs:2220-2234），
 * 不是静默结束；M2 简化为"缺失即返回不执行"，BEGIN 全状态机（SystemStateCode 状态图）
 * 属 M4 游戏流程层。
 */
enum class BeginType(
    val targetFunction: String,
    val isEvent: Boolean,
    val force: Boolean,
) {
    TITLE("SYSTEM_TITLE", isEvent = false, force = false),
    FIRST("EVENTFIRST", isEvent = true, force = true),
    TRAIN("EVENTTRAIN", isEvent = true, force = false),
    AFTERTRAIN("EVENTEND", isEvent = true, force = true),
    ABLUP("SHOW_ABLUP_SELECT", isEvent = false, force = true),
    TURNEND("EVENTTURNEND", isEvent = true, force = true),
    SHOP("EVENTSHOP", isEvent = true, force = false),
    ;

    companion object {
        /**
         * `BEGIN <keyword>` 的字符串入口 —— 原版 `ProcessState.SetBegin(string, bool)`
         * （`ProcessState.cs:135-167`）。原版是**裸 C# string switch**（`ProcessState.cs:139-161`），
         * 不经 `Config.StrComper` ⇒ **大小写敏感**（此前 KDoc 写「不敏感」是错的，已更正）。
         * 移植侧同为大小写敏感；语料实测（画蛇添足版 4.980，ERB 目录 `^\s*begin ` 共 23 行）
         * 关键字全为大写、无小写写法 ⇒ 收紧不打掉任何语料合法写法。
         * 仍保留的两处**超集**（原版会拒绝）：① `trim()` 前后空白（原版直接用
         * `func.Argument.ConstStr`，`FunctionIdentifier.cs:2932`，不 trim）；② 见下条函数名口径。
         *
         * 除关键字外**同时接受目标函数名**（`EVENTSHOP`/`EVENTFIRST`/…）：语句层
         * [ExecResult.Begin] 带的目标字符串既可能是脚本写的关键字，也可能是
         * 汇编期归一成的函数名（`StatementBridgeTest` 的既有口径），两种都按同一
         * BeginType 处理，避免同一入口两种行为。原版 `BEGIN EVENTSHOP` 落 default 抛错
         * （`ProcessState.cs:164-166`），函数名别名是移植侧超集读数。
         *
         * 未识别关键字 → `CodeEE`，文案 = 原版 zhs 表形 `BEGIN的关键字"{0}"没有定义`
         * （`Lang.cs:3118` InvalidBeginArg / `lang.emuera-zhs.xml:734`，关键字两侧为**成对直引号**；
         * `ProcessState.cs:164-166` 抛出；实参按原样插入，不 trim 不换大小写，同原版
         * `string.Format(..., keyword)`）。
         *
         * force 位：`BEGIN` 与 `FORCE_BEGIN` 两条语句**都**以 `force: true` 调
         * `SetBegin`（`FunctionIdentifier.cs:2933` / `:2950`），因此
         * `ExecResult.Begin`（`StatementExecutor.kt:73-74`）只带目标字符串**不构成语义丢失**；
         * `__CAN_BEGIN__` 守卫（`ProcessState.cs:179-184`）只对非语句入口
         * （读档后 `Begin(type)` 等）生效，本层不需要它。
         */
        fun fromKeyword(keyword: String): BeginType = when (keyword.trim()) {
            "SHOP", "EVENTSHOP" -> SHOP
            "TRAIN", "EVENTTRAIN" -> TRAIN
            "AFTERTRAIN", "EVENTEND" -> AFTERTRAIN
            "ABLUP", "SHOW_ABLUP_SELECT" -> ABLUP
            "TURNEND", "EVENTTURNEND" -> TURNEND
            "FIRST", "EVENTFIRST" -> FIRST
            "TITLE", "SYSTEM_TITLE" -> TITLE
            else -> throw CodeEE("""BEGIN的关键字"$keyword"没有定义""")
        }
    }
}
