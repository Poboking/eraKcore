package com.eratw.port.core.stmt

import com.eratw.port.core.expr.ExprToken
import com.eratw.port.core.expr.ExprTokenKind
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.expr.TermEndWith
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.Ternary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * CMP-52 必红夹具 —— `splitTerm` 的终止判据必须比**运算符枚举**，
 * 不许拿源码符号文本去比枚举名。
 *
 * `ExprToken(OPERATOR).text` 是**源码符号**（`"?"` / `"#"` / `"="` / `"'="`，
 * 由 `ExprToken` 适配层 `ExprOperatorCode.toOperatorString` 产出，
 * spec: Operator.kt:141-142），而枚举名是 `TERNARY_A` / `TERNARY_B` / `ASSIGNMENT` /
 * `ASSIGNMENT_STR`。旧实现 `t.text == ExprOperatorCode.TERNARY_A.name` 等三处恒假
 * （`ParseSupport.kt:322-331`），所以 `TermEndWith.Assignment` 位在 `splitTerm` 里**永不生效**：
 * 任何将来传该位的调用点都会把 `名=默认值` 当成一条表达式。
 * 原版判据（spec: ExpressionParser.cs:548-555）：`code2 == OperatorCode.Assignment` 且
 * `(endWith & TermEndWith.Assignment) == TermEndWith.Assignment` → 交回上层 `end()`。
 */
class Cmp52SplitTermEndWithTest {

    private fun ctx(): ArgCtx {
        val sym = SymbolTable()
            .declareVariable("A", com.eratw.port.core.expr.OperandType.INT, 0)
            .declareVariable("S", com.eratw.port.core.expr.OperandType.STR, 0)
        return ArgCtx(
            line = 3,
            code = FunctionCode.SET,
            statementName = "SET",
            argText = "",
            resolver = sym,
            config = ParserConfig(),
            warn = {},
        )
    }

    private fun op(text: String) = ExprToken(ExprTokenKind.OPERATOR, text, line = 3, col = 2)

    private fun ident(text: String) = ExprToken(ExprTokenKind.IDENTIFIER, text, line = 3, col = 0)

    private fun int(v: Long) = ExprToken(ExprTokenKind.INT_LITERAL, v.toString(), intValue = v, line = 3, col = 4)

    // -----------------------------------------------------------------------
    // 红形：Assignment 终止位
    // -----------------------------------------------------------------------

    @Test
    fun `Assignment 位在等号处切断`() {
        // `A = 5` 的表达式位：上层要拿到 (A, 1)，由它自己消费 '='。
        val tokens = listOf(ident("A"), op("="), int(5))
        val (term, next) = splitTerm(tokens, 0, TermEndWith.Assignment, ctx())
        assertEquals(1, next, "`=` 必须被 TermEndWith.Assignment 切断（ExpressionParser.cs:548-553）")
        val vr = assertIs<VarRef>(term)
        assertEquals("A", vr.name)
    }

    @Test
    fun `Assignment 位在字符串赋值符处切断`() {
        val tokens = listOf(
            ident("S"),
            op("'="),
            ExprToken(ExprTokenKind.STR_LITERAL, "x", line = 3, col = 4),
        )
        val (term, next) = splitTerm(tokens, 0, TermEndWith.Assignment, ctx())
        assertEquals(1, next, "`'=`（ASSIGNMENT_STR）同样受 Assignment 位控制")
        assertEquals("S", assertIs<VarRef>(term).name)
    }

    @Test
    fun `不传 Assignment 位时等号不是终止符`() {
        // 对照：没有该位时切分不在 '=' 处停，整段交给表达式层按原版抛
        // `EqualInExpression`（spec: ExpressionParser.cs:554）。
        val tokens = listOf(int(1), op("="), int(2))
        assertFailsWith<Throwable> { splitTerm(tokens, 0, TermEndWith.Comma, ctx()) }
    }

    // -----------------------------------------------------------------------
    // 三目：`?` / `#` 运算符按枚举识别（旧实现恒假；本用例锁「不得因此改变边界」）
    // -----------------------------------------------------------------------

    @Test
    fun `三目表达式在逗号处切断`() {
        val tokens = listOf(
            int(1), op("?"), int(2), op("#"), int(3),
            ExprToken(ExprTokenKind.COMMA, ",", line = 3, col = 12),
        )
        val (term, next) = splitTerm(tokens, 0, TermEndWith.Comma, ctx())
        assertEquals(5, next, "顶层逗号才是实参边界")
        val t = assertIs<Ternary>(term)
        assertEquals(1L, (t.cond as com.eratw.port.core.expr.IntLiteral).value)
    }

    // -----------------------------------------------------------------------
    // CMP-53 复核：KeyWordPx 位的实现在位，但全仓**无调用点传入**（见交付回执）
    // -----------------------------------------------------------------------

    @Test
    fun `KeyWordPx 位在 px 关键字处切断`() {
        val tokens = listOf(ident("A"), ident("px"), ExprToken(ExprTokenKind.COMMA, ",", line = 3, col = 5))
        val (_, next) = splitTerm(tokens, 0, TermEndWith.Comma or TermEndWith.KeyWordPx, ctx())
        assertEquals(1, next, "px 是尺寸单位后缀，不进表达式（ExpressionParser.cs:534-537）")
    }
}
