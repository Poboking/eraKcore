package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.RuntimeAux

/**
 * 输入 / 跳过状态家族（[registerInputMethods]）。**文件所有者：A6**（M3-B 第二波）。
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:307`（`ISSKIP=0 MESSKIP=0`，
 * 调用序列 `fixtures/golden/GOLDEN_02_PRINT.ERB:266`）与 `fixtures/golden/GOLDEN_07_INPUT_MANUAL.ERB:62-64`
 * （ISACTIVE/GETKEY/GETKEYTRIGGERED，半人工节，不在 root 基准内）。
 *
 * 权威实现分布（`reference/emuera-cs`）：
 * - 函数壳：`MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - 键状态：`MinorShift.Emuera.Runtime.Utils/WinInput.cs:7-9`（user32 `GetKeyState`，Win32 虚拟键码）
 * - 窗口激活：`MinorShift.Emuera.GameView/EmueraConsole.cs:258-268`（IsActive）
 * - 跳过标志：`Process.SkipPrint`（IsSkipMethod :3611-3614）、`Console.MesSkip`（MesSkipMethod :3642-3645）
 *
 * 落地函数与证据行号（均在 FunctionMethodCreator.cs）：
 * - GETKEY / GETKEYTRIGGERED  GetKeyStateMethod :7805-7839；注册 :9163-9164；
 *   沿检测表 `keytoggle = new short[256]` :9035-9039
 * - MOUSEX / MOUSEY          MousePosMethod :7841-7863；注册 :9165-9166
 * - MOUSEB                   MouseButtonMethod :7865-7898；注册 :9167
 * - ISACTIVE                 IsActiveMethod :7900-7913；注册 :9168
 * - ISSKIP                   IsSkipMethod :3602-3615；注册 :9064
 * - MOUSESKIP / MESSKIP      MesSkipMethod :3617-3646；注册 :9065-9066
 *   （MOUSESKIP 原版在解析期发“已废弃”警告 :3635-3638；本移植无解析期警告通道，照抄返回值语义）
 * - FLOWINPUT                FlowInputMethod :8461-8500；注册 :9300
 * - FLOWINPUTS               FlowInputsMethod :8502-8531；注册 :9301
 *
 * 无宿主能力时的行为（逐条核对原版后决定，不静默假装）：
 * - GETKEY / GETKEYTRIGGERED / MOUSEX / MOUSEY / MOUSEB / ISACTIVE 读的是**真实输入后端**
 *   （键盘轮询、鼠标坐标、窗口激活），core 层无从伪造 → [BuiltinHost.inputState] 为 null 时
 *   抛 CodeEE（需宿主接线键盘/鼠标/窗口状态）。
 * - ISSKIP / MOUSESKIP / MESSKIP 读的是**进程级跳过标志**（原版 `Process.SkipPrint`
 *   Process.cs:160-171 / `Console.MesSkip` EmueraConsole.cs:148）：真身是每 VM 的 aux 槽
 *   [SKIP_STATE_AUX_SLOT]（值类型 [SkipStateSlot]），sem 层 `ExecContext.skipPrint` /
 *   `mesSkip` 两个属性就是这口槽的视图（SKIPDISP 直写 Process.cs:1589、SKIPLOG 直写
 *   Process.cs:1832）⇒ 语句写过的标志，函数立刻读得到，中间没有第二份拷贝。
 *   本条**不读** [BuiltinHost.skipState]：生产宿主 `ConsoleBuiltinHost` 从未接线那条缝，
 *   按宿主快照读会在真机上抛 CodeEE（HOST-01）。槽由 `ExecContext` 构造时建立，
 *   槽缺失 = 该求值语境没有语句层（纯 fn 装置）→ CodeEE，不静默答 0 ——
 *   SKIPDISP 进行中查询 ISSKIP 必须得 1（FunctionMethodCreator.cs:3613），静默 0 会歪曲基准。
 * - FLOWINPUT / FLOWINPUTS 是纯标志写入（消费方在输入循环，Process.cs:2055-2085，属 M4），
 *   返回值恒 0（:8498/:8529 本来就是常量）→ 落进每 VM 附属状态（[RuntimeAux] 的
 *   `flowinput` 槽），返回 0。
 */

/**
 * 跳过状态快照（[BuiltinHost.skipState] 的数据形态）。
 *
 * 对应原版两处标志：
 * - [skipPrint] = `Process.SkipPrint`（SKIPDISP 语句直写；IsSkipMethod 读它，
 *   FunctionMethodCreator.cs:3613）
 * - [mesSkip] = `Console.MesSkip`（SKIPLOG 语句直写；MesSkipMethod 读它，:3644）
 *
 * 自 HOST-01 起 ISSKIP / MESSKIP / MOUSESKIP **不再向宿主要快照**（生产宿主没接线，
 * 见类 KDoc），三个函数读的是每 VM 的 [SkipStateSlot]；本类型保留为宿主侧数据形态，
 * 外部形状不变。
 */
data class SkipState(val skipPrint: Boolean, val mesSkip: Boolean)

/**
 * 跳过状态槽的键（[RuntimeAux] 的槽名）：每 VM 一份的 [SkipStateSlot]。
 *
 * 写入方只有 sem 层的 `ExecContext`（`skipPrint` / `mesSkip` 属性的 setter，以及构造时的
 * 落槽初始化）；读取方只有本文件的 ISSKIP / MESSKIP 与 `core/fn/MouseMethods.kt` 的 MOUSESKIP。
 */
const val SKIP_STATE_AUX_SLOT: String = "skipState"

/**
 * 每 VM 的跳过状态（aux 槽 [SKIP_STATE_AUX_SLOT] 的值类型）。主构造初值 = 原版新进程出厂态
 * （`Process.skipPrint` 与 `Console.MesSkip` 的字段初值都是 false，Process.cs:160、
 * EmueraConsole.cs:148）。
 *
 * 两字段的读写点与原版字段一一对应：
 * - [skipPrint]：SKIPDISP / NOSKIP / ENDNOSKIP 直写（Process.cs:1589、:1597-1601、:1609-1612），
 *   ISSKIP 读（FunctionMethodCreator.cs:3613），PRINT 系语句据此抑制输出。
 * - [mesSkip]：SKIPLOG 直写（Process.cs:1832），MESSKIP / MOUSESKIP 读（:3644），
 *   CanSkip 输入在跳过中直接取默认值；输入消费路径另写（EmueraConsole.cs:1373、:1456）。
 */
class SkipStateSlot {
    var skipPrint: Boolean = false
    var mesSkip: Boolean = false
}

/**
 * 取/建本 VM 的跳过状态槽（sem 层入口）：只在缺失时新建，不覆盖已有值。
 * 初值 false/false 由 `ExecContext` 构造时显式写入（它的 init 块），本函数只管
 * 「这口槽在不在」——aux 被 `clear()` 重建后，下一次访问就靠它把槽补回来。
 */
internal fun skipStateSlot(aux: RuntimeAux): SkipStateSlot =
    aux.slot(SKIP_STATE_AUX_SLOT) { SkipStateSlot() }

/**
 * 输入后端快照（[BuiltinHost.inputState] 的数据形态）。全部为轮询式读取，
 * 对应原版每次函数调用时向 Win32/控件现场问的状态。
 */
interface InputState {
    /** 窗口是否激活。原版 `EmueraConsole.IsActive`（EmueraConsole.cs:258-268）= window.Created && ActiveForm != null。 */
    val isActive: Boolean

    /** 鼠标 X（客户区坐标）。原版 `Console.GetMousePosition().X`（FunctionMethodCreator.cs:7861）。 */
    val mouseX: Int

    /** 鼠标 Y（客户区坐标）。原版 `Console.GetMousePosition().Y`（:7857）。 */
    val mouseY: Int

    /**
     * Win32 `GetKeyState(nVirtKey)` 的 short 返回值（WinInput.cs:8）。
     *
     * **键码口径照抄原版，不做二次映射**：游戏侧传进来的就是 Win32 虚拟键码
     * （= `System.Windows.Forms.Keys` 的数值，如 A=0x41、Enter=0x0D、Esc=0x1B、
     * 方向键 0x25-0x28），GetKeyStateMethod 直接把它交给 user32（:7820-7825）。
     *
     * 返回值按 short 符号扩展给出：**bit15 置位（按下）时整体为负**（对应 C# 的
     * `keyState < 0`，:7837）；bit0 = 切换位（原版 GETKEYTRIGGERED 的沿检测用它，
     * :7826-7833）。GETKEY/GETKEYTRIGGERED 的有效域是 0..255（:7821-7824）。
     */
    fun keyState(virtualKey: Int): Int

    /**
     * MOUSEB：鼠标正指向的按钮输入串；null = 无指向。
     *
     * 原版（MouseButtonMethod :7884-7896）：`Console.PointingSring == null` → `""`；
     * 否则按钮的输入串（整型按钮给数字串）。无输入后端时宿主可直接返回 null（等价 ""
     * 只在 [BuiltinHost.inputState] 整体接线后可达——整个 inputState 为 null 时函数先抛
     * CodeEE，不会走到这里）。
     */
    val pointingInput: String?
}

/**
 * 无输入设备的空闲态：窗口未激活、无键按下、鼠标 (0,0)、无指向按钮。
 *
 * 口径 = 原版在窗口未创建/未激活时的读数（`GETKEY` 先判 `Console.IsActive`，
 * FunctionMethodCreator.cs:7816-7819；鼠标坐标取 (0,0)，EmueraConsole.cs:2259-2262）。
 * headless 与控制台宿主用它在 `AWAIT` 轮询里正常走时间退出分支，而不是抛「未接线」。
 */
object IdleInputState : InputState {
    override val isActive: Boolean get() = false
    override val mouseX: Int get() = 0
    override val mouseY: Int get() = 0
    override fun keyState(virtualKey: Int): Int = 0
    override val pointingInput: String? get() = null
}

internal fun registerInputMethods(r: BuiltinRegistry) {
    val I = OperandType.INT

    // ---- GETKEY(keyCode) / GETKEYTRIGGERED(keyCode) ---- :7805-7839
    // 顺序照抄：先窗口激活（未激活 → 0，:7816-7819），再键码域 0..255（越界 → 0，:7821-7824）。
    // GETKEY：当前按下 → 1。GETKEYTRIGGERED：按下且 bit0 切换位自本 VM 上次查询以来变过 → 1
    // （:7826-7833）。keytoggle 表是原版静态字段（:9035-9039）——本移植放每 VM 附属状态
    // （aux 槽 "keytoggle"），保证多 VM/测试并行不串味（CONTRACT-M3-B §2）。
    r.register(BuiltinMethod("GETKEY", I, 1, 1) { c, a ->
        val vk = iv(a[0]!!)
        Value.IntV(keyStateOf(c, "GETKEY", vk, triggered = false))
    })
    r.register(BuiltinMethod("GETKEYTRIGGERED", I, 1, 1) { c, a ->
        val vk = iv(a[0]!!)
        Value.IntV(keyStateOf(c, "GETKEYTRIGGERED", vk, triggered = true))
    })

    // ---- MOUSEX/MOUSEY/MOUSEB/MOUSESKIP/FLOWINPUT/FLOWINPUTS 已迁出 ----
    // 这六个名字的真相在 `core/fn/MouseMethods.kt`（M4-I i-mouse，回 C# 类体逐条对拍后重写）：
    // MOUSEY 要减 ClientHeight（EmueraConsole.cs:2264）、无窗口返回 (0,0)（:2259-2262）、
    // MOUSEB 越界不调 MoveMouse 因而返回过期指向串（FunctionMethodCreator.cs:7876-7896）。
    // 同族注册若留在这里会与 MouseMethods 撞名（BuiltinRegistry 真实现重名即 error）。

    // ---- ISACTIVE() ---- :7900-7913（窗口激活 → 1，否则 0）
    r.register(BuiltinMethod("ISACTIVE", I, 0, 0) { c, _ ->
        val st = c.host.inputState
            ?: throw CodeEE("函数 ISACTIVE 需要宿主接线输入状态（inputState == null）")
        Value.IntV(if (st.isActive) 1L else 0L)
    })

    // ---- ISSKIP() ---- :3602-3615（Process.SkipPrint → 1/0；SKIPDISP 直写 Process.cs:1589）
    r.register(BuiltinMethod("ISSKIP", I, 0, 0) { c, _ ->
        Value.IntV(if (skipStateSlotOf(c, "ISSKIP").skipPrint) 1L else 0L)   // :3613
    })

    // ---- MESSKIP() ---- :3617-3646（Console.MesSkip → 1/0；同类的 MOUSESKIP 在 MouseMethods.kt，
    // 差别只在那条弃用警告，数据源同为 aux 跳过状态槽 [SKIP_STATE_AUX_SLOT]）
    r.register(BuiltinMethod("MESSKIP", I, 0, 0) { c, _ ->
        Value.IntV(if (skipStateSlotOf(c, "MESSKIP").mesSkip) 1L else 0L)     // :3644
    })
    // MOUSESKIP() 与 FLOWINPUT()/FLOWINPUTS() 已迁往 `core/fn/MouseMethods.kt`（M4-I i-mouse）：
    // 原版 MOUSESKIP = MesSkipMethod(warn: true)（FunctionMethodCreator.cs:9065，警告文案 :3635-3638）；
    // FLOWINPUT(S) 恒返回 0（:8498/:8529），四个标志位写 Process 字段（Process.cs:104-114）。
}

// ---------------------------------------------------------------------------
// 内部辅助
// ---------------------------------------------------------------------------

/**
 * GETKEY / GETKEYTRIGGERED 共用逻辑（FunctionMethodCreator.cs:7814-7838）。
 *
 * keytoggle 表（256 项）按 C# `static short[]`（:9035-9039）的语义用 IntArray 复刻：
 * 每项存上次查询的 `(keyState & 1) + 1`（1 或 2），本 VM 生命周期内有效。
 */
private fun keyStateOf(c: BuiltinContext, fn: String, vk: Long, triggered: Boolean): Long {
    val st = c.host.inputState
        ?: throw CodeEE("函数 $fn 需要宿主接线输入状态（inputState == null）")
    if (!st.isActive) return 0L                     // :7816-7819
    if (vk < 0 || vk > 255) return 0L               // :7821-7824
    val keyState = st.keyState(vk.toInt())          // :7825
    val toggle = c.store.aux.slot("keytoggle") { IntArray(256) }
    val prev = toggle[vk.toInt()]                   // :7826
    toggle[vk.toInt()] = (keyState and 1) + 1       // :7827
    return if (!triggered) {
        if (keyState < 0) 1L else 0L                // :7837（GETKEY：按下即 1）
    } else {
        if (keyState < 0 && prev != toggle[vk.toInt()]) 1L else 0L  // :7833（GETKEYTRIGGERED）
    }
}

/**
 * 取本 VM 的跳过状态槽（fn 层唯一入口；[registerInputMethods] 与 MouseMethods.kt 共用）。
 *
 * 槽由 sem 层 `ExecContext` 构造时建立（[skipStateSlot]），此后语句写的每个标志都在槽里。
 * 槽缺失 = 这个求值语境没有语句层（纯 fn 装置 / 宿主自检）→ CodeEE：
 * 原版跳过标志恒存在（`Process.SkipPrint` / `Console.MesSkip` 是字段），答 0 就是伪造
 * 「没在跳过」（SKIPDISP 进行中 ISSKIP 必须为 1，FunctionMethodCreator.cs:3613）。
 * 文案里的 `skipState == null` 指本槽（`RuntimeAux` 的 `skipState` 槽）缺失。
 */
internal fun skipStateSlotOf(c: BuiltinContext, fn: String): SkipStateSlot =
    c.aux.peek<SkipStateSlot>(SKIP_STATE_AUX_SLOT)
        ?: throw CodeEE("函数 $fn 需要宿主接线跳过状态（skipState == null）")
