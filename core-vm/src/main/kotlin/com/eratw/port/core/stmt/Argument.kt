package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.OperatorCode

/**
 * 语句参数基类 —— 移植自 Argument（spec: Argument.cs:5-11）。
 *
 * 原版 IsConst/ConstStr/ConstInt 是解析期常量折叠的缓存（Restructure 阶段写入），
 * 供执行期跳过表达式求值。本实现只在参数为字面量时填写，语义与 ConstantTerm 一致。
 */
sealed class Argument {
    /** 参数整体是否为编译期常量。spec: Argument.cs:7 */
    var isConst: Boolean = false

    /** 常量字符串（IsConst 且为字符串型时有效）。spec: Argument.cs:9 */
    var constStr: String = ""

    /** 常量整数（IsConst 且为整数型时有效）。spec: Argument.cs:11 */
    var constInt: Long = 0L

    /** 参数形态标签，供 AST 遍历/验证统计使用。 */
    abstract val shape: ArgType

    /** 常量字符串字面量提示（若参数是单一 StrLiteral）。 */
    open val literalStr: String? get() = null

    /** 常量整数字面量提示（若参数是单一 IntLiteral）。 */
    open val literalInt: Long? get() = null
}

/** 无参。spec: VoidArgument.cs:5 */
class VoidArgument : Argument() {
    override val shape get() = ArgType.VOID
}

/** 单表达式。spec: ExpressionArgument.cs:7-13（Term 可为 null：原版 EXPRESSION 可空形态） */
class ExpressionArgument(val term: Expr?) : Argument() {
    override val shape get() = ArgType.EXPRESSION
    override val literalStr get() = (term as? com.eratw.port.core.expr.StrLiteral)?.value
    override val literalInt get() = (term as? com.eratw.port.core.expr.IntLiteral)?.value
}

/** 表达式数组。spec: ExpressionArrayArgument.cs:7-15 */
class ExpressionArrayArgument(val terms: List<Expr?>) : Argument() {
    override val shape get() = ArgType.INT_ANY
}

/** 带声明类型数组的表达式列表。spec: ExpressionsArgument.cs:8-18 */
class ExpressionsArgument(val types: List<ArgValueType?>, val terms: List<Expr?>) : Argument() {
    override val shape get() = ArgType.EXPRESSIONS
}

/** 参数静态类型。原版用 System.Type（typeof(long)/typeof(string)/typeof(void)/null）。 */
enum class ArgValueType {
    INT,
    STR,
    VOID,
    ;

    companion object {
        fun of(t: OperandType): ArgValueType = if (t == OperandType.INT) INT else STR
    }
}

/** VARI 的参数 —— 作用域私有整型变量声明。spec: IntAsignArgument.cs:6-22 */
class IntAsignArgument(
    val name: String,
    val lengths: IntArray,
    val exp: Expr?,
) : Argument() {
    override val shape get() = ArgType.INT_ASIGN

    init {
        constStr = name
    }
}

/** VARS 的参数 —— 作用域私有字符串变量声明。spec: StrAsignArgument.cs:5-22 */
class StrAsignArgument(
    val name: String,
    val lengths: IntArray,
    /** 初值（原版为字符串常量；无初值时为 null，等价 ""）。 */
    val value: String?,
) : Argument() {
    override val shape get() = ArgType.STR_ASIGN

    init {
        constStr = name
    }
}

/** SETBIT/CLEARBIT/INVERTBIT。spec: BitArgument.cs:7-17 */
class BitArgument(val dest: VarRef, val terms: List<Expr>) : Argument() {
    override val shape get() = ArgType.BIT_ARG
}

/** CASE 条件式集合。spec: CaseArgument.cs:5-13 */
class CaseArgument(val caseExps: List<CaseExpression>) : Argument() {
    override val shape get() = ArgType.CASE
}

/** 单个 CASE 条件分支。spec: CaseExpression.cs:5-78 / CaseExpressionType.cs:5-9 */
class CaseExpression(
    var caseType: CaseType = CaseType.NORMAL,
    var leftTerm: Expr? = null,
    var rightTerm: Expr? = null,
    var operator: OperatorCode = OperatorCode.NULL,
) {
    enum class CaseType { NORMAL, TO, IS }

    /** spec: CaseExpression.cs:19-26 —— LeftTerm 为 null 时返回 void（此处用 null 表示） */
    fun operandType(): ArgValueType? = leftTerm?.let { ArgValueType.of(it.operandType) }
}

/** 用户函数方法调用。spec: MethodArgument.cs:5-13 */
class MethodArgument(val methodTerm: Expr?) : Argument() {
    override val shape get() = ArgType.METHOD
}

/** PRINTDATA 的随机索引写入目标（VAR_INT）。spec: PrintDataArgument.cs:6-13 */
class PrintDataArgument(val dest: VarRef?) : Argument() {
    override val shape get() = ArgType.VAR_INT
}

/**
 * REF / REFBYNAME 的引用目标。原版持有 UserDefinedRefMethod / ReferenceToken / VariableToken 等
 * 符号对象（spec: RefArgument.cs:8-50）；符号绑定属 M2 符号层，此处只保留名字与源表达式。
 * TODO(verify): 符号层就绪后把 refName/srcName 绑定为符号对象。
 */
class RefArgument(
    val refName: String,
    val srcName: String? = null,
    val srcTerm: Expr? = null,
    val refByName: Boolean = false,
) : Argument() {
    override val shape get() = if (refByName) ArgType.SP_REFBYNAME else ArgType.SP_REF
}

/** STRDATA 的目标字符串数组。spec: StrDataArgument.cs:6-13 */
class StrDataArgument(val dest: VarRef?) : Argument() {
    override val shape get() = ArgType.VAR_STR
}

/** ARRAYREMOVE 等数组控制。spec: SpArrayControlArgument.cs:6-20 */
class SpArrayControlArgument(val dest: VarRef, val num1: Expr?, val num2: Expr?) : Argument() {
    override val shape get() = ArgType.SP_CONTROL_ARRAY
}

/** ARRAYSHIFT。spec: SpArrayShiftArgument.cs:6-26 */
class SpArrayShiftArgument(
    val dest: VarRef,
    val num1: Expr?,
    val num2: Expr?,
    val num3: Expr?,
    val num4: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_SHIFT_ARRAY
}

/** 排序方向。spec: SortOrder.cs:3-8 */
enum class SortOrder { UNDEF, ASCENDING, DESENDING }

/** ARRAYSORT。spec: SpArraySortArgument.cs:7-23 */
class SpArraySortArgument(
    val dest: VarRef,
    val order: SortOrder,
    val num1: Expr?,
    val num2: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_SORTARRAY
}

/** BAR。spec: SpBarArgument.cs:6-15 */
class SpBarArgument(val value: Expr?, val max: Expr?, val length: Expr?) : Argument() {
    override val shape get() = ArgType.SP_BAR
    val terms: List<Expr?> get() = listOf(value, max, length)
}

/** PRINTBUTTON。spec: SpButtonArgument.cs:6-16 */
class SpButtonArgument(val printStrTerm: Expr?, val buttonWord: Expr?) : Argument() {
    override val shape get() = ArgType.SP_BUTTON
}

/** CVARSET。spec: SpCVarSetArgument.cs:6-26 */
class SpCVarSetArgument(
    val dest: VarRef,
    val index: Expr?,
    val term: Expr?,
    val start: Expr?,
    val end: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_CVAR_SET
}

/**
 * JUMP/CALL/GOTO 等标签跳转参数。spec: SpCallArgment.cs:7-24。
 * FuncnameTerm 为标签名字符串表达式；[label] 为其常量名（非常量时为 null）。
 */
class SpCallArgument(
    val funcNameTerm: Expr?,
    val subNames: List<Expr?>,
    val rowArgs: List<Expr?>,
) : Argument() {
    override val shape get() = ArgType.SP_CALL
    override val literalStr get() = (funcNameTerm as? com.eratw.port.core.expr.StrLiteral)?.value
    val label: String? get() = literalStr
}

/** CALLF。spec: SpCallFArgment.cs:7-22 */
class SpCallFArgument(
    val funcNameTerm: Expr?,
    val subNames: List<Expr?>,
    val rowArgs: List<Expr?>,
) : Argument() {
    override val shape get() = ArgType.SP_CALLF
    override val literalStr get() = (funcNameTerm as? com.eratw.port.core.expr.StrLiteral)?.value
}

/** CALLSHARP。spec: SpCallSharpArgment.cs:7-25 */
class SpCallSharpArgument(
    val funcNameTerm: Expr?,
    val subNames: List<Expr?>,
    val rowArgs: List<Expr?>,
) : Argument() {
    override val shape get() = ArgType.SP_CALLCSHARP
}

/** SETCOLOR / SETBGCOLOR。spec: SpColorArgument.cs:6-26 */
class SpColorArgument private constructor(
    val r: Expr?,
    val g: Expr?,
    val b: Expr?,
    val rgb: Expr?,
) : Argument() {
    constructor(rgb: Expr?) : this(null, null, null, rgb)
    constructor(r: Expr?, g: Expr?, b: Expr?) : this(r, g, b, null)

    override val shape get() = ArgType.SP_COLOR
}

/** ARRAYCOPY。spec: SpCopyArrayArgument.cs:6-16 */
class SpCopyArrayArgument(val varName1: Expr?, val varName2: Expr?) : Argument() {
    override val shape get() = ArgType.SP_COPY_ARRAY
}

/** DT_COLUMN_OPTIONS。spec: SpDtColumnOptions.cs:6-27 */
class SpDtColumnOptions(
    val dt: Expr?,
    val column: Expr?,
    val options: List<DTOption>,
    val values: List<Expr?>,
) : Argument() {
    /** spec: SpDtColumnOptions.cs:8-11 —— 目前只有 Default 一种选项 */
    enum class DTOption { DEFAULT }

    override val shape get() = ArgType.SP_DT_COLUMN_OPTIONS
}

/** FOR / REPEAT 的循环参数。spec: SpForNextArgment.cs:7-23 */
class SpForNextArgument(
    val cnt: VarRef,
    val start: Expr?,
    val end: Expr?,
    val step: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_FOR_NEXT
}

/** PRINTCPERLINE / SAVENOS / HTML_TAGSPLIT 的结果写入目标。spec: SpGetIntArgument.cs:6-13 */
class SpGetIntArgument(val dest: VarRef) : Argument() {
    override val shape get() = ArgType.SP_GETINT
}

/** HTML_PRINT / PLAYSOUND 等。spec: SpHtmlPrint.cs:6-16 */
class SpHtmlPrint(val str: Expr?, val opt: Expr?) : Argument() {
    override val shape get() = ArgType.SP_HTML_PRINT

    init {
        // spec: ArgumentParser.cs:203-209 —— 两个参数均为常量时缓存
        val s = (str as? com.eratw.port.core.expr.StrLiteral)?.value
        val o = (opt as? com.eratw.port.core.expr.IntLiteral)?.value
        if (s != null && (opt == null || o != null)) {
            isConst = true
            constStr = s
            constInt = o ?: 0L
        }
    }
}

/** HTML_TAGSPLIT。spec: SpHtmlSplitArgument.cs:7-21 */
class SpHtmlSplitArgument(
    val targetStr: Expr?,
    val destVarName: String,
    val num: VarRef?,
) : Argument() {
    override val shape get() = ArgType.SP_HTMLSPLIT
}

/** INPUT/INPUTS/BINPUT 等。spec: SpInputsArgument.cs:6-19 */
class SpInputsArgument(val def: Expr?, val mouse: Expr?, val canSkip: Expr?) : Argument() {
    override val shape get() = ArgType.SP_INPUTS
}

/** POWER。spec: SpPowerArgument.cs:6-20 */
class SpPowerArgument(val dest: VarRef, val x: Expr?, val y: Expr?) : Argument() {
    override val shape get() = ArgType.SP_POWER
}

/** 带 px 标记的整数参数。spec: MixedIntegerExprTerm.cs:5-9 */
class MixedIntegerExprTerm(val num: Expr?, val isPx: Boolean)

/** PRINT_IMG。spec: SpPrintImgArgument.cs:6-22 */
class SpPrintImgArgument(
    val name: Expr?,
    val nameb: Expr?,
    val namem: Expr?,
    val param: List<MixedIntegerExprTerm>?,
) : Argument() {
    override val shape get() = ArgType.SP_PRINT_IMG
}

/** PRINT_RECT / PRINT_SPACE。spec: SpPrintShapeArgument.cs:3-11 */
class SpPrintShapeArgument(val param: List<MixedIntegerExprTerm>) : Argument() {
    override val shape get() = ArgType.SP_PRINT_RECT
}

/** PRINTV 家族（逐项输出表达式）。spec: SpPrintVArgument.cs:6-14 */
class SpPrintVArgument(val terms: List<Expr>) : Argument() {
    override val shape get() = ArgType.SP_PRINTV
}

/** SAVEDATA。spec: SpSaveDataArgument.cs:6-16 */
class SpSaveDataArgument(val target: Expr?, val strExpression: Expr?) : Argument() {
    override val shape get() = ArgType.SP_SAVEDATA
}

/** SAVEVAR。spec: SpSaveVarArgument.cs:8-20 */
class SpSaveVarArgument(
    val term: Expr?,
    val savMes: Expr?,
    /** 被保存的变量名（原版为 VariableToken[]，符号绑定属 M2）。 */
    val varNames: List<String>,
) : Argument() {
    override val shape get() = ArgType.SP_SAVEVAR
}

/** SET 的标量赋值。spec: SpSetArgument.cs:6-20 */
class SpSetArgument(val dest: VarRef, val term: Expr?) : Argument() {
    override val shape get() = ArgType.SP_SET

    /** 原版 AddConst：+= / -= 的常量增量形式。spec: ArgumentParser.cs:1073-1090 */
    var addConst: Boolean = false
}

/** SET 的数组赋值。spec: SpSetArrayArgument.cs:7-30 */
class SpSetArrayArgument(
    val dest: VarRef,
    val terms: List<Expr?>,
    val constIntList: LongArray? = null,
    val constStrList: Array<String>? = null,
) : Argument() {
    override val shape get() = ArgType.SP_SET_ARRAY
}

/** SORTCHARA。spec: SpSortcharaArgument.cs:6-16 */
class SpSortcharaArgument(val sortKey: VarRef, val sortOrder: SortOrder) : Argument() {
    override val shape get() = ArgType.SP_SORTCHARA
}

/** SPLIT。spec: SpSplitArgument.cs:7-24 */
class SpSplitArgument(
    val targetStr: Expr?,
    val split: Expr?,
    val destVarName: String,
    val num: VarRef?,
) : Argument() {
    override val shape get() = ArgType.SP_SPLIT
}

/** SWAPCHARA / COPYCHARA / TWAIT / TOOLTIP_SETCOLOR。spec: SpSwapCharaArgument.cs:6-16 */
class SpSwapCharaArgument(val x: Expr?, val y: Expr?) : Argument() {
    override val shape get() = ArgType.SP_SWAP
}

/** SWAP。spec: SpSwapVarArgument.cs:6-16 */
class SpSwapVarArgument(val var1: VarRef, val var2: VarRef) : Argument() {
    override val shape get() = ArgType.SP_SWAPVAR
}

/** TINPUT / TINPUTS。spec: SpTInputsArgument.cs:6-28 */
class SpTInputsArgument(
    val time: Expr?,
    val def: Expr?,
    val disp: Expr?,
    val timeout: Expr?,
    val mouse: Expr?,
    val canSkip: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_TINPUTS
}

/** TIMES。spec: SpTimesArgument.cs:6-16 */
class SpTimesArgument(val dest: VarRef, val doubleValue: Double) : Argument() {
    override val shape get() = ArgType.SP_TIMES
}

/** VARSET。spec: SpVarSetArgument.cs:6-23 */
class SpVarSetArgument(
    val dest: VarRef,
    val term: Expr?,
    val start: Expr?,
    val end: Expr?,
) : Argument() {
    override val shape get() = ArgType.SP_VAR_SET
}

/** VARSIZE。spec: SpVarsizeArgument.cs:6-13 */
class SpVarsizeArgument(val varName: String) : Argument() {
    override val shape get() = ArgType.SP_VAR
}

/** TOOLTIP_SETDELAY 等 STR_DOUBLE 参数。spec: StrDoubleArgument.cs:6-16 */
class StrDoubleArgument(val term: Expr?, val doubleValue: Double) : Argument() {
    override val shape get() = ArgType.STR_DOUBLE
}

/** HTML_PRINT 的另一形态（行尾控制）。spec: HTML_PRINTArgument.cs:6-16 */
class HtmlPrintArgument(val term: Expr?, val lineEnd: Expr?) : Argument() {
    override val shape get() = ArgType.SP_HTML_PRINT
}
