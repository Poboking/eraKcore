package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.LogicalLineReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 全量语料语句层验证（默认跳过；设置 `ERATW_CORPUS` 后运行）。
 *
 * 运行方式：
 * ```
 * ERATW_CORPUS=<…>/ERB ../.tooling/gradle-8.10.2/bin/gradle --no-daemon :core-vm:test --tests "*CorpusStmtTest*"
 * ```
 */
class CorpusStmtTest {
    @Test
    fun fullCorpus() {
        val corpus = System.getenv("ERATW_CORPUS")
        // CMP-68：缺 env 走 assumption（计 skipped），不许 println+return 静默 PASS。
        Assumptions.assumeTrue(
            !corpus.isNullOrBlank(),
            "未设置 ERATW_CORPUS：全量语句层回归按假设跳过（计 skipped，不再静默 PASS）",
        )
        val limit = System.getenv("ERATW_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
        // 默认报告目录相对 core-vm 模块（gradle test JVM 的 cwd），
        // 落在 core-vm/build/stmt-report；可用 ERATW_STMT_REPORT 覆盖
        val out = System.getenv("ERATW_STMT_REPORT") ?: "build/stmt-report"
        val report = CorpusValidator.run(Path(requireNotNull(corpus)), limit, Path(out))
        println(
            "语句层验证：文件 ${report.files}（已排除 GOLDEN_* 采集副本） 逻辑行 ${report.logicalLines} 语句 ${report.statements} " +
                "失败 ${report.failures.size} 块错误 ${report.blockErrors.size} 耗时 ${report.elapsedMs}ms",
        )
        report.failures.take(10).forEach { println("  失败 ${it.file}:${it.line} ${it.message} | ${it.text}") }
        report.blockErrors.take(10).forEach { println("  块错误 ${it.file}:${it.line} ${it.message}") }
        assertTrue(report.failures.isEmpty(), "解析失败 ${report.failures.size} 条，详见 $out/summary.md")
        assertTrue(report.blockErrors.isEmpty(), "块平衡错误 ${report.blockErrors.size} 条，详见 $out/summary.md")
    }

    /**
     * A13 最小复现（不依赖语料环境变量，永久回归闸）。
     *
     * 语料实况 `ERB/ANOTHER_TALK.ERB:8-12`：
     * ```
     * @ANOTHERTARGET_TALK
     * #DIM HATAKE
     * VARSET HATAKE
     * ```
     * `#DIM` 在函数体内 = 该函数的**私有变量**（spec: LogicalLineParser.cs:279-292
     * `label.AddPrivateVariable(isPrivate=true)`；登记在 ErbLoader 第一遍扫描
     * spec: ErbLoader.cs:413-423；取标识符时私有表优先 spec: IdentifierDictionary.cs:429-447）。
     * A13 缺陷：编译侧装置从不切私有作用域，`HATAKE` 被判「未定义的标识符」，
     * 连带把 5,345 个切片 / 59,163 行误报成解析失败。
     * 现在作用域由 `LineParser.parseLogicalLine` 统一切换，两侧共用同一条代码路径。
     */
    @Test
    fun `函数内 DIM 私有名必须可解析`() {
        val dir = Files.createTempDirectory("a13-scope")
        try {
            val erb = dir.resolve("ANOTHER_TALK.ERB")
            Files.writeString(erb, "@ANOTHERTARGET_TALK\n#DIM HATAKE\nVARSET HATAKE\n")
            val pre = CorpusValidator.prescan(listOf(erb))
            assertTrue(pre.funcVars.containsKey("ANOTHERTARGET_TALK"), "prescan 未把 #DIM 收进函数私有表")
            val parser = LineParser(CorpusValidator.ScopedResolver(pre))
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(Files.readAllBytes(erb)), erb.toString())
            var parsed = 0
            while (true) {
                val line = reader.readLogicalLine() ?: break
                if (line.content.isEmpty()) continue
                parser.parseLogicalLine(line)   // 修前在这里对第 3 行抛异常
                parsed++
            }
            assertEquals(3, parsed, "三条逻辑行应全部解析成功")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
