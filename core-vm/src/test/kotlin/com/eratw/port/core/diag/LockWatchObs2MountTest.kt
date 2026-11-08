package com.eratw.port.core.diag

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OBS2-L2 两个新挂载点的**行为面**单测（契约 §2 L2 第 1/3 项；源码形状钉子在
 * [LockWatchMountPinTest]，那边钉「摘掉即红」，这边钉「挂上不许改语义」）。
 *
 * 三条判据，逐个挂载点各跑一遍：
 * 1. **关时零行为**：`enabled = false` 时把挂载点真的跑一遍，采样总数必须一条都不涨
 *    （守卫是 [LockWatch.handleOrNull]，关时连 `System.nanoTime()` 都不取，
 *    口径同 `core/proc/ProcessRuntime.kt:1138` 的 statementProbe 守卫）；
 * 2. **开时逐条记账 + tag 是人话**：写侧入列 tag = 本次行数、读侧快照 tag = 拷出行数、
 *    逐条物化 tag = 1、建片趟 tag = 本趟条数（口径见类 KDoc 挂载点表的 tag 列）；
 * 3. **挂载前后逐字同结果**：同一装置跑两遍（一遍关、一遍开），显示行内容 / 语句实例身份
 *    与条数完全一致 —— 本波是观测波，零语义位移是判据。
 *
 * 另有一条**桌面旋钮自证对**（[桌面旋钮开时新挂载点真的在记] / [桌面旋钮没传时全进程仍是缺省关]）：
 * 带 `-Plockwatch=true` 与不带各跑一次时，两条用例一条执行一条跳过，读数差就是自证
 * （契约 §4：`core-vm/build.gradle.kts:131` 把 gradle 属性透传成 test JVM 性质）。
 */
class LockWatchObs2MountTest {

    /** 真跑一遍 `sink.display` 的两个临界区：一次入列（写侧）+ 一次快照（读侧）。 */
    private fun runDisplayMount(rows: Int): List<String> {
        val sink = LineBufferConsoleSink(clientWidth = 40)
        repeat(rows) { i -> sink.printSingleLine("OBS2 第 $i 行") }
        return sink.lines().map { sink.textOf(it) }
    }

    /** 最小可装载产物（形状照 `core/loader/LazyStatementConcurrencyTest` 的装置，只是条数更少）。 */
    private fun writeArtifact(dir: Path, statements: Int): Path {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val ids = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            ids.add(
                b.statement(
                    InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "WAIT 第 $i 条 / obs2-$i"),
                ),
            )
        }
        for (k in ids.indices) b.emit(Opcode.WAIT, ids[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        val f = dir.resolve("program.erbc")
        Files.write(f, ErbcWriter.encode(b.build(0)))
        return f
    }

    private fun rowOf(lockId: Int): LockWatch.LockSample? =
        LockWatch.snapshot().firstOrNull { it.lockId == lockId }

    @Test
    fun `sink display 关时零行为 开时逐条记账`() {
        val saved = LockWatch.enabled
        try {
            LockWatch.enabled = false
            LockWatch.reset()
            val offRows = runDisplayMount(3)
            assertEquals(0L, LockWatch.totalSamples(), "缺省关时 sink.display 一条都不记（零开销守卫）")
            assertFalse(LockWatch.hasSamples(LockWatch.ID_SINK_DISPLAY))

            LockWatch.enabled = true
            LockWatch.reset()
            runDisplayMount(3)
            // 写侧 3 次入列 + 读侧 1 次快照 = 4 条样本（同一挂载点、同一线程名聚在一行）
            val row = rowOf(LockWatch.ID_SINK_DISPLAY)
            assertTrue(row != null && row.samples == 4L, "开时应记 4 条（3 次入列 + 1 次快照），实读 ${row?.samples}")
            // tag：读侧那一次拷出 3 行 ⇒ 最长持有的 tag 落在 1 或 3（哪一发最久由机器决定），
            // 但**必须**是这两者之一 —— 出现别的数就说明 tag 取错了对象。
            assertTrue(
                row.maxHoldTag == 1L || row.maxHoldTag == 3L,
                "sink.display 的 tag 只能是行数（入列 1 / 快照 3），实读 ${row.maxHoldTag}",
            )
            assertTrue(LockWatch.readout().contains("sink.display"), "读数行要带挂载点名")
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }

    @Test
    fun `sink display 挂载前后显示行逐字不变`() {
        val saved = LockWatch.enabled
        try {
            LockWatch.enabled = false
            val off = runDisplayMount(5)
            LockWatch.enabled = true
            val on = runDisplayMount(5)
            LockWatch.enabled = false
            val offAgain = runDisplayMount(5)
            assertEquals(off, on, "挂上探针后显示行逐字不变（观察面不得反噬排版与入列）")
            assertEquals(off, offAgain, "摘掉探针后回到同一份结果")
            assertEquals(5, off.size, "装置本身要有效：5 次 printSingleLine 应落 5 行")
            assertTrue(off.all { it.contains("OBS2 第") }, "行文本口径：$off")
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }

    @Test
    fun `erbc materialize lock 装载期零物化也零样本`(@TempDir dir: Path) {
        val saved = LockWatch.enabled
        try {
            LockWatch.enabled = true
            LockWatch.reset()
            val f = writeArtifact(dir, 8)
            val program = ProgramLoader.loadMapped(f)
            assertEquals(0, program.materializedStatementCount, "装载完不许物化（否则测不到首取）")
            assertFalse(
                LockWatch.hasSamples(LockWatch.ID_ERBC_MATERIALIZE_LOCK),
                "装载期（解码 + 种子去重 + releaseRaws）不许在 materializeLock 挂载点留下样本：" +
                    "本波只钉 statementAt 与建片趟两处，另外两处临界区没有探针",
            )
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }

    @Test
    fun `erbc materialize lock 逐条物化与建片趟各记一条 tag 是条数`(@TempDir dir: Path) {
        val saved = LockWatch.enabled
        try {
            val f = writeArtifact(dir, 8)
            val program = ProgramLoader.loadMapped(f)

            // ① 关时：首取真跑，一条都不记
            LockWatch.enabled = false
            LockWatch.reset()
            val first = program.statement(3)
            assertEquals(0L, LockWatch.totalSamples(), "缺省关时 erbc.materialize.lock 一条都不记")

            // ② 开时：逐条物化 = 1 条样本、tag = 1（本段只解一条）
            LockWatch.enabled = true
            LockWatch.reset()
            val again = program.statement(4)
            val row = rowOf(LockWatch.ID_ERBC_MATERIALIZE_LOCK)
            assertTrue(row != null && row.samples == 1L, "首取一条语句应记 1 条样本，实读 ${row?.samples}")
            assertEquals(1L, row.maxHoldTag, "逐条物化的 tag = 锁内解码条数 = 1")

            // ③ 实例身份契约不变（同下标恒同一实例，CMP-56）
            val same = program.statement(3)
            assertSameInstance(first, same, "挂探针后同下标仍同一实例")
            assertSameInstance(again, program.statement(4), "第二次取（快路径）不许换实例")

            // ④ 建片趟：一次建片一条样本、tag = 本趟条数。
            //    走的是生产同一条路（`Program.buildSegmentedFlatSource` → `SegmentedFlatStmtSource.blockFor`
            //    → `ConstantPool.buildFlatStatementsRange`），不是把内部函数硬调出来。
            LockWatch.reset()
            val src = program.buildSegmentedFlatSource(segmentSize = 8, maxSegments = 1)
            val ops = (0 until 8).map { src.opAt(it) }
            val flatRow = rowOf(LockWatch.ID_ERBC_MATERIALIZE_LOCK)
            assertTrue(
                flatRow != null && flatRow.samples == 1L,
                "建第一片应记 1 条样本（片内 8 条一次压扁），实读 ${flatRow?.samples}",
            )
            assertEquals(8L, flatRow.maxHoldTag, "建片趟的 tag = 本趟解码条数（0..8）")
            assertEquals(
                ops, (0 until 8).map { src.opAt(it) },
                "建片后第二次读逐值相同（LRU 命中同一片；探针不许把片换成另一份）",
            )
        } finally {
            LockWatch.enabled = saved
            LockWatch.reset()
        }
    }

    private fun assertSameInstance(expected: Statement, actual: Statement, message: String) {
        if (expected !== actual) throw AssertionError("$message：期望同一实例，实际不是")
    }

    // ---- 桌面旋钮自证对（契约 §2 L2 验收：带 -Plockwatch=true 与不带各跑一次） ----

    /**
     * 带 `-Plockwatch=true`：性质即初值 ⇒ 新挂载点开箱就在记。
     * 不带时本条**跳过**（不是红），配对的另一半在下面。
     */
    @Test
    fun `桌面旋钮开时新挂载点真的在记`() {
        val knob = System.getProperty(LockWatch.PROPERTY_ENABLED)?.trim()?.toBoolean() == true
        Assumptions.assumeTrue(
            knob,
            "本用例只在 -Plockwatch=true 下执行（${LockWatch.PROPERTY_ENABLED} 没传 ⇒ 走配对的下一条）",
        )
        assertTrue(LockWatch.enabled, "性质传了 true，object 初值就必须是挂载态")
        LockWatch.reset()
        runDisplayMount(2)
        assertTrue(
            LockWatch.hasSamples(LockWatch.ID_SINK_DISPLAY),
            "桌面旋钮必须真的把 sink.display 挂上（读不到 = build.gradle.kts:131 的透传断了）",
        )
    }

    /**
     * 不带旋钮：全进程必须是**缺省关**，且挂载点零行为。
     * 带 `-Plockwatch=true` 时本条跳过（与上一条成对，两次跑动的执行/跳过读数就是自证）。
     *
     * 口径说清楚：本条同时是「不许有人把 `LockWatch.attach()` 漏在装配期」的守卫 ——
     * 性质没传却读到 enabled = true，只可能是有人在别处挂了它（全进程一档，
     * 见类 KDoc「为什么必须是进程级 object」第 3 点）。
     */
    @Test
    fun `桌面旋钮没传时全进程仍是缺省关`() {
        val knob = System.getProperty(LockWatch.PROPERTY_ENABLED)?.trim()?.toBoolean() == true
        Assumptions.assumeFalse(
            knob,
            "本用例只在**不带** -Plockwatch=true 时执行（带了 ⇒ 走配对的上一条）",
        )
        assertFalse(LockWatch.enabled, "性质没传就必须是缺省关（装配期不许自动挂载）")
        LockWatch.reset()
        runDisplayMount(2)
        assertEquals(0L, LockWatch.totalSamples(), "缺省关时两个新挂载点一条都不记")
    }
}
