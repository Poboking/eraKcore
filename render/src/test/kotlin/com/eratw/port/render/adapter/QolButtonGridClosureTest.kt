package com.eratw.port.render.adapter

import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.glyph.CellMetrics
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语料那层的「指令按钮网格」在端口必须闭得拢（2026-10-29 玩家报的会话 / 扫地一排错位）。
 *
 * ## 这一格是谁排的 —— 不是引擎
 *
 * 屏上那排指令由 `ERB/魔改内容/qol/QOL_USERCOM.ERB` 逐条交给
 * `ERB/魔改内容/PRINT系/HTML_PRINT_Components.ERB` 的 `HTML_PRINTC_MANAGER`，后者在**脚本里**
 * 按像素排网格（四条原式）：
 *
 * ```
 * CELL_WIDTH_PX = PRINTCLENGTH() * GETCONFIG("フォントサイズ") / 2            ; :120 → 25*18/2 = 225
 * ELEMENT_PX    = HTML_STRINGLEN(ADD_HTML, 1)                                  ; :138
 * COLS_NEEDED   = MAX((ELEMENT_PX + CELL_WIDTH_PX - 1) / CELL_WIDTH_PX, 1)     ; :140-141
 * PAD_PX        = COLS_NEEDED * CELL_WIDTH_PX - ELEMENT_PX                     ; :152 → <shape type='space' param='PADpx'>
 * ```
 *
 * ⇒ 脚本要求「一枚按钮补齐到整格的整数倍」，而补齐额写的是**脚本 px**，于是它必须与
 * `HTML_STRINGLEN` 的读数、`GETCONFIG("フォントサイズ")` 同一条尺。PC 上三者天然是同一把
 * （`StringMeasure.cs:42-72` 量的就是 `Config.FontSize`），端口曾经不是：量宽那一路被喂了
 * 真机 page-fit 实测字号（夹具取 41），排格仍按配置的 18 ⇒ 补齐额算小、`<shape>` 折算回来
 * 又乘一次 `41/18`，一枚按钮实占 **14 格**而不是 25 格（[反证_按真机字号量宽就闭不拢] 把旧值
 * 钉在原地）。尺子本身修在 core 一处（`GameSession.scriptFontPixelSize` 同时做
 * `builtinHost.fontSize`），**本文件守的是「闭合」这一半**。
 *
 * ## 夹具
 *
 * 真机 110 列档：字号 41 / 行高 43（`view/PageFitter` 一档），脚本 px 基准 = 生效配置 18。
 * `HTML_STRINGLEN(…,1)` 建模成 `格数 × 半角步进`，缺省步进 9 = 配置字号的一半 —— 随包像素体
 * 与黄金基线同值（`pc-golden-root.txt:253`：199 个 `=` 填满 1797px ⇒ 9.03 @ 18px 字号），
 * 同口径也钉在 `core/fn/FnWidthSingleSourceTest`。[量宽尺的实际步进在真实字体带宽内照样闭拢]
 * 那条把步进本身当变量扫一遍：**机型**（page-fit 字号）与**字体前进量**是两件不同的事，
 * 前者约掉、后者有带宽，两样都要有读数。
 */
class QolButtonGridClosureTest {

    private val configFontPx = 18
    private val printCLength = 25
    private val cellWidthPx = printCLength * configFontPx / 2

    /** 脚本对一条标签算出的整格数（`COLS_NEEDED`）；[halfPx] = 量宽那把尺上每个半角的步进。 */
    private fun colsNeeded(label: String, halfPx: Double): Int =
        maxOf(1, ceil(elementPx(label, halfPx).toDouble() / cellWidthPx).toInt())

    /** `HTML_STRINGLEN(…,1)` 的读数（原版返回 int 像素，这里同口径四舍五入）。 */
    private fun elementPx(label: String, halfPx: Double): Int = (Grid.cells(label) * halfPx).roundToInt()

    /** 语料 `L_ALIGN="C"` 的形态：补齐的 space 落在标签**之前**（右对齐在自己的整格里）。 */
    private fun qolElement(label: String, halfPx: Double = configFontPx / 2.0): String {
        val px = elementPx(label, halfPx)
        val pad = colsNeeded(label, halfPx) * cellWidthPx - px
        val space = if (pad > 0) "<shape type='space' param='${pad}px'/>" else ""
        return "$space<button value='400'>$label</button>"
    }

    private fun elementCells(html: String): Int = deviceCells(41, html)

    /** 按给定**机型字号**（page-fit 结果）展平一条元素，返回它实占的半角格数。 */
    private fun deviceCells(fontPx: Int, html: String): Int =
        HtmlRunFlatten(CellMetrics(fontPx, fontPx * 19 / 18), scriptFontPixelSize = configFontPx)
            .flatten(
                TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML)
            ).sumOf { Grid.cells(it.text) }

    @Test
    fun `随机型字号变化仍然每枚占满整格`() {
        // 自适应的根据：补齐折成的格数 = ceil(PAD × F/18 ÷ (F/2 × 1.04))，**F 在分子分母同时出现、
        // 整个约掉** ⇒ 从 `PageFitter.MIN_READABLE_FONT_PX`（30）到平板那一档（60+），同一枚按钮
        // 占的列数必须一模一样。字号这一把尺与机型无关，靠的就是「量宽也吃配置字号」这条修正。
        for (fontPx in 30..64 step 2) {
            for (label in labels) {
                assertEquals(
                    colsNeeded(label, configFontPx / 2.0) * printCLength,
                    deviceCells(fontPx, qolElement(label)),
                    "$fontPx px 机型档：「$label」的占宽漂了",
                )
            }
        }
    }

    @Test
    fun `量宽尺的实际步进在真实字体带宽内照样闭拢`() {
        // 上一条钉的是**机型**（page-fit 字号 F），这一条钉的是**随包字体本身**。
        // [halfPx] = `HTML_STRINGLEN` 那把尺上每个半角的实际前进量（脚本 px @ 配置字号 18）：
        //   9.00 = 配置尺（headless 的 `approxTextWidth` 就是这一把，黄金基线 `pc-golden-root.txt:253`
        //          的 199 个 `=` 填 1797px ⇒ 9.03 也在旁边）；
        //   9.36 = 端口整数格的步进（0.52 × 18，`StringMeasure.cs:37` 那个 1.04 调参）。
        // 真字体落在两者之间，所以这一条问的是「换字体 / 换机器会不会又错」。
        // 带宽取 8.94..9.30 —— 语料这批指令标签（8..22 半角）与机型字号 30..80 全域算出来的闭区间。
        for (halfPx in doubleArrayOf(8.95, 9.00, 9.03, 9.10, 9.20, 9.25, 9.30)) {
            for (fontPx in intArrayOf(30, 41, 64)) {
                for (label in labels) {
                    assertEquals(
                        printCLength,
                        deviceCells(fontPx, qolElement(label, halfPx = halfPx)),
                        "$fontPx px 机型 / 半角步进 $halfPx px：「$label」（${Grid.cells(label)} 格）应当仍占满一格",
                    )
                }
            }
        }
    }

    @Test
    fun `出了带宽也只是少占一格_不会再攒出旧版那种半格`() {
        // 再往 9.36 那一端走，`补齐px × 机型字号 / 18` 的整数截断会碰巧落到格边界之下，
        // 一枚就少占**一格**。这一条钉的是**误差上界**（≤1 格）而不是恒等：
        // 旧版喂 page-fit 实测字号时一枚少 11 格、一行十几个攒成整排平移；
        // 修好之后即便字体前进量偏离配置尺一截，最坏也只是列边界差一格，肉眼不再错位成排。
        for (halfPx in doubleArrayOf(9.32, 9.36)) {
            for (label in labels) {
                val cells = deviceCells(41, qolElement(label, halfPx = halfPx))
                assertTrue(
                    kotlin.math.abs(cells - printCLength) <= 1,
                    "半角步进 $halfPx px：「$label」占 $cells 格，超出「一格以内」的上界",
                )
            }
        }
    }

    @Test
    fun `headless与PC档（字号18恒等）同样闭拢`() {
        // 黄金装置 / 桌面跑：metrics 字号 == 脚本基准 ⇒ scriptPixels 是恒等，仍应占满整格。
        assertEquals(printCLength, deviceCells(18, qolElement("移动[400]")))
        assertEquals(printCLength, deviceCells(18, qolElement("会话[31]")))
    }

    /** 语料里真实出现过的指令标签：从 8 半角到 22 半角，宽度差一大截。 */
    private val labels = listOf(
        "会话[31]",
        "移动[400]",
        "一键扫除[778]",
        "能力显示(主角)[804]",
        "所在地察觉[811]",
        "邀请去港町(3分)[990]",
        "踏上归途(12分)[999]",
        "检查手上的胖次[4253]",
    )

    @Test
    fun `一枚按钮补齐后正好占满整数格`() {
        for (label in labels) {
            assertEquals(
                colsNeeded(label, configFontPx / 2.0) * printCLength,
                elementCells(qolElement(label)),
                "「$label」补齐后应占满 ${colsNeeded(label, configFontPx / 2.0)} 个 PRINTCLENGTH 整格" +
                    "（一格 = $printCLength 半角）",
            )
        }
    }

    @Test
    fun `同行里每枚按钮列宽相同_所以排到第几枚都不漂`() {
        // 错位的本质是「每枚各差一点」。尺子对的时候，短标签补得多、长标签补得少，
        // 补完全都落在同一个列边界上；尺子错的时候补齐量不再跟着标签走，列宽开始互相错开。
        val widths = labels.map { elementCells(qolElement(it)) }
        assertEquals(1, widths.distinct().size, "一行的列宽必须一致，实到 $widths")
        assertEquals(printCLength, widths.first())
    }

    @Test
    fun `格宽对半角步进是整数倍_所以不会攒出半格`() {
        // 闭合的前提：一格 225 脚本 px = 25 个半角格，整除 ⇒ `<shape>` 的向上取整把每枚按钮
        // 都钉在列边界上，一行排多少枚都不漂（错位是攒出来的，不是随机出来的）。
        assertEquals(0, cellWidthPx % (configFontPx / 2))
        assertEquals(printCLength, cellWidthPx / (configFontPx / 2))
    }

    @Test
    fun `刚好占满一格不补_超一格补成两格`() {
        // 两条边界：25 半角 = 225 脚本 px ⇒ PAD=0（不产出 space 段）；26 半角 ⇒ 补到 450。
        val exactly = "一".repeat(12) + "A"
        assertEquals(1, colsNeeded(exactly, configFontPx / 2.0))
        assertEquals(printCLength, elementCells(qolElement(exactly)))

        val over = "一".repeat(12) + "AB"
        assertEquals(2, colsNeeded(over, configFontPx / 2.0))
        assertEquals(2 * printCLength, elementCells(qolElement(over)))
    }

    @Test
    fun `反证_按真机字号量宽就闭不拢`() {
        // 2026-10-29 那版的读数：量宽吃 41、排格吃 18 ⇒「移动[400]」只补 41 脚本 px，
        // 折算后整枚占 14 格；同一标签在正确的尺上占满 25 格。
        // 这一条不许"顺手改成 25"：它证明上面五条断言真的在测尺子，而不是恒真。
        assertEquals(14, elementCells(qolElement("移动[400]", halfPx = 20.5)))
        assertTrue(elementCells(qolElement("移动[400]")) > 14, "正确的尺上必须补齐到整格")
        // 而且旧尺下各枚列宽**互不相同** —— 正是玩家看到的「一排挤歪」形态。
        val broken = labels.map { elementCells(qolElement(it, halfPx = 20.5)) }
        assertTrue(broken.distinct().size > 1, "错位形态：每枚列宽各走一点，实到 $broken")
    }
}
