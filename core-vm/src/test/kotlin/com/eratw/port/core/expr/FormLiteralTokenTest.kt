package com.eratw.port.core.expr

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **FORM 串里的 `===` 与 `{}`：字面 vs 形式令牌的判定（A2 / 合同 §6，#10）**
 *
 * 症状登记（A14）：`===` 与 `{}` 被当成形式令牌吞掉，导致 PC 基准的分节段头对不上。
 * 真正的判据是**通道选择**——原版只有 FORM 通道做三连记号/花括号展开，
 * 字面通道（`STR_NULLABLE`）直接取原文子串：
 *
 * - `PRINT`/`PRINTL` 家族（后缀无 FORM）→ `STR_ArgumentBuilder` 取 `charStream.Substring()`
 *   包成 `SingleStrTerm`，**不做任何 FORM 分析**（spec: ArgumentParser.cs:446-476，末行
 *   `return new ExpressionArgument(new SingleStrTerm(text))`）；`{`/`%`/`===` 在那里只是普通字符。
 * - `PRINTFORM*` 家族 → `FORM_STR_ArgumentBuilder` → `AnalyseFormattedString`
 *   （spec: ArgumentParser.cs:2604-2677 注册表 + LexicalAnalyzer.cs:1073-1229）：
 *   `{}` 只收整型（StrForm.cs:310-315）、`%%` 只收字符串（:330-335）、
 *   三连记号换成 NAME/CALLNAME 变量项（:216-238），且**受 `SystemIgnoreTripleSymbol` 门控**
 *   （LexicalAnalyzer.cs:1138 `if (!Config.SystemIgnoreTripleSymbol && st.TripleSymbol())`）。
 *
 * 本作 `emuera.config:63` = `DO NOT PROCESS TRIPLE SYMBOLS INSIDE FORM:NO`
 * ⇒ `SystemIgnoreTripleSymbol=false` ⇒ 本游戏 FORM 内三连展开**开启**（即 LexerConfig 默认值）。
 *
 * GOLDEN_12 §8.2 的段头行恰是字面通道：`GOLDEN_12_SYMBOLS.ERB:27`
 * `PRINTL === SECTION:DUP-METHOD ===` → 逐字节输出 `=== SECTION:DUP-METHOD ===`
 * （fixtures/golden/baseline/pc-golden-root.txt:80 起）。本文件把两侧判据一起钉住。
 */
class FormLiteralTokenTest {

    private fun parser(lexerConfig: LexerConfig = LexerConfig()): LineParser =
        LineParser(SymbolTable(), lexerConfig = lexerConfig)

    private fun stmt(text: String, lexerConfig: LexerConfig = LexerConfig()): PrintStmt =
        assertIs<PrintStmt>(
            parser(lexerConfig).parseLogicalLine(LogicalLine(LineKind.CODE, "A2_LITERAL.ERB", 1, text)),
        )

    private fun argOf(text: String, lexerConfig: LexerConfig = LexerConfig()): ExpressionArgument =
        assertIs<ExpressionArgument>(stmt(text, lexerConfig).args)

    private fun constOf(text: String): String? = argOf(text).constStr

    /**
     * FORM 通道里**含控制段**的用例用它（`===`/`{}`/`%%`/`@@`）。
     *
     * 纯文本 FORM 不产生 [FormStringTerm]：`StrForm.IsConst`（`strs.Length == 1`，
     * spec: StrForm.cs:118）⇒ `ToStrFormTerm` 返回 `SingleStrTerm`
     * （spec: ExpressionParser.cs:321-327）⇒ `Argument.ConstStr/IsConst` 有值
     * （spec: ArgumentParser.cs:504-511）。那种用例断言 [ExpressionArgument.constStr]。
     */
    private fun form(text: String, lexerConfig: LexerConfig = LexerConfig()): FormStringTerm =
        assertIs<FormStringTerm>(argOf(text, lexerConfig).term)

    private fun embedded(f: FormStringTerm): List<FormPart.Embedded> =
        f.parts.filterIsInstance<FormPart.Embedded>()

    private fun texts(f: FormStringTerm): List<String> =
        f.parts.filterIsInstance<FormPart.Text>().map { it.str }

    // -----------------------------------------------------------------------
    // 1. 字面通道（STR_NULLABLE）：不展开，逐字节保留原文
    // -----------------------------------------------------------------------

    @Test
    fun `字面通道_PRINTL的段头三连记号原样保留`() {
        // GOLDEN_12_SYMBOLS.ERB:27-32 的原句；§8.2 要求逐字节一致
        assertEquals("=== SECTION:DUP-METHOD ===", constOf("PRINTL === SECTION:DUP-METHOD ==="))
        assertEquals("=== SECTION:DUP-PLAIN ===", constOf("PRINTL === SECTION:DUP-PLAIN ==="))
        assertEquals("=== SECTION:EVENT-BUCKET ===", constOf("PRINTL === SECTION:EVENT-BUCKET ==="))
        // PRINT / PRINTW / PRINTC 同属 STR_NULLABLE（FunctionIdentifier.cs:85-131 的 else 分支）
        assertEquals("*** 名字 +++", constOf("PRINT *** 名字 +++"))
        assertEquals("$$$ ///", constOf("PRINTW $$$ ///"))
    }

    @Test
    fun `字面通道_花括号与百分号只是普通字符`() {
        // ArgumentParser.cs:461 `text = charStream.Substring()` —— 原文子串，无插值上下文
        assertEquals("花括号{1+1}原样", constOf("PRINTL 花括号{1+1}原样"))
        assertEquals("空括号{}", constOf("PRINTL 空括号{}"))
        assertEquals("100%%", constOf("PRINTL 100%%"))
        assertEquals("\\@ CONDT?A#B \\@", constOf("PRINTL \\@ CONDT?A#B \\@"))
    }

    @Test
    fun `字面通道不做FORM分析_故不报插值错误`() {
        // 同一个串走 FORM 通道必报的三种形态，字面通道一律合法（不抛即通过）
        for (src in listOf("PRINTL {}", "PRINTL %%", "PRINTL A={MONEYLABEL}", "PRINTL A=%1+1%")) {
            val a = argOf(src)
            assertTrue(a.constStr.isNotEmpty(), "$src 应为原文常量字面，实际 constStr=${a.constStr}")
        }
    }

    // -----------------------------------------------------------------------
    // 2. FORM 通道（FORM_STR_NULLABLE）：三连记号展开为 NAME/CALLNAME 变量项
    // -----------------------------------------------------------------------

    @Test
    fun `FORM通道_等号三连展开为CALLNAME_PLAYER`() {
        val f = form("PRINTFORML === SECTION:DUP-METHOD ===")
        val parts = embedded(f)
        assertEquals(2, parts.size, "两个 === 应各成一段内嵌，实际=${f.parts}")
        for (p in parts) {
            assertIs<FormConversion.Percent>(p.conversion, "三连记号的转换即 formatPercent")
            val ref = assertIs<VarRef>(p.ast)
            assertEquals("CALLNAME", ref.name.uppercase())
            assertIs<VarRef>(ref.indices.single()).let {
                assertEquals("PLAYER", it.name.uppercase())
                assertEquals(OperandType.INT, it.operandType)
            }
        }
        // 段头文本其余部分原样保留（strs 段），顺序 = Text,Embedded,Text,Embedded,Text
        assertEquals(listOf("", " SECTION:DUP-METHOD ", ""), texts(f))
    }

    @Test
    fun `FORM通道_五种三连记号各映射到对应系统变量`() {
        // spec: StrForm.cs:216-238 分派 + 129-203 Initialize 构建的 FunctionMethodTerm
        val cases = listOf(
            "***" to ("NAME" to "TARGET"),
            "+++" to ("CALLNAME" to "MASTER"),
            "===" to ("CALLNAME" to "PLAYER"),
            "///" to ("NAME" to "ASSI"),
            "$$$" to ("CALLNAME" to "TARGET"),
        )
        for ((sym, expect) in cases) {
            val parts = embedded(form("PRINTFORML $sym"))
            assertEquals(1, parts.size, "$sym 应成 1 段内嵌")
            val ref = assertIs<VarRef>(parts[0].ast)
            assertEquals(expect.first, ref.name.uppercase(), "$sym 变量名")
            assertEquals(expect.second, assertIs<VarRef>(ref.indices.single()).name.uppercase(), "$sym 角色")
        }
    }

    @Test
    fun `FORM通道_非三连的比较符仍是字面`() {
        // 这句话里没有任何控制段（无 `{}`/`%%`/`===`/`@@`）⇒ StrForm.IsConst（StrForm.cs:118）
        // ⇒ ExpressionParser.ToStrFormTerm 走 SingleStrTerm 分支（:321-327）⇒ 常量串。
        // 钉的是「折叠判据只看有没有内嵌子词」，不是「FORM 通道一律 FormStringTerm」。
        val arg = argOf("PRINTFORML A == B")
        assertEquals("A == B", arg.constStr)
        assertEquals("A == B", assertIs<StrLiteral>(arg.term).value)
    }

    // -----------------------------------------------------------------------
    // 3. FORM 通道：{} / %% 的整型/字符串契约（与 PcFormInterpolationTest 同口径）
    // -----------------------------------------------------------------------

    @Test
    fun `FORM通道_花括号是整型插值`() {
        val parts = embedded(form("PRINTFORML A={1+1}"))
        assertEquals(1, parts.size)
        assertIs<FormConversion.Curly>(parts[0].conversion)
    }

    @Test
    fun `FORM通道_空花括号与空百分号被拒`() {
        assertFailsWith<Throwable>("{} 必须被拒") { form("PRINTFORML A={}") }
        assertFailsWith<Throwable>("%% 必须被拒") { form("PRINTFORML A=%%") }
        // 花括号内是字符串 → 拒（StrForm.cs:310-315）
        assertFailsWith<Throwable>("{MONEYLABEL} 必须被拒") { form("PRINTFORML A={MONEYLABEL}") }
    }

    // -----------------------------------------------------------------------
    // 4. SystemIgnoreTripleSymbol 门控：FORM 内三连记号退化为字面
    // spec: LexicalAnalyzer.cs:1138 `if (!Config.SystemIgnoreTripleSymbol && st.TripleSymbol())`
    // 本游戏 emuera.config:63 = NO（=false，展开开启），但开关本身必须双向可用。
    // -----------------------------------------------------------------------

    @Test
    fun `三连符号开关_关闭时FORM内等号三连仍是字面`() {
        val off = LexerConfig(systemIgnoreTripleSymbol = true)
        // 门关 ⇒ 整句是纯文本 ⇒ IsConst（StrForm.cs:118）⇒ SingleStrTerm
        //（ExpressionParser.cs:321-327）；断言常量串，而不是 FormStringTerm。
        val arg = argOf("PRINTFORML === SECTION:DUP-METHOD ===", off)
        assertEquals("=== SECTION:DUP-METHOD ===", arg.constStr)
        assertEquals("=== SECTION:DUP-METHOD ===", assertIs<StrLiteral>(arg.term).value)
    }

    @Test
    fun `三连符号开关_词法层与语句层同口径`() {
        // 两条实现路径：ErbLexer.analyseFormattedString（词法语料装载用）与
        // core.stmt.analyseFormToEol（语句层 FORM_STR 到行尾），必须服从同一个开关，
        // 否则同一句在装载期与语句层会得到不同的 StrForm。
        for (ignore in listOf(false, true)) {
            val cfg = LexerConfig(systemIgnoreTripleSymbol = ignore)
            val lex = ErbLexer(cfg).analyseFormattedString(
                CharStream("***"), FormStrEndWith.DoubleQuotation, false, 1,
            )
            assertEquals(
                if (ignore) 0 else 1, lex.subWords.size,
                "analyseFormattedString(ignore=$ignore) 内嵌段数",
            )
            val arg = argOf("PRINTFORML ***", cfg)
            // 门开 ⇒ 有内嵌段 ⇒ FormStringTerm；门关 ⇒ 纯文本 ⇒ IsConst ⇒ SingleStrTerm
            //（ExpressionParser.cs:321-327）。两种形态都必须与词法层的子词数同口径。
            val statementParts = if (ignore) {
                assertEquals("***", arg.constStr, "门关时语句层必须是常量串")
                0
            } else {
                embedded(assertIs<FormStringTerm>(arg.term)).size
            }
            assertEquals(
                if (ignore) 0 else 1, statementParts,
                "语句层 FORM 通道(ignore=$ignore) 内嵌段数（应与词法层一致）",
            )
        }
    }
}
