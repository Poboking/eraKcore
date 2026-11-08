package com.eratw.port.core.fn

import com.eratw.port.core.host.AndroidKeyCodes
import java.util.concurrent.ConcurrentHashMap

/**
 * 文本框镜像端口（宿主编排）：读 = `GETTEXTBOX` 的数据源，写 = 覆盖镜像。
 *
 * 只服务执行期点击意图的补写（见 [KeyEventInputState] 类文档「执行期点击意图」节）。
 * 真机由 `GameHost` 绑到会话的 `ConsoleTextBox`；未绑定（离线装置）时读写都退化为空，
 * 意图仍按沿变寿命被收走，只是无处落笔。
 */
interface InputMirrorPort {
    fun read(): String

    fun write(text: String)
}

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
 * ## 执行期点击意图（CHARA_MOVEMENT 长链丢点击）
 *
 * 真机「点好几次才切换地图」：QoL 地图/外出屏走 `AWAIT` + `GETKEYTRIGGERED` + `GETTEXTBOX`
 * 轮询（4.980 语料 qol_MAP.ERB:44 的 `AWAIT_UI_INPUT`），执行期（无等待方）点按钮的文本被
 * 宿主的执行期闸丢弃（`EmueraConsole.cs:1374-1377` 的 Running 早退），只剩「文本框镜像 +
 * VK_RETURN 沿变」两条保留通道。脚本在下一轮输入循环顶用 `SETTEXTBOX MOUSEB()`
 * （qol_MAP.ERB:43）覆写镜像时，回车支读到的就是空串 —— 点击随之消失。
 *
 * 宿主把这类点击经 [noteClickIntent] 记进**单槽**（最新一笔覆盖，不排队列：队列积压会被
 * 下一屏误吃，2026-10-25 跨屏串值红），本类在**读点**收走：
 * - 沿变被查询（[keyState]）且是 VK_LBUTTON / VK_RETURN 时解析该意图；
 * - 镜像仍握着这笔值 ⇒ 直接作废 —— 消费它的那支（左键 MOUSEB 或回车 GETTEXTBOX）读到的
 *   就是同一份文本，再补写等于制造第二次触发；
 * - 镜像已被覆写 ⇒ **左键读点保留**、由紧随其后的 VK_RETURN 读点经 [mirror] 补写回去，
 *   紧跟其后的 `GETTEXTBOX()` 就能读到。左键读点不补写（左键支读的是 MOUSEB，不是镜像），
 *   但若在这里清槽就会把回车兜底一起吞掉 —— QoL 轮询屏正是「先查左键、再查回车」。
 *
 * 意图与原版按键沿变同寿命：下一次读点或 [clear] 收走，不引入墙钟（沿变本身也不过期；
 * 给文本单独设 TTL 会让「沿变还在、文本却没了」两条通道分叉）。它**不进输入队列**，
 * 因此不会出现在取值等待（INPUT 系）的 RESULT/RESULTS 槽里，10-25 跨屏串值面不受影响；
 * 宿主还会在脚本落到 sink 等待时经 `QueueInputSink.onWaitEntered` 作废它（原版 Running
 * 早退那一面保持丢弃，不补交）。
 *
 * **偏离登记**：这是对原版「Running/Initializing 期间按回车直接丢弃文本」
 * （`EmueraConsole.cs:1374-1377`）的一处刻意偏离，仅限**画面按钮落点**。PC 上 QoL 轮询屏
 * 靠鼠标事件（MOUSEB/沿变）存活，触屏只有「提交即回车」一条通道，丢文本就等于丢点击
 * （真机「点好几次才切换地图」）。补写只落文本框镜像、不进取值槽；防串值闸见上一段，
 * ADR 由组长登记。
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

    /** 执行期点击意图的镜像端口；null = 未接线（补写退化为空操作）。宿主装配期写。 */
    @Volatile
    var mirror: InputMirrorPort? = null

    /** 执行期点击意图（单槽，最新一笔覆盖）及其监视器。 */
    private val intentLock = Any()
    private var clickIntent: String? = null

    /**
     * 记一笔**执行期点击**（无等待方时被宿主执行期闸丢弃的按钮落点；见类文档同名节）。
     *
     * 单槽覆盖：玩家连点时只有最后一笔算数 —— 排队列会在下一屏把积压逐条喂给 INPUT，
     * 正是 2026-10-25 跨屏串值的根子。非点击提交（键入文本）不进本槽。
     */
    fun noteClickIntent(text: String) {
        // 空值不记：它不携带任何落点；补写空串还会清掉脚本自己 SETTEXTBOX 写的状态串。
        if (text.isEmpty()) return
        synchronized(intentLock) { clickIntent = text }
    }

    /** 作废待交付的点击意图（新提交到达、BEGIN 复位时用）。 */
    fun clearClickIntent() {
        synchronized(intentLock) { clickIntent = null }
    }

    /** BEGIN 复位/切屏时清空，避免残留的按下位跨过一局生效。 */
    fun clear() {
        for (s in keys.values) {
            synchronized(s) {
                s.physical = false
                s.edge = false
            }
        }
        clearClickIntent()
    }

    /**
     * 在读点解析执行期点击意图（[keyState] 报出沿变后调用，**不得持槽锁**：端口读写是
     * 宿主/脚本的普通文本操作，不要在按键锁里做）。
     *
     * 解析全程持 [intentLock]（含端口的读与写）：UI 线程的 [clearClickIntent] /
     * [noteClickIntent] 与它互斥，否则会留下「这里读到旧值 → 新点击写进镜像 → 这里再用
     * 旧值覆盖」的交叠窗口。端口实现只许摸文本框（不得回调输入主机，否则与宿主的
     * pendingLock 成环）。
     *
     * @param enterRead true = VK_RETURN 读点（镜像被覆写时补写回去）；false = VK_LBUTTON
     *   读点（只作废「镜像已握着」的情形、不补写；镜像被覆写时保留给回车读点）
     */
    private fun resolveClickIntent(enterRead: Boolean) {
        synchronized(intentLock) {
            val pending = clickIntent ?: return
            val port = mirror
            // 镜像还握着这笔值：无论左键支还是回车支，读文本的消费方拿到的就是它，
            // 再补写会在下一轮制造第二次触发（地图屏会直接「同点两下」甚至退出）。
            if (port != null && port.read() == pending) {
                clickIntent = null
                return
            }
            // 左键读点 **不** 动这笔意图：左键支读的是 MOUSEB()（不是镜像），镜像被覆写
            // 说明它多半读不到值；留给紧随其后的回车读点补写（QoL 轮询屏先查左键、再查
            // 回车）。在这里清掉会把兜底一起吞掉 —— 真机「点好几次」正是这个形态。
            if (!enterRead) return
            clickIntent = null
            // 镜像已被覆写（或未接线）：以补写为准 —— 紧接着的 GETTEXTBOX 必须看到这笔点击。
            // 未接线时 write 是空操作，意图只能作废（无处落笔）。
            port?.write(pending)
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
        val fresh: Boolean
        val result: Int
        synchronized(s) {
            fresh = s.edge
            if (!s.physical && !fresh) return 0
            s.edge = false
            result = if (fresh) DOWN_TOGGLED else DOWN_ONLY
        }
        // 读点解析执行期点击意图（见类文档同名节）：锁外做，端口读写不占按键锁。
        if (fresh) {
            when (virtualKey) {
                AndroidKeyCodes.VK_RETURN -> resolveClickIntent(enterRead = true)
                AndroidKeyCodes.VK_LBUTTON -> resolveClickIntent(enterRead = false)
            }
        }
        return result
    }

    private fun slotOf(virtualKey: Int): KeySlot = keys.getOrPut(virtualKey) { KeySlot() }

    companion object {
        /** 按下（bit15）+ 沿变位（bit0）；`short` 0x8001 符号扩展后的 int 形。 */
        private const val DOWN_TOGGLED: Int = -32767

        /** 仅按下（bit15，沿变位已回落）；`short` 0x8000 符号扩展后的 int 形。 */
        private const val DOWN_ONLY: Int = -32768
    }
}
