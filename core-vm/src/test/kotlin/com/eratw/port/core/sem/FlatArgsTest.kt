package com.eratw.port.core.sem

import com.eratw.port.core.code.ConstantPool
import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.FlatStatements
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgValueType
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WhileStmt
import com.eratw.port.core.stmt.WendStmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * T2-64 波次 C lane A：[FlatArgs] 的槽序对偶闸（契约 CONTRACT-T2-64-C §2.1 / §3.A）。
 *
 * ## 判据来源
 *
 * 造流走真实编码器 `FlatStatements.encode`（**不手工拼槽**，否则测的是测试自己）；
 * 读数走 [FlatArgs]，期望值取自**原始 Argument 对象的字段**（逐字段手写，不改用
 * `FlatStatements.statementAt` 的还原结果 —— 那是被测代码的另一条读法，两边同错才
 * 会漏网）。
 *
 * 参数记录起点 = `headAt(i) + 2 + prefixSlots(语句)`：`prefixSlots` 是**手抄**
 * `FlatStatements.encode` 里 `when (s)` 各分支写槽数的独立判定表，等于再验一次
 * 「语句专属字段」段宽；起点槽必须正好是期望 ArgTag，偏移错了当场红。
 *
 * ## 契约读面之外的形态
 *
 * 负责人 2026-10-28 批准追加的 5 个读面（`expressions` / `l64ListOpt` / `strListOpt` /
 * `mixedListOpt` / `dtOptions`）在 §7 单独成组测：`NONE_REF → null`、`expressions` 的类型槽
 * 被跳过、DTOption 线值越界抛错、`dtOptions` 之后必须紧接 `exprOptList` 读 values 段。
 * 区间终点 `endAt` 的缺省行为与「三参构造只覆盖前半记录」也在这组里。
 */
class FlatArgsTest {

    // ------------------------------------------------------------------
    // 造流 / 读数支架
    // ------------------------------------------------------------------

    /** `FlatStatements` → `FlatStmtSource` 的只转发适配（生产实现是 `Program` 里那个）。 */
    private class FlatSource(private val flat: FlatStatements) : FlatStmtSource {
        override fun opAt(index: Int): Int = flat.opAt(index)

        override fun headAt(index: Int): Int = flat.headAt(index)

        override fun argStreamBase(): IntArray = flat.stream

        override fun exprAt(poolIdx: Int): Expr? =
            if (poolIdx < 0 || poolIdx >= flat.poolSize + flat.extraExprs.size) null else flat.exprAt(poolIdx)

        override fun stringAt(poolIdx: Int): String = flat.strings[poolIdx]

        override fun statementAt(index: Int): Statement = flat.statementAt(index)
    }

    /** 手工槽流（畸形记录路径专用）：槽内容由测试直接给，不受编码器约束。 */
    private class RawSource(
        private val slots: IntArray,
        private val exprs: Map<Int, Expr> = emptyMap(),
        private val strings: Map<Int, String> = emptyMap(),
    ) : FlatStmtSource {
        override fun opAt(index: Int): Int = error("RawSource.opAt 不参与本用例")

        override fun headAt(index: Int): Int = 0

        override fun argStreamBase(): IntArray = slots

        override fun exprAt(poolIdx: Int): Expr? = exprs[poolIdx]

        override fun stringAt(poolIdx: Int): String = strings[poolIdx] ?: error("RawSource 无字符串 $poolIdx")

        override fun statementAt(index: Int): Statement = error("RawSource.statementAt 不参与本用例")
    }

    private fun encodeAll(statements: List<Statement>, pool: List<Expr> = emptyList()): FlatStatements =
        FlatStatements.encode(statements, pool)

    /**
     * 语句专属字段的槽数（`[StmtTag][op]` 之后、参数记录之前）——
     * **逐条手抄** `FlatStatements.encode` 的 `when (s)` 分支，故意不复用被测代码。
     */
    private fun prefixSlots(s: Statement): Int = when (s) {
        is PrintStmt -> 3 // argType / printBits / flag
        is AssignStmt -> 2 // dest + assignOp（AssignStmt.op.ordinal；执行层不消费但槽必须跳过）
        is JumpStmt -> 1 // label
        is CallStmt -> 1 // label
        is CallFStmt -> 1 // functionName
        is CallSharpStmt -> 0
        is MethodStmt -> 1 // methodName
        is IfStmt -> 2 // condition / single
        is ElseIfStmt -> 1 // condition
        is SelectCaseStmt -> 1 // target
        is CaseStmt -> 1 + 4 * s.cases.size // 计数 + 每项（type / left / right / operator）
        is WhileStmt -> 1 // condition
        is LoopStmt -> 1 // condition
        is ReturnStmt -> 1 + s.values.size // 计数 + n×表达式槽
        is ReturnFStmt -> 1 // value
        is FuncLabelStmt -> 1 + 1 + s.paramNames.size // name + 计数 + n×字符串槽
        is GotoLabelStmt -> 1 // name
        is SharpStmt -> 1 + 1 + 2 * s.values.size + 1 + 1 // keyword + 计数 + n×2 槽 + name + isString
        is DataStmt -> 1 // text
        else -> 0 // 纯 InstructionStmt / PRINT_DATA / VAR_DECL / FOR / NEXT / ... 无专属字段
    }

    /** 定位第 [index] 条语句的参数记录起点并构造游标（起点槽必须正好是 [expectedTag]）。 */
    private fun argsAt(flat: FlatStatements, index: Int, s: Statement, expectedTag: Int): FlatArgs {
        val start = flat.headAt(index) + 2 + prefixSlots(s)
        assertEquals(
            expectedTag, flat.stream[start],
            "[$index] ${s.javaClass.simpleName} 参数记录起点槽应是 ArgTag（prefixSlots=${prefixSlots(s)}）",
        )
        return argsOf(flat, start, index)
    }

    /**
     * 正式调用口径：`end` 取 [FlatStmtSource.endAt]（缺省实现 = 下一条语句起点）。
     * 测试里凡是手工定位的 `start` 都走这里，和 lane D 的产品调用点同形。
     */
    private fun argsOf(flat: FlatStatements, start: Int, index: Int = 0): FlatArgs {
        val src = FlatSource(flat)
        return FlatArgs(src, start, src.endAt(index))
    }

    /** 单条语句的最小流：`[StmtTag=INSTRUCTION][op][参数记录...]`（prefixSlots = 0）。 */
    private fun singleArgStream(a: Argument): FlatStatements =
        encodeAll(listOf(InstructionStmt(FunctionCode.REUSELASTLINE, a, 1, "raw")))

    private fun vref(name: String = "A", vararg idx: Int): VarRef =
        VarRef(name, idx.map { IntLiteral(it.toLong()) }, operandType = OperandType.INT)

    private fun il(v: Long) = IntLiteral(v)

    private fun sl(s: String) = StrLiteral(s)

    private fun spec(name: String): PrintSpec =
        PrintSpec.parse(FunctionCode.valueOf(name)) ?: error("PrintSpec.parse 失败: $name")

    /** 期望 ArgTag（手抄 `FlatStatements.Encoder.argument` 各分支的标签）。 */
    private fun tagOf(a: Argument): Int = when (a) {
        is VoidArgument -> ArgTag.VOID
        is ExpressionArgument -> ArgTag.EXPRESSION
        is ExpressionArrayArgument -> ArgTag.EXPRESSION_ARRAY
        is ExpressionsArgument -> ArgTag.EXPRESSIONS
        is IntAsignArgument -> ArgTag.INT_ASIGN
        is StrAsignArgument -> ArgTag.STR_ASIGN
        is BitArgument -> ArgTag.BIT
        is CaseArgument -> ArgTag.CASE
        is MethodArgument -> ArgTag.METHOD
        is PrintDataArgument -> ArgTag.PRINT_DATA
        is RefArgument -> ArgTag.REF
        is StrDataArgument -> ArgTag.STR_DATA
        is SpArrayControlArgument -> ArgTag.SP_ARRAY_CONTROL
        is SpArrayShiftArgument -> ArgTag.SP_ARRAY_SHIFT
        is SpArraySortArgument -> ArgTag.SP_ARRAY_SORT
        is SpBarArgument -> ArgTag.SP_BAR
        is SpButtonArgument -> ArgTag.SP_BUTTON
        is SpCVarSetArgument -> ArgTag.SP_CVAR_SET
        is SpCallArgument -> ArgTag.SP_CALL
        is SpCallFArgument -> ArgTag.SP_CALLF
        is SpCallSharpArgument -> ArgTag.SP_CALL_SHARP
        is SpColorArgument -> ArgTag.SP_COLOR
        is SpCopyArrayArgument -> ArgTag.SP_COPY_ARRAY
        is SpDtColumnOptions -> ArgTag.SP_DT_COLUMN_OPTIONS
        is SpForNextArgument -> ArgTag.SP_FOR_NEXT
        is SpGetIntArgument -> ArgTag.SP_GET_INT
        is SpHtmlPrint -> ArgTag.SP_HTML_PRINT
        is SpHtmlSplitArgument -> ArgTag.SP_HTML_SPLIT
        is SpInputsArgument -> ArgTag.SP_INPUTS
        is SpPowerArgument -> ArgTag.SP_POWER
        is SpPrintImgArgument -> ArgTag.SP_PRINT_IMG
        is SpPrintShapeArgument -> ArgTag.SP_PRINT_SHAPE
        is SpPrintVArgument -> ArgTag.SP_PRINT_V
        is SpSaveDataArgument -> ArgTag.SP_SAVE_DATA
        is SpSaveVarArgument -> ArgTag.SP_SAVE_VAR
        is SpSetArgument -> ArgTag.SP_SET
        is SpSetArrayArgument -> ArgTag.SP_SET_ARRAY
        is SpSortcharaArgument -> ArgTag.SP_SORTCHARA
        is SpSplitArgument -> ArgTag.SP_SPLIT
        is SpSwapCharaArgument -> ArgTag.SP_SWAP_CHARA
        is SpSwapVarArgument -> ArgTag.SP_SWAP_VAR
        is SpTInputsArgument -> ArgTag.SP_TINPUTS
        is SpTimesArgument -> ArgTag.SP_TIMES
        is SpVarSetArgument -> ArgTag.SP_VAR_SET
        is SpVarsizeArgument -> ArgTag.SP_VARSIZE
        is StrDoubleArgument -> ArgTag.STR_DOUBLE
        else -> error("未知参数形态: ${a.javaClass.name}")
    }

    // ------------------------------------------------------------------
    // 逐字段判据
    // ------------------------------------------------------------------

    private fun checkArg(a: Argument, fa: FlatArgs, label: String) {
        assertEquals(tagOf(a), fa.open(), "$label ArgTag")
        assertEquals(a.isConst, fa.isConst, "$label isConst")
        assertEquals(a.constStr, fa.constStr, "$label constStr")
        assertEquals(a.constInt, fa.constInt, "$label constInt")
        when (a) {
            is VoidArgument -> Unit
            is ExpressionArgument -> assertEquals(a.term, fa.expr(), "$label term")
            is ExpressionArrayArgument -> assertEquals(a.terms, fa.exprOptList(), "$label terms")
            is ExpressionsArgument -> assertEquals(a.terms, fa.expressions(), "$label terms（类型槽丢弃）")
            is IntAsignArgument -> {
                assertEquals(a.name, fa.str(), "$label name")
                assertEquals(a.lengths.toList(), fa.intArray().toList(), "$label lengths")
                assertEquals(a.exp, fa.expr(), "$label exp")
            }
            is StrAsignArgument -> {
                assertEquals(a.name, fa.str(), "$label name")
                assertEquals(a.lengths.toList(), fa.intArray().toList(), "$label lengths")
                assertEquals(a.value, fa.strOpt(), "$label value")
            }
            is BitArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.terms, fa.exprList(), "$label terms")
            }
            is CaseArgument -> expectCases(a.caseExps, fa.caseList(), "$label caseExps")
            is MethodArgument -> assertEquals(a.methodTerm, fa.expr(), "$label methodTerm")
            is PrintDataArgument -> assertEquals(a.dest, fa.varRefOpt(), "$label dest")
            is RefArgument -> {
                assertEquals(a.refName, fa.str(), "$label refName")
                assertEquals(a.srcName, fa.strOpt(), "$label srcName")
                assertEquals(a.srcTerm, fa.expr(), "$label srcTerm")
                assertEquals(a.refByName, fa.i32() != 0, "$label refByName")
            }
            is StrDataArgument -> assertEquals(a.dest, fa.varRefOpt(), "$label dest")
            is SpArrayControlArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.num1, fa.expr(), "$label num1")
                assertEquals(a.num2, fa.expr(), "$label num2")
            }
            is SpArrayShiftArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.num1, fa.expr(), "$label num1")
                assertEquals(a.num2, fa.expr(), "$label num2")
                assertEquals(a.num3, fa.expr(), "$label num3")
                assertEquals(a.num4, fa.expr(), "$label num4")
            }
            is SpArraySortArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.order, fa.sortOrder(), "$label order")
                assertEquals(a.num1, fa.expr(), "$label num1")
                assertEquals(a.num2, fa.expr(), "$label num2")
            }
            is SpBarArgument -> {
                assertEquals(a.value, fa.expr(), "$label value")
                assertEquals(a.max, fa.expr(), "$label max")
                assertEquals(a.length, fa.expr(), "$label length")
            }
            is SpButtonArgument -> {
                assertEquals(a.printStrTerm, fa.expr(), "$label printStrTerm")
                assertEquals(a.buttonWord, fa.expr(), "$label buttonWord")
            }
            is SpCVarSetArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.index, fa.expr(), "$label index")
                assertEquals(a.term, fa.expr(), "$label term")
                assertEquals(a.start, fa.expr(), "$label start")
                assertEquals(a.end, fa.expr(), "$label end")
            }
            is SpCallArgument -> {
                assertEquals(a.funcNameTerm, fa.expr(), "$label funcNameTerm")
                assertEquals(a.subNames, fa.exprOptList(), "$label subNames")
                assertEquals(a.rowArgs, fa.exprOptList(), "$label rowArgs")
            }
            is SpCallFArgument -> {
                assertEquals(a.funcNameTerm, fa.expr(), "$label funcNameTerm")
                assertEquals(a.subNames, fa.exprOptList(), "$label subNames")
                assertEquals(a.rowArgs, fa.exprOptList(), "$label rowArgs")
            }
            is SpCallSharpArgument -> {
                assertEquals(a.funcNameTerm, fa.expr(), "$label funcNameTerm")
                assertEquals(a.subNames, fa.exprOptList(), "$label subNames")
                assertEquals(a.rowArgs, fa.exprOptList(), "$label rowArgs")
            }
            is SpColorArgument -> {
                assertEquals(a.r, fa.expr(), "$label r")
                assertEquals(a.g, fa.expr(), "$label g")
                assertEquals(a.b, fa.expr(), "$label b")
                assertEquals(a.rgb, fa.expr(), "$label rgb")
            }
            is SpCopyArrayArgument -> {
                assertEquals(a.varName1, fa.expr(), "$label varName1")
                assertEquals(a.varName2, fa.expr(), "$label varName2")
            }
            is SpDtColumnOptions -> {
                assertEquals(a.dt, fa.expr(), "$label dt")
                assertEquals(a.column, fa.expr(), "$label column")
                assertEquals(a.options, fa.dtOptions(), "$label options")
                assertEquals(a.values, fa.exprOptList(), "$label values（options 段之后的 values 段）")
            }
            is SpForNextArgument -> {
                assertEquals(a.cnt, fa.varRefNN(), "$label cnt")
                assertEquals(a.start, fa.expr(), "$label start")
                assertEquals(a.end, fa.expr(), "$label end")
                assertEquals(a.step, fa.expr(), "$label step")
            }
            is SpGetIntArgument -> assertEquals(a.dest, fa.varRefNN(), "$label dest")
            is SpHtmlPrint -> {
                assertEquals(a.str, fa.expr(), "$label str")
                assertEquals(a.opt, fa.expr(), "$label opt")
            }
            is SpHtmlSplitArgument -> {
                assertEquals(a.targetStr, fa.expr(), "$label targetStr")
                assertEquals(a.destVarName, fa.str(), "$label destVarName")
                assertEquals(a.num, fa.varRefOpt(), "$label num")
            }
            is SpInputsArgument -> {
                assertEquals(a.def, fa.expr(), "$label def")
                assertEquals(a.mouse, fa.expr(), "$label mouse")
                assertEquals(a.canSkip, fa.expr(), "$label canSkip")
            }
            is SpPowerArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.x, fa.expr(), "$label x")
                assertEquals(a.y, fa.expr(), "$label y")
            }
            is SpPrintImgArgument -> {
                assertEquals(a.name, fa.expr(), "$label name")
                assertEquals(a.nameb, fa.expr(), "$label nameb")
                assertEquals(a.namem, fa.expr(), "$label namem")
                val param = fa.mixedListOpt()
                if (a.param == null) {
                    assertNull(param, "$label param 为 null")
                } else {
                    expectMixed(a.param, param ?: error("$label param 不应为 null"), "$label param")
                }
            }
            is SpPrintShapeArgument -> expectMixed(a.param, fa.mixedList(), "$label param")
            is SpPrintVArgument -> assertEquals(a.terms, fa.exprList(), "$label terms")
            is SpSaveDataArgument -> {
                assertEquals(a.target, fa.expr(), "$label target")
                assertEquals(a.strExpression, fa.expr(), "$label strExpression")
            }
            is SpSaveVarArgument -> {
                assertEquals(a.term, fa.expr(), "$label term")
                assertEquals(a.savMes, fa.expr(), "$label savMes")
                assertEquals(a.varNames, fa.strList(), "$label varNames")
            }
            is SpSetArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.term, fa.expr(), "$label term")
                assertEquals(a.addConst, fa.i32() != 0, "$label addConst")
            }
            is SpSetArrayArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.terms, fa.exprOptList(), "$label terms")
                assertEquals(a.constIntList?.toList(), fa.l64ListOpt()?.toList(), "$label constIntList")
                assertEquals(a.constStrList?.toList(), fa.strListOpt()?.toList(), "$label constStrList")
            }
            is SpSortcharaArgument -> {
                assertEquals(a.sortKey, fa.varRefNN(), "$label sortKey")
                assertEquals(a.sortOrder, fa.sortOrder(), "$label sortOrder")
            }
            is SpSplitArgument -> {
                assertEquals(a.targetStr, fa.expr(), "$label targetStr")
                assertEquals(a.split, fa.expr(), "$label split")
                assertEquals(a.destVarName, fa.str(), "$label destVarName")
                assertEquals(a.num, fa.varRefOpt(), "$label num")
            }
            is SpSwapCharaArgument -> {
                assertEquals(a.x, fa.expr(), "$label x")
                assertEquals(a.y, fa.expr(), "$label y")
            }
            is SpSwapVarArgument -> {
                assertEquals(a.var1, fa.varRefNN(), "$label var1")
                assertEquals(a.var2, fa.varRefNN(), "$label var2")
            }
            is SpTInputsArgument -> {
                assertEquals(a.time, fa.expr(), "$label time")
                assertEquals(a.def, fa.expr(), "$label def")
                assertEquals(a.disp, fa.expr(), "$label disp")
                assertEquals(a.timeout, fa.expr(), "$label timeout")
                assertEquals(a.mouse, fa.expr(), "$label mouse")
                assertEquals(a.canSkip, fa.expr(), "$label canSkip")
            }
            is SpTimesArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.doubleValue.toRawBits(), fa.f64().toRawBits(), "$label doubleValue")
            }
            is SpVarSetArgument -> {
                assertEquals(a.dest, fa.varRefNN(), "$label dest")
                assertEquals(a.term, fa.expr(), "$label term")
                assertEquals(a.start, fa.expr(), "$label start")
                assertEquals(a.end, fa.expr(), "$label end")
            }
            is SpVarsizeArgument -> assertEquals(a.varName, fa.str(), "$label varName")
            is StrDoubleArgument -> {
                assertEquals(a.term, fa.expr(), "$label term")
                assertEquals(a.doubleValue.toRawBits(), fa.f64().toRawBits(), "$label doubleValue")
            }
            else -> error("$label 未覆盖的参数形态: ${a.javaClass.name}")
        }
    }

    /** `CaseExpression` 无 equals：逐字段比。 */
    private fun expectCases(expected: List<CaseExpression>, actual: List<CaseExpression>, label: String) {
        assertEquals(expected.size, actual.size, "$label 条数")
        for (i in expected.indices) {
            assertEquals(expected[i].caseType, actual[i].caseType, "$label[$i] caseType")
            assertEquals(expected[i].leftTerm, actual[i].leftTerm, "$label[$i] leftTerm")
            assertEquals(expected[i].rightTerm, actual[i].rightTerm, "$label[$i] rightTerm")
            assertEquals(expected[i].operator, actual[i].operator, "$label[$i] operator")
        }
    }

    /** `MixedIntegerExprTerm` 无 equals：逐字段比。 */
    private fun expectMixed(expected: List<MixedIntegerExprTerm>, actual: List<MixedIntegerExprTerm>, label: String) {
        assertEquals(expected.size, actual.size, "$label 项数")
        for (i in expected.indices) {
            assertEquals(expected[i].num, actual[i].num, "$label[$i] num")
            assertEquals(expected[i].isPx, actual[i].isPx, "$label[$i] isPx")
        }
    }

    // ------------------------------------------------------------------
    // 1. 37 个 Statement 类：语句专属字段段宽 + 参数记录
    // ------------------------------------------------------------------

    @Test
    fun `37 个 Statement 类的参数记录分段与字段`() {
        val statements = listOf(
            InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(il(1)), 10, "raw"),
            PrintStmt(spec("PRINT"), ExpressionArgument(sl("hi")), 11, "raw"),
            PrintStmt(spec("PRINTV"), SpPrintVArgument(listOf(il(1), il(2))), 12, "raw"),
            PrintStmt(spec("PRINTLC"), ExpressionArgument(sl("x")), 13, "raw"),
            PrintStmt(spec("PRINTFORMS"), ExpressionArgument(sl("f")), 14, "raw"),
            PrintDataStmt(FunctionCode.PRINTDATA, VoidArgument(), 15, "raw"),
            AssignStmt(vref("A", 0), OperatorCode.PLUS, ExpressionArgument(il(3)), 16, "raw"),
            JumpStmt(FunctionCode.JUMP, "LABEL", VoidArgument(), 17, "raw"),
            JumpStmt(FunctionCode.JUMP, null, VoidArgument(), 18, "raw"),
            CallStmt(FunctionCode.CALL, "FN", ExpressionArrayArgument(listOf(il(1), null)), 19, "raw"),
            CallFStmt("FN2", ExpressionArgument(il(9)), 20, "raw", FunctionCode.CALLF),
            CallSharpStmt(SpCallSharpArgument(sl("S"), listOf(il(1)), listOf(null, il(2))), 21, "raw"),
            MethodStmt("M1", MethodArgument(il(7)), 22, "raw"),
            VarDeclStmt(FunctionCode.VARI, IntAsignArgument("LOC", intArrayOf(1, 2), il(0)), 23, "raw"),
            IfStmt(il(1), ExpressionArgument(il(1)), 24, "raw"),
            IfStmt(il(0), ExpressionArgument(il(0)), 25, "raw", single = true),
            ElseIfStmt(il(2), ExpressionArgument(il(2)), 26, "raw"),
            ElseStmt(VoidArgument(), 27, "raw"),
            EndIfStmt(VoidArgument(), 28, "raw"),
            SelectCaseStmt(il(5), ExpressionArgument(il(5)), 29, "raw"),
            CaseStmt(
                listOf(
                    CaseExpression(CaseExpression.CaseType.NORMAL, il(1), null, OperatorCode.EQUAL),
                    CaseExpression(CaseExpression.CaseType.TO, il(1), il(9), OperatorCode.NULL),
                    CaseExpression(CaseExpression.CaseType.IS, il(3), null, OperatorCode.GREATER),
                ),
                CaseArgument(emptyList()),
                30,
                "raw",
            ),
            CaseElseStmt(VoidArgument(), 31, "raw"),
            EndSelectStmt(VoidArgument(), 32, "raw"),
            ForStmt(FunctionCode.FOR, SpForNextArgument(vref("LOOP"), il(0), il(9), il(1)), 33, "raw"),
            NextStmt(VoidArgument(), 34, "raw"),
            RepeatStmt(SpForNextArgument(vref("R"), il(1), il(2), il(1)), 35, "raw"),
            RendStmt(VoidArgument(), 36, "raw"),
            WhileStmt(il(1), ExpressionArgument(il(1)), 37, "raw"),
            WendStmt(VoidArgument(), 38, "raw"),
            DoStmt(VoidArgument(), 39, "raw"),
            LoopStmt(il(1), ExpressionArgument(il(1)), 40, "raw"),
            LoopStmt(null, VoidArgument(), 41, "raw"),
            CatchStmt(VoidArgument(), 42, "raw"),
            EndCatchStmt(VoidArgument(), 43, "raw"),
            DataListStmt(FunctionCode.DATALIST, VoidArgument(), 44, "raw"),
            EndBlockStmt(FunctionCode.ENDDATA, VoidArgument(), 45, "raw"),
            FlowStmt(FunctionCode.QUIT, VoidArgument(), 46, "raw"),
            ReturnStmt(listOf(il(1), null, il(3)), VoidArgument(), 47, "raw"),
            ReturnFStmt(il(2), ExpressionArgument(il(2)), 48, "raw", FunctionCode.RETURNFORM),
            FuncLabelStmt("FUNC", listOf("A", "B"), 49, "raw"),
            GotoLabelStmt("GOTO", 50, "raw"),
            SharpStmt(
                keyword = "DIM",
                values = listOf(1L, Long.MAX_VALUE, -1L),
                name = "V",
                isString = false,
                line = 51,
                raw = "raw",
            ),
            SharpStmt(keyword = "LOCALSIZE", values = listOf(16L), line = 52, raw = "raw"),
            DataStmt(FunctionCode.DATA, "文本", VoidArgument(), 53, "raw"),
        )
        // StmtTag 全集（37 个）每类至少一条
        assertEquals(37, statements.map { it.javaClass }.toSet().size, "语句类覆盖数")
        val flat = encodeAll(statements)
        for (i in statements.indices) {
            val s = statements[i]
            val fa = argsAt(flat, i, s, tagOf(s.args))
            checkArg(s.args, fa, "[$i] ${s.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // 2. 全部参数形态（含 46 个 Argument 类）逐字段对偶
    // ------------------------------------------------------------------

    @Test
    fun `全部参数形态逐字段对偶`() {
        val shapes: List<Pair<String, Argument>> = listOf(
            "VOID" to VoidArgument(),
            "EXPRESSION null" to ExpressionArgument(null),
            "EXPRESSION" to ExpressionArgument(il(1)),
            "EXPRESSION_ARRAY 空" to ExpressionArrayArgument(emptyList()),
            "EXPRESSION_ARRAY" to ExpressionArrayArgument(listOf(il(1), null, sl("s"))),
            "EXPRESSIONS" to ExpressionsArgument(
                listOf(ArgValueType.INT, null),
                listOf(il(1), null),
            ),
            "INT_ASIGN 空维度" to IntAsignArgument("N", intArrayOf(), null),
            "INT_ASIGN 维度" to IntAsignArgument("N2", intArrayOf(2, 3), il(5)),
            "STR_ASIGN" to StrAsignArgument("S", intArrayOf(1), "初值"),
            "STR_ASIGN null" to StrAsignArgument("S2", intArrayOf(1, 2, 3), null),
            "BIT" to BitArgument(vref("B"), listOf(il(1), il(2))),
            "CASE 空" to CaseArgument(emptyList()),
            "METHOD" to MethodArgument(null),
            "PRINT_DATA" to PrintDataArgument(null),
            "PRINT_DATA dest" to PrintDataArgument(vref("P")),
            "REF" to RefArgument("RN", null, null, false),
            "REF 全" to RefArgument("RN2", "SRC", il(4), true),
            "STR_DATA" to StrDataArgument(vref("SD")),
            "SP_ARRAY_CONTROL" to SpArrayControlArgument(vref("AC"), null, il(2)),
            "SP_ARRAY_SHIFT" to SpArrayShiftArgument(vref("AS"), il(1), il(2), il(3), null),
            "SP_ARRAY_SORT" to SpArraySortArgument(vref("SO"), SortOrder.ASCENDING, il(1), null),
            "SP_ARRAY_SORT DESC" to SpArraySortArgument(vref("SOD"), SortOrder.DESENDING, null, null),
            "SP_BAR" to SpBarArgument(il(1), il(9), null),
            "SP_BUTTON" to SpButtonArgument(sl("btn"), il(3)),
            "SP_CVAR_SET" to SpCVarSetArgument(vref("CV"), il(1), il(2), il(3), il(4)),
            "SP_CALL" to SpCallArgument(sl("L"), listOf(il(1)), listOf(null)),
            "SP_CALLF" to SpCallFArgument(null, emptyList(), emptyList()),
            "SP_CALL_SHARP" to SpCallSharpArgument(sl("CS"), listOf(il(1)), listOf(null)),
            "SP_COLOR rgb" to SpColorArgument(il(0xFF)),
            "SP_COLOR 分量" to SpColorArgument(il(1), il(2), il(3)),
            "SP_COPY_ARRAY" to SpCopyArrayArgument(il(1), il(2)),
            "SP_DT_COLUMN_OPTIONS" to SpDtColumnOptions(
                il(1), null, listOf(SpDtColumnOptions.DTOption.DEFAULT), listOf(sl("v"), null),
            ),
            "SP_FOR_NEXT" to SpForNextArgument(vref("F"), null, null, null),
            "SP_GET_INT" to SpGetIntArgument(vref("GI")),
            "SP_HTML_PRINT" to SpHtmlPrint(sl("h"), null),
            "SP_HTML_SPLIT" to SpHtmlSplitArgument(sl("t"), "DV", vref("NUM")),
            "SP_INPUTS" to SpInputsArgument(il(1), il(0), null),
            "SP_POWER" to SpPowerArgument(vref("PW"), il(1), il(2)),
            "SP_PRINT_IMG" to SpPrintImgArgument(
                sl("n"), null, sl("m"),
                listOf(MixedIntegerExprTerm(il(1), true), MixedIntegerExprTerm(null, false)),
            ),
            "SP_PRINT_IMG null 表" to SpPrintImgArgument(sl("n2"), il(1), null, null),
            "SP_PRINT_SHAPE 空" to SpPrintShapeArgument(emptyList()),
            "SP_PRINT_SHAPE" to SpPrintShapeArgument(listOf(MixedIntegerExprTerm(il(2), false))),
            "SP_PRINT_V" to SpPrintVArgument(listOf(il(1), il(2))),
            "SP_SAVE_DATA" to SpSaveDataArgument(il(1), sl("s")),
            "SP_SAVE_VAR" to SpSaveVarArgument(il(1), sl("m"), listOf("A", "B")),
            "SP_SET" to SpSetArgument(vref("SET"), il(1)).apply { addConst = false },
            "SP_SET addConst" to SpSetArgument(vref("SET2"), il(2)).apply { addConst = true },
            "SP_SET_ARRAY" to SpSetArrayArgument(
                vref("SA"), listOf(il(1), null), longArrayOf(10L, -20L), arrayOf("x", "y"),
            ),
            "SP_SET_ARRAY null 常量表" to SpSetArrayArgument(vref("SB"), emptyList(), null, null),
            "SP_SORTCHARA" to SpSortcharaArgument(vref("SK"), SortOrder.UNDEF),
            "SP_SPLIT" to SpSplitArgument(sl("t"), sl(","), "DN", null),
            "SP_SWAP_CHARA" to SpSwapCharaArgument(il(1), il(2)),
            "SP_SWAP_VAR" to SpSwapVarArgument(vref("V1"), vref("V2")),
            "SP_TINPUTS" to SpTInputsArgument(il(1), il(2), il(3), il(4), il(5), il(6)),
            "SP_TIMES" to SpTimesArgument(vref("T"), 1.5),
            "SP_TIMES 位精确" to SpTimesArgument(vref("T2"), -0.0),
            "SP_VAR_SET" to SpVarSetArgument(vref("VS"), il(1), il(2), il(3)),
            "SP_VARSIZE" to SpVarsizeArgument("VN"),
            "STR_DOUBLE" to StrDoubleArgument(il(1), 2.25),
        )
        val statements = shapes.mapIndexed { i, (_, a) ->
            InstructionStmt(FunctionCode.REUSELASTLINE, a, 200 + i, "raw")
        }
        assertEquals(shapes.map { it.second.javaClass }.toSet().size, 46, "Argument 形态覆盖数")
        val flat = encodeAll(statements)
        for (i in shapes.indices) {
            val (label, a) = shapes[i]
            checkArg(a, argsAt(flat, i, statements[i], tagOf(a)), "[$i] $label")
        }
    }

    // ------------------------------------------------------------------
    // 3. 常量载荷三槽（str 下标 + long lo + long hi）
    // ------------------------------------------------------------------

    @Test
    fun `常量载荷三槽 - 字符串下标与 lo_hi`() {
        val arg = ExpressionArgument(il(7)).apply {
            isConst = true
            constStr = "载荷"
            constInt = 0x1_2345_6789L
        }
        val flat = singleArgStream(arg)
        // 槽序：[tag][header=0b11][strIdx][lo][hi][term]，逐槽直查造出的流
        assertEquals(ArgTag.EXPRESSION, flat.stream[2])
        assertEquals(0x3, flat.stream[3], "header = bit0(isConst) | bit1(有载荷)")
        assertEquals(0, flat.stream[4], "载荷第 1 槽 = 字符串旁表下标")
        assertEquals(0x23456789, flat.stream[5], "载荷第 2 槽 = constInt 低 32 位")
        assertEquals(0x1, flat.stream[6], "载荷第 3 槽 = constInt 高 32 位")
        assertEquals(0, flat.stream[7], "载荷之后才是形态字段（term = 表达式池下标）")
        assertEquals(8, flat.stream.size, "记录总长 = [tag][header] + 载荷 3 槽 + 1 项")

        val fa = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.EXPRESSION, fa.open())
        assertEquals(true, fa.isConst)
        assertEquals("载荷", fa.constStr)
        assertEquals(0x1_2345_6789L, fa.constInt)
        assertEquals(il(7), fa.expr(), "open 必须消费掉 3 槽载荷，游标落在形态字段第 1 槽")
    }

    @Test
    fun `常量载荷 - 负值与仅字符串位`() {
        // constInt 最高位为 1（lo/hi 都要符号正确地拼回来）
        val neg = ExpressionArgument(il(1)).apply { isConst = true; constInt = -2L }
        val faNeg = FlatArgs(FlatSource(singleArgStream(neg)), 2)
        assertEquals(ArgTag.EXPRESSION, faNeg.open())
        assertEquals(-2L, faNeg.constInt)
        assertEquals(true, faNeg.isConst)
        assertEquals(il(1), faNeg.expr())

        // 仅 constStr → bit1 置位而 bit0 不置位（hasPayload 判定不只 isConst）
        val strOnly = ExpressionArgument(sl("S")).apply { constStr = "C" }
        val flat = singleArgStream(strOnly)
        assertEquals(0x2, flat.stream[3], "仅 constStr ⇒ header 只有 bit1")
        val faStr = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.EXPRESSION, faStr.open())
        assertEquals(false, faStr.isConst)
        assertEquals("C", faStr.constStr)
        assertEquals(0L, faStr.constInt)
        assertEquals(sl("S"), faStr.expr())

        // isConst 且载荷全空（"" / 0L）：位必须保真，载荷 3 槽照写
        val zero = VoidArgument().apply { isConst = true }
        val flatZero = singleArgStream(zero)
        assertEquals(0x3, flatZero.stream[3])
        val faZero = FlatArgs(FlatSource(flatZero), 2)
        assertEquals(ArgTag.VOID, faZero.open())
        assertEquals(true, faZero.isConst)
        assertEquals("", faZero.constStr)
        assertEquals(0L, faZero.constInt)
    }

    @Test
    fun `open 可重复调用 - 每次都从头读`() {
        val arg = ExpressionArgument(il(9)).apply { isConst = true; constInt = 5L }
        val fa = FlatArgs(FlatSource(singleArgStream(arg)), 2)
        assertEquals(ArgTag.EXPRESSION, fa.open())
        assertEquals(il(9), fa.expr())
        assertEquals(ArgTag.EXPRESSION, fa.open(), "二次 open 复位游标")
        assertEquals(5L, fa.constInt)
        assertEquals(il(9), fa.expr())
    }

    // ------------------------------------------------------------------
    // 4. -1 = null 槽
    // ------------------------------------------------------------------

    @Test
    fun `null 槽一律编码为 -1`() {
        // 表达式槽：ExpressionArgument(null) 的形态字段就是 -1
        val flatExpr = singleArgStream(ExpressionArgument(null))
        assertEquals(NONE_REF, flatExpr.stream[4], "null 表达式 = -1（槽 2 = ArgTag、槽 3 = header）")
        val faExpr = FlatArgs(FlatSource(flatExpr), 2)
        faExpr.open()
        assertNull(faExpr.expr())
        assertThrows<ErbcDecodeException>("exprNN 不许把 -1 当结点") { faExpr.varRefNN() }

        // varRefOpt：PrintDataArgument(null)
        val flatDest = singleArgStream(PrintDataArgument(null))
        assertEquals(NONE_REF, flatDest.stream[4])
        val faDest = FlatArgs(FlatSource(flatDest), 2)
        assertEquals(ArgTag.PRINT_DATA, faDest.open())
        assertNull(faDest.varRefOpt())

        // 列表项为空：ExpressionArrayArgument(terms = [expr, null]) 的计数后第 2 项是 -1
        val flatList = singleArgStream(ExpressionArrayArgument(listOf(il(1), null)))
        assertEquals(2, flatList.stream[4], "列表计数")
        assertEquals(NONE_REF, flatList.stream[6], "第 2 项 = -1")
        val faList = FlatArgs(FlatSource(flatList), 2)
        faList.open()
        assertEquals(listOf<Expr?>(il(1), null), faList.exprOptList())

        // 语句级 null：LOOP 的 condition 可空（槽 2 = condition，槽 3 = 参数记录起点）
        val flatLoop = encodeAll(listOf(LoopStmt(null, VoidArgument(), 1, "raw")))
        assertEquals(NONE_REF, flatLoop.stream[2], "LOOP 的无条件形态写 -1")
        val faLoop = FlatArgs(FlatSource(flatLoop), flatLoop.headAt(0) + 2 + 1)
        assertEquals(ArgTag.VOID, faLoop.open())
    }

    // ------------------------------------------------------------------
    // 5. long / double 载荷高低位
    // ------------------------------------------------------------------

    @Test
    fun `double 载荷按 raw bits 的 lo_hi 写读`() {
        val nanBits = 0x7FF8_0000_0000_0001L
        val flat = singleArgStream(SpTimesArgument(vref("T"), Double.fromBits(nanBits)))
        // 槽序：[tag][op][SP_TIMES][header=0][dest][lo][hi]
        assertEquals(ArgTag.SP_TIMES, flat.stream[2])
        assertEquals(0, flat.stream[3], "SpTimesArgument 不折叠常量 ⇒ header 无载荷位")
        assertEquals(0x0000_0001, flat.stream[5], "raw bits 低位槽")
        assertEquals(0x7FF8_0000, flat.stream[6], "raw bits 高位槽")
        val fa = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.SP_TIMES, fa.open())
        assertEquals(vref("T"), fa.varRefNN())
        assertEquals(nanBits, fa.f64().toRawBits(), "NaN 载荷位精确往返")
    }

    @Test
    fun `double 与 long 高低位逐槽核对`() {
        val value = 1.5 // bits = 0x3FF8000000000000
        val flat = singleArgStream(SpTimesArgument(vref("T"), value))
        val header = flat.stream[3]
        val destSlot = 4 // [SP_TIMES][header][dest][lo][hi]
        assertEquals(0, header and 0x2, "SpTimesArgument 不折叠常量 ⇒ 无载荷")
        assertEquals(0x0000_0000, flat.stream[destSlot + 1], "double 低位槽")
        assertEquals(0x3FF8_0000, flat.stream[destSlot + 2], "double 高位槽")

        val fa = FlatArgs(FlatSource(flat), 2)
        fa.open()
        fa.varRefNN()
        assertEquals(value.toRawBits(), fa.f64().toRawBits())

        // long 载荷（constInt）走同一条 lo/hi 路径
        val big = VoidArgument().apply { isConst = true; constInt = Long.MAX_VALUE }
        val flatLong = singleArgStream(big)
        assertEquals(0xFFFF_FFFF.toInt(), flatLong.stream[5], "Long.MAX_VALUE 低 32 位")
        assertEquals(0x7FFF_FFFF, flatLong.stream[6], "Long.MAX_VALUE 高 32 位")
        val faLong = FlatArgs(FlatSource(flatLong), 2)
        faLong.open()
        assertEquals(Long.MAX_VALUE, faLong.constInt)
    }

    // ------------------------------------------------------------------
    // 6. 列表形态
    // ------------------------------------------------------------------

    @Test
    fun `intArray 维度表`() {
        val cases = listOf(
            intArrayOf(),
            intArrayOf(0),
            intArrayOf(2, 3),
            intArrayOf(-1, Int.MAX_VALUE, Int.MIN_VALUE),
        )
        val statements = cases.map { IntAsignArgument("N", it, null) }
            .map { InstructionStmt(FunctionCode.VARI, it, 1, "raw") }
        val flat = encodeAll(statements)
        for (i in cases.indices) {
            val fa = argsOf(flat, flat.headAt(i) + 2, i)
            fa.open()
            fa.str()
            assertEquals(cases[i].toList(), fa.intArray().toList(), "[$i] lengths")
            assertNull(fa.expr(), "[$i] exp 为 null")
        }
    }

    @Test
    fun `exprList 非空项与 exprOptList 可空项`() {
        // exprList（childList）：项恒非空
        val bit = BitArgument(vref("B"), listOf(il(1), il(2), il(3)))
        val faBit = FlatArgs(FlatSource(singleArgStream(bit)), 2)
        assertEquals(ArgTag.BIT, faBit.open())
        assertEquals(vref("B"), faBit.varRefNN())
        assertEquals(listOf(il(1), il(2), il(3)), faBit.exprList())

        // exprOptList（childOptList）：项可空
        val arr = ExpressionArrayArgument(listOf(null, il(1), null))
        val faArr = FlatArgs(FlatSource(singleArgStream(arr)), 2)
        assertEquals(ArgTag.EXPRESSION_ARRAY, faArr.open())
        assertEquals(listOf(null, il(1), null), faArr.exprOptList())

        // 空列表：n = 0，无项槽
        val empty = ExpressionArrayArgument(emptyList())
        val flatEmpty = singleArgStream(empty)
        assertEquals(0, flatEmpty.stream[4], "空列表计数 0")
        val faEmpty = FlatArgs(FlatSource(flatEmpty), 2)
        faEmpty.open()
        assertEquals(emptyList<Expr?>(), faEmpty.exprOptList())
    }

    @Test
    fun `strList 与内置字符串槽`() {
        val arg = SpSaveVarArgument(il(1), sl("m"), listOf("A", "B", "空的"))
        val fa = FlatArgs(FlatSource(singleArgStream(arg)), 2)
        assertEquals(ArgTag.SP_SAVE_VAR, fa.open())
        assertEquals(il(1), fa.expr())
        assertEquals(sl("m"), fa.expr())
        assertEquals(listOf("A", "B", "空的"), fa.strList())

        // 空 strList
        val emptyList = SpSaveVarArgument(null, null, emptyList())
        val faEmpty = FlatArgs(FlatSource(singleArgStream(emptyList)), 2)
        faEmpty.open()
        faEmpty.expr()
        faEmpty.expr()
        assertEquals(emptyList<String>(), faEmpty.strList())

        // 字符串槽接受空串（"" 是合法旁表条目，不能与 null 混同）
        val blank = SpVarsizeArgument("")
        val faBlank = FlatArgs(FlatSource(singleArgStream(blank)), 2)
        assertEquals(ArgTag.SP_VARSIZE, faBlank.open())
        assertEquals("", faBlank.str())
    }

    @Test
    fun `mixedList 每项表达式槽加 isPx 槽`() {
        val param = listOf(
            MixedIntegerExprTerm(il(0), true),
            MixedIntegerExprTerm(null, false),
            MixedIntegerExprTerm(sl("x"), true),
        )
        val arg = SpPrintShapeArgument(param)
        val flat = singleArgStream(arg)
        // [tag][header=0][计数=3][expr][isPx][expr][isPx][expr][isPx]
        assertEquals(3, flat.stream[4], "计数槽")
        assertEquals(0, flat.stream[5], "第 1 项表达式槽")
        assertEquals(1, flat.stream[6], "第 1 项 isPx 槽")
        assertEquals(NONE_REF, flat.stream[7], "第 2 项表达式槽 = -1")
        assertEquals(0, flat.stream[8], "第 2 项 isPx 槽")
        val fa = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.SP_PRINT_SHAPE, fa.open())
        expectMixed(param, fa.mixedList(), "mixedList")

        // 空表：只读计数
        val faEmpty = FlatArgs(FlatSource(singleArgStream(SpPrintShapeArgument(emptyList()))), 2)
        faEmpty.open()
        assertEquals(emptyList<MixedIntegerExprTerm>(), faEmpty.mixedList())
    }

    @Test
    fun `caseList 三形态与运算符槽`() {
        val cases = listOf(
            CaseExpression(CaseExpression.CaseType.NORMAL, il(1), null, OperatorCode.EQUAL),
            CaseExpression(CaseExpression.CaseType.TO, il(1), il(9), OperatorCode.NULL),
            CaseExpression(CaseExpression.CaseType.IS, null, null, OperatorCode.GREATER),
        )
        val flat = singleArgStream(CaseArgument(cases))
        // [tag][header][计数=3][type][left][right][op] × 3（每项 4 槽，首项从槽 5 起）
        assertEquals(3, flat.stream[4], "计数槽")
        assertEquals(0, flat.stream[5], "NORMAL 线值")
        assertEquals(1, flat.stream[9], "TO 线值")
        assertEquals(2, flat.stream[13], "IS 线值")
        assertEquals(OperatorCode.NULL.ordinal, flat.stream[12], "TO 形态的 operator 槽")
        val fa = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.CASE, fa.open())
        expectCases(cases, fa.caseList(), "caseList")

        // 空 case 表
        val faEmpty = FlatArgs(FlatSource(singleArgStream(CaseArgument(emptyList()))), 2)
        faEmpty.open()
        assertEquals(emptyList<CaseExpression>(), faEmpty.caseList())
    }

    // ------------------------------------------------------------------
    // 7. 表达式池下标（池内实例经 exprAt 取回同一身份）
    // ------------------------------------------------------------------

    @Test
    fun `池内表达式取回同一实例`() {
        val pooled = il(42)
        val pool = ConstantPool()
        pool.addExpr(pooled)
        val statements = listOf(InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(pooled), 1, "raw"))
        val flat = FlatStatements.encode(statements, pool.exprList)
        assertEquals(0, flat.extraExprs.size, "池内实例不应旁挂")
        val fa = argsOf(flat, flat.headAt(0) + 2)
        assertEquals(ArgTag.EXPRESSION, fa.open())
        assertSame(pooled, fa.expr(), "池下标直读回同一实例")
    }

    // ------------------------------------------------------------------
    // 7b. 负责人批准的追加读面（§2.1 之外，2026-10-28）
    // ------------------------------------------------------------------

    @Test
    fun `追加读面 - expressions 跳过类型槽只回 terms`() {
        // types = [INT, null, STR] ⇒ 类型槽依次是 0 / -1 / 1（ArgValueType ordinal）
        val arg = ExpressionsArgument(
            listOf(ArgValueType.INT, null, ArgValueType.STR),
            listOf(il(1), null, sl("s")),
        )
        val flat = singleArgStream(arg)
        // [tag][header][n][type][expr] × 3
        assertEquals(3, flat.stream[4], "计数槽")
        assertEquals(0, flat.stream[5], "第 1 项类型序号 = INT")
        assertEquals(NONE_REF, flat.stream[7], "第 2 项类型序号 = null")
        assertEquals(ArgValueType.STR.ordinal, flat.stream[9], "第 3 项类型序号 = STR")
        assertEquals(0, flat.stream[6], "第 1 项表达式槽")

        val fa = argsOf(flat, 2)
        assertEquals(ArgTag.EXPRESSIONS, fa.open())
        assertEquals(listOf(il(1), null, sl("s")), fa.expressions(), "只回 terms，类型槽丢弃")

        // 空表
        val faEmpty = argsOf(singleArgStream(ExpressionsArgument(emptyList(), emptyList())), 2)
        faEmpty.open()
        assertEquals(emptyList<Expr?>(), faEmpty.expressions())
    }

    @Test
    fun `追加读面 - SET_ARRAY 两张可空常量表`() {
        // 非空：long 每项 2 槽（含负数与 Long.MAX_VALUE 的高位）
        val full = SpSetArrayArgument(
            vref("SA", 0),
            listOf(il(1), null),
            longArrayOf(10L, -20L, Long.MAX_VALUE),
            arrayOf("x", "", "z"),
        )
        val faFull = argsOf(singleArgStream(full), 2)
        assertEquals(ArgTag.SP_SET_ARRAY, faFull.open())
        assertEquals(vref("SA", 0), faFull.varRefNN())
        assertEquals(listOf<Expr?>(il(1), null), faFull.exprOptList())
        assertEquals(listOf(10L, -20L, Long.MAX_VALUE), faFull.l64ListOpt()?.toList(), "constIntList")
        assertEquals(listOf("x", "", "z"), faFull.strListOpt()?.toList(), "constStrList（空串是合法条目）")

        // null：两张表各写 1 槽 -1（terms 计数在它们之前）
        val nullTables = SpSetArrayArgument(vref("SB"), emptyList(), null, null)
        val flatNull = singleArgStream(nullTables)
        assertEquals(0, flatNull.stream[5], "terms 计数 0")
        assertEquals(NONE_REF, flatNull.stream[6], "constIntList 为 null ⇒ -1")
        assertEquals(NONE_REF, flatNull.stream[7], "constStrList 为 null ⇒ -1")
        val faNull = argsOf(flatNull, 2)
        faNull.open()
        faNull.varRefNN()
        assertEquals(emptyList<Expr?>(), faNull.exprOptList())
        assertNull(faNull.l64ListOpt(), "constIntList null")
        assertNull(faNull.strListOpt(), "constStrList null")

        // 空表（非 null，n = 0）与 null 必须区分
        val emptyTables = SpSetArrayArgument(vref("SC"), emptyList(), LongArray(0), emptyArray<String>())
        val faEmpty = argsOf(singleArgStream(emptyTables), 2)
        faEmpty.open()
        faEmpty.varRefNN()
        faEmpty.exprOptList()
        assertEquals(0, faEmpty.l64ListOpt()!!.size, "空 LongArray ≠ null")
        assertEquals(0, faEmpty.strListOpt()!!.size, "空字符串表 ≠ null")
    }

    @Test
    fun `追加读面 - PRINT_IMG 的 param 可空`() {
        val param = listOf(MixedIntegerExprTerm(il(1), true), MixedIntegerExprTerm(null, false))
        val withParam = SpPrintImgArgument(sl("n"), null, sl("m"), param)
        val fa = argsOf(singleArgStream(withParam), 2)
        assertEquals(ArgTag.SP_PRINT_IMG, fa.open())
        assertEquals(sl("n"), fa.expr())
        assertNull(fa.expr())
        assertEquals(sl("m"), fa.expr())
        expectMixed(param, fa.mixedListOpt() ?: error("param 非 null"), "param")

        val nullParam = SpPrintImgArgument(sl("n2"), null, null, null)
        val flatNull = singleArgStream(nullParam)
        assertEquals(NONE_REF, flatNull.stream[7], "null param ⇒ -1")
        val faNull = argsOf(flatNull, 2)
        faNull.open()
        faNull.expr()
        faNull.expr()
        faNull.expr()
        assertNull(faNull.mixedListOpt(), "null param")

        val emptyParam = SpPrintImgArgument(sl("n3"), null, null, emptyList())
        val faEmpty = argsOf(singleArgStream(emptyParam), 2)
        faEmpty.open()
        faEmpty.expr()
        faEmpty.expr()
        faEmpty.expr()
        assertEquals(emptyList<MixedIntegerExprTerm>(), faEmpty.mixedListOpt(), "空表 ≠ null")
    }

    @Test
    fun `追加读面 - dtOptions 之后必须紧接 values 段`() {
        val arg = SpDtColumnOptions(
            il(1),
            null,
            listOf(SpDtColumnOptions.DTOption.DEFAULT, SpDtColumnOptions.DTOption.DEFAULT),
            listOf(sl("v"), null),
        )
        val flat = singleArgStream(arg)
        // [tag][header][dt][column][n=2][线值][线值][values 计数][expr][expr]
        assertEquals(2, flat.stream[6], "options 计数")
        assertEquals(0, flat.stream[7], "DT_DEFAULT 线值")
        assertEquals(0, flat.stream[8], "DT_DEFAULT 线值")
        assertEquals(2, flat.stream[9], "values 段计数")

        val fa = argsOf(flat, 2)
        assertEquals(ArgTag.SP_DT_COLUMN_OPTIONS, fa.open())
        assertEquals(il(1), fa.expr(), "dt")
        assertNull(fa.expr(), "column")
        assertEquals(
            listOf(SpDtColumnOptions.DTOption.DEFAULT, SpDtColumnOptions.DTOption.DEFAULT),
            fa.dtOptions(),
            "options 段",
        )
        assertEquals(listOf<Expr?>(sl("v"), null), fa.exprOptList(), "values 段紧随 options 段")

        // 线值越界 ⇒ 畸形记录
        val badWire = FlatArgs(
            RawSource(intArrayOf(ArgTag.SP_DT_COLUMN_OPTIONS, 0, 0, 0, 1, 9), mapOf(0 to il(0))),
            0,
            6,
        )
        badWire.open()
        badWire.expr()
        badWire.expr()
        assertThrows<ErbcDecodeException> { badWire.dtOptions() }
    }

    // ------------------------------------------------------------------
    // 7c. 区间终点 endAt 与三参构造
    // ------------------------------------------------------------------

    @Test
    fun `FlatStmtSource endAt 缺省 = 下一条语句起点`() {
        val statements = listOf(
            InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(il(1)), 1, "raw"),
            InstructionStmt(FunctionCode.REUSELASTLINE, SpBarArgument(il(1), il(2), il(3)), 2, "raw"),
            PrintStmt(spec("PRINT"), ExpressionArgument(sl("x")), 3, "raw"),
        )
        val flat = encodeAll(statements)
        val src = FlatSource(flat) // 不覆写 endAt：走接口的缺省实现
        for (i in statements.indices) {
            assertEquals(flat.headAt(i + 1), src.endAt(i), "[$i] 缺省 endAt = 下一条语句起点")
        }
        assertEquals(
            flat.stream.size,
            src.endAt(statements.size - 1),
            "最后一条的终点 = 参数流长度（heads 是 n+1 槽，headAt(n) 可读）",
        )
        // 区间与语句一一对应：相邻两条首尾相接
        for (i in 0 until statements.size - 1) {
            assertEquals(flat.headAt(i + 1), src.endAt(i), "[$i] endAt 与下一条 headAt 相接")
        }
    }

    @Test
    fun `三参构造 - 区间只覆盖前半记录时读后半抛错`() {
        // SP_TINPUTS 有 6 个表达式槽：[tag][header][e1..e6]，记录区间 = [2, 10)
        val flat = singleArgStream(SpTInputsArgument(il(1), il(2), il(3), il(4), il(5), il(6)))
        val src = FlatSource(flat)
        assertEquals(10, src.endAt(0), "整条记录长度 = 10 槽")
        // 只给到槽 8（含 e1..e4）：第 5 个表达式在区间外
        val truncated = FlatArgs(src, 2, 8)
        assertEquals(ArgTag.SP_TINPUTS, truncated.open())
        assertEquals(il(1), truncated.expr())
        assertEquals(il(2), truncated.expr())
        assertEquals(il(3), truncated.expr())
        assertEquals(il(4), truncated.expr())
        val e = assertThrows<ErbcDecodeException> { truncated.expr() }
        assertTrue(
            e.message!!.contains("end=8"),
            "越界文案要带区间终点，便于定位是「槽序错」还是「区间给短了」：${e.message}",
        )

        // 同一记录给足区间就能读完 6 项 —— 证明上面的红是区间截断，不是槽序错
        val full = FlatArgs(src, 2, src.endAt(0))
        assertEquals(ArgTag.SP_TINPUTS, full.open())
        assertEquals(
            listOf(il(1), il(2), il(3), il(4), il(5), il(6)),
            List(6) { full.expr() },
        )

        // 计数也按区间校验：区间剩余 4 槽却声称 5 项列表
        val listTruncated = FlatArgs(RawSource(intArrayOf(ArgTag.EXPRESSION_ARRAY, 0, 5, 0, 0, 0, 0)), 0, 7)
        listTruncated.open()
        assertThrows<ErbcDecodeException> { listTruncated.exprOptList() }
    }

    @Test
    fun `两参次构造 - 终点退化为参数流末尾`() {
        // 单条语句流：endAt(0) == stream.size，两种构造必须等价（lane D 现有 2 参调用不破）
        val flat = singleArgStream(SpBarArgument(il(1), il(2), il(3)))
        val a = argsOf(flat, 2)
        val b = FlatArgs(FlatSource(flat), 2)
        assertEquals(ArgTag.SP_BAR, a.open())
        assertEquals(ArgTag.SP_BAR, b.open())
        assertEquals(a.expr(), b.expr())
        assertEquals(a.expr(), b.expr())
        assertEquals(a.expr(), b.expr())
    }

    // ------------------------------------------------------------------
    // 8. 越界与畸形记录
    // ------------------------------------------------------------------
    @Test
    fun `越界与畸形记录抛 ErbcDecodeException`() {
        // 空流：open 第一槽就越界
        assertThrows<ErbcDecodeException> { FlatArgs(RawSource(IntArray(0)), 0).open() }
        // 只有 [tag][header]：形态字段缺失
        val truncated = FlatArgs(RawSource(intArrayOf(ArgTag.EXPRESSION, 0)), 0)
        assertEquals(ArgTag.EXPRESSION, truncated.open())
        assertThrows<ErbcDecodeException> { truncated.expr() }
        // 派生参数标签 NONE
        assertThrows<ErbcDecodeException> { FlatArgs(RawSource(intArrayOf(ArgTag.NONE, 0)), 0).open() }
        // 未知参数标签
        assertThrows<ErbcDecodeException> { FlatArgs(RawSource(intArrayOf(99, 0)), 0).open() }
        // 公共头未定义位
        assertThrows<ErbcDecodeException> { FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0x04)), 0).open() }
        // 载荷字符串下标为负
        assertThrows<ErbcDecodeException> {
            FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0x02, -1, 0, 0)), 0).open()
        }
        // 载荷 3 槽被截断（字符串槽可读、两个 long 槽缺失）
        assertThrows<ErbcDecodeException> {
            FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0x02, 0), strings = mapOf(0 to "p")), 0).open()
        }
        // exprNN 撞 -1
        val nullExpr = FlatArgs(RawSource(intArrayOf(ArgTag.EXPRESSION, 0, NONE_REF)), 0)
        nullExpr.open()
        assertThrows<ErbcDecodeException> { nullExpr.exprNN() }
        // str 撞 -1
        val nullStr = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, NONE_REF)), 0)
        nullStr.open()
        assertThrows<ErbcDecodeException> { nullStr.str() }
        // varRefNN 撞非 VarRef 结点
        val notVar = FlatArgs(RawSource(intArrayOf(ArgTag.EXPRESSION, 0, 0), mapOf(0 to il(1))), 0)
        notVar.open()
        assertThrows<ErbcDecodeException> { notVar.varRefNN() }
        // 表达式下标不在池内
        val missing = FlatArgs(RawSource(intArrayOf(ArgTag.EXPRESSION, 0, 7)), 0)
        missing.open()
        assertThrows<ErbcDecodeException> { missing.expr() }
        // 列表计数为负
        val negCount = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, -2)), 0)
        negCount.open()
        assertThrows<ErbcDecodeException> { negCount.intArray() }
        // 列表计数超过剩余槽数（先拒绝，不做巨型分配）
        val hugeCount = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, 1_000_000)), 0)
        hugeCount.open()
        assertThrows<ErbcDecodeException> { hugeCount.intArray() }
        // caseList 的每项 4 槽也要计入剩余槽数校验
        val shortCases = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, 2, 0, 0, 0, 0)), 0)
        shortCases.open()
        assertThrows<ErbcDecodeException> { shortCases.caseList() }
        // SortOrder 未知线值
        val badSort = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, 9)), 0)
        badSort.open()
        assertThrows<ErbcDecodeException> { badSort.sortOrder() }
        // CaseType 未知线值
        val badCase = FlatArgs(RawSource(intArrayOf(ArgTag.VOID, 0, 1, 9)), 0)
        badCase.open()
        assertThrows<ErbcDecodeException> { badCase.caseList() }
    }
}
