package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CMP-56 的可测判据：惰性语句池的**取用面**必须并发安全。
 *
 * ## 缺陷
 *
 * `LazyStatementRecords.statementAt` 先在锁外读 `cached[index]`、再整条解码并写回，
 * 全程无同步，而底下共享的有三样可变状态：
 * - `cached` 条目表：并发同下标 ⇒ 两个线程都看到 null ⇒ **同一语句下标解出两个实例**
 *   （运行期数据节以 `Statement` 实例为键 ⇒ 另一实例取不到数据）；
 * - `RecordSource.nodes`（惰性节点表）与它的 `ensureDepth`：并发补节点互相覆盖；
 * - 段 3 的 `SectionWindow` 与串表 `scratch`：两条记录的串字节混成一条 ⇒ 解错原文。
 *
 * ## 判据
 *
 * 1. **同下标并发恒同一实例**：16 个线程同时首次取同一下标，必须全部 `assertSame`；
 * 2. **不同下标并发与整池档逐字段一致**：8 线程各取一组下标，`raw`/`line`/类必须等于
 *    `ProgramLoader.loadMappedEager`（整池顺序解码）的读数 —— 串字节混流会直接改 `raw`。
 *
 * 修法见 [ErbcStream] 的 `LazyStatementRecords`：条目槽换 `AtomicReferenceArray`（只有
 * 快路径的元素读，无锁、volatile 语义），慢路径（解码 + 去重 + 写回）与
 * `releaseRaws`/`seedInterning` 共用一把实例锁 ⇒ 上面三样共享状态全部串行化。
 */
class LazyStatementConcurrencyTest {

    private fun sampleProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(
                b.statement(
                    // 每条语句的原文都不同：串表 scratch 混流时 raw 会当场对不上。
                    InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "WAIT 第 $i 条 / raw-$i"),
                ),
            )
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(dir: Path, statements: Int = 48): Path {
        val f = dir.resolve("program.erbc")
        Files.write(f, com.eratw.port.core.code.ErbcWriter.encode(sampleProgram(statements)))
        return f
    }

    /** 起 [n] 个线程，在同一个闩锁上同时开跑，收集异常与结果。 */
    private fun race(
        n: Int,
        body: (Int) -> Unit,
    ): Array<Throwable?> {
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val failures = arrayOfNulls<Throwable>(n)
        val workers = (0 until n).map { i ->
            Thread {
                try {
                    start.await()
                    body(i)
                } catch (t: Throwable) {
                    failures[i] = t
                } finally {
                    done.countDown()
                }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发取语句未在 60 s 内结束（疑似死锁）")
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        return failures
    }

    @Test
    fun `同一下标并发首取只产出一个实例`(@TempDir dir: Path) {
        val f = writeArtifact(dir)
        val lazy = ProgramLoader.loadMapped(f)
        assertEquals(0, lazy.materializedStatementCount, "装载完不许物化（否则测不到并发首取）")
        val index = 7
        val threads = 16
        val results = arrayOfNulls<Statement>(threads)
        val failures = race(threads) { i -> results[i] = lazy.statement(index) }
        failures.forEachIndexed { i, t -> assertNull(t, "线程 $i 取语句 $index 抛异常：$t") }
        val first = results[0] ?: error("线程 0 没拿到语句")
        for (i in 1 until threads) {
            assertSame(
                first,
                results[i],
                "同一下标并发首取解出了两个 Statement 实例（线程 $i）：运行期数据节以实例为键，另一份会静默丢数据",
            )
        }
        assertEquals("WAIT 第 7 条 / raw-7", first.raw)
    }

    @Test
    fun `不同下标并发取用与整池顺序解码逐条一致`(@TempDir dir: Path) {
        val f = writeArtifact(dir)
        val eager = ProgramLoader.loadMappedEager(f)
        val lazy = ProgramLoader.loadMapped(f)
        val count = lazy.statementCount
        assertEquals(eager.statementCount, count)
        val threads = 8
        val results = arrayOfNulls<Statement>(count)
        val failures = race(threads) { t ->
            // 每个线程从不同起点绕环走一圈：相邻线程同时打同一段记录区/节点表/串表。
            var i = t
            while (i < count) {
                results[i] = lazy.statement(i)
                i += threads
            }
        }
        failures.forEachIndexed { i, x -> assertNull(x, "线程 $i 并发取语句抛异常：$x") }
        for (i in 0 until count) {
            val actual = results[i] ?: error("语句 $i 没有被取到")
            val expected = eager.statement(i)
            assertEquals(expected.javaClass, actual.javaClass, "语句 $i 类")
            assertEquals(expected.line, actual.line, "语句 $i 行号")
            assertEquals(expected.raw, actual.raw, "语句 $i 原文（串表 scratch 混流会在这里露馅）")
            assertSame(actual, lazy.statement(i), "语句 $i 并发之后仍必须同一下标恒同一实例")
        }
    }
}
