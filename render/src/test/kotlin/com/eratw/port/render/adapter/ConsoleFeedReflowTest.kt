package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ConsoleFeed.swapScreen] 的判据（LIVE-REFLOW L3）。
 *
 * ## 判的是哪几条不变量
 *
 * 1. **只换屏、不发布**：`swapScreen` 只做「换 + 清」，重放交给调用方的 `force()`
 *    （契约 §5 L3：不自动 force，调用方先 `syncFeedLayout()` 再 `force()`）；
 * 2. **宽度单一坐标系**：`next.clientWidth != sink.clientWidth` 一律抛
 *    [IllegalArgumentException]，抛错前不动任何状态；
 * 3. **状态归零**：换屏后适配缓存、`previousTail`、`publishedRevision` 水位、内联锚点
 *    全部作废 —— 于是漏了 `force()` 也会由下一次 `pump()` 整屏重放（新屏日志是空的，
 *    `ConsoleLogSync.submit` 从空缓冲追加）；
 * 4. **`next === 当前屏` 返回 false 且零状态变化**（契约 §1 对 false 的定义）；
 * 5. **旧屏冻结**：换屏后旧屏的行表不再被写，新屏内容与旧屏那次快照逐行一致；
 * 6. **本波已知差异**：`PRINT_IMG` 一族的持久锚点随换屏丢失（KDoc 记明，后续靠 journal
 *    `logicalId` 再收），判据把它钉成**显式的可接受差异**，不是静默行为。
 *
 * ## 为什么用「在目标宽度直接构造 sink + screen」
 *
 * L1 的 `LineBufferConsoleSink.resize` 在本 worktree 里仍是 TODO（`clientWidth` 还是 `val`），
 * 所以这里不能真的「先在 20 格打印、再 resize 到 12 格、再换屏」。本文件的「不同宽」用例
 * 一律**按目标宽度直接构造 sink 与 screen**（等价于 resize 之后的那一对），只验证 swapScreen
 * 自己的换屏 / 重放 / 宽度校验；真实 `resize → swap` 的联动由 L7 的回归路覆盖。
 *
 * 夹具宽度 20 半角格、高 5 行（全部行都在视口内，脏区不被滚动污染），
 * 「不同宽」用例取 12 格与 10 格折行宽。
 */
class ConsoleFeedReflowTest {

    private fun sink(width: Int = 20) = LineBufferConsoleSink(clientWidth = width)

    private fun screen(width: Int = 20, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    // ==================================================================
    // 1) 换屏本身：换哪只、换没换、swap 自己不发布
    // ==================================================================

    @Test
    fun `换到同宽新屏返回 true 且 swap 自己不发布`() {
        val s = sink()
        s.printLine("abc")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh), "换了另一只屏 = 真换了")
        assertSame(fresh, feed.screen, "对外只读的 screen 必须指向新屏")
        assertEquals(emptyList<String>(), fresh.snapshotText(), "swapScreen 不自动 force：新屏此时还是空的")
        assertEquals(listOf("abc"), old.snapshotText(), "旧屏保留自己那一刻的内容")
    }

    /** 换屏后旧屏的行表不再被写；后续输出只进新屏（来源屏之间没有共享状态）。 */
    @Test
    fun `换屏后旧屏冻结而新屏独立接收后续输出`() {
        val s = sink()
        s.printLine("abc")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh))
        feed.force()
        s.printLine("def")
        feed.pump()

        assertEquals(listOf("abc"), old.snapshotText(), "旧屏内容冻结在换屏那一刻")
        assertEquals(listOf("abc", "def"), fresh.snapshotText())
    }

    // ==================================================================
    // 2) 重放：force 与 pump 两条路径都要能整屏重放
    // ==================================================================

    @Test
    fun `换屏后 force 把整屏快照重放进新屏且折行结果一致`() {
        val s = sink()
        s.printLine("abc")
        s.printLine("あ".repeat(12)) // 24 格 > 20 ⇒ 两排
        s.printLine("tail")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()
        val before = old.snapshotText()
        assertEquals(4, before.size, "1 + 折两排 + 1")

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh))
        val rects = feed.force()

        assertEquals(before, fresh.snapshotText(), "重放后逐行与旧屏一致（含折行位置）")
        assertEquals(1, rects.size, "新屏的显示列表从空起步 ⇒ 整屏标脏")
        assertEquals(0, rects.single().rowStart)
        assertFalse(feed.hasUnpublishedContent(), "force 之后水位已前移")
    }

    /** `publishedRevision` 若没被清，pump 会早退、新屏永远空着 —— 这条钉住「水位归零」。 */
    @Test
    fun `换屏后不 force 也由下一次 pump 整屏重放`() {
        val s = sink()
        s.printLine("abc")
        s.printLine("def")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()
        assertFalse(feed.hasUnpublishedContent(), "换屏前已经交干净了")

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh))
        assertTrue(feed.hasUnpublishedContent(), "水位已清：新屏还什么都没收到")

        assertTrue(feed.pump().isNotEmpty())
        assertEquals(listOf("abc", "def"), fresh.snapshotText())
        assertFalse(feed.hasUnpublishedContent())
    }

    // ==================================================================
    // 3) 宽度：单一坐标系、按新屏自己的折行器重排
    // ==================================================================

    /**
     * 「不同宽」的合法形态：核心侧 resize 之后，sink 与 screen **同为** 12 格。
     * 重放必须按 12 格折行，而不是把 20 格那次的排法搬过来。
     */
    @Test
    fun `在目标宽度构造 sink 与 screen 后换屏重放按该宽度折行`() {
        val text = "abcdeabcdeabcdeabcde" // 20 半角格

        val wideSink = sink(width = 20)
        wideSink.printLine(text)
        val wide = ConsoleFeed(wideSink, screen(width = 20))
        wide.pump()
        assertEquals(listOf(text), wide.screen.snapshotText(), "20 格宽：整行一排")

        // 目标宽度 12：sink 与 screen 都直接按 12 构造（等价于 L1 resize 之后的那一对）。
        val narrowSink = sink(width = 12)
        narrowSink.printLine(text)
        val old = screen(width = 12)
        val feed = ConsoleFeed(narrowSink, old)
        feed.pump()
        assertEquals(listOf("abcdeabcdeab", "cdeabcde"), old.snapshotText())

        val fresh = screen(width = 12)
        assertTrue(feed.swapScreen(fresh))
        feed.force()
        assertEquals(old.snapshotText(), fresh.snapshotText())
        assertEquals(2, fresh.snapshotText().size, "按 12 格折成两排，不是 20 格的一排")
    }

    /**
     * 折行宽（`drawableWidthCells`）也换了时：新屏必须用自己的折行器重排。
     * 这条能抓到「换屏后仍复用旧屏折行/适配产物」这一类残状态。
     */
    @Test
    fun `换到折行宽不同的新屏时按新屏的折行器重排`() {
        val s = sink()
        s.printLine("abcdeabcdeabcdeabcde")
        val narrowFold = ConsoleScreen(
            clientWidth = 20,
            maxLog = 100,
            heightLines = 5,
            drawableWidthCells = 10,
        )
        val feed = ConsoleFeed(s, narrowFold)
        feed.pump()
        assertEquals(listOf("abcdeabcde", "abcdeabcde"), narrowFold.snapshotText(), "旧屏按 10 格折两排")

        val fresh = screen() // drawableWidthCells 缺省 = clientWidth = 20
        assertTrue(feed.swapScreen(fresh))
        feed.force()
        assertEquals(
            listOf("abcdeabcdeabcdeabcde"),
            fresh.snapshotText(),
            "新屏按自己的折行宽重排：旧屏那两排不得被复用",
        )
    }

    @Test
    fun `宽度不一致时抛错且不换屏不动水位`() {
        val s = sink(width = 20)
        s.printLine("abc")
        val old = screen(width = 20)
        val feed = ConsoleFeed(s, old)
        feed.pump()

        val narrow = screen(width = 12)
        val ex = assertFailsWith<IllegalArgumentException> { feed.swapScreen(narrow) }
        assertTrue(ex.message.orEmpty().contains("clientWidth"), "报错要写明两边的宽度：${ex.message}")

        assertSame(old, feed.screen, "抛错前不得换屏")
        assertFalse(feed.hasUnpublishedContent(), "抛错前不得动发布水位")
        assertTrue(feed.pump().isEmpty(), "抛错后仍是同一 revision：零脏区")
    }

    // ==================================================================
    // 4) false 的合法含义：无需重建 + 零状态变化
    // ==================================================================

    @Test
    fun `swap 同一实例返回 false 且不动任何状态`() {
        val s = sink()
        s.printLine("abc")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()

        assertFalse(feed.swapScreen(old), "就是当前屏：无需重建")
        assertFalse(feed.hasUnpublishedContent(), "返回 false 时水位不得被清")

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh))
        feed.force()
        assertFalse(feed.swapScreen(fresh), "刚换上的屏再换一次仍是 false")
        assertFalse(feed.hasUnpublishedContent(), "重复 swap 不得把水位清回未发布")
        assertEquals(listOf("abc"), fresh.snapshotText())
    }

    // ==================================================================
    // 5) 内联图形锚点：本波接受的已知差异
    // ==================================================================

    @Test
    fun `换屏清空内联图形锚点（本波接受 PRINT_IMG 丢失）`() {
        val s = sink()
        s.printLine("甲")
        val layer = GraphicsLayer()
        layer.enqueue(InlineGraphic.Image("a", 60, 30, 0, 7))
        val old = screen()
        val feed = ConsoleFeed(s, old, layer, CellMetrics(18))
        feed.pump()
        assertEquals(RunKind.IMAGE, old.list.render().last().kind, "换屏前 PRINT_IMG 已持久贴在行尾")

        val fresh = screen()
        assertTrue(feed.swapScreen(fresh))
        feed.force()

        assertEquals(listOf("甲"), fresh.snapshotText(), "本波口径：换屏后内联图形不再重贴（锚点已清）")
        assertTrue(
            fresh.list.render().none { it.kind == RunKind.IMAGE || it.kind == RunKind.SHAPE },
            "图形段不得残留到新屏",
        )
    }

    // ==================================================================
    // 6) 关闭态：没人调 swapScreen 就零行为变化
    // ==================================================================

    @Test
    fun `不调用 swapScreen 时既有 pump force 行为逐项不变`() {
        val s = sink()
        s.printLine("abc")
        val only = screen()
        val feed = ConsoleFeed(s, only)

        assertEquals(1, feed.pump().size, "首帧整屏脏（既有口径）")
        assertTrue(feed.pump().isEmpty(), "同一 revision 再 pump 幂等")
        assertFalse(feed.hasUnpublishedContent())
        assertTrue(feed.force().isEmpty(), "内容没变：force 重交也是空脏区")
        assertEquals(listOf("abc"), only.snapshotText())
        assertSame(only, feed.screen, "没换屏：始终是构造时那只")
    }

    // ==================================================================
    // 7) 交接窗口：核心宽与屏宽差的那一代（L4 换屏前置条件的读数口径）
    // ==================================================================

    /**
     * [ConsoleFeed.sinkClientWidth] 为什么必须单独存在：宿主链是
     * `sink.resize(newCols)` → `view.reflowAttachedFeed(newCols)`，中间这一格
     * **核心已经是新宽、屏还挂着旧宽**。拿 `screen.clientWidth` 当「核心是否已重排」的判据
     * 会在这格反向（2026-11-04 L4 验收时抓到），所以判据只能读 [ConsoleFeed.sinkClientWidth]。
     *
     * L1 已并入，这里走**真实 resize**：20 格打印 → 重排到 12 格 → 换 12 格屏。
     */
    @Test
    fun `resize 后换屏前核心宽与屏宽差一代且判据只认核心宽`() {
        val s = LineBufferConsoleSink(clientWidth = 20, reflowEnabled = true)
        s.printLine("abcdefghijklmnopqrstuvwxyz0123456789")
        val old = screen()
        val feed = ConsoleFeed(s, old)
        feed.pump()
        assertEquals(20, feed.sinkClientWidth, "初始两把尺对齐")
        assertEquals(feed.sinkClientWidth, feed.screen.clientWidth)

        assertTrue(s.resize(12), "核心按新列数重排")
        assertEquals(12, feed.sinkClientWidth, "交接窗口：核心已是新宽")
        assertEquals(20, feed.screen.clientWidth, "而屏仍是上一代宽度 —— 判据不许读这一支")

        assertFailsWith<IllegalArgumentException> {
            feed.swapScreen(screen(width = 20))
        }.let { e -> assertTrue(e.message!!.contains("sink.clientWidth=12"), "require 给的是核心宽：${e.message}") }

        val fresh = screen(width = 12)
        assertTrue(feed.swapScreen(fresh))
        assertEquals(12, feed.screen.clientWidth, "换屏后两把尺重新钉回相等")
    }
}
