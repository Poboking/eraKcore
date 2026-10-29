package com.eratw.port.core.loader

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.stmt.FoldAuditState
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CMP-24 的可测判据：折叠审计状态在并发 seed 下不丢数、不破容器。
 *
 * ## 缺陷
 *
 * [FoldAuditState] 是 core 层静态可变全局：`folds`/`red` 用裸 `++`、`log` 是裸
 * `ArrayList`，装载线程 append 与 DevTools 线程 `drain()` 并发即竞态。BOARD 的下一步
 * 「分区并行 seed」会让**多个装载线程**同时折叠 ⇒ 丢 RED、容器损坏（`ArrayList.add`
 * 并发可抛 `ArrayIndexOutOfBoundsException`）。
 *
 * ## 修法取舍
 *
 * 选**加锁**而不是「实例态挂 store.aux」：`ArgInterner` 由
 * `Program.internStatementArgs()` 在装载期创建，那时还没有 `VariableStore`（它由
 * `GameSession` 之后才建）；`FoldAuditState` 又是装载前由宿主/装置开关、装载后取读数的
 * **进程级诊断面**，挪成实例态需要动 `Program`/`GameSession`/`GameHost`（超出本路文件面）。
 * 加锁后每个 `fold()`/`drain()` 是短临界区：并行 seed 时计数与日志线性化、`drain()`
 * 拿到一致快照；`enabled == false` 时仍然只有一个 volatile 读，零开销。
 *
 * ## 判据
 *
 * 8 线程 × 200 次折叠（全部 RED：两侧关键摘要不同）⇒ 总折叠 1600、RED 1600、
 * 明细恰好 200 条 + 一条省略行；`drain()` 清零。旧实现丢更新/丢明细/抛容器异常。
 */
class FoldAuditConcurrencyTest {

    @Test
    fun `并发折叠不丢计数不丢 RED，drain 拿一致快照`() {
        val threads = 8
        val perThread = 200
        val total = threads * perThread
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = arrayOfNulls<Throwable>(threads)
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until perThread) {
                        // 两侧 keyOf 不同 ⇒ 必进 RED（IntLiteral 的摘要就是 value）
                        FoldAuditState.fold(IntLiteral((t * perThread + i).toLong()), IntLiteral(-1L))
                    }
                } catch (x: Throwable) {
                    failures[t] = x
                } finally {
                    done.countDown()
                }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发折叠未在 60 s 内结束（疑似容器损坏/死锁）")
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        failures.forEachIndexed { i, x -> if (x != null) throw AssertionError("线程 $i 折叠时抛异常：$x", x) }

        val lines = FoldAuditState.drain()
        assertEquals(
            "fold-audit: 总折叠 $total 次，可疑（关键摘要不同）$total 次",
            lines.first(),
            "并发折叠丢了计数（旧实现是裸 ++ / 裸 ArrayList）",
        )
        assertEquals(1 + 200 + 1, lines.size, "摘要 + 200 条 RED 明细 + 1 条超限省略行")
        assertEquals(200, lines.count { it.startsWith("!!折叠") }, "RED 明细条数不许丢")
        assertTrue(lines.last().contains("超上限 200"), "超限省略行只许出现一次：${lines.last()}")

        val after = FoldAuditState.drain()
        assertEquals("fold-audit: 总折叠 0 次，可疑（关键摘要不同）0 次", after.first(), "drain 必须清零")
    }
}
