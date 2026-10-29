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
 * CMP-48 必红夹具 —— `SET` / `METHOD` 是**移植侧伪码**，不许进语句名表。
 *
 * 原版这两个码根本没有名字：
 * - `setFunc = new FunctionIdentifier("SET", FunctionCode.SET, new SET_Instruction())`
 *   只是构造出来供赋值行复用，**从未 addFunction 进 funcDic**
 *   （spec: FunctionIdentifier.cs:3829；`addFunction` 才是 :3791-3805 的唯一入表口）；
 * - `METHOD` 同理：`methodInstruction` 只在 :4140-4148 与 methodList 的名字**配对**入表，
 *   入表键是**方法名**（如 `MONEYLABLE`），不是字面量 "METHOD"（spec: FunctionIdentifier.cs:4138-4141）。
 *
 * 因此原版 `funcDic`（= `GetInstructionNameDic()`，:3807-3810）里查不到 "SET"/"METHOD"：
 * 行首写 `SET = 5` 走赋值两段式（`LogicalLineParser.cs:536-556`），
 * 而用户 `#DIM SET` 这个名字在原版是**合法**的（`nameDic` 只收 Reserved / SystemVariable /
 * SystemMethod / SystemInstrument，spec: IdentifierDictionary.cs:141-183、:258-297）。
 *
 * 移植侧 `FunctionCode.BY_NAME` 由枚举名直接建表（FunctionCode.kt:474），把两个伪码也收了进去；
 * `LineParser.parseCodeLine` 先查该表（LineParser.kt:149-151）⇒ 用户变量撞名时整行被当指令：
 * `SET = 5` 进 SP_SET 无左值 → 抛错 → **整个 @函数降成空体桩**。
 */
class Cmp48PseudoCodeNameTest {

    private fun parser(): LineParser {
        val sym = SymbolTable()
            .declareVariable("SET", OperandType.INT, 0)
            .declareVariable("METHOD", OperandType.INT, 0)
        return LineParser(sym)
    }

    private fun parse(text: String, lineNo: Int = 5): Statement? =
        parser().parseLogicalLine(LogicalLine(LineKind.CODE, "CMP48_PSEUDO.ERB", lineNo, text))

    // -----------------------------------------------------------------------
    // 红形
    // -----------------------------------------------------------------------

    @Test
    fun `用户变量 SET 的赋值行不得被当指令`() {
        val s = assertIs<AssignStmt>(parse("SET = 5"))
        assertEquals("SET", s.dest.name)
        val rhs = (s.args as SpSetArgument).term
        assertEquals(5L, (rhs as com.eratw.port.core.expr.IntLiteral).value)
    }

    @Test
    fun `用户变量 METHOD 的赋值行不得被当指令`() {
        val s = assertIs<AssignStmt>(parse("METHOD = 7"))
        assertEquals("METHOD", s.dest.name)
    }

    @Test
    fun `伪码不进语句名表`() {
        // spec: FunctionIdentifier.cs:3807-3810 的 funcDic 只有 addFunction/addPrint* 的键。
        assertFalse(FunctionCode.BY_NAME.containsKey("SET"), "SET 不是语句名（setFunc 不入 funcDic）")
        assertFalse(FunctionCode.BY_NAME.containsKey("METHOD"), "METHOD 不是语句名（入表键是方法名）")
        // 真语句名仍在表里（回归护栏）。
        assertTrue(FunctionCode.BY_NAME.containsKey("VARSET"))
        assertTrue(FunctionCode.BY_NAME.containsKey("PRINTL"))
    }

    // -----------------------------------------------------------------------
    // 基线：METHOD 语句（用户/内建方法名）不受影响
    // -----------------------------------------------------------------------

    @Test
    fun `内建方法名仍是 METHOD 语句`() {
        // 入表键是方法名（FunctionIdentifier.cs:4138-4141），与伪码 "METHOD" 无关。
        val s = assertIs<MethodStmt>(parse("GETCOLOR 1"))
        assertEquals("GETCOLOR", s.methodName)
    }
}
