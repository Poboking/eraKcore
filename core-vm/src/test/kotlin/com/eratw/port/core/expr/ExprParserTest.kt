package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 解析器结构测试：AST 形态、优先级、错误路径、终止符。 */
class ExprParserTest {

    @Test
    fun `AST形态-优先级与结合性`() {
        // 1+2*3 → Binary(PLUS, 1, Binary(MULT, 2, 3))
        val e1 = parseOrThrow(listOf(i(1), op("+"), i(2), op("*"), i(3))) as Binary
        assertEquals(OperatorCode.PLUS, e1.op)
        assertEquals(OperatorCode.MULT, (e1.right as Binary).op)
        // 1-2-3 → (1-2)-3 左结合
        val e2 = parseOrThrow(listOf(i(1), op("-"), i(2), op("-"), i(3))) as Binary
        assertEquals(OperatorCode.MINUS, (e2.left as Binary).op)
        // 1&2==3 → 1&(2==3)：位运算优先级低于比较。spec: OperatorCode.cs 优先级 80 < 96
        val e3 = parseOrThrow(listOf(i(1), op("&"), i(2), op("=="), i(3))) as Binary
        assertEquals(OperatorCode.BIT_AND, e3.op)
        assertEquals(OperatorCode.EQUAL, (e3.right as Binary).op)
        // 1<<2+3 → 1<<(2+3)：移位(112)低于加法(128)
        val e4 = parseOrThrow(listOf(i(1), op("<<"), i(2), op("+"), i(3))) as Binary
        assertEquals(OperatorCode.LEFT_SHIFT, e4.op)
        assertEquals(OperatorCode.PLUS, (e4.right as Binary).op)
    }

    @Test
    fun `括号优先`() {
        val e = parseOrThrow(listOf(lp(), i(1), op("+"), i(2), rp(), op("*"), i(3))) as Binary
        assertEquals(OperatorCode.MULT, e.op)
        assertEquals(OperatorCode.PLUS, (e.left as Binary).op)
    }

    @Test
    fun `一元前缀右结合`() {
        // -(-1) → Unary(MINUS, Unary(MINUS, 1))；注意无括号连用 `--1` 是语法错误
        // （原版 TermStack state 2/3 拒绝第二个前缀运算符，ExpressionParser.cs:31-34）
        val e = parseOrThrow(listOf(op("-"), lp(), op("-"), i(1), rp())) as Unary
        assertEquals(OperatorCode.MINUS, e.op)
        assertEquals(OperatorCode.MINUS, (e.operand as Unary).op)
        // -(1+2)
        val e2 = parseOrThrow(listOf(op("-"), lp(), i(1), op("+"), i(2), rp())) as Unary
        assertEquals(OperatorCode.MINUS, e2.op)
    }

    @Test
    fun `无括号连用前缀运算符报错`() {
        // spec: ExpressionParser.cs:31-34
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(op("-"), op("-"), i(1))) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(op("!"), op("-"), i(1))) }
    }

    @Test
    fun `一元加消除`() {
        // +1 直接返回 IntLiteral，无 Unary 节点。spec: OperatorMethodManager.cs:763-766
        val e = parseOrThrow(listOf(op("+"), i(1)))
        assertTrue(e is IntLiteral)
    }

    @Test
    fun `等号默认不允许-可用Assignment终止`() {
        // 表达式中裸 '=' 报错。spec: ExpressionParser.cs:548-555
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("A"), op("="), i(1))) }
        // 语句层（赋值语句）以 Assignment 终止
        val parser = ExprParser(listOf(id("A"), op("="), i(1)), null, ParserConfig())
        val e = parser.reduceExpressionTerm(TermEndWith.EoL or TermEndWith.Assignment)
        assertTrue(e is VarRef)
    }

    @Test
    fun `三目缺少井号报错`() {
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), op("?"), i(2))) }
        // 有 ? 无 # 且无后续 → '?' 缺少 '#'
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), op("?"), i(2), op("+"), i(3))) }
        // 孤立 '#'
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), op("#"), i(2))) }
    }

    @Test
    fun `变量下标单遍解析`() {
        // A:1+2 → (A[1]) + 2：下标只吃一个 term。spec: ExpressionParser.cs:502,618
        val resolver = resolverWith(VarInfo("A", OperandType.INT, 1, isCharacterData = false))
        val e = parseOrThrow(listOf(id("A"), colon(), i(1), op("+"), i(2)), resolver) as Binary
        assertEquals(OperatorCode.PLUS, e.op)
        val idx = (e.left as VarRef).indices
        assertEquals(1, idx.size)
        assertEquals(1L, (idx[0] as IntLiteral).value)
        // DA:1:2 → 两个下标（2D 变量；1D 变量两个下标是错误）
        val resolver2D = resolverWith(VarInfo("DA", OperandType.INT, 2, isCharacterData = false))
        val e2 = parseOrThrow(listOf(id("DA"), colon(), i(1), colon(), i(2)), resolver2D) as VarRef
        assertEquals(2, e2.indices.size)
        // 1D 变量两个下标报错。spec: VariableParser.cs:156-159
        assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("A"), colon(), i(1), colon(), i(2)), resolver)
        }
        // 下标内不允许运算符。spec: ExpressionParser.cs:543-546
        assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("A"), colon(), i(1), op("+"), i(2), colon(), i(9)), resolver)
        }
        // 下标内括号表达式 OK（单遍内括号子解析）
        val e3 = parseOrThrow(listOf(id("A"), colon(), lp(), i(1), op("+"), i(2), rp()), resolver) as VarRef
        assertEquals(1, e3.indices.size)
        // ':' 后缺少参数。spec: ExpressionParser.cs:342-345
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("A"), colon()), resolver) }
    }

    @Test
    fun `连续比较运算符产生告警`() {
        // spec: ExpressionParser.cs:556-559
        val (e, parser) = parse(listOf(i(1), op("<"), i(2), op("<"), i(3)))
        assertEquals(1L, evalInt(e!!))
        assertEquals(1, parser.warnings.size)
    }

    @Test
    fun `空表达式返回null`() {
        assertEquals(null, parse(listOf()).first)
    }

    @Test
    fun `未闭合括号报错`() {
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(lp(), i(1), op("+"), i(2))) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(lp(), rp())) }  // 空括号
    }

    @Test
    fun `多余闭合符号报错`() {
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), rp())) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), comma())) }
    }

    @Test
    fun `TO与IS关键字`() {
        // IS 在表达式中永远非法。spec: ExpressionParser.cs:530-533
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("IS"))) }
        // TO 仅在 SELECTCASE 语境（allowKeywordTo）合法
        val parser = ExprParser(listOf(id("TO")), null, ParserConfig())
        assertFailsWith<ExprParseException> { parser.reduceExpressionTerm() }
    }

    @Test
    fun `参数列表允许空位`() {
        // FUNC(,1) → [null, 1]。spec: ExpressionParser.cs:261-264（ReduceExpressionTerm 可返回 null）
        // reduceArguments 从 '(' 之后开始消费
        val parser = ExprParser(
            listOf(comma(), i(1), rp()),
            resolverWith().also { it.funcs["FUNC"] = OperandType.INT },
            ParserConfig(),
        )
        val args = parser.reduceArguments(ArgsEndWith.RightParenthesis)
        assertEquals(2, args.size)
        assertEquals(null, args[0])
        assertTrue(args[1] is IntLiteral)
    }

    @Test
    fun `未闭合括号的参数列表报错`() {
        val parser = ExprParser(listOf(i(1), comma(), i(2)), null, ParserConfig())
        assertFailsWith<ExprParseException> { parser.reduceArguments(ArgsEndWith.RightParenthesis) }
    }

    @Test
    fun `未知标识符报错`() {
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("FOO")), FakeResolver()) }
    }

    @Test
    fun `点号成员访问未实现`() {
        // spec: ExpressionParser.cs:363-367
        assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("A"), ExprToken(ExprTokenKind.DOT, "."), id("B")), resolverWith(var0D("A", OperandType.INT)))
        }
    }

    @Test
    fun `中括号函数调用未实现`() {
        // spec: ExpressionParser.cs:370-374
        assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("A"), ExprToken(ExprTokenKind.LBRACKET, "["), i(1), ExprToken(ExprTokenKind.RBRACKET, "]")))
        }
    }

    @Test
    fun `str乘int混合类型解析`() {
        // int*str 与 str*int 都在解析期定型为 STR。spec: OperatorMethodManager.cs:830-833
        val e1 = parseOrThrow(listOf(i(3), op("*"), s("ab"))) as Binary
        assertEquals(OperandType.STR, e1.operandType)
        val e2 = parseOrThrow(listOf(s("ab"), op("*"), i(3))) as Binary
        assertEquals(OperandType.STR, e2.operandType)
        // int+str 非法
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(i(3), op("+"), s("ab"))) }
    }

    @Test
    fun `AT私钥语法`() {
        // A@key → subKey。spec: ExpressionParser.cs:347-358
        val resolver = resolverWith(var0D("A", OperandType.INT))
        val e = parseOrThrow(listOf(id("A"), at(), id("key")), resolver) as VarRef
        assertEquals("key", e.subKey)
        // @ 后缺少标识符
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("A"), at()), resolver) }
    }

    @Test
    fun `零参函数引用`() {
        val resolver = FakeResolver().apply { funcs["F"] = OperandType.INT }
        val e = parseOrThrow(listOf(id("F"), op("+"), i(1)), resolver) as Binary
        assertTrue(e.left is FuncCall)
    }
}
