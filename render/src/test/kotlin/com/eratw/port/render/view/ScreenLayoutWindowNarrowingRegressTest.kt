package com.eratw.port.render.view

import com.eratw.port.render.glyph.CellMetrics
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `C-WINDOW-NARROW-REGRESS@1`：0.2.43「幻想乡之主改好感页 CFLAG_DEBUG 行582 除数为0」的
 * **根因层断言**（纯 JVM，不需要设备）。
 *
 * ## 玩家报的形状
 *
 * `DEBUG.ERB:575` 的 `PRINTNUM = MAXWIDTH()/90` 在窄版面下得 0，`:582` 的
 * `LOCAL:1 % PRINTNUM` 抛官方 `DivideByZero`「除数为0的除法运算」；同一函数 `:570`
 * 用的是 `MAXWIDTH()/45`，仍得 1，所以**报错固定在 582**。
 * `MAXWIDTH()` = `HTML_STRINGLEN(DRAWLINESTR)` = `ceil(2 × htmlLength / FontSize)`
 * （`HtmlManager.cs:168-182` + `FunctionMethodCreator.cs:34-68`），`DRAWLINESTR` 由
 * `LineBufferConsoleSink.stBar` 按 [ScreenLayout.columns] 个半角格生成。
 *
 * ## 0.2.42 → 0.2.43 的唯一差异
 *
 * `0664085e` 首次把 `emuera.config` 的 `WINDOW WIDTH` 当**版面输入**灌进
 * `CanvasConsoleView.applySourceWindowMetrics` → `ScreenLayout.resolve` 第 5 步。
 * 旧玩家盘上的配置没有该键（升级只补缺键、不覆盖），读到的是 `ConfigSpec` 裸缺省
 * **760**（`ConfigData.cs:56` 的 PC 值）；0.2.42 完全不读这个键。
 *
 * ## 本用例钉的三件事（任一环断掉即红）
 *
 * 1. **反向证明**：把 760 折成设备 px 灌进去 ⇒ 版面收窄到 80 列 ⇒ `MAXWIDTH()/90 == 0`
 *    （复现玩家报错），且 `MAXWIDTH()/45 != 0`（复现「固定在 582」）；
 * 2. **正向证明**：未接线（`windowWidthPx = 0`，即 0.2.42 与修复后的生产形态）
 *    ⇒ 满宽 139 列 ⇒ `MAXWIDTH()/90 == 1`，不除零；
 * 3. **闭环读数**：窄版面反折回的脚本 px 恰好又播成 `WindowX` ⇒ `drawableWidth`
 *    同步缩到 757，两把尺一起塌（解释为什么不是单纯「屏变窄」）。
 *
 * 算式里的版面宽/列数全部取自生产 [ScreenLayout.resolve] 与
 * [CellMetrics.scriptPixels]，只有 `MAXWIDTH()` 那一截按官方公式独立复算 ——
 * 不引用生产分支，否则两边一起漂、用例恒绿（同 `ScreenLayoutWindowLayoutTest`
 * 的等价金标纪律）。
 */
class ScreenLayoutWindowNarrowingRegressTest {

    /** 仓内既有算例（`ScreenLayoutWindowLayoutTest` / `ConfigSeedWindowWidthTest` 同一组读数）。 */
    private companion object {
        const val CONTENT_WIDTH_PX = 2400
        const val DRAWABLE_WIDTH_PX = 2395 // 物理内容宽扣掉 5px 形状预留（字号 33 的 shift）
        const val HEIGHT_PX = 1080
        const val DENSITY_DPI = 400
        const val SCRIPT_FONT_PX = 18
        const val VIEW_FONT_PX = 33
        /** `ConfigSpec.kt:113` 的 `WindowX` 引擎裸缺省（PC 主窗口宽）。 */
        const val LEGACY_WINDOW_X = 760
        /** `DEBUG.ERB:561` 与 `:575` 的两个除数。 */
        const val DIVISOR_FIRST_LOOP = 45
        const val DIVISOR_SECOND_LOOP = 90
    }

    /**
     * 官方 `MAXWIDTH()` 的等价复算：`HTML_STRINGLEN(DRAWLINESTR)` 读的是**第一显示行**的
     * 像素宽（`HtmlManager.cs:168-182`），超 `Config.DrawableWidth` 会折行；
     * plain 分支再按 `ceil(2 × width / FontSize)` 折成格数
     * （`FunctionMethodCreator.cs:54-66`，`HtmlMethods.kt:95-109`）。
     *
     * 半角 `DRAWLINE STRING` 缺省 `"-"`，等宽近似下 1 格 = `FontSize/2` 脚本 px。
     */
    private fun maxWidth(columns: Int, drawableWidthScriptPx: Int): Int {
        val linePx = columns * (SCRIPT_FONT_PX / 2)
        val firstRowPx = minOf(linePx, drawableWidthScriptPx.coerceAtLeast(0))
        return ceil(2.0 * firstRowPx / SCRIPT_FONT_PX).toInt()
    }

    /** `EffectiveConfig.drawableWidth = WindowX - max(2, FontSize/6)`（`Config.cs:461-466`）。 */
    private fun drawableWidth(windowXScriptPx: Int): Int =
        windowXScriptPx - max(2, SCRIPT_FONT_PX / 6)

    /** `CanvasConsoleView.windowWidthScriptPx()`：版面宽按 `k = 真机字号 / 脚本字号` 反折。 */
    private fun inverseScriptPx(layoutWidthPx: Int): Int {
        val ratio = VIEW_FONT_PX.toFloat() / SCRIPT_FONT_PX
        return (layoutWidthPx / ratio).roundToInt()
    }

    private fun resolve(windowWidthPx: Int): ScreenLayout = ScreenLayout.resolve(
        widthPx = DRAWABLE_WIDTH_PX,
        heightPx = HEIGHT_PX,
        densityDpi = DENSITY_DPI,
        configFontPx = SCRIPT_FONT_PX,
        contentWidthPx = CONTENT_WIDTH_PX,
        windowWidthPx = windowWidthPx,
    )

    // ------------------------------------------------------------------
    // 1) 反向证明：760 灌进版面 ⇒ 80 列 ⇒ DEBUG.ERB:582 除零
    // ------------------------------------------------------------------

    @Test
    fun `配置裸缺省760灌进版面时列数掉到90以下且MAXWIDTH除90得0`() {
        // GameHost.applyBootFontMetrics 曾把 effective.windowX 直接交给 View；
        // CanvasConsoleView.windowWidthPxFor 用候选度量按 scriptPixels 折设备 px。
        val metrics = CellMetrics(VIEW_FONT_PX, 35)
        val windowWidthPx = metrics.scriptPixels(LEGACY_WINDOW_X, SCRIPT_FONT_PX)
        assertEquals(1393, windowWidthPx, "760 脚本 px × 33/18 = 1393 设备 px")
        assertTrue(windowWidthPx < CONTENT_WIDTH_PX, "该窗口宽确实窄于物理内容宽 ⇒ 会触发收窄")

        val layout = resolve(windowWidthPx)
        assertEquals(1393, layout.layoutWidthPx, "版面宽被夹到窗口宽")
        assertEquals(VIEW_FONT_PX, layout.fontPixelSize, "列数守护只按物理宽压字号，窄化路径不改字号")
        assertEquals(80, layout.columns, "窄化后列数绕过 136 列守护，掉到 80")

        // 窄版面反折回脚本 px 又经 seedHostState 播成 WindowX ⇒ drawableWidth 同步缩水。
        val seededWindowX = inverseScriptPx(layout.layoutWidthPx)
        assertEquals(760, seededWindowX, "同尺逆折算把窄版面又折回 760 脚本 px（闭环）")
        val drawable = drawableWidth(seededWindowX)
        assertEquals(757, drawable, "Config.DrawableWidth = 760 - max(2, 18/6)")

        val width = maxWidth(layout.columns, drawable)
        assertEquals(80, width, "MAXWIDTH() = DRAWLINESTR 第一显示行宽折成格数")
        assertTrue(
            width / DIVISOR_FIRST_LOOP != 0,
            "DEBUG.ERB:570 的 /45 仍非 0 ⇒ 第一个循环不报错",
        )
        assertEquals(
            0,
            width / DIVISOR_SECOND_LOOP,
            "DEBUG.ERB:575 的 /90 得 0 ⇒ :582 的取模必抛「除数为0的除法运算」",
        )
        // 报错区间的定量边界：玩家屏文落在 582 而不是 570，把 MAXWIDTH() 锁在 [45, 89]。
        assertTrue(width in DIVISOR_FIRST_LOOP until DIVISOR_SECOND_LOOP, "MAXWIDTH() 落在 45..89")
    }

    // ------------------------------------------------------------------
    // 2) 正向证明：未接线（0.2.42 / 修复后）⇒ 满宽 139 列 ⇒ 不除零
    // ------------------------------------------------------------------

    @Test
    fun `窗口宽未接线时满宽解算MAXWIDTH除90非0`() {
        val layout = resolve(0)
        assertEquals(CONTENT_WIDTH_PX, layout.layoutWidthPx, "未接线 ⇒ 版面宽 = 物理内容宽")
        assertEquals(0, layout.contentInsetLeftPx, "满宽 ⇒ 无居中留白")
        assertEquals(VIEW_FONT_PX, layout.fontPixelSize)
        assertEquals(139, layout.columns, "满宽列数（列数守护 136 生效）")

        // 未接线 ⇒ windowWidthScriptPx() 返回 0 ⇒ seedHostState 回落 host.clientWidth；
        // 自 009c5e81 起 CLIENTWIDTH 已折回脚本 px（设备 2400 × 18/33）。
        val clientWidthScriptPx = (CONTENT_WIDTH_PX.toFloat() * SCRIPT_FONT_PX / VIEW_FONT_PX).roundToInt()
        assertEquals(1309, clientWidthScriptPx, "回落读数是脚本 px，与屏上可绘制宽同源")
        val drawable = drawableWidth(clientWidthScriptPx)
        assertEquals(1306, drawable)

        val width = maxWidth(layout.columns, drawable)
        assertEquals(139, width, "DRAWLINESTR 139 格不折行 ⇒ MAXWIDTH() = 139")
        assertEquals(1, width / DIVISOR_SECOND_LOOP, "/90 非 0，582 行不除零")
        assertTrue(width >= ScreenLayout.DEFAULT_COLUMNS, "满宽档守住了 136 列守护下限")
    }

    // ------------------------------------------------------------------
    // 3) 两态对拍：唯一的输入差就是「配置宽有没有被灌进版面」
    // ------------------------------------------------------------------

    @Test
    fun `断开配置宽输入后字号与行高逐值不变只有列数回到守护档`() {
        val wired = resolve(CellMetrics(VIEW_FONT_PX, 35).scriptPixels(LEGACY_WINDOW_X, SCRIPT_FONT_PX))
        val unwired = resolve(0)
        assertEquals(unwired.fontPixelSize, wired.fontPixelSize, "窄化不改变字号（守护只吃物理宽）")
        assertEquals(unwired.lineHeight, wired.lineHeight, "窄化不改变行高")
        assertTrue(unwired.columns > wired.columns, "断开输入后列数回升")
        assertTrue(
            maxWidth(unwired.columns, drawableWidth(1309)) / DIVISOR_SECOND_LOOP > 0 &&
                maxWidth(wired.columns, drawableWidth(760)) / DIVISOR_SECOND_LOOP == 0,
            "只有灌入配置宽的那一态会触发 CFLAG_DEBUG 行582 除零",
        )
    }
}
