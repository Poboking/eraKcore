package com.eratw.port.core.host

/**
 * 一次性的并行装载任务（CMP-18 的接缝，纯 JVM、无 Android 依赖）。
 *
 * ## 它修掉的是什么
 *
 * `GameHost.ParallelMeta` 原来只有 `await()` 的 `join()`：装载中途 `close()`（Activity 销毁、
 * 装载失败、改字号重建）时它只 interrupt VM 线程，**从不碰** `eratw-meta`。
 * 于是被遗弃的 meta 线程还在解 49 MB 侧车，新一代装载又起一条 —— 两条线程同时把侧车读进堆，
 * 正是宿主注释里自警的「第二次必然 OOM」。本类补上 [cancel]：interrupt + **join（有上限）**，
 * 让「线程已经死了」成为可断言的返回值，而不是靠时序赌。
 *
 * ## 为什么 join 必须有上限
 *
 * [cancel] 在 UI 线程（`GameHost.close`）上被调用：无上限的 join 会把一次侧车解析
 * （真机 ~8 s）原样加到 `onDestroy` 上，够 ANR。上限到了就返回 false，让宿主把这条读数
 * 落日志（谁还在跑、跑哪一段），而不是静默把线程丢掉。
 *
 * ## 与 [SingleDaemonSlot] 的分工
 *
 * 本类是一次性任务（装载期的 meta 解析，一辈子一次）；[SingleDaemonSlot] 是跨代常驻线程
 * （调试轮询，换代不叠线程）。两者都不持有静态可变全局。
 */
class ParallelTask<T : Any>(
    private val threadName: String,
    private val body: () -> T,
) {
    @Volatile
    private var result: T? = null

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var started = false

    private val lock = Any()

    private val worker: Thread = Thread(object : Runnable {
        override fun run() {
            try {
                result = body()
            } catch (t: Throwable) {
                // 装载层的异常口径原样保留（宿主 await 处再决定是失败屏还是回落同步读）。
                failure = t
            }
        }
    }, threadName).apply { isDaemon = true }

    /** 线程是否还活着（宿主 close 的读数：见 `GameHost.close`）。 */
    val isAlive: Boolean get() = worker.isAlive

    /** 是否已经起过且已经结束。 */
    val isDone: Boolean get() = started && !worker.isAlive

    /** 起线程（只允许一次；与 [cancel] 互斥在 [lock] 上）。 */
    fun start(): ParallelTask<T> {
        synchronized(lock) {
            check(!started) { "$threadName: 同一条任务只能 start 一次" }
            started = true
            worker.start()
        }
        return this
    }

    /**
     * 等任务结束并取结果；body 抛出的异常原样抛出（与同步读的报错口径一致）。
     *
     * 返回 null 只会发生在「body 正常返回 null」——本类泛型限定 `T : Any`，所以正常路径恒非 null。
     */
    fun await(): T? {
        worker.join()
        failure?.let { throw it }
        return result
    }

    /**
     * 收口：interrupt + join（上限 [timeoutMs]）。
     *
     * @return true = 线程已经结束（含「本来就已经结束」）；false = 超时仍在跑
     *   （此时线程是 daemon，随进程退出；宿主应把这条读数落日志）。
     */
    fun cancel(timeoutMs: Long = DEFAULT_JOIN_MS): Boolean {
        worker.interrupt()
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !worker.isAlive
    }

    companion object {
        /** [cancel] 的缺省等待上限（毫秒）。 */
        const val DEFAULT_JOIN_MS: Long = 1_000L
    }
}
