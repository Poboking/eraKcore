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
 * 而 `100px` 粘连形在**纯词法层**根本产生不了 px 词：ReadInt64 把数字后的 `p` 当二进制
 * 指数、指数位不是数字时直接抛错（spec: LexicalAnalyzer.cs:66-73、:216-221）。
 * 移植侧既有图形语句用例与自产快照全是粘连形 ⇒ `ArgBuilders.spaceOutPxUnit` 在引号外先拆成
 * `100 px` 再交给词法层（保持既有读数；原版是否会放行粘连形无 PC 样本可证，见下方用例）。
 * 旧实现按原文剥末两字符的另一处偏离（把 `100 px + 1` 静默当单位）已按原版收掉。
 *
 * 语料 PRINT_IMG / PRINT_RECT / PRINT_SPACE 实测 0 处 ⇒ 本次接线对语料字节中性。
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
    fun `粘连写法的 px 与空格式读数相同`() {
        // 原版词法上 px 必须自成一词，`20px` 粘连形进不了 px 关键字位（ReadInt64 把 `p`
        // 当二进制指数，指数位不是数字即抛，spec: LexicalAnalyzer.cs:216-221）。移植侧既有
        // 图形语句测试与自产快照用的全是粘连形 ⇒ ArgBuilders 在**引号外**先拆成 `20 px`
        // 再交给词法层（spaceOutPxUnit），于是两式同解；不改这条既有读数属保守口径，
        // TODO(verify)：原版对 `20px` 报错还是接受，需 PC 侧 oracle 实跑（语料 0 处，无样本）。
        val glued = assertIs<SpPrintShapeArgument>(parse("PRINT_RECT 10, 10, 20px, 20").args)
        val spaced = assertIs<SpPrintShapeArgument>(parse("PRINT_RECT 10, 10, 20 px, 20").args)
        assertEquals(listOf(false, false, true, false), glued.param.map { it.isPx })
        assertEquals(spaced.param.map { it.isPx }, glued.param.map { it.isPx }, "粘连形与空格式逐位同解")
    }

    @Test
    fun `拆词只认数字尾巴的 px，不吃文件后缀与标识符`() {
        // `a.webp` 在引号内（p 后不是 x 也不是单位位）、`50` 无单位 —— 都不该被动到
        val arg = assertIs<SpPrintImgArgument>(parse("PRINT_IMG \"a.webp\", 10, 10, 20px").args)
        assertNotNull(arg.name, "第 1 参（含 .webp 的串）照常交出")
        val params = assertNotNull(arg.param)
        assertEquals(listOf(false, false, true), params.map { it.isPx })
        val space = assertIs<SpPrintShapeArgument>(parse("PRINT_SPACE 50").args)
        assertFalse(space.param[0].isPx, "没有 px 时一位都不置")
    }
}
