package com.eratw.port.core.stmt

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertIs

/**
 * 稳定性审查波 L8：`VARI` 初值的词法错误必须收敛到语句层报错契约。
 *
 * `LineParser` 的其余词法通道（行首标识符 / 自增行 / 赋值左值 / 标签行 / `#` 行）都把
 * `LexError` 包成 [StmtParseException]（带行号）。`intExpr` 此前把
 * `lexer.analyse(...)` 放在 try 之外，`VARI A = "未闭合` 这类输入会把裸 `LexError`
 * 漏给调用方 —— 行号信息丢失，且违反 `parseLogicalLine` 的「解析失败抛
 * [StmtParseException]」契约。修复只挪动 try 边界并保留原始文案，不改变任何合法行的解析结果。
 */
class StabilityL8ParseErrorTypeTest {

    private fun parse(text: String, lineNo: Int = 3): Statement? = LineParser(SymbolTable())
        .parseLogicalLine(LogicalLine(LineKind.CODE, "STABILITY_L8.ERB", lineNo, text))

    @Test
    fun `VARI 初值未闭合字符串收敛为带行号的 StmtParseException`() {
        val e = assertThrows<StmtParseException> { parse("VARI A = \"abc") }
        assertEquals(3, e.line)
        assertTrue(e.message!!.contains("閉じられていません"), e.message)
    }

    @Test
    fun `VARI 初值合法整数仍正常解析`() {
        val decl = assertIs<VarDeclStmt>(parse("VARI A = 42"))
        val init = assertIs<IntAsignArgument>(decl.args).exp
        assertEquals(42L, (init as IntLiteral).value)
    }
}
