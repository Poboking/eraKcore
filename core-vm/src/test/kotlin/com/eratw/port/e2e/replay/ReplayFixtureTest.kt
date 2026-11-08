package com.eratw.port.e2e.replay

import com.eratw.port.e2e.GoldenModel
import com.eratw.port.e2e.GoldenPipeline
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 输入轨迹重放装置（CONTRACT-MODERN-R2）：一个 `.replay` 文件 = 一条 JUnit 动态用例。
 *
 * 装置构成：
 * - `fixtures/replay/REPLAY_SCRIPTS.ERB`：全部用例的脚本（一用例一个 `@` 函数）；
 * - `fixtures/replay/cases/<name>.replay`：输入轨迹 + 期望（格式见 [ReplayCaseParser]）；
 * - [ReplayRunner]：生产 `QueueInputSink` + 第二条线程投喂 + 控制台/变量对拍。
 *
 * 期望值纪律：全部由人工从反编译 C# 推导，每条 `.replay` 头部带 `anchor: 文件:行号`；
 * 本装置**没有** update 模式，不把引擎输出回写成期望（契约 §0-5）。
 */
class ReplayFixtureTest {

    companion object {
        // gradle test JVM 的 cwd = 模块目录（core-vm/），fixtures 在仓库根 —— 双路径探测（与 E2EGoldenTest 同口径）
        private val fixtureDir: Path = sequenceOf(File("fixtures/replay"), File("../fixtures/replay"))
            .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

        private val casesDir: Path = fixtureDir.resolve("cases")

        /** 一个 REPLAY_SCRIPTS.ERB 一个模型，全部用例共享（契约 §3.1-5）。 */
        private val pipeline: Result<GoldenModel> = runCatching { GoldenPipeline.build(fixtureDir) }

        private fun model(): GoldenModel =
            pipeline.getOrElse { throw AssertionError("重放装置管线构建失败: ${it.message}", it) }
    }

    /** 用例清单结构性断言：数量下限、名字唯一、目标函数在模型里真实存在。 */
    @Test
    fun `用例清单结构`() {
        val m = model()
        assertEquals(0, m.compileFailures.size, "REPLAY_SCRIPTS 切片编译失败:\n${m.compileFailures.joinToString("\n")}")
        val cases = ReplayRunner.loadAll(casesDir)
        assertTrue(cases.size >= 10, "用例数不足 10（实为 ${cases.size}）——契约 §3.4 下限")
        assertEquals(cases.size, cases.map { it.name }.toSet().size, "case 名重复：${cases.map { it.name }}")
        val known = m.program.functions.map { it.name.uppercase() }.toSet()
        val missing = cases.filter { it.target.uppercase() !in known }.map { "${it.name} -> ${it.target}" }
        assertTrue(missing.isEmpty(), "用例目标函数不在模型里（脚本缺 @ 函数或拼错）: $missing")
        val noAnchor = cases.filter { it.anchors.isEmpty() }.map { it.name }
        assertTrue(noAnchor.isEmpty(), "缺 anchor 的用例: $noAnchor")
    }

    /** 一条 `.replay` 一条动态用例；红就红，失败消息带来源文件与控制台首差。 */
    @TestFactory
    fun `输入轨迹重放`(): List<DynamicTest> {
        val m = model()
        val runner = ReplayRunner(m)
        val cases = ReplayRunner.loadAll(casesDir)
        return cases.map { c ->
            DynamicTest.dynamicTest("replay: ${c.name}") {
                val r = runner.run(c)
                assertTrue(
                    r.passed,
                    "[${c.source}] 用例 ${c.name} 失败（target=${c.target}，anchors=${c.anchors}）:\n" +
                        r.failures.joinToString("\n") +
                        "\n实际控制台增量:\n" + r.consoleDelta.joinToString("\n") { "  | $it" },
                )
            }
        }
    }
}
