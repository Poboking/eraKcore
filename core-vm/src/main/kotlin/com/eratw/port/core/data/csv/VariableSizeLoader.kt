package com.eratw.port.core.data.csv

import com.eratw.port.core.vars.VariableCode
import com.eratw.port.core.vars.VariableSizes
import java.nio.file.Path

/**
 * `VariableSize.CSV` 装载。spec: ConstantData.loadVariableSizeData / changeVariableSizeData
 * / decideActualArraySize（ConstantData.cs:262-307 / :309-551 / :553-670）。
 *
 * 行格式：`变量名,尺寸[,第2维[,第3维]]`。
 *
 * 落地范围（VARSIZE-FULL）：
 * - 名字解析、0 维/计算变量/RANDDATA 拒绝，照 `changeVariableSizeData` 的前置判定（:317-332）；
 * - 0 长度拒绝（ArrayLengthIs0，:342-345）；负数仅在 CanForbid 变量上改写成 0（禁用数组，
 *   :347-356），非 CanForbid 负数拒绝（CanNotDisableVarArrayLengthIsNegative，:347-351）；
 * - 1 维非局部变量尺寸下限 100、单维上限 1,000,000（:358-379）；
 * - 2 维必须有第 2 维、每维 1..1,000,000、总元素 ≤1,000,000（:380-411）；
 * - 3 维必须有第 3 维、每维 1..1,000,000、总元素 ≤10,000,000（:412-448）；
 * - 写入分派与 ITEMPRICE/ITEMNAME、STR 特例交给 [VariableSizes.applySize]；
 * - 全部行读完调 [VariableSizes.decideActualArraySize] 做主数组↔名字表联动。
 *
 * "禁用数组"在存储层的形态是 dims 含 0（[com.eratw.port.core.vars.VarDef.isForbid]，
 * VariableStore.checkElement 对 IsForbid 抛 CodeEE），与原版 0 长度数组的访问语义一致。
 *
 * 两处**有意的偏离**（官方 bug 不复刻，因为复刻 = 直接 OOM 崩游戏机）：
 * - 2 维总元素判定 `result3 * result > 1000000`（ConstantData.cs:406）与 3 维的
 *   `result3 * result * result2 > 10000000`（:443）里三个操作数都是 **int**，乘积按 int 回绕：
 *   `DIM,1000000,1000000` → 10^12 回绕成 -726,768,768 → 判定通过 → 原版去分配 10^12 个元素。
 *   这里用 Long 乘（[MAX_2D_ELEMENTS] / [MAX_3D_ELEMENTS]）⇒ 该形态被判超限跳过。
 *   实测本语料 `CSV/VariableSize.csv` 102 行（96 行 2 列 / 6 行 3 列）最大单维 20000、
 *   多维行只有 6 条 `×,100,100`，无任何乘积 ≥ 2^31 的行 ⇒ 两侧在本语料同解（2026-10-22 实测）。
 * - 1 维的 `LocalVarSizeCanNotLessThan1`（ConstantData.cs:363-367，局部变量尺寸 < 1）在这里**不实现**：
 *   `result3 <= 0` 已在 :340-357 全部 return（0 → ArrayLengthIs0，负 → CanForbid 分支），
 *   走到 :363 时 result3 必 ≥ 1 ⇒ 原版该分支同样是死代码。
 */
object VariableSizeLoader {

    private const val MAX_DIM = 1_000_000
    private const val MAX_2D_ELEMENTS = 1_000_000L
    private const val MAX_3D_ELEMENTS = 10_000_000L

    fun load(dir: Path, sizes: VariableSizes, warnings: MutableList<String>) {
        val path = CsvLoader.resolveFile(dir, "VariableSize.csv") ?: return
        for (raw in CsvLoader.readEnabledLines(path, warnings, "VariableSize.csv")) {
            changeOne(raw, sizes, warnings)
        }
        sizes.decideActualArraySize(warnings)
    }

    /** 单行 = `changeVariableSizeData`（ConstantData.cs:309-551）。 */
    private fun changeOne(raw: String, sizes: VariableSizes, warnings: MutableList<String>) {
        val cols = CsvLoader.splitRow(raw)
        if (cols.size < 2) {
            warnings.add("VariableSize.csv: 列数不足，已跳过（$raw）")
            return
        }
        val name = cols[0].trim()
        val code = sizes.variableCode(name)
        if (code == null) {
            // CanNotInterpretVarName（ConstantData.cs:317-322）
            warnings.add("VariableSize.csv: 未知或不可改尺寸的变量（$name）")
            return
        }
        if (!sizes.isArray(code)) {
            // CanNotChange0DVarSize（ConstantData.cs:323-327）
            warnings.add("VariableSize.csv: 0 维变量不可改尺寸（$name）")
            return
        }
        if (sizes.isCalc(code) || code == VariableCode.RANDDATA) {
            // CanNotChangeVarSize（ConstantData.cs:328-332）
            warnings.add("VariableSize.csv: 该变量尺寸不可修改（$name）")
            return
        }
        val first = cols[1].trim().toIntOrNull()
        if (first == null) {
            warnings.add("VariableSize.csv: 尺寸非整数，已跳过（$raw）")
            return
        }
        if (first <= 0) {
            // ConstantData.cs:340-357 的 result3 <= 0 分支。
            if (first == 0) {
                // ArrayLengthIs0（:342-345）：0 对所有变量都非法，禁用要写负值。
                warnings.add("VariableSize.csv: 数组长度无法为 0（禁用该变量请将长度设置为负值），已跳过（$raw）")
                return
            }
            if (!sizes.canForbid(code)) {
                // CanNotDisableVarArrayLengthIsNegative（:347-351）
                warnings.add("VariableSize.csv: 该变量不可禁用，数组长度不能为负，已跳过（$raw）")
                return
            }
            // :352-356：多余尺寸只按第 1 维口径告警；:356 的 result3 = 0 落到写入 switch，
            // 各维都取 0（CanForbid 变量在本版全是 1 维，这里按实际维度构造全 0）。
            warnIgnoredExtraDims(cols, 2, "1", warnings)
            val dims = IntArray(VariableCode.dimension(code)) { 0 }
            if (!sizes.applySize(code, dims)) {
                // VarSizeAlreadyDefined（ConstantData.cs:547-550）
                warnings.add("VariableSize.csv: 变量尺寸重复定义（$name），以最后一次为准")
            }
            return
        }
        val dims = when (VariableCode.dimension(code)) {
            1 -> {
                warnIgnoredExtraDims(cols, 2, "1", warnings)
                // 原版 :363-367 的 `IsLocal && result3 < 1` 不实现：外层 :340-357 已把
                // result3 <= 0 全部 return，到这里 result3 必 >= 1 ⇒ 原版该分支同为死代码。
                if (!sizes.isLocal(code) && first < 100) {
                    // InternalVarSizeCanNotLessThan100（:369-373）
                    warnings.add("VariableSize.csv: 内部变量尺寸不能小于 100，已跳过（$raw）")
                    return
                }
                if (first > MAX_DIM) {
                    // OneDVarSizeCanNotGreaterThan1M（:374-378）
                    warnings.add("VariableSize.csv: 1 维变量尺寸不能超过 $MAX_DIM，已跳过（$raw）")
                    return
                }
                intArrayOf(first)
            }
            2 -> {
                if (cols.size < 3) {
                    warnings.add("VariableSize.csv: 2 维变量缺少第 2 个尺寸，已跳过（$raw）")
                    return
                }
                warnIgnoredExtraDims(cols, 3, "2", warnings)
                val second = cols[2].trim().toIntOrNull()
                if (second == null) {
                    warnings.add("VariableSize.csv: 第 2 个尺寸非整数，已跳过（$raw）")
                    return
                }
                if (first < 1 || second < 1) {
                    warnings.add("VariableSize.csv: 2 维尺寸不能小于 1，已跳过（$raw）")
                    return
                }
                if (first > MAX_DIM || second > MAX_DIM) {
                    warnings.add("VariableSize.csv: 2 维单维尺寸不能超过 $MAX_DIM，已跳过（$raw）")
                    return
                }
                if (first.toLong() * second > MAX_2D_ELEMENTS) {
                    // ConstantData.cs:406 是 int×int（会回绕），这里按 Long 精确判 ⇒ 有意偏离，见类 KDoc
                    warnings.add(
                        "VariableSize.csv: 2 维总元素不能超过 $MAX_2D_ELEMENTS，已跳过（$raw）",
                    )
                    return
                }
                intArrayOf(first, second)
            }
            else -> {
                if (cols.size < 4) {
                    warnings.add("VariableSize.csv: 3 维变量缺少第 3 个尺寸，已跳过（$raw）")
                    return
                }
                warnIgnoredExtraDims(cols, 4, "3", warnings)
                val second = cols[2].trim().toIntOrNull()
                if (second == null) {
                    warnings.add("VariableSize.csv: 第 2 个尺寸非整数，已跳过（$raw）")
                    return
                }
                val third = cols[3].trim().toIntOrNull()
                if (third == null) {
                    warnings.add("VariableSize.csv: 第 3 个尺寸非整数，已跳过（$raw）")
                    return
                }
                if (first < 1 || second < 1 || third < 1) {
                    warnings.add("VariableSize.csv: 3 维尺寸不能小于 1，已跳过（$raw）")
                    return
                }
                if (first > MAX_DIM || second > MAX_DIM || third > MAX_DIM) {
                    warnings.add("VariableSize.csv: 3 维单维尺寸不能超过 $MAX_DIM，已跳过（$raw）")
                    return
                }
                if (first.toLong() * second * third > MAX_3D_ELEMENTS) {
                    // ConstantData.cs:443 是 int×int×int（会回绕），这里按 Long 精确判 ⇒ 有意偏离，见类 KDoc
                    warnings.add(
                        "VariableSize.csv: 3 维总元素不能超过 $MAX_3D_ELEMENTS，已跳过（$raw）",
                    )
                    return
                }
                intArrayOf(first, second, third)
            }
        }
        if (!sizes.applySize(code, dims)) {
            // VarSizeAlreadyDefined（ConstantData.cs:547-550）
            warnings.add("VariableSize.csv: 变量尺寸重复定义（$name），以最后一次为准")
        }
    }

    /**
     * 多余维度告警（IgnoreNDData，ConstantData.cs:352-355 / :387-390 / :419-422）：
     * 只有"下一列存在、非空、首字符是数字"才算多写的尺寸。
     */
    private fun warnIgnoredExtraDims(
        cols: List<String>,
        index: Int,
        dim: String,
        warnings: MutableList<String>,
    ) {
        if (cols.size <= index) return
        val extra = cols[index].trim()
        if (extra.isEmpty() || !extra[0].isDigit()) return
        warnings.add("VariableSize.csv: 忽略第 ${index + 1} 个及之后的尺寸（按 $dim 维变量解析）")
    }
}
