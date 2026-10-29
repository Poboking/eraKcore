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

    private val lock = Any()

    /** 槽里的线程是否还活着（CMP-17 要求 stop 之后必须为 false）。 */
    val isRunning: Boolean get() = worker?.isAlive == true

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
            val thread = Thread({ body(created) }, threadName).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            target = created
            worker = thread
            generation++
            thread.start()
            return true
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
    }
}
