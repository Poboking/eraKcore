package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PIXREG：`GDRAWSPRITE` 合成大拼图时走**区域解码**，不整张落地。
 *
 * 病灶（真机 logcat 实测）：
 * ```
 * I eratw-image: 解码像素预检不足 resources/汎用モブ子dairi版/汎用モブ子dairi版.webp 6964x6191
 *    —— 按缺图降级，要 172456496 B / 上限 536870912 B / 已用 242144048 B / 像素预算 134217728 B
 * ```
 * 那张拼图 6962x6189、整张 ARGB 164.4 MiB > 像素预算 128 MiB ⇒ 整张解码被拒 ⇒ 源像素空 ⇒
 * `GDRAWSPRITE` 合成的画布全透明 ⇒ 布娃娃预览三张 `<img>` 全空。
 * 合成单层只用 sprite 子矩形（最大 850x1280 ≈ 4.3 MiB），所以按区域解就够。
 *
 * 本测试钉四件事：
 * 1. 有区域源时**不碰**整张 `pixels`（假宿主的整张解码计数必须是 0）；
 * 2. 区域解码请求的矩形 = 这一笔真正会采样的源矩形（不是整张）；
 * 3. 合成结果与「整张解码」逐像素一致（缩放、裁剪、矩阵同一口径）；
 * 4. 没有区域源（宿主不支持）时退回整张解码，行为与改前一致。
 */
class GraphicsBufferRegionBlitTest {

    /** 假拼图：宽 x 高，像素按 (x,y) 编一个可预期的颜色。 */
    private class FakeSheet(val w: Int, val h: Int) {
        var fullDecodes = 0
        var regionDecodes = 0
        val regions = ArrayList<IntArray>()

        fun pixelAt(x: Int, y: Int): Int = 0xFF000000.toInt() or (x shl 8) or (y and 0xFF)

        fun fullPixels(): IntArray {
            fullDecodes++
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = pixelAt(x, y)
            return out
        }

        fun region(x: Int, y: Int, rw: Int, rh: Int): IntArray {
            regionDecodes++
            regions += intArrayOf(x, y, rw, rh)
            val out = IntArray(rw * rh)
            for (j in 0 until rh) for (i in 0 until rw) out[j * rw + i] = pixelAt(x + i, y + j)
            return out
        }
    }

    private fun sheetBuffer(sheet: FakeSheet, withRegion: Boolean): GraphicsBuffer {
        val buffer = GraphicsBuffer(-1)
        val ok = buffer.createDeferredFromFile(
            sheet.w, sheet.h, "resources/fake/sheet.webp",
            load = { sheet.fullPixels() },
            region = if (withRegion) {
                { x, y, w, h -> sheet.region(x, y, w, h) }
            } else {
                null
            },
        )
        assertTrue(ok, "延迟缓冲要建得起来")
        return buffer
    }

    /** 合成一块 sprite 子矩形到目标缓冲，返回目标缓冲（已落地像素）。 */
    private fun compose(
        sheet: FakeSheet,
        withRegion: Boolean,
        sx: Int, sy: Int, sw: Int, sh: Int,
    ): GraphicsBuffer {
        val src = sheetBuffer(sheet, withRegion)
        val dest = GraphicsBuffer(-2)
        dest.create(64, 64)
        dest.blitFrom(src, 0, 0, sw, sh, sx, sy, sw, sh)
        return dest
    }

    @Test
    fun `有区域源时只解子矩形且不整张落地`() {
        val sheet = FakeSheet(200, 120)
        val dest = compose(sheet, withRegion = true, sx = 40, sy = 10, sw = 20, sh = 15)

        assertEquals(0, sheet.fullDecodes, "整张解码一次都不许发生（那正是被像素预算拒的那笔）")
        assertEquals(1, sheet.regionDecodes, "只解这一块")
        // 请求的矩形 = 采样会覆盖的源矩形（这里 dest 尺寸 = 源尺寸，正好是 (40,10,20,15)）
        assertTrue(sheet.regions.single().contentEquals(intArrayOf(40, 10, 20, 15)), "区域请求=${sheet.regions.single().toList()}")
        // 目标像素 = 源图对应坐标的颜色
        val pixels = dest.pixels
        assertEquals(sheet.pixelAt(40, 10), pixels[0])
        assertEquals(sheet.pixelAt(59, 24), pixels[14 * 64 + 19])
    }

    @Test
    fun `区域合成与整张解码逐像素一致`() {
        val sheet = FakeSheet(200, 120)
        val byRegion = compose(sheet, withRegion = true, sx = 40, sy = 10, sw = 20, sh = 15).pixels.copyOf()
        val sheet2 = FakeSheet(200, 120)
        val byFull = compose(sheet2, withRegion = false, sx = 40, sy = 10, sw = 20, sh = 15).pixels.copyOf()

        assertTrue(byRegion.contentEquals(byFull), "区域路与整张路必须逐像素一致")
        assertEquals(0, sheet.fullDecodes)
        assertEquals(1, sheet2.fullDecodes, "没有区域源时退回整张（改前口径）")
        assertEquals(0, sheet2.regionDecodes)
    }

    @Test
    fun `缩放采样时区域覆盖被采样的全部源像素`() {
        val sheet = FakeSheet(200, 120)
        val src = sheetBuffer(sheet, withRegion = true)
        val dest = GraphicsBuffer(-2)
        dest.create(10, 10)
        // 源矩形 (100,20)-(139,39) 缩到 10x10：采样点会覆盖到边缘，区域必须包含 xMax/yMax
        dest.blitFrom(src, 0, 0, 10, 10, 100, 20, 40, 20)
        assertEquals(0, sheet.fullDecodes)
        val r = sheet.regions.single()
        assertEquals(100, r[0])
        assertEquals(20, r[1])
        // 区域宽高 = 会被采样的源范围（可能略小于 sw/sh，但必须覆盖两端）
        assertTrue(r[2] in 1..40 && r[3] in 1..20, "区域=${r.toList()}")
        assertEquals(sheet.pixelAt(100, 20), dest.pixels[0])
    }

    @Test
    fun `区域源失败时退回整张解码`() {
        val sheet = FakeSheet(80, 40)
        val buffer = GraphicsBuffer(-1)
        buffer.createDeferredFromFile(
            sheet.w, sheet.h, "resources/fake/sheet.webp",
            load = { sheet.fullPixels() },
            region = { _, _, _, _ -> null }, // 宿主解不出来（越界/坏文件）
        )
        val dest = GraphicsBuffer(-2)
        dest.create(16, 16)
        dest.blitFrom(buffer, 0, 0, 16, 16, 8, 4, 16, 16)
        assertEquals(1, sheet.fullDecodes, "区域失败要退回整张路径")
        assertEquals(sheet.pixelAt(8, 4), dest.pixels[0])
    }

    @Test
    fun `没有区域源的普通缓冲行为不变`() {
        val src = GraphicsBuffer(-1)
        src.create(8, 8)
        for (i in 0 until 64) src.pixels[i] = 0xFF102030.toInt()
        val dest = GraphicsBuffer(-2)
        dest.create(8, 8)
        dest.blitFrom(src, 0, 0, 8, 8, 0, 0, 8, 8)
        assertEquals(0xFF102030.toInt(), dest.pixels[0])
        assertNull(src.regionPixels(0, 0, 8, 8), "已落地像素的缓冲不需要区域源")
    }
}
