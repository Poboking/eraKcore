package com.eratw.port.render.scroll

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.displaylist.DisplayList
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ScreenComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对拍族 ④「滚动 / 裁剪窗口」—— M6-RENDER-AUDIT。
 *
 * 逐条口径（C# 出处 → 本仓落地 → 结论）：
 *
 * | # | C# 出处 | 本仓落地 | 结论 |
 * |---|---|---|---|
 * | 1 | `EmueraConsole.cs:1788-1794` `GetLinePointY`：`num2 = Value - 1` 夹到 `Count - 1` | [Viewport.bottomRingIndex] | 一致 |
 * | 2 | `EmueraConsole.cs:1806-1807` 绘制 `num3 = num2 - ((Height - LineHeight) / LineHeight + 1)` | [ConsoleAdapter.DRAW_EXTRA_ABOVE]=1 → `topRingIndex` | **公式等价、论证错**（见下），行为等价 |
 * | 3 | `EmueraConsole.cs:2328-2331` 命中 `num3 = num2 - Height / LineHeight` | [ConsoleAdapter.HIT_EXTRA_ABOVE]=0 | 与第 2 条**同一个** `num3`（见下），本仓也落在同一行 |
 * | 4 | `EmueraConsole.cs:1808-1811`、`:2329-2332` `if (num3 < 0) num3 = 0` | `topRingIndex` 的 `coerce` | 一致 |
 * | 5 | `EmueraConsole.cs:1852` `for (i = num3; i <= num2 && i < Count; i++)` | `visibleRange` 的 `first..last` + `rowsAt` | 一致（闭区间，两端各夹一次） |
 * | 6 | `EmueraConsole.cs:2543-2567` `verticalScrollBarUpdate`：`num = count - Maximum`，`num > 0` 时 `Value += num`，`num < 0` 时抬到 `count` | [Viewport.sync] 的 `offsetFromEnd` 守恒 / **只夹取** | 增长一致；缩短**观感差异**（2026-10-29：本移植按折行后的显示行数计数，整屏重印会让它摆几行，抬到 `count` = 每个重印周期把上翻的读者扔回底部；取舍与出处见 Viewport 类文档「缩短不再拽回」，与第 7 条同一先例） |
 * | 7 | `MainWindow.cs:1276-1287` 滚轮 `Value += -sign(delta) * SmallChange * ScrollHeight`，夹在 `[Minimum, Maximum]` | [Viewport.onWheel] / `shift` 夹到 `[0, max(0, retainedLines - heightLines)]` | **观感差异**（M11-B：上界按平台最优收紧，不复刻 `Value == 0` 的整屏空，取舍见 Viewport 类文档；步长与下界仍一致，`ScrollHeight` 默认 1，`ConfigData.cs:74`） |
 * | 8 | `MainWindow.cs:1379-1398` PageUp/PageDown 固定 10 行 | [Viewport.PAGE_STEP] | 一致 |
 * | 9 | `MainWindow.cs:799-805`、`:1402-1406` 点击/按键回贴底 `Value = Maximum` | [Viewport.snapToBottom] | 一致 |
 * | 10 | `EmueraConsole.cs:2565` `ScrollBar.Enabled = count > 0` | [Viewport.scrollEnabled] | 一致 |
 * | 11 | `ConsoleDisplayLine.cs:112-116` 整行擦满 `Config.WindowX` | [DisplayList] 按内容列段擦（ADR-003） | **有意差异**（行级脏粒度收窄；列段取新旧并集覆盖残影） |
 * | 12 | `ConsoleStyledString.cs:138` 用 `PreserveGraphicsClipping` 由 GDI 裁右沿 | [DisplayList.prepare] 的 `min(start + cells, clientWidth)` | 一致（右沿裁剪；左沿裁整码点见其 KDoc） |
 *
 * ## 第 2/3 条：C# 的两个窗口**是同一个**（本仓论证错了，行为对）
 *
 * 令 `H = MainPicBox.Height`、`L = Config.LineHeight`，则
 * `(H - L) / L + 1 == H / L`（整数除法恒等式：`floor((H-L)/L) = floor(H/L) - 1`），
 * 所以 `:1807` 与 `:2328` 算出的 `num3` **逐值相同**；原版的「绘制比命中多读一行」
 * 并不存在，两处只是写法不同（一处用 `H - L` 再 `+1`，一处直接用 `H`）。
 * 本仓取 `DRAW_EXTRA_ABOVE = 1`、`HIT_EXTRA_ABOVE = 0`，于是绘制窗口比命中窗口
 * 多出**最上面一行**；这一行在 [ScreenComposer.rowTopPx] 下完全落在画布外
 * （`canvasHeight - rowCount * lineHeight` 起步，底沿 ≤ 0），所以**屏上可见的内容与
 * 命中窗口逐行相同**，行下标则由
 * [com.eratw.port.render.view.ConsoleScreen.hitRowToSnapshotRow] 的 `+1` 对齐。
 * 结论：可视行为等价，`ConsoleAdapter` / `ConsoleScreen` 三处 KDoc 的
 * 「绘制比命中多读一排」论证已按恒等式改写，`ConsoleAdapterTest` 的 `assertNotEquals`
 * 也换成 `rowsForDraw.drop(1) == rowsForHit` 的等价断言（M6-RENDER-VIEW 接线完成）；
 * 本用例继续钉住等价性读数与「溢出行完全在画布外」这条像素读数。
 *
 * 本用例用黄金基准的窗口（`pc-golden-root.txt:688-689`：`CLIENTHEIGHT=955`、
 * 语料行高 18）给出逐值读数：`955 / 18 = 53`，`(955-18)/18 + 1 = 53`，
 * `heightLines = 54`（含顶端被裁的那一行），故 C# 的 `num3 = num2 - 53`。
 */
class ViewportAuditTest {

    private fun row(i: Int): DisplayRow = DisplayRow(
        runs = listOf(TextRun("L$i", fore = 0xC0C0C0, back = 0x000000, styleMask = 0, fontName = "黑体")),
        cells = ("L$i").length,
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun buffer(n: Int): LogBuffer {
        val b = LogBuffer(Grid.MAX_LOG_DEFAULT)
        for (i in 0 until n) b.append(row(i))
        return b
    }

    /** 第 2/3 条前提：C# 两式逐值恒等（伪造「绘制多读一行」的算式不成立）。 */
    @Test
    fun `C# 绘制与命中的起行公式恒等`() {
        for (h in listOf(18, 19, 36, 54, 100, 954, 955, 960)) {
            for (l in listOf(10, 18, 19, 20)) {
                if (h < l) continue
                assertEquals(h / l, (h - l) / l + 1, "H=$h L=$l")
            }
        }
        assertEquals(53, 955 / 18)
        assertEquals(53, (955 - 18) / 18 + 1)
    }

    /** 第 2/3/5 条：绘制窗口 = 命中窗口 + 一条完全裁掉的行；两者文本逐行相同。 */
    @Test
    fun `绘制窗口多出的那一行完全在画布外`() {
        val buffer = buffer(100)
        val viewport = Viewport(heightLines = 54)
        viewport.sync(buffer)

        val draw = ConsoleAdapter.rowsForDraw(viewport, buffer)
        val hit = ConsoleAdapter.rowsForHit(viewport, buffer)
        assertEquals(55, draw.size)
        assertEquals(54, hit.size)
        assertEquals(hit, draw.drop(1))
        // C# num2 = Value-1 = 99，num3 = 99-53 = 46
        assertEquals(99, viewport.bottomRingIndex())
        assertEquals(46, ConsoleAdapter.topRowForHit(viewport))
        assertEquals(46, ConsoleAdapter.topRowForDraw(viewport) + 1)

        // 多出的那一行（绘制窗口下标 0）在行高 18 的画布 972px 上完全不可见
        val composer = ScreenComposer(
            clientWidthCells = 200,
            heightLines = 54,
            metrics = CellMetrics(18, 18),
            canvasHeightPx = 972,
        )
        val top = composer.rowTopPx(0, draw.size)
        assertEquals(-18, top)
        assertTrue(top + composer.metrics.lineHeight <= 0, "溢出行必须整行在画布外：top=$top")
        // 第一条可见行与命中窗口第 0 行同高
        assertEquals(0, composer.rowTopPx(1, draw.size))

        // 真机口径（画布高 = CLIENTHEIGHT = 955，pc-golden-root.txt:688）逐像素对齐原版：
        // C# 的起绘 y = (H - L) - (num2 - num3) * L = 937 - 53*18 = -17，
        // 即顶端那行**露出 1 像素**；本仓绘制窗口的 row 1 正好压在 -17，row 0 落在 -35（全裁）。
        val real = ScreenComposer(
            clientWidthCells = 200,
            heightLines = 54,
            metrics = CellMetrics(18, 18),
            canvasHeightPx = 955,
        )
        assertEquals(-17, real.rowTopPx(1, draw.size))
        assertEquals(937, real.rowTopPx(54, draw.size))
        assertEquals(-35, real.rowTopPx(0, draw.size))
    }

    /** 第 1/6/7 条：滚轮上翻后 `Value` 与 `num2` 的逐值换算。 */
    @Test
    fun `上翻十行后的行号换算`() {
        val viewport = Viewport(heightLines = 54)
        viewport.sync(buffer(100))
        assertEquals(100, viewport.retainedLines)
        assertEquals(100, viewport.scrollBarValue())
        assertTrue(viewport.atBottom)

        viewport.onWheel(delta = 1)
        assertEquals(1, viewport.offsetFromEnd)
        assertEquals(99, viewport.scrollBarValue())
        assertEquals(98, viewport.bottomRingIndex())

        viewport.pageUp()
        assertEquals(11, viewport.offsetFromEnd)
        assertEquals(89, viewport.scrollBarValue())
        assertEquals(88, viewport.bottomRingIndex())
        assertEquals(35, viewport.topRingIndex()) // 88 - 54 + 1
        // 命中与绘制窗口的基准差恰好一行
        assertEquals(viewport.topRingIndex() - 1, viewport.topRingIndex(extraAbove = 1))
    }

    /** 第 7/8 条：步长夹取 —— 上翻到顶显示保留区最早的一屏（M11-B 起不再复刻 `Value == 0` 的空屏）。 */
    @Test
    fun `上翻到底显示最早的一屏`() {
        val buffer = buffer(100)
        val viewport = Viewport(heightLines = 54)
        viewport.sync(buffer)
        viewport.pageUp(1000)
        // 上界 = max(0, retainedLines - heightLines) = 100 - 54（取舍见 Viewport 类文档「能上翻到哪里」；
        // 原版 Value == 0 ⇒ num2 = -1 ⇒ 整屏不画一行，EmueraConsole.cs:1806-1812、:1852，观感缺陷不复刻）
        assertEquals(46, viewport.offsetFromEnd)
        assertEquals(54, viewport.scrollBarValue())
        assertEquals(53, viewport.bottomRingIndex())
        assertEquals(0, viewport.topRingIndex())
        assertEquals(0L..53L, viewport.visibleRange(buffer))
        assertEquals(54, viewport.rows(buffer).size)
        assertEquals("L0", viewport.rows(buffer).first().runs.first().text, "屏上最早一行 = 保留区首行")
        assertFalse(viewport.atBottom)
        // 再往下滚只回到贴底，不会越过
        viewport.pageDown(1000)
        assertEquals(0, viewport.offsetFromEnd)
        assertTrue(viewport.atBottom)
    }

    /** 第 6 条：行数变多时偏移守恒（`Value += num`）；变少时**只夹取、不归零**（本仓的观感取舍）。 */
    @Test
    fun `增长守恒偏移且缩短只夹取不归零`() {
        val viewport = Viewport(heightLines = 10)
        viewport.sync(50)
        viewport.scrollUp(3)
        assertEquals(3, viewport.offsetFromEnd)

        viewport.sync(60)
        assertEquals(3, viewport.offsetFromEnd)
        assertEquals(57, viewport.scrollBarValue())
        assertEquals(56, viewport.bottomRingIndex())

        // 原版这一支把 Value 抬到 count（= 贴底，EmueraConsole.cs:2557-2564）；本移植保留玩家的
        // 距底偏移，只在超出 maxOffset 时夹取 —— 取舍见 Viewport 类文档「缩短不再拽回」。
        viewport.sync(55)
        assertEquals(3, viewport.offsetFromEnd, "缩短不得把上翻的读者扔回底部")
        assertEquals(52, viewport.scrollBarValue())
        assertFalse(viewport.atBottom)

        // 剩下的行不够一屏 ⇒ maxOffset 归 0，夹取自然回到唯一合法的贴底态。
        viewport.sync(11)
        assertEquals(1, viewport.offsetFromEnd, "55→11 后余量只剩 11-10=1 行")
        viewport.sync(8)
        assertEquals(0, viewport.offsetFromEnd, "不足一屏 ⇒ 贴底")
        assertTrue(viewport.atBottom)

        viewport.sync(0)
        assertEquals(0, viewport.retainedLines)
        assertFalse(viewport.scrollEnabled)
        assertEquals(-1, viewport.bottomRingIndex())
    }

    /** 第 10 条：清屏回初始态（`ClearDisplay` 末尾的 `verticalScrollBarUpdate`）。 */
    @Test
    fun `清空后回到贴底初始态`() {
        val viewport = Viewport(heightLines = 10)
        viewport.sync(80)
        viewport.scrollUp(5)
        viewport.onCleared()
        assertEquals(0, viewport.retainedLines)
        assertEquals(0, viewport.offsetFromEnd)
        assertTrue(viewport.atBottom)
        assertFalse(viewport.scrollEnabled)
    }

    /** 第 11/12 条：显示列表按内容列段裁右沿，行下标与绘制窗口同域。 */
    @Test
    fun `显示列表右沿裁到 clientWidth`() {
        val list = DisplayList(clientWidth = 10, maxRows = 8)
        val wide = DisplayRow(
            runs = listOf(TextRun("0123456789AB", fore = 0xFFFFFF, back = 0, styleMask = 0, fontName = "黑体")),
            cells = 12,
            isLogicalLine = true,
            isLineEnd = true,
        )
        val rects = list.submit(listOf(wide))
        assertEquals(1, rects.size)
        assertEquals(0, rects[0].rowStart)
        assertEquals(1, rects[0].rowEnd)
        assertEquals(0, rects[0].colStart)
        assertEquals(10, rects[0].colEnd)
        val cmds = list.render()
        assertEquals(1, cmds.size)
        assertEquals(0, cmds[0].colStart)
        assertEquals(10, cmds[0].colEnd)
        // 右侧多出的两个码点不在这里丢（由绘制侧在 clientWidthCells 处停笔），
        // 与 GDI 的 PreserveGraphicsClipping 同形（ConsoleStyledString.cs:138）。
        assertEquals(12, cmds[0].glyphs.size)
        // 幂等：同一快照再提交返回空脏区
        assertTrue(list.submit(listOf(wide)).isEmpty())
    }

    /** 空视口下取行返回空表而不是抛（`num3`/`num2` 的越界组合）。 */
    @Test
    fun `空缓冲取行为空`() {
        val viewport = Viewport(heightLines = 54)
        val empty = LogBuffer(10)
        viewport.sync(empty)
        assertTrue(ConsoleAdapter.rowsForDraw(viewport, empty).isEmpty())
        assertTrue(ConsoleAdapter.rowsForHit(viewport, empty).isEmpty())
        assertNull(ConsoleAdapter.lineNoAt(empty, 0))
        assertEquals(0, ConsoleAdapter.topRowForDraw(viewport))
        assertEquals(0, ConsoleAdapter.topRowForHit(viewport))
    }
}
