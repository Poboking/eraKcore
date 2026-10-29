package com.eratw.port.render.scroll

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.TextRun

/**
 * 测试用的行工厂。
 *
 * 本路的判据是「行的**位置**与**保留/淘汰**」，不是行的内容，所以只塞一个单段
 * `TextRun`，段文本 `L{n}` 同时充当黄金断言里的可观察标记（内容一律原样透传）。
 */
internal fun rowOf(marker: String, logical: Boolean = true, lineEnd: Boolean = true): DisplayRow = DisplayRow(
    runs = listOf(TextRun(text = marker, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "unit")),
    cells = marker.length,
    isLogicalLine = logical,
    isLineEnd = lineEnd,
)

/** 取行的标记（与 [rowOf] 对偶）。 */
internal fun marker(row: DisplayRow): String = row.runs.first().text

/** 连续追加 n 行，标记依次为 `L0`..`L{n-1}`（绝对行号即下标，spec: `EmueraConsole.cs:2945`+`2970`）。 */
internal fun LogBuffer.appendRun(from: Int, n: Int, logical: Boolean = true, lineEnd: Boolean = true) {
    var i = from
    while (i < from + n) {
        append(rowOf("L$i", logical, lineEnd))
        i++
    }
}
