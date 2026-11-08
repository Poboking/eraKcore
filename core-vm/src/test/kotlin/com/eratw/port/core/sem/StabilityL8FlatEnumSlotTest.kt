package com.eratw.port.core.sem

import com.eratw.port.core.code.ErbcDecodeException
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.Statement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 稳定性审查波 L8：扁平参数/语句记录里**枚举序号线值**的越界护栏。
 *
 * 背景：`StatementExecutor.execute` 对槽 1（`FunctionCode` ordinal）已有越界校验并抛
 * [ErbcDecodeException]，但同类的两个枚举槽此前直接走 `entries[...]`：
 * - `FlatArgs.caseList()` 的运算符槽（`OperatorCode.ordinal`，`FlatStatements` 写入口同序）；
 * - `execPrintFlat` 的 `argType` 槽（`ArgType.ordinal`，`FlatStatements.readPrintSpec` 同序）。
 *
 * 越界线值只可能来自畸形记录（或编解码两侧漂移）。修复前是 `ArrayIndexOutOfBoundsException`
 * （与容器层「畸形记录 ⇒ ErbcDecodeException」的报错口径不符，且越界位置只剩实现细节），
 * 修复后与 `caseType` / `sortOrder` / `dtOptions` / `op` 的既有护栏同口径。
 *
 * 每个越界用例都配一条**合法值正例**：证明护栏不改动正常记录的还原/打印语义。
 */
class StabilityL8FlatEnumSlotTest {

    /** 单条语句的槽数组（`headAt` 恒 0；`FlatArgs` 用 2 参构造，区间取数组长度）。 */
    private class SlotsSource(private val slots: IntArray) : FlatStmtSource {
        override fun opAt(index: Int): Int = slots[1]

        override fun headAt(index: Int): Int = 0

        override fun argStreamBase(): IntArray = slots

        override fun exprAt(poolIdx: Int): Expr? = null

        override fun stringAt(poolIdx: Int): String = ""

        override fun statementAt(index: Int): Statement = error("本用例不走兜底还原")
    }

    private fun caseRecord(operatorWire: Int): SlotsSource = SlotsSource(
        intArrayOf(
            ErbcFormat.ArgTag.CASE,
            0, // 公共头：无常量载荷
            1, // 1 项
            ErbcFormat.WireEnum.CASE_NORMAL,
            -1, // leftTerm = null
            -1, // rightTerm = null
            operatorWire,
        ),
    )

    @Test
    fun `caseList 运算符线值越界时抛 ErbcDecodeException 而非 AIOOBE`() {
        val badWires = listOf(-1, OperatorCode.entries.size, Int.MAX_VALUE)
        for (wire in badWires) {
            val args = FlatArgs(caseRecord(wire), 0)
            args.open()
            val e = assertThrows<ErbcDecodeException>("OperatorCode 线值 $wire 应被拒绝") {
                args.caseList()
            }
            assertTrue(e.message!!.contains("OperatorCode"), e.message)
        }
    }

    @Test
    fun `caseList 合法运算符线值仍按 ordinal 还原`() {
        val args = FlatArgs(caseRecord(OperatorCode.PLUS.ordinal), 0)
        args.open()
        val cases = args.caseList()
        assertEquals(1, cases.size)
        assertEquals(OperatorCode.PLUS, cases[0].operator)
        assertNull(cases[0].leftTerm)
        assertNull(cases[0].rightTerm)
    }

    private fun printRecord(argTypeWire: Int): SlotsSource = SlotsSource(
        intArrayOf(
            ErbcFormat.StmtTag.PRINT,
            FunctionCode.PRINT.ordinal,
            argTypeWire,
            0, // printBits
            0, // flag
            ErbcFormat.ArgTag.VOID,
            0, // 公共头
        ),
    )

    @Test
    fun `execPrintFlat 的 ArgType 线值越界时抛 ErbcDecodeException 而非 AIOOBE`() {
        val badWires = listOf(-1, ArgType.entries.size)
        for (wire in badWires) {
            val env = SemTestEnv()
            val e = assertThrows<ErbcDecodeException>("ArgType 线值 $wire 应被拒绝") {
                env.executor.execute(printRecord(wire), 0, env.ctx)
            }
            assertTrue(e.message!!.contains("ArgType"), e.message)
        }
    }

    @Test
    fun `execPrintFlat 合法记录仍照常打印`() {
        val env = SemTestEnv()
        env.runFlat(printStmt(FunctionCode.PRINT, strArg("abc")))
        assertTrue(env.console.events.contains("print(abc)"), env.console.events.toString())
    }
}
