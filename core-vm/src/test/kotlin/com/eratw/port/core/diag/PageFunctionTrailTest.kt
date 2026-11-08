package com.eratw.port.core.diag

import com.eratw.port.core.code.Assembler
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.code.VmHost
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.QueueInputSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 「本页函数流水」记录面（观察性波 obs-pagefuncs）。
 *
 * 钉死四件事（§3 出口判据逐条）：
 * 1. 进帧顺序逐字对：入口帧 / callInto 嵌套 / jumpInto 尾跳各记一次，
 *    `_RET` 退帧**不**回滚也不追加（leaveFrameCore 无记录点）；
 * 2. 页边界清零：QueueInputSink 两个等待点结算处「先交付、后清零」，
 *    连续等待点（turnMs = -1 短路）**不**清；
 * 3. 定长环绕：写满后旧的最先被挤掉、长度恒等容量、ring 数组实例不换（零分配）；
 * 4. 读侧禁抛：解析器抛错/未绑定一律降级为下标占位。
 */
class PageFunctionTrailTest {

    private companion object {
        val NAMES = listOf("ALPHA", "BETA", "GAMMA", "DELTA", "EPSILON", "ZETA")
        fun fakeNames(idx: Int): String? = NAMES.getOrNull(idx)
        fun newTrail(cap: Int = 4) = PageFunctionTrail(cap).also { it.bindNameResolver(::fakeNames) }
    }

    // ------------------------------------------------------------------
    // 环形本体：顺序 / 环绕 / 清零 / 零分配
    // ------------------------------------------------------------------

    @Test
    fun emptyTrailReadsEmptyAndClearResetsOrder() {
        val t = newTrail()
        assertEquals("", t.readout(), "空页读数是空串")
        t.record(0)
        t.record(1)
        assertEquals("ALPHA → BETA", t.readout(), "最旧到最新逐字对")
        t.clear()
        assertEquals(0, t.size, "清零即空页")
        assertEquals("", t.readout())
        // 清零后重新从头写：顺序与全新实例一致
        t.record(2)
        t.record(3)
        assertEquals("GAMMA → DELTA", t.readout())
    }

    @Test
    fun wrapEvictsOldestFirstAndSizeStaysAtCapacity() {
        val t = newTrail(cap = 4)
        for (i in 0 until 6) {
            t.record(i)
            assertEquals(minOf(i + 1, 4), t.size, "长度只增到容量后恒定（第 $i 次写入）")
        }
        assertEquals(4, t.size, "满环后长度恒等容量")
        // 记录 0..5：0、1 被挤掉，窗口 = 2..5（最旧 → 最新）
        assertEquals("GAMMA → DELTA → EPSILON → ZETA", t.readout())
    }

    @Test
    fun thousandRecordsReuseTheSameRingArray() {
        val t = PageFunctionTrail(16)
        val ringBefore = t.ring
        for (i in 0 until 1000) t.record(i and 1023)
        assertSame(ringBefore, t.ring, "record 只动下标与计数，不换数组")
        assertEquals(16, t.ring.size, "容量不随写入增长")
        // 结构性断言钉住的是「无扩容/无重分配」：数组身份不换 ⇒ 千次写入期间没有任何
        // 替代容器诞生；读侧拼接串只在 readout（低频）发生。
    }

    @Test
    fun readoutNeverThrowsEvenWhenResolverBlowsUp() {
        val t = PageFunctionTrail(4)
        t.record(7)
        assertEquals("#7", t.readout(), "未绑定解析器：下标占位")
        t.bindNameResolver { throw IllegalStateException("解析器炸了") }
        assertEquals("#7", t.readout(), "解析器抛错被吞：仍出占位")
        t.bindNameResolver { idx -> if (idx == 7) "SEVEN" else null }
        assertEquals("SEVEN", t.readout(), "正常解析生效")
    }

    // ------------------------------------------------------------------
    // VM 接线：三处进帧钩子
    // ------------------------------------------------------------------

    private object SilentHost : VmHost {
        override val evalContext: EvalContext? = null
        override fun print(text: String) {}
    }

    @Test
    fun entryFrameAndNestedCallsAppendInEnterOrderAndRetAddsNothing() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 3
              _CALL rec, 1, R1
              _HALT

            .func rec arity=1 slots=3
              _SET_INT R1, 1
              _LT R2, R0, R1
              _JUMP_IF_TRUE R2, done
              _SUB R0, R0, R1
              _CALL rec, 1, R1
            done:
              _RET R0
            """.trimIndent(),
        )
        val trail = PageFunctionTrail(64)
        trail.bindNameResolver { program.functions.getOrNull(it)?.name }
        val vm = VirtualMachine(program, SilentHost)
        vm.pageTrail = trail
        vm.run()
        // rec(3)→rec(2)→rec(1)→rec(0)：入口帧 main 一次 + rec 四次；_RET 只走 leaveFrameCore。
        assertEquals("main → rec → rec → rec → rec", trail.readout())
        assertEquals(5, trail.size)
        // 同一 VM 再跑一次 run：入口帧再次建立 ⇒ 流水继续追加（页清零归输入边界，不归 run 收尾）。
        vm.run()
        assertEquals(10, trail.size)
    }

    @Test
    fun unboundTrailMakesHooksNoOpsAndKeepsReturnValue() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL leaf, 0, R0
              _HALT

            .func leaf slots=1
              _RET R0
            """.trimIndent(),
        )
        val vm = VirtualMachine(program, SilentHost)
        assertTrue(vm.pageTrail == null, "缺省不挂")
        assertEquals(0L, vm.run())
    }

    @Test
    fun tailJumpRecordsTargetFrame() {
        // 复刻 VmJumpTest 的 main → mid --(JUMP)--> target：三段换帧都要在流水里留痕。
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.local(0))
        b.emit(Opcode._HALT)
        b.declareFunction("mid", b.here(), 0)
        b.emit(Opcode._CALL, 2, 0 or 0x40000000, SlotAddr.NONE) // JUMP 位：尾跳
        b.emit(Opcode._PRINT_LITERAL, b.string("MID-AFTER"), 1)
        b.emit(Opcode._RET, SlotAddr.NONE)
        b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(42L))
        b.emit(Opcode._RET, SlotAddr.local(1))
        b.setFunctionSlots(0, 2)
        b.setFunctionSlots(1, 2)
        b.setFunctionSlots(2, 2)
        val program = b.build(0)

        val trail = PageFunctionTrail(8)
        trail.bindNameResolver { program.functions.getOrNull(it)?.name }
        val vm = VirtualMachine(program, SilentHost)
        vm.pageTrail = trail
        vm.run()
        // 尾跳「enter 成功后登记」：mid 与 target 各一条，target 的 _RET 回 main 不留痕。
        assertEquals("main → mid → target", trail.readout())
    }

    // ------------------------------------------------------------------
    // 页边界清零：QueueInputSink 两个等待点
    // ------------------------------------------------------------------

    private val intRequest = InputRequest(
        type = InputRequest.InputType.INT,
        hasDef = true,
        defInt = 0L,
    )

    @Test
    fun waitInputDeliversBeforeClearingSoHostSeesWholePage() {
        val sink = QueueInputSink()
        val trail = newTrail(cap = 8)
        sink.pageTrail = trail
        trail.record(0)
        trail.record(1)
        var seenByHost: String? = null
        var sizeAtDelivery = -1
        sink.onTurnReady = {
            seenByHost = trail.readout()
            sizeAtDelivery = trail.size
        }
        assertTrue(sink.submit("7"), "先有提交才有页起点")
        sink.waitInput(intRequest)
        assertEquals("ALPHA → BETA", seenByHost, "交付时流水未清")
        assertEquals(2, sizeAtDelivery)
        assertEquals(0, trail.size, "等待点返回后已清零")
        assertEquals("", trail.readout())
    }

    @Test
    fun readAnyKeyAlsoClearsAtPageBoundary() {
        val sink = QueueInputSink()
        val trail = newTrail(cap = 8)
        sink.pageTrail = trail
        trail.record(2)
        assertTrue(sink.submit(""))
        sink.readAnyKey(anyKey = false, stopMesSkip = false)
        assertEquals(0, trail.size, "readAnyKey 的结算处同样清零")
    }

    @Test
    fun secondWaitingPointWithoutSubmitKeepsTrail() {
        val sink = QueueInputSink()
        val trail = newTrail(cap = 8)
        sink.pageTrail = trail
        trail.record(3)
        // waitInput 进入时 lastSubmitNs = 0 ⇒ settleTurnStart 短路 -1 ⇒ 不交付也不清零；
        // 推进靠随后 submit（另线程模拟 VM 线程阻塞在等待点）。
        val waiter = Thread { sink.waitInput(intRequest) }
        waiter.start()
        // 等 waiter 真正进入等待点（settle 已在入口短路 -1），再提交推进那一条。
        repeat(200) { if (!sink.isAwaiting) Thread.sleep(10) }
        assertTrue(sink.isAwaiting, "waiter 已进入等待点")
        sink.submit("1")
        waiter.join(5_000)
        assertTrue(!waiter.isAlive, "等待点已返回")
        assertEquals(1, trail.size, "无页起点的一拍不清流水")
        assertEquals("DELTA", trail.readout())
    }

    @Test
    fun hostCallbackThrowingStillLeadsToClearAndPropagates() {
        val sink = QueueInputSink()
        val trail = newTrail(cap = 8)
        sink.pageTrail = trail
        trail.record(0)
        sink.onTurnReady = { throw IllegalArgumentException("宿主炸了") }
        assertTrue(sink.submit("1"))
        val boom = runCatching { sink.waitInput(intRequest) }.exceptionOrNull()
        assertTrue(boom is IllegalArgumentException, "异常原样传播（语义零改动：不加吞口）")
        assertEquals(0, trail.size, "finally 保证页边界清零必达")
    }
}
