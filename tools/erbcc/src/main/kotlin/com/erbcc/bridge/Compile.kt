package com.erbcc.bridge

import com.eratw.port.core.compile.DimSizeEvaluator
import com.eratw.port.core.compile.LocalDim
import com.eratw.port.core.vars.DimFunctionFolder
import com.eratw.port.erbcc.DimKeyword
import com.eratw.port.erbcc.DimSpec
import com.eratw.port.erbcc.LabelDef
import com.eratw.port.erbcc.SymbolTable
import java.util.Locale

/**
 * erbcc → core-vm 编译器桥（模块方向 erbcc → core-vm，桥只能住在对依赖的这一端）。
 * specs/loader.md 附录 D.8：`LabelDef.dimSpecs` → core-vm `LocalDim` 映射与函数表组装。
 */

private val GLOBAL_SIDE = setOf(DimKeyword.GLOBAL, DimKeyword.SAVEDATA, DimKeyword.CHARADATA)

/**
 * 单条 `DimSpec` → `LocalDim`。
 *
 * - GLOBAL/SAVEDATA/CHARADATA → null（全局侧：由调用方进全局声明，不进函数局部表）
 * - REF → `ref = true`（绑定槽，不占帧槽数组；每个顶层 ',' 恒贡献 0 尺寸，
 *   spec: UserDefinedVariableData.cs:309-319）。REF 的尺寸段**仍要求值**且必须为 0
 *   （`:329-335` CanNotSizedRef），erbcc 侧的空段（`Symbols.kt` 对每个逗号记 `""`）
 *   按 0 处理。
 * - 其余：dims 原文逐段经 [DimSizeEvaluator] 求值（等价 ReduceIntegerTerm→SingleLongTerm，
 *   spec: UserDefinedVariableData.cs:325-341）；任一段不可解/越界 → null，
 *   对应原版「该行无效、变量不注册」（D.8）。丢弃原因见 [dimDropReason]，
 *   调用方用 [buildFuncDims] 的 `drops` 槽留痕。
 * - **slots = 元素总数**（各维乘积；无 dims = 1）。修复前恒 1 是占位口径，
 *   与侧车/运行期注册的 `dims` 分叉（CMP-40：`VARSIZE("RELATION")` 折出 200 时，
 *   布局面只有 1 个元素槽）。
 * - [folder] 是**与侧车/运行期同源**的装载期折叠回调（`DimFunctionFolders.pureStatic`
 *   吃 `DimVarSizes.systemSnapshot` 快照）。不注入时 `VARSIZE(...)` 一类尺寸保持
 *   「折不出 ⇒ 该行不收集」的失败方向，绝不静默按 1 收下。
 */
fun DimSpec.toLocalDim(
    consts: Map<String, Long> = emptyMap(),
    folder: DimFunctionFolder? = null,
): LocalDim? {
    if (keywords.any { it in GLOBAL_SIDE }) return null
    return when (val c = checkDims(this, consts, folder)) {
        is DimCheck.Invalid -> null
        is DimCheck.Ok -> {
            if (DimKeyword.REF in keywords) {
                LocalDim(name = name, isStr = isStr, slots = 1, ref = true)
            } else {
                var total = 1L
                for (v in c.values) total *= v
                LocalDim(name = name, isStr = isStr, slots = total.toInt(), ref = false)
            }
        }
    }
}

/** 一条被丢弃的私有 `#DIM` 声明与原因（erbcc 侧留痕用，不许静默丢）。 */
data class DimDrop(val spec: DimSpec, val reason: String)

/**
 * 该声明在给定常量表 / 折叠回调下**不可收集**的原因；null = 可收集或全局侧。
 *
 * 尺寸校验口径 = `UserDefinedVariableData.cs:325-341`（每段 ReduceIntegerTerm →
 * SingleLongTerm，REF 段必须 0，非 REF 段 1..1000000），与运行期
 * [com.eratw.port.core.vars.DimParser.parse] 同一组行号（CMP-45 单源，不许一边放宽）。
 */
fun DimSpec.dimDropReason(
    consts: Map<String, Long> = emptyMap(),
    folder: DimFunctionFolder? = null,
): String? {
    if (keywords.any { it in GLOBAL_SIDE }) return null
    return (checkDims(this, consts, folder) as? DimCheck.Invalid)?.reason
}

/** 校验结果：有效 = 各维值（REF 的每个空段按原版记 0）；无效 = 丢弃原因。 */
private sealed interface DimCheck {
    class Ok(val values: List<Long>) : DimCheck
    class Invalid(val reason: String) : DimCheck
}

/**
 * 尺寸校验的**唯一判据**（[toLocalDim] / [dimDropReason] / [droppedByUnresolvableDim] 共用）：
 * 逐条对照 `UserDefinedVariableData.cs:325-341` ——
 * - 每段必须折成**单值**（`:325-328`，`DimSizeEvaluator` 已拒带尾巴的文本）；
 * - REF：每个空段恒 0（`:309-319`），显式写出的尺寸必须为 0（`:329-335`）；
 * - 非 REF：每段 1..1000000（`:337-340`），总元素数同界（与 `DimParser.parse` 同判据）。
 */
private fun checkDims(
    spec: DimSpec,
    consts: Map<String, Long>,
    folder: DimFunctionFolder?,
): DimCheck {
    val isRef = DimKeyword.REF in spec.keywords
    val evaluator = DimSizeEvaluator(consts, folder)
    val values = ArrayList<Long>(spec.dims.size)
    var total = 1L
    for (raw in spec.dims) {
        if (isRef && raw.isBlank()) {
            values.add(0L)                                // 原版每个顶层逗号记一维 0（:309-319）
            continue
        }
        val v = evaluator.eval(raw) ?: return DimCheck.Invalid(
            "${spec.name} 的尺寸 '$raw' 不能求值为单个整数常量" +
                "（原版 UserDefinedVariableData.cs:325-328 HasNotExpressionAfterComma）",
        )
        if (isRef) {
            if (v != 0L) {
                return DimCheck.Invalid(
                    "${spec.name} 是 REF 变量，尺寸只能写 0（实际 $v；" +
                        "原版 UserDefinedVariableData.cs:329-335 CanNotSizedRef）",
                )
            }
            values.add(0L)
            continue
        }
        if (v <= 0L || v > 1_000_000L) {
            return DimCheck.Invalid(
                "${spec.name} 的尺寸 $v 越界（1..1000000；" +
                    "原版 UserDefinedVariableData.cs:337-340 OoRDefinable）",
            )
        }
        values.add(v)
        total *= v
    }
    if (!isRef && total > 1_000_000L) {
        return DimCheck.Invalid("${spec.name} 的总元素数 $total 越界（1..1000000）")
    }
    return DimCheck.Ok(values)
}

/**
 * 组装 core-vm `DimSpecLayout` 需要的 funcDims 表。
 *
 * 重名口径（D.8.2）：非事件重名取 resolve() 胜者（[com.eratw.port.erbcc.DuplicateGroup.winner]
 * = 最后装载者）；事件组每条 `LabelDef` 各自携带 dimSpecs 且互不覆盖——事件成员以
 * `<KEY>#<defIndex>` 为键进入局部表（与 core-vm 编译器的事件成员命名约定一致）。
 *
 * @param consts 尺寸求值常量表：用 [sizeConsts] 从 erbcc 收集物（`SymbolConfig.macros`
 *   宏名→展开体原文 + `SymbolTable.erhDimConsts` ERH `#DIM CONST` 原文）构造。
 *   **能求值就注册、不能求值的声明按 D.8 不收集**（toLocalDim 返回 null，
 *   引用走全局占位槽，编译不受阻、不报错）。
 * @param folder 装载期可折叠函数回调（与侧车/运行期同源；`VARSIZE` 尺寸靠它折出）。
 * @param drops 非空时，每条被丢弃的声明（**不含**全局侧：那是走全局声明、不是丢弃）
 *   连同原因追加进来。erbcc/装置据此留痕，杜绝「折不出就静默丢」（CMP-89 的留痕面）。
 */
fun buildFuncDims(
    defs: List<LabelDef>,
    consts: Map<String, Long> = emptyMap(),
    folder: DimFunctionFolder? = null,
    drops: MutableList<DimDrop>? = null,
): Map<String, List<LocalDim>> {
    val out = LinkedHashMap<String, List<LocalDim>>()
    for (def in defs) {
        val dims = ArrayList<LocalDim>(def.dimSpecs.size)
        for (spec in def.dimSpecs) {
            val local = spec.toLocalDim(consts, folder)
            if (local != null) {
                dims.add(local)
            } else if (drops != null && spec.keywords.none { it in GLOBAL_SIDE }) {
                // 原因复算：toLocalDim 与 dimDropReason 共用 checkDims，不存在「丢了但说不出为什么」。
                drops.add(
                    DimDrop(
                        spec,
                        spec.dimDropReason(consts, folder)
                            ?: "${spec.name} 未能进局部表（原因复算为空；请查 toLocalDim 的早退分支）",
                    ),
                )
            }
        }
        if (dims.isNotEmpty()) {
            val key = if (def.isEvent) "${def.key}#${def.defIndex}" else def.key
            out[key] = dims
        }
    }
    return out
}

/**
 * 诊断口径：该 `DimSpec` 在给定常量表 / 折叠回调下是否**被丢弃**
 * （全局侧走全局声明，不算丢弃；REF 的合法形态、空 dims 都可收集，也不算）。
 * 用于「宏/常量收集前后被丢弃声明数」对比（specs/loader.md 附录 D.8）。
 * 判据与 [toLocalDim] 完全同源（含 REF≠0 / 越界 / 非单值，不再只看「能不能求值」）。
 */
fun DimSpec.droppedByUnresolvableDim(
    consts: Map<String, Long>,
    folder: DimFunctionFolder? = null,
): Boolean = keywords.none { it in GLOBAL_SIDE } && toLocalDim(consts, folder) == null

/** erbcc 全语料收集物 → 尺寸求值常量表（配合 [buildFuncDims]）。 */
fun sizeConsts(table: SymbolTable): Map<String, Long> =
    sizeConsts(table.config.macros, table.erhDimConsts)

/**
 * erbcc 收集物 → `DimSizeEvaluator` 的常量表（宏名/常量名大写归一 → Long）。
 * erbcc 只收原文（D.8 纪律「erbcc 不做宏求值」），求值全部发生在这里：
 *
 *  - `#DEFINE 名 体`：体按整数表达式经 [DimSizeEvaluator] 求值（纯整数、`1+2` 表达式、
 *    `宏→宏→常量` 链均通；`TIME:2`/`VARSIZE(...)`/字符串体等不可解 → 不纳入）。
 *    词法层展开链的对应物：spec: LexicalAnalyzer.cs:285-307（标识符读宏、循环展开、
 *    上限 100 防自参照循环，MacroOverLimit）；原版对非单词宏体在标识符上下文
 *    拒绝展开（MacroIsNotAvailable，词法只接受 1 词替换），本表只认「求值得到唯一整数」
 *    的体，口径更宽但仅用于尺寸求值场景（= 原版 ReduceIntegerTerm 的折叠域，
 *    spec: UserDefinedVariableData.cs:325-341）。
 *  - `#DIM CONST 名 = 初值`：取**首个顶层逗号段**（无下标的裸标识符 = 元素 0，
 *    spec: VariableTerm.cs:337-340 CanRestructure→GetValue 默认下标 0；CONST 恒 1 维，
 *    UserDefinedVariableData.cs:438-441），初值原文经同一求值器折叠。
 *  - **定点迭代**：一条的表达式可引用另一条的结果，逐轮消化直到无进展——等价原版
 *    ERH 装载尾部 analyzeSharpDimLines 的重试队列（IdentifierNotFoundCodeEE → 重新入队，
 *    整轮无进展才放弃并报错，spec: ErhLoader.cs:249-340）。
 *  - 宏名与常量名重名：宏先占（原版 CheckUserMacroName/CheckUserVarName 冲突
 *    warnLevel=2 行无效，本语料 0 例，spec: IdentifierDictionary.cs:283-285, 324-331）。
 *
 * 残留不可求值形态（DimSizeEvaluator 支持 十进制/`0x` 十六进制整数、标识符、
 * `+ - * / %`/括号；`0x` 口径 = spec `LexicalAnalyzer.cs:36-45`）：
 * TODO(verify) 二进制 `0b...`、`名@下标`、VARSIZE/COLOR_FROMNAME 等函数折叠——
 * 原版 ReduceIntegerTerm 支持，移植侧这些声明仍按「不收集」降级（D.8），不报错。
 */
fun sizeConsts(macros: Map<String, String>, dimConsts: List<DimSpec>): Map<String, Long> {
    val resolved = LinkedHashMap<String, Long>()
    val pending = LinkedHashMap<String, String>()      // 名字（大写）→ 表达式原文
    for ((k, v) in macros) {
        if (v.isNotBlank()) pending[k.uppercase(Locale.ROOT)] = v
    }
    for (spec in dimConsts) {
        if (spec.isStr || DimKeyword.CONST !in spec.keywords) continue
        val init = spec.initializer ?: continue
        val first = SymbolTable.splitTopLevel(init, ',').firstOrNull()?.trim() ?: continue
        if (first.isEmpty()) continue
        pending.putIfAbsent(spec.name.uppercase(Locale.ROOT), first)
    }
    while (pending.isNotEmpty()) {
        val evaluator = DimSizeEvaluator(resolved)
        val it = pending.entries.iterator()
        var progressed = false
        while (it.hasNext()) {
            val (k, expr) = it.next()
            val v = evaluator.eval(expr)
            if (v != null) {
                resolved[k] = v
                it.remove()
                progressed = true
            }
        }
        if (!progressed) break                          // 定点：剩余均不可求值 → 不纳入
    }
    return resolved
}

/**
 * 宏体表 → 尺寸求值常量表（宏名大写归一；展开体按 [sizeConsts] 的宏处理路径求值，
 * 纯整数与整数表达式均可，不可求值的剔除）。
 */
fun macroConsts(macrosWithBodies: Map<String, String>): Map<String, Long> =
    sizeConsts(macrosWithBodies, emptyList())
