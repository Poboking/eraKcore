package com.eratw.port.core.diag

/**
 * 「本页函数流水」：最近 N 次**进帧**的 ERB 函数下标环形流水（观察面，非语义）。
 *
 * ## 为什么需要这一面（取证）
 *
 * 系统队列等待点全部发生在 `BeginFlow` 的 Kotlin 步、相邻两次 ERB 调用**之间**
 * （`ConsoleBeginHost.kt:86-92` / `:130` 走 `waitSystemInteger`）；那一刻 VM 里
 * `depth == -1`（`Vm.kt` 的 `unwindScopes`，`:820`），「无帧 = null」是 `Vm.currentFunctionKey`
 * 的既有承诺（`Vm.kt:2326-2333`）⇒ 定名口拿不到「最内层 ERB 帧」，只能靠**进帧流水**
 * 回溯「本页依次进过哪些函数」。真机 `longturn <ms>ms @<栈顶>` 同理只有栈顶、没有页内顺序。
 *
 * ## 口径
 *
 * - 一「页」= 上一次提交输入 → 本次等待点（同 `QueueInputSink.onTurnReady` 的 `turnMs` 口径）；
 *   清零点钉在 `QueueInputSink` 的两个页边界（`readAnyKey` / `waitInput` 结算段之后）。
 * - **写入 = 进帧**：与 `funcStack[...]` 的三处建立写一一对应
 *   （`Vm.run` 的 `funcStack[0] = entryFunctionIndex`、`callInto` 与 `jumpInto` 各自的
 *   `funcStack[depth] = funcIndex` —— 按符号定位，行号随别的波次改 `Vm.kt` 会漂），
 *   `_RET`/退帧（`leaveFrameCore`）**不写**、也不回滚已写入的流水 —— 本面记录的是
 *   「发生过进帧」这一历史事实，不是当前栈。
 * - 记录的是**函数下标**（`program.functions` 的 Int 索引），**名字解析推迟到读侧**：
 *   热路径里查名会走 `Program.functions` 的懒物化（`Program.kt:1208-1221`：按名取用面
 *   专门为此另设 `nameAt`，就是为了不把 110,072 个函数名整池物化），且每次拼串即分配 ——
 *   观察面自己不能变成新的分配源。
 *
 * ## 容量与内存（常驻算式，512 MiB 挡位口径）
 *
 * 容量固定 [DEFAULT_CAPACITY] = 64 次进帧。代价：`IntArray(64)` = 256 B + 对象头，
 * 全实例（主 VM 一个）常驻 < 0.5 KiB；即便嵌套派生 VM 各挂一个也是 KiB 级 —— 对
 * 512 MiB 堆的账可以忽略，故**常驻开销不构成开关理由**；关不记由挂载点
 * （`Vm.pageTrail = null`）决定，而不是本类内部再加 enabled 位。
 * 为什么 64 够用：慢页定名要的是「等待点之前最近一段」的落点序列，桌面浸泡实测
 * 正常页 p99 = 4 ms（函数级进帧远慢于指令），64 条进帧足以覆盖一个交互页的函数链
 * （BeginFlow 类页面的调用链深度×宽度实测在几十以内，见交付报告；超出部分被挤掉是
 * 设计行为 —— 保新弃旧）。TODO(verify)：真实「一页进帧数」分布需要 vmdbg 流水采样确认。
 *
 * ## 线程模型
 *
 * 单写线程：`record` / `clear` 都只发生在 VM 线程（进帧在 `VirtualMachine` 主循环；
 * 页边界清零在 `QueueInputSink.waitInput`/`readAnyKey` 的进入段，同样是脚本线程）。
 * 读侧两类：
 * 1. **同线程读**（主消费者：页内压力检查点 `Vm.kt:953 host.pressureCheckpoint` →
 *    `ProcessRuntime.onPressureCheckpoint`（`ProcessRuntime.kt:1287`，VM 线程同步回调））：
 *    逐字确定 —— 读到的就是此刻的流水，无可见性问题。
 * 2. **跨线程读**（app 主线程 longturn 钉行 / DevTools 通道）：`next`/`size` 是
 *    @Volatile，数组写先于 volatile 写 ⇒ 读者经 happens-before 至少看到已发布的条目；
 *    但环绕覆盖与清零并发时，一次 readout 可能是「撕裂的近似快照」（个别槽新旧混合、
 *    长度 ±1 误差）。口径 = **尽力而为**：读数只用于人工定位，绝不参与判定；
 *    读侧**禁抛**（解析器异常/越界一律降级为 `#<下标>` 占位）。
 *
 * 禁静态可变：实例挂在宿主（`Vm.pageTrail` / `QueueInputSink.pageTrail`）上，
 * 与 `FunctionProbe`/`StatementProbe` 的「实例字段 + 宿主接线」同口径（ADR-003 纯 JVM）。
 */
class PageFunctionTrail(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        // 掩码取模要求 2 的幂；构造期校验一次，热路径因此不需要除法。
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "容量必须是 2 的幂（$capacity）：环形写入用掩码不用取模"
        }
    }

    private companion object {
        /** 默认容量：最近 64 次进帧（取舍依据见类 KDoc 的容量节）。 */
        const val DEFAULT_CAPACITY = 64
    }

    /** 环形槽：存 `program.functions` 下标；构造期一次性分配，之后零分配。 */
    internal val ring = IntArray(capacity)

    private val mask = capacity - 1

    /** 下一个写入位（仅 VM 线程写；@Volatile 只为跨线程读者的可见性）。 */
    @Volatile
    private var next = 0

    /** 当前有效条数（封顶 [capacity]；清零页边界时归 0）。 */
    @Volatile
    private var count = 0

    /** 函数下标 → 展示名；读侧注入，null/异常/越界降级为 `#<下标>`。 */
    @Volatile
    private var nameResolver: ((Int) -> String?)? = null

    /** 当前流水条数（观察用，封顶 [capacity]）。 */
    val size: Int get() = count

    /** 绑定名字解析器（装配期一次即可；@Volatile ⇒ 装配晚于 VM 线程启动也可见）。 */
    fun bindNameResolver(resolver: (Int) -> String?) {
        nameResolver = resolver
    }

    /**
     * 记录一次进帧（**热路径唯一入口**，只允许 VM 线程调）。
     *
     * 写入 = 一次数组存 + 两次 volatile 存（`next`、`count`）；满环后新值覆盖最旧。
     * 不判 [funcIndex] 合法性：三处调用点（`Vm.run`/`callInto`/`jumpInto`）的 funcStack
     * 写入本就发生在各自的越界守卫之后，写进来的下标必然在界内；读侧另有降级兜底。
     */
    fun record(funcIndex: Int) {
        val n = next
        ring[n] = funcIndex
        next = (n + 1) and mask
        val c = count
        if (c < capacity) count = c + 1
    }

    /**
     * 页边界清零（只允许在 `QueueInputSink` 的等待点结算处、VM 线程调用）。
     * 与 [record] 同线程 ⇒ 无需互斥；跨线程读者最坏看到「清零瞬间的部分旧值」，
     * 属尽力而为口径（类 KDoc 线程模型节）。
     */
    fun clear() {
        next = 0
        count = 0
    }

    /**
     * 读数：`最旧 → 最新` 的「本页依次进过的函数」单行串，形如
     * `BEGIN_FLOW → ERH_MAIN → CHARA_MOVEMENT`；空流水返回空串。
     *
     * 名字解析发生且仅发生在这里（热路径只存下标，理由见类 KDoc「口径」节）。
     * **禁抛**：解析器抛异常按 `#<下标>` 降级；volatile 读各取一次 ⇒ 快照至多撕裂、
     * 不死循环不越界（槽位用 `and mask` 收敛在界内）。
     */
    fun readout(): String {
        val c = count
        if (c == 0) return ""
        val n = next
        val resolver = nameResolver
        val filled = if (c == capacity) capacity else c
        val from = if (c == capacity) n and mask else 0
        val sb = StringBuilder(c * 16)
        for (i in 0 until filled) {
            if (i > 0) sb.append(" → ")
            val idx = ring[(from + i) and mask]
            sb.append(resolveName(resolver, idx))
        }
        return sb.toString()
    }

    private fun resolveName(resolver: ((Int) -> String?)?, idx: Int): String {
        if (resolver == null) return "#$idx"
        val name = try {
            resolver(idx)
        } catch (_: Throwable) {
            null
        }
        return name ?: "#$idx"
    }
}
