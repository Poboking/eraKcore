package com.eratw.port.render.input

import com.eratw.port.render.Grid

/**
 * 输入框的**内容与选区（光标）**模型 —— 原版那块 `RichTextBox richTextBox1`
 * （`MinorShift.Emuera.Forms/MainWindow.cs:112` 声明，`MainWindow.cs:210` 以 `TextBox` 对外暴露）
 * 在纯 JVM 里的等价状态。本波不做控件、不做输入法/软键盘（那属真机绑定），
 * 只把「文本框里现在是什么、光标在第几格」这两件事固化成可单测的状态。
 *
 * ## 为什么能只看 MainWindow 的用法来定 API
 * 对这个框的写操作几乎都集中在 `MainWindow.cs` 里，且形态非常有限 ——
 * 赋值 `Text`、`AppendText`、`Clear`、`SelectAll`、`SelectionStart`、`SelectionLength`。
 * 本类的每个方法都对应其中之一，逐条行号见各方法的 KDoc。
 * 唯一的例外在 `EmueraConsole.cs:1722-1725`：`SetEmueraVersionInfo(str)` 会把版本串
 * 直接写进框里（`window.TextBox.Text = str`）。但反编译里**查不到它的调用者**
 * （全库 grep `SetEmueraVersionInfo` 只有声明那一处），故本层不为它单开入口。
 * TODO(verify)：它到底是死代码还是由 `Program.cs`/加载流程间接触发，属宿主启动序；
 * 卡点＝本波不碰 `core-host` 包，且反编译没有调用边。
 * 各方法对应的原版块行：
 * - 赋值文本：`ChangeTextBox` 只写 `richTextBox1.Text = str`（`MainWindow.cs:378-381`，
 *   即 `SETTEXTBOX` 的实现体，`FunctionMethodCreator.cs:8357-8370`）；
 * - 追加文本：`AppendText(num.ToString())`（`MainWindow.cs:1365-1366`，HOTKEY.ERB 命中后）；
 * - 清空：`Clear()`（`clear_richText` `MainWindow.cs:1308-1311`、`richTextBox1.Clear()` :1327）；
 * - 全选：`SelectAll()`（`MainWindow.cs:1372`）；
 * - 光标到末尾：`SelectionStart = richTextBox1.Text.Length`（`MainWindow.cs:509-510`，
 *   F1-F12 预填之后**只有这一行**动了光标）；
 * - 整行选中：`SelectionStart = 0` + `SelectionLength = Text.Length`
 *   （`MainWindow.cs:999-1001`，↑↓ 召回历史之后）。
 *
 * ## 光标行列的口径
 * [caretCellColumn] 用 render 层唯一的宽度模型 [Grid.cells] 数「半角格」，
 * 与折行/按钮命中同一把尺子（契约 §0.5 的两条口径里，等宽网格这条归 render 层；
 * 宽度表本身在 d-width 之后只剩一份，见 `Kernel.Grid` 的委托）。
 * 行分隔只按**真实换行**（LF/CR/CRLF）切；提交文本那一套四元素切分符
 * （`EmueraConsole.cs:146` 的 `spliter`，含字面量反斜杠加 n）属 [InputSession.splitPieces]，
 * 两者不是一回事，理由见 [lines] 的 KDoc。
 * 富文本框自己的软换行（按控件宽度折行）是像素域的，本层不模拟。
 * TODO(verify)：原版 `RichTextBox` 是像素域多行控件（默认 `Multiline=true`），
 * 而窗口初始时它只有一行高（`MainWindow.cs:727-728`：`Location=(0, ClientHeight-LineHeight)`、
 * `Size=(Config.WindowX, LineHeight)`）。硬换行之外的**软折行行数**没有 PC 可比读数，
 * 卡点：本机既无 WinForms 也无 Android SDK，量不到「滚动后第几行」。
 */
class EmueraTextBox {

    /** 框内文本；对应 `richTextBox1.Text` 的取值侧（`MainWindow.cs:926`、`FunctionMethodCreator.cs:8353`）。 */
    var text: String = ""
        private set

    /** 选区起点（UTF-16 下标）；对应 `richTextBox1.SelectionStart`（`MainWindow.cs:510`、`:1000`、`:1424`）。 */
    var selectionStart: Int = 0
        private set

    /** 选区长度；对应 `richTextBox1.SelectionLength`（`MainWindow.cs:1001`）。 */
    var selectionLength: Int = 0
        private set

    /**
     * 整串赋值（`[assign]` = 原版的 `richTextBox1.Text = str`）。
     *
     * 选区清成「起点 0、长度 0」是**推断**而非实测：原版在赋值后需要光标落在别处的地方
     * 都显式补了一句（F1-F12 预填后 `SelectionStart = Text.Length` `MainWindow.cs:509-510`，
     * 历史召回后 `SelectionStart = 0` `:1000`），说明赋值本身**不能**指望它把光标放到末尾。
     * TODO(verify)：WinForms `RichTextBox.Text` setter 之后 `SelectionStart` 的确切值
     * （0 还是「夹到新长度」）没有 PC 侧可比读数；卡点＝本机无 WinForms。
     * 真机接上软键盘后这一条要用实机复核，复核前本层按 0 走。
     */
    fun assign(value: String) {
        text = value
        selectionStart = 0
        selectionLength = 0
    }

    /**
     * 末尾追加（`MainWindow.cs:1366` 的 `AppendText`）。
     * WinForms `AppendText` 把插入点留在新文本末尾，本层照抄（选区长度归 0）。
     */
    fun appendText(value: String) {
        text += value
        selectionStart = text.length
        selectionLength = 0
    }

    /** 清空（`clear_richText` `MainWindow.cs:1308-1311`；`richTextBox1.Clear()` 同义）。 */
    fun clear() {
        text = ""
        selectionStart = 0
        selectionLength = 0
    }

    /** 全选（`richTextBox1.SelectAll()` `MainWindow.cs:1372`）。 */
    fun selectAll() {
        selectionStart = 0
        selectionLength = text.length
    }

    /**
     * 整行选中（`MainWindow.cs:1000-1001` 那两句的合写：起点 0、长度 = 文本长度）。
     * 与 [selectAll] 在纯文本下等价，分开是因为原版两处用的不是同一个 API 形态。
     */
    fun selectWholeLine() {
        selectionStart = 0
        selectionLength = text.length
    }

    /**
     * 把插入点放到末尾（`MainWindow.cs:510` 只赋 `SelectionStart` 那一句）。
     * 本层严格照那句的形状：**只改起点、不碰长度**，越界不夹（起点由其它写入口保证合法）。
     * TODO(verify)：WinForms 赋 `SelectionStart` 时 `SelectionLength` 是否被顺带清零，
     * 没有 PC 可比读数；卡点＝本机无 WinForms。影响面很小 —— 唯一的调用点（F1-F12 预填）
     * 在 :509 刚赋过 `Text`，此刻 `SelectionLength` 本来就是零。
     */
    fun moveCaretToEnd() {
        selectionStart = text.length
    }

    /**
     * 用户改了一次文本（打字/粘贴/软键盘上屏）。
     *
     * 这是本层**新造**的最小输入原语：原版打字由 `RichTextBox` 内部完成，没有 C# 行号可引，
     * 所以只在「替换当前选区 + 插入」这一条最朴素的语义上落地，其余一律不猜。
     * 真机绑定时由输入法回调给最终文本，再走 [assign] 或本方法。
     * TODO(verify)：IMF 组合区（未上屏字符串）与 `SelectionStart` 的关系、
     * 以及 `LanguageOption = UIFonts`（`MainWindow.cs:240`）下的输入法行为，属真机项。
     */
    fun replaceSelectionWith(insertion: String) {
        val start = selectionStart.coerceIn(0, text.length)
        val end = (start + selectionLength).coerceIn(start, text.length)
        text = text.substring(0, start) + insertion + text.substring(end)
        selectionStart = start + insertion.length
        selectionLength = 0
    }

    /** 删除当前选区（右键菜单「削除」那一路的等价操作，`MainWindow.cs:1503-1514` 只开关菜单项，不在此模拟）。 */
    fun deleteSelection() {
        if (selectionLength > 0) {
            replaceSelectionWith("")
        }
    }

    /** 选区文本；对应 `richTextBox1.SelectedText`（`MainWindow.cs:1424`、`:1503` 的读侧）。 */
    fun selectedText(): String {
        val start = selectionStart.coerceIn(0, text.length)
        val end = (start + selectionLength).coerceIn(start, text.length)
        return text.substring(start, end)
    }

    /** 没有选区且插入点在整个文本最左（`MainWindow.cs:1424` 的 `SelectionStart == 0 && SelectedText.Length == 0`）。 */
    fun atStartWithoutSelection(): Boolean = selectionStart == 0 && selectionLength == 0

    /** 插入点在整个文本最右，或框内本来就空（`MainWindow.cs:1424`、`:1428` 的 `Text.Length == 0` 分支）。 */
    fun atEnd(): Boolean = text.isEmpty() || selectionStart == text.length

    /**
     * 该按下的键是否被「光标已到边」吞掉。
     * 原版 Left/Home/Back 在最左、Right/End 在最右时 `e.SuppressKeyPress = true`
     * （`MainWindow.cs:1424-1431`），目的是让边界键不去碰剪贴板/不产生字符。
     */
    fun suppressesEdgeKey(key: EdgeKey): Boolean = when (key) {
        EdgeKey.LEFT, EdgeKey.HOME, EdgeKey.BACK -> atStartWithoutSelection() || text.isEmpty()
        EdgeKey.RIGHT, EdgeKey.END -> atEnd()
    }

    /** 本层认识的「边界键」（`MainWindow.cs:1424`、`:1428` 里出现的那几个）。 */
    enum class EdgeKey { LEFT, HOME, BACK, RIGHT, END }

    /**
     * 文本按硬换行切出来的行（LF/CR/CRLF 各算一次断行）。
     *
     * 注意出处边界：`EmueraConsole.cs:146` 的 `spliter`（字面量反斜杠加 n、CRLF、LF、CR 四项）
     * 是**提交文本**的切分口径（`EmueraConsole.cs:1429` 的 `input.Split(spliter, None)`），
     * 见本包 `InputSession.splitPieces`；它**不能**证明 `RichTextBox` 自己怎么分行。
     * TODO(verify)：WinForms `TextBoxBase.Lines` 的确切切分规则（CR、LF、CRLF 是否都算一次，
     * 末尾空行是否保留）没有 PC 可比读数；卡点＝本机无 WinForms。本层按「CR/LF/CRLF 各一次、
     * 末尾保留空行」这条确定性口径固化，真机接入后以实机复核。
     */
    fun lines(): List<String> {
        if (text.isEmpty()) {
            return listOf("")
        }
        val out = ArrayList<String>(2)
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                out.add(current.toString())
                current.setLength(0)
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                    i++
                }
            } else {
                current.append(c)
            }
            i++
        }
        out.add(current.toString())
        return out
    }

    /** 光标所在行（0 基）在硬换行口径下的行号。 */
    fun caretLine(): Int {
        val clamped = selectionStart.coerceIn(0, text.length)
        var line = 0
        var i = 0
        while (i < clamped) {
            val c = text[i]
            if (c == '\r' || c == '\n') {
                line++
                if (c == '\r' && i + 1 < clamped && text[i + 1] == '\n') {
                    i++
                }
            }
            i++
        }
        return line
    }

    /** 光标所在行的文本（[caretLine] 对应的那一行）。 */
    fun caretLineText(): String = lines()[caretLine()]

    /**
     * 光标在本行内的**半角格**列号（render 层唯一宽度口径 [Grid.cells]，
     * 先按 [Grid.expandTabs] 把 TAB 摊开）。
     * 「度量前先把 TAB 换成 8 个空格」这一条有原版依据：`StringMeasure.cs:59-62`
     * 的 `s.Replace("\t", "        ")`；但 `StringMeasure` 本身返回的是**像素**宽，
     * 所以「格」这一维仍取本层 `Grid` 的等宽网格口径，不是从它抄来的。
     * TODO(verify)：PC 侧插入点位置由 WinForms 在像素域自己算（`SelectionStart` 只是字符下标），
     * 「第几格」是本层为安卓画插入点新增的读数，官方无对应可比数据；卡点＝无 PC 截尺工具。
     */
    fun caretCellColumn(): Int = Grid.cells(caretLineText().substring(0, caretColumnChars()))

    /** 光标在本行内的字符数下标（不是格数；[caretCellColumn] 的换算前一步）。 */
    private fun caretColumnChars(): Int {
        val clamped = selectionStart.coerceIn(0, text.length)
        var i = clamped
        while (i > 0 && text[i - 1] != '\r' && text[i - 1] != '\n') {
            i--
        }
        return clamped - i
    }

    /** 整框的呈现读数（单测与真机绑定层都读它，避免各自回读内部字段）。 */
    fun presentation(): TextBoxPresentation = TextBoxPresentation(
        text = text,
        selectionStart = selectionStart,
        selectionLength = selectionLength,
        caretLine = caretLine(),
        caretCellColumn = caretCellColumn(),
    )
}

/**
 * 文本框呈现快照：光标行 + 该行的格列 + 选区。
 * `data class` 保证可等值断言；字段顺序固定，无 Map 迭代序参与（确定性要求）。
 */
data class TextBoxPresentation(
    val text: String,
    val selectionStart: Int,
    val selectionLength: Int,
    val caretLine: Int,
    val caretCellColumn: Int,
)
