package com.erbcc.bridge

import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.expr.OperandType
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.SymbolTable
import java.util.Locale

/**
 * erbcc 符号表 → core-vm 编译器 [ExprCallTable] 的构造桥（A16 任务 3；契约 C1/C3 + §5 R6）。
 *
 * `ErbProgramCompiler.compileFunction(..., exprCalls)` 拿到本表才会把表达式位的
 * 用户 `#FUNCTION` 方法调用降级成"实参升序求值 → `_MOV` R0.. → `_CALL`(c=返回槽) →
 * `#EXPR_RET#<k>` 哨兵读槽"（降级形态 spec: UserDefinedFunctionArgument.cs:35-69、
 * ProcessState.cs:435-452；哨兵运行期桥 = [com.eratw.port.core.proc.ProcessRuntime.evalContext]）。
 * `#FUNCTIONS`（str）同形降级，走 `_RETS` 字符串通道 + `#EXPR_RETS#<k>` 哨兵
 * （合同 M3-A §3 A7）。传 null 则保持旧行为（语料零回退，但 E2E 的表达式调用全部撞运行期桩）。
 *
 * 三桶口径（逐条对照原版标识符解析，spec: IdentifierDictionary.cs:538-586）：
 * - [LabelDef.isMethod] 且返回类型 **INT**（`#FUNCTION`）→ intMethods（可降级）。
 * - 返回类型 **STR**（`#FUNCTIONS`）→ strMethods：降级走 VM `_RETS` 字符串通道
 *   （合同 M3-A §3 A7：`_CALL` b bit28 + `#EXPR_RETS#<k>` 哨兵）。
 * - 返回类型**未知**（methodTypes 缺声明）→ unknownMethods：**不降级**，运行期桥显式红。
 *   与 strMethods 分桶的理由：把可能实为 `#FUNCTION` 的方法误降级成字符串通道会
 *   **静默读错值**，而保守只是明确失败。
 * - 普通 `@label`（非方法、**非事件**）→ nonMethodLabels：表达式位调用它 = 原版
 *   UsedNonMethodFunc 拒绝（spec: IdentifierDictionary.cs:559, 566-569），编译器照此抛；
 *   与内建同名时 `ExprCallTable.classify` 已按原口径静默走内建（:566/:576-585）。
 * - 事件名**不入** nonMethodLabels：原版 `GetNonEventLabel` 对纯事件名返回 null，
 *   走的是"标识符无法解析"而非 UsedNonMethodFunc（:552-560）；同名内建仍由 classify 命中。
 * - 同名多条定义只看**生效者**（后装载者，spec: LabelDictionary.cs:60-68）：
 *   `@DUP`(普通) 在前、`@DUP`+`#FUNCTION` 在后 ⇒ 表达式位按方法降级，不落 nonMethodLabels。
 *
 * @param methodTypes 方法名（大写）→ 返回类型。**上游现状**：[LabelDef] 只有
 *   `isMethod`（`#FUNCTION`/`#FUNCTIONS` 在收集期被合并，spec 侧证据
 *   tools/erbcc/.../erbcc/Symbols.kt:637 `"FUNCTION", "FUNCTIONS" -> c.copy(isMethod = true)`），
 *   没有 int/str 之辨 ⇒ 本参数是唯一的类型来源（装载侧 prescan 的 `funcTypes` /
 *   ERH 元数据）。不传 = 全部方法按"类型未知"落 unknownMethods（保守不降级）。
 */
fun buildExprCallTable(
    defs: List<LabelDef>,
    methodTypes: Map<String, OperandType> = emptyMap(),
): ExprCallTable {
    // 生效定义 = 同键的**后装载者**（spec: LabelDictionary.cs:60-68 字典直接赋值；
    // erbcc 侧同口径见 com.eratw.port.erbcc.DuplicateGroup.winner）。
    // 纯事件名先剔除：原版表达式位查的是 GetNonEventLabel，事件组不参与本表（:552-560）。
    val winnerByKey = LinkedHashMap<String, LabelDef>()
    for (def in defs) {
        if (def.isEvent) continue
        val key = def.key.uppercase(Locale.ROOT)
        val cur = winnerByKey[key]
        if (cur == null || def.defIndex >= cur.defIndex) winnerByKey[key] = def
    }
    val intMethods = LinkedHashSet<String>()
    val strMethods = LinkedHashSet<String>()
    val unknownMethods = LinkedHashSet<String>()
    val plainLabels = LinkedHashSet<String>()
    for ((key, def) in winnerByKey) {
        if (!def.isMethod) {
            plainLabels.add(key)
            continue
        }
        when (methodTypes[key]) {
            // 只有明确 INT 才按 int 通道降级
            OperandType.INT -> intMethods.add(key)
            // 明确 STR → _RETS 字符串通道（A7）
            OperandType.STR -> strMethods.add(key)
            // 类型未知 → 不降级（误判通道会静默读错值）
            else -> unknownMethods.add(key)
        }
    }
    return ExprCallTable(
        intMethods = intMethods,
        strMethods = strMethods,
        nonMethodLabels = plainLabels,
        unknownMethods = unknownMethods,
    )
}

/**
 * [SymbolTable] 形态的便捷入口（与 [com.erbcc.bridge.buildFuncDims] 一样吃 `LabelDef` 全集）。
 * 同键多条定义只看生效者（[com.eratw.port.erbcc.DuplicateGroup.winner] 同口径）。
 */
fun buildExprCallTable(
    table: SymbolTable,
    methodTypes: Map<String, OperandType> = emptyMap(),
): ExprCallTable = buildExprCallTable(table.nameKeys.flatMap { table.defsOf(it) }, methodTypes)
