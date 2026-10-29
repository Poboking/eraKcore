package com.eratw.port.core.expr

import com.eratw.port.core.vars.SystemLayout

/**
 * 解析期错误（带行列号）。
 */
class ExprParseException(
    val line: Int,
    val col: Int,
    message: String,
) : RuntimeException("解析错误(行:$line,列:$col): $message")

/** 解析期标识符解析（变量表/函数表在 M2 才有真实实现，测试注入假实现）。 */
interface IdentifierResolver {
    /**
     * 变量名 → 元信息；null 表示不是变量（随后走零参函数路径）。
     * 对应原版 IdentifierDictionary.GetVariableToken，spec: ExpressionParser.cs:347-358。
     */
    fun resolveVariable(name: String, subKey: String?): VarInfo?

    /**
     * 函数引用的静态类型；null = 未知函数。
     * 对应原版 `GetFunctionMethod(idStr, null)`（spec: ExpressionParser.cs:404-408）。
     * 带括号调用（`NAME(...)`）同样走本表：null ⇒ 解析器抛错（spec: :376-392）；
     * 参数类型检查（CheckArgumentType）仍属 M2 绑定层，本层不做。TODO(verify)
     */
    fun resolveFunction(name: String): OperandType?

    /**
     * 该名字能否在**裸标识符**位（不带括号）当零参函数引用。
     *
     * 原版两条路径泾渭分明（spec: IdentifierDictionary.cs:538-547 与 :566-586）：
     * - `GetFunctionMethod(..., arguments == null)`（无括号）**只**查 `refmethodDic`
     *   —— 即用户 `REF`/`#DIM` 系列的引用方法，内建方法一律返回 null；
     *   随后标识符落到 nameDic，内建方法被登记为 `DefinedNameType.SystemMethod`（:170），
     *   于是 :607-612 抛「用变量的方式使用函数名"NAME"」。
     * - `GetFunctionMethod(..., arguments != null)`（有括号）才查 `methodDic`，
     *   内建方法在此合法（`FunctionMethodTerm`）。
     *
     * `resolveFunction` 只有「是否存在 + 返回类型」两档信息，无法区分调用形态，
     * 故单列本判据。默认 `true` = 不设限，保持既有实现（含测试 FakeResolver）可编译；
     * 生产符号层（[com.eratw.port.core.stmt.SymbolTable] / [com.eratw.port.core.stmt.StrictSymbolResolver]）
     * 覆写为「非内建方法」。
     */
    fun canBeReferencedBare(name: String): Boolean = true

    /**
     * `:` 下标里的裸标识符能否按字符串关键字回落 —— 原版 [reduceIdentifier] 的第二级判据：
     * `ConstantData.isDefined(varCode, idStr)`（spec: ExpressionParser.cs:409-412）与
     * `ConstantData.isUserDefined(varId.Name, idStr, dim)`（spec: :413-443）都未命中时，
     * `ThrowException(idStr, isFunc: false)` 在解析期报错（spec: :444-445）。
     *
     * 返回三态（[varName] = 外层变量名、[dimension] = 外层变量的声明维度，与 C#
     * `isUserDefined(varname, str, dim)` 的第三参同义）：
     * - `true`：常量表命中 → 解析器回落 `SingleStrTerm`（→ [KeywordIndexArg]）；
     * - `false`：确定不是该变量的关键字 → 解析器抛 [ExprParseException]；
     * - `null`：本解析器没有关键字表，解析器保守放行（与严格化前的行为一致）。
     *
     * 默认实现只判**系统变量**（C# 的 `varCode` 路径）：
     * - 变量名在 [SystemLayout.keywords] 里 → `true`。键级校验要读 CSV，表达式解析期没有表，
     *   因此只做到「该变量存在关键字表」这一层（`TALENT:不存在的键` 仍留待运行期
     *   [EvalContext] 的 keywordToInteger 报错）；
     * - 变量名是 [SystemLayout] 注册的系统变量但不在 keywords 里 → `false`（C# 的
     *   `GetKeywordDictionary` 对这些 code 返回 null）；
     * - 其余（用户 `#DIM` 变量 / ERD / 与变量同名的 CSV 键表）→ `null`。用户关键字表
     *   （C# `PrepareERDFileNames` 扫 `*.erd` 与 CSV 目录，spec: ErhLoader.cs:342-388）
     *   在解析期不可见，是符号层的注入点：生产 resolver 可覆写本方法给出精确三态。
     */
    fun resolveIndexKeyword(varName: String, dimension: Int, idStr: String): Boolean? {
        val upper = varName.uppercase()
        if (upper in SystemLayout.keywords) return true
        return if (upper in SYSTEM_VARIABLE_NAMES) false else null
    }
}

/** 变量元信息（解析期建 VarRef/下标省略规则所需的最小集）。 */
data class VarInfo(
    val name: String,
    val operandType: OperandType,
    /** 维度 0..3。 */
    val dimension: Int,
    val isCharacterData: Boolean,
    val isConst: Boolean = false,
)

/**
 * [SystemLayout] 注册的全部系统变量名（大写）。
 *
 * 只服务 [IdentifierResolver.resolveIndexKeyword] 的默认实现（「是不是系统变量」判据），
 * 是布局表的派生视图：不新增真源，系统变量总表仍只有 [SystemLayout] 一份。
 * 不含用户 `#DIM` 变量与 ERD / 同名 CSV 键表 —— 那些在解析期不可判定（见该方法的 KDoc）。
 */
private val SYSTEM_VARIABLE_NAMES: Set<String> = buildSet {
    fun addPairs(entries: Iterable<Pair<String, *>>) {
        entries.forEach { add(it.first.uppercase()) }
    }
    addPairs(SystemLayout.int1D)
    addPairs(SystemLayout.str1D)
    addPairs(SystemLayout.str0D)
    addPairs(SystemLayout.int2D)
    addPairs(SystemLayout.int3D)
    addPairs(SystemLayout.charaInt0D)
    addPairs(SystemLayout.charaStr0D)
    addPairs(SystemLayout.charaInt1D)
    addPairs(SystemLayout.charaStr1D)
    addPairs(SystemLayout.charaInt2D)
    addPairs(SystemLayout.localInt)
    addPairs(SystemLayout.localStr)
    SystemLayout.pseudo.forEach { add(it.first.uppercase()) }
    addPairs(SystemLayout.csvConsts)
    addPairs(SystemLayout.gameBaseStr)
    addPairs(SystemLayout.gameBaseInt)
}

/**
 * 解析期配置（对应原版读取的 `Config` 静态开关，逐条按出厂默认值落定）。
 *
 * - [systemNoTarget] = `Config.SystemNoTarget`（ConfigData.cs:112 `t: false`）；
 * - [compatiRAND]    = `Config.CompatiRAND`（ConfigData.cs:98 `t: false`）；
 * - [systemIgnoreStringSet] = `Config.SystemIgnoreStringSet`（ConfigData.cs:113 `t: false`）。
 *
 * TODO(wiring): 生产构造点（`LineParser` / `ArgBuilders` / `Builtins` / `ProcessRuntime`）
 * 目前一律 `ParserConfig()`，**未从宿主 `emuera.config` 接线**；原版这三个开关是运行期静态
 * 可变的（`Config.SetConfig` 读 `ConfigData`）。本作语料 `emuera.config` 未写这三行
 * ⇒ 出厂默认下行为一致；仅当用户改了配置才分叉。接线点 = 由宿主把
 * `BinaryHost.configOverrides` 经同一条链灌进 `ParserConfig`（跨文件，登记备接线）。
 */
data class ParserConfig(
    val systemNoTarget: Boolean = false,
    /**
     * `Config.CompatiRAND`（ConfigData.cs:98 出厂 false；语料 `emuera.config:52` 为 NO）。
     *
     * 表达式层的消费点只有 RAND 伪变量的下标规则（VariableParser.cs:160-170）：
     * 开关关闭时 `RAND` 省略下标报 `OmittedRandArg`、`RAND:0` 报 `RandArgIsZero`
     * （Lang.cs:2070 / :2074 一族）；打开时两者都合法（eramaker 口径）。
     * 取值面的分叉不在这里 —— 那是 `RandToken` 与 `CompatiRandToken` 的选择
     * （VariableData.cs:3154），移植侧在 `VarConfig.compatiRand`（同键，见 `VariableStore.random`）。
     */
    val compatiRAND: Boolean = false,
    /**
     * `Config.SystemIgnoreStringSet`（ConfigData.cs:113 出厂 false；语料 `emuera.config:66` 为 NO）。
     *
     * 消费点唯一：字符串变量用 `=` 赋值的语句（ArgumentParser.cs:1025-1029）。开关打开时该语句
     * 判 level 2 错误（`line.IsError = true`，ArgumentBuilder.cs:21-26），文案
     * `Lang.Error.StrAssignIsPrihibited`（Lang.cs:2062；zhs 译文 xml:470）。
     * `'=`（`OperatorCode.AssignmentStr`）与 `SETS` 的逗号形态走同一 switch 的其它分支，
     * **不受影响**。
     *
     * 落在 [ParserConfig] 而不是语句层的 `StmtParseConfig`：原版这条判据在 ArgumentParser
     * （语句参数构造）里读静态配置，移植侧 [ArgCtx] 只携带本类（`ArgCtx.config`），
     * 而 [ArgCtx] 的构造点在语句层解析器里 —— 复用现有载体可避免跨层改签名。
     */
    val systemIgnoreStringSet: Boolean = false,
)

/**
 * ERB 表达式解析器。
 *
 * 移植自 ExpressionParser.cs（TermStack 状态机 + reduceTerm + ReduceArguments）
 * 与 VariableParser.cs（`:` 下标链与省略规则）。优先级/结合性由 TermStack 的
 * 运算符栈归约天然实现（ReduceAll 时从栈顶反复归约，优先级高者先结合）。
 */
class ExprParser(
    tokens: List<ExprToken>,
    private val resolver: IdentifierResolver? = null,
    private val config: ParserConfig = ParserConfig(),
) {
    private val tokens: List<ExprToken> = tokens + ExprToken.EOL
    private var pos = 0

    /** 解析告警（如连续比较运算符）。spec: ExpressionParser.cs:556-559 */
    val warnings = mutableListOf<String>()

    // cur / shiftNext 对 FormStringTerm.fromLexerStrForm 开放（internal）：原版
    // FromWordToken 在同一 WordCollection 上连续多次 reduce（spec: StrForm.cs:272-308），
    // 需要在两次 reduce 之间手动越过 ',' 并读取当前 Token。
    internal val cur: ExprToken get() = tokens[pos]
    private fun peek(offset: Int): ExprToken = tokens[(pos + offset).coerceAtMost(tokens.size - 1)]
    internal fun shiftNext() {
        if (cur.kind != ExprTokenKind.EOL) pos++
    }

    private fun parseErr(message: String): ExprParseException =
        ExprParseException(cur.line, cur.col, message)

    /** spec: ExpressionParser.cs:306-309 ReduceExpressionTerm */
    fun reduceExpressionTerm(endWith: Int = TermEndWith.EoL): Expr? =
        reduceTerm(allowKeywordTo = false, endWith = endWith)

    /** spec: ExpressionParser.cs:311-319 ReduceIntegerTerm */
    fun reduceIntegerTerm(endWith: Int = TermEndWith.EoL): Expr {
        val e = reduceTerm(allowKeywordTo = false, endWith = endWith)
            ?: throw parseErr("无法解释为表达式")
        if (e.operandType != OperandType.INT) throw parseErr("表达式结果不是数值")
        return e
    }

    /**
     * spec: ExpressionParser.cs:342-345 ReduceVariableArgument（`:` 后的单个下标表达式，单遍模式）。
     *
     * [owner] 是外层变量 —— 下标里的裸标识符要按它的关键字表判定
     * （spec: ExpressionParser.cs:409-443 的 `varCode` / `varId` 两参即此）。
     */
    private fun reduceVariableArgument(owner: VarInfo): Expr =
        reduceTerm(
            allowKeywordTo = false,
            endWith = TermEndWith.EoL,
            singlePass = true,
            indexOwner = owner,
        ) ?: throw parseErr("缺少 ':' 后的参数")

    // ------------------------------------------------------------------
    // 参数列表
    // ------------------------------------------------------------------

    /**
     * spec: ExpressionParser.cs:211-304 ReduceArguments。
     * 元素可能为 null（如 `FUNC(,1)` 的空参数位），与原版 LinkedList 允许 null 一致。
     */
    fun reduceArguments(endWith: ArgsEndWith, isDefine: Boolean = false): List<Expr?> {
        // 原版 switch 只处理 EoL 与 RightParenthesis 两支，**没有 RightBracket case**，
        // 故 endWith == RightBracket 时 termEndWith 保持初值 TermEndWith.EoL
        // （spec: ExpressionParser.cs:218-227）。不是移植缺口。
        val termEndWith = when (endWith) {
            ArgsEndWith.EoL -> TermEndWith.Comma
            ArgsEndWith.RightParenthesis -> TermEndWith.RightParenthesis_Comma
            ArgsEndWith.RightBracket -> TermEndWith.EoL
        }
        val termEndWithAssignment = termEndWith or TermEndWith.Assignment
        val terms = mutableListOf<Expr?>()
        while (true) {
            when (cur.kind) {
                ExprTokenKind.EOL -> {
                    if (endWith == ArgsEndWith.RightBracket) throw parseErr("未闭合的中括号")
                    if (endWith == ArgsEndWith.RightParenthesis) throw parseErr("未闭合的小括号")
                    return terms
                }
                ExprTokenKind.RPAREN -> {
                    if (endWith == ArgsEndWith.RightParenthesis) {
                        shiftNext()
                        return terms
                    }
                    throw parseErr("不该出现的小括号")
                }
                ExprTokenKind.RBRACKET -> {
                    if (endWith == ArgsEndWith.RightBracket) {
                        shiftNext()
                        return terms
                    }
                    throw parseErr("不该出现的中括号")
                }
                else -> {
                    if (!isDefine) {
                        terms.add(reduceTerm(allowKeywordTo = false, endWith = termEndWith))
                    } else {
                        // #FUNCTION 声明参数：`名 = 默认值` 形态。spec: ExpressionParser.cs:268-294
                        val last = reduceTerm(allowKeywordTo = false, endWith = termEndWithAssignment)
                            ?: throw parseErr("函数参数不能省略")
                        terms.add(last)
                        if (cur.kind == ExprTokenKind.OPERATOR) {
                            shiftNext()
                            val default = reduceTerm(allowKeywordTo = false, endWith = termEndWith)
                                ?: throw parseErr("'=' 后缺少表达式")
                            if (default.operandType != last.operandType) throw parseErr("'=' 两端类型不匹配")
                            terms.add(default)
                        } else if (last.operandType == OperandType.INT) {
                            terms.add(NullTerm(isInt = true))
                        } else {
                            terms.add(NullTerm(isInt = false))
                        }
                    }
                    if (cur.kind == ExprTokenKind.COMMA) shiftNext()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // reduceTerm 主体
    // ------------------------------------------------------------------

    /**
     * spec: ExpressionParser.cs:497-628 reduceTerm。
     *
     * [singlePass] 对应原版 varCode != __NULL__ 的 flag 分支（ExpressionParser.cs:502,618）：
     * 变量下标参数只解析**一个** term 就结束（`A:1:2` 的两个下标由外层 ':' 循环拆分，
     * `A:1+2` 中 `+2` 属于外层表达式）。
     * 该语境下的**字符串标识符回退**（`ConstantData.isDefined` / `isUserDefined` → SingleStrTerm）
     * 已实现，见 [reduceIdentifier] 的 `inVariableArg` 分支（spec: ExpressionParser.cs:409-443）。
     * 唯一未移植的是原版的 `Program.AnalysisMode` 分支（:379-390，分析方法模式下未知函数记入
     * tempDic 并返回 NullTerm）——本作没有该模式，未知函数一律按：391 的 ThrowException 报错。
     *
     * [indexOwner] 只在变量下标参数里非空：`A:B` 的 B 命中 [IdentifierResolver.resolveIndexKeyword]
     * 的判定要用 A 的关键字表（spec: ExpressionParser.cs:409-443）。
     * 括号内的子表达式按原版传 VariableCode.__NULL__（spec: :580），故递归时不透传。
     */
    private fun reduceTerm(
        allowKeywordTo: Boolean,
        endWith: Int,
        singlePass: Boolean = false,
        indexOwner: VarInfo? = null,
    ): Expr? {
        val termStack = TermStack()
        var num = 0
        var operatorCode = OperatorCode.NULL
        do {
            val t = cur
            when (t.kind) {
                ExprTokenKind.EOL -> return end(termStack, num)
                ExprTokenKind.STR_LITERAL -> termStack.add(StrLiteral(t.text, t.line, t.col))
                ExprTokenKind.INT_LITERAL -> termStack.add(IntLiteral(t.intValue, t.line, t.col))
                ExprTokenKind.FORM_STRING -> termStack.add(
                    FormStringTerm.fromLexerStrForm(
                        t.form ?: throw parseErr("FORM_STRING 缺少词法器 StrForm"),
                        resolver, config, t.line, t.col,
                    ),
                )
                ExprTokenKind.IDENTIFIER -> {
                    val code = t.text
                    if (code.equals("TO", ignoreCase = true)) {
                        if (allowKeywordTo) return end(termStack, num)
                        throw parseErr("不合法的 TO")
                    }
                    if (code.equals("IS", ignoreCase = true)) throw parseErr("不合法的 IS")
                    // px 关键字（尺寸参数）。spec: ExpressionParser.cs:534-537
                    if ((endWith and TermEndWith.KeyWordPx) != 0 &&
                        code.equals("px", ignoreCase = true) &&
                        (peek(1).kind == ExprTokenKind.COMMA || peek(1).kind == ExprTokenKind.EOL)
                    ) {
                        return end(termStack, num)
                    }
                    termStack.add(reduceIdentifier(code, singlePass, indexOwner))
                    // fix(语句层): 变量下标参数只解析一个 term —— 对应 C# reduceTerm 末尾的
                    // `while (!flag)`（flag = varCode != __NULL__，spec: ExpressionParser.cs:618-620）。
                    // 原移植版本在 IDENTIFIER 分支用 continue 跳过了 singlePass 检查，导致
                    // `TALENT:MASTER:形状` / `FLAG:地主` 这类下标在读到第二个 ':' 或 ',' 时报
                    // “不该出现的符号”。此处显式返回，行为与 C# 的 flag 分支一致。
                    if (singlePass) return end(termStack, num)
                    continue
                }
                ExprTokenKind.OPERATOR -> {
                    val code2 = OperatorCode.fromText(t.text)
                        ?: throw parseErr("未知运算符 '${t.text}'")
                    // 变量下标参数中不允许任何运算符。spec: ExpressionParser.cs:543-546
                    if (singlePass) throw parseErr("变量参数中不该出现运算符")
                    if (code2 == OperatorCode.ASSIGNMENT) {
                        if ((endWith and TermEndWith.Assignment) != 0) return end(termStack, num)
                        throw parseErr("表达式中不允许 '='")
                    }
                    // 连续比较运算符告警。spec: ExpressionParser.cs:556-559
                    if (operatorCode in COMPARISON_OPS && code2 in COMPARISON_OPS) {
                        warnings.add("(行:${t.line},列:${t.col}) 连续出现比较运算符")
                    }
                    termStack.add(code2)
                    operatorCode = code2
                    when (code2) {
                        OperatorCode.TERNARY_A -> num++
                        OperatorCode.TERNARY_B ->
                            if (num > 0) num-- else throw parseErr("'#' 之前缺少 '?'")
                        else -> {}
                    }
                }
                ExprTokenKind.LPAREN -> {
                    shiftNext()
                    val inner = reduceTerm(allowKeywordTo = false, endWith = TermEndWith.RightParenthesis)
                        ?: throw parseErr("括号内缺少表达式")
                    termStack.add(inner)
                    if (cur.kind != ExprTokenKind.RPAREN) throw parseErr("未闭合的小括号")
                    shiftNext()
                    // fix(语句层): 同上，变量下标参数遇到括号形式（`CFLAG:(TALKER:0)`）时也要终止
                    if (singlePass) return end(termStack, num)
                    continue
                }
                ExprTokenKind.RPAREN ->
                    if ((endWith and TermEndWith.RightParenthesis) != 0) return end(termStack, num)
                    else throw parseErr("不该出现的符号 ')'")
                ExprTokenKind.RBRACKET ->
                    if ((endWith and TermEndWith.RightBracket) != 0) return end(termStack, num)
                    else throw parseErr("不该出现的符号 ']'")
                ExprTokenKind.COMMA ->
                    if ((endWith and TermEndWith.Comma) != 0) return end(termStack, num)
                    else throw parseErr("不该出现的符号 ','")
                else -> throw parseErr("不该出现的符号")
            }
            shiftNext()
            if (singlePass) return end(termStack, num)
        } while (true)
        // 恒由 return/throw 退出；变量下标语境(varCode != __NULL__)的两条已实现路径
        // 见上（[singlePass] / [indexOwner]），移植侧无 AnalysisMode 分支（见 [reduceTerm] 的 KDoc）。
    }

    private fun end(stack: TermStack, ternaryCount: Int): Expr? {
        if (ternaryCount > 0) throw parseErr("'?' 缺少对应的 '#'")
        return stack.reduceAll()
    }

    // ------------------------------------------------------------------
    // 标识符 → 函数调用 / 变量引用
    // ------------------------------------------------------------------

    /**
     * spec: ExpressionParser.cs:360-446 reduceIdentifier。
     *
     * [indexOwner] 非空 = 本标识符处在变量 [indexOwner] 的 `:` 下标位置（原版 varCode != __NULL__）。
     */
    private fun reduceIdentifier(
        idStr: String,
        inVariableArg: Boolean = false,
        indexOwner: VarInfo? = null,
    ): Expr {
        // 标识符自身的行列号：函数/下标的未定义错误要指向标识符，而不是 reduce 结束后的游标。
        val ident = cur
        shiftNext()
        when (cur.kind) {
            ExprTokenKind.DOT -> throw parseErr("成员访问 '.' 未实现")  // spec: ExpressionParser.cs:363-367 NotImplCodeEE
            ExprTokenKind.LPAREN -> {
                shiftNext()
                val args = reduceArguments(ArgsEndWith.RightParenthesis)
                return functionCall(idStr, args, ident.line, ident.col)
            }
            ExprTokenKind.LBRACKET -> throw parseErr("不支持中括号形式的函数调用")  // spec: ExpressionParser.cs:370-374
            else -> {}
        }
        // `变量@私钥`。spec: ExpressionParser.cs:347-358
        var subKey: String? = null
        var atLine = -1
        var atCol = -1
        if (cur.kind == ExprTokenKind.AT) {
            atLine = cur.line
            atCol = cur.col
            shiftNext()
            if (cur.kind != ExprTokenKind.IDENTIFIER) throw parseErr("'@' 后缺少标识符")
            subKey = cur.text
            shiftNext()
        }
        val info = resolver?.resolveVariable(idStr, subKey)
        if (info != null) {
            // 变量下标参数内的标识符不再吃 ':' 链（原版 ReduceVariable(token, null, null, null)）。
            // spec: ExpressionParser.cs:398-401
            if (inVariableArg) return buildVariableTerm(info, null, null, null, subKey)
            return reduceVariable(info, subKey)
        }
        if (subKey != null && resolver != null) {
            // C# GetVariableToken 对「带 @ 但查不到变量」的名字必然抛 InvalidAt，绝不返回 null
            // （IdentifierDictionary.cs:490-493），故 `函数名@私钥`（ExpressionParser.cs:395-404
            // 先过变量表）与 `未定义名@私钥` 都到不了后续的函数/关键字路径。
            // 文案 = lang.emuera-zhs.xml:213 Error.InvalidAt。
            //
            // TODO(verify): C# 对「是变量但带 @」还分三支（IdentifierDictionary.cs:437-442/:453-476/:470-473）——
            // 私有变量抛「对私有变量使用了@」、全局变量抛「对非本地变量使用了@」、
            // LOCAL/ARG 系局部变量仅告警并按 subKey 建局部 token（跨函数局部引用）。
            // 本层 VarInfo 无局部/全局之分，无法同口径分流，暂维持「命中即接受 subKey」。
            throw ExprParseException(atLine, atCol, "@符号的非法用法")
        }
        // 零参函数引用。spec: ExpressionParser.cs:404-408
        val funcType = resolver?.resolveFunction(idStr)
        if (funcType != null) {
            // 裸标识符位的内建方法非法：原版 arguments==null 时不查 methodDic，
            // 标识符按 SystemMethod 处理并抛「用变量的方式使用函数名」。
            // spec: IdentifierDictionary.cs:538-547 + :607-612
            if (resolver?.canBeReferencedBare(idStr) == false) {
                throw parseErr("用变量的方式使用函数名\"$idStr\"")
            }
            return FuncCall(idStr, emptyList(), funcType)
        }
        if (inVariableArg) {
            // C# 的回落顺序（spec: ExpressionParser.cs:409-443）：
            //   ① ConstantData.isDefined(varCode, idStr) → SingleStrTerm；
            //   ② varId 是 VAR*/CVAR* 系列时 ConstantData.isUserDefined(...) → SingleStrTerm；
            //   ③ 都没有 → IdentifierDictionary.ThrowException(idStr, isFunc: false)（:444-445）。
            // 解析期没有 CSV/ERD 表，两级的「是否命中」由 resolver 注入：
            // true = 命中、false = 确定未命中（抛）、null = 无表（保守放行，等价严格化前）。
            val verdict = indexOwner?.let { owner ->
                resolver?.resolveIndexKeyword(owner.name, owner.dimension, idStr)
            }
            when (verdict) {
                true, null -> return StrLiteral(idStr)
                false -> throw ExprParseException(
                    ident.line,
                    ident.col,
                    "未定义的标识符 '$idStr'",
                )
            }
        }
        if (resolver == null) {
            // TODO(verify): 无解析器（独立单元测试）时的回退——按变量引用处理，
            // 操作数类型假定 INT；原版此处直接抛“未定义标识符”。
            val fallback = VarInfo(name = idStr, operandType = OperandType.INT, dimension = 0, isCharacterData = false)
            return reduceVariable(fallback, subKey)
        }
        throw parseErr("未定义的标识符 '$idStr'")
    }

    /**
     * 带括号的函数调用。spec: ExpressionParser.cs:375-393。
     *
     * 原版在解析期用 `GetFunctionMethod(labelDic, idStr, arguments, userDefinedOnly: false)`
     * 绑定函数；返回 null 且非 AnalysisMode 时 `ThrowException(idStr, isFunc: true)`
     * （spec: :376-392）。移植侧的存在性来源 = [IdentifierResolver.resolveFunction]
     * （用户 `#FUNCTION`/`#FUNCTIONS` 前向声明 + 内建方法表），因此这里必须同口径报错：
     * 未命中 → 「用函数的方式使用变量名」（nameDic 判为变量，spec: IdentifierDictionary.cs:600-605）
     * 或「未定义的标识符」（CanNotInterpreted，spec: :628）。
     *
     * 无 resolver（独立单元测试）时保持旧的 INT 兜底。
     * TODO(verify): 参数个数/类型检查（C# `CheckArgumentType`，spec: :580-584）仍属绑定层，
     * 本层只判存在性与返回类型。
     */
    private fun functionCall(name: String, args: List<Expr?>, line: Int, col: Int): Expr {
        val resolver = this.resolver
            ?: return FuncCall(name, args, OperandType.INT)
        val type = resolver.resolveFunction(name)
        if (type == null) {
            if (resolver.resolveVariable(name, null) != null) {
                throw ExprParseException(line, col, "用函数的方式使用变量名\"$name\"")
            }
            throw ExprParseException(line, col, "未定义的标识符 '$name'")
        }
        return FuncCall(name, args, type)
    }

    // ------------------------------------------------------------------
    // `:` 下标链与省略规则
    // ------------------------------------------------------------------

    /** spec: VariableParser.cs:34-63 ReduceVariable(VariableToken, WordCollection) */
    private fun reduceVariable(info: VarInfo, subKey: String?): Expr {
        var p1: Expr? = null
        var p2: Expr? = null
        var p3: Expr? = null
        var num = 0
        while (cur.kind == ExprTokenKind.COLON) {
            if (num >= 3) throw parseErr("变量 ${info.name} 的下标过多")
            shiftNext()
            val e = reduceVariableArgument(info)
            when (num) {
                0 -> p1 = e
                1 -> p2 = e
                else -> p3 = e
            }
            num++
        }
        return buildVariableTerm(info, p1, p2, p3, subKey)
    }

    /**
     * spec: VariableParser.cs:65-190 —— 下标省略补全规则：
     * - 角色变量 1D：1 个下标 → 前面补 TARGET；0 个 → [TARGET, 0]（SystemNoTarget 时为无参形态）
     * - 角色变量 2D/3D、一般 2D/3D：全给或全不给，不给部分直接报错
     * - 一般 1D：省略 → 0
     * - 0D：不允许下标
     */
    private fun buildVariableTerm(info: VarInfo, p1: Expr?, p2: Expr?, p3: Expr?, subKey: String?): Expr {
        val target = targetRef()
        val array: List<Expr> = when {
            info.isCharacterData -> when {
                info.dimension == 2 -> when {
                    p1 == null && p2 == null && p3 == null -> emptyList()  // 无参形态
                    p1 == null || p2 == null || p3 == null ->
                        throw parseErr("变量 ${info.name} 的下标不能省略")
                    else -> listOf(p1, p2, p3)
                }
                info.dimension == 1 -> {
                    if (p3 != null) throw parseErr("变量 ${info.name} 的下标过多")
                    if (p1 == null && p2 == null && config.systemNoTarget) return noArgRef(info, subKey)
                    var a1 = p1
                    var a2 = p2
                    if (a2 == null) {
                        if (config.systemNoTarget) throw parseErr("变量 ${info.name} 的下标不能省略")
                        a2 = a1 ?: IntLiteral(0L)
                        a1 = target
                    }
                    listOf(a1!!, a2)
                }
                else -> {
                    if (p2 != null) throw parseErr("变量 ${info.name} 的下标过多")
                    if (p1 == null && p2 == null && p3 == null && config.systemNoTarget) return noArgRef(info, subKey)
                    var a1 = p1
                    if (a1 == null) {
                        if (config.systemNoTarget) throw parseErr("变量 ${info.name} 的下标不能省略")
                        a1 = target
                    }
                    listOf(a1)
                }
            }
            info.dimension == 3 -> when {
                p1 == null && p2 == null && p3 == null -> return noArgRef(info, subKey)  // spec: VariableParser.cs:130
                p1 == null || p2 == null || p3 == null ->
                    throw parseErr("变量 ${info.name} 的下标不能省略")
                else -> listOf(p1, p2, p3)
            }
            info.dimension == 2 -> when {
                p1 == null && p2 == null && p3 == null -> return noArgRef(info, subKey)  // spec: VariableParser.cs:141
                p1 == null || p2 == null -> throw parseErr("变量 ${info.name} 的下标不能省略")
                p3 != null -> throw parseErr("变量 ${info.name} 的下标过多")
                else -> listOf(p1, p2)
            }
            info.dimension == 1 -> {
                if (p2 != null) throw parseErr("变量 ${info.name} 的下标过多")
                var a1 = p1
                if (a1 == null) {
                    // spec: VariableParser.cs:160-167 —— RAND 省略下标（非 CompatiRAND 兼容模式）
                    if (!config.compatiRAND && info.name == "RAND") throw parseErr("RAND 不能省略下标")
                    a1 = IntLiteral(0L)
                }
                // spec: VariableParser.cs:168-170 —— RAND:0 报错
                if (!config.compatiRAND && info.name == "RAND" && a1 is IntLiteral && a1.value == 0L) {
                    throw parseErr("RAND 的参数不能为 0")
                }
                listOf(a1)
            }
            else -> {
                if (p1 != null) throw parseErr("变量 ${info.name} 不带下标")
                emptyList()
            }
        }
        // 字符串关键字下标包装。spec: VariableParser.cs:182-188
        val wrapped = array.mapIndexed { i, e ->
            if (e.operandType == OperandType.STR) KeywordIndexArg(e, info.name, i) else e
        }
        return VarRef(
            name = info.name,
            indices = wrapped,
            operandType = info.operandType,
            isConst = info.isConst,
            subKey = subKey,
        )
    }

    private fun noArgRef(info: VarInfo, subKey: String?): Expr =
        VarRef(info.name, emptyList(), isNoArg = true, operandType = info.operandType, isConst = info.isConst, subKey = subKey)

    /** 共享 TARGET 引用。spec: VariableParser.cs:16-22 */
    private fun targetRef(): Expr = VarRef("TARGET", listOf(IntLiteral(0L)), operandType = OperandType.INT)

    // ------------------------------------------------------------------
    // TermStack —— 运算符栈状态机，逐行移植
    // ------------------------------------------------------------------

    private inner class TermStack {
        private var state = 0
        private var hasBefore = false
        private var hasAfter = false
        private var waitAfter = false
        private val stack = ArrayDeque<Any>()  // 元素: Expr | OperatorCode

        /** spec: ExpressionParser.cs:29-100 */
        fun add(op: OperatorCode) {
            if (state == 2 || state == 3) throw parseErr("无法识别的语法")
            if (state == 0) {
                if (!op.isUnary) throw parseErr("无法识别的语法")
                stack.addLast(op)
                state = if (op == OperatorCode.PLUS || op == OperatorCode.MINUS || op == OperatorCode.BIT_NOT) 2 else 3
                return
            }
            // state == 1
            if (op.isUnaryAfter) {
                if (hasAfter) throw parseErr("连续出现多个后置一元运算符")
                if (hasBefore) throw parseErr("不能同时使用前置与后置 ++/--")
                stack.addLast(op)
                reduceUnaryAfter()
                if (waitAfter) reduceUnary()
                hasBefore = false
                hasAfter = true
                waitAfter = false
            } else {
                if (!op.isBinary && !op.isTernary) throw parseErr("无法识别的语法")
                if (waitAfter) reduceUnary()
                val priority = op.priority
                while (lastPriority() >= priority) {
                    reduceLastThree()
                }
                stack.addLast(op)
                state = 0
                waitAfter = false
                hasBefore = false
                hasAfter = false
            }
        }

        /** spec: ExpressionParser.cs:102-129 */
        fun add(term: Expr) {
            stack.addLast(term)
            if (state == 1) throw parseErr("无法识别的语法")
            if (state == 2) waitAfter = true
            if (state == 3) {
                reduceUnary()
                hasBefore = true
            }
            state = 1
        }

        /** spec: ExpressionParser.cs:131-141 */
        private fun lastPriority(): Int {
            if (stack.size < 3) return -1
            val item = stack.removeLast()
            val p = (stack.last() as OperatorCode).priority
            stack.addLast(item)
            return p
        }

        /** spec: ExpressionParser.cs:143-165 */
        fun reduceAll(): Expr? {
            if (stack.isEmpty()) return null
            if (state != 1) throw parseErr("无法识别的语法")
            if (waitAfter) reduceUnary()
            waitAfter = false
            hasBefore = false
            hasAfter = false
            while (stack.size > 1) {
                reduceLastThree()
            }
            return stack.removeLast() as Expr
        }

        /** spec: ExpressionParser.cs:167-172 */
        private fun reduceUnary() {
            val o = stack.removeLast() as Expr
            stack.addLast(reduceUnaryTerm(stack.removeLast() as OperatorCode, o))
        }

        /** spec: ExpressionParser.cs:174-180 */
        private fun reduceUnaryAfter() {
            val op = stack.removeLast() as OperatorCode
            val o = stack.removeLast() as Expr
            stack.addLast(reduceUnaryAfterTerm(op, o))
        }

        /** spec: ExpressionParser.cs:182-200 */
        private fun reduceLastThree() {
            val right = stack.removeLast() as Expr
            val op = stack.removeLast() as OperatorCode
            val left = stack.removeLast() as Expr
            if (op.isTernary) {
                if (stack.size <= 1) throw parseErr("表达式不足")
                // 弹出 '#'（丢弃），再弹条件。spec: ExpressionParser.cs:202-207
                stack.removeLast()
                val cond = stack.removeLast() as Expr
                stack.addLast(reduceTernaryTerm(cond, left, right))
            } else {
                stack.addLast(reduceBinaryTerm(op, left, right))
            }
        }
    }

    private companion object {
        val COMPARISON_OPS = setOf(
            OperatorCode.EQUAL, OperatorCode.GREATER, OperatorCode.LESS,
            OperatorCode.GREATER_EQUAL, OperatorCode.LESS_EQUAL, OperatorCode.NOT_EQUAL,
        )
    }
}

// ---------------------------------------------------------------------------
// 归约工厂 —— OperatorMethodManager.ReduceXxxTerm 的移植
// ---------------------------------------------------------------------------

private fun typeName(t: OperandType) = if (t == OperandType.INT) "数值型" else "字符串型"

/** spec: OperatorMethodManager.cs:754-785 */
internal fun reduceUnaryTerm(op: OperatorCode, o1: Expr): Expr {
    if (op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT) {
        val vr = o1 as? VarRef ?: throw ExprEvalException("++/-- 只能作用于变量")
        if (vr.isConst) throw ExprEvalException("不能对常量 ${vr.name} 使用 ++/--")
    }
    if (o1.operandType == OperandType.INT) {
        // spec: OperatorMethodManager.cs:763-766 —— 一元 + 直接返回操作数本身
        if (op == OperatorCode.PLUS) return o1
        return Unary(op, o1)
    }
    throw ExprEvalException("一元运算符 ${OperatorCode.toOperatorString(op)} 不能作用于${typeName(o1.operandType)}")
}

/** spec: OperatorMethodManager.cs:787-811 */
internal fun reduceUnaryAfterTerm(op: OperatorCode, o1: Expr): Expr {
    if (op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT) {
        val vr = o1 as? VarRef ?: throw ExprEvalException("++/-- 只能作用于变量")
        if (vr.isConst) throw ExprEvalException("不能对常量 ${vr.name} 使用 ++/--")
    }
    if (o1.operandType == OperandType.INT &&
        (op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT)
    ) {
        return UnaryAfter(op, o1)
    }
    throw ExprEvalException("运算符 ${OperatorCode.toOperatorString(op)} 不能作用于${typeName(o1.operandType)}")
}

/** spec: OperatorMethodManager.cs:813-848 */
internal fun reduceBinaryTerm(op: OperatorCode, left: Expr, right: Expr): Expr {
    val li = left.operandType == OperandType.INT
    val ri = right.operandType == OperandType.INT
    return when {
        li && ri -> Binary(op, left, right, OperandType.INT)
        !li && !ri -> when (op) {
            OperatorCode.PLUS -> Binary(op, left, right, OperandType.STR)
            OperatorCode.EQUAL, OperatorCode.NOT_EQUAL, OperatorCode.GREATER,
            OperatorCode.LESS, OperatorCode.GREATER_EQUAL, OperatorCode.LESS_EQUAL,
            -> Binary(op, left, right, OperandType.INT)
            else -> throw ExprEvalException(
                "字符串型 ${OperatorCode.toOperatorString(op)} 字符串型：不合法的组合",
            )
        }
        op == OperatorCode.MULT -> Binary(op, left, right, OperandType.STR)  // str*int 重复。spec: OperatorMethodManager.cs:75-118,830-833
        else -> throw ExprEvalException(
            "${typeName(left.operandType)} ${OperatorCode.toOperatorString(op)} ${typeName(right.operandType)}：不合法的组合",
        )
    }
}

/** spec: OperatorMethodManager.cs:850-877 */
internal fun reduceTernaryTerm(cond: Expr, ifTrue: Expr, ifFalse: Expr): Expr {
    val type = when {
        cond.operandType == OperandType.INT &&
            ifTrue.operandType == OperandType.INT && ifFalse.operandType == OperandType.INT -> OperandType.INT
        cond.operandType == OperandType.INT &&
            ifTrue.operandType == OperandType.STR && ifFalse.operandType == OperandType.STR -> OperandType.STR
        else -> throw ExprEvalException("三目运算符的操作数类型不合法")
    }
    return Ternary(cond, ifTrue, ifFalse, type)
}
