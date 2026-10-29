package com.eratw.port.render.button

/**
 * 点击回环时「这次等待要什么类型的值」—— 对齐原版 `InputType` 的按钮三态。
 *
 * 原版语句（契约 m-button 写作 INPUTBUTTON / INPUTBUTTONS，实际语句名见
 * `specs/isa.yaml:966-1003`）：
 * - `BINPUT` / `ONEBINPUT`：`InputType.IntButton`
 *   （`FunctionIdentifier.cs:2113-2215`，InputRequest 见 2129-2132；候选筛选
 *   `IsButton && IsInteger` 见 2167、2189；ONEBINPUT 同族 2321-2424，筛选 2376、2398）
 * - `BINPUTS` / `ONEBINPUTS`：`InputType.StrButton`
 *   （`FunctionIdentifier.cs:2217-2319`，InputRequest 见 2233-2236；候选筛选只看
 *   `IsButton` 见 2271、2293；ONEBINPUTS 同族 2426-2529，筛选 2481、2503）
 * - 任意输入（`INPUT` / `INPUTANY` 系）：`InputType.AnyValue`
 *
 * 判据来自原版的命中筛选条件：排除支只有
 * `state == ConsoleState.WaitInput && !IsInteger && (InputType == IntValue ||
 * InputType == IntButton)` 这一条（`EmueraConsole.cs:2452-2459`，指着态同条件见
 * `EmueraConsole.cs:2439`）—— 字符串值按钮在数值等待期连「被指着」都不算；
 * `StrButton` 与 `AnyValue` 下两类按钮都是候选（没有对应的排除支）。
 *
 * 注意：这是**可点性**筛选，不是命中几何 —— 同一坐标下 `ButtonLayer.hit` 用
 * [ANY] 与 [INT] 的差别只在这个类型判断上。
 */
enum class ButtonAcceptance {

    /** 数值等待（BINPUT / ONEBINPUT）：只有 Long 值按钮可点。 */
    INT,

    /** 字符串等待（BINPUTS / ONEBINPUTS）：Long 与 String 值按钮都可点。 */
    STR,

    /** 无按钮输入在等待，或按任意值口径取：不做类型筛选。 */
    ANY;

    /** 该值在本次等待下是否算可点候选。 */
    fun accepts(value: Any?): Boolean = when (this) {
        INT -> value is Long
        STR, ANY -> value != null
    }
}
