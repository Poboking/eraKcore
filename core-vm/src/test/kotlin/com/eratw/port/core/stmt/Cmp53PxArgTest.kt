package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CMP-53：`px` 是**词法级**尺寸单位标记，不是文本后缀。
 *
 * 原版口径（三处联动）：
 * 1. SP_PRINT_IMG / SP_PRINT_SHAPE 逐参数调 `ReduceExpressionTerm(wc, (TermEndWith)34)`
 *    —— 34 = Comma|KeyWordPx（spec: ArgumentParser.cs:57、:124）；
 * 2. `px` 只在「词面等于 px 且 wc.Next 是 `,` 或行尾」时终止表达式
 *    （spec: ExpressionParser.cs:534-537）；
 * 3. 终止后原版用「当前词不是行尾、也不是逗号」判 isPx 并跳过该词
 *    （spec: ArgumentParser.cs:77-90、:126-140）。
 *
 * 因此 `100 px` 合法（`px` 是独立 IdentifierWord，数字与 px 之间的空格由词法层吃掉），
 * 而 `100px` 粘连形**词法必拒**（B4，2026-11-06 翻案落定）：数字字面量由 `ReadInt64` 吃进
 * （LexicalAnalyzer.cs:765），`readDigits` 吃完数字后游标停在 `p`，:66-68 把它当 2 进制
 * 指数标记，ShiftNext 后指数位是 `x`，第二次 `readDigits` 得空串 → `long.Parse("")` 抛
 * FormatException → :149-152 转 CodeEE(CanNotConvertToInt)，上屏 `""を整数値に変換できません`。
 * 移植侧词法器同链条复刻（core/lexer/LexicalAnalyzer.kt readInt64/readDigits），曾有的
 * 「引号外拆成 `100 px`」放行近似（spaceOutPxUnit）已撤销 —— 粘连文本原样进词法层即同形拒绝。
 * 旧实现按原文剥末两字符的另一处偏离（把 `100 px + 1` 静默当单位）已按原版收掉。
 *
 * 语料 PRINT_IMG / PRINT_RECT / PRINT_SPACE 实测 0 处 ⇒ 本次翻案对语料字节中性。
 */
class Cmp53PxArgTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("LOCALS", OperandType.STR, 1)
        return LineParser(sym)
    }

    private fun parse(text: String): Statement =
        assertNotNull(parser().parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 7, text)))

    // -----------------------------------------------------------------------
    // PRINT_IMG：尺寸位 px
    // -----------------------------------------------------------------------

    @Test
    fun `PRINT_IMG 行尾 px 是单位`() {
        // 整数位最多 3 个（原版循环首 `if (list.Count == 3)` 就报参数过多，
        // spec: ArgumentParser.cs:46-50），故这里用 3 位形态。
        val arg = assertIs<SpPrintImgArgument>(parse("PRINT_IMG \"a.png\", 0, 0, 100 px").args)
        val params = assertNotNull(arg.param)
        assertEquals(3, params.size)
        assertFalse(params[0].isPx)
        assertFalse(params[1].isPx)
        assertTrue(params[2].isPx, "px 后随行尾同样终止表达式（wc.Next.Type 为行尾）")
    }

    @Test
    fun `PRINT_IMG 中间位 px 后随逗号`() {
        val arg = assertIs<SpPrintImgArgument>(parse("PRINT_IMG \"a.png\", 0, 20 px, 30").args)
        val params = assertNotNull(arg.param)
        assertEquals(3, params.size)
        assertFalse(params[0].isPx)
        assertTrue(params[1].isPx, "`20 px,` —— px 后随逗号，是单位（ExpressionParser.cs:534-537）")
        assertFalse(params[2].isPx, "末位 `30` 无 px")
    }

    @Test
    fun `PRINT_IMG 无 px 时一位不置`() {
        val arg = assertIs<SpPrintImgArgument>(parse("PRINT_IMG \"a.png\", 0, 0, 100").args)
        assertTrue(assertNotNull(arg.param).none { it.isPx })
    }

    // -----------------------------------------------------------------------
    // PRINT_RECT / PRINT_SPACE：同一口径
    // -----------------------------------------------------------------------

    @Test
    fun `PRINT_RECT 逐位认 px`() {
        val arg = assertIs<SpPrintShapeArgument>(parse("PRINT_RECT 10, 10, 20 px, 20 px").args)
        assertEquals(listOf(false, false, true, true), arg.param.map { it.isPx })
    }

    @Test
    fun `PRINT_SPACE 单参数认 px`() {
        val arg = assertIs<SpPrintShapeArgument>(parse("PRINT_SPACE 5 px").args)
        assertEquals(1, arg.param.size)
        assertTrue(arg.param[0].isPx)
    }

    // -----------------------------------------------------------------------
    // 两处旧偏差：现在都按原版拒绝
    // -----------------------------------------------------------------------

    @Test
    fun `px 后不是逗号也不是行尾时不作单位`() {
        // `20 px + 1`：px 的 Next 是运算符 ⇒ 不终止表达式 ⇒ px 按普通标识符归约，
        // 符号层查不到就报未定义（原版走 reduceIdentifier 同路）。
        assertFailsWith<Throwable> { parse("PRINT_RECT 10, 10, 20 px + 1, 20") }
    }

    @Test
    fun `粘连写法的 px 词法必拒`() {
        // 翻案依据（B4，原「与空格式同解」断言撤销）：数字字面量由 ReadInt64 吃进
        // （LexicalAnalyzer.cs:765），readDigits 吃完 `20` 后游标停在 p，:66-68 当 2 进制
        // 指数标记，ShiftNext 后指数位是 x，第二次 readDigits 得空串 → long.Parse("") 抛
        // FormatException → :149-152 转 CodeEE(CanNotConvertToInt)。移植侧词法器同链条
        // （core/lexer/LexicalAnalyzer.kt readInt64/readDigits），报同一句日文原文。
        val e = assertFailsWith<StmtParseException> { parse("PRINT_RECT 10, 10, 20px, 20") }
        // 内层词法错误文案 = CanNotConvertToInt 空串形（LexError 被 LineParser 包成 StmtParseException）
        assertTrue(e.message!!.contains("\"\"を整数値に変換できません"), e.message)
        // 大写粘连形同拒：ReadInt64 的指数判定对 p/P 都生效（LexicalAnalyzer.cs:66）
        assertFailsWith<StmtParseException> { parse("PRINT_SPACE 5PX") }
    }

    @Test
    fun `文件后缀的 webp 不受 px 词法影响`() {
        // `a.webp` 在引号内（串字面量不经 ReadInt64）、`20 px` 是空格分词的合法单位 ——
        // 词法层只对「数字后直接粘连 p/P」报错
        val arg = assertIs<SpPrintImgArgument>(parse("PRINT_IMG \"a.webp\", 10, 10, 20 px").args)
        assertNotNull(arg.name, "第 1 参（含 .webp 的串）照常交出")
        val params = assertNotNull(arg.param)
        assertEquals(listOf(false, false, true), params.map { it.isPx })
        val space = assertIs<SpPrintShapeArgument>(parse("PRINT_SPACE 50").args)
        assertFalse(space.param[0].isPx, "没有 px 时一位都不置")
    }
}
