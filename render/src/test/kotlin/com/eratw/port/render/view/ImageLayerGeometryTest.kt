package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WIRE-3 / WIRE-4 的纯 JVM 几何与颜色换算钉（[ImageLayerGeometry]）。
 *
 * 每条断言对着 `specs/isa.yaml:2512` 的 SETIMAGELAYERL 坐标公式与 `:2456` 的 TEXT_BGC
 * alpha 折算；形态依据 = 画蛇添足版 `Flan_UI.ERB:148-153`（xpos 已扣 `L_SHAPE_SHIFT`，
 * 本层再加回）与 `:296/:314/:325`（对齐测试）。
 */
class ImageLayerGeometryTest {

    // ---------------------------------------------------------------
    // 图层横坐标：x = contentLeft + ShapePositionShift + xpos
    // ---------------------------------------------------------------

    @Test
    fun `图层横坐标等于 ShapePositionShift 加 xpos 再加内容区左沿`() {
        // PC 基准 FontSize=18 ⇒ shift=3；xpos=0 与该行 img 左沿对齐
        assertEquals(3f, ImageLayerGeometry.layerLeftPx(0f, 3, 0))
        assertEquals(203f, ImageLayerGeometry.layerLeftPx(0f, 3, 200))
        // Flan_UI.ERB:148 的形态：脚本已从 xpos 扣除 shift（L_SHAPE_SHIFT），本层加回 ⇒ 净 0
        assertEquals(0f, ImageLayerGeometry.layerLeftPx(0f, 3, -3))
        // 内容区左沿（paddingLeft）参与
        assertEquals(40f, ImageLayerGeometry.layerLeftPx(40f, 3, -3))
    }

    // ---------------------------------------------------------------
    // 图层纵坐标：y = rowTop + LineHeight - ClientHeight + (imageHeight - LineHeight) + ypos
    // ---------------------------------------------------------------

    @Test
    fun `图层纵坐标按 isa 四项公式逐项算`() {
        // 100 + 20 - 500 + (200 - 20) + 0 = -200
        assertEquals(-200f, ImageLayerGeometry.layerTopPx(100f, 20f, 500f, 200f, 0))
        // ypos 平移
        assertEquals(-190f, ImageLayerGeometry.layerTopPx(100f, 20f, 500f, 200f, 10))
        // imageHeight == LineHeight 时该项抵消（只剩 rowTop - ClientHeight）
        assertEquals(-380f, ImageLayerGeometry.layerTopPx(100f, 20f, 500f, 20f, 0))
    }

    @Test
    fun `图层框组合出左上角与右下角`() {
        val box = ImageLayerGeometry.layerBoxPx(
            contentLeftPx = 10f,
            rowTopPx = 200f,
            lineHeightPx = 20f,
            clientHeightPx = 500f,
            imageHeightPx = 120f,
            shapeShiftPx = 3,
            xpos = 5,
            ypos = 2,
            targetWidthPx = 300f,
            targetHeightPx = 120f,
        )
        assertEquals(18f, box[0], "10 + 3 + 5")
        assertEquals(-178f, box[1], "200 + 20 - 500 + (120-20) + 2")
        assertEquals(318f, box[2], "x + 宽")
        assertEquals(-58f, box[3], "y + 高")
    }

    // ---------------------------------------------------------------
    // IMG-A4 I3：精确式 —— GetLinePointY 展开 + ClientHeight 相消
    // ---------------------------------------------------------------

    /** `GetLinePointY` 的本地展开（`EmueraConsole.cs:1786-1795`）：底行行顶 − 行距×行高。 */
    @Test
    fun `GetLinePointY 精确式按本地定义逐项算`() {
        // ClientHeight=500, LineHeight=20：底行行顶 = 480；锚行在底行之下 0 行 → 480
        assertEquals(480f, ImageLayerGeometry.linePointYPx(500f, 20f, 0))
        // 上方第 3 行（linesBelowAnchor=3）：480 - 60 = 420
        assertEquals(420f, ImageLayerGeometry.linePointYPx(500f, 20f, 3))
    }

    /**
     * 精确式与四项式在「行高等高 + 行顶由底行反推」的形态下逐位同值（代数闭合的自证）：
     * 旧式 `layerTopPx(100, 20, 500, 200, 0) = -200`；同一几何下
     * `rowTop=100 = linePointY(500,20,19)` ⇒ 精确式 `200 - 20 - 19*20 = -200`。
     */
    @Test
    fun `精确式与四项式在等高行形态下逐位同值`() {
        val lineHeight = 20f
        val clientHeight = 500f
        val linesBelow = 19
        val rowTop = ImageLayerGeometry.linePointYPx(clientHeight, lineHeight, linesBelow) // = 100
        assertEquals(-200f, ImageLayerGeometry.layerTopPx(rowTop, lineHeight, clientHeight, 200f, 0))
        assertEquals(-200f, ImageLayerGeometry.layerTopPxExact(200f, lineHeight, linesBelow, 0))
        // ypos 平移两式同步
        assertEquals(-190f, ImageLayerGeometry.layerTopPx(rowTop, lineHeight, clientHeight, 200f, 10))
        assertEquals(-190f, ImageLayerGeometry.layerTopPxExact(200f, lineHeight, linesBelow, 10))
    }

    /** 锚行 = 最后可见行（linesBelowAnchor=0）：y = imageHeight - LineHeight + ypos（H 项全消）。 */
    @Test
    fun `精确式锚定底行时只剩图高与行高差`() {
        assertEquals(180f, ImageLayerGeometry.layerTopPxExact(200f, 20f, 0, 0), "200 - 20")
        assertEquals(190f, ImageLayerGeometry.layerTopPxExact(200f, 20f, 0, 10))
        // 图高 = 行高时归零（与该行 img 重合的形态）
        assertEquals(0f, ImageLayerGeometry.layerTopPxExact(20f, 20f, 0, 0))
    }

    /** 精确式整框：x 与四项式同源（layerLeftPx），y 走闭合式。 */
    @Test
    fun `精确式图层框组合出左上角与右下角`() {
        val box = ImageLayerGeometry.layerBoxPxExact(
            contentLeftPx = 10f,
            imageHeightPx = 120f,
            lineHeightPx = 20f,
            linesBelowAnchor = 2,
            shapeShiftPx = 3,
            xpos = 5,
            ypos = 2,
            targetWidthPx = 300f,
            targetHeightPx = 120f,
        )
        assertEquals(18f, box[0], "10 + 3 + 5")
        assertEquals(62f, box[1], "120 - 20 - 2*20 + 2")
        assertEquals(318f, box[2], "x + 宽")
        assertEquals(182f, box[3], "y + 高")
    }

    // ---------------------------------------------------------------
    // IMG-A4 I4：(num2 - lineNo) 的求差面（调用侧从视口最后可见行取 num2）
    // ---------------------------------------------------------------

    /**
     * `linesBelowAnchor` = `num2 - lineNo`（同一坐标系的两个显示行下标），负差夹到 0
     * （`EmueraConsole.cs:1786-1795` 的 `num2` 是最后一条可见逻辑行）。
     */
    @Test
    fun `锚行到最后可见行的行距按同坐标系求差`() {
        // 锚行就是最后可见行 ⇒ 0（精确式退化成 imageHeight - LineHeight + ypos）
        assertEquals(0, ImageLayerGeometry.linesBelowAnchor(9, 9))
        // 锚行在最后可见行之上 3 行
        assertEquals(3, ImageLayerGeometry.linesBelowAnchor(9, 6))
        // 锚行 = 窗口顶行（48 行高窗口：num2 - 47）
        assertEquals(47, ImageLayerGeometry.linesBelowAnchor(80, 33))
        // 锚行落到最后可见行之下（滚动看历史时锚行在视口下方）⇒ 夹到 0，不许出现负行距
        assertEquals(0, ImageLayerGeometry.linesBelowAnchor(9, 12))
        // 空视口：bottomRingIndex 的 -1 也走同一条夹取
        assertEquals(0, ImageLayerGeometry.linesBelowAnchor(-1, 3))
    }

    /**
     * 求差面接上精确式后与「行顶四项式 + 本地 `GetLinePointY`」逐位同值：
     * 同一几何（ClientHeight=500、LineHeight=20、num2=24）下
     * `rowTop = linePointY(500,20, num2 - lineNo)`，两式读数必须一致。
     */
    @Test
    fun `视口求差的精确式与行顶四项式逐位同值`() {
        val lineHeight = 20f
        val clientHeight = 500f
        val lastVisible = 24
        val anchor = 19
        val below = ImageLayerGeometry.linesBelowAnchor(lastVisible, anchor) // = 5
        val rowTop = ImageLayerGeometry.linePointYPx(clientHeight, lineHeight, below)
        assertEquals(380f, rowTop, "480 - 5*20")
        assertEquals(
            ImageLayerGeometry.layerTopPx(rowTop, lineHeight, clientHeight, 200f, -7),
            ImageLayerGeometry.layerTopPxExact(200f, lineHeight, below, -7),
        )
        // 交叉校验：精确式只剩「图高 - 行高 - 行距 + ypos」
        assertEquals(73f, ImageLayerGeometry.layerTopPxExact(200f, lineHeight, below, -7), "200 - 20 - 100 - 7")
    }

    // ---------------------------------------------------------------
    // 文字衬底：alpha = opacity% × 255 / 100（specs/isa.yaml:2456）
    // ---------------------------------------------------------------

    @Test
    fun `文字衬底 alpha 按百分比折算为 255 制`() {
        assertEquals(0xFF112233.toInt(), ImageLayerGeometry.textBackgroundArgb(0x112233, 100))
        assertEquals(0x7F112233, ImageLayerGeometry.textBackgroundArgb(0x112233, 50), "50 × 255 / 100 = 127")
        assertEquals(0x00112233, ImageLayerGeometry.textBackgroundArgb(0x112233, 0), "0% ⇒ alpha 0，颜色位保留")
        // 越界夹到 100（百分比域，specs/isa.yaml:2456）；负值夹到 0
        assertEquals(0xFF112233.toInt(), ImageLayerGeometry.textBackgroundArgb(0x112233, 255))
        assertEquals(0x00112233, ImageLayerGeometry.textBackgroundArgb(0x112233, -1))
    }

    @Test
    fun `文字衬底矩形原样返回四角`() {
        val rect = ImageLayerGeometry.textBackgroundRect(1f, 2f, 3f, 4f)
        assertEquals(1f, rect[0])
        assertEquals(2f, rect[1])
        assertEquals(3f, rect[2])
        assertEquals(4f, rect[3])
    }
}