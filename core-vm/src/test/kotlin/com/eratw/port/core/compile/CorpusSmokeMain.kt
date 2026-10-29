package com.eratw.port.core.compile

import com.eratw.port.core.stmt.CorpusValidator
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

private const val EXIT_GREEN = 0
private const val EXIT_RED = 1

/** 闸口径失效（缺 env / 单文件残留 / LIMITED / 清单不符）：不判语义，只判闸自身。 */
private const val EXIT_GATE_INVALID = 2

/** FULL 语料清单定值（2026-10-24 基线：3,971 文件，已排除 GOLDEN_* 采集副本）。 */
private const val FULL_CORPUS_FILES = 3_971

/** FULL 编译切片基线下限（430e5dc9 实测 110,066；只许涨不许缩，缩了就是缩语料或缩编译）。 */
private const val FULL_SLICES_FLOOR = 110_066

/**
 * 语料编译冒烟命令行入口（gradle JavaExec / 直接 java 均可；不受 test worker 限制）。
 *
 * 环境变量：
 * - `ERATW_CORPUS`：语料根目录（全量模式）。清单**排除** `GOLDEN_*.ERB` 采集副本
 *   （今日混入 ERB 根，属 `fixtures/golden` 的拷贝，非原版语料；基线 3,971 文件口径）。
 * - `ERATW_CORPUS_FILE`：单文件复现模式（优先级高于 ERATW_CORPUS；仅编译该文件，
 *   跨文件函数引用报"未定义的函数"属预期噪音，只用于最小复现确认）。
 * - `ERATW_LIMIT`：只编译排序后前 N 个文件（调试用；终验禁用——不许缩语料集；
 *   受限模式一律 `exit 2`，不再判 GATE OK）。
 * - `ERATW_PARSE_CROSSCHECK=1`：FULL 模式跑完编译侧后，再跑一遍语句层
 *   （[CorpusValidator.run]，同一文件清单、同一装置）比对解析失败行数 —— A13 要求
 *   两侧口径可证一致，不只是"看起来一样"。
 * - `ERATW_COMPILE_REPORT`：报告输出目录，默认 `build/compile-report`
 *   （gradle 场景 cwd=core-vm 模块目录 → 落 core-vm/build/ 下；绝不写仓库路径）。
 *
 * 退出码：
 * - 0 = GATE GREEN：仅 FULL 模式、文件数过定值闸、切片数过下限闸、编译失败 0、解析失败 0 切片/0 行；
 * - 1 = GATE RED：编译失败 / 解析失败 / 未归类残值（详见 summary.md）；
 * - 2 = 闸口径失效：缺 `ERATW_CORPUS`/`ERATW_CORPUS_FILE`、`ERATW_CORPUS_FILE` 残留、
 *   `ERATW_LIMIT` 受限模式、FULL 文件数 0 或 ≠ 定值（3,971）、切片数 < 基线下限。
 *   **闸自身失效一律非零退出**，不许「打印一句就 return 0」。
 */
fun main() {
    val single = System.getenv("ERATW_CORPUS_FILE")
    val corpus = System.getenv("ERATW_CORPUS")
    val files: List<Path>
    val root: Path?
    when {
        !single.isNullOrBlank() -> {
            files = listOf(Path(single))
            root = null
            println("单文件复现模式：$single")
        }
        !corpus.isNullOrBlank() -> {
            root = Path(corpus)
            val limit = System.getenv("ERATW_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
            files = CorpusCompileValidator.listCorpusFiles(root).take(limit)
        }
        else -> {
            println("GATE RED(2)：未设置 ERATW_CORPUS / ERATW_CORPUS_FILE —— 闸口径失效，禁止退出 0（旧行为即假绿）")
            exitProcess(EXIT_GATE_INVALID)
        }
    }
    val r = CorpusCompileValidator.compile(files, root)
    r.runScope = when {
        !single.isNullOrBlank() -> "SINGLE"
        System.getenv("ERATW_LIMIT")?.toIntOrNull() != null -> "LIMITED(${files.size})"
        else -> "FULL"
    }
    println(
        "语料编译冒烟：文件 ${r.files}（已排除 GOLDEN_* 副本） 切片 ${r.slicesTotal + r.parseFailures}（成功 ${r.functions} / 编译失败 ${r.failures.size} / " +
            "解析失败跳过 ${r.parseFailures}，失败行 ${r.parseFailureLines}） " +
            "事件入口 ${r.eventEntries} 指令 ${r.instructions} 全局槽 ${r.globalSlots} 耗时 ${r.elapsedMs}ms",
    )
    println(
        "A13 口径修正前（同语料 FULL）：成功 ${CorpusCompileValidator.A13_PRE_FUNCTIONS} / " +
            "解析失败 ${CorpusCompileValidator.A13_PRE_PARSE_FAILURES} 切片、" +
            "${CorpusCompileValidator.A13_PRE_PARSE_FAILURE_LINES} 行 → 本次 " +
            "成功 ${r.functions} / 解析失败 ${r.parseFailures} 切片、${r.parseFailureLines} 行",
    )
    val denom = r.slicesTotal
    println("编译成功率: %.2f%%（基线 100.00%% @ 104,725 切片）".format(if (denom == 0) 100.0 else r.functions * 100.0 / denom))
    println(
        "基线对比：切片 ${r.slicesTotal}/104,725 指令 ${r.instructions}/~3,970,000 耗时 ${r.elapsedMs}ms/~41,000ms",
    )
    if (r.programBuilt) {
        println(
            "语句池/marker：池条目 ${r.statementPoolEntries}（A13 池未落地时=marker 估算） " +
                "marker ${r.markerInstructions}/${r.programInstructions} 指令 " +
                "(%.2f%%)".format(if (r.programInstructions > 0) r.markerInstructions * 100.0 / r.programInstructions else 0.0),
        )
        println("marker TOP: " + r.markerByName.entries.take(10).joinToString(", ") { "${it.key}=${it.value}" })
    } else {
        println("Program 构建失败（marker/池统计不可用）：${r.programBuildError}")
    }
    // 残值归因表（必须全部落入两栏之一）
    if (r.parseGroups.isEmpty()) {
        println("解析失败残值：0 组（无残值）")
    } else {
        println("解析失败残值归因（${r.parseGroups.size} 组）：")
        for (g in r.parseGroups.values.sortedByDescending { it.lines }) {
            println("  [${g.verdict}] 切片 ${g.slices} / 行 ${g.lines} :: ${g.signature} :: ${g.sample}")
        }
    }
    val outDir = Path(System.getenv("ERATW_COMPILE_REPORT") ?: "build/compile-report")
    val abs = CorpusCompileValidator.writeReport(r, root, outDir)
    println("报告: $abs/summary.md（编译失败明细 $abs/failures.tsv；解析失败归因 $abs/parse-failures.tsv）")

    r.failures.take(30).forEach { println("  失败 ${it.file}:${it.line} [${it.function}] ${it.code} ${it.message}") }
    if (r.failures.size > 30) println("  …其余 ${r.failures.size - 30} 条见 failures.tsv")

    // ---- A13 交叉核对：语句层 vs 编译层，同一文件清单、同一装置 ----
    if (root != null && r.runScope == "FULL" && System.getenv("ERATW_PARSE_CROSSCHECK") == "1") {
        val stmt = CorpusValidator.run(root, outDir = Path("build/stmt-report"))
        println(
            "交叉核对：语句层 文件 ${stmt.files} 逻辑行 ${stmt.logicalLines} 解析失败 ${stmt.failures.size} 块错误 ${stmt.blockErrors.size} " +
                "‖ 编译层 文件 ${r.files} 解析失败行 ${r.parseFailureLines}",
        )
        val aligned = stmt.files == r.files && stmt.failures.size == r.parseFailureLines && stmt.blockErrors.isEmpty()
        println(if (aligned) "交叉核对：两侧解析失败口径一致 ✔" else "交叉核对：两侧口径**不一致**（需继续查差异）")
        stmt.failures.take(10).forEach { println("  语句层失败 ${it.file}:${it.line} ${it.message} | ${it.text}") }
        stmt.blockErrors.take(10).forEach { println("  语句层块错误 ${it.file}:${it.line} ${it.message}") }
    }

    if (!single.isNullOrBlank()) {
        println("GATE RED(2)：ERATW_CORPUS_FILE 残留（单文件复现模式）不判闸 —— 拒绝退出 0 的假绿")
        exitProcess(EXIT_GATE_INVALID)
    }

    // GATE（契约 §2 不回退 + §5 R8：FULL 下 0 编译失败 / 0 解析失败切片 / 0 解析失败行；
    // 残值若有则必须全部归入 R9 两栏，「未归类」直接 RED）。
    // 闸口径前置（CMP-61）：受限模式 / FULL 清单不符 / 0 文件 / 切片缩水一律 exit 2，
    // 不许走到 GATE GREEN —— 闸自身失效时任何「绿」都不可信。
    if (r.runScope != "FULL") {
        println("GATE RED(2)：受限模式 ${r.runScope}（ERATW_LIMIT）不得判闸 —— 终验必须 FULL")
        exitProcess(EXIT_GATE_INVALID)
    }
    if (r.files != FULL_CORPUS_FILES) {
        println("GATE RED(2)：FULL 语料清单 ${r.files} 文件 ≠ 定值 $FULL_CORPUS_FILES（0 文件同样拒收；清单口径漂移即闸失效）")
        exitProcess(EXIT_GATE_INVALID)
    }
    val unclassified = r.parseGroups.values.count { it.verdict == CorpusCompileValidator.VERDICT_UNCLASSIFIED }
    if (r.failures.isEmpty() && unclassified == 0 && r.parseFailures == 0 && r.parseFailureLines == 0) {
        // 切片下限只挂在绿路径上：有失败/解析失败时走 RED(1)，不能把真红误报成闸口径失效。
        if (r.slicesTotal < FULL_SLICES_FLOOR) {
            println("GATE RED(2)：FULL 编译切片 ${r.slicesTotal} < 基线下限 $FULL_SLICES_FLOOR（缩语料或缩编译）")
            exitProcess(EXIT_GATE_INVALID)
        }
        println("GATE GREEN：编译失败 0、解析失败 0 切片/0 行、残值归因无未归类项")
        exitProcess(EXIT_GREEN)
    }
    println(
        "GATE RED：编译失败 ${r.failures.size} 切片 / 解析失败 ${r.parseFailures} 切片、${r.parseFailureLines} 行" +
            " / 未归类残值组 $unclassified（归因见 summary.md §3-§5、§7-§8）",
    )
    exitProcess(EXIT_RED)
}

/** 便捷：失败行的语句码（可能为空）。 */
private val CorpusCompileValidator.Failure.code: String get() = stmtCode?.let { "[$it]" } ?: ""
