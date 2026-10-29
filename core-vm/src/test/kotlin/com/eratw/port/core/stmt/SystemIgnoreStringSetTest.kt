package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.ParserConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `Config.SystemIgnoreStringSet` 的两态分叉（SAVE-09b）。
 *
 * 语义唯一消费点：字符串变量用 `=` 赋值的语句（`ArgumentParser.cs:1025-1029`）。
 * 打开时该语句判 level 2 错误（`line.IsError = true`，`ArgumentBuilder.cs:21-26`），
 * 移植侧按语句层纪律抛 [StmtParseException]（`ArgBuilders` 类 KDoc）；
 * 关闭时（出厂默认 `ConfigData.cs:113`，语料 `emuera.config:66` = NO）走 FORM 原文解析。
 *
 * 不受影响的三支：`'=`（`OperatorCode.AssignmentStr`）、复合赋值、整数变量赋值 ——
 * 原版那个 `switch (assignOperator)` 的其它 case 都不查这个开关。
 */
class SystemIgnoreStringSetTest {

    private fun parser(ignoreStringSet: Boolean): LineParser = LineParser(
        SymbolTable()
            .declareVariable("S", OperandType.STR, 1)
            .declareVariable("T", OperandType.STR, 0)
            .declareVariable("A", OperandType.INT, 1),
        ParserConfig(systemIgnoreStringSet = ignoreStringSet),
    )

    private fun parse(text: String, ignoreStringSet: Boolean): Statement? =
        parser(ignoreStringSet).parseLogicalLine(LogicalLine(LineKind.CODE, "TEST.ERB", 10, text))

    @Test
    fun `关：字符串变量的等号赋值按 FORM 原文解析`() {
        // spec: ArgumentParser.cs:1031-1041 —— 关的时候右值是 AnalyseFormattedString 的原文，
        // 引号/括号/等号都是字面量（这正是 `'=` 存在的原因，见 LineParserTest 同款断言）。
        val s = assertIs<AssignStmt>(parse("T = \"a=b(c)\"", ignoreStringSet = false))
        assertEquals(OperandType.STR, s.dest.operandType)
        assertEquals("\"a=b(c)\"", (s.args as SpSetArgument).constStr)
    }

    @Test
    fun `开：字符串变量的等号赋值判 level 2 错误`() {
        // spec: ArgumentParser.cs:1026-1029 + Lang.Error.StrAssignIsPrihibited（Lang.cs:2062）。
        val e = assertFailsWith<StmtParseException> { parse("T = \"abc\"", ignoreStringSet = true) }
        assertTrue(e.message!!.contains("直接赋值字符串被禁用"), e.message)
    }

    @Test
    fun `开：单引号赋值不受影响`() {
        // `'=` 走 OperatorCode.AssignmentStr 分支（ArgumentParser.cs:1043 起），原版不查开关。
        val s = assertIs<AssignStmt>(parse("S:0 '= \"abc\"", ignoreStringSet = true))
        assertEquals("abc", (s.args as SpSetArgument).constStr)
    }

    @Test
    fun `开：复合赋值不受影响`() {
        val s = assertIs<AssignStmt>(parse("T += \"x\"", ignoreStringSet = true))
        assertEquals(com.eratw.port.core.expr.OperatorCode.PLUS, s.op)
    }

    @Test
    fun `开：整数变量赋值不受影响`() {
        // 整数分支在 ArgumentParser.cs:917-1016，开关只在字符串分支里。
        val s = assertIs<AssignStmt>(parse("A = 1", ignoreStringSet = true))
        assertTrue((s.args as SpSetArgument).isConst)
        assertEquals(1L, (s.args as SpSetArgument).constInt)
    }
}
