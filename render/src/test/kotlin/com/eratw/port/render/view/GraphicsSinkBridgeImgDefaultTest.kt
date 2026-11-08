package com.eratw.port.render.view

import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PRINT_IMG` 缺 `width` 的**登记面**（EP3 L12 · 挖虫 rec105 #5）。
 *
 * 判性：原版缺宽（或宽写 0）走**基图纵横比** ——
 * `ConsoleImagePart.cs:67-71` 的 `base.Width = cImage.DestBaseSize.Width * 高 / DestBaseSize.Height`
 * （亚像素余数进 `XsubPixel`），`raw_width == null` 与 `raw_width.num == 0` 同一条支。
 * 本 sink 不查精灵表、拿不到 `DestBaseSize`，于是按「见方」占位（`heightPx`）
 * —— **这是移植自创的偏差，不是官方同形**，而且绘制侧也不会替它补纵横比
 * （`view/GraphicGeometry.kt:112` 的 `inlineSize` 只在 `widthPx <= 0` 时才回落帧宽）。
 *
 * 判死依据（2026-11-02 实测语料 = 画蛇添足版 eratw4.980）：`PRINT_IMG` **语句本体 0 处**。
 * 全游戏根含 "PRINT_IMG" 的只有 69 行 / 13 个文件，逐行看是 `CALL K63_PRINT_IMG`
 * （同名用户函数，定义在 `ERB/口上・メッセージ関連/個人口上/063 Kagerou [影狼]/影狼/
 * M_KOJO_K63_独自関数.ERB:2405`）+ 3 行 `;` 注释（`ERB/ステータス表示関連/IMAGE.ERB:504`、
 * `IMAGE2.ERB:575` 及其 patches 副本）+ 1 行编辑器关键字帮助（`ERB_EXCOM.khp`，非脚本）。
 * 语料里真正成规模出现的「缺 width」形态走 HTML 那一路（48 枚 `<img>` 标签里 **27 枚不写
 * `width`**，如 `ERB/DLC/神签/神签.ERB:64` 的 `<img src='神签_{ARG}' height='{IMG_H_PX}px' ypos='0'>`、
 * `ERB/SHOP関連/TEST.ERB:687` 的 `<img src='阴阳鱼' height='200' ypos='40'>`），
 * 那条路的纵横比缺省在 `adapter/HtmlRunFlatten.kt:601-611` 里读 `sizeSource?.destSize(src)`，
 * 与原版同式 —— 本文件登记的偏差**只在语句路**，而语句路 0 处命中。
 *
 * 所以本文件**不改语义**，只把「语句路当前是见方占位」钉成显式登记：将来把尺寸源
 * 注进 [GraphicsSinkBridge]（签名见交付「需组长接线」）时，下面三条读数就是要一起翻的数。
 */
class GraphicsSinkBridgeImgDefaultTest {

    private val metrics = CellMetrics(requestedFontPixelSize = 20, requestedLineHeight = 24)

    private fun drain(layer: GraphicsLayer): List<InlineGraphic> = layer.drainInline()

    /** 只缺宽（宽写 0）而高给了 200% ⇒ 原版按纵横比，我们按见方 = 40px。 */
    @Test
    fun `缺宽登记的见方占位_高给定时宽等于高`() {
        val layer = GraphicsLayer()
        // 槽位：param[0] = 宽、param[1] = 高、param[2] = ypos（FunctionIdentifier.cs:427-439）
        GraphicsSinkBridge(layer, metrics).printImage(
            name = "pic",
            nameb = null,
            namem = null,
            params = listOf(0L to false, 200L to false),
        )
        val img = drain(layer).single() as InlineGraphic.Image
        assertEquals(40, img.heightPx, "200% * FontSize 20")
        assertEquals(40, img.widthPx, "缺宽登记形态：按 heightPx 见方占位（原版此处是纵横比推宽）")
        assertEquals(
            InlineGraphic.cellsFor(40, metrics.cellPixelWidth),
            img.widthCells,
            "占位宽同时是占格数 —— 偏差会连着推进量一起登记在这里",
        )
    }

    /** 高与宽都没给 ⇒ 两支都落缺省：高 = 一行高（`ConsoleImagePart.cs:66`），宽跟着见方。 */
    @Test
    fun `宽高全缺时按一行高见方`() {
        val layer = GraphicsLayer()
        GraphicsSinkBridge(layer, metrics).printImage("pic", null, null, emptyList())
        val img = drain(layer).single() as InlineGraphic.Image
        assertEquals(20, img.heightPx, "缺高 = FontSize 一行高")
        assertEquals(20, img.widthPx, "缺宽 = 跟高见方（登记偏差）")
        assertEquals(0, img.yOffsetPx)
    }

    /** 反向对照：宽给了非 0 值就走正常支，本偏差不得传染到它（`ConsoleImagePart.cs:72-80`）。 */
    @Test
    fun `给了宽度时不得落进见方占位支`() {
        val layer = GraphicsLayer()
        GraphicsSinkBridge(layer, metrics).printImage(
            name = "pic",
            nameb = null,
            namem = null,
            params = listOf(70L to true, 200L to false),
        )
        val img = drain(layer).single() as InlineGraphic.Image
        assertEquals(70, img.widthPx, "px 形态原样（未接基准字号时恒等）")
        assertEquals(40, img.heightPx, "高仍按百分比折 FontSize")
        assertTrue(img.widthPx != img.heightPx, "见方占位只属于缺宽那一支")
    }
}
