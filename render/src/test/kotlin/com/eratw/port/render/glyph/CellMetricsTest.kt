package com.eratw.port.render.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m-glyph · [CellMetrics] 的像素 ↔ 半角格换算闸。
 *
 * 期望值全部按 `StringMeasure.cs:37,65` 的公式手算（默认字号 18 → 每格 9.36 像素，
 * spec: `ConfigData.cs:66`），每个断言旁写清算式；刻意只取**远离格边界**的像素值，
 * 因为原公式的「减 1 像素 + 0.95 偏置 + 两次向零截断」让边界附近由浮点末位决定，
 * 拿边界值断言等于把实现细节钉成期望。
 */
class CellMetricsTest {

    private val m = CellMetrics()

    @Test
    fun `默认字号与行高取配置默认值并互相夹取`() {
        assertEquals(18, m.fontPixelSize)
        assertEquals(19, m.lineHeight)
        // 9.0f * 1.04f（spec: StringMeasure.cs:37）；只断言区间，避开浮点末位
        assertTrue(m.cellPixelWidth > 9.35f && m.cellPixelWidth < 9.37f, "cellPixelWidth=${m.cellPixelWidth}")
        // 字号下限 8（spec: Config.cs:431-434）
        val small = CellMetrics(4, 2)
        assertEquals(8, small.fontPixelSize)
        // 行高夹到字号（spec: Config.cs:436-439）
        assertEquals(8, small.lineHeight)
        val tallFont = CellMetrics(30, 12)
        assertEquals(30, tallFont.lineHeight)
    }

    @Test
    fun `像素到半角格按原公式内层截断`() {
        // (9-1)/9.36 + 0.95 = 1.8047 -> 1
        assertEquals(1, m.pixelsToCells(9))
        // (18-1)/9.36 + 0.95 = 2.7662 -> 2
        assertEquals(2, m.pixelsToCells(18))
        // (36-1)/9.36 + 0.95 = 4.6893 -> 4
        assertEquals(4, m.pixelsToCells(36))
        // (47-1)/9.36 + 0.95 = 5.8645 -> 5
        assertEquals(5, m.pixelsToCells(47))
        // (100-1)/9.36 + 0.95 = 11.5270 -> 11
        assertEquals(11, m.pixelsToCells(100))
    }

    @Test
    fun `空测量与非正像素一律 0`() {
        // spec: StringMeasure.cs:53-56（空串先返回 0，不进截断）
        assertEquals(0, m.pixelsToCells(0))
        assertEquals(0, m.pixelsToCells(-5))
        assertEquals(0, m.cellsToPixels(0))
        assertEquals(0, m.cellsToPixels(-3))
        assertEquals(0, m.quantizePixels(0))
    }

    @Test
    fun `半角格到像素按原公式外层截断`() {
        // 1 * 9.36 = 9.36 -> 9
        assertEquals(9, m.cellsToPixels(1))
        // 2 * 9.36 = 18.72 -> 18
        assertEquals(18, m.cellsToPixels(2))
        // 3 * 9.36 = 28.08 -> 28
        assertEquals(28, m.cellsToPixels(3))
        // 5 * 9.36 = 46.80 -> 46
        assertEquals(46, m.cellsToPixels(5))
        // 13 * 9.36 = 121.68 -> 121
        assertEquals(121, m.cellsToPixels(13))
        // 17 * 9.36 = 159.12 -> 159
        assertEquals(159, m.cellsToPixels(17))
    }

    @Test
    fun `整条量化会把宽度抬到整格且允许大于入参`() {
        // 36 像素 -> 4 格 -> 37.44 -> 37（原版的再量化效果，spec: StringMeasure.cs:65）
        assertEquals(37, m.quantizePixels(36))
        assertEquals(9, m.quantizePixels(9))
        assertEquals(18, m.quantizePixels(18))
        // 20 像素 -> (19/9.36)+0.95 = 2.9799 -> 2 格 -> 18
        assertEquals(18, m.quantizePixels(20))
        // 100 像素 -> 11 格 -> 102.96 -> 102
        assertEquals(102, m.quantizePixels(100))
    }

    @Test
    fun `夹取后的字号上行高与度量自洽`() {
        val small = CellMetrics(4, 2)
        // 生效字号 8 -> 每格 8/2*1.04 = 4.16
        assertEquals(4, small.cellsToPixels(1))
        // (4-1)/4.16 + 0.95 = 1.6712 -> 1
        assertEquals(1, small.pixelsToCells(4))
        // (9-1)/4.16 + 0.95 = 2.8731 -> 2
        assertEquals(2, small.pixelsToCells(9))
    }

    @Test
    fun `换算单调不减`() {
        var previous = 0
        for (pixels in 0..300) {
            val cells = m.pixelsToCells(pixels)
            assertTrue(cells >= previous, "pixels=$pixels 时格数回退：$cells < $previous")
            previous = cells
        }
    }

    @Test
    fun `码元格宽沿用 Grid 的半角 1 全角 2 模型`() {
        // 半角：ASCII 与半角假名区（宽度模型 spec: Kernel.kt:97-104 的 Grid.isFullWidth/cellWidth，
        // 字节数口径 spec: EmueraConsole.cs:3255；基准实测 pc-golden-root.txt:135）
        assertEquals(1, m.cellsOf('A'.code))
        assertEquals(1, m.cellsOf(' '.code))
        assertEquals(1, m.cellsOf('~'.code))
        assertEquals(1, m.cellsOf('｡'.code))
        assertEquals(1, m.cellsOf('ﾟ'.code))
        // 全角：其余一律 2
        assertEquals(2, m.cellsOf('½'.code))
        assertEquals(2, m.cellsOf('あ'.code))
        assertEquals(2, m.cellsOf('漢'.code))
        assertEquals(2, m.cellsOf('７'.code))
        // 增补平面：一个码位 = 两个 UTF-16 码元，各按全角 2 格累加 ⇒ 4 格。
        // 布局侧 Grid.cells / LineFolder 就是逐码元 4 格（已登记口径，见 Kernel.kt 的
        // Grid KDoc「增补平面」节与 grid/AstralWidthTest）；绘制与命中的推进量必须同值。
        assertEquals(4, m.cellsOf(0x1F600))
        assertEquals(2 * CellMetrics.FULL_WIDTH_CELLS, m.cellsOf(0x2FFFF))
        // 非法码位仍按单字全角兜底（不是代理对，无从累加）
        assertEquals(CellMetrics.FULL_WIDTH_CELLS, m.cellsOf(-1))
        assertEquals(CellMetrics.FULL_WIDTH_CELLS, m.cellsOf(0x110000))
    }

    @Test
    fun `默认字体名取基准 PC 跑的字体`() {
        // spec: fixtures/golden/baseline/pc-golden-root.txt:279-280（FONT=黑体 / FONT_DEFAULT=黑体）
        assertEquals("黑体", CellMetrics.DEFAULT_FONT_NAME)
        // 基准配置默认值是 ＭＳ ゴシック（spec: ConfigData.cs:65），二者不同不可混用
        assertTrue(CellMetrics.DEFAULT_FONT_NAME != "ＭＳ ゴシック")
    }

    /**
     * 脚本 `px` → 设备 `px`（立绘空档修复的尺）。
     * 判据是**与百分比支同值**：真机 41px 档下 `180px` 与 `1000%` 必须是同一个数，
     * 因为语料 `emuera.config:22-23` 把 FONT SIZE 与 LINE HEIGHT 都写 18，
     * PC 上二者同为 180 像素（`ERB/魔改内容/QOL_IMAGE.ERB:826` vs
     * `ERB/グラフィック表示ライブラリ/グラフィック表示.ERB:213`）。
     */
    @Test
    fun `脚本px折算与百分比支同尺且缺省恒等`() {
        val device = CellMetrics(41, 43)
        assertEquals(410, device.scriptPixels(180, 18), "180 * 41 / 18（先乘后除的整数除）")
        assertEquals(device.fontPixelSize * 1000 / 100, device.scriptPixels(180, 18), "同一张图两支同值")
        assertEquals(-68, device.scriptPixels(-30, 18), "-30 * 41 / 18 = -68.3 向零截断，负号原样")
        assertEquals(180, device.scriptPixels(180, 0), "基准未报（0）= 恒等")
        assertEquals(180, device.scriptPixels(180, 41), "基准 == 真机字号 = 恒等")
        assertEquals(180, m.scriptPixels(180, 18), "夹具字号 18 == 基准 18 ⇒ 恒等（headless 与黄金基准档）")
    }

    /** 两个边界：Int 上限的 px 不许回绕成负数（图会静默消失）；缩小档不许把 1px 截成 0。 */
    @Test
    fun `脚本px折算的溢出与缩到零两个边界`() {
        val device = CellMetrics(41, 43)
        assertEquals(Int.MAX_VALUE, device.scriptPixels(Int.MAX_VALUE, 18), "Long 中间量 + 夹取，不许回绕成负")
        assertEquals(Int.MIN_VALUE, device.scriptPixels(Int.MIN_VALUE, 18), "负向同样夹在下界")
        val shrink = CellMetrics(20, 24)
        assertEquals(1, shrink.scriptPixels(1, 40), "1 * 20 / 40 = 0 ⇒ 保号抬到 1（≥0.001 抬到 1 的同思路）")
        assertEquals(-1, shrink.scriptPixels(-1, 40), "负号保留")
        assertEquals(0, shrink.scriptPixels(0, 40), "0 仍是 0，不许被抬成 1")
        assertEquals(90, shrink.scriptPixels(180, 40), "缩小档照比例：180 * 20 / 40")
    }
}
