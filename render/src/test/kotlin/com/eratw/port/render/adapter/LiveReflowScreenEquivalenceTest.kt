package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * LIVE-REFLOW L7（回归路）渲染侧的两条等价钉子（契约 CONTRACT-LIVE-REFLOW §3.3 / §5 / §8 第 4 条）。
 *
 * ## A：开关关（不开 journal、不换屏）= 既有横屏渲染链逐字等价
 *
 * 同一段打印脚本分别喂给 `reflowEnabled=false` 与 `reflowEnabled=true` 的核心 sink，
 * 各挂一只同宽同高的 [ConsoleScreen]，**每一步之后**比：可见行文本（[ConsoleScreen.snapshotText]）、
 * 按钮占格尺（[ConsoleScreen.snapshotButtonCells]）、脏区（[DirtyRect] 列表）、行数、浮岛原文。
 * 于是「journal 的开销」在渲染面同样是零可见差异。
 *
 * ## B：开关开 = 走真实链重排后与「一开始就在新宽度的一局」等价
 *
 * 真实链（宿主链的渲染半段，契约 §5 L3/L4）：
 * `sink.resize(新列)` → 新建空屏 `ConsoleScreen(新列, maxLog, 新行)` → `feed.swapScreen(fresh)`
 * → `feed.force()`。与「直接在新宽度构造 feed + screen、打同样一批行、pump」比可见行文本、
 * 按钮占格尺与脏区口径。
 *
 * ## 交接窗口的口径（组长在 L4 验收时钉过，别写反）
 *
 * 宿主链是 `session.resizeConsole(新列)` → `view.reflowAttachedFeed(新列)`，
 * **中间那一格核心已是新宽、屏还挂旧宽**。「核心是否已重排」只能读 [ConsoleFeed.sinkClientWidth]
 * （`6380bdb7` 新开的纯 Int 口），读 `feed.screen.clientWidth` 拿到的是上一代宽度。
 * 本文件把那一代际差显式钉成一枚用例（见「resize 后换屏前」）。
 *
 * ## 本波已知边界（不当 bug 报）
 *
 * 换屏丢 `PRINT_IMG` 内联图形锚点已由 `ConsoleFeedReflowTest` 钉住，本文件不重复；
 * 被 MaxLog 吃掉前半截的逻辑行冻结不重排由核心侧 `LiveReflowLandscapeEquivalenceTest` 钉住。
 *
 * A 组夹具宽度 20 半角格、高 5 行（内容都远小于一屏，脏区不被滚动污染）；
 * B 组重排目标宽度 12，屏高按用例给到 20 行（整份程序都在窗口内）或 3 行（钉「行列同时变」）。
 * 本文件只新增测试，不改任何生产文件。
 */
class LiveReflowScreenEquivalenceTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun closedSink(width: Int) = LineBufferConsoleSink(clientWidth = width, maxLines = 200)

    private fun openSink(width: Int) =
        LineBufferConsoleSink(clientWidth = width, maxLines = 200, reflowEnabled = true)

    private fun screen(width: Int, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    /** 一次渲染面可见状态：屏上文本、按钮占格尺、窗口行数、浮岛、核心宽。 */
    private data class ScreenState(
        val text: List<String>,
        val buttonCells: List<String>,
        val visibleRowCount: Int,
        val islands: List<String>,
        val screenWidth: Int,
        val sinkWidth: Int,
    )

    private fun stateOf(feed: ConsoleFeed): ScreenState = ScreenState(
        text = feed.screen.snapshotText(),
        buttonCells = feed.screen.snapshotButtonCells(),
        visibleRowCount = feed.screen.visibleRows().size,
        islands = feed.screen.islandHtml(),
        screenWidth = feed.screen.clientWidth,
        sinkWidth = feed.sinkClientWidth,
    )

    /** 脏区的可读形态（行窗口 + 列窗口），比 `List<DirtyRect>` 的失败信息更直观。 */
    private fun rectsOf(rects: List<DirtyRect>): List<String> =
        rects.map { "rows=${it.rowStart}..${it.rowEnd} cols=${it.colStart}..${it.colEnd}" }

    /** 孪生 feed：同一步动作喂两棵树（关态与开态），每步之后比全部渲染面读数。 */
    private inner class TwinFeed(val width: Int, val heightLines: Int) {
        val offSink = closedSink(width)
        val onSink = openSink(width)
        val off = ConsoleFeed(offSink, screen(width, heightLines))
        val on = ConsoleFeed(onSink, screen(width, heightLines))
        private var seq = 0

        fun step(name: String, action: (LineBufferConsoleSink) -> Unit) {
            seq++
            action(offSink)
            action(onSink)
            val offRects = rectsOf(off.pump())
            val onRects = rectsOf(on.pump())
            assertEquals(offRects, onRects, "第 $seq 步「$name」的脏区口径必须逐字相同")
            assertEquals(
                stateOf(off),
                stateOf(on),
                "第 $seq 步「$name」之后：开 journal 的渲染链必须与关 journal 逐项一致",
            )
        }
    }

    private fun twin(width: Int = 20, heightLines: Int = 5) = TwinFeed(width, heightLines)

    // ==================================================================
    // A：关态 == 开态（渲染面逐字等价）
    // ==================================================================

    /** 折行 / PRINTN 并入 / CLEARLINE 三条既有分支在渲染面逐字等价。 */
    @Test
    fun `折行与未结行并入与清除行的渲染链开journal与关journal逐字等价`() {
        val t = twin()
        t.step("短行") { it.printLine("abc") }
        t.step("超长行（折 3 排）") { it.printLine("0123456789012345678901234567890123456789") }
        t.step("全角长行") { it.printLine("あいうえおかきくけこさしすせそ") }
        t.step("PRINTN 未结") {
            it.print("前半", false)
            it.newLine()
        }
        t.step("PRINTN 续打并入") {
            it.print("后半段", true)
            it.newLine()
        }
        t.step("CLEARLINE 1（快照变短 → 回撤）") { it.deleteLine(1) }
        t.step("CLEARLINE 3") { it.deleteLine(3) }
        t.step("重建") { it.printLine("重建后的行") }
    }

    /** 按钮面（显式 PRINTBUTTON 与自动按钮 `[1]`）的占格尺与脏区逐字等价。 */
    @Test
    fun `按钮面的占格尺与脏区开journal与关journal逐字等价`() {
        val t = twin()
        t.step("显式按钮行") {
            it.printButton("确定", 1L)
            it.print(" ")
            it.printButton("取消", "CANCEL")
            it.newLine()
        }
        t.step("自动按钮行") { it.printLine("[1] 战斗 [2] 休息 [3] 状态") }
        t.step("跨排长按钮行") {
            it.printLine("开场白很长很长" + "[7] 选项文本" + "尾随很长很长")
        }
        t.step("ALIGNMENT CENTER 带按钮") {
            it.setAlignment(Alignment.CENTER)
            it.printButton("居中钮", 4L)
            it.newLine()
            it.setAlignment(Alignment.LEFT)
        }
        assertTrue(
            t.on.screen.snapshotButtonCells().any { it.contains('B') },
            "夹具必须真的排出按钮格，否则这条用例是空跑",
        )
    }

    /** HTML / 样式 / 临时行 / 分隔线 / 错误行的渲染链逐字等价。 */
    @Test
    fun `HTML与样式与临时行与分隔线的渲染链开journal与关journal逐字等价`() {
        val t = twin()
        t.step("直印 HTML 带按钮") {
            it.printHtml("<button value='12'>开始</button> 与 <button value='x'>字符串钮</button>", option = false)
        }
        t.step("缓冲 HTML 段（随正文折行）") {
            it.printHtml("<b>加粗片段</b>后随一长串普通文本一长串普通文本", option = true)
            it.newLine()
        }
        t.step("SETCOLOR / SETBACKCOLOR / FONTSTYLE") {
            it.setForeColor(255, 64, 32)
            it.setBackColor(0, 128, 255)
            it.setFontStyle(1 or 8)
            it.printLine("带样式的一行带样式的一行带样式的一行")
        }
        t.step("REUSELASTLINE 临时行 + 正式行删掉它") {
            it.printTemporaryLine("temporary")
            it.printLine("正式")
        }
        t.step("DRAWLINE") {
            it.printBar()
            it.newLine()
        }
        t.step("PRINTSINGLE 超长") { it.printSingleLine("单行".repeat(20)) }
        t.step("错误行") { it.printError("错误：出事了") }
    }

    // ==================================================================
    // B：开态走真实链重排 == 一开始就在新宽度的一局
    // ==================================================================

    /**
     * 真实链：20 格打印并 pump → [LineBufferConsoleSink.resize] 到 12 → 新建空屏
     * `ConsoleScreen(12, maxLog, 20)` → [ConsoleFeed.swapScreen] → [ConsoleFeed.force]。
     * 可见行文本、按钮占格尺与「直接在 12 格构造 feed+screen 打同样一批」逐行相同；
     * 脏区口径都是「新屏从空起步 ⇒ 整屏一条脏区、rowStart=0」。
     * 屏高给到 20 行，让整份程序都落在可视窗口里（否则只能比到滚动裁剩的尾部）。
     */
    @Test
    fun `重排换屏 force 后的屏面与一开始就在新宽度逐行相同`() {
        val program: (LineBufferConsoleSink) -> Unit = { s ->
            s.printLine("开场白")
            s.printLine("很长的正文很长的正文很长的正文很长") // 20 折 2 排 / 12 折 3 排
            s.printLine("[1] 战斗 [2] 休息 [3] 状态")
            s.printButton("确定", 7L)
            s.print(" ")
            s.printButton("取消", "CANCEL")
            s.newLine()
            s.setAlignment(Alignment.CENTER)
            s.printLine("居中短行")
            s.setAlignment(Alignment.LEFT)
            s.printHtml("<button value='21'>直印按钮</button>", option = false)
            s.printLine("收尾")
        }

        // 重排那一棵树（屏高故意给到 20 行：整个程序都在可视窗口里，比对不被滚动裁掉）
        val sink = openSink(20)
        program(sink)
        val reflowFeed = ConsoleFeed(sink, screen(20, heightLines = 20))
        reflowFeed.pump()
        assertTrue(sink.resize(12), "核心先按新列数重排")
        val fresh = screen(12, heightLines = 20)
        assertTrue(reflowFeed.swapScreen(fresh), "换上新宽度的空屏")
        val reflowRects = reflowFeed.force()

        // 对照组：一开始就在 12 格
        val directSink = closedSink(12)
        program(directSink)
        val directFeed = ConsoleFeed(directSink, screen(12, heightLines = 20))
        val directRects = directFeed.pump()

        assertEquals(
            directFeed.screen.snapshotText(),
            reflowFeed.screen.snapshotText(),
            "重排换屏后的可见行文本必须与「一开始就 12 格」逐行相同",
        )
        assertEquals(
            directFeed.screen.snapshotButtonCells(),
            reflowFeed.screen.snapshotButtonCells(),
            "按钮占格尺（屏上绝对列）也要逐行相同",
        )
        assertEquals(1, reflowRects.size, "新屏日志为空 ⇒ force 整屏一条脏区")
        assertEquals(0, reflowRects.single().rowStart)
        assertEquals(rectsOf(directRects), rectsOf(reflowRects), "脏区口径与首帧同形")
        assertEquals(12, reflowFeed.screen.clientWidth)
        assertEquals(12, reflowFeed.sinkClientWidth, "换屏后两把尺重新钉回相等")
        assertTrue(reflowFeed.screen.snapshotText().any { it.contains("开场白") }, "夹具真的把内容搬上了屏")
        assertTrue(
            reflowFeed.screen.snapshotText().count { it.isNotBlank() } >=
                directFeed.screen.snapshotText().count { it.isNotBlank() },
            "重放不许比对照组少行",
        )
    }

    /**
     * 交接窗口那一格的读数口径（组长在 L4 验收时钉的）：
     * `resize` 之后、`swapScreen` 之前，**核心已是新宽、屏还挂旧宽**；
     * 判「核心是否已重排」只能读 [ConsoleFeed.sinkClientWidth]，读 `screen.clientWidth` 会反向。
     */
    @Test
    fun `resize 后换屏前核心宽与屏宽差一代且判据只认核心宽`() {
        val sink = openSink(20)
        sink.printLine("abcdefghijklmnopqrstuvwxyz0123456789")
        val old = screen(20)
        val feed = ConsoleFeed(sink, old)
        feed.pump()
        assertEquals(feed.sinkClientWidth, feed.screen.clientWidth, "初始两把尺对齐")

        assertTrue(sink.resize(12))
        assertEquals(12, feed.sinkClientWidth, "交接窗口：核心已是新宽")
        assertEquals(20, feed.screen.clientWidth, "屏仍是上一代宽度 —— 判据不许读这一支")
        assertNotEquals(feed.sinkClientWidth, feed.screen.clientWidth, "这一格两把尺必须不同")

        val ex = assertFailsWith<IllegalArgumentException> { feed.swapScreen(screen(20)) }
        assertTrue(ex.message.orEmpty().contains("sink.clientWidth=12"), "require 报的是核心宽：${ex.message}")

        assertTrue(feed.swapScreen(screen(12)))
        assertEquals(12, feed.sinkClientWidth)
        assertEquals(12, feed.screen.clientWidth, "换屏后两把尺重新相等")
    }

    /**
     * 换屏时行数也变了（`ConsoleScreen(新列, maxLog, 新行)`，契约 §5 L4 的七步）：
     * 视口按新行高重算，屏面与「直接在新尺寸打同样一批」逐行相同。
     */
    @Test
    fun `换屏同时改行列数后视口按新尺寸且屏面与新尺寸一局相同`() {
        val program: (LineBufferConsoleSink) -> Unit = { s ->
            repeat(8) { i -> s.printLine("第 $i 行正文") }
            s.printLine("很长的正文很长的正文很长的正文很长")
            s.printLine("最后一行")
        }

        val sink = openSink(20)
        program(sink)
        val feed = ConsoleFeed(sink, screen(20, heightLines = 5))
        feed.pump()
        assertTrue(sink.resize(12))
        assertTrue(feed.swapScreen(screen(12, heightLines = 3)))
        val rects = feed.force()

        val directSink = closedSink(12)
        program(directSink)
        val direct = ConsoleFeed(directSink, screen(12, heightLines = 3))
        direct.pump()

        assertEquals(3, feed.screen.heightLines, "新屏的行数就是入参那一个")
        assertEquals(direct.screen.visibleRows().size, feed.screen.visibleRows().size)
        assertEquals(direct.screen.snapshotText(), feed.screen.snapshotText(), "新行高下的可见窗口相同")
        assertEquals(1, rects.size, "整屏重放：一条脏区")
        assertEquals(0, rects.single().rowStart)
    }

    /**
     * 重排换屏之后继续打印：新行按新宽度上屏，与「直接在新宽度打到这一处」逐行相同，
     * 且脏区是增量口径（只脏新行，不再整屏重放）。
     */
    @Test
    fun `重排换屏后继续打印的屏面与增量脏区与新宽一局相同`() {
        val sink = openSink(20)
        sink.printLine("第一屏的内容")
        val feed = ConsoleFeed(sink, screen(20))
        feed.pump()

        assertTrue(sink.resize(12))
        assertTrue(feed.swapScreen(screen(12)))
        feed.force()

        sink.printLine("重排后新打印的一行很长很长很长")
        val reflowRects = feed.pump()

        val directSink = closedSink(12)
        directSink.printLine("第一屏的内容")
        val direct = ConsoleFeed(directSink, screen(12))
        direct.pump()
        directSink.printLine("重排后新打印的一行很长很长很长")
        val directRects = direct.pump()

        assertEquals(direct.screen.snapshotText(), feed.screen.snapshotText())
        assertEquals(rectsOf(directRects), rectsOf(reflowRects), "换屏后的增量脏区口径与首帧一局相同")
        assertTrue(reflowRects.isNotEmpty(), "有新内容就必须有脏区")
        assertEquals(12, feed.sinkClientWidth)
    }

    /**
     * PRINTN 未结行跨过重排边界：核心允许物理换行位置不同（原版并入不重新折行，ADR-005），
     * 渲染层按新屏的折行器重新折一遍，于是**屏上的字符序列**与「一开始就在新宽度」仍然相同。
     */
    @Test
    fun `未结行跨过重排边界后屏上字符序列与新宽一局相同`() {
        val program: (LineBufferConsoleSink) -> Unit = { s ->
            s.print("甲段乙段", false) // 8 格：两个宽度下都不折行
            s.newLine()
            s.print("丙丁戊己庚辛壬癸", true) // 并入后 16 格 > 12 ⇒ 核心侧换行位置可以不同
            s.newLine()
            s.printLine("后续行")
        }

        val sink = openSink(20)
        program(sink)
        val feed = ConsoleFeed(sink, screen(20))
        feed.pump()
        assertTrue(sink.resize(12))
        assertTrue(feed.swapScreen(screen(12)))
        feed.force()

        val directSink = closedSink(12)
        program(directSink)
        val direct = ConsoleFeed(directSink, screen(12))
        direct.pump()

        val reflowJoined = feed.screen.snapshotText().joinToString("") { it.trimEnd() }
        val directJoined = direct.screen.snapshotText().joinToString("") { it.trimEnd() }
        assertEquals(directJoined, reflowJoined, "屏上逐字符拼接必须一致（丢字/串行都会在这里现形）")
        assertTrue(reflowJoined.contains("甲段乙段丙丁戊己庚辛壬癸"), "并入后的文本在屏上连续：$reflowJoined")
        assertTrue(reflowJoined.contains("后续行"), "重排后续打的行也上了屏")
        // 已知、契约允许的差别：核心侧换行位置可以不同（并入不重新折行），渲染层按新屏折行器再折一次。
        // 所以这里**不比**逐行表，只比字符序列 —— 逐行相同只在没有并入的程序上成立（见上一条用例）。
    }

    /** 关态红线在渲染面的对应物：开 journal 但**没人调 swapScreen** ⇒ 既有 pump/force 行为零变化。 */
    @Test
    fun `开journal但不换屏时既有pump与force行为与关态逐项相同`() {
        val offSink = closedSink(20)
        val onSink = openSink(20)
        offSink.printLine("abc")
        onSink.printLine("abc")
        val off = ConsoleFeed(offSink, screen(20))
        val on = ConsoleFeed(onSink, screen(20))

        assertEquals(rectsOf(off.pump()), rectsOf(on.pump()), "首帧整屏脏（既有口径）")
        assertEquals(rectsOf(off.pump()), rectsOf(on.pump()), "同一 revision 再 pump 幂等")
        assertEquals(rectsOf(off.force()), rectsOf(on.force()), "内容没变时 force 也是空脏区")
        assertEquals(stateOf(off), stateOf(on))
        assertTrue(!on.hasUnpublishedContent(), "开 journal 不许让 pump 误判成有未发布内容")
    }
}
