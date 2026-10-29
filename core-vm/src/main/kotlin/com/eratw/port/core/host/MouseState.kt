package com.eratw.port.core.host

/**
 * 鼠标现场状态（MOUSE 族的数据源）——**宿主可写、core 只读**的一份每 VM 状态。
 *
 * 为什么挂在 `store.aux` 而不是 [com.eratw.port.core.fn.BuiltinHost]：
 * fn 层只能经 `BuiltinContext.store.aux` 够到「宿主写、脚本读」的活状态，而
 * `BuiltinHost` 是**只读快照**接口（CONTRACT-M4-I §1 全波禁改 `core/fn/Builtins.kt`，
 * 本路不能往里加钩子）。宿主（platform 的触摸/指针事件、或测试装置）拿到同一个实例后
 * 直接写字段即可，脚本侧读到的是现场值——与原版「每次调用现场轮询
 * `PointToClient(Cursor.Position)`」等价（`reference/emuera-cs/MinorShift.Emuera.GameView/EmueraConsole.cs:2257-2266`）。
 *
 * 取用方式（同一份状态，禁止第二处记账）：
 * ```
 * val mouse = mouseStateOf(store.aux)   // fn 层 / 宿主都用这一个入口
 * ```
 *
 * 坐标口径（**照抄原版，不是常见 GUI 直觉**，逐条带行号）：
 * - 原版 [positionX]/[positionY] 存的是 `MainPicBox.PointToClient(Cursor.Position)` 的返回值，
 *   即**图片框客户区**坐标：原点 = 客户区左上角、Y 轴**向下为正**（EmueraConsole.cs:2263）。
 * - `MOUSEX` 直接取该 X（FunctionMethodCreator.cs:7861）；`MOUSEY` 取的是
 *   **该 Y 再减去 ClientHeight**（EmueraConsole.cs:2264：`result.Y -= ClientHeight`；
 *   `ClientHeight => window.MainPicBox.Height`，:234）。于是鼠标在客户区内（y ∈ [0, Height-1]）时
 *   **MOUSEY 恒为负**（顶边 -Height、可视区最后一行 -1；只有 y = Height 这一格边界外才给 0）
 *   ——原版的反直觉口径，按 ADR-005 原样复刻，勿「顺手修正」。
 * - 窗口未创建（`window == null || !window.Created`）时 `GetMousePosition()` 返回
 *   `default(Point)` = **(0,0)**，连 Y 位移都不做（EmueraConsole.cs:2259-2262）。
 *   [windowCreated] 就是这一判据的替身，缺省 false → MOUSEX/MOUSEY 均读 0。
 *
 * `MOUSEB` 用的是**未位移的原始点**（FunctionMethodCreator.cs:7877），并且先用
 * `MainPicBox.ClientRectangle.Contains(point)` 判越界（:7879）：越界时**不刷新指向态**，
 * 于是返回上一次留下的 [pointing]（可能是已过期的按钮值）——原版官方行为，照抄（:7879-7883）。
 */
class MouseState {

    /** 窗口是否已创建（原版 `window != null && window.Created`，EmueraConsole.cs:2259）。 */
    var windowCreated: Boolean = false

    /** `MainPicBox.Width`（原版 `Console.ClientWidth`，EmueraConsole.cs:232）：越界判定的右边界。 */
    var clientWidth: Int = 0

    /**
     * `MainPicBox.Height`（原版 `Console.ClientHeight`，EmueraConsole.cs:234）：
     * 既是 `MOUSEY` 的位移量（:2264），也是越界判定的下边界。
     */
    var clientHeight: Int = 0

    /** 客户区原始 X（`PointToClient` 之后、位移之前；EmueraConsole.cs:2263）。 */
    var positionX: Int = 0

    /** 客户区原始 Y（同上；Y 向下为正，未减 [clientHeight]）。 */
    var positionY: Int = 0

    /** 鼠标当前指向的按钮串（原版 `Console.PointingSring`，EmueraConsole.cs:437）；null = 无指向。 */
    var pointing: ConsoleButtonPointing? = null

    /** 原版 `Console.AlwaysRefresh`（EmueraConsole.cs:184）；MOUSEB 调用期间临时置 true（:7876-7883）。 */
    var alwaysRefresh: Boolean = false

    /**
     * 命中测试钩子 = 原版 `Console.MoveMouse(point)`（EmueraConsole.cs:2268 起）的替身。
     *
     * 原版靠**像素取色 + 逐字符几何**（`escapedParts` / `ConsoleDisplayLine.Buttons`，
     * :2271-2400）算出指向的 `ConsoleButtonString`，属于渲染/控制台层，core 无从复刻。
     * 因此本字段由宿主注入（签名 = 客户区原始点 → 指向态），[refreshPointingForButton] 负责
     * 「什么条件下才调用它」这条口径。null = 宿主没有命中测试能力 → 指向态保持不变。
     */
    var hitTest: ((Int, Int) -> ConsoleButtonPointing?)? = null

    /**
     * 弃用警告记录钩子（MOUSESKIP 用；见 [com.eratw.port.core.fn.MOUSESKIP_WARNING_TEXT]）。
     *
     * 原版在**解析期**发警告（`MesSkipMethod.CheckArgumentType` →
     * `ParserMediator.Warn(..., level = 1, isError = false, isBackComp = false)`，
     * FunctionMethodCreator.cs:3635-3638）。移植侧的解析/装载通道面归邻路（save15a）所有，
     * 故本钩子在**求值期**逐次投递；接到解析期通道后应把投递点移到解析期（MouseMethods.kt 同注）。
     *
     * 生产装配（SAVE-15b）：GameSession 装配即给出——缺省收进会话的 mouseWarnings 清单，
     * 或经其 mouseWarnSink 参数转发宿主。null = 本 VM 未走生产装配（裸 MouseState /
     * 纯 fn 装置）→ 静默。
     */
    var warnRecorder: ((String) -> Unit)? = null

    /** 一次写入鼠标现场（客户区原始坐标）。 */
    fun setPointerPosition(x: Int, y: Int) {
        positionX = x
        positionY = y
    }

    /** 一次写入客户区尺寸（宿主 resize / 首帧时调用）。 */
    fun setClientSize(width: Int, height: Int) {
        clientWidth = width
        clientHeight = height
    }

    /** `MOUSEX` 读数：窗口未创建 → 0（EmueraConsole.cs:2259-2262、FunctionMethodCreator.cs:7861）。 */
    fun mouseX(): Long = if (windowCreated) positionX.toLong() else 0L

    /**
     * `MOUSEY` 读数：窗口未创建 → 0；否则**原始 Y 减 [clientHeight]**（:2264、:7857）。
     * 可视区内的鼠标必然给出 ≤ 0 的负值——原版口径，勿「顺手修正」。
     */
    fun mouseY(): Long = if (windowCreated) (positionY - clientHeight).toLong() else 0L

    /**
     * 原始点是否落在 `MainPicBox.ClientRectangle` 内。
     *
     * WinForms `Rectangle.Contains` 为**半开区间** `[0, Width) × [0, Height)`；
     * 这正是 MOUSEB 的越界判据（FunctionMethodCreator.cs:7879）。
     * 窗口未创建时原版会先 NRE（:7877 无 `Created` 防护），移植侧按「不可用」处理，
     * 见 [refreshPointingForButton]。
     */
    fun isPointerInClientArea(): Boolean =
        positionX in 0 until clientWidth && positionY in 0 until clientHeight

    /**
     * MOUSEB 的刷新动作（FunctionMethodCreator.cs:7876-7883 的逐句复刻）：
     * 先存下 `AlwaysRefresh` 并置 true，**仅当原始点在客户区内**才调 `MoveMouse`，随后还原。
     *
     * @return 是否执行了刷新（false = 越界或窗口未创建 → 指向态原样保留）
     */
    fun refreshPointingForButton(): Boolean {
        val saved = alwaysRefresh                    // :7876
        alwaysRefresh = true                         // :7878
        try {
            if (!windowCreated) return false         // 原版此处 NRE（:7877 无防护）；移植侧视为不可用
            if (!isPointerInClientArea()) return false  // :7879
            val test = hitTest ?: return false
            pointing = test(positionX, positionY)    // :7881 = MoveMouse(point)
            return true
        } finally {
            alwaysRefresh = saved                    // :7883
        }
    }

    /**
     * MOUSEB 的返回串（FunctionMethodCreator.cs:7884-7896，逐分支）。
     *
     * - `PointingSring == null` → `""`（:7884 判空后 :7896 `return ""`）
     * - 指向存在但 `IsButton == false`（纯文本段，ConsoleButtonString.cs:68-78）→ `""`（:7886-7889）
     * - `IsInteger == true` → `Input.ToString()`，即**整数输入值的十进制串**（:7890-7892；
     *   `Inputs = input.ToString()`，ConsoleButtonString.cs:84-87 那条构造同样如此）
     * - 其余（字符串按钮）→ `Inputs`（:7893-7894）
     *
     * **注意：MOUSEB 不返回按键位掩码**。原版全文没有位掩码口径：`MouseButtons` 枚举
     * （WinForms：Left=0x100000 / Right=0x200000 / Middle=0x400000）只出现在
     * `MinorShift.Emuera.Forms/MainWindow.cs:765-903` 的鼠标事件分派里，
     * 以及 `EmueraConsole.MouseDown(Point, MouseButtons)`（:1300）；`MouseButtonMethod`
     * 一个字节都没读它（:7865-7898）。
     */
    fun buttonValue(): String {
        val p = pointing ?: return ""
        if (!p.isButton) return ""
        if (p.isInteger) return p.input.toString()
        return p.inputs
    }
}

/**
 * 指向态 —— 原版 `ConsoleButtonString`（`MinorShift.Emuera.UI.Game/ConsoleButtonString.cs:13-140`）
 * 中与 MOUSEB 相关的四个字段。
 *
 * @property isButton  是否按钮（:33；纯文本段构造置 false，:68-78）
 * @property isInteger 是否整型输入按钮（:35；整型构造 :80-95，字符串构造 :97-110 / :126-140）
 * @property input     整型输入值（:37；`Inputs = input.ToString()`，:85）
 * @property inputs    字符串输入值（:39）
 */
data class ConsoleButtonPointing(
    val isButton: Boolean,
    val isInteger: Boolean = false,
    val input: Long = 0L,
    val inputs: String = "",
)

/**
 * 流式输入标志组（`FLOWINPUT` / `FLOWINPUTS` 的写入目标）。
 *
 * 对应原版 `Process` 的六个公开字段
 * （`MinorShift.Emuera.GameProc/Process.cs`：`flowinputDef` :104、`flowinput` :106、
 * `flowinputCanSkip` :108、`flowinputDefString` :110、`flowinputString` :112、
 * `flowinputForceSkip` :114；初值 :185 只显式给了 `flowinputDefString = ""`，其余零值 / false）。
 * 本移植初值逐条照抄：全 false、`flowinputDef = 0`、`flowinputDefString = ""`。
 *
 * 消费方 = `Process.setWaitInput()`（Process.cs:2052-2087，属 M4 的 WAIT/输入接线，本路只负责写）：
 * - :2055 的 `if (flowinput)` 是**总闸**：它为 false 时 `HasDefValue`/`DefIntValue`/`DefStrValue`
 *   一概不赋值 ⇒ **`FLOWINPUTS` 写的默认串在 `flowinput` 为 0 时被原版丢掉**（:2060 在 if 块内）。
 *   这是原版行为，接线时不要「顺手修好」（ADR-005）。
 * - :2059 `MouseInput = flowinput` ⇒ [com.eratw.port.core.host.InputRequest.mouseInput] 的现场来源。
 * - :2062-2069 `flowinputString` 决定 `InputType`（StrValue / IntValue）。
 * - :2071-2078 `flowinputForceSkip` 直接取默认值当结果；否则 :2079-2086
 *   `flowinputCanSkip && Console.MesSkip` 才跳过 ⇒ MOUSESKIP 的读数在这里二次参与流程。
 */
class FlowInputFlags {
    /** `Process.flowinputDef`（Process.cs:104；FLOWINPUT 第 1 参数原样存 long，FunctionMethodCreator.cs:8485）。 */
    var flowinputDef: Long = 0L

    /** `Process.flowinput`（:106；第 2 参数非 0 → true；:8488。消费方 :2055 的总闸）。 */
    var flowinput: Boolean = false

    /** `Process.flowinputCanSkip`（:108；第 3 参数；:8492。消费方 :2079，需与 MesSkip 合取）。 */
    var flowinputCanSkip: Boolean = false

    /** `Process.flowinputForceSkip`（:114；第 4 参数；:8496。消费方 :2071，优先级高于 canSkip）。 */
    var flowinputForceSkip: Boolean = false

    /** `Process.flowinputString`（:112；FLOWINPUTS 第 1 参数；:8524。消费方 :2062 决定 InputType）。 */
    var flowinputString: Boolean = false

    /** `Process.flowinputDefString`（:110；FLOWINPUTS 第 2 参数；:8527。消费方 :2060，受总闸门控）。 */
    var flowinputDefString: String = ""
}

/** aux 槽名：鼠标现场（家族前缀小写，约定见 [RuntimeAux] 的 KDoc）。 */
const val MOUSE_AUX_SLOT: String = "mouse"

/** aux 槽名：流式输入标志（与 [RuntimeAux] 的 `flowinput` 槽分开，避免类型串味）。 */
const val FLOW_INPUT_AUX_SLOT: String = "mouseflow"

/** 取（或建）本 VM 的鼠标现场。宿主与 fn 层都必须走这里，禁止各自另存一份。 */
fun mouseStateOf(aux: RuntimeAux): MouseState = aux.slot(MOUSE_AUX_SLOT) { MouseState() }

/** 取（或建）本 VM 的流式输入标志。 */
fun flowInputFlagsOf(aux: RuntimeAux): FlowInputFlags = aux.slot(FLOW_INPUT_AUX_SLOT) { FlowInputFlags() }
