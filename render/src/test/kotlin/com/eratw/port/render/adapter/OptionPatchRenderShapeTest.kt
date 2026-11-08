package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.grid.LineFolder
import com.eratw.port.render.glyph.CellMetrics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * OPTION 补丁页面渲染层形状测试（读 [OptionPatchPageProbeTest] 导出的快照 TSV）。
 *
 * 语料依赖用例：`core-vm/build/option-patch-snapshot.tsv` 存在才生效，缺失即静默跳过。
 * 快照由 `:core-vm OptionPatchPageProbeTest`（真语料驱动 `@OPTION_DLC`）写出，
 * 本测试只负责重建 RenderLine → HtmlRunFlatten 展平 → LineFolder 折行（两档真机量级列宽），
 * 把每条显示行的段文本 / kind / 按钮值 / 列区间写进 `build/option-patch-render.md`，
 * 供比对「折行后按钮列是否错位、HTML 翻页按钮是否落成可点段」。
 */
class OptionPatchRenderShapeTest {

    @Test
    fun foldPatchPageSnapshotAtDeviceWidths() {
        val tsv = sequenceOf(Path.of("../core-vm/build/option-patch-snapshot.tsv"), Path.of("build/option-patch-snapshot.tsv"))
            .firstOrNull { Files.exists(it) } ?: return
        val lines = parseTsv(tsv)
        val flatten = HtmlRunFlatten(CellMetrics.DEFAULT)
        val report = buildString {
            appendLine("# OPTION 补丁页面渲染层形状")
            appendLine()
            appendLine("- 快照: ${tsv.toAbsolutePath()}（${lines.size} 逻辑行）")
            for (width in intArrayOf(136, 66)) {
                appendLine()
                appendLine("## clientWidth=$width")
                appendLine()
                appendLine("```text")
                val folder = LineFolder(width)
                val renderLines = lines.map { flatten.flattenLine(it) }
                val rows: List<DisplayRow> = renderLines.flatMap { line ->
                    folder.fold(line, noBr = line.noBreak)
                }
                rows.forEachIndexed { i, row ->
                    var col = 0
                    val parts = row.runs.map { r ->
                        val w = Grid.cells(r.text)
                        val seg = "[$col..${col + w})${r.kind}" +
                            (if (r.buttonValue != null) "(值=${r.buttonValue})" else "") +
                            ":'${r.text.take(20)}'"
                        col += w
                        seg
                    }
                    appendLine("%4d cells=%-4d | %s".format(i, row.cells, parts.joinToString(" ")))
                }
                appendLine("```")
            }
        }
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/option-patch-render.md"), report)
    }

    /** 快照 TSV → RenderLine 列（段字段按 ConsoleAdapter.runsOf 同口径重建，按钮按列区间切段）。 */
    private fun parseTsv(tsv: Path): List<RenderLine> {
        val lines = ArrayList<RenderLine>()
        var lineEnd = false
        var logical = false
        var align = Alignment.LEFT
        var noBreak = false
        var segs: List<Seg>? = null
        var btns: List<Btn> = emptyList()
        fun flush() {
            val s = segs ?: return
            lines.add(RenderLine(runs = buildRuns(s, btns), isLineEnd = lineEnd, isLogicalLine = logical, align = align, noBreak = noBreak))
            segs = null
            btns = emptyList()
        }
        for (raw in Files.readAllLines(tsv)) {
            when {
                raw.startsWith("DL ") -> {
                    flush()
                    val p = raw.split(' ')
                    lineEnd = p[1] == "true"
                    logical = p[2] == "true"
                    align = Alignment.valueOf(p[3])
                    noBreak = p[4] == "true"
                    segs = ArrayList()
                    btns = ArrayList()
                }
                raw.startsWith("SEG ") -> {
                    val rest = raw.removePrefix("SEG ")
                    val kind = rest.substringBefore(' ')
                    val tail = rest.substringAfter(' ')
                    val htmlOption = tail.startsWith("true ")
                    val text = unesc(tail.removePrefix(if (htmlOption) "true " else "false "))
                    (segs as ArrayList<Seg>).add(Seg(kind, htmlOption, text))
                }
                raw.startsWith("BTN ") -> {
                    val p = raw.split(' ', limit = 4)
                    (btns as ArrayList<Btn>).add(Btn(p[1].toInt(), p[2].toInt(), unesc(p[3])))
                }
                raw == "END" -> flush()
            }
        }
        flush()
        return lines
    }

    /** 按钮切段（ConsoleAdapter.runsOf 的最小复刻：按列区间把 BUTTON 段切回一按钮一 run）。 */
    private fun buildRuns(segs: List<Seg>, btns: List<Btn>): List<TextRun> {
        val out = ArrayList<TextRun>(segs.size)
        var next = 0
        var remaining = if (btns.isEmpty()) 0 else btns[0].endCol - btns[0].startCol
        for (seg in segs) {
            val kind = runKind(seg.kind)
            if (kind != RunKind.BUTTON) {
                out.add(
                    TextRun(
                        text = seg.text, fore = 0xC0C0C0, back = 0, styleMask = 0,
                        fontName = "黑体", kind = kind, htmlOption = seg.htmlOption,
                    ),
                )
                continue
            }
            val text = seg.text
            var p = 0
            while (p < text.length && next < btns.size && remaining > 0) {
                var cut = p
                var acc = 0
                while (cut < text.length && acc < remaining) {
                    acc += Grid.cells(text[cut].toString())
                    cut++
                }
                remaining -= acc
                out.add(
                    TextRun(
                        text = text.substring(p, cut), fore = 0xC0C0C0, back = 0xC8C8FF,
                        styleMask = 0, fontName = "黑体", kind = RunKind.BUTTON,
                        buttonValue = btns[next].value.toLongOrNull() ?: btns[next].value,
                        buttonId = next,
                    ),
                )
                p = cut
                if (remaining <= 0) {
                    next++
                    remaining = if (next < btns.size) btns[next].endCol - btns[next].startCol else 0
                }
            }
            if (p < text.length) {
                out.add(
                    TextRun(
                        text = text.substring(p), fore = 0xC0C0C0, back = 0, styleMask = 0,
                        fontName = "黑体", kind = RunKind.BUTTON,
                    ),
                )
            }
        }
        return out
    }

    /** core Kind → RunKind 五项同名映射（ConsoleAdapter.runKind 同口径）。 */
    private fun runKind(kind: String): RunKind = when (kind) {
        "BUTTON" -> RunKind.BUTTON
        "PLAIN" -> RunKind.PLAIN
        "HTML" -> RunKind.HTML
        "ERROR" -> RunKind.ERROR
        else -> RunKind.TEXT
    }

    private fun unesc(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\r", "\r")
        .replace("\\\\", "\\")

    private class Seg(val kind: String, val htmlOption: Boolean, val text: String)

    private class Btn(val startCol: Int, val endCol: Int, val value: String)
}