package com.eratw.port.core.fn

import java.util.concurrent.ConcurrentHashMap

/**
 * 离散按键事件后端的 [InputState]（真机接线用）。
 *
 * ## 两个读数位（照 Win32 `GetKeyState` 的文档语义）
 * - **bit15（按下）**：`keyState < 0` ⇒ GETKEY 报 1（spec: FunctionMethodCreator.cs:7837）。
 * - **bit0（沿变位）**：Win32 对非切换键的口径是「**自上一次 `GetKeyState` 调用之后被按下过**」，
 *   按下后第一次查询为 1、之后回到 0（抬起后仍为 0）。core 侧 `keyStateOf` 把
 *   `(keyState and 1) + 1` 存进 `keytoggle` 并与上次读数比较，不等才算一次触发
 *   （:7826-7833），于是**一次按下会产生两次触发**：第一次是沿变位本身，第二次是沿变位
 *   回落到 0 时读数从 2 变 1。这是原版的既有形态，本类照复刻，不「顺手修好」（ADR-005）。
 *
 * ## 短按不丢
 * 事件在两次轮询之间完成（按下又抬起）时，真 Win32 也会因 bit15 已回落而只留下沿变位；
 * 本类同样把这次按下**存记**到下一次查询，之后归零。真机的触屏「提交即回车」正是这种
 * 一次性事件，丢掉就等于 `TITLE.ERB:158` 的跳出条件永假（红 E）。
 *
 * ## 线程口径
 * 写侧是 UI 线程（Android 事件回调），读侧是 VM 线程。每键状态装在一个 [KeySlot] 里，
 * 表本体用 [ConcurrentHashMap]，槽内两标志用槽对象自身作监视器读写，不引入全局状态
 * （AGENTS §2 铁律 4：可变状态挂实例，不挂静态）。
 *
 * @param activeProvider 窗口激活态（原版 `EmueraConsole.IsActive`，EmueraConsole.cs:258-268：
 *   未激活时 GETKEY 族一律返回 0，:7816-7819）。缺省恒为已激活。
 */
class KeyEventInputState(
    private val activeProvider: () -> Boolean = { true },
) : InputState {

    /** 每键的「物理按下」与「未读沿变」两位；只在键存在时分配。 */
    private class KeySlot {
        var physical = false
        var edge = false
    }

    private val keys = ConcurrentHashMap<Int, KeySlot>()

    override val isActive: Boolean get() = activeProvider()

    /** 指针位置：真机由宿主在触摸/绘制时写入（原版 `Console.GetMousePosition()`，:7857-7861）。 */
    @Volatile
    override var mouseX: Int = 0

    @Volatile
    override var mouseY: Int = 0

    /** MOUSEB 的数据源：当前指针指向的按钮输入串；null = 无指向。 */
    @Volatile
    override var pointingInput: String? = null

    /** 一次按下（[virtualKey] = Win32 虚拟键码，映射表由宿主负责，不做二次解释）。 */
    fun onKeyDown(virtualKey: Int) {
        val s = slotOf(virtualKey)
        synchronized(s) {
            s.physical = true
            s.edge = true
        }
    }

    /** 一次抬起；此前的按下若尚未被查询过，仍会在下一次查询报出（短按不丢）。 */
    fun onKeyUp(virtualKey: Int) {
        val s = slotOf(virtualKey)
        synchronized(s) { s.physical = false }
    }

    /** BEGIN 复位/切屏时清空，避免残留的按下位跨过一局生效。 */
    fun clear() {
        for (s in keys.values) {
            synchronized(s) {
                s.physical = false
                s.edge = false
            }
        }
    }

    /**
     * 原版 `GetKeyState` 的 short 读数（符号扩展成 int）：
     * 按下且有沿变 = `0x8001`（-32767）、仅按下 = `0x8000`（-32768）、未按下 = 0。
     *
     * 存记（[KeySlot.edge]）在被报出的这一次就清除，所以「按下→查询→抬起→再查询」
     * 不会把已抬起的键谎报成按住。
     */
    override fun keyState(virtualKey: Int): Int {
        val s = keys[virtualKey] ?: return 0
        synchronized(s) {
            val fresh = s.edge
            if (!s.physical && !fresh) return 0
            s.edge = false
            return if (fresh) DOWN_TOGGLED else DOWN_ONLY
        }
    }

    private fun slotOf(virtualKey: Int): KeySlot = keys.getOrPut(virtualKey) { KeySlot() }

    companion object {
        /** 按下（bit15）+ 沿变位（bit0）；`short` 0x8001 符号扩展后的 int 形。 */
        private const val DOWN_TOGGLED: Int = -32767

        /** 仅按下（bit15，沿变位已回落）；`short` 0x8000 符号扩展后的 int 形。 */
        private const val DOWN_ONLY: Int = -32768
    }
}
