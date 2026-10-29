package com.eratw.port.perf

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * 堆读数装置（只测量，不改语义）。
 *
 * 口径：`Runtime.totalMemory() - freeMemory()` = 已提交且尚未释放的堆字节，**包含尚未
 * 回收的垃圾**。因此每次读数前连打 [rounds] 轮 `System.gc()` 并取**最小值**——最小值
 * 对应「垃圾尽量被清掉之后仍然活着的那部分」，即本报告所说的「常驻」。
 *
 * 为什么不用 `MemoryPoolMXBean.used`：G1 的 eden/survivor 池在两次 GC 之间会把 used
 * 抬到容量上限，读数没有意义；`Runtime` 口径是整堆，且与 `bootSmoke` 报告的 `used`
 * （`GameBootSmoke.kt:368`）同源，便于跨装置对照。
 *
 * 显式 GC 在默认 JVM 上不被禁用（本装置不传 `-XX:+DisableExplicitGC`）；
 * 若宿主环境禁用了显式 GC，[settled] 会退化成「当前 used」，报告里 `S0` 的
 * 最小值/最大值展布可用于识别这种失真。
 */
internal object HeapMeter {

    /** GC 轮数：轮数越多最小值越稳，耗时线性增长（ERATW_PERF_ROUNDS，默认 5）。 */
    val rounds: Int = (System.getenv("ERATW_PERF_ROUNDS")?.toIntOrNull() ?: 5).coerceIn(1, 20)

    /** 每轮 GC 之后留出的回收时间（毫秒）。 */
    private const val SETTLE_MS = 60L

    fun used(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    fun max(): Long = Runtime.getRuntime().maxMemory()

    fun settled(): Long = settle().first

    /**
     * 连续 [rounds] 轮 GC 的读数，返回 `(最小值, 最大值, 全部读数)`。
     * 最小值 = 常驻估计；最小值与最大值的差 = 测量噪声（G1 大对象区残留、JIT 元数据等）。
     */
    fun settle(): Triple<Long, Long, List<Long>> {
        val all = ArrayList<Long>(rounds)
        repeat(rounds) {
            System.gc()
            Thread.sleep(SETTLE_MS)
            all.add(used())
        }
        return Triple(all.minOrNull() ?: 0L, all.maxOrNull() ?: 0L, all)
    }

    /** 清空各堆池的 peakUsage，使下一次 [peak] 只覆盖本段。 */
    fun resetPeaks() {
        runCatching {
            ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .forEach { it.resetPeakUsage() }
        }
    }

    /**
     * 堆池 peak 之和。JVM 没有全局峰值计数器，取各 heap pool 的 `peakUsage.used` 求和
     * （与 `GameBootSmoke.kt:538-542` 同口径）。G1 下该值是「上次 GC 时的池占用峰值」，
     * 对短促尖峰（< 一次 GC 周期）会低估，故报告同时给出 `settled` 与 `peak` 两个数。
     */
    fun peak(): Long = runCatching {
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP }
            .sumOf { it.peakUsage?.used ?: 0L }
    }.getOrDefault(0L)

    fun gcNames(): String = runCatching {
        ManagementFactory.getGarbageCollectorMXBeans().joinToString(" + ") { it.name }
    }.getOrDefault("?")

    fun loadedClasses(): Int = runCatching {
        ManagementFactory.getClassLoadingMXBean().loadedClassCount
    }.getOrDefault(-1)

    fun inputArgs(): List<String> = runCatching {
        ManagementFactory.getRuntimeMXBean().inputArguments
    }.getOrDefault(emptyList())

    fun jvmName(): String = runCatching {
        "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")} " +
            "(java ${System.getProperty("java.version")})"
    }.getOrDefault("?")

    fun osName(): String =
        "${System.getProperty("os.name")} ${System.getProperty("os.arch")} / " +
            "${Runtime.getRuntime().availableProcessors()} cores"
}
