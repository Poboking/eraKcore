package com.eratw.port.core.host

/**
 * Android 按键码 → Win32 虚拟键码的映射表（红 E 的输入面）。
 *
 * ## 为什么这张表放在 core
 * 原版 `GETKEY` / `GETKEYTRIGGERED` 收的是 Win32 虚拟键码，且**原样**交给 user32
 * （spec: FunctionMethodCreator.cs:7820-7825、WinInput.cs:8），剧本里写的也是这套码
 * （`TITLE.ERB:158` 用 1/13/27 = VK_DECIMAL/VK_RETURN/VK_ESCAPE）。Android 只给
 * `KeyEvent.keyCode`，两者必须有一张换算表。表本身是纯整数数据、不引 Android API，
 * 放 core 才能进纯 JVM 闸（`:app` 在含非 ASCII 的工程路径上根本配置不起来）。
 *
 * ## 数值来源
 * Android 侧常量取 `android.view.KeyEvent` 的公开码值（API 1 起稳定：ENTER=66、
 * DPAD_CENTER=23、NUMPAD_ENTER=160、SPACE=62、DEL=67、DPAD_UP=19…DOWN=20…LEFT=21…
 * RIGHT=22、0..9 = 7..16、A..Z = 29..54 依字母序）；Win32 侧取 `VK_*`（BACK=8、
 * TAB=9、CLEAR=12、RETURN=13、PAUSE=19、CAPITAL=20、ESCAPE=27、SPACE=32、
 * PRIOR=33、NEXT=34、END=35、HOME=36、LEFT=37、UP=38、RIGHT=39、DOWN=40、
 * INSERT=45、DELETE=46、LWIN=91、CONTROL=17、MENU=18、SHIFT=16、DECIMAL=110、
 * 数字 0x30..0x39、字母 0x41..0x5A）。
 */
object AndroidKeyCodes {

    /** Android 键码 → Win32 VK；表外键返回 null（宿主按「本移植不建模」处理，不伪造读数）。 */
    fun toWinVirtualKey(androidKeyCode: Int): Int? {
        if (androidKeyCode in DIGIT_ZERO..DIGIT_NINE) return VK_DIGIT_ZERO + (androidKeyCode - DIGIT_ZERO)
        if (androidKeyCode in LETTER_A..LETTER_Z) return VK_LETTER_A + (androidKeyCode - LETTER_A)
        return when (androidKeyCode) {
            ENTER, NUMPAD_ENTER, DPAD_CENTER -> VK_RETURN
            SPACE -> VK_SPACE
            DEL -> VK_BACK
            DPAD_UP -> VK_UP
            DPAD_DOWN -> VK_DOWN
            DPAD_LEFT -> VK_LEFT
            DPAD_RIGHT -> VK_RIGHT
            HOME_KEY -> VK_HOME
            END_KEY -> VK_END
            PAGE_UP -> VK_PRIOR
            PAGE_DOWN -> VK_NEXT
            TAB_KEY -> VK_TAB
            ESCAPE -> VK_ESCAPE
            else -> null // 含音量/媒体等系统键：原版没有对应形态
        }
    }

    // ---- Android `KeyEvent` 码值 ----
    private const val ENTER = 66
    private const val NUMPAD_ENTER = 160
    private const val DPAD_CENTER = 23
    private const val SPACE = 62
    private const val DEL = 67
    private const val DPAD_UP = 19
    private const val DPAD_DOWN = 20
    private const val DPAD_LEFT = 21
    private const val DPAD_RIGHT = 22
    private const val HOME_KEY = 3
    private const val END_KEY = 123
    private const val PAGE_UP = 92
    private const val PAGE_DOWN = 93
    private const val TAB_KEY = 61
    private const val ESCAPE = 111
    private const val DIGIT_ZERO = 7
    private const val DIGIT_NINE = 16
    private const val LETTER_A = 29
    private const val LETTER_Z = 54

    // ---- Win32 `VK_*` 码值 ----
    /**
     * 鼠标左键。语料把它和回车/ESC 并列为「打断」键（`TITLE.ERB:158` 的
     * `GETKEYTRIGGERED(1) || GETKEYTRIGGERED(13) || GETKEYTRIGGERED(27)`），
     * 原版 `GetKeyState(VK_LBUTTON)` 读的是指针按下位（FunctionMethodCreator.cs:7825）。
     * 触屏没有指针按下态可轮询，宿主在「玩家点了一下提交」时补一次左键沿变。
     */
    const val VK_LBUTTON: Int = 0x01

    const val VK_RETURN: Int = 0x0D

    /** ESC（`TITLE.ERB:158` 的三个打断键之一；触屏没有 ESC 键，只能由外接键盘触发）。 */
    const val VK_ESCAPE: Int = 0x1B

    private const val VK_BACK = 0x08
    private const val VK_TAB = 0x09
    private const val VK_SPACE = 0x20
    private const val VK_PRIOR = 0x21
    private const val VK_NEXT = 0x22
    private const val VK_END = 0x23
    private const val VK_HOME = 0x24
    private const val VK_LEFT = 0x25
    private const val VK_UP = 0x26
    private const val VK_RIGHT = 0x27
    private const val VK_DOWN = 0x28
    private const val VK_DIGIT_ZERO = 0x30
    private const val VK_LETTER_A = 0x41
}
