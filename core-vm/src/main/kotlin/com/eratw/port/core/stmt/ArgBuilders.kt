package com.eratw.port.core.stmt

import com.eratw.port.core.expr.ArgsEndWith
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.TermEndWith
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.reduceBinaryTerm
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith
import com.eratw.port.core.lexer.LangKey
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.StrEndWith
import com.eratw.port.core.lexer.Token
import com.eratw.port.core.lexer.instructionPrefix

/**
 * 语句参数构造器 —— 移植自 ArgumentParser 的 50 个 ArgumentBuilder
 * （spec: ArgumentParser.cs:19-2560；注册表 :2604-2677）。
 *
 * 与原版的差异（结构层省略，语义不变）：
 * - 省略 `Restructure(exm)`（常量折叠，属装载期优化，不影响 AST 形态）。
 * - 变量/函数/引用符号不在此绑定对象，只保留名字（M2 符号层负责绑定）。
 * - level 1 的告警进 [ArgCtx.warn]；level 2 的告警一律抛 [StmtParseException]（纪律：不静默吞错）。
 */
object ArgBuilders {

    fun create(ctx: ArgCtx): Argument = try {
        dispatch(ctx)
    } catch (e: StmtParseException) {
        throw e
    } catch (e: Exception) {
        throw StmtParseException(ctx.line, -1, ctx.statementName, e.message ?: e::class.simpleName ?: "未知错误")
    }

    // -----------------------------------------------------------------------
    // 分派
    // -----------------------------------------------------------------------

    private fun dispatch(ctx: ArgCtx): Argument = when (ctx.argTypeOverride ?: ctx.code.argType) {
        ArgType.VOID -> void(ctx)
        ArgType.STR, ArgType.KEYWORD -> str(ctx, nullable = false)
        ArgType.STR_NULLABLE -> str(ctx, nullable = true)
        ArgType.FORM_STR -> formStr(ctx, nullable = false)
        ArgType.FORM_STR_NULLABLE -> formStr(ctx, nullable = true)
        ArgType.STR_EXPRESSION -> strExpression(ctx, nullable = false)
        ArgType.STR_EXPRESSION_NULLABLE -> strExpression(ctx, nullable = true)
        ArgType.EXPRESSION -> expression(ctx, nullable = false)
        ArgType.EXPRESSION_NULLABLE -> expression(ctx, nullable = true)
        ArgType.INT_EXPRESSION -> intExpression(ctx, nullable = false)
        ArgType.INT_EXPRESSION_NULLABLE -> intExpression(ctx, nullable = true)
        ArgType.INT_ANY -> intAny(ctx)
        ArgType.FORM_STR_ANY -> formStrAny(ctx)
        ArgType.EXPRESSIONS -> expressions(ctx, listOf(ArgValueType.INT), 0)
        ArgType.CASE -> case(ctx)
        ArgType.METHOD -> method(ctx)
        ArgType.PRINT -> throw StmtParseException(ctx.line, -1, ctx.statementName, "PRINT 后缀未解析")
        ArgType.SP_PRINTV -> printV(ctx)
        ArgType.SP_SET, ArgType.SP_SET_ARRAY, ArgType.SP_SETS, ArgType.SP_SETS_ARRAY -> set(ctx)
        ArgType.SP_TIMES -> times(ctx)
        ArgType.STR_DOUBLE -> strDouble(ctx)
        ArgType.SP_BAR -> bar(ctx)
        ArgType.SP_SWAP, ArgType.SP_COPYCHARA -> swap(ctx, nullable = ctx.code.argType == ArgType.SP_COPYCHARA)
        ArgType.SP_VAR -> varsize(ctx)
        ArgType.SP_SAVEDATA -> saveData(ctx)
        ArgType.SP_INPUT, ArgType.SP_ONEINPUT -> spInput(ctx)
        ArgType.SP_INPUTS, ArgType.SP_ONEINPUTS -> spInputs(ctx)
        ArgType.SP_TINPUT -> tInput(ctx, strDef = false)
        ArgType.SP_TINPUTS -> tInput(ctx, strDef = true)
        ArgType.SP_SORTCHARA -> sortChara(ctx)
        ArgType.SP_SORTARRAY -> sortArray(ctx)
        ArgType.SP_CALL, ArgType.SP_CALLFORM -> call(ctx, callf = false, form = ctx.code.argType == ArgType.SP_CALLFORM)
        ArgType.SP_CALLF, ArgType.SP_CALLFORMF ->
            call(ctx, callf = true, form = ctx.code.argType == ArgType.SP_CALLFORMF)
        ArgType.SP_CALLCSHARP -> callSharp(ctx)
        ArgType.SP_FOR_NEXT -> forNext(ctx)
        ArgType.SP_POWER -> power(ctx)
        ArgType.SP_SWAPVAR -> swapVar(ctx)
        ArgType.VAR_INT -> varInt(ctx)
        ArgType.VAR_STR -> varStr(ctx)
        ArgType.BIT_ARG -> bitArg(ctx)
        ArgType.SP_VAR_SET -> varSet(ctx)
        ArgType.SP_CVAR_SET -> cVarSet(ctx)
        ArgType.SP_BUTTON -> button(ctx)
        ArgType.SP_COLOR -> color(ctx)
        ArgType.SP_SPLIT -> split(ctx)
        ArgType.SP_HTMLSPLIT -> htmlSplit(ctx)
        ArgType.SP_GETINT -> getInt(ctx)
        ArgType.SP_CONTROL_ARRAY -> controlArray(ctx)
        ArgType.SP_SHIFT_ARRAY -> shiftArray(ctx)
        ArgType.SP_COPY_ARRAY -> copyArray(ctx)
        ArgType.SP_SAVEVAR -> saveVar(ctx)
        ArgType.SP_SAVECHARA -> saveChara(ctx)
        ArgType.SP_REF -> ref(ctx, byName = false)
        ArgType.SP_REFBYNAME -> ref(ctx, byName = true)
        ArgType.SP_PRINT_IMG -> printImg(ctx)
        ArgType.SP_PRINT_RECT -> printShape(ctx, maxArg = 4)
        ArgType.SP_PRINT_SPACE -> printShape(ctx, maxArg = 1)
        ArgType.SP_HTML_PRINT -> htmlPrint(ctx)
        ArgType.SP_DT_COLUMN_OPTIONS -> dtColumnOptions(ctx)
        ArgType.INT_ASIGN, ArgType.STR_ASIGN ->
            throw StmtParseException(ctx.line, -1, ctx.statementName, "VARI/VARS 参数应由语句层手工解析")
        ArgType.__NULL__ ->
            throw StmtParseException(ctx.line, -1, ctx.statementName, "该语句没有参数构造器")
    }

    // -----------------------------------------------------------------------
    // 通用工具
    // -----------------------------------------------------------------------

    /**
     * 语句参数构造失败 —— 统一带 ArgumentBuilder 族前缀，逐字对应
     * `ArgumentBuilder.cs:30-40` 的 `warn`：`line.Function.Name + Lang.Error.Instruction.Text + mes`
     * （即「语句名 + 命令: + 文案」）。`assignwarn`（:19-28）与 `ParserMediator.Warn` 不加前缀，
     * 故 [BlockChecker] 那侧不走这里。
     *
     * 已知冗余：[StmtParseException] 自身还会拼 `语句名: `（`ParseSupport.kt:35`，本波封存面），
     * 于是最终 message 里语句名出现两次；去重属 ParseSupport 接线，已写进交付回执。
     */
    private fun fail(ctx: ArgCtx, msg: String): Nothing =
        throw StmtParseException(ctx.line, -1, ctx.statementName, argPrefix(ctx) + msg)

    /** 官方键版 [fail]：使用者面取 zhs（口径见 [LangKey] 类头第 2 条）。 */
    private fun fail(ctx: ArgCtx, key: LangKey, vararg args: Any?): Nothing =
        throw StmtParseException(ctx.line, -1, ctx.statementName, argPrefix(ctx) + key.render(*args))

    /** 统一前缀版的 [ArgCtx.warn]。 */
    private fun warn(ctx: ArgCtx, msg: String) = ctx.warn(argPrefix(ctx) + msg)

    /** 官方键版 [warn]（zhs）。 */
    private fun warn(ctx: ArgCtx, key: LangKey, vararg args: Any?) =
        ctx.warn(argPrefix(ctx) + key.render(*args))

    /** `ArgumentBuilder.cs:32` 的前缀：`line.Function.Name + Lang.Error.Instruction.Text`。 */
    private fun argPrefix(ctx: ArgCtx): String = instructionPrefix(ctx.statementName)

    /**
     * `FunctionIdentifier.IsExtended()`（spec: FunctionIdentifier.cs:4235-4238）：flag 的
     * EXTENDED 位。INT_EXPRESSION 的「省略实参」告警文案按它分流（spec: ArgumentParser.cs:1199-1207）。
     */
    private fun isExtended(code: FunctionCode): Boolean = (code.flag and 2) == 2

    /**
     * 常量折叠的**窄版**（只服务 INT_EXPRESSION 的范围告警）。
     *
     * 原版 `Restructure` 会把 `-1` 折成负常量（spec: OperatorMethodManager 的常量折叠），本仓
     * 省略了 `Restructure`（见类头）⇒ [constInt] 只认裸 [IntLiteral]，`FONTSTYLE -1` 这种
     * 「一元负号 + 字面量」形态看不见。这里只对「字面量取负」做一次浅折叠，**不写回**
     * `Argument.isConst/constInt`（那会改 `.erbc` 落盘字节），仅用于范围告警判据。
     */
    private fun Expr?.foldedConstInt(): Long? = when (this) {
        is IntLiteral -> value
        is com.eratw.port.core.expr.Unary ->
            if (op == OperatorCode.MINUS) (operand as? IntLiteral)?.let { -it.value } else null
        else -> null
    }

    // -----------------------------------------------------------------------
    // 官方 zhs 文案（`emuera-zhs.xml`）就地照抄
    //
    // 本波 [LangKey] 键表未收这几条（键表是另一文件面），按纪律「使用者面取 zhs、照抄」把官方
    // 译文原样硬编码；行号见每条的 zhs xml 引用。均为 level 0/1 告警：**不置错、语句照执行**。
    // -----------------------------------------------------------------------

    /** `emuera-zhs.xml:478`；spec: ArgumentParser.cs:1219-1222（REPEAT 常量次数 <= 0）。 */
    private const val REPEAT_COUNT_NONPOSITIVE = "检测到REPEAT重复的次数为0或以下(eramaker会抛出一个错误)"

    /** `emuera-zhs.xml:479`；spec: ArgumentParser.cs:1235-1241（CLEARLINE 常量 <= 0）。 */
    private const val CLEARLINE_NONPOSITIVE = "在参数中传递了小于等于0的值(这一行什么都不会做)"

    /** `emuera-zhs.xml:480`；spec: ArgumentParser.cs:1242-1245（FONTSTYLE 常量 < 0）。 */
    private const val FONTSTYLE_NEGATIVE = "在参数中传递了一个负值(结果未定义)"

    /** `emuera-zhs.xml:489`；spec: ArgumentParser.cs:1809-1812（VARSET 目标非一维数组时的第 3 参之后）。 */
    private const val VAR_SET_IGNORE_THIRD = "第3参数后面除了一维数组以外的参数都会被忽略"

    /** `emuera-zhs.xml:492`；spec: ArgumentParser.cs:1876-1879（CVARSET 目标非一维数组时的第 4 参之后）。 */
    private const val CVAR_SET_IGNORE_FOURTH = "第4参数后面除了一维数组以外的参数都会被忽略"

    /** `emuera-zhs.xml:502`；spec: ArgumentParser.cs:2214-2223（SAVEVAR 重复指定同一变量）。 */
    private const val DUPLICATE_VAR_SAVE = "试图多次保存变量"

    /**
     * 参数个数超限：原版为 level 1 告警并截断。spec: ArgumentBuilder.cs:54-59。
     *
     * 文案不是「多余的将被忽略」而是原版拼接式：`ArgumentBuilder.warn`
     * （ArgumentBuilder.cs:30-40）把 `mes` 改写成
     * `line.Function.Name + Lang.Error.Instruction.Text + mes`，即「语句名 + `命令:` + 原因」；
     * `Instruction` = "命令:"（Lang.cs:1918，emuera-zhs.xml:434），
     * `TooManyArg` = "参数过多"（Lang.cs:1930，emuera-zhs.xml:437）。
     * 故 `INPUTS "ANS", 0, 1` 在 PC 上打的是 `INPUTS命令:参数过多`
     * （装载告警基准 `.workbuddy/golden-pc-loadwarnings.txt:4`，同款 BINPUTS 见 `:5`；
     * 语句侧触发点 ArgumentParser.cs:1157 与 SP_INPUTS 的 `:1155-1163`）。
     * 本侧统一走 [LangKey.TooManyArg] + [argPrefix]（CMP-60 渲染层），与基准逐字一致。
     */
    private fun tooMany(ctx: ArgCtx) = warn(ctx, LangKey.TooManyArg)

    /**
     * checkArgumentType 的类型校验（spec: ArgumentBuilder.cs:42-90）。
     * [types] 中 null 表示不限类型，[ArgValueType.VOID] 表示不检查（原版 typeof(void)）。
     *
     * **null 实参与 typeof(void) 槽位是两件事**（CMP-47）：原版先判
     * `arguments[i] == null`（:67-74），只要槽位类型非 null（**含 typeof(void)**）就
     * `CanNotRecognizeArg`（level 2）；只有实参非 null 时才用
     * `type != typeof(void) && type != GetOperandType()` 判类型（:75-79）。
     * 旧实现把两者并成 `t == null || t == VOID` 同一放行分支，于是 `VARSET LOCAL, , 1, 5`
     * 静默补默认值、`PRINTBUTTON` 静默退成 key=0 —— 全是「原版该行报错」的静默降级。
     */
    private fun checkTypes(ctx: ArgCtx, list: List<Expr?>, types: List<ArgValueType?>, minArg: Int, argAny: Boolean) {
        if (list.size < minArg || (list.size < types.size && minArg < 0)) fail(ctx, LangKey.NotEnoughArguments)
        val n = if (list.size > types.size && !argAny) {
            tooMany(ctx)
            types.size
        } else list.size
        for (i in 0 until n) {
            if (!argAny && i < types.size && types[i] == null) continue
            val t = if (!argAny || i < types.size) types.getOrNull(i) else types.lastOrNull()
            val e = list[i]
            if (e == null) {
                // spec: ArgumentBuilder.cs:67-73 —— type 非 null 即「无法识别」，typeof(void) 不豁免。
                if (t != null) fail(ctx, LangKey.CanNotRecognizeArg, i + 1)
            } else if (t == null || (t != ArgValueType.VOID && ArgValueType.of(e.operandType) != t)) {
                // spec: ArgumentBuilder.cs:75-79 —— type == null 时 `type != typeof(void)` 成立，
                // 接着 `type != GetOperandType()` 恒成立 ⇒ 同样报「类型不正确」。
                fail(ctx, LangKey.IncorrectArg, i + 1)
            }
        }
    }

    /** 可变参数位必须是变量且非 const。spec: ArgumentBuilder.cs:92-105 */
    private fun changeable(ctx: ArgCtx, list: List<Expr?>, index1: Int): VarRef {
        val e = list.getOrNull(index1 - 1) ?: fail(ctx, "第${index1}个参数无法识别")
        val vr = e.asVarRef() ?: fail(ctx, LangKey.ArgIsNotVariable, index1)
        if (vr.isConst) fail(ctx, "第${index1}个参数是常量")
        return vr
    }

    private fun defaultVar(name: String): VarRef =
        VarRef(name, listOf(IntLiteral(0L)), operandType = OperandType.INT)

    private fun defaultStrVar(name: String): VarRef =
        VarRef(name, listOf(IntLiteral(0L)), operandType = OperandType.STR)

    /**
     * 参数原文 —— **原样返回，不裁剪**。
     *
     * 原版链路：ParseLine 只 ShiftNext **1 个**分隔字符（spec: LogicalLineParser.cs:513-522），
     * 之后 STR 构造器对剩余流直接 `Substring()`（spec: ArgumentParser.cs:446-462），
     * FORM 构造器 `AnalyseFormattedString(trim:false)` 不裁文本段
     * （spec: ArgumentParser.cs:504 + LexicalAnalyzer.cs:1223-1227 的 trim 只作用于 trim:true）。
     * 因此 `PRINTL   x` 输出保留前导两个空格、`DATA   x` 原文同样是 "  x"。
     * 此前这里 trimStart(' ','\t') 会吞掉任意多个前导空白，属于行为偏离，已修正。
     */
    private fun rawText(ctx: ArgCtx): String = ctx.argText

    // -----------------------------------------------------------------------
    // 简单形态
    // -----------------------------------------------------------------------

    /**
     * spec: ArgumentParser.cs:423-435 VOID。
     * 余空判定走 SkipWhiteSpace（空白集含全角空格、';' 视为行尾注释吞掉，
     * spec: LexicalAnalyzer.cs:724-752）：`RESETCOLOR ; 注释` 与 `RESETCOLOR 　`
     * 都不告警，`RESETCOLOR x` 告警。此前用 trimStart(' ','\t') 判空，
     * 尾注释与全角空格会误报。
     */
    private fun void(ctx: ArgCtx): Argument {
        val st = CharStream(ctx.argText)
        ctx.lexer.skipWhiteSpace(st)
        if (!st.eos) warn(ctx, LangKey.ArgIsNotRequired)
        return VoidArgument()
    }

    /** spec: ArgumentParser.cs:437-477 STR / STR_NULLABLE —— :461 Substring() 原文，不 trim */
    private fun str(ctx: ArgCtx, nullable: Boolean): Argument {
        val text = rawText(ctx)
        if (text.isEmpty() && !nullable) fail(ctx, LangKey.MissingArg)
        val arg = ExpressionArgument(StrLiteral(text))
        arg.isConst = true
        arg.constStr = text
        return arg
    }

    /**
     * spec: ArgumentParser.cs:479-514 FORM_STR / FORM_STR_NULLABLE —— :504
     * `AnalyseFormattedString(..., trim: false)`，前导空格原样保留。
     *
     * **常量表单折成 [StrLiteral]**（CMP-51）：原版把这步交给
     * `ExpressionParser.ToStrFormTerm`（spec: ArgumentParser.cs:504），而它对
     * `StrForm.IsConst`（`strs.Length == 1`，即无内嵌 SubWord，spec: StrForm.cs:118）
     * 返回 `SingleStrTerm`、非常量才返回 `StrFormTerm`（spec: ExpressionParser.cs:321-329）；
     * 随后构造器按 `aExpression is SingleTerm` 写 `ConstStr`（spec: ArgumentParser.cs:507-511）。
     *
     * 旧实现对纯文本表单也造 [FormStringTerm]，于是 `DATAFORM` 的 `DataStmt.text`
     * （`LineParser.rawTextOfArg` 只认 `term.constStr()`）恒空 —— 该字段会随
     * `ErbcWriter.kt:1146` 全量写进语句串池。折成 StrLiteral 后求值路径等价
     * （纯文本 FormStringTerm 的 `getStrValue` 就是拼接文本段），但常量身份与 C# 对齐。
     */
    private fun formStr(ctx: ArgCtx, nullable: Boolean): Argument {
        val text = rawText(ctx)
        if (text.isEmpty()) {
            if (!nullable) fail(ctx, LangKey.MissingArg)
            val arg = ExpressionArgument(StrLiteral(""))
            arg.isConst = true
            arg.constStr = ""
            return arg
        }
        val form = analyseFormToEol(
            ctx.lexer, CharStream(text), ctx.line, trim = false,
            ignoreTripleSymbol = ctx.lexerConfig.systemIgnoreTripleSymbol,
        )
        if (form.subWords.isEmpty()) {
            // spec: ExpressionParser.cs:323-327 —— IsConst 的 StrForm → SingleStrTerm(strForm.GetString())
            val literal = StrLiteral(form.strs.joinToString(""))
            val arg = ExpressionArgument(literal)
            arg.isConst = true
            arg.constStr = literal.value
            return arg
        }
        val term = FormStringTerm.fromLexerStrForm(form, ctx.resolver, ctx.config, ctx.line, 0)
        return ExpressionArgument(term)
    }

    /** spec: ArgumentParser.cs:1313-1354 STR_EXPRESSION(_NULLABLE) */
    private fun strExpression(ctx: ArgCtx, nullable: Boolean): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.STR), if (nullable) 0 else -1, argAny = false)
        if (list.isEmpty()) {
            val arg = ExpressionArgument(StrLiteral(""))
            arg.isConst = true
            arg.constStr = ""
            return arg
        }
        return ExpressionArgument(list[0])
    }

    /** spec: ArgumentParser.cs:1356-1387 EXPRESSION(_NULLABLE) —— 类型数组为 typeof(void)，即不做类型检查 */
    private fun expression(ctx: ArgCtx, nullable: Boolean): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.VOID), if (nullable) 0 else -1, argAny = false)
        if (list.isEmpty()) {
            val arg = ExpressionArgument(null)
            arg.isConst = true
            return arg
        }
        return ExpressionArgument(list[0])
    }

    /** spec: ArgumentParser.cs:1175-1249 INT_EXPRESSION(_NULLABLE) */
    private fun intExpression(ctx: ArgCtx, nullable: Boolean): Argument {
        val list = popTerms(ctx)
        // 两个构造器**恒** minArg=0（spec: ArgumentParser.cs:1179-1184）：省略实参不是「参数不足」，
        // 而是补 SingleLongTerm(0)，非 nullable 时只发 level 1 告警（spec: ArgumentParser.cs:1194-1208）。
        // `CLEARLINE` / `IF` / `LOOP` 的空参因此合法（FunctionCode.kt:141 / :193 / :207）。
        checkTypes(ctx, list, listOf(ArgValueType.INT), 0, argAny = false)
        val term: Expr = if (list.isEmpty()) {
            if (!nullable) {
                // 原版为 level 1 告警（补 0）。spec: ArgumentParser.cs:1194-1207 ——
                // 文案按 `IsExtended()` 分流：EXTENDED 位在位的走 Arg1，否则走兼容档 Arg2。
                warn(ctx, if (isExtended(ctx.code)) LangKey.OmittedArg1 else LangKey.OmittedArg2)
            }
            IntLiteral(0L)
        } else {
            list[0]!!
        }
        // REPEAT <n> 在 INT_EXPRESSION 构造器内被改写成 FOR 参数：FOR COUNT, 0, n, 1
        // ——**不减一**（续循环判定 `end > cnt` 为排他上界，n 恰为迭代次数，0..n-1）。
        // spec: ArgumentParser.cs:1213-1229
        //   （new SpForNextArgment(var, SingleLongTerm(0), aExpression, SingleLongTerm(1))；
        //     续循环条件 spec: FunctionIdentifier.cs:3401-3403）
        if (ctx.code == FunctionCode.REPEAT) {
            // spec: ArgumentParser.cs:1219-1222 —— 常量次数 <= 0 时 level 0 告警（eramaker 会报错），
            // 构造照旧（补 SingleLongTerm(0)，仍产 FOR COUNT,0,n,1）。

            term.foldedConstInt()?.let { if (it <= 0L) warn(ctx, REPEAT_COUNT_NONPOSITIVE) }
            // TODO(verify)：原版此处另有 `IdentifierDictionary.getVarTokenIsForbid("COUNT")`
            // → CodeEE(CanNotUseRepeat)（spec: ArgumentParser.cs:1215-1218）。本仓符号层无
            // 「禁用 token」概念，语料 0 命中；登记待接线（不在此文件面）。
            // 契约 R4（CONTRACT-M2-E2E §5）：原"n-1 含端补偿"是 compileFor 用含端 `_LE`
            // 判定时的补丁，判定已改排他（ErbProgramCompiler.compileFor 的 _LT/_GT），补偿同步撤销。
            // 常量 n 直接折叠（保持 _SET_INT 快路径），非常量沿用原表达式（终值只求值一次，
            // 留在 VM 匿名临时槽，契约 C1/R4）
            val end: Expr = term.constInt()?.let { IntLiteral(it) } ?: term
            return SpForNextArgument(defaultVar("COUNT"), IntLiteral(0L), end, IntLiteral(1L))
        }
        // spec: ArgumentParser.cs:1235-1245 —— 常量实参的范围告警（level 1，不置错、照执行）：
        // CLEARLINE 常量 <= 0（ArgLessThan0）、FONTSTYLE 常量 < 0（ArgIsNegativeValue）。
        term.foldedConstInt()?.let { v ->
            when {
                ctx.code == FunctionCode.CLEARLINE && v <= 0L -> warn(ctx, CLEARLINE_NONPOSITIVE)
                ctx.code == FunctionCode.FONTSTYLE && v < 0L -> warn(ctx, FONTSTYLE_NEGATIVE)
            }
        }
        val arg = ExpressionArgument(term)
        term.constInt()?.let {
            arg.isConst = true
            arg.constInt = it
        }
        return arg
    }

    /** spec: ArgumentParser.cs:1287-1345 INT_ANY */
    private fun intAny(ctx: ArgCtx): Argument {
        val list = popTerms(ctx).toMutableList()
        checkTypes(ctx, list, listOf(ArgValueType.INT), minArg = 0, argAny = true)
        val arg = ExpressionArrayArgument(list)
        if (list.isEmpty()) {
            if (ctx.code == FunctionCode.RETURN) {
                list.add(IntLiteral(0L))
                arg.isConst = true
                arg.constInt = 0L
                return arg
            }
            fail(ctx, LangKey.MissingArg)
        }
        if (list.size == 1) {
            list[0].constInt()?.let {
                arg.isConst = true
                arg.constInt = it
            }
        }
        return arg
    }

    /**
     * spec: ArgumentParser.cs:381-421 FORM_STR_ANY —— 逐项按 FORM 串解析（逗号分隔）。
     * 使用方仅 SETBGIMAGE / REMOVEBGIMAGE（spec: FunctionIdentifier.cs:1523 / 1549）。
     * 前导只跳**半角空格**（SkipHalfSpace，spec: LexicalAnalyzer.cs:692-701，不含 tab/全角），
     * tab 与全角空格会作为 FORM 文本保留：`SETBGIMAGE \tx` 的首参是 "\tx"。
     * 逗号后耗尽为 level 1 告警 MissingArgAfterComma 并截断（spec: ArgumentParser.cs:412-417）。
     * RETURNFORM 无参补 "0" 的分支只服务旧调用方（RETURNFORM 本体系 FORM_STR，
     * spec: FunctionIdentifier.cs:3217，不会进本构造器），保留以兼容既有单测。
     */
    private fun formStrAny(ctx: ArgCtx): Argument {
        val list = mutableListOf<Expr?>()
        val st = CharStream(ctx.argText)
        ctx.lexer.skipHalfSpace(st)
        if (st.eos) {
            if (ctx.code == FunctionCode.RETURNFORM) {
                list.add(StrLiteral("0"))
                val arg = ExpressionArrayArgument(list)
                arg.isConst = true
                arg.constInt = 0L
                return arg
            }
            fail(ctx, LangKey.MissingArg)
        }
        while (true) {
            val form = ctx.lexer.analyseFormattedString(st, FormStrEndWith.Comma, trim = false, ctx.line)
            list.add(FormStringTerm.fromLexerStrForm(form, ctx.resolver, ctx.config, ctx.line, 0))
            if (st.eos) break
            st.shiftNext() // 跳过 ','
            if (st.eos) break
            ctx.lexer.skipHalfSpace(st)
            if (st.eos) {
                warn(ctx, LangKey.MissingArgAfterComma)
                break
            }
        }
        return ExpressionArrayArgument(list)
    }

    /** spec: ArgumentParser.cs:2587-2606 Expressions_ArgumentBuilder（GetNormalArgumentBuilder("I", 0)） */
    private fun expressions(ctx: ArgCtx, types: List<ArgValueType?>, minArg: Int): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, types, minArg, argAny = false)
        return ExpressionsArgument(types, list)
    }

    /** spec: ArgumentParser.cs:890-909 METHOD —— 原版绑定 FunctionMethod 并检查参数类型（M2 符号层） */
    private fun method(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        val type = ctx.resolver?.resolveFunction(ctx.statementName) ?: OperandType.INT
        val call = com.eratw.port.core.expr.FuncCall(ctx.statementName, list, type)
        return MethodArgument(call)
    }

    /** spec: ArgumentParser.cs:325-343 SP_PRINTV —— 词法标志 AllowSingleQuotationStr（'…' 视为字符串） */
    private fun printV(ctx: ArgCtx): Argument {
        val tokens = toExprTokens(
            if (ctx.argText.isEmpty()) emptyList()
            else ctx.lexer.analyse(
                CharStream(ctx.argText), LexEndWith.EoL,
                LexAnalyzeFlag.ANALYZE_PRINT_V, ctx.line,
            ),
        )
        val list = com.eratw.port.core.expr.ExprParser(tokens, ctx.resolver, ctx.config)
            .reduceArguments(ArgsEndWith.EoL)
        for ((i, e) in list.withIndex()) {
            if (e == null) fail(ctx, "第${i + 1}个参数不能省略")
        }
        return SpPrintVArgument(list.map { it!! })
    }

    /**
     * spec: ArgumentParser.cs:345-395 SP_TIMES。
     *
     * 第二参**必须按字符流读实数前缀**（CMP-46）：原版在逗号之后
     * `SkipWhiteSpace`（`;` 当行尾注释吞掉，spec: LexicalAnalyzer.cs:734-752）→
     * `ReadDouble`（只吃「正负号 + 数字/'.' + 可选 e/E 指数」，
     * spec: LexicalAnalyzer.cs:232-266）→ `SkipWhiteSpace` → 余字非空才 level-1 `TooManyArg`
     * （spec: ArgumentParser.cs:340-355）。
     * 旧实现取「逗号后到行尾」整段 `.trim().toDoubleOrNull()`，行内注释一出现就判失败并
     * **按 0.0 结算**（钱/经验清零级后果）。
     */
    private fun times(ctx: ArgCtx): Argument {
        val tokens = lexWords(ctx)
        val commaIdx = topLevelCommaIndex(tokens)
        if (commaIdx < 0 || commaIdx == tokens.size - 1) fail(ctx, "参数不足")
        val varTokens = tokens.subList(0, commaIdx)
        val varExpr = com.eratw.port.core.expr.ExprParser(
            toExprTokens(varTokens), ctx.resolver, ctx.config,
        ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "第1个参数格式错误")
        val vr = varExpr.asVarRef() ?: fail(ctx, "第1个参数不是变量")
        if (vr.operandType == OperandType.STR) fail(ctx, "第1个参数是字符串变量")
        if (vr.isConst) fail(ctx, "第1个参数是常量")
        // spec: ArgumentParser.cs:334-335 —— 越过 ',' 后 EOS 即 NotEnoughArguments。
        val comma = tokens[commaIdx] as Token.Symbol
        val st = CharStream(ctx.argText)
        st.jump(comma.col + 1)
        if (st.eos) fail(ctx, LangKey.NotEnoughArguments)
        val d = try {
            ctx.lexer.skipWhiteSpace(st)
            val value = readDouble(st)
            // spec: ArgumentParser.cs:345-349 —— 数字之后的余字是 level 1 TooManyArg，值照留。
            ctx.lexer.skipWhiteSpace(st)
            if (!st.eos) warn(ctx, LangKey.TooManyArg)
            value
        } catch (e: NumberFormatException) {
            // spec: ArgumentParser.cs:351-355 —— 读不出实数才告警并补 0.0。
            // 官方键 ArgIsNotRealNumber（Lang.cs:1962 / zhs:445）未入本波键表（L11 登记未接线）。
            warn(ctx, "第2个参数不是实数")
            0.0
        }
        return SpTimesArgument(vr, d)
    }

    /**
     * spec: LexicalAnalyzer.cs:232-266 ReadDouble —— 从流当前位置读实数前缀。
     *
     * 只吃「可选正负号 + 数字/'.'」，随后可选 `e/E` 指数段（指数允许 `-`，与原版一致）；
     * 读到非法字符即停，返回 `double.Parse(子串)`。空串/畸形（如 `"."`、`"-"`）抛
     * [NumberFormatException]，由调用方按原版 catch 兜成 0.0。
     */
    private fun readDouble(st: CharStream): Double {
        val start = st.currentPosition
        if (st.current == '-' || st.current == '+') st.shiftNext()
        while (!st.eos) {
            val c = st.current
            if (!c.isDigit() && c != '.') break
            st.shiftNext()
        }
        if (st.current == 'e' || st.current == 'E') {
            st.shiftNext()
            if (st.current == '-') st.shiftNext()
            while (!st.eos) {
                val c = st.current
                if (!c.isDigit() && c != '.') break
                st.shiftNext()
            }
        }
        return st.substring(start, st.currentPosition - start).toDouble()
    }

    /**
     * 实参分割点：第一个**括号深度 0** 的逗号 token。
     *
     * 原版 `Analyse(..., LexEndWith.Comma)` 只在 `nestBracketS == 0` 时于逗号处停
     * （spec: LexicalAnalyzer.cs:836-843），圆括号里的逗号属表达式内部
     * （`TIMES F(1,2), 1.5`）；方括号层级原版不参与该判定，此处同口径只看圆括号。
     */
    private fun topLevelCommaIndex(tokens: List<Token>): Int {
        var paren = 0
        for ((i, t) in tokens.withIndex()) {
            if (t !is Token.Symbol) continue
            when (t.char) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                ',' -> if (paren == 0) return i
                else -> {}
            }
        }
        return -1
    }

    /** spec: ArgumentParser.cs:2534-2570 STR_DOUBLE */
    private fun strDouble(ctx: ArgCtx): Argument {
        val tokens = lexWords(ctx)
        val commaIdx = tokens.indexOfFirst { it is Token.Symbol && it.char == ',' }
        if (commaIdx < 0) {
            val term = com.eratw.port.core.expr.ExprParser(
                toExprTokens(tokens), ctx.resolver, ctx.config,
            ).reduceExpressionTerm(TermEndWith.EoL)
            return StrDoubleArgument(term, 0.0)
        }
        val term = com.eratw.port.core.expr.ExprParser(
            toExprTokens(tokens.subList(0, commaIdx)), ctx.resolver, ctx.config,
        ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "参数格式错误")
        val tail = rawTail(ctx.argText, tokens, commaIdx + 1).trim()
        val d = tail.toDoubleOrNull() ?: 0.0
        return StrDoubleArgument(term, d)
    }

    /** spec: ArgumentParser.cs:1389-1410 SP_BAR */
    private fun bar(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.INT, ArgValueType.INT, ArgValueType.INT), -1, argAny = false)
        if (list.size < 3) fail(ctx, "参数不足")
        return SpBarArgument(list[0], list[1], list[2])
    }

    /** spec: ArgumentParser.cs:1412-1443 SP_SWAP / SP_COPYCHARA（nullable → minArg 1） */
    private fun swap(ctx: ArgCtx, nullable: Boolean): Argument {
        val list = popTerms(ctx).toMutableList()
        checkTypes(ctx, list, listOf(ArgValueType.INT, ArgValueType.INT), if (nullable) 1 else -1, argAny = false)
        if (list.size == 1) list.add(null)
        if (list.size < 2) fail(ctx, "参数不足")
        return SpSwapCharaArgument(list[0], list[1])
    }

    /** spec: ArgumentParser.cs:516-546 SP_VAR（VARSIZE） */
    private fun varsize(ctx: ArgCtx): Argument {
        val tokens = lexWords(ctx)
        val id = tokens.firstOrNull() as? Token.Identifier ?: fail(ctx, "第1个参数无法识别")
        val info = ctx.resolver?.resolveVariable(id.code, null)
        if (info == null) {
            // 取不到 VariableToken 即 level 2（spec: ArgumentParser.cs:528-533）。
            // resolver 为 null（无符号层，仅独立单测）时无从判定存在性，保留旧告警放行。
            if (ctx.resolver == null) {
                // 移植侧兜底（原版无此分支）：符号层缺席时无从判定存在性。
                warn(ctx, "目标变量 '${id.code}' 未在符号表中")
            } else {
                // 原版 ArgumentParser.cs:529-533 报 ArgIsNotVariable。
                fail(ctx, LangKey.ArgIsNotVariable, 1)
            }
        } else if (info.dimension == 0 || id.code.equals("RAND", ignoreCase = true)) {
            // 非数组变量与 RAND（伪随机寄存器，无「长度」语义）同属 level 2。
            // spec: ArgumentParser.cs:534-538
            fail(ctx, "第1个参数不是数组变量")
        }
        if (tokens.size > 1) warn(ctx, LangKey.ExtraCharacterAfterArg)
        return SpVarsizeArgument(id.code)
    }

    /** spec: ArgumentParser.cs:1445-1470 SP_SAVEDATA */
    private fun saveData(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.INT, ArgValueType.STR), -1, argAny = false)
        if (list.size < 2) fail(ctx, "参数不足")
        return SpSaveDataArgument(list[0], list[1])
    }

    /** spec: ArgumentParser.cs:2386-2425 SP_INPUT */
    private fun spInput(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        // C# 逐项判 `GetOperandType() != typeof(long)`，**null（省略位）不判**，超长也不告警
        // （spec: ArgumentParser.cs:2402-2410 只取前 3 项）。所以 `INPUT ,0` 合法。
        for ((i, e) in list.withIndex()) {
            if (e != null && e.operandType != OperandType.INT) fail(ctx, "第${i + 1}个参数的类型不正确")
        }
        return when {
            list.isEmpty() -> SpInputsArgument(null, null, null)
            list.size == 1 -> SpInputsArgument(list[0], null, null)
            list.size == 2 -> SpInputsArgument(list[0], list[1], null)
            else -> SpInputsArgument(list[0], list[1], list[2])
        }
    }

    /**
     * spec: ArgumentParser.cs:1149-1173 SP_INPUTS（首参为 FORM 串）。
     * 原版对参数原文**不做任何空白跳过**、`trim:false`（:1141-1147）：
     * `INPUTS   x` 的默认串保留前导空格（"  x"）。
     */
    private fun spInputs(ctx: ArgCtx): Argument {
        val text = ctx.argText
        if (text.isEmpty()) return SpInputsArgument(null, null, null)
        val st = CharStream(text)
        val form = ctx.lexer.analyseFormattedString(st, FormStrEndWith.Comma, trim = false, ctx.line)
        val def = FormStringTerm.fromLexerStrForm(form, ctx.resolver, ctx.config, ctx.line, 0)
        if (st.eos) return SpInputsArgument(def, null, null)
        st.shiftNext()
        val list = com.eratw.port.core.expr.ExprParser(
            toExprTokens(ctx.lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, ctx.line)),
            ctx.resolver, ctx.config,
        ).reduceArguments(ArgsEndWith.EoL)
        if (!st.eos || list.size > 1) tooMany(ctx)
        if (list.isEmpty()) return SpInputsArgument(def, null, null)
        val first = list[0]
        if (first == null || !first.isInteger()) {
            warn(ctx, LangKey.IgnoreArgBecauseNotInt)
            return SpInputsArgument(def, null, null)
        }
        return if (list.size == 1) SpInputsArgument(def, first, null)
        else SpInputsArgument(def, first, list[1])
    }

    /** spec: ArgumentParser.cs:1472-1556 SP_TINPUT / SP_TINPUTS */
    private fun tInput(ctx: ArgCtx, strDef: Boolean): Argument {
        val list = popTerms(ctx)
        val types = if (strDef) {
            listOf(ArgValueType.INT, ArgValueType.STR, ArgValueType.INT, ArgValueType.STR,
                ArgValueType.INT, ArgValueType.INT)
        } else {
            listOf(ArgValueType.INT, ArgValueType.INT, ArgValueType.INT, ArgValueType.STR,
                ArgValueType.INT, ArgValueType.INT)
        }
        checkTypes(ctx, list, types, 2, argAny = false)
        if (list.size < 2) fail(ctx, "参数不足")
        return SpTInputsArgument(
            list[0], list[1],
            list.getOrNull(2), list.getOrNull(3), list.getOrNull(4), list.getOrNull(5),
        )
    }

    /** spec: ArgumentParser.cs:548-620 SP_SORTCHARA：`SORTCHARA [<角色变量>][, FORWARD|BACK]` */
    private fun sortChara(ctx: ArgCtx): Argument {
        var order = SortOrder.ASCENDING
        var key = defaultVar("NO")
        val tokens = lexWords(ctx)
        if (tokens.isEmpty()) return SpSortcharaArgument(key, order)
        val first = tokens[0]
        if (first is Token.Identifier && isForwardBack(first.code)) {
            if (first.code.equals("BACK", ignoreCase = true)) order = SortOrder.DESENDING
            if (tokens.size > 1) tooMany(ctx)
            return SpSortcharaArgument(key, order)
        }
        // 原版靠 WordCollection 的游标：`ReduceExpressionTerm(TermEndWith.Comma)` 吃掉排序键，
        // 再 `ShiftNext()` 跳过逗号，随后读方向词（ArgumentParser.cs:574-620）。词法层会把
        // `CFLAG:0:7` 拆成 [CFLAG][:][0][:][7]，故这里必须先定位顶层逗号再切分。
        val commaIdx = findTopLevelComma(tokens, 0)
        val term = com.eratw.port.core.expr.ExprParser(
            toExprTokens(tokens.subList(0, commaIdx)), ctx.resolver, ctx.config,
        ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "第1个参数格式错误")
        key = term.asVarRef() ?: fail(ctx, "第1个参数不是变量")
        // 排序键必须是角色数据变量（级别 2）；无下标形态（`SORTCHARA CFLAG`）同样受限。
        // spec: ArgumentParser.cs:588-592（`!variableTerm.Identifier.IsCharacterData` → ArgIsNotCharaVar）
        val keyInfo = ctx.resolver?.resolveVariable(key.name, key.subKey)
        if (keyInfo != null && !keyInfo.isCharacterData) fail(ctx, "第1个参数不是角色变量")
        if (commaIdx >= tokens.size) return SpSortcharaArgument(key, order)
        val dir = tokens.getOrNull(commaIdx + 1)
        if (dir is Token.Identifier && isForwardBack(dir.code)) {
            if (dir.code.equals("BACK", ignoreCase = true)) order = SortOrder.DESENDING
            if (commaIdx + 2 < tokens.size) tooMany(ctx)
        } else fail(ctx, "第2个参数格式错误")
        return SpSortcharaArgument(key, order)
    }

    private fun isForwardBack(s: String) = s.equals("FORWARD", ignoreCase = true) || s.equals("BACK", ignoreCase = true)

    /** spec: ArgumentParser.cs:697-800 SP_SORTARRAY */
    private fun sortArray(ctx: ArgCtx): Argument {
        var order = SortOrder.ASCENDING
        val tokens = lexWords(ctx)
        if (tokens.isEmpty()) fail(ctx, "参数格式错误")
        var pos = 0
        val firstEnd = findTopLevelComma(tokens, pos)
        val term = com.eratw.port.core.expr.ExprParser(
            toExprTokens(tokens.subList(0, firstEnd)), ctx.resolver, ctx.config,
        ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "参数格式错误")
        val dest = term.asVarRef() ?: fail(ctx, "第1个参数不是变量")
        if (dest.isConst) fail(ctx, "第1个参数是常量")
        // 目标必须是一维数组（原版判 Identifier.IsArray1D，级别 2）。
        // spec: ArgumentParser.cs:645-649
        val destInfo = ctx.resolver?.resolveVariable(dest.name, dest.subKey)
        if (destInfo != null && destInfo.dimension != 1) fail(ctx, "第1个参数不是一维数组变量")
        var num1: Expr? = IntLiteral(0L)
        var num2: Expr? = null
        // spec: ArgumentParser.cs:650-703 —— 只有目标、没有顶层逗号时（`ARRAYSORT X`）原版照常构造：
        // `ReduceExpressionTerm(TermEndWith.Comma)` 停在行尾 → `ShiftNext()` 无动作 →
        // `Current as IdentifierWord` 得 null → 跳过方向/数字两支 → 直接 return
        // （order 保持 ASCENDING、num1 保持 SingleLongTerm(0)、num2 为 null）。
        // 旧实现在此处 fail「参数格式错误」，把原版合法语句判死。
        if (firstEnd >= tokens.size) return SpArraySortArgument(dest, order, num1, num2)
        pos = firstEnd + 1
        val second = tokens.getOrNull(pos)
        if (second is Token.Identifier && isForwardBack(second.code)) {
            if (second.code.equals("BACK", ignoreCase = true)) order = SortOrder.DESENDING
            pos++
            if (pos < tokens.size && tokens[pos] is Token.Symbol && (tokens[pos] as Token.Symbol).char == ',') pos++
            if (pos < tokens.size) {
                val e = findTopLevelComma(tokens, pos)
                num1 = com.eratw.port.core.expr.ExprParser(
                    toExprTokens(tokens.subList(pos, e)), ctx.resolver, ctx.config,
                ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "第3个参数无法识别")
                if (!num1.isInteger()) fail(ctx, "第3个参数不是数值")
                pos = e
                if (pos < tokens.size) {
                    pos++
                    val e2 = findTopLevelComma(tokens, pos)
                    num2 = com.eratw.port.core.expr.ExprParser(
                        toExprTokens(tokens.subList(pos, e2)), ctx.resolver, ctx.config,
                    ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "第4个参数无法识别")
                    if (!num2.isInteger()) fail(ctx, "第4个参数不是数值")
                    pos = e2
                    if (pos < tokens.size) tooMany(ctx)
                }
            }
        } else if (second is Token.Identifier) {
            fail(ctx, "第2个参数必须是 FORWARD 或 BACK")
        }
        return SpArraySortArgument(dest, order, num1, num2)
    }

    private fun findTopLevelComma(tokens: List<Token>, from: Int): Int {
        var paren = 0
        var bracket = 0
        for (i in from until tokens.size) {
            val t = tokens[i]
            if (t is Token.Symbol) {
                when (t.char) {
                    '(' -> paren++
                    ')' -> paren--
                    '[' -> bracket++
                    ']' -> bracket--
                    ',' -> if (paren == 0 && bracket == 0) return i
                }
            }
        }
        return tokens.size
    }

    // -----------------------------------------------------------------------
    // 跳转/调用
    // -----------------------------------------------------------------------

    /** spec: ArgumentParser.cs:802-875 SP_CALL / SP_CALLF / SP_CALLFORM / SP_CALLFORMF */
    private fun call(ctx: ArgCtx, callf: Boolean, form: Boolean): Argument {
        val st = CharStream(ctx.argText)
        ctx.lexer.skipWhiteSpace(st)
        val rawNameTerm: Expr = if (form) {
            if (st.current == '"') {
                // 带引号的 FORM 名字（`GOTOFORM "TAIL"`）：原版经词法 token 层，引号作为
                // StringLiteral 定界符被消费（LexicalAnalyzer.cs），字面内容再作为 FORM 串
                // 解析（{expr}/%% 插值在引号内仍有效）。直接对原文跑 AnalyseFormattedString
                // 会把外层引号当普通文本吞进 Text 段（endWith != DoubleQuotation），
                // 导致按名查表拿到 `"TAIL"` 查不到 $TAIL。
                st.shiftNext()   // 越过开引号（readString 把 '"' 当结束符）
                val inner = ctx.lexer.readString(st, StrEndWith.DoubleQuotation, ctx.line)
                if (st.current == '"') st.shiftNext()   // 越过闭引号，否则后续 analyse 再遇 '"' 报未闭合
                val f = ctx.lexer.analyseFormattedString(
                    CharStream(inner), FormStrEndWith.LeftParenthesis_Bracket_Comma_Semicolon,
                    trim = false, ctx.line,
                )
                FormStringTerm.fromLexerStrForm(f, ctx.resolver, ctx.config, ctx.line, 0)
            } else {
                val f = ctx.lexer.analyseFormattedString(
                    st, FormStrEndWith.LeftParenthesis_Bracket_Comma_Semicolon, trim = true, ctx.line,
                )
                FormStringTerm.fromLexerStrForm(f, ctx.resolver, ctx.config, ctx.line, 0)
            }
        } else {
            StrLiteral(
                ctx.lexer.readString(st, StrEndWith.LeftParenthesis_Bracket_Comma_Semicolon, ctx.line).trim(' ', '\t'),
            )
        }
        val nameTerm: Expr = foldTryConstFormName(ctx, rawNameTerm)
        val terminator = if (st.eos) CharStream.END_OF_STRING else st.current
        val tokens = toExprTokens(ctx.lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, ctx.line))
        var pos = if (tokens.isEmpty()) 0 else 1 // 原版 ShiftNext 跳过终结符
        var subNames: List<Expr?> = emptyList()
        var rowArgs: List<Expr?> = emptyList()
        if (terminator == '[') {
            val r1 = reduceArgs(tokens, pos, ArgsEndWith.RightBracket, ctx)
            subNames = r1.first
            pos = r1.second
            if (pos < tokens.size) {
                if (tokens[pos].kind != ExprTokenKind.LPAREN) pos++
                val r2 = reduceArgs(tokens, pos, ArgsEndWith.RightParenthesis, ctx)
                rowArgs = r2.first
                pos = r2.second
            }
        }
        if (terminator == '(' || terminator == ',') {
            val end = if (terminator == '(') ArgsEndWith.RightParenthesis else ArgsEndWith.EoL
            val r = reduceArgs(tokens, pos, end, ctx)
            rowArgs = r.first
            pos = r.second
        }
        // CMP-50②：原版只在 `(` / `,` 两分支后查尾部杂字（ArgumentParser.cs:735-751 的
        // `if (!wordCollection.EOL) { warn(WrongFormat, 2); return null; }` 落在
        // `current == '(' || current == ','` 块内 —— CALLSHARP 构造器同构，:795 起同段代码）；
        // `[` 分支（:737-745）**没有**这条检查：`CALL X[a](1) 杂字` 在 `]` 后解析完 `(...)`
        // 就收工，尾部词被静默忽略。旧实现把 `[` 一并纳入，比原版更严（命中即毒化整个函数）
        // ⇒ 按原版收窄为仅 `(`。语料 `CALL*` / `CALLSHARP` 的 `](` 形态实测 0 处 ⇒ 字节中性。
        if (terminator == '(') {
            if (pos < tokens.size) fail(ctx, "参数格式错误")
        }
        val arg: Argument = if (callf) SpCallFArgument(nameTerm, subNames, rowArgs)
        else SpCallArgument(nameTerm, subNames, rowArgs)
        nameTerm.constStr()?.let {
            if (it.isEmpty()) fail(ctx, "未指定函数名")
            arg.isConst = true
            arg.constStr = it
        }
        return arg
    }

    /**
     * TRY 族（非 TRYC）FORM 调用（TRYCALLFORM / TRYJUMPFORM）的**纯文本函数名**折叠成
     * 常量名（StrLiteral）。复刻原版解析期 Restructure 的常量折叠：
     * spec: ArgumentParser.cs:726（SP_CALL_ArgumentBuilder 对 FORM 名做
     * `aExpression.Restructure(exm)`，无插值的 StrForm 收敛成 SingleTerm）+
     * :781-789（`IsConst = true; ConstStr = ...`）；装载期 SetJumpTo 据此把常量名绑成
     * CallFunc，运行期 `FunctionIdentifier.cs:3566-3570` 直接取绑定、`:3577` 的 null 判定
     * **先于** `:3590-3597` 的 ConvertArg（实参求值）——目标缺失时 TRY 族 `:3583-3588`
     * 直接 return，实参**一次都不求值**（RAND 不消费、副作用不触发）。
     *
     * 折叠成 StrLiteral 后 SpCallArgument.label 生效，编译器走静态名分支（compileJumpLike）：
     * 目标缺失 ⇒ TRY 族整行不发射，实参求值指令根本不存在；目标存在 ⇒ 与静态 CALL
     * 同一条 `_CALL` 通道（缺省实参补位 padOmittedArgs、REF 位绑定随之按被调签名生效，
     * 与原版装载期 ConvertArg 的相位一致）。
     *
     * **排除面**（逐项给理由）：
     * - CALLFORM / JUMPFORM（非 TRY）：静态分支对缺失目标会登记 `_CALL` 引用并在 build()
     *   抛「未定义的函数」（ErbProgramCompiler.build 的 CallRef 回填），而原版是装载成功、
     *   执行到行才抛（:3577-3581）⇒ 非 TRY 维持动态 `_CALL_BY_NAME` 通道，缺失报错由 VM
     *   在执行期按 :3581 的 zhs 文案逐字抛（core/code/Vm.kt `_CALL_BY_NAME` 非 try 分支）。
     * - TRYCCALLFORM / TRYCJUMPFORM（TRYC）：动态名的两步式发射（`_CALL_BY_NAME` +
     *   `_JUMP_IF_TRUE` + `_JUMP`，契约 R3）已被 ErbProgramCompilerTest 的
     *   tryccallform 两步式发射形态用例按常量名钉死，本路文件面改不了那枚钉 ⇒
     *   TRYC 常量名的先行判名归组长接线（连同动态名求值序一起，见交付回执）。
     * - GOTOFORM 族（$ 标签跳转，编译器按 DYNAMIC_LABEL_CODES 分流）与 FUNC（TRY*LIST 体，
     *   已有 constFuncName 判定）：不在函数调用语义面内。
     *
     * 空名不折（`TRYCALLFORM ""`）：原版此处是解析期 NotSpecifiedFuncName（:785-789），
     * 移植侧维持动态通道的运行期缺失报错，语料 0 处，登记为已知差异。
     */
    private fun foldTryConstFormName(ctx: ArgCtx, term: Expr): Expr {
        if (ctx.code !in TRY_FORM_CALL_CODES) return term
        val t = term as? FormStringTerm ?: return term
        if (t.parts.size != 1 || t.parts[0] !is FormPart.Text) return term
        val name = (t.parts[0] as FormPart.Text).str
        if (name.isEmpty()) return term
        return StrLiteral(name)
    }

    /** 折叠适用的语句面（目标为**函数**的 TRY 族非 TRYC FORM 调用）。 */
    private val TRY_FORM_CALL_CODES = setOf(
        FunctionCode.TRYCALLFORM, FunctionCode.TRYJUMPFORM,
    )

    /** spec: ArgumentParser.cs:877-943 SP_CALLSHARP */
    private fun callSharp(ctx: ArgCtx): Argument {
        val st = CharStream(ctx.argText)
        ctx.lexer.skipWhiteSpace(st)
        val nameTerm: Expr = StrLiteral(
            ctx.lexer.readString(st, StrEndWith.LeftParenthesis_Bracket_Comma_Semicolon, ctx.line).trim(' ', '\t'),
        )
        val terminator = if (st.eos) CharStream.END_OF_STRING else st.current
        val tokens = toExprTokens(ctx.lexer.analyse(st, LexEndWith.EoL, LexAnalyzeFlag.NONE, ctx.line))
        var pos = if (tokens.isEmpty()) 0 else 1
        var subNames: List<Expr?> = emptyList()
        var rowArgs: List<Expr?> = emptyList()
        if (terminator == '[') {
            val r1 = reduceArgs(tokens, pos, ArgsEndWith.RightBracket, ctx)
            subNames = r1.first
            pos = r1.second
            if (pos < tokens.size) {
                if (tokens[pos].kind != ExprTokenKind.LPAREN) pos++
                val r2 = reduceArgs(tokens, pos, ArgsEndWith.RightParenthesis, ctx)
                rowArgs = r2.first
                pos = r2.second
            }
        }
        if (terminator == '(' || terminator == ',') {
            val end = if (terminator == '(') ArgsEndWith.RightParenthesis else ArgsEndWith.EoL
            val r = reduceArgs(tokens, pos, end, ctx)
            rowArgs = r.first
            pos = r.second
        }
        // CMP-50②：原版只在 `(` / `,` 两分支后查尾部杂字（ArgumentParser.cs:735-751 的
        // `if (!wordCollection.EOL) { warn(WrongFormat, 2); return null; }` 落在
        // `current == '(' || current == ','` 块内 —— CALLSHARP 构造器同构，:795 起同段代码）；
        // `[` 分支（:737-745）**没有**这条检查：`CALL X[a](1) 杂字` 在 `]` 后解析完 `(...)`
        // 就收工，尾部词被静默忽略。旧实现把 `[` 一并纳入，比原版更严（命中即毒化整个函数）
        // ⇒ 按原版收窄为仅 `(`。语料 `CALL*` / `CALLSHARP` 的 `](` 形态实测 0 处 ⇒ 字节中性。
        if (terminator == '(') {
            if (pos < tokens.size) fail(ctx, "参数格式错误")
        }
        val arg = SpCallSharpArgument(nameTerm, subNames, rowArgs)
        nameTerm.constStr()?.let {
            if (it.isEmpty()) fail(ctx, "未指定函数名")
            arg.isConst = true
            arg.constStr = it
        }
        return arg
    }

    /** 游标式 ReduceArguments（spec: ExpressionParser.cs:211-304）。返回（参数列表, 新位置） */
    private fun reduceArgs(
        tokens: List<ExprToken>,
        start: Int,
        endWith: ArgsEndWith,
        ctx: ArgCtx,
    ): Pair<List<Expr?>, Int> {
        val list = mutableListOf<Expr?>()
        var pos = start
        val endBits = when (endWith) {
            ArgsEndWith.EoL -> TermEndWith.Comma
            ArgsEndWith.RightParenthesis -> TermEndWith.RightParenthesis_Comma
            ArgsEndWith.RightBracket -> TermEndWith.RightBracket_Comma
        }
        while (true) {
            if (pos >= tokens.size) {
                if (endWith == ArgsEndWith.EoL) return list to pos
                fail(ctx, if (endWith == ArgsEndWith.RightBracket) "未闭合的中括号" else "未闭合的小括号")
            }
            val k = tokens[pos].kind
            if (k == ExprTokenKind.RPAREN) {
                if (endWith == ArgsEndWith.RightParenthesis) return list to pos + 1
                fail(ctx, "不该出现的小括号")
            }
            if (k == ExprTokenKind.RBRACKET) {
                if (endWith == ArgsEndWith.RightBracket) return list to pos + 1
                fail(ctx, "不该出现的中括号")
            }
            val (term, next) = splitTerm(tokens, pos, endBits, ctx)
            list.add(term)
            pos = next
            if (pos >= tokens.size) {
                if (endWith == ArgsEndWith.EoL) return list to pos
                fail(ctx, "参数列表未闭合")
            }
            when (tokens[pos].kind) {
                ExprTokenKind.COMMA -> pos++
                ExprTokenKind.RPAREN ->
                    if (endWith == ArgsEndWith.RightParenthesis) return list to pos + 1
                    else fail(ctx, "不该出现的小括号")
                ExprTokenKind.RBRACKET ->
                    if (endWith == ArgsEndWith.RightBracket) return list to pos + 1
                    else fail(ctx, "不该出现的中括号")
                else -> fail(ctx, "参数格式错误")
            }
        }
    }

    // -----------------------------------------------------------------------
    // 循环/变量写入类
    // -----------------------------------------------------------------------

    /** spec: ArgumentParser.cs:1558-1604 SP_FOR_NEXT */
    private fun forNext(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list,
            listOf(ArgValueType.INT, null, ArgValueType.INT, ArgValueType.INT), 3, argAny = false,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        val cnt = changeable(ctx, list, 1)
        val vrInfo = ctx.resolver?.resolveVariable(cnt.name, cnt.subKey)
        if (vrInfo?.isCharacterData == true) fail(ctx, "第1个参数不能是角色变量")
        val start = list[1] ?: IntLiteral(0L)
        val end = list[2]
        val step = list.getOrNull(3) ?: IntLiteral(1L)
        if (!start.isInteger()) fail(ctx, "第2个参数的类型不正确")
        return SpForNextArgument(cnt, start, end, step)
    }

    /** spec: ArgumentParser.cs:1606-1631 SP_POWER */
    private fun power(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.INT, ArgValueType.INT, ArgValueType.INT), -1, argAny = false)
        if (list.size < 3) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        return SpPowerArgument(dest, list[1], list[2])
    }

    /** spec: ArgumentParser.cs:1633-1666 SP_SWAPVAR */
    private fun swapVar(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.VOID, ArgValueType.VOID), -1, argAny = false,
        )
        if (list.size < 2) fail(ctx, "参数不足")
        val v1 = changeable(ctx, list, 1)
        val v2 = changeable(ctx, list, 2)
        if (v1.operandType != v2.operandType) fail(ctx, "两个参数类型不匹配")
        return SpSwapVarArgument(v1, v2)
    }

    /** spec: ArgumentParser.cs:1668-1693 VAR_INT（PRINTDATA 家族） */
    private fun varInt(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        if (list.isEmpty()) return PrintDataArgument(null)
        checkTypes(ctx, list, listOf(ArgValueType.INT), 0, argAny = false)
        return PrintDataArgument(changeable(ctx, list, 1))
    }

    /** spec: ArgumentParser.cs:1695-1724 VAR_STR（STRDATA） */
    private fun varStr(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        if (list.isEmpty()) return StrDataArgument(defaultStrVar("RESULTS"))
        checkTypes(ctx, list, listOf(ArgValueType.STR), 0, argAny = false)
        return StrDataArgument(changeable(ctx, list, 1))
    }

    /** spec: ArgumentParser.cs:1726-1764 BIT_ARG */
    private fun bitArg(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.INT, ArgValueType.INT), 2, argAny = true)
        if (list.size < 2) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        val bits = list.drop(1).map { it!! }
        for ((i, e) in bits.withIndex()) {
            val v = e.constInt() ?: continue
            if (v < 0 || v > 63) fail(ctx, "第${i + 2}个参数的位序号超出范围($v)")
        }
        return BitArgument(dest, bits)
    }

    /**
     * spec: ArgumentParser.cs:1766-1852 SP_VAR_SET。
     *
     * CMP-47 下游：`list[1]` 的默认值只服务**整参省略**（`VARSET LOCAL`，原版
     * `list.Count > 1 ? list[1] : 默认`，spec: ArgumentParser.cs:1791）；
     * `VARSET LOCAL, , 1, 5` 那种**槽位内省略**在 [checkTypes] 就按
     * `CanNotRecognizeArg` 报「第2个参数无法识别」（spec: ArgumentBuilder.cs:67-73），
     * 走不到这里的默认分支。
     */
    private fun varSet(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list,
            listOf(ArgValueType.VOID, ArgValueType.VOID, ArgValueType.INT, ArgValueType.INT),
            1, argAny = false,
        )
        if (list.isEmpty()) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        if (dest.isConst) fail(ctx, "指定的变量是常量")
        val term = list.getOrNull(1)
            ?: if (dest.operandType == OperandType.STR) StrLiteral("") else IntLiteral(0L)
        val start = list.getOrNull(2)
        val end = list.getOrNull(3)
        // spec: ArgumentParser.cs:1809-1812 —— 给了第 3 参及以后、而目标不是一维数组时 level 1
        // 告警（「第3参数后面除了一维数组以外的参数都会被忽略」）；不置错、语句照执行。
        // `VariableNoArgTerm` 的独立分支（:1792-1800，无角标目标 + 第 3 参 → level 2 判错）
        // 见 TODO(verify)：本仓 VarRef.isNoArg 不是原版 VariableNoArgTerm 的完备代理（chara 2D
        // 全省略在 :548 走 emptyList 而非 noArgRef），贸然按它判错会误伤语料，登记待接线。
        if (list.size >= 3) {
            val destInfo = ctx.resolver?.resolveVariable(dest.name, dest.subKey)
            if (destInfo != null && destInfo.dimension != 1) warn(ctx, VAR_SET_IGNORE_THIRD)
        }
        if (term.operandType != dest.operandType) fail(ctx, "两个参数类型不匹配")
        return SpVarSetArgument(dest, term, start, end)
    }

    /**
     * spec: ArgumentParser.cs:1854-1922 SP_CVAR_SET。
     *
     * CMP-47 下游：`index`/`term` 的默认值只服务整参省略（原版
     * `list.Count <= 1 ? SingleLongTerm(0) : list[1]`，spec: ArgumentParser.cs:1861-1862）；
     * 槽位内省略（`CVARSET CFLAG:0, , , 5`）在 [checkTypes] 即报「第N个参数无法识别」。
     */
    private fun cVarSet(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list,
            listOf(ArgValueType.VOID, ArgValueType.VOID, ArgValueType.VOID,
                ArgValueType.INT, ArgValueType.INT),
            1, argAny = false,
        )
        if (list.isEmpty()) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        val info = ctx.resolver?.resolveVariable(dest.name, dest.subKey)
        if (info != null && !info.isCharacterData) fail(ctx, "第1个参数不是角色变量")
        if (info != null && info.dimension == 2) fail(ctx, "第1个参数不能是二维变量")
        val index = list.getOrNull(1) ?: IntLiteral(0L)
        val term = list.getOrNull(2)
            ?: if (dest.operandType == OperandType.STR) StrLiteral("") else IntLiteral(0L)
        val start = list.getOrNull(3)
        val end = list.getOrNull(4)
        // spec: ArgumentParser.cs:1876-1879 —— 给了第 4 参及以后、而目标不是一维数组时 level 1
        // 告警（「第4参数后面除了一维数组以外的参数都会被忽略」）；不置错、语句照执行。
        // 前面的 `NotDefinedKey`（:1871-1875，第 2 参为字符串字面量且目标是一维角色数据数组、
        // 该键未定义 → level 2 判错）需要 `GlobalStatic.ConstantData.isDefined` 的键表，本仓
        // 符号层未接线，登记 TODO(verify)（语料 CVARSET 下标全是 GETNUM(...) 非常量，0 命中）。
        if (list.size > 3 && info != null && info.dimension != 1) warn(ctx, CVAR_SET_IGNORE_FOURTH)
        if (term.operandType != dest.operandType) fail(ctx, "两个参数类型不匹配")
        return SpCVarSetArgument(dest, index, term, start, end)
    }

    /**
     * spec: ArgumentParser.cs:1924-1935 SP_BUTTON。
     *
     * CMP-47 下游：`list[1]` 不许再是 null（否则执行层静默按 key=0）。
     * 槽位内省略（`PRINTBUTTON "押", ,`）在 [checkTypes] 报「第2个参数无法识别」
     * （spec: ArgumentBuilder.cs:67-73）；尾逗号形态只剩 1 项，命中上面的 NotEnoughArguments。
     */
    private fun button(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.STR, ArgValueType.VOID), -1, argAny = false,
        )
        if (list.size < 2) fail(ctx, "参数不足")
        return SpButtonArgument(list[0], list[1])
    }

    /** spec: ArgumentParser.cs:1937-1982 SP_COLOR */
    private fun color(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.INT, ArgValueType.INT, ArgValueType.INT), 1, argAny = false,
        )
        if (list.size == 2) fail(ctx, "参数个数不正确")
        if (list.size < 3) {
            val arg = SpColorArgument(list[0])
            list[0].constInt()?.let {
                arg.isConst = true
                arg.constInt = it
            }
            return arg
        }
        val arg = SpColorArgument(list[0], list[1], list[2])
        val r = list[0].constInt()
        val g = list[1].constInt()
        val b = list[2].constInt()
        if (r != null && g != null && b != null) {
            arg.isConst = true
            arg.constInt = (r shl 16) + (g shl 8) + b
        }
        return arg
    }

    /** spec: ArgumentParser.cs:1984-2030 SP_SPLIT */
    private fun split(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list,
            listOf(ArgValueType.STR, ArgValueType.STR, ArgValueType.STR, ArgValueType.INT),
            3, argAny = false,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 3)
        val info = ctx.resolver?.resolveVariable(dest.name, dest.subKey)
        if (info != null && info.dimension == 0) fail(ctx, "第3个参数不是数组变量")
        val num = if (list.size >= 4) changeable(ctx, list, 4) else defaultVar("RESULT")
        return SpSplitArgument(list[0], list[1], dest.name, num)
    }

    /** spec: ArgumentParser.cs:2032-2080 SP_HTMLSPLIT */
    private fun htmlSplit(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.STR, ArgValueType.STR, ArgValueType.INT), 1, argAny = false,
        )
        if (list.isEmpty()) fail(ctx, "参数不足")
        var destName = "RESULTS"
        if (list.size >= 2) {
            val v = changeable(ctx, list, 2)
            destName = v.name
            val info = ctx.resolver?.resolveVariable(v.name, v.subKey)
            // CMP-50③：原版判 `!variableToken.IsArray1D`（spec: ArgumentParser.cs:2025-2029，
            // 必须是**一维**数组且非角色数据）⇒ 维度 0/2/3 一律不合法。旧实现只排除维度 0，
            // 二维以上数组被放行（更松）。语料唯一使用点 COMMON_HTML.ERB:100 的第二参是
            // `LOCALS`（一维局部字符串数组）⇒ 收窄对语料零影响。
            if (info != null && (info.dimension != 1 || info.isCharacterData)) {
                fail(ctx, "第2个参数必须是非角色数组变量")
            }
        }
        val num = if (list.size >= 3) changeable(ctx, list, 3) else defaultVar("RESULT")
        return SpHtmlSplitArgument(list[0], destName, num)
    }

    /** spec: ArgumentParser.cs:2082-2110 SP_GETINT */
    private fun getInt(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        if (list.isEmpty()) return SpGetIntArgument(defaultVar("RESULT"))
        checkTypes(ctx, list, listOf(ArgValueType.INT), 0, argAny = false)
        return SpGetIntArgument(changeable(ctx, list, 1))
    }

    /** spec: ArgumentParser.cs:2112-2134 SP_CONTROL_ARRAY */
    private fun controlArray(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.VOID, ArgValueType.INT, ArgValueType.INT), -1, argAny = false,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        return SpArrayControlArgument(dest, list[1], list[2])
    }

    /** spec: ArgumentParser.cs:2136-2180 SP_SHIFT_ARRAY */
    private fun shiftArray(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list,
            listOf(ArgValueType.VOID, ArgValueType.INT, ArgValueType.VOID,
                ArgValueType.INT, ArgValueType.INT),
            3, argAny = false,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        val dest = changeable(ctx, list, 1)
        val info = ctx.resolver?.resolveVariable(dest.name, dest.subKey)
        if (info != null && info.dimension != 1) fail(ctx, "第1个参数不是一维数组变量")
        if (ctx.code == FunctionCode.ARRAYSHIFT && list[0]?.operandType != list[2]?.operandType) {
            fail(ctx, "第1个与第3个参数类型不匹配")
        }
        val num1 = list.getOrNull(3) ?: IntLiteral(0L)
        val num2 = list.getOrNull(4)
        return SpArrayShiftArgument(dest, list[1], list[2], num1, num2)
    }

    /** spec: ArgumentParser.cs:2420-2500 SP_COPY_ARRAY */
    private fun copyArray(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(ctx, list, listOf(ArgValueType.STR, ArgValueType.STR), 2, argAny = false)
        if (list.size < 2) fail(ctx, "参数不足")
        // 变量名常量校验（符号层未知时放宽，只告警）。spec: ArgumentParser.cs:2443-2490
        val n1 = list[0].constStr()
        val n2 = list[1].constStr()
        if (n1 != null) checkArrayName(ctx, n1, 1)
        if (n2 != null) checkArrayName(ctx, n2, 2)
        if (n1 != null && n2 != null) {
            val i1 = ctx.resolver?.resolveVariable(n1, null)
            val i2 = ctx.resolver?.resolveVariable(n2, null)
            if (i1 != null && i2 != null) {
                if (i1.dimension != i2.dimension) fail(ctx, "两个参数的数组维度不同")
                if (i1.operandType != i2.operandType) fail(ctx, "两个参数的数组类型不同")
            }
        }
        return SpCopyArrayArgument(list[0], list[1])
    }

    private fun checkArrayName(ctx: ArgCtx, name: String, index: Int) {
        val info = ctx.resolver?.resolveVariable(name, null)
        if (info == null) {
            // 移植侧兜底（原版无此分支）：符号层缺席时无从判定变量性质。
            warn(ctx, "第${index}个参数的变量 '$name' 未在符号表中")
            return
        }
        if (info.dimension == 0) fail(ctx, "第${index}个参数不是数组变量")
        if (info.isCharacterData) fail(ctx, "第${index}个参数是角色变量")
    }

    /** spec: ArgumentParser.cs:2182-2285 SP_SAVEVAR */
    private fun saveVar(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.STR, ArgValueType.STR, ArgValueType.VOID),
            3, argAny = true,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        val names = mutableListOf<String>()
        for (i in 2 until list.size) {
            if (list[i] == null) fail(ctx, "第${i + 1}个参数不能省略")
            val vr = changeable(ctx, list, i + 1)
            val info = ctx.resolver?.resolveVariable(vr.name, vr.subKey)
            if (info != null) {
                if (info.isCharacterData) fail(ctx, "不能保存角色变量 '${vr.name}'")
                if (info.isConst) fail(ctx, "不能保存常量变量 '${vr.name}'")
            }
            // spec: ArgumentParser.cs:2214-2223 —— 重复指定同一变量是 **level 1** 告警，但紧接着
            // `return null`；SetArgumentTo 对「argument==null 且 line.IsError==false」走 error()
            // （spec: ArgumentParser.cs:2708-2714）⇒ 该行最终判错、不执行。旧实现只 warn 不停，
            // 与原版「拒绝该行」分叉。
            if (names.contains(vr.name)) fail(ctx, "$DUPLICATE_VAR_SAVE\"${vr.name}\"")
            names.add(vr.name)
        }
        return SpSaveVarArgument(list[0], list[1], names)
    }

    /** spec: ArgumentParser.cs:2287-2333 SP_SAVECHARA */
    private fun saveChara(ctx: ArgCtx): Argument {
        val list = popTerms(ctx)
        checkTypes(
            ctx, list, listOf(ArgValueType.STR, ArgValueType.STR, ArgValueType.INT),
            3, argAny = true,
        )
        if (list.size < 3) fail(ctx, "参数不足")
        for (i in 2 until list.size) {
            val v = list[i]?.constInt() ?: continue
            if (v < 0) fail(ctx, "角色号不能为负数")
            if (v > Int.MAX_VALUE) fail(ctx, "角色号超出范围")
            for (j in i + 1 until list.size) {
                if (list[j]?.constInt() == v) fail(ctx, "角色号 $v 重复指定")
            }
        }
        return ExpressionArrayArgument(list)
    }

    /** spec: ArgumentParser.cs:2335-2400 SP_REF / SP_REFBYNAME */
    private fun ref(ctx: ArgCtx, byName: Boolean): Argument {
        val tokens = lexWords(ctx)
        if (tokens.size < 2) fail(ctx, "参数格式错误")
        val first = tokens[0] as? Token.Identifier ?: fail(ctx, "参数格式错误")
        if (!(tokens[1] is Token.Symbol && (tokens[1] as Token.Symbol).char == ',')) {
            fail(ctx, "参数格式错误")
        }
        if (byName) {
            val term = com.eratw.port.core.expr.ExprParser(
                toExprTokens(tokens.subList(2, tokens.size)), ctx.resolver, ctx.config,
            ).reduceExpressionTerm(TermEndWith.EoL) ?: fail(ctx, "参数格式错误")
            if (term.isInteger()) fail(ctx, "参数格式错误")
            return RefArgument(first.code, srcTerm = term, refByName = true)
        }
        val second = tokens.getOrNull(2) as? Token.Identifier ?: fail(ctx, "参数格式错误")
        if (tokens.size > 3) fail(ctx, "参数格式错误")
        return RefArgument(first.code, srcName = second.code)
    }

    // -----------------------------------------------------------------------
    // 图形/HTML 类
    // -----------------------------------------------------------------------

    /**
     * 切一个允许 `px` 单位后缀的尺寸参数，返回（表达式, 是否 px, 下一位置 —— 指向顶层逗号）。
     *
     * CMP-53：`px` 是**词法级**单位标记，不是文本后缀（spec: ArgumentParser.cs:56-90）：
     * - 终止位传 `(TermEndWith)34` = `Comma or KeyWordPx`（:57 / :124，常量见
     *   [TermEndWith.Comma_KeyWordPx]）；
     * - `px` 由 ExpressionParser.cs:534-537 在「后随 `,` 或行尾」时终止表达式（该判定在
     *   [splitTerm] 内），于是它作为独立 IdentifierWord 留在游标上；
     * - :77-81 用「当前词不是行尾、也不是逗号」判 `isPx` 并把它跳过。
     *
     * 旧实现按原文切逗号 + 剥末两字符（splitPxArgs/stripPx）是文本级猜测，两处偏离原版：
     * 额外接受原版会在**词法层**报错的 `100px`（ReadInt64 把 `p` 当二进制指数，
     * LexicalAnalyzer.cs:216-221 在指数位非数字时 `NumericCheck` 返回 false / 直接抛
     * CanNotConvertToInt），以及把 `100 px + 1` 静默当单位。语料 PRINTIMG/PRINTRECT/
     * PRINTSPACE 实测 0 处 ⇒ 本次接线对语料字节中性。
     */
    private fun splitPxTerm(
        tokens: List<ExprToken>,
        pos: Int,
        ctx: ArgCtx,
        argNo: Int,
    ): Triple<Expr?, Boolean, Int> {
        val (term, next) = splitTerm(tokens, pos, TermEndWith.Comma_KeyWordPx, ctx)
        var p = next
        val isPx = p < tokens.size &&
            tokens[p].kind == ExprTokenKind.IDENTIFIER &&
            tokens[p].text.equals("px", ignoreCase = true)
        if (isPx) p++
        if (p < tokens.size && tokens[p].kind != ExprTokenKind.COMMA) {
            // 原版此处不可达：reduceTerm 在 Comma|KeyWordPx 下只会在 `,`、行尾或 px 词处停下，
            // 其余符号在 reduceTerm 内已抛错（spec: ExpressionParser.cs:594-612）。
            // 移植侧的 [splitTerm] 是浅扫描器，故显式兜住。
            fail(ctx, "第${argNo}个参数格式错误")
        }
        return Triple(term, isPx, p)
    }

    /**
     * 把**粘连写**的单位（`300px`）拆成 `300 px`，好让 [splitPxTerm] 走词法级判定（CMP-53）。
     *
     * 原版的 `px` 是一个独立的词：终止位 `(TermEndWith)34` = Comma or KeyWordPx
     * （spec: ArgumentParser.cs:57 / :124），且 `ExpressionParser.cs:534-537` 只在
     * 「`px` 后紧跟 `,` 或行尾」时把表达式终止在这里 ⇒ 粘连形在词法层根本产生不了 px 词
     * （`LexicalAnalyzer.cs:216-221` 会把数字后的 `p` 当 2 进制指数标记，`300px` 直接
     * `"を整数値に変換できません"`）。移植侧既有测试与自产快照用的全是粘连形
     * （`PRINT_IMG "a.webp", 300px, 200px, 40px`、`PRINT_RECT 5px`）⇒ 这里先在**引号外**
     * 做一次拆词，再把文本交给词法层，读数与旧文本级剥离逐档相同。
     *
     * 只对「前面是数字 / `)` / `]`，后面（可隔空白）是 `,` 或行尾」的 `px` 生效，故
     * `bg.webp`（引号内或后面是别的字符）、`20 px + 1`（后面是 `+`）都不动。
     *
     * TODO(verify)：原版对 `300px` 到底是报错还是接受，需 PC 侧 oracle 实跑 —— 语料
     * PRINTIMG / PRINTRECT / PRINTSPACE 实测 0 处，无脚本样本可证 ⇒ 本处取「保持移植侧
     * 既有读数」的保守口径，不据词法层把粘连形判死。
     */
    private fun spaceOutPxUnit(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var quote = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') quote = !quote
            if (!quote && (c == 'p' || c == 'P') && i + 1 < text.length &&
                (text[i + 1] == 'x' || text[i + 1] == 'X') && i > 0
            ) {
                val prev = text[i - 1]
                val prevIsValue = prev.isDigit() || prev == ')' || prev == ']'
                if (prevIsValue && pxEndsArg(text, i + 2)) {
                    sb.append(' ')
                    sb.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** `px` 之后（跳过空白）必须是 `,` 或行尾 —— spec: ExpressionParser.cs:534-537。 */
    private fun pxEndsArg(text: String, from: Int): Boolean {
        var j = from
        while (j < text.length && text[j] == ' ') j++
        return j >= text.length || text[j] == ','
    }

    /** spec: ArgumentParser.cs:19-100 SP_PRINT_IMG；px 口径见 [splitPxTerm]（CMP-53）。 */
    private fun printImg(ctx: ArgCtx): Argument {
        val tokens = toExprTokens(lexWords(ctx, spaceOutPxUnit(ctx.argText)))
        if (tokens.isEmpty()) fail(ctx, "第1个参数不能省略")
        val (nameTerm, p1) = splitTerm(tokens, 0, TermEndWith.Comma, ctx)
        if (nameTerm == null) fail(ctx, "第1个参数不能省略")
        var pos = p1
        if (pos < tokens.size) {
            // 原版 :36 `wordCollection.ShiftNext()` 跳过第 1 个参数后的逗号。
            if (tokens[pos].kind != ExprTokenKind.COMMA) fail(ctx, "参数格式错误")
            pos++
        }
        var nameb: Expr? = null
        var namem: Expr? = null
        val params = mutableListOf<MixedIntegerExprTerm>()
        var argNo = 2
        while (pos < tokens.size) {
            if (params.size == 3) fail(ctx, "参数过多")
            val (term, isPx, next) = splitPxTerm(tokens, pos, ctx, argNo)
            pos = next
            if (term == null) fail(ctx, "第${argNo}个参数不能省略")
            if (term.operandType == OperandType.STR) {
                if (params.isNotEmpty() || argNo > 3) fail(ctx, "第${argNo}个参数的类型不正确")
                when (argNo) {
                    2 -> nameb = term
                    3 -> namem = term
                }
            } else {
                params.add(MixedIntegerExprTerm(term, isPx))
            }
            argNo++
            // 尾随逗号在原版是合法的：跳过后即行尾，循环自然结束（:84-90）。
            if (pos < tokens.size) pos++
        }
        return SpPrintImgArgument(nameTerm, nameb, namem, params.ifEmpty { null })
    }

    /** spec: ArgumentParser.cs:102-166 SP_PRINT_SHAPE；px 口径见 [splitPxTerm]（CMP-53）。 */
    private fun printShape(ctx: ArgCtx, maxArg: Int): Argument {
        val tokens = toExprTokens(lexWords(ctx, spaceOutPxUnit(ctx.argText)))
        val params = mutableListOf<MixedIntegerExprTerm>()
        // 槽位数（含空槽位）单独计数：原版 :122-143 的循环**每个逗号分隔位都向 list 追加一项**
        // （`aExpression` 为 null 也照样 `list.Add(new MixedIntegerExprTerm{ num = null })`），
        // 参数个数判定（:144 `list.Count != 1 && list.Count != 4`）与 TooManyArg（:139）都按
        // 这个槽位数走。旧实现跳过空槽位、只按非空项计数 ⇒ `PRINT_RECT ,5` 原版是 2 项
        // （DifferentArgsCount，level 2 报错），移植侧却当 1 项放行（比原版更松）。
        // 注意只让**计数**含空槽位：写进 AST 的仍是非空项 —— 原版对含空位的 4 项形态会在
        // 执行期对 null 项 NRE（官方崩溃面），移植侧按「不复刻崩溃」取 0 值，参见执行层注释。
        var slots = 0
        var pos = 0
        while (pos < tokens.size) {
            val (term, isPx, next) = splitPxTerm(tokens, pos, ctx, slots + 1)
            pos = next
            slots++
            if (term != null) {
                params.add(MixedIntegerExprTerm(term, isPx))
                if (slots > maxArg) tooMany(ctx)
            }
            if (pos < tokens.size) pos++
        }
        if (slots != 1 && slots != 4) fail(ctx, "参数个数必须为 1 或 4")
        return SpPrintShapeArgument(params)
    }

    /** spec: ArgumentParser.cs:167-231 SP_HTML_PRINT */
    private fun htmlPrint(ctx: ArgCtx): Argument {
        val tokens = toExprTokens(
            if (ctx.argText.isEmpty()) emptyList()
            else ctx.lexer.analyse(
                CharStream(ctx.argText), LexEndWith.EoL,
                LexAnalyzeFlag.ANALYZE_PRINT_V, ctx.line,
            ),
        )
        val list = com.eratw.port.core.expr.ExprParser(tokens, ctx.resolver, ctx.config)
            .reduceArguments(ArgsEndWith.EoL)
        if (list.isEmpty()) fail(ctx, "参数不足")
        if (list.size > 2) fail(ctx, "参数过多")
        val first = list[0] ?: fail(ctx, "第1个参数不能省略")
        if (first.operandType != OperandType.STR) fail(ctx, "第1个参数类型不正确")
        val second = list.getOrNull(1)
        if (second != null && second.operandType != OperandType.INT) fail(ctx, "第2个参数类型不正确")
        return SpHtmlPrint(first, second)
    }

    /** spec: ArgumentParser.cs:233-324 SP_DT_COLUMN_OPTIONS（当前仅 default 选项） */
    private fun dtColumnOptions(ctx: ArgCtx): Argument {
        val tokens = toExprTokens(lexWords(ctx))
        if (tokens.isEmpty()) fail(ctx, "第1个参数不能省略")
        var pos = 0
        val (dt, p1) = splitTerm(tokens, pos, TermEndWith.Comma, ctx)
        dt ?: fail(ctx, "第1个参数不能省略")
        pos = p1
        if (pos >= tokens.size) fail(ctx, "参数不足")
        pos++
        val (col, p2) = splitTerm(tokens, pos, TermEndWith.Comma, ctx)
        col ?: fail(ctx, "第2个参数不能省略")
        pos = p2
        if (pos >= tokens.size) fail(ctx, "参数不足")
        pos++
        val options = mutableListOf<SpDtColumnOptions.DTOption>()
        val values = mutableListOf<Expr?>()
        while (pos < tokens.size) {
            val id = tokens[pos].text.lowercase()
            pos++
            if (pos < tokens.size && tokens[pos].kind == ExprTokenKind.COMMA) pos++
            if (pos >= tokens.size) fail(ctx, "参数不足")
            if (id != "default") fail(ctx, "无法解释的选项 '$id'")
            options.add(SpDtColumnOptions.DTOption.DEFAULT)
            val (v, np) = splitTerm(tokens, pos, TermEndWith.Comma, ctx)
            v ?: fail(ctx, "参数不足")
            values.add(v)
            pos = np
            if (pos < tokens.size && tokens[pos].kind == ExprTokenKind.COMMA) pos++
        }
        if (options.isEmpty()) fail(ctx, "参数不足")
        return SpDtColumnOptions(dt, col, options, values)
    }

    // -----------------------------------------------------------------------
    // CASE
    // -----------------------------------------------------------------------

    /** spec: ExpressionParser.cs:331-338 ReduceCaseExpressions / :340-400 reduceCaseExpression */
    private fun case(ctx: ArgCtx): Argument {
        val tokens = toExprTokens(lexWords(ctx))
        val list = mutableListOf<CaseExpression>()
        var pos = 0
        while (pos < tokens.size) {
            val (ce, next) = reduceCaseExpression(tokens, pos, ctx)
            list.add(ce)
            pos = next
            if (pos < tokens.size && tokens[pos].kind == ExprTokenKind.COMMA) pos++
            else if (pos < tokens.size) fail(ctx, "CASE 参数格式错误")
        }
        if (list.isEmpty()) fail(ctx, "CASE 参数格式错误")
        return CaseArgument(list)
    }

    private fun reduceCaseExpression(tokens: List<ExprToken>, start: Int, ctx: ArgCtx): Pair<CaseExpression, Int> {
        var pos = start
        val ce = CaseExpression()
        val t = tokens[pos]
        if (t.kind == ExprTokenKind.IDENTIFIER && t.text.equals("IS", ignoreCase = true)) {
            pos++
            val opTok = tokens.getOrNull(pos) ?: fail(ctx, "'IS' 之后没有运算符")
            if (opTok.kind != ExprTokenKind.OPERATOR) fail(ctx, "'IS' 之后没有运算符")
            val op = OperatorCode.fromText(opTok.text) ?: fail(ctx, "无法识别的运算符")
            if (!op.isBinary) fail(ctx, "'IS' 之后的运算符不是二元运算符")
            pos++
            val (left, np) = splitTerm(tokens, pos, TermEndWith.Comma, ctx)
            left ?: fail(ctx, "'IS' 之后没有表达式")
            ce.caseType = CaseExpression.CaseType.IS
            ce.operator = op
            ce.leftTerm = left
            return ce to np
        }
        var (left, np) = splitTerm(tokens, pos, TermEndWith.Comma, ctx, stopAtTo = true)
        left ?: fail(ctx, "CASE 参数不能省略")
        pos = np
        if (pos < tokens.size && tokens[pos].kind == ExprTokenKind.IDENTIFIER &&
            tokens[pos].text.equals("TO", ignoreCase = true)
        ) {
            pos++
            val (right, np2) = splitTerm(tokens, pos, TermEndWith.Comma, ctx, stopAtTo = true)
            right ?: fail(ctx, "'TO' 之后没有表达式")
            pos = np2
            if (pos < tokens.size && tokens[pos].kind == ExprTokenKind.IDENTIFIER &&
                tokens[pos].text.equals("TO", ignoreCase = true)
            ) {
                fail(ctx, "TO 重复出现")
            }
            if (left.operandType != right.operandType) fail(ctx, "TO 两端类型不匹配")
            ce.caseType = CaseExpression.CaseType.TO
            ce.leftTerm = left
            ce.rightTerm = right
            return ce to pos
        }
        ce.caseType = CaseExpression.CaseType.NORMAL
        ce.leftTerm = left
        return ce to pos
    }

    // -----------------------------------------------------------------------
    // 赋值（SET）
    // -----------------------------------------------------------------------

    /** spec: ArgumentParser.cs:911-1145 SP_SET / SP_SET_ARRAY / SP_SETS / SP_SETS_ARRAY */
    private fun set(ctx: ArgCtx): Argument {
        val destTokens = ctx.destTokens ?: fail(ctx, "赋值语句缺少左值")
        val destList = com.eratw.port.core.expr.ExprParser(
            toExprTokens(destTokens), ctx.resolver, ctx.config,
        ).reduceArguments(ArgsEndWith.EoL)
        if (destList.isEmpty() || destList[0] == null) fail(ctx, "无法读取左值")
        if (destList.size != 1) fail(ctx, "左值有多余的逗号")
        val dest = destList[0]!!.asVarRef() ?: fail(ctx, "左值不是变量")
        if (dest.isConst) fail(ctx, "左值是常量")
        val op = ctx.assignOp ?: fail(ctx, "缺少赋值运算符")
        val rhsText = ctx.argText

        if (dest.operandType == OperandType.INT) {
            if (op == OperatorCode.ASSIGNMENT_STR) fail(ctx, "整数变量不能使用 '$' 赋值")
            if (op == OperatorCode.INCREMENT || op == OperatorCode.DECREMENT) {
                // 原版 SkipWhiteSpace 遇 ';' 直接吃到行尾（spec: LexicalAnalyzer.cs SkipWhiteSpace）
                val rest = rhsText.trimStart(' ', '\t').let { if (it.startsWith(";")) "" else it }
                if (rest.isNotEmpty()) {
                    fail(ctx, if (op == OperatorCode.INCREMENT) "++ 运算符不能带参数" else "-- 运算符不能带参数")
                }
                val arg = SpSetArgument(dest, null)
                arg.isConst = true
                arg.constInt = if (op == OperatorCode.INCREMENT) 1L else -1L
                arg.addConst = true
                return arg
            }
            val list = popTerms(ctx)
            if (list.isEmpty() || list[0] == null) fail(ctx, "无法读取右值")
            if (list.size != 1) {
                if (op != OperatorCode.ASSIGNMENT) fail(ctx, "该赋值运算符不能有多个右值")
                val consts = LongArray(list.size)
                var allConst = true
                for (i in list.indices) {
                    val e = list[i] ?: fail(ctx, "右值不能省略")
                    if (!e.isInteger()) fail(ctx, "不能把字符串赋给整数变量")
                    if (allConst) {
                        val c = e.constInt()
                        if (c != null) consts[i] = c else allConst = false
                    }
                }
                val arg = SpSetArrayArgument(dest, list, constIntList = if (allConst) consts else null)
                arg.isConst = allConst
                return arg
            }
            val term = list[0]!!
            if (!term.isInteger()) fail(ctx, "不能把字符串赋给整数变量")
            when (op) {
                OperatorCode.ASSIGNMENT -> {
                    val arg = SpSetArgument(dest, term)
                    term.constInt()?.let {
                        arg.isConst = true
                        arg.addConst = false
                        arg.constInt = it
                    }
                    return arg
                }
                OperatorCode.PLUS, OperatorCode.MINUS -> {
                    val c = term.constInt()
                    if (c != null) {
                        val arg = SpSetArgument(dest, null)
                        arg.isConst = true
                        arg.constInt = if (op == OperatorCode.PLUS) c else -c
                        arg.addConst = true
                        return arg
                    }
                }
                else -> {}
            }
            return SpSetArgument(dest, reduceBinary(op, dest, term, ctx))
        }

        // 字符串变量
        when (op) {
            OperatorCode.ASSIGNMENT -> {
                // spec: ArgumentParser.cs:1025-1029 —— `Config.SystemIgnoreStringSet` 打开时，
                // 字符串变量的 `=` 赋值是 level 2 错误（ArgumentBuilder.cs:21-26 置 line.IsError），
                // 文案 `Lang.Error.StrAssignIsPrihibited`（Lang.cs:2062；zhs xml:470）。
                // 只挡这一支：`'=`（ASSIGNMENT_STR）与复合赋值在下面的分支，原版同样不挡。
                if (ctx.config.systemIgnoreStringSet) {
                    fail(ctx, "直接赋值字符串被禁用(请使用「'=」或更改设置)")
                }
                val text = rhsText.trimStart(' ', '\t')
                val form = analyseFormToEol(
                    ctx.lexer, CharStream(text), ctx.line, trim = true,
                    ignoreTripleSymbol = ctx.lexerConfig.systemIgnoreTripleSymbol,
                )
                val term = FormStringTerm.fromLexerStrForm(form, ctx.resolver, ctx.config, ctx.line, 0)
                val arg = SpSetArgument(dest, term)
                if (form.subWords.isEmpty()) {
                    arg.isConst = true
                    arg.addConst = false
                    arg.constStr = form.strs.joinToString("")
                }
                return arg
            }
            OperatorCode.ASSIGNMENT_STR, OperatorCode.MULT, OperatorCode.PLUS -> {
                val list = popTerms(ctx)
                if (list.isEmpty() || list[0] == null) fail(ctx, "无法读取右值")
                if (op == OperatorCode.ASSIGNMENT_STR) {
                    if (list.size == 1) {
                        val e = list[0]!!
                        if (e.isInteger()) fail(ctx, "不能把整数赋给字符串变量")
                        val arg = SpSetArgument(dest, e)
                        e.constStr()?.let {
                            arg.isConst = true
                            arg.addConst = false
                            arg.constStr = it
                        }
                        return arg
                    }
                    val consts = arrayOfNulls<String>(list.size)
                    var allConst = true
                    for (i in list.indices) {
                        val e = list[i] ?: fail(ctx, "右值不能省略")
                        if (e.isInteger()) fail(ctx, "不能把整数赋给字符串变量")
                        if (allConst) {
                            val c = e.constStr()
                            if (c != null) consts[i] = c else allConst = false
                        }
                    }
                    val arg = SpSetArrayArgument(
                        dest, list,
                        constStrList = if (allConst) consts.map { it!! }.toTypedArray() else null,
                    )
                    arg.isConst = allConst
                    return arg
                }
                if (list.size != 1) fail(ctx, "右值有多余的逗号")
                return SpSetArgument(dest, reduceBinary(op, dest, list[0]!!, ctx))
            }
            else -> fail(ctx, "不合法的赋值运算符")
        }
    }

    private fun reduceBinary(op: OperatorCode, dest: VarRef, term: Expr, ctx: ArgCtx): Expr = try {
        reduceBinaryTerm(op, dest, term)
    } catch (e: Exception) {
        fail(ctx, e.message ?: "赋值运算符类型不匹配")
    }
}
