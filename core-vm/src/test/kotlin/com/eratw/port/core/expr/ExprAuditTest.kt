package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * core.expr 表达式文法对拍（审计路，只钉现状，产品零改动）。
 *
 * 对拍对象：
 * - OperatorCode.cs:3-40（运算符编码位布局）
 * - OperatorManager.cs:7-154（文本表 / IsUnary 族 / GetPriority）
 * - ExpressionParser.cs:17-208（TermStack 状态机）与 :497-628（reduceTerm）
 * - OperatorMethodManager.cs:14-878（各运算符求值与 ReduceXxxTerm 类型规则）
 * - LexicalAnalyzer.cs:402-509（运算符词法，补足 OperatorManager 文本表缺口）
 * - lang.emuera-zhs.xml（错误/系统行的权威文案）
 *
 * 约定：分歧处在断言旁用 TODO(verify) 登记 C# 行号；断言写的是**当前实现**的行为，
 * 便于后续修正时这里先红。
 */
class ExprAuditTest {

    // ------------------------------------------------------------------
    // 1. 编码表
    // ------------------------------------------------------------------

    @Test
    fun `OperatorCode 数值与 C# 逐项一致`() {
        // 期望值直接取 OperatorCode.cs 的十进制字面量（:11-39）。
        val expected = mapOf(
            OperatorCode.PLUS to 196992,
            OperatorCode.MINUS to 197248,
            OperatorCode.MULT to 131984,
            OperatorCode.DIV to 132240,
            OperatorCode.MOD to 132496,
            OperatorCode.EQUAL to 132704,
            OperatorCode.GREATER to 132965,
            OperatorCode.LESS to 133221,
            OperatorCode.GREATER_EQUAL to 133477,
            OperatorCode.LESS_EQUAL to 133733,
            OperatorCode.NOT_EQUAL to 133984,
            OperatorCode.INCREMENT to 598016,
            OperatorCode.DECREMENT to 598272,
            OperatorCode.AND to 134208,
            OperatorCode.OR to 134464,
            OperatorCode.XOR to 136512,
            OperatorCode.NAND to 136768,
            OperatorCode.NOR to 137024,
            OperatorCode.BIT_AND to 134736,
            OperatorCode.BIT_OR to 134992,
            OperatorCode.BIT_XOR to 135248,
            OperatorCode.NOT to 69888,
            OperatorCode.BIT_NOT to 70144,
            OperatorCode.RIGHT_SHIFT to 136048,
            OperatorCode.LEFT_SHIFT to 136304,
            OperatorCode.TERNARY_A to 268293,
            OperatorCode.TERNARY_B to 268560,
            OperatorCode.ASSIGNMENT to 510,
            OperatorCode.ASSIGNMENT_STR to 766,
        )
        expected.forEach { (code, cs) ->
            assertEquals(cs, code.value, "枚举数值与 C# 不一致: $code")
        }
        // 枚举里除 NULL 外必须正好这 29 个，防止漏移植或多移植。
        assertEquals(29, expected.size)
        assertEquals(30, OperatorCode.entries.size)
        assertEquals(0, OperatorCode.NULL.value)
    }

    @Test
    fun `标记位分类与 C# IsUnary 族一致`() {
        // OperatorManager.cs:131-149 就是四次按位与。
        assertEquals(
            setOf(
                OperatorCode.PLUS, OperatorCode.MINUS, OperatorCode.NOT, OperatorCode.BIT_NOT,
                OperatorCode.INCREMENT, OperatorCode.DECREMENT,
            ),
            OperatorCode.entries.filter { it.isUnary }.toSet(),
        )
        assertEquals(
            setOf(OperatorCode.INCREMENT, OperatorCode.DECREMENT),
            OperatorCode.entries.filter { it.isUnaryAfter }.toSet(),
        )
        assertEquals(
            setOf(OperatorCode.TERNARY_A, OperatorCode.TERNARY_B),
            OperatorCode.entries.filter { it.isTernary }.toSet(),
        )
        assertEquals(21, OperatorCode.entries.count { it.isBinary })
        // 位布局里 Plus/Minus 同时带 __UNARY__(65536) 与 __BINARY__(131072)，
        // 故 `+`/`-` 两义；C# 同（196992 = 0x30180、197248 = 0x30280）。
        assertTrue(OperatorCode.PLUS.isUnary && OperatorCode.PLUS.isBinary)
        assertTrue(OperatorCode.MINUS.isUnary && OperatorCode.MINUS.isBinary)
        assertTrue(!OperatorCode.MULT.isUnary)
    }

    @Test
    fun `优先级表-移位112低于加减128`() {
        // 期望值 = C# 数值 & __PRIORITY_MASK__(255)（OperatorCode.cs:6, OperatorManager.cs:151-154）。
        val expected = mapOf(
            OperatorCode.PLUS to 128,
            OperatorCode.MINUS to 128,
            OperatorCode.MULT to 144,
            OperatorCode.DIV to 144,
            OperatorCode.MOD to 144,
            OperatorCode.EQUAL to 96,
            OperatorCode.GREATER to 101,
            OperatorCode.LESS to 101,
            OperatorCode.GREATER_EQUAL to 101,
            OperatorCode.LESS_EQUAL to 101,
            OperatorCode.NOT_EQUAL to 96,
            OperatorCode.AND to 64,
            OperatorCode.OR to 64,
            OperatorCode.XOR to 64,
            OperatorCode.NAND to 64,
            OperatorCode.NOR to 64,
            OperatorCode.BIT_AND to 80,
            OperatorCode.BIT_OR to 80,
            OperatorCode.BIT_XOR to 80,
            OperatorCode.NOT to 0,
            OperatorCode.BIT_NOT to 0,
            OperatorCode.RIGHT_SHIFT to 112,
            OperatorCode.LEFT_SHIFT to 112,
            OperatorCode.TERNARY_A to 5,
            OperatorCode.TERNARY_B to 16,
            OperatorCode.INCREMENT to 0,
            OperatorCode.DECREMENT to 0,
            OperatorCode.ASSIGNMENT to 254,
            OperatorCode.ASSIGNMENT_STR to 254,
        )
        expected.forEach { (code, p) -> assertEquals(p, code.priority, "优先级与 C# 不一致: $code") }
        // 原版反直觉处：移位(112) **低于** 加减乘除(128/144)，高于比较(96-101)。
        assertTrue(OperatorCode.LEFT_SHIFT.priority < OperatorCode.PLUS.priority)
        assertTrue(OperatorCode.LEFT_SHIFT.priority > OperatorCode.LESS.priority)
        // 位运算(80)夹在比较(96-101)与逻辑(64)之间。
        assertTrue(OperatorCode.BIT_AND.priority < OperatorCode.EQUAL.priority)
        assertTrue(OperatorCode.BIT_AND.priority > OperatorCode.AND.priority)
    }

    @Test
    fun `优先级-算术高于移位高于比较高于位运算高于逻辑`() {
        // 1<<2+3*4 → 1<<(2+12) = 1<<14。spec: ExpressionParser.cs:86-90 的 lastPriority 归约。
        val e1 = parseOrThrow(listOf(i(1), op("<<"), i(2), op("+"), i(3), op("*"), i(4))) as Binary
        assertEquals(OperatorCode.LEFT_SHIFT, e1.op)
        assertEquals(OperatorCode.PLUS, (e1.right as Binary).op)
        assertEquals(OperatorCode.MULT, ((e1.right as Binary).right as Binary).op)
        assertEquals(1L shl 14, evalInt(e1))

        // 1<<2<3 → (1<<2)<3：移位比比较紧。
        val e2 = parseOrThrow(listOf(i(1), op("<<"), i(2), op("<"), i(3))) as Binary
        assertEquals(OperatorCode.LESS, e2.op)
        assertEquals(OperatorCode.LEFT_SHIFT, (e2.left as Binary).op)
        assertEquals(0L, evalInt(e2))

        // 1&2==3 → 1&(2==3)：比较比按位与紧。
        val e3 = parseOrThrow(listOf(i(1), op("&"), i(2), op("=="), i(3))) as Binary
        assertEquals(OperatorCode.BIT_AND, e3.op)
        assertEquals(OperatorCode.EQUAL, (e3.right as Binary).op)

        // 0||1&2 → 0||(1&2)：按位与比逻辑或紧。
        val e4 = parseOrThrow(listOf(i(0), op("||"), i(1), op("&"), i(2))) as Binary
        assertEquals(OperatorCode.OR, e4.op)
        assertEquals(OperatorCode.BIT_AND, (e4.right as Binary).op)
        assertEquals(0L, evalInt(e4))
    }

    @Test
    fun `同优先级左结合`() {
        // C# while (lastPriority() >= priority) reduceLastThree()：相等即归约 → 左结合。
        // spec: ExpressionParser.cs:87-90
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(8), op("/"), i(4), op("/"), i(2)))))
        // 1|2^3 → (1|2)^3 = 0（若右结合则是 1|(2^3)=3）。
        val e = parseOrThrow(listOf(i(1), op("|"), i(2), op("^"), i(3))) as Binary
        assertEquals(OperatorCode.BIT_XOR, e.op)
        assertEquals(OperatorCode.BIT_OR, (e.left as Binary).op)
        assertEquals(0L, evalInt(e))
        // && 与 ^^ 同为 64：1&&2^^0 → (1&&2)^^0 = 1。
        val e2 = parseOrThrow(listOf(i(1), op("&&"), i(2), op("^^"), i(0))) as Binary
        assertEquals(OperatorCode.XOR, e2.op)
        assertEquals(OperatorCode.AND, (e2.left as Binary).op)
        assertEquals(1L, evalInt(e2))
    }

    // ------------------------------------------------------------------
    // 2. 三目
    // ------------------------------------------------------------------

    @Test
    fun `三目优先级-问号5井号16夹在最低档`() {
        // 1+2?3#4+5 → (1+2)?3#(4+5)：? 的 5 低于一切二元运算，故两侧各成整式。
        val e1 = parseOrThrow(listOf(i(1), op("+"), i(2), op("?"), i(3), op("#"), i(4), op("+"), i(5))) as Ternary
        assertEquals(OperatorCode.PLUS, (e1.cond as Binary).op)
        assertEquals(3L, (e1.ifTrue as IntLiteral).value)
        assertEquals(OperatorCode.PLUS, (e1.ifFalse as Binary).op)
        assertEquals(3L, evalInt(e1))

        // 1?2&&3#4 → 1?(2&&3)#4：# 的 16 < && 的 64，故真值支可含完整的逻辑式。
        val e2 = parseOrThrow(listOf(i(1), op("?"), i(2), op("&&"), i(3), op("#"), i(4))) as Ternary
        assertEquals(OperatorCode.AND, (e2.ifTrue as Binary).op)
        assertEquals(1L, evalInt(e2))

        // 1?2#3&&0 → 1?2#(3&&0)：假值支同样右结合到 # 之后。
        val e3 = parseOrThrow(listOf(i(1), op("?"), i(2), op("#"), i(3), op("&&"), i(0))) as Ternary
        assertEquals(OperatorCode.AND, (e3.ifFalse as Binary).op)
        assertEquals(2L, evalInt(e3))
    }

    @Test
    fun `三目类型规则-条件必须整数且分支同型`() {
        // spec: OperatorMethodManager.cs:850-877 —— 只认 int/int/int 与 int/str/str。
        val ok = parseOrThrow(listOf(i(1), op("?"), s("y"), op("#"), s("n"))) as Ternary
        assertEquals(OperandType.STR, ok.operandType)
        assertEquals("y", evalStr(ok))

        // 条件为字符串：C# 走 ReduceTernaryTerm 的 else 抛 InvalidTernaryOp。
        assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(s("a"), op("?"), i(1), op("#"), i(2)))
        }
        // 分支异型。
        assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(i(1), op("?"), i(2), op("#"), s("a")))
        }
        assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(i(1), op("?"), s("a"), op("#"), i(2)))
        }
    }

    // ------------------------------------------------------------------
    // 3. 字符串 `+` / `*` 与类型规则
    // ------------------------------------------------------------------

    @Test
    fun `字符串加号走 str 重载-数值加号走 int 重载`() {
        // spec: OperatorMethodManager.cs:14-45（PlusIntInt / PlusStrStr 各是独立 OperatorMethod）
        val strPlus = parseOrThrow(listOf(s("a"), op("+"), s("b"))) as Binary
        assertEquals(OperandType.STR, strPlus.operandType)
        assertEquals("ab", evalStr(strPlus))

        val intPlus = parseOrThrow(listOf(i(1), op("+"), i(2))) as Binary
        assertEquals(OperandType.INT, intPlus.operandType)
        assertEquals(3L, evalInt(intPlus))

        // 字符串结果可以继续参与 * 与字符串比较（类型在解析期已定）。
        val repeated = parseOrThrow(listOf(lp(), s("a"), op("+"), s("b"), rp(), op("*"), i(2))) as Binary
        assertEquals(OperandType.STR, repeated.operandType)
        assertEquals("abab", evalStr(repeated))
        assertEquals(
            1L,
            evalInt(parseOrThrow(listOf(i(2), op("*"), s("a"), op("=="), s("aa")))),
        )
    }

    @Test
    fun `混合类型只在乘号处跨界-其余一律报错`() {
        // spec: OperatorMethodManager.cs:816-833 —— int/int、str/str、以及 **仅 Mult** 的 int/str。
        val a = parseOrThrow(listOf(i(3), op("*"), s("a"))) as Binary
        assertEquals(OperandType.STR, a.operandType)
        val b = parseOrThrow(listOf(s("a"), op("*"), i(3))) as Binary
        assertEquals(OperandType.STR, b.operandType)

        // 加/减/除/余/比较 的混型都没有 OperatorMethod → ReduceBinaryTerm 抛 CodeEE。
        listOf("+", "-", "/", "%", "==", "!=", ">", "<", ">=", "<=").forEach { o ->
            assertFailsWith<ExprEvalException>("int $o str 应报错") {
                parseOrThrow(listOf(i(1), op(o), s("a")))
            }
            assertFailsWith<ExprEvalException>("str $o int 应报错") {
                parseOrThrow(listOf(s("a"), op(o), i(1)))
            }
        }
        // str*str 也不在表里（binaryStrStrDic 只登记 Plus/比较，:742-748）。
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(s("a"), op("*"), s("b"))) }
        // 逻辑/按位/移位对字符串同样无方法。
        listOf("&&", "||", "^^", "!&", "!|", "&", "|", "^", "<<", ">>").forEach { o ->
            assertFailsWith<ExprEvalException>("str $o str 应报错") {
                parseOrThrow(listOf(s("a"), op(o), s("b")))
            }
        }
    }

    @Test
    fun `字符串重复的边界-负数与一万都先于空串判断`() {
        // spec: OperatorMethodManager.cs:97-108 —— 先查 intValue<0、再查 >=10000、最后才 empty/0。
        // 负数分支先抛，故空串*-1 也是错误而不是 ""。
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(s(""), op("*"), i(-1)))) }
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(-1)))) }
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(s("a"), op("*"), i(10000)))) }
        assertEquals("", evalStr(parseOrThrow(listOf(s(""), op("*"), i(0)))))
        assertEquals("", evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(0)))))
        assertEquals("a", evalStr(parseOrThrow(listOf(s("a"), op("*"), i(1)))))
        // int*str 与 str*int 同表（binaryMultIntStr），取整数的顺序按操作数类型分派。
        assertEquals("aaa", evalStr(parseOrThrow(listOf(i(3), op("*"), s("a")))))
    }

    // ------------------------------------------------------------------
    // 4. 逻辑 vs 按位
    // ------------------------------------------------------------------

    @Test
    fun `短路差异-与或与非或非短路其余全求值`() {
        // spec: OperatorMethodManager.cs:383-409（And/Or 用 &&/|| 短路）
        //       :439-446（NandIntInt `arg0 == 0 || arg1 == 0`（:441）—— 左为 0 即返回，右侧不求值）
        //       :457-464（NorIntInt `arg0 == 0 && arg1 == 0`（:459）—— 左非 0 即返回，右侧不求值）
        //       :411-429（XorIntInt 先把两个操作数取进局部变量，不短路）
        //       :475-505（按位全求值）
        fun ctxWithF(): Pair<TestContext, List<ExprToken>> {
            val ctx = TestContext()
            ctx.funcs["F"] = { Value.IntV(1L) }
            return ctx to listOf(id("F"), lp(), rp())
        }

        val (c1, f1) = ctxWithF()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("&&")) + f1), c1))
        assertEquals(0, c1.funcCalls.size, "&& 左为 0 时右侧不求值")

        val (c2, f2) = ctxWithF()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("||")) + f2), c2))
        assertEquals(0, c2.funcCalls.size, "|| 左非 0 时右侧不求值")

        // !&：左为 0 即得 1，右侧不求值；左非 0 时必须求右侧。
        val (c3, f3) = ctxWithF()
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("!&")) + f3), c3))
        assertEquals(0, c3.funcCalls.size, "!& 左为 0 时右侧不求值")
        val (c4, f4) = ctxWithF()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!&")) + f4), c4))
        assertEquals(1, c4.funcCalls.size, "!& 左非 0 时右侧应被求值")

        // !|：左非 0 即得 0，右侧不求值；左为 0 时必须求右侧。
        val (c5, f5) = ctxWithF()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!|")) + f5), c5))
        assertEquals(0, c5.funcCalls.size, "!| 左非 0 时右侧不求值")
        val (c6, f6) = ctxWithF()
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("!|")) + f6), c6))
        assertEquals(1, c6.funcCalls.size, "!| 左为 0 时右侧应被求值")

        // ^^ 与按位运算都会求右侧（左操作数固定 1，右侧 F() 返回 1）。
        listOf("^^" to 0L, "&" to 1L, "|" to 1L, "^" to 0L).forEach { (o, want) ->
            val (c, f) = ctxWithF()
            assertEquals(want, evalInt(parseOrThrow(listOf(i(1), op(o)) + f), c), "$o 结果")
            assertEquals(1, c.funcCalls.size, "$o 右侧应被求值")
        }

        // 逻辑运算结果归一化为 0/1（不是操作数原值），按位运算保留位。
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(2), op("&&"), i(3)))))
        assertEquals(2L, evalInt(parseOrThrow(listOf(i(2), op("&"), i(3)))))
        assertEquals(7L, evalInt(parseOrThrow(listOf(i(6), op("|"), i(3)))))
    }

    // ------------------------------------------------------------------
    // 5. 除零与求值顺序
    // ------------------------------------------------------------------

    @Test
    fun `除余先取右操作数-左操作数不求值`() {
        // spec: OperatorMethodManager.cs:121-138 / :140-157 —— 先算 arguments[1] 判 0 再算 arguments[0]。
        listOf("/", "%").forEach { o ->
            val ctx = TestContext()
            ctx.funcs["F"] = { Value.IntV(6L) }
            val tokens = listOf(id("F"), lp(), rp(), op(o), i(0))
            assertFailsWith<ExprEvalException>(o) { evalInt(parseOrThrow(tokens), ctx) }
            assertEquals(0, ctx.funcCalls.size, "$o 除数为 0 时左操作数不应被求值")
        }
    }

    // ------------------------------------------------------------------
    // 6. 字符串比较的官方 bug
    // ------------------------------------------------------------------

    @Test
    fun `字符串比较-GreaterEqual与LessEqual都是小于`() {
        // spec: OperatorMethodManager.cs:331（GreaterEqualStrStr 实为 Compare<0）
        //       OperatorMethodManager.cs:367（LessEqualStrStr 实为 Compare<0，相等泄漏）
        // c<0
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op(">="), s("b")))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op("<="), s("b")))))
        // c==0：两个都返回 0（>= 与 <= 同时为假）
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("a"), op(">="), s("a")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("a"), op("<="), s("a")))))
        // c>0：两个都返回 0（>= 应为 1）
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("b"), op(">="), s("a")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("b"), op("<="), s("a")))))
        // 其余四个比较按 Ordinal 正常：spec: OperatorMethodManager.cs:177-301
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("b"), op(">"), s("a")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("a"), op(">"), s("a")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("a"), op(">"), s("b")))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op("<"), s("b")))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op("=="), s("a")))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op("!="), s("b")))))
    }

    // ------------------------------------------------------------------
    // 7. 一元
    // ------------------------------------------------------------------

    @Test
    fun `一元-加号在解析期消除而其余装饰成节点`() {
        // spec: OperatorMethodManager.cs:761-766 —— long 上的 Plus 直接 return o1。
        val plusVar = parseOrThrow(listOf(op("+"), id("A")), resolverWith(var0D("A", OperandType.INT)))
        assertTrue(plusVar is VarRef, "一元 + 应返回操作数本身，不建节点")
        assertEquals("A", (plusVar as VarRef).name)

        val minus = parseOrThrow(listOf(op("-"), i(5))) as Unary
        assertEquals(OperatorCode.MINUS, minus.op)
        assertEquals(-5L, evalInt(minus))
        val not = parseOrThrow(listOf(op("!"), i(0))) as Unary
        assertEquals(1L, evalInt(not))
        val bitNot = parseOrThrow(listOf(op("~"), i(5))) as Unary
        assertEquals(-6L, evalInt(bitNot))
        // ~(-5) = 4：括号里的前缀一元是独立子表达式，不受 state 2/3 连用限制。
        assertEquals(4L, evalInt(parseOrThrow(listOf(op("~"), lp(), op("-"), i(5), rp()))))

        // 字符串操作数一律拒绝：unaryDic 只登记 long 版，落到 else 抛 CodeEE。
        // spec: OperatorMethodManager.cs:761-784
        listOf("!", "~", "-").forEach { o ->
            assertFailsWith<ExprEvalException>("$o 作用于字符串应报错") {
                parseOrThrow(listOf(op(o), s("a")))
            }
        }
    }

    // ------------------------------------------------------------------
    // 8. 错误形态
    // ------------------------------------------------------------------

    @Test
    fun `错误形态-两族异常与消息现状`() {
        // C# 只有 CodeEE / ExeEE 两类（ExpressionParser.cs 各处 throw），Kotlin 分成
        // ExprParseException（带行/列，Parser.kt:6-10）与 ExprEvalException（无位置，Eval.kt:4）。
        // 注意：ReduceXxxTerm 的类型错误发生在**解析期**，C# 是 CodeEE（用户脚本错误），
        // Kotlin 抛的是 ExprEvalException —— 这一分层差异按现状钉桩。
        // TODO(verify): 以下消息都是硬编码中文，未接 lang 表；C# 走 Lang.Error 文本，
        //   权威文案见 lang.emuera-zhs.xml 的 Error 段，逐条比对如下：
        //   除数为 0 ↔ Error.DivideByZero「除数为0的除法运算」（zhs.xml:286）
        //   数值型 + 字符串型：不合法的组合 ↔ Error.CanNotAppliedBinaryOp（zhs.xml:282，{0}整数型/{1}字符串型）
        //   ++/-- 只能作用于变量 ↔ Error.IncrementNonVar（zhs.xml:273）
        //   一元运算符 ! 不能作用于字符串型 ↔ Error.CanNotAppliedUnaryOp（zhs.xml:281）
        val div = assertFailsWith<ExprEvalException> { evalInt(parseOrThrow(listOf(i(1), op("/"), i(0)))) }
        assertEquals("除数为 0", div.message)

        val mix = assertFailsWith<ExprEvalException> { parseOrThrow(listOf(i(1), op("+"), s("a"))) }
        assertEquals("数值型 + 字符串型：不合法的组合", mix.message)

        val inc = assertFailsWith<ExprEvalException> { parseOrThrow(listOf(op("++"), i(5))) }
        assertEquals("++/-- 只能作用于变量", inc.message)

        val constInc = assertFailsWith<ExprEvalException> {
            parseOrThrow(
                listOf(op("++"), id("C")),
                resolverWith(VarInfo("C", OperandType.INT, 0, isCharacterData = false, isConst = true)),
            )
        }
        assertEquals("不能对常量 C 使用 ++/--", constInc.message)

        val unaryStr = assertFailsWith<ExprEvalException> { parseOrThrow(listOf(op("!"), s("a"))) }
        assertEquals("一元运算符 ! 不能作用于字符串型", unaryStr.message)

        // TODO(verify): 系统行文案同样是硬编码；C# 用 SystemLine.MinusWontWork
        //   （zhs.xml:1040「对整型最小值({0})进行-操作其值也不对发生变化」，
        //   OperatorMethodManager.cs:561-564），与这里的「警告：对 -9223372036854775808 取负不生效」不一致。
        val ctx = TestContext()
        assertEquals(Long.MIN_VALUE, evalInt(parseOrThrow(listOf(op("-"), lp(), i(0), op("-"), i(Long.MIN_VALUE), rp())), ctx))
        assertEquals(listOf("警告：对 -9223372036854775808 取负不生效"), ctx.systemLines)
    }

    @Test
    fun `错误形态-解析期异常带行列号`() {
        // C# CodeEE 本身不带位置，行号由 ParserMediator 在调用侧补
        // （ExpressionParser.cs:558 的 Warn 传 GetScaningLine）；Kotlin 把位置焊在异常里。
        val bad = ExprToken(ExprTokenKind.OPERATOR, "=", 0, 7, 3)
        val ex = assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), bad, i(2))) }
        assertEquals(7, ex.line)
        assertEquals(3, ex.col)
        assertEquals("解析错误(行:7,列:3): 表达式中不允许 '='", ex.message)

        // 未闭合括号 / 多余符号 / 缺 # 都是解析族。
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(lp(), i(1))) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), rp())) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(i(1), op("?"), i(2))) }
        // 连用前缀一元：state 2/3 拒绝第二个运算符。spec: ExpressionParser.cs:31-34
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(op("-"), op("-"), i(1))) }
        // 前置与后置 ++ 连用：Error.DuplicateIncrementDecrement（ExpressionParser.cs:61-65）。
        val resolverA = resolverWith(var0D("A", OperandType.INT))
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(op("++"), id("A"), op("++")), resolverA) }
    }

    // ------------------------------------------------------------------
    // 9. 运算符文本表
    // ------------------------------------------------------------------

    @Test
    fun `运算符文本表-斜杠百分号等号是词法器直构的补充项`() {
        // C# OperatorManager.cs:7-113 的文本表**不含** "/"、"%"，也不含 "=="；
        // 它们由词法器直接构造 OperatorCode（LexicalAnalyzer.cs:425-433、:547-563），
        // 因此 OperatorManager.ToOperatorString 对这三个返回 ""（:115-129）。
        assertEquals(OperatorCode.DIV, OperatorCode.fromText("/"))
        assertEquals(OperatorCode.MOD, OperatorCode.fromText("%"))
        assertEquals(OperatorCode.EQUAL, OperatorCode.fromText("=="))
        // TODO(verify): 上述三项是 Kotlin 侧为对接词法器文本自加的（Operator.kt:68-78 已登记），
        //   副作用是 toOperatorString 对它们不再返回 ""：
        assertEquals("/", OperatorCode.toOperatorString(OperatorCode.DIV))
        assertEquals("%", OperatorCode.toOperatorString(OperatorCode.MOD))
        assertEquals("==", OperatorCode.toOperatorString(OperatorCode.EQUAL))
        // 其余文本与 C# 表同源；NULL 无文本 → ""（OperatorManager.cs:117-120）。
        assertEquals("", OperatorCode.toOperatorString(OperatorCode.NULL))
        assertEquals("+", OperatorCode.toOperatorString(OperatorCode.PLUS))
        assertEquals("!&", OperatorCode.toOperatorString(OperatorCode.NAND))
        assertEquals("#", OperatorCode.toOperatorString(OperatorCode.TERNARY_B))
        assertEquals("'=", OperatorCode.toOperatorString(OperatorCode.ASSIGNMENT_STR))
        // 词法器的另一条路径：C# 枚举名 → 编码（adapter 用）。必须覆盖全部 29 项且无碰撞。
        // C# 枚举名 = 首字母大写驼峰，唯 Ternary_a / Ternary_b 带小写下划线后缀。
        fun toCSharpName(code: OperatorCode): String = when (code) {
            OperatorCode.TERNARY_A -> "Ternary_a"
            OperatorCode.TERNARY_B -> "Ternary_b"
            else -> code.name.split("_").joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        val named = OperatorCode.entries.filter { it != OperatorCode.NULL }
        assertEquals(29, named.size)
        // 双射：名字集合 29 个互不相同，且每个都能反查回自身。
        assertEquals(29, named.map(::toCSharpName).toSet().size)
        named.forEach { code ->
            assertEquals(code, OperatorCode.fromCSharpName(toCSharpName(code)), "名字映射不一致: ${toCSharpName(code)}")
        }
        assertEquals(OperatorCode.NOT_EQUAL, OperatorCode.fromCSharpName("NotEqual"))
        assertEquals(OperatorCode.ASSIGNMENT, OperatorCode.fromCSharpName("Assignment"))
        assertEquals(null, OperatorCode.fromCSharpName("Nope"))
    }

    // ------------------------------------------------------------------
    // 10. 解析期严格化（w7 审计登记的两条静默回退已修）
    // ------------------------------------------------------------------

    @Test
    fun `未定义函数解析期报错-下标关键字按常量表判定`() {
        // 分歧一（已修）：C# 在解析期绑定函数，GetFunctionMethod 返回 null 时
        //   非 AnalysisMode 下 ThrowException 报「未定义标识符」（ExpressionParser.cs:376-392）；
        //   移植侧 functionCall 原先只取静态类型、未知函数按 INT 放行（Parser.kt 旧 :337-343），
        //   现在同口径抛 ExprParseException（Parser.functionCall）。
        val resolver = FakeResolver()
        val e = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("NOSUCH"), lp(), i(1), rp()), resolver)
        }
        assertTrue(e.message!!.contains("未定义的标识符 'NOSUCH'"), e.message)

        // 已知函数仍按 resolver 的返回类型构造 FuncCall（绑定结果不变）。
        resolver.funcs["KNOWN"] = OperandType.STR
        val call = parseOrThrow(listOf(id("KNOWN"), lp(), i(1), rp()), resolver) as FuncCall
        assertEquals("KNOWN", call.name)
        assertEquals(OperandType.STR, call.operandType)
        assertEquals(1, call.args.size)

        // 函数位出现变量名 → C# nameDic 分支 UseVarLikeFunc（IdentifierDictionary.cs:600-605）。
        resolver.vars["AVAR"] = VarInfo("AVAR", OperandType.INT, 1, isCharacterData = false)
        val e2 = assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("AVAR"), lp(), rp()), resolver) }
        assertTrue(e2.message!!.contains("用函数的方式使用变量名"), e2.message)

        // 裸标识符位仍会报错（走的是变量表 + 零参函数两条都没命中，ExpressionParser.cs:404-444）。
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("NOSUCH")), resolver) }

        // 分歧二（已修到「系统变量」边界）：C# 先 ConstantData.isDefined(varCode, idStr)，
        //   再查 isUserDefined，最后 ThrowException（ExpressionParser.cs:409-443）；
        //   移植侧原先无条件把未知标识符当字符串常量。现在由
        //   IdentifierResolver.resolveIndexKeyword 给出三态，默认实现覆盖 SystemLayout
        //   注册的系统变量：A 是系统 1D 变量且无关键字表 ⇒ 解析期抛。
        val aResolver = resolverWith(VarInfo("A", OperandType.INT, 1, isCharacterData = false))
        val e3 = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("A"), colon(), id("NOPE")), aResolver)
        }
        assertTrue(e3.message!!.contains("未定义的标识符 'NOPE'"), e3.message)

        // 常量表命中位仍回落 SingleStrTerm → KeywordIndexArg（TALENT 在 SystemLayout.keywords 里）。
        val tResolver = resolverWith(VarInfo("TALENT", OperandType.INT, 1, isCharacterData = true))
        val talentRef = parseOrThrow(listOf(id("TALENT"), colon(), id("氷精")), tResolver) as VarRef
        // 角色 1D 变量省略了角色位 → 完整下标 = [TARGET, KeywordIndexArg]。
        assertEquals(2, talentRef.indices.size)
        val talentArg = assertNotNull(
            talentRef.indices[1] as? KeywordIndexArg,
            "字符串下标应被包装为 KeywordIndexArg",
        )
        assertEquals("TALENT", talentArg.varName)
        assertEquals(1, talentArg.dim)
        assertEquals("氷精", (talentArg.expr as StrLiteral).value)

        // TODO(verify) 残留边界：用户 #DIM 变量的键表来自 ERD / 与变量同名的 CSV
        //   （C# PrepareERDFileNames，ErhLoader.cs:342-388），解析期没有这两张表 ⇒
        //   默认实现返回 null（保守放行），`M_TFLAG:时间因子类型` 仍编译通过、运行期
        //   keywordToInteger 报错。接线点 = 生产 resolver 覆写 resolveIndexKeyword。
        val uResolver = resolverWith(VarInfo("M_TFLAG", OperandType.INT, 1, isCharacterData = false))
        val userRef = parseOrThrow(listOf(id("M_TFLAG"), colon(), id("时间因子类型")), uResolver) as VarRef
        val userArg = assertNotNull(userRef.indices.single() as? KeywordIndexArg)
        assertEquals("时间因子类型", (userArg.expr as StrLiteral).value)
    }

    // ------------------------------------------------------------------
    // 11. 一元求值次数与 @ 私钥（审计修正钉桩）
    // ------------------------------------------------------------------

    @Test
    fun `一元负号双求值-副作用随双倍`() {
        // spec: OperatorMethodManager.cs:559-566 —— MinusInt.GetIntValue 里
        // arguments[0].GetIntValue(exm) 调了两次（判 MIN_VALUE 一次、取负又一次），
        // 操作数的副作用（RAND 抽号 / 函数调用）随之双倍，原样复刻。
        val ctx = TestContext()
        ctx.funcs["F"] = { Value.IntV(3L) }
        val e = parseOrThrow(listOf(op("-"), lp(), id("F"), lp(), rp(), rp()))
        assertEquals(-3L, evalInt(e, ctx))
        assertEquals(2, ctx.funcCalls.size, "负号应把操作数求值两次")

        // MIN_VALUE 路径：第一次求值触发系统行，第二次取负回绕；函数同样被调两次。
        val ctx2 = TestContext()
        ctx2.funcs["G"] = { Value.IntV(Long.MIN_VALUE) }
        val e2 = parseOrThrow(listOf(op("-"), lp(), id("G"), lp(), rp(), rp()))
        assertEquals(Long.MIN_VALUE, evalInt(e2, ctx2))
        assertEquals(2, ctx2.funcCalls.size)
        assertEquals(1, ctx2.systemLines.size)
    }

    @Test
    fun `前置自增自减下标只求值一次-不单独读旧值`() {
        // spec: OperatorMethodManager.cs:609-627 → VariableTerm.ChangeValue
        // （VariableTerm.cs:202-223）—— 下标表达式求值一遍后直接 Identifier.PlusValue，
        // 返回加完的新值；旧值不单独读（也就不会第二次求下标）。
        val resolver = resolverWith(VarInfo("A", OperandType.INT, 1, isCharacterData = false))
        resolver.funcs["F"] = OperandType.INT
        // 前置 ++/--。
        listOf("++" to 1L, "--" to -1L).forEach { (o, delta) ->
            val ctx = TestContext()
            ctx.funcs["F"] = { Value.IntV(1L) }
            val e = parseOrThrow(listOf(op(o), id("A"), colon(), id("F"), lp(), rp()), resolver)
            assertEquals(delta, evalInt(e, ctx), "$o 返回新值")
            assertEquals(1, ctx.funcCalls.size, "$o 的下标只求值一次")
            assertEquals(listOf("A:1+=($delta)"), ctx.variables.plusCalls, o)
        }
        // 后置 ++/--：UnaryAfter 同样只求一遍下标，返回旧值。
        // spec: OperatorMethodManager.cs:629-655
        listOf("++" to 1L, "--" to -1L).forEach { (o, delta) ->
            val ctx = TestContext()
            ctx.funcs["F"] = { Value.IntV(1L) }
            val e = parseOrThrow(listOf(id("A"), colon(), id("F"), lp(), rp(), op(o)), resolver)
            assertEquals(0L, evalInt(e, ctx), "后置 $o 返回旧值")
            assertEquals(1, ctx.funcCalls.size, "后置 $o 的下标只求值一次")
            assertEquals(listOf("A:1+=($delta)"), ctx.variables.plusCalls, o)
        }
    }

    @Test
    fun `名字@私钥查不到变量时解析期报InvalidAt`() {
        // C# reduceIdentifier 先 ReduceVariableIdentifier → GetVariableToken；
        // subKey != null 且三张变量表都未命中时必然抛 InvalidAt，绝不返回 null
        // （IdentifierDictionary.cs:490-493，ExpressionParser.cs:347-358 与 :395-404）。
        // 故 `函数名@私钥` 与 `未定义名@私钥` 都到不了后续函数/关键字路径，
        // 文案 = lang.emuera-zhs.xml:213「@符号的非法用法」。
        val funcResolver = FakeResolver().apply { funcs["F"] = OperandType.INT }
        val ex1 = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("F"), at(), id("key")), funcResolver)
        }
        assertTrue(ex1.message!!.contains("@符号的非法用法"), ex1.message)

        val ex2 = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("NOSUCH"), at(), id("key")), FakeResolver())
        }
        assertTrue(ex2.message!!.contains("@符号的非法用法"), ex2.message)

        // 报错位置钉在 @ 记号上（C# CodeEE 本身无位置，行列是 Kotlin 的补充约定）。
        val atTok = ExprToken(ExprTokenKind.AT, "@", 0L, 9, 4)
        val ex3 = assertFailsWith<ExprParseException> {
            parseOrThrow(listOf(id("NOSUCH"), atTok, id("key")), FakeResolver())
        }
        assertEquals(9, ex3.line)
        assertEquals(4, ex3.col)

        // 变量命中时 `变量@私钥` 仍合法（C# localvarTokenDic 分支只告警不拒，
        // IdentifierDictionary.cs:453-476；本层 VarInfo 无局部/全局之分，维持「命中即接受」）。
        val varResolver = resolverWith(var0D("A", OperandType.INT))
        val ref = parseOrThrow(listOf(id("A"), at(), id("key")), varResolver) as VarRef
        assertEquals("A", ref.name)
        assertEquals("key", ref.subKey)
    }
}
