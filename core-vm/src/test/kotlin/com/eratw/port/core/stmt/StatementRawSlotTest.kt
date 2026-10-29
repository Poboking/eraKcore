package com.eratw.port.core.stmt

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Statement 原文槽（MEM-OPT）：
 * 构造期照常入槽（既有调用方零改动），releaseRaw 后 raw 返回空串。
 * 机制本身默认惰性 —— 没有任何生产路径在本波调用 releaseRaw（接线点由组长裁决，
 * 见交付报告「需组长接线」）。
 */
class StatementRawSlotTest {

    @Test
    fun `构造期原文照常入槽`() {
        val s = InstructionStmt(FunctionCode.WAIT, VoidArgument(), 3, "WAIT")
        assertEquals("WAIT", s.raw)
        assertEquals(3, s.line)

        val assign = AssignStmt(
            VarRef("LV", emptyList(), operandType = OperandType.INT),
            OperatorCode.ASSIGNMENT,
            SpSetArgument(VarRef("LV", emptyList(), operandType = OperandType.INT), IntLiteral(1)),
            5,
            "LV = 1",
        )
        assertEquals("LV = 1", assign.raw)
    }

    @Test
    fun `releaseRaw 后 raw 为空串 幂等`() {
        val s = InstructionStmt(FunctionCode.WAIT, VoidArgument(), 3, "WAIT")
        s.releaseRaw()
        assertEquals("", s.raw, "释放后 raw 必须是空串（生产读者为零，见 Statement.kt 原文槽 KDoc）")
        s.releaseRaw()
        assertEquals("", s.raw, "幂等")
        assertEquals(3, s.line, "行号不受释放影响")
        assertEquals(FunctionCode.WAIT, s.code)
        assertTrue(s.args is VoidArgument, "args 不受释放影响")
    }
}
