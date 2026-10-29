package com.eratw.port.core.proc

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import java.util.concurrent.locks.ReentrantLock

/** 输入队列已关闭后仍发起等待（宿主销毁/退出游戏时用来打断阻塞的脚本线程）。 */
class InputSinkClosedException(
    message: String = "输入队列已关闭，不再接受等待",
) : IllegalStateException(message)

/**
 * 生产输入宿主：**阻塞队列**实现的 [InputSink]，按**等待形态**分派交付（[InputRequest.RequestForm]）。
 *
 * ## 模型
 *
 * 队列里放的是「一次回车提交的原始文本」——就是原版 `PressEnterKey(keySkip, input, changedByMouse)`
 * 拿到的那一行（`EmueraConsole.cs:1371-1374` 的输入循环入口）。UI/触屏/宏都只需
 * 「把用户敲的这条文本推进来」这一个动作；等待侧的形态判定（吃不吃这条提交、超时推不推进、
 * 交付写哪个槽）全在 [waitInput] 里按原版规则做，UI 不参与解释。
 *
 * 形态分派表（原版两处判定点：`PressEnterKey` 的入口早退 :1412-1448、
 * `doInputToEmueraProgram` 的 switch :1112-1288、`endTimer` 的超时收尾 :1040-1082）：
 *
 * | 形态 | 一条提交 | 超时 | 交付 |
 * |---|---|---|---|
 * | VALUE + INT | 空且非计时中取 `DefIntValue`；解析失败**丢弃继续等** | 有默认值 ⇒ 交付默认值；**无默认值 ⇒ 死等** | RESULT |
 * | VALUE + STR | 空且非计时中取 `DefStrValue`，否则原文（含空串） | 恒推进（默认值或空串） | RESULTS |
 * | ENTER_KEY / ANY_KEY | 恒推进，文本忽略 | 恒推进 | **什么都不写** |
 * | VOID | **丢弃、不推进**（早退 :1417-1420） | 恒推进 | 什么都不写 |
 * | ANY_VALUE | 恒推进；解析得动写 RESULT，否则写 RESULTS | 推进并写空串到 RESULTS | 二选一 |
 * | PRIMITIVE_MOUSE_KEY | 恒推进（switch 无 case ⇒ 不写值） | 以事件类型 4 交付 | RESULT:0 = 4 |
 *
 * 线程模型：单生产（UI 线程）/ 单消费（VM 线程），内部用一把 [ReentrantLock] + 一个条件变量，
 * 不做自旋、不读墙钟（超时只用单调时钟 [timeSource]，默认 `System.nanoTime`；
 * 测试注入假计时源即可零墙钟地驱动截止分支）。
 *
 * ## 与原版逐条对齐的行为
 *
 * 1. **无请求方时提交不丢**：原版控制台把输入立刻交给 `doInputToEmueraProgram`
 *    （`EmueraConsole.cs:1108-1260`），没有「排队」概念，因为输入永远发生在等待之后；
 *    本类允许宿主抢跑提交（触屏连点、宏回放），先入队、后到者取。队首就是最早那次回车。
 *    **与原版的一处差异（已在宿主接线收口，2026-10-25）**：原版 `PressEnterKey` 在
 *    `state` 为 `Running`/`Initializing`（脚本执行中）时**直接丢弃**这次按键（:1374-1377）
 *    ⇒ 「脚本跑着的时候按的回车」不会落到下一次等待；本类队列自身没有控制台状态这一维，
 *    供测试/装置先入队后等待（BootPlayChain 的 UI 代理依赖这一形态）。**生产宿主**
 *    （GameHost.submitInternal）现按 [isAwaiting] 预筛：无等待方时文本丢弃、
 *    `keySkip` 走 [noteSkipIntent] 保住跳过意图 —— 与原版「入口置 MesSkip 后早退」同序。
 *    真机教训（未预筛时代）：世界初始化页一跑十几秒，期间玩家输入全部入队、被**下一个
 *    界面**的 INPUT 吃掉，值跨屏错位直接把 qol_update.ERB:358 的 CFLAG:(V-2000)/10 打越界。
 *    预筛仍有 µs 级竞态窗口（isAwaiting 读与 submit 之间等待方可能离开），与原版
 *    「事件到达时 state 恰好翻转」的窗口同构，不追求完全等价。
 *    **两处例外**：① VOID 形态下到达的提交按 :1417-1420 **丢弃**（原版那次
 *    回车根本没进程序，文本只留在输入框里，不会被下一次等待自动吃掉）；② 越过截止点之后
 *    才到达的提交留给**下一次**等待（原版 `endTimer` 已用空文本推进并 `stopTimer`，
 *    后到的按键属于下一次 `WaitInput`，见第 6 条）。
 * 2. **`oneInput` 截断**：`inputReq.OneInput && text.Length > 1` ⇒ 只留第 1 个字符
 *    （`EmueraConsole.cs:1440-1443` 的 `text.Remove(1)`）；截断发生在**取值之前**，
 *    与数值/字符串类型无关。
 * 3. **空行取默认值**：`string.IsNullOrEmpty(str) && inputReq.HasDefValue && !IsRunningTimer`
 *    ⇒ 整数取 `DefIntValue`（:1119-1124）、字符串取 `DefStrValue`（:1190-1194）。
 *    `IsRunningTimer` 为真（限时等待进行中且尚未超时，:371-381）时空行**不**取默认值：
 *    整数解析失败丢弃该次提交继续等（:1125-1128），字符串按空串提交（:1190-1204）。
 * 4. **数值输入解析失败 = 丢弃该次提交并继续等**：原版 `long.TryParse` 失败直接 `return false`
 *    （:1125-1128），控制台**不离开** WaitInput 状态、不改 `RESULT`。这里同样丢弃并重新等待；
 *    队列空了就继续阻塞（不是伪造 0，也不是抛错）。
 * 5. **非取值形态认任何一次提交**：原版 `ReadAnyKey` 只是把 `inputReq.InputType` 置成
 *    `EnterKey`/`AnyKey` 后等下一次 `PressEnterKey`（:874-895），输入文本被忽略；
 *    `doInputToEmueraProgram` 的 switch 没有这两项的 case ⇒ 直接落到 :1278 的 `stopTimer`
 *    与 :1287 的 `return true`（推进但不写值）。因此 ENTER_KEY / ANY_KEY 对 [waitInput]
 *    **不区分提交内容**，出队即返回；[readAnyKey] 是同一条路的无时限版本（WAIT 家族用）。
 *    `anyKey` / `stopMesSkip` 只影响消息跳过与提示，不影响等待条件（:1451-1456 的
 *    `MesSkip` 循环是控制台侧的事）。
 * 6. **限时输入超时**：`Timelimit > 0` 时按毫秒截止；超时把本次等待的 `timerFired` 置真
 *    （对应 `endTimer` 的 `isTimeout = true`，:1043）后按形态决定是否推进 ——
 *    原版 `endTimer` 调 `RunEmueraProgram("")`（:1068），空文本经 `doInputToEmueraProgram`：
 *    整数有默认值 ⇒ 交付 `DefIntValue`（:1119-1124，此时 `IsRunningTimer` 已为假）；
 *    整数**无**默认值 ⇒ `long.TryParse("")` 失败返回 false ⇒ **不推进，原地继续等**；
 *    字符串 ⇒ 交付 `DefStrValue` 或空串（:1190-1204）；非取值形态 ⇒ 无 case ⇒ 推进。
 *    `timerFired` 在一次等待内**粘滞**：死等之后靠提交推进时，[InputOutcome.isTimeout] 仍为真
 *    （原版 `stopTimer`（:1035-1038）不清 `isTimeout`）。
 *
 *    **按键与截止点的先后**：每条提交带按下时刻（[Entry.atNs]）。队首的按下时刻不晚于截止点
 *    ⇒ 原版同一 UI 线程按事件到达序处理，按键先于计时器 tick ⇒ `doInputToEmueraProgram`
 *    收下它并在 :1278 `stopTimer` ⇒ 这一次**不算超时**（`isTimeout` 保持假，直到下一次
 *    `setTimer` 才改写）。反之，截止点先到 ⇒ 先交超时结果（默认值/空串/推进），那条后到的
 *    提交**不出队**，留给下一次等待 —— 原版 `endTimer` 已经推进完，后到的 Enter 落在新的
 *    `WaitInput` 上（:1040-1082 与 :1371-1374 的事件序）。
 * 7. **超时提示语**：`endTimer` 在推进前先按 `DisplayTime` 决定是改写最后一行还是新起一行
 *    打印 `TimeUpMes`（:1058-1065）。那是绘制层动作，本类不打印，只把形态与
 *    [InputRequest.timeUpMessage] 原样留在 [awaiting] 快照里供宿主取用。
 * 8. **关闭唤醒**：原版退出时 `state = ConsoleState.Quit` 并 `window.Close()`
 *    （:1378-1389、:1390-1401），等待方永远不会再被喂输入。本类用 [close] 等价地打断等待：
 *    已入队的文本保留在队列里但不再被消费，等待方抛 [InputSinkClosedException]。
 *    VOID + `Timelimit <= 0`（例如 `TWAIT 0, 1`）在原版**没有任何键盘路径能推进**
 *    （:1417-1420 早退 + 无计时器 ⇒ 只有鼠标改写的提交或关窗能收；语料普查 0 命中），
 *    本类照抄为无限等（ADR-005），只能由 [close] / interrupt 打断。
 * 9. **谁吃、吃几次、多余的去哪**：一次等待只吃**一条**能推进的提交（[readAnyKey] 同样一条）；
 *    被丢弃的提交（数值解析失败、VOID 期间的按键）算「吃掉了但没推进」，不再回到队里；
 *    一次等待结束后队列里剩下的条目属于**后续**等待，不会被当前这次补吃 —— 与原版
 *    「一条 Enter 事件只跑一次 `doInputToEmueraProgram`，推进后 state 变了，后面的按键落到
 *    新的 WaitInput」一致（`EmueraConsole.cs:1456-1470` 的循环在 `state != WaitInput` 时 break）。
 *
 * ## 被 interrupt 时的语义
 *
 * [waitInput] / [readAnyKey] 的阻塞等待被 [Thread.interrupt] 打断时**不是脚本错误**：
 * 本移植里 interrupt 只有一个含义——宿主正在销毁（`GameHost.close` 打断 VM 线程），
 * 若任其冒出 [InterruptedException]，上层 `loadAndRun` 的 catch(Throwable) 会把它
 * 错误地写进失败屏。因此等待侧捕获后先复查 [close] 状态：已关闭则抛
 * [InputSinkClosedException]（宿主销毁的正常收敛路径）；尚未关闭则恢复中断位
 * （[Thread.currentThread].interrupt()，不吞中断）并按虚假唤醒重进等待循环。
 * 锁语义不受影响：`Condition.await` 因中断释放锁后再重新获取，catch 在锁内执行，
 * finally 的 unlock 一次配对不变。
 *
 * TODO(verify)：原版 `Void` 的「鼠标改写提交」那一路会 `i--` 重放一次并以空文本推进
 * （:1406-1408 + :1444-1448），本类按键盘路径（:1417-1420）一律忽略；补齐要在
 * [Entry] 里加 `changedByMouse` 维并让宿主提交点（`app`/`platform`）跟着分流。
 * `PRIMITIVE_MOUSE_KEY` 的真鼠标/按键事件推进（:1290-1346）同样未接，本类只覆盖
 * 「一条提交」与「超时」两个推进源。
 * 另有四处提交预处理/状态维未接（语料 0 命中，不影响本路形态分派）：
 * ① 脚本执行期（`state == Running`，:1374-1377）的按键原版丢弃 —— 生产宿主已按
 * [isAwaiting] 预筛对齐（见第 1 条，2026-10-25），本类自身的队列语义保留给测试/装置；
 * ② `@` 开头的系统命令（:1412-1416，`input.Length > 1 && !OneInput` 才走）；
 * ③ 括号表达式输入 `parseInput`（:1425-1428，实现 :1546）—— 提交文本在原版进
 * `doInputToEmueraProgram` 之前还要过这两道，本类按「整条就是最终文本」收；
 * ④ **多段切分**：原版先把这条提交按 `spliter = { "\\n", "\r\n", "\n", "\r" }`
 * （`EmueraConsole.cs:146`，首项是**字面**反斜杠+n 两字符）切成多段，再逐段
 * `RunEmueraProgram`（:1429-1433）⇒ 一次回车可跨多处等待连推；本类把「一条提交」
 * 当作一个等待的输入、不切分（宏回放/多行输入含字面反斜杠+n 或 CR/LF 时行为分叉）。
 * 段内跳过标记的处理已补上（见 `ESCAPE_SKIP` 与 [interpret]）。
 */
class QueueInputSink(
    /** 单调时钟（纳秒）。默认墙钟；测试注入假时钟以在不真等毫秒的情况下驱动截止/超时分支。 */
    private val timeSource: () -> Long = { System.nanoTime() },
) : InputSink, AutoCloseable {

    /**
     * `PressEnterKey` 段内的跳过标记 `\e`（`EmueraConsole.cs:1435` 的 `"\\e"` 字面量：
     * 反斜杠 + `e` 两个字符，**不是** ESC 控制符 U+001B）。命中即从提交文本里全部剥离，
     * 并把 `MesSkip` 置真（:1437-1438）。C# 逐段独立判定（:1432 的分段循环），
     * 本移植「一条提交 = 一段」等价为整条判定一次；剥离发生在 OneInput 截断**之前**（同 C# 序）。
     */
    private companion object {
        const val ESCAPE_SKIP = "\\e"
    }

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queue = ArrayDeque<Entry>()
    private var closed = false

    /** 最近一次被消费的提交带不带 `keySkip`（[consumeMesSkip] 的待取标志）。 */
    private var consumedMesSkip = false

    /**
     * 翻页计时：最近一次 [submit] 的纳秒时刻（0 = 当前没有待测翻页）。
     *
     * 口径 = 「提交 → 下一次进入等待」的墙钟毫秒：脚本被输入唤醒后一直跑到下一页刷完、
     * 落到下一处 [waitInput]/[readAnyKey] 为止，正好是玩家感知的「一页」耗时。
     * 与 `GameHost.drive()` 里按 handler 返回计的口径不同 —— 一个 handler 内可能连等
     * 好几页（自定义角色流），那种口径永远等不到返回；这里按**等待点**计，逐页都有读数。
     *
     * 只在 debug 宿主装了 [onTurnReady] 时有观察者，生产路径只多一次 `nanoTime` 与一个空判。
     */
    @Volatile
    private var lastSubmitNs: Long = 0L

    /** 翻页读数观察者（宿主调试通道用；null = 不测）。参数 = 毫秒。 */
    var onTurnReady: ((Long) -> Unit)? = null

    /**
     * 记一次「页面就绪」：把上次提交到当前等待点的墙钟毫秒交给观察者。
     *
     * 每次等待点只消费一次起点（置 0），连点/排队提交只记最新一次。
     */
    private fun noteTurnReady() {
        val start = lastSubmitNs
        if (start == 0L) return
        lastSubmitNs = 0L
        onTurnReady?.invoke((System.nanoTime() - start) / 1_000_000L)
    }

    /**
     * 队列元素：一次回车提交的原文 + `PressEnterKey` 的 `keySkip`（`EmueraConsole.cs:1371-1373`）
     * + 按下时刻（[timeSource] 纳秒，判「按键与截止点谁先到」用）。
     */
    private data class Entry(val text: String, val mesSkip: Boolean, val atNs: Long)

    /** 未消费的提交条数（UI 观察用；同锁读）。 */
    val pendingCount: Int
        get() {
            lock.lock()
            try {
                return queue.size
            } finally {
                lock.unlock()
            }
        }

    /** 是否已关闭（[close] 之后为 true）。 */
    val isClosed: Boolean
        get() {
            lock.lock()
            try {
                return closed
            } finally {
                lock.unlock()
            }
        }

    /**
     * 当前正在等待的输入请求；null = 不在 [waitInput] 里。
     *
     * 只读快照，给 UI 选按钮可点性（`ButtonAcceptance`）与输入框类型用；等待方在
     * [waitInput] 进出时维护，读侧不加锁（单生产单消费之外只允许这种只读快照观察）。
     */
    @Volatile
    var awaiting: InputRequest? = null
        private set

    /** 当前是否在 [readAnyKey] 里（WAIT 系：只等一次回车，不解释文本）。 */
    @Volatile
    var awaitingAnyKey: Boolean = false
        private set

    /** 是否有任一等待方在等（UI 据此点亮提交按钮）。 */
    val isAwaiting: Boolean
        get() = awaiting != null || awaitingAnyKey

    /**
     * 提交一条「回车文本」。
     *
     * @param mesSkip `PressEnterKey` 的 `keySkip`：双击/右键/Esc 跳过时为 true，
     *   普通回车为 false（`EmueraConsole.cs:1371-1373`、`MainWindow.cs:1415-1422`）
     * @return true = 已入队；false = 队列已关闭（丢弃，不抛：这条提交来自正在销毁的 UI，
     *   不该把关闭竞态变成宿主线程上的异常）
     */
    fun submit(text: String, mesSkip: Boolean = false): Boolean {
        lock.lock()
        try {
            if (closed) return false
            // 翻页计时起点：入队即算「玩家已提交」，到下一个等待点结算。
            lastSubmitNs = System.nanoTime()
            // 按下时刻用 [timeSource]（与截止点同一条时间轴；假时钟下可判先后）。
            queue.addLast(Entry(text, mesSkip, timeSource()))
            arrived.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /** [submit] 的整数便捷入口（按钮 [0]/[1]、指令号等）。 */
    fun submitInt(value: Long): Boolean = submit(value.toString())

    /**
     * 只记跳过意图、不落文本（原版 `PressEnterKey` 在脚本执行期的形态：`MesSkip = keySkip`
     * 先行（`EmueraConsole.cs:1373`）、随后 Running/Initializing 早退把文本丢弃（:1374-1377））。
     *
     * 宿主按 [isAwaiting] 预筛「执行期提交」时（见类 KDoc 第 1 条的接线建议，GameHost 已接），
     * `keySkip = true` 的那条提交调这里保住跳过意图；文本按原版丢弃。等待侧的取法不变：
     * 下一次 [consumeMesSkip] 读到真（与真提交同一条读口）。
     */
    fun noteSkipIntent() {
        lock.lock()
        try {
            consumedMesSkip = true
        } finally {
            lock.unlock()
        }
    }

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        lock.lock()
        try {
            awaitingAnyKey = true
            noteTurnReady()
            while (true) {
                ensureOpen()
                val entry = queue.removeFirstOrNull()
                if (entry != null) {
                    // 本次提交的 keySkip 与段内 `\e` 跳过标记一齐落标志（EmueraConsole.cs:1373/:1438）。
                    consumedMesSkip = entry.mesSkip || entry.text.contains(ESCAPE_SKIP)
                    return
                }
                try {
                    arrived.await()
                } catch (_: InterruptedException) {
                    // interrupt = 宿主销毁（见类 KDoc「被 interrupt 时的语义」）：恢复中断位后
                    // 按关闭收敛。continue 重进等待会在持锁状态下热转（await 首行即再抛），
                    // close 拿不到同一把锁就永远置不上 closed。
                    Thread.currentThread().interrupt()
                    ensureOpen()
                    throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
                }
            }
        } finally {
            awaitingAnyKey = false
            lock.unlock()
        }
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        val form = request.form
        val limitMs = request.timeLimit
        val timed = limitMs > 0
        val deadline = if (timed) timeSource() + limitMs * 1_000_000L else Long.MIN_VALUE
        // 本次等待内的 `isTimeout`（原版控制台字段，EmueraConsole.cs:138/:1043）：
        // 一旦越过截止点就置真并**粘滞**到本条等待结束。
        var timerFired = false
        lock.lock()
        try {
            awaiting = request
            // EnterKey/AnyKey 形态在原版是「按一次键就走、不取值」的等待，与 WAIT 家族同相；
            // 宿主 UI 的键盘类型/按钮可点性按 [awaitingAnyKey] 走（`GameHost.acceptance`）。
            if (form == InputRequest.RequestForm.ENTER_KEY || form == InputRequest.RequestForm.ANY_KEY) {
                awaitingAnyKey = true
            }
            noteTurnReady()
            while (true) {
                ensureOpen()
                // VOID：键盘提交在出队之前就早退（:1417-1420）⇒ 丢弃，不留给下一次等待。
                if (form == InputRequest.RequestForm.VOID) drainIgnored()
                // kotlin.collections.ArrayDeque 没有 peekFirst（stdlib 2.0.20 字节码核实），
                // firstOrNull 即「看一眼队首、不出队」。
                val head = if (form == InputRequest.RequestForm.VOID) null else queue.firstOrNull()
                // 队首的按下时刻不晚于截止点 ⇒ 按键先到（原版同一 UI 线程按事件序处理）。
                val inTime = head != null && (!timed || head.atNs <= deadline)
                // 时钟推进到「计时器先到」这一支（原版 `tickTimer` 过点即 `endTimer`，
                // EmueraConsole.cs:1013-1016；`endTimer` 于 :1043 置 isTimeout）。
                val firedNow = timed && !timerFired && !inTime && timeSource() - deadline >= 0L
                if (firedNow) timerFired = true
                val entry = when {
                    head == null -> null
                    inTime -> queue.removeFirst()
                    // 超时先收尾：`endTimer` → `RunEmueraProgram("")`（:1068）用空文本推进，
                    // 后到的这条按键留在队里给下一次等待。
                    firedNow -> null
                    // 超时后仍停在同一处等待 = 只有「整数无默认值」的死等路径（:1119-1128）。
                    else -> queue.removeFirst()
                }
                if (entry != null) {
                    // 跳过标志与取值成败无关：原版在 PressEnterKey 入口就置 MesSkip
                    // （EmueraConsole.cs:1373），提交被丢弃也不回滚。
                    // 段内 `\e` 跳过标记同样置位（:1435-1438）——两者 OR 后供 sem 层一次性取用。
                    consumedMesSkip = entry.mesSkip || entry.text.contains(ESCAPE_SKIP)
                }
                interpret(request, entry, timed, timerFired)?.let { return it }
                // 只有「没有可取的货」才等：interpret 返回 null（丢弃提交）时队列里可能
                // 还有后续条目，直接 await 会错过早已发出的 signal 死等下去。
                val next = if (form == InputRequest.RequestForm.VOID) null else queue.firstOrNull()
                val pending = next != null && (timerFired || !timed || next.atNs <= deadline)
                when {
                    pending -> Unit
                    // 没有截止点可言（不限时 / 已过点还不能推进的死等路径）：无限挂起。
                    timerFired || !timed -> awaitQuietly()
                    else -> {
                        val remain = deadline - timeSource()
                        if (remain > 0L) awaitNanosQuietly(remain)
                    }
                }
            }
        } finally {
            awaiting = null
            awaitingAnyKey = false
            lock.unlock()
        }
    }

    /**
     * VOID 等待期间到达的提交：原版 `PressEnterKey` 在出队之前早退（`EmueraConsole.cs:1417-1420`），
     * 那次回车不进程序、也不会被下一次等待自动吃掉（文本只留在输入框里，要再按一次）。
     * `MesSkip` 仍然在入口就置（:1373），所以丢弃时照样记下跳过标志。
     */
    private fun drainIgnored() {
        while (true) {
            val ignored = queue.removeFirstOrNull() ?: return
            consumedMesSkip = ignored.mesSkip
        }
    }

    /**
     * 按形态解释一条提交（`entry == null` 表示「这一次循环手里没有可交付的提交」：
     * 要么队列空，要么计时器先到、那条提交留给下一次等待）。
     *
     * 返回 null = 这次循环什么都不交付、继续等待（原版 `doInputToEmueraProgram` 返回 false
     * ⇒ `RunEmueraProgram` 于 `EmueraConsole.cs:1094` 不收尾）。
     * 逐条对应 EmueraConsole.cs:1112-1288 的 switch 各分支与 :1040-1082 的超时收尾。
     */
    private fun interpret(
        request: InputRequest,
        entry: Entry?,
        timed: Boolean,
        timerFired: Boolean,
    ): InputOutcome? {
        val form = request.form
        // 原版 `IsRunningTimer`：限时等待且尚未超时（EmueraConsole.cs:371-381）。
        // 超时之后 `endTimer` 已把 isTimeout 置真 ⇒ 计时不再进行 ⇒ 空文本可以吃默认值。
        val timerRunning = timed && !timerFired
        if (entry == null) {
            if (!timerFired) return null
            // endTimer → RunEmueraProgram("") 的空文本交付路径（:1068 + :1112-1288）。
            return when (form) {
                InputRequest.RequestForm.VOID,
                InputRequest.RequestForm.ENTER_KEY,
                InputRequest.RequestForm.ANY_KEY,
                -> InputOutcome(isTimeout = true, writesInt = false, writesStr = false)
                InputRequest.RequestForm.VALUE -> when (request.type) {
                    // 整数：有默认值 ⇒ 交 DefaultInt；无默认值 ⇒ TryParse("") 失败 ⇒ 不推进（:1119-1128）
                    InputRequest.InputType.INT ->
                        if (request.hasDef) {
                            InputOutcome(intValue = request.defInt, isTimeout = true, writesStr = false)
                        } else {
                            null
                        }
                    // 字符串：默认值或空串，两种都推进（:1190-1204）
                    InputRequest.InputType.STR ->
                        InputOutcome(strValue = if (request.hasDef) request.defStr else "", isTimeout = true, writesInt = false)
                }
                // AnyValue：空串解析不成整数 ⇒ InputString("")（:1256-1274）
                InputRequest.RequestForm.ANY_VALUE ->
                    InputOutcome(strValue = "", isTimeout = true, writesInt = false)
                // PrimitiveMouseKey：endTimer 直接 InputMouseKey(4,0,0,0,0,0)（:1044-1056）
                // ⇒ RESULT:0 = 4 是「超时交付」的事件类型位；RESULTS 不动。
                InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                    InputOutcome(intValue = 4L, isTimeout = true, writesStr = false)
            }
        }
        // 原版按段切分后逐段截断（:1440-1443）；本移植的提交不带分隔符，等价于「整条就是一段」。
        // 段内 `\e` 跳过标记先全部剥离（:1435-1439），剥离发生在 OneInput 截断**之前**（与 C# 同序）。
        val raw0 = entry.text
        val raw = if (raw0.contains(ESCAPE_SKIP)) raw0.replace(ESCAPE_SKIP, "") else raw0
        val text = if (request.oneInput && raw.length > 1) raw.substring(0, 1) else raw
        return when (form) {
            // EnterKey/AnyKey：任何一条提交都推进（:1421-1424 顺带 stopTimer），
            // switch 没有对应 case ⇒ 不写 RESULT/RESULTS（:1276-1287）。
            InputRequest.RequestForm.ENTER_KEY,
            InputRequest.RequestForm.ANY_KEY,
            -> InputOutcome(isTimeout = timerFired, writesInt = false, writesStr = false)
            // Void 走不到这里（上面不出队），保留分支只为穷尽 when。
            InputRequest.RequestForm.VOID -> null
            InputRequest.RequestForm.VALUE -> when (request.type) {
                InputRequest.InputType.INT -> {
                    if (text.isEmpty() && request.hasDef && !timerRunning) {
                        return InputOutcome(intValue = request.defInt, isTimeout = timerFired, writesStr = false)
                    }
                    val v = text.trim().toLongOrNull() ?: return null
                    InputOutcome(intValue = v, isTimeout = timerFired, writesStr = false)
                }
                InputRequest.InputType.STR -> {
                    val s = if (text.isEmpty() && request.hasDef && !timerRunning) request.defStr else text
                    InputOutcome(strValue = s, isTimeout = timerFired, writesInt = false)
                }
            }
            // AnyValue（INPUTANY）：能解析就只写 RESULT，否则只写 RESULTS（:1256-1274）。
            // 注意默认值不参与：原版该分支没有 HasDefValue 判断。
            InputRequest.RequestForm.ANY_VALUE -> {
                val v = text.trim().toLongOrNull()
                if (v != null) {
                    InputOutcome(intValue = v, isTimeout = timerFired, writesStr = false)
                } else {
                    InputOutcome(strValue = text, isTimeout = timerFired, writesInt = false)
                }
            }
            // PrimitiveMouseKey：一条文本提交同样推进（switch 无 case ⇒ 不写值，:1276-1287）。
            InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ->
                InputOutcome(isTimeout = timerFired, writesInt = false, writesStr = false)
        }
    }

    /**
     * 无限挂起。interrupt = 宿主销毁（见类文档「被 interrupt 时的语义」）：恢复中断位后
     * 按关闭收敛。这里**不得**回循环重等 —— 持锁状态下 await 首行即再抛会热转，
     * close 拿不到同一把锁就永远置不上 closed（上游 4f9f379 的裁定，见主线上同一裁语）。
     */
    private fun awaitQuietly() {
        try {
            arrived.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ensureOpen()
            throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
        }
    }

    /** 按剩余纳数挂起；中断收敛同 [awaitQuietly]。 */
    private fun awaitNanosQuietly(remainNanos: Long) {
        try {
            arrived.awaitNanos(remainNanos)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ensureOpen()
            throw InputSinkClosedException("等待被中断：宿主正在销毁，输入队列不再接受等待")
        }
    }

    /** [InputSink.consumeMesSkip] 的队列实现：读一次即清。 */
    override fun consumeMesSkip(): Boolean {
        lock.lock()
        try {
            val value = consumedMesSkip
            consumedMesSkip = false
            return value
        } finally {
            lock.unlock()
        }
    }

    /** 关闭队列并唤醒全部等待方（等待方抛 [InputSinkClosedException]）。可重复调用。 */
    override fun close() {
        lock.lock()
        try {
            closed = true
            arrived.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun ensureOpen() {
        if (closed) throw InputSinkClosedException()
    }
}
