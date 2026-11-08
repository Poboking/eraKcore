package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L18（核心↔宿主边界）装载侧判据：[ProgramLoader.deferredDirectory] 的失败回滚。
 *
 * 装机首启会对整个产物目录开延迟句柄（一个句柄 = 一份 38 字节头 + 段界数组）。旧写法在第 N 个
 * 文件抛错时把前 N-1 个句柄直接丢给 GC：几千个产物就是几千个待回收的 fd，Android 的 fd 上限
 * 先到，屏上只剩「装载失败」而看不出是哪个文件坏。现在回滚就地关完再上抛，首因保持原样。
 */
class StabilityL18DeferredHandleTest {

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "WAIT 第 $i 条")))
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(dir: Path, name: String): Path {
        val f = dir.resolve(name)
        Files.write(f, ErbcWriter.encode(sampleProgram(4)))
        return f
    }

    @Test
    fun `整目录都好时按序出句柄，未物化也能逐个关闭`(@TempDir dir: Path) {
        writeArtifact(dir, "a.erbc")
        writeArtifact(dir, "b.erbc")
        val handles = ProgramLoader.deferredDirectory(dir)
        assertEquals(2, handles.size)
        ProgramLoader.closeDeferredHandles(handles)
        assertFailsWith<Exception>("回滚之后再取段数据必须失败") { handles[0].program() }
    }

    @Test
    fun `坏产物让整目录失败而不是交回半份清单`(@TempDir dir: Path) {
        writeArtifact(dir, "a.erbc")
        Files.write(dir.resolve("b.erbc"), ByteArray(64) { (it % 7).toByte() })
        assertFailsWith<Exception> { ProgramLoader.deferredDirectory(dir) }
    }

    @Test
    fun `回滚口子幂等：重复关同一句柄不再抛`(@TempDir dir: Path) {
        val handle = ProgramLoader.deferredMapped(writeArtifact(dir, "only.erbc"))
        ProgramLoader.closeDeferredHandles(listOf(handle))
        ProgramLoader.closeDeferredHandles(listOf(handle))
        ProgramLoader.closeDeferredHandles(emptyList())
    }
}
