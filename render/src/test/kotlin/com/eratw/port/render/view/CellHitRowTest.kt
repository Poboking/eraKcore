package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 绘制行 → 命中行的换算（真机点按钮落到上一行的回归）。
 *
 * 口径：`rowsForDraw(..).drop(1) == rowsForHit(..)`，见 `adapter/ConsoleAdapter.kt:272-285`；
 * 因此点在第一个完整可见行（cellIndex = extraRows）必须命中 0 号命中行。
 */
class CellHitRowTest {

    @Test
    fun `窗口装满时额外一排不参与命中`() {
        // 0 号是屏外被裁掉的溢出行；1 号才是第一个可点的命中行。
        assertEquals(0, hitRowForCell(minRow = 0, cellIndex = 1, extraRows = 1))
        assertEquals(1, hitRowForCell(minRow = 0, cellIndex = 2, extraRows = 1))
        assertEquals(53, hitRowForCell(minRow = 0, cellIndex = 54, extraRows = 1))
    }

    @Test
    fun `行数不足一屏时没有额外行`() {
        // 两支窗口都夹到 0（ConsoleAdapter 的顶行夹取），逐行相同。
        assertEquals(0, hitRowForCell(minRow = 0, cellIndex = 0, extraRows = 0))
        assertEquals(20, hitRowForCell(minRow = 0, cellIndex = 20, extraRows = 0))
    }

    @Test
    fun `顶部空行抬高 minRow 时仍然只减一次`() {
        // minRow = 3 表示可见快照前 3 行没有绘制命令；idx = 0 对应快照第 3 行。
        assertEquals(2, hitRowForCell(minRow = 3, cellIndex = 0, extraRows = 1))
        assertEquals(7, hitRowForCell(minRow = 3, cellIndex = 5, extraRows = 1))
    }

    @Test
    fun `回归守卫：不会把手指上一行当成命中行`() {
        // 旧实现会得出 -1（再减一次 hitRowBase - visibleRowBase）。
        assertEquals(0, hitRowForCell(minRow = 0, cellIndex = 1, extraRows = 1))
    }
}
