package com.eratw.port.render.view

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 稳定性波 L12：`RowLayout.plan` 的**入参边界**（纯图行压缩下限 > 执行行高）。
 *
 * 判据（可证伪）：`artAdvance = fit.coerceIn(minArtRowHeightPx, lineHeightPx)`，而 Kotlin 的
 * `coerceIn` 在 `minimumValue > maximumValue` 时**抛 IllegalArgumentException**。
 * 修复前 `plan()` 没有声明「min ≤ lineHeight」这条前置，直接调用（极端字号档 / 缩字调试口 /
 * 未来新增调用方）就在绘制路径上每帧抛一次 —— 绘制入口整帧丢，表现为持续掉帧或闪退。
 *
 * 钉住两件事：
 * 1. 坏档位（min 40 > 行高 8）必须退化成「不压缩」而不是抛；
 * 2. 正常档位（min 6 < 行高 38，`CanvasConsoleView.MIN_ART_ROW_HEIGHT` 与真机行高）的
 *    行高与行顶逐值不变 —— 修复只动非法区间。
 */
class StabilityL12RowLayoutTest {

    private fun text(row: Int) = DrawCmd(
        row = row, colStart = 0, colEnd = 8, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.TEXT, glyphs = emptyList(),
    )

    private fun image(row: Int, heightPx: Int) = DrawCmd(
        row = row, colStart = 0, colEnd = 10, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.IMAGE, glyphs = emptyList(),
        graphic = InlineGraphic.Image(
            resource = "立绘X", widthPx = heightPx, heightPx = heightPx,
            yOffsetPx = 0, widthCells = 10,
        ),
    )

    /**
     * 压缩下限高于执行行高：修复前 `fit.coerceIn(40f, 8f)` 抛 IllegalArgumentException。
     *
     * 手推（窗口 3 行 = 1 图行 + 2 文字行，可用高 100）：
     * `remain = 100 - 2 × 8 = 84`、`fit = 84 / 1 = 84`；
     * 下限先对上限夹一次 ⇒ `floor = min(40, 8) = 8` ⇒ `artAdvance = 84.coerceIn(8, 8) = 8`
     * （压缩后的行高不可能超过执行行高，所以这一档就是「不压缩」）。
     * 行高 `[8, 8, 8]`、`total = 24`、`tops = [76, 84, 92]`。
     *
     * 图高取 8px（= 行高）而不是 100px：高于一行的图自 2026-11 起整类不参与压缩
     * （`RowLayout.compressible`），走不到 `coerceIn` 那一支；本用例要钉的正是那条夹取路径。
     */
    @Test
    fun `压缩下限高于行高时退化为不压缩而不是抛`() {
        val plan = RowLayout.plan(
            cmds = listOf(image(row = 0, heightPx = 8)),
            windowRows = 3,
            contentTop = 0f,
            contentBottom = 100f,
            lineHeightPx = 8f,
            minArtRowHeightPx = 40f,
        )
        assertEquals(3, plan.tops.size)
        assertEquals(8f, plan.heights[0], 1e-4f)
        assertEquals(8f, plan.heights[1], 1e-4f)
        assertEquals(8f, plan.heights[2], 1e-4f)
        assertEquals(76f, plan.tops[0], 1e-4f)
        assertEquals(84f, plan.tops[1], 1e-4f)
        assertEquals(92f, plan.tops[2], 1e-4f)
    }

    /** 下限由 `Float.NaN` 给出（坏度量）：同样不许抛，行高仍是执行行高。 */
    @Test
    fun `压缩下限为 NaN 时不抛`() {
        val plan = RowLayout.plan(
            cmds = listOf(image(row = 0, heightPx = 8)),
            windowRows = 2,
            contentTop = 0f,
            contentBottom = 100f,
            lineHeightPx = 8f,
            minArtRowHeightPx = Float.NaN,
        )
        assertEquals(2, plan.tops.size)
        assertEquals(8f, plan.heights[0], 1e-4f)
        assertEquals(8f, plan.heights[1], 1e-4f)
    }

    /**
     * 正常档位逐值不变（回归钉）：`min = 6`（`CanvasConsoleView.MIN_ART_ROW_HEIGHT`）、
     * 行高 38、可用高 300、10 行 = 8 文字行 + 2 纯图行。
     * `remain = 300 - 8 × 38 = -4` ⇒ `fit = 6` ⇒ `artAdvance = 6.coerceIn(6, 38) = 6`。
     * 行高 `[38 × 8, 6, 6]`、`total = 316`、`tops[0] = 300 - 316 = -16`。
     *
     * 图高取 **16px**（标题横带那一档，`<= lineHeightPx`）而不是原来的 100px：
     * 2026-11 的修法把「高于一行的图」整类排除出压缩（压了会让图越出脚本预留的空行、
     * 再经第二趟铺墨盖住文字，见 `RowLayout.compressible` 与 `ArtRowOverflowTest`），
     * 所以 100px 的图不再走 `artAdvance` 这一支。本用例要钉的是**`coerceIn` 的下限/上限
     * 夹取路径**（L12 的 `minimumValue > maximumValue` 会抛），那条路径与图高无关，
     * 换成可压的 16px 横带后逐值不变、意图也不变。
     */
    @Test
    fun `正常档位的行高与行顶逐值不变`() {
        val plan = RowLayout.plan(
            cmds = listOf(image(row = 8, heightPx = 16), image(row = 9, heightPx = 16)),
            windowRows = 10,
            contentTop = 0f,
            contentBottom = 300f,
            lineHeightPx = 38f,
            minArtRowHeightPx = 6f,
        )
        assertEquals(10, plan.tops.size)
        for (i in 0..7) {
            assertEquals(38f, plan.heights[i], 1e-4f, "第 $i 行是文字行")
        }
        assertEquals(6f, plan.heights[8], 1e-4f)
        assertEquals(6f, plan.heights[9], 1e-4f)
        assertEquals(-16f, plan.tops[0], 1e-4f)
        assertEquals(-16f + 8 * 38f, plan.tops[8], 1e-4f)
    }
}
