package com.eratw.port.core.stmt

import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRINT 族参数原文的前导空白保真 —— print-align 波首差钉子。
 *
 * 原版语义：ParseLine 在语句名后只 ShiftNext **1 个**分隔字符
 * （spec: LogicalLineParser.cs:513-522），STR 构造器对余流直接 `Substring()`
 * （spec: ArgumentParser.cs:446-462），FORM 构造器 `AnalyseFormattedString(trim:false)`
 * 不裁文本段（spec: ArgumentParser.cs:504 + LexicalAnalyzer.cs:1223-1227）。
 * 因此 `PRINTL   x`（两个前导空格）输出 "  x"。
 * 旧实现 rawText.trimStart(' ','\t') 吞掉全部前导空白，破坏 ERA 脚本
 * 靠 PRINT/DATA 前导空格做的屏上对齐 —— 本测试组锁死该首差。
 */
class PrintRawArgTextTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("T", OperandType.STR, 0)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 10): Statement =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", lineNo, text))!!

    // -----------------------------------------------------------------------
    // STR_NULLABLE：PRINT / PRINTL / PRINTW …（DATA 同构造器，spec: FunctionIdentifier.cs:4017）
    // -----------------------------------------------------------------------

    @Test
    fun `PRINTL 连续前导空格保留`() {
        // 语句名后第 1 个空格是分隔符，其余 2 个是正文（spec: LogicalLineParser.cs:517-521）
        val s = assertIs<PrintStmt>(parse("PRINTL   x"))
        assertEquals("  x", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `PRINT 分隔符之后的制表符保留`() {
        // 第 1 个分隔符是 ' '，其后的 '\t' 属于正文（原版只 ShiftNext 一次）
        val s = assertIs<PrintStmt>(parse("PRINTL \tx"))
        assertEquals("\tx", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `PRINTL 制表符作分隔符`() {
        val s = assertIs<PrintStmt>(parse("PRINTL\tx"))
        assertEquals("x", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `PRINTL 全角空格按正文保留`() {
        // 半角空格作分隔符后，全角空格是正文（分隔字符集合 ';' ' ' '\t'，
        // spec: LogicalLineParser.cs:517-521）
        val s = assertIs<PrintStmt>(parse("PRINTL 　x"))
        assertEquals("　x", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `DATA 前导空格保留`() {
        val s = assertIs<DataStmt>(parse("DATA   x"))
        assertEquals("  x", (s.args as ExpressionArgument).constStr)
    }

    // -----------------------------------------------------------------------
    // FORM_STR_NULLABLE：PRINTFORM / PRINTFORML / PRINTFORMW …（DATAFORM 同构造器）
    // -----------------------------------------------------------------------

    @Test
    fun `PRINTFORM 前导空白进入文本段`() {
        val s = assertIs<PrintStmt>(parse("PRINTFORM \t  x"))
        // trim:false（spec: ArgumentParser.cs:504）：整段 "\t  x" 都是字面文本
        assertEquals("\t  x", (s.args as ExpressionArgument).constStr)
    }

    @Test
    fun `PRINTFORML 插值前的前导空格保留`() {
        val s = assertIs<PrintStmt>(parse("PRINTFORML   {A}"))
        val term = (s.args as ExpressionArgument).term as FormStringTerm
        assertEquals("  ", (term.parts.first() as FormPart.Text).str)
    }

    @Test
    fun `DATAFORM 前导空格保留`() {
        val s = assertIs<DataStmt>(parse("DATAFORM   x"))
        // 纯文本 FORM 没有控制段 ⇒ StrForm.IsConst（`strs.Length == 1`，spec: StrForm.cs:118）
        // ⇒ ExpressionParser.ToStrFormTerm 返回 SingleStrTerm（:321-327）⇒ Argument 记
        // ConstStr/IsConst（spec: ArgumentParser.cs:504-511）。所以这里读到的是常量串而非
        // FormStringTerm 的 Text 段；前导空格照样完整保留（trim:false，:504）。
        val arg = s.args as ExpressionArgument
        assertEquals("  x", arg.constStr)
        assertEquals("  x", assertIs<StrLiteral>(arg.term).value)
    }

    // -----------------------------------------------------------------------
    // VOID：余空判定走 SkipWhiteSpace（spec: ArgumentParser.cs:423-435）
    // -----------------------------------------------------------------------

    @Test
    fun `VOID 行尾注释不告警`() {
        // SkipWhiteSpace 遇 ';' 吞到行尾（spec: LexicalAnalyzer.cs:734-752）
        val p = parser()
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 1, "RESETCOLOR ; 注释"))
        assertEquals(emptyList(), p.warnings)
    }

    @Test
    fun `VOID 全角空格不告警`() {
        // SkipWhiteSpace 的空白集含全角空格（spec: LexicalAnalyzer.cs:724-727 + :734-752）
        val p = parser()
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 1, "RESETCOLOR 　"))
        assertEquals(emptyList(), p.warnings)
    }

    @Test
    fun `VOID 多余参数告警`() {
        val p = parser()
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 1, "RESETCOLOR x"))
        assertTrue(p.warnings.any { it.message.contains("不需要参数") })
    }

    // -----------------------------------------------------------------------
    // FORM_STR：RETURNFORM —— 原版不做任何空白跳过（spec: ArgumentParser.cs:479-514）
    // -----------------------------------------------------------------------

    @Test
    fun `RETURNFORM 前导空格按文本保留`() {
        // FORM_STR 构造器直接对余流 Substring / AnalyseFormattedString(trim:false)，
        // 无 SkipHalfSpace：分隔符后的 ' ' 也是正文
        val s = assertIs<ReturnFStmt>(parse("RETURNFORM  \tx"))
        assertEquals(" \tx", (s.args as ExpressionArgument).constStr)
    }

    // -----------------------------------------------------------------------
    // FORM_STR_ANY：SETBGIMAGE / REMOVEBGIMAGE（spec: FunctionIdentifier.cs:1523/1549）
    // 只跳半角空格（spec: ArgumentParser.cs:387 + LexicalAnalyzer.cs:692-701）
    // -----------------------------------------------------------------------

    @Test
    fun `SETBGIMAGE 制表符按文本保留`() {
        // SkipHalfSpace 只吃 ' '：分隔符后的半角空格被跳过，'\t' 起的正文原样进 FORM
        val s = assertIs<InstructionStmt>(parse("SETBGIMAGE  \tx"))
        val term = (s.args as ExpressionArrayArgument).terms.single() as FormStringTerm
        assertEquals("\tx", (term.parts.single() as FormPart.Text).str)
    }

    @Test
    fun `SETBGIMAGE 逗号加尾随空格告警`() {
        // 逗号后半角空格被 SkipHalfSpace 吃尽 → MissingArgAfterComma level 1
        // （spec: ArgumentParser.cs:412-417；L11 渲染层落地后文案取官方 zhs「,'后面必须存在一个参数」）
        val p = parser()
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 1, "SETBGIMAGE A, "))
        assertTrue(
            p.warnings.any { it.message.contains("后面必须存在一个参数") },
            "实际告警：${p.warnings.map { it.message }}",
        )
    }

    // -----------------------------------------------------------------------
    // SP_INPUTS：原版不做任何空白跳过（spec: ArgumentParser.cs:1141-1147）
    // -----------------------------------------------------------------------

    @Test
    fun `INPUTS 前导空格保留进默认串`() {
        val s = assertIs<InstructionStmt>(parse("INPUTS   x"))
        val arg = s.args as SpInputsArgument
        val def = assertIs<FormStringTerm>(arg.def)
        assertEquals("  x", (def.parts.single() as FormPart.Text).str)
    }
}
