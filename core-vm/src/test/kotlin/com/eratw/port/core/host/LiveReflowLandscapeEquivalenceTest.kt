package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LIVE-REFLOW L7（回归路）核心侧的**两条等价**钉子（契约 CONTRACT-LIVE-REFLOW §3.3 / §8 第 4 条）。
 *
 * ## A：开关关 = 既有横屏路径逐字等价（红线）
 *
 * 同一段打印脚本分别喂给 `reflowEnabled=false` 与 `reflowEnabled=true`（且**不调** `resize`）
 * 的两个 [LineBufferConsoleSink]，**每一步之后**比全部可见状态：显示行表逐字段
 * （[LineBufferConsoleSink.DisplayLine] 的全部槽位：文本段、列号、对齐、背景色、`isTemporary`/`isLineEnd`/
 * `isLogicalLine`/`noBreak`、按钮与 HTML 按钮的世代与身份）、`serial` 序列、[LineBufferConsoleSink.lineNo]、
 * [LineBufferConsoleSink.lineCount]、[LineBufferConsoleSink.deletedLines]、[LineBufferConsoleSink.revision]、[LineBufferConsoleSink.stBar]、三份选项快照。
 * 覆盖打印面：`printLine`／PRINTN 未结并入、`PRINTSINGLE`、`PRINTPLAIN`、直印与缓冲 HTML、
 * 显式按钮与自动按钮、`DRAWLINE`/`CUSTOMDRAWLINE`、错误行、`CLEARLINE`、REUSELASTLINE
 * 临时行先删、MaxLog 淘汰（含多排组被吃前半截）。
 * 这一层同时钉住「journal 的开销不改变任何行为」——开态多出来的只有记账，没有可见差异。
 *
 * ## B：开关开 = 重排后与「一开始就在新宽度的一局」等价
 *
 * 20 格打印 → [LineBufferConsoleSink.resize] 到 12 格，与另一台**直接在 12 格构造**、打同一批行的 sink 比：
 * 逻辑行内容（按 [LineBufferConsoleSink.DisplayLine.isLogicalLine] 归组后逐字符拼接）、[LineBufferConsoleSink.lineCount]、
 * 按钮值/`groupId`/世代/标签/来源、[LineBufferConsoleSink.stBar] 长度、[LineBufferConsoleSink.lineNo]。
 *
 * B 的等价强度按程序的形状分档，两档都是**契约语义**而不是缺陷：
 * - 没有 PRINTN 并入、没有打印期就吃掉宽度的语句（`DRAWLINE`）时：重排后的行表与新宽一局
 *   **逐行逐字段相同**（只 serial 不同代）——这是能钉到的最强形态；
 * - 有 PRINTN 并入时：原版并入**不重新折行**（`mergeRows` 只把两段段表接起来，
 *   EmueraConsole.cs:2947-2967 + ADR-005），所以物理换行位置可以与「一开始就 12 格」不同，
 *   但逻辑行的字符必须逐字一致；
 * - `DRAWLINE` 打的是**打印那一刻**的 [LineBufferConsoleSink.stBar]，重排只把它按新宽度重新折行、不会补字或删字，
 *   所以重排前打的分隔线内容必然等于旧宽度那串（见用例「重排前打出的分隔线保留旧宽度串」）。
 *
 * 本文件只新增测试，不改任何生产文件。
 */
class LiveReflowLandscapeEquivalenceTest {

    // ------------------------------------------------------------------
    // 夹具：一次读取「全部可见状态」的尺
    // ------------------------------------------------------------------

    private fun closed(width: Int = 20, maxLines: Int = 200) =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines)

    private fun opened(width: Int = 20, maxLines: Int = 200) =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines, reflowEnabled = true)

    /** 一次读取的全部可见状态（A 档「逐字等价」比的就是它）。 */
    private data class State(
        val width: Int,
        val rows: List<String>,
        val serials: List<Long>,
        val lineNo: Int,
        val lineCount: Long,
        val deletedLines: Long,
        val revision: Long,
        val stBar: String,
        val currentOptions: List<String>,
        val displayOptions: List<String>,
        val publishedOptions: List<String>,
    )

    private fun stateOf(s: LineBufferConsoleSink): State {
        val lines = s.snapshot()
        return State(
            width = s.clientWidth,
            // DisplayLine / Segment / Button / HtmlOption 全是 data class：toString 覆盖全部槽位
            rows = lines.map { it.toString() },
            serials = lines.map { it.serial },
            lineNo = s.lineNo,
            lineCount = s.lineCount,
            deletedLines = s.deletedLines,
            revision = s.revision,
            stBar = s.stBar,
            currentOptions = s.currentOptions().map { it.toString() },
            displayOptions = s.displayOptions().map { it.toString() },
            publishedOptions = s.publishedOptions().map { it.toString() },
        )
    }

    /** 行表去掉 serial 代际差的形态：B 档拿它比「重排后」与「新宽一局」的逐行逐字段。 */
    private fun rowsWithoutSerial(s: LineBufferConsoleSink): List<String> =
        s.snapshot().map { it.copy(serial = 0L).toString() }

    /** 每条逻辑行的纯文本（折行续行按 `isLogicalLine` 归组；头部被吃掉的半截自成一组）。 */
    private fun logicalTexts(s: LineBufferConsoleSink): List<String> {
        val out = ArrayList<StringBuilder>()
        for (line in s.snapshot()) {
            if (line.isLogicalLine || out.isEmpty()) out.add(StringBuilder())
            out.last().append(s.textOf(line))
        }
        return out.map { it.toString() }
    }

    /**
     * 按钮身份元组。[withColumns] = true 时带列号（A 档同宽可比），false 时只比语义身份
     * （B 档允许物理换行位置不同，列号自然不同）。
     */
    private fun buttonIdentities(s: LineBufferConsoleSink, withColumns: Boolean): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<Int>()
        for (line in s.snapshot()) {
            for (b in line.buttons) {
                if (b.groupId != LineBufferConsoleSink.ID_UNKNOWN && !seen.add(b.groupId)) continue
                val columns = if (withColumns) "|${b.startCol}-${b.endCol}" else ""
                out.add("${b.groupId}|${b.value}|${b.generation}|${b.origin}|${b.label}$columns")
            }
            for (h in line.htmlOptions) {
                if (h.groupId != LineBufferConsoleSink.ID_UNKNOWN && !seen.add(h.groupId)) continue
                val columns = if (withColumns) "|${h.startCol}-${h.endCol}" else ""
                out.add("${h.groupId}|${h.value}|${h.generation}|HTML|${h.label}$columns")
            }
        }
        return out
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

    /**
     * 关态/开态孪生：同一步动作喂两只 sink，**每一步之后**比全部可见状态。
     *
     * 只允许「两态都能做且应当同形」的动作；`resize(新宽)` 不属于这里（开态会真重排），
     * 同宽 `resize` 属于（两态都必须返回 false 且零变化）。
     */
    private inner class Twin(val width: Int, val maxLines: Int) {
        val off: LineBufferConsoleSink = closed(width, maxLines)
        val on: LineBufferConsoleSink = opened(width, maxLines)
        private var seq = 0

        fun step(name: String, action: (LineBufferConsoleSink) -> Unit) {
            seq++
            action(off)
            action(on)
            assertEquals(
                stateOf(off),
                stateOf(on),
                "第 $seq 步「$name」之后：开 journal 与关 journal 必须逐项一致（宽度 $width）",
            )
        }

        /** 同宽 `resize`：两态都必须 false，且状态仍然逐项一致（契约 §3.3 第二条）。 */
        fun sameWidthResize() {
            assertFalse(off.resize(width), "关态 resize 恒 false")
            assertFalse(on.resize(width), "开态同宽 resize 必须 false（契约 §3.2）")
            step("同宽 resize($width)") { }
        }
    }

    private fun twin(width: Int = 20, maxLines: Int = 200) = Twin(width, maxLines)

    // ==================================================================
    // A：开关关 = 既有横屏路径逐字等价（含 journal 开销不改变行为）
    // ==================================================================

    /** 打印面 `PRINT`/`PRINTLC`/超长折行/空串/空缓冲换行：开态与关态逐字段等价。 */
    @Test
    fun `printLine与超长折行开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("printLine 短行") { it.printLine("abc") }
        t.step("printLine 全角长行（折 2 排）") { it.printLine("あいうえおかきくけこさしすせそたちつてと") }
        t.step("printLine 半角超长（折 3 排）") {
            it.printLine("0123456789012345678901234567890123456789")
        }
        t.step("printLine 空串（原版忽略）") { it.printLine("") }
        t.step("newLine 空缓冲（补空格行）") { it.newLine() }
        t.step("print 不带 newLine（留在缓冲）") { it.print("未 flush") }
        t.sameWidthResize()
        t.step("print 文本内换行") { it.print("甲\n乙\n丙") }
        t.step("printLine 混合宽度") { it.printLine("半a全【括】号2") }
        t.step("flush 收尾") { it.newLine() }
    }

    /** PRINTN 未结行并入分支：开 journal 后底稿合并不得改变任何既有可见状态。 */
    @Test
    fun `PRINTN未结行并入开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("PRINTN 未结") {
            it.print("前段", false)
            it.newLine()
        }
        t.step("续打并入（同一条显示行）") {
            it.print("后段", true)
            it.newLine()
        }
        t.sameWidthResize()
        t.step("PRINTN 之后直接 PRINTBUTTON（按钮不改 lineEnd）") {
            it.print("按钮前", false)
            it.newLine()
            it.printButton("确认", 9L)
            it.newLine()
        }
        t.step("PRINTN 折行组并入（原版吃掉前半截）") {
            it.print("あ".repeat(11), false) // 22 格 > 20 ⇒ 折 2 排
            it.newLine()
            it.print("尾", true)
            it.newLine()
        }
        t.step("未结行接 CLEARLINE") {
            it.print("未收尾", false)
            it.newLine()
            it.deleteLine(1)
        }
    }

    /** 按钮面（显式 PRINTBUTTON/PRINTBUTTONC 与文本自动按钮 `[1]`/`[100]`）逐字段等价。 */
    @Test
    fun `按钮面显式与自动按钮开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("两颗显式按钮同一行") {
            it.printButton("确定", 1L)
            it.print(" ")
            it.printButton("取消", "CANCEL")
            it.newLine()
        }
        t.step("自动按钮行 [1][2][3]") { it.printLine("[1] 战斗 [2] 休息 [3] 状态") }
        t.step("编号在末尾的移动[400]") { it.printLine("移动[400] 与 [10] 号") }
        t.step("超长按钮行（跨排拆分）") {
            it.printLine("开场白很长很长" + "[7] 选项" + "尾随文本很多很多很多")
        }
        t.step("PRINTBUTTONC 定宽按钮") {
            it.printButtonC("定宽", 5L, alignRight = true)
            it.newLine()
        }
        t.step("进入输入等待（选项快照）") { noteWait(it, line = 77L) }
        t.sameWidthResize()
        t.step("换页：另一批按钮 + 再次等待") {
            it.printButton("下一页", 2L)
            it.printLine("[8] 存档 [9] 读档")
            it.newLine()
            noteWait(it, line = 78L)
        }
    }

    /** 样式面（SETCOLOR/SETBACKCOLOR/FONTSTYLE/SETFONT/ALIGNMENT/PRINTC）逐字段等价。 */
    @Test
    fun `样式面颜色字体对齐开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("SETCOLOR") { it.setForeColor(255, 64, 32) }
        t.step("SETBACKCOLOR + 折行长行") {
            it.setBackColor(0, 128, 255)
            it.printLine("带底色的长文本带底色的长文本带底色的长文本")
        }
        t.step("FONTSTYLE + SETFONT") {
            it.setFontStyle(1 or 8)
            it.setFont("ＭＳ Ｐゴシック")
            it.printLine("粗体下划线")
        }
        t.step("ALIGNMENT CENTER 短行") {
            it.setAlignment(Alignment.CENTER)
            it.printLine("居中")
        }
        t.step("ALIGNMENT RIGHT 长行（折行）") {
            it.setAlignment(Alignment.RIGHT)
            it.printLine("右对齐的很长一段文字右对齐的很长一段文字")
        }
        t.step("回到 LEFT + RESETCOLOR 系") {
            it.setAlignment(Alignment.LEFT)
            it.setForeColor(192, 192, 192)
            it.setBackColor(0, 0, 0)
            it.setFontStyle(0)
            it.setFont("")
        }
        t.sameWidthResize()
        t.step("PRINTC 定宽列") {
            it.printC("列一", false)
            it.printC("列二", true)
            it.newLine()
        }
        t.step("USE_COLOR_STYLE 开关") {
            it.setUseSetColorStyle(false)
            it.printLine("不使用用户样式")
            it.setUseSetColorStyle(true)
        }
    }

    /** 宽度无关的打印面（PRINTSINGLE/PRINTPLAIN/REUSELASTLINE 临时行先删）逐字段等价。 */
    @Test
    fun `PRINTSINGLE与PRINTPLAIN与临时行先删开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("PRINTSINGLE 超长不折行") { it.printSingleLine("单行".repeat(20)) }
        t.step("PRINTPLAIN 里的方括号不转按钮") { it.printPlain("plain [1] 不是按钮") }
        t.step("newLine 空缓冲") { it.newLine() }
        t.step("REUSELASTLINE 临时行") { it.printTemporaryLine("temporary-1") }
        t.step("第二条临时行（先删上一条）") { it.printTemporaryLine("temporary-2 更长的一段临时行内容") }
        t.step("正式行（删掉临时行）") { it.printLine("正式") }
        t.sameWidthResize()
        t.step("临时行折行后再接正式行") {
            it.printTemporaryLine("あ".repeat(15))
            it.printLine("正式 2")
        }
    }

    /** HTML 面（直印 HTML_PRINT 与缓冲内 HTML 段、HTML 按钮跨行）逐字段等价。 */
    @Test
    fun `HTML面直印与缓冲段开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("直印 HTML（option=false）") { it.printHtml("<div>普 通</div>", option = false) }
        t.step("直印 HTML 带按钮") {
            it.printHtml("<button value='12'>开始</button> 与 <button value='x'>字符串钮</button>", option = false)
        }
        t.step("直印 HTML 跨行按钮（<br>）") {
            it.printHtml("<button value='77'>很长的按钮文本<br>第二行</button>", option = false)
        }
        t.sameWidthResize()
        t.step("缓冲内 HTML 段（option=true，随正文折行）") {
            it.printHtml("<b>加粗片段</b>后随一长串普通文本一长串普通文本", option = true)
            it.newLine()
        }
        t.step("HTML 段 + 按钮混排") {
            it.printHtml("<i>斜体</i>", option = true)
            it.printButton("按钮", 3L)
            it.printHtml("<u>下划线</u>", option = true)
            it.newLine()
            noteWait(it, line = 91L)
        }
        t.step("PRINTSINGLE 式 HTML 与 clearbutton") {
            it.printHtml("<clearbutton><button value='5'>不可点</button></clearbutton>可点<button value='6'>可点</button>", option = false)
        }
    }

    /** DRAWLINE / CUSTOMDRAWLINE：分隔线串在构造期与关态同源，开 journal 后逐字段等价。 */
    @Test
    fun `DRAWLINE与自定义分隔线开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("DRAWLINE") {
            it.printBar()
            it.newLine()
        }
        t.step("CUSTOMDRAWLINE '='") {
            it.printCustomBar("=")
            it.newLine()
        }
        t.step("分隔线夹在正文之间") {
            it.printLine("正文")
            it.printBar()
            it.printLine("正文 2")
            it.newLine()
        }
        t.sameWidthResize()
        assertEquals("-".repeat(20), t.off.stBar)
        assertEquals(t.off.stBar, t.on.stBar, "stBar 必须逐字相同（它同时是 DRAWLINESTR 的数据源）")
    }

    /** 错误行（PrintError：独立、强制左对齐、不折行、默认色）逐字段等价。 */
    @Test
    fun `错误行开journal与关journal逐字段等价`() {
        val t = twin(width = 20)
        t.step("错误行") { it.printError("错误：出事了") }
        t.step("错误行前有未 flush 文本") {
            it.print("未结的正文", false)
            it.printError("错误 2")
        }
        t.step("错误行是超长串（不折行）") { it.printError("Ｅ".repeat(30)) }
        t.step("错误行之后的正常行恢复用户样式") { it.printLine("正常") }
        t.sameWidthResize()
    }

    /** CLEARLINE 与 MaxLog 淘汰（含多排组被吃前半截、补行）逐字段等价。 */
    @Test
    fun `CLEARLINE与MaxLog淘汰开journal与关journal逐字段等价`() {
        val t = twin(width = 10, maxLines = 5)
        t.step("折行组 2 排") { it.printLine("ああああああ") }
        t.step("单排") { it.printLine("b") }
        t.step("第二个折行组") { it.printLine("cccccccccccc") }
        t.step("挤掉头部一排") { it.printLine("d") }
        t.step("CLEARLINE 1") { it.deleteLine(1) }
        t.step("CLEARLINE 3（删不够会硬扣）") { it.deleteLine(3) }
        t.step("删空后再打印") {
            it.deleteLine(99)
            it.printLine("重建")
        }
        t.sameWidthResize()
        t.step("继续把 MaxLog 打满") { sink ->
            repeat(6) { i -> sink.printLine("行$i 一段较长的文本") }
        }
        // 夹具自检：这条用例必须真的走到淘汰/删除记账，否则「逐字段等价」只是在比两份空表。
        assertTrue(t.off.displayLineCount <= 5, "MaxLog 夹取必须生效")
        assertNotEquals(0L, t.off.deletedLines, "夹具必须真的走到淘汰或 CLEARLINE 记账分支")
    }

    /**
     * 关态红线（契约 §0 第 4 条 + §3.3）：`reflowEnabled=false` 时 `resize` 恒 false，
     * 且与开态同宽 false 的两只 sink 在任意打印脚本后仍逐项一致。
     */
    @Test
    fun `关态resize恒false且与开态同宽false逐项一致`() {
        val t = twin(width = 20)
        t.step("一批混合打印") {
            it.printLine("[1] 首行")
            it.print("未结", false)
            it.newLine()
            it.printButton("钮", 8L)
            it.printLine("あ".repeat(25))
            it.newLine()
            it.printTemporaryLine("临时")
            noteWait(it)
        }
        val offBefore = stateOf(t.off)
        val onBefore = stateOf(t.on)

        assertFalse(t.off.resize(12), "关态必须返回 false")
        assertFalse(t.off.resize(20))
        assertEquals(20, t.off.clientWidth, "关态不许改宽度")
        assertEquals("-".repeat(20), t.off.stBar, "关态不许重算 stBar")
        assertEquals(offBefore, stateOf(t.off), "关态 resize 前后逐字段不变")

        assertFalse(t.on.resize(20), "开态同宽必须返回 false（契约 §3.2）")
        assertEquals(onBefore, stateOf(t.on), "开态同宽 resize 前后逐字段不变（含 serial/lineNo/revision）")
        assertEquals(stateOf(t.off), stateOf(t.on))
    }

    /**
     * `require(newWidth > 0)` 判在开关之前（契约 §3.2 第一条）：关态与开态都抛，
     * 且抛错前后全部可见状态逐字段不变、两态仍然相同。
     */
    @Test
    fun `非正宽度两态都抛且抛后状态逐项不变`() {
        val t = twin(width = 20)
        t.step("一批混合打印") {
            it.printLine("[1] 首行")
            it.printLine("あ".repeat(20))
            it.printButton("钮", 8L)
            it.newLine()
            noteWait(it)
        }
        val offBefore = stateOf(t.off)
        val onBefore = stateOf(t.on)

        for (bad in intArrayOf(0, -1, -20)) {
            assertFailsWith<IllegalArgumentException> { t.off.resize(bad) }
            assertFailsWith<IllegalArgumentException> { t.on.resize(bad) }
        }

        assertEquals(offBefore, stateOf(t.off), "抛错前后逐字段不变（关态）")
        assertEquals(onBefore, stateOf(t.on), "抛错前后逐字段不变（开态：不许把 journal 改坏）")
        assertEquals(stateOf(t.off), stateOf(t.on))
    }

    // ==================================================================
    // B：开关开 = 重排后与「一开始就在新宽度的一局」等价
    // ==================================================================

    /**
     * 最强形态：程序里没有 PRINTN 并入、没有打印期就吃宽度的语句时，
     * 20 格打印 → 重排到 12 格 的行表，与**直接在 12 格打印**的行表
     * 逐行逐字段相同（serial 除外：两台的序号本来就是不同代际），
     * `LINECOUNT`、[LineBufferConsoleSink.lineNo]、`deletedLines`、stBar 也相同。
     */
    @Test
    fun `重排到新宽度的行表与一开始就在新宽度逐字段相同`() {
        val program: (LineBufferConsoleSink) -> Unit = { s ->
            s.printLine("第 1 行简短")
            s.printLine("第 2 行：很长很长的正文很长很长的正文很长") // 20 折 2 排 / 12 折 3 排
            s.printLine("[1] 战斗 [2] 休息 [3] 状态")
            s.printButton("确定", 7L)
            s.print(" ")
            s.printButton("取消", "CANCEL")
            s.newLine()
            s.setAlignment(Alignment.CENTER)
            s.printLine("居中短行")
            s.setAlignment(Alignment.LEFT)
            s.setBackColor(10, 20, 30)
            s.printLine("带底色的一行")
            s.setBackColor(0, 0, 0)
            s.print("abc", false)
            s.newLine()
            s.print("defgh", true)
            s.newLine() // PRINTN 并入：整条逻辑行 8 格，在 20 与 12 两个宽度下都是单排
            s.printSingleLine("PRINTSINGLE 超长超长超长超长超长超长")
            s.printPlain("PRINTPLAIN 纯文本")
            s.printHtml("<button value='21'>直印按钮</button>", option = false)
            s.printError("错误行")
            s.printTemporaryLine("临时行")
            s.printLine("正式行收尾")
        }

        val reflowed = opened(width = 20)
        program(reflowed)
        val rowsBefore = reflowed.displayLineCount
        assertTrue(reflowed.resize(12), "20 → 12 是真的变宽，必须重排")

        val fresh = closed(width = 12)
        program(fresh)

        assertEquals(
            rowsWithoutSerial(fresh),
            rowsWithoutSerial(reflowed),
            "重排后的行表必须与「一开始就 12 格」逐行逐字段相同（列号/世代/标志/段文本全在内）",
        )
        assertEquals(logicalTexts(fresh), logicalTexts(reflowed))
        assertEquals(fresh.lineCount, reflowed.lineCount, "LINECOUNT 等价")
        assertEquals(fresh.lineNo, reflowed.lineNo, "无淘汰时 lineNo = 物理行数，两台必须相同")
        assertEquals(fresh.deletedLines, reflowed.deletedLines)
        assertEquals(fresh.stBar, reflowed.stBar, "stBar 按新宽度重算后与新宽一局相同")
        assertEquals(buttonIdentities(fresh, withColumns = true), buttonIdentities(reflowed, withColumns = true))
        assertTrue(reflowed.displayLineCount > rowsBefore, "变窄后物理行只会变多")

        // serial 是「代际」读数：重排重新发号，只要求严格递增不重号。
        val serials = reflowed.snapshot().map { it.serial }
        assertEquals(serials.size, serials.toSet().size, "重排后不许出现重复序号")
        assertTrue(
            serials.zipWithNext().all { (a, b) -> b - a == 1L },
            "重排后的 serial 必须是连续递增的：$serials",
        )
    }

    /**
     * 带 PRINTN 并入的宽程序：允许物理换行位置不同（原版并入不重新折行，ADR-005），
     * 但逻辑行内容逐字符必须一致、`LINECOUNT` 一致、按钮值与 `groupId`/世代一致、
     * stBar 长度 = 新宽度。
     */
    @Test
    fun `重排后逻辑行内容与按钮与新宽一局等价（允许换行位置不同）`() {
        val widthBefore = 20
        val widthAfter = 12
        val program: (LineBufferConsoleSink) -> Unit = { s ->
            s.printLine("开场白")
            // 未结行的两段各 <= 12 格：在任何一侧都不会被折行「吃掉前半截」
            s.print("甲段", false)
            s.newLine()
            s.print("乙丙丁段", true)
            s.newLine()
            s.printLine("很长的正文很长的正文很长的正文")
            s.printLine("[12] 选项甲 [13] 选项乙")
            s.printButton("按钮一", 1L)
            s.printButton("按钮二", "TWO")
            s.newLine()
            noteWait(s, line = 55L)
            s.printLine("收尾行")
        }

        val reflowed = opened(width = widthBefore)
        program(reflowed)
        val rowsBefore = reflowed.displayLineCount
        val lineNoBefore = reflowed.lineNo
        assertTrue(reflowed.resize(widthAfter))
        val rowsAfter = reflowed.displayLineCount

        val fresh = closed(width = widthAfter)
        program(fresh)

        assertEquals(logicalTexts(fresh), logicalTexts(reflowed), "逻辑行内容逐字符一致")
        assertEquals(
            logicalTexts(reflowed).joinToString(""),
            reflowed.snapshot().joinToString("") { reflowed.textOf(it) },
            "逐字符拼接即全表文本（不允许丢字/串行）",
        )
        assertEquals(fresh.lineCount, reflowed.lineCount, "LINECOUNT 等价")
        assertEquals(buttonIdentities(fresh, false), buttonIdentities(reflowed, false), "按钮值/groupId/世代等价")
        assertEquals(widthAfter, reflowed.stBar.length, "stBar 长度 = 新宽度")
        assertEquals(fresh.stBar, reflowed.stBar)
        // lineNo 的差值口径（契约 §3.2）：新物理行数 - 旧物理行数
        assertEquals(lineNoBefore + (rowsAfter - rowsBefore), reflowed.lineNo, "lineNo 按物理行差调整")
        assertTrue(reflowed.lineNo >= 0, "lineNo 必须保持非负（契约 §3.2）")
    }

    /** 重排前打出的 DRAWLINE：内容仍是**旧宽度**那一串，只按新宽度重新折行（打印期语义，非缺陷）。 */
    @Test
    fun `重排前打出的分隔线保留旧宽度串并按新宽度折行`() {
        val s = opened(width = 20)
        s.printBar()
        s.newLine()
        s.printLine("正文")

        assertTrue(s.resize(12))

        assertEquals("-".repeat(12), s.stBar, "stBar 本身按新宽度重算")
        val rows = s.snapshot()
        assertEquals("-".repeat(12), s.textOf(rows[0]), "旧宽度的 20 格串在 12 格下折成两排")
        assertEquals("--------", s.textOf(rows[1]), "第二排承接剩下的 8 个连字符，一个字都不许多/少")
        assertEquals(3, s.displayLineCount, "分隔线 2 排 + 正文 1 排")
        assertTrue(rows[0].isLogicalLine && !rows[1].isLogicalLine, "两排同属一条逻辑行")
        assertEquals("正文", s.textOf(rows[2]), "正文按新宽度仍是单排")

        // 重排之后再打分隔线用的就是新宽度
        s.printBar()
        s.newLine()
        assertEquals("-".repeat(12), s.textOf(s.snapshot().last()))
    }

    /** 变窄导致物理行越过 MaxLog：走既有淘汰记账，`LINECOUNT` 与淘汰计数各按契约口径。 */
    @Test
    fun `重排放大到越过MaxLog时走既有淘汰记账`() {
        val s = opened(width = 20, maxLines = 5)
        s.printLine("第一行正文很长") // 14 格：20 宽一排、10 宽两排
        s.printLine("第二行正文很长")
        s.printLine("第三行正文很长")
        assertEquals(3, s.displayLineCount)
        val lineCountBefore = s.lineCount
        val deletedBefore = s.deletedLines

        assertTrue(s.resize(10), "20 → 10 必然把每行折成两排，超过 MaxLog")
        assertEquals(5, s.displayLineCount, "淘汰后停在 MaxLog")
        assertTrue(s.deletedLines > deletedBefore, "越过 MaxLog 必须按既有规则累加 deletedLines")
        assertEquals(lineCountBefore, s.lineCount, "重排与淘汰都不许改 LINECOUNT")
        assertEquals("-".repeat(10), s.stBar)
        assertTrue(s.lineNo >= 0)
        assertEquals("很长", s.textOf(s.snapshot().first()), "被吃掉前半截的逻辑行冻结在头部（契约 §3.1）")
    }

    /**
     * 重排后继续打印：新行按新宽度折行、未结行的 journal 底稿仍然接得上，
     * 与「新宽一局」打到同一处时逻辑行内容一致。
     */
    @Test
    fun `重排之后继续打印与未结行并入仍按新宽度`() {
        val s = opened(width = 20)
        s.print("前半", false)
        s.newLine()
        assertTrue(s.resize(12))
        assertFalse(s.snapshot().last().isLineEnd, "重排不许把未结行改成已结")

        s.print("后段", true)
        s.newLine()
        assertEquals(1, s.displayLineCount, "并入后仍是同一条显示行")
        assertEquals("前半后段", s.textOf(s.snapshot().single()))
        assertEquals(1L, s.lineCount)

        s.printLine("重排后新打印的长行") // 18 格 > 12 ⇒ 按新宽度折 2 排
        assertEquals(3, s.displayLineCount)

        val fresh = closed(width = 12)
        fresh.print("前半", false)
        fresh.newLine()
        fresh.print("后段", true)
        fresh.newLine()
        fresh.printLine("重排后新打印的长行")
        assertEquals(rowsWithoutSerial(fresh), rowsWithoutSerial(s), "重排后续打的结果与新宽一局逐字段相同")
        assertEquals(fresh.lineCount, s.lineCount)
        assertEquals(fresh.lineNo, s.lineNo)
    }

    /**
     * 被 MaxLog 吃掉前半截的逻辑行冻结不重排（契约 §3.1 已知边界）：
     * 重排后它原样留在头部，其余逻辑行与新宽一局逐字段相同。
     */
    @Test
    fun `头部冻结的半截逻辑行不参与重排其余与新宽一局相同`() {
        val s = opened(width = 10, maxLines = 5)
        s.printLine("ああああああ") // 12 格 → 2 排
        s.printLine("b")
        s.printLine("cccccccccccc")
        s.printLine("d") // 挤掉头一排：只剩半截「あ」

        assertEquals("あ", s.textOf(s.snapshot().first()))
        assertTrue(s.resize(30))

        assertEquals("あ", s.textOf(s.snapshot().first()), "冻结的半截原样留在头部，不复活成整行")

        val tail = s.snapshot().drop(1).map { it.copy(serial = 0L).toString() }
        // 冻结行的前半截已被 MaxLog 吃掉，新宽一局里没有可对照的完整行 ⇒ 只比它之后的部分
        val fresh = closed(width = 30)
        fresh.printLine("b")
        fresh.printLine("cccccccccccc")
        fresh.printLine("d")
        assertEquals(
            fresh.snapshot().map { it.copy(serial = 0L).toString() },
            tail,
            "除冻结行以外的部分与「一开始就 30 格」逐字段相同",
        )
    }
}
