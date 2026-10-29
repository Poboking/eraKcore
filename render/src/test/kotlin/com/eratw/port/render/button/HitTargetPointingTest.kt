package com.eratw.port.render.button

import com.eratw.port.render.HitTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `MOUSEB` 指向态的映射：整型按钮走 `input`、字符串按钮走 `inputs`
 * （`ConsoleButtonString.cs:80-110`；`MouseState.kt:151-156`）。
 */
class HitTargetPointingTest {

    @Test
    fun `数值按钮映射到整型通道`() {
        val p = HitTarget(row = 3, colStart = 10, colEnd = 14, value = 2000L).toPointing()
        assertTrue(p.isButton)
        assertTrue(p.isInteger)
        assertEquals(2000L, p.input)
        assertEquals("2000", p.inputs)
    }

    @Test
    fun `字符串按钮映射到字符串通道`() {
        val p = HitTarget(row = 0, colStart = 0, colEnd = 4, value = "exit").toPointing()
        assertTrue(p.isButton)
        assertFalse(p.isInteger)
        assertEquals(0L, p.input)
        assertEquals("exit", p.inputs)
    }
}
