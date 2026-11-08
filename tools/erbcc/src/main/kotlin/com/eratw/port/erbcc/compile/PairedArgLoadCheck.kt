package com.eratw.port.erbcc.compile

import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.fn.Builtins
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.erbcc.LoadWarning
import java.util.Locale

/**
 * 装载期成对实参校验（ENGFIX2 B4）：语句位内建方法的变参组不满足「列名, 值」成对时，
 * 产一条 [LoadWarning]（移植侧 `ParserMediator.Warn` 族的落点，见 CompileResult.warnings KDoc）。
 *
 * 原版相位：`FunctionMethod.cs:186-193` 的 `MatchVariadicGroup` 检查发生在
 * `CheckArgumentTypeEx` 里，而后者由装载期 `METHOD_ArgumentBuilder.CreateArgument`
 * （ArgumentParser.cs:1119-1127）与表达式位 `IdentifierDictionary.GetFunctionMethod`
 * （IdentifierDictionary.cs:576-585）调用，失败抛 `CodeEE`；`ErbLoader` 装载循环把解析
 * 异常收成 `InvalidLine.ErrMes` 后 `ParserMediator.Warn(…, 2)` 上屏
 * （ErbLoader.cs:481-490、ParserMediator.cs:120-125）。即**装载期上屏**，不是执行到才报。
 *
 * 移植侧此前把同一约束留在 `MapDataTableMethods.kt` 的运行期 `CodeEE`
 * （dtRowAdd / dtRowSet 成对循环），相位不同（`MapDataAuditTest` 审计 KDoc 已登记为
 * 「需组长接线」）。本函数把判据上移到语料装载/联结期：判据只读
 * [com.eratw.port.core.fn.VariadicGroup]（注册在 [Builtins] 的单一声明源），
 * 不再另维护方法名白名单 —— 漏登记即漏保护正是 2026-11-06 审查报告判词③的病根。
 *
 * 边界（诚实登记，不静默）：只覆盖 [MethodStmt]（语句位方法调用，即这两族在语料里的
 * 实际形态）；表达式位内嵌调用的同源判据已接入 `ArgSignatureCheck.checkOneBuiltin`
 * 的池遍历面（ENGFIX3 B4 遗留①，判据同读 [VariadicGroup.violates] 单一声明源；
 * 其宽松模式告警经 `checkProgram` 返回值交调用方，向 erbcc `LoadWarning` 的挂接
 * 仍归装载返回值接线面，现网该返回值由 `ErbProgramCompiler.build` 钩子显式丢弃）。
 * `CALLFORM` 动态名与成对判据**有锚地不适用**：原版 CALL/CALLFORM 的名字解析只查
 * 用户标签表（`CalledFunction.cs:87-115` `GetNonEventLabel`，命中 `IsMethod` 还抛
 * `CallToUserFunc`），内建方法（成对判据的声明域）不可达 —— 不是静态不可判的欠账，
 * 是域外。个数下界以下的场景归 `checkArity` 个数闸，[VariadicGroup.violates] 不判。
 *
 * @param userMethodNames 用户 `#FUNCTION` 名（大写），用户方法遮蔽同名内建
 *   （IdentifierDictionary.cs:548-565 先查 labelDic）时不该按内建签名判。
 */
internal fun pairedArgLoadWarning(
    stmt: Statement,
    file: String,
    userMethodNames: Set<String>,
): LoadWarning? {
    if (stmt !is MethodStmt) return null
    // 用户方法名按 Locale.ROOT 大写比对：userMethodNames 本身就是 Locale.ROOT 大写集合
    // （CorpusCompile.kt:480），同 locale 归一才不会在 tr 语系下 i→İ 错配而误判用户方法遮蔽。
    // Builtins.registry 是大小写不敏感表（Builtins.kt:856 TreeMap CASE_INSENSITIVE_ORDER，
    // 对应 FunctionMethodCreator.cs:9040 `methodList = new Dictionary(Config.StrComper)`），
    // 原始名可直接命中，查表不依赖
    // 本次归一；MethodStmt.methodName 保留语料原始大小写（LineParser.kt:192 传 first、
    // ArgInterner.kt:231 只 intern 不归一），归一后的 [name] 仅用于集合比对与告警文案回显。
    val name = stmt.methodName.uppercase(Locale.ROOT)
    if (name in userMethodNames) return null
    val term = (stmt.args as? MethodArgument)?.methodTerm as? FuncCall ?: return null
    val spec = Builtins.registry[name]?.variadicGroup ?: return null
    val n = term.args.size
    if (!spec.violates(n)) return null
    return LoadWarning(
        file,
        stmt.line + 1,
        "函数 $name 的「列名, 值」参数个数不成对（装载期：实参 $n 个，" +
            "需要 ${spec.fixedPrefix}+${spec.groupSize}n 或数组形态 ${spec.exactFormArgs} 个）",
    )
}
