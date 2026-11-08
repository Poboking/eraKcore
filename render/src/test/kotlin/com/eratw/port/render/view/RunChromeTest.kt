package com.eratw.port.render.view

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RunChrome.backgroundFill] 的口径钉子（2026-10-24 真机「选项行黄/粉底色」缺陷回归）。
 *
 * 缺陷现场：语料 `CALL COLORMESSAGE(@"　[2000] 能力编辑", 0x70C070, 1)`
 * （`Print_&_Input.ERB:172-200`）只做 `SETCOLOR 0x70C070` + `PRINTFORML`，原版**没有底色**；
 * 而按钮段的 `DrawCmd.back` 装的是 `ButtonColor`（焦点字色），Android 渲染器把它当底色铺满
 * ⇒ 整行黄底/粉底。本用例把这条口径钉在纯 JVM 层，两个渲染器都必须走 [RunChrome]。
 *
 * 2026-11 时停背景修复后的口径：`back` 非 null **只**剩 HTML `<div color>` 的染整行矩形；
 * `SETBGCOLOR` 是控制台级活值（EmueraConsole.cs:200），每帧 `graph.Clear(bgColor)` 整张清
 * （`:1816`），经 `ConsoleFeed` 发布进 `ConsoleScreen.backgroundArgb` 整幅铺底——
 * run 不再承载它，普通文本段 null back 一律不铺。
 */
class RunChromeTest {

    private fun cmd(kind: RunKind, back: Int?, fore: Int = 0xC0C0C0): DrawCmd = DrawCmd(
        row = 0,
        colStart = 0,
        colEnd = 1,
        fore = fore,
        back = back,
        styleMask = 0,
        kind = kind,
        glyphs = emptyList(),
    )

    @Test
    fun `按钮段非按下态不铺底色_即使 back 是黄色焦点色`() {
        val yellowFocus = 0xFFFF00
        assertNull(
            RunChrome.backgroundFill(cmd(RunKind.BUTTON, back = yellowFocus), pressed = false),
            "按钮段的 back 是 ButtonColor，不是背景色；铺出来就是用户看到的黄底",
        )
    }

    @Test
    fun `按钮段按下态用半透明白高亮`() {
        assertEquals(
            RunChrome.PRESSED_BACK,
            RunChrome.backgroundFill(cmd(RunKind.BUTTON, back = 0xFFFF00), pressed = true),
        )
    }

    @Test
    fun `非按钮段带自有底色才铺（HTML div 染整行矩形）`() {
        assertEquals(
            0xFF102030.toInt(),
            RunChrome.backgroundFill(cmd(RunKind.TEXT, back = 0x102030), pressed = false),
        )
    }

    @Test
    fun `非按钮段无自有底色不铺（落在整屏底色上）`() {
        assertNull(
            RunChrome.backgroundFill(cmd(RunKind.TEXT, back = null), pressed = false),
            "SETBGCOLOR 走整屏底色（graph.Clear 整张清），run 不再按行快照背色",
        )
    }
}
