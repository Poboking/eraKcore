package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.stmt.FunctionCode

/**
 * PRINT_ABL / PRINT_TALENT / PRINT_MARK / PRINT_EXP / PRINT_PALAM 的角色数据字符串化。
 *
 * 原版位置：五条语句在 Process.cs 的 DoInstruction 里取角色位置参数后调
 * `vEvaluator.GetCharacterDataString`（PRINT_ABL/TALENT/MARK/EXP，spec: Process.cs:1171-1181）
 * 或 `vEvaluator.GetCharacterParamString`（PRINT_PALAM 逐参数 PrintC，spec: Process.cs:1182-1206）。
 * 字符串化的真正实现都在 VariableEvaluator（spec: VariableEvaluator.cs:1066-1140 与 :1142-1199），
 * 本文件按原逻辑逐条移植；语句分派在 [StatementExecutor]。
 *
 * 变量槽位对照（CharacterData.DataIntegerArray 的下标即 Kotlin 侧同名变量）：
 * ABL=2、TALENT=3、EXP=4、MARK=5、PALAM=6（spec: CharacterData.cs 的字段序，
 * 与本仓 `SystemLayout.charaInt1D` 的注册序一致：BASE/MAXBASE/ABL/TALENT/EXP/MARK/PALAM）。
 */
internal object PrintCharaData {

    /** 越界文案取官方简中语言包。spec: lang emuera-zhs.xml:312（Error.OoRCharaNum） */
    private const val OOR_CHARA = "试图引用一个不存在的注册角色"

    // ------------------------------------------------------------------
    // PRINT_ABL / PRINT_TALENT / PRINT_MARK / PRINT_EXP
    // ------------------------------------------------------------------

    /**
     * GetCharacterDataString 移植。spec: VariableEvaluator.cs:1066-1140
     *
     * 四个家族同一骨架（spec: VariableEvaluator.cs:1080）：遍历
     * `i < 角色数组长度 && i < CSV 名字表长度`，值非 0 且名字非空才输出：
     * - PRINT_ABL（:1076-1091）：名字 + "LV" + 值 + 半角空格；
     * - PRINT_TALENT（:1092-1106）：名字外包裹半角方括号，无空格分隔；
     * - PRINT_MARK（:1107-1122）：同 PRINT_ABL 形态；
     * - PRINT_EXP（:1123-1137）：名字 + 值 + 半角空格，**无 LV 前缀**。
     *
     * 值为 0 或 CSV 名字表该位为空 ⇒ 该项跳过（这就是基准里 ABL 1、3-11 的空档）。
     * 名字表空位原版是 null、本仓 `CsvNameTable[i]` 返回空串，IsNullOrEmpty 判断等价。
     */
    fun characterDataString(ctx: ExecContext, target: Long, code: FunctionCode): String {
        checkTarget(ctx, target) // spec: VariableEvaluator.cs:1069-1072
        val store = ctx.variables
        val (varName, tableName) = when (code) {
            FunctionCode.PRINT_ABL -> "ABL" to "ABL" // spec: VariableEvaluator.cs:1078-1079
            FunctionCode.PRINT_TALENT -> "TALENT" to "TALENT" // spec: VariableEvaluator.cs:1094-1095
            FunctionCode.PRINT_MARK -> "MARK" to "MARK" // spec: VariableEvaluator.cs:1109-1110
            FunctionCode.PRINT_EXP -> "EXP" to "EXP" // spec: VariableEvaluator.cs:1125-1126
            else -> throw CodeEE("PRINT 角色数据不支持语句 $code")
        }
        val nameTable = store.registry.csvTable(tableName) // spec: ConstantData.cs:159-162（GetCsvNameList）
        val arrayLen = store.getLength(varName, 0) // CharaVariableToken.GetLength：元素尺寸，不含角色位置
        val bound = minOf(arrayLen, nameTable.size) // spec: VariableEvaluator.cs:1080
        val sb = StringBuilder(100) // spec: VariableEvaluator.cs:1068
        for (i in 0 until bound) {
            val value = store.readInt(varName, longArrayOf(target, i.toLong()))
            val name = nameTable[i]
            if (value == 0L || name.isEmpty()) continue // spec: VariableEvaluator.cs:1082（值非 0 且名字非空）
            when (code) {
                FunctionCode.PRINT_TALENT -> { // spec: VariableEvaluator.cs:1100-1103
                    sb.append('[')
                    sb.append(name)
                    sb.append(']')
                }
                FunctionCode.PRINT_EXP -> { // spec: VariableEvaluator.cs:1131-1134：无 LV
                    sb.append(name)
                    sb.append(value)
                    sb.append(' ')
                }
                else -> { // PRINT_ABL / PRINT_MARK：spec: VariableEvaluator.cs:1084-1088 / :1114-1118
                    sb.append(name)
                    sb.append("LV")
                    sb.append(value)
                    sb.append(' ')
                }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // PRINT_PALAM
    // ------------------------------------------------------------------

    /**
     * GetCharacterParamString 移植。spec: VariableEvaluator.cs:1142-1199
     *
     * 返回 `PALAM 名 + 进度条 + 数值(右对齐宽 6)`；值 0 且名字为空的项返回 null
     * （调用方跳过，spec: VariableEvaluator.cs:1151-1154 + Process.cs:1193-1194）。
     *
     * 进度条阈值取**主变量 PALAMLV 的 1-4 号元素**（spec: VariableEvaluator.cs:1149-1175，
     * `varData.DataIntegerArray[6]`；PALAMLV 的 VariableCode 低 16 位 = 6
     * —— spec: VariableCode.cs:30 `PALAMLV = 655366`，且 VariableToken.cs:68
     * `VarCodeInt = varCode & __LOWERCASE__`，即主数据数组的 6 号槽）。档位字符：
     * 未过 1 档用 '-'，过 1 档 '='、2 档 '>'、3 档 '*'（与原版同一 cascade）。
     *
     * 条形：阈值 <= 0 或值已达当前档 ⇒ 当前档字符 × 10；值 <= 0 ⇒ '.' × 10；
     * 否则 = 档字符 × (值×10/阈值) + '.' 补齐 10 格（spec: VariableEvaluator.cs:1178-1191）。
     *
     * 注意：原版对 paramCode 不做 PALAM 数组/名字表长度校验（直接索引，越界即崩），
     * 本移植同样直读——越界由 VariableStore 的下标检查报 CodeEE（ADR-005：不修原版怪癖）。
     */
    fun characterParamString(ctx: ExecContext, target: Long, paramCode: Int): String? {
        checkTarget(ctx, target) // spec: VariableEvaluator.cs:1144-1147
        val store = ctx.variables
        val num = store.readInt(PALAM, longArrayOf(target, paramCode.toLong())) // spec: VariableEvaluator.cs:1148
        val text = store.registry.csvTable(PALAM)[paramCode] // spec: VariableEvaluator.cs:1150
        if (num == 0L && text.isEmpty()) return null // spec: VariableEvaluator.cs:1151-1154
        // :1155-1158（text == null 时置空串）在本仓不可达：CsvNameTable[i] 恒返回非 null。
        var mark = '-'
        var threshold = store.readInt(PALAMLV, longArrayOf(1L)) // spec: VariableEvaluator.cs:1159-1160
        if (num >= threshold) { mark = '='; threshold = store.readInt(PALAMLV, longArrayOf(2L)) } // :1161-1164
        if (num >= threshold) { mark = '>'; threshold = store.readInt(PALAMLV, longArrayOf(3L)) } // :1166-1169
        if (num >= threshold) { mark = '*'; threshold = store.readInt(PALAMLV, longArrayOf(4L)) } // :1171-1174
        val bar = StringBuilder()
        bar.append('[')
        when {
            threshold <= 0L || threshold <= num -> repeat(10) { bar.append(mark) } // spec: :1178-1180
            num <= 0L -> repeat(10) { bar.append('.') } // spec: :1182-1184
            else -> { // spec: :1186-1190
                val filled = (num * 10L / threshold).toInt()
                repeat(filled) { bar.append(mark) }
                repeat(10 - filled) { bar.append('.') }
            }
        }
        bar.append(']')
        // 数值按**不变文化**、右对齐宽 6（spec: VariableEvaluator.cs:1193-1198：
        // `CultureInfo.InvariantCulture` + `handler.AppendFormatted(num, 6)`）。
        // 必须显式 Locale.ROOT：`"%6d".format(num)` 走 JVM 默认区域，而 java.util.Formatter
        // 的 `d` 转换会用该区域的**本地数字**（ar/hi 等零位非 '0' 的区域），屏上会从 ASCII
        // 数字变成阿拉伯-印度数字（本机 JDK 17 实测 `String.format(ar, "%6d", 42)` 得 `٤٢`）。
        // C# 的 AppendFormatted 带 InvariantCulture ⇒ 恒 ASCII 数字。
        val numText = String.format(java.util.Locale.ROOT, "%6d", num)
        return "${text}${bar}${numText}"
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private const val PALAM = "PALAM"
    private const val PALAMLV = "PALAMLV"

    /** 角色位置越界检查。spec: VariableEvaluator.cs:1069-1072 / :1144-1147 */
    private fun checkTarget(ctx: ExecContext, target: Long) {
        if (target < 0 || target >= ctx.variables.characters.count) {
            throw CodeEE(OOR_CHARA)
        }
    }
}
