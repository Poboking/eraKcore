package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LIVE-REFLOW：会话期宽度热重排（[LineBufferConsoleSink.resize]）的口径钉子。
 *
 * 判据全部来自契约 CONTRACT-LIVE-REFLOW §3：
 * - `reflowEnabled=false` ⇒ `resize` 恒 false、journal 不存在、全部可见状态逐字段不变；
 * - 打开但同宽 ⇒ false，快照（含 serial / lineNo / revision / stBar）逐字段不变；
 * - 真的变宽 ⇒ 逻辑行文本逐字符一致（只允许物理换行位置不同）、按钮的值/源编号/世代不变、
 *   `LINECOUNT` 不变、`stBar` 长度 = 新宽度、`revision` 前进一枚；
 * - 重排**不重新盖按钮世代**（等待中的可点按钮不能因重排失效）；
 * - 宽度无关行（PRINTSINGLE / 临时行 / 直印 HTML / MaxLog 补行）原样保留；
 * - MaxLog 淘汰掉某逻辑行前半截时，剩余排冻结在头部，不再重排该组。
 */
class ConsoleResizeTest {

    private fun sink(width: Int, maxLines: Int = 200, enabled: Boolean = true): LineBufferConsoleSink =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines, reflowEnabled = enabled)

    /** 每条逻辑行的纯文本（折行续行按 `isLogicalLine` 归组；头部被淘汰的半截自成一组）。 */
    private fun logicalTexts(s: LineBufferConsoleSink): List<String> {
        val out = ArrayList<StringBuilder>()
        for (line in s.snapshot()) {
            if (line.isLogicalLine || out.isEmpty()) out.add(StringBuilder())
            out.last().append(s.textOf(line))
        }
        return out.map { it.toString() }
    }

    /** 按钮身份三元组（源编号, 值, 世代），按屏上顺序、同源只留首次出现。 */
    private fun buttonIdentities(s: LineBufferConsoleSink): List<Triple<Int, Any, Long>> {
        val out = ArrayList<Triple<Int, Any, Long>>()
        val seen = HashSet<Int>()
        for (line in s.snapshot()) {
            for (b in line.buttons) {
                if (b.groupId != LineBufferConsoleSink.ID_UNKNOWN && !seen.add(b.groupId)) continue
                out.add(Triple(b.groupId, b.value, b.generation))
            }
        }
        return out
    }

    /** 「逐字段不变」的指纹：行标志、序号、样式、段文本、按钮几何/世代、HTML 按钮全在里面。 */
    private fun fingerprint(s: LineBufferConsoleSink): List<String> = s.snapshot().map { line ->
        buildString {
            append(line.isLineEnd).append('/').append(line.isLogicalLine).append('/')
            append(line.isTemporary).append('/').append(line.serial).append('/')
            append(line.alignment).append('/').append(line.backColor).append('/').append(line.noBreak).append('/')
            append(line.segments.joinToString(",") { "${it.kind}:${it.foreColor}:${it.styleMask}:${it.fontName}:${it.text}" })
            append('/')
            append(line.buttons.joinToString(",") { "${it.startCol}-${it.endCol}:${it.value}:${it.generation}:${it.groupId}:${it.origin}" })
            append('/')
            append(line.htmlOptions.joinToString(",") { "${it.startCol}-${it.endCol}:${it.value}:${it.generation}:${it.groupId}" })
        }
    }

    private fun noteWait(s: LineBufferConsoleSink, line: Long = 42L) {
        s.noteInputWait(
            InputRequest(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                scriptLine = line,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 开关关闭 / 同宽：逐字不变
    // ------------------------------------------------------------------

    /** 关闭开关（缺省）：[LineBufferConsoleSink.resize] 恒 false，连 `clientWidth` 都不动。 */
    @Test
    fun `关闭开关时 resize 恒 false 且零状态变化`() {
        val s = sink(width = 12, enabled = false)
        s.printLine("[1] alpha beta gamma")
        s.printLine("ββββββββ")
        s.printButton("OK", 7L)
        s.newLine()

        val before = fingerprint(s)
        val revisionBefore = s.revision
        val lineNoBefore = s.lineNo

        assertFalse(s.resize(40), "开关关闭必须返回 false")
        assertEquals(12, s.clientWidth, "关闭态不许改宽度")
        assertEquals("-".repeat(12), s.stBar, "关闭态 stBar 不重算")
        assertEquals(before, fingerprint(s), "关闭态行表逐字段不变")
        assertEquals(revisionBefore, s.revision)
        assertEquals(lineNoBefore, s.lineNo)
    }

    /** 打开但同宽：false，且快照（含 serial / lineNo / revision / stBar / 选项）逐字段不变。 */
    @Test
    fun `打开开关同宽 resize 返回 false 且快照逐字段不变`() {
        val s = sink(width = 20)
        s.printLine("[1] alpha beta gamma delta")
        s.printLine("ββββββββββββββ")
        s.printButton("OK", 7L)
        s.printButton("OK2", "s")
        s.newLine()
        noteWait(s)

        val before = fingerprint(s)
        val revisionBefore = s.revision
        val lineNoBefore = s.lineNo
        val lineCountBefore = s.lineCount
        val optionsBefore = s.currentOptions()

        assertFalse(s.resize(20), "同宽必须返回 false")
        assertEquals(before, fingerprint(s))
        assertEquals(revisionBefore, s.revision, "同宽不许动 revision")
        assertEquals(lineNoBefore, s.lineNo)
        assertEquals(lineCountBefore, s.lineCount)
        assertEquals(optionsBefore, s.currentOptions())
        assertEquals("-".repeat(20), s.stBar)
    }

    // ------------------------------------------------------------------
    // 变宽：逻辑文本 / 按钮 / LINECOUNT / stBar
    // ------------------------------------------------------------------

    /** 窄 → 宽：逻辑行文本逐字符一致，按钮值/源编号/世代一致，物理行只会变少。 */
    @Test
    fun `窄转宽后逻辑文本逐字符一致且按钮世代保持`() {
        val s = sink(width = 12)
        s.printLine("[1] alpha beta gamma")
        s.printLine("ββββββββββββββ")
        s.printButton("OK", 7L)
        s.print("tail", true)
        s.newLine()

        val textsBefore = logicalTexts(s)
        val buttonsBefore = buttonIdentities(s)
        val lineCountBefore = s.lineCount
        val rowsBefore = s.displayLineCount
        val revisionBefore = s.revision
        val lineNoBefore = s.lineNo

        assertTrue(s.resize(30), "宽度真的变了必须返回 true")

        assertEquals(30, s.clientWidth)
        assertEquals("-".repeat(30), s.stBar, "stBar 按新宽度重算（drawLineStr 的数据源）")
        assertEquals(textsBefore, logicalTexts(s), "逻辑行文本逐字符一致（只允许物理换行位置不同）")
        assertEquals(buttonsBefore, buttonIdentities(s), "按钮值/源编号/世代都不许变")
        assertEquals(lineCountBefore, s.lineCount, "LINECOUNT 不变")
        assertTrue(s.displayLineCount < rowsBefore, "变宽后物理行应减少：$rowsBefore -> ${s.displayLineCount}")
        assertEquals(revisionBefore + 1, s.revision, "重排是一次可见变化，revision 前进一枚")
        assertEquals(
            lineNoBefore + (s.displayLineCount - rowsBefore),
            s.lineNo,
            "lineNo 按物理行差调整",
        )
        assertEquals(0L, s.deletedLines, "没有淘汰就不许动 deletedLines")
    }

    /** 宽 → 窄：逻辑行文本仍逐字符一致，物理行只会变多。 */
    @Test
    fun `宽转窄后逻辑文本逐字符一致且物理行变多`() {
        val s = sink(width = 30)
        s.printLine("[1] alpha beta gamma")
        s.printLine("ββββββββββββββ")
        s.print("[2] second option line", true)
        s.newLine()

        val textsBefore = logicalTexts(s)
        val buttonsBefore = buttonIdentities(s)
        val lineCountBefore = s.lineCount
        val rowsBefore = s.displayLineCount

        assertTrue(s.resize(10))

        assertEquals(10, s.clientWidth)
        assertEquals("-".repeat(10), s.stBar)
        assertEquals(textsBefore, logicalTexts(s))
        assertEquals(buttonsBefore, buttonIdentities(s))
        assertEquals(lineCountBefore, s.lineCount)
        assertTrue(s.displayLineCount > rowsBefore, "变窄后物理行应增加：$rowsBefore -> ${s.displayLineCount}")
    }

    /** 重排后 DRAWLINE 打的是新宽度的分隔线（`stBar` 与 `printBar` 同源）。 */
    @Test
    fun `重排后 DRAWLINE 打印新宽度的分隔线`() {
        val s = sink(width = 12)
        s.printLine("x")
        assertTrue(s.resize(24))
        s.printBar()
        s.newLine()

        val bar = s.snapshot().last()
        assertEquals("-".repeat(24), s.textOf(bar))
        assertEquals(s.stBar, s.textOf(bar))
    }

    // ------------------------------------------------------------------
    // 逻辑行身份的边界：PRINTN / 临时行 / CLEARLINE / MaxLog
    // ------------------------------------------------------------------

    /** PRINTN 未结行重排后仍未结，下一次 flush 照旧并入（合并的 journal 底稿没丢前半截）。 */
    @Test
    fun `PRINTN 未结行重排后仍未结并可继续并入`() {
        val s = sink(width = 10)
        s.print("abc", lineEnd = false)
        s.newLine()
        assertEquals(1, s.displayLineCount)
        assertFalse(s.snapshot().last().isLineEnd, "PRINTN 未结")

        assertTrue(s.resize(20))
        assertEquals("abc", s.textOf(s.snapshot().last()), "未结行文本不变")
        assertFalse(s.snapshot().last().isLineEnd, "重排不许把未结行改成已结")

        s.print("defghi", lineEnd = true)
        s.newLine()

        assertEquals(1, s.displayLineCount, "并入后仍是同一条显示行")
        assertEquals("abcdefghi", s.textOf(s.snapshot().single()))
        assertEquals(1L, s.lineCount)
    }

    /**
     * PRINTN 折行组被并入时只留住末排（原版 `deleteLine(1)` 吃掉整条旧折行，ADR-005）：
     * 重排**不许**把被丢掉的半截「复活」。
     */
    @Test
    fun `PRINTN 折行组并入后重排不复活被丢掉的前半截`() {
        val s = sink(width = 6)
        s.print("αααα", lineEnd = false) // 8 格 → 折 2 排
        s.newLine()
        assertTrue(s.displayLineCount >= 2)

        s.print("βββ", lineEnd = true)
        s.newLine()
        val joined = s.snapshot().joinToString("") { s.textOf(it) }
        assertEquals("αβββ", joined, "并入只留住旧折行的末排内容（原版形状）")
        assertEquals(1, s.displayLineCount)
        assertEquals(1L, s.lineCount)

        assertTrue(s.resize(20))
        assertEquals("αβββ", s.snapshot().joinToString("") { s.textOf(it) }, "重排不许复活被丢掉的字")
        assertEquals(1, s.displayLineCount)
    }

    /** REUSELASTLINE 临时行宽度无关：重排原样留在尾部，仍是临时行。 */
    @Test
    fun `临时行重排后原样留在尾部`() {
        val s = sink(width = 8)
        s.printLine("[1] menu")
        s.printTemporaryLine("temporary-shown-only")
        val tempBefore = s.snapshot().last()
        assertTrue(tempBefore.isTemporary)

        assertTrue(s.resize(30))

        val tempAfter = s.snapshot().last()
        assertTrue(tempAfter.isTemporary, "临时行不许在重排里丢掉标记")
        assertEquals(s.textOf(tempBefore), s.textOf(tempAfter), "临时行宽度无关，文本原样")
        assertEquals(1, s.snapshot().count { it.isTemporary })
    }

    /** CLEARLINE 之后重排只重排剩余行，删除记账不被重排改写。 */
    @Test
    fun `CLEARLINE 后重排只影响剩余逻辑行`() {
        val s = sink(width = 10)
        s.printLine("one")
        s.printLine("two two two")
        s.printLine("three")
        s.deleteLine(1)

        val textsBefore = logicalTexts(s)
        val lineCountBefore = s.lineCount
        val deletedBefore = s.deletedLines
        val lineNoBefore = s.lineNo
        val rowsBefore = s.displayLineCount

        assertTrue(s.resize(30))

        assertEquals(textsBefore, logicalTexts(s))
        assertEquals(lineCountBefore, s.lineCount, "CLEARLINE 的记账不被重排回退")
        assertEquals(deletedBefore, s.deletedLines, "重排不是淘汰，不许动 deletedLines")
        assertEquals(lineNoBefore + (s.displayLineCount - rowsBefore), s.lineNo)
    }

    /**
     * MaxLog 淘汰掉某逻辑行的**前半截**：剩余排冻结在头部、不再参与重排。
     *
     * 场景：宽 10 下 `ああああああ`（12 格）折 2 排；接着 `b` / `c…` 逐条打印把
     * MaxLog（5）挤满，再打一条 `d` ⇒ 头部第 1 排被淘汰，剩下第 2 排 `あ`（半截逻辑行）。
     * 重排到 30 格：这一排原样留在头部（不会变回 `ああああああ`），其余行正常重排。
     */
    @Test
    fun `MaxLog 淘汰掉前半截的逻辑行冻结在头部`() {
        val s = sink(width = 10, maxLines = 5)
        s.printLine("ああああああ") // 12 格 → 2 排
        s.printLine("b")
        s.printLine("cccccccccccc") // 12 格 → 2 排
        assertEquals(5, s.displayLineCount)

        s.printLine("d") // 挤掉最旧一排：首条逻辑行只剩后半截
        assertEquals(5, s.displayLineCount)
        assertEquals("あ", s.textOf(s.snapshot().first()), "头部只剩半截逻辑行")

        val deletedBefore = s.deletedLines
        val lineCountBefore = s.lineCount

        assertTrue(s.resize(30))

        assertEquals("あ", s.textOf(s.snapshot().first()), "被淘汰过半截的逻辑行冻结在头部，不再重排")
        val cRows = s.snapshot().filter { s.textOf(it).startsWith("c") }
        assertEquals(listOf("cccccccccccc"), cRows.map { s.textOf(it) }, "其余逻辑行按新宽度合并回一排")
        assertEquals(deletedBefore, s.deletedLines, "重排不许改淘汰计数")
        assertEquals(lineCountBefore, s.lineCount, "重排不许改 LINECOUNT")
    }

    // ------------------------------------------------------------------
    // 按钮世代：等待期重排后仍可点
    // ------------------------------------------------------------------

    /**
     * 等待中的按钮重排后仍可点：世代取 journal 记录值，**不**重新盖
     * （`stampButtonGeneration` 会把世代推到 `newButtonGeneration`，旧页按钮整批作废）。
     */
    @Test
    fun `等待中的按钮重排后仍可点且世代不变`() {
        val s = sink(width = 12)
        s.printButton("[1] 选项甲", 1L)
        s.printButton("[2] 选项乙", 2L)
        s.newLine()
        noteWait(s, line = 77L)

        val optionsBefore = s.currentOptions()
        val identitiesBefore = buttonIdentities(s)
        assertEquals(2, optionsBefore.size, "本页两颗按钮都可点")
        assertEquals(2, s.displayOptions().size)

        assertTrue(s.resize(24))

        assertEquals(identitiesBefore, buttonIdentities(s), "按钮世代必须是打印时盖下的那一个")
        assertEquals(
            optionsBefore.map { it.value to it.generation },
            s.currentOptions().map { it.value to it.generation },
            "等待快照不受重排影响",
        )
        assertTrue(
            s.displayOptions().isNotEmpty(),
            "活表口径仍认这些按钮为当前世代（重新盖世代会让它判成旧页而清空）",
        )
    }

    /** 同一脚本行重复进入等待（世代不动）后重排，按钮仍可点。 */
    @Test
    fun `重复等待同一脚本行后重排按钮仍可点`() {
        val s = sink(width = 12)
        s.printButton("[1] only", 1L)
        s.newLine()
        noteWait(s, line = 9L)
        noteWait(s, line = 9L)
        assertEquals(1, s.currentOptions().size)

        assertTrue(s.resize(30))

        assertEquals(1, s.currentOptions().size)
        assertEquals(1, s.displayOptions().size, "世代不变 ⇒ 重排后仍算当前页")
    }

    // ------------------------------------------------------------------
    // 宽度无关行
    // ------------------------------------------------------------------

    /** PRINTSINGLE / 直印 HTML 行不折行：重排前后都是同样的排数与文本，HTML 按钮世代不动。 */
    @Test
    fun `PRINTSINGLE 与直印 HTML 行宽度无关重排原样保留`() {
        val s = sink(width = 10)
        s.printSingleLine("single-line-that-should-not-wrap")
        s.printHtml("<div><button value='5'>五子棋</button></div>", option = false)
        s.newLine()

        val textsBefore = logicalTexts(s)
        val htmlOptionsBefore = s.snapshot().flatMap { line -> line.htmlOptions.map { it.value to it.generation } }
        val rowsBefore = s.displayLineCount
        assertTrue(htmlOptionsBefore.isNotEmpty(), "直印 HTML 的按钮先抽出来")

        assertTrue(s.resize(40))

        assertEquals(textsBefore, logicalTexts(s), "宽度无关行文本不变")
        assertEquals(rowsBefore, s.displayLineCount, "PRINTSINGLE/HTML 行不因变宽而合并")
        assertEquals(
            htmlOptionsBefore,
            s.snapshot().flatMap { line -> line.htmlOptions.map { it.value to it.generation } },
            "HTML 按钮的世代不许被重排改写",
        )
    }

    // ------------------------------------------------------------------
    // 参数与并发
    // ------------------------------------------------------------------

    /** `newWidth` 非正一律拒绝（开关开着或关着都抛，口径先于开关判定）。 */
    @Test
    fun `非正宽度抛 IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { sink(width = 20).resize(0) }
        assertFailsWith<IllegalArgumentException> { sink(width = 20).resize(-3) }
        assertFailsWith<IllegalArgumentException> { sink(width = 20, enabled = false).resize(0) }
    }

    /**
     * 写线程持续打印、另一线程来回改宽：不许丢字、不许串行、LINECOUNT 不变。
     *
     * 只钉「重排与 flush 并发时行表仍是一致的逻辑行序列」（契约 §3.2 的「禁止新旧宽度
     * 混在同一组」由 appendGroup 的入列前重排保证，这里做压力冒烟）。
     */
    @Test
    fun `并发打印与重排不丢字不串行`() {
        val s = sink(width = 20, maxLines = 500)
        val expected = (0 until 120).map { "行$it:" + "文".repeat(it % 12) }
        val writer = Thread {
            for (text in expected) s.printLine(text)
        }
        val resizer = Thread {
            var w = 20
            repeat(60) {
                w = if (w == 20) 40 else 20
                s.resize(w)
                Thread.yield()
            }
        }
        writer.start()
        resizer.start()
        writer.join()
        resizer.join()

        assertEquals(expected, logicalTexts(s), "并发重排不许丢字/串行")
        assertEquals(expected.size.toLong(), s.lineCount, "LINECOUNT 不受重排影响")
        assertEquals(20, s.clientWidth, "最后一次 resize 是 20")
        assertEquals("-".repeat(20), s.stBar)
    }
}
