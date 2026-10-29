package com.eratw.port.core.code

import com.eratw.port.core.code.ErbcFormat.ArgTag
import com.eratw.port.core.code.ErbcFormat.NONE_REF
import com.eratw.port.core.code.ErbcFormat.StmtTag
import com.eratw.port.core.code.ErbcFormat.WireEnum
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgType
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
import java.util.IdentityHashMap

/**
 * T2-64 波次 A：语句池扁平化 —— `Statement` 对象树 ⇄ int 流的双向编解码。
 *
 * ## 形态
 *
 * 每条语句占 `argStream` 的一个区间 `[heads[i], heads[i+1])`：
 *
 * ```text
 * [StmtTag] [opOrdinal] [语句专属字段...] [ArgTag] [argHeader] [参数专属字段...]
 * ```
 *
 * - `StmtTag` / `ArgTag` 与 `.erbc` 段 3 同值（`ErbcFormat.StmtTag` / `ErbcFormat.ArgTag`），
 *   字段序与 `ErbcReader.readStatement` / `readArgument` **逐一对偶**（除 line/raw，见下）。
 *   这样编码正确性的论证只有一个来源：与既有装载序列化同构，不发明第二套形态。
 * - 槽宽恒 32 位：i64/double 拆 2 槽（lo, hi）；表达式 = 表达式池下标（null = -1）；
 *   字符串 = 本类自带的去重旁表下标（null = -1）；bool = 0/1；枚举 = ordinal；
 *   列表 = `n` + n×项（可空列表 n = -1 表示整个列表为 null）。
 * - `argHeader` 与 erbc 写侧同判定：bit0 = isConst、bit1 = 有 constStr/constInt 载荷
 *   （`hasPayload = isConst || constStr 非空 || constInt != 0`，ErbcWriter.writeArgument）。
 * - **行号**：`stmtLines[i]` 独立 4B/条（encode 顺手收集）。行号不是零成本猜测——
 *   `Statement.line` 是否有运行期读者尚未程序化证明，带上它消灭该判断风险，
 *   还原件与原件可在对拍中逐行比较 line。
 * - **raw 原文不进扁平层**：运行期 `ProcessRuntime` 构造期即 `releaseStatementRaws()`，
 *   生产口径 raw 恒 `""`；还原件同样 raw = ""，与运行期口径一致（对拍跳过 raw）。
 *
 * ## 表达式下标的来源（旁挂语义）
 *
 * 编码器用 `IdentityHashMap` 反向表按**身份**查下标：命中表达式池（`[exprPool]`）即池下标；
 * 池外实例（装载链字符串归一等**重建产物**，与池条目内容可能相等但身份不同）按身份
 * 去重旁挂进 [extraExprs]，下标 = `poolSize + 旁表序号`——同一实例恒归同一下标，
 * 还原侧经 [extraExprs] 取回**同一实例**（身份往返成立）。真语料实测旁挂量 ~80.7 万
 * （结构读数，见 encode KDoc）；`statementAt` 还原出的参数树在 Expr 层与原件
 * equals 且池外实例 assertSame 成立（FlatStmtRoundTripTest 覆盖）。
 *
 * ## 边界
 *
 * 本文件只提供纯编解码，不改任何执行路径；`statementAt` 供对拍装置与诊断降级路径使用。
 * 执行面切换（`when(op)` 直读）在波次 B/C 落地。
 *
 * 写码只有一份（私有 `Encoder`）：[encode] 是「先攒后压」的入口（对拍装置/再编码/编译期），
 * [Builder] 是「解一条写一条」的增量入口（T2-64 波次 C 的装载期「压扁即弃」）。
 */
class FlatStatements private constructor(
    /** 第 i 条语句的 FunctionCode.ordinal（tableswitch 友好）。 */
    val ops: IntArray,
    /** 第 i 条语句在 [stream] 的区间起点；[heads.size] = 语句数 + 1，末项 = stream.size。 */
    val heads: IntArray,
    /** 第 i 条语句的源行号（对偶 `Statement.line`）。 */
    val lines: IntArray,
    /** 参数 int 流。 */
    val stream: IntArray,
    /** 语句级字符串旁表（label/name/destVarName 等；与段 1 字符串池解耦，去重引用）。 */
    val strings: Array<String>,
    /** 表达式池条数：下标 `[0, poolSize)` 走 [poolAt]，`[poolSize, …)` 走 [extraExprs] 旁表。 */
    val poolSize: Int,
    /** 池外旁挂结点（装载链字符串归一等重建产物；编码时按身份去重追加）。 */
    val extraExprs: List<Expr>,
    /** 表达式池读（decode 时按下标取回共享树）。 */
    private val poolAt: (Int) -> Expr,
) {
    val count: Int get() = ops.size

    /** 第 [index] 条语句的 op（FunctionCode.ordinal）。 */
    fun opAt(index: Int): Int = ops[index]

    /** 第 [index] 条语句参数流区间起点（[stream] 下标）。 */
    fun headAt(index: Int): Int = heads[index]

    /**
     * 按扁平流还原第 [index] 条语句（对拍/诊断/兜底路径；运行期主路径在波次 B 后不再走它）。
     * `raw` 恒为 ""（见类 KDoc「raw 原文不进扁平层」）。
     */
    fun statementAt(index: Int): Statement {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("语句下标越界: $index（共 $count）")
        val cur = FlatCursor(stream, heads[index], heads[index + 1])
        val tag = cur.next()
        val opOrd = cur.next()
        val line = lines[index]
        return try {
            decodeStatement(tag, opOrd, line, cur)
        } catch (e: ErbcDecodeException) {
            throw ErbcDecodeException(e.offset, "语句 #$index: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // 解码（与 ErbcReader.readStatement / readArgument 逐一对偶）
    // ------------------------------------------------------------------

    private fun decodeStatement(tag: Int, opOrd: Int, line: Int, cur: FlatCursor): Statement {
        val code = enumValues<FunctionCode>()[opOrd]
        val s: Statement = when (tag) {
            StmtTag.PRINT -> PrintStmt(readPrintSpec(cur, code), argument(cur), line, "")
            StmtTag.ASSIGN -> AssignStmt(
                dest = varRefAt(cur.next()),
                op = enumValues<OperatorCode>()[cur.next()],
                args = argument(cur),
                line = line,
                raw = "",
            )
            StmtTag.JUMP -> JumpStmt(code, strOptAt(cur.next()), argument(cur), line, "")
            StmtTag.CALL -> CallStmt(code, strOptAt(cur.next()), argument(cur), line, "")
            StmtTag.CALL_F -> CallFStmt(functionName = strOptAt(cur.next()), args = argument(cur), line = line, raw = "", code = code)
            StmtTag.CALL_SHARP -> CallSharpStmt(argument(cur), line, "")
            StmtTag.METHOD -> MethodStmt(strAt(cur.next()), argument(cur), line, "")
            StmtTag.IF -> {
                // 读序必须与写序逐字一致：condition、single、然后才是参数记录
                val condition = exprAt(cur.next())
                val single = cur.next() != 0
                IfStmt(condition, argument(cur), line, "", single)
            }
            StmtTag.ELSE_IF -> ElseIfStmt(exprAt(cur.next()), argument(cur), line, "")
            StmtTag.ELSE -> ElseStmt(argument(cur), line, "")
            StmtTag.END_IF -> EndIfStmt(argument(cur), line, "")
            StmtTag.SELECT_CASE -> SelectCaseStmt(exprAt(cur.next()), argument(cur), line, "")
            StmtTag.CASE -> CaseStmt(readCaseList(cur), argument(cur), line, "")
            StmtTag.CASE_ELSE -> CaseElseStmt(argument(cur), line, "")
            StmtTag.END_SELECT -> EndSelectStmt(argument(cur), line, "")
            StmtTag.WHILE -> WhileStmt(exprAt(cur.next()), argument(cur), line, "")
            StmtTag.WEND -> WendStmt(argument(cur), line, "")
            StmtTag.DO -> DoStmt(argument(cur), line, "")
            StmtTag.LOOP -> LoopStmt(exprOptAt(cur.next()), argument(cur), line, "")
            StmtTag.CATCH -> CatchStmt(argument(cur), line, "")
            StmtTag.END_CATCH -> EndCatchStmt(argument(cur), line, "")
            StmtTag.RETURN -> ReturnStmt(readChildOptList(cur), argument(cur), line, "")
            StmtTag.RETURN_F -> ReturnFStmt(value = exprOptAt(cur.next()), args = argument(cur), line = line, raw = "", code = code)
            StmtTag.FUNC_LABEL -> {
                val name = strAt(cur.next())
                val params = stringList(cur)
                // paramRefs/paramDefaults 不进扁平层（与 .erbc 同口径：还原为构造默认）
                FuncLabelStmt(name, params, line, "")
            }
            StmtTag.GOTO_LABEL -> {
                val name = strAt(cur.next())
                GotoLabelStmt(name, line, "")
            }
            StmtTag.SHARP -> {
                val keyword = strAt(cur.next())
                val values = longList(cur)
                val name = strOptAt(cur.next())
                val isString = cur.next() != 0
                SharpStmt(keyword = keyword, values = values, name = name, isString = isString, line = line, raw = "")
            }
            StmtTag.DATA -> DataStmt(code, strAt(cur.next()), argument(cur), line, "")
            StmtTag.PRINT_DATA -> PrintDataStmt(code, argument(cur), line, "")
            StmtTag.VAR_DECL -> VarDeclStmt(code, argument(cur), line, "")
            StmtTag.FOR -> ForStmt(code, argument(cur), line, "")
            StmtTag.NEXT -> NextStmt(argument(cur), line, "", code)
            StmtTag.REPEAT -> RepeatStmt(argument(cur), line, "")
            StmtTag.REND -> RendStmt(argument(cur), line, "")
            StmtTag.DATA_LIST -> DataListStmt(code, argument(cur), line, "")
            StmtTag.END_BLOCK -> EndBlockStmt(code, argument(cur), line, "")
            StmtTag.FLOW -> FlowStmt(code, argument(cur), line, "")
            StmtTag.INSTRUCTION -> InstructionStmt(code, argument(cur), line, "")
            else -> throw ErbcDecodeException(0, "扁平语句流未知语句类标签: $tag")
        }
        // 类表与语句身份一致性（对偶 ErbcReader.readStatement 尾部校验）
        if (s.code.ordinal != opOrd) {
            throw ErbcDecodeException(0, "扁平语句流 ${s.javaClass.simpleName} 的 code(${s.code.ordinal}) 与记录里的 $opOrd 不符")
        }
        return s
    }

    private fun readPrintSpec(cur: FlatCursor, code: FunctionCode): PrintSpec {
        val argType = enumValues<ArgType>()[cur.next()]
        val bits = cur.next()
        val flag = cur.next()
        return PrintSpec(
            code = code,
            name = code.name,
            argType = argType,
            isLineEnd = (bits and 0x001) != 0,
            isSingleLine = (bits and 0x002) != 0,
            isPrintV = (bits and 0x004) != 0,
            isS = (bits and 0x008) != 0,
            isForms = (bits and 0x010) != 0,
            isC = (bits and 0x020) != 0,
            isLC = (bits and 0x040) != 0,
            isK = (bits and 0x080) != 0,
            isD = (bits and 0x100) != 0,
            isN = (bits and 0x200) != 0,
            flag = flag,
        )
    }

    private fun argument(cur: FlatCursor): Argument {
        val tag = cur.next()
        if (tag == ArgTag.NONE) throw ErbcDecodeException(0, "扁平语句流出现派生参数标签 NONE")
        val header = cur.next()
        if ((header and 0xFC) != 0) throw ErbcDecodeException(0, "扁平参数头含未定义位: 0x${header.toString(16)}")
        val constStr = if ((header and 2) != 0) strAt(cur.next()) else ""
        val constInt = if ((header and 2) != 0) cur.nextLong() else 0L
        val a: Argument = when (tag) {
            ArgTag.VOID -> VoidArgument()
            ArgTag.EXPRESSION -> ExpressionArgument(exprOptAt(cur.next()))
            ArgTag.EXPRESSION_ARRAY -> ExpressionArrayArgument(readChildOptList(cur))
            ArgTag.EXPRESSIONS -> {
                val n = cur.next()
                val types = ArrayList<ArgValueType?>(n)
                val terms = ArrayList<Expr?>(n)
                repeat(n) {
                    types.add(argValueType(cur.next()))
                    terms.add(exprOptAt(cur.next()))
                }
                ExpressionsArgument(types, terms)
            }
            ArgTag.INT_ASIGN -> IntAsignArgument(strAt(cur.next()), intArray(cur), exprOptAt(cur.next()))
            ArgTag.STR_ASIGN -> StrAsignArgument(strAt(cur.next()), intArray(cur), strOptAt(cur.next()))
            ArgTag.BIT -> BitArgument(varRefAt(cur.next()), readChildList(cur))
            ArgTag.CASE -> CaseArgument(readCaseList(cur))
            ArgTag.METHOD -> MethodArgument(exprOptAt(cur.next()))
            ArgTag.PRINT_DATA -> PrintDataArgument(varRefOptAt(cur.next()))
            ArgTag.REF -> RefArgument(strAt(cur.next()), strOptAt(cur.next()), exprOptAt(cur.next()), cur.next() != 0)
            ArgTag.STR_DATA -> StrDataArgument(varRefOptAt(cur.next()))
            ArgTag.SP_ARRAY_CONTROL -> SpArrayControlArgument(varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_ARRAY_SHIFT -> SpArrayShiftArgument(
                varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
            )
            ArgTag.SP_ARRAY_SORT -> SpArraySortArgument(
                varRefAt(cur.next()), sortOrder(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
            )
            ArgTag.SP_BAR -> SpBarArgument(exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_BUTTON -> SpButtonArgument(exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_CVAR_SET -> SpCVarSetArgument(
                varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
            )
            ArgTag.SP_CALL -> SpCallArgument(exprOptAt(cur.next()), readChildOptList(cur), readChildOptList(cur))
            ArgTag.SP_CALLF -> SpCallFArgument(exprOptAt(cur.next()), readChildOptList(cur), readChildOptList(cur))
            ArgTag.SP_CALL_SHARP -> SpCallSharpArgument(exprOptAt(cur.next()), readChildOptList(cur), readChildOptList(cur))
            ArgTag.SP_COLOR -> {
                val r = exprOptAt(cur.next())
                val g = exprOptAt(cur.next())
                val b = exprOptAt(cur.next())
                val rgb = exprOptAt(cur.next())
                if (rgb != null) SpColorArgument(rgb) else SpColorArgument(r, g, b)
            }
            ArgTag.SP_COPY_ARRAY -> SpCopyArrayArgument(exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_DT_COLUMN_OPTIONS -> {
                val dt = exprOptAt(cur.next())
                val column = exprOptAt(cur.next())
                val optionCount = cur.next()
                val options = ArrayList<SpDtColumnOptions.DTOption>(optionCount)
                repeat(optionCount) {
                    options.add(
                        when (cur.next()) {
                            WireEnum.DT_DEFAULT -> SpDtColumnOptions.DTOption.DEFAULT
                            else -> throw ErbcDecodeException(0, "扁平语句流未知 DTOption 线值")
                        },
                    )
                }
                SpDtColumnOptions(dt, column, options, readChildOptList(cur))
            }
            ArgTag.SP_FOR_NEXT -> SpForNextArgument(varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_GET_INT -> SpGetIntArgument(varRefAt(cur.next()))
            ArgTag.SP_HTML_PRINT -> SpHtmlPrint(exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_HTML_SPLIT -> SpHtmlSplitArgument(exprOptAt(cur.next()), strAt(cur.next()), varRefOptAt(cur.next()))
            ArgTag.SP_INPUTS -> SpInputsArgument(exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_POWER -> SpPowerArgument(varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_PRINT_IMG -> SpPrintImgArgument(exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), mixedListOpt(cur))
            ArgTag.SP_PRINT_SHAPE -> SpPrintShapeArgument(mixedList(cur))
            ArgTag.SP_PRINT_V -> SpPrintVArgument(readChildList(cur))
            ArgTag.SP_SAVE_DATA -> SpSaveDataArgument(exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_SAVE_VAR -> SpSaveVarArgument(exprOptAt(cur.next()), exprOptAt(cur.next()), stringList(cur))
            ArgTag.SP_SET -> {
                val dest = varRefAt(cur.next())
                val term = exprOptAt(cur.next())
                val add = cur.next() != 0
                SpSetArgument(dest, term).apply { addConst = add }
            }
            ArgTag.SP_SET_ARRAY -> {
                val dest = varRefAt(cur.next())
                val terms = readChildOptList(cur)
                val ci = longListOpt(cur)
                val cs = stringListOpt(cur)
                SpSetArrayArgument(dest, terms, ci, cs)
            }
            ArgTag.SP_SORTCHARA -> SpSortcharaArgument(varRefAt(cur.next()), sortOrder(cur.next()))
            ArgTag.SP_SPLIT -> SpSplitArgument(exprOptAt(cur.next()), exprOptAt(cur.next()), strAt(cur.next()), varRefOptAt(cur.next()))
            ArgTag.SP_SWAP_CHARA -> SpSwapCharaArgument(exprOptAt(cur.next()), exprOptAt(cur.next()))
            ArgTag.SP_SWAP_VAR -> SpSwapVarArgument(varRefAt(cur.next()), varRefAt(cur.next()))
            ArgTag.SP_TINPUTS -> SpTInputsArgument(
                exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
                exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
            )
            ArgTag.SP_TIMES -> SpTimesArgument(varRefAt(cur.next()), cur.nextDouble())
            ArgTag.SP_VAR_SET -> SpVarSetArgument(
                varRefAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()), exprOptAt(cur.next()),
            )
            ArgTag.SP_VARSIZE -> SpVarsizeArgument(strAt(cur.next()))
            ArgTag.STR_DOUBLE -> StrDoubleArgument(exprOptAt(cur.next()), cur.nextDouble())
            ArgTag.HTML_PRINT_LINE_END -> throw ErbcDecodeException(
                0,
                "扁平语句流出现 HTML_PRINT_LINE_END：装载产物不会还原出该类（erbc 同形还原为 SP_HTML_PRINT），见 ErbcReader 同名分支",
            )
            else -> throw ErbcDecodeException(0, "扁平语句流未知参数标签: $tag")
        }
        if ((header and 1) != 0) a.isConst = true
        a.constStr = constStr
        a.constInt = constInt
        return a
    }

    private fun readCaseList(cur: FlatCursor): List<CaseExpression> {
        val n = cur.next()
        return ArrayList<CaseExpression>(n).apply {
            repeat(n) {
                val type = when (cur.next()) {
                    WireEnum.CASE_NORMAL -> CaseExpression.CaseType.NORMAL
                    WireEnum.CASE_TO -> CaseExpression.CaseType.TO
                    WireEnum.CASE_IS -> CaseExpression.CaseType.IS
                    else -> throw ErbcDecodeException(0, "扁平语句流未知 CaseType 线值")
                }
                val left = exprOptAt(cur.next())
                val right = exprOptAt(cur.next())
                add(CaseExpression(type, left, right, enumValues<OperatorCode>()[cur.next()]))
            }
        }
    }

    private fun readChildList(cur: FlatCursor): List<Expr> {
        val n = cur.next()
        return ArrayList<Expr>(n).apply { repeat(n) { add(exprAt(cur.next())) } }
    }

    private fun readChildOptList(cur: FlatCursor): List<Expr?> {
        val n = cur.next()
        return ArrayList<Expr?>(n).apply { repeat(n) { add(exprOptAt(cur.next())) } }
    }

    private fun mixedList(cur: FlatCursor): List<MixedIntegerExprTerm> {
        val n = cur.next()
        return ArrayList<MixedIntegerExprTerm>(n).apply {
            repeat(n) { add(MixedIntegerExprTerm(exprOptAt(cur.next()), cur.next() != 0)) }
        }
    }

    private fun mixedListOpt(cur: FlatCursor): List<MixedIntegerExprTerm>? {
        val n = cur.next()
        if (n == NONE_REF) return null
        return ArrayList<MixedIntegerExprTerm>(n).apply {
            repeat(n) { add(MixedIntegerExprTerm(exprOptAt(cur.next()), cur.next() != 0)) }
        }
    }

    private fun intArray(cur: FlatCursor): IntArray {
        val n = cur.next()
        return IntArray(n) { cur.next() }
    }

    private fun stringList(cur: FlatCursor): List<String> {
        val n = cur.next()
        return ArrayList<String>(n).apply { repeat(n) { add(strAt(cur.next())) } }
    }

    private fun stringListOpt(cur: FlatCursor): Array<String>? {
        val n = cur.next()
        if (n == NONE_REF) return null
        return Array(n) { strAt(cur.next()) }
    }

    private fun longList(cur: FlatCursor): List<Long> {
        val n = cur.next()
        return ArrayList<Long>(n).apply { repeat(n) { add(cur.nextLong()) } }
    }

    private fun longListOpt(cur: FlatCursor): LongArray? {
        val n = cur.next()
        if (n == NONE_REF) return null
        return LongArray(n) { cur.nextLong() }
    }

    private fun sortOrder(wire: Int): SortOrder = when (wire) {
        WireEnum.SORT_UNDEF -> SortOrder.UNDEF
        WireEnum.SORT_ASCENDING -> SortOrder.ASCENDING
        WireEnum.SORT_DESCENDING -> SortOrder.DESENDING
        else -> throw ErbcDecodeException(0, "扁平语句流未知 SortOrder 线值: $wire")
    }

    private fun argValueType(wire: Int): ArgValueType? = when (wire) {
        -1 -> null
        0 -> ArgValueType.INT
        1 -> ArgValueType.STR
        2 -> ArgValueType.VOID
        else -> throw ErbcDecodeException(0, "扁平语句流未知 ArgValueType 线值: $wire")
    }

    /** 表达式槽读（直读经 FlatStmtSource 转发）。 */
    internal fun exprAt(index: Int): Expr {
        if (index < 0) throw ErbcDecodeException(0, "扁平语句流表达式下标为负: $index")
        return if (index < poolSize) poolAt(index) else extraExprs[index - poolSize]
    }

    private fun exprOptAt(index: Int): Expr? = if (index == NONE_REF) null else exprAt(index)

    private fun varRefAt(index: Int): VarRef {
        val e = exprAt(index)
        return e as? VarRef
            ?: throw ErbcDecodeException(0, "扁平语句流表达式 $index 应为 VarRef，实为 ${e.javaClass.simpleName}")
    }

    private fun varRefOptAt(index: Int): VarRef? = if (index == NONE_REF) null else varRefAt(index)

    /** 字符串旁表读（直读经 FlatStmtSource 转发）。 */
    internal fun strAt(index: Int): String {
        if (index < 0) throw ErbcDecodeException(0, "扁平语句流字符串下标为负: $index")
        return strings[index]
    }

    private fun strOptAt(index: Int): String? = if (index == NONE_REF) null else strAt(index)

    companion object {
        /**
         * 装载后压扁：把 [statements] 全量压成 int 流。`strings` 去重入旁表。
         * [exprPool] = 表达式池全量条目（身份反向表据此建立）。语句树引用的结点
         * 若不在池里（装载链字符串归一等的**重建产物**），按身份旁挂进
         * [FlatStatements.extraExprs]，下标 = `poolSize + 旁表序号`——同一池外实例
         * 归到同一下标。**旁挂量是结构读数而非异常信号**：erbc 段 3 内联 Expr 与
         * 段 2 池本就不共享身份，真语料实测 ~80.7 万全走旁挂（2026-10-27 对拍）；
         * 旁表只是引用槽，Expr 本要驻留，「压扁即弃」的收益面是 Statement +
         * Argument + 容器（CONTRACT-T2-64 §8.3）。波次 C 装载期可按 equals 把
         * 树内 Expr intern 进池消旁挂，是否值得看收益再定。
         *
         * 本函数只是「先攒后压」的便捷入口：[Builder] 逐条 `append` + `build` 的
         * 等价形式（T2-64 波次 C，装载期走增量那条路）。
         */
        fun encode(statements: List<Statement>, exprPool: List<Expr>): FlatStatements {
            val builder = Builder(exprPool, statements.size)
            for (s in statements) builder.append(s)
            return builder.build()
        }
    }

    /**
     * T2-64 波次 C：**增量**压扁器 —— 与 [encode] 共用同一套写码逻辑（私有 `Encoder`，
     * 两处都不许各写一份），只把「先攒整池语句、再一次性压」换成「解一条、写一条、丢一条」。
     *
     * ## 为什么必须增量
     *
     * 波次 B 的 `ensureFlatStmtSource` 先把 171.7 万条语句树全量物化再 `encode`：
     * 装载期多付 ~7.8 s 解码 + ~1.9 s 压扁，并且**语句树整池常驻 ≈ 390 MB**
     * （CONTRACT-T2-64 §9.5）。装载侧改成「惰性单条解码 → 当场 `append` → 弃树」后，
     * 任一时刻只有一条语句树活着；[FlatStatements] 本体（int 流 + 字符串旁表 +
     * 表达式引用槽）是运行期真正要用的部分，本来就该常驻。
     *
     * ## 形态承诺
     *
     * 同一序列依次 `append` 之后 `build`，产物的 `ops`/`heads`/`lines`/`stream`/
     * `strings`/`extraExprs` 与 `encode(同序列, 同池)` **逐槽相同**（同一个 `Encoder`，
     * 同一个写序）—— `FlatStmtRoundTripTest` 钉住这条等价。
     *
     * ## 容量初值
     *
     * `ops`/`lines`/`heads`/`stream` 都按 [initialStatements] 外推初值（每条语句约
     * 8 个参数槽；真语料实测 17.7M / 171.7 万 ≈ 10.3 槽），估小只会多几次 [IntSink]
     * 扩容，估大只是短暂多占，不影响形态。
     *
     * 单线程使用（与 `LazyStatementPool` 的解码口径一致）；`build` 之后再 `append` 抛
     * [IllegalStateException] —— 半冻结的表被继续写会让 `heads` 末项与 `stream` 脱节。
     */
    class Builder(
        exprPool: List<Expr> = emptyList(),
        initialStatements: Int = 0,
    ) {
        private val enc = Encoder(exprPool, slotsHint(initialStatements))
        private val ops = IntSink(initialStatements.coerceAtLeast(0))
        private val lines = IntSink(initialStatements.coerceAtLeast(0))
        private val heads = IntSink(initialStatements.coerceAtLeast(0) + 1)
        private var built = false

        /** 已追加条数（`build` 前后同一口径）。 */
        val count: Int get() = ops.size

        /** 已写入的参数槽数（诊断读数；`build` 之后 = `stream.size`）。 */
        val streamSize: Int get() = enc.sink.size

        /** 追加一条语句：op / 行号 / 语句专属字段 / 参数记录，写序与 [encode] 的循环体同一份。 */
        fun append(s: Statement) {
            check(!built) { "扁平压扁器已 build，不能再追加语句" }
            heads.add(enc.sink.size)
            ops.add(s.code.ordinal)
            lines.add(s.line)
            enc.statement(s)
        }

        /** 收口：冻结成 [FlatStatements]，之后再 `append` 即抛。只许调一次。 */
        fun build(): FlatStatements {
            check(!built) { "扁平压扁器只能 build 一次" }
            built = true
            heads.add(enc.sink.size)
            return FlatStatements(
                ops = ops.toIntArray(),
                heads = heads.toIntArray(),
                lines = lines.toIntArray(),
                stream = enc.sink.toIntArray(),
                strings = enc.strings.toTypedArray(),
                poolSize = enc.poolSize,
                extraExprs = enc.extraExprs,
                poolAt = enc.poolAt,
            )
        }

        private companion object {
            /** 每条语句的平均参数槽数下界（真语料 17.7M 槽 / 171.7 万条 ≈ 10.3）。 */
            const val SLOTS_PER_STATEMENT = 8

            /** 参数槽初值：条数 × 8，按 Int 上界收口（真语料 1.72M 条 ⇒ 13.7M 槽 ≈ 55 MB）。 */
            fun slotsHint(statements: Int): Int {
                if (statements <= 0) return 0
                val slots = statements.toLong() * SLOTS_PER_STATEMENT
                return if (slots > Int.MAX_VALUE / 2) Int.MAX_VALUE / 2 else slots.toInt()
            }
        }
    }

    // ------------------------------------------------------------------
    // 编码器（对偶 ErbcWriter 的语句记录写法；仅波次 A 对拍与后续波次共用）
    // ------------------------------------------------------------------

    private class Encoder(exprPool: List<Expr>, streamHint: Int = 0) {
        /** 池内表达式条数（下标 `[0, poolSize)` 走池，其余走 [extraExprs] 旁表）。 */
        val poolSize = exprPool.size

        /** 池内下标读（`[Builder.build]` 交给 [FlatStatements] 的运行期读面）。 */
        val poolAt: (Int) -> Expr = { idx -> exprPool[idx] }

        val sink = IntSink(streamHint)
        val strings = ArrayList<String>()
        /** 池外旁挂结点（按身份去重；见 [encode] KDoc）。 */
        val extraExprs = ArrayList<Expr>()
        private val stringIndex = HashMap<String, Int>()
        private val extraIndex = IdentityHashMap<Expr, Int>()
        private val exprIndex: IdentityHashMap<Expr, Int> = IdentityHashMap<Expr, Int>(exprPool.size * 2)

        init {
            for ((idx, e) in exprPool.withIndex()) exprIndex[e] = idx
        }

        fun statement(s: Statement) {
            val tag = tagOf(s)
            sink.add(tag)
            sink.add(s.code.ordinal)
            when (s) {
                is PrintStmt -> {
                    val p = s.print
                    sink.add(p.argType.ordinal)
                    sink.add(printBits(p))
                    sink.add(p.flag)
                }
                is AssignStmt -> {
                    addExpr(s.dest)
                    sink.add(s.op.ordinal)
                }
                is JumpStmt -> addStrOpt(s.label)
                is CallStmt -> addStrOpt(s.label)
                is CallFStmt -> addStrOpt(s.functionName)
                is CallSharpStmt -> {}
                is MethodStmt -> addStr(s.methodName)
                is IfStmt -> {
                    addExpr(s.condition)
                    sink.add(if (s.single) 1 else 0)
                }
                is ElseIfStmt -> addExpr(s.condition)
                is ElseStmt -> {}
                is EndIfStmt -> {}
                is SelectCaseStmt -> addExpr(s.target)
                is CaseStmt -> caseList(s.cases)
                is CaseElseStmt -> {}
                is EndSelectStmt -> {}
                is ForStmt -> {}
                is NextStmt -> {}
                is RepeatStmt -> {}
                is RendStmt -> {}
                is WhileStmt -> addExpr(s.condition)
                is WendStmt -> {}
                is DoStmt -> {}
                is LoopStmt -> addExprOpt(s.condition)
                is CatchStmt -> {}
                is EndCatchStmt -> {}
                is DataListStmt -> {}
                is EndBlockStmt -> {}
                is FlowStmt -> {}
                is ReturnStmt -> childOptList(s.values)
                is ReturnFStmt -> addExprOpt(s.value)
                is FuncLabelStmt -> {
                    addStr(s.name)
                    // paramRefs/paramDefaults 不进扁平层（与 .erbc 同口径）
                    stringList(s.paramNames)
                }
                is GotoLabelStmt -> addStr(s.name)
                is SharpStmt -> {
                    addStr(s.keyword)
                    longList(s.values)
                    addStrOpt(s.name)
                    sink.add(if (s.isString) 1 else 0)
                }
                is DataStmt -> addStr(s.text)
                is PrintDataStmt -> {}
                is VarDeclStmt -> {}
                // 纯 InstructionStmt 兜底（无专属字段）：必须排在全部子类分支之后
                is InstructionStmt -> {}
                else -> throw ErbcEncodeException("扁平化不支持的语句类: ${s.javaClass.name}")
            }
            argument(s.args)
        }

        private fun tagOf(s: Statement): Int = when (s) {
        // AssignStmt 直接继承 Statement（Statement.kt:168，不经 InstructionStmt）
        is AssignStmt -> StmtTag.ASSIGN
        is InstructionStmt -> when (s) {
            is PrintStmt -> StmtTag.PRINT
            is PrintDataStmt -> StmtTag.PRINT_DATA
            is JumpStmt -> StmtTag.JUMP
            is CallStmt -> StmtTag.CALL
            is CallFStmt -> StmtTag.CALL_F
            is CallSharpStmt -> StmtTag.CALL_SHARP
            is MethodStmt -> StmtTag.METHOD
            is VarDeclStmt -> StmtTag.VAR_DECL
            is IfStmt -> StmtTag.IF
            is ElseIfStmt -> StmtTag.ELSE_IF
            is ElseStmt -> StmtTag.ELSE
            is EndIfStmt -> StmtTag.END_IF
            is SelectCaseStmt -> StmtTag.SELECT_CASE
            is CaseStmt -> StmtTag.CASE
            is CaseElseStmt -> StmtTag.CASE_ELSE
            is EndSelectStmt -> StmtTag.END_SELECT
            is ForStmt -> StmtTag.FOR
            is NextStmt -> StmtTag.NEXT
            is RepeatStmt -> StmtTag.REPEAT
            is RendStmt -> StmtTag.REND
            is WhileStmt -> StmtTag.WHILE
            is WendStmt -> StmtTag.WEND
            is DoStmt -> StmtTag.DO
            is LoopStmt -> StmtTag.LOOP
            is CatchStmt -> StmtTag.CATCH
            is EndCatchStmt -> StmtTag.END_CATCH
            is DataListStmt -> StmtTag.DATA_LIST
            is EndBlockStmt -> StmtTag.END_BLOCK
            is FlowStmt -> StmtTag.FLOW
            is ReturnStmt -> StmtTag.RETURN
            is ReturnFStmt -> StmtTag.RETURN_F
            // DataStmt 也是 InstructionStmt 子类（Statement.kt:579），必须在内层显式列出，
            // 否则落 else 被吞成 INSTRUCTION、text 槽错位（debug43 实证）
            is DataStmt -> StmtTag.DATA
            else -> StmtTag.INSTRUCTION
        }
        is FuncLabelStmt -> StmtTag.FUNC_LABEL
        is GotoLabelStmt -> StmtTag.GOTO_LABEL
        is SharpStmt -> StmtTag.SHARP
        is DataStmt -> StmtTag.DATA
        else -> throw ErbcEncodeException("扁平化不支持的语句类: ${s.javaClass.name}")
    }

        private fun printBits(p: PrintSpec): Int {
            var b = 0
            if (p.isLineEnd) b = b or 0x001
            if (p.isSingleLine) b = b or 0x002
            if (p.isPrintV) b = b or 0x004
            if (p.isS) b = b or 0x008
            if (p.isForms) b = b or 0x010
            if (p.isC) b = b or 0x020
            if (p.isLC) b = b or 0x040
            if (p.isK) b = b or 0x080
            if (p.isD) b = b or 0x100
            if (p.isN) b = b or 0x200
            return b
        }

        private fun argument(a: Argument) {
            when (a) {
                is VoidArgument -> {
                    // VOID 也要写公共头：isConst/const 载荷不属于任何形态（对偶 ErbcWriter 的统一 header）
                    sink.add(ArgTag.VOID)
                    header(a)
                }
                is ExpressionArgument -> {
                    sink.add(ArgTag.EXPRESSION)
                    header(a)
                    addExprOpt(a.term)
                }
                is ExpressionArrayArgument -> {
                    sink.add(ArgTag.EXPRESSION_ARRAY)
                    header(a)
                    childOptList(a.terms)
                }
                is ExpressionsArgument -> {
                    sink.add(ArgTag.EXPRESSIONS)
                    header(a)
                    val n = a.types.size
                    check(n == a.terms.size) { "ExpressionsArgument types/terms 长度不一致" }
                    sink.add(n)
                    repeat(n) {
                        val t = a.types[it]
                        sink.add(if (t == null) -1 else t.ordinal)
                        addExprOpt(a.terms[it])
                    }
                }
                is IntAsignArgument -> {
                    sink.add(ArgTag.INT_ASIGN)
                    header(a)
                    addStr(a.name)
                    intArray(a.lengths)
                    addExprOpt(a.exp)
                }
                is StrAsignArgument -> {
                    sink.add(ArgTag.STR_ASIGN)
                    header(a)
                    addStr(a.name)
                    intArray(a.lengths)
                    addStrOpt(a.value)
                }
                is BitArgument -> {
                    sink.add(ArgTag.BIT)
                    header(a)
                    addExpr(a.dest)
                    childList(a.terms)
                }
                is CaseArgument -> {
                    sink.add(ArgTag.CASE)
                    header(a)
                    caseList(a.caseExps)
                }
                is MethodArgument -> {
                    sink.add(ArgTag.METHOD)
                    header(a)
                    addExprOpt(a.methodTerm)
                }
                is PrintDataArgument -> {
                    sink.add(ArgTag.PRINT_DATA)
                    header(a)
                    addExprOpt(a.dest)
                }
                is RefArgument -> {
                    sink.add(ArgTag.REF)
                    header(a)
                    addStr(a.refName)
                    addStrOpt(a.srcName)
                    addExprOpt(a.srcTerm)
                    sink.add(if (a.refByName) 1 else 0)
                }
                is StrDataArgument -> {
                    sink.add(ArgTag.STR_DATA)
                    header(a)
                    addExprOpt(a.dest)
                }
                is SpArrayControlArgument -> {
                    sink.add(ArgTag.SP_ARRAY_CONTROL)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.num1)
                    addExprOpt(a.num2)
                }
                is SpArrayShiftArgument -> {
                    sink.add(ArgTag.SP_ARRAY_SHIFT)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.num1)
                    addExprOpt(a.num2)
                    addExprOpt(a.num3)
                    addExprOpt(a.num4)
                }
                is SpArraySortArgument -> {
                    sink.add(ArgTag.SP_ARRAY_SORT)
                    header(a)
                    addExpr(a.dest)
                    sink.add(sortOrderWire(a.order))
                    addExprOpt(a.num1)
                    addExprOpt(a.num2)
                }
                is SpBarArgument -> {
                    sink.add(ArgTag.SP_BAR)
                    header(a)
                    addExprOpt(a.value)
                    addExprOpt(a.max)
                    addExprOpt(a.length)
                }
                is SpButtonArgument -> {
                    sink.add(ArgTag.SP_BUTTON)
                    header(a)
                    addExprOpt(a.printStrTerm)
                    addExprOpt(a.buttonWord)
                }
                is SpCVarSetArgument -> {
                    sink.add(ArgTag.SP_CVAR_SET)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.index)
                    addExprOpt(a.term)
                    addExprOpt(a.start)
                    addExprOpt(a.end)
                }
                is SpCallArgument -> {
                    sink.add(ArgTag.SP_CALL)
                    header(a)
                    addExprOpt(a.funcNameTerm)
                    childOptList(a.subNames)
                    childOptList(a.rowArgs)
                }
                is SpCallFArgument -> {
                    sink.add(ArgTag.SP_CALLF)
                    header(a)
                    addExprOpt(a.funcNameTerm)
                    childOptList(a.subNames)
                    childOptList(a.rowArgs)
                }
                is SpCallSharpArgument -> {
                    sink.add(ArgTag.SP_CALL_SHARP)
                    header(a)
                    addExprOpt(a.funcNameTerm)
                    childOptList(a.subNames)
                    childOptList(a.rowArgs)
                }
                is SpColorArgument -> {
                    sink.add(ArgTag.SP_COLOR)
                    header(a)
                    addExprOpt(a.r)
                    addExprOpt(a.g)
                    addExprOpt(a.b)
                    addExprOpt(a.rgb)
                }
                is SpCopyArrayArgument -> {
                    sink.add(ArgTag.SP_COPY_ARRAY)
                    header(a)
                    addExprOpt(a.varName1)
                    addExprOpt(a.varName2)
                }
                is SpDtColumnOptions -> {
                    sink.add(ArgTag.SP_DT_COLUMN_OPTIONS)
                    header(a)
                    addExprOpt(a.dt)
                    addExprOpt(a.column)
                    sink.add(a.options.size)
                    repeat(a.options.size) {
                        sink.add(
                            when (a.options[it]) {
                                SpDtColumnOptions.DTOption.DEFAULT -> WireEnum.DT_DEFAULT
                            },
                        )
                    }
                    childOptList(a.values)
                }
                is SpForNextArgument -> {
                    sink.add(ArgTag.SP_FOR_NEXT)
                    header(a)
                    addExpr(a.cnt)
                    addExprOpt(a.start)
                    addExprOpt(a.end)
                    addExprOpt(a.step)
                }
                is SpGetIntArgument -> {
                    sink.add(ArgTag.SP_GET_INT)
                    header(a)
                    addExpr(a.dest)
                }
                is SpHtmlPrint -> {
                    sink.add(ArgTag.SP_HTML_PRINT)
                    header(a)
                    addExprOpt(a.str)
                    addExprOpt(a.opt)
                }
                is SpHtmlSplitArgument -> {
                    sink.add(ArgTag.SP_HTML_SPLIT)
                    header(a)
                    addExprOpt(a.targetStr)
                    addStr(a.destVarName)
                    addExprOpt(a.num)
                }
                is SpInputsArgument -> {
                    sink.add(ArgTag.SP_INPUTS)
                    header(a)
                    addExprOpt(a.def)
                    addExprOpt(a.mouse)
                    addExprOpt(a.canSkip)
                }
                is SpPowerArgument -> {
                    sink.add(ArgTag.SP_POWER)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.x)
                    addExprOpt(a.y)
                }
                is SpPrintImgArgument -> {
                    sink.add(ArgTag.SP_PRINT_IMG)
                    header(a)
                    addExprOpt(a.name)
                    addExprOpt(a.nameb)
                    addExprOpt(a.namem)
                    mixedListOpt(a.param)
                }
                is SpPrintShapeArgument -> {
                    sink.add(ArgTag.SP_PRINT_SHAPE)
                    header(a)
                    mixedList(a.param)
                }
                is SpPrintVArgument -> {
                    sink.add(ArgTag.SP_PRINT_V)
                    header(a)
                    childList(a.terms)
                }
                is SpSaveDataArgument -> {
                    sink.add(ArgTag.SP_SAVE_DATA)
                    header(a)
                    addExprOpt(a.target)
                    addExprOpt(a.strExpression)
                }
                is SpSaveVarArgument -> {
                    sink.add(ArgTag.SP_SAVE_VAR)
                    header(a)
                    addExprOpt(a.term)
                    addExprOpt(a.savMes)
                    stringList(a.varNames)
                }
                is SpSetArgument -> {
                    sink.add(ArgTag.SP_SET)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.term)
                    sink.add(if (a.addConst) 1 else 0)
                }
                is SpSetArrayArgument -> {
                    sink.add(ArgTag.SP_SET_ARRAY)
                    header(a)
                    addExpr(a.dest)
                    childOptList(a.terms)
                    val ci = a.constIntList
                    if (ci == null) sink.add(NONE_REF) else {
                        sink.add(ci.size)
                        for (v in ci) sink.addLong(v)
                    }
                    val cs = a.constStrList
                    if (cs == null) sink.add(NONE_REF) else {
                        sink.add(cs.size)
                        for (v in cs) addStr(v)
                    }
                }
                is SpSortcharaArgument -> {
                    sink.add(ArgTag.SP_SORTCHARA)
                    header(a)
                    addExpr(a.sortKey)
                    sink.add(sortOrderWire(a.sortOrder))
                }
                is SpSplitArgument -> {
                    sink.add(ArgTag.SP_SPLIT)
                    header(a)
                    addExprOpt(a.targetStr)
                    addExprOpt(a.split)
                    addStr(a.destVarName)
                    addExprOpt(a.num)
                }
                is SpSwapCharaArgument -> {
                    sink.add(ArgTag.SP_SWAP_CHARA)
                    header(a)
                    addExprOpt(a.x)
                    addExprOpt(a.y)
                }
                is SpSwapVarArgument -> {
                    sink.add(ArgTag.SP_SWAP_VAR)
                    header(a)
                    addExpr(a.var1)
                    addExpr(a.var2)
                }
                is SpTInputsArgument -> {
                    sink.add(ArgTag.SP_TINPUTS)
                    header(a)
                    addExprOpt(a.time)
                    addExprOpt(a.def)
                    addExprOpt(a.disp)
                    addExprOpt(a.timeout)
                    addExprOpt(a.mouse)
                    addExprOpt(a.canSkip)
                }
                is SpTimesArgument -> {
                    sink.add(ArgTag.SP_TIMES)
                    header(a)
                    addExpr(a.dest)
                    sink.addDouble(a.doubleValue)
                }
                is SpVarSetArgument -> {
                    sink.add(ArgTag.SP_VAR_SET)
                    header(a)
                    addExpr(a.dest)
                    addExprOpt(a.term)
                    addExprOpt(a.start)
                    addExprOpt(a.end)
                }
                is SpVarsizeArgument -> {
                    sink.add(ArgTag.SP_VARSIZE)
                    header(a)
                    addStr(a.varName)
                }
                is StrDoubleArgument -> {
                    sink.add(ArgTag.STR_DOUBLE)
                    header(a)
                    addExprOpt(a.term)
                    sink.addDouble(a.doubleValue)
                }
                else -> throw ErbcEncodeException("扁平化不支持的参数类: ${a.javaClass.name}")
            }
        }

        /** 与 ErbcWriter.writeArgument 同判定的公共头（bit0=isConst、bit1=有 const 载荷）。 */
        private fun header(a: Argument) {
            var h = 0
            if (a.isConst) h = h or 1
            val hasPayload = a.isConst || a.constStr.isNotEmpty() || a.constInt != 0L
            if (hasPayload) h = h or 2
            sink.add(h)
            if (hasPayload) {
                addStr(a.constStr)
                sink.addLong(a.constInt)
            }
        }

        private fun caseList(cases: List<CaseExpression>) {
            sink.add(cases.size)
            for (c in cases) {
                sink.add(
                    when (c.caseType) {
                        CaseExpression.CaseType.NORMAL -> WireEnum.CASE_NORMAL
                        CaseExpression.CaseType.TO -> WireEnum.CASE_TO
                        CaseExpression.CaseType.IS -> WireEnum.CASE_IS
                    },
                )
                addExprOpt(c.leftTerm)
                addExprOpt(c.rightTerm)
                sink.add(c.operator.ordinal)
            }
        }

        private fun childList(terms: List<Expr>) {
            sink.add(terms.size)
            for (t in terms) addExpr(t)
        }

        private fun childOptList(terms: List<Expr?>) {
            sink.add(terms.size)
            for (t in terms) addExprOpt(t)
        }

        private fun intArray(v: IntArray) {
            sink.add(v.size)
            for (x in v) sink.add(x)
        }

        private fun stringList(v: List<String>) {
            sink.add(v.size)
            for (x in v) addStr(x)
        }

        private fun longList(v: List<Long>) {
            sink.add(v.size)
            for (x in v) sink.addLong(x)
        }

        private fun mixedList(v: List<MixedIntegerExprTerm>) {
            sink.add(v.size)
            for (t in v) {
                addExprOpt(t.num)
                sink.add(if (t.isPx) 1 else 0)
            }
        }

        private fun mixedListOpt(v: List<MixedIntegerExprTerm>?) {
            if (v == null) {
                sink.add(NONE_REF)
                return
            }
            mixedList(v)
        }

        private fun sortOrderWire(o: SortOrder): Int = when (o) {
            SortOrder.UNDEF -> WireEnum.SORT_UNDEF
            SortOrder.ASCENDING -> WireEnum.SORT_ASCENDING
            SortOrder.DESENDING -> WireEnum.SORT_DESCENDING
        }

        /** 计算表达式的流下标（池内 = 池下标；池外 = 旁挂）。只算不写。 */
        private fun indexOfExpr(e: Expr): Int {
            val inPool = exprIndex[e]
            if (inPool != null) return inPool
            val extra = extraIndex[e]
            if (extra != null) return poolSize + extra
            val idx = extraExprs.size
            extraExprs.add(e)
            extraIndex[e] = idx
            return poolSize + idx
        }

        private fun indexOfStr(s: String): Int {
            val hit = stringIndex[s]
            if (hit != null) return hit
            val idx = strings.size
            strings.add(s)
            stringIndex[s] = idx
            return idx
        }

        /** 写入语义：下标直接进流（encode 槽对称的唯一写入口；裸算不写是本波踩过的坑）。 */
        fun addExpr(e: Expr) {
            sink.add(indexOfExpr(e))
        }

        fun addExprOpt(e: Expr?) {
            sink.add(if (e == null) NONE_REF else indexOfExpr(e))
        }

        fun addStr(s: String) {
            sink.add(indexOfStr(s))
        }

        fun addStrOpt(s: String?) {
            sink.add(if (s == null) NONE_REF else indexOfStr(s))
        }
    }
}

/** int 流增长汇（ErbcSink 的 int 版；只增不改，decode 走 [FlatCursor]）。 */
internal class IntSink(capacity: Int = 256) {
    private var data = IntArray(if (capacity > 0) capacity else 16)
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > data.size) {
            var cap = data.size
            while (cap < size + n) cap *= 2
            data = data.copyOf(cap)
        }
    }

    fun add(v: Int) {
        ensure(1)
        data[size++] = v
    }

    fun addLong(v: Long) {
        add(v.toInt())
        add((v ushr 32).toInt())
    }

    fun addDouble(v: Double) = addLong(v.toRawBits())

    fun toIntArray(): IntArray = data.copyOf(size)
}

/** int 流只进游标（区间 [start, end)；越界抛 [ErbcDecodeException]，不返回脏值）。 */
internal class FlatCursor(
    private val data: IntArray,
    start: Int,
    private val end: Int,
) {
    private var pos = start

    fun next(): Int {
        if (pos >= end) throw ErbcDecodeException(pos, "扁平语句流区间耗尽（end=$end）")
        return data[pos++]
    }

    fun nextLong(): Long {
        val lo = next().toLong() and 0xffffffffL
        val hi = next().toLong() and 0xffffffffL
        return (hi shl 32) or lo
    }

    fun nextDouble(): Double = Double.fromBits(nextLong())
}
