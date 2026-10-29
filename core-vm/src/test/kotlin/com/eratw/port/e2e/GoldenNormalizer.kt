package com.eratw.port.e2e

import java.io.File

/**
 * 输出口径归一化 + diff（docs/golden-runbook.md §5 / §6 / §8.3 的逐条装置化）。
 *
 * 规则（顺序执行，**不得放宽**）：
 *  1. `\r` 去除（Windows 换行）。
 *  2. 截取：从 `=== GOLDEN RUN START ===` 起（§8.3 的"二选一"取截取方案，
 *     同时天然剔除 PC 装载期 `函数"@..."已经被定义过` 告警行）；`=== END ===` 行为终点（含）。
 *  3. 填充条**保长度**折叠（GOLDEN-COVER）：`^={10,}$` → `LINE(=)×<字符数>`；
 *     `^-{10,}$` → `LINE(-)×<字符数>`（字符数 = 原行字符数；长度不一致即进 diff，
 *     宽度敏感行不再被定长哨兵遮蔽。runbook §5 的 sed 示例仍是定长版，装置口径从严）。
 *  4. `=== SECTION:RNG-* ===` 段整段剔除至下一个 SECTION 头或 END（§6：RNG 只对
 *     完全同序列实现有效，通用 L2 diff 排除）。
 *
 * 基准策略：`pc-golden-root.txt`（组长另线采集，**存在即为唯一权威 diff 目标**）→
 * 缺失时用 `self-golden-root.txt`（本装置自跑回归快照）。快照只在
 * 不存在或 `ERATW_E2E_UPDATE=1` 时覆写——实现变动会撞红快照，这是有意的回归闸。
 */
object GoldenNormalizer {

    const val PC_BASELINE = "pc-golden-root.txt"
    const val SELF_BASELINE = "self-golden-root.txt"

    const val START_MARKER = "=== GOLDEN RUN START ==="
    const val END_MARKER = "=== END ==="

    private val SECTION_HEADER = Regex("^=== SECTION:(.*) ===$")
    private val LINE_EQUALS = Regex("^={10,}$")
    private val LINE_DASH = Regex("^-{10,}$")

    fun normalize(raw: String): List<String> {
        val all = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var from = 0
        val si = all.indexOfFirst { it.trim() == START_MARKER }
        if (si >= 0) from = si
        val ei = (from until all.size).firstOrNull { all[it].trim() == END_MARKER }
        val out = ArrayList<String>()
        var inRng = false
        val endInclusive = if (ei != null) ei + 1 else all.size
        for (i in from until endInclusive) {
            var s = all[i].removeSuffix("\r")
            val m = SECTION_HEADER.find(s.trim())
            if (m != null) {
                inRng = m.groupValues[1].startsWith("RNG-")
                if (inRng) continue
            }
            if (inRng) continue
            // §8.3 备选剔除路径（未截到 START 时仍可能带进装载告警）
            if (s.startsWith("函数\"@")) continue
            s = when {
                LINE_EQUALS.matches(s) -> "LINE(=)×${s.length}"
                LINE_DASH.matches(s) -> "LINE(-)×${s.length}"
                else -> s
            }
            out.add(s)
        }
        // 末尾因换行产生的空尾行剔除（只剔尾部，中间空行是有效输出）
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    data class DiffResult(val identical: Boolean, val firstMismatch: Int, val preview: String) {
        override fun toString(): String = preview
    }

    fun diff(expected: List<String>, actual: List<String>): DiffResult {
        if (expected == actual) return DiffResult(true, -1, "一致（${expected.size} 行）")
        var p = 0
        while (p < expected.size && p < actual.size && expected[p] == actual[p]) p++
        var se = expected.size - 1
        var sa = actual.size - 1
        while (se >= p && sa >= p && expected[se] == actual[sa]) { se--; sa-- }
        val sb = StringBuilder()
        sb.append("首个差异: 行 ${p + 1}（基准 ${expected.size} 行 vs 实际 ${actual.size} 行）\n")
        sb.append("--- 基准段 ---\n")
        for (i in p..se) sb.append("  -").append(i + 1).append(": ").append(expected[i]).append('\n')
        sb.append("--- 实际段 ---\n")
        for (i in p..sa) sb.append("  +").append(i + 1).append(": ").append(actual[i]).append('\n')
        return DiffResult(false, p + 1, sb.toString())
    }

    /** 读基准文件（PC 原始日志 = 未归一化文本，同样过 normalize 保证两口径对称）。 */
    fun readBaseline(file: File): List<String> = normalize(file.readText(Charsets.UTF_8))

    fun writeBaseline(file: File, lines: List<String>) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
