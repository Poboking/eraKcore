package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.scroll.LogBuffer
import com.eratw.port.render.scroll.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ConsoleLogSync] 与 [ConsoleAdapter] 的日志侧接线判据（M4-ADAPT 接线⑤⑥⑦）。
 *
 * 期望值逐条来自 C#：
 * * 入列与并行的号守恒：`EmueraConsole.cs:2945`（`line.LineNo = lineNo`）、`:2954`（`deleteLine(1)`）、
 *   `:2969-2970`（`Add` 后 `lineNo++`）、`:3013`（`deleteLine` 里 `lineNo--`）。
 * * 并入行的标志取**新行**：入列的是新行对象（`:2966` 的 `line.ChangeStr(...)` 只换 buttons 数组、
 *   `:2969` 的 `displayLineList.Add(line)`），而 `IsLogicalLine`/`IsTemporary` 是只读的构造期字段
 *   （`ConsoleDisplayLine.cs:12,14,45-46`）。
 * * 临时行先删：`EmueraConsole.cs:2891-2893`。
 * * 绝对行号 → 列表下标：`EmueraConsole.cs:1865-1868`、`:2402`。
 * * 绘制与命中的取行溢出：`EmueraConsole.cs:1805-1807` 对 `:2328-2331`；顶行下标的负值夹取
 *   `if (num3 < 0) num3 = 0` 见 `:1808-1811`。
 */
class ConsoleLogSyncTest {

    private fun row(marker: String, logical: Boolean = true, lineEnd: Boolean = true, temporary: Boolean = false) = DisplayRow(
        runs = listOf(TextRun(marker, 0xC0C0C0, 0x000000, 0, "f")),
        cells = marker.length,
        isLogicalLine = logical,
        isLineEnd = lineEnd,
        temporary = temporary,
    )

    /** 折行之前的输入行（样式与核心层默认同值，本用例只关心入列规则，不关心样式）。 */
    private fun renderLine(
        text: String,
        logical: Boolean = true,
        lineEnd: Boolean = true,
        align: Alignment = Alignment.LEFT,
        temporary: Boolean = false,
    ) = RenderLine(
        runs = listOf(TextRun(text, 0xC0C0C0, 0x000000, 0, "f")),
        isLogicalLine = logical,
        isLineEnd = lineEnd,
        align = align,
        temporary = temporary,
    )

    private fun joined(row: DisplayRow?): String = row?.runs?.joinToString("") { it.text } ?: "<无>"

    private fun sink(width: Int = 80) = LineBufferConsoleSink(clientWidth = width)

    // ------------------------------------------------------------------
    // 接线⑤：PRINTN 续行在日志上不断行，绝对行号守恒
    // ------------------------------------------------------------------

    @Test
    fun `快照重复提交幂等且 PRINTN 续行只替换末行不新增显示行`() {
        val s = sink()
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(80))

        s.print("abc", false)
        s.newLine()
        assertEquals(1, sync.submit(ConsoleAdapter.adapt(s.snapshot())).size)
        assertEquals(1, buf.size)
        assertEquals(1L, buf.totalLines)
        assertEquals(0L, buf.firstIndex)
        assertEquals(0L, buf.logicalLines, "PRINTN 未收尾不计 LINECOUNT（EmueraConsole.cs:2971-2978）")
        assertFalse(buf.last()!!.isLineEnd)
        assertEquals("abc", joined(buf[0L]))

        // 同一个快照再交一次：一行都不该动。
        assertEquals(0, sync.submit(ConsoleAdapter.adapt(s.snapshot())).size)
        assertEquals(1, buf.size)
        assertEquals(1L, buf.totalLines)
        assertEquals(0L, buf.logicalLines)

        // PRINTN 的续行：核心层就地并入末行，快照条数不变（snapshot 仍是 1 条）。
        s.print("def", true)
        s.newLine()
        assertEquals(1, s.snapshot().size, "夹具自检：并入后快照仍是一条显示行（EmueraConsole.cs:2947-2970）")
        val landed = sync.submit(ConsoleAdapter.adapt(s.snapshot()))
        assertEquals(1, landed.size)
        assertEquals(1, buf.size, "日志不断行：不产生第二条显示行")
        assertEquals(1L, buf.totalLines, "先删后加拿回同一个号（EmueraConsole.cs:2945 与 :3013）")
        assertEquals(0L, buf.firstIndex)
        assertEquals(1L, buf.logicalLines)
        assertEquals("abcdef", joined(buf[0L]))
        assertTrue(buf[0L]!!.isLineEnd)
        assertTrue(buf[0L]!!.isLogicalLine)

        // 之后才是新的一行。
        s.print("x", true)
        s.newLine()
        assertEquals(1, sync.submit(ConsoleAdapter.adapt(s.snapshot())).size)
        assertEquals(2, buf.size)
        assertEquals(2L, buf.totalLines)
        assertEquals("x", joined(buf[1L]))
    }

    @Test
    fun `快照变短即回撤尾部对应删除行的效果`() {
        val s = sink()
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(80))

        s.print("A", true)
        s.newLine()
        s.print("B", true)
        s.newLine()
        sync.submit(ConsoleAdapter.adapt(s.snapshot()))
        assertEquals(2, buf.size)
        assertEquals(2L, buf.logicalLines)

        s.deleteLine(1) // EmueraConsole.cs:2999-3051：从尾部删掉一个逻辑行
        assertEquals(1, s.snapshot().size, "夹具自检")
        sync.submit(ConsoleAdapter.adapt(s.snapshot()))
        assertEquals(1, buf.size, "被删行从缓冲尾部回撤")
        assertEquals(1L, buf.totalLines, "lineNo-- 同步回退（EmueraConsole.cs:3013）")
        assertEquals(0L, buf.firstIndex)
        assertEquals("A", joined(buf.last()))
    }

    /**
     * 连续 REUSELASTLINE（PRINT 反复覆盖同一行）：每条临时行入列前先删掉上一条
     * （spec: `EmueraConsole.cs:2891-2893` 的 `LastLineIsTemporary` 先删 + `deleteLine(1)`，
     * :3007-3021），更早的行不得陪葬。原版临时行经 `PrintTemporaryLine` → `PrintSingleLine` →
     * `FlushSingleLine`，恒为**单排**显示行、不折行（spec: `EmueraConsole.cs:3053-3056`、
     * `:3158-3163`、`:3339-3353`；`PrintStringBuffer.cs:170-177`），所以这里的覆盖
     * 一条临时行恰好对应一次 removeLast。
     */
    @Test
    fun `连续 PRINT 覆盖临时行逐条顶替前面的行不动`() {
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(10))
        sync.submit(listOf(renderLine("KEEP")))
        assertEquals(1, buf.size)
        sync.submit(listOf(renderLine("KEEP"), renderLine("第一次", temporary = true)))
        assertEquals(2, buf.size)
        assertEquals(2L, buf.totalLines)
        assertTrue(buf.last()!!.temporary)
        // 第二次覆盖：旧临时行整条出列，KEEP 纹丝不动
        sync.submit(listOf(renderLine("KEEP"), renderLine("第二次", temporary = true)))
        assertEquals(2, buf.size, "旧临时行出列，前面的行不陪葬")
        assertEquals(2L, buf.totalLines, "删 1 排、加 1 排：行号守恒（EmueraConsole.cs:3013 对 :2970）")
        assertEquals(2L, buf.logicalLines, "KEEP 与新临时行各计 1")
        assertEquals("KEEP", joined(buf[0L]))
        assertEquals("第二次", joined(buf[1L]))
        assertTrue(buf.last()!!.temporary)
        // 第三条正常行：把临时行顶掉（同一条 EmueraConsole.cs:2891-2893 分支）
        sync.submit(listOf(renderLine("KEEP"), renderLine("落定")))
        assertEquals(2, buf.size)
        assertFalse(buf.last()!!.temporary, "临时行被正常行顶掉后标记消失")
        assertEquals("落定", joined(buf[1L]))
    }

    /**
     * 按钮行撤销：按钮行（PRINTBUTTON）折成两排后从快照里撤掉（CLEARLINE / DELETELINE 形态），
     * 两排一起出列、行号逐排回退（spec: `EmueraConsole.cs:2999-3051`），
     * 前面的普通行不动。按钮段与文本段在删除语义上无差别。
     */
    @Test
    fun `按钮行撤销整条回撤含折行续排`() {
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(10))
        val button = RenderLine(
            runs = listOf(
                TextRun("[0] あいうえお", 0xC0C0C0, 0x000000, 0, "f", kind = RunKind.BUTTON, buttonValue = 0L),
            ),
            isLogicalLine = true,
            isLineEnd = true,
        )
        sync.submit(listOf(renderLine("A"), renderLine("B"), button))
        assertEquals(4, buf.size, "A、B 各 1 排 + 按钮行折 2 排（13 格 > 10 格宽）")
        assertEquals(4L, buf.totalLines)
        assertEquals(3L, buf.logicalLines)
        // 撤销按钮行：快照少一条
        sync.submit(listOf(renderLine("A"), renderLine("B")))
        assertEquals(2, buf.size, "按钮行的两排一起出列")
        assertEquals(2L, buf.totalLines, "lineNo 逐排回退（EmueraConsole.cs:3013）")
        assertEquals(2L, buf.logicalLines, "按钮行首排计过的那个逻辑行同步回退（:3014-3020）")
        assertEquals("B", joined(buf.last()))
    }

    /**
     * 图形段与文本混排在同一条逻辑行（PRINT_IMG / HTML img 落到显示行的形态：图形占位段
     * 只是普通段的一种，宽度照常计格）且折成多排时，DELETELINE 删最后一行删的是**整条**
     * 逻辑行：图形排与文本排一起出列 —— `deleteLine` 不挑段类型
     * （spec: `EmueraConsole.cs:2999-3051`）。
     */
    @Test
    fun `graphic 行与文本混排的逻辑行删最后一行时整条出列`() {
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(10))
        // 图形占位段以等宽 ASCII 表达（本用例只关心删除粒度，宽度口径与段类型无关）：
        // 8 格图形段 + 8 格文本段 = 16 格，在宽 10 下折成「图形 + 文」与「文本尾」两排
        val mixed = RenderLine(
            runs = listOf(
                TextRun("########", 0xC0C0C0, 0x000000, 0, "f", kind = RunKind.PLAIN),
                TextRun("文本尾部", 0xC0C0C0, 0x000000, 0, "f"),
            ),
            isLogicalLine = true,
            isLineEnd = true,
        )
        sync.submit(listOf(renderLine("KEEP"), mixed))
        assertEquals(3, buf.size, "KEEP 1 排 + 混排行折 2 排（图形排 + 文本排）")
        assertEquals(3L, buf.totalLines)
        // DELETELINE 1：混排行整条出列
        sync.submit(listOf(renderLine("KEEP")))
        assertEquals(1, buf.size, "图形排与文本排一起出列，不许只删末排")
        assertEquals(1L, buf.totalLines)
        assertEquals(1L, buf.logicalLines)
        assertEquals("KEEP", joined(buf.last()))
    }

    /**
     * 变化点落在**折行续排**上（PRINTN 继续写入：head 条目不变、tail 条目变长）时，
     * 回撤与重放都要从那条逻辑行的**首排**开始：整条删掉、再从首排重放入列。
     * 只重放 tail 的两种坏形态 —— 要么 head 被连带删掉（按物理排数回撤一次就会整条删光，
     * 重放却只有 tail），要么 tail 被拼重（不回撤直接并入旧 tail）。
     * head 条目的 IsLineEnd 是折行默认 true、只有末排被写成 isLastLineEnd
     * （spec: `PrintStringBuffer.cs:183,193,206`），所以重放的 tail 不会并进 head。
     */
    @Test
    fun `变化点落在折行续排时从首排整条重放`() {
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(10))
        val head = renderLine("あああああ", lineEnd = true) // 10 格：恰在折行宽内
        val tail = renderLine("あ", logical = false, lineEnd = false)
        sync.submit(listOf(renderLine("KEEP"), head, tail))
        assertEquals(3, buf.size)
        assertEquals(3L, buf.totalLines)
        assertEquals(2L, buf.logicalLines, "KEEP 计 1；PRINTN 首排按折行默认 IsLineEnd 也计 1，末排未收尾不计")
        // 继续写入：head 条目不变、tail 条目变长
        val landed = sync.submit(
            listOf(renderLine("KEEP"), head, renderLine("あxx", logical = false, lineEnd = false)),
        )
        assertEquals(2, landed.size, "重放 head + tail 两条")
        assertEquals(3, buf.size)
        assertEquals(3L, buf.totalLines, "删 2 排、加 2 排：行号守恒（EmueraConsole.cs:3013 对 :2970）")
        assertEquals(2L, buf.logicalLines)
        assertEquals("KEEP", joined(buf[0L]))
        assertEquals("あああああ", joined(buf[1L]), "首排必须重放回来，不许被丢掉")
        assertEquals("あxx", joined(buf[2L]), "续排重放的是新内容，不许拼重")
    }

    @Test
    fun `appendMerged 复刻临时行先删与未断行拼接`() {
        val buf = LogBuffer(500)
        // 临时行占一个号；下一条正常行入列前先把它删掉（EmueraConsole.cs:2891-2893）。
        ConsoleAdapter.appendMerged(buf, listOf(row("t", lineEnd = true, temporary = true)))
        assertEquals(1, buf.size)
        assertEquals(1L, buf.totalLines)
        val landed = ConsoleAdapter.appendMerged(buf, listOf(row("A", lineEnd = true)))
        assertEquals(listOf("A"), landed.map { joined(it) })
        assertEquals(1, buf.size, "临时行被正常行顶掉")
        assertEquals(1L, buf.totalLines, "删号再取号 ⇒ 绝对行号不变")
        assertFalse(buf.last()!!.temporary)

        // 未断行的末行 + 新行 ⇒ 拼成一条，条数不增（EmueraConsole.cs:2947-2968）。
        // 返回值只收「仍然留在缓冲里」的行：B 被删掉换成 BC，所以 B 不在返回值中。
        val more = ConsoleAdapter.appendMerged(buf, listOf(row("B", lineEnd = false), row("C", lineEnd = true)))
        assertEquals(1, more.size, "两条入列、一条并入 ⇒ 被并掉的 B 不回报（:2954 的 deleteLine(1) 已把它摘出缓冲）")
        assertEquals("BC", joined(more[0]), "并入结果是一条行")
        assertEquals(2, buf.size)
        assertEquals(2L, buf.totalLines)
        assertEquals(2L, buf.logicalLines, "B 未收尾不计数，并入后的 BC 收尾计 1")
        assertEquals("BC", joined(buf[1L]))
        assertTrue(buf[1L]!!.isLogicalLine, "入列的是新行对象 ⇒ isLogicalLine 取新行（EmueraConsole.cs:2966,2969 + ConsoleDisplayLine.cs:12,45）")
        assertTrue(buf[1L]!!.isLineEnd, "isLineEnd 取新行（PrintStringBuffer.cs:183）")
        assertEquals(2, buf[1L]!!.cells, "cells = 旧行 + 新行（ShiftPositionX 后的首尾相接）")
    }

    /**
     * 并入行的四个标志**全部取新行** —— 入列的对象就是那个新行
     * （spec: `EmueraConsole.cs:2966` 的 `line.ChangeStr(array)` 只换 buttons 数组，
     * `:2969` 的 `displayLineList.Add(line)` 加的还是它；`IsLogicalLine`/`IsTemporary`
     * 只在构造时赋值，spec: `ConsoleDisplayLine.cs:12,14,45-46`）。
     * 上面那条 `appendMerged` 用例里旧行与新行的 `isLogicalLine` 都是 true，判不出这个分支，
     * 故这里给一条「旧行是折行续行（false）、新行是逻辑行首（true）」的判别用例。
     */
    @Test
    fun `并入行的四个标志全部取新行`() {
        val buf = LogBuffer(500)
        ConsoleAdapter.appendMerged(buf, listOf(row("old", logical = false, lineEnd = false)))
        assertEquals(0L, buf.logicalLines, "未收尾不计（EmueraConsole.cs:2971-2977）")
        val next = row("new", logical = true, lineEnd = true, temporary = false)
            .copy(align = Alignment.CENTER, indentCells = 7)
        val landed = ConsoleAdapter.appendMerged(buf, listOf(next))
        assertEquals(1, landed.size)
        assertEquals(1, buf.size)
        assertEquals("oldnew", joined(buf.last()))
        val merged = buf.last()!!
        assertTrue(merged.isLogicalLine, "取新行：IsLogicalLine 只读、只在构造时赋值（ConsoleDisplayLine.cs:12,45）")
        assertTrue(merged.isLineEnd, "取新行：一次 flush 只有末行被写成 isLastLineEnd（PrintStringBuffer.cs:183）")
        assertFalse(merged.temporary, "取新行（ConsoleDisplayLine.cs:14,46）")
        assertEquals(Alignment.CENTER, merged.align, "取新行的对齐（EmueraConsole.cs:2943）")
        assertEquals(7, merged.indentCells, "取新行的列偏移；旧行偏移的组合形态另见 mergedRow 的 TODO(verify)")
        assertEquals(6, merged.cells, "cells = 旧行 + 新行（ShiftPositionX 后的首尾相接，:2955-2966）")
        assertEquals(1L, buf.totalLines, "删号再取号 ⇒ 绝对行号不变（:2945 + :3013 + :2970）")
        assertEquals(1L, buf.logicalLines)
    }

    /**
     * 增量批入口：折行 + 入列全部委托适配层，**不比较、不回撤、不动快照记忆**
     * （装配层 `view/ConsoleScreen.submit` 走的就是这条，故并入规则只有一份）。
     */
    @Test
    fun `appendBatch 只做折行加并入且不动快照记忆`() {
        val buf = LogBuffer(500)
        val sync = ConsoleLogSync(buf, LineFolder(40))
        val landed = sync.appendBatch(listOf(renderLine("NN", lineEnd = false), renderLine("45")))
        assertEquals(1, landed.size, "两条入列、一条并入 ⇒ 落表的是拼接结果（EmueraConsole.cs:2947-2968）")
        assertEquals("NN45", joined(landed.single()))
        assertEquals(1, buf.size)
        assertEquals(1L, buf.totalLines)
        assertEquals(1L, buf.logicalLines)
        assertEquals(0, sync.publishedCount, "增量批不写快照记忆（两种喂法不许混在同一缓冲上）")
        // 折行的产物带着 ALIGNMENT 标记进缓冲，装配层因此不必自己算偏移
        val aligned = sync.appendBatch(listOf(renderLine("AB", align = Alignment.RIGHT)))
        assertEquals(38, aligned.single().indentCells, "RIGHT：40 - 2（ConsoleDisplayLine.cs:83）")
        assertEquals(2, aligned.single().cells, "偏移不计入内容宽度")
    }

    // ------------------------------------------------------------------
    // 接线⑥：firstIndex 与显示列表下标的换算（绝对行号是权威）
    // ------------------------------------------------------------------

    @Test
    fun `绝对行号是权威序号下标随淘汰整体前移`() {
        val buf = LogBuffer(500)
        repeat(530) { buf.append(row("L$it")) }
        assertEquals(500, buf.size)
        assertEquals(30L, buf.firstIndex, "firstIndex = totalLines - size = shift（EmueraConsole.cs:1865-1868）")
        assertEquals(530L, buf.totalLines)

        assertEquals(null, ConsoleAdapter.positionOf(buf, 29L), "已淘汰的号没有下标")
        assertEquals(0, ConsoleAdapter.positionOf(buf, 30L))
        assertEquals(499, ConsoleAdapter.positionOf(buf, 529L))
        assertEquals(null, ConsoleAdapter.positionOf(buf, 530L), "还没生成的号")

        assertEquals(30L, ConsoleAdapter.lineNoAt(buf, 0))
        assertEquals(529L, ConsoleAdapter.lineNoAt(buf, 499))
        assertEquals(null, ConsoleAdapter.lineNoAt(buf, 500))

        // 再追加 3 行 ⇒ 淘汰 3 行 ⇒ 同一个绝对行号对应的下标前移 3。
        repeat(3) { buf.append(row("N$it")) }
        assertEquals(33L, buf.firstIndex)
        assertEquals(496, ConsoleAdapter.positionOf(buf, 529L))
        assertEquals(499, ConsoleAdapter.positionOf(buf, 532L))
        assertEquals(529L, ConsoleAdapter.lineNoAt(buf, 496))
    }

    // ------------------------------------------------------------------
    // 接线⑦：绘制与命中两条取行口径
    // ------------------------------------------------------------------

    /**
     * C# 的绘制式 `(H-L)/L + 1` 与命中式 `H/L` **恒等**（EmueraConsole.cs:1805-1807 对 :2328-2331），
     * 本模型里绘制只多要一排**被画布完全裁掉**的行，故两支逐行等价：`draw.drop(1) == hit`。
     */
    @Test
    fun `绘制比命中多读最上面一排且其余逐行等价`() {
        val buf = LogBuffer(500)
        val vp = Viewport(heightLines = 4)
        repeat(30) { buf.append(row("L$it")) }
        vp.sync(buf)

        val hit = ConsoleAdapter.rowsForHit(vp, buf)
        val draw = ConsoleAdapter.rowsForDraw(vp, buf)
        assertEquals(4, hit.size, "命中口径 extraAbove = 0（EmueraConsole.cs:2328-2331）")
        assertEquals(5, draw.size, "绘制口径多要一排（EmueraConsole.cs:1805-1807）")
        assertEquals("L26", joined(hit.first()))
        assertEquals("L25", joined(draw.first()), "多要的那排在最上")
        assertEquals(hit.last(), draw.last())
        assertEquals(hit, draw.drop(1), "除最上面那排外逐行等值 —— C# 两式的 num3 恒等")
    }

    /**
     * 两个入口的**顶行下标**与取行必须出自同一对常量（`topRowForDraw` / `topRowForHit`
     * 对 `rowsForDraw` / `rowsForHit`）：装配层要「行窗口 + 它的行号基准」时用的就是这一对。
     * 手算依据：`num2 = Value - 1 = 30 - 1 = 29`（`EmueraConsole.cs:1786-1795`、贴底时 `Value == Maximum`
     * 且 `Maximum = Count`，`:2554`）；命中侧 `num3 = num2 - Height/LineHeight` 在本模型里写作
     * `num2 - heightLines + 1`（`:2328-2331`）⇒ 26；绘制侧再多要一排（`DRAW_EXTRA_ABOVE`，`:1805-1807`）⇒ 25。
     * C# 两式的 `num3` 恒等（`(H - L) / L + 1 == H / L`），所以这是**同一窗口多取一排**
     * 被裁掉的行，不是第二套可见口径：`rowsForHit == rowsForDraw.drop(1)`（行数不足一屏时两支整体相同）。
     */
    @Test
    fun `顶行基准与取行出自同一对常量`() {
        val buf = LogBuffer(500)
        val vp = Viewport(heightLines = 4)
        repeat(30) { buf.append(row("L$it")) }
        vp.sync(buf)
        val extra = ConsoleAdapter.DRAW_EXTRA_ABOVE - ConsoleAdapter.HIT_EXTRA_ABOVE
        assertEquals(29, vp.bottomRingIndex(), "num2 = Value - 1，贴底时 Value == Maximum == Count（:1786-1795、:2554）")
        val hitTop = ConsoleAdapter.topRowForHit(vp)
        val drawTop = ConsoleAdapter.topRowForDraw(vp)
        assertEquals(26, hitTop)
        assertEquals(25, drawTop)
        assertEquals(hitTop - extra, drawTop, "绘制基准比命中基准早 `DRAW - HIT` 行")
        assertEquals(26L, ConsoleAdapter.lineNoAt(buf, hitTop), "基准是保留区下标，行号由 lineNoAt 给出")
        assertEquals(25L, ConsoleAdapter.lineNoAt(buf, drawTop))
        assertEquals("L26", joined(ConsoleAdapter.rowsForHit(vp, buf).first()), "基准那一条就是取回来的第一行")
        assertEquals("L25", joined(ConsoleAdapter.rowsForDraw(vp, buf).first()))
        assertEquals(ConsoleAdapter.rowsForHit(vp, buf).size + extra, ConsoleAdapter.rowsForDraw(vp, buf).size)
        // 行数不足一屏时两个基准都被夹到 0（原版 `if (num3 < 0) num3 = 0`，:1808-1811），窗口重合
        val shortBuf = LogBuffer(500)
        val shortVp = Viewport(heightLines = 4)
        repeat(2) { shortBuf.append(row("S$it")) }
        shortVp.sync(shortBuf)
        assertEquals(0, ConsoleAdapter.topRowForDraw(shortVp))
        assertEquals(0, ConsoleAdapter.topRowForHit(shortVp))
        assertEquals(2, ConsoleAdapter.rowsForDraw(shortVp, shortBuf).size)
        assertEquals(ConsoleAdapter.rowsForHit(shortVp, shortBuf), ConsoleAdapter.rowsForDraw(shortVp, shortBuf))
    }
}
