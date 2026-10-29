package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConsoleFeed] 挂上 [GraphicsLayer] 之后的两条职责（M10-GFX 第 3 步 ③）。
 *
 * 1. **HTML 展平**：`HTML_PRINT` 段里的 `<img>` / `<shape>` 在提交快照那一步变成图形段
 *    （语料实测 `<img` 52 处 / `<shape` 58 处，是本作立绘的唯一来路；`PRINT_IMG` 语句 0 处）。
 * 2. **内联段汇合**：`PRINT_IMG` / `PRINT_RECT` / `PRINT_SPACE` 由语句层经 sink 投进
 *    [GraphicsLayer]，本桥在同一个提交点把它们接到**最后一条显示行**尾部
 *    （原版打印游标恒在最后一条行末，spec: `EmueraConsole.cs:2948-2977` 的并入分支）。
 *
 * 两条都必须**只在挂了 layer 时发生**：`graphics = null` 的那几条用例就是接入前的现状，
 * 反向防误接（E2E / 演示屏走的都是那条）。
 *
 * 宽度 20 半角格、字号 18px（半角步进 9.36px，见 [CellMetrics.cellPixelWidth]），
 * 图形段占宽按 `ceil(px / 9.36)` 手算。
 */
class ConsoleFeedGraphicsTest {

    private val metrics = CellMetrics(18)

    /** 只查表不画像素的假取图口：资源名 → 打包尺寸（`destSize` 用）。 */
    private class FakeSizes(vararg pairs: Pair<String, Pair<Int, Int>>) : HtmlRunFlatten.ImageSizeSource {
        private val map = pairs.toMap()
        override fun destSize(resource: String): Int? =
            map[resource]?.let { (it.first shl 16) or it.second }
    }

    /** 绘制侧取图口：这里只用来证明 `sizeSource` 与 `frames` 可以同一个对象（生产侧就是同一个）。 */
    private class FakeFrames(private val sizes: Map<String, IntArray>) :
        ImageFrameSource, HtmlRunFlatten.ImageSizeSource {
        val asked = ArrayList<String>()
        override fun frame(resource: String): ImageFrame? {
            asked.add(resource)
            val s = sizes[resource] ?: return null
            return ImageFrame(handle = "bitmap:$resource", srcW = s[0], srcH = s[1], destW = s[0], destH = s[1])
        }

        override fun destSize(resource: String): Int? =
            sizes[resource]?.let { (it[0] shl 16) or it[1] }
    }

    private fun feed(
        sink: LineBufferConsoleSink,
        screen: ConsoleScreen,
        layer: GraphicsLayer?,
        sizes: HtmlRunFlatten.ImageSizeSource? = null,
    ) = ConsoleFeed(sink, screen, layer, metrics, sizes)

    private fun sink(width: Int = 20) = LineBufferConsoleSink(clientWidth = width)
    private fun screen(width: Int = 20, heightLines: Int = 5) =
        ConsoleScreen(clientWidth = width, maxLog = 100, heightLines = heightLines)

    // ==================================================================
    // 1) 没挂图形层 = 接入前逐项相同
    // ==================================================================

    @Test
    fun `未挂图形层时 HTML 段仍是原文`() {
        // 夹具宽度必须容得下这条 40 半角格的原文：折行是 `LineFolder` 的既有职责，
        // 与图形层无关，但 20 格会把原文劈成两条行、把这条「反向防误接」判据淹在折行噪声里。
        // 判据本身不动：传 null 时 `ConsoleFeed` 一个字节都不该动（原文逐字符相等）。
        val s = sink(width = 80)
        s.printHtml("<img src='a' width='60px' height='30px'>", false)
        val f = feed(s, screen(width = 80), null)
        f.pump()
        // 原文一条字符都不少（这正是真机上「屏上只有 HTML 源码」的那条现状）
        assertEquals(listOf("<img src='a' width='60px' height='30px'>"), f.screen.snapshotText())
        assertNull(f.screen.list.render().firstOrNull()?.graphic)
    }

    @Test
    fun `未挂图形层时内联队列里的段被丢弃`() {
        val s = sink()
        val layer = GraphicsLayer()
        layer.enqueue(InlineGraphic.Image("a", 60, 30, 0, 7))
        // 故意**不**把 layer 交给 feed：队列不归任何人排空
        val f = feed(s, screen(), null)
        s.printLine("只有文字")
        f.pump()
        assertEquals(listOf("只有文字"), f.screen.snapshotText())
        assertTrue(layer.hasPendingInline(), "没接线的图形层不该被别人排空")
    }

    // ==================================================================
    // 2) HTML 展平
    // ==================================================================

    /** 标题图那条（`ERB/TITLE.ERB:59`）：没有尺寸表时按「一行高见方」占 2 格并带图形负载。 */
    @Test
    fun `挂上图形层后 html 的 img 变成图形段`() {
        val s = sink()
        s.printHtml("<p align='center'><img src='TW_title001'>", false)
        val f = feed(s, screen(), GraphicsLayer())
        val rects = f.pump()
        assertEquals(1, rects.size, "首帧整屏脏")
        val cmd = f.screen.list.render().single()
        assertNotNull(cmd.graphic, "该行必须带图形负载")
        assertEquals(RunKind.IMAGE, cmd.kind)
        assertEquals("TW_title001", (cmd.graphic as InlineGraphic.Image).resource)
        assertEquals(18, (cmd.graphic as InlineGraphic.Image).heightPx, "height 缺省 = FontSize（ConsoleImagePart.cs:66）")
        assertEquals(2, (cmd.graphic as InlineGraphic.Image).widthCells, "ceil(18 / 9.36) = 2")
        // 直印 HTML 行的 `<p align='center'>` 生效：indent = 20/2 - 2/2 = 9
        // （ConsoleDisplayLine.cs:80 的 CENTER 分支；HtmlManager.cs:702-710 的一次 SetAlignment
        // 吃掉 addDisplayLine 的控制台对齐，ConsoleDisplayLine.cs:52-56）。
        assertEquals(9, cmd.colStart)
        assertEquals(11, cmd.colEnd)
        assertEquals(emptyList<Any>(), cmd.glyphs, "图形段没有字形可铺")
        assertEquals("  ", f.screen.snapshotText().single(), "占宽是等量空格")
    }

    /** `<img>` 之外还有文本时：一行内文本段在前、图形段在后，列区间连续。 */
    @Test
    fun `展平后文本段与图形段共占同一行`() {
        val s = sink()
        s.printHtml("甲<img src='a' width='20px' height='20px'>乙", false)
        val f = feed(s, screen(), GraphicsLayer())
        f.pump()
        val cmds = f.screen.list.render()
        // 甲/乙 各 2 格（全角），img 20px ⇒ ceil(20 / 9.36) = 3 格
        assertEquals(listOf(0 to 2, 2 to 5, 5 to 7), cmds.map { it.colStart to it.colEnd })
        assertEquals(listOf(RunKind.TEXT, RunKind.IMAGE, RunKind.TEXT), cmds.map { it.kind })
        assertEquals(listOf(null, "a", null), cmds.map { (it.graphic as? InlineGraphic.Image)?.resource })
    }

    /**
     * `<shape type='rect'>` 走 SHAPE kind，绘制侧据此画色块。
     *
     * 夹具宽度取 60 格而不是默认的 20：本段 440px ⇒ `ceil(440 / 9.36) = 48` 格，
     * 20 格装不下会被 [com.eratw.port.render.grid.LineFolder] 劈成三条显示行
     * （折行按 run 文本的格数走，与 [InlineGraphic.widthCells] 无关），
     * 那时 `.single()` 拿到的不再是「这一段」而是「折出来的三段」，判据就废了。
     * 折行侧对图形段的正确口径是**整段不拆**（原版 `ConsoleImagePart`/`ConsoleShapePart`
     * 的 `CanDivide` 为 false，spec: `PrintStringBuffer.cs:425-441` 跳过不可拆部件），
     * 那条归 grid 路、写进交付报告，不在本用例里顺手放宽成 `.first()`。
     */
    @Test
    fun `展平后 shape 段带 Rect 负载`() {
        val s = sink(width = 60)
        s.printHtml("<shape type='rect' param='0,1px,440px,2px' color='#FF8800'>", false)
        val f = feed(s, screen(width = 60), GraphicsLayer())
        f.pump()
        val cmd = f.screen.list.render().single()
        assertEquals(RunKind.SHAPE, cmd.kind)
        val r = cmd.graphic as InlineGraphic.Rect
        assertEquals(440, r.widthPx)
        assertEquals(48, r.widthCells)
        // 颜色必须是**不透明** ARGB：`<shape>` 的 color 是 RGB 域，展平侧补 0xFF000000；
        // 否则 drawRect 拿到 alpha=0 会画成全透明（2026-10-23 云真机「少女祈祷中」彩条消失的根因）。
        assertEquals(0xFF_FF8800.toInt(), r.colorArgb)
        assertEquals(48, cmd.colEnd, "段宽 48 格小于 clientWidth = 60 ⇒ 不被裁剪；colStart = 0")
        assertEquals(0, cmd.colStart)
    }

    /**
     * HTML 的 `<img>` 是 **sink 行文本的一部分**，每次 `publish` 都重新展平 ⇒ 后续控制台更新
     * 不会把它抹掉（审计项 5 的「HTML `<img>` 只贴本帧」是误报；受影响的是 `GraphicsLayer`
     * 内联队列那条路 —— 它由 `ConsoleFeed.attachInline` 的锚点表持久化，GFX-04，见下面的
     * 「内联图形持久化」节）。
     */
    @Test
    fun `HTML 的 img 在后续控制台更新后仍留在日志里`() {
        val s = sink(width = 80)
        s.printHtml("<img src='a' width='60px' height='30px'>", false)
        val f = feed(s, screen(width = 80), GraphicsLayer())
        f.pump()
        val before = f.screen.list.render().single()
        assertEquals(RunKind.IMAGE, before.kind)
        s.printLine("下一行")
        f.pump()
        val after = f.screen.list.render()
        assertEquals(listOf(RunKind.IMAGE, RunKind.TEXT), after.map { it.kind })
        assertEquals("a", (after[0].graphic as InlineGraphic.Image).resource, "第二帧仍在第 1 行")
        assertEquals(before.colStart to before.colEnd, after[0].colStart to after[0].colEnd, "列区间不漂移")
    }

    /** 重复 pump 不重展平、不产脏区（幂等；对应 EmueraConsole.cs:1758 的早退）。 */
    @Test
    fun `同一 revision 重复 pump 脏区为空`() {
        val s = sink()
        s.printHtml("<img src='a' width='60px'>", false)
        val f = feed(s, screen(), GraphicsLayer())
        assertTrue(f.pump().isNotEmpty())
        assertTrue(f.pump().isEmpty())
        assertTrue(f.pump().isEmpty())
    }

    /**
     * 全标签行（`<br>` / 只有 shape 的行）必须**留下一段占位**：
     * 若这一行从显示列表里**整体消失**，脏区就覆盖不到它，上一帧的字会留在屏上（残影）。
     * 这条钉 flatten() 的 `out.isEmpty()` 分支。
     *
     * 判据的单位是「行」而不是「命令」：[com.eratw.port.render.displaylist.DisplayList.submit]
     * 标脏看的是 [com.eratw.port.render.DisplayRow] 的**结构相等**（行在列表里就标脏），
     * 一条命令都没有的行照样进脏区（无可画内容时 `bandOf` 回落到零宽、左沿 0，
     * spec: `EmueraConsole.cs:1942-1949` 画完写水位、:1758 水位早退，逐行比对由
     * `displayLineList` 给出 :238）。空文本段的 `Grid.cells` = 0，
     * `prepare` 对零格段不产命令（既有口径，空白行处处如此）—— 所以这里断言
     * 「行活着 + 行号进了脏区」，而不是「命令数 = 2」。
     */
    @Test
    fun `全标签行仍产出空段以便脏矩形覆盖`() {
        val s = sink()
        s.printLine("上一行有字")
        s.printHtml("<br>", false)
        val f = feed(s, screen(), GraphicsLayer())
        val rects = f.pump()
        assertEquals(listOf("上一行有字", ""), f.screen.snapshotText())
        // 该行留在保留列表里（第 2 行），所以它一定在脏区判定里被看见
        assertEquals(2, f.screen.list.rowCount, "空占位段必须把这一行留在保留列表里")
        assertTrue(
            rects.any { it.rowStart <= 1 && it.rowEnd > 1 },
            "第 2 行（空占位行）必须落进脏区，否则残影：$rects",
        )
    }

    // ==================================================================
    // 2.5) 直印 HTML 的行级对齐（HtmlManager.cs:702-710 → ConsoleDisplayLine.cs:50-91）
    // ==================================================================

    /** `<p align='right'>` 经 pump 落到列位移（`DrawableWidth - num`，ConsoleDisplayLine.cs:83）。 */
    @Test
    fun `直印 html 的 p align 经 pump 落到列位移`() {
        val s = sink()
        s.printHtml("<p align='right'>ab", false)
        val f = feed(s, screen(), GraphicsLayer())
        f.pump()
        val cmd = f.screen.list.render().single()
        assertEquals(18, cmd.colStart, "RIGHT：DrawableWidth - num = 20 - 2")
        assertEquals(20, cmd.colEnd)
    }

    /**
     * 控制台 ALIGNMENT 对直印 HTML 行**失效**：html2DisplayLine 产出线先经
     * `SetAlignment(state.Alignment)`（HtmlManager.cs:702-710，state 缺省 LEFT，
     * HtmlManager.cs:536）把 `aligned` 置上（ConsoleDisplayLine.cs:56），
     * addDisplayLine 的 `SetAlignment(alignment)`（EmueraConsole.cs:2941-2944）
     * 撞一次守卫（ConsoleDisplayLine.cs:52-55）成 no-op —— 若守卫失效，
     * 本例 11 格的行会被 RIGHT 位移到 colStart = 9。
     */
    @Test
    fun `控制台对齐对直印 html 行失效`() {
        val s = sink()
        s.setAlignment(Alignment.RIGHT)
        s.printHtml("没有 p 标签", false)
        val f = feed(s, screen(), GraphicsLayer())
        f.pump()
        val cmd = f.screen.list.render().single()
        assertEquals(0, cmd.colStart, "html 状态缺省 LEFT 抢先，ALIGNMENT 不生效")
        assertEquals(11, cmd.colEnd, "没有(4)+空格+p+空格+标签(4) = 11 格，左对齐原位")
    }

    // ==================================================================
    // 3) 内联段汇合（PRINT_IMG 家族）
    // ==================================================================

    /** 追加到最后一条显示行的尾部，且列区间从该行当前右沿起。 */
    @Test
    fun `内联段追加到最后一条行尾`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("文字行")
        val f = feed(s, screen(), layer)
        f.pump()
        assertEquals(1, f.screen.list.render().size)

        layer.enqueue(InlineGraphic.Image("gauge", 30, 18, 0, 4))
        val rects = f.pump()
        assertTrue(rects.isNotEmpty(), "sink 的 revision 没动，但内联段到了也必须交（GraphicsLayer.hasPendingInline 分支）")
        val cmds = f.screen.list.render()
        assertEquals(2, cmds.size, "同一行的第二段")
        assertEquals(0, cmds[0].colStart)
        // 本仓宽度模型：CJK 全角 = 2 半角格（`Grid.cells("文字行")` = 6，
        // 与核心层 `core/host/CellWidth.kt` 的逐码位口径同值，PRINTC 补齐用的就是它，
        // spec: `EmueraConsole.cs:3248-3257`）⇒ 三个汉字占 6 格，不是 3 格。
        assertEquals(6, cmds[0].colEnd, "文字行「文字行」= 6 格（全角各 2 格）")
        assertEquals(6, cmds[1].colStart)
        assertEquals(10, cmds[1].colEnd)
        assertEquals(RunKind.IMAGE, cmds[1].kind)
        // 排空是一次性的：同一批内联段不许被追加两次
        assertTrue(f.pump().isEmpty())
        assertEquals(2, f.screen.list.render().size)
    }

    /** 开局第一条语句就是 `PRINT_IMG`（屏上还没有行）：当场造一条行接住它。 */
    @Test
    fun `空屏时内联段自建一行`() {
        val s = sink()
        val layer = GraphicsLayer()
        layer.enqueue(InlineGraphic.Space(5))
        val f = feed(s, screen(), layer)
        val rects = f.pump()
        assertEquals(1, rects.size)
        val cmd = f.screen.list.render().single()
        assertEquals(0, cmd.colStart)
        assertEquals(5, cmd.colEnd)
        assertEquals(RunKind.SHAPE, cmd.kind)
    }

    /** 占宽为 0 的段（越界被夹没）直接不进列表，避免零宽段把脏区判据搅乱。 */
    @Test
    fun `零宽内联段被丢弃`() {
        val s = sink()
        val layer = GraphicsLayer()
        layer.enqueue(InlineGraphic.Space(0))
        val f = feed(s, screen(), layer)
        f.pump()
        assertEquals(emptyList<String>(), f.screen.snapshotText())
    }

    /** 背景层换代不进内联队列：`ConsoleFeed` 只管内联段，背景由绘制侧直接读 layer。 */
    @Test
    fun `背景层与内联队列互不干扰`() {
        val s = sink()
        val layer = GraphicsLayer()
        layer.addBackground(GraphicsLayer.BgPlacement("bg", 0, 255))
        layer.enqueue(InlineGraphic.Image("a", 60, 30, 0, 7))
        val f = feed(s, screen(), layer)
        f.pump()
        assertEquals(1, layer.background.size)
        assertTrue(!layer.hasPendingInline(), "内联队列必须被排空")
    }

    // ==================================================================
    // 4) 内联图形持久化（GFX-04 锚点表：连续两帧仍在 / 清行与淘汰不残留）
    // ==================================================================

    /** 屏上当前绘制命令里的图形段（按行序）；调用方自行断言行号与列区间。 */
    private fun graphicsOf(screen: ConsoleScreen) = screen.list.render().filter { it.graphic != null }

    /**
     * `PRINT_IMG` 的内联段：贴上去之后再来一次 publish（新行到达），图形必须仍在原行原列。
     * 没有锚点表时这一帧图形就没了（GFX-04 的现象）；原版图形段是打印缓冲里的节点、随显示行
     * 进 `displayLineList`（EmueraConsole.cs:238、:3192-3195），不会因下一次控制台更新消失。
     */
    @Test
    fun `内联 IMAGE 连续两次 publish 后仍在锚点行`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("血条")
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Image("gauge", 30, 18, 0, 4))
        f.pump()
        assertEquals(listOf("血条    "), f.screen.snapshotText(), "图形段以等量空格占宽")

        s.printLine("下一行")
        val rects = f.pump()
        assertTrue(rects.isNotEmpty(), "新行到达要重交，图形必须跟着重贴")
        assertEquals(1, rects.single().rowStart, "只有新行进脏区：重贴本身不产生无谓重绘")
        assertEquals(listOf("血条    ", "下一行"), f.screen.snapshotText())
        val cmds = f.screen.list.render()
        assertEquals(listOf(RunKind.TEXT, RunKind.IMAGE, RunKind.TEXT), cmds.map { it.kind })
        assertEquals(0, cmds[1].row, "图形仍锚在第 1 行，不漂到新行")
        assertEquals(4, cmds[1].colStart)
        assertEquals(8, cmds[1].colEnd)
        assertEquals("gauge", (cmds[1].graphic as InlineGraphic.Image).resource)
        assertTrue(f.pump().isEmpty(), "同一 revision 再 pump 幂等：锚点重贴不进脏区")
    }

    /** `PRINT_RECT` 同一条锚点纪律（色块 ARGB 与列区间一并保持）。 */
    @Test
    fun `内联 RECT 连续两次 publish 后仍在锚点行`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("量表")
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Rect(0, 0, 30, 18, 0xFF_FF8800.toInt(), 4))
        f.pump()
        s.printLine("尾行")
        f.pump()

        assertEquals(listOf("量表    ", "尾行"), f.screen.snapshotText())
        val cmd = graphicsOf(f.screen).single()
        assertEquals(RunKind.SHAPE, cmd.kind)
        assertEquals(0, cmd.row)
        assertEquals(4, cmd.colStart)
        assertEquals(8, cmd.colEnd)
        assertEquals(0xFF_FF8800.toInt(), (cmd.graphic as InlineGraphic.Rect).colorArgb)
    }

    /** `PRINT_SPACE` 同一条锚点纪律（空白段只推进列游标，两帧后列位不漂）。 */
    @Test
    fun `内联 SPACE 连续两次 publish 后仍在锚点行`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("留白")
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Space(3))
        f.pump()
        s.printLine("x")
        f.pump()

        assertEquals(listOf("留白   ", "x"), f.screen.snapshotText())
        val cmd = graphicsOf(f.screen).single()
        assertEquals(RunKind.SHAPE, cmd.kind)
        assertEquals(0, cmd.row)
        assertEquals(4, cmd.colStart, "留白 = 4 格")
        assertEquals(7, cmd.colEnd)
    }

    /**
     * CLEARLINE：core 从尾部按逻辑行摘显示行对象（LineBufferConsoleSink.kt:688-711，
     * 原版 EmueraConsole.cs:2999-3051）⇒ 锚点找不回源行必须作废。
     * 两条判据：被删行的图形一起消失；之后重打的同文行不得继承旧图形
     * （认领 PRINTN 并入行用的是 Segment **实例**前缀，文字相等不算数）。
     */
    @Test
    fun `CLEARLINE 后锚点随行作废且同文重打不继承图形`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("甲")
        s.printLine("乙")
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Image("gauge", 30, 18, 0, 4))
        f.pump()
        assertEquals(listOf("甲", "乙    "), f.screen.snapshotText())

        s.deleteLine(1)
        val rects = f.pump()
        assertTrue(rects.isNotEmpty(), "被删行要进脏区才能擦掉图形")
        assertEquals(listOf("甲"), f.screen.snapshotText())
        assertTrue(graphicsOf(f.screen).isEmpty(), "被删行的图形必须一起消失")

        s.printLine("乙")
        f.pump()
        assertEquals(listOf("甲", "乙"), f.screen.snapshotText())
        assertTrue(graphicsOf(f.screen).isEmpty(), "同文重打的行不得继承旧图形（锚点已作废）")
    }

    /**
     * MaxLog 淘汰：core 从头部丢最旧行（LineBufferConsoleSink.kt:1615-1618，
     * 原版 EmueraConsole.cs:2988-2996）。
     * 判据：锚点行还在快照里时图形照常贴（下标跟着前移）；锚点行被淘汰后图形消失，
     * 且绝不贴到别的行（没有越界，也没有悬空 run）。
     */
    @Test
    fun `日志淘汰后锚点随行消失且不贴到别的行`() {
        val s = LineBufferConsoleSink(clientWidth = 20, maxLines = 3)
        val layer = GraphicsLayer()
        s.printLine("L0")
        s.printLine("L1")
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Space(2))
        f.pump()
        assertEquals(listOf("L0", "L1  "), f.screen.snapshotText(), "图形贴在当时的末行 L1")

        s.printLine("L2")
        f.pump()
        s.printLine("L3")
        f.pump() // 淘汰 L0：锚点行 L1 还在，锚点下标要跟着前移
        assertEquals(listOf("L1  ", "L2", "L3"), f.screen.snapshotText())
        assertEquals(0, graphicsOf(f.screen).single().row, "L0 被淘汰后 L1 成为首行，图形跟着走")

        s.printLine("L4")
        f.pump() // 淘汰 L1：锚点随行作废
        assertEquals(listOf("L2", "L3", "L4"), f.screen.snapshotText())
        assertTrue(graphicsOf(f.screen).isEmpty(), "被淘汰行的图形不得残留或贴到别的行")
    }

    /**
     * PRINTN 并入：末行被 core 以 `mergeRows` 重建（LineBufferConsoleSink.kt:1607-1613；
     * 原版 deleteLine(1) 让号再 Add，EmueraConsole.cs:2947-2970）。
     * 锚点按「旧 Segment 实例是新行前缀」认领同一逻辑行：显示行数不增，图形留在旧文字与
     * 续写文字之间（插入点 = 锚定当时的 run 数），之后新增的行不会把图形带走。
     */
    @Test
    fun `PRINTN 并入后锚点留在同一逻辑行且不漂到新行`() {
        val s = sink()
        val layer = GraphicsLayer()
        s.printLine("甲")
        s.print("乙", false)
        s.newLine()
        val f = feed(s, screen(), layer)
        f.pump()

        layer.enqueue(InlineGraphic.Image("gauge", 30, 18, 0, 4))
        f.pump()
        assertEquals(listOf("甲", "乙    "), f.screen.snapshotText(), "图形先贴在未收尾的乙行")

        s.print("丙", true)
        s.newLine() // 并入乙行
        f.pump()
        assertEquals(2, f.screen.snapshotText().size, "PRINTN 并入不新增显示行")
        assertEquals("甲", f.screen.snapshotText()[0])
        assertEquals("乙    丙", f.screen.snapshotText()[1], "图形留在乙与丙之间（原版节点序）")

        s.printLine("丁")
        f.pump()
        assertEquals(listOf("甲", "乙    丙", "丁"), f.screen.snapshotText())
        val cmd = graphicsOf(f.screen).single()
        assertEquals(1, cmd.row, "图形仍锚在乙丙所在行，不漂到后来的丁行")
        assertEquals(2, cmd.colStart, "乙 = 2 格")
        assertEquals(6, cmd.colEnd)
    }

    /** `sizeSource` 与取图口是同一个对象时（生产装配就是这一个实例），宽高与尺寸表同源。 */
    @Test
    fun `生产装配的同一个对象既是尺寸口也是取图口`() {
        val frames = FakeFrames(mapOf("bodyGraph_X" to intArrayOf(60, 30)))
        val s = sink()
        s.printHtml("<img src='bodyGraph_X' height='30px'>", false)
        val f = feed(s, screen(), GraphicsLayer(), frames)
        f.pump()
        val g = f.screen.list.render().single().graphic as InlineGraphic.Image
        assertEquals(60, g.widthPx, "缺宽 ⇒ 按 DestBaseSize 纵横比推 = 100 * 30 / 50 同源读数")
        assertEquals(7, g.widthCells)
        // 绘制侧现取（显示列表里只存名字）
        assertEquals("bodyGraph_X", (frames.frame("bodyGraph_X")!!.handle as String).removePrefix("bitmap:"))
    }

    /**
     * 「适应屏幕」改字号后调用 [ConsoleFeed.updateLayout]：`<img>` 缺省高与占宽
     * 必须按**新**字号重展平，否则标题图仍按旧字号铺，和行高对不上
     * （真机 36px → 30px，带高 36 落进 31px 行 ⇒ 逐带压到下一行）。
     */
    @Test
    fun `updateLayout 按新字号重展平图片并重交`() {
        val s = sink(width = 80)
        s.printHtml("<img src='a'>", false)
        val sizes = FakeSizes("a" to (1000 to 16))
        val sc = screen(width = 80, heightLines = 5)
        val f = ConsoleFeed(s, sc, GraphicsLayer(), CellMetrics(18), sizes)
        f.pump()
        val before = sc.list.render().single().graphic as InlineGraphic.Image
        assertEquals(18, before.heightPx, "缺省高 = FontSize（ConsoleImagePart.cs:66）")
        assertEquals(1125, before.widthPx, "按纵横比推 1000 × 18 / 16 = 1125，不按窗口宽夹")

        assertTrue(
            f.updateLayout(
                metrics = CellMetrics(30),
                bandAvailWidthPx = 0,
                bandMaxHeightPx = 0,
            ),
            "字号变了 ⇒ 必须重交一次",
        )
        val after = sc.list.render().single().graphic as InlineGraphic.Image
        assertEquals(30, after.heightPx, "重展平后图高跟新字号")
        assertEquals(1875, after.widthPx, "按 30px 推宽 1000 × 30 / 16 = 1875（仍不夹）")
        assertEquals(1, sc.snapshotText().size, "行数不变")

        assertTrue(
            !f.updateLayout(CellMetrics(30), 0, 0),
            "参数没变时不重交",
        )
    }

    /**
     * 竖带合并（C）：20 条同尺寸横带合成一张等比缩小的图，行数收窄到
     * `ceil(块高 / 行高)`，并打上 [InlineGraphic.Image.rowSpan]（绘制侧据此不按单行压）。
     */
    @Test
    fun `竖带合并把连续图片行收成一张跨行的图`() {
        val s = sink(width = 80)
        repeat(20) { s.printHtml("<img src='band'>", false) }
        assertEquals(20, s.snapshot().size, "前置：20 次 HTML_PRINT 落 20 条显示行")
        val sizes = FakeSizes("band" to (1000 to 16))
        val m = CellMetrics(18)
        val sc = screen(width = 80, heightLines = 20)
        val f = ConsoleFeed(
            s, sc, GraphicsLayer(), m, sizes,
            bandAvailWidthPx = 748,
            bandMaxHeightPx = 100,
        )
        f.pump()

        val rows = (100 + m.lineHeight - 1) / m.lineHeight
        assertEquals(rows, sc.snapshotText().size, "20 条带收成 ceil(100/行高) 行")
        val imgCmds = sc.list.render().filter { it.graphic != null }
        assertEquals(1, imgCmds.size, "承载行只有占位空格，图形命令只有一条")
        val g = imgCmds.single().graphic as InlineGraphic.Image
        assertEquals(100, g.heightPx, "块高按 maxBlockHeightPx 上限")
        assertEquals(rows, g.rowSpan, "整块跨 $rows 行，绘制侧不得压回单行高")
        assertEquals(20, g.bandResources?.size, "20 张切片全部带进块里逐片堆叠")
        // 单张带子按字号推宽 1000 × 18/16 = 1125（HtmlRunFlatten 不再按窗口夹），
        // 合并块再按高限 100/360 等比缩到 1125 × 100/360 ≈ 313。
        assertEquals(313, g.widthPx, "自然宽 1125 × 100/360 ≈ 313（等比）")
    }
}
