package com.eratw.port.core.compile

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 全量语料编译冒烟（默认跳过；设置 `ERATW_CORPUS` 后运行）。
 *
 * 首选入口是 `:core-vm:corpusSmoke`（JavaExec，3g 堆，不受 test worker 限制）；
 * 本 test 保留给 `:core-vm:test` 全量回归时顺带判闸（1536m 堆，大语料有 OOM 风险，
 * 判闸以 corpusSmoke 的 GATE 退出码为准）。
 *
 * ```
 * ERATW_CORPUS=<…>/ERB ../.tooling/gradle-8.10.2/bin/gradle --no-daemon :core-vm:test --tests "*CorpusCompileSmokeTest*"
 * ```
 */
class CorpusCompileSmokeTest {

    @Test
    fun fullCorpusCompile() {
        val corpus = System.getenv("ERATW_CORPUS")
        // CMP-68：缺 env 必须走 assumption（计入 skipped），不许 println+return 静默 PASS ——
        // 静默 PASS 会让「按 tests 数缩水抓门控失效」失效。
        Assumptions.assumeTrue(
            !corpus.isNullOrBlank(),
            "未设置 ERATW_CORPUS：全量语料回归按假设跳过（计 skipped，不再静默 PASS）",
        )
        val root: Path = Path(requireNotNull(corpus))
        val limit = System.getenv("ERATW_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
        // 排除今日混入语料根的 GOLDEN_* 采集副本（非原版语料，基线 3,971 文件口径）
        val files = CorpusCompileValidator.listCorpusFiles(root).take(limit)
        val r = CorpusCompileValidator.compile(files, root)
        r.runScope = if (limit == Int.MAX_VALUE) "FULL" else "LIMITED(${files.size})"
        println(
            "语料编译冒烟：文件 ${r.files} 切片 ${r.slicesTotal + r.parseFailures}（成功 ${r.functions} / 失败 ${r.failures.size} / " +
                "解析失败跳过 ${r.parseFailures}） 事件入口 ${r.eventEntries} " +
                "指令 ${r.instructions} 全局槽 ${r.globalSlots} 耗时 ${r.elapsedMs}ms",
        )
        println(
            "基线对比：切片 ${r.slicesTotal}/104,725 指令 ${r.instructions}/~3,970,000 耗时 ${r.elapsedMs}ms/~41,000ms " +
                "池条目 ${r.statementPoolEntries} marker ${r.markerInstructions}",
        )
        val out = System.getenv("ERATW_COMPILE_REPORT") ?: "build/compile-report"
        val abs = CorpusCompileValidator.writeReport(r, root, Path(out))
        println("报告: $abs/summary.md")
        r.failures.take(20).forEach { println("  失败 ${it.file}:${it.line} [${it.function}] ${it.stmtCode ?: ""} ${it.message}") }
        assertTrue(r.functions > 0, "未编译出任何函数")

        // 残值归因：任何解析失败签名都必须落入 R9 两栏之一，「未归类」直接红
        val unclassified = r.parseGroups.values.filter { it.verdict == CorpusCompileValidator.VERDICT_UNCLASSIFIED }
        assertTrue(
            unclassified.isEmpty(),
            "解析失败残值存在未归类组（不允许）：" + unclassified.joinToString("; ") { "${it.signature} ×${it.lines}" },
        )
        // GATE（CONTRACT-M2-E2E §2「不回退」+ §5 R8）：基线 = 0 编译失败 / 0 解析失败切片 / 0 解析失败行。
        // 不许跳过失败切片或缩小语料集来凑绿灯——失败逐条进 failures.tsv 归因。
        // 子集运行（设了 ERATW_LIMIT）时 prescan 符号表不完整，解析失败属伪影，只判编译失败；
        // 判闸以 :core-vm:corpusSmoke 全量（FULL）为准。
        val subset = System.getenv("ERATW_LIMIT")?.toIntOrNull() != null
        if (!subset) {
            // 契约 R8：parseFailures 与 parseFailureLines 双双入闸（基线 0/0）
            assertTrue(
                r.parseFailures == 0 && r.parseFailureLines == 0,
                "解析失败切片 ${r.parseFailures}（${r.parseFailureLines} 行，基线 0/0），归因见 $abs/summary.md §7、§8",
            )
        }
        assertTrue(
            r.failures.isEmpty(),
            "编译失败 ${r.failures.size} 切片（基线 0，全部属本轮改造引入），分栏见 $abs/summary.md §3、复现见 §5",
        )
    }
}
