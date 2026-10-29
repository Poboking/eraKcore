package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.ProgramLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **mem7 的窄基准**：装载后「索引骨架 + 条目取用」热路径的新旧同机对拍。
 *
 * 动刀对象是装载期建好、之后只读或只增的索引面（语句记录偏移表 / 局部串池偏移表 /
 * 条目与节点槽表 / 段 2 池根表）。本装置**不**测「装载后骨架占多少」——那一账归
 * [HeapCompositionProbeTest] 的 §1 —— 只测**取用一条**要付多少：那一刀只有可能把
 * 「一张大数组直取」换成「映射页直取 / 两级块表」，收益在常驻、成本在取用面。
 *
 * 三条腿各走真实产物上的真实取用面（每个下标只物化一次，腿与腿之间不共享缓存）：
 *
 * 1. `statement(i)`：段 3 记录偏移表读 + 记录重放 + 段 3 局部串池取值 + 节点稀疏补链
 *    —— 覆盖 [com.eratw.port.core.loader.LazyStatementRecords] 的条目表与节点表；
 * 2. `expr(k)`：段 2 池根表读 + 节点重放 + 段 2 局部串池取值；
 * 3. `functions[i].name`：段 4 记录偏移表读 + 函数名局部串池取值（屏蔽表装配面就是这条）。
 *
 * 口径：`ERATW_PERF_BENCH=N` 才跑（N = 轮数；恒绿、不进常规闸）。每轮**重新装载一份 Program**
 * （条目表一旦物化就回不去，同一份 Program 跑第二轮量的全是缓存命中）；跨轮取最小值。
 * **改前 / 改后各跑一次、同机对比**：下标集合是固定步长的确定值，两次跑取到的是同一批条目，
 * 于是每条腿的耗时差就是索引形态的差（换成随机下标会让两次跑抽到不同的记录长度分布）。
 *
 * 报告落 `core-vm/build/mem7-skeleton-bench.md`（build 目录，不入库）。
 */
class SkeletonFetchBenchTest {

    private companion object {
        const val STMT_OPS = 100_000
        const val EXPR_OPS = 50_000
        const val FN_OPS = 50_000
        const val WARMUP = 2_000

        /** 三条腿的名字（报告按这个顺序列，与 legs() 的构造顺序一致）。 */
        val TAGS = listOf("statement(i)", "expr(k)", "functions[i].name")
    }

    /**
     * 一腿。[sample] 收 `0 until ops`，按等距步长折成真实下标；[warm] 收 `0 until WARMUP`，
     * 走**池尾**下标 —— 预热若走同一批等距下标，计时段量到的就全是缓存命中（条目表一旦写上
     * 就不回落），量不到「首取」的成本。两条腿相交的只有等距样本落进尾部的那约 0.1%。
     */
    private class Leg(
        val tag: String,
        val ops: Int,
        val sample: (Int) -> Int,
        val warm: (Int) -> Int,
    )

    @Test
    fun `索引骨架取用面：语句 表达式 函数名三条腿`() {
        val rounds = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        assumeTrue(rounds > 0, "ERATW_PERF_BENCH=N 才跑（N=轮数）")
        val dir = findErbcDir()
        assumeTrue(dir != null, "未找到 erbc.v1 产物（设 ERATW_ERBC_DIR 或 ERATW_ERBC）：跳过")
        val programFile = dir!!.resolve("program.erbc")
        assertTrue(Files.isRegularFile(programFile), "产物不存在：$programFile")

        val best = HashMap<String, Long>()
        val perRound = ArrayList<String>()
        val t0 = System.nanoTime()
        var stmtTotal = 0
        var lastSettledAfter = 0L
        for (r in 1..rounds) {
            val program = ProgramLoader.loadMapped(programFile)
            val settledLoaded = HeapMeter.settled()
            val legs = legs(program)
            for (leg in legs) {
                val ns = time(leg)
                best[leg.tag] = minOf(best[leg.tag] ?: Long.MAX_VALUE, ns)
            }
            stmtTotal = program.materializedStatementCount
            val settledUsed = HeapMeter.settled()
            val line = "[mem7-bench] 第 $r 轮 | " +
                legs.joinToString(" | ") { "${it.tag}=${"%.1f".format(ns(best.getValue(it.tag)).toDouble() / it.ops)}ns/op" } +
                " | 装载后常驻 ${mib(settledLoaded)} MiB → 取用后 ${mib(settledUsed)} MiB" +
                "（取用增量 +${mib(settledUsed - settledLoaded)} MiB，物化 ${group(stmtTotal)} 条）"
            println(line)
            perRound.add(line)
            lastSettledAfter = settledUsed
            // 让上一轮的 Program 先脱手，下一轮的装载读数才不互相污染
            program.releaseMmap()
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val lines = ArrayList<String>()
        lines.add("# mem7 索引骨架取用基准（真产物；只测量）")
        lines.add("- 产物：`${programFile.parent}`（erbc ${Files.size(programFile)} B）")
        lines.add("- JVM：${HeapMeter.jvmName()}，maxHeap ${mib(HeapMeter.max())} MiB，" +
            "GC ${HeapMeter.gcNames()}，GC 轮数=${HeapMeter.rounds}")
        lines.add("- 样本量：statement $STMT_OPS / expr $EXPR_OPS / functions $FN_OPS 条（等距步长，跨轮同一批）")
        lines.add("- 轮数：$rounds（每轮重新装载一份 Program；下表 = 跨轮最小值）")
        for (tag in TAGS) {
            val ns = best.getValue(tag)
            val ops = when (tag) {
                "statement(i)" -> STMT_OPS
                "expr(k)" -> EXPR_OPS
                else -> FN_OPS
            }
            lines.add("- **$tag**：${"%.1f".format(ns.toDouble() / ops)} ns/op（合计 ${"%.0f".format(ns / 1_000_000.0)} ms）")
        }
        lines.add("- 末轮取用后常驻：${mib(lastSettledAfter)} MiB")
        lines.add("- 基准总耗时 ${totalMs / 1000}s")
        lines.add("")
        lines.add("## 逐轮")
        lines.addAll(perRound.map { it.replace("[mem7-bench] ", "") })
        val text = lines.joinToString("\n")
        println(text)
        val report = Path.of("build", "mem7-skeleton-bench.md").toAbsolutePath().normalize()
        Files.createDirectories(report.parent)
        Files.writeString(report, text + "\n")
        println("[mem7-bench] 报告：$report")
        assertTrue(totalMs < 900_000, "基准超预算：${totalMs / 1000}s")
    }

    /** 三条腿的取用面（下标 = 等距步长，跨轮同一批）。 */
    private fun legs(program: Program): List<Leg> {
        val stmtCount = program.statementCount
        val exprCount = program.exprCount
        val fnCount = program.functions.size
        assertTrue(stmtCount > STMT_OPS && exprCount > EXPR_OPS && fnCount > FN_OPS,
            "产物规模不够：stmt=$stmtCount expr=$exprCount fn=$fnCount")
        return listOf(
            Leg(
                "statement(i)",
                STMT_OPS,
                sample = { i -> program.statement((i.toLong() * stmtCount / STMT_OPS).toInt()).hashCode() },
                warm = { j -> program.statement(stmtCount - 1 - j).hashCode() },
            ),
            Leg(
                "expr(k)",
                EXPR_OPS,
                sample = { i -> program.expr((i.toLong() * exprCount / EXPR_OPS).toInt()).hashCode() },
                warm = { j -> program.expr(exprCount - 1 - j).hashCode() },
            ),
            Leg(
                "functions[i].name",
                FN_OPS,
                sample = { i -> program.functions[(i.toLong() * fnCount / FN_OPS).toInt()].name.hashCode() },
                warm = { j -> program.functions[fnCount - 1 - j].name.hashCode() },
            ),
        )
    }

    private fun time(leg: Leg): Long {
        var acc = 0L
        for (j in 0 until WARMUP) acc += leg.warm(j)
        val t = System.nanoTime()
        for (i in 0 until leg.ops) acc += leg.sample(i)
        val ns = System.nanoTime() - t
        assertTrue(acc != Long.MIN_VALUE, "sink 必须被使用")
        return ns
    }

    private fun ns(v: Long): Long = v

    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return if (Files.isRegularFile(Path.of(it).resolve("program.erbc"))) Path.of(it) else null }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return Path.of(it).parent }
        return null
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    private fun group(n: Int): String {
        val s = n.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }
}
