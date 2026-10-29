package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语句层行解析单测 —— 覆盖 isa.yaml 的每个语句族、两段式分派、PRINT 后缀变体、
 * 块平衡错误与全角标识符参数。
 */
class LineParserTest {

    /** 测试用符号表：常用内建 + 用户变量。 */
    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("B", OperandType.INT, 1)
            .declareVariable("C", OperandType.INT, 0)
            .declareVariable("S", OperandType.STR, 1)
            .declareVariable("T", OperandType.STR, 0)
            .declareVariable("好感度", OperandType.INT, 1)
            .declareVariable("计数", OperandType.INT, 0)
            .declareFunction("MYFUNC", OperandType.INT)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 10): Statement? =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", lineNo, text))

    private fun code(s: Statement?): FunctionCode {
        assertNotNull(s)
        return s.code
    }

    // -----------------------------------------------------------------------
    // 指令分派与别名
    // -----------------------------------------------------------------------

    @Test
    fun `普通指令`() {
        val s = parse("PRINTL こんにちは")
        assertIs<PrintStmt>(s)
        assertEquals(FunctionCode.PRINTL, s.code)
        assertEquals(ArgType.STR_NULLABLE, s.print.argType)
        assertTrue(s.isLineEnd)
        assertEquals("こんにちは", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `无参指令`() {
        assertEquals(FunctionCode.RESETCOLOR, code(parse("RESETCOLOR")))
        // 行尾注释
        assertEquals(FunctionCode.RESETCOLOR, code(parse("RESETCOLOR ; 注释")))
    }

    @Test
    fun `指令后非法字符报错`() {
        val e = assertFailsWith<StmtParseException> { parse("RESETCOLORX") }
        assertTrue(e.message!!.contains("非法字符") || e.message!!.contains("无法") || e.statementName.isNotEmpty())
    }

    @Test
    fun `别名分派`() {
        // ELSE 与 ELSEIF 是两个函数码，但共享实现
        assertEquals(FunctionCode.ELSE, code(parse("ELSE")))
        assertEquals(FunctionCode.CASEELSE, code(parse("CASEELSE")))
        assertEquals(FunctionCode.NEXT, code(parse("NEXT")))
        assertEquals(FunctionCode.REND, code(parse("REND")))
        assertEquals(FunctionCode.ENDFUNC, code(parse("ENDFUNC")))
    }

    // -----------------------------------------------------------------------
    // PRINT 家族后缀
    // -----------------------------------------------------------------------

    @Test
    fun `PRINT 后缀变体`() {
        // V：逐项输出
        val v = assertIs<PrintStmt>(parse("PRINTVL A, B"))
        assertEquals(ArgType.SP_PRINTV, v.print.argType)
        assertTrue(v.print.isPrintV)
        assertTrue(v.isLineEnd)
        assertEquals(2, (v.args as SpPrintVArgument).terms.size)

        // FORM：格式化串
        val f = assertIs<PrintStmt>(parse("PRINTFORMW 値は{A}です"))
        assertEquals(ArgType.FORM_STR_NULLABLE, f.print.argType)
        assertTrue(f.print.isLineEnd)

        // FORML：FORM + 换行
        val fl = assertIs<PrintStmt>(parse("PRINTFORML {A}"))
        assertEquals(ArgType.FORM_STR_NULLABLE, fl.print.argType)

        // N：不换行
        val n = assertIs<PrintStmt>(parse("PRINTN 続く"))
        assertEquals(false, n.isLineEnd)
        assertTrue(n.print.isN)

        // K：类型转换；D：调试配色；C/LC：定宽对齐
        assertTrue(assertIs<PrintStmt>(parse("PRINTK 1")).print.isK)
        assertTrue(assertIs<PrintStmt>(parse("PRINTD 1")).print.isD)
        assertTrue(assertIs<PrintStmt>(parse("PRINTC 1")).print.isC)
        assertTrue(assertIs<PrintStmt>(parse("PRINTLC 1")).print.isLC)

        // SINGLE：单行独立显示
        val single = assertIs<PrintStmt>(parse("PRINTSINGLEV A"))
        assertTrue(single.print.isSingleLine)
        assertEquals(ArgType.SP_PRINTV, single.print.argType)

        // S：字符串表达式（可带 FORMS 前缀）
        assertEquals(ArgType.STR_EXPRESSION, assertIs<PrintStmt>(parse("PRINTS T")).print.argType)
        assertTrue(assertIs<PrintStmt>(parse("PRINTFORMS \"{A}\"")).print.isForms)
    }

    @Test
    fun `PRINTFORM 内的逗号不被截断`() {
        // FormStrEndWith.EoL 语义：顶层逗号是字面量
        val s = assertIs<PrintStmt>(parse("PRINTFORM {A},{B}"))
        val form = (s.args as ExpressionArgument).term as com.eratw.port.core.expr.FormStringTerm
        assertEquals(2, form.parts.count { it is com.eratw.port.core.expr.FormPart.Embedded })
    }

    @Test
    fun `PRINTV 的单引号字符串`() {
        val s = assertIs<PrintStmt>(parse("PRINTV 'abc', A"))
        val terms = (s.args as SpPrintVArgument).terms
        assertEquals(2, terms.size)
        assertEquals(OperandType.STR, terms[0].operandType)
    }

    // -----------------------------------------------------------------------
    // 赋值（两段式）
    // -----------------------------------------------------------------------

    @Test
    fun `整数赋值`() {
        val s = assertIs<AssignStmt>(parse("A = 1"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.ASSIGNMENT, s.op)
        assertEquals("A", s.dest.name)
        assertTrue((s.args as SpSetArgument).isConst)
        assertEquals(1L, (s.args as SpSetArgument).constInt)
    }

    @Test
    fun `字符串赋值与单引号赋值`() {
        val s = assertIs<AssignStmt>(parse("S:0 '= \"abc\""))
        assertEquals(com.eratw.port.core.expr.OperatorCode.ASSIGNMENT_STR, s.op)
        assertEquals("S", s.dest.name)
        assertEquals(1, s.dest.indices.size)
        assertEquals("abc", (s.args as SpSetArgument).constStr)

        val s2 = assertIs<AssignStmt>(parse("T = 文字列{A}"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.ASSIGNMENT, s2.op)
        assertEquals(OperandType.STR, s2.dest.operandType)
    }

    @Test
    fun `复合赋值`() {
        val add = assertIs<AssignStmt>(parse("A += 2"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.PLUS, add.op)
        assertTrue((add.args as SpSetArgument).addConst)
        assertEquals(2L, (add.args as SpSetArgument).constInt)

        val minus = assertIs<AssignStmt>(parse("A -= 3"))
        assertEquals(-3L, (minus.args as SpSetArgument).constInt)

        val mult = assertIs<AssignStmt>(parse("A *= 2"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.MULT, mult.op)

        val plusStr = assertIs<AssignStmt>(parse("S += \"x\""))
        assertEquals(com.eratw.port.core.expr.OperatorCode.PLUS, plusStr.op)
    }

    @Test
    fun `自增减语句`() {
        val inc = assertIs<AssignStmt>(parse("A++"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.INCREMENT, inc.op)
        assertEquals("A", inc.dest.name)
        assertTrue((inc.args as SpSetArgument).addConst)

        val dec = assertIs<AssignStmt>(parse("--A:1"))
        assertEquals(com.eratw.port.core.expr.OperatorCode.DECREMENT, dec.op)
        assertEquals(1, dec.dest.indices.size)
    }

    @Test
    fun `数组赋值`() {
        val s = assertIs<AssignStmt>(parse("A = 1, 2, 3"))
        val arg = assertIs<SpSetArrayArgument>(s.args)
        assertEquals(3, arg.terms.size)
        assertEquals(listOf(1L, 2L, 3L), arg.constIntList?.toList())

        val s2 = assertIs<AssignStmt>(parse("S '= \"a\", \"b\""))
        assertEquals(listOf("a", "b"), assertIs<SpSetArrayArgument>(s2.args).constStrList?.toList())
    }

    @Test
    fun `赋值两段式保护右值的等号与括号`() {
        // 右值里的 == 不参与左值切分
        val s = assertIs<AssignStmt>(parse("C = A == 1"))
        assertEquals("C", s.dest.name)
        // 右值的括号与字符串里的 '=' 全部合法
        // 字符串 `=` 赋值的右值是 FORM 原文：引号是字面量（这正是 `'=` 存在的原因）
        val s2 = assertIs<AssignStmt>(parse("T = \"a=b(c)\""))
        assertEquals("\"a=b(c)\"", (s2.args as SpSetArgument).constStr)
        val s3 = assertIs<AssignStmt>(parse("T '= \"a=b(c)\""))
        assertEquals("a=b(c)", (s3.args as SpSetArgument).constStr)
    }

    @Test
    fun `赋值类型错误报错`() {
        assertFailsWith<StmtParseException> { parse("A = \"文字\"") }
        assertFailsWith<StmtParseException> { parse("S '= 1") }
        assertFailsWith<StmtParseException> { parse("UNDEFINED_VAR_X = 1") } // 未声明
    }

    // -----------------------------------------------------------------------
    // 条件与块结构
    // -----------------------------------------------------------------------

    @Test
    fun `IF 家族`() {
        assertEquals(FunctionCode.IF, code(parse("IF A == 1")))
        assertEquals(FunctionCode.ELSEIF, code(parse("ELSEIF A == 2")))
        assertEquals(FunctionCode.ELSE, code(parse("ELSE")))
        assertEquals(FunctionCode.ENDIF, code(parse("ENDIF")))
        val sif = assertIs<IfStmt>(parse("SIF A"))
        assertTrue(sif.single)
    }

    @Test
    fun `SELECTCASE 家族`() {
        assertEquals(FunctionCode.SELECTCASE, code(parse("SELECTCASE A")))
        val c = assertIs<CaseStmt>(parse("CASE 1, 2 TO 5, IS > 10"))
        assertEquals(3, c.cases.size)
        assertEquals(CaseExpression.CaseType.NORMAL, c.cases[0].caseType)
        assertEquals(CaseExpression.CaseType.TO, c.cases[1].caseType)
        assertEquals(CaseExpression.CaseType.IS, c.cases[2].caseType)
        assertEquals(FunctionCode.CASEELSE, code(parse("CASEELSE")))
        assertEquals(FunctionCode.ENDSELECT, code(parse("ENDSELECT")))
    }

    @Test
    fun `循环家族`() {
        assertEquals(FunctionCode.FOR, code(parse("FOR A, 0, 1, 10")))
        assertEquals(FunctionCode.NEXT, code(parse("NEXT")))
        assertEquals(FunctionCode.REPEAT, code(parse("REPEAT 3")))
        assertEquals(FunctionCode.REND, code(parse("REND")))
        assertEquals(FunctionCode.WHILE, code(parse("WHILE A < 10")))
        assertEquals(FunctionCode.WEND, code(parse("WEND")))
        assertEquals(FunctionCode.DO, code(parse("DO")))
        assertEquals(FunctionCode.LOOP, code(parse("LOOP A < 3")))
        val forStmt = assertIs<ForStmt>(parse("FOR A, 1, 10"))
        val arg = assertIs<SpForNextArgument>(forStmt.args)
        assertEquals("A", arg.cnt.name)
    }

    @Test
    fun `流程控制`() {
        assertEquals(FunctionCode.BREAK, code(parse("BREAK")))
        assertEquals(FunctionCode.CONTINUE, code(parse("CONTINUE")))
        assertEquals(FunctionCode.RETURN, code(parse("RETURN")))
        assertEquals(FunctionCode.RETURN, code(parse("RETURN 1")))
        assertEquals(FunctionCode.RETURNF, code(parse("RETURNF A")))
        assertEquals(FunctionCode.RETURNFORM, code(parse("RETURNFORM {A}")))
    }

    @Test
    fun `跳转与调用`() {
        val jump = assertIs<JumpStmt>(parse("JUMP ラベル"))
        assertEquals("ラベル", jump.label)
        assertEquals(FunctionCode.GOTO, code(parse("GOTO ラベル")))

        val call = assertIs<CallStmt>(parse("CALL 関数(A, \"s\")"))
        assertEquals("関数", call.label)
        val sp = assertIs<SpCallArgument>(call.args)
        assertEquals(2, sp.rowArgs.size)

        val callf = assertIs<CallFStmt>(parse("CALLF MYFUNC, 1"))
        assertEquals("MYFUNC", callf.functionName)
        assertEquals(FunctionCode.CALLF, callf.code)

        val sharp = assertIs<CallSharpStmt>(parse("CALLSHARP Lib.Method, 1"))
        assertEquals(FunctionCode.CALLSHARP, sharp.code)
    }

    @Test
    fun `CALL 的 FORM 参数形态`() {
        // 语料实测形态：CALL 名, @"..."（spec: ArgumentParser.cs:802-875 的 current == ',' 分支）
        val call = assertIs<CallStmt>(parse("CALL PRINTBUTTON_EX, @\"[{A}]\"\n"))
        val sp = assertIs<SpCallArgument>(call.args)
        assertEquals(1, sp.rowArgs.size)
        assertEquals(0, sp.subNames.size)
    }

    // -----------------------------------------------------------------------
    // 数据节 / 变量声明 / 标签 / 预处理
    // -----------------------------------------------------------------------

    @Test
    fun `数据节`() {
        assertEquals(FunctionCode.DATA, code(parse("DATA テキスト")))
        assertEquals(FunctionCode.DATAFORM, code(parse("DATAFORM {A}です")))
        assertEquals(FunctionCode.DATALIST, code(parse("DATALIST")))
        assertEquals(FunctionCode.ENDLIST, code(parse("ENDLIST")))
        assertEquals(FunctionCode.ENDDATA, code(parse("ENDDATA")))
        assertEquals(FunctionCode.STRDATA, code(parse("STRDATA")))
    }

    @Test
    fun `PRINTDATA 家族`() {
        val s = assertIs<PrintDataStmt>(parse("PRINTDATAL A"))
        assertEquals(FunctionCode.PRINTDATAL, s.code)
        assertEquals("A", (s.args as PrintDataArgument).dest?.name)
        // 无参形态
        assertNull((assertIs<PrintDataStmt>(parse("PRINTDATA")).args as PrintDataArgument).dest)
    }

    @Test
    fun `VARI 与 VARS`() {
        val vari = assertIs<VarDeclStmt>(parse("VARI X, 10, 20"))
        val ia = assertIs<IntAsignArgument>(vari.args)
        assertEquals("X", ia.name)
        assertEquals(listOf(10, 20), ia.lengths.toList())

        val variInit = assertIs<VarDeclStmt>(parse("VARI Y = 5"))
        assertEquals(5L, (assertIs<IntAsignArgument>(variInit.args).exp as com.eratw.port.core.expr.IntLiteral).value)

        // 带维度时初值被丢弃：原版只在「无 dim」分支读 '=' 之后
        // （spec: LogicalLineParser.cs:447-462 VARI / :485-493 VARS）
        val variDims = assertIs<VarDeclStmt>(parse("VARI Z, 2 = 5"))
        assertNull(assertIs<IntAsignArgument>(variDims.args).exp)

        val vars = assertIs<VarDeclStmt>(parse("VARS S$, 10 = \"hi\""))
        val sa = assertIs<StrAsignArgument>(vars.args)
        assertEquals("S$", sa.name)
        assertNull(sa.value, "带维度形态不吃初值（spec: LogicalLineParser.cs:485-493）")

        val varsInit = assertIs<VarDeclStmt>(parse("VARS T$ = \"hi\""))
        assertEquals("hi", assertIs<StrAsignArgument>(varsInit.args).value)
    }

    @Test
    fun `指令名后紧跟分号是分隔字符不是注释起点`() {
        // spec: LogicalLineParser.cs:517-523 —— ';' 与空白同为分隔符，只 ShiftNext 一次。
        // 原文型 PRINT（STR_NULLABLE，spec: ArgumentParser.cs:437-460 Substring()，
        // 不做 SkipWhiteSpace）拿的是分隔符**之后**的原文：
        // `PRINTL ;x` 消耗 1 个空格 → 原文 ";x"；`PRINTL;x` 消耗 ';' → 原文 "x"。
        val s = assertIs<PrintStmt>(parse("PRINTL ;x"))
        assertEquals(";x", (s.args as ExpressionArgument).constStr)
        val s2 = assertIs<PrintStmt>(parse("PRINTL;x"))
        assertEquals("x", (s2.args as ExpressionArgument).constStr)
        // 表达式位：';' 之后的原文交给词法 analyse 归约到行尾（spec: LexicalAnalyzer.cs:919-933）
        val sif = assertIs<IfStmt>(parse("SIF;1"))
        assertEquals(1L, (assertIs<com.eratw.port.core.expr.IntLiteral>(sif.condition)).value)
    }

    @Test
    fun `函数标签与跳转标签`() {
        val f = assertIs<FuncLabelStmt>(parseLabel("@EVENT_DAILY(A, B, C)"))
        assertEquals("EVENT_DAILY", f.name)
        assertEquals(listOf("A", "B", "C"), f.paramNames)

        val f2 = assertIs<FuncLabelStmt>(parseLabel("@SYSTEM_TITLE"))
        assertEquals("SYSTEM_TITLE", f2.name)
        assertTrue(f2.paramNames.isEmpty())

        val g = assertIs<GotoLabelStmt>(parseLabel("\$ラベル"))
        assertEquals("ラベル", g.name)
    }

    private fun parseLabel(text: String): Statement = parser().parseLabelLine(text, 7, "test.erb")

    @Test
    fun `预处理行`() {
        val dim = assertIs<SharpStmt>(parser().parseSharpLine("#DIM 好感度, 10", 3))
        assertEquals("DIM", dim.keyword)
        assertEquals("好感度", dim.name)
        assertEquals(listOf(10L), dim.values)

        val local = assertIs<SharpStmt>(parser().parseSharpLine("#LOCALSIZE 100", 3))
        assertEquals(listOf(100L), local.values)

        val func = assertIs<SharpStmt>(parser().parseSharpLine("#FUNCTION", 3))
        assertEquals("FUNCTION", func.keyword)
    }

    // -----------------------------------------------------------------------
    // 全角标识符 / 特殊参数形态
    // -----------------------------------------------------------------------

    @Test
    fun `全角标识符参数`() {
        val s = assertIs<PrintStmt>(parse("PRINTFORML 好感度：{好感度:0}"))
        val form = (s.args as ExpressionArgument).term as com.eratw.port.core.expr.FormStringTerm
        val embedded = form.parts.filterIsInstance<com.eratw.port.core.expr.FormPart.Embedded>()
        assertEquals(1, embedded.size)

        val assign = assertIs<AssignStmt>(parse("好感度:0 = 10"))
        assertEquals("好感度", assign.dest.name)
    }

    @Test
    fun `特殊参数形态`() {
        assertEquals(FunctionCode.SETCOLOR, code(parse("SETCOLOR 255, 0, 0")))
        assertEquals(FunctionCode.SETCOLORBYNAME, code(parse("SETCOLORBYNAME Red")))
        assertEquals(FunctionCode.SETBGIMAGE, code(parse("SETBGIMAGE \"bg.png\"")))
        assertEquals(FunctionCode.BAR, code(parse("BAR A, 100, 20")))
        assertEquals(FunctionCode.PRINTBUTTON, code(parse("PRINTBUTTON \"[1] 選択\", 1")))
        assertEquals(FunctionCode.INPUT, code(parse("INPUT")))
        val input = parse("INPUT 100, 0, 1")
        assertEquals(FunctionCode.INPUT, code(input))
        assertEquals(FunctionCode.TINPUT, code(parse("TINPUT 1000, 0")))
        assertEquals(FunctionCode.SPLIT, code(parse("SPLIT \"a,b\", \",\", S")))
        assertEquals(FunctionCode.VARSIZE, code(parse("VARSIZE A")))
        assertEquals(FunctionCode.SWAP, code(parse("SWAP A, B")))
        assertEquals(FunctionCode.ARRAYSHIFT, code(parse("ARRAYSHIFT A, 1, 0")))
        assertEquals(FunctionCode.ARRAYSORT, code(parse("ARRAYSORT A, FORWARD")))
        assertEquals(FunctionCode.HTML_PRINT, code(parse("HTML_PRINT \"<b>hi</b>\"")))
        assertEquals(FunctionCode.PRINT_IMG, code(parse("PRINT_IMG \"a.png\"")))
        assertEquals(FunctionCode.PRINT_RECT, code(parse("PRINT_RECT 10, 10, 20, 20")))
        assertEquals(FunctionCode.PRINT_SPACE, code(parse("PRINT_SPACE 10")))
        assertEquals(FunctionCode.SAVEDATA, code(parse("SAVEDATA 1, \"メモ\"")))
        assertEquals(FunctionCode.DT_COLUMN_OPTIONS, code(parse("DT_COLUMN_OPTIONS 1, 2, DEFAULT, 0")))
    }

    @Test
    fun `px 单位参数`() {
        // CMP-53：px 是**词法级**单位标记 —— 原版是「`100 px`」（px 独立成词，后随逗号/行尾
        // 时终止表达式，spec: ExpressionParser.cs:534-537 + ArgumentParser.cs:57/:124）。
        // 贴写的 `10px` 在词法层就报错（ReadInt64 把 `p` 当二进制指数，
        // spec: LexicalAnalyzer.cs:216-221），旧实现的文本剥离曾放行，已按原版收口。
        val s = parse("PRINT_RECT 10 px, 20 px, 30 px, 40 px")
        val arg = assertIs<SpPrintShapeArgument>(s!!.args)
        assertTrue(arg.param.all { it.isPx })
        assertEquals(4, arg.param.size)
    }

    @Test
    fun `METHOD 语句`() {
        val p = LineParser(object : StmtResolver {
            override fun resolveVariable(name: String, subKey: String?) = null
            override fun resolveFunction(name: String) = OperandType.INT
            override fun isUserMethod(name: String) = name.equals("MYMETHOD", ignoreCase = true)
        })
        val s = p.parseLogicalLine(LogicalLine(LineKind.CODE, "t", 1, "MYMETHOD 1, 2"))!!
        assertIs<MethodStmt>(s)
        assertEquals("MYMETHOD", (s as MethodStmt).methodName)
    }

    // -----------------------------------------------------------------------
    // 块平衡
    // -----------------------------------------------------------------------

    @Test
    fun `块平衡通过`() {
        val stmts = listOf(
            "IF A == 1", "ELSEIF A == 2", "ELSE", "ENDIF",
            "SELECTCASE A", "CASE 1", "CASEELSE", "ENDSELECT",
            "FOR A, 0, 3", "NEXT",
            "REPEAT 3", "REND",
            "WHILE A", "WEND",
            "DO", "LOOP A < 3",
        ).mapIndexed { i, t -> parse(t, i)!! }
        val errors = BlockChecker().check(stmts)
        assertTrue(errors.isEmpty(), "不应有块错误：$errors")
    }

    @Test
    fun `块未闭合报错`() {
        val stmts = listOf("IF A", "PRINTL 1").mapIndexed { i, t -> parse(t, i)!! }
        val errors = BlockChecker().check(stmts)
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("ENDIF"))
    }

    @Test
    fun `块错配报错`() {
        val stmts = listOf("SELECTCASE A", "CASE 1", "ENDIF").mapIndexed { i, t -> parse(t, i)!! }
        val errors = BlockChecker().check(stmts)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].message.contains("ENDSELECT"), errors[0].message)
    }

    @Test
    fun `多余结束语句报错`() {
        val errors = BlockChecker().check(listOf(parse("ENDIF", 1)!!))
        assertEquals(1, errors.size)
    }

    @Test
    fun `ELSE 缺少 IF 报错`() {
        val errors = BlockChecker().check(listOf(parse("ELSE", 1)!!))
        assertEquals(1, errors.size)
    }

    @Test
    fun `SELECTCASE 与第一个 CASE 之间的普通指令报错`() {
        // spec: ErbLoader.cs:1098-1102 —— 栈顶 IfCaseList 为空的 SELECTCASE 下，
        // CASE/CASEELSE/ENDSELECT 之外的指令行一律 OutsideSelectcase（isError）。
        val stmts = listOf("SELECTCASE A", "PRINTL BAD", "CASE 1", "ENDSELECT")
            .mapIndexed { i, t -> parse(t, i)!! }
        val errors = BlockChecker().check(stmts)
        assertEquals(1, errors.size, "只在第一个 CASE 之前报；$errors")
        assertTrue(errors[0].message.contains("SELECTCASE"), errors[0].message)
    }

    @Test
    fun `SELECTCASE 前置区允许 CASE CASEELSE ENDSELECT 与标签行`() {
        val stmts = listOf(
            parse("SELECTCASE A", 0)!!,
            parser().parseLogicalLine(LogicalLine(LineKind.GOTO_LABEL, "test.erb", 1, "\$SKIP"))!!,
            parse("CASE 1", 2)!!,
            parse("CASEELSE", 3)!!,
            parse("ENDSELECT", 4)!!,
        )
        assertTrue(BlockChecker().check(stmts).isEmpty())
    }
}
