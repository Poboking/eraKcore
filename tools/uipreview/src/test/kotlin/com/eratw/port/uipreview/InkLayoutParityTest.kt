package com.eratw.port.uipreview

import com.eratw.port.render.GlyphKey
import com.eratw.port.render.glyph.GlyphSource
import com.eratw.port.render.glyph.GlyphRaster
import com.eratw.port.render.glyph.StaticGlyphRaster
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 铁不变量的整链对拍（CONTRACT-UP2 验收①）：同一 mini 语料、同一 seed，两套
 * [PreviewRenderer] —— 一套默认 [AwtGlyphRaster]（真字形），一套经 `rasterOverride`
 * 注 [StaticGlyphRaster]（占位），**共享同一 TextMetrics 实例** —— 跑到同一终态后：
 *
 * - [com.eratw.port.render.displaylist.DisplayList]（`screen.list.render()`）逐项结构相等；
 * - 画布宽高 / PNG 帧尺寸相等；
 * - 两份 [com.eratw.port.render.glyph.GlyphCache] 同键条目的 advance / 位图宽高逐值相等
 *   （`servedBy` 允许不同：请求名在 Static 侧落默认跳、Awt 侧可直接画，advance 同源即平）；
 * - 位图**内容**必须不同 —— 那是本波的正题（占位 → 真墨）。
 */
class InkLayoutParityTest {

    @Test
    fun `换光栅器前后布局逐位不变 只有墨迹变真`(@TempDir dirA: Path, @TempDir dirB: Path) {
        val rendererA = boot(dirA, rasterOverride = null)
        val rendererB = boot(dirB, rasterOverride = StaticGlyphRaster(cellMetrics = rendererA.metrics, metrics = rendererA.textMetrics))
        try {
            assertEquals(
                rendererA.screen.list.render(),
                rendererB.screen.list.render(),
                "DisplayList 逐项相等（折行 / 列位置 / 命中区都不动）",
            )
            assertEquals(rendererA.composer.canvasWidthPx, rendererB.composer.canvasWidthPx)
            assertEquals(rendererA.composer.canvasHeightPx, rendererB.composer.canvasHeightPx)
            assertEquals(rendererA.currentFrame?.width, rendererB.currentFrame?.width)
            assertEquals(rendererA.currentFrame?.height, rendererB.currentFrame?.height)

            val keysA = rendererA.glyphCache.keysOldestFirst().toSet()
            val keysB = rendererB.glyphCache.keysOldestFirst().toSet()
            assertEquals(keysA, keysB, "两份缓存进的键集合相同")
            assertTrue(keysA.isNotEmpty(), "对拍语料必须真产出字形")
            var inkDiffCount = 0
            for (key in keysA) {
                val entryA = rendererA.glyphCache.get(key)
                val entryB = rendererB.glyphCache.get(key)
                assertEquals(entryA.advancePixels, entryB.advancePixels, "advance 同源：$key")
                assertEquals(entryA.advanceCells, entryB.advanceCells, "advanceCells 同源：$key")
                assertEquals(entryA.bitmapWidth, entryB.bitmapWidth, "位图宽同源：$key")
                assertEquals(entryA.bitmapHeight, entryB.bitmapHeight, "位图高同源：$key")
                if (!entryA.bitmap.contentEquals(entryB.bitmap)) inkDiffCount++
            }
            assertTrue(inkDiffCount > 0, "至少一条字形从占位色块换成了真墨")
            // 请求名直接命中 Awt 的字形，servedBy 与 Static 的默认跳不同是允许且预期的差异。
            assertTrue(
                keysA.any { rendererA.glyphCache.get(it).servedBy == GlyphSource.REQUESTED },
                "Awt 侧至少有一条字形由请求跳直出",
            )
        } finally {
            rendererA.session.close()
            rendererB.session.close()
        }
    }

    @Test
    fun `缺字码位两光栅器同走方框 兜底 advance 一致`(@TempDir dirA: Path, @TempDir dirB: Path) {
        val rendererA = boot(dirA, rasterOverride = null)
        val rendererB = boot(dirB, rasterOverride = StaticGlyphRaster(cellMetrics = rendererA.metrics, metrics = rendererA.textMetrics))
        try {
            // U+1F600（emoji）在随包 Saitamaar 子集覆盖面外（sidecar 5,269 行全在 BMP 常用区），
            // 两条回退链都应落 GlyphCache 第三跳方框，advance 都是网格折算（notdef 位图同构，
            // 由 GlyphCache.notdef 统一构造，不随光栅器变化）。
            val key = GlyphKey("", 0, 0x1F600, rendererA.metrics.fontPixelSize)
            val entryA = rendererA.glyphCache.get(key)
            val entryB = rendererB.glyphCache.get(key)
            assertEquals(GlyphSource.NOTDEF, entryA.servedBy)
            assertEquals(GlyphSource.NOTDEF, entryB.servedBy)
            assertEquals(entryA.advancePixels, entryB.advancePixels)
            assertEquals(entryA.bitmapWidth, entryB.bitmapWidth)
        } finally {
            rendererA.session.close()
            rendererB.session.close()
        }
    }

    /** 装配并同步跑完 AUTOQUIT 语料（无输入等待，QUIT 收尾），在本线程当一次泵消费者。 */
    private fun boot(tmp: Path, rasterOverride: GlyphRaster?): PreviewRenderer {
        val built = MiniTitle.build(tmp, MiniTitle.ERB_AUTOQUIT)
        val session = MiniTitle.assemble(built, columns = 110)
        val renderer = PreviewRenderer(
            session,
            columns = 110,
            screenW = 2400,
            screenH = 1080,
            rasterOverride = rasterOverride,
        )
        runSessionLoop(session, renderer)
        renderer.pumpOnce()
        return renderer
    }
}
