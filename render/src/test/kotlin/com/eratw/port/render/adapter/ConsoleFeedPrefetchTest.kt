package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ConsoleFeed.prefetch`：HTML 图（立绘主路径）的后台预取请求面。
 *
 * 判据只有三条：`<img>` 段在发布后把**资源名**交给回调；同一资源跨次 [ConsoleFeed.pump]
 * 只请求一次（`prefetchSeen`）；`prefetch = null`（缺省）时屏上形态与接入前逐值相同。
 *
 * 本类不测解码 —— 回调在这里是记录器，生产侧才是 `SpriteBitmapSource.prefetchAsync`
 * （Android 桩环境跑不了解码本体，见交付报告）。
 */
class ConsoleFeedPrefetchTest {

    private val metrics = CellMetrics(18)

    private fun sink(width: Int = 20) = LineBufferConsoleSink(clientWidth = width)
    private fun screen(width: Int = 20, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    private fun feed(
        sink: LineBufferConsoleSink,
        screen: ConsoleScreen,
        layer: GraphicsLayer?,
        prefetch: ((String) -> Unit)?,
    ) = ConsoleFeed(sink, screen, layer, metrics, null, prefetch = prefetch)

    @Test
    fun `挂 layer 与预取记录器时 img 段在 publish 后回调资源名`() {
        val seen = ArrayList<String>()
        val s = sink()
        s.printHtml("<img src='a'>", false)
        val f = feed(s, screen(), GraphicsLayer(), prefetch = { seen.add(it) })
        f.pump()
        assertEquals(listOf("a"), seen)
    }

    @Test
    fun `同一资源第二次 publish 不重复回调 新资源照常回调`() {
        val seen = ArrayList<String>()
        val s = sink()
        s.printHtml("<img src='a'>", false)
        val f = feed(s, screen(), GraphicsLayer(), prefetch = { seen.add(it) })
        f.pump()
        assertEquals(listOf("a"), seen)

        // 第二次 publish：老 `<img>` 仍在日志里（每帧重展平），去重表必须挡掉重复请求。
        s.printLine("下一行")
        f.pump()
        assertEquals(listOf("a"), seen, "同一资源只请求一次预取")

        // 新资源照常入队。
        s.printHtml("<img src='b'>", false)
        f.pump()
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `prefetch 缺省时屏上形态与接入前相同`() {
        val s = sink()
        s.printHtml("<img src='a'>", false)
        val f = ConsoleFeed(s, screen(), GraphicsLayer(), metrics)
        f.pump()
        // 不是「没预取所以不画」：展平与占宽完全走原路（缺省高 = FontSize 18 ⇒ 2 格空白）。
        assertEquals(listOf("  "), f.screen.snapshotText())
    }

    @Test
    fun `没挂图形层时 img 仍是原文且不预取`() {
        val seen = ArrayList<String>()
        val s = sink(width = 80)
        s.printHtml("<img src='a'>", false)
        val f = feed(s, screen(width = 80), null, prefetch = { seen.add(it) })
        f.pump()
        assertEquals(emptyList<String>(), seen, "没展平就没有图片段，不该请求预取")
        assertEquals(listOf("<img src='a'>"), f.screen.snapshotText())
    }
}
