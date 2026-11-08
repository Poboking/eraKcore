package com.eratw.port.core.frontend

import com.eratw.port.core.compile.DimSizeEvaluator

/**
 * 设备端尺寸求值器（R7-C1，CONTRACT-R7-C §2/C1）：把侧车 `macros` / `constants` 段折成
 * `#DIM` 尺寸求值用的常量表（名字大写归一 → Long）。
 *
 * ## 与桌面侧的同解契约（出口判据 3）
 *
 * 桌面侧的求值器住在 erbcc 桥（`com.erbcc.bridge.sizeConsts(macros, dimConsts)`，
 * tools/erbcc/src/main/kotlin/com/erbcc/bridge/Compile.kt:218），core-vm 不能反向依赖它
 * （模块方向 erbcc → core-vm）。本函数是它在设备端的**逐行同构**实现，求值口径完全一致：
 *
 * - `#DEFINE 名 体`：体按整数表达式经 [DimSizeEvaluator] 求值（纯整数、`1+2` 表达式、
 *   宏→宏→常量链均通；`TIME:2`、`VARSIZE(...)`、字符串体等不可解 → 不纳入）。
 *   宏展开链的对应物：spec: LexicalAnalyzer.cs:285-307（标识符读宏、循环展开、上限 100
 *   防自参照循环）；本表只认「求值得到唯一整数」的体，口径更宽但仅用于尺寸求值场景
 *   （= 原版 ReduceIntegerTerm 的折叠域，spec: UserDefinedVariableData.cs:325-341）。
 * - `#DIM CONST 名 = 初值`：取首个顶层逗号段（无下标的裸标识符 = 元素 0，
 *   spec: VariableTerm.cs:337-340；CONST 恒 1 维，UserDefinedVariableData.cs:438-441），
 *   初值原文经同一求值器折叠。
 * - **定点迭代**：一条的表达式可引用另一条的结果，逐轮消化直到无进展——等价原版
 *   ERH 装载尾部 analyzeSharpDimLines 的重试队列（spec: ErhLoader.cs:249-340）。
 *   整轮无进展就停：剩余项一律不纳入（不折叠也不报错），与桌面 sizeConsts 出口一致。
 * - **宏名与常量名重名：宏先占**（桌面侧宏先进表、常量 putIfAbsent 的同款次序；
 *   原版 CheckUserMacroName/CheckUserVarName 冲突 warnLevel=2 行无效，
 *   spec: IdentifierDictionary.cs:283-285, 324-331）。
 *
 * 「两端同解」由 [com.eratw.port.core.compile.PatchCompilerTest] 的对拍用例钉住：
 * 同一份宏表与常量集，桌面 `com.erbcc.bridge.sizeConsts` 与本函数的求值结果逐键相等。
 *
 * ## 两个入口对应两种输入形态
 *
 * - [sizeConsts]：**侧车形态**。`macros` = 侧车 `dim-config` 段的宏表（宏名 → 展开体
 *   原文，[com.eratw.port.core.vars.DimParseConfig.macros]）；`presetConstants` = 侧车
 *   `constants` 段（**已求值**的整型常量，[com.eratw.port.core.vars.DimParseConfig.intConstants]）。
 *   已求值常量以十进制字面量的形态进定点折叠（第一轮必折出），因此与「原文形态进桌面
 *   版再折一遍」的结果逐键相同——这是判据 3 对拍把同一份侧车输入喂两端的桥。
 * - [labelTableSizeConsts]：**收集物形态**。签名与桌面
 *   `sizeConsts(macros, dimConsts)` 同构（`(Map<String, String>, List<DimSpec>) -> Map<String, Long>`），
 *   即 [LabelTable.build] 的 `sizeConsts` 注入点要求的函数形态（Symbols.kt:716）——
 *   设备端若要对一组 ERH 收集物跑 [LabelTable.build]（一致性对拍、导入链预处理），
 *   用它替换桌面 `::sizeConsts` 的位置，两端的常量表**单源同解**。
 *
 * 残留不可求值形态与桌面同向（不纳入、不报错）：二进制 `0b` 之外的函数折叠、
 * `名@下标`、VARSIZE/COLOR_FROMNAME 等函数尺寸——原版 ReduceIntegerTerm 支持，
 * 本表按「不收集」降级（specs/loader.md D.8），不报错。
 */

/**
 * 侧车形态入口：宏表（宏名 → 展开体原文）+ 已求值常量（大写名 → 值）→ 尺寸求值常量表。
 *
 * @param macros 侧车 `dim-config` 段的宏表；**空体宏不进表**（`#DEFINE 名` 无替换式，
 *   spec: ErhLoader.cs:167-171，展开为空 token 流，不能当数值用）。
 * @param presetConstants 侧车 `constants` 段的已求值常量；以十进制字面量形态参与定点折叠，
 *   因此仍可被「宏体引用该常量名」的表达式链消费。
 * @return 大写名 → Long。不可求值的项不出现（与桌面 sizeConsts 同向，不报错）。
 */
fun sizeConsts(
    macros: Map<String, String>,
    presetConstants: Map<String, Long> = emptyMap(),
): Map<String, Long> {
    val literals = HashMap<String, String>(presetConstants.size)
    for ((k, v) in presetConstants) literals[k] = v.toString()
    return foldSizeConstants(macros, literals)
}

/**
 * 收集物形态入口（[LabelTable.build] 的 `sizeConsts` 注入形态）：宏表 + ERH `#DIM CONST`
 * 声明原文 → 尺寸求值常量表。
 *
 * 取值口径照桌面 `com.erbcc.bridge.sizeConsts` 的 dimConsts 路径逐行同构：非字符串、
 * 带 `CONST` 关键字、有初值的声明才进表；初值取首个顶层逗号段（[LabelTable.splitTopLevel]）；
 * 重名声明先到者胜（`putIfAbsent`，与桌面同款）。宏先占的次序同样保留——本函数与
 * [sizeConsts] 共用 [foldSizeConstants] 的同一套定点折叠，两端不存在第二份实现。
 *
 * @param macros 宏表（宏名 → 展开体原文；空体不进表）。
 * @param dimConsts ERH `#DIM CONST` 声明原文（[LabelTable.erhDimConsts] 的收集物）。
 */
fun labelTableSizeConsts(
    macros: Map<String, String>,
    dimConsts: List<DimSpec>,
): Map<String, Long> {
    val literals = LinkedHashMap<String, String>()
    for (spec in dimConsts) {
        if (spec.isStr || DimKeyword.CONST !in spec.keywords) continue
        val init = spec.initializer ?: continue
        val first = LabelTable.splitTopLevel(init, ',').firstOrNull()?.trim() ?: continue
        if (first.isEmpty()) continue
        literals.putIfAbsent(spec.name.uppercase(), first)
    }
    return foldSizeConstants(macros, literals)
}

/**
 * 定点折叠核心（[sizeConsts] 与 [labelTableSizeConsts] 的单源）：宏先进表（重名时宏赢），
 * 预置常量/声明原文随后 `putIfAbsent`；逐轮用 [DimSizeEvaluator]（与运行期 `#DIM` 解析
 * 同源的求值器，specs/loader.md D.8.1）消化，无进展即停。
 *
 * @param macros 宏表（宏名 → 展开体原文）。
 * @param presetExpressions 预置项（大写名 → 表达式原文；已求值常量 = 十进制字面量原文）。
 */
private fun foldSizeConstants(
    macros: Map<String, String>,
    presetExpressions: Map<String, String>,
): Map<String, Long> {
    val resolved = LinkedHashMap<String, Long>()
    val pending = LinkedHashMap<String, String>()      // 名字（大写）→ 表达式原文
    for ((k, v) in macros) {
        if (v.isNotBlank()) pending[k.uppercase()] = v
    }
    for ((k, expr) in presetExpressions) {
        pending.putIfAbsent(k.uppercase(), expr)
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