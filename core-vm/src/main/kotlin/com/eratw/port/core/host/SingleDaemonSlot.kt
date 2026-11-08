package com.eratw.port.core.host

/**
 * 进程级「单代常驻 daemon 线程」槽（CMP-17 的接缝，纯 JVM、无 Android 依赖）。
 *
 * ## 它修掉的是什么
 *
 * `DevToolsHost` 原来用进程级的 `started` CAS 决定「只起一次」，而 `running` 没有任何
 * 复位位：`Activity.recreate()`（字号档位变化 `MainActivity` 那条、fontScale/density/locale、
 * 开发者「不保留活动」）之后新的 `GameHost`/`View` 调 `startOnce` 会被 CAS 拒掉，
 * 轮询线程仍然持有**第一代** `host` + `view` —— 每次重建泄漏 1× Activity + View 树，
 * 而且调试命令永远打在已经销毁的会话上。
 *
 * 本类把「起代」与「换代」拆开：
 * - [start]：只有当前线程已经死了才允许起新一代（CAS 语义仍在：未 [stop] 时重复 start 恒 false，
 *   且**不调** [create]，方便宿主把 `startOnce` 保持成可反复调用的幂等入口）；
 * - [stop]：interrupt + join（有上限），并把 [target]/[worker] 一起清空 ⇒ 旧 `host`/`view`
 *   不再被静态引用钉住，下一次 [start] 起的是第二代对象。
 *
 * ## 对 body 的契约
 *
 * [body] 必须**睡到被打断为止**（与 `DevToolsHost.loop` 同款：`Thread.sleep` 抛
 * `InterruptedException` 即返回）。本类不额外维护 `running` 布尔位：线程存活状态就是
 * 唯一事实（`running` 位与线程实际状态反正是两个可能分叉的真相，CMP-17 就是这个分叉）。
 *
 * 线程是 daemon、最低优先级（与生产里的调试轮询一致：它只做 500 ms 一次的文件轮询，
 * 抢不到 CPU 也不影响脚本线程）。
 */
class SingleDaemonSlot<T : Any>(private val threadName: String) {

    /** 当前代的宿主侧对象；null = 没有活着的代（[stop] 之后、或还没 [start]）。 */
    @Volatile
    var target: T? = null
        private set

    @Volatile
    private var worker: Thread? = null

    /** 已起的代次（0 = 还没起过；诊断读数）。 */
    @Volatile
    var generation: Int = 0
        private set

    /**
     * 当代线程的**非预期死亡**读数（W8 刀五：守护线程静默死亡不再无声无息）。
     *
     * [body] 契约是「睡到被打断」——正常 [stop] 的打断路不走这里（body 吞掉
     * `InterruptedException` 后干净返回）。只有 body 抛出未捕获异常把线程带死时，
     * [Thread.UncaughtExceptionHandler] 才把异常记进本字段；下一次 [start] 清零。
     * 纯 JVM、无静态全局：只是实例上的诊断读数，宿主要不要上报告志归调用方。
     */
    @Volatile
    var lastFailure: Throwable? = null
        private set

    private val lock = Any()

    /** 槽里的线程是否还活着（CMP-17 要求 stop 之后必须为 false）。 */
    val isRunning: Boolean get() = worker?.isAlive == true

    /**
     * 取一次即清地把当代线程的非预期死亡读数交出去（FEEL1/L2：把「后台 daemon 静默死」接进错误报告）。
     *
     * 结的病：[lastFailure] 此前**全仓无人取**，而「下一次 [start] 清零」意味着只要没人赶在两代
     * 之间读，那条死就被起代动作抹掉。本函数就是那个「有人来取」的出口。
     *
     * 与 [lastFailure] 的区别只有一个但关键：[lastFailure] 是**读数**（一直挂着到下一次 [start]
     * 才清），本函数是**消费**——返回当前值并立刻复位 null。报告侧每生成一份就 drain 一次，同一条
     * 死只出声一次，不会在后续每一份报告里被重播（防重复上报）。读 + 清在 [lock] 内成一个原子
     * 动作：两个消费点也不会把同一条死各取一次。纯 JVM、无静态全局：只动本实例上的 [lastFailure]。
     */
    fun drainLastFailure(): Throwable? = synchronized(lock) {
        val failure = lastFailure
        if (failure != null) lastFailure = null
        failure
    }

    /**
     * 起一代线程（幂等）：当前线程还活着就返回 false，且**不调** [create]。
     *
     * [create] 只在真的要起线程时调用一次（把 `hold host/view` 的时机压在起线程这一帧里），
     * [body] 在新线程里跑。
     */
    fun start(
        create: () -> T,
        body: (T) -> Unit,
    ): Boolean {
        synchronized(lock) {
            val live = worker
            if (live != null && live.isAlive) return false
            val created = create()
            // 代次先落账再建线程（FEEL1/L2）：非预期死亡的留痕（[drainCapturedFailures] 环里
            // 那条）要带「死的是第几代」，而起代与死可能只隔一瞬，晚自增会把第一代记成第 0 代。
            generation++
            val gen = generation
            lastFailure = null
            val thread = Thread({ body(created) }, threadName).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    lastFailure = e
                    captureDeath(gen, e)
                }
            }
            target = created
            worker = thread
            thread.start()
            return true
        }
    }

    /**
     * 死因进进程级有界环（[drainCapturedFailures] 读的那张）；整段兜住、**永不抛**。
     *
     * 为什么环挂在 companion 而不是只留 [lastFailure]：[start] 会把上一代死因清零，两代之间没有
     * 消费者证据就没了（本波要结的病）；而 app 侧 `ErrorReportCollector` 拿不到宿主私有的槽实例
     * （`DevToolsHost.kt:564`，归组长），所以「可直接接线」只有一条路——**死亡当场在 core 侧
     * 留一条裁剪后的短文本**，采集侧 [drainCapturedFailures] 取一次即清。与 [drainLastFailure]
     * 不冲突：宿主愿意显式消费时走实例口，环是无宿主参与的兜底。
     *
     * handler 跑在**正在死的这条线程**上：留痕装置自己再抛只是把静默死换成 noisy 死，零取证收益，
     * 所以取类名/消息/栈顶各自 runCatching，最差也是一行「不可读」的记录。入表只有短字符串
     * （长度闸见 companion），异常对象图不进环——环活过代次更替，攥着 Throwable 就是攥着引用图。
     */
    private fun captureDeath(gen: Int, error: Throwable) {
        runCatching {
            val row = DaemonFailure(
                threadName = threadName,
                generation = gen,
                errorClass = runCatching { error.javaClass.name }.getOrDefault("类名不可读"),
                message = clip(
                    runCatching { error.message }.getOrNull(),
                    MESSAGE_MAX_CHARS,
                    fallback = "",
                ),
                topFrame = clip(
                    runCatching { error.stackTrace?.firstOrNull()?.toString() }.getOrNull(),
                    FRAME_MAX_CHARS,
                    fallback = "无栈可读",
                ),
            )
            synchronized(deathRing) {
                deathRing.addLast(row)
                while (deathRing.size > FAILURE_RING) deathRing.removeFirst()
            }
        }
    }

    /**
     * 收口当代线程：interrupt + join（上限 [timeoutMs]），成功收口后清空 [target]/[worker]。
     *
     * @return true = 线程已经死了（含「本来就没起过」）；false = 超时仍活着
     *   （此时**不**清引用：清掉就等于把还活着的线程连同它的 host/view 变成孤儿）。
     */
    fun stop(timeoutMs: Long = DEFAULT_JOIN_MS): Boolean {
        val thread = synchronized(lock) { worker } ?: return true
        thread.interrupt()
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val dead = !thread.isAlive
        if (dead) {
            synchronized(lock) {
                if (worker === thread) {
                    worker = null
                    target = null
                }
            }
        }
        return dead
    }

    companion object {
        /** [stop] 的缺省等待上限（毫秒）：判据是「1 s 内死」，与 JVM 单测同一读数。 */
        const val DEFAULT_JOIN_MS: Long = 1_000L

        /**
         * 进程级死亡留痕环的容量上限（**写死**，契约 §0 第 10 条）。
         *
         * 取 8 的算术理由：本波在册用 [SingleDaemonSlot] 的常驻线程 1 路（`eratw-devtools`，
         * `DevToolsHost.kt:564`），契约点名「资产解包 / 图形预取」两类将来同形状 ⇒ 3 路在册；
         * 一路在「死 → 采集」窗口内最多攒 2 代（上一代死、采集前又起又死），3 × 2 = 6，
         * 向上收整到 2 的幂 = **8**。环里每条只有裁剪后的短字符串（单条 ≤ 约 420 字符 ⇒
         * 满环 < 4 KB 硬顶），读侧 [drainCapturedFailures] 取一次即清，环不随时间增长。
         * 环形口径与 core 的 `SaveDiagnostics`（RING=16 的进程级诊断留痕环）同源：满丢最旧。
         */
        const val FAILURE_RING: Int = 8

        /** 单条记录里消息文本的硬顶字符数（死那一刻就压短，不攥对象图）。 */
        const val MESSAGE_MAX_CHARS: Int = 160

        /** 单条记录里栈顶帧文本的硬顶字符数。 */
        const val FRAME_MAX_CHARS: Int = 120

        /** 环本体：进程级唯一，只由 [captureDeath] 写、[drainCapturedFailures] 读清。 */
        private val deathRing = ArrayDeque<DaemonFailure>()

        /**
         * 取一次即清地交出自上次 drain 以来所有守护线程的非预期死亡记录（旧在前）。
         *
         * 「取一次即清」是防重复上报的闸：app 侧 `ErrorReportCollector` 每生成一份报告调一次，
         * 同一条死只进一份报告、不逐份重播。空环返回空表（调用方整节不写）。
         */
        fun drainCapturedFailures(): List<DaemonFailure> = synchronized(deathRing) {
            if (deathRing.isEmpty()) return emptyList()
            val out = deathRing.toList()
            deathRing.clear()
            out
        }

        /** 清空留痕环（单测收口用；生产不需要，与 `SaveDiagnostics.clear` 同口径）。 */
        fun clearCapturedFailures() {
            synchronized(deathRing) { deathRing.clear() }
        }

        /** 定长裁剪：null 或空白给 fallback；超长截断加省略号。永不抛。 */
        private fun clip(text: String?, maxChars: Int, fallback: String): String {
            val t = text?.takeIf { it.isNotBlank() } ?: return fallback
            return if (t.length <= maxChars) t else t.take(maxChars) + "…"
        }
    }
}

/**
 * 守护线程非预期死亡的一条留痕记录（[SingleDaemonSlot.captureDeath] 在**死那一刻**压成短字符串）。
 *
 * 刻意不持有 Throwable：环活过代次更替与采集间隙，攥着异常对象就是攥着它的整棵引用图
 * （报告可能在堆将尽时生成，这是本仓 OOM 取证反复付过的学费）。字段全部定长裁剪，
 * 长度闸见 [SingleDaemonSlot] companion。渲染成报告行在 app 侧（`ErrorReportCollector`）。
 */
data class DaemonFailure(
    /** 槽的线程名（起槽时给定，如 `eratw-devtools`）。 */
    val threadName: String,
    /** 死的是第几代（[SingleDaemonSlot.generation] 的当场读数）。 */
    val generation: Int,
    /** 异常类全名；读不出写「类名不可读」。 */
    val errorClass: String,
    /** 异常消息（裁剪到 [SingleDaemonSlot.MESSAGE_MAX_CHARS]；空串 = 消息读不出）。 */
    val message: String,
    /** 栈顶帧文本（裁剪到 [SingleDaemonSlot.FRAME_MAX_CHARS]；无栈写「无栈可读」）。 */
    val topFrame: String,
)
