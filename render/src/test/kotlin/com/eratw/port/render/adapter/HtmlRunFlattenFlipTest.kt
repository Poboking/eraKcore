package com.eratw.port.render.adapter

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B2 定向单测：负尺寸的**镜像标记**。
 *
 * ## 原版语义（`ConsoleImagePart.cs:83-92`，本仓 reference 实读）
 *
 * ```
 * destRect = new Rectangle(0, top, base.Width, num);   // :82
 * if (destRect.Width < 0)  { destRect.X = -destRect.Width;  base.Width = -destRect.Width; }
 * if (destRect.Height < 0) { destRect.Y -= destRect.Height; num = -destRect.Height; }
 * ```
 *
 * 目标矩形带**负宽/负高**交给 GDI+，画出来是镜像而不是「不画」：
 * - 负宽 `-5`，起点 0 ⇒ 矩形 `(X=5, W=-5)` ⇒ 覆盖 `[0, 5]` 且**水平翻转**；
 * - 负高 `-10`，`top` 不变 ⇒ 矩形 `(Y=top+10, H=-10)` ⇒ 覆盖 `[top, top+10]` 且**垂直翻转**，
 *   `bottom = top + num`（`:93`）因此仍是 `top + |H|`。
 *
 * 布局侧（`HtmlRunFlatten.emitImage`）已按绝对值占位（占宽恒为正，与原版 `base.Width` 一致），
 * 本组用例钉的是**新加的两个标记** `flipX` / `flipY` —— 没有它们，绘制侧根本不知道
 * 该不该镜像。
 *
 * 判据看的是**折算后**的宽/高（`destRect.Width < 0` / `destRect.Height < 0`），不只是
 * 属性原文：`width` 没给、由负 `height` 按纵横比派生（`:69`）时派生宽同样是负的，
 * 于是两个轴一起翻 —— 这是原版行为，见「只给负 height」那条用例。
 *
 * 与 `ypos` 的界线：`ypos` 负值只是「往上摆」（`:81`），**不是**翻转。
 */
class HtmlRunFlattenFlipTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> =
        HtmlRunFlatten(metrics).flatten(
            TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML),
        )

    private fun image(html: String): InlineGraphic.Image =
        flatten(html).single { it.graphic != null }.graphic as InlineGraphic.Image

    /** `width='-5px'` ⇒ 水平镜像，宽仍按绝对值占位（`ConsoleImagePart.cs:83-87`）。 */
    @Test
    fun `负宽是水平镜像且宽按绝对值占位`() {
        val g = image("<img src='a' width='-5px' height='10px'>")
        assertTrue(g.flipX, "destRect.Width < 0 ⇒ X = -Width、Width = -Width 画镜像")
        assertFalse(g.flipY, "height 是正数，纵轴不翻")
        assertEquals(5, g.widthPx, "布局侧占宽 = |W|（原版同时把 base.Width 改成 |W|）")
        assertEquals(10, g.heightPx)
        assertTrue(g.widthCells > 0, "占格恒为正，否则列游标会回退")
    }

    /** `height='-10px'` ⇒ 垂直镜像，高按绝对值占位（`ConsoleImagePart.cs:88-92`）。 */
    @Test
    fun `负高是垂直镜像且高按绝对值占位`() {
        val g = image("<img src='a' height='-10px'>")
        assertTrue(g.flipY, "destRect.Height < 0 ⇒ Y -= Height、num = -Height 画镜像")
        assertEquals(10, g.heightPx, "布局侧占高 = |H|，bottom = top + |H|（:93）")
        // 只给负 height 时宽由纵横比派生（:69）：没有基图表时直接退 heightPx（负值）
        // ⇒ 派生宽也是负的，`:83` 那一支同样成立 ⇒ 水平也翻。这是原版行为，不是本端口的
        // 额外发明：`base.Width = DestBaseSize.Width * num / DestBaseSize.Height` 里 num 为负。
        assertTrue(g.flipX, "派生宽为负 ⇒ 两个轴一起镜像（原版 destRect.Width < 0 同样成立）")
        assertEquals(10, g.widthPx)
    }

    /** 正常尺寸：两个标记都是 false（不得给普通图挂上镜像）。 */
    @Test
    fun `正常尺寸两个标记都是false`() {
        val g = image("<img src='a' width='20px' height='20px'>")
        assertFalse(g.flipX)
        assertFalse(g.flipY)
        assertEquals(20, g.widthPx)
        assertEquals(20, g.heightPx)
    }

    /** `width='-50'`（百分比形态）同样折算成负像素 ⇒ 翻转，且尺寸取绝对值。 */
    @Test
    fun `负的百分比宽同样翻转`() {
        val g = image("<img src='a' width='-50' height='20px'>")
        assertTrue(g.flipX)
        assertFalse(g.flipY)
        assertEquals(9, g.widthPx, "18 * -50 / 100 = -9 ⇒ |−9|")
        assertEquals(1, g.widthCells, "ceil(9 / 9.36) = 1")
    }

    /** `ypos` 负值只是往上摆（`:81`），不是翻转 —— 两者别混。 */
    @Test
    fun `负ypos不是翻转`() {
        val g = image("<img src='a' width='20px' height='20px' ypos='-10px'>")
        assertFalse(g.flipX)
        assertFalse(g.flipY)
        assertEquals(-10, g.yOffsetPx)
    }

    /** 缺省尺寸（`height` 没给 ⇒ 一行高）不产生翻转。 */
    @Test
    fun `缺省尺寸不翻转`() {
        val g = image("<img src='a'>")
        assertFalse(g.flipX)
        assertFalse(g.flipY)
        assertEquals(18, g.heightPx)
    }
}
