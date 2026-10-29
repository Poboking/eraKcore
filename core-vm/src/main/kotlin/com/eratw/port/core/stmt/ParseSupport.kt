package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.TermEndWith
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.OperatorCode as LexOperatorCode
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.TokenType
import com.eratw.port.core.expr.OperatorCode as ExprOperatorCode

/**
 * 语句层解析错误（带行号/列号）。
 *
 * 原版一律走 ParserMediator.Warn(msg, line, level)，level>=2 时把 InstructionLine 标记为
 * IsError（spec: ArgumentBuilder.cs:19-40）；由于原版继续装载、仅在运行到该行时抛 ExeEE，
 * 本实现改为**解析期立即抛出**，避免静默吞错（纪律要求）。
 */
class StmtParseException(
    val line: Int,
    val col: Int,
    val statementName: String,
    message: String,
) : RuntimeException("语句解析错误(行:$line,列:$col)$statementName: $message")

/**
 * 语句层解析配置 —— 与原版 Config/JSONConfig 中**解析期**读取的语句级开关对应。
 * （表达式归约期的开关在 [com.eratw.port.core.expr.ParserConfig]，两者互不重叠。）
 */
data class StmtParseConfig(
    /**
     * VARI/VARS 作用域私有变量语句开关。
     *
     * 原版仅在 `UseScopedVariableInstruction=true` 时才把 VARI/VARS 注册进指令表
     * （spec: FunctionIdentifier.cs:4123-4127：`if (JSONConfig.Data.UseScopedVariableInstruction)
     * { addFunction(FunctionCode.VARI, …); addFunction(FunctionCode.VARS, …); }`，
     * 门控名单恰为 VARI、VARS 两条，FunctionIdentifier.cs:4125-4126）；
     * 关闭时语句名在标识符解析期即抛 CanNotUseVAR
     * （spec: IdentifierDictionary.cs:624-627，消息模板 Lang.cs:4010
     * 「{0}命令は現在の設定では使用できません」）。
     *
     * 本作 `setting.json` 为 false（eratw-画蛇添足版eratw4.980/setting.json；
     * 语料实测 0 条 VARI/VARS 行）。
     *
     * **默认 true（门开）= 与移植现状兼容**：老构造 `LineParser(resolver)` 不传本配置时
     * VARI/VARS 照常解析（LineParserTest.kt:328-341 等既有测试不变红）。
     * 生产装载（本作口径）显式传 false：
     * `LineParser(sym, stmtConfig = StmtParseConfig(useScopedVariableInstruction = false))`。
     */
    val useScopedVariableInstruction: Boolean = true,
)

/**
 * 语句层符号解析接口 —— 在表达式解析器所需的 [IdentifierResolver] 之上，
 * 增加「语句名/方法名是否为用户定义」的查询（原版 funcDic 查不到的标识符仍可能是
 * user-defined function label，spec: FunctionIdentifier.cs:4140-4148）。
 */
interface StmtResolver : IdentifierResolver {
    /**
     * 行首标识符是否为用户定义函数方法名（原版 FunctionMethodCreator 的 methodList 之外，
     * 用户 #FUNCTION 不注册进 funcDic，语句层的 METHOD 分支由符号层决定）。
     * TODO(verify): 用户函数标签表由 M2 符号层提供，此接口为其注入点。
     */
    fun isUserMethod(name: String): Boolean = false

    /**
     * 装载期进入 `@函数` 的**私有变量作用域**。
     *
     * 原版装载器把 `@` 行之后每一行的解析都挂在「当前函数标签」上：
     * 取标识符时先查 `GlobalStatic.Process.GetScaningLine().ParentLabelLine.GetPrivateVariable(key)`
     * （spec: IdentifierDictionary.cs:429-447 `GetVariableToken(key, subKey, allowPrivate)`，
     * 私有命中即返回，带 `@私钥` 才报 UsedAtForPrivVar），
     * 而函数体内的 `#DIM/#DIMS` 正是登记到这张私有表上
     * （spec: LogicalLineParser.cs:279-292 `UserDefinedVariableData.Create(…, isPrivate: true)` +
     * `label.AddPrivateVariable(...)`；表本体 spec: FunctionLabelLine.cs:98-117；
     * `#` 行的登记发生在 ErbLoader 第一遍扫描 spec: ErbLoader.cs:413-423，
     * 函数体行在第二遍 `parseLabel` 才解析，故**整函数体都可见**该私有变量）。
     * `VARI/VARS` 同样登记私有表（spec: LogicalLineParser.cs:471 / 508）。
     *
     * 因此「函数内 `#DIM` 的私有名 / 中文私有名 / `VARSET 私有名`」不是未定义标识符。
     * 本回调由 [LineParser.parseLogicalLine] 在解析到 `@` 行时**自动**调用，
     * 使语句层与编译层不可能各自维护一份作用域状态（A13 缺陷即编译层漏调，
     * 5,345 切片被误判为解析失败）。默认无操作：内建符号表无私有作用域。
     */
    fun enterFunction(functionName: String?) {}
}

/** 严格解析器：未知标识符按错误处理（生产模式应使用符号层实现）。 */
object StrictSymbolResolver : StmtResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? =
        BuiltinSymbols.variable(name)?.toVarInfo()

    override fun resolveFunction(name: String): OperandType? = BuiltinSymbols.function(name)

    /** 内建方法只能在 `NAME(...)` 形式出现（IdentifierDictionary.cs:538-547 / :607-612）。 */
    override fun canBeReferencedBare(name: String): Boolean = !BuiltinSymbols.isBuiltinMethod(name)

    /** 解析期允许未知语句名（用户函数调用），由调用方决定如何处理。 */
    override fun isUserMethod(name: String): Boolean = false
}

private fun VarSpec.toVarInfo(): VarInfo =
    VarInfo(name = name, operandType = type, dimension = dimension, isCharacterData = isCharacterData, isConst = isConst)

/**
 * 可变符号表 —— 语句层的解析上下文（内建符号 + 用户 #DIM 声明 + 用户函数名）。
 * M2 符号层就绪后可直接替换/灌入，spec: 见 specs/variables.yaml 的 scoping 一节。
 */
class SymbolTable : StmtResolver {
    private val vars = LinkedHashMap<String, VarInfo>()
    private val funcs = LinkedHashMap<String, OperandType>()
    private val methods = HashSet<String>()

    fun declareVariable(
        name: String,
        type: OperandType,
        dimension: Int,
        isCharacterData: Boolean = false,
        isConst: Boolean = false,
    ): SymbolTable {
        vars[name.uppercase()] = VarInfo(name, type, dimension, isCharacterData, isConst)
        return this
    }

    fun declareFunction(name: String, type: OperandType): SymbolTable {
        funcs[name.uppercase()] = type
        return this
    }

    fun declareMethod(name: String): SymbolTable {
        methods.add(name.uppercase())
        return this
    }

    override fun resolveVariable(name: String, subKey: String?): VarInfo? =
        vars[name.uppercase()] ?: BuiltinSymbols.variable(name)?.toVarInfo()

    override fun resolveFunction(name: String): OperandType? =
        funcs[name.uppercase()] ?: BuiltinSymbols.function(name)

    /**
     * 内建方法只能带括号调用；用户 `#FUNCTION` 同名声明（[funcs]）按用户函数处理。
     * spec: IdentifierDictionary.cs:538-547（无括号只查 refmethodDic）与 :607-612。
     */
    override fun canBeReferencedBare(name: String): Boolean =
        funcs.containsKey(name.uppercase()) || !BuiltinSymbols.isBuiltinMethod(name)

    override fun isUserMethod(name: String): Boolean = methods.contains(name.uppercase())

    /** 已装入的符号数量（诊断用）。 */
    val size: Int get() = vars.size + funcs.size + methods.size
}

// ---------------------------------------------------------------------------
// 词法器 Token → 表达式层 Token 的适配
// ---------------------------------------------------------------------------

/**
 * core.lexer.Token → core.expr.ExprToken。
 * 两套模型由并行开发产生，字段映射见 core/expr/Token.kt 的对接表。
 */
fun Token.toExprToken(): ExprToken = when (this) {
    is Token.Identifier -> ExprToken(ExprTokenKind.IDENTIFIER, code, line = line, col = col)
    is Token.Integer -> ExprToken(ExprTokenKind.INT_LITERAL, text, intValue = value, line = line, col = col)
    is Token.StringLiteral -> ExprToken(ExprTokenKind.STR_LITERAL, value, line = line, col = col)
    // 表达式解析器按源码符号文本取运算符（OperatorCode.fromText），
    // 而词法器给出的是原版 C# 枚举名，需再转一次（同 FormStringTerm.toExprTokens）。
    is Token.Operator -> ExprToken(
        ExprTokenKind.OPERATOR,
        ExprOperatorCode.toOperatorString(code.toExprOperator()),
        line = line, col = col,
    )
    is Token.FormStr -> ExprToken(ExprTokenKind.FORM_STRING, "<FORM>", line = line, col = col, form = form)
    is Token.Symbol -> when (char) {
        '(' -> ExprToken(ExprTokenKind.LPAREN, "(", line = line, col = col)
        ')' -> ExprToken(ExprTokenKind.RPAREN, ")", line = line, col = col)
        '[' -> ExprToken(ExprTokenKind.LBRACKET, "[", line = line, col = col)
        ']' -> ExprToken(ExprTokenKind.RBRACKET, "]", line = line, col = col)
        ',' -> ExprToken(ExprTokenKind.COMMA, ",", line = line, col = col)
        ':' -> ExprToken(ExprTokenKind.COLON, ":", line = line, col = col)
        '@' -> ExprToken(ExprTokenKind.AT, "@", line = line, col = col)
        '.' -> ExprToken(ExprTokenKind.DOT, ".", line = line, col = col)
        else -> throw StmtParseException(line, col, "", "表达式层不支持的分隔符 '$char'")
    }
}

/** lexer.OperatorCode → expr.OperatorCode（赋值两段式用）。 */
fun LexOperatorCode.toExprOperator(): ExprOperatorCode =
    ExprOperatorCode.fromCSharpName(name)
        ?: throw IllegalStateException("未映射的运算符 $name")

// ---------------------------------------------------------------------------
// 参数文本解析工具
// ---------------------------------------------------------------------------

/**
 * 语句参数解析上下文 —— 对应原版 InstructionLine 的 argprimitive/assigndest
 * （spec: InstructionLine.cs:14-16, 118-130）。
 *
 * @param argText 指令名之后的参数原文（原版 argprimitive）
 * @param destTokens 赋值目标词列（原版 assigndest，仅 SET 有；来自两段式第一段）
 * @param assignOp 赋值运算符（原版 AssignOperator；仅 SET 有）
 */
class ArgCtx(
    val line: Int,
    val code: FunctionCode,
    val statementName: String,
    val argText: String,
    val resolver: IdentifierResolver?,
    val config: ParserConfig,
    val warn: (String) -> Unit,
    /**
     * 词法层开关（原版 `Config.SystemIgnoreTripleSymbol` 等）。
     *
     * 语句层的 FORM 通道另有一份「到行尾」的词法实现（[analyseFormToEol]，
     * 因 core.lexer 的 `FormStrEndWith` 缺 EoL 分支），它必须服从与
     * `ErbLexer.analyseFormattedString` 相同的 [LexerConfig]，否则同一句会得到两种 StrForm。
     * spec: LexicalAnalyzer.cs:1138。
     */
    val lexerConfig: LexerConfig = LexerConfig(),
    val destTokens: List<Token>? = null,
    val assignOp: ExprOperatorCode? = null,
    /** PRINT 家族的真实参数形态（由语句名后缀决定，spec: FunctionIdentifier.cs:85-171）。 */
    val argTypeOverride: ArgType? = null,
) {
    /**
     * 本语句参数专用词法器（同一份 [lexerConfig]，含 `#DEFINE` 宏表）。
     *
     * 旧实现用 object 级 `ErbLexer()`：参数原文的宏表被整个丢掉 —— `LineParser` 的
     * 指令名/LHS 走配置词法器、参数却走无宏词法器，`DEF_DT_CID` 一类宏在参数位
     * 被当变量解析，最终运行时「未定义的变量」。per-ctx 实例保证同一语句两条通道同源。
     */
    val lexer: ErbLexer = ErbLexer(lexerConfig)
}

/**
 * 把参数原文切成词法 Token（spec: ArgumentBuilder.cs:107-110 popWords）。
 *
 * CMP-35：**不保留**无宏、无配置的 object 级词法器。旧实现 `private val sharedLexer =
 * ErbLexer()` 只服务 `VARI`/`VARS` 初值（`LineParser.intExpr`），参数原文里的 `#DEFINE`
 * 宏被整个丢掉 —— 同一语句的指令名/LHS 走配置词法器、初值却走无宏词法器，最后运行到
 * 使用点报「未定义的变量」。所有调用方一律走 [ArgCtx.lexer] 或从所属解析器的
 * `LexerConfig` 建同一个 [ErbLexer]；是否接受宏表由调用方显式传入的配置决定。
 */
internal fun lexWords(lexer: ErbLexer, text: String, line: Int): List<Token> =
    if (text.isEmpty()) emptyList()
    else lexer.analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, line)

/** 同 [lexWords]，但服从语句级的 [ArgCtx.lexerConfig]（宏表 / 全角空格等）。 */
internal fun lexWords(ctx: ArgCtx, text: String = ctx.argText, line: Int = ctx.line): List<Token> =
    if (text.isEmpty()) emptyList()
    else ctx.lexer.analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, line)

/** 词法 Token 列表 → 表达式 Token 列表。 */
internal fun toExprTokens(tokens: List<Token>): List<ExprToken> = tokens.map { it.toExprToken() }

/**
 * 参数原文 → 表达式列表（spec: ArgumentBuilder.cs:112-115 popTerms = ReduceArguments(Analyse(EoL), EoL)）。
 * 元素可为 null（空参数位 `FUNC(,1)`）。
 */
internal fun popTerms(ctx: ArgCtx, text: String = ctx.argText): List<Expr?> =
    exprParser(toExprTokens(lexWords(ctx, text, ctx.line)), ctx).reduceArguments(com.eratw.port.core.expr.ArgsEndWith.EoL)

internal fun exprParser(tokens: List<ExprToken>, ctx: ArgCtx): ExprParser =
    ExprParser(tokens, ctx.resolver, ctx.config)

/**
 * 从词法 Token 列的 [from] 起，取剩余原文（用于 SP_TIMES / STR_DOUBLE 之类的
 * 「先切出表达式、再从字符串流读实数」形态，spec: ArgumentParser.cs:352-370, 2534-2570）。
 */
internal fun rawTail(text: String, tokens: List<Token>, from: Int): String {
    if (from >= tokens.size) return ""
    return text.substring(tokens[from].col.coerceIn(0, text.length))
}

/**
 * 在 [tokens] 上按结束条件切出一个表达式并返回（表达式, 下一个 token 下标）。
 * 结束条件位标志与 [TermEndWith] 一致；嵌套括号/中括号/三目内部不切分。
 */
internal fun splitTerm(
    tokens: List<ExprToken>,
    pos: Int,
    endWith: Int,
    ctx: ArgCtx,
    /** CASE 的 `expr TO expr` 需要在顶层标识符 TO 处切断（spec: ExpressionParser.cs:379-388）。 */
    stopAtTo: Boolean = false,
): Pair<Expr?, Int> {
    var i = pos
    var paren = 0
    var bracket = 0
    var ternary = 0
    val start = i
    while (i < tokens.size) {
        val t = tokens[i]
        when (t.kind) {
            ExprTokenKind.LPAREN -> paren++
            ExprTokenKind.RPAREN -> {
                if (paren == 0) break
                paren--
            }
            ExprTokenKind.LBRACKET -> bracket++
            ExprTokenKind.RBRACKET -> {
                if (bracket == 0) break
                bracket--
            }
            ExprTokenKind.COMMA -> if (paren == 0 && bracket == 0) break
            ExprTokenKind.IDENTIFIER ->
                if (paren == 0 && bracket == 0) {
                    if (stopAtTo && t.text.equals("TO", ignoreCase = true)) break
                    // px 是尺寸单位后缀，不参与表达式。原版判据三合一
                    // （spec: ExpressionParser.cs:534-537）：带 KeyWordPx 位、词面等于 px、
                    // **且 wc.Next 是 `,` 或行尾**；缺最后一条时 `px` 只是个普通标识符，
                    // 由 reduceIdentifier 当变量/函数处理（`10 px + 1` 因此报未定义变量，
                    // 而不是把 `px + 1` 当单位丢掉）。CMP-53。
                    if ((endWith and TermEndWith.KeyWordPx) != 0 &&
                        t.text.equals("px", ignoreCase = true) &&
                        (i + 1 >= tokens.size ||
                            tokens[i + 1].kind == ExprTokenKind.COMMA ||
                            tokens[i + 1].kind == ExprTokenKind.EOL)
                    ) break
                }
            ExprTokenKind.OPERATOR -> {
                if (paren == 0 && bracket == 0) {
                    // CMP-52：`t.text` 是**源码符号**（"?" / "#" / "=" / "'="，见
                    // `ExprOperatorCode.toOperatorString`），不能拿去比枚举名
                    // （"TERNARY_A" / "ASSIGNMENT" / "ASSIGNMENT_STR"）——旧写法三处恒假，
                    // `TermEndWith.Assignment` 终止位因此永不生效（spec: ExpressionParser.cs:548-553）。
                    when (ExprOperatorCode.fromText(t.text)) {
                        ExprOperatorCode.TERNARY_A -> ternary++
                        ExprOperatorCode.TERNARY_B -> ternary--
                        ExprOperatorCode.ASSIGNMENT, ExprOperatorCode.ASSIGNMENT_STR ->
                            if ((endWith and TermEndWith.Assignment) != 0) break
                        else -> {}
                    }
                }
            }
            else -> {}
        }
        i++
    }
    if (i == start) return null to i
    val slice = tokens.subList(start, i).toList()
    val expr = ExprParser(slice, ctx.resolver, ctx.config).reduceExpressionTerm(TermEndWith.EoL)
    return expr to i
}

/** 表达式是否整数型（对应原版 AExpression.IsInteger）。 */
internal fun Expr?.isInteger(): Boolean = this != null && operandType == OperandType.INT

/** 表达式是否为字符串型。 */
internal fun Expr?.isString(): Boolean = this != null && operandType == OperandType.STR

/** 常量整数值（对应原版 SingleTerm.Int / GetIntValue(null)）。 */
internal fun Expr?.constInt(): Long? = (this as? com.eratw.port.core.expr.IntLiteral)?.value

/** 常量字符串值。 */
internal fun Expr?.constStr(): String? = (this as? com.eratw.port.core.expr.StrLiteral)?.value

/** 变量引用（对应原版 VariableTerm 判定）。 */
internal fun Expr?.asVarRef(): VarRef? = this as? VarRef

/** 参数静态类型数组元素。 */
internal fun argValueType(t: OperandType): ArgValueType = ArgValueType.of(t)
