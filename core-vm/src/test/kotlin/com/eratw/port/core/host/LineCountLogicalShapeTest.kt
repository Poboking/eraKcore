package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUD-h3-linecount 的正钉：显示行的「行尾标志」形状与 LINECOUNT 计数门与原版逐值同判。
 *
 * ## 两条判式（各带 C# 点位）
 *
 * 1. **形状**：一次 flush 折出的每一排都带 `IsLineEnd = true`（构造缺省：字段初值
 *    `ConsoleDisplayLine.cs:16`、形参缺省 `:32`、赋值 `:47`；建排的三处
 *    `PrintStringBuffer.cs:193` → `:211/:242/:261` 都不传 `lineEnd`），
 *    `Flush` 只单独改写**末排** `array[^1].IsLineEnd = isLastLineEnd`（`:183`）。
 *    移植侧曾经把「逻辑行未完」抄到每一排上（续排 `isLineEnd=false`），方向与原版相反。
 * 2. **计数门**：`EmueraConsole.addDisplayLine` 的两层 if ——
 *    `if (line.IsLogicalLine) { if (displayLineList[^1].IsLineEnd) logicalLineCount++ }`
 *    （`:2971-2977`，`:2969` 的 `Add` 之后表尾就是刚入列的这条）；
 *    删除侧同样把扣数**嵌在** `IsLogicalLine` 里面（`:3014-3021`）。
 *
 * ## 语料侧为什么要它
 *
 * `LINECOUNT` 在 4.980 语料里读点 733 处 / 139 文件，其中「快照 LINECOUNT → 打印一块 →
 * `CLEARLINE LINECOUNT - 快照`」的页面重画环 124 文件（例：
 * `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:341/:343`）。计数门少一个 `isLogicalLine`
 * 条件，一块按 `<br>` 切成 k 排的 `HTML_PRINT` 就多计 k-1 枚，`CLEARLINE` 于是删过头；
 * 玩家侧对同一条的表述写在 `ERB/fromEN/HTML_TALENTS/HTML_TALENTS.ERB:41`
 * 的注释里（br 不往 LINECOUNT 上加）。
 */
class LineCountLogicalShapeTest {

    private fun sink(width: Int = 10, maxLines: Int = 100) =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines)

    // ------------------------------------------------------------------
    // 1. 形状：折行的中间排带构造缺省的行尾标志
    // ------------------------------------------------------------------

    /**
     * 宽 4 的三排折行（超宽 PRINTL）：前两排都带 `isLineEnd=true`（构造缺省），
     * 末排由 `Flush` 写成 `isLastLineEnd`（PrintStringBuffer.cs:183）= true。
     * LINECOUNT 只 +1（计数门只认组内首排，:2971-2977）。
     */
    @Test
    fun everyFoldedRowCarriesLineEndExceptTailOverwrite() {
        val s = sink(width = 4)

        s.print("abcdefghij") // 10 半角 → abcd / efgh / ij
        s.newLine()

        val rows = s.snapshot()
        assertEquals(3, rows.size, "夹具自检：折成三排")
        assertTrue(rows[0].isLogicalLine)
        assertFalse(rows[1].isLogicalLine)
        assertFalse(rows[2].isLogicalLine)
        assertTrue(rows[0].isLineEnd, "组内首排：构造缺省 true（ConsoleDisplayLine.cs:16）")
        assertTrue(rows[1].isLineEnd, "折行中间排同样是缺省 true —— 原版从不把它写成 false")
        assertTrue(rows[2].isLineEnd, "末排被 Flush 写成 isLastLineEnd（PRINTL ⇒ true）")
        assertEquals(1L, s.lineCount, "三排一个逻辑行（首排 ∧ 行尾才计）")
        assertEquals(3, s.lineNo, "折行的每一排各占一个行号（:2970）")
    }

    /**
     * 未收尾的一折（PRINTN 超宽）：末排 `isLineEnd=false`，**首排仍为 true** ⇒
     * 待决窗口里原版 LINECOUNT = 1（这一枚是本格形状的直接后果，不许「修好」成 0）。
     */
    @Test
    fun pendingFoldKeepsHeadRowLineEndTrue() {
        val s = sink(width = 4)

        s.print("abcdefghij", lineEnd = false)
        s.newLine()

        val rows = s.snapshot()
        assertEquals(3, rows.size)
        assertTrue(rows[0].isLineEnd, "首排带缺省 true ⇒ :2971-2977 会计这一枚")
        assertTrue(rows[1].isLineEnd)
        assertFalse(rows[2].isLineEnd, "只有末排被写成 isLastLineEnd=false（PrintStringBuffer.cs:183）")
        assertEquals(1L, s.lineCount)
    }

    // ------------------------------------------------------------------
    // 2. 计数门：一次 flush 组 = 一个逻辑行
    // ------------------------------------------------------------------

    /**
     * `HTML_PRINT` 直印按 `<br>` 切出的 k 排同属一个 flush 组
     * （`HtmlManager.cs:698` → `ButtonsToDisplayLines`，`firstLine` 只在首排为真，
     * PrintStringBuffer.cs:206/:212/:242/:261）⇒ LINECOUNT 只 +1，
     * 而显示行数照旧是 k（GETDISPLAYLINE / HTML_GETPRINTEDSTR 读的是排，不受标志影响）。
     */
    @Test
    fun multiRowHtmlCountsOneLogicalLineAndKeepsDisplayRows() {
        val s = sink()

        s.printLine("BEFORE")
        s.printHtml("A<br>B<br>C<br>D", false)

        assertEquals(5, s.displayLineCount, "一条脚本行 + HTML 四排：显示行不缩")
        assertEquals(2L, s.lineCount, "两个逻辑行（BEFORE 一条、整块 HTML 一条）")
        assertEquals(
            listOf("BEFORE", "A", "B", "C", "D"),
            s.snapshot().map { s.textOf(it) },
            "行序与文本逐字不变（只改标志形状）",
        )
        assertTrue(s.snapshot().drop(1).all { it.isLineEnd }, "HTML 组的每一排都带缺省 true")
        assertFalse(s.snapshot()[2].isLogicalLine)
    }

    /**
     * 语料的页面重画环（`TEMP_CHARA_LIST_BY_DT.ERB:341/:343` 同形）：
     * 快照 LINECOUNT → 打印一块多排 HTML → `CLEARLINE LINECOUNT - 快照`。
     * delta 必须恰为 1，且 CLEARLINE 1 恰好摘掉整块、不多删 preceding 内容
     * （旧计数下 delta=4 ⇒ CLEARLINE 4 会连删四个逻辑行 = 删过头）。
     */
    @Test
    fun corpusClearlineDeltaErasesExactlyOneHtmlBlock() {
        val s = sink()

        s.printLine("HEADER")
        val snapshot = s.lineCount
        s.printHtml("A<br>B<br>C<br>D", false)

        val delta = s.lineCount - snapshot
        assertEquals(1L, delta, "一块 HTML = 一个逻辑行（原版同判）")

        s.deleteLine(delta.toInt())

        assertEquals(listOf("HEADER"), s.snapshot().map { s.textOf(it) }, "整块四排一起出列，HEADER 保留")
        assertEquals(snapshot, s.lineCount, "扣嵌在 isLogicalLine 里面 ⇒ 一个逻辑行只扣 1（:3014-3021）")
        assertEquals(1, s.lineNo, "五排入列、四排出列（:2970 对 :3013）")
    }

    /**
     * 删除侧的嵌套判据（:3014-3021 的 `if (IsLogicalLine) { num++; if (IsLineEnd) … }`）：
     * 三排的折行整条出列时 LINECOUNT 只回退 1。并列写法会扣 3（每一排都带行尾标志）。
     */
    @Test
    fun deletingFoldedLogicalLineRefundsExactlyOne() {
        val s = sink(width = 4)

        s.print("abcdefghij")
        s.newLine()
        assertEquals(1L, s.lineCount)

        s.deleteLine(1)

        assertEquals(0, s.snapshot().size, "三排一起出列")
        assertEquals(0L, s.lineCount, "只扣一枚：扣数嵌在组内首排判定里")
        assertEquals(0, s.lineNo)
    }

    /**
     * 未收尾的折行被删：它计过 1（组内首排带行尾标志），删除时也要退掉那 1
     * —— 与「并入时 deleteLine(1) 先扣回首排那枚」是同一处判据（:2954 + :3007-3021）。
     */
    @Test
    fun deletingPendingFoldRefundsTheHeadRow() {
        val s = sink(width = 4)

        s.print("abcdefghij", lineEnd = false)
        s.newLine()
        assertEquals(1L, s.lineCount, "待决窗口内计 1（原版同判）")

        s.deleteLine(1)

        assertEquals(0, s.snapshot().size)
        assertEquals(0L, s.lineCount, "末排不扣（isLogicalLine=false），首排扣回那枚")
    }

    /**
     * 稳态等式：n 条脚本行 + m 块多排 HTML ⇒ LINECOUNT = n + m，
     * 与显示行数（n + m·k）脱钩。逐值来自 :2971-2977 的「组内首排 ∧ 行尾」。
     */
    @Test
    fun steadyStateEqualsLogicalGroupsNotDisplayRows() {
        val s = sink(width = 40)

        repeat(3) { s.printLine("L$it") }
        repeat(2) { s.printHtml("X<br>Y<br>Z", false) }

        assertEquals(9, s.displayLineCount, "3 条脚本行 + 2 块各三排")
        assertEquals(5L, s.lineCount, "3 + 2 个逻辑行")
    }
}
