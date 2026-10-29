package com.eratw.port.e2e

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.err.CodeEE
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A6 · M2 端到端终验装置。
 *
 * 管线：erbcc 符号表（装载序）→ 语句切片 → ErbProgramCompiler（DimSpecLayout ←
 * com.erbcc.bridge.buildFuncDims）→ Program → ProcessRuntime + VirtualMachine 真执行
 * → 生产 sink（[com.eratw.port.core.host.LineBufferConsoleSink]，经 [ProductionConsoleSink] 门面读回文本行）→ [GoldenNormalizer]（runbook §5/§6）→ 与基准 diff。
 *
 * 基准策略（PC 基准由组长另线采集，落地前）：
 *  - diff 目标常量 = [GoldenNormalizer.PC_BASELINE]，存在即优先；
 *  - 缺失时用自跑回归快照 [GoldenNormalizer.SELF_BASELINE]（首次自动生成；
 *    `ERATW_E2E_UPDATE=1` 显式重刷——实现推进后由本 agent/组长重刷，不做静默自更新）。
 *
 * 口径纪律：**红就是红**。分节弹性执行如实记录 通过/失败(首个失败语句@文件:行 +
 * CodeEE 函数:行:pc)/阻塞(编译失败/未接线)，不删脚本、不删语句、不放宽归一化。
 * 报告落 `core-vm/build/e2e-report/`（build 目录，不进 git）。
 */
class E2EGoldenTest {

    companion object {
        // gradle test JVM 的 cwd = 模块目录（core-vm/），fixtures 在仓库根 —— 双路径探测
        private val fixtureDir: Path = sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .first { it.isDirectory }.toPath().toAbsolutePath().normalize()

        private val baselineDir: Path = fixtureDir.resolve("baseline")
        private val pcBaseline: File = baselineDir.resolve(GoldenNormalizer.PC_BASELINE).toFile()
        private val selfBaseline: File = baselineDir.resolve(GoldenNormalizer.SELF_BASELINE).toFile()

        /** 报告目录：core-vm/build/e2e-report（不是仓库路径，防污染 git）。每次运行重置，避免陈旧段。 */
        private val reportDir: File = File("build/e2e-report").apply {
            mkdirs()
            File(this, "e2e-report.md").writeText(
                "# E2E 终验报告（A6 装置 · build 目录，非仓库文档）\n\n- fixtures: $fixtureDir\n",
                Charsets.UTF_8,
            )
        }

        private val pipeline: Result<GoldenModel> = runCatching { GoldenPipeline.build(fixtureDir) }
        private val model: GoldenModel
            get() = pipeline.getOrElse { throw AssertionError("管线构建阻塞（上游中间态）: ${it.message}", it) }

        /** 分节弹性执行只跑一次，多个测试共享其结果。 */
        private val sectionRun: SectionRun by lazy { runSections(model) }

        enum class UnitStatus { PASS, FAIL, BLOCKED_COMPILE, SKIPPED, ERROR }

        class UnitResult(
            val title: String,
            val status: UnitStatus,
            val lines: List<String>,
            val lastSection: String,
            val failure: String?,
            val degraded: String? = null,
        )

        class SectionRun(
            val results: List<UnitResult>,
            val registerFailures: List<String>,
        ) {
            val captured: List<String> = results.flatMap { it.lines }
            fun byTitle(t: String): UnitResult? = results.firstOrNull { it.title == t }
        }

        // ------------------------------------------------------------------
        // 分节弹性执行器
        // ------------------------------------------------------------------

        private fun runSections(m: GoldenModel): SectionRun {
            val console = GoldenPipeline.newConsole()
            val rt = GoldenPipeline.newRuntime(m, console, stepCap = 2_000_000)
            val regFailures = GoldenPipeline.lastRegisterFailures.toList()
            val results = ArrayList<UnitResult>()
            var emitted = 0
            for (u in m.units) {
                if (u.skippable) {
                    results.add(UnitResult(u.title, UnitStatus.SKIPPED, emptyList(), "-", u.skipReason))
                    continue
                }
                val compileFail = m.compileFailures.firstOrNull { it.startsWith("${u.sigKey} ") }
                if (compileFail != null) {
                    results.add(UnitResult(u.title, UnitStatus.BLOCKED_COMPILE, emptyList(), "-", compileFail))
                    continue
                }
                var status = UnitStatus.PASS
                var failure: String? = null
                try {
                    rt.callFunction(u.sigKey)
                } catch (e: CodeEE) {
                    status = UnitStatus.FAIL
                    failure = GoldenPipeline.describeFailure(m.program, m, e)
                } catch (e: Exception) {
                    status = UnitStatus.ERROR
                    failure = "${e::class.simpleName}: ${e.message}"
                }
                val all = console.snapshot()
                val unitLines = all.drop(minOf(emitted, all.size))
                emitted = all.size
                val lastSection = unitLines.lastOrNull { it.trim().startsWith("=== SECTION:") }?.trim() ?: "-"
                results.add(UnitResult(u.title, status, unitLines, lastSection, failure, null))
            }
            return SectionRun(results, regFailures)
        }

        private fun appendReport(section: String) {
            val f = File(reportDir, "e2e-report.md")
            if (!f.exists()) f.writeText("# E2E 终验报告（A6 装置 · build 目录，非仓库文档）\n\n- fixtures: $fixtureDir\n", Charsets.UTF_8)
            f.appendText(section, Charsets.UTF_8)
        }

        private fun stackTail(t: Throwable): String =
            t.stackTraceToString().lines().take(40).joinToString("\n")

        /**
         * M2 执行层验收必过节。其余节依赖 M3+ 能力（视图/IO/XML/音频）或游戏数据
         * （chara CSV），其失败按分节报告如实记录，不作为 M2 红。
         */
        private val M2_REQUIRED_SECTIONS = listOf(
            "ROOT-INFO", "GOLDEN_BASIC", "GOLDEN_FLOW",
            "GOLDEN_DATA", "GOLDEN_INPUT_AUTO", "GOLDEN_SYMBOLS",
        )

        /**
         * 能力缺口白名单（CMP-63）：**显式表，不做异常文案子串匹配**。
         *
         * 表项按「失败语句码 / 失败函数名 / pc」三元组登记，只用于**归因措辞**：命中也不会
         * 解除硬闸 —— 全量对拍要求执行到 `=== END ===` 且 `runError == null`，任何中断都直接
         * fail。放行一条缺口只能在产线补齐能力；往本表加条目必须带产线行号，不许改文案绕闸。
         */
        private data class CapabilityGap(
            val stmtCode: String?,
            val func: String?,
            val pc: Int?,
            val reason: String,
        )

        private val CAPABILITY_GAP_WHITELIST: List<CapabilityGap> = listOf(
            CapabilityGap("REF", null, null, "REF 语句未实现（StatementExecutor.kt:1162-1163）"),
            CapabilityGap("REFBYNAME", null, null, "REFBYNAME 语句未实现（StatementExecutor.kt:1162-1163）"),
            CapabilityGap("SAVEVAR", null, null, "SAVEVAR 语句未实现（StatementExecutor.kt:1162-1163）"),
            CapabilityGap("LOADVAR", null, null, "LOADVAR 语句未实现（StatementExecutor.kt:1162-1163）"),
        )

        /** 从 CodeEE 的 pc/函数名解出失败点，与白名单做结构化比对（不比异常文案）。 */
        private fun capabilityGapAt(program: Program, e: Throwable): CapabilityGap? {
            if (e !is CodeEE) return null
            val pc = e.pc
            val stmtCode = if (pc in 0 until program.instructionCount) {
                val op = program.op(pc)
                if (!Opcode.isInternal(op) && op <= 197 && program.a(pc) in 0 until program.statementCount) {
                    program.statement(program.a(pc)).code.name
                } else {
                    Opcode.nameOf(op)
                }
            } else {
                null
            }
            return CAPABILITY_GAP_WHITELIST.firstOrNull {
                (it.stmtCode == null || it.stmtCode == stmtCode) &&
                    (it.func == null || it.func == e.funcName?.uppercase()) &&
                    (it.pc == null || it.pc == pc)
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    @Test
    fun `金标管线构建全函数编译`() {
        val m = pipeline.getOrElse {
            throw AssertionError("管线构建失败（阻塞方=上游包中间态）: ${it.message}\n${stackTail(it)}")
        }
        assertEquals(0, m.compileFailures.size, "切片编译失败:\n${m.compileFailures.joinToString("\n")}")
        assertTrue(m.program.functions.isNotEmpty(), "无函数产出")
        assertEquals("GOLDEN_ROOT", m.program.functions[m.program.entryFunction].name)
        // 反向证明：装载规则在装置里有实物 —— 重名胜者以裸键注册、被遮蔽成员在 <KEY>#<defIndex> 下
        val names = m.program.functions.map { it.name }
        assertTrue("G12_PLAIN" in names && names.any { it.startsWith("G12_PLAIN#") }, "重名遮蔽注册缺失: $names")
        assertTrue("G12_FUNC" in names && names.any { it.startsWith("G12_FUNC#") }, "重名方法遮蔽注册缺失")
        assertTrue("EVENTTURNEND" in names, "事件入口未合成")
        appendReport(
            "## 管线\n- stats=${m.stats}\n- 编译失败=${m.compileFailures.size}\n" +
                m.compileFailures.joinToString("\n") { "- $it" } + "\n" +
                m.notes.joinToString("\n") { "- 备忘: $it" } + "\n",
        )
    }

    @Test
    fun `全量执行与基准对拍`() {
        GoldenPipeline.requireGameCsvDir()
        val m = model
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(m, console, stepCap = 20_000_000)
        var runError: String? = null
        var gap: CapabilityGap? = null
        try {
            rt.execute()
        } catch (e: Throwable) {
            runError = GoldenPipeline.describeFailure(m.program, m, e)
            gap = capabilityGapAt(m.program, e)
        }
        val actual = GoldenNormalizer.normalize(console.capturedText())
        File(reportDir, "full-run.norm.txt").writeText(actual.joinToString("\n") + "\n", Charsets.UTF_8)

        val update = System.getenv("ERATW_E2E_UPDATE") == "1"
        val selfWasNew = !selfBaseline.exists()
        if (update || selfWasNew) {
            Files.createDirectories(baselineDir)
            GoldenNormalizer.writeBaseline(selfBaseline, actual)
        }
        // diff 目标：PC 基准存在即权威；否则自跑回归快照
        val target = if (pcBaseline.exists()) pcBaseline else selfBaseline
        val skipDiff = selfWasNew && !update && target == selfBaseline
        val expected = GoldenNormalizer.readBaseline(target)
        val d = if (skipDiff) GoldenNormalizer.DiffResult(true, -1, "首次生成自跑快照，无对比对象") else GoldenNormalizer.diff(expected, actual)

        // CMP-63：硬闸前置条件 = 执行到 `=== END ===` 且 runError == null。不再按
        // 「尚未实现 / 需要宿主提供 / M3+」文案子串自动推迟 —— 这三条串由产线抛出，任一语句
        // 从「已接」退回「未接」都会让最强 PC 对拍自动变无操作并记绿。
        val reachedEnd = actual.lastOrNull() == GoldenNormalizer.END_MARKER
        val hardGateEnabled = runError == null && reachedEnd
        val sb = StringBuilder("\n## 全量对拍\n")
        sb.appendLine(
            "- 硬闸: " + if (hardGateEnabled) {
                "已启用（全量到底，要求逐行一致）"
            } else {
                "**未启用**（无 ${GoldenNormalizer.END_MARKER} 或 runError != null ⇒ 本用例直接红）"
            },
        )
        sb.appendLine(
            "- 全量执行: " + when {
                runError != null -> "中断于: $runError"
                reachedEnd -> "到底(${GoldenNormalizer.END_MARKER})"
                else -> "无异常但缺终点（未输出 ${GoldenNormalizer.END_MARKER}）"
            },
        )
        if (runError != null) {
            sb.appendLine(
                "- 中断归因: " + (gap?.let { g ->
                    "命中能力缺口白名单[${CAPABILITY_GAP_WHITELIST.indexOf(g) + 1}] " +
                        "stmt=${g.stmtCode ?: "*"} func=${g.func ?: "*"} pc=${g.pc ?: "*"} :: ${g.reason}（白名单只作归因，仍判红）"
                } ?: "不在能力缺口白名单（按执行层回归处理）"),
            )
        }
        sb.appendLine("- diff 目标: ${target.name}（基准 ${expected.size} 行 / 实际 ${actual.size} 行${if (update) "，ERATW_E2E_UPDATE=1 已重刷自快照" else ""}）")
        sb.append(d.preview).append('\n')
        appendReport(sb.toString())
        assertTrue(
            hardGateEnabled,
            "硬闸未启用：全量执行未到 ${GoldenNormalizer.END_MARKER} 或 runError != null" +
                "（实际=${runError ?: "无异常但缺终点"}）—— CMP-63 起不再按文案推迟，直接判红",
        )
        assertTrue(d.identical, "输出与基准(${target.name})不一致，首差行 ${d.firstMismatch}:\n${d.preview}")
    }

    @Test
    fun `分节弹性执行与状态报告`() {
        val run = sectionRun
        val sb = StringBuilder("\n## 分节弹性执行\n| 节 | 状态 | 捕获行数 | 末节头 | 首个失败语句/阻塞原因 | 降级标注 |\n|---|---|---|---|---|---|\n")
        for (u in run.results) {
            sb.appendLine("| ${u.title} | ${u.status} | ${u.lines.size} | ${u.lastSection} | ${u.failure ?: "-"} | ${u.degraded ?: "-"} |")
        }
        File(reportDir, "section-run.norm.txt").writeText(run.captured.joinToString("\n") + "\n", Charsets.UTF_8)
        File(reportDir, "sections.csv").writeText(
            buildString {
                appendLine("节,状态,捕获行数,末节头,失败/阻塞,降级")
                run.results.forEach {
                    appendLine("\"${it.title}\",${it.status},${it.lines.size},\"${it.lastSection}\",\"${(it.failure ?: "").replace("\"", "'")}\",\"${(it.degraded ?: "").replace("\"", "'")}\"")
                }
            },
            Charsets.UTF_8,
        )
        appendReport(sb.toString())
        if (run.registerFailures.isNotEmpty()) {
            appendReport("\n### 用户变量注册失败\n" + run.registerFailures.joinToString("\n") { "- $it" } + "\n")
        }
        // 本测试如实入报告（M3+ 节红不拦截）；M2 必过节另设硬闸。
        assertTrue(run.results.isNotEmpty(), "无节被执行")
        val notPass = M2_REQUIRED_SECTIONS.mapNotNull { title ->
            val u = run.byTitle(title)
            when {
                u == null -> "$title=未执行"
                u.status != UnitStatus.PASS -> "$title=${u.status}（${u.failure ?: u.lastSection}）"
                else -> null
            }
        }
        assertTrue(notPass.isEmpty(), "M2 必过节未全 PASS:\n${notPass.joinToString("\n")}")
    }

    @Test
    fun `GOLDEN12 预期输出硬断言`() {
        // docs/golden-runbook.md §8.2 —— 唯一已固化的文字基准（不受自跑快照影响，规约硬断言）
        val expected = listOf(
            "=== SECTION:DUP-METHOD ===",
            "DUP-METHOD=2",
            "=== SECTION:DUP-PLAIN ===",
            "DUP-PLAIN=3",
            "=== SECTION:EVENT-BUCKET ===",
            "EVENT-DEFS=3 EXPECT-ORDER=PRI,NORMAL,LATER",
            "EVENT-BUCKET-REMARK=collect one turn end to see markers",
        )
        val unit = sectionRun.byTitle("GOLDEN_SYMBOLS")
            ?: throw AssertionError("GOLDEN_SYMBOLS 节未执行")
        val idx = unit.lines.indexOf(expected.first())
        assertTrue(
            idx >= 0,
            "GOLDEN_SYMBOLS 未输出 DUP-METHOD 段头；节状态=${unit.status}，失败点=${unit.failure}",
        )
        val block = unit.lines.subList(idx, minOf(idx + expected.size, unit.lines.size))
        assertEquals(expected, block, "§8.2 预期输出块不匹配（反向证明：先装载者胜/遮蔽失效会在此撞红）")
    }

    @Test
    fun `EVENTTURNEND 桶序断言`() {
        // runbook §8.4：定义序 NORMAL→PRI→LATER，执行序必须 PRI→NORMAL→LATER，位标记合 7
        val m = model
        val console = GoldenPipeline.newConsole()
        val rt = GoldenPipeline.newRuntime(m, console, stepCap = 5_000_000)
        val ok = rt.executeEvent("EVENTTURNEND")
        assertTrue(ok, "EVENTTURNEND 未注册/未派发")
        val lines = console.snapshot().filter { it.startsWith("EVENT-BUCKET-") }
        assertEquals(listOf("EVENT-BUCKET-PRI", "EVENT-BUCKET-NORMAL", "EVENT-BUCKET-LATER"), lines, "桶序输出错误")
        val bits = rt.store.readInt("RESULT", longArrayOf(1))
        assertEquals(7L, bits and 7L, "RESULT:1 位标记不全（漏执行成员），实测=$bits")
    }
}
