package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 求值语义测试——每类运算至少 1 例；负数除法/取余、`+` 重载、三目结合性、
 * 比较链、省略索引变量引用各 1 例。
 */
class ExprEvalTest {

    // ---------- 字面量与算术 ----------

    @Test
    fun `整数与字符串字面量`() {
        assertEquals(42L, evalInt(parseOrThrow(listOf(i(42)))))
        assertEquals("abc", evalStr(parseOrThrow(listOf(s("abc")))))
    }

    @Test
    fun `四则与优先级`() {
        // 2+3*4 → 14（* 优先级 144 > + 128）
        assertEquals(14L, evalInt(parseOrThrow(listOf(i(2), op("+"), i(3), op("*"), i(4)))))
        assertEquals(-5L, evalInt(parseOrThrow(listOf(i(2), op("-"), i(3), op("-"), i(4)))))  // (2-3)-4
        assertEquals(100L, evalInt(parseOrThrow(listOf(i(10), op("*"), i(10)))))
    }

    @Test
    fun `负数除法向零取整`() {
        // C# long 整除向零取整。spec: OperatorMethodManager.cs:121-138
        assertEquals(-3L, evalInt(parseOrThrow(listOf(i(-7), op("/"), i(2)))))
        assertEquals(3L, evalInt(parseOrThrow(listOf(i(7), op("/"), i(2)))))
        assertEquals(-3L, evalInt(parseOrThrow(listOf(i(7), op("/"), i(-2)))))
    }

    @Test
    fun `负数取余符号随被除数`() {
        // C# % 符号随被除数。spec: OperatorMethodManager.cs:140-157
        assertEquals(-1L, evalInt(parseOrThrow(listOf(i(-7), op("%"), i(2)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(7), op("%"), i(-2)))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(-8), op("%"), i(2)))))
    }

    @Test
    fun `除零报错`() {
        assertFailsWith<ExprEvalException> { evalInt(parseOrThrow(listOf(i(1), op("/"), i(0)))) }
        assertFailsWith<ExprEvalException> { evalInt(parseOrThrow(listOf(i(1), op("%"), i(0)))) }
    }

    /**
     * `Long.MIN_VALUE / -1`：C# 抛 OverflowException（.NET 唯一会溢出的整除，
     * spec: OperatorMethodManager.cs:136），JVM 的 ldiv 静默回绕成 MIN_VALUE —— 按原版抛错。
     * 取余不在此列：C# 与 JVM 都得 0（ECMA 除余规则只在除法上抛溢出）。
     */
    @Test
    fun `最小整数除以负一报错取余仍为 0`() {
        assertFailsWith<ExprEvalException> {
            evalInt(parseOrThrow(listOf(i(Long.MIN_VALUE), op("/"), i(-1))))
        }
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(Long.MIN_VALUE), op("%"), i(-1)))))
        // 边界外的同号结果保持原样（MIN_VALUE / 1 与 MAX_VALUE / -1 都不溢出）
        assertEquals(Long.MIN_VALUE, evalInt(parseOrThrow(listOf(i(Long.MIN_VALUE), op("/"), i(1)))))
        assertEquals(-Long.MAX_VALUE, evalInt(parseOrThrow(listOf(i(Long.MAX_VALUE), op("/"), i(-1)))))
    }

    @Test
    fun `一元负号与长整型下限`() {
        assertEquals(1L, evalInt(parseOrThrow(listOf(op("-"), lp(), i(2), op("-"), i(3), rp()))))
        val ctx = TestContext()
        val e = parseOrThrow(listOf(op("-"), lp(), i(0), op("-"), i(Long.MIN_VALUE), rp()))
        assertEquals(Long.MIN_VALUE, evalInt(e, ctx))
        // spec: OperatorMethodManager.cs:561-564 —— MIN_VALUE 取负打印系统行
        assertEquals(1, ctx.systemLines.size)
    }

    // ---------- `+` 重载与字符串 ----------

    @Test
    fun `加号重载-int加法与str拼接`() {
        assertEquals(3L, evalInt(parseOrThrow(listOf(i(1), op("+"), i(2)))))
        assertEquals("ab中", evalStr(parseOrThrow(listOf(s("ab"), op("+"), s("中")))))
        // 混合类型在解析期即报错。spec: OperatorMethodManager.cs:813-848
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(s("a"), op("+"), i(1))) }
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(i(1), op("+"), s("a"))) }
    }

    @Test
    fun `str乘int重复字符串`() {
        // spec: OperatorMethodManager.cs:75-118
        assertEquals("ababab", evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(3)))))
        assertEquals("ababab", evalStr(parseOrThrow(listOf(i(3), op("*"), s("ab")))))
        assertEquals("", evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(0)))))
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(-1)))) }
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(s("ab"), op("*"), i(10000)))) }
    }

    // ---------- 一元 ! ~ + ----------

    @Test
    fun `逻辑非与按位非`() {
        assertEquals(0L, evalInt(parseOrThrow(listOf(op("!"), i(5)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(op("!"), i(0)))))
        assertEquals(-1L, evalInt(parseOrThrow(listOf(op("~"), i(0)))))
        // 一元 + 恒等（解析期消除）。spec: OperatorMethodManager.cs:537-549,763-766
        // 注意：原版 TermStack 状态机在 state 2/3 拒绝第二个前缀运算符，
        // 即 `++1`、`--1`、`!-1` 这类无括号连用是语法错误（ExpressionParser.cs:31-34）。
        assertEquals(7L, evalInt(parseOrThrow(listOf(op("+"), i(7)))))
        // 字符串不能取非。spec: OperatorMethodManager.cs:783-784
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(op("!"), s("a"))) }
    }

    // ---------- 位运算与移位 ----------

    @Test
    fun `位运算`() {
        assertEquals(2L, evalInt(parseOrThrow(listOf(i(6), op("&"), i(3)))))
        assertEquals(7L, evalInt(parseOrThrow(listOf(i(6), op("|"), i(3)))))
        assertEquals(5L, evalInt(parseOrThrow(listOf(i(6), op("^"), i(3)))))
    }

    @Test
    fun `移位为算术移位`() {
        assertEquals(-4L, evalInt(parseOrThrow(listOf(i(-8), op(">>"), i(1)))))
        assertEquals(1L shl 62, evalInt(parseOrThrow(listOf(i(1), op("<<"), i(62)))))
        // 移位计数超出按低 6 位取模（JVM 与 C# 一致）
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("<<"), i(64)))))
    }

    // ---------- 比较 ----------

    @Test
    fun `数值比较`() {
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("<"), i(2)))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(2), op("<="), i(1)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(2), op(">="), i(2)))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("=="), i(2)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("!="), i(2)))))
    }

    @Test
    fun `字符串比较按序数`() {
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("abc"), op("<"), s("abd")))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op("=="), s("a")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("a"), op("!="), s("a")))))
        // 原版 bug 复刻：字符串 >= 实现为 `< 0`。spec: OperatorMethodManager.cs:331
        assertEquals(1L, evalInt(parseOrThrow(listOf(s("a"), op(">="), s("b")))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(s("b"), op(">="), s("a")))))
    }

    @Test
    fun `比较链左结合`() {
        // (1<2)<3 → 1<3 → 1
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("<"), i(2), op("<"), i(3)))))
    }

    // ---------- 逻辑运算（含短路） ----------

    @Test
    fun `逻辑运算`() {
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("&&"), i(2)))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("&&"), i(0)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("||"), i(3)))))
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("||"), i(0)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("^^"), i(0)))))   // ^^ 互斥
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("^^"), i(2)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("!&"), i(1)))))   // !& 与非
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!&"), i(1)))))
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("!|"), i(0)))))   // !| 或非
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("!|"), i(1)))))
    }

    @Test
    fun `逻辑与或短路求值`() {
        // 原版 && 位于 if 条件中，左操作数为 0 时右侧不求值。spec: OperatorMethodManager.cs:383-409
        val ctx = TestContext()
        ctx.funcs["F"] = { Value.IntV(1L) }
        val fCall = { listOf(id("F"), lp(), rp()) }
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("&&")) + fCall()), ctx))
        assertEquals(0, ctx.funcCalls.size)
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("||")) + fCall()), ctx))
        assertEquals(0, ctx.funcCalls.size)
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(1), op("&&")) + fCall()), ctx))
        assertEquals(1, ctx.funcCalls.size)
    }

    /**
     * `!&` / `!|` 与原版同样短路：原版的判据本身是 C# 的 `||` / `&&`，
     * 左操作数已决定结果时右侧不求值（副作用 / RAND 消耗随之跳过）。
     * spec: OperatorMethodManager.cs:439-446（NandIntInt，判据 :441）、:457-464（NorIntInt，判据 :459）。
     */
    @Test
    fun `与非或非短路求值`() {
        val ctx = TestContext()
        ctx.funcs["F"] = { Value.IntV(1L) }
        val fCall = { listOf(id("F"), lp(), rp()) }

        // !&：左为 0 → 1，右侧不求值；左非 0 → 求右侧。
        assertEquals(1L, evalInt(parseOrThrow(listOf(i(0), op("!&")) + fCall()), ctx))
        assertEquals(0, ctx.funcCalls.size, "0 !& F() 右侧不求值")
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!&")) + fCall()), ctx))
        assertEquals(1, ctx.funcCalls.size, "1 !& F() 右侧必须求值")

        // !|：左非 0 → 0，右侧不求值；左为 0 → 求右侧。
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(1), op("!|")) + fCall()), ctx))
        assertEquals(1, ctx.funcCalls.size, "1 !| F() 右侧不求值")
        assertEquals(0L, evalInt(parseOrThrow(listOf(i(0), op("!|")) + fCall()), ctx))
        assertEquals(2, ctx.funcCalls.size, "0 !| F() 右侧必须求值")
    }

    // ---------- 三目 ----------

    @Test
    fun `三目运算`() {
        assertEquals(10L, evalInt(parseOrThrow(listOf(i(1), op("?"), i(10), op("#"), i(20)))))
        assertEquals(20L, evalInt(parseOrThrow(listOf(i(0), op("?"), i(10), op("#"), i(20)))))
        assertEquals("x", evalStr(parseOrThrow(listOf(i(0), op("?"), s("y"), op("#"), s("x")))))
        // 分支惰性：未选中的分支不求值。spec: OperatorMethodManager.cs:657-691
        val ctx = TestContext()
        ctx.funcs["F"] = { Value.IntV(9L) }
        val e = parseOrThrow(listOf(i(0), op("?"), id("F"), lp(), rp(), op("#"), i(20)))
        assertEquals(20L, evalInt(e, ctx))
        assertEquals(0, ctx.funcCalls.size)
    }

    @Test
    fun `三目左结合`() {
        // a?b#c?d:e ≙ ((a?b:c)?d:e)：1?0:1 → 0 → 0?3:4 → 4
        assertEquals(
            4L,
            evalInt(parseOrThrow(listOf(i(1), op("?"), i(0), op("#"), i(1), op("?"), i(3), op("#"), i(4)))),
        )
        assertEquals(
            10L,
            evalInt(parseOrThrow(listOf(i(0), op("?"), i(9), op("#"), i(0), op("?"), i(3), op("#"), i(10)))),
        )
    }

    @Test
    fun `三目分支类型必须一致`() {
        assertFailsWith<ExprEvalException> {
            parseOrThrow(listOf(i(1), op("?"), i(2), op("#"), s("a")))
        }
    }

    // ---------- 自增自减 ----------

    @Test
    fun `自增自减的值与副作用`() {
        val ctx = TestContext()
        ctx.variables.ints["A"] = 5L
        val resolver = resolverWith(var0D("A", OperandType.INT))
        // 前置：返回新值。spec: OperatorMethodManager.cs:601-627
        assertEquals(6L, evalInt(parseOrThrow(listOf(op("++"), id("A")), resolver), ctx))
        assertEquals(6L, ctx.variables.ints["A"])
        // 后置：返回旧值。spec: OperatorMethodManager.cs:629-655
        assertEquals(6L, evalInt(parseOrThrow(listOf(id("A"), op("++")), resolver), ctx))
        assertEquals(7L, ctx.variables.ints["A"])
        assertEquals(7L, evalInt(parseOrThrow(listOf(id("A"), op("--")), resolver), ctx))
        assertEquals(6L, ctx.variables.ints["A"])
        // 前置：返回新值
        assertEquals(5L, evalInt(parseOrThrow(listOf(op("--"), id("A")), resolver), ctx))
        assertEquals(5L, ctx.variables.ints["A"])
    }

    @Test
    fun `自增副作用时点-左先于右`() {
        val ctx = TestContext()
        ctx.variables.ints["A"] = 5L
        val resolver = resolverWith(var0D("A", OperandType.INT))
        // A++ + A → 5 + 6 = 11（左操作数先求值并完成自增）
        assertEquals(11L, evalInt(parseOrThrow(listOf(id("A"), op("++"), op("+"), id("A")), resolver), ctx))
        // A + A++ → 6 + 6 = 12（A 已被上一断言增至 6，后置返回旧值 6）
        assertEquals(12L, evalInt(parseOrThrow(listOf(id("A"), op("+"), id("A"), op("++")), resolver), ctx))
    }

    @Test
    fun `自增自减仅限变量`() {
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(op("++"), i(5))) }
        val resolver = resolverWith(VarInfo("C", OperandType.INT, 0, isCharacterData = false, isConst = true))
        assertFailsWith<ExprEvalException> { parseOrThrow(listOf(op("++"), id("C")), resolver) }
        // 前后置连用报错。spec: ExpressionParser.cs:56-65
        val resolverA = resolverWith(var0D("A", OperandType.INT))
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("A"), op("++"), op("++")), resolverA) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(op("++"), id("A"), op("++")), resolverA) }
    }

    // ---------- 变量引用 ----------

    @Test
    fun `变量引用与下标`() {
        val ctx = TestContext()
        ctx.variables.ints["X"] = 3L
        ctx.variables.ints["ITEM:0"] = 7L
        ctx.variables.ints["ITEM:5"] = 99L
        ctx.variables.keywords[Triple("ITEM", 0, "药")] = 5L
        val resolver = resolverWith(
            var0D("X", OperandType.INT),
            VarInfo("ITEM", OperandType.INT, 1, isCharacterData = false),
        )
        assertEquals(3L, evalInt(parseOrThrow(listOf(id("X")), resolver), ctx))
        assertEquals(7L, evalInt(parseOrThrow(listOf(id("ITEM")), resolver), ctx))  // 省略下标 → 0
        assertEquals(7L, evalInt(parseOrThrow(listOf(id("ITEM"), colon(), i(0)), resolver), ctx))
        assertEquals(99L, evalInt(parseOrThrow(listOf(id("ITEM"), colon(), id("药")), resolver), ctx))  // 关键字下标
    }

    @Test
    fun `角色变量省略下标补TARGET`() {
        val ctx = TestContext()
        ctx.variables.ints["TARGET:0"] = 3L
        ctx.variables.ints["ABL:3:2"] = 30L
        ctx.variables.ints["ABL:3:0"] = 31L
        ctx.variables.ints["ABL:7:4"] = 77L
        val resolver = resolverWith(VarInfo("ABL", OperandType.INT, 1, isCharacterData = true))
        // ABL:2 → TARGET:2。spec: VariableParser.cs:94-103
        assertEquals(30L, evalInt(parseOrThrow(listOf(id("ABL"), colon(), i(2)), resolver), ctx))
        // ABL → TARGET:0。spec: VariableParser.cs:90-103
        assertEquals(31L, evalInt(parseOrThrow(listOf(id("ABL")), resolver), ctx))
        // ABL:7:4 → [7,4]（已给两参则不补 TARGET）
        assertEquals(77L, evalInt(parseOrThrow(listOf(id("ABL"), colon(), i(7), colon(), i(4)), resolver), ctx))
    }

    @Test
    fun `2D3D变量下标省略规则`() {
        val resolver = resolverWith(
            VarInfo("DA", OperandType.INT, 2, isCharacterData = false),
            VarInfo("T3", OperandType.INT, 3, isCharacterData = false),
            var0D("Z", OperandType.INT),
        )
        // 全省略 → 无参形态
        val noArg = parseOrThrow(listOf(id("DA")), resolver) as VarRef
        assertTrue(noArg.isNoArg)
        // 部分省略 → 报错。spec: VariableParser.cs:138-152
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("DA"), colon(), i(1)), resolver) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("T3"), colon(), i(1), colon(), i(2)), resolver) }
        val ctx = TestContext()
        ctx.variables.ints["T3:1:2:3"] = 123L
        assertEquals(
            123L,
            evalInt(parseOrThrow(listOf(id("T3"), colon(), i(1), colon(), i(2), colon(), i(3)), resolver), ctx),
        )
        // 0D 变量带下标报错。spec: VariableParser.cs:174-180
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(id("Z"), colon(), i(1)), resolver) }
    }

    @Test
    fun `字符串变量读取`() {
        val ctx = TestContext()
        ctx.variables.strs["NAME:0"] = "爱丽丝"
        val resolver = resolverWith(VarInfo("NAME", OperandType.STR, 1, isCharacterData = false))
        assertEquals("爱丽丝", evalStr(parseOrThrow(listOf(id("NAME")), resolver), ctx))
    }

    // ---------- 函数调用 ----------

    @Test
    fun `函数调用`() {
        val ctx = TestContext()
        ctx.funcs["ADD"] = { args -> Value.IntV((args[0] as Value.IntV).v + (args[1] as Value.IntV).v) }
        ctx.funcs["REP"] = { args -> Value.StrV((args[0] as Value.StrV).v.repeat((args[1] as Value.IntV).v.toInt())) }
        val resolver = FakeResolver().apply {
            funcs["ADD"] = OperandType.INT
            funcs["REP"] = OperandType.STR
        }
        val e1 = parseOrThrow(listOf(id("ADD"), lp(), i(4), comma(), i(5), rp()), resolver)
        assertEquals(9L, evalInt(e1, ctx))
        val e2 = parseOrThrow(listOf(id("REP"), lp(), s("a"), comma(), i(4), rp()), resolver)
        assertEquals("aaaa", evalStr(e2, ctx))
        // 嵌套与表达式参数
        val e3 = parseOrThrow(
            listOf(
                id("ADD"), lp(), i(1), op("+"), i(2), comma(),
                id("ADD"), lp(), i(3), comma(), i(5), rp(), rp(),
            ),
            resolver,
        )
        assertEquals(11L, evalInt(e3, ctx))
    }

    @Test
    fun `字符串形式串占位`() {
        // FormStringTerm 已结构化（FormPart/FormConversion），详细覆盖见 FormStringTermTest
        assertEquals("x42y", evalStr(parseOrThrow(listOf(f("x{A}y"))), TestContext().apply { variables.ints["A"] = 42L }))
    }
}
