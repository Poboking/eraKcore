package com.eratw.port.perf

import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「局部变量实例改帧内数组直取」的**微基准**（只量被改的那条路径）。
 *
 * ## 为什么不用整局墙钟
 *
 * `TurnLatencyProbeTest` 的整局读数在同一台机器上逐轮跨 213~420 ns/instr（±50%），
 * 因为它跑的是 168 角色两两交互的真实脚本，受别的构建抢核、GC、JIT 时机影响。
 * 要判「每次局部变量访问少查 4 次 `HashMap`」值不值，必须在**同一进程内交替**量
 * 同一段循环 —— 本用例就是那段循环：`readInt("LOCAL", …)` + `writeInt("LOCAL", …)`，
 * 除此之外什么都不做。
 *
 * ## 口径
 *
 * - 两条腿（直取开 / 关）在**同一 JVM**里交替构造 `VariableStore`、交替计时，
 *   开关是 `VariableStore` 构造期读的 `-Deratw.perf.noLocalSlots`（`System.setProperty`
 *   后立刻 new 才生效）。
 * - 每腿先跑 [WARMUP] 次预热（让 `bindOf` 的直映缓存与 JIT 都到位），再计 [OPS] 次。
 * - 取多轮**最小值**：最小值最接近「无干扰」的那一轮，是这类噪声下唯一稳定的统计量。
 *
 * 不进常规闸：本用例恒绿（只打印读数），用 `ERATW_PERF_BENCH=1` 显式开启。
 */
class LocalSlotBenchTest {

    @Test
    fun `局部变量读写微基准`() {
        val on = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        if (on <= 0) return

        // 语义自查（顺手当回归）：两条腿读到的必须是同一份值，且互不串味。
        assertEquals(7L, readBack(off = false), "直取开：LOCAL:1 写入 7 必须读回 7")
        assertEquals(7L, readBack(off = true), "直取关：LOCAL:1 写入 7 必须读回 7")

        val rounds = on
        val best = HashMap<String, Long>()
        try {
            for (r in 1..rounds) {
                // 奇偶交替：抵消「后跑那条腿 JIT 更熟」的系统偏差。
                val legs = if (r % 2 == 1) listOf(false, true) else listOf(true, false)
                for (off in legs) {
                    val ns = timeLeg(off)
                    val tag = if (off) "OFF" else "ON"
                    best[tag] = minOf(best[tag] ?: Long.MAX_VALUE, ns)
                }
            }
        } finally {
            System.clearProperty("eratw.perf.noLocalSlots")
        }
        val onNs = best.getValue("ON")
        val offNs = best.getValue("OFF")
        println(
            "BENCH 局部变量读写 ${OPS}次 | ON(直取)=${onNs / 1000}us OFF(两层哈希)=${offNs / 1000}us " +
                "| 每次 ON=${"%.2f".format(onNs.toDouble() / OPS)}ns OFF=${"%.2f".format(offNs.toDouble() / OPS)}ns " +
                "| 提速=${"%.3f".format(offNs.toDouble() / onNs)}x",
        )
        assertTrue(onNs > 0 && offNs > 0, "读数必须为正")
    }

    /** 一腿：新开 store（构造期读开关）→ 预热 → 计时。返回总纳秒。 */
    private fun timeLeg(off: Boolean): Long {
        System.setProperty("eratw.perf.noLocalSlots", off.toString())
        val store = VariableStore()
        store.defineLocalLengths(FN, VariableStore.LocalLengths(local = 4))
        store.enterFunction(FN)
        val idx = LongArray(1)
        run(store, idx, WARMUP)
        val t = System.nanoTime()
        val sink = run(store, idx, OPS)
        val ns = System.nanoTime() - t
        // 防止 JIT 把整段循环当死代码删掉。
        assertTrue(sink != Long.MIN_VALUE, "读数必须被使用")
        store.leaveFunction()
        return ns
    }

    private fun readBack(off: Boolean): Long {
        System.setProperty("eratw.perf.noLocalSlots", off.toString())
        val store = VariableStore()
        store.defineLocalLengths(FN, VariableStore.LocalLengths(local = 4))
        store.enterFunction(FN)
        store.writeInt("LOCAL", longArrayOf(1), 7L)
        val v = store.readInt("LOCAL", longArrayOf(1))
        store.leaveFunction()
        System.clearProperty("eratw.perf.noLocalSlots")
        return v
    }

    /**
     * 被量的那段循环：`readInt` + `writeInt` 交替，下标在 0..3 之间轮转
     * （语料里 `FOR LOCAL` 的典型形态）。
     */
    private fun run(store: VariableStore, idx: LongArray, n: Int): Long {
        var acc = 0L
        for (i in 0 until n) {
            idx[0] = (i and 3).toLong()
            acc += store.readInt("LOCAL", idx)
            store.writeInt("LOCAL", idx, acc)
        }
        return acc
    }

    private companion object {
        const val FN = "BENCH_FN"
        const val WARMUP = 300_000
        const val OPS = 2_000_000
    }
}
