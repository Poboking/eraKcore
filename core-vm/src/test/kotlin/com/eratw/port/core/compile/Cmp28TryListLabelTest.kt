package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CMP-28：`labelNames` 预扫描与 `defineLabel` 的**可定义集合**必须一致。
 *
 * TRY{CALL,JUMP,GOTO}LIST 是自带 `FUNC … ENDFUNC` 的子块，由 `compileTryList` 整体消费，
 * 块内的 `$标签` 行**永远不会**走到 `defineLabel`（也不会进 CodeBuilder 的标签表）。
 * 旧预扫描用 `statements.filterIsInstance<GotoLabelStmt>()` 把块内标签也算进「存在」，
 * 于是 `TRYGOTOLIST` 的常量目标判定命中、发射 `_JUMP` 并登记 labelFixup，而回填表里
 * 没有该标签 ⇒ `run()` 末尾抛「GOTO 目标不存在」⇒ **整函数编译失败**（变空体桩）。
 *
 * 原版把 TRY*LIST 块内的 `GotoLabelLine` 判为装载期错误行
 * （`CanNotLabelDefineInSyntax`，spec: ErbLoader.cs:1060-1067），它不是合法跳转目标；
 * 常量 GOTO 查不到目标时 TRY 系在运行期直接跳过本行（`JumpTo == null → return`，
 * spec: FunctionIdentifier.cs:3685-3693）。因此本侧的正确降级是：**存在判定不认它、
 * 不发射任何指令**。语料 0 处（BOARD CMP-28）。
 */
class Cmp28TryListLabelTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareFunction("MYFUNC", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "cmp28.erb", i + 1, text))
        }
    }

    private fun compile(vararg lines: String): Pair<FunctionBytecode, com.eratw.port.core.code.Program> {
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(FuncSig("TEST"), parse(*lines), setOf("TEST", "MYFUNC"))
        return fb to c.build("TEST")
    }

    @Test
    fun `TRYGOTOLIST 指向块内标签时静默跳过_不再在 run 末抛 GOTO 目标不存在`() {
        val (fb, p) = compile(
            "TRYGOTOLIST",
            "FUNC \$INSIDE",
            "\$INSIDE",
            "ENDFUNC",
            "PRINTL \"AFTER\"",
        )
        val ops = (fb.entryPc until fb.endPc).map { p.instructionAt(it).op }
        assertFalse(
            Opcode._JUMP in ops,
            "块内 \$INSIDE 不是可定义标签 ⇒ 目标不存在 ⇒ TRY 系静默跳过，不得发射 _JUMP",
        )
        assertFalse(Opcode.THROW in ops, "不得把「目标不存在」推迟到运行期")
        assertTrue(fb.labels.isEmpty(), "块内标签不进函数标签表（compileTryList 从不 defineLabel），实际=${fb.labels}")
    }

    @Test
    fun `TRYGOTOLIST 多个目标中块内标签被跳过_块外标签仍命中`() {
        val (fb, p) = compile(
            "TRYGOTOLIST",
            "FUNC \$INSIDE",
            "FUNC \$OUTSIDE",
            "\$INSIDE",
            "ENDFUNC",
            "\$OUTSIDE",
            "PRINTL \"OUT\"",
        )
        val jumps = (fb.entryPc until fb.endPc)
            .map { it to p.instructionAt(it) }
            .filter { it.second.op == Opcode._JUMP }
        assertEquals(1, jumps.size, "只应发射命中 \$OUTSIDE 的那一条 _JUMP，实际 ${jumps.size}")
        val (pc, ins) = jumps.single()
        assertEquals(fb.labels["OUTSIDE"], pc + 1 + ins.a, "_JUMP 目标 = 块外标签 \$OUTSIDE")
        assertEquals(setOf("OUTSIDE"), fb.labels.keys, "标签表只含块外标签")
    }
}
