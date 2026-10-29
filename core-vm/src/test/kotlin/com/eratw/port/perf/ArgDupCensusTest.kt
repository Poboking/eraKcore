package com.eratw.port.perf

import com.eratw.port.boot.RealCorpus
import com.eratw.port.boot.RealCorpusCompile
import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions

/**
 * 语句 `args` 子树重复率普查的入口（`ERATW_CORPUS` 指向语料 `ERB` 子树才展开）。
 *
 * `ERATW_ARGDUP_LIMIT=<n>` 只编译前 n 个文件（开发期快速迭代用；缺省无此变量 ⇒ 本用例
 * 计 skipped，不再静默 PASS —— CMP-68）。
 * 读数落到 `build/argdup-census.txt`（build 目录，不进 git）。
 */
class ArgDupCensusTest {

    @Test
    fun `语句args子树结构重复率普查`() {
        val limit = System.getenv("ERATW_ARGDUP_LIMIT")?.toIntOrNull()
        // CMP-68：两个前置（LIMIT 未设 / 语料缺失）都走 assumption（计 skipped），
        // 不许 println+return 静默 PASS。触发条件见交付报告的 skipped 期望值表。
        Assumptions.assumeTrue(
            limit != null,
            "未设 ERATW_ARGDUP_LIMIT：开发期普查装置按假设跳过（计 skipped）",
        )
        val corpus = RealCorpus.load()
        Assumptions.assumeTrue(
            corpus != null,
            "未找到真实语料（ERATW_CORPUS）：普查按假设跳过（计 skipped）",
        )
        val model = RealCorpusCompile.compile(requireNotNull(corpus), requireNotNull(limit))
        val program = model.program ?: throw AssertionError("编译失败: ${model.buildError}")
        val report = ArgDupCensus.census(program, SizeModel(SizeModel.detectRefWidth()))
        // 交叉验证：同一份 Program 用 PoolDecomp 的 ResidentWalker（全字段、身份去重）走一遍，
        // 确认本普查没有漏掉字段（两侧字节应在同一量级、对象数口径差在 String/背衬数组）。
        val w = com.eratw.port.core.perf.ResidentWalker(SizeModel(SizeModel.detectRefWidth()))
        val objectsBefore = w.objects
        for (i in 0 until program.statementCount) w.walk(program.statement(i))
        val walkerBytes = w.bytes
        val walkerObjects = w.objects - objectsBefore
        val text = buildString {
            appendLine("# 语句 args 子树结构重复率（$limit 文件上限 / ${program.statementCount} 语句）")
            appendLine()
            appendLine("> 交叉验证（ResidentWalker 全字段、身份去重）：${report.bytes(walkerBytes)} / $walkerObjects 对象；" +
                "本普查 ${report.bytes(report.shallowBytes)} / ${report.instances} 对象（差 = String 与容器背衬的记账口径）")
            appendLine()
            appendLine("| 类 | 实例 | 形态 | 重复 | 重复率 | 浅尺寸 | 可回收 |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|")
            for (r in report.rows) {
                appendLine(
                    "| ${r.cls} | ${r.instances} | ${r.forms} | ${r.dup} | ${r.dupPct}% | " +
                        "${report.bytes(r.shallowBytes)} | ${report.bytes(r.reclaimBytes)} |",
                )
            }
            appendLine()
            appendLine("- 实例合计 ${report.instances} / 形态 ${report.forms} / 重复 ${report.dup}")
            appendLine("- 浅尺寸合计 ${report.bytes(report.shallowBytes)} / 可回收上限 ${report.bytes(report.reclaimBytes)}")
            appendLine("- 字符串持有者前 15：${report.stringOwners}")
            appendLine("- 遍历 ${report.walkMs}ms")
        }
        File("build/argdup-census.txt").apply { parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
        println(text)
    }
}
