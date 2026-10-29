package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.view.ConsoleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整屏重印期间不许把上翻的读者拽回底部（2026-10-29 真机反馈）。
 *
 * ## 现场
 *
 * 玩家开「动态地图」后进地图屏：`ERB/魔改内容/qol/qol_MAP.ERB:34-44` 的 `$MAP_LOOP`
 * 每 `ANIMATERECOLOREDMAPS`（语料 `emuera.config:28` 的 FPS:5 ⇒ 200 ms）把整屏
 * `CLEARLINE` + 重印一次。玩家向上滑想看清地图顶部，屏却每 200 ms 跳回底部并跟着抖一下。
 *
 * ## 这一路走的正是生产三件套
 *
 * 真 [LineBufferConsoleSink]（`:core-vm` 的生产 sink）→ [ConsoleFeed]（发布口）→
 * [ConsoleScreen] → `Viewport`。夹具按地图屏的形态排：一屏装不下的地图块 + 页尾会摆动的
 * 指令区（`UI_MESSAGE` 出现与消失就是这种摆），每拍收在同一个让出边界上 ⇒ 与真机一样
 * **一拍只发布一帧**。
 *
 * ## 钉子
 *
 * 上翻之后，无论重印把显示行数改多改少，`offsetFromEnd` 都不许被清零；页尾长度不变的那些拍，
 * 屏上那几行文本必须逐拍一模一样。贴底态（偏移 0）照常自动跟随最新行。
 */
class MapRedrawScrollAnchorTest {

    private val clientWidth = 20
    private val heightLines = 6
    private val mapRows = 18

    private fun feed(): ConsoleFeed = ConsoleFeed(
        LineBufferConsoleSink(clientWidth = clientWidth, maxLines = 400),
        ConsoleScreen(clientWidth = clientWidth, maxLog = 400, heightLines = heightLines),
    )

    /**
     * 印一拍地图屏：`rows` 行地图 + `uiRows` 行页尾区，收在让出边界上。
     * 开头的 `deleteLine` 就是 `$MAP_LOOP` 的 `CLEARLINE LINECOUNT - LINESTART`。
     */
    private fun tick(feed: ConsoleFeed, previousLines: Int, rows: Int, uiRows: Int): Int {
        val sink = feed.sink
        if (previousLines > 0) sink.deleteLine(previousLines)
        repeat(rows) { i -> sink.printLine("地图行%02d".format(i)) }
        repeat(uiRows) { i -> sink.printLine("指令区%d".format(i)) }
        sink.publishFrame()
        feed.pump()
        return rows + uiRows
    }

    /** 页尾一行不多一行不少的那些拍：锚点与屏上内容都该纹丝不动。 */
    @Test
    fun `整屏重印不动上翻的锚点与屏上内容`() {
        val feed = feed()
        val viewport = feed.screen.viewport
        var lines = tick(feed, previousLines = 0, rows = mapRows, uiRows = 3)
        assertTrue(viewport.atBottom, "开局贴底：底部就是最新行")

        viewport.scrollUp(4)
        feed.screen.refresh()
        val visibleBefore = feed.screen.snapshotText()
        assertEquals(4, viewport.offsetFromEnd)

        repeat(8) { i ->
            lines = tick(feed, previousLines = lines, rows = mapRows, uiRows = 3)
            assertEquals(4, viewport.offsetFromEnd, "第 ${i + 1} 拍把玩家拽回了底部")
            assertEquals(visibleBefore, feed.screen.snapshotText(), "第 ${i + 1} 拍屏上内容变了")
        }
    }

    /** 页尾行数摆动（UI_MESSAGE 出现/消失的真实形态）：偏移守恒，但**不许清零**。 */
    @Test
    fun `页尾行数摆动时锚点只守恒不归零`() {
        val feed = feed()
        val viewport = feed.screen.viewport
        var lines = tick(feed, previousLines = 0, rows = mapRows, uiRows = 3)
        viewport.scrollUp(4)
        feed.screen.refresh()

        // 21 → 20 → 19 → 20 → 21：既有缩短也有增长，缩短那一拍正是原 bug 的触发点。
        val wobbling = listOf(2, 1, 2, 3, 2)
        wobbling.forEachIndexed { i, uiRows ->
            val shrinking = mapRows + uiRows < lines
            lines = tick(feed, previousLines = lines, rows = mapRows, uiRows = uiRows)
            assertEquals(4, viewport.offsetFromEnd, "第 ${i + 1} 拍（$lines 行，${if (shrinking) "缩短" else "增长"}）锚点被改了")
        }
    }

    /** 内容缩到不足一屏时，`maxOffset` 自己归零 ⇒ 回到唯一合法的贴底态。 */
    @Test
    fun `内容缩到不足一屏时回到贴底`() {
        val feed = feed()
        val viewport = feed.screen.viewport
        var lines = tick(feed, previousLines = 0, rows = mapRows, uiRows = 3)
        viewport.scrollUp(10)
        feed.screen.refresh()
        assertEquals(10, viewport.offsetFromEnd)

        lines = tick(feed, previousLines = lines, rows = 3, uiRows = 0)
        assertEquals(0, viewport.offsetFromEnd, "只剩 $lines 行 < $heightLines 屏 ⇒ 必须贴底")
        assertTrue(viewport.atBottom)
    }
}
