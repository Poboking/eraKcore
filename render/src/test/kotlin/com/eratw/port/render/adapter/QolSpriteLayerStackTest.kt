package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.grid.LineFolder
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 立绘的**图层叠放**不许把整行推歪（2026-10-29 玩家报：开启「时间停止」后立绘整体向左错位，
 * 关掉就正常）。
 *
 * ## 这一屏是谁排的
 *
 * `ERB/魔改内容/QOL_IMAGE.ERB` 的 `ASSEMBLE_HTML_IMAGE_LAYERS`（:792-836）把一张立绘的
 * 所有图层拼成**同一行**里的「图 + 负向空白 + 图 + 负向空白 …」，靠负 space 把列游标拉回
 * 第一张图的起点来叠放（原样四条）：
 *
 * ```
 * <img src='BASE' width='{IMG_W_PX}px' height='{IMG_H_PX}px' ypos='{Y_OFF}px'>        ; :824
 * 每一层特效：
 *   <shape type='space' param='-{IMG_W_PX}px'>                                        ; :829
 *   <img src='EFF' width='{IMG_W_PX}px' height='{IMG_H_PX}px' ypos='{Y_OFF}px'>       ; :831/833
 * ```
 *
 * 「时间停止」正是往这个层数组里多塞一层（`LAYERS:LAYER_CNT '= "時間停止"`，:584 与 :741；
 * 开关位由 `GET_DEFAULT_IMAGE_FLAGS` 的 `SETBIT FLAGS, 5` 给出，:1380-1381）。所以玩家一开
 * 时间停止，同一行里就多**一对**「负 space + 立绘尺寸图」，其它什么都没变。
 *
 * ## 为什么会向左错
 *
 * 行是 `<p align='center'>` 的（:88/109/393 按 `FLAG:画像表示位置` 取 left/center，默认 center），
 * 居中平移 = `可绘制宽/2 - 行宽/2`（`ConsoleDisplayLine.cs:80`，端口 `ConsoleAdapter.alignmentShift`）。
 * 原版的「行宽」是**部件宽的有符号求和**：`ConsoleSpacePart.cs:11` 把矩形宽原样存进 `WidthF`
 * （负值保留）、`:20-24` 直写 `Width`，`ConsoleDisplayLine.cs:63-66` 累加的就是它 ——
 * 于是加一层图层，行宽**一张图都不多**（+20 格与 -20 格相消）。
 * 端口此前按「文本负载的空格数」量行宽，而负 space 的文本是**空串**（`emitGraphicRun` 对负宽
 * 给空串，`" ".repeat(负)` 会抛），回退就被记成「不占」：每多叠一层，行宽凭空多出一张图，
 * 居中点向**左**挪半张图的宽度。真机 110 列档（字号 41）下一张立绘 20 格 ⇒ 每叠一层向左挪
 * 10 格 = 213 设备像素（半张立绘宽），实测读数见 [每加一层图层都不动整行的居中落位]（47 → 37），
 * 玩家看到的就是「立绘整块向左错位」；关掉时间停止少一层 ⇒ 复原。
 *
 * 修正在 `grid/LineFolder.kt` 的 `FoldPiece.cells`（图形件改取节点自带 `widthCells`，带符号）。
 * 本文件守的是**结果**：叠多少层，行宽与落位都不许动。
 */
class QolSpriteLayerStackTest {

    /** 生效配置字号（脚本 px 的尺）。语料 `emuera.config:22` = 18。 */
    private val scriptFontPx = 18

    /** 真机 110 列档的 page-fit 读数：字号 41 / 行高 43（`view/PageFitter` 一档）。 */
    private val deviceFontPx = 41
    private val deviceLinePx = 43

    /** 一屏半角格数（同档 `clientWidth`）。 */
    private val clientCells = 115

    /** `GET_DEFAULT_IMAGE_WIDTH` 的默认档 = 默认角色画像横幅 180（`キャラ描画共通.ERH:2`）。 */
    private val imgWPx = 180
    private val imgHPx = 180

    /** 一张立绘的占宽格数：180 脚本 px 折成设备像素 410，再按半角 21.32px 向上取整。 */
    private val imgCells = ceil(imgWPx * deviceFontPx / scriptFontPx / (deviceFontPx / 2f * 1.04f)).toInt()

    /** QOL 那层拼出来的一整串（`ASSEMBLE_HTML_IMAGE_LAYERS` 的原样形态）。 */
    private fun qolSpriteHtml(layers: Int, chars: Int = 1): String = buildString {
        append("<p align='center'><nobr>")
        for (c in 0 until chars) {
            append("<img src='立绘_服_通常_$c' width='${imgWPx}px' height='${imgHPx}px' ypos='0px'>")
            repeat(layers - 1) {
                append("<shape type='space' param='-${imgWPx}px'>")
                append("<img src='時間停止' width='${imgWPx}px' height='${imgHPx}px' ypos='0px'>")
            }
        }
    }

    private fun rows(html: String): List<DisplayRow> {
        val line = RenderLine(
            runs = listOf(
                TextRun(
                    html,
                    fore = 0xC0C0C0,
                    back = 0,
                    styleMask = 0,
                    fontName = "msgothic",
                    kind = RunKind.HTML,
                ),
            ),
            isLogicalLine = true,
            isLineEnd = true,
        )
        val flatten = HtmlRunFlatten(CellMetrics(deviceFontPx, deviceLinePx), scriptFontPixelSize = scriptFontPx)
        return ConsoleAdapter.fold(LineFolder(clientCells), listOf(flatten.flattenLine(line)))
    }

    @Test
    fun `立绘占宽是一张图的格数而不是层数乘`() {
        assertEquals(20, imgCells, "夹具本身要对得上真机 110 列档的一张立绘 = 20 半角格")
        val one = rows(qolSpriteHtml(1))
        val three = rows(qolSpriteHtml(3))
        assertEquals(imgCells, one.single().cells)
        assertEquals(imgCells, three.single().cells, "叠三层仍然只有一张图的宽（负 space 必须扣回去）")
        assertNotEquals(3 * imgCells, three.single().cells, "旧口径：回退被记成不占，行宽凭空多两张图")
    }

    @Test
    fun `每加一层图层都不动整行的居中落位`() {
        // 玩家的可验判据：开/关时间停止，立绘的起列不许变。
        val base = rows(qolSpriteHtml(1)).single()
        for (layers in 2..5) {
            val row = rows(qolSpriteHtml(layers)).single()
            assertEquals(base.indentCells, row.indentCells, "${layers} 层与 1 层的居中平移必须相同")
        }
        assertEquals(clientCells / 2 - imgCells / 2, base.indentCells, "居中口径直译 ConsoleDisplayLine.cs:80")
    }

    @Test
    fun `五枚角色各叠三层仍是一行_不会被折成多行`() {
        // 净宽 5×20 = 100 格 < 115；旧口径按 5×3×20 = 300 格算 ⇒ 折成三行，
        // 后面的图层整个掉到下一行去（那是比「左移」更狠的同源症状）。
        val rows = rows(qolSpriteHtml(3, chars = 5))
        assertEquals(1, rows.size, "层与层靠负 space 叠在同一行，折行判定也必须按有符号游标走")
        assertEquals(5 * imgCells, rows.single().cells)
    }

    @Test
    fun `负 space 段确实带着负的部件宽进表`() {
        // 上面三条都靠这一条成立：展平侧给的是空文本 + 负 widthCells，
        // 折行侧若改回去数文本，负 space 就又是 0 格了。
        val line = HtmlRunFlatten(CellMetrics(deviceFontPx, deviceLinePx), scriptFontPixelSize = scriptFontPx)
            .flattenLine(
                RenderLine(
                    runs = listOf(
                        TextRun(
                            qolSpriteHtml(2),
                            fore = 0xC0C0C0,
                            back = 0,
                            styleMask = 0,
                            fontName = "msgothic",
                            kind = RunKind.HTML,
                        ),
                    ),
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            )
        val backs = line.runs.filter { it.graphic is com.eratw.port.render.displaylist.InlineGraphic.Space }
        assertEquals(1, backs.size, "两层 = 一枚回退 space")
        assertEquals(-imgCells, backs.single().graphic!!.widthCells)
        assertEquals("", backs.single().text, "回退段的文本负载是空串，宽度只能从部件取")
        assertTrue(line.align == Alignment.CENTER, "夹具走的是 QOL 默认的 center 行")
    }
}
