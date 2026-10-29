package com.eratw.port.render.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ScreenSurfacePlanner] 的判定闸（纯 JVM）。
 *
 * 这批用例钉的是 2026-10-26 真机黑屏的**反面**：判定可以少画行，但永远不能给出
 * 「这一帧不用把画面贴出去」的口子 —— 黑屏就是这么来的（旧实现把「跳过没变的行」
 * 当成了「屏上还留着上一帧像素」，而安卓每帧重录 RenderNode、画布从空白开始）。
 *
 * 原版基准：每次 paint 先 `graph.Clear(bgColor)`（`EmueraConsole.cs:1816`）再把视口内
 * 每一行全部重画（`:1852-1856`），像素从不作为状态载体。
 *
 * 读数口径先说清：[ScreenSurfacePlanner.full] / [ScreenSurfacePlanner.lastRasteredRows]
 * 是**本帧**结论；`fullFrames` / `incrementalFrames` / `rasteredRows` 是**累计**取证值。
 */
class ScreenSurfacePlannerTest {

    private val fp = 0x5A5A

    /** 三个可见行，签名互不相同。 */
    private fun sigs(a: Int = 11, b: Int = 22, c: Int = 33) = intArrayOf(a, b, c)

    private fun plan(
        p: ScreenSurfacePlanner,
        s: IntArray,
        fingerprint: Int = fp,
        graphic: Boolean = false,
        background: Boolean = false,
        forced: Boolean = false,
        minRow: Int = 100,
    ) = p.plan(minRow, s, s.size, fingerprint, graphic, background, forced)

    /**
     * 先喂两帧打底：第 1 帧必然整幅（surface 是脏的），第 2 帧内容没变 ⇒ 落到增量口径。
     * 之后各用例判的都是「已有正确 surface 内容」之上的第三帧起。
     */
    private fun warmed(): ScreenSurfacePlanner {
        val p = ScreenSurfacePlanner()
        plan(p, sigs())
        plan(p, sigs())
        assertFalse(p.full, "第 2 帧起才是增量口径的起点")
        assertEquals(1L, p.fullFrames, "只有首帧是整幅")
        assertEquals(3L, p.rasteredRows, "累计重画行数 = 首帧的 3 行")
        return p
    }

    @Test
    fun `首帧必然整幅重栅格化`() {
        val p = ScreenSurfacePlanner()
        plan(p, sigs())
        assertTrue(p.full, "surface 刚建时里面是脏数据")
        for (i in 0 until 3) assertTrue(p.needsRaster(i), "第 $i 行要重栅格化")
        assertEquals(3, p.rowCount)
        assertEquals(3, p.lastRasteredRows)
        assertEquals(1L, p.fullFrames)
    }

    @Test
    fun `内容不变的增量帧零行重栅格化`() {
        val p = warmed()
        plan(p, sigs())
        assertFalse(p.full)
        for (i in 0 until 3) assertFalse(p.needsRaster(i), "第 $i 行签名全等 ⇒ 不用重画")
        assertEquals(0, p.lastRasteredRows, "一行都不用重画；这帧的画面靠整幅贴图上来，不靠重画")
        assertEquals(3L, p.rasteredRows, "累计值只含首帧那 3 行")
        assertEquals(2L, p.incrementalFrames, "warm 的第二帧 加 本用例这一帧")
    }

    @Test
    fun `色付き地图那一tick只有动画格所在行要重画`() {
        val p = warmed()
        plan(p, sigs(b = 999))
        assertFalse(p.full)
        assertFalse(p.needsRaster(0)); assertTrue(p.needsRaster(1)); assertFalse(p.needsRaster(2))
        assertEquals(1, p.lastRasteredRows)
    }

    @Test
    fun `显式置脏必须被消费_这就是症状1的正解`() {
        // 症状 1 的路径：revision 未变、版面未变，但点击后 submitInput 无条件置脏。
        // 旧实现此刻算出「0 行要画」又不清屏 ⇒ 整屏黑。现在置脏 = 整幅重画同样的内容。
        val p = warmed()
        plan(p, sigs(), forced = true)
        assertTrue(p.full, "forceRedraw 必须参与判定（旧代码写了这个标志却从不读）")
        for (i in 0 until 3) assertTrue(p.needsRaster(i))
    }

    @Test
    fun `版面指纹变化走整幅`() {
        val p = warmed()
        plan(p, sigs(), fingerprint = 2)
        assertTrue(p.full, "滚动/行高/内容区/按下态任一变化 ⇒ 同一行换了位置，跳过会留错位残影")
    }

    @Test
    fun `背景层存在时每帧整幅`() {
        val p = warmed()
        plan(p, sigs(), background = true)
        assertTrue(p.full, "逐行重画会盖掉行下方的背景 ⇒ 保守整幅（与旧实现同口径）")
    }

    @Test
    fun `背景层撤掉后那一帧也必须整幅`() {
        val p = warmed()
        plan(p, sigs(), background = true)
        plan(p, sigs(), background = false)
        assertTrue(
            p.full,
            "撤背景若不整幅重画，留在保留面上的背景像素没人清 —— 原版每帧 Clear 整屏（EmueraConsole.cs:1816）不存在这个洞",
        )
    }

    @Test
    fun `含图形的帧恒为整幅`() {
        val p = warmed()
        plan(p, sigs(), graphic = true)
        assertTrue(p.full, "含图形段的行不能逐行 clip（标题横带实测过）")
        plan(p, sigs(), graphic = true)
        assertTrue(
            p.full,
            "图形在场期间每帧都是整幅（绘制路径无条件全画）；判定结论必须如实，否则取证读数低报",
        )
        plan(p, sigs(), graphic = false)
        assertTrue(p.full, "图形退场也要整幅：留在 surface 上的图形像素没人清")
    }

    @Test
    fun `可见窗口变化即整幅_签名基线只覆盖上一帧`() {
        val p = ScreenSurfacePlanner()
        plan(p, sigs(), minRow = 100)
        assertEquals(3, p.trackedRows(), "基线只覆盖上一帧的可见行")
        // 指纹故意不变：判定器不许假设调用方的指纹含 minRow —— 同一行号换了位置就得整幅。
        plan(p, sigs(1, 2, 3), minRow = 200)
        assertTrue(p.full, "窗口起点变了 ⇒ 第 i 行已经不是原来那一行")
        assertEquals(3, p.trackedRows(), "内存有界：不为历史行号留条目（旧实现按行号累加，永不回收）")
        plan(p, IntArray(9) { it + 1 }, minRow = 200)
        assertTrue(p.full, "行数变了同样是窗口变化")
        assertEquals(9, p.trackedRows())
    }

    @Test
    fun `surface丢失后下一帧整幅`() {
        val p = warmed()
        p.notifySurfaceLost()
        plan(p, sigs())
        assertTrue(p.full)
    }

    @Test
    fun `行数变少时缓冲尾部清零`() {
        val p = ScreenSurfacePlanner()
        plan(p, IntArray(10) { it + 1 })
        plan(p, sigs())
        assertEquals(3, p.rowCount)
        for (i in 3 until 10) assertFalse(p.needsRaster(i), "第 $i 位残留 true 会让调用方把不存在的行当活干")
    }

    @Test
    fun `needsRaster越界安全`() {
        val p = ScreenSurfacePlanner()
        plan(p, sigs())
        assertTrue(p.needsRaster(0))
        assertFalse(p.needsRaster(3))
        assertFalse(p.needsRaster(-1))
    }

    @Test
    fun `resetStatistics只清累计不清判定`() {
        val p = warmed()
        plan(p, sigs())
        assertEquals(2L, p.incrementalFrames, "warm 的第二帧 加 本用例这一帧")
        p.resetStatistics()
        assertEquals(0L, p.incrementalFrames)
        assertEquals(0L, p.rasteredRows)
        assertEquals(0L, p.fullFrames)
        assertFalse(p.full, "读数清零不许顺带把判定打成整幅")
        assertTrue(p.needsRaster(0).not(), "清零也不许改变上一帧的逐行结论")
    }

    @Test
    fun `判定层不提供跳过贴图的口子`() {
        // 黑屏的根因是「不贴图」。公开面上不允许出现任何 blit 开关，只允许 full / needsRaster 两个维度。
        val names = ScreenSurfacePlanner::class.java.methods.map { it.name }
        assertTrue(
            names.none { it.contains("blit", ignoreCase = true) },
            "出现 blit 开关就意味着又能少贴一帧：$names",
        )
        assertTrue("getFull" in names, "整幅结论必须在公开面上")
        assertTrue("needsRaster" in names, "逐行结论必须在公开面上")
    }
}
