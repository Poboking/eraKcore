package com.eratw.port.core.code

/**
 * 分片惰性扁平池（[SegmentedFlatStmtSource]）的**内存预算**：片数上限、片容量、两笔字节读数。
 *
 * 纯 JVM 判式，不读任何进程读数（堆上限与档位由调用方传进来）：[Program.ensureFlatStmtSource]
 * 用 `Runtime.getRuntime().maxMemory()` + [com.eratw.port.core.host.MemoryTier.isGenerous] 喂它，
 * [com.eratw.port.core.loader.ProgramLoader.estimateHeap] 用**判定用的同一个堆读数**喂它 ——
 * 两处判式必须同源，否则会出现「预检按注入的堆记账、运行期按真实堆建池」两张皮
 * （口径与 `ProgramLoader.estimateHeap` 的 `maxMemoryOverride` 注释同一条）。
 *
 * ## 两个字节读数分工（不许互相顶替）
 *
 * - [residentBytes] = **片缓存份额**，可推导、可调：[maxSegments] × [SEGMENT_ESTIMATED_BYTES]
 *   （片内平均 ≈0.94 MB，向上取整到 1 MiB，见 `SegmentedFlatStmt.kt:48`）。
 * - [reserveBytes] = **实测总增量上界**，**不随 [maxSegments] 缩放**。2026-10-29 真机读数
 *   （vivo V2199A / 512 MiB，干净窗口协议）关池 141.9–149.5 MiB、开池（64 片）256.2–260.7 MiB
 *   ⇒ 增量 ≈114 MiB；而 32 片 260.7 MiB、64 片 260.5–260.7 MiB、1,024 条/片 246.8–255.8 MiB
 *   三组同量级（`SegmentedFlatStmt.kt:52-61`）⇒ **这 114 MiB 的大头是执行路径固定开销、
 *   不是片缓存，调参无效**。按片数去缩这笔预留就是少报。
 *
 * ## 判式（逐条钉死）
 *
 * - 堆上限 < [MIN_HEAP_BYTES]（512 MiB）⇒ [DISABLED]：`maxSegments=0`、两个字节读数都是 0。
 *   理由：256 MiB 挡是产品护栏（`ProgramLoader.kt:426`：该挡位游玩期连图像预算都放不下），
 *   预检在那挡恒判不足，池本来就不会被建。
 * - 否则 `maxSegments = 64`（放开档 [GENEROUS_MAX_SEGMENTS]）/ `32`（保守档
 *   [CONSERVATIVE_MAX_SEGMENTS]），`segmentStatements = 4096`，
 *   [residentBytes] = 片数 × 1 MiB，[reserveBytes] = [MEASURED_INCREMENT_BYTES]。
 *
 * 降级口径不在本类：预算 [enabled] 为假、但调用方仍显式开了 flat 时，
 * [Program.ensureFlatStmtSource] 降到 1 片（不拒绝构建）—— 那是「显式开关的语义优先于
 * 缓存容量」这一条策略，写在那边的 KDoc 里。
 *
 * @property maxSegments 同时驻留的片数上限（LRU，`SegmentedFlatStmt.kt` 的 `trim()`）；0 = 不开放
 * @property segmentStatements 每片语句数（与 [SEGMENT_STATEMENTS] 同值；显式带上是为了让
 *   预算自己说清「按什么粒度切片」，不靠构造点再抄一遍常量）
 * @property residentBytes 片缓存份额（诊断/报告面读数，不参与预检）
 * @property reserveBytes 预检要记的**总增量预留**（[com.eratw.port.core.loader.ProgramLoader.estimatedHeapBytes]）
 */
data class FlatPoolBudget(
    val maxSegments: Int,
    val segmentStatements: Int,
    val residentBytes: Long,
    val reserveBytes: Long,
) {

    /** 是否开放分片池（[maxSegments] > 0）。 */
    val enabled: Boolean get() = maxSegments > 0

    companion object {

        /** 每片语句数：与 `SegmentedFlatStmt.kt` 的 `DEFAULT_SEGMENT_STATEMENTS` 同值（4,096）。 */
        const val SEGMENT_STATEMENTS: Int = 4096

        /**
         * 片内平均常驻的向上取整读数：真语料 171.7 万条 ⇒ 420 片，整池 396 MiB 摊薄 ≈0.94 MB
         * （`SegmentedFlatStmt.kt:48`），这里取 1 MiB 留余量。
         */
        const val SEGMENT_ESTIMATED_BYTES: Long = 1024L * 1024L

        /**
         * 真机实测开池增量（上界）：vivo V2199A / 512 MiB / 2026-10-29，关池 141.9–149.5 MiB、
         * 开池 256.2–260.7 MiB ⇒ ≈114 MiB（`SegmentedFlatStmt.kt:52-61`）。
         *
         * 换机重标是 TODO(verify)：桌面读数不能冒充真机（同机同产物，干净窗口协议）。
         */
        const val MEASURED_INCREMENT_BYTES: Long = 114L * 1024L * 1024L

        /** 保守档片数上限（堆 >= 512 MiB、档位不放开时）。 */
        const val CONSERVATIVE_MAX_SEGMENTS: Int = 32

        /** 放开档片数上限（`MemoryTier.isGenerous` 为真时）。 */
        const val GENEROUS_MAX_SEGMENTS: Int = 64

        /** 开放分片池的堆下限：低于它一律 [DISABLED]（256 MiB 挡是产品护栏）。 */
        const val MIN_HEAP_BYTES: Long = 512L * 1024L * 1024L

        /** 不开放：片数 0、两个字节读数 0（预检在那笔堆上不加预留）。 */
        val DISABLED: FlatPoolBudget = FlatPoolBudget(
            maxSegments = 0,
            segmentStatements = SEGMENT_STATEMENTS,
            residentBytes = 0L,
            reserveBytes = 0L,
        )

        /**
         * 按堆上限与档位判一次预算（纯函数，无进程读数）。
         *
         * @param maxMemoryBytes 判定用的堆上限（生产 = `Runtime.maxMemory()`；测试可注入）
         * @param generous 是否放开档（[com.eratw.port.core.host.MemoryTier.isGenerous] 的读数，
         *   **必须**按同一个 `maxMemoryBytes` 判 —— 见 [com.eratw.port.core.loader.ProgramLoader.estimateHeap]）
         */
        fun forHeap(maxMemoryBytes: Long, generous: Boolean): FlatPoolBudget {
            if (maxMemoryBytes < MIN_HEAP_BYTES) return DISABLED
            val segments = if (generous) GENEROUS_MAX_SEGMENTS else CONSERVATIVE_MAX_SEGMENTS
            return FlatPoolBudget(
                maxSegments = segments,
                segmentStatements = SEGMENT_STATEMENTS,
                residentBytes = segments * SEGMENT_ESTIMATED_BYTES,
                reserveBytes = MEASURED_INCREMENT_BYTES,
            )
        }
    }
}
