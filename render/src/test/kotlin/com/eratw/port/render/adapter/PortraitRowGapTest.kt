package com.eratw.port.render.adapter

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 立绘页「图与下一行文字之间空一大段」的对拍（2026-10-27 主人报的 1 号问题）。
 *
 * ## 这条页面上到底发生什么
 *
 * eraTW 的立绘由 `HTML_PRINTL_PX` 打印：先 `HTML_PRINT` 一条 `<img>`，再按
 * **`ceil(图高 / GETCONFIG("一行の高さ"))`** 补空行，让后续文字不被图压住
 * （spec: `ERB/魔改内容/QOL_IMAGE.ERB:350-366` 的 `@HTML_PRINTL_PX`，:357 那一行算式；
 * 图高来自 `GET_DEFAULT_IMAGE_HEIGHT` = `默认角色画像横幅` 180，标签写成 `height='180px'`，
 * 同文件 :826）。语料 `emuera.config:22-23` 把 `FONT SIZE` 与 `LINE HEIGHT` 都定为 **18**，
 * 所以 PC 上：预留 `ceil(180/18) = 10` 行 × 18px = 180px，图正好吃满，图底与下一行文字
 * 的顶边**齐平**（`LINES_DRAWN = 0`：第一个 `PRINTL` 只结束图那一行，图行算在这 10 行里）。
 *
 * 端口这边行高由 `view/PageFitter` 按屏适应（110 列档 ≈ 41px 字号 / 43px 行高），而 `px`
 * 形态的图此前**不跟着版面缩放**（百分比形态跟：基准换成了真机字号）—— 于是同一张图
 * 只画 180px（4.2 行），预留却是 10 行 × 43px = 430px，空档 = `430 - 180 = 250px`
 * ≈ **5.8 行**。这就是「很突兀」的那段白。
 *
 * ## 判据（本文件钉的四条）
 * 1. 折算后的图高 ≤ 预留块高（图不会压到下一行文字）；
 * 2. 空档与 PC 同量级（PC 是 0；端口只剩行高 19/18 的取整余量，不足 1 行）；
 * 3. 基准未报（headless / 黄金装置 / 既有单测）时逐值不变；
 * 4. 与百分比写法等值（`adapter/HtmlRunFlattenTest` 的「px 与百分比折成同一个高」是同一件事的另一半）。
 */
class PortraitRowGapTest {

    /** 真机 110 列档的一屏（`PageFitter.fit(2400, 1000, 20)` 同解：字号 41 / 行高 43）。 */
    private val deviceMetrics = CellMetrics(41, 43)

    /** 语料 `emuera.config:23` 的 `LINE HEIGHT`（PC 行高）与 :22 的 `FONT SIZE` 同值 18。 */
    private val pcRowPx = 18

    /** QOL 立绘的图高（`默认角色画像横幅`，OPTION.ERB:985 的出厂档）。 */
    private val artHeightScriptPx = 180

    private fun image(html: String, metrics: CellMetrics, scriptFontPx: Int): InlineGraphic.Image =
        HtmlRunFlatten(metrics, scriptFontPixelSize = scriptFontPx)
            .flatten(TextRun(html, 0xC0C0C0, 0, 0, "msgothic", RunKind.HTML))
            .single()
            .graphic as InlineGraphic.Image

    /** 剧本 `@HTML_PRINTL_PX` 的预留行数（QOL_IMAGE.ERB:357 的整数上取整除法）。 */
    private fun reservedRows(heightScriptPx: Int, rowHeightPx: Int): Int =
        ceil(heightScriptPx.toDouble() / rowHeightPx).toInt()

    /**
     * 图底到下一行文字顶边的空档（像素）：预留块高 − 实画高。
     *
     * 预留块的算法（`QOL_IMAGE.ERB:355-366`）：`L_START = LINECOUNT` → `HTML_PRINT HTML_STR`
     * （图挂在**当前行**上，不添行）→ `LINES_NEEDED = ceil(图高 / 18)` → `LINES_DRAWN =
     * LINECOUNT - L_START = 0` → 补打 `LINES_NEEDED` 个 `PRINTL`。第一个 `PRINTL` 只是结束
     * 图那一行，所以**图行 + 9 个空行**共 10 行 = `rows * 行高`，下一行文字正落在第 10 行
     * 的行首（图行算在预留块里，不额外加一行）。
     */
    private fun gapPx(drawnHeightPx: Int, rows: Int, rowHeightPx: Int): Int =
        rows * rowHeightPx - drawnHeightPx

    @Test
    fun `pc 基准：图吃满预留块，图底与下一行文字齐平`() {
        // 参照系（原版行为）：18px 行高 + 180px 图 ⇒ 预留 10 行正好吃满，空档 0。
        val rows = reservedRows(artHeightScriptPx, pcRowPx)
        assertEquals(10, rows, "ceil(180 / 18)")
        assertEquals(0, gapPx(artHeightScriptPx, rows, pcRowPx), "PC 上图底与下一行顶边齐平")
    }

    @Test
    fun `真机 110 列档修复后：图不压字且空档与 pc 同量级`() {
        val rows = reservedRows(artHeightScriptPx, pcRowPx) // 剧本仍按 18 这一把尺算行数
        val drawn = image("<img src='立绘' height='${artHeightScriptPx}px'>", deviceMetrics, pcRowPx).heightPx
        assertEquals(410, drawn, "180 * 41 / 18 —— px 支终于与百分比支同尺")
        assertTrue(drawn <= rows * deviceMetrics.lineHeight, "图高 $drawn 必须落进预留的 $rows 行里，不许压字")
        val gapPx = gapPx(drawn, rows, deviceMetrics.lineHeight)
        assertEquals(20, gapPx, "10 行 430px − 图 410px（行高的 19/18 余量）")
        assertTrue(gapPx.toDouble() / deviceMetrics.lineHeight <= 1.0, "空档不足一行（PC 是 0）")
    }

    @Test
    fun `修复前的形态就是主人看到的那段白（反向钉）`() {
        // 基准未报（= 修复前行为）：图只有 180px，而预留块 430px。
        val rows = reservedRows(artHeightScriptPx, pcRowPx)
        val drawn = image("<img src='立绘' height='${artHeightScriptPx}px'>", deviceMetrics, 0).heightPx
        assertEquals(180, drawn)
        val gapRows = (gapPx(drawn, rows, deviceMetrics.lineHeight) / deviceMetrics.lineHeight.toFloat())
        assertTrue(gapRows > 5f, "旧形态的空档 = ${gapRows.roundToInt()} 行 —— 正是「很大的间距」")
    }

    /**
     * 真机实测档（V2199A / 2400×1080）：启动日志 `字体=36px 半角=18.72px
     * 脚本字号=18px(px 折算分母)` —— 行高按 `字号 * 19/18` 得 38。
     *
     * 这一条钉的是「手机屏上应当读到的数」：折算后 360px，落进 10 行 = 380px 的预留块，
     * 空档 20px（半行）。修复前的构建在同一块屏上是图 180px、预留块 380px ⇒ 空档 200px。
     */
    @Test
    fun `真机 36 档：折算 360px 落进 10 行 380px，空档半行`() {
        val device = CellMetrics(36, 38)
        val drawn = image("<img src='立绘' height='180px'>", device, pcRowPx).heightPx
        assertEquals(360, drawn, "180 * 36 / 18")
        val rows = reservedRows(artHeightScriptPx, pcRowPx)
        assertEquals(380, rows * device.lineHeight, "10 行 × 38px")
        assertEquals(20, gapPx(drawn, rows, device.lineHeight), "380 − 360 = 20px ≈ 0.53 行")
        // 列足迹与 PC 同格：ceil(360 / 18.72) = ceil(180 / 9.36) = 20。
        assertEquals(20, com.eratw.port.render.displaylist.InlineGraphic.cellsFor(drawn, device.cellPixelWidth))
    }

    @Test
    fun `用户把字号改成 24 时尺跟着走`() {
        // 基准取会话生效值而不是常量 18：PC 版面整体放大，px 支也必须按同一比值折。
        val drawn = image("<img src='立绘' height='${artHeightScriptPx}px'>", deviceMetrics, 24).heightPx
        assertEquals(307, drawn, "180 * 41 / 24（整数除）")
    }
}
