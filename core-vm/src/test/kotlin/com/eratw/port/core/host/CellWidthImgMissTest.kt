package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * `<img>` 图元 **miss** 的单出口在 core 折行/行内游标这一侧的读数（三种接线状态）。
 *
 * 判据 = 反编译 `ConsoleImagePart.cs`：
 * - `:44-59` AltText 重建：`<img src='…'` + `srcb`（非 null 才写）+ `srcm`（非空才写）+
 *   `height` / `width` / `ypos`（原始 num != 0 才写，次序同 `:52-57`；px 带后缀、
 *   百分比落 `num * FontSize / 100` 整除 —— `Utils.cs:364-371` + `:87-95`）；
 * - `:60-65` **单出口**：`cImage == null` ⇒ `Text = AltText` 后立刻 `return`，
 *   整段尺寸计算（含 `:66-82`）都不执行 ⇒ **显式 `width` 不生效**；
 * - `:104-115` miss 节点的宽 = `StringMeasure.GetDisplayLength(Text)`，移植侧 =
 *   [CellWidth.displayCells]（与渲染侧 `Grid.cells` 同口径）。
 *
 * 三态的分界（见 [CellWidth.visibleHtmlCells] 的类文档）：
 * 1. 宿主**未接线**（`imageSizeOf == null`）⇒ 判不了 miss，保持旧读数
 *    （显式 `width` 计图形格、缺 `width` 计 0）；
 * 2. 接线且查表 **miss**（或登记的基图高 ≤ 0）⇒ AltText 按文本计格；
 * 3. 接线且 **命中** ⇒ 显式 `width` 或「高 × 基图纵横比」推宽（`:66-71`）。
 */
class CellWidthImgMissTest {

    private val fontPx = 18L

    /** 宿主已接线、但表里没有这张图。 */
    private val missing: (String) -> ImageSizePx? = { null }

    private fun sized(w: Int, h: Int): (String) -> ImageSizePx? = { ImageSizePx(w, h) }

    // ------------------------------------------------------------------
    // 1. 未接线：旧读数逐位不变
    // ------------------------------------------------------------------

    @Test
    fun `未接线时显式宽计图形格_缺宽计零`() {
        assertEquals(
            20,
            CellWidth.visibleHtmlCells("<img src='pic' width='180px'>", fontPx),
            "ceil(180 / 9.36) = 20（接线前的读数）",
        )
        assertEquals(
            0,
            CellWidth.visibleHtmlCells("<img src='pic'>", fontPx),
            "未接线判不了 miss ⇒ 缺宽仍是旧的 0 格",
        )
        assertEquals(180L, CellWidth.graphicWidthPxOrNull("<img src='pic' width='180px'>", fontPx, null))
        assertEquals(0L, CellWidth.graphicWidthPxOrNull("<img src='pic'>", fontPx, null))
    }

    // ------------------------------------------------------------------
    // 2. 接线 + miss：AltText 当文本（显式 width 不生效）
    // ------------------------------------------------------------------

    @Test
    fun `AltText 重建按 height width ypos 次序且 num 为零不写`() {
        // 属性原文乱序 + 空格变体：重建必须归一到 `ConsoleImagePart.cs:52-57` 的次序。
        assertEquals(
            "<img src='pic' srcb='pic_b' height='9' width='180px'>",
            CellWidth.imgAltText("<img width='180px' height='50' srcb='pic_b' src='pic'>", fontPx),
            "height 50% → 50*18/100 = 9（BuilderString 整除，Utils.cs:87-95）；srcb 非 null 照写",
        )
        assertEquals(
            "<img src='p' srcb='b' ypos='-9'>",
            CellWidth.imgAltText("<img src='p' srcb='b' srcm='' height='0' width='0px' ypos='-50'>", fontPx),
            "srcm 空串不写；num == 0 的 height/width 整个属性不写（AddTagMixedNumArg 守卫）",
        )
        assertNull(CellWidth.imgAltText("<img width='10px'>", fontPx), "没有 src 不是图元节点（解析期 CodeEE）")
    }

    @Test
    fun `接线后查表 miss 按 AltText 文本计格且显式宽被忽略`() {
        val tag = "<img width='180px' height='50' src='pic'>"
        val alt = CellWidth.imgAltText(tag, fontPx)!!
        assertNull(
            CellWidth.graphicWidthPxOrNull(tag, fontPx, missing),
            "null = 图元 miss（调用方落 ConsoleImagePart.cs:60-65 的单出口）",
        )
        val cells = CellWidth.visibleHtmlCells(tag, fontPx, missing)
        assertEquals(CellWidth.displayCells(alt), cells, "宽 = AltText 的显示格（:104-115）")
        assertEquals(40, cells, "重建文案 `<img src='pic' height='9' width='180px'>` = 40 半角格")
        assertNotEquals(20, cells, "显式 width='180px' 不生效 —— 原版 return 在尺寸计算之前")
    }

    @Test
    fun `基图高为零按 miss 处理`() {
        val tag = "<img src='pic' width='180px'>"
        assertNull(
            CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(100, 0)),
            "原版这里是 :69 的整数除零；端口按 ADR-005 登记为 miss",
        )
        assertEquals(
            CellWidth.displayCells(CellWidth.imgAltText(tag, fontPx)!!),
            CellWidth.visibleHtmlCells(tag, fontPx, sized(100, 0)),
        )
    }

    // ------------------------------------------------------------------
    // 3. 接线 + 命中：显式宽 / 纵横比推宽
    // ------------------------------------------------------------------

    @Test
    fun `接线命中时显式宽照旧计图形格`() {
        val tag = "<img src='pic' width='180px' height='50'>"
        assertEquals(180L, CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(64, 32)))
        assertEquals(20, CellWidth.visibleHtmlCells(tag, fontPx, sized(64, 32)))
    }

    @Test
    fun `接线命中且缺宽时按基图纵横比推宽`() {
        val tag = "<img src='pic' height='30px'>"
        assertEquals(
            60L,
            CellWidth.graphicWidthPxOrNull(tag, fontPx, sized(100, 50)),
            "100 * 30 / 50（ConsoleImagePart.cs:69 的整数除法）",
        )
        assertEquals(7, CellWidth.visibleHtmlCells(tag, fontPx, sized(100, 50)), "ceil(60 / 9.36) = 7")
    }
}
