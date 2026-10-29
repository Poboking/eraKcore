package com.eratw.port.core.stmt

import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **FORM 串插值的类型契约（A10 parser-oracle，类别 FORM_BRACE_NOT_INT / FORM_PERCENT_NOT_STR /
 * BRACE_OUTSIDE_FORM）**
 *
 * 组长待核第一条的裁定：FORM 串里 `{expr}` **只接受整型**，字符串插值走 `%expr%` —— 源码已证实：
 * - `StrForm.cs:310-315`：`subWord is CurlyBraceSubWord` 且 `aExpression.GetOperandType() != typeof(long)`
 *   → `throw new CodeEE(Lang.Error.IsNotNumericBrace.Text)`，即告警原文「{{}}中的表达式结果不是整数」
 *   （lang.emuera-zhs.xml:231）。该 CodeEE 被 `LogicalLineParser.cs:558-562` 兜成 InvalidLine
 *   → 装载中止（Process.cs:2124-2128）。
 * - `StrForm.cs:330-335`：`%…%`（PercentSubWord）要求 `typeof(string)`，否则 IsNotStringPer（:232）。
 * - `StrForm.cs:241-254`：`\@条件?左#右\@` 的条件走 `ReduceIntegerTerm`（必须是整数表达式）。
 * - `StrForm.cs:272-308`：`,宽度[,LEFT|RIGHT]` 的宽度为整数表达式，第三项只能是 LEFT/RIGHT。
 * - 类型是**装载期静态判定**，不是运行期"能转就转"：`StrForm.cs:45-60`（Curly→GetIntValue().ToString()）、
 *   `StrForm.cs:62-84`（Percent→GetStrValue()+全角宽度折算）只是格式化实现。
 *   我们的对应实现 = FormStringTerm.kt:209-219，本文件把这条契约钉住。
 *
 * 真实语料核对（`eratw-画蛇添足版eratw4.980/ERB`，统计排除 `GOLDEN_*`）：
 * - PRINTFORM* 行含 `%名字%` 的用法 **200226 次**；`{MONEYLABEL}` / `{WINDOW_TITLE}` / `{SUBSTRING(` **0 次**。
 * - 混用范式即本契约的正写，例：`PRINTFORMW 对%ENEMY_NAME%造成了{DAMAGE}点伤害！`
 *
 * 参数形态提醒（金标 11 条「意料之外的字符'{'」的根因）：PRINT*S / PRINTFORMS 的 ArgBuilder 是
 * **STR_EXPRESSION**（FunctionIdentifier.cs:101-111），不是 FORM_STR，故其中的 `{`/`%` 都落进普通词法，
 * 由 LexicalAnalyzer.cs:916-918 抛意外字符；而 PRINT/PRINTL 的 STR_ArgumentBuilder
 * （ArgumentParser.cs:446-476）直接取原文子串，`{` 在那里是普通字面文本。
 */
class PcFormInterpolationTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("CNT", OperandType.INT, 1)
            .declareVariable("STRARR", OperandType.STR, 1)
            .declareVariable("GA", OperandType.INT, 1)
            .declareVariable("GSB", OperandType.STR, 1)
        return LineParser(sym)
    }

    private fun form(text: String): FormStringTerm {
        val s = assertIs<PrintStmt>(
            parser().parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, text)),
        )
        return assertIs<FormStringTerm>((s.args as ExpressionArgument).term)
    }

    private fun rejectForm(text: String, contains: String? = null): Throwable {
        val e = assertFailsWith(Throwable::class, "FORM 类型契约必须拒绝：$text") { form(text) }
        if (contains != null) {
            assertContains(e.message ?: "", contains, message = "应报「$contains」，实际：${e.message}")
        }
        return e
    }

    private fun embedded(f: FormStringTerm): List<FormPart.Embedded> =
        f.parts.filterIsInstance<FormPart.Embedded>()

    // -----------------------------------------------------------------------
    // 契约正面：`{…}` 只收整型
    // -----------------------------------------------------------------------

    @Test
    fun `花括号接受整型表达式`() {
        val f = form("PRINTFORML A={1 + 2} B={ARR:0} C={GA} D={!0} E={1 && 0}")
        val parts = embedded(f)
        assertTrue(parts.size == 5, "内插段数应为 5，实际 ${parts.size}")
        assertTrue(parts.all { it.conversion is FormConversion.Curly })
        // 整型内建方法调用同样合法（对照下面的字符串方法）
        form("PRINTFORML V={GETBIT(GA, 2)}")
        form("PRINTFORML V={CNT + 1} W={CNT > 0}")
    }

    @Test
    fun `花括号拒绝字符串型内建变量`() {
        // 金标逐条实测形态（GOLDEN_00_ROOT:19-21、GOLDEN_08_IO:24、GOLDEN_09_MISC:73、GOLDEN_01:111）
        for (name in listOf(
            "EMUERA_VERSION", "MONEYLABEL", "GAMEBASE_TITLE", "WINDOW_TITLE", "SAVEDATA_TEXT",
        )) {
            rejectForm("PRINTFORML X={$name}", "不是数值型")
        }
        rejectForm("PRINTFORML X={RESULTS:0}", "不是数值型")
        rejectForm("PRINTFORML X={LOCALS:1}", "不是数值型")
        rejectForm("PRINTFORML X={CSTR:0:0}", "不是数值型")
        rejectForm("PRINTFORML X={CALLNAME:0}", "不是数值型")
        rejectForm("PRINTFORML X={GSB}", "不是数值型")
        rejectForm("PRINTFORML X={STRARR:0}", "不是数值型")
    }

    @Test
    fun `花括号拒绝返回字符串的方法调用`() {
        // 金标 70 条「{{}}中的表达式结果不是整数」的主力形态
        for (src in listOf(
            "SUBSTRING(\"abcdef\",2,3)", "SUBSTRINGU(\"abcdef\",2,3)", "TOUPPER(\"aBc\")",
            "TOLOWER(\"aBc\")", "TOFULL(\"AB12\")", "TOHALF(\"AB12\")", "ESCAPE(\"a.b\")",
            "REPLACE(\"a-b-c\",\"-\",\"+\")", "STRFORM(\"V={1+2}\")", "TOSTR(42)",
            "BARSTR(5,10,10)", "MONEYSTR(1234)", "CSVNAME(0)", "CSVCALLNAME(0)",
            "HTML_TOPLAINTEXT(\"<i>AB</i>CD\")", "HTML_ESCAPE(\"<a>\")", "LOADTEXT(\"GOLDEN_TEST.txt\")",
            "GETDISPLAYLINE(0)", "MAP_GET(\"GM\", \"K1\")", "XML_TOSTR(\"GDOC\")",
            "GETVARS(\"GAMEBASE_TITLE\")", "GGETFONT(10)",
        )) {
            rejectForm("PRINTFORML V={$src}", "不是数值型")
        }
    }

    // -----------------------------------------------------------------------
    // 契约对偶：`%…%` 只收字符串
    // -----------------------------------------------------------------------

    @Test
    fun `百分号接受字符串表达式`() {
        val f = form("PRINTFORML V=%MONEYLABEL%")
        assertTrue(embedded(f).all { it.conversion is FormConversion.Percent })
        form("PRINTFORM 名前：%CALLNAME:0% 説明：%SUBSTRING(\"abcdef\",2,3)%")
        form("PRINTFORML S=%GSB%")
        form("PRINTFORML S=%STRARR:0%")
    }

    @Test
    fun `百分号拒绝整型表达式`() {
        rejectForm("PRINTFORML V=%CNT%", "不是字符串型")
        rejectForm("PRINTFORML V=%1 + 2%", "不是字符串型")
        rejectForm("PRINTFORML V=%ARR:0%", "不是字符串型")
    }

    @Test
    fun `串与整数的正确混排`() {
        // 语料实测范式：`PRINTFORMW 对%ENEMY_NAME%造成了{DAMAGE}点伤害！`
        val f = form("PRINTFORMW 对%GSB%造成了{CNT}点伤害")
        val kinds = embedded(f).map { it.conversion }
        assertTrue(
            kinds[0] is FormConversion.Percent && kinds[1] is FormConversion.Curly,
            "顺序与形态应为 [Percent, Curly]，实际=$kinds",
        )
        // 反向：把串塞进 {} / 把整数塞进 %%，各自被拒
        rejectForm("PRINTFORMW 对%GSB%造成了{GSB}点伤害", "不是数值型")
        rejectForm("PRINTFORMW 对%CNT%造成了{CNT}点伤害", "不是字符串型")
    }

    // -----------------------------------------------------------------------
    // 宽度 / LEFT / RIGHT / 空括号（StrForm.cs:272-308）
    // -----------------------------------------------------------------------

    @Test
    fun `宽度与对齐关键字`() {
        val f = form("PRINTFORML A={CNT, 8} B={CNT, 8, LEFT} C={CNT, 8, RIGHT}")
        val parts = embedded(f)
        assertTrue(parts.size == 3, "内插段数应为 3，实际 ${parts.size}")
        assertTrue((parts[0].conversion as FormConversion.Curly).width != null)
        assertTrue((parts[1].conversion as FormConversion.Curly).alignLeft)
        assertTrue(!(parts[2].conversion as FormConversion.Curly).alignLeft)
        // 宽度必须是整数表达式（StrForm.cs:287 ReduceIntegerTerm）
        rejectForm("PRINTFORML A={CNT, MONEYLABEL}", "不是数值")
        // 第三项只能是 LEFT / RIGHT（StrForm.cs:291-302）
        rejectForm("PRINTFORML A={CNT, 8, SIDE}", "LEFT 或 RIGHT")
        rejectForm("PRINTFORML A={CNT, 8, LEFT, 1}", "多余字符")
    }

    @Test
    fun `空括号与空百分号被拒`() {
        rejectForm("PRINTFORML A={}", "缺少表达式")
        rejectForm("PRINTFORML A=%%", "缺少表达式")
    }

    // -----------------------------------------------------------------------
    // `\@条件?左#右\@`：条件是整数表达式（StrForm.cs:241-254）
    // -----------------------------------------------------------------------

    @Test
    fun `三项条件式的条件必须是整数`() {
        form("PRINTFORML V=\\@ CNT?A#B \\@")
        rejectForm("PRINTFORML V=\\@ MONEYLABEL?A#B \\@", "不是数值")
    }

    // -----------------------------------------------------------------------
    // 三连记号（*** / +++ / === / /// / $$$）是**字符串**插值
    // 根因：StrForm.cs:216-238 把三连记号换成 NAME/CALLNAME 变量项，
    //       绑定的转换即 formatPercent（StrForm.cs:144-203 构造 FunctionMethodTerm）。
    //       故 `%***%` 之外无需再包一层，直接 `***` 就是字符串段；写成 `{***}` 则违反整型契约。
    // -----------------------------------------------------------------------

    @Test
    fun `三连记号是字符串插值`() {
        val f = form("PRINTFORML V=*** W=+++")
        val parts = embedded(f)
        assertTrue(parts.size == 2 && parts.all { it.conversion is FormConversion.Percent }, "实际=$parts")
    }

    // -----------------------------------------------------------------------
    // 非 FORM 参数形态没有插值上下文（金标 11 条「词法解析中出现意料之外的字符'{'」）
    // 根因：PRINT*S/PRINTFORMS = STR_EXPRESSION（FunctionIdentifier.cs:101-111），
    //       '{' 落在普通词法 → LexicalAnalyzer.cs:916-918 抛 UnexpectedCharacter。
    // -----------------------------------------------------------------------

    @Test
    fun `非 FORM 参数形态里的花括号被拒`() {
        for (src in listOf(
            "PRINTFORMS XY{3+3}", "PRINTFORMSL Z{4+4}", "PRINTFORMSK SK{11}",
            "PRINTFORMSKL SKL{12}", "PRINTFORMSD SDD{17}", "PRINTFORMSDL SDDL{18}",
            "PRINTSINGLES XY{3+3}", "PRINTSINGLEFORMS FS{23}", "PRINTSINGLEVK VK{2}",
            "PRINTVL A{1}", "PRINTV A{1}",
        )) {
            val e = assertFailsWith(Throwable::class, "$src 必须被拒（普通词法没有 '{' 上下文）") {
                parser().parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, src))
            }
            assertContains(e.message ?: "", "予期しない文字", message = "应报词法意外字符：${e.message}")
        }
    }

    @Test
    fun `字面文本形态里花括号与百分号只是普通字符或运算符`() {
        // PRINT/PRINTL（STR_NULLABLE）取原文子串（ArgumentParser.cs:446-476）→ '{' 合法且不展开
        val s = assertIs<PrintStmt>(
            parser().parseLogicalLine(
                LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, "PRINT 花括号{1}原样输出"),
            ),
        )
        assertTrue((s.args as ExpressionArgument).constStr!!.contains("{1}"), "PRINT 应保留字面 '{'")
        // STR_EXPRESSION 形态里 '%' 是取模运算符而非插值定界符 → 表达式异常
        assertFailsWith(Throwable::class, "PRINTFORMS 没有 %…% 插值上下文") {
            parser().parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, "PRINTFORMS %MONEYLABEL%"))
        }
    }
}
