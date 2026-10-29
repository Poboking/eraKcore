package com.eratw.port.core.code

import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T2-64 波次 A 对拍装置（合成侧）：全 Statement 类 × 全 Argument 形态的压扁-还原往返。
 *
 * 判据是 [FlatStmtKey]（手写全字段归约，**独立于编解码器的字段清单**——两边漏同一字段
 * 才会漏网，清单誊自 `stmt/Statement.kt` / `stmt/Argument.kt` 的字段声明并逐类复核）。
 * `line` 参与（扁平层带 stmtLines）；`raw` 不参与（运行期口径恒 ""，见 FlatStatements KDoc）；
 * `SharpStmt.tokens` / `FuncLabelStmt.paramRefs·paramDefaults` 不参与（.erbc 同口径不落盘）。
 */
class FlatStmtRoundTripTest {

    // ------------------------------------------------------------------
    // 比较器：语句 → 结构键（深度 equals）
    // ------------------------------------------------------------------

    private fun roundTrip(statements: List<Statement>, pool: ConstantPool = poolOf(statements)): List<Statement> {
        val flat = FlatStatements.encode(statements, pool.exprList)
        val out = ArrayList<Statement>(statements.size)
        for (i in statements.indices) out.add(flat.statementAt(i))
        return out
    }

    private fun poolOf(statements: List<Statement>): ConstantPool {
        val pool = ConstantPool()
        collectExprs(statements, pool)
        return pool
    }

    /** 语句树里的全部 Expr 结点尽量入池（漏收集无害——encoder 会按身份旁挂，往返照样成立）。 */
    private fun collectExprs(statements: List<Statement>, pool: ConstantPool) {
        val seen = java.util.IdentityHashMap<Expr, Boolean>()
        fun walk(e: Expr?) {
            if (e == null || seen.put(e, true) == true) return
            if (e is VarRef) e.indices.forEach { walk(it) }
            if (e is com.eratw.port.core.expr.KeywordIndexArg) walk(e.expr)
            pool.addExpr(e)
        }
        for (s in statements) for (e in exprsOf(s)) walk(e)
    }

    private fun exprsOf(s: Statement): List<Expr?> = when (s) {
        is AssignStmt -> listOf<Expr?>(s.dest) + argExprs(s.args)
        is IfStmt -> listOf(s.condition) + argExprs(s.args)
        is ElseIfStmt -> listOf(s.condition) + argExprs(s.args)
        is SelectCaseStmt -> listOf(s.target) + argExprs(s.args)
        is WhileStmt -> listOf(s.condition) + argExprs(s.args)
        is LoopStmt -> listOf(s.condition) + argExprs(s.args)
        is ReturnStmt -> s.values + argExprs(s.args)
        is ReturnFStmt -> listOf(s.value) + argExprs(s.args)
        is CaseStmt -> s.cases.flatMap { listOf(it.leftTerm, it.rightTerm) } + argExprs(s.args)
        else -> argExprs(s.args)
    }

    private fun argExprs(a: Argument): List<Expr?> = when (a) {
        is VoidArgument -> emptyList()
        is ExpressionArgument -> listOf(a.term)
        is ExpressionArrayArgument -> a.terms
        is ExpressionsArgument -> a.terms
        is IntAsignArgument -> listOf(a.exp)
        is StrAsignArgument -> emptyList()
        is BitArgument -> listOf(a.dest) + a.terms
        is CaseArgument -> a.caseExps.flatMap { listOf(it.leftTerm, it.rightTerm) }
        is MethodArgument -> listOf(a.methodTerm)
        is PrintDataArgument -> listOf(a.dest)
        is RefArgument -> listOf(a.srcTerm)
        is StrDataArgument -> listOf(a.dest)
        is SpArrayControlArgument -> listOf(a.dest, a.num1, a.num2)
        is SpArrayShiftArgument -> listOf(a.dest, a.num1, a.num2, a.num3, a.num4)
        is SpArraySortArgument -> listOf(a.dest, a.num1, a.num2)
        is SpBarArgument -> listOf(a.value, a.max, a.length)
        is SpButtonArgument -> listOf(a.printStrTerm, a.buttonWord)
        is SpCVarSetArgument -> listOf(a.dest, a.index, a.term, a.start, a.end)
        is SpCallArgument -> listOf(a.funcNameTerm) + a.subNames + a.rowArgs
        is SpCallFArgument -> listOf(a.funcNameTerm) + a.subNames + a.rowArgs
        is SpCallSharpArgument -> listOf(a.funcNameTerm) + a.subNames + a.rowArgs
        is SpColorArgument -> listOf(a.r, a.g, a.b, a.rgb)
        is SpCopyArrayArgument -> listOf(a.varName1, a.varName2)
        is SpDtColumnOptions -> listOf(a.dt, a.column) + a.values
        is SpForNextArgument -> listOf(a.cnt, a.start, a.end, a.step)
        is SpGetIntArgument -> listOf(a.dest)
        is SpHtmlPrint -> listOf(a.str, a.opt)
        is SpHtmlSplitArgument -> listOf(a.targetStr, a.num)
        is SpInputsArgument -> listOf(a.def, a.mouse, a.canSkip)
        is SpPowerArgument -> listOf(a.dest, a.x, a.y)
        is SpPrintImgArgument -> listOf(a.name, a.nameb, a.namem) + (a.param?.flatMap { listOf(it.num) } ?: emptyList())
        is SpPrintShapeArgument -> a.param.flatMap { listOf(it.num) }
        is SpPrintVArgument -> a.terms
        is SpSaveDataArgument -> listOf(a.target, a.strExpression)
        is SpSaveVarArgument -> listOf(a.term, a.savMes)
        is SpSetArgument -> listOf(a.dest, a.term)
        is SpSetArrayArgument -> listOf(a.dest) + a.terms
        is SpSortcharaArgument -> listOf(a.sortKey)
        is SpSplitArgument -> listOf(a.targetStr, a.split, a.num)
        is SpSwapCharaArgument -> listOf(a.x, a.y)
        is SpSwapVarArgument -> listOf(a.var1, a.var2)
        is SpTInputsArgument -> listOf(a.time, a.def, a.disp, a.timeout, a.mouse, a.canSkip)
        is SpTimesArgument -> listOf(a.dest)
        is SpVarSetArgument -> listOf(a.dest, a.term, a.start, a.end)
        is SpVarsizeArgument -> emptyList()
        is StrDoubleArgument -> listOf(a.term)
        else -> emptyList()
    }

    // ------------------------------------------------------------------
    // 合成用例
    // ------------------------------------------------------------------

    private fun vref(name: String = "A", vararg idx: Int): VarRef =
        VarRef(name, idx.map { IntLiteral(it.toLong()) }, operandType = OperandType.INT)

    private fun il(v: Long) = IntLiteral(v)
    private fun sl(s: String) = StrLiteral(s)

    private fun spec(name: String): PrintSpec = PrintSpec.parse(
        FunctionCode.valueOf(name),
    ) ?: error("PrintSpec.parse 失败: $name")

    @Test
    fun `全形态往返 - 语句类覆盖`() {
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
                keyword = "DIM", values = listOf(1L, Long.MAX_VALUE, -1L), name = "V", isString = false,
                line = 51, raw = "raw",
            ),
            SharpStmt(keyword = "LOCALSIZE", values = listOf(16L), line = 52, raw = "raw"),
            DataStmt(FunctionCode.DATA, "文本", VoidArgument(), 53, "raw"),
        )
        val back = roundTrip(statements)
        assertEquals(statements.size, back.size)
        for (i in statements.indices) {
            assertEquals(statements[i].javaClass, back[i].javaClass, "[$i] 类")
            assertEquals(statements[i].code, back[i].code, "[$i] code")
            assertEquals(statements[i].line, back[i].line, "[$i] line")
            assertEquals(FlatStmtKey.key(statements[i]), FlatStmtKey.key(back[i]), "[$i] 结构键")
        }
        // raw 口径：还原件恒 ""（运行期 releaseStatementRaws 后的同口径）
        assertTrue(back.all { it.raw.isEmpty() })
    }

    @Test
    fun `全形态往返 - 参数形态覆盖`() {
        val mk: List<Pair<String, Argument>> = listOf(
            "VOID" to VoidArgument(),
            "EXPRESSION null" to ExpressionArgument(null),
            "EXPRESSION" to ExpressionArgument(il(1)),
            "EXPRESSION_ARRAY 空" to ExpressionArrayArgument(emptyList()),
            "EXPRESSION_ARRAY" to ExpressionArrayArgument(listOf(il(1), null, sl("s"))),
            "EXPRESSIONS" to ExpressionsArgument(
                listOf(ArgValueType.INT, null, ArgValueType.STR, ArgValueType.VOID),
                listOf(il(1), null, sl("s"), null),
            ),
            "INT_ASIGN" to IntAsignArgument("N", intArrayOf(), null),
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
            "SP_COLOR rgb" to SpColorArgument(il(0xFF)),
            "SP_COLOR rgb 分量" to SpColorArgument(il(1), il(2), il(3)),
            "SP_COPY_ARRAY" to SpCopyArrayArgument(il(1), il(2)),
            "SP_DT_COLUMN_OPTIONS" to SpDtColumnOptions(il(1), null, listOf(SpDtColumnOptions.DTOption.DEFAULT), listOf(sl("v"), null)),
            "SP_FOR_NEXT" to SpForNextArgument(vref("F"), null, null, null),
            "SP_GET_INT" to SpGetIntArgument(vref("GI")),
            "SP_HTML_PRINT" to SpHtmlPrint(sl("h"), null),
            "SP_HTML_SPLIT" to SpHtmlSplitArgument(sl("t"), "DV", vref("NUM")),
            "SP_INPUTS" to SpInputsArgument(il(1), il(0), null),
            "SP_POWER" to SpPowerArgument(vref("PW"), il(1), il(2)),
            "SP_PRINT_IMG" to SpPrintImgArgument(sl("n"), null, sl("m"), listOf(MixedIntegerExprTerm(il(1), true), MixedIntegerExprTerm(null, false))),
            "SP_PRINT_IMG null 表" to SpPrintImgArgument(sl("n2"), il(1), null, null),
            "SP_PRINT_SHAPE 空" to SpPrintShapeArgument(emptyList()),
            "SP_PRINT_SHAPE" to SpPrintShapeArgument(listOf(MixedIntegerExprTerm(il(2), false))),
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
        val statements = mk.mapIndexed { i, (label, arg) ->
            InstructionStmt(FunctionCode.REUSELASTLINE, arg, 100 + i, "raw")
        }
        val back = roundTrip(statements)
        for (i in statements.indices) {
            assertEquals(FlatStmtKey.key(statements[i]), FlatStmtKey.key(back[i]), "[${i}] ${mk[i].first}")
            // 参数头公共槽逐位复核（isConst/constStr/constInt）
            val a0 = statements[i].args
            val a1 = back[i].args
            assertEquals(a0.isConst, a1.isConst, "[${i}] ${mk[i].first} isConst")
            assertEquals(a0.constStr, a1.constStr, "[${i}] ${mk[i].first} constStr")
            assertEquals(a0.constInt, a1.constInt, "[${i}] ${mk[i].first} constInt")
        }
    }

    @Test
    fun `常量载荷往返`() {
        fun stmt(a: Argument) = InstructionStmt(FunctionCode.REUSELASTLINE, a, 1, "raw")
        // 全零载荷（isConst=true 但 constStr="" constInt=0）：erbc header 判定 hasPayload=true，位必须保真
        val zero = VoidArgument().apply { isConst = true }
        // 仅 constInt
        val intOnly = ExpressionArgument(il(7)).apply { isConst = true; constInt = 42L }
        // 仅 constStr
        val strOnly = ExpressionArgument(sl("S")).apply { constStr = "C" }
        val back = roundTrip(listOf(stmt(zero), stmt(intOnly), stmt(strOnly)))
        assertEquals(true, back[0].args.isConst)
        assertEquals("", back[0].args.constStr)
        assertEquals(0L, back[0].args.constInt)
        assertEquals(true, back[1].args.isConst)
        assertEquals(42L, back[1].args.constInt)
        assertEquals("C", back[2].args.constStr)
    }

    @Test
    fun `池外结点旁挂`() {
        val pool = ConstantPool()
        val inPool = il(100)
        pool.addExpr(inPool)
        val out200 = il(200) // 池外实例，两条语句共享同一身份
        val statements = listOf(
            InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(inPool), 1, "raw"),
            InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(out200), 2, "raw"),
            InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(out200), 3, "raw"),
        )
        val flat = FlatStatements.encode(statements, pool.exprList)
        assertEquals(1, flat.extraExprs.size, "池内实例不旁挂；池外同一实例只旁挂一次")
        val back1 = flat.statementAt(1)
        val back2 = flat.statementAt(2)
        assertEquals(out200, (back1.args as ExpressionArgument).term)
        org.junit.jupiter.api.Assertions.assertSame(
            (back1.args as ExpressionArgument).term,
            (back2.args as ExpressionArgument).term,
            "同一池外实例归同一下标 ⇒ 还原出同一实例",
        )
    }

    @Test
    fun `For 最小复现`() {
        val statements = listOf(
            ForStmt(FunctionCode.FOR, SpForNextArgument(vref("LOOP"), il(0), il(9), il(1)), 33, "raw"),
        )
        val back = roundTrip(statements)
        assertEquals(FlatStmtKey.key(statements[0]), FlatStmtKey.key(back[0]), "FOR 往返")
    }

    @Test
    fun `区间越界防御`() {
        val flat = FlatStatements.encode(listOf(InstructionStmt(FunctionCode.QUIT, VoidArgument(), 1, "raw")), emptyList())
        org.junit.jupiter.api.assertThrows<IndexOutOfBoundsException> { flat.statementAt(-1) }
        org.junit.jupiter.api.assertThrows<IndexOutOfBoundsException> { flat.statementAt(1) }
    }

    // ------------------------------------------------------------------
    // T2-64 波次 C：增量压扁器（装载期「压扁即弃」的写码入口）
    // ------------------------------------------------------------------

    @Test
    fun `增量压扁与一次性压扁逐槽相同`() {
        val statements = listOf(
            PrintStmt(spec("PRINT"), ExpressionArgument(sl("hi")), 11, "raw"),
            AssignStmt(vref("A", 0), OperatorCode.PLUS, ExpressionArgument(il(3)), 16, "raw"),
            IfStmt(il(1), ExpressionArgument(il(1)), 24, "raw"),
            FuncLabelStmt("FUNC", listOf("A", "B"), 49, "raw"),
            DataStmt(FunctionCode.DATA, "文本", VoidArgument(), 53, "raw"),
            ForStmt(FunctionCode.FOR, SpForNextArgument(vref("LOOP"), il(0), il(9), il(1)), 33, "raw"),
        )
        val pool = poolOf(statements)
        val whole = FlatStatements.encode(statements, pool.exprList)

        val builder = FlatStatements.Builder(pool.exprList, statements.size)
        assertEquals(0, builder.count, "空压扁器的条数为 0")
        for (s in statements) builder.append(s)
        assertEquals(statements.size, builder.count, "append 条数")
        assertEquals(whole.stream.size, builder.streamSize, "写完的参数槽数 = 一次性压扁的 stream.size")
        val inc = builder.build()

        org.junit.jupiter.api.Assertions.assertArrayEquals(whole.ops, inc.ops, "ops")
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole.heads, inc.heads, "heads（末项 = stream.size）")
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole.lines, inc.lines, "lines")
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole.stream, inc.stream, "参数流逐槽")
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole.strings, inc.strings, "字符串旁表（按首次出现序）")
        assertEquals(whole.poolSize, inc.poolSize, "池内条数")
        assertEquals(whole.extraExprs.size, inc.extraExprs.size, "池外旁挂条数")
        assertEquals(whole.stream.size, inc.heads[inc.count], "末条 head = 流长")
        for (i in statements.indices) {
            assertEquals(FlatStmtKey.key(whole.statementAt(i)), FlatStmtKey.key(inc.statementAt(i)), "[$i] 还原结构键")
        }
    }

    @Test
    fun `增量压扁空序列与同池身份往返`() {
        val empty = FlatStatements.Builder().build()
        assertEquals(0, empty.count, "空池条数")
        assertEquals(1, empty.heads.size, "空池的 heads 只有末项")
        assertEquals(0, empty.heads[0], "空池首项 head = 0")
        assertEquals(0, empty.stream.size, "空池流长 0")

        // 池外实例按身份去重：builder 与 encode 的旁挂序也必须一致
        val out = il(200)
        val s1 = InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(out), 1, "raw")
        val s2 = InstructionStmt(FunctionCode.REUSELASTLINE, ExpressionArgument(out), 2, "raw")
        val flat = FlatStatements.Builder().apply { append(s1); append(s2) }.build()
        assertEquals(1, flat.extraExprs.size, "同一池外实例只旁挂一次")
        org.junit.jupiter.api.Assertions.assertSame(
            (flat.statementAt(0).args as ExpressionArgument).term,
            (flat.statementAt(1).args as ExpressionArgument).term,
            "同一池外实例归同一下标 ⇒ 还原出同一实例",
        )
    }

    @Test
    fun `压扁器 build 之后拒绝再追加或重复 build`() {
        val builder = FlatStatements.Builder()
        builder.append(InstructionStmt(FunctionCode.QUIT, VoidArgument(), 1, "raw"))
        builder.build()
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            builder.append(InstructionStmt(FunctionCode.QUIT, VoidArgument(), 2, "raw"))
        }
        org.junit.jupiter.api.assertThrows<IllegalStateException> { builder.build() }
    }
}

/**
 * 语句结构键（对拍「尺」）：誊自 `stmt/Statement.kt` / `stmt/Argument.kt` 字段声明，
 * **独立于 FlatStmtCodec 的字段清单**。Expr 层直接 data class equals；
 * `raw`/`SharpStmt.tokens`/`FuncLabelStmt.paramRefs·paramDefaults` 按装载口径排除。
 */
internal object FlatStmtKey {
    fun key(s: Statement): List<Any?> = listOf(tag(s), keyArgs(s.args)) + keyStmt(s)

    private fun tag(s: Statement): String = when (s) {
        is PrintStmt -> "PRINT"
        is PrintDataStmt -> "PRINT_DATA"
        is AssignStmt -> "ASSIGN"
        is JumpStmt -> "JUMP"
        is CallStmt -> "CALL"
        is CallFStmt -> "CALL_F"
        is CallSharpStmt -> "CALL_SHARP"
        is MethodStmt -> "METHOD"
        is VarDeclStmt -> "VAR_DECL"
        is IfStmt -> "IF"
        is ElseIfStmt -> "ELSE_IF"
        is ElseStmt -> "ELSE"
        is EndIfStmt -> "END_IF"
        is SelectCaseStmt -> "SELECT_CASE"
        is CaseStmt -> "CASE"
        is CaseElseStmt -> "CASE_ELSE"
        is EndSelectStmt -> "END_SELECT"
        is ForStmt -> "FOR"
        is NextStmt -> "NEXT"
        is RepeatStmt -> "REPEAT"
        is RendStmt -> "REND"
        is WhileStmt -> "WHILE"
        is WendStmt -> "WEND"
        is DoStmt -> "DO"
        is LoopStmt -> "LOOP"
        is CatchStmt -> "CATCH"
        is EndCatchStmt -> "END_CATCH"
        is DataListStmt -> "DATA_LIST"
        is EndBlockStmt -> "END_BLOCK"
        is FlowStmt -> "FLOW"
        is ReturnStmt -> "RETURN"
        is ReturnFStmt -> "RETURN_F"
        is FuncLabelStmt -> "FUNC_LABEL"
        is GotoLabelStmt -> "GOTO_LABEL"
        is SharpStmt -> "SHARP"
        is DataStmt -> "DATA"
        is InstructionStmt -> "INSTRUCTION"
        else -> error("未知语句类: ${s.javaClass.name}")
    }

    private fun keyStmt(s: Statement): List<Any?> = when (s) {
        is PrintStmt -> listOf(s.code, printSpec(s.print))
        is PrintDataStmt -> listOf(s.code)
        is AssignStmt -> listOf(s.code, s.dest, s.op)
        is JumpStmt -> listOf(s.code, s.label)
        is CallStmt -> listOf(s.code, s.label)
        is CallFStmt -> listOf(s.code, s.functionName)
        is CallSharpStmt -> listOf(s.code)
        is MethodStmt -> listOf(s.code, s.methodName)
        is VarDeclStmt -> listOf(s.code)
        is IfStmt -> listOf(s.code, s.condition, s.single)
        is ElseIfStmt -> listOf(s.code, s.condition)
        is ElseStmt -> listOf(s.code)
        is EndIfStmt -> listOf(s.code)
        is SelectCaseStmt -> listOf(s.code, s.target)
        is CaseStmt -> listOf(s.code, s.cases.map { listOf(it.caseType, it.leftTerm, it.rightTerm, it.operator) })
        is CaseElseStmt -> listOf(s.code)
        is EndSelectStmt -> listOf(s.code)
        is ForStmt -> listOf(s.code)
        is NextStmt -> listOf(s.code)
        is RepeatStmt -> listOf(s.code)
        is RendStmt -> listOf(s.code)
        is WhileStmt -> listOf(s.code, s.condition)
        is WendStmt -> listOf(s.code)
        is DoStmt -> listOf(s.code)
        is LoopStmt -> listOf(s.code, s.condition)
        is CatchStmt -> listOf(s.code)
        is EndCatchStmt -> listOf(s.code)
        is DataListStmt -> listOf(s.code)
        is EndBlockStmt -> listOf(s.code)
        is FlowStmt -> listOf(s.code)
        is ReturnStmt -> listOf(s.code, s.values)
        is ReturnFStmt -> listOf(s.code, s.value)
        is FuncLabelStmt -> listOf(s.name, s.paramNames)
        is GotoLabelStmt -> listOf(s.name)
        is SharpStmt -> listOf(s.keyword, s.values, s.name, s.isString)
        is DataStmt -> listOf(s.code, s.text)
        is InstructionStmt -> listOf(s.code)
        else -> error("未知语句类: ${s.javaClass.name}")
    }

    private fun printSpec(p: PrintSpec): List<Any?> = listOf(
        p.code, p.argType, p.isLineEnd, p.isSingleLine, p.isPrintV, p.isS, p.isForms,
        p.isC, p.isLC, p.isK, p.isD, p.isN, p.flag,
    )

    private fun keyArgs(a: Argument): List<Any?> = when (a) {
        is VoidArgument -> listOf("VOID")
        is ExpressionArgument -> listOf("EXP", a.term)
        is ExpressionArrayArgument -> listOf("EXPA", a.terms)
        is ExpressionsArgument -> listOf("EXPS", a.types, a.terms)
        is IntAsignArgument -> listOf("IA", a.name, a.lengths.toList(), a.exp)
        is StrAsignArgument -> listOf("SA", a.name, a.lengths.toList(), a.value)
        is BitArgument -> listOf("BIT", a.dest, a.terms)
        is CaseArgument -> listOf("CASE", a.caseExps.map { listOf(it.caseType, it.leftTerm, it.rightTerm, it.operator) })
        is MethodArgument -> listOf("METHOD", a.methodTerm)
        is PrintDataArgument -> listOf("PD", a.dest)
        is RefArgument -> listOf("REF", a.refName, a.srcName, a.srcTerm, a.refByName)
        is StrDataArgument -> listOf("SD", a.dest)
        is SpArrayControlArgument -> listOf("AC", a.dest, a.num1, a.num2)
        is SpArrayShiftArgument -> listOf("ASH", a.dest, a.num1, a.num2, a.num3, a.num4)
        is SpArraySortArgument -> listOf("ASO", a.dest, a.order, a.num1, a.num2)
        is SpBarArgument -> listOf("BAR", a.value, a.max, a.length)
        is SpButtonArgument -> listOf("BTN", a.printStrTerm, a.buttonWord)
        is SpCVarSetArgument -> listOf("CVS", a.dest, a.index, a.term, a.start, a.end)
        is SpCallArgument -> listOf("CALL", a.funcNameTerm, a.subNames, a.rowArgs)
        is SpCallFArgument -> listOf("CALLF", a.funcNameTerm, a.subNames, a.rowArgs)
        is SpCallSharpArgument -> listOf("CALL#", a.funcNameTerm, a.subNames, a.rowArgs)
        is SpColorArgument -> listOf("COLOR", a.r, a.g, a.b, a.rgb)
        is SpCopyArrayArgument -> listOf("CPA", a.varName1, a.varName2)
        is SpDtColumnOptions -> listOf("DTC", a.dt, a.column, a.options, a.values)
        is SpForNextArgument -> listOf("FOR", a.cnt, a.start, a.end, a.step)
        is SpGetIntArgument -> listOf("GI", a.dest)
        is SpHtmlPrint -> listOf("HP", a.str, a.opt)
        is SpHtmlSplitArgument -> listOf("HPS", a.targetStr, a.destVarName, a.num)
        is SpInputsArgument -> listOf("IN", a.def, a.mouse, a.canSkip)
        is SpPowerArgument -> listOf("PW", a.dest, a.x, a.y)
        is SpPrintImgArgument -> listOf("IMG", a.name, a.nameb, a.namem, a.param?.map { listOf(it.num, it.isPx) })
        is SpPrintShapeArgument -> listOf("SHAPE", a.param.map { listOf(it.num, it.isPx) })
        is SpPrintVArgument -> listOf("PV", a.terms)
        is SpSaveDataArgument -> listOf("SAVE", a.target, a.strExpression)
        is SpSaveVarArgument -> listOf("SV", a.term, a.savMes, a.varNames)
        is SpSetArgument -> listOf("SET", a.dest, a.term, a.addConst)
        is SpSetArrayArgument -> listOf("SETA", a.dest, a.terms, a.constIntList?.toList(), a.constStrList?.toList())
        is SpSortcharaArgument -> listOf("SORT", a.sortKey, a.sortOrder)
        is SpSplitArgument -> listOf("SPLIT", a.targetStr, a.split, a.destVarName, a.num)
        is SpSwapCharaArgument -> listOf("SWC", a.x, a.y)
        is SpSwapVarArgument -> listOf("SWV", a.var1, a.var2)
        is SpTInputsArgument -> listOf("TIN", a.time, a.def, a.disp, a.timeout, a.mouse, a.canSkip)
        is SpTimesArgument -> listOf("TIMES", a.dest, a.doubleValue)
        is SpVarSetArgument -> listOf("VARSET", a.dest, a.term, a.start, a.end)
        is SpVarsizeArgument -> listOf("VARSIZE", a.varName)
        is StrDoubleArgument -> listOf("SD2", a.term, a.doubleValue)
        else -> error("未知参数类: ${a.javaClass.name}")
    }
}
