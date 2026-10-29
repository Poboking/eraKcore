package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `#DEFINE` 宏展开（spec: `LexicalAnalyzer.cs:942-1006`）。
 *
 * 只展开**标识符 token**：字符串字面量/注释内的同名文本保持原样；展开体重新词法化，
 * 支持宏套宏（100 次上限）。这是「`TALENT:ARG:氷精` 被幽灵变量抢走」与
 * `天候` / `DEF_DT_CID` 一类「宏名当变量」红 A 的语言层根因。
 */
class MacroExpansionTest {

    private val macros = linkedMapOf(
        "天候" to "TIME:5",
        "DEF_DT_CID" to "\"COLUMN_CID\"",
        "A" to "B + 1",
        "B" to "2",
    )

    private fun lexer(ignoreCase: Boolean = true) =
        ErbLexer(LexerConfig(macros = macros, macroIgnoreCase = ignoreCase))

    private fun tokensOf(text: String, ignoreCase: Boolean = true): List<Token> =
        lexer(ignoreCase).tokenizeLine(text, 0)

    @Test
    fun expandsIdentifierTokenToExpression() {
        val tokens = tokensOf("GROUPMATCH(天候, 10)")
        assertEquals("GROUPMATCH", (tokens[0] as Token.Identifier).code)
        assertEquals('(', (tokens[1] as Token.Symbol).char)
        assertEquals("TIME", (tokens[2] as Token.Identifier).code)
        assertEquals(':', (tokens[3] as Token.Symbol).char)
        assertEquals(5L, (tokens[4] as Token.Integer).value)
        assertEquals(',', (tokens[5] as Token.Symbol).char)
        assertEquals(10L, (tokens[6] as Token.Integer).value)
        assertEquals(')', (tokens[7] as Token.Symbol).char)
    }

    @Test
    fun expandsStringMacroToLiteralToken() {
        val tokens = tokensOf("DT_COLUMN_ADD L_DTNAME , DEF_DT_CID")
        val strs = tokens.filterIsInstance<Token.StringLiteral>()
        assertEquals(listOf("COLUMN_CID"), strs.map { it.value })
        assertTrue(tokens.none { it is Token.Identifier && it.code == "DEF_DT_CID" })
    }

    @Test
    fun expandsRecursively() {
        val tokens = tokensOf("A")
        assertEquals(3, tokens.size)
        assertEquals(2L, (tokens[0] as Token.Integer).value)
        assertEquals(OperatorCode.Plus, (tokens[1] as Token.Operator).code)
        assertEquals(1L, (tokens[2] as Token.Integer).value)
    }

    @Test
    fun doesNotExpandInsideStringOrComment() {
        val tokens = tokensOf("PRINTL \"天候\" ; 天候")
        assertEquals(2, tokens.size)
        assertEquals("PRINTL", (tokens[0] as Token.Identifier).code)
        assertEquals("天候", (tokens[1] as Token.StringLiteral).value)
    }

    @Test
    fun macroNameRespectsCaseSwitch() {
        val table = mapOf("DEF" to "1")
        val ci = ErbLexer(LexerConfig(macros = table, macroIgnoreCase = true))
            .tokenizeLine("def", 0)
        assertEquals(1, ci.size)
        assertEquals(1L, (ci[0] as Token.Integer).value)

        val cs = ErbLexer(LexerConfig(macros = table, macroIgnoreCase = false))
            .tokenizeLine("def", 0)
        assertEquals(1, cs.size)
        assertEquals("def", (cs[0] as Token.Identifier).code)
    }

    @Test
    fun cycleHitsExpansionLimit() {
        val cyclic = ErbLexer(
            LexerConfig(macros = mapOf("X" to "Y", "Y" to "X")),
        )
        assertFailsWith<LexError> { cyclic.tokenizeLine("X", 0) }
    }
}
