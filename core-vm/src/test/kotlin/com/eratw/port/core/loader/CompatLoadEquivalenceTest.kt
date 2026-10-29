package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 兼容装载档（[ProgramLoader.loadMapped] 的 `compat = true`，2026-10-28 低端机 OOM）与
 * 默认档的**等价性**单测。
 *
 * 兼容档只改装载的**并发形状**（去重与校验串行、不起 `erbc-intern-seed` 线程），不许改
 * 任何装载语义。判据：
 * 1. 同一份产物两条档的 `Program` 逐字段一致（`code`/`lines` 全量 + 四个池 + 函数表），
 *    且都以 `ProgramLoader.loadFile` 为共同基准；
 * 2. 两条档的装载分段 mark 序列一致（VALIDATE / INTERN 两段都还在，只是不再重叠）——
 *    失败屏的「卡在 <段>」口径依赖这些段名。
 */
class CompatLoadEquivalenceTest {

    /** 覆盖面优先的小样本：表达式池 / 语句池 / 标签表 / 多字节 UTF-8 / 负常量。 */
    private fun sampleProgram(): com.eratw.port.core.code.Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 1, 3)
        val c0 = b.constant(42L)
        val c1 = b.constant(Long.MIN_VALUE)
        val s0 = b.string("こんにちは，世界")
        val s1 = b.string("")
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.global(1), c1, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s1, 0, 2)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(7L, 1, 2)), SlotAddr.local(1), 0, 4)
        b.emit(Opcode.WAIT, b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 10, "WAIT")), 0, SlotAddr.NONE, 10)
        b.emit(Opcode.DATA, b.statement(DataStmt(FunctionCode.DATA, "数据", VoidArgument(), 11, "DATA 数据")), 0, SlotAddr.NONE, 11)
        b.declareLabel(main, "TAIL", b.here())
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        val sub = b.declareFunction("sub", b.here(), 2, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 20)
        b.declareLabel(sub, "INNER", b.here() - 1)
        b.setFunctionSlots(sub, 4)
        b.noteGlobalSlot(1)
        return b.build(0)
    }

    /** 与 `ErbcStreamEquivalenceTest` 同口径的逐字段比对（两档互为参照，共同基准是 loadFile）。 */
    private fun assertProgramsIdentical(expected: com.eratw.port.core.code.Program, actual: com.eratw.port.core.code.Program, ctx: String) {
        assertContentEquals(expected.code, actual.code, "$ctx：code 全量 ${expected.code.size} 字")
        assertContentEquals(expected.lines, actual.lines, "$ctx：lines 全量 ${expected.lines.size} 行")
        assertEquals(expected.instructionCount, actual.instructionCount, "$ctx：指令条数")
        assertEquals(expected.globalSlots, actual.globalSlots, "$ctx：全局槽")
        assertEquals(expected.entryFunction, actual.entryFunction, "$ctx：入口函数")
        assertEquals(expected.constantCount, actual.constantCount, "$ctx：常量池条目数")
        for (i in 0 until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$ctx：常量池 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$ctx：字符串池条目数")
        for (i in 0 until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$ctx：字符串池 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$ctx：表达式池条目数")
        for (i in 0 until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$ctx：表达式池 $i")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$ctx：语句池条目数")
        for (i in 0 until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "$ctx：语句池 $i 类")
            assertEquals(e.code, a.code, "$ctx：语句池 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$ctx：语句池 $i 行号")
            assertEquals(e.raw, a.raw, "$ctx：语句池 $i 原文")
        }
        assertEquals(expected.functions.size, actual.functions.size, "$ctx：函数表长度")
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$ctx：函数 $i 名")
            assertEquals(e.entryPc, a.entryPc, "$ctx：函数 $i 入口")
            assertEquals(e.arity, a.arity, "$ctx：函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$ctx：函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$ctx：函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$ctx：函数 ${e.name} 标签 pc")
        }
        assertEquals(expected.callArgs, actual.callArgs, "$ctx：调用点实参表（v2 段 7）")
    }

    @Test
    fun `兼容档与默认档装载同一份产物逐字段一致`(@TempDir dir: Path) {
        val f = dir.resolve("program.erbc")
        Files.write(f, ErbcWriter.encode(sampleProgram()))
        val baseline = ProgramLoader.loadFile(f)
        val default = ProgramLoader.loadMapped(f)
        val compat = ProgramLoader.loadMapped(f, compat = true)
        assertProgramsIdentical(baseline, default, "默认档 vs loadFile")
        assertProgramsIdentical(baseline, compat, "兼容档 vs loadFile")
    }

    @Test
    fun `兼容档的装载分段 mark 序列与默认档一致`(@TempDir dir: Path) {
        val f = dir.resolve("program.erbc")
        Files.write(f, ErbcWriter.encode(sampleProgram()))
        val defaultMarks = ArrayList<String>()
        val compatMarks = ArrayList<String>()
        ProgramLoader.loadMapped(f, compat = false) { defaultMarks += it }
        ProgramLoader.loadMapped(f, compat = true) { compatMarks += it }
        assertEquals(defaultMarks, compatMarks, "两条档的段名序列（失败屏「卡在 <段>」口径）")
        // 兼容档只是不再重叠，VALIDATE / INTERN 两段必须都还在：
        assertTrue(compatMarks.any { it.endsWith(LoadStages.VALIDATE) }, "引用范围校验段缺失")
        assertTrue(compatMarks.any { it.endsWith(LoadStages.INTERN) }, "去重段缺失")
    }
}
