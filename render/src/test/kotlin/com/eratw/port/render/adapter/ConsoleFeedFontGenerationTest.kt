package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 字号换代与脚本尺寸读数（AUD-r2-fontsize）—— **判死锁样**，不改生产码。
 *
 * ## 判死结论（BOARD：updateLayout 重建 flow 时 scriptFontPixelSize 不跟随）
 *
 * 换代路径**根本不经过旧 ConsoleFeed 实例**，构造期 `val scriptFontPixelSize`
 * （ConsoleFeed.kt:75）因此没有失真窗口：
 *
 * 1. 生产唯一调用 `updateLayout` 的是 `CanvasConsoleView.syncFeedLayout`
 *    （CanvasConsoleView.kt:1629）；feed 在挂期间 `rebuildScreen` 走冻结分支——
 *    只重算可见行数与竖带参数、**不**调 `applyMetrics`（CanvasConsoleView.kt:1541-1552），
 *    metrics.fontPixelSize 与构造期恒等 ⇒ 重建展平器喂进的是同代基准
 *    （ConsoleFeed.kt:127-130），读数同代。
 * 2. 字号换代只发生在会话边界：`FONT SIZE` / `LINE HEIGHT` 已判 RESTART 键
 *    （main 上 fb23a69c：SettingsModel.kt:91-97 的烘死点论证、:119-130 的 HOT_KEYS 与 of()；
 *    「显示大小」档同理，e3781dd7 的 `restartGame { recreate() }`，MainActivity.kt:765-770/:801）。
 *    重开路径：`GameHost.start` 先 `applyBootFontMetrics` 灌新字号（GameHost.kt:704，
 *    applySourceFontMetrics 要求在会话构造前调，CanvasConsoleView.kt:176-195），
 *    新 `GameSession` 按新生效配置自报基准（GameSession.kt:546），再
 *    `attachConsoleFeed` **新建** ConsoleFeed 双参同换（GameHost.kt:2899-2916）。
 *    旧实例不参与：换代 = 新构造，「读数同代」由构造恒等式保证。
 * 3. `backToTitle` 复用**同一**会话重挂（GameHost.kt:3157）——基准不变，无换代可言。
 *
 * 消费方判性（BOARD 点名的三路，逐条给证据）：
 * - **px 形态**（img height/width/ypos 与 shape 的 px 支）：唯一吃
 *   `scriptFontPixelSize` 的读数（HtmlRunFlatten.kt:738 → CellMetrics.kt:157-175）。
 *   换代不经旧实例 ⇒ 无需热更（本文件三例锁两种可达形态 + 不可达形态的现行为）。
 * - **百分比形态（含 ypos 百分比）与行高**：读 `metrics.fontPixelSize`
 *   （HtmlRunFlatten.kt:595-615/:644-673/:722-727），metrics 是 updateLayout 的参数、
 *   每次重建整体换新（ConsoleFeed.kt:127,130）；竖带合并的行高在 publish 现读
 *   `metrics.lineHeight`（ConsoleFeed.kt:277-278）⇒ 天然随代，无需额外热更。
 * - **取色**：ConsoleFeed 与 HtmlRunFlatten 消费链内没有任何依赖字号的取色读数
 *   （颜色只来自 HTML 标签与 run 快照，HtmlRunFlatten.kt:206-210/:467-475；
 *   rect 渐变采样 colorAt 纯几何，HtmlRunFlatten.kt:888-895）。BOARD 所指是 view 侧
 *   shapeShiftPx 的「绘制与取色形状原点」（CanvasConsoleView.kt:316-321），它在
 *   applyMetrics 里随字号换代、不经过 ConsoleFeed，且不在本任务文件面。
 *
 * 期望值全部手算（scriptPixels 的先乘后除整除式，CellMetrics.kt:165）：
 * `设备px = 脚本px × 设备字号 ÷ 基准字号`；百分比 = `设备字号 × num ÷ 100`。
 */
class ConsoleFeedFontGenerationTest {

    /** 语句侧 HTML 直印一行（htmlOption=false），不加 src 让宽高同值、读数只剩高度一条变量。 */
    private fun sinkWith(vararg htmls: String): LineBufferConsoleSink {
        val s = LineBufferConsoleSink(clientWidth = 80)
        for (h in htmls) s.printHtml(h, false)
        return s
    }

    private fun screen() = ConsoleScreen(clientWidth = 80, maxLog = 100, heightLines = 10)

    /** 屏上已提交的图形段（按行序）；本文件每行恰好一个图形段。 */
    private fun graphicsOf(sc: ConsoleScreen): List<InlineGraphic> =
        sc.list.render().filter { it.graphic != null }.map { it.graphic!! }

    /**
     * 换代的生产形态 = 重开新构造：两代 feed 各自读数只由**本代**的基准与字号决定。
     *
     * 第一代取语料恒等对（基准 18 = emuera.config 的 FONT SIZE，设备字号 30）：
     * `height='180px'` 与 `height='1000'`（脚本按 180×100/18 算出的百分比形态，
     * 口径见 CellMetrics.scriptPixels 的 KDoc 与 HtmlRunFlatten.emitImage）同落 300px——
     * px 支与百分比支同尺。第二代（改字号重开：基准 36、设备 63）手算
     * px = 180×63÷36 = 315、百分比 = 63×1000÷100 = 630：读数跟着新代走，
     * 而不是停在旧代的 300。
     */
    @Test
    fun `换代走重开新构造：读数按新代基准与字号`() {
        // 第一代：基准 18、设备 30。px 180×30÷18 = 300；百分比 30×1000÷100 = 300。
        val gen1 = ConsoleFeed(
            sinkWith("<img height='180px'>", "<img height='1000'>"),
            screen(),
            GraphicsLayer(),
            CellMetrics(30),
            null,
            scriptFontPixelSize = 18,
        )
        gen1.pump()
        val first = graphicsOf(gen1.screen)
        assertEquals(2, first.size, "前置：两行各一个图形段")
        assertEquals(300, (first[0] as InlineGraphic.Image).heightPx, "px 支 180×30÷18 = 300")
        assertEquals(300, (first[1] as InlineGraphic.Image).heightPx, "百分比支 30×1000÷100 = 300，与 px 支同尺")

        // 第二代：改字号重开会话 = 新 sink/新 screen/新 feed，基准与设备字号双双换代。
        val gen2 = ConsoleFeed(
            sinkWith("<img height='180px'>", "<img height='1000'>"),
            screen(),
            GraphicsLayer(),
            CellMetrics(63),
            null,
            scriptFontPixelSize = 36,
        )
        gen2.pump()
        val second = graphicsOf(gen2.screen)
        assertEquals(2, second.size, "前置：两行各一个图形段")
        assertEquals(315, (second[0] as InlineGraphic.Image).heightPx, "px 支 180×63÷36 = 315（新代）")
        assertEquals(630, (second[1] as InlineGraphic.Image).heightPx, "百分比支 63×1000÷100 = 630（新代）")
    }

    /**
     * 生产唯一可达的 updateLayout 路径（可见高变化 ⇒ 竖带参数变）：
     * metrics 同代，重建展平器后读数**不漂**。
     *
     * 这条钉住 ConsoleFeed.kt:73 的 KDoc 断言「不随 updateLayout 的字号换代丢失」：
     * updateLayout 用同一 scriptFontPixelSize 重建展平器（ConsoleFeed.kt:127-130），
     * px 读数在重建前后恒等。行尾带一个文字段让该行不参与竖带合并
     * （runs 不唯一即跳过，ConsoleFeed.kt:270-271），图形段读数不被块缩放污染。
     */
    @Test
    fun `会话期 updateLayout 重建展平器：同代读数不漂`() {
        val s = sinkWith("<img height='180px'>尾")
        val sc = screen()
        val f = ConsoleFeed(s, sc, GraphicsLayer(), CellMetrics(30), null, scriptFontPixelSize = 18)
        f.pump()
        assertEquals(300, (graphicsOf(sc).single() as InlineGraphic.Image).heightPx, "首展平 180×30÷18 = 300")

        // 可见高变化的生产形态：metrics 原样、竖带块高变了 ⇒ 重交为真，但基准不动。
        assertTrue(
            f.updateLayout(CellMetrics(30), 120, 60),
            "竖带参数变了必须重交（updateLayout 返回真）",
        )
        assertEquals(300, (graphicsOf(sc).single() as InlineGraphic.Image).heightPx, "重建展平器后仍 300：基准未被丢")

        // 参数没变时不重交（ConsoleFeed.kt:119-126 的守卫）。
        assertTrue(!f.updateLayout(CellMetrics(30), 120, 60), "参数没变 ⇒ 返回假")
    }

    /**
     * 混代复用锁样（**生产不可达路径**，判死注记）：假如未来有人把「换字号不重开」
     * 重新接回（比如复活会话期 page-fit），updateLayout 换 metrics 不换基准时的现行为是——
     * 百分比支跟新 metrics（随代），px 支按**构造期**基准折算（停在旧代）。
     * 手算：构造（设备 30、基准 20）px = 180×30÷20 = 270；updateLayout 到设备 63 后
     * px = 180×63÷20 = 567、百分比 = 63×1000÷100 = 630。哪天这条路径真的可达了，
     * 「该喂什么基准」必须先过一遍判性再动本用例。
     */
    @Test
    fun `混代复用锁样：百分比随新代、px 按构造期基准`() {
        val s = sinkWith("<img height='180px'>", "<img height='1000'>")
        val sc = screen()
        val f = ConsoleFeed(s, sc, GraphicsLayer(), CellMetrics(30), null, scriptFontPixelSize = 20)
        f.pump()
        val before = graphicsOf(sc)
        assertEquals(270, (before[0] as InlineGraphic.Image).heightPx, "px 支 180×30÷20 = 270")
        assertEquals(300, (before[1] as InlineGraphic.Image).heightPx, "百分比支 30×1000÷100 = 300")

        assertTrue(f.updateLayout(CellMetrics(63), 0, 0), "字号变了必须重交（混代形态的前提）")
        val after = graphicsOf(sc)
        assertEquals(567, (after[0] as InlineGraphic.Image).heightPx, "px 支按构造期基准：180×63÷20 = 567")
        assertEquals(630, (after[1] as InlineGraphic.Image).heightPx, "百分比支随新 metrics：63×1000÷100 = 630")
    }
}
