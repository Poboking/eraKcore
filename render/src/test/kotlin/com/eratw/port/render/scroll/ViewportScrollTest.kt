package com.eratw.port.render.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M11-B DEV-01 的两条滚动判据：
 *
 * 1. **tap 贴底半边**：未贴底时点击回贴底、已在底部无事可做 ——
 *    `MainWindow.cs:799-805` 的 `flag = Value != Maximum`（`:799`）与
 *    `Value = Maximum`（`:803`）；命中目标在 `:800` 先取，所以贴底发生在命中之后，
 *    纯逻辑就是 [Viewport.snapToBottomForTap] 的返回值口径（同构于按键半边
 *    `InputSession.snapToBottomForKeyDown`，`MainWindow.cs:1402-1406`）。
 * 2. **滚到顶不空屏**：clamp 上界 = `max(0, retainedLines - heightLines)`，
 *    滚到顶显示保留区最早的一屏（原版 `Value == 0` ⇒ `num2 == -1` ⇒ 整屏不画一行，
 *    `EmueraConsole.cs:1806-1812`、`:1852`，属观感缺陷不复刻，取舍见 Viewport 类文档）；
 *    且上界收紧后「上翻锁定 = 偏移守恒」（`EmueraConsole.cs:2552-2556`）不回退。
 *
 * 夹具复用 [appendRun]（标记 `L{n}`，绝对行号即下标）。
 */
class ViewportScrollTest {

    /** 按键/点击半边共用的口径：动了才返回 true，重复调用幂等。 */
    @Test
    fun `tap 贴底半边只在未贴底时动`() {
        val vp = Viewport(heightLines = 10)
        vp.sync(50)
        assertFalse(vp.snapToBottomForTap(), "已在底部 ⇒ 无事可做（MainWindow.cs:799 的 flag 不成立）")
        assertTrue(vp.atBottom)

        vp.scrollUp(7)
        assertEquals(7, vp.offsetFromEnd)
        assertFalse(vp.atBottom)
        assertTrue(vp.snapToBottomForTap(), "未贴底时点击 ⇒ Value = Maximum（MainWindow.cs:803）")
        assertEquals(0, vp.offsetFromEnd)
        assertTrue(vp.atBottom)
        assertFalse(vp.snapToBottomForTap(), "第二次已在底部 ⇒ 幂等")
    }

    /** 贴底之后新行自动跟随（点击贴底不能破坏贴底跟随）。 */
    @Test
    fun `tap 贴底后新行继续自动跟随`() {
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 30)
        vp.scrollUp(4)
        assertTrue(vp.snapToBottomForTap())
        grow(buf, vp, 30, 10)
        assertTrue(vp.atBottom, "贴底时追加新行仍自动跟随（偏移守恒的 offset == 0 特例）")
        assertEquals("L39", marker(vp.rows(buf).last()))
    }

    /** 上翻锁定与 tap 贴底的组合：守恒的偏移被一次点击清零，且之后可再上翻。 */
    @Test
    fun `上翻锁定期间点击回贴底且可再上翻`() {
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 40)
        vp.scrollUp(3)
        grow(buf, vp, 40, 10)
        assertEquals(3, vp.offsetFromEnd, "追加新行偏移守恒（EmueraConsole.cs:2552-2556）")
        assertTrue(vp.snapToBottomForTap())
        assertEquals(0, vp.offsetFromEnd)
        vp.scrollUp(2)
        assertEquals(2, vp.offsetFromEnd, "贴底后仍可再上翻，滚动状态没有被点击钉死")
    }

    /** 滚到顶的极限偏移：显示最早一屏；追加新行后偏移守恒且仍不越界。 */
    @Test
    fun `滚到顶显示最早一屏且追加后仍守恒`() {
        val buf = LogBuffer(50)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 30)
        vp.scrollUp(99)
        assertEquals(20, vp.offsetFromEnd, "上界 = retainedLines - heightLines = 30 - 10")
        assertEquals("L0", marker(vp.rows(buf).first()), "滚到顶 = 保留区首行在屏上")
        assertEquals(10, vp.rows(buf).size, "滚到顶恰好显示 heightLines 行而不是空屏")
        grow(buf, vp, 30, 10)
        assertEquals(20, vp.offsetFromEnd, "上翻锁定：偏移守恒（EmueraConsole.cs:2552-2556）")
        assertEquals("L10", marker(vp.rows(buf).first()), "底部与尾部距离恒定 ⇒ 窗口整体前移")
        assertFalse(vp.atBottom)
    }

    /** 内容不足一屏时没有上翻余量（retainedLines 小于等于 heightLines ⇒ maxOffset == 0）。 */
    @Test
    fun `内容不足一屏时恒贴底`() {
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 5)
        assertEquals(0, vp.maxOffset)
        vp.onWheel(120)
        vp.pageUp(3)
        assertEquals(0, vp.offsetFromEnd, "滚轮与翻页都推不动")
        assertTrue(vp.atBottom)
        assertEquals(5, vp.rows(buf).size)
    }
}

/** [ViewportScrollTest] 用的局部 grow（与 ViewportTest 同式，独立声明避免跨文件耦合）。 */
private fun grow(buf: LogBuffer, vp: Viewport, from: Int, n: Int) {
    buf.appendRun(from, n)
    vp.sync(buf)
}
