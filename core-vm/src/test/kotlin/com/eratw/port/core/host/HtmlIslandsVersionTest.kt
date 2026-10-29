package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浮岛水位（[LineBufferConsoleSink.htmlIslandsVersion]）—— 渲染侧「每帧搬一次浮岛」的止血点。
 *
 * ## 现场（2026-10-29）
 *
 * `ConsoleFeed.pump()` 原本每帧无条件 `sink.htmlIslands()` + `screen.updateIslands(...)`：
 * `htmlIslands` 是**加锁 + 整表拷贝**（`synchronized(displayLines) { islands.toList() }`），
 * 而浮岛内容只在 `HTML_PRINT_ISLAND` / `HTML_PRINT_ISLAND_CLEAR` 时变。外出/地图屏
 * （`ERB/魔改内容/qol/qol_MAP.ERB`）用浮岛画面板，几十条字符串 × 每帧拷贝 = UI 线程的
 * 持续分配与锁竞争，正是「外出界面闪动」里最容易被忽略的一层开销。
 *
 * 口径（与 `revision` 同一套「写侧抬水位、读侧无锁比」）：**内容真的变了才 +1**；
 * 空串被 [LineBufferConsoleSink.addHtmlIsland] 直接丢弃，因此不抬水位。
 */
class HtmlIslandsVersionTest {

    private fun sink() = LineBufferConsoleSink(clientWidth = 40)

    @Test
    fun `浮岛内容变化时水位递增`() {
        val s = sink()
        val v0 = s.htmlIslandsVersion()
        s.addHtmlIsland("<div><button value=1>x</button></div>")
        val v1 = s.htmlIslandsVersion()
        assertTrue(v1 > v0, "加一条浮岛必须抬水位（$v0 → $v1）")
        s.addHtmlIsland("<div><button value=2>y</button></div>")
        assertTrue(s.htmlIslandsVersion() > v1, "再一条必须再抬")
        assertEquals(2, s.htmlIslands().size, "内容与水位同源")
    }

    @Test
    fun `清空浮岛抬水位`() {
        val s = sink()
        s.addHtmlIsland("<div>a</div>")
        val v = s.htmlIslandsVersion()
        s.clearHtmlIslands()
        assertTrue(s.htmlIslandsVersion() > v, "清空也是一次内容变化")
        assertTrue(s.htmlIslands().isEmpty())
    }

    @Test
    fun `空串不抬水位`() {
        val s = sink()
        val v = s.htmlIslandsVersion()
        s.addHtmlIsland("")
        assertEquals(v, s.htmlIslandsVersion(), "空串被丢弃（addHtmlIsland 的既有早退），不算内容变化")
    }
}
