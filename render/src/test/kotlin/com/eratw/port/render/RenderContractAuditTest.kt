package com.eratw.port.render

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.displaylist.DisplayList
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.sprite.DrawLineBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对拍族「控制台渲染口径」—— W7-RENDERAUDIT（只读审计，产品零改动）。
 *
 * ## 与既有审计文件的分工
 *
 * `grid/LineFolderAuditTest` 管折行算法的单点分支、`grid/GridWidthSingleSourceTest` 管宽度表逐码点、
 * `button/ButtonHitAuditTest` 管命中区间的几何、`sprite/BarLineTest` 管基准的三条分隔线串。
 * 本文件**不重测**那些逐值表，只把「[LineFolder] → [ConsoleAdapter] → [DisplayList] /
 * [ButtonLayer] / [LogBuffer] / [DrawLineBar]」这条链按 C# 的端到端口径串起来，
 * 每条用例都读一个跨模块的读数（行数、`cells`、`indentCells`、命中列、绘制列、填充长度）。
 *
 * ## 逐条口径（C# 出处 → 本仓落地 → 结论）
 *
 * | # | C# 出处 | 本仓落地 | 结论 |
 * |---|---|---|---|
 * | 1 | `PrintStringBuffer.cs:217` 放得下（`PointX + Width <= num`）就入行 | `LineFolder.fold` 的 `seg.pointX + seg.width <= clientWidth` | 一致；恰好等于宽度时不再开新行 |
 * | 2 | `PrintStringBuffer.cs:236-240` 前缀为 0 且当前行空 → 整段入行（可溢出） | `fold` 的 `div != 0` 或 `current.isEmpty()` 分支 | 一致（第 3 条钉全角跨界） |
 * | 3 | `PrintStringBuffer.cs:244-249` 断行后从当前段起整体重排 `pointX` | `relayout(from = i)` | 一致（第 2 条读右半的 `cells`/文本） |
 * | 4 | 输出路径**没有**行尾空白裁剪（对照：PRINTC 才在 `EmueraConsole.cs:3277-3289` 逐尾空格删） | `FoldPiece` 原样保留文本，`cells` 计入尾空格 | 一致（第 1/2/8 条逐字读尾空白） |
 * | 5 | `PrintStringBuffer.cs:170-177` `FlushSingleLine` 传 `nobr: true`，一行不折 | `ConsoleAdapter.fold` 把 `noBreak` 交给 `fold(noBr = …)` | 一致（第 4 条经 `DisplayLine.noBreak` 走完整条链） |
 * | 6 | `EmueraConsole.cs:3147-3165` PRINTSINGLE 走 `BufferToSingleLine(force: true)` | 同一条 `noBreak` 通道 | 一致 |
 * | 7 | `EmueraConsole.cs:3333-3337` 换行、`:3375-3386` 空缓冲先 `Append(" ", Style)` | `ConsoleAdapter.adapt` 的 `emptySpaceRun`；`LineFolder` 对零段返回空表（`PrintStringBuffer.cs:198-201`） | 一致（补空格只能在适配层，第 5 条两侧都断言） |
 * | 8 | `EmueraConsole.cs:2947-2970` 末行未断行 → `deleteLine(1)` 后 `ShiftPositionX` + `ChangeStr` 拼接，`:3013` 让号 | `ConsoleAdapter.appendMerged` + `mergedRow` | 一致（第 6 条读 `size`/`totalLines`/`cells`） |
 * | 9 | `PrintStringBuffer.cs:183` 只有一批 flush 的**末行**被赋 `isLastLineEnd` | `fold` 的 `rowOf(last = …)` | 一致（第 7 条读中间行恒 `true`） |
 * | 10 | `ConsoleButtonString.cs:300-304` 按钮宽 = 末段右沿 − 首段左沿；`EmueraConsole.cs:2358-2361` 逐 `PointX` 判命中 | `ButtonLayer.attach` 的列游标 + `HitTarget` 半开区间 | 一致（第 8 条读全角列宽与尾空白不可点） |
 * | 11 | `EmueraConsole.cs:232` `ClientWidth = MainPicBox.Width`，`Config.cs:461-466` `DrawableWidth = WindowX - shift` 是两个数 | 本层只有**一个** `clientWidth` | **现状钉桩**（第 9 条，见下节） |
 * | 12 | `EmueraConsole.cs:2937-2944` 每条显示行各自 `SetAlignment`；`ConsoleDisplayLine.cs:80,83,86-90` 的 `num3` 加到 `PointX` | `ConsoleAdapter.fold` 逐行 `alignmentShift` | 一致（第 10 条读折行后的两行偏移） |
 * | 13 | `EmueraConsole.cs:3420-3437` `getStBar`：追加到不小于再逐 char 删到不大于 | `DrawLineBar.build` 同式 | 一致（第 11/12 条读残留量） |
 *
 * ## 第 11 条的现状与待验（不修，只钉）
 *
 * 原版折行与对齐读 `Config.DrawableWidth`（`Config.cs:466`，基准 1797px），
 * 而鼠标坐标 `point` 取自 `MainPicBox`、上界是 `ClientWidth`（`EmueraConsole.cs:232,2321-2322`），
 * 两者相差 `DrawingParam_ShapePositionShift`（基准 3px）。所以原版「可点」的最后 3px
 * 落在可绘制宽之外 —— 折行按 1797 排、命中按 1800 算。
 * 本层只有一个 `clientWidth`（折行、绘制裁剪、命中、触屏外扩共读它），
 * 于是溢出排（`cells` 大于 `clientWidth`，见 `PrintStringBuffer.cs:236-240`）上的按钮：
 * - 绘制被夹到窗口（`DisplayList.prepare`，对应 GDI 对画布外像素的裁剪
 *   `ConsoleStyledString.cs:119,129-138`）；
 * - 命中**不夹**：`ButtonLayer.hit` 对任意 `col` 都按区间判，`col` 大于等于 `clientWidth`
 *   仍能命中（原版那一段在窗口外，`point.X` 到不了，效果等价于不可点）；
 * - 触屏外扩的右界取 `clientWidthCells` 与自身右沿的**较大者**（`nextStartInRow` 的
 *   `coerceAtLeast`），溢出按钮的右沿因此越出窗口。
 * 三条都按**现状**钉在 `按钮溢出排的绘制夹到窗口而命中不夹` 里。
 * TODO(verify)：`ClientWidth` 与 `DrawableWidth` 要不要拆成两个字段（拆了才能逐像素对齐原版那 3px
 * 的命中带），卡点：安卓侧坐标由宿主 View 裁到窗口，拿不到「窗口外一点」的真机读数；
 * 另需确认 `ButtonLayer.hit` 是否该在 `clientWidth` 处夹住 —— 改了会动 m-button 的对外契约。
 *
 * ## 第 13 条的「填满整行」是「再加一个单元就超」
 *
 * `getStBar` 先追加到**不小于** `DrawableWidth` 再逐 char 删到**不大于**，所以结果恒是
 * 「重复单元的最长前缀且不超过可绘制宽」。半角单元（`-`，9px）在 1797px 下恰好 199 个、
 * 残 6px；全角单元（`━`，18px）只有 99 个、残 15px —— 后者的残量是**单元宽度不够**，
 * 不是没填满。第 11/12 条分别钉这两种单元的读数。
 */
class RenderContractAuditTest {

    private fun text(s: String) = TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun button(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun segment(s: String) = LineBufferConsoleSink.Segment(s, foreColor = 0xFFFFFF, styleMask = 0, fontName = "msgothic")

    private fun line(
        vararg runs: TextRun,
        isLineEnd: Boolean = true,
        align: Alignment = Alignment.LEFT,
        noBreak: Boolean = false,
    ) = RenderLine(
        runs = runs.toList(),
        isLogicalLine = true,
        isLineEnd = isLineEnd,
        align = align,
        noBreak = noBreak,
    )

    /**
     * 第 1/4 条：宽度 4、文本 `ab` + 两个尾空格 ⇒ 恰好占满，**只产出一行**，
     * 尾空格原样留在 `runs` 与 `cells` 里（C# 输出路径没有 `TrimEnd`；
     * 只有 PRINTC 的 `CreateTypeCString` 会在超宽时删尾空格，`EmueraConsole.cs:3277-3289`）。
     */
    @Test
    fun `自动换行_恰好占满整行不造空行且行尾空白原样保留`() {
        val rows = LineFolder(clientWidth = 4).fold(line(text("ab  ")))
        assertEquals(1, rows.size)
        assertEquals(listOf("ab  "), rows[0].runs.map { it.text })
        assertEquals(4, rows[0].cells)
        assertTrue(rows[0].isLogicalLine)
        assertTrue(rows[0].isLineEnd)
    }

    /**
     * 第 2/3/4 条：`abc` + 两个尾空格共 5 格、宽 4 ⇒ 切点 4，右半是**一个空格**的独立显示行
     * （`PrintStringBuffer.cs:224-249`；右半从行首重排，故第二行 `cells = 1` 而不是被丢掉）。
     */
    @Test
    fun `自动换行_行尾空白参与折行并独占次行`() {
        val rows = LineFolder(clientWidth = 4).fold(line(text("abc  ")))
        assertEquals(2, rows.size)
        assertEquals("abc ", rows[0].runs[0].text)
        assertEquals(4, rows[0].cells)
        assertEquals(" ", rows[1].runs[0].text)
        assertEquals(1, rows[1].cells)
        assertTrue(!rows[1].isLogicalLine)
    }

    /**
     * 第 2 条：3 个全角字共 6 格、宽 5 ⇒ 前缀只收 2 字（4 格），第三个字整字挪到次行，
     * 行内留 1 格空白也**不劈字**（`PrintStringBuffer.cs:453-496` 在 `text.Length` 上取前缀，
     * 拆分按 Char 下标 `ConsoleStyledString.cs:54-73`）。
     */
    @Test
    fun `自动换行_全角字符不劈开且在右沿留一格`() {
        val rows = LineFolder(clientWidth = 5).fold(line(text("あああ")))
        assertEquals(2, rows.size)
        assertEquals(listOf("ああ"), rows[0].runs.map { it.text })
        assertEquals(4, rows[0].cells)
        assertEquals(listOf("あ"), rows[1].runs.map { it.text })
        assertEquals(2, rows[1].cells)
    }

    /**
     * 第 5/6 条：`DisplayLine.noBreak`（PRINTSINGLE / REUSELASTLINE 的 `nobr`）经
     * [ConsoleAdapter.adapt] 透传后，6 格原文在宽 4 下仍是**一行**（`PrintStringBuffer.cs:170-177`
     * 的 `nobr: true`；判定在 `:217`）。
     */
    @Test
    fun `强制换行_超宽原文一行不折`() {
        val display = LineBufferConsoleSink.DisplayLine(
            isLineEnd = true,
            segments = listOf(segment("abcdef")),
            noBreak = true,
        )
        val adapted = ConsoleAdapter.adapt(display)
        assertTrue(adapted.noBreak)
        val rows = ConsoleAdapter.fold(LineFolder(clientWidth = 4), listOf(adapted))
        assertEquals(1, rows.size)
        assertEquals(6, rows[0].cells)
        assertEquals("abcdef", rows[0].runs[0].text)
    }

    /**
     * 第 7 条：空缓冲的强制换行在原版先 `Append(" ", Style)`（`EmueraConsole.cs:3379-3382`，
     * 调用侧 `NewLine` `:3333-3337`）。本层零段在 [LineFolder] 里返回空表
     * （`PrintStringBuffer.cs:198-201`），补空格只能由适配层做 —— 两侧一起断言，
     * 谁把补空格挪到折行器里都会在这条炸。
     */
    @Test
    fun `强制换行_空行按一格空格入列`() {
        assertEquals(0, LineFolder(clientWidth = 4).fold(RenderLine(emptyList(), true, true)).size)
        val adapted = ConsoleAdapter.adapt(LineBufferConsoleSink.DisplayLine(isLineEnd = true, segments = emptyList()))
        assertEquals(listOf(" "), adapted.runs.map { it.text })
        assertEquals(LineBufferConsoleSink.DEFAULT_FONT_NAME, adapted.runs[0].fontName)
        assertEquals(LineBufferConsoleSink.DEFAULT_FORE_COLOR, adapted.runs[0].fore)
        val rows = ConsoleAdapter.fold(LineFolder(clientWidth = 4), listOf(adapted))
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].cells)
    }

    /**
     * 第 8 条：PRINTN 的下一批内容并入未断行的末行 —— 先删末行（让号，`EmueraConsole.cs:3013`），
     * 把新行整体右移旧行宽后拼成一条（`:2955-2966`），再入列取回同一个号（`:2945,2970`）。
     * 读数：缓冲仍 1 行、绝对行号不变、`cells` 相加、段序旧前新后。
     */
    @Test
    fun `强制换行_PRINTN未断行并入上一行且保号`() {
        val folder = LineFolder(clientWidth = 20)
        val first = ConsoleAdapter.fold(folder, listOf(line(text("abc"), isLineEnd = false)))
        val second = ConsoleAdapter.fold(folder, listOf(line(text("日本語"))))
        assertEquals(1, first.size)
        assertTrue(!first[0].isLineEnd)

        val buffer = LogBuffer(capacity = 10)
        ConsoleAdapter.appendMerged(buffer, first)
        assertEquals(1, buffer.size)
        assertEquals(1L, buffer.totalLines)
        ConsoleAdapter.appendMerged(buffer, second)
        assertEquals(1, buffer.size)
        assertEquals(1L, buffer.totalLines)
        assertEquals(1L, buffer.logicalLines)
        val merged = buffer.last()
        assertNotNull(merged)
        assertEquals(9, merged.cells)
        assertEquals(listOf("abc", "日本語"), merged.runs.map { it.text })
        assertTrue(merged.isLineEnd)
    }

    /**
     * 第 9 条：一批 flush 只有**末行**被写成逻辑行的 `lineEnd`（`PrintStringBuffer.cs:183`），
     * 折行产生的中间行仍是 `isLineEnd = true`（`ConsoleDisplayLine.cs:16,32`）——
     * 所以并入点只认逻辑行尾。宽 4 拆 `abcdef` 且 `lineEnd = false`：行 0 真、行 1 假。
     */
    @Test
    fun `折行标记_中间行恒为行尾而末行透传逻辑行尾`() {
        val rows = LineFolder(clientWidth = 4).fold(line(text("abcdef"), isLineEnd = false))
        assertEquals(2, rows.size)
        assertTrue(rows[0].isLineEnd)
        assertTrue(!rows[1].isLineEnd)
        assertTrue(rows[0].isLogicalLine)
        assertTrue(!rows[1].isLogicalLine)
    }

    /**
     * 第 10 条：按钮区间由全角 2 格累加得出 —— `あ` 之后按钮 `い` 占 `[2,4)`；
     * 按钮后的两个尾空格计入行宽（6）但不进命中表（`ConsoleButtonString.cs:300-304` 的宽只算
     * 按钮自身，`EmueraConsole.cs:2358-2361` 的命中只认 `PointX` 到 `PointX + Width`）。
     */
    @Test
    fun `按钮区间_按全半角列宽累加且行尾空白不可点`() {
        val rows = ConsoleAdapter.fold(LineFolder(clientWidth = 10), listOf(line(text("あ"), button("い", 3L), text("  "))))
        assertEquals(1, rows.size)
        assertEquals(6, rows[0].cells)
        val layer = ButtonLayer()
        layer.attach(rows)
        assertEquals(1, layer.all().size)
        val target = layer.all()[0]
        assertEquals(2, target.colStart)
        assertEquals(4, target.colEnd)
        assertNull(layer.hit(0, 1))
        assertEquals(3L, layer.hit(0, 2)?.value)
        assertEquals(3L, layer.hit(0, 3)?.value)
        assertNull(layer.hit(0, 4))
        assertNull(layer.hit(0, 5))
        assertEquals("3", layer.click(0, 2))
    }

    /**
     * 第 11 条（现状钉桩）：溢出排里被右沿裁掉的按钮。
     *
     * 宽 8、`nobr` 行 `ABCDEFG`（7 格）+ 按钮 `OK`（2 格）共 9 格 ⇒ 按钮占 `[7,9)`，两端各有一截
     * 落在可绘制宽之外。读数按**现状**：
     * - 绘制：按钮段被夹到 `[7,8)`（`DisplayList.prepare` 的 `minOf(…, clientWidth)`，
     *   对应 GDI 对画布外的裁剪 `ConsoleStyledString.cs:119,129-138`），但 `glyphs` 仍是 2 个
     *   （裁剪只夹列、不裁字形表，绘制侧在窗口右沿停笔）；
     * - 命中：区间仍是 `[7,9)`，`col = 8`（窗口外一格）**也命中** —— 本层不在 `clientWidth` 处夹；
     * - 触屏外扩：`minWidthCells = 5` 时把 2 格按钮扩到 `[4,9)`，右沿 9 越出窗口 1 格
     *   （`nextStartInRow` 取 `clientWidthCells` 与自身右沿的较大者）。
     * 原版的对照读数见类 KDoc「第 11 条的现状与待验」：可绘制宽 1797 与窗口宽 1800 是两个数。
     *
     * TODO(verify)：命中域是否该拆出「窗口宽」并在其上界夹住；卡点与影响面见类 KDoc。
     */
    @Test
    fun `按钮溢出排的绘制夹到窗口而命中不夹`() {
        val rows = ConsoleAdapter.fold(
            LineFolder(clientWidth = 8),
            listOf(line(text("ABCDEFG"), button("OK", 1L), noBreak = true)),
        )
        assertEquals(1, rows.size)
        assertEquals(9, rows[0].cells)

        val list = DisplayList(clientWidth = 8)
        list.submit(rows)
        val cmds = list.render()
        assertEquals(2, cmds.size)
        assertEquals(0, cmds[0].colStart)
        assertEquals(7, cmds[0].colEnd)
        assertEquals(7, cmds[1].colStart)
        assertEquals(8, cmds[1].colEnd)
        assertEquals(RunKind.BUTTON, cmds[1].kind)
        assertEquals(2, cmds[1].glyphs.size)

        val layer = ButtonLayer()
        layer.attach(rows)
        val target = layer.all()[0]
        assertEquals(7, target.colStart)
        assertEquals(9, target.colEnd)
        assertEquals(1L, layer.hit(0, 7)?.value)
        assertEquals(1L, layer.hit(0, 8)?.value)

        val boxes = layer.touchHitboxes(minWidthCells = 5, clientWidthCells = 8)
        assertEquals(1, boxes.size)
        assertEquals(4, boxes[0].colStart)
        assertEquals(9, boxes[0].colEnd)
    }

    /**
     * 第 12 条：折行后**每一条显示行各自对齐**（`EmueraConsole.cs:2937-2944` 对每条行调
     * `SetAlignment`）。`あああ` 在可绘制宽 5 下拆成 4 格 + 2 格，CENTER 的
     * `num3 = 5/2 - cells/2`（`ConsoleDisplayLine.cs:80`）逐行取值 ⇒ 0 与 1。
     */
    @Test
    fun `对齐_折行后每行各自平移`() {
        val rows = ConsoleAdapter.fold(
            LineFolder(clientWidth = 5),
            listOf(line(text("あああ"), align = Alignment.CENTER)),
        )
        assertEquals(2, rows.size)
        assertEquals(listOf(4, 2), rows.map { it.cells })
        assertEquals(listOf(0, 1), rows.map { it.indentCells })
        assertEquals(listOf(Alignment.CENTER, Alignment.CENTER), rows.map { it.align })
    }

    /**
     * 第 13 条（半角单元）：`getStBar` 的「再加一个单元就超」。
     * 基准窗口 1800px、`shift = max(2, 18/6) = 3` ⇒ `DrawableWidth = 1797`（`Config.cs:461-466`）；
     * 半角步进 9px ⇒ 199 个 `-` 是 1791px，再放一个就 1800px 越界（算法 `EmueraConsole.cs:3420-3437`，
     * 基准 `pc-golden-root.txt:253-254` 的 199 字串同式）。`DRAWLINE` 用的就是这条串
     * （`setStBar` `:3439-3442`、`PrintBar` `:3388-3394`）。
     */
    @Test
    fun `分隔线_半角单元填满整行到不能再放一个单元`() {
        val gen = DrawLineBar()
        assertEquals(3, gen.shapeShift)
        assertEquals(1797, gen.drawableWidth)
        val bar = gen.build("-")
        assertEquals(199, bar.length)
        assertTrue(bar.all { it == '-' })
        assertEquals(1791, gen.measurePx(bar))
        assertTrue(gen.measurePx(bar) <= gen.drawableWidth)
        assertTrue(gen.measurePx(bar) + gen.halfCellPx > gen.drawableWidth)
        assertEquals(gen.build("-"), gen.defaultBar)
        assertEquals(199, gen.defaultBar.length)
    }

    /**
     * 第 13 条（全角单元）：`getStBar` 对全角单元同样只收到「不大于可绘制宽」——
     * 18px × 99 = 1782px，第 100 个就 1800px 越界，所以结果是 99 字、尾部残 15px。
     * 残量小于一个单元宽度，这就是「填满整行」在本算法里的唯一含义
     * （逐 char 删除循环 `EmueraConsole.cs:3431-3435`；全角按 2 格见 `Grid.cellWidth`）。
     */
    @Test
    fun `分隔线_全角单元同样填满且残量小于一个单元`() {
        val gen = DrawLineBar()
        val bar = gen.build("━")
        assertEquals(99, bar.length)
        assertTrue(bar.all { it == '━' })
        assertEquals(198, Grid.cells(bar))
        assertEquals(1782, gen.measurePx(bar))
        assertTrue(gen.measurePx(bar) <= gen.drawableWidth)
        assertTrue(gen.measurePx(bar) + 2 * gen.halfCellPx > gen.drawableWidth)
        // 残量 1797 - 1782 = 15px，小于一个全角单元 18px
        assertTrue(gen.drawableWidth - gen.measurePx(bar) < 2 * gen.halfCellPx)
    }
}
