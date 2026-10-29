package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Android 键码 → Win32 VK 的换算表（红 E 的输入面）。
 *
 * 判据 = 语料实际用到的键：`TITLE.ERB:158` 的跳出条件读 1 / 13 / 27
 * （VK_LBUTTON / VK_RETURN / VK_ESCAPE），另加方向键与字母数字的常规映射。
 */
class AndroidKeyCodesTest {

    @Test
    fun `回车族全部折成 VK_RETURN`() {
        assertEquals(0x0D, AndroidKeyCodes.toWinVirtualKey(66)) // ENTER
        assertEquals(0x0D, AndroidKeyCodes.toWinVirtualKey(160)) // NUMPAD_ENTER
        assertEquals(0x0D, AndroidKeyCodes.toWinVirtualKey(23)) // DPAD_CENTER
        assertEquals(AndroidKeyCodes.VK_RETURN, AndroidKeyCodes.toWinVirtualKey(66))
    }

    @Test
    fun `方向键与常用编辑键`() {
        assertEquals(0x25, AndroidKeyCodes.toWinVirtualKey(21)) // LEFT
        assertEquals(0x26, AndroidKeyCodes.toWinVirtualKey(19)) // UP
        assertEquals(0x27, AndroidKeyCodes.toWinVirtualKey(22)) // RIGHT
        assertEquals(0x28, AndroidKeyCodes.toWinVirtualKey(20)) // DOWN
        assertEquals(0x08, AndroidKeyCodes.toWinVirtualKey(67)) // DEL → BACK
        assertEquals(0x20, AndroidKeyCodes.toWinVirtualKey(62)) // SPACE
        assertEquals(AndroidKeyCodes.VK_ESCAPE, AndroidKeyCodes.toWinVirtualKey(111))
    }

    @Test
    fun `数字与字母按位移`() {
        assertEquals(0x30, AndroidKeyCodes.toWinVirtualKey(7)) // 0
        assertEquals(0x39, AndroidKeyCodes.toWinVirtualKey(16)) // 9
        assertEquals(0x41, AndroidKeyCodes.toWinVirtualKey(29)) // A
        assertEquals(0x5A, AndroidKeyCodes.toWinVirtualKey(54)) // Z
    }

    @Test
    fun `表外键不伪造读数`() {
        assertNull(AndroidKeyCodes.toWinVirtualKey(4)) // BACK：由系统导航消费，不喂 GETKEY
        assertNull(AndroidKeyCodes.toWinVirtualKey(24)) // VOLUME_UP
        assertNull(AndroidKeyCodes.toWinVirtualKey(0))
        assertNull(AndroidKeyCodes.toWinVirtualKey(-1))
    }
}
