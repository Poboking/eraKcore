package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprParser
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.TermEndWith
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.OperatorCode as LexOperatorCode
import com.eratw.port.core.lexer.Token

/** 语句层告警（非致命）。 */
data class StmtWarning(val line: Int, val message: String)

/**
 * 按「标签定义」寻址的私有变量作用域回调。
 *
 * 原版的私有变量表是 **每个 `FunctionLabelLine` 一份**：
 * `FunctionLabelLine.privateVar` 声明于 `FunctionLabelLine.cs:15`，登记入口
 * `AddPrivateVariable`（`FunctionLabelLine.cs:98-111`），查询入口 `GetPrivateVariable`
 * （`FunctionLabelLine.cs:113-117`）。同名多定义（事件成员组）**各自一份**，
 * A 文件的 `@X` 私有名不会出现在 B 文件的 `@X` 里 —— 这正是 G2 要收紧的宽松面。
 *
 * [enterFunction] 只给函数名，无法区分同名多定义；本接口用「文件名 + 行号」作为定义身份。
 * 未实现本接口的解析器仍走 [StmtResolver.enterFunction]（默认无操作），不破坏既有测试替身。
 *
 * - 形参校验（G1）：`@F(...)` 的每个形参按原版 `ReduceArguments(..., isDefine:true)`
 *   解成 `VariableTerm`（spec: ErbLoader.cs:658-702），非可赋值变量 / 未声明名 →
 *   `ArgCanOnlyAssignableVar`（spec: ErbLoader.cs:651-652）→ `Warn(level 2, isError)`（:769）。
 * - `VARI`/`VARS`（G3）：原版在 `ParseLine` 内即时 `AddPrivateVariable`
 *   （spec: LogicalLineParser.cs:471 / 508），本条回调与之同点位。
 */
interface DefinitionScopedResolver : StmtResolver {
    /**
     * 进入某个 `@函数` **定义**的私有作用域。
     *
     * @param functionName 标签名（大写不敏感）
     * @param filename 逻辑行来源文件（`LogicalLine.filename`）
     * @param line 标签行行号（`LogicalLine.lineNo`，0 基）
     */
    fun enterFunctionDefinition(functionName: String?, filename: String, line: Int) {}

    /**
     * 登记 `VARI`/`VARS` 声明的私有变量（spec: LogicalLineParser.cs:463-471 / 500-508）。
     *
     * 原版字段：`Name` / `Static=false` / `Lengths` / `Dimension=list2.Count` / `TypeIsStr`，
     * 即维度 = 长度个数，无 CONST 语义（`UserDefinedVariableData.cs:19-21`）。
     */
    fun declarePrivateVariable(name: String, type: OperandType, dimensions: IntArray) {}
}

/**
 * ERB 语句层行解析器 —— 移植自 LogicalLineParser.ParseLine
 * （spec: LogicalLineParser.cs:385-563）与 ArgumentParser（spec: ArgumentParser.cs:19-2560）。
 *
 * ## 两段式（重要）
 * 原版对非指令行采用两段式：
 * `Analyse(stream, LexEndWith.Operator, None)` 截出左值词列，再用 `ReadAssignmentOperator`
 * 读取赋值运算符（spec: LogicalLineParser.cs:536-556）。**不能**用整行 tokenize 代替：
 * 右值里的 `=`、`'`、`{`、`\`、`$`、括号等只有越过首个顶层赋值符后才合法。
 *
 * ## 分派顺序（spec: LogicalLineParser.cs:385-556）
 * 1. `+`/`-` 开头：仅 `++`/`--` 合法 → 赋值 SET（自增减）
 * 2. 行首标识符命中指令表（含 PRINT 家族、别名）→ 指令行，参数交 [ArgBuilders]
 * 3. 行首标识符是用户函数方法名 → METHOD
 * 4. 否则走赋值两段式 → SET
 *
 * ## 语句级配置门控
 * 构造参数 [stmtConfig]（[StmtParseConfig]）承载原版 Config 中**解析期**读取的语句级开关，
 * 当前仅 `useScopedVariableInstruction`（VARI/VARS 门控）：开关关闭时语句名命中
 * VARI/VARS 即在标识符解析期抛 CanNotUseVAR 同口径错误
 * （spec: FunctionIdentifier.cs:4123-4127 + IdentifierDictionary.cs:624-627）。
 * 默认 true（门开）= 与现状兼容；本作生产口径（setting.json false）显式传 false。
 * 表达式层开关在 [ParserConfig]（core/expr），两者互不重叠。
 *
 * 解析失败抛 [StmtParseException]（带行号），不静默吞错。
 */
class LineParser(
    val resolver: StmtResolver = StrictSymbolResolver,
    private val config: ParserConfig = ParserConfig(),
    private val stmtConfig: StmtParseConfig = StmtParseConfig(),
    /**
     * 词法层开关 —— 同时喂给本解析器自己的词法器与 [ArgCtx.lexerConfig]，
     * 使 FORM 通道的两条实现（词法 `analyseFormattedString` / 语句 `analyseFormToEol`）
     * 共用一份配置。默认 [LexerConfig] 即本作口径（`emuera.config:63`
     * `DO NOT PROCESS TRIPLE SYMBOLS INSIDE FORM:NO` ⇒ 三连展开开启）。
     */
    private val lexerConfig: LexerConfig = LexerConfig(),
) {
    private val lexer = ErbLexer(lexerConfig)

    /** 解析期告警（原版 level 0/1 的非致命诊断）。 */
    val warnings = mutableListOf<StmtWarning>()

    private fun warn(line: Int): (String) -> Unit = { msg ->
        warnings.add(StmtWarning(line, msg))
    }

    // -----------------------------------------------------------------------
    // 逻辑行入口
    // -----------------------------------------------------------------------

    /** 解析一条逻辑行；空/纯空白行返回 null（spec: LogicalLineParser.cs:387-391）。 */
    fun parseLogicalLine(line: LogicalLine): Statement? {
        if (line.content.isEmpty()) return null
        return when (line.kind) {
            LineKind.CODE -> parseCodeLine(line.content, line.lineNo)
            LineKind.FUNCTION_LABEL, LineKind.GOTO_LABEL ->
                // 作用域切换在 [parseLabelLine] 内完成（形参校验必须在**该定义**的私有表内解析）。
                // `$` 标签不切作用域。这是语句层/编译层共用的唯一入口，
                // 任何一侧都不许自己再维护作用域。
                parseLabelLine(line.content, line.lineNo, line.filename)
            LineKind.SHARP -> parseSharpLine(line.content, line.lineNo)
        }
    }

    // -----------------------------------------------------------------------
    // 代码行
    // -----------------------------------------------------------------------

    fun parseCodeLine(text: String, lineNo: Int): Statement {
        val content = text.trimStart(' ', '\t')
        if (content.isEmpty()) throw StmtParseException(lineNo, 0, "", "空行")
        // 1. +/- 开头 —— 只允许 ++/--。spec: LogicalLineParser.cs:394-405
        if (content[0] == '+' || content[0] == '-') {
            return parseIncrementLine(content, lineNo)
        }
        // 2. 行首标识符。spec: LogicalLineParser.cs:406-414
        val first = try {
            lexer.readSingleIdentifier(CharStream(content))
        } catch (e: LexError) {
            throw StmtParseException(lineNo, 0, "", e.message ?: "行首字符非法")
        }
        if (first.isEmpty()) {
            throw StmtParseException(lineNo, 0, "", "不正な文字で行が始まっています")
        }
        val nameEnd = first.length
        val code = FunctionCode.BY_NAME[first.uppercase()]
        if (code != null) {
            return parseInstructionLine(code, first, content, nameEnd, lineNo)
        }
        // 3. 内建函数方法名（原版 funcDic 同时收录 methodList，spec: FunctionIdentifier.cs:4140-4148）
        //    与用户定义函数方法名 → METHOD 语句
        if (first.uppercase() in BuiltinSymbols.methods || resolver.isUserMethod(first)) {
            val argText = splitArgs(content, nameEnd, lineNo, first)
            val ctx = ArgCtx(
                lineNo, FunctionCode.METHOD, first, argText, resolver, config, warn(lineNo),
                lexerConfig = lexerConfig,
            )
            val arg = ArgBuilders.create(ctx)
            return MethodStmt(first, arg, lineNo, content)
        }
        // 4. 赋值两段式。spec: LogicalLineParser.cs:530-556
        return parseAssignmentLine(content, lineNo)
    }

    /** spec: LogicalLineParser.cs:394-405 */
    private fun parseIncrementLine(content: String, lineNo: Int): Statement {
        val st = CharStream(content)
        val tokens = try {
            lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, lineNo)
        } catch (e: LexError) {
            throw StmtParseException(lineNo, e.column, "", e.message ?: "词法错误")
        }
        val op = (tokens.firstOrNull() as? Token.Operator)?.code
        if (op != LexOperatorCode.Increment && op != LexOperatorCode.Decrement) {
            throw StmtParseException(
                lineNo, 0, "",
                if (content[0] == '+') "行首为'+'但不是自增" else "行首为'-'但不是自减",
            )
        }
        val destTokens = tokens.drop(1)
        val assignOp = if (op == LexOperatorCode.Increment) OperatorCode.INCREMENT else OperatorCode.DECREMENT
        val ctx = ArgCtx(
            line = lineNo, code = FunctionCode.SET, statementName = content.take(2),
            argText = "", resolver = resolver, config = config, warn = warn(lineNo),
            lexerConfig = lexerConfig,
            destTokens = destTokens, assignOp = assignOp,
        )
        val arg = ArgBuilders.create(ctx)
        return AssignStmt(
            dest = (arg as SpSetArgument).dest, op = assignOp, args = arg, line = lineNo, raw = content,
        )
    }

    /** 指令行：参数原文 = 指令名之后的部分（原版只丢弃一个分隔字符）。spec: LogicalLineParser.cs:412-528 */
    private fun parseInstructionLine(
        code: FunctionCode,
        name: String,
        content: String,
        nameEnd: Int,
        lineNo: Int,
    ): Statement {
        // VARI/VARS 门控：原版 UseScopedVariableInstruction=false 时这两个名字根本不进
        // funcDic（spec: FunctionIdentifier.cs:4123-4127），语句名在标识符解析期即抛
        // CanNotUseVAR（spec: IdentifierDictionary.cs:624-627 + Lang.cs:4010）。
        // 门控先于参数切分：原版开关关闭时语句名解析即失败，永远走不到参数形态校验。
        if (!stmtConfig.useScopedVariableInstruction &&
            (code == FunctionCode.VARI || code == FunctionCode.VARS)
        ) {
            throw StmtParseException(lineNo, 0, name, "命令在当前设置下不可使用（UseScopedVariableInstruction 已禁用）")
        }
        val argText = splitArgs(content, nameEnd, lineNo, name)
        // PRINT 家族：后缀决定参数形态。spec: FunctionIdentifier.cs:85-171
        if (code.argType == ArgType.PRINT) {
            val spec = PrintSpec.parse(code)
                ?: throw StmtParseException(lineNo, 0, name, "PRINT 语句名后缀非法")
            val ctx = ArgCtx(
                line = lineNo, code = code, statementName = name, argText = argText,
                resolver = resolver, config = config, warn = warn(lineNo),
                lexerConfig = lexerConfig,
                argTypeOverride = spec.argType,
            )
            val arg = ArgBuilders.create(ctx)
            return PrintStmt(spec, arg, lineNo, content)
        }
        // VARI / VARS：原版在 ParseLine 内手工解析（spec: LogicalLineParser.cs:412-511）；
        // 开关关闭时已在函数顶部门控处抛 CanNotUseVAR，到不了这里。
        // 原文 = 指令名之后的**全部**字符（不经分隔符切分：原版 :418 直接对 stream
        // 取 Substring，未 ShiftNext；分号截断在 :418-423 由本遍 splitTopLevelEquals 完成）。
        if (code == FunctionCode.VARI || code == FunctionCode.VARS) {
            return parseVarDecl(code, content.substring(nameEnd), lineNo, content)
        }
        val ctx = ArgCtx(
            line = lineNo, code = code, statementName = name, argText = argText,
            resolver = resolver, config = config, warn = warn(lineNo),
            lexerConfig = lexerConfig,
        )
        val arg = ArgBuilders.create(ctx)
        return buildInstruction(code, arg, lineNo, content)
    }

    /**
     * 取出指令名之后的参数原文。
     *
     * spec: LogicalLineParser.cs:513-527 —— 原版在读完语句名标识符后只看**一个**字符：
     * `;` / `' '` / `'\t'` 三者之一，或（`Config.SystemAllowFullSpace` 为真时）`'\u3000'`，
     * 命中就 `stream.ShiftNext()` **只跳这 1 个字符**，然后把「该字符之后的整行剩余」原样
     * 交给 ArgumentBuilder（`:515` / `:521` 两处 `new InstructionLine(position, functionIdentifier, stream)`）；
     * 不命中才是 `InvalidLine`（`:523-527`）。
     *
     * 两点必须照抄的原版事实：
     * - **`;` 在这里不是注释起始符**，只是四个合法分隔符之一。行内注释是词法器的事
     *   （`LexicalAnalyzer.cs:919-933` 的 `Analyse` 与 `:647-690` 的 `SkipWhiteSpace` 才会
     *   `Seek(0, End)`），而语句实参这两条路都不受 `;` 影响，只是机制不同：
     *   `STR` / `STR_NULLABLE` 走 `STR_ArgumentBuilder`（注册 `ArgumentParser.cs:2623-2624`），
     *   它**不进词法器**、直接吃 `charStream.Substring()` 原文（`:437-477`，取值在 `:461`）；
     *   `FORM_STR` / `FORM_STR_NULLABLE` 走 `FORM_STR_ArgumentBuilder`（`:479-514`，注册
     *   `:2625-2626`），它进的是 `AnalyseFormattedString`（`LexicalAnalyzer.cs:1073`）且
     *   `endWith = EoL` —— 该形态下 `;` 与 `(` `[` 同列、按普通字符 append（`:1105-1111`），
     *   只有 `LeftParenthesis_Bracket_Comma_Semicolon` 才在 `;` 处断词（`:1107-1109`）。
     *   于是 `PRINT;abc` 的实参就是 `abc`、`PRINT a;b` 的实参就是 `a;b`，
     *   分号之后的内容会照常打印出来。移植侧曾把 `;` 当「名后即注释」直接清空实参，
     *   与本作的语料相撞（`口上・メッセージ関連/個人口上/153 SonBiten [孙美天]/你的小小大圣/`
     *   `M_KOJO_K153_性骚扰指令.ERB:2108` 的 `PRINTFORM; 「…」` 整行文字被吞），已按原口径改回。
     * - 分隔符只吃 1 个：`PRINT  x`（两名）的实参原文是 `" x"`，带一个前导空格
     *   （原版同样如此，`trim` 只在 `FORM_STR_ANY`/`SP_*` 那几类里由词法器负责）。
     */
    private fun splitArgs(content: String, nameEnd: Int, lineNo: Int, name: String): String {
        if (nameEnd >= content.length) return "" // spec: LogicalLineParser.cs:513-516（名后什么都没有 = 空实参）
        val c = content[nameEnd]
        if (c == ';' || c == ' ' || c == '\t' ||
            (c == '\u3000' && lexerConfig.systemAllowFullSpace)
        ) {
            return content.substring(nameEnd + 1) // spec: LogicalLineParser.cs:517-522（只跳 1 个字符）
        }
        // spec: LogicalLineParser.cs:523-527 —— 其余字符一律 InvalidLine；
        // 全角空格在 SystemAllowFullSpace=false 时也走这里，原版此时给的是
        // 「要把该配置打开」那条文案（`Lang.Error.InvalidCharacterAfterInstruction1`）。
        throw StmtParseException(
            lineNo, nameEnd, name,
            if (c == '\u3000') {
                "指令后跟随全角空格，但 SystemAllowFullSpace 已关闭（原版提示要把该配置打开）"
            } else {
                "指令后跟随非法字符 '$c'"
            },
        )
    }

    /** 赋值行两段式。spec: LogicalLineParser.cs:536-556 */
    private fun parseAssignmentLine(content: String, lineNo: Int): Statement {
        val st = CharStream(content)
        val destTokens = try {
            lexer.analyse(st, LexEndWith.Operator, LexAnalyzeFlag.NONE, lineNo)
        } catch (e: LexError) {
            throw StmtParseException(lineNo, e.column, "", e.message ?: "词法错误")
        }
        var op = try {
            lexer.readAssignmentOperator(st, lineNo)
        } catch (e: LexError) {
            throw StmtParseException(lineNo, e.column, "", "无法解释为赋值语句：${e.message}")
        }
        if (op == LexOperatorCode.Equal) {
            warnings.add(StmtWarning(lineNo, "赋值请使用 '=' 而不是 '=='"))
            op = LexOperatorCode.Assignment
        }
        // 后置 ++/--：`LOCAL++` 与前置 `++LOCAL` 是同一语句的两种写法
        // （spec: LogicalLineParser.cs:394-405 前置、:536-556 赋值行读出的 Increment/Decrement）。
        // LexOperatorCode 的枚举名是大写 INCREMENT，而 ExprOperatorCode 的 C# 名映射是小写驼峰
        // "Increment" ⇒ 不能走 toExprOperator（收口前这里抛「不支持的赋值运算符 INCREMENT」，
        // bootSmoke 的 PRINT_STATE.ERB:429 `LOCAL++` 即此路径）。
        if (op == LexOperatorCode.Increment || op == LexOperatorCode.Decrement) {
            val assignOp = if (op == LexOperatorCode.Increment) OperatorCode.INCREMENT else OperatorCode.DECREMENT
            val argText = content.substring(st.currentPosition.coerceAtMost(content.length))
            val ctx = ArgCtx(
                line = lineNo, code = FunctionCode.SET,
                statementName = if (op == LexOperatorCode.Increment) "++" else "--",
                argText = argText, resolver = resolver, config = config, warn = warn(lineNo),
                lexerConfig = lexerConfig,
                destTokens = destTokens, assignOp = assignOp,
            )
            val arg = ArgBuilders.create(ctx)
            return AssignStmt(
                dest = (arg as SpSetArgument).dest, op = assignOp, args = arg, line = lineNo, raw = content,
            )
        }
        val exprOp = try {
            op.toExprOperator()
        } catch (e: Exception) {
            throw StmtParseException(lineNo, st.currentPosition, "", "不支持的赋值运算符 $op")
        }
        val argText = content.substring(st.currentPosition.coerceAtMost(content.length))
        val ctx = ArgCtx(
            line = lineNo, code = FunctionCode.SET, statementName = "SET", argText = argText,
            resolver = resolver, config = config, warn = warn(lineNo),
            lexerConfig = lexerConfig,
            destTokens = destTokens, assignOp = exprOp,
        )
        val arg = ArgBuilders.create(ctx)
        val dest = when (arg) {
            is SpSetArgument -> arg.dest
            is SpSetArrayArgument -> arg.dest
            else -> throw StmtParseException(lineNo, 0, "SET", "赋值左值必须是变量")
        }
        return AssignStmt(dest, exprOp, arg, lineNo, content)
    }

    /**
     * VARI / VARS —— 作用域私有变量声明。
     * spec: LogicalLineParser.cs:412-511（原版直接对参数原文做字符串切分，不走 ArgumentBuilder）。
     */
    private fun parseVarDecl(
        code: FunctionCode,
        argText: String,
        lineNo: Int,
        content: String,
    ): Statement {
        val (head, initText) = splitTopLevelEquals(argText)
        val parts = head.split(',')
        val name = parts[0].trim()
        if (name.isEmpty()) throw StmtParseException(lineNo, 0, code.name, "变量名不能为空")
        val lengths = mutableListOf<Int>()
        var hasDims = false
        if (parts.size > 1) {
            hasDims = true
            for (i in 1 until parts.size) {
                val v = parts[i].trim().toIntOrNull()
                    ?: throw StmtParseException(lineNo, 0, code.name, "变量长度不是整数：'${parts[i].trim()}'")
                lengths.add(v)
            }
        } else {
            lengths.add(1)
        }
        // G3：VARI/VARS 登记当前函数的私有变量表（spec: LogicalLineParser.cs:463-471 / 500-508）。
        // 原版字段：Dimension = 长度个数、TypeIsStr = (VARS)、Static=false，无 CONST；
        // 登记点位是 ParseLine 内即时登记，故只有**之后的**行可见（与函数体内 #DIM 的
        // 第一遍预登记不同，spec: ErbLoader.cs:413-423）。
        if (resolver is DefinitionScopedResolver) {
            resolver.declarePrivateVariable(
                name,
                if (code == FunctionCode.VARI) OperandType.INT else OperandType.STR,
                lengths.toIntArray(),
            )
        }
        // 初值只在「无维度」时读取（spec: LogicalLineParser.cs:447-462 / 485-493 ——
        // VARI 的 aExpression、VARS 的 value 都只在 array.Length == 1 分支里填；
        // 带 dim 时 '=' 之后原样丢弃，VARI 初值折 SingleLongTerm(0)（:472-479）、
        // VARS 停 null（:484-499））。旧实现把 `VARI A,1,2 = 5` 的 5 当初值，与原版不符。
        val effInit = if (hasDims) null else initText
        val arg: Argument = if (code == FunctionCode.VARI) {
            val init = if (effInit == null) null else intExpr(effInit, lineNo)
            IntAsignArgument(name, lengths.toIntArray(), init)
        } else {
            val value = effInit?.let { extractQuoted(it) }
            StrAsignArgument(name, lengths.toIntArray(), value)
        }
        return VarDeclStmt(code, arg, lineNo, content)
    }

    private fun intExpr(text: String, lineNo: Int): Expr? {
        if (text.isBlank()) return null
        // CMP-35：初值必须与本行其它词法通道同源（同一个 lexerConfig：宏表 / 全角空格 / 三连记号）。
        // 旧实现走 `lexWords(text, lineNo)` 的 object 级 `ErbLexer()` —— 无宏、无配置。
        // 归约必须是**整型**：原版 handle 的是 `ReduceIntegerTerm`（spec: LogicalLineParser.cs:461），
        // 非整型（如 `VARI A = "x"`）要按 CodeEE 拒绝该行，而不是收成字符串初值。
        val tokens = lexer.analyse(CharStream(text), LexEndWith.EoL, LexAnalyzeFlag.NONE, lineNo)
        return try {
            ExprParser(
                tokens.map { it.toExprToken() }, resolver, config,
            ).reduceIntegerTerm(TermEndWith.EoL)
        } catch (e: StmtParseException) {
            throw e
        } catch (e: Exception) {
            // 与语句层其它解析失败统一收敛到 StmtParseException（带行号，不静默吞错）。
            throw StmtParseException(lineNo, -1, "VARI", e.message ?: "VARI 初值非法")
        }
    }

    /** 取首个顶层 '=' 切分（引号/括号内的 '=' 不算）。spec: LogicalLineParser.cs:418-437 */
    private fun splitTopLevelEquals(text: String): Pair<String, String?> {
        var quote = false
        var paren = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> quote = !quote
                quote -> {}
                c == '(' || c == '[' -> paren++
                c == ')' || c == ']' -> paren--
                c == '=' && paren == 0 -> {
                    // 排除 == <= >= !=
                    val prev = if (i > 0) text[i - 1] else ' '
                    val next = if (i + 1 < text.length) text[i + 1] else ' '
                    if (prev !in "=<>!" && next != '=') return text.substring(0, i) to text.substring(i + 1)
                }
                c == ';' && paren == 0 && !quote -> return text.substring(0, i) to null
            }
            i++
        }
        return text to null
    }

    private fun extractQuoted(text: String): String? {
        val a = text.indexOf('"')
        val b = text.lastIndexOf('"')
        if (a < 0 || b <= a) return null
        return text.substring(a + 1, b)
    }

    // -----------------------------------------------------------------------
    // 语句构造
    // -----------------------------------------------------------------------

    private fun buildInstruction(code: FunctionCode, arg: Argument, line: Int, raw: String): Statement {
        val expr = (arg as? ExpressionArgument)?.term
        return when (code) {
            FunctionCode.IF, FunctionCode.SIF ->
                IfStmt(expr ?: IntLiteral(0L), arg, line, raw, single = code == FunctionCode.SIF)
            FunctionCode.ELSEIF -> ElseIfStmt(expr ?: IntLiteral(0L), arg, line, raw)
            FunctionCode.ELSE -> ElseStmt(arg, line, raw)
            FunctionCode.ENDIF -> EndIfStmt(arg, line, raw)
            FunctionCode.SELECTCASE -> SelectCaseStmt(expr ?: IntLiteral(0L), arg, line, raw)
            FunctionCode.CASE -> CaseStmt((arg as CaseArgument).caseExps, arg, line, raw)
            FunctionCode.CASEELSE -> CaseElseStmt(arg, line, raw)
            FunctionCode.ENDSELECT -> EndSelectStmt(arg, line, raw)
            FunctionCode.FOR, FunctionCode.REPEAT -> ForStmt(code, arg, line, raw)
            FunctionCode.NEXT, FunctionCode.REND -> NextStmt(arg, line, raw, code)
            FunctionCode.WHILE -> WhileStmt(expr ?: IntLiteral(0L), arg, line, raw)
            FunctionCode.WEND -> WendStmt(arg, line, raw)
            FunctionCode.DO -> DoStmt(arg, line, raw)
            FunctionCode.LOOP -> LoopStmt(expr, arg, line, raw)
            FunctionCode.CATCH -> CatchStmt(arg, line, raw)
            FunctionCode.ENDCATCH -> EndCatchStmt(arg, line, raw)
            FunctionCode.DATALIST, FunctionCode.ENDLIST -> DataListStmt(code, arg, line, raw)
            FunctionCode.ENDDATA, FunctionCode.ENDNOSKIP, FunctionCode.ENDFUNC ->
                EndBlockStmt(code, arg, line, raw)
            FunctionCode.DATA, FunctionCode.DATAFORM ->
                DataStmt(code, rawTextOfArg(arg), arg, line, raw)
            FunctionCode.STRDATA -> DataStmt(code, rawTextOfArg(arg), arg, line, raw)
            in PRINT_DATA_CODES -> PrintDataStmt(code, arg, line, raw)
            in JUMP_CODES -> JumpStmt(code, (arg as? SpCallArgument)?.label, arg, line, raw)
            in CALL_CODES -> CallStmt(code, (arg as? SpCallArgument)?.label, arg, line, raw)
            FunctionCode.CALLF, FunctionCode.TRYCALLF ->
                CallFStmt((arg as? SpCallFArgument)?.literalStr, arg, line, raw, code)
            FunctionCode.CALLFORMF, FunctionCode.TRYCALLFORMF ->
                CallFStmt((arg as? SpCallFArgument)?.literalStr, arg, line, raw, code)
            FunctionCode.CALLSHARP -> CallSharpStmt(arg, line, raw)
            FunctionCode.RETURN ->
                ReturnStmt((arg as ExpressionArrayArgument).terms, arg, line, raw)
            FunctionCode.RETURNF, FunctionCode.RETURNFORM -> ReturnFStmt(expr, arg, line, raw, code)
            in FLOW_ONLY_CODES -> FlowStmt(code, arg, line, raw)
            else -> InstructionStmt(code, arg, line, raw)
        }
    }

    private fun rawTextOfArg(arg: Argument): String = when (arg) {
        is ExpressionArgument -> arg.term?.constStr() ?: ""
        else -> ""
    }

    // -----------------------------------------------------------------------
    // 标签行 / # 行
    // -----------------------------------------------------------------------

    /** spec: LogicalLineParser.cs:314-383 ParseLabelLine */
    fun parseLabelLine(content: String, lineNo: Int, filename: String = "<line>"): Statement {
        val (isFunction, name, rest) = try {
            lexer.parseLabelLine(content, lineNo, filename)
        } catch (e: LexError) {
            throw StmtParseException(lineNo, 0, "", e.message ?: "标签名非法")
        }
        if (!isFunction) return GotoLabelStmt(name, lineNo, content)
        // 先切到**本定义**的私有作用域，再校验形参：原版 parseLabel 在
        // `parentProcess.scaningLine = label` 下解析参数（spec: ErbLoader.cs:546-548），
        // 因此 `#DIM DYNAMIC VAL` + `@F(VAL)` 合法（spec: ErbLoader.cs:658-702）。
        enterFunctionDefinition(name, filename, lineNo)
        // 两种形参写法语料都存在：`@NAME(ARG, ARG$)` 与 `@NAME, ARG, ARG:1 = 默认值`
        // （`@K17_RS, ARGS, ARGS:1 = "/", ARG = 1`）。首字符不是 '(' 或 ',' 时原版
        // 报 WrongArgFormat（spec: ErbLoader.cs:654-668）。
        // 解析器跨标签复用；零形参标签在 extractLabelParams 里早退，必须先清掉上一函数的项。
        labelParamRefs = emptyList()
        val params = extractLabelParams(rest, name, lineNo)
        return FuncLabelStmt(name, params, lineNo, content, labelParamRefs, labelParamDefaults)
    }

    /** 作用域切换：定义级解析器优先，老解析器退回按函数名（默认无操作）。 */
    private fun enterFunctionDefinition(name: String, filename: String, line: Int) {
        val r = resolver
        if (r is DefinitionScopedResolver) r.enterFunctionDefinition(name, filename, line)
        else r.enterFunction(name)
    }

    /**
     * 提取并校验函数形参名（G1）。
     *
     * 原版把每个形参解成 `VariableTerm`：`@F("ABC")` / `@F(123)` / 未声明名 /
     * CONST 名一律 `ArgCanOnlyAssignableVar`（spec: ErbLoader.cs:651-652 + 680-684；
     * 未声明名在 ReduceArguments 的标识符解析期抛 CodeEE，spec: IdentifierDictionary.cs:628），
     * 随后 `Warn(level 2, isError:true)`（spec: ErbLoader.cs:769）把该标签置错。
     * 本移植沿用「解析期立即抛 [StmtParseException]」的既有口径（不新增错误等级）。
     *
     * 省略形参位（`@F(A,,B)`）在原版是 `CannotOmitFuncArg`
     * （spec: ExpressionParser.cs:269-272），同样拒绝。
     *
     * 冒号下标必须是**编译期常量**（CMP-85A 生产者侧）：原版要求
     * `variableTerm.isAllConst`（spec: ErbLoader.cs:691-693），否则
     * `ArgSubscriptOnlyConst`（→ :774-776）并 `Warn(level 2, isError:true)`（:768-770）。
     * 本遍按「表达式归约后仍是 [IntLiteral]」判定，非字面量形态立即抛
     * [StmtParseException]（见 [constSubscripts]）；不再把 `OUT:1+1` 截成 `OUT:1`、
     * 把 `OUT:-1` 截成 `OUT:`。
     *
     * TODO(verify): 默认值类型匹配（spec: ErbLoader.cs:724-741）已按 [parseLabelDefault]
     * 复刻；`#DIM CONST` 名作下标（如 `@F(OUT:C_INDEX)`）语料 0 例，本遍没有常量值表，
     * 按非字面量拒绝，不做折叠（见 [constSubscripts]）。
     * TODO(verify): 方括号形参 `@F[…]`（spec: ErbLoader.cs:613-640，isDefine:false 的常量参数）
     * 也未复刻；3,971 文件语料 0 条，故暂按 WrongArgFormat 拒绝。
     */
    private fun extractLabelParams(rest: List<Token>, labelName: String, lineNo: Int): List<String> {
        if (rest.isEmpty()) {
            // 无括号形参（`@F`）：两个旁路表必须清空，否则会沿用**上一个** @行 的形参项
            // （位置绑定读 `paramRefs`/`paramDefaults`，串台就是拿 A 的定义去绑 B 的实参）。
            labelParamRefs = emptyList()
            labelParamDefaults = emptyList()
            return emptyList()
        }
        val first = rest[0]
        if (!(first is Token.Symbol && (first.char == '(' || first.char == ','))) {
            throw StmtParseException(lineNo, 0, labelName, "函数形参格式错误（WrongArgFormat）")
        }
        labelParamRefs = extractLabelParamRefs(rest, labelName, lineNo)
        return labelParamRefs.map { it.substringBefore(':') }
    }

    /** 最近一次 [parseLabelLine] 的形参项原文（含 `:n` 下标）——见 [FuncLabelStmt.paramRefs]。 */
    private var labelParamRefs: List<String> = emptyList()

    /** 最近一次 [parseLabelLine] 的逐位默认值 —— 见 [FuncLabelStmt.paramDefaults]。 */
    private var labelParamDefaults: List<ParamDefault?> = emptyList()

    /**
     * 形参项（`@F(ARG, ARG:1)` → `[ARG, ARG:1]`）。
     *
     * 原版 `TopLabel.Arg[i]` 是 `VariableTerm`，其 arguments 决定写回哪个元素
     * （spec: VariableTerm.cs:108-129、ProcessState.cs:443-450）；逐位绑定要它。
     * 冒号下标在**装载期**必须是常量（spec: ErbLoader.cs:691-693），本遍把每段折成
     * 规范化十进制文本写回（[constSubscripts]）；非字面量立即抛 [StmtParseException]，
     * 不再出现 `OUT:1+1` → `OUT:1`、`OUT:-1` → `OUT:` 的错值。
     *
     * 同一遍顺带产出**逐位默认值**（[ParamDefault]，写进 [labelParamDefaults]）：
     * 原版在 `ErbLoader.cs:646-760` 把每个形参项的 `= 默认值` 解成 `SingleTerm` 存进
     * `TopLabel.Def[i]`，调用点省略实参时用它顶替（spec: CalledFunction.cs:167）。
     * 本遍按同一「顶层 `=` 之前是变量项、之后是默认值项」的口径切分。
     */
    private fun extractLabelParamRefs(rest: List<Token>, labelName: String, lineNo: Int): List<String> {
        val segments = splitLabelParamSegments(rest)
        val refs = ArrayList<String>(segments.size)
        val defaults = ArrayList<ParamDefault?>(segments.size)
        for (seg in segments) {
            if (seg.isEmpty()) {
                throw StmtParseException(lineNo, 0, labelName, "函数形参不能省略（CannotOmitFuncArg）")
            }
            // 形参头 = 顶层 '=' 之前的部分（默认值不参与变量判定）
            var depth = 0
            var eqIndex = -1
            val head = ArrayList<Token>(seg.size)
            for ((i, t) in seg.withIndex()) {
                if (depth == 0 && t is Token.Operator && t.code == LexOperatorCode.Assignment) {
                    eqIndex = i
                    break
                }
                head.add(t)
                if (t is Token.Symbol) {
                    when (t.char) {
                        '(', '[' -> depth++
                        ')', ']' -> depth--
                    }
                }
            }
            val id = head.firstOrNull() as? Token.Identifier
                ?: throw StmtParseException(
                    lineNo, 0, labelName, "函数形参只能是可赋值变量（ArgCanOnlyAssignableVar）",
                )
            // 变量名之后只允许 ':' 下标；其它运算符/符号都不是 VariableTerm
            // （先落局部变量：List.get() 是方法调用，对其结果做 is 检查拿不到智能转换）
            val second: Token? = if (head.size > 1) head[1] else null
            if (second != null && !(second is Token.Symbol && second.char == ':')) {
                throw StmtParseException(
                    lineNo, 0, labelName,
                    "函数形参 '${id.code}' 不是变量引用（ArgCanOnlyAssignableVar）",
                )
            }
            val info = resolver.resolveVariable(id.code, null)
                ?: throw StmtParseException(lineNo, 0, labelName, "未定义的标识符 '${id.code}'")
            if (info.isConst) {
                throw StmtParseException(
                    lineNo, 0, labelName,
                    "函数形参 '${id.code}' 是常量（ArgCanOnlyAssignableVar）",
                )
            }
            val sb = StringBuilder(id.code)
            for (value in constSubscripts(head, labelName, lineNo)) sb.append(':').append(value)
            refs.add(sb.toString())
            defaults.add(
                if (eqIndex < 0) null
                else parseLabelDefault(seg.subList(eqIndex + 1, seg.size), lineNo, info.operandType),
            )
        }
        labelParamDefaults = defaults
        return refs
    }

    /**
     * 一个形参默认值的解析（spec: ErbLoader.cs:646-760 的 `list2[i * 2 + 1]` 一侧）。
     *
     * 只收**字面量**常量（整型/十六进制/字符串，含一元 `-`/`+`/`~` 折叠）：
     * 原版要求 `Restructure` 后是 `SingleTerm`（:764-767 `ArgCanOnlyConst`），本遍对
     * 常量名（如 `C_YELLOW`）与任意表达式**不注入也不报错**——返回 `expr = null`，
     * 由装载装置按 [ParamDefault.raw] 登记（省略位保持既有「不写」行为，spec: ProcessState.cs:437）。
     *
     * `= ` 后无表达式的形态在原版是 `NullTerm`，按形参类型折成 `0` / `""`
     * （spec: ErbLoader.cs:717-726）。
     *
     * 解析失败不抛：默认值不是语句语义的一部分（原版是 Warn+置错标签），
     * 抛异常会把整条 @行 判成解析失败、整函数退化成空体桩 —— 代价远大于不补位。
     */
    private fun parseLabelDefault(tokens: List<Token>, lineNo: Int, paramType: OperandType): ParamDefault {
        if (tokens.isEmpty()) {
            val zero: Expr = if (paramType == OperandType.STR) StrLiteral("") else IntLiteral(0L)
            return ParamDefault(zero, "")
        }
        val raw = tokenText(tokens)
        val parsed = try {
            ExprParser(toExprTokens(tokens), resolver, config).reduceExpressionTerm(TermEndWith.EoL)
        } catch (_: Exception) {
            return ParamDefault(null, raw)
        }
        val folded = parsed?.let(::foldLiteralDefault) ?: return ParamDefault(null, raw)
        // 类型必须与形参一致（spec: ErbLoader.cs:770-773 NotMatchTypeArgAndInitialValue）。
        // 不一致就不注入：补进去只会让运行期按位绑定跳过该位，反而不如保持「省略即不写」。
        if (folded.operandType != paramType) return ParamDefault(null, raw)
        return ParamDefault(folded, raw)
    }

    /**
     * 字面量默认值的常量折叠（口径 = 原版 `Restructure` 把 `-1` 折成 `SingleLongTerm(-1)`，
     * spec: ErbLoader.cs:746-752）。
     *
     * 返回 null = 不是本遍支持的字面量常量（调用方登记 [ParamDefault.raw]，不注入）。
     */
    private fun foldLiteralDefault(e: Expr): Expr? = when (e) {
        is IntLiteral, is StrLiteral -> e
        is Unary -> {
            val v = (e.operand as? IntLiteral)?.value
            when {
                v == null -> null
                e.op == OperatorCode.PLUS -> e.operand
                e.op == OperatorCode.MINUS -> IntLiteral(-v)
                e.op == OperatorCode.BIT_NOT -> IntLiteral(v.inv())
                else -> null
            }
        }
        else -> null
    }

    /**
     * 形参项的**完整**原文（`:` 之后到形参项结束，含全部下标 token）。
     *
     * 旧实现只取 `:` 后的**一个** token（`OUT:1+1` → `1`、`OUT:-1` → 空），
     * 既把错值写进容器段 8，也让运行期 `foldParamSubscripts` 无从判断。
     * 本遍只把它用于诊断：真正写进 `paramRefs` 的是 [constSubscripts] 折出的常量值。
     *
     * 词法层的 `Token.Operator.text` 是 C# 枚举名（`Plus`/`Minus`），这里经
     * [OperatorCode.fromCSharpName] 反查源码符号，诊断里才看得到 `1+1` 而不是 `1Plus1`。
     */
    private fun tokenText(tokens: List<Token>): String = tokens.joinToString("") { t ->
        if (t is Token.Operator) {
            val op = OperatorCode.fromCSharpName(t.code.name)
            if (op == null) t.code.name else OperatorCode.toOperatorString(op).ifEmpty { t.code.name }
        } else {
            t.text
        }
    }

    /**
     * 形参项 `NAME[:常量[:常量…]]` 的冒号下标逐段取常量（CMP-85A 生产者侧）。
     *
     * ## 原版口径
     *
     * `ErbLoader.parseLabel` 逐位把形参项 `Restructure` 成 `VariableTerm`：
     * 不是 `VariableTerm` ⇒ `ArgCanOnlyAssignableVar`（`ErbLoader.cs:680-684`）；
     * `!isAllConst` ⇒ `ArgSubscriptOnlyConst`（:691-693 → :774-776），两者都
     * `Warn(level 2, isError:true)`（:768-770）。`isAllConst` 的判据是**构造期**
     * 每个下标实参都已是 `SingleLongTerm`（`VariableTerm.cs:28-46`）：所以裸十进制
     * 字面量算常量，而 `-1`（一元节点）、`C_INDEX`（`VariableTerm` 节点）都不算。
     *
     * ## 本移植的判定
     *
     * 用现有 [ExprParser] 归约每个下标段，要求结果是 [IntLiteral]：
     *
     * - 十进制/十六进制字面量、`(1)`、`+1` 通过（原版括号与一元 `+` 直接返回内层
     *   `SingleLongTerm`，`OperatorMethodManager.cs:763-766`）；
     * - `1+1` / `-1` / `X+1` / `C_INDEX` 一律按非 const 拒绝，**不再静默取首 token**。
     *
     * 写回的是规范化十进制文本：容器段 8 与运行期 `foldParamSubscripts` 都按
     * 十进制 `toLongOrNull()` 解析，十六进制原样写会失效。
     *
     * TODO(verify): `C_INDEX` 一类 `#DIM CONST` 名下标语料 0 例；本遍没有常量值表，
     * 不硬做折叠（原版构造期判据同样不把它当 `SingleLongTerm`，故拒绝有据）。
     *
     * @return 每个下标段的十进制常量文本（与段数同长；无下标时为空）
     */
    private fun constSubscripts(head: List<Token>, labelName: String, lineNo: Int): List<String> {
        if (head.size <= 1) return emptyList()
        val item = tokenText(head)
        val out = ArrayList<String>()
        var i = 1 // 调用方已确认 head[1] 是 ':'
        while (i < head.size) {
            val sep = head[i]
            if (!(sep is Token.Symbol && sep.char == ':')) {
                throw StmtParseException(
                    lineNo, 0, labelName,
                    "函数\"@$labelName\"的参数错误:函数形参项 '$item' 不是可赋值变量（ArgCanOnlyAssignableVar）",
                )
            }
            i++
            val seg = ArrayList<Token>()
            var depth = 0
            while (i < head.size) {
                val t = head[i]
                if (t is Token.Symbol) {
                    when (t.char) {
                        '(', '[' -> depth++
                        ')', ']' -> depth--
                        ':' -> if (depth == 0) break
                    }
                }
                seg.add(t)
                i++
            }
            out.add(constSubscript(seg, item, labelName, lineNo))
        }
        return out
    }

    /**
     * 一个下标段的常量值（十进制文本）；非编译期常量 ⇒ [StmtParseException]
     * （文案 = zhs `lang.emuera-zhs.xml:576`，外层模板 `:566`）。
     *
     * 归约失败（未知标识符、字符串下标等）也归到同一错误：这一位本来就无法在装载期
     * 定出元素号，继续放行只会重演「静默写错位」；错误信息带完整形参项原文便于定位。
     */
    private fun constSubscript(
        tokens: List<Token>,
        item: String,
        labelName: String,
        lineNo: Int,
    ): String {
        val folded = try {
            if (tokens.isEmpty()) null
            else ExprParser(toExprTokens(tokens), resolver, config).reduceIntegerTerm(TermEndWith.EoL)
        } catch (_: Exception) {
            null
        }
        return (folded as? IntLiteral)?.value?.toString() ?: throw StmtParseException(
            lineNo, 0, labelName,
            "函数\"@$labelName\"的参数错误:函数定义的参数下标必须指定为一个常量" +
                "（ArgSubscriptOnlyConst）：'$item'",
        )
    }

    /**
     * 按顶层逗号切分形参段；`(` / `[` 内的逗号不切。
     * 括号式丢弃首个 '('，逗号式丢弃首个 ','（spec: ErbLoader.cs:654-671）。
     *
     * 空段只在「逗号后紧跟逗号」时产生（如 `A,,B`）→ `CannotOmitFuncArg`
     * （spec: ExpressionParser.cs:269-272）。**末尾逗号不算空段**：原版在
     * `ReduceArguments` 里遇到 ',' 先 ShiftNext，再回到循环顶撞上 ')' / EOL 直接返回
     * （spec: ExpressionParser.cs:247-252 / 296-299），故 `@F(A,)` 合法。
     */
    private fun splitLabelParamSegments(rest: List<Token>): List<List<Token>> {
        val out = ArrayList<List<Token>>()
        var i = 1 // rest[0] 必为 '(' 或 ','（调用方已校验）
        var depth = 0
        var cur = ArrayList<Token>()
        while (i < rest.size) {
            val t = rest[i]
            if (t is Token.Symbol) {
                when (t.char) {
                    '(', '[' -> {
                        depth++
                        cur.add(t)
                    }
                    ')', ']' -> {
                        if (depth == 0) break // 外层右括号
                        depth--
                        cur.add(t)
                    }
                    ',' -> if (depth == 0) {
                        out.add(cur)
                        cur = ArrayList()
                    } else {
                        cur.add(t)
                    }
                    else -> cur.add(t)
                }
            } else {
                cur.add(t)
            }
            i++
        }
        // 末尾逗号后的空 cur 丢弃（原版容忍 trailing comma）；真正缺失的形参已由
        // 上面的「逗号后紧接逗号」产生空段并在调用方报错。
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * `#` 预处理行的语句层形态。spec: LogicalLineParser.cs:20-307。
     * 语义校验（重复声明、尺寸范围、事件函数限制等）属声明层，此处只做语法切分。
     */
    fun parseSharpLine(content: String, lineNo: Int): Statement {
        val (keyword, tokens) = try {
            lexer.parseSharpLine(content, lineNo, "<line>")
        } catch (e: LexError) {
            throw StmtParseException(lineNo, 0, "", e.message ?: "# 行无法解释")
        }
        val kw = keyword.uppercase()
        val values = mutableListOf<Long>()
        var name: String? = null
        when (kw) {
            "LOCALSIZE", "LOCALSSIZE" -> {
                val term = ExprParser(tokens.map { it.toExprToken() }, resolver, config)
                    .reduceIntegerTerm(TermEndWith.EoL)
                // #LOCALSIZE 允许常量表达式（如 `#LOCALSIZE 人物数量上限`），此处只取字面量
                values.add((term as? IntLiteral)?.value ?: 0L)
            }
            "DIM", "DIMS" -> {
                // 变量名 = 修饰符序列之后的首个标识符。原版把整行交给
                // `UserDefinedVariableData.Create`，其关键字循环先吞掉
                // CONST/REF/DYNAMIC/STATIC/GLOBAL/SAVEDATA/CHARADATA
                // （spec: UserDefinedVariableData.cs:44-58），之后读到的才是 `Name`
                // （spec: UserDefinedVariableData.cs:60-70）。旧实现直接取「首个标识符」，
                // 于是 `#DIMS CONST STR_RANK10, 10 = …` 的 name 变成 "CONST"。
                val id = tokens.asSequence()
                    .filterIsInstance<Token.Identifier>()
                    .firstOrNull { it.code.uppercase() !in DIM_KEYWORDS }
                name = id?.code
                // CORE-06：用户变量名不得占用系统保留名 —— 原版在声明层调
                // `IdentifierDictionary.CheckUserVarName`（IdentifierDictionary.cs:258-297，
                // 私有变量走 :340-390 同一分类），命中即抛 CodeEE（warnLevel = 2）。
                // 四类判据的单一真源都在本仓注册表：Reserved 名单、BuiltinSymbols.methods
                // （SystemMethod）、BuiltinSymbols.systemVariableNames（SystemVariable；
                // 不是 variables —— 见其 KDoc 的 FILE/FUNCTION/LINE 误拒面）、
                // FunctionCode.BY_NAME（SystemInstrument；SET/METHOD 伪码不在表内）。
                id?.code?.let { reservedVarMessage(it) }?.let {
                    throw StmtParseException(lineNo, 0, "", it)
                }
                for (t in tokens) if (t is Token.Integer) values.add(t.value)
            }
            else -> {}
        }
        return SharpStmt(keyword, values, name, kw == "DIMS", tokens, lineNo, content)
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private companion object {
        /** `#DIM` 修饰符关键字 —— 原版 `UserDefinedVariableData.Create` 的关键字循环
         * （spec: UserDefinedVariableData.cs:54-57，7 个 `text2.Equals(...)` 分支：
         * CONST :60 / REF :89 / DYNAMIC :119 / STATIC :145 / GLOBAL :171 / SAVEDATA :200 /
         * CHARADATA :229）；循环之后的首个标识符才是 `Name`（:262-263）。 */
        val DIM_KEYWORDS: Set<String> = setOf(
            "CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA",
        )

        /**
         * `DefinedNameType.Reserved` 的 15 个名字（IdentifierDictionary.cs:141-155）。
         * 分类优先级依原版 `nameDic` 的插入序：Reserved 先于 SystemMethod/SystemVariable/
         * SystemInstrument（后三者分别是 :170 `Add`、:174/:178 `TryAdd`、:182 `TryAdd`）。
         */
        val RESERVED_VAR_NAMES: Set<String> = setOf(
            "IS", "TO", "INT", "STR", "REFFUNC", "STATIC", "DYNAMIC", "GLOBAL",
            "PRIVATE", "SAVEDATA", "CHARADATA", "REF", "__DEBUG__", "__SKIP__", "_",
        )

        /**
         * `#DIM` 名撞系统保留名时的 CodeEE 文案（CORE-06）；null = 合法。
         *
         * 文案取 `lang.emuera-zhs.xml`：:872 VarContainsOtherThanUnderline（词法层已挡）、
         * :873 VarConflictReservedWord、:874 VarNameAlreadyUsedInternalInstruction、
         * :875 VarNameAlreadyUsedInternalVariable。
         */
        fun reservedVarMessage(rawName: String): String? {
            val name = rawName.uppercase()
            return when {
                name in RESERVED_VAR_NAMES -> "变量名\"$rawName\"为Emuera的保留字"
                name in BuiltinSymbols.methods || name in FunctionCode.BY_NAME ->
                    "变量名\"$rawName\"为Emuera所使用的变量或者命令名"
                name in BuiltinSymbols.systemVariableNames ->
                    "变量名\"$rawName\"为Emuera所使用的变量名"
                else -> null
            }
        }

        /** PRINTDATA 家族（数据节）。spec: FunctionIdentifier.cs:3902-3910 */
        val PRINT_DATA_CODES: Set<FunctionCode> = setOf(
            FunctionCode.PRINTDATA, FunctionCode.PRINTDATAL, FunctionCode.PRINTDATAW,
            FunctionCode.PRINTDATAK, FunctionCode.PRINTDATAKL, FunctionCode.PRINTDATAKW,
            FunctionCode.PRINTDATAD, FunctionCode.PRINTDATADL, FunctionCode.PRINTDATADW,
        )

        /** 无条件跳转类。spec: FunctionIdentifier.cs:3962-3990 注册段 */
        val JUMP_CODES: Set<FunctionCode> = setOf(
            FunctionCode.JUMP, FunctionCode.TRYJUMP, FunctionCode.JUMPFORM, FunctionCode.TRYJUMPFORM,
            FunctionCode.TRYCJUMP, FunctionCode.TRYCJUMPFORM,
            FunctionCode.GOTO, FunctionCode.TRYGOTO, FunctionCode.GOTOFORM, FunctionCode.TRYGOTOFORM,
            FunctionCode.TRYCGOTO, FunctionCode.TRYCGOTOFORM,
        )

        /** 函数跳转类。 */
        val CALL_CODES: Set<FunctionCode> = setOf(
            FunctionCode.CALL, FunctionCode.TRYCALL, FunctionCode.CALLFORM, FunctionCode.TRYCALLFORM,
            FunctionCode.TRYCCALL, FunctionCode.TRYCCALLFORM,
        )

        /** 无参流程控制（BREAK/CONTINUE/RESTART/QUIT 等）。 */
        val FLOW_ONLY_CODES: Set<FunctionCode> = setOf(
            FunctionCode.BREAK, FunctionCode.CONTINUE, FunctionCode.RESTART,
            FunctionCode.QUIT, FunctionCode.QUIT_AND_RESTART, FunctionCode.FORCE_QUIT,
            FunctionCode.FORCE_QUIT_AND_RESTART, FunctionCode.STOPCALLTRAIN,
        )
    }
}
