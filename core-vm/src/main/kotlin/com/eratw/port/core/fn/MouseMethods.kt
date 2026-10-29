package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.host.MouseState
import com.eratw.port.core.host.flowInputFlagsOf
import com.eratw.port.core.host.mouseStateOf

/**
 * MOUSE 族 + 流式输入开关（[registerMouseMethods]）。**文件所有者：M4-I i-mouse**。
 *
 * 语义权威 = 反编译 C#（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`，
 * 下称 FMCreator），本文件每个分支都带类体行号；口径一律回类体取，不照 GUI 直觉。
 *
 * 注册表出处（FMCreator）：
 * - `MOUSEX` = `MousePosMethod` :9165；`MOUSEY` = 同类 :9166
 * - `MOUSEB` = `MouseButtonMethod` :9167
 * - `MOUSESKIP` = `MesSkipMethod(warn = true)` :9065（同类的 `MESSKIP` = warn:false :9066）
 * - `FLOWINPUT` = `FlowInputMethod` :9300；`FLOWINPUTS` = `FlowInputsMethod` :9301
 * - 名字分派：`MousePosMethod.GetIntValue` 内部按 `base.Name` 分支 :7852-7862
 *
 * 类体出处（FMCreator）：
 * - `MousePosMethod` :7841-7863（无参、返回 long、`CanRestructure = false` :7847）
 * - `MouseButtonMethod` :7865-7898（无参、返回 **string** :7869）
 * - `MesSkipMethod` :3617-3646（`argumentTypeArray = null` :3624 ⇒ 零参；
 *   `CheckArgumentType` :3629-3640 是警告/报错的**唯一发出点**；取值 :3642-3645）
 * - `FlowInputMethod` :8461-8500、`FlowInputsMethod` :8502-8531
 *
 * 已查定的四个口径分歧（详见 [MouseState] 的逐条行号）：
 * 1. **坐标原点与 Y 轴方向**：`GetMousePosition()` =
 *    `window.MainPicBox.PointToClient(Cursor.Position)`，X 原样返回；
 *    **Y 要再减去 `ClientHeight`**（`MinorShift.Emuera.GameView/EmueraConsole.cs:2263-2264`，
 *    `ClientHeight => window.MainPicBox.Height` :234）。原点 = 客户区左上角、Y 向下为正，
 *    位移后可视区底边 = -1、顶边 = -ClientHeight ⇒ **`MOUSEY` 在客户区内恒为负**。这是原版行为，按 ADR-005 原样复刻。
 * 2. **未初始化返回**：`window == null || !window.Created` → `default(Point)` = **(0,0)**，
 *    且此时**不做 Y 位移**（EmueraConsole.cs:2259-2262）⇒ MOUSEX/MOUSEY 双 0，不抛错。
 *    （本路与 A6 的 `InputMethods.kt` 差别就在这里：那边 `inputState == null` 抛 CodeEE，
 *    本路按原版「无窗口 = (0,0)」返回 0。见交付报告的「需组长接线」。）
 * 3. **越界返回**：`MOUSEB` 先做 `MainPicBox.ClientRectangle.Contains(point)`（:7879），
 *    **越界时不调 `MoveMouse`**（:7881 的 if 不成立）⇒ 指向态保持上一次的值，
 *    于是越界后 MOUSEB 可以返回**过期的按钮串**（官方行为，不修正）。
 *    另外 `MouseButtonMethod` 全程不检查 `Created`，无窗口时会 NRE（:7877 无防护）；
 *    移植侧不复刻崩溃，按「视为越界、不刷新」处理（[MouseState.refreshPointingForButton]）。
 * 4. **没有按键位掩码**：`MOUSEB` 返回的是**指向按钮的输入串**（long → 十进制串，或字符串），
 *    不是左/右/中键的位掩码。`MouseButtons` 枚举（WinForms：Left=0x100000、Right=0x200000、
 *    Middle=0x400000）只服务于 `MinorShift.Emuera.Forms/MainWindow.cs:765-903` 的事件分派与
 *    `EmueraConsole.MouseDown(Point, MouseButtons)`（:1300），`MouseButtonMethod` 没有读它（:7865-7898）。
 *
 * `MOUSESKIP` 的 warn 文案原文（解析期，非求值期）：
 * - 模板：`Lang.Error.FuncDeprecated` = `関数{0}()は推奨されません。代わりに関数{1}()を使用してください`
 *   （`MinorShift.Emuera.Runtime.Utils.EvilMask/Lang.cs:3850`）
 * - 实参：`{0}` = 调用名（这里是 `MOUSESKIP`）、`{1}` = **固定字面量 "MESSKIP"**
 *   （FMCreator :3637 写的是 `"MESSKIP"` 而非参数名），故成品串见 [MOUSESKIP_WARNING_TEXT]
 * - 等级：`ParserMediator.Warn(..., level = 1, isError = false, isBackComp = false)`（:3637）
 *   ⇒ 警告、不中断执行；返回值仍是 `Console.MesSkip` 的 1/0（:3642-3645）
 * - 顺序：`CheckArgumentType` **先**查参数个数（:3631-3634，多于 0 个 → `{0}関数: 引数が多すぎます`，
 *   Lang.cs:3790）并**直接 return**，警告只在零参时发出 ⇒ 本移植把个数检查交给
 *   [BuiltinMethod.checkArity]（先抛），警告在求值体里发，顺序一致。
 * - TODO(verify)：原版警告发生在**解析期**（每个 `MOUSESKIP()` 出现一次即警告一次，与是否执行无关）；
 *   移植侧的解析/装载通道面归邻路（save15a）所有，故经 [MouseState.warnRecorder] 在求值期投递。
 *   生产装配 = GameSession 装配链（SAVE-15b）：缺省收进会话的 mouseWarnings 清单，
 *   未装配的裸 VM 仍是 null = 静默。接到解析期通道后应把投递点搬走，否则
 *   「写了但从未执行」的 MOUSESKIP 不产生警告（与基准的加载期警告清单会有差异）。
 */

/** `Lang.Error.FuncDeprecated` 模板原文（Lang.cs:3850；`{0}` = 被弃用的函数名，`{1}` = 推荐替代品名）。 */
const val FUNC_DEPRECATED_TEMPLATE: String = "関数{0}()は推奨されません。代わりに関数{1}()を使用してください"

/**
 * MOUSESKIP 的警告成品串（FMCreator :3637：`string.Format(FuncDeprecated.Text, name, "MESSKIP")`，
 * 第二个实参是**硬编码字面量**，不是查表得到的名字）。
 */
const val MOUSESKIP_WARNING_TEXT: String = "関数MOUSESKIP()は推奨されません。代わりに関数MESSKIP()を使用してください"

/** 按原版格式生成弃用警告串（供解析期通道接线后复用；模板与实参口径见 [MOUSESKIP_WARNING_TEXT]）。 */
fun funcDeprecatedWarning(name: String, replacement: String = "MESSKIP"): String =
    FUNC_DEPRECATED_TEMPLATE.replace("{0}", name).replace("{1}", replacement)

/** MOUSE 族的宿主可写现场（aux 槽 [com.eratw.port.core.host.MOUSE_AUX_SLOT]）。 */
fun mouseState(c: BuiltinContext): MouseState = mouseStateOf(c.aux)

/**
 * `MOUSEX()` / `MOUSEY()` 的取值（`MousePosMethod.GetIntValue`，FMCreator :7850-7862）。
 *
 * 原版按 `base.Name` 分派：`MOUSEX` → `.X`（:7853、:7861），`MOUSEY` → `.Y`（:7855-7857），
 * 其余名字 → `throw new ExeEE("異常な名前")`（:7859）。注册表只把这两个名字指向本类
 * （:9165-9166），故第三条分支在正常装载下不可达；移植侧只保留同样的守卫
 * （本仓 `core/err` 只有 [CodeEE]，故用之，不新增异常类）。
 */
fun mousePositionValue(name: String, state: MouseState): Long = when (name) {
    "MOUSEX" -> state.mouseX()   // :7861
    "MOUSEY" -> state.mouseY()   // :7857
    else -> throw CodeEE("異常な名前：$name")  // :7859（原版 ExeEE）
}

/**
 * `MOUSEB()` 的取值（`MouseButtonMethod.GetStrValue`，FMCreator :7874-7897）。
 * 刷新判据与四条返回分支的原文口径见 [MouseState.refreshPointingForButton] 与
 * [MouseState.buttonValue]。
 */
fun mouseButtonValue(state: MouseState): String {
    state.refreshPointingForButton()   // :7876-7883
    return state.buttonValue()         // :7884-7896
}

/** MOUSE 族注册入口（组长在 [Builtins.buildRegistry] / `core/fn/HostMethods.kt` 接线）。 */
fun registerMouseMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // ---- MOUSEX() / MOUSEY() ---- MousePosMethod :7841-7863；注册 :9165-9166
    // 零参（argumentTypeArray = Array.Empty() :7846）、返回 long（:7845）、CanRestructure=false（:7847）。
    r.register(BuiltinMethod("MOUSEX", I, 0, 0) { c, _ ->
        Value.IntV(mousePositionValue("MOUSEX", mouseState(c)))
    })
    r.register(BuiltinMethod("MOUSEY", I, 0, 0) { c, _ ->
        Value.IntV(mousePositionValue("MOUSEY", mouseState(c)))
    })

    // ---- MOUSEB() ---- MouseButtonMethod :7865-7898；注册 :9167（返回**字符串**，:7869）
    r.register(BuiltinMethod("MOUSEB", S, 0, 0) { c, _ ->
        Value.StrV(mouseButtonValue(mouseState(c)))
    })

    // ---- MOUSESKIP() ---- MesSkipMethod(warn = true) :3617-3646；注册 :9065
    // 返回值 = GlobalStatic.Console.MesSkip ? 1 : 0（:3644），与 MESSKIP 完全同一实现（:9066），
    // 唯一差别是本名多一条解析期弃用警告（:3635-3638）。
    // 跳过标志的数据源 = 每 VM 的 aux 槽 [SKIP_STATE_AUX_SLOT]（原版 Console.MesSkip 由
    // SKIPLOG 语句直写 Process.cs:1832；本移植的写入口是 sem 层 `ExecContext.mesSkip`，
    // 它就是这口槽的视图）。槽缺失（纯 fn 装置，没有语句层）时抛 CodeEE 而不默认 false——
    // 理由见 InputMethods.kt 的类 KDoc，静默 0 会让 SKIPLOG 进行中的查询歪曲基准。
    r.register(BuiltinMethod("MOUSESKIP", I, 0, 0) { c, _ ->
        val st = mouseState(c)
        st.warnRecorder?.invoke(MOUSESKIP_WARNING_TEXT)   // :3635-3638（本移植移到求值期，见文件 KDoc 的 TODO(verify)）
        Value.IntV(if (mesSkipOf(c, "MOUSESKIP")) 1L else 0L)  // :3644
    })

    // ---- FLOWINPUT(def[, flowinput[, canSkip[, forceSkip]]]) ---- :8461-8500；注册 :9300
    // ArgTypeList{Int,Int,Int,Int} 且 OmitStart = 1（:8466-8479）⇒ 实参 1..4 个，第 1 个必有。
    // 恒返回 0L（:8498）——原版就是常量 0，不是「失败返 0」。
    r.register(BuiltinMethod("FLOWINPUT", I, 1, 4) { c, a ->
        val f = flowInputFlagsOf(c.aux)
        f.flowinputDef = iv(a[0]!!)                                        // :8485（无 != 0 转换，原样存 long）
        if (a.size > 1 && a[1] != null) f.flowinput = iv(a[1]!!) != 0L      // :8486-8489
        if (a.size > 2 && a[2] != null) f.flowinputCanSkip = iv(a[2]!!) != 0L // :8490-8493
        if (a.size > 3 && a[3] != null) f.flowinputForceSkip = iv(a[3]!!) != 0L // :8494-8497
        Value.IntV(0L)                                                     // :8498
    })

    // ---- FLOWINPUTS(useString[, defString]) ---- FlowInputsMethod :8502-8531；注册 :9301
    // ArgTypeList{Int,String} 且 OmitStart = 1（:8507-8516）⇒ 1..2 个；恒返回 0L（:8529）。
    r.register(BuiltinMethod("FLOWINPUTS", I, 1, 2) { c, a ->
        val f = flowInputFlagsOf(c.aux)
        f.flowinputString = iv(a[0]!!) != 0L                               // :8524
        if (a.size > 1 && a[1] != null) f.flowinputDefString = sv(a[1]!!)   // :8525-8528
        Value.IntV(0L)                                                     // :8529
    })
}

/**
 * `Console.MesSkip` 的读取（原版是控制台公开字段，EmueraConsole.cs:148；`SKIPLOG` 语句直写
 * Process.cs:1832）。数据源 = 本 VM 的 aux 跳过状态槽（[skipStateSlotOf]），与 MESSKIP 同判据：
 * 槽缺失 → CodeEE，保证两条实现不会一个抛错一个静默 0。
 */
private fun mesSkipOf(c: BuiltinContext, name: String): Boolean = skipStateSlotOf(c, name).mesSkip
