package com.eratw.port.spec

import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.PrintSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRINT 族 per-alias flag 断言 —— verify-notes.md §10.3 / §11 遗留的「运行期对拍」收口。
 *
 * 三个**互相独立**的实现必须逐名一致（86 = PRINT 77 + PRINT_DATA 9）：
 *  ① C# 原文：PRINT_Instruction / PRINT_DATA_Instruction 构造器按语句名后缀单遍解析置位
 *     （FunctionIdentifier.cs:85-171 / :233-268；注册调用只有名字没有字面量 ⇒
 *     无法静态求值，只能按别名定值）；
 *  ② 规约表：`specs/isa.yaml` 的 `flag_by_alias`（由 tools/spec_crosscheck.py 的
 *     NameSuffixSim 对 ① 逐名模拟生成；`--emit-flag-by-alias` 可整体重生成）；
 *  ③ 移植侧：[PrintSpec.parse]（① 的 Kotlin 逐行移植，PRINT 家族 77 名在构造期算 flag）。
 *
 * 名义上这是「规约 vs 移植」的对拍闸：②与③任何一侧漂移（改文法、改位义、笔误）
 * 都会在这里炸。PRINT_DATA 家族 9 名移植侧未建模 flag（[com.eratw.port.core.stmt.PrintDataStmt]
 * 不算 flag，行为走 code 身份），只断言「已在 FunctionCode 注册 + 表内覆盖」。
 */
class PrintFlagByAliasTest {

    private data class AliasTable(val print: Map<String, Int>, val printData: Map<String, Int>)

    private fun specFile(): File {
        val candidates = listOf("../specs/isa.yaml", "specs/isa.yaml", "../../specs/isa.yaml")
        for (c in candidates) {
            val f = File(c)
            if (f.isFile) return f
        }
        error("找不到 specs/isa.yaml（工作目录 ${File(".").absolutePath}）")
    }

    /**
     * 从 isa.yaml 抽两个 `flag_by_alias` 映射。映射块是
     * tools/spec_crosscheck.py `--emit-flag-by-alias` 的机器产物，格式稳定：
     * 条目头 `- name: PRINT` / `- name: PRINT_DATA`，块头 `  flag_by_alias:`，
     * 键行 `    ALIAS: INT`（4 空格缩进）；`  flag_source_by_alias:` 块的值是
     * 带引号串，天然不匹配键行形状，仍显式跳过以防未来格式漂移。
     */
    private fun parseFlagByAlias(): AliasTable {
        val lines = specFile().readLines(Charsets.UTF_8)
        val print = LinkedHashMap<String, Int>()
        val printData = LinkedHashMap<String, Int>()
        var target: MutableMap<String, Int>? = null
        var inSourceBlock = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("- name: ") -> {
                    target = when (line.removePrefix("- name: ").trim()) {
                        "PRINT" -> print
                        "PRINT_DATA" -> printData
                        else -> null
                    }
                    inSourceBlock = false
                }
                line == "  flag_by_alias:" -> inSourceBlock = false
                line == "  flag_source_by_alias:" -> inSourceBlock = true
                target != null && !inSourceBlock && line.startsWith("    ") -> {
                    val m = Regex("([A-Z][A-Z0-9_]*): (\\d+)$").find(line.trim())
                    if (m != null) target[m.groupValues[1]] = m.groupValues[2].toInt()
                }
            }
        }
        return AliasTable(print, printData)
    }

    @Test
    fun `PRINT 家族 77 名的移植侧 flag 与 isa_yaml flag_by_alias 逐名一致`() {
        val table = parseFlagByAlias().print
        assertEquals(77, table.size, "isa.yaml PRINT 条目的 flag_by_alias 应为 77 名")

        var checked = 0
        for ((alias, expected) in table) {
            val code = FunctionCode.valueOf(alias)
            val spec = PrintSpec.parse(code)
            assertNotNull(spec, "$alias 应被 PrintSpec.parse 接受（PRINT 文法名）")
            assertEquals(expected, spec.flag, "$alias 的移植侧 flag 与 isa.yaml flag_by_alias 不一致")
            checked++
        }
        assertEquals(77, checked)
    }

    @Test
    fun `PRINT_DATA 家族 9 名已在 FunctionCode 注册且表内覆盖`() {
        val table = parseFlagByAlias().printData
        assertEquals(9, table.size, "isa.yaml PRINT_DATA 条目的 flag_by_alias 应为 9 名")
        for (alias in table.keys) {
            assertTrue(
                FunctionCode.entries.any { it.name == alias },
                "$alias 应在 FunctionCode 枚举注册",
            )
        }
        // 移植侧 PrintDataStmt 不建模 AInstruction.flag（C# :233-268 的逐后缀置位未移植，
        // 运行期行为由 code 身份与执行器分支承载），因此 9 名可断的只有注册与表内覆盖；
        // 若要补 PRINT_DATA 的 flag 模型，属独立路线（需先在 core/stmt 落构造器移植）。
    }

    @Test
    fun `两族解析口径互斥 —— PRINT 文法只认 PRINT 77 名`() {
        // PRINTDATA 家族名（PRINTDATA…）喂给 PrintSpec.parse 必须返回 null：
        // "DATA…" 会被 D 分支吃一个字符后 EOS 判定失败（C# :167-170 抛 ExeEE 的同构），
        // 防两族解析器串线。
        val table = parseFlagByAlias()
        for (alias in table.print.keys) {
            assertNotNull(PrintSpec.parse(FunctionCode.valueOf(alias)), "$alias 应属 PRINT 文法")
        }
        for (alias in table.printData.keys) {
            assertNull(PrintSpec.parse(FunctionCode.valueOf(alias)), "$alias 不应被 PRINT 文法接受")
        }
    }
}
