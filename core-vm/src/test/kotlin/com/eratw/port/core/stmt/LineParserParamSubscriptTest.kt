package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * CMP-85A **生产者侧**：函数形参项的冒号下标必须是常量，且原文不得被截短。
 *
 * ## 改前缺陷（本文件的夹具先红）
 *
 * `LineParser.extractLabelParamRefs`（改前 :622-634）在 `:` 之后只取**一个** token
 * 拼进形参项原文（改前 `tokenText` :698-703），于是：
 *
 * * `@F(OUT:1+1)` 的 `paramRefs` 变成 `OUT:1` —— 静默写错元素（应拒绝）；
 * * `@F(OUT:-1)` 变成 `OUT:` —— 值丢失，只剩运行期 `CodeEE` 兜底；
 * * `@F(OUT:X+1)` 变成 `OUT:X` —— 同样只剩运行期兜底。
 *
 * ## 原版口径（reference/emuera-cs 只读）
 *
 * `ErbLoader.parseLabel`（`ErbLoader.cs:676-695`）把每个形参项 `Restructure` 成
 * `VariableTerm`：
 *
 * * 不是 `VariableTerm` ⇒ `ArgCanOnlyAssignableVar`（`:680-684` → :652）；
 * * `VariableTerm.isAllConst` 为假 ⇒ `ArgSubscriptOnlyConst`（`:691-693` → :774-776）；
 * * 两种都走 `Warn(level 2, isError:true)`（`:768-770`），标签置错。
 *
 * `isAllConst` 的判据是构造期「每个下标实参都已是 `SingleLongTerm`」
 * （`VariableTerm.cs:28-46`）：所以裸十进制字面量算常量；`-1`（一元节点）、
 * `C_INDEX`（`VariableTerm` 节点）都不算 —— 本文件的拒绝口径与之一致。
 * zhs 文案：`lang.emuera-zhs.xml:566` = `函数"@{0}"的参数错误:{1}`、
 * `:576` = `函数定义的参数下标必须指定为一个常量`。
 *
 * 本移植沿用 [StmtParseException]「解析期立即抛」的既有口径（`ParseSupport.kt:23-35`），
 * 不新增错误等级；错误信息里带**完整**形参项原文，证明没有退回到单 token 截断。
 *
 * 语料读数（3,971 个 ERB/ERH，`ERATW_CORPUS` 指到 ERB 子树；本机逐行扫描）：
 * 形参下标项 11,721 个、非十进制下标 0 个 —— 本路不影响语料解析失败计数
 * （权威读数见 `:core-vm:corpusSmoke`：3,971 文件 / 0 解析失败）。
 */
class LineParserParamSubscriptTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            // 形参本体（可赋值变量）与下标里的普通变量 X
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("X", OperandType.INT, 1)
        return LineParser(sym)
    }

    private fun label(text: String): FuncLabelStmt =
        assertIs<FuncLabelStmt>(parser().parseLabelLine(text, 1, "PARAMSUB.ERB"))

    private fun reject(text: String): StmtParseException {
        val e = assertFailsWith<StmtParseException>(
            "形参下标非编译期常量必须在解析期拒绝（改前静默取错值）：$text",
        ) { label(text) }
        assertContains(e.message ?: "", "函数定义的参数下标必须指定为一个常量", message = "实际=${e.message}")
        assertContains(e.message ?: "", "ArgSubscriptOnlyConst", message = "实际=${e.message}")
        return e
    }

    // ------------------------------------------------------------------
    // 1. `1+1`：改前 paramRefs 静默折成 `OUT:1`（错值）—— 必须解析期拒绝
    // ------------------------------------------------------------------

    @Test
    fun `常量表达式 1+1 必须解析期拒绝且报完整原文`() {
        // 原版：`+1` 越过 `ReduceVariableArgument` 的单 term 口径后，整个项不再是
        // VariableTerm ⇒ ArgCanOnlyAssignableVar（ErbLoader.cs:680-684）。本移植把
        // 「不是单个常量字面量」统一按 ArgSubscriptOnlyConst 拒绝（同为 level 2 置错）。
        val e = reject("@F(OUT:1+1)")
        assertContains(e.message ?: "", "OUT:1+1", message = "错误信息必须带完整下标原文：${e.message}")
    }

    // ------------------------------------------------------------------
    // 2. `-1`：改前 paramRefs 变成 `OUT:` —— 必须解析期拒绝
    // ------------------------------------------------------------------

    @Test
    fun `一元负号 -1 必须解析期拒绝且报完整原文`() {
        // 原版：一元负号在构造期还是函数节点，`isAllConst` 为假
        // （VariableTerm.cs:34-45）⇒ ArgSubscriptOnlyConst（ErbLoader.cs:691-693 → :774-776）。
        val e = reject("@F(OUT:-1)")
        assertContains(e.message ?: "", "OUT:-1", message = "错误信息必须带完整下标原文：${e.message}")
    }

    // ------------------------------------------------------------------
    // 3. 回归护栏：合法常量下标照旧通过
    // ------------------------------------------------------------------

    @Test
    fun `十进制常量下标照旧通过并保留元素号`() {
        val f = label("@F(OUT:1)")
        assertEquals(listOf("OUT"), f.paramNames)
        assertEquals(listOf("OUT:1"), f.paramRefs)
        // 逐位默认值：本项没有 `= 默认值`（ErbLoader.cs:715-722 的 NullTerm 分支）
        assertEquals(listOf<ParamDefault?>(null), f.paramDefaults)
    }

    @Test
    fun `多维常量下标照旧通过`() {
        // 语料里 `ARGS:0` 一类一位下标为主；两位下标保持逐段校验、逐段保留
        val f = label("@F(OUT:1:0)")
        assertEquals(listOf("OUT"), f.paramNames)
        assertEquals(listOf("OUT:1:0"), f.paramRefs)
    }

    @Test
    fun `括号与一元加号的字面量按下标常量折叠`() {
        // 原版括号/一元 `+` 在 reduceTerm 里直接返回内层 SingleLongTerm
        // （OperatorMethodManager.cs:763-766：一元 Plus 返回 o1），故 `(1)` / `+1`
        // 仍是 SingleLongTerm、仍是常量。
        assertEquals(listOf("OUT:1"), label("@F(OUT:(1))").paramRefs)
        assertEquals(listOf("OUT:1"), label("@F(OUT:+1)").paramRefs)
    }

    @Test
    fun `十六进制字面量下标折成十进制`() {
        // 词法器把 0x10 解成 Integer(16)（LexicalAnalyzer.kt:82-96）；
        // paramRefs 与运行期 `toLongOrNull()` 都吃十进制，故写回必须是 `16`。
        assertEquals(listOf("OUT:16"), label("@F(OUT:0x10)").paramRefs)
    }

    // ------------------------------------------------------------------
    // 4. 非 const 变量表达式：装载期拒绝（不再只靠运行期 CodeEE 兜底）
    // ------------------------------------------------------------------

    @Test
    fun `变量表达式 X+1 必须解析期拒绝`() {
        // 原版：整个形参项不再是 VariableTerm ⇒ ArgCanOnlyAssignableVar
        // （ErbLoader.cs:680-684）；运行期 `ProcessRuntime.foldParamSubscripts`
        // 只是容器旧数据/直构路径的兜底。
        val e = reject("@F(OUT:X+1)")
        assertContains(e.message ?: "", "OUT:X+1", message = "错误信息必须带完整下标原文：${e.message}")
    }

    // ------------------------------------------------------------------
    // 5. TODO(verify)：`#DIM CONST` 名下标 —— 语料 0 例，不硬做常量折叠
    // ------------------------------------------------------------------

    /**
     * TODO(verify): `@F(OUT:C_INDEX)` 形态语料 0 例。
     *
     * 原版按 `VariableTerm.cs:34-45` 的构造期判据也不把常量名当 `SingleLongTerm`
     * （`OUT:C_INDEX` 的下标实参是 `VariableTerm(C_INDEX)`），故 [ArgSubscriptOnlyConst]
     * 有据；但本移植没有「常量值表」可用来折成元素号，这里**不硬做折叠**，
     * 只钉「与其它非字面量一样在解析期拒绝、绝不静默取错值」。若真机/新语料出现该形态，
     * 需先给 [SymbolTable] 补常量值查询再决定折成哪个元素。
     */
    @Test
    fun `TODO verify 常量名下标当前按非字面量拒绝`() {
        val sym = SymbolTable()
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("C_INDEX", OperandType.INT, 0, isConst = true)
        val e = assertFailsWith<StmtParseException> {
            assertIs<FuncLabelStmt>(LineParser(sym).parseLabelLine("@F(OUT:C_INDEX)", 1, "PARAMSUB.ERB"))
        }
        assertContains(e.message ?: "", "函数定义的参数下标必须指定为一个常量", message = "实际=${e.message}")
    }
}
