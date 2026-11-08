package com.eratw.port.render.scroll

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「补丁页滚动错位」修复的定向单测（2026-11-04）。
 *
 * 被钉的语义：[Viewport.sync] 的 [LogBuffer] 版本在**逻辑行**净减少超过
 * [Viewport.LOGICAL_SHRINK_SNAP_THRESHOLD] 时把视口弹回底部 —— PC `num < 0` 支
 * `Value = count` 的直译（spec: `EmueraConsole.cs:2557-2564`）；折行口径的 Int 版
 * 保持只夹取（2026-10-29 取舍，救 QoL 地图页）。依据与取舍见 Viewport 类文档
 * 「缩短不再拽回」的 2026-11-04 追记。
 *
 * 驱动序列按 4.981 语料「更新日志」页的原序（`ERB/fromEN/NEW_UPDATE/PatchNotes/
 * PatchNotes.ERB`，入口 `UpdateMenus.ERB:308`）：
 * `:16-20` 100 行空行垫底 + 记基线 → 画 20 条记录与按钮 → INPUT；
 * 点条目 `:117` `CLEARLINE 1` 后画详情（`:119-163` 的内层循环）；
 * 点「返回」回到外层 `:49` `CLEARLINE LINECOUNT - nLineCount` 清掉菜单 + 详情再重画。
 * 每行用单排逻辑行（isLogicalLine = isLineEnd = true），与该页的 PRINTL 输出粒度一致。
 */
class ViewportLogicalShrinkTest {

    private fun row(i: Int): DisplayRow = DisplayRow(
        runs = listOf(TextRun("L$i", fore = 0xC0C0C0, back = 0x000000, styleMask = 0, fontName = "黑体")),
        cells = ("L$i").length,
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun LogBuffer.appendRows(n: Int, from: Int = 0) {
        for (i in 0 until n) append(row(from + i))
    }

    /** 逐逻辑行回撤（LogBuffer.removeLast 是逻辑行粒度，见其 KDoc）。 */
    private fun LogBuffer.removeLogical(n: Int) {
        repeat(n) { check(removeLast()) }
    }

    /** 补丁页打开瞬间的缓冲：基线 10 行 + 100 行空行垫底 + 30 行菜单。 */
    private fun patchPageBuffer(): LogBuffer {
        val b = LogBuffer(Grid.MAX_LOG_DEFAULT)
        b.appendRows(10)
        b.appendRows(100, 10)
        b.appendRows(30, 110)
        return b
    }

    /** 主修复：读完详情页上翻后「返回」，菜单重画完成时视口必须回到底部。 */
    @Test
    fun `补丁页从详情返回_逻辑行净收缩_弹回底部`() {
        val vp = Viewport(40)
        val buf = patchPageBuffer()
        vp.sync(buf)
        assertTrue(vp.atBottom)

        // 玩家上翻看过列表顶部（真机上常态：20 条记录 + 窄屏折行超出窗口）。
        vp.scrollUp(15)
        assertEquals(15, vp.offsetFromEnd)

        // 点条目：CLEARLINE 1 + 详情 40 行（净增长，锚点守恒）。
        buf.appendRows(40, 140)
        vp.sync(buf)
        assertEquals(15, vp.offsetFromEnd)

        // 点「返回」：CLEARLINE 清掉菜单(30) + 详情(40)，再重画菜单 30 行。
        buf.removeLogical(70)
        buf.appendRows(30, 180)
        vp.sync(buf)

        assertEquals(0, vp.offsetFromEnd)
        assertTrue(vp.atBottom)
        // 弹底后看到的就是重画菜单的尾部（垫底的 100 行空行不再挡路）。
        assertEquals("L209", buf.rowsAt(vp.visibleRange(buf).last, 1).single().runs.single().text)
    }

    /** 地图页保护：同页重印逻辑行净值 0（清多少补多少），锚点不动；±1~2 行归属抖动同样不动。 */
    @Test
    fun `地图页同页重印_逻辑行净值零或微抖_锚点不拽回`() {
        val vp = Viewport(40)
        val buf = LogBuffer(Grid.MAX_LOG_DEFAULT)
        buf.appendRows(140)
        vp.sync(buf)
        vp.scrollUp(10)
        assertEquals(10, vp.offsetFromEnd)

        // qol_MAP.ERB:34-44 的整屏 CLEARLINE + 等量重印：净值 0。
        buf.removeLogical(30)
        buf.appendRows(30, 140)
        vp.sync(buf)
        assertEquals(10, vp.offsetFromEnd)

        // 末行归属抖动：多折 1 行（+1）与少 1 行、少 2 行，均在阈值内。
        buf.appendRows(1, 170)
        vp.sync(buf)
        assertEquals(10, vp.offsetFromEnd)
        buf.removeLogical(2)
        vp.sync(buf)
        assertEquals(10, vp.offsetFromEnd)
        buf.removeLogical(1)
        vp.sync(buf)
        assertEquals(10, vp.offsetFromEnd)
        assertFalse(vp.atBottom)
    }

    /** 阈值边界：单帧净减 3 行是抖动量级、不弹底；净减 4 行按 PC 语义弹底。 */
    @Test
    fun `抖动阈值_净减3行不弹_净减4行弹`() {
        val vp = Viewport(40)
        val buf = LogBuffer(Grid.MAX_LOG_DEFAULT)
        buf.appendRows(140)
        vp.sync(buf)

        vp.scrollUp(5)
        buf.removeLogical(3)
        vp.sync(buf)
        assertEquals(5, vp.offsetFromEnd, "净减 3 行仍在抖动带内，不得拽回")

        vp.scrollUp(5)
        buf.removeLogical(4)
        vp.sync(buf)
        assertEquals(0, vp.offsetFromEnd, "净减 4 行超过阈值，按 PC num<0 支弹回底部")
        assertTrue(vp.atBottom)
    }

    /** Int 版（uipreview/直驱路）维持 2026-10-29 口径：缩短只夹取、绝不弹底。 */
    @Test
    fun `Int版sync_缩短只夹取不弹底`() {
        val vp = Viewport(40)
        vp.sync(100)
        vp.scrollUp(30)
        vp.sync(80)
        assertEquals(30, vp.offsetFromEnd, "80 - 40 = 40 的余量装得下，锚点保持")
        vp.sync(30)
        assertEquals(0, vp.offsetFromEnd, "剩的行不够一屏时 maxOffset = 0，夹到唯一合法的贴底态")
    }

    /** 清屏（ClearDisplay）后弹底判据的基线一并作废，新会话首帧不当作收缩。 */
    @Test
    fun `onCleared后重建_新基线_不误判`() {
        val vp = Viewport(40)
        val buf = patchPageBuffer()
        vp.sync(buf)
        vp.scrollUp(20)

        buf.clear()
        vp.onCleared()
        buf.appendRows(5)
        vp.sync(buf)

        assertEquals(5, vp.retainedLines)
        assertTrue(vp.atBottom)
    }
}
