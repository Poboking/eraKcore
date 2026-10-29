package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 状态图静态不变量：入口映射、C# 数值、迁移目标闭包、非法迁移、Normal 的失败语义。
 *
 * 断言的是 `reference/emuera-cs` 的 `SystemStateCode.cs:3-50` / `ProcessState.cs:225-256,273-278`
 * / `Process.cs:1998-2045,2959-2962`。
 */
class SystemStateGraphTest {

    @Test
    fun `BeginType 入口状态与原版 Begin 分派一致`() {
        assertEquals(SystemStateCode.Title_Begin, SystemStateGraph.entryStateOf(BeginType.TITLE))
        assertEquals(SystemStateCode.First_Begin, SystemStateGraph.entryStateOf(BeginType.FIRST))
        assertEquals(SystemStateCode.Train_Begin, SystemStateGraph.entryStateOf(BeginType.TRAIN))
        assertEquals(SystemStateCode.AfterTrain_Begin, SystemStateGraph.entryStateOf(BeginType.AFTERTRAIN))
        assertEquals(SystemStateCode.Ablup_Begin, SystemStateGraph.entryStateOf(BeginType.ABLUP))
        assertEquals(SystemStateCode.Turnend_Begin, SystemStateGraph.entryStateOf(BeginType.TURNEND))
        assertEquals(SystemStateCode.Shop_Begin, SystemStateGraph.entryStateOf(BeginType.SHOP))
    }

    @Test
    fun `状态数值与 C 枚举逐值一致`() {
        assertEquals(0, SystemStateCode.Title_Begin.code)
        assertEquals(1, SystemStateCode.Openning.code)
        assertEquals(16, SystemStateCode.Train_Begin.code)
        assertEquals(17, SystemStateCode.Train_CallEventTrain.code)
        assertEquals(21, SystemStateCode.Train_WaitInput.code)
        assertEquals(26, SystemStateCode.Train_DoTrain.code)
        assertEquals(131094, SystemStateCode.Train_CallEventCom.code)
        assertEquals(131124, SystemStateCode.Ablup_CallAblupXX.code)
        assertEquals(131136, SystemStateCode.Turnend_Begin.code)
        assertEquals(65616, SystemStateCode.Shop_Begin.code)
        assertEquals(196689, SystemStateCode.Shop_CallEventShop.code)
        assertEquals(576, SystemStateCode.First_Begin.code)
        assertEquals(262143, SystemStateCode.Normal.code)
        // 反查
        assertEquals(SystemStateCode.Normal, SystemStateCode.ofCode(262143))
        assertEquals(null, SystemStateCode.ofCode(12345))
    }

    @Test
    fun `CAN_SAVE 与 CAN_BEGIN 标志位按位判定`() {
        // __CAN_BEGIN__ = 131072；Normal(262143) 与训练事件链(131094+) 都含该位。
        assertTrue(SystemStateCode.Normal.canBegin)
        assertTrue(SystemStateCode.Normal.canSave)
        assertTrue(SystemStateCode.Train_CallEventCom.canBegin)
        assertFalse(SystemStateCode.Title_Begin.canBegin)
        assertFalse(SystemStateCode.Openning.canSave)
        // 65616 = __CAN_SAVE__(65536) | 80：商店内可存档，但不能再 BEGIN SHOP。
        assertTrue(SystemStateCode.Shop_Begin.canSave)
        assertFalse(SystemStateCode.Shop_Begin.canBegin)
    }

    @Test
    fun `所有迁移目标都在图内且节点非空`() {
        assertTrue(SystemStateGraph.nodes.isNotEmpty())
        for ((state, node) in SystemStateGraph.nodes) {
            assertEquals(state, node.state)
            assertTrue(node.handler.isNotEmpty(), "${state.name} 缺 handler")
            assertTrue(node.evidence.contains(":") || node.evidence.contains("Process"), "${state.name} 缺证据")
            for (tr in node.transitions) {
                assertTrue(
                    SystemStateGraph.nodes.containsKey(tr.to),
                    "${state.name} -> ${tr.to.name} 的目标未登记",
                )
                assertTrue(tr.guard.isNotEmpty())
                assertTrue(tr.evidence.isNotEmpty())
            }
        }
    }

    @Test
    fun `关键迁移合法且非法迁移抛 IllegalSystemTransition`() {
        SystemStateGraph.requireTransition(SystemStateCode.Title_Begin, SystemStateCode.Normal)
        SystemStateGraph.requireTransition(SystemStateCode.Title_Begin, SystemStateCode.Openning)
        SystemStateGraph.requireTransition(SystemStateCode.Openning, SystemStateCode.First_Begin)
        SystemStateGraph.requireTransition(SystemStateCode.First_Begin, SystemStateCode.Normal)
        SystemStateGraph.requireTransition(
            SystemStateCode.Train_CallEventTrain,
            SystemStateCode.Train_CallShowStatus,
        )
        SystemStateGraph.requireTransition(
            SystemStateCode.Train_CallComXX,
            SystemStateCode.Train_CallSourceCheck,
        )
        SystemStateGraph.requireTransition(SystemStateCode.Shop_Begin, SystemStateCode.Shop_CallEventShop)
        SystemStateGraph.requireTransition(SystemStateCode.Shop_CallShowShop, SystemStateCode.Shop_WaitInput)

        // FIRST 不可能直接进 SHOP / 标题不可能直接进训练
        assertFailsWith<IllegalSystemTransition> {
            SystemStateGraph.requireTransition(SystemStateCode.First_Begin, SystemStateCode.Shop_Begin)
        }
        assertFailsWith<IllegalSystemTransition> {
            SystemStateGraph.requireTransition(SystemStateCode.Title_Begin, SystemStateCode.Train_Begin)
        }
        assertTrue(SystemStateGraph.allowedTargets(SystemStateCode.Normal).isEmpty())
    }

    @Test
    fun `Normal 没有 handler 出口且失败语义是 UnexpectedScriptEnd`() {
        val normal = SystemStateGraph.nodeOf(SystemStateCode.Normal)
        assertEquals("endNormal", normal.handler)
        assertTrue(normal.transitions.isEmpty())
        assertTrue(normal.failure.contains("UnexpectedScriptEnd"))
        assertEquals("Process.cs:2039 / Process.cs:2959-2962", normal.evidence)
    }
}
