package com.eratw.port.core.perf

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.ProgramLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 装载链分段计时探针（2026-10-22，只测量）。
 *
 * ## 为什么需要它
 *
 * 真机首启实测约 2 分钟，而 `LoadTrace` 只落**内存**读数（`used/max/native`），
 * 没有任何一行说明「时间花在哪一段」。没有分段耗时就只能靠猜 —— 而猜错的代价是
 * 把优化做在「本来只占 3%」的段上。本探针把 `ProgramLoader.loadMapped` 的
 * `LoadStageMark` 回调当秒表，逐段打印耗时，用来**定位**而不是**验收**。
 *
 * ## 读数口径
 *
 * 单次冷装载（不预热）+ 一次热装载；两者之差把 JIT/页缓存的影响摊开。
 * JVM 数字**不等于** ART，但段间**比例**是同一套代码、同一份产物，可用来排序优先级。
 *
 * 产物查找与 `PoolDecompAuditTest` 同源；缺产物时跳过。
 */
class LoadTimingProbe {

    @Test
    fun `装载分段耗时`() {
        val dir = findErbcDir()
        assumeTrue(dir != null, "无 erbc 产物：跳过（先跑 :tools:erbcc:compileCorpus）")
        val erbc = dir!!.resolve("program.erbc")
        val meta = dir.resolve("program.meta")
        assumeTrue(Files.isRegularFile(erbc), "缺 program.erbc：跳过")

        // 只跑一轮：所谓「首次加载」就是冷的那一轮，预热过的读数对用户没有意义；
        // 而且两个 Program 同时常驻会撞 test worker 的 3g 上限。
        repeat(1) { round ->
            val stages = ArrayList<Pair<String, Long>>()
            val begin = System.nanoTime()
            var last = begin
            val mark = LoadStageMark { stage ->
                val now = System.nanoTime()
                stages += stage to (now - last) / 1_000_000L
                last = now
            }
            val program = ProgramLoader.loadMapped(erbc, mark)
            val total = (System.nanoTime() - begin) / 1_000_000L
            val metaBegin = System.nanoTime()
            if (Files.isRegularFile(meta)) BootMetadata.read(meta)
            val metaMs = (System.nanoTime() - metaBegin) / 1_000_000L
            println(
                "[load-timing] round=$round total=${total}ms " +
                    "instr=${program.instructionCount} meta=${metaMs}ms",
            )
            stages.forEach { (name, ms) -> println("[load-timing]   $name ${ms}ms") }
            check(program.instructionCount > 0)
        }
    }

    /** 与 `PoolDecompAuditTest` 同一套查找顺序（环境变量 → 模块 build → 仓库内 erbcc 输出）。 */
    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
        val candidates = listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("build", "erbc.v1"),
            Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }
}
