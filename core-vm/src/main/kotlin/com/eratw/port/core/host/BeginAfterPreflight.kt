package com.eratw.port.core.host

/**
 * 「堆预检闸 → 允许起并行任务」的两步动作序（CMP-18 的接缝，纯 JVM、无 Android 依赖）。
 *
 * ## 它修掉的是什么
 *
 * `GameHost.loadAndRun` 原来的顺序是「构造 `ParallelMeta`（构造即起线程）→ 判堆预检 →
 * 拒绝就上失败屏 return」：拒绝分支 return 时 `eratw-meta` 已经在解 49 MB 侧车，
 * 违背「预检不足就不进入解码把堆打碎」的既有不变量（弱机上就是失败屏 + 后台 OOM）。
 *
 * 本类把顺序钉成状态机：闸拒绝（[reject]）之后 [begin] 恒返回 null、且**一次都不调**
 * `create`；只有放行路径才会起线程。[begin] 自身幂等（重复调用返回同一实例），
 * 因为 `close()` 与新装载可能读同一个句柄。
 *
 * 顺序由**调用点的语句位置**保证（`GameHost.loadAndRun` 里 `reject()` 与 `return` 相邻，
 * `begin()` 在闸之后），本类保证的是「即使调用点写错顺序，拒绝后也不可能起线程」。
 */
class BeginAfterPreflight<T : Any>(private val start: () -> T) {
    private val lock = Any()
    private var rejected = false
    private var begun: T? = null

    /** 闸是否已经拒绝（诊断读数：失败屏路径应当为 true）。 */
    val isRejected: Boolean get() = synchronized(lock) { rejected }

    /** 是否已经起过并行任务。 */
    val isBegun: Boolean get() = synchronized(lock) { begun != null }

    /** 闸拒绝：之后 [begin] 永不起线程（幂等）。 */
    fun reject() {
        synchronized(lock) { rejected = true }
    }

    /**
     * 闸放行路径上起并行任务；幂等。
     *
     * @return null = 闸已拒绝（调用方不许再往下走）
     */
    fun begin(): T? {
        synchronized(lock) {
            if (rejected) return null
            begun?.let { return it }
            val created = start()
            begun = created
            return created
        }
    }
}
