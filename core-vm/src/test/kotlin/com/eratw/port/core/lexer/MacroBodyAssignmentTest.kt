package com.eratw.port.core.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CMP-37：`#DEFINE` 展开体的重切 flag 必须与原版声明时口径一致。
 *
 * 原版证据链：`ErhLoader.cs:166` 声明时用 `LexAnalyzeFlag.AllowAssignment` 把展开体解析成
 * 既成 `WordCollection`（`DefineMacro.cs:22` 存 `Statement`）；使用点 `LexicalAnalyzer.cs:967`
 * `wc.InsertRange(macro.Statement)` 原地拼接、不再词法化。本实现存原文、每次使用重切，
 * 故重切 flag 同为 `ALLOW_ASSIGNMENT`。
 *
 * 修复前（`LexAnalyzeFlag.NONE` 重切）的实际抛错（本文件第二个用例组反向钉住该口径只在
 * 宏体外生效）：宏表 `M -> A=1` 时 `tokenizeLine("PRINT M")` 抛
 * `LexError("予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)")`。
 *
 * 影响面：NONE 与 ALLOW_ASSIGNMENT 在 analyse 主循环的唯一差异是裸 `'='` 的
 * `readOperator` 分支（`LexicalAnalyzer.kt:229-236`）；`'=='`、`'!='`、`'<='`、`'>='`
 * 走双字符分支，token 流逐字节相同 —— 以下回归组逐个钉死。
 */
class MacroBodyAssignmentTest {

    private fun tokensOf(text: String, macros: Map<String, String>, ignoreCase: Boolean = true): List<Token> =
        ErbLexer(LexerConfig(macros = macros, macroIgnoreCase = ignoreCase)).tokenizeLine(text, 0)

    private fun operatorCodes(tokens: List<Token>): List<OperatorCode> =
        tokens.filterIsInstance<Token.Operator>().map { it.code }

    // ------------------------------------------------------------------
    // 最小复现 → 修复后行为
    // ------------------------------------------------------------------

    @Test
    fun macroBodyWithBareEqualExpandsToAssignmentTokens() {
        // 修复前这里抛「予期しない代入演算子」；修复后与原版一致：'=' 是既成 Assignment token
        val tokens = tokensOf("PRINT M", mapOf("M" to "A=1"))
        assertEquals("PRINT", (tokens[0] as Token.Identifier).code)
        assertEquals("A", (tokens[1] as Token.Identifier).code)
        assertEquals(OperatorCode.Assignment, (tokens[2] as Token.Operator).code)
        assertEquals(1L, (tokens[3] as Token.Integer).value)
        assertEquals(4, tokens.size)
    }

    @Test
    fun plainLineWithoutMacroStillRejectsBareEqualUnderNoneFlag() {
        // 改动只落在宏展开体：调用点 flag 为 NONE 的 analyse 对裸 '=' 依旧抛（不误放行）
        val e = assertFailsWith<LexError> {
            ErbLexer(LexerConfig())
                .analyse(CharStream("A=1"), LexEndWith.EoL, LexAnalyzeFlag.NONE, 0)
        }
        assertTrue(e.message!!.contains("予期しない代入演算子"), e.message!!)
    }

    // ------------------------------------------------------------------
    // 三类场景：普通表达式位 / 语句位 / 赋值左值位
    // ------------------------------------------------------------------

    @Test
    fun expressionPositionMacroBodyUnchangedWithoutEqual() {
        // 普通表达式位：无裸 '=' 的体在两种 flag 下 token 流逐字节相同（回归钉）
        val t1 = tokensOf("PRINT 天候", mapOf("天候" to "TIME:5"))
        assertEquals(listOf("PRINT", "TIME"), t1.filterIsInstance<Token.Identifier>().map { it.code })
        assertEquals(5L, t1.filterIsInstance<Token.Integer>().single().value)
        val t2 = tokensOf("E", mapOf("E" to "1+2*3"))
        assertEquals(listOf(OperatorCode.Plus, OperatorCode.Mult), operatorCodes(t2))
        assertEquals(listOf(1L, 2L, 3L), t2.filterIsInstance<Token.Integer>().map { it.value })
    }

    @Test
    fun statementPositionNestedAssignmentBodyExpandsIteratively() {
        // 语句位：宏套宏且内层体含裸 '=' —— 迭代展开与原版拼接结果一致
        val tokens = tokensOf("SET M", mapOf("SET" to "CMD", "M" to "X=Q"))
        assertEquals(listOf("CMD", "X", "Q"), tokens.filterIsInstance<Token.Identifier>().map { it.code })
        assertEquals(listOf(OperatorCode.Assignment), operatorCodes(tokens))
        assertEquals(4, tokens.size)
    }

    @Test
    fun assignmentLeftValuePositionNotAffected() {
        // 赋值左值位：宏名作左值（体不含 '='），行级 ALLOW_ASSIGNMENT 路径不回归
        val tokens = tokensOf("ALIAS = 5", mapOf("ALIAS" to "T"))
        assertEquals("T", (tokens[0] as Token.Identifier).code)
        assertEquals(OperatorCode.Assignment, (tokens[1] as Token.Operator).code)
        assertEquals(5L, (tokens[2] as Token.Integer).value)
    }

    @Test
    fun comparisonOperatorsInBodyStayNonAssignment() {
        // '=' 相关的其余形态一律不误收成 Assignment（双字符分支不受 flag 影响）
        assertEquals(listOf(OperatorCode.Equal), operatorCodes(tokensOf("M", mapOf("M" to "A==1"))))
        assertEquals(listOf(OperatorCode.NotEqual), operatorCodes(tokensOf("M", mapOf("M" to "A!=1"))))
        assertEquals(listOf(OperatorCode.LessEqual), operatorCodes(tokensOf("M", mapOf("M" to "A<=1"))))
        assertEquals(listOf(OperatorCode.GreaterEqual), operatorCodes(tokensOf("M", mapOf("M" to "A>=1"))))
        // 体尾裸 '='（原版声明时同样收成 Assignment，不抛）
        assertEquals(listOf(OperatorCode.Assignment), operatorCodes(tokensOf("M", mapOf("M" to "A="))))
    }

    // ------------------------------------------------------------------
    // 宏名大小写口径（macroIgnoreCase）
    // ------------------------------------------------------------------

    @Test
    fun ignoreCaseLookupExpandsLowercaseUseOfAssignmentBody() {
        val tokens = tokensOf("m", mapOf("M" to "A=1"), ignoreCase = true)
        assertEquals("A", (tokens[0] as Token.Identifier).code)
        assertEquals(OperatorCode.Assignment, (tokens[1] as Token.Operator).code)
        assertEquals(1L, (tokens[2] as Token.Integer).value)
    }

    @Test
    fun caseSensitiveLookupLeavesLowercaseUnexpanded() {
        // 大小写敏感时小写使用不展开 ⇒ 不触发展开体词法化，也不得抛
        val tokens = tokensOf("m", mapOf("M" to "A=1"), ignoreCase = false)
        assertEquals(1, tokens.size)
        assertEquals("m", (tokens[0] as Token.Identifier).code)
    }

    // ------------------------------------------------------------------
    // 循环上限口径不变
    // ------------------------------------------------------------------

    @Test
    fun assignmentBodyCycleStillHitsExpansionLimit() {
        val e = assertFailsWith<LexError> {
            tokensOf("X", mapOf("X" to "X=1"))
        }
        // Lang.Error.MacroOverLimit（Lang.cs:3554），实参 = 100（不带单位）；旧串
        // 「マクロの展開回数が100回を超えました（循環 ##DEFINE?）: <ident>」是自造文案，
        // 2026-10-26 深审波按 LexicalAnalyzer.cs:954-963 改钉，本用例随之改钉。
        assertTrue(e.message!!.contains("マクロの展開数が1文あたりの上限値100を超えました"), e.message!!)
    }
}
