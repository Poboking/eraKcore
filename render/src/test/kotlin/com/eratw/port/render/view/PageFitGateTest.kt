package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PageFitGate] 的回归锁 —— 「外出界面闪动」的最小复现（2026-10-29 玩家反馈）。
 *
 * 现场：QoL 地图/外出屏每 `ANIMATERECOLOREDMAPS`（语料 `emuera.config:28` 的 FPS:5
 * ⇒ 200 ms）整屏 `CLEARLINE` + 重印一次，`pageExtentRows()` 量到的行数在 N 与 N±1
 * 之间摆；字号是行数的函数 ⇒ 整页在两种字号间跳 = 闪动。闸的口径两条：同一行数连续
 * 两帧（吃单帧误读）+ 与已生效档位的差跨过行数死区（吃 200 ms 节拍的摆动 —— 一个读数
 * 能稳稳活过十几帧，光数帧是挡不住的，见「一个读数稳稳活过十几帧也不许越过死区」）。
 */
class PageFitGateTest {

    @Test
    fun `同一行数连续两帧才生效`() {
        val gate = PageFitGate()
        assertFalse(gate.accept(18), "第 1 帧只是待确认，不能立刻换字号")
        assertTrue(gate.hasPending, "第 1 帧之后必须处于待确认态（调用方据此不早退）")
        assertTrue(gate.accept(18), "第 2 帧凑满确认，应当应用")
        assertFalse(gate.hasPending, "应用后清空待确认态")
        assertTrue(gate.appliedRows == 18)
    }

    @Test
    fun `整屏重印的行数抖动永远不生效`() {
        val gate = PageFitGate()
        // 先把 18 行坐实（真翻页的形态）。
        gate.accept(18)
        gate.accept(18)
        // 之后每帧在 18/19 之间跳 —— 这是重印抖动，不是翻页。
        val applied = ArrayList<Int>()
        repeat(20) { i ->
            val rows = if (i % 2 == 0) 19 else 18
            if (gate.accept(rows)) applied += rows
        }
        assertTrue(applied.isEmpty(), "抖动不得触发字号换代，实际应用了 $applied")
        assertTrue(gate.appliedRows == 18, "字号必须停在稳定档 18，实际 ${gate.appliedRows}")
    }

    @Test
    fun `连续两帧同值视为真翻页并生效`() {
        val gate = PageFitGate()
        gate.accept(18)
        gate.accept(18)
        // 翻到另一页：行数稳定变成 25。
        assertFalse(gate.accept(25))
        assertTrue(gate.accept(25), "稳定两帧 = 真翻页，必须换字号")
        assertTrue(gate.appliedRows == 25)
    }

    @Test
    fun `已生效档位重复交入不重复应用`() {
        val gate = PageFitGate()
        gate.accept(18)
        gate.accept(18)
        repeat(5) { assertFalse(gate.accept(18), "同一档位不得反复应用（会每帧重排整页）") }
        assertTrue(gate.appliedRows == 18)
    }

    @Test
    fun `三帧确认档在抖动下同样稳定`() {
        val gate = PageFitGate(stableFrames = 3)
        assertFalse(gate.accept(18), "第 1 帧待确认")
        assertFalse(gate.accept(18), "第 2 帧仍待确认（档位调大 = 更稳、翻页后落得更慢）")
        assertTrue(gate.accept(18), "第 3 帧才生效")
        assertTrue(gate.appliedRows == 18)
    }

    /**
     * 重印节拍的摆动：一个读数可以稳稳量到十几帧，光靠「连续两帧」根本挡不住。
     *
     * 现场是地图/外出屏的 200 ms 重印（FPS:5，语料 `emuera.config:28`）：60 Hz 下一个读数能活
     * ~12 帧，所以旧口径每拍都过闸、整页字号每 200 ms 跳一次（2026-10-29 真机「抖动」）。
     * 死区才是挡这一层的东西。
     */
    @Test
    fun `一个读数稳稳活过十几帧也不许越过死区`() {
        val gate = PageFitGate()
        gate.accept(18)
        gate.accept(18)
        assertEquals(18, gate.appliedRows)

        // 12 帧都量到 19（差 1 行）—— 按旧口径第 2 帧就该换字号。
        val applied = ArrayList<Int>()
        repeat(12) { i -> if (gate.accept(19)) applied += 19 }
        assertTrue(applied.isEmpty(), "死区内的稳定读数不得换字号，实际换了 $applied 次")
        assertEquals(18, gate.appliedRows, "字号必须停在 18 档")
        assertFalse(gate.hasPending, "死区内不留待确认值（调用方据此早退，不必每帧重量）")
    }

    @Test
    fun `跨过死区的翻页仍当帧生效`() {
        val gate = PageFitGate()
        gate.accept(18)
        gate.accept(18)
        // 差 3 行 > 缺省死区 2 ⇒ 仍是「页面真的变了」。
        assertFalse(gate.accept(21))
        assertTrue(gate.accept(21), "跨过死区必须两帧生效，翻页不该等")
        assertEquals(21, gate.appliedRows)
    }

    @Test
    fun `死区调成零就是旧的两帧口径`() {
        val gate = PageFitGate(deadbandRows = 0)
        gate.accept(18)
        gate.accept(18)
        assertFalse(gate.accept(19))
        assertTrue(gate.accept(19), "零死区 ⇒ 任何稳定的新读数都生效（证明上一条用例是死区在挡）")
    }

    @Test
    fun `首档不受死区影响`() {
        val gate = PageFitGate()
        assertFalse(gate.accept(3), "还没应用过任何档位 ⇒ 不适用死区")
        assertTrue(gate.accept(3))
        assertEquals(3, gate.appliedRows)
    }
}
