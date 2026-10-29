package com.eratw.port.core.host

import com.eratw.port.e2e.GoldenNormalizer
import com.eratw.port.e2e.LineConsoleSink
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 生产 sink（[LineBufferConsoleSink]）与 E2E 现用假 sink（`e2e/LineConsoleSink`）的
 * **逐字节文本对拍**，以 `fixtures/golden/baseline/pc-golden-root.txt` 为期望值。
 *
 * 为什么这样对拍（本路不跑 gradle，结论要在组长过闸时自证）：
 * 1. `e2e/LineConsoleSink` 是当前 E2E 装置 610/610 行的输出口径（BOARD「闸门基线」）；
 * 2. 同一调用序喂给两个 sink，若 `loggedLines()` 与 `snapshot()` 逐行相同，
 *    则换成生产 sink 不改变 E2E 文本；同时两边都对齐 PC 基准的对应段，
 *    把「假 sink 本身就已偏离基准」这种可能也一起挡住。
 *
 * 归一化口径 = `docs/golden-runbook.md` §5（`GoldenNormalizer` 装置化）：`={10,}` / `-{10,}`
 * 折成**保长度**哨兵 `LINE(=)×<字符数>` / `LINE(-)×<字符数>`（长度进 diff，见
 * `GoldenNormalizer` 规则 3；GOLDEN-COVER），RNG- 段剔除。逐字节比较发生在归一化**之前**的拼接上
 * （两边同样归一化，等价于逐行逐字节）。
 *
 * 覆盖段：PRINT-NULL-L / PRINT-V-S / PRINT-FORM / PRINT-K-D / PRINT-SINGLE / PRINT-C /
 * PRINT-N / PRINT-W / BUTTON-PLAIN / LINE-CTRL / DRAWLINE。
 * **不覆盖** HTML 段：假 sink 做去标签粗渲染，生产 sink 保留 HTML 原文（M4 由
 * `:render` 的 mini-HTML 排版消费），两者本来就不同源；HTML 行的文本化归 M4-ADAPT。
 */
class GoldenTextParityTest {

    /** PC 基准的 DrawableWidth 字符数 = 假 sink 的 `barWidth`（FakeSinks.kt:34）。 */
    private val barWidth = 199

    /** Config.PrintCLength（ConfigData.cs:64；PC 基准 PRINTCLENGTH=25，pc-golden-root.txt:292）。 */
    private val printCWidth = 25

    private class Duo(val fake: LineConsoleSink, val prod: LineBufferConsoleSink) {
        /** 假 sink 的日志文本行（跳过 temporary 行）。 */
        fun fakeLines(): List<String> = fake.snapshot()

        /** 生产 sink 的日志文本行（跳过 temporary 行）。 */
        fun prodLines(): List<String> = prod.loggedLines()
    }

    private fun duo(): Duo = Duo(
        LineConsoleSink(barWidth = barWidth, printCLength = printCWidth),
        LineBufferConsoleSink(clientWidth = barWidth, printCWidth = printCWidth),
    )

    // ------------------------------------------------------------------
    // 基准读取
    // ------------------------------------------------------------------

    private val baseline: List<String> by lazy {
        val dir = sequenceOf(File("fixtures/golden"), File("../fixtures/golden"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 fixtures/golden（测试 JVM cwd=${File("").absolutePath}）")
        File(dir, "baseline/${GoldenNormalizer.PC_BASELINE}")
            .readText(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .split("\n")
    }

    /** 取 `[startMarker, endMarker)` 的基准段并按 runbook §5 归一化。 */
    private fun block(startMarker: String, endMarker: String): List<String> {
        val from = baseline.indexOfFirst { it.trim() == startMarker }
        val to = baseline.indexOfFirst { it.trim() == endMarker }
        check(from >= 0) { "基准缺少段头 $startMarker" }
        check(to > from) { "基准段 $startMarker 之后找不到 $endMarker" }
        return GoldenNormalizer.normalize(baseline.subList(from, to).joinToString("\n"))
    }

    /** 两边都必须等于同一份基准段；失败信息带上首差，便于组长直接定位。 */
    private fun assertBothMatchGolden(duo: Duo, expected: List<String>, label: String) {
        val fakeLines = duo.fakeLines()
        val prodLines = duo.prodLines()
        val fakeDiff = GoldenNormalizer.diff(expected, GoldenNormalizer.normalize(fakeLines.joinToString("\n")))
        val prodDiff = GoldenNormalizer.diff(expected, GoldenNormalizer.normalize(prodLines.joinToString("\n")))
        assertTrue(fakeDiff.identical, "[$label] E2E 假 sink 已偏离基准：\n${fakeDiff.preview}")
        assertTrue(prodDiff.identical, "[$label] 生产 sink 偏离基准：\n${prodDiff.preview}")
        assertEquals(fakeLines, prodLines, "[$label] 两个 sink 的显示行序列不一致")
    }

    // ------------------------------------------------------------------
    // PRINT 家族
    // ------------------------------------------------------------------

    /**
     * PRINT 家族的完整段（ERB:29-134）：逐条语句的文本与行结束位按 `GOLDEN_02_PRINT.ERB`
     * 原样转录。断言点：
     * - `PRINT-N` 的未结行（isLineEnd=false）在裸 PRINTL 的空缓冲 force flush 下并入
     *   行尾空格，得到 `NN45NSFN46FSN47 `（EmueraConsole.cs:3379-3382）；
     * - `PRINT-C` 的 12 个定宽项共 300 格，按 199 折成 199 + 101 两条显示行；
     * - `PRINT-SINGLE` 每项独立一行、不折行。
     */
    @Test
    fun printFamilyTextMatchesPcGolden() {
        val duo = duo()
        drivePrintFamily(duo.prod)
        // 同一序喂假 sink：接口方法同名，直接复用同一个驱动函数
        drivePrintFamily(duo.fake)

        val expected = block("=== SECTION:PRINT-NULL-L ===", "=== SECTION:PRINT-DATA ===")
        assertBothMatchGolden(duo, expected, "PRINT 家族")

        // 结构性事实（基准只反映文本，这些是数据源契约）
        val rows = duo.prod.loggedLines()
        assertEquals(duo.fake.lineCount, duo.prod.lineCount, "LINECOUNT 与假 sink 同口径")
        assertEquals(
            "NN45NSFN46FSN47 ",
            rows[rows.indexOf("=== SECTION:PRINT-N ===") + 1],
            "未结行尾的空缓冲 force flush 补一个空格后被并入（EmueraConsole.cs:3379-3382）",
        )
        val printC = rows.indexOf("=== SECTION:PRINT-C ===")
        assertEquals(199, rows[printC + 1].length, "PRINT-C 300 格按 199 折行")
        assertEquals(101, rows[printC + 2].length)
    }

    /**
     * PRINT 家族的驱动脚本：只做 ConsoleSink 事件，不经过 sem。
     * 每条注释给出 `GOLDEN_02_PRINT.ERB` 的行号与语句；**文本值取自 PC 基准**
     * （例如 `PRINTV 1+2` → `3`，pc-golden-root.txt:168）。
     */
    private fun drivePrintFamily(c: ConsoleSink) {
        // 29-32 PRINT-NULL-L
        c.printLine("=== SECTION:PRINT-NULL-L ===")
        c.print("ABC"); c.newLine()      // PRINT ABC ; PRINTL（flush 掉 ABC）
        c.print("DEF"); c.newLine()      // PRINTL DEF

        // 33-39 PRINT-V-S
        c.printLine("=== SECTION:PRINT-V-S ===")
        c.print("3"); c.newLine()        // PRINTV 1+2 ; PRINTL
        c.print("12"); c.newLine()       // PRINTVL 3*4
        c.print("STR"); c.newLine()      // PRINTS "STR" ; PRINTL
        c.print("ABC"); c.newLine()      // PRINTSL "ABC"

        // 40-46 PRINT-FORM
        c.printLine("=== SECTION:PRINT-FORM ===")
        c.print("A=2B"); c.newLine()     // PRINTFORM A={1+1}B ; PRINTL
        c.print("C=4"); c.newLine()      // PRINTFORML C={2+2}
        c.print("XY6"); c.newLine()      // PRINTFORMS "XY{3+3}" ; PRINTL
        c.print("Z8"); c.newLine()       // PRINTFORMSL "Z{4+4}"

        // 47-77 PRINT-K-D（K/D 后缀不改变文本；转换属 Windows StrConv，sem 原样透传）
        c.printLine("=== SECTION:PRINT-K-D ===")
        listOf(
            "5", "6", "7", "8", "KS", "KSL", "K9", "KL10", "SK11", "SKL12",
            "DBG", "DBG2", "13", "14", "SD", "SDL", "D15", "DL16", "SDD17", "SDDL18",
        ).forEach { c.print(it); c.newLine() }

        // 78-93 PRINT-SINGLE（每项一条独立显示行，nobr）
        c.printLine("=== SECTION:PRINT-SINGLE ===")
        listOf(
            "ONE", "19", "S3", "F21", "FS23", "K25", "VK27", "SK29", "FK31", "FSK33",
            "D35", "VD37", "SD39", "FD41", "FSD43",
        ).forEach { c.printSingleLine(it) }

        // 94-107 PRINT-C：12 项 × 25 格 = 300 格，末条 PRINTL 收行
        c.printLine("=== SECTION:PRINT-C ===")
        c.printC("AA", true)
        c.printC("BB", false)
        c.printC("C1", true)
        c.printC("D2", false)
        c.printC("EE", true)
        c.printC("FF", false)
        c.printC("G3", true)
        c.printC("H4", false)
        c.printC("II", true)
        c.printC("JJ", false)
        c.printC("K5", true)
        c.printC("L6", false)
        c.newLine()

        // 108-115 PRINT-N：五段 lineEnd=false，裸 PRINTL 的空缓冲补空格收尾
        c.printLine("=== SECTION:PRINT-N ===")
        c.print("NN", false); c.newLine()
        c.print("45", false); c.newLine()
        c.print("NS", false); c.newLine()
        c.print("FN46", false); c.newLine()
        c.print("FSN47", false); c.newLine()
        c.print("")             // PRINTL：空串不改 isLastLineEnd（EmueraConsole.cs:3167-3170）
        c.newLine()             // force flush → 补 " " 并入未结行
        c.print("AFTER-PRINTN"); c.newLine()

        // 116-134 PRINT-W（W = 换行 + 等待按键；等待属输入后端，文本流只看到换行）
        c.printLine("=== SECTION:PRINT-W ===")
        listOf(
            "WW", "48", "SW", "FW49", "FSW50", "KW51", "KW52", "KW53", "KW54", "KW55",
            "DW56", "DW57", "DW58", "DW59", "DW60", "KW61",
        ).forEach { c.print(it); c.newLine() }
    }

    // ------------------------------------------------------------------
    // BUTTON / LINE-CTRL / DRAWLINE
    // ------------------------------------------------------------------

    /**
     * BUTTON-PLAIN（ERB:185-197）：按钮文本原样进文本流；PRINTBUTTONC/LC 的补齐属于
     * 按钮文本本身（EmueraConsole.cs:3309-3323 → CreateTypeCString），所以按钮区间
     * 覆盖补齐后的整段 25 格。断言按钮表（行号 + 区间 + 值）与文本同时成立。
     */
    @Test
    fun buttonPlainTextMatchesPcGoldenAndButtonTableCoversPaddedText() {
        val duo = duo()
        driveButtons(duo.prod)
        driveButtons(duo.fake)

        assertBothMatchGolden(duo, block("=== SECTION:BUTTON-PLAIN ===", "=== SECTION:COLOR ==="), "BUTTON-PLAIN")

        val buttons = duo.prod.buttons()
        assertEquals(
            listOf(
                LineBufferConsoleSink.PlacedButton(1, 0, 3, 1L),          // BTN
                LineBufferConsoleSink.PlacedButton(2, 0, 3, "SVAL"),      // [X]
                LineBufferConsoleSink.PlacedButton(3, 0, 25, 2L),         // 右对齐补齐的 BC
                LineBufferConsoleSink.PlacedButton(4, 0, 25, 3L),         // 左对齐补齐的 BLC
            ),
            buttons,
            "补齐的空格算在按钮内：按钮文本是 CreateTypeCString 的输出",
        )
        assertEquals(0, duo.prod.lines()[5].buttons.size, "PRINTPLAIN 不产生按钮")
    }

    private fun driveButtons(c: ConsoleSink) {
        c.printLine("=== SECTION:BUTTON-PLAIN ===")
        c.printButton("BTN", 1L); c.newLine()
        c.printButton("[X]", "SVAL"); c.newLine()
        c.printButtonC("BC", 2L, true); c.newLine()
        c.printButtonC("BLC", 3L, false); c.newLine()
        c.printPlain("PLAIN"); c.newLine()
        c.printPlain("PF7"); c.newLine()
    }

    /**
     * LINE-CTRL（ERB:164-175）：`CLEARLINE 1` 与 `REUSELASTLINE` 的 LINECOUNT 增量
     * 必须仍是 2 与 5（基准 pc-golden-root.txt:245-247）。
     *
     * 这条同时锁住 [LineBufferConsoleSink.lineCount] 的计时点：临时行**计入**
     * LINECOUNT（`PrintSingleLine` → `addDisplayLine` 的 `IsLogicalLine && IsLineEnd`
     * 自增，`EmueraConsole.cs:2973-2978`），但**下一条显示行加入时会被删掉**
     * （`addDisplayLine` 开头 `if (LastLineIsTemporary) deleteLine(1)`，`:2891-2893`），
     * 于是 `REUSELASTLINE` 对 LINECOUNT 的净贡献是 0（GOLDEN_02_PRINT.ERB:166 的注释口径）。
     */
    @Test
    fun lineControlDeltasMatchPcGolden() {
        val duo = duo()
        driveLineControl(duo.prod)
        driveLineControl(duo.fake)

        assertBothMatchGolden(duo, block("=== SECTION:LINE-CTRL ===", "=== SECTION:BAR ==="), "LINE-CTRL")
        // 段末 6 行：段头 / A / B / AFTER-CLEAR / LINE-D / AFTER-REUSE。
        // LINE-C 被 CLEARLINE 1 删掉；REUSED-2 是临时行，AFTER-REUSE 加入时按
        // EmueraConsole.cs:2891-2893 被 deleteLine(1) 替换，所以既不出现在文本里也不占行数。
        // 两个 DELTA 都在打印前取值：2 = 3-1(CLEARLINE)，5 = 6-1(临时行计了行，随后被替换)。
        assertEquals(6L, duo.prod.lineCount, "临时行计行但被下一条显示行替换，净贡献 0")
        assertEquals(6, duo.prod.displayLineCount, "临时行不留在显示列表里")
        assertEquals(6, duo.prod.loggedLines().size, "临时行不进日志文本")
    }

    private fun driveLineControl(c: ConsoleSink) {
        c.printLine("=== SECTION:LINE-CTRL ===")
        val lc0 = c.lineCount
        c.printLine("LINE-A")
        c.printLine("LINE-B")
        c.printLine("LINE-C")
        c.deleteLine(1) // CLEARLINE 1
        c.print("AFTER-CLEAR LINE-DELTA=${c.lineCount - lc0}"); c.newLine()
        c.printLine("LINE-D")
        c.printTemporaryLine("REUSED-2") // REUSELASTLINE REUSED-{1+1}
        c.print("AFTER-REUSE LINE-DELTA=${c.lineCount - lc0}"); c.newLine()
    }

    /**
     * DRAWLINE（ERB:181-184）：`CUSTOMDRAWLINE =` / `DRAWLINE` / `DRAWLINEFORM -{1+1}`
     * 各填满 [barWidth] 个字符，按 `GoldenNormalizer` 规则 3 归一化为保长度哨兵
     * `LINE(=)×199` / `LINE(-)×199`（字符数进 diff，这正是 [barWidth] 取值的意义）。
     *
     * 注意：这里**只断言三条 bar 行**。ERB:185 的 `PRINTL` 是下一段的段头，
     * 不是空行；不要把「段末的空行」写进期望。
     */
    @Test
    fun drawLineBarsMatchPcGoldenUnderRunbookNormalization() {
        val duo = duo()
        driveBars(duo.prod)
        driveBars(duo.fake)

        val expected = block("=== SECTION:DRAWLINE ===", "=== SECTION:BUTTON-PLAIN ===")
        assertEquals(4, expected.size, "段头 + 三条 bar 行")
        assertEquals("LINE(=)×199", expected[1], "保长度哨兵：字符数进 diff（GOLDEN-COVER）")
        assertEquals("LINE(-)×199", expected[2], "保长度哨兵：字符数进 diff（GOLDEN-COVER）")

        assertBothMatchGolden(duo, expected, "DRAWLINE")
        val rows = duo.prod.loggedLines()
        assertEquals(4, rows.size)
        assertEquals("=== SECTION:DRAWLINE ===", rows[0])
        assertEquals(199, rows[1].length)
        assertEquals(199, rows[2].length)
        assertEquals(199, rows[3].length)
        assertTrue(rows[3].startsWith("-2-2"))
    }

    private fun driveBars(c: ConsoleSink) {
        c.printLine("=== SECTION:DRAWLINE ===")
        c.printCustomBar("="); c.newLine()      // CUSTOMDRAWLINE =
        c.printBar(); c.newLine()               // DRAWLINE（字符 "-"）
        c.printCustomBar("-2"); c.newLine()     // DRAWLINEFORM -{1+1}
    }
}
