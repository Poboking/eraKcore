package com.eratw.port.perf

import com.eratw.port.e2e.GoldenNormalizer
import com.eratw.port.e2e.GoldenPipeline
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6-MEMCODE 的**正确性闸**：金标语料在「表达式池 × 语句 args 交叉去重 + 语句原文释放」之后
 * 全量执行，输出与 PC 基准（`pc-golden-root.txt`，610 行）逐字节相同。
 *
 * 为什么单独一个测试而不复用 `E2EGoldenTest`：那条装置走的是「编译产物原样执行」，
 * 不调用 `Program.internStatementArgs`；生产路径（`GameSession` / `ProgramLoader`）则在
 * 装载后必跑规范化。本测试把生产路径的两步补上，才是对拍对象：
 *
 * 1. `program.internStatementArgs()` —— 池根先入表、语句侧并入（M6-MEMCODE）；
 * 2. `ProcessRuntime` 构造 —— 构造期 `program.releaseStatementRaws()`，此后池内 `raw` 全 ""。
 *
 * 断言除了逐字节输出，还锁两条接线事实：
 * - **释放真的发生了**：1,000+ 条语句的 `raw` 全部为空（释放点不在测试里手动调，而在
 *   `ProcessRuntime` 构造期 —— 所以这条同时验证「生产接线」没被绕过）；
 * - **语义没变**：规范化去重是纯身份合并（结构相等），输出必须与基准一致。
 */
class PerfGoldenInternedEquivalenceTest {

    private val fixtureDir = sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
        .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

    @Test
    fun `规范化去重后全量执行与 PC 基准逐字节一致`() {
        GoldenPipeline.requireGameCsvDir()
        val m = GoldenPipeline.build(fixtureDir)
        // 生产路径：装载后、运行前一次性规范化（幂等；这里显式跑一次，等价于 ProgramLoader/GameSession）
        val stats = m.program.internStatementArgs()
        assertTrue(stats.statements > 0, "金标语料应有语句，实际 ${stats.statements}")

        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(m, console, stepCap = 20_000_000)

        // 释放契约（由 ProcessRuntime 构造期触发，不在本测试里手动调）
        var released = 0
        for (i in 0 until m.program.statementCount) {
            if (m.program.statement(i).raw.isEmpty()) released++
        }
        assertEquals(
            m.program.statementCount, released,
            "ProcessRuntime 构造后池内语句 raw 必须全部为空（已释放 $released / ${m.program.statementCount}）",
        )

        rt.execute()
        val actual = GoldenNormalizer.normalize(console.capturedText())
        val target = fixtureDir.resolve("baseline").resolve(GoldenNormalizer.PC_BASELINE).toFile()
        assertTrue(target.isFile, "缺少 PC 基准：$target")
        val expected = GoldenNormalizer.readBaseline(target)
        val d = GoldenNormalizer.diff(expected, actual)
        assertTrue(
            d.identical,
            "去重+释放后输出与 PC 基准不一致（首差行 ${d.firstMismatch}）：\n${d.preview}",
        )
        assertEquals(610, actual.size, "PC 基准口径 = 610 行")
    }
}
