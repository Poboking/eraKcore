package com.eratw.port.render.view

/**
 * 画布绘制行 → 命中行下标。
 *
 * 绘制窗口比命中窗口多取最上面一排（[minRow] 是首个绘制命令在**可见行快照**里的下标）：
 * `rowsForDraw(..).drop(1) == rowsForHit(..)`，两窗口的可见行逐行相同
 * （`adapter/ConsoleAdapter.kt:272-285`；原版 `EmueraConsole.cs:1805-1807` 与
 * `:2328-2331` 的 `num3` 恒等式）。所以：
 *
 * ```
 * 快照行 = minRow + cellIndex
 * 命中行 = 快照行 - extraRows
 * ```
 *
 * [minRow] 现在恒传 0：绘制侧的行表由 [RowLayout] **按窗口行数**建行，表下标就是
 * `ConsoleScreen.visibleRows()` 的下标 = `DrawCmd.row`，「首个绘制命令在快照里的下标」
 * 这层平移随缺陷 A 的修法一起消失（旧实现按命令极值建行，尾部零命令的空行不进表）。
 * 参数保留是因为换算口径只许有一份，调用方不许在自己那儿再算一次。
 *
 * 2026-10-26 真机「点按钮没反应」的根因：`CanvasConsoleView.cellAt` / `setPointer`
 * 除了这里的 `- extraRows`，又按 `hitRowBase - visibleRowBase` 减了一次，
 * 点击整体落到手指上一行；上一行没有按钮时命中为 null，取值等待下不会补回车，
 * 表现就是完全没反应。本函数是唯一换算口径，调用方不要再做第二次换算。
 */
fun hitRowForCell(minRow: Int, cellIndex: Int, extraRows: Int): Int =
    minRow + cellIndex - extraRows
