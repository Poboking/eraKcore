package com.eratw.port.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FormStringTerm 结构化 parts 测试。
 * 构造路径：形式串原文 → ErbLexer.analyseFormattedString（真实词法）→
 * ExprParser(FORM_STRING) → FormStringTerm.fromLexerStrForm（spec: StrForm.cs:206-353）。
 */
class FormStringTermTest {

    private fun strResolver(vararg names: String) =
        resolverWith(*names.map { VarInfo(it, OperandType.STR, 0, isCharacterData = false) }.toTypedArray())

    private fun ctxWith(ints: Map<String, Long> = emptyMap(), strs: Map<String, String> = emptyMap()) =
        TestContext().apply {
            ints.forEach { (k, v) -> variables.ints[k] = v }
            strs.forEach { (k, v) -> variables.strs[k] = v }
        }

    // ------------------------------------------------------------------
    // 基本形态
    // ------------------------------------------------------------------

    @Test
    fun `纯文本形式串`() {
        // IsConst 快路径。spec: StrForm.cs:403-406
        assertEquals("abc", evalStr(parseOrThrow(listOf(f("abc")))))
        assertEquals("纯文本", evalStr(parseOrThrow(listOf(f("纯文本")))))
    }

    @Test
    fun `单表达式-花括号int转string`() {
        // {int} → long.ToString()。spec: StrForm.cs:47-59
        val ctx = ctxWith(ints = mapOf("A" to 42L))
        assertEquals("42", evalStr(parseOrThrow(listOf(f("{A}"))), ctx))
    }

    @Test
    fun `单表达式-百分号字符串`() {
        // %str% 原样。spec: StrForm.cs:62-84
        val resolver = strResolver("S")
        val ctx = ctxWith(strs = mapOf("S" to "hello"))
        assertEquals("hello", evalStr(parseOrThrow(listOf(f("%S%")), resolver), ctx))
    }

    @Test
    fun `文本与表达式混合`() {
        val ctx = ctxWith(ints = mapOf("A" to 12L, "B" to 34L))
        assertEquals("HP=12 MP=34", evalStr(parseOrThrow(listOf(f("HP={A} MP={B}"))), ctx))
    }

    @Test
    fun `parts结构-文本与内嵌交替`() {
        val e = parseOrThrow(listOf(f("x{A}y{B}z")))
        assertTrue(e is FormStringTerm)
        // strs 4 段 + 内嵌 2 段，空文本段原样保留 → 4+2=6? strs=[x,y,z]... 见 fromLexerStrForm
        assertEquals(5, e.parts.size)
        assertTrue(e.parts[0] is FormPart.Text)
        assertTrue(e.parts[1] is FormPart.Embedded)
        assertTrue(e.parts[2] is FormPart.Text)
        assertTrue(e.parts[3] is FormPart.Embedded)
        assertTrue(e.parts[4] is FormPart.Text)
        assertTrue((e.parts[1] as FormPart.Embedded).conversion is FormConversion.Curly)
    }

    // ------------------------------------------------------------------
    // int→string 边界（spec: StrForm.cs:49 long.ToString()）
    // ------------------------------------------------------------------

    @Test
    fun `int转string边界-负数与大数`() {
        val ctx = ctxWith(ints = mapOf("A" to Long.MIN_VALUE, "B" to Long.MAX_VALUE, "C" to -5L))
        assertEquals(
            "[-9223372036854775808][9223372036854775807][-5]",
            evalStr(parseOrThrow(listOf(f("[{A}][{B}][{C}]"))), ctx),
        )
    }

    // ------------------------------------------------------------------
    // 宽度与对齐
    // ------------------------------------------------------------------

    @Test
    fun `花括号宽度补齐-默认右对齐`() {
        // {expr,width} 无第三参数 → PadLeft（右对齐）。spec: StrForm.cs:58
        val ctx = ctxWith(ints = mapOf("A" to 42L))
        assertEquals("   42", evalStr(parseOrThrow(listOf(f("{A,5}"))), ctx))
        // 宽度小于结果长度 → 原样。spec: StrForm.cs:56,58（.NET Pad 语义）
        assertEquals("42", evalStr(parseOrThrow(listOf(f("{A,1}"))), ctx))
        assertEquals("42", evalStr(parseOrThrow(listOf(f("{A,2}"))), ctx))
    }

    @Test
    fun `花括号宽度补齐-LEFT左对齐`() {
        // LEFT → PadRight。spec: StrForm.cs:295-298,56
        val ctx = ctxWith(ints = mapOf("A" to 42L))
        assertEquals("42   ", evalStr(parseOrThrow(listOf(f("{A,5,LEFT}"))), ctx))
        // RIGHT 显式 → PadLeft。spec: StrForm.cs:299,58
        assertEquals("   42", evalStr(parseOrThrow(listOf(f("{A,5,RIGHT}"))), ctx))
        // LEFT/RIGHT 大小写不敏感（Config.StringComparison 忽略大小写）。spec: StrForm.cs:295,299
        assertEquals("42   ", evalStr(parseOrThrow(listOf(f("{A,5,left}"))), ctx))
    }

    @Test
    fun `宽度可以是表达式`() {
        // 第二参数经 ReduceIntegerTerm。spec: StrForm.cs:287
        val ctx = ctxWith(ints = mapOf("A" to 1L, "B" to 2L))
        assertEquals("  1", evalStr(parseOrThrow(listOf(f("{A,B+1}"))), ctx))
        assertEquals("1  ", evalStr(parseOrThrow(listOf(f("{A,B+1,LEFT}"))), ctx))
    }

    @Test
    fun `花括号负宽度报错`() {
        // .NET PadLeft/PadRight 负 totalWidth 抛 ArgumentOutOfRangeException（原版异常路径）
        val ctx = ctxWith(ints = mapOf("A" to 42L))
        assertFailsWith<ExprEvalException> { evalStr(parseOrThrow(listOf(f("{A,-1}"))), ctx) }
    }

    @Test
    fun `百分号宽度补齐-全角按显示宽度折算`() {
        // "中文" SJIS 4 字节、2 字符：num = width - (4 - 2)。spec: StrForm.cs:72-77
        val resolver = strResolver("S")
        val ctx = ctxWith(strs = mapOf("S" to "中文"))
        // width=5 → num=3 → 左补 1 空格
        assertEquals(" 中文", evalStr(parseOrThrow(listOf(f("%S,5%")), resolver), ctx))
        assertEquals("中文 ", evalStr(parseOrThrow(listOf(f("%S,5,LEFT%")), resolver), ctx))
        // width=4 → num=2 → 恰好不补
        assertEquals("中文", evalStr(parseOrThrow(listOf(f("%S,4%")), resolver), ctx))
        // width=2 → num=0 < 2 → 原样返回（不裁剪）。spec: StrForm.cs:74-77
        assertEquals("中文", evalStr(parseOrThrow(listOf(f("%S,2%")), resolver), ctx))
        // 半角不受折算影响
        val ctx2 = ctxWith(strs = mapOf("S" to "ab"))
        assertEquals("  ab", evalStr(parseOrThrow(listOf(f("%S,4%")), resolver), ctx2))
    }

    // ------------------------------------------------------------------
    // 嵌套 form
    // ------------------------------------------------------------------

    @Test
    fun `嵌套form-百分号内嵌形式串`() {
        // %@"内{x}"% —— 形式串本身是 STR 型，可作为 %...% 的表达式。spec: StrForm.cs:272-281,332
        val ctx = ctxWith(ints = mapOf("x" to 9L))
        assertEquals("内9", evalStr(parseOrThrow(listOf(f("%@\"内{x}\"%"))), ctx))
    }

    @Test
    fun `嵌套form-花括号内形式串报错`() {
        // {} 要求数值型表达式，形式串是 STR → 解析期报错。spec: StrForm.cs:310-315
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{@\"a\"}"))) }
    }

    @Test
    fun `YenAt三项条件式`() {
        // \@cond?left#right\@：cond != 0 → left，== 0 → right。spec: StrForm.cs:86-96
        val ctx1 = ctxWith(ints = mapOf("A" to 1L))
        assertEquals("是", evalStr(parseOrThrow(listOf(f("\\@A?是#否\\@"))), ctx1))
        val ctx0 = ctxWith(ints = mapOf("A" to 0L))
        assertEquals("否", evalStr(parseOrThrow(listOf(f("\\@A?是#否\\@"))), ctx0))
        // 条件可以是表达式
        val ctx2 = ctxWith(ints = mapOf("A" to 2L))
        assertEquals("大", evalStr(parseOrThrow(listOf(f("\\@A>1?大#小\\@"))), ctx2))
    }

    @Test
    fun `YenAt缺少井号分支-取空串`() {
        // 缺 # 分支 → right = SingleStrTerm("")。spec: StrForm.cs:256
        val ctx1 = ctxWith(ints = mapOf("A" to 1L))
        assertEquals("只", evalStr(parseOrThrow(listOf(f("\\@A?只\\@"))), ctx1))
        val ctx0 = ctxWith(ints = mapOf("A" to 0L))
        assertEquals("", evalStr(parseOrThrow(listOf(f("\\@A?只\\@"))), ctx0))
    }

    @Test
    fun `YenAt分支内可再嵌form`() {
        // 分支是嵌套 StrFormTerm。spec: StrForm.cs:255-256
        val ctx = ctxWith(ints = mapOf("A" to 1L, "B" to 7L))
        assertEquals("[7]", evalStr(parseOrThrow(listOf(f("\\@A?[{B}]#否\\@"))), ctx))
        val ctx0 = ctxWith(ints = mapOf("A" to 0L, "B" to 7L))
        assertEquals("否", evalStr(parseOrThrow(listOf(f("\\@A?[{B}]#否\\@"))), ctx0))
    }

    // ------------------------------------------------------------------
    // 三连记号（spec: StrForm.cs:216-238 → NAME/CALLNAME 系统变量）
    // ------------------------------------------------------------------

    @Test
    fun `三连记号-五种映射`() {
        fun sym(raw: String, target: Long, name: String, callname: String): String {
            val ctx = ctxWith(
                // 角色变量以下标 [0] 引用 → FakeVariables 的键为 "TARGET:0" 等
                ints = mapOf(
                    "TARGET:0" to target, "MASTER:0" to target,
                    "PLAYER:0" to target, "ASSI:0" to target,
                ),
                strs = mapOf("NAME:$target" to name, "CALLNAME:$target" to callname),
            )
            return evalStr(parseOrThrow(listOf(f(raw))), ctx)
        }
        // *** → NAME:TARGET。spec: StrForm.cs:220-221
        assertEquals("爱丽丝", sym("***", 7L, "爱丽丝", "Alice"))
        // $$$ → CALLNAME:TARGET。spec: StrForm.cs:232-233
        assertEquals("Alice", sym("\$\$\$", 7L, "爱丽丝", "Alice"))
        // +++ → CALLNAME:MASTER。spec: StrForm.cs:223-224
        assertEquals("Mas", sym("+++", 3L, "N", "Mas"))
        // === → CALLNAME:PLAYER。spec: StrForm.cs:226-227
        assertEquals("Pl", sym("===", 4L, "N", "Pl"))
        // /// → NAME:ASSI。spec: StrForm.cs:229-230
        assertEquals("AssiName", sym("///", 5L, "AssiName", "C"))
    }

    // ------------------------------------------------------------------
    // 转义序列（词法层解码，spec: LexicalAnalyzer.cs:1148-1201）
    // ------------------------------------------------------------------

    @Test
    fun `form内转义序列`() {
        // \s→空格 \S→全角空格 \n→换行 \t→制表 \x→x（未定义转义取字符本身）
        assertEquals("a b\u3000c\nd\te", evalStr(parseOrThrow(listOf(f("a\\sb\\Sc\\nd\\te")))))
        // 无转义的普通反斜杠后随普通字符
        assertEquals("x/y", evalStr(parseOrThrow(listOf(f("x\\/y")))))
    }

    // ------------------------------------------------------------------
    // 解析期错误路径
    // ------------------------------------------------------------------

    @Test
    fun `空花括号与空百分号报错`() {
        // spec: StrForm.cs:274-281（EmptyBrace / EmptyPer）
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{}"))) }
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("%%"))) }
    }

    @Test
    fun `花括号内字符串与百分号内整数报错`() {
        // {} 要求数值型。spec: StrForm.cs:310-315
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{\"a\"}"))) }
        // % % 要求字符串型。spec: StrForm.cs:330-335
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("%A%"))) }
    }

    @Test
    fun `第三参数必须是LEFT或RIGHT`() {
        // 非标识符。spec: StrForm.cs:289-293
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{A,5,6}"))) }
        // 非 LEFT/RIGHT。spec: StrForm.cs:295-302
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{A,5,TOP}"))) }
        // LEFT 之后有多余字符。spec: StrForm.cs:305-308
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("{A,5,LEFT,}"))) }
    }

    @Test
    fun `YenAt条件为空或非法报错`() {
        // 条件必须可归约为整数表达式。spec: StrForm.cs:241-249
        assertFailsWith<ExprParseException> { parseOrThrow(listOf(f("\\@?x#y\\@"))) }
    }
}
