package com.eratw.port.render.view

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.glyph.CellMetrics
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 4.981 qol 设置页侧边栏 = **竖排卡片**，不是阶梯。
 *
 * 语料 `ERB/魔改内容/PRINT系/UI_Container_Framework.ERB:698-706`：每张卡
 * `HTML_DIV_OPEN(L_SIDEBAR_W, L_TAB_H_PX, 0, L_I * L_TAB_H_PX, …)` —— `xpos=0` 恒定、
 * 只有 `ypos` 按卡高递增，所以 5 张卡的**左沿 x 必须完全一致**。
 *
 * 现场曾出现阶梯（每张更靠右），根因是锚列把前一张卡的按钮文字算进了外层文本宽度；
 * core 侧回归钉见 `DivAnchorColTest`，这里钉**画出来的像素**：三张卡的左边框列相同、
 * 卡顶按卡高递增。
 */
class DivStackedCardsTest {

    private val metrics = CellMetrics()
    private val clientWidth = 60
    private val heightLines = 24
    private val tabH = 38

    private fun card(label: String, value: Int, ypos: Int, color: String) =
        "<div xpos='0px' ypos='${ypos}px' width='200px' height='${tabH}px' border='2px' " +
            "bcolor='#888888' padding='3px,4px' color='$color'>" +
            "<button value='$value'>$label</button></div>"

    private fun pageHtml(): String {
        val colors = listOf("#443300", "#151A22", "#151A22")
        val labels = listOf("日常设置", "诶嘿嘿设置", "视觉设置")
        val sb = StringBuilder("<nobr>")
        labels.forEachIndexed { i, label -> sb.append(card(label, 100 + i, i * tabH, colors[i])) }
        sb.append("</nobr>")
        return sb.toString()
    }

    private fun compose(outName: String): Pair<com.eratw.port.render.sprite.Raster, ConsoleScreen> {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth)
        sink.printHtml(pageHtml(), false)
        // 语料打完窗体后补 `L_FIXED_LINES` 行空行（UI_Container_Framework.ERB:728-730），
        // 锚行因此落在顶部、卡片朝下铺满一屏。
        repeat(heightLines - 1) { sink.newLine() }
        val screen = ConsoleScreen(clientWidth, 100, heightLines)
        screen.submitSnapshot(ConsoleAdapter.adapt(sink.snapshot()))
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        val frame = ConsoleFrame(composer)
        frame.draw(screen, screen.list.dirtyStrips())
        val raster = frame.canvas ?: error("frame 没出画布")
        val img = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, raster.width, raster.height, raster.pixels, 0, raster.width)
        val out = File("build/$outName")
        out.parentFile?.mkdirs()
        ImageIO.write(img, "png", out)
        return raster to screen
    }

    /** 三张卡的**左边框列**必须相同（竖排），卡顶按 `tabH` 递增。 */
    @Test
    fun `三张卡左沿对齐且纵向按卡高等距`() {
        val (raster, screen) = compose("div-stacked-cards.png")
        val rowCount = screen.visibleRows().size
        val composer = ScreenComposer(
            clientWidth,
            heightLines,
            metrics,
            canvasHeightPx = heightLines * metrics.lineHeight,
        )
        val rowTop = composer.rowTopPx(0, rowCount)

        // 卡左沿 = 锚列像素 + xpos = 0；第一列像素应是 bcolor 边框
        fun leftBorderX(cardTop: Int): Int {
            for (x in 0 until 40) {
                if (raster.pixelAt(x, cardTop + tabH / 2) == 0xFF888888.toInt()) return x
            }
            error("卡顶 $cardTop 附近找不到左边框")
        }

        val tops = listOf(0, tabH, 2 * tabH).map { rowTop + it }
        val xs = tops.map { leftBorderX(it) }
        assertEquals(xs[0], xs[1], "第二张卡不能比第一张更靠右（阶梯回归）")
        assertEquals(xs[0], xs[2], "第三张卡同理")
        // 卡顶间距 = 卡高（竖排等距）
        assertEquals(tabH, tops[1] - tops[0])
        assertEquals(tabH, tops[2] - tops[1])
        // 底色取样点避开按钮字形（标签在卡左侧）：取卡内靠右的空白处
        val bgX = xs[0] + 150
        val inner = tops.map { raster.pixelAt(bgX, it + tabH / 2) }
        assertEquals(0xFF443300.toInt(), inner[0], "第一张卡是选中态底色")
        assertEquals(0xFF151A22.toInt(), inner[1], "第二张卡是常态底色")
    }
}
