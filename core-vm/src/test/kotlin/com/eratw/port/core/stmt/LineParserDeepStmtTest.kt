package com.eratw.port.core.stmt


import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import java.util.Locale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 深审 stmt 语句层（deep-stmt-parse）的回归：
 *
 * 1. **语句名归一与宿主 locale 无关**：原版 `funcDic` 用 `StringComparer.OrdinalIgnoreCase`
 *    （spec: FunctionIdentifier.cs:3823）。审计结论：Kotlin `String.uppercase()` **默认即
 *    `Locale.ROOT`**（引导期旧 `toUpperCase()` 才是宿主敏感），故当前 `LineParser` 没有
 *    locale 缺陷；本用例把「tr 宿主下 printl/input/#dim 仍正确分派」钉住，防回归到
 *    `toUpperCase()`。
 * 2. **`VARI` 初值必须整型**：原版 `LogicalLineParser.cs:461` 走 `ReduceIntegerTerm`，
 *    非整型（`VARI A = "x"`）按 CodeEE 拒行；移植侧曾用 `reduceExpressionTerm` 收成字符串初值。
 * 3. **形参默认值原文还原**：`ErbLoader.cs:646-760` 的默认值原文用于报告；`Token.Operator.text`
 *    是 C# 枚举名（`Minus`/`Plus`），直接用 `it.text` 会得到 `Minus1`，须经 [LineParser] 的
 *    `tokenText`（同文件里为下标诊断写的同一映射）还原成 `-1` / `1+1`。
 */
class LineParserDeepStmtTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 10): Statement? =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "deep.erb", lineNo, text))

    // ------------------------------------------------------------------
    // 1. Locale 无关的语句名归一
    // ------------------------------------------------------------------


    @Test
    fun `小写语句名在土耳其语宿主下仍能分派`() {
        val saved = Locale.getDefault()
        try {
            // tr：'i'→'İ'（U+0130）。Kotlin 的 `String.uppercase()` 默认即 `Locale.ROOT`
            // （不是引导期旧 `toUpperCase()` 的宿主敏感实现），故 printl 仍归一为 PRINTL ——
            // 本用例把这条语言事实钉住，防止有人把它换回 `toUpperCase()`。
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("PRINTL", "printl".uppercase(), "Kotlin uppercase() 默认 Locale.ROOT")
            assertEquals("PRİNTL", "printl".uppercase(Locale.getDefault()), "对照：宿主 locale 版会跑偏")

            val s = assertIs<PrintStmt>(parse("printl こんにちは"), "小写语句名必须仍命中指令表")
            assertEquals(FunctionCode.PRINTL, s.code)
            assertIs<PrintStmt>(parse("PrintL こんにちは"))
            // 方法名通道同样归一到 ROOT（input 也是方法名/canBeReferencedBare 面）
            assertEquals(FunctionCode.INPUT, parse("input 100, 0, 1")!!.code)
            // `#` 行关键字同为 ROOT 归一：否则 `#dim xyz, 3` 落到 else 分支（name/values 全空）
            val dim = assertIs<SharpStmt>(parser().parseSharpLine("#dim xyz, 3", 1))
            assertEquals("xyz", dim.name, "#dim 小写关键字必须命中 DIM 分支")
            assertEquals(listOf(3L), dim.values)
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ------------------------------------------------------------------
    // 2. VARI 初值必须整型
    // ------------------------------------------------------------------

    @Test
    fun `VARI 初值是非整型表达式时必须拒绝`() {
        // spec: LogicalLineParser.cs:461 ReduceIntegerTerm → 非 long 抛 CodeEE → 该行 InvalidLine。
        assertFailsWith<StmtParseException> { parse("VARI A = \"字\"" ) }
        // 字面量整型照旧可注入
        val ok = assertIs<VarDeclStmt>(parse("VARI A = 5"))
        assertEquals(5L, (assertIs<IntAsignArgument>(ok.args).exp as IntLiteral).value)
    }

    // ------------------------------------------------------------------
    // 3. 形参默认值原文（tokenText 还原，不是 C# 枚举名）
    // ------------------------------------------------------------------

    private fun label(text: String): FuncLabelStmt =
        assertIs<FuncLabelStmt>(parser().parseLabelLine(text, 1, "deep.erb"))

    @Test
    fun `形参默认值原文还原一元负号与二元表达式`() {
        val d0 = label("@F(OUT = -1)").paramDefaults[0]!!
        assertEquals("-1", d0.raw, "不得是 Minus1（Token.Operator.text 是 C# 枚举名）")
        assertEquals(-1L, (d0.expr as IntLiteral).value, "一元负号按常量折叠注入")

        val d1 = label("@F(OUT = 1 + 2)").paramDefaults[0]!!
        assertEquals("1+2", d1.raw, "不得是 1Plus2")
        assertNull(d1.expr, "二元表达式本遍不折叠（只收字面量 / 一元折叠）")

        val d2 = label("@F(OUT = \"s\")").paramDefaults[0]!!
        assertEquals("\"s\"", d2.raw)

        // 无默认值的位
        assertNull(label("@F(OUT)").paramDefaults[0])
        assertTrue(label("@F(OUT)").paramDefaults.size == 1)
    }
}