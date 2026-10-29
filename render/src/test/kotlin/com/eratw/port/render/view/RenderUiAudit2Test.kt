package com.eratw.port.render.view

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.input.BoxGeom
import com.eratw.port.render.input.EmueraTextBox
import com.eratw.port.render.input.HotkeyErbProgram
import com.eratw.port.render.input.HistoryEdit
import com.eratw.port.render.input.InputHistory
import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.input.MacroKeys
import com.eratw.port.render.input.PosState
import com.eratw.port.render.input.TextBoxPosition
import com.eratw.port.render.input.TextBoxPresentation
import com.eratw.port.render.scroll.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L14「渲染输入 / 按钮 / 视口」第二轮审计（AUDIT2 / L14）。
 *
 * 覆盖面与判据（左 = C# 出处，右 = 本仓落点）：
 *
 * - 1 `MainWindow.cs:1352-1355` `richTextBox1_KeyDown` 开头 `console == null` 即 return
 *   ⇒ [InputSession.recallHistory] / [InputSession.onHotkeyErbNumber] 补 `consolePresent` 闸（**本轮修复**：原来只挡提交，框照改）。
 * - 2 `MainWindow.cs:1402-1406` 按键公共段先 `Value = Maximum` 回贴底
 *   ⇒ [InputSession.snapToBottomForKeyDown]（**本轮补建模**：回车 / Esc / 上下键贴，HOTKEY 命中与翻页不贴）。
 * - 3 `MainWindow.cs:279` 与 `:335-338` 的 `ValueChanged` → `ScrollBack` 时补 `ApplyTextBoxChanges()`
 *   ⇒ 同一条链转给 [TextBoxPosition.onScrollBarValueChanged]（**本轮接线**，输入侧原来整条链缺失）。
 * - 4 `MainWindow.cs:1375-1401` PageUp/PageDown 在 :1402 **之前** return ⇒ 与 [Viewport.pageUp] 互不相干（翻页不得弹回底部）。
 * - 5 `MainWindow.cs:1424`、`:1428` 边界键夹的两种形状 ⇒ [EmueraTextBox.suppressesEdgeKey]（一致，本轮钉数）。
 * - 6 `MainWindow.cs:52-56`、`:940-944`、`:945-966`、`:963`、`:976-984` 历史三件套 ⇒ [InputHistory] / [InputSession]（一致，本轮把「再按 ↑ 第一次不前进」钉住）。
 * - 7 `MainWindow.cs:385` 的 `console != null` **与** `IsWaitingPrimitive` 合取 ⇒ [MacroKeys.dispatch] 的 `waitingPrimitive` 口径 + [InputSession.onMacroKey]（**本轮修正口径**）。
 * - 8 `MainWindow.cs:1567` 菜单预填的三道闸 ⇒ [MacroKeys.prefillFromMenu] 补 `consolePresent`（**本轮修复**）。
 * - 9 `EmueraConsole.cs:2361`、`:2420` 的原式（`PointX` 大于 x 或 `PointX + Width` 小于 x 才跳过）⇒ [ButtonLayer.hit] 的 `col in colStart until colEnd`（一致；半开区间是 ADR-003 的网格化选择，不是语义分叉）。
 * - 10 `EmueraConsole.cs:2360` 的倒序扫（重叠时后打印者赢）⇒ 本层单调列游标 + 正序首个命中（一致，行内结构性不相交）。
 * - 11 `EmueraConsole.cs:2439`、`:2456` 唯一排除支 = 数值等待且非整数 ⇒ [ButtonAcceptance]（一致）。
 * - 12 `EmueraConsole.cs:395-428` `SelectedString` 三支 ⇒ [ButtonLayer.selectedString]（一致）。
 * - 13 `EmueraConsole.cs:3295,3303` 空文本守卫、`ConsoleButtonString.cs:273-277` 零宽压成 -1 ⇒ [ButtonLayer.attach] 的 `width > 0`（一致）。
 * - 14 `ConsoleButtonString.cs:19,296-303` 一条按钮多段 对 `PrintStringBuffer.cs:103-123` 两次 PRINTBUTTON ⇒ [ButtonLayer.attach] 按 [TextRun.buttonId] 合并（一致，两条都钉）。
 * - 15 `EmueraConsole.cs:1738`、`:1804`、`:2323`、`:2543-2567` 滚动模型 ⇒ [Viewport]（守恒 / 极限空屏 / 恒等式三组一致；`:2557-2564` 的缩短归零那一支按平台观感改为**只夹取**，见「缩短不再拽回」与下面那条读数）。
 * - 16 `EmueraConsole.cs:899-906` 的 `is SpriteF` 准入、`:917` 未命中删不掉 ⇒ [GraphicsLayer.addBackground] 的准入判定（**本轮修复**，默认恒真 = 旧行为）。
 * - 17 `ConsoleImagePart.cs:66-70` 高缺省 `FontSize` 且宽按 `DestBaseSize` 等比反推、`:83-92` 负值取绝对值 ⇒ [GraphicGeometry.inlineBox]（**本轮新增**；[GraphicGeometry.inlineSize] 不等比，两口径并存并互指）。
 * - 18 `ASpriteSingle.cs:57-63` 取色显式夹、`:74,86,98` 绘制走 GDI+ 静默裁 ⇒ [GraphicGeometry.sourceRectClipped]（**本轮新增**：安卓 `Canvas.drawBitmap` 对越界源矩形抛异常，不夹 = 真机崩）。
 * - 19 `EmueraConsole.cs:947-952` `BakeBackground` 的 `flag ? num2 : num` ⇒ [GraphicGeometry.coverBox]（一致，本轮钉住溢出形态）。
 * - 20 `HotkeyState.cs:235-246` 三道前置、`:259-261` 末尾 `ENDIF` 的落点 ⇒ [HotkeyErbProgram.keyToNumber]（一致，含官方 bug 照抛，见 `HotkeyErbProgram` 类文档怪癖 3）。
 *
 * 基准 `fixtures/golden/baseline/pc-golden-root.txt` 不含输入框 / 按钮命中 / 帧统计读数
 * （只有 `:525-527` 的 HOTKEY 状态段与窗口几何），所以本文件的期望值一律钉在上面这些 C# 行号上；
 * 官方怪癖按 ADR-005 原样复刻，不「顺手修好」。
 */
class RenderUiAudit2Test {

    // ------------------------------------------------------------------ 夹具

    private fun buttonRow(vararg buttons: Triple<String, Any, Int>): DisplayRow = buttonRowOf(0, *buttons)

    private fun buttonRowOf(indent: Int, vararg buttons: Triple<String, Any, Int>): DisplayRow = DisplayRow(
        runs = buttons.map { (text, value, id) -> buttonRun(text, value, id) },
        cells = buttons.sumOf { Grid.cells(it.first) },
        isLogicalLine = true,
        isLineEnd = true,
        indentCells = indent,
    )

    /** 两个等值按钮、缺省身份（`buttonId = -1` = 手工构造的行）。 */
    private fun legacyRow(vararg buttons: Pair<String, Any>): DisplayRow = DisplayRow(
        runs = buttons.map { (text, value) ->
            TextRun(
                text = text,
                fore = FORE,
                back = BACK,
                styleMask = 0,
                fontName = FONT,
                kind = RunKind.BUTTON,
                buttonValue = value,
            )
        },
        cells = buttons.sumOf { Grid.cells(it.first) },
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun textBox(clientWidthPx: Int = 640, clientHeightPx: Int = 480): TextBoxPosition = TextBoxPosition(
        clientWidthPx = clientWidthPx,
        clientHeightPx = clientHeightPx,
        // 默认几何 = 初始那一行的值（MainWindow.cs:727-728：Location=(0, ClientHeight-LineHeight)、Size=(WindowX, LineHeight)）
        defaultInfo = BoxGeom(left = 0, top = clientHeightPx - LINE_HEIGHT, width = clientWidthPx, height = LINE_HEIGHT),
    )

    private fun scrolledUp(offset: Int, heightLines: Int = 3, lines: Int = 10): Viewport =
        Viewport(heightLines).apply {
            sync(lines)
            scrollUp(offset)
        }

    private fun buttonRun(text: String, value: Any, buttonId: Int): TextRun = TextRun(
        text = text,
        fore = FORE,
        back = BACK,
        styleMask = 0,
        fontName = FONT,
        kind = RunKind.BUTTON,
        buttonValue = value,
        buttonId = buttonId,
    )

    private fun textRun(text: String): TextRun = TextRun(
        text = text,
        fore = FORE,
        back = BACK,
        styleMask = 0,
        fontName = FONT,
    )

    private fun hotkeyProgram(vararg body: String): HotkeyErbProgram {
        val program = HotkeyErbProgram()
        program.toggle(fileExists = true, lines = listOf(HotkeyErbProgram.HEADER) + body.toList())
        program.hotkeyStates.init(4)
        return program
    }

    // ------------------------------------------------------------------ 1 · KeyDown 总闸

    @Test
    fun `无核心层时上下键一次都不动框`() {
        val s = InputSession()
        s.box.assign("当前输入")
        s.consolePresent = false
        assertFalse(s.recallHistory(-1), ":1352 的 console == null 直接 return，movePrev 一次都不调")
        assertEquals("当前输入", s.box.text, "清框与赋值都发生在处理器内部，被 :1352 挡在外面")
        assertEquals(InputHistory.CAPACITY, s.history.selectedInputs, "哨兵值原样保留（:54）")
    }

    @Test
    fun `无核心层时热键数字整条不落地`() {
        val s = InputSession()
        s.box.assign("abc")
        s.consolePresent = false
        assertNull(s.onHotkeyErbNumber(7), ":1352 排在 :1362 之前")
        assertEquals("abc", s.box.text, "原版连 :1365 的 Clear() 都不执行，不只是不提交")
        assertEquals(0, s.submissions.size)
    }

    @Test
    fun `正在跑程序时热键仍改框但不提交`() {
        val s = InputSession()
        s.inProcess = true
        assertNull(s.onHotkeyErbNumber(7), ":1368 的 !IsInProcess 只挡提交")
        assertEquals("7", s.box.text, ":1365-1366 在闸之外")
        assertEquals(1, s.box.selectionLength, ":1372 的 SelectAll 也在闸之外")
    }

    // ------------------------------------------------------------------ 2-3 · 按键回贴底与位置补生效

    @Test
    fun `回车与 Esc 之前先弹回底部`() {
        val viewport = scrolledUp(4)
        val s = InputSession(viewport = viewport)
        s.box.assign("123")
        assertNotNull(s.onEnterKeyPressed(), ":1412 的提交照旧发生")
        assertEquals(0, viewport.offsetFromEnd, ":1402-1406 的 Value = Maximum 排在 :1407 之前")

        viewport.scrollUp(2)
        val esc = assertNotNull(s.onEscapeKeyPressed())
        assertTrue(esc.mesSkip, ":1421 传 mesSkip = true")
        assertEquals(0, viewport.offsetFromEnd, "Esc 同样先吃 :1402")
    }

    @Test
    fun `召回被在跑程序挡下时贴底已经发生`() {
        val viewport = scrolledUp(3)
        val s = InputSession(viewport = viewport)
        viewport.scrollUp(3)
        s.inProcess = true
        assertFalse(s.recallHistory(-1), ":1435 的 !IsInProcess 挡住 movePrev")
        assertEquals(0, viewport.offsetFromEnd, "但 :1402 排在 :1432 之前 ⇒ 贴底是执行过了的，这一点在屏上看得见")
    }

    @Test
    fun `热键命中那一路不贴底`() {
        val viewport = scrolledUp(6)
        val s = InputSession(viewport = viewport)
        assertNotNull(s.onHotkeyErbNumber(7))
        assertEquals(6, viewport.offsetFromEnd, ":1373 的 return 排在 :1402 之前 ⇒ HOTKEY 命中不动滚动条")
    }

    @Test
    fun `宏预填与换组不贴底也不提交`() {
        val viewport = scrolledUp(5)
        val table = KeyMacroTable()
        table.set(0, 0, "123")
        val s = InputSession(macroTable = table, viewport = viewport)
        s.onMacroKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        s.applyMacro(MacroKeys.dispatch(MacroKeys.VK_DIGIT_0 + 1, MacroKeys.Modifier.CONTROL, "", "", 0))
        assertEquals(5, viewport.offsetFromEnd, "F1-F12 与 Ctrl+数字走 ProcessCmdKey（:383 起），根本不经 richTextBox1_KeyDown")
        assertEquals(0, s.submissions.size, "宏这一路永不提交（:507-512 整段没有一次 PressEnterKey）")
    }

    @Test
    fun `贴底幂等且没有视口时是空操作`() {
        val bare = InputSession()
        assertFalse(bare.snapToBottomForKeyDown(), "没接视口 ⇒ 整段空操作（纯输入单测与无窗口宿主）")
        bare.box.assign("x")
        assertNotNull(bare.onEnterKeyPressed())

        val viewport = scrolledUp(0)
        val s = InputSession(viewport = viewport)
        assertFalse(s.snapToBottomForKeyDown(), ":1402 外层 if 不成立时连 RefreshStrings 都不调，ValueChanged 也不触发")
        viewport.scrollUp(5)
        assertTrue(s.snapToBottomForKeyDown(), "真的动了才返回 true")
        assertFalse(s.snapToBottomForKeyDown(), "第二次已经在底部 ⇒ 无事可做")
    }

    @Test
    fun `贴底把 ScrollBack 态挂起的位置补生效`() {
        val pos = textBox()
        pos.requestMove(xOffsetPx = 100, yOffsetFromBottomPx = 200, widthPx = 300)
        pos.applyChanges()
        pos.scrollBack()
        assertEquals(PosState.SCROLL_BACK, pos.state, ":356-360：滚上去看历史时把框收回默认位")
        assertEquals(BoxGeom(0, 461, 640, 19), pos.applied)
        val s = InputSession(position = pos, viewport = scrolledUp(1))
        s.box.assign("1")
        assertNotNull(s.onEnterKeyPressed())
        assertEquals(0, s.viewport!!.offsetFromEnd)
        assertEquals(PosState.CHANGED, pos.state, ":335-338 → :362-369：回到底部才把 nextTextBoxInfo 刷上控件")
        // 左沿夹到 100；上沿 = 480 - 200 - 19 = 261（:345 用**当时生效**的框高）；宽 300（:346 受 640 - 当前左沿夹）
        assertEquals(BoxGeom(100, 261, 300, 19), pos.applied)
    }

    @Test
    fun `程序化滚动期间不接受贴底通知`() {
        val pos = textBox()
        pos.requestMove(100, 200, 300)
        pos.applyChanges()
        pos.scrollBack()
        pos.ignoreScrollBarChanges = true
        val s = InputSession(position = pos, viewport = scrolledUp(1))
        assertNotNull(s.onEnterKeyPressed())
        assertEquals(
            PosState.SCROLL_BACK,
            pos.state,
            ":329 的 TextBoxIgnoreScrollBarChanges 闸门（EmueraConsole.cs:2551、:2566 一开一关）；按键这一路原版不关闸，所以本层也不能自己关",
        )
        pos.ignoreScrollBarChanges = false
        assertTrue(pos.onScrollBarValueChanged(atMaximum = true), "开闸后同一次通知就能生效")
        assertEquals(PosState.CHANGED, pos.state)
    }

    @Test
    fun `未生效的位置请求不会被提交后复位捡回来`() {
        val pos = textBox()
        pos.requestMove(100, 200, 300)
        assertEquals(PosState.WATING_TO_CHANGE, pos.state, ":347 只置状态不动控件（:342-348 整段没有一次 richTextBox1 赋值）")
        val s = InputSession(position = pos)
        s.box.assign("1")
        assertNotNull(s.onEnterKeyPressed())
        s.onInputAccepted()
        assertEquals(
            PosState.WATING_TO_CHANGE,
            pos.state,
            "EmueraConsole.cs:1283 只夹 TextBoxPosChanged，WatingToChange 不复位 ⇒ 这条 MOVETEXTBOX 还挂着，等下一条 INPUT/BINPUT 生效",
        )
        assertEquals(BoxGeom(0, 461, 640, 19), pos.applied)
        pos.applyChanges()
        assertEquals(PosState.CHANGED, pos.state, "INPUT 侧调 ApplyTextBoxChanges 才落地（FunctionIdentifier.cs:852、:896、:2143、:2247、:2352、:2457）")
    }

    // ------------------------------------------------------------------ 5-6 · 光标 / 选区 / 历史

    @Test
    fun `边界键夹的两种形状`() {
        val box = EmueraTextBox()
        box.assign("abc")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.LEFT), ":1424 的 SelectionStart == 0 且 SelectedText.Length == 0")
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.RIGHT), ":1428 不成立（光标不在末尾）")
        box.moveCaretToEnd()
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.END), ":1428 的 SelectionStart == Text.Length")
        box.assign("")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.BACK), ":1424 的后半支 Text.Length == 0")
        assertTrue(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.RIGHT), ":1428 的后半支同义")
        box.assign("abc")
        box.selectAll()
        assertFalse(box.suppressesEdgeKey(EmueraTextBox.EdgeKey.HOME), "有选区时不夹（SelectedText.Length == 0 不成立）")
        assertEquals("abc", box.selectedText(), ":1424、:1503 的 SelectedText 读侧")
        box.deleteSelection()
        assertEquals("", box.text, "删选区 = 用空串替换当前选区（replaceSelectionWith 的空插入形态）")
        assertEquals(TextBoxPresentation("", 0, 0, 0, 0), box.presentation(), "呈现快照：文本/起点/长度/光标行/格列")
        box.assign("全角ab")
        box.moveCaretToEnd()
        assertEquals(6, box.caretCellColumn(), "半角格口径由 Grid.cells 数：两个全角各 2 + 两个半角各 1（StringMeasure.cs:59-62 只证「度量前先摊 TAB」，格数不是从它抄的）")
    }

    @Test
    fun `被核心层拒绝的输入照样进历史且空提交不进历史`() {
        val s = InputSession()
        s.box.assign("非数字")
        assertNotNull(s.onEnterKeyPressed(), ":933 的 updateInputs 排在 :934 交给核心层之前")
        assertEquals("非数字", s.history.snapshot().last(), ":947-951 整体左移一格后末尾写入")
        assertEquals(InputHistory.CAPACITY, s.history.selectedInputs, ":966 复位")

        // 此时框已被 :965 清成空串，直接再按回车就是提交空串
        assertNotNull(s.onEnterKeyPressed(), ":924 只夹 console 与 IsInProcess，空串照样交")
        assertEquals(2, s.submissions.size)
        assertTrue(s.submissions.last().text.isEmpty(), ":926 读到的就是空串")
        assertEquals(1, s.history.snapshot().count { it.isNotEmpty() }, ":940-944 空串不进历史")
        assertEquals("非数字", s.history.snapshot().last(), "历史末条没被空提交冲掉")
        assertEquals(InputHistory.CAPACITY, s.history.selectedInputs, ":940-944 的早退绕过了 :966")
    }

    @Test
    fun `再按上键时第一次不前进`() {
        val s = InputSession()
        s.box.assign("X")
        assertNotNull(s.onEnterKeyPressed())
        assertTrue(s.recallHistory(-1), "普通召回：:987 的 num = selectedInputs + move")
        assertEquals("X", s.box.text, ":998-999")
        assertEquals(1, s.box.selectionLength, ":1000-1001 整行选中")
        assertEquals(99, s.history.selectedInputs, "落点 = 末格")
        assertNotNull(s.onEnterKeyPressed(), "把召回到的同一条原样再提交一次")
        assertEquals(99, s.history.lastSelected, ":963 —— 与末条相同 ⇒ lastSelected = selectedInputs")
        assertEquals(InputHistory.CAPACITY, s.history.selectedInputs, ":966")
        assertTrue(s.recallHistory(-1), ":976-984 —— 刚从提交回来，第一次 ↑ 被抹成 0")
        assertEquals(99, s.history.selectedInputs, "落点仍是同一条（num = lastSelected + 0）")
        assertEquals("X", s.box.text)
    }

    @Test
    fun `空槽先自增再判越界导致上键卡在末条`() {
        val s = InputSession()
        s.box.assign("A")
        assertNotNull(s.onEnterKeyPressed(), ":947-951 之后只有末格有值")
        assertIs<HistoryEdit.SetText>(s.history.recall(-1)).also {
            assertEquals("A", it.text, ":996 的「槽为空则先自增再判是否走到哨兵」—— 这里命中末条")
            assertTrue(it.selectAll, ":1000-1001 整行选中")
        }
        assertIs<HistoryEdit.SetText>(s.history.recall(-1)).also {
            assertEquals("A", it.text, "再按 ↑：num 退到空格 ⇒ 自增回同一条 ⇒ 卡在末条（官方行为，不修）")
        }
        assertIs<HistoryEdit.SetText>(s.history.recall(1)).also {
            assertEquals("", it.text, "↓ 走到哨兵位 ⇒ :991-995 清空文本框")
            assertFalse(it.selectAll, ":991-995 这一支只赋 Text，不动选区")
        }
        assertIs<HistoryEdit.None>(s.history.recall(1), "已经贴到哨兵还往下 ⇒ 越界不响应（:989 的范围判）")
        assertEquals(1, s.submissions.size, "召回全程零提交：整段只有开头那一次回车产出（:969-1004 一次 PressEnterKey 都没有）")
    }

    // ------------------------------------------------------------------ 7-8 · 宏的两道闸

    @Test
    fun `等待原始键只挡有核心层的那一路`() {
        val table = KeyMacroTable()
        table.set(0, 0, "123")
        val ignored = InputSession(macroTable = table).onMacroKey(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            waitingPrimitive = true,
        )
        assertSame(MacroKeys.Decision.Ignored, ignored, ":385-388 console != null 且 IsWaitingPrimitive ⇒ 整段提前 return")

        val noConsole = InputSession(macroTable = table)
        noConsole.consolePresent = false
        val decision = noConsole.onMacroKey(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            waitingPrimitive = true,
        )
        assertEquals(MacroKeys.Decision.Prefill("123"), decision, ":385 是合取；没有核心层时条件不成立，宏照样预填")
        assertEquals("123", noConsole.box.text)
        assertEquals(3, noConsole.box.selectionStart, ":510 插入点放到末尾")
        assertEquals(0, noConsole.submissions.size)
    }

    @Test
    fun `菜单预填的三道闸`() {
        assertEquals(
            MacroKeys.Decision.Prefill("1"),
            MacroKeys.prefillFromMenu(macro = "1", inProcess = false),
            ":1567 三条都成立才去取宏",
        )
        assertSame(MacroKeys.Decision.Ignored, MacroKeys.prefillFromMenu("1", inProcess = true), ":1567 的 !console.IsInProcess")
        assertSame(
            MacroKeys.Decision.Ignored,
            MacroKeys.prefillFromMenu("1", inProcess = false, useKeyMacro = false),
            ":1567 的 Config.UseKeyMacro（ConfigData.cs:53 默认 true）",
        )
        assertSame(
            MacroKeys.Decision.Ignored,
            MacroKeys.prefillFromMenu("1", inProcess = false, consolePresent = false),
            ":1567 的 console != null —— 本轮补上的那条",
        )
        assertSame(
            MacroKeys.Decision.Ignored,
            MacroKeys.prefillFromMenu("", inProcess = false),
            ":1570 宏为空整条不响应（按键那一路 :509 反而无条件赋空串清框，两支守卫不同）",
        )
    }

    @Test
    fun `空宏按功能键清框而 Shift 加空框不覆盖旧宏`() {
        val table = KeyMacroTable()
        table.set(2, 0, "旧宏")
        val s = InputSession(macroTable = table)
        assertEquals(
            MacroKeys.Decision.Prefill(""),
            s.onMacroKey(MacroKeys.VK_F1 + 5, MacroKeys.Modifier.NONE),
            ":509 无条件赋值 ⇒ 宏为空就是把框清空（另取一个空槽，不能用刚写过宏的那一格）",
        )
        assertEquals("", s.box.text)
        assertEquals("旧宏", table.get(2, 0), "按 Fn 只读表，不写表（:509 走的是 GetMacro）")
        val record = s.onMacroKey(MacroKeys.VK_F1 + 2, MacroKeys.Modifier.SHIFT)
        assertIs<MacroKeys.Decision.Record>(record)
        assertFalse(record.write, ":501-504 的 IsNullOrEmpty 守卫：框内为空就不写")
        assertEquals("旧宏", table.get(2, 0), "旧宏原样留着")
        assertEquals(0, s.submissions.size, "记录这一支也吞键不提交（:505）")
    }

    @Test
    fun `换组返回组名标签文本`() {
        val table = KeyMacroTable()
        val s = InputSession(macroTable = table)
        val label = s.applyMacro(
            MacroKeys.dispatch(
                keyCode = MacroKeys.VK_DIGIT_0 + 3,
                modifier = MacroKeys.Modifier.CONTROL,
                boxText = "",
                macroAtSlot = "",
                group = 0,
            ),
        )
        assertEquals(table.groupName(3), label, ":1605 标签文本 = KeyMacro.GetGroupName(group)")
        assertEquals(3, s.macroGroup, ":1604")
        assertEquals(0, s.submissions.size)
    }

    @Test
    fun `Alt 与 Ctrl+Shift 组合三个旗标都不成立`() {
        assertSame(
            MacroKeys.Decision.Ignored,
            MacroKeys.dispatch(MacroKeys.VK_F1, MacroKeys.Modifier.OTHER, "框内", "宏", 0),
            ":493-495 用的是整集合相等而不是 HasFlag ⇒ 这类组合连键都不吞",
        )
        assertSame(
            MacroKeys.Decision.Ignored,
            MacroKeys.dispatch(MacroKeys.VK_F1, MacroKeys.Modifier.CONTROL, "框内", "宏", 0),
            ":496 命中区间后走不到 :514 的 else if",
        )
    }

    // ------------------------------------------------------------------ 9-14 · 按钮命中 / 层级 / 重叠

    @Test
    fun `命中区间是半开区间且相邻按钮不共用格`() {
        val layer = ButtonLayer()
        layer.attach(listOf(buttonRow(Triple("ab", 1L, 0), Triple("cd", 2L, 1))))
        assertEquals(HitTarget(0, 0, 2, 1L), layer.hit(0, 0), "首格归第一条")
        assertEquals(HitTarget(0, 0, 2, 1L), layer.hit(0, 1), "末格仍归第一条（:2361 的闭区间换成网格半开，ADR-003）")
        assertEquals(HitTarget(0, 2, 4, 2L), layer.hit(0, 2), "第二条的首格 = 第一条的 colEnd ⇒ 行内不相交，倒序扫无从差别")
        assertNull(layer.hit(0, 4), "右沿之外")
        assertNull(layer.hit(0, -1), "屏幕列负数直接不算命中（原版坐标取自 MainPicBox，天然非负，:2321）")
        assertNull(layer.hit(1, 0), "行号不对")
    }

    @Test
    fun `对齐位移后整段出左界的按钮点不到`() {
        val layer = ButtonLayer()
        layer.attach(listOf(buttonRowOf(-6, Triple("ab", 7L, 0))))
        assertEquals(
            listOf(HitTarget(0, -6, -4, 7L)),
            layer.all(),
            "区间原样带上负偏移（ConsoleDisplayLine.cs:80,83 的减法可以算出负 num2，本层不夹回 0）",
        )
        assertNull(layer.hit(0, 0), ":2361 直接比大小 ⇒ 画到左沿外的按钮在可见列上恒不命中")
    }

    @Test
    fun `非按钮 kind 带着值也不入表`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                DisplayRow(
                    runs = listOf(textRun("xy").copy(kind = RunKind.TEXT, buttonValue = 9L)),
                    cells = 2,
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            ),
        )
        assertTrue(layer.all().isEmpty(), "命中区只由 RunKind.BUTTON 承载（EmueraConsole.cs:411-417 的 Input/Inputs 挂在 ConsoleButtonString 上）")
    }

    @Test
    fun `空文本按钮不入表而单空格按钮入表`() {
        val empty = ButtonLayer()
        empty.attach(listOf(legacyRow("" to 1L)))
        assertTrue(empty.all().isEmpty(), ":3295,3303 的 IsNullOrEmpty 守卫 + :273-277 把零宽压成 Width=-1")

        val space = ButtonLayer()
        space.attach(listOf(legacyRow(" " to 2L)))
        assertEquals(listOf(HitTarget(0, 0, 1, 2L)), space.all(), "空格量得出宽度，照原版进表")
    }

    @Test
    fun `同 buttonId 的多样式段并成一条命中区`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                DisplayRow(
                    runs = listOf(buttonRun("ab", 5L, 0), buttonRun("cd", 5L, 0)),
                    cells = 4,
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            ),
        )
        assertEquals(
            listOf(HitTarget(0, 0, 4, 5L)),
            layer.all(),
            "一条 ConsoleButtonString 的 StrArray 可以多段（ConsoleButtonString.cs:19,296-303），而 Buttons 数组按按钮计数（ConsoleDisplayLine.cs:18,28）",
        )
    }

    @Test
    fun `值相同且列相接但身份不同仍是两条`() {
        val layer = ButtonLayer()
        layer.attach(listOf(buttonRow(Triple("ab", 5L, 0), Triple("cd", 5L, 1))))
        assertEquals(
            listOf(HitTarget(0, 0, 2, 5L), HitTarget(0, 2, 4, 5L)),
            layer.all(),
            "两次 PRINTBUTTON 各 createButton（PrintStringBuffer.cs:103-123），各自的 Input 各读各的",
        )
        val legacy = ButtonLayer()
        legacy.attach(listOf(legacyRow("ab" to 5L, "cd" to 5L)))
        assertEquals(
            listOf(HitTarget(0, 0, 4, 5L)),
            legacy.all(),
            "buttonId 全为 -1（手工构造的行、身份未知）才退回「列相接且值相同就并」的老口径",
        )
    }

    @Test
    fun `数值等待期字符串按钮连指着都不算`() {
        val layer = ButtonLayer()
        layer.attach(listOf(buttonRow(Triple("ab", "s", 0), Triple("cd", 7L, 1))))
        assertNull(layer.hit(0, 0, ButtonAcceptance.INT), ":2439、:2456 的唯一排除支")
        assertEquals(HitTarget(0, 0, 2, "s"), layer.hit(0, 0, ButtonAcceptance.STR), "StrButton 只看 IsButton（FunctionIdentifier.cs:2271、:2293）")
        assertEquals(HitTarget(0, 0, 2, "s"), layer.hit(0, 0), "不带 acceptance 的入口等价 ANY")
        assertEquals("s", layer.click(0, 0, ButtonAcceptance.ANY), ":415-417 取 Inputs")
        assertNull(layer.click(0, 0, ButtonAcceptance.INT), ":427 那条 return null 的网格形态")
        assertEquals("7", layer.click(0, 2, ButtonAcceptance.INT), ":411-413 取 Input.ToString()")
        assertEquals("7", layer.click(0, 2, ButtonAcceptance.STR), "数值按钮的 Inputs 在构造时就是 Input.ToString()（ConsoleButtonString.cs:85）")
    }

    @Test
    fun `跨行的同一按钮在每一行各一条命中区`() {
        val layer = ButtonLayer()
        layer.attach(
            listOf(
                buttonRow(Triple("abcdef", 3L, 0)),
                DisplayRow(
                    runs = listOf(buttonRun("gh", 3L, 0)),
                    cells = 2,
                    isLogicalLine = false,
                    isLineEnd = true,
                ),
            ),
        )
        assertEquals(
            listOf(HitTarget(0, 0, 6, 3L), HitTarget(1, 0, 2, 3L)),
            layer.all(),
            "DivideAt 把 IsButton/IsInteger/Input/Inputs/Generation 整份抄给后半截（ConsoleButtonString.cs:247-251），后半截在下一行从 0 重新排点（PrintStringBuffer.cs:242-249）",
        )
        assertEquals("3", layer.click(1, 0, ButtonAcceptance.INT))
    }

    @Test
    fun `外扩不重叠且宁可小于最小尺寸`() {
        val dense = ButtonLayer()
        dense.attach(listOf(buttonRow(Triple("a", 1L, 0), Triple("b", 2L, 1), Triple("c", 3L, 2))))
        val denseBoxes = dense.touchHitboxes(minWidthCells = 4, clientWidthCells = 10)
        assertEquals(
            listOf(0 to 1, 1 to 2, 2 to 6),
            denseBoxes.map { it.colStart to it.colEnd },
            "右沿最远只到下一个按钮的原始左沿 ⇒ 挤在一起的前两条长不动，最后一条独占右侧余量（宽度不足也不重叠）",
        )

        val spaced = ButtonLayer()
        spaced.attach(
            listOf(
                DisplayRow(
                    runs = listOf(buttonRun("a", 1L, 0), textRun("    "), buttonRun("b", 2L, 1)),
                    cells = 6,
                    isLogicalLine = true,
                    isLineEnd = true,
                ),
            ),
        )
        val boxes = spaced.touchHitboxes(minWidthCells = 4, clientWidthCells = 10)
        assertEquals(
            listOf(0 to 4, 4 to 8),
            boxes.map { it.colStart to it.colEnd },
            "左右各扩一半、奇数余数给右侧；两条首尾相接仍不相交",
        )
    }

    @Test
    fun `指针态不跨 attach 存活而复用时表实例不变`() {
        val rows = listOf(buttonRow(Triple("ab", 1L, 0)))
        val layer = ButtonLayer()
        layer.attach(rows)
        val first = layer.all().single()
        layer.setPointer(0, 0, pressed = true)
        assertTrue(layer.isFocused(first), ":830-838 的 ButtonIsSelected/ButtonIsPointing 只是引用比较，纯绘制标记")
        assertTrue(layer.isPressed(first))
        assertFalse(layer.isPressed(HitTarget(0, 0, 2, 2L)), "不是同一条就不是")

        layer.attach(rows.toList())
        assertSame(first, layer.all().single(), "行窗口逐元素同一批 ⇒ 走复用分支，命中表一个新对象都没造")
        assertFalse(layer.isFocused(first), ":130 的 clearPointer 在任何分支之前 ⇒ 指针态不跨 attach 存活")

        layer.attach(listOf(buttonRow(Triple("ab", 1L, 0))))
        assertNotSame(first, layer.all().single(), "等值但不是同一批实例 ⇒ 必须整表重建")
    }

    // ------------------------------------------------------------------ 15 · 视口边界

    @Test
    fun `追加守恒与缩短只夹取`() {
        val view = Viewport(heightLines = 3)
        view.sync(5)
        view.scrollUp(2)
        assertEquals(2, view.offsetFromEnd)
        view.sync(8)
        assertEquals(2, view.offsetFromEnd, ":2552-2556 的 Value += num ⇒ 偏移守恒（这就是上翻锁定）")
        assertEquals(6, view.scrollBarValue(), "Value = Maximum - offset = 8 - 2")
        // 原版 :2557-2564 的 num 小于 0 那一支回贴底；本移植只夹进 maxOffset（8→4 后余量 4-3=1）。
        view.sync(4)
        assertEquals(1, view.offsetFromEnd, "缩短越界才夹取，不清零玩家的锚点")
        assertEquals(4, view.retainedLines)
        view.sync(3)
        assertEquals(0, view.offsetFromEnd, "不够一屏 ⇒ maxOffset 自己归零，仍是贴底")
        view.sync(0)
        assertFalse(view.scrollEnabled, ":2565 的 Enabled = count 大于 0")
        view.onCleared()
        assertEquals(0, view.retainedLines)
    }

    @Test
    fun `上翻到极限是保留区最早的一屏`() {
        val view = Viewport(heightLines = 3)
        view.sync(5)
        view.scrollUp(5)
        assertEquals(
            2,
            view.offsetFromEnd,
            "M11-B 起上界 = max(0, retainedLines - heightLines) = 5 - 3：滚到顶显示最早一屏，" +
                "不复刻原版 Value == 0 ⇒ num2 == -1 的整屏空（EmueraConsole.cs:1806-1812，取舍见 Viewport 类文档）",
        )
        assertEquals(3, view.scrollBarValue(), "Value = Maximum - offset = 5 - 2")
        assertEquals(2, view.bottomRingIndex(), ":1789-1792 的 num2 = Value - 1")
        assertEquals(
            0L..2L,
            view.visibleRange(5, 0L),
            ":1852 的绘制循环画到的正是保留区最早三行",
        )
    }

    @Test
    fun `绘制与命中两个窗口在整数除法下恒等`() {
        val view = Viewport(heightLines = 54)
        view.sync(300)
        assertEquals(299, view.bottomRingIndex())
        assertEquals(246, view.topRingIndex(), "num3 = num2 - (H 除以 L)：:2328-2331 与 :1806-1807 逐值相同（(H-L)/L + 1 == H/L）")
        assertEquals(245, view.topRingIndex(extraAbove = 1), "绘制侧多要的那一排")
        assertEquals(view.topRingIndex(), view.topRingIndex(extraAbove = 1) + 1)
    }

    @Test
    fun `滚轮与翻页的步长口径`() {
        val view = Viewport(heightLines = 3)
        view.sync(100)
        view.onWheel(120)
        assertEquals(1, view.offsetFromEnd, ":1262 的 -Math.Sign(delta) * SmallChange * Config.ScrollHeight，SCROLLHEIGHT 默认 1 行（ConfigData.cs:74）")
        view.onWheel(-120, linesPerScroll = 3)
        assertEquals(0, view.offsetFromEnd, "向下 3 行但已经贴底 ⇒ 夹在 0（:1277-1279 的 Value = Maximum）")
        view.pageUp()
        assertEquals(10, view.offsetFromEnd, ":1378-1382 的固定 10 行")
        view.scrollUp(Int.MAX_VALUE)
        assertEquals(
            97,
            view.offsetFromEnd,
            "夹到 max(0, retainedLines - heightLines) = 100 - 3（M11-B：滚到顶显示最早一屏，" +
                "不再夹到 retainedLines 的空屏点；下界仍对应 :1281-1283 的 Value = Minimum）",
        )
        view.snapToBottom()
        assertTrue(view.atBottom, ":1804 的 Value == Maximum 判贴底")
    }

    @Test
    fun `缓冲区写满后追加不改变偏移`() {
        val view = Viewport(heightLines = 3)
        view.sync(4000)
        view.scrollUp(17)
        for (i in 0 until 50) {
            view.sync(4000)
        }
        assertEquals(17, view.offsetFromEnd, "count 没变 ⇒ :2547-2550 直接早退，一个数都不改（淘汰式缓冲的稳态）")
    }

    // ------------------------------------------------------------------ 16 · 图形层准入

    @Test
    fun `背景层准入按 is SpriteF 判据`() {
        val always = GraphicsLayer()
        always.addBackground(GraphicsLayer.BgPlacement("NOTFOUND", depth = 0, opacity = 255))
        assertEquals(1, always.background.size, "不注入判据 = 旧行为：一律收下（宿主接线前行为不变）")

        val gated = GraphicsLayer { name -> name == "OK" }
        gated.addBackground(GraphicsLayer.BgPlacement("OK", depth = 0, opacity = 255))
        val watermark = gated.revision.get()
        gated.addBackground(GraphicsLayer.BgPlacement("NOPE", depth = 1, opacity = 255))
        assertEquals(listOf("OK"), gated.background.map { it.resource }, ":899 查不到 / 不是整幅基图 ⇒ 整条语句静默不成立")
        assertEquals(watermark, gated.revision.get(), "被拒时连水位都不推进：:905 的 BakeBackground 也在那个 if 里")
    }

    @Test
    fun `背景层按 depth 降序与删除语义`() {
        val layer = GraphicsLayer()
        layer.addBackground(GraphicsLayer.BgPlacement("A", depth = 1, opacity = 255))
        layer.addBackground(GraphicsLayer.BgPlacement("B", depth = 5, opacity = 128))
        layer.addBackground(GraphicsLayer.BgPlacement("C", depth = 1, opacity = 255))
        assertEquals(
            listOf("B", "A", "C"),
            layer.background.map { it.resource },
            ":904 的比较器（depth 大的在前）；同 depth 本层用稳定排序保入列序 —— 原版比较器从不返回 0，TODO(verify) 见其 KDoc",
        )
        val watermark = layer.revision.get()
        layer.removeBackground("ZZZ")
        assertEquals(3, layer.background.size, ":917 的 RemoveAt(-1) 原版会抛，本层不抛（返回码与告警归 core/fn）")
        assertEquals(watermark, layer.revision.get(), "没删掉东西就不推进水位")
        layer.removeBackground("A")
        assertEquals(listOf("B", "C"), layer.background.map { it.resource }, "只删第一个名字命中的（FindIndex + RemoveAt）")
        layer.clearBackground()
        assertTrue(layer.background.isEmpty(), ":909-913")
        assertFalse(layer.hasPendingInline())
    }

    @Test
    fun `内联段队列排空后不分配`() {
        val layer = GraphicsLayer()
        assertFalse(layer.hasPendingInline())
        assertTrue(layer.drainInline().isEmpty())
        assertSame(layer.drainInline(), layer.drainInline(), "两次空排空给的是同一个空表实例（稳态帧零分配）")
    }

    // ------------------------------------------------------------------ 17-19 · 图形几何

    @Test
    fun `PRINTIMG 只给一个尺寸时按基准宽高比反推`() {
        val box = GraphicGeometry.inlineBox(givenWidthPx = 0, givenHeightPx = 60, baseW = 200, baseH = 100, fontSizePx = 18)
        assertEquals(120, box[0], ":69 的 DestBaseSize.Width * num / DestBaseSize.Height（整数除法）")
        assertEquals(60, box[1], "高用脚本给的")
        val defaultHeight = GraphicGeometry.inlineBox(0, 0, 200, 100, 18)
        assertEquals(18, defaultHeight[1], ":66 高没给 ⇒ Config.FontSize（这一支不退等比）")
        assertEquals(36, defaultHeight[0], "高退成 FontSize 之后宽 = 200 * 18 / 100")
        assertEquals(
            listOf(200, 100),
            GraphicGeometry.inlineBox(200, 100, 32, 32, 18).toList(),
            "两个尺寸都给了就都用脚本的，基准尺寸不参与",
        )
        assertEquals(
            listOf(50, 20),
            GraphicGeometry.inlineBox(-50, -20, 10, 10, 18).toList(),
            ":83-92 的负值形态是取绝对值并搬原点，不是丢弃（inlineSize 会把负值当没给）",
        )
        assertEquals(listOf(0, 18), GraphicGeometry.inlineBox(0, 0, 0, 0, 18).toList(), "基准高为 0 ⇒ 不做除法，宽给 0")
        assertEquals(
            listOf(64, 64),
            GraphicGeometry.inlineBox(0, 64, 64, 64, 18).toList(),
            "基准 1 比 1 时等比反推与原尺寸同值（两口径的差异只在非 1 比 1）",
        )
    }

    @Test
    fun `inlineSize 的旧口径与 inlineBox 的新口径并存`() {
        assertEquals(120, GraphicGeometry.inlineSize(120, 64), "给了值就用，两式同值")
        assertEquals(64, GraphicGeometry.inlineSize(0, 64), "没给 ⇒ 退基准宽：**不等比**，与 :69 不同（函数 KDoc 已标注）")
        assertEquals(64, GraphicGeometry.inlineSize(-5, 64), "非正一律当没给")
    }

    @Test
    fun `源矩形夹进基图以免安卓抛异常`() {
        assertNull(
            GraphicGeometry.sourceRectClipped(100, 50, 200, 300, 64, 64),
            "整块在基图右下之外 ⇒ 没有可画的像素（GDI+ 静默裁，Canvas.drawBitmap 抛 IllegalArgumentException）",
        )
        assertEquals(listOf(0, 0, 20, 30), GraphicGeometry.sourceRectClipped(0, 0, 20, 30, 64, 64)?.toList(), "完全在内时原样")
        assertEquals(listOf(0, 0, 15, 10), GraphicGeometry.sourceRectClipped(-5, 0, 20, 10, 64, 64)?.toList(), "左上越界 ⇒ 夹到 (0,0)")
        assertEquals(listOf(0, 0, 64, 64), GraphicGeometry.sourceRectClipped(0, 0, 999, 999, 64, 64)?.toList(), "右下越界 ⇒ 夹到基图右沿")
        assertNull(GraphicGeometry.sourceRectClipped(-30, -30, 20, 20, 64, 64), "整块在左上之外 ⇒ null")
        assertEquals(listOf(0, 0, 64, 64), GraphicGeometry.sourceRectClipped(3, 4, 0, 9, 64, 64)?.toList(), "srcW 非正 ⇒ 整幅基图（沿用 sourceRect 那支）")
        assertEquals(listOf(100, 50, 300, 350), GraphicGeometry.sourceRect(100, 50, 200, 300, 64, 64).toList(), "纯算式入口不夹，保留脚本原值")
    }

    @Test
    fun `cover 取较大缩放比与 BakeBackground 同式`() {
        val wide = GraphicGeometry.coverBox(areaW = 1000f, areaH = 600f, srcW = 200f, srcH = 200f)
        assertEquals(1000f, wide[2], ":949 的 flag 等价于 scaleW 小于 scaleH；此处不成立 ⇒ 用 num（=5）")
        assertEquals(1000f, wide[3])
        assertEquals(0f, wide[0], ":952 的水平居中")
        assertEquals(0f, wide[1], ":955 的 Rectangle(x, 0, ...) 顶对齐")

        val tall = GraphicGeometry.coverBox(areaW = 600f, areaH = 1000f, srcW = 200f, srcH = 200f)
        assertEquals(1000f, tall[2], "反过来用 num2 ⇒ 仍是 cover（把短边填满），不是 contain")
        assertEquals(-200f, tall[0], "居中后左沿为负：原版 (int) 截断给 -200，窗外那截交给绘制侧的裁剪")
    }

    // ------------------------------------------------------------------ 20 · HOTKEY.ERB

    @Test
    fun `解释器的三道前置门槛各自独立`() {
        val program = HotkeyErbProgram()
        val lines = listOf(HotkeyErbProgram.HEADER, "IF KEY == KEYS:F1", "RETURN 5", "ENDIF")
        assertEquals(0, program.parse(lines), ":85-164 正常收尾")
        assertEquals(HotkeyErbProgram.NO_MATCH, program.keyToNumber(0x70), ":235-238 没 Toggle 过 ⇒ 不响应")
        val notFound = program.toggle(fileExists = false, lines = emptyList())
        assertFalse(notFound.enabled)
        assertEquals(HotkeyErbProgram.MSG_NOT_FOUND, notFound.message, ":181-184")
        assertEquals(HotkeyErbProgram.NO_MATCH, program.keyToNumber(0x70), "Toggle 失败也不解锁：availableHotkeyFile 仍 false（:243-246）")
        assertFalse(program.availableHotkeyFile)
    }

    @Test
    fun `状态数组未初始化时解释器一律不响应`() {
        val program = HotkeyErbProgram()
        val toggle = program.toggle(
            fileExists = true,
            lines = listOf(HotkeyErbProgram.HEADER, "IF KEY == KEYS:F1", "RETURN 5", "ENDIF"),
        )
        assertTrue(toggle.enabled, ":193 的 enabled 与 availableStateArray 无关")
        assertEquals(HotkeyErbProgram.MSG_ON_NO_INIT, toggle.message, ":200")
        assertEquals(HotkeyErbProgram.NO_MATCH, program.keyToNumber(0x70), ":239-242 的第二道闸")
        program.hotkeyStates.init(4)
        assertEquals(5, program.keyToNumber(0x70), "数组通了以后命中值照常返回")
        assertEquals(HotkeyErbProgram.NO_MATCH, HotkeyErbProgram().keyToNumber(0x70), "另一份实例不受影响（无静态可变全局）")
    }

    @Test
    fun `末尾ENDIF 的落点在原版必然抛而本层照抛`() {
        val program = hotkeyProgram("IF KEY == KEYS:F1", "RETURN 5", "ENDIF")
        assertEquals(5, program.keyToNumber(0x70), "命中那一路不受影响")
        val e = assertFailsWith<IllegalStateException> { program.keyToNumber(0x71) }
        assertEquals(
            "something is wrong",
            e.message,
            ":259-261 —— ENDIF 把跳转落点写成 bytecode.Count，而那条夹正好判它越界（ADR-005 照抛，见类文档怪癖 3）",
        )

        val twoBlocks = hotkeyProgram("IF KEY == KEYS:F1", "RETURN 5", "ENDIF", "IF KEY == KEYS:F2", "RETURN 6", "ENDIF")
        assertEquals(6, twoBlocks.keyToNumber(0x71), "第二块命中：第一块的落点 7 在数组内，正常接住")
        assertFailsWith<IllegalStateException> { twoBlocks.keyToNumber(0x72) }
    }

    @Test
    fun `求值四类与命令两类的落位`() {
        val program = hotkeyProgram("SIF STATE:2 == 7", "RETURN 1", "RETURN 9")
        assertEquals(
            listOf(1, 8, 2, 2, 7, 0, 0, 1, 0, 0, 9),
            program.bytecodeSnapshot(),
            "SIF 也发 GOTOIFNOT 并压栈（:137-147），补丁在下一条 RETURN 处理完时落位（:156-161）",
        )
        assertEquals(9, program.keyToNumber(0), "STATE:2 还是 0 ⇒ 跳到第 8 格的兜底 RETURN")
        program.hotkeyStates.set(2L, 7L)
        assertEquals(1, program.keyToNumber(0), "STATE 相等 ⇒ 接住第一个 RETURN")

        val getter = hotkeyProgram("RETURN STATE:2")
        getter.hotkeyStates.set(2L, 0x1_0000_0064L)
        assertEquals(
            100,
            getter.keyToNumber(0),
            ":226 的 (int)state[num] 是**截断**到低 32 位，不是夹取（0x1_0000_0064 → 100）",
        )
    }

    @Test
    fun `KEY 名解析失败是错误码而裸数解析失败是崩溃`() {
        val program = HotkeyErbProgram()
        assertEquals(1, program.parse(listOf(HotkeyErbProgram.HEADER, "IF KEY == KEYS:NOT_A_KEY", "RETURN 1", "ENDIF")), ":55-58 的 TryParse 失败 ⇒ 错误码 1")
        assertEquals(
            listOf(HotkeyErbProgram.CMD_GOTOIFNOT, HotkeyErbProgram.PLACEHOLDER),
            program.bytecodeSnapshot().take(2),
            "怪癖 1：失败那次已经写进去的半截字节码不回收（:59-60 起就是追加式的）",
        )
        assertFailsWith<NumberFormatException> {
            program.parse(listOf(HotkeyErbProgram.HEADER, "IF STATE:abc", "RETURN 1", "ENDIF"))
        }
    }

    @Test
    fun `Toggle 的两条早退与重复开启`() {
        val program = HotkeyErbProgram()
        val on = program.toggle(fileExists = true, lines = listOf(HotkeyErbProgram.HEADER, "RETURN 3"))
        assertTrue(on.enabled)
        assertNull(on.parseErrorCode)
        assertEquals(HotkeyErbProgram.NO_MATCH, program.keyToNumber(0), "状态数组还没建（:239-242）")
        program.hotkeyStates.init(1)
        assertEquals(3, program.keyToNumber(0), "没有 IF 的单条 RETURN 直接命中（:266-267）")

        val off = program.toggle(fileExists = false, lines = emptyList())
        assertFalse(off.enabled)
        assertEquals(HotkeyErbProgram.MSG_OFF, off.message, ":168-172 已启用时只关，连文件都不看")

        val again = program.toggle(fileExists = false, lines = emptyList())
        assertTrue(again.enabled, ":174-178 availableHotkeyFile 已为真 ⇒ 不重读文件直接开")
        assertEquals(HotkeyErbProgram.MSG_ON, again.message)
        assertEquals(
            HotkeyErbProgram.MSG_PARSE_FAILED,
            HotkeyErbProgram().toggle(fileExists = true, lines = listOf("坏的开头")).message,
            ":87-94 的两条前置（行数不足 / 首行不等）都返回错误码 1 ⇒ :189 的文案；这里必须换新实例，:168 的「已启用先关」会抢在前面",
        )
    }

    @Test
    fun `状态数组越界照原版裸抛`() {
        val program = HotkeyErbProgram()
        assertFailsWith<IllegalStateException> { program.hotkeyStates.set(0, 1) }.also {
            assertEquals("use HOTKEY_STATE_INIT before using HOTKEY_STATE", it.message, ":41-48 的原文案，一字不改")
        }
        program.hotkeyStates.init(2)
        assertEquals(2, program.hotkeyStates.size)
        assertFailsWith<IndexOutOfBoundsException> { program.hotkeyStates.set(5, 1) }
    }

    // ------------------------------------------------------------------ 常量

    private companion object {
        const val FORE = 0xC0C0C0
        const val BACK = 0x000000
        const val FONT = "黑体"
        const val LINE_HEIGHT = 19
    }
}
