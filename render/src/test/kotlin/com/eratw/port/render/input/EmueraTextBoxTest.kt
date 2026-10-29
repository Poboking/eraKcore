package com.eratw.port.render.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EmueraTextBox] 的内容、选区与光标读数。
 *
 * ## 期望值出处（`MinorShift.Emuera.Forms/MainWindow.cs`，控件声明 :112，对外经 :210）
 * - 六个写入口逐条对应原版：`Text =`（:380 `ChangeTextBox`，即 `SETTEXTBOX`，
 *   函数壳 `FunctionMethodCreator.cs:8357-8370`、注册 :9293）、`AppendText`（:1366）、
 *   `Clear`（:1310 的 `clear_richText` 与 :1327）、`SelectAll`（:1372）、
 *   `SelectionStart =`（:510、:1000、:1573）、`SelectionLength =`（:1001）。
 * - 读侧：`Text`（:926 与 `FunctionMethodCreator.cs:8353` 的 `GETTEXTBOX`，注册 :9292）、
 *   `SelectedText`（:1424、:1503）。
 * - 边界键吞键条件：最左一组 Left/Home/Back（:1424）、最右一组 Right/End（:1428）。
 * - 光标列的格宽口径取 render 层唯一的 `Grid.cells`；TAB 先摊成 8 空格的原版依据是
 *   `MinorShift.Emuera.UI.Game/StringMeasure.cs:59-62` 的 `s.Replace("\t", "        ")`。
 *
 * 硬换行的切分与「光标第几格」都是本层为安卓新增的确定性口径（原版 `RichTextBox` 的行与插入点
 * 在像素域，PC 无对应读数），类文档已逐条挂 `TODO(verify)`；这里断言的是**本层固化的口径本身**，
 * 目的是让它一旦改动就会被测到。
 */
class EmueraTextBoxTest {

    @Test
    fun `初值是空框`() {
        val box = EmueraTextBox()
        assertEquals("", box.text, "WinForms 初始 Text 为空串")
        assertEquals(0, box.selectionStart)
        assertEquals(0, box.selectionLength)
        assertTrue(box.atStartWithoutSelection())
        assertTrue(box.atEnd(), "空框同时算最左与最右（:1424、:1428 都带 Text.Length 为零的或件）")
    }

    @Test
    fun `整串赋值把选区清成零`() {
        val box = EmueraTextBox()
        box.assign("第一版")
        box.selectWholeLine()
        box.assign("第二版")
        assertEquals("第二版", box.text, ":380 的 richTextBox1.Text = str 是覆盖而非追加")
        assertEquals(0, box.selectionStart)
        assertEquals(0, box.selectionLength, "本层固化值；原版赋值后的 SelectionStart 待实机复核（assign 的 TODO(verify)）")
    }

    @Test
    fun `追加文本落在末尾`() {
        val box = EmueraTextBox()
        box.appendText("7")
        assertEquals("7", box.text, ":1366 的 AppendText(num.ToString())")
        assertEquals(1, box.selectionStart, "追加后插入点在新文本末尾")
        assertEquals(0, box.selectionLength)
        box.appendText("89")
        assertEquals("789", box.text)
        assertEquals(3, box.selectionStart)
    }

    @Test
    fun `清空与全选和整行选中`() {
        val box = EmueraTextBox()
        box.assign("abcd")
        box.selectAll()
        assertEquals(0, box.selectionStart, ":1372 SelectAll")
        assertEquals(4, box.selectionLength)
        assertEquals("abcd", box.selectedText(), "SelectedText 的读侧（:1503）")
        box.clear()
        assertEquals("", box.text, ":1310 的 richTextBox1.Clear()")
        assertEquals(0, box.selectionStart)
        assertEquals(0, box.selectionLength)

        box.assign("召回内容")
        box.selectWholeLine()
        assertEquals(0, box.selectionStart, ":1000")
        assertEquals(4, box.selectionLength, ":1001")
        assertEquals("召回内容", box.selectedText())
    }

    @Test
    fun `光标移到末尾只动起点`() {
        val box = EmueraTextBox()
        box.assign("宏内容")
        box.selectWholeLine()
        box.moveCaretToEnd()
        assertEquals(3, box.selectionStart, ":510 只赋 SelectionStart = Text.Length")
        assertEquals(3, box.selectionLength, "本层不在这一句里清长度")
        assertTrue(box.atEnd())
    }

    @Test
    fun `替换选区即先删后插`() {
        val box = EmueraTextBox()
        box.assign("01234")
        box.selectAll()
        box.replaceSelectionWith("x")
        assertEquals("x", box.text, "全选后上屏 ⇒ 整串被替换")
        assertEquals(1, box.selectionStart, "插入点落在插入内容之后")
        assertEquals(0, box.selectionLength)

        box.assign("ab")
        box.replaceSelectionWith("Z")
        assertEquals("Zab", box.text, "起点零、无选区 ⇒ 插到最前")
        assertEquals(1, box.selectionStart)

        box.moveCaretToEnd()
        box.replaceSelectionWith("9")
        assertEquals("Zab9", box.text, "无选区时即在插入点插入")

        box.assign("abcdef")
        box.replaceSelectionWith("X")
        assertEquals("Xabcdef", box.text, "上一步把起点留在末尾，这里被 assign 复位为零")
    }

    @Test
    fun `空选区时删除是空操作`() {
        val box = EmueraTextBox()
        box.assign("abcdef")
        box.replaceSelectionWith("@")
        assertEquals("@abcdef", box.text)
        assertEquals(1, box.selectionStart)
        box.deleteSelection()
        assertEquals("@abcdef", box.text, "无选区 ⇒ :1503-1514 那一路的「削除」菜单本来就是禁用态")
    }

    @Test
    fun `选中片段后删除只去掉片段`() {
        val box = EmueraTextBox()
        box.assign("hello")
        box.selectAll()
        box.deleteSelection()
        assertEquals("", box.text, "全选后删除 ⇒ 空框")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.BACK), "空框 ⇒ 边界键一律吞掉（:1424）")
    }

    @Test
    fun `边界键抑制条件逐条对上`() {
        val box = EmueraTextBox()
        for (key in EmueraTextBox.EdgeKey.entries) {
            assertTrue(box.suppressesEdgeKey(key), "空框时 $key 都吞（:1424、:1428 的或件）")
        }
        box.assign("abc")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.LEFT))
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.HOME))
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.BACK))
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.RIGHT))
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.END))
        box.selectWholeLine()
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.LEFT), "有选区时 :1424 的与件不成立")
        box.moveCaretToEnd()
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.RIGHT), ":1428 起点等于长度")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.END))
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.BACK), "起点不在零")
    }

    @Test
    fun `硬换行切分保留首尾空行`() {
        val box = EmueraTextBox()
        box.assign("")
        assertEquals(listOf(""), box.lines(), "空框也算一行")
        box.assign("ab\ncd")
        assertEquals(listOf("ab", "cd"), box.lines())
        box.assign("ab\r\ncd")
        assertEquals(listOf("ab", "cd"), box.lines(), "CRLF 算一次断行")
        box.assign("ab\rcd")
        assertEquals(listOf("ab", "cd"), box.lines())
        box.assign("\nab")
        assertEquals(listOf("", "ab"), box.lines(), "行首换行留出一个空行")
        box.assign("ab\n")
        assertEquals(listOf("ab", ""), box.lines(), "行尾换行留出一个空行")
        box.assign("a\nb\nc")
        assertEquals(listOf("a", "b", "c"), box.lines())
    }

    @Test
    fun `字面量反斜杠加n在框内不算换行`() {
        val box = EmueraTextBox()
        box.assign("a\\nb")
        assertEquals(listOf("a\\nb"), box.lines(), "四元素切分符属提交侧（EmueraConsole.cs:146、:1429）")
        box.moveCaretToEnd()
        assertEquals(0, box.caretLine())
        assertEquals(4, box.caretCellColumn())
    }

    @Test
    fun `光标行号与该行文本`() {
        val box = EmueraTextBox()
        box.assign("第一行\n第二行\n第三行")
        box.moveCaretToEnd()
        assertEquals(2, box.caretLine())
        assertEquals("第三行", box.caretLineText())

        box.assign("ab\ncd")
        box.replaceSelectionWith("")
        assertEquals(0, box.caretLine(), "起点仍在第一行")
        assertEquals("ab", box.caretLineText())
    }

    @Test
    fun `CRLF 只算一行`() {
        val box = EmueraTextBox()
        box.assign("a\r\nb")
        box.moveCaretToEnd()
        assertEquals(1, box.caretLine(), "CR 后面紧跟 LF 时只前进一行（:194-199 的那一步）")
        assertEquals("b", box.caretLineText())
        assertEquals(1, box.caretCellColumn())
    }

    @Test
    fun `光标列按半角格数`() {
        val box = EmueraTextBox()
        box.assign("xあyy")
        box.moveCaretToEnd()
        assertEquals(5, box.caretCellColumn(), "三个半角各一格、一个全角两格")
        box.assign("あいう")
        box.moveCaretToEnd()
        assertEquals(6, box.caretCellColumn(), "三个全角")
        box.assign("ｱｲｳ")
        box.moveCaretToEnd()
        assertEquals(3, box.caretCellColumn(), "半角假名区 1 格（口径同 Grid.isFullWidth）")
    }

    @Test
    fun `TAB 在列读数里摊成八格`() {
        val box = EmueraTextBox()
        box.assign("\t")
        box.moveCaretToEnd()
        assertEquals(8, box.caretCellColumn(), "度量前先 Replace 成 8 空格（StringMeasure.cs:59-62）")
    }

    @Test
    fun `呈现快照带上光标行与列`() {
        val box = EmueraTextBox()
        box.assign("ab\ncde")
        box.moveCaretToEnd()
        val p = box.presentation()
        assertEquals("ab\ncde", p.text)
        assertEquals(6, p.selectionStart)
        assertEquals(1, p.caretLine)
        assertEquals(3, p.caretCellColumn)
        assertEquals(TextBoxPresentation("ab\ncde", 6, 0, 1, 3), p, "data class 等值 ⇒ 快照可整体断言")
    }

    @Test
    fun `全选后光标锚点回到整串起点`() {
        val box = EmueraTextBox()
        box.assign("aaa\nbbbb")
        box.moveCaretToEnd()
        assertEquals(1, box.caretLine(), "起点在整串末尾 ⇒ 第二个硬换行之后")
        assertEquals(4, box.caretCellColumn(), "只数本行那四格")
        box.selectAll()
        val p = box.presentation()
        assertEquals(0, p.selectionStart, ":1372 SelectAll 把起点拉回零")
        assertEquals(8, p.selectionLength)
        assertEquals(0, p.caretLine, "行读数跟的是插入点，锚点回到零就是第一行")
        assertEquals(0, p.caretCellColumn)
    }

    @Test
    fun `空选区读出来是空串`() {
        val box = EmueraTextBox()
        box.assign("abc")
        box.moveCaretToEnd()
        assertEquals(3, box.selectionStart)
        assertEquals(0, box.selectionLength)
        assertEquals("", box.selectedText(), ":1424 用的正是「SelectedText 长度为零」这一半条件")
        assertTrue(box.atEnd())
    }
}
