package com.eratw.port.render.input

/**
 * 输入框**位置与宽度**的四态状态机 —— `MOVETEXTBOX` / `RESUMETEXTBOX` 那一套
 * （`MinorShift.Emuera.Forms/MainWindow.cs:23-48`、`:214-220`、`:327-376`
 * 与函数壳 `FunctionMethodCreator.cs:3079-3108`）。
 *
 * ## 单位是像素，不是半角格
 * 原版这一段全程用 `ClientSize`/`Location`/`Size`（`MainWindow.cs:342-348`），
 * 与等宽字符网格无关；契约 §0.5 允许两条口径并存，本类属于「像素那条」，
 * 因此**不**调 [com.eratw.port.render.Grid]。真机侧由绑定层把 dp 换成 px 再进来。
 *
 * ## 为什么要「延迟生效」
 * `SetTextBoxPos(int, int, int)` 只写 `nextTextBoxInfo` 并把状态置成
 * `WatingToChange`（:342-348，注意原版就是这个拼错的枚举名，:35），
 * **不动控件**；真正把几何落到 `richTextBox1` 上的是私有重载
 * `SetTextBoxPos(TextBoxInfo)`（:371-376），调用它的只有三支：
 * - `ApplyTextBoxChanges()`：状态是 `WatingToChange` 或 `ScrollBack` 才生效，并把状态置 `Changed`
 *   （:362-369）；
 * - `ResetTextBoxPos()`：直接回到默认几何并把状态置 `Unchanged`（:350-354）；
 * - `ScrollBackTextBoxPos()`：直接回到默认几何但状态置 `ScrollBack`（:356-360）。
 *
 * 于是「谁触发生效」成了语句相关的口径：
 * `INPUT`（`FunctionIdentifier.cs:852`）与 `INPUTS`（:896）以及
 * `BINPUT`/`BINPUTS`/`ONEBINPUT`/`ONEBINPUTS`（:2143、:2247、:2352、:2457）
 * 在等待前调 `ApplyTextBoxChanges()`；而 **`ONEINPUT`/`ONEINPUTS`/`TINPUT`/`TINPUTS`
 * 不调**（各自的 `DoInstruction` 里根本没有那一句，见 :915-957、:959-1001、:1003-1050、:1052-1099）。
 * 所以 `MOVETEXTBOX` 之后紧跟一个 `TINPUT` 时框子**不动**，要等下一条 `INPUT`/`INPUTS`/
 * `BINPUT` 系，或者等滚动条滚回底部（:335-338 那支）才动。这条「延迟」必须建模，
 * 否则安卓侧一收到 `MOVETEXTBOX` 就搬框子，与 PC 屏上的位置不一致。
 *
 * ## RESUMETEXTBOX 的恢复语义
 * 同一个 `MoveTextBoxMethod` 类的 `resume` 分支：`ResetTextBoxPos()`，
 * **三个实参整个丢掉**（`FunctionMethodCreator.cs:3098-3101` 对 :3086-3091 的三元声明），
 * 但装载期仍按 `MOVETEXTBOX` 的参数表校验（`specs/verify-notes.md:335`、
 * `specs/builtin-functions.yaml:1482-1487`）。返回值恒 1（:3106）。
 * 另一支恢复入口是「提交成功后」：`doInputToEmueraProgram` 末尾
 * `if (window.TextBoxPosChanged) window.ResetTextBoxPos()`（`EmueraConsole.cs:1283-1286`）——
 * 只夹 `Changed` 态，**`WatingToChange`（还没生效的）不会被复位**。
 *
 * ## 滚动条联动（:327-340）
 * 向上滚 ⇒ 若已 `Changed` 就 `ScrollBack`（把框收回默认位，让出画面）；
 * 滚回底部 ⇒ 若处于 `ScrollBack` 就 `ApplyTextBoxChanges()`（把之前请求过的位置补上）。
 * 这段由 `TextBoxIgnoreScrollBarChanges` 闸门挡住程序化滚动
 * （`EmueraConsole.cs:2551`、`:2566` 在 `verticalScrollBarUpdate` 前后一开一关）。
 */
class TextBoxPosition(
    /** 客户区宽（`base.ClientSize.Width`，`MainWindow.cs:344`、`:346`）。 */
    var clientWidthPx: Int,
    /** 客户区高（`base.ClientSize.Height`，`MainWindow.cs:345`）。 */
    var clientHeightPx: Int,
    /**
     * 默认几何 = 构造时快照的 `textBoxInfo`（`MainWindow.cs:275-278` 抄的是 :727-728
     * 那一行的初始值：`Location=(0, ClientHeight-LineHeight)`、`Size=(Config.WindowX, LineHeight)`）。
     */
    val defaultInfo: BoxGeom,
) {

    /** 当前真正生效的几何（对应 `richTextBox1.Left/Top/Size`）。 */
    var applied: BoxGeom = defaultInfo
        private set

    /** 已算好但还没生效的几何（`nextTextBoxInfo`，:46）。 */
    var pending: BoxGeom = defaultInfo
        private set

    /** 状态机当前态（:48）。 */
    var state: PosState = PosState.UNCHANGED
        private set

    /** 程序化滚动期间抑制滚动联动（`TextBoxIgnoreScrollBarChanges`，:214）。 */
    var ignoreScrollBarChanges: Boolean = false

    /** `TextBoxPosChanged`（:216）。 */
    val posChanged: Boolean
        get() = state == PosState.CHANGED

    /** `TextBoxPosScrolledBack`（:218）。 */
    val posScrolledBack: Boolean
        get() = state == PosState.SCROLL_BACK

    /** `TextBoxPosWatingToChange`（:220，原版的拼写）。 */
    val posWaitingToChange: Boolean
        get() = state == PosState.WATING_TO_CHANGE

    /**
     * `SetTextBoxPos(xOffset, yOffset, width)`（:342-348）= `MOVETEXTBOX x, y, width`
     * （`FunctionMethodCreator.cs:3104`）。三个夹取式**逐字照抄**：
     * - 左沿夹到 `[0, clientWidth - 50]`；
     * - 上沿按「从底部往上数 yOffset 再减去框高」算，夹到 `[0, clientHeight - boxHeight]`，
     *   其中 `boxHeight` 取**当前生效**的高度（`:345` 读 `richTextBox1.Height`）；
     * - 宽夹到 `[50, clientWidth - 当前左沿]`（`:346` 读 `richTextBox1.Left`），高度不动。
     *
     * 只写 `pending` + 置 `WATING_TO_CHANGE`，不动 `applied`（类文档的「延迟生效」）。
     */
    fun requestMove(xOffsetPx: Int, yOffsetFromBottomPx: Int, widthPx: Int) {
        val left = maxOf(0, minOf(xOffsetPx, clientWidthPx - MIN_WIDTH_PX))
        val topFromBottom = clientHeightPx - yOffsetFromBottomPx - applied.height
        val top = minOf(maxOf(topFromBottom, 0), clientHeightPx - applied.height)
        val width = maxOf(MIN_WIDTH_PX, minOf(widthPx, clientWidthPx - applied.left))
        pending = BoxGeom(left, top, width, applied.height)
        state = PosState.WATING_TO_CHANGE
    }

    /**
     * `ApplyTextBoxChanges()`（:362-369）：只有 `WATING_TO_CHANGE`/`SCROLL_BACK` 才生效，
     * 生效后状态一律置 `CHANGED`；其余状态**原样不动**（`UNCHANGED` 时是空操作，
     * 这正是「`RESUMETEXTBOX` 之后 `INPUT` 不会把丢掉的位置又捡回来」的机制）。
     */
    fun applyChanges() {
        if (posWaitingToChange || posScrolledBack) {
            applied = pending
            state = PosState.CHANGED
        }
    }

    /**
     * `ResetTextBoxPos()`（:350-354）= `RESUMETEXTBOX`（`FunctionMethodCreator.cs:3100`）
     * 与「提交后自动复位」（`EmueraConsole.cs:1285`）两个入口的共同实现：
     * 直接把默认几何刷上控件并把状态置回 `UNCHANGED`。
     * 注意 `pending` **不清**（原版 `nextTextBoxInfo` 保持原值，:352 只调私有重载）。
     */
    fun reset() {
        applied = defaultInfo
        state = PosState.UNCHANGED
    }

    /**
     * `ScrollBackTextBoxPos()`（:356-360）：回到默认几何，但状态记成 `SCROLL_BACK`
     * —— 意思是「用户滚上去看历史了，等回到底部时要把 [pending] 补回来」。
     */
    fun scrollBack() {
        applied = defaultInfo
        state = PosState.SCROLL_BACK
    }

    /**
     * `textBoxHandleScrollValueChanged`（:327-340）。
     *
     * @param atMaximum 滚动条是否在底部（原版 `vScrollBar.Value == vScrollBar.Maximum`）；
     *   不在底部即视为「用户往上看了」（:331 那个 `<` 判定）
     * @return 本次是否真的动了状态（闸门关着或状态不匹配时为 false）
     */
    fun onScrollBarValueChanged(atMaximum: Boolean): Boolean {
        if (ignoreScrollBarChanges) {
            return false
        }
        if (!atMaximum && posChanged) {
            scrollBack()
            return true
        }
        if (atMaximum && posScrolledBack) {
            applyChanges()
            return true
        }
        return false
    }

    /** 提交成功后的收尾（`EmueraConsole.cs:1283-1286`）：仅 `CHANGED` 态复位。 */
    fun afterAcceptedInput() {
        if (posChanged) {
            reset()
        }
    }

    /** 本层用到的两个像素常量（:344、:346 里那两个 `50` 字面量）。 */
    companion object {
        /** 左沿与宽度下界的同一个 50（`MainWindow.cs:344`、`:346`）。 */
        const val MIN_WIDTH_PX: Int = 50
    }
}

/**
 * 输入框几何（像素）。字段序与 C# `TextBoxInfo{Top, Left, Size}` 一一对应
 * （`MainWindow.cs:23-30`），这里按「left, top, width, height」摊平便于断言。
 */
data class BoxGeom(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * 位置状态，取值与顺序照原版 `TextBoxState`（`MainWindow.cs:32-38`；
 * `WatingToChange` 的拼写错误**原样保留**，ADR-005）。
 */
enum class PosState {
    UNCHANGED,
    WATING_TO_CHANGE,
    CHANGED,
    SCROLL_BACK,
}
