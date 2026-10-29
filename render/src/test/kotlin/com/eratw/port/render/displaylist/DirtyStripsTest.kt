package com.eratw.port.render.displaylist

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.Grid
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 行段（最小刷新单元）与两级脏区口径（W8-RENDERPERF）。
 *
 * 期望值全部手算，不拿实现回读：
 * - 整行带的列跨度 = 带内「新旧两侧列段」的并集（`DisplayList.submit` 的 KDoc，
 *   依据 spec: `ConsoleDisplayLine.cs:112-116` 的整行擦 + `ConsoleButtonString.cs:307-315` 的位移只动 PointX）；
 * - 行段的列跨度 = **该行**新旧列段的并集，格子数不因合并而膨胀；
 * - 合并判据：`并集格数 × 行数 <= 各行自身格数之和`（零浪费才算赚），
 *   逐例手算见每条的注释。
 */
class DirtyStripsTest {

    private fun run(text: String, fore: Int = 0x112233) =
        TextRun(text, fore, 0xFFFFFF, 0, "sarasa", com.eratw.port.render.RunKind.TEXT)

    private fun row(text: String, indent: Int = 0, align: Alignment = Alignment.LEFT) =
        DisplayRow(
            runs = listOf(run(text)),
            cells = Grid.cells(text),
            isLogicalLine = true,
            isLineEnd = true,
            align = align,
            indentCells = indent,
        )

    /** 首次提交：整行带仍是一条（既有口径），行段按「一行一段」拆开。 */
    @Test
    fun `首次提交的行段逐行给出且合并不膨胀`() {
        val dl = DisplayList(clientWidth = 40)
        // "aaaa" 4 格、"b".repeat(40) 40 格：并集 40 格 × 2 行 = 80 格 > 4 + 40 = 44 格 ⇒ 不合并。
        val rects = dl.submit(listOf(row("aaaa"), row("b".repeat(40))))
        assertEquals(listOf(DirtyRect(0, 2, 0, 40)), rects, "整行带仍是既有口径：带内并集")
        val strips = dl.dirtyStrips()
        assertEquals(
            listOf(DirtyRect(0, 1, 0, 4), DirtyRect(1, 2, 0, 40)),
            strips,
            "宽行不许把窄行的擦除宽度拖到 40 格",
        )
        // 行段覆盖的行集合与整行带一致（同一批脏标记的两种分组）。
        assertEquals(rects.flatMap { it.rowStart until it.rowEnd }, strips.flatMap { it.rowStart until it.rowEnd })
    }

    /** 等宽相邻行：并集格数 × 行数 == 各行之和，零浪费 ⇒ 合成一条。 */
    @Test
    fun `等宽相邻行零浪费时合成一条行段`() {
        val dl = DisplayList(clientWidth = 40)
        dl.submit(listOf(row("aaaa"), row("bbbb"), row("cccc")))
        val strips = dl.dirtyStrips()
        assertEquals(listOf(DirtyRect(0, 3, 0, 4)), strips, "3 行 × 4 格 = 12 格 == 4 + 4 + 4")
    }

    /** 中间夹一条更宽的行：前后两段各自抱团，宽行单独一段（谁都不多擦一格）。 */
    @Test
    fun `宽行切断零浪费合并`() {
        val dl = DisplayList(clientWidth = 40)
        dl.submit(listOf(row("aaaa"), row("b".repeat(30)), row("cccc")))
        val strips = dl.dirtyStrips()
        assertEquals(listOf(DirtyRect(0, 1, 0, 4), DirtyRect(1, 2, 0, 30), DirtyRect(2, 3, 0, 4)), strips)
    }

    /**
     * 对齐位移把同一行挪了列段：行段必须同时覆盖旧列段与新列段（否则残影）。
     * 手算：`abcd` 占 4 格，基准 20 格宽，CENTER 位移 `20/2 - 4/2 = 8`
     * （spec: `ConsoleDisplayLine.cs:80`）；旧列段 `[0,4)`、新列段 `[8,12)` ⇒ 并集 `[0,12)`。
     */
    @Test
    fun `改对齐时行段覆盖新旧两列段`() {
        val dl = DisplayList(clientWidth = 20)
        dl.submit(listOf(row("abcd")))
        assertEquals(listOf(DirtyRect(0, 1, 0, 4)), dl.dirtyStrips())
        dl.submit(listOf(row("abcd", indent = 8, align = Alignment.CENTER)))
        assertEquals(
            listOf(DirtyRect(0, 1, 0, 12)),
            dl.dirtyStrips(),
            "只擦新列段会把 0..4 的旧字留在屏上",
        )
    }

    /** 无变化的帧：行段为空表，且反复取到的是同一个空表实例（稳态帧不分配）。 */
    @Test
    fun `无脏行时行段为空且重复取值不新建`() {
        val dl = DisplayList(clientWidth = 20)
        val snapshot = listOf(row("abc"))
        dl.submit(snapshot)
        assertTrue(dl.dirtyStrips().isNotEmpty())
        assertEquals(emptyList<DirtyRect>(), dl.submit(snapshot))
        val first = dl.dirtyStrips()
        assertEquals(emptyList<DirtyRect>(), first)
        assertSame(first, dl.dirtyStrips(), "同一次提交内行段只算一次（缓存）")
    }

    /** 同一次提交内重复取值命中缓存；下一次提交后按新结果重算。 */
    @Test
    fun `行段缓存的有效期到下一次提交为止`() {
        val dl = DisplayList(clientWidth = 20)
        dl.submit(listOf(row("aa")))
        val first = dl.dirtyStrips()
        assertSame(first, dl.dirtyStrips())
        dl.submit(listOf(row("aa"), row("bb")))
        val second = dl.dirtyStrips()
        assertEquals(listOf(DirtyRect(1, 2, 0, 2)), second, "只有新行脏")
        assertTrue(first !== second)
    }

    /** 整屏清空：每个旧行各一条行段，列段取被擦掉内容的宽度。 */
    @Test
    fun `清屏时逐行擦掉旧内容`() {
        val dl = DisplayList(clientWidth = 40)
        dl.submit(listOf(row("a"), row("bb中"), row("c")))
        val rects = dl.submit(emptyList())
        assertEquals(listOf(DirtyRect(0, 3, 0, 4)), rects, "整行带仍是既有口径")
        assertEquals(
            listOf(DirtyRect(0, 1, 0, 1), DirtyRect(1, 2, 0, 4), DirtyRect(2, 3, 0, 1)),
            dl.dirtyStrips(),
            "行段按行给，擦除量 = 3 行的内容格数",
        )
    }

    /** 行数超上限后头部丢行导致整屏位移：每一行都脏，行段仍按「零浪费」分组。 */
    @Test
    fun `超上限位移后整屏脏且行段按内容跨度分组`() {
        val dl = DisplayList(clientWidth = 20, maxRows = 4)
        dl.submit((0 until 5).map { row("line$it") })
        val rects = dl.submit((0 until 6).map { row("line$it") })
        assertEquals(4, dl.rowCount)
        assertEquals(listOf(DirtyRect(0, 4, 0, 5)), rects, "行下标整体前移 ⇒ 整屏一条带")
        assertEquals(listOf(DirtyRect(0, 4, 0, 5)), dl.dirtyStrips(), "4 行 × 5 格 = 20 格 == 4 × 5，零浪费")
    }

    /** `renderInto` 与 `render(rects)` 结果逐项相同（零分配版本不许换口径）。 */
    @Test
    fun `renderInto与render逐项相同`() {
        val dl = DisplayList(clientWidth = 40)
        dl.submit(listOf(row("aaa"), row("bbb"), row("ccc")))
        val rects = dl.submit(listOf(row("aaa"), row("B"), row("ccc")))
        assertEquals(1, rects.size)
        val out = ArrayList<DrawCmd>()
        dl.renderInto(rects, out)
        assertEquals(dl.render(rects), out.toList())
        // 全量版本同理。
        dl.renderInto(out)
        assertEquals(dl.render(), out.toList())
        // 空脏区不产出命令，且缓冲被清空。
        dl.renderInto(emptyList(), out)
        assertTrue(out.isEmpty())
    }

    /** 行段与整行带都不许越出保留列表；越界的 rects 由 `renderInto` 裁掉。 */
    @Test
    fun `越界脏区按保留列表裁剪`() {
        val dl = DisplayList(clientWidth = 20)
        dl.submit(listOf(row("aa")))
        val out = ArrayList<DrawCmd>()
        dl.renderInto(listOf(DirtyRect(0, 99, 0, 2)), out)
        assertEquals(1, out.size)
        dl.renderInto(listOf(DirtyRect(5, 9, 0, 2)), out)
        assertTrue(out.isEmpty())
    }
}
