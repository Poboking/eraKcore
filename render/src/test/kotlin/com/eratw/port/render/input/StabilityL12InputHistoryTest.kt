package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 稳定性波 L12：`InputHistory` 的**容量边界**。
 *
 * 判据（可证伪）：`record()` 里 `val newestIndex = capacity - 1`，随后读 `slots[newestIndex]`。
 * 容量 ≤ 0 时构造照样成功（`arrayOfNulls(0)`），第一条非空提交就走到 `slots[-1]` ⇒
 * **ArrayIndexOutOfBoundsException**，而且位置在 `InputSession.pressEnterKey` 的提交路径里
 * （历史记录在交给核心层之前，`MainWindow.cs:933` 对 `:934`）—— 坏容量被推迟到「玩家按下回车
 * 的那一刻」才爆，堆栈里看不到构造点。
 *
 * 修复口径：容量是构造期常量，非正直接在构造期拒绝（与同车道
 * `TouchGestureController` / `MacroGroupLabel` 的 `init require` 同风格）；
 * 正常容量（含 1）的读数逐值不变。
 */
class StabilityL12InputHistoryTest {

    @Test
    fun `容量为零或负数在构造期就拒绝`() {
        assertFailsWith<IllegalArgumentException> { InputHistory(0) }
        assertFailsWith<IllegalArgumentException> { InputHistory(-1) }
    }

    /** 容量 1 是合法下界：提交 / 召回 / 回到空框三条仍然按官方语义走。 */
    @Test
    fun `容量一的历史仍可用`() {
        val h = InputHistory(1)
        assertEquals(1, h.selectedInputs)
        assertEquals(HistoryEdit.SetText("", selectAll = false), h.record("a"))
        assertEquals(listOf("a"), h.snapshot())
        assertEquals(HistoryEdit.SetText("a", selectAll = true), h.recall(-1))
        assertEquals(0, h.selectedInputs)
        assertEquals(HistoryEdit.SetText("", selectAll = false), h.recall(1))
        assertEquals(1, h.selectedInputs)
    }

    /**
     * 召回态下「与末条相同」的提交走 `MainWindow.cs:963` 那一支：不左移、只把 lastSelected
     * 拉回当前召回位。修复（构造期校验）不得碰这条分支。
     */
    @Test
    fun `召回态下与末条相同的提交不左移`() {
        val h = InputHistory(2)
        h.record("a")
        assertEquals(HistoryEdit.SetText("a", selectAll = true), h.recall(-1))
        assertEquals(1, h.selectedInputs)
        h.record("a")
        assertEquals(listOf("", "a"), h.snapshot())
        assertEquals(1, h.lastSelected)
        assertEquals(2, h.selectedInputs)
    }
}
