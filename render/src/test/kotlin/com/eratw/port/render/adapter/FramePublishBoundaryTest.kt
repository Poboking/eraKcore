package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 帧发布边界的**单一判据**（A2 · P0）：`ConsoleFeed` 判「要不要交」只做两次版本比较 ——
 * 文字流看 [LineBufferConsoleSink.revision]，图形流看 [GraphicsLayer.inlineRevision]
 * （内联段到达版本），不再拿「内联队列非空」这种**状态代理**当发布判据。
 *
 * 为什么状态代理不够：队列由发布方自己排空（`ConsoleFeed.publish` 里的 `drainInline`），
 * 排空即变假，与「那一批内容有没有真交出去」不是一回事（`GraphicsLayer.reset` 清队列同理）。
 * 到达版本只增不减，发布水位只在整帧提交成功之后记，两者一次比较就是答案。
 *
 * 三条用例：
 * - (a) 只有内联段到达（sink 的 revision 一步没动）恰好发布一次，第二次 pump 空表；
 * - (b) 冻结段内 `CLEARLINE` 之后内联段到达：发布拿的是**发布时刻快照**，不是清屏后的
 *   中间态（与 `FrameGateFrozenSnapshotTest` 的「动态地图整屏交替」病灶同源）；
 * - (c) 同一输入序列在 `frameGateEnabled=true/false` 下逐帧文本一致（逐帧比对）。
 *
 * 宽度 20 半角格、字号 18px；全角字符各占 2 格，图形段以等量空格占宽（既有口径）。
 */
class FramePublishBoundaryTest {

    private val metrics = CellMetrics(18)

    private fun sink(width: Int = 20) = LineBufferConsoleSink(clientWidth = width)

    private fun screen(width: Int = 20, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    // ==================================================================
    // (a) 只有内联段到达 ⇒ 恰好一帧
    // ==================================================================

    /**
     * sink 的 revision 是文字流的水位，图形语句**不推进它**（`GraphicsLayer.revision` 是另一
     * 条流）。所以「屏上要变」不能靠 revision 相等来判 —— 也不能靠队列非空来判；只有到达版本
     * 与发布水位的一次比较能同时给出「该交」与「交过了」两个答案。
     */
    @Test
    fun `只来内联段时恰好发布一次`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("血条")
        val f = ConsoleFeed(s, screen(), layer, metrics)
        assertTrue(f.pump().isNotEmpty(), "首帧整屏")
        val sinkRev = s.revision
        val inlineRev = layer.inlineRevision()
        assertFalse(f.hasUnpublishedContent(), "首帧之后没有待发布内容")

        layer.enqueue(InlineGraphic.Space(3))
        assertEquals(sinkRev, s.revision, "内联段不推进 sink 的 revision")
        assertEquals(inlineRev + 1, layer.inlineRevision(), "入队推进到达版本")
        assertTrue(f.hasUnpublishedContent(), "到达版本前进 ⇒ 循环侧必须 invalidate")

        val rects = f.pump()
        assertTrue(rects.isNotEmpty(), "版本前进就要交，哪怕 revision 一步没动")
        assertEquals(listOf("血条   "), f.screen.snapshotText(), "图形段以 3 格空白占宽")
        assertTrue(f.pump().isEmpty(), "发布水位已记 ⇒ 第二次 pump 必须返回空表")
        assertFalse(f.hasUnpublishedContent(), "水位收敛之后不再 invalidate")
        assertFalse(layer.hasPendingInline(), "队列状态查询保留（测试/预览在用），与发布水位无关")
    }

    /** 到达版本只由入队推进：排空不是到达，`reset` 清队列也不是。 */
    @Test
    fun `内联到达版本只由入队推进`() {
        val layer = GraphicsLayer()
        val start = layer.inlineRevision()
        layer.enqueue(InlineGraphic.Space(2))
        layer.enqueue(InlineGraphic.Space(2))
        assertEquals(start + 2, layer.inlineRevision())
        layer.drainInline()
        assertEquals(start + 2, layer.inlineRevision(), "排空不动到达版本")
        assertFalse(layer.hasPendingInline(), "排队列已空（状态代理此时为假）")
        layer.reset()
        assertEquals(start + 2, layer.inlineRevision(), "清队列/换会话不动到达版本")
    }

    // ==================================================================
    // (b) 冻结段：清行之后内联到达，上屏的必须是发布时刻整帧
    // ==================================================================

    /**
     * 病灶形态（2026-11-05 真机「动态地图整屏交替」）：闸开直通写行把发布快照置空，让出边界
     * 关闸进冻结段，循环体先 `CLEARLINE`、后重印。
     *
     * 内联段在冻结段中间到达会把发布拉起来一次（图形流自己的水位前进，与文字流无关）。
     * 那一刻上屏的必须是**发布边界定下的整帧**（sink 的 `frameSnapshot`），而不是
     * `frameSnapshot` 回落活表抓到的「清屏之后、重印之前」空屏 —— 后者就是屏上两次画面
     * 严格交替的来源。原版没有这个窗口：`RefreshStrings(force_Paint: true)` 在脚本线程内
     * 同步画完（EmueraConsole.cs:840-856），快照点恒等于发布点。
     */
    @Test
    fun `冻结段内清行后内联到达 上屏的是发布时刻整帧`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("地图前日志")
        val f = ConsoleFeed(s, screen(), layer, metrics)
        f.pump()
        assertEquals(listOf("地图前日志"), f.screen.snapshotText())

        // 让出边界 → 关闸：下一段循环体的写行只置脏、不动发布快照。
        s.publishFrame()
        s.holdFrame()
        val revInFrozen = s.revision
        s.deleteLine(1)
        assertEquals(revInFrozen, s.revision, "冻结段文字变更不推进 sink 的 revision")

        // 循环体里的图形语句此刻到达：图形流自己的水位前进，把发布拉起来一次。
        layer.enqueue(InlineGraphic.Space(3))
        f.pump()

        assertEquals(
            listOf("地图前日志   "),
            f.screen.snapshotText(),
            "冻结段内被拉起的发布必须用发布时刻整帧，不是清屏后的中间态",
        )
        assertEquals(
            listOf("地图前日志"),
            s.frameSnapshot().map { s.textOf(it) },
            "sink 的发布快照仍是让出边界那一刻的整帧",
        )

        // 解冻：冻结段攒出的完整帧一次性上屏，覆盖上一帧。
        s.printLine("地图页")
        s.publishFrame()
        f.pump()
        assertEquals(listOf("地图页"), f.screen.snapshotText(), "解冻发布的是整帧")
    }

    // ==================================================================
    // (c) 帧闸开关下逐帧一致
    // ==================================================================

    /** 一次「sink + 图形层 + feed」的最小装配，按帧记录屏上文本。 */
    private inner class Session(frameGateEnabled: Boolean) {
        val sink = LineBufferConsoleSink(clientWidth = 20, frameGateEnabled = frameGateEnabled)
        val layer = GraphicsLayer()
        val feed = ConsoleFeed(sink, screen(), layer, metrics)
        val frames = ArrayList<List<String>>()
        fun pumpFrame() {
            feed.pump()
            frames.add(feed.screen.snapshotText())
        }

        /** 让出边界的标准顺序：先整帧发布、再关闸（`holdFrame` 在 `publishFrame` 之后）。 */
        fun boundary() {
            sink.publishFrame()
            sink.holdFrame()
        }
    }

    /**
     * 同一输入序列、同一 pump 节奏，只在 `frameGateEnabled` 上不同 ⇒ 逐帧文本必须一致。
     *
     * 序列的构造有一个硬约束：**冻结段内不做「写文字 + pump」**。那段窗口正是帧闸存在的理由
     * （闸关攒帧，见 `FrameGateFrozenSnapshotTest`），闸开侧的直通会把还没定下来的中间态
     * 先送上屏；两闸在这条路径上本来就不该一致。这里比的是生产形态：文字只在让出边界
     * 上屏，冻结段中途被拉起的发布只可能来自图形流。
     */
    private fun drive(frameGateEnabled: Boolean): List<List<String>> {
        val t = Session(frameGateEnabled)
        t.sink.printLine("甲")
        t.pumpFrame() // 帧 1：开局直通
        t.boundary()
        t.layer.enqueue(InlineGraphic.Space(2))
        t.pumpFrame() // 帧 2：冻结段内只有图形语句（没有文字变更）
        t.sink.printLine("乙")
        t.boundary()
        t.pumpFrame() // 帧 3：边界上屏新行
        t.sink.deleteLine(1)
        t.boundary()
        t.pumpFrame() // 帧 4：边界上屏删行
        t.layer.enqueue(InlineGraphic.Space(3))
        t.pumpFrame() // 帧 5：冻结段内图形到达，文字表仍在边界上那一版
        t.sink.printLine("丙")
        t.boundary()
        t.pumpFrame() // 帧 6：下一帧整帧
        return t.frames
    }

    @Test
    fun `同一输入序列在帧闸开关下逐帧文本一致`() {
        val gated = drive(frameGateEnabled = true)
        val direct = drive(frameGateEnabled = false)
        assertEquals(
            listOf(
                listOf("甲"),
                listOf("甲  "),
                listOf("甲  ", "乙"),
                listOf("甲  "),
                listOf("甲     "),
                listOf("甲     ", "丙"),
            ),
            gated,
            "帧序列的具体内容（不是只比两边相等：闸开侧一旦漏掉挂帧这里会先红）",
        )
        assertEquals(gated, direct, "逐帧比对：两闸在每个 pump 点上给出同一份文本")
    }

    /**
     * 撤销上游判据必红：发布判据只认到达版本水位，不认「队列非空」状态代理。
     *
     * 造一个「队列已被排空、但这一版到达从未交出去」的形态：直接 [GraphicsLayer.drainInline]
     * 排空队列，此时 `hasPendingInline()` 为假而 [GraphicsLayer.inlineRevision] 已前进。
     * 若把 [ConsoleFeed.pump] / [ConsoleFeed.hasUnpublishedContent] 退回
     * `hasPendingInline()` 状态代理，这一版会被当成已发布而永久漏掉 —— 前一轮 A2 的反向证明
     * 正是在这种形态上「撤判据仍绿」，本用例把那个漏洞钉死。
     */
    @Test
    fun `撤销水位判据必红 排空队列仍有一版未发布`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("血条")
        val f = ConsoleFeed(s, screen(), layer, metrics)
        f.pump()
        assertFalse(f.hasUnpublishedContent(), "首帧之后没有待发布内容")

        layer.enqueue(InlineGraphic.Space(2))
        val arrived = layer.inlineRevision()
        // 绕过 publish 直接排空队列：状态代理变假，但这一版从未交出去。
        layer.drainInline()
        assertFalse(layer.hasPendingInline(), "队列空了（状态代理为假）")
        assertEquals(arrived, layer.inlineRevision(), "到达版本只由入队推进，排空不动它")

        assertTrue(
            f.hasUnpublishedContent(),
            "排空不等于发布：发布判据必须仍认这一版未交的水位差",
        )
        f.pump()
        assertFalse(f.hasUnpublishedContent(), "交过之后水位收敛")
    }
}
