package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * [LineBufferConsoleSink] 作为 M4 行数据源的接口测试。
 *
 * 只测**数据源形状与不变量**（快照独立性、按钮表摊平、段列偏移、样式位、宽度模型）；
 * 文字流语义（PRINTN 合并、折行、删除行、PRINTC 补齐）已由 [LineBufferConsoleSinkTest]
 * 锁定，此处不重复。
 *
 * 期望值来源：
 * - 列/宽度：`CellWidth` 的半角 1 / 全角 2（口径 = CP932 字节数，逐点依据见本文件
 *   `cellWidthFollowsSjisByteCountNotEastAsianWidth`；`core/fn` 的三处读数已收口到同一张表，
 *   由 `core/fn/FnWidthSingleSourceTest` 钉住）；
 * - 分段与样式位：ConsoleStyledString 构造时固定样式（ConsoleStyledString.cs:26-43）、
 *   样式位 1=粗体 2=斜体 4=删除线 8=下划线（Process.cs:1508-1530）；
 * - 按钮跨行：PrintStringBuffer.cs:222-249（每行各一条、value 相同、行内列）。
 */
class ConsoleDataSourceTest {

    /** 增补平面示例字符 U+20000（不在 `CellWidth` 的两个半角段里 ⇒ 2 格；UTF-16 两个单元，按码位只计一次）。 */
    private val astral = String(Character.toChars(0x20000))

    // ------------------------------------------------------------------
    // lines()：稳定只读视图
    // ------------------------------------------------------------------

    /**
     * `lines()` 返回**浅拷贝**：之后的输出不会回写已取出的列表；
     * 元素 [LineBufferConsoleSink.DisplayLine] 是不可变 data class，可直接跨线程读。
     */
    @Test
    fun linesAreStableReadOnlyCopies() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a")
        s.newLine()
        val first = s.lines()

        s.print("b")
        s.newLine()

        assertEquals(1, first.size, "已取出的快照不应看到后续输出")
        assertEquals("a", s.textOf(first[0]))
        assertEquals(2, s.lines().size)
        assertEquals(2, s.displayLineCount)
        assertNotSame(first, s.lines(), "每次返回新列表，调用方无法改写 sink 内部状态")
    }

    /** `displayLineCount` 数**显示行**（含折行续行），`lineCount` 数**逻辑行**（LINECOUNT 口径）。 */
    @Test
    fun displayLineCountCountsPhysicalRowsWhileLineCountCountsLogicalRows() {
        val s = LineBufferConsoleSink(clientWidth = 4)

        s.print("abcdef") // 6 格 → 4 + 2 两条显示行、一个逻辑行
        s.newLine()

        assertEquals(2, s.displayLineCount)
        assertEquals(1L, s.lineCount)
        assertEquals(listOf("abcd", "ef"), s.loggedLines())
    }

    /**
     * 临时行（REUSELASTLINE）**进** [LineBufferConsoleSink.lines]（要画），
     * **不进** [LineBufferConsoleSink.loggedLines]（原版日志回放不含 temporary 行，
     * EmueraConsole.cs:3053-3056）。
     */
    @Test
    fun temporaryLineIsVisibleToRendererButNotToLogReplay() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("base")
        s.newLine()
        s.printTemporaryLine("tmp")

        assertEquals(2, s.lines().size)
        assertTrue(s.lines()[1].isTemporary)
        assertEquals("tmp", s.textOf(s.lines()[1]))
        assertEquals(listOf("base"), s.loggedLines(), "临时行不进日志文本")
        assertEquals(2L, s.lineCount, "临时行仍占一条 LINECOUNT（原版 PrintSingleLine 计行）")
    }

    /** 空 sink 的行源为空；空缓冲 newLine 产生一个空格行（EmueraConsole.cs:3379-3382）。 */
    @Test
    fun emptySinkHasNoLinesAndBlankNewLineProducesSpaceRow() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        assertTrue(s.lines().isEmpty())
        assertTrue(s.buttons().isEmpty())
        assertEquals(0, s.displayLineCount)

        s.newLine()

        assertEquals(1, s.displayLineCount)
        assertEquals(listOf(" "), s.loggedLines())
    }

    // ------------------------------------------------------------------
    // buttons()：摊平按钮表
    // ------------------------------------------------------------------

    /**
     * 按钮表按（行号，列号）摊平；PRINTN 合并时列号随并入段的宽度整体右移
     * （EmueraConsole.cs:2948-2977 的并入 + 本类 [LineBufferConsoleSink] 的 mergeRows 位移）。
     */
    @Test
    fun buttonsFlattenWithLineIndexAndShiftAfterPrintnMerge() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.printButton("AB", 1L)
        s.newLine()
        s.print("x", false)
        s.newLine()
        s.printButton("CD", 2L)
        s.newLine()

        assertEquals(2, s.displayLineCount, "PRINTN 未结行与下一批合并成一条显示行")
        assertEquals("xCD", s.textOf(s.lines()[1]))
        assertEquals(
            listOf(
                LineBufferConsoleSink.PlacedButton(lineIndex = 0, startCol = 0, endCol = 2, value = 1L),
                LineBufferConsoleSink.PlacedButton(lineIndex = 1, startCol = 1, endCol = 3, value = 2L),
            ),
            s.buttons(),
            "并入 1 格后按钮列从 [0,2) 右移到 [1,3)",
        )
    }

    /** 跨行按钮在每行各一条、value 相同、列号是该行内列（PrintStringBuffer.cs:222-249）。 */
    @Test
    fun buttonSplitAcrossRowsAppearsOncePerRowWithSameValue() {
        val s = LineBufferConsoleSink(clientWidth = 10)

        s.print("aaaaaaaaa") // 9 格
        s.printButton("abc", 7L)
        s.newLine()

        assertEquals(2, s.displayLineCount)
        assertEquals(
            listOf(
                LineBufferConsoleSink.PlacedButton(0, 9, 10, 7L),
                LineBufferConsoleSink.PlacedButton(1, 0, 2, 7L),
            ),
            s.buttons(),
        )
    }

    /** 字符串按钮与数值按钮共用一张表，值类型原样保留（INPUTBUTTON/INPUTBUTTONS 分流靠它）。 */
    @Test
    fun buttonTableKeepsValueTypesDistinct() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.printButton("A", 5L)
        s.printButton("B", "five")
        s.newLine()

        val values = s.buttons().map { it.value }
        assertEquals(listOf<Any>(5L, "five"), values)
        assertEquals(listOf(0, 1), s.buttons().map { it.startCol })
    }

    // ------------------------------------------------------------------
    // segmentsOf / widthOf：段列偏移
    // ------------------------------------------------------------------

    /**
     * 段列偏移按 [CellWidth] 累加：全角 2 格、半角 1 格。
     * 段宽不等于 `text.length`（"ＡB" 长度 2、宽度 3）。
     */
    @Test
    fun segmentOffsetsUseCellWidthNotStringLength() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 原版 `UseUserStyle` 出厂 false（EmueraConsole.cs:467 的 bool 字段默认值），
        // 只有 PRINT 系语句会把它写真（FunctionIdentifier.cs:179/:276、
        // Process.cs:1110/:1129/:1149）。本用例直接驱动 sink 的样式快照，
        // 等价于「站在 PRINT 语句里」，故先开这一轴。
        s.setUseUserStyle(true)

        s.print("A")
        s.setForeColor(1, 2, 3)
        s.print("ＡB")
        s.newLine()

        val line = s.lines().single()
        val placed = s.segmentsOf(line)

        assertEquals(2, placed.size)
        assertEquals(0, placed[0].startCol)
        assertEquals(1, placed[0].widthCells)
        assertEquals("A", placed[0].segment.text)
        assertEquals(1, placed[1].startCol)
        assertEquals(3, placed[1].widthCells, "Ａ=2 格 + B=1 格")
        assertEquals("ＡB", placed[1].segment.text)
        assertEquals(4, s.widthOf(line), "整行宽度 = 1 + 3（与段偏移同源）")
    }

    /** 无段行的段列表为空、宽度 0（对齐计算不得除零或读出越界）。 */
    @Test
    fun emptyRowHasNoSegmentsAndZeroWidth() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        // 空缓冲 newLine 产出的是「一个空格段」的行（EmueraConsole.cs:3379-3382），不是空段行；
        // 零段的形态由 REUSELASTLINE 之外的空 HTML/未来形态产生，这里直接构造契约对象验证视图。
        val blank = LineBufferConsoleSink.DisplayLine(isLineEnd = true, segments = emptyList())

        assertTrue(s.segmentsOf(blank).isEmpty())
        assertEquals(0, s.widthOf(blank))
    }

    // ------------------------------------------------------------------
    // 样式位
    // ------------------------------------------------------------------

    /**
     * FONTBOLD/FONTITALIC 是 **OR 语义**（FunctionIdentifier.cs:1574-1606）：
     * 先粗体再斜体 → 1 再 3；FONTSTYLE 是整位替换（Process.cs:1508-1530）。
     * 样式变更把同一行的文本切成多段，早先段落保留自己的快照值。
     */
    @Test
    fun styleBitsAreOrAccumulatedAndSnapshotPerSegment() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 原版 `UseUserStyle` 出厂 false（EmueraConsole.cs:467 的 bool 字段默认值），
        // 只有 PRINT 系语句会把它写真（FunctionIdentifier.cs:179/:276、
        // Process.cs:1110/:1129/:1149）。本用例直接驱动 sink 的样式快照，
        // 等价于「站在 PRINT 语句里」，故先开这一轴。
        s.setUseUserStyle(true)

        s.print("a")
        s.setFontStyle(s.fontStyle or 1) // FONTBOLD
        assertEquals(1, s.fontStyle)
        s.print("b")
        s.setFontStyle(s.fontStyle or 2) // FONTITALIC
        assertEquals(3, s.fontStyle)
        s.print("c")
        s.setFontStyle(4) // FONTSTYLE 4 = 删除线（整位替换）
        s.print("d")
        s.setFontStyle(8) // FONTSTYLE 8 = 下划线
        s.print("e")
        s.newLine()

        val line = s.lines().single()
        assertEquals(listOf("a", "b", "c", "d", "e"), line.segments.map { it.text })
        assertEquals(listOf(0, 1, 3, 4, 8), line.segments.map { it.styleMask })
        assertEquals(8, s.fontStyle, "读出侧返回当前整位掩码")
    }

    /** 颜色同样按段快照，且 0xRRGGBB 组装正确（SETCOLOR 语句层已保证 0-255）。 */
    @Test
    fun foregroundColorIsSnapshotPerSegment() {
        val s = LineBufferConsoleSink(clientWidth = 80)
        // 原版 `UseUserStyle` 出厂 false（EmueraConsole.cs:467 的 bool 字段默认值），
        // 只有 PRINT 系语句会把它写真（FunctionIdentifier.cs:179/:276、
        // Process.cs:1110/:1129/:1149）。本用例直接驱动 sink 的样式快照，
        // 等价于「站在 PRINT 语句里」，故先开这一轴。
        s.setUseUserStyle(true)

        s.setForeColor(0xFF, 0x80, 0x01)
        s.print("x")
        s.setForeColor(0, 0, 0)
        s.print("y")
        s.resetForeColor()
        s.print("z")
        s.newLine()

        val segs = s.lines().single().segments
        assertEquals(listOf(0xFF8001, 0x000000, LineBufferConsoleSink.DEFAULT_FORE_COLOR), segs.map { it.foreColor })
    }

    // ------------------------------------------------------------------
    // 宽字符 / 字符边界
    // ------------------------------------------------------------------

    /**
     * 宽 4：两个全角恰好填满 → 只有一行，**不产生空尾行**
     * （原版段总宽恰等于宽度时不折，PrintStringBuffer.cs:205-224）。
     */
    @Test
    fun exactlyFilledWideRowDoesNotEmitEmptyTailRow() {
        val s = LineBufferConsoleSink(clientWidth = 4)

        s.print("ああ")
        s.newLine()

        assertEquals(1, s.displayLineCount)
        assertEquals(listOf("ああ"), s.loggedLines())
        assertTrue(s.lines().single().isLineEnd)
        assertEquals(4, s.widthOf(s.lines().single()))
    }

    /** 宽 4、三全角：第二个字符放不下时整字折到下一行，绝不把字符切成两半。 */
    @Test
    fun wideCharMovesWholeToNextRowAtBoundary() {
        val s = LineBufferConsoleSink(clientWidth = 4)

        s.print("あああ")
        s.newLine()

        assertEquals(listOf("ああ", "あ"), s.loggedLines())
        assertTrue(s.lines()[0].isLogicalLine)
        assertFalse(s.lines()[1].isLogicalLine)
        assertFalse(s.lines()[0].isLineEnd, "折行续行 IsLineEnd=false")
        assertTrue(s.lines()[1].isLineEnd)
    }

    /**
     * 增补平面字符（U+20000，代理对）按**一个码位**计宽 2，且折行不得拆开代理对：
     * 每一行的文本里都不允许出现孤立代理项。
     */
    @Test
    fun surrogatePairIsNeverSplitAcrossRows() {
        val s = LineBufferConsoleSink(clientWidth = 2)

        s.print(astral + astral)
        s.newLine()

        assertEquals(2, s.displayLineCount)
        assertEquals(listOf(astral, astral), s.loggedLines())
        for (line in s.lines()) {
            val t = s.textOf(line)
            assertEquals(2, t.length, "一行一个代理对 = 2 个 UTF-16 单元")
            assertTrue(Character.isHighSurrogate(t[0]) && Character.isLowSurrogate(t[1]), "代理对完整")
        }

        // 2 + 1 = 3 > 2：第二个码位放不下，整字折行（不是把两个单元的代理对拆开）
        val s2 = LineBufferConsoleSink(clientWidth = 2)
        s2.print(astral + "A")
        s2.newLine()
        assertEquals(listOf(astral, "A"), s2.loggedLines())
    }

    /** 单个码位本身宽于 clientWidth 时独占一行，不得死循环（buildRows 的既有分支）。 */
    @Test
    fun overwideSingleCharOccupiesOwnRow() {
        val s = LineBufferConsoleSink(clientWidth = 1)

        s.print("あA")
        s.newLine()

        assertEquals(listOf("あ", "A"), s.loggedLines())
    }

    // ------------------------------------------------------------------
    // CellWidth：唯一的宽度实现
    // ------------------------------------------------------------------

    /** 半角 1 / 全角 2 / 增补平面 2 / 空串 0；整串按码位累加，不按 UTF-16 长度。 */
    @Test
    fun cellWidthTableIsTheSingleSourceOfTruth() {
        assertEquals(2, astral.length, "前置条件：U+20000 的 UTF-16 长度是 2")
        assertEquals(0, CellWidth.of(""))
        assertEquals(1, CellWidth.of("A"))
        assertEquals(2, CellWidth.of("Ａ"), "全角Ａ是 2 格")
        assertEquals(2, CellWidth.of("あ"))
        assertEquals(2, CellWidth.ofCodePoint(0x20000), "U+20000 按一个码位计 2 格")
        assertEquals(2, CellWidth.of(astral), "代理对不是两个半角")
        assertEquals(3, CellWidth.of("Aあ"))
        assertEquals(4, CellWidth.of("A" + astral + "B"))
    }

    /**
     * 表口径 = **CP932(Shift-JIS) 字节数**，不是 East Asian Width（组长裁定，main@`166dbd7`）。
     *
     * 判据两条，都不依赖本实现：
     * - 补齐宽度原样问字节数：`EmueraConsole.cs:3255` 的
     *   `Encoding.GetEncoding("Shift-JIS").GetByteCount(str)`（`STRLEN` 一族同理，
     *   `LangManager.cs:15-22,39,74,90`；码页 `Config.cs:412-416` 定成 932）。
     *   下面逐个钉的符号在 CP932 里全是双字节（逐点字节序列实测登记在
     *   `render/src/test/kotlin/com/eratw/port/render/grid/GridWidthSingleSourceTest.kt`）。
     * - 屏上宽度由基准钉死：`pc-golden-root.txt:61` 是 **99 个 `━`**，而 `:253` 的 199 个 `=`
     *   把半角步进钉成 9px、可绘制宽度 1797px（`Config.cs:461-466`）—— 99 字填满只能是
     *   单字 18px = 2 个半角格。等宽日文字体下「双字节 ⇒ 两倍半角宽」，与 GDI 像素口径
     *   （`StringMeasure.cs:42-72`）同结论。
     *
     * EAW 把这一类判成 Ambiguous（1 格），故**不按 EAW 建表**；`── ━ ※ ① ± … ― “ ” ■ ▼ ♪ ≦ ≧`
     * 一律 2 格。半角四档：ASCII 可打印 `U+0020..U+007E`、半角假名 `U+FF61..U+FF9F`、
     * C0/DEL/C1 首码点 `U+0000..U+001F` / `U+007F` / `U+0080`、以及 best-fit 回 `0x5C` 的 `U+00A5`
     * （后两档的 .NET 932 实测读数见 `cellWidthFollowsSjisByteCountNotEastAsianWidth` 末尾）。
     */
    @Test
    fun cellWidthFollowsSjisByteCountNotEastAsianWidth() {
        val fullWidth = listOf(
            0x2500, 0x2501,               // ─ ━（基准:61 直接钉住 ━）
            0x203B,                       // ※（基准:67 :534）
            0x2460, 0x2461,               // ① ②
            0x00B1, 0x00D7, 0x00B0,       // ± × °
            0x2026, 0x2015, 0x2010,       // … ― ‐
            0x201C, 0x201D,               // 引号
            0x25A0, 0x25BC, 0x266A,       // ■ ▼ ♪
            0x2266, 0x2267, 0x2260,       // ≦ ≧ ≠
            0x2192, 0x21D2, 0x221A,       // → ⇒ √
            0x2166, 0x2116, 0x2103,       // Ⅶ № ℃
            0x00A2, 0x00A7, 0x00AC, 0x00B4, // ¢ § ¬ ´
        )
        for (cp in fullWidth) {
            assertEquals(2, CellWidth.ofCodePoint(cp), "U+%04X 在 CP932 是双字节 ⇒ 2 格".format(cp))
        }
        // 半角四档，别的一个都不给。
        for (cp in listOf(0x0020, 0x0041, 0x005C, 0x007E, 0xFF61, 0xFF76, 0xFF9F)) {
            assertEquals(1, CellWidth.ofCodePoint(cp), "U+%04X 是 Shift-JIS 单字节 ⇒ 1 格".format(cp))
        }
        // 控制符 / DEL / C1 首点 / ¥：.NET 932 的 GetByteCount 实测各 1 字节
        // （C0 与 0x7F/0x80 原样直传，U+00A5 best-fit 回 0x5C）⇒ 1 格。
        for (cp in listOf(0x0000, 0x0009, 0x001F, 0x007F, 0x0080, 0x00A5)) {
            assertEquals(1, CellWidth.ofCodePoint(cp), "U+%04X 在 .NET 932 是单字节 ⇒ 1 格".format(cp))
        }
        assertEquals(1, CellWidth.ofCodePoint(0x005C), "ASCII 反斜杠与 U+00A5 同字节 0x5C ⇒ 同格")
        // 紧邻半角假名区之外的两个符号不能顺手带进来。
        assertEquals(2, CellWidth.ofCodePoint(0xFF60), "全角「)」在 FF61 之前，仍是 2 格")
        assertEquals(2, CellWidth.ofCodePoint(0xFFA0), "半角谚文不是 CP932 单字节")
        assertEquals(7, CellWidth.of("a―b※c"), "整串：1 + 2 + 1 + 2 + 1")
        // TODO(verify) CP932 表外字（`— ≤ ≥ ♥ ❤ · ½ ∶ ≈` 与 emoji）这里按全角 2 格，
        // 与旧表同值；字节数口径对它们实得 1（.NET 替换回退按单个 `?` 计），
        // 原版屏上宽度取决于字体回退，基准文本里没有可对拍的读数。
        assertEquals(2, CellWidth.ofCodePoint(0x2264), "≤ 是 CP932 表外字：本表按全角，卡点见上")
    }
}
