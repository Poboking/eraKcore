package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [QueueInputSink] 的**输入流水面**单测（类 KDoc 第 10 条）。
 *
 * 这一刀是为「玩着玩着卡死 / 点了没反应」这类现场取证加的：release 包拿不到 logcat、
 * 调试通道只在 debug 包，所以「Android MotionEvent → 宿主 submit → 入队 → 等待方消费」
 * 四层里断在哪一层，必须由 sink 自己实例上的读数说清楚。本文件钉的就是这些读数的口径：
 * - **seq 对应关系**：一条提交在 [QueueInputSink.submit] 的锁内拿到单调编号，
 *   被哪一次等待吃掉 ⇒ [QueueInputSink.lastConsumedSeq] 与流水里的 `con` 行同号；
 * - **两类丢弃分开计数**：VOID 形态早退丢弃（`EmueraConsole.cs:1417-1420`）与
 *   整数解析失败丢弃（`:1125-1128`）各计各的、各带最后一条的编号，`droppedCount` 只做汇总；
 * - **环形窗口定长**：写满 [QueueInputSink.trailCapacity] 后新值覆盖最旧，条数不超容量，
 *   计数仍继续累加（观察面不能变成新的常驻账本 —— 本仓 OOM 账的起点就是「没人记账的累加」）；
 * - **抢跑提交**（无等待方时先入队，类 KDoc 第 1 条）同样能对上 seq。
 *
 * 语义本身（等待判定、超时先后、默认值交付、`mesSkip` 记账、`close` 打断）由
 * [QueueInputSinkTest] / [InputKindModelingTest] / [InputQueueConsumptionTest] 钉住，
 * 本文件只额外确认「加账不改序」：每条用例都同时断言交付值与流水读数。
 *
 * 自包含：不依赖语料环境变量；需要越过截止点的用例注入假单调时钟（每读一次前进 1 秒），
 * 线程类用例一律带 join 超时 —— 红要红成断言，不要红成挂住。
 */
class InputTrailSequenceTest {

    /** 假单调时钟（纳秒）：每被读一次前进 1 秒 ⇒ 进入等待后的第一次截止判定必然越过 deadline。 */
    private fun clockOf(stepNanos: Long): () -> Long {
        val now = AtomicLong(0L)
        return { now.addAndGet(stepNanos) }
    }

    private fun intReq(
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        type: InputRequest.InputType = InputRequest.InputType.INT,
        timeLimit: Long = -1L,
    ) = InputRequest(type = type, form = form, timeLimit = timeLimit)

    /** 等等待方进入等待态（带超时，红要红成断言而不是挂住）。 */
    private fun awaitAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) Thread.sleep(5)
        assertTrue(sink.isAwaiting, "等待方未进入等待态")
    }

    // ------------------------------------------------------------------
    // seq：入队 → 被下一次等待消费 的对应关系
    // ------------------------------------------------------------------

    /**
     * 三条抢跑提交（FIFO）被三次等待逐条吃掉：`lastSubmittedSeq` 随提交走、
     * `lastConsumedSeq` 随消费走，流水里同一号的 `enq` 与 `con` 一一配对。
     */
    @Test
    fun `入队 seq 被下一次等待按序消费 con 与 enq 同号`() {
        val sink = QueueInputSink()
        assertEquals(0L, sink.lastSubmittedSeq, "新 sink 一条提交都没发过")
        assertEquals(0L, sink.lastConsumedSeq)

        assertTrue(sink.submitInt(1))
        assertTrue(sink.submitInt(2))
        assertTrue(sink.submitInt(3))
        assertEquals(3L, sink.lastSubmittedSeq)
        assertEquals(0L, sink.lastConsumedSeq, "没有等待方时 seq 只算发出、不算消费")
        assertEquals(3, sink.pendingCount)

        assertEquals(1L, sink.waitInput(intReq()).intValue)
        assertEquals(1L, sink.lastConsumedSeq, "第 1 条被第 1 次等待吃掉")
        assertEquals(2L, sink.waitInput(intReq()).intValue)
        assertEquals(2L, sink.lastConsumedSeq)
        assertEquals(3L, sink.waitInput(intReq()).intValue)
        assertEquals(3L, sink.lastConsumedSeq)

        assertEquals(
            listOf(
                "#1 enq len=1 skip=false",
                "#2 enq len=1 skip=false",
                "#3 enq len=1 skip=false",
                "#1 con @wait VALUE/INT",
                "#2 con @wait VALUE/INT",
                "#3 con @wait VALUE/INT",
            ),
            sink.recentInputTrail(),
            "流水按发生序给：三条抢跑入队在前，三次消费按同号顺序跟上",
        )
        assertEquals(0L, sink.droppedCount)
        assertEquals(0, sink.pendingCount)
    }

    /** `readAnyKey`（WAIT 家族）的消费同样计数，等待点标识为 `@anykey`。 */
    @Test
    fun `抢跑提交先入队后由 readAnyKey 吃掉 seq 照样对上`() {
        val sink = QueueInputSink()
        assertTrue(sink.submit("wait"))
        assertEquals(1L, sink.lastSubmittedSeq)
        assertEquals(0L, sink.lastConsumedSeq, "无等待方期间只有 enq")
        assertEquals(listOf("#1 enq len=4 skip=false"), sink.recentInputTrail())

        sink.readAnyKey(anyKey = true)
        assertEquals(1L, sink.lastConsumedSeq)
        assertEquals(0, sink.pendingCount)
        assertEquals(
            listOf("#1 enq len=4 skip=false", "#1 con @anykey"),
            sink.recentInputTrail(),
        )
    }

    /** `keySkip` 进流水：入队行带 skip 标志（原版 PressEnterKey 的第一参，:1371-1373）。 */
    @Test
    fun `enq 行带出本次提交的 skip 标志`() {
        val sink = QueueInputSink()
        sink.submit("", mesSkip = true)
        sink.readAnyKey()
        assertEquals(listOf("#1 enq len=0 skip=true", "#1 con @anykey"), sink.recentInputTrail())
        assertTrue(sink.consumeMesSkip(), "跳过记账不受流水影响（:1373）")
        assertFalse(sink.consumeMesSkip(), "读过即清（原口径不变）")
    }

    /** 跨线程：VM 线程等待、UI 线程连点两次 —— 第一条被吃掉、第二条带自己的 seq 留在队里。 */
    @Test
    fun `跨线程连点 第一条被当次等待吃掉 第二条 seq 留给下一次等待`() {
        val sink = QueueInputSink()
        val outcome = AtomicReference<com.eratw.port.core.host.InputOutcome?>()
        val t = thread(name = "trail-vm-waiter") {
            runCatching { sink.waitInput(intReq()) }.onSuccess { outcome.set(it) }
        }
        awaitAwaiting(sink)
        sink.submitInt(5)
        sink.submitInt(6)
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
        assertEquals(5L, outcome.get()?.intValue, "队首先被消费（FIFO 原口径）")
        assertEquals(2L, sink.lastSubmittedSeq, "两条都发了号")
        assertEquals(1L, sink.lastConsumedSeq, "只有第 1 条被这次等待吃掉")
        assertEquals(1, sink.pendingCount, "第 2 条留给下一次等待，不丢")
        assertEquals(6L, sink.waitInput(intReq()).intValue)
        assertEquals(2L, sink.lastConsumedSeq, "下一次等待对上第 2 条的号")
    }

    // ------------------------------------------------------------------
    // 丢弃：两类分开计数
    // ------------------------------------------------------------------

    /**
     * VOID 形态：键盘提交在出队之前就早退（`EmueraConsole.cs:1417-1420`）⇒ 每条都计进
     * `dropVoidCount`，**不**进 `dropParseCount`；等待本身靠超时推进（`endTimer` :1043/:1068），
     * 没有提交被吃掉 ⇒ `lastConsumedSeq` 保持 0，流水用不带 seq 的 `tmo` 行记这一次推进。
     */
    @Test
    fun `VOID 丢弃逐条计数 与解析失败分类不串`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("9")
        sink.submit("", mesSkip = true)
        val out = sink.waitInput(intReq(form = InputRequest.RequestForm.VOID, timeLimit = 100))
        assertTrue(out.isTimeout, "VOID 只由超时推进")
        assertFalse(out.writesInt && out.writesStr)

        assertEquals(0, sink.pendingCount, "两条都被丢弃，不留给下一次等待（:1417-1420）")
        assertEquals(2L, sink.dropVoidCount)
        assertEquals(2L, sink.lastDropVoidSeq)
        assertEquals(0L, sink.dropParseCount, "VOID 丢弃不得混进解析失败计数")
        assertEquals(0L, sink.lastDropParseSeq)
        assertEquals(2L, sink.droppedCount, "汇总读数 = 两类之和")
        assertEquals(2L, sink.lastSubmittedSeq)
        assertEquals(0L, sink.lastConsumedSeq, "超时推进没吃提交 ⇒ 不产生 con")
        assertTrue(sink.consumeMesSkip(), "丢弃路仍照 :1373 记跳过标志（语义未变）")
        assertEquals(
            listOf(
                "#1 enq len=1 skip=false",
                "#2 enq len=0 skip=true",
                "#1 drop(void)",
                "#2 drop(void)",
                "tmo @wait VOID",
            ),
            sink.recentInputTrail(),
        )
    }

    /**
     * 整数解析失败：出队了却没交付（`EmueraConsole.cs:1125-1128` 的 `return false`）⇒
     * 记 `drop(parse)`、计数进 `dropParseCount`，**不算**消费；随后那条才是 `con`。
     */
    @Test
    fun `解析失败丢弃出队那条 con 只记真正推进的那条`() {
        val sink = QueueInputSink()
        sink.submit("abc")
        sink.submit("7")
        assertEquals(7L, sink.waitInput(intReq()).intValue, "丢弃非法数字后取下一条（原口径）")

        assertEquals(1L, sink.dropParseCount)
        assertEquals(1L, sink.lastDropParseSeq, "最后一次解析失败的就是第 1 条")
        assertEquals(0L, sink.dropVoidCount)
        assertEquals(1L, sink.droppedCount)
        assertEquals(2L, sink.lastConsumedSeq, "推进等待的是第 2 条")
        assertEquals(2L, sink.lastSubmittedSeq)
        assertEquals(0, sink.pendingCount)
        assertEquals(
            listOf(
                "#1 enq len=3 skip=false",
                "#2 enq len=1 skip=false",
                "#1 drop(parse len=3)",
                "#2 con @wait VALUE/INT",
            ),
            sink.recentInputTrail(),
        )
    }

    /** 同一个 sink 里两类丢弃各自累加、各带最后一条的号；汇总值只增不减。 */
    @Test
    fun `两类丢弃在同一实例上分别累加 汇总读数只增`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("9") // seq1 → VOID 早退丢弃
        sink.waitInput(intReq(form = InputRequest.RequestForm.VOID, timeLimit = 100))
        assertEquals(1L, sink.dropVoidCount)
        assertEquals(0L, sink.dropParseCount)
        assertEquals(1L, sink.droppedCount)

        sink.submit("xy") // seq2 → 解析失败丢弃
        sink.submit("4") // seq3 → 消费
        assertEquals(4L, sink.waitInput(intReq()).intValue)

        assertEquals(1L, sink.dropVoidCount, "解析失败不得加到 VOID 计数上")
        assertEquals(1L, sink.lastDropVoidSeq)
        assertEquals(1L, sink.dropParseCount)
        assertEquals(2L, sink.lastDropParseSeq)
        assertEquals(2L, sink.droppedCount, "两类之和")
        assertEquals(3L, sink.lastSubmittedSeq)
        assertEquals(3L, sink.lastConsumedSeq)
        assertEquals(3, sink.recentInputTrail().count { it.contains(" enq ") }, "三条提交三行 enq")
        assertEquals(2, sink.recentInputTrail().count { it.contains("drop(") }, "两类丢弃各一行")
    }

    // ------------------------------------------------------------------
    // 环形窗口：定长 + 新值覆盖旧值
    // ------------------------------------------------------------------

    /** 写满窗口后条数封顶、只保留最近 [QueueInputSink.trailCapacity] 行；计数仍继续累加。 */
    @Test
    fun `环形窗口不超容量 新值覆盖最旧而计数只增`() {
        val sink = QueueInputSink()
        assertEquals(16, sink.trailCapacity)
        for (i in 1..25) {
            sink.submitInt(i.toLong())
            assertEquals(i.toLong(), sink.waitInput(intReq()).intValue)
        }
        val trail = sink.recentInputTrail()
        assertEquals(sink.trailCapacity, trail.size, "窗口定长：25 对流水只留最近 16 行")
        assertEquals("#18 enq len=2 skip=false", trail.first(), "最旧 17 对已被覆盖")
        assertEquals("#25 con @wait VALUE/INT", trail.last())
        assertTrue(trail.none { it.startsWith("#17") }, "被覆盖的旧行不得复活")
        assertEquals(25L, sink.lastSubmittedSeq, "seq 与计数不受窗口封顶影响")
        assertEquals(25L, sink.lastConsumedSeq)
        assertEquals(0L, sink.droppedCount)
        assertEquals(0, sink.pendingCount)
    }

    /** 空账本：新 sink 的流水是空表，读数全 0（不给报告留 null 分支）。 */
    @Test
    fun `新实例的流水读数为空且不报错`() {
        val sink = QueueInputSink()
        assertEquals(emptyList(), sink.recentInputTrail())
        assertEquals(0L, sink.lastSubmittedSeq)
        assertEquals(0L, sink.lastConsumedSeq)
        assertEquals(0L, sink.droppedCount)
        val readout = sink.trailReadout()
        assertEquals(0L, readout.lastSubmittedSeq)
        assertNull(readout.awaitingTag, "不在等待中 ⇒ 等待点标识为 null")
        assertFalse(readout.isAwaiting)
        assertFalse(readout.isClosed)
        assertTrue(readout.trail.isEmpty())
    }

    /** 队列已关闭时的提交不发号、不计丢弃（它连 sink 都没进，属宿主销毁期的正常丢弃）。 */
    @Test
    fun `关闭后的提交不发号也不计入丢弃`() {
        val sink = QueueInputSink()
        sink.submit("1")
        sink.close()
        assertFalse(sink.submit("2"), "关闭后提交返回 false（原口径）")
        assertEquals(1L, sink.lastSubmittedSeq, "关闭后的提交不占号")
        assertEquals(0L, sink.droppedCount)
        assertEquals(listOf("#1 enq len=1 skip=false"), sink.recentInputTrail())
        assertTrue(sink.trailReadout().isClosed)
    }

    // ------------------------------------------------------------------
    // 等待点标识 + 快照一致性
    // ------------------------------------------------------------------

    /** 非取值形态的 `con` 行只写形态（形态本身单义，值通道无意义）。 */
    @Test
    fun `con 行的等待点标识按形态给 取值形态才带值通道`() {
        val enter = QueueInputSink()
        enter.submit("随便")
        val out = enter.waitInput(intReq(form = InputRequest.RequestForm.ENTER_KEY))
        assertFalse(out.writesInt && out.writesStr, "EnterKey 不写槽（:1276-1287，语义未变）")
        assertEquals(listOf("#1 enq len=2 skip=false", "#1 con @wait ENTER_KEY"), enter.recentInputTrail())

        val str = QueueInputSink()
        str.submit("あ")
        str.waitInput(intReq(type = InputRequest.InputType.STR))
        assertEquals(
            listOf("#1 enq len=1 skip=false", "#1 con @wait VALUE/STR"),
            str.recentInputTrail(),
        )

        val any = QueueInputSink()
        any.submit("42")
        any.waitInput(intReq(form = InputRequest.RequestForm.ANY_VALUE))
        assertEquals(
            listOf("#1 enq len=2 skip=false", "#1 con @wait ANY_VALUE"),
            any.recentInputTrail(),
        )
    }

    /** 快照与逐项读数同源；等待进行中的快照带得出当前等待点（真机卡死时的第一手证据）。 */
    @Test
    fun `trailReadout 与逐项读数一致 等待中可见等待点`() {
        val sink = QueueInputSink()
        val outcome = AtomicReference<com.eratw.port.core.host.InputOutcome?>()
        val t = thread(name = "trail-readout-waiter") {
            runCatching { sink.waitInput(intReq(type = InputRequest.InputType.STR)) }
                .onSuccess { outcome.set(it) }
        }
        awaitAwaiting(sink)
        // 快照在提交**之前**读：先钉住「等待进行中的快照带得出当前等待点」，
        // 免得提交一落地、等待方推进并清空 awaiting，读数就变成竞态。
        val during = sink.trailReadout()
        assertEquals("@wait VALUE/STR", during.awaitingTag, "快照要能说出卡在哪个等待点")
        assertTrue(during.isAwaiting)
        assertEquals(0L, during.lastSubmittedSeq, "此时还没有任何提交")
        sink.submit("答案")
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals("答案", outcome.get()?.strValue, "交付值不受流水影响")

        val after = sink.trailReadout()
        assertEquals(after.lastSubmittedSeq, sink.lastSubmittedSeq)
        assertEquals(after.lastConsumedSeq, sink.lastConsumedSeq)
        assertEquals(after.dropVoidCount, sink.dropVoidCount)
        assertEquals(after.lastDropVoidSeq, sink.lastDropVoidSeq)
        assertEquals(after.dropParseCount, sink.dropParseCount)
        assertEquals(after.lastDropParseSeq, sink.lastDropParseSeq)
        assertEquals(after.droppedCount, sink.droppedCount)
        assertEquals(after.pendingCount, sink.pendingCount)
        assertEquals(sink.recentInputTrail(), after.trail, "快照与逐项读数同源")
        assertEquals(
            listOf("#1 enq len=2 skip=false", "#1 con @wait VALUE/STR"),
            after.trail,
            "整条链路只一次入队、一次消费，seq 对上",
        )
        assertNull(after.awaitingTag, "离开等待点后快照回到 null")
    }
}
