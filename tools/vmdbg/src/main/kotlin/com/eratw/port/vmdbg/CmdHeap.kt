package com.eratw.port.vmdbg

import java.io.PrintStream

/**
 * `heap` 子命令（obs-heap512）：把本进程真实拿到的 JVM 挡位打出来。
 *
 * 存在的唯一理由是**挡位透传自证**：`devtools/desktop.py --xmx 512m` 只是往 fork 出的命令行里
 * 拼一个 `-Xmx512m`，脚本自己读不到子进程的堆；由子进程自己打印 `Runtime.getRuntime().maxMemory()`
 * 才能证明挡位真的落到了被测进程，而不是只传给了 Python。512m 挡位下
 * `jvmMaxHeapBytes` 应为 536870912（ART `largeHeap` 的 `growth limit` 同值）。
 *
 * 输出字段口径（全部是本进程 `Runtime` 的即时读数，不含 GC 校正）：
 * - `jvmMaxHeapBytes` / `jvmMaxHeapMiB`：`-Xmx` 实际生效值；
 * - `jvmTotalMiB` / `jvmUsedMiB`：committed 与 `committed - free`（与浸泡采样同一口径）；
 * - `jvmVersion` / `jvmName`：JDK 版本与 VM 名，便于跨机对照时区分是不是同一套 JVM。
 */
object CmdHeap {

    private const val MIB = 1024L * 1024L

    fun run(out: PrintStream): Int {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val total = rt.totalMemory()
        val used = total - rt.freeMemory()
        out.println("jvmMaxHeapBytes=$max")
        out.println("jvmMaxHeapMiB=${max / MIB}")
        out.println("jvmTotalMiB=${total / MIB}")
        out.println("jvmUsedMiB=${used / MIB}")
        out.println("jvmVersion=${Runtime.version()}")
        out.println("jvmName=${System.getProperty("java.vm.name") ?: "-"}")
        return 0
    }
}
