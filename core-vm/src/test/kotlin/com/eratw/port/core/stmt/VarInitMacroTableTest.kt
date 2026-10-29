package com.eratw.port.core.stmt

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CMP-35：`VARI`/`VARS` 初值与同一行其它词法通道共用 [LexerConfig]（含 `#DEFINE` 宏表）。
 *
 * 原版初值走 `LogicalLineParser.cs:461` 的 `ReduceExpressionTerm`，其 token 流来自整行
 * `Analyse(... AllowAssignment)` —— `#DEFINE` 展开在词法层已经发生（`LexicalAnalyzer.cs:729-732`、
 * `:942-1006`）。旧实现 `LineParser.intExpr` 走 `ParseSupport.sharedLexer = ErbLexer()`（无宏、
 * 无配置），初值里的宏名会被当变量解析；本用例钉死修复后的同源口径。
 */
class VarInitMacroTableTest {

    @Test
    fun `VARI 初值吃解析器的宏表`() {
        val parser = LineParser(
            lexerConfig = LexerConfig(macros = mapOf("FOO" to "42", "天候" to "TIME:5")),
        )
        val stmt = parser.parseLogicalLine(
            LogicalLine(LineKind.CODE, "test.erb", 7, "VARI A = FOO"),
        )
        val decl = stmt as VarDeclStmt
        val arg = decl.args as IntAsignArgument
        assertEquals("A", arg.name)
        assertEquals(42L, (arg.exp as IntLiteral).value)
    }

    @Test
    fun `VARI 初值里的宏体可以是表达式`() {
        val parser = LineParser(lexerConfig = LexerConfig(macros = mapOf("BASE" to "40")))
        val stmt = parser.parseLogicalLine(
            LogicalLine(LineKind.CODE, "test.erb", 8, "VARI B = BASE + 2"),
        )
        val arg = (stmt as VarDeclStmt).args as IntAsignArgument
        // 宏展开后是 `40 + 2`；常量折叠不在本层断言，只钉 token 流里 BASE 已被替换。
        val sum = arg.exp as Binary
        assertEquals(40L, (sum.left as IntLiteral).value)
        assertEquals(2L, (sum.right as IntLiteral).value)
    }
}
