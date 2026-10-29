package com.eratw.port.e2e

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语句参数子树规范化去重（`ArgInterner`）的**端到端等价**钉死。
 *
 * 判据：同一份金标管线产物（`fixtures/golden`，15 节 / 610 行）在
 * 「去重前」「去重后」两次执行里，控制台原文**逐字节相同**；且去重后的原文与
 * PC 基准 `pc-golden-root.txt` 逐行一致（不是只跟自己去重前比 —— 那样两边一起错也绿）。
 *
 * 为什么单独一个装置而不是改 `E2EGoldenTest`：那条闸跑的是「未去重」路径，
 * 本条是它的差分对照；两条都绿才说明去重没有改变任何可观测行为。
 */
class InternedArgsE2ETest {

    private val fixtureDir: Path =
        sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

    private fun runAll(model: GoldenModel): String {
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(model, console, stepCap = 20_000_000)
        rt.execute()
        return console.capturedText()
    }

    @Test
    fun `去重前后全量执行输出逐字节相同`() {
        GoldenPipeline.requireGameCsvDir()
        val model = GoldenPipeline.build(fixtureDir)
        assertEquals(0, model.compileFailures.size, "切片编译失败: ${model.compileFailures}")

        val before = runAll(model)
        val statementsBefore = model.program.statementCount
        val stats = model.program.internStatementArgs()
        val after = runAll(model)

        assertEquals(before, after, "去重前后控制台原文必须逐字节相同（长度 ${before.length} vs ${after.length}）")
        assertEquals(statementsBefore, model.program.statementCount, "语句池下标/条目数不得变化")
        assertTrue(stats.merged > 0, "金标产物里也应存在可合并的同形结点（实测 ${stats.merged}）")

        // 去重后的输出直接对 PC 基准（610 行）—— 反向证明不是「两边一起错」
        val baseline = fixtureDir.resolve("baseline").resolve(GoldenNormalizer.PC_BASELINE)
        assertTrue(baseline.toFile().exists(), "PC 基准缺失: $baseline")
        val expected = GoldenNormalizer.readBaseline(baseline.toFile())
        val actual = GoldenNormalizer.normalize(after)
        val diff = GoldenNormalizer.diff(expected, actual)
        assertTrue(
            diff.identical,
            "去重后输出与 PC 基准不一致（基准 ${expected.size} 行 / 实际 ${actual.size} 行），首差 ${diff.firstMismatch}:\n${diff.preview}",
        )
    }
}
