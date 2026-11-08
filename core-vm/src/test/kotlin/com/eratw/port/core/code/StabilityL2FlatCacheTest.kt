package com.eratw.port.core.code

import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L2 稳定性通道：分片惰性扁平池的**片数上限**必须真的卡住。
 *
 * `SegmentedFlatStmtSource` 的 KDoc 承诺"上限由 maxSegments 卡住（LRU 淘汰）"，
 * 上一版 `trim()` 却有一条 `if (maxSegments < 2) return`：上限配成 1 片（或 0 片）时
 * 淘汰整段被跳过，片表随跑到的段数无界增长 —— 与承诺相反，且诊断口
 * `Program.buildSegmentedFlatSource(segmentSize, maxSegments)` 正是这条路的入口。
 *
 * 反证：把 `trim()` 里的 `limit` 换回 `maxSegments` 并恢复那条早退，
 * 本用例的 `residentSegments` 会等于 21（旧行为），断言当场红。
 */
class StabilityL2FlatCacheTest {

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "")))
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    @Test
    fun `片数上限为 1 时仍必须淘汰旧片`() {
        val program = sampleProgram(40)
        val reference = FlatStatements.encode(program.statements(), emptyList())
        val src = program.buildSegmentedFlatSource(segmentSize = 2, maxSegments = 1) as SegmentedFlatStmtSource

        for (i in 0 until program.statementCount) {
            assertEquals(reference.opAt(i), src.opAt(i), "[$i] op 读数必须与整池压扁一致")
        }

        val expectedSegments = (program.statementCount + 1) / 2
        assertEquals(
            1,
            src.residentSegments,
            "上限 1 片必须真的卡住；旧实现（maxSegments < 2 直接 return）常驻 $expectedSegments 片",
        )
        assertEquals(expectedSegments, src.builtSegments, "每片都应被建过一次（淘汰不影响正确性）")
    }
}
