package com.eratw.port.perf

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「语句层单点写接站点句柄 + 下标借还池」的微基准（PERF 2026-10-27）。
 *
 * ## 为什么要单独量
 *
 * `SET X = …` 占这个负载**语句执行的 99.9%**（BOARD 第七轮：`<语句合计>` 23.63% ≈ `SET` 23.60%），
 * 而旧实现每写一次都要：① `resolveIndices` 现分配一个 `LongArray`（JFR 分配画像里
 * `resolveIndices` **83.7 MB，全应用第 5 大分配点**）；② `writeInt(name, …)` 再过一次
 * `bindOf` 的 512 槽直映（真产物实测冲突率 66.5%）。
 *
 * 这两项**都不在指令直方图里**——指令数是同一条 `SET`，成本全在被调方内部。
 *
 * ## 口径
 *
 * - **分配字节**用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 量：
 *   这个数对机器负载、GC、JIT 时机**完全免疫**（同样的工作量分配同样的字节），
 *   是比墙钟硬得多的判据。
 * - 耗时同时打印，但只作参考（同进程交替、取多轮最小值）。
 * - 两条腿量的是同一件事：往 `LOCAL:1` 写一个整数。旧腿手工复刻旧实现
 *   （`LongArray(n)` + 填 + `writeInt`），新腿走 `VarRef.setIntValue`。
 *
 * 不进常规闸：恒绿（只打印读数），用 `ERATW_PERF_BENCH=<轮数>` 显式开启。
 */
class StmtWriteBenchTest {

    @Test
    fun `语句层单点写的分配与耗时`() {
        val rounds = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        if (rounds <= 0) return

        val store = newStore()
        val ref = VarRef("LOCAL", listOf(IntLiteral(1L)), operandType = OperandType.INT)
        val ctx = ctxOf(store)

        // 语义自查：两条路径必须落到同一个格子里。
        oldWrite(store, 7L)
        assertEquals(7L, store.readInt("LOCAL", longArrayOf(1)), "旧路径写入必须读回 7")
        ref.setIntValue(ctx, 9L)
        assertEquals(9L, store.readInt("LOCAL", longArrayOf(1)), "新路径写入必须读回 9")

        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id

        var bestOldNs = Long.MAX_VALUE
        var bestNewNs = Long.MAX_VALUE
        var oldBytes = 0L
        var newBytes = 0L
        repeat(rounds) { r ->
            // 交替顺序：奇偶轮互换，抵消「后跑那条腿 JIT 更熟」的系统偏差。
            val legs = if (r % 2 == 0) listOf(false, true) else listOf(true, false)
            for (isNew in legs) {
                val b0 = mx.getThreadAllocatedBytes(tid)
                val t0 = System.nanoTime()
                val sink = if (isNew) newLoop(ref, ctx, store) else oldLoop(store)
                val ns = System.nanoTime() - t0
                val bytes = mx.getThreadAllocatedBytes(tid) - b0
                assertTrue(sink != Long.MIN_VALUE, "读数必须被使用")
                if (isNew) {
                    bestNewNs = minOf(bestNewNs, ns)
                    newBytes = maxOf(newBytes, bytes)
                } else {
                    bestOldNs = minOf(bestOldNs, ns)
                    oldBytes = maxOf(oldBytes, bytes)
                }
            }
        }
        println(
            "BENCH 语句层单点写 ${OPS}次 | 旧(自带数组+按名) ${bestOldNs / 1_000_000}ms " +
                "分配=${oldBytes / 1024}KiB (${"%.1f".format(oldBytes.toDouble() / OPS)}B/次) " +
                "| 新(句柄+借还池) ${bestNewNs / 1_000_000}ms " +
                "分配=${newBytes / 1024}KiB (${"%.1f".format(newBytes.toDouble() / OPS)}B/次) " +
                "| 耗时比=${"%.3f".format(bestOldNs.toDouble() / bestNewNs)}x",
        )
    }

    /** 旧实现：`resolveIndices` 现分配 + `writeInt(name, …)`。 */
    private fun oldLoop(store: VariableStore): Long {
        var acc = 0L
        // 读侧共用一个数组：要量的是**写侧**的分配，读侧别把读数污染了。
        val readIdx = LongArray(1) { 1L }
        for (i in 0 until OPS) {
            oldWrite(store, acc)
            acc += store.readInt("LOCAL", readIdx)
        }
        return acc
    }

    private fun oldWrite(store: VariableStore, v: Long) {
        val idx = LongArray(1)
        idx[0] = 1L
        store.writeInt("LOCAL", idx, v)
    }

    /**
     * 新实现：`VarRef.setIntValue`（站点句柄 + 下标借还池）。
     *
     * 读侧**故意与旧腿相同**（按名 `store.readInt` + 共享数组）—— 要量的是写侧的差，
     * 别把读路径的差异混进来（首版就踩了这个坑：新腿读走 `getIntValue` 的借还池，
     * 结果耗时比 0.934× 里混着读侧的开销）。
     */
    private fun newLoop(ref: VarRef, ctx: EvalContext, store: VariableStore): Long {
        var acc = 0L
        val readIdx = LongArray(1) { 1L }
        for (i in 0 until OPS) {
            ref.setIntValue(ctx, acc)
            acc += store.readInt("LOCAL", readIdx)
        }
        return acc
    }

    private fun newStore(): VariableStore {
        val store = VariableStore()
        store.defineLocalLengths(FN, VariableStore.LocalLengths(local = 4))
        store.enterFunction(FN)
        return store
    }

    private fun ctxOf(store: VariableStore): EvalContext = object : EvalContext {
        override val variables: VariableReader get() = store
        override fun callFunction(name: String, args: List<Value?>): Value = Value.IntV(0L)
    }

    private companion object {
        const val FN = "WRITE_BENCH_FN"
        const val OPS = 2_000_000
    }
}
