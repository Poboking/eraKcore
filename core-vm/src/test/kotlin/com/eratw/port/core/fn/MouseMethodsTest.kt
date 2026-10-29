package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.MouseState
import com.eratw.port.core.host.flowInputFlagsOf
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOUSE 族 + FLOWINPUT / FLOWINPUTS 的单测（CONTRACT-M4-I §2 i-mouse）。
 *
 * 断言的是**具体读数**：坐标值、Y 位移后的负值、按钮输入串的四种分支形态、
 * 越界时「不刷新、返回过期指向」的读数、MOUSESKIP 的 1/0 与警告原文、
 * FLOWINPUT(S) 写进 aux 槽后每个字段的值与恒 0 返回值。
 *
 * 证据行号一律指 `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * （FMCreator）与 `MinorShift.Emuera.GameView/EmueraConsole.cs`。
 *
 * 本路不注册进全局 [Builtins.registry]（`core/fn/Builtins.kt` 与 `core/fn/HostMethods.kt` 全波禁改），
 * 故测试用**局部 [BuiltinRegistry]** 直接调用 [registerMouseMethods] 的产物；
 * 组长接线后同一批读数应由 `Builtins.registry` 给出（分派差异写进交付报告）。
 *
 * **语料依赖：0 个用例**（本路全部读数由注入的假现场给出，不需要 `ERATW_CORPUS` / `ERATW_GAMEDIR`）。
 */
private class MouseCtx(
    override val store: VariableStore = VariableStore(),
    override val host: BuiltinHost = BuiltinHost.DEFAULT,
) : BuiltinContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MouseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 局部注册表 + 一个 VM（一份 [VariableStore]，因此一份 aux 鼠标现场）。 */
private class MouseHarness(val host: BuiltinHost = BuiltinHost.DEFAULT) {
    val store = VariableStore()
    val ctx: MouseCtx = MouseCtx(store, host).also { it.variables = store }
    val mouse: MouseState = mouseStateOf(store.aux)
    private val registry = BuiltinRegistry().also { registerMouseMethods(it) }

    fun call(name: String, vararg args: Value?): Value {
        val m = registry[name] ?: error("本路未注册函数 $name")
        m.checkArity(args.size)
        val eval = m.eval ?: error("函数 $name 需要引用参数入口")
        return eval.invoke(ctx, args.toList())
    }

    fun i(name: String, vararg args: Value?): Long = (call(name, *args) as Value.IntV).v
    fun s(name: String): String = (call(name) as Value.StrV).v
    fun flags() = flowInputFlagsOf(store.aux)
    fun method(name: String): BuiltinMethod = registry[name] ?: error("本路未注册函数 $name")
}

/** 原版窗口口径的假现场：MainPicBox 客户区 760 x 461（ConfigData.cs:56/57/67 的默认窗口高）。 */
private const val CLIENT_W = 760
private const val CLIENT_H = 461

private class RecordingHitTest(var result: ConsoleButtonPointing? = null) {
    var calls = 0
    var lastX = Int.MIN_VALUE
    var lastY = Int.MIN_VALUE

    /** MOUSEB 调用期间看到的 AlwaysRefresh（原版 :7876-7883 的置位窗口）。 */
    var refreshFlagSeenDuringCall: Boolean? = null

    fun install(mouse: MouseState) {
        mouse.hitTest = { x, y ->
            calls++
            lastX = x
            lastY = y
            refreshFlagSeenDuringCall = mouse.alwaysRefresh
            result
        }
    }
}

private fun intButton(v: Long) = ConsoleButtonPointing(isButton = true, isInteger = true, input = v, inputs = v.toString())
private fun strButton(v: String) = ConsoleButtonPointing(isButton = true, isInteger = false, input = 0L, inputs = v)

class MouseMethodsTest {

    // ===== MOUSEX / MOUSEY：MousePosMethod :7841-7863 + EmueraConsole.cs:2257-2266 =====

    @Test
    fun `窗口未创建时 MOUSEX 与 MOUSEY 都是 0`() {
        // EmueraConsole.cs:2259-2262 —— window 未 Created → default(Point)，且不做 Y 位移。
        val h = MouseHarness()
        h.mouse.clientHeight = CLIENT_H
        h.mouse.setPointerPosition(100, 300)   // 即便有残留坐标也不参与计算
        h.mouse.windowCreated = false
        assertEquals(0L, h.i("MOUSEX"))
        assertEquals(0L, h.i("MOUSEY"))         // 不是 300-461 = -161
    }

    @Test
    fun `MOUSEX 取原始 X 且 MOUSEY 减去 ClientHeight（可视区内恒非正）`() {
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(123, 45)
        assertEquals(123L, h.i("MOUSEX"))       // :7861 = GetMousePosition().X
        assertEquals(-416L, h.i("MOUSEY"))      // :7857 + EmueraConsole.cs:2264 = 45 - 461
        h.mouse.setPointerPosition(0, 0)
        assertEquals(-461L, h.i("MOUSEY"))      // 顶边 → 0 - ClientHeight
        h.mouse.setPointerPosition(759, 460)
        assertEquals(759L, h.i("MOUSEX"))
        assertEquals(-1L, h.i("MOUSEY"))        // 可视区最后一行（y = Height-1）→ -1
        h.mouse.setPointerPosition(759, 461)
        assertEquals(0L, h.i("MOUSEY"))         // 底边（客户区外一格）→ 0：MOUSEX/Y 不判界
    }

    @Test
    fun `MOUSEX 与 MOUSEY 不做越界裁剪（原版 GetMousePosition 无判界）`() {
        // EmueraConsole.cs:2257-2266 只查 Created，不查范围 ⇒ 客户区外的坐标原样给出。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(-50, 900)
        assertEquals(-50L, h.i("MOUSEX"))
        assertEquals(439L, h.i("MOUSEY"))       // 900 - 461
    }

    @Test
    fun `mousePositionValue 对未知名字复刻異常な名前分支`() {
        // :7853-7860：name 既不是 MOUSEX 也不是 MOUSEY → ExeEE("異常な名前")。
        val h = MouseHarness()
        val e = assertFailsWith<CodeEE> { mousePositionValue("MOUSEZ", h.mouse) }
        assertTrue(e.message!!.contains("異常な名前"), "实际：${e.message}")
        assertEquals(0L, mousePositionValue("MOUSEX", h.mouse))   // 未创建 → 0
    }

    // ===== MOUSEB：MouseButtonMethod :7865-7898（返回 string，:7869） =====

    @Test
    fun `MOUSEB 返回整型按钮输入的十进制串`() {
        // :7890-7892 = PointingSring.Input.ToString()（long → 无分隔符十进制，负数带负号）。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(10, 10)
        val probe = RecordingHitTest(intButton(1234L))
        probe.install(h.mouse)
        assertEquals("1234", h.s("MOUSEB"))
        probe.result = intButton(Long.MIN_VALUE)
        assertEquals("-9223372036854775808", h.s("MOUSEB"))
        probe.result = intButton(0L)
        assertEquals("0", h.s("MOUSEB"))
        assertEquals(3, probe.calls)            // 每次调用都刷新一次（在客户区内）
    }

    @Test
    fun `MOUSEB 返回字符串按钮的 Inputs 而不是数字`() {
        // :7893-7894 = PointingSring.Inputs（字符串按钮构造：IsInteger=false，ConsoleButtonString.cs:97-110）。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(20, 20)
        val probe = RecordingHitTest(ConsoleButtonPointing(isButton = true, isInteger = false, input = 7L, inputs = "選択Ａ"))
        probe.install(h.mouse)
        assertEquals("選択Ａ", h.s("MOUSEB"))
    }

    @Test
    fun `MOUSEB 指向非按钮文本段时返回空串`() {
        // :7886-7889 —— !IsButton → ""（纯文本段构造：ConsoleButtonString.cs:68-78）。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(20, 20)
        h.mouse.hitTest = { _, _ -> ConsoleButtonPointing(isButton = false, isInteger = false, inputs = "只是文本") }
        assertEquals("", h.s("MOUSEB"))
    }

    @Test
    fun `MOUSEB 无指向时返回空串`() {
        // :7884 判空 + :7896 return ""。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(20, 20)
        h.mouse.hitTest = { _, _ -> null }
        assertNull(h.mouse.pointing)
        assertEquals("", h.s("MOUSEB"))
        // 宿主没有命中测试能力（hitTest == null）同样回 ""（指向态无从计算，不伪造）。
        h.mouse.hitTest = null
        assertEquals("", h.s("MOUSEB"))
    }

    @Test
    fun `MOUSEB 越界时不刷新并返回过期的指向串`() {
        // :7879 的 ClientRectangle.Contains 判据：原始点必须落在 [0,Width)×[0,Height)。
        // 越界 ⇒ 不调 MoveMouse（:7881）⇒ 上一次留下的 PointingSring 原样返回（官方行为）。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        val probe = RecordingHitTest(intButton(11L))
        probe.install(h.mouse)
        h.mouse.setPointerPosition(10, 10)
        assertEquals("11", h.s("MOUSEB"))
        assertEquals(1, probe.calls)

        probe.result = intButton(22L)
        h.mouse.setPointerPosition(CLIENT_W, 10)     // x == Width → 半开区间外
        assertEquals("11", h.s("MOUSEB"))            // 过期值，不是 22
        h.mouse.setPointerPosition(10, CLIENT_H)     // y == Height → 外
        assertEquals("11", h.s("MOUSEB"))
        h.mouse.setPointerPosition(-1, 10)           // 负 → 外
        assertEquals("11", h.s("MOUSEB"))
        assertEquals(1, probe.calls)                 // 三次越界一次都没刷新
        assertFalse(h.mouse.isPointerInClientArea())

        h.mouse.setPointerPosition(11, 12)           // 回到区内 → 刷新，命中点用原始坐标
        assertEquals("22", h.s("MOUSEB"))
        assertEquals(2, probe.calls)
        assertEquals(11, probe.lastX)
        assertEquals(12, probe.lastY)                // 未做 -ClientHeight 位移（:7877 用的是原始点）
    }

    @Test
    fun `MOUSEB 调用期间 AlwaysRefresh 置 true 并在返回后还原`() {
        // :7876 存旧值 → :7878 置 true → :7883 还原。原版靠这个让 MoveMouse 走强制刷新分支
        // （EmueraConsole.cs:2319 的 `if (!IsInProcess || AlwaysRefresh)`）。
        val h = MouseHarness()
        h.mouse.windowCreated = true
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(10, 10)
        val probe = RecordingHitTest(strButton("A"))
        probe.install(h.mouse)

        h.mouse.alwaysRefresh = false
        assertEquals("A", h.s("MOUSEB"))
        assertEquals(true, probe.refreshFlagSeenDuringCall)
        assertFalse(h.mouse.alwaysRefresh)

        h.mouse.alwaysRefresh = true
        assertEquals("A", h.s("MOUSEB"))
        assertTrue(h.mouse.alwaysRefresh)            // 旧值 true 也如实还原，不是被清成 false
    }

    @Test
    fun `MOUSEB 在窗口未创建时不刷新且返回空串`() {
        // 原版 :7877 直接访问 Window.MainPicBox，无 Created 防护 → NRE（未验证的崩溃路径）。
        // 移植侧不伪造崩溃也不伪造指向：视为不可用，指向态保持不动 ⇒ ""（见 MouseMethods.kt 的 TODO(verify)）。
        val h = MouseHarness()
        h.mouse.setClientSize(CLIENT_W, CLIENT_H)
        h.mouse.setPointerPosition(10, 10)
        val probe = RecordingHitTest(strButton("B"))
        probe.install(h.mouse)
        assertEquals("", h.s("MOUSEB"))
        assertEquals(0, probe.calls)
        assertNull(h.mouse.pointing)
    }

    // ===== MOUSESKIP：MesSkipMethod(warn = true) :3617-3646；注册 :9065 =====

    @Test
    fun `MOUSESKIP 返回 Console 的 MesSkip 值`() {
        // :3644 = GlobalStatic.Console.MesSkip ? 1 : 0。
        val on = MouseHarness().also { skipStateSlot(it.store.aux).mesSkip = true }
        assertEquals(1L, on.i("MOUSESKIP"))
        val off = MouseHarness().also { skipStateSlot(it.store.aux).mesSkip = false }
        assertEquals(0L, off.i("MOUSESKIP"))
        // 本路局部注册表只拥有契约点名的六个名字；MESSKIP / ISSKIP / GETKEY / GETKEYTRIGGERED /
        // ISACTIVE 仍归 A6 的 InputMethods.kt（:9066 与 :9064 等）。组长接线时只能把
        // MOUSESKIP 这一条换过来，整族替换会让 MESSKIP 从注册表消失。
        assertFailsWith<IllegalStateException> { MouseHarness().method("MESSKIP") }
    }

    @Test
    fun `MOUSESKIP 的警告串是 Lang 的 FuncDeprecated 成品原文`() {
        // Lang.cs:3850 模板 + FMCreator:3637 的第二个实参是硬编码 "MESSKIP"。
        assertEquals(
            "関数{0}()は推奨されません。代わりに関数{1}()を使用してください",
            FUNC_DEPRECATED_TEMPLATE,
        )
        assertEquals("関数MOUSESKIP()は推奨されません。代わりに関数MESSKIP()を使用してください", MOUSESKIP_WARNING_TEXT)
        assertEquals(MOUSESKIP_WARNING_TEXT, funcDeprecatedWarning("MOUSESKIP"))
        val warns = ArrayList<String>()
        val h = MouseHarness().also { skipStateSlot(it.store.aux) }
        h.mouse.warnRecorder = { warns += it }
        h.i("MOUSESKIP")
        h.i("MOUSESKIP")
        assertEquals(listOf(MOUSESKIP_WARNING_TEXT, MOUSESKIP_WARNING_TEXT), warns)
        assertEquals(2, warns.size)
    }

    @Test
    fun `MOUSESKIP 带实参时先报参数个数错且不写警告`() {
        // :3631-3634 的个数检查在 :3635 的警告之前并直接 return（原文案 `{0}関数: 引数が多すぎます`，
        // Lang.cs:3790）；移植侧个数检查由 checkArity 承担 ⇒ 抛出时警告不应已发出。
        val warns = ArrayList<String>()
        val h = MouseHarness().also { skipStateSlot(it.store.aux).mesSkip = true }
        h.mouse.warnRecorder = { warns += it }
        val e = assertFailsWith<CodeEE> { h.call("MOUSESKIP", Value.IntV(1L)) }
        assertTrue(e.message!!.contains("MOUSESKIP"), "实际：${e.message}")
        assertTrue(e.message!!.contains("参数个数"), "实际：${e.message}")
        assertEquals(emptyList<String>(), warns)
    }

    @Test
    fun `MOUSESKIP 在宿主未接跳过状态时抛 CodeEE 且警告已发出`() {
        // 警告属解析期动作（与能否取到值无关），取值需要 Console.MesSkip 的宿主桥接；
        // 未接线不允许静默答 0（理由同 InputMethods.kt 的类 KDoc）。
        val warns = ArrayList<String>()
        val h = MouseHarness(BuiltinHost.DEFAULT)
        h.mouse.warnRecorder = { warns += it }
        val e = assertFailsWith<CodeEE> { h.call("MOUSESKIP") }
        assertTrue(e.message!!.contains("跳过状态"), "实际：${e.message}")
        assertEquals(listOf(MOUSESKIP_WARNING_TEXT), warns)
    }

    @Test
    fun `warnRecorder 缺省为 null 未装配的 VM 静默`() {
        // SAVE-15b 判据④的缺省态：未经 GameSession 装配（裸 MouseState / 纯 fn 装置）不投递。
        // 在位态的投递读数见上一用例。
        assertNull(MouseState().warnRecorder)
        val h = MouseHarness()
        assertNull(h.mouse.warnRecorder)
        // 求值仍走原路径（此处因无跳过状态槽抛 CodeEE），没有 recorder 也不额外报错。
        assertFailsWith<CodeEE> { h.i("MOUSESKIP") }
    }

    // ===== FLOWINPUT：FlowInputMethod :8461-8500；注册 :9300 =====

    @Test
    fun `FLOWINPUT 四参全写并按 非0 转布尔`() {
        // :8485-8497；返回值恒 0（:8498），不是「失败才 0」。
        val h = MouseHarness()
        assertEquals(0L, h.i("FLOWINPUT", Value.IntV(42L), Value.IntV(-1L), Value.IntV(0L), Value.IntV(5L)))
        val f = h.flags()
        assertEquals(42L, f.flowinputDef)
        assertTrue(f.flowinput)              // 第 2 参非 0 → true（:8488）
        assertFalse(f.flowinputCanSkip)      // 第 3 参 0 → false（:8492）
        assertTrue(f.flowinputForceSkip)     // 第 4 参非 0 → true（:8496）
    }

    @Test
    fun `FLOWINPUT 省略的实参保持原值不被清零`() {
        // C# 用 arguments.Count 逐条 if（:8486/:8490/:8494）⇒ 只写第 1 参时其余四个字段不动。
        val h = MouseHarness()
        h.call("FLOWINPUT", Value.IntV(42L), Value.IntV(1L), Value.IntV(0L), Value.IntV(1L))
        assertEquals(0L, h.i("FLOWINPUT", Value.IntV(7L)))
        val f = h.flags()
        assertEquals(7L, f.flowinputDef)     // 只有 def 被改写
        assertTrue(f.flowinput)              // 保持上一句写入的 true
        assertFalse(f.flowinputCanSkip)
        assertTrue(f.flowinputForceSkip)
    }

    @Test
    fun `FLOWINPUT 的第 1 参原样存 long（不做非 0 转换）`() {
        // :8485 = flowinputDef = arguments[0].GetIntValue(...)（直接赋 long，无 != 0）。
        val h = MouseHarness()
        h.i("FLOWINPUT", Value.IntV(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, h.flags().flowinputDef)
        h.i("FLOWINPUT", Value.IntV(2L))
        assertEquals(2L, h.flags().flowinputDef)
    }

    @Test
    fun `FLOWINPUT 零参与五参都按个数不匹配报错`() {
        // ArgTypeList{Int,Int,Int,Int} 且 OmitStart = 1（:8466-8479）⇒ 合法区间 1..4。
        val h = MouseHarness()
        assertFailsWith<CodeEE> { h.call("FLOWINPUT") }
        assertFailsWith<CodeEE> {
            h.call("FLOWINPUT", Value.IntV(1L), Value.IntV(1L), Value.IntV(1L), Value.IntV(1L), Value.IntV(1L))
        }
        assertEquals(0L, h.flags().flowinputDef)   // 两条都没走到写入
    }

    // ===== FLOWINPUTS：FlowInputsMethod :8502-8531；注册 :9301 =====

    @Test
    fun `FLOWINPUTS 写字符串模式开关与默认串并返回 0`() {
        val h = MouseHarness()
        assertEquals(0L, h.i("FLOWINPUTS", Value.IntV(1L), Value.StrV("初期値")))
        val f = h.flags()
        assertTrue(f.flowinputString)        // :8524
        assertEquals("初期値", f.flowinputDefString)   // :8527
        // 第 2 参省略时默认串保持（:8525 的 if）——初值口径："" （Process.cs:185）。
        assertEquals(0L, h.i("FLOWINPUTS", Value.IntV(0L)))
        assertFalse(h.flags().flowinputString)
        assertEquals("初期値", h.flags().flowinputDefString)
        // 空串是合法实参，会被写入（不是「省略」）。
        h.i("FLOWINPUTS", Value.IntV(1L), Value.StrV(""))
        assertEquals("", h.flags().flowinputDefString)
    }

    @Test
    fun `FLOWINPUTS 只接受 1 到 2 个实参且第 2 参必须是串`() {
        // ArgTypeList{Int,String} 且 OmitStart = 1（:8507-8516）。
        val h = MouseHarness()
        assertFailsWith<CodeEE> { h.call("FLOWINPUTS") }
        assertFailsWith<CodeEE> { h.call("FLOWINPUTS", Value.IntV(1L), Value.StrV("a"), Value.StrV("b")) }
        val e = assertFailsWith<CodeEE> { h.call("FLOWINPUTS", Value.IntV(1L), Value.IntV(9L)) }
        assertTrue(e.message!!.contains("字符串"), "实际：${e.message}")   // sv() 的类型守卫
    }

    // ===== 注册元数据与 VM 隔离 =====

    @Test
    fun `注册元数据 返回类型与参数区间`() {
        val h = MouseHarness()
        assertEquals(OperandType.INT, h.method("MOUSEX").retType)   // :7845
        assertEquals(OperandType.INT, h.method("MOUSEY").retType)
        assertEquals(OperandType.STR, h.method("MOUSEB").retType)   // :7869（**字符串**，不是掩码）
        assertEquals(OperandType.INT, h.method("MOUSESKIP").retType)  // :3623
        assertEquals(OperandType.INT, h.method("FLOWINPUT").retType)   // :8465
        assertEquals(OperandType.INT, h.method("FLOWINPUTS").retType)  // :8506
        assertEquals(0, h.method("MOUSEX").minArgs); assertEquals(0, h.method("MOUSEX").maxArgs)  // :7846
        assertEquals(0, h.method("MOUSEB").maxArgs)                    // :7870
        assertEquals(0, h.method("MOUSESKIP").maxArgs)                 // :3624（argumentTypeArray = null）
        assertEquals(1, h.method("FLOWINPUT").minArgs); assertEquals(4, h.method("FLOWINPUT").maxArgs)
        assertEquals(1, h.method("FLOWINPUTS").minArgs); assertEquals(2, h.method("FLOWINPUTS").maxArgs)
        assertFalse(h.mouse.windowCreated)                  // 缺省即「无窗口」，不伪造现场
        assertFalse(h.mouse.alwaysRefresh)                  // 缺省即原版 AlwaysRefresh 的零值
        assertNull(h.mouse.pointing)                        // 缺省无指向
        assertEquals(0, h.mouse.clientWidth)                // 尺寸只由宿主写入
        assertEquals(0, h.mouse.clientHeight)
        assertFalse(h.mouse.isPointerInClientArea())        // 尺寸为 0 ⇒ 任何点都算越界（:7879）
        assertEquals("", h.mouse.buttonValue())             // 未接现场的 MOUSEB 读数
    }

    @Test
    fun `鼠标现场按 VM 隔离且 aux 槽幂等复用同一实例`() {
        // 禁静态可变全局（AGENTS §2.4）：两份 VariableStore 必须是两份现场。
        val a = MouseHarness()
        val b = MouseHarness()
        a.mouse.windowCreated = true
        a.mouse.setClientSize(CLIENT_W, CLIENT_H)
        a.mouse.setPointerPosition(300, 200)
        a.mouse.pointing = strButton("甲")
        assertEquals(300L, a.i("MOUSEX"))
        assertEquals(0L, b.i("MOUSEX"))                     // 未创建 → 0
        assertEquals(-261L, a.i("MOUSEY"))                  // 200 - 461（EmueraConsole.cs:2264）
        assertEquals(0L, b.i("MOUSEY"))
        assertEquals("", b.s("MOUSEB"))                     // 现场不串味
        assertNotNull(a.mouse.pointing)
        assertEquals(a.mouse, a.store.aux.slot("mouse") { MouseState() })  // 同一实例，不是拷贝
        b.flags().flowinputDef = 9L
        assertEquals(9L, b.flags().flowinputDef)
        assertEquals(0L, a.flags().flowinputDef)            // 流式标志同样按 VM 隔离
    }
}
