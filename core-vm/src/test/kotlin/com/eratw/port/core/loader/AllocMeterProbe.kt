package com.eratw.port.core.loader

import java.lang.management.ManagementFactory

/**
 * 线程分配计数探针（测试装置，只测量，不改判定）。
 *
 * 原先五份逐字重复的私有 `AllocMeter`（MappedCodeSourceTest / ErbcStreamPeakTest /
 * ErbcStreamAllocProfileTest / LazyStatementDecodeTest / StreamLoadTest）收敛为这一份；
 * 尺子不变：`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 的线程累计分配
 * 字节，用例取前后增量。
 *
 * ## 三档失败原因（改前 runCatching 把三者塌缩成同一个 null）
 *
 * [open] 取不到计数器时把原因分三档记进 [Opened.failureReason]，skip / 失败消息里能
 * 直接看出是哪一档：
 *
 * - `机制不支持：`——管理接口不是 `com.sun.management.ThreadMXBean`（非 Hotspot 系
 *   JVM），或 `isThreadAllocatedMemorySupported == false`。对应改前的两条显式
 *   `return null`。
 * - `未启用：`——`setThreadAllocatedMemoryEnabled(true)` 投掷（机制声称支持但开不起来，
 *   见于受限 / 代理管理环境）。对应改前被 runCatching 吞掉的 enable 步异常。
 * - `投掷：`——`getThreadMXBean()` 本身投掷（如 SecurityException），或 enable 成功后
 *   探针调用 `getThreadAllocatedBytes` 投掷（管理代理未实现该方法时抛
 *   UnsupportedOperationException，ErbcStreamPeakTest 原行内注释记录过这一形态）。
 *   原话（异常类型 + 消息）原样缀在档名后面。
 *
 * ## 与改前的判定等价
 *
 * 三档最终都表现为 [Opened.meter] 为 null，由调用方既有的 assumeTrue / requireNotNull
 * 处置；本类不 skip、不断言、不伪造读数。刻意**不**新增改前没有的检测：enable 之后
 * 不读回开关、探针读数为 -1 也照常交付 meter——改前这类宿主会带着 -1 跑进用例自身的
 * 断言里炸出来，这里若改判 skip 就变了判定。操作顺序与改前逐句同构（getThreadMXBean
 * → 接口判定 → 支持判定 → enable → 探针一次 → 交付），成功路径不新增任何调用。
 *
 * skip / 失败消息里直接插值 [Opened.failureReason] 即可：该消息只在失败路径被渲染
 * （assumeTrue 只在条件不成立时出消息，requireNotNull 的 lambda 只在 null 时求值）。
 */
internal class AllocMeterProbe private constructor(
    private val bean: com.sun.management.ThreadMXBean,
    private val tid: Long,
) {
    /** open 时刻调用线程的累计分配字节（前后两次 read 的差即该段代码的分配量）。 */
    fun read(): Long = bean.getThreadAllocatedBytes(tid)

    /** [open] 的结果：[meter] 非 null 当且仅当 [failureReason] 为 null。 */
    internal class Opened internal constructor(
        /** 成功时为可用计数器；失败时为 null，由调用方按既有处置 skip / requireNotNull。 */
        val meter: AllocMeterProbe?,
        /** 三档失败原因（机制不支持 / 未启用 / 投掷），只在失败路径的消息里被渲染。 */
        val failureReason: String?,
    )

    companion object {
        fun open(): Opened = try {
            val bean = ManagementFactory.getThreadMXBean()
            if (bean !is com.sun.management.ThreadMXBean) {
                Opened(null, "机制不支持：管理接口不是 com.sun.management.ThreadMXBean（实为 ${bean.javaClass.name}）")
            } else if (!bean.isThreadAllocatedMemorySupported) {
                Opened(null, "机制不支持：isThreadAllocatedMemorySupported=false")
            } else {
                val enableError: Throwable? = try {
                    bean.isThreadAllocatedMemoryEnabled = true
                    null
                } catch (t: Throwable) {
                    t
                }
                if (enableError != null) {
                    Opened(null, "未启用：setThreadAllocatedMemoryEnabled(true) 投掷 $enableError")
                } else {
                    try {
                        val tid = Thread.currentThread().id
                        // 探一次：代理未实现该方法时会抛 UnsupportedOperationException
                        bean.getThreadAllocatedBytes(tid)
                        Opened(AllocMeterProbe(bean, tid), null)
                    } catch (t: Throwable) {
                        Opened(null, "投掷：getThreadAllocatedBytes 抛出 $t")
                    }
                }
            }
        } catch (t: Throwable) {
            // getThreadMXBean() 本身投掷：改前 runCatching 同样吞成 null，这里分档透传
            Opened(null, "投掷：getThreadMXBean 抛出 $t")
        }
    }
}
