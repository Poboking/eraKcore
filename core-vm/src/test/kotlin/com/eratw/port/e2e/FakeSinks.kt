package com.eratw.port.e2e

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.Alignment
import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.sem.SystemOps

// ===========================================================================
// A6 e2e-harness 假宿主 sink
//
// 文字流出口 = [LineConsoleSink]：把 ConsoleSink 的离散事件重建成**行序列**，
// 行模型近似原版 EmueraConsole 的回放缓冲（print 追加、newLine 结行）。
// 颜色/字体/对齐/重绘**不进入文本流**（docs/golden-runbook.md §5 的明确结论），
// 只记入 [LineConsoleSink.styleLog] 供诊断。
//
// 已知的口径近似（接口点）：
//  - printC/PRINTLC：按 Shift-JIS 字节数 + Config.PrintCLength=25 补空格（EmueraConsole.cs:3229-3296）；
//    裁剪循环以等宽字体近似 GetDisplayLength（fixture 仅 ASCII，逐字符=1）。
//  - printBar/printCustomBar：以 [barWidth] 个字符填充；归一化把 `={10,}`/`-{10,}`
//    整行折叠为 LINE(=)/LINE(-)，其它填充字符的行**保留原文**。
//  - printButton*：按钮文本按原文入流（value 不入文本）。
//  - printHtml：去标签粗渲染（<br>→换行、剥 <>、常见实体），近似 PC 文本回放。
//
// CONSOLE-2 起本类实现 [GoldenConsoleSink]（与生产门面 [ProductionConsoleSink] 共同的
// 查询面）。E2E 黄金对拍已经换到生产 sink 上跑，本类退为 GoldenTextParityTest 的对拍臂
// 与 boot/l3 两处的输出口径，所以**公开 API 一个都不动**（含具名参数构造与
// snapshot/styleSnapshot/displayLines/poppingLines），只追加 `override` 与
// [LineConsoleSink.loggedLines]。
// ===========================================================================

// 默认 199 = PC golden 运行环境的 Config.DrawableWidth（EmueraConsole.cs:3426-3437）；
// 宽度本身属宿主环境，取基准同值以便 DRAWLINE 文本可直接对拍。
class LineConsoleSink(
    var barWidth: Int = 199,
    /** PRINTC 定宽；PC 基准实测 25（ConfigData.cs:64 默认值同值）。 */
    var printCLength: Int = 25,
) : GoldenConsoleSink {
    /** 已提交的显示行。temporary 行（REUSELASTLINE/系统临时行）不进日志文本，但计 LINECOUNT。 */
    private data class Line(
    val text: String,
    val temporary: Boolean,
    val counted: Boolean = true,
    /** 提交时刻的对齐（ConsoleDisplayLine.Align 在 flush 时定格）。 */
    val align: Alignment = Alignment.LEFT,
)

    private val done = ArrayList<Line>()
    /**
     * 当前逻辑文本行：原版 PrintBuffer.flush 会清空缓冲，但 IsLineEnd=false 的显示行
     * 在 addRangeDisplayLine 里并入上一行（EmueraConsole.cs:2948-2977）。
     * [merged] 承载已 flush 的未结段，[buffer] 承载未 flush 的待定段。
     */
    private val merged = StringBuilder()
    private val buffer = StringBuilder()

    /** 最近一段的行结束标记。spec: PrintStringBuffer.isLastLineEnd */
    private var lastLineEnd = true
    private var logicalCount = 0L

    /** 非文本流动作（颜色/字体/对齐/重绘）诊断日志。 */
    val styleLog = ArrayList<String>()

    // ---- 样式状态（GETCOLOR 家族数据源；PC 基准环境值 = 游戏 emuera.config:19-23）----
    // FONT NAME:黑体 / TEXT COLOR:192,192,192 / BACKGROUND COLOR:0,0,0 / HIGHLIGHT COLOR:255,255,0
    private val pcDefFore = intArrayOf(192, 192, 192)
    private val pcDefBack = intArrayOf(0, 0, 0)
    private val pcFocus = intArrayOf(255, 255, 0)
    private val PC_FONT_NAME = "黑体"

    /** 当前前景色；null = 未显式设置（RESETCOLOR 后 → GETCOLOR 回落默认色）。 */
    private var fore: IntArray? = null
    private var back: IntArray? = null
    private var fontStyleMask = 0
    private var fontNameCur = PC_FONT_NAME
    private var alignmentCur = Alignment.LEFT
    private var redrawCur = 0

    // 生产读线（ConsoleSink 的「样式读出侧」）：与 styleSnapshot() 同一批字段，
    // 让 BuiltinHost.consoleStyle 的唯一装配 consoleStyleOf(sink) 也能读到本夹具的活状态。
    // 两处读数同源，不存在第二份状态。
    override val foregroundColor: Int? get() = fore?.let { (it[0] shl 16) or (it[1] shl 8) or it[2] }
    override val backgroundColor: Int? get() = back?.let { (it[0] shl 16) or (it[1] shl 8) or it[2] }
    override val fontName: String? get() = fontNameCur
    override val alignment: Alignment get() = alignmentCur
    override val redraw: Int get() = redrawCur

    /** GETCOLOR 家族快照（[com.eratw.port.core.fn.ConsoleStyleSnapshot]）。 */
    override fun styleSnapshot(): com.eratw.port.core.fn.ConsoleStyleSnapshot =
        com.eratw.port.core.fn.ConsoleStyleSnapshot(
            fore = fore, back = back,
            defFore = pcDefFore, defBack = pcDefBack, focus = pcFocus,
            fontStyleMask = fontStyleMask, fontName = fontNameCur,
            alignment = when (alignmentCur) {
                Alignment.LEFT -> 0; Alignment.CENTER -> 1; Alignment.RIGHT -> 2
            },
            redraw = redrawCur,
        )

    /**
     * LINECOUNT：仅 IsLineEnd 的逻辑行 +1（含 temporary）。spec: EmueraConsole.cs:2971-2977；
     * deleteLine 回退见 :3019/:3035。
     */
    override val lineCount: Long get() = logicalCount

    /**
     * 当前打印缓冲是否**无待决内容**（LINEISEMPTY 的数据源）。
     *
     * 语义 = 未 flush 的打印缓冲空态，**不是**"上一显示行是否为空"：任何 PRINTL / 换行
     * flush 之后即为空（故为 true）。spec: EmueraConsole.cs:505
     * `public bool EmptyLine => printBuffer.IsEmpty;`；PrintStringBuffer.cs:39-48 的 IsEmpty
     * （buttonList / builder / stringList 三空判定）；clearBuffer()（:273-278）在
     * Flush（:179-186）/ FlushSingleLine（:170-177）末尾调用 → flush 后必空。
     *
     * 对应本 sink 的 [buffer]（未 flush 的待定段），与已提交显示行模型（[done]/[merged]）无关。
     */
    override val printingBufferEmpty: Boolean get() = buffer.isEmpty()

    /** 当前完整快照：已结行（不含 temporary）+ 未结的当前行（若有）。 */
    override fun snapshot(): List<String> {
        val out = done.filterNot { it.temporary }.mapTo(ArrayList()) { it.text }
        val cur = merged.toString() + buffer.toString()
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * 日志文本行 = [snapshot]：假 sink 的行序列就是它的日志口径。
     *
     * 与生产实现的差别（CONSOLE-2 记录在案）：这里**含**未 flush 的当前行，
     * 而原版 GetLog 与 [ProductionConsoleSink.loggedLines] 只回放已提交的显示行
     * （EmueraConsole.cs:3536-3543）。E2E 切到生产 sink 后，本方法只服务
     * GoldenTextParityTest 与 boot/l3 两处仍用假 sink 的装置。
     */
    override fun loggedLines(): List<String> = snapshot()

    /** 快照拼回全文（行间换行；与 PC 日志的文本流一致，末尾无换行）。 */
    override fun capturedText(): String = snapshot().joinToString("\n")

    fun reset() {
        done.clear(); merged.setLength(0); buffer.setLength(0)
        lastLineEnd = true; logicalCount = 0; styleLog.clear()
        fore = null; back = null; fontStyleMask = 0
        fontNameCur = PC_FONT_NAME; alignmentCur = Alignment.LEFT; redrawCur = 0
    }

    // ---- 行模型原语 ----------------------------------------------------------

    /** 追加文本（内嵌 \n 逐段结行）。VmHost.print 透传也走这里（同源，C6）。 */
    fun appendRaw(text: String) {
        var first = true
        for (part in text.split('\n')) {
            if (!first) flush()
            append(part, lineEnd = true)
            first = false
        }
    }

    /** 追加一段文本但不 flush。spec: EmueraConsole.Print → PrintStringBuffer.Append */
    private fun append(text: String, lineEnd: Boolean) {
        if (text.isEmpty()) return // 原版 Print 对空串直接返回，不改 isLastLineEnd
        buffer.append(text)
        lastLineEnd = lineEnd
    }

    /**
     * flush。空缓冲按原版 PrintFlush(force) 补一个空格并视为行结束
     * （EmueraConsole.cs:3373-3383）；IsLineEnd=false 时只并入 [merged]，不结行、不计 LINECOUNT。
     */
    private fun flush() {
        if (buffer.isEmpty()) {
            buffer.append(' ')
            lastLineEnd = true
        }
        merged.append(buffer)
        buffer.setLength(0)
        if (lastLineEnd) {
            commit(merged.toString())
            merged.setLength(0)
        }
    }

    /**
     * 提交一条逻辑行；超过 DrawableWidth 时按显示宽度折成多条显示行
     * （PrintStringBuffer.ButtonsToDisplayLines）。只有末段 IsLineEnd=true，
     * LINECOUNT 每逻辑行只 +1（EmueraConsole.cs:2971-2977）。
     */
    private fun commit(text: String) {
        var rest = text
        while (rest.length > barWidth) {
            done.add(Line(rest.substring(0, barWidth), temporary = false, counted = false, align = alignmentCur))
            rest = rest.substring(barWidth)
        }
        done.add(Line(rest, temporary = false, counted = true, align = alignmentCur))
        logicalCount++
    }

    /**
     * 已提交显示行（BuiltinHost.consoleLines 的数据源；HTML_GETPRINTEDSTR / GETDISPLAYLINE）。
     *
     * 形态对照原版 EmueraConsole.displayLineList（EmueraConsole.cs:238）：text = 行文本、
     * html = 行体 HTML（纯文本行走 [com.eratw.port.core.fn.htmlEscape] 口径——与引擎
     * HtmlManager.Escape 同一张表，避免两处漂移）、align = 提交时对齐。
     * temporary 行不进列表（原版 GetDisplayLines 只取 displayLineList）。
     */
    override fun displayLines(): List<com.eratw.port.core.fn.ConsolePrintedLine> =
        done.filter { !it.temporary }.map {
            com.eratw.port.core.fn.ConsolePrintedLine(
                text = it.text,
                html = com.eratw.port.core.fn.htmlEscape(it.text),
                align = it.align,
                isLogicalLine = it.counted,
            )
        }

    /**
     * 取出"正在打印中"的行并清空缓冲（BuiltinHost.popPrintingLines；HTML_POPPRINTINGSTR）。
     * 原版 PopDisplayingLines：取当前打印缓冲后 flush 清空（EmueraConsole.cs:3578-3588）。
     * PRINTFORML 之后缓冲已空 → 空表 → 函数返回 ""（对拍基准 pc-golden-root.txt:302）。
     */
    override fun poppingLines(): List<com.eratw.port.core.fn.ConsolePrintedLine> {
        val text = merged.toString() + buffer.toString()
        merged.setLength(0)
        buffer.setLength(0)
        return if (text.isEmpty()) emptyList()
        else listOf(
            com.eratw.port.core.fn.ConsolePrintedLine(
                text = text,
                html = com.eratw.port.core.fn.htmlEscape(text),
                align = alignmentCur,
            ),
        )
    }

    private fun standalone(text: String) {
        if (text.isEmpty()) return
        if (buffer.isNotEmpty()) flush()
        append(text, lineEnd = true)
        flush()
    }

    // ---- ConsoleSink ---------------------------------------------------------

    override fun print(text: String) = append(text, lineEnd = true)

    override fun print(text: String, lineEnd: Boolean) = append(text, lineEnd)

    override fun printLine(text: String) {
        append(text, lineEnd = true); flush()
    }

    override fun printC(text: String, alignRight: Boolean) {
        if (text.isEmpty()) return
        // PrintC → printBuffer.Append(padded, force_button:true)，lineEnd 缺省 true，不 flush。
        append(applyPrintC(text, alignRight), lineEnd = true)
    }

    override fun printSingleLine(text: String) = standalone(text)

    override fun printButton(text: String, value: Long) { append(text, lineEnd = true) }
    override fun printButton(text: String, value: String) { append(text, lineEnd = true) }

    override fun printButtonC(text: String, value: Long, alignRight: Boolean) {
        if (text.isNotEmpty()) append(applyPrintC(text, alignRight), lineEnd = true)
    }

    override fun printButtonC(text: String, value: String, alignRight: Boolean) {
        if (text.isNotEmpty()) append(applyPrintC(text, alignRight), lineEnd = true)
    }

    override fun printPlain(text: String) = append(text, lineEnd = true)

    override fun printTemporaryLine(text: String) {
        // PrintSingleLine(temporary:true)：先 flush 待定缓冲，再独立成一条临时显示行。
        if (text.isEmpty()) return
        if (buffer.isNotEmpty()) flush()
        merged.append(text)
        done.add(Line(merged.toString(), temporary = true))
        logicalCount++
        merged.setLength(0)
    }

    override fun printBar() {
        // 只追加不结行：原版 Process 随后显式 NewLine（Process.cs:1155-1161）。
        // 默认字符 = Config.DrawLineString（缺省 "-"，Config.cs:725-728）。
        append("-".repeat(barWidth), lineEnd = true)
    }

    override fun printCustomBar(text: String) {
        val fill = if (text.isEmpty()) "-" else text
        val sb = StringBuilder(barWidth)
        var i = 0
        while (sb.length < barWidth) { sb.append(fill[i % fill.length]); i++ }
        append(sb.toString(), lineEnd = true)
    }

    override fun newLine() = flush()

    override fun deleteLine(count: Int) {
        var n = count
        repeat(minOf(count, done.size)) {
            val line = done.removeAt(done.size - 1)
            if (line.counted && logicalCount > 0) logicalCount--
            n--
        }
        if (n > 0) { merged.setLength(0); buffer.setLength(0); lastLineEnd = true }
    }

    override fun clearText() { styleLog.add("clearText") }

    override fun setForeColor(r: Int, g: Int, b: Int) {
        styleLog.add("fg$r,$g,$b"); fore = intArrayOf(r, g, b)
    }
    override fun setBackColor(r: Int, g: Int, b: Int) {
        styleLog.add("bg$r,$g,$b"); back = intArrayOf(r, g, b)
    }
    override fun resetForeColor() { styleLog.add("fg-reset"); fore = null }
    override fun resetBackColor() { styleLog.add("bg-reset"); back = null }
    override fun setFontStyle(mask: Int) { styleLog.add("font$mask"); fontStyleMask = mask }
    override fun setFont(name: String) {
        styleLog.add("font:$name")
        // 空串恢复默认字体（原版 SetFont("")，spec: EmueraConsole.SetFont）
        fontNameCur = if (name.isEmpty()) PC_FONT_NAME else name
    }
    override fun setAlignment(kind: Alignment) { styleLog.add("align:${kind.name}"); alignmentCur = kind }
    // SetRedraw(i)：bit0 决定状态（0 → ConsoleRedraw.None，1 → Normal），bit1 = 立即重绘
    // （EmueraConsole.cs:1694-1703）。CURRENTREDRAW 读的是折减后的枚举，故只留 bit0。
    override fun setRedraw(mode: Int) { styleLog.add("redraw$mode"); redrawCur = mode and 1 }

    /** FONTBOLD/FONTITALIC 的 OR 基值（ConsoleSink.fontStyle）。 */
    override val fontStyle: Int get() = fontStyleMask

    override fun printHtml(text: String, option: Boolean) {
        appendRaw(htmlToText(text))
        flush()
    }

    override fun addHtmlIsland(text: String) { styleLog.add("island") }
    override fun clearHtmlIslands() { styleLog.add("island-clear") }

    override fun printError(text: String) { appendRaw(text); flush() }

    /**
     * PRINTC 定宽补齐。spec: EmueraConsole.cs:3245-3296 CreateTypeCString ——
     * 右对齐补前导空格、左对齐（PRINTLC）补尾随空格，再按显示宽度裁到 PrintCLength。
     */
    private fun applyPrintC(str: String, alignRight: Boolean): String {
        val bytes = sjisByteLength(str)
        return if (alignRight) {
            if (bytes < printCLength) {
                var s = " ".repeat(printCLength - bytes) + str
                while (s.length > printCLength && s.startsWith(' ')) s = s.substring(1)
                s
            } else str
        } else {
            if (bytes < printCLength + 1) {
                var s = str + " ".repeat(printCLength + 1 - bytes)
                while (s.length > printCLength && s.endsWith(' ')) s = s.substring(0, s.length - 1)
                s
            } else str
        }
    }

    /** 原版 CreateTypeCString 的字节数口径 = Shift-JIS（EmueraConsole.cs:3254-3257）。 */
    private fun sjisByteLength(s: String): Int =
        s.toByteArray(java.nio.charset.Charset.forName("Shift_JIS")).size

    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>|</div>|</tr>"), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        return s
    }
}

/** 输入回放：一律立即返回请求里的默认值（GOLDEN_06 依赖 SKIPLOG+CanSkip，不落到这里）。 */
class ReplayInputSink : InputSink {
    val requests = ArrayList<InputRequest>()
    var anyKeyCount = 0
        private set

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) { anyKeyCount++ }

    override fun waitInput(request: InputRequest): InputOutcome {
        requests.add(request)
        return InputOutcome(intValue = request.defInt, strValue = request.defStr)
    }
}

class E2ETimerSink : TimerSink {
    var totalDelayMs = 0L
        private set

    override fun delay(millis: Long) { totalDelayMs += maxOf(millis, 0L) } // 不真睡
}

class E2ESoundSink : SoundSink {
    val events = ArrayList<String>()
    override fun playSound(name: String, count: Int) { events.add("sound:$name x$count") }
    override fun stopSound() { events.add("sound-stop") }
    override fun playBgm(name: String) { events.add("bgm:$name") }
    override fun stopBgm() { events.add("bgm-stop") }
    override fun setSoundVolume(volume: Int) { events.add("vol:$volume") }
    override fun setBgmVolume(volume: Int) { events.add("bgmvol:$volume") }
}

class E2EGraphicsSink : GraphicsSink {
    val events = ArrayList<String>()
    override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {
        events.add("img:$name")
    }

    override fun printRect(params: List<Pair<Long, Boolean>>) { events.add("rect") }
    override fun printSpace(params: List<Pair<Long, Boolean>>) { events.add("space") }
    override fun setBgImage(name: String, depth: Long?, opacity: Long?) { events.add("bg:$name") }
    override fun removeBgImage(name: String) { events.add("bg-:$name") }
    override fun clearBgImages() { events.add("bg-clear") }
}

/** 存档/系统语句的假宿主：SAVEDATA 走记忆体，SAVEGLOBAL/LOADGLOBAL 真落盘（临时目录，不污染仓库）。 */
class E2ESystemOps(
    private val store: com.eratw.port.core.vars.VariableStore,
    private val ioRoot: java.nio.file.Path,
) : SystemOps {
    val calls = ArrayList<String>()

    /**
     * 存档槽 → 描述文本。SAVEDATA 记入，[checkSaveData] 读回（CHKDATA）。
     * 键 = 槽号，值与 sav/save{no}.sav 头里的描述文本等价。
     */
    val saveSlots = HashMap<Int, String>()
    override fun saveData(no: Int, text: String): Boolean {
        calls.add("saveData:$no"); saveSlots[no] = text; return true
    }
    override fun delData(no: Int) { calls.add("delData:$no"); saveSlots.remove(no) }
    override fun loadData(no: Int) { calls.add("loadData:$no") }
    override fun saveGame() { throw CodeEE("E2E 装置不进入存/读档 UI 流程（runbook §6 排除项）") }
    override fun loadGame() { throw CodeEE("E2E 装置不进入存/读档 UI 流程（runbook §6 排除项）") }

    /** global.sav 路径 = SavDir + "global.sav"。spec: VariableEvaluator.cs:2018-2021 getSaveDataPathG */
    private val globalFile: java.nio.file.Path get() = ioRoot.resolve("sav").resolve("global.sav")

    /**
     * SAVEGLOBAL：GLOBAL/GLOBALS 非零/非空项稀疏快照写盘。spec: VariableEvaluator.cs:2474-2511
     * （SaveGlobal → Config.SavDir+"global.sav"；SavDir 缺省 = 游戏目录\sav\，与
     * EXISTFILE("sav/global.sav") 以 CWD=游戏目录的相对解析同指一文件）。快照还原语义 =
     * LoadGlobalFromStream 整帧覆写，故 LOADGLOBAL 侧先清后写。文本行格式：
     * 头行 `ERATW-E2E-GLOBAL/1`、`GLOBAL <n>` + 每项 `<idx> <v>`、`GLOBALS <n>` +
     * 每项 `<idx> <s>`（s 经反斜杠转义 \ 与换行）。
     */
    override fun saveGlobal() {
        calls.add("saveGlobal")
        java.nio.file.Files.createDirectories(globalFile.parent)
        val ints = ArrayList<Pair<Int, Long>>()
        val strs = ArrayList<Pair<Int, String>>()
        for (i in 0 until store.getLength(GLOBAL)) {
            val v = store.readInt(GLOBAL, longArrayOf(i.toLong()))
            if (v != 0L) ints.add(i to v)
        }
        for (i in 0 until store.getLength(GLOBALS)) {
            val s = store.readStr(GLOBALS, longArrayOf(i.toLong()))
            if (s.isNotEmpty()) strs.add(i to s)
        }
        val sb = StringBuilder("ERATW-E2E-GLOBAL/1\n")
        sb.append("GLOBAL ").append(ints.size).append('\n')
        for ((i, v) in ints) sb.append(i).append(' ').append(v).append('\n')
        sb.append("GLOBALS ").append(strs.size).append('\n')
        for ((i, s) in strs) sb.append(i).append(' ').append(escape(s)).append('\n')
        java.nio.file.Files.write(globalFile, sb.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * LOADGLOBAL：文件缺失 → false（spec: VariableEvaluator.cs:2516-2519 File.Exists 早退；
     * 解析失败同样 false，:2570-2573 catch-all）；成功 → 清空后整帧还原，返回 true。
     */
    override fun loadGlobal(): Boolean {
        calls.add("loadGlobal")
        if (!java.nio.file.Files.exists(globalFile)) return false
        val parsed = runCatching { parseGlobal(java.nio.file.Files.readAllLines(globalFile, Charsets.UTF_8)) }
            .getOrNull() ?: return false
        store.setAll(GLOBAL, 0L)
        store.setAllStr(GLOBALS, "")
        val intLen = store.getLength(GLOBAL)
        for ((i, v) in parsed.first) if (i < intLen) store.writeInt(GLOBAL, longArrayOf(i.toLong()), v)
        val strLen = store.getLength(GLOBALS)
        for ((i, s) in parsed.second) if (i < strLen) store.writeStr(GLOBALS, longArrayOf(i.toLong()), s)
        return true
    }

    private fun parseGlobal(lines: List<String>): Pair<List<Pair<Int, Long>>, List<Pair<Int, String>>> {
        if (lines.isEmpty() || lines[0] != "ERATW-E2E-GLOBAL/1") error("global.sav 头不符")
        var p = 1
        fun section(expected: String): Int {
            val head = lines[p].split(' ')
            require(head[0] == expected && head.size == 2) { "global.sav 段名不符: ${lines[p]}" }
            p++
            return head[1].toInt()
        }
        val nInt = section("GLOBAL")
        val ints = ArrayList<Pair<Int, Long>>(nInt)
        repeat(nInt) {
            val parts = lines[p].split(' ', limit = 2); p++
            ints.add(parts[0].toInt() to parts[1].toLong())
        }
        val nStr = section("GLOBALS")
        val strs = ArrayList<Pair<Int, String>>(nStr)
        repeat(nStr) {
            val parts = lines[p].split(' ', limit = 2); p++
            strs.add(parts[0].toInt() to unescape(parts[1]))
        }
        return ints to strs
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && (s[i + 1] == 'n' || s[i + 1] == '\\')) {
                sb.append(if (s[i + 1] == 'n') '\n' else '\\')
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    override fun saveChara(nos: List<Long>, filename: String, text: String) { calls.add("saveChara:$filename") }
    override fun loadChara(filename: String) { calls.add("loadChara:$filename") }
    override fun updateCheck(): Long { calls.add("updateCheck"); return 0L }
    override fun callTrain(no: Long) { throw CodeEE("E2E 装置不进入训练状态机（runbook §6 排除项）") }
    override fun stopCallTrain() { throw CodeEE("E2E 装置不进入训练状态机（runbook §6 排除项）") }
    override fun doTrain(no: Long) { throw CodeEE("E2E 装置不进入训练状态机（runbook §6 排除项）") }
    override fun upcheck() { calls.add("upcheck") }
    override fun cupcheck(target: Long) { calls.add("cupcheck:$target") }

    private companion object {
        const val GLOBAL = "GLOBAL"
        const val GLOBALS = "GLOBALS"
    }
}
