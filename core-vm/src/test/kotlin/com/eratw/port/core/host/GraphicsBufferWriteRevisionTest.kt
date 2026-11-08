package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `GraphicsBuffer.pixelRevision`（写代数）：**原地重写**必须让渲染层的位图缓存失效。
 *
 * 病灶（真机 vivo V2199A 实测）：布娃娃预览页每次改选项都 `GDRAWSPRITE` 重画同一块合成画布
 * （`GCREATE` 一次、之后原地重写），而渲染层判「缓存还能不能用」只有一条凭据 ——
 * `SpriteBitmapSource.kt:928` 的 `cached.pixels === pixels`（数组引用同一性）。
 * 原地重写不换数组 ⇒ 引用不变 ⇒ 缓存永远命中第一张位图：屏幕上一直是首帧那半张图。
 * 数组引用只表达「换代」（新 `Surface`），表达不了「同代内被改写」，所以补这条写代数。
 *
 * 本测试钉死「哪些操作算一次写」：9 个图形写入口每笔至少 +1；纯读（`pixelAt` / `pixels` 读）
 * 一次都不 +1 —— 后者是渲染层每帧都要读的路径，读也 +1 会让缓存永不命中。
 */
class GraphicsBufferWriteRevisionTest {

    private fun buffer(w: Int = 4, h: Int = 4): GraphicsBuffer {
        val b = GraphicsBuffer(-1)
        b.create(w, h)
        return b
    }

    @Test
    fun `建缓冲不改写代数`() {
        val b = buffer()
        assertTrue(b.pixelRevision >= 0)
    }

    @Test
    fun `每笔写操作都推进写代数`() {
        val b = buffer()
        val before = b.pixelRevision
        b.setPixel(0, 0, 0xFF112233.toInt())
        assertTrue(b.pixelRevision > before, "setPixel 要推进")

        val afterSet = b.pixelRevision
        b.fillRect(0, 0, 4, 4, 0xFF445566.toInt())
        assertTrue(b.pixelRevision > afterSet, "fillRect 要推进")

        val afterFill = b.pixelRevision
        b.clear(0xFF000000.toInt())
        assertTrue(b.pixelRevision > afterFill, "clear 要推进")

        val afterClear = b.pixelRevision
        b.clearRect(1, 1, 2, 2, 0xFFFFFFFF.toInt())
        assertTrue(b.pixelRevision > afterClear, "clearRect 要推进")

        val afterClearRect = b.pixelRevision
        b.drawLine(0, 0, 3, 3, 0xFF00FF00.toInt())
        assertTrue(b.pixelRevision > afterClearRect, "drawLine 要推进")
    }

    @Test
    fun `合成一笔（blitFrom）推进写代数——布娃娃预览正是这条`() {
        val src = buffer(2, 2)
        src.setPixel(0, 0, 0xFFFF0000.toInt())
        val dst = buffer(8, 8)
        val before = dst.pixelRevision
        dst.blitFrom(src, 0, 0, 2, 2, 0, 0, 2, 2)
        assertTrue(dst.pixelRevision > before, "blitFrom 要推进（否则缓存永远停在首帧）")
        // 再来一笔：批合成是一层一个 blitFrom，每层都必须推进
        val mid = dst.pixelRevision
        dst.blitFrom(src, 2, 2, 2, 2, 0, 0, 2, 2)
        assertTrue(dst.pixelRevision > mid, "第二层也要推进")
    }

    @Test
    fun `纯读不推进写代数`() {
        val b = buffer()
        b.fillRect(0, 0, 4, 4, 0xFF102030.toInt())
        val before = b.pixelRevision
        b.pixelAt(1, 1)
        val px = b.pixels
        px.size
        b.width
        b.height
        assertTrue(b.pixelRevision == before, "读运算一次都不许推进（否则缓存永不命中）")
    }
}
