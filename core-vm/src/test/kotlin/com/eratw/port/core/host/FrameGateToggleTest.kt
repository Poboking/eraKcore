package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「一屏一帧」（CONSOLE-FRAMEGATE）开关的语义测试。
 *
 * 契约（[LineBufferConsoleSink.frameGateEnabled]）：
 * - true（缺省）= 既有冻结语义：`holdFrame()` 之后可见变更只记脏，
 *   `publishFrame()` 时一次性 bump [LineBufferConsoleSink.revision]；
 * - false = 永不冻结：`holdFrame()` 不生效，任何可见变更立即 bump revision（旧行为，
 *   2026-10-23 真机「画面卡在上一帧」的逃生阀）。
 */
class FrameGateToggleTest {

    @Test
    fun gateEnabledFreezesUntilPublish() {
        val s = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = true)
        val before = s.revision

        s.holdFrame()
        s.print("abc")
        s.newLine()
        assertEquals(before, s.revision, "冻结态下可见变更不应发布（只记 gateDirty）")

        s.publishFrame()
        assertEquals(before + 1, s.revision, "解冻时把冻结期间的变更一次性发布")
    }

    @Test
    fun gateDisabledNeverFreezes() {
        val free = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = false)
        val gated = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = true)

        free.holdFrame()
        gated.holdFrame()
        free.print("abc")
        free.newLine()
        gated.print("abc")
        gated.newLine()

        assertEquals(1L, free.revision, "关闭一屏一帧后 holdFrame 不生效，变更立即发布")
        assertEquals(0L, gated.revision, "开着一屏一帧时同一序列被冻结")

        gated.publishFrame()
        assertEquals(free.revision, gated.revision, "解冻后两者发布进度一致")
    }

    @Test
    fun gateDisabledPublishesEveryChange() {
        val free = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = false)
        var expected = free.revision

        free.holdFrame()
        free.print("a")
        free.newLine()
        assertEquals(++expected, free.revision)

        free.holdFrame()
        free.print("b")
        free.newLine()
        assertEquals(++expected, free.revision)

        assertTrue(free.snapshot().isNotEmpty(), "内容照常入列（只是发布节奏不同）")
    }
}
