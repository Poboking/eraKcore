package com.eratw.port.perf

import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.loader.BootMetadata
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **M13-MEM8 刀 A 的窄基准**：非事件按名查询表「HashMap（改造前）vs 开放寻址单数组（改造后）」
 * 同机同轮次对拍，三条腿 = 混合 `_CALL` 面 / 按名解析 / 下标取回。
 *
 * ## 为什么另立一枚，而不是复用 [FuncTableQueryBenchTest]
 *
 * 那枚的 OLD 是**复刻类**、NEW 是**生产类**：两者算法相同时仍读出
 * `byFunctionIndex` 腿 NEW/OLD = 1.288x、`nonEvent` 腿 0.848x（main@c7b89109 实测，
 * 见 `.local/mem8-baseline-main-bench.txt`）⇒ 「复刻 vs 生产」的形状差本身就有 ±29%，
 * 拿它当 5% 闸门会把装置噪声读成收益/劣化。本枚改成**两条复刻腿互拍**（同一套计时骨架、
 * 同一批查询词、轮内轮换顺序、多轮取最小），生产类只作第三条腿验证复刻没跑偏。
 *
 * ## 口径
 *
 * - `ERATW_PERF_BENCH=N` 才跑（N = 轮数；不进常规闸，恒绿）。
 * - 词表 = 真产物侧车 110,072 条 key 的 4,096 条抽样（等距），奇数位换成必然缺失的键
 *   ⇒ 命中/未命中各半，与 `_CALL_BY_NAME` / `CALLFORM` 的真实混排同量级。
 * - 读数写 `core-vm/build/mem8-form-bench.txt`（build 目录，不入库；下一次运行即覆盖）。
 */
class FuncTableFormBenchTest {

    private companion object {
        const val OPS = 2_000_000
        const val WARMUP = 300_000
        const val PROBE_SET = 4096

        // 与生产 FuncMeta.kt 的槽位编码逐位一致（刀 A）
        const val B_SEQ_BITS = 20
        const val B_SEQ_MASK = (1 shl 20) - 1
        const val B_FP_SHIFT = 20
        const val B_FP_MASK = 0x7FF
    }

    /** 一腿：一个形态的三个读面。 */
    private class Leg(
        val tag: String,
        val nonEvent: (String) -> FuncMeta?,
        val byIndex: (Int) -> FuncMeta?,
    )

    /** 改造前形态（`HashMap<String, FuncMeta>` + Node 壳）。 */
    private class HashMapForm(metas: List<FuncMeta>) {
        private val nameKeys = HashMap<String, String>(2048)
        private val nonEventByKey = HashMap<String, FuncMeta>(metas.size + metas.size / 2 + 1)
        private val byIndex: Array<FuncMeta?>

        init {
            var max = -1
            for (m in metas) if (m.index > max) max = m.index
            byIndex = arrayOfNulls(max + 1)
            for (m in metas) {
                if (!m.isEvent) nonEventByKey[m.key] = m
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

    /** 改造后形态（单张 IntArray 槽；与生产实现同式）。[capExp] 允许同轮扫容量。 */
    private class SlotForm(metas: List<FuncMeta>, capExp: Int) {
        private val nameKeys = HashMap<String, String>(2048)
        private val byIndex: Array<FuncMeta?>
        private val slots: IntArray

        init {
            var max = -1
            for (m in metas) if (m.index > max) max = m.index
            byIndex = arrayOfNulls(max + 1)
            for (m in metas) if (m.index in byIndex.indices) byIndex[m.index] = m
            var cap = 16
            while (cap < (1 shl capExp) && cap < (1 shl 30)) cap = cap shl 1
            slots = IntArray(cap)
            val mask = cap - 1
            for (m in metas) {
                if (m.isEvent) continue
                val s = spread(m.key)
                val fp = (s ushr B_FP_SHIFT) and B_FP_MASK
                val v = (fp shl B_SEQ_BITS) or (m.index + 1)
                var i = s and mask
                while (true) {
                    val cur = slots[i]
                    if (cur == 0) {
                        slots[i] = v
                        break
                    }
                    if ((cur ushr B_FP_SHIFT) == fp) {
                        val o = byIndex[(cur and B_SEQ_MASK) - 1]
                        if (o != null && o.key == m.key) {
                            slots[i] = v
                            break
                        }
                    }
                    i = (i + 1) and mask
                }
            }
        }

        private fun spread(k: String): Int = k.hashCode().let { it xor (it ushr 16) }

        private fun keyOf(name: String): String {
            nameKeys[name]?.let { return it }
            val u = name.uppercase()
            nameKeys[name] = u
            return u
        }

        fun nonEvent(key: String): FuncMeta? {
            val k = keyOf(key)
            val mask = slots.size - 1
            val s = spread(k)
            var i = s and mask
            val fp = (s ushr B_FP_SHIFT) and B_FP_MASK
            while (true) {
                val v = slots[i]
                if (v == 0) return null
                if ((v ushr B_FP_SHIFT) == fp) {
                    val m = byIndex[(v and B_SEQ_MASK) - 1]
                    if (m != null && m.key == k) return m
                }
                i = (i + 1) and mask
            }
        }

        fun byFunctionIndex(index: Int): FuncMeta? =
            if (index >= 0 && index < byIndex.size) byIndex[index] else null
    }

    /**
     * 候选形态 A2：**引用槽**（`Array<FuncMeta?>`，线性探测，无 fingerprint、无 byIndex 间接层）。
     *
     * 与 [SlotForm] 的差别只在「命中一次要几条依赖随机线」：A2 = 槽 → `meta.key`（2 条，与
     * HashMap 的 桶 → `node.key` 同深）；[SlotForm] = 槽 → `byIndex` → `meta.key`（3 条）。
     * 表本体 2^19 槽 × 4 B ≈ 2.0 MiB（与 [SlotForm] 同），比 HashMap 档省 Node 壳。
     */
    private class RefSlotForm(metas: List<FuncMeta>, capExp: Int) {
        private val nameKeys = HashMap<String, String>(2048)
        private val byIndex: Array<FuncMeta?>
        private val slots: Array<FuncMeta?>

        init {
            var max = -1
            for (m in metas) if (m.index > max) max = m.index
            byIndex = arrayOfNulls(max + 1)
            for (m in metas) if (m.index in byIndex.indices) byIndex[m.index] = m
            var cap = 16
            while (cap < (1 shl capExp) && cap < (1 shl 30)) cap = cap shl 1
            slots = arrayOfNulls(cap)
            val mask = cap - 1
            for (m in metas) {
                if (m.isEvent) continue
                var i = spread(m.key) and mask
                while (true) {
                    val cur = slots[i]
                    if (cur == null || cur.key == m.key) {
                        slots[i] = m
                        break
                    }
                    i = (i + 1) and mask
                }
            }
        }

        private fun spread(k: String): Int = k.hashCode().let { it xor (it ushr 16) }

        private fun keyOf(name: String): String {
            nameKeys[name]?.let { return it }
            val u = name.uppercase()
            nameKeys[name] = u
            return u
        }

        fun nonEvent(key: String): FuncMeta? {
            val k = keyOf(key)
            val mask = slots.size - 1
            var i = spread(k) and mask
            while (true) {
                val m = slots[i] ?: return null
                if (m.key == k) return m
                i = (i + 1) and mask
            }
        }

        fun byFunctionIndex(index: Int): FuncMeta? =
            if (index >= 0 && index < byIndex.size) byIndex[index] else null
    }

    @Test
    fun `三条腿对拍 HashMap档 vs 开放寻址档 vs 生产类`() {
        val rounds = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        assumeTrue(rounds > 0, "ERATW_PERF_BENCH=N 才跑（N=轮数）")
        val metas = loadMetas()
        val queries = buildQueries(metas)
        val indexQueries = IntArray(PROBE_SET) { (it * 7919) % metas.size }

        val hashForm = HashMapForm(metas)
        val slot18 = SlotForm(metas, 18)
        val slot19 = SlotForm(metas, 19)
        val ref19 = RefSlotForm(metas, 19)
        val prod = FunctionTable(metas)
        // 语义自查：各腿对同一批查询给出同一条 meta。
        for (q in queries.take(256)) {
            val a = hashForm.nonEvent(q)?.index
            assertEquals(a, slot18.nonEvent(q)?.index, "复刻两腿分叉 @ $q")
            assertEquals(a, slot19.nonEvent(q)?.index, "容量档之间分叉 @ $q")
            assertEquals(a, ref19.nonEvent(q)?.index, "引用槽与 HashMap 分叉 @ $q")
            assertEquals(a, prod.nonEvent(q)?.index, "复刻与生产分叉 @ $q")
        }

        val legs = listOf(
            Leg("HASH", { q -> hashForm.nonEvent(q) }, { i -> hashForm.byFunctionIndex(i) }),
            Leg("SLOT18", { q -> slot18.nonEvent(q) }, { i -> slot18.byFunctionIndex(i) }),
            Leg("SLOT19", { q -> slot19.nonEvent(q) }, { i -> slot19.byFunctionIndex(i) }),
            Leg("REF19", { q -> ref19.nonEvent(q) }, { i -> ref19.byFunctionIndex(i) }),
            Leg("PROD", { q -> prod.nonEvent(q) }, { i -> prod.byFunctionIndex(i) }),
        )
        val bestMixed = HashMap<String, Long>()
        val bestName = HashMap<String, Long>()
        val bestIndex = HashMap<String, Long>()
        for (r in 1..rounds) {
            val order = List(legs.size) { legs[(it + r) % legs.size] }
            for (leg in order) {
                bestMixed[leg.tag] = minOf(bestMixed[leg.tag] ?: Long.MAX_VALUE, timeMixed(leg, queries, indexQueries))
                bestName[leg.tag] = minOf(bestName[leg.tag] ?: Long.MAX_VALUE, timeNameOp(leg.nonEvent, queries))
                bestIndex[leg.tag] = minOf(bestIndex[leg.tag] ?: Long.MAX_VALUE, timeIndexOp(leg.byIndex, indexQueries))
            }
        }
        fun ratio(a: String, b: String, m: Map<String, Long>): String =
            "${"%.3f".format(m.getValue(a).toDouble() / m.getValue(b))}x"
        val tags = listOf("HASH", "SLOT18", "SLOT19", "REF19", "PROD")
        fun line(label: String, m: Map<String, Long>) = StringBuilder().apply {
            append("BENCH $label ${OPS}次 | ")
            append(tags.joinToString(" ") { "$it=${ns2(m.getValue(it))}" })
            append(" | ")
            append(listOf("SLOT18", "SLOT19", "REF19", "PROD").joinToString(" ") { "$it/HASH=${ratio(it, "HASH", m)}" })
        }.toString()
        val ns = StringBuilder()
        ns.appendLine(line("混合(_CALL 面：按名解析+下标取回)", bestMixed))
        ns.appendLine(line("nonEvent(小写查询词表 50% 命中)", bestName))
        ns.appendLine(line("byFunctionIndex+布尔/整型字段读", bestIndex))
        ns.appendLine("rounds=$rounds metas=${metas.size} 词表=$PROBE_SET 容量档 SLOT18=2^18 槽 / SLOT19=2^19 槽 / REF19=2^19 引用槽")
        // 复刻腿量「算法差」，但同算法的「复刻 vs 生产」实测差到 12%（形状/JIT 面不同）；
        // 出口判据量的是**生产类前后**，故再把本轮 PROD 读数与上一轮落盘的读数对拍。
        val report = Path.of("build", "mem8-form-bench.txt").toAbsolutePath().normalize()
        Files.createDirectories(report.parent)
        val prev = if (Files.isRegularFile(report)) readPrev(report) else emptyMap()
        if (prev.isEmpty()) {
            ns.appendLine("PROD 跨轮对拍：无上一轮读数（本轮即基线；同机同产物才可比）")
        } else {
            fun d(label: String, cur: Long, key: String): String {
                val p = prev[key] ?: return "$key=缺"
                return "$label 生产类 前=${ns2(p)} 后=${ns2(cur)} 后/前=${"%.3f".format(cur.toDouble() / p)}x"
            }
            ns.appendLine(
                "GATE " + d("混合", bestMixed.getValue("PROD"), "MIX") + " | " +
                    d("按名", bestName.getValue("PROD"), "NAME") + " | " +
                    d("下标", bestIndex.getValue("PROD"), "IDX"),
            )
        }
        ns.appendLine("RAW PROD MIX=${bestMixed.getValue("PROD")} NAME=${bestName.getValue("PROD")} IDX=${bestIndex.getValue("PROD")}")
        println(ns.toString())
        Files.writeString(report, ns.toString())
        assertTrue(bestMixed.getValue("SLOT18") > 0)
    }

    private fun ns2(totalNs: Long): String = "${"%.2f".format(totalNs.toDouble() / OPS)}ns"

    /** 读上一轮落盘的 PROD 原始读数（`RAW PROD` 行）；格式不合就返回空表（不猜）。 */
    private fun readPrev(p: Path): Map<String, Long> {
        val line = runCatching {
            Files.readAllLines(p).firstOrNull { it.startsWith("RAW PROD ") }
        }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (tok in line.split(' ')) {
            val eq = tok.indexOf('=')
            if (eq <= 0) continue
            val k = tok.substring(0, eq)
            if (k in setOf("MIX", "NAME", "IDX")) out[k] = tok.substring(eq + 1).toLongOrNull() ?: 0L
        }
        return if (out.size == 3) out else emptyMap()
    }

    private fun timeMixed(leg: Leg, queries: Array<String>, indexQueries: IntArray): Long {
        var acc = 0L
        for (i in 0 until WARMUP) acc += mix(leg, queries, indexQueries, i)
        val t = System.nanoTime()
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
        for (i in 0 until WARMUP) acc += query(pool[i % PROBE_SET])?.defIndex ?: -1
        val t = System.nanoTime()
        for (i in 0 until OPS) acc += query(pool[i % PROBE_SET])?.defIndex ?: -1
        val ns = System.nanoTime() - t
        assertTrue(acc != Int.MIN_VALUE, "sink 必须被使用")
        return ns
    }

    private fun timeIndexOp(query: (Int) -> FuncMeta?, pool: IntArray): Long {
        var acc = 0
        for (i in 0 until WARMUP) {
            val m = query(pool[i % PROBE_SET])
            acc += (if (m != null && m.isMethod) 1 else 0) + (m?.argIntCount ?: 0)
        }
        val t = System.nanoTime()
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
        val step = maxOf(1, metas.size / PROBE_SET)
        for (i in 0 until PROBE_SET) {
            out[i] = if (i % 2 == 0) {
                metas[(i / 2 * step) % metas.size].key.lowercase()
            } else {
                "ZZ__NOSUCH__$i"
            }
        }
        return out.requireNoNulls()
    }

    private fun loadMetas(): List<FuncMeta> {
        val dir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        val metaFile = dir?.resolve("program.meta")
        if (metaFile != null && Files.isRegularFile(metaFile)) {
            return BootMetadata.read(metaFile).functions
        }
        return List(110_072) { i -> FuncMeta(index = i, key = "BENCH_FN_${i}_UPPER", defIndex = i) }
    }
}
