package com.eratw.port.core.host

import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.FakeInputSink
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTML_PRINTC 逐项制表在折行侧必须成**定宽列网格**（2026-11-04 玩家反馈的回归钉子）。
 *
 * ## 缺陷与判据
 *
 * 玩家屏上「选项左右左右斜斜的 + 选项中途换行」：4.981 起语料把指令网格从脚本侧
 * `HTML_PRINTC_MANAGER` 自排（整行一次 HTML_PRINT）改成引擎语句 `HTML_PRINTC` 逐项制表
 * （`ERB/魔改内容/qol/QOL_USERCOM.ERB:108`、`ERB/コマンド関連/USERCOM_コマンド表示処理.ERB`
 * 的 Ex_COM 段同族），排版权威落到本仓引擎。旧实现的补齐段写的是
 * `<shape type='space' param='Npx'>`，而折行的可见宽把标签剥掉 ⇒ 补齐段计 **0 格**，
 * 一列在折行眼里只剩文本那几格，行攒到两三倍密度才折 —— 桌面探针实测 16 颗按钮挤进
 * **1 条**显示行（199 列档），fork 语义应为每行 7 项（199 列 ÷ PRINTCLENGTH 25）。
 *
 * 判据 = fork 语义（`specs/isa.yaml:2444`：像素定宽制表、右对齐前补空格/左对齐后补空格；
 * fork-src `EmueraConsole.Print.cs:521-574`）在格数模型下的等价形：
 * - 每项补齐到**恰好 `PRINTCLENGTH`（25）个半角格**，与文本长短无关；
 * - 行折在列边界上：`clientWidth=199`（PC 基准档）⇒ 每行 7 项；
 *   `clientWidth=137`（真机列数守护档，`render/view/ScreenLayout.DEFAULT_COLUMNS`）⇒ 每行 5 项；
 * - 折出来的行永不超宽（渲染右沿不裁内容），列位逐项对齐（斜斜的根除判据）。
 *
 * 语料形态夹具 = `BUILD_COM_BUTTON_HTML`（`HTML_PRINT_Components.ERB:136`）的
 * `<font color='#64fa8c'><button value='N'>文本[N]</button></font>` +
 * `QOL_USERCOM.ERB:108` 的逐项 `HTML_PRINTC`（右对齐）。
 */
class HtmlPrintcColumnFoldTest {

    /** 记录 TEXT_BGC 转发的假图形宿主（其余图形入口空转）。 */
    private class NoGraphics : GraphicsSink {
        override fun printImage(name: String, nameb: String?, namem: String?, params: List<Pair<Long, Boolean>>) {}
        override fun printRect(params: List<Pair<Long, Boolean>>) {}
        override fun printSpace(params: List<Pair<Long, Boolean>>) {}
        override fun setBgImage(name: String, depth: Long?, opacity: Long?) {}
        override fun removeBgImage(name: String) {}
        override fun clearBgImages() {}
        override fun setTextBgColor(rgb: Long, opacity: Long) {}
    }

    /** 语料指令网格的一个抽样（Act_COM 段的真实命令号与名称，4.981 `QOL_USERCOM.ERB`）。 */
    private val items = listOf(
        403L to "休憩", 406L to "日记本", 410L to "扫除", 412L to "学习", 413L to "制作料理",
        416L to "演奏（子选单）", 425L to "下药", 446L to "进行调合", 450L to "查看酒虫的情况", 451L to "自慰",
        482L to "追踪雏", 490L to "道具", 495L to "眺望天空", 496L to "使用收音机", 498L to "料理之铁人",
        641L to "能力卡牌",
    )

    /** 逐项 `HTML_PRINTC`（右对齐，语料 :108 同形）后 `PRINTL` 收尾，返回折行结果。 */
    private fun foldGrid(clientWidth: Int): List<LineBufferConsoleSink.DisplayLine> {
        val sink = LineBufferConsoleSink(clientWidth = clientWidth, maxLines = 200)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        for ((value, name) in items) {
            executor.execute(
                instr(
                    FunctionCode.HTML_PRINTC,
                    SpHtmlPrint(
                        StrLiteral("<font color='#64fa8c'><button value='$value'>$name[$value]</button></font>"),
                        null,
                    ),
                ),
                ctx,
            )
        }
        sink.newLine()
        return sink.lines()
    }

    @Test
    fun `PC 基准档 199 列 每行折 7 项且每项恰占 25 格`() {
        val rows = foldGrid(199)
        // 16 项 ÷ 每行 7 = 7 + 7 + 2
        assertEquals(listOf(7, 7, 2), rows.map { it.segments.size }, "行分布必须折在列边界上；实际=${rows.map { it.segments.size }}")
        for (row in rows) {
            for (seg in row.segments) {
                assertEquals(
                    25,
                    CellWidth.visibleHtmlCells(seg.text),
                    "每项必须补齐到 PRINTCLENGTH=25 格（与文本长短无关）；实际=«${seg.text.take(60)}»",
                )
            }
        }
        assertEquals(
            listOf(true, true, true),
            rows.map { it.isLineEnd },
            "折行续排每排都带行尾标志（RowBuilder 构造缺省，ConsoleDisplayLine.cs:16/:32；" +
                "逻辑行计数只认 isLogicalLine，LINECOUNT 读数不受影响）",
        )
    }

    @Test
    fun `真机档 137 列 每行折 5 项`() {
        val rows = foldGrid(137)
        // 5 × 25 = 125 ≤ 137 < 150 ⇒ 每行 5 项；16 项 = 5 + 5 + 5 + 1
        assertEquals(listOf(5, 5, 5, 1), rows.map { it.segments.size }, "实际=${rows.map { it.segments.size }}")
        assertTrue(rows.all { row -> row.segments.sumOf { CellWidth.visibleHtmlCells(it.text) } <= 137 },
            "折出的行永不超宽（渲染右沿裁切=漏指令的根）")
    }

    @Test
    fun `右对齐补齐落在文本之前且全部进选项快照`() {
        val rows = foldGrid(199)
        val first = rows[0].segments.single { "[403]" in it.text }
        assertTrue(first.text.startsWith(" ".repeat(16)), "右对齐 = 前补空格（「あ」=2 格档的 23 格同源，9 格文本补 16）；实际=«${first.text.take(24)}»")

        val sink = LineBufferConsoleSink(clientWidth = 199, maxLines = 200)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        for ((value, name) in items) {
            executor.execute(
                instr(
                    FunctionCode.HTML_PRINTC,
                    SpHtmlPrint(
                        StrLiteral("<font color='#64fa8c'><button value='$value'>$name[$value]</button></font>"),
                        null,
                    ),
                ),
                ctx,
            )
        }
        sink.newLine()
        sink.noteInputWait(InputRequest(type = InputRequest.InputType.INT))
        assertEquals(
            items.map { it.first },
            sink.currentOptions().map { it.value },
            "16 颗按钮全部进取值候选且按屏上行序（折行不吞项）",
        )
    }

    /**
     * 图形段（`<img>` / `<shape>`）必须计入折行宽（2026-11-05 遗留①收口钉）。
     *
     * 语料形态 = `CHARALIST_DT` 的立绘行（img 显式宽 + 按钮文本，`HTML_PRINT` option=1
     * 进折行缓冲）。旧实现折行把图形计 0 格，img 行攒到超宽才折、渲染右沿裁切。
     * `scriptFontSize = 18` 时 img `216px` = 24 格（`ceil(216/9.36)`），折行据此在
     * 图形真实占位处断行。
     */
    @Test
    fun `图形段计入折行宽_img行不再攒到超宽`() {
        val sink = LineBufferConsoleSink(clientWidth = 40, maxLines = 200, scriptFontSize = 18)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        // 每段：img 24 格 + 文本 8 格 = 32 格；40 列宽下第二段（32+32=64 > 40）必须折行
        repeat(2) {
            executor.execute(
                instr(
                    FunctionCode.HTML_PRINTLC,
                    SpHtmlPrint(StrLiteral("<img src='a' width='216px' height='144px'><button value='1'>设置角色</button>"), null),
                ),
                ctx,
            )
        }
        sink.newLine()
        val rows = sink.lines()
        assertEquals(2, rows.size, "img 占 24 格必须被折行看见：两段各 32 格、40 列宽下逐段成行；实际=${rows.size} 行")
        for (row in rows) {
            assertEquals(
                32,
                CellWidth.visibleHtmlCells(row.segments[0].text, 18L),
                "行宽 = img 24 格 + 文本 8 格（图形计入后与渲染实占一致）",
            )
        }
    }

    /** 图形段在折行宽度里「差一格就满」时必须把整段移行，而不是让渲染右沿裁切。 */
    @Test
    fun `图形段接近满行时整段移行`() {
        // 39 列宽：img 24 + 文本 8 = 32 ≤ 39 放一行；再来一段（32+32=64 > 39）折第二行
        val sink = LineBufferConsoleSink(clientWidth = 39, maxLines = 200, scriptFontSize = 18)
        val store = VariableStore()
        val ctx = ExecContext(
            variables = store,
            eval = BuiltinEvalContext(TestEvalContext(store), store, BuiltinHost.DEFAULT),
            console = sink,
            input = FakeInputSink(),
            graphics = NoGraphics(),
        )
        val executor = StatementExecutor()
        repeat(2) {
            executor.execute(
                instr(
                    FunctionCode.HTML_PRINTLC,
                    SpHtmlPrint(StrLiteral("<img src='a' width='216px' height='144px'><button value='1'>设置角色</button>"), null),
                ),
                ctx,
            )
        }
        sink.newLine()
        assertEquals(
            listOf(1, 1),
            sink.lines().map { it.segments.size },
            "每行一段（图形计入后 32 > 39−32，第二段移行）",
        )
    }
}
