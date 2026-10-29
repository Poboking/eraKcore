package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OptionButtonScanner] 的口径钉子（2026-10-24）：
 *
 * 旧实现（`CanvasConsoleView.optionChips`）拿**文本快照正则**认 `[数字]`，所以
 * 本作大量**非数字按钮**（`exit`、`戻る` 等字符串值按钮）在右侧栏根本不出现。
 * 新实现直接读引擎的按钮命中层，本用例把「数值 + 字符串两种按钮都要出现、且只认当前页」
 * 钉死。
 */
class OptionButtonScannerTest {

    private fun buttonRun(text: String, value: Any, id: Int): TextRun = TextRun(
        text = text,
        fore = 0xC0C0C0,
        back = 0x000000,
        styleMask = 0,
        fontName = "",
        kind = RunKind.BUTTON,
        buttonValue = value,
        buttonId = id,
    )

    private fun textRun(text: String): TextRun = TextRun(
        text = text,
        fore = 0xC0C0C0,
        back = 0x000000,
        styleMask = 0,
        fontName = "",
        kind = RunKind.TEXT,
    )

    private fun row(vararg runs: TextRun): DisplayRow = DisplayRow(
        runs = runs.toList(),
        cells = runs.sumOf { it.text.length },
        isLogicalLine = true,
        isLineEnd = true,
    )

    @Test
    fun `数值按钮与字符串按钮都能枚举出来`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = 100, heightLines = 6)
        // 一行：正文 + 数值按钮「[0] 开始」+ 字符串按钮「exit」
        screen.buttons.attach(
            listOf(
                row(
                    textRun("[0] "),
                    buttonRun("开始游戏", 0L, id = 1),
                    textRun("   "),
                    buttonRun("exit", "exit", id = 2),
                ),
            ),
        )
        val chips = OptionButtonScanner.scan(screen)

        val exit = chips.firstOrNull { it.value == "exit" }
        assertTrue(exit != null, "字符串按钮 exit 必须被枚举出来（旧实现看不到）")
        assertEquals("exit", exit!!.label)
        assertEquals(null, exit.ordinal, "字符串按钮没有数值序号")

        val numeric = chips.firstOrNull { it.value == 0L }
        assertTrue(numeric != null, "数值按钮 0 也要在")
        assertEquals(0, numeric!!.ordinal)
    }

    @Test
    fun `只认当前窗口里的按钮_旧页的不出现`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = 100, heightLines = 4)
        // 只有 attach 传进去的行才是「当前页」；旧页按钮根本不在命中层里
        screen.buttons.attach(listOf(row(buttonRun("exit", "exit", id = 1))))
        val first = OptionButtonScanner.scan(screen)
        assertEquals(1, first.size)

        // 翻页后（新的行集合，不含 exit）——旧按钮必须消失
        screen.buttons.attach(listOf(row(buttonRun("戻る", "back", id = 2))))
        val second = OptionButtonScanner.scan(screen)
        assertEquals(listOf("戻る"), second.map { it.label })
        assertTrue(second.none { it.value == "exit" }, "上一页的 exit 不能再出现")
    }

    @Test
    fun `同一按钮拆成多段 run 时合并成一条`() {
        val screen = ConsoleScreen(clientWidth = 40, maxLog = 100, heightLines = 4)
        screen.buttons.attach(
            listOf(row(buttonRun("能力", "exit", id = 7), buttonRun("编辑", "exit", id = 7))),
        )
        val chips = OptionButtonScanner.scan(screen)
        assertEquals(1, chips.size, "同一个源按钮（同 buttonId/同值）相邻 run 要并成一条")
        assertEquals("能力编辑", chips.single().label)
    }
}
