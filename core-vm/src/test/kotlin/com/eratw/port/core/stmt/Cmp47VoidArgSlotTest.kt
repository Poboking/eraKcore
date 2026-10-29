package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-47 必红夹具 —— `checkArgumentType` 必须把「实参为 null」与「槽位类型 instanceof void」
 * **拆开**，不许像旧实现那样并成同一个放行分支。
 *
 * 原版判据（spec: ArgumentBuilder.cs:60-80）：
 * ```
 * if (arguments[i] == null) {
 *     if (type != null) { Warn(CanNotRecognizeArg, i+1, level 2); return false; }   // typeof(void) 也算「有类型」
 * } else if (type != typeof(void) && type != arguments[i].GetOperandType()) { Warn(IncorrectArg, ...); }
 * ```
 * 也就是说：**省略的实参位**在 `typeof(void)` 槽位上仍然报「无法识别第N参数」
 * （官方 `CanNotRecognizeArg` zhs 原文 = 「无法识别第{0}参数」，Lang.cs:1934 / zhs xml:438；
 * L11 的文案渲染层落地后 message 改为官方 zhs，故本文件断言同步改钉）。
 * 旧实现 `if (t == null || t == VOID) continue`（ArgBuilders.kt:137）把 VOID 一起放过，
 * 于是 `VARSET LOCAL, , 1, 5` 静默补默认值（:912-913）、`CVARSET` 同理（:935-936）、
 * `PRINTBUTTON "x", ` 静默按 key=0 —— 全是「该行本应报错」的静默降级。
 */
class Cmp47VoidArgSlotTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("SLOCAL", OperandType.STR, 1)
            .declareVariable("CFLAG", OperandType.INT, 1, isCharacterData = true)
        return LineParser(sym)
    }

    private fun parse(p: LineParser, text: String, lineNo: Int = 9): Statement? =
        p.parseLogicalLine(LogicalLine(LineKind.CODE, "CMP47_VOID.ERB", lineNo, text))

    private fun rejectsSlot(text: String, slot: Int) {
        val e = assertFailsWith<StmtParseException>("$text 必须报「无法识别第${slot}参数」") {
            parse(parser(), text)
        }
        assertTrue(
            e.message!!.contains("无法识别第${slot}参数"),
            "$text 实际错误：${e.message}",
        )
    }

    // -----------------------------------------------------------------------
    // 红形：三个下游（VARSET / CVARSET / PRINTBUTTON）的 VOID 槽被省略
    // -----------------------------------------------------------------------

    @Test
    fun `VARSET 第二参省略必须报错`() {
        // spec: ArgumentParser.cs:1766-1818（argumentTypeArray = [void, void, long, long]）
        rejectsSlot("VARSET LOCAL, , 1, 5", 2)
    }

    @Test
    fun `CVARSET 省略中间槽必须报错`() {
        // spec: ArgumentParser.cs:1822-1886（argumentTypeArray = [void, void, void, long, long]）
        rejectsSlot("CVARSET CFLAG:0, , , 5", 2)
        rejectsSlot("CVARSET CFLAG:0, 0, , 5", 3)
    }

    @Test
    fun `PRINTBUTTON 第二参省略必须报错而不是 key 0`() {
        // spec: ArgumentParser.cs:1889-1907（argumentTypeArray = [string, void]）。
        // 注意 `PRINTBUTTON "押", ` 的尾逗号在 ReduceArguments 里被当成「整参省略」→ 只剩 1 项，
        // 命中的是 NotEnoughArguments；要落在**槽位内 null** 上得写 `"押", ,`（原版同样 level 2）。
        rejectsSlot("PRINTBUTTON \"押\", ,", 2)
        // 尾逗号形态：原版 count==1 → NotEnoughArguments（level 2），同样不许静默 key 0。
        // 官方 zhs 原文 = 「参数过少」（Lang.cs:1926 / zhs xml:436；L11 渲染层落地后旧断言「参数不足」需改钉）。
        val e = assertFailsWith<StmtParseException> { parse(parser(), "PRINTBUTTON \"押\", ") }
        assertTrue(e.message!!.contains("参数过少"), "尾逗号形态实际错误：${e.message}")
    }

    // -----------------------------------------------------------------------
    // 绿形（改后必须仍合法）：整参省略 vs 槽内省略是两回事
    // -----------------------------------------------------------------------

    @Test
    fun `VARSET 只给目标时补默认值`() {
        // 原版 `list.Count > 1 ? list[1] : 默认`（ArgumentParser.cs:1791）
        val arg = assertIs<InstructionStmt>(parse(parser(), "VARSET LOCAL")).args
        val v = assertIs<SpVarSetArgument>(arg)
        assertEquals(0L, (v.term as com.eratw.port.core.expr.IntLiteral).value)
        assertNull(v.start)
        assertNull(v.end)
    }

    @Test
    fun `VARSET 显式给值不受影响`() {
        val v = assertIs<SpVarSetArgument>(assertIs<InstructionStmt>(parse(parser(), "VARSET LOCAL, 5, 1, 3")).args)
        assertEquals(5L, (v.term as com.eratw.port.core.expr.IntLiteral).value)
        assertEquals(1L, (v.start as com.eratw.port.core.expr.IntLiteral).value)
        assertEquals(3L, (v.end as com.eratw.port.core.expr.IntLiteral).value)
    }

    @Test
    fun `CVARSET 只给目标时下标与值补 0`() {
        // spec: ArgumentParser.cs:1861-1862（list.Count<=1 → SingleLongTerm(0)；>2 才取 list[2]）
        val c = assertIs<SpCVarSetArgument>(assertIs<InstructionStmt>(parse(parser(), "CVARSET CFLAG:0")).args)
        assertEquals(0L, (c.index as com.eratw.port.core.expr.IntLiteral).value)
        assertEquals(0L, (c.term as com.eratw.port.core.expr.IntLiteral).value)
    }

    @Test
    fun `PRINTBUTTON 显式 key 正常`() {
        val b = assertIs<SpButtonArgument>(assertIs<InstructionStmt>(parse(parser(), "PRINTBUTTON \"押\", 3")).args)
        assertEquals(3L, (b.buttonWord as com.eratw.port.core.expr.IntLiteral).value)
    }

    @Test
    fun `VARSET 尾逗号形态仍按整参省略补默认值`() {
        // 语料实况（4 行，尾逗号但**没有第二个槽**）：`VARSET 合成文字列配列,`
        // —— STONE_MIZUKOFACE.ERB:66、モブ店員関連.ERB:69/:489、モブ子表示.ERB:31。
        // ReduceArguments 不在行尾补空实参 ⇒ 仍是「整参省略」，必须保持绿。
        val v = assertIs<SpVarSetArgument>(assertIs<InstructionStmt>(parse(parser(), "VARSET SLOCAL,")).args)
        assertEquals("", (v.term as com.eratw.port.core.expr.StrLiteral).value)
    }

    @Test
    fun `SWAP 第一个变量省略报第1个`() {
        // 同一个 checkTypes 路径：VOID 槽 + null 实参（spec: ArgumentParser.cs:1633-1666）
        rejectsSlot("SWAP , LOCAL", 1)
    }
}
