package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef

/** 语句在块结构中的角色（用于块平衡校验）。 */
enum class BlockRole {
    /** 不参与块结构 */
    NONE,

    /** 块开始（IF/SELECTCASE/FOR/WHILE/DO/REPEAT/PRINTDATA/DATALIST/NOSKIP/TRYCALL…LIST/CATCH） */
    OPEN,

    /** 块分支（ELSEIF/ELSE/CASE/CASEELSE），不改变深度 */
    BRANCH,

    /** 块结束（ENDIF/ENDSELECT/NEXT/REND/WEND/LOOP/ENDDATA/ENDLIST/ENDNOSKIP/ENDCATCH/ENDFUNC） */
    CLOSE,
}

/**
 * 语句 AST 基类。
 *
 * 一个 ERB 语句行对应一个 [Statement] 子类实例；[code] 为原版 FunctionCode，
 * [args] 为参数（[Argument] 家族，形态由 [ArgType] 决定）。
 *
 * 层级说明：指令行按语义族给出专门类（块结构、跳转、赋值、PRINT、数据节、变量声明），
 * 其余指令统一用 [InstructionStmt]，由 [code] 区分——169 条 isa 语句共用 50 种参数形态，
 * 逐条建类只会产生 169 个字段完全相同的空壳，故按「结构 + 参数形态」建类，
 * 语句身份由 [code] 唯一确定（与 Accessor 的 FunctionCode 一一对应）。
 */
sealed class Statement {
    abstract val code: FunctionCode
    abstract val line: Int

    /**
     * 参数结点（[Argument] 家族）。
     *
     * 读取面是**只读属性**（`val`）。装载后规范化去重（[ArgInterner]）经
     * [replaceArgs] 换入结构相等的共享实例——语句对象本身与池下标都不变，
     * 因此数据节身份链（以 `Statement` 实例为键）不受影响。
     */
    val args: Argument get() = argsSlot ?: VoidArgument().also { argsSlot = it }

    /** 参数槽：[Statement] 的子类经 [initArgs] 在构造期填入（惰性兜底 = 无参）。 */
    private var argsSlot: Argument? = null

    protected fun initArgs(value: Argument) {
        argsSlot = value
    }

    /**
     * 换入规范化后的参数实例（装载期专用，见 [ArgInterner]）。
     *
     * 只许在「编译/落盘全部结束、运行期尚未开始」的窗口调用：语义上等价于原实例
     * （结构相等），运行期对参数只读。
     */
    internal fun replaceArgs(value: Argument) {
        argsSlot = value
    }

    /**
     * 原文槽（MEM-OPT）。全量语料实测：42,780,556 字符的原文驻留 ≈ 131.0 MiB
     * （PERF-1 S2 账单「statements.raw」项），而运行期生产路径（Vm 取指 →
     * ProcessRuntime.executeStatement → StatementExecutor）**没有任何读者** ——
     * CodeEE 报错定位走 Program.lines + StmtEnv（ProcessRuntime.stampPosition），
     * 全局 #DIM 装载（SystemVariableBootstrap）吃的是装载链自采的 RawDeclaration
     * 原文，不是池内语句。唯一在池外读 raw 的是编译期（ErbProgramCompiler 的
     * FOR 合成行、ErbcWriter 的落盘）。
     *
     * 因此原文改为可释放槽：构造期经 [initRaw] 填入；运行期接线方（组长裁决落点，
     * 建议 compile → runtime 交接处）调 [releaseRaw] 后 [raw] 返回 ""，131 MiB 级
     * 驻留归零。释放只许发生在「编译 + 落盘全部结束之后」——ErbcWriter 会把
     * raw 写进 .erbc（specs/erbc-format.md §5.4 逐条原文），先释放后落盘会写出空原文。
     */
    private var rawSlot: String? = null

    /** 原始行文本（去掉行首空白，保留 @/$/# 前导标记）。释放后返回 ""。 */
    val raw: String get() = rawSlot ?: ""

    protected fun initRaw(text: String) {
        rawSlot = text
    }

    /** 释放原文驻留（幂等）。释放后 [raw] 恒为 ""，不可恢复。 */
    internal fun releaseRaw() {
        rawSlot = ""
    }

    val kind: StmtKind get() = code.kind

    val role: BlockRole
        get() = when {
            BlockTable.openToClose.containsKey(code) -> BlockRole.OPEN
            BlockTable.branchTargets.contains(code) -> BlockRole.BRANCH
            BlockTable.parentOf.containsKey(code) -> BlockRole.CLOSE
            END_ONLY.contains(code) -> BlockRole.CLOSE
            else -> BlockRole.NONE
        }

    /** 结束块期望的母语句（仅 CLOSE 有意义）。spec: FunctionIdentifier.cs:4170-4173 */
    val closeParent: FunctionCode? get() = BlockTable.parentOf[code]

    /** 该开始语句期望的结束语句（仅 OPEN 有意义）。spec: FunctionIdentifier.cs:4144-4169 */
    val expectedClose: FunctionCode? get() = BlockTable.openToClose[code]

    override fun toString(): String = "${javaClass.simpleName}(${code.name}, line=$line)"

    private companion object {
        /**
         * 无 funcParent 记录的结束语句 —— 其母块由配对表反查：
         * ENDIF→IF、ENDSELECT→SELECTCASE、ENDDATA→PRINTDATA/STRDATA、ENDLIST→DATALIST、
         * ENDNOSKIP→NOSKIP、ENDCATCH→CATCH、ENDFUNC→TRYCALL…LIST。
         */
        val END_ONLY: Set<FunctionCode> = setOf(
            FunctionCode.ENDIF, FunctionCode.ENDSELECT, FunctionCode.ENDDATA,
            FunctionCode.ENDLIST, FunctionCode.ENDNOSKIP, FunctionCode.ENDCATCH, FunctionCode.ENDFUNC,
        )
    }
}

// ---------------------------------------------------------------------------
// 指令行
// ---------------------------------------------------------------------------

/** 通用指令语句。全部指令行子类经本类构造，raw 在此统一入槽。 */
open class InstructionStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : Statement() {
    init {
        initArgs(args)
        initRaw(raw)
    }
}

/** PRINT 家族。后缀标志见 [PrintSpec]。spec: FunctionIdentifier.cs:73-229 */
class PrintStmt(
    print: PrintSpec,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(print.code, args, line, raw) {
    /** PRINT 后缀解析结果（装载期规范化后与其他同形态 PRINT 共享，见 [ArgInterner]）。 */
    var print: PrintSpec = print
        internal set

    /** 是否末尾换行。spec: FunctionIdentifier.cs:75,146-166 */
    val isLineEnd: Boolean get() = print.isLineEnd
}

/** PRINTDATA 家族（数据节 + 随机行输出）。spec: FunctionIdentifier.cs:231-320 */
class PrintDataStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

/**
 * 赋值语句 `lhs op= rhs`。
 * 原版在语句层被翻译为 InstructionLine(SETFunction, assignOp, destWords, argStream)
 * （spec: LogicalLineParser.cs:536-556），此处保留左值与运算符。
 */
class AssignStmt(
    dest: VarRef,
    val op: OperatorCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : Statement() {
    override val code get() = FunctionCode.SET

    /** 左值（装载期规范化后可与其他同形态赋值共享，见 [ArgInterner]）。 */
    var dest: VarRef = dest
        internal set

    init {
        initArgs(args)
        initRaw(raw)
    }
}

/** 跳转语句（JUMP/GOTO/TRYJUMP/…；label 为常量标签名）。spec: SpCallArgment.cs */
class JumpStmt(
    override val code: FunctionCode,
    label: String?,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw) {
    /** 标签名（装载期按内容归一，见 [ArgInterner]）。 */
    var label: String? = label
        internal set
}

/** 函数调用语句（CALL/TRYCALL/…）。 */
class CallStmt(
    override val code: FunctionCode,
    label: String?,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw) {
    /** 被调函数键（装载期按内容归一，见 [ArgInterner]）。 */
    var label: String? = label
        internal set
}

/** CALLF / TRYCALLF。spec: SpCallFArgment.cs */
class CallFStmt(
    functionName: String?,
    args: Argument,
    override val line: Int,
    raw: String,
    override val code: FunctionCode = FunctionCode.CALLF,
) : InstructionStmt(code, args, line, raw) {
    /** 被调函数键（装载期按内容归一，见 [ArgInterner]）。 */
    var functionName: String? = functionName
        internal set
}

/** CALLSHARP。spec: SpCallSharpArgment.cs */
class CallSharpStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.CALLSHARP, args, line, raw)

/** 用户函数方法调用（行首为用户函数名）。spec: FunctionIdentifier.cs:4140-4148 */
class MethodStmt(
    methodName: String,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.METHOD, args, line, raw) {
    /** 方法名（装载期按内容归一，见 [ArgInterner]）。 */
    var methodName: String = methodName
        internal set
}

/** VARI / VARS —— 作用域私有变量进入作用域。spec: LogicalLineParser.cs:412-511 */
class VarDeclStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

// ---------------------------------------------------------------------------
// 块结构语句
// ---------------------------------------------------------------------------

/** IF / SIF。args 为条件表达式。spec: ArgumentParser.cs:1147-1240 */
class IfStmt(
    condition: Expr,
    args: Argument,
    override val line: Int,
    raw: String,
    /** SIF：单行 IF（无 ENDIF 配对） */
    val single: Boolean = false,
) : InstructionStmt(if (single) FunctionCode.SIF else FunctionCode.IF, args, line, raw) {
    /** 条件表达式（装载期规范化后可共享，见 [ArgInterner]）。 */
    var condition: Expr = condition
        internal set
}

/** ELSEIF。spec: FunctionIdentifier.cs:3962-3963（ELSEIF_Instruction） */
class ElseIfStmt(
    condition: Expr,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.ELSEIF, args, line, raw) {
    /** 条件表达式（装载期规范化后可共享，见 [ArgInterner]）。 */
    var condition: Expr = condition
        internal set
}

/** ELSE。 */
class ElseStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.ELSE, args, line, raw)

/** ENDIF。 */
class EndIfStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.ENDIF, args, line, raw)

/** SELECTCASE。spec: ArgumentParser.cs:1356-1387（EXPRESSION） */
class SelectCaseStmt(
    target: Expr,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.SELECTCASE, args, line, raw) {
    /** 选择目标（装载期规范化后可共享，见 [ArgInterner]）。 */
    var target: Expr = target
        internal set
}

/** CASE（条件式集合）。spec: CaseArgument */
class CaseStmt(
    cases: List<CaseExpression>,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.CASE, args, line, raw) {
    /** 条件分支表（装载期规范化后可共享，见 [ArgInterner]）。 */
    var cases: List<CaseExpression> = cases
        internal set
}

/** CASEELSE。 */
class CaseElseStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.CASEELSE, args, line, raw)

/** ENDSELECT。 */
class EndSelectStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.ENDSELECT, args, line, raw)

/** FOR / REPEAT（同为 SP_FOR_NEXT 形态）。spec: SpForNextArgment.cs:7-23 */
class ForStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

/** NEXT。 */
class NextStmt(
    args: Argument,
    override val line: Int,
    raw: String,
    override val code: FunctionCode = FunctionCode.NEXT,
) : InstructionStmt(code, args, line, raw)

/** REPEAT。 */
class RepeatStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.REPEAT, args, line, raw)

/** REND。 */
class RendStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.REND, args, line, raw)

/** WHILE。 */
class WhileStmt(
    condition: Expr,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.WHILE, args, line, raw) {
    /** 循环条件（装载期规范化后可共享，见 [ArgInterner]）。 */
    var condition: Expr = condition
        internal set
}

/** WEND。 */
class WendStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.WEND, args, line, raw)

/** DO。 */
class DoStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.DO, args, line, raw)

/** LOOP。 */
class LoopStmt(
    condition: Expr?,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.LOOP, args, line, raw) {
    /** 循环条件（null = 无条件；装载期规范化后可共享，见 [ArgInterner]）。 */
    var condition: Expr? = condition
        internal set
}

/** CATCH。 */
class CatchStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.CATCH, args, line, raw)

/** ENDCATCH。 */
class EndCatchStmt(
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.ENDCATCH, args, line, raw)

/** DATALIST。spec: ArgumentParser.cs:3890-3898（VOID，flag 22） */
class DataListStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

/** ENDLIST / ENDDATA / ENDNOSKIP / ENDFUNC 等无参结束语句。 */
class EndBlockStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

// ---------------------------------------------------------------------------
// 流程控制（非块）
// ---------------------------------------------------------------------------

/** BREAK / CONTINUE / RESTART / QUIT 等无参流程语句。 */
class FlowStmt(
    override val code: FunctionCode,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw)

/** RETURN（整数列表）。spec: ArgumentParser.cs:1287-1345 */
class ReturnStmt(
    values: List<Expr?>,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(FunctionCode.RETURN, args, line, raw) {
    /** 返回值列表（装载期规范化后可共享，见 [ArgInterner]）。 */
    var values: List<Expr?> = values
        internal set
}

/** RETURNF / RETURNFORM。 */
class ReturnFStmt(
    value: Expr?,
    args: Argument,
    override val line: Int,
    raw: String,
    override val code: FunctionCode = FunctionCode.RETURNF,
) : InstructionStmt(code, args, line, raw) {
    /** 返回值（装载期规范化后可共享，见 [ArgInterner]）。 */
    var value: Expr? = value
        internal set
}

// ---------------------------------------------------------------------------
// 标签 / 预处理行
// ---------------------------------------------------------------------------

/**
 * 形参缺省值的一项 —— 原版 `FunctionLabelLine.Def[i]`（spec: FunctionLabelLine.cs:49，
 * 字段类型 `SingleTerm[]`）。
 *
 * 装载期生成口径（spec: ErbLoader.cs:646-760）：形参头按 `ReduceArguments(..., isDefine: true)`
 * 解成「[变量项, 默认值项]」对，默认值项经 `Restructure` 折叠后**必须是 `SingleTerm`（常量）**，
 * 否则 `ArgCanOnlyConst`（:764-767；同理 `NotMatchTypeArgAndInitialValue` :770-773 要求
 * 默认值与形参同类型）。调用点被省略的实参用它顶替（spec: CalledFunction.cs:167）。
 *
 * @property expr 编译期可注入的常量表达式。本遍只收**字面量**（整型/十六进制/字符串，
 *   含一元 `-`/`+`/`~` 折叠）；null = 有默认值但本遍不注入（常量名/任意表达式/解析失败），
 *   只登记 [raw] 供报告，绝不猜
 * @property raw 默认值原文（诊断/报告用；空串 = `= ` 后无表达式的形态）
 */
data class ParamDefault(val expr: Expr?, val raw: String)

/**
 * `@NAME(ARG, ARG$)` 函数标签行。spec: LogicalLineParser.cs:314-383 ParseLabelLine。
 * [paramNames] 为形参**名**（`@FUNC(A, B$, C)` → [A, B$, C]；下标被剥掉，供 arity /
 * 类型桶计数使用）。
 *
 * [paramRefs] 是形参**项原文**（保留 `:n` 下标）：`@F(ARG, ARG:1)` → [ARG, ARG:1]。
 * 原版 `TopLabel.Arg[i]` 是 `VariableTerm`（含 arguments），`SetValue` 写的是**该下标处
 * 的元素**（spec: VariableTerm.cs:108-129 + ProcessState.cs:435-452）；只有裸名字无法区分
 * `ARG` 与 `ARG:1`，故逐位绑定必须用本字段。默认与 [paramNames] 相同（无下标时二者一致）。
 *
 * [paramDefaults] 逐位默认值（与形参个数等长；null 位 = 该形参无默认值）。
 * **只在编译期消费**（调用点补位）：`_CALL` 的实参在编译期就补成完整实参表，
 * 故 `.erbc` 容器不需要落盘这张表（[paramRefs] 同样只在装载侧用，实参列记录里也没有）。
 */
class FuncLabelStmt(
    name: String,
    paramNames: List<String>,
    override val line: Int,
    raw: String,
    paramRefs: List<String> = paramNames,
    paramDefaults: List<ParamDefault?> = emptyList(),
) : Statement() {
    override val code get() = FunctionCode.__NULL__

    /** 函数名（装载期按内容归一，见 [ArgInterner]）。 */
    var name: String = name
        internal set

    /** 形参名列表（装载期按内容归一，见 [ArgInterner]）。 */
    var paramNames: List<String> = paramNames
        internal set

    /** 形参项原文列表（装载期按内容归一，见 [ArgInterner]）。 */
    var paramRefs: List<String> = paramRefs
        internal set

    /** 逐位默认值（编译期补位用；`.erbc` 不落盘，见类 KDoc）。 */
    var paramDefaults: List<ParamDefault?> = paramDefaults
        internal set

    init {
        initRaw(raw)
    }
}

/** `$LABEL` 跳转标签行。 */
class GotoLabelStmt(
    name: String,
    override val line: Int,
    raw: String,
) : Statement() {
    override val code get() = FunctionCode.__NULL__

    /** 标签名（装载期按内容归一，见 [ArgInterner]）。 */
    var name: String = name
        internal set

    init {
        initRaw(raw)
    }
}

/** `#` 预处理行（#DIM/#DIMS/#FUNCTION/#LOCALSIZE/…）。spec: LogicalLineParser.cs:20-307 */
class SharpStmt(
    keyword: String,
    /** LOCALSIZE 的常量值；DIM 的尺寸列表等（按关键字含义不同）。 */
    val values: List<Long> = emptyList(),
    name: String? = null,
    val isString: Boolean = false,
    val tokens: List<com.eratw.port.core.lexer.Token> = emptyList(),
    override val line: Int,
    raw: String,
) : Statement() {
    override val code get() = FunctionCode.__NULL__

    /** 预处理关键字（装载期按内容归一，见 [ArgInterner]）。 */
    var keyword: String = keyword
        internal set

    /** 声明名（装载期按内容归一，见 [ArgInterner]）。 */
    var name: String? = name
        internal set

    init {
        initRaw(raw)
    }
}

/** DATA / DATAFORM / STRDATA 的数据行。spec: ArgumentParser.cs:4017, 3902-3910 */
class DataStmt(
    override val code: FunctionCode,
    text: String,
    args: Argument,
    override val line: Int,
    raw: String,
) : InstructionStmt(code, args, line, raw) {
    /**
     * 数据行文本（装载期按内容归一，见 [ArgInterner]）。
     *
     * 运行期只按内容输出（`StatementExecutor` 的 DATA 家族），没有身份依赖。
     */
    var text: String = text
        internal set
}
