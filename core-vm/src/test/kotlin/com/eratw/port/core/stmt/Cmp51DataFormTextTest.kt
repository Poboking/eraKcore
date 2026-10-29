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
 * CMP-51 必红夹具 —— `DATAFORM` 的 `DataStmt.text` 不许恒为空。
 *
 * ## 根因（C# 对照）
 * 原版 FORM_STR 构造器把 `AnalyseFormattedString(..., trim:false)` 交给
 * `ExpressionParser.ToStrFormTerm`（spec: ArgumentParser.cs:504），而
 * `ToStrFormTerm` 对 **const StrForm**（`StrForm.IsConst => strs.Length == 1`，
 * spec: StrForm.cs:118）**折成 `SingleStrTerm`**、非常量才 new `StrFormTerm`
 * （spec: ExpressionParser.cs:321-329）。随后构造器按 `aExpression is SingleTerm`
 * 写 `ConstStr`（spec: ArgumentParser.cs:507-511）。
 *
 * 移植侧 `formStr` 对纯文本 FORM 一律造 [FormStringTerm]（ArgBuilders.kt:198-218），
 * 而 `LineParser.rawTextOfArg` 只认 `arg.term.constStr()`（即 `StrLiteral`）
 * ⇒ 每条 `DATAFORM` 的 `text` 都是 ""，随 `ErbcWriter` 全量写进语句串池
 * （ErbcWriter.kt:1146 → ErbcReader.kt:1148）。`DATA`（STR_CONSTABLE → StrLiteral）不受影响。
 */
class Cmp51DataFormTextTest {

    private fun parser(): LineParser {
        val sym = SymbolTable().declareVariable("A", OperandType.INT, 0)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 11): Statement? =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "CMP51_DATAFORM.ERB", lineNo, text))

    // -----------------------------------------------------------------------
    // 红形：DATAFORM 纯文本
    // -----------------------------------------------------------------------

    @Test
    fun `DATAFORM 纯文本写进 DataStmt_text`() {
        val s = assertIs<DataStmt>(parse("DATAFORM   x"))
        assertEquals(FunctionCode.DATAFORM, s.code)
        assertEquals("  x", s.text, "DATAFORM 的 text 不得恒空（ArgumentParser.cs:504-511）")
    }

    @Test
    fun `DATAFORM 纯文本参数按 C# 折成 StrLiteral`() {
        // spec: ExpressionParser.cs:321-329 —— IsConst 的 StrForm → SingleStrTerm
        val s = assertIs<DataStmt>(parse("DATAFORM です"))
        val arg = assertIs<ExpressionArgument>(s.args)
        val term = assertIs<StrLiteral>(arg.term, "纯文本 FORM 必须折成 StrLiteral/SingleStrTerm")
        assertEquals("です", term.value)
        assertTrue(arg.isConst)
        assertEquals("です", arg.constStr)
    }

    @Test
    fun `PRINTFORM 纯文本同样折成 StrLiteral`() {
        // 同一个 FORM_STR 构造器（spec: ArgumentParser.cs:504-511）；FORM_STR_NULLABLE 家族。
        val s = assertIs<PrintStmt>(parse("PRINTFORML  abc"))
        val arg = assertIs<ExpressionArgument>(s.args)
        assertEquals(StrLiteral(" abc"), arg.term)
        assertTrue(arg.isConst)
    }

    // -----------------------------------------------------------------------
    // 基线：DATA 与带插值的 FORM（非常量，仍走 FormStringTerm）
    // -----------------------------------------------------------------------

    @Test
    fun `DATA 原文照旧进 text`() {
        val s = assertIs<DataStmt>(parse("DATA   x"))
        assertEquals("  x", s.text)
    }

    @Test
    fun `DATAFORM 带插值时保留 FormStringTerm`() {
        val s = assertIs<DataStmt>(parse("DATAFORM {A}です"))
        val arg = assertIs<ExpressionArgument>(s.args)
        val form = assertIs<FormStringTerm>(arg.term, "非常量 FORM 仍是 FormStringTerm（StrFormTerm）")
        assertEquals(1, form.parts.filterIsInstance<FormPart.Embedded>().size)
        // text 是**常量快照**：非常量表单没有编译期字符串，保持空串（原版 ExpressionArgument.ConstStr 同口径，
        // spec: ArgumentParser.cs:507-511 只在 SingleTerm 时写 ConstStr）。
        assertEquals("", s.text)
    }
}
