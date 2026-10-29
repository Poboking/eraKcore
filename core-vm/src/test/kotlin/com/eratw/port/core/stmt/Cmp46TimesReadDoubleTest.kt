package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CMP-46 必红夹具 —— `TIMES` 第二参必须按**数字前缀**读，不许把整段原文喂 `toDoubleOrNull`。
 *
 * 原版 SP_TIMES 在逗号之后走
 * `SkipWhiteSpace` → `LexicalAnalyzer.ReadDouble`（只吃「正负号 + 数字/'.' + 可选 e/E 指数」）
 * → `SkipWhiteSpace` → 余字非空才 level-1 `TooManyArg`
 * （spec: ArgumentParser.cs:332-355；ReadDouble 本体 spec: LexicalAnalyzer.cs:232-266）。
 * 因为 `SkipWhiteSpace` 把 `;` 当行尾注释吞掉（spec: LexicalAnalyzer.cs:734-752），
 * `TIMES HAITOU, 1.5 ;翻倍` 在原版读到的就是 **1.5**、且**不告警**。
 *
 * 移植侧旧实现取「逗号后到行尾」整段 `.trim().toDoubleOrNull()`（ArgBuilders.kt:387-391），
 * 注释一进来就整条判失败 → 告警 + **按 0.0 结算**（钱/经验清零级后果）。
 */
class Cmp46TimesReadDoubleTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("HAITOU", OperandType.INT, 0)
            .declareVariable("EXP", OperandType.INT, 0)
        return LineParser(sym)
    }

    private fun parse(p: LineParser, text: String, lineNo: Int = 7): Statement? =
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "CMP46_TIMES.ERB", lineNo, text))

    private fun times(p: LineParser, text: String): SpTimesArgument {
        val stmt = assertIs<InstructionStmt>(parse(p, text))
        assertEquals(FunctionCode.TIMES, stmt.code)
        return assertIs<SpTimesArgument>(stmt.args)
    }

    // -----------------------------------------------------------------------
    // 红形：行内注释
    // -----------------------------------------------------------------------

    @Test
    fun `TIMES 数字后有行内注释仍按数字前缀结算`() {
        // 原版：ReadDouble 读 "1.5"，SkipWhiteSpace 吞掉 ";翻倍" 到 EOS，无告警。
        val p = parser()
        val arg = times(p, "TIMES HAITOU, 1.5 ;翻倍")
        assertEquals("HAITOU", arg.dest.name)
        assertEquals(1.5, arg.doubleValue, "行内注释不得把倍率清零（ArgumentParser.cs:340-355）")
        assertFalse(
            p.warnings.any { it.message.contains("不是实数") },
            "SkipWhiteSpace 吃掉的是注释，不该报「不是实数」：${p.warnings}",
        )
    }

    @Test
    fun `TIMES 行内注释带前导空白同样不告警`() {
        val p = parser()
        val arg = times(p, "TIMES EXP,   2.25   ; 注释（全角括号）")
        assertEquals(2.25, arg.doubleValue)
        assertEquals(emptyList(), p.warnings, "注释之后已到行尾，原版无任何告警")
    }

    // -----------------------------------------------------------------------
    // 基线（改前也绿）：纯数字形态
    // -----------------------------------------------------------------------

    @Test
    fun `TIMES 纯整数与小数形态`() {
        assertEquals(2.0, times(parser(), "TIMES HAITOU, 2").doubleValue)
        assertEquals(0.5, times(parser(), "TIMES HAITOU, 0.5").doubleValue)
        assertEquals(-1.25, times(parser(), "TIMES HAITOU, -1.25").doubleValue)
        // ReadDouble 的指数段：spec: LexicalAnalyzer.cs:248-264
        assertEquals(1000.0, times(parser(), "TIMES HAITOU, 1e3").doubleValue)
        assertEquals(0.5, times(parser(), "TIMES HAITOU, .5").doubleValue)
    }

    // -----------------------------------------------------------------------
    // ReadDouble 的边界：数字前缀之后仍有非空白字符 ⇒ 值保留 + level-1「参数过多」
    // -----------------------------------------------------------------------

    @Test
    fun `TIMES 数字前缀后接杂字保留数值并只告警参数过多`() {
        // 原版：ReadDouble 读 "0.5"，余下 "x" 非空白 ⇒ TooManyArg（level 1），值仍是 0.5。
        val p = parser()
        val arg = times(p, "TIMES HAITOU, 0.5x")
        assertEquals(0.5, arg.doubleValue)
        assertFalse(
            p.warnings.any { it.message.contains("不是实数") },
            "非空余字不是「不是实数」，原版是 TooManyArg：${p.warnings}",
        )
        assertTrue(
            p.warnings.any { it.message.contains("参数过多") },
            "数字后余字必须留 level-1 告警：${p.warnings}",
        )
    }

    // 注：`ArgType.STR_DOUBLE`（Argument.kt:425-428）在本仓**没有任何语句登记**
    // （`FunctionCode` 无一条用该 ArgType）⇒ 同族的 `strDouble()` 是死码，不在本夹具范围。
}
