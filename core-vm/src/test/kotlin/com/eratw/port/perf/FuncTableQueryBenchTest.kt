package com.eratw.port.perf

import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **M13-MEM5 刀 2 的窄基准**：`_CALL` 侧「函数元数据查询 + 名字解析」新旧形态同机对拍。
 *
 * 量的是被改动的两条面（口径同 [LocalSlotBenchTest]：同一进程内交替跑、每轮奇偶倒序、
 * 取多轮最小值；`ERATW_PERF_BENCH=N` 显式开启，N=轮数；不进常规闸、恒绿）：
 *
 * 1. `byFunctionIndex`（每条 `_CALL` 的下标取 meta + 字段读回）——数组直取没改，
 *    但 Boolean 字段改走位域 getter，必须证明不慢；
 * 2. `nonEvent`（CALLFORM / `_CALL_BY_NAME` 的按名解析）——
 *    `HashMap<String, FuncMeta>`（表数组 + Node + hash/equals）vs 平行数组二分
 *    （keyOf 记忆 + compareTo）。
 *
 * 数据面用真产物侧车（`ERATW_ERBC_DIR` 下的 `program.meta`，110,072 条真实 key 形态）；
 * 无产物时用等长合成 key 顶替（合成腿只用于本机冒烟，交付读数一律用真产物腿）。
 */
class FuncTableQueryBenchTest {

    private companion object {
        const val OPS = 2_000_000
        const val WARMUP = 300_000
        const val PROBE_SET = 4096
    }

    /** 一腿：三个查询闭包 + 名字，新旧共用同一计时骨架。 */
    private class Leg(
        val tag: String,
        val nonEvent: (String) -> FuncMeta?,
        val byIndex: (Int) -> FuncMeta?,
    )

    /** 改造前的查询面复刻（只为对拍，生产不存在第二份）。 */
    private class OldTable(metas: List<FuncMeta>) {
        private val nameKeys = HashMap<String, String>(2048)
        private val nonEventByKey = HashMap<String, FuncMeta>(metas.size + metas.size / 2 + 1)
        private val eventMembersByKey = HashMap<String, MutableList<FuncMeta>>()
        private val byIndex: Array<FuncMeta?>

        init {
            var max = -1
            for (m in metas) if (m.index > max) max = m.index
            byIndex = arrayOfNulls(max + 1)
            for (m in metas) {
                if (m.isEvent) eventMembersByKey.getOrPut(m.key) { ArrayList() }.add(m) else nonEventByKey[m.key] = m
                if (m.index in byIndex.indices) byIndex[m.index] = m
            }
        }

        private fun keyOf(name: String): String {
            nameKeys[name]?.let { return it }
            val u = name.uppercase()
            nameKeys[name] = u
            return u
        }

        fun nonEvent(key: String): FuncMeta? = nonEventByKey[keyOf(key)]

        fun byFunctionIndex(index: Int): FuncMeta? =
            if (index >= 0 && index < byIndex.size) byIndex[index] else null
    }

    @Test
    fun `CALL 面窄基准：下标取 meta 与按名解析新旧对拍`() {
        val rounds = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        assumeTrue(rounds > 0, "ERATW_PERF_BENCH=N 才跑（N=轮数）")
        val metas = loadMetas()
        val queries = buildQueries(metas)
        val indexQueries = IntArray(PROBE_SET) { (it * 7919) % metas.size }

        val newTable = FunctionTable(metas)
        val oldTable = OldTable(metas)
        // 语义自查：同一批查询两腿结果逐位相同（按名解析面）。
        for (q in queries.take(128)) {
            assertEquals(oldTable.nonEvent(q)?.index, newTable.nonEvent(q)?.index, "nonEvent 分叉 @ $q")
        }

        val legs = listOf(
            Leg("OLD", { q -> oldTable.nonEvent(q) }, { i -> oldTable.byFunctionIndex(i) }),
            Leg("NEW", { q -> newTable.nonEvent(q) }, { i -> newTable.byFunctionIndex(i) }),
        )
        val bestMixed = HashMap<String, Long>()
        val bestName = HashMap<String, Long>()
        val bestIndex = HashMap<String, Long>()
        for (r in 1..rounds) {
            val order = if (r % 2 == 1) legs else legs.reversed()
            for (leg in order) {
                bestMixed[leg.tag] = minOf(bestMixed[leg.tag] ?: Long.MAX_VALUE, timeMixed(leg, queries, indexQueries))
                bestName[leg.tag] = minOf(bestName[leg.tag] ?: Long.MAX_VALUE, timeNameOp(leg.nonEvent, queries))
                bestIndex[leg.tag] = minOf(bestIndex[leg.tag] ?: Long.MAX_VALUE, timeIndexOp(leg.byIndex, indexQueries))
            }
        }
        val ns = StringBuilder()
        ns.appendLine("BENCH 混合(_CALL 面：按名解析+下标取回) ${OPS}次 | " +
            "OLD=${"%.2f".format(bestMixed.getValue("OLD").toDouble() / OPS)}ns " +
            "NEW=${"%.2f".format(bestMixed.getValue("NEW").toDouble() / OPS)}ns " +
            "NEW/OLD=${"%.3f".format(bestMixed.getValue("NEW").toDouble() / bestMixed.getValue("OLD"))}x")
        ns.appendLine("BENCH nonEvent(小写查询词表 50% 命中) ${OPS}次 | " +
            "OLD=${"%.2f".format(bestName.getValue("OLD").toDouble() / OPS)}ns " +
            "NEW=${"%.2f".format(bestName.getValue("NEW").toDouble() / OPS)}ns " +
            "NEW/OLD=${"%.3f".format(bestName.getValue("NEW").toDouble() / bestName.getValue("OLD"))}x")
        ns.appendLine("BENCH byFunctionIndex+布尔/整型字段读 ${OPS}次 | " +
            "OLD=${"%.2f".format(bestIndex.getValue("OLD").toDouble() / OPS)}ns " +
            "NEW=${"%.2f".format(bestIndex.getValue("NEW").toDouble() / OPS)}ns " +
            "NEW/OLD=${"%.3f".format(bestIndex.getValue("NEW").toDouble() / bestIndex.getValue("OLD"))}x")
        println(ns.toString())
        assertTrue(bestMixed.getValue("NEW") > 0)
    }

    /** 混合腿：按名解析与下标取回交替（贴近 `_CALL`/`_CALL_BY_NAME` 混排；防 JIT 折叠单腿）。 */
    private fun timeMixed(leg: Leg, queries: Array<String>, indexQueries: IntArray): Long {
        var acc = 0L
        var t = System.nanoTime()
        for (i in 0 until WARMUP) acc += mix(leg, queries, indexQueries, i)
        t = System.nanoTime()
        for (i in 0 until OPS) acc += mix(leg, queries, indexQueries, i)
        val ns = System.nanoTime() - t
        assertTrue(acc != Long.MIN_VALUE, "sink 必须被使用")
        return ns
    }

    private fun mix(leg: Leg, queries: Array<String>, indexQueries: IntArray, i: Int): Long {
        val m = leg.nonEvent(queries[i % PROBE_SET]) ?: leg.byIndex(indexQueries[i % PROBE_SET])
        return if (m != null) (m.defIndex + (if (m.returnsStr) 1 else 0)).toLong() else -1L
    }

    private fun timeNameOp(query: (String) -> FuncMeta?, pool: Array<String>): Long {
        var acc = 0
        var t = System.nanoTime()
        for (i in 0 until WARMUP) acc += query(pool[i % PROBE_SET])?.defIndex ?: -1
        t = System.nanoTime()
        for (i in 0 until OPS) acc += query(pool[i % PROBE_SET])?.defIndex ?: -1
        val ns = System.nanoTime() - t
        assertTrue(acc != Int.MIN_VALUE, "sink 必须被使用")
        return ns
    }

    private fun timeIndexOp(query: (Int) -> FuncMeta?, pool: IntArray): Long {
        var acc = 0
        var t = System.nanoTime()
        for (i in 0 until WARMUP) {
            val m = query(pool[i % PROBE_SET])
            acc += (if (m != null && m.isMethod) 1 else 0) + (m?.argIntCount ?: 0)
        }
        t = System.nanoTime()
        for (i in 0 until OPS) {
            val m = query(pool[i % PROBE_SET])
            acc += (if (m != null && m.isMethod) 1 else 0) + (m?.argIntCount ?: 0)
        }
        val ns = System.nanoTime() - t
        assertTrue(acc != Int.MIN_VALUE, "sink 必须被使用")
        return ns
    }

    private fun buildQueries(metas: List<FuncMeta>): Array<String> {
        val out = arrayOfNulls<String>(PROBE_SET)
        for (i in 0 until PROBE_SET) {
            out[i] = if (i % 2 == 0) {
                metas[(i / 2 * 104729L % metas.size).toInt()].key.lowercase()
            } else {
                // 缺失腿：带独有前缀的键不可能出现在语料（语料函数名不带 __ 双下划线前缀段）。
                "ZZ__NOSUCH__$i"
            }
        }
        return out.requireNoNulls()
    }

    private fun loadMetas(): List<FuncMeta> {
        val dir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        val metaFile = dir?.resolve("program.meta")
        if (metaFile != null && Files.isRegularFile(metaFile)) {
            return com.eratw.port.core.loader.BootMetadata.read(metaFile).functions
        }
        // 合成腿：11 万条、key 长度与语料同量级（大写）。
        return List(110_072) { i -> FuncMeta(index = i, key = "BENCH_FN_${i}_UPPER", defIndex = i) }
    }
}
