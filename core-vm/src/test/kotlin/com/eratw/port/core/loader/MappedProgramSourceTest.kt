package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CMP-20 的可测判据：`MappedProgramSource` 的延迟物化句柄。
 *
 * ## 缺陷（BOARD CMP-20）
 *
 * `cached` 不是 `@Volatile` 却在 `program()` 的锁外快路径直接读；`close()` 也从不把
 * `file` 置 null ⇒「句柄不留强引用」不成立，惰性产物丢掉 Program 之后段 3 映射与 fd
 * 仍被句柄钉着。两项已在 `96664b02`（CMP-11/14/15 那笔）落地，**本类只补判据**，
 * 不重复改产品代码。
 *
 * ## 钉死的性质
 *
 * 1. 那两个字段的 `volatile` 修饰符就在字节码里（删掉即红，不靠并发运气）；
 * 2. [MappedProgramSource.close] 之后 `file == null`，但惰性 Program 的解码路径
 *    （由 Program 自己持有的 `RecordSource`）照常可用 —— 通道寿命只跟 Program 走；
 * 3. 并发首次物化只产出一个 Program 实例（`Statement` 池以实例为键，不能有第二份）。
 */
class MappedProgramSourceTest {

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(
                b.statement(
                    InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "WAIT 第 $i 条"),
                ),
            )
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(dir: Path): Path {
        val f = dir.resolve("program.erbc")
        Files.write(f, com.eratw.port.core.code.ErbcWriter.encode(sampleProgram(8)))
        return f
    }

    private fun field(name: String) =
        MappedProgramSource::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test
    fun `cached 与 file 是 volatile 字段（CMP-20 的双检锁前提）`() {
        assertTrue(
            Modifier.isVolatile(field("cached").modifiers),
            "program() 的锁外快路径读 cached：没有 @Volatile，JMM 允许 close 之后仍读到旧值/二次物化",
        )
        assertTrue(
            Modifier.isVolatile(field("file").modifiers),
            "file 在锁外被 fileOrThrow() 读：没有 @Volatile，close 的置空对其它线程不可见",
        )
    }

    @Test
    fun `close 之后句柄不再强引用映射，而惰性 Program 的解码路径照常可用`(@TempDir dir: Path) {
        val f = writeArtifact(dir)
        val source = ProgramLoader.deferredMapped(f)
        val program = source.program()
        assertSame(program, source.program(), "同一句柄重复物化必须返回同一实例")
        assertTrue(program.holdsLazyStatementPool, "样本必须走惰性档（否则通道在 close 前就关了，测不到句柄引用）")

        source.close()

        assertNull(
            field("file").get(source),
            "CMP-20：close 之后句柄必须把 file 置 null（不许留映射/通道的强引用）",
        )
        assertTrue(source.isClosed, "句柄自身立即失效")
        // 通道由 Program 接管（惰性形态不关）：已建映射仍可读，按需解码不许断。
        val statement = program.statement(3)
        assertEquals("WAIT 第 3 条", statement.raw, "close 之后 Program 的惰性语句解码必须照常工作")
        assertSame(statement, program.statement(3), "同一下标恒返回同一实例（契约 C2）")

        val e = assertFailsWith<ErbcLoadException>("close 之后的句柄读数必须被拒绝，而不是二次物化") {
            source.program()
        }
        assertTrue(
            e.message!!.contains("句柄已关闭"),
            "惰性产物的关闭理由要与「映射已关闭」区分开，实际：${e.message}",
        )
    }

    @Test
    fun `并发首次物化只产出一个 Program 实例`(@TempDir dir: Path) {
        val f = writeArtifact(dir)
        val source = ProgramLoader.deferredMapped(f)
        val threads = 16
        val start = CountDownLatch(1)
        val results = arrayOfNulls<Program>(threads)
        val failures = arrayOfNulls<Throwable>(threads)
        val workers = (0 until threads).map { i ->
            Thread {
                try {
                    start.await()
                    results[i] = source.program()
                } catch (t: Throwable) {
                    failures[i] = t
                }
            }.apply { start() }
        }
        start.countDown()
        for (t in workers) t.join(TimeUnit.SECONDS.toMillis(30))
        failures.forEachIndexed { i, t -> assertNull(t, "线程 $i 首次物化失败：$t") }
        val first = results[0] ?: error("线程 0 没有拿到 Program")
        for (i in 1 until threads) {
            assertSame(first, results[i], "线程 $i 拿到的 Program 不是同一实例（语句池身份表不允许第二份）")
        }
        source.close()
    }
}
