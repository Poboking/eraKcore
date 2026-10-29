package com.eratw.port.render.sprite

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Raster] 软件合成层单测。
 *
 * 像素级公式（source-over 整数近似）是重设计口径（ADR-001，原版交 GDI+ 无位式可抄），
 * 这里断言的是**实现自身必须满足的不变量**（透明无操作、全透明覆盖、alpha=128 的
 * 确定性混合值）与**裁剪语义**（越界静默丢弃，对应原版 Graphics 自带裁剪、
 * 基准 `pc-golden-root.txt:652` `GDRAWG=1` 允许贴出界不报错的调用面）。
 */
class RasterTest {

    private val opaqueRed = 0xFFFF0000.toInt()
    private val opaqueBlue = 0xFF0000FF.toInt()
    private val transparent = 0x00123456

    @Test
    fun `blend 不透明覆盖 透明无操作 越界静默`() {
        val r = Raster(2, 2)
        r.blend(0, 0, opaqueRed)
        assertEquals(opaqueRed, r.pixelAt(0, 0))
        r.blend(0, 0, transparent) // A=0 不改目标
        assertEquals(opaqueRed, r.pixelAt(0, 0))
        r.blend(-1, 5, opaqueBlue) // 越界不抛
        assertEquals(opaqueRed, r.pixelAt(0, 0))
    }

    @Test
    fun `blend 半透明按整数 source-over 计算`() {
        val r = Raster(1, 1)
        r.blend(0, 0, opaqueBlue)            // dst = FF0000FF
        r.blend(0, 0, 0x80808080.toInt())     // src a=128 gray
        // r = (128*128 + 0*127)/255 = 64；g 同；b = (128*128 + 255*127)/255 = 191；
        // oa = 128 + 255*127/255 = 255
        assertEquals(0xFF4040BF.toInt(), r.pixelAt(0, 0))
    }

    @Test
    fun `blit 源区与目标区双向裁剪`() {
        val src = Raster(4, 4).apply { fillRect(0, 0, 4, 4, opaqueRed) }
        val dst = Raster(6, 6)
        // 负偏移：只落 (0,0)..(1,1) 四个像素
        dst.blit(-2, -2, src)
        assertEquals(opaqueRed, dst.pixelAt(1, 1))
        assertEquals(0, dst.pixelAt(2, 2))
        // 超右界：src 3x3 从 (4,4) 起 → 目标裁到 (4..5, 4..5) 共 2x2
        val d2 = Raster(6, 6)
        d2.blit(4, 4, src, 0, 0, 3, 3)
        assertEquals(opaqueRed, d2.pixelAt(5, 5))
        // 完全在界外：无任何像素变化
        val d3 = Raster(2, 2)
        d3.blit(10, 10, src)
        d3.blit(-4, -4, src)
        assertEquals(0, d3.pixelAt(0, 0))
        assertEquals(0, d3.pixelAt(1, 1))
        // 源区越界裁剪：源矩形 (-1,-1) 起 4x4 与位图 [0,4) 求交，可见部分为源 [0,3)（3x3）。
        // 落位映射按原版 GDI+ `DrawImage(bitmap, destRect, srcRect, Pixel)`
        // （spec: GraphicsImage.cs:306-315、SpriteAnime.cs:144、ASpriteSingle.cs:74）
        // 即 目标位置 = 目标原点 + (源像素坐标 - 源矩形原点)，源矩形原点仍是 -1；
        // 原版从不因裁剪搬移 srcRect 原点（AppContents.cs:313-325 只做相交测试后整块丢弃，
        // SpriteAnime.cs:24-35 的 `Normalize` 只裁目标矩形）。故可见 3x3 落目标 (1..3, 1..3)，
        // 目标第 0 行/列对应源坐标 -1 无数据，保持透明。
        val d4 = Raster(4, 4)
        d4.blit(0, 0, src, -1, -1, 4, 4)
        for (y in 1..3) {
            for (x in 1..3) assertEquals(opaqueRed, d4.pixelAt(x, y), "($x,$y) 应被贴入")
        }
        assertEquals(0, d4.pixelAt(0, 0), "源矩形原点外的目标列没有源数据")
        assertEquals(0, d4.pixelAt(0, 1))
        assertEquals(0, d4.pixelAt(1, 0))
    }

    @Test
    fun `blit 自贴用快照 不受覆写顺序污染`() {
        val r = Raster(2, 1)
        r.blend(0, 0, opaqueRed)
        // 把左列平移到右列，快照语义：右列得到红（若边读边写自污染则顺序不同结果不同）
        r.blit(1, 0, r, 0, 0, 1, 1)
        assertEquals(opaqueRed, r.pixelAt(1, 0))
        assertEquals(opaqueRed, r.pixelAt(0, 0))
    }

    @Test
    fun `fillRect 裁剪区域且宽高非正时无操作`() {
        val r = Raster(3, 3)
        r.fillRect(1, 1, 5, 5, opaqueBlue)
        assertEquals(opaqueBlue, r.pixelAt(2, 2))
        assertEquals(0, r.pixelAt(0, 0))
        r.fillRect(0, 0, 0, 3, opaqueRed)
        r.fillRect(0, 0, -1, -1, opaqueRed)
        assertEquals(0, r.pixelAt(0, 0))
        r.fillRect(-1, -1, 2, 2, transparent) // 半透明路径 + 左上越界：A=0 不改
        assertEquals(0, r.pixelAt(0, 0))
    }

    @Test
    fun `fillRect 非不透明逐点混合`() {
        val r = Raster(1, 2)
        r.fillRect(0, 0, 1, 2, 0x80808080.toInt()) // A=128 over 全 0 底
        // dst 透明黑：a = 128 + 0 = 128，rgb = (128*128+0)/255 = 64
        val p = r.pixelAt(0, 0)
        assertEquals(0x80404040.toInt(), p)
        assertEquals(0x80404040.toInt(), r.pixelAt(0, 1))
    }

    @Test
    fun `drawLine 端点闭区间且斜线确定`() {
        val r = Raster(5, 5)
        r.drawLine(0, 0, 4, 0, opaqueRed)
        for (x in 0..4) assertEquals(opaqueRed, r.pixelAt(x, 0))
        val d = Raster(3, 3)
        d.drawLine(0, 0, 2, 2, opaqueRed) // Bresenham 对角线
        assertEquals(opaqueRed, d.pixelAt(0, 0))
        assertEquals(opaqueRed, d.pixelAt(1, 1))
        assertEquals(opaqueRed, d.pixelAt(2, 2))
        val rev = Raster(5, 1)
        rev.drawLine(4, 0, 0, 0, opaqueBlue) // 反向
        for (x in 0..4) assertEquals(opaqueBlue, rev.pixelAt(x, 0))
        // 全越界直线：不抛、无像素
        val oob = Raster(1, 1)
        oob.drawLine(10, 10, 20, 20, opaqueRed)
        assertEquals(0, oob.pixelAt(0, 0))
    }

    @Test
    fun `零尺寸光栅一切绘制无操作且合法`() {
        val r = Raster(0, 0)
        r.fillRect(0, 0, 4, 4, opaqueRed)
        r.drawLine(0, 0, 3, 3, opaqueRed)
        r.blit(0, 0, Raster(2, 2))
        r.blend(0, 0, opaqueRed)
        assertEquals(0, r.pixels.size)
    }
}
