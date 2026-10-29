package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「动态地图调试屏」重印窗口内枚举选项的口径钉子（2026-10-27 真机报「按钮不断抖动、
 * 数量在 11 / 29 / 32 / 9 之间变」的收口）。
 *
 * ## 屏上发生的事
 *
 * 调试工具 `ERB/SHOP関連/TEST.ERB:981` 的 `@DEBUG_MAP_TOOL` 是个自绘循环
 * （`:1008-1035`）：
 *
 * ```
 * $MAP_LOOP
 * CLEARLINE LINECOUNT - LINESTART      ; 先把上一帧整屏删掉
 * REDRAW 0 / CALL DEBUG_MAP_DRAW(..)   ; 再逐行重印：17 行地图格 + 15 个面板按钮
 * REDRAW PREV_REDRAW
 * $INPUT_LOOP
 * CALL AWAIT_UI_INPUT(LISTEN_CONF, UPDATE_INTERVAL)  ; AWAIT 16 轮询，200ms 一次 tick
 * ```
 *
 * 动画档（`COLOREDOPTION.ERB:13` 的 `ANIMATERECOLOREDMAPS = 1000 / FPS`，语料 FPS=5
 * ⇒ **200 ms 一帧**）走的是 `AWAIT` 轮询，**从不进 `INPUT`** ⇒ 宿主在「无等待方」时
 * 用显示兜底枚举右侧栏（`GameHost.kt` 的 `currentOptions`）。
 *
 * ## 两条口径，必须分清楚
 *
 * - [LineBufferConsoleSink.publishedOptions]：与 `revision` **同一发布点**（让出边界）的
 *   整帧快照 ⇒ 重印窗口内读它，拿到的永远是上一整帧，数字不跟着重印进度变。**生产壳层用这条。**
 * - [LineBufferConsoleSink.displayOptions]：活表读数 ⇒ 数字就是「采样那一刻印出了几枚」，
 *   9 / 11 / 29 / 32 全是它的合法读数。留给 DevTools / `uipreview` 的 `/state/chips`。
 *
 * 修之前壳层用的是后者，于是 200 ms 的重印周期与 250 ms 的扫描周期拍频，玩家看到的就是
 * 那四个数字来回跳。
 */
class MapRedrawOptionWindowTest {

    private fun sink(frameGate: Boolean = true): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = 110, maxLines = 900, frameGateEnabled = frameGate)

    /** 地图格：语料用 `HTML_PRINT` 打整行（`DRAW_COLOREDMAP.ERB:138`），按钮在 `htmlOptions` 里。 */
    private fun mapRow(s: LineBufferConsoleSink, n: Int) {
        s.printHtml("<button value='$n'>格$n</button>", false)
    }

    /** 控制面板的一枚按钮行（`PRINTBUTTON` + 换行 = 一条逻辑行）。 */
    private fun panelRow(s: LineBufferConsoleSink, label: String, value: String) {
        s.printButton(label, value)
        s.newLine()
    }

    /** 面板 15 枚（`TEST.ERB:1083-1155`：3 模式 + 2 地图 + 2 分页 + 2 季节 + 2 日期 + 2 延迟 + 1 时停 + 1 退出）。 */
    private fun panel(s: LineBufferConsoleSink) {
        panelRow(s, "[家(Home)]", "MODE_HOME")
        panelRow(s, "[外出(Odekake)]", "MODE_ODE")
        panelRow(s, "[大地图(Global)]", "MODE_GLO")
        panelRow(s, "[<]", "MAP_PREV")
        panelRow(s, "[>]", "MAP_NEXT")
        panelRow(s, "[<]", "PAGE_PREV")
        panelRow(s, "[>]", "PAGE_NEXT")
        panelRow(s, "[<]", "SEASON_PREV")
        panelRow(s, "[>]", "SEASON_NEXT")
        panelRow(s, "[<]", "DAY_PREV")
        panelRow(s, "[>]", "DAY_NEXT")
        panelRow(s, "[-10]", "DELAY_SUB")
        panelRow(s, "[+10]", "DELAY_ADD")
        panelRow(s, "[关]", "TOGGLE_TIME")
        panelRow(s, "[999] 退出测试", "EXIT")
    }

    /** 整帧：17 格地图 + 15 枚面板 = 32 条逻辑行 / 32 枚按钮。 */
    private fun fullFrame(s: LineBufferConsoleSink) {
        for (i in 1..17) mapRow(s, i)
        panel(s)
    }

    /**
     * 帧口径：脚本重印期间（`CLEARLINE` 之后、下一帧印完之前）读到的**始终是上一整帧**，
     * 与采样落在第几行无关 —— 这就是「右侧栏不抖」的判据。
     */
    @Test
    fun `重印窗口内帧口径快照恒定_与采样落点无关`() {
        val s = sink()
        fullFrame(s)
        s.publishFrame() // 让出边界（AWAIT）：整帧 32 上屏
        assertEquals(32, s.publishedOptions().size, "整帧应是 17 格地图 + 15 枚面板")

        s.holdFrame() // AWAIT 返回后重新冻结

        // 脚本进入下一帧：CLEARLINE 整屏（TEST.ERB:1009），然后在闸关状态下逐行重印。
        s.deleteLine(32)
        assertEquals(32, s.publishedOptions().size, "刚清完还没重印：栏里仍是上一整帧，不是 0")

        for (i in 1..9) mapRow(s, i)
        assertEquals(32, s.publishedOptions().size, "印到第 9 格：仍是上一整帧，不是 9")

        for (i in 10..11) mapRow(s, i)
        assertEquals(32, s.publishedOptions().size, "印到第 11 格：仍是上一整帧，不是 11")

        // 本帧印完，走到下一个让出边界（REDRAW 1 / AWAIT）才换代。
        s.deleteLine(11)
        fullFrame(s)
        s.publishFrame()
        assertEquals(32, s.publishedOptions().size, "新一帧发布后仍是 32（这一帧是完整的）")
    }

    /**
     * 活表口径没被改：DevTools / `uipreview` 要的是「引擎此刻认为有什么」，
     * 所以它必须仍然跟着重印进度走（9 / 11 / 29 / 32 都是它的合法读数）。
     */
    @Test
    fun `活表读数仍跟着重印进度走_DevTools 口径不变`() {
        val s = sink()
        fullFrame(s)
        s.publishFrame()
        assertEquals(32, s.displayOptions().size)

        s.holdFrame()
        s.deleteLine(32)
        assertTrue(s.displayOptions().isEmpty(), "活表：清完还没重印就是 0 枚")

        for (i in 1..9) mapRow(s, i)
        assertEquals(9, s.displayOptions().size, "活表：印到第 9 格 ⇒ 9")

        for (i in 10..11) mapRow(s, i)
        assertEquals(11, s.displayOptions().size, "活表：印到第 11 格 ⇒ 11")

        for (i in 12..17) mapRow(s, i)
        for (k in 0 until 12) panelRow(s, "[<]", "P$k")
        assertEquals(29, s.displayOptions().size, "活表：地图印完、面板印到第 12 枚 ⇒ 29")

        for (k in 12 until 15) panelRow(s, "[<]", "P$k")
        assertEquals(32, s.displayOptions().size, "活表：面板印完 ⇒ 32")
    }

    /**
     * 判别条件（真机可验）：走 `INPUT` 的页面（动画关 / 时间停止）在让出边界冻结一份快照，
     * 同一重印过程里它一动不动 —— 所以「动画开 ⇒ 抖、动画关 ⇒ 不抖」。
     */
    @Test
    fun `走 INPUT 的页面在帧边界冻结_同一重印过程不抖`() {
        val s = sink()
        fullFrame(s)
        s.publishFrame()
        s.noteInputWait(
            InputRequest(type = InputRequest.InputType.STR, form = InputRequest.RequestForm.VALUE),
        )
        val frozen = s.currentOptions().size
        assertEquals(32, frozen, "冻结快照是整帧的")

        s.holdFrame()
        s.deleteLine(32)
        for (i in 1..9) mapRow(s, i)
        assertEquals(frozen, s.currentOptions().size, "冻结快照不受重印进度影响")
        assertEquals(frozen, s.publishedOptions().size, "帧口径同样不受影响")
    }

    /**
     * 「一屏一帧」关掉时退回活表：那时连画布都是逐条发布的，再给栏加一层帧口径只会让
     * 两者对不上（保留既有逃生阀行为）。
     */
    @Test
    fun `一屏一帧关掉时帧口径退回活表`() {
        val s = sink(frameGate = false)
        fullFrame(s)
        s.publishFrame()
        assertEquals(32, s.publishedOptions().size)

        s.deleteLine(32)
        for (i in 1..9) mapRow(s, i)
        assertEquals(9, s.publishedOptions().size, "闸关掉 ⇒ publishedOptions 就是活表读数")
    }
}
