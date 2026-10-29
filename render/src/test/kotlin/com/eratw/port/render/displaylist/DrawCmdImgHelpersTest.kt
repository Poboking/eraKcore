package com.eratw.port.render.displaylist

import org.junit.jupiter.api.Assertions.assertArrayEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图形段三助手的判别力钉子（mapgfx 路）：负尺寸翻正、遮罩取色坐标、altText 重建。
 * 口径全部落在各项 KDoc 引的原版行号上，这里只钉边界与整除语义。
 */
class DrawCmdImgHelpersTest {

    @Test
    fun fixNegativeExtentFlipsStartAlongAxis() {
        // spec: ConsoleImagePart.cs:83-92 —— 尺寸取绝对值，起点沿该轴前移 |尺寸|
        // IntArray 的 assertEquals 是引用相等，必须 assertArrayEquals 逐元素比。
        assertArrayEquals(intArrayOf(0, 120), InlineGraphic.fixNegativeExtent(0, 120), "正尺寸原样")
        assertArrayEquals(intArrayOf(120, 120), InlineGraphic.fixNegativeExtent(0, -120), "X 轴起点 0 → X=|W|")
        assertArrayEquals(intArrayOf(40, 60), InlineGraphic.fixNegativeExtent(-20, -60), "Y 轴 top-20 → Y=top-20+|H|")
        assertArrayEquals(intArrayOf(5, 0), InlineGraphic.fixNegativeExtent(5, 0), "0 走缺省分支，不翻正")
    }

    @Test
    fun maskSamplePointEdgesAndScaling() {
        // spec: ConsoleButtonString.cs:142-146 —— 左/上沿严格 >0，右/下沿严格 <宽/高
        assertNull(InlineGraphic.maskSamplePoint(0, 5, 100, 50, 64, 32), "第 0 列不算命中")
        assertNull(InlineGraphic.maskSamplePoint(5, 0, 100, 50, 64, 32), "第 0 行不算命中")
        assertNull(InlineGraphic.maskSamplePoint(100, 5, 100, 50, 64, 32), "右沿外")
        assertNull(InlineGraphic.maskSamplePoint(-1, 5, 100, 50, 64, 32), "左沿外")
        assertNull(InlineGraphic.maskSamplePoint(5, 5, 0, 50, 64, 32), "绘制框 0 ⇒ 永不命中")
        // 整除截断：ConsoleImagePart.cs:152-153 的 num * mask.DestBaseSize / destRect.Width
        assertEquals(Pair(32, 16), InlineGraphic.maskSamplePoint(50, 25, 100, 50, 64, 32))
        assertEquals(Pair(0, 0), InlineGraphic.maskSamplePoint(1, 1, 100, 50, 64, 32), "小坐标整除截断为 0")
    }

    @Test
    fun imgAltTextRebuildsTagPerOriginalGuards() {
        // spec: ConsoleImagePart.cs:45-59 + Utils.cs:364-371（AddTagMixedNumArg 的
        // `num != null && num.num != 0` 守卫）+ BuilderString :87-95（百分比 = 整除）
        assertEquals(
            "<img src='MAP'>",
            InlineGraphic.imgAltText("MAP", null, null, null, null, null, 18),
            "最小形态：只有 src",
        )
        assertEquals(
            "<img src='MAP' srcb='MAP_B'>",
            InlineGraphic.imgAltText("MAP", "MAP_B", "", null, null, null, 18),
            "srcb 非 null 才写；srcm 空串不写",
        )
        assertEquals(
            "<img src='MAP' srcm='MAP_M'>",
            InlineGraphic.imgAltText("MAP", null, "MAP_M", null, null, null, 18),
            "srcm 非空才写",
        )
        assertEquals(
            "<img src='MAP' height='36' width='36px' ypos='-9'>",
            InlineGraphic.imgAltText("MAP", null, null,
                height = Pair(200, false), width = Pair(36, true),
                ypos = Pair(-50, false), fontSizePx = 18),
            "百分比 = num*FontSize/100 整除、不带 px（200*18/100=36；-50*18/100=-9）；" +
                "isPx=true 带 px 后缀（36 → '36px'）",
        )
        assertEquals(
            "<img src='MAP'>",
            InlineGraphic.imgAltText("MAP", null, null,
                height = Pair(0, true), width = Pair(0, false), ypos = null, fontSizePx = 18),
            "num==0 整个属性不写（AddTagMixedNumArg 守卫）",
        )
        assertTrue(InlineGraphic.imgAltText("", null, null, null, null, null, 18).startsWith("<img src=''>"),
            "空资源名也照写（ResourceName = resName ?? \"\"，ConsoleImagePart.cs:42,46）")
    }
}
