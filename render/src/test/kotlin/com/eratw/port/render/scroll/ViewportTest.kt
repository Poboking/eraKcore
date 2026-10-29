package com.eratw.port.render.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Viewport] 的贴底跟随 / 上翻锁定判据。
 * 口径全部来自 `EmueraConsole.cs:2543-2567`（滚动条更新）、`EmueraConsole.cs:1786-1812`（视口起止）、
 * `MainWindow.cs:799-805,1262-1287,1379-1406`（输入侧）。
 *
 * ## 夹具口径（LOG-FLOOR 之后的口径）
 * [LogBuffer] 的裸构造接受任意（正）容量 —— 500 下限只属配置解析层：原版夹的是
 * `Config.MaxLog` 这个配置项（spec: `Config.cs:451-455`），默认值 5000 来自 `ConfigItem` 注册
 * （spec: `ConfigData.cs:62`），而 `displayLineList` 自己从不设长度下限
 * （淘汰判据与滚动条 `Maximum` 都只是读那个外来的数，spec: `EmueraConsole.cs:2988`、`EmueraConsole.cs:2554`）。
 * 因此要看「缓冲区写满后淘汰」不必再把夹具铺到 500 行：容量取 20、追加 40 行就够。
 * 上一波因下限塞进裸构造而统一抬到 `LogBuffer(500)` 的夹具，这里按新口径重标规模
 * （淘汰类用 20，只涨行不淘汰的类用 50 或 60 保证填不满），**断言语义一条没减**，
 * 只重算与容量相关的字面量。
 */
class ViewportTest {

    /** 追加一段行并让视口跟着缓冲走一步（等价于原版的 `RefreshStrings` ⇒ `verticalScrollBarUpdate`）。 */
    private fun grow(buf: LogBuffer, vp: Viewport, from: Int, n: Int) {
        buf.appendRun(from, n)
        vp.sync(buf)
    }

    @Test
    fun `初态贴底且新行自动跟随`() {
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 10)
        assertTrue(vp.atBottom)
        assertEquals(0, vp.retainedLines)
        assertEquals(0, vp.offsetFromEnd)
        grow(buf, vp, 0, 3)
        assertTrue(vp.atBottom, "贴底时新行自动跟随（Value += num 后 Value == Maximum）")
        assertEquals(buf.size, vp.retainedLines)
        assertEquals(2, vp.bottomRingIndex())
        val visible = vp.rows(buf)
        assertEquals(3, visible.size)
        assertEquals("L0", marker(visible.first()))
        assertEquals("L2", marker(visible.last()))
    }

    @Test
    fun `跨淘汰仍然贴底跟随`() {
        // 缓冲区写满后每次追加同时淘汰一行 ⇒ count 不变 ⇒ verticalScrollBarUpdate 早退
        //（EmueraConsole.cs:2546-2550）⇒ 偏移守恒，底部仍是最新行。
        // 夹具：容量 20（裸构造按传入值，下限已归配置层）⇒ 追加到 80 行即 firstIndex 60。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 4)
        grow(buf, vp, 0, 20)
        assertEquals(20, vp.retainedLines)
        grow(buf, vp, 20, 60) // 一共 80 行，firstIndex 60
        assertEquals(60L, buf.firstIndex)
        assertEquals(20, vp.retainedLines)
        assertTrue(vp.atBottom)
        assertEquals(0, vp.offsetFromEnd)
        val visible = vp.rows(buf)
        assertEquals(4, visible.size)
        assertEquals("L76", marker(visible.first()))
        assertEquals("L79", marker(visible.last()))
        assertEquals(79L, vp.visibleRange(buf).last, "视口最后一行必须就是最新行")
    }

    @Test
    fun `上翻后追加新行偏移守恒`() {
        // 夹具：容量 50，一共只追加 40 行 ⇒ 不触发淘汰，firstIndex 恒 0（这条用例只测偏移守恒）
        val buf = LogBuffer(50)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 30)
        vp.scrollUp(5)
        assertEquals(5, vp.offsetFromEnd)
        assertFalse(vp.atBottom, "上翻后不能被判成贴底（原版 Value != Maximum）")
        grow(buf, vp, 30, 10)
        assertEquals(5, vp.offsetFromEnd, "追加新行只移动 Maximum，偏移一个数都不改")
        assertFalse(vp.atBottom, "上翻锁定：绝不跳回底部")
        assertEquals(34L, vp.visibleRange(buf).last, "底部 = 最新行 39 往上压着 5 行")
        assertEquals(25L, vp.visibleRange(buf).first)
        val visible = vp.rows(buf)
        assertEquals(10, visible.size)
        assertEquals("L25", marker(visible.first()))
        assertEquals("L34", marker(visible.last()))
    }

    @Test
    fun `满缓冲淘汰时上翻不跳底`() {
        // 夹具：容量 20（裸构造按传入值）⇒ 20 行就写满，追加到 50 行即 firstIndex 30，
        // 淘汰分支与「count 不变 ⇒ verticalScrollBarUpdate 早退」都能真的被走到（EmueraConsole.cs:2546-2550）。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 4)
        grow(buf, vp, 0, 20)
        vp.scrollUp(3)
        assertEquals(3, vp.offsetFromEnd)
        grow(buf, vp, 20, 30)
        assertEquals(3, vp.offsetFromEnd, "满缓冲淘汰走早退分支，偏移守恒")
        assertFalse(vp.atBottom)
        assertEquals(46L, vp.visibleRange(buf).last, "firstIndex 30 时底部保留区下标 16 ⇒ 行号 46 = 最新行 49 往上压着 3 行")
        val visible = vp.rows(buf)
        assertEquals(4, visible.size)
        assertEquals("L43", marker(visible.first()))
        assertEquals("L46", marker(visible.last()))
    }

    @Test
    fun `滚轮步长取 SCROLLHEIGHT 且看符号`() {
        // 滚轮：num = -Sign(delta) * SmallChange * Config.ScrollHeight，Value += num
        //（MainWindow.cs:1262、MainWindow.cs:1276-1287；SCROLLHEIGHT 默认 1 行，ConfigData.cs:74）
        // 夹具：容量 60，只追加 50 行 ⇒ 不淘汰（本用例只测步长与符号）
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 50)
        vp.onWheel(delta = 120)
        assertEquals(1, vp.offsetFromEnd, "向上滚一格 = 上翻 1 行（默认 SCROLLHEIGHT=1）")
        vp.onWheel(delta = -120)
        assertEquals(0, vp.offsetFromEnd)
        vp.onWheel(delta = -120)
        assertEquals(0, vp.offsetFromEnd, "贴底后继续向下滚不越过最新行（Value 夹在 Maximum）")
        vp.onWheel(delta = 120, linesPerScroll = 3)
        assertEquals(3, vp.offsetFromEnd, "SCROLLHEIGHT 参与步长")
        vp.onWheel(delta = 0)
        assertEquals(3, vp.offsetFromEnd)
    }

    @Test
    fun `翻页固定十行`() {
        // Keys.Prior / Keys.Next：固定 10 行，Prior 取负（MainWindow.cs:1379-1398）
        // 夹具：容量 60 > 追加的 50 行 ⇒ 不淘汰（本用例只测步长）
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 50)
        vp.pageUp()
        assertEquals(Viewport.PAGE_STEP, vp.offsetFromEnd)
        vp.pageUp()
        assertEquals(20, vp.offsetFromEnd)
        vp.pageDown()
        assertEquals(10, vp.offsetFromEnd)
        vp.pageDown()
        vp.pageDown()
        vp.pageDown()
        assertEquals(0, vp.offsetFromEnd, "连按 PageDown 收敛到贴底而不是翻到尾部之下")
        assertTrue(vp.atBottom)
    }

    @Test
    fun `点击或按键回贴底`() {
        // 上翻状态下鼠标点击与任意按键都无条件回贴底（MainWindow.cs:799-805、MainWindow.cs:1402-1406）
        // 夹具：容量 60 > 追加的 50 行 ⇒ 不淘汰，`Maximum` 就等于行数 50
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 50)
        vp.scrollUp(7)
        assertEquals(43, vp.scrollBarValue(), "Value = Maximum - offsetFromEnd = 50 - 7")
        assertFalse(vp.atBottom)
        vp.snapToBottom()
        assertTrue(vp.atBottom)
        assertEquals(vp.retainedLines, vp.scrollBarValue(), "贴底即 Value == Maximum")
        assertEquals("L49", marker(assertNotNull(vp.rows(buf).lastOrNull())))
    }

    @Test
    fun `上翻极限显示最早的一屏而不是空屏`() {
        // 原版 Minimum=0 允许 Value == 0 ⇒ num2 = -1 ⇒ 绘制循环一次都不进（EmueraConsole.cs:1806-1812、:1852），
        // 直译就是「滚到顶整屏黑」。M11-B DEV-01 按平台最优把 clamp 上界收为
        // max(0, retainedLines - heightLines)（取舍见 Viewport 类文档「能上翻到哪里」）：
        // 滚到顶显示保留区最早的一屏，不再复刻空屏。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 15)
        assertEquals(5, vp.maxOffset, "上界 = retainedLines - heightLines = 15 - 10")
        vp.scrollUp(99)
        assertEquals(5, vp.offsetFromEnd, "夹到上界而不是 retainedLines")
        assertEquals(10, vp.scrollBarValue(), "Value = Maximum - offset = 15 - 5")
        assertEquals(9, vp.bottomRingIndex(), "滚到顶时视口底部 = heightLines - 1")
        assertEquals(0, vp.topRingIndex(), "滚到顶时顶部夹到 0（EmueraConsole.cs:1808-1811）")
        assertEquals(0L..9L, vp.visibleRange(buf))
        assertEquals(10, vp.rows(buf).size)
        assertEquals("L0", marker(vp.rows(buf).first()), "屏上最早一行 = 保留区首行")
        assertEquals("L9", marker(vp.rows(buf).last()))
        assertFalse(vp.atBottom)
        // 再往下滚只回到贴底，不会越过
        vp.pageDown(1000)
        assertEquals(0, vp.offsetFromEnd)
        assertTrue(vp.atBottom)
    }

    @Test
    fun `内容不足一屏时没有上翻余量`() {
        // retainedLines <= heightLines ⇒ maxOffset == 0：视口恒贴底（无东西可滚）。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 5)
        assertEquals(0, vp.maxOffset)
        vp.scrollUp(99)
        assertEquals(0, vp.offsetFromEnd)
        assertTrue(vp.atBottom)
        assertEquals(5, vp.rows(buf).size)
    }

    @Test
    fun `列表缩短保留锚点只在越界时夹取`() {
        // 原版 num < 0 那一支把 Value 抬到 count（回贴底，EmueraConsole.cs:2557-2564）；本移植改成
        // 只夹进 [0, maxOffset]，因为 retainedLines 是折行后的显示行数、整屏重印时它会摆 ——
        // 归零等于每个重印周期把上翻的读者扔回底部（取舍见 Viewport 类文档「缩短不再拽回」）。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 4)
        grow(buf, vp, 0, 20)
        vp.scrollUp(6)
        assertEquals(6, vp.offsetFromEnd)
        buf.removeLast()
        vp.sync(buf)
        assertEquals(6, vp.offsetFromEnd, "少一行不许动玩家的锚点")
        assertFalse(vp.atBottom)
        assertEquals(19, vp.retainedLines)
        // 剩下的行不够一屏时 maxOffset 自己归零 ⇒ 仍是贴底，不需要额外的归零动作。
        buf.clear()
        vp.sync(buf)
        assertEquals(0, vp.retainedLines)
        assertFalse(vp.scrollEnabled)
        vp.scrollUp(3)
        assertEquals(0, vp.offsetFromEnd, "禁用态滚不动")
        assertTrue(vp.rows(buf).isEmpty())
    }

    @Test
    fun `视口边界夹在保留区内`() {
        // 夹具：容量 20（裸构造按传入值，Config.cs:451-455 的下限已归配置层），追加 50 行
        // ⇒ firstIndex 30 的满缓冲贴底态，与上一版 500 容量 + 530 行是同一个判据。
        val buf = LogBuffer(20)
        val vp = Viewport(heightLines = 4)
        grow(buf, vp, 0, 50) // firstIndex 30
        assertEquals(30L, buf.firstIndex)
        assertEquals(16, vp.topRingIndex(), "满缓冲贴底：底部下标 19，高 4 行 ⇒ 顶部 16")
        assertEquals(19, vp.bottomRingIndex())
        assertEquals(46L..49L, vp.visibleRange(20, 30L), "绝对行号 = firstIndex + 保留区下标")
        assertEquals(4, vp.rows(buf).size)
        assertEquals("L46", marker(vp.rows(buf).first()))
        assertEquals("L49", marker(vp.rows(buf).last()))
        // 行数不足一屏时顶部夹到 0（EmueraConsole.cs:1808-1811 的 if (num3 < 0) num3 = 0）
        val small = LogBuffer(20)
        val vp2 = Viewport(heightLines = 4)
        grow(small, vp2, 0, 2)
        assertEquals(0, vp2.topRingIndex())
        assertEquals(1, vp2.bottomRingIndex())
        assertEquals(0L..1L, vp2.visibleRange(small))
        assertEquals(2, vp2.rows(small).size)
    }

    @Test
    fun `绘制口径多读一行用 extraAbove 表达`() {
        // 绘制口径 num3 = num2 - (Height/LineHeight + 1)（EmueraConsole.cs:1805-1807）
        // 比命中口径 Height/LineHeight（EmueraConsole.cs:2328-2331）多读一行溢出
        // 夹具：容量 60 > 追加的 30 行 ⇒ 不淘汰，firstIndex 恒 0
        val buf = LogBuffer(60)
        val vp = Viewport(heightLines = 10)
        grow(buf, vp, 0, 30)
        assertEquals(20, vp.topRingIndex(), "命中检测口径")
        assertEquals(19, vp.topRingIndex(extraAbove = 1), "绘制口径多读一行")
        assertEquals(17, vp.topRingIndex(extraAbove = 3))
        assertEquals(20L..29L, vp.visibleRange(buf))
        assertEquals(17L..29L, vp.visibleRange(buf, extraAbove = 3))
        assertEquals(13, vp.rows(buf, extraAbove = 3).size)
    }

    @Test
    fun `onCleared 同时复位保留数与偏移`() {
        val vp = Viewport(heightLines = 10)
        val buf = LogBuffer(500)
        grow(buf, vp, 0, 40)
        vp.scrollUp(4)
        vp.onCleared()
        assertEquals(0, vp.retainedLines)
        assertEquals(0, vp.offsetFromEnd)
        assertTrue(vp.atBottom)
        assertEquals(0, vp.scrollBarValue())
        vp.sync(buf)
        assertEquals(40, vp.retainedLines, "sync 之后重新对齐缓冲实际保留数")
        assertTrue(vp.atBottom, "清屏后回到贴底初态")
    }
}
